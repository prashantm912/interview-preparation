# Serverless & FaaS Patterns

[← Back to master index](../README.md)

Serverless computing (and Function-as-a-Service in particular) lets you run code in response to events without provisioning or managing servers, paying only for actual execution. This guide focuses on AWS Lambda as the canonical FaaS platform — its execution model, cold starts, concurrency controls, event integrations, orchestration with Step Functions, and the architectural patterns (statelessness, idempotency, fan-out/fan-in) that make serverless robust. Examples are in Java, since the JVM's startup behaviour makes many of these trade-offs especially concrete.

## 🟢 Basic (0–2 yrs)

### Q1. [Theory] What is "serverless" and how does FaaS fit into it?

"Serverless" is a deployment model where the cloud provider fully manages the underlying servers — provisioning, scaling, patching, and capacity planning are abstracted away. You are billed for actual consumption (invocations, execution time, requests) rather than for idle capacity. The name is a misnomer: servers still exist, you just never see or manage them.

Serverless is an umbrella covering several service categories:

```
Serverless umbrella
├── FaaS (Function-as-a-Service)   → AWS Lambda, Azure Functions, Google Cloud Functions
├── Serverless containers          → AWS Fargate, Cloud Run, Azure Container Apps
├── Serverless data stores         → DynamoDB on-demand, Aurora Serverless, S3
└── Serverless integration         → API Gateway, EventBridge, SQS, SNS
```

FaaS is the compute pillar: you upload a small unit of code (a "function"), bind it to one or more event sources, and the platform runs it on demand, scaling from zero to thousands of concurrent executions and back to zero automatically. The key distinction from PaaS is that you deploy functions, not long-running applications, and you genuinely pay nothing when no events arrive.

### Q2. [Theory] What are the core characteristics that define a FaaS platform?

1. **Event-driven invocation** — functions run in response to a trigger (HTTP request, queue message, file upload, timer), never as a persistently running process you start.
2. **Stateless execution** — no guarantee that two invocations share memory or local state; persistent state must live elsewhere (DynamoDB, S3, Redis).
3. **Automatic scaling** — the platform creates and destroys execution environments to match load, including scaling to zero.
4. **Pay-per-use billing** — you're billed for the number of invocations and the compute time consumed (rounded to the millisecond on Lambda), not for reserved capacity.
5. **Ephemeral, managed runtime** — the provider owns the OS, the patching, and the lifecycle of the execution environment. Functions have bounded execution time (15 minutes max on Lambda).

### Q3. [Theory] Walk through the AWS Lambda execution model.

When an event arrives, Lambda routes it to an **execution environment** (a lightweight micro-VM, historically built on Firecracker). The lifecycle has three phases:

```
┌──────────────┐   ┌──────────────┐   ┌──────────────┐
│    INIT      │ → │   INVOKE     │ → │   SHUTDOWN   │
│ (cold start) │   │  (handler)   │   │ (eviction)   │
└──────────────┘   └──────────────┘   └──────────────┘
  - download code    - run handler      - after idle
  - start runtime    - per-event        - SIGTERM to
  - run init code    - reused warm         extensions
```

- **Init phase**: Lambda downloads your code/image, bootstraps the runtime (e.g. the JVM), and runs everything *outside* the handler — static initializers, constructors, SDK client setup. This happens once per execution environment.
- **Invoke phase**: your handler method runs for each event. If the environment is reused ("warm"), only this phase runs.
- **Shutdown phase**: after a period of inactivity, Lambda freezes then destroys the environment, giving registered extensions a SIGTERM.

A single environment handles **one event at a time**. Concurrency comes from running many environments in parallel, not from threading within one.

### Q4. [Theory] What is a cold start, and why does it matter more for Java?

A **cold start** occurs when Lambda must create a brand-new execution environment because no warm one is available — typically on the first request, after scaling up, or after idle eviction. The full INIT phase runs: code download, runtime bootstrap, and your initialization code.

```
Cold:  [download][JVM boot][static init][handler]  ← seconds for Java
Warm:  ........................................[handler]  ← milliseconds
```

Java is hit hardest because the JVM has to start, load and verify classes, and JIT compilation hasn't warmed up yet. A naive Spring-on-Lambda cold start can be **3–8 seconds**, versus tens of milliseconds for a warm invocation or for a Node.js/Python cold start. This is why Java-specific mitigations (SnapStart, GraalVM native images, trimming dependencies, AWS SDK v2 with the URLConnection HTTP client) matter so much.

### Q5. [Practical] How do you write a basic AWS Lambda handler in Java?

Implement the `RequestHandler<Input, Output>` interface from the `aws-lambda-java-core` library. Lambda deserializes the event JSON into your input type and serializes your return value back to JSON.

```java
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

public class GreetingHandler implements RequestHandler<GreetingRequest, GreetingResponse> {

    @Override
    public GreetingResponse handleRequest(GreetingRequest input, Context context) {
        context.getLogger().log("Received name: " + input.getName());
        String message = "Hello, " + input.getName() + "!";
        return new GreetingResponse(message);
    }
}

class GreetingRequest  { private String name; /* getters/setters */ }
class GreetingResponse { private String message; /* ctor + getters */ }
```

The handler is configured in Lambda as `com.example.GreetingHandler::handleRequest`. The `Context` object exposes request metadata: remaining execution time, request ID, log group, and memory limit.

### Q6. [Practical] Why should you initialize SDK clients outside the handler?

Anything created outside `handleRequest` runs once during INIT and is **reused across warm invocations** in the same environment. Creating a DynamoDB or HTTP client per invocation wastes the entire connection-setup cost on every request.

```java
public class OrderHandler implements RequestHandler<Order, String> {

    // Created ONCE during INIT, reused by every warm invocation.
    private static final DynamoDbClient DDB = DynamoDbClient.builder()
            .httpClientBuilder(UrlConnectionHttpClient.builder()) // lighter than Netty for Lambda
            .build();

    @Override
    public String handleRequest(Order order, Context ctx) {
        DDB.putItem(b -> b.tableName("orders").item(toItem(order)));
        return "ok";
    }
}
```

Putting the client in a `static` field (or instance field initialized in the constructor) means the expensive connection pool and credential resolution happen during the cold start, not on the hot path. The trade-off: this code runs during INIT, so heavy init increases cold-start latency — but it's amortized over the lifetime of the warm environment.

### Q7. [Theory] What is the difference between synchronous and asynchronous invocation?

- **Synchronous** (`RequestResponse`): the caller waits for the function to finish and receives the result. Used by API Gateway, Application Load Balancer, and direct `Invoke` calls. Errors are returned to the caller, which is responsible for retries.
- **Asynchronous** (`Event`): the caller hands the event to Lambda's internal queue and returns immediately. Lambda invokes the function in the background, **automatically retries twice on failure** (with delays), and can route failures to a dead-letter queue or on-failure destination. Used by S3, SNS, EventBridge.

```
Sync :  caller ── invoke ──▶ Lambda ── result ──▶ caller   (caller blocks)
Async:  caller ── event  ──▶ [queue] ──▶ Lambda             (caller returns instantly)
                                  │ on error: retry x2 → DLQ
```

A third mode, **stream/poll-based** (SQS, Kinesis, DynamoDB Streams), has Lambda's poller read batches and invoke your function; retry/error semantics differ again (batch-level for SQS standard).

### Q8. [Theory] What event sources can trigger a Lambda function?

Common triggers fall into three integration styles:

- **Synchronous (request/response)**: API Gateway, Application Load Balancer, Lambda Function URLs, direct SDK `Invoke`.
- **Asynchronous (fire-and-forget)**: S3 events, SNS, EventBridge rules/schedules, CloudWatch alarms, SES.
- **Poll-based (event source mapping)**: SQS, Kinesis Data Streams, DynamoDB Streams, Amazon MQ, self-managed and MSK Kafka.

Each style has different batching, retry, ordering, and error-handling semantics — knowing which style a source uses is essential for designing correct error handling.

### Q9. [Theory] What is API Gateway and how does it work with Lambda?

API Gateway is a managed service that fronts your functions with an HTTP/REST or WebSocket API. It handles request routing, authentication/authorization (IAM, Cognito, Lambda authorizers, JWT), throttling, request/response transformation, and caching, then invokes Lambda synchronously.

```
Client ──HTTPS──▶ API Gateway ──▶ Lambda ──▶ DynamoDB
                   │ auth
                   │ throttle / rate limit
                   │ validate / transform
                   └ map response
```

Two main flavours: **REST APIs** (feature-rich: API keys, usage plans, request validation, WAF) and **HTTP APIs** (cheaper, lower latency, simpler — good default for most Lambda backends). For very simple cases, **Lambda Function URLs** give a function its own HTTPS endpoint with no API Gateway at all.

### Q10. [Practical] How do you handle an API Gateway proxy event in Java?

With proxy integration, API Gateway passes the whole HTTP request as a JSON envelope and expects a specific response shape. Use the prebuilt event types from `aws-lambda-java-events`.

```java
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

public class ApiHandler implements
        RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    @Override
    public APIGatewayProxyResponseEvent handleRequest(
            APIGatewayProxyRequestEvent req, Context ctx) {

        String userId = req.getPathParameters().get("userId");
        String body   = "{\"userId\":\"" + userId + "\"}";

        return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withHeaders(Map.of("Content-Type", "application/json"))
                .withBody(body);
    }
}
```

You must return the status code, headers, and a (string) body; API Gateway maps these straight back to the client. Note that throwing an exception here surfaces as a 502 to the client unless you catch it and return a structured error response.

### Q11. [Theory] What are Lambda's key limits and timeouts?

Know these cold:

| Limit | Value |
|-------|-------|
| Max execution timeout | 15 minutes |
| Memory | 128 MB – 10,240 MB (10 GB) |
| vCPUs | scale with memory (up to ~6 vCPUs at 10 GB) |
| Deployment package (zipped) | 50 MB direct upload / 250 MB unzipped |
| Container image size | up to 10 GB |
| `/tmp` ephemeral storage | 512 MB – 10,240 MB |
| Payload (sync) | 6 MB |
| Payload (async) | 256 KB |
| Default account concurrency | 1,000 (soft, can be raised) |
| Environment variables | 4 KB total |

The 15-minute ceiling is the hard wall that pushes long jobs toward Step Functions, Fargate, or batch processing.

### Q12. [Theory] How does Lambda allocate CPU, and why does memory setting affect performance?

On Lambda you do **not** set CPU directly — you set **memory**, and CPU (plus network and disk I/O bandwidth) scales proportionally. At 1,769 MB you get roughly one full vCPU; at 10 GB you get around 6 vCPUs.

This has a counter-intuitive consequence: increasing memory can make a CPU-bound function *faster and cheaper*. A function that runs 2 s at 512 MB might run 0.5 s at 2 GB. Even though the per-ms rate is higher, the much shorter duration can lower total cost — and the user-facing latency drops. Right-sizing memory (with tools like AWS Lambda Power Tuning) is one of the highest-leverage optimizations available.

### Q13. [Practical] How do you read configuration and secrets in a Lambda function?

Use environment variables for non-sensitive config, and a secrets service (Secrets Manager / SSM Parameter Store) for sensitive values — fetched during INIT and cached.

```java
public class ConfigHandler implements RequestHandler<Object, String> {

    private static final String TABLE = System.getenv("TABLE_NAME");

    // Fetched once during INIT, cached for the life of the environment.
    private static final String API_KEY = fetchSecret(System.getenv("SECRET_ARN"));

    private static String fetchSecret(String arn) {
        try (SecretsManagerClient c = SecretsManagerClient.create()) {
            return c.getSecretValue(b -> b.secretId(arn)).secretString();
        }
    }

    @Override
    public String handleRequest(Object event, Context ctx) {
        return "table=" + TABLE;
    }
}
```

For higher-frequency or hot-reloadable config, the **AWS Parameters and Secrets Lambda Extension** runs a local cache as a sidecar so you avoid an API call on every cold start. Never bake secrets into environment variables in plaintext for sensitive data — they're visible to anyone with `GetFunctionConfiguration`.

### Q14. [Theory] What does "stateless" mean for a Lambda function, and where does state go?

Stateless means a function must not rely on any local state surviving between invocations. Even though warm environments *can* reuse memory, you get **no guarantee**: the next request may hit a different environment, or the environment may have been recycled.

```
Wrong: store user session in a static HashMap in the function
       → next request lands on a different warm env → cache miss / data loss
```

Durable state belongs in external services: DynamoDB or RDS for structured data, S3 for blobs, ElastiCache/MemoryDB for shared caches, Step Functions for workflow state. The only legitimate local "state" is a per-environment **cache** for read-mostly data (config, connection pools) where a miss is harmless and simply triggers a re-fetch.

### Q15. [Behavioral / Theory] When would you choose serverless, and when would you avoid it?

**Good fit**: spiky or unpredictable traffic, event-driven glue (S3-triggered processing, webhook handlers), low-to-moderate steady volume where pay-per-use beats always-on, rapid prototyping, and teams that want to minimize ops.

**Poor fit**:
- **Sustained high throughput** — at constant heavy load, a right-sized container/EC2 fleet is usually cheaper than per-invocation billing.
- **Long-running jobs** — anything over 15 minutes.
- **Ultra-low-latency, latency-SLA-critical paths** sensitive to cold starts (though SnapStart/provisioned concurrency mitigate this).
- **Heavy local state or large in-memory datasets** that you'd reload on every cold start.
- **Specialized hardware** (GPUs) or workloads needing fine-grained network/kernel control.

The honest framing: serverless trades higher per-unit compute cost and some latency variability for near-zero operational overhead and true scale-to-zero.

## 🟡 Intermediate (3–7 yrs)

### Q16. [Theory] Explain concurrency in Lambda: account, reserved, and provisioned.

**Concurrency** = the number of in-flight executions at a given instant (roughly: invocation rate × average duration).

- **Account (unreserved) concurrency**: a shared pool, default 1,000 per region, used by all functions that don't reserve any.
- **Reserved concurrency**: a fixed slice carved out for one function. It both *guarantees* that function up to N concurrent executions **and caps** it at N (excess invocations are throttled). It protects downstream systems and prevents one function from starving others.
- **Provisioned concurrency**: a number of pre-initialized, warm environments kept ready so requests skip the cold start entirely. You pay for these whether used or not.

```
Reserved concurrency = guarantee + ceiling (no warm-up)
Provisioned concurrency = pre-warmed envs (eliminates cold starts, costs $$)
```

Reserved is about *capacity allocation*; provisioned is about *latency*. They compose: you can set provisioned concurrency within a function's reserved limit.

### Q17. [Theory] What is provisioned concurrency and when is it worth it?

Provisioned concurrency keeps a configured number of execution environments fully initialized (INIT already done, optionally with code warmed via init hooks) so matching invocations run with **no cold start**. It's the strongest latency guarantee Lambda offers.

Use it when you have:
- A **latency SLA** that cold starts would violate (user-facing APIs).
- **Predictable traffic peaks** — combine with Application Auto Scaling to ramp provisioned concurrency on a schedule.

The catch is cost: you pay an hourly rate for each provisioned environment regardless of traffic, so it only makes sense when the warm capacity is actually used. For Java specifically, **SnapStart** is often a cheaper alternative that addresses the same cold-start pain without per-hour charges.

### Q18. [Theory] What is Lambda SnapStart and how does it help Java?

SnapStart attacks Java cold starts by taking a **Firecrawl micro-VM snapshot** of the fully initialized environment after INIT, then restoring from that snapshot on future cold starts instead of re-running the JVM boot and init code.

```
Without SnapStart:  [JVM boot][class load][static init][handler]  ← per cold start
With SnapStart:     publish version → snapshot taken once
                    cold start = [restore snapshot][handler]      ← ~10x faster
```

It can cut Java cold starts from seconds to a few hundred milliseconds, at no extra charge (you pay only a small cost for caching the snapshot, and for the restore). As of 2026 SnapStart is available for Java, Python, and .NET. Key caveats:

- The snapshot is taken at **publish time** on a specific version — uniqueness and freshness concerns arise.
- Anything captured in the snapshot (random seeds, cached timestamps, open connections, ephemeral credentials) is frozen. Use the **`Resource` / runtime hooks** (`beforeCheckpoint` / `afterRestore`) to regenerate per-instance state.

### Q19. [Practical] How do you handle SnapStart correctly in code (the uniqueness problem)?

Because the snapshot is restored into many environments, anything that must be unique or fresh per instance has to be regenerated *after* restore. The classic bug: a seeded `SecureRandom` or a cached connection captured in the snapshot is shared across all restored instances.

```java
import org.crac.Context;
import org.crac.Core;
import org.crac.Resource;

public class TokenHandler implements RequestHandler<Req, Res>, Resource {

    private SecureRandom random;

    public TokenHandler() {
        random = new SecureRandom();   // would be frozen in the snapshot
        Core.getGlobalContext().register(this);
    }

    @Override
    public void beforeCheckpoint(Context<? extends Resource> c) {
        // release things that must not be in the snapshot (e.g. close DB conns)
    }

    @Override
    public void afterRestore(Context<? extends Resource> c) {
        random = new SecureRandom();   // fresh entropy per restored instance
    }

    @Override
    public Res handleRequest(Req req, com.amazonaws.services.lambda.runtime.Context ctx) {
        byte[] token = new byte[16];
        random.nextBytes(token);
        return new Res(Base64.getEncoder().encodeToString(token));
    }
}
```

