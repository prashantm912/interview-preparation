# OpenHTMLtoPDF & HTML-to-PDF Generation

A practical interview-prep guide for generating pixel-stable, Unicode-safe PDFs from HTML/CSS in Java using **OpenHTMLtoPDF** (the maintained successor to Flying Saucer), plus the surrounding ecosystem of templating engines and alternative renderers.

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

### Q1. [Theory] What is OpenHTMLtoPDF and how does it relate to Flying Saucer?

OpenHTMLtoPDF is a pure-Java library that renders an **XHTML/CSS 2.1** document into a PDF (or image) without any external binary or browser. It is a fork and modernization of **Flying Saucer** (`org.xhtmlrenderer`), which had largely stalled. OpenHTMLtoPDF kept Flying Saucer's CSS box-model renderer but replaced the aging iText 2.x PDF backend with **Apache PDFBox**, which matters legally because iText moved to AGPL. The "why" for choosing it: it's deterministic, runs entirely in-JVM (great for servers and containers with no headless Chrome), and produces tagged/accessible PDFs (PDF/UA, PDF/A). The trade-off is that it speaks an older CSS dialect — no Flexbox, no CSS Grid, limited modern selectors — so you author "print HTML," not arbitrary web pages.

### Q2. [Practical] Show the minimal code to convert an HTML string into a PDF file.

The core entry point is the builder `PdfRendererBuilder`. You feed it HTML (as a string, file, or W3C DOM), an output stream, and call `run()`.

```java
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class SimplePdf {
    public static void main(String[] args) throws Exception {
        String html = """
            <html><head><meta charset='utf-8'/>
            <style>body{font-family:sans-serif;} h1{color:#003366;}</style>
            </head><body><h1>Invoice #1024</h1><p>Hello, world.</p></body></html>
            """;
        try (OutputStream os = new FileOutputStream("out.pdf")) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();                 // enables the faster renderer
            builder.withHtmlContent(html, "/");    // baseUri for resolving relative URLs
            builder.toStream(os);
            builder.run();
        }
    }
}
```

The `baseUri` (`"/"` above) is how relative `<img src>` and `<link>` URLs are resolved — get it wrong and your images silently vanish.

### Q3. [Theory] What input does OpenHTMLtoPDF actually require — can it parse "any" HTML?

No. The renderer requires **well-formed XHTML** (valid XML), not the loose tag-soup browsers tolerate. Unclosed `<br>`, `<img>`, or `<hr>` tags, unquoted attributes, or stray `&` characters will throw a parse error. In practice you run real-world HTML through a cleaner like **jsoup** (`Jsoup.parse(html)` then output as XHTML) before handing it to the builder. This is the single most common surprise for newcomers: OpenHTMLtoPDF is a *renderer*, not a forgiving browser parser.

### Q4. [Practical] How do you add OpenHTMLtoPDF to a Maven project?

You need the core module plus the PDFBox output module. Optional modules add SVG, MathML, and slf4j logging.

```xml
<dependency>
  <groupId>com.openhtmltopdf</groupId>
  <artifactId>openhtmltopdf-pdfbox</artifactId>
  <version>1.1.28</version>
</dependency>
<!-- Optional: SVG rendering via Apache Batik -->
<dependency>
  <groupId>com.openhtmltopdf</groupId>
  <artifactId>openhtmltopdf-svg-support</artifactId>
  <version>1.1.28</version>
</dependency>
```

`openhtmltopdf-pdfbox` transitively pulls the core. Pin the version explicitly and keep all `openhtmltopdf-*` modules on the **same** version to avoid `NoSuchMethodError` at runtime.

### Q5. [Theory] Why generate PDFs from HTML at all instead of building them programmatically?

Building PDFs imperatively (placing each text run and line at x/y coordinates with raw PDFBox or iText low-level APIs) gives total control but is painful for documents that change: every layout tweak is code. HTML/CSS lets non-engineers (or designers) own the look, supports natural reflow of variable-length content (a 3-line address vs. a 10-line one), and reuses skills the whole team already has. The trade-off is fidelity: HTML-to-PDF tools never match a browser's CSS support 100%, so highly designed documents need testing. For most business documents — invoices, statements, contracts, reports — HTML templating is the pragmatic winner.

