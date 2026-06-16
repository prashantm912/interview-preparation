# Microsoft Azure

A practical, interview-focused guide to Microsoft Azure — its core services, identity model, networking, IaC tooling, scaling, and DevOps practices — with Java examples and AWS comparisons. Knowledge current through 2026.

[← Back to master index](../README.md)

## Table of Contents

- [🟢 Basic (0–2 yrs)](#-basic-02-yrs)
- [🟡 Intermediate (3–7 yrs)](#-intermediate-37-yrs)
- [🟠 Advanced (8–12 yrs)](#-advanced-812-yrs)
- [🔴 Expert (15+ yrs)](#-expert-15-yrs)

---

## 🟢 Basic (0–2 yrs)

### Q1. [Theory] What is the Azure resource hierarchy, and why do resource groups matter?

Azure organizes everything into a four-level hierarchy: **Management Groups → Subscriptions → Resource Groups → Resources**. A *resource* is a single deployable thing (a VM, a storage account, a database). A *resource group* (RG) is a logical container for resources that share a lifecycle, region of management metadata, and access/policy boundary. A *subscription* is the unit of billing and quota, and *management groups* let you apply policy and RBAC across many subscriptions in an enterprise.

Resource groups matter because they are the natural blast-radius and lifecycle boundary: you can delete an RG to tear down an entire environment, apply Azure Policy at the RG scope, and assign RBAC roles once for everything inside. The trade-off is that a resource lives in exactly one RG and cannot span groups, so you must plan grouping by lifecycle (e.g., "all the dev web tier") rather than by accident.

```
Management Group (Contoso)
└── Subscription (Prod)
    ├── Resource Group (rg-payments-prod)
    │   ├── App Service Plan
    │   ├── Azure SQL Database
    │   └── Storage Account
    └── Resource Group (rg-network-prod)
        ├── Virtual Network
        └── Application Gateway
```

### Q2. [Theory] Compare Azure Blob Storage tiers and when you'd use each.

Azure Blob Storage offers **Hot, Cool, Cold, and Archive** access tiers within a general-purpose v2 storage account. Hot has the highest storage cost but lowest access cost — use it for actively served data (web assets, current logs). Cool (min 30 days) and Cold (min 90 days) progressively lower storage cost while raising read cost — good for backups and infrequently read data. Archive is offline, cheapest to store but requires a rehydration step (hours) and is for long-term compliance retention.

The "why" is a cost-vs-latency trade-off: you pay less to keep data the longer you commit to leaving it alone, but more to read it. **Lifecycle management policies** can auto-transition blobs (e.g., Hot → Cool after 30 days → Archive after 180). The AWS analog is S3 with its Standard / Infrequent Access / Glacier tiers.

### Q3. [Practical] You need to host a stateless Java Spring Boot REST API with minimal ops overhead. Which compute service and why?

For a stateless Spring Boot API where you want minimal operations, **Azure App Service** (Web App for Linux) is the default sweet spot: it gives you managed HTTPS, autoscale, deployment slots, and built-in CI/CD without managing OS patching or a cluster. You deploy the runnable JAR and Azure handles the platform.

```
Browser → App Service (HTTPS) → Spring Boot JAR
            │  autoscale rules
            │  deployment slots (blue/green)
            └─ Managed Identity → Azure SQL (no passwords)
```

If you later need fine-grained scaling-to-zero or event-driven bursts, **Azure Container Apps** (built on KEDA) is the modern container-native step up; **AKS** is overkill until you need multi-service orchestration, custom networking, or operator-style workloads. In production I'd start on App Service, wire a **system-assigned managed identity** to reach Azure SQL passwordlessly, and only graduate to Container Apps/AKS when scaling or packaging needs demand it.

### Q4. [Theory] What is Azure AD / Microsoft Entra ID, and how does it differ from on-prem Active Directory?

**Microsoft Entra ID** (formerly Azure Active Directory) is Azure's cloud identity and access management service. It authenticates users and applications and issues OAuth 2.0 / OpenID Connect tokens for accessing Microsoft 365, the Azure control plane, and your own apps. On-prem **Active Directory** (AD DS) is a different beast — it is a directory using LDAP and Kerberos/NTLM for domain-joined Windows machines and group policy.

The key difference: Entra ID is an internet-facing, REST/token-based IdP built for SaaS and cloud apps; AD DS is a LAN-oriented directory for domain authentication. They are not the same protocol stack. Many enterprises sync identities from AD DS to Entra ID via **Entra Connect** to get single sign-on across both worlds. The AWS counterpart to Entra ID's control-plane role is IAM combined with IAM Identity Center.

### Q5. [Coding] Write Java code that authenticates to Azure Blob Storage using a managed identity (no secrets) and uploads a file.

Using a managed identity means **no connection strings or keys** in code or config — `DefaultAzureCredential` discovers the identity from the runtime environment (managed identity in Azure, your `az login` locally).

```java
import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;

public class BlobUploader {

    public void upload(String accountName, String container,
                       String blobName, String filePath) {
        // Discovers credential: env vars -> managed identity -> az CLI, etc.
        DefaultAzureCredential credential =
            new DefaultAzureCredentialBuilder().build();

        BlobServiceClient service = new BlobServiceClientBuilder()
            .endpoint("https://" + accountName + ".blob.core.windows.net")
            .credential(credential)
            .buildClient();

        BlobContainerClient containerClient =
            service.getBlobContainerClient(container);
        if (!containerClient.exists()) {
            containerClient.create();
        }

        BlobClient blob = containerClient.getBlobClient(blobName);
        blob.uploadFromFile(filePath, /* overwrite */ true);
        System.out.println("Uploaded " + blobName);
    }
}
```

**Edge cases:** the identity must be granted the `Storage Blob Data Contributor` RBAC role on the account (control-plane Owner is *not* enough for data-plane writes); `uploadFromFile` without overwrite throws if the blob exists; large files should use `BlobClient.uploadWithResponse` with parallel block options. **Time/Space:** upload is O(n) in file size, streamed in blocks so memory is O(block size), not O(file). **Security note:** managed identity eliminates the most common Azure breach vector — leaked storage account keys committed to source control.

---

## 🟡 Intermediate (3–7 yrs)

### Q6. [Theory] Compare Azure SQL Database and Cosmos DB. When do you pick each?

**Azure SQL Database** is a managed relational engine (the SQL Server engine) offering strong ACID transactions, complex joins, and T-SQL. It scales vertically (DTU/vCore) and horizontally via elastic pools, sharding, or Hyperscale (which separates compute from a distributed storage layer for up to 100 TB). Choose it when you have relational data, need transactional integrity, and your access patterns include ad-hoc queries and joins.

**Cosmos DB** is a globally distributed, multi-model NoSQL database with single-digit-millisecond latency and turnkey multi-region writes. Its defining feature is **five tunable consistency levels** (Strong, Bounded Staleness, Session, Consistent Prefix, Eventual), letting you trade consistency for latency/availability per the CAP/PACELC theorem. Throughput is provisioned as **Request Units (RU/s)**, and the **partition key** choice is the single most important design decision — a poor key creates "hot partitions" that throttle.

```
                 ACID joins, T-SQL          Global low-latency, NoSQL
                 ┌──────────────┐           ┌──────────────────────┐
relational  ───► │  Azure SQL   │           │      Cosmos DB        │ ◄─── key-value,
OLTP             │  Hyperscale  │           │ 5 consistency levels  │      document,
                 └──────────────┘           │ RU/s, partition key   │      graph, columnar
                                            └──────────────────────┘
```

Rule of thumb: relational + joins + transactions → Azure SQL; planet-scale + flexible schema + tunable latency → Cosmos DB.

### Q7. [Practical] Your App Service is hitting CPU limits during a daily traffic spike. Walk through how you'd scale it.

First I'd distinguish **scale up** (bigger SKU — more CPU/RAM per instance) from **scale out** (more instances behind the built-in load balancer). For a daily, predictable spike, scale-out is usually right because it adds capacity elastically and survives instance failures.

Approach in production:
1. Confirm the workload is **stateless** (session state externalized to Redis/Cosmos) so any instance can serve any request — a prerequisite for safe scale-out.
2. Configure **Autoscale rules** on the App Service Plan: scale out when avg CPU > 70% for 10 min, scale in when < 30%, with min/max bounds to cap cost.
3. Add a **scheduled profile** for the known spike window so capacity is pre-warmed *before* the surge rather than reacting late (reactive autoscale lags the spike by minutes).
4. Watch for downstream bottlenecks — if Azure SQL is the real limit, scaling web instances just moves the queue. Add connection pooling and consider read replicas.

Trade-off: scheduled scaling wastes money if the spike doesn't come; metric-based scaling is cheaper but reacts late. I'd combine both — schedule the baseline, let metrics handle the unexpected.

### Q8. [Theory] Explain Service Bus vs Event Hubs vs Event Grid. They all "move messages" — what's the difference?

These three solve different messaging problems:

- **Service Bus** is an enterprise message broker for **commands and transactions** — ordered queues, topics/subscriptions (pub/sub), sessions (FIFO per session), dead-letter queues, duplicate detection, and transactional exactly-once-ish semantics. Use it for "process this order" where each message matters and you need reliable, ordered delivery. Throughput is moderate (thousands/sec).
- **Event Hubs** is a high-throughput **streaming ingestion** pipeline (millions of events/sec) — think telemetry, clickstreams, IoT. Consumers read from partitions by offset (Kafka-like; it even has a Kafka-compatible endpoint). Events are retained for a window and replayable.
- **Event Grid** is a lightweight **reactive event router** for discrete events ("a blob was created", "a resource changed") using push delivery to webhooks/functions, with built-in retry. It is for event-driven glue, not bulk data movement.

```
Commands / ordered   ┌─────────────┐
work items     ─────►│ Service Bus │ queue, topics, DLQ, sessions
                     └─────────────┘
High-volume    ┌──────────────┐
telemetry  ───►│  Event Hubs  │ partitions, offsets, replay (Kafka API)
streaming      └──────────────┘
Discrete       ┌─────────────┐
"X happened" ─►│ Event Grid  │ push routing → Functions/webhooks
notifications  └─────────────┘
```

### Q9. [Coding] Write an Azure Function (Java) triggered by a Service Bus queue that processes an order message.

Azure Functions for Java use the `@FunctionName` annotation and binding annotations. This consumer is triggered per message and uses idempotent processing.

```java
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.ServiceBusQueueTrigger;

public class OrderProcessor {

    @FunctionName("ProcessOrder")
    public void run(
            @ServiceBusQueueTrigger(
                name = "msg",
                queueName = "orders",
                connection = "ServiceBusConnection") String message,
            final ExecutionContext context) {

        context.getLogger().info("Received order: " + message);
        try {
            Order order = parse(message);          // deserialize JSON
            if (alreadyProcessed(order.getId())) { // idempotency guard
                context.getLogger().info("Duplicate, skipping " + order.getId());
                return;
            }
            persist(order);                        // write to Azure SQL/Cosmos
            markProcessed(order.getId());
        } catch (Exception e) {
            // Throwing lets the runtime retry; after maxDeliveryCount
            // the message auto-moves to the dead-letter queue.
            context.getLogger().severe("Processing failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private Order parse(String json) { /* Jackson */ return new Order(); }
    private boolean alreadyProcessed(String id) { return false; }
    private void persist(Order o) { }
    private void markProcessed(String id) { }
    static class Order { String getId() { return "o1"; } }
}
```

**Why this shape:** the runtime completes the message only on a clean return; an exception triggers redelivery up to `maxDeliveryCount`, then dead-letters it — so you must make `persist` **idempotent** because Service Bus guarantees at-least-once, not exactly-once. **Edge cases:** poison messages (always fail) end up in the DLQ for manual inspection; large payloads should be passed by reference (store the blob, send the URL). **Complexity:** O(1) per message excluding the persistence call.

### Q10. [Practical] How would you design a Java microservice on AKS to reach Azure SQL without storing any password?

The production pattern is **Microsoft Entra Workload Identity** federated with a Kubernetes service account, plus an Entra-authenticated SQL connection:

1. Enable the OIDC issuer and workload identity on the AKS cluster.
2. Create a **user-assigned managed identity**, grant it a contained-database user in Azure SQL (`CREATE USER [mi-name] FROM EXTERNAL PROVIDER`) with least-privilege roles.
3. Federate that managed identity with the pod's Kubernetes service account (no secret exchanged — the pod presents its OIDC token, Entra returns an access token).
4. In the JDBC connection string use `Authentication=ActiveDirectoryMSI` (or `DefaultAzureCredential` via the MSAL/Azure SDK) so the driver fetches a token automatically.

```
Pod (SA) ──OIDC token──► Entra ID ──access token──► Azure SQL
   │                                                    ▲
   └── JDBC: Authentication=ActiveDirectoryMSI ─────────┘  (no password)
```

Trade-offs: workload identity is the current best practice (the older AAD Pod Identity is deprecated); it requires cluster setup but removes secret rotation entirely. The fallback — secrets in **Azure Key Vault** pulled via the CSI Secrets Store driver — still beats hardcoding but reintroduces a secret to rotate. I'd choose workload identity in production for the zero-secret posture.

### Q11. [Theory] What is the difference between Azure Load Balancer, Application Gateway, and Front Door?

These operate at different network layers and scopes:

- **Azure Load Balancer** is a **Layer 4 (TCP/UDP)** regional load balancer. It distributes flows by hashing 5-tuples, is extremely high-performance, but is protocol-agnostic — it does not understand HTTP, URLs, or cookies.
- **Application Gateway** is a **Layer 7 (HTTP/HTTPS)** regional load balancer with URL-path-based routing, cookie-based session affinity, SSL termination, and an optional **Web Application Firewall (WAF)**. Use it for web traffic routing within a region.
- **Front Door** is a **global, Layer 7** entry point combining a CDN, global anycast routing, SSL offload, WAF, and health-based failover across regions. Use it as the internet-facing front for a multi-region app.

A common topology stacks them: **Front Door (global) → Application Gateway (regional L7 + WAF) → Load Balancer / AKS service (L4) → pods.** AWS analogs: Load Balancer ≈ NLB, App Gateway ≈ ALB, Front Door ≈ CloudFront + Global Accelerator.

### Q12. [Theory] ARM templates vs Bicep — what changed and why does it matter?

**ARM (Azure Resource Manager) templates** are the original JSON-based declarative IaC for Azure. They are verbose, hard to read, and notoriously fiddly with expressions and dependencies. **Bicep** is a domain-specific language that **transpiles to ARM JSON** — same engine and idempotent deployment, but with clean syntax, automatic dependency inference, modules, type safety, and no JSON boilerplate.

Why it matters: Bicep is now Microsoft's recommended authoring experience because it dramatically improves readability and maintainability while remaining a 1:1 representation of ARM (you can decompile JSON to Bicep and back). Both are *declarative* and *idempotent* — re-running converges to the desired state. The main alternative is **Terraform**, which is multi-cloud and has a mature state model but is third-party; teams standardizing only on Azure often prefer Bicep, while multi-cloud shops prefer Terraform.

```bicep
param location string = resourceGroup().location

resource sa 'Microsoft.Storage/storageAccounts@2023-05-01' = {
  name: 'st${uniqueString(resourceGroup().id)}'
  location: location
  sku: { name: 'Standard_LRS' }
  kind: 'StorageV2'
  properties: { minimumTlsVersion: 'TLS1_2', allowBlobPublicAccess: false }
}
```

### Q13. [Practical] Map the Azure services to their AWS equivalents for a colleague migrating from AWS.

| Capability | Azure | AWS |
|---|---|---|
| VMs | Virtual Machines | EC2 |
| Object storage | Blob Storage | S3 |
| Managed relational DB | Azure SQL Database | RDS / Aurora |
| Global NoSQL | Cosmos DB | DynamoDB |
| Serverless functions | Azure Functions | Lambda |
| PaaS web hosting | App Service | Elastic Beanstalk / App Runner |
| Managed Kubernetes | AKS | EKS |
| Container serverless | Container Apps | App Runner / ECS Fargate |
| Virtual network | VNet | VPC |
| L7 load balancer | Application Gateway | ALB |
| L4 load balancer | Load Balancer | NLB |
| Global CDN/entry | Front Door | CloudFront + Global Accelerator |
| Message broker | Service Bus | SQS / SNS |
| Event streaming | Event Hubs | Kinesis / MSK |
| Identity/IAM | Entra ID + RBAC | IAM + Identity Center |
| Secrets | Key Vault | Secrets Manager / KMS |
| IaC | Bicep / ARM | CloudFormation |

The mental-model difference: Azure's permission model is **RBAC role assignments scoped to the resource hierarchy** (MG/Sub/RG/Resource), whereas AWS uses JSON IAM policies attached to principals. Azure billing/quota lives at the *subscription*; AWS at the *account*.

---

## 🟠 Advanced (8–12 yrs)

### Q14. [Theory] Explain Cosmos DB consistency levels and the latency/availability trade-offs of each.

Cosmos DB exposes five consistency levels, forming a spectrum from strongest to weakest guarantees:

1. **Strong** — linearizable; a read sees the latest committed write. Requires synchronous quorum, so it has the highest latency and **cannot be used with multi-region writes** (it would mean a global synchronous commit). Highest read cost in RU.
2. **Bounded Staleness** — reads lag writes by at most K versions or T seconds; you bound the staleness window. Good for global apps needing near-strong guarantees with better availability.
3. **Session** (default) — strong consistency *within a client session* (read-your-writes, monotonic reads) via a session token, but eventual across sessions. Best balance for most apps.
4. **Consistent Prefix** — reads never see out-of-order writes (no gaps), but may be stale.
5. **Eventual** — lowest latency and cost, no ordering guarantee; replicas converge over time.

```
Strong ──► Bounded ──► Session ──► Consistent Prefix ──► Eventual
 high latency / cost                              low latency / cost
 strongest guarantee                              weakest guarantee
```

The PACELC framing: under a partition you trade availability vs consistency; *else* (normal operation) you trade latency vs consistency. Cosmos lets you set this per-request, so a checkout read can use Strong while a product-catalog read uses Eventual.

### Q15. [Practical] Design a multi-region, active-active e-commerce backend on Azure. What components and failure modes?

```
        ┌───────────── Azure Front Door (global anycast + WAF) ─────────────┐
        │                                                                   │
   Region: East US                                          Region: West Europe
   ┌──────────────────┐                                   ┌──────────────────┐
   │ App Gateway (WAF) │                                   │ App Gateway (WAF) │
   │  → AKS / App Svc  │                                   │  → AKS / App Svc  │
   │  → Redis (cache)  │                                   │  → Redis (cache)  │
   └────────┬─────────┘                                   └────────┬─────────┘
            │                  Cosmos DB (multi-region writes)      │
            └──────────────►  Session consistency, conflict policy ◄┘
                              Event Hubs (geo) for order events
```

Design choices and the *why*:
- **Front Door** does global health-probed routing and instant failover; it terminates TLS at the edge and applies WAF rules close to users.
- **Cosmos DB with multi-region writes** gives low write latency in each region; pick **Session** consistency and define a **conflict-resolution policy** (last-writer-wins on a timestamp, or a custom stored procedure for inventory).
- **Stateless app tier** with session/cart state in **Azure Cache for Redis** (active geo-replication) so any region can serve any user.
- **Event Hubs** geo-disaster-recovery pairs for the order event stream.

Failure modes to plan for: (a) a single region outage → Front Door reroutes, Cosmos serves locally — the app keeps running; (b) **write conflicts** in active-active (two regions decrement the same inventory) — this is the hard one; resolve with a per-item conflict policy or move inventory decrements to a single authoritative region/queue; (c) **split-brain** during a network partition — bounded staleness limits divergence but you must accept eventual reconciliation. The trade-off is the classic CAP tension: true active-active write availability means you cannot also have Strong global consistency.

### Q16. [Theory] How do managed identities actually work under the hood, and what are system-assigned vs user-assigned?

A **managed identity** is a service principal in Entra ID whose credential lifecycle Azure manages for you — you never see or rotate a secret. Under the hood, on a VM/App Service/AKS node there is an **Instance Metadata Service (IMDS)** endpoint (`http://169.254.169.254/metadata/identity/...`) reachable only from inside the resource. When your code (via `DefaultAzureCredential` or `ManagedIdentityCredential`) requests a token, the runtime calls IMDS, which exchanges the identity for an OAuth2 access token scoped to a resource (e.g., `https://database.windows.net`). The token, not a password, authenticates the call.

- **System-assigned**: tied 1:1 to a single resource; created and deleted with it. Simple, but the identity dies with the resource and cannot be shared.
- **User-assigned**: a standalone resource you create once and attach to many resources (a fleet of VMs, several Functions). Better for shared permissions and stable identity across redeploys.

**Security implication:** IMDS is the trust anchor, so SSRF vulnerabilities that let an attacker make the app fetch `169.254.169.254` are dangerous — they can exfiltrate a token. Mitigate with IMDS v2-style protections, egress controls, and least-privilege role assignments so a stolen token is low-value.

### Q17. [Practical] Design an Azure DevOps / GitHub Actions pipeline to build and deploy the Java app to AKS with security gates.

A solid multi-stage pipeline (Azure Pipelines YAML shown conceptually) separates build, scan, and deploy with approvals:

```
┌── Stage: Build ──┐   ┌── Stage: Scan ──┐   ┌── Stage: Deploy ──────────────┐
│ mvn verify       │   │ SAST (CodeQL)   │   │ env: staging (auto)           │
│ unit tests       │──►│ dependency scan │──►│   helm upgrade --install      │
│ docker build     │   │ container scan  │   │ env: prod (manual approval +  │
│ push to ACR      │   │ (Trivy/Defender)│   │   gate: no high CVEs)         │
└──────────────────┘   └─────────────────┘   └───────────────────────────────┘
```

Production specifics:
- **Build** with Maven, produce a versioned container, push to **Azure Container Registry (ACR)** tagged with the commit SHA (immutable tags, never `latest` in prod).
- **Security gates**: SAST, SCA dependency scanning, and **image scanning** (Microsoft Defender for Containers or Trivy) that *fails* the build on high/critical CVEs. Sign images with Notation/cosign and enforce admission with policy.
- **Service connection** uses **workload identity federation** (OIDC) to AKS/ACR — no stored cloud credentials in the pipeline.
- **Environments & approvals**: staging deploys automatically; prod requires a manual approval and an automated gate (e.g., query App Insights error rate before promoting). Use **Helm** or GitOps (Flux/Argo) for the actual rollout, with canary or blue/green.

Trade-off: heavier gates slow delivery but are non-negotiable for regulated workloads; for low-risk internal tools I'd relax manual approvals and rely on automated gates plus fast rollback.

### Q18. [Coding] Implement an idempotent retry with exponential backoff for an Azure SDK call in Java.

Transient throttling (HTTP 429 from Cosmos DB / Storage) is common at scale. While the SDKs have built-in retry, interviewers often want you to demonstrate the pattern explicitly.

```java
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

public final class Retry {

    /** Retries a transient operation with exponential backoff + jitter. */
    public static <T> T withBackoff(Callable<T> op, int maxAttempts,
                                    long baseDelayMs) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return op.call();                  // success exits immediately
            } catch (Exception e) {
                last = e;
                if (!isTransient(e) || attempt == maxAttempts) {
                    throw e;                       // non-retryable or exhausted
                }
                long expo = baseDelayMs * (1L << (attempt - 1)); // 2^(n-1)
                long jitter = ThreadLocalRandom.current().nextLong(baseDelayMs);
                Thread.sleep(Math.min(expo + jitter, 30_000L));  // cap at 30s
            }
        }
        throw last;
    }

    private static boolean isTransient(Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        return m.contains("429") || m.contains("503") || m.contains("timeout");
    }
}
```

**Why exponential + jitter:** fixed retries cause a "thundering herd" where all clients retry in lockstep and re-overload the service; randomized jitter spreads them out. **Edge cases:** only retry *idempotent* or de-duplicated operations (a non-idempotent POST could double-charge a customer); always cap total delay; respect a `Retry-After` header if the service sends one. **Time:** worst case O(maxAttempts) calls with sleep dominated by the backoff sum; **Space:** O(1).

### Q19. [Theory] How does AKS networking work — kubenet vs Azure CNI — and what about ingress and egress?

AKS supports two main networking models:

- **kubenet** assigns pods IPs from a *separate* internal CIDR (not the VNet); node-level NAT and route tables forward traffic. It conserves VNet IP space (only nodes get VNet IPs) but adds a hop, limits some features, and complicates connectivity to other VNet resources.
- **Azure CNI** gives every pod a *real VNet IP*, so pods are first-class network citizens — directly routable, compatible with network policies, private endpoints, and peered VNets. The cost is rapid IP exhaustion; **Azure CNI Overlay** mitigates this by using an overlay address space for pods while keeping CNI's feature set.

For ingress, an **Ingress Controller** (NGINX, or the **Application Gateway Ingress Controller / AGIC**) terminates HTTP and routes to services. For egress, by default pods share the cluster's outbound IP; production clusters route egress through **Azure Firewall or a NAT Gateway** for stable source IPs, logging, and FQDN-based allow-listing. **Network Policies** (Azure or Calico) enforce pod-to-pod segmentation — essential for zero-trust inside the cluster.

```
Internet → Front Door → App Gateway (AGIC ingress) → ClusterIP Service → Pods
Pods → NAT Gateway / Azure Firewall → Internet (stable egress IP, FQDN rules)
```

---

## 🔴 Expert (15+ yrs)

### Q20. [Theory] Design the landing-zone / governance model for a 2,000-engineer enterprise adopting Azure.

An **Azure Landing Zone** (the Cloud Adoption Framework pattern) is the foundational, opinionated environment that scales governance, security, and networking before workloads arrive. The structure:

- **Management group hierarchy**: a root MG, then platform MGs (Identity, Management, Connectivity) and landing-zone MGs (Corp, Online), with **Azure Policy** assigned at MG scope so guardrails inherit downward (e.g., "deny public IPs", "require encryption", "allowed regions").
- **Subscription democratization**: each team/workload gets its own subscription as a billing and blast-radius boundary, vended through an automated pipeline rather than manual portal clicks.
- **Hub-and-spoke networking**: a central connectivity hub (Azure Firewall, ExpressRoute/VPN gateways, DNS) peered to spoke VNets per workload.
- **Identity & access**: Entra ID with PIM (Privileged Identity Management) for just-in-time elevation, RBAC custom roles, and Conditional Access policies.
- **Cost & observability**: centralized Log Analytics, Defender for Cloud, and cost management with budgets/tags enforced by policy.

The "why": guardrails-not-gates — policy-as-code lets thousands of engineers self-serve within safe boundaries, instead of a central team bottlenecking every deployment. The trade-off is upfront investment and the risk of over-restrictive policy that engineers route around; you manage this by treating policy as a product with exception workflows.

### Q21. [Behavioral] Tell me about a time you led a costly Azure migration or architecture decision that went wrong, and how you recovered.

*(Use a STAR structure.)* **Situation:** We lifted-and-shifted a monolith onto oversized Azure VMs to hit a deadline, and the monthly bill came in roughly 3x the on-prem run rate. **Task:** As the lead, I owned both the cost overrun and the credibility of the cloud program with finance. **Action:** I instituted a FinOps review: tagged every resource by team and environment, used Azure Cost Management and Advisor to find the waste (idle VMs, over-provisioned premium disks, no reserved instances). We right-sized VMs, moved stateless tiers to App Service with autoscale, bought **reservations/savings plans** for steady workloads, and put dev/test on auto-shutdown schedules. I also set budget alerts and a policy denying SKUs above a threshold without approval. **Result:** We cut the bill ~55% within two quarters and, more importantly, established a cost-accountability culture.

The leadership lesson I emphasize in interviews: the original mistake was optimizing for *delivery date* over *operating model*, and the recovery worked because I made cost a first-class, owned, measured concern rather than a surprise — and because I was transparent with finance instead of defensive. Cloud cost is an architecture decision, not an afterthought.

### Q22. [Practical] A regulated client requires that data never traverses the public internet and all PaaS access is private. How do you architect this?

The pattern is **Private Endpoints + Private DNS + restricted public access**, enforced by policy:

1. **Private Endpoints**: each PaaS resource (Azure SQL, Storage, Key Vault, Cosmos) gets a private endpoint — a NIC with a private IP inside your VNet. The public endpoint is then **disabled** (`publicNetworkAccess = Disabled`), so the only path is through the VNet.
2. **Private DNS Zones**: link `privatelink.database.windows.net` (etc.) zones to the VNet so the service FQDN resolves to the private IP, not the public one — otherwise the app still tries the public endpoint and fails.
3. **Hub egress control**: route all outbound through **Azure Firewall** with FQDN allow-lists; deny default internet egress.
4. **ExpressRoute** for on-prem connectivity so traffic never touches the internet end-to-end.
5. **Policy enforcement**: Azure Policy denies creation of any storage/SQL with public access enabled, and audits missing private endpoints — governance, not hope.

```
On-prem ──ExpressRoute──► Hub VNet ──peering──► Spoke VNet
                              │                     │
                         Azure Firewall      Private Endpoint (NIC)
                         (FQDN egress)         → Azure SQL (public OFF)
                                              Private DNS resolves FQDN → private IP
```

The trade-off: private endpoints add cost, DNS complexity, and operational friction (every new PaaS resource needs the endpoint + DNS wiring), so you automate the whole thing in Bicep modules. The biggest real-world failure mode is **DNS misconfiguration** — the app silently resolves the public name and the firewall blocks it, producing confusing timeouts. Industry case: financial-services and healthcare clients under regulations like PCI-DSS and HIPAA routinely mandate exactly this private-link topology.

### Q23. [Theory] How would you architect cross-region disaster recovery with defined RPO/RTO for a stateful Azure system?

DR design starts by quantifying **RPO** (max acceptable data loss) and **RTO** (max acceptable downtime) per workload, because they drive cost. The tiers:

- **Backup/restore** (RPO hours, RTO hours): Azure Backup with geo-redundant vaults; cheapest, slowest.
- **Pilot light / warm standby** (RPO minutes, RTO minutes): replicate data continuously to a secondary region, keep minimal compute warm, scale up on failover. Use **Azure Site Recovery** for VMs, **active geo-replication** for Azure SQL (async, RPO ~seconds, asynchronous so some loss), **RA-GRS/GZRS** for storage, and **Cosmos multi-region** for near-zero RPO.
- **Active-active** (RPO ~0, RTO ~0): both regions live, as in Q15; highest cost and complexity.

```
RPO/RTO ↓ (better)  but $ ↑
Backup ──► Pilot Light ──► Warm Standby ──► Active-Active
hours        minutes          minutes           ~zero
```

Critically, DR must be **tested** with regular failover drills, and you must understand the consistency model of each replication (Azure SQL geo-replication is asynchronous, so a sudden region loss can lose the last few unreplicated transactions — that *is* your RPO). Decide failover orchestration (manual approval vs automatic via Front Door/Traffic Manager health probes) and, just as important, **failback**. The expert nuance: the weakest data store sets the system-wide RPO, so design DR around your most-stateful, least-replicable component.

### Q24. [Theory] Compare Azure Functions hosting plans (Consumption, Premium, Dedicated, Flex) and the cold-start trade-off.

Azure Functions run on several plans that trade cost against latency and control:

- **Consumption**: pure pay-per-execution, scales to zero. Cheapest, but suffers **cold starts** (the runtime spins up an instance after idle, adding hundreds of ms to seconds for a JVM-heavy Java app) and has execution-time limits.
- **Premium (Elastic Premium)**: pre-warmed instances eliminate cold starts, supports VNet integration and longer runs, billed for reserved + elastic capacity. The go-to for latency-sensitive event processing.
- **Flex Consumption** (the modern serverless plan): combines scale-to-zero economics with fast scaling, configurable always-ready instances to mitigate cold start, and VNet support — increasingly the default choice over classic Consumption.
- **Dedicated (App Service Plan)**: runs functions on a plan you already pay for; no per-execution cost benefit but predictable and lets functions share resources with web apps.

The cold-start nuance matters most for Java because the JVM and Spring context take time to initialize; mitigations include keeping at least one always-ready/pre-warmed instance, using lighter frameworks (or GraalVM native images), and avoiding heavy static initialization. The trade-off is the eternal serverless tension: scale-to-zero saves money on idle but pays a latency tax on the first invocation after idle — Premium/Flex with always-ready instances buys that latency back with money.

---

## ✅ Key Takeaways

- The Azure hierarchy (Management Group → Subscription → Resource Group → Resource) is the backbone of billing, RBAC, and policy; design resource groups around shared lifecycle.
- Prefer **managed identities** over keys/connection strings everywhere — they are the single biggest security and operational win on Azure.
- Match the service to the workload: App Service/Container Apps for stateless web, AKS for orchestration; Service Bus for commands, Event Hubs for streams, Event Grid for reactive events.
- Cosmos DB's **partition key** and **consistency level** are the two decisions that make or break it; Azure SQL Hyperscale covers relational scale.
- **Bicep** is the modern IaC authoring layer over ARM; **Terraform** wins for multi-cloud.
- Multi-region active-active buys availability at the cost of write-conflict complexity — quantify RPO/RTO before choosing a DR tier.
- Governance at scale = **Landing Zones + Azure Policy as code + PIM + hub-and-spoke**, enabling self-service within guardrails.

## ⚠️ Common Pitfalls

- Confusing control-plane RBAC (Owner/Contributor) with **data-plane roles** (e.g., `Storage Blob Data Contributor`) — Owner alone can't read blob data.
- Forgetting **Private DNS zones** when using Private Endpoints, causing the app to resolve the now-disabled public endpoint and time out mysteriously.
- Treating Service Bus as exactly-once — it is **at-least-once**, so consumers must be idempotent.
- Choosing a poor Cosmos **partition key**, creating hot partitions that throttle (429s) regardless of provisioned RU/s.
- Reactive autoscale lagging predictable spikes — add **scheduled** scaling for known traffic windows.
- Hardcoding secrets instead of using Key Vault + managed identity; leaked storage keys are a top breach vector.
- Java **cold starts** on Consumption-plan Functions — use Premium/Flex with always-ready instances for latency-sensitive paths.
- Lift-and-shift to oversized VMs without right-sizing or reservations, causing 2–3x cost overruns.

## 📚 Further Reading

- *Microsoft Azure Cloud Adoption Framework* — official architecture, landing zones, and governance guidance ([learn.microsoft.com/azure/cloud-adoption-framework](https://learn.microsoft.com/azure/cloud-adoption-framework/)).
- *Azure Architecture Center* — reference architectures and the Well-Architected Framework five pillars ([learn.microsoft.com/azure/architecture](https://learn.microsoft.com/azure/architecture/)).
- *Designing Distributed Systems* by Brendan Burns — patterns directly applicable to AKS and container workloads.
- *Learning Microsoft Azure* by Jonah Andersson — broad practitioner coverage of core services.
- *Azure SQL Hyperscale* and *Cosmos DB* product docs — the authoritative source on scaling and consistency models.
- *Microsoft Entra (Azure AD) documentation* — identity, managed identities, and workload identity federation ([learn.microsoft.com/entra](https://learn.microsoft.com/entra/)).
