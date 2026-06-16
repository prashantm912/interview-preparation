# AWS (Amazon Web Services)

A staff-level interview guide to AWS: core compute/storage/networking services, IAM, the Well-Architected Framework, serverless and resilience patterns, cost, and the hard design trade-offs that separate juniors from principals. Knowledge current through 2026.

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

### Q1. [Theory] What are Regions, Availability Zones, and Edge Locations, and why does the distinction matter?

AWS organizes its global footprint into three tiers. A **Region** (e.g. `us-east-1`, `eu-west-1`) is a separate geographic area with its own isolated infrastructure; most services and data are scoped to a single Region, and cross-Region traffic costs money and adds latency. An **Availability Zone (AZ)** is one or more discrete data centers within a Region, with independent power, cooling, and networking, connected to sibling AZs by low-latency private links (single-digit milliseconds). **Edge Locations** are the hundreds of CloudFront/Route 53 points-of-presence used to cache content and terminate connections close to users.

The distinction matters because it defines your fault domains. Deploying across **multiple AZs** protects you from a data-center failure with negligible latency cost — this is the default for production. Deploying across **multiple Regions** protects you from a whole-Region outage and serves global users, but costs far more and forces you to confront data-replication latency and consistency. A senior engineer chooses the smallest fault domain that meets the resilience requirement.

### Q2. [Theory] Explain the difference between EC2, ECS/EKS, Lambda, and Fargate as compute options.

These sit on a spectrum from "you manage everything" to "AWS manages everything." **EC2** gives you virtual machines — you own the OS, patching, scaling, and capacity. **ECS** (Elastic Container Service) and **EKS** (managed Kubernetes) orchestrate containers; you still choose where they run. **Fargate** is a serverless container *launch type* for ECS/EKS — you specify CPU/memory per task and AWS runs the underlying host, so there are no instances to patch. **Lambda** is function-as-a-service: you upload code, AWS runs it on demand and scales to zero, billed per millisecond.

The trade-off is control vs. operational burden. EC2 wins for legacy software, GPU/specialized workloads, and predictable steady-state load (Reserved/Savings Plans make it cheap). Fargate/Lambda win for spiky, event-driven, or unpredictable workloads where you don't want to manage capacity. Lambda has cold starts and a 15-minute execution limit, so it's wrong for long-running or latency-critical synchronous paths.

### Q3. [Theory] What are the main S3 storage classes and when do you use each?

S3 storage classes trade retrieval speed and availability for cost:

```
Class                       Use case                         Retrieval
--------------------------  -------------------------------  -----------------
S3 Standard                 Hot data, frequent access        Instant
S3 Intelligent-Tiering      Unknown/changing access patterns Instant (auto-moves)
S3 Standard-IA              Infrequent but needs fast access Instant (per-GB fee)
S3 One Zone-IA              Re-creatable infrequent data     Instant, single AZ
S3 Glacier Instant Retrieval Archive needing ms access       Instant
S3 Glacier Flexible Retrieval Archive, occasional restore    Minutes to hours
S3 Glacier Deep Archive     Compliance, 7-10 yr retention    Up to 12 hours
```

Use **Standard** for active workloads, **Intelligent-Tiering** when you can't predict access (it auto-moves objects and is now the recommended default for most data), and **Glacier Deep Archive** for cheap long-term compliance storage. Lifecycle policies automate transitions (e.g. Standard → Standard-IA after 30 days → Glacier after 90). Note: S3 now provides **strong read-after-write consistency** for all operations (since Dec 2020) — a common interview trap is candidates who still cite "eventual consistency."

### Q4. [Practical] You need to host a static React website cheaply with HTTPS and a custom domain. How do you do it on AWS?

The canonical pattern is **S3 + CloudFront + Route 53 + ACM**, and it costs cents per month at low traffic.

```
User → Route 53 (DNS) → CloudFront (CDN, TLS) → S3 bucket (private, OAC)
                              │
                       ACM cert (free TLS)
```

Steps: (1) Upload the built assets to a **private** S3 bucket. (2) Put **CloudFront** in front, using **Origin Access Control (OAC)** so the bucket is reachable only through CloudFront — never make the bucket public. (3) Request a free TLS certificate in **ACM** (must be in `us-east-1` for CloudFront). (4) Point **Route 53** at the distribution with an alias record. Configure CloudFront's "default root object" to `index.html` and add a custom error response mapping 403/404 to `/index.html` for client-side routing. Trade-off vs. AWS Amplify Hosting: Amplify is faster to set up with CI/CD built in, but the S3+CloudFront approach gives you more control and is what you'd explain in an interview to show you understand the moving parts.

### Q5. [Theory] What is IAM, and what is the difference between a user, a group, a role, and a policy?

**IAM** (Identity and Access Management) controls *who* can do *what* to *which* resources. A **policy** is a JSON document listing allowed/denied actions on resources (the "what"). A **user** is a long-lived identity for a human or legacy app, with permanent credentials. A **group** is a collection of users that share policies — you attach policies to the group, not each user. A **role** is an identity with *no permanent credentials* that can be *assumed* temporarily; AWS issues short-lived credentials via STS when something assumes it.

The key principle is **least privilege**: grant only the permissions needed. In modern AWS you should heavily prefer **roles** over users — EC2 instances, Lambda functions, ECS tasks, and even your CI pipeline assume roles, so there are no long-lived keys to leak. The single most common real-world breach vector is a hard-coded access key committed to GitHub; roles eliminate that class of bug.

### Q6. [Practical] What is a security group, and how does it differ from a network ACL?

A **security group (SG)** is a *stateful* virtual firewall attached to an ENI (network interface) on an instance/resource. "Stateful" means if you allow inbound traffic, the return traffic is automatically allowed — you don't write a reverse rule. SGs only have *allow* rules (an implicit deny for everything else). A **Network ACL (NACL)** operates at the *subnet* level, is *stateless* (you must allow both directions explicitly), and supports both allow and deny rules evaluated in numbered order.

In practice you do almost all of your access control with security groups and leave NACLs at their permissive default, reaching for NACLs only when you need a coarse subnet-wide deny (e.g. block a malicious IP range). A powerful pattern is **SG referencing**: the database SG allows inbound 5432 *from the app-tier SG* rather than from an IP range, so the rule keeps working as instances scale up and down.

### Q7. [Coding] Write Java code (AWS SDK v2) to upload a file to S3 and generate a time-limited pre-signed download URL.

