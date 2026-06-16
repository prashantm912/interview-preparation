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