Use the open-source **CRaC (`org.crac`) API**. The rule of thumb: any per-instance uniqueness, time-sensitive value, or network connection should be torn down in `beforeCheckpoint` and recreated in `afterRestore`.

### Q20. [Theory] What are Lambda layers and when should you use them?

A **layer** is a ZIP archive of libraries, custom runtimes, or shared data that you attach to a function; its contents are extracted into `/opt`. A function can use up to 5 layers, and they count toward the 250 MB unzipped limit.

Use layers to:
- Share common dependencies across many functions (avoid duplicating a fat JAR in every deployment).
- Ship a custom runtime or shared native binaries.
- Keep the function's own deployment package small for faster uploads.

Caveats: layers are largely a packaging convenience, not a versioned dependency manager. For Java specifically, layers are used less than in Node/Python because Java's deploy artifact is a single JAR; many teams prefer to just build a self-contained JAR or use container images. Overusing layers can make builds harder to reason about (which version of what is where).

### Q21. [Theory] Compare deploying Lambda as a ZIP vs a container image.

Lambda supports two packaging formats:

- **ZIP archive**: your code + deps, up to 250 MB unzipped, run on AWS-managed base runtimes. Faster to deploy small functions; simplest path.
- **Container image** (OCI): up to **10 GB**, built on AWS base images (or any image implementing the Lambda Runtime API), pushed to ECR. Lets you use familiar Docker tooling, bring large dependencies/ML models, and standardize on one packaging format across container and serverless workloads.

```
ZIP:   smaller, simpler, 250 MB cap, managed runtime
Image: up to 10 GB, full Docker control, ECR-hosted, good for big deps/ML
```

Cold-start performance is comparable today thanks to Lambda's image caching and layer-deduplication, so the choice is mostly about packaging preference and artifact size, not speed. Images shine when you already have a container build pipeline or need >250 MB of dependencies.

### Q22. [Theory] What does idempotency mean and why is it critical in serverless?

Idempotency means processing the **same event more than once produces the same result** as processing it once. It's critical because most serverless event sources guarantee **at-least-once** (not exactly-once) delivery: SQS, EventBridge, async invocation retries, and Kinesis can all deliver a message twice.

```
Without idempotency:  duplicate "charge $50" event → customer charged twice
With idempotency:     second "charge $50" with same key → no-op, returns prior result
```

The standard technique is an **idempotency key** (often a business ID or a hash of the payload) recorded in a store (e.g. a DynamoDB table with a conditional write and TTL). On reprocessing, you detect the key already exists and skip the side effect, returning the cached result.

### Q23. [Coding] Implement an idempotency guard for a Lambda handler in Java.

Use a conditional `PutItem` on DynamoDB: the write succeeds only if the key doesn't already exist. A `ConditionalCheckFailedException` means it's a duplicate.

```java
public class PaymentHandler implements RequestHandler<PaymentEvent, String> {

    private static final DynamoDbClient DDB = DynamoDbClient.create();
    private static final String TABLE = "idempotency";

    @Override
    public String handleRequest(PaymentEvent event, Context ctx) {
        String key = event.getIdempotencyKey();   // e.g. order id from caller

        try {
            // Atomic "claim" — succeeds only if this key has never been seen.
            DDB.putItem(b -> b.tableName(TABLE)
                    .item(Map.of(
                        "id",  AttributeValue.fromS(key),
                        "ttl", AttributeValue.fromN(String.valueOf(
                                Instant.now().plus(Duration.ofDays(1)).getEpochSecond()))))
                    .conditionExpression("attribute_not_exists(id)"));
        } catch (ConditionalCheckFailedException dup) {
            return "DUPLICATE_IGNORED";            // already processed → no-op
        }

        charge(event);                              // safe: runs at most once per key
        return "PROCESSED";
    }
}
```

For production, libraries like **AWS Lambda Powertools for Java (Idempotency module)** handle this declaratively (`@Idempotent`), including in-progress locking, response caching, and TTL — so you don't hand-roll the edge cases (e.g. crash *after* the claim but *before* completing the side effect).

### Q24. [Theory] Explain the fan-out / fan-in pattern in serverless.

**Fan-out** distributes one event to many parallel workers; **fan-in** aggregates their results.

```
              ┌──▶ Lambda ─┐
Event ─▶ SNS ─┼──▶ Lambda ─┼─▶ results ─▶ aggregator (DynamoDB / Step Functions)
              └──▶ Lambda ─┘
   (fan-out)                         (fan-in)
```

Common implementations:
- **SNS → multiple Lambdas/SQS queues**: one publish, many independent subscribers process in parallel.
- **S3 → SQS → Lambda with high concurrency**: thousands of objects processed concurrently.
- **Step Functions `Map` state**: native parallel iteration over a collection, with built-in result aggregation (this is the cleanest fan-in).

Fan-in is the harder half: you need somewhere to collect partial results and know when all branches are done. Step Functions `Map`/`Parallel` states handle this automatically; ad-hoc approaches use a DynamoDB counter or a "rendezvous" record updated atomically by each worker.

### Q25. [Theory] What is AWS Step Functions and when do you use it?

Step Functions is a managed **workflow orchestrator**: you define a state machine (in Amazon States Language / ASL JSON) that coordinates Lambda functions and 200+ AWS services into a multi-step workflow, with built-in retries, error handling, parallelism, and visual execution history.

Use it when:
- A process has **multiple steps with dependencies** (do A, then B, branch on result, then C).
- You need **long-running** orchestration beyond Lambda's 15-minute limit (Standard workflows run up to 1 year).
- You want **visibility** into where each execution is and automatic retry/catch logic without writing glue code.
- You need **human-in-the-loop** or wait states.

```
Start ─▶ Validate ─▶ Choice ─┬─[approved]─▶ Charge ─▶ Ship ─▶ End
                             └─[rejected]─▶ Notify ─▶ End
```

It replaces brittle "Lambda calls Lambda calls Lambda" chains, where you'd otherwise reimplement retries, timeouts, and state tracking yourself.

### Q26. [Theory] What's the difference between Standard and Express Step Functions workflows?

- **Standard**: durable, exactly-once execution semantics, runs up to **1 year**, full execution history retained, priced **per state transition**. Best for long-running, auditable, low-to-moderate-volume business workflows (order fulfillment, approval flows).
- **Express**: high-volume, short-lived (up to **5 minutes**), at-least-once semantics, priced by **number of executions + duration**. Best for high-throughput event processing and streaming ingestion where you'd otherwise overpay on state transitions.

```
Standard:  durable, exactly-once, ≤ 1 yr,  per-transition price   → business workflows
Express:   high-volume, at-least-once, ≤ 5 min, per-exec price    → event ingestion
```

A common pattern is to nest an Express workflow inside a Standard one for the high-frequency inner loop.

### Q27. [Practical] How do you implement retries and error handling in a Step Functions task?

ASL has declarative `Retry` and `Catch` fields on task states, so you express resilience as configuration rather than code.

```json
{
  "ChargeCard": {
    "Type": "Task",
    "Resource": "arn:aws:lambda:...:function:ChargeCard",
    "Retry": [
      {
        "ErrorEquals": ["TransientError", "Lambda.TooManyRequestsException"],
        "IntervalSeconds": 2,
        "BackoffRate": 2.0,
        "MaxAttempts": 3
      }
    ],
    "Catch": [
      {
        "ErrorEquals": ["States.ALL"],
        "Next": "RefundAndNotify",
        "ResultPath": "$.error"
      }
    ],
    "Next": "ShipOrder"
  }
}
```

`Retry` does exponential backoff on matching errors; `Catch` routes unrecoverable failures to a compensating state (here, a refund — a serverless take on the **Saga pattern**). Pushing retry policy into the state machine keeps individual Lambdas simple and idempotent.

### Q28. [Theory] How does error handling differ across SQS, async, and stream event sources?

Each integration style has distinct failure semantics — a frequent source of bugs:

- **SQS (standard)**: the poller processes a batch; a failed message returns to the queue after the visibility timeout and is retried up to `maxReceiveCount`, then goes to a DLQ. Use **partial batch response** (`ReportBatchItemFailures`) so one bad message doesn't reprocess the whole batch.
- **Async invocation** (SNS/EventBridge/S3): Lambda retries **twice** automatically; configure an **on-failure destination** or DLQ for the rest.
- **Streams (Kinesis/DynamoDB)**: processing is **per-shard and ordered**; a failing batch **blocks the shard** until it succeeds or expires ("poison pill"). Mitigate with `BisectBatchOnFunctionError`, `MaximumRetryAttempts`, and an on-failure destination.

Designing error handling without knowing which style applies is the classic mid-level mistake.

### Q29. [Coding] Implement SQS partial batch failure reporting in Java.

By default, if your handler throws, the *entire* SQS batch is retried — including already-processed messages. Partial batch response lets you report only the IDs that failed.

```java
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse.BatchItemFailure;

public class QueueHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context ctx) {
        List<BatchItemFailure> failures = new ArrayList<>();

        for (SQSEvent.SQSMessage msg : event.getRecords()) {
            try {
                process(msg.getBody());          // idempotent processing
            } catch (Exception e) {
                ctx.getLogger().log("Failed " + msg.getMessageId() + ": " + e);
                failures.add(new BatchItemFailure(msg.getMessageId()));
            }
        }
        // Only these messages return to the queue for retry.
        return new SQSBatchResponse(failures);
    }
}
```

You must also enable `ReportBatchItemFailures` on the event source mapping. Combined with idempotent processing, this gives clean at-least-once semantics without re-running successful work.

### Q30. [Practical] How do you do fan-out with a Step Functions Map state?

The `Map` state iterates over an array, running the inner steps for each element in parallel (with a configurable concurrency limit), and collects the results — giving you fan-out and fan-in for free.

```json
{
  "ProcessImages": {
    "Type": "Map",
    "ItemsPath": "$.images",
    "MaxConcurrency": 10,
    "ItemProcessor": {
      "StartAt": "Resize",
      "States": {
        "Resize": {
          "Type": "Task",
          "Resource": "arn:aws:lambda:...:function:ResizeImage",
          "End": true
        }
      }
    },
    "ResultPath": "$.resized",
    "Next": "Aggregate"
  }
}
```

`MaxConcurrency` caps parallelism (protecting downstream systems); the aggregated array of per-item results lands at `$.resized`. For very large datasets, **Distributed Map** mode scales to millions of items by reading directly from S3 and running thousands of parallel child executions.

### Q31. [Theory] What is the cost model of Lambda, and how does it compare to containers?

Lambda billing has three components: **number of requests**, **GB-seconds** (memory × duration, billed per ms), and ancillary costs (provisioned concurrency hours, data transfer). You pay **nothing** when idle.

```
Cost ≈ requests × $/req  +  GB-seconds × $/GB-s
```

Versus containers (Fargate/ECS/EKS) which bill for **allocated capacity over time**, regardless of utilization:

- **Low / spiky traffic** → Lambda usually wins (scale-to-zero, no idle cost).
- **High, steady traffic** → containers usually win; there's a crossover point (often cited around sustained high utilization) where always-on capacity beats per-invocation pricing.
- **Hidden Lambda costs**: provisioned concurrency, high-memory functions running long, NAT gateway data charges for VPC functions, and per-request costs at extreme scale.

The mature view: model your actual traffic shape. Lambda optimizes for **operational cost and elasticity**, not necessarily raw compute cost at scale.

### Q32. [Theory] How do you observe and trace serverless applications?

Serverless observability rests on three pillars plus distributed tracing:

- **Logs**: CloudWatch Logs captures `stdout`/logger output; use **structured (JSON) logging** so you can query with CloudWatch Logs Insights.
- **Metrics**: CloudWatch emits `Invocations`, `Errors`, `Throttles`, `Duration`, `ConcurrentExecutions`, and (critically) `IteratorAge` for streams. Use **Embedded Metric Format (EMF)** to emit custom metrics cheaply from logs.
- **Traces**: **AWS X-Ray** (or OpenTelemetry via the ADOT layer) stitches a request across API Gateway → Lambda → DynamoDB into one trace, exposing where latency and cold starts occur.

```
API GW ──▶ Lambda ──▶ DynamoDB
   └────── one X-Ray trace, with cold-start subsegment visible ──────┘
```

**Powertools for Java** standardizes structured logging, EMF metrics, and tracing with annotations, which is the recommended baseline.

### Q33. [Practical] Add X-Ray tracing and structured logging to a Java Lambda.

Enable active tracing on the function and instrument the SDK/handler. Powertools makes this declarative.

```java
import software.amazon.lambda.powertools.logging.Logging;
import software.amazon.lambda.powertools.tracing.Tracing;
import software.amazon.lambda.powertools.metrics.Metrics;

public class OrderHandler implements RequestHandler<Order, String> {

    @Logging(logEvent = true)      // structured JSON logs + correlation IDs
    @Tracing                       // creates an X-Ray subsegment for the handler
    @Metrics(namespace = "orders") // EMF metrics
    @Override
    public String handleRequest(Order order, Context ctx) {
        // SDK calls are auto-traced when the X-Ray SDK / ADOT is on the path
        saveOrder(order);
        return "ok";
    }
}
```

The function's execution role needs `xray:PutTraceSegments`/`PutTelemetryRecords`, and active tracing must be on in the function config. With this in place you can see cold-start time, downstream call latency, and error annotations per request in the X-Ray service map.

### Q34. [Theory] Why is putting Lambda in a VPC historically a cold-start concern, and what changed?

Originally, a VPC-attached Lambda had to create an **Elastic Network Interface (ENI)** per environment on cold start, which could add **seconds** of latency and exhaust IP space at scale. This made VPC Lambdas painful for latency-sensitive APIs.

AWS later introduced **Hyperplane ENIs**: ENIs are now created and shared at the function level, decoupled from individual cold starts. The result is that VPC networking adds **negligible** cold-start overhead today.

```
Old:  cold start → create ENI (seconds) → handler
New:  shared Hyperplane ENI provisioned at function update → cold start ~ unaffected
```

Remaining VPC considerations: you still need **NAT/VPC endpoints** for the function to reach AWS services or the internet (and NAT data charges add up), and you must size subnets for IPs. But the old "never put Lambda in a VPC" advice is outdated.

### Q35. [Theory] What is Lambda@Edge / CloudFront Functions, and when do you use each?

These run code at CloudFront edge locations, close to users, for request/response manipulation:

- **CloudFront Functions**: ultra-lightweight JavaScript, sub-millisecond, runs at the edge cache for **viewer request/response** only. Use for header manipulation, URL rewrites, redirects, simple auth checks, A/B routing — extremely high volume, very cheap.
- **Lambda@Edge**: full Lambda (Node.js/Python), more compute and network access, runs at **all four CloudFront events** (viewer/origin request/response). Use for heavier logic: origin selection, content generation, image transformation, request signing.

```
Viewer req ─▶ [CloudFront Function]  ─▶ cache ─▶ [Lambda@Edge: origin req] ─▶ Origin
   (cheap, fast, JS-only)                          (heavier, full Lambda)
```

Rule of thumb: reach for CloudFront Functions first for simple, high-frequency edge logic; use Lambda@Edge when you need real compute, larger payloads, or network calls at the edge.

## 🟠 Advanced (8–12 yrs)

### Q36. [Theory] Design a strategy to minimize Java cold starts end-to-end.

Attack each phase of the cold start:

1. **Reduce artifact / classpath size** — trim dependencies, prefer AWS SDK v2 with the `UrlConnectionHttpClient` (skip Netty), avoid heavyweight DI frameworks or use compile-time DI (Micronaut/Quarkus/Dagger) instead of reflective Spring.
2. **Adopt SnapStart** — snapshot the initialized JVM; restore in ~hundreds of ms. Use CRaC hooks to fix per-instance state.
3. **Consider GraalVM native image** (via Quarkus or the AWS custom runtime) — ahead-of-time compilation yields tens-of-ms cold starts with low memory, at the cost of build complexity and reflection config.
4. **Provisioned concurrency** for the small set of latency-critical functions where SnapStart isn't enough.
5. **Right-size memory** — more memory = more CPU = faster JVM warm-up and init.
6. **Lazy-load** rarely used code paths; do only essential work in INIT.

```
JVM cold-start spectrum (fastest → slowest):
GraalVM native  <  SnapStart  <  tuned JVM + high memory  <  naive Spring fat JAR
```

The pragmatic default in 2026 for most Java teams is **SnapStart + AWS SDK v2 + trimmed deps + right-sized memory**, reserving native image for the most latency-critical functions.

### Q37. [Theory] How do you protect downstream systems from Lambda's "infinite" scaling?

Lambda's auto-scaling is a double-edged sword: a traffic spike can fan out to thousands of concurrent executions and overwhelm a downstream RDS database, a third-party API, or a rate-limited service.

