# Cloud Security Patterns

A deep, interview-focused guide to securing cloud workloads: the shared responsibility model, identity, network isolation, data protection, secrets, posture management, zero-trust, audit, and compliance automation. Examples lean on AWS and Java, but the patterns translate to Azure and GCP.

[← Back to master index](../README.md)

## Table of Contents

- [🟢 Basic (0–2 yrs)](#-basic-02-yrs)
- [🟡 Intermediate (3–7 yrs)](#-intermediate-37-yrs)
- [🟠 Advanced (8–12 yrs)](#-advanced-812-yrs)
- [🔴 Expert (15+ yrs)](#-expert-15-yrs)
- [✅ Key Takeaways](#-key-takeaways)
- [⚠️ Common Pitfalls](#-common-pitfalls)
- [📚 Further Reading](#-further-reading)

---

## 🟢 Basic (0–2 yrs)

### Q1. [Theory] What is the cloud shared responsibility model, and why does it matter?

The shared responsibility model splits security duties between the cloud provider and the customer. The provider is responsible for **security *of* the cloud** — physical data centers, the hypervisor, the network backbone, and managed-service control planes. The customer is responsible for **security *in* the cloud** — IAM configuration, OS patching (for IaaS), data encryption choices, network rules, and application code.

The exact line shifts with the service model:

```
            IaaS            PaaS            SaaS
          (EC2/VM)       (RDS/App Eng.)   (Workspace)
 Data        YOU             YOU              YOU
 App         YOU             YOU            PROVIDER
 Runtime     YOU           PROVIDER         PROVIDER
 OS          YOU           PROVIDER         PROVIDER
 Hypervisor  PROVIDER       PROVIDER         PROVIDER
 Hardware    PROVIDER       PROVIDER         PROVIDER
```

It matters because most cloud breaches are *customer-side misconfigurations*, not provider failures. Gartner has repeatedly stated that through 2025+, well over 95% of cloud security failures are the customer's fault. Knowing exactly where your responsibility starts prevents the dangerous assumption that "the cloud is secure by default."

### Q2. [Theory] What does "least privilege" mean in IAM, and how do you approach it?

Least privilege means every identity (user, role, service) gets only the permissions it needs to do its job — nothing more. You approach it by starting from *deny-all* and adding specific allows, scoping actions (`s3:GetObject` not `s3:*`), scoping resources (a single bucket ARN, not `*`), and adding conditions (source IP, MFA present, time of day). The trade-off is operational friction: tight policies break when requirements change, so teams are tempted to over-grant. The mature answer is to grant narrowly, then use access analyzers and last-accessed data to *tighten further*, treating policies as living artifacts rather than one-time grants.

### Q3. [Theory] What is the difference between authentication and authorization in cloud IAM?

Authentication answers "who are you?" — verifying an identity via credentials, tokens, federation (SAML/OIDC), or instance/workload identity. Authorization answers "what can you do?" — evaluating policies against the authenticated principal. In AWS the request is authenticated by signature (SigV4) or a session token, then authorized by evaluating all applicable identity policies, resource policies, SCPs, permission boundaries, and session policies. A common confusion in interviews: a valid login (authn) does not imply access (authz); you can be authenticated and still get `AccessDenied`.

### Q4. [Practical] You found an S3 bucket that is publicly readable. Walk through fixing it.

Scenario: a marketing bucket was made public years ago "to serve images" and now exposes internal PDFs.

Approach:
1. **Confirm exposure** — check the bucket policy, ACLs, and Block Public Access (BPA) settings.
2. **Stop the bleeding** — enable account-level *and* bucket-level Block Public Access immediately; this overrides public bucket policies/ACLs.
3. **Serve content correctly** — front the bucket with CloudFront + Origin Access Control (OAC) so the bucket itself stays private but assets are still served.
4. **Audit access** — review CloudTrail/S3 access logs to see if sensitive objects were downloaded.
5. **Prevent recurrence** — turn on account-level BPA org-wide via an SCP, and add an AWS Config rule (`s3-bucket-public-read-prohibited`).

Trade-off: OAC adds a CDN dependency and cache-invalidation complexity, but it is the production-correct pattern. In production I would never rely on bucket ACLs for access control — they are legacy and disabled by default on new buckets.

### Q5. [Theory] What is encryption at rest vs. in transit, and why do you need both?

Encryption at rest protects data on disk/storage (EBS volumes, S3 objects, RDS) so a stolen disk or snapshot is useless. Encryption in transit (TLS) protects data moving over the network so it cannot be sniffed or man-in-the-middled. You need both because they defend different threat models: at-rest defends physical/snapshot theft and insider disk access; in-transit defends network interception. Defense in depth means assuming any single layer can fail, so you never let plaintext exist on a wire *or* on a disk you do not fully control.

### Q6. [Practical] A teammate hardcoded an AWS access key in a Git repo. What now?

This is an emergency. Steps:
1. **Rotate/revoke first** — deactivate then delete the leaked key in IAM immediately. Assume it is already compromised; bots scrape public GitHub within minutes.
2. **Investigate** — use CloudTrail to look for anomalous API calls (new IAM users, `RunInstances` in odd regions for crypto-mining, data exfiltration).
3. **Purge from history** — the key is in Git history, so `git filter-repo` / BFG to remove it, then force-push (deleting the key already neutralizes it; purging is hygiene).
4. **Prevent recurrence** — add pre-commit secret scanning (gitleaks, `git-secrets`), enable GitHub push protection, and move to short-lived credentials (IAM roles, IRSA, or OIDC for CI) so there is no long-lived key to leak.

The real fix is architectural: long-lived static keys are an anti-pattern; workloads should assume roles.

---

## 🟡 Intermediate (3–7 yrs)

### Q7. [Theory] Explain IAM role assumption and why it beats long-lived keys.

Role assumption lets a principal call `sts:AssumeRole` to receive *temporary* credentials (access key, secret, and session token) scoped to a role's permissions, expiring in minutes to a few hours. It beats long-lived keys because: credentials are short-lived (a leaked token expires quickly), there is nothing static to embed in code, every assumption is logged in CloudTrail, and cross-account access is explicit via a trust policy. EC2 instances assume a role via the instance profile, EKS pods via IRSA/Pod Identity, and CI systems via OIDC federation. The trust relationship is two-sided: the *trust policy* on the role says who may assume it, while the *permissions policy* says what the assumed session can do.

```
   ┌──────────────┐   sts:AssumeRole    ┌──────────────────┐
   │  Principal   │ ──────────────────▶ │   STS endpoint   │
   │ (CI / pod /  │                     │  checks trust    │
   │  EC2 role)   │ ◀────────────────── │  policy of role  │
   └──────────────┘  temp creds (15m-   └──────────────────┘
                      12h, auto-expire)
```

### Q8. [Coding] Write Java code that assumes a cross-account role and lists S3 buckets using temporary credentials.

Problem: from service account A, assume a role in account B and use the *temporary* credentials to access B's S3, never using a static key.

```java
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.s3.S3Client;

public class CrossAccountS3 {

    public static void main(String[] args) {
        String roleArn = "arn:aws:iam::222222222222:role/ReadOnlyS3";

        // Base creds come from the instance role / IRSA - NOT hardcoded.
        try (StsClient sts = StsClient.builder().region(Region.US_EAST_1).build()) {

            AssumeRoleRequest req = AssumeRoleRequest.builder()
                    .roleArn(roleArn)
                    .roleSessionName("svc-A-audit-" + System.currentTimeMillis())
                    .durationSeconds(900)               // 15 min - least privilege in time
                    .externalId("shared-secret-xyz")    // mitigates confused-deputy
                    .build();

            Credentials c = sts.assumeRole(req).credentials();

            AwsSessionCredentials temp = AwsSessionCredentials.create(
                    c.accessKeyId(), c.secretAccessKey(), c.sessionToken());

            try (S3Client s3 = S3Client.builder()
                    .region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(temp))
                    .build()) {
                s3.listBuckets().buckets()
                  .forEach(b -> System.out.println(b.name()));
            }
        }
    }
}
```

- **Time complexity:** O(n) over the returned buckets; the STS call is a single network round trip.
- **Space complexity:** O(n) to hold the bucket list.
- **Edge cases:** trust policy missing/`externalId` mismatch → `AccessDenied`; session shorter than the work → use `durationSeconds` appropriately or refresh; reusing expired tokens → `ExpiredToken`. In real code prefer `StsAssumeRoleCredentialsProvider`, which auto-refreshes before expiry rather than manually copying credentials.

### Q9. [Theory] What is the confused-deputy problem and how does `ExternalId` prevent it?

The confused deputy is when a privileged service (the deputy) is tricked into using its authority on behalf of an attacker. Classic case: a SaaS vendor assumes roles in many customer accounts. If the trust policy only checks the vendor's account, an attacker who is *also* a customer could guess your role ARN and trick the vendor into assuming *your* role. The `ExternalId` is a shared secret placed in the trust policy condition; the vendor must present *your* unique ExternalId to assume *your* role, so they cannot be confused into acting against an account whose ID they were not given. It is specifically for third-party/cross-account scenarios — not a substitute for least privilege.

### Q10. [Theory] Explain envelope encryption and the role of KMS.

Envelope encryption encrypts data with a **data encryption key (DEK)**, then encrypts the DEK with a **key encryption key (KEK)** held in KMS. The encrypted DEK is stored alongside the ciphertext; the plaintext DEK is used in memory and discarded. KMS never exposes the KEK (often HSM-backed) and you call `GenerateDataKey` to get a DEK and `Decrypt` to unwrap it.

```
GenerateDataKey ─▶ returns {plaintext DEK, encrypted DEK}
   encrypt data with plaintext DEK (local, fast, AES-GCM)
   store: [ciphertext] + [encrypted DEK]
   discard plaintext DEK from memory

decrypt path:
   send encrypted DEK to KMS Decrypt ─▶ plaintext DEK
   decrypt ciphertext locally
```

Benefits: you encrypt gigabytes locally (fast symmetric crypto) while KMS only handles tiny keys (cheap, auditable, rate-friendly). Rotating the KEK does not require re-encrypting all data — only re-wrapping DEKs. This is exactly how S3 SSE-KMS, EBS, and the AWS Encryption SDK work.

### Q11. [Coding] Implement envelope encryption in Java using KMS `GenerateDataKey` and AES-GCM.

Problem: encrypt a payload using a KMS-generated DEK, store the encrypted DEK with the ciphertext, then decrypt.

```java
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;

public class EnvelopeCrypto {
    private static final int GCM_TAG_BITS = 128, IV_LEN = 12;
    private final KmsClient kms = KmsClient.create();
    private final String keyId;

    EnvelopeCrypto(String keyId) { this.keyId = keyId; }

    /** Returns: [encDekLen(4)][encDek][iv(12)][ciphertext+tag] */
    byte[] encrypt(byte[] plaintext) throws Exception {
        GenerateDataKeyResponse dk = kms.generateDataKey(b -> b
                .keyId(keyId).keySpec(DataKeySpec.AES_256));
        byte[] plainDek = dk.plaintext().asByteArray();
        byte[] encDek   = dk.ciphertextBlob().asByteArray();

        byte[] iv = new byte[IV_LEN];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(plainDek, "AES"),
               new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ct = c.doFinal(plaintext);
        Arrays.fill(plainDek, (byte) 0); // wipe DEK from memory ASAP

        return concat(intBytes(encDek.length), encDek, iv, ct);
    }

    byte[] decrypt(byte[] blob) throws Exception {
        int p = 0;
        int encLen = readInt(blob, p); p += 4;
        byte[] encDek = Arrays.copyOfRange(blob, p, p + encLen); p += encLen;
        byte[] iv     = Arrays.copyOfRange(blob, p, p + IV_LEN); p += IV_LEN;
        byte[] ct     = Arrays.copyOfRange(blob, p, blob.length);

        DecryptResponse dr = kms.decrypt(b -> b
                .ciphertextBlob(SdkBytes.fromByteArray(encDek)).keyId(keyId));
        byte[] plainDek = dr.plaintext().asByteArray();

        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(plainDek, "AES"),
               new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] out = c.doFinal(ct);
        Arrays.fill(plainDek, (byte) 0);
        return out;
    }

    // --- tiny byte helpers ---
    private static byte[] intBytes(int v){return new byte[]{(byte)(v>>>24),(byte)(v>>>16),(byte)(v>>>8),(byte)v};}
    private static int readInt(byte[] a,int o){return ((a[o]&0xff)<<24)|((a[o+1]&0xff)<<16)|((a[o+2]&0xff)<<8)|(a[o+3]&0xff);}
    private static byte[] concat(byte[]... parts){
        int n=0; for(byte[] x:parts)n+=x.length;
        byte[] r=new byte[n]; int p=0;
        for(byte[] x:parts){System.arraycopy(x,0,r,p,x.length);p+=x.length;}
        return r;
    }
}
```

- **Time complexity:** O(n) in payload size for AES-GCM; two constant-time KMS round trips (one per encrypt/decrypt).
- **Space complexity:** O(n) for ciphertext plus a constant overhead for the wrapped DEK + IV.
- **Edge cases:** never reuse an IV with the same key (GCM nonce reuse is catastrophic — always randomize); always zero the plaintext DEK; tag verification failure on decrypt throws `AEADBadTagException` (do not swallow it — it signals tampering); add an **encryption context** to KMS calls for an extra authenticated, auditable binding.

### Q12. [Practical] Compare secrets-management options: env vars, KMS, Secrets Manager, Parameter Store, Vault.

| Option | Rotation | Audit | Best for | Risk |
|---|---|---|---|---|
| Plain env vars | none | none | nothing sensitive | leaks via logs, `/proc`, crash dumps |
| SSM Parameter Store (SecureString) | manual/Lambda | CloudTrail | config + light secrets, cheap | no native rotation |
| Secrets Manager | **native, scheduled** | CloudTrail | DB creds, API keys | per-secret cost |
| KMS | n/a (encrypts) | CloudTrail | encrypting *other* things | not a secret *store* |
| HashiCorp Vault | dynamic, leases | audit log | multi-cloud, dynamic DB creds | you operate it (HA, unseal) |

Production stance: never put secrets in env vars or images. Use Secrets Manager (or Vault) with automatic rotation, fetch at runtime via the instance/pod role, and cache in memory with a short TTL. Vault wins when you need *dynamic* secrets (e.g., per-request, auto-expiring database credentials) or true multi-cloud portability; the cost is operating Vault's HA/unseal lifecycle yourself.

### Q13. [Theory] What are VPC security groups vs. NACLs, and when do you use each?

Security groups are **stateful**, instance/ENI-level firewalls that allow only what you specify (implicit deny, return traffic auto-allowed). NACLs are **stateless**, subnet-level rules evaluated in numbered order with explicit allow *and* deny, where you must open both directions. You use security groups as your primary control (e.g., "app tier may reach DB tier on 5432"), and reserve NACLs for coarse subnet-level guardrails like blocking a malicious IP range or enforcing that a private subnet never talks to the internet. A subtle interview point: security groups can reference *other security groups* as the source, enabling identity-based microsegmentation without hardcoding IPs.

### Q14. [Practical] Design private connectivity so your app reaches S3/DynamoDB without traversing the internet.

Scenario: a PCI workload must never send data over the public internet, even to AWS APIs.

Approach: use **VPC endpoints**. A *gateway endpoint* (S3, DynamoDB) adds a route-table entry so traffic to those services stays on the AWS network — free and simple. An *interface endpoint* (PrivateLink, most other services) provisions an ENI with a private IP in your subnet, optionally with a private DNS name, so SDK calls resolve to a private address. Lock it down with an endpoint policy (only specific buckets) and a security group on the interface endpoint.

```
   App in private subnet
        │  (no IGW / NAT needed for these calls)
        ▼
  ┌───────────────┐    gateway EP route        ┌────────────┐
  │ Route Table   │ ─────────────────────────▶ │  S3 / DDB  │
  └───────────────┘                            └────────────┘
        │  interface EP (PrivateLink ENI, private IP)
        ▼
  ┌───────────────┐ ─────────────────────────▶ ┌────────────┐
  │  ENI 10.0.x.y │                            │  KMS/SM/.. │
  └───────────────┘                            └────────────┘
```

Trade-offs: interface endpoints cost per hour + per GB and you need one per service per AZ; but they remove the NAT gateway data-processing cost and, more importantly, eliminate the internet egress path entirely, which is what the auditor wants.

### Q15. [Theory] What does a WAF protect against, and how does it relate to Shield/DDoS protection?

A Web Application Firewall (WAF) inspects HTTP(S) layer-7 traffic and blocks application attacks — SQL injection, XSS, bad bots, and lets you rate-limit or geo-block. It sits in front of ALB/CloudFront/API Gateway and uses managed rule groups plus custom rules. Shield is the **DDoS** service: Shield Standard (free) absorbs common L3/L4 volumetric attacks automatically; Shield Advanced adds L7 attack mitigation, cost-protection for scaling during attacks, and a response team. They are complementary layers: Shield handles *volumetric/flood* attacks (overwhelm you), WAF handles *application-logic* attacks (exploit you). A rate-based WAF rule is your first cheap defense against L7 floods before paying for Shield Advanced.

### Q16. [Coding] Write a Java filter that enforces TLS-only and adds security headers (HSTS, etc.).

Problem: even behind a load balancer, enforce HTTPS and emit hardening headers from a Spring Boot service.

```java
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import java.io.IOException;

@Component
public class SecurityHeadersFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  request  = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        // Behind an ALB, the real scheme is in X-Forwarded-Proto.
        String proto = request.getHeader("X-Forwarded-Proto");
        boolean secure = "https".equalsIgnoreCase(proto) || request.isSecure();

        if (!secure) {
            String host = request.getHeader("Host");
            response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY); // 301
            response.setHeader("Location", "https://" + host + request.getRequestURI());
            return;
        }

        response.setHeader("Strict-Transport-Security",
                "max-age=63072000; includeSubDomains; preload");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy", "default-src 'self'");
        response.setHeader("Referrer-Policy", "no-referrer");
        chain.doFilter(req, res);
    }
}
```

- **Time/Space complexity:** O(1) per request; constant memory.
- **Edge cases:** trust `X-Forwarded-Proto` only when the ALB strips/overwrites it (otherwise a client can spoof it — never trust the header from untrusted hops); HSTS `preload` is a one-way commitment, so test on a subdomain first; do not set HSTS on plain HTTP responses.

---

## 🟠 Advanced (8–12 yrs)

### Q17. [Theory] Explain landing zones and guardrails in a multi-account strategy.

A landing zone is a pre-architected, multi-account baseline that new teams "land" into with security, networking, logging, and identity already wired up. In AWS this is Control Tower + Organizations: a management account, dedicated *log archive* and *audit* accounts, and an Organizational Unit (OU) structure. **Guardrails** are the policies that keep accounts compliant: *preventive* guardrails are Service Control Policies (SCPs) that make actions impossible (e.g., "deny disabling CloudTrail", "deny non-approved regions"); *detective* guardrails are Config rules that flag drift. The pattern matters at scale because you cannot manually secure hundreds of accounts — you bake security into the account-vending process so a freshly minted account is already compliant on day zero. The trade-off is centralization friction: overly strict SCPs in the org root can block legitimate experimentation, so you tier guardrails per-OU (sandbox vs. prod).

```
        ┌────────────── Organization (root) ──────────────┐
        │  SCPs: deny-leave-org, deny-disable-cloudtrail,  │
        │        region-restriction                        │
        └───────┬───────────────┬───────────────┬──────────┘
            Security OU       Workloads OU      Sandbox OU
          ┌─────┴─────┐      ┌────┴────┐        ┌───┴───┐
        LogArchive  Audit   Prod    NonProd    devA   devB
        (immutable  (Sec    tighter  looser    loosest SCP
         CloudTrail) Hub)    SCPs     SCPs       + budget cap)
```

### Q18. [Theory] What is CSPM and what does it actually detect?

Cloud Security Posture Management (CSPM) continuously inventories cloud resources and evaluates them against security benchmarks (CIS, PCI, your own policies), flagging misconfigurations and drift. It detects things like public S3 buckets, security groups open to `0.0.0.0/0` on 22/3389, unencrypted volumes, IAM users without MFA, over-permissive policies, and exposed databases. Native examples are AWS Security Hub + Config; cross-cloud examples are Wiz, Prisma Cloud, and Microsoft Defender for Cloud. The value over point-in-time audits is *continuous* assessment with prioritization — modern CSPM (CNAPP) correlates a misconfig with network exposure and a known CVE to surface a true "toxic combination" (e.g., internet-exposed VM + critical CVE + role with admin) rather than drowning you in thousands of low-severity findings.

### Q19. [Practical] How do you implement automatic key rotation without breaking decryption of old data?

Scenario: compliance requires annual KMS rotation, but you have petabytes encrypted under the old key material.

Approach: KMS *automatic rotation* keeps the same key ID/ARN but generates new backing material on schedule; KMS retains old material so old ciphertext still decrypts transparently — applications change nothing. For *application-level* keys or secrets, use **versioning**: new writes use the new key version, reads look up the version ID stored with the ciphertext and decrypt with the matching key. For credentials in Secrets Manager, use the **multi-user rotation** pattern (two alternating DB users) so there is never a window where the live credential is invalid:

```
1. rotate Lambda creates/updates the "next" user's password
2. test the new credential
3. flip AWSCURRENT -> new, AWSPREVIOUS -> old (atomic label swap)
4. clients refresh on next fetch; old version still resolvable briefly
```

Trade-off: re-encrypting historical data under new material is expensive and usually unnecessary because envelope encryption only requires re-wrapping DEKs. What you *must* guarantee is that decryption code can resolve *any* historical key version — losing old key material means losing the data.

### Q20. [Theory] Describe zero-trust architecture in the cloud and how it differs from perimeter security.

Zero-trust replaces "trust the internal network" with "never trust, always verify": every request is authenticated, authorized, and encrypted regardless of network location, under the assumption the network is already breached. In the cloud this means workload identity (SPIFFE/SVID, IAM roles, mTLS via a service mesh) instead of IP allowlists, per-request authorization with short-lived tokens, microsegmentation (security groups referencing each other, not CIDRs), and continuous device/posture signals. It differs from perimeter ("castle and moat") security where, once inside the VPC, services trusted each other freely — a model that lets an attacker move laterally after one foothold. The principles map to NIST 800-207: continuous verification, least privilege, assume-breach, and explicit per-session authorization. The trade-off is complexity and latency from per-hop authentication, mitigated by a mesh that handles mTLS transparently.

### Q21. [Practical] Walk through detecting and responding to compromised cloud credentials using CloudTrail.

Scenario: GuardDuty alerts on "anomalous API call from a Tor exit node" using a developer's role.

Approach:
1. **Detect** — GuardDuty/Security Hub finding fires; the finding includes the principal, source IP, and API.
2. **Scope via CloudTrail** — query (Athena over the CloudTrail S3 bucket, or CloudTrail Lake) for all events by that `accessKeyId`/`sessionIssuer` over the window: look for `CreateUser`, `AttachUserPolicy`, `CreateAccessKey`, `RunInstances`, `GetSecretValue`, `Decrypt`, unusual regions.
3. **Contain** — attach an explicit deny-all policy to the principal (faster than deleting and preserves forensics), revoke active sessions (`aws-revoke-older-than` inline policy with a token-issue-time condition), rotate any keys.
4. **Eradicate/recover** — terminate attacker resources, restore from known-good IaC, rotate downstream secrets the principal could read.
5. **Postmortem** — how did the credential leak? Move to short-lived creds and add detective Config rules.

```sql
-- Athena: every action by the suspect session in the last 24h
SELECT eventtime, eventname, sourceipaddress, awsregion, errorcode
FROM cloudtrail_logs
WHERE useridentity.accesskeyid = 'ASIA...'
  AND eventtime > date_add('hour', -24, now())
ORDER BY eventtime;
```

Production note: CloudTrail must be **multi-region, org-wide, with log file validation** and delivered to a separate, locked-down log-archive account with Object Lock so an attacker who compromises the workload account cannot delete the evidence.

### Q22. [Coding] Implement a least-privilege IAM policy generator in Java from observed API calls.

Problem: given a stream of CloudTrail-style `(service, action, resourceArn)` events from a workload's *test* run, emit a minimal IAM policy granting exactly those, grouped by resource.

```java
import java.util.*;
import java.util.stream.Collectors;

public class LeastPrivilegePolicyBuilder {

    record ApiCall(String service, String action, String resourceArn) {}

    /** Groups actions by resource ARN and emits a minimal IAM policy JSON. */
    static String build(List<ApiCall> calls) {
        // resource -> sorted unique "service:action"
        Map<String, TreeSet<String>> byResource = new TreeMap<>();
        for (ApiCall c : calls) {
            String act = c.service() + ":" + c.action();
            byResource.computeIfAbsent(
                c.resourceArn() == null ? "*" : c.resourceArn(),
                k -> new TreeSet<>()).add(act);
        }

        String stmts = byResource.entrySet().stream().map(e -> """
                {
                  "Effect": "Allow",
                  "Action": [%s],
                  "Resource": "%s"
                }""".formatted(
                    e.getValue().stream()
                        .map(a -> "\"" + a + "\"")
                        .collect(Collectors.joining(", ")),
                    e.getKey()))
            .collect(Collectors.joining(",\n"));

        return "{\n  \"Version\": \"2012-10-17\",\n  \"Statement\": [\n"
                + stmts + "\n  ]\n}";
    }

    public static void main(String[] args) {
        var calls = List.of(
            new ApiCall("s3", "GetObject",  "arn:aws:s3:::data/*"),
            new ApiCall("s3", "PutObject",  "arn:aws:s3:::data/*"),
            new ApiCall("kms","Decrypt",    "arn:aws:kms:us-east-1:1:key/abc"),
            new ApiCall("s3", "GetObject",  "arn:aws:s3:::data/*")); // dup
        System.out.println(build(calls));
    }
}
```

- **Time complexity:** O(n log n) — each insert into the per-resource `TreeSet` is O(log k); n calls total.
- **Space complexity:** O(u) for u unique (resource, action) pairs.
- **Edge cases:** wildcard ARNs collapse to `"*"` (flag these for human review — they defeat least privilege); some actions don't support resource-level permissions and *must* use `*` (e.g., `ec2:DescribeInstances`), so a production version consults the IAM "actions, resources, condition keys" reference; this generates *allow* but you should still layer a permission boundary. Java text blocks (Java 15+) are used; on Java 8 you'd concatenate strings manually.

### Q23. [Theory] What are the most common cloud misconfigurations and the systemic fixes?

The recurring offenders: (1) **public storage** — S3/Blob buckets exposed; fix with org-wide Block Public Access + Config rule. (2) **over-permissive IAM** — `*:*`, wildcard resources, unused admin; fix with access analyzer, last-accessed pruning, permission boundaries. (3) **open security groups** — `0.0.0.0/0` on SSH/RDP/DB ports; fix with detective rules + SSM Session Manager instead of bastions. (4) **no/disabled logging** — CloudTrail off; fix with preventive SCP. (5) **unencrypted resources** — default-encrypt EBS/S3 via account settings. (6) **public snapshots/AMIs**. (7) **exposed secrets in IaC/images**. The systemic fix is not whack-a-mole remediation but *shifting left*: policy-as-code in CI (OPA/Conftest, `tfsec`, Checkov) blocks the misconfig before deploy, and preventive guardrails make the worst ones impossible at runtime.

### Q24. [Practical] How would you architect compliance automation for SOC 2 / PCI across many accounts?

Scenario: a fintech needs continuous PCI evidence across 200 accounts without an army of auditors.

Approach: treat controls as code. Map each control to a *machine-checkable* rule in AWS Config / Security Hub conformance packs (e.g., "all EBS encrypted", "MFA on root"). Aggregate findings into Security Hub in a central audit account. Auto-remediate low-risk drift with Config remediation actions / EventBridge → Lambda (e.g., re-enable Block Public Access). Generate evidence continuously with AWS Audit Manager mapped to the PCI framework, exporting time-stamped reports auditors accept. Enforce the non-negotiables with SCP guardrails so violations cannot happen. The trade-off: auto-remediation can mask root causes and occasionally break legitimate workloads, so high-risk controls only *alert + ticket* (human-in-the-loop) while low-risk ones self-heal. The win is moving from a quarterly fire drill to a live dashboard where compliance is a measured percentage, not an annual hope.

---

## 🔴 Expert (15+ yrs)

### Q25. [Theory] Critique the shared responsibility model for serverless and managed AI services.

The classic IaaS/PaaS/SaaS chart breaks down at the edges. With serverless (Lambda, Fargate), the provider owns the runtime and OS, but you still own IAM, the function's dependencies (a vulnerable npm/maven transitive dep is *your* problem), event-source configuration, and the blast radius of an over-permissioned execution role — so the "code + config + data" slice is deceptively large. With managed AI/LLM services, new responsibility lines appear that the chart never anticipated: prompt-injection and data-exfiltration via tool use, training-data governance, model-output handling, and tenant isolation of embeddings. The honest expert position is that the model is a *useful heuristic, not a contract*: each managed service has its own implicit boundary, and the only safe assumption is to read the service's specific security docs and threat-model the integration rather than trusting the generic pyramid. This is exactly why "shared *fate*" framing is emerging — providers ship secure-by-default configs and opinionated guardrails because pure "responsibility" leaves customers stranded.

### Q26. [Practical] Design a defense-in-depth architecture for a regulated multi-region SaaS. What are the trade-offs?

```
 Internet
   │
 [Shield Adv + CloudFront + WAF]      L3/4 DDoS + L7 rules + geo
   │
 [ALB, TLS1.3, OAC]                   public subnets, no instances
   │  (SG ref, not CIDR)
 [App tier - private subnets]         IRSA workload identity, no keys
   │  (mTLS via mesh)
 [Internal ALB / service mesh]        zero-trust east-west
   │  (VPC endpoints, no NAT to AWS APIs)
 [Data tier - isolated subnets]       RDS SSE-KMS, no public access
   │
 [KMS (CMK, per-tenant grants)] [Secrets Mgr rotation]
   │
 Cross-cutting: CloudTrail(org, Object Lock) | GuardDuty | Config |
                Security Hub | Macie (PII) | centralized log archive acct
```

Decisions and trade-offs: **per-tenant CMKs** give crypto-isolation and per-tenant revocation/BYOK, but KMS request costs and key limits force pooling above a threshold — so I'd tier (dedicated keys for enterprise, pooled with encryption-context isolation for SMB). **Active-active multi-region** improves availability and meets data-residency, but key replication and consistent IAM/guardrails across regions add operational load; I'd use multi-Region KMS keys and deploy guardrails via Control Tour/IaC so posture is identical everywhere. **Service mesh mTLS** delivers zero-trust east-west at the cost of latency and sidecar overhead. The overarching trade-off is that defense-in-depth adds cost and cognitive load; I justify each layer against a specific threat in the threat model rather than adding controls reflexively.

### Q27. [Theory] How do you reason about the IAM policy evaluation order across SCPs, boundaries, and resource policies?

AWS evaluates a request through several gates, and an *explicit deny anywhere wins*. The mental model: (1) if any applicable **SCP** doesn't allow it → deny (SCPs are a ceiling, never a grant). (2) **Resource-based policy** can grant cross-account access independently. (3) For same-account, you need an **identity policy** allow *and* the **permission boundary** must allow (boundary is also a ceiling on the identity). (4) **Session policies** further narrow an assumed-role session. So the effective permission is the *intersection* of every "allow ceiling" minus any explicit deny. The expert insight: SCPs and permission boundaries do not *grant* anything — a frequent source of "I added the SCP allow but still get denied" confusion. You combine them deliberately: SCPs are the org guardrail, boundaries are how you let teams self-service IAM safely (devs can create roles, but only within the boundary), and session policies scope down third-party/federated access.

### Q28. [Behavioral] Tell me about a time you had to balance security with developer velocity.

(Use STAR.) *Situation:* engineers were bypassing our security review because it was a manual, multi-day gate, and were storing secrets in env vars to ship faster. *Task:* reduce real risk without becoming the team everyone routes around. *Action:* I reframed security as a paved road, not a checkpoint — I shipped a vetted Terraform module library with encryption, logging, and least-privilege baked in, added policy-as-code (Checkov/OPA) that failed CI *with the fix in the message*, and replaced static keys with OIDC federation so the "secure way" was also the *easiest* way. I co-owned the rollout with two senior devs so it wasn't security dictating to engineering. *Result:* secret-in-repo incidents went to zero, mean time to a compliant deploy dropped from days to minutes, and adoption was voluntary because the paved road was genuinely faster. The lesson I emphasize: security that fights velocity loses; security that *removes toil* wins. I measure my function's success partly by developer satisfaction, not just findings closed.

### Q29. [Practical] A pen-test found that an over-permissioned Lambda role allowed privilege escalation to admin. How do you remediate and prevent recurrence?

Scenario: the Lambda's role had `iam:PassRole` with `Resource: *` plus `lambda:UpdateFunctionConfiguration`, letting an attacker pass an admin role to a new function and escalate.

Remediate immediately: scope `iam:PassRole` to specific role ARNs with a `PassedToService` condition, remove unused IAM/`*` actions, and add a permission boundary so the role can never exceed its intended ceiling even if someone re-broadens it. Then sweep: use IAM Access Analyzer's *unused access* findings and CloudTrail last-accessed data to right-size every role, and run an automated check for the known escalation combinations (`iam:PassRole`+`lambda:Create*`, `iam:CreatePolicyVersion`, `iam:AttachUserPolicy`, etc.). Prevent recurrence systemically: require all roles to be created via a vetted IaC module that *injects a permission boundary*, add an SCP denying `iam:PassRole` without a service condition, and add a policy-as-code rule that fails CI on wildcard `PassRole`. The deeper point for an expert: privilege escalation is rarely a single bad permission — it is a *graph* of permissions, so I'd run a tool that models the escalation graph (e.g., access-analyzer custom policy checks, or open-source IAM-graph analyzers) rather than reviewing policies line by line.

### Q30. [Theory] How do you secure the cloud control plane and supply chain, beyond runtime workloads?

The control plane (your IaC, CI/CD, and the cloud APIs themselves) is now the highest-value target because compromising it grants everything at once. Securing it: (1) **CI/CD identity** — pipelines assume short-lived roles via OIDC, never store cloud keys; scope the role to exactly what the pipeline deploys. (2) **Protect the IaC path** — require PR review and signed commits, run policy-as-code as a merge gate, and apply via a controlled runner, not from laptops. (3) **Supply-chain integrity** — generate SBOMs, scan dependencies and base images, and verify provenance with SLSA/Sigstore (cosign) so only signed artifacts deploy; this directly counters the SolarWinds-style build-system compromise. (4) **Admin access** — break-glass roles with session recording, JIT elevation, and MFA; no standing admin. (5) **Tenant of the tenant** — secure the *humans*: phishing-resistant MFA (FIDO2) for the console, since a phished admin defeats every runtime control. The expert framing: runtime controls protect *what runs*; control-plane and supply-chain controls protect *what gets to run* — and the latter is where catastrophic, fleet-wide compromise originates.

### Q31. [Practical] Real-world case study: what would you have changed about the 2019 Capital One breach?

The 2019 Capital One breach exposed ~100M records. The chain: a misconfigured WAF (an SSRF-vulnerable reverse proxy) let an attacker reach the EC2 instance metadata service, retrieve the instance role's temporary credentials, and use the role's overly broad `s3:List*`/`s3:GetObject` to exfiltrate data from S3. What I'd change, mapped to the layers in this guide: (1) **IMDSv2** — require token-based metadata (IMDSv2) org-wide via SCP/launch templates, which blocks the SSRF-to-credentials pivot that made this possible. (2) **Least privilege** — the role should not have had broad bucket access; scope to needed prefixes, and add VPC-endpoint policies. (3) **Egress controls + GuardDuty** — anomalous S3 download volume to an external IP should have fired (GuardDuty's S3 exfiltration finding). (4) **WAF hardening + SSRF-resistant proxy config**. (5) **Macie** to classify the sensitive data so its exposure surface was known and monitored. The systemic lesson: no single control failed in isolation — defense-in-depth means any one of IMDSv2, least-privilege role scoping, or egress detection would have broken the chain. This is the canonical interview case for *why* you layer.

### Q32. [Behavioral] How do you drive a security culture and prioritize finite security budget across an org?

(STAR-lite.) I treat security as risk management, not absolutism — you cannot fix everything, so I prioritize by *likelihood × impact* against an explicit threat model, and I make the trade-offs visible to leadership in business terms (expected loss, regulatory exposure) rather than CVSS scores. *Action* patterns that have worked: establish a small set of non-negotiable guardrails (the things that can never happen) enforced automatically, then let teams move fast inside them; embed security champions in product teams so security scales without a giant central team; and run blameless incident reviews so people report problems instead of hiding them. I invest budget first in the controls with the broadest blast-radius reduction — identity, logging, and the supply chain — before niche tooling. *Result/principle:* the metric I care about is reduced mean-time-to-detect and the percentage of risk covered by automated guardrails, because a culture where the secure path is the default and the easy one is far more durable than one that depends on heroics or fear.

---

## ✅ Key Takeaways

- The shared responsibility model is a heuristic, not a contract — the boundary shifts per service, and the customer side is where nearly all breaches originate.
- Prefer **short-lived, assumed-role credentials** over long-lived keys everywhere: workloads, CI (OIDC), and cross-account (with `ExternalId`).
- Least privilege is a continuous practice: grant narrowly, then prune with access analyzers and last-accessed data; use permission boundaries so teams self-service IAM safely.
- Defense in depth: encrypt at rest *and* in transit, layer Shield (DDoS) + WAF (L7), use private endpoints to keep traffic off the internet, and microsegment with SG-references not CIDRs.
- Envelope encryption + KMS lets you rotate keys without re-encrypting data; never reuse a GCM nonce and always wipe plaintext DEKs.
- Scale security with **landing zones + preventive SCP guardrails + detective Config rules**, and shift misconfiguration prevention left with policy-as-code in CI.
- Zero-trust ("never trust, always verify") replaces the perimeter; verify every request with workload identity regardless of network location.
- CloudTrail must be org-wide, multi-region, validated, and stored in a separate locked-down account with Object Lock so attackers can't erase evidence.
- The control plane and software supply chain are top-tier targets — protect CI/CD identity, sign artifacts (SLSA/Sigstore), and use phishing-resistant MFA for admins.

## ⚠️ Common Pitfalls

- Assuming "the cloud is secure by default" — public buckets, open security groups, and disabled encryption are *your* defaults to fix.
- Hardcoding static access keys in code, images, or environment variables instead of using roles/secrets managers.
- Granting `*:*` or wildcard resources "to unblock the team," then never tightening — over-permissive IAM is the second-most-common breach cause.
- Forgetting NACLs are stateless (must open return traffic) while security groups are stateful — a frequent connectivity *and* exposure bug.
- Reusing AES-GCM IVs/nonces, or losing old KMS key material so historical ciphertext becomes undecryptable.
- Trusting `X-Forwarded-Proto`/`X-Forwarded-For` from untrusted hops, enabling spoofing.
- Relying on IMDSv1 (SSRF → credential theft, the Capital One pivot) instead of requiring IMDSv2.
- Treating compliance as an annual audit instead of continuous, automated control checks.
- Auto-remediating high-risk findings blindly, masking root causes or breaking legitimate workloads.
- Building security gates that slow developers so much they route around them — the secure path must also be the easy path.

## 📚 Further Reading

- *AWS Well-Architected Framework — Security Pillar* (AWS, current edition) — authoritative design principles and patterns.
- NIST SP 800-207, *Zero Trust Architecture* — the canonical zero-trust reference.
- *Hacking Kubernetes* and *Container Security* (Liz Rice, O'Reilly) — for workload/runtime security depth.
- CIS Benchmarks (AWS / Azure / GCP Foundations) — the misconfiguration checklist CSPM tools encode.
- *Practical Cloud Security* (Chris Dotson, O'Reilly, 2nd ed.) — broad, vendor-neutral cloud security treatment.
- Cloud provider security docs: AWS IAM, KMS, and Organizations/Control Tower guides; the *AWS Security Reference Architecture (SRA)*.
