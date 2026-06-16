# 🎯 The Complete Software Engineering Interview Preparation Guide

> A comprehensive, multi-technology interview-prep resource spanning **0–2 years → 15+ years** experience.
> Every topic is answered at four levels — 🟢 Basic, 🟡 Intermediate, 🟠 Advanced, 🔴 Expert — mixing **theory**, **practical scenarios**, and **coding** questions with full solutions, ASCII diagrams, complexity analysis, version differences, and real-world case studies.

---

## 📊 At a Glance

| Metric | Count |
|---|---|
| Topic documents | **110** |
| Sections | **13** |
| Interview questions (Q&A) | **~2,666** |
| Coding problems (full solutions) | **~527** |
| Approx. word count | **~575,000** |
| Experience levels per topic | **4** (0–2y / 3–7y / 8–12y / 15+y) |

This guide is built to be your **primary interview-prep resource for 6–12 months**.

---

## 🚀 Start Here

| Document | What it gives you |
|---|---|
| [How to Use This Guide & Study Plan](00-getting-started/how-to-use.md) | Executive summary, navigation, and 12-week / 6-month study plans |
| [Interview Tips by Experience Level](00-getting-started/interview-tips-by-level.md) | What interviewers expect at each level, answer frameworks, common mistakes, demonstrating leadership |
| [Study Resources & Recommendations](00-getting-started/study-resources.md) | Curated books, courses, and practice platforms by topic |

---

## 📚 How Each Topic File Is Structured

```
H1 Title + intro
├── 🟢 Basic (0–2 yrs)          fundamentals, definitions
├── 🟡 Intermediate (3–7 yrs)   practical implementation, common scenarios
├── 🟠 Advanced (8–12 yrs)      complex architecture, optimization
├── 🔴 Expert (15+ yrs)         system design, trade-offs, leadership
├── ✅ Key Takeaways
├── ⚠️ Common Pitfalls
└── 📚 Further Reading
```

Each question is tagged **[Theory]**, **[Practical]**, **[Coding]**, or **[Behavioral]**. Coding answers include multiple approaches (brute-force → optimal), **Time/Space complexity**, and edge cases.

**Coverage tiers:** *Deep* topics (core: Java, Spring, DSA, System Design, messaging, databases, security) are exhaustive. *Solid* topics (e.g. ExtJS, SOAP, EclipseLink, niche tools) get full level coverage with focused depth.

---

## 🗂️ Table of Contents

### 01 · Backend & Enterprise Java
| File | Tier | Focus |
|---|---|---|
| [Java Core (8/11/17/21)](01-backend-java/java-core.md) | Deep | Lambdas, Streams, records, sealed, virtual threads, version-by-version diffs |
| [Java Concurrency & Multithreading](01-backend-java/java-concurrency.md) | Deep | Executors, JMM/happens-before, locks, CompletableFuture, virtual threads |
| [JVM Internals & Performance Tuning](01-backend-java/jvm-internals.md) | Deep | Memory areas, GC (G1/ZGC/Shenandoah), JIT, leak/dump/profiling, GraalVM |
| [J2EE / Jakarta EE](01-backend-java/jakarta-ee.md) | Solid | Servlets, EJB, CDI, JPA/JTA, JAX-RS/WS, javax→jakarta migration |
| [Spring Framework Core](01-backend-java/spring-core.md) | Deep | IoC/DI, bean lifecycle, AOP proxies, @Transactional internals |
| [Spring Boot](01-backend-java/spring-boot.md) | Deep | Auto-config, Boot 2 vs 3, MVC/WebFlux, testing, Actuator, native image |
| [Spring Security (Advanced)](01-backend-java/spring-security.md) | Deep | Filter chain, OAuth2/JWT, method security, ACL, reactive security |
| [Spring Cloud](01-backend-java/spring-cloud.md) | Deep | Config, discovery, Gateway, OpenFeign, Resilience4j, tracing |
| [Hibernate & JPA](01-backend-java/hibernate-jpa.md) | Deep | Mappings, N+1, caching, locking, JPQL/Criteria, performance |
| [EclipseLink](01-backend-java/eclipselink.md) | Solid | JPA RI, caching, weaving, MOXy, vs Hibernate |