Defenses, layered:
- **Reserved concurrency** as a hard ceiling on the function hitting the fragile dependency.
- **SQS as a buffer** in front of Lambda (queue absorbs the spike; Lambda drains at a controlled rate via the event source mapping's `maximumConcurrency`).
- **RDS Proxy** to pool and multiplex database connections, since each Lambda env otherwise opens its own connection and exhausts the DB's connection limit.
- **Circuit breakers / bulkheads** in code for third-party calls.
- **Token-bucket rate limiting** against rate-limited APIs, coordinated via a shared store.

```
Spike ─▶ [SQS buffer] ─▶ Lambda (reserved conc. cap) ─▶ [RDS Proxy] ─▶ RDS
```

The mental model: serverless makes *your* compute elastic, but your **dependencies are not** — you must throttle deliberately.

### Q38. [Theory] How do you handle database connections in serverless at scale?

The fundamental tension: relational databases have a bounded connection pool, but Lambda creates an environment (and thus a connection) per concurrent execution. At 2,000 concurrency you can blow past Postgres's connection limit instantly.

Strategies:
- **RDS Proxy** — sits between Lambda and the DB, maintaining a warm pool and multiplexing many client connections onto fewer DB connections. The standard answer.
- **One connection per environment, reused** — open in INIT (static field), reuse across warm invocations; never open per invocation.
- **Prefer DynamoDB** for serverless-native workloads — it's HTTP-based with no persistent connections and scales with the function.
- **Aurora Serverless v2 / Data API** — HTTP-based query interface avoids connection management entirely (at some latency cost).

```
Naive:   2000 envs × 1 conn = 2000 DB connections → DB melts
RDS Proxy: 2000 client conns → ~50 pooled DB connections → DB happy
```

### Q39. [Coding] Implement a circuit breaker around a downstream call in a Lambda handler.

Because warm environments persist, a simple per-environment circuit breaker can shed load to a failing dependency. State is per-environment (acceptable — each independently learns the dependency is down).

```java
public class ResilientHandler implements RequestHandler<Req, Res> {

    // Per-environment breaker; survives warm invocations.
    private static final AtomicInteger failures = new AtomicInteger();
    private static volatile long openUntil = 0;
    private static final int THRESHOLD = 5;
    private static final long COOLDOWN_MS = 10_000;

    @Override
    public Res handleRequest(Req req, Context ctx) {
        if (System.currentTimeMillis() < openUntil) {
            return Res.fallback();                 // breaker OPEN → fail fast
        }
        try {
            Res r = callDownstream(req);           // protected call
            failures.set(0);                       // success → reset
            return r;
        } catch (Exception e) {
            if (failures.incrementAndGet() >= THRESHOLD) {
                openUntil = System.currentTimeMillis() + COOLDOWN_MS;  // trip
            }
            return Res.fallback();
        }
    }
}
```

This bounds the damage when a dependency degrades. For cross-environment coordination (global breaker state), you'd externalize the counter to DynamoDB/ElastiCache — but per-environment is often enough and avoids adding a dependency on the very infra you're protecting. In practice, prefer a library (Resilience4j) over hand-rolled state.

### Q40. [Theory] How would you architect exactly-once processing despite at-least-once delivery?

True exactly-once **delivery** is generally impossible in distributed systems; the achievable goal is **exactly-once effect** = at-least-once delivery + idempotent processing.

The pattern:
1. **At-least-once delivery** from the source (SQS, Kinesis) — accept that duplicates will arrive.
2. **Idempotency key** per message (business ID or content hash).
3. **Atomic dedup + side-effect** — record the key and perform the effect in a way that's safe to repeat. Two robust options:
   - **Conditional write** to DynamoDB keyed on the idempotency key (claim-then-act, with a "completed" flag to handle mid-processing crashes).
   - **Transactional outbox** — write the business change and the "processed" marker in a single DynamoDB transaction so they can't diverge.

```
Source (at-least-once) ─▶ Lambda ─┬─ check idempotency store
                                   ├─ if seen + completed → return cached result
                                   └─ else: do effect + mark completed (atomically)
```

Edge case to nail in interviews: the crash *between* doing the side effect and marking it complete. Solve it with an "in-progress" lock + timeout, or by making the side effect itself idempotent (e.g. conditional writes downstream too).

### Q41. [Theory] What are the trade-offs of orchestration (Step Functions) vs choreography (events)?

- **Orchestration** (Step Functions): a central state machine explicitly drives each step. Pros: visible end-to-end flow, centralized error handling/retries/compensation, easy to reason about and debug. Cons: the orchestrator is a coupling point; can become a bottleneck; per-transition cost at high volume.
- **Choreography** (EventBridge/SNS/SQS): services react to events independently with no central coordinator. Pros: loose coupling, independent scaling/deployment, no single bottleneck. Cons: emergent behaviour is hard to trace ("where did this flow go?"), distributed error handling, harder to reason about overall correctness.

```
Orchestration:   [Step Functions] → A → B → C   (central brain, easy to trace)
Choreography:    A ─event→ B ─event→ C           (no brain, loosely coupled)
```

Pragmatic guidance: use **orchestration for a bounded business process** (order fulfillment) where you need visibility and compensation; use **choreography across bounded contexts** where teams own services independently. Real systems mix both — orchestrate within a domain, choreograph across domains.

### Q42. [Behavioral] Tell me about a time you had to decide between serverless and a traditional architecture. How did you reason about it?

A strong answer shows **structured decision-making**, not dogma. Frame it as:

- **Context**: the workload's traffic shape (spiky vs steady), latency SLAs, team size/ops maturity, existing tooling, and cost constraints.
- **Analysis**: you modeled cost at expected and peak load, evaluated cold-start risk against SLAs, considered the team's operational capacity, and weighed vendor lock-in.
- **Decision and trade-off**: e.g. *"We chose Lambda for the event-driven ingestion pipeline because traffic was bursty and scale-to-zero saved ~70% over an always-on fleet; but we kept the steady high-QPS read API on Fargate because modeling showed Lambda would be ~3x the cost at that sustained load and we had a strict p99 latency SLA."*
- **Reflection**: what you'd revisit (e.g. you later moved a service to SnapStart once it matured, or added provisioned concurrency after measuring real cold-start impact).

The interviewer is checking that you **quantify trade-offs and avoid resume-driven architecture** — choosing the tool that fits the workload rather than the trendiest one.

### Q43. [Theory] How do you address vendor lock-in in serverless architectures?

Serverless is inherently more coupled to a provider (proprietary event formats, IAM, Step Functions ASL, DynamoDB). Mitigations, in order of pragmatism:

1. **Hexagonal architecture** — keep business logic in plain, framework-free classes; confine provider-specific code (handlers, SDK calls) to thin adapter layers. You can re-host the core on another platform by rewriting adapters.
2. **Abstract the trigger and persistence** behind your own interfaces (a `MessageHandler` your Lambda handler delegates to).
3. **Use open standards where they exist** — OpenTelemetry over X-Ray-only, CloudEvents for event schemas, container images (portable to Cloud Run/Knative).
4. **Accept *strategic* lock-in deliberately** — the managed value (Step Functions, DynamoDB) is often worth it; the goal is to make lock-in a conscious cost/benefit decision, and to keep the **portable core** portable.

```
[ thin adapter: Lambda handler ] → [ portable core: domain logic ] → [ adapter: persistence ]
        replaceable                      provider-agnostic                  replaceable
```

The honest stance: you rarely fully escape lock-in, but you can localize it so a migration is a bounded adapter rewrite, not a rewrite of the whole system.

### Q44. [Theory] How do you design a serverless system to be testable?

Serverless testability hinges on separating logic from the platform:

- **Unit test the core**: keep business logic in plain classes (no `Context`, no SDK in the hot path) so it's testable in isolation, then have the thin handler delegate to it.
- **Mock the boundaries**: inject SDK clients so you can substitute mocks/`@Mock`s in tests.
- **Local emulation**: AWS SAM Local / LocalStack to run functions and emulate API Gateway, SQS, DynamoDB locally for integration tests.
- **Contract/event-shape tests**: validate against real event JSON samples (the `aws-lambda-java-tests` library loads sample events) so a malformed event source mapping is caught early.
- **Ephemeral cloud environments**: deploy a full stack per pull request (CDK/SAM) and run end-to-end tests against real services — the most faithful, since emulators diverge from real IAM/throttling/cold-start behaviour.

```
fast/cheap  →  unit (core logic, mocked SDK)
            →  local integration (SAM/LocalStack)
            →  ephemeral cloud E2E (real services)  ← slowest/most faithful
```

The principle: don't fight to make handlers testable — make them **thin**, and push everything worth testing into provider-agnostic code.

### Q45. [Coding] Write a Java structure that cleanly separates the handler from testable business logic.

The handler becomes an adapter; the logic is a plain, injectable service.

```java
// Provider-agnostic core — unit-testable with no AWS dependencies.
public class OrderService {
    private final OrderRepository repo;          // interface, mockable
    public OrderService(OrderRepository repo) { this.repo = repo; }

    public OrderResult place(OrderCommand cmd) {
        if (cmd.quantity() <= 0) throw new IllegalArgumentException("qty");
        repo.save(cmd.toOrder());
        return new OrderResult(cmd.id(), "PLACED");
    }
}

// Thin Lambda adapter — wires AWS-specific bits, delegates immediately.
public class OrderHandler implements RequestHandler<APIGatewayProxyRequestEvent,
                                                    APIGatewayProxyResponseEvent> {
    // Built once in INIT; the only AWS coupling lives here.
    private static final OrderService SERVICE =
            new OrderService(new DynamoOrderRepository(DynamoDbClient.create()));

    @Override
    public APIGatewayProxyResponseEvent handleRequest(
            APIGatewayProxyRequestEvent req, Context ctx) {
        OrderCommand cmd = parse(req.getBody());
        OrderResult result = SERVICE.place(cmd);          // pure delegation
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(201)
                .withBody(toJson(result));
    }
}
```

Now `OrderService` is tested with a mock `OrderRepository` and zero AWS — fast and deterministic — while the handler needs only a thin integration test. This is the hexagonal/ports-and-adapters approach applied to FaaS, and it simultaneously solves testability and limits vendor lock-in.

## 🔴 Expert (15+ yrs)

### Q46. [Theory] Design a serverless platform standard for an organization with 50 teams. What governance and patterns do you mandate?

At organizational scale the problem shifts from "can we build a function" to "can 50 teams build thousands of functions safely and consistently." A platform standard should cover:

- **Golden paths / paved road**: a blessed CDK/SAM construct library (or an internal developer platform) that bakes in logging (Powertools), tracing (OTel), idempotency, DLQs, and tagging — so teams get correctness by default, not by discipline.
- **Account & boundary strategy**: per-team or per-domain AWS accounts via Control Tower/Organizations, with SCPs enforcing guardrails (no public functions without approval, mandatory encryption, region restrictions).
- **Concurrency governance**: account-level concurrency is a shared, finite resource (1,000 default). Mandate reserved concurrency for critical functions and monitor the regional pool so one team can't starve others — this is a real production failure mode.
- **Security baseline**: least-privilege IAM templated, no wildcard policies, secrets via Secrets Manager, automated scanning (cfn-nag/Checkov) in CI.
- **Cost visibility & FinOps**: per-team tagging, anomaly detection, and chargeback so cost is owned by teams.
- **Observability standard**: structured logs, EMF metrics, distributed tracing with a common correlation-ID convention across teams.

The meta-point for an interviewer: at scale you're designing **constraints and defaults**, not individual functions — the platform team's product is the golden path.

### Q47. [Theory] When is serverless the wrong abstraction, and what would you choose instead? Argue both sides.

A senior answer resists serverless maximalism and reasons from workload characteristics.

**Serverless is the wrong fit when:**
- **Sustained high throughput** with steady load — per-invocation billing and per-request overhead lose to a well-utilized container fleet; the crossover is real and quantifiable.
- **Strict, consistent low latency** at p99/p99.9 where even mitigated cold starts and provisioned-concurrency cost/complexity make a warm fleet simpler.
- **Heavy stateful or long-lived workloads** — stateful stream processing, large in-memory caches, anything exceeding 15 minutes, or needing GPUs.
- **High data egress / chatty inter-service traffic** where per-request and data-transfer costs compound.

**Counter-arguments / nuance:**
- "It's cheaper at scale on containers" ignores the **fully-loaded cost** of running, patching, scaling, and on-calling that fleet — serverless's operational savings can dominate the compute delta for a small team.
- SnapStart, Lambda SnapStart pricing, provisioned concurrency, and 10-GB memory have eroded many classic objections.

The mature conclusion: **choose per workload, not per organization**. Most real estates are **hybrid** — serverless for event-driven, spiky, and glue workloads; containers for steady high-QPS and stateful services; and the architecture's value is in drawing those boundaries deliberately.

### Q48. [Behavioral] Describe leading a migration to (or from) serverless. What went wrong and what did you learn?

This probes judgment, leadership, and intellectual honesty. A strong narrative includes:

- **Why**: the concrete driver — runaway ops cost on an always-on fleet, or conversely runaway Lambda cost at scale that forced a partial move *to* containers. Tie it to business outcomes, not novelty.
- **How you de-risked**: incremental migration (strangler-fig: route a slice of traffic, one bounded context at a time), measuring cost and latency at each step rather than a big-bang rewrite.
- **What went wrong (be specific and honest)**: e.g. you under-budgeted Java cold starts and breached an SLA until SnapStart/provisioned concurrency was added; or you exhausted RDS connections under load and had to introduce RDS Proxy; or a team consumed the shared regional concurrency pool and throttled others.
- **What you learned**: the systemic fix (golden-path constructs, concurrency governance, load-testing the *dependencies* not just the functions) and the cultural change (teams reasoning about cost and idempotency by default).
- **Leadership signal**: how you brought engineers along — training, paved roads, and making the right thing the easy thing — rather than mandating from on high.

The interviewer wants evidence you can run a **data-driven, incremental** architectural change, own the failures, and convert them into durable organizational improvements.

## 🧩 Extended Questions — Set 1: Deeper theory & internals

This set drills below the API surface into how the Lambda platform actually works: the micro-VM substrate, the freeze/thaw lifecycle, how SnapStart snapshots are built and restored, the Runtime API protocol, event-source-mapping poller internals, billing arithmetic, and the consistency/ordering guarantees of the surrounding services. The aim is to be able to reason about *why* a behaviour happens, not just *that* it happens.

### 🟢 — extended

#### Q49. [Theory] What is Firecracker, and why does Lambda use micro-VMs instead of containers for isolation?

Firecracker is an open-source **virtual machine monitor (VMM)** written in Rust that AWS built specifically for serverless. Each Lambda execution environment runs inside a Firecracker **micro-VM** — a minimal KVM-based guest with a stripped-down device model (just virtio-net, virtio-block, a serial console, and a one-button keyboard controller).

The reason Lambda uses micro-VMs rather than plain containers is **isolation strength under multi-tenancy**. Lambda packs functions from many different AWS customers onto the same physical hosts. Containers share the host kernel, so a kernel exploit is a cross-tenant escape. A micro-VM gives each function its own guest kernel behind the hardware virtualization boundary (KVM), which is a far stronger security frontier — yet Firecracker boots in ~125 ms and adds only a few MB of memory overhead, so you get VM-grade isolation at roughly container-grade density and startup cost. That combination (hardware isolation + fast boot + high density) is precisely what a multi-tenant FaaS needs.

```
Container model:  func A ┐
                  func B ┼─ shared host kernel  ← weaker tenant boundary
                  func C ┘
Firecracker:      func A → guest kernel ┐
                  func B → guest kernel ┼─ KVM hardware boundary  ← strong boundary
                  func C → guest kernel ┘
```

Note the micro-VM is the *isolation* unit; your function still runs as a process inside it, and Lambda's own components (the runtime, extensions) run alongside.

#### Q50. [Theory] What exactly happens during the "freeze" and "thaw" of an execution environment?

Between invocations, Lambda does not destroy a warm environment immediately — it **freezes** it. Freezing suspends the micro-VM: the processes inside stop being scheduled, the CPU is taken away, and the environment consumes no compute (you are not billed during the freeze). When the next event arrives, Lambda **thaws** the environment — restores CPU scheduling — and your handler resumes almost instantly.

The practical consequences trip people up:
- **Background threads stop.** Any thread you spawned, async task, or timer is frozen mid-flight the instant the handler returns. It does not keep running between invocations. Work you "fire and forget" after returning the response may never complete — it only resumes (from where it was frozen) if and when the same environment is thawed for the next event.
- **Wall-clock time jumps.** Because the freeze can last seconds or minutes, `System.currentTimeMillis()` measured across a freeze boundary shows a large gap with no CPU consumed. Cached "now" values or TTLs computed before a freeze can be stale on thaw.
- **In-flight network connections may have been dropped** by the remote side during the freeze, so a pooled connection that was healthy before the freeze can fail on first use after thaw.

```
INVOKE 1 → [FREEZE: no CPU, no billing, threads suspended] → THAW → INVOKE 2
                        (could be ms or minutes)
```

This is why Lambda is "single event at a time per environment" and why you must finish all work *before* the handler returns.

#### Q51. [Theory] How does the Lambda Runtime API work under the hood?

Every Lambda runtime — managed or custom — is just a loop that talks HTTP to a local endpoint Lambda exposes inside the environment at `$AWS_LAMBDA_RUNTIME_API`. The protocol has four endpoints:

1. **`GET /runtime/invocation/next`** — a long-poll the runtime calls to ask "give me the next event." It blocks until an event is ready, then returns the event body plus headers (request ID, deadline, invoked function ARN, X-Ray trace ID).
2. **`POST /runtime/invocation/{requestId}/response`** — the runtime posts your handler's successful result here.
3. **`POST /runtime/invocation/{requestId}/error`** — posts a handler error.
4. **`POST /runtime/init/error`** — posts a failure that happened during initialization.

```
runtime loop:
  while true:
    event = GET  /runtime/invocation/next        # blocks (this is where "warm" waits)
    try:    result = handler(event)
            POST /runtime/invocation/{id}/response  result
    except: POST /runtime/invocation/{id}/error     err
```

The crucial insight: a **warm environment is one parked inside the `next` long-poll**. The freeze happens while it's blocked there. This is also exactly the contract a **custom runtime** (`provided.al2023`) implements — that's all "bring your own runtime" means: write this loop. Knowing the protocol demystifies cold starts, extensions (which register against a parallel `/extension` API), and why the handler must return before the next event can be fetched.

#### Q52. [Theory] What is the difference between the platform "INIT" phase and your handler "INVOKE" phase in billing and timeout terms?

They are billed and bounded differently, and the rules changed in a way worth knowing:

- **INIT (initialization)** runs your code outside the handler (static blocks, constructors, SDK client creation). Historically, *standard* INIT was **not billed** and ran under a separate ~10-second init budget. As of a 2024 pricing change, AWS began **billing INIT duration for functions using managed runtimes on the ZIP package type** (it was already billed for provisioned concurrency and SnapStart). So today you should assume INIT time costs money.
- **INVOKE** is your handler execution, billed per millisecond at the function's memory tier, bounded by the function's configured timeout (up to 15 minutes).

```
[ INIT: bootstrap + your init code ]  [ INVOKE: handler ]  → SHUTDOWN
   billed (modern runtimes),             billed per ms,
   separate init budget                  up to 15 min timeout
```

The takeaway for design: heavy initialization is no longer "free latency you don't pay for" — it inflates both cold-start latency *and* cost, which strengthens the case for trimming dependencies and using SnapStart.

#### Q53. [Practical] How can you detect, in code, whether a given invocation ran on a cold start?

There is no official API flag, so the idiomatic trick is a static boolean that is `true` only for the first invocation in a fresh environment. Because static state survives warm reuse but is reinitialized on every cold start, the first handler call after INIT sees it unset.

```java
public class ColdStartAwareHandler implements RequestHandler<Req, Res> {

    // Initialized once per execution environment (i.e. per cold start).
    private static boolean COLD = true;

    @Override
    public Res handleRequest(Req req, Context ctx) {
        boolean wasCold = COLD;
        if (COLD) {
            COLD = false;                       // subsequent warm calls see false
        }
        // Emit a metric so you can measure cold-start rate in CloudWatch.
        ctx.getLogger().log("{\"coldStart\":" + wasCold + "}");
        return process(req);
    }
}
```

This lets you (a) emit a `coldStart` dimension on your metrics to measure how often cold starts actually happen, and (b) do one-time-per-environment warm-up lazily on the first request if you didn't do it in INIT. Powertools for Java exposes the same signal via a `ColdStart` metric dimension so you don't hand-roll it in production. Note: under SnapStart the "first invocation after restore" is conceptually a cold start, but the heavy work already happened at snapshot time.

#### Q54. [Theory] Why is there exactly one concurrent invocation per execution environment, and what does that imply for thread-safety?

Lambda's concurrency model deliberately serializes events per environment: an environment fetches one event from `/runtime/invocation/next`, runs the handler to completion, and only *then* fetches the next. It never delivers two events to the same environment simultaneously. Parallelism comes from spinning up *more environments*, not from concurrent handler calls in one.

The implication is liberating: **within a single invocation you don't need to worry about another invocation mutating your instance/static fields concurrently** — there's no second handler thread in the same environment. A non-thread-safe scratch object reused across invocations is safe *with respect to other invocations*.

But two subtleties remain:
1. If *you* spawn threads inside your handler, those are concurrent with each other (and the freeze will suspend them, per Q50) — your own concurrency is your problem.
2. Static mutable state is shared **across sequential invocations** on the same warm environment. So a static cache is fine, but a static field you forget to reset can leak data from one request into the next (e.g. a `ThreadLocal` or a request-scoped object stored statically). The classic bug is request data bleeding between users because it was parked in a reused field.

```
Env A:  evt1 → handler → evt2 → handler   (sequential, never overlapping)
Env B:  evt3 → handler                    (parallel env, separate memory)
```

#### Q55. [Practical] What is `/tmp` really, how much can you use, and what is its lifecycle?

`/tmp` is a writable scratch directory backed by the execution environment's ephemeral storage. Key facts:
- **Sizable and configurable**: 512 MB by default, configurable up to **10,240 MB (10 GB)** — you pay for storage above the free 512 MB tier.
- **Per-environment, not per-invocation**: like memory, `/tmp` persists across warm invocations on the same environment and is wiped when the environment is destroyed. So a file written by one invocation can still be there on the next warm call in the same environment.
- **Not shared across environments**: two concurrent invocations on different environments see different `/tmp`s, so you cannot use it for cross-invocation coordination.

```java
public class DownloadCacheHandler implements RequestHandler<Req, Res> {
    @Override
    public Res handleRequest(Req req, Context ctx) {
        Path cached = Path.of("/tmp", req.modelId() + ".bin");
        if (!Files.exists(cached)) {            // warm reuse: skip re-download
            downloadFromS3(req.modelId(), cached);
        }
        return runModel(cached, req);
    }
}
```

Common legitimate uses: caching a large read-only asset (an ML model, a reference dataset) once per environment, or staging a file too big to hold in memory. Pitfalls: treating it as durable storage (it isn't), and forgetting it counts against environment lifetime — a leftover huge file in a long-lived warm environment just sits there consuming the ephemeral storage you're paying for. Always size it deliberately and clean up if you write a lot.

#### Q56. [Theory] What does "at-least-once" actually guarantee, and how is it different from "exactly-once" and "at-most-once"?

These three delivery guarantees describe what a messaging/eventing system promises about duplicates and loss:

- **At-most-once**: a message is delivered zero or one times — never duplicated, but **may be lost**. (Fire-and-forget with no retries.)
- **At-least-once**: a message is delivered one or more times — **never lost, but may be duplicated**. This is what SQS, EventBridge, Lambda async retries, and Kinesis effectively provide.
- **Exactly-once**: delivered precisely once — no loss, no duplicates. Genuinely hard/impossible to guarantee end-to-end across independent failure domains, because the acknowledgement of "I processed it" can itself be lost, forcing a redelivery.

```
at-most-once :  0 or 1   (lossy, no dupes)
at-least-once:  1 or more (no loss, dupes possible)   ← serverless default
exactly-once :  exactly 1 (the hard one)
```

Why it matters in serverless: nearly every event source is at-least-once, so **duplicates are normal, not exceptional**. The engineering response isn't to chase impossible exactly-once delivery but to make processing **idempotent**, converting at-least-once delivery into exactly-once *effect* (Q40). Interviewers probe this to see whether you accept duplicates as a design input rather than treating them as a bug to be eliminated upstream.

#### Q57. [Practical] Why should you reuse the AWS SDK client across invocations, and what specifically gets cached?

Creating an SDK client is expensive because the constructor does a lot of one-time work that you want to pay for once per environment, not once per request:

- **Credential resolution** — walking the default provider chain and fetching/caching temporary credentials from the environment (the role's STS credentials surfaced via the runtime).
- **Region and endpoint resolution.**
- **HTTP client setup** — building the connection pool, TLS context, and (for Netty-based clients) the event-loop threads.
- **Marshaller/metadata loading** — service model metadata for the SDK.

```java
public class ReuseHandler implements RequestHandler<Req, Res> {
    // Constructed ONCE in INIT: credentials, endpoint, connection pool all set up here.
    private static final DynamoDbClient DDB = DynamoDbClient.builder()
            .httpClientBuilder(UrlConnectionHttpClient.builder())  // no Netty threads
            .build();

    @Override
    public Res handleRequest(Req req, Context ctx) {
        return new Res(DDB.getItem(/* ... */));   // reuses warm connection pool + creds
    }
}
```

Reusing the static client means warm invocations skip all of that and reuse an already-open, already-authenticated connection pool — often the single biggest warm-path latency win. The corollary is the **`UrlConnectionHttpClient`** recommendation for Lambda: the Netty client spins up event-loop threads that add INIT cost and complicate SnapStart, whereas the URLConnection-based client is lighter and a better fit for the single-threaded-per-invocation model.

#### Q58. [Theory] What is an "event source mapping" and how is it fundamentally different from a direct trigger?

An **event source mapping (ESM)** is a Lambda-managed **poller** that Lambda runs *on your behalf* for poll-based sources (SQS, Kinesis, DynamoDB Streams, Kafka/MSK, Amazon MQ, DocumentDB). It is a distinct resource from the function: it has its own configuration (batch size, batching window, concurrency, starting position, filter criteria, failure destinations).

The fundamental difference from a "direct" trigger:
- With **push sources** (API Gateway, S3, SNS), the *source* calls Lambda's `Invoke` API — Lambda is passive and just receives the call.
- With an **ESM**, *Lambda* runs a fleet of pollers that read from the source, assemble batches, and then synchronously invoke your function with each batch. You never see the poller; it's managed infrastructure.

```
Push  :   S3 / API GW / SNS  ──Invoke──▶ Lambda function
Poll  :   SQS / Kinesis / Stream  ◀──poll── [ Lambda-managed ESM poller ] ──Invoke──▶ function
```

This explains a lot of behaviour: batch size and batching window (you get *batches*, not single records), why SQS scaling ramps gradually (the ESM adds pollers over time), why ordering and retries differ per source (they're properties of the poller, not your code), and where **event filtering** happens (in the ESM, before invocation, so you don't pay to invoke for messages you'd discard).

### 🟡 — extended

#### Q59. [Theory] What is the SHUTDOWN phase, when does it fire, and what (if anything) can your function do during it?

The SHUTDOWN phase is the third lifecycle stage (after INIT and INVOKE): Lambda decides to **destroy** a warm execution environment — typically because it has been idle long enough to evict, or it's reclaiming capacity, or the function was updated. Crucially, **plain function handler code gets no shutdown hook** — your handler isn't called for shutdown, and a JVM shutdown hook is not a reliable place to flush work.

Where shutdown *is* observable is the **Extensions API**: registered external (and internal) extensions receive an explicit `SHUTDOWN` event with a short grace period (default ~500 ms, up to 2 seconds) so an observability or secrets-cache extension can flush buffered telemetry before the environment disappears.

```
INIT ──▶ INVOKE (×N, with freezes between) ──▶ SHUTDOWN
                                                 │ extensions get SHUTDOWN event (~500ms–2s)
                                                 │ runtime gets SIGTERM
                                                 └ environment destroyed
```

Design consequences:
- **Do not rely on shutdown to flush application state.** Any buffering you do (batched metrics, log aggregation) must either flush *within* each invocation or be handled by an extension that subscribes to SHUTDOWN — your handler can't count on a "drain on exit" moment.
- **`/tmp`, memory, and connections are gone after shutdown** — there's no "graceful close" you control for application sockets; design so an abruptly destroyed environment loses nothing important (which is just restating statelessness).
- **The freeze (Q50), not shutdown, is the common between-invocation state.** Most environments live for many invocations and are frozen/thawed repeatedly; shutdown is the terminal event, and you can't predict exactly when it comes. This is why "finish all work before the handler returns" is the only safe rule.

#### Q60. [Theory] How does the SnapStart snapshot/restore mechanism work internally, and what is captured?

SnapStart builds on **CRaC (Coordinated Restore at Checkpoint)** and the Firecracker snapshot capability. The flow is:

1. **At publish time** (when you publish a version), Lambda runs your full INIT — JVM boot, class loading, static initializers, your init code — once, in a build environment.
2. It then takes a **Firecracker micro-VM snapshot** of that fully initialized memory and disk state: the entire guest memory image with the warmed JVM, loaded classes, and your initialized objects frozen in place.
3. The snapshot is **encrypted and cached** (tiered caching, with chunks fetched lazily on restore).
4. **On a cold start**, instead of re-running INIT, Lambda **restores the micro-VM from the snapshot** and resumes execution right after the checkpoint. JVM boot and class loading are already done in the restored image.

```
publish:  INIT once → [Firecracker snapshot of warmed JVM] → encrypt + cache
cold start: restore snapshot (memory image) → afterRestore hooks → handler
            (no JVM boot, no class load, no static init re-run)
```

What's captured is literally a memory image, which is why anything in memory at checkpoint time is *frozen and cloned* into every restored environment — open sockets, file descriptors, seeded RNG state, cached timestamps, ephemeral credentials. That's the root cause of the SnapStart uniqueness and staleness problems and why CRaC `beforeCheckpoint`/`afterRestore` hooks exist (Q19).

#### Q61. [Theory] What are the three classes of bugs SnapStart introduces, and how do you reason about each?

Because SnapStart clones one memory image into many environments, three failure modes appear:

1. **Uniqueness violations** — values that must differ per instance are now identical everywhere. The canonical case is a `SecureRandom`/RNG seeded at INIT: every restored environment produces the *same* "random" sequence. Fix: re-seed in `afterRestore`. (Modern JDK SnapStart integration re-seeds the default `SecureRandom` for you, but app-level RNGs are still your responsibility.)
2. **Staleness** — values captured at checkpoint that drift over time: cached "current time," soon-to-expire credentials, cached config, or a token with a TTL. Because the snapshot may be restored hours or days after it was taken, these can be expired on first use. Fix: refresh in `afterRestore`, or fetch lazily on first invocation rather than at INIT.
3. **Broken external state** — network connections, DB connections, and file handles captured open in the snapshot are dead on restore (the remote peer closed them long ago, or they point at a now-invalid socket). Fix: close in `beforeCheckpoint`, re-establish in `afterRestore` (or lazily).

```
uniqueness  → re-generate per instance (RNG, instance IDs)        in afterRestore
staleness   → refresh time-sensitive values (creds, TTLs, "now")  in afterRestore
ext. state  → tear down + rebuild (sockets, DB conns, FDs)        beforeCheckpoint / afterRestore
```

The unifying mental model: treat the snapshot as a **fork** that happens long before and far away from where the code actually runs. Anything tied to "this moment" or "this instance" must be (re)derived after restore, not at INIT.

#### Q62. [Practical] How do you correctly prime a connection pool with SnapStart so the first real request is fast but connections aren't stale?

You want the *expensive setup* (class loading, pool object graph, DNS) captured in the snapshot for speed, but the *live sockets* re-established after restore so they aren't dead. The pattern: build the pool at INIT (captured), but close/invalidate sockets in `beforeCheckpoint` and warm fresh ones in `afterRestore`.

```java
public class PooledHandler implements RequestHandler<Req, Res>, org.crac.Resource {

    private static HikariDataSource pool;   // object graph captured in snapshot

    public PooledHandler() {
        if (pool == null) {
            pool = buildPool();             // INIT: expensive setup, captured in snapshot
        }
        org.crac.Core.getGlobalContext().register(this);
    }

    @Override
    public void beforeCheckpoint(org.crac.Context<? extends org.crac.Resource> c) {
        pool.getHikariPoolMXBean().softEvictConnections();  // drop live sockets pre-snapshot
    }

    @Override
    public void afterRestore(org.crac.Context<? extends org.crac.Resource> c) {
        try (Connection warmUp = pool.getConnection()) {    // re-open fresh sockets on restore
            warmUp.isValid(2);
        } catch (SQLException ignored) { /* will retry on first real request */ }
    }

    @Override
    public Res handleRequest(Req req, Context ctx) {
        try (Connection conn = pool.getConnection()) {
            return query(conn, req);
        } catch (SQLException e) { throw new RuntimeException(e); }
    }
}
```

This captures the costly class-loading/pool-construction in the snapshot while ensuring the restored environment opens its own live connections. The same shape applies to HTTP clients, cache clients, and any long-lived socket. (In practice, validating connections on borrow — e.g. Hikari's `connectionTestQuery` — is a belt-and-suspenders backstop against any stale socket that slips through.)

#### Q63. [Theory] Walk through the exact billing arithmetic for a Lambda invocation. How do you compute the cost?

Lambda cost has two compute components plus request count:

1. **Request charge**: a flat price per million requests (~$0.20 per million in the standard tier).
2. **Compute charge in GB-seconds**: `(memory in GB) × (billed duration in seconds)`, multiplied by the per-GB-second rate (~$0.0000166667/GB-s for x86 standard). Duration is billed **per millisecond**.

Worked example — 512 MB function, 200 ms average, 10 million invocations/month:
```
memory  = 512 MB = 0.5 GB
duration = 0.200 s
GB-s per invocation = 0.5 × 0.200 = 0.1 GB-s
total GB-s = 0.1 × 10,000,000 = 1,000,000 GB-s
compute cost = 1,000,000 × $0.0000166667 ≈ $16.67
request cost = 10,000,000 / 1,000,000 × $0.20 = $2.00
TOTAL ≈ $18.67 / month
```

Key levers this exposes: cost scales **linearly with both memory and duration**, so the cheapest config minimizes their *product* — which is why raising memory can lower cost if it shortens duration more than proportionally (a CPU-bound function going from 512 MB/400 ms to 1024 MB/180 ms: 0.5×0.4=0.2 vs 1.0×0.18=0.18 GB-s, cheaper *and* faster). Extras layer on top: provisioned-concurrency hours, ARM/Graviton (~20% cheaper per GB-s), and INIT billing on modern runtimes.

#### Q64. [Theory] How does Lambda scale concurrency over time, and what are burst vs sustained limits?

Lambda scaling is not instantaneous to infinity — it has a defined ramp:

- **Burst concurrency**: on a sudden spike, Lambda can immediately add a burst of environments. Since a 2023 improvement, scaling is **per-function**: each function can scale by up to **1,000 concurrent executions every 10 seconds** (previously there was a shared account-wide burst pool of 500–3,000 by region). 
- **Sustained**: after the burst, it keeps adding capacity at that 1,000-per-10-seconds cadence until it reaches your **account concurrency limit** (default 1,000, raisable) or the function's reserved concurrency cap.
- **Throttling**: requests beyond available concurrency are throttled (429 `TooManyRequestsException` for sync; retried/queued for async and poll sources).

```
concurrency
   ^
   |        ┌─────── sustained (limited by account/reserved cap)
   |    ┌───┘   +1000 per 10s
   |  ┌─┘ burst
   |─┘
   +────────────────────────▶ time
```

The interview-relevant nuance: for poll-based sources the ESM has its *own* scaling behaviour (SQS standard ramps by adding pollers, up to 60 concurrent batches/minute increase historically; Kinesis is bounded by shard count × parallelization factor). So "Lambda scales instantly" is a myth you should be able to correct — and a cold-start storm during a burst is a real latency event you design around with provisioned concurrency or SnapStart.

#### Q65. [Theory] Why does a single failing record block an entire Kinesis/DynamoDB Stream shard, and how do you prevent it?

Stream sources guarantee **per-shard ordering**, and that guarantee is exactly what causes the "poison pill" problem. The ESM reads records from a shard **in order** and must process them in order to preserve that guarantee. If a batch fails, the ESM cannot skip ahead to later records without violating ordering — so it **retries the same batch from the same checkpoint**, blocking the shard. Records pile up behind the poison pill, and **`IteratorAge` climbs** (the age of the oldest unprocessed record), which is your primary alarm signal.

```
shard:  [r1 r2 r3(poison) r4 r5 ...]
            ESM retries batch at r3 forever → r4, r5 never processed → IteratorAge ↑
```

Mitigations, layered:
- **`MaximumRetryAttempts`** — cap retries so a poison pill is eventually given up on (default is effectively "until record expires," up to ~7 days).
- **`MaximumRecordAgeInSeconds`** — discard records older than a threshold.
- **`BisectBatchOnFunctionError`** — on failure, split the batch in half and retry each, narrowing down to the single bad record so good neighbours still get processed.
- **On-failure destination** (`DestinationConfig`) — send the metadata of the failed batch to an SQS queue or SNS topic for out-of-band handling, so the shard can advance.
- **Idempotent processing** — because retries reprocess good records repeatedly, the rest of the batch must be safe to re-run.

Contrast with SQS standard, which is *unordered*, so a bad message just goes back to the queue and later to a DLQ without blocking anything — which is why "use SQS instead of a stream" is sometimes the right architectural fix when you don't actually need ordering.

#### Q66. [Practical] How do event filters on an event source mapping work, and why do they save money?

Event filtering lets you attach **filter criteria** to an ESM so the Lambda-managed poller evaluates each record *before* invoking your function and **drops non-matching records without invoking** (for SQS, dropped messages are deleted from the queue; for streams, the checkpoint advances past them). You pay nothing for filtered-out records because no invocation happens.

Filters use a JSON pattern syntax (the same content-filtering grammar as EventBridge): equality, prefix, numeric ranges, `anything-but`, existence, and OR across values.

```json
{
  "filters": [
    {
      "pattern": "{ \"body\": { \"eventType\": [\"ORDER_PLACED\"], \"amount\": [{ \"numeric\": [\">\", 100] }] } }"
    }
  ]
}
```

This ESM only invokes the function for `ORDER_PLACED` events over $100; everything else is discarded at the poller. Why it matters:
- **Cost**: you're not billed for invocations on records you'd immediately discard in code.
- **Concurrency**: filtered records don't consume your concurrency budget, so noise can't crowd out signal.
- **Simplicity**: the function only ever sees relevant events, so the handler has no "is this for me?" guard clause.

The catch: filtering happens on the *raw* source payload shape (note the `body` wrapper for SQS, whose payload is a JSON string), and there's no transformation — it's match-or-drop only. For complex routing you still want EventBridge with its richer rules.

#### Q67. [Theory] What consistency model does DynamoDB offer, and why does it pair so well with Lambda?

DynamoDB defaults to **eventual consistency** for reads but offers **strongly consistent reads** as a per-request option (`ConsistentRead=true`), plus **ACID transactions** (`TransactWriteItems`/`TransactGetItems`) across multiple items, and **conditional writes** for optimistic concurrency.

- **Eventually consistent read** (default, cheaper — half the RCU): may not reflect a write that completed moments ago, because it might be served from a replica that hasn't caught up.
- **Strongly consistent read**: reflects all writes acknowledged before it, served from the leader replica (not available on GSIs).
- **Transactions**: all-or-nothing across up to 100 items, used for the transactional-outbox and atomic dedup patterns (Q40).

Why it pairs with Lambda:
- **No connection management** — it's an HTTP/API service, so there's no connection pool to exhaust as concurrency scales (unlike RDS — Q38). Each Lambda environment just makes signed HTTPS calls.
- **Serverless scaling** — on-demand capacity scales with your function's concurrency instead of being a fixed bottleneck.
- **Conditional writes** give you the atomic primitive idempotency guards need without a separate lock service.
- **DynamoDB Streams** close the loop, turning table changes into an event source that triggers more Lambdas.

The result is a fully serverless data path where neither tier has a fixed capacity ceiling that the other can overwhelm — which is exactly why "API Gateway → Lambda → DynamoDB" is the canonical serverless stack.

#### Q68. [Theory] How do Lambda extensions work, and what is the difference between internal and external extensions?

Extensions are a way to run additional code (observability agents, secrets caches, security tooling) alongside your function, integrated via the **Extensions API** and **Telemetry API**.

- **Internal extensions** run **inside the runtime process** — e.g. a Java agent attached via `JAVA_TOOL_OPTIONS`/`-javaagent` that instruments your code in-process. They share the runtime's lifecycle and memory.
- **External extensions** run as **separate processes** in the same execution environment, started before the runtime. They register with the Extensions API, then receive lifecycle events (`INVOKE`, `SHUTDOWN`) via a long-poll, much like the runtime polls for events. They have their own entry point and can be written in any language regardless of your function's language.

```
execution environment
├── runtime process ── handler  (+ internal extension as in-process agent)
└── external extension process  ── polls /extension/event/next, gets INVOKE/SHUTDOWN
```

Lifecycle nuance that matters: external extensions get the **SHUTDOWN** event with a grace period (default ~500 ms, up to 2 s) — this is the one moment Lambda *tells* you the environment is going away, letting an extension flush buffered telemetry. They also subtly **affect the freeze boundary**: the environment isn't frozen until both the runtime *and* all extensions have responded, so a slow extension can extend billed duration. The **Parameters and Secrets Lambda Extension** (Q13) is the most common external extension — a local HTTP cache for SSM/Secrets Manager that avoids per-cold-start API calls.

### 🟠 — extended

#### Q69. [Theory] Compare SnapStart, provisioned concurrency, and GraalVM native image at the mechanism level. When does each break down?

All three attack cold starts but at different layers, with different failure modes:

- **Provisioned concurrency** — Lambda keeps N environments **fully initialized and thawed**, ready to take traffic with zero cold start. Mechanism: pre-run INIT and hold the environments warm. *Breaks down* when traffic exceeds N (overflow requests still cold-start), and it costs per-hour regardless of use; you must predict/auto-scale N.
- **SnapStart** — snapshot the warmed JVM once at publish, restore from the memory image on cold start (Q60). Mechanism: skip JVM boot/class load by cloning memory. *Breaks down* on the uniqueness/staleness/dead-connection issues (Q61); restore still has non-zero latency (chunk fetch + `afterRestore` hooks); and it constrains you to supported runtimes and adds publish-time snapshot cost.
- **GraalVM native image** — ahead-of-time compile to a native executable with no JVM, giving tens-of-ms cold starts and low memory. Mechanism: closed-world AOT compilation. *Breaks down* on **build complexity and reflection** — dynamic class loading, reflection, and dynamic proxies need explicit configuration; some libraries don't work; peak throughput can be lower than a JIT-warmed JVM for long-running hot loops; build times are long.

```
cold-start latency:  GraalVM native  <  provisioned conc.  ≈  SnapStart  <  plain JVM
ongoing $ cost:      SnapStart/native ≈ free  <  provisioned conc. (per-hour)
build/dev friction:  provisioned ≈ plain  <  SnapStart  <  GraalVM native
peak throughput:     JIT JVM (warm)  >  native image
```

The 2026 default for most Java teams is **SnapStart** (best latency/cost/effort balance), provisioned concurrency layered on for hard p99 SLAs that restore latency still misses, and native image reserved for the most extreme latency/memory-constrained functions where the build investment pays off.

#### Q70. [Theory] How does cross-region or cross-account event flow change the consistency and ordering guarantees you can rely on?

Once events cross a region or account boundary, several single-region guarantees weaken or vanish, and reasoning about it is a senior skill:

- **Ordering** — SQS FIFO and Kinesis preserve order *within a single region's* construct. Replicate events to another region (via EventBridge cross-region rules, DynamoDB global tables streams, or your own forwarding) and the cross-region path is independently retried/buffered, so global ordering is **not** preserved. You can only rely on per-source-shard ordering, and only within one region.
- **Exactly-once becomes even more aspirational** — each hop is independently at-least-once, so duplicates can be *introduced* at every boundary; idempotency keys must be carried end-to-end and be globally unique.
- **Latency and partial failure** — a cross-region hop can succeed in region A and fail in region B, leaving the two regions divergent until reconciliation. Designs need a reconciliation/repair path, not just the happy forward path.
- **Replication lag** — DynamoDB global tables are **eventually consistent across regions** (last-writer-wins on conflicts via timestamps), so a read in region B may lag a write in region A, and concurrent writes in two regions can silently clobber each other.

```
region A:  write ──▶ stream ──▶ [cross-region replicate] ──▶ region B (lagging, reordered, maybe dup)
                       last-writer-wins on conflict; no global order; per-hop at-least-once
```

The design response: carry a globally-unique idempotency key and a logical timestamp/version with every event; make every consumer idempotent and conflict-aware (LWW or CRDT-style merges); and treat each region as an independent failure domain with explicit reconciliation rather than assuming a single global ordered log.

#### Q71. [Coding] Implement a robust idempotency store that correctly handles the "crash mid-processing" race.

The naive "conditional put then act" (Q23) has a hole: if the process crashes *after* claiming the key but *before* completing the side effect, the key exists but the work never finished — and every retry sees the key and skips, permanently dropping the work. The fix is a **three-state** record (`IN_PROGRESS` → `COMPLETED`) with a lock expiry, so a stale in-progress lock can be reclaimed.

```java
public class RobustIdempotency {

    private final DynamoDbClient ddb;
    private final String table;
    private static final long LOCK_TTL_SECONDS = 60;   // max expected processing time

    enum Outcome { PROCEED, ALREADY_DONE, IN_FLIGHT_ELSEWHERE }

    /** Atomically claim the key, or detect a prior/concurrent attempt. */
    Outcome claim(String key) {
        long now = Instant.now().getEpochSecond();
        try {
            ddb.putItem(b -> b.tableName(table)
                .item(Map.of(
                    "id",        AttributeValue.fromS(key),
                    "status",    AttributeValue.fromS("IN_PROGRESS"),
                    "lockUntil", AttributeValue.fromN(Long.toString(now + LOCK_TTL_SECONDS))))
                // Claim if: brand new, OR a previous IN_PROGRESS lock has expired (crashed worker).
                .conditionExpression(
                    "attribute_not_exists(id) OR (#s = :inprog AND #lu < :now)")
                .expressionAttributeNames(Map.of("#s", "status", "#lu", "lockUntil"))
                .expressionAttributeValues(Map.of(
                    ":inprog", AttributeValue.fromS("IN_PROGRESS"),
                    ":now",    AttributeValue.fromN(Long.toString(now)))));
            return Outcome.PROCEED;                         // we hold the lock
        } catch (ConditionalCheckFailedException e) {
            // Key exists and is either COMPLETED or still-valid IN_PROGRESS — disambiguate.
            var item = ddb.getItem(b -> b.tableName(table)
                    .key(Map.of("id", AttributeValue.fromS(key)))).item();
            return "COMPLETED".equals(item.get("status").s())
                    ? Outcome.ALREADY_DONE
                    : Outcome.IN_FLIGHT_ELSEWHERE;          // another worker holds a fresh lock
        }
    }

    /** Mark done after the side effect succeeds, caching the response. */
    void complete(String key, String response) {
        ddb.updateItem(b -> b.tableName(table)
            .key(Map.of("id", AttributeValue.fromS(key)))
            .updateExpression("SET #s = :done, response = :r, #ttl = :exp")
            .expressionAttributeNames(Map.of("#s", "status", "#ttl", "expiresAt"))
            .expressionAttributeValues(Map.of(
                ":done", AttributeValue.fromS("COMPLETED"),
                ":r",    AttributeValue.fromS(response),
                ":exp",  AttributeValue.fromN(Long.toString(
                            Instant.now().plus(Duration.ofDays(1)).getEpochSecond())))));
    }
}
```

Handler usage: `claim` → if `PROCEED`, do the side effect then `complete`; if `ALREADY_DONE`, return the cached response; if `IN_FLIGHT_ELSEWHERE`, fail fast so the message is retried later (after the other worker finishes or its lock expires). The expiring lock is what makes a crashed worker's claim reclaimable — without it, a single crash poisons the key forever. This is essentially what Powertools' Idempotency module implements for you.

#### Q72. [Theory] What is the transactional outbox pattern, and how is it adapted for a DynamoDB-backed serverless system?

The **dual-write problem**: a handler that (1) writes a business change to the database and (2) publishes an event to a message bus can crash between the two, leaving them inconsistent — the order is saved but no "OrderPlaced" event fires, or vice versa. You cannot wrap a DB write and an SNS publish in one atomic transaction across two systems.

The **outbox pattern** makes the write atomic by recording the event *in the same transaction as the business change*, then publishing it asynchronously from that record.

DynamoDB adaptation:
1. In a single **`TransactWriteItems`**, write the business item **and** an outbox item (or just rely on the item write itself) atomically — they both commit or neither does.
2. **DynamoDB Streams** captures the committed change and triggers a relay Lambda.
3. The relay Lambda publishes to EventBridge/SNS/SQS. Because the stream replays on failure and the relay is idempotent, the event is delivered **at-least-once** but never lost.

```
handler ── TransactWriteItems ─▶ [ business item + (implicit) change ]   (atomic commit)
                                          │ DynamoDB Streams
                                          ▼
                              relay Lambda ── publish ──▶ EventBridge / SNS   (at-least-once)
```

The elegance is that DynamoDB Streams *is* the outbox poller — you don't build a separate poller thread (which would freeze on Lambda anyway, per Q50). The committed change and the event source are the same thing, so the "did the DB write and the event get out of sync?" failure mode is eliminated: if the item committed, the stream will eventually deliver it; if it didn't commit, there's nothing to deliver. Consumers must be idempotent to absorb the stream's at-least-once redelivery.

#### Q73. [Theory] How would you reason about and reduce the p99.9 latency tail of a serverless API, distinct from the median?

The median is dominated by warm-path work; the **tail** (p99/p99.9) is dominated by rare-but-expensive events, and they require different fixes:

Sources of the tail, roughly in order:
- **Cold starts** — by definition rare under steady load, but each one adds hundreds of ms (Java) and lands squarely in the tail. The more environments churn (bursty traffic, aggressive idle eviction, frequent deploys), the fatter the tail.
- **Downstream tail amplification** — if your handler makes several dependent calls, the slowest of N calls sets your latency (tail-of-tails). A p99 DynamoDB call behind a p99 cold start compounds.
- **Throttling/retry** — a throttled downstream call that retries with backoff turns a fast request into a multi-hundred-ms one.
- **GC pauses** (Java) and **connection re-establishment after a freeze** (Q50).

Tail-specific mitigations:
- **Eliminate cold starts from the tail**: SnapStart or provisioned concurrency sized to peak — this is the single biggest p99.9 lever for Java.
- **Hedged/parallel reads** where idempotent: issue a second request after a short delay and take the first response, capping the tail at the hedge timeout.
- **Tight client timeouts + bounded retries** so a slow dependency fails fast into a fallback rather than dragging the tail.
- **Reduce the critical-path fan-out**: fewer sequential dependent calls means fewer chances to hit a downstream tail; parallelize independent calls.
- **Measure the right thing**: alarm on p99/p99.9 `Duration` *with a cold-start dimension* (Q53), and track `IteratorAge`/throttle metrics, because averages hide the tail entirely.

The senior framing: the tail is a **statistics problem** — you're attacking the probability and cost of rare events, so you optimize for *worst-case predictability* (warm capacity, fast-fail timeouts, hedging) rather than average throughput.

#### Q74. [Practical] How does the Lambda execution role's credential delivery work, and how do credentials rotate within a warm environment?

Lambda assumes your function's **execution role** and surfaces temporary STS credentials into the environment as the variables `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, and `AWS_SESSION_TOKEN`. The default credential provider chain in the AWS SDK reads these automatically, which is why a default-configured client "just works" with no hardcoded keys.

The subtlety for warm environments and SnapStart:
- These credentials are **temporary and rotate** before they expire. Lambda refreshes the environment variables, and the SDK's `ContainerCredentialsProvider`/environment provider is designed to pick up refreshed values rather than caching the first set forever.
- If you **read the env vars yourself once at INIT** and cache the literal key/secret in your own field, you'll hold a credential that **expires** and then get `ExpiredToken` errors hours later on a long-lived warm environment. Always let the SDK manage credential resolution; never snapshot the raw keys.
- Under **SnapStart**, credentials captured in the snapshot are stale on restore (Q61) — another reason to rely on the SDK's provider (which re-resolves) rather than a cached copy, and to recreate any credential-bearing object in `afterRestore`.

```java
// CORRECT: SDK resolves (and refreshes) credentials from the environment itself.
private static final S3Client S3 = S3Client.create();

// WRONG: snapshots a temporary key that will expire under you.
// private static final String KEY = System.getenv("AWS_ACCESS_KEY_ID");
```

The principle: treat the execution role's credentials as a **rotating, SDK-managed resource**, not a static secret — caching them defeats the rotation the platform does for your security.

#### Q75. [Theory] What is `IteratorAge`, and how do you use it to diagnose stream-processing health?

`IteratorAge` (emitted by Lambda for Kinesis and DynamoDB Stream sources) is the **age of the oldest record in the batch when Lambda received it** — i.e. how far *behind* your consumer is from the tip of the stream. It is the single most important health metric for stream processing.

Interpreting it:
- **Near zero** — you're keeping up; records are processed almost as fast as they arrive.
- **Steadily rising** — your consumer is falling behind: either the function is too slow, concurrency is too low for the incoming rate, or a poison pill is blocking a shard (Q65). Left unchecked, records age out (Kinesis retention defaults to 24 h, max 7 days; once aged out, **data is lost**).
- **Sawtooth** — periodic catch-up after bursts; usually fine if it returns to baseline.

```
IteratorAge
   ^         ╱╲    poison pill or under-scaling: never recovers → data loss risk
   |        ╱  ╲__╱
   |   ╱╲__╱            healthy sawtooth (recovers to baseline)
   +────────────────▶ time
```

Levers to reduce it:
- **Increase parallelization** — Kinesis is bounded by `shard count × ParallelizationFactor` (up to 10 concurrent batches per shard); add shards or raise the factor.
- **Speed up the handler** — more memory (more CPU), batch the downstream writes, remove synchronous slow calls.
- **Larger batch size / batching window** — amortize per-invoke overhead (trade latency for throughput).
- **Fix poison pills** — `BisectBatchOnFunctionError` + on-failure destination so one bad record doesn't stall the shard.

In an interview, "I'd alarm on `IteratorAge` and treat sustained growth as either under-provisioned concurrency or a poison pill" signals real operational stream experience.

#### Q76. [Theory] How do you design multi-tenant isolation and fairness in a shared serverless platform?

When one Lambda-based platform serves many tenants (customers), the challenge is preventing one tenant from degrading others — the **noisy-neighbour** problem — given that account concurrency is a shared finite pool.

Isolation dimensions and techniques:
- **Concurrency fairness** — the shared 1,000-default account concurrency means a single tenant flooding requests can starve everyone. Defenses: **reserved concurrency per tenant-tier function**, or front each tenant's traffic with a per-tenant SQS queue and a controlled drain rate, or token-bucket throttling keyed by tenant in a shared store.
- **Account/boundary isolation** — for strong isolation, give large tenants (or tenant tiers) their **own AWS account** (cell-based architecture), so concurrency, limits, and blast radius are physically separated. This is the strongest answer for regulated/enterprise tenants.
- **Data isolation** — pool vs silo models: shared table with tenant-id partition keys and IAM/`dynamodb:LeadingKeys` condition keys to enforce row-level access (pool), versus a table/account per tenant (silo).
- **Cost attribution** — tag invocations/resources per tenant and use per-tenant metrics so you can bill and detect abuse.
- **Blast radius / poison isolation** — a poison message from one tenant shouldn't block another's processing; per-tenant queues/shards keep failures contained.

```
noisy tenant ─▶ [per-tenant SQS + reserved conc.] ─▶ Lambda   (can't starve others)
big/regulated tenant ─▶ [dedicated AWS account / cell]        (physical isolation)
```

The senior framing is **cell-based architecture**: partition tenants into cells (each a self-contained stack with its own concurrency and limits) so that the blast radius of any failure or noisy neighbour is bounded to one cell, and you scale by adding cells rather than growing one shared pool. The trade-off is operational complexity and lower density versus stronger isolation and fairness.

### 🔴 — extended

#### Q77. [Theory] At extreme scale, when does the per-request and per-environment overhead of Lambda itself become the bottleneck, and how do you reason about the crossover to containers quantitatively?

Beyond the headline GB-seconds cost, several *structural* overheads of the FaaS model dominate at extreme scale and define the crossover:

- **Per-request charge** — at billions of invocations the flat per-request fee ($0.20/M) becomes material independent of duration; a high-frequency, ultra-short function pays disproportionately for requests vs compute.
- **No request multiplexing** — one event per environment means you cannot amortize a warm process across concurrent requests the way a container handling 100 concurrent connections does. A container with an async server serves many requests on one set of resources; Lambda provisions a whole environment per concurrent request. At high steady QPS this is the core inefficiency.
- **Cold-start tax under churn** — at scale with frequent deploys and bursty traffic, the aggregate cold-start cost (latency + INIT billing) is a recurring tax a long-lived fleet doesn't pay.
- **Fixed per-environment memory floor** — you pay for the function's full memory per concurrent execution; a container can pack many lightweight requests under one memory allocation.

Reasoning about the crossover quantitatively:
```
Lambda monthly ≈ requests × $/req + Σ(memory_GB × duration_s) × $/GB-s + provisioned-conc hours
Container monthly ≈ (right-sized task count to hold peak QPS at target utilization) × task $/hr × 730
```
You estimate sustained QPS and duration, compute the required *always-on* container capacity at a target utilization (say 60–70%) to hold peak, and compare to Lambda's per-invocation total at that same traffic. The crossover typically appears at **sustained high utilization** — when a container fleet would run consistently busy (so little idle waste) and Lambda would be billing for the same compute *plus* per-request and per-environment overhead.

The senior nuance: this is a **fully-loaded** comparison, not just the AWS bill. Containers add ops cost (patching, scaling policies, on-call, cluster management); Lambda's premium often *buys back* that engineering cost for small teams. So the honest crossover is "when the compute delta exceeds the operational savings," which is workload- *and* org-specific — and the right architecture is usually **hybrid**: Lambda for spiky/event-driven/glue, containers for the steady high-QPS core, with the boundary drawn deliberately and re-measured as traffic grows.

#### Q78. [Theory] Design the failure-domain and disaster-recovery strategy for a business-critical serverless system. What can and cannot fail independently?

A senior DR design starts by mapping **failure domains** — what fails together and what fails independently — because serverless's managed services hide but do not eliminate these boundaries.

Failure domains to reason about:
- **Availability Zone** — Lambda automatically spreads environments across AZs in a region, so single-AZ failure is largely transparent *for compute*; but your VPC subnets, RDS, and ElastiCache may be AZ-bound, so the data tier is the real single-AZ risk.
- **Region** — a regional service impairment (Lambda, DynamoDB, API Gateway, or a dependency) takes down everything in that region. This is the domain that demands an explicit multi-region strategy for business-critical systems.
- **Account / control plane** — throttling or a misconfiguration at the account level (concurrency exhaustion, an SCP change, a bad deploy) is a failure domain independent of infrastructure health.
- **Dependency** — a single downstream (a third-party API, a shared database) is its own domain; circuit breakers and bulkheads isolate it (Q39, Q37).

DR patterns by RTO/RPO target:
```
RTO/RPO target        strategy
high (hours)          backup & restore (redeploy stack from IaC + restore data)
medium                pilot light (minimal warm standby region, scale on failover)
low (minutes)         warm standby (scaled-down full stack in region B, Route 53 failover)
near-zero             active-active multi-region (global tables, both regions serving)
```

Serverless-specific DR mechanics:
- **Infrastructure as code (CDK/SAM)** makes the *compute* tier trivially reproducible in another region — redeploying functions is fast; the hard part is **data**.
- **DynamoDB global tables** give active-active, multi-region, eventually-consistent data with last-writer-wins — the natural fit for serverless multi-region, but you must design for cross-region conflict and lag (Q70).
- **Route 53 health checks + failover routing** (or Global Accelerator) shift traffic at the edge between regional API Gateway endpoints.
- **Idempotency and at-least-once** become essential across the failover boundary, because in-flight events may be retried in the failover region (Q70).

What *cannot* fail independently and must be designed around: anything sharing a region is one domain (your "multi-AZ" Lambda + single-region DynamoDB is **not** region-resilient); and an active-active design trades strong consistency for availability — you cannot have both a single global ordered/consistent view *and* independent regional survival. The interview signal is explicitly stating the **RTO/RPO targets first**, choosing the cheapest pattern that meets them, and being honest that true active-active forces eventual consistency and conflict handling as the price of regional independence.

#### Q79. [Behavioral] You discover that a "serverless-first" mandate across the org is causing recurring incidents and cost overruns. How do you lead the course-correction without undermining the platform team or relitigating settled decisions?

This probes systems-level leadership, organizational diplomacy, and intellectual honesty under political constraint. A strong answer has several threads:

- **Lead with evidence, not opinion.** Quantify the actual harm: incident postmortems traced to cold-start SLA breaches and shared-concurrency starvation, and a cost model showing specific steady-high-QPS workloads costing 2–3× their container equivalent. Frame it as data the org would want to know, not as "serverless was a mistake."
- **Separate the mandate from the technology.** The problem is usually the *blanket* mandate, not serverless itself. Reframe from "serverless-first" to "**right tool per workload**," which preserves serverless where it genuinely wins (spiky, event-driven, glue) and surgically moves only the workloads where it doesn't. This lets the platform team keep ownership and saves face.
- **Protect the platform team's standing.** Bring them in as partners: the recurring incidents (concurrency governance, golden-path gaps) are exactly the problems a platform team is *meant* to own, so position the course-correction as *empowering* them to add the missing guardrails (reserved-concurrency governance, paved-road constructs, a documented "when not to use Lambda" rubric) rather than as a referendum on their work.
- **Make it incremental and reversible.** Propose a small, measured pilot: move one or two of the worst-offending workloads to containers behind a strangler-fig, measure cost and latency, and let the data decide rather than mandating a swing to the opposite extreme (which would just repeat the original mistake in reverse).
- **Change the decision process, not just the decisions.** The durable fix is institutional: a lightweight architecture-decision rubric (traffic shape, latency SLA, steady vs spiky, statefulness) that teams apply *per workload*, so the org stops making one-size-fits-all mandates. That's the real deliverable.
- **Own your part and avoid blame.** Acknowledge the original mandate was reasonable given what was known (it reduced ops burden and accelerated delivery early on); the learning is that scale revealed workload heterogeneity. This models the intellectual honesty you want from the org.

The interviewer is checking whether you can drive a **data-driven, face-saving, incremental** correction of a popular-but-flawed decision — improving outcomes and the decision-making process while keeping the platform team as allies, rather than "winning the argument" at the cost of the relationship.

## 🧩 Extended Questions — Set 2: Practical scenarios, troubleshooting & coding

### 🟢 — extended

#### Q80. [Practical] A teammate reports "my Lambda environment variable change didn't take effect." What's the most likely cause and how do you confirm it?

The overwhelmingly common cause is **versioning and aliases**: environment variables are part of the function *configuration*, and if traffic is served via a **published version** or an alias pointing at an old version, your change to `$LATEST` has no effect on production traffic. A change to `$LATEST` only affects unqualified invocations or aliases pointing at `$LATEST`.

How to confirm and fix:

1. Check whether the invoker uses a qualified ARN (`...:function:foo:prod` or `...:function:foo:7`). If so, the variable lives in that immutable version's config, not `$LATEST`.
2. Run `aws lambda get-function-configuration --function-name foo --qualifier prod` and inspect the `Environment.Variables` actually attached to that version.
3. Publish a new version with the updated config and shift the alias (or the deployment pipeline) to it.

Secondary causes: a CloudFormation/CDK/Terraform drift where the change was made in the console but the IaC pipeline reverted it on the next deploy, or a SnapStart function where the variable is read once during INIT and the value is frozen in the snapshot until you republish. The rule: **environment variables are immutable per version; production almost always runs a published version.**

#### Q81. [Practical] You see `Task timed out after 3.00 seconds` in CloudWatch but the downstream call usually takes 200 ms. How do you investigate?

A timeout when the happy path is fast almost always means the function is **blocked waiting on something**, not that the work is slow. Walk the likely causes:

1. **The timeout is too low for a cold start.** The 3 s default includes JVM boot + INIT on a cold start. Look at whether the timed-out invocations correlate with `Init Duration` lines in the logs (cold starts). Fix: raise the timeout and, separately, reduce cold-start time.
2. **A hung network call with no client timeout.** If the downstream is occasionally unreachable (NAT misconfig, security group, throttling) and your HTTP client has no connect/read timeout, the call blocks until Lambda kills it. **Always set explicit SDK/HTTP timeouts shorter than the Lambda timeout.**
3. **VPC egress black hole.** A Lambda in a private subnet with no NAT gateway / VPC endpoint to reach the AWS API will hang on the first SDK call and time out. Symptom: every cold-start invocation that makes an external call times out, warm ones may too.
4. **Connection pool exhaustion** — all warm environments fighting over a too-small pool (e.g. RDS Proxy borrow timeout).

The diagnostic move: add a log line with elapsed time immediately before and after the downstream call. If "before" prints but "after" never does, it's a blocked/hung call, not slow compute.

#### Q82. [Practical] How do you reproduce and test a Lambda locally before deploying?

Use the AWS SAM CLI to invoke the function with a sample event, which runs your handler inside a Lambda-like container:

```bash
# Invoke once with a saved event payload
sam local invoke OrderFunction --event events/order.json

# Or stand up API Gateway locally
sam local start-api
curl http://localhost:3000/orders/123
```

For richer integration (DynamoDB, SQS, S3) use **LocalStack** so SDK calls hit local emulated services. Capture real event JSON from a live invocation (CloudWatch Logs, or `aws lambda invoke` with `--log-type Tail`) and save it as a fixture so your local event matches production exactly. Two caveats worth stating in an interview: local emulators **diverge** from real IAM, throttling, and cold-start behaviour, so they catch logic and event-shape bugs but not permission/scaling bugs — for those you need an ephemeral cloud environment.

#### Q83. [Coding] Write a unit test for a Lambda handler that mocks the AWS SDK client.

Keep the SDK client injectable so the test can substitute a mock. Here the handler takes the client via constructor (production uses a default; tests pass a Mockito mock).

```java
public class OrderHandler implements RequestHandler<Order, String> {
    private final DynamoDbClient ddb;

    public OrderHandler() { this(DynamoDbClient.create()); }   // prod
    OrderHandler(DynamoDbClient ddb) { this.ddb = ddb; }       // test seam

    @Override
    public String handleRequest(Order order, Context ctx) {
        ddb.putItem(b -> b.tableName("orders").item(toItem(order)));
        return "saved:" + order.getId();
    }
}

// --- test ---
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class OrderHandlerTest {
    @org.junit.jupiter.api.Test
    void savesOrderAndReturnsId() {
        DynamoDbClient mock = mock(DynamoDbClient.class);
        when(mock.putItem(any(java.util.function.Consumer.class)))
            .thenReturn(PutItemResponse.builder().build());

        OrderHandler handler = new OrderHandler(mock);
        String result = handler.handleRequest(new Order("A1"), null);

        assertEquals("saved:A1", result);
        verify(mock, times(1)).putItem(any(java.util.function.Consumer.class));
    }
}
```

The point is that `Context` can be `null` in the test because the thin handler doesn't depend on it, and the SDK boundary is mocked so the test is fast and deterministic.

#### Q84. [Practical] Your function logs aren't showing up in CloudWatch. What are the usual causes?

Three things in order of likelihood:

1. **Missing IAM permission.** The execution role needs `logs:CreateLogGroup`, `logs:CreateLogStream`, and `logs:PutLogEvents` (the `AWSLambdaBasicExecutionRole` managed policy grants these). Without them, the function runs but can't write logs — and you won't see an error in the logs because, well, it can't log.
2. **Looking at the wrong place.** Logs go to log group `/aws/lambda/<function-name>`. If you invoke a specific version/alias you're still in the same group but a different stream; and a function in another region writes to that region's CloudWatch.
3. **Buffering / async logging.** With Java + an async log appender (or `System.out` buffered), lines can be lost if the environment is frozen mid-flush. Flush before returning, or use the Lambda logger / Powertools logging which is integrated with the runtime's log delivery.

A fourth modern cause: if you configured **advanced logging controls** with a JSON format and a minimum level of `WARN`, your `INFO` lines are filtered out by the platform before they reach CloudWatch.

#### Q85. [Practical] How do you wire up a dead-letter queue and confirm failed events actually land there?

For **asynchronous** invocation, attach an on-failure destination (preferred over the legacy DLQ) or a DLQ so events that exhaust the two automatic retries are captured instead of silently dropped.

```bash
aws lambda put-function-event-invoke-config \
  --function-name processor \
  --maximum-retry-attempts 2 \
  --destination-config '{"OnFailure":{"Destination":"arn:aws:sqs:...:failed-events"}}'
```

To confirm it works, deliberately make the function throw (a feature-flagged "poison" input or a temporary `throw`), invoke asynchronously, and watch the SQS `failed-events` queue depth rise after the retry window. Important distinctions for the interview: a **DLQ** captures only the original event payload, whereas an **on-failure destination** captures a richer record including the error and response context, and destinations work for both success and failure. For **SQS event sources**, the DLQ is configured on the *source queue's redrive policy*, not on the function — a common mix-up.

#### Q86. [Coding] Parse an S3 ObjectCreated event in Java and extract the bucket and key.

S3 sends a batch of records; the key is URL-encoded and must be decoded (spaces become `+`, etc.).

```java
import com.amazonaws.services.lambda.runtime.events.S3Event;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public class S3Handler implements RequestHandler<S3Event, String> {
    @Override
    public String handleRequest(S3Event event, Context ctx) {
        for (var record : event.getRecords()) {
            String bucket = record.getS3().getBucket().getName();
            String rawKey = record.getS3().getObject().getKey();
            // S3 keys arrive URL-encoded; '+' means space.
            String key = URLDecoder.decode(rawKey.replace("+", " "),
                                           StandardCharsets.UTF_8);
            ctx.getLogger().log("New object s3://" + bucket + "/" + key);
            process(bucket, key);
        }
        return "ok";
    }
}
```

The classic bug is using the raw key directly in a `GetObject` call and getting a `NoSuchKey` error for any object whose name contains a space or special character, because the encoded key (`my%20file.txt`) doesn't match the real key (`my file.txt`).

#### Q87. [Practical] A scheduled Lambda (EventBridge cron) didn't run. How do you debug it?

Start at the trigger and work toward the function:

1. **Is the rule enabled and is the cron right?** EventBridge `cron()` uses a 6-field UTC expression and a quirk: you cannot specify both day-of-month and day-of-week (one must be `?`). A malformed expression silently never matches. Check the rule's "Next 10 trigger dates" in the console.
2. **Did the rule fire but fail to invoke?** Check the rule's `FailedInvocations` and `Invocations` CloudWatch metrics. If `Invocations` is 0, the rule never matched (cron/timezone issue). If `FailedInvocations` > 0, the rule fired but couldn't invoke the target.
3. **Permissions.** EventBridge needs `lambda:InvokeFunction` permission on the target (a resource-based policy statement on the function). If it's missing, the rule fires but the invoke is denied.
4. **The function ran but errored** — then you'd see invocations in the function's own metrics/logs. Configure a DLQ on the rule's target so dropped invocations are visible.

The key mental split: separate "did the schedule fire" (EventBridge metrics) from "did the function run" (Lambda metrics) — they're different systems with different failure modes.

#### Q88. [Practical] How do you safely roll out a new version of a Lambda to limit blast radius?

Use **weighted aliases** for canary/linear deployment. An alias can split traffic between two versions by weight, so you shift a small percentage to the new version, watch metrics, then ramp.

```bash
# Send 10% of traffic to version 8, 90% stays on the current version 7
aws lambda update-alias --function-name api --name prod \
  --function-version 7 \
  --routing-config '{"AdditionalVersionWeights":{"8":0.10}}'
```

In practice you let **AWS CodeDeploy** automate this (`Canary10Percent5Minutes`, `Linear10PercentEvery1Minute`) with CloudWatch alarms that **auto-rollback** if error rate or latency breaches a threshold. Because each version is immutable, rollback is instant — just point the alias back at the prior version. This is far safer than mutating `$LATEST` in place, where a bad deploy hits 100% of traffic immediately with no clean revert.

### 🟡 — extended

#### Q89. [Practical] You're seeing intermittent `ProvisionedThroughputExceededException` / throttling from DynamoDB under a Lambda spike. How do you fix it without over-provisioning?

The root cause is Lambda's fan-out: a burst spins up hundreds of concurrent environments that all hammer the table at once, exceeding the per-partition or table throughput. Layered fixes:

1. **Switch the table to on-demand capacity** if traffic is spiky — it absorbs bursts without you guessing the right RCU/WCU.
2. **Add exponential backoff with jitter** on the write (the AWS SDK retries by default, but tune `numRetries` and the backoff strategy for your spike shape).
3. **Throttle the source** — put SQS in front and cap the event source mapping's `maximumConcurrency` so Lambda drains at a rate the table can sustain, converting a spike into a steady drain.
4. **Check for a hot partition** — if all writes share one partition key, no amount of table capacity helps; you need to distribute the key (write sharding).
5. **Reserved concurrency** on the function as a blunt ceiling so it can't out-scale the table.

The framing: don't just raise capacity — **reshape the load** so the elastic compute tier doesn't overwhelm the (less elastic) data tier.

#### Q90. [Coding] Implement exponential backoff with jitter for a retryable downstream call in Java.

Full jitter (random between 0 and the computed cap) avoids the thundering-herd where every throttled caller retries at the same instant.

```java
public <T> T withRetry(Supplier<T> call, int maxAttempts) {
    long baseMs = 50, capMs = 5_000;
    java.util.concurrent.ThreadLocalRandom rng =
        java.util.concurrent.ThreadLocalRandom.current();
    RuntimeException last = null;

    for (int attempt = 0; attempt < maxAttempts; attempt++) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            if (!isRetryable(e)) throw e;       // 4xx (non-throttle) → don't retry
            last = e;
            long exp = Math.min(capMs, baseMs * (1L << attempt)); // 50,100,200...
            long sleep = rng.nextLong(exp + 1);  // full jitter: [0, exp]
            try { Thread.sleep(sleep); }
            catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted", ie);
            }
        }
    }
    throw last;   // exhausted
}
```

Crucial detail for a serverless context: the total worst-case retry time must stay **under the remaining Lambda execution budget** (`context.getRemainingTimeInMillis()`), otherwise you trade a downstream error for a less-diagnosable Lambda timeout. Cap `maxAttempts` and the backoff accordingly, and only retry idempotent operations.

#### Q91. [Practical] Cold starts spiked after you added a new dependency. How do you find and fix the culprit?

Cold start time for Java is dominated by classloading and INIT work, so a new dependency that pulls in a large transitive tree or does heavy static init is a prime suspect.

Investigation:

1. **Measure the INIT delta.** The `Init Duration` field in the `REPORT` log line (or the X-Ray INIT subsegment) tells you exactly how long INIT took before vs after. Confirm the regression is in INIT, not INVOKE.
2. **Inspect the dependency tree** with `mvn dependency:tree` / `gradle dependencies` — a single "small" library can drag in Netty, Jackson modules, or a reflection-heavy framework.
3. **Check what runs at class-load time** — a new library may register providers, scan the classpath, or build large static maps during `<clinit>`.

Fixes: exclude unnecessary transitive deps, prefer `UrlConnectionHttpClient` over the Netty-based async client for the AWS SDK, lazy-initialize the heavy component so it's not in the cold-start path if not every request needs it, and consider SnapStart so the cost is paid once at publish time rather than on every cold start. Right-sizing memory upward also speeds classloading because CPU scales with memory.

#### Q92. [Practical] How do you diagnose and resolve `Rate Exceeded` / `TooManyRequestsException` (throttling) on the function itself?

This means concurrent executions hit a ceiling. Identify which ceiling:

1. **Account concurrency limit** (default 1,000/region). The `ConcurrentExecutions` metric near the account limit plus `Throttles` > 0 across many functions points here. Fix: request a quota increase, or reserve concurrency for critical functions so a noisy neighbour can't consume the whole pool.
2. **Reserved concurrency on this function set too low.** If you reserved 50 and the function needs 200, it throttles at 50. Check the function's reserved-concurrency setting against its `ConcurrentExecutions`.
3. **Burst limit.** Lambda scales by an initial burst (region-dependent, e.g. 1,000) then adds 1,000/min (as of the 2023+ scaling model, per-function). A sudden vertical spike beyond the burst gets throttled even if you're under the account limit.

For asynchronous and SQS sources, throttled invocations are retried automatically (events queue up), so the user-visible symptom differs from synchronous (where the caller gets a 429 immediately). The buffer-with-SQS pattern converts hard throttle errors into a tolerable processing delay.

#### Q93. [Coding] Write a handler that reads `getRemainingTimeInMillis()` and bails out gracefully before the hard timeout.

Self-imposed soft deadlines let you checkpoint, flush, or return a partial result instead of being killed mid-operation (which can leave state inconsistent).

```java
public class BatchHandler implements RequestHandler<List<Item>, BatchResult> {

    private static final long SAFETY_MARGIN_MS = 1_000; // leave time to flush

    @Override
    public BatchResult handleRequest(List<Item> items, Context ctx) {
        BatchResult result = new BatchResult();
        for (Item item : items) {
            // Stop before Lambda force-kills us, so we can checkpoint progress.
            if (ctx.getRemainingTimeInMillis() < SAFETY_MARGIN_MS) {
                result.setIncomplete(true);
                result.setNextIndex(items.indexOf(item)); // resume point
                break;
            }
            process(item);
            result.incrementProcessed();
        }
        flushMetricsAndCheckpoint(result); // runs even on early exit
        return result;
    }
}
```

The returned `nextIndex` lets the caller (or a Step Functions loop) resume from where you stopped — turning a long job into resumable chunks instead of risking a hard timeout that loses all progress and triggers a full reprocess.

#### Q94. [Practical] An SQS-triggered Lambda is reprocessing the same messages repeatedly and never draining the queue. What's wrong?

This is the classic **visibility-timeout vs function-timeout** mismatch. If the function's timeout (or actual processing time) is longer than the queue's visibility timeout, a message becomes visible again and gets redelivered to *another* environment while the first is still working — so it's processed multiple times and never acknowledged cleanly.

The rule: **set the queue's visibility timeout to at least 6× the function timeout** (AWS's own recommendation), so an in-flight message can't reappear mid-processing or during the retry buffer.

Other causes of the same symptom:

- **The handler throws on a single bad message**, so the whole batch returns to the queue; without partial batch response, good messages are reprocessed forever alongside the poison one until they hit `maxReceiveCount` → DLQ.
- **No DLQ / high `maxReceiveCount`**, so a genuinely unprocessable message loops indefinitely.
- **Not deleting messages** — but with the native event source mapping, successful return deletes them automatically; this bites people using manual `ReceiveMessage` polling instead.

Fix: align timeouts, enable `ReportBatchItemFailures`, make processing idempotent, and set a DLQ with a sane `maxReceiveCount`.

#### Q95. [Practical] How do you troubleshoot a Lambda that works in the console "Test" but fails when triggered by API Gateway?

The "Test" button sends whatever JSON you paste, but API Gateway wraps the request in a **proxy event envelope** — the real event shape is completely different. Mismatches here are the most common cause.

Checklist:

1. **Event shape.** API Gateway proxy integration delivers `APIGatewayProxyRequestEvent` (body is a *string*, often Base64-encoded for binary, headers and path params in specific fields). If your handler typed the input as your domain object, deserialization fails. Type it as the proxy event and parse `getBody()` yourself.
2. **Response shape.** The function must return `{statusCode, headers, body(String)}`. Returning a bare domain object yields a 502 "malformed Lambda proxy response."
3. **Permissions / integration.** API Gateway needs permission to invoke the function; a missing `lambda:InvokeFunction` resource policy gives a 500 with "Internal server error" but a clean console test.
4. **Base64 bodies.** Binary or compressed bodies arrive Base64-encoded with `isBase64Encoded=true`; decode before parsing.

Reproduce faithfully by capturing the actual proxy event from CloudWatch and feeding *that* JSON to the console test, not a hand-written payload.

#### Q96. [Coding] Implement structured JSON logging with a correlation ID propagated from the incoming request.

A correlation ID threaded through logs lets you trace one request across services. Pull it from a header (or generate one) and include it in every log line.

```java
public class TracedHandler implements
        RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final com.fasterxml.jackson.databind.ObjectMapper M =
        new com.fasterxml.jackson.databind.ObjectMapper();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(
            APIGatewayProxyRequestEvent req, Context ctx) {

        String correlationId = req.getHeaders() != null
            ? req.getHeaders().getOrDefault("X-Correlation-Id",
                                            ctx.getAwsRequestId())
            : ctx.getAwsRequestId();

        log(ctx, correlationId, "INFO", "handling request",
            Map.of("path", req.getPath()));

        // ... business logic, passing correlationId downstream in headers ...

        return new APIGatewayProxyResponseEvent().withStatusCode(200)
            .withHeaders(Map.of("X-Correlation-Id", correlationId))
            .withBody("{\"ok\":true}");
    }

    private void log(Context ctx, String corrId, String level,
                     String msg, Map<String, Object> fields) {
        try {
            var entry = new java.util.HashMap<String, Object>(fields);
            entry.put("level", level);
            entry.put("message", msg);
            entry.put("correlationId", corrId);
            entry.put("requestId", ctx.getAwsRequestId());
            entry.put("function", ctx.getFunctionName());
            ctx.getLogger().log(M.writeValueAsString(entry)); // one JSON line
        } catch (Exception e) { ctx.getLogger().log("log-fail: " + e); }
    }
}
```

Now CloudWatch Logs Insights can `filter correlationId = "..."` to retrieve the full path of a single request. AWS Lambda Powertools for Java does this with `@Logging` and `appendKey`, which is the recommended production path.

#### Q97. [Practical] Your VPC-attached Lambda can reach RDS but times out calling the Secrets Manager / S3 API. Why?

Because the function is in a private subnet, it has **no route to the public AWS API endpoints** unless you provide one. RDS works because it's inside the VPC; Secrets Manager and S3 are reached over their *public* (or VPC-endpoint) endpoints, which a private subnet can't hit by default.

Two fixes:

1. **VPC endpoints (PrivateLink)** — create an interface endpoint for Secrets Manager and a **gateway** endpoint for S3/DynamoDB. Traffic stays on the AWS network, no NAT needed, no data charges for the gateway endpoints. This is the preferred, cheaper, more secure option.
2. **NAT gateway** — put a NAT in a public subnet and route the private subnet's `0.0.0.0/0` through it. Works for everything but incurs NAT data-processing charges that add up at scale.

The diagnostic tell: the call hangs and times out (rather than getting an auth error) because the packets have nowhere to go. Always set an explicit client timeout so this surfaces as a fast, clear error rather than consuming the whole Lambda budget.

#### Q98. [Practical] How do you handle and recover from partial failures in a Step Functions workflow that already did some side effects?

You implement **compensation** (the Saga pattern): for each step that performs an irreversible side effect, define a compensating action and route to it via `Catch` when a later step fails.

```
Reserve Inventory ──▶ Charge Card ──▶ Ship
       │ Catch              │ Catch
       ▼                    ▼
  Release Inventory ◀── Refund Card   (compensations run in reverse)
```

In ASL, each task's `Catch` routes to a compensation state, and you chain compensations so they undo completed work in reverse order. Key practices: make each forward action and each compensation **idempotent** (a retried refund must not double-refund), record what was completed in the execution state (`ResultPath`) so compensation knows exactly what to undo, and use `.waitForTaskToken` for steps needing external confirmation. Step Functions' durable execution history means even if the workflow itself is interrupted, it resumes and the compensation logic still runs — far more robust than try/catch spread across independent Lambdas.

### 🟠 — extended

#### Q99. [Practical] In production you observe p50 latency is fine but p99 spikes to several seconds intermittently. Walk through your diagnosis.

A bimodal latency distribution (fast median, slow tail) in serverless is the signature of **cold starts**. Diagnose systematically:

1. **Confirm it's cold starts.** Filter logs for the `Init Duration` field — those invocations are cold. Correlate their timestamps with the p99 spikes. In X-Ray, the INIT subsegment appears only on cold starts.
2. **Quantify the cold-start rate.** It rises with scaling events (traffic ramps), after deployments (all environments recycled), and after idle eviction. A low-traffic function that scales from 1→50 will see ~49 cold starts in the spike.
3. **Rule out downstream tail.** If `Init Duration` is absent on the slow ones, the tail is elsewhere — a downstream service's own p99, a connection-pool wait, or DNS resolution. Check the downstream subsegment durations in X-Ray.

Mitigations, matched to cause: **provisioned concurrency** (or SnapStart for Java) to eliminate cold-start tail on latency-critical paths; **right-size memory** to shrink each cold start; smooth traffic with a queue if the spikes are scaling-driven; and fix downstream tails (RDS Proxy, caching, timeouts) if INIT isn't the culprit. The discipline: the tail and the median have different root causes, so optimize them separately.

#### Q100. [Coding] Implement a per-environment cache with TTL for read-mostly config, safe under Lambda's reuse model.

A warm environment can cache config to avoid re-fetching on every invocation, but you want a TTL so it eventually picks up changes. The cache must be thread-confined-safe (one invocation per environment, but afterRestore/refresh can race) and tolerate the environment freezing between invocations.

```java
public class ConfigCache {
    private static final long TTL_MS = 60_000;
    private final Supplier<Map<String,String>> loader;
    private volatile Map<String,String> cached;
    private volatile long expiresAt = 0;

    public ConfigCache(Supplier<Map<String,String>> loader) {
        this.loader = loader;
    }

    public Map<String,String> get() {
        long now = System.currentTimeMillis();
        Map<String,String> snapshot = cached;
        if (snapshot != null && now < expiresAt) {
            return snapshot;            // fresh enough → no fetch
        }
        synchronized (this) {           // only one thread refreshes
            if (cached == null || System.currentTimeMillis() >= expiresAt) {
                cached = loader.get();  // e.g. SSM/Secrets Manager call
                expiresAt = System.currentTimeMillis() + TTL_MS;
            }
            return cached;
        }
    }
}
```

Two serverless-specific subtleties: (1) because the environment may be **frozen** between invocations, wall-clock TTL is measured across frozen time too — that's fine for config but means the cache could be "older" than expected after a long idle gap; (2) under **SnapStart**, `expiresAt` captured in the snapshot would make every restored instance think the cache is fresh — register an `afterRestore` hook to reset `expiresAt = 0` so each restored environment re-fetches.

#### Q101. [Practical] A function intermittently returns stale data after a deploy. SnapStart is enabled. What's the likely bug?

SnapStart captures the fully initialized JVM at **publish time** into a snapshot, and every cold start restores that frozen memory. Anything computed during INIT and stored in a field is **baked into the snapshot** and shared across all restored environments until you publish a new version.

Likely culprits:

- **Config/secrets fetched in INIT and cached in a static field** — the value frozen at publish time is served indefinitely, even after you rotate the secret or change the parameter. The restored environments never re-fetch.
- **A timestamp or "loaded at" marker** captured at publish, making TTL logic believe the cache is perpetually fresh.
- **A cached clock or random seed** producing identical values across instances.

The fix is the CRaC lifecycle: move freshness-sensitive loads out of the snapshot by re-fetching in `afterRestore`, or reset the cache's expiry to force a reload on first use post-restore. The mental model: **with SnapStart, INIT runs once ever (at publish), not once per cold start** — so treat INIT-time state as immutable and put anything that must be fresh into the restore hook.

#### Q102. [Practical] How would you load-test a serverless API and interpret the results meaningfully?

Serverless load testing has gotchas because the system *itself* scales, so naive tests measure the wrong thing.

Approach:

1. **Warm vs cold separation.** A ramp test reveals the cold-start tail as concurrency grows; a steady-state test (after the system has scaled out) measures warm performance. Report both — a single average hides the bimodal reality.
2. **Drive realistic concurrency, not just RPS.** Lambda's behaviour is governed by *concurrency* (= RPS × duration). Tools like k6, Artillery, or Gatling with a closed-model (fixed virtual users) expose scaling and throttling that an open-model RPS test may mask.
3. **Watch the right metrics.** `ConcurrentExecutions`, `Throttles`, `Duration` percentiles, `IteratorAge` (streams), and **downstream** saturation (DynamoDB throttles, RDS connections) — the function often isn't the bottleneck; the data tier is.
4. **Account-level blast radius.** Because functions share account concurrency, a load test can throttle *other* production functions. Run against an isolated account or use reserved concurrency to fence the test.

Interpretation: a healthy result shows latency rising only via cold-start tail during ramps and flat warm latency at steady state; throttling or rising `IteratorAge` means a downstream or concurrency ceiling, not slow code.

#### Q103. [Coding] Implement graceful shutdown handling to flush buffered telemetry before the environment is frozen/terminated.

Lambda sends a `SIGTERM` to registered extensions / runtime hooks during SHUTDOWN. For buffered metrics or async logs, you want to flush on that signal so data isn't lost when the environment is destroyed.

```java
public class FlushOnShutdown {
    private static final MetricsBuffer BUFFER = new MetricsBuffer();

    static {
        // Registered once during INIT; fires during SHUTDOWN phase.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                BUFFER.flush();          // push remaining metrics/logs
            } catch (Exception e) {
                System.err.println("shutdown flush failed: " + e);
            }
        }));
    }

    public static void record(String metric, double value) {
        BUFFER.add(metric, value);       // buffered across warm invocations
    }
}
```

Caveats to state explicitly: the shutdown hook only runs when Lambda gracefully terminates the environment (idle eviction), it has a **limited time budget** (a couple of seconds), and it is **not** guaranteed on every invocation — so it's a best-effort flush of cross-invocation buffers, not a substitute for flushing critical data within the handler itself. For richer control, register an **external extension** that subscribes to the SHUTDOWN event via the Extensions API.

#### Q104. [Practical] You need zero-downtime schema migration for a DynamoDB-backed serverless service. How do you do it?

Because functions are deployed independently and old/new versions run concurrently during a rollout, you must make schema changes **backward- and forward-compatible** — never a big-bang migration.

The expand/contract (parallel-change) pattern:

1. **Expand** — add the new attribute and have the new code write **both** old and new shapes; keep reading the old. DynamoDB is schemaless so adding attributes needs no DDL.
2. **Migrate** — backfill existing items with a paginated, throttled scan-and-update job (often itself a Lambda + Step Functions Map), writing the new attribute. Throttle it so the backfill doesn't starve live traffic.
3. **Switch reads** — once all items have the new attribute, deploy code that reads the new shape.
4. **Contract** — stop writing the old attribute and (optionally) clean it up.

Each step is independently deployable and reversible, and at no point does a running old version encounter data it can't read. For relational stores the same dance applies but with additive DDL (add nullable column, backfill, switch, drop) and care around long-running migrations vs Lambda's 15-minute limit (use Step Functions or a Fargate task for the backfill).

#### Q105. [Coding] Write a Step Functions Distributed Map driver Lambda that processes one item idempotently and reports a typed result.

Distributed Map invokes your function once per item (or per small batch) at massive parallelism, reading items from S3. The per-item worker must be idempotent and return a clear success/failure the state machine can aggregate.

```java
public class ItemWorker implements RequestHandler<ItemEvent, ItemResult> {

    private static final DynamoDbClient DDB = DynamoDbClient.create();
    private static final String DONE_TABLE = "processed-items";

    @Override
    public ItemResult handleRequest(ItemEvent event, Context ctx) {
        String itemId = event.getId();
        try {
            // Idempotency claim: only the first attempt does the work.
            DDB.putItem(b -> b.tableName(DONE_TABLE)
                .item(Map.of("id", AttributeValue.fromS(itemId)))
                .conditionExpression("attribute_not_exists(id)"));
        } catch (ConditionalCheckFailedException dup) {
            return ItemResult.skipped(itemId);     // already done on a retry
        }

        try {
            doWork(event);                         // the real side effect
            return ItemResult.success(itemId);
        } catch (Exception e) {
            // Roll back the claim so a retry can re-attempt cleanly.
            DDB.deleteItem(b -> b.tableName(DONE_TABLE)
                .key(Map.of("id", AttributeValue.fromS(itemId))));
            throw new RuntimeException("failed " + itemId, e); // → Map records failure
        }
    }
}
```

Distributed Map aggregates per-item results and can tolerate a configurable failure percentage before failing the whole run, writing successes and failures to S3 — so the typed `ItemResult` and the claim/rollback give you exactly-once *effect* across millions of items with built-in fan-in.

#### Q106. [Practical] Costs are higher than expected for a low-traffic function. Where do you look?

Low traffic but high bill almost always means you're paying for something **other than invocations**:

1. **Provisioned concurrency** left enabled — you pay per provisioned-environment-hour 24/7 regardless of traffic. The single most common surprise.
2. **Over-provisioned memory** — a function set to 3 GB but using 200 MB pays ~15× the GB-seconds it needs. Right-size with Lambda Power Tuning.
3. **Long duration from a hung dependency or missing timeout** — a function that should take 200 ms but waits 14 s on a stuck call pays for the full duration on every such invocation.
4. **NAT gateway data charges** for a VPC function — often dwarfs the Lambda compute cost itself; switch to VPC endpoints.
5. **CloudWatch Logs ingestion/storage** — verbose logging (especially `logEvent=true` dumping full payloads) can cost more than the compute. Set log retention and trim noise.
6. **Recursive/looping invocations** — a function that writes to the same S3 bucket/SQS that triggers it creates an invocation loop (Lambda now has recursion detection, but it can still rack up cost before tripping).

The method: break the bill down by line item (Cost Explorer grouped by usage type) rather than assuming "Lambda is cheap" — the compute is often the smallest part.

#### Q107. [Practical] How do you debug a "works for some requests, fails for others" issue where the failure correlates with which environment serves it?

When failures cluster by environment, suspect **per-environment state corruption** — something cached in a warm environment that became invalid or was poisoned.

Likely causes:

- **A captured-but-expired credential or token** in a static field that one environment cached and never refreshed (others got fresh ones).
- **A non-thread-safe object** mutated incorrectly — even though one invocation runs at a time per environment, INIT-time or background-thread mutation can corrupt shared state in just some environments.
- **A cached connection that went stale** (DB/HTTP keep-alive dropped server-side) in some environments but not others; the stale ones fail until recycled.
- **SnapStart** restoring a frozen value (seed, timestamp, connection) that's wrong only in environments that hit the affected code path.

Diagnosis: log the environment's identity. There's no public environment-ID API, but you can generate a UUID in INIT and log it on every invocation, then group failures by that UUID in Logs Insights. If failures concentrate on specific UUIDs, it's per-environment state — fix by adding TTL/refresh to the cached object, validating connections before use (or using a pool that does), and moving freshness-sensitive state into `afterRestore`.

### 🔴 — extended

#### Q108. [Practical] Design the end-to-end troubleshooting runbook for a serverless event-processing pipeline (API GW → SQS → Lambda → DynamoDB → Stream → Lambda) that is "losing events."

"Losing events" in a multi-hop pipeline requires checking each boundary for a silent drop. Build the runbook as a trace from ingress to final effect:

1. **Ingress (API GW → SQS).** Confirm API Gateway actually enqueued: check API GW 4xx/5xx and integration errors. A failed SQS `SendMessage` (permissions, throttling) drops the event at the door. Verify with the SQS `NumberOfMessagesSent` metric vs API request count.
2. **SQS → Lambda.** Check `ApproximateNumberOfMessagesVisible` (growing = consumer can't keep up), `Throttles` on the consumer, and the **DLQ depth**: messages exceeding `maxReceiveCount` land in the DLQ — that's not "lost," it's parked, and an empty/unmonitored DLQ is the classic blind spot. Verify visibility-timeout ≥ 6× function timeout.
3. **Lambda → DynamoDB.** Confirm writes succeed: `ConditionalCheckFailedException` from over-aggressive idempotency guards can silently skip writes that *should* have happened. Check conditional-write logic and DynamoDB throttling.
4. **DynamoDB Stream → Lambda.** Streams are the most common silent-loss point: a **poison record blocks the shard** until records **expire after 24h** — then they're genuinely gone. Watch `IteratorAge`: a climbing iterator age means the consumer is falling behind and approaching data loss. Configure `BisectBatchOnFunctionError` + on-failure destination.
5. **Cross-cutting.** Trace a single event end-to-end with a correlation ID through every hop (X-Ray + structured logs). Reconcile counts at each boundary; the hop where the count drops is your culprit.

The senior framing: in serverless, "lost" events are usually **parked in a DLQ no one watches** or **expired in a blocked stream** — so the runbook is really about instrumenting every queue/stream boundary with depth/age alarms *before* the incident, so loss is impossible to miss.

#### Q109. [Practical] A critical serverless workload must survive a regional outage. Design and walk through the failover, including the hard parts.

Multi-region serverless is mostly about the **stateful** tier and **DNS-driven** failover; the compute is easy to duplicate.

Architecture:

1. **Deploy the stack to two regions** (CDK/SAM with the same template) — functions, API Gateway, Step Functions are stateless and trivially duplicated.
2. **Global data tier** — DynamoDB **Global Tables** (active-active, multi-region replication) so both regions have the data. This is the linchpin and the source of the hard problems.
3. **Routing** — Route 53 with health checks doing failover (active-passive) or latency/weighted routing (active-active). Or a global accelerator for faster failover.

The hard parts (what an interviewer is probing):

- **Write conflicts in active-active Global Tables** — last-writer-wins can silently drop a concurrent write made in the other region during a partition. Design idempotency keys and conflict-tolerant data models, or route writes for a given entity to one region (write-home).
- **Replication lag** — a failover during lag means the passive region is missing the last few seconds of writes; you must decide your acceptable RPO and whether to accept that loss.
- **In-flight workflow state** — Step Functions executions are **regional and don't replicate**; an in-flight order workflow in the failed region is stranded. You need a way to reconstruct or replay it from the durable event log, not from Step Functions state.
- **Idempotency stores must be global** — a request retried against the failover region must see the same idempotency key, so that table must also be a Global Table.
- **Testing** — you must regularly run game-day failover drills; an untested DR plan is a hypothesis, not a capability.

The honest senior answer names the RPO/RTO trade-off explicitly and concedes that true zero-loss active-active is extremely hard; most real systems accept a small RPO and design every side effect to be idempotent so replay after failover is safe.

#### Q110. [Behavioral] You're called into a war room: a serverless system is melting under a traffic surge, downstream databases are throttling, and costs are spiking. As the senior engineer, how do you take control and what do you actually do?

This tests incident leadership, prioritization under pressure, and serverless-specific judgment. Structure the answer as stabilize → diagnose → prevent:

- **Take command calmly and set roles.** Establish an incident commander (maybe you), a scribe, and clear comms; stop everyone from making uncoordinated changes. The first job is to reduce the noise, not to find the root cause.
- **Stabilize by reshaping load, not scaling infinitely.** The counter-intuitive serverless move: **throttle deliberately.** Set or lower **reserved concurrency** on the offending function so it stops out-scaling and hammering the database — sacrificing some throughput to protect the data tier and stop the cascade. If a queue is involved, cap the event source mapping's `maximumConcurrency`. This is the opposite of the instinct to "scale up," and explaining *why* (the DB is the bottleneck, not the compute) is the signal of seniority.
- **Protect the data tier.** Flip DynamoDB to on-demand or raise capacity, ensure RDS Proxy is absorbing connections, shed non-critical traffic (feature-flag off background jobs) to preserve the critical path.
- **Watch the blast radius.** Because functions share account concurrency, fence critical functions with reserved concurrency so the surge doesn't starve unrelated production workloads.
- **Diagnose with data.** Use the metrics (`Throttles`, `ConcurrentExecutions`, DB throttles, `IteratorAge`) to confirm where the bottleneck actually is before changing more things.
- **Communicate trade-offs to stakeholders.** "We are intentionally rate-limiting to protect data integrity; throughput is reduced but the system is stable" — naming the deliberate degradation.
- **After stabilization, prevent recurrence.** Buffer with SQS, add reserved-concurrency governance, set up the depth/age alarms that were missing, and run a blameless postmortem.

The interviewer is checking whether, under pressure, you reach for **deliberate throttling and load-shaping** (the correct serverless reflex) rather than naive scaling, lead the room with composure, and convert the incident into durable guardrails afterward — all while keeping the team functioning rather than thrashing.

## ✅ Key Takeaways

- FaaS = event-driven, stateless, auto-scaling, pay-per-use compute; Lambda's INIT/INVOKE/SHUTDOWN model is the foundation for understanding cold starts.
- Cold starts hit Java hardest; mitigate with **SnapStart**, AWS SDK v2 + lightweight HTTP client, trimmed dependencies, right-sized memory, and (for the most critical paths) provisioned concurrency or GraalVM native images.
- Design for **at-least-once delivery**: idempotency and partial-batch handling turn it into exactly-once *effect*.
- Know the three integration styles (sync / async / poll-based) — their retry and error semantics differ and drive correct design.
- Use **Step Functions** to orchestrate multi-step, long-running, or fan-out/fan-in workflows instead of brittle Lambda-to-Lambda chains.
- Keep business logic provider-agnostic (hexagonal architecture) for testability and to localize vendor lock-in.
- Choose serverless **per workload**: ideal for spiky/event-driven/glue work; containers often win for steady high throughput and stateful or long-running jobs.

## ⚠️ Common Pitfalls

- Initializing SDK clients or DB connections inside the handler instead of during INIT, paying setup cost on every request.
- Storing session/business state in function memory and assuming it persists — warm reuse is never guaranteed.
- Ignoring idempotency and getting duplicate side effects (double charges, duplicate emails) from at-least-once delivery.
- Letting Lambda's unbounded scaling overwhelm a non-elastic dependency (RDS connection exhaustion) — forgetting RDS Proxy / reserved concurrency / SQS buffering.
- Forgetting SnapStart's snapshot freezes per-instance state (random seeds, connections) — not using CRaC hooks to regenerate it.
- Treating SQS/async/stream error handling as identical; missing partial-batch responses or the stream "poison pill" that blocks a shard.
- Assuming serverless is always cheaper — not modeling the crossover point against containers for sustained high load.
- Over-provisioning memory blindly, or under-provisioning a CPU-bound function and paying more in duration than higher memory would cost.

## 📚 Further Reading

- AWS Lambda Developer Guide — execution environment, concurrency, SnapStart.
- AWS Well-Architected Framework — Serverless Applications Lens.
- AWS Lambda Powertools for Java (logging, tracing, metrics, idempotency).
- AWS Step Functions Developer Guide — Standard vs Express, Map/Distributed Map, error handling.
- "Operating Lambda" blog series (AWS Compute Blog) — performance, observability, and cost optimization.
- CRaC (Coordinated Restore at Checkpoint) project documentation for SnapStart internals.