**Problem:** Securely let a client download a private object for 15 minutes without making the bucket public.

```java
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import java.nio.file.Paths;
import java.time.Duration;

public class S3Demo {

    static final Region REGION = Region.US_EAST_1;
    static final String BUCKET = "my-app-bucket";

    public static void upload(String key, String localPath) {
        // Credentials resolved from the default chain:
        // env vars → ~/.aws → container/instance role. No hard-coded keys.
        try (S3Client s3 = S3Client.builder().region(REGION).build()) {
            s3.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key(key).build(),
                Paths.get(localPath));
        }
    }

    public static String presignedDownloadUrl(String key) {
        try (S3Presigner presigner = S3Presigner.builder().region(REGION).build()) {
            GetObjectRequest get = GetObjectRequest.builder()
                    .bucket(BUCKET).key(key).build();
            GetObjectPresignRequest req = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(15))
                    .getObjectRequest(get)
                    .build();
            return presigner.presignRequest(req).url().toString();
        }
    }
}
```

**Notes / edge cases:** The pre-signed URL inherits the *signer's* permissions, so the signing role must have `s3:GetObject`. The URL stops working after expiry or if the signing credentials are revoked. Never embed AWS keys in client apps — pre-signed URLs are exactly the mechanism that avoids that. For large files, prefer multipart upload via `S3TransferManager`.

---

## 🟡 Intermediate (3–7 yrs)

### Q8. [Theory] Compare RDS and DynamoDB. When would you choose each?

**RDS** is managed *relational* database (MySQL, PostgreSQL, MariaDB, Oracle, SQL Server) — and **Aurora** is AWS's cloud-native MySQL/PostgreSQL-compatible engine with a distributed storage layer. You get ACID transactions, joins, complex queries, and a fixed schema. RDS scales *vertically* (bigger instance) plus read replicas; writes go to a single primary (Aurora can do multi-writer but it's niche). **DynamoDB** is a fully managed *key-value/document* NoSQL store that scales *horizontally* to virtually unlimited throughput with single-digit-millisecond latency, no servers to manage, and on-demand pricing.

Choose **RDS/Aurora** when you have relational data, need transactions and ad-hoc queries, or your team thinks in SQL. Choose **DynamoDB** when you have well-known access patterns, need massive scale and predictable low latency (gaming leaderboards, shopping carts, session stores, IoT), or want true scale-to-zero serverless data. The classic mistake is treating DynamoDB like a relational DB — it punishes you for scans and joins. DynamoDB rewards **single-table design** built backward from your queries.

### Q9. [Theory] Explain VPC architecture: subnets, route tables, IGW, NAT gateway. Draw the topology.

A **VPC** is your private, isolated network in a Region, defined by a CIDR block (e.g. `10.0.0.0/16`). You carve it into **subnets**, each pinned to one AZ. A subnet is *public* if its **route table** sends `0.0.0.0/0` to an **Internet Gateway (IGW)**; otherwise it's *private*. Private instances reach the internet *outbound only* via a **NAT Gateway** sitting in a public subnet (for OS updates, calling external APIs) while remaining unreachable from the internet.

```
                          Internet
                              │
                          [ IGW ]
                              │
   ┌──────────────────────── VPC 10.0.0.0/16 ────────────────────────┐
   │   AZ-a                              AZ-b                          │
   │  ┌─ Public subnet 10.0.1.0/24 ─┐  ┌─ Public subnet 10.0.2.0/24 ─┐│
   │  │  ALB        NAT-GW          │  │  ALB                        ││
   │  └──────┬──────────┬──────────┘  └──────┬─────────────────────┘ │
   │         │          │ (egress)            │                       │
   │  ┌─ Private subnet 10.0.11.0/24 ┐ ┌─ Private subnet 10.0.12.0/24┐│
   │  │  App / EC2 / ECS tasks       │ │  App / EC2 / ECS tasks      ││
   │  └──────────────┬───────────────┘ └─────────────┬──────────────┘│
   │  ┌─ DB subnet 10.0.21.0/24 ──────┐ ┌─ DB subnet 10.0.22.0/24 ───┐│
   │  │  RDS primary                  │ │  RDS standby (Multi-AZ)    ││
   │  └───────────────────────────────┘ └───────────────────────────┘│
   └──────────────────────────────────────────────────────────────────┘
```

The standard production topology is a **3-tier layout across ≥2 AZs**: public subnets for the load balancer and NAT, private subnets for the app tier, and isolated DB subnets with no internet route at all. **VPC endpoints** (Gateway for S3/DynamoDB, Interface for most others) let private resources reach AWS services without a NAT gateway — saving both money and exposure.

### Q10. [Practical] Design auto scaling for a web app whose traffic triples at 9am and drops at night. Walk through your approach.

The goal is to match capacity to demand so you neither pay for idle nor fall over under load.

**Approach:** Put an **Auto Scaling Group (ASG)** behind an **Application Load Balancer (ALB)**, spread across ≥2 AZs. Configure:

1. **Target-tracking scaling** on a metric like average CPU at 50% or, better, `ALBRequestCountPerTarget` — this self-tunes, adding/removing instances to hold the target.
2. **Scheduled scaling** to pre-warm before the 9am spike (set min capacity higher at 8:45am) — reactive scaling alone lags behind a sharp ramp because instance boot + app warm-up takes minutes.
3. **Health checks** set to ELB type so unhealthy instances are replaced.
4. **Warm pools** or a slim AMI/container image to cut boot time.

**Trade-offs:** Scheduled scaling needs maintenance if traffic patterns shift; target-tracking is hands-off but reactive. **What I'd actually do in production:** combine both — scheduled scaling to set the floor ahead of known daily peaks, target-tracking to handle the unpredictable variance on top. Use **Spot Instances** (via a mixed-instances policy) for the bursty top-of-the-curve capacity to cut cost ~70%, keeping On-Demand/Reserved for the steady baseline. Add **predictive scaling** (ML-based) if the pattern is stable enough to forecast.

### Q11. [Theory] When do you use SQS vs. SNS vs. EventBridge vs. Kinesis?

All four decouple producers from consumers, but the semantics differ:

```
SQS         Point-to-point queue. One consumer (group) pulls each msg.
            Buffers work, smooths load. FIFO option for ordering+dedup.
SNS         Pub/sub fan-out. One message → many subscribers (push).
            Pairs with SQS for the "fan-out" pattern.
EventBridge Event bus with content-based routing rules + schema registry.
            Best for event-driven app integration & SaaS/AWS events.
Kinesis     Ordered, replayable streaming. Many consumers read the same
            shard at their own offset. High-throughput analytics/log pipes.
```

