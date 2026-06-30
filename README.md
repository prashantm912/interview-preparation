# 🎯 The Complete Software Engineering Interview Preparation Guide

> A comprehensive, multi-technology interview-prep resource spanning **0–2 years → 15+ years** experience.
> Every topic is answered at four levels — 🟢 Basic, 🟡 Intermediate, 🟠 Advanced, 🔴 Expert — mixing **theory**, **practical scenarios**, and **coding** questions with full solutions, ASCII diagrams, complexity analysis, version differences, and real-world case studies.

---

## 📊 At a Glance

| Metric | Count |
|---|---|
| Topic documents | **129** |
| Sections | **14** |
| Interview questions (Q&A) | **~6,247** |
| Coding problems with full Java (DSA) | **~936** |
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

**Coverage tiers:** *Deep* topics (core: Java, Spring, DSA, System Design, messaging, databases, security, AI/ML) are exhaustive. *Solid* topics (e.g. ExtJS, SOAP, EclipseLink, niche tools) get full level coverage with focused depth.

---

## 🗂️ Table of Contents

> **Column guide:** Theory = conceptual/architectural questions · Practical = scenario/implementation · Coding = hands-on coding problems (with solutions) · Behavioral = leadership/soft-skills · Total = sum. DSA files are all coding problems — counts reflect solved Java implementations.

### 01 · Backend & Enterprise Java
| File | Tier | Focus | Theory | Practical | Coding | Behavioral | Total |
|---|---|---|:---:|:---:|:---:|:---:|:---:|
| [Java Core (8/11/17/21)](01-backend-java/java-core.md) | Deep | Lambdas, Streams, records, sealed, virtual threads, version-by-version diffs | 50 | 39 | 39 | 3 | **131** |
| [Java Concurrency & Multithreading](01-backend-java/java-concurrency.md) | Deep | Executors, JMM/happens-before, locks, CompletableFuture, virtual threads | 69 | 31 | 30 | 2 | **132** |
| [JVM Internals & Performance Tuning](01-backend-java/jvm-internals.md) | Deep | Memory areas, GC (G1/ZGC/Shenandoah), JIT, leak/dump/profiling, GraalVM | 14 | 9 | 4 | 1 | **28** |
| [J2EE / Jakarta EE](01-backend-java/jakarta-ee.md) | Solid | Servlets, EJB, CDI, JPA/JTA, JAX-RS/WS, javax→jakarta migration | 47 | 38 | 36 | 3 | **124** |
| [Spring Framework Core](01-backend-java/spring-core.md) | Deep | IoC/DI, bean lifecycle, AOP proxies, @Transactional internals | 54 | 44 | 34 | 3 | **135** |
| [Spring Boot](01-backend-java/spring-boot.md) | Deep | Auto-config, Boot 2 vs 3, MVC/WebFlux, testing, Actuator, native image | 46 | 47 | 34 | 3 | **130** |
| [Spring Security (Advanced)](01-backend-java/spring-security.md) | Deep | Filter chain, OAuth2/JWT, method security, ACL, reactive security | 16 | 9 | 4 | 1 | **30** |
| [Spring Cloud](01-backend-java/spring-cloud.md) | Deep | Config, discovery, Gateway, OpenFeign, Resilience4j, tracing | 15 | 9 | 6 | 1 | **31** |
| [Hibernate & JPA](01-backend-java/hibernate-jpa.md) | Deep | Mappings, N+1, caching, locking, JPQL/Criteria, performance | 48 | 51 | 32 | 3 | **134** |
| [EclipseLink](01-backend-java/eclipselink.md) | Solid | JPA RI, caching, weaving, MOXy, vs Hibernate | 44 | 45 | 32 | 3 | **124** |