### 02 · Microservices
| File | Tier | Focus |
|---|---|---|
| [Microservices Architecture Patterns](02-microservices/microservices-patterns.md) | Deep | DDD, decomposition, CQRS, BFF, contract testing, when not to |
| [Resilience Patterns](02-microservices/resilience-patterns.md) | Deep | Circuit breaker, retry/backoff, bulkhead, rate limit, chaos |
| [Saga & Distributed Transactions](02-microservices/saga-distributed-tx.md) | Deep | Orchestration vs choreography, outbox+CDC, exactly-once |
| [Service Mesh](02-microservices/service-mesh.md) | Solid | Envoy/Istio/Linkerd, mTLS, ambient/eBPF, vs libraries |

### 03 · Message Queues & Streaming
| File | Tier | Focus |
|---|---|---|
| [Apache Kafka](03-messaging/kafka.md) | Deep | Partitions, ISR, EOS/transactions, Streams, Connect, KRaft, tuning |
| [RabbitMQ](03-messaging/rabbitmq.md) | Deep | Exchanges, acks/prefetch, DLX, quorum queues, vs Kafka |
| [ActiveMQ](03-messaging/activemq.md) | Solid | JMS, Artemis vs Classic, persistence, HA |
| [Redis](03-messaging/redis.md) | Deep | Data types, persistence, cluster/sentinel, caching, locks |
| [Event-Driven Architecture](03-messaging/event-driven-architecture.md) | Deep | Event sourcing, CQRS, delivery semantics, outbox, schema evolution |

### 04 · APIs & Authentication
| File | Tier | Focus |
|---|---|---|
| [REST API Design](04-apis-auth/rest.md) | Deep | Methods, idempotency, versioning, pagination, RFC 7807, caching |
| [SOAP & Web Services](04-apis-auth/soap.md) | Solid | WSDL, WS-*, JAX-WS, faults, REST vs SOAP |
| [GraphQL](04-apis-auth/graphql.md) | Deep | Schema, resolvers, N+1/DataLoader, federation, security |
| [OpenAPI / Swagger](04-apis-auth/openapi-swagger.md) | Solid | Spec, contract-first, springdoc, codegen, governance |
| [MapStruct](04-apis-auth/mapstruct.md) | Solid | Compile-time mapping, DTO↔entity, vs ModelMapper/Dozer |
| [OAuth 2.0 & OpenID Connect](04-apis-auth/oauth2-oidc.md) | Deep | Grant types, PKCE, tokens, OIDC, OAuth 2.1, attacks |
| [JWT (Advanced Patterns)](04-apis-auth/jwt.md) | Deep | JWS/JWE, alg=none, JWKS, refresh rotation, revocation |
| [API Gateway Patterns](04-apis-auth/api-gateway.md) | Deep | Routing, auth offload, rate limiting, BFF, vs mesh |
| [Auth Patterns (Basic, M2M, mTLS, WS-Security)](04-apis-auth/auth-patterns.md) | Deep | API keys, client creds, mTLS, SAML/WS-Federation, passkeys, zero-trust |

### 05 · Databases
| File | Tier | Focus |
|---|---|---|
| [SQL Fundamentals](05-databases/sql-fundamentals.md) | Deep | Joins, window functions, CTEs, isolation, normalization, SQL puzzles |
| [Oracle Database](05-databases/oracle.md) | Solid | PL/SQL, CBO/hints, partitioning, RAC, AWR |
| [PostgreSQL](05-databases/postgresql.md) | Deep | MVCC, vacuum, index types, JSONB, replication, EXPLAIN |
| [MySQL](05-databases/mysql.md) | Deep | InnoDB, locks, replication, binlog/CDC, online schema change |
| [MongoDB](05-databases/mongodb.md) | Deep | Document modeling, aggregation, sharding, transactions |
| [Query Optimization & Indexing](05-databases/query-optimization-indexing.md) | Deep | Index internals, composite order, plans, slow-query tuning |
| [Sharding & Replication Strategies](05-databases/sharding-replication.md) | Deep | Shard keys, topologies, quorum, distributed SQL |
| [Connection Pooling & HikariCP](05-databases/connection-pooling-hikaricp.md) | Solid | Pool sizing, leak detection, PgBouncer, serverless |