---

## 🟡 Intermediate (3–7 yrs)

### Q6. [Practical] How do you embed a custom font, and why is this almost always necessary for non-Latin text?

OpenHTMLtoPDF does **not** read OS-installed fonts and ships with only the 14 standard PDF base fonts (Helvetica, Times, Courier), which cover Latin-1 only. So any accented character beyond basic, and all CJK / Arabic / Devanagari / emoji, render as blank boxes (□, "tofu") unless you register and embed a TrueType/OpenType font that contains those glyphs.

```java
PdfRendererBuilder builder = new PdfRendererBuilder();
builder.useFastMode();
// family name must match your CSS font-family
builder.useFont(new File("/fonts/NotoSans-Regular.ttf"), "Noto Sans");
builder.useFont(new File("/fonts/NotoSans-Bold.ttf"), "Noto Sans",
        700, BaseRendererBuilder.FontStyle.NORMAL, true); // weight 700, embedded
builder.useFont(new File("/fonts/NotoSansCJK-Regular.ttf"), "Noto CJK");
```

Then in CSS: `body { font-family: "Noto Sans", "Noto CJK", sans-serif; }`. Use a font with broad Unicode coverage (Google's **Noto** family is the standard choice) and **embed** it (the last `boolean` arg / default) so the PDF renders identically on any machine. Note: only TrueType (`.ttf`) and OpenType-with-TrueType-outlines work reliably; OpenType-CFF (`.otf`) support is limited, and `.woff` is not supported — convert to `.ttf` first.

### Q7. [Theory] Which CSS does OpenHTMLtoPDF support, and what's the most important non-screen feature?

It targets **CSS 2.1** plus a curated subset of CSS 3 print features. You get the full box model, floats, tables, absolute/relative/fixed positioning, backgrounds, borders, lists, and basic transforms. You do **not** get Flexbox, Grid, CSS variables (custom properties), `calc()` in most contexts, viewport units, or modern pseudo-class selectors. The single most important feature for PDF work is the **paged media** module: the `@page` at-rule controls page size, margins, and margin boxes; `page-break-before/after/inside` (and the modern `break-*` aliases) control where content splits; and `-fs-page-sequence` / running elements handle repeating headers and footers. You design around pages, not a continuous viewport.

### Q8. [Coding] Implement a Thymeleaf-templated invoice rendered to PDF, with proper resource cleanup.

**Problem:** Render a server-side Thymeleaf template populated with a model into a PDF byte array suitable for an HTTP download, using a W3C DOM (the most robust input path because Thymeleaf can emit valid XHTML).

```java
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.jsoup.Jsoup;
import com.openhtmltopdf.util.XRLog;
import org.w3c.dom.Document;
import java.io.ByteArrayOutputStream;
import java.util.Map;

public class InvoicePdfService {

    private final TemplateEngine engine;

    public InvoicePdfService() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setTemplateMode(TemplateMode.HTML);   // permissive HTML mode
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCacheable(true);                   // cache compiled templates
        this.engine = new TemplateEngine();
        this.engine.setTemplateResolver(resolver);
    }

    public byte[] render(String templateName, Map<String, Object> model) {
        // 1. Thymeleaf -> HTML string
        Context ctx = new Context();
        ctx.setVariables(model);
        String rawHtml = engine.process(templateName, ctx);

        // 2. Clean to well-formed XHTML via jsoup -> W3C DOM
        org.jsoup.nodes.Document jsoupDoc = Jsoup.parse(rawHtml);
        jsoupDoc.outputSettings()
                .syntax(org.jsoup.nodes.Document.OutputSettings.Syntax.xml);
        Document w3c = new org.jsoup.helper.W3CDom().fromJsoup(jsoupDoc);

        // 3. Render to PDF
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withW3cDocument(w3c, "classpath:/templates/");
            builder.useFont(
                new java.io.File("fonts/NotoSans-Regular.ttf"), "Noto Sans");
            builder.toStream(baos);
            builder.run();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("PDF render failed for " + templateName, e);
        }
    }
}
```

**Edge cases:** (1) the `baseUri` must let the renderer find images/CSS — use a custom `FSUriResolver` if assets live in S3 or the classpath; (2) values from `model` that contain user-supplied HTML must be escaped by Thymeleaf (`th:text`, not `th:utext`) to avoid markup injection breaking the document or leaking data; (3) very large invoices (thousands of line items) should stream to a file/`OutputStream`, not a `ByteArrayOutputStream`, to bound heap.
**Complexity:** rendering is roughly **O(n)** in DOM nodes plus a layout pass; memory is dominated by the in-memory DOM and the page buffers.

### Q9. [Practical] How do you create repeating headers and footers with the page number on every page?

Use the paged-media model. Define page margins and margin boxes, and use `position: running()` to lift an element into a margin box, plus the `counter(page)` / `counter(pages)` counters for "Page X of Y".

```css
@page {
  size: A4;
  margin: 25mm 18mm 22mm 18mm;
  @top-center { content: element(header); }   /* running element */
  @bottom-right {
    content: "Page " counter(page) " of " counter(pages);
    font-size: 9px; color: #666;
  }
}
#pageHeader { position: running(header); }     /* removed from flow, drawn in @top-center */
```

```html
<div id="pageHeader">Acme Corp — Confidential</div>
```

Approach in production: keep the header/footer markup tiny and avoid images there (they re-rasterize per page and bloat the file). For dynamic per-page content beyond simple counters, you typically post-process with PDFBox to stamp overlays. Trade-off: `counter(pages)` requires a full layout pass to know the total, which is fine but means you can't stream incrementally and know the page count up front.

### Q10. [Theory] How do you handle images and SVG, and what are the gotchas?

Raster images (`<img>` PNG/JPEG) work out of the box and are resolved relative to `baseUri`. Key gotchas: PDFs are print media, so a 1000px-wide image at 96 DPI is only ~10.4 inches — set explicit `width`/`max-width` in CSS or images overflow the page; and large/uncompressed PNGs bloat output, so pre-size and compress assets. **SVG** requires the `openhtmltopdf-svg-support` module (backed by Apache Batik) and renders as true vectors — sharp at any zoom and small in bytes — making it the right choice for charts, logos, and barcodes. The trade-off is Batik adds startup cost and a heavier dependency tree, and its SVG/CSS support is its own subset (no SMIL animation, limited filters). For data-URI images (`data:image/png;base64,...`) you can avoid resolver issues entirely, useful for self-contained templates.

### Q11. [Practical] You need to embed an existing PDF chart or merge generated pages with a cover PDF. How?

OpenHTMLtoPDF renders HTML only; it does not merge PDFs. Because the backend is **PDFBox**, you combine its output with `PDFMergerUtility` or `PDFBox` page manipulation. A common production pattern: render the body from HTML, then prepend a designer-built cover page and append boilerplate legal pages.

```java
import org.apache.pdfbox.multipdf.PDFMergerUtility;

PDFMergerUtility merger = new PDFMergerUtility();
merger.addSource(new File("cover.pdf"));
merger.addSource(new ByteArrayInputStream(renderedBody)); // from OpenHTMLtoPDF
merger.addSource(new File("legal.pdf"));
merger.setDestinationFileName("final.pdf");
merger.mergeDocuments(org.apache.pdfbox.io.MemoryUsageSetting.setupTempFileOnly());
```

Using `setupTempFileOnly()` keeps the merge off-heap — important when concatenating many large documents in a server.

---

## 🟠 Advanced (8–12 yrs)

### Q12. [Practical] A nightly batch renders 50,000 statements and the JVM OOMs. Diagnose and fix.

```
PROBLEM SHAPE
  for each customer:
      html = template(customer)        # fine
      pdf  = render(html) -> byte[]    # each held in a List -> heap blows up
  writeZip(allPdfs)

ROOT CAUSES (typical)
  1. Accumulating byte[]/Documents in a collection.
  2. One giant HTML doc instead of one-per-statement.
  3. Embedding the full font subset into every tiny PDF.
  4. Batik (SVG) + DOM retained across iterations.
```

**Approach:** (1) Stream each PDF straight to its destination (`ZipOutputStream` entry or object store) and let it be GC'd before the next — never collect them. (2) Reuse the `TemplateEngine` and font configuration, but build a fresh `PdfRendererBuilder` per document (it's cheap; renderer state is not reusable). (3) Use **font subsetting** (PDFBox subsets embedded fonts by default in OpenHTMLtoPDF) so each PDF only carries glyphs it uses. (4) Run rendering on a **bounded thread pool** sized to CPU cores, not unbounded parallel streams, because each render holds a full DOM + layout tree; uncapped concurrency multiplies peak heap. (5) For one truly massive document (e.g., a 5,000-page report), prefer many smaller renders merged with PDFBox `setupTempFileOnly()` over one DOM. **What I'd actually ship:** a producer/consumer pipeline with a fixed thread pool, per-document streaming, JFR/heap monitoring, and a circuit breaker that falls back to retry-on-smaller-batch if memory pressure spikes.