### 02 · Microservices
| File | Tier | Focus | Theory | Practical | Coding | Behavioral | Total |
|---|---|---|:---:|:---:|:---:|:---:|:---:|
| [Microservices Architecture Patterns](02-microservices/microservices-patterns.md) | Deep | DDD, decomposition, CQRS, BFF, contract testing, when not to | 41 | 24 | 23 | 4 | **92** |
| [Resilience Patterns](02-microservices/resilience-patterns.md) | Deep | Circuit breaker, retry/backoff, bulkhead, rate limit, chaos | 51 | 44 | 21 | 5 | **121** |
| [Saga & Distributed Transactions](02-microservices/saga-distributed-tx.md) | Deep | Orchestration vs choreography, outbox+CDC, exactly-once | 19 | 8 | 4 | 1 | **32** |
| [Service Mesh](02-microservices/service-mesh.md) | Solid | Envoy/Istio/Linkerd, mTLS, ambient/eBPF, vs libraries | 10 | 8 | 2 | 1 | **21** |

### 03 · Message Queues & Streaming
| File | Tier | Focus | Theory | Practical | Coding | Behavioral | Total |
|---|---|---|:---:|:---:|:---:|:---:|:---:|
| [Apache Kafka](03-messaging/kafka.md) | Deep | Partitions, ISR, EOS/transactions, Streams, Connect, KRaft, tuning | 45 | 33 | 7 | 2 | **87** |
| [RabbitMQ](03-messaging/rabbitmq.md) | Deep | Exchanges, acks/prefetch, DLX, quorum queues, vs Kafka | 39 | 26 | 11 | 3 | **79** |
| [ActiveMQ](03-messaging/activemq.md) | Solid | JMS, Artemis vs Classic, persistence, HA | 39 | 28 | 6 | 2 | **75** |
| [Redis](03-messaging/redis.md) | Deep | Data types, persistence, cluster/sentinel, caching, locks | 41 | 28 | 19 | 2 | **90** |
| [Event-Driven Architecture](03-messaging/event-driven-architecture.md) | Deep | Event sourcing, CQRS, delivery semantics, outbox, schema evolution | 18 | 8 | 6 | 1 | **33** |

### 04 · APIs & Authentication
| File | Tier | Focus | Theory | Practical | Coding | Behavioral | Total |
|---|---|---|:---:|:---:|:---:|:---:|:---:|
| [REST API Design](04-apis-auth/rest.md) | Deep | Methods, idempotency, versioning, pagination, RFC 7807, caching | 48 | 50 | 28 | 3 | **129** |
| [SOAP & Web Services](04-apis-auth/soap.md) | Solid | WSDL, WS-*, JAX-WS, faults, REST vs SOAP | 42 | 32 | 7 | 2 | **83** |
| [GraphQL](04-apis-auth/graphql.md) | Deep | Schema, resolvers, N+1/DataLoader, federation, security | 19 | 7 | 6 | 1 | **33** |
| [OpenAPI / Swagger](04-apis-auth/openapi-swagger.md) | Solid | Spec, contract-first, springdoc, codegen, governance | 25 | 19 | 23 | 3 | **70** |
| [MapStruct](04-apis-auth/mapstruct.md) | Solid | Compile-time mapping, DTO↔entity, vs ModelMapper/Dozer | 39 | 50 | 29 | 4 | **122** |
| [OAuth 2.0 & OpenID Connect](04-apis-auth/oauth2-oidc.md) | Deep | Grant types, PKCE, tokens, OIDC, OAuth 2.1, attacks | 38 | 32 | 12 | 3 | **85** |
| [JWT (Advanced Patterns)](04-apis-auth/jwt.md) | Deep | JWS/JWE, alg=none, JWKS, refresh rotation, revocation | 18 | 9 | 4 | 1 | **32** |
| [API Gateway Patterns](04-apis-auth/api-gateway.md) | Deep | Routing, auth offload, rate limiting, BFF, vs mesh | 16 | 10 | 5 | 1 | **32** |
| [Auth Patterns (Basic, M2M, mTLS, WS-Security)](04-apis-auth/auth-patterns.md) | Deep | API keys, client creds, mTLS, SAML/WS-Federation, passkeys, zero-trust | 45 | 26 | 8 | 2 | **81** |

