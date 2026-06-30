# Networking

[← Back to master index](../README.md)

A deep, interview-focused reference on computer networking for backend and systems engineers — covering the OSI and TCP/IP models, TCP vs UDP, the TCP handshake/teardown, flow and congestion control, the evolution from HTTP/1.1 to HTTP/2 to HTTP/3 (QUIC), TLS, DNS, IP addressing and CIDR, NAT/ARP/ICMP, sockets and WebSockets, gRPC, load balancing (L4 vs L7), proxies and CDNs, and the HTTP plumbing (cookies, CORS, keep-alive, connection pooling) you touch every day. Examples use Java and are current through 2026.

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

### Q1. [Theory] What is the OSI model, and how does it map to the TCP/IP model?

The **OSI (Open Systems Interconnection)** model is a 7-layer conceptual framework that standardizes the functions of a networking system. It is primarily a teaching and reasoning tool — real implementations follow the leaner 4-layer **TCP/IP** model.

```
OSI (7 layers)            TCP/IP (4 layers)     Examples / PDU
-----------------------   -------------------   ----------------------------
7 Application  ┐                                HTTP, DNS, gRPC, TLS    (data)
6 Presentation ├────────  Application           encoding, encryption
5 Session      ┘                                sessions, sockets
4 Transport    ─────────  Transport             TCP, UDP, QUIC     (segment/datagram)
3 Network      ─────────  Internet              IP, ICMP, ARP*     (packet)
2 Data Link    ┐                                Ethernet, MAC,     (frame)
1 Physical     ┘────────  Link / Network Access  Wi-Fi, cables      (bits)
```

Mnemonic top-down: **A**ll **P**eople **S**eem **T**o **N**eed **D**ata **P**rocessing. Each layer adds its own header (encapsulation) as data moves down the stack, and strips it on the way up. The key interview point: layers provide **abstraction** — TCP doesn't care whether the physical medium is fiber or Wi-Fi, and HTTP doesn't care whether it rides TCP or QUIC. (*ARP is technically a link-layer protocol that straddles L2/L3.)

### Q2. [Theory] What is the difference between TCP and UDP? When would you choose each?

Both are **transport-layer** protocols, but they make opposite trade-offs.

```
                 TCP                          UDP
---------------  ---------------------------  ---------------------------
Connection       Connection-oriented          Connectionless
Reliability      Guaranteed, ordered, no dup  Best-effort, may drop/reorder
Flow control     Yes (sliding window)         No
Congestion ctrl  Yes                          No (app must handle)
Header size      20–60 bytes                  8 bytes
Speed/latency    Higher overhead              Lower overhead
Use cases        HTTP, SSH, DB, email         DNS, VoIP, video, gaming, QUIC
```

**TCP** gives you a reliable, ordered byte stream: it numbers bytes, acknowledges them, retransmits losses, and reorders out-of-sequence segments. You pay for this with handshake latency and head-of-line blocking. **UDP** is a thin wrapper over IP — fire-and-forget datagrams with a checksum.

Choose **TCP** when correctness matters more than latency (web pages, file transfer, database connections). Choose **UDP** when you can tolerate loss but not delay, or when you want to build your own reliability semantics on top — which is exactly what **QUIC/HTTP/3** does over UDP. Real-time media prefers a slightly glitchy live stream over a perfectly ordered but late one.

### Q3. [Theory] Walk through the TCP three-way handshake and the connection teardown.

**Handshake (connection setup)** establishes initial sequence numbers (ISNs) and confirms both sides can send and receive.

```
Client                         Server
  |  --- SYN (seq=x) --------->  |   "I want to talk, my ISN is x"
  |  <-- SYN-ACK (seq=y,ack=x+1) |   "OK, my ISN is y, I got x"
  |  --- ACK (ack=y+1) -------->  |   "Got y, let's go"
ESTABLISHED                  ESTABLISHED
```

Three messages (not four) because the server piggybacks its SYN onto the ACK. After this, data flows.

**Teardown (four-way)** — because TCP is full-duplex, each direction is closed independently with a FIN/ACK pair:

```
Client                         Server
  |  --- FIN ----------------->  |   "I'm done sending"
  |  <-- ACK -----------------   |
  |  <-- FIN -----------------   |   "I'm done too"
  |  --- ACK ----------------->  |
TIME_WAIT (2*MSL)            CLOSED
```

The active closer enters **TIME_WAIT** for 2×MSL (Maximum Segment Lifetime, often ~60s total) to absorb any delayed duplicate segments and ensure the final ACK is received. A server with many connections in TIME_WAIT can exhaust ephemeral ports — a classic production symptom.

### Q4. [Theory] What is an IP address? Explain IPv4 vs IPv6.

An **IP address** is a logical, network-layer identifier for a host's interface, used for routing packets across networks.

- **IPv4**: 32-bit, written as four dotted octets (`192.168.1.10`). ~4.3 billion addresses — long exhausted, which is why NAT and CIDR exist.
- **IPv6**: 128-bit, written as eight hex groups (`2001:0db8:85a3::8a2e:0370:7334`). ~3.4×10³⁸ addresses, so NAT is largely unnecessary; it also has a simpler fixed header, built-in autoconfiguration (SLAAC), and recommended (SHOULD, not mandatory) support for IPsec — it was originally mandatory in early IPv6 specs (RFC 4294) but was downgraded to a SHOULD by RFC 6434 / RFC 8504, so "IPsec is mandatory in IPv6" is now an outdated myth.

IPv6 notation allows **zero compression**: a run of all-zero groups collapses to `::` (once per address), and leading zeros in a group are dropped. So `2001:0db8:0000:0000:0000:0000:0000:0001` → `2001:db8::1`. Adoption is mixed; dual-stack (running both) is the common reality in 2026, with most public clouds defaulting to IPv4 for legacy reasons but offering IPv6.

### Q5. [Theory] What are ports and sockets? How does a single server handle many clients?

A **port** is a 16-bit number (0–65535) that identifies a specific process/service on a host. Well-known ports (0–1023) are reserved: 80 (HTTP), 443 (HTTPS), 22 (SSH), 53 (DNS), 5432 (PostgreSQL). Ephemeral ports (typically 49152–65535) are assigned to client-side connections.

A **socket** is the OS endpoint for network I/O, uniquely identified by a **5-tuple**:

```
(protocol, src IP, src port, dst IP, dst port)
```

This is the key insight for "how does one server port serve thousands of clients": the **listening socket** sits on `(TCP, *, 443, ...)`, but each accepted connection becomes a distinct **connected socket** because the client side of the tuple (client IP + client port) differs. The server can have one listening socket and 50,000 established sockets all on port 443.

### Q6. [Practical] Write a minimal TCP echo client and server in Java using blocking sockets.

```java
// Server: accepts one connection, echoes lines back.
import java.io.*;
import java.net.*;

public class EchoServer {
    public static void main(String[] args) throws IOException {
        try (ServerSocket server = new ServerSocket(9000)) {     // bind + listen
            System.out.println("Listening on 9000...");
            try (Socket client = server.accept();                // blocks until a client connects
                 BufferedReader in = new BufferedReader(
                     new InputStreamReader(client.getInputStream()));
                 PrintWriter out = new PrintWriter(client.getOutputStream(), true)) {
                String line;
                while ((line = in.readLine()) != null) {         // null == client closed (FIN)
                    out.println("echo: " + line);
                }
            }
        }
    }
}
```

```java
// Client
import java.io.*;
import java.net.*;

public class EchoClient {
    public static void main(String[] args) throws IOException {
        try (Socket socket = new Socket("localhost", 9000);      // 3-way handshake happens here
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(
                 new InputStreamReader(socket.getInputStream()))) {
            out.println("hello");
            System.out.println(in.readLine());                   // "echo: hello"
        }
    }
}
```

The blocking model is simple but one thread per connection scales poorly into the thousands; production servers use NIO selectors, Netty, or virtual threads (Project Loom, GA since Java 21) to multiplex.

### Q7. [Theory] What is DNS and how does a domain name get resolved to an IP?

**DNS (Domain Name System)** is the distributed, hierarchical directory that maps human-readable names (`api.example.com`) to IP addresses. Resolution is typically **recursive** from the client's perspective and **iterative** from the resolver's:

```
Browser → Stub resolver → Recursive resolver (e.g. 8.8.8.8)
                                |
       (iterative)             v
   Root servers (.)  ──►  ".com is at these TLD servers"
   TLD servers (.com) ─►  "example.com's authoritative NS is ns1.example.com"
   Authoritative NS ──►  "api.example.com = 93.184.216.34"  (A record)
                                |
                                v
   Resolver caches by TTL, returns to browser
```

Common record types: **A** (IPv4), **AAAA** (IPv6), **CNAME** (alias), **MX** (mail), **NS** (nameserver), **TXT** (arbitrary text, used for SPF/verification), **SOA** (zone metadata). Caching at every level (browser, OS, resolver) governed by each record's **TTL** is what keeps DNS fast and the root servers from melting.

### Q8. [Theory] What is HTTP, and what changed between HTTP/1.0 and HTTP/1.1?

**HTTP (HyperText Transfer Protocol)** is a stateless, request/response application-layer protocol. A request has a method (GET, POST...), a URL, headers, and an optional body; a response has a status code, headers, and a body.

Key improvements **HTTP/1.1** (1997) brought over **HTTP/1.0**:

- **Persistent connections (keep-alive) by default** — reuse one TCP connection for multiple requests instead of one connection per request. This avoids repeated handshake cost.
- **Pipelining** — send multiple requests without waiting for each response (rarely used in practice due to head-of-line blocking and buggy proxies).
- **`Host` header (mandatory)** — enables virtual hosting (many domains on one IP).
- **Chunked transfer encoding** — stream a response of unknown length.
- **Better caching** (`Cache-Control`, `ETag`) and **range requests** (resumable downloads).

### Q9. [Theory] What is the difference between HTTP and HTTPS?

**HTTPS** is HTTP layered over **TLS (Transport Layer Security)**. It adds three guarantees:

1. **Confidentiality** — traffic is encrypted, so eavesdroppers see only ciphertext.
2. **Integrity** — tampering is detected via message authentication codes.
3. **Authentication** — the server proves its identity via an X.509 certificate signed by a trusted Certificate Authority (CA).

The default port is **443** (vs 80 for plain HTTP). Modern browsers mark plain HTTP as "Not Secure," HSTS forces HTTPS, and protocols like HTTP/2 and HTTP/3 effectively require TLS. There is no meaningful reason to serve a public site over plain HTTP in 2026.

### Q10. [Theory] What are HTTP cookies and how are sessions maintained over a stateless protocol?

HTTP is stateless — each request is independent. **Cookies** add state: the server sends `Set-Cookie: session=abc123` in a response, and the browser automatically attaches `Cookie: session=abc123` on subsequent requests to that domain.

Two common patterns:

- **Server-side sessions**: the cookie holds an opaque **session ID**; the actual data lives in server memory or a store (Redis). Easy to invalidate, but requires shared/sticky storage across servers.
- **Stateless tokens (e.g. JWT)**: the cookie/header carries a signed token containing the claims themselves; the server validates the signature without a lookup. Scales horizontally but is harder to revoke before expiry.