### 06 · UI & Frontend
| File | Tier | Focus |
|---|---|---|
| [HTML & CSS](06-frontend/html-css.md) | Solid | Semantics, a11y, Flexbox/Grid, specificity, modern CSS |
| [JavaScript](06-frontend/javascript.md) | Deep | Closures, prototypes, event loop, async, debounce/throttle |
| [TypeScript](06-frontend/typescript.md) | Deep | Generics, utility/conditional/mapped types, narrowing |
| [Angular (v17–21)](06-frontend/angular.md) | Deep | DI, RxJS, signals, control flow, change detection, SSR |
| [React](06-frontend/react.md) | Deep | Hooks, reconciliation/fiber, memoization, Server Components |
| [Vue.js](06-frontend/vue.md) | Solid | Reactivity, Composition API, Pinia, vs React/Angular |
| [ExtJS (Sencha)](06-frontend/extjs.md) | Solid | Class system, stores, MVVM, migrating off |
| [Thymeleaf](06-frontend/thymeleaf.md) | Solid | Natural templating, Spring integration, vs JSP |
| [State Management (NgRx & beyond)](06-frontend/state-management-ngrx.md) | Deep | Redux pattern, Effects/Selectors, SignalStore, vs RTK/Zustand |
| [Frontend Security](06-frontend/frontend-security.md) | Deep | XSS, CSRF, CSP, CORS, token storage, supply chain |

### 07 · DevOps & Cloud
| File | Tier | Focus |
|---|---|---|
| [Git & GitHub](07-devops-cloud/git-github.md) | Solid | Internals, rebase/merge, branching strategies, recovery |
| [GitHub Actions](07-devops-cloud/github-actions.md) | Solid | Workflows, matrix, OIDC, reusable, caching, security |
| [Jenkins](07-devops-cloud/jenkins.md) | Solid | Declarative pipelines, shared libs, agents, K8s |
| [GitLab CI/CD](07-devops-cloud/gitlab-ci.md) | Solid | Stages, runners, DAG, built-in scanning, Auto DevOps |
| [Docker & Containers](07-devops-cloud/docker.md) | Deep | Layers, multi-stage, networking, security, BuildKit |
| [Kubernetes](07-devops-cloud/kubernetes.md) | Deep | Workloads, services/ingress, HPA, RBAC, operators, troubleshooting |
| [Helm](07-devops-cloud/helm.md) | Solid | Charts, templating, releases, vs Kustomize |
| [Terraform & IaC](07-devops-cloud/terraform.md) | Deep | State, modules, plan/apply, drift, OpenTofu |
| [AWS](07-devops-cloud/aws.md) | Deep | Core services, Well-Architected, serverless, IAM |
| [Microsoft Azure](07-devops-cloud/azure.md) | Solid | Core services, AKS, Entra ID, Bicep |
| [Google Cloud Platform](07-devops-cloud/gcp.md) | Solid | Core services, GKE, Cloud Run, BigQuery |
| [SonarQube & Code Quality](07-devops-cloud/sonarqube.md) | Solid | Quality gates, coverage, debt, branch/PR analysis |
| [Prometheus & Grafana](07-devops-cloud/prometheus-grafana.md) | Deep | Metric types, PromQL, alerting, cardinality, SLOs |
| [Observability (Metrics/Logs/Traces)](07-devops-cloud/observability.md) | Deep | OpenTelemetry, tracing, RED/USE, SLI/SLO, incident debugging |

### 08 · Data Structures & Algorithms
> Start with the [complexity cheat-sheet](08-dsa/complexity-analysis.md). **~180+ fully-solved coding problems** in Java across these files.