### 05 · Databases
| File | Tier | Focus | Theory | Practical | Coding | Behavioral | Total |
|---|---|---|:---:|:---:|:---:|:---:|:---:|
| [SQL Fundamentals](05-databases/sql-fundamentals.md) | Deep | Joins, window functions, CTEs, isolation, normalization, SQL puzzles | 58 | 45 | 29 | 3 | **135** |
| [Oracle Database](05-databases/oracle.md) | Solid | PL/SQL, CBO/hints, partitioning, RAC, AWR | 40 | 57 | 23 | 3 | **123** |
| [PostgreSQL](05-databases/postgresql.md) | Deep | MVCC, vacuum, index types, JSONB, replication, EXPLAIN | 53 | 44 | 40 | 2 | **139** |
| [MySQL](05-databases/mysql.md) | Deep | InnoDB, locks, replication, binlog/CDC, online schema change | 16 | 10 | 5 | 1 | **32** |
| [MongoDB](05-databases/mongodb.md) | Deep | Document modeling, aggregation, sharding, transactions | 43 | 25 | 17 | 3 | **88** |
| [Query Optimization & Indexing](05-databases/query-optimization-indexing.md) | Deep | Index internals, composite order, plans, slow-query tuning | 38 | 33 | 9 | 2 | **82** |
| [Sharding & Replication Strategies](05-databases/sharding-replication.md) | Deep | Shard keys, topologies, quorum, distributed SQL | 17 | 8 | 4 | 1 | **30** |
| [Connection Pooling & HikariCP](05-databases/connection-pooling-hikaricp.md) | Solid | Pool sizing, leak detection, PgBouncer, serverless | 11 | 8 | 3 | 1 | **23** |

### 06 · UI & Frontend
| File | Tier | Focus | Theory | Practical | Coding | Behavioral | Total |
|---|---|---|:---:|:---:|:---:|:---:|:---:|
| [HTML & CSS](06-frontend/html-css.md) | Solid | Semantics, a11y, Flexbox/Grid, specificity, modern CSS | 42 | 17 | 22 | 3 | **84** |
| [JavaScript](06-frontend/javascript.md) | Deep | Closures, prototypes, event loop, async, debounce/throttle | 64 | 22 | 40 | 2 | **128** |
| [TypeScript](06-frontend/typescript.md) | Deep | Generics, utility/conditional/mapped types, narrowing | 61 | 29 | 30 | 2 | **122** |
| [Angular (v17–21)](06-frontend/angular.md) | Deep | DI, RxJS, signals, control flow, change detection, SSR | 65 | 26 | 35 | 2 | **128** |
| [React](06-frontend/react.md) | Deep | Hooks, reconciliation/fiber, memoization, Server Components | 44 | 27 | 23 | 2 | **96** |
| [Vue.js](06-frontend/vue.md) | Solid | Reactivity, Composition API, Pinia, vs React/Angular | 35 | 28 | 10 | 2 | **75** |
| [ExtJS (Sencha)](06-frontend/extjs.md) | Solid | Class system, stores, MVVM, migrating off | 61 | 36 | 19 | 2 | **118** |
| [Thymeleaf](06-frontend/thymeleaf.md) | Solid | Natural templating, Spring integration, vs JSP | 9 | 7 | 3 | 1 | **20** |
| [State Management (NgRx & beyond)](06-frontend/state-management-ngrx.md) | Deep | Redux pattern, Effects/Selectors, SignalStore, vs RTK/Zustand | 55 | 29 | 35 | 3 | **122** |
| [Frontend Security](06-frontend/frontend-security.md) | Deep | XSS, CSRF, CSP, CORS, token storage, supply chain | 41 | 26 | 21 | 2 | **90** |

