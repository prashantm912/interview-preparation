# gRPC

[← Back to master index](../README.md)

A deep, interview-focused reference on gRPC — Google's open-source, high-performance RPC framework built on Protocol Buffers and HTTP/2. This guide covers the protobuf IDL and service model, the four RPC types, the HTTP/2 transport, interceptors, deadlines and cancellation, the status/error model, metadata, channels and stubs, load balancing, TLS/mTLS, gRPC-Web, reflection, health checking, retries, and protobuf versioning. Examples use Protobuf 3 and Java (grpc-java) and are current through 2026.

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

### Q1. [Theory] What is gRPC, and what problem does it solve?

gRPC is a modern, open-source **Remote Procedure Call** framework originally built at Google (the "g" historically stood for different things per release; officially it's just "gRPC Remote Procedure Calls"). It lets a client call a method on a server in a different process or machine as if it were a local function call, hiding the network plumbing.

Its three defining pillars are:

1. **Protocol Buffers (protobuf)** as the Interface Definition Language (IDL) and binary serialization format — you define services and messages once in a `.proto` file and generate strongly-typed client and server code in many languages.
2. **HTTP/2** as the transport — enabling multiplexing, streaming, header compression, and bidirectional flow.
3. **Contract-first, polyglot** design — the `.proto` is the single source of truth, so a Java server and a Go/Python/Node client all agree on the wire format.

The problem it solves: in microservice architectures you need **fast, strongly-typed, low-latency** inter-service communication. JSON-over-REST is human-readable but verbose and weakly typed; gRPC gives you a compact binary format, generated stubs (no hand-written HTTP clients), and first-class streaming. It excels at **internal east-west traffic** between services.

### Q2. [Theory] What are Protocol Buffers and why does gRPC use them instead of JSON?

Protocol Buffers are a **language-neutral, platform-neutral, binary serialization format** plus a schema language. You declare your data shape in a `.proto` file, and the `protoc` compiler generates code to serialize/deserialize it efficiently.

Why protobuf over JSON for gRPC:

- **Compact**: fields are encoded by integer *tag numbers* using varint/length-delimited encoding, not repeated text field names. A message is often 3–10× smaller than equivalent JSON.
- **Fast**: binary parsing avoids string tokenization; no need to parse `"`/`{`/`:` characters.
- **Strongly typed**: the schema is the contract; you can't accidentally send a string where an int is expected.
- **Schema evolution**: tag numbers (not names) identify fields on the wire, so you can rename fields and add/remove optional fields without breaking old clients.

```protobuf
syntax = "proto3";

package shop.v1;

option java_package = "com.example.shop.v1";
option java_multiple_files = true;

message Product {
  string id   = 1;   // the "= 1" is the field TAG, not a default value
  string name = 2;
  int64  price_cents = 3;
}
```

The numbers `1, 2, 3` are wire tags. They must be stable for the life of the field — changing them is a breaking change.

### Q3. [Theory] What does a gRPC service definition look like in a `.proto` file?

A service is a named collection of RPC methods, each taking exactly one request message and returning exactly one response message (streaming is expressed with the `stream` keyword).

```protobuf
syntax = "proto3";
package shop.v1;

service ProductService {
  rpc GetProduct (GetProductRequest) returns (Product);
  rpc ListProducts (ListProductsRequest) returns (stream Product);   // server streaming
}

message GetProductRequest { string id = 1; }
message ListProductsRequest { int32 page_size = 1; }
message Product {
  string id = 1;
  string name = 2;
  int64  price_cents = 3;
}
```

Key conventions: one request and one response type per method (wrap multiple params in a message — never use bare scalars), and prefer dedicated `XxxRequest`/`XxxResponse` messages even for single-field calls so you can add fields later without changing the method signature.

### Q4. [Theory] What are the four kinds of RPC in gRPC?

gRPC supports four method shapes, distinguished by where the `stream` keyword appears:

```
                  Request        Response
Unary             single    →    single
Server streaming  single    →    stream
Client streaming  stream    →    single
Bidirectional     stream    →    stream
```

```protobuf
service ChatService {
  rpc Echo            (Msg)        returns (Msg);          // unary
  rpc Subscribe       (Topic)      returns (stream Msg);   // server streaming
  rpc Upload          (stream Msg) returns (UploadResult); // client streaming
  rpc Chat            (stream Msg) returns (stream Msg);   // bidirectional
}
```

- **Unary**: classic request/response — the most common, REST-like.
- **Server streaming**: client sends one request; server sends a sequence of messages (live feeds, large result sets, server-sent progress).
- **Client streaming**: client sends a sequence; server replies once at the end (file/metric upload, aggregation).
- **Bidirectional streaming**: both sides send independent streams over one connection (chat, real-time sync, multiplexed control channels). The two streams are independent — neither side has to wait for the other.

### Q5. [Theory] How does gRPC differ from REST? When would you choose each?

```
Aspect          REST/JSON              gRPC
--------------- ---------------------- ---------------------------
Transport       HTTP/1.1 (usually)     HTTP/2 (required)
Payload         JSON (text)            Protobuf (binary)
Contract        OpenAPI (optional)     .proto (mandatory, first)
Streaming       SSE/WebSocket bolt-on  Native, 4 modes
Browser         Native                 Needs gRPC-Web/proxy
Typing          Weak/runtime           Strong/compile-time
Human-readable  Yes                    No (binary)
```

**Choose gRPC** for internal microservice-to-microservice traffic where you want low latency, strong typing, generated clients, and streaming — especially in polyglot environments. **Choose REST** for public-facing APIs, browser clients, broad third-party integration, easy debuggability (curl/Postman), and when human-readable payloads and HTTP caching matter. Many systems use both: gRPC internally, a REST/GraphQL gateway at the edge.

### Q6. [Practical] How do you generate Java code from a `.proto` and implement a server?

You compile the `.proto` with `protoc` plus the gRPC plugin (in Maven/Gradle this is wired via `protobuf-maven-plugin` / `protobuf-gradle-plugin`). This generates message classes and an abstract `ProductServiceImplBase`. You extend that base class and override the methods.

```java
public class ProductServiceImpl extends ProductServiceGrpc.ProductServiceImplBase {

    @Override
    public void getProduct(GetProductRequest req,
                           StreamObserver<Product> responseObserver) {
        Product p = Product.newBuilder()
                .setId(req.getId())
                .setName("Widget")
                .setPriceCents(1999)
                .build();
        responseObserver.onNext(p);       // send the single response
        responseObserver.onCompleted();   // close the call (sends trailers / OK status)
    }
}
```

```java
Server server = ServerBuilder.forPort(50051)
        .addService(new ProductServiceImpl())
        .build()
        .start();
server.awaitTermination();
```

Even a *unary* method uses a `StreamObserver` in grpc-java's async API: call `onNext()` once, then `onCompleted()`. Calling `onError()` instead sends a non-OK status.

### Q7. [Practical] How do you create a client channel and call the server?

A **`ManagedChannel`** represents a long-lived, reusable connection (pool) to a server. You create one channel and derive **stubs** from it. Channels are expensive — create one per target and share it; never create one per request.

```java
ManagedChannel channel = ManagedChannelBuilder
        .forAddress("localhost", 50051)
        .usePlaintext()                 // dev only; use TLS in prod
        .build();

// Blocking (synchronous) stub
ProductServiceGrpc.ProductServiceBlockingStub stub =
        ProductServiceGrpc.newBlockingStub(channel);

Product p = stub.getProduct(
        GetProductRequest.newBuilder().setId("42").build());
System.out.println(p.getName());

channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
```

### Q8. [Theory] What are stubs, and what stub types does grpc-java provide?

A **stub** is the generated client-side proxy that exposes the service's methods as local calls and marshals them onto the channel. grpc-java generates three stub flavors from one service:

- **Blocking stub** (`newBlockingStub`) — synchronous; the call blocks until the response (or a streaming iterator) is ready. Easiest for unary and server-streaming.
- **Async stub** (`newStub`) — fully non-blocking; you supply a `StreamObserver` for responses. Required for client-streaming and bidirectional.
- **Future stub** (`newFutureStub`) — returns a `ListenableFuture` for unary calls; good for fan-out/parallel calls.

All three are cheap, immutable, and derive from the same channel. You attach per-call config (deadline, metadata) by calling `withXxx()` on a stub, which returns a *new* stub.

### Q9. [Theory] What is metadata in gRPC and how does it relate to HTTP headers?

**Metadata** is a set of key-value pairs sent alongside an RPC — the gRPC equivalent of HTTP headers. On HTTP/2, metadata literally travels as HTTP/2 headers (request metadata) and trailers (response status/trailing metadata). It carries cross-cutting context: auth tokens, trace IDs, tenant IDs, etc.

- Keys are ASCII; keys ending in `-bin` carry **binary** values (base64 on the wire).
- Reserved `grpc-*` keys are managed by the framework (e.g., `grpc-status`, `grpc-timeout`).

```java
Metadata headers = new Metadata();
Metadata.Key<String> AUTH =
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
headers.put(AUTH, "Bearer " + token);

stub = MetadataUtils.attachHeaders(stub, headers);
```

Metadata is for *out-of-band* context. Business data belongs in the protobuf message, not in metadata.

### Q10. [Theory] What is a deadline, and why prefer it over a timeout?

A **deadline** is an absolute point in time by which an RPC must complete; a **timeout** is a relative duration. gRPC propagates deadlines, so the correct mental model is "this whole call tree must finish by time T." If service A calls B which calls C, the deadline set at A flows down: B and C see the *remaining* time, and any hop that detects the deadline is exceeded can stop work early instead of doing useless computation.

```java
Product p = stub
    .withDeadlineAfter(200, TimeUnit.MILLISECONDS)
    .getProduct(req);
```

If the deadline passes, the call fails with status `DEADLINE_EXCEEDED`. Best practice: **always set a deadline on every client call.** Without one, a hung server can make the client wait forever. Deadlines (absolute) compose correctly across hops; raw per-hop timeouts don't, because each hop would restart the clock.

### Q11. [Theory] What is the gRPC status model? Name some common status codes.

Every gRPC call completes with a **status code** (an integer enum) plus an optional message. This is conceptually like HTTP status codes but is gRPC-specific and transport-independent. The status travels in HTTP/2 *trailers* (`grpc-status`).

```
OK (0)                  Success
CANCELLED (1)           Caller cancelled the RPC
INVALID_ARGUMENT (3)    Bad client input (validation)
DEADLINE_EXCEEDED (4)   Deadline passed before completion
NOT_FOUND (5)           Resource doesn't exist
ALREADY_EXISTS (6)      Conflict on create
PERMISSION_DENIED (7)   Authenticated but not authorized
RESOURCE_EXHAUSTED (8)  Quota/rate limit
FAILED_PRECONDITION (9) System not in required state
UNIMPLEMENTED (12)      Method not implemented
INTERNAL (13)           Server bug / invariant broken
UNAVAILABLE (14)        Transient; retryable
UNAUTHENTICATED (16)    Missing/invalid credentials
```

Returning the right code matters: `UNAVAILABLE` and `DEADLINE_EXCEEDED` are commonly retried by clients, whereas `INVALID_ARGUMENT` and `NOT_FOUND` are not. Use `INVALID_ARGUMENT` for malformed input regardless of system state, and `FAILED_PRECONDITION` when the input is fine but the system state forbids the operation.

### Q12. [Practical] How do you return an error from a gRPC server method in Java?

You signal an error by calling `onError()` with a `StatusRuntimeException` carrying the appropriate `Status`. Do **not** throw arbitrary exceptions — uncaught ones become an opaque `UNKNOWN` status, leaking nothing useful to the client.

```java
@Override
public void getProduct(GetProductRequest req,
                       StreamObserver<Product> responseObserver) {
    if (req.getId().isBlank()) {
        responseObserver.onError(Status.INVALID_ARGUMENT
                .withDescription("id must not be blank")
                .asRuntimeException());
        return;
    }
    Product p = repo.find(req.getId());
    if (p == null) {
        responseObserver.onError(Status.NOT_FOUND
                .withDescription("no product " + req.getId())
                .asRuntimeException());
        return;
    }
    responseObserver.onNext(p);
    responseObserver.onCompleted();
}
```

Once you call `onError()`, the call is over — don't also call `onNext`/`onCompleted`.

### Q13. [Theory] Why does gRPC require HTTP/2? What features does it rely on?

gRPC is built directly on HTTP/2 and depends on several of its features:

- **Multiplexing**: many concurrent RPCs (streams) share one TCP connection, each as an independent HTTP/2 stream. This avoids HTTP/1.1 head-of-line blocking at the request level and removes the need for many connections.
- **Bidirectional streams**: HTTP/2's full-duplex streams map naturally onto gRPC's streaming RPC types.
- **Binary framing**: HTTP/2 frames carry length-prefixed protobuf messages cleanly.
- **Header compression (HPACK)**: metadata/headers are compressed, cheap to send repeatedly.
- **Flow control**: per-stream and per-connection windows let a slow consumer push back on a fast producer.

```
   one TCP connection (HTTP/2)
   ┌─────────────────────────────┐
   │ stream 1  ──── RPC A ─────►  │
   │ stream 3  ──── RPC B ─────►  │   multiplexed, independent
   │ stream 5  ◄─── RPC C stream  │
   └─────────────────────────────┘
```

Because browsers don't expose raw HTTP/2 frames to JavaScript, native gRPC can't run directly in a browser — hence gRPC-Web.

### Q14. [Theory] What does `proto3` give you by default for unset fields?

In `proto3`, scalar fields have **default values** when unset: `0` for numbers, `false` for bool, `""` for strings, empty for bytes, and the first enum value (which must be `0`) for enums. Crucially, by default proto3 does **not distinguish "absent" from "default"** for plain scalars — a `0` and "not sent" look the same on the wire and in the API.

When you genuinely need to tell "absent" from "zero" (e.g., a nullable field in an update), use the `optional` keyword (re-introduced in proto3), which adds presence tracking, or use a wrapper type like `google.protobuf.Int32Value`.

```protobuf
message UpdateUser {
  string id = 1;
  optional string nickname = 2;  // can distinguish "" from absent
}
```

### Q15. [Practical] How do you define and use an enum in protobuf?

Enums map identifiers to integers. In proto3 the **zero value is mandatory** and should be an `UNSPECIFIED` sentinel, because an unset enum field defaults to 0.

```protobuf
enum OrderStatus {
  ORDER_STATUS_UNSPECIFIED = 0;  // required default / "unknown"
  ORDER_STATUS_PENDING     = 1;
  ORDER_STATUS_SHIPPED     = 2;
  ORDER_STATUS_DELIVERED   = 3;
}
```

The `UNSPECIFIED = 0` convention matters for forward compatibility: if a new server adds `ORDER_STATUS_RETURNED = 4` and sends it to an old client, the old client preserves the unknown number rather than crashing, and your code can treat unknown/zero distinctly from real states.

---

## 🟡 Intermediate (3–7 yrs)

### Q16. [Practical] Implement a server-streaming RPC in Java.

The server calls `onNext()` multiple times before `onCompleted()`. Respect client cancellation and flow control.

```protobuf
rpc ListProducts (ListProductsRequest) returns (stream Product);
```

```java
@Override
public void listProducts(ListProductsRequest req,
                         StreamObserver<Product> resp) {
    ServerCallStreamObserver<Product> obs =
            (ServerCallStreamObserver<Product>) resp;

    for (Product p : repo.stream(req.getFilter())) {
        if (obs.isCancelled()) return;        // client gave up; stop work
        // honor flow control to avoid unbounded buffering
        while (!obs.isReady()) { /* park / await onReadyHandler */ }
        obs.onNext(p);
    }
    obs.onCompleted();
}
```

On the client (blocking stub) you simply iterate:

```java
Iterator<Product> it = blockingStub.listProducts(req);
while (it.hasNext()) process(it.next());
```

Checking `isCancelled()` and `isReady()` is what separates a toy implementation from a production one — without them you waste CPU and risk OOM from buffering.

### Q17. [Practical] Implement a client-streaming RPC in Java.

The client uses the **async stub** and gets a `StreamObserver` to push messages into; the server returns a single response via its own observer.

```protobuf
rpc UploadMetrics (stream Metric) returns (UploadSummary);
```

Server:

```java
@Override
public StreamObserver<Metric> uploadMetrics(StreamObserver<UploadSummary> resp) {
    return new StreamObserver<>() {
        long count = 0;
        @Override public void onNext(Metric m) { count++; store(m); }
        @Override public void onError(Throwable t) { /* log; client aborted */ }
        @Override public void onCompleted() {
            resp.onNext(UploadSummary.newBuilder().setReceived(count).build());
            resp.onCompleted();
        }
    };
}
```

Client (async):

```java
StreamObserver<UploadSummary> respObs = new StreamObserver<>() {
    @Override public void onNext(UploadSummary s) { System.out.println(s.getReceived()); }
    @Override public void onError(Throwable t) { latch.countDown(); }
    @Override public void onCompleted() { latch.countDown(); }
};
StreamObserver<Metric> reqObs = asyncStub.uploadMetrics(respObs);
for (Metric m : metrics) reqObs.onNext(m);
reqObs.onCompleted();   // signal end of client stream
latch.await();
```

### Q18. [Practical] Implement a bidirectional streaming RPC.

Both sides hold a `StreamObserver`; the streams are independent, so the server can reply at any cadence — per message, batched, or only at the end.

```protobuf
rpc Chat (stream ChatMessage) returns (stream ChatMessage);
```

```java
@Override
public StreamObserver<ChatMessage> chat(StreamObserver<ChatMessage> out) {
    return new StreamObserver<>() {
        @Override public void onNext(ChatMessage in) {
            // echo to sender + could fan out to a room
            out.onNext(ChatMessage.newBuilder()
                    .setText("echo: " + in.getText()).build());
        }
        @Override public void onError(Throwable t) { /* peer error */ }
        @Override public void onCompleted() { out.onCompleted(); }  // half-close
    };
}
```

Note the **half-close** semantics: when one side calls `onCompleted()`, it stops sending but can still *receive*. The call ends only when both directions are closed (and the server sends the final status).

### Q19. [Theory] What is an interceptor and what are common uses?

An **interceptor** is middleware that wraps RPC invocations, letting you run cross-cutting logic without touching service methods. gRPC has both **server interceptors** (`ServerInterceptor`) and **client interceptors** (`ClientInterceptor`), and they can be chained.

Common uses:

- **AuthN/AuthZ**: read the `authorization` metadata, validate a JWT, reject with `UNAUTHENTICATED`/`PERMISSION_DENIED`.
- **Observability**: logging, metrics, distributed tracing (inject/extract trace context from metadata).
- **Deadline/retry policy**, request/response logging, payload size limits.
- **Context propagation**: stash tenant/user info into the gRPC `Context`.

They are the gRPC analogue of servlet filters / Spring interceptors and are the right place for anything that should apply uniformly to every method.

### Q20. [Practical] Write a server interceptor that validates a JWT in metadata.

```java
public class AuthInterceptor implements ServerInterceptor {
    static final Metadata.Key<String> AUTH =
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <Req, Resp> ServerCall.Listener<Req> interceptCall(
            ServerCall<Req, Resp> call, Metadata headers,
            ServerCallHandler<Req, Resp> next) {

        String token = headers.get(AUTH);
        if (token == null || !token.startsWith("Bearer ")) {
            call.close(Status.UNAUTHENTICATED
                    .withDescription("missing bearer token"), new Metadata());
            return new ServerCall.Listener<>() {};   // no-op listener
        }
        Principal user = verify(token.substring(7)); // throws -> handle
        Context ctx = Context.current().withValue(USER_KEY, user);
        return Contexts.interceptCall(ctx, call, headers, next);
    }
}

// register
ServerBuilder.forPort(50051)
    .addService(ServerInterceptors.intercept(new ProductServiceImpl(), new AuthInterceptor()))
    .build();
```

Downstream code reads `USER_KEY.get()` from the current `Context`. Returning a no-op listener after `call.close()` is the correct way to short-circuit.

### Q21. [Theory] How does cancellation work in gRPC?

Cancellation propagates the intent "stop, I no longer want the result." A client cancels (explicitly, or implicitly when its deadline expires or it disconnects), and the server is notified so it can abort work and release resources. Cancellation flows **downstream**: if the server is mid-way calling another service, that nested call is cancelled too.

- Client side: cancel via `ClientCallStreamObserver.cancel(msg, cause)`, or it happens automatically on deadline/disconnect.
- Server side: detect via `Context.current().isCancelled()`, register `Context.addListener(...)`, or check `ServerCallStreamObserver.isCancelled()` in streaming.

```java
if (Context.current().isCancelled()) {
    responseObserver.onError(Status.CANCELLED.asRuntimeException());
    return;
}
```

Honoring cancellation prevents wasted CPU and "zombie" work after the caller is gone. It is tightly coupled with deadlines: an expired deadline cancels the call.

### Q22. [Theory] How is rich, structured error detail conveyed beyond a status code?

A bare status code + message is often too coarse. gRPC's **richer error model** uses `google.rpc.Status` (with embedded `Any` details) and the standard detail types in `google.rpc.error_details.proto`, such as `BadRequest` (field violations), `QuotaFailure`, `RetryInfo`, `ErrorInfo`, and `LocalizedMessage`. These are serialized into the trailing metadata.

```java
com.google.rpc.Status rich = com.google.rpc.Status.newBuilder()
    .setCode(Code.INVALID_ARGUMENT.getNumber())
    .setMessage("validation failed")
    .addDetails(Any.pack(BadRequest.newBuilder()
        .addFieldViolations(BadRequest.FieldViolation.newBuilder()
            .setField("price_cents").setDescription("must be > 0"))
        .build()))
    .build();
responseObserver.onError(StatusProto.toStatusRuntimeException(rich));
```

The client uses `StatusProto.fromThrowable(e)` to unpack the details. This lets you return machine-readable, localizable, field-level errors — analogous to RFC 9457 problem+json in REST.

### Q23. [Theory] How does protobuf handle schema evolution / backward compatibility?

Wire compatibility rests on **tag numbers**, not field names. Rules to stay compatible:

- **Adding** a new field with a new tag is safe — old code ignores unknown fields (they're preserved as unknown fields, not dropped).
- **Removing** a field: never reuse its tag number. Mark it `reserved` to prevent accidental reuse.
- **Renaming** a field is safe on the wire (name is irrelevant) but breaks generated code/JSON mapping.
- **Changing a field's type or tag** is a breaking change.
- Some type changes are wire-compatible (e.g., `int32`/`int64`/`uint32`/`bool` share varint encoding) but can silently truncate — treat as risky.

```protobuf
message User {
  reserved 4, 7;                  // tags of removed fields
  reserved "old_email";           // removed field name
  string id = 1;
  string name = 2;
}
```

The discipline: never change or reuse a tag; only add new ones; reserve what you remove.

### Q24. [Practical] How do you version a gRPC API?

The dominant convention is **package-based versioning**: put the major version in the proto package and the generated namespace, e.g. `shop.v1`, `shop.v2`. A breaking change means a new package (`v2`), and the server can serve both `v1` and `v2` services side by side during migration.

```protobuf
package shop.v1;
option java_package = "com.example.shop.v1";
service ProductService { /* ... */ }
```

Within a major version you only make **backward-compatible** changes (add fields/methods, never remove/repurpose). This mirrors API versioning conventions used by Google and the Buf style guide. Avoid versioning via metadata or method-name suffixes; package versioning keeps generated code, routing, and reflection clean.

### Q25. [Theory] What transport security options does gRPC support (TLS / mTLS)?

gRPC supports:

- **Plaintext** (`usePlaintext()`) — no encryption; only for local dev or inside a trusted, encrypted-by-other-means mesh.
- **Server TLS** — the channel verifies the server's certificate against a trust store; traffic is encrypted. This is the minimum for production.
- **Mutual TLS (mTLS)** — both client and server present certificates and verify each other. Common in zero-trust service meshes; the server can derive the caller's identity from the client cert.

```java
// Server with mTLS
Server server = NettyServerBuilder.forPort(50051)
    .sslContext(GrpcSslContexts.forServer(certChain, privateKey)
        .trustManager(caCert)                    // verify client certs
        .clientAuth(ClientAuth.REQUIRE)          // demand client cert => mTLS
        .build())
    .addService(new ProductServiceImpl())
    .build();
```

In meshes (Istio/Linkerd) mTLS is often handled by sidecars transparently, so application code uses plaintext to localhost while the proxy does mTLS on the wire.

### Q26. [Theory] How does load balancing work in gRPC, and why is L4 LB problematic?

Because gRPC multiplexes many requests over **one long-lived HTTP/2 connection**, a naive L4 (connection-level/TCP) load balancer pins all of a client's traffic to a single backend — new requests reuse the existing connection rather than opening new ones. This defeats balancing.

Two correct approaches:

```
Client-side (look-aside / pick-first / round-robin):
  client ── resolves N addresses ── opens subchannels to all
         └─ picks per-RPC across subchannels (round_robin)

Proxy / L7:
  client ── conn ── [L7 gRPC-aware proxy] ── balances per-RPC ── backends
                    (Envoy, NGINX, linkerd)
```

- **Client-side LB**: the client resolves all backend addresses (via DNS or a service registry / xDS), keeps subchannels to each, and applies a policy (`round_robin`, `pick_first`, weighted). Configured via the channel's `defaultLoadBalancingPolicy`.
- **L7 proxy LB**: a gRPC/HTTP-2-aware proxy (Envoy is canonical) balances individual streams across backends.

The key insight to state in an interview: **balance per-RPC, not per-connection**, because gRPC connections are sticky and long-lived.

### Q27. [Practical] How do you configure round-robin client-side load balancing?

You point the channel at a resolver that returns multiple addresses and select a policy. With DNS, all A-records become subchannels.

```java
ManagedChannel channel = ManagedChannelBuilder
    .forTarget("dns:///product-service.internal:50051")
    .defaultLoadBalancingPolicy("round_robin")
    .usePlaintext()
    .build();
```

For richer setups, gRPC supports **xDS** (the Envoy control-plane API) so a central control plane pushes endpoints, weights, and policies to clients — this is "proxyless service mesh." The takeaway: the LB policy lives in the channel config + name resolver, not in your business code.

### Q28. [Theory] What is gRPC-Web and why is it needed?

Browsers cannot make native gRPC calls because JavaScript has no access to raw HTTP/2 frames or trailers. **gRPC-Web** is a spec/implementation that adapts gRPC to what browsers can do: it uses a slightly different wire encoding (trailers folded into the body) over HTTP/1.1 or HTTP/2 fetch/XHR.

```
Browser ──gRPC-Web (HTTP/1.1 or h2)──► [Envoy / grpc-web proxy] ──gRPC──► backend
```

A proxy (Envoy's `grpc_web` filter, or the standalone `grpcwebproxy`) translates between gRPC-Web and native gRPC. Limitations: **client streaming and bidirectional streaming are not (fully) supported** in browsers — unary and server-streaming are. Modern "Connect" protocol (from Buf) is a related, increasingly popular alternative that is gRPC-compatible and browser-friendly without a translating proxy.

### Q29. [Theory] What is gRPC reflection and what is it used for?

**Server reflection** is a standard gRPC service (`grpc.reflection.v1.ServerReflection`) that lets clients query a server at runtime for its service definitions — which services and methods exist and their message schemas — without having the `.proto` files locally.

It powers dynamic tooling:

- `grpcurl` (the curl of gRPC) — call methods by name without compiled stubs.
- GUI clients (Postman, grpcui, BloomRPC successors) that introspect services.
- Debugging and service discovery in dev/test.

```java
ServerBuilder.forPort(50051)
    .addService(new ProductServiceImpl())
    .addService(ProtoReflectionService.newInstance())  // enable reflection
    .build();
```

Security note: reflection exposes your API surface, so it's typically **enabled in dev/staging and disabled (or access-controlled) in production**.

### Q30. [Theory] What is the standard gRPC health checking protocol?

gRPC defines a standard **Health Checking Protocol** via the `grpc.health.v1.Health` service with `Check` (unary) and `Watch` (server-streaming) methods returning a `ServingStatus` (`SERVING`, `NOT_SERVING`, `UNKNOWN`, `SERVICE_UNKNOWN`). Load balancers, Kubernetes (via the `grpc_health_probe` or native gRPC probes), and clients use it to decide whether to route traffic.

```java
HealthStatusManager health = new HealthStatusManager();
ServerBuilder.forPort(50051)
    .addService(new ProductServiceImpl())
    .addService(health.getHealthService())
    .build();

health.setStatus("shop.v1.ProductService", ServingStatus.SERVING);
// on graceful shutdown, flip to NOT_SERVING so LBs drain you first
health.setStatus("shop.v1.ProductService", ServingStatus.NOT_SERVING);
```

Kubernetes 1.24+ supports native gRPC liveness/readiness probes, so you no longer need the separate `grpc_health_probe` binary.

### Q31. [Practical] How do you write a client interceptor that adds a deadline and trace ID to every call?

```java
public class ClientContextInterceptor implements ClientInterceptor {
    static final Metadata.Key<String> TRACE =
        Metadata.Key.of("x-trace-id", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <Req, Resp> ClientCall<Req, Resp> interceptCall(
            MethodDescriptor<Req, Resp> method, CallOptions options, Channel next) {

        CallOptions withDeadline = options.getDeadline() == null
            ? options.withDeadlineAfter(2, TimeUnit.SECONDS) : options;

        return new ForwardingClientCall.SimpleForwardingClientCall<>(
                next.newCall(method, withDeadline)) {
            @Override public void start(Listener<Resp> l, Metadata headers) {
                headers.put(TRACE, currentTraceId());
                super.start(l, headers);
            }
        };
    }
}
ManagedChannel ch = ManagedChannelBuilder.forTarget(target)
    .intercept(new ClientContextInterceptor()).build();
```

This guarantees a default deadline and trace propagation without each call site remembering to set them.

### Q32. [Theory] How do retries and backoff work in gRPC?

gRPC supports **transparent and configurable retries** via a **service config** (JSON), typically delivered through the name resolver or set on the channel. You specify which methods to retry, the max attempts, the retryable status codes, and an exponential backoff policy.

```json
{
  "methodConfig": [{
    "name": [{"service": "shop.v1.ProductService"}],
    "retryPolicy": {
      "maxAttempts": 4,
      "initialBackoff": "0.1s",
      "maxBackoff": "1s",
      "backoffMultiplier": 2,
      "retryableStatusCodes": ["UNAVAILABLE"]
    }
  }]
}
```

Only retry **idempotent** operations or use server-side dedup, and only retry codes that imply the request likely didn't take effect (`UNAVAILABLE`). gRPC also supports **hedging** (sending parallel attempts) and **retry throttling** (a token budget) to avoid retry storms that amplify an outage. Pair retries with deadlines so the total time is bounded across attempts.

### Q33. [Practical] How do you set max message size and other channel/server limits?

By default gRPC caps inbound messages at **4 MB** to protect against memory abuse. Large payloads (or streaming chunking) require raising the limit deliberately.

```java
// Client
ManagedChannelBuilder.forTarget(target)
    .maxInboundMessageSize(16 * 1024 * 1024)   // 16 MB
    .build();

// Server
ServerBuilder.forPort(50051)
    .maxInboundMessageSize(16 * 1024 * 1024)
    .maxInboundMetadataSize(16 * 1024)
    .keepAliveTime(30, TimeUnit.SECONDS)
    .build();
```

Prefer **streaming + chunking** over huge single messages. Raising the limit blindly is a DoS risk; size it to real needs and validate.

### Q34. [Theory] What is the gRPC `Context` and how does it differ from `ThreadLocal`?

`io.grpc.Context` is an **immutable, propagated request scope** that carries values (and the deadline/cancellation signal) through the call, including across async boundaries and into outbound calls. Unlike a raw `ThreadLocal`, gRPC `Context` is designed to be explicitly **attached/detached** and to survive thread hops in async executors when you wrap runnables with `Context.current().wrap(...)`.

```java
static final Context.Key<Principal> USER = Context.key("user");
// read anywhere downstream in the same call:
Principal p = USER.get();
```

It's the right vehicle for per-request data (authenticated user, tenant, trace IDs) and it ties into cancellation: cancelling the context cancels the work.

### Q35. [Practical] How do you map gRPC services into Spring Boot?

The common approach uses a community starter (e.g., `grpc-spring-boot-starter` / the newer `grpc-server-spring-boot-starter`) that auto-creates the `Server`, scans for annotated services, and lets you use Spring beans, interceptors, and properties.

```java
@GrpcService                       // registers with the embedded gRPC server
public class ProductServiceImpl extends ProductServiceGrpc.ProductServiceImplBase {
    private final ProductRepository repo;   // injected Spring bean
    @Override public void getProduct(GetProductRequest r, StreamObserver<Product> o) { /* ... */ }
}
```

```properties
grpc.server.port=50051
grpc.server.security.enabled=true
```

You can register Spring-managed `ServerInterceptor`s globally, wire metrics via Micrometer, and reuse the application context — combining gRPC's performance with Spring's DI/config.

---

## 🟠 Advanced (8–12 yrs)

### Q36. [Theory] Walk through what happens on the wire for a unary gRPC call.

```
Client                                   Server
  │  HTTP/2 HEADERS (new stream)           │
  │  :method POST                          │
  │  :path /shop.v1.ProductService/GetProduct
  │  content-type application/grpc         │
  │  grpc-timeout 200m                     │   (deadline as metadata)
  │  + custom metadata (authorization...)  │
  │ ─────────────────────────────────────►│
  │  DATA: [1B compressed-flag][4B len][protobuf bytes]
  │ ─────────────────────────────────────►│  (length-prefixed message)
  │                                        │  ... server processes ...
  │  ◄───────────────────────────────────  HEADERS (:status 200, content-type)
  │  ◄───────────────────────────────────  DATA: length-prefixed Product
  │  ◄───────────────────────────────────  TRAILERS: grpc-status 0, grpc-message
```

Key points: the path is `/<package>.<Service>/<Method>`; the body is a **length-prefixed frame** (1 compression-flag byte + 4 length bytes + payload); the real outcome (`grpc-status`) lives in **HTTP/2 trailers**, which is why the HTTP `:status` is always `200` even for application errors. This trailer-based status is also why plain HTTP/1.1 and most browsers can't do native gRPC.

### Q37. [Theory] Why is the HTTP status almost always 200 for gRPC calls?

Because gRPC carries its own status out-of-band in trailers, the HTTP-level `:status` reflects only transport success. An RPC that fails with `NOT_FOUND` still returns HTTP 200 with `grpc-status: 5` in the trailers. (HTTP non-200 appears only for transport/protocol-level failures, e.g., 401 from an auth proxy or 502 from a broken gateway.) Monitoring tools that only watch HTTP status codes will miss application errors — you must scrape `grpc-status`/`grpc_code` labels instead.

### Q38. [Theory] How does HTTP/2 flow control interact with gRPC streaming, and how do you avoid OOM?

HTTP/2 has per-stream and per-connection **flow-control windows**; a receiver advertises how many bytes it's willing to accept. gRPC surfaces this via the `isReady()`/`onReadyHandler` API on stream observers. If a fast producer ignores readiness and keeps calling `onNext()`, messages buffer in memory unbounded → OOM.

```java
ServerCallStreamObserver<Product> o = (ServerCallStreamObserver<Product>) resp;
o.setOnReadyHandler(() -> {
    while (o.isReady() && source.hasNext()) o.onNext(source.next());
    if (!source.hasNext()) o.onCompleted();
});
o.disableAutoInboundFlowControl(); // for inbound, request() manually
```

Production streaming code must be **demand-driven**: only push when `isReady()` is true, and for inbound use manual flow control (`request(n)`). This is essentially reactive backpressure (and maps cleanly onto Reactor/RxJava bridges).

### Q39. [Practical] How do you implement graceful shutdown and connection draining?

Graceful shutdown lets in-flight RPCs finish while refusing new ones, and signals load balancers to stop sending traffic.

```java
// 1. Flip health to NOT_SERVING so LBs/K8s readiness drain you
health.setStatus("", ServingStatus.NOT_SERVING);

// 2. Stop accepting new RPCs, let existing ones finish
server.shutdown();                                   // graceful
if (!server.awaitTermination(30, TimeUnit.SECONDS)) {
    server.shutdownNow();                            // force after grace period
    server.awaitTermination(5, TimeUnit.SECONDS);
}
```

Pair this with HTTP/2 **GOAWAY** frames (gRPC sends them on shutdown) so clients reconnect elsewhere, and configure `maxConnectionAge` on the server so long-lived connections periodically cycle, allowing client-side LB to rebalance onto new backends.

### Q40. [Theory] How do keepalives work and why do they matter?

gRPC keepalive sends periodic HTTP/2 PING frames to detect dead connections and to keep idle connections alive through NAT/load-balancer idle timeouts. Misconfiguration causes either dropped connections or `ENHANCE_YOUR_CALM`/`too_many_pings` GOAWAYs when a client pings too aggressively.

```java
// client
ManagedChannelBuilder.forTarget(t)
  .keepAliveTime(30, TimeUnit.SECONDS)
  .keepAliveTimeout(10, TimeUnit.SECONDS)
  .keepAliveWithoutCalls(true);
// server must permit it
NettyServerBuilder.forPort(p)
  .permitKeepAliveTime(20, TimeUnit.SECONDS)
  .permitKeepAliveWithoutCalls(true);
```

Rule: the client's `keepAliveTime` must be **≥** the server's `permitKeepAliveTime`, or the server will reject pings. Keepalives are essential behind cloud LBs (which often kill idle connections after 60s) to avoid surprise `UNAVAILABLE` on the next call.

### Q41. [Theory] Compare gRPC's wire efficiency and tradeoffs against REST+JSON at scale.

Beyond "binary is smaller," at scale the relevant factors are:

- **CPU**: protobuf encode/decode is far cheaper than JSON tokenization; matters at millions of RPS.
- **Bytes on wire**: protobuf + HPACK header compression + HTTP/2 multiplexing reduce both payload and connection overhead. Fewer TCP connections → less memory and fewer handshakes.
- **Latency**: multiplexing eliminates head-of-line blocking at the request layer (though HTTP/2 still has TCP-level HOL blocking under packet loss — HTTP/3/QUIC addresses that, and gRPC over QUIC exists).
- **Tradeoffs**: lost human-readability and HTTP caching; harder to debug; protobuf's lack of self-describing payloads means consumers need the schema; weaker support for ad-hoc evolution than, say, JSON with optional fields.

A nuanced answer notes that **observability and tooling cost** is the real tax: you give up curl/grep-ability and gain a schema-enforced, efficient contract.

### Q42. [Practical] How do you propagate distributed tracing context across gRPC hops?

Tracing requires injecting/extracting context (e.g., W3C `traceparent`) into gRPC **metadata** at the client and reading it at the server, then making it the active span. With OpenTelemetry this is largely automatic via the gRPC instrumentation, which installs client/server interceptors.

```java
// OpenTelemetry gRPC instrumentation wires interceptors for you:
ManagedChannel channel = ManagedChannelBuilder.forTarget(t)
    .intercept(GrpcTelemetry.create(openTelemetry).newClientInterceptor())
    .build();

ServerBuilder.forPort(p)
    .intercept(GrpcTelemetry.create(openTelemetry).newServerInterceptor())
    .addService(svc).build();
```

Under the hood: the client interceptor injects `traceparent` into outbound metadata; the server interceptor extracts it, starts a child span, and stores it in the gRPC `Context` so downstream outbound calls continue the trace. Because deadline and cancellation also ride the `Context`, the whole call tree is observable and bounded.

### Q43. [Theory] How would you design idempotency and exactly-once-ish semantics for gRPC calls?

gRPC retries (and hedging) can deliver a request **more than once**, so non-idempotent operations need protection:

- **Idempotency keys**: client sends a unique key in metadata (`idempotency-key`); the server records the key + result, and on a duplicate returns the stored result instead of re-executing. Backed by a dedup store with TTL.
- **Restrict retries** to `UNAVAILABLE` (request likely never reached the server) and idempotent methods; mark mutating RPCs as non-retryable in the service config.
- **Server-side dedup** at the persistence layer (unique constraints, conditional writes).

True exactly-once across a network is impossible; you achieve **effectively-once** by combining at-least-once delivery (retries) with idempotent processing (dedup). State this distinction explicitly in an interview.

### Q44. [Behavioral] Describe a time you migrated a service from REST to gRPC (or chose not to). How did you decide?

A strong answer is structured and honest about tradeoffs. Frame it with: the **driver** (e.g., latency/cost on a hot internal path, or a polyglot fan-out), the **decision criteria** (is this internal east-west traffic? do we control both ends? do we need streaming? is the team's tooling/observability ready for binary protocols?), and the **migration approach** (run gRPC alongside REST via a gateway/transcoding, dual-stack during cutover, contract-first `.proto` reviewed like an API).

Equally valuable is describing when you **chose not to** migrate — e.g., a public/partner API where REST's debuggability, browser support, and broad client familiarity outweighed gRPC's efficiency. The interviewer is assessing whether you optimize for the actual constraints (team maturity, client base, debuggability, latency budget) rather than chasing a trend. Mention concrete outcomes: payload size reduction, p99 latency change, and the operational cost (new dashboards, gRPC-aware LB).

### Q45. [Practical] How do you expose a gRPC service to REST/JSON clients without rewriting it?

Two standard approaches:

1. **gRPC-Gateway / transcoding**: annotate methods with `google.api.http` options; a generated reverse proxy (grpc-gateway in Go, or Envoy's gRPC-JSON transcoder) accepts REST/JSON and translates to gRPC. One backend serves both.

```protobuf
import "google/api/annotations.proto";
service ProductService {
  rpc GetProduct (GetProductRequest) returns (Product) {
    option (google.api.http) = { get: "/v1/products/{id}" };
  }
}
```

2. **Connect protocol** (Buf): a single server speaks gRPC, gRPC-Web, **and** Connect's own HTTP/JSON-friendly protocol, so plain `curl`/browser clients work without a separate proxy.

This gives internal callers efficient gRPC and external/browser callers a familiar REST surface from the same `.proto` contract.

### Q46. [Theory] How does protobuf encoding work at the byte level (varints, wire types)?

Each field is encoded as a **key** (tag + wire type) followed by a value. The key is `(field_number << 3) | wire_type`, varint-encoded.

```
Wire types:
  0  Varint          int32/64, uint, bool, enum, sint(zigzag)
  1  64-bit          fixed64, double
  2  Length-delim    string, bytes, embedded msgs, packed repeated
  5  32-bit          fixed32, float
```

- **Varints** use 7 bits per byte with a continuation MSB, so small numbers take 1 byte. Negative `int32`/`int64` encode poorly (always 10 bytes) — use `sint32`/`sint64` (zigzag) for signed values that can be negative.
- **Length-delimited** fields prefix the byte length, enabling skip-on-unknown.

This is *why* tag numbers ≤ 15 are cheaper (1-byte key) — reserve them for the most frequent fields. And it's why unknown fields can be safely skipped: the wire type tells the parser how many bytes to jump.

### Q47. [Practical] How do you test gRPC services in Java?

Use the **in-process transport** for fast, real (no-network) tests, plus `GrpcCleanupRule` to manage lifecycles. For contract/integration tests, spin up the real server on an ephemeral port.

```java
@Rule public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

@Test public void getProduct_returnsProduct() throws Exception {
    String name = InProcessServerBuilder.generateName();
    grpcCleanup.register(InProcessServerBuilder.forName(name)
        .directExecutor().addService(new ProductServiceImpl(repo)).build().start());

    ManagedChannel ch = grpcCleanup.register(
        InProcessChannelBuilder.forName(name).directExecutor().build());

    var stub = ProductServiceGrpc.newBlockingStub(ch);
    Product p = stub.getProduct(GetProductRequest.newBuilder().setId("1").build());
    assertThat(p.getName()).isEqualTo("Widget");
}
```

For error paths, assert the `StatusRuntimeException.getStatus().getCode()`. Use `grpcurl` against a running instance for manual/exploratory testing.

---

## 🔴 Expert (15+ yrs)

### Q48. [Theory] Design the rollout strategy for a breaking protobuf change across many independent teams.

A breaking change (removing/repurposing a field, changing a method's semantics) can't be deployed atomically across services you don't control. The strategy:

1. **Never break in place** — introduce a `v2` package/service alongside `v1`; servers serve both. Old clients keep using `v1`.
2. **Expand–migrate–contract**: add the new field/method (expand), migrate consumers off the old one over a deprecation window, then remove and `reserved`-tag the old (contract) only after telemetry shows zero `v1` usage.
3. **Schema governance**: enforce compatibility in CI with **Buf breaking-change detection** against the registry's main branch, so no one can merge a wire-incompatible change accidentally.
4. **Observability of versions**: tag metrics by service/method/version to know when `v1` traffic hits zero.
5. **Communication**: deprecation notices, owner sign-off, and a hard cutoff date.

The core principle: **the schema is a distributed contract**; you evolve it with the same expand/contract discipline as a database migration, gated by automated compatibility checks.

### Q49. [Theory] How do you architect proxyless service mesh / xDS-based gRPC at scale?

Traditional meshes put an Envoy sidecar next to every pod; the gRPC client speaks plain gRPC to localhost and Envoy does LB, mTLS, retries. **Proxyless mesh** removes the sidecar: the gRPC library itself becomes an xDS client, fetching endpoints, load-balancing policy, routing, and security config from a control plane (e.g., Istio's istiod, Google Traffic Director) over the **xDS** APIs.

```
Control plane (xDS)
   │  CDS/EDS/LDS/RDS pushes
   ▼
gRPC client (xDS-enabled)  ──per-RPC LB, mTLS, routing──►  backends
   (no sidecar in the data path)
```

Benefits: lower latency and resource use (no extra hop/proxy), but the LB/routing logic now lives in every language's gRPC runtime, so feature parity and rollout coordination across languages becomes the hard part. You decide between sidecar and proxyless based on language coverage, the policy features you need, and operational maturity.

### Q50. [Behavioral] You're the tech lead and two teams disagree on gRPC vs REST for a new platform API. How do you drive the decision?

A senior answer shows **facilitation and first-principles**, not authority. Steps: (1) clarify the **API's audience** — internal-only vs partner/public vs browser — since that single factor usually dominates. (2) Enumerate the real **requirements**: latency budget, streaming needs, polyglot clients, debuggability, caching, SLA. (3) Surface **hidden costs**: gRPC-aware LB/observability, gRPC-Web/transcoding for browsers, team's protobuf maturity. (4) Consider a **both/and**: contract-first protobuf with transcoding/Connect so you serve gRPC internally and JSON/REST externally from one definition — often dissolving the dispute. (5) Make the decision **reversible and measurable**: pick one, define success metrics, and timebox a spike.

The meta-point interviewers want: you reduce a tribal argument to explicit tradeoffs tied to product constraints, document the decision (an ADR), and keep team trust by deciding transparently rather than by seniority.

### Q51. [Theory] What are the failure modes of long-lived HTTP/2 connections in gRPC, and how do you mitigate them?

Long-lived, multiplexed connections introduce subtle failure modes:

- **Load imbalance**: connections stick to one backend; new backends get no traffic. *Mitigate* with `maxConnectionAge`/`maxConnectionAgeGrace` on the server (forces periodic reconnect → rebalance) and per-RPC client-side LB.
- **HTTP/2 HOL blocking under packet loss**: all streams on a TCP connection stall on a lost segment. *Mitigate* with HTTP/3/QUIC where available.
- **Stream limits**: `SETTINGS_MAX_CONCURRENT_STREAMS` caps in-flight RPCs per connection; exceeding it queues calls. *Mitigate* by tuning the limit or using more subchannels.
- **Idle-timeout kills** by intermediary LBs → next call sees `UNAVAILABLE`. *Mitigate* with keepalive pings.
- **`too_many_pings` GOAWAY** when keepalive is too aggressive. *Mitigate* by aligning client `keepAliveTime` with server `permitKeepAliveTime`.

The expert framing: gRPC trades many-short-connections for few-long-connections, which is efficient but shifts your operational concerns to **connection lifecycle management** (aging, keepalive, rebalancing) rather than per-request connection churn.

### Q52. [Theory] How do you secure a gRPC deployment in depth (authn, authz, transport, validation)?

Defense in depth across layers:

- **Transport**: mTLS everywhere (often via mesh sidecars or xDS), modern TLS versions, rotated certs (SPIFFE/SPIRE identities).
- **AuthN**: validate caller identity — JWT/OIDC tokens in `authorization` metadata, or mTLS client-cert identity; reject with `UNAUTHENTICATED`.
- **AuthZ**: per-method/per-resource authorization in an interceptor or policy engine (OPA), returning `PERMISSION_DENIED`.
- **Input validation**: protobuf gives type safety but not value validation — use `protoc-gen-validate`/`protovalidate` for field constraints; never trust client input.
- **Resource protection**: max message/metadata sizes, concurrent-stream limits, rate limiting/quota (`RESOURCE_EXHAUSTED`), deadlines to bound work.
- **Surface reduction**: disable reflection in prod, don't leak internal details in status messages, scrub PII from logs/traces.

The key insight: protobuf's type system is **not** a security boundary — you still need authn, authz, validation, and rate limiting exactly as with REST.

### Q53. [Practical] How would you bridge gRPC streaming to a reactive stack (Reactor/RxJava) with proper backpressure?

The naive bridge breaks because gRPC's `StreamObserver` push model ignores reactive demand, causing buffering. The correct bridge couples gRPC flow control (`isReady`/`request(n)`) to reactive demand using a generated reactive binding (e.g., `reactor-grpc` / `rxgrpc`) or a hand-written adapter.

```java
// Conceptual adapter: only emit to gRPC when the peer is ready,
// and only pull from the reactive source per downstream demand.
ServerCallStreamObserver<Item> o = (ServerCallStreamObserver<Item>) resp;
Flux<Item> source = service.stream(req);
source.limitRate(64)                       // reactive backpressure
      .doOnNext(item -> { while (!o.isReady()) Thread.onSpinWait(); o.onNext(item); })
      .doOnComplete(o::onCompleted)
      .doOnError(t -> o.onError(toStatus(t)))
      .subscribe();
```

In practice you use `reactor-grpc`/`rxgrpc` which generate `Mono<Resp> method(Mono<Req>)` / `Flux<Resp> method(Flux<Req>)` signatures and wire `isReady()`/`request()` to `Subscription.request(n)` automatically — giving end-to-end backpressure from the network through your reactive pipeline.

### Q54. [Theory] When does gRPC stop being the right tool, and what alternatives fit those cases?

A senior engineer names the boundaries:

- **Browser-first / public web APIs** → REST/JSON or GraphQL (gRPC-Web needs a proxy and lacks full streaming).
- **Event-driven / async, fire-and-forget, pub-sub** → a message broker (Kafka, NATS, Pub/Sub); gRPC is request/response, not a durable queue.
- **Massive fan-out, decoupled producers/consumers** → streaming platforms, not point-to-point RPC.
- **Public partner integrations** where consumers want curl-ability, OpenAPI, and minimal tooling → REST.
- **Ultra-low-latency, in-datacenter, schema-stable** with tight control of both ends → gRPC shines (or sometimes a custom binary protocol/Cap'n Proto/Thrift in niche cases).

The mature view: gRPC is excellent for **synchronous, strongly-typed, internal service-to-service** communication; it is not a message bus, not inherently browser-friendly, and not the lowest-friction choice for public APIs. Choosing the transport per workload — gRPC for RPC, a broker for events, REST/GraphQL at the public edge — is the hallmark of good system design.

## 🧩 Extended Questions — Set 1: Deeper theory & internals

### 🟢 — extended

#### Q55. [Theory] What is a `MethodDescriptor` and what does it carry?

A `MethodDescriptor<ReqT, RespT>` is the generated, immutable object that fully describes one RPC method to the runtime. It is the bridge between your typed stub call and the bytes on the wire. It carries:

- The **fully-qualified method name** (`shop.v1.ProductService/GetProduct`) used as the HTTP/2 `:path`.
- The **method type** — `UNARY`, `SERVER_STREAMING`, `CLIENT_STREAMING`, or `BIDI_STREAMING` — derived from where `stream` appears in the `.proto`.
- The **request and response marshallers** (how to turn `ReqT`/`RespT` into and out of bytes — usually the protobuf marshaller).
- Flags like `idempotentLevel`, `safe`, and `sampledToLocalTracing`.

```java
MethodDescriptor<GetProductRequest, Product> md =
    MethodDescriptor.<GetProductRequest, Product>newBuilder()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName(
            MethodDescriptor.generateFullMethodName("shop.v1.ProductService", "GetProduct"))
        .setRequestMarshaller(ProtoUtils.marshaller(GetProductRequest.getDefaultInstance()))
        .setResponseMarshaller(ProtoUtils.marshaller(Product.getDefaultInstance()))
        .build();
```

Interceptors receive the `MethodDescriptor`, which is how generic middleware (logging, metrics, routing) can act per method without knowing the concrete types. Everything the transport needs to dispatch a call is encoded here, not in the stub.

#### Q56. [Theory] What is a subchannel, and how does it relate to a channel and a connection?

A `ManagedChannel` is a *logical* connection to a *target* (which may resolve to many addresses). Internally it owns one or more **subchannels**, and each subchannel manages the actual **transport** (one HTTP/2 connection) to a single resolved backend address. The hierarchy:

```
ManagedChannel (target: dns:///svc:50051)
   ├─ NameResolver  → [10.0.0.1, 10.0.0.2, 10.0.0.3]
   ├─ LoadBalancer  → decides which subchannel per RPC
   ├─ Subchannel A  → transport (1 HTTP/2 conn to 10.0.0.1)
   ├─ Subchannel B  → transport (1 HTTP/2 conn to 10.0.0.2)
   └─ Subchannel C  → transport (1 HTTP/2 conn to 10.0.0.3)
```

The **load-balancing policy** operates on subchannels: `pick_first` uses one, `round_robin` rotates across all READY ones. Each subchannel has its own **connectivity state** (`IDLE`, `CONNECTING`, `READY`, `TRANSIENT_FAILURE`, `SHUTDOWN`) and its own reconnect backoff. Understanding this three-tier model (channel → subchannel → transport) explains why one channel can survive a backend dying — the failed subchannel reconnects while others keep serving.

#### Q57. [Theory] What connectivity states does a channel move through?

A gRPC channel (and each subchannel) is a small state machine:

```
        ┌──────────────────────────────────────────┐
        ▼                                            │
      IDLE ──(RPC starts / connect)──► CONNECTING ──► READY
        ▲                                │             │
        │                                ▼             │
        └──(backoff reset)──── TRANSIENT_FAILURE ◄─────┘ (conn lost)
                                         │
                                         ▼
                                     SHUTDOWN (terminal)
```

- **IDLE**: no active connection; created lazily, or after the idle timeout collapses an unused channel to save resources.
- **CONNECTING**: a transport handshake (TCP + TLS + HTTP/2 preface) is in progress.
- **READY**: a working transport exists; RPCs flow.
- **TRANSIENT_FAILURE**: the last connect attempt failed; the channel waits with exponential backoff before retrying. RPCs either fail fast or queue (wait-for-ready).
- **SHUTDOWN**: terminal after `shutdown()`.

```java
ConnectivityState s = channel.getState(true);   // true = try to connect if IDLE
channel.notifyWhenStateChanged(s, () -> log.info("state -> " + channel.getState(false)));
```

You can observe this for readiness gating and to implement smart warm-up before serving traffic.

#### Q58. [Theory] What is "wait-for-ready" and when should you enable it?

By default, if a channel has no ready transport (e.g., it is in `TRANSIENT_FAILURE`), an RPC **fails fast** with `UNAVAILABLE`. With **wait-for-ready** enabled on the call, the RPC instead **queues** until the channel becomes `READY` or the **deadline** expires.

```java
Product p = stub
    .withWaitForReady()
    .withDeadlineAfter(2, TimeUnit.SECONDS)
    .getProduct(req);
```

- **Use it** when transient unavailability (a backend restarting, a brief network blip) should be hidden from callers and you have a deadline to bound the wait — common for resilient internal calls.
- **Avoid it** when you want to fail fast and shed load (e.g., a request already near its budget, or a circuit-breaker pattern where queuing makes congestion worse).

The critical pairing: wait-for-ready is only safe **with a deadline**, otherwise a down backend makes the call hang indefinitely.

#### Q59. [Practical] How do you declare and use a `oneof` in protobuf, and what does it generate?

A `oneof` models "exactly one of these fields is set" — a tagged union. Setting one field clears the others, and it adds presence tracking for free.

```protobuf
message Notification {
  string id = 1;
  oneof channel {
    EmailPayload email = 2;
    SmsPayload   sms   = 3;
    PushPayload  push  = 4;
  }
}
```

In generated Java you get a `getChannelCase()` enum to switch on:

```java
switch (n.getChannelCase()) {
    case EMAIL -> sendEmail(n.getEmail());
    case SMS   -> sendSms(n.getSms());
    case PUSH  -> sendPush(n.getPush());
    case CHANNEL_NOT_SET -> handleMissing();
}
```

On the wire, only the set field's tag is written, so a `oneof` is space-efficient. Caveats: fields in a `oneof` cannot be `repeated`, and you cannot make a `oneof` itself `optional`. Adding a new case is backward-compatible, but moving an existing field into or out of a `oneof` is a breaking change.

#### Q60. [Theory] What are well-known types, and why use `Timestamp`/`Duration` instead of raw int64?

**Well-known types (WKTs)** are a small standard library of messages shipped with protobuf under `google.protobuf.*` — `Timestamp`, `Duration`, `Any`, `Struct`, `Value`, `Empty`, `FieldMask`, and the wrapper types (`Int32Value`, `StringValue`, etc.).

```protobuf
import "google/protobuf/timestamp.proto";
import "google/protobuf/duration.proto";

message Job {
  google.protobuf.Timestamp scheduled_at = 1;  // canonical UTC instant
  google.protobuf.Duration  timeout      = 2;
}
```

Why prefer them over a raw `int64 epoch_millis`:

- **Canonical semantics**: `Timestamp` is defined as UTC seconds + nanos since the Unix epoch — no ambiguity about units (seconds? millis? micros?) or time zone.
- **Cross-language mapping**: libraries convert `Timestamp` ↔ `Instant`/`time.Time`/`datetime` and render it as RFC 3339 in protobuf-JSON automatically.
- **Tooling/validation** understands them.

`Empty` is the idiomatic "no request/response body" type; `FieldMask` expresses partial updates (which fields a PATCH touches); the wrapper types give nullable scalars in JSON mapping.

#### Q61. [Theory] What is the difference between `proto2` and `proto3` field presence?

**Field presence** is the ability to tell "explicitly set" from "default/absent." The two syntaxes differ sharply:

- **proto2**: every field is `optional` or `required` and has explicit presence — you can always ask `hasField()`, and unset is distinct from default. (`required` is now considered a design mistake and is gone in proto3.)
- **proto3** (original): scalar fields have **no presence** — a `0`/`""`/`false` is indistinguishable from unset, and there is no `hasField()` for them. Message-typed fields always had presence.
- **proto3 with `optional`** (re-added ~2020, GA): adding `optional` to a proto3 scalar restores explicit presence and generates `hasNickname()`.

```protobuf
message Patch {
  optional string nickname = 1;   // hasNickname() distinguishes "" from absent
  int32 score = 2;                // no presence: 0 == unset on the wire
}
```

This matters most for **PATCH/update** semantics, where "set field to empty string" must differ from "leave field unchanged." Use `optional` or a `FieldMask` to express that.

#### Q62. [Practical] How does protobuf encode a `repeated` field, and what is packed encoding?

A `repeated` field of scalars is, by default in proto3, **packed**: all the values are concatenated into a single length-delimited (wire type 2) record under one tag, rather than re-emitting the tag per element. This is much more compact.

```protobuf
message Histogram {
  repeated int32 buckets = 1;   // proto3: packed by default
}
```

On the wire, `buckets = [3, 270, 86942]` becomes: `tag(field 1, wire type 2)` + `length` + `varint(3) varint(270) varint(86942)` — one tag total, not three.

- Packing applies only to **scalar numeric** types (varints, fixed32/64). Strings, bytes, and message types are length-delimited per element (cannot be packed).
- proto2 required explicit `[packed=true]`; proto3 packs by default but parsers must still accept the unpacked form for compatibility.
- A `repeated` message field emits one length-delimited record per element, each parseable/skippable independently.

This is why a list of 10,000 ints is dramatically smaller in protobuf than in JSON, where each element re-pays for delimiters.

### 🟡 — extended

#### Q63. [Theory] How does the gRPC name resolution + load-balancing plugin architecture fit together?

gRPC's client side is built from pluggable pieces that hand off in sequence:

```
target URI  ──►  NameResolver  ──►  LoadBalancer  ──►  Subchannels/Picker  ──►  transport
("dns:///x")    (scheme-keyed)     (policy plugin)     (per-RPC choice)
```

1. The **target scheme** (`dns:`, `xds:`, `unix:`, or a custom one) selects a registered **`NameResolver`**. It produces a `ResolutionResult`: a list of addresses, an optional **service config**, and attributes.
2. The resolver hands addresses + config to the **`LoadBalancer`** (the policy named in the service config, e.g. `round_robin`, `pick_first`, `grpclb`, `xds`).
3. The LoadBalancer creates **subchannels** and produces a **`SubchannelPicker`**; for each RPC, `pick()` returns the subchannel to use.

Everything is a registered plugin, so you can add a custom resolver (e.g., reading from Consul/etcd) or a custom LB policy without touching application code. The service config (delivered by the resolver) is what lets ops push retry/timeout/LB policy centrally.

#### Q64. [Practical] What is a service config and how is it delivered to clients?

A **service config** is a JSON document that configures client behavior per service/method: the load-balancing policy, retry/hedging policy, per-method timeouts, and wait-for-ready defaults. It is the canonical way to push policy from the server/control plane to clients without code changes.

```json
{
  "loadBalancingConfig": [{"round_robin": {}}],
  "methodConfig": [{
    "name": [{"service": "shop.v1.ProductService", "method": "GetProduct"}],
    "timeout": "1s",
    "waitForReady": true,
    "retryPolicy": {
      "maxAttempts": 3,
      "initialBackoff": "0.1s", "maxBackoff": "1s", "backoffMultiplier": 2,
      "retryableStatusCodes": ["UNAVAILABLE"]
    }
  }]
}
```

Delivery paths:

- **Resolver-provided** (preferred at scale): the DNS resolver reads TXT records, or the `xds` resolver gets it from the control plane — so ops change policy centrally.
- **Default service config** set on the channel in code: `ManagedChannelBuilder.defaultServiceConfig(map)` — a fallback when the resolver provides none.

A method `timeout` here acts as a default deadline; an explicit `withDeadlineAfter` on the call still takes precedence.

#### Q65. [Theory] How does message compression work in gRPC, and what are the pitfalls?

gRPC compresses **per message**, not per stream. The 1-byte **compressed flag** in each length-prefixed frame says whether that message's payload is compressed, and the `grpc-encoding` header names the algorithm (`gzip`, `deflate`, or `identity`); `grpc-accept-encoding` advertises what each side supports.

```java
// Client: compress outbound messages
stub = stub.withCompression("gzip");

// Server: set per-call compressor before responding
((ServerCallStreamObserver<Product>) resp).setCompression("gzip");
```

Pitfalls:

- **CPU vs bytes**: for small protobuf messages (already compact), gzip can cost more CPU than it saves in bytes — measure before enabling globally.
- **Decompression-bomb DoS**: a tiny compressed frame can expand hugely; enforce `maxInboundMessageSize` on the *decompressed* size.
- **Negotiation**: if a peer doesn't list your algorithm in `grpc-accept-encoding`, you get `UNIMPLEMENTED`. Stick to widely-supported `gzip`.

Compression is most worthwhile for large, repetitive payloads (text, JSON-in-bytes) and least worthwhile for already-small binary messages.

#### Q66. [Theory] Explain HTTP/2 frame types and how a gRPC message maps onto them.

HTTP/2 is a binary framing protocol. The frames that matter for gRPC:

- **HEADERS** — carries the request metadata (`:path`, `:method`, `content-type: application/grpc`, custom metadata) at stream start, and response `:status` + initial metadata. HPACK-compressed.
- **DATA** — carries the gRPC message bytes. One gRPC message = `[1-byte compressed flag][4-byte big-endian length][payload]`, and that can span multiple DATA frames or several messages can share one.
- **HEADERS (trailers)** — the final response metadata including `grpc-status` and `grpc-message`. Sent with END_STREAM.
- **WINDOW_UPDATE** — flow-control credit (the basis of backpressure).
- **RST_STREAM** — abrupt stream termination (used for cancellation).
- **PING** — keepalive liveness checks.
- **GOAWAY** — connection draining; "stop opening new streams on me."
- **SETTINGS** — negotiates `MAX_CONCURRENT_STREAMS`, initial window sizes, etc.

The crucial insight: a gRPC **message** is not an HTTP/2 frame — it is a length-prefixed payload *inside* DATA frames, decoupled from framing. That is why a single message can be split across frames and why status must live in trailing HEADERS, not DATA.

#### Q67. [Practical] How do you implement and register a custom client interceptor that retries on a specific condition not covered by the built-in policy?

The built-in retry policy keys off status codes only. For richer logic (e.g., retry on a specific `ErrorInfo` reason, or refresh a token on `UNAUTHENTICATED`), write a `ClientInterceptor` that wraps the call and re-issues it.

```java
public class TokenRefreshInterceptor implements ClientInterceptor {
    private final TokenProvider tokens;

    @Override
    public <Req, Resp> ClientCall<Req, Resp> interceptCall(
            MethodDescriptor<Req, Resp> method, CallOptions opts, Channel next) {

        return new ForwardingClientCall.SimpleForwardingClientCall<>(
                next.newCall(method, opts)) {
            @Override
            public void start(Listener<Resp> responseListener, Metadata headers) {
                headers.put(AUTH, "Bearer " + tokens.current());
                Listener<Resp> retrying = new ForwardingClientCallListener
                        .SimpleForwardingClientCallListener<>(responseListener) {
                    @Override public void onClose(Status status, Metadata trailers) {
                        if (status.getCode() == Status.Code.UNAUTHENTICATED) {
                            tokens.refreshAsync();   // refresh for next call; surface this one
                        }
                        super.onClose(status, trailers);
                    }
                };
                super.start(retrying, headers);
            }
        };
    }
}
```

For an *actual transparent re-issue*, you must buffer the request and start a brand-new `ClientCall` inside `onClose` — which is exactly the complexity the built-in retry subsystem already handles safely (including memory buffering limits and throttling), so prefer service-config retries unless your condition truly can't be expressed as a status code.

#### Q68. [Theory] What is the difference between the `Channelz` service and reflection, and what does Channelz expose?

They serve different debugging needs:

- **Reflection** answers *"what is the API?"* — services, methods, and message schemas, for dynamic clients like `grpcurl`.
- **Channelz** answers *"what is the runtime doing?"* — live introspection of the gRPC internals: channels, subchannels, sockets, servers, and per-entity stats (calls started/succeeded/failed, last call time, connectivity state, socket options, flow-control windows).

```java
ServerBuilder.forPort(50051)
    .addService(new ProductServiceImpl())
    .addService(ChannelzService.newInstance(100))   // maxPageSize
    .build();
```

You then query it with `grpcdebug` or a Channelz UI to diagnose, e.g., why a subchannel is stuck in `TRANSIENT_FAILURE`, which sockets are open, or whether keepalive is firing. Like reflection, Channelz exposes internal topology, so gate or disable it in production.

#### Q69. [Practical] How do you handle a streaming RPC that must enforce a deadline across the whole stream?

A deadline applies to the **entire RPC**, including a long-lived stream — not per message. For server-streaming, set it on the stub as usual; when it expires mid-stream the call terminates with `DEADLINE_EXCEEDED` and the observer's `onError` fires.

```java
// Client: deadline covers the full server-streaming lifetime
Iterator<Event> it = stub
    .withDeadlineAfter(30, TimeUnit.SECONDS)
    .subscribe(req);
try {
    while (it.hasNext()) handle(it.next());
} catch (StatusRuntimeException e) {
    if (e.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED) {
        // stream ran past its budget
    }
}
```

For genuinely **infinite** streams (a live feed that should never end), a fixed deadline is wrong — instead omit the deadline and rely on **keepalive** + **cancellation** + application-level heartbeats to detect death, and re-subscribe on disconnect. The interview point: deadlines bound *bounded* work; long-lived subscriptions need a different liveness strategy.

#### Q70. [Theory] How does the gRPC `Context` deadline interact with `withDeadlineAfter` on a stub?

There are two deadline sources and gRPC takes the **earliest (most restrictive) wins**:

1. The **`CallOptions` deadline** set via `stub.withDeadlineAfter(...)` / `.withDeadline(...)`.
2. The **`Context` deadline** — when the current `io.grpc.Context` has a deadline (e.g., propagated from an inbound server call), outbound calls made within that context inherit it.

```java
// Server handling an inbound call whose remaining budget is 200ms:
// this outbound call is automatically capped at min(200ms, 500ms) = 200ms
Product p = downstreamStub
    .withDeadlineAfter(500, TimeUnit.MILLISECONDS)   // looks like 500ms...
    .getProduct(req);                                // ...but Context caps it to ~200ms
```

This is precisely the mechanism that makes deadlines **propagate down a call tree** without manual plumbing: each hop's inbound deadline lives in its `Context`, and outbound stubs created in that context cannot exceed it. If you forget and set a *longer* per-hop deadline, the context still protects you — but setting a *shorter* one is how you reserve time for retries or local work.

### 🟠 — extended

#### Q71. [Theory] Walk through the exact lifecycle of a server-side streaming call in grpc-java, from listener callbacks to status.

On the server, a call is driven by a `ServerCall.Listener` whose callbacks fire in a defined order on the call's executor:

```
onReady()        ← transport can accept more outbound (flow control)
onMessage(req)   ← a request message arrived (once for unary/server-streaming)
onHalfClose()    ← client finished sending; for server-streaming you now produce responses
onCancel()       ← client cancelled / deadline exceeded / disconnect (terminal)
onComplete()     ← call closed successfully (terminal)
```

For a server-streaming method generated via `StreamObserver`, grpc-java adapts these: your method body runs after `onHalfClose`, you call `responseObserver.onNext()` N times (each becomes a DATA frame, gated by `onReady`), then `onCompleted()` sends trailers with `grpc-status: 0`. If you `onError`, trailers carry the failing status instead. `onCancel` and `onComplete` are **mutually exclusive terminal events** — exactly one fires, which is where you release per-call resources. Misusing the observer (calling `onNext` after `onCompleted`, or both `onError` and `onCompleted`) throws `IllegalStateException`.

#### Q72. [Theory] How does HTTP/2 HPACK header compression work, and what are its security implications for gRPC metadata?

**HPACK** compresses HTTP/2 headers using two mechanisms: a **static table** of common header names/values, and a **dynamic table** that both peers update as headers are seen, so a repeated header (like a constant `authorization` or `user-agent`) is later sent as a tiny index reference instead of full text.

Implications for gRPC metadata:

- **Efficiency**: stable metadata (auth tokens reused across calls, fixed trace headers) compresses to near-nothing after the first send — a big win for chatty RPC.
- **`maxInboundMetadataSize`**: HPACK can hide a "header bomb" (huge dynamic-table growth); gRPC caps total header size (default ~8 KB) to prevent memory abuse — tune it deliberately if you carry large metadata.
- **HPACK is not encryption**: it's compression. Confidentiality comes from **TLS**; never treat HPACK as protecting secret metadata.
- **`-bin` keys**: binary metadata is base64-encoded in headers, inflating size ~33% before HPACK; account for this when sizing `maxInboundMetadataSize`.

The key nuance for an interview: HPACK's stateful dynamic table is why long-lived gRPC connections amortize metadata cost, and why per-connection metadata-size limits exist.

#### Q73. [Practical] How do you implement custom flow control on the inbound side of a streaming server?

By default grpc-java auto-requests inbound messages, which can overwhelm a slow processor. Disable auto inbound flow control and pull explicitly with `request(n)` to apply backpressure to the *sender*.

```java
@Override
public StreamObserver<Chunk> upload(StreamObserver<Ack> resp) {
    ServerCallStreamObserver<Ack> srvObs = (ServerCallStreamObserver<Ack>) resp;
    srvObs.disableAutoRequest();          // we now control demand
    srvObs.request(1);                    // prime: ask for the first message

    return new StreamObserver<>() {
        @Override public void onNext(Chunk c) {
            processAsync(c).whenComplete((r, err) -> {
                if (err == null) srvObs.request(1);   // only ask for next when done
                else srvObs.onError(Status.INTERNAL.withCause(err).asRuntimeException());
            });
        }
        @Override public void onError(Throwable t) { /* client aborted */ }
        @Override public void onCompleted() {
            resp.onNext(Ack.newBuilder().setOk(true).build());
            resp.onCompleted();
        }
    };
}
```

By requesting exactly one message at a time and only requesting more after the async work completes, the server propagates backpressure through HTTP/2 `WINDOW_UPDATE` frames to the client, which stops sending until credit is available. This converts an unbounded-buffer OOM risk into a bounded, demand-driven pipeline.

#### Q74. [Theory] How does gRPC's retry implementation buffer messages, and what limits prevent unbounded memory use?

For automatic retries, gRPC must be able to **replay** the request, so it **buffers** the outgoing messages of an attempt until the attempt either commits (a retry is no longer possible) or the response begins. Without limits this would blow memory on large/streaming calls.

Safeguards:

- **`retryBufferSize`** (channel-level): a total byte budget for buffered retryable messages across all calls. When exceeded, buffering is disabled and in-flight calls become **non-retryable** (they "commit").
- **`perRpcBufferLimit`**: a per-call cap; a single large request that exceeds it disables retry for that call.
- **Commit on first response byte**: once any response data/headers arrive, the attempt is committed — the buffer is freed and no further retry happens.
- **Retry throttling**: a token-bucket per channel (`retryThrottling` in service config) that drains on failures and refills on successes, so a sustained outage stops generating retries (preventing **retry storms**).
- **Hedging** has analogous buffering plus a hedging-specific delay/throttle.

This is exactly why hand-rolling retries in an interceptor is discouraged — the built-in subsystem already solves replay buffering, commit semantics, and throttling correctly.

#### Q75. [Practical] How would you implement client-side load balancing with health-aware subchannel picking?

Combine `round_robin` (which already skips non-READY subchannels) with the gRPC **health checking** integration so that a backend reporting `NOT_SERVING` is removed from the pick set even while its TCP/HTTP-2 connection stays up.

```java
// Service config enabling per-subchannel health checking + round_robin
String config = """
{
  "loadBalancingConfig": [{"round_robin": {}}],
  "healthCheckConfig": { "serviceName": "shop.v1.ProductService" }
}""";

ManagedChannel ch = ManagedChannelBuilder
    .forTarget("dns:///product-service.internal:50051")
    .defaultServiceConfig(parseJson(config))
    .defaultLoadBalancingPolicy("round_robin")
    .build();
```

With `healthCheckConfig`, each subchannel runs a `Health/Watch` stream against its backend; a backend that flips to `NOT_SERVING` (e.g., during shutdown drain or because a dependency is down) is marked unhealthy and excluded from `round_robin` picks **without** tearing down the connection. This gives graceful drain and dependency-aware routing. For richer policies (locality-aware, weighted, outlier detection / circuit breaking), move to **xDS**, where the control plane pushes endpoint weights and ejection rules.

#### Q76. [Theory] How does outlier detection / circuit breaking work in gRPC (xDS), and why does it matter?

**Outlier detection** is passive circuit breaking: the client tracks per-endpoint success/error rates and **ejects** a backend that misbehaves (e.g., too many consecutive 5xx-equivalent statuses or excessive failure percentage) from the load-balancing pool for a growing time window, then tentatively re-admits it.

```
endpoint error rate > threshold ──► eject for base_ejection_time
   │                                   │ (×ejection count, capped)
   └──── still failing on re-admit ────┘  re-eject longer
```

Delivered via **xDS** (`OutlierDetection` config in the cluster), it matters because:

- It contains the **blast radius** of one sick backend — clients route around it instead of all sharing the pain.
- It's **automatic and decentralized** — no human in the loop, no central health-checker single point of failure.
- Combined with **circuit-breaking limits** (max connections, max pending requests, max concurrent requests per cluster), it prevents a struggling backend from being overwhelmed and prevents cascading failure.

The senior framing: at scale you want clients to *detect and avoid* bad backends locally (outlier detection) rather than relying solely on centralized health checks, because local detection reacts in real time to the actual traffic each client sees.

#### Q77. [Theory] Explain the protobuf binary encoding of a nested message vs a `group`, and why groups are deprecated.

A **nested (embedded) message** is encoded as a **length-delimited** field (wire type 2): the parser reads the byte length, then recursively parses that many bytes as the sub-message. This makes nested messages **skippable** when unknown — you can jump the whole blob using its length prefix.

```
field key (tag<<3 | 2)  |  length (varint)  |  <serialized sub-message bytes>
```

**Groups** were proto2's older nesting mechanism using paired **start-group (wire type 3)** and **end-group (wire type 4)** markers around the fields, with no length prefix:

```
START_GROUP(tag) ... fields ... END_GROUP(tag)
```

Groups are **deprecated** because:

- **No length prefix** → a parser that doesn't know the group must scan field-by-field to find the matching END_GROUP, defeating fast skip-on-unknown.
- They complicate streaming and indexing.

Length-delimited nested messages win on parse efficiency and forward compatibility, which is why proto3 dropped groups entirely. The encoding insight (length prefix enables O(1) skip) is the reason unknown fields and schema evolution work smoothly.

#### Q78. [Practical] How do you safely evolve an enum, and what happens to unknown enum values on the wire?

Enums evolve by **adding** new values with new numbers; the rules and runtime behavior:

```protobuf
enum OrderStatus {
  ORDER_STATUS_UNSPECIFIED = 0;   // mandatory zero sentinel
  ORDER_STATUS_PENDING     = 1;
  ORDER_STATUS_SHIPPED     = 2;
  ORDER_STATUS_DELIVERED   = 3;
  ORDER_STATUS_RETURNED    = 4;   // newly added — safe
  reserved 5, 6;                  // tombstone removed values
  reserved "ORDER_STATUS_CANCELLED";
}
```

- **Open enums (proto3)**: an unknown number received by an old client is **preserved**, not rejected. In Java, `getStatus()` returns `UNRECOGNIZED` while `getStatusValue()` returns the raw int — so the value round-trips intact and re-serializes correctly. Always handle `UNRECOGNIZED`/the `_UNSPECIFIED` default in switches.
- **Closed enums (proto2)**: unknown values are dropped into unknown fields rather than the enum field — a behavioral difference to be aware of in mixed proto2/proto3 systems.
- **Never renumber or reuse** an enum value; `reserved` the numbers and names of removed values.

The forward-compat payoff: a new server can introduce `RETURNED` and old clients won't crash — they just see an unrecognized value and fall through to a default branch, which is why the `_UNSPECIFIED = 0` discipline is non-negotiable.

### 🔴 — extended

#### Q79. [Theory] How does gRPC over HTTP/3 (QUIC) change the connection and failure model versus HTTP/2?

gRPC over **HTTP/3** runs on **QUIC** (UDP-based) instead of TCP, which changes several fundamentals:

- **No TCP head-of-line blocking**: QUIC streams are independently delivered, so a lost packet stalls only the stream it belongs to, not every multiplexed RPC on the connection. Under packet loss, this materially improves p99 for high-concurrency gRPC — the main motivation.
- **Faster connection establishment**: QUIC folds the transport and TLS 1.3 handshakes together (1-RTT, or 0-RTT for resumption), versus TCP+TLS's separate round trips.
- **Connection migration**: a QUIC connection is identified by a **connection ID**, not the 4-tuple, so it can survive a client IP/port change (Wi-Fi↔cellular) without reconnecting — useful for mobile gRPC.
- **Failure model shifts**: middleboxes/LBs must speak QUIC/UDP; some networks block or throttle UDP, so you need HTTP/2 fallback. Flow control and keepalive concepts carry over but are implemented at the QUIC layer.

The expert caveat: HTTP/3 helps most under loss/mobility and high stream concurrency; in a clean datacenter with low loss, HTTP/2's TCP HOL blocking is rarely the bottleneck, so adopt it where the network conditions justify the added operational complexity.

#### Q80. [Theory] What are the consistency and ordering guarantees of bidirectional streaming, and what can and cannot be assumed?

Within a single bidirectional stream, gRPC (via HTTP/2) guarantees:

- **Per-direction ordering**: messages a sender emits arrive at the peer **in order** — the client's message #2 never arrives before #1, and likewise server→client. This is the ordering of writes on one stream.
- **No cross-direction ordering**: the two directions are independent; you cannot assume a server reply corresponds to (or follows) any particular client message unless your application correlates them (e.g., a request id in each message).
- **No exactly-once**: a stream can break and be retried/re-established; messages may be redelivered if the application re-sends after reconnect. gRPC gives at-most-once *within an unbroken stream*, not across reconnects.
- **No global ordering across streams/connections**: messages on different RPCs or after a reconnect have no mutual ordering guarantee.

```protobuf
message ChatMessage {
  string client_msg_id = 1;   // app-level correlation, since gRPC won't pair req/resp
  string text = 2;
}
```

The staff-level point: gRPC streaming gives you a reliable, ordered byte/​message pipe **per direction within one live stream** — anything stronger (request/response correlation, dedup across reconnects, total order) is the application's responsibility, typically via message ids and idempotent handling.

#### Q81. [Practical] Design a backpressure-aware fan-out broadcaster (one producer → many slow gRPC subscribers) without head-of-line blocking or unbounded memory.

The hazard: one slow subscriber must not stall fast ones (HOL blocking) or force the broadcaster to buffer the whole stream in memory. The design gives each subscriber an **independent, bounded** queue and drops/slow-paths only the laggard.

```java
class Broadcaster {
    // one bounded buffer per subscriber; producer is decoupled from consumers
    private final Map<String, Subscriber> subs = new ConcurrentHashMap<>();

    void subscribe(String id, ServerCallStreamObserver<Event> obs) {
        Subscriber s = new Subscriber(obs, new ArrayBlockingQueue<>(1024));
        obs.setOnReadyHandler(() -> drain(s));      // push only when transport-ready
        obs.setOnCancelHandler(() -> subs.remove(id));
        subs.put(id, s);
    }

    void publish(Event e) {                         // called by the single producer
        for (Subscriber s : subs.values()) {
            if (!s.queue.offer(e)) {                // bounded: laggard's queue is full
                onSlowConsumer(s);                  // drop-oldest, or evict with RESOURCE_EXHAUSTED
            }
            drain(s);
        }
    }

    private void drain(Subscriber s) {
        ServerCallStreamObserver<Event> o = s.obs;
        Event ev;
        while (o.isReady() && (ev = s.queue.poll()) != null) o.onNext(ev);
    }
}
```

Key decisions to articulate: (1) **per-subscriber bounded queues** isolate slow consumers; (2) emit only when `isReady()` so HTTP/2 flow control governs each stream independently; (3) a **policy for the laggard** — drop-oldest for telemetry, or evict with `RESOURCE_EXHAUSTED` for must-not-lose semantics, or shed to a durable broker (Kafka) if the fan-out is large. For truly large fan-out, gRPC point-to-point streaming is the wrong tool — front it with a pub/sub system and use gRPC only for the last hop.

#### Q82. [Theory] How do you design protobuf schemas and a schema registry to support thousands of services with safe, automated evolution?

At organizational scale the `.proto` files become a governed, distributed contract surface. The architecture:

- **Single source of truth in a schema registry** (Buf Schema Registry or equivalent): protos are versioned, dependency-managed modules, not copy-pasted files.
- **CI-enforced breaking-change detection**: every PR runs `buf breaking` against the registry's committed baseline, so wire-incompatible changes (tag reuse, type change, field removal without `reserved`, enum renumber) **cannot merge**. This is the automated equivalent of the manual evolution rules.
- **Lint + style enforcement** (`buf lint`): mandatory `_UNSPECIFIED = 0` enums, `XxxRequest`/`XxxResponse` wrappers, package versioning, naming — so every team's protos are consistent and evolvable.
- **Generated SDK distribution**: the registry generates and publishes per-language client libraries, so consumers depend on a published artifact, not raw protos — decoupling release cadence.
- **Package-based major versioning** (`v1`, `v2`) with **expand/migrate/contract** for breaking changes, plus per-version telemetry to know when old versions hit zero traffic before removal.
- **Ownership + deprecation policy**: each proto module has owners; deprecations carry a sunset date and `deprecated` options that surface compiler warnings to consumers.

The staff-level thesis: you treat schema evolution like **database migrations under change control** — automated compatibility gates plus expand/contract discipline — because with thousands of independently deployed services you can never coordinate an atomic flag day, so safety must be *enforced by tooling*, not convention.

#### Q83. [Theory] What are the trade-offs of grpc-java's threading model (executors, `directExecutor`, blocking work) and how do you avoid starving the transport?

grpc-java separates **transport I/O threads** (Netty event loop) from the **application executor** that runs your service-method callbacks. The trade-offs:

- **Default executor**: a cached thread pool runs your handlers, so blocking in a handler doesn't block Netty's event loop directly — but an unbounded/cached pool can explode threads under load, and blocking calls there still cap throughput.
- **`directExecutor()`**: runs callbacks **directly on the Netty I/O thread** — lowest latency and no hand-off, but **any blocking or slow work there stalls the event loop**, freezing *all* connections/streams on that loop. Safe only for trivial, non-blocking, CPU-tiny handlers (and tests).
- **Blocking work** (DB/IO inside a handler) should run on a **bounded, dedicated executor**, never on `directExecutor`, and ideally be offloaded asynchronously so the response is completed from a worker — protecting the I/O threads.

```java
ServerBuilder.forPort(50051)
    .executor(Executors.newFixedThreadPool(64))   // bounded app pool, not cached/unbounded
    .addService(svc)
    .build();
```

Mitigations to state: use a **bounded** application pool sized to your downstream concurrency, offload blocking I/O off the I/O loop, consider **virtual threads** (Java 21+) for blocking-style handlers without thread explosion, and reserve `directExecutor` for genuinely non-blocking interceptors/handlers. The failure mode to name: blocking on the event loop causes *connection-wide* latency spikes and keepalive timeouts, not just a slow single RPC — because one loop multiplexes many streams.

#### Q84. [Practical] How would you build a transparent gRPC proxy/gateway that forwards arbitrary methods without knowing their proto types?

A generic proxy must route and forward calls **without compiled stubs** for each method. The trick is to treat payloads as **opaque bytes** by supplying a passthrough marshaller and using the method name from the inbound HTTP/2 `:path`.

```java
// Byte-passthrough marshaller: never deserialize, just forward the frame payload
static final MethodDescriptor.Marshaller<byte[]> BYTES =
    new MethodDescriptor.Marshaller<>() {
        public InputStream stream(byte[] b) { return new ByteArrayInputStream(b); }
        public byte[] parse(InputStream s) {
            try { return s.readAllBytes(); } catch (IOException e) { throw new RuntimeException(e); }
        }
    };

// Build a generic descriptor for whatever method name arrived
static MethodDescriptor<byte[], byte[]> anyMethod(String fullName, MethodDescriptor.MethodType type) {
    return MethodDescriptor.<byte[], byte[]>newBuilder()
        .setType(type)                         // discover from inbound call type
        .setFullMethodName(fullName)           // from the inbound :path
        .setRequestMarshaller(BYTES)
        .setResponseMarshaller(BYTES)
        .build();
}
```

The proxy registers a **`HandlerRegistry`** fallback that, for any unrecognized method, creates a `ClientCall` to the upstream channel with the byte-passthrough descriptor and **pipes** request bytes, metadata, and status straight through in both directions (handling all four streaming shapes via the listener callbacks). This is exactly the pattern Envoy and the `grpc-proxy` libraries use. The staff-level nuances to mention: you must forward **trailers and status** faithfully (don't collapse `grpc-status`), propagate **deadlines** (subtract elapsed time), honor **cancellation** in both directions, preserve **`-bin` metadata**, and avoid double-(de)compression. Because you never parse the payload, the proxy stays schema-agnostic and works for any service — at the cost of not being able to inspect or transform message contents.

## 🧩 Extended Questions — Set 2: Practical scenarios, troubleshooting & coding

### 🟢 — extended

#### Q85. [Practical] You call a gRPC server and immediately get `UNAVAILABLE: io exception` with no response. How do you triage it?

`UNAVAILABLE` at connect time means the transport never became `READY` — the call failed before reaching application code. Triage from the outside in:

1. **Is the address right and reachable?** `UNAVAILABLE` covers connection-refused, DNS failure, and TLS handshake failure. Try `grpcurl -plaintext host:port list` (or `nc -vz host port`) to separate "port closed" from "gRPC not answering."
2. **Plaintext vs TLS mismatch.** The single most common cause: client uses `usePlaintext()` but the server expects TLS (or vice-versa). An h2c client hitting a TLS server, or an ALPN mismatch, surfaces as `UNAVAILABLE` with an SSL/`io exception` cause. Check the nested cause: `e.getStatus().getCause()`.
3. **Wrong scheme / load balancer in front.** An L4 LB or HTTP/1.1-only proxy in the path breaks HTTP/2; a `502`/`GOAWAY` becomes `UNAVAILABLE`.
4. **Server not started / still binding**, or it crashed and the port is now refused.

```java
try {
    stub.withDeadlineAfter(2, SECONDS).getProduct(req);
} catch (StatusRuntimeException e) {
    log.error("code={} desc={} cause={}",
        e.getStatus().getCode(),
        e.getStatus().getDescription(),
        e.getStatus().getCause());   // the nested IOException/SSLException is the real clue
}
```

The key habit: always log `getCause()` — `UNAVAILABLE` is a category, and the wrapped exception (ConnectException, SSLHandshakeException, UnknownHostException) tells you which.

#### Q86. [Practical] A client logs `DEADLINE_EXCEEDED` but the server logs show the request completed successfully. What happened and how do you fix it?

The deadline fired **on the client** while the server was still working (or while the response was in flight). The server finished its work *after* the client gave up, so from the client's perspective it timed out while the server "succeeded." This is a classic distributed-systems race: the deadline is absolute and the client stops waiting; the server may or may not notice the cancellation in time.

Fixes / mitigations:

- **Right-size the deadline** to the realistic p99 of the call, not an optimistic average.
- **Honor cancellation server-side** so the server stops doing useless work once the deadline passes — check `Context.current().isCancelled()` / `ServerCallStreamObserver.isCancelled()` and abort. This prevents "ghost work" that completes after the client left.
- **Idempotency for mutations**: because the client may retry after `DEADLINE_EXCEEDED`, a non-idempotent write could be applied twice (once by the timed-out call that actually succeeded, once by the retry). Use idempotency keys.
- **Propagate deadlines** so the server's downstream calls also stop, instead of one slow hop blowing the whole budget.

```java
// server: bail out as soon as the caller's deadline has passed
if (Context.current().isCancelled()) {
    responseObserver.onError(Status.CANCELLED.withDescription("client gone").asRuntimeException());
    return;
}
```

The interview point: `DEADLINE_EXCEEDED` on the client does **not** mean the operation didn't happen — design writes to be idempotent.

#### Q87. [Practical] Your server throws a `NullPointerException` in a handler and the client receives `UNKNOWN`. Why, and how should you handle exceptions properly?

Any exception that escapes a handler and isn't a `StatusRuntimeException`/`StatusException` is mapped to status `UNKNOWN (2)` with the description and stack trace **stripped** (to avoid leaking internals). So an NPE, an `IllegalStateException`, a downstream `SQLException` — all collapse to an opaque `UNKNOWN`, giving the client nothing actionable.

The fix is to translate exceptions into meaningful statuses, centrally via an interceptor rather than try/catch in every method:

```java
public class ExceptionTranslationInterceptor implements ServerInterceptor {
    @Override
    public <Req, Resp> ServerCall.Listener<Req> interceptCall(
            ServerCall<Req, Resp> call, Metadata headers, ServerCallHandler<Req, Resp> next) {
        ServerCall.Listener<Req> delegate = next.startCall(call, headers);
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
            @Override public void onHalfClose() {
                try { super.onHalfClose(); }
                catch (RuntimeException e) { call.close(toStatus(e), trailers(e)); }
            }
        };
    }
    private Status toStatus(RuntimeException e) {
        if (e instanceof EntityNotFoundException) return Status.NOT_FOUND.withDescription(e.getMessage());
        if (e instanceof ValidationException)    return Status.INVALID_ARGUMENT.withDescription(e.getMessage());
        log.error("unexpected", e);              // log full detail server-side
        return Status.INTERNAL.withDescription("internal error");   // generic to the client
    }
}
```

Use `INTERNAL` (not `UNKNOWN`) for genuine server bugs, log the full stack trace server-side, and return a generic message to the client so you don't leak internals. grpc-java's `TransmitStatusRuntimeExceptionInterceptor` is a built-in helper for part of this.

#### Q88. [Practical] How do you call a gRPC method from the command line to reproduce a bug, without writing any code?

Use **`grpcurl`** — the curl of gRPC. If the server has **reflection** enabled you don't even need the `.proto`:

```bash
# list services / methods / message schema
grpcurl -plaintext localhost:50051 list
grpcurl -plaintext localhost:50051 list shop.v1.ProductService
grpcurl -plaintext localhost:50051 describe shop.v1.GetProductRequest

# invoke a unary method with JSON request (grpcurl maps JSON <-> protobuf)
grpcurl -plaintext -d '{"id":"42"}' localhost:50051 shop.v1.ProductService/GetProduct

# send metadata (e.g., auth) and use TLS
grpcurl -H 'authorization: Bearer TOKEN' -d '{"id":"42"}' \
    shop.example.com:443 shop.v1.ProductService/GetProduct
```

If reflection is **disabled** (typical in prod), point grpcurl at the proto: `grpcurl -import-path ./proto -proto shop.proto ...`. For server-streaming, grpcurl prints each message as it arrives. This is the fastest way to confirm whether a bug is in the server or the client. GUI equivalents: `grpcui`, Postman's gRPC support, Kreya.

#### Q89. [Theory] A teammate sees `RESOURCE_EXHAUSTED: gRPC message exceeds maximum size 4194304`. What does it mean and what are the options?

The inbound message exceeded the default **4 MB** limit (`4194304` bytes). gRPC caps message size to protect memory; the receiver (could be client or server, depending on direction) rejected the frame.

Options, in order of preference:

1. **Don't send giant messages** — chunk the payload into a **stream** of smaller messages (e.g., 64 KB chunks) using a client-streaming or server-streaming RPC. This is the idiomatic fix for files/large lists.
2. **Raise the limit deliberately** on the receiving side if the payload is legitimately large and bounded: `maxInboundMessageSize(N)`. Set it on **both** client and server as appropriate, and size it to real need — raising it blindly is a DoS vector.
3. **Compress** if the data is compressible (`withCompression("gzip")`), though compression doesn't change the decompressed-size limit.

```java
ManagedChannelBuilder.forTarget(t).maxInboundMessageSize(16 * 1024 * 1024).build();   // 16 MB
ServerBuilder.forPort(p).maxInboundMessageSize(16 * 1024 * 1024).build();
```

The interview-grade answer leads with "stream and chunk," not "bump the limit," because unbounded message sizes reintroduce the memory-abuse risk the limit exists to prevent.

#### Q90. [Practical] Write a client-streaming RPC that uploads a large file in fixed-size chunks.

Chunking keeps each message small (under the size limit) and enables progress/backpressure. The first message can carry metadata; subsequent ones carry bytes.

```protobuf
message UploadChunk {
  oneof payload {
    FileInfo info  = 1;   // first message: filename, content type
    bytes    data  = 2;   // subsequent messages: raw bytes
  }
}
message FileInfo { string filename = 1; string content_type = 2; }
message UploadResult { string file_id = 1; int64 bytes_received = 2; }

service FileService {
  rpc Upload (stream UploadChunk) returns (UploadResult);
}
```

Client (async stub) reading the file in 64 KB chunks:

```java
StreamObserver<UploadResult> respObs = new StreamObserver<>() {
    public void onNext(UploadResult r) { System.out.println("stored " + r.getFileId()); }
    public void onError(Throwable t) { latch.countDown(); }
    public void onCompleted() { latch.countDown(); }
};
StreamObserver<UploadChunk> req = asyncStub.upload(respObs);

req.onNext(UploadChunk.newBuilder()
    .setInfo(FileInfo.newBuilder().setFilename("report.pdf").setContentType("application/pdf"))
    .build());

byte[] buf = new byte[64 * 1024];
try (InputStream in = Files.newInputStream(path)) {
    int n;
    while ((n = in.read(buf)) != -1) {
        req.onNext(UploadChunk.newBuilder()
            .setData(ByteString.copyFrom(buf, 0, n))   // copy only the bytes read
            .build());
    }
}
req.onCompleted();      // signal end of stream
latch.await();
```

Note `ByteString.copyFrom(buf, 0, n)` copies only the bytes actually read; copying the whole `buf` would append stale trailing bytes on the last chunk. In production, also honor `isReady()`/`onReadyHandler` so a slow server pushes back instead of buffering the whole file.

#### Q91. [Practical] How do you set a per-call deadline AND attach auth metadata to a single blocking-stub call?

Both are done with `withXxx()` builders, which return a **new** stub (stubs are immutable). Chain them:

```java
Metadata md = new Metadata();
md.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);

Product p = MetadataUtils.attachHeaders(stub, md)        // returns a new stub with the headers
        .withDeadlineAfter(300, TimeUnit.MILLISECONDS)   // returns another new stub
        .getProduct(GetProductRequest.newBuilder().setId("42").build());
```

The common mistake is calling `stub.withDeadlineAfter(...)` and discarding the result (e.g., `stub.withDeadlineAfter(...); stub.getProduct(...)`) — because `with*` doesn't mutate `stub`, the deadline silently has no effect. Always call the method on the returned stub. For cross-cutting auth applied to *every* call, prefer a `ClientInterceptor` or a `CallCredentials` instead of attaching metadata per call site.

#### Q92. [Practical] Reflection is disabled in production but you need to debug a live call. What are your options?

Reflection is off precisely so you can't introspect the API remotely — so bring the schema with you:

1. **Use `grpcurl` with the proto files** instead of reflection: `grpcurl -import-path ./proto -proto shop.proto -d '{...}' host:443 shop.v1.ProductService/GetProduct`. The compiled FileDescriptorSet (`buf build -o image.bin`) works too: `grpcurl -protoset image.bin ...`.
2. **Enable reflection behind auth** rather than fully off — register `ProtoReflectionService` but gate it with an interceptor that only allows internal/operator identities.
3. **Use Channelz** (`grpcdebug`) for *runtime* state (connectivity, sockets, per-method call counts) — it doesn't need reflection and answers "is the connection healthy / are calls failing" questions.
4. **Server logs + interceptors**: a logging interceptor capturing method, status, and latency is often enough to localize the failing method without calling it.

The principle: production debuggability comes from **carrying the contract** (proto/protoset) and **runtime introspection** (Channelz, structured logs), not from leaving reflection open to the world.

### 🟡 — extended

#### Q93. [Practical] A streaming RPC works fine in tests but in production the server OOMs under load. What's the likely cause and the fix?

The overwhelmingly likely cause is **ignored flow control**: the producing side calls `onNext()` in a tight loop regardless of whether the transport can absorb the data, so unsent messages buffer in memory until the heap is exhausted. Tests pass because the in-process/loopback transport is fast and the data set is small; production has slower consumers, real network RTT, and large result sets.

The fix is **demand-driven** streaming — only emit when `isReady()` is true, driven by the `onReadyHandler`:

```java
ServerCallStreamObserver<Row> o = (ServerCallStreamObserver<Row>) resp;
Iterator<Row> src = repo.cursor(req);          // lazy DB cursor, not a materialized List
o.setOnReadyHandler(() -> {
    while (o.isReady() && src.hasNext()) o.onNext(src.next());
    if (!src.hasNext()) o.onCompleted();
});
```

Secondary causes to check: **materializing the whole result set** into a `List` before streaming (defeats the point — use a lazy cursor), missing `isCancelled()` checks (continuing to produce after the client left), and on the inbound side, not using manual `request(n)` so a fast client floods a slow handler. The fingerprint of this bug is heap growth proportional to in-flight stream count and message rate.

#### Q94. [Practical] How do you implement a global server interceptor that logs method, status code, and latency for every RPC?

You must measure latency across the call's lifetime and capture the **final** status, which is only known when the call closes. Wrap the `ServerCall` to observe `close()`:

```java
public class MetricsInterceptor implements ServerInterceptor {
    @Override
    public <Req, Resp> ServerCall.Listener<Req> interceptCall(
            ServerCall<Req, Resp> call, Metadata headers, ServerCallHandler<Req, Resp> next) {

        long startNanos = System.nanoTime();
        String method = call.getMethodDescriptor().getFullMethodName();

        ServerCall<Req, Resp> wrapped =
            new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
                @Override public void close(Status status, Metadata trailers) {
                    long micros = (System.nanoTime() - startNanos) / 1_000;
                    log.info("grpc method={} code={} latencyUs={}", method, status.getCode(), micros);
                    // e.g., Micrometer: timer(method, status.getCode()).record(micros, MICROSECONDS)
                    super.close(status, trailers);
                }
            };
        return next.startCall(wrapped, headers);
    }
}
```

Key subtleties: latency must be measured to `close()` (not to when the handler returns, which for streaming is much earlier); label metrics by **method and status code** (so you can alert on `grpc-status` errors that hide behind HTTP 200); and register the interceptor **globally** (via the framework's global-interceptor mechanism or `ServerBuilder.intercept(...)`) so it covers every service uniformly.

#### Q95. [Practical] A new field you added to a response message is always empty on the client. What are the likely causes?

A field arriving empty/default despite the server setting it points to a **build/version skew**, not a wire problem (the wire is forward/backward compatible by tag number):

1. **Client and server compiled against different `.proto` revisions** — the client's generated class doesn't know tag *N*, so it parses that field into **unknown fields** and `getNewField()` returns the default. Regenerate the client from the updated proto and redeploy.
2. **Server isn't actually setting it** — e.g., it sets the field on a different builder instance, or a mapping layer drops it. Verify with `grpcurl` (which uses the live proto/reflection) to see whether the field is on the wire at all.
3. **proto3 presence confusion**: if the value legitimately *is* the default (`0`/`""`/`false`), a plain scalar can't distinguish "set to default" from "unset." If you must tell them apart, make the field `optional` and check `hasNewField()`.
4. **Tag collision / reuse**: if the new field accidentally reused a `reserved` or previously-used tag number, old data maps incorrectly. Check the proto history.

The fastest discriminator: call the server with `grpcurl` against the **current** proto — if the field is present there but empty in your app, your app's generated code is stale.

#### Q96. [Practical] How do you propagate a tenant ID and request ID from an inbound RPC to outbound RPCs the handler makes?

Use the gRPC **`Context`** (not `ThreadLocal`), because it propagates across async boundaries and is automatically inherited by outbound calls made within it. A server interceptor extracts the metadata and stashes it in the `Context`; a client interceptor reads it back from the `Context` and writes it onto outbound metadata.

```java
static final Context.Key<String> TENANT = Context.key("tenant");
static final Metadata.Key<String> TENANT_MD =
    Metadata.Key.of("x-tenant-id", Metadata.ASCII_STRING_MARSHALLER);

// Inbound server interceptor: metadata -> Context
public <Q,R> ServerCall.Listener<Q> interceptCall(ServerCall<Q,R> call, Metadata h, ServerCallHandler<Q,R> next) {
    Context ctx = Context.current().withValue(TENANT, h.get(TENANT_MD));
    return Contexts.interceptCall(ctx, call, h, next);
}

// Outbound client interceptor: Context -> metadata
public <Q,R> ClientCall<Q,R> interceptCall(MethodDescriptor<Q,R> m, CallOptions o, Channel ch) {
    return new SimpleForwardingClientCall<>(ch.newCall(m, o)) {
        @Override public void start(Listener<R> l, Metadata headers) {
            String t = TENANT.get();
            if (t != null) headers.put(TENANT_MD, t);
            super.start(l, headers);
        }
    };
}
```

The reason `Context` beats `ThreadLocal`: if the handler hops to a worker thread, you wrap the runnable with `Context.current().wrap(task)` and the values (and deadline/cancellation) follow; a bare `ThreadLocal` would be lost across the hop. This is the same mechanism OpenTelemetry uses to carry trace context across hops automatically.

#### Q96b. [Theory] How do you decide between `UNAVAILABLE`, `INTERNAL`, `FAILED_PRECONDITION`, and `ABORTED` for a server-side failure?

Picking the right code drives client behavior (retry vs not), so map by *cause and retryability*:

- **`UNAVAILABLE (14)`** — the service is temporarily down or the request likely **never took effect** (connection failure, server draining, dependency unreachable). Clients **retry** this. Use it when a transient retry could succeed.
- **`INTERNAL (13)`** — a server-side invariant broke / unexpected bug. Generally **not** safely retryable (the request may have partially applied); signals "this is our fault, page someone."
- **`FAILED_PRECONDITION (9)`** — the request is well-formed but the **system state** forbids it right now, and retrying **without changing state** won't help (e.g., "directory not empty," "account not verified"). Do not retry blindly.
- **`ABORTED (10)`** — a **concurrency conflict** (optimistic-lock / transaction abort); the client should retry at a **higher level**, typically after re-reading state (read-modify-write loop).

```java
// optimistic concurrency conflict -> ABORTED, client re-reads and retries
if (!repo.compareAndSet(id, expectedVersion, newValue))
    responseObserver.onError(Status.ABORTED
        .withDescription("version conflict; re-read and retry").asRuntimeException());
```

The discriminators to state: `FAILED_PRECONDITION` = retry won't help until *state* changes; `ABORTED` = retry the whole read-modify-write; `UNAVAILABLE` = transient, retry as-is; `INVALID_ARGUMENT` = bad input, never retry regardless of state.

#### Q97. [Practical] Calls intermittently fail with `UNAVAILABLE` after the connection sits idle. What's happening and how do you fix it?

A network intermediary — cloud load balancer, NAT gateway, or firewall — silently dropped the **idle TCP connection** after its idle timeout (often 60s–350s). gRPC still thinks the connection is alive, so the next RPC tries to use a dead socket and fails with `UNAVAILABLE` before transparently reconnecting.

Fix with **keepalive pings** so the connection is never idle long enough to be reaped, plus retries to paper over the rare unlucky call:

```java
ManagedChannelBuilder.forTarget(t)
    .keepAliveTime(30, TimeUnit.SECONDS)        // ping every 30s (< the LB idle timeout)
    .keepAliveTimeout(5, TimeUnit.SECONDS)
    .keepAliveWithoutCalls(true)                // keep pinging even with no active RPCs
    .build();
```

Critically, the **server must permit** that ping cadence (`permitKeepAliveTime` ≤ the client's `keepAliveTime`, and `permitKeepAliveWithoutCalls(true)`), or it responds with a `too_many_pings` GOAWAY and you've made things worse. Set `keepAliveTime` comfortably below the smallest idle timeout in the path. Pair with a service-config retry on `UNAVAILABLE` for idempotent methods so a connection that dies between pings doesn't surface to the caller.

#### Q98. [Practical] Write a JUnit test that asserts a method returns `INVALID_ARGUMENT` for bad input.

Drive the real service over the **in-process transport** and assert on the thrown `StatusRuntimeException`'s code:

```java
@Rule public final GrpcCleanupRule cleanup = new GrpcCleanupRule();
private ProductServiceGrpc.ProductServiceBlockingStub stub;

@Before public void setUp() throws Exception {
    String name = InProcessServerBuilder.generateName();
    cleanup.register(InProcessServerBuilder.forName(name).directExecutor()
        .addService(new ProductServiceImpl(repo)).build().start());
    stub = ProductServiceGrpc.newBlockingStub(
        cleanup.register(InProcessChannelBuilder.forName(name).directExecutor().build()));
}

@Test public void blankId_isInvalidArgument() {
    StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
        () -> stub.getProduct(GetProductRequest.newBuilder().setId("").build()));

    assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(ex.getStatus().getDescription()).contains("must not be blank");
}
```

Assert on the **code** (stable contract), and optionally a substring of the description; don't assert the full message (brittle). `directExecutor()` makes the test deterministic by running everything on the calling thread. For richer error details (`BadRequest` field violations), unpack them with `StatusProto.fromThrowable(ex)`.

#### Q99. [Practical] How do you validate request fields declaratively instead of hand-writing `if`-checks in every method?

Use **`protovalidate`** (the successor to `protoc-gen-validate`), which lets you express constraints as options on the proto fields and validate at runtime with a single call — no per-field boilerplate.

```protobuf
import "buf/validate/validate.proto";

message CreateProductRequest {
  string name = 1 [(buf.validate.field).string.min_len = 1];
  int64  price_cents = 2 [(buf.validate.field).int64.gt = 0];
  string sku = 3 [(buf.validate.field).string.pattern = "^[A-Z0-9-]{4,20}$"];
}
```

```java
private static final Validator VALIDATOR = ValidatorFactory.newBuilder().build();

@Override public void createProduct(CreateProductRequest req, StreamObserver<Product> resp) {
    ValidationResult result = VALIDATOR.validate(req);
    if (!result.getViolations().isEmpty()) {
        resp.onError(Status.INVALID_ARGUMENT
            .withDescription(result.toString()).asRuntimeException());
        return;
    }
    // ... business logic on validated input
}
```

Centralize this in a **server interceptor** so every request message is validated uniformly before the handler runs, and map violations into the rich `BadRequest` error detail for field-level client feedback. The principle from the security section applies: protobuf gives type safety, not value validation — `protovalidate` fills that gap declaratively.

#### Q100. [Practical] Your unary client uses a blocking stub but you need to fire 50 calls concurrently with a bounded latency budget. How?

A blocking stub serializes calls on the calling thread; to fan out, use the **future stub** (`newFutureStub`) which returns a `ListenableFuture` per call, and bound the whole batch with a deadline.

```java
ProductServiceGrpc.ProductServiceFutureStub fut = ProductServiceGrpc.newFutureStub(channel);

List<ListenableFuture<Product>> futures = ids.stream()
    .map(id -> fut.withDeadlineAfter(300, TimeUnit.MILLISECONDS)
                  .getProduct(GetProductRequest.newBuilder().setId(id).build()))
    .collect(Collectors.toList());

// wait for all (each individually bounded by its own deadline)
ListenableFuture<List<Product>> all = Futures.allAsList(futures);
List<Product> products = all.get(400, TimeUnit.MILLISECONDS);   // overall ceiling
```

All 50 calls multiplex over the **one** HTTP/2 connection (no extra connections needed), so this is cheap. Set a per-call deadline so one slow backend can't stall the batch, and an overall `get` timeout as a backstop. If any call fails, `allAsList` fails fast; use `Futures.successfulAsList` if you want partial results. Don't spin up 50 threads with the blocking stub — that wastes threads for no benefit since the transport is already async.

### 🟠 — extended

#### Q101. [Practical] In production, p99 latency for one method spiked while throughput is normal. The handler does a DB call. Walk through diagnosing it.

Normal throughput + p99 spike on one method points at **tail latency from a shared, contended resource**, not a systemic overload. Diagnose methodically:

1. **Confirm where the time goes**: a per-method latency-histogram metric (from a metrics interceptor) plus a span around the DB call. If the span dominates, it's the DB; if the gap between "call received" and "handler started" is large, it's **executor queuing**.
2. **Executor starvation**: if handlers do blocking DB I/O on an undersized or shared pool, requests queue waiting for a thread → p99 spikes while p50 looks fine. Check pool saturation; move blocking work to a **bounded dedicated pool** (or virtual threads), never `directExecutor`.
3. **Connection-pool contention**: a too-small JDBC pool serializes DB access; the symptom is identical (tail waits). Right-size the pool to the executor concurrency.
4. **Head-of-line / flow-control**: if this method streams, a slow consumer plus ignored `isReady()` can stall. Check for buffering.
5. **GC pauses**: stop-the-world pauses hit p99 specifically; correlate with GC logs.

```java
// metrics interceptor already records latency by method+status; add a span on the DB call
Span span = tracer.spanBuilder("db.getProduct").startSpan();
try (Scope s = span.makeCurrent()) { return repo.find(id); } finally { span.end(); }
```

The discriminating question to state: "is the time in the handler (DB/downstream) or before it (executor queue)?" — that single split routes you to either resource sizing or thread-pool tuning.

#### Q102. [Practical] How do you implement a graceful shutdown that drains in-flight RPCs and cooperates with Kubernetes?

Coordinate three things: tell the orchestrator/LB to stop routing, let in-flight calls finish, then force-stop after a grace window — all within the pod's `terminationGracePeriodSeconds`.

```java
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    // 1. flip health so K8s readiness fails and the Service removes this pod
    health.setStatus("", HealthCheckResponse.ServingStatus.NOT_SERVING);
    // (optional) sleep a few seconds so the readiness change propagates before we stop accepting
    try { Thread.sleep(5_000); } catch (InterruptedException ignored) {}

    server.shutdown();                                   // stop new RPCs, keep in-flight ones
    try {
        if (!server.awaitTermination(25, TimeUnit.SECONDS)) {
            server.shutdownNow();                        // force-cancel stragglers
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    } catch (InterruptedException e) { server.shutdownNow(); }
}));
```

K8s specifics: set `terminationGracePeriodSeconds` larger than your drain budget (here ~30s), use a **readiness** probe wired to the gRPC health service so failing it removes the pod from the Service endpoints, and rely on gRPC's **GOAWAY** (sent on `shutdown()`) so clients migrate to other pods. A `preStop` hook sleeping briefly also helps bridge the window where the pod is `Terminating` but still in some kube-proxy iptables rules. The sequence — *fail readiness → wait for propagation → graceful shutdown → force* — avoids dropping requests during rollouts.

#### Q103. [Practical] Two services occasionally deadlock under load with bidirectional streaming. What's the classic cause and fix?

The classic cause is **mutual flow-control deadlock**: each side fills the other's receive window and then *blocks trying to send more* without reading what's already buffered for it. If side A keeps calling `onNext()` and never drains incoming messages, and B does the same, both stall once the HTTP/2 windows are full — neither can make progress because neither is reading.

Root issues and fixes:

- **Don't block the I/O thread while sending.** If your `StreamObserver.onNext` (or the read loop) blocks waiting to send, you stop reading, which stops the peer's `WINDOW_UPDATE`s. Always **read and write independently**.
- **Honor `isReady()` for sends** and use manual `request(n)` / auto-flow-control for reads, so sending is demand-driven and reading continues.
- **Decouple producer and consumer** with a bounded queue per direction and separate the "read incoming" loop from the "write outgoing" loop, rather than interleaving them on one thread that can block.

```java
ClientCallStreamObserver<Msg> tx = (ClientCallStreamObserver<Msg>) requestObserver;
tx.setOnReadyHandler(this::pumpOutbound);   // only push when the peer can receive
// inbound onNext must keep returning quickly so WINDOW_UPDATEs flow back to the peer
```

The interview framing: bidirectional streaming requires treating the two directions as **independent**; coupling "I'll send my next only after I block-send this one" while not draining inbound is how you create a self-inflicted flow-control deadlock.

#### Q104. [Practical] How do you add automatic, safe retries with backoff for a specific method without writing retry code?

Use a **service config** with a `retryPolicy` scoped to that method, delivered via `defaultServiceConfig` (or the name resolver). gRPC handles attempt buffering, backoff, and throttling for you.

```java
Map<String, Object> serviceConfig = Map.of(
  "methodConfig", List.of(Map.of(
    "name", List.of(Map.of("service", "shop.v1.ProductService", "method", "GetProduct")),
    "retryPolicy", Map.of(
        "maxAttempts", 4.0,
        "initialBackoff", "0.1s",
        "maxBackoff", "2s",
        "backoffMultiplier", 2.0,
        "retryableStatusCodes", List.of("UNAVAILABLE")))));

ManagedChannel ch = ManagedChannelBuilder.forTarget(target)
    .defaultServiceConfig(serviceConfig)
    .enableRetry()                       // retries are off unless explicitly enabled
    .maxRetryAttempts(4)
    .build();
```

Critical safety points: (1) you must call `enableRetry()` — it's off by default; (2) only list **retryable codes that imply the request didn't take effect** (`UNAVAILABLE`), never `INVALID_ARGUMENT`; (3) only enable on **idempotent** methods or pair with idempotency keys; (4) add **retry throttling** (`retryThrottling`) to prevent retry storms during an outage; (5) pair with a deadline so total time across attempts is bounded. Scoping per method (not per service) avoids accidentally retrying mutating calls.

#### Q105. [Practical] A mutating RPC is occasionally executed twice. How do you make it idempotent end-to-end?

Duplicate execution comes from **retries** (built-in, or a client that retried after `DEADLINE_EXCEEDED`/`UNAVAILABLE` where the request actually succeeded). Make the operation idempotent with a client-supplied **idempotency key** and server-side dedup:

```protobuf
message TransferRequest {
  string idempotency_key = 1;   // client-generated UUID, stable across retries
  string from = 2; string to = 3; int64 amount_cents = 4;
}
```

```java
@Override public void transfer(TransferRequest req, StreamObserver<TransferResult> resp) {
    String key = req.getIdempotencyKey();
    // atomic "insert if absent" on the dedup store keyed by idempotency_key
    Optional<TransferResult> prior = dedupStore.getIfPresent(key);
    if (prior.isPresent()) { resp.onNext(prior.get()); resp.onCompleted(); return; }  // replay stored result

    TransferResult result = doTransferTransactionally(req);   // unique constraint on key as a backstop
    dedupStore.put(key, result, Duration.ofHours(24));        // TTL'd
    resp.onNext(result); resp.onCompleted();
}
```

Design points to state: the key must be **generated by the client and reused on retries** (not regenerated); the dedup check + business write should be **atomic** (a DB unique constraint on the key is the durable backstop, the cache is the fast path); store and **return the original result** on duplicates (don't re-execute and don't just say "duplicate"); and TTL the keys. This converts at-least-once delivery into **effectively-once** processing — true exactly-once over a network is unattainable.

#### Q106. [Practical] How do you load-test a gRPC service and interpret the results?

Use a gRPC-aware load tool — `ghz` is the standard — because HTTP/1 tools (ab, wrk) can't speak gRPC, and naive ones may open one connection and serialize requests.

```bash
ghz --insecure \
  --proto ./shop.proto --call shop.v1.ProductService/GetProduct \
  -d '{"id":"42"}' \
  -c 50 -n 200000 \           # 50 concurrent streams, 200k total requests
  --connections 5 \           # spread load over multiple HTTP/2 connections
  shop.internal:50051
```

Interpretation and pitfalls:

- **Use multiple `--connections`** — a single HTTP/2 connection is capped by `MAX_CONCURRENT_STREAMS` and one client CPU, so one connection under-measures the server. This mirrors the real LB problem: one connection pins to one backend.
- **Watch p50/p95/p99 and the status-code distribution**, not just RPS — a high `UNAVAILABLE`/`RESOURCE_EXHAUSTED` rate means you're hitting limits, and average latency hides tail problems.
- **Saturate server-side, not client-side**: confirm the client/box isn't the bottleneck (CPU, GC). Compare against server metrics (executor queue depth, GC, DB pool).
- **Test the real path** including TLS and any L7 proxy, since those add latency and CPU that plaintext-localhost tests miss.

The takeaway: a single-connection, status-blind load test gives misleading numbers; measure across connections, read the tail, and correlate with server-side resource metrics.

#### Q107. [Practical] How do you implement per-client rate limiting in a gRPC server, returning the right status and signaling retry timing?

Enforce a quota in a **server interceptor** keyed by caller identity (from mTLS cert, JWT subject, or an API-key metadata header), reject over-limit calls with `RESOURCE_EXHAUSTED`, and attach a `RetryInfo` detail so clients back off correctly.

```java
public class RateLimitInterceptor implements ServerInterceptor {
    private final RateLimiterRegistry limiters;   // e.g., per-key token buckets

    @Override public <Q,R> ServerCall.Listener<Q> interceptCall(
            ServerCall<Q,R> call, Metadata headers, ServerCallHandler<Q,R> next) {
        String caller = identify(headers);                 // cert/JWT/api-key
        if (!limiters.forKey(caller).tryAcquire()) {
            com.google.rpc.Status rich = com.google.rpc.Status.newBuilder()
                .setCode(Code.RESOURCE_EXHAUSTED.getNumber())
                .setMessage("rate limit exceeded")
                .addDetails(Any.pack(RetryInfo.newBuilder()
                    .setRetryDelay(Duration.newBuilder().setSeconds(1)).build()))
                .build();
            StatusRuntimeException e = StatusProto.toStatusRuntimeException(rich);
            call.close(e.getStatus(), e.getTrailers() == null ? new Metadata() : e.getTrailers());
            return new ServerCall.Listener<>() {};         // short-circuit
        }
        return next.startCall(call, headers);
    }
}
```

Design notes: use `RESOURCE_EXHAUSTED` (the canonical quota code), include **`RetryInfo`** so well-behaved clients honor the suggested delay instead of hammering, key the limiter on a **stable identity** (not IP, which NAT collapses), and for a fleet use a **distributed** limiter (Redis token bucket) so the limit is global rather than per-instance. This pairs with client-side retry throttling to avoid amplifying load during a limit breach.

#### Q108. [Practical] How would you debug "the call hangs forever and never returns"?

A hang means neither a response nor a status ever arrives. Work the layers:

1. **Is there a deadline?** The first cause of "hangs forever" is **no deadline** plus a stuck server. Always set `withDeadlineAfter`; if adding one turns the hang into `DEADLINE_EXCEEDED`, the server side is the problem.
2. **Server never completes the observer.** A handler that returns without calling `onCompleted()`/`onError()` (or a streaming server that stops calling `onNext` and never completes) leaves the client waiting. Audit every code path for a terminal call; a missing `onCompleted` in an exception branch is classic.
3. **Wait-for-ready with no deadline** on a down backend queues the call indefinitely. Pair `withWaitForReady()` with a deadline, always.
4. **Flow-control stall** in streaming: the client isn't reading (so `WINDOW_UPDATE`s stop) and the server blocks on a full window. Check `isReady()` handling on both sides.
5. **Deadlocked executor**: all handler threads are blocked (e.g., on a `directExecutor` doing blocking I/O, or a pool exhausted by a downstream that's also hung). Thread-dump the server.
6. **Use Channelz/grpcdebug** to see whether the call even started a stream and what state the subchannel is in.

The first move is always **add a deadline** — it converts an unbounded hang into a diagnosable `DEADLINE_EXCEEDED`, and where it surfaces (client connect, server processing) localizes the fault.

### 🔴 — extended

#### Q109. [Practical] Design and sketch a generic gRPC retry-budget + circuit-breaker layer for a polyglot fleet where you can't rely on each language's xDS support.

When languages have uneven xDS/outlier-detection support, you standardize resilience in a **shared interceptor library** (one per language, same config schema) rather than depending on the runtime. The layer combines a **retry budget** (token bucket, not fixed max-attempts) with a **per-endpoint circuit breaker**.

```java
public class ResilienceInterceptor implements ClientInterceptor {
    private final TokenBucket retryBudget;                 // refills on success, drains on retry
    private final Map<String, CircuitBreaker> breakers;    // per target host

    @Override public <Q,R> ClientCall<Q,R> interceptCall(
            MethodDescriptor<Q,R> m, CallOptions o, Channel ch) {
        CircuitBreaker cb = breakers.computeIfAbsent(target(ch), k -> new CircuitBreaker());
        if (cb.isOpen()) // fail fast without touching the network
            return failedCall(Status.UNAVAILABLE.withDescription("circuit open"));

        return new RetryingClientCall<>(m, o, ch, retryBudget, cb,
            /*retryable*/ Set.of(Status.Code.UNAVAILABLE),
            /*maxAttempts*/ 3, /*backoff*/ exp(50, 2, 1_000));
    }
}
```

Key design decisions to articulate:

- **Retry budget over fixed attempts**: a token bucket caps *aggregate* retries (e.g., retries ≤ 10% of requests), so an outage can't multiply load 3× — this is what prevents **retry-storm cascading failure**. Fixed `maxAttempts` per call doesn't bound fleet-wide amplification.
- **Circuit breaker per endpoint** trips on sustained failure, fails fast (no network), and half-opens to probe recovery — containing blast radius locally, like outlier detection but in the app layer.
- **Only retry idempotent methods / on `UNAVAILABLE`**; carry idempotency keys for mutations.
- **Deadline-aware**: each retry uses the *remaining* budget from the `Context` deadline, never restarting the clock.
- **Uniform config + telemetry**: same JSON config and the same metrics (`retries`, `breaker_state`, `budget_remaining`) across languages, so ops reason about the whole fleet identically.

The thesis: when you can't push resilience into the transport (xDS), push it into a **consistent, well-tested interceptor library** with budget-based retries and local circuit breaking — the goal is to *avoid amplifying* failures, which naive per-call retries actively worsen.

#### Q110. [Practical] You must migrate a high-traffic REST endpoint to gRPC with zero downtime and instant rollback. Sketch the plan.

Treat it as a **dual-stack, traffic-shifted migration** gated by metrics, never a flag-day cutover:

1. **Contract-first**: define the `.proto`, review it like an API change, and run `buf lint` + `buf breaking` in CI. Keep request/response semantics equivalent to the REST contract.
2. **Serve both protocols from one backend**: implement the gRPC service over the *same* domain/service layer the REST controller already calls, so there's a single source of truth. Optionally use **gRPC-JSON transcoding** or **Connect** so one server speaks REST and gRPC, reducing drift.
3. **Shadow / mirror traffic**: send a copy of live REST requests to the gRPC path (responses discarded) to validate correctness and load under real traffic *before* any user depends on it. Diff results offline.
4. **Progressive client cutover**: move callers behind a **feature flag / client-side switch**; ramp 1% → 10% → 50% → 100%, watching p99 latency, error-rate (by `grpc-status`, not just HTTP 200), and resource metrics at each step.
5. **Instant rollback**: the flag flips callers back to REST in seconds; because both stacks stay live and the backend logic is shared, rollback is config-only — no redeploy.
6. **Operational readiness first**: gRPC-aware LB (per-RPC), dashboards keyed on `grpc-status`, keepalive/connection-aging tuned, deadlines + retries configured — stand these up *before* shifting traffic.
7. **Decommission** REST only after telemetry shows sustained zero REST traffic over a bake period.

The senior emphasis: zero-downtime comes from **running both in parallel with shared business logic and metric-gated, reversible traffic shifting** (shadow → canary → ramp), plus having gRPC's operational surface (LB, observability, deadlines) ready *first* — the protocol swap is the easy part; the safety net is the engineering.

#### Q111. [Practical] A protobuf change shipped and some consumers are now mis-parsing data — fields shifted. Diagnose the root cause and the remediation.

"Fields shifted / wrong values appear under the wrong field" is the signature of a **tag-number violation** — the one truly unsafe protobuf change. Likely root causes:

- A **field's tag number was changed** (renaming is safe; renumbering is not), so old data's tag *N* now maps to a different field in the new schema.
- A **deleted field's tag was reused** for a new field (no `reserved`), so persisted/in-flight data with the old meaning is parsed as the new field.
- A **type change on the same tag** (e.g., `int32` → `string`, or `int32` → `message`) with incompatible wire types, causing parse corruption or dropped data.

Remediation:

1. **Stop the bleed**: roll back the producer/schema to restore the original tag assignments, or fast-forward consumers — but if tags were *reused*, any data written in the bad window may already be ambiguous.
2. **Restore tags exactly**: re-pin the original tag numbers; move the new field to a **fresh, never-used** tag; `reserved` the disputed numbers/names so it can't recur.
3. **Quarantine ambiguous data**: data serialized during the incompatible window can't be auto-disambiguated by tag — you may need a version marker or out-of-band knowledge to reinterpret it.
4. **Prevent recurrence**: wire **`buf breaking`** into CI against the registry baseline so renumber/reuse/type-change **cannot merge**; this is the automated enforcement of the "never reuse a tag" rule.

The staff-level point: protobuf compatibility lives entirely in **stable tag numbers**; this class of incident is preventable only by *tooling-enforced* breaking-change detection, because human review misses it under deadline pressure.

#### Q112. [Practical] Design end-to-end backpressure for a pipeline: gRPC client-stream in → async processing → gRPC client-stream out to a downstream service, with bounded memory under bursts.

The goal is that a slow downstream (or slow processor) **propagates backpressure all the way back to the original sender**, so memory stays bounded instead of buffering the burst. You couple three flow-control boundaries: inbound gRPC demand, the processing stage, and outbound gRPC readiness.

```java
public StreamObserver<InItem> ingest(StreamObserver<Ack> ackObs) {
    ServerCallStreamObserver<Ack> in = (ServerCallStreamObserver<Ack>) ackObs;
    in.disableAutoRequest();                       // we control inbound demand
    in.request(1);                                 // prime: ask for one

    // outbound stream to the downstream service
    ClientCallStreamObserver<OutItem>[] out = openDownstream();

    return new StreamObserver<>() {
        @Override public void onNext(InItem item) {
            process(item).thenAccept(result -> {
                // only forward when the downstream transport is ready (its flow control)
                if (out[0].isReady()) out[0].onNext(result);
                else enqueueBounded(result);       // small bounded buffer; block/park if full
                // only pull the next inbound item after this one is handed off
                in.request(1);
            });
        }
        @Override public void onError(Throwable t) { out[0].cancel("upstream error", t); }
        @Override public void onCompleted() { drainThen(out[0]::onCompleted); in.onCompleted?; }
    };
}
```

The mechanism, stated explicitly:

- **Inbound**: `disableAutoRequest()` + `request(1)`-after-handoff means we never pull faster than we process — this sends HTTP/2 `WINDOW_UPDATE`s upstream only as we drain, so the **original client slows down** when we're behind.
- **Processing stage**: a **bounded** queue (or `request(1)` gating) caps in-flight items; under a burst it fills and stalls demand rather than growing the heap.
- **Outbound**: forward only when the downstream `isReady()`; if the downstream is slow, items don't leave the bounded buffer, which stops us pulling inbound, which stops the original sender — the chain is complete.
- **Deadlines/cancellation** propagate so a stuck downstream eventually fails the whole pipeline instead of hanging.

The thesis: bounded memory under bursts requires **demand to flow backwards** through every stage — inbound `request(n)`, a bounded middle, and outbound `isReady()` — so the slowest stage sets the pace. Any unbounded buffer between two stages breaks the chain and reintroduces OOM. For very bursty or decoupled workloads, insert a **durable broker** (Kafka) between stages instead of trying to make synchronous gRPC streaming absorb the burst.

## ✅ Key Takeaways

- gRPC = **Protocol Buffers (contract-first IDL + binary encoding) + HTTP/2 transport + generated polyglot stubs**; it shines for internal, synchronous, strongly-typed service-to-service traffic.
- Know the **four RPC types** (unary, server-streaming, client-streaming, bidirectional) and that grpc-java models them all with `StreamObserver`.
- **Channels are long-lived and shared**; stubs are cheap and derived from channels; always attach a **deadline** to every call (absolute, propagated) and honor **cancellation** server-side.
- The **status model** (codes like `UNAVAILABLE`, `DEADLINE_EXCEEDED`, `INVALID_ARGUMENT`) drives retry decisions; status rides in HTTP/2 **trailers**, so HTTP `:status` is almost always 200.
- **Interceptors** are the home for auth, tracing, logging, and limits; **metadata** carries out-of-band context (never business data).
- **Schema evolution** depends on stable **tag numbers**: add fields, never reuse/repurpose tags, `reserved` what you remove; version by **package** (`v1`, `v2`).
- Production concerns: **per-RPC load balancing** (not per-connection), **mTLS**, **keepalive/connection aging**, **flow-control/backpressure**, **retries with backoff on idempotent calls**, **health checking**, and **reflection** (dev only).
- **gRPC-Web/transcoding/Connect** bridge gRPC to browsers and REST clients from the same `.proto`.

## ⚠️ Common Pitfalls

- **Creating a `ManagedChannel` per request** — they're expensive; create one per target and share it.
- **Omitting deadlines**, letting a hung server block clients forever; or using per-hop timeouts that don't compose across a call tree.
- **Throwing raw exceptions** from server methods → opaque `UNKNOWN`; always `onError` with a meaningful `Status`.
- **Putting a TCP/L4 load balancer** in front of gRPC — it pins all traffic to one backend; balance **per-RPC** (client-side or L7).
- **Reusing or renumbering protobuf tag numbers**, or deleting a field without `reserved` — silent wire corruption for old clients.
- **Ignoring flow control** (`isReady()`/`request(n)`) in streaming → unbounded buffering and OOM under load.
- **Retrying non-idempotent calls** or retrying on non-retryable codes (e.g., `INVALID_ARGUMENT`) — causes duplicate side effects or wasted work; no idempotency keys for mutations.
- **Treating protobuf typing as security/validation** — you still need authn, authz, value validation (`protovalidate`), and rate limits.
- **Leaving reflection enabled in production**, exposing the full API surface.
- **Monitoring only HTTP status** and missing `grpc-status` application errors hidden behind HTTP 200.

## 📚 Further Reading

- **Official gRPC documentation** (grpc.io) — concepts, language guides (incl. grpc-java), and the gRPC Core Concepts/Guides pages.
- **Protocol Buffers documentation** (protobuf.dev) — proto3 language guide, encoding internals, and best practices for schema evolution.
- **gRPC GitHub design docs** (`grpc/grpc/doc`) — authoritative specs for status codes, health checking, server reflection, retries, keepalive, and load balancing.
- **Buf documentation & style guide** (buf.build) — modern protobuf tooling, breaking-change detection, the Buf Schema Registry, and the Connect protocol.
- **Kasun Indrasiri & Danesh Kuruppu**, *gRPC: Up and Running* (O'Reilly) — end-to-end practical coverage across languages and patterns.
- **Envoy / Istio documentation** — gRPC-aware L7 load balancing, gRPC-JSON transcoding, gRPC-Web, and xDS/proxyless service mesh.
- **OpenTelemetry gRPC instrumentation docs** — context propagation, tracing, and metrics for gRPC in production.