| File | Focus |
|---|---|
| [Time & Space Complexity Analysis](08-dsa/complexity-analysis.md) | Big-O/Ω/Θ, Master Theorem, amortized analysis |
| [Arrays & Strings](08-dsa/arrays-strings.md) | Prefix sums, Kadane, two-pointer, KMP, intervals |
| [Linked Lists](08-dsa/linked-lists.md) | Reverse, cycle detection, merge-k, LRU |
| [Stacks & Queues](08-dsa/stacks-queues.md) | Monotonic stack/deque, histogram, RPN |
| [Hash Tables & Maps](08-dsa/hashing.md) | Collisions, two-sum family, top-k, LRU/LFU |
| [Trees (Binary, BST, AVL, B-Trees)](08-dsa/trees.md) | Traversals, LCA, serialize, balancing, B+-trees |
| [Heaps & Priority Queues](08-dsa/heaps-priority-queues.md) | Heapsort, kth-largest, median stream, merge-k |
| [Trie, Segment Tree & Fenwick Tree](08-dsa/tries-segment-fenwick.md) | Autocomplete, range queries, inversions |
| [Graphs](08-dsa/graphs.md) | BFS/DFS, components, topo, islands, clone |
| [Sorting Algorithms](08-dsa/sorting.md) | Quick/merge/heap/radix, stability, TimSort |
| [Searching Algorithms](08-dsa/searching.md) | Binary search on answer, rotated, 2-sorted median |
| [Recursion & Backtracking](08-dsa/recursion-backtracking.md) | Subsets/permutations, N-Queens, Sudoku |
| [Dynamic Programming (1D/2D/opt)](08-dsa/dynamic-programming.md) | Knapsack, LCS, LIS, interval/tree/bitmask DP |
| [Greedy Algorithms](08-dsa/greedy.md) | Interval scheduling, Huffman, exchange argument |
| [Divide & Conquer](08-dsa/divide-conquer.md) | Closest pair, inversions, Karatsuba, skyline |
| [Sliding Window & Two Pointers](08-dsa/sliding-window-two-pointers.md) | Min window, 3-sum, container with water |
| [Union-Find & Topological Sort](08-dsa/union-find-topological.md) | DSU, Kruskal, Kahn, alien dictionary |
| [Shortest Path](08-dsa/shortest-path.md) | Dijkstra, Bellman-Ford, Floyd-Warshall, 0-1 BFS |
| [Minimum Spanning Tree](08-dsa/mst.md) | Prim, Kruskal, cut property, applications |

### 09 · System Design & Architecture
**Concepts**
| File | Focus |
|---|---|
| [System Design Fundamentals](09-system-design/fundamentals.md) | Scaling, LB, caching, CAP/PACELC, estimation, latency numbers |
| [Data Layer Design](09-system-design/data-layer.md) | SQL vs NoSQL, ACID/BASE, replication, sharding, polyglot |
| [Distributed Systems Concepts](09-system-design/distributed-systems.md) | Raft/Paxos, locking, clocks, consistent hashing |
| [Reliability, Resilience & Operations](09-system-design/ops-reliability.md) | Nines, failover, DR (RTO/RPO), deploys, chaos, load testing |
| [Security Architecture](09-system-design/security-architecture.md) | Zero-trust, encryption, KMS/secrets, OWASP API Top 10, STRIDE |
| [Observability & Monitoring Design](09-system-design/observability-design.md) | OTel pipeline, SLO/error budgets, alerting design |

**Real-World Design Problems**
| File | Problem |
|---|---|
| [URL Shortener](09-system-design/design-problems/url-shortener.md) | TinyURL / bit.ly |
| [Chat System](09-system-design/design-problems/chat-system.md) | WhatsApp / Slack |
| [Payment Gateway](09-system-design/design-problems/payment-gateway.md) | Ledger, idempotency, PCI |
| [Search Engine / Autocomplete](09-system-design/design-problems/search-engine.md) | Inverted index, typeahead |
| [Video Streaming Platform](09-system-design/design-problems/video-streaming.md) | YouTube / Netflix |
| [E-Commerce Platform](09-system-design/design-problems/ecommerce.md) | Amazon, inventory, checkout |
| [Social Media Feed](09-system-design/design-problems/social-media-feed.md) | Twitter / Instagram fan-out |
| [Distributed Rate Limiter](09-system-design/design-problems/rate-limiter.md) | Token/leaky bucket, Redis |
| [Distributed Caching System](09-system-design/design-problems/distributed-cache.md) | Consistent hashing, stampede |
| [Distributed Message Queue](09-system-design/design-problems/message-queue.md) | Kafka-like log storage |
| [More Enterprise-Scale Problems (15+ yrs)](09-system-design/design-problems/more-enterprise-problems.md) | Ride-sharing, Dropbox, feature flags, scheduler, ad-serving, recommender, collaborative editor (CRDT) |