Use **SQS** to buffer and reliably process tasks (image processing, order fulfillment) with retries and a dead-letter queue. Use **SNS+SQS** when one event must trigger several independent workflows (the "fan-out" pattern — each subscriber gets its own durable queue). Use **EventBridge** when you want declarative routing rules and decoupled microservices reacting to typed events. Use **Kinesis** (or MSK/Kafka) when you need ordered, *replayable* streams with multiple independent readers and high throughput — SQS can't replay, since a consumed message is gone.

### Q12. [Practical] Your Lambda function intermittently times out calling an RDS database. Diagnose and fix.

**Likely root causes, in order of probability:**

1. **VPC cold-start / no route to RDS.** If the Lambda is in a VPC, confirm it's in subnets that can reach the DB and that the **DB security group allows inbound from the Lambda's SG**. Missing SG rule = silent timeout.
2. **Connection exhaustion.** Each concurrent Lambda invocation opens its own DB connection; a spike to 500 concurrent invocations can blow past RDS `max_connections`. **Fix: put RDS Proxy in front** — it pools and reuses connections, which is *the* canonical Lambda+RDS fix. Also reuse the connection across invocations by declaring it outside the handler.
3. **Timeout misconfiguration.** Lambda timeout shorter than the query, or the JDBC socket timeout too long so the function dies before erroring cleanly.
4. **NAT-dependent egress** for an internet call inside the same function adding latency.

**What I'd do in production:** add **RDS Proxy**, move connection setup to module scope, set a sane JDBC `connectTimeout`/`socketTimeout`, and right-size Lambda memory (more memory = more CPU = faster). If the workload is high-concurrency and DB-bound, I'd question whether Lambda is even the right tool versus a long-lived ECS service with a real connection pool (HikariCP).

### Q13. [Coding] Implement an idempotent SQS message consumer in Java that prevents duplicate processing.

**Problem:** SQS standard queues deliver *at-least-once*, so the same message can arrive twice. Make processing idempotent using DynamoDB conditional writes as a dedup lock.

```java
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.util.Map;

public class IdempotentConsumer {

    private final DynamoDbClient ddb;
    private final String dedupTable; // PK: messageId (TTL attribute set)

    public IdempotentConsumer(DynamoDbClient ddb, String dedupTable) {
        this.ddb = ddb;
        this.dedupTable = dedupTable;
    }

    /** Returns true if this message is new and should be processed. */
    public boolean claim(String messageId, long ttlEpochSeconds) {
        try {
            ddb.putItem(PutItemRequest.builder()
                .tableName(dedupTable)
                .item(Map.of(
                    "messageId", AttributeValue.fromS(messageId),
                    "ttl",       AttributeValue.fromN(Long.toString(ttlEpochSeconds))))
                // Only succeeds if no row with this messageId exists yet.
                .conditionExpression("attribute_not_exists(messageId)")
                .build());
            return true;                       // we won the claim
        } catch (ConditionalCheckFailedException dup) {
            return false;                      // already processed → skip
        }
    }

    public void handle(String messageId, Runnable businessLogic) {
        long ttl = (System.currentTimeMillis() / 1000) + 86_400; // 24h
        if (claim(messageId, ttl)) {
            businessLogic.run();
        } // else: duplicate, silently ack & drop
    }
}
```

**Time/Space:** Each message is O(1) — one conditional `PutItem` plus the business logic. Space is O(N) dedup rows, bounded by the TTL which auto-expires old entries.

**Edge cases:** (1) If `businessLogic` fails *after* the claim succeeds, the message is marked done but not processed — for true exactly-once, claim and process inside a transaction or claim only on success. (2) Use a **FIFO queue** with `MessageDeduplicationId` if AWS-side 5-minute dedup is sufficient and you don't need 24h. (3) Always configure a **dead-letter queue** so poison messages don't loop forever.

### Q14. [Theory] What is Multi-AZ vs. Multi-Region for RDS, and what RPO/RTO does each give you?

**Multi-AZ** RDS maintains a synchronous standby replica in a *different AZ within the same Region*. On primary failure, RDS automatically fails over (60–120s) by flipping the DNS endpoint. Because replication is synchronous, **RPO ≈ 0** (no data loss) and **RTO** is a couple of minutes. It protects against AZ/hardware failure but **not** a Region outage, and the standby does *not* serve reads (read replicas do that).

**Multi-Region** means replicating to another Region — via **Aurora Global Database** (typically <1s replication lag, sub-minute promotion) or cross-Region read replicas. This protects against a full Region outage and reduces global read latency, but replication is asynchronous so you accept a small **RPO > 0** (some in-flight data may be lost on failover), and **RTO** depends on your promotion/cutover automation. The trade-off is cost and complexity: Multi-AZ is a checkbox; Multi-Region is a genuine DR program with runbooks, data-residency considerations, and regular failover testing.

### Q15. [Practical] How do you give an EC2 application access to S3 without storing credentials on the box?

You attach an **IAM role** to the instance via an **instance profile**. The application uses the AWS SDK's default credential provider chain, which automatically fetches short-lived, auto-rotating credentials from the **Instance Metadata Service (IMDS)** — no keys on disk, no keys in env vars, nothing to leak or rotate manually.

```
EC2 instance ── assumes ──► IAM Role (instance profile)
      │                          │ policy: s3:GetObject on arn:...:my-bucket/*
      └─ SDK reads creds from IMDSv2 (token-protected) ──► STS temp creds
```

**Security must-dos:** (1) Enforce **IMDSv2** (token-based, hop-limit 1) to defend against SSRF attacks that abuse IMDSv1 to steal the role's credentials — the Capital One 2019 breach exploited exactly this. (2) Scope the role's policy to specific buckets/prefixes (least privilege). (3) For containers, use ECS task roles / EKS IRSA / Pod Identity, which give each task its own scoped role rather than sharing the node's role. This pattern — workloads assuming roles, never holding static keys — is the single highest-leverage security practice on AWS.

### Q16. [Coding] Using AWS SDK v2 in Java, query DynamoDB for all orders of a customer placed after a given date, efficiently (no table scan).

**Problem:** Table `Orders` with partition key `customerId` and sort key `orderDate` (ISO-8601). Fetch one customer's recent orders without scanning.

