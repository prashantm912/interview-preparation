# PWA & Service Workers

[← Back to master index](../README.md)

A comprehensive, tier-based interview guide to Progressive Web Apps (PWAs) and Service Workers. It covers the service worker lifecycle, caching strategies and the Cache API, offline architecture, the Web App Manifest and installability, push notifications, background sync, IndexedDB, the app-shell model, update flows, and Workbox. Content is current through 2026, reflecting modern browser support, the `BackgroundSync` / `PeriodicSync` APIs, and current Workbox 7.x patterns.

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

### Q1. [Theory] What is a Progressive Web App (PWA)?

A **Progressive Web App** is a web application that uses modern web platform capabilities to deliver an app-like experience while remaining a website at its core. The "progressive" part means it enhances gracefully: it works in any browser, but in capable browsers it gains features like offline support, installability, and push notifications.

The three technical pillars of a PWA are:

1. **A Service Worker** — a background script that acts as a programmable network proxy, enabling offline support, caching, and push.
2. **A Web App Manifest** — a JSON file describing how the app should appear when installed (name, icons, theme color, display mode).
3. **HTTPS** — a secure origin is mandatory for service workers and most powerful APIs.

The goal is the set of qualities summarized by the acronym **FIRE**: **F**ast, **I**ntegrated (with the OS), **R**eliable (works offline / on flaky networks), and **E**ngaging (installable, push-capable). A well-built PWA can be installed to the home screen and launched like a native app, but ships as a single codebase served over the web.

### Q2. [Theory] What is a Service Worker and how does it differ from regular JavaScript on a page?

A **Service Worker** is a script the browser runs in the background, separate from any web page, on its own thread. It functions as a **programmable network proxy** sitting between your web app and the network, letting you intercept and handle network requests, manage a cache, and receive push messages even when no page is open.

Key differences from page JavaScript:

- **No DOM access.** A service worker cannot touch `window` or `document`. It communicates with pages via `postMessage`.
- **Runs on a separate thread**, so it never blocks the UI.
- **Event-driven and terminable.** The browser can kill it when idle and restart it on the next event (`fetch`, `push`, `sync`). You cannot rely on global in-memory state persisting between events.
- **HTTPS only** (except `localhost` for development).
- **Lifecycle-driven**, with distinct `install`, `activate`, and `fetch` phases.

```
   Page  ──fetch──▶  Service Worker  ──▶  Cache
                          │
                          └──────────────▶  Network
```

### Q3. [Theory] Why do service workers require HTTPS?

Because a service worker is a **man-in-the-middle by design** — it intercepts every network request for its scope and can return whatever it wants. If served over plain HTTP, a network attacker could inject a malicious service worker that would then persist and hijack all traffic for that origin, even across sessions. Requiring HTTPS (a *secure context*) guarantees the script was delivered untampered.

The one exception is `http://localhost` (and `127.0.0.1`), which browsers treat as a secure context to make local development practical without certificates.

### Q4. [Practical] How do you register a service worker?

You register it from your page's JavaScript, typically after the page loads so registration doesn't compete with critical resources. The registration returns a promise.

```javascript
if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js', { scope: '/' })
      .then((registration) => {
        console.log('SW registered, scope:', registration.scope);
      })
      .catch((error) => {
        console.error('SW registration failed:', error);
      });
  });
}
```

Notes:
- Always **feature-detect** with `'serviceWorker' in navigator`.
- The file path determines the **default scope**: `/sw.js` at the root can control the whole origin; `/js/sw.js` would only control `/js/` by default.
- Registration is idempotent — calling it on every page load is fine; the browser only installs a new worker if the script bytes changed.

### Q5. [Theory] What is the service worker lifecycle? Walk through install, activate, and fetch.

The lifecycle has three main phases:

1. **Install** — Fires once when a new (byte-different) service worker is downloaded. This is where you **pre-cache** your app shell. The worker is "installed" but not yet controlling pages.
2. **Activate** — Fires when the worker takes control. This is where you **clean up old caches** from previous versions. By default a new worker won't activate until all pages controlled by the old worker are closed (more on this with `skipWaiting`).
3. **Fetch** (and other functional events like `push`, `sync`) — Once active, the worker intercepts network requests within its scope.

```
download ──▶ install ──▶ (waiting) ──▶ activate ──▶ idle ⇄ fetch/push/sync
                            │                          │
                       skipWaiting()             (terminated when idle,
                       skips waiting             restarted on next event)
```

```javascript
self.addEventListener('install', (event) => {
  event.waitUntil(precacheAppShell());
});

self.addEventListener('activate', (event) => {
  event.waitUntil(cleanupOldCaches());
});

self.addEventListener('fetch', (event) => {
  event.respondWith(handleRequest(event.request));
});
```

### Q6. [Theory] What is the `install` event used for?

The `install` event is the right place to **pre-cache the static assets** that make up your application shell — HTML, CSS, JS, fonts, the app icon — so the app can load instantly and work offline. You wrap the caching work in `event.waitUntil()` so the browser keeps the worker alive until caching completes, and treats the install as failed (discarding the new worker) if the promise rejects.

```javascript
const CACHE_NAME = 'app-shell-v1';
const APP_SHELL = ['/', '/index.html', '/styles.css', '/app.js', '/offline.html'];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(APP_SHELL))
  );
});
```

If any URL in `addAll` fails to fetch (e.g., a 404), the whole install fails — making it an all-or-nothing integrity check on your shell.

### Q7. [Theory] What is the `activate` event used for?

The `activate` event is where you do **cleanup that's unsafe while the old worker is still running** — chiefly deleting outdated caches. When you ship a new version you typically bump the cache name (`app-shell-v2`); on activate you delete every cache that isn't in your current allowlist so old assets don't pile up and consume storage quota.

```javascript
const CURRENT_CACHES = ['app-shell-v2'];

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((names) =>
      Promise.all(
        names
          .filter((name) => !CURRENT_CACHES.includes(name))
          .map((name) => caches.delete(name))
      )
    )
  );
});
```

### Q8. [Theory] What is the Cache API and how does it relate to service workers?

The **Cache API** is a browser storage mechanism for **Request/Response pairs**, exposed through the global `caches` object (`CacheStorage`). It's the storage backbone for offline support. Although most commonly used inside a service worker, it's actually available on the `window` too.

Key points:
- It stores complete HTTP `Response` objects (headers + body), keyed by `Request` (or URL string).
- It's **promise-based** and asynchronous.
- It is **not** automatically managed — you decide what goes in, what comes out, and when to evict. The browser may evict entire caches under storage pressure, but never partially.
- A `Response` body is a stream that can only be read once, so you `.clone()` it before both caching and returning.

```javascript
const cache = await caches.open('my-cache');
await cache.put('/data.json', new Response('{"hello":"world"}'));
const match = await cache.match('/data.json');
```

### Q9. [Practical] How do you intercept a fetch request and serve from cache?

You listen for the `fetch` event and call `event.respondWith()` with a `Response` (or a promise resolving to one). This is the heart of offline support.

```javascript
self.addEventListener('fetch', (event) => {
  event.respondWith(
    caches.match(event.request).then((cached) => {
      // Return cached response if present, otherwise hit the network
      return cached || fetch(event.request);
    })
  );
});
```

Important: if you call `event.respondWith()`, **you** are responsible for the response. If you don't call it at all, the browser handles the request normally (default network behavior). You should generally only handle `GET` requests and let `POST`/`PUT` pass through.

### Q10. [Theory] What is the "cache-first" strategy and when should you use it?

**Cache-first** (also called *cache, falling back to network*) checks the cache first; only if there's no match does it go to the network. It gives the fastest possible response and full offline support.

```
request ──▶ cache hit? ──yes──▶ return cached
                │
                no
                ▼
            network ──▶ return (and optionally cache)
```

Use it for **static, versioned, immutable assets** — hashed JS/CSS bundles, fonts, images, the app shell. These rarely change, and when they do, the URL changes (cache-busting), so stale content isn't a concern. It's the wrong choice for frequently-changing data like API responses or user content, where you'd serve stale data.

### Q11. [Theory] What is the "network-first" strategy and when should you use it?

**Network-first** tries the network first and falls back to the cache only when the network fails (offline or timeout). It prioritizes **freshness** over speed.

```
request ──▶ network ok? ──yes──▶ return (and update cache)
                │
                no / timeout
                ▼
            cache ──▶ return cached (or offline fallback)
```

Use it for content that should be **as fresh as possible but still available offline** — API calls for a news feed, an inbox, dashboards. The cost is a slower response when online (you always pay a network round trip first), often mitigated with a timeout that falls back to cache after, say, 3 seconds.

### Q12. [Theory] What is the "stale-while-revalidate" strategy?

**Stale-while-revalidate** returns the cached response **immediately** (fast) while simultaneously fetching a fresh copy from the network **in the background** to update the cache for next time. The user gets an instant response now and fresh content on the *next* visit.

```
request ──▶ return cached immediately ──▶ user sees content fast
                │
                └──(in parallel)──▶ network ──▶ update cache
```

It's an excellent default for resources where slightly stale is acceptable but you still want to converge to fresh — avatars, non-critical API data, content that updates occasionally. The trade-off is that users see the previous version for one cycle.

### Q13. [Coding] Implement a stale-while-revalidate handler.

```javascript
async function staleWhileRevalidate(request, cacheName = 'swr-cache') {
  const cache = await caches.open(cacheName);
  const cached = await cache.match(request);

  // Kick off the network fetch regardless; update the cache when it lands.
  const networkPromise = fetch(request).then((response) => {
    if (response && response.status === 200) {
      cache.put(request, response.clone());
    }
    return response;
  }).catch(() => cached); // if network fails, the cached copy is still fine

  // Return cached immediately if available, else wait for the network.
  return cached || networkPromise;
}

self.addEventListener('fetch', (event) => {
  if (event.request.method === 'GET') {
    event.respondWith(staleWhileRevalidate(event.request));
  }
});
```

Note `response.clone()` — the response body is a one-shot stream, so you clone before putting it in the cache while still returning the original.

### Q14. [Practical] How do you provide an offline fallback page?

Pre-cache an `offline.html` during install, then serve it when a navigation request fails on the network. Scoping the fallback to navigation requests (full page loads) is the common pattern.

```javascript
self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open('offline-v1').then((cache) => cache.add('/offline.html'))
  );
});

self.addEventListener('fetch', (event) => {
  if (event.request.mode === 'navigate') {
    event.respondWith(
      fetch(event.request).catch(() =>
        caches.match('/offline.html')
      )
    );
  }
});
```

`event.request.mode === 'navigate'` identifies top-level page navigations, so you only show the offline page for actual page loads, not for sub-resources.

### Q15. [Theory] What is the Web App Manifest and what goes in it?

The **Web App Manifest** is a JSON file (typically `manifest.webmanifest` or `manifest.json`) that tells the browser how your app should behave when installed. It's linked from your HTML:

```html
<link rel="manifest" href="/manifest.webmanifest">
```

A minimal manifest:

```json
{
  "name": "My Awesome App",
  "short_name": "Awesome",
  "start_url": "/?source=pwa",
  "display": "standalone",
  "background_color": "#ffffff",
  "theme_color": "#0a84ff",
  "icons": [
    { "src": "/icons/192.png", "sizes": "192x192", "type": "image/png" },
    { "src": "/icons/512.png", "sizes": "512x512", "type": "image/png" },
    { "src": "/icons/maskable.png", "sizes": "512x512", "type": "image/png", "purpose": "maskable" }
  ]
}
```