### Q13. [Theory] Compare OpenHTMLtoPDF, iText, Flying Saucer, wkhtmltopdf, and Puppeteer/Playwright. When do you pick each?

```
                  Engine            CSS support     Deps/Runtime         License        Best for
OpenHTMLtoPDF     pure Java(PDFBox) CSS 2.1 + print  in-JVM, no binary   LGPL/Apache    server-side, deterministic, PDF/A & UA
iText 7/9         pure Java        pdfHTML add-on    in-JVM              AGPL/commercial enterprise w/ license; rich PDF features
Flying Saucer     pure Java(iText2) CSS 2.1         in-JVM (old iText)  LGPL            legacy; superseded by OpenHTMLtoPDF
wkhtmltopdf       WebKit binary    older WebKit CSS  native binary       LGPL (archived) richer CSS but UNMAINTAINED, CVEs
Puppeteer/        headless Chrome  FULL modern CSS   Node + Chromium     Apache         pixel-perfect modern web -> PDF
Playwright                          (Flexbox/Grid)   (heavy, ~300MB)
```

**Decision logic:** If you need a self-contained JVM solution, reproducible output, and accessibility/archival standards (PDF/UA, PDF/A-3) — **OpenHTMLtoPDF**. If you need full modern CSS (Flexbox, Grid, web fonts, JS-driven charts) and can run a browser — **Playwright/Puppeteer** (Playwright is the modern pick; wkhtmltopdf is **deprecated/archived** and should not be chosen for new systems). **iText** is excellent and feature-rich but its core is **AGPL** — commercial use requires a paid license, which is the decisive factor for many shops. Flying Saucer is effectively legacy; new projects should use OpenHTMLtoPDF. The headless-browser route gives the best fidelity but adds operational weight (Chromium in your container, ~300MB, sandbox/seccomp concerns) and is harder to make deterministic across versions.

