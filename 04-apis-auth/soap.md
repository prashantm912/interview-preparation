# SOAP & Web Services

SOAP (Simple Object Access Protocol) is an XML-based messaging protocol for exchanging structured information between services over a transport (usually HTTP, but also JMS or SMTP). It underpins the WS-* "Big Web Services" stack and still powers a large share of enterprise, banking, telecom, and government integrations in 2026.

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

### Q1. [Theory] What is SOAP and what are the parts of a SOAP message?

SOAP is a protocol that defines a standard XML message format for exchanging data between systems regardless of platform or language. A SOAP message is an XML document called the **Envelope**, which contains an optional **Header** and a mandatory **Body**. The Header carries metadata and cross-cutting concerns (security tokens, addressing, transaction context, correlation IDs) that are processed by intermediaries or the endpoint, while the Body carries the actual payload — the operation request or response, or a **Fault** if something went wrong. The key "why" is that SOAP separates *what you're sending* (Body) from *how it should be handled* (Header), enabling layered processing without polluting the business payload.

```
┌─────────────────────────────────────────┐
│ <soap:Envelope>                          │  ← namespace + encoding
│   ┌─────────────────────────────────┐    │
│   │ <soap:Header>  (optional)       │    │  ← WS-Security, WS-Addressing,
│   │   security tokens, addressing   │    │     correlation, txn context
│   └─────────────────────────────────┘    │
│   ┌─────────────────────────────────┐    │
│   │ <soap:Body>    (mandatory)      │    │  ← business payload
│   │   request / response / Fault    │    │
│   └─────────────────────────────────┘    │
│ </soap:Envelope>                         │
└─────────────────────────────────────────┘
```

### Q2. [Theory] What is a WSDL and why is it central to SOAP?

A **WSDL** (Web Services Description Language) is an XML document that formally describes a SOAP service: what operations it exposes, the message structure for each operation (input/output/faults), the data types (via embedded or imported XSD), and the binding/transport details (how and where to call it). It is the machine-readable **contract** between client and server. Because it is so precise, tooling can auto-generate client stubs and server skeletons from it, giving SOAP its hallmark strong typing and early compile-time validation. The five core elements are `types`, `message`, `portType` (called `interface` in WSDL 2.0), `binding`, and `service`.

### Q3. [Theory] What is the difference between WSDL and XSD?

WSDL describes the *service* — operations, messages, bindings, and endpoints. XSD (XML Schema Definition) describes the *data* — the structure, types, and constraints of the XML elements being exchanged. In practice the WSDL's `<types>` section either inlines an XSD or imports one. You can think of XSD as the "class definitions" (what a `Customer` looks like) and WSDL as the "interface definition" (what methods you can call and where). Separating them lets multiple services reuse the same schema.

### Q4. [Practical] How would you call an existing SOAP service in Java?

In production I would do **contract-first**: take the provider's WSDL and generate strongly-typed client stubs, rather than hand-crafting XML. With JAX-WS the standard tool is `wsimport` (JDK ≤ 8) or the `jaxws-maven-plugin` (Java 11+). I bind the generated port and call it like a normal Java method.

```java
// Generated from WSDL by wsimport / jaxws-maven-plugin
CountryInfoService service = new CountryInfoService();      // the @WebServiceClient
CountryInfoServiceSoapType port = service.getCountryInfoServiceSoap();

// Strongly typed call — no manual XML
String currency = port.countryCurrency("US");
System.out.println(currency); // USD
```

Trade-off: generated code couples you tightly to the contract, so a breaking WSDL change forces regeneration — but you get compile-time safety and IDE autocomplete for free, which is exactly why enterprises prefer it over hand-built REST clients for stable contracts.

### Q5. [Theory] What is a SOAP Fault?

A SOAP Fault is the standardized way SOAP reports errors, returned inside `<soap:Body>` as a `<soap:Fault>` element with an HTTP 500 status. In SOAP 1.2 it has `Code` (with a `Value` such as `Sender` or `Receiver`, plus optional `Subcode`), `Reason` (human-readable text), `Node`, `Role`, and `Detail` (application-specific error data). SOAP 1.1 uses different element names (`faultcode`, `faultstring`, `faultactor`, `detail`). The benefit over ad-hoc REST error bodies is that the fault structure is part of the contract, so clients can parse errors uniformly.

```xml
<soap:Fault>
  <soap:Code><soap:Value>soap:Sender</soap:Value></soap:Code>
  <soap:Reason><soap:Text xml:lang="en">Invalid account number</soap:Text></soap:Reason>
  <soap:Detail>
    <err:AccountFault><err:code>ACC-404</err:code></err:AccountFault>
  </soap:Detail>
</soap:Fault>
```

---

## 🟡 Intermediate (3–7 yrs)

### Q6. [Theory] Contract-first vs code-first — which do you choose and why?

**Code-first** (start from Java classes, let the framework generate the WSDL) is fast to prototype: you annotate a class with `@WebService` and the runtime publishes a WSDL. **Contract-first** (start from a hand-authored WSDL/XSD, generate Java from it) is the disciplined choice for any service with external or long-lived consumers. Contract-first is preferred in serious enterprise work because the contract is a deliberate, reviewed artifact independent of your implementation language — you avoid leaking Java type quirks (e.g., how a `BigDecimal` or `LocalDate` serializes) into the wire format, and you can negotiate the schema with partners before writing code. The downside is more upfront XSD effort and a steeper learning curve. My rule: code-first for internal throwaway/prototype, contract-first for anything other teams or companies depend on.

```
Code-first:   Java @WebService  ──generate──▶  WSDL  (implementation drives contract)
Contract-first: WSDL + XSD       ──generate──▶  Java  (contract drives implementation)  ✅ enterprise
```

### Q7. [Coding] Write a contract-first JAX-WS service (Java) with proper packaging.

**Problem:** Expose a `getQuote(symbol)` SOAP operation. Define the SEI (Service Endpoint Interface), the implementation, and publish it.

```java
// 1. SEI — in real contract-first this is generated from WSDL; shown by hand for clarity
import jakarta.jws.WebService;   // jakarta.* on Java 11+/Spring Boot 3; javax.* on Java 8/SB2
import jakarta.jws.WebMethod;
import jakarta.jws.WebParam;

@WebService(name = "QuoteService", targetNamespace = "http://trade.example.com/quotes")
public interface QuoteService {
    @WebMethod
    double getQuote(@WebParam(name = "symbol") String symbol);
}
```

```java
// 2. Implementation
import jakarta.jws.WebService;

@WebService(endpointInterface = "com.example.QuoteService",
            serviceName = "QuoteService",
            targetNamespace = "http://trade.example.com/quotes")
public class QuoteServiceImpl implements QuoteService {
    @Override
    public double getQuote(String symbol) {
        if (symbol == null || symbol.isBlank())
            throw new IllegalArgumentException("symbol required"); // becomes a SOAP Fault
        return lookupPrice(symbol); // pretend DB/market lookup
    }
    private double lookupPrice(String s) { return 123.45; }
}
```

```java
// 3. Publish (lightweight JAX-WS endpoint; in prod you'd deploy to a servlet container/Spring)
import jakarta.xml.ws.Endpoint;

public class Server {
    public static void main(String[] args) {
        Endpoint.publish("http://0.0.0.0:8080/ws/quotes", new QuoteServiceImpl());
        // WSDL auto-served at http://host:8080/ws/quotes?wsdl
    }
}
```

**Edge cases:** null/blank symbol → translate to a typed SOAP Fault; very large responses → consider MTOM; symbol not found → a checked exception annotated with `@WebFault` maps to a custom fault detail. **Time/Space:** the framework's marshalling is `O(n)` in message size for both time and memory (it builds a DOM/JAXB tree); for huge payloads prefer streaming (StAX) or MTOM to avoid `O(n)` heap spikes.

### Q8. [Practical] How do you handle authentication and message-level security in SOAP?

For transport security I use **HTTPS/TLS** as the baseline, but SOAP's distinguishing strength is **message-level security via WS-Security** (WSS), which protects the message itself end-to-end even across intermediaries that terminate TLS. WS-Security defines how to put a `UsernameToken`, a binary security token (X.509 cert), or a SAML assertion in the SOAP header, and how to sign and/or encrypt specific elements using XML Signature and XML Encryption. In practice with Spring-WS or Apache CXF I configure a WSS4J interceptor: e.g., require a signed timestamp + signed body with X.509, and encrypt the body for the recipient's public key.

```
TLS (transport):     protects hop-by-hop, gone once message leaves the wire
WS-Security (message): signs/encrypts elements; survives multiple hops & async queues  ✅
```

Trade-off: WS-Security is heavyweight (canonicalization, key management, cert rotation) but it is *the* reason banking and B2B partners still mandate SOAP — they need per-message non-repudiation and partial encryption, which plain TLS + REST can't provide without bolt-ons (JWS/JWE).

### Q9. [Theory] Explain WS-Addressing and WS-ReliableMessaging.

**WS-Addressing** standardizes transport-neutral addressing and correlation by putting endpoint references and message metadata (`MessageID`, `RelatesTo`, `ReplyTo`, `FaultTo`, `Action`) in the SOAP header. This decouples the message from the underlying transport, enabling asynchronous request/response (the reply can come back on a different connection or queue) and routing through intermediaries. **WS-ReliableMessaging** (WS-RM) guarantees delivery semantics — *at-least-once*, *at-most-once*, *exactly-once*, and *in-order* — by adding sequence numbers and acknowledgements in the header, with automatic retransmission of lost messages. Together they let SOAP do robust, async, guaranteed messaging over an unreliable transport, which is why they appear in EAI/B2B and telecom systems. The cost is significant protocol complexity and state on both ends.

### Q10. [Practical] What is MTOM and when do you use it?

**MTOM** (Message Transmission Optimization Mechanism) lets you send large binary data (PDFs, images, signed documents) efficiently. Without it, binary must be Base64-encoded inside the XML, which inflates size by ~33% and forces it through the XML parser. MTOM instead extracts the binary into a separate MIME attachment (via XOP — XML-binary Optimized Packaging) and leaves an `<xop:Include href="cid:..."/>` reference in the XML, so the bytes travel raw. You enable it with `@MTOM` (JAX-WS) and type the field as `byte[]` or `DataHandler`.

```java
@MTOM
@WebService
public class DocServiceImpl implements DocService {
    @Override
    public void upload(@XmlMimeType("application/octet-stream") DataHandler file) { ... }
}
```

Use MTOM when payloads exceed a few hundred KB; for small fields the MIME overhead can make it slightly *worse*, so it's a threshold decision. Note the predecessors SwA (SOAP with Attachments) and DIME are largely obsolete — MTOM is the modern standard.

### Q11. [Theory] REST vs SOAP — what are the real trade-offs?

REST is an architectural style over HTTP using resources, verbs, and typically JSON; SOAP is a protocol with a rigid XML contract and a rich WS-* extension stack. SOAP wins where you need **formal contracts, strong typing, built-in standards for security/reliability/transactions, and transport independence** (HTTP, JMS, SMTP). REST wins on **simplicity, performance, caching (HTTP semantics), browser/mobile friendliness, and developer velocity**. JSON is far lighter than XML, and REST has a vastly larger tooling/ecosystem in 2026. The honest answer in interviews: there's no universal winner — pick SOAP when a regulator or partner mandates WS-Security/WS-RM or you have a stable enterprise contract; pick REST (or gRPC/GraphQL) for new public APIs, microservices, and anything consumed by web/mobile clients.

| Dimension | SOAP | REST |
|-----------|------|------|
| Format | XML only | JSON/XML/anything |
| Contract | WSDL (strict) | OpenAPI (looser) |
| Transport | HTTP, JMS, SMTP | HTTP only |
| Security | WS-Security (msg-level) | TLS + OAuth/JWT |
| State/Reliability | WS-RM, WS-AT built-in | none built-in |
| Caching | hard | native HTTP caching |
| Payload size | heavy | light |
| Best fit | banking, B2B, legacy | web/mobile, microservices |

### Q12. [Practical] A partner bank requires WS-Security with signed and encrypted bodies. How do you implement it with Spring Boot?

I'd use **Spring-WS** (or Apache CXF) with a **Wss4jSecurityInterceptor**. Approach: (1) contract-first — import the partner's WSDL/XSD and generate JAXB classes with the `jaxb2-maven-plugin`; (2) configure the security interceptor to *sign* the timestamp + body with our private key (X.509), *encrypt* the body with the partner's public cert, and *require* the same on inbound; (3) store keys in a hardware-backed keystore (HSM/PKCS#11) and externalize the keystore password; (4) add a `Timestamp` with a short TTL to defeat replay attacks; (5) test with the partner in a UAT environment because cert chains and canonicalization mismatches are the #1 source of interop failures.

```java
@Bean
public Wss4jSecurityInterceptor securityInterceptor() {
    var wss4j = new Wss4jSecurityInterceptor();
    wss4j.setSecurementActions("Timestamp Signature Encrypt");
    wss4j.setSecurementUsername("our-key-alias");
    wss4j.setSecurementSignatureCrypto(ourCrypto());        // our private key
    wss4j.setSecurementEncryptionUser("partner-cert-alias"); // partner public key
    wss4j.setValidationActions("Timestamp Signature Encrypt");
    return wss4j;
}
```

**Security note:** never disable timestamp validation to "make it work" — that reopens replay attacks. Rotate certs ahead of expiry and monitor for the partner's cert renewals.

---

## 🟠 Advanced (8–12 yrs)

### Q13. [Theory] What is the SOAP processing model with `mustUnderstand`, roles, and intermediaries?

A SOAP message can flow through one or more **intermediary** nodes before reaching the **ultimate receiver**. Header blocks can be **targeted** at a specific node using the SOAP `role` (1.2) / `actor` (1.1) attribute — e.g., a logging intermediary processes and removes a header block intended for it. The `mustUnderstand="true"` attribute on a header block means a targeted node MUST either fully process that block or generate a `MustUnderstand` fault; it cannot silently ignore it. This is the formal mechanism that makes extensions like WS-Security enforceable: a server can mandate that clients send a security header by marking it mustUnderstand. Understanding this model is what separates someone who *uses* SOAP from someone who can *design* WS-* extensions or debug why an intermediary is rejecting messages.

```
Client ─▶ [Intermediary: logging]  ─▶ [Intermediary: security gateway] ─▶ Ultimate Receiver
            processes role="log"        processes WS-Security header        processes Body
            header, removes it          (mustUnderstand=1)
```

### Q14. [Theory] Compare RPC/encoded, RPC/literal, Document/literal, and Document/literal wrapped WSDL styles.

This is the classic interop minefield. The WSDL `binding` `style` is `rpc` or `document`, and `use` is `encoded` or `literal`. **RPC/encoded** uses SOAP encoding (type info on the wire) — non-interoperable and effectively dead; banned by WS-I Basic Profile. **RPC/literal** validates against the schema and names the operation, but the body isn't a single schema-validatable element. **Document/literal** sends a schema-defined document but loses the operation name in the body (a problem for dispatch and for WS-Addressing-less routing). **Document/literal wrapped** is the de-facto standard: it wraps the parameters in a single element named after the operation, giving you both schema validation *and* operation identification, and it's what JAX-WS generates by default. Knowing this matters because legacy services often use a non-wrapped style and you must match it exactly or marshalling fails.

### Q15. [Practical] You must integrate with a 15-year-old SOAP service whose WSDL won't import cleanly. How do you debug?

Real scenario I've handled: vendor WSDL has imported XSDs behind a firewall, uses `rpc/encoded`, and a non-standard SOAP 1.1 fault. Approach: (1) **download the full WSDL + all imported XSDs locally** and rewrite `schemaLocation`/`import` to relative paths so generation isn't network-dependent; (2) run `wsimport`/CXF `wsdl2java` with verbose to see exactly which construct fails; (3) if `rpc/encoded` blocks generation, fall back to building the SOAP envelope manually with a low-level client (`SOAPMessage`/SAAJ or `javax.xml.transform`) and validate against the schema by hand; (4) capture real request/response pairs with **SoapUI** or Wireshark/TCPdump and replay them to confirm the exact namespaces and prefixes the server expects (legacy servers are often prefix-sensitive despite the spec); (5) add an outbound logging interceptor and diff my generated envelope against a known-good one byte by byte. The lesson: with legacy SOAP, *capture the real traffic* — the WSDL often lies or is incomplete.

### Q16. [Coding] Implement a JAX-WS handler (SOAPHandler) to inject a WS-Addressing-style correlation header and log faults.

**Problem:** Add a `MessageID` header to every outbound request and log any inbound SOAP Fault for observability — without touching business code.

```java
import jakarta.xml.ws.handler.soap.SOAPHandler;
import jakarta.xml.ws.handler.soap.SOAPMessageContext;
import jakarta.xml.ws.handler.MessageContext;
import jakarta.xml.soap.*;
import javax.xml.namespace.QName;
import java.util.Set;
import java.util.UUID;

public class CorrelationHandler implements SOAPHandler<SOAPMessageContext> {

    private static final String WSA = "http://www.w3.org/2005/08/addressing";

    @Override
    public boolean handleMessage(SOAPMessageContext ctx) {
        boolean outbound = (Boolean) ctx.get(MessageContext.MESSAGE_OUTBOUND_PROPERTY);
        try {
            SOAPMessage msg = ctx.getMessage();
            SOAPEnvelope env = msg.getSOAPPart().getEnvelope();
            if (outbound) {
                SOAPHeader header = env.getHeader();
                if (header == null) header = env.addHeader();
                SOAPHeaderElement id = header.addHeaderElement(new QName(WSA, "MessageID", "wsa"));
                id.addTextNode("urn:uuid:" + UUID.randomUUID());
            }
        } catch (SOAPException e) {
            throw new RuntimeException(e); // surfaces as a client-side fault
        }
        return true; // true = continue chain; false = stop processing
    }

    @Override
    public boolean handleFault(SOAPMessageContext ctx) {
        try {
            SOAPFault fault = ctx.getMessage().getSOAPBody().getFault();
            if (fault != null)
                System.err.println("SOAP Fault: " + fault.getFaultString());
        } catch (SOAPException ignored) { }
        return true;
    }

    @Override public void close(MessageContext ctx) { }
    @Override public Set<QName> getHeaders() { return Set.of(new QName(WSA, "MessageID")); }
}
```