Key fields: `name`/`short_name` (home-screen label), `start_url` (what opens on launch), `display` (`standalone`, `fullscreen`, `minimal-ui`, or `browser`), `theme_color` (toolbar/status-bar color), `background_color` (splash screen), and `icons` (including a `maskable` icon so Android can shape it to the device's icon mask).

### Q16. [Theory] What are the requirements for a PWA to be installable ("add to home screen")?

For browsers to offer installation, the app generally must meet these criteria:

1. Served over **HTTPS** (secure context).
2. Has a **valid Web App Manifest** with at least `name`/`short_name`, a `start_url`, a `display` of `standalone`/`fullscreen`/`minimal-ui`, and icons (Chrome requires at least a 192px and 512px icon, or one with `purpose: any`).
3. Has a **registered service worker** with a `fetch` handler (so the app is capable of offline behavior — Chrome historically required this).
4. (Engagement heuristics) The user has interacted with the site.

When met, Chrome fires the `beforeinstallprompt` event, letting you show a custom install button. On iOS Safari, installation is manual via the Share menu ("Add to Home Screen").

### Q17. [Practical] How do you trigger a custom install prompt?

Capture the `beforeinstallprompt` event, prevent the default mini-infobar, stash the event, and call `prompt()` later in response to a user gesture (e.g., clicking your "Install" button).

```javascript
let deferredPrompt = null;

window.addEventListener('beforeinstallprompt', (e) => {
  e.preventDefault();              // stop the automatic mini-infobar
  deferredPrompt = e;             // save for later
  showInstallButton();
});

installButton.addEventListener('click', async () => {
  if (!deferredPrompt) return;
  deferredPrompt.prompt();         // must be in response to a user gesture
  const { outcome } = await deferredPrompt.userChoice;
  console.log('User choice:', outcome); // 'accepted' | 'dismissed'
  deferredPrompt = null;           // can only be used once
  hideInstallButton();
});

window.addEventListener('appinstalled', () => {
  console.log('PWA installed');
});
```

### Q18. [Theory] What is service worker scope and how is it determined?

**Scope** defines which URLs a service worker controls. By default, the scope is the **directory the script lives in**. A worker at `/sw.js` defaults to scope `/` (whole origin); one at `/app/sw.js` defaults to `/app/` and can only intercept requests under `/app/`.

You can narrow the scope at registration, but you **cannot widen it above the script's directory** unless the response includes the `Service-Worker-Allowed` header. This is a security boundary: a script in a subdirectory shouldn't be able to hijack the whole origin.

```javascript
// Allowed: narrower or equal to script directory
navigator.serviceWorker.register('/sw.js', { scope: '/app/' });

// To register /js/sw.js with scope '/', the server must send:
//   Service-Worker-Allowed: /
```

The common best practice is to serve the service worker from the **root** so it can control the entire app.

### Q19. [Theory] What is the difference between a Web Worker and a Service Worker?

Both run JavaScript off the main thread, but they serve different purposes:

| Aspect | Web Worker | Service Worker |
|---|---|---|
| Purpose | Offload CPU-heavy computation | Network proxy, caching, offline, push |
| Lifetime | Tied to the page that created it; dies with the tab | Independent of pages; survives tab close, restarted on events |
| Instances | One per `new Worker()` call | One per scope, shared across all tabs |
| Network interception | No | Yes (`fetch` events) |
| Lifecycle events | None special | install / activate / fetch / push / sync |
| Use case | Image processing, parsing, crypto | PWA offline, push notifications, background sync |

In short: use a **Web Worker** to keep the UI responsive during heavy work; use a **Service Worker** to make the app reliable, installable, and offline-capable. (A *Shared Worker* is a third type, shared across tabs of the same origin, but used far less often.)

---

## 🟡 Intermediate (3–7 yrs)

### Q20. [Theory] Explain the "waiting" state and why a new service worker doesn't activate immediately.

When you deploy an updated service worker, the browser installs it but puts it in a **waiting** state. It will not activate and take control while the *old* worker still controls any open page (clients). This is intentional: it guarantees that all tabs of your app run **one consistent version** of the service worker, preventing a situation where tab A uses old caching logic while tab B uses new logic against the same caches.

```
old SW (active) ──controls──▶ open tabs
new SW (installed) ──▶ WAITING ... until all tabs close
                                  │
                              then ──▶ activate
```

The new worker activates only after every controlled tab is closed (or you call `skipWaiting()`). This is why users sometimes need to fully close all tabs to get a PWA update.

### Q21. [Theory] What does `skipWaiting()` do and what's the risk of using it?

`self.skipWaiting()` tells a waiting service worker to **activate immediately**, bypassing the wait for existing tabs to close. It's typically called in the `install` handler.

```javascript
self.addEventListener('install', (event) => {
  self.skipWaiting();
  event.waitUntil(precache());
});
```

The **risk**: if you skip waiting while pages are open, those pages were loaded with the *old* assets but are now controlled by the *new* worker. If the new worker serves a different, incompatible bundle (e.g., new JS that expects new API shapes, or old lazy-loaded chunks that no longer exist), you can get **runtime errors or mismatched chunks** in already-open tabs. Safer patterns: call `skipWaiting()` only after prompting the user to reload, or pair it with versioned/hashed assets and a reload-on-`controllerchange`.

### Q22. [Theory] What does `clients.claim()` do?

By default, even after a service worker activates, it does **not control pages that were already loaded** before it took over — they remain uncontrolled until their next navigation/reload. `self.clients.claim()` (called in `activate`) makes the newly activated worker **take control of all in-scope clients immediately**, including the page that registered it.

```javascript
self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim());
});
```

It's commonly used on first install so the very first page load benefits from the service worker without a refresh. Combined with `skipWaiting()`, it gives "activate and control everything right now" semantics — convenient for first-load offline support, but with the same versioning caveats as `skipWaiting`.

### Q23. [Practical] How do you implement a network-first strategy with a timeout fallback to cache?

You race the network fetch against a timer; if the network doesn't respond in time, you fall back to cache.

```javascript
function networkFirstWithTimeout(request, timeoutMs = 3000, cacheName = 'api-cache') {
  return new Promise((resolve) => {
    let settled = false;

    const timer = setTimeout(async () => {
      if (settled) return;
      settled = true;
      const cached = await caches.match(request);
      resolve(cached || new Response('Offline', { status: 503 }));
    }, timeoutMs);

    fetch(request).then(async (response) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      const cache = await caches.open(cacheName);
      cache.put(request, response.clone());
      resolve(response);
    }).catch(async () => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      const cached = await caches.match(request);
      resolve(cached || new Response('Offline', { status: 503 }));
    });
  });
}
```

This balances freshness with resilience: fast networks get fresh data, slow/offline ones fall back to the last cached copy after the timeout.

### Q24. [Theory] What is the app shell architecture?

The **app shell** is the minimal HTML, CSS, and JavaScript required to power the user interface "chrome" — the header, navigation, and layout scaffolding — *without* any dynamic content. The idea: cache the shell aggressively (cache-first) so it loads **instantly and offline**, then populate it with dynamic content fetched separately (often network-first or from IndexedDB).

```
┌──────────────────────────────┐
│  App Shell (cached, instant) │
│  ┌────────────────────────┐  │
│  │ header / nav (static)  │  │
│  ├────────────────────────┤  │
│  │  dynamic content       │◀─┼── fetched from network / IndexedDB
│  │  (loaded after shell)  │  │
│  └────────────────────────┘  │
└──────────────────────────────┘
```

This separation gives a native-app-like instant first paint on repeat visits and is the classic model for single-page PWAs. With modern frameworks and streaming SSR it's evolved, but the principle — cache the stable skeleton, stream in the volatile content — still holds.

### Q25. [Theory] What is IndexedDB and why use it in a PWA instead of localStorage?

**IndexedDB** is a transactional, asynchronous, object-oriented database built into the browser. It stores structured JavaScript objects (and Blobs/Files) in object stores, supports indexes for querying, and can hold large amounts of data.

Why prefer it over `localStorage` in PWAs:

| | localStorage | IndexedDB |
|---|---|---|
| API | Synchronous (blocks main thread) | Asynchronous |
| Capacity | ~5–10 MB | Hundreds of MB to GB (quota-based) |
| Data types | Strings only | Structured objects, Blobs, Files |
| Querying | Key lookup only | Indexes, ranges, cursors |
| Available in SW | No | Yes |

Crucially, **`localStorage` is not available inside a service worker** (it's synchronous and DOM-bound), whereas IndexedDB is — making IndexedDB the storage of choice for offline data, queued requests, and background sync payloads. Most teams use a thin wrapper like **`idb`** to make it promise-friendly.

### Q26. [Coding] Show how to read and write data with IndexedDB using the `idb` library.

```javascript
import { openDB } from 'idb';

const dbPromise = openDB('app-db', 1, {
  upgrade(db) {
    const store = db.createObjectStore('todos', { keyPath: 'id' });
    store.createIndex('byDone', 'done');
  },
});

async function addTodo(todo) {
  const db = await dbPromise;
  await db.put('todos', todo);          // insert or update
}

async function getAllTodos() {
  const db = await dbPromise;
  return db.getAll('todos');
}

async function getPendingTodos() {
  const db = await dbPromise;
  return db.getAllFromIndex('todos', 'byDone', false);
}
```

The `upgrade` callback runs only when the version number increases, and is where you create object stores and indexes — the equivalent of a schema migration.

### Q27. [Theory] What is Background Sync and what problem does it solve?

**Background Sync** (the `SyncManager` API) lets you **defer an action until the user has stable connectivity**. When a user performs an action offline (sends a message, submits a form), instead of failing, you register a sync; the browser fires a `sync` event in your service worker once connectivity returns — even if the user has navigated away or closed the tab.

```javascript
// In the page: register a sync after queuing the action in IndexedDB
async function queueMessage(msg) {
  await saveToIndexedDB(msg);
  const reg = await navigator.serviceWorker.ready;
  await reg.sync.register('send-messages');
}

// In the service worker: handle the sync when connectivity returns
self.addEventListener('sync', (event) => {
  if (event.tag === 'send-messages') {
    event.waitUntil(flushQueuedMessages());
  }
});
```

It solves the "lost action on flaky network" problem — the user fires and forgets, and the platform guarantees eventual delivery with retry/backoff. Note browser support is uneven (Chromium yes; Safari/Firefox limited), so you should still have a fallback path.

### Q28. [Theory] What is the difference between Background Sync and Periodic Background Sync?

- **Background Sync** (one-off): retries a **specific deferred task** as soon as connectivity is available. Triggered by a user action that needs to reach the server. Tag-based, fires once (with retries on failure).
- **Periodic Background Sync** (`periodicSync`): fires at **browser-controlled intervals** (e.g., roughly daily) to **proactively refresh content** — pre-fetching the morning's news, updating cached data — so the app is fresh on next open. Requires the app to be **installed** and the browser gates frequency based on site engagement.

```javascript
// Periodic sync registration (requires permission + installed PWA)
const reg = await navigator.serviceWorker.ready;
await reg.periodicSync.register('refresh-content', {
  minInterval: 24 * 60 * 60 * 1000, // hint: ~once a day
});
```

In short: one-off sync = "make sure this *thing I did* gets sent"; periodic sync = "keep my content fresh in the background." Periodic sync has narrower browser support.

### Q29. [Theory] How do push notifications work in a PWA? Outline the full flow.

Push relies on three parties: your **web app/service worker**, your **application server**, and a browser **push service** (e.g., FCM for Chrome, Mozilla's for Firefox). The flow:

```
1. App asks user for notification permission.
2. App subscribes via PushManager → gets a PushSubscription (endpoint + keys).
3. App sends that subscription to YOUR server, which stores it.
4. Later, your server sends an (encrypted) message to the push service endpoint,
   authenticated with VAPID.
5. Push service delivers it to the browser, which wakes the service worker.
6. SW's 'push' event fires → SW calls showNotification().
7. User clicks → SW's 'notificationclick' event → open/focus the app.
```

The two web standards involved are the **Push API** (subscribing and receiving) and the **Notifications API** (displaying). **VAPID** keys identify and authenticate your server to the push service. The service worker is essential because it receives pushes even when no tab is open.

### Q30. [Coding] Subscribe a user to push notifications.

```javascript
async function subscribeToPush() {
  // 1. Ask permission
  const permission = await Notification.requestPermission();
  if (permission !== 'granted') throw new Error('Permission denied');

  // 2. Subscribe via the service worker's PushManager
  const reg = await navigator.serviceWorker.ready;
  const subscription = await reg.pushManager.subscribe({
    userVisibleOnly: true,                      // must show a notification per push
    applicationServerKey: urlBase64ToUint8Array(PUBLIC_VAPID_KEY),
  });

  // 3. Send the subscription to your server to store
  await fetch('/api/save-subscription', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(subscription),
  });
}

// VAPID keys are base64url; PushManager wants a Uint8Array
function urlBase64ToUint8Array(base64) {
  const padding = '='.repeat((4 - (base64.length % 4)) % 4);
  const raw = atob((base64 + padding).replace(/-/g, '+').replace(/_/g, '/'));
  return Uint8Array.from([...raw].map((c) => c.charCodeAt(0)));
}
```

`userVisibleOnly: true` is required by browsers — you must show a visible notification for each push (no silent background pushes), preventing abuse.

### Q31. [Coding] Handle the `push` and `notificationclick` events in the service worker.

```javascript
self.addEventListener('push', (event) => {
  const data = event.data ? event.data.json() : {};
  const title = data.title || 'New notification';
  const options = {
    body: data.body,
    icon: '/icons/192.png',
    badge: '/icons/badge.png',
    data: { url: data.url || '/' },   // stash data for the click handler
    actions: [{ action: 'open', title: 'Open' }],
  };
  event.waitUntil(self.registration.showNotification(title, options));
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const targetUrl = event.notification.data.url;
  event.waitUntil(
    clients.matchAll({ type: 'window', includeUncontrolled: true }).then((wins) => {
      // Focus an existing tab on that URL if open, else open a new one
      const existing = wins.find((w) => w.url === targetUrl);
      if (existing) return existing.focus();
      return clients.openWindow(targetUrl);
    })
  );
});
```

### Q32. [Practical] How do you communicate between a page and its service worker?

There are several channels:

1. **`postMessage` + `message` event** — direct messaging in either direction.
2. **`MessageChannel`** — for request/response with a dedicated reply port.
3. **`BroadcastChannel`** — fan-out to all clients and the SW.

```javascript
// Page → Service Worker
navigator.serviceWorker.controller?.postMessage({ type: 'CLEAR_CACHE' });

// Service Worker → all pages
self.addEventListener('message', (event) => {
  if (event.data.type === 'CLEAR_CACHE') {
    event.waitUntil(caches.delete('api-cache'));
  }
});

async function broadcast(message) {
  const all = await self.clients.matchAll();
  for (const client of all) client.postMessage(message);
}
```

A common use is telling the page "a new version is ready" or telling the SW "the user clicked update, call skipWaiting now."

### Q33. [Practical] How do you detect and prompt the user when a new service worker version is available?

Listen for `updatefound` on the registration, then watch the installing worker's state. When it becomes `installed` *and* there's already a controller, an update is waiting.

```javascript
const reg = await navigator.serviceWorker.register('/sw.js');

reg.addEventListener('updatefound', () => {
  const newWorker = reg.installing;
  newWorker.addEventListener('statechange', () => {
    if (newWorker.state === 'installed' && navigator.serviceWorker.controller) {
      // A new version is waiting — show "Update available" UI
      showUpdateBanner(() => {
        newWorker.postMessage({ type: 'SKIP_WAITING' });
      });
    }
  });
});

// Reload once the new SW takes control
let reloading = false;
navigator.serviceWorker.addEventListener('controllerchange', () => {
  if (reloading) return;
  reloading = true;
  window.location.reload();
});
```

In the SW: `self.addEventListener('message', (e) => { if (e.data.type === 'SKIP_WAITING') self.skipWaiting(); });`

### Q34. [Theory] What is Workbox and what does it give you over hand-written service workers?

**Workbox** is Google's production-grade library/toolset for building service workers. Hand-rolling a service worker is error-prone (cache versioning, expiration, edge cases); Workbox encapsulates the battle-tested patterns:

- **Routing** — declarative request matching (`registerRoute`).
- **Strategies** — ready-made `CacheFirst`, `NetworkFirst`, `StaleWhileRevalidate`, `NetworkOnly`, `CacheOnly`.
- **Plugins** — `ExpirationPlugin` (max entries / max age), `CacheableResponsePlugin` (only cache certain statuses), `BackgroundSyncPlugin`.
- **Precaching** — `precacheAndRoute` with a build-time manifest of hashed files, handling revision-based cache busting automatically.
- **Build tooling** — `workbox-build`, `workbox-webpack-plugin`, `vite-plugin-pwa` to generate/inject the precache manifest.

It dramatically reduces boilerplate and bugs, and is the de-facto standard (used under the hood by frameworks' PWA plugins).

### Q35. [Coding] Configure caching strategies with Workbox.

```javascript
import { precacheAndRoute } from 'workbox-precaching';
import { registerRoute } from 'workbox-routing';
import { CacheFirst, NetworkFirst, StaleWhileRevalidate } from 'workbox-strategies';
import { ExpirationPlugin } from 'workbox-expiration';
import { CacheableResponsePlugin } from 'workbox-cacheable-response';

// Precache the build manifest (hashed assets), injected at build time
precacheAndRoute(self.__WB_MANIFEST);

// Images: cache-first with expiration
registerRoute(
  ({ request }) => request.destination === 'image',
  new CacheFirst({
    cacheName: 'images',
    plugins: [
      new CacheableResponsePlugin({ statuses: [0, 200] }),
      new ExpirationPlugin({ maxEntries: 60, maxAgeSeconds: 30 * 24 * 60 * 60 }),
    ],
  })
);

// API: network-first so data stays fresh, cache as offline fallback
registerRoute(
  ({ url }) => url.pathname.startsWith('/api/'),
  new NetworkFirst({ cacheName: 'api', networkTimeoutSeconds: 3 })
);

// Static CSS/JS not in precache: stale-while-revalidate
registerRoute(
  ({ request }) => ['style', 'script'].includes(request.destination),
  new StaleWhileRevalidate({ cacheName: 'static-resources' })
);
```

### Q36. [Theory] What is `__WB_MANIFEST` and precaching in Workbox?

`self.__WB_MANIFEST` is a **placeholder** that Workbox's build step replaces with an array of `{ url, revision }` entries for all your build assets. `precacheAndRoute(self.__WB_MANIFEST)` then:

1. Pre-caches every listed file on install.
2. Uses the `revision` (or the hash already in the filename) to know when a file changed and needs re-caching.
3. Sets up routing so those precached files are served cache-first.

```javascript
// What the build injects in place of self.__WB_MANIFEST:
precacheAndRoute([
  { url: '/index.html', revision: 'a1b2c3' },
  { url: '/app.4f8e.js', revision: null },  // hashed name → no revision needed
]);
```

This automates the hardest part of offline support — knowing exactly which files make up "the app" and invalidating them correctly when you deploy. The manifest is generated by `workbox-build`, the webpack plugin, or `vite-plugin-pwa`.

### Q37. [Practical] How do you handle caching for cross-origin / opaque responses?

When you fetch a cross-origin resource without CORS (e.g., a third-party image with `mode: 'no-cors'`), you get an **opaque response**: status `0`, no readable headers or body. You *can* cache and serve it, but with caveats:

- You **cannot inspect** whether it succeeded — a 404 or 500 looks the same as a 200.
- Opaque responses are **padded** in the storage quota (they count as much larger than their real size — often ~7 MB each in some browsers), so caching many can blow your quota.

Guard against caching failures by checking `response.ok` for same-origin/CORS responses, and only cache opaque responses deliberately:

```javascript
const response = await fetch(request);
if (response.status === 200 || response.type === 'opaque') {
  // be deliberate about opaque — they bloat quota and hide errors
  cache.put(request, response.clone());
}
```

With Workbox, the `CacheableResponsePlugin({ statuses: [0, 200] })` explicitly opts into caching opaque (status 0) responses.

### Q38. [Theory] What are the storage limits for a PWA and how does eviction work?

Browsers grant origins a **storage quota** that's a fraction of available disk (commonly up to ~60% of free space, varying by browser). The Cache API, IndexedDB, and other storage share this quota. You can inspect it:

```javascript
const { usage, quota } = await navigator.storage.estimate();
console.log(`Using ${usage} of ${quota} bytes`);
```

Two eviction modes:
- **Best-effort** (default): under storage pressure the browser may **evict your origin's data entirely** (LRU across origins). The Cache API evicts whole caches, never partial entries.
- **Persistent**: request `navigator.storage.persist()`. If granted (often gated on installation/engagement), your data is **exempt from automatic eviction** and only removed if the user manually clears it.

For data you can't afford to lose (offline queues, user drafts), request persistence and design for eviction by treating the cache as disposable.

---

## 🟠 Advanced (8–12 yrs)

### Q39. [Theory] Describe a robust update flow that avoids breaking already-open tabs.

The failure mode: `skipWaiting()` makes the new SW serve new (hashed) assets to a tab that loaded old HTML/JS, so lazy-loaded chunks 404 or API contracts mismatch. A robust flow:

1. **Default to non-skip-waiting.** Let the new SW wait; don't auto-activate.
2. **Detect the waiting worker** via `updatefound` / `statechange` and show an unobtrusive "Update available — Reload" banner.
3. **On user click**, post `SKIP_WAITING` to the waiting worker, which calls `self.skipWaiting()`.
4. **Listen for `controllerchange`** and reload the page once — now HTML and SW are consistent.
5. **Version your assets with content hashes** so old chunks remain available long enough for in-flight loads; keep a few old precache versions before deleting.

```
new SW installed (waiting) ──▶ banner shown
        user clicks "Reload"
                │
       postMessage SKIP_WAITING ──▶ SW skipWaiting()
                │
       controllerchange fires ──▶ window.reload() (once)
                │
            fresh, consistent app
```

This gives users control over *when* the disruption happens and guarantees HTML/JS/SW consistency after reload.

### Q40. [Theory] How do you handle service worker updates when the SW file itself is cached by HTTP?

A subtle deployment bug: if your server sends a long `Cache-Control: max-age` for `sw.js`, the browser's HTTP cache may serve a **stale service worker file**, and users never get updates. Mitigations:

- Browsers now **bypass the HTTP cache** for the main service worker script on update checks if its `max-age` is over 24 hours, and you can opt into always bypassing via the `updateViaCache: 'none'` registration option.

```javascript
navigator.serviceWorker.register('/sw.js', { updateViaCache: 'none' });
```

- Best practice: serve `sw.js` (and `manifest`) with `Cache-Control: no-cache` (or short max-age) so the browser always revalidates.
- Note the SW *script* should not be hashed in its filename — its URL must stay stable so the browser can detect byte changes; cache-busting belongs on the *assets it precaches*, not the SW itself.

### Q41. [Practical] How would you implement a background-sync outbox for offline form submissions?

The pattern: persist the request to IndexedDB, register a sync, and replay from the SW when connectivity returns.

```javascript
// PAGE: enqueue and request a sync
async function submitOffline(formData) {
  const db = await openDB('outbox-db', 1, {
    upgrade: (d) => d.createObjectStore('requests', { autoIncrement: true }),
  });
  await db.add('requests', { url: '/api/submit', body: formData, ts: Date.now() });
  const reg = await navigator.serviceWorker.ready;
  if ('sync' in reg) await reg.sync.register('outbox');
  else await replayNow(); // fallback for unsupported browsers
}

// SERVICE WORKER: drain the outbox on sync
self.addEventListener('sync', (event) => {
  if (event.tag === 'outbox') event.waitUntil(drainOutbox());
});

async function drainOutbox() {
  const db = await openDB('outbox-db', 1);
  const tx = db.transaction('requests', 'readwrite');
  const store = tx.objectStore('requests');
  let cursor = await store.openCursor();
  while (cursor) {
    try {
      await fetch(cursor.value.url, { method: 'POST', body: JSON.stringify(cursor.value.body) });
      await cursor.delete();        // remove only on success
    } catch {
      // leave in store; sync will retry with backoff
      return;
    }
    cursor = await cursor.continue();
  }
}
```

Workbox's `BackgroundSyncPlugin` (backed by its `Queue`) does exactly this with retries and backoff out of the box.

### Q42. [Theory] What are the security implications of service workers and how do you mitigate them?

Service workers are powerful and persistent, which makes them an attractive target:

- **Persistence/hijacking** — a malicious or compromised SW can intercept all in-scope traffic indefinitely. Mitigated by the **HTTPS requirement** and the **scope** restrictions (can't widen above script directory without `Service-Worker-Allowed`).
- **Cache poisoning** — if you cache responses indiscriminately (including error or attacker-influenced responses), you can serve bad content offline. Mitigate by validating `response.ok`/status before caching and scoping what you cache.
- **Supply-chain risk** — a compromised build that ships a bad SW persists across visits. Mitigate with SRI on imported scripts, careful CI, and a way to **unregister/kill** a bad SW (ship a "self-destruct" SW that unregisters itself and clears caches).
- **Sensitive data in cache** — the Cache API and IndexedDB are readable by any script on the origin and persist on disk. Don't cache secrets/tokens; clear sensitive caches on logout.

```javascript
// "Kill switch" SW to recover from a bad deploy
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', async () => {
  await self.registration.unregister();
  const keys = await caches.keys();
  await Promise.all(keys.map((k) => caches.delete(k)));
  const clients = await self.clients.matchAll();
  clients.forEach((c) => c.navigate(c.url));
});
```

### Q43. [Theory] How does caching strategy choice interact with cache invalidation and content hashing?

The two questions "which strategy?" and "how do I invalidate?" are coupled:

- **Hashed/immutable assets** (`app.4f8e.js`) → **cache-first** is safe forever, because a content change yields a new URL. Invalidation is implicit: the new HTML references a new filename; the old file simply stops being requested. Serve these with `Cache-Control: immutable, max-age=31536000`.
- **Stable-URL, mutable resources** (HTML, `/api/...`) → **never** cache-first without expiry, or users get stuck on old content with no URL change to break the cache. Use **network-first** or **stale-while-revalidate** with an `ExpirationPlugin` (max-age) so entries don't live forever.
- **The HTML document** is the critical pivot: it must update for users to ever see new hashed assets. Cache it network-first (or revalidate it) so a deploy propagates; if the HTML is stuck cache-first, your whole app is frozen at an old version.

Rule of thumb: *hash what you can, cache-first the hashed, network-first the stable-URL'd, and always expire the mutable.*

### Q44. [Behavioral] Tell me about a time you had to debug a tricky service worker / caching issue in production.

A strong answer follows STAR and shows judgment about the unique hazards of SWs:

- **Situation:** "After a deploy, a subset of users reported a blank screen / old version, but we couldn't reproduce it — because *our* browsers had fresh caches."
- **Task:** "Identify why some clients were stuck and ship a fix without bricking more users, given the SW persists across visits."
- **Action:** "I reproduced by hard-loading the old SW, found we were cache-first'ing `index.html`, so users never fetched the new HTML pointing at new hashed bundles — and their old lazy chunks had been purged, causing chunk-load errors. I switched the HTML route to network-first, shipped a corrected SW, and deployed a temporary update banner plus a `controllerchange` reload so stuck users would recover on next visit. I kept the previous two precache versions so in-flight chunk loads wouldn't 404."
- **Result:** "Error rate dropped to zero within a day as clients updated; I added a runbook and a 'kill-switch SW' to our toolkit, and we adopted network-first for documents as policy."

The interviewer is checking that you understand SW persistence, the HTML-as-pivot insight, recovery mechanics, and that you de-risked rather than just pushed a fix.

### Q45. [Practical] How do you test and debug service workers?

A layered approach:

- **DevTools → Application → Service Workers**: see registration state, force "Update on reload," "Bypass for network," and "Unregister." The **Cache Storage** and **IndexedDB** panels let you inspect/clear stored data.
- **`chrome://serviceworker-internals`** for deeper diagnostics and the ability to start/stop workers.
- **Lighthouse** PWA audit verifies installability, offline behavior, and manifest correctness in CI.
- **Update on reload** during dev so you don't fight the waiting state; pair with **"Disable cache"** off so you actually exercise the SW cache.
- **Automated tests**: Workbox ships testing utilities; tools like Puppeteer/Playwright can drive a real browser, toggle offline (`page.context().setOffline(true)`), and assert the app still works.
- **Unregister + clear storage** between manual test runs to avoid stale state poisoning results.

```javascript
// Playwright: assert offline behavior
await context.setOffline(true);
await page.reload();
await expect(page.getByText('My Awesome App')).toBeVisible();
```

### Q46. [Theory] How would you architect offline-first data sync with conflict resolution?

An offline-first app treats local storage as the source of truth for reads and queues writes for eventual server reconciliation. Key elements:

1. **Local store (IndexedDB)** holds the working copy; the UI reads from it for instant, offline-capable rendering.
2. **An outbound queue** (outbox) records mutations with client-generated IDs and timestamps/version vectors.
3. **Sync engine** (often in the SW via Background Sync) replays the queue when online and pulls server changes.
4. **Conflict resolution policy**: last-write-wins (simple, lossy), server-wins, client-wins, or merge via **CRDTs/operational transforms** for collaborative data. Use version vectors or `updatedAt` to detect conflicts.
5. **Idempotency**: client IDs / idempotency keys so retried writes don't duplicate.

```
UI ⇄ IndexedDB (local truth)
        │  writes
        ▼
     Outbox (queued mutations w/ clientId + version)
        │  Background Sync
        ▼
     Server  ──conflict?──▶ resolution policy (LWW / merge / CRDT)
        │
        └──pull remote changes──▶ reconcile into IndexedDB
```

The hard part is conflicts; libraries like RxDB, PouchDB/CouchDB, or CRDT toolkits (Yjs, Automerge) productize this. The interviewer wants to hear that you'd pick a resolution policy deliberately based on the data's semantics, not hand-wave "just merge it."

---

## 🔴 Expert (15+ yrs)

### Q47. [Theory] When is a PWA the wrong choice versus a native or hybrid app, and how do you make that call?

A senior engineer frames this as a capability/UX/economics trade-off, not dogma:

- **PWA wins** when reach, instant access (no install friction), low distribution cost, single codebase, and easy updates matter — content, commerce, productivity, internal tools.
- **PWA struggles** when you need: deep OS integration (rich background processing, advanced Bluetooth/USB on all platforms, certain sensors), guaranteed background execution, App Store discoverability/trust, or **iOS-specific gaps** — historically Safari has limited push (improved with web push for installed PWAs from iOS 16.4+), background sync, and storage durability. Heavy 3D/AR/native-grade performance also favors native.
- **Decision approach:** enumerate required capabilities, check them against current platform support (especially the weakest target — usually iOS Safari), weigh distribution and update velocity, and consider **hybrid** (Capacitor/Tauri) to ship the web codebase in a store wrapper when you need both. I'd also factor team skills and whether a single web codebase materially lowers TCO.

The mark of seniority is citing *current, platform-specific* support facts and tying the choice to business constraints rather than "PWAs can do everything now."

### Q48. [Theory] How do you reason about the service worker as a distributed-systems caching layer?

Treat the SW as a **per-client edge cache** with all the classic distributed-caching concerns, just on the device:

- **Consistency model:** what staleness is acceptable? cache-first = eventual/weak; network-first = strong-ish but availability-degrading offline; SWR = read-your-writes-eventually. Choose per resource.
- **Invalidation:** the hardest problem — solved via content-hash URLs (immutable) for assets and TTL/revalidation for mutable data; the HTML document is your cache-coherence pivot.
- **Cache stampede / thundering herd** is mostly N/A (per-client), but you can still over-fetch on activate; dedupe in-flight requests in the SW.
- **Quota & eviction** = capacity management; design for eviction (treat cache as disposable, persist only what you request `persist()` for).
- **Coherence across tabs:** all tabs share one SW and one CacheStorage, so a write in one tab is visible to others — use `BroadcastChannel` to notify UIs.

Framing it this way shows you can transfer backend caching intuition (TTLs, invalidation, consistency vs. availability) to the client edge, and that you choose strategies as deliberate consistency/availability trade-offs.

### Q49. [Practical] How would you roll out a service worker safely to millions of users, including rollback?

Because a SW persists on the client, a bad one can't be "recalled" — you can only ship a *better* one. So safety is about controlled propagation and a guaranteed recovery path:

1. **Stage behind a flag / percentage rollout** at the registration layer (e.g., only register the new SW for X% of sessions, server-driven) so a regression hits a small cohort.
2. **Bake in observability**: report SW errors, cache hit/miss, install/activate failures, and chunk-load errors to your telemetry so you detect regressions fast.
3. **Conservative update policy**: no auto-`skipWaiting`; user-prompted update + `controllerchange` reload, preserving N previous precache versions to avoid chunk 404s.
4. **`updateViaCache: 'none'`** and `no-cache` on `sw.js` so fixes propagate promptly.
5. **Rollback = roll forward**: keep a **kill-switch SW** ready (unregisters, clears caches, reloads clients) and the ability to redeploy the previous-good SW. Because clients only update on visit, communicate that recovery is gradual.
6. **Pre-flight in CI** with Lighthouse + automated offline E2E so the broken SW never reaches prod.

The senior signal is acknowledging that **you can't force-uninstall a SW**, so the architecture must make every version recoverable by the *next* version, plus percentage rollout to bound blast radius.

### Q50. [Behavioral] Describe how you'd lead a team's adoption of PWA capabilities across a large existing web app.

A strong answer balances technical strategy with org change:

- **Start with value, not tech:** identify the user pain (slow repeat loads, offline needs, re-engagement) and pick a high-ROI first capability (e.g., precache the app shell for instant loads) rather than "let's add a service worker."
- **De-risk incrementally:** ship the manifest + installability and a *passive* SW (no fetch handling) first to validate the deploy/HTTP-cache pipeline, then layer caching strategies behind a percentage rollout with telemetry.
- **Codify guardrails:** adopt Workbox (don't hand-roll), document the update flow, network-first-the-HTML policy, kill-switch SW, and a runbook — so the team avoids the classic footguns.
- **Build shared understanding:** because SW bugs are uniquely sticky, run a brown-bag on lifecycle/caching pitfalls and add Lighthouse + offline E2E gates to CI so quality is enforced, not tribal.
- **Measure:** define success metrics (TTI on repeat visit, offline success rate, install rate, push opt-in) and review them; cut scope that doesn't move them.

The interviewer is assessing that you can drive adoption safely (given the persistence hazard), invest in guardrails and education, and tie the initiative to measurable user/business outcomes rather than chasing a checklist.

---

## 🧩 Extended Questions — Set 1: Deeper theory & internals

### 🟢 — extended

#### Q51. [Theory] What is `event.waitUntil()` actually doing under the hood, and what happens if you forget it?

`event.waitUntil(promise)` extends the **lifetime of the event** (and, by extension, of the service worker) until the promise settles. The browser treats a service worker as disposable: it spins one up to dispatch an event and is free to terminate it the moment the event handler returns synchronously. `waitUntil` tells the browser "this event isn't really done until this async work finishes — keep me alive."

Concretely, in `install` it delays the transition to *installed* (and a rejection fails the install, discarding the worker). In `activate` it delays the transition to *activated*, so the worker isn't considered ready to handle fetches until your cache cleanup completes. In `push`/`sync` it keeps the worker alive while you do async work.

If you forget it, any async work started in the handler races against worker termination. A classic bug:

```javascript
// BROKEN: install "completes" immediately; caching may be killed mid-flight
self.addEventListener('install', () => {
  caches.open('v1').then((cache) => cache.addAll(APP_SHELL)); // not awaited by the event
});

// CORRECT
self.addEventListener('install', (event) => {
  event.waitUntil(caches.open('v1').then((cache) => cache.addAll(APP_SHELL)));
});
```

The first form may "work" on a fast machine and fail intermittently on a slow one — exactly the kind of flaky, hard-to-reproduce bug service workers are notorious for.

#### Q52. [Theory] How does `event.respondWith()` differ from `event.waitUntil()`?

They look similar but answer different questions:

- **`respondWith(responseOrPromise)`** (FetchEvent only) **provides the response** for the intercepted request. Calling it tells the browser "I'm handling this fetch; use what I give you." It must be called **synchronously** within the event handler (you can pass a promise, but you can't `await` something first and then call it). Not calling it lets the request fall through to default network handling.
- **`waitUntil(promise)`** (extendable events) **extends the worker's lifetime** for side-effect work — it does not affect the response.

A common pattern uses both together: `respondWith` to serve from cache fast, and `waitUntil` to perform a background cache update that outlives the response.

```javascript
self.addEventListener('fetch', (event) => {
  event.respondWith(caches.match(event.request).then((c) => c || fetch(event.request)));
  // keep the worker alive for a background revalidation even after responding
  event.waitUntil(revalidate(event.request));
});
```

The key trap: calling `respondWith` after an `await` throws, because the browser has already moved on. Decide synchronously, do async work inside the promise you pass.

#### Q53. [Theory] What does it mean that a service worker has "no persistent global state," and why is the global scope unreliable?

A service worker's global scope (`self`, module-level variables) is **ephemeral**. The browser terminates an idle worker — typically after tens of seconds of inactivity — to save memory and battery, then restarts it fresh when the next event arrives. On restart, the script re-executes top-to-bottom, so any variable you mutated at runtime resets to its initial value.

```javascript
let requestCount = 0; // resets to 0 every time the worker restarts

self.addEventListener('fetch', (event) => {
  requestCount++; // unreliable — this counter loses its value between events
});
```

Implications:
- Don't keep counters, auth tokens, in-memory queues, or "is this the first run" flags in globals. Persist them in **IndexedDB** (or the Cache API).
- Top-level code runs on **every** startup, so keep it cheap; expensive setup repeated on each wake hurts.
- Variables set during `install` are gone by the time a later `fetch` fires in a new worker instance.

The mental model: treat the global scope like a serverless function's — fresh per invocation, with durable state living in storage.

#### Q54. [Theory] What is a `Client`, and what's the difference between `Client`, `WindowClient`, and a "controlled" page?

A **`Client`** represents an execution context the service worker can talk to — a page, a worker, or a `SharedWorker`. `WindowClient` is the subtype for browser tabs/windows and adds window-specific abilities like `focus()` and `navigate()`. You enumerate them with `self.clients.matchAll()` and get one by id with `self.clients.get(id)`.

A page is **controlled** when a service worker is intercepting its requests (`navigator.serviceWorker.controller` is non-null on that page). A page can exist and be *in scope* yet be **uncontrolled** — for example, the very first load before `clients.claim()`, or a page loaded while no SW was registered.

```javascript
self.addEventListener('notificationclick', (event) => {
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((wins) => {
      const open = wins.find((w) => w.url.includes('/inbox'));
      return open ? open.focus() : self.clients.openWindow('/inbox');
    })
  );
});
```

`includeUncontrolled: true` matters: without it, `matchAll` only returns pages this SW controls, so freshly opened or not-yet-claimed tabs would be invisible — and you'd wrongly open a duplicate window.

#### Q55. [Practical] How do you unregister a service worker and fully clear its caches programmatically?

You unregister from a page via the registration object and delete caches via `CacheStorage`. Unregistering stops the SW from controlling *future* loads but does not affect already-controlled pages until they reload.

```javascript
async function nukeServiceWorkerAndCaches() {
  // 1. Unregister every registration for this scope
  const regs = await navigator.serviceWorker.getRegistrations();
  await Promise.all(regs.map((r) => r.unregister()));

  // 2. Delete all named caches
  const names = await caches.keys();
  await Promise.all(names.map((n) => caches.delete(n)));

  // 3. Optionally clear IndexedDB too (per-DB)
  // indexedDB.deleteDatabase('app-db');

  // 4. Reload so the now-uncontrolled page fetches fresh from network
  window.location.reload();
}
```

This is the manual recovery path users perform via DevTools ("Unregister" + "Clear storage"); wiring it to a hidden "reset app" button is handy for support. Remember unregistration is asynchronous and only fully takes effect once no client is controlled.

#### Q56. [Theory] Why must `Notification.requestPermission()` and the install prompt be tied to a user gesture?

Both are **abuse-prone** capabilities, so browsers gate them behind **transient user activation** — a recent genuine user interaction like a click or tap. Without that gate, sites would spam permission dialogs and install prompts on page load, training users to reflexively dismiss them and harming the whole ecosystem.

For notifications, modern Chrome and Firefox **require a user gesture** to even show the permission prompt; calling `Notification.requestPermission()` from a top-level `load` handler is ignored or auto-denied. For installation, `deferredPrompt.prompt()` throws or no-ops unless called within a gesture handler.

```javascript
// Good: prompt in direct response to a click
enableNotificationsBtn.addEventListener('click', async () => {
  const result = await Notification.requestPermission(); // gesture present
  if (result === 'granted') await subscribeToPush();
});
```

The deeper principle is **transient activation**: the gesture grants a short-lived (a few seconds) "permission budget" that powerful APIs consume. Best practice is also to show your own *contextual* pre-prompt explaining the value before triggering the real OS dialog, since a denied permission is sticky and hard to recover.

#### Q57. [Theory] What is the difference between `cache.add`, `cache.addAll`, and `cache.put`?

All three write to a cache, but with different semantics:

- **`cache.add(request)`** — fetches the request from the network and stores the response. Convenience for "go get this and cache it." Rejects on a non-ok (e.g., 404) response, so it doubles as a fetch-and-validate.
- **`cache.addAll(requests)`** — same as `add` but atomic over a list: it fetches all, and if **any** fails the whole operation rejects and nothing is added. Ideal for precaching an app shell as an all-or-nothing unit.
- **`cache.put(request, response)`** — stores a response **you already have**, doing no fetch. This is what you use after a manual `fetch` (e.g., to cache a `clone()` in stale-while-revalidate), and it's the only one that lets you cache a synthetic or modified `Response`, or a non-ok response if you deliberately want to.

```javascript
await cache.add('/logo.png');                 // fetch + store, throws on 404
await cache.addAll(['/', '/app.js', '/x.css']); // atomic precache
const res = await fetch('/api/data');
await cache.put('/api/data', res.clone());     // store an already-fetched response
```

The crucial distinction: `add`/`addAll` perform the network request for you and reject on failure; `put` is the low-level primitive that just stores, accepting whatever response you hand it.

#### Q58. [Practical] How do you scope a service worker to control pages above its own directory?

By default a worker's max scope is its **own directory**, so `/js/sw.js` can only control `/js/`. To let it control a broader path (e.g., the whole origin `/`), the server must send the **`Service-Worker-Allowed`** response header on the script request, and you pass a matching `scope` at registration.

```http
# Response headers for GET /js/sw.js
Service-Worker-Allowed: /
```

```javascript
// Now this is permitted because the header authorizes the wider scope
navigator.serviceWorker.register('/js/sw.js', { scope: '/' });
```

Without the header, that registration rejects with a security error. The header is a deliberate server-side opt-in: it ensures only the origin owner (who controls response headers) can authorize a sub-directory script to escape its directory, preventing, say, a user-uploaded script in `/uploads/` from hijacking the whole site. In practice the simpler path is to just serve `sw.js` from the root and avoid the header entirely.

#### Q59. [Theory] What does the `Response.type` property tell you, and what are its possible values?

`Response.type` describes the **provenance and CORS status** of a response, which governs what you can read from it:

- **`basic`** — same-origin; full access to headers and body.
- **`cors`** — cross-origin fetched with CORS and the server allowed it; you can read the body but only a limited, CORS-safelisted set of headers.
- **`opaque`** — cross-origin fetched with `mode: 'no-cors'`; status appears as `0`, headers and body are **not readable**, and it's heavily padded in quota. You can cache and replay it but can't inspect success.
- **`opaqueredirect`** — a redirect response captured with `redirect: 'manual'`; you can't read it, only pass it through.
- **`error`** — a network error; throws if you try to use it.

```javascript
const res = await fetch(url, { mode: 'no-cors' });
console.log(res.type, res.status); // "opaque" 0 — can't tell if it really succeeded
```

This is why caching logic so often checks `response.ok` (which is `false` for opaque, since status 0 isn't 200–299) and treats opaque responses as a special, deliberate case.

### 🟡 — extended

#### Q60. [Theory] Walk through the precise state machine of a service worker registration: parsed, installing, installed/waiting, activating, activated, redundant.

A registration exposes up to three worker slots — `installing`, `waiting`, `active` — and each `ServiceWorker` object moves through a `state` machine:

1. **`parsed`** — the script downloaded and parsed without syntax errors; it's a valid worker but no lifecycle event has run.
2. **`installing`** — the `install` event is dispatched; `event.waitUntil` keeps it here until its promise settles. Sits in `registration.installing`.
3. **`installed`** (a.k.a. **waiting**) — install succeeded. If an active worker still controls clients, it parks in `registration.waiting`. If there's no active worker (first install), it proceeds.
4. **`activating`** — the `activate` event is dispatched; `waitUntil` holds it here (this is where old caches are cleaned and `clients.claim()` may run). Sits in `registration.active` during this phase.
5. **`activated`** — fully in control; handles `fetch`/`push`/`sync`.
6. **`redundant`** — superseded by a newer worker, or it failed to install/activate. A dead end.

```
parsed → installing → installed(waiting) → activating → activated
                          │                                  │
                          └──────────── redundant ◀──────────┘ (replaced / failed)
```

Knowing this maps directly to debugging: a worker "stuck" in `installed` means it's *waiting* (old clients open); one in `redundant` means install or activate threw, or it was replaced. You observe these via `registration.installing/waiting/active` and `worker.state` + `statechange`.

#### Q61. [Theory] How does the browser decide a service worker is "byte-different" and needs updating, and when do update checks happen?

On every navigation to an in-scope page (and at most once per ~24h for `updateViaCache` reasons, plus when you call `registration.update()`), the browser **re-fetches the SW script** and performs a **byte-for-byte comparison** with the currently installed version. If even one byte differs, it treats it as a new worker and kicks off the install lifecycle. Importantly, the comparison historically covered the main script; modern browsers also re-check **imported scripts** (`importScripts`) and ES module imports for changes.

Triggers for an update check:
- A navigation within scope (the most common one).
- An explicit `registration.update()` call.
- A `push` or `sync` event waking the worker (functional events trigger a check if >24h since last).

```javascript
// Force a check, e.g., on a long-lived SPA that rarely navigates
const reg = await navigator.serviceWorker.ready;
setInterval(() => reg.update(), 60 * 60 * 1000); // hourly
```

Two practical consequences: (1) a long-lived SPA that never does a full navigation may **never check for updates** unless you call `update()` yourself; (2) the byte comparison is why you must **not** put a content hash in the SW filename — the URL must stay stable while the bytes change, or the browser sees a brand-new registration each deploy instead of an update to the existing one.

#### Q62. [Coding] Implement a cache-then-network pattern where the page renders cached data instantly and updates when the network response arrives.

This UI pattern (distinct from SW-side SWR) lets the *page* request both the cache and network in parallel and reconcile in the DOM:

```javascript
// In the page
async function loadArticles() {
  const cache = await caches.open('api-cache');
  let networkResolved = false;

  // 1. Show cached data immediately if present
  const cachedRes = await cache.match('/api/articles');
  if (cachedRes) {
    const cachedData = await cachedRes.json();
    render(cachedData, { stale: true });
  }

  // 2. Fetch fresh in parallel; update the cache and the UI when it lands
  try {
    const netRes = await fetch('/api/articles');
    networkResolved = true;
    await cache.put('/api/articles', netRes.clone());
    const freshData = await netRes.json();
    render(freshData, { stale: false }); // overwrites the stale render
  } catch (err) {
    if (!cachedRes) render({ error: 'offline' }); // nothing cached AND offline
  }
}

function render(data, { stale }) {
  // Guard against the network "winning the race" but arriving before cache, etc.
  document.body.dataset.dataStale = String(stale);
  // ...paint the list...
}
```

The subtlety is the **race**: cache usually wins (it's local) so the user sees content instantly, then the network render replaces it. You should make the second render idempotent and handle the case where the network resolves before the cache read completes.

#### Q63. [Theory] What is the `updateViaCache` registration option and what do its three values mean?

`updateViaCache` controls whether the **browser's HTTP cache** is consulted when the browser fetches the SW script (and its imported scripts) during an update check. It has three values:

- **`imports`** (the default) — the main SW script bypasses the HTTP cache, but `importScripts()`-imported scripts may be served from the HTTP cache. This was the historical default and a footgun for cached imports.
- **`all`** — both the main script and its imports may be served from the HTTP cache. The most aggressive; risks serving a stale SW if you set a long `max-age`.
- **`none`** — neither the main script nor imports use the HTTP cache; the browser always revalidates against the server. The safest for ensuring updates ship promptly.

```javascript
navigator.serviceWorker.register('/sw.js', { updateViaCache: 'none' });
```

Paired guidance: serve `sw.js` with `Cache-Control: no-cache` regardless, but `updateViaCache: 'none'` is belt-and-suspenders that doesn't depend on getting the server header right. It directly addresses the classic "users never get updates because the SW file is HTTP-cached" bug.

#### Q64. [Theory] How does a `PushSubscription` work cryptographically — what are the `p256dh` and `auth` keys for, and what is VAPID?

When you call `pushManager.subscribe()`, the browser generates an **ECDH key pair** on the P-256 curve for that subscription and returns:

- **`endpoint`** — the unique URL on the push service to POST messages to.
- **`p256dh`** — the subscription's **public key**, used by your server to derive a shared secret and **encrypt the payload** (per the Message Encryption for Web Push / RFC 8291 scheme) so the push service can't read the message contents.
- **`auth`** — a 16-byte **authentication secret** mixed into the encryption key derivation, binding the ciphertext to this subscription.

**VAPID** (Voluntary Application Server Identification, RFC 8292) is a *separate* mechanism: your server signs a JWT with its own private key and includes the matching public key (the `applicationServerKey` you passed at subscribe time). This **authenticates your server to the push service**, letting it reject pushes from anyone who isn't you and giving the push service a contact identity.

```javascript
const sub = await reg.pushManager.subscribe({
  userVisibleOnly: true,
  applicationServerKey: vapidPublicKey, // ties the subscription to YOUR server
});
const json = sub.toJSON();
// { endpoint, keys: { p256dh, auth } }  → send to your server
```

So two crypto layers coexist: **VAPID** proves *who is sending* (server → push service), while **p256dh/auth** provide **end-to-end payload encryption** (server → browser) so the push service is a blind relay.

#### Q65. [Practical] How do you handle `pushsubscriptionchange` and keep server-side subscriptions valid?

Push subscriptions can be **invalidated by the browser** — on key rotation, browser updates, or when the push service expires them — firing a `pushsubscriptionchange` event in the service worker. If you ignore it, you'll keep pushing to a dead endpoint (getting `410 Gone`) and the user silently stops receiving notifications.

```javascript
self.addEventListener('pushsubscriptionchange', (event) => {
  event.waitUntil((async () => {
    // Re-subscribe with the same VAPID key
    const newSub = await self.registration.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: APPLICATION_SERVER_KEY,
    });
    // Tell the server: replace old with new
    await fetch('/api/push/resubscribe', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        oldEndpoint: event.oldSubscription?.endpoint ?? null,
        newSubscription: newSub.toJSON(),
      }),
    });
  })());
});
```

Complementary server hygiene: when a push send returns **404/410**, delete that subscription from your store. Between client-side `pushsubscriptionchange` re-subscription and server-side cleanup on `410`, you keep your subscription table accurate. Note `event.oldSubscription`/`newSubscription` support is uneven, so also key resubscription by user identity, not just endpoint.

#### Q66. [Theory] What is the difference between `navigator.serviceWorker.ready` and `navigator.serviceWorker.controller`?

They answer different questions about SW availability:

- **`navigator.serviceWorker.ready`** is a **promise** that resolves with the `ServiceWorkerRegistration` once a worker is **active** for the page's scope — regardless of whether it controls *this* page. Use it when you need to *use* the registration (subscribe to push, register a sync). It never rejects; it waits.
- **`navigator.serviceWorker.controller`** is a **synchronous reference** to the worker currently **controlling this page** (intercepting its fetches), or `null` if the page is uncontrolled. Use it to check "are my requests going through a SW right now?" and to `postMessage` the controlling worker.

```javascript
const reg = await navigator.serviceWorker.ready; // active worker exists
reg.pushManager.subscribe(/* ... */);

if (navigator.serviceWorker.controller) {
  navigator.serviceWorker.controller.postMessage({ type: 'PING' }); // controls this page
}
```

The gap matters on **first load**: right after the first registration, `ready` will resolve (a worker becomes active), but `controller` is still `null` until either the page reloads or the worker calls `clients.claim()`. So "active" ≠ "controlling this specific page."

#### Q67. [Coding] Implement request/response messaging between page and service worker using `MessageChannel`.

`postMessage` alone is fire-and-forget. For a true request→response (e.g., "SW, what version are you?"), use a `MessageChannel` and pass one of its ports as a transferable, so the SW can reply on a dedicated channel:

```javascript
// PAGE: send a message and await a reply
function askServiceWorker(message) {
  return new Promise((resolve, reject) => {
    const channel = new MessageChannel();
    channel.port1.onmessage = (event) => {
      if (event.data.error) reject(event.data.error);
      else resolve(event.data);
    };
    // Transfer port2 to the SW so it can reply via port2 → our port1
    navigator.serviceWorker.controller.postMessage(message, [channel.port2]);
  });
}

const { version } = await askServiceWorker({ type: 'GET_VERSION' });

// SERVICE WORKER: reply on the provided port
self.addEventListener('message', (event) => {
  if (event.data.type === 'GET_VERSION') {
    event.ports[0].postMessage({ version: CACHE_VERSION });
  }
});
```

`event.ports[0]` is the transferred `port2`; posting to it delivers back to the page's `port1`. This pattern underpins libraries like `workbox-window`, which wraps it to give promise-based SW communication. For broadcasting to *all* clients instead, use `BroadcastChannel`.

#### Q68. [Theory] How does `clients.claim()` interact with the `controllerchange` event, and why can it cause an unexpected reload loop?

When a newly activated worker calls `self.clients.claim()`, every in-scope uncontrolled page suddenly gains a controller, which fires **`controllerchange`** on `navigator.serviceWorker` in those pages. Teams commonly listen for `controllerchange` to reload the page so it runs under the new worker:

```javascript
let refreshing = false;
navigator.serviceWorker.addEventListener('controllerchange', () => {
  if (refreshing) return; // guard against multiple fires
  refreshing = true;
  window.location.reload();
});
```

The **reload-loop hazard**: if your `activate` handler always calls `clients.claim()` *and* your page always reloads on `controllerchange`, then on first load the claim fires `controllerchange` → page reloads → (if logic re-triggers claim/activation under some conditions) it can loop. The `refreshing` guard above prevents the immediate double-reload; the deeper fix is to only reload on `controllerchange` when you intentionally triggered an update (i.e., the user accepted a "new version" prompt), not on the benign first-load claim. Distinguish "first controller acquired" (no reload needed) from "controller replaced by an update" (reload appropriate).

### 🟠 — extended

#### Q69. [Theory] What is "navigation preload" and what specific performance problem does it solve?

When a navigation request hits a service worker that isn't already running, the browser must **boot the worker** (parse and execute the script) before the `fetch` handler can even start the network request. For a network-first navigation, this **serializes** worker startup *before* the request, adding latency on every cold navigation.

**Navigation Preload** (`registration.navigationPreload`) fixes this by letting the browser **start the navigation network request in parallel** with booting the service worker. The handler then consumes the already-in-flight response via `event.preloadResponse`.

```javascript
self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    if (self.registration.navigationPreload) {
      await self.registration.navigationPreload.enable();
    }
  })());
});

self.addEventListener('fetch', (event) => {
  if (event.request.mode !== 'navigate') return;
  event.respondWith((async () => {
    const preload = await event.preloadResponse; // request already started during SW boot
    if (preload) return preload;
    return fetch(event.request);
  })());
});
```

The win is largest on resource-constrained devices where SW startup is slow. You can also send a custom header (`Service-Worker-Navigation-Preload`) so the server can tailor the preloaded response (e.g., return only the content fragment, since the shell is cached).

#### Q70. [Theory] How does the Cache API's storage quota padding for opaque responses actually affect capacity planning?

Browsers deliberately **pad the recorded size of opaque (cross-origin, no-cors) responses** so a site can't infer a cross-origin resource's real byte size via quota probing (a side-channel that could leak, e.g., whether a user is logged into another site based on response size). The padding is large — historically on the order of several megabytes per opaque entry in Chromium regardless of the resource's true size.

Capacity consequences:
- Caching, say, 50 opaque third-party images can consume **hundreds of MB of quota** even if the images total a few MB, potentially triggering eviction of your *whole* origin's storage.
- `navigator.storage.estimate()` reflects the **padded** usage, not real bytes, so your numbers won't match the sum of actual file sizes.

Mitigations: fetch cross-origin resources with **CORS** (`crossorigin` attribute / `mode: 'cors'`) when the third party allows it, so you get a non-padded `cors` response; cap opaque caches tightly with Workbox's `ExpirationPlugin({ maxEntries })`; and avoid precaching large numbers of opaque assets. The lesson for senior planning: opaque responses are "expensive" in quota terms in a way that's invisible if you only count real bytes.

#### Q71. [Practical] How would you implement cache versioning and migration so a deploy never serves a half-old/half-new mix of assets?

The goal is **atomic switchover**: clients run entirely on vN or entirely on vN+1, never a Frankenstein mix. Approach:

1. **Namespace caches by version** (`shell-v3`, `api-v3`) so the new worker writes to brand-new caches and never mutates the old ones.
2. **Precache the full new shell during `install`**, atomically (`addAll`) — if any asset 404s, install fails and the old worker keeps serving the consistent old set.
3. **Switch reads to the new caches only in `activate`** (after install fully succeeded), then delete non-allowlisted old caches.
4. **Keep N-1 (and maybe N-2) precache versions briefly** so in-flight lazy-chunk loads from already-open old tabs don't 404 during the transition.

```javascript
const VERSION = 'v3';
const ALLOWLIST = [`shell-${VERSION}`, `api-${VERSION}`];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(`shell-${VERSION}`).then((c) => c.addAll(APP_SHELL)));
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    const keys = await caches.keys();
    await Promise.all(
      keys.filter((k) => !ALLOWLIST.includes(k) && !k.endsWith('-v2')) // keep previous one cycle
          .map((k) => caches.delete(k))
    );
    await self.clients.claim();
  })());
});
```

Because the new caches are only *read* after a clean install+activate, a partially failed deploy can never produce a mixed set: the worker either fully transitions or stays fully on the old version. Workbox's revision-keyed precache manifest implements exactly this atomic-by-version model for you.

#### Q72. [Theory] How do you architect a service worker so it can be reliably terminated and restarted mid-operation without data loss?

Because the browser can kill the worker at any idle moment (and even between `await`s if `waitUntil` lapses), design every operation to be **resumable from durable storage**, not from in-memory progress:

1. **Persist intent before acting.** Write the queued mutation/request to IndexedDB *first*, then attempt the network. If the worker dies mid-send, the record survives and a future `sync` retries it.
2. **Make replays idempotent.** Tag each queued write with a client-generated idempotency key so a retry after an ambiguous failure doesn't double-apply server-side.
3. **Delete only on confirmed success.** Remove the queue entry **after** a 2xx, inside the same logical step, so a crash before confirmation leaves it to be retried.
4. **Anchor long work with `waitUntil`.** Keep the relevant event alive while draining the queue, but don't assume it'll stay alive forever — break work into small, individually-committed units.
5. **Recover on startup.** On `activate`/first `sync`, scan the queue and resume; never rely on a flag held only in a global variable.

```javascript
async function drain() {
  const db = await openDB('outbox', 1);
  let cursor = await db.transaction('q').store.openCursor();
  while (cursor) {
    const item = cursor.value;
    const res = await fetch(item.url, { method: 'POST', headers: { 'Idempotency-Key': item.id }, body: item.body });
    if (res.ok) await db.delete('q', cursor.key); // commit per-item
    else break; // leave the rest; sync will retry
    cursor = await cursor.continue();
  }
}
```

The discipline mirrors crash-safe server design: **write-ahead the intent, act, confirm, then commit** — so termination at any point is just a pause, not a loss.

#### Q73. [Practical] What goes wrong when a service worker and the page disagree about which assets exist, and how do you prevent chunk-load errors after a deploy?

The failure: a tab loaded `index.html` referencing `main.a1b2.js`, which lazy-loads `chunk.c3d4.js` on demand. You deploy a new build with `main.e5f6.js` and `chunk.g7h8.js`, and your `activate` handler deletes the old precache. Now the still-open old tab tries to lazy-load `chunk.c3d4.js`, but it's gone from cache **and** removed from the server — the dynamic `import()` rejects with a **chunk-load error**, crashing a route.

Prevention combines several tactics:
- **Keep old chunks available** on the server (don't delete previous build assets immediately; expire after, say, a week) so in-flight loads resolve over the network.
- **Retain N-1 precache versions** in the SW so even offline old tabs can still fetch their chunks.
- **Don't `skipWaiting` silently;** prompt the user to reload so HTML and SW move together, eliminating the mismatch window.
- **Add a chunk-load-error handler** in the app that, on a dynamic-import failure, forces a one-time `location.reload()` to pick up the new HTML.

```javascript
// App-level recovery for stale chunk references
window.addEventListener('vite:preloadError', (e) => { // or webpack's equivalent
  if (!sessionStorage.getItem('chunkReloaded')) {
    sessionStorage.setItem('chunkReloaded', '1');
    location.reload();
  }
});
```

The root cause is treating the SW cache and the deployed assets as independently mutable; the fix is to **version them together and overlap old/new during the transition** so no open client is ever stranded.

#### Q74. [Theory] How do `BroadcastChannel`, `clients.matchAll()` messaging, and `MessageChannel` differ for SW↔page communication, and when do you pick each?

All three move messages, but with different topologies and trade-offs:

| Mechanism | Topology | Reply semantics | Best for |
|---|---|---|---|
| `client.postMessage` via `clients.matchAll()` | SW → specific/all controlled clients | One-way (need a return channel for replies) | SW notifying pages ("update ready", "cache cleared") |
| `MessageChannel` (ports) | Point-to-point with a dedicated reply port | Built-in request→response | RPC-style "ask the SW a question, get an answer" |
| `BroadcastChannel` | Many-to-many fan-out across same-origin contexts (pages *and* SW) | One-way pub/sub | Cross-tab + SW state sync without enumerating clients |

Decision guide:
- Need a **response** to a specific message? Use `MessageChannel` (or `workbox-window`, which wraps it).
- Need to **push an event from the SW to whatever tabs are open**? Enumerate with `clients.matchAll({ includeUncontrolled: true })` and `postMessage` each.
- Need **all tabs to react to a shared event** (e.g., "logged out in one tab → clear UI in all") and you don't want to enumerate clients or care about controlled-vs-not? Use `BroadcastChannel`.

```javascript
// BroadcastChannel: SW and all tabs subscribe to the same named channel
const bc = new BroadcastChannel('app-events');
bc.postMessage({ type: 'CACHE_UPDATED', url: '/api/feed' });
bc.onmessage = (e) => { if (e.data.type === 'CACHE_UPDATED') refetch(e.data.url); };
```

A subtle factor: `BroadcastChannel` inside the SW only delivers while the worker is alive, and it doesn't wake a terminated worker — so for "must process" events prefer `postMessage` to clients or a `waitUntil`-anchored flow.

#### Q75. [Theory] How does Workbox's `BackgroundSyncPlugin`/`Queue` implement durable retries, and what does it give you over hand-rolling Background Sync?

`workbox-background-sync` wraps the raw `SyncManager` with a durable, replay-safe queue so failed requests are persisted and retried automatically:

1. **Persistence in IndexedDB.** Each failed (or intercepted) request is **serialized** — URL, method, headers, body, and a timestamp — into an IndexedDB-backed `Queue`, surviving worker termination and browser restarts.
2. **Registration of a sync tag.** The `Queue` registers a Background Sync; when the browser fires `sync`, Workbox **replays queued requests in order**, removing each on success and re-queuing on failure for the next sync (the platform handles exponential backoff).
3. **`maxRetentionTime`.** Requests older than a configurable window are discarded rather than retried forever, so a stale write doesn't replay days later with surprising effects.
4. **Fallback when sync is unsupported.** On browsers without Background Sync, Workbox replays the queue **on the next page load / SW startup** instead, so you still get eventual delivery.

```javascript
import { registerRoute } from 'workbox-routing';
import { NetworkOnly } from 'workbox-strategies';
import { BackgroundSyncPlugin } from 'workbox-background-sync';

const bgSync = new BackgroundSyncPlugin('post-queue', {
  maxRetentionTime: 24 * 60, // minutes
});

registerRoute(
  ({ url }) => url.pathname === '/api/messages',
  new NetworkOnly({ plugins: [bgSync] }),
  'POST'
);
```

Over hand-rolling, you get **serialization correctness** (headers/body round-tripping, including `FormData`/blobs), **ordering**, **retention limits**, **idempotent removal on success**, and the **no-Background-Sync fallback** — all the fiddly, error-prone parts that make a naive outbox subtly broken.

#### Q76. [Practical] How do you debug "the service worker is serving stale content but I can't reproduce it"?

This is the signature SW bug — your machine has fresh caches, the user's is poisoned. A systematic approach:

1. **Reproduce the user's state, don't reset yours.** In DevTools → Application → Service Workers, *uncheck* "Update on reload" and turn *off* "Bypass for network" so you experience the SW as the user does. Load the **old** SW first (check out the previous deploy) to recreate the stuck state.
2. **Inspect what's cached vs. requested.** Application → Cache Storage: confirm whether `index.html` is cached and whether it points at old hashed bundles. The classic culprit is **cache-first on the HTML document**.
3. **Check the SW's HTTP caching.** Network tab: is `sw.js` served with a long `max-age`? If so the browser may never see the new SW. Verify `Cache-Control: no-cache` / `updateViaCache: 'none'`.
4. **Confirm update detection.** In `chrome://serviceworker-internals` or the Application panel, check `registration.waiting` — a worker stuck in *waiting* means updates install but never activate.
5. **Add telemetry going forward.** Log install/activate, the active cache version, and `controllerchange` to your analytics so next time you can *see* which version each client runs.

```javascript
self.addEventListener('activate', (e) => {
  e.waitUntil(reportTelemetry({ event: 'sw_activate', version: VERSION }));
});
```

The mental shortcut: stale content almost always traces to one of three things — **HTML cached cache-first**, **`sw.js` HTTP-cached**, or **a worker stuck waiting**. Check those three before anything else.

### 🔴 — extended

#### Q77. [Theory] Compare the service worker's caching/offline model with the deprecated AppCache — what specific design failures did service workers fix?

**AppCache** (the Application Cache manifest) was the first attempt at offline web, and its failure modes directly shaped the service worker design:

- **Declarative, inflexible manifest.** AppCache used a static `.appcache` manifest; you couldn't express per-request logic (cache-first vs. network-first vs. SWR). Service workers replace this with **imperative `fetch` handlers** — arbitrary JS deciding each request.
- **Notorious update model.** AppCache always served from cache first and fetched the manifest in the background; users got the new version only on the *second* reload, and the rules were full of counterintuitive gotchas (the master page implicitly cached, `FALLBACK`/`NETWORK` interactions). SW updates are explicit and observable via the lifecycle.
- **All-or-nothing failures.** A single 404 in the manifest silently broke the *entire* cache. SW precaching makes failure explicit (install rejects) and you control granularity.
- **No programmatic control or storage integration.** AppCache couldn't talk to IndexedDB, couldn't do push or background sync, and had no scripting hooks. Service workers are a general event-driven runtime that integrates with the whole storage and push stack.

The throughline: AppCache was **declarative and opinionated to the point of being unusable**, so the platform replaced it with a **low-level, imperative primitive** (the SW + Cache API) on top of which libraries like Workbox build the conveniences — "extensible web" philosophy. AppCache was removed from browsers; service workers are its successor in every sense.

#### Q78. [Theory] How would you reason about the consistency and availability trade-offs of a service worker cache through a CAP-theorem-like lens?

A per-client SW cache is effectively a **replica** that can diverge from the server "source of truth," so the same C/A tension applies on the device:

- **Cache-first = availability over consistency.** The replica answers locally, always available (even offline), but may serve stale data until something invalidates it. Acceptable only when the data is **immutable** (hashed assets) so "stale" can't happen, or staleness is harmless.
- **Network-first = consistency over availability.** You prefer the authoritative server copy; when the network partitions (offline), you *degrade* — falling back to a possibly-stale cache or an error. You're trading availability for freshness.
- **Stale-while-revalidate = tunable, read-your-writes-eventually.** Always available (serves the replica) and *converges* to consistent on the next cycle — bounded staleness of one fetch interval.

The senior framing: you're choosing a **per-resource consistency model**, exactly as you would for a distributed cache, and the **HTML document is your coherence pivot** — if it's served strongly-consistent (network-first/revalidated), new immutable assets propagate; if it's served available-first (cache-first), the whole client is frozen at an old version regardless of how fresh the assets *could* be. The unavoidable invariant is that an offline client cannot be both fresh and available for mutable data, so you decide, per data class, which property to sacrifice — and you make writes safe across partitions with an **idempotent outbox** (your AP write path) reconciled when the partition heals.

#### Q79. [Practical] Design an end-to-end offline-first sync system using CRDTs for a collaborative app, and explain where the service worker fits.

For multi-user collaborative editing offline, last-write-wins loses data, so use **CRDTs** (Conflict-free Replicated Data Types) that merge deterministically without coordination:

**Architecture:**
1. **CRDT document in memory + IndexedDB.** A library like **Yjs** or **Automerge** holds the shared state. Local edits apply optimistically and produce compact **update deltas** (not full documents).
2. **Local persistence.** Deltas are appended to IndexedDB so the document survives reloads and offline sessions; on startup you replay them to rebuild state.
3. **Outbox of pending deltas.** Unsent updates queue in IndexedDB keyed for idempotency.
4. **Service worker's role:** it's the **durable transport coordinator** — via Background Sync it flushes queued CRDT deltas to the server when connectivity returns, even if the tab closed mid-edit, and it can cache the app shell + initial document snapshot for instant offline open. (Live, low-latency collaboration still typically rides a WebSocket from the page; the SW handles the *durable, eventual* path and the *fetch* layer.)
5. **Server as relay + log.** The server broadcasts deltas to other clients and appends them to a log; because CRDT merges are **commutative, associative, and idempotent**, clients can receive deltas in any order, duplicated, after arbitrary delay, and still converge to identical state.

```
edit ──▶ Yjs doc (optimistic) ──▶ IndexedDB (delta log)
                                       │
                            ┌──────────┴───────────┐
                       WebSocket (live)     SW Background Sync (durable)
                            │                       │
                            └────────▶ Server (relay + log) ◀──┘
                                       │ broadcast deltas
                                       ▼
                              other clients merge (convergent)
```

**Why CRDTs over LWW/OT here:** they guarantee convergence with **no central coordinator and no transform server**, tolerate the exact reordering/duplication/delay that offline + retrying Background Sync introduce, and never silently drop a concurrent edit. The trade-offs to call out: CRDT metadata growth (tombstones), the need for snapshot/compaction, and that CRDTs ensure *convergence* but not *intent preservation*, so some UX-level merge rules still belong in app logic. The SW is deliberately scoped to **durability and transport**, not conflict resolution — keeping the merge semantics in the CRDT layer where they belong.

#### Q80. [Behavioral] How would you set engineering policy and guardrails for service workers across many teams in a large org, given that a bad SW can't be recalled?

The core constraint — **a deployed SW persists on the client and can only be fixed by a *newer* SW** — means policy must make every version recoverable and bound blast radius. As a staff-level lead I'd establish:

- **A single blessed SW toolchain.** Mandate **Workbox** (via `vite-plugin-pwa`/framework plugin) rather than hand-rolled workers, so teams inherit correct precache versioning, expiration, and background-sync queues instead of re-discovering the footguns. Provide an internal wrapper enforcing our defaults.
- **Codified non-negotiables.** Network-first (or revalidate) the **HTML document**; never silent `skipWaiting` (user-prompted updates + `controllerchange` reload); `sw.js` served `no-cache` with `updateViaCache: 'none'`; retain N-1 precache versions; ship a **kill-switch SW** in every app's repo from day one.
- **Mandatory observability.** Standardized telemetry for install/activate failures, active cache version, chunk-load errors, and SW error rates, with dashboards and alerts — because SW regressions are invisible to the deployer (fresh local caches).
- **Gradual, server-controlled rollout.** Registration gated behind a percentage flag so a regression hits a small cohort; staged ramp with automated rollback of the *flag* (not the artifact, since clients update lazily).
- **CI gates.** Lighthouse PWA audit + automated offline E2E (Playwright `setOffline`) blocking merge, so a broken SW never reaches prod.
- **Shared knowledge and an incident runbook.** Brown-bags on lifecycle/caching pitfalls, a documented recovery procedure (deploy kill-switch → confirm clients drain → roll forward), and an architecture review checkpoint for any new SW capability.

The leadership signal is recognizing that the usual "just roll back" safety net **doesn't exist** for service workers, so the org's guardrails must guarantee forward-recoverability, bounded exposure, and detection — turning a uniquely sticky failure mode into a managed, observable, gradually-propagating one.

## 🧩 Extended Questions — Set 2: Practical scenarios, troubleshooting & coding

### 🟢 — extended

#### Q81. [Practical] Your service worker registers fine but never controls the page on the first visit. What's happening and how do you fix it?

This is the single most common "it's not working" report, and it's **expected behavior, not a bug**. When a page first registers a service worker, that worker installs and activates, but the page that triggered the registration was **already loaded uncontrolled** — `navigator.serviceWorker.controller` stays `null` until the next navigation, because a worker only assumes control of clients that *start* under its control.

Two fixes depending on what you want:

- **Accept it**: the SW will control the page on the next reload/navigation. Fine for caching that only needs to help repeat visits.
- **Take control immediately** by pairing `skipWaiting()` (so the first worker doesn't wait) with `clients.claim()` (so it grabs the already-open page):

```javascript
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()));
```

To verify in code, check the controller before assuming caching is active:

```javascript
const reg = await navigator.serviceWorker.register('/sw.js');
await navigator.serviceWorker.ready;          // a worker is active
if (!navigator.serviceWorker.controller) {
  console.log('Active but not controlling THIS page yet — needs claim() or a reload');
}
```

The mental check: "active" (a worker exists for the scope) is not the same as "controlling this tab." On first load they diverge unless you `claim()`.

#### Q82. [Practical] You added a `fetch` handler but some requests still hit the network directly, bypassing the SW. Why?

Several legitimate reasons a request skips your handler:

1. **It's out of scope.** A worker at `/app/sw.js` (scope `/app/`) never sees requests to `/`, `/other/`, or cross-path resources.
2. **The page is uncontrolled** (see Q81) — on the very first load before claim/reload, no `fetch` events fire for it.
3. **You didn't call `event.respondWith()`.** If your handler returns without calling it (e.g., you `return` early for non-GET), the browser handles that request itself — which is correct, just not "through" your cache logic.
4. **Cross-origin requests** still fire `fetch` events, but if you only match same-origin URLs in your routing, they fall through.
5. **Range requests, `no-cors` quirks, and some browser-internal requests** can behave differently.

A quick diagnostic is to log every fetch unconditionally at the top of the handler:

```javascript
self.addEventListener('fetch', (event) => {
  console.log('[SW] saw', event.request.method, event.request.url);
  // ... your routing ...
});
```

If a URL you expect never logs, it's a **scope or control** problem (1 or 2). If it logs but still goes to network, it's your **routing/respondWith** logic (3 or 4).

#### Q83. [Coding] Write a fetch handler that caches only same-origin GET requests and lets everything else pass through untouched.

A common safety baseline: never interfere with POST/PUT/DELETE, cross-origin, or non-HTTP requests.

```javascript
self.addEventListener('fetch', (event) => {
  const { request } = event;
  const url = new URL(request.url);

  // Only handle same-origin GET over http(s); let the rest pass through.
  const isSameOrigin = url.origin === self.location.origin;
  const isGet = request.method === 'GET';
  const isHttp = url.protocol === 'http:' || url.protocol === 'https:';

  if (!isSameOrigin || !isGet || !isHttp) {
    return; // not calling respondWith → browser handles it normally
  }

  event.respondWith(
    caches.match(request).then((cached) => cached || fetch(request))
  );
});
```

The key discipline: **early-return without `respondWith`** for anything you don't want to manage, so mutations and third-party requests behave exactly as if no SW existed. Trying to cache POSTs throws (the Cache API only stores GET), and caching cross-origin opaque responses can silently bloat quota.

#### Q84. [Practical] After deploying, users on the old version report a blank page. What do you check first?

This is the textbook stale-SW incident. Walk the three usual suspects in order:

1. **Is the HTML cached cache-first?** If so, users keep the old `index.html`, which references old hashed bundles that may have been purged → blank page or chunk-load errors. The fix is to serve the document **network-first** (or stale-while-revalidate with revalidation).
2. **Is `sw.js` HTTP-cached?** A long `Cache-Control: max-age` on the worker script means the browser never even sees the new SW. Verify it's served `no-cache` and consider `updateViaCache: 'none'`.
3. **Is the new worker stuck waiting?** If the old worker still controls tabs and you don't prompt for update, the new one parks in `waiting` forever. Check `registration.waiting` in DevTools.

Immediate mitigation while you fix the root cause: ship a corrected SW plus an "update available → reload" banner and a `controllerchange` reload so stuck clients recover on their next visit. If it's severe, deploy a **kill-switch SW** that unregisters and clears caches.

#### Q85. [Practical] How do you force a service worker update during development without fighting the waiting state?

During development the waiting state is pure friction — every change parks behind open tabs. Tools:

- **DevTools → Application → Service Workers → "Update on reload"**: re-installs and activates the SW on every page reload, bypassing waiting entirely. This is the single most useful dev toggle.
- **"Bypass for network"**: ignores the SW for fetches so you see real server responses while debugging.
- **"Unregister"** + **"Clear storage"** (Application → Storage) to fully reset between tests.
- Programmatically, `registration.update()` forces a re-fetch/byte-check on demand.

```javascript
// During dev, force an update check on demand
const reg = await navigator.serviceWorker.getRegistration();
await reg?.update();
```

Important caveat: **turn "Update on reload" off before testing your real update flow**, otherwise you'll never exercise the actual waiting/skipWaiting/prompt path that production users hit — and you'll miss bugs in it.

#### Q86. [Coding] Write a cache-first handler that falls back to a cached placeholder image when both cache and network fail.

Useful for image-heavy UIs so broken images don't show ugly browser icons offline.

```javascript
const IMG_CACHE = 'images-v1';
const FALLBACK_IMG = '/img/placeholder.svg';

async function imageCacheFirst(request) {
  const cache = await caches.open(IMG_CACHE);
  const cached = await cache.match(request);
  if (cached) return cached;

  try {
    const response = await fetch(request);
    if (response.ok) cache.put(request, response.clone());
    return response;
  } catch {
    // Both cache miss AND network failure → serve the placeholder.
    const fallback = await caches.match(FALLBACK_IMG);
    return fallback || new Response('', { status: 504, statusText: 'Image unavailable' });
  }
}

self.addEventListener('fetch', (event) => {
  if (event.request.destination === 'image') {
    event.respondWith(imageCacheFirst(event.request));
  }
});
```

Precache `/img/placeholder.svg` during install so the fallback itself is always available offline. Using `request.destination === 'image'` is more robust than matching file extensions.

#### Q87. [Practical] A user says push notifications stopped arriving even though they granted permission. How do you diagnose it?

Permission granted is only step one; pushes can silently stop for several reasons. Check systematically:

1. **Is the subscription still valid?** The browser may have rotated or expired it (`pushsubscriptionchange`). On your server, sends to a dead endpoint return **404/410 Gone** — log and inspect those.
2. **Is the service worker still registered and active?** If it was unregistered (or errored on activate), there's no `push` handler to receive the message.
3. **Did you actually call `showNotification()`?** Browsers require `userVisibleOnly` pushes to display a notification; failing to show one can get your origin's push privilege **revoked** by the browser after repeated silent pushes.
4. **OS / browser-level muting**: Do Not Disturb, focus modes, or the user disabling notifications at the OS level.
5. **VAPID mismatch**: if you rotated VAPID keys, existing subscriptions become invalid and must be re-created.

```javascript
// Server-side: prune dead subscriptions on 404/410
async function sendPush(subscription, payload) {
  try {
    await webpush.sendNotification(subscription, payload);
  } catch (err) {
    if (err.statusCode === 404 || err.statusCode === 410) {
      await deleteSubscription(subscription.endpoint); // it's gone for good
    } else {
      throw err;
    }
  }
}
```

The most common real-world cause is **stale subscriptions never cleaned up**, so add the 404/410 pruning and a `pushsubscriptionchange` re-subscribe handler.

#### Q88. [Coding] Write code to check whether a PWA is currently running in standalone (installed) mode.

Useful for tweaking UI (e.g., hiding your "install" button when already installed, or adjusting layout for the lack of browser chrome).

```javascript
function isStandalone() {
  // Standard: display-mode media query covers most browsers
  const displayModeStandalone = window.matchMedia('(display-mode: standalone)').matches;
  // iOS Safari legacy: navigator.standalone
  const iosStandalone = window.navigator.standalone === true;
  return displayModeStandalone || iosStandalone;
}

if (isStandalone()) {
  document.body.classList.add('installed');
  hideInstallButton();
}

// React to changes (e.g., user installs while the tab is open)
window.matchMedia('(display-mode: standalone)').addEventListener('change', (e) => {
  document.body.classList.toggle('installed', e.matches);
});
```

Note `display-mode` also accepts `'fullscreen'`, `'minimal-ui'`, and `'window-controls-overlay'`, so check the specific mode your manifest declares. Combining the media query with iOS's non-standard `navigator.standalone` covers the cross-browser gap.

#### Q89. [Practical] Your `cache.addAll()` during install keeps failing the whole install. How do you make precaching resilient?

`addAll` is **atomic** — one 404 (a typo'd path, a missing optional asset) rejects the entire install, so no worker activates and offline support never ships. Two approaches:

1. **Split critical vs. optional assets.** Use `addAll` for the truly-required app shell (fail fast if those are broken), and cache optional extras individually with `Promise.allSettled` so their failures don't block install.

```javascript
const CRITICAL = ['/', '/index.html', '/app.js', '/styles.css'];
const OPTIONAL = ['/img/hero.jpg', '/fonts/extra.woff2'];

self.addEventListener('install', (event) => {
  event.waitUntil((async () => {
    const cache = await caches.open('shell-v1');
    await cache.addAll(CRITICAL); // all-or-nothing — these MUST exist
    await Promise.allSettled(OPTIONAL.map((url) => cache.add(url))); // best-effort
  })());
});
```

2. **Verify paths at build time.** The deeper fix is to **generate the precache list from your build output** (Workbox's `__WB_MANIFEST`) rather than hand-maintaining it, so a path can never drift out of sync with what actually shipped.

The trade-off: keeping critical assets atomic preserves the "shell is all-or-nothing" integrity guarantee, while best-effort caching of extras avoids a single broken optional asset bricking your whole offline experience.

### 🟡 — extended

#### Q90. [Practical] Caching API responses works online but the cache never updates after the data changes server-side. What's the likely cause and fix?

If you used **cache-first** for API data, that's the bug: cache-first returns the stored copy and never re-fetches as long as it's present, so server-side changes are invisible until the cache entry is somehow evicted. API/data endpoints with **stable URLs** must not be cache-first.

Fixes, in order of preference:

- **Switch to network-first** so you always try fresh data and only fall back to cache offline.
- **Or stale-while-revalidate** if instant render matters more than immediate freshness — the user sees cached data now and fresh on the next request.
- **Add expiration** (`ExpirationPlugin({ maxAgeSeconds })`) so even cached entries don't live forever.

```javascript
// Was (buggy for mutable data): cache-first
// registerRoute(({url}) => url.pathname.startsWith('/api/'), new CacheFirst());

// Fix: network-first with a short cache fallback window
registerRoute(
  ({ url }) => url.pathname.startsWith('/api/'),
  new NetworkFirst({
    cacheName: 'api',
    networkTimeoutSeconds: 3,
    plugins: [new ExpirationPlugin({ maxAgeSeconds: 5 * 60 })],
  })
);
```

The general rule: **cache-first is only safe for content-addressed (hashed) URLs**; any stable-URL resource that can change needs network-first or SWR plus expiry.

#### Q91. [Coding] Implement a network-first strategy that also caches successful responses, with a proper offline fallback.

A complete, production-shaped network-first handler:

```javascript
async function networkFirst(request, cacheName = 'pages') {
  const cache = await caches.open(cacheName);
  try {
    const response = await fetch(request);
    // Only cache real successes (not 4xx/5xx/opaque-by-accident)
    if (response.ok) {
      cache.put(request, response.clone());
    }
    return response;
  } catch {
    // Network failed → serve from cache
    const cached = await cache.match(request);
    if (cached) return cached;
    // Navigation request with nothing cached → offline page
    if (request.mode === 'navigate') {
      return caches.match('/offline.html');
    }
    return new Response('Offline', { status: 503, statusText: 'Offline' });
  }
}

self.addEventListener('fetch', (event) => {
  if (event.request.method === 'GET') {
    event.respondWith(networkFirst(event.request));
  }
});
```

Three correctness details: gate caching on `response.ok` so you don't cache error pages; `clone()` before `put` because the body is read-once; and special-case navigations with `/offline.html` so a failed page load shows a friendly screen instead of a raw 503.

#### Q92. [Practical] Two open tabs of your PWA show different data after one tab makes a change. How do you keep them in sync?

All tabs of an origin share one CacheStorage and one IndexedDB, but they **don't automatically re-render** when another tab (or the SW) mutates shared state. You need an explicit cross-tab signal. `BroadcastChannel` is the cleanest:

```javascript
const channel = new BroadcastChannel('data-sync');

// Tab A: after a successful write, announce it
async function saveTodo(todo) {
  await db.put('todos', todo);
  channel.postMessage({ type: 'TODOS_CHANGED' });
}

// Every tab (and optionally the SW): react by re-reading the store
channel.onmessage = (event) => {
  if (event.data.type === 'TODOS_CHANGED') refreshTodoListFromDB();
};
```

Alternatives: the service worker can `clients.matchAll()` and `postMessage` each client after it writes (useful when the *SW* is the mutator, e.g., a background sync just flushed the outbox); or use the `storage` event for `localStorage`-backed flags (cruder). For SW-initiated changes specifically, prefer SW→clients `postMessage` since `BroadcastChannel` inside the SW only fires while the worker is alive.

#### Q93. [Practical] How do you cache responses that require authentication (e.g., per-user API data) without leaking one user's data to another?

The hazard: caching authenticated responses by URL means user B, after a logout/login on a shared device, could read user A's cached data — the Cache API is per-origin, not per-user. Defenses:

1. **Don't cache sensitive per-user responses cache-first.** Prefer network-first so you re-authenticate each time; use cache only as a last-resort offline fallback you're comfortable showing.
2. **Clear user-scoped caches on logout.**

```javascript
async function onLogout() {
  await caches.delete('user-data');           // wipe per-user cache
  const db = await openDB('app-db', 1);
  await db.clear('userTables');               // and IndexedDB
}
```

3. **Namespace caches by user/session** so they can't collide:

```javascript
const cacheName = `user-data-${currentUserId}`;
```

4. **Never cache credentials or tokens** in the Cache API or IndexedDB — they're readable by any script on the origin and persist on disk.

The mental model: treat the Cache/IndexedDB as **shared, persistent, on-disk, and readable by any origin script** — so anything user-specific or secret needs explicit lifecycle management (namespacing + clear-on-logout), and truly sensitive material shouldn't live there at all.

#### Q94. [Coding] Implement cache expiration (max age + max entries) manually, without Workbox.

When you can't pull in Workbox, you can replicate `ExpirationPlugin` by storing timestamps and trimming on write:

```javascript
const META = 'cache-meta'; // an IndexedDB-like store; here we use a side cache for simplicity

async function putWithExpiry(cacheName, request, response, { maxEntries = 50, maxAgeMs = 86400000 }) {
  const cache = await caches.open(cacheName);
  await cache.put(request, response.clone());

  // Track insertion time in a parallel metadata cache keyed by URL
  const meta = await caches.open(`${cacheName}-meta`);
  await meta.put(request.url, new Response(String(Date.now())));

  await trim(cacheName, maxEntries, maxAgeMs);
}

async function trim(cacheName, maxEntries, maxAgeMs) {
  const cache = await caches.open(cacheName);
  const meta = await caches.open(`${cacheName}-meta`);
  const keys = await cache.keys();

  // 1. Evict expired entries
  const now = Date.now();
  for (const req of keys) {
    const tsRes = await meta.match(req.url);
    const ts = tsRes ? Number(await tsRes.text()) : 0;
    if (now - ts > maxAgeMs) {
      await cache.delete(req);
      await meta.delete(req.url);
    }
  }

  // 2. Enforce max entries (FIFO by timestamp)
  const remaining = await cache.keys();
  if (remaining.length > maxEntries) {
    const withTs = await Promise.all(remaining.map(async (req) => {
      const tsRes = await meta.match(req.url);
      return { req, ts: tsRes ? Number(await tsRes.text()) : 0 };
    }));
    withTs.sort((a, b) => a.ts - b.ts); // oldest first
    for (const { req } of withTs.slice(0, remaining.length - maxEntries)) {
      await cache.delete(req);
      await meta.delete(req.url);
    }
  }
}
```

This is precisely the fiddly bookkeeping Workbox's `ExpirationPlugin` does for you (it uses IndexedDB for the timestamps) — which is exactly why most teams use Workbox rather than maintaining this by hand.

#### Q95. [Practical] How do you make a service worker behave correctly during local development on `localhost` vs production?

`localhost` is treated as a secure context, so SWs work without HTTPS — but several dev pitfalls differ from prod:

- **Aggressive caching hides your changes.** Enable "Update on reload" and/or "Bypass for network" so you don't debug against stale caches.
- **Scope confusion.** A dev server serving from a subpath can change the SW's effective scope vs. production at root; keep paths consistent or register with an explicit scope.
- **Conditionally disable or simplify the SW in dev.** Many teams only register the SW in production builds to avoid caching surprises while iterating:

```javascript
if ('serviceWorker' in navigator && import.meta.env.PROD) {
  navigator.serviceWorker.register('/sw.js');
}
```

- **`vite-plugin-pwa`** offers `devOptions.enabled` to test the SW in dev deliberately, and otherwise keeps it off so HMR isn't fighting the cache.
- **Remember to unregister** a dev SW before switching projects on the same `localhost` port, or a leftover worker from another app will intercept your requests confusingly.

The principle: dev and prod differ on caching behavior and HTTPS, so make SW registration **build-conditional** and lean on DevTools toggles to avoid chasing phantom "it changed but didn't update" bugs.

#### Q96. [Coding] Write a handler that serves cached content but revalidates in the background and notifies open pages when fresh content arrives.

Stale-while-revalidate plus a push notification to the UI so it can update without a reload:

```javascript
async function swrWithNotify(request, cacheName = 'content') {
  const cache = await caches.open(cacheName);
  const cached = await cache.match(request);

  const networkFetch = fetch(request).then(async (response) => {
    if (response.ok) {
      const oldBody = cached ? await cached.clone().text() : null;
      const newBody = await response.clone().text();
      await cache.put(request, response.clone());
      // If the content actually changed, tell open clients
      if (oldBody !== newBody) {
        const clients = await self.clients.matchAll({ includeUncontrolled: true });
        clients.forEach((c) => c.postMessage({ type: 'CONTENT_UPDATED', url: request.url }));
      }
    }
    return response;
  }).catch(() => cached);

  return cached || networkFetch;
}

self.addEventListener('fetch', (event) => {
  if (event.request.method === 'GET' && new URL(event.request.url).pathname.startsWith('/content/')) {
    event.respondWith(swrWithNotify(event.request));
  }
});
```

On the page, listen for the message and refresh just that piece of UI:

```javascript
navigator.serviceWorker.addEventListener('message', (event) => {
  if (event.data.type === 'CONTENT_UPDATED') refetchAndRerender(event.data.url);
});
```

This upgrades plain SWR (which only converges on the *next* request) into a "live update" by diffing the response and notifying clients — the user gets instant cached content *and* sees fresh data the moment it lands, without a full reload.

#### Q97. [Practical] After a deploy, `navigator.storage.estimate()` shows your quota usage ballooning unexpectedly. How do you investigate?

Quota balloon usually traces to one of a few causes:

1. **Opaque responses.** Cross-origin `no-cors` responses are heavily padded (often ~7 MB each in Chromium) regardless of real size — caching many third-party images/fonts can consume hundreds of MB. Fetch with CORS where possible, or cap those caches with `maxEntries`.
2. **Old caches not deleted on activate.** If you bumped the cache name but forgot to delete the previous version, both pile up. Audit your `activate` cleanup allowlist.
3. **Unbounded runtime caches.** A runtime cache (images, API) without an `ExpirationPlugin` grows forever.
4. **Large IndexedDB stores** (offline data, queued blobs) you never prune.

Investigate in DevTools → Application → **Storage** (shows the breakdown by Cache Storage / IndexedDB / Service Workers) and the **Cache Storage** panel to see entry counts per cache. Programmatically:

```javascript
const est = await navigator.storage.estimate();
console.log('usage', est.usage, 'quota', est.quota);
// Chromium exposes a per-bucket breakdown:
console.log(est.usageDetails); // e.g. { caches, indexedDB, serviceWorkerRegistrations }
```

The fixes: tighten cache expiration, ensure old caches are deleted on activate, prefer CORS over opaque, and request `navigator.storage.persist()` only for data you truly must keep (so the rest stays evictable under pressure).

### 🟠 — extended

#### Q98. [Practical] A subset of users are permanently stuck on a broken service worker that you've already replaced server-side. How do you recover them?

The hard truth: you **cannot force-uninstall** a SW from the server — a stuck client only updates when it next visits and successfully fetches a newer worker. If the broken SW is intercepting requests in a way that prevents even fetching the new `sw.js` (e.g., it cached `sw.js` itself, or its `fetch` handler errors), normal updates can't reach them. Recovery options:

1. **Ship a kill-switch SW** at the same URL — a minimal worker that unregisters itself, clears all caches, and reloads clients. As long as the browser's update check fetches it (it bypasses the HTTP cache for the main script in modern browsers), stuck clients self-heal on next visit.

```javascript
// kill-switch sw.js
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    await self.registration.unregister();
    const keys = await caches.keys();
    await Promise.all(keys.map((k) => caches.delete(k)));
    const clients = await self.clients.matchAll();
    clients.forEach((c) => c.navigate(c.url));
  })());
});
```

2. **Ensure the new SW is actually fetchable**: serve `sw.js` with `Cache-Control: no-cache` so the browser's byte-comparison can see the new worker.
3. **Communicate that recovery is gradual** — there's no instant fix; clients heal as they revisit.

The lesson reinforced: design for forward-recoverability up front (kill-switch in every repo, `no-cache` on the worker script) because once a bad SW is in the wild, your *only* lever is making the *next* fetch deliver something that fixes it.

#### Q99. [Practical] Lazy-loaded route chunks throw "ChunkLoadError" for users who had a tab open during your deploy. Walk through the fix.

Root cause: an open tab loaded `main.OLD.js`, which lazily `import()`s `route.OLD.js` only when the user navigates there. Your deploy replaced those with `*.NEW.js` and your SW (or server) purged the old files, so the lazy import now 404s mid-session.

The layered fix:

1. **Keep old chunks on the server** for a grace period (a week is common) so in-flight imports resolve over the network instead of 404ing.
2. **Retain N-1 precache versions** in the SW so even offline old tabs find their chunks.
3. **Don't silently `skipWaiting`** — prompt for reload so HTML and SW advance together, shrinking the mismatch window.
4. **Add an app-level retry**: catch the import failure and force a one-time reload to pick up new HTML.

```javascript
function lazyWithReload(importFn) {
  return importFn().catch((err) => {
    // Avoid an infinite reload loop with a session flag
    if (!sessionStorage.getItem('chunkReloaded')) {
      sessionStorage.setItem('chunkReloaded', '1');
      window.location.reload();
      return new Promise(() => {}); // never resolves; page is reloading
    }
    throw err;
  });
}

// Usage with a router
const Settings = React.lazy(() => lazyWithReload(() => import('./Settings.jsx')));
```

The deeper principle: **the SW cache and deployed assets must be versioned together and overlap during the transition**, and the app must degrade gracefully when it references an asset that no longer exists — never assume a chunk that existed at page-load time still exists at navigation time.

#### Q100. [Coding] Implement a `fetch` handler that times out slow network requests and falls back to cache after N milliseconds.

Network-first is useless on a flaky connection that *hangs* rather than fails — you need an explicit timeout. Use `AbortController` so the request is actually cancelled, not just ignored:

```javascript
async function networkFirstWithTimeout(request, { timeoutMs = 3000, cacheName = 'pages' } = {}) {
  const cache = await caches.open(cacheName);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);

  try {
    const response = await fetch(request, { signal: controller.signal });
    clearTimeout(timer);
    if (response.ok) cache.put(request, response.clone());
    return response;
  } catch (err) {
    clearTimeout(timer);
    // Aborted (timeout) OR network failure → fall back to cache
    const cached = await cache.match(request);
    if (cached) return cached;
    if (request.mode === 'navigate') return caches.match('/offline.html');
    return new Response('Timed out', { status: 504 });
  }
}

self.addEventListener('fetch', (event) => {
  if (event.request.method === 'GET') {
    event.respondWith(networkFirstWithTimeout(event.request, { timeoutMs: 3000 }));
  }
});
```

Using `AbortController` matters: without it, a `Promise.race` against a timer would resolve from cache but the original fetch keeps running in the background, wasting bandwidth and battery. Aborting frees the connection. Note Workbox's `NetworkFirst({ networkTimeoutSeconds })` does exactly this internally.

#### Q101. [Practical] Your push notifications work in Chrome but not at all in Safari / on iOS. What do you need to account for?

Safari and iOS have historically been the weakest PWA targets; as of 2026 the situation is much better but still has specifics to handle:

- **iOS requires the PWA to be installed (Added to Home Screen).** Web Push on iOS (Safari 16.4+, iOS 16.4+) only works for PWAs the user has *installed* to the home screen — you cannot push to a regular Safari tab. Your UX must guide installation first.
- **Permission must come from a user gesture**, and on iOS the prompt only appears within the installed PWA context.
- **No `pushManager` until installed on iOS** — feature-detect and gate your subscribe UI accordingly.
- **VAPID is supported**, but historically Safari was pickier about the encryption/payload format; use a maintained library (`web-push`) rather than rolling your own.
- **Background Sync / Periodic Sync are not supported** on Safari/iOS, so don't rely on them for delivery — have a foreground fallback.

```javascript
async function canUsePush() {
  if (!('serviceWorker' in navigator) || !('PushManager' in window)) return false;
  // On iOS, push only works when running as an installed PWA
  const standalone = window.matchMedia('(display-mode: standalone)').matches
    || window.navigator.standalone === true;
  const isIOS = /iphone|ipad|ipod/i.test(navigator.userAgent);
  return isIOS ? standalone : true;
}
```

The senior move is to **feature-detect and gate by platform**, guide iOS users to install first, and never assume parity — test on the actual weakest target rather than trusting that "web push works now."

#### Q102. [Coding] Implement a robust update-prompt flow: detect a waiting worker, let the user trigger the update, and reload exactly once.

The complete, production-grade pattern that avoids both silent breakage and reload loops:

```javascript
// ---- In the page ----
let refreshing = false;
navigator.serviceWorker.addEventListener('controllerchange', () => {
  if (refreshing) return;        // guard against double reload
  refreshing = true;
  window.location.reload();
});

async function registerWithUpdateFlow() {
  const reg = await navigator.serviceWorker.register('/sw.js');

  // A worker is already waiting (installed before this page loaded)
  if (reg.waiting && navigator.serviceWorker.controller) {
    promptUserToUpdate(reg.waiting);
  }

  reg.addEventListener('updatefound', () => {
    const newWorker = reg.installing;
    newWorker?.addEventListener('statechange', () => {
      if (newWorker.state === 'installed' && navigator.serviceWorker.controller) {
        promptUserToUpdate(newWorker); // an update is ready and waiting
      }
    });
  });
}

function promptUserToUpdate(worker) {
  showBanner('A new version is available.', () => {
    worker.postMessage({ type: 'SKIP_WAITING' }); // user clicked "Reload"
  });
}

// ---- In the service worker ----
self.addEventListener('message', (event) => {
  if (event.data?.type === 'SKIP_WAITING') self.skipWaiting();
});
```

The three correctness guarantees: (1) the `refreshing` flag ensures the page reloads **once**, not in a loop; (2) `skipWaiting()` is called **only after explicit user consent**, so open tabs aren't broken behind the user's back; (3) checking `navigator.serviceWorker.controller` distinguishes a genuine *update* from a benign *first install* (where no reload is needed).

#### Q103. [Practical] How do you test offline behavior reliably in an automated CI pipeline?

Manual "toggle offline in DevTools" doesn't scale or guard against regressions. Build it into CI:

1. **Drive a real browser with Playwright/Puppeteer**, register the SW, let it install, then toggle offline and assert the app still renders.

```javascript
test('app works offline after first load', async ({ page, context }) => {
  await page.goto('/');
  // Wait for the SW to control the page
  await page.waitForFunction(() => navigator.serviceWorker.controller !== null);
  await context.setOffline(true);
  await page.reload();
  await expect(page.getByRole('heading', { name: 'My Awesome App' })).toBeVisible();
  await context.setOffline(false);
});
```

2. **Run Lighthouse's PWA audit in CI** (via `lighthouse-ci`) to verify installability, a registered SW with a fetch handler, manifest correctness, and offline start_url response.
3. **Reset state between tests** — clear storage and unregister the SW so a stale cache from one test doesn't poison the next.
4. **Test the update flow explicitly**, not just first install: deploy v1, load it, deploy v2, and assert the update prompt appears and reload activates v2.

The key insight is that **offline and update behavior are exactly the parts that break silently in production**, so they deserve dedicated automated coverage — toggling network conditions and asserting both first-load offline support *and* correct version transitions.

#### Q104. [Coding] Write a service worker that intercepts navigations to serve an app-shell `index.html` from cache (SPA offline navigation).

For a client-routed SPA, every route should fall back to the cached shell so deep links work offline:

```javascript
const SHELL = '/index.html';

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open('shell-v1').then((cache) => cache.add(SHELL))
  );
});

self.addEventListener('fetch', (event) => {
  const { request } = event;

  // For navigations (page loads / SPA route entry points), serve the shell.
  if (request.mode === 'navigate') {
    event.respondWith((async () => {
      try {
        // Try network first so a fresh shell wins when online
        const preload = await event.preloadResponse;
        if (preload) return preload;
        return await fetch(request);
      } catch {
        // Offline → serve the cached app shell; the SPA router renders the route
        const cache = await caches.open('shell-v1');
        return (await cache.match(SHELL)) || new Response('Offline', { status: 503 });
      }
    })());
    return;
  }

  // Non-navigation assets handled by your normal strategies elsewhere...
});
```

The crucial idea for SPAs: **all navigation requests resolve to the same cached `index.html`**, and the client-side router then renders the correct view from the URL. This is why a deep link like `/settings/profile` works offline even though there's no `/settings/profile.html` on the server — the shell loads from cache and JS takes over routing.

#### Q105. [Practical] Users report the install prompt never appears. How do you diagnose installability problems?

`beforeinstallprompt` only fires when Chrome's installability criteria are met, so a missing prompt means one criterion is failing. Diagnose with **DevTools → Application → Manifest**, which lists installability errors directly. Check the criteria:

1. **Served over HTTPS** (or localhost).
2. **Valid manifest** with `name`/`short_name`, `start_url`, a `display` of `standalone`/`fullscreen`/`minimal-ui`, and icons including at least 192px and 512px.
3. **Registered service worker with a `fetch` handler** (historically required for installability).
4. **Not already installed** — once installed, the prompt won't re-fire.
5. **Engagement heuristic** — Chrome may require some user interaction with the site before firing.

```javascript
window.addEventListener('beforeinstallprompt', (e) => {
  console.log('Installable! Criteria met.'); // if this never logs, a criterion failed
  e.preventDefault();
  deferredPrompt = e;
});
```

If the event never fires, the Manifest panel's warnings tell you exactly which field or asset is the problem (e.g., "no maskable icon," "manifest start_url is not valid," "no matching service worker"). On **iOS Safari there is no `beforeinstallprompt` at all** — installation is always manual via Share → Add to Home Screen, so you show your own iOS-specific instructions instead.

### 🔴 — extended

#### Q106. [Practical] Design the rollout and monitoring plan for shipping a new caching strategy to a high-traffic PWA, given you can't roll back a SW.

Because a bad SW persists on clients and can only be fixed forward, the plan centers on **bounded exposure, deep observability, and a guaranteed recovery path**:

1. **Percentage rollout at the registration layer.** Server-drive which sessions register the new SW (e.g., 1% → 5% → 25% → 100%), so a regression hits a small cohort. Crucially, gate *registration*, not just behavior, since you can't un-register remotely.
2. **Telemetry baked in before ramp**: install/activate success/failure, active cache version per client, cache hit/miss ratios, chunk-load errors, and overall SW error rate — dashboards plus alerts, because the deployer's own fresh cache hides regressions.
3. **Conservative update policy**: no silent `skipWaiting`; user-prompted update + single `controllerchange` reload; retain N-1 precache versions; `sw.js` served `no-cache` with `updateViaCache: 'none'`.
4. **A pre-staged kill-switch SW** ready to deploy that unregisters and clears caches, so recovery doesn't require writing code under incident pressure.
5. **CI pre-flight**: Lighthouse PWA audit + offline E2E + an explicit *update-path* test (v1→v2) blocking merge.
6. **Define success/abort metrics** up front (e.g., abort if SW error rate rises >X% or chunk-load errors spike), and ramp only when the cohort is healthy.

The senior signal: treating SW rollout like a **database migration that can't be reverted** — you bound blast radius, instrument heavily so you *see* per-version health, and ensure every version is recoverable by the next, because the standard "roll back the artifact" safety net does not exist.

#### Q107. [Practical] A memory/quota leak is slowly degrading your PWA over weeks of use. How do you find and fix it?

Slow degradation over a long-lived install points at **unbounded growth** in caches or IndexedDB. Methodology:

1. **Quantify with `navigator.storage.estimate()`** over time (log it periodically to telemetry) to confirm usage trends upward and identify whether it's Cache Storage or IndexedDB (`usageDetails`).
2. **Audit each runtime cache for an expiration policy.** A cache without `ExpirationPlugin({ maxEntries, maxAgeSeconds })` grows forever — every new image/API response accumulates. Add limits.
3. **Check activate cleanup.** Confirm old versioned caches are actually deleted (a typo in the allowlist leaves them orphaned each deploy).
4. **Inspect IndexedDB stores** for never-pruned records — an outbox that doesn't delete on success, an append-only delta log without compaction, or cached blobs that accumulate.
5. **Look for opaque-response bloat** — padded entries make usage balloon far beyond real bytes.

```javascript
// Add bounds to a previously-unbounded runtime cache
registerRoute(
  ({ request }) => request.destination === 'image',
  new CacheFirst({
    cacheName: 'images',
    plugins: [new ExpirationPlugin({ maxEntries: 60, maxAgeSeconds: 30 * 24 * 60 * 60, purgeOnQuotaError: true })],
  })
);
```

`purgeOnQuotaError: true` lets Workbox evict this cache if the origin hits its quota, preventing a quota error from breaking caching entirely. The systemic fix is **a bound on everything that grows** — every cache gets max-entries/age, every IndexedDB store gets a pruning/compaction step, and you monitor `estimate()` so leaks surface before users do.

#### Q108. [Coding] Implement a self-healing service worker that detects repeated activation failures and falls back to a safe minimal mode.

A resilient worker should not brick the app if its own advanced logic throws on activate — it should degrade to pass-through networking:

```javascript
const VERSION = 'v7';
const SHELL = ['/', '/index.html', '/app.js', '/styles.css'];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(`shell-${VERSION}`)
      .then((cache) => cache.addAll(SHELL))
      .catch((err) => {
        // Precache failed — record it but don't block install entirely
        console.error('[SW] precache failed, entering safe mode', err);
        return self.registration.scope; // resolve so install can still proceed
      })
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    try {
      const keys = await caches.keys();
      await Promise.all(
        keys.filter((k) => k !== `shell-${VERSION}` && k.startsWith('shell-'))
            .map((k) => caches.delete(k))
      );
      await self.clients.claim();
    } catch (err) {
      console.error('[SW] activate cleanup failed, continuing in safe mode', err);
      // Do NOT rethrow — a thrown activate makes the worker redundant and can strand clients.
    }
  })());
});

self.addEventListener('fetch', (event) => {
  if (event.request.method !== 'GET') return;
  event.respondWith((async () => {
    try {
      const cached = await caches.match(event.request);
      if (cached) return cached;
      return await fetch(event.request);
    } catch {
      // Safe mode: if anything in our logic throws, fall back to plain network,
      // and only then to an offline page — never let the SW itself break navigation.
      try { return await fetch(event.request); }
      catch { return (await caches.match('/offline.html')) || new Response('Offline', { status: 503 }); }
    }
  })());
});
```

The design principle: **a service worker's own errors must never be worse than having no service worker at all.** By swallowing activate-cleanup errors (rather than rethrowing into a `redundant` state) and wrapping fetch logic so it always falls through to plain network, the worst case degrades to "no caching" instead of "broken app" — exactly the fail-safe posture you want for code that can't be remotely recalled.

#### Q109. [Behavioral] You discover a teammate shipped a service worker that cache-firsts the HTML and it's already live. How do you handle it?

A strong answer balances urgency, blamelessness, and durable prevention:

- **Assess blast radius first.** Check telemetry: how many clients are on the bad SW, are users seeing stale content or hard failures (chunk-load errors)? This sizes the response.
- **Mitigate immediately, calmly.** Ship a corrected SW that serves the HTML network-first, plus an update banner and `controllerchange` reload so stuck clients recover on next visit. If users are hard-broken, deploy the kill-switch SW. Communicate to support/stakeholders that recovery is gradual because clients update lazily.
- **No blame on the individual.** This is a notorious footgun precisely because SW persistence is unintuitive; treat it as a gap in guardrails, not a personal failure. Pair with the teammate on the fix so it's a learning moment, not a callout.
- **Fix the system, not just the incident.** Add the missing guardrails: a lint/review check or Workbox config that enforces network-first documents, a CI offline+update E2E test, a kill-switch SW in the repo, and a short brown-bag on SW lifecycle pitfalls. Write a blameless postmortem capturing the HTML-as-pivot insight.

The leadership signal is responding with **measured urgency** (mitigate without panic), **psychological safety** (blameless, collaborative fix), and a shift from "fix this bug" to "make this class of bug impossible" via tooling and education — recognizing the unique stickiness of SW failures.

#### Q110. [Practical] How would you architect a PWA that must work fully offline for days (e.g., a field-inspection or in-flight app), including data capture and eventual sync?

Multi-day offline raises the stakes on durability and quota far beyond typical caching:

1. **Request persistent storage up front.** Call `navigator.storage.persist()` (gated on install/engagement) so the OS won't evict captured data under pressure — non-negotiable when losing field data is unacceptable. Monitor `estimate()` and warn the user as they approach quota.
2. **Precache the entire app + reference data.** Cache the full app shell and any read-only reference data (forms, catalogs, maps tiles within a region) so nothing requires the network during the session. For large datasets, store in IndexedDB rather than the Cache API.
3. **All writes go to a durable local outbox.** Every captured inspection/record is written to IndexedDB immediately with a client-generated UUID (idempotency key) and timestamp — the local store is the source of truth; the UI reads from it.
4. **Handle large media deliberately.** Photos/videos are stored as Blobs in IndexedDB; track their cumulative size against quota and possibly downscale/compress on capture.
5. **Eventual sync with idempotent replay.** When connectivity returns (Background Sync where supported, plus a foreground "sync now" fallback for Safari/iOS), drain the outbox in order, deleting each record only on a confirmed 2xx; idempotency keys make retries after ambiguous failures safe. Pull server changes and reconcile with a deliberate conflict policy (often last-write-wins per record, or merge if collaborative).
6. **Surface state to the user.** Show pending-sync counts, storage usage, and last-synced time so a field user trusts that nothing is lost.

```javascript
// Capture a record durably, independent of connectivity
async function captureRecord(record, photoBlob) {
  const db = await openDB('field-db', 1);
  const id = crypto.randomUUID();
  await db.put('records', { id, ...record, photo: photoBlob, capturedAt: Date.now(), synced: false });
  if ('sync' in (await navigator.serviceWorker.ready)) {
    (await navigator.serviceWorker.ready).sync.register('upload-records');
  }
}
```

The architecture treats the device as a **disconnected replica with a write-ahead log**: persistent storage so data survives, local-first writes with idempotency, and a sync engine that replays safely whenever a connection appears — explicitly accounting for iOS's lack of Background Sync with a foreground fallback, and for quota limits with persistence + size management. The senior framing is designing for **partition-tolerance as the default state**, not the exception.

## ✅ Key Takeaways

- A PWA stands on three pillars: a **service worker**, a **Web App Manifest**, and **HTTPS** — delivering an installable, offline-capable, app-like web experience.
- The service worker lifecycle is **install → activate → fetch**: pre-cache in install, clean up old caches in activate, intercept requests in fetch.
- Pick caching strategy by resource: **cache-first** for hashed/immutable assets, **network-first** for fresh-critical data (and the HTML), **stale-while-revalidate** as a fast-but-eventually-fresh default.
- Use **IndexedDB** (not localStorage) for offline data; it's the only structured store available inside a service worker.
- **Background Sync** defers actions until connectivity returns; **Push** + the **Notifications API** re-engage users via a push service authenticated with **VAPID**.
- The SW **waiting/skipWaiting/clients.claim** mechanics govern updates — default to user-prompted updates with a `controllerchange` reload to keep open tabs consistent.
- **Workbox** encapsulates the hard-won patterns (routing, strategies, precaching with `__WB_MANIFEST`, expiration) and is the production standard.

## ⚠️ Common Pitfalls

- **Cache-first'ing the HTML** so users get stuck on an old version with no URL change to break the cache — almost always cache the document network-first or revalidate it.
- **`skipWaiting()` carelessly**, breaking already-open tabs whose old lazy chunks are gone — prefer prompted updates.
- **Forgetting `.clone()`** on a Response before caching, since the body stream can only be read once.
- **Serving `sw.js` with a long `Cache-Control`**, so the browser keeps a stale SW and updates never ship — use `no-cache` / `updateViaCache: 'none'`.
- **Caching opaque/cross-origin responses indiscriminately**, hiding errors and ballooning quota (opaque responses are heavily padded).
- **Assuming SW global state persists** — the worker is terminated when idle; persist anything you need in IndexedDB.
- **Treating cached/IndexedDB data as private/durable** — it's readable by any script on the origin and can be evicted; don't store secrets, request `persist()` for must-keep data, and clear on logout.
- **Relying on Background Sync / Periodic Sync everywhere** — support is uneven (notably outside Chromium); always have a fallback path.

## 📚 Further Reading

- MDN — *Service Worker API*, *Cache API*, *Web App Manifest*, *Push API*, *Background Synchronization API*, *IndexedDB API*.
- web.dev — *Learn PWA* course; *The Offline Cookbook* (caching strategies); *Service Worker Lifecycle* (Jake Archibald).
- *Workbox* documentation (developer.chrome.com/docs/workbox) and `vite-plugin-pwa` docs.
- Jake Archibald — *The offline cookbook* and *Service Worker Lifecycle* articles.
- Google *Lighthouse* PWA audits and the installability criteria reference.
- *idb* library (Jake Archibald) for promise-based IndexedDB.