### Q14. [Practical] How would you make rendered PDFs accessible (PDF/UA) and archival (PDF/A)?

OpenHTMLtoPDF has first-class support, which is a major reason it's chosen for regulated industries. Enable the conformance mode on the builder and supply the prerequisites the standard demands.

```java
builder.useFastMode();
builder.usePdfUaAccessibility(true);                       // tagged structure for screen readers
builder.usePdfAConformance(PdfRendererBuilder.PdfAConformance.PDFA_3_U);
// PDF/A requires: embedded fonts, an ICC color profile, document language & title
builder.useColorProfile(Files.readAllBytes(Path.of("sRGB.icc")));
```

You must also: embed **all** fonts (no base-14 references), set the document language (`<html lang="en">`) and `<title>`, provide `alt` text on images (`<img alt="...">` becomes the accessible alternative), and use semantic markup (`<h1>`–`<h6>`, `<table>` with `<th scope>`, lists). Trade-offs: PDF/A forbids transparency and certain encryption, and bans external references, so all assets must be embedded — file sizes grow. **Real-world case:** banks and government portals delivering statements legally must meet **PDF/UA** (Section 508 / EN 301 549 / EAA in the EU, enforced from 2025) and often **PDF/A** for long-term retention; OpenHTMLtoPDF lets them satisfy both from the same HTML template pipeline rather than buying a commercial engine.

### Q15. [Coding] Implement a reusable, thread-safe PDF service with custom URI resolution for classpath/S3 assets.

**Problem:** A web app renders many PDFs concurrently. Builders are not thread-safe and must not be shared, but the font config and resolver logic should be. Assets (logo, CSS) live on the classpath, not the filesystem.