```java
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.util.List;
import java.util.Map;

public class OrderQuery {

    private final DynamoDbClient ddb;
    public OrderQuery(DynamoDbClient ddb) { this.ddb = ddb; }

    public List<Map<String, AttributeValue>> recentOrders(String customerId,
                                                          String sinceIso) {
        QueryRequest req = QueryRequest.builder()
            .tableName("Orders")
            // Query (not Scan): targets a single partition + sort-key range.
            .keyConditionExpression("customerId = :c AND orderDate > :d")
            .expressionAttributeValues(Map.of(
                ":c", AttributeValue.fromS(customerId),
                ":d", AttributeValue.fromS(sinceIso)))
            .scanIndexForward(false)   // newest first
            .limit(100)                // page size
            .build();

        return ddb.query(req).items(); // paginate via LastEvaluatedKey if needed
    }
}
```

**Why Query, not Scan:** `Query` reads only the matching partition and uses the sort key for an efficient range scan — cost is proportional to data *returned*. A `Scan` reads the *entire table* and filters after, which is O(table size) in both latency and read-capacity cost. **Time:** O(matching items). **Space:** O(page).

**Edge cases:** Result sets >1MB are paginated — loop on `LastEvaluatedKey`. If you need to query by a non-key attribute (e.g. by product), add a **Global Secondary Index (GSI)** rather than scanning. Watch for **hot partitions**: if one `customerId` is enormously more active, it can throttle — mitigate with write sharding.

### Q17. [Theory] Explain the difference between an ALB, NLB, and the (legacy) Classic Load Balancer.

The **Application Load Balancer (ALB)** operates at **Layer 7 (HTTP/HTTPS)**. It understands paths, hosts, headers, and methods, so it can do **content-based routing** (`/api/*` → service A, `/img/*` → service B), host-based routing, native HTTP/2 and WebSockets, TLS termination, and integrate with WAF and Cognito auth. It's the default for web apps and microservices.

The **Network Load Balancer (NLB)** operates at **Layer 4 (TCP/UDP/TLS)**. It's built for extreme throughput and ultra-low latency, preserves the client source IP, supports static/Elastic IPs, and handles non-HTTP protocols. Use it for high-performance, gaming, IoT, or when you need a fixed IP or to terminate millions of connections. The **Classic Load Balancer** is the deprecated first generation — only mentioned to migrate *off* it. A subtle interview point: ALB target groups can route to IPs, instances, *and Lambda functions*, while NLB excels where you must not pay the L7 processing overhead.

---

## 🟠 Advanced (8–12 yrs)

### Q18. [Theory] Walk through the six pillars of the Well-Architected Framework and a tension between two of them.

The AWS Well-Architected Framework defines six pillars:

```
1. Operational Excellence – run & monitor; IaC, observability, runbooks, game days.
2. Security             – identity, least privilege, encryption, detection, response.
3. Reliability          – recover from failure; multi-AZ, backups, auto-healing.
4. Performance Efficiency– right resources; right service for the job, scale, evolve.
5. Cost Optimization    – pay for what you need; rightsizing, Savings Plans, Spot.
6. Sustainability       – minimize environmental impact; efficient regions/usage.
```

The framework's real value is forcing explicit trade-offs. A classic **tension is Reliability vs. Cost Optimization**: full Multi-Region active-active maximizes reliability but roughly doubles infrastructure cost and adds replication complexity. You resolve it by tying the architecture to a *business* RPO/RTO — a payments ledger justifies multi-Region active-active; an internal analytics dashboard does not, and a warm standby (or even backups + restore) is the cheaper, appropriate answer. Another tension is **Security vs. Operational Excellence** (tight IAM boundaries can slow developers) — solved with guardrails (SCPs, permission boundaries) plus self-service paved roads rather than blanket admin. A staff engineer's job is making these trade-offs *deliberate and documented*, not accidental.

### Q19. [Practical] Design a resilient, cost-aware multi-AZ architecture for a high-traffic e-commerce checkout service. Justify each choice.

**Requirements:** low latency, no data loss on checkout, survive an AZ failure, handle flash sales, keep cost sane.

```
                    Route 53 (latency/health-based)
                              │
                       CloudFront (static + cache)
                              │
                        WAF → ALB (2+ AZs)
                              │
                ┌─────────────┴─────────────┐
            ECS Fargate (AZ-a)         ECS Fargate (AZ-b)   ← stateless, autoscaled
                │                            │
        ┌───────┴────────┐                   │
   Aurora (Multi-AZ writer + reader)   ElastiCache (Redis, Multi-AZ) ← sessions/cart
        │                                     │
   Async: SQS → Lambda/worker → fulfillment, email, inventory (decoupled)
        │
   DynamoDB (idempotency keys, order events)   S3 (invoices, lifecycle→Glacier)
```

**Justification:** Stateless **ECS Fargate** tasks across ≥2 AZs auto-scale on `ALBRequestCountPerTarget`; statelessness means any task can die without losing user state. **Aurora Multi-AZ** gives RPO≈0 for the order-of-record with automatic failover; **ElastiCache Redis** holds session/cart so app tier stays stateless. The actual *checkout write* is made **idempotent** (DynamoDB dedup key) so a retry during failover never double-charges. Heavy/slow work (email, inventory sync, fulfillment) is pushed to **SQS** so a downstream slowdown can't stall checkout — with a **DLQ** for poison messages. **Cost levers:** Fargate Spot for the burst layer, Savings Plans for baseline, S3 lifecycle to Glacier for invoices, CloudFront to offload static traffic. **Resilience extras:** circuit breakers around the payment gateway, and the whole thing defined in **IaC (Terraform/CDK)** so it's reproducible and game-day testable.

### Q20. [Theory] Explain how IAM policy evaluation actually works, including explicit deny, SCPs, permission boundaries, and resource policies.

A request is allowed only if it passes *every* applicable policy layer. The evaluation logic: **an explicit `Deny` anywhere always wins**; otherwise the request needs an explicit `Allow` and no `Deny`; the default is implicit deny.

```
Request → 1. Organizations SCP        (max permissions for the whole account)
          2. Resource-based policy    (e.g. S3 bucket policy, KMS key policy)
          3. Permission boundary       (max permissions for this principal)
          4. Identity-based policy     (attached to user/role/group)
          5. Session policy            (passed when assuming a role)
   ALLOW only if every applicable layer permits AND none denies.
```