### 07 · DevOps & Cloud
| File | Tier | Focus | Theory | Practical | Coding | Behavioral | Total |
|---|---|---|:---:|:---:|:---:|:---:|:---:|
| [Git & GitHub](07-devops-cloud/git-github.md) | Solid | Internals, rebase/merge, branching strategies, recovery | 31 | 32 | 17 | 2 | **82** |
| [GitHub Actions](07-devops-cloud/github-actions.md) | Solid | Workflows, matrix, OIDC, reusable, caching, security | 43 | 46 | 27 | 4 | **120** |
| [Jenkins](07-devops-cloud/jenkins.md) | Solid | Declarative pipelines, shared libs, agents, K8s | 10 | 9 | 3 | 1 | **23** |
| [GitLab CI/CD](07-devops-cloud/gitlab-ci.md) | Solid | Stages, runners, DAG, built-in scanning, Auto DevOps | 12 | 5 | 2 | 1 | **20** |
| [Docker & Containers](07-devops-cloud/docker.md) | Deep | Layers, multi-stage, networking, security, BuildKit | 66 | 37 | 23 | 2 | **128** |
| [Kubernetes](07-devops-cloud/kubernetes.md) | Deep | Workloads, services/ingress, HPA, RBAC, operators, troubleshooting | 60 | 30 | 34 | 2 | **126** |
| [Helm](07-devops-cloud/helm.md) | Solid | Charts, templating, releases, vs Kustomize | 30 | 31 | 18 | 4 | **83** |
| [Terraform & IaC](07-devops-cloud/terraform.md) | Deep | State, modules, plan/apply, drift, OpenTofu | 14 | 9 | 6 | 1 | **30** |
| [AWS](07-devops-cloud/aws.md) | Deep | Core services, Well-Architected, serverless, IAM | 17 | 10 | 5 | 2 | **34** |
| [Microsoft Azure](07-devops-cloud/azure.md) | Solid | Core services, AKS, Entra ID, Bicep | 45 | 51 | 25 | 3 | **124** |
| [Google Cloud Platform](07-devops-cloud/gcp.md) | Solid | Core services, GKE, Cloud Run, BigQuery | 11 | 8 | 2 | 1 | **22** |
| [SonarQube & Code Quality](07-devops-cloud/sonarqube.md) | Solid | Quality gates, coverage, debt, branch/PR analysis | 45 | 47 | 25 | 4 | **121** |
| [Prometheus & Grafana](07-devops-cloud/prometheus-grafana.md) | Deep | Metric types, PromQL, alerting, cardinality, SLOs | 65 | 30 | 29 | 2 | **126** |
| [Observability (Metrics/Logs/Traces)](07-devops-cloud/observability.md) | Deep | OpenTelemetry, tracing, RED/USE, SLI/SLO, incident debugging | 42 | 25 | 20 | 3 | **90** |

### 08 · Data Structures & Algorithms
> Start with the [complexity cheat-sheet](08-dsa/complexity-analysis.md). **~936+ fully-solved coding problems** in Java across these files.
> DSA files contain solved coding problems, not Q&A — the **Coding** column shows Java solutions per file.

