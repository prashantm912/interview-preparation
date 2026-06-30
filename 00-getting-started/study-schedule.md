# Interview Prep Schedule — 8 Weeks, 1–2 hrs/day
**Target: 15+ yrs Java/Angular/DevOps → Product company senior engineering role (India)**

**Time allocation:**
- **60%** System Design (4.8 weeks) — 35K–40K words + 6 case studies
- **20%** AI/ML (1.6 weeks) — 15K words + 1 mini-project
- **10%** Additional Topics (0.8 weeks) — 10K–15K words  
- **5%** Behavioral (0.4 weeks) — 2–3 mock stories
- **5%** Stack review (0.4 weeks) — Expert tiers only (skim)

**Total:** ~56–64 hours, ~1.5 hrs/day. **Pairs with 3–4 lunch-break slots for system design sketches.**

> **Companion docs:**
> - `13-ai-ml/rag-mini-project-java.md` — the Week 5 AI/ML build, in Java + Spring Boot + LangChain4j (use this; the Python version is `13-ai-ml/rag-mini-project.md`).
> - `14-hands-on-projects/hands-on-projects.md` — Java build projects for system design, DSA, and architecture, with a week-by-week integration table. Pick ~5–6 weighted to your gaps and slot them into the weeks below.
> - `14-hands-on-projects/learning-projects.md` — **~110 ground-up "build X from scratch to understand Y" projects** across 13 tracks (foundations, concurrency, DS, DB internals, networking, distributed systems, caching, messaging, architecture, observability, security, performance, AI/ML). This is the long-game *learning* curriculum (6–18 months), separate from this 8-week interview sprint — use it to close real internals gaps, not to cram.
> - `07-devops-cloud/bicep.md` — closes your IaC gap.

---

## Week 1: System Design Foundations + Stack Review (10–12 hrs)

**Goal:** Build SD vocabulary, refresh expert-tier knowledge in your existing stack.

| Day | Topic | File(s) | Time | Notes |
|---|---|---|---|---|
| Mon–Tue | **System Design Fundamentals** | `09-system-design/fundamentals.md` | 2.5 hrs | Read all 8K words. Capacity planning, estimation, telemetry, consistency models. This is the *vocabulary* for the next 4 weeks. |
| Wed | **Distributed Systems Basics** | `09-system-design/distributed-systems.md` | 1.5 hrs | CAP, eventual consistency, quorum, split-brain. Essential mental models. |
| Thu | **Spring Boot Expert Q's** | `01-backend-java/spring-boot.md` | 1 hr | Jump to 🔴 Expert tier (Q25+). Actuators, observability hooks, custom starters. (You know this; just fill gaps.) |
| Fri | **Kafka Expert Q's** | `03-messaging/kafka.md` | 1 hr | Jump to 🔴 Expert tier. Exactly-once semantics, rebalancing, ISR. |
| Sat | **PostgreSQL + Query Optimization** | `05-databases/postgresql.md` + `query-optimization-indexing.md` | 2 hrs | 🔴 tier: EXPLAIN ANALYZE, index strategies, statistics. Quick-refresh a day before using in designs. |
| Sun | *Rest or catch-up* | — | — | If you're ahead, read the first case study (Chat System). If behind, re-read fundamentals. |

**End of W1:** You have the SD language and have refreshed your stack's expert-tier corners.

---

## Week 2: System Design — Data Layer, Caching, API Design (10–12 hrs)

**Goal:** Master the components, understand trade-offs.