**SCPs** (Service Control Policies) set the *ceiling* for an entire account/OU — they grant nothing, they only cap (e.g. "no one in this OU can disable CloudTrail or leave us-east-1"). **Permission boundaries** cap what an *individual* principal can do — used so a team can create roles for their apps without being able to escalate beyond the boundary. **Resource policies** (bucket/KMS/SQS policies) can grant *cross-account* access without role assumption. The intersection model is why a user with `AdministratorAccess` can still be denied: an SCP or a permission boundary above them removed the permission. This layered model is the foundation of multi-account governance.

### Q21. [Practical] A team's AWS bill jumped 40% last month with no traffic increase. How do you investigate and control it?

**Investigate (top-down):** Open **Cost Explorer**, group by *service*, then *usage type*, then *linked account/tag* over the spike window. Common culprits: (1) **NAT Gateway data-processing charges** from chatty cross-AZ or internet-bound traffic — often the silent #1 surprise; (2) **inter-AZ / cross-Region data transfer**; (3) orphaned **EBS volumes, idle RDS, unattached Elastic IPs**; (4) a **misconfigured CloudWatch Logs** retention pumping TB into ingestion; (5) S3 requests from a runaway client or a Glacier early-deletion fee; (6) someone left a large dev cluster running. **CloudTrail** + **Cost Anomaly Detection** pinpoint *who* changed *what* and *when*.

**Control going forward:** enforce a **tagging policy** (cost-allocation tags per team/env) so spend is attributable; set **AWS Budgets** with alerts and actions; use **Compute Optimizer** for rightsizing; add **VPC Gateway Endpoints** for S3/DynamoDB to bypass NAT charges; apply **Savings Plans** to committed baseline; set **S3 lifecycle + Intelligent-Tiering**; and put **Cost Anomaly Detection** on every account. **What I'd actually do:** fix the immediate leak (usually NAT/data-transfer or a forgotten resource), then institute tagging + budgets so the *next* spike is caught in hours, not on the invoice.

### Q22. [Coding] Implement exponential backoff with jitter for AWS API retries in Java, and explain why jitter matters.

**Problem:** Under throttling (`ProvisionedThroughputExceededException`, HTTP 429), naive fixed retries cause a **thundering herd** — all clients retry in lockstep and re-overload the service. Add capped exponential backoff with **full jitter**.

```java
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

public class Retry {

    /** Retries on throttling/5xx with capped exponential backoff + full jitter. */
    public static <T> T withBackoff(Supplier<T> op, int maxAttempts) throws Exception {
        long baseMs = 50, capMs = 20_000;
        Exception last = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                return op.get();
            } catch (RuntimeException e) {
                if (!isRetryable(e) || attempt == maxAttempts - 1) throw e;
                last = e;
                // exp = base * 2^attempt, capped
                long exp = Math.min(capMs, baseMs * (1L << attempt));
                // FULL JITTER: sleep a random value in [0, exp]
                long sleep = ThreadLocalRandom.current().nextLong(exp + 1);
                Thread.sleep(sleep);
            }
        }
        throw last;
    }

    static boolean isRetryable(RuntimeException e) {
        String n = e.getClass().getSimpleName();
        return n.contains("Throttling") || n.contains("ProvisionedThroughput")
            || n.contains("ServiceUnavailable") || n.contains("InternalServerError");
    }
}
```