| File | Focus | Theory | Practical | Coding | Behavioral | Total |
|---|---|:---:|:---:|:---:|:---:|:---:|
| [Time & Space Complexity Analysis](08-dsa/complexity-analysis.md) | Big-O/Ω/Θ, Master Theorem, amortized analysis | — | — | 45 solved | — | **45** |
| [Arrays & Strings](08-dsa/arrays-strings.md) | Prefix sums, Kadane, two-pointer, KMP, intervals | — | — | 50 solved | — | **50** |
| [Linked Lists](08-dsa/linked-lists.md) | Reverse, cycle detection, merge-k, LRU | — | — | 49 solved | — | **49** |
| [Stacks & Queues](08-dsa/stacks-queues.md) | Monotonic stack/deque, histogram, RPN | — | — | 50 solved | — | **50** |
| [Hash Tables & Maps](08-dsa/hashing.md) | Collisions, two-sum family, top-k, LRU/LFU | — | — | 49 solved | — | **49** |
| [Trees (Binary, BST, AVL, B-Trees)](08-dsa/trees.md) | Traversals, LCA, serialize, balancing, B+-trees | — | — | 51 solved | — | **51** |
| [Heaps & Priority Queues](08-dsa/heaps-priority-queues.md) | Heapsort, kth-largest, median stream, merge-k | — | — | 47 solved | — | **47** |
| [Trie, Segment Tree & Fenwick Tree](08-dsa/tries-segment-fenwick.md) | Autocomplete, range queries, inversions | — | — | 49 solved | — | **49** |
| [Graphs](08-dsa/graphs.md) | BFS/DFS, components, topo, islands, clone | — | — | 52 solved | — | **52** |
| [Sorting Algorithms](08-dsa/sorting.md) | Quick/merge/heap/radix, stability, TimSort | — | — | 47 solved | — | **47** |
| [Searching Algorithms](08-dsa/searching.md) | Binary search on answer, rotated, 2-sorted median | — | — | 51 solved | — | **51** |
| [Recursion & Backtracking](08-dsa/recursion-backtracking.md) | Subsets/permutations, N-Queens, Sudoku | — | — | 50 solved | — | **50** |
| [Dynamic Programming (1D/2D/opt)](08-dsa/dynamic-programming.md) | Knapsack, LCS, LIS, interval/tree/bitmask DP | — | — | 53 solved | — | **53** |
| [Greedy Algorithms](08-dsa/greedy.md) | Interval scheduling, Huffman, exchange argument | — | — | 51 solved | — | **51** |
| [Divide & Conquer](08-dsa/divide-conquer.md) | Closest pair, inversions, Karatsuba, skyline | — | — | 49 solved | — | **49** |
| [Sliding Window & Two Pointers](08-dsa/sliding-window-two-pointers.md) | Min window, 3-sum, container with water | — | — | 51 solved | — | **51** |
| [Union-Find & Topological Sort](08-dsa/union-find-topological.md) | DSU, Kruskal, Kahn, alien dictionary | — | — | 51 solved | — | **51** |
| [Shortest Path](08-dsa/shortest-path.md) | Dijkstra, Bellman-Ford, Floyd-Warshall, 0-1 BFS | — | — | 48 solved | — | **48** |
| [Minimum Spanning Tree](08-dsa/mst.md) | Prim, Kruskal, cut property, applications | — | — | 43 solved | — | **43** |

### 09 · System Design & Architecture
**Concepts**
| File | Tier | Focus | Theory | Practical | Coding | Behavioral | Total |
|---|---|---|:---:|:---:|:---:|:---:|:---:|
| [System Design Fundamentals](09-system-design/fundamentals.md) | Deep | Scaling, LB, caching, CAP/PACELC, estimation, latency numbers | 19 | 11 | 5 | 1 | **36** |
| [Data Layer Design](09-system-design/data-layer.md) | Deep | SQL vs NoSQL, ACID/BASE, replication, sharding, polyglot | 17 | 10 | 4 | 1 | **32** |
| [Distributed Systems Concepts](09-system-design/distributed-systems.md) | Deep | Raft/Paxos, locking, clocks, consistent hashing | 17 | 8 | 4 | 1 | **30** |
| [Consensus & Coordination](09-system-design/consensus-coordination.md) | Deep | Paxos, Raft, ZK/etcd/Consul, locks, quorums, fencing, split-brain | 16 | 11 | 4 | 1 | **32** |
| [Caching Strategies & CDN](09-system-design/caching-strategies.md) | Deep | Cache-aside vs write-through/behind, eviction, stampede, multi-tier, CDN | 19 | 11 | 5 | 1 | **36** |
| [API Design at Scale](09-system-design/api-design-at-scale.md) | Deep | Idempotency, pagination, versioning, rate limiting, BFF, errors | 16 | 13 | 5 | 1 | **35** |
| [Back-of-the-Envelope Estimation](09-system-design/estimation-capacity-planning.md) | Deep | QPS/storage/bandwidth math, latency numbers, capacity planning | 14 | 15 | 5 | 2 | **36** |
| [Reliability, Resilience & Operations](09-system-design/ops-reliability.md) | Deep | Nines, failover, DR (RTO/RPO), deploys, chaos, load testing | 15 | 9 | 5 | 1 | **30** |
| [Security Architecture](09-system-design/security-architecture.md) | Deep | Zero-trust, encryption, KMS/secrets, OWASP API Top 10, STRIDE | 17 | 9 | 5 | 2 | **33** |
| [Observability & Monitoring Design](09-system-design/observability-design.md) | Deep | OTel pipeline, SLO/error budgets, alerting design | 15 | 10 | 4 | 1 | **30** |