| Day | Topic | File(s) | Time | Notes |
|---|---|---|---|---|
| Mon–Tue | **Data Layer** | `09-system-design/data-layer.md` | 2.5 hrs | Partitioning, replication, failover, consistency guarantees. Blueprint for Week 3–4 case studies. |
| Wed–Thu | **Caching Strategies** | `09-system-design/caching-strategies.md` | 2 hrs | Bloom filters, LRU, write-through, invalidation, thundering herd. Every design uses this. |
| Fri | **API Design at Scale** | `09-system-design/api-design-at-scale.md` | 1.5 hrs | Versioning, pagination, rate limiting, contract evolution. Real-world interview tension. |
| Sat | **Observability Design** | `09-system-design/observability-design.md` | 1.5 hrs | Metrics vs logs vs traces. SLI/SLO. You live this with Prometheus/Grafana; nail the language. |
| Sun | *Sketch one design component* | *Choose one: leaderboard or typeahead from design-problems/* | 1–1.5 hrs | Pick the **simplest** case study (leaderboard or typeahead). Spend 45 min writing a one-page design (no docs, no code—just boxes and arrows). Time yourself. |

**End of W2:** You own the building blocks. You've done one tiny design sketch to build muscle memory.

---

## Week 3: System Design — Case Studies 1–3 (10–12 hrs)

**Goal:** Deep-dive 3 contrasting case studies. Each teaches different patterns.

**Pick these three:**
1. **Chat System** (`chat-system.md`, ~3.5K words) — real-time, event-driven, Kafka patterns
2. **Distributed Cache** (`distributed-cache.md`, ~2.8K words) — data layer, consistency, cache eviction
3. **Rate Limiter** (`rate-limiter.md`, ~2.5K words) — algorithms, distributed counters, trade-offs

| Day | Topic | File(s) | Time | Notes |
|---|---|---|---|---|
| Mon–Tue | **Chat System** | Design Problems: chat-system.md | 3 hrs | Read + sketch design. Pay attention to: WebSockets vs polling, message ordering, consistency guarantees. |
| Wed | **Distributed Cache** | Design Problems: distributed-cache.md | 2 hrs | Read. Cross-reference with caching strategies. Understand eviction + replication. |
| Thu–Fri | **Rate Limiter** | Design Problems: rate-limiter.md | 2.5 hrs | Read. Implement the token bucket algorithm in your head for talking points. |
| Sat | *Sketch two designs* | Chat System + Distributed Cache | 2 hrs | Spend 30 min each. Include: data model, API calls, failure modes. No perfectionism. |
| Sun | *Rest or stretch* | — | — | Re-read case study Q&A sections if uncertain. |

**End of W3:** You've internalized 3 real-world designs. You can *talk* through them end-to-end.

---

## Week 4: System Design — Case Studies 4–6 + Reliability (10–12 hrs)

**Goal:** Finish three more contrasting case studies; understand operations & reliability.

**Pick these three:**
1. **Search Engine** (`search-engine.md`, ~3.2K words) — indexing, ranking, distributed search
2. **Notification System** (`notification-system.md`, ~2.9K words) — fan-out, delivery guarantees, retries
3. **Payment Gateway** (`payment-gateway.md`, ~3.1K words) — exactly-once, idempotency, audit trail

| Day | Topic | File(s) | Time | Notes |
|---|---|---|---|---|
| Mon–Tue | **Search Engine** | Design Problems: search-engine.md | 2.5 hrs | Inverted index, ranking, distributed queries. May feel unfamiliar; that's the point. |
| Wed–Thu | **Notification System** | Design Problems: notification-system.md | 2.5 hrs | Fan-out patterns, retry logic, delivery guarantees. Core to Kafka expertise. |
| Fri | **Payment Gateway** | Design Problems: payment-gateway.md | 2 hrs | Idempotency keys, exactly-once semantics, auditing. Financial rigor. |
| Sat | **Ops & Reliability** | `09-system-design/ops-reliability.md` | 1.5 hrs | Deployment, rollback, SLO breach recovery. How you *run* the design. |
| Sun | *Sketch one full design from scratch* | *Leaderboard or Typeahead (or your own idea)* | 1.5 hrs | 60 min to design, 30 min to review against the guide. Build confidence. |

**End of W4:** You've read 6 designs + 1 reliability doc. You can sketch a system from scratch in 60 min. **This is your system design floor. Everything after is gravy.**

---

## Week 5: AI/ML Fundamentals + Project Setup (8–10 hrs)

**Goal:** Understand LLMs, RAG, agents. Start one mini-project.

| Day | Topic | File(s) | Time | Notes |
|---|---|---|---|---|
| Mon–Tue | **LLM Fundamentals** | `13-ai-ml/llm-fundamentals.md` | 2 hrs | Transformers, tokens, context windows, temperature, top-k. You're learning this for real. |
| Wed | **Prompt Engineering** | `13-ai-ml/prompt-engineering.md` | 1.5 hrs | Few-shot, chain-of-thought, instruction tuning. How to *talk* to LLMs in interviews. |
| Thu | **RAG Systems** | `13-ai-ml/rag-systems.md` | 1.5 hrs | Retrieval, embedding, vector stores, re-ranking. This is your "hands-on" production pattern. |
| Fri | **Project Setup** | *None (hands-on)* | 1.5 hrs | Clone/scaffold a **simple RAG app**: use LangChain or LLamaIndex, a public embedding model (e.g., sentence-transformers), and a free vector DB (Chroma or Weaviate local). Load 3–5 docs (your own interview guides work). |
| Sat | **Project Coding** | *Continued* | 2 hrs | Build the retrieval + LLM pipeline. Aim for: "Ask me anything about system design" chatbot over your own docs. |
| Sun | *Project polish + reflection* | *Continued* | 1–1.5 hrs | Add one prompt optimization (e.g., few-shot examples in the system message). Write 3 bullet points on what you learned. |

**Project Goal:** Spend 4–5 hrs this week building a toy RAG app. By end of week, you can say: *"I built a retrieval-augmented LLM app; I understand how embeddings, vector search, and context windows fit together. I've tuned prompts empirically."* That's insider knowledge.

**End of W5:** You've read the AI/ML theory and shipped a mini-project. Your interviewer will hear depth, not surface.

---

## Week 6: AI/ML Advanced + System Design Patterns (8–10 hrs)

**Goal:** Deepen AI/ML; tie it to your system design thinking.

| Day | Topic | File(s) | Time | Notes |
|---|---|---|---|---|
| Mon–Tue | **ML System Design** | `13-ai-ml/ml-system-design.md` | 2 hrs | Training pipelines, inference, feature stores, monitoring. How do you *scale* ML? |
| Wed | **AI Agents** | `13-ai-ml/ai-agents.md` | 1.5 hrs | Agentic loops, tool use, planning. Cutting edge; shows you're current. |
| Thu | **GenAI Security & Governance** | `13-ai-ml/genai-security-governance.md` | 1.5 hrs | Prompt injection, hallucination mitigation, cost control, IP concerns. Enterprise rigor. |
| Fri | **API Design + Security** | `09-system-design/security-architecture.md` | 1.5 hrs | Threat modeling, auth, encryption at scale. Tie back to your Azure + GitHub Actions knowledge. |
| Sat | **Project Retrospective** | *Reflect on RAG mini-project* | 1–1.5 hrs | Write a **design doc** (1 page): How would you scale your RAG app to serve 10k queries/day? What breaks? Where's your bottleneck? (You don't build it; you *design* it.) |
| Sun | *Consolidation* | — | 1 hr | Review your 10 system design sketches. Pick the 3 strongest. |

**End of W6:** You own AI/ML surface + depth (theory + code + design thinking). You've linked it to your existing expertise.

---

## Week 7: Additional Topics + Behavioral (6–8 hrs)

**Goal:** Cover the 2024+ landscape and leadership stories.

| Day | Topic | File(s) | Time | Notes |
|---|---|---|---|---|
| Mon | **Modern Architecture 2024–26** | `11-additional-topics/modern-architecture-2024-2026.md` | 1.5 hrs | Event-driven, serverless, GitOps, eBPF, observability-first. Competitive advantage. |
| Tue | **Cloud Security** | `11-additional-topics/cloud-security.md` | 1 hr | IAM, secrets, encryption, compliance. Your Azure role should make this familiar; deepen it. |
| Wed | **DevSecOps** | `11-additional-topics/devsecops.md` | 1 hr | Shift-left security, supply chain, container scanning. GitHub Actions angle. |
| Thu | **Observability Patterns** | `11-additional-topics/observability-patterns.md` | 1 hr | SLOs, SLIs, error budgets, dashboards. You've seen Prometheus/Grafana; frame it formally. |
| Fri–Sat | **Behavioral: Leadership Stories** | `12-behavioral/behavioral-leadership.md` | 1.5 hrs | Read the guide. Write down **3 true stories** from your 15 years:  1. A technical decision that was costly (and what you'd do differently).  2. A time you led a team through a crisis.  3. A mentorship moment or hiring win. Spend 30 min per story; aim for 2–3 min tellings. |
| Sun | *Mock interview* | *Self or with a friend* | 1–1.5 hrs | Pick one system design (chat, payment, search). Spend 40 min designing. Have someone (or imagine someone) ask clarifying questions. Record yourself if possible. |

**End of W7:** You've covered the 2024 landscape. You have polished behavioral stories. You've timed yourself under pressure.

---

## Week 8: Final Polish + Mock Rounds (8–10 hrs)

**Goal:** High-confidence readiness. One final check of weak spots.

| Day | Topic | File(s) | Time | Notes |
|---|---|---|---|---|
| Mon–Tue | **Consensus & Coordination** | `09-system-design/consensus-coordination.md` | 1.5 hrs | Paxos, Raft, distributed locks. One of the hardest topics; you'll see it. |
| Wed | **Estimation Refresher** | `09-system-design/estimation-capacity-planning.md` | 1 hr | Re-read. Quick-check your power-of-10 math. |
| Thu–Fri | **Mock Round 1: System Design** | *Design a system you haven't studied* | 2.5 hrs | Pick one from design-problems you skipped (e.g., ecommerce, stock exchange, ride-sharing). 60 min design, 30 min review against guide, 30 min reflection. |
| Sat | **Mock Round 2: System Design** | *Another unstudied case* | 2.5 hrs | Repeat with a different system. |
| Sun | **Final Review** | *Your top 3 sketches + behavioral stories* | 1–1.5 hrs | Refine your talking points. Review your RAG project for questions. Time your stories (should be 2–3 min each). |

**End of W8:** You are **system design confident**. You have shipped a real AI/ML artifact. Your behavioral toolkit is solid. You've timed yourself under pressure and recovered well.

---

## Success Criteria

By end of Week 8, you should be able to:

✅ **System Design:** Pick any system design problem, sketch a complete end-to-end design (data layer, APIs, caching, scaling, failure modes) in 50–60 min. Defend your choices.

✅ **AI/ML:** Explain LLMs, RAG, and agents. Discuss your mini-project. Answer questions on scaling, hallucination, cost.

✅ **Additional Topics:** Speak credibly on 2024–26 patterns, cloud security, observability, and DevSecOps.

✅ **Behavioral:** Deliver 3 clear, 2–3 min leadership stories with specific outcomes and learnings.

✅ **Stack Review:** For any question on Spring Boot, Kafka, Postgres, or Azure, jump to Expert tier, answer the Q, and add one insightful follow-up.

---

## Optional Extensions (if you have 10 weeks or want to deepen)

- **Week 9:** Read `08-dsa/` (select 5–10 medium-difficulty problems). Even at your level, coding under pressure shows rigor. (Can compress to 4–5 hrs/week for 2 weeks if needed.)
- **Week 9–10:** Study two more complex case studies (e.g., Collaborative Editor, Distributed Job Scheduler). These are "stretches" and rare asks, but impressive if you nail them.
- **Anytime:** If Bicep interviews are likely, spend 2 hrs creating a 5-problem Bicep cheat sheet (IaC patterns, module composition, parameter files). The guides cover it thinly; a small project fills it.

---

## Practical Tips

1. **System Design Sketches:** Keep a physical whiteboard or a Figma/Excalidraw notebook. Redraw each case study from memory once. First time is slow; second time proves you own it.

2. **Timing:** Book 60 min of uninterrupted time for each design sketch. Use a timer. Real interviews are 45–60 min.

3. **AI/ML Project:** Don't overthink it. Sentence-transformers + Chroma + OpenAI/Claude API (or open-weight models) takes 2–3 hrs to scaffold. The goal is hands-on, not production-grade.

4. **Behavioral Stories:** Practice out loud. Record on your phone. Listen back. Trim filler. You should sound natural, not scripted.

5. **Reuse Existing Docs:** Your interview guides are gold for the RAG mini-project. "Let me build a chatbot over this interview repo" is meta-credible.

6. **Rest Days:** Every Sunday includes optional catch-up. Don't skip them; interview prep is a marathon. If you're ahead, deepen. If you're behind, catch up without guilt.

---

## Estimated Hours Breakdown

| Phase | Hours | Weeks |
|---|---|---|
| System Design (fundamentals + components + 6 case studies) | 32–36 | 4 |
| AI/ML (theory + mini-project) | 12–14 | 1.5 |
| Additional Topics | 6–8 | 0.8 |
| Behavioral | 2–3 | 0.4 |
| Stack review (Expert tier skim) | 3–4 | 0.3 |
| Mock interviews & polish | 5–6 | 1 |
| **Total** | **60–71 hours** | **8 weeks** |

**At 1.5 hrs/day:** 56 days = ~8 weeks ✓

---

## Interview Scenario Prep

### Scenario 1: "Design a Slack-like chat system."
**Weeks 1–4 prep:** Read chat system case study, distribute systems, caching. **Week 8 mock:** Sketch under time pressure. You should nail: WebSockets, message ordering, 1M concurrent users, emoji reactions, read receipts, persistence.

### Scenario 2: "How would you build a GenAI-powered code assistant?"
**Weeks 5–6 prep:** RAG mini-project, LLM fundamentals, prompt engineering, cost/latency trade-offs. **Week 7 additional-topics:** Security (prompt injection, code leakage). You should articulate: chunking strategy, retrieval precision, fast inference, cost scaling, security surface.

### Scenario 3: "You have a microservice that's timing out. Walk me through debugging."
**All weeks:** Ops & reliability (W4), observability (W2 + W6 additional), distributed systems (W1). You should touch: metrics, traces, SLOs, circuit breakers, timeouts, bulkheads, graceful degradation.

### Scenario 4: "Tell me about a time you led a team through a complex architectural decision."
**Weeks 7–8:** Behavioral stories. You should deliver a 2–3 min narrative with conflict, resolution, and what you learned.

---

## Checkpoint Dates

- **End W2:** You have fundamentals + one tiny design sketch. Check: Can you explain cap theorem in 2 min?
- **End W4:** You've sketched 3–4 systems. Check: Can you design a leaderboard system in 50 min? (If yes, system design is solid.)
- **End W5:** RAG mini-project is live. Check: Can you ask it a question and explain how the retrieval pipeline worked?
- **End W7:** Behavioral stories are rehearsed. Check: Can you tell each story in 2–3 min without notes?
- **End W8:** Two mock rounds done. Check: Did you improve your time or depth in the second mock vs. the first?

---

**Good luck. You've got this.**