```java
import com.openhtmltopdf.extend.FSStream;
import com.openhtmltopdf.extend.FSStreamFactory;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.*;
import java.util.function.Consumer;

public final class PdfRenderService {

    // Resolve "classpath:/assets/..." URIs to classpath streams.
    private static final FSStreamFactory CLASSPATH_FACTORY = url -> new FSStream() {
        private final String path = url.replaceFirst("^classpath:/?", "");
        public InputStream getStream() {
            InputStream in = PdfRenderService.class.getClassLoader()
                    .getResourceAsStream(path);
            if (in == null) throw new IllegalStateException("Missing asset: " + path);
            return in;
        }
        public Reader getReader() { return new InputStreamReader(getStream()); }
    };

    /** Render HTML to the supplied stream. A fresh builder PER CALL = thread-safe. */
    public void render(String xhtml, String baseUri, OutputStream out) {
        PdfRendererBuilder b = new PdfRendererBuilder();
        b.useFastMode();
        b.useProtocolsStreamImplementation(CLASSPATH_FACTORY, "classpath");
        b.useFont(new File("fonts/NotoSans-Regular.ttf"), "Noto Sans");
        b.withHtmlContent(xhtml, baseUri);   // e.g. "classpath:/assets/"
        b.toStream(out);
        try {
            b.run();
        } catch (IOException e) {
            throw new UncheckedIOException("render failed", e);
        }
    }

    /** Helper for callers that want bytes. */
    public byte[] renderToBytes(String xhtml, String baseUri) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024)) {
            render(xhtml, baseUri, baos);
            return baos.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
```