**Real-World Design Problems**
> Interview-grade case studies (requirements → scale estimate → design → deep-dive → tagged Q&A). Use them to practice the canonical framework end-to-end.

| File | Problem | Theory | Practical | Coding | Behavioral | Total |
|---|---|:---:|:---:|:---:|:---:|:---:|
| [URL Shortener](09-system-design/design-problems/url-shortener.md) | TinyURL / bit.ly | *case study* | *case study* | *case study* | — | — |
| [Chat System](09-system-design/design-problems/chat-system.md) | WhatsApp / Slack | *case study* | *case study* | *case study* | — | — |
| [Payment Gateway](09-system-design/design-problems/payment-gateway.md) | Ledger, idempotency, PCI | *case study* | *case study* | *case study* | — | — |
| [Search Engine / Autocomplete](09-system-design/design-problems/search-engine.md) | Inverted index, typeahead | *case study* | *case study* | *case study* | — | — |
| [Video Streaming Platform](09-system-design/design-problems/video-streaming.md) | YouTube / Netflix | *case study* | *case study* | *case study* | — | — |
| [E-Commerce Platform](09-system-design/design-problems/ecommerce.md) | Amazon, inventory, checkout | *case study* | *case study* | *case study* | — | — |
| [Social Media Feed](09-system-design/design-problems/social-media-feed.md) | Twitter / Instagram fan-out | *case study* | *case study* | *case study* | — | — |
| [Distributed Rate Limiter](09-system-design/design-problems/rate-limiter.md) | Token/leaky bucket, Redis | *case study* | *case study* | *case study* | — | — |
| [Distributed Caching System](09-system-design/design-problems/distributed-cache.md) | Consistent hashing, stampede | *case study* | *case study* | *case study* | — | — |
| [Distributed Message Queue](09-system-design/design-problems/message-queue.md) | Kafka-like log storage | *case study* | *case study* | *case study* | — | — |
| [Notification System](09-system-design/design-problems/notification-system.md) | Push / Email / SMS fan-out | 4 | 7 | 2 | 2 | **15** |
| [Ride-Sharing Service](09-system-design/design-problems/ride-sharing.md) | Uber / Lyft, geo matching | 6 | 5 | 2 | 1 | **14** |
| [Collaborative Editor](09-system-design/design-problems/collaborative-editor.md) | Google Docs, OT/CRDT | 7 | 6 | 1 | 1 | **15** |
| [File Storage & Sync](09-system-design/design-problems/file-storage-sync.md) | Dropbox / Google Drive, delta sync | 9 | 4 | 1 | 1 | **15** |
| [Search Autocomplete / Typeahead](09-system-design/design-problems/typeahead-autocomplete.md) | Trie + ranked suggestions | 7 | 6 | 1 | 1 | **15** |
| [Distributed Web Crawler](09-system-design/design-problems/web-crawler.md) | Frontier, politeness, dedup | 7 | 5 | 2 | 1 | **15** |
| [Ticket Booking System](09-system-design/design-problems/ticket-booking.md) | Ticketmaster / BookMyShow | 6 | 6 | 2 | 1 | **15** |
| [Stock Exchange / Matching Engine](09-system-design/design-problems/stock-exchange.md) | Order book, low latency | 8 | 4 | 2 | 1 | **15** |
| [Ad Click Aggregation](09-system-design/design-problems/ad-click-aggregation.md) | Real-time streaming pipeline | 6 | 5 | 2 | 2 | **15** |
| [Distributed Job Scheduler](09-system-design/design-problems/distributed-job-scheduler.md) | Cron at scale, leader election | 8 | 5 | 2 | 1 | **16** |
| [Real-Time Gaming Leaderboard](09-system-design/design-problems/leaderboard.md) | Sorted sets, sharded ranking | 6 | 7 | 1 | 1 | **15** |
| [Object Storage Service](09-system-design/design-problems/object-storage.md) | S3-like blob store, erasure coding | 8 | 4 | 2 | 1 | **15** |
| [More Enterprise-Scale Problems (15+ yrs)](09-system-design/design-problems/more-enterprise-problems.md) | Feature flags, recommender, scheduler, ad-serving, more | 13 | 11 | 5 | 1 | **30** |