**Why jitter:** Without randomization, N clients that fail at the same instant all back off by the *same* deterministic interval and retry simultaneously, recreating the overload — a self-synchronizing herd. **Full jitter** spreads retries uniformly across the window, smoothing load and dramatically improving recovery (AWS's own architecture blog quantifies the win). **Time:** worst case O(maxAttempts) with bounded sleep. **Edge cases:** only retry *idempotent* operations (or pair with the idempotency key from Q13); the AWS SDK already does adaptive retries — implement this only for custom flows or to tune the policy. Always cap total time so a caller isn't blocked indefinitely.

### Q23. [Theory] What strategies exist for cross-account access, and which is most secure for a CI/CD pipeline deploying to prod?

Options, from worst to best: (1) **Static IAM user access keys** stored in the CI system — long-lived, leak-prone, the thing breaches are made of. Avoid. (2) **Cross-account role assumption with an external ID** — the prod account has a role trusting the CI account; CI calls `sts:AssumeRole` to get short-lived credentials. Good, but the CI account still needs its own credentials. (3) **OIDC federation** — the CI provider (GitHub Actions, GitLab) is registered as an OIDC identity provider in AWS; the pipeline exchanges a signed, short-lived OIDC token directly for AWS STS credentials. **No long-lived AWS secrets exist anywhere.**

For a CI/CD pipeline, **OIDC federation is the most secure** and is the modern standard. The trust policy can be scoped to a specific repository, branch, and even environment (`sub` claim conditions), so only `main` of `org/repo` can assume the deploy role. Combine with: a dedicated deploy role per environment, **permission boundaries** so the role can't escalate, requiring **manual approval** gates for prod, and **CloudTrail** auditing of every assumed-role action. This eliminates the single biggest cloud-credential risk class.

### Q24. [Practical] How do you implement observability across a microservices platform on AWS, and what would you instrument?

Observability rests on three pillars — **metrics, logs, traces** — plus events. On AWS:

```
Metrics  → CloudWatch (custom + service metrics), or Amazon Managed Prometheus
Logs     → CloudWatch Logs / OpenSearch, structured JSON, correlation IDs
Traces   → AWS X-Ray (or OpenTelemetry → X-Ray/3rd party) for distributed traces
Dashboards/Alerts → CloudWatch dashboards + alarms → SNS → PagerDuty
```

**What I'd instrument:** the **RED method** for every service (Rate, Errors, Duration) and the **USE method** for resources (Utilization, Saturation, Errors). Concretely: per-endpoint p50/p95/p99 latency and error rate, queue depth and message age for SQS, DynamoDB throttle/consumed-capacity, Lambda concurrency/throttles/cold-starts, ALB 5xx and target-response-time, and DB connection counts. **Propagate a correlation/trace ID** through every hop (HTTP headers, SQS message attributes) so a single user request is reconstructable across services via X-Ray. Alert on **SLO burn rate**, not raw thresholds, to cut noise. In production I'd standardize on **OpenTelemetry** for vendor-neutral instrumentation, ship to CloudWatch/X-Ray plus a long-term store, and run **game days** to verify the alarms actually fire and the dashboards answer "is it us or AWS?"

### Q25. [Theory] Explain DynamoDB capacity modes, partitioning, and how to avoid hot partitions.

DynamoDB has two **capacity modes**: **On-Demand** (pay per request, instant scaling, ideal for spiky/unknown load) and **Provisioned** (you set RCU/WCU, cheaper for steady predictable load, with auto-scaling available). Under the hood, data is sharded into **partitions** by a hash of the **partition key**; each physical partition supports up to ~3,000 RCU / 1,000 WCU. Throughput is divided across partitions, so if one partition key is disproportionately hot, you can throttle even while *total* provisioned capacity looks fine — the dreaded **hot partition**.

**Avoidance strategies:** (1) Choose a **high-cardinality partition key** with even access distribution (user_id good; status/country bad). (2) **Write sharding** — append a suffix (`orderId#<0-9>`) to spread a hot key across partitions, querying all shards on read. (3) Use **DAX** (in-memory cache) or ElastiCache for hot read keys. (4) For time-series, partition by composite keys so writes don't all land on "today." **Adaptive capacity** now automatically isolates and boosts a hot partition, mitigating but not eliminating the problem — design still matters. The interview signal is whether you model **backward from access patterns** rather than normalizing as you would in SQL.

---

## 🔴 Expert (15+ yrs)

### Q26. [Theory] Compare disaster-recovery strategies (Backup & Restore, Pilot Light, Warm Standby, Active-Active) on the RPO/RTO/cost axes.

AWS defines four DR strategies along a continuum of recovery speed vs. cost:

```
Strategy          RTO        RPO        Cost      Description
---------------  ---------  ---------  --------  ---------------------------------
Backup & Restore  Hours      Hours     $         Restore from S3/snapshots on demand
Pilot Light       ~10s min   Minutes   $$        Core data replicated; servers off
Warm Standby      Minutes    Seconds   $$$       Scaled-down full stack always running
Active-Active     ~0 (secs)  ~0        $$$$      Both regions serve live traffic
```

**Backup & Restore** keeps only data backed up cross-Region and rebuilds infra (via IaC) during a disaster — cheapest, slowest. **Pilot Light** continuously replicates the database and keeps a minimal core provisioned but idle; you scale up compute on failover. **Warm Standby** runs a fully functional but under-scaled copy that you scale up on cutover. **Active-Active** serves production traffic from multiple Regions simultaneously, so a Region loss is near-transparent — but you must solve cross-Region data consistency (conflict resolution, global tables), which is the genuinely hard part. The senior move is matching the strategy to a *business-justified* RPO/RTO and **regularly testing failover** — an untested DR plan is a fiction. Most real systems land on Pilot Light or Warm Standby; Active-Active is reserved for systems where downtime cost dwarfs the doubled spend.

### Q27. [Practical] You're migrating a 200-service monolith-plus-microservices estate from on-prem to AWS. How do you structure the program?

**Approach:** Start with the **6 R's** assessment per workload — Rehost (lift-and-shift), Replatform (lift-and-tinker, e.g. DB → RDS), Repurchase (move to SaaS), Refactor (re-architect, e.g. → serverless), Retire (kill dead apps), Retain (leave on-prem for now). Don't refactor everything up front — rehost first to stop the bleeding (data-center exit), then refactor the high-value workloads once they're in AWS.

**Foundations first (Landing Zone):** stand up **AWS Organizations** with a multi-account strategy (separate accounts per environment + a security/log-archive account), **SCPs** for guardrails, centralized networking (Transit Gateway / shared VPCs), **IAM Identity Center** for SSO, centralized **CloudTrail** + **Config** + **GuardDuty**, and everything in **IaC**. Use **Control Tower** to bootstrap this. **Migration tooling:** Application Migration Service (MGN) for rehost, DMS + SCT for databases, DataSync for bulk file transfer. **Sequencing:** migrate in waves grouped by dependency, with a **strangler-fig** pattern for the monolith — route slices of traffic to new services behind an ALB/API Gateway while the legacy app shrinks. **Governance:** a Cloud Center of Excellence sets the paved-road patterns; FinOps from day one with tagging and budgets. The biggest failure modes are skipping the landing zone (account sprawl, no guardrails) and trying to refactor before rehosting (analysis paralysis).

### Q28. [Theory] A region-wide AWS service degradation (e.g. an S3 or DynamoDB control-plane event) takes down half your app. What architectural and organizational lessons apply?

Architecturally: (1) **Reduce blast radius via cell-based architecture** — partition users into independent cells so one cell's failure (or one Region's) can't take down everyone; AWS itself builds this way. (2) **Beware hidden single points of failure** — many services historically had control-plane dependencies on `us-east-1`; assume the dependency graph is deeper than your diagram shows, and test it. (3) **Static stability** — design so the system keeps running on its *last known good* state during a control-plane outage (e.g. don't require new EC2 launches or new IAM evaluations to keep serving). (4) **Multi-Region for tier-0** services, with the discipline that the failover path must itself not depend on the failing Region. (5) **Graceful degradation** — shed non-critical features and serve cached/stale data rather than hard-failing.

Organizationally: blameless **post-incident reviews** focused on systemic causes, **chaos engineering / game days** so failover is muscle memory, clear **runbooks**, and SLO/error-budget discipline that *funds* resilience work. The deepest lesson is that resilience is an investment justified by business impact — and that you cannot outsource your availability entirely to a provider; your architecture's assumptions about the provider are *your* responsibility.

### Q29. [Behavioral] Tell me about a time you had to push back on an over-engineered "go multi-region active-active" mandate.

**Situation:** A VP, spooked by a competitor's outage, mandated active-active multi-Region for an internal B2B reporting product used by ~2,000 users during business hours. **Task:** I owned the architecture and had to either deliver it or change the decision. **Action:** Rather than argue in the abstract, I quantified it — active-active would roughly double a ~$180k/yr infra bill, add months of work to solve cross-Region data consistency, and the product's actual SLA was 99.9% with a generous overnight maintenance window. I mapped the *business* RPO/RTO with the product owner (RTO of a few hours was genuinely fine) and presented three options on one slide: Backup & Restore, Pilot Light, Warm Standby — with cost and recovery time for each. I framed it as "what are we buying for the extra $180k?"