**Edge cases:** message may already have a header (reuse it, don't double-add); inbound messages shouldn't get a MessageID (guard on `outbound`); SOAP 1.1 vs 1.2 envelope namespaces differ — derive from the actual envelope rather than hard-coding. **Time/Space:** `O(1)` extra work per message beyond the DOM that already exists; no extra allocation proportional to payload. Register the handler via a `@HandlerChain` annotation or programmatically on the `BindingProvider`.

### Q17. [Theory] How do distributed transactions work in SOAP via WS-AtomicTransaction?

**WS-AtomicTransaction** (WS-AT), layered on **WS-Coordination**, brings two-phase commit (2PC) to SOAP services so multiple participants can join a single ACID transaction that spans services. A **coordinator** issues a coordination context (propagated in the SOAP header); participants **register** with it, and at commit time the coordinator runs the prepare/commit (or rollback) phases. This is genuinely powerful for, say, a payment that must atomically debit one bank service and credit another. But it's also why SOAP has a reputation for heaviness: 2PC has blocking and availability problems (the famous coordinator-failure window), doesn't scale across high-latency boundaries, and is largely avoided in modern microservices in favor of **sagas/eventual consistency**. In interviews, knowing *why* the industry moved away from WS-AT toward sagas is more valuable than the protocol mechanics.

### Q18. [Practical] How do you version a SOAP service without breaking existing consumers?

The contract is the constraint, so versioning must be deliberate. Tactics, in order of preference: (1) **additive, backward-compatible changes only** within a version — add optional elements (`minOccurs="0"`), never remove or retype existing ones, never reorder in a `sequence`; (2) **namespace versioning** — bump the target namespace (e.g., `.../quotes/v2`) for breaking changes, which generates a distinct schema so old clients keep using v1; (3) run **v1 and v2 endpoints side by side** behind a router and deprecate v1 on a published schedule; (4) use XSD `xsd:any`/extension points sparingly to allow forward-compatible additions. The anti-pattern is mutating a live schema — XML clients validate strictly and will reject unexpected or reordered elements, breaking partners silently in production.

---

## 🔴 Expert (15+ yrs)

### Q19. [Theory] In 2026, where does SOAP still genuinely matter, and how do you decide whether to keep or replace it?

SOAP remains entrenched where the cost of change is high and the WS-* guarantees are contractual, not optional: **core banking and payments** (ISO 20022 over SOAP, SWIFT-adjacent rails), **insurance** (ACORD), **healthcare** (some HL7 v3 / IHE profiles), **telecom OSS/BSS**, **government/tax filing**, and **B2B EDI gateways**. These mandate per-message signing/encryption (WS-Security), guaranteed delivery (WS-RM), and formal contracts with legal weight. My decision framework: keep SOAP when (a) a regulator/partner *requires* a WS-* capability with no clean REST equivalent, (b) the service is stable and the migration ROI is negative, or (c) it sits behind a well-tested integration layer. Replace/wrap it when the friction is mostly developer velocity — in which case I put a **REST/gRPC façade** in front of the SOAP backend rather than ripping it out, getting modern ergonomics for new consumers while preserving the certified core. The senior judgment is recognizing that "legacy" is not a pejorative when the system is correct, audited, and trusted.

### Q20. [Behavioral] Tell me about a time you had to advocate for keeping (or retiring) SOAP against organizational pressure.

Strong answers use a structure like STAR and show *trade-off ownership*, not dogma. Example narrative: "A new platform team wanted to rewrite our partner-facing settlement service from SOAP to REST for consistency. I led a spike that surfaced two blockers: our top three banking partners contractually required WS-Security message-level signing for non-repudiation, and WS-RM exactly-once delivery underpinned our reconciliation guarantees — neither was free in REST. **Task/Action:** rather than a binary fight, I proposed a hybrid: keep the certified SOAP endpoints for the three regulated partners, and stand up a REST/gRPC façade for the dozen newer integrations that didn't need WS-*. I quantified the migration cost and the regulatory risk, and brought the partners' compliance contacts into the review. **Result:** we cut new-integration onboarding time by ~60% while avoiding a 6-figure re-certification effort, and leadership adopted 'façade, don't rewrite' as the default pattern." The signal interviewers want: data-driven, partner-empathetic, avoids ideology, and optimizes for business risk over technical fashion.

### Q21. [Theory] What are the deep performance and security pitfalls in SOAP/XML that a staff engineer must guard against?

Three classes dominate. **(1) XML parsing attacks:** XXE (XML External Entity) and billion-laughs/entity-expansion DoS — mandatory mitigation is to disable DTDs and external entities on every parser (`XMLConstants.FEATURE_SECURE_PROCESSING`, `disallow-doctype-decl`); this is the single most common SOAP CVE class. **(2) WS-Security signature wrapping (XSW) attacks:** an attacker moves signed elements within the document so the signature still verifies but a different (malicious) element is processed — mitigation is strict schema validation, processing only what was actually signed (signed-element references, not XPath-by-id), and using hardened libraries. **(3) Performance:** XML is verbose and DOM-based marshalling is memory-heavy; canonicalization for signatures is CPU-intensive; and synchronous 2PC (WS-AT) blocks. Mitigations: StAX streaming for large messages, MTOM for binaries, connection pooling, caching parsed WSDL/JAXB contexts (JAXBContext is expensive to build but thread-safe to reuse), and replacing WS-AT with sagas. A staff engineer is expected to *automate* the XXE/XSW hardening across all services, not fix it per-incident.

### Q22. [Practical] Design a high-throughput, resilient SOAP integration gateway for a bank. Walk through the architecture.

```
                              ┌─────────────────────────────────────┐
 Internal REST/gRPC clients ─▶│        SOAP Integration Gateway      │─▶ Partner Bank SOAP
                              │                                       │     (WS-Security,
   (modern microservices)     │  ┌──────────────────────────────┐    │      WS-RM)
                              │  │ REST/gRPC façade (OpenAPI)   │    │
                              │  ├──────────────────────────────┤    │
                              │  │ Translation: JSON ⇄ JAXB/XML │    │
                              │  ├──────────────────────────────┤    │
                              │  │ WS-Security (WSS4J + HSM)    │    │
                              │  ├──────────────────────────────┤    │
                              │  │ Resilience: retry+backoff,   │    │
                              │  │ circuit breaker, idempotency │    │
                              │  ├──────────────────────────────┤    │
                              │  │ Observability: correlation   │    │
                              │  │ IDs, audit log, metrics      │    │
                              │  └──────────────────────────────┘    │
                              └─────────────────────────────────────┘
```

**Approach:** (1) Expose a clean REST/gRPC façade internally so app teams never touch SOAP; (2) translate JSON↔XML with cached `JAXBContext` instances (never rebuild per request); (3) centralize WS-Security with keys in an **HSM/PKCS#11** keystore and automated cert-rotation alerts; (4) add **resilience4j** circuit breakers + exponential backoff because partner SOAP endpoints are often slow/flaky, plus an **idempotency key** so retries don't double-settle (critical: combine with WS-RM exactly-once or your own dedup table); (5) push every message through an immutable **audit log** (regulatory requirement) keyed by a propagated correlation ID; (6) bulkhead thread pools per partner so one slow partner can't exhaust the gateway. **Trade-offs:** the façade adds a hop and a translation cost, but it isolates legacy complexity, lets you migrate partners independently, and is the pattern I'd actually ship in a regulated environment.

### Q23. [Theory] How do WS-Policy and the WS-I Basic Profile fit into governing SOAP at enterprise scale?

**WS-Policy** is a machine-readable way to attach capabilities and requirements (security tokens required, encryption algorithms, reliable-messaging assertions) to a WSDL, so a client's tooling can auto-configure to meet the server's demands instead of relying on out-of-band docs. **WS-SecurityPolicy** specializes it for security assertions. The **WS-I Basic Profile** is a set of interoperability constraints (e.g., document/literal wrapped, no rpc/encoded, specific fault rules) that the industry adopted to tame the WS-* spec sprawl and guarantee that a .NET client and a Java server actually talk. At enterprise scale, governing SOAP means enforcing a profile (WS-I BP + your internal security policy) across all services via a registry/repository (UDDI historically, now usually an internal API catalog) and CI checks that validate every published WSDL against the profile. The expert insight: the WS-* stack's combinatorial complexity is precisely why profiles and policy automation exist — and why, absent that governance, SOAP estates rot into unmaintainable interop nightmares.

---

## 🧩 Extended Questions — Set 1: Deeper Theory & Practical Operations

### 🟢 Basic — extended

#### Q24. [Theory] What is the difference between SOAP 1.1 and SOAP 1.2?

SOAP 1.1 (2000, a W3C Note) and SOAP 1.2 (2003/2007, a full W3C Recommendation) are the two versions you encounter in the wild, and the differences matter every time you debug an interop failure. The most visible change is the **envelope namespace**: SOAP 1.1 uses `http://schemas.xmlsoap.org/soap/envelope/` while SOAP 1.2 uses `http://www.w3.org/2003/05/soap-envelope`. A client built for one version will not parse the other's envelope, so a namespace mismatch is one of the most common first-contact errors. The fault structure also changed: 1.1 uses flat `<faultcode>`/`<faultstring>`/`<faultactor>`/`<detail>` elements, while 1.2 uses the structured `<Code>`/`<Reason>`/`<Node>`/`<Role>`/`<Detail>` hierarchy with `Value` and `Subcode`.

There are subtler but important transport-level changes. SOAP 1.1 relies on the `SOAPAction` HTTP header to identify the operation; SOAP 1.2 deprecates that in favor of an `action` parameter on the `application/soap+xml` content type (and changes the content type itself from `text/xml` to `application/soap+xml`). SOAP 1.2 also formally defines the **processing model** (roles, `mustUnderstand`) more rigorously and removes the rarely-interoperable SOAP encoding (`rpc/encoded`) from the required feature set.

```
                  SOAP 1.1                          SOAP 1.2
Envelope NS    schemas.xmlsoap.org/soap/envelope/  www.w3.org/2003/05/soap-envelope
Content-Type   text/xml                            application/soap+xml
Operation hint SOAPAction header (required)        action= param on content type
Fault model    faultcode/faultstring (flat)        Code/Reason/Detail (structured)
Status         W3C Note                            W3C Recommendation
```

The practical takeaway: a service can support both, but your client must speak the same version. When generating stubs, the WSDL `binding` namespace (`http://schemas.xmlsoap.org/wsdl/soap/` vs `.../soap12/`) tells you which version the operation uses, so always check it before assuming.

#### Q25. [Practical] How do you test a SOAP service manually with curl and SoapUI?

For a quick smoke test I reach for **curl** because it requires no setup and shows me the raw wire bytes — which is exactly what I want when debugging namespace or `SOAPAction` problems. You POST the envelope to the endpoint with the right content type. For SOAP 1.1 you must include the `SOAPAction` header (its value comes from the WSDL's `soapAction` attribute); for SOAP 1.2 the action goes inside the content type.

```bash
# SOAP 1.1 — note the SOAPAction header
curl -X POST http://host:8080/ws/quotes \
  -H 'Content-Type: text/xml; charset=utf-8' \
  -H 'SOAPAction: "http://trade.example.com/quotes/getQuote"' \
  --data-binary @request.xml

# SOAP 1.2 — action folded into content type, no SOAPAction header
curl -X POST http://host:8080/ws/quotes \
  -H 'Content-Type: application/soap+xml; charset=utf-8; action="http://trade.example.com/quotes/getQuote"' \
  --data-binary @request.xml
```

For anything beyond a one-off, **SoapUI** is the standard tool: point it at the `?wsdl` URL and it generates sample requests for every operation with the correct envelope, namespaces, and placeholder values. It also handles WS-Security configuration (keystores, signing, encryption), MTOM attachments, and lets you save reusable test suites and assertions. The reason I default to SoapUI for partner integration work is that it removes the entire class of hand-typed namespace/prefix errors and lets me share a ready-to-run project with the partner. curl is for "is the endpoint even alive and what does the raw fault look like"; SoapUI is for systematic functional and security testing.

#### Q26. [Theory] What is the role of namespaces in SOAP and XSD, and why do they cause so many bugs?

XML namespaces exist to disambiguate element and attribute names that come from different vocabularies — the SOAP envelope elements, the WS-Security header elements, and your business payload all coexist in one document, and namespaces are what keep `<Body>` (SOAP) distinct from a hypothetical `<Body>` in your domain schema. Each namespace is identified by a URI (just an identifier, not necessarily a fetchable URL), and prefixes like `soap:`, `wsse:`, or `tns:` are local aliases bound to those URIs. The crucial rule that trips people up: **the prefix is arbitrary and the URI is what matters** — `<soap:Body>` and `<env:Body>` are identical to a conformant parser as long as both prefixes are bound to the same envelope URI.

The reason namespaces cause so many SOAP bugs is twofold. First, schema validation is namespace-aware: if your payload element is in the wrong target namespace (or in no namespace when the XSD declares `elementFormDefault="qualified"`), validation fails even though the element name looks right. Second, many legacy and hand-rolled SOAP servers are subtly **non-conformant and prefix-sensitive** — they string-match on `soap:Body` and choke if you send `env:Body`, despite the spec saying they are equivalent.

```xml
<!-- These two are IDENTICAL to a conformant parser; some legacy servers reject the second -->
<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope">
  <soap:Body> ... </soap:Body>
</soap:Envelope>

<env:Envelope xmlns:env="http://www.w3.org/2003/05/soap-envelope">
  <env:Body> ... </env:Body>
</env:Envelope>
```

The practical defense: when generating clients, let the tooling manage prefixes, and when integrating with a flaky legacy server, capture a known-good request and **match its prefixes exactly** rather than trusting the spec's equivalence guarantee. `elementFormDefault` in the XSD is the single setting I check first when "the element is there but validation fails" — it controls whether child elements must be namespace-qualified.

#### Q27. [Practical] You get "Content-Type text/xml not supported" calling a SOAP service. How do you diagnose it?

This error almost always means a **SOAP version mismatch** between client and server. A SOAP 1.2 endpoint expects `application/soap+xml` and rejects the SOAP 1.1 `text/xml` content type your client is sending (or vice versa). The first thing I do is open the WSDL and look at the binding namespace: `http://schemas.xmlsoap.org/wsdl/soap/` means SOAP 1.1, `http://schemas.xmlsoap.org/wsdl/soap12/` means SOAP 1.2. That tells me which content type and envelope namespace the server actually wants.

```bash
# Reproduce by being explicit about the content type the server expects
curl -v -X POST http://host/service \
  -H 'Content-Type: application/soap+xml; charset=utf-8' \
  --data-binary @soap12-request.xml
# -v shows request + response headers so you confirm what was actually sent
```

The diagnosis sequence: (1) confirm the version from the WSDL binding; (2) check whether your client framework defaulted to the wrong version — in JAX-WS the binding ID `javax.xml.ws.soap.SOAPBinding.SOAP12HTTP_BINDING` vs the 1.1 binding controls this; (3) verify the **envelope namespace** matches the content type (a 1.2 content type with a 1.1 envelope namespace also fails); (4) capture the raw bytes with curl `-v` or Wireshark to see exactly what went on the wire, because frameworks sometimes silently override headers. The root cause is nearly always that the generated stub or the manually built request is on the opposite SOAP version from the endpoint, so aligning version, content type, and envelope namespace together resolves it.

### 🟡 Intermediate — extended

#### Q28. [Theory] Explain JAXB marshalling/unmarshalling and why JAXBContext must be cached.

JAXB (Jakarta XML Binding, formerly Java Architecture for XML Binding) is the layer that converts between Java objects and XML. **Marshalling** turns a Java object graph into XML; **unmarshalling** parses XML back into objects. In JAX-WS this happens transparently for every request and response — your `@WebMethod` parameters and return values are JAXB-bound POJOs, usually generated from the XSD via `xjc` or `wsimport`. The mapping is driven by annotations (`@XmlRootElement`, `@XmlElement`, `@XmlType`, `@XmlAccessorType`) that the codegen places on the generated classes.

The performance-critical fact every senior engineer must know: **building a `JAXBContext` is expensive** — it scans classes via reflection and constructs the binding metamodel, often taking tens to hundreds of milliseconds — but the resulting context is **thread-safe and immutable**, so it should be created once and reused for the life of the application. `Marshaller` and `Unmarshaller` instances created from it are *not* thread-safe and should be created per-use (they're cheap) or pooled. Rebuilding `JAXBContext` per request is a classic latency and CPU regression that doesn't show up until production load.

```java
// CORRECT: build once, reuse forever (thread-safe)
private static final JAXBContext CTX = newContext();
private static JAXBContext newContext() {
    try { return JAXBContext.newInstance(Quote.class); }
    catch (JAXBException e) { throw new ExceptionInInitializerError(e); }
}
// Marshaller is NOT thread-safe — create per call (cheap)
Marshaller m = CTX.createMarshaller();
m.marshal(quote, writer);
```

The trade-off to be aware of: JAXB builds the full object tree in memory (`O(n)` heap in message size), so for very large messages you stream with StAX or use MTOM for binary parts instead of letting JAXB materialize everything. But for typical messages, the correct pattern is cached context + per-call marshaller, and getting this wrong is one of the most common SOAP performance pitfalls.

#### Q29. [Practical] How do you configure a connection/read timeout and connection pooling for a JAX-WS client?

Untuned SOAP clients are a frequent cause of production cascading failures: a slow partner endpoint with no read timeout will hold threads indefinitely until the whole pool is exhausted. So the first thing I configure on any outbound SOAP client is **connect and read timeouts**. In JAX-WS the standard (if awkwardly named) way is to set request-context properties on the `BindingProvider`, though the exact property keys differ between the JDK's reference implementation and Apache CXF.

```java
QuoteService port = new QuoteServiceClient().getQuoteServiceSoap();
Map<String,Object> ctx = ((BindingProvider) port).getRequestContext();

// JAX-WS RI (Metro) property keys
ctx.put("com.sun.xml.ws.connect.timeout", 3000);   // ms to establish TCP
ctx.put("com.sun.xml.ws.request.timeout", 10000);  // ms to read response
// Also set the standard JDK keys as a fallback for the default HttpURLConnection transport
ctx.put("javax.xml.ws.client.connectionTimeout", 3000);
ctx.put("javax.xml.ws.client.receiveTimeout", 10000);
```

For **connection pooling**, the default `HttpURLConnection` transport is limited, so in serious deployments I switch the transport to Apache HttpClient (CXF makes this easy via an `HTTPConduit` with a `HTTPClientPolicy`, or you configure the connection manager directly). With CXF you set `setConnectionTimeout`, `setReceiveTimeout`, `setMaxRetransmits`, and a pooled connection manager so concurrent calls reuse keep-alive connections instead of doing a TLS handshake every time.

The why behind all this: SOAP calls to external partners are the least reliable part of a banking integration, so I always pair timeouts with a **circuit breaker** (resilience4j) and a bounded thread pool / bulkhead per partner. A timeout without a circuit breaker just fails slower; a circuit breaker without a timeout never trips because the call never returns. The two together are what keep one flaky partner from taking down the gateway.

#### Q30. [Theory] What is chunked vs buffered SOAP transmission and how does it affect WS-Security?

By default many SOAP stacks **buffer** the entire outbound message in memory before sending it, which lets them set an accurate `Content-Length` header and, critically, compute things that require the whole message — like an XML Signature digest. **Chunked transfer encoding** instead streams the message in pieces without a precomputed length, which reduces memory for large payloads but interacts badly with some features and some servers. CXF exposes this via `HTTPClientPolicy.setAllowChunking(true|false)` and `setChunkingThreshold(...)`.

The WS-Security interaction is the subtle part. XML Signature requires **canonicalizing** and digesting the signed elements, and XML Encryption transforms element content — both generally need the relevant subtree fully materialized, so security processing tends to force buffering of at least the signed/encrypted portions regardless of the chunking setting. Worse, some legacy servers and proxies don't handle chunked POSTs correctly and silently truncate or reject them, producing baffling "premature end of file" or signature-validation failures that look like security bugs but are really transport bugs.

```
Buffered:  [build full message] → [sign/encrypt] → [Content-Length: N] → send   (more memory, max compat)
Chunked:   [stream pieces] ... no Content-Length ...                              (less memory, can break old servers / signing)
```

My operational rule: keep **chunking off** for WS-Security partner integrations unless I've confirmed the partner handles it, because the failure mode (intermittent signature errors under certain message sizes) is brutal to diagnose. For large unsigned/unencrypted MTOM uploads to a modern endpoint, chunking is the right choice to avoid `O(n)` heap. This is exactly the kind of transport-vs-security trade-off that only surfaces in production, so I document the chosen setting per partner.

#### Q31. [Practical] How do you log and capture raw SOAP request/response messages for debugging in CXF and JAX-WS RI?

The single most useful debugging capability for SOAP is seeing the **exact bytes** on the wire, because most integration failures are namespace, prefix, header, or version mismatches invisible at the Java-object level. In **Apache CXF** you add the logging interceptors (or the newer `LoggingFeature`) to the bus or to a specific client/endpoint, which dumps the full inbound/outbound envelopes to the logger.

```java
// CXF — attach logging to a specific client
Client client = ClientProxy.getClient(port);
client.getInInterceptors().add(new LoggingInInterceptor());
client.getOutInterceptors().add(new LoggingOutInterceptor());
// or, modern: factory.getFeatures().add(new org.apache.cxf.ext.logging.LoggingFeature());
```

```bash
# JAX-WS RI (Metro) — enable via system properties, no code change
-Dcom.sun.xml.ws.transport.http.client.HttpTransportPipe.dump=true
-Dcom.sun.xml.ws.transport.http.HttpAdapter.dump=true
# (older builds use com.sun.xml.internal.ws.* on the JDK 8 internal copy)
```

A few operational cautions that separate a junior from a senior here. First, **never leave full message logging on in production** when WS-Security is involved — you will write signed tokens, encrypted-then-decrypted bodies, account numbers, and PII into your logs, which is both a compliance violation and a security hole; gate it behind a flag and mask sensitive elements. Second, for transport-level problems that the framework can't see (TLS handshake, chunking, proxy mangling), drop below the SOAP stack to **Wireshark** or an `mitmproxy`/Fiddler-style HTTPS proxy. Third, when comparing your envelope against a partner's known-good sample, diff them with whitespace normalized, because insignificant whitespace differences create false positives. Capturing raw traffic early is what turns a multi-day "it just fails" investigation into a ten-minute namespace fix.

#### Q32. [Theory] How does HTTP status code mapping work for SOAP, and why is everything "200 or 500"?

SOAP deliberately decouples the application-level outcome from the HTTP transport, which surprises people coming from REST where status codes carry rich semantics. In SOAP over HTTP, a **successful** response — even one whose body contains a business "no, that's denied" answer — comes back as **HTTP 200**, and a **SOAP Fault** comes back as **HTTP 500** (in SOAP 1.2; 1.1 is similar). The HTTP layer is treated as a dumb transport for the envelope; the *real* result lives inside the Body. This is why you cannot judge SOAP success by HTTP status alone — you must parse the body and check for a `<Fault>`.

This design has real consequences. It means SOAP doesn't get HTTP's free benefits the way REST does: a `404`, `401`, or `429` from an intermediary (load balancer, gateway, WAF) is *transport* failure, not an application fault, and your client must distinguish "the SOAP server returned a Fault" (HTTP 500 with a parseable Fault body) from "an infrastructure box returned HTML or a non-SOAP error" (HTTP 500/502/503 with no Fault). Conflating the two leads to clients trying to parse an nginx error page as a SOAP envelope.

```
HTTP 200 + <Body><GetQuoteResponse>...   → success (parse result)
HTTP 200 + <Body> business "denied"      → still "success" at HTTP level; logic in body
HTTP 500 + <Body><Fault>...              → SOAP Fault (parse Code/Reason/Detail)
HTTP 500/502/503 + HTML / no envelope    → infrastructure error, NOT a SOAP Fault
HTTP 401/407                             → transport auth/proxy, before SOAP even ran
```

The senior takeaway: write client error handling that **first checks the content type and whether the body is a parseable SOAP envelope**, then checks for a Fault, and only then trusts the payload. Retry logic must treat transport-level 5xx (no Fault) differently from a SOAP `Receiver` fault (which may be retryable) and a `Sender` fault (which is the client's fault and must not be blindly retried).

#### Q33. [Practical] A SOAP request works in SoapUI but fails from your application. What's your debugging approach?

This is one of the most common real-world SOAP puzzles, and the framing tells you the endpoint and credentials are fine — the difference is in *how the two clients construct the message*. My approach is to make both produce raw output and diff them. SoapUI sends a clean, conformant envelope; my application's stack may be adding, omitting, or reordering something. So step one is to **enable full request logging in my app** (CXF `LoggingFeature` or the JAX-WS RI dump properties) and capture the exact envelope it sends.

Then I diff against the working SoapUI request, looking for the usual suspects in priority order: (1) **namespace/prefix differences** — a prefix-sensitive legacy server, or `elementFormDefault` causing unqualified vs qualified children; (2) a missing or differently-cased **`SOAPAction` header** (SoapUI fills it from the WSDL automatically; a hand-built client may omit it); (3) **SOAP version mismatch** (1.1 vs 1.2 content type/envelope); (4) WS-Security header present in SoapUI's project config but not wired into the app; (5) **element ordering** inside a `sequence` — JAXB usually orders correctly, but hand-built DOM or a misannotated class can reorder and break strict validation; (6) character encoding / BOM differences.

```
SoapUI envelope  ─┐
                  ├─▶  normalize whitespace, diff  ─▶  spot the delta
App envelope     ─┘     (namespace? SOAPAction? security header? ordering?)
```

The reason this method works every time is that it converts a vague "my app is broken" into a concrete byte-level difference. The fix is then almost always one of: align the SOAP version, add the missing header, correct the target namespace on a JAXB class, or match the legacy server's prefix expectation. I explicitly avoid guessing-and-tweaking; capturing both raw messages and diffing them is faster and leaves an artifact I can attach to the partner ticket.

### 🟠 Advanced — extended

#### Q34. [Theory] Compare WS-Security UsernameToken, X.509, and SAML token profiles. When do you use each?

WS-Security defines several **token profiles** for proving identity in the SOAP header, and choosing among them is a real architectural decision driven by the trust model. **UsernameToken** carries a username and either a plaintext password (only acceptable over TLS) or a `PasswordDigest` (a SHA-1 hash of nonce + created-timestamp + password) to avoid sending the raw password. It's simple and good for internal or low-assurance scenarios, but it proves only *knowledge of a shared secret* and doesn't provide non-repudiation. **X.509 certificate** tokens (the BinarySecurityToken profile) let the sender sign the message with their private key, so the receiver can both authenticate the sender via the cert chain and get **non-repudiation** — the signature proves *this specific party* sent *this exact message*. This is what regulated banking partners mandate.

**SAML** assertions carry a set of claims (identity plus attributes/roles) issued and signed by a trusted third party — a **Security Token Service (STS)**. This is the foundation of **federated identity** and brokered trust (WS-Trust/WS-Federation): instead of every service trusting every client directly, clients obtain a SAML token from the STS, and services trust the STS. It scales trust across organizational boundaries and supports single sign-on.

| Profile | Proves | Non-repudiation | Typical use |
|---------|--------|-----------------|-------------|
| UsernameToken | Knowledge of shared secret | No | Internal/low-assurance (over TLS) |
| X.509 / Signature | Possession of private key | Yes | B2B, banking, signed messages |
| SAML (via STS) | Claims vouched by trusted issuer | Yes (signed assertion) | Federation, SSO, brokered trust |

My selection heuristic: UsernameToken only behind TLS for internal traffic; **X.509 signing+encryption** for any partner needing non-repudiation or per-message confidentiality; **SAML/WS-Trust** when you have many parties and want centralized, brokered trust rather than N×M direct cert exchanges. The combinatorial cert-management problem (every pair of partners exchanging and rotating certs) is precisely what pushes large estates toward an STS-issued SAML model.

#### Q35. [Practical] How do you defend a SOAP endpoint against XXE and billion-laughs attacks at scale?

XXE (XML External Entity) and entity-expansion DoS ("billion laughs") are the dominant SOAP/XML vulnerability class, and the fix is to **harden every XML parser** to disable DTDs and external entities. The problem at scale isn't knowing the fix — it's *guaranteeing* it's applied uniformly across dozens of services, libraries, and transitive parser instances, because a single unhardened `DocumentBuilderFactory` or `SAXParserFactory` somewhere in the stack reopens the hole.

```java
// Harden a parser factory — disable DTDs entirely (strongest)
DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
dbf.setExpandEntityReferences(false);
dbf.setXIncludeAware(false);
dbf.setNamespaceAware(true);
```

Disallowing the doctype declaration outright kills both XXE (no external entities can be declared) and billion-laughs (no internal entity recursion to expand). For SOAP specifically, neither WS-Security nor normal envelopes need a DTD, so disabling it has no functional cost. Where you can't disallow doctypes for some legacy reason, `FEATURE_SECURE_PROCESSING` plus the `jdk.xml.entityExpansionLimit` / `jdk.xml.totalEntitySizeLimit` system properties bound the expansion to defeat the DoS.

The "at scale" answer is governance, not heroics. I (1) centralize XML parsing through a hardened factory utility that all services import, so there's one place to get it right; (2) add a **static-analysis / CI check** (e.g., a Semgrep or SpotBugs rule) that fails the build if anyone instantiates a parser factory without the secure settings; (3) keep the SOAP stack (CXF/Metro) patched, since they ship their own hardening but have had CVEs; and (4) run a periodic scan with a known XXE payload against staging endpoints to confirm the defense end-to-end. The staff-level insight from Q21 holds: you *automate* the hardening across the estate rather than fixing it per incident, because the attack surface is every parser, and humans forget.

#### Q36. [Practical] How do you implement idempotency and safe retries for a SOAP payment operation?

A payment "settle" or "transfer" operation must never execute twice even though the network *will* eventually give you an ambiguous failure (you sent the request, the timeout fired, but you don't know whether the server processed it). The correct pattern is an **idempotency key**: the client generates a unique key per logical operation and the server deduplicates on it, so a retry with the same key returns the original result instead of performing a second debit.

In SOAP there are two clean places to carry the key. The protocol-native option is **WS-Addressing `MessageID`** combined with **WS-ReliableMessaging** configured for *exactly-once* delivery — WS-RM's sequence numbers and acknowledgements let the stack itself deduplicate retransmissions. If you don't control both ends' WS-RM stack (common with partners), carry an explicit idempotency key in a SOAP header element and implement your own server-side dedup table.

```xml
<soap:Header>
  <wsa:MessageID>urn:uuid:7f3c...e91</wsa:MessageID>          <!-- WS-Addressing -->
  <pay:IdempotencyKey>txn-2026-06-16-000abc</pay:IdempotencyKey>
</soap:Header>
```

```
Server dedup logic (pseudo):
  on request(key):
     row = INSERT key INTO processed_txns IF NOT EXISTS   -- atomic, unique constraint
     if row already existed:
         return stored_response[key]        -- replay original result, do NOT re-debit
     else:
         result = perform_payment()
         stored_response[key] = result      -- persist before ACK
         return result
```

The non-negotiable details: the dedup store must be **durable and transactionally consistent** with the payment itself (ideally the same DB transaction or an outbox pattern), or a crash between "performed payment" and "stored response" lets a retry double-pay. Retries on the client must use **exponential backoff with jitter** and must **only retry transport failures and `Receiver` (server-side) faults** — never retry a `Sender` fault (the request was bad; retrying just fails again) and never retry without the *same* idempotency key (that defeats the whole mechanism). Pair this with the circuit breaker and bulkhead from Q29 so retries don't amplify a partner outage into your own overload.

#### Q37. [Theory] How does XML Digital Signature canonicalization (C14N) work and why does it break interop?

XML Signature signs not the raw bytes but a **canonical form** of the targeted elements, produced by a Canonicalization (C14N) algorithm. The reason is that XML has many byte-level representations that are semantically identical — different attribute ordering, whitespace in tags, namespace declaration placement, `&#xA;` vs literal newline, omitted vs explicit defaults. If you signed raw bytes, any intermediary that re-serialized the XML (which parsers routinely do) would invalidate the signature even though nothing meaningful changed. C14N normalizes all of that to a deterministic byte sequence so both signer and verifier compute the digest over the same thing.

The interop trap is that there are **multiple incompatible C14N algorithms**, and signer and verifier must use the exact same one. **Inclusive C14N** (`xml-c14n11`) pulls in *all* in-scope namespace declarations from ancestor elements into the signed subtree — which breaks the moment that subtree is moved into a different document (e.g., extracted and forwarded), because the ancestor context changed. **Exclusive C14N** (`xml-exc-c14n`) includes only the namespaces actually *used* by the signed element, making the signature stable under relocation — which is why WS-Security mandates Exclusive C14N. Choosing inclusive C14N in a multi-hop SOAP scenario is a classic source of "signature verifies in test, fails in production."

```
Signer:   subtree ──C14N(exc)──▶ canonical bytes ──digest──▶ sign with private key
Verifier: subtree ──C14N(exc)──▶ canonical bytes ──digest──▶ compare to decrypted signature
          (MUST use identical C14N alg + transforms or the digests differ → INVALID signature)
```

Beyond the algorithm choice, the **transform chain order** matters (e.g., enveloped-signature transform then C14N), and a mismatch produces a valid-looking but failing signature with an unhelpful "digest mismatch" error. My debugging move when a signature fails across systems: confirm both sides use Exclusive C14N, dump the canonicalized bytes from each side, and diff them — the difference (a stray namespace, a whitespace node, an attribute order) is the bug. Understanding C14N is what separates someone who can configure WSS4J from someone who can actually *debug* a cross-vendor signature failure.

#### Q38. [Coding] Write a JAX-WS @WebFault custom exception that maps to a structured SOAP Fault detail.

**Problem:** A `getQuote` operation must return a machine-parseable business fault (error code + symbol) in the SOAP Fault `<Detail>`, not just a string, so consumers can branch on the code.

```java
// 1. The fault detail bean (the contract's <Detail> content) — JAXB-bound
import jakarta.xml.bind.annotation.*;

@XmlRootElement(name = "QuoteFault", namespace = "http://trade.example.com/quotes")
@XmlAccessorType(XmlAccessType.FIELD)
public class QuoteFaultInfo {
    private String code;     // e.g. "SYM-404"
    private String symbol;   // the offending symbol
    // getters/setters omitted for brevity
    public QuoteFaultInfo() {}
    public QuoteFaultInfo(String code, String symbol) { this.code = code; this.symbol = symbol; }
}
```

```java
// 2. The checked exception, annotated so JAX-WS maps it to a Fault with that detail
import jakarta.xml.ws.WebFault;

@WebFault(name = "QuoteFault", targetNamespace = "http://trade.example.com/quotes")
public class QuoteException extends Exception {
    private final QuoteFaultInfo faultInfo;            // @WebFault requires a getFaultInfo()
    public QuoteException(String message, QuoteFaultInfo faultInfo) {
        super(message);
        this.faultInfo = faultInfo;
    }
    public QuoteFaultInfo getFaultInfo() { return faultInfo; }
}
```

```java
// 3. Throwing it from the service — becomes a structured SOAP Fault on the wire
@Override
public double getQuote(String symbol) throws QuoteException {
    if (!exists(symbol))
        throw new QuoteException("Unknown symbol",
                                 new QuoteFaultInfo("SYM-404", symbol));
    return lookupPrice(symbol);
}
```

The resulting wire fault carries the bean inside `<soap:Detail>`, so the client's generated stub throws a typed `QuoteException` whose `getFaultInfo()` exposes `code` and `symbol` — letting the consumer write `catch (QuoteException e) { if ("SYM-404".equals(e.getFaultInfo().getCode())) ... }` instead of string-matching a reason text.

**Edge cases:** the `@WebFault` class *must* expose a `getFaultInfo()` returning the JAXB bean, or codegen on the client side won't reconstruct the typed exception; the fault bean's namespace must match the WSDL/XSD or unmarshalling drops the detail; and you should map only *expected business* failures to checked `@WebFault` exceptions — unexpected `RuntimeException`s should become generic `Receiver` faults, not leak internals. **Time/Space:** `O(d)` in the size of the detail bean, negligible versus the payload; the value is consumer-side branching on a stable code rather than brittle text parsing.

#### Q39. [Practical] How do you migrate a SOAP service to REST/gRPC incrementally without a big-bang rewrite?

The big-bang rewrite is the anti-pattern — it bets the business on a flip-the-switch cutover of a system that partners depend on and regulators audit. The proven incremental pattern is the **strangler fig**: stand up a façade in front of the SOAP service and migrate consumers and capabilities behind it one at a time until the old surface can be retired. I covered the gateway architecture in Q22; here the focus is the *migration sequence*.

```
Phase 1: Façade in front, SOAP still does the work
  new clients ─▶ REST/gRPC façade ─▶ translate ─▶ existing SOAP core (unchanged)
  old clients  ─▶ SOAP endpoint (untouched, still live)

Phase 2: Reimplement capability-by-capability behind the façade
  façade routes /quotes → new native service ;  /settle → still SOAP core

Phase 3: Migrate remaining SOAP consumers to façade, then retire SOAP endpoint
```

The sequencing rules that make it safe: (1) **never change the SOAP contract during migration** — old consumers keep hitting the untouched endpoint while you build alongside; (2) **migrate read-only / low-risk operations first**, leaving money-movement and regulated flows (which need WS-Security non-repudiation) for last or keeping them on SOAP permanently behind the façade; (3) **run both paths in parallel and reconcile** — shadow-call the new implementation, compare its output to the SOAP result, and only cut over an operation once results match for a sustained period; (4) **migrate consumers, not just the implementation** — publish the REST/gRPC contract, onboard each consumer with a deprecation timeline, and track usage so you know when the last SOAP caller is gone before retiring it.

The judgment most candidates miss: some operations should **never** be migrated. If a partner contractually requires WS-Security message-level signing or WS-RM exactly-once, the REST equivalent (JWS/JWE + an idempotency layer) may not satisfy the audit/non-repudiation requirement, so the right end-state is a *permanent* hybrid — modern façade for velocity, certified SOAP core for the regulated flows. Incremental migration is as much about deciding what *not* to move as about moving things.

### 🔴 Expert — extended

#### Q40. [Theory] Why did the WS-* "WS-Death Star" stack lose to REST, and what does that teach about protocol design?

The WS-* stack — WS-Security, WS-Addressing, WS-ReliableMessaging, WS-AtomicTransaction, WS-Coordination, WS-Policy, WS-Trust, WS-Federation, MTOM, and dozens more — was technically comprehensive and, for a time, the consensus enterprise standard. It lost mainstream mindshare to REST/JSON for reasons that are a masterclass in protocol economics. **Combinatorial complexity:** the specs composed in subtle ways, every vendor implemented a slightly different subset, and "interoperable" required a profile (WS-I) just to constrain the chaos — so the promised plug-and-play interop was expensive in practice. **Tooling-dependence:** WS-* was effectively unusable without heavy codegen and framework support, which raised the barrier to entry precisely when the web was being built by people who could `curl` a JSON endpoint and read the response by eye.

The deeper lesson is about **where complexity should live**. WS-* tried to standardize *everything* (security, reliability, transactions) into the messaging layer as reusable, composable protocols — an attractive engineering ideal. REST won by pushing most of that complexity *out* of the protocol and *up* into the application or *down* into existing layers: TLS for transport security, OAuth/JWT for auth, HTTP semantics for caching and idempotency, and "just retry / use a queue" for reliability. The market consistently preferred a simpler 80%-solution that an individual developer could adopt incrementally over a complete-but-heavy standard that required organizational buy-in and tooling.

```
WS-*:  complexity centralized IN the protocol stack (composable specs, needs tooling + profiles)
REST:  complexity pushed OUT to layers (TLS, OAuth, HTTP caching) and UP to the app
        → lower barrier to entry won, even at the cost of fewer built-in guarantees
```

The senior takeaway for designing any protocol or platform: **adoption is dominated by the marginal cost to the next individual developer**, not by feature completeness. WS-* optimized for the enterprise architect's checklist; REST optimized for the developer's first five minutes. That's also *why* WS-* survives exactly where the individual-developer dynamic doesn't apply — regulated B2B with mandated guarantees and dedicated integration teams, where the WS-Security/WS-RM guarantees are worth their complexity cost. Recognizing which world you're in is the actual expertise.

#### Q41. [Practical] A partner's WS-Security signatures intermittently fail under load. Walk through the incident.

Intermittent — not total — signature failures under load are a fingerprint, and the experienced move is to resist the urge to blame the crypto and instead look at what *changes with load*. My incident playbook: first, **confirm the pattern** from logs — does it correlate with throughput, message size, a specific node, or time of day? "Intermittent under load" most often points to a small set of culprits, ranked by my prior probability.

The leading suspect is a **non-thread-safe component being shared across threads**. WSS4J/`Crypto` setup, `Merlin` keystores, and especially **`Marshaller`/`Unmarshaller`/`Signature` objects are not thread-safe**; if someone cached a single instance as a field and concurrency exposes it, you get sporadic corruption of the canonicalized bytes and thus invalid digests — perfectly explaining "works at low load, fails at high." Second suspect: **clock skew with the WS-Security `Timestamp`** — under load, retries and queueing widen the gap between `Created` and receipt; if the partner's TTL is tight and either side's NTP has drifted, valid messages get rejected as expired/replayed. Third: a **load balancer or proxy mutating the message** (re-chunking, re-encoding, adding/stripping whitespace) on some nodes only — which the canonicalization-sensitive signature catches (ties back to Q30 chunking and Q37 C14N).

```
Triage order for intermittent-under-load signature failure:
  1. Thread-safety:   shared Marshaller/Signature/Crypto across threads?  → make per-call/pool
  2. Clock skew:      Timestamp TTL vs NTP drift; widens under queueing    → fix NTP, widen TTL
  3. Proxy mutation:  LB/WAF re-chunking or re-encoding on some nodes      → pin transport, disable chunking
  4. C14N/transform:  inclusive vs exclusive mismatch surfacing on big msgs → enforce exc-c14n
  5. Cert/keystore:   stale cert cache after a rotation on one node        → coordinate rotation
```

To isolate it I'd capture raw envelopes from the failing requests (gated, masked), pull the canonicalized bytes, and diff a failing one against a passing one — the delta localizes the cause fast. The resolution depends on which suspect it is, but the meta-lesson I'd convey in the interview is the *diagnostic discipline*: intermittent + load-correlated → think concurrency, time, and infrastructure mutation before touching the cryptographic configuration, because the crypto itself is deterministic and rarely the actual fault.

#### Q42. [Theory] How do you govern a large estate of SOAP services for consistency, security, and discoverability?

Governing dozens or hundreds of SOAP services is the problem that, left unmanaged, turns an estate into the "interop nightmare" of Q23. The governance has three pillars. **Discoverability:** there must be a single source of truth for "what services exist, who owns them, what version, and where." Historically this was **UDDI** (the WS-* registry standard), but UDDI largely failed in practice; modern estates use an internal **API catalog / service registry** (often the same platform that catalogs REST/gRPC) where every WSDL is published, searchable, and linked to its owning team and SLA.

**Consistency and conformance:** you enforce a profile — **WS-I Basic Profile** plus your internal standards (document/literal wrapped only, no rpc/encoded, mandated SOAP version, fault structure conventions, naming, versioning-by-namespace from Q18) — and you make conformance **automated, not aspirational**. That means CI gates that validate every published WSDL/XSD against the profile and against schema-compatibility rules (reject a non-backward-compatible change to an existing version), plus contract tests so a provider can't silently break consumers.

**Security as policy:** mandated WS-Security posture (signing/encryption requirements, allowed algorithms — no SHA-1, exclusive C14N, timestamp TTLs) expressed where possible as **WS-Policy/WS-SecurityPolicy** attached to WSDLs so clients auto-configure, and enforced by the same CI plus runtime checks at the gateway. Centralized key management (HSM, automated cert-rotation alerts) and the hardened-parser governance from Q35 sit under this pillar.

```
                 ┌──────────────────────────────────────────────┐
   Providers ──▶ │  CI conformance gate                          │
                 │   • WS-I BP + internal profile validation     │
                 │   • schema backward-compat check (vs prev ver)│ ──▶ publish to ──▶ API Catalog
                 │   • WS-SecurityPolicy / parser-hardening rule │      (registry)     (owners, SLA,
                 │   • contract tests vs registered consumers    │                     versions, WSDLs)
                 └──────────────────────────────────────────────┘
   Runtime: gateway enforces security policy, emits audit log + correlation IDs (Q22)
```

The expert framing: governance is what makes WS-*'s power sustainable. The stack's combinatorial complexity (Q40) means that *without* an enforced profile, a registry, and automated compatibility/security gates, every team makes locally-reasonable choices that are globally incompatible, and the estate rots. With them, you get the WS-* guarantees (Q34, Q37) at fleet scale. The deliverable I'd push for as a staff/principal engineer is the **automated gate plus the catalog**, because manual review of WSDLs does not scale and humans miss the subtle backward-incompatibility and SHA-1-still-allowed cases that the CI rule catches every time.

#### Q43. [Practical] Your SOAP gateway shows rising latency and GC pressure under peak load. How do you find and fix it?

Rising latency *with* GC pressure on an XML-heavy gateway points hard at **allocation churn from message processing**, and the staff-level approach is to measure before changing anything. I'd start with the JVM: capture a **GC log** and a **heap allocation profile** (async-profiler in allocation mode, or a flight recording). XML/SOAP gateways have a signature heap profile — large transient `char[]`/`byte[]`/DOM-node allocations per request — so the profiler usually points straight at marshalling.

The highest-probability root causes, in order: (1) **`JAXBContext` being rebuilt per request** (Q28) — catastrophic for both CPU and allocation; fix is a cached static context. (2) **DOM-based parsing of large messages** materializing the whole tree (`O(n)` heap each); fix is StAX streaming for large payloads and MTOM for binaries so bytes don't get Base64'd into the DOM (Q10). (3) **Unbounded buffering** of request/response bodies (and full message logging left on — Q31 — writing huge strings to logs); fix is bounded buffers, streaming, and gating debug logging. (4) **No connection pooling / TLS handshake per call** (Q29) inflating latency and creating session-object garbage; fix is a pooled HTTP transport with keep-alive.

```
Diagnose:  GC log + async-profiler (alloc mode) + per-request latency histogram
           ▼
Likely:    JAXBContext rebuilt? ── cache it (static, thread-safe)        → big CPU+alloc win
           DOM for big msgs?     ── StAX streaming / MTOM for binaries
           buffering + logging?  ── bound buffers, gate full-message logs
           handshake per call?   ── pooled keep-alive HTTP transport
           ▼
Verify:    re-profile under the same load; confirm alloc rate + p99 dropped, GC pauses shrank
```

Beyond the XML specifics, I'd check that the gateway has **bulkheads per partner** (Q22/Q29) so one slow downstream doesn't back up threads and balloon in-flight buffers, and that timeouts are set so requests don't pile up. The discipline I'd emphasize in the interview is **profile → hypothesize → fix one thing → re-measure**: GC pressure on a SOAP gateway has a short list of usual suspects, but you confirm with an allocation profile rather than guessing, because the fix for "rebuilt JAXBContext" (one line) and the fix for "DOM instead of streaming" (significant refactor) have wildly different costs and you want to spend effort where the profiler tells you the allocations actually are.

#### Q44. [Theory] What is the SOAPAction HTTP header and is it still needed?

`SOAPAction` is an HTTP request header, originally mandatory in SOAP 1.1, whose value identifies the *intent* of the request — typically a URI naming the operation being invoked. Its purpose was to let firewalls, routers, and the server itself dispatch or filter a request *without parsing the XML body*. A server could route to the right handler, or a firewall could allow/deny by operation, just by reading one header. The value comes from the WSDL binding's `soapAction` attribute on each operation.

In SOAP 1.1 the header is required even if empty (`SOAPAction: ""`), and omitting it or sending the wrong value is a frequent cause of "operation not found" or HTTP 500 from strict servers — a classic interop gotcha because some servers dispatch on `SOAPAction` rather than the body element. SOAP 1.2 deprecated the standalone header and folded the action into the content type as an `action` parameter: `Content-Type: application/soap+xml; action="..."`.

```
SOAP 1.1:  POST ...                                SOAP 1.2:  POST ...
           Content-Type: text/xml                             Content-Type: application/soap+xml;
           SOAPAction: "http://.../getQuote"                              action="http://.../getQuote"
```

The practical rule: always populate `SOAPAction` for 1.1 from the WSDL, quote it, and never assume the server ignores it — modern stacks like CXF often dispatch on the body element and treat `SOAPAction` as advisory, but legacy and .NET servers may require an exact match. When a call mysteriously 500s with a valid-looking body, a missing/mismatched `SOAPAction` is in my top three suspects.

#### Q45. [Practical] How do you secure a SOAP endpoint's WSDL exposure — should `?wsdl` be public?

By default most JAX-WS/CXF stacks publish the WSDL at the endpoint URL with `?wsdl` appended, and the imported XSDs at `?xsd=1` etc. For an internal or partner-only service, leaving that publicly reachable is an **information-disclosure** concern: it reveals every operation, data type, internal namespace, and sometimes server/framework version to anyone who probes the URL, which aids reconnaissance. The first decision is whether the WSDL needs to be reachable at all by unauthenticated callers.

Common hardening options, in increasing strictness: (1) keep `?wsdl` live but put the endpoint behind network controls (mTLS, IP allowlist, API gateway) so only known partners reach it; (2) **disable dynamic WSDL publishing** and distribute the WSDL to partners out-of-band (email/portal) — in CXF you can set the published endpoint to not serve `?wsdl`, or front it so the gateway returns 404 for `?wsdl`; (3) serve a **static, sanitized WSDL** that omits internal endpoint hosts and uses the public-facing URL, rather than the auto-generated one that may leak internal hostnames in the `<soap:address location="...">`.

```
Anti-pattern:  public https://api.bank.com/settle?wsdl  → anyone enumerates all operations + types
Better:        WSDL behind mTLS/gateway, or distributed out-of-band; runtime ?wsdl returns 404 externally
Always:        sanitize <soap:address location> so it shows the public URL, not internal host:port
```

The why: the WSDL is a *contract artifact*, and partners legitimately need it, but "discoverable by the open internet" and "available to authenticated partners" are different requirements. I treat the WSDL like any other API documentation — version-controlled, deliberately published to the right audience — rather than relying on the framework's convenient-but-leaky default of serving it to everyone who appends `?wsdl`.

#### Q46. [Theory] How do `minOccurs`, `maxOccurs`, and `nillable` in XSD affect SOAP contract evolution?

These three XSD attributes govern cardinality and null-handling, and getting them right is the difference between a contract you can evolve and one that breaks consumers on every change. **`minOccurs`** sets the minimum number of times an element must appear (default `1` — i.e., required); **`maxOccurs`** the maximum (default `1`; `unbounded` for collections). **`nillable="true"`** is distinct: it allows the element to be *present but explicitly null* via `xsi:nil="true"`, which is semantically different from the element being absent (`minOccurs="0"`).

The evolution rule that flows from this: to add a field in a backward-compatible way you must make it **`minOccurs="0"`** (optional), because existing clients and existing stored messages don't include it, and a strict validator rejects a message missing a required element. This is the schema-level mechanism behind the versioning advice in Q18 — "additive, optional-only changes." Conversely, *tightening* cardinality (raising `minOccurs` from 0 to 1, or lowering `maxOccurs`) is always a breaking change because previously-valid messages become invalid.

```xml
<!-- Backward-compatible field addition: optional -->
<xsd:element name="middleName" type="xsd:string" minOccurs="0"/>

<!-- nillable: present-but-null is allowed and distinct from absent -->
<xsd:element name="closedDate" type="xsd:date" nillable="true" minOccurs="0"/>
<!-- on the wire:  <closedDate xsi:nil="true"/>   vs   element simply omitted -->
```

The subtlety that bites teams: `nillable` vs `minOccurs="0"` encode different intents — "the value is known to be null" vs "the value is not provided" — and consumers may treat them differently (e.g., a nil `closedDate` means "account explicitly has no close date" while absence means "we didn't send it"). For evolvable contracts I default new fields to `minOccurs="0"`, reserve `nillable` for genuine tri-state semantics, and never reorder elements within a `sequence` (order is significant and a reorder breaks strict parsers), which together keep the schema additive and safe.

#### Q47. [Practical] How do you implement WS-Security UsernameToken authentication on a Spring-WS server?

For a service that needs simple credential-based authentication over TLS, WS-Security `UsernameToken` is the lightweight choice, and in Spring-WS you wire it with a `Wss4jSecurityInterceptor` configured for validation. The interceptor extracts the `<wsse:UsernameToken>` from the SOAP header and validates the supplied credentials against a callback handler that you back with your user store.

```java
@Bean
public Wss4jSecurityInterceptor securityInterceptor() {
    var interceptor = new Wss4jSecurityInterceptor();
    interceptor.setValidationActions("UsernameToken");      // require a UsernameToken inbound
    interceptor.setValidationCallbackHandler(passwordCallback());
    return interceptor;
}

@Bean
public SimplePasswordValidationCallbackHandler passwordCallback() {
    var handler = new SimplePasswordValidationCallbackHandler();
    handler.setUsersMap(Map.of("partnerA", "s3cret"));      // in prod: look up + verify a hash
    return handler;
}
```

Two security points are non-negotiable. First, `UsernameToken` with `PasswordText` sends the password essentially in the clear inside the XML, so it **must run over TLS** — without transport encryption it's trivially sniffed; if you can't guarantee TLS end-to-end, use `PasswordDigest` (SHA-1 of nonce+created+password) plus a **nonce cache and timestamp check** to block replay. Second, never store or compare plaintext passwords on the server: the callback should verify against a salted hash, not echo back a stored plaintext (the `SimplePasswordValidationCallbackHandler` map above is illustrative only).

The trade-off versus X.509 (Q34): `UsernameToken` is simple and stateless to configure but proves only knowledge of a shared secret, gives no non-repudiation, and couples both sides to a credential-rotation process. It's appropriate for internal service-to-service calls behind TLS; for external regulated partners I escalate to signed X.509 as in Q12. The implementation effort is small; the discipline (TLS, hashing, replay protection) is what makes it actually secure rather than security theater.

#### Q48. [Theory] What is the difference between synchronous, asynchronous, and one-way SOAP message exchange patterns?

SOAP/WSDL defines several **message exchange patterns (MEPs)** that shape how request and response relate. The default is **request-response (synchronous)**: the client sends a request and blocks on the same HTTP connection until the response (or Fault) returns. It's simple and the right choice for fast operations, but it ties up a client thread and a connection for the whole server processing time, which is poor for long-running work. **One-way (fire-and-forget)** has an input but no output in the WSDL operation — the client sends and the transport returns an HTTP 202 Accepted with no SOAP body; useful for notifications and event ingestion where no business reply is needed (though without WS-RM you get no delivery guarantee).

**Asynchronous request-response** decouples the reply from the request connection. This is where **WS-Addressing** (Q9) earns its keep: the request carries a `ReplyTo` endpoint reference and a `MessageID`, the server immediately acknowledges, and later sends the response as a *new* message to the `ReplyTo` address, correlating it via `RelatesTo = MessageID`. The reply can arrive on a different connection, a callback endpoint, or a message queue — essential for operations that take seconds-to-hours (batch settlement, document processing) without holding a synchronous connection open.

```
Synchronous:   Client ──request──▶ Server      (one connection, client blocks)
                      ◀─response──
One-way:       Client ──request──▶ Server  →  202 Accepted, no body (fire-and-forget)
Async (WS-A):  Client ──request(ReplyTo=cb, MessageID=m)──▶ Server  → 202
               Server ──response(RelatesTo=m)──▶ Client's callback/queue   (later, new conn)
```

Choosing among them is a latency-and-coupling decision. Synchronous for sub-second operations and simplicity; one-way for events you don't need to confirm at the business level; asynchronous (WS-Addressing, often plus WS-RM for reliability) for long-running or high-fan-out workflows where holding connections open would exhaust resources. The senior insight is that async SOAP isn't just "non-blocking I/O" — it's a *protocol-level* pattern with explicit addressing and correlation in the header, which is why WS-Addressing exists and why it composes with reliable messaging.

#### Q49. [Practical] A consumer reports your newly-deployed schema change broke them, though you only "added a field." What went wrong and how do you prevent it?

This is a textbook backward-compatibility incident, and the phrase "only added a field" is the tell — *how* it was added matters more than *that* it was added. The most likely root causes: (1) the new element was added as **required** (`minOccurs="1"`, the XSD default) instead of optional, so the consumer's *outbound* messages now fail the server's validation; (2) the field was inserted **in the middle of a `<sequence>`**, and since sequence order is significant, the consumer's strictly-ordered marshaller now produces elements in an order the new schema rejects; (3) the change bumped a type or added an enum value the consumer's generated stubs don't recognize, causing unmarshalling to fail; (4) the consumer regenerated stubs against the new WSDL and a renamed/retyped element broke their code at compile time.

```
"Added a field" failure modes:
  required (minOccurs=1) new field   → consumer's messages now rejected as invalid
  inserted mid-<sequence>            → element order mismatch, strict validator fails
  retyped/renamed existing element   → unmarshalling or codegen breaks
  Safe version:  <xsd:element name="x" type="..." minOccurs="0"/>  appended at the END of the sequence
```

The immediate fix is to redeploy with the field as `minOccurs="0"` and, ideally, appended at the end of the sequence so existing message structures remain valid; if the change is genuinely breaking, it belongs under a **new target namespace / v2 endpoint** (Q18) running alongside v1, not mutated into the live schema.

Prevention is the real answer and it's process, not heroics: (1) a **CI schema-compatibility gate** that diffs the new XSD against the previously published version and fails the build on any non-additive change (new required element, reorder, retype, removal) — this is exactly the governance control from Q42; (2) **consumer-driven contract tests** so a provider change runs the registered consumers' expectations before deploy; (3) treating the WSDL/XSD as a versioned, reviewed artifact with the same rigor as a public API. The lesson I'd state in the interview: "additive" has a precise technical meaning in XSD (optional, appended, no retyping), and "I only added a field" is not the same as "I made a backward-compatible change" — the CI gate exists precisely because humans conflate the two.

#### Q50. [Theory] How do `wsimport`, `wsgen`, `xjc`, and CXF `wsdl2java` differ, and when do you use each?

These are the JAX-WS/JAXB code-generation tools, and knowing which does what saves a lot of confusion. **`wsimport`** is the contract-first client/server generator: WSDL in, Java SEI + JAXB types + service classes out — it's what you run to consume a service from its WSDL. **`wsgen`** is the *code-first* counterpart: it takes an annotated `@WebService` implementation class and generates the wrapper/fault beans and (optionally) the WSDL — i.e., it goes Java → WSDL/artifacts. **`xjc`** is purely the JAXB schema compiler: XSD in, JAXB-annotated Java classes out, with no SOAP/WSDL awareness — you use it when you only need data binding (e.g., the message payloads) without the full service plumbing.

```
Direction        Tool          Input          Output
contract-first   wsimport      WSDL (+XSD)    SEI + JAXB types + client/service classes
code-first       wsgen         @WebService    wrappers/fault beans (+ optional WSDL)
data binding     xjc           XSD            JAXB POJOs only (no SOAP)
contract-first   CXF wsdl2java WSDL (+XSD)    same role as wsimport, CXF flavor + more options
```

**CXF's `wsdl2java`** plays the same contract-first role as `wsimport` but is part of Apache CXF rather than the JDK/Metro RI, and it tends to offer more configuration (binding customization, frontends, async method generation) and is the natural choice when your runtime is CXF. The key historical wrinkle for 2026: `wsimport`/`wsgen` shipped *inside* the JDK only through Java 8; from Java 11 onward JAX-WS was removed from the JDK, so you get these tools via the standalone `jakarta.xml.ws`/Metro distribution or the Maven plugins (`jaxws-maven-plugin`, `cxf-codegen-plugin`) — and the generated code uses `jakarta.*` packages instead of `javax.*`.

My selection rule: contract-first (the enterprise default per Q6) means `wsimport` or CXF `wsdl2java` depending on runtime; reach for `xjc` alone when you're only binding XML data (e.g., a batch file format) and don't need the SOAP layer; and use `wsgen` only in code-first prototypes. Wiring these into Maven/Gradle plugins rather than running them by hand is what keeps generated code reproducible across the team and CI.

#### Q51. [Practical] How do you handle large file transfers in SOAP — streaming MTOM vs base64 vs chunked, with memory limits?

Large attachments are where naive SOAP handling causes OutOfMemoryErrors, because the default path Base64-encodes the bytes *inside* the XML and the stack materializes the whole DOM in heap — a 200 MB file becomes ~267 MB of Base64 text plus the parsed tree, easily `O(n)` multiples of the file size in memory. The first lever is **MTOM** (Q10): it carries the binary as a raw MIME attachment with an `<xop:Include>` reference, eliminating the 33% Base64 inflation and keeping bytes out of the XML parser.

But MTOM alone isn't enough for *truly* large files — you must also **stream** rather than buffer. With JAX-WS/CXF you type the field as `DataHandler` and enable streaming so the runtime reads/writes the attachment incrementally instead of loading it whole. On the server, you process the `InputStream` from the `DataHandler` in bounded chunks (e.g., copy to disk or to object storage in a buffered loop) and never call `.getBytes()` on the whole thing.

```java
@MTOM(threshold = 1024)            // inline tiny payloads, attach anything larger
@WebService
public class DocServiceImpl implements DocService {
    public void upload(@XmlMimeType("application/octet-stream") DataHandler file) throws IOException {
        try (InputStream in = file.getInputStream();
             OutputStream out = openStreamToStorage()) {
            byte[] buf = new byte[8192];          // bounded buffer — O(1) heap, not O(filesize)
            for (int n; (n = in.read(buf)) != -1; ) out.write(buf, 0, n);
        }
    }
}
```

```
Base64 inline:   file → 1.33× as text → into DOM → O(n) heap (worst)          ← avoid for big files
MTOM buffered:   file → MIME attachment → but read whole into memory → O(n)   ← better, still risky
MTOM streamed:   file → MIME attachment → 8KB-buffered copy → O(1) heap       ← correct for large files
```

The operational guardrails I always add: a **maximum message/attachment size limit** enforced at the gateway and the CXF policy (so a malicious or buggy client can't send a 10 GB body and OOM the server), an **MTOM threshold** so small fields stay inline (MIME overhead makes attachments slightly worse below a few hundred KB — Q10), and writing the stream straight to **durable storage (disk/S3)** rather than holding it in memory. The combination — MTOM + streaming `DataHandler` + a hard size cap — is what turns "works in test with a 1 MB file, OOMs in prod with a 500 MB file" into a robust transfer.

#### Q52. [Theory] What are SOAP intermediaries and the actor/role attribute, and how would you use one in practice?

A SOAP **intermediary** is a node that sits on the message path between the initial sender and the ultimate receiver and processes part of the message — typically specific header blocks — before forwarding it. This is the foundation of SOAP's layered processing model (Q13): cross-cutting concerns like logging, security enforcement, routing, or content auditing can be handled by dedicated nodes without the endpoint or the business payload knowing about them. Header blocks are *targeted* at a node using the **`role`** attribute (SOAP 1.2) or **`actor`** attribute (SOAP 1.1), whose value is a URI naming the intended processor.

There are well-known role URIs: `next` (1.2: `http://www.w3.org/2003/05/soap-envelope/role/next`) means "the next node in the chain, whoever that is," so every intermediary inspects it; `ultimateReceiver` (the default, the final endpoint); and `none`, meaning no node should process the block (it's carried but only used as reference data). A targeted block marked `mustUnderstand="true"` *must* be processed by its target node or that node raises a `MustUnderstand` fault — the enforcement mechanism that lets, say, a security gateway *require* a credentials header.

```
Sender ──▶ [Logging intermediary]  ──▶ [Security gateway] ──▶ Ultimate Receiver
            role="next": reads &        role="...secgw":       role=ultimateReceiver:
            removes its log header       validates+strips        processes Body
                                         WS-Security (mU=1)
```

In practice I'd use an intermediary for a **central security/audit gateway**: incoming partner messages hit the gateway node, which processes the WS-Security header (targeted at the gateway's role, `mustUnderstand=1`), records an immutable audit entry, strips the now-consumed security header, and forwards a clean message to the internal backend that only deals with the Body. The value is separation of concerns — the backend services don't each reimplement WS-Security; one hardened intermediary does it. The design caution is that an intermediary that *removes* a header block must be trusted, since downstream nodes can no longer verify what it consumed — which is exactly why the gateway sits inside the trust boundary and emits the audit log (ties to Q22's gateway and Q42's governance).

#### Q53. [Behavioral] Describe how you'd lead a team through a high-stakes SOAP-to-event-driven migration for a core banking flow.

The behavioral signal here is whether you can balance technical risk, regulatory constraints, and team/stakeholder dynamics on a system where mistakes move real money. I'd frame it with a STAR-style narrative emphasizing *risk-managed incrementalism over heroics*. **Situation/Task:** "Our settlement flow ran on a SOAP service with WS-Security non-repudiation and WS-RM exactly-once delivery that partners and auditors depended on; leadership wanted to move to an event-driven (Kafka) architecture for scalability and decoupling, but a wrong cutover could double-settle payments or break a regulatory guarantee."

**Action:** I'd establish three guardrails before any code. First, **identify the non-negotiables** by bringing compliance and the partners' technical contacts into the room early — which guarantees are contractual (non-repudiation, exactly-once) and therefore must be preserved in the new design (e.g., signed events + an idempotency/outbox layer + a dedup store, mirroring Q36). Second, mandate a **strangler-fig rollout** (Q39): keep the SOAP flow authoritative, run the event-driven path in **shadow mode** reconciling every transaction against the SOAP result, and only shift traffic per-partner once reconciliation matches for a sustained window with zero discrepancies. Third, define **explicit rollback criteria and a kill switch** so any operator can revert a partner to SOAP instantly, and make that a rehearsed runbook, not a hope.

On the team side, I'd split work so no single engineer owns both the legacy SOAP teardown and the new path (reduces tunnel vision), pair the WS-Security/crypto knowledge with the Kafka/event expertise deliberately, and run **blameless pre-mortems** ("how could this double-settle?") to surface failure modes before launch. **Result/Reflection:** the outcome I'd aim for and report is "migrated the high-volume, low-risk operations first, kept the regulated exactly-once path on a verified event design only after months of clean shadow reconciliation, and retired the SOAP endpoint for a flow only when the last partner was cut over and reconciled — zero settlement incidents, with leadership adopting shadow-reconcile-then-cutover as the standard for money-movement migrations." The interview signal: I optimize for **provable correctness and reversibility** on financial flows, I treat compliance and partners as design inputs rather than obstacles, and I lead through process and verification rather than betting the bank on a clever cutover.

## 🧩 Extended Questions — Supplemental Set: Mixed Depth

### 🟢 Basic — extended

#### Q54. [Theory] What transports can SOAP run over besides HTTP, and why does transport independence matter?

The thing that surprises people who only know REST is that SOAP is **transport-agnostic by design**: the Envelope is a self-contained XML document, and HTTP is just the most common *binding* for moving it. The same message can travel over **JMS** (Java Message Service / a message broker), **SMTP/email**, **FTP**, or raw **TCP**, because nothing in the Envelope assumes request/response over a socket. The WSDL `binding` element is precisely where this is pinned down — `soap:binding transport="..."` names the transport, and HTTP uses `http://schemas.xmlsoap.org/soap/http` while a JMS binding uses a different URI.

This matters because it unlocks delivery and decoupling semantics that HTTP can't give you natively. SOAP-over-JMS is heavily used in banking middleware: the message lands on a durable queue, so the producer and consumer don't have to be up at the same time, the broker provides persistence and guaranteed delivery, and you get load-leveling for free. WS-Addressing (Q9) complements this by carrying the reply destination *in the header* rather than relying on a live HTTP connection, so an async reply can come back on a different queue.

```
HTTP binding:  client ──POST envelope──▶ server          (synchronous, connection-bound)
JMS binding:   client ──put──▶ [durable queue] ──▶ consumer   (async, persistent, decoupled)
SMTP binding:  client ──email envelope──▶ mailbox ──▶ poller  (store-and-forward, very async)
```

The trade-off is tooling and interop: HTTP is universal and every stack supports it, whereas JMS bindings are vendor-flavored (the exact JMS URI scheme and property mapping differs between WebLogic, ActiveMQ, IBM MQ), so SOAP-over-JMS is usually an *internal* enterprise pattern rather than something you expose to external partners. The senior point is that "SOAP = HTTP" is a simplification; the protocol's separation of the Envelope from the binding is exactly what lets a bank put guaranteed, asynchronous messaging under a SOAP contract.

#### Q55. [Practical] How do you consume a SOAP service from a non-Java stack like Python or Node.js?

SOAP is language-neutral, so a `?wsdl` URL is consumable from any stack — the key is using a library that reads the WSDL and builds the envelope for you rather than hand-stitching XML. In **Python** the de-facto modern client is **`zeep`**: you point it at the WSDL and it introspects the operations and types, letting you call them like Python methods.

```python
from zeep import Client
client = Client("https://host/ws/quotes?wsdl")
# zeep parsed the WSDL; call the operation by name with typed kwargs
price = client.service.getQuote(symbol="AAPL")
print(price)
# Inspect available operations/types when the WSDL is unfamiliar:
#   python -m zeep https://host/ws/quotes?wsdl
```

In **Node.js** the common library is **`soap`** (the `strong-soap` fork is also used). It loads the WSDL asynchronously and exposes the operations on a client object; SOAP calls are callback/promise-based.

```javascript
const soap = require("soap");
const url = "https://host/ws/quotes?wsdl";
soap.createClientAsync(url).then(async (client) => {
  const [result] = await client.getQuoteAsync({ symbol: "AAPL" });
  console.log(result.return); // shape mirrors the WSDL response message
});
```

The practical caveats are the same across stacks and are where time gets lost: (1) **WS-Security** support varies — `zeep` has plugins for `UsernameToken`/signing and `soap` supports security objects, but X.509 signing/encryption is fiddlier than in Java/CXF, so for heavy WSS I sometimes still front the partner with a Java/CXF gateway; (2) **MTOM and attachments** are less mature outside Java; (3) **type coercion** differs — dates, decimals, and nil-handling can serialize subtly differently, so I always capture the raw envelope and diff it against a known-good one (the Q33 technique). The reassuring fact for interviews: because the WSDL is the contract, any conformant client can interoperate — the friction is in the WS-* extensions, not the base SOAP exchange.

#### Q56. [Theory] What is the difference between a SOAP `port`, `binding`, `service`, and `endpoint`?

These four WSDL/runtime terms get conflated constantly, and being precise about them is what lets you read a WSDL fluently. A **`portType`** (WSDL 1.1; renamed `interface` in WSDL 2.0) is the *abstract* set of operations — the methods, with their input/output/fault messages, with no mention of wire format or location. A **`binding`** maps that abstract portType onto a *concrete* protocol and encoding — e.g., "these operations, expressed as document/literal SOAP 1.2 over HTTP." So one portType can have several bindings (a SOAP 1.1 binding and a SOAP 1.2 binding for the same operations).

A **`port`** (confusingly named, and called `endpoint` in WSDL 2.0) ties a binding to an actual **network address** — it's a `<wsdl:port>` containing `<soap:address location="https://host/path"/>`. The **`service`** is simply a named container grouping one or more ports. So the hierarchy is: a *service* exposes one or more *ports*, each *port* is a *binding* deployed at a specific URL, and each *binding* realizes an abstract *portType* over a specific protocol.

```
<wsdl:service name="QuoteService">          ← named grouping
  <wsdl:port name="QuoteSoap12"             ← binding + address  (WSDL 2.0: "endpoint")
             binding="tns:QuoteBinding">     ← protocol/encoding for a portType
    <soap:address location="https://host/ws/quotes"/>   ← actual URL
  </wsdl:port>
</wsdl:service>

abstract  ── portType/interface (operations) ──▶ concrete ── binding (SOAP/HTTP, doc/literal)
                                                           └─ port/endpoint (URL)  ──▶ service (group)
```

The "why this matters" is twofold. First, when generating a client you call `service.getQuoteSoap12()` — the method name comes from the *port* name, which is why mismatched expectations there cause "method not found" confusion. Second, the abstract/concrete split is what enables transport independence (Q54): the same `portType` gets a SOAP-over-HTTP binding *and* a SOAP-over-JMS binding, and the `service` can list both ports so a client picks the one it can reach.

### 🟡 Intermediate — extended

#### Q57. [Practical] How do SOAP and .NET/WCF interoperate with a Java service, and what are the common gotchas?

Java-to-.NET SOAP interop is one of the most common real-world integration tasks, and the good news is it *works* — both honor the WSDL contract and the WS-I Basic Profile exists precisely to guarantee it. On the .NET side you generate a client with **`svcutil.exe`** or "Add Service Reference" against the Java service's `?wsdl`; the reverse (Java consuming a WCF service) uses `wsimport`/`wsdl2java` against the WCF-exposed metadata. When both stick to **document/literal wrapped** (Q14) and WS-I BP, the base exchange is clean.

The gotchas cluster around type and serialization differences. The classic one is **`DateTime`/timezone handling**: .NET's `DateTime` serialization and Java's `XMLGregorianCalendar`/`Instant` mapping disagree on timezone offset and precision, so a date that round-trips inside .NET can shift or lose its zone crossing to Java — always use `xsd:dateTime` with explicit offsets and test round-trips. Second, **null vs empty vs nil**: .NET often emits `xsi:nil="true"` where Java expects element absence, and `nillable` vs `minOccurs="0"` (Q46) must agree on both sides. Third, **WCF's default bindings**: `basicHttpBinding` is SOAP 1.1 / WS-I BP and interoperates easily, but `wsHttpBinding` defaults to SOAP 1.2 *with* WS-Security and WS-Addressing turned on, which a plain JAX-WS client won't satisfy — a frequent "it won't connect" cause.

```
WCF binding        SOAP ver   WS-Security default   Java interop
basicHttpBinding   1.1        none                  easy (WS-I BP)
wsHttpBinding      1.2        message security ON    needs matching WSS config on Java side
customBinding      varies     explicit               match element-by-element
```

The fourth recurring issue is **`MustUnderstand` mismatches**: WCF may add headers (addressing, security) marked `mustUnderstand="1"` that the Java endpoint doesn't process, triggering a `MustUnderstand` fault — you align this by configuring matching WS-Addressing/WS-Security or relaxing the requirement. My standard approach for any cross-stack integration is contract-first from a shared, WS-I-validated WSDL, then capture and diff the real envelopes from both clients (Q33) because the spec equivalences (prefixes, nil) are exactly where the two runtimes diverge in practice.

#### Q58. [Coding] Build a low-level SOAP request manually with the SAAJ API (no generated stubs).

**Problem:** Sometimes the WSDL won't generate (legacy `rpc/encoded`, Q15) or you need byte-level control. Build and send a SOAP 1.2 request using SAAJ (`jakarta.xml.soap`) directly.

```java
import jakarta.xml.soap.*;
import javax.xml.namespace.QName;

public class SaajClient {
    public static void main(String[] args) throws Exception {
        // 1. Build the SOAP 1.2 message
        MessageFactory mf = MessageFactory.newInstance(SOAPConstants.SOAP_1_2_PROTOCOL);
        SOAPMessage request = mf.createMessage();
        SOAPEnvelope env = request.getSOAPPart().getEnvelope();
        env.addNamespaceDeclaration("q", "http://trade.example.com/quotes");

        // 2. Build the body: <q:getQuote><q:symbol>AAPL</q:symbol></q:getQuote>
        SOAPBody body = env.getBody();
        SOAPBodyElement op = body.addBodyElement(new QName("http://trade.example.com/quotes", "getQuote", "q"));
        op.addChildElement("symbol", "q").addTextNode("AAPL");

        // 3. (SOAP 1.2) set the action on the content type via MIME headers
        request.getMimeHeaders().addHeader("Content-Type",
            "application/soap+xml; charset=utf-8; action=\"http://trade.example.com/quotes/getQuote\"");
        request.saveChanges();

        // 4. Send synchronously
        try (SOAPConnection conn = SOAPConnectionFactory.newInstance().createConnection()) {
            SOAPMessage response = conn.call(request, "https://host/ws/quotes");

            // 5. Always check for a Fault BEFORE trusting the body (Q32)
            SOAPBody respBody = response.getSOAPBody();
            if (respBody.hasFault()) {
                SOAPFault f = respBody.getFault();
                System.err.println("Fault: " + f.getFaultCode() + " / " + f.getFaultString());
            } else {
                response.writeTo(System.out);
            }
        }
    }
}
```

This is the escape hatch when codegen fails: you control every namespace, prefix, and header, which is exactly what legacy prefix-sensitive servers (Q26) need. **Edge cases:** for SOAP 1.1 you'd use `SOAP_1_1_PROTOCOL` and set a `SOAPAction` MIME header instead of the `action=` content-type parameter (Q44); a server returning a non-SOAP HTML error page makes `getSOAPBody()` throw, so wrap parsing defensively and check the content type first. **Time/Space:** SAAJ is DOM-based, so it's `O(n)` in message size for both — fine for control/diagnostics but not for huge payloads, where you'd stream with StAX instead. The trade-off versus generated stubs is total control at the cost of zero compile-time type safety, so I use SAAJ for diagnostics and intractable legacy services, not for everyday calls.

#### Q59. [Theory] Explain XSD `complexType` extension vs restriction and `xsi:type` polymorphism in SOAP messages.

XSD supports a form of inheritance via `<complexContent>` with either `extension` or `restriction`, and it's the mechanism behind polymorphic SOAP payloads. **Extension** is the common case: a derived type *adds* elements/attributes to a base type (like subclassing) — a `PremiumCustomer` extends `Customer` with a `creditLimit`. **Restriction** *narrows* a base type by tightening constraints (e.g., making an optional element required, or shrinking a numeric range) while remaining substitutable for the base; it's far rarer and trickier because the restricted type must still validate as the base.

The runtime payoff is **polymorphism via `xsi:type`**: when an element is declared as the base type but you want to send a derived instance, the message carries an `xsi:type` attribute naming the actual type, and a schema-aware parser (and JAXB, with `@XmlSeeAlso`) deserializes it to the right subclass. This lets one operation accept or return a family of related types.

```xml
<!-- Schema: base Customer, derived PremiumCustomer adds creditLimit -->
<xsd:complexType name="Customer">
  <xsd:sequence><xsd:element name="name" type="xsd:string"/></xsd:sequence>
</xsd:complexType>
<xsd:complexType name="PremiumCustomer">
  <xsd:complexContent>
    <xsd:extension base="tns:Customer">
      <xsd:sequence><xsd:element name="creditLimit" type="xsd:decimal"/></xsd:sequence>
    </xsd:extension>
  </xsd:complexContent>
</xsd:complexType>

<!-- On the wire: element typed as Customer, but xsi:type promotes it to PremiumCustomer -->
<customer xsi:type="tns:PremiumCustomer">
  <name>Acme Corp</name>
  <creditLimit>50000</creditLimit>
</customer>
```

There are two senior-level cautions. First, **`xsi:type` polymorphism is an XSW/interop risk and a frequent .NET↔Java mismatch** — some stacks don't emit or honor it consistently, and an attacker controlling `xsi:type` can sometimes coerce unexpected deserialization, so security-sensitive services often forbid it and use explicit per-type operations or a `choice` instead. Second, JAXB needs `@XmlSeeAlso({PremiumCustomer.class})` on the base to know the subtypes exist, or unmarshalling silently drops to the base type. My rule: use extension for genuine type hierarchies you control end-to-end, prefer `<xsd:choice>` or distinct operations when interoperating across vendors, and treat `restriction` as a code smell unless there's a strong modeling reason.

#### Q60. [Practical] How do you enable schema validation on inbound SOAP messages, and what are the trade-offs?

By default many SOAP stacks **do not** fully validate the inbound payload against the XSD — they unmarshal leniently, so a message with a missing element, a wrong type, or extra junk may sail through and blow up later as a confusing `NullPointerException` deep in business logic. Turning on **schema validation** makes the stack reject non-conforming messages early with a clear fault, which is both a correctness and a security control (it shrinks the attack surface by enforcing structure). In CXF you enable it per-endpoint; in Spring-WS via a `PayloadValidatingInterceptor`; in raw JAX-WS via the `@SchemaValidation` annotation.

```java
// CXF endpoint — turn on schema validation
@Endpoint
... // or programmatically:
Map<String,Object> props = new HashMap<>();
props.put("schema-validation-enabled", "true");        // CXF property
endpoint.setProperties(props);

// Spring-WS — validate request (and optionally response) against the XSDs
@Bean
public PayloadValidatingInterceptor validatingInterceptor() {
    var v = new PayloadValidatingInterceptor();
    v.setSchema(new ClassPathResource("schemas/quotes.xsd"));
    v.setValidateRequest(true);
    v.setValidateResponse(false);   // often off in prod for perf; on in test
    return v;
}
```

The trade-offs are real and worth articulating. **Pro:** fail-fast with a precise fault pointing at the offending element, defense against malformed/malicious input, and a contract that's actually enforced rather than aspirational. **Con:** validation costs CPU (it walks the whole message against the schema, roughly `O(n)`), and — the operational gotcha — **strict validation makes the contract brittle against benign producer drift**: if a partner adds an element you don't care about, strict validation rejects the whole message even though you'd have ignored it.

My usual posture: **validate requests strictly in lower environments and at the security gateway**, but in production weigh whether to validate inbound from *trusted* partners more leniently (or validate only structure, not every facet) to avoid rejecting on harmless additions — while *always* keeping the XXE/entity hardening (Q35) on regardless of schema validation, since those are separate parser settings. Response validation I generally leave on only in test, because validating your own output in prod is pure overhead once the code is correct.

#### Q61. [Theory] What is HTTP compression (gzip) for SOAP and when does it help vs hurt?

XML is verbose, and SOAP envelopes are full of repetitive, highly compressible structure (long namespace URIs, repeated element names), so they typically **compress 80–90%** with gzip — often a bigger win than for already-compact JSON. HTTP-level compression is negotiated the standard way: the client sends `Accept-Encoding: gzip`, and if the server supports it, it responds with `Content-Encoding: gzip` and the compressed body; for requests, the client can send `Content-Encoding: gzip` if the server accepts it. In CXF you enable it with the `GZIPInInterceptor`/`GZIPOutInterceptor` (or the `GZIPFeature`); most servlet containers can also gzip responses via a filter.

```
Client:  POST ...   Accept-Encoding: gzip          (willing to receive compressed)
Server:  200 ...    Content-Encoding: gzip          (sent compressed)
Effect:  150 KB XML envelope → ~15–25 KB on the wire   (huge for big SOAP messages)
```

When it **helps**: large XML payloads over a bandwidth-constrained or metered link (WAN, cross-datacenter, partner over the public internet) — compression dramatically cuts transfer time and egress cost, and the CPU to compress is usually far cheaper than the saved network time. When it **hurts or is neutral**: small messages (the gzip header/overhead can make a 200-byte message slightly *larger*, so stacks apply a size threshold), CPU-bound servers under extreme throughput where compression cycles compete with request processing, and — the important one — content that's **already compressed or binary**, like MTOM attachments, where re-gzipping wastes CPU for ~0% gain.

Two cautions complete the senior answer. First, compression interacts with **WS-Security and chunking** (Q30): you sign/encrypt *before* the message is gzipped at the HTTP layer (compression is transport-level and transparent to the XML signature), so ordering is fine — but combining gzip *content-encoding* with chunked *transfer-encoding* trips up some legacy proxies, so test through the actual network path. Second, beware the historical **BREACH/CRIME** class of attacks where compressing secret-bearing responses leaks length information; it's mostly a browser-TLS concern, not typical server-to-server SOAP, but for highly sensitive responses some shops disable compression on principle. Net: enable gzip with a size threshold for large messages over constrained links, skip it for tiny messages and MTOM binaries.

### 🟠 Advanced — extended

#### Q62. [Theory] How does WS-Trust and a Security Token Service (STS) broker trust across many parties?

WS-Trust solves the N×M trust problem that direct credential exchange creates: if every service must trust every client directly, then `N` clients and `M` services require `N×M` trust relationships, each with its own certificate exchange and rotation — unmanageable at scale (Q34 hinted at this). WS-Trust introduces a **Security Token Service (STS)** as a trusted broker. Instead of presenting credentials to each service, a client first authenticates to the STS and requests a token (typically a **SAML assertion**) via a standardized `RequestSecurityToken` (RST) exchange; the STS validates the client, mints a signed token containing the client's identity and claims, and returns it in a `RequestSecurityTokenResponse` (RSTR).

The client then attaches that STS-issued token to its SOAP request to the *target* service. The target service doesn't trust the client directly — it trusts the **STS's signing key**, so it verifies the token's signature and accepts the claims inside. This collapses the trust topology from `N×M` to `N+M`: every party trusts one STS.

```
Direct trust:   each client ⇄ each service  →  N×M cert relationships (does not scale)

Brokered (WS-Trust):
  Client ──RST(authenticate)──▶ STS ──RSTR(signed SAML token)──▶ Client
  Client ──SOAP request + SAML token──▶ Target Service
                                          └─ trusts STS's signature, not the client  →  N+M
```

This is the foundation of **federation** (WS-Federation builds on it for cross-organization SSO): a partner company's STS can be trusted by your STS, so users authenticate in their home domain and access your services without you ever holding their credentials. The trade-offs are the STS becomes a **critical, high-availability dependency** (if it's down, nobody can get tokens — you mitigate with token caching and STS clustering) and a **high-value attack target** (compromising the STS signing key forges any identity, so it lives in an HSM). The expert framing: WS-Trust is genuinely elegant for large regulated estates, and it's the conceptual ancestor of OAuth2/OIDC token issuance — recognizing that lineage helps when you're bridging a legacy WS-Trust/SAML world to a modern OAuth one via a token-exchange gateway.

#### Q63. [Coding] Add and read custom SOAP headers in Spring-WS (server-side endpoint).

**Problem:** A partner sends a `<TenantId>` in a custom SOAP header that must be read for routing/auditing, and your response must echo a `<CorrelationId>` header back. Show a Spring-WS `@Endpoint` doing both without a full WS-Addressing stack.

```java
import org.springframework.ws.server.endpoint.annotation.*;
import org.springframework.ws.soap.SoapHeader;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.soap.SoapMessage;
import javax.xml.namespace.QName;
import javax.xml.transform.dom.DOMResult;
import org.w3c.dom.Element;

@Endpoint
public class QuoteEndpoint {

    private static final String NS = "http://trade.example.com/quotes";

    @PayloadRoot(namespace = NS, localPart = "getQuoteRequest")
    @ResponsePayload
    public GetQuoteResponse handle(@RequestPayload GetQuoteRequest req,
                                   MessageContext ctx,
                                   SoapHeader soapHeader) {

        // 1. READ the inbound custom header <q:TenantId>
        String tenant = null;
        java.util.Iterator<org.springframework.ws.soap.SoapHeaderElement> it =
            soapHeader.examineAllHeaderElements();
        while (it.hasNext()) {
            var he = it.next();
            if (new QName(NS, "TenantId").equals(he.getName())) {
                tenant = he.getText();
            }
        }
        log.info("Request for tenant={}", tenant);

        // 2. WRITE a response header <q:CorrelationId> onto the outgoing message
        SoapMessage responseMsg = (SoapMessage) ctx.getResponse();
        SoapHeader respHeader = responseMsg.getSoapHeader();
        var corr = respHeader.addHeaderElement(new QName(NS, "CorrelationId", "q"));
        corr.setText(java.util.UUID.randomUUID().toString());

        return service.quote(req.getSymbol(), tenant);
    }
}
```

The Spring-WS model splits the **payload** (the Body content, bound by JAXB and routed via `@PayloadRoot`) from the **header** (accessed via the injected `SoapHeader`), which is exactly the separation the protocol intends (Q1) — your business method gets clean JAXB objects while cross-cutting metadata stays in the header. **Edge cases:** the header may be absent entirely (guard for null/empty iteration), there may be multiple elements with the same name (iterate, don't assume one), and namespace must match exactly or the lookup silently misses (Q26). For real correlation/async you'd prefer the WS-Addressing `MessageID`/`RelatesTo` (Q9, Q48) with Spring-WS's addressing support rather than a hand-rolled header, but custom headers are the right tool for app-specific metadata like tenant or partner IDs. **Time/Space:** `O(h)` over the number of header elements, negligible versus the payload; no extra payload-sized allocation.

#### Q64. [Practical] How do you load-test a SOAP service and what metrics actually matter?

Load-testing SOAP differs from REST in ways that bite if you treat it like JSON. The headline difference is that **XML marshalling and (if enabled) WS-Security crypto dominate CPU**, so the server's bottleneck is usually parsing/canonicalization/signature verification, not the business logic — which means your load test must use **realistic message sizes and the real security configuration**, because a tiny unsigned test envelope will give wildly optimistic numbers versus a signed, full-sized production message. I drive load with **Apache JMeter** (which has a SOAP/XML sampler), **Gatling**, or **k6**, feeding a corpus of representative envelopes (varying sizes, including the large/MTOM cases).

```
JMeter plan:  Thread Group (ramp 0→500 over 60s, hold 5 min)
                └─ HTTP Request sampler  POST /ws/quotes
                     Content-Type: application/soap+xml; action="..."
                     Body: parameterized envelope (CSV-driven symbols, sizes)
                └─ Response Assertion: body contains <getQuoteResponse>, NOT <Fault>
                └─ listeners: p50/p95/p99 latency, throughput, error %
```

The metrics that actually matter, in priority order: (1) **p95/p99 latency, not the mean** — XML GC pauses and occasional large messages create a long tail the average hides; (2) **throughput (req/s) at a target latency SLA**, found by ramping until p99 breaches the SLA — that's your real capacity; (3) **error rate broken down by *type*** — and critically you must assert on the **body**, not just HTTP 200, because a SOAP Fault returns 500 (Q32) and a "business denied" returns 200, so naive HTTP-status counting both over- and under-counts errors; (4) **server-side CPU and especially GC behavior / allocation rate** (Q43) — SOAP load tests should run alongside a GC log because the failure mode is GC-pause-driven latency, not CPU saturation; (5) **connection pool / thread saturation** at the client and any downstream partner (bulkheads, Q29).

The senior discipline is to test the *whole realistic path*: schema validation on, WS-Security on, the actual message size distribution, and the downstream partner stubbed with realistic latency — because turning those off (the tempting shortcut) measures a system you'll never run. I also load-test **after** the JAXBContext-caching and pooling fixes (Q28/Q29) are in, then re-run to prove the fix moved p99 and dropped allocation rate, closing the profile→fix→re-measure loop (Q43).

#### Q65. [Theory] How does distributed tracing work across a SOAP boundary, and how do you propagate trace context?

Modern observability relies on **distributed tracing** (OpenTelemetry/W3C Trace Context), where a `traceparent` identifier is propagated across service hops so you can stitch one request's journey into a single trace. SOAP predates this, so there's no built-in slot — but SOAP's header model (Q1) is *exactly* the right place to carry it. You inject the trace context as a SOAP header element (or, more compatibly, as an HTTP header since SOAP runs over HTTP) on the outbound call, and extract it on the inbound side to continue the same trace.

The cleanest approach when SOAP rides on HTTP is to propagate the **W3C `traceparent` HTTP header** via a JAX-WS/CXF interceptor or a Spring-WS `ClientInterceptor`, because OpenTelemetry's HTTP instrumentation already understands it and you avoid touching the XML. When the transport isn't HTTP (SOAP-over-JMS, Q54) or an intermediary strips HTTP headers, you instead inject the context into a **SOAP header block** so it survives transport changes and async hops (this composes with WS-Addressing's correlation, Q9/Q48).

```java
// CXF outbound interceptor: inject the current trace context as an HTTP header
public class TracePropagationInterceptor extends AbstractPhaseInterceptor<Message> {
    public TracePropagationInterceptor() { super(Phase.PRE_PROTOCOL); }
    @Override public void handleMessage(Message msg) {
        @SuppressWarnings("unchecked")
        Map<String,List<String>> headers = (Map<String,List<String>>)
            msg.get(Message.PROTOCOL_HEADERS);
        if (headers == null) { headers = new HashMap<>(); msg.put(Message.PROTOCOL_HEADERS, headers); }
        // OpenTelemetry context → traceparent (e.g. via propagators.inject)
        headers.put("traceparent", List.of(currentTraceParent()));
    }
}
```

The design decisions that matter: (1) **HTTP-header propagation is preferable when possible** (no XML changes, works with off-the-shelf OTel auto-instrumentation), but **SOAP-header propagation is necessary for non-HTTP transports and async MEPs** where the request and reply are separate messages — there you correlate via WS-Addressing `MessageID`/`RelatesTo` *and* carry the trace context so the async reply rejoins the trace; (2) you must instrument the **gateway/façade** (Q22) so a modern REST/gRPC caller's trace continues *through* the SOAP translation into the partner call, otherwise the SOAP hop is a black hole in your traces; (3) the same correlation ID should also flow to the **audit log** (Q22) so traces and audit records line up. The senior point: SOAP doesn't obstruct tracing — its header model was *designed* for cross-cutting metadata — but you must deliberately bridge the SOAP boundary in your instrumentation, especially at the legacy-to-modern façade, or you lose end-to-end visibility precisely where the system is most fragile.

#### Q66. [Practical] How do you deploy and scale a SOAP service in Kubernetes, including health checks and graceful shutdown?

Containerizing a SOAP service is straightforward, but a few SOAP-specific details separate a robust deployment from one that drops in-flight transactions. The service (a Spring Boot 3 / CXF or Spring-WS app) packages as a normal JVM container; you scale it horizontally behind a Service since SOAP-over-HTTP is request/response and (assuming you don't keep server-side session state) **stateless and trivially load-balanced**. The wrinkles are health checks, graceful shutdown for in-flight messages, and any stateful WS-* feature.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata: { name: soap-gateway }
spec:
  replicas: 3
  template:
    spec:
      terminationGracePeriodSeconds: 45        # let in-flight SOAP calls drain
      containers:
        - name: soap-gateway
          image: registry/soap-gateway:1.4.2
          ports: [{ containerPort: 8080 }]
          readinessProbe:                        # don't send traffic until WSDL/JAXB ready
            httpGet: { path: /actuator/health/readiness, port: 8080 }
            initialDelaySeconds: 20              # JAXBContext build + WSDL parse is slow (Q28)
            periodSeconds: 5
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: 8080 }
            periodSeconds: 10
          lifecycle:
            preStop: { exec: { command: ["sh","-c","sleep 10"] } }  # stop intake, finish in-flight
          resources:
            requests: { memory: "1Gi", cpu: "500m" }
            limits:   { memory: "2Gi" }          # XML/DOM is memory-heavy (Q43) — size headroom
```

The SOAP-specific reasoning: (1) **readiness must wait for slow startup** — building `JAXBContext` and parsing the WSDL/XSDs (Q28) takes real time, so a short `initialDelaySeconds` causes Kubernetes to route traffic to a pod that 500s on the first calls; gate readiness on the SOAP stack actually being up. (2) **Graceful shutdown matters more for SOAP payments** — a SIGTERM mid-transaction could abandon a half-processed settle; set a generous `terminationGracePeriodSeconds`, use a `preStop` hook plus Spring's graceful shutdown so the pod stops accepting new requests but finishes in-flight ones, and combine with the idempotency layer (Q36) so any retry after a killed pod is safe. (3) **Memory limits need headroom** because DOM marshalling and large/MTOM messages spike heap (Q43, Q51) — an under-provisioned limit causes OOMKills under large-message load; enforce a max message size at the app (Q51) so a single huge request can't blow the limit.

The features that *don't* scale trivially are the stateful WS-* ones: **WS-ReliableMessaging sequences** and **WS-AtomicTransaction** coordinators hold state, so naive round-robin across replicas breaks them — you either pin a sequence to a pod (sticky sessions / session affinity), externalize the state to a shared store, or (the modern preference) avoid stateful WS-RM in favor of the idempotency-key + dedup-store pattern (Q36) which *is* stateless at the app tier. My default posture: stateless SOAP-over-HTTP scales like any web app; the moment WS-RM/WS-AT state enters, treat it as a stateful workload with affinity or externalized state, and prefer designs that keep the pods stateless.

#### Q67. [Theory] What are WS-Eventing and WS-Notification, and how does SOAP do publish/subscribe?

The base SOAP MEPs (Q48) are point-to-point; **WS-Eventing** and **WS-Notification** (the OASIS family: WS-BaseNotification, WS-BrokeredNotification, WS-Topics) layer a **publish/subscribe** model on top so a producer can push events to many interested subscribers without knowing them in advance. The pattern: a subscriber sends a `Subscribe` request to an **event source** (WS-Eventing) or a **notification producer/broker** (WS-Notification), expressing interest (optionally filtered by topic or XPath); the source returns a subscription reference (an EPR, see Q68) with a lease/expiry; thereafter, when an event occurs, the source delivers a SOAP notification message to each subscriber's `NotifyTo` endpoint, typically as a one-way message.

```
Subscriber ──Subscribe(NotifyTo=cb, Filter=topic:trades, Expires=1h)──▶ Notification Producer
           ◀──SubscribeResponse(SubscriptionRef = EPR)──
   ... event occurs ...
Producer  ──Notify(event)──▶ Subscriber's NotifyTo (one-way)      (repeat per subscriber/broker)
Subscriber ──Renew / Unsubscribe──▶ Producer        (lease management)
```

WS-Eventing and WS-Notification overlap heavily (a long-standing standards-fragmentation annoyance — two committees, similar goals); WS-Notification adds a **broker** intermediary and richer topic structures, decoupling producers from subscribers entirely, while WS-Eventing is leaner and source-centric. Both rely on **WS-Addressing** (Q9) for the `NotifyTo`/`EndTo` endpoint references and on lease-based subscriptions so dead subscribers eventually get garbage-collected when they stop renewing.

The honest 2026 framing matters in interviews: these specs exist and appear in some telecom/utility/SCADA and WS-Management contexts, but pub/sub over SOAP **largely lost** to lighter eventing — Kafka, AMQP/RabbitMQ, MQTT, cloud pub/sub, and webhooks — for the same reasons WS-* generally ceded ground (Q40): operational heft and tooling cost. So the senior answer is: know that SOAP *can* do pub/sub via WS-Eventing/WS-Notification with brokers, leases, and topic filters, understand it's built on WS-Addressing EPRs, but recognize that for a *new* event-driven design you'd reach for a message broker, and you'd only engage WS-Notification when integrating with an existing system (e.g., a device-management or WS-Management deployment) that mandates it.

#### Q68. [Theory] What is an Endpoint Reference (EPR) in WS-Addressing, and why is it more than just a URL?

An **Endpoint Reference (EPR)** is the WS-Addressing construct for identifying a web service endpoint, and the reason it exists rather than "just use the URL" is that a bare URL can't carry the *context* needed for stateful, routed, or instance-specific messaging. An EPR wraps the destination `Address` (the URL) plus optional **`ReferenceParameters`** (opaque tokens the endpoint defined, e.g., a session or subscription ID that must be echoed back into the header of messages sent to that EPR) and **`Metadata`** (e.g., the WSDL/portType the endpoint implements, or policy). The endpoint *mints* the EPR and hands it out; clients treat its reference parameters as opaque and just replay them.

```xml
<wsa:EndpointReference>
  <wsa:Address>https://host/ws/subscriptions</wsa:Address>
  <wsa:ReferenceParameters>
    <sub:SubscriptionId>sub-7f3c-e91</sub:SubscriptionId>   <!-- opaque to client; echoed back -->
  </wsa:ReferenceParameters>
  <wsa:Metadata> ... portType / policy ... </wsa:Metadata>
</wsa:EndpointReference>
```

The "more than a URL" payoff shows up in three places. First, **stateful interactions** (Q67 subscriptions, WS-RM sequences, WS-Trust tokens): the server returns an EPR whose reference parameters encode *which* instance/session/sequence the client is talking to, so a stateless-looking URL can address a specific server-side resource — when the client sends a follow-up, the runtime copies the reference parameters into SOAP headers and the server routes to the right state. Second, **callbacks/async** (Q48): the `ReplyTo`/`FaultTo` in a request *are* EPRs, telling the server exactly where (and with what reference parameters) to send the eventual reply. Third, **dynamic routing**: an intermediary can hand back an EPR pointing at a different concrete node than the one the client first contacted.

The design insight worth stating: an EPR is a **portable, self-describing pointer to a (possibly stateful) endpoint**, which is what enables SOAP's transport-neutral async and brokered patterns — and it's conceptually the ancestor of things like OAuth's resource/issuer metadata and capability URLs. The practical caution is that reference parameters are **security-relevant**: because clients replay them verbatim into headers and servers trust them for routing/state lookup, they must be unguessable and validated server-side, or they become an IDOR-style vulnerability — a subtlety that distinguishes someone who's merely read the WS-Addressing spec from someone who's operated it.

### 🔴 Expert — extended

#### Q69. [Theory] Compare SOAP, gRPC, and GraphQL as RPC/contract technologies for a 2026 greenfield decision.

All three are "call a remote operation against a contract," but they optimize for different worlds, and a staff engineer should be able to place each precisely rather than reflexively reaching for one. **SOAP** is XML over (usually) HTTP with a WSDL contract and the WS-* stack; its differentiators are **message-level security/non-repudiation (WS-Security), transport independence (incl. JMS), and built-in reliability/transactions** — bought at the cost of verbosity, tooling weight, and developer friction (Q40). **gRPC** is Protobuf over HTTP/2 with a `.proto` contract; its differentiators are **compact binary payloads, true bidirectional streaming, code generation across many languages, and excellent performance** — at the cost of weak browser support without a proxy and binary payloads that aren't human-readable. **GraphQL** is a query language over (usually) HTTP/JSON with an SDL schema; its differentiator is **client-driven field selection** that eliminates over/under-fetching for rich, aggregating front-ends — at the cost of server complexity (resolvers, N+1 query risk), hard HTTP caching, and weak native support for streaming/transactions.

```
Dimension          SOAP                  gRPC                    GraphQL
Wire format        XML (verbose)         Protobuf (binary)       JSON (query-shaped)
Contract           WSDL + XSD            .proto                  SDL schema
Transport          HTTP/JMS/SMTP         HTTP/2                  HTTP/1.1+
Streaming          via WS-* (heavy)      native bidirectional    subscriptions (add-on)
Security           WS-Security (msg-lvl) TLS + token (transport) TLS + token (transport)
Human-readable     yes (XML)             no                      yes
Browser-native     yes                   no (needs grpc-web)     yes
Caching            poor                  poor                    poor (vs REST)
Sweet spot         regulated B2B/legacy  internal microservices, web/mobile aggregation,
                                          low-latency, polyglot    rich front-end APIs
```

The decision framework I'd actually apply for greenfield: **default to REST** for public/simple APIs (it still wins on ubiquity and caching); choose **gRPC** for internal service-to-service where latency, throughput, streaming, and polyglot codegen matter (the modern successor to a lot of what SOAP RPC did internally); choose **GraphQL** when diverse front-ends need to shape their own queries over an aggregating graph; and choose **SOAP only when forced by a partner/regulator** needing WS-Security message-level guarantees or when integrating an existing SOAP estate — in which case you wrap it behind a façade (Q22). The senior nuance is that these aren't mutually exclusive: a realistic enterprise runs **gRPC internally, GraphQL or REST at the edge for clients, and SOAP at the certified B2B boundary**, with gateways translating between them — so the skill is matching each protocol to the constraint it was designed for, not declaring a universal winner.

#### Q70. [Practical] A nightly batch of 50,000 SOAP calls to a partner is missing its window. How do you make it fast and reliable?

A serialized loop of 50,000 synchronous SOAP calls is the classic "missing the batch window" cause: even at 200 ms per round trip, 50k sequential calls is ~2.8 hours, and any partner slowness or retries blows the window entirely. The fix is a combination of **concurrency, connection reuse, batching where the contract allows, and bounded resilience** — and the discipline is to measure where the time actually goes before tuning blindly.

First, **stop being synchronous and serial**. Run the calls through a bounded thread pool (or an async/reactive client) with a concurrency level tuned to the partner's tolerance — not unbounded, which would hammer the partner into rate-limiting or collapse. With ~50 concurrent in-flight calls at 200 ms each, throughput jumps from ~5/s to ~250/s, turning hours into minutes. Crucially this **must** use a **pooled keep-alive HTTP transport** (Q29) so you're not doing a TLS handshake 50,000 times — handshake cost often dwarfs the actual call at this volume.

```
Serial:    [call][call][call]...           50,000 × 200ms ≈ 2.8 h   ❌ misses window
Concurrent: ┌call┐┌call┐ ... (×50)          50,000 / (50/0.2s) ≈ 3.3 min ✅
            └call┘└call┘                    + pooled keep-alive (no per-call TLS)
            + bulkhead caps concurrency so the partner isn't overwhelmed
```

Second, **check whether the contract supports batching** — many SOAP operations accept a `maxOccurs="unbounded"` collection, so instead of 50,000 calls you send, say, 500 calls of 100 items each, slashing per-message overhead (envelope, WS-Security, round trips) by 100×. If the partner offers a bulk operation or an **async MEP** (Q48 — submit a batch, get a callback), that's even better for a nightly job. Third, **make it resilient and resumable**: wrap calls in a circuit breaker (Q29) so a partner outage fails fast instead of hanging, use idempotency keys (Q36) so safe retries don't duplicate, and **checkpoint progress** so a crash at item 40,000 resumes rather than restarting — for a payment batch, resumability plus idempotency is non-negotiable.

The senior framing: I'd first profile one call to see if the cost is network round-trip (→ concurrency + pooling + batching), WS-Security crypto (→ are we rebuilding `Crypto`/`JAXBContext` per call? Q28), or partner-side latency (→ negotiate a bulk/async operation, because no amount of client tuning fixes a slow partner per-item). Then I'd run the batch off-peak with a concurrency cap agreed with the partner, monitored with per-item success/fault metrics (distinguishing transport vs Sender vs Receiver faults, Q32) so a spike of failures pages someone rather than silently corrupting the nightly reconciliation.

#### Q71. [Theory] How do you defend against XML Signature Wrapping (XSW) attacks in depth, beyond "use a good library"?

XSW (Q21 introduced it) is the most dangerous WS-Security-specific attack, and the reason it's so insidious is that the signature **still cryptographically verifies** — the attacker doesn't break the crypto, they exploit the gap between *what was signed* and *what gets processed*. The classic attack: a legitimately signed `<Body Id="1">` is moved into a bogus wrapper element (often a header or a fake `<BogusHeader>`), and a new malicious `<Body>` (without the signed Id) is inserted where the application logic reads it. The signature verifier finds the original signed element by its `Id`, validates it (it's unchanged), and reports "signature valid" — while the business logic processes the *attacker's* unsigned body. Verification and processing looked at **different elements**.

```
Original:                          XSW-attacked:
<Envelope>                         <Envelope>
  <Header><Signature refs Id=1/>    <Header>
  <Body Id="1">transfer $5</Body>      <Signature refs Id=1/>            ← still verifies...
</Envelope>                            <Wrapper><Body Id="1">$5</Body></Wrapper>  ← signed, but moved/ignored
                                     </Header>
                                     <Body>transfer $5,000,000</Body>   ← UNSIGNED, but PROCESSED ❌
                                   </Envelope>
```

Defense in depth means closing the *gap*, not just trusting the library: (1) **Process only what was actually signed** — resolve the signed references and have the application read the verified elements directly (a "see-what-is-signed" / `WSSecurityEngineResult` element-binding approach in WSS4J), rather than letting business code independently re-query the document by tag name; the moment verification and processing use *different* lookups, XSW is possible. (2) **Strict schema validation** (Q60) — a hardened schema that permits a `<Body>` in exactly one place and forbids unexpected wrapper elements removes the room to hide the moved element; XSW thrives on schema laxity (`xsd:any`, optional/extensible content). (3) **Use ID-based references with caution** — prefer referencing by element position/structure where the toolkit supports it, and reject documents with **duplicate IDs** (a common XSW enabler). (4) **Keep the WS-Security stack patched** — WSS4J/CXF have shipped multiple XSW-hardening fixes, and old versions are exploitable even with correct config.

The expert-level point is that XSW is fundamentally a **"trust the verified data, not the document" discipline** problem: the only robust posture is that the application acts on the *output of signature verification* (the specific signed elements), with strict schema constraints that leave no room for an attacker to relocate or duplicate content. I'd also red-team it — run known XSW payload variants (there are well-catalogued ones) against staging as a standing test (Q35's "scan with a known payload" applied to XSW), because a library upgrade or a schema relaxation can silently reopen the hole, and no amount of code review reliably catches it the way an automated exploit attempt does.

#### Q72. [Practical] Design a contract-testing and CI strategy so SOAP providers never silently break consumers.

Silent contract breakage (Q49) is the chronic failure mode of large SOAP estates, and the fix is to make the contract's compatibility **machine-verified in CI**, not trusted to human review. I'd build three layers of automated gating, escalating from cheap-and-syntactic to thorough-and-semantic.

**Layer 1 — schema backward-compatibility gate (provider CI).** On every change to a WSDL/XSD, a CI step diffs the new schema against the **last published version** (pulled from the registry/artifact repo) and fails the build on any non-additive change: a new `minOccurs="1"` element, an element reorder within a `<sequence>`, a type narrowing/rename, a removed element, or a tightened facet (Q46). This catches the "I only added a field" class (Q49) deterministically. The rule is encoded once and applied estate-wide (Q42 governance), so no provider can ship a breaking change to an existing namespace — breaking changes are *forced* into a new `v2` namespace (Q18).

**Layer 2 — consumer-driven contract tests.** Each consumer publishes an **example request + the response shape it relies on** (the subset of fields it actually reads) into a shared contract store. The provider's CI replays *every registered consumer's* expectation against the new build: it sends the consumer's example request and asserts the response still contains the fields that consumer depends on. This is the SOAP analogue of Pact for REST — it means a provider learns *before merge* that a change breaks consumer X, even if the change is technically schema-additive but semantically breaking (e.g., a field's meaning changed).

```
Provider CI pipeline:
  1. validate WSDL/XSD against WS-I BP + internal profile        (conformance, Q42)
  2. schema-compat diff vs last published version → FAIL on non-additive change  (Q46/Q49)
  3. replay each registered consumer's contract test → FAIL if any breaks         (CDC)
  4. spin provider in test container, run integration suite against real envelopes
  5. on green: publish WSDL/XSD to registry, tag version, notify consumers
Consumer CI:
  - run against a provider stub generated from the *published* WSDL (not a hand-mock)
  - on provider version bump: pipeline re-runs to confirm still-compatible
```

**Layer 3 — environment & runtime guards.** Consumers test against a **stub generated from the published WSDL** (so the mock can't drift from the real contract — a hand-written mock that lies is worse than no mock), and a **staging contract-verification job** periodically calls the real provider with the registered consumer requests to catch config/deployment drift the unit-level CDC tests miss.

The senior framing is that this strategy makes the **contract the enforced source of truth**: provider changes are gated by both *syntactic* compatibility (schema diff) and *semantic* compatibility (consumer-driven tests), breaking changes are channelled into versioned namespaces rather than mutations, and the registry ties versions to owning teams and consumers so you know exactly who to coordinate with before deprecating. The cultural change that makes it stick is treating a red contract-compat gate like a failing unit test — a hard stop, not a warning — because the alternative is a 2 a.m. partner outage and a frantic rollback, which is precisely what the gate exists to prevent.

#### Q73. [Coding] Implement a resilient CXF SOAP client with timeout, circuit breaker, and retry-with-backoff.

**Problem:** Wrap a generated CXF SOAP port with production resilience — connect/read timeouts, a resilience4j circuit breaker, and exponential-backoff retry that only retries safe failures (Q32/Q36).

```java
import org.apache.cxf.frontend.ClientProxy;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy;
import io.github.resilience4j.circuitbreaker.*;
import io.github.resilience4j.retry.*;
import java.time.Duration;

public class ResilientQuoteClient {

    private final QuoteService port;          // generated CXF port
    private final CircuitBreaker breaker;
    private final Retry retry;

    public ResilientQuoteClient(QuoteService port) {
        this.port = port;

        // 1. Timeouts on the CXF HTTP conduit — without these a slow partner hangs threads (Q29)
        HTTPConduit conduit = (HTTPConduit) ClientProxy.getClient(port).getConduit();
        HTTPClientPolicy policy = new HTTPClientPolicy();
        policy.setConnectionTimeout(3000);     // ms to establish TCP
        policy.setReceiveTimeout(10000);       // ms to read response
        policy.setAllowChunking(false);        // off for WS-Security partners (Q30)
        conduit.setClient(policy);

        // 2. Circuit breaker — trip open after sustained failure so we fail fast, not slow
        this.breaker = CircuitBreaker.of("partner-quotes", CircuitBreakerConfig.custom()
                .failureRateThreshold(50)                       // open at 50% failures
                .slidingWindowSize(20)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .recordException(ResilientQuoteClient::isRetryable)  // faults that count as failure
                .build());

        // 3. Retry with exponential backoff + jitter, ONLY for retryable failures
        this.retry = Retry.of("partner-quotes", RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        Duration.ofMillis(200), 2.0))            // 200ms, ~400ms, ~800ms (+jitter)
                .retryOnException(ResilientQuoteClient::isRetryable)
                .build());
    }

    public double getQuote(String symbol) {
        // Compose: retry wraps the breaker-protected call
        var decorated = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(breaker, () -> port.getQuote(symbol)));
        return decorated.get();
    }

    // Retry/trip ONLY on transport errors and server-side (Receiver) faults — never on Sender faults (Q32/Q36)
    private static boolean isRetryable(Throwable t) {
        if (t instanceof jakarta.xml.ws.WebServiceException) return true;       // transport/connection
        if (t instanceof jakarta.xml.ws.soap.SOAPFaultException sf) {
            String code = String.valueOf(sf.getFault().getFaultCodeAsQName());
            return code.contains("Receiver") || code.contains("Server");        // server-side → retry
            // "Sender"/"Client" faults are the caller's fault → do NOT retry
        }
        return false;
    }
}
```

The load-bearing design choices: **retry wraps the breaker** (not the other way around) so a tripped breaker fails fast without consuming retry attempts; the **`isRetryable` predicate is the crux** — retrying a `Sender`/`Client` fault just re-sends a bad request and wastes the partner's capacity, while transport errors and `Receiver` faults are plausibly transient (Q32); and timeouts are mandatory because a circuit breaker *without* a read timeout never trips (the call never returns to be counted — Q29). **Edge cases:** for non-idempotent operations (a payment) this retry is only safe combined with an idempotency key (Q36), or you risk double-execution on a retry after an ambiguous timeout; chunking is disabled for WS-Security partners (Q30). **Time/Space:** `O(1)` overhead per call beyond the SOAP work itself; the breaker's sliding window is fixed-size. I'd also add a **bulkhead** (bounded concurrency per partner) so one slow partner can't exhaust the shared thread pool (Q29/Q66), completing the resilience picture.

#### Q73 covered the resilient-client coding; the remaining expert questions deepen niche internals and the senior/staff judgment around them.

#### Q74. [Theory] What is Fast Infoset and MTOM/XOP at the wire level — how do they reduce SOAP overhead differently?

These two optimizations attack different costs and are sometimes confused. **MTOM/XOP** (Q10, Q51) targets **binary payload bloat**: it pulls a `base64Binary` element's bytes out of the XML, sends them raw in a separate MIME part, and leaves an `<xop:Include href="cid:...">` placeholder — eliminating the ~33% Base64 inflation and keeping the bytes out of the XML parser. It does *nothing* for the verbosity of the XML structure itself (the element names, namespaces, attributes); it only helps when there's a meaningful chunk of binary data.

**Fast Infoset** (FI) attacks a *different* cost: the **verbosity of the XML text encoding**. FI is a binary serialization of the XML *Infoset* (the abstract structure) — it tokenizes repeated element/attribute names and namespace URIs into a string table referenced by index, and encodes the tree in a compact binary form rather than angle-bracket text. So the same SOAP message that's, say, 40 KB as text might be 10–15 KB as Fast Infoset, parsing faster too because there's no text lexing. It's negotiated via HTTP content negotiation (`Accept: application/fastinfoset`) and supported by CXF and Metro.

```
Plain text XML:   <q:getQuote xmlns:q="...long-uri..."><q:symbol>AAPL</q:symbol>...   (verbose, re-stated names)
MTOM/XOP:         same XML, but <photo><xop:Include href="cid:1"/></photo> + raw bytes in MIME part
                  → fixes BINARY bloat, not structural verbosity
Fast Infoset:     binary-encoded Infoset; "getQuote","symbol",namespace URI → table indices
                  → fixes STRUCTURAL verbosity + parse cost, not binary specifically
```

When to use which: **MTOM** whenever you carry non-trivial binary (documents, images) — it's a clear win and broadly interoperable. **Fast Infoset** when you have high-volume, structurally-verbose messages between systems you *control both ends of* (it's not universally supported, so it's an internal optimization, like SOAP-over-JMS) and where parse CPU / bandwidth is a measured bottleneck. They **compose**: you can use Fast Infoset for the structure *and* MTOM for binary parts. The senior caveats: FI breaks human-readability and tooling (you can't `curl | less` it), and it overlaps with **gzip** (Q61) — gzip on text XML often gets you most of the size win with zero interop cost and universal support, so I reach for **gzip first** and only consider Fast Infoset when profiling shows XML *parsing* (not just transfer size) is the bottleneck and both endpoints support it. The decision is: gzip for bandwidth on the open path, MTOM for binary, Fast Infoset only for measured internal parse-bound hot paths.

#### Q75. [Practical] How do you configure mutual TLS (mTLS) for a SOAP partner connection, and how does it relate to WS-Security?

For a regulated partner, **mutual TLS** authenticates *both* sides at the transport layer: not only does the client verify the server's cert (ordinary TLS), the server also requires and verifies the **client's** certificate, so only holders of a trusted client cert can even open a connection. This is distinct from — and often combined with — WS-Security message-level signing, and confusing the two is a common error. In a JAX-WS/CXF client you configure mTLS on the HTTP conduit's TLS parameters with a **keystore** (your client identity/private key) and a **truststore** (the CA/cert you trust for the server).

```xml
<!-- CXF http-conduit TLS config: keystore = our client identity, truststore = whom we trust -->
<http:conduit name="https://partner.bank.com/.*">
  <http:tlsClientParameters secureSocketProtocol="TLSv1.3">
    <sec:keyManagers keyPassword="${ks.pass}">
      <sec:keyStore type="PKCS12" password="${ks.pass}" resource="client-identity.p12"/>
    </sec:keyManagers>
    <sec:trustManagers>
      <sec:keyStore type="JKS" password="${ts.pass}" resource="partner-truststore.jks"/>
    </sec:trustManagers>
    <!-- pin/limit to specific cipher suites the partner mandates -->
    <sec:cipherSuitesFilter><sec:include>.*GCM.*</sec:include></sec:cipherSuitesFilter>
  </http:tlsClientParameters>
</http:conduit>
```

The relationship to WS-Security is the key conceptual point, and it maps to Q8's hop-by-hop vs end-to-end distinction. **mTLS authenticates and encrypts the connection (hop-by-hop)** — it proves the *connection peer's* identity and protects bytes on that one TCP hop, but the protection ends at TLS termination (a load balancer, gateway, or proxy decrypts it), and it says nothing about the message once it's off the wire. **WS-Security authenticates and protects the message (end-to-end)** — a signed/encrypted body survives intermediaries, TLS termination, and queue hops, and provides per-message **non-repudiation** that mTLS can't (mTLS proves "this connection came from a cert holder," not "this specific party authored this exact message").

So regulated partners frequently mandate **both**: mTLS for a strongly-authenticated, encrypted channel *and* WS-Security signing for per-message non-repudiation and protection past the TLS endpoint. The operational realities I'd flag: **certificate lifecycle is the dominant headache** — client and server certs both expire, partners rotate CAs, and an un-renewed cert is the single most common cause of a sudden "it worked yesterday" outage, so I automate expiry monitoring and rotate ahead of time; store keys in an HSM/PKCS#11 where required (Q22); and pin TLS versions/cipher suites to what the partner's compliance mandates. When someone proposes "we have mTLS, do we still need WS-Security?", the answer depends entirely on whether intermediaries terminate TLS and whether non-repudiation is contractually required — if either is true, mTLS alone is insufficient.

#### Q76. [Theory] What is the difference between WSDL 1.1 and WSDL 2.0, and why did 1.1 win in practice?

WSDL 1.1 (2001, a W3C Note like SOAP 1.1) is what virtually every SOAP service in the wild uses; WSDL 2.0 (2007, a full W3C Recommendation) was a cleaner redesign that **failed to displace it** — and the *why* is a recurring lesson in standards adoption. The structural differences: WSDL 2.0 renamed `portType` to **`interface`** and `port` to **`endpoint`** (Q56), added **interface inheritance** (`extends`), removed the `message` construct (operations reference XSD elements directly, simplifying the indirection), formalized a richer set of **message exchange patterns** (in-out, in-only, robust-in-only, out-in, etc. — Q48), and improved HTTP binding support so you could describe RESTful-ish HTTP services, not just SOAP.

```
Concept              WSDL 1.1               WSDL 2.0
abstract operations  portType               interface (supports extends/inheritance)
concrete endpoint    port                   endpoint
message indirection  <message> elements     none — operations reference XSD elements directly
MEPs                 implicit (in/out)       explicit, richer set (in-only, robust-in-only, ...)
status               W3C Note               W3C Recommendation
real-world usage     dominant                rare
```

Despite being technically cleaner, WSDL 2.0 lost for classic adoption-economics reasons (echoing Q40). By 2007 the **tooling ecosystem was overwhelmingly built around WSDL 1.1** — `wsimport`, `wsgen`, .NET's `svcutil`, Axis, every IDE — and vendors had little incentive to add WSDL 2.0 codegen when 1.1 worked and customers weren't asking. The **WS-I Basic Profile** standardized on WSDL 1.1, cementing it as the interop baseline. And critically, by the time WSDL 2.0 arrived, the **industry's energy was already shifting to REST/JSON**, so the appetite to re-tool the entire SOAP world for a marginally better description language was near zero — WSDL 2.0 was a better answer to a question fewer people were still asking.

The practical takeaway for 2026: you will essentially **always** encounter WSDL 1.1; treat WSDL 2.0 as a piece of standards trivia and a reminder that the better spec doesn't win — the one with the entrenched tooling and the WS-I blessing does. The deeper engineering lesson, which is what an interviewer is really probing, is that **backward-compatible, tooling-aligned incrementalism beats clean-redesign migrations** for established standards — the same reason mutating-vs-versioning matters for your own schemas (Q18) and the same dynamic that kept SOAP 1.1 widely deployed alongside 1.2.

#### Q77. [Practical] A SOAP service intermittently returns truncated/corrupted XML responses. Walk through diagnosis.

Truncated or corrupted XML — `SAXParseException: premature end of file`, `unexpected end of stream`, or a response that's valid XML but missing its closing tags — is almost never a SOAP-logic bug; it's a **transport or buffering** problem, and the experienced move is to stop staring at the WSDL and instead look below the SOAP layer (this connects to Q30 chunking and Q43 buffering). My triage starts by characterizing *when*: does it correlate with response size, load, a specific node, or a network path? "Truncated" with a size correlation is a strong fingerprint.

The ranked suspects: (1) **Chunked transfer-encoding mishandled by an intermediary** (Q30) — a proxy, WAF, or old load balancer that doesn't correctly reassemble chunked responses can truncate at a chunk boundary, which is exactly why large responses fail while small ones succeed; the fix is often disabling chunking or fixing the proxy. (2) **A timeout firing mid-response** — if the client's read timeout (Q29) is shorter than the time to stream a large response, the client closes the socket and you get a truncated body that *looks* like server corruption but is client-side impatience; the fix is right-sizing the read timeout for the largest expected response, or streaming. (3) **A server-side error thrown after streaming has begun** — the server starts writing a 200 response, then hits an exception mid-marshal (e.g., a lazy-loaded entity blows up), and can't switch to a Fault because headers are already sent, so it just abandons the stream; the fix is server-side (materialize/validate before responding, or buffer). (4) **Content-Length mismatch** — a `Content-Length` header that disagrees with the actual bytes (a bug in a custom interceptor or compression filter) makes the client read too few or too many bytes. (5) **Character-encoding/multibyte boundary issues** — corruption (not truncation) of specific characters points at an encoding mismatch (a byte-counted buffer splitting a multibyte UTF-8 sequence).

```
Diagnosis path for truncated/corrupted SOAP response:
  size-correlated?  → chunking via proxy (Q30)  OR  read timeout too short (Q29)
  load-correlated?  → buffering/thread issue, or partner overload mid-stream
  specific chars?   → encoding mismatch (UTF-8 vs latin-1, multibyte split)
  Capture below SOAP: tcpdump/Wireshark on BOTH client and a point past any proxy
     → compare bytes seen at each hop → the hop where bytes diverge IS the culprit
```

The decisive technique is to **capture the raw bytes at multiple points** — Wireshark/tcpdump at the client *and* (if you can) just outside the server and just outside any proxy — and compare: the hop where the byte count or content first diverges localizes the fault to a specific box. This is faster and more conclusive than tweaking the application, because it converts "corrupted XML" (a symptom that screams "parser/SOAP bug") into "the WAF truncates responses over 64 KB" (a transport fact you can fix). The senior lesson I'd convey: **trust the layering** — valid-XML-that-arrives-incomplete is overwhelmingly a transport/buffering/timeout problem, so instrument the wire before suspecting the marshaller.

#### Q78. [Theory] How do you bridge a legacy WS-Security/SAML world to a modern OAuth2/OIDC estate?

This is an increasingly common staff-level problem: a regulated SOAP estate authenticates with WS-Security and SAML/WS-Trust (Q62), while the rest of the company has moved to OAuth2/OIDC bearer tokens — and new services need to call old ones and vice versa without weakening either side's security. The wrong answers are "rewrite the SOAP services" (often infeasible/regulated) or "share long-lived static credentials across the boundary" (a security disaster). The right pattern is a **token-translation gateway** that performs a controlled exchange between the two trust models.

For **modern → legacy** (an OAuth2 microservice needs to call a WS-Security/SAML SOAP service): the gateway validates the caller's OAuth2 access token, then **exchanges it for a SAML assertion** by calling the legacy **STS** (Q62) — this is precisely what **OAuth2 Token Exchange (RFC 8693)** standardizes, and it maps cleanly because WS-Trust's RST/RSTR and OAuth token exchange are conceptually the same "present a token, get a different token for a different audience." The gateway then attaches the SAML assertion as a WS-Security header (and signs the message if non-repudiation is required) to the SOAP call. The caller never holds a SAML token or a signing key; the gateway, inside the trust boundary, brokers it.

```
Modern → Legacy:
  OAuth2 service ──(Bearer JWT)──▶ Gateway ──RST(JWT)──▶ STS ──RSTR(SAML)──▶ Gateway
                                   Gateway ──SOAP + WS-Security(SAML, signed)──▶ Legacy SOAP service

Legacy → Modern:
  SOAP client ──WS-Security/SAML──▶ Gateway (validates SAML via STS trust)
              Gateway mints/obtains OAuth2 token (client-credentials or token-exchange)
              Gateway ──Bearer JWT──▶ modern REST/gRPC service
```

For **legacy → modern**, the gateway validates the inbound SAML/WS-Security, maps the SAML subject/claims to an OAuth identity, and obtains an OAuth2 token (via client-credentials or token-exchange) to call the modern service — so the modern service sees a normal Bearer token and needn't understand SAML at all. The design principles that keep this secure: (1) **the gateway is the only thing that spans both trust domains**, so it's hardened, audited (Q22), and its keys live in an HSM; (2) **claims are explicitly mapped and minimized** at the boundary — you don't blindly forward every SAML attribute into a JWT or vice versa, because the two systems have different authorization models, and over-broad claim forwarding is a privilege-escalation risk; (3) **tokens are short-lived and audience-restricted** on both sides so a leaked exchanged token has limited blast radius; (4) **the exchange is logged end-to-end with a correlation ID** (Q65) so an auditor can trace a modern request all the way through the SAML hop to the regulated SOAP action.

The senior framing: don't try to make the SOAP world speak OAuth or the OAuth world speak SAML — **broker between them with a dedicated, trusted gateway** using standardized token exchange (RFC 8693 ↔ WS-Trust), which recognizes that WS-Trust/STS was the spiritual predecessor of OAuth token issuance (Q62) and that the two models map onto each other. This is the same "façade, don't rewrite" judgment as Q19/Q39 applied to *identity* rather than to the API surface, and it lets the regulated core keep its certified WS-Security posture while the rest of the company enjoys OAuth ergonomics.

#### Q79. [Coding] Write a JAXB adapter to correctly serialize Java time types to xsd:dateTime across timezones.

**Problem:** A persistent .NET↔Java interop bug (Q57): `java.time.Instant`/`OffsetDateTime` aren't bound to `xsd:dateTime` cleanly by default, and timezone handling drifts. Write an `XmlAdapter` that serializes to and parses from ISO-8601 `xsd:dateTime` with an explicit offset, so dates round-trip unambiguously.

```java
import jakarta.xml.bind.annotation.adapters.XmlAdapter;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

// Binds an OffsetDateTime <-> xsd:dateTime string WITH explicit zone offset.
// Normalizing to UTC on output removes the ambiguity that causes .NET/Java drift.
public class OffsetDateTimeXsdAdapter extends XmlAdapter<String, OffsetDateTime> {

    // ISO_OFFSET_DATE_TIME emits e.g. 2026-06-16T14:30:00Z  (xsd:dateTime-compatible)
    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    @Override
    public String marshal(OffsetDateTime value) {
        if (value == null) return null;                       // → element omitted / xsi:nil per @XmlElement
        // Normalize to UTC so the wire form is canonical and offset-unambiguous
        return value.withOffsetSameInstant(ZoneOffset.UTC).format(FMT);
    }

    @Override
    public OffsetDateTime unmarshal(String text) {
        if (text == null || text.isBlank()) return null;
        // Parse preserving whatever offset the sender provided; both Z and +hh:mm work
        return OffsetDateTime.parse(text, FMT);
    }
}
```

```java
// Apply it on the field (or package-wide via @XmlJavaTypeAdapters in package-info.java)
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

public class Trade {
    @XmlJavaTypeAdapter(OffsetDateTimeXsdAdapter.class)
    private OffsetDateTime executedAt;     // serializes as 2026-06-16T14:30:00Z
    // ...
}
```

The load-bearing decisions: (1) **always emit an explicit offset** (here normalized to `Z`/UTC) — the root cause of date drift is sending a *zoneless* `xsd:dateTime` like `2026-06-16T14:30:00`, which the receiver then interprets in *its* local zone, shifting the instant; an explicit offset removes all ambiguity and is what WS-I and sane interop demand. (2) **Normalizing to UTC on the wire** gives a canonical form that's also easiest for signatures/caching/comparison and sidesteps the `.NET DateTime.Kind` ambiguity (Q57). (3) Use `OffsetDateTime`/`Instant`, **not** `XMLGregorianCalendar` (the clumsy default) or the legacy `Date`, which conflate local time and instant.

**Edge cases:** a `LocalDate`-only field maps to `xsd:date` (no time/zone) and needs a *separate* adapter — don't force a zone onto a pure calendar date; leap seconds and sub-millisecond precision can differ between stacks, so agree on precision in the contract; and a null must follow the `nillable` vs `minOccurs="0"` decision (Q46) so absent-vs-explicitly-null is intentional. **Time/Space:** `O(1)` per field — formatter is thread-safe and reusable (`DateTimeFormatter` is immutable, unlike the old `SimpleDateFormat`, so it can be a shared `static final`). The broader lesson: date/time and decimal serialization are the **most common silent interop bugs** in cross-stack SOAP, and the fix is to pin the wire representation explicitly (offset + precision) in an adapter rather than trusting two runtimes' defaults to agree.

#### Q80. [Practical] How do you safely retire a SOAP service that you believe has no remaining consumers?

"I think nothing uses this anymore" is how outages are born — the dangerous failure mode is retiring a SOAP endpoint that a forgotten quarterly batch job or a single partner's reconciliation process still calls, and discovering it only when their settlement breaks. The safe retirement is an evidence-driven, reversible process, not a confident deletion. The core principle: **prove non-use with data, then retire in stages with the ability to instantly restore.**

The staged process: (1) **Instrument and observe before deciding** — turn on per-operation access logging (caller IP/cert/identity, timestamp) and let it run for a full business *cycle*, which for finance means at least a **quarter** (and ideally a year) to catch quarterly/annual batch jobs and month-end reconciliations that a two-week sample would entirely miss. Cross-reference with the API registry (Q42) to see which consumers were ever registered. (2) **Announce deprecation** with a published sunset date to all registered consumers and partners, even if logs show no traffic — partners may have dormant integrations they'll reactivate, and contractual notice periods often apply. (3) **Make retirement reversible in stages** before deleting anything: first return a **deprecation SOAP header** on responses (the service still works, but signals sunset); then introduce a **"brownout"** — return a Fault for short scheduled windows (e.g., 5 minutes a day) so any remaining consumer surfaces *loudly* in a controlled window rather than silently depending on it, and you can instantly revert if someone screams; then **disable but keep deployed** (return a clear `Gone`/Fault but leave the code and config in place so re-enabling is a flag flip, not a redeploy); only finally **decommission** the infrastructure.

```
Retirement runbook (each stage reversible, gated on evidence):
  1. Observe:    per-op access logs ≥ 1 full cycle (quarter+) + registry cross-check
  2. Announce:   deprecation notice + sunset date to all registered/contracted consumers
  3. Signal:     return deprecation header (still functional)
  4. Brownout:   scheduled Fault windows (5 min/day) → flush out hidden consumers loudly
  5. Disable:    return Gone/Fault but keep deployed → re-enable = flag flip, not redeploy
  6. Decommission: tear down infra only after a clean disabled period + stakeholder sign-off
```

The senior judgment points: (1) **the WS-Security/contractual angle** — for a regulated flow, retiring an endpoint may have audit and partner-contract implications, so legal/compliance sign-off is part of the gate, not an afterthought (mirrors Q19/Q53's "compliance as a design input"); (2) **brownouts beat passive log-watching** because a consumer that calls the service once a quarter won't appear in a month of logs but *will* fail loudly during a brownout, converting an unknown-unknown into a controlled signal; (3) **reversibility is the whole game** — every stage before decommission must be instantly undoable, because the cost of a wrongly-retired payment endpoint is a settlement outage, whereas the cost of leaving a disabled-but-deployed service around for an extra quarter is trivial. The lesson I'd state: you don't retire a SOAP service by *deciding* it's unused — you *prove* it with a full-cycle of evidence and a brownout, and you keep the off-switch reversible until you're certain, because in a regulated estate the asymmetry between "retired too slowly" and "broke a partner's reconciliation" is enormous.

#### Q81. [Theory] What are the consistency and failure-mode trade-offs between WS-AtomicTransaction (2PC) and a saga for cross-service SOAP operations?

This sharpens Q17's "the industry moved to sagas" into the precise trade-off a staff engineer must articulate. **WS-AtomicTransaction (2PC)** gives **true ACID atomicity** across services: a coordinator runs *prepare* (every participant votes and durably promises it can commit) then *commit* (everyone commits) or *abort* (everyone rolls back), so the multi-service operation is all-or-nothing with strong isolation — the appeal for a payment that must debit one service and credit another *atomically*. The cost is in the failure modes: 2PC is a **blocking protocol** — between prepare and commit, participants hold locks and resources, and if the **coordinator crashes after participants have voted to commit, the participants are stuck** (the "in-doubt" window) holding locks until the coordinator recovers, because they've promised to commit but haven't been told to. This kills availability and throughput, scales terribly across high-latency boundaries, and means the coordinator is a critical single point whose failure freezes participants.

A **saga** trades atomicity for **availability and eventual consistency**. The operation is decomposed into a sequence of local transactions, each with a **compensating action** that semantically undoes it; if step 3 fails, you run the compensations for steps 2 and 1 (e.g., "refund the debit" rather than "roll back the debit"). No global locks, no blocking coordinator, no in-doubt window — each service commits locally and independently, so it scales and stays available. The cost is that there's a window where the system is **inconsistent** (the debit happened but the credit hasn't yet), so other readers can observe intermediate states, and you must design **idempotent, commutative-where-possible, and genuinely reversible** compensations — which is hard, because some actions don't cleanly compensate (you can refund money, but you can't un-send a notification email).

```
                  WS-AT / 2PC                          Saga
Consistency       strong ACID, atomic, isolated        eventual; intermediate states visible
Locking           holds locks across prepare→commit    none across steps (local commits)
Coordinator       required; crash → in-doubt blocking   none; orchestrator/choreography, non-blocking
Availability      poor under partition/coordinator fail high; each step independent
Scale/latency     bad across WAN/many participants      good; designed for distributed scale
Failure recovery  coordinator recovery, in-doubt resolution  compensating transactions
Hard part         the blocking/in-doubt window          designing correct, idempotent compensations
```

The decision framework: choose **2PC only when** participants are few, co-located/low-latency, the operation is short, and strong isolation is genuinely required *and* the availability hit is acceptable — increasingly rare. Choose a **saga** for cross-service, cross-network, or microservice contexts where availability and scale matter and you can model correct compensations — which is most modern distributed work. The senior nuance that elevates the answer: it's not purely binary — you can keep a **local ACID transaction within each service** (the database does real 2PC across its own resources) and a **saga across services**, getting strong consistency where it's cheap (one service's DB) and eventual consistency where 2PC is expensive (across the network). For a regulated SOAP payment flow specifically, the pattern is a saga *plus* the idempotency/dedup and outbox machinery from Q36 to make compensations and retries safe — which is exactly why Q53's migration kept exactly-once guarantees via idempotency rather than via WS-AT. The expert point is that "sagas are better" is too glib: 2PC's atomicity is real and sometimes worth its cost, but its **blocking in-doubt failure mode** is what makes it unsuitable for the distributed, high-availability systems most teams now build.

#### Q82. [Practical] How do you mock or stub a SOAP dependency for testing without hitting the real partner?

You cannot run your CI against a partner's production bank endpoint, so a fast, deterministic SOAP stub is essential — and the cardinal rule is that the **stub must be generated from or validated against the published WSDL/XSD**, never a hand-written mock that can silently drift from the real contract (a lying mock is worse than no mock, Q72). There are three tiers depending on what you're testing.

**Tier 1 — in-process mock of the generated port (unit tests).** Since contract-first gives you a typed SEI, you mock it like any interface with Mockito, which is fast and great for testing *your* logic around the call (resilience, mapping, error handling), but it tests nothing about the actual SOAP wire format.

```java
QuoteService port = Mockito.mock(QuoteService.class);
when(port.getQuote("AAPL")).thenReturn(189.50);
// also exercise the fault path:
when(port.getQuote("BAD")).thenThrow(new SOAPFaultException(makeReceiverFault()));
ResilientQuoteClient client = new ResilientQuoteClient(port);
assertEquals(189.50, client.getQuote("AAPL"));
```

**Tier 2 — a real SOAP server stub over HTTP (integration tests).** To exercise the *actual* envelope, marshalling, headers, and fault parsing, stand up a lightweight server that speaks SOAP. Options: publish a JAX-WS `Endpoint` with a stub implementation on a localhost port; use **SoapUI's MockService** (it generates a mock from the WSDL with canned responses — great for sharing with the team and the partner); or use **WireMock** with XML body-matching to return fixture envelopes for specific requests, including **fault and timeout simulation**.

```java
// WireMock: match on the SOAP operation in the body, return a fixture response envelope
stubFor(post(urlEqualTo("/ws/quotes"))
    .withHeader("Content-Type", containing("application/soap+xml"))
    .withRequestBody(containing("<q:symbol>AAPL</q:symbol>"))
    .willReturn(aResponse().withStatus(200)
        .withHeader("Content-Type", "application/soap+xml")
        .withBodyFile("getQuote-AAPL-response.xml")));   // a real, schema-valid envelope

// Crucially, also stub the SAD paths:
stubFor(post(urlEqualTo("/ws/quotes")).withRequestBody(containing("FAULTME"))
    .willReturn(aResponse().withStatus(500).withBodyFile("receiver-fault.xml")));   // Q32/Q73
stubFor(post(urlEqualTo("/ws/quotes")).withRequestBody(containing("SLOW"))
    .willReturn(aResponse().withFixedDelay(15000)));     // exercise read-timeout/circuit-breaker (Q73)
```

**Tier 3 — record/replay against a captured corpus.** Capture real (sanitized, PII-masked) request/response pairs from a UAT session with the partner (Q15/Q33 capture technique), store them as fixtures, and replay them — this gives the highest-fidelity stub because the responses are literally what the partner produced, catching the prefix/namespace/nil quirks (Q26/Q57) a synthetic stub would miss.

The senior framing of *what to stub*: don't just stub the happy path — the whole point of a stub is to **deterministically exercise the failure modes you can't trigger on demand against the real partner**: SOAP Faults (`Sender` vs `Receiver`, to verify your retry predicate from Q73), slow responses (to verify timeouts and the circuit breaker trip), truncated/malformed responses (Q77), and WS-Security validation failures. Keep fixtures **schema-valid and contract-anchored** (regenerate them when the WSDL version bumps, tied to the Q72 contract-test pipeline) so the stub can't drift from reality, and reserve a thin **smoke-test suite against the partner's actual UAT endpoint** for pre-release verification — because no stub perfectly captures a flaky real partner, and the bugs that bite in production (chunking, proxy mutation, cert issues, clock skew) live precisely in the gap between your stub and their real infrastructure.

#### Q83. [Theory] As a staff engineer, how do you build the business and risk case for whether to invest in, maintain, or sunset a SOAP estate?

This is the meta-question that ties the whole topic together: beyond knowing SOAP's mechanics, a staff/principal engineer must make and *defend* the portfolio decision about a SOAP estate to non-technical leadership, framed in **risk and money, not protocol aesthetics**. The mistake junior-to-senior engineers make is arguing "SOAP is old, let's replace it" (technology fashion) or "SOAP works, don't touch it" (inertia) — both skip the actual analysis. The staff approach is to evaluate each service (or cluster) on a small set of orthogonal axes and let the *combination* drive the decision.

The axes I'd assess: (1) **Constraint** — does a regulator or partner *contractually mandate* a WS-* capability (WS-Security non-repudiation, WS-RM exactly-once) with no clean modern equivalent? If yes, replacement is off the table regardless of engineering preference (Q19) — you can only wrap, not rip out. (2) **Change velocity / friction cost** — how often does this contract change, and what does each change cost in developer time, codegen, and partner coordination? A stable, rarely-changing service has near-zero ongoing cost; a frequently-evolving one bleeds velocity and is a migration candidate. (3) **Operational risk** — is the stack on supported, patched versions (CXF/Metro CVEs, Q35/Q71), or is it a frozen, unpatchable liability? An unmaintained SOAP stack with known XXE/XSW exposure is a *security* argument for action independent of velocity. (4) **Consumer base & blast radius** — how many consumers, how critical, what's the cost of a botched migration (Q53/Q80)? (5) **Migration ROI** — concrete cost of change (engineering, re-certification, partner onboarding, risk) versus concrete benefit (velocity, talent, operational simplification).

```
Decision matrix (per service/cluster):
                          │ Mandated WS-* guarantee?
                          │   YES                        NO
  ────────────────────────┼───────────────────────────────────────────────
  Stable + patched + low  │ KEEP as certified core      KEEP (low cost; don't churn)
   consumer friction      │   (Q19); wrap w/ façade
  ────────────────────────┼───────────────────────────────────────────────
  High velocity / friction│ HYBRID: façade for velocity, MIGRATE to REST/gRPC
   OR unpatched/at-risk    │ certified SOAP core for the   (strangler-fig, Q39);
                          │ mandated flows (Q19/Q78)      sunset SOAP (Q80)
```

The output I'd bring to leadership is not "rewrite everything" but a **portfolio plan**: a small number of services land in "keep as certified core, wrap with a façade for modern consumers" (the regulated flows — Q19/Q22/Q78); the bulk of low-friction stable services land in "leave alone, don't spend money churning working software"; and the high-friction or unpatched-and-risky ones get a **funded, incremental migration** (strangler-fig, Q39) with a quantified ROI and a risk-managed rollout (Q53/Q80). Critically, I'd **quantify**: migration cost in engineer-months, re-certification cost, the velocity tax of the status quo, and the security risk of unpatched stacks — because leadership funds *risk reduction and ROI*, not "this is legacy." I'd also bring **compliance and key partners in as stakeholders early** (the recurring Q19/Q53/Q78/Q80 theme), because the binding constraints are usually contractual/regulatory, not technical.

The senior signal an interviewer is listening for: I treat "legacy" as a **neutral portfolio-management problem**, not a pejorative; I optimize for **business risk and total cost of ownership** over technical fashion; I recognize that the right answer is almost always a **differentiated strategy across the estate** (keep some, wrap some, migrate some, sunset some) rather than a single sweeping verdict; and I can defend that strategy in the language of money, regulatory risk, and reversibility to people who don't know what a WSDL is. That ability — to make the *judgment call* and *justify it to the business* — is what distinguishes a staff engineer from a very good senior engineer who merely knows SOAP deeply.

## ✅ Key Takeaways

- A SOAP message is an Envelope with an optional **Header** (cross-cutting metadata: security, addressing, transactions) and a mandatory **Body** (payload or Fault).
- **WSDL** is the machine-readable service contract; **XSD** defines the data types — together they enable codegen and strong typing.
- Prefer **contract-first** for any externally consumed or long-lived service; code-first only for prototypes.
- SOAP's lasting advantage over REST is the **WS-* stack**: WS-Security (message-level signing/encryption), WS-Addressing (transport-neutral correlation/async), WS-ReliableMessaging (guaranteed delivery), WS-AtomicTransaction (2PC).
- Use **MTOM/XOP** for large binary attachments to avoid Base64 bloat.
- Understand the **processing model** (`mustUnderstand`, roles, intermediaries) and **document/literal wrapped** as the interop standard.
- In 2026 SOAP still matters in **banking, insurance, healthcare, telecom, and government**; the modern pattern is a **REST/gRPC façade over a certified SOAP core**, not a rewrite.
- Java note: **`javax.*` (Java 8 / Spring Boot 2)** moved to **`jakarta.*` (Java 11+/17/21, Spring Boot 3)**; JAX-WS was removed from the JDK after Java 8 and now ships as a separate dependency.

## ⚠️ Common Pitfalls

- Disabling timestamp/replay protection or signature validation to "make integration work" — reopens replay and tampering attacks.
- Leaving XML parsers open to **XXE** and **billion-laughs** DoS; not hardening against **signature-wrapping (XSW)** attacks.
- Mutating a live XSD (removing, retyping, or reordering elements) and breaking strict-validating clients in production instead of versioning by namespace.
- Rebuilding `JAXBContext` per request (expensive) instead of caching it; building full DOM trees for huge messages instead of streaming/MTOM.
- Mixing up SOAP 1.1 vs 1.2 fault structures and envelope namespaces, or assuming `rpc/encoded` services still interoperate.
- Choosing WS-AtomicTransaction (2PC) for cross-service consistency in a high-latency/microservice context where a saga is the right tool.
- Hand-crafting SOAP XML when a WSDL exists — skip codegen and you lose type safety and invite prefix/namespace bugs.

## 📚 Further Reading

- **W3C SOAP 1.2 Specification** (Primer, Part 1 & 2) — the authoritative protocol definition: https://www.w3.org/TR/soap12/
- **W3C WSDL 2.0** and **XML Schema (XSD 1.1)** specifications — service and data contract definitions.
- **OASIS WS-Security, WS-SecurityPolicy, WS-ReliableMessaging, WS-AtomicTransaction** standards — the WS-* stack from the source: https://www.oasis-open.org/
- **WS-I Basic Profile** — the interoperability profile every enterprise SOAP service should conform to.
- *Spring Web Services Reference Documentation* and *Apache CXF User Guide* — practical contract-first JAX-WS, WSS4J security, and MTOM in Java.
- *SOA Principles of Service Design* and *Web Service Contract Design & Versioning for SOA* by Thomas Erl — design and versioning depth for SOAP/WS-*.