### 10 · Tools & Development
| File | Focus | Theory | Practical | Coding | Behavioral | Total |
|---|---|:---:|:---:|:---:|:---:|:---:|
| [GitHub Copilot & AI Coding Assistants](10-tools/github-copilot.md) | Prompting, limits, IP/security, agentic coding | 38 | 63 | 19 | 3 | **123** |
| [IntelliJ IDEA](10-tools/intellij.md) | Refactoring, debugging, profiling, shortcuts | 9 | 12 | 2 | 1 | **24** |
| [OpenHTMLtoPDF & HTML→PDF](10-tools/openhtml-to-pdf.md) | Fonts, CSS support, templating, alternatives | 9 | 9 | 2 | 1 | **21** |

### 11 · Additional Topics (cross-cutting & emerging)
| File | Focus | Theory | Practical | Coding | Behavioral | Total |
|---|---|:---:|:---:|:---:|:---:|:---:|
| [Complementary & Emerging Technologies](11-additional-topics/emerging-tech.md) | gRPC, Quarkus/Micronaut, Keycloak, Vault, Temporal, Flyway, vector DBs/RAG | 14 | 10 | 4 | 2 | **30** |
| [DevSecOps & Secure SDLC](11-additional-topics/devsecops.md) | SAST/DAST/SCA, SBOM/SLSA, policy-as-code, supply chain | 12 | 11 | 4 | 2 | **29** |
| [Cloud Security Patterns](11-additional-topics/cloud-security.md) | IAM, network, KMS, CSPM, misconfigurations | 16 | 10 | 4 | 2 | **32** |
| [Advanced Observability Patterns](11-additional-topics/observability-patterns.md) | OTel deep dive, propagation, sampling, eBPF | 11 | 7 | 3 | 1 | **22** |
| [Cloud Cost Optimization & FinOps](11-additional-topics/cost-optimization.md) | Rightsizing, spot, tiering, K8s cost | 10 | 6 | 2 | 2 | **20** |
| [Compliance & Regulatory](11-additional-topics/compliance-regulatory.md) | GDPR, HIPAA, PCI-DSS, SOC 2, data residency | 10 | 7 | 2 | 1 | **20** |
| [Modern Architecture (2024–2026)](11-additional-topics/modern-architecture-2024-2026.md) | Platform engineering, GitOps, cell-based, WASM, AI-native | 18 | 9 | 5 | 1 | **33** |

### 12 · Behavioral & Leadership
| File | Focus | Theory | Practical | Coding | Behavioral | Total |
|---|---|:---:|:---:|:---:|:---:|:---:|
| [Behavioral & Leadership Interviews](12-behavioral/behavioral-leadership.md) | STAR, leadership principles, conflict, mentoring, staff+ scope | 29 | 18 | 12 | 26 | **85** |

### 13 · AI / ML for Engineers
> A new section covering the AI surface every senior engineer is now expected to know: LLM internals, RAG/vector search, prompt engineering, AI agents, ML system design, and GenAI security/governance. Current through 2026.