**Result:** We chose **Pilot Light** with automated IaC failover and quarterly DR game days — meeting the real requirement at a fraction of the cost and complexity. **Reflection:** The lesson I carry is that senior engineers translate fear-driven mandates into *quantified trade-offs* tied to business value, and that "the most resilient architecture" is rarely "the most resilient architecture you can name." Saying no with data, and offering a calibrated menu, builds more trust than either blind compliance or a flat refusal.

### Q30. [Practical] Design a globally-distributed, low-latency API serving 100M users with strict data-residency (GDPR) requirements. What are the hard parts?

```
        Users (per geography)
              │
        Route 53 (geolocation routing) ───────────────┐
              │                                        │
   ┌──────────┴──────────┐                  ┌──────────┴──────────┐
   EU stack (eu-central-1)                  US stack (us-east-1)
   CloudFront → API GW → Lambda/ECS         CloudFront → API GW → Lambda/ECS
   DynamoDB (EU-only data)                  DynamoDB (US-only data)
   Aurora (EU)                              Aurora (US)
              └─── only NON-personal/aggregated data replicates globally ───┘
```

**Approach:** Use **Route 53 geolocation routing** (or **AWS Global Accelerator** for anycast TCP) to pin each user to a Region in their jurisdiction. Within each Region, edge-cache at **CloudFront**, scale stateless compute, and keep per-user data in that Region. **The hard parts:** (1) **Data residency** — GDPR/data-sovereignty means EU users' personal data must *stay* in the EU, so you can't naively use a single global table for PII. Partition data by jurisdiction; replicate only non-personal/aggregated/config data globally. (2) **Consistency vs. latency** — global strong consistency is physically expensive (speed of light); choose per-data-type (strong for a user's own writes via their home Region; eventual for cross-Region reads). (3) **Identity** — a user roaming across Regions needs auth that works everywhere without moving their PII; use region-scoped data with a global directory of *non-personal* routing metadata. (4) **Right to erasure / auditability** — deletes and access logs must respect residency too. (5) **Failover that respects residency** — DR for an EU Region must fail over to *another EU Region*, not the US.

**What I'd actually do:** treat each jurisdiction as an independent **cell** with its own full stack, keep PII Region-resident, replicate only metadata, document the data-flow map for the DPO, and validate with a privacy/legal review — because here the binding constraint is *legal*, not technical, and an elegant architecture that violates GDPR is a liability, not an asset.

### Q31. [Theory] How do encryption-at-rest and KMS envelope encryption work on AWS, and what are the key-management trade-offs?

Most AWS storage services (S3, EBS, RDS, DynamoDB) encrypt at rest using **KMS** and **envelope encryption**: KMS holds a **Customer Master Key (CMK / KMS key)** that never leaves the HSM-backed service; to encrypt data, the service asks KMS for a **data key**, gets back a plaintext copy (used to encrypt the bytes) plus an encrypted copy (stored next to the ciphertext). To decrypt, the encrypted data key is sent to KMS, which returns the plaintext data key. This means bulk encryption is fast/local while the *root of trust* stays in KMS, and every key use is logged in **CloudTrail**.

**Trade-offs:** (1) **AWS-managed keys** are zero-effort but offer no key policy control or cross-account sharing; **customer-managed keys (CMKs)** give you key policies, rotation control, grants, and cross-account access — at a small monthly cost plus per-call charges. (2) **Automatic annual rotation** vs. manual rotation for compliance regimes that mandate shorter cycles. (3) For the strictest control, **CloudHSM** or **external key stores (XKS)** let you hold keys in your own HSM, trading convenience for sovereignty. (4) Key policies are the *only* thing protecting the key — a misconfigured policy granting broad `kms:Decrypt` undermines all the encryption, so least-privilege on key usage is essential. The deepest control move is **separating the key admin from the data admin** so no single principal can both read data and grant itself decryption.

### Q32. [Coding] Implement a token-bucket rate limiter backed by DynamoDB for distributed API throttling in Java.

**Problem:** Many app instances must enforce a *shared* per-client rate limit (e.g. 100 req/s). A local limiter won't work across instances; use DynamoDB atomic updates as the shared state.

```java
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.util.Map;

public class DistributedTokenBucket {

    private final DynamoDbClient ddb;
    private final String table;        // PK: clientId
    private final long capacity;       // max tokens
    private final double refillPerMs;  // tokens added per millisecond

    public DistributedTokenBucket(DynamoDbClient ddb, String table,
                                  long capacity, double refillPerSecond) {
        this.ddb = ddb; this.table = table;
        this.capacity = capacity; this.refillPerMs = refillPerSecond / 1000.0;
    }

    /** Returns true if a token was granted. Conditional update = atomic, no race. */
    public boolean tryAcquire(String clientId) {
        long now = System.currentTimeMillis();
        // Read current state (one item: tokens + lastRefillMs)
        GetItemResponse cur = ddb.getItem(GetItemRequest.builder()
            .tableName(table).consistentRead(true)
            .key(Map.of("clientId", AttributeValue.fromS(clientId))).build());

        double tokens = capacity;
        long last = now;
        if (cur.hasItem() && !cur.item().isEmpty()) {
            tokens = Double.parseDouble(cur.item().get("tokens").n());
            last   = Long.parseLong(cur.item().get("lastRefillMs").n());
            tokens = Math.min(capacity, tokens + (now - last) * refillPerMs);
        }
        if (tokens < 1.0) return false;          // throttled

        double newTokens = tokens - 1.0;
        try {
            // Optimistic concurrency: only commit if lastRefillMs unchanged.
            ddb.putItem(PutItemRequest.builder().tableName(table)
                .item(Map.of(
                    "clientId",     AttributeValue.fromS(clientId),
                    "tokens",       AttributeValue.fromN(Double.toString(newTokens)),
                    "lastRefillMs", AttributeValue.fromN(Long.toString(now))))
                .conditionExpression(
                    "attribute_not_exists(clientId) OR lastRefillMs = :last")
                .expressionAttributeValues(Map.of(
                    ":last", AttributeValue.fromN(Long.toString(last))))
                .build());
            return true;
        } catch (ConditionalCheckFailedException race) {
            return tryAcquire(clientId);          // lost race → retry once
        }
    }
}
```

**Time/Space:** O(1) DynamoDB ops per request (1 read + 1 conditional write), bounded retries under contention. Space O(clients).

**Edge cases / trade-offs:** (1) Under very high contention the optimistic retry can thrash — for extreme scale prefer **API Gateway usage plans** or a dedicated service like ElastiCache/Redis (`INCR` + TTL) which is cheaper and lower-latency for pure counters. (2) The DynamoDB approach wins when you already use DynamoDB and want durability without new infra. (3) Set a **TTL** so idle clients' rows expire. (4) This is *approximate* under clock skew across instances — acceptable for throttling, not for billing.

### Q33. [Behavioral] Describe how you'd lead the response when a junior engineer's IAM misconfiguration exposes an S3 bucket publicly.

**Situation:** Suppose monitoring (Macie/Config/GuardDuty) flags an S3 bucket made public, exposing customer data, traced to a junior's deploy. **Task:** Contain the incident, protect customers, and protect the engineer — both matter. **Action:** First, **contain** — apply S3 Block Public Access at the account level (stops it bucket-wide instantly), revoke the offending policy, and snapshot CloudTrail/access logs to scope what was *actually* accessed versus merely exposed. Run the **incident process**: incident commander, comms, legal/privacy for breach-notification obligations (GDPR's 72-hour clock if PII), and a clear timeline. I explicitly make it **blameless** — the engineer reports facts without fear, because fear hides information during an incident.