### 10 · Tools & Development
| File | Focus |
|---|---|
| [GitHub Copilot & AI Coding Assistants](10-tools/github-copilot.md) | Prompting, limits, IP/security, agentic coding |
| [IntelliJ IDEA](10-tools/intellij.md) | Refactoring, debugging, profiling, shortcuts |
| [OpenHTMLtoPDF & HTML→PDF](10-tools/openhtml-to-pdf.md) | Fonts, CSS support, templating, alternatives |

### 11 · Additional Topics (cross-cutting & emerging)
| File | Focus |
|---|---|
| [Complementary & Emerging Technologies](11-additional-topics/emerging-tech.md) | gRPC, Quarkus/Micronaut, Keycloak, Vault, Temporal, Flyway, vector DBs/RAG |
| [DevSecOps & Secure SDLC](11-additional-topics/devsecops.md) | SAST/DAST/SCA, SBOM/SLSA, policy-as-code, supply chain |
| [Cloud Security Patterns](11-additional-topics/cloud-security.md) | IAM, network, KMS, CSPM, misconfigurations |
| [Advanced Observability Patterns](11-additional-topics/observability-patterns.md) | OTel deep dive, propagation, sampling, eBPF |
| [Cloud Cost Optimization & FinOps](11-additional-topics/cost-optimization.md) | Rightsizing, spot, tiering, K8s cost |
| [Compliance & Regulatory](11-additional-topics/compliance-regulatory.md) | GDPR, HIPAA, PCI-DSS, SOC 2, data residency |
| [Modern Architecture (2024–2026)](11-additional-topics/modern-architecture-2024-2026.md) | Platform engineering, GitOps, cell-based, WASM, AI-native |

### 12 · Behavioral & Leadership
| File | Focus |
|---|---|
| [Behavioral & Leadership Interviews](12-behavioral/behavioral-leadership.md) | STAR, leadership principles, conflict, mentoring, staff+ scope |

---

## 🧭 Suggested Study Path

```
Week 1–2 : 00-getting-started + your core language (Java) + DSA complexity/arrays/strings
Week 3–6 : DSA core (linked lists → trees → graphs → DP) alongside Spring/backend
Week 7–9 : Databases + messaging + APIs/auth
Week 10–12: System design fundamentals + all 11 design problems
Ongoing  : Frontend / DevOps-cloud (to your role), behavioral, mock interviews
```

See [the full study plans](00-getting-started/how-to-use.md) for 12-week intensive and 6-month steady tracks.

---

## ℹ️ Notes

- **"Wendi authentication"** is not a standard industry term. It is covered as **WS-Security / WS-Federation** (SOAP-era enterprise SSO) in [auth-patterns.md](04-apis-auth/auth-patterns.md), alongside Basic auth, M2M (client-credentials, mTLS, signed JWT), SAML, and passkeys. If you meant a specific proprietary scheme, let me know.
- **Thymeleaf** (templating) is covered in [thymeleaf.md](06-frontend/thymeleaf.md) (the request listed "Thymeleam").
- Code examples default to **Java**; frontend topics use **JS/TS**, IaC topics use **HCL/YAML**, etc.
- Content reflects technology and best practices **current through 2026** (Java 21, Spring Boot 3.x, Angular 17–21, Kafka KRaft, OAuth 2.1, etc.).

---

*A living document — extend, correct, and add notes as you study. Good luck with your interviews! 🚀*