**Approaches considered:** sharing one builder (rejected — mutable, not thread-safe → corrupted output under load); a builder pool (over-engineering — builders are cheap to construct). **Chosen:** new builder per render, shared immutable resolver/font paths. **Edge cases:** missing asset throws fast (don't silently drop the logo); a custom resolver should validate/whitelist URIs to prevent **SSRF/local-file-read** if any part of the HTML is user-controlled (block `file://`, internal IPs). **Complexity:** O(nodes) layout, O(1) extra builder allocation per call; concurrency bounded by your thread pool.

### Q16. [Theory] What are the security risks of HTML-to-PDF, and how do they differ between OpenHTMLtoPDF and headless-browser approaches?

The dominant risk class is **server-side request forgery (SSRF) and local file disclosure** through resource resolution: if attacker-controlled HTML reaches the renderer, `<img src="http://169.254.169.254/...">` (cloud metadata) or `<link href="file:///etc/passwd">` can exfiltrate secrets into the rendered PDF. Headless browsers are *more* dangerous here because they also execute **JavaScript** and follow far more protocols, enabling richer SSRF, DNS rebinding, and even RCE via browser CVEs — they should run sandboxed (seccomp, no privileged network). OpenHTMLtoPDF runs no JS and is narrower, but is **not** automatically safe: you must supply a restrictive `FSUriResolver`/`FSStreamFactory` that whitelists schemes and hosts, disable external entity resolution in the XML parser (**XXE**), and treat all template variables as untrusted (escape, never `utext`). Additional concerns: zip-bomb / billion-laughs style DoS via deeply nested HTML, and embedding sensitive data in the PDF that then needs encryption (PDFBox `StandardProtectionPolicy`) and access controls at rest.

---

## 🔴 Expert (15+ yrs)

### Q17. [Theory] Walk through OpenHTMLtoPDF's internal pipeline. Where are the performance and correctness boundaries?

```
 HTML/XHTML string ─┐
 W3C DOM ───────────┼─► (1) Parse ─► DOM tree
 InputStream ───────┘
        │
        ▼
 (2) CSS cascade & match  ── builds styled tree (computed styles per node)
        │
        ▼
 (3) Box generation       ── block/inline/table/float boxes (CSS 2.1 box model)
        │
        ▼
 (4) Layout / line-breaking── paginates: applies @page, break rules, running elems
        │                     (this pass is where counter(pages) total is resolved)
        ▼
 (5) Paint  ── PdfBoxOutputDevice draws text runs, vectors, images to PDFBox
        │      (fonts subset & embedded here; SVG handed to Batik)
        ▼
     PDF bytes (PDFBox PDDocument -> OutputStream)
```

The correctness boundary lives in **(2)–(3)**: the CSS engine is CSS 2.1, so anything depending on Flexbox/Grid/`calc` won't lay out as a browser would — this is the source of most "looks fine in Chrome, broken in PDF" tickets. The performance boundary is **(4)**: layout is the most expensive pass, super-linear for pathological tables (huge `colspan`/`rowspan` matrices) and deeply nested floats, and it must complete fully before painting because pagination needs global information. `useFastMode()` selects the newer, faster layout/paint path and is recommended for essentially all new code. Memory peaks during (1)–(4) because the entire DOM, styled tree, and box tree coexist.

### Q18. [Practical] Your PDFs render perfectly in dev but show tofu boxes and wrong line-breaks only in production containers. Root-cause it.

This is a classic environment-divergence bug. **Approach:** First confirm it's a font issue — tofu (□) means a glyph isn't in any embedded font. In dev your code likely picked up an OS or local-path font that **doesn't exist in the slim container image** (e.g., `eclipse-temurin:21-jre` has almost no fonts). Because OpenHTMLtoPDF embeds fonts you explicitly register — and *only* those — the fix is to **bundle the `.ttf` files as classpath resources or COPY them into the image**, and register via stream, never rely on `/usr/share/fonts`. The wrong line-breaks are a downstream symptom: when the primary font is missing, the fallback metrics differ, so wrapping changes. Secondary suspects: locale/charset differences (`-Dfile.encoding=UTF-8`, ensure `<meta charset>`), and missing complex-script shaping for Arabic/Indic — OpenHTMLtoPDF's shaping is limited, so for those scripts you may need a font with proper OpenType tables and to verify bidi. **What I'd ship:** fonts as versioned classpath resources loaded through the resolver, a startup self-test that renders a known multi-script string and asserts the byte length / glyph presence, and golden-image visual regression tests in CI so production never diverges from dev silently.

### Q19. [Theory] How do you build a deterministic, byte-reproducible PDF pipeline, and why is it hard?

Reproducibility (identical input → identical bytes) matters for caching, digital signatures, and audit/diffing. The obstacles: (1) PDFBox writes a **`CreationDate`/`ModDate`** and a random **document ID** by default — you must override both (`PDDocumentInformation` dates and `setDocumentId`) to fixed values. (2) Font subsetting can vary if glyph ordering isn't stable — pin font versions and the library version. (3) Image compression and floating-point layout are generally stable within a fixed library version but can shift across upgrades, so you **pin OpenHTMLtoPDF, PDFBox, and Batik** exactly and treat upgrades as content-affecting changes. (4) For **digital signatures**, sign as a post-step (PDFBox `SignatureInterface`) over the finalized bytes; never re-render a signed PDF. The "why it's hard": PDF was designed for visual fidelity, not byte stability, and several layers inject entropy. In practice you reach *content* reproducibility easily and *byte* reproducibility with the date/ID pinning above — and you verify it with a hash check in CI.

### Q20. [Behavioral] Your team must choose a PDF strategy for a new product that needs both simple statements and a heavily designed marketing report. How do you lead that decision?

I'd frame it as **two problems, not one**, and resist the pressure to pick a single tool to "keep it simple." First I'd gather hard requirements: volume and concurrency, accessibility/archival mandates (PDF/UA, PDF/A), licensing budget, container/runtime constraints, and how design-heavy each document truly is — pulling in legal/compliance and design, not just engineers. Then I'd prototype the two hardest documents in two candidates: OpenHTMLtoPDF (in-JVM, deterministic, free, great for the high-volume accessible statements) and Playwright/headless Chrome (full modern CSS for the marketing report). I'd present a decision matrix with the trade-offs explicit — operational weight and non-determinism of Chromium vs. CSS limitations of OpenHTMLtoPDF — and a recommendation, commonly a **hybrid**: OpenHTMLtoPDF for the bulk transactional path, headless browser (isolated, rate-limited service) for the rare design-heavy artifact. I'd flag that introducing Chromium has a real operational cost (image size, CVE patching, sandboxing) the SRE team must own, and I'd write down the decision and its expiry conditions so a future engineer understands *why*, not just *what*. The behavioral core is making the trade-offs visible, bringing the right stakeholders in early, and committing to a reversible, documented call rather than a religious one.

### Q21. [Practical] How do you set up visual regression testing so template changes don't silently break thousands of generated documents?

**Approach:** Treat PDFs like UI. Render each template with a frozen, representative fixture set, rasterize pages to PNG (PDFBox `PDFRenderer.renderImageWithDPI` at a fixed DPI), and compare against committed **golden images** with a perceptual/pixel diff (e.g., a tolerance-based image comparator) in CI. Pair this with structural assertions: extract text (PDFBox `PDFTextStripper`) and assert key fields are present and not overlapping/clipped, and assert page count is within range. Pin all rendering library versions so diffs reflect *your* change, not a dependency bump. **Trade-offs:** golden images are noisy across font-rendering or library upgrades, so you gate regeneration behind review and keep tolerances calibrated. **What I'd actually do in production:** a small library of fixtures covering edge cases (empty tables, max-length fields, multi-page overflow, RTL/CJK strings), golden images per locale, the visual diff as a required CI check, and the self-test render on app startup as a last line of defense. This is the difference between catching a broken footer in CI and customers receiving 40,000 malformed statements.

---

## ✅ Key Takeaways

- **OpenHTMLtoPDF = Flying Saucer + PDFBox**, pure Java, no external binary, CSS 2.1 + print media — deterministic and great for servers/containers.
- Input must be **well-formed XHTML**; run real HTML through **jsoup** first. The `baseUri` resolves relative assets — get it right.
- Fonts are **not** read from the OS: register and **embed** Unicode fonts (Noto) or get tofu boxes; use TTF, not WOFF/OTF-CFF.
- Use **`@page`, running elements, and `counter(page)/counter(pages)`** for size, margins, and repeating headers/footers; design around pages, not a viewport.
- For scale, **stream each PDF and bound concurrency**; for merging/cover pages, use **PDFBox** with off-heap temp files.
- It supports **PDF/UA (accessibility)** and **PDF/A (archival)** natively — a key differentiator vs. AGPL-licensed iText.
- **wkhtmltopdf is deprecated**; for full modern CSS use **Playwright/headless Chrome**, accepting the operational weight and non-determinism.
- Always **secure the resolver** (whitelist schemes/hosts, disable XXE, escape template variables) — HTML-to-PDF is an SSRF/file-disclosure vector.

## ⚠️ Common Pitfalls

- Feeding tag-soup HTML and getting cryptic XML parse errors (forgot to close `<br>`/`<img>`).
- Expecting **Flexbox/Grid/CSS variables/`calc()`** to work — they don't; rewrite layouts with floats/tables/positioning.
- Missing fonts in slim container images → tofu boxes and changed line-breaks only in production.
- Accumulating rendered `byte[]` in a list during batch jobs → OutOfMemoryError.
- Oversized raster images blowing up file size and overflowing pages because PDF is print-DPI, not 96dpi screen.
- Forgetting the SVG module dependency, then wondering why charts don't appear.
- Mixing mismatched `openhtmltopdf-*` module versions → `NoSuchMethodError`.
- Using `th:utext` / unescaped variables, opening markup injection and broken documents.
- Assuming `counter(pages)` is free — it forces a full layout pass before output.
- Leaving non-deterministic `CreationDate`/document ID in place, breaking caching and signatures.

## 📚 Further Reading

- **OpenHTMLtoPDF GitHub & Wiki** — `github.com/openhtmltopdf/openhtmltopdf` — the authoritative source for builder API, modules, and accessibility/PDF-A guides.
- **Apache PDFBox documentation** — `pdfbox.apache.org` — for merging, encryption, signing, and rasterization that complement the renderer.
- **W3C CSS 2.1 Specification & CSS Paged Media Module** — the contract OpenHTMLtoPDF implements; essential for `@page` and break behavior.
- **Thymeleaf Documentation** (`thymeleaf.org`) and **Apache FreeMarker Manual** (`freemarker.apache.org`) — server-side templating that feeds the renderer.
- **PDF Association — PDF/UA & PDF/A guides** (`pdfa.org`) — accessibility and archival conformance requirements.
- **OWASP — Server-Side Request Forgery Prevention Cheat Sheet** — for securing the resource resolver against SSRF/file disclosure.