**Result/Reflection:** Post-incident, the fix is *systemic*, not "tell people to be careful": enable **S3 Block Public Access** org-wide by default, add an **SCP** that forbids public buckets, add **IAM Access Analyzer** to catch external exposure pre-merge, and require **policy review in CI** for any resource-policy change. The behavioral signal I'd want to convey: a public bucket is a *system* failure (the platform allowed it), so a staff engineer fixes the guardrail, mentors the engineer through the post-mortem as a learning opportunity, and measures success by "this class of mistake is now impossible," not by who gets blamed. Psychological safety is what makes the *next* incident surface in minutes instead of being hidden.

### Q34. [Theory] What is the difference between API Gateway REST APIs, HTTP APIs, and AppSync, and how do you choose?

**API Gateway REST APIs** are the feature-rich, mature option: request/response transformation (VTL mapping templates), API keys + usage plans, request validation, WAF integration, caching, and fine-grained throttling — at higher per-request cost and latency. **API Gateway HTTP APIs** are the newer, leaner, ~70% cheaper, lower-latency option supporting JWT authorizers and the common proxy-to-Lambda/ECS patterns, but lacking some REST features (no request transformation, no API-key usage plans in the same way). **AppSync** is managed **GraphQL** (and now also supports events/subscriptions), letting clients fetch exactly the fields they need across multiple data sources with real-time subscriptions over WebSockets.

**How to choose:** Default to **HTTP API** for simple, cost-sensitive REST proxies — it's the right answer for most new serverless APIs. Reach for **REST API** when you genuinely need its advanced features (mTLS, request validation/transformation, API-key monetization, edge caching). Choose **AppSync** when clients are diverse (mobile/web) with varied data needs, you want to avoid over/under-fetching, or you need real-time subscriptions — common in mobile backends. The interview signal is *not* defaulting to the heaviest option: many teams reflexively pick REST API and overpay for features they never use.

---

## ✅ Key Takeaways

- **Prefer roles over long-lived keys, everywhere.** EC2 instance profiles, ECS task roles, EKS IRSA/Pod Identity, and OIDC federation for CI eliminate the #1 breach vector — leaked static credentials. Enforce IMDSv2.
- **Design for the smallest fault domain that meets the requirement.** Multi-AZ is the cheap default (RPO≈0, automatic failover); Multi-Region is a real DR program justified by business RPO/RTO, not a reflex.
- **S3 is now strongly consistent** (read-after-write) — don't repeat the outdated "eventual consistency" line.
- **Model DynamoDB backward from access patterns**, use Query not Scan, and watch for hot partitions; treat it as key-value, not a relational DB.
- **Decouple with queues/streams** (SQS/SNS/EventBridge/Kinesis) and make consumers **idempotent** — at-least-once delivery is the norm.
- **The Well-Architected Framework is a trade-off tool**, not a checklist — the six pillars exist to make tensions (cost vs. reliability, security vs. velocity) explicit and documented.
- **FinOps from day one:** tag everything, set budgets and anomaly detection; NAT gateway and data-transfer charges are the usual silent budget killers — use VPC endpoints.
- **Combine scheduled + target-tracking auto scaling**, and use Spot for the burst layer with Savings Plans for the baseline.
- **Resilience is an investment** justified by business impact; an untested DR plan and a security guardrail you don't enforce are both fictions.

## ⚠️ Common Pitfalls

- Making S3 buckets public instead of fronting them with CloudFront + OAC; not enabling account-wide S3 Block Public Access.
- Hard-coding AWS access keys in code, env files, or client apps (and committing them to git).
- Putting Lambda in a VPC without an RDS Proxy and exhausting database connections under concurrency.
- Assuming Multi-AZ protects against a Region outage, or that the standby serves reads (it doesn't — use read replicas).
- Treating DynamoDB like SQL: scanning the table, expecting joins, or picking a low-cardinality partition key (hot partitions).
- Forgetting that an explicit `Deny` (or an SCP / permission boundary) overrides any `Allow` — then being confused why an "admin" is blocked.
- Ignoring NAT Gateway data-processing and cross-AZ data-transfer costs until the invoice arrives.
- Naive fixed-interval retries that create a thundering herd under throttling — always use exponential backoff *with jitter*.
- Defaulting to the heaviest service (REST API, Multi-Region active-active) when a cheaper, simpler option meets the actual requirement.
- Replicating PII into a global DynamoDB table without considering GDPR/data-residency.

## 📚 Further Reading

- **AWS Well-Architected Framework** (whitepaper + Tool) — the canonical reference for the six pillars and the workload-review process.
- **AWS Builders' Library** (`aws.amazon.com/builders-library`) — engineering essays on timeouts/retries/jitter, static stability, and cell-based architecture, written by AWS principal engineers.
- **"AWS Certified Solutions Architect Study Guide"** (Sybex) and the **SA-Pro exam guide** — broad, well-structured coverage of service trade-offs.
- **"The Good Parts of AWS"** by Daniel Vassallo & Josh Pschorr — opinionated, pragmatic guidance on which services to actually reach for.
- **AWS Security Best Practices** & **IAM documentation** — least privilege, policy evaluation logic, and credential management.
- **DynamoDB documentation: "NoSQL Design" and Rick Houlihan's re:Invent single-table-design talks** — essential for getting data modeling right.