Important cookie attributes: **`HttpOnly`** (JS can't read it — mitigates XSS theft), **`Secure`** (HTTPS only), **`SameSite`** (`Strict`/`Lax`/`None` — mitigates CSRF), **`Domain`/`Path`** (scope), and **`Max-Age`/`Expires`** (lifetime).

### Q11. [Theory] What does a URL contain? Break down its components.

```
   https://user:pass@api.example.com:8443/v1/orders?status=open&page=2#section
   \___/   \_______/ \_____________/ \__/\________/ \________________/ \_____/
  scheme    userinfo      host       port   path          query        fragment
```

- **scheme** — protocol (`https`, `ws`, `grpc`).
- **userinfo** — optional credentials (deprecated for security).
- **host** — domain or IP, resolved via DNS.
- **port** — defaults from scheme (443 for https) if omitted.
- **path** — hierarchical resource locator.
- **query** — `key=value` pairs after `?`, sent to the server.
- **fragment** — after `#`, processed **client-side only**, never sent to the server.

### Q12. [Practical] Make an HTTP GET request in Java using the built-in HttpClient.

Java 11+ ships a modern, async-capable `HttpClient` in `java.net.http` — no third-party library needed.

```java
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

public class HttpGetExample {
    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)        // negotiates h2, falls back to h1.1
            .connectTimeout(Duration.ofSeconds(5))
            .build();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://httpbin.org/get"))
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();

        HttpResponse<String> resp =
            client.send(request, HttpResponse.BodyHandlers.ofString());

        System.out.println("Status: " + resp.statusCode());   // 200
        System.out.println("HTTP version: " + resp.version());
        System.out.println(resp.body());
    }
}
```

The same client supports `sendAsync(...)` returning a `CompletableFuture`, connection pooling, and HTTP/2 multiplexing automatically.

### Q13. [Theory] What is the difference between latency, bandwidth, and throughput?

- **Latency** — time for one bit/packet to travel from A to B (and back, for RTT). Measured in milliseconds. Bounded by the speed of light and the number of hops; you can't cache your way around physics.
- **Bandwidth** — the maximum data rate a link *can* carry, like the width of a pipe (bits/second).
- **Throughput** — the data rate you *actually achieve*, always ≤ bandwidth, degraded by congestion, packet loss, protocol overhead, and the bandwidth-delay product.

Analogy: bandwidth is the number of lanes on a highway, latency is how long the drive takes, throughput is how many cars actually arrive per minute. High bandwidth does **not** fix high latency — a chatty protocol over a satellite link feels slow no matter the bandwidth.

### Q14. [Theory] What is a MAC address and how does it differ from an IP address?

A **MAC (Media Access Control) address** is a 48-bit hardware identifier burned into a network interface (e.g. `00:1A:2B:3C:4D:5E`), operating at the **data-link layer (L2)**. An **IP address** is a logical, configurable address at the **network layer (L3)**.

```
IP address  → WHERE you are (routable across the internet, changes by network)
MAC address → WHO you are    (local segment only, tied to the hardware)
```

Routing across the internet uses IP; delivery on the final local network segment uses MAC. The protocol that bridges them — finding the MAC for a given IP on the local network — is **ARP** (covered later). An analogy: the IP is the street address used by the postal system; the MAC is the specific mailbox on that street.

---

## 🟡 Intermediate (3–7 yrs)

### Q15. [Theory] Explain CIDR notation and how to compute a subnet's range.

**CIDR (Classless Inter-Domain Routing)** replaced the old rigid Class A/B/C system with a flexible prefix length: `IP/prefix`, where the prefix is the number of leading bits that identify the **network**; the rest identify **hosts**.

```
192.168.1.0/24
  /24  → 24 network bits, 8 host bits
  mask → 255.255.255.0
  range → 192.168.1.0  ...  192.168.1.255
  usable hosts → 2^8 - 2 = 254  (minus network + broadcast)
```

Quick math: usable hosts = 2^(32 − prefix) − 2 (for IPv4). Smaller prefix = bigger network:

```
/24 → 256 addresses     (a typical small subnet)
/16 → 65,536 addresses
/30 → 4 addresses (2 usable — point-to-point links)
/32 → a single host
```

CIDR also enables **route aggregation/supernetting**: `10.0.0.0/8` summarizes everything from `10.0.0.0` to `10.255.255.255` in one routing-table entry. AWS VPCs, Kubernetes pod CIDRs, and firewall rules are all expressed this way.

### Q16. [Coding] Write a function to check whether an IPv4 address belongs to a given CIDR block.

The approach: convert both the address and the network to 32-bit integers, build the mask from the prefix, and compare the masked values.

```java
public class CidrMatcher {

    /** Returns true if `ip` falls within `cidr` (e.g. "10.1.2.3" in "10.1.0.0/16"). */
    public static boolean inRange(String ip, String cidr) {
        String[] parts = cidr.split("/");
        int network = toInt(parts[0]);
        int prefix  = Integer.parseInt(parts[1]);

        // mask = prefix ones followed by (32-prefix) zeros. Guard prefix==0.
        int mask = (prefix == 0) ? 0 : (~0 << (32 - prefix));

        return (toInt(ip) & mask) == (network & mask);
    }

    private static int toInt(String ip) {
        String[] o = ip.split("\\.");
        int result = 0;
        for (String octet : o) {
            result = (result << 8) | (Integer.parseInt(octet) & 0xFF);
        }
        return result;   // 32-bit value packed into an int
    }

    public static void main(String[] args) {
        System.out.println(inRange("10.1.2.3", "10.1.0.0/16")); // true
        System.out.println(inRange("10.2.2.3", "10.1.0.0/16")); // false
        System.out.println(inRange("8.8.8.8",  "0.0.0.0/0"));   // true (matches all)
    }
}
```

Complexity is O(1). The `& 0xFF` matters because Java bytes are signed; the `prefix == 0` guard avoids the undefined `<< 32` shift (in Java, `x << 32 == x << 0`, a classic bug).

### Q17. [Theory] What is NAT, and what problem does it solve?

**NAT (Network Address Translation)** lets many devices on a private network share one (or a few) public IP addresses. It rewrites the source IP/port of outgoing packets and maintains a translation table to route replies back.

```
Private host          NAT router (public 203.0.113.5)     Internet server
10.0.0.7:51000  ──►   rewrites src to 203.0.113.5:40001  ──►  93.184.216.34:443
10.0.0.8:51000  ──►   rewrites src to 203.0.113.5:40002  ──►  ...
                       (table maps each public port back to the private socket)
```

The most common form is **PAT (Port Address Translation)**, aka NAT overload, which multiplexes on port numbers. NAT was a pragmatic response to IPv4 exhaustion and incidentally provides a basic firewall (unsolicited inbound traffic has no table entry). Downsides: it breaks end-to-end connectivity, complicates peer-to-peer (requiring STUN/TURN/hole-punching), and is why you need port forwarding to host a server behind a home router. IPv6's vast address space aims to make NAT unnecessary.

### Q18. [Theory] Explain ARP and ICMP. Where do they sit in the stack?

- **ARP (Address Resolution Protocol)** maps an **IP address → MAC address** on a local network segment. When a host wants to send to `10.0.0.5` on the same LAN, it broadcasts "Who has 10.0.0.5?"; the owner replies with its MAC. Results are cached in the ARP table. It operates between L2 and L3. (IPv6 replaces ARP with **NDP**, Neighbor Discovery Protocol.)

- **ICMP (Internet Control Message Protocol)** is a network-layer (L3) protocol for diagnostics and error reporting — *not* for carrying application data. It powers `ping` (echo request/reply) and `traceroute` (via TTL-exceeded messages), and reports errors like "Destination Unreachable" and "Time Exceeded."

```
ARP   → "find the MAC for this local IP"      (L2/L3, local segment)
ICMP  → "report an error / probe reachability" (L3, end-to-end)
```

A security note: **ARP spoofing** enables man-in-the-middle on a LAN, and ICMP is often rate-limited or filtered at firewalls (which is why ping sometimes fails for hosts that are actually up).

### Q19. [Theory] How does TCP flow control work?

**Flow control** prevents a fast sender from overwhelming a slow receiver. TCP uses a **sliding window**: the receiver advertises a **receive window (rwnd)** in every ACK — the amount of buffer space it currently has free. The sender may have at most `rwnd` bytes unacknowledged "in flight."

```
Sender's view of the byte stream:

  [ sent+ACKed | sent, not ACKed | can send now | can't send yet ]
                 \______________window (rwnd)_____/
                          slides right as ACKs arrive
```

If the receiver's application is slow to read, its buffer fills, `rwnd` shrinks toward 0, and the sender pauses. When the buffer drains, the receiver advertises a larger window via a **window update**. A subtlety: a zero-window then a lost window-update could deadlock, so the sender sends periodic **zero-window probes**. Modern stacks use **window scaling** (RFC 7323) to allow windows far beyond the 16-bit field's 64 KB limit, essential for high-bandwidth-delay links.

### Q20. [Theory] How does TCP congestion control work? Name the key phases/algorithms.

Where flow control protects the *receiver*, **congestion control** protects the *network* from collapse. TCP maintains a **congestion window (cwnd)**; the effective send window is `min(cwnd, rwnd)`. The classic algorithm (Reno/NewReno):

```
1. Slow Start         cwnd doubles each RTT (exponential) until ssthresh
2. Congestion Avoid.  cwnd grows +1 MSS per RTT (linear, "additive increase")
3. On packet loss:
     - 3 dup ACKs  → Fast Retransmit + Fast Recovery (halve cwnd)
     - timeout     → drastic: cwnd back to 1, slow start again
```

This is **AIMD** (Additive Increase, Multiplicative Decrease), which is provably fair across competing flows. Loss is treated as a congestion signal.

Modern stacks (Linux default since kernel 2.6.19, late 2006) use **CUBIC**, which grows the window as a cubic function of time since the last loss — better for high-speed networks. **BBR** (Google) takes a different tack entirely: it models the bottleneck **B**andwidth and **R**ound-trip propagation time rather than reacting to loss, achieving higher throughput on lossy links (e.g. mobile) and lower buffer bloat.

### Q21. [Theory] What is head-of-line (HOL) blocking, and how does each HTTP version address it?

**HOL blocking** is when one stalled item at the front of a queue blocks everything behind it, even if those items are ready.

- **HTTP/1.1**: only one request is "in flight" per connection (pipelining is broken in practice), so a slow response blocks the connection. Browsers work around this by opening ~6 parallel TCP connections per origin — wasteful and still limited.
- **HTTP/2**: introduces **multiplexing** — many independent **streams** over one TCP connection, so application-layer HOL blocking is gone. *But* because it still rides one TCP connection, a single lost TCP segment stalls **all** streams (transport-layer HOL blocking remains).
- **HTTP/3 (QUIC)**: runs over UDP and implements streams in user space with **per-stream** loss recovery. A lost packet only stalls the affected stream; others proceed. This finally eliminates transport-layer HOL blocking.

```
HTTP/1.1: [req1]→[req2]→[req3]   one at a time (HOL at app layer)
HTTP/2:   stream1 ┐
          stream2 ├─ one TCP conn (HOL at TCP layer if a segment is lost)
          stream3 ┘
HTTP/3:   stream1 ┐
          stream2 ├─ QUIC over UDP, independent loss recovery (no transport HOL)
          stream3 ┘
```

### Q22. [Theory] What new features does HTTP/2 add beyond multiplexing?

- **Binary framing** — replaces HTTP/1.1's text protocol with a compact binary layer of frames (HEADERS, DATA, etc.), which is faster to parse and less error-prone.
- **Header compression (HPACK)** — headers are highly repetitive across requests; HPACK uses a dynamic table + Huffman coding to avoid resending them, a big win for cookie-heavy traffic.
- **Stream prioritization** — clients can hint which streams matter more (e.g. CSS before images).
- **Server push** — the server can proactively send resources it knows the client will need. (In practice push was underused and often counterproductive, and has been **deprecated/removed** from most browsers — don't recommend it in 2026; use `103 Early Hints` instead.)

All of this runs over a single TCP+TLS connection per origin, reducing handshake and connection overhead dramatically versus HTTP/1.1's connection-per-origin sprawl.

### Q23. [Theory] What is QUIC, and why does HTTP/3 build on it?

**QUIC** is a transport protocol built on **UDP** that reimplements the reliability, ordering, and congestion control that TCP provides, but in **user space** and integrated with **TLS 1.3**. HTTP/3 is simply HTTP semantics mapped onto QUIC. Benefits:

1. **No transport-layer HOL blocking** — per-stream loss recovery (see Q21).
2. **Faster handshakes** — QUIC merges the transport and TLS handshakes, so a new connection is **1-RTT**, and resumed connections can be **0-RTT** (send data with the first packet).
3. **Connection migration** — a QUIC connection is identified by a **Connection ID**, not the 4-tuple, so it survives an IP change (Wi-Fi → cellular) without re-handshaking. Huge for mobile.
4. **Always encrypted** — even most transport metadata is protected, reducing ossification by middleboxes.

The cost: UDP is sometimes throttled by networks, QUIC is more CPU-intensive (encryption per packet, user-space processing), and it's harder to inspect. As of 2026 the major CDNs and browsers support HTTP/3 widely.

### Q24. [Theory] Walk through the TLS 1.3 handshake. How does it differ from TLS 1.2?

**TLS 1.3** (RFC 8446) streamlined the handshake to **one round trip** (1-RTT) for a new connection:

```
Client                                  Server
  | -- ClientHello -------------------->  |   supported ciphers + key share (guess)
  |                                       |
  | <-- ServerHello -------------------   |   chosen cipher + key share
  |     {EncryptedExtensions}             |   ...now everything below is encrypted...
  |     {Certificate, CertVerify}         |   server's cert + proof of private key
  |     {Finished} -------------------    |
  |                                       |
  | -- {Finished} --------------------->  |
  [ application data flows ]
```

Both sides derive the shared secret via **(EC)DHE** key exchange immediately, so the rest of the handshake is already encrypted. Differences from **TLS 1.2**:

- **1-RTT** instead of 2-RTT, plus an optional **0-RTT** resumption mode (with replay caveats).
- Removed insecure legacy crypto: RSA key transport, static DH, RC4, CBC-mode MACs, SHA-1. Only **forward-secret** AEAD cipher suites remain.
- Forward secrecy is **mandatory**, so a compromised server key can't decrypt past sessions.

The CA-signed certificate provides **authentication**; the ephemeral key exchange provides **confidentiality with forward secrecy**.

### Q25. [Practical] Explain CORS. Write the headers a server returns to allow a cross-origin request.

**CORS (Cross-Origin Resource Sharing)** relaxes the browser's **Same-Origin Policy**, which by default blocks JavaScript from reading responses from a different origin (scheme + host + port). The server opts in via response headers.

For "non-simple" requests (e.g. `Content-Type: application/json`, or custom headers), the browser first sends a **preflight** `OPTIONS` request:

```
Browser preflight:
  OPTIONS /api/orders
  Origin: https://app.example.com
  Access-Control-Request-Method: POST
  Access-Control-Request-Headers: Content-Type, Authorization

Server response:
  Access-Control-Allow-Origin: https://app.example.com   # NOT "*" if credentials are used
  Access-Control-Allow-Methods: GET, POST, PUT, DELETE
  Access-Control-Allow-Headers: Content-Type, Authorization
  Access-Control-Allow-Credentials: true
  Access-Control-Max-Age: 600     # cache the preflight result for 10 min
```

Spring Boot example:

```java
@RestController
@CrossOrigin(origins = "https://app.example.com",
             allowedHeaders = {"Content-Type", "Authorization"},
             allowCredentials = "true")
public class OrderController {
    @PostMapping("/api/orders")
    public Order create(@RequestBody Order order) { /* ... */ }
}
```

Two interview gotchas: CORS is **enforced by the browser, not the server** (curl ignores it), and you **cannot** combine `Allow-Origin: *` with `Allow-Credentials: true` — you must echo the specific origin.

### Q26. [Theory] What are WebSockets and how do they differ from HTTP polling?

**WebSocket** (RFC 6455) is a full-duplex, persistent, bidirectional connection over a single TCP connection. It starts as an HTTP request with an **`Upgrade`** header, then "switches protocols" (status `101`) and abandons the request/response model for a lightweight message-framed channel.

```
Client                              Server
  | -- GET /ws  Upgrade: websocket -->  |
  | <-- 101 Switching Protocols ------  |
  | <========= persistent, both sides push messages anytime =========> |
```

Compared to alternatives:

```
Polling          client asks every N seconds → high latency + wasted requests
Long-polling     server holds request until data → better, but still req/resp churn
SSE              server→client only (one-way), over plain HTTP, auto-reconnect
WebSocket        full duplex, low overhead per message, ideal for chat/games/trading
```

Use WebSockets for genuinely interactive, low-latency, bidirectional needs (chat, multiplayer, live dashboards, collaborative editing). For server→client-only streams, **Server-Sent Events (SSE)** is simpler and works over standard HTTP/2.

### Q27. [Theory] What is gRPC and why does it run over HTTP/2?

**gRPC** is a high-performance RPC framework using **Protocol Buffers** (a compact binary IDL/serialization) for the contract and **HTTP/2** as the transport. It depends on HTTP/2 features:

- **Multiplexing** — many concurrent RPCs over one connection without HOL blocking.
- **Streaming** — HTTP/2's bidirectional streams map directly onto gRPC's four call types: **unary**, **server-streaming**, **client-streaming**, and **bidirectional streaming**.
- **Binary framing + header compression** — low overhead vs JSON-over-HTTP/1.1.

```
Client stub ──(protobuf over HTTP/2)──► Server impl
  unary:            req → resp
  server-stream:    req → resp, resp, resp...
  client-stream:    req, req, req... → resp
  bidi-stream:      req,req... <───> resp,resp...
```

gRPC excels for **internal service-to-service** communication (strong contracts, codegen, performance). Its weakness is the browser: native gRPC isn't directly callable from JS, requiring **gRPC-Web** with a proxy (Envoy) to translate. For public-facing APIs, REST/JSON or GraphQL often remain more accessible.

### Q28. [Theory] What is HTTP keep-alive, and why does it matter for performance?

**Keep-alive** (persistent connections) reuses one TCP (and TLS) connection for multiple HTTP request/response cycles instead of tearing it down after each. The savings are large because setup is expensive:

```
Without keep-alive (per request):  SYN/SYN-ACK/ACK  +  TLS handshake  +  request/response  +  FIN
With keep-alive:                   handshake ONCE, then many requests on the same socket
```

Each new HTTPS connection costs at least 2 round trips (TCP + TLS 1.3) before any data flows — on a 100ms-RTT link that's 200ms of pure overhead per request avoided. In HTTP/1.1 keep-alive is the default (`Connection: keep-alive`); `Connection: close` opts out. Servers cap idle keep-alive with a timeout to free resources. HTTP/2 and HTTP/3 take this further with one long-lived multiplexed connection per origin.

### Q29. [Practical] What is connection pooling and why is it essential for clients?

A **connection pool** maintains a set of reusable, already-established connections so callers borrow one, use it, and return it — avoiding the per-request handshake cost. It's used both for HTTP clients and, critically, for **database** connections (where setup also includes authentication and session initialization).

```
Request → [borrow conn from pool] → use → [return to pool] → reused by next request
                pool: [conn1][conn2][conn3] ... (idle, kept warm)
```

Java example with Apache HttpClient 5 connection pool:

```java
PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
cm.setMaxTotal(200);                 // total connections across all routes
cm.setDefaultMaxPerRoute(20);        // per target host

CloseableHttpClient client = HttpClients.custom()
    .setConnectionManager(cm)
    .evictIdleConnections(TimeValue.ofSeconds(30))  // reap stale connections
    .build();
```

For databases, **HikariCP** is the de-facto Java pool. Pool sizing is a classic trap: too small starves throughput, too large overwhelms the DB and increases contention. The well-known formula is roughly `connections ≈ (core_count * 2) + effective_spindle_count` for DB pools — usually much smaller than people expect (often 10–20, not hundreds).

### Q30. [Theory] What is the difference between a forward proxy and a reverse proxy?

Both sit between client and server and relay traffic, but they serve opposite sides.

```
Forward proxy (acts for the CLIENT):
   [clients] → [forward proxy] → internet
   Use: corporate egress filtering, caching, anonymity (VPN-like), bypassing geo-blocks.
   The server sees the proxy's IP, not the client's.

Reverse proxy (acts for the SERVER):
   internet → [reverse proxy] → [backend servers]
   Use: load balancing, TLS termination, caching, compression, WAF, hiding topology.
   The client sees the proxy, unaware of the backends behind it.
```

A **forward proxy** is configured by clients and protects/serves the client population. A **reverse proxy** (Nginx, HAProxy, Envoy) is deployed in front of your servers and is invisible to clients — it's the front door of most production architectures, doing TLS termination, routing, and load balancing.

---

## 🟠 Advanced (8–12 yrs)

### Q31. [Theory] Compare Layer 4 and Layer 7 load balancing. When do you choose each?

```
L4 (transport)                        L7 (application)
------------------------------------  ------------------------------------
Routes on IP + TCP/UDP port           Routes on HTTP content (path, host,
                                       header, cookie, method)
Doesn't read payload                   Parses the HTTP request
Faster, lower latency, less CPU        More CPU (terminates + inspects)
Can't do path-based routing            Path/host routing, A/B, canary
Often DSR/NAT mode                     Terminates TLS, can re-encrypt
Example: AWS NLB, IPVS                 Example: AWS ALB, Nginx, Envoy
```

**L4** load balancing forwards connections based purely on the 4-tuple — it can't tell `/api` from `/images`. It's extremely fast and protocol-agnostic (works for any TCP/UDP service, including databases and gRPC at the connection level). **L7** terminates the connection, parses HTTP, and can route `/api/*` to one fleet and `/static/*` to another, do header-based canary releases, rewrite paths, and apply a WAF.

Choose **L4** for raw throughput, non-HTTP protocols, or when you want end-to-end TLS without termination at the LB. Choose **L7** when you need content-aware routing, observability per route, or TLS offload. Many architectures layer them: an L4 LB in front spreading load across L7 proxies.

### Q32. [Theory] What load-balancing algorithms exist, and what are their trade-offs?

```
Round Robin            Even rotation; ignores server load/request cost.
Weighted Round Robin   Bigger servers get proportionally more traffic.
Least Connections      Sends to the server with fewest active conns —
                       good when request durations vary widely.
Least Response Time    Factors in latency; adaptive but needs probing.
IP Hash / Consistent   Maps client → server deterministically (stickiness,
  Hashing              cache locality); consistent hashing minimizes
                       reshuffling when servers join/leave.
Power of Two Choices   Pick 2 at random, send to the less loaded — near-
                       optimal with O(1) state, used in modern proxies.
```

Key trade-off: **stateless even distribution** (round robin) is simple but ignores that requests have wildly different costs. **Least-connections** handles heterogeneous request durations. **Consistent hashing** is essential when you need **session affinity** or **cache locality** (so the same key hits the same node) while minimizing churn on scale events — it's the backbone of distributed caches and sharded systems. **Power-of-two-choices** is a beautiful result: random sampling of just 2 backends gives almost the quality of full least-connections without the global state.

### Q33. [Theory] How does a CDN work, and what does it optimize?

A **CDN (Content Delivery Network)** is a globally distributed mesh of edge **PoPs (Points of Presence)** that cache and serve content close to users. It optimizes:

1. **Latency** — serve from an edge near the user instead of a distant origin. Lower RTT also speeds TCP/TLS handshakes and congestion-window ramp-up.
2. **Origin offload** — cached content (and even dynamic content via edge compute) never hits your origin, cutting load and cost.
3. **Availability & DDoS absorption** — the distributed capacity soaks up traffic spikes and attacks.

```
User → nearest edge PoP ─ cache hit? ─► serve from edge (fast)
                          │
                          └ miss ─► fetch from origin, cache by Cache-Control/TTL, then serve
```

Routing users to the nearest PoP uses **anycast** (same IP announced from many locations; BGP picks the closest) and/or **DNS-based geo-routing**. Modern CDNs also do **TLS termination**, **edge compute** (Cloudflare Workers, Lambda@Edge), image optimization, and serve as the **HTTP/3 front**. Cache correctness hinges on `Cache-Control`, `ETag`, `Vary`, and explicit **purge/invalidation** — invalidation being one of the genuinely hard problems.

### Q34. [Theory] Explain TLS termination vs TLS passthrough vs re-encryption at a load balancer.

```
Termination:   client --TLS--> [LB decrypts] --plaintext--> backend
   + offloads crypto from backends, enables L7 routing/WAF
   - traffic inside the perimeter is unencrypted (often acceptable in a VPC)

Passthrough:   client --TLS-----------------------------> backend (LB only forwards bytes)
   + true end-to-end encryption, backend owns the cert
   - LB can't inspect/route on content (L4 only), no offload

Re-encryption: client --TLS--> [LB decrypts, re-encrypts] --TLS--> backend
   + L7 features AND encrypted in transit to backend (zero-trust)
   - double crypto cost
```

**Termination** is the common default for internal architectures (crypto at the edge, fast plaintext internally). **Passthrough** suits strict end-to-end requirements (or when the backend must see the client cert for mTLS). **Re-encryption** is the **zero-trust** choice: you get L7 routing/observability *and* encryption all the way to the backend — increasingly standard in regulated and service-mesh environments (where sidecars handle mTLS).

### Q35. [Coding] Build a non-blocking TCP server in Java using NIO Selectors to handle many connections on one thread.

The blocking one-thread-per-connection model doesn't scale to tens of thousands of connections. NIO's **Selector** multiplexes many channels onto a single thread via the OS `epoll`/`kqueue`.

```java
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;

public class NioEchoServer {
    public static void main(String[] args) throws IOException {
        Selector selector = Selector.open();
        ServerSocketChannel server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress(9000));
        server.configureBlocking(false);
        server.register(selector, SelectionKey.OP_ACCEPT);

        ByteBuffer buf = ByteBuffer.allocate(1024);

        while (true) {
            selector.select();                                   // blocks until any channel is ready
            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
            while (keys.hasNext()) {
                SelectionKey key = keys.next();
                keys.remove();

                if (key.isAcceptable()) {
                    SocketChannel client = server.accept();
                    client.configureBlocking(false);
                    client.register(selector, SelectionKey.OP_READ);
                } else if (key.isReadable()) {
                    SocketChannel client = (SocketChannel) key.channel();
                    buf.clear();
                    int n = client.read(buf);
                    if (n == -1) {                               // client closed
                        client.close();
                    } else {
                        buf.flip();
                        client.write(buf);                       // echo back
                    }
                }
            }
        }
    }
}
```

This single thread can service thousands of sockets. In 2026, **virtual threads (Loom)** offer an alternative: write simple blocking code with `Thread.ofVirtual()`, and the JVM multiplexes millions of them onto a few carrier threads — often easier to maintain than raw NIO while retaining scalability. Frameworks like Netty still favor the selector/event-loop model for maximum control.

### Q36. [Practical] A client reports intermittent "connection reset" errors. How do you diagnose it?

Walk it methodically; "connection reset" means a **TCP RST** was received — the peer (or a middlebox) abruptly aborted.

1. **Reproduce & scope** — one client or all? One endpoint? Correlate timing with deploys, traffic spikes, or scaling events.
2. **Inspect the layers**:
   - **Idle timeouts** — an LB or NAT silently drops idle connections; the next write gets an RST. Mismatched keep-alive timeouts between client pool and server/LB is the #1 cause. Fix: client idle timeout < server's.
   - **Connection pool reuse** — pooled connections gone stale server-side; enable **validate-on-borrow** / `evictIdleConnections`.
   - **Backlog/overload** — server's accept queue full or it's `RST`-ing under load; check `ss -s`, accept backlog, thread pool saturation.
3. **Capture packets** — `tcpdump`/Wireshark on both ends to see *who* sends the RST and after what. A RST right after a long idle gap points at a timeout; a RST under load points at backlog/resource exhaustion.
4. **Check resource limits** — ephemeral port exhaustion (too many TIME_WAIT), file-descriptor limits (`ulimit -n`), conntrack table full on NAT.
5. **Tooling** — `netstat`/`ss` for socket states, `dmesg` for `nf_conntrack: table full`, LB access logs for upstream resets.

The frequent root cause in microservices: an HTTP client pool holds connections longer than the upstream LB's idle timeout, so reused connections are dead on arrival. The fix is aligning timeouts and validating connections before reuse.

### Q37. [Theory] What is the bandwidth-delay product, and why does it matter for throughput tuning?

The **bandwidth-delay product (BDP)** is the amount of data "in flight" to fully utilize a link:

```
BDP (bits) = bandwidth (bits/sec) × RTT (sec)

Example: 1 Gbps link, 100 ms RTT
  BDP = 1e9 × 0.1 = 1e8 bits = 12.5 MB
```

To keep a pipe full, the sender must have at least **BDP** unacknowledged bytes in flight — meaning the TCP **window** (and socket buffers) must be ≥ BDP. The default 16-bit window maxes at 64 KB, far below 12.5 MB, so **window scaling** (RFC 7323) is mandatory on high-BDP "long fat networks." If your buffers are too small, throughput is capped at `window / RTT` regardless of bandwidth — a common reason a "10 Gbps" cross-continent transfer crawls. The fix is enlarging socket send/receive buffers (`SO_SNDBUF`/`SO_RCVBUF`) or relying on OS auto-tuning.

### Q38. [Theory] How does anycast routing work and where is it used?

**Anycast** announces the *same* IP address from *multiple* physically distinct locations via **BGP**; the internet's routing naturally delivers each client's packets to the **topologically nearest** instance.

```
       announce 1.1.1.1 from many PoPs
  user(EU) ─► BGP picks ─► London PoP
  user(US) ─► BGP picks ─► Virginia PoP
  user(APAC) ─► BGP picks ─► Singapore PoP
```

Used heavily for:

- **DNS** — root servers and resolvers like `8.8.8.8`/`1.1.1.1` are anycast for low latency and resilience.
- **CDNs** — route users to the nearest edge.
- **DDoS mitigation** — attack traffic is spread across many PoPs rather than concentrated.

Trade-off vs unicast: anycast gives automatic geographic load distribution and failover (withdraw a route and traffic reroutes), but it's connectionless-friendly. For long-lived TCP, a BGP reconvergence could shift a flow mid-connection to a different node — usually rare, and stateful services pin sessions or use it mainly for the initial routing.

### Q39. [Theory] What is mutual TLS (mTLS), and where does it fit in a zero-trust architecture?

Standard TLS authenticates only the **server** to the client. **mTLS** adds **client authentication** — the client also presents an X.509 certificate, and the server validates it. Both ends prove identity cryptographically.

```
Client                          Server
  | --- ClientHello ----------->  |
  | <-- ServerHello, Cert,        |
  |     CertificateRequest -----  |   server ASKS for client cert
  | --- Client Cert, CertVerify > |   client proves its identity
  |     ...mutual auth complete...|
```

In **zero-trust** networks ("never trust, always verify, even inside the perimeter"), mTLS is the standard for **service-to-service** authentication. A **service mesh** (Istio, Linkerd) automates this: sidecar proxies transparently establish mTLS between every pod, rotate short-lived certs (SPIFFE/SPIRE identities), and enforce policy — so application code stays oblivious. This replaces the old "soft interior" model where anything inside the VPC was trusted.

### Q40. [Behavioral] Describe a time you debugged a difficult production networking issue. How did you approach it?

(Use the **STAR** structure — Situation, Task, Action, Result.) A strong answer demonstrates **layered, evidence-driven** diagnosis rather than guessing.

- **Situation**: e.g. "Checkout latency spiked to multi-second P99 for ~5% of requests after a region failover, but only intermittently."
- **Task**: "Restore latency without a full rollback, and find the root cause."
- **Action**: "I worked the stack bottom-up. Metrics showed retries climbing, not CPU. `tcpdump` on a sample host revealed RSTs on reused pooled connections. I correlated the LB idle timeout (60s) against our HTTP client's pool TTL (no eviction) — stale connections were being borrowed and immediately reset. I lowered the client idle timeout below the LB's and enabled validate-on-borrow, then rolled out to one canary instance first."
- **Result**: "P99 dropped back to baseline within an hour; I added an alert on upstream-reset rate and documented the timeout-alignment rule so other services wouldn't repeat it."

The interviewer is listening for: forming hypotheses tied to specific layers, using packet/metric **evidence** over hunches, minimizing blast radius (canary), and turning the fix into prevention (alerting + documentation).

### Q41. [Theory] Explain DNS-based service discovery and load balancing. What are its limitations?

DNS can do basic load balancing by returning **multiple A records** for one name; clients pick one (often the first, or randomly). It's also the basis of service discovery in platforms like Kubernetes (`my-svc.namespace.svc.cluster.local`).

Limitations to call out in an interview:

- **Caching/TTL lag** — clients, OS, and resolvers cache results; lowering TTL to react fast increases query load and *still* isn't instant. Removing a dead host from rotation propagates slowly.
- **No health awareness** — plain DNS round-robin returns dead hosts until manually/automatically pulled.
- **Uneven distribution** — clients that cache aggressively or only use the first record skew load.
- **Stale clients ignore TTL** — some libraries cache forever (notably the JVM's default `networkaddress.cache.ttl`, which you must tune).

Mitigations: short TTLs with **health-checked** DNS (Route 53 health checks), **weighted/latency/geo** routing policies, or pushing load balancing into a dedicated LB / service mesh / client-side LB (gRPC, Ribbon-style) rather than relying on DNS alone.

### Q42. [Practical] How do you configure timeouts and retries for resilient HTTP clients? Show the layered timeouts.

Naively retrying or using one global timeout causes cascading failures and retry storms. You need **distinct, layered** timeouts:

```
connect timeout    — time to establish TCP/TLS                 (e.g. 1–3s)
write/send timeout — time to send the request                  (e.g. 2s)
read/response t.o. — time waiting for the response             (e.g. 5–10s)
idle/keep-alive    — how long to keep an unused pooled conn    (< upstream LB idle)
overall/request    — total deadline including retries          (budget-based)
```

```java
HttpClient client = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(2))      // connect phase only
    .build();

HttpRequest req = HttpRequest.newBuilder()
    .uri(URI.create("https://api.example.com/orders"))
    .timeout(Duration.ofSeconds(5))             // whole request deadline
    .build();
```

Pair these with: **retries only on idempotent/safe operations** or with **idempotency keys**; **exponential backoff + jitter** to avoid synchronized retry storms; a **circuit breaker** (Resilience4j) to stop hammering a failing dependency; and a **deadline/timeout budget** propagated across hops so a downstream call can't exceed the caller's remaining time. The cardinal sin is an infinite or very long read timeout — it lets a single slow dependency exhaust your thread pool and take the whole service down.

### Q43. [Theory] What causes "TIME_WAIT" socket accumulation, and how do you mitigate it at scale?

When a socket actively closes, it enters **TIME_WAIT** for 2×MSL (often ~60s) to (a) ensure the final ACK is delivered and (b) prevent stray duplicate segments from a closed connection corrupting a new one reusing the same 4-tuple. Under high connection **churn**, thousands of TIME_WAIT sockets pile up:

```
Symptom: cannot allocate ephemeral ports → "address already in use" / connect failures
Cause:   short-lived connections opened/closed rapidly (e.g. no keep-alive)
```

Mitigations (roughly in order of preference):

1. **Use keep-alive / connection pooling** — the real fix is fewer connections, reused. This eliminates most churn.
2. **Make the server the active closer** where appropriate, so TIME_WAIT lands on the side with more headroom (clients), not a single busy server.
3. **OS tuning** — `net.ipv4.tcp_tw_reuse=1` (safely reuse TIME_WAIT for new outbound connections), widen the ephemeral port range, increase `somaxconn`. Avoid the old `tcp_tw_recycle` (removed; broke NAT).
4. **More source tuples** — multiple source IPs or destination ports widen the 4-tuple space.

The interview signal is recognizing TIME_WAIT as *correct, protective behavior* and that the right response is reducing connection churn, not blindly disabling the protection.

---

## 🔴 Expert (15+ yrs)

### Q44. [Theory] Discuss protocol ossification and how QUIC was designed to resist it.

**Ossification** is the inability to evolve a protocol because middleboxes (NATs, firewalls, "transparent" proxies, DPI appliances) inspect and depend on specific wire details, so any deviation gets dropped. TCP became deeply ossified: new TCP options are often stripped, ECN was mishandled, and **TCP Fast Open** struggled to deploy because middleboxes reject unfamiliar SYN payloads. This is why useful TCP improvements stalled for years.

**QUIC's** design is partly a reaction to this:

- **Encrypt almost everything**, including most of the transport header — middleboxes *can't* inspect or depend on internals they can't read, so they can't ossify them. Only a minimal invariant header is exposed.
- **Run in user space over UDP** — the protocol lives in the application/library, so it can be updated by shipping a new client/server binary rather than waiting on OS kernel and middlebox upgrades.
- **Greasing (GREASE)** — deliberately send reserved/varying values so implementations are forced to tolerate the unknown, preventing future ossification of extension points.

The deeper lesson: protocols must be designed with an **evolvability** mindset — encrypt what you don't want others depending on, define clear invariants, and exercise extension points from day one. This is a recurring theme (TLS 1.3 GREASE, HTTP/2 settings) that staff-level engineers should articulate.

### Q45. [Theory] How would you design the network architecture for a global, low-latency, multi-region service?

Reason through it as **layers of the path**, optimizing each:

1. **DNS / entry routing** — anycast + geo/latency-based DNS (Route 53 latency routing) to send users to the nearest healthy region; health checks for automatic failover.
2. **Edge / CDN** — terminate **TLS 1.3 + HTTP/3** at edge PoPs near users; cache static and cacheable dynamic content; use **0-RTT** resumption and edge compute for personalization. The edge also fronts DDoS protection and a WAF.
3. **Edge-to-origin transport** — keep persistent, pre-warmed connections from edge to origin over the provider's private backbone (not the public internet) to skip repeated handshakes and avoid congested transit. This is the single biggest dynamic-content latency win.
4. **Regional load balancing** — L4 NLB front for raw throughput → L7 (Envoy/ALB) for content routing, canaries, and observability; consistent hashing where cache/session locality matters.
5. **Service mesh** — mTLS everywhere (zero trust), retries with budgets, circuit breaking, locality-aware routing (prefer same-zone to cut cross-AZ latency and cost).
6. **Data layer** — the hard part: read replicas per region, async geo-replication, and an explicit **consistency** choice (most reads regional/eventually-consistent, writes routed to a primary region or via a globally consistent store like Spanner). Network design can't hide the speed of light, so the data model must embrace it.
7. **Resilience** — graceful degradation, regional isolation (bulkheads) so one region's failure doesn't cascade, and **chaos/failover testing** as a routine.

The framing that impresses: you can't beat physics (cross-continent RTT is ~100–150ms), so the strategy is **terminate close, cache aggressively, keep connections warm, replicate data regionally, and make consistency trade-offs explicit** — and quantify each decision against an RTT/latency budget.

### Q46. [Behavioral] You disagree with a team's decision to use WebSockets for a feature you believe should use HTTP/SSE. How do you handle it?

This probes **technical leadership and influence**, not just protocol knowledge. A strong answer:

- **Lead with the requirement, not the protocol.** "I'd first align on the actual need: is communication truly bidirectional and low-latency, or is it server→client streaming? WebSockets shine for the former; for one-way streams, SSE over HTTP/2 is simpler — it auto-reconnects, traverses proxies/CDNs cleanly, and avoids the operational weight of long-lived stateful WS connections (sticky sessions, scaling, load-balancer support)."
- **Bring data, not just opinion.** "I'd quantify trade-offs: connection count and memory at our scale, CDN/proxy compatibility, reconnection complexity, and team familiarity — maybe a quick spike or prototype to compare."
- **Disagree-and-commit.** "If, after weighing it, the team still chooses WebSockets for good reasons (e.g. a near-term bidirectional roadmap), I commit fully and help make it robust — heartbeats, backpressure, scaling plan — rather than relitigating."
- **Make it reversible.** "I'd push for an abstraction at the boundary so we can switch transports later with minimal blast radius, and document the decision (an ADR) with the conditions that would change it."

The interviewer wants to see: requirements-first reasoning, evidence over ego, respect for the team's autonomy, and turning a disagreement into a durable, documented decision.

### Q47. [Theory] Explain BGP, route propagation, and why BGP misconfigurations cause large internet outages.

**BGP (Border Gateway Protocol)** is the routing protocol of the internet's backbone — it exchanges **reachability** information between **autonomous systems (ASes)**, each AS announcing which IP prefixes it can reach and selecting paths based on policy (not purely shortest path).

```
AS announces: "prefix 203.0.113.0/24 is reachable via me (AS path: 64500)"
Neighbors propagate, prepending their AS → routes converge across the internet
```

Why misconfigurations are catastrophic:

- **Implicit trust** — classic BGP largely trusts announcements. A wrong or malicious announcement of someone else's prefix (**BGP hijack**) can blackhole or intercept their traffic globally (the 2008 Pakistan/YouTube and 2021 Facebook outages are canonical).
- **More-specific wins** — a more-specific prefix (`/25` vs `/24`) is preferred, so a leaked specific route overrides the legitimate one.
- **Global propagation** — a bad announcement spreads worldwide in minutes; withdrawal must also propagate to recover.
- **Self-inflicted disconnection** — Facebook's 2021 outage withdrew the routes to its own DNS/authoritative servers, making the network unreachable *and* breaking the tools needed to fix it.

Mitigations maturing by 2026: **RPKI** (Resource Public Key Infrastructure) to cryptographically validate origin ASes, **ROAs**, route filtering, and **MANRS** best practices. The staff-level insight: the internet's core routing is built on trust and policy, making it fragile to both fat-fingers and attacks — defense-in-depth (RPKI + filtering + monitoring + careful change control) is essential, and your own service's resilience plan must assume upstream BGP/DNS can fail.

### Q48. [Theory] Compare congestion-control algorithms (Reno/CUBIC/BBR) and explain bufferbloat.

```
Reno/NewReno  Loss-based AIMD. Halves cwnd on loss. Simple, fair, but
              underperforms on high-BDP links and treats any loss as congestion.
CUBIC         Loss-based; cwnd grows as a cubic of time since last loss.
              Aggressive ramp after a loss → great for high-speed nets.
              Linux default. Still fills buffers (loss-driven).
BBR           Model-based: estimates bottleneck Bandwidth + min RTT and paces
              to that, largely ignoring loss as a signal. Higher throughput on
              lossy/wireless links, lower latency (avoids filling buffers).
```

**Bufferbloat** is excessive latency caused by oversized buffers in routers/middleboxes: loss-based algorithms (Reno/CUBIC) keep increasing the send rate **until a buffer overflows and drops a packet** — but a huge buffer means megabytes of queued data and hundreds of ms of standing queue delay *before* any loss occurs. So throughput looks fine while latency (and jitter) is terrible, ruining interactive traffic (gaming, video calls) sharing the link.

Two complementary fixes:

- **Smarter queuing (AQM)** at the bottleneck — **CoDel**/**FQ-CoDel**/**CAKE** drop or mark early to keep queues short, and fair-queue flows so one bulk transfer can't starve interactive ones.
- **Model-based sender control** — **BBR** paces to the estimated bandwidth and keeps the queue near-empty, sidestepping the "fill until loss" trap entirely.

The nuance worth raising: BBR's early versions could be unfair to CUBIC flows and starve them; **BBRv2/v3** address fairness and add ECN responsiveness. The general principle — *don't equate loss with congestion, and don't let buffers grow unbounded* — is the modern view of healthy network behavior.

### Q49. [Theory] How do 0-RTT (TLS 1.3 / QUIC) connection resumptions work, and what are the security risks?

After a prior handshake, the server can issue a **session ticket / PSK**. On reconnection, the client can send **application data in the very first flight** (0-RTT) encrypted with a key derived from that PSK — saving a full round trip, which is significant on high-latency mobile links.

```
1-RTT new:   ClientHello → ServerHello/... → [data]        (1 round trip first)
0-RTT resume: ClientHello + PSK + EARLY DATA  ──► server can act immediately
```

The security catch: **0-RTT early data is replayable.** Because it's sent before the handshake completes (no fresh server nonce exchanged yet), a network attacker can capture and **replay** the early-data flight, and the server can't intrinsically tell the difference. Risks and mitigations:

- **Only allow 0-RTT for idempotent, safe requests** (e.g. GET) — never for state-changing operations like "transfer money." Servers/CDNs typically restrict early data to GET/HEAD.
- **Application-layer replay defenses** — single-use tokens, idempotency keys, anti-replay windows / strike registers (bounded by ticket lifetime).
- **No forward secrecy for the 0-RTT portion** by itself; the rest of the connection regains it.

The staff-level point: 0-RTT trades a measurable latency win for a real security weakening, so it must be **opt-in, scoped to safe operations, and paired with application-level replay protection** — a textbook example of an explicit latency-vs-security trade-off you should be able to defend in a design review.

### Q50. [Theory] How do eBPF and modern kernel-bypass techniques change network performance engineering?

At very high packet rates, the traditional kernel network stack (per-packet interrupts, context switches, socket-buffer copies) becomes the bottleneck. Two families of techniques address this:

- **eBPF / XDP** — run sandboxed programs **inside the kernel** at the earliest point (the driver, via **XDP**) to filter, redirect, load-balance, or observe packets without copying them to user space. This powers Cilium (Kubernetes networking + L3–L7 policy + service load balancing), Katran (Facebook's L4 LB), and rich observability (per-flow metrics, tracing) with minimal overhead. It's programmable, safe (verifier-checked), and avoids the cost of leaving the kernel.

- **Kernel bypass (DPDK, AF_XDP, user-space stacks)** — move packet processing **entirely to user space**, polling NICs directly (busy-poll instead of interrupts), using huge pages and lockless ring buffers to hit tens of millions of packets per second. Used in NFV, high-frequency trading, and software routers/firewalls.

```
Traditional:  NIC → IRQ → kernel stack → copy → socket → app   (flexible, slower)
XDP/eBPF:     NIC → eBPF at driver → drop/redirect/LB           (in-kernel, fast)
DPDK bypass:  NIC ─poll─► user-space app (no kernel)            (fastest, CPU-bound)
```

The trade-offs a principal engineer should articulate: kernel bypass gives raw speed but sacrifices the kernel's features (TCP stack, security, fairness) and burns CPU cores on polling; eBPF/XDP is the sweet spot for most cloud-native needs — programmable, observable, and fast while staying in the kernel's safety model. In 2026, eBPF has become the default substrate for cloud-native networking, security enforcement, and observability — knowing where it fits (and its verifier/complexity limits) is increasingly expected at senior levels.

---

## 🧩 Extended Questions — Set 1: Deeper theory & internals

### 🟢 — extended

#### Q51. [Theory] What actually happens, byte by byte, when you type a URL and press Enter?

The end-to-end chain touches almost every layer, and interviewers love it because it lets you connect concepts:

1. **URL parsing** — the browser splits scheme/host/port/path. If it's a known HSTS host, `http://` is rewritten to `https://` *before* any network call.
2. **DNS resolution** — check the in-memory and OS caches; on a miss, the stub resolver queries the configured recursive resolver, which walks root → TLD → authoritative (see Q7). Result cached by TTL.
3. **TCP connection** — a 3-way handshake to the resolved IP on port 443 (or QUIC's 1-RTT over UDP for HTTP/3).
4. **TLS handshake** — ClientHello/ServerHello, certificate validation against the trust store, key derivation (1-RTT in TLS 1.3). ALPN negotiates `h2`/`h3`/`http/1.1` *inside* this handshake.
5. **HTTP request** — the browser sends `GET / HTTP/2` with headers (Host, cookies, Accept, User-Agent).
6. **Server processing** — possibly through a CDN edge, reverse proxy, load balancer, then the app server.
7. **Response + render** — status, headers, body; the browser parses HTML, discovers subresources, and repeats steps 2–6 for each (reusing the multiplexed connection in HTTP/2/3).

The signal you're sending: you understand caching at every level, that TLS rides on TCP which rides on IP, and that one page load is dozens of these flows.

#### Q52. [Theory] What is MTU, and what is fragmentation? Why is Path MTU Discovery important?

The **MTU (Maximum Transmission Unit)** is the largest L3 packet a link can carry in one frame — classically **1500 bytes** for Ethernet. If an IP packet exceeds the MTU of a link along the path, it must be **fragmented** into smaller pieces and reassembled at the destination.

Fragmentation is undesirable: it adds overhead, and losing *one* fragment forces retransmission of the *whole* original packet. In IPv4 a router can fragment in transit; in **IPv6 routers never fragment** — only the source may, guided by Path MTU Discovery.

**Path MTU Discovery (PMTUD)** finds the smallest MTU along the entire path: the sender sets the **DF (Don't Fragment)** bit; if a router can't forward without fragmenting, it returns an **ICMP "Fragmentation Needed"** message stating the next-hop MTU, and the sender shrinks its packet size. The classic production failure: a firewall blocks ICMP, so the sender never learns to shrink — connections hang on large transfers ("PMTUD black hole"). Mitigation is **MSS clamping** at the edge (capping TCP's advertised MSS so segments never exceed the path MTU). This is also why VPN/tunnel overhead (which reduces effective MTU) so often breaks things.

#### Q53. [Theory] What is the TCP MSS, and how does it relate to MTU and the window?

The **MSS (Maximum Segment Size)** is the largest amount of **application payload** TCP will put in one segment, *excluding* IP and TCP headers. It's derived from the MTU:

```
MSS = MTU − IP header − TCP header
    = 1500 − 20 − 20 = 1460 bytes   (typical IPv4, no options)
```

Each side advertises its MSS in the **SYN** (it is a SYN-only option), and the effective MSS for the connection is the **smaller** of the two — so a host can't be sent segments larger than it asked for. MSS is about a *single segment's* size; the **window** (rwnd/cwnd) is about *how many bytes total* may be in flight unacknowledged. A useful mental model: MSS is the size of each truck, the window is how many trucks you can have on the highway at once. Getting MSS wrong (e.g. over a tunnel) causes fragmentation or black-holing (Q52); getting the window wrong caps throughput (Q37).

#### Q54. [Practical] Show how to parse the structure of a TCP segment header conceptually in Java.

You rarely parse raw TCP in app code (the kernel does it), but understanding the header fields is fair game. Here's a read-only decoder over a `ByteBuffer` holding a TCP header, which forces you to name every field:

```java
import java.nio.ByteBuffer;

public class TcpHeader {
    final int srcPort, dstPort;
    final long seq, ack;          // unsigned 32-bit → hold in long
    final int dataOffsetWords;    // header length in 32-bit words
    final boolean syn, ackFlag, fin, rst, psh, urg;
    final int window;

    TcpHeader(ByteBuffer b) {
        srcPort = Short.toUnsignedInt(b.getShort());      // bytes 0-1
        dstPort = Short.toUnsignedInt(b.getShort());      // bytes 2-3
        seq     = Integer.toUnsignedLong(b.getInt());     // bytes 4-7
        ack     = Integer.toUnsignedLong(b.getInt());     // bytes 8-11
        int offAndFlags = Short.toUnsignedInt(b.getShort()); // bytes 12-13
        dataOffsetWords = (offAndFlags >> 12) & 0xF;      // top 4 bits
        urg = (offAndFlags & 0x20) != 0;
        ackFlag = (offAndFlags & 0x10) != 0;
        psh = (offAndFlags & 0x08) != 0;
        rst = (offAndFlags & 0x04) != 0;
        syn = (offAndFlags & 0x02) != 0;
        fin = (offAndFlags & 0x01) != 0;
        window = Short.toUnsignedInt(b.getShort());       // bytes 14-15
        // bytes 16-17 checksum, 18-19 urgent ptr, then options...
    }

    int headerBytes() { return dataOffsetWords * 4; }     // 20 if no options
}
```

The key teaching points: ports are 16-bit, seq/ack are 32-bit *unsigned* (Java has no unsigned int, hence `Integer.toUnsignedLong`), the **data offset** tells you where options end and payload begins, and the flag bits (SYN/ACK/FIN/RST/PSH/URG) drive the state machine.

#### Q55. [Theory] What is the difference between a packet, a segment, a frame, and a datagram?

These are the **Protocol Data Units (PDUs)** at each layer — interviewers use them precisely and expect you to as well:

```
Layer            PDU name        Adds
--------------   -------------   -----------------------------------
Application      message/data    application headers (HTTP, etc.)
Transport (TCP)  segment         ports, seq/ack  → reliable stream
Transport (UDP)  datagram        ports, length, checksum (no reliability)
Network (IP)     packet          src/dst IP, TTL → routing
Data link        frame           MAC addresses, FCS → local delivery
```

So a single HTTP response is a *message*, carried inside one or more TCP *segments*, each wrapped in an IP *packet*, each transmitted as an Ethernet *frame*. "Datagram" specifically connotes a self-contained, best-effort unit — that's why UDP units and IP units are both called datagrams, whereas TCP's are "segments" because they're pieces of a continuous stream. Using "packet" for everything is common in casual speech but a tell that you haven't internalized the layering.

#### Q56. [Theory] Why does TCP need sequence numbers and ACKs, and how are initial sequence numbers chosen?

TCP delivers a **reliable, ordered byte stream over an unreliable packet network**, and sequence numbers are how it does it. Every byte is numbered; a segment's sequence number is the number of its first byte. The receiver's **ACK** carries the next byte it expects (cumulative acknowledgment), which simultaneously confirms receipt and tells the sender where to resume after a loss.

Sequence numbers also enable:

- **Reordering** — out-of-order segments are buffered and reassembled by sequence number.
- **Duplicate detection** — a retransmitted or stray duplicate is recognized and discarded.

The **Initial Sequence Number (ISN)** is deliberately **not** zero. Per RFC 6528 it's generated from a clock-based counter mixed with a cryptographic hash of the 4-tuple and a secret. The reasons: (a) avoid collision with **old duplicate segments** from a previous connection on the same 4-tuple (the original TIME_WAIT motivation), and (b) make ISNs **unpredictable** to defend against off-path **TCP spoofing/injection** attacks, where an attacker who can guess the next sequence number could inject data or forge an RST.

### 🟡 — extended

#### Q57. [Theory] Explain Nagle's algorithm and delayed ACKs, and why their interaction can hurt latency.

Both are bandwidth-saving optimizations that can interact badly to *add* latency.

- **Nagle's algorithm** (`TCP_NODELAY` disables it) reduces tiny "tinygram" packets: if there is **unacknowledged** data in flight, the sender **buffers** small writes until either a full MSS accumulates or an ACK arrives. Great for chatty terminal traffic; bad for request/response protocols that send a small request and wait.
- **Delayed ACKs** let the receiver wait up to ~200ms before ACKing, hoping to **piggyback** the ACK on a response or batch multiple ACKs.

The pathological interaction: the sender (Nagle) won't send the last small chunk until it gets an ACK; the receiver (delayed ACK) won't send the ACK immediately, hoping for more data. Each waits for the other → a **~40–200ms stall** per exchange. This is a classic, baffling latency bug in RPC and database drivers.

The fix in almost all latency-sensitive request/response code is to **set `TCP_NODELAY`** (disable Nagle). In Java:

```java
Socket socket = new Socket();
socket.setTcpNoDelay(true);   // disable Nagle — send small writes immediately
```

Most modern frameworks (Netty, gRPC, HTTP clients) enable `TCP_NODELAY` by default for exactly this reason.

#### Q58. [Theory] What is selective acknowledgment (SACK), and why is it better than cumulative ACKs alone?

Plain TCP uses **cumulative ACKs**: an ACK of *N* means "I have everything up to byte *N*." The problem: if segments 1, 3, 4, 5 arrive but 2 is lost, the receiver can only keep ACKing "I still want 2" — it **can't tell the sender it already has 3, 4, 5**. The sender may needlessly retransmit them ("go-back-N" style waste).

**SACK (Selective Acknowledgment, RFC 2018)** is a TCP option where the receiver explicitly reports the **non-contiguous blocks** it has received:

```
Cumulative ACK: "give me byte 2"           (sender may resend 2,3,4,5)
With SACK:      "give me 2; I already have   (sender resends ONLY 2)
                 blocks [3–5]"
```

This lets the sender retransmit **only** the truly missing segment(s), dramatically improving recovery on links with multiple losses per window (wireless, high-BDP). **D-SACK (Duplicate SACK)** extends it to report duplicate segments received, helping the sender detect spurious retransmissions and unnecessary window reductions. SACK is negotiated in the SYN and is on by default in all modern stacks.

#### Q59. [Theory] Explain how a TLS certificate chain is validated, including the role of intermediates and the root store.

A server presents not just its **leaf** certificate but a **chain** up toward (but not including) a root. Validation walks the chain:

```
Leaf (example.com)  ──signed by──►  Intermediate CA  ──signed by──►  Root CA
   (server sends leaf + intermediates)                 (in OS/browser trust store)
```

Steps the client performs:

1. **Build the chain** from the leaf to a trusted root, verifying each certificate's **signature** was made by the next one's private key.
2. **Anchor in the trust store** — the chain must terminate at a **root** preinstalled in the OS/browser trust store. Roots are kept offline; **intermediates** do the day-to-day signing so a compromised intermediate can be revoked without re-rolling the root.
3. **Validity checks** — not expired/not-yet-valid, the requested hostname matches a **Subject Alternative Name (SAN)** (the CN is ignored by modern clients), correct key usage/EKU, and basic constraints (is this CA allowed to sign?).
4. **Revocation** — check **OCSP** (often **stapled** by the server to avoid a client round trip and privacy leak) or CRLs; some clients use pushed revocation lists (CRLite).

Common failures: the server forgets to send the **intermediate** (works in browsers that cache it, fails in stricter clients like Java/curl — a notorious "works on my machine" bug), an expired cert, or a hostname/SAN mismatch. In 2026, certificate lifetimes are short (driven toward ~47 days / automated ACME renewal) and **Certificate Transparency** logs are mandatory, so misissuance is publicly auditable.

#### Q60. [Coding] Implement consistent hashing with virtual nodes in Java.

Consistent hashing (Q32) minimizes key remapping when nodes join/leave. **Virtual nodes** (replicas) smooth out the otherwise lumpy distribution. A `TreeMap` gives the "find the next node clockwise on the ring" lookup in O(log n):

```java
import java.util.*;

public class ConsistentHashRing {
    private final TreeMap<Long, String> ring = new TreeMap<>();
    private final int vnodes;                       // replicas per physical node

    public ConsistentHashRing(int vnodes) { this.vnodes = vnodes; }

    public void addNode(String node) {
        for (int i = 0; i < vnodes; i++) {
            ring.put(hash(node + "#" + i), node);   // place vnodes around the ring
        }
    }

    public void removeNode(String node) {
        for (int i = 0; i < vnodes; i++) {
            ring.remove(hash(node + "#" + i));
        }
    }

    /** Find the node owning this key: first vnode clockwise from the key's hash. */
    public String getNode(String key) {
        if (ring.isEmpty()) return null;
        long h = hash(key);
        Map.Entry<Long, String> e = ring.ceilingEntry(h);
        return (e != null ? e : ring.firstEntry()).getValue();  // wrap around
    }

    /** 64-bit FNV-1a — cheap, decent distribution. Use a real hash in production. */
    private long hash(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    public static void main(String[] args) {
        ConsistentHashRing r = new ConsistentHashRing(150);
        r.addNode("nodeA"); r.addNode("nodeB"); r.addNode("nodeC");
        System.out.println("user42  -> " + r.getNode("user42"));
        r.removeNode("nodeB");                       // only keys on B move
        System.out.println("user42  -> " + r.getNode("user42"));
    }
}
```

The win: removing `nodeB` only remaps the keys that were on B (≈1/N of keys), not the whole space as a `hash % N` scheme would. More vnodes → smoother balance and gentler movement on scale events, at the cost of more ring entries. This is the algorithm behind Dynamo, Cassandra, Memcached client sharding, and many L7 proxies' sticky routing.

#### Q61. [Theory] How does HPACK header compression work, and what is the security concern behind it?

**HPACK (RFC 7541)** compresses HTTP/2 headers, which are highly repetitive (the same cookies, user-agent, and accept headers ride every request). It uses three mechanisms:

1. **Static table** — a fixed table of ~61 common header name/value pairs (`:method: GET`, `:status: 200`, etc.) referenced by index.
2. **Dynamic table** — a per-connection table that grows as headers are seen, so a repeated header becomes a tiny index reference on subsequent requests.
3. **Huffman coding** — literal values that must be sent are Huffman-encoded for further shrinkage.

```
First request:  cookie: session=abc...  → sent literally + added to dynamic table
Later requests: cookie: session=abc...  → just an index into the dynamic table
```

The security concern is the reason **TLS-level compression (CRIME) and HTTP-body compression (BREACH)** are dangerous: if attacker-controlled input and a secret share a compression context, the **compressed length** leaks information about the secret (a guess that matches the secret compresses better). HPACK was deliberately designed to resist this — it **never compresses across the security boundary in a way that mixes attacker input with secrets in the static/Huffman layer**, and sensitive headers can be marked "never indexed." HTTP/3 uses **QPACK**, a variant that decouples header compression from QUIC's out-of-order stream delivery (a naive dynamic table would create head-of-line blocking that QUIC was built to avoid).

#### Q62. [Theory] Walk through the QUIC connection establishment and the role of Connection IDs.

QUIC merges transport and crypto setup, so a brand-new connection completes in **1-RTT** and a resumed one can be **0-RTT**:

```
Client                                   Server
  | -- Initial (ClientHello, key share) -->  |
  | <-- Initial (ServerHello) + Handshake    |   crypto + transport params together
  |     (cert, Finished) ----------------     |
  | -- Handshake (Finished) -------------->   |
  | == 1-RTT protected application data ==>   |
```

The pivotal innovation is the **Connection ID (CID)**: a QUIC connection is identified not by the 4-tuple but by CIDs that each side chooses for the *other* to use. This enables:

- **Connection migration** — when a client's IP/port changes (Wi-Fi → cellular, NAT rebinding), the CID stays constant, so the connection survives without a new handshake. The server validates the new path to prevent address-spoofing amplification.
- **Load-balancer routing** — a stateless LB can route by CID to the correct server even as the client's address changes (CIDs can encode routing info).
- **Privacy** — CIDs are rotated to make it harder to link a user's connection across network changes.

QUIC also defends against **amplification attacks** (an attacker spoofing a victim's address to make the server flood it) by limiting how much a server sends to an unvalidated address to **3× what it received**, and via **Retry** tokens for address validation. These are exactly the protections TCP got piecemeal (SYN cookies) but QUIC bakes in.

#### Q63. [Practical] How do you debug a slow HTTPS request layer by layer? Name the tools and what each isolates.

Decompose the request into phases and attribute the time to one of them — guessing wastes hours:

```
DNS lookup → TCP connect → TLS handshake → request sent → TTFB → content download
```

- **`curl -w` timing** is the fastest first cut — it prints exactly these phases:
  ```
  curl -w "dns:%{time_namelookup} conn:%{time_connect} \
  tls:%{time_appconnect} ttfb:%{time_starttransfer} total:%{time_total}\n" \
  -o /dev/null -s https://api.example.com/orders
  ```
  A high `time_namelookup` → DNS problem; high `time_appconnect − time_connect` → TLS/cert/OCSP; high `time_starttransfer − time_appconnect` → server processing; large `total − time_starttransfer` → slow body/bandwidth.
- **`dig` / `nslookup`** — isolate DNS resolution time and which resolver/records answer.
- **`tcpdump` / Wireshark** — see retransmissions, RSTs, window sizes, the actual TLS handshake; distinguishes network loss from application slowness.
- **`mtr` / `traceroute`** — locate a lossy or high-latency hop along the path.
- **`ss -i`** — per-socket RTT, cwnd, retransmit counters from the kernel.
- **Server-side APM / access logs** — confirm whether TTFB is the server's compute or the network.

The discipline: each tool isolates a *layer*, so you binary-search the stack with evidence rather than swapping random configs.

#### Q64. [Theory] What is the difference between the listen backlog (SYN queue vs accept queue), and what happens when they fill?

A listening TCP socket maintains **two** queues, and conflating them is a common gap:

```
                 SYN arrives
  client ───────────────────────►  [ SYN queue ]   (half-open: SYN received, SYN-ACK sent,
                                                      waiting for the client's final ACK)
                final ACK arrives          │
                                           ▼
                                    [ accept queue ]  (fully established, waiting for the
                                                        app to call accept())
  app calls accept() ◄──────────────────────┘
```

- The **SYN queue** (a.k.a. half-open queue) holds connections mid-handshake. If it overflows — classically under a **SYN flood** DoS — new SYNs are dropped. The defense is **SYN cookies**: instead of storing state, the server encodes connection info into the SYN-ACK's sequence number, so it can reconstruct state from the returning ACK without keeping a queue entry.
- The **accept queue** (backlog, sized by `listen(fd, backlog)` and capped by `net.core.somaxconn`) holds *established* connections the application hasn't `accept()`ed yet. If it overflows, the kernel either **drops the ACK** (client retransmits, appears as latency) or sends an **RST**, and increments `ListenOverflows`/`ListenDrops` (visible in `netstat -s`).

Production symptom: a server whose application thread is too slow to `accept()` (blocked, GC pause, undersized thread pool) fills the accept queue → clients see connection timeouts or resets even though the server is "up." The fix is faster accept loops, larger `somaxconn`, and addressing the real bottleneck behind the slow accept.

### 🟠 — extended

#### Q65. [Theory] Explain the full TCP state machine, including the less-obvious states (CLOSE_WAIT, FIN_WAIT, LAST_ACK).

TCP connections traverse a well-defined state machine; the diagnostic value is reading `ss`/`netstat` output and knowing what a stuck state means:

```
CLOSED → LISTEN                                  (server)
CLOSED → SYN_SENT → ESTABLISHED                  (client active open)
LISTEN → SYN_RCVD → ESTABLISHED                  (server passive open)

Active close:  ESTABLISHED → FIN_WAIT_1 → FIN_WAIT_2 → TIME_WAIT → CLOSED
Passive close: ESTABLISHED → CLOSE_WAIT → LAST_ACK → CLOSED
```

The states that matter most in debugging:

- **CLOSE_WAIT** — the *peer* sent a FIN, the kernel ACKed it, and TCP is now waiting for **your application** to call `close()`. **Many sockets stuck in CLOSE_WAIT is an application bug** — you're leaking sockets by not closing them (e.g. not closing a response stream). The remote is gone but you hold the fd. This is one of the highest-signal diagnostics: CLOSE_WAIT = "my code forgot to close."
- **FIN_WAIT_2** — you sent FIN, got the ACK, and are waiting for the *peer's* FIN. Lingering here means the peer isn't closing its half; the OS has a timeout (`tcp_fin_timeout`) to reap orphaned ones.
- **TIME_WAIT** — the active closer's wait of 2×MSL (Q43) — normal and protective, but high volume signals connection churn.
- **LAST_ACK** — the passive closer sent its FIN and awaits the final ACK before closing.

So a heap of TIME_WAIT points at *your* high connection churn; a heap of CLOSE_WAIT points at *your* socket leak. Knowing which side you are tells you where the bug lives.

#### Q66. [Theory] How does ECN (Explicit Congestion Notification) work, and why is it underused?

Traditional congestion control infers congestion from **packet loss** — which means a packet had to be *dropped* (and retransmitted) to learn the network was full. **ECN (RFC 3168)** lets routers signal congestion **without dropping packets**:

```
1. Endpoints negotiate ECN in the TCP handshake; mark packets "ECN-Capable" (ECT).
2. A congested router, instead of dropping, sets the CE ("Congestion Experienced") bit
   in the IP header.
3. The receiver echoes this back to the sender (ECE flag in TCP).
4. The sender reduces cwnd as if there had been a loss — but no packet was actually lost.
```

The benefit: you get the congestion signal **without the latency and retransmission cost of an actual drop** — especially valuable for short flows and interactive traffic. So why is it historically underused? **Middlebox ossification** (Q44) — firewalls and old routers mangled or cleared the ECN bits, and buggy stacks negotiated it incorrectly, so it was often disabled by default to avoid breakage. By 2026 this has shifted: ECN is far more deployed, and **L4S (Low Latency, Low Loss, Scalable throughput)** uses a more granular ECN signal with AQM (DualPI2) and scalable congestion controllers (TCP Prague, BBRv3-style) to deliver consistently low latency under load — a direct attack on bufferbloat (Q48).

#### Q67. [Coding] Implement a token-bucket rate limiter suitable for protecting a network endpoint.

Rate limiting is the network engineer's first line of defense against abuse and overload. The **token bucket** allows bursts up to the bucket size while bounding the long-run rate, and is more flexible than a fixed window:

```java
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe lock-free token bucket: `rate` tokens/sec, burst up to `capacity`. */
public class TokenBucket {
    private final long capacity;
    private final double refillPerNano;       // tokens added per nanosecond
    private final AtomicLong stateBits;       // packs tokens(*1000) + lastRefill nanos? — kept simple below

    // For clarity we use a small synchronized core; the algorithm is the point.
    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(long capacity, double ratePerSecond) {
        this.capacity = capacity;
        this.refillPerNano = ratePerSecond / 1_000_000_000.0;
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
        this.stateBits = new AtomicLong();    // unused in this simple variant
    }

    public synchronized boolean tryAcquire(int permits) {
        refill();
        if (tokens >= permits) {
            tokens -= permits;
            return true;                       // allowed
        }
        return false;                          // rate exceeded → reject (429) or queue
    }

    private void refill() {
        long now = System.nanoTime();
        double added = (now - lastRefillNanos) * refillPerNano;
        if (added > 0) {
            tokens = Math.min(capacity, tokens + added);
            lastRefillNanos = now;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        TokenBucket tb = new TokenBucket(10, 5);   // burst 10, then 5/sec
        for (int i = 0; i < 12; i++) System.out.println(i + ": " + tb.tryAcquire(1));
        Thread.sleep(1000);                         // refills ~5 tokens
        System.out.println("after 1s: " + tb.tryAcquire(1));
    }
}
```

Why token bucket over a fixed-window counter: fixed windows allow a **2× burst at the window boundary** (full quota at the end of one window plus full quota at the start of the next). Token bucket smooths this while still permitting controlled bursts. For distributed rate limiting you push the bucket state into Redis (e.g. with a Lua script for atomicity) so all instances share one limit. **Sliding-window-log** and **GCRA/leaky-bucket** are the common alternatives, trading memory for precision.

#### Q68. [Theory] How does TCP retransmission timing work? Explain RTO, RTT estimation, and fast retransmit.

TCP must decide *when* a segment is lost and resend it. Two mechanisms, by speed:

**1. Timeout-based (RTO — Retransmission Timeout):** the slow safety net. TCP continuously estimates the round-trip time and its variance (Jacobson/Karels algorithm, RFC 6298):

```
SRTT   = (1 − α)·SRTT + α·sample          (smoothed RTT, α ≈ 1/8)
RTTVAR = (1 − β)·RTTVAR + β·|SRTT − sample|  (β ≈ 1/4)
RTO    = SRTT + 4·RTTVAR                   (with a 1s floor in modern stacks)
```

If an ACK doesn't arrive within RTO, the segment is retransmitted and the RTO is **doubled** (exponential backoff). **Karn's algorithm** says: don't use the RTT sample from a *retransmitted* segment (you can't tell which copy was ACKed), avoiding corrupted estimates.

**2. Fast retransmit (loss inferred without waiting for the timer):** if the sender receives **3 duplicate ACKs** (the receiver keeps re-requesting the same byte because later segments arrived but one is missing), it retransmits **immediately** without waiting for RTO, then enters **fast recovery** (halve cwnd rather than collapse to 1). This is far faster than waiting a full RTO.

```
seq 1,2,3,4,5 sent; seq 2 lost
receiver ACKs: want-2, want-2, want-2, want-2   (3 dup ACKs)
sender → retransmit seq 2 NOW (don't wait for the timeout)
```

Modern stacks add **RACK (Recent ACKnowledgment)**, which uses **time** rather than dup-ACK *counts* to detect loss — more robust with reordering and small flows where you may never accumulate 3 dup ACKs. Tail losses (the last segments of a flow) are handled by **Tail Loss Probe (TLP)**, which sends a probe before the costly RTO fires.

#### Q69. [Theory] Explain how a service mesh implements mTLS, traffic policy, and observability without changing app code.

A **service mesh** (Istio, Linkerd, Cilium Mesh) factors cross-cutting network concerns out of application code into infrastructure, via the **sidecar** (or, increasingly, **ambient/per-node**) pattern:

```
            ┌─────────── Pod ───────────┐
 traffic ─► │  sidecar proxy  ⇄  app    │ ─► sidecar ─► remote pod
            │  (Envoy)        loopback  │
            └───────────────────────────┘
   data plane = the proxies;  control plane = Istiod/etc. pushes config to them
```

- **mTLS** — sidecars transparently upgrade pod-to-pod traffic to mutual TLS, using short-lived **SPIFFE/SPIRE** workload identities issued and auto-rotated by the control plane. The app sends plaintext to localhost; the proxy encrypts it. Zero app changes, identity-based (not IP-based) authn/authz.
- **Traffic policy** — retries with budgets, timeouts, circuit breaking, locality-aware load balancing, canary/traffic-splitting, and fault injection are all expressed as control-plane config and enforced by the proxies.
- **Observability** — because every request transits a proxy, you get uniform **golden metrics** (latency, traffic, errors, saturation), distributed-trace propagation, and access logs for free.

The trade-off a senior engineer must name: the sidecar adds **latency (an extra proxy hop each way), memory/CPU per pod, and operational complexity**. That cost is why 2026 has a strong move toward **sidecar-less / ambient mesh** (per-node L4 proxy + optional per-namespace L7 "waypoint") and **eBPF-based meshes** (Cilium) that handle identity and policy in the kernel — reducing the per-pod tax while keeping the zero-code-change benefit.

#### Q70. [Theory] What is a SYN flood, and how do SYN cookies defend against it without keeping per-connection state?

A **SYN flood** is a classic DoS that exploits the handshake's asymmetry: the attacker sends a torrent of **SYN** packets (often with **spoofed source IPs**) and never completes the handshake. Each SYN makes the server allocate a half-open entry in the **SYN queue** and send a SYN-ACK; the queue fills, and legitimate clients can't connect.

**SYN cookies** eliminate the need to store half-open state at all:

```
Normally: SYN → server stores TCB in SYN queue → SYN-ACK → ACK → move to accept queue
SYN cookie: SYN → server stores NOTHING; encodes state INTO the SYN-ACK's ISN:
            ISN = hash(src/dst IP+port, secret, time-counter) || encoded MSS
            → ACK comes back with ack = ISN+1 → server reconstructs & validates state
```

The trick: the server's chosen Initial Sequence Number *is* the cookie — a keyed hash of the connection 4-tuple plus a slowly-rotating time counter, with a few bits encoding the negotiated MSS. When the (legitimate) client's final ACK returns, its acknowledgment number equals cookie+1, so the server recomputes the hash and verifies it without ever having stored anything. Spoofed SYNs cost the server nothing because no state was kept, and no ACK ever returns for them.

The limitations to mention: SYN cookies can only encode a **few bits**, so some TCP options (window scale, SACK, timestamps) were historically lost — modern Linux stashes some of these in the timestamp option to preserve them. SYN cookies are therefore typically engaged **only when the SYN queue is actually under pressure**, not always-on. Defense-in-depth adds upstream scrubbing (anycast + DDoS providers) for volumetric floods.

#### Q71. [Coding] Implement a sliding-window log rate limiter and explain its trade-off vs the fixed window.

The **fixed-window** counter is cheap but allows a burst of up to **2× the limit** straddling the window boundary. The **sliding-window log** records the timestamp of each request and counts only those within the trailing window — precise, at the cost of memory proportional to the rate:

```java
import java.util.ArrayDeque;
import java.util.Deque;

/** Allows at most `limit` requests in any trailing `windowMillis` interval. */
public class SlidingWindowLog {
    private final int limit;
    private final long windowMillis;
    private final Deque<Long> timestamps = new ArrayDeque<>();

    public SlidingWindowLog(int limit, long windowMillis) {
        this.limit = limit;
        this.windowMillis = windowMillis;
    }

    public synchronized boolean allow() {
        long now = System.currentTimeMillis();
        long cutoff = now - windowMillis;
        while (!timestamps.isEmpty() && timestamps.peekFirst() <= cutoff) {
            timestamps.pollFirst();              // evict entries older than the window
        }
        if (timestamps.size() < limit) {
            timestamps.addLast(now);
            return true;
        }
        return false;                            // limit reached for the trailing window
    }

    public static void main(String[] args) throws InterruptedException {
        SlidingWindowLog rl = new SlidingWindowLog(3, 1000);  // 3 per rolling second
        for (int i = 0; i < 5; i++) System.out.println(i + ": " + rl.allow());
        Thread.sleep(1100);
        System.out.println("after window: " + rl.allow());    // true again
    }
}
```

Trade-offs to articulate: the log is **exact** (no boundary burst) but stores one entry per request — memory-heavy at high rates. The **sliding-window counter** (a weighted blend of the current and previous fixed windows) approximates this with O(1) memory and is what most production limiters (e.g. Cloudflare) actually use. Choose the log for low-rate precision (per-user auth attempts), the approximation or token bucket for high-throughput edges.

#### Q72. [Theory] How does DNS actually travel on the wire — UDP vs TCP, EDNS0, and DoT/DoH/DoQ?

DNS's transport story is richer than "it's UDP":

- **UDP/53 is the default** — one datagram per query/response, low overhead. But classic DNS responses were capped at **512 bytes**; a larger response set the **TC (truncated)** flag, forcing the client to **retry over TCP/53**.
- **EDNS0 (RFC 6891)** lets endpoints advertise a larger UDP payload size (e.g. 1232 or 4096 bytes), avoiding most truncation, and carries extensions (DNSSEC OK bit, client subnet). The 1232-byte recommendation exists to dodge IP fragmentation (Q52), which DNS-over-UDP handles poorly and which enables cache-poisoning attacks.
- **TCP/53** is used for truncated responses, **zone transfers (AXFR/IXFR)**, and increasingly as a first-class transport.

The encrypted/private transports, all standard by 2026:

```
DoT  (DNS over TLS,   port 853)  — DNS inside a TLS connection; private, easy to block by port.
DoH  (DNS over HTTPS, port 443)  — DNS inside HTTPS; blends with web traffic, hard to block/censor.
DoQ  (DNS over QUIC,  port 853)  — DNS over QUIC: TLS privacy without TCP head-of-line blocking; great for mobile.
```

Plain DNS is unauthenticated and unencrypted, enabling **spoofing/cache poisoning** (Kaminsky attack) and **surveillance**. **DNSSEC** adds origin authentication/integrity via signatures (but not confidentiality), while DoT/DoH/DoQ add confidentiality of the *query itself* from on-path observers. The interview nuance: DNSSEC and DoH solve **different** problems — integrity vs privacy — and are complementary, not alternatives.

### 🔴 — extended

#### Q73. [Theory] Derive the maximum throughput of a single TCP flow as a function of loss and RTT (the Mathis equation).

For a loss-based (AIMD) congestion controller in steady state, throughput is bounded by a remarkably simple relationship — the **Mathis equation**:

```
Throughput ≈ (MSS / RTT) · (C / √p)

  MSS = segment size, RTT = round-trip time, p = packet-loss probability, C ≈ 1.22 (for Reno-like AIMD)
```

The derivation intuition: AIMD sawtooths the window — it grows by 1 MSS per RTT (additive increase) and halves on loss (multiplicative decrease). If loss occurs roughly every `1/p` packets, the window oscillates between `W/2` and `W`, averaging `~0.75W`, and steady state forces `W ∝ 1/√p`. Throughput is `W·MSS/RTT`.

The staff-level implications are profound:

- **Throughput is inversely proportional to RTT** — a flow with twice the RTT gets *half* the throughput for the same loss. This is why a far-away server is slow even on a fat pipe, and why CDNs/edge termination matter so much.
- **Throughput falls as 1/√p** — even tiny loss devastates high-speed flows. To fill a **10 Gbps, 100ms** path with 1500B packets, Reno needs a loss rate around **2×10⁻¹⁰** (about one packet in five billion, i.e. a congestion window of ~83,000 segments) — physically unachievable on real links. This single fact is *why* CUBIC and BBR exist: loss-based AIMD simply cannot fill modern long-fat networks.

So when someone asks "why won't my cross-continent transfer go faster?", the Mathis equation is the quantitative answer: RTT and loss, not bandwidth, are the binding constraints — fix them with parallel streams, BBR, larger windows, or moving the endpoints closer.

#### Q74. [Theory] Explain how Maglev / consistent-hashing-with-bounded-loads solves connection consistency in stateful L4 load balancers.

A stateless L4 load balancer (like Google's **Maglev** or an IPVS/eBPF LB) must send every packet of a given connection to the **same backend** — but it can't afford to keep per-connection state for millions of flows, and the backend set changes (deploys, autoscaling, failures). Two problems collide: **even distribution** and **connection consistency under churn**.

Naive `hash(5-tuple) % N` fails: when N changes, **almost every** connection remaps to a different backend, breaking in-flight TCP connections en masse. Plain consistent hashing helps but can distribute unevenly.

**Maglev hashing** builds a large lookup table (a permutation) so that:

- **Load is near-perfectly even** across backends (much tighter than ring-based consistent hashing), and
- **Adding/removing one backend disturbs only ~1/N of the table entries**, so the vast majority of existing connections still map to their original backend.

```
packet → hash(5-tuple) → index into Maglev table → backend
   backend removed → only entries that pointed at it are reassigned;
                     all other flows keep landing on the same backend.
```

This is paired with **connection tracking** as a fast path: the LB remembers recently-seen flows (best-effort, bounded memory) so even the small fraction that *would* remap on a change stays pinned, and consistent hashing is the **fallback** when the LB has no state (e.g. after its own restart, or when traffic shifts between LB instances via ECMP). **Consistent hashing with bounded loads (CHWBL)** is the related idea that caps how much any one backend can receive, spilling overflow to the next node — used in L7 proxies (Envoy's `ring_hash`/`maglev` policies) to combine stickiness with hot-key protection. The deep point: stateless-but-consistent routing is achievable only by making the *hash function itself* stable under membership changes, not by storing state.

#### Q75. [Theory] How does QUIC's loss detection and congestion control differ from TCP's, and why was moving it to user space significant?

QUIC reuses the *concepts* of TCP loss recovery (RFC 9002) but fixes long-standing ambiguities and moves the whole machinery into user space:

- **Monotonic packet numbers** — QUIC numbers **packets** monotonically and **never reuses a packet number** for a retransmission (the data is resent in a *new* packet with a *new* number). This kills TCP's **retransmission ambiguity** (Karn's problem, Q68): an ACK unambiguously identifies exactly which transmission arrived, so RTT samples are always clean and spurious-retransmit detection is exact.
- **Per-stream delivery, connection-level congestion** — loss recovery and congestion control operate at the **connection** level (shared cwnd), but *delivery* to the application is **per-stream**, so a loss on stream A doesn't head-of-line-block stream B (Q21).
- **Richer ACKs** — QUIC ACK frames carry up to 256 ranges (vs TCP SACK's 3–4 blocks), giving far better visibility into exactly what was lost on high-BDP/lossy paths.
- **Pluggable, evolvable congestion control** — CUBIC/BBR/Prague live in the library.

Why **user space** is significant, not just an implementation detail:

1. **Deployability/evolvability** — congestion-control and recovery improvements ship with an app/library update (a browser auto-update, a server deploy) instead of waiting years for OS kernels *and* middleboxes to upgrade. This directly attacks ossification (Q44).
2. **Per-application tuning** — different services can run different controllers without kernel-wide changes.
3. **The cost** — user-space processing means more **CPU per packet** (syscall overhead, no kernel offloads like TSO/GRO that TCP enjoys, encryption per packet). Early QUIC used 2–3× the CPU of TCP; by 2026 this is narrowed via **UDP GSO/GRO**, **kernel offloads**, and even **eBPF/io_uring** fast paths, but QUIC remains more CPU-hungry — the deliberate trade of CPU and complexity for deployability, lower latency, and resilience.

The principal-level synthesis: QUIC is a bet that **agility beats kernel-integration efficiency** — putting the transport where it can evolve, and spending CPU and engineering to claw back the performance, is the right call for an internet whose middleboxes froze TCP in place.

#### Q76. [Theory] What is BGP route convergence, and how do mechanisms like route flap damping, BFD, and graceful restart affect it?

**Convergence** is the time for all routers in (and between) autonomous systems to agree on a consistent view of reachable prefixes after a topology change. During convergence, packets can be **dropped, looped, or blackholed** — so minimizing it is critical, but doing so naively causes instability.

The tension and the mechanisms:

- **Withdrawal/announcement propagation** — when a prefix goes away, the withdrawal must ripple across the internet; until it does, some routers still send traffic toward the dead path. BGP's **path-vector** design (carrying the full AS-path) prevents *loops* but not transient blackholes.
- **Route flap damping (RFC 2439)** — if a prefix flaps (announce/withdraw repeatedly, e.g. a flapping link), repeatedly reconverging the whole internet is expensive. Damping **suppresses** a flapping route for a back-off period, trading a *slower* recovery of a genuinely-restored route for *global stability*. It's a double-edged sword — overly aggressive damping historically delayed legitimate recovery, so modern guidance uses conservative parameters (RFC 7196).
- **BFD (Bidirectional Forwarding Detection)** — BGP's own keepalives are slow (tens of seconds). BFD is a lightweight, sub-second liveness protocol that detects a dead neighbor in **milliseconds** and triggers BGP to reroute far faster than waiting for hold timers. This is how you get fast failover without cranking BGP timers (which would add load and instability).
- **Graceful Restart (GR) / Non-Stop Routing** — lets a router whose **control plane** restarts (software upgrade, crash) keep **forwarding** on the existing data plane while it re-establishes BGP sessions, so a control-plane blip doesn't drop traffic. The neighbor holds the routes as "stale" during the grace period instead of immediately withdrawing them.

The staff insight: there's a fundamental **stability-vs-reactivity** trade-off in routing. Fast detection (BFD) and fast reaction reduce outage time but risk amplifying instability (flap storms); damping and hold timers add stability but slow recovery. Operators tune this balance per context, and the 2026 layer on top is **RPKI/ROV** for *correctness* (preventing hijacks/leaks, Q47) plus heavy **route monitoring** (e.g. real-time leak/hijack detection) because the protocol itself converges on whatever it's told — true or not.

#### Q77. [Coding] Implement a fixed-size sliding-window protocol simulator (Go-Back-N style) to demonstrate flow/loss recovery.

This models the sender side of a sliding-window ARQ to make the window/ACK/retransmit mechanics concrete:

```java
import java.util.*;

/** Go-Back-N sender simulation: window of N, cumulative ACKs, retransmit-on-timeout. */
public class GoBackN {
    private final int windowSize;
    private final String[] data;        // the "bytes"/frames to send
    private int base = 0;               // oldest unacked frame
    private int nextSeq = 0;            // next frame to send

    GoBackN(String[] data, int windowSize) {
        this.data = data; this.windowSize = windowSize;
    }

    /** Send everything currently allowed by the window. */
    List<Integer> sendWindow() {
        List<Integer> sent = new ArrayList<>();
        while (nextSeq < base + windowSize && nextSeq < data.length) {
            sent.add(nextSeq);          // "transmit" frame nextSeq
            nextSeq++;
        }
        return sent;                    // base .. nextSeq-1 are now in flight
    }

    /** Cumulative ACK: receiver confirms everything up to and including ackNum. */
    void onAck(int ackNum) {
        if (ackNum >= base) base = ackNum + 1;   // slide the window forward
    }

    /** Timeout on `base`: Go-Back-N retransmits the entire window from base. */
    List<Integer> onTimeout() {
        nextSeq = base;                 // rewind — resend base..everything in flight
        return sendWindow();
    }

    boolean done() { return base >= data.length; }

    public static void main(String[] args) {
        String[] frames = {"A","B","C","D","E","F"};
        GoBackN s = new GoBackN(frames, 3);

        System.out.println("send: " + s.sendWindow());   // [0,1,2] (window=3)
        s.onAck(0);                                       // ack 0 → base=1
        System.out.println("send: " + s.sendWindow());   // [3]    (window slid)
        System.out.println("loss! timeout on base=" + 1);
        System.out.println("resend: " + s.onTimeout());  // [1,2,3] go back N
        s.onAck(3);
        System.out.println("send: " + s.sendWindow());   // [4,5]
        s.onAck(5);
        System.out.println("done: " + s.done());          // true
    }
}
```

The simulation surfaces the core trade-off: **Go-Back-N** is simple (the receiver discards anything out of order, the sender retransmits the whole window from the lost frame) but **wasteful on lossy/high-BDP links** because it resends correctly-received frames. **Selective Repeat** (the spirit of TCP SACK, Q58) buffers out-of-order frames at the receiver and retransmits only the missing ones — more memory and bookkeeping, far less wasted bandwidth. Real TCP is essentially selective-repeat-flavored with SACK, which is why modern stacks recover from sparse loss efficiently.

#### Q78. [Theory] How do you reason about and defend against a SYN/UDP/amplification DDoS at the network layer in 2026?

Treat DDoS as a layered problem and match the defense to the attack *vector* and *layer*:

```
Volumetric (L3/4): saturate bandwidth         → UDP/ICMP floods, amplification
Protocol/state (L4): exhaust connection state  → SYN floods, ACK floods
Application (L7): exhaust app resources         → HTTP floods, slowloris, expensive queries
```

**Volumetric & amplification** — the attacker spoofs the victim's IP and queries reflectors (open DNS/NTP/memcached/SSDP servers) that send **much larger** responses to the victim (memcached amplification reached ~50,000×). Defenses:

- **Anycast scrubbing** (Q38) — announce the victim's prefix from a global scrubbing network so attack traffic is **spread across many PoPs** and absorbed near its source, never concentrating on one site. This is the core of Cloudflare/Akamai/AWS Shield.
- **BCP 38 / source-address validation (ingress filtering)** — networks drop packets with spoofed source addresses at the edge; the *systemic* fix, though incompletely deployed.
- **Securing reflectors** — close open resolvers, rate-limit responses (DNS RRL).

**Protocol/state floods** — **SYN cookies** (Q70) for SYN floods; **conntrack tuning** and stateless filtering for ACK/UDP floods; eBPF/XDP (Q50) to **drop attack packets at the NIC driver** before they cost the kernel a full traversal (Cloudflare's "L4Drop", Facebook's Katran do exactly this — millions of pps dropped per core).

**Application floods** — L7 WAF rules, behavioral/ML detection, challenge mechanisms (JS challenges, proof-of-work, CAPTCHA-as-last-resort), per-client rate limiting (Q67/Q71), and **caching** so floods of cacheable requests never reach origin.

The principal-level framing for 2026: defense is **always-on, layered, and anycast-fronted** — you don't "turn on" DDoS protection during an attack, you architect for it continuously (capacity headroom, edge absorption, autoscaling with cost caps to avoid "economic denial of sustainability," and graceful degradation). And you must defend your **dependencies** too — your DNS and BGP (Q47) are part of the attack surface, as the 2016 Dyn DNS attack showed.

#### Q79. [Theory] Explain how kernel offloads (TSO/GRO/checksum/RSS) and the path from NIC to socket affect real-world throughput.

At 10/40/100 Gbps, the **per-packet cost** (interrupts, header processing, copies) dominates, so NICs and kernels offload work from the CPU. Knowing these explains why a "fast" link underperforms:

- **Checksum offload** — the NIC computes/validates IP/TCP checksums in hardware, freeing the CPU.
- **TSO / GSO (TCP/Generic Segmentation Offload, transmit)** — the stack hands the NIC one giant (up to 64KB) "super-segment"; the **NIC slices it into MSS-sized packets**. The CPU processes one buffer instead of ~45, slashing per-packet overhead on send.
- **LRO / GRO (Large/Generic Receive Offload, receive)** — the inverse: incoming packets of the same flow are **coalesced** into one large buffer before the stack processes them, amortizing receive cost. (GRO is the safer software variant; LRO can mangle forwarded traffic.)
- **RSS (Receive Side Scaling)** — the NIC hashes each flow's 5-tuple to one of several **hardware queues**, each pinned to a different CPU core, so receive processing **scales across cores** instead of bottlenecking on one. **RPS/RFS** are software equivalents, and **XPS** does it for transmit.

```
TX: app write → [GSO: one big buffer] → NIC slices into MSS packets → wire
RX: wire → NIC [RSS: pick a core queue] → [GRO: coalesce] → stack → socket → app
```

The performance reality this explains:

- A flow **pinned to a single core** (poor RSS hashing, or a single elephant flow) can bottleneck on that core's softirq processing while other cores idle — a common "we have 100Gbps but only get 20" mystery. Fix with proper queue/IRQ affinity, or split into multiple flows.
- **Disabling offloads** for packet capture/measurement makes throughput crater — and conversely, GRO can make `tcpdump` show misleadingly huge "packets."
- This is also *why* **QUIC was slower** (Q75): it initially couldn't use TSO/GRO/checksum offloads built for TCP, so it paid full per-packet CPU. The 2026 fixes — **UDP GSO/GRO**, hardware offloads for QUIC, and **io_uring/eBPF** fast paths — exist precisely to bring these amortizations to UDP.

The staff-level point: throughput engineering at high speed is mostly about **amortizing per-packet cost** and **spreading work across cores** — the protocol and app sit atop a hardware/kernel pipeline whose offloads you must understand to diagnose why the wire isn't full.

#### Q80. [Behavioral] Tell me about a time you had to make a networking architecture decision under significant uncertainty or constraints. How did you reason and communicate it?

This probes **judgment, trade-off articulation, and stakeholder communication** at a senior/staff level — not protocol trivia. Use **STAR**, and show that you quantified and de-risked rather than guessed:

- **Situation**: "We were rolling out HTTP/3 across our global edge to cut mobile tail latency, but ~5% of users sat behind networks that throttle or block UDP/443, and our observability for QUIC was immature. Leadership wanted the latency win for an upcoming launch."
- **Task**: "Decide whether and how to ship HTTP/3 without regressing the users it might break, under a fixed launch deadline."
- **Action**: "I framed it as a reversible, measurable rollout rather than a binary switch. We kept **happy-eyeballs-style fallback** (race HTTP/3 against HTTP/2, fall back automatically if QUIC fails or stalls), so a UDP-block could never break a user. I ran a **1% canary** with explicit metrics — connection-failure rate, fallback rate, P50/P99 by access network — and set a **rollback trigger** tied to fallback-rate and error budget. I wrote a one-page decision doc (an ADR) stating the hypothesis, the guardrail metrics, and the conditions under which we'd halt, and reviewed it with the SRE and mobile teams so everyone owned the kill switch."
- **Result**: "Mobile P99 dropped ~20% for the majority on clean networks; the UDP-blocked minority transparently fell back with no regression. The fallback-rate metric became a permanent SLI, and the ADR became our template for future transport rollouts."

What the interviewer listens for: **reducing risk with reversibility and canaries**, **defining guardrail metrics and rollback criteria up front**, **respecting the long tail** of real-world networks (not just the happy path), and **communicating the decision in writing** so it's auditable and teachable. The anti-pattern is "we turned on HTTP/3 because it's newer/faster" with no measurement, fallback, or stakeholder buy-in.

#### Q81. [Theory] How does QPACK avoid the head-of-line blocking that a naive HPACK-over-QUIC would create?

This is the subtle interaction between **stateful header compression** and **out-of-order stream delivery** — a favorite deep question because it shows you understand *why* HTTP/2 mechanisms couldn't be copied verbatim into HTTP/3.

**The problem:** HPACK's dynamic table (Q61) is updated **in request order** — header block N may reference an entry added by header block N−1. Over **TCP** (HTTP/2) that's fine because TCP delivers bytes strictly in order. But **QUIC** delivers each stream independently and **out of order across streams** — the whole point (Q21). If you ran HPACK directly over QUIC, a header block that *references a dynamic-table entry whose defining instruction hasn't arrived yet* would have to **block and wait** — reintroducing exactly the head-of-line blocking QUIC was designed to remove.

**QPACK's solution (RFC 9204):** it splits the work across dedicated unidirectional streams and tracks dependencies explicitly:

- Dynamic-table **insertions** travel on a separate **encoder stream**; table-state **acknowledgments** travel on a **decoder stream**.
- Each header block carries a **"Required Insert Count"** — the table state it depends on. If that state hasn't arrived, *only that block* waits; it never blocks unrelated streams.
- The encoder can **choose** how aggressively to reference the dynamic table: referencing only **already-acknowledged** entries means **zero blocking** (at the cost of less compression), while referencing recent entries gives better compression but risks a brief per-stream wait. This is a **tunable compression-vs-HOL-blocking knob**.

```
HPACK/TCP: dynamic table updated in strict byte order → in-order delivery guarantees it works
QPACK/QUIC: insertions on encoder stream + "Required Insert Count" per block
            → a block waits ONLY for its own dependencies, not for other streams
```

The deep lesson: QUIC's per-stream independence is a property you must **preserve end-to-end** — any shared, ordered state (compression tables, priorities) has to be redesigned to avoid recreating the very HOL blocking you eliminated at the transport layer. QPACK is the canonical example of that redesign.

#### Q82. [Theory] Compare the data-plane models — thread-per-connection, event loop, io_uring, and kernel bypass — and when each wins.

The server's **concurrency/I-O model** determines how many connections it can serve and at what latency. The evolution:

```
Model                  Mechanism                          Scales to       Cost / when it wins
---------------------  ---------------------------------  --------------  --------------------------------
Thread-per-connection  one OS thread blocks per socket    ~1000s          simplest code; thread + context-
                                                                          switch overhead kills it at high C
Event loop (epoll)     1 thread, readiness notification   100K+ (C10K)    Netty/nginx/Node; complex callback
                       (epoll/kqueue), non-blocking I/O                   code, but minimal per-conn memory
Virtual threads (Loom) M:N user threads on carriers       millions        Java 21+; blocking-style code,
                                                                          JVM multiplexes — easy AND scalable
io_uring               shared submission/completion       very high       Linux 5.x+; batched async syscalls,
                       ring buffers, batched async ops                    fewer syscalls, true async disk+net
Kernel bypass (DPDK)   poll NIC from user space, no kernel  10s of Mpps   NFV/HFT/routers; max pps, but burns
                                                                          cores, loses kernel TCP/security
```

How to reason about *which*:

- **Thread-per-connection** wins on **simplicity** for modest concurrency (internal tools, low connection counts). It dies at scale from per-thread stack memory and context-switch cost — the original **C10K problem**.
- **Event loop (epoll/kqueue)** solved C10K: one (or few) threads handle 100K+ connections via **readiness** notification and non-blocking sockets. The cost is **inverted control flow** (callbacks/reactor), which is harder to write and debug. This is nginx, Netty, Node, Envoy.
- **Virtual threads (Project Loom, Java 21+)** are the 2026 sweet spot for the JVM: you write **straightforward blocking code**, but the runtime parks a virtual thread (not the carrier) on I/O, multiplexing millions onto a few OS threads. You get event-loop scalability with thread-per-connection *readability* — as long as you avoid pinning (native calls, `synchronized` over I/O).
- **io_uring** changes the *syscall* model: instead of one syscall per I/O, the app and kernel share **ring buffers** to submit and reap operations in **batches**, and it offers **true async** for disk and network (where epoll only signals readiness). It slashes syscall overhead at very high request rates and is increasingly the substrate under high-performance runtimes.
- **Kernel bypass (DPDK/AF_XDP, Q50)** is the extreme: poll the NIC directly from user space for tens of millions of packets/sec, used in routers, firewalls, NFV, and HFT. You **give up** the kernel's TCP stack, security, and fairness, and **dedicate CPU cores to busy-polling** — only worth it when raw packet rate is the product.

The synthesis a principal engineer offers: there's a spectrum from **easy-but-limited** (thread-per-connection) to **fast-but-specialized** (kernel bypass), and the right point depends on your **connection count, latency target, and team's ability to maintain complexity**. For most cloud-native services in 2026, **event loops or virtual threads** are the answer; **io_uring** when syscall overhead bites; **eBPF/XDP or DPDK** only for the packet-processing tier (LBs, gateways, security appliances) where you're counting packets-per-second-per-core.

---

## 🧩 Extended Questions — Set 2: Practical scenarios, troubleshooting & coding

### 🟢 — extended

#### Q83. [Practical] A user reports "the site won't load" but you can reach it fine. Walk through a first-pass triage from their machine outward.

Treat it as a layered isolation problem and move outward from the client. Don't guess — each step rules out a layer.

1. **Is it DNS or connectivity?** Have them run `ping <host>` (or `Test-NetConnection` on Windows). If the name resolves to an IP but doesn't connect, DNS is fine and the problem is reachability. If the name fails to resolve, try `nslookup <host> 8.8.8.8` to bypass their configured resolver — a working answer from 8.8.8.8 but not the default resolver points at a broken/poisoned local DNS or stale cache (`ipconfig /flushdns`).
2. **Is it just this host or the whole internet?** `ping 1.1.1.1` (raw IP). Works → DNS/app layer; fails → local network/gateway.
3. **Is the path broken?** `traceroute`/`tracert` shows where packets die — last responding hop localizes the failure (their router, ISP, or beyond).
4. **Is it the app, not the network?** `curl -v https://host/` — a TCP connect that succeeds but a TLS or HTTP error means the network is fine and the problem is certificate/app-level (expired cert, 5xx).
5. **Is it them specifically?** Captive portal (coffee-shop Wi-Fi), corporate proxy, VPN split-tunnel, or a hosts-file override. Browsers also pin HSTS, so an expired cert is a hard block for them but not necessarily for you.

The discipline: each command isolates one layer (DNS → IP reachability → path → TLS → HTTP), so you converge instead of flailing.

#### Q84. [Practical] What do the most common HTTP status codes mean, and how do you act on a 502 vs 503 vs 504?

Status codes are grouped by leading digit: **2xx** success, **3xx** redirect, **4xx** client error (the request is wrong), **5xx** server error (the server failed a valid request). The 5xx trio is the one that trips people up because they all look like "the gateway is sad":

```
502 Bad Gateway       The proxy/LB got an INVALID response from upstream
                      (upstream crashed, returned garbage, or reset the conn).
                      → Look at the backend: did it OOM, panic, or send a bad response?
503 Service Unavailable The server is up but refusing — overloaded, in maintenance,
                      or no healthy upstreams in the pool.
                      → Check capacity/health checks; often transient; honor Retry-After.
504 Gateway Timeout   The proxy waited for upstream and gave up (upstream too slow).
                      → A latency problem: slow query, downstream dependency, or a
                        proxy read-timeout shorter than the backend's real work.
```

Actionable distinction: **502** = upstream returned something broken (debug the app/connection); **503** = no healthy capacity (scale/health-check problem); **504** = upstream was alive but too slow (timeout/latency problem). For 503 specifically, a well-behaved client respects the `Retry-After` header and backs off rather than hammering.

#### Q85. [Practical] How do you test whether a specific TCP port is open on a remote host, and what does each outcome tell you?

You're probing the 3-way handshake. The three outcomes map cleanly to three causes:

```
nc -vz host 443          # netcat: just attempt connect, no data
# or PowerShell:
Test-NetConnection host -Port 443
```

```
SYN → SYN-ACK (connect succeeds)  → port OPEN, something is listening
SYN → RST     (connection refused) → host reachable, NOTHING listening on that port
SYN → (silence, then timeout)      → a FIREWALL is dropping packets (no RST sent)
```

The key teaching point: **"connection refused" and "timeout" mean very different things.** A refused connection (RST) proves the host is up and reachable at L3 — it's just that no process bound that port (or it's not started). A **timeout** means packets are being silently dropped, almost always a firewall/security-group rule, because a closed port normally answers with a fast RST. In cloud environments, a timeout to a known-good service usually means a missing security-group/NACL rule, not a dead app.

#### Q86. [Theory] Your `ping` to a server times out, but the website loads fine in a browser. Is the server down?

No — this is a classic false alarm. **`ping` uses ICMP**, while your browser uses **TCP on port 443**. Many hosts and firewalls **block or rate-limit ICMP** for security/DDoS reasons while happily serving HTTP/HTTPS. So a dropped ping says nothing about whether the application is up.

```
ping host    → ICMP echo  → often filtered → "Request timed out" (misleading)
curl host    → TCP/443    → succeeds       → server is actually fine
```

The correct reachability test for a *service* is to probe its actual port (`curl`, `nc -vz host 443`, `Test-NetConnection host -Port 443`), not to ping. ICMP being blocked is also why `traceroute` sometimes shows `* * *` for hops that are alive — they just don't answer ICMP. Conclusion: ping is a coarse, ICMP-specific tool; never conclude "server down" from a failed ping alone.

#### Q87. [Coding] Write a Java method that, given a list of `host:port` endpoints, checks which are reachable within a timeout, concurrently.

A practical health-probe: attempt a TCP connect with a bounded timeout, in parallel, and report which succeeded. This is the core of a simple uptime checker.

```java
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;

public class PortChecker {

    /** Returns a map of "host:port" -> reachable(true/false), probed concurrently. */
    public static Map<String, Boolean> checkAll(List<String> endpoints, int timeoutMs) {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) { // Java 21+
            Map<String, Future<Boolean>> futures = new LinkedHashMap<>();
            for (String ep : endpoints) {
                futures.put(ep, pool.submit(() -> isReachable(ep, timeoutMs)));
            }
            Map<String, Boolean> result = new LinkedHashMap<>();
            for (var e : futures.entrySet()) {
                try {
                    result.put(e.getKey(), e.getValue().get());
                } catch (ExecutionException | InterruptedException ex) {
                    result.put(e.getKey(), false);
                }
            }
            return result;
        }
    }

    private static boolean isReachable(String endpoint, int timeoutMs) {
        String[] hp = endpoint.split(":");
        try (Socket socket = new Socket()) {
            // connect() blocks at most timeoutMs; success == SYN/SYN-ACK/ACK completed
            socket.connect(new InetSocketAddress(hp[0], Integer.parseInt(hp[1])), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;   // refused (RST), timeout (firewall), or unknown host
        }
    }

    public static void main(String[] args) {
        var endpoints = List.of("github.com:443", "github.com:9999", "localhost:5432");
        System.out.println(checkAll(endpoints, 2000));
    }
}
```

Using a **virtual-thread-per-task executor** means each blocking `connect()` parks cheaply, so probing hundreds of endpoints concurrently costs almost nothing. A bounded `connect` timeout is essential — without it a firewalled host (silent drop) hangs until the OS default (~tens of seconds).

#### Q88. [Practical] A teammate says "just hardcode the IP, DNS is flaky." Why is that a bad idea, and what are the right fixes?

Hardcoding an IP trades one rare failure for several common ones. DNS exists precisely so the address can change without the client changing:

- **Cloud IPs are ephemeral** — a load balancer, NAT gateway, or autoscaled instance gets a new IP on replacement. Hardcoding breaks on the next deploy/failover.
- **You lose load balancing & failover** — DNS round-robin and health-based DNS hand out different/healthy IPs; a fixed IP pins you to one node.
- **TLS breaks** — certificates are issued for *names*, not IPs; connecting by IP fails SNI/hostname verification.
- **CDNs/anycast depend on resolution** — the "right" IP is location-dependent; a hardcoded one defeats edge routing.

Right fixes for genuinely flaky DNS: use a **reliable recursive resolver** (1.1.1.1/8.8.8.8), respect and tune **TTLs**, enable **negative-cache** limits, add a **local caching resolver** (systemd-resolved, dnsmasq) to absorb hiccups, and configure your client/runtime DNS cache TTL (the JVM's `networkaddress.cache.ttl` defaults can be surprising). If a dependency's DNS truly is unreliable, fix the resolver, don't bypass the indirection that makes the system operable.

#### Q110. [Practical] You curl an API and get `curl: (60) SSL certificate problem`. List the concrete causes and how to identify the right one without disabling verification.

A cert verification failure means the chain didn't validate — and reaching for `curl -k` (skip verification) hides a real problem and is dangerous in production. The distinct causes:

- **Expired (or not-yet-valid) certificate** — the most common; check `openssl s_client -connect host:443 -servername host </dev/null | openssl x509 -noout -dates`.
- **Hostname mismatch** — the cert's CN/SAN doesn't cover the name you connected to (e.g. you hit an IP, or `api.x.com` vs `x.com`). Verify with the `-servername` (SNI) flag and inspect the SAN list.
- **Missing intermediate certificate** — the server sent only the leaf, not the intermediate, so the client can't build a chain to a trusted root. Browsers sometimes paper over this via AIA fetching; curl/Java do not. Fix on the *server* by serving the full chain.
- **Untrusted/unknown CA** — a private/self-signed CA not in the client's trust store (common for internal services); add the CA to the trust store, don't skip verification.
- **Clock skew** — the client's clock is wrong, making a valid cert look expired/not-yet-valid. Check the local time.

Identify it precisely:

```
openssl s_client -connect api.example.com:443 -servername api.example.com -showcerts
```

This prints the presented chain, the verify result, and the exact error code (e.g. `unable to get local issuer certificate` = missing intermediate or untrusted CA; `certificate has expired` = expired). Fix the actual cause — never normalize `-k`/disabled verification, which silently accepts MITM.

### 🟡 — extended

#### Q89. [Practical] Requests to a downstream service intermittently take exactly 30s and then fail. What does the round number tell you, and how do you confirm?

A suspiciously **round, constant duration** (5s, 10s, 30s, 60s) is the signature of a **timeout firing**, not variable work — real latency is jittery, timeouts are exact. The job is to find *which* timeout owns 30s.

Likely candidates, outermost to innermost:
- **Connect timeout** — DNS resolves but the TCP handshake never completes (firewall dropping SYNs, or the host is gone). 30s connect timeouts are common defaults.
- **Read/response timeout** — connection established, request sent, but the server never responds (deadlocked thread pool, slow query, downstream of the downstream).
- **Pool acquisition timeout** — all pooled connections are checked out; callers block 30s waiting for one, then fail. This looks like "the service is slow" but is really client-side starvation.

Confirm:
1. **Packet capture** (`tcpdump`) — see whether you even get a SYN-ACK (connect issue) or you send a request and get silence (read issue).
2. **Enable client timeout logging** — most clients log *which* timeout tripped.
3. **Check pool metrics** — active vs idle vs pending-acquire; a full pool with a queue is the tell for acquisition timeout.
4. **Correlate with the server** — is it slow for everyone, or only when your pool is saturated?

The fix differs entirely by cause: connect → fix firewall/DNS; read → fix the server or shorten the timeout to fail fast; pool → resize the pool or reduce hold time.

#### Q90. [Coding] Implement an exponential-backoff-with-jitter retry helper for an idempotent network call in Java.

Naive fixed-interval retries cause **thundering herds** — all clients retry in lockstep and re-overload the recovering server. Exponential backoff spreads load over time; **jitter** de-synchronizes clients. "Full jitter" (random between 0 and the cap) is the AWS-recommended variant.

```java
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

public class RetryWithBackoff {

    /** Retries an idempotent call with exponential backoff + full jitter. */
    public static <T> T call(Callable<T> action, int maxAttempts,
                             Duration base, Duration cap) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                return action.call();
            } catch (Exception e) {
                last = e;
                if (!isRetryable(e) || attempt == maxAttempts - 1) throw e;

                // exp = base * 2^attempt, capped
                long expMillis = Math.min(cap.toMillis(),
                        base.toMillis() * (1L << attempt));
                // full jitter: sleep a random amount in [0, expMillis]
                long sleep = ThreadLocalRandom.current().nextLong(expMillis + 1);
                Thread.sleep(sleep);
            }
        }
        throw last;
    }

    private static boolean isRetryable(Exception e) {
        // Retry transient faults only: timeouts, connection resets, 5xx, 429.
        // NEVER blindly retry non-idempotent writes or 4xx (except 429).
        return e instanceof java.net.SocketTimeoutException
            || e instanceof java.net.ConnectException;
    }

    public static void main(String[] args) throws Exception {
        String body = call(() -> fetch("https://example.com"),
                           5, Duration.ofMillis(100), Duration.ofSeconds(5));
        System.out.println(body);
    }

    static String fetch(String url) { /* real HTTP call */ return "ok"; }
}
```

Three non-negotiables, often missed in interviews: **(1)** only retry **idempotent** operations (or guard non-idempotent ones with an idempotency key), **(2)** retry only **transient** errors (timeouts, connection resets, 5xx, 429) — never 400/401/404, and **(3)** always **cap** total attempts/time and **add jitter**. Pair this with a **circuit breaker** so you stop retrying a dead dependency entirely.

#### Q91. [Practical] You see thousands of sockets in `CLOSE_WAIT` on your server and it eventually stops accepting connections. What's the bug?

`CLOSE_WAIT` means **the peer closed (sent FIN), your side ACKed it, but your application never called `close()`**. The connection is half-closed and stuck waiting for *your* code to finish. Thousands of them is a textbook **socket/file-descriptor leak** in the application.

```
Remote sends FIN → your kernel ACKs → state = CLOSE_WAIT
   ... now waiting for YOUR app to call close() ...
App never closes → socket stays in CLOSE_WAIT forever → FD leak
```

Contrast with `TIME_WAIT` (which is the *active closer* behaving correctly and is self-healing after 2×MSL). **`CLOSE_WAIT` piling up is always an app bug**, not a tuning issue. Usual causes: a code path that returns/throws without closing the connection, a missing `try-with-resources`/`finally`, an HTTP client whose response body is never consumed/closed, or a pool that leaks connections. Eventually you hit the `ulimit -n` file-descriptor cap and `accept()` fails with "Too many open files," so the server stops taking new connections.

Diagnose with `ss -tan state close-wait` and `lsof -p <pid>` to see which sockets/FDs leak; fix by ensuring every connection/stream is closed on all paths (idiomatically, try-with-resources in Java).

#### Q92. [Practical] After deploying behind a reverse proxy, all your access logs show the proxy's IP instead of the real client. How do you fix it correctly and securely?

When a reverse proxy (Nginx, ALB, Cloudflare) terminates the connection, your app's socket peer **is** the proxy — so the real client IP must travel in an HTTP header. The proxy adds **`X-Forwarded-For`** (a comma-separated chain) and **`X-Forwarded-Proto`**; modern proxies may use the standardized **`Forwarded`** header (RFC 7239).

```
Client(203.0.113.9) → Proxy(10.0.0.5) → App
   App sees peer = 10.0.0.5
   Proxy adds:  X-Forwarded-For: 203.0.113.9
   App reads the header to recover the true client IP
```

The **security** part is what separates a senior answer: `X-Forwarded-For` is **client-spoofable** if the request didn't actually come through your proxy. So:
- **Only trust the header from known proxy IPs** — configure your framework's "trusted proxies"/"trusted hops" (Spring's `ForwardedHeaderFilter`, `server.forward-headers-strategy=framework`, or Tomcat's `RemoteIpValve`).
- **Take the correct entry in the chain** — with N trusted hops, the real client is the (N+1)th-from-right entry; don't naively take the leftmost (attacker-controlled) value.
- **Strip/overwrite the header at your edge** so inbound spoofed values can't leak inward.

Getting this wrong causes both wrong logs *and* security bugs (rate limiters and ACLs keying off a spoofable IP).

#### Q93. [Coding] Parse a Cookie header and a Set-Cookie header in Java, respecting their different formats.

A common gotcha: the **request** `Cookie` header packs many cookies separated by `; `, while each **response** `Set-Cookie` carries **one** cookie plus attributes — and multiple `Set-Cookie` lines are sent separately (never comma-joined, because cookie values may contain commas, e.g. in `Expires` dates).

```java
import java.util.*;

public class CookieParsing {

    /** Request side: "Cookie: a=1; b=2; c=3" -> {a=1, b=2, c=3} */
    public static Map<String, String> parseRequestCookies(String header) {
        Map<String, String> out = new LinkedHashMap<>();
        if (header == null || header.isBlank()) return out;
        for (String pair : header.split(";")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(pair.substring(0, eq).trim(),
                        pair.substring(eq + 1).trim());
            }
        }
        return out;
    }

    /** Response side: parse ONE Set-Cookie into name, value, and attributes. */
    public static Cookie parseSetCookie(String header) {
        String[] parts = header.split(";");
        String[] nv = parts[0].split("=", 2);     // first pair is the actual cookie
        Cookie c = new Cookie(nv[0].trim(), nv.length > 1 ? nv[1].trim() : "");
        for (int i = 1; i < parts.length; i++) {  // remaining are attributes/flags
            String[] attr = parts[i].split("=", 2);
            String key = attr[0].trim().toLowerCase();
            String val = attr.length > 1 ? attr[1].trim() : "true";  // HttpOnly/Secure are flags
            c.attributes.put(key, val);
        }
        return c;
    }

    record Cookie(String name, String value, Map<String, String> attributes) {
        Cookie(String name, String value) { this(name, value, new LinkedHashMap<>()); }
    }

    public static void main(String[] args) {
        System.out.println(parseRequestCookies("session=abc; theme=dark; lang=en"));
        var c = parseSetCookie("session=abc; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=3600");
        System.out.println(c.name() + "=" + c.value() + " attrs=" + c.attributes());
    }
}
```

The load-bearing detail: split the cookie **value** on `=` with a limit of 2 (`split("=", 2)`) because base64 values often end in `=` padding; a naive `split("=")` corrupts them.

#### Q94. [Practical] An internal HTTP call works from your laptop but fails from inside the Kubernetes pod with "no route to host" / DNS failure. How do you debug?

Different network namespace, different rules — your laptop's success is irrelevant inside the cluster. Debug from *inside* the pod's perspective:

1. **DNS first.** `nslookup my-svc.my-ns.svc.cluster.local` from inside the pod (or `kubectl exec ... -- nslookup`). K8s service DNS is `service.namespace.svc.cluster.local`; a bare `my-svc` only resolves via the `search` domains in `/etc/resolv.conf`. A failure here points at CoreDNS, the `search`/`ndots` config, or a missing Service.
2. **Is the Service/Endpoints populated?** `kubectl get endpoints my-svc` — empty endpoints means the selector matches no ready pods (label mismatch or failing readiness probes), so DNS resolves but there's nothing behind it.
3. **NetworkPolicy.** A default-deny `NetworkPolicy` silently drops cross-namespace or egress traffic — "no route to host"/timeout with healthy DNS and endpoints is a strong signal. Check policies in both namespaces.
4. **Right port & protocol.** The Service `port` vs `targetPort` mismatch, or hitting the pod IP directly instead of the ClusterIP.
5. **`ndots:5` gotcha.** K8s sets `ndots:5`, so short names trigger several search-domain lookups before the real one — can manifest as slow or failing external DNS. A fully-qualified name (trailing dot) bypasses it.

The mental model: from a pod, the moving parts are **CoreDNS → Service → Endpoints → NetworkPolicy → kube-proxy/iptables-or-eBPF**, none of which exist on your laptop.

#### Q95. [Theory] Your monitoring shows p50 latency is great but p99 is terrible and spiky. What networking causes should you suspect?

A wide gap between **median and tail** latency means *most* requests are fine and a *few* hit a stall — classic tail-latency, and several causes are network/transport-level:

- **TCP retransmissions** — a lost packet forces a retransmit after an RTO (often 200ms+), so any request that loses a segment jumps to a much higher latency. Check retransmit counters (`ss -ti`, `netstat -s`).
- **Connection establishment on cold paths** — requests that find no warm pooled connection pay handshake (TCP+TLS) cost; pool exhaustion makes this hit the tail.
- **Head-of-line blocking (HTTP/2 over TCP)** — one lost segment stalls *all* multiplexed streams on that connection until recovery; HTTP/3/QUIC fixes this.
- **Bufferbloat** — oversized queues add variable queuing delay under load, inflating the tail.
- **GC / event-loop stalls** intersecting with I/O — a stop-the-world pause makes in-flight requests stall.
- **DNS resolution spikes** — an uncached lookup (resolver hiccup, low TTL) adds tens to hundreds of ms to a subset.
- **Noisy neighbors / rate limiting** — a few requests get throttled or land on a saturated backend.

The discipline: tail problems are about **the unlucky few**, so you hunt for *intermittent* events (a dropped packet, a cold connection, a GC pause) rather than systemic slowness. Mitigations: connection warm-up, hedged/parallel requests, HTTP/3, retransmit tuning, and per-route timeouts.

#### Q96. [Coding] Implement a simple HTTP request line + header parser that defends against malformed input.

Parsing untrusted protocol input safely is a real skill — request smuggling and DoS bugs live here. Parse the request line and headers, rejecting anything malformed instead of guessing.

```java
import java.io.*;
import java.util.*;

public class HttpRequestParser {

    record Request(String method, String target, String version,
                   Map<String, String> headers) {}

    public static Request parse(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in), 8192);

        String requestLine = r.readLine();
        if (requestLine == null) throw new IOException("empty request");
        String[] parts = requestLine.split(" ");
        if (parts.length != 3)                              // METHOD SP TARGET SP VERSION
            throw new IOException("malformed request line: " + requestLine);
        if (!parts[2].startsWith("HTTP/"))
            throw new IOException("bad version");

        Map<String, String> headers = new LinkedHashMap<>();
        String line;
        int count = 0;
        while ((line = r.readLine()) != null && !line.isEmpty()) {
            if (++count > 100) throw new IOException("too many headers"); // DoS guard
            int colon = line.indexOf(':');
            if (colon <= 0) throw new IOException("malformed header: " + line);
            String name = line.substring(0, colon).trim().toLowerCase();
            String value = line.substring(colon + 1).trim();
            // Reject smuggling vectors: duplicate Content-Length, or both CL + TE.
            if (name.equals("content-length") && headers.containsKey("content-length")
                    && !headers.get("content-length").equals(value)) {
                throw new IOException("conflicting Content-Length");
            }
            headers.merge(name, value, (a, b) -> a + ", " + b);
        }
        if (headers.containsKey("content-length") && headers.containsKey("transfer-encoding")) {
            throw new IOException("CL + TE present (smuggling risk)"); // reject per RFC 9112
        }
        return new Request(parts[0], parts[1], parts[2], headers);
    }
}
```

The defensive choices are the point: a strict 3-token request line, a header **count cap** (and in production, a size cap) to prevent memory-exhaustion DoS, lowercased header names (HTTP header names are case-insensitive), and explicit rejection of the **`Content-Length` + `Transfer-Encoding`** combination and conflicting duplicate `Content-Length` — the root of HTTP request-smuggling attacks (RFC 9112 mandates rejecting these).

#### Q111. [Practical] A `POST` works but the identical request as a `GET` with a body is silently dropped or mangled by an intermediary. What's happening and what does it teach about idempotency and method semantics?

Intermediaries (proxies, CDNs, some HTTP libraries) make **method-based assumptions** that bite when you violate convention. A `GET` with a request body is technically permitted by the spec but **its semantics are undefined**, so many components strip the body, ignore it, refuse to cache correctly, or reject the request. The fix is to use the method that matches the semantics — a request that carries data to act on is a `POST` (or `PUT`).

This connects to **method semantics** that intermediaries rely on:

```
Safe        GET, HEAD, OPTIONS  → no side effects; proxies may prefetch/cache freely
Idempotent  GET, PUT, DELETE, HEAD → repeating N times == doing it once (LBs may retry)
Neither     POST                → has side effects and is NOT idempotent → must NOT
                                  be auto-retried or cached by intermediaries
```

Two practical consequences: **(1)** because `GET`/`PUT`/`DELETE` are declared idempotent, load balancers and clients feel free to **auto-retry** them on a timeout — so don't smuggle non-idempotent effects into a `GET`, or a retry duplicates them. **(2)** `POST` bodies are expected and handled everywhere; `GET` bodies are a footgun. The lesson: HTTP method choice isn't cosmetic — the entire chain of caches, proxies, and retry logic behaves differently based on the method's documented **safe/idempotent** properties, so picking the right method is a correctness decision, not a style one.

### 🟠 — extended

#### Q97. [Practical] A cross-region replication link suddenly has high latency and packet loss. Walk through isolating whether it's your app, the network, or the provider.

Cross-region means a long path you don't fully own, so the goal is to attribute the fault to a segment. Work from symptoms to a localized hop:

1. **Quantify first** — `mtr`/`pathping` (continuous traceroute + per-hop loss) over a few minutes. This shows *where* loss starts. Loss appearing only at the final hop = destination/app; loss starting at an intermediate hop and persisting downstream = a real path problem.
2. **Beware ICMP-deprioritized hops** — a single mid-path hop showing loss while downstream hops are clean is usually a router **rate-limiting ICMP**, not real loss. Only loss that *propagates to the end* counts.
3. **App vs network** — test with `iperf3` (raw throughput, removes your app) between the two regions. If iperf is fine but your app is slow, it's your app/serialization/pool, not the link. If iperf also degrades, it's the network/transport.
4. **Transport tuning** — high-BDP links need large windows; check `ss -ti` for `retrans`, `cwnd`, and whether the window is BDP-sized. Loss on a long fat network murders a loss-based congestion control (Reno/CUBIC) — consider **BBR**.
5. **Provider attribution** — correlate with the cloud provider's status/health dashboards; capture `mtr` reports to open a support ticket with evidence. For dedicated interconnects, check the link's own metrics.

The senior framing: don't say "the network is slow" — produce a per-hop loss/latency profile and an iperf baseline so you can say "loss begins at hop 7, in the transit provider's network, and app-independent throughput confirms it."

#### Q98. [Theory] A service works at low traffic but starts dropping connections at peak. List the network-layer limits that typically get exhausted, in order of likelihood.

"Works small, breaks at scale" is almost always a **finite resource hitting its ceiling**. The usual suspects, with how to spot each:

```
1. Ephemeral port exhaustion   Outbound conns to one dest exhaust the ~28k ephemeral
                               ports (esp. with many TIME_WAIT). Symptom: "cannot assign
                               requested address". Fix: pooling, SO_REUSEADDR, more dests,
                               tcp_tw_reuse.
2. File-descriptor limits      Each socket is an FD; ulimit -n caps them. Symptom:
                               "Too many open files", accept() fails. Fix: raise nofile.
3. Accept/SYN backlog overflow Connections arrive faster than accept(); the queue fills
                               and the kernel drops SYNs. Symptom: connection timeouts,
                               ListenOverflows in `netstat -s`. Fix: backlog + faster accept.
4. Conntrack table full        NAT/firewall connection-tracking table caps. Symptom:
                               "nf_conntrack: table full, dropping packet" in dmesg.
5. Connection-pool exhaustion  Client pool maxed; callers queue then time out (app-level).
6. Thread-pool / worker limits  No worker to handle accepted connections; they sit and
                               time out even though the socket connected.
```

The diagnostic order mirrors likelihood: start at the OS counters (`ss -s`, `netstat -s`, `dmesg`, `ulimit -n`) because these silently cap *before* CPU/memory look stressed — which is why the box "looks fine" while connections drop. The fix is rarely "bigger box"; it's raising the specific limit and reducing churn (pooling, keep-alive).

#### Q99. [Coding] Implement a per-client concurrency limiter (bulkhead) that bounds in-flight requests per upstream and fails fast when saturated.

A **bulkhead** isolates failures: it caps concurrent calls to each dependency so a slow upstream can't consume all your threads/connections and take down unrelated traffic. A `Semaphore` with non-blocking `tryAcquire` gives fail-fast behavior.

```java
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

public class Bulkhead {
    private final Semaphore permits;
    private final long acquireTimeoutMs;
    private final LongAdder rejected = new LongAdder();

    public Bulkhead(int maxConcurrent, long acquireTimeoutMs) {
        this.permits = new Semaphore(maxConcurrent);
        this.acquireTimeoutMs = acquireTimeoutMs;
    }

    /** Runs the call only if a permit is available within the timeout; else fails fast. */
    public <T> T execute(Callable<T> call) throws Exception {
        boolean acquired = permits.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
        if (!acquired) {
            rejected.increment();
            throw new RejectedExecutionException("bulkhead full, shedding load");
        }
        try {
            return call.call();
        } finally {
            permits.release();          // MUST release on every path, even on exception
        }
    }

    public int available()   { return permits.availablePermits(); }
    public long rejected()   { return rejected.sum(); }

    public static void main(String[] args) throws Exception {
        Bulkhead bh = new Bulkhead(10, 50);   // max 10 concurrent, wait up to 50ms
        String r = bh.execute(() -> callUpstream());
        System.out.println(r);
    }
    static String callUpstream() { return "ok"; }
}
```

Why fail-fast matters: under overload, **shedding load quickly** (returning a fast 503) keeps the system responsive and lets clients back off, whereas an unbounded queue grows latency until everything times out at once. The `finally` release is critical — a leaked permit permanently shrinks the bulkhead. Bulkheads pair naturally with circuit breakers (stop calling a dead dependency) and timeouts (bound each call's duration).

#### Q100. [Practical] You must do a zero-downtime migration of a service to a new TLS certificate / new domain. What can break, and how do you stage it?

The risks cluster around **caching, pinning, and cutover ordering**. Plan the rollout so no client is ever pointed at a dead or mismatched endpoint:

- **DNS TTL pre-staging** — lower the record's TTL *well before* cutover (e.g. from 1h to 60s) so caches turn over fast when you flip. Clients honor the old TTL, so you must reduce it ahead of time, not at the moment of switch.
- **Certificate SAN overlap** — issue the new cert covering **both** old and new names (Subject Alternative Names) so a client hitting either name during the transition validates cleanly. Rotate the key/cert with overlap, never a hard swap.
- **Certificate pinning** — if any client (mobile apps especially) pins the cert/public key, a new cert breaks them until they update. Ship the new pin in a release *before* the rotation, or pin to the CA/backup key.
- **HSTS / `includeSubDomains` / preload** — once a browser sees HSTS, plain-HTTP fallback is gone; mis-sequencing during a domain move can lock users out. Be careful with `preload`, which is hard to undo.
- **Connection draining** — keep the old endpoint serving in-flight/keep-alive connections while new ones move over; drain gracefully rather than killing live sockets.
- **Validation gates** — verify the chain (`openssl s_client -connect host:443 -servername newname`), OCSP/stapling, and protocol negotiation in staging before flipping production weight.

Stage it as: lower TTL → deploy dual-SAN cert → ship updated pins → shift traffic gradually (weighted DNS or LB) → monitor handshake-failure metrics → drain old → raise TTL back. The theme is **overlap everything** so there's never a moment where a cached client meets an endpoint that no longer matches.

#### Q101. [Theory] Under load, you observe duplicate processing of requests even though the client "only sent it once." How can the network cause this, and how do you make the system safe?

The network can absolutely deliver a request more than once, and the client may legitimately resend:

- **Client-side retries** — a request times out at the client (slow response), so it retries, but the *original* actually completed server-side. Now it ran twice.
- **Proxy/LB retries** — many proxies retry idempotent-looking requests on upstream timeout, unbeknownst to the app.
- **TCP itself never duplicates at the app layer** (sequence numbers dedup) — but *connection-level* retransmits after a partial failure, or an `at-least-once` message queue redelivering, do.

The robust answer isn't "prevent duplicates on the wire" (impossible in general) — it's **make the operation idempotent**:

- **Idempotency keys** — the client sends a unique key per logical operation; the server records "key → result" and on a repeat key returns the stored result without re-executing. This is how payment APIs (Stripe) stay safe under retries.
- **Natural idempotency** — design writes as upserts/`PUT` keyed by a deterministic ID, conditional updates (compare-and-set / `If-Match` ETag), or unique constraints that reject the second insert.
- **Dedup window** — store processed request IDs for a TTL covering the retry horizon.

The mantra: in a distributed system you get **at-least-once** delivery for free and **exactly-once** *processing* only by building idempotency on top. Assume every request can arrive twice and make the second one a no-op.

#### Q102. [Coding] Implement a deadline/timeout budget that propagates across chained service calls in Java.

A real reliability pattern: a request arrives with an overall deadline, and each downstream hop must use only the *remaining* time — not its own fixed timeout — so the whole chain fails fast instead of each hop independently waiting its full budget.

```java
import java.time.*;
import java.util.concurrent.TimeoutException;

public final class Deadline {
    private final Instant expiresAt;

    private Deadline(Instant expiresAt) { this.expiresAt = expiresAt; }

    public static Deadline after(Duration budget) {
        return new Deadline(Instant.now().plus(budget));
    }

    /** Time left before the deadline; never negative. */
    public Duration remaining() {
        Duration left = Duration.between(Instant.now(), expiresAt);
        return left.isNegative() ? Duration.ZERO : left;
    }

    public boolean isExpired() { return remaining().isZero(); }

    /** Use this as each downstream call's timeout. Throws if already out of budget. */
    public long remainingMillisOrThrow() throws TimeoutException {
        long ms = remaining().toMillis();
        if (ms <= 0) throw new TimeoutException("deadline exceeded before call");
        return ms;
    }

    public static void main(String[] args) throws Exception {
        Deadline dl = Deadline.after(Duration.ofMillis(500)); // total request budget

        // hop 1: pass remaining budget as the per-call timeout
        callService("auth",    dl.remainingMillisOrThrow());
        // hop 2: only what's LEFT after hop 1
        callService("profile", dl.remainingMillisOrThrow());
        // hop 3
        callService("orders",  dl.remainingMillisOrThrow());
    }

    static void callService(String name, long timeoutMs) {
        System.out.printf("calling %s with %d ms left%n", name, timeoutMs);
        // set this timeoutMs as the socket read/connect timeout for the call
    }
}
```

The key idea: propagate an **absolute deadline** (an instant), not a relative duration, so each hop computes `remaining()` itself. This prevents the failure mode where a 500ms request calls three services each with a 500ms timeout — potentially waiting 1.5s. In gRPC this is built in (`Context` deadlines auto-propagate via the `grpc-timeout` header); over HTTP you pass it explicitly (a header) and convert to a per-call timeout. It also lets a slow first hop cause the rest to fail fast rather than wastefully proceed.

#### Q103. [Practical] A WebSocket-based feature drops connections roughly every 60 seconds in production but never locally. What's going on and how do you fix it?

A **regular, round interval** (60s) screams **idle-timeout**, and the local-vs-prod difference confirms an intermediary that only exists in production. The culprit is almost always a **proxy/load balancer idle timeout** sitting between client and server that locally you don't have.

```
Local:  Browser ────────────────► Server      (no proxy, never idles out)
Prod:   Browser ─► LB/Proxy ─────► Server      (LB drops idle conns after 60s)
```

WebSocket connections are long-lived but may carry no traffic for stretches; the LB (ALB default 60s, many Nginx/Cloudflare defaults similar) sees an idle connection and closes it. Fixes:

- **Application-level heartbeats** — send WebSocket **ping/pong** frames (or app-level keepalive messages) every ~30s, comfortably under the idle timeout, so the connection is never idle long enough to be reaped. This is the right fix because it works regardless of which intermediary enforces the timeout.
- **Raise the LB idle timeout** for the WebSocket route (e.g. ALB idle timeout up to several minutes) — helps but is brittle if other proxies/CDNs sit in the path.
- **Ensure the proxy is configured for WebSocket upgrade** — it must pass `Upgrade`/`Connection` headers and not buffer; some proxies need explicit WebSocket support enabled.
- **Client auto-reconnect with backoff** — defense in depth; reconnect transparently when a drop does happen, resuming state via a sequence/cursor.

The teaching point: long-lived connections require **active keepalive** to survive the many idle timeouts along a real internet path; "works locally" almost always means "no middlebox locally."

#### Q112. [Coding] Implement a connection-validation ("test on borrow") check for a pooled connection so you never hand out a dead socket.

The single most common production cause of intermittent "connection reset" with pooled clients is reusing a connection the server/LB already closed during idle. The defense is to **validate before borrow** (cheaply) and evict if dead.

```java
import java.io.IOException;
import java.net.Socket;

public class PooledConnection {
    private final Socket socket;
    private volatile long lastUsedMillis;

    public PooledConnection(Socket socket) {
        this.socket = socket;
        this.lastUsedMillis = System.currentTimeMillis();
    }

    /**
     * Returns true if the socket still looks usable. The trick: set a 1ms read
     * timeout and peek. If the peer sent a FIN, read() returns -1 (dead). If no
     * data is waiting, we get SocketTimeoutException — which means ALIVE (a healthy
     * idle connection has nothing to read), so we swallow it and report healthy.
     */
    public boolean isValid(long maxIdleMillis) {
        if (System.currentTimeMillis() - lastUsedMillis > maxIdleMillis) return false;
        if (socket.isClosed() || !socket.isConnected() || socket.isInputShutdown())
            return false;
        try {
            int oldTimeout = socket.getSoTimeout();
            socket.setSoTimeout(1);
            try {
                socket.getInputStream().mark(1);
                int b = socket.getInputStream().read();   // peek one byte
                if (b == -1) return false;                 // FIN received → dead
                socket.getInputStream().reset();           // un-consume the byte
                return true;
            } catch (java.net.SocketTimeoutException ste) {
                return true;                               // nothing to read == healthy idle
            } finally {
                socket.setSoTimeout(oldTimeout);
            }
        } catch (IOException e) {
            return false;                                  // any I/O error → discard
        }
    }

    public void markUsed() { this.lastUsedMillis = System.currentTimeMillis(); }
}
```

The counter-intuitive core: a **`SocketTimeoutException` on the peek means the connection is HEALTHY** (an idle live socket has no bytes pending), whereas `read()` returning `-1` means the peer already sent a FIN and the socket is dead. Real pools (HikariCP, Apache HttpClient) implement exactly this idea — plus a cheaper **`maxIdle`/`keepalive` eviction** so connections older than the upstream's idle timeout are discarded proactively rather than validated. Align this `maxIdleMillis` to be shorter than the LB/server idle timeout and most "reset on reuse" errors vanish.

### 🔴 — extended

#### Q104. [Practical] During a major incident, a single slow downstream dependency cascades into total outage of your fleet. Diagnose the failure mode and design the fix.

This is **cascading failure via resource exhaustion**, and it's one of the most important distributed-systems failure modes to articulate.

**The mechanism:** the downstream slows (not fails — *slows*). Your threads/connections block waiting on it. Because each request now holds a worker/connection far longer, your finite pool fills. New requests — *including ones that don't even touch the slow dependency* — queue or are rejected. Health checks time out, the LB marks instances unhealthy, traffic concentrates on the remaining nodes, which then tip over the same way. A slow dependency has been amplified into a fleet-wide outage.

```
slow dep → threads block → pool exhausts → ALL requests stall (even unrelated)
        → health checks fail → instances ejected → load concentrates → cascade
```

**The fix is a layered resilience stack:**
- **Timeouts everywhere** — never wait unbounded; a slow call must fail fast and free its resource. This is the single most important control.
- **Circuit breakers** — after a threshold of failures/timeouts, *stop calling* the dependency entirely for a cooldown, returning fast errors/fallbacks instead of blocking. This breaks the amplification.
- **Bulkheads** — isolate each dependency's concurrency so it can't consume the whole pool; unrelated traffic keeps flowing.
- **Load shedding** — reject excess work early (return 503) to protect the core rather than collapse trying to serve everything.
- **Health checks decoupled from dependencies** — a liveness check shouldn't fail just because a downstream is slow, or you eject healthy instances.
- **Graceful degradation / fallbacks** — serve cached/default data when the dependency is open-circuited.

The senior synthesis: **isolate, time-bound, and shed.** You can't prevent a dependency from being slow; you architect so that its slowness is *contained* to the feature that needs it, not amplified into a total outage.

#### Q105. [Theory] How would you architect connection management for a service that must hold millions of concurrent long-lived connections (e.g. IoT/push)?

Millions of mostly-idle connections is a different problem from high request throughput — it's bounded by **memory and FD limits**, not CPU. The architecture:

- **Event-driven, not thread-per-connection** — epoll/kqueue (or io_uring) so one thread services many thousands of sockets. Thread-per-connection dies at ~tens of thousands due to stack memory and scheduler overhead. (On the JVM, virtual threads make blocking code viable to ~millions, but the I/O substrate is still epoll underneath.)
- **Tune the kernel** — raise `ulimit -n` (FDs) to millions, expand `somaxconn`/backlog, tune `tcp_mem`/socket buffers *down* per-connection (idle connections don't need big buffers — memory is the constraint at this scale), and watch conntrack if NAT is involved.
- **Minimize per-connection memory** — at 1M connections, even 10KB/conn is 10GB. Shrink buffers, avoid per-connection threads, and use compact protocol state.
- **Horizontal sharding + a connection tier** — terminate connections on a stateless "edge/gateway" fleet (many boxes each holding a slice), with a routing layer (consistent hashing on device ID) so a message for a device finds the box holding its connection. Decouple connection-holding from business logic.
- **Heartbeats sized against NAT/idle timeouts** — keep mobile/NAT mappings alive without flooding; balance battery (mobile) vs liveness.
- **Backpressure and slow-consumer handling** — bound per-connection send queues; drop or disconnect slow consumers rather than buffering unboundedly (a memory-exhaustion vector).
- **Graceful rebalancing** — on deploy/scale, migrate connections gradually (drain) since reconnecting millions at once is a self-inflicted thundering herd.

The framing: the bottleneck is **memory × connections** and **FD limits**, so the whole design optimizes per-connection footprint and uses a dedicated, horizontally-sharded connection tier with a routing fabric to reach any device.

#### Q106. [Coding] Implement a circuit breaker (closed/open/half-open) in Java suitable for guarding a flaky network dependency.

The canonical resilience primitive. It tracks failures and, once they exceed a threshold, **opens** to fail fast for a cooldown, then **half-opens** to test recovery with a trial request before fully closing.

```java
import java.time.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.*;

public class CircuitBreaker {
    enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final Duration openDuration;
    private volatile State state = State.CLOSED;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openedAtMillis = new AtomicLong();

    public CircuitBreaker(int failureThreshold, Duration openDuration) {
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
    }

    public <T> T call(Callable<T> action, Callable<T> fallback) throws Exception {
        if (state == State.OPEN) {
            if (System.currentTimeMillis() - openedAtMillis.get() >= openDuration.toMillis()) {
                state = State.HALF_OPEN;          // time to probe recovery
            } else {
                return fallback.call();            // fail fast, don't touch the dependency
            }
        }
        try {
            T result = action.call();
            onSuccess();
            return result;
        } catch (Exception e) {
            onFailure();
            if (state == State.OPEN) return fallback.call();
            throw e;
        }
    }

    private void onSuccess() {
        consecutiveFailures.set(0);
        state = State.CLOSED;                      // a HALF_OPEN success closes the circuit
    }

    private void onFailure() {
        // any failure in HALF_OPEN re-opens immediately
        if (state == State.HALF_OPEN
                || consecutiveFailures.incrementAndGet() >= failureThreshold) {
            state = State.OPEN;
            openedAtMillis.set(System.currentTimeMillis());
        }
    }

    public State state() { return state; }
}
```

The three states: **CLOSED** (normal, counting failures), **OPEN** (tripped — short-circuit to the fallback without calling the dependency, giving it time to recover and protecting your own threads), and **HALF_OPEN** (after the cooldown, let *one* request through; success closes the circuit, failure re-opens it). Production-grade versions (Resilience4j) use a **rolling window / failure-rate** rather than consecutive count, limit concurrent half-open probes, and emit metrics — but the state machine above is the essence and the answer interviewers want.

#### Q107. [Theory] A latency-sensitive trading/RTB system needs single-digit-microsecond network latency. What techniques take you below the kernel-TCP floor?

At microsecond scale the **kernel network stack itself is the bottleneck** — syscalls, context switches, interrupts, and copies each cost microseconds. You progressively remove the kernel from the path:

- **Kernel bypass (DPDK / AF_XDP / Solarflare Onload)** — map the NIC into user space and poll it directly, eliminating syscalls, interrupts, and the kernel TCP stack. The app busy-polls a dedicated core. This is the headline technique for HFT.
- **Userspace TCP/UDP stacks** — since you bypassed the kernel stack, you run a lightweight user-space stack (or raw UDP) tuned for latency over throughput.
- **Busy-polling instead of interrupts** — dedicate CPU cores to spin on the NIC ring (`isolcpus`, `nohz_full`) so there's no interrupt/wakeup latency; you trade CPU and power for determinism.
- **CPU pinning, NUMA locality, huge pages** — pin the polling thread to the core nearest the NIC's NUMA node, keep buffers local, use huge pages to cut TLB misses.
- **Kernel/scheduler tuning** — disable C-states/frequency scaling for deterministic latency, isolate cores from the scheduler, disable IRQ balancing onto the hot core.
- **Hardware acceleration / FPGA / SmartNIC** — push parsing, matching, or even the strategy onto the NIC/FPGA for nanosecond-class, jitter-free processing; co-location at the exchange removes propagation delay.
- **Avoid GC languages on the hot path** — C/C++/Rust, or carefully zero-allocation Java with pre-touched buffers, to avoid GC-induced jitter (tail latency is the enemy).

The trade-off you must state: kernel bypass **dedicates entire cores to busy-polling** (terrible for power/density), **gives up the kernel's TCP, security, and fairness**, and demands specialized ops. It's only justified when **microseconds are literally money** (HFT, RTB, some telecom NFV) — for normal services it's the wrong tool.

#### Q108. [Behavioral] You're the senior engineer; a costly production outage was caused by a missing client timeout. Walk through how you lead the postmortem and prevent recurrence.

The behavioral substance is **blameless rigor plus systemic prevention**, not finding who forgot the timeout.

1. **Run a blameless postmortem** — focus on *why the system allowed* a missing timeout to cause an outage, not who wrote the line. Psychological safety is what gets you the honest timeline; blame gets you hidden facts next time.
2. **Build a precise timeline** — when the dependency slowed, when threads exhausted, when health checks failed, when it cascaded. Tie each to telemetry. Distinguish trigger (slow dependency) from the *real* root cause (no timeout + no circuit breaker let it amplify).
3. **Identify contributing factors, not a single cause** — the missing timeout was the spark, but the absence of bulkheads, the default unbounded client config, and health checks coupled to the dependency all contributed. Outages are systemic.
4. **Action items that are concrete, owned, and dated** — e.g. enforce timeouts via a shared HTTP-client wrapper with safe defaults (you can't *forget* what the default sets), add circuit breakers, add a lint/CI check that flags clients constructed without timeouts, add a chaos test that injects downstream latency.
5. **Make the safe path the default path** — the durable fix is platform-level: a paved-road client library where timeouts/retries/breakers are on by default, so individual engineers can't reintroduce the gap. Process docs alone don't scale; defaults do.
6. **Close the loop** — track action items to completion, share the postmortem widely as a learning artifact, and validate the fix with a game-day that re-injects the original failure.

The leadership signal: you convert one painful incident into a **systemic guardrail** (safe-by-default tooling + automated detection) and a stronger blameless culture, so the *class* of failure can't recur — not just this instance.

#### Q109. [Theory] Explain how you'd design active health checking and outlier detection so a load balancer never sends traffic to a degraded backend — including the subtle failure modes.

Naive health checks cause as many outages as they prevent, so a robust design separates *kinds* of health and guards against the failure modes:

- **Active vs passive checks** — *active*: the LB periodically probes a `/health` endpoint. *Passive (outlier detection)*: the LB watches *real* traffic and ejects a backend that returns consecutive 5xx/timeouts even if its `/health` lies. You want both — active catches a dead instance fast; passive catches one that's healthy-by-probe but failing real requests.
- **Liveness vs readiness vs deep health** — a *liveness* check ("am I running") must NOT depend on downstream dependencies, or a shared dependency's blip ejects your *entire* fleet at once (a catastrophic correlated failure). A *readiness* check can include "can I serve" but must be designed so a downstream outage doesn't black-hole all instances simultaneously.
- **Hysteresis / thresholds** — require N consecutive failures to eject and M consecutive successes to re-admit, so a single blip doesn't flap an instance in and out. Flapping causes connection churn and uneven load.
- **The "eject too many" guard** — outlier detection must cap the fraction of the pool it will eject (e.g. never eject more than 50%). Otherwise a bad *deploy* or a poison request that makes everyone fail leads the LB to eject the whole fleet → total outage. Envoy's `max_ejection_percent` exists exactly for this.
- **Slow-start / connection draining** — re-admitted or newly added backends should ramp traffic gradually (cold caches, JIT warmup) and drain in-flight connections on removal.
- **Health-check cost & coupling** — probes shouldn't be so frequent/expensive they add load, and a synchronous deep check that hammers the database can itself cause the outage it's meant to detect.

The subtle, senior insight: the dangerous failure mode isn't "fails to eject a bad node" — it's **ejecting too many nodes at once because health is coupled to a shared dependency.** Decouple liveness from dependencies and bound the ejection fraction, or your safety mechanism becomes the outage amplifier.

#### Q113. [Theory] You're asked to cut user-perceived latency for a global web app by 40% without rewriting the app. What network-layer levers do you pull, and how do you prioritize them?

Frame it as **eliminating round trips and shortening the ones that remain**, because latency at a distance is dominated by RTT count × RTT length, not bandwidth. Prioritize by impact-per-effort:

1. **Terminate close to the user (CDN + edge TLS)** — the biggest lever. Serving static assets and terminating TLS at an edge PoP near the user collapses the RTT for the handshake and for cached content. Even for dynamic requests, terminating TLS at the edge and keeping a **warm, pooled origin connection** means the user pays one short RTT to the edge instead of a full handshake to a distant origin.
2. **Adopt HTTP/3 / QUIC** — 1-RTT (0-RTT on resumption) connection setup, no transport HOL blocking, and connection migration for mobile. On lossy mobile networks this is a large tail-latency win, and it's a config/proxy change, not an app rewrite.
3. **Kill redundant round trips** — enable keep-alive/connection reuse end-to-end, ensure TLS 1.3 (1-RTT) with **session resumption** and **OCSP stapling** (so the client doesn't make a separate OCSP round trip), and use **`103 Early Hints`** to let the browser preconnect/preload while the origin thinks.
4. **Cut DNS cost** — low but reasonable TTLs served from an anycast DNS provider, and `dns-prefetch`/`preconnect` hints so name resolution and connection setup overlap with page parse.
5. **Compression and right-sizing** — Brotli for text, modern image formats, and `Vary`/`Cache-Control` correctness so the CDN actually caches. Less bytes = fewer congestion-window round trips to drain a response (a cold TCP connection ramps slowly).
6. **Reduce congestion-window warmup pain** — reuse connections (warm `cwnd`), and prefer BBR on the server for lossy paths.

How to prioritize: **measure first** with Real User Monitoring split by region and by waterfall phase (DNS / connect / TLS / TTFB / transfer). Spend effort where the RTT count is highest — usually that's "distance to origin" and "handshake round trips," which CDN + HTTP/3 + TLS-1.3-resumption attack directly. The discipline is that you can't buy your way out of RTT with bandwidth, so every lever here is about **doing fewer round trips and doing them over shorter distances** — and none of them require touching application code.

---

## ✅ Key Takeaways

- **Know the models cold**: OSI (7) vs TCP/IP (4), which protocol lives at which layer, and that each layer is an abstraction enabling independent evolution (HTTP over TCP *or* QUIC).
- **TCP gives reliability and ordering at the cost of latency and HOL blocking; UDP is the thin, fast base** that QUIC builds reliability on top of. Pick by whether correctness or latency dominates.
- **The HTTP evolution is a story of removing HOL blocking**: 1.1 (per-connection) → 2 (app-layer multiplexing, still TCP-HOL) → 3/QUIC (per-stream recovery over UDP, no transport HOL) — plus faster, always-encrypted handshakes and connection migration.
- **Most production latency wins come from reducing round trips**: keep-alive, connection pooling, TLS 1.3 1-RTT/0-RTT, CDNs terminating close to users, and warm edge-to-origin connections.
- **CIDR, subnetting, NAT, ARP, ICMP, ports, and the socket 5-tuple** are the addressing fundamentals that explain how one server port serves thousands of clients and how private networks share public IPs.
- **L4 is fast and protocol-agnostic; L7 is content-aware.** Choose load-balancing algorithms by workload (least-connections for variable cost, consistent hashing for locality/stickiness).
- **Zero trust means mTLS everywhere** (often via a service mesh), explicit timeout/retry budgets, circuit breakers, and treating the internal network as untrusted.

## ⚠️ Common Pitfalls

- **Confusing flow control with congestion control** — the first protects the *receiver* (rwnd), the second protects the *network* (cwnd); the effective window is their minimum.
- **Setting infinite or huge read timeouts** — one slow dependency then exhausts your thread pool and cascades into a full outage. Always layer connect/read/overall timeouts.
- **Mismatched keep-alive timeouts** between client pool and upstream LB → reused-but-dead connections → intermittent "connection reset." Client idle timeout must be shorter; validate on borrow.
- **`Access-Control-Allow-Origin: *` together with credentials** — forbidden by the spec; echo the specific origin. Also remember CORS is enforced by the *browser*, not the server.
- **Treating DNS as instant** — TTL caching (including the JVM's aggressive default DNS cache) means rotation changes propagate slowly; plain round-robin DNS isn't health-aware.
- **Oversizing connection/DB pools** — bigger isn't better; it overwhelms the backend. DB pools are usually 10–20, not hundreds.
- **Recommending HTTP/2 Server Push** — it's deprecated/removed in browsers; use `103 Early Hints` instead.
- **Enabling 0-RTT for non-idempotent requests** — early data is replayable; restrict it to safe operations with application-level replay protection.
- **Disabling TIME_WAIT protection** instead of fixing connection churn — TIME_WAIT is correct behavior; reduce churn with keep-alive, don't blindly use removed knobs like `tcp_tw_recycle`.

## 📚 Further Reading

- **RFC 9110/9111/9112** (HTTP Semantics, Caching, HTTP/1.1), **RFC 9113** (HTTP/2), **RFC 9114** (HTTP/3), **RFC 9000** (QUIC), **RFC 8446** (TLS 1.3) — the authoritative, current specs.
- **W. Richard Stevens**, *TCP/IP Illustrated, Vol. 1* — the definitive deep dive on TCP/IP internals.
- **Ilya Grigorik**, *High Performance Browser Networking* (free at hpbn.co) — the best single resource on TCP, TLS, HTTP/1-2, WebSockets, and latency optimization for engineers.
- **Cloudflare Learning Center** and the **Cloudflare blog** — consistently excellent, current explanations of HTTP/3, QUIC, BGP, anycast, and DDoS.
- **Brendan Gregg's** site and *BPF Performance Tools* — for eBPF/XDP and kernel networking performance.
- **Beej's Guide to Network Programming** — a classic, approachable intro to sockets.
- **Kurose & Ross**, *Computer Networking: A Top-Down Approach* — the standard university text for the full breadth.