| File | Tier | Focus | Theory | Practical | Coding | Behavioral | Total |
|---|---|---|:---:|:---:|:---:|:---:|:---:|
| [LLM Fundamentals](13-ai-ml/llm-fundamentals.md) | Deep | Transformers, tokens, embeddings, context, sampling, fine-tuning, model families, hallucination | 19 | 11 | 5 | 1 | **36** |
| [RAG & Vector Search](13-ai-ml/rag-systems.md) | Deep | Chunking, embeddings, vector DBs, ANN (HNSW/IVF), hybrid+rerank, RAG eval, multi-tenancy | 16 | 15 | 3 | 2 | **36** |
| [Prompt Engineering & LLM App Patterns](13-ai-ml/prompt-engineering.md) | Deep | Zero/few-shot, CoT, ReAct, JSON/function calling, prompt injection, evals, cost control | 16 | 13 | 1 | 2 | **32** |
| [AI Agents & Orchestration](13-ai-ml/ai-agents.md) | Deep | Agent loop, tools/MCP, planning, multi-agent, memory, frameworks, observability | 20 | 12 | 3 | 1 | **36** |
| [ML System Design & MLOps](13-ai-ml/ml-system-design.md) | Deep | Training/serving split, feature stores, vLLM/Triton, drift, A/B & shadow, GPU scaling | 20 | 7 | 4 | 1 | **32** |
| [GenAI Security, Safety & Governance](13-ai-ml/genai-security-governance.md) | Deep | Prompt injection, jailbreaks, OWASP LLM Top 10, red-teaming, EU AI Act, NIST AI RMF | 19 | 12 | 4 | 1 | **36** |

---

## 🧭 Suggested Reading Order

A first-pass **reading order through the folders** — what to open, in what sequence. For the *time-boxed schedule* (hours per week, how to interleave tracks, spaced repetition, mock cadence) follow the [12-week intensive / 6-month steady plans](00-getting-started/how-to-use.md). Complementary, not competing: this is *what to read*; that is *when and how*.

```
Week 1–2 : 00-getting-started + your core language (Java) + DSA complexity/arrays/strings
Week 3–6 : DSA core (linked lists → trees → graphs → DP) alongside Spring/backend
Week 7–9 : Databases + messaging + APIs/auth
Week 10–12: System design fundamentals + all design problems
Week 13–14: AI/ML for engineers (13-ai-ml)
Ongoing  : Frontend / DevOps-cloud (to your role), behavioral, mock interviews
```

The week labels above align with the 12-week intensive plan in [how-to-use.md](00-getting-started/how-to-use.md); AI/ML (weeks 13–14) extends it for AI-touching or senior+ roles. See that file for the full intensive and 6-month steady schedules, plus [study-resources.md](00-getting-started/study-resources.md) for which book/platform to use at each phase.

---

## ℹ️ Notes

- **"Wendi authentication"** is not a standard industry term. It is covered as **WS-Security / WS-Federation** (SOAP-era enterprise SSO) in [auth-patterns.md](04-apis-auth/auth-patterns.md), alongside Basic auth, M2M (client-credentials, mTLS, signed JWT), SAML, and passkeys.
- **Thymeleaf** (templating) is covered in [thymeleaf.md](06-frontend/thymeleaf.md) (the request listed "Thymeleam").
- Code examples default to **Java**; frontend topics use **JS/TS**, IaC topics use **HCL/YAML**, AI topics use **Python**.
- Content reflects technology and best practices **current through 2026** (Java 21, Spring Boot 3.x, Angular 17–21, Kafka KRaft, OAuth 2.1, GPT-5/Claude 4.x/Llama 3, vLLM, MCP, etc.).
- Most topic files have been deeply expanded with multiple supplemental question sets covering deeper theory, practical operations, hands-on coding, and senior/staff-level behavioral angles.

---

*A living document — extend, correct, and add notes as you study. Good luck with your interviews! 🚀*
