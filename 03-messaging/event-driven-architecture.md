# Event-Driven Architecture

Event-Driven Architecture (EDA) is a design paradigm in which components communicate by producing and consuming **events** — immutable records of something that has happened — rather than calling each other synchronously. This guide covers the full spectrum from fundamentals to staff-level system design, with Java-centric examples (Spring Boot, Kafka, JPA) and production trade-offs current through 2026.

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

### Q1. [Theory] What is Event-Driven Architecture and how does it differ from request/response?

EDA is a style where producers emit **events** describing facts that have already occurred (e.g. `OrderPlaced`), and one or more consumers react asynchronously. The producer does not know who consumes the event, nor does it wait for a response — this is **temporal decoupling** (parties need not be online at the same time) and **spatial decoupling** (producer needs no reference to the consumer).

In request/response (e.g. a synchronous REST call), the caller knows the callee, blocks for the result, and the two are tightly coupled in time and availability. If the callee is down, the caller fails. In EDA, the broker buffers the event, so a temporarily unavailable consumer simply processes it later. The trade-off is that you gain scalability and resilience but lose the simplicity of an immediate, strongly consistent answer — you must now reason about **eventual consistency** and asynchronous failure handling.

```
Request/Response (coupled):           Event-Driven (decoupled):

  ServiceA ──call──▶ ServiceB           ServiceA ──emit──▶ [ Broker ]
           ◀─reply──                                         │  │  │
   (blocks, fails if B down)                                 ▼  ▼  ▼
                                                            C1 C2 C3  (react async)
```

### Q2. [Theory] What is the difference between an event, a command, and a message?

A **message** is the generic envelope — any payload sent over a channel. **Events** and **commands** are two semantic kinds of message:

- An **event** states a fact that already happened in the past tense (`PaymentCaptured`). The producer broadcasts it without expecting any specific action; zero, one, or many consumers may react. Ownership of the consequence belongs to the consumer.
- A **command** is an imperative instruction in the imperative mood (`CapturePayment`) directed at a specific handler that is expected to do something. It usually has exactly one logical recipient and often expects an acknowledgement or a resulting event.

The key distinction is **intent and coupling**: commands imply the sender knows what should happen next (more coupling); events imply the sender only reports what occurred (less coupling). A useful test: if renaming the field to past tense reads naturally and you don't care who listens, it's an event.

### Q3. [Theory] What is a message broker and what role does it play?

A message broker (e.g. Apache Kafka, RabbitMQ, AWS SQS/SNS, Google Pub/Sub) is the intermediary that receives messages from producers and delivers them to consumers. It provides **buffering** (absorbing load spikes), **routing** (topic/exchange/queue semantics), **durability** (persisting messages so they survive crashes), and **delivery guarantees**. By sitting between producer and consumer, the broker is what enables the decoupling that defines EDA — producers and consumers only know the broker and the contract (topic + schema), not each other.

### Q4. [Practical] When would you choose EDA over a synchronous REST API?

Choose EDA when:
- The producer does not need an immediate result (fire-and-forget side effects like sending email, updating analytics, invalidating caches).
- Multiple independent consumers care about the same fact (fan-out), and you don't want the producer to know about all of them.
- Workloads are bursty and you need a buffer to smooth spikes and protect downstream systems.
- You want services to evolve and deploy independently without breaking callers.

Stick with synchronous REST/gRPC when the caller genuinely needs the answer **now** to proceed (e.g. "is this credit card valid before I show the confirmation page?"), when strong consistency is required, or when the added operational complexity of a broker is not justified. A common real-world pattern: an e-commerce checkout validates payment synchronously (need the answer) but emits `OrderPlaced` asynchronously to trigger fulfillment, invoicing, and recommendations.

### Q5. [Coding] Produce and consume an event with Spring Boot and Kafka.

**Problem:** Emit an `OrderPlaced` event when an order is created and have a separate listener react to it.

```java
// 1. The event (immutable record — Java 17+)
public record OrderPlaced(String orderId, String customerId,
                          long amountCents, Instant occurredAt) {}

// 2. Producer
@Service
public class OrderService {
    private final KafkaTemplate<String, OrderPlaced> kafka;

    public OrderService(KafkaTemplate<String, OrderPlaced> kafka) {
        this.kafka = kafka;
    }

    public void placeOrder(String orderId, String customerId, long amountCents) {
        // ... persist the order first ...
        OrderPlaced event = new OrderPlaced(orderId, customerId,
                                            amountCents, Instant.now());
        // Key by orderId so all events for one order land on the same partition
        kafka.send("orders.placed.v1", orderId, event);
    }
}

// 3. Consumer
@Component
public class FulfillmentListener {
    @KafkaListener(topics = "orders.placed.v1", groupId = "fulfillment")
    public void onOrderPlaced(OrderPlaced event) {
        // React: reserve inventory, schedule shipping, etc.
        System.out.println("Fulfilling order " + event.orderId());
    }
}
```

**Notes:** Keying by `orderId` guarantees per-order ordering within a partition. The `.v1` suffix is a versioning convention. **Edge cases:** the consumer may receive the same event more than once (see idempotency, Q11); the listener should not throw on transient errors without a retry/DLQ strategy. Using a `record` (Java 14+ preview, stable in 16/17) makes the event immutable by construction, which is the correct semantic for an event.

### Q6. [Theory] What does "eventual consistency" mean in an event-driven system?

Eventual consistency means that after a change is made, the various services and read models will converge to a consistent state **after some delay**, rather than instantaneously. Because consumers process events asynchronously, there is a window where the producer's data is updated but a downstream view is not yet. For example, immediately after `OrderPlaced`, the order exists in the order service but the "my orders" read model or the warehouse system may lag by milliseconds to seconds. This is an acceptable trade-off for availability and scalability in many domains, but you must design UX and business logic to tolerate the lag (e.g. show "processing" states) and never assume read-after-write consistency across service boundaries.

---

## 🟡 Intermediate (3–7 yrs)

### Q7. [Theory] Compare event notification vs event-carried state transfer.

These are two patterns for how much data an event carries:

- **Event notification:** the event is a thin signal containing little more than an ID and event type (`OrderPlaced{orderId}`). Consumers that need details **call back** to the source service (`GET /orders/{id}`). This keeps events small and the source as the single source of truth, but reintroduces runtime coupling and load on the producer, and risks the "callback storm" where every consumer hammers the source.
- **Event-carried state transfer (ECST):** the event carries enough state for consumers to act without calling back (`OrderPlaced{orderId, items, totals, address}`). Consumers keep a local replica. This maximizes decoupling and resilience (consumers work even if the producer is down) at the cost of larger events, data duplication, and the challenge of keeping replicas fresh and dealing with stale data.

```
Notification:                          ECST:
  Producer ─{id}─▶ Consumer              Producer ─{id, full state}─▶ Consumer
                      │                                                  │
                      └─GET /id─▶ Producer                        (acts locally,
                        (coupling, load)                           keeps replica)
```

In practice, mature systems often use ECST for cross-bounded-context integration to reduce coupling, accepting the duplication. A hybrid ("fat events with a fallback fetch") is also common.

### Q8. [Theory] Explain choreography vs orchestration. When do you pick each?

Both coordinate a multi-step business process (a saga) across services:

- **Choreography:** there is no central coordinator. Each service listens for events and reacts by emitting its own events. Control is distributed. It's loosely coupled and resilient, but the overall flow is implicit — no single place describes the whole process, which makes it hard to understand, debug, and modify as steps grow.
- **Orchestration:** a central orchestrator (a saga coordinator / workflow engine like Temporal, AWS Step Functions, or Camunda) explicitly invokes each step and decides what happens next, including compensations. The flow is explicit and observable, but the orchestrator is a coupling point and can become a bottleneck or god-object.

```
Choreography:                          Orchestration:
 Order ─evt─▶ Payment ─evt─▶ Ship       Orchestrator
   (each step listens & reacts)          ├─cmd─▶ Order
                                         ├─cmd─▶ Payment
                                         └─cmd─▶ Ship   (central control)
```

Rule of thumb: use **choreography** for simple flows (2–4 steps) where loose coupling matters; switch to **orchestration** when the process is long, has many branches/compensations, or when business stakeholders need a visible, auditable workflow.

### Q9. [Theory] Compare message queues and event streams (e.g. RabbitMQ/SQS vs Kafka/Kinesis).

A traditional **message queue** treats messages as transient work items: a message is delivered to one consumer in a group and then **deleted/acknowledged** (destructive read, competing-consumers pattern). It's optimized for task distribution and per-message routing/priority.

An **event stream / log** (Kafka, Kinesis, Pulsar) is an **append-only, durable, ordered log**. Reads are non-destructive — each consumer group tracks its own **offset**, so messages can be **replayed**, multiple independent consumers can read the same stream at different positions, and retention is time/size based rather than consumption based.

| Dimension          | Queue (RabbitMQ/SQS)      | Log/Stream (Kafka)            |
|--------------------|---------------------------|-------------------------------|
| Read semantics     | Destructive (ack & delete)| Non-destructive (offset)      |
| Replay             | No (gone after ack)       | Yes (rewind offset)           |
| Ordering           | Per-queue, weakened by competing consumers | Per-partition |
| Fan-out            | Via exchanges/topics      | Native (each group own offset)|
| Typical use        | Task/job distribution     | Event sourcing, analytics, integration |

Choose a queue for command-style work distribution; choose a log for event broadcasting, replayability, and stream processing.

### Q10. [Theory] Explain the three delivery semantics: at-most-once, at-least-once, exactly-once.

- **At-most-once:** a message is delivered zero or one time. The system never retries, so messages may be lost but never duplicated. Acceptable only for tolerant data (e.g. metrics, telemetry) where loss is cheaper than duplication.
- **At-least-once:** a message is delivered one or more times. The system retries until acknowledged, so nothing is lost, but duplicates are possible (e.g. ack lost after processing). This is the **default and most practical** guarantee for most brokers; the burden of handling duplicates shifts to the consumer via **idempotency**.
- **Exactly-once:** each message affects the system's state once and only once. This is the hardest to achieve and only holds within specific boundaries. Kafka offers exactly-once *semantics* (EOS) for **Kafka-to-Kafka** processing via idempotent producers (`enable.idempotence=true`) and transactions spanning consume-process-produce. But the instant a side effect leaves Kafka (an HTTP call, an email, a non-transactional DB), you are back to at-least-once and must rely on idempotency. The pragmatic stance: design for at-least-once + idempotent consumers, and treat "exactly-once" as "effectively once" achieved via dedup.

### Q11. [Coding] Implement an idempotent consumer that deduplicates events.

**Problem:** A consumer may receive the same event multiple times (at-least-once). Process each event's effect exactly once.

**Approach 1 — Naive (broken):** just process the event. Under retries this double-charges, double-ships, etc. Not acceptable.

**Approach 2 — Dedup table with a unique constraint (robust):** record each processed event ID in a table with a unique key, inside the same transaction as the business effect. The DB constraint becomes the dedup guard.

```java
@Component
public class PaymentConsumer {
    private final JdbcTemplate jdbc;

    public PaymentConsumer(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @KafkaListener(topics = "payments.capture.v1", groupId = "billing")
    @Transactional
    public void onCapture(CapturePayment cmd) {
        try {
            // Insert the dedup marker FIRST; unique PK on event_id.
            jdbc.update(
                "INSERT INTO processed_events(event_id, processed_at) VALUES (?, ?)",
                cmd.eventId(), Timestamp.from(Instant.now()));
        } catch (DuplicateKeyException e) {
            // Already processed — safe to skip. Commit the (no-op) offset.
            return;
        }
        // Same transaction: if the business effect fails, the marker rolls back too.
        capturePayment(cmd);
    }
}
```

**Approach 3 — Idempotency via natural key / upsert:** if the effect is itself idempotent (e.g. `UPDATE balance SET captured = true WHERE order_id = ?` is a set-to-state, not an increment), you may not need a separate dedup table at all. Prefer state-setting operations over state-mutating ones where possible.

- **Time complexity:** O(1) per event (single indexed insert + business op).
- **Space complexity:** O(N) for the dedup table; prune old rows (e.g. older than your max retention/replay window) to bound it.
- **Edge cases:** the dedup insert and the business effect MUST share a transaction, or you can mark-as-done then crash before doing the work. Use a TTL/partitioned table to avoid unbounded growth. If effects span systems (DB + external API), combine dedup with the outbox pattern (Q19).

### Q12. [Theory] How is ordering handled, and why is global ordering hard?

In Kafka, ordering is guaranteed **only within a partition**. Messages with the same key always go to the same partition and are consumed in order, so keying by an aggregate ID (e.g. `accountId`) preserves per-aggregate order — which is usually all the business needs. **Global ordering across a topic requires a single partition**, which destroys parallelism and throughput, so it is almost never the right goal.

Ordering is further threatened by **retries**: if message A fails and is retried while B succeeds, B can land first. With Kafka, setting `max.in.flight.requests.per.connection=1` (or ≤5 with idempotence) plus enabling idempotent producers preserves order under retry. In queues, parallel competing consumers break ordering entirely unless you use ordered constructs (SQS FIFO message groups, RabbitMQ single active consumer). The lesson: **partition by the entity whose order matters**, and don't ask for more ordering than the domain requires.

### Q13. [Practical] What is the dual-write problem and how do you solve it?

The **dual-write problem** occurs when a single operation must update two systems — typically a database and a message broker — and there is no shared transaction across them. If you write to the DB and then publish to Kafka, a crash between the two leaves them inconsistent: the order is saved but `OrderPlaced` was never emitted (or vice versa). You cannot fix this with a try/catch because the failure can happen after the DB commit and before the publish.

```
  BEGIN
    INSERT order            ✅ committed
  COMMIT
  kafka.send(OrderPlaced)   💥 crash here → event lost, DB & broker diverge
```

The canonical solution is the **Transactional Outbox** (Q19): write the event into an `outbox` table in the **same local transaction** as the business data, then a separate process relays outbox rows to the broker. This converts two non-atomic writes into one atomic local DB write plus an asynchronous, retryable relay. Alternatives include the **listen-to-yourself** pattern and using a broker that participates in the same transaction (rare in practice). Avoid "publish then write" and "write then publish" naive approaches — both have a lost-update window.

### Q14. [Theory] How do you evolve event schemas without breaking consumers?

Events are contracts that outlive any single deployment, so schema evolution must respect compatibility:

- **Backward compatible:** new consumers can read old events (you may add optional fields with defaults, must not remove/rename required fields).
- **Forward compatible:** old consumers can read new events (they ignore unknown fields).
- **Full compatibility:** both — the safest for independently deployed producers and consumers.

Use a **schema registry** (Confluent Schema Registry, AWS Glue) with **Avro** or **Protobuf**, which enforce compatibility checks at publish time and reject breaking changes. Rules of thumb: only **add optional fields**; never reuse or repurpose a field number/name; never change a field's type or meaning. For breaking changes, publish a **new versioned topic** (`orders.placed.v2`) and run both versions until all consumers migrate, then retire v1. JSON Schema works too but is weaker; prefer Protobuf/Avro for binary efficiency and strong tooling.

### Q15. [Coding] Define an Avro schema and evolve it compatibly.

**Problem:** You have an `OrderPlaced` Avro schema and need to add a `couponCode` without breaking existing consumers.

```json
// v1 schema (orders.placed.v1)
{
  "type": "record",
  "name": "OrderPlaced",
  "namespace": "com.shop.events",
  "fields": [
    { "name": "orderId",    "type": "string" },
    { "name": "customerId", "type": "string" },
    { "name": "amountCents","type": "long" }
  ]
}
```

```json
// v2 — add an OPTIONAL field with a default → backward AND forward compatible
{
  "type": "record",
  "name": "OrderPlaced",
  "namespace": "com.shop.events",
  "fields": [
    { "name": "orderId",    "type": "string" },
    { "name": "customerId", "type": "string" },
    { "name": "amountCents","type": "long" },
    { "name": "couponCode", "type": ["null", "string"], "default": null }
  ]
}
```

**Why this works:** old consumers using the v1 reader schema simply ignore `couponCode` (forward compatible). New consumers reading old v1 messages get `null` from the default (backward compatible). The `["null","string"]` union + `"default": null` is the idiomatic Avro way to add an optional field.

- **Breaking changes to avoid:** removing `orderId`, renaming `amountCents`, or changing `long` → `int`. The registry (in `FULL` compatibility mode) will reject these at registration time.
- **Edge case:** if you truly must break, mint `orders.placed.v2` as a new topic and dual-write during migration.

### Q16. [Practical] How do you handle a "poison message" and what is a dead-letter queue?

A **poison message** is one that a consumer can never successfully process — bad data, a schema it can't parse, or a code bug. Without handling, it blocks the partition/queue (head-of-line blocking) or loops forever in infinite retries, starving everything behind it.

The standard remedy is a **retry-with-backoff then dead-letter (DLQ)** strategy: attempt N times with increasing delays (to ride out transient failures), and on exhaustion route the message to a **dead-letter topic/queue** for later inspection, alerting, and manual or automated reprocessing.

```
 main topic ──▶ consumer ──fail──▶ retry topic (5s) ──fail──▶ retry topic (1m)
                                                                    │ fail
                                                                    ▼
                                                              dead-letter topic
                                                          (alert + human/triage)
```

In Spring Kafka, `DefaultErrorHandler` with a `DeadLetterPublishingRecoverer` and exponential `BackOff` implements this. Crucially: **distinguish transient from permanent failures** — a 503 from a downstream is worth retrying; a `JsonParseException` is not and should DLQ immediately to avoid wasted retries. Always attach the failure reason and original headers to the DLQ message so on-call engineers can triage.

### Q17. [Practical] Your consumer lag is steadily growing in production. How do you diagnose and fix it?

**Scenario:** Kafka consumer lag (offset behind log-end) climbs, so events are processed minutes late.

**Diagnose:**
1. Is the producer rate genuinely exceeding consumer throughput, or did consumers stall? Check throughput on both sides.
2. Is one partition hot (skewed key) while others idle? Check per-partition lag.
3. Is processing slow (a slow downstream call, large GC pauses, a lock)? Profile the handler.
4. Are rebalances thrashing (frequent group rebalances reset progress)?

**Fix options (in rough order):**
- **Scale out consumers** up to the partition count (parallelism is capped by partitions — more consumers than partitions just idle).
- **Increase partitions** if you're at the cap and need more parallelism (note: changing partition count changes key→partition mapping, affecting ordering for in-flight keys).
- **Make the handler faster:** batch processing, async I/O, remove a synchronous external call from the hot path, increase `max.poll.records` thoughtfully.
- **Fix skew:** choose a higher-cardinality partition key.
- **Tune `max.poll.interval.ms`** if long processing triggers rebalances.

What I'd actually do in production: first confirm whether it's a sudden spike (transient, may self-heal — buy time by scaling consumers) or a structural mismatch (must add partitions/optimize). Add lag alerts (e.g. Burrow / Cruise Control / managed metrics) so this is caught proactively rather than discovered by angry users.

### Q18. [Coding] Implement retry-with-exponential-backoff and DLQ in Spring Kafka.

```java
@Configuration
public class KafkaErrorConfig {

    @Bean
    public DefaultErrorHandler errorHandler(KafkaTemplate<Object, Object> template) {
        // Publish to "<topic>.DLT" after retries exhausted
        var recoverer = new DeadLetterPublishingRecoverer(template);

        // Exponential backoff: 1s, 2s, 4s ... capped, max 4 attempts
        var backOff = new ExponentialBackOffWithMaxRetries(4);
        backOff.setInitialInterval(1000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10_000L);

        var handler = new DefaultErrorHandler(recoverer, backOff);

        // PERMANENT failures: don't retry, DLQ immediately
        handler.addNotRetryableExceptions(
            JsonProcessingException.class,
            IllegalArgumentException.class);
        return handler;
    }
}
```

- **Why:** transient errors (network blips, 503s) benefit from backoff; permanent errors (unparseable payloads) must skip retries and go straight to the DLT, otherwise you waste capacity retrying something that will never succeed.
- **Complexity:** retries add at most `sum(backoff intervals)` latency per failing message before it lands in the DLT; this is bounded by `maxRetries`.
- **Edge case:** with non-blocking retries (`@RetryableTopic`, Spring Kafka 2.7+), retries go to separate retry topics so they don't block the main partition — preferred for high-throughput topics where blocking backoff would stall healthy messages.

---

## 🟠 Advanced (8–12 yrs)

### Q19. [Coding] Implement the Transactional Outbox pattern.

**Problem:** Atomically persist business state and ensure the corresponding event is published (solving the dual-write problem from Q13).

**Approach:** Write the event row into an `outbox` table in the **same DB transaction** as the business change. A relay (a poller, or better, Change Data Capture via Debezium reading the DB log) ships outbox rows to Kafka.

```java
@Service
public class OrderService {
    private final OrderRepository orders;
    private final OutboxRepository outbox;
    private final ObjectMapper mapper;

    @Transactional  // single local DB transaction — atomic
    public void placeOrder(Order order) throws JsonProcessingException {
        orders.save(order);                       // business state
        OutboxEvent ev = new OutboxEvent(
            UUID.randomUUID().toString(),         // event id (used for dedup)
            "Order",                              // aggregate type
            order.getId(),                        // aggregate id  -> partition key
            "OrderPlaced",                        // event type
            mapper.writeValueAsString(order));    // payload
        outbox.save(ev);                          // same transaction
    }   // COMMIT: order + outbox row atomically persisted or neither
}
```

```sql
CREATE TABLE outbox (
  id            UUID PRIMARY KEY,
  aggregate_type VARCHAR(64),
  aggregate_id   VARCHAR(64),
  event_type     VARCHAR(64),
  payload        JSONB,
  created_at     TIMESTAMP DEFAULT now(),
  published      BOOLEAN DEFAULT false
);
```

**Relay options:**
- **Polling publisher:** a scheduled job selects unpublished rows, publishes to Kafka, marks them published. Simple but adds DB load and latency; must handle the case where publish succeeds but the mark fails (→ at-least-once, so consumers must dedup).
- **CDC (recommended):** Debezium tails the database transaction log and streams inserts to Kafka with no application polling. Lower latency, no extra read load, and the log is the source of truth.

```
 [App] --tx--> Postgres (orders + outbox)
                   │ WAL
                   ▼
              Debezium (CDC) ──▶ Kafka topic ──▶ consumers
```

- **Complexity:** O(1) extra write per command; relay is O(unpublished rows). Prune/partition the outbox to bound size.
- **Edge cases:** still at-least-once (relay can re-publish) — consumers must be idempotent (Q11). Ordering preserved by keying Kafka messages on `aggregate_id`. Watch for outbox table bloat and long-running CDC offset lag.

### Q20. [Theory] Explain Event Sourcing and its trade-offs.

In **Event Sourcing**, you do not store current state; you store the **full, ordered, immutable sequence of events** that led to it. Current state is derived by **replaying** events (folding them into an aggregate). The event log is the system of record.

**Benefits:** a complete, tamper-evident audit trail for free; the ability to reconstruct state at any past point in time (temporal queries); the power to build new read models retroactively by replaying history; and natural fit with EDA since the stored events are the integration events.

**Costs and risks:** querying current state requires replay or maintained snapshots/projections; **schema evolution of stored events is harder** because old events are immutable and must remain readable forever (you need upcasters); eventual consistency between the write log and read projections; and significant conceptual complexity. Deleting data for GDPR is awkward against an append-only log (common solution: **crypto-shredding** — encrypt PII per subject and delete the key to render it unrecoverable).

```
 Commands ─▶ [ Event Store: e1,e2,e3,... (append-only) ]
                        │ replay/fold
                        ▼
              Aggregate state + Projections (read models)
```

Use it where the *history itself is valuable* (finance, ledgers, audit-heavy domains). It is overkill for simple CRUD.

### Q21. [Theory] What is CQRS and how does it relate to Event Sourcing?

**CQRS (Command Query Responsibility Segregation)** separates the **write model** (commands that mutate state) from the **read model** (queries). The two can use different schemas, different databases, and scale independently — for example, normalized Postgres for writes and a denormalized Elasticsearch index for reads, kept in sync by events.

CQRS and Event Sourcing are **independent but synergistic**: you can do CQRS without event sourcing (just separate read/write stores synced via events), and event sourcing naturally produces the event stream that feeds CQRS read-model projections. Together they let the write side capture events and the read side maintain optimized, query-specific projections.

**Trade-offs:** the read model is **eventually consistent** with the write model, so the UI must tolerate lag (a user may not immediately see their just-written change). It adds operational complexity and duplicated data. Apply CQRS selectively to the parts of the system with asymmetric read/write loads or divergent query needs — not as a blanket architecture.

```
       ┌──────────── Command side (write) ───────────┐
 cmd ─▶│ validate ─▶ aggregate ─▶ append events       │
       └────────────────────┬────────────────────────┘
                            events
       ┌────────────────────▼────────────────────────┐
 qry ◀─│ projections / materialized views (read)      │ (eventually consistent)
       └─────────────────────────────────────────────┘
```

### Q22. [Practical] Design an order-processing saga with compensation. Choreography or orchestration?

**Scenario:** `OrderPlaced → ReservePayment → ReserveInventory → ArrangeShipping`. Any step can fail, and earlier steps must be undone (a distributed transaction without 2PC).

A **saga** breaks the transaction into local transactions, each emitting an event that triggers the next, with **compensating actions** to semantically undo prior steps on failure (release payment hold, restock inventory). Note compensations are *semantic* reversals, not rollbacks — money may have moved, so you issue a refund rather than "undo."

```
 OrderPlaced ─▶ PaymentReserved ─▶ InventoryReserved ─▶ Shipped ✅
                    │                    │
              (fail) │              (fail) │
                    ▼                    ▼
            CancelOrder         ReleasePayment + CancelOrder
            (compensate)            (compensate backwards)
```

**Choreography vs orchestration here:** with only 3–4 steps, choreography is viable, but compensation logic spread across services becomes hard to reason about and audit. For an order saga with money involved, I'd choose **orchestration** with a durable workflow engine (Temporal or Step Functions): the orchestrator persists state, drives each step, handles timeouts, and centralizes compensation — giving us an auditable, debuggable, restartable process. **Production reality:** make every step idempotent (retries are guaranteed), set timeouts on each step, persist saga state durably so it survives crashes, and emit observability events for each transition.

### Q23. [Theory] How do you achieve exactly-once processing in a Kafka stream pipeline, and what are its limits?

Kafka's **Exactly-Once Semantics (EOS)** works for the **consume → process → produce** loop entirely within Kafka. It relies on: (1) the **idempotent producer** (`enable.idempotence=true`, on by default in modern Kafka) which dedups producer retries via a producer ID + sequence number; and (2) **transactions** (`transactional.id`) that atomically commit both the produced output records and the consumer offsets, so either everything in the transaction is visible or nothing is. Consumers reading with `isolation.level=read_committed` never see records from aborted transactions.

```
 [consume in]──process──[produce out + commit offsets]  ←── all in ONE Kafka txn
```

**Limits — this is the staff-level nuance:** EOS only holds while you stay inside Kafka. The moment your processor performs an **external side effect** — call a payment API, send an email, write to a non-transactional store — that side effect is not part of the Kafka transaction and can be re-executed on retry. So "end-to-end exactly-once" with external systems is generally **impossible** without the side effect itself being idempotent. The correct framing: Kafka gives exactly-once *within the stream*; for everything else, design **at-least-once + idempotent effects** ("effectively once"). Transactions also add latency and throughput cost, so enable EOS only where duplicates are genuinely harmful.

### Q24. [Coding] Build an event replay / projection rebuild from a Kafka log.

**Problem:** A bug corrupted a read model. Rebuild it by replaying all historical events from the beginning of the topic into a fresh projection — without disturbing the live consumer group.

```java
public class ProjectionRebuilder {

    public void rebuild(String topic, ReadModelStore store) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker:9092");
        // Brand-new group id so we don't disturb production offsets:
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "rebuild-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"); // replay from start
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        try (var consumer =
                 new KafkaConsumer<String, OrderEvent>(props, new StringDeserializer(),
                                                        new JsonDeserializer<>(OrderEvent.class))) {
            consumer.subscribe(List.of(topic));
            long idlePolls = 0;
            while (idlePolls < 5) {  // stop after several empty polls = caught up
                var records = consumer.poll(Duration.ofMillis(500));
                if (records.isEmpty()) { idlePolls++; continue; }
                idlePolls = 0;
                for (var rec : records) {
                    store.apply(rec.value());  // fold event into the projection (must be idempotent)
                }
            }
        }
    }
}
```

**Why this is possible:** Kafka is a non-destructive log, so a new consumer group with `auto.offset.reset=earliest` replays the entire retained history. This is a core advantage of event streams over destructive queues (Q9).

- **Complexity:** O(N) over the retained events; bounded by retention. For very large logs, parallelize across partitions and use snapshots to avoid replaying from genesis every time.
- **Edge cases:** `store.apply` must be idempotent (replays re-apply events). To swap live without downtime, rebuild into a shadow table, then atomically alias/switch. Beware events whose schema has evolved — apply upcasters during replay.

### Q25. [Practical] How do you make event-driven systems observable and debuggable?

Async flows are notoriously hard to trace because a single user action spawns a cascade of events across many services with no synchronous call stack. Strategies:

- **Correlation / causation IDs:** propagate a `correlationId` (the originating request) and `causationId` (the immediate parent event) in every event header so you can stitch the whole tree together in logs.
- **Distributed tracing:** OpenTelemetry context propagation through Kafka headers links spans across producers and consumers, so a trace shows the full async fan-out in Jaeger/Tempo.
- **Lag and DLQ metrics:** alert on consumer lag, DLQ depth, retry rates, and rebalance frequency — these are the early-warning signs.
- **Schema registry + contract tests:** catch breaking changes before deploy.
- **Event catalog / discovery** (e.g. AsyncAPI specs, an internal event registry): document who produces and consumes each event so the implicit choreography becomes explicit.

What I'd put in place first: correlation IDs + OpenTelemetry + lag/DLQ alerting. Without these, a production incident in a choreographed system is nearly impossible to diagnose.

### Q26. [Theory] When does EDA hurt? When should you NOT use it?

EDA is not free; it trades synchronous simplicity for asynchronous complexity. It **hurts** when:

- **You need strong consistency or read-after-write guarantees.** Eventual consistency confuses users and complicates logic (e.g. a banking transfer that must reflect immediately).
- **The flow is inherently a simple request/response** that needs an answer now — wrapping it in events adds latency and indirection for no benefit.
- **The team lacks operational maturity.** Brokers, schema registries, DLQs, idempotency, replay, and tracing are real operational burdens; a small team on a simple CRUD app will drown in incidental complexity.
- **Low scale / low fan-out.** If there's one producer and one consumer and no scaling pressure, a direct call is simpler and easier to debug.
- **Debugging cost outweighs benefit.** Async, distributed flows are harder to trace; the implicit control flow of choreography can become "spaghetti by events."

The mature view: adopt EDA where decoupling, scalability, fan-out, buffering, or auditability deliver concrete value — and keep synchronous calls where they're simpler and correct. Many real systems are **hybrid**: synchronous within a bounded context, event-driven across contexts.

---

## 🔴 Expert (15+ yrs)

### Q27. [Theory] How do you manage event schema governance across hundreds of services and teams?

At scale, events become a shared organizational contract, and uncoordinated changes cause cascading breakage. Governance combines technical and organizational controls:

- **Mandatory schema registry** with enforced compatibility (`FULL_TRANSITIVE` for cross-team topics) in CI, so a breaking change physically cannot be deployed.
- **Treat events as products** ("event as a product" / data mesh thinking): each event has an owning team, an SLA, documentation (AsyncAPI), versioning policy, and a deprecation process.
- **Consumer-driven contract testing** (Pact-style for async) so producers learn which fields consumers actually depend on before changing them.
- **Explicit deprecation lifecycle:** announce → dual-publish v1/v2 → monitor v1 consumer count via metrics → retire only when usage hits zero.
- **Canonical event taxonomy and naming standards** so the same business fact isn't modeled five different ways.

The hard part is organizational, not technical: you need a forum/working group that owns cross-cutting event standards, plus golden-path tooling that makes the compliant path the easy path. Without governance, EDA at scale degrades into a brittle, undocumented web — the "distributed monolith" failure mode.

### Q28. [Theory] Discuss multi-region / cross-datacenter event replication and its consistency implications.

Spanning regions with events forces a confrontation with CAP and physics. Options and trade-offs:

- **Active-passive replication** (e.g. MirrorMaker 2 / Confluent Cluster Linking): asynchronously copy topics to a DR region. Simple, but offsets differ across clusters and there's a replication lag window where failover loses recently-produced events (RPO > 0).
- **Active-active:** producers in multiple regions, replicating both ways. Now you face **conflict resolution** and **cycle prevention** (an event replicated back to its origin). You need last-writer-wins or CRDT-style merge semantics, and per-region key partitioning to avoid write conflicts on the same aggregate.
- **Global ordering across regions is effectively impossible** without unacceptable latency — light-speed alone makes a synchronous global order impractical. Accept regional ordering and design aggregates so a given entity is "homed" in one region.

The expert framing: pick the **consistency/availability trade-off per use case**. Financial ledgers may pin an account to a home region (sacrificing cross-region write availability for correctness); analytics events tolerate lag and dedup. Always design idempotent, commutative consumers because cross-region replication amplifies duplicates and reordering.

### Q29. [Practical] You're migrating a synchronous monolith to EDA. How do you sequence it safely?

**Approach — strangler-fig, not big-bang:**

1. **Identify seams by bounded context**, not by technical layer. Find a fact that's already conceptually an "event" (e.g. `OrderPlaced`) at a domain boundary.
2. **Introduce the event alongside the existing synchronous call** ("event-carried, sync-backed"): keep the synchronous path working, but also emit the event. New consumers attach to the event; the old path is untouched. This de-risks by avoiding a flag-day cutover.
3. **Add the outbox** so the event publish is transactionally consistent with the monolith's DB writes from day one — never start with naive dual-writes.
4. **Move read models to projections** (CQRS) where read pressure justifies it, validating eventual-consistency behavior with real traffic.
5. **Decommission the synchronous path** for a flow only after its event-based replacement has proven itself in production (shadow/compare for a period).
6. **Invest in observability first** (correlation IDs, tracing, lag alerting) — you cannot operate the new async paths blind.

**What I'd actually do:** start with one low-risk, high-fan-out flow (e.g. notifications), prove the platform (broker, registry, outbox, DLQ, tracing), build team muscle, then expand. Resist converting strongly-consistent core transactions to async early — those carry the most risk and the least immediate payoff.

### Q30. [Behavioral] Describe a time you had to convince a team to adopt (or to NOT adopt) event-driven architecture. How did you handle the disagreement?

**Situation:** A team wanted to re-platform a moderately-trafficked internal CRUD admin tool onto Kafka + CQRS + event sourcing because it was "the modern way." **Task:** As the staff engineer, I had to assess fit and align the team.

**Action:** Rather than dismissing it, I ran a structured trade-off analysis with them: we mapped the actual requirements (modest scale, strong-consistency expectations from admins editing records, a two-person team with no Kafka ops experience). I framed the decision around concrete costs — operational burden of a broker, eventual-consistency UX issues for an edit-heavy tool, and the team's on-call capacity — versus speculative benefits. I proposed a middle path: keep it as a well-structured modular monolith now, but introduce a transactional outbox for the *one* genuine integration point (notifying a downstream analytics system), so we'd gain async decoupling exactly where it paid off without taking on the whole paradigm.

**Result:** The team agreed; we shipped faster, avoided an over-engineered system, and the outbox seam later made it trivial to add a second consumer. **Lesson / what I emphasize:** architecture decisions should be driven by requirements and team context, not by trend-following. The senior skill is saying "not here" to a powerful pattern — and showing the reasoning so the team owns the decision rather than feeling overruled. I also documented it as an ADR so the rationale survived team changes.

### Q31. [Theory] What are the security implications of event-driven systems, and how do you address them?

EDA widens the attack and compliance surface in ways synchronous APIs don't:

- **Broker access control:** Kafka topics need fine-grained ACLs / RBAC so a compromised service can't read or produce to topics it shouldn't. Default-open topics are a common, dangerous misconfiguration.
- **Encryption:** TLS in transit between clients and brokers, and encryption at rest for the durable log (which may retain sensitive events for days). The persistent, replayable nature of logs means PII lingers far longer than in a transient request.
- **PII and right-to-erasure (GDPR/CCPA):** an append-only or long-retention log conflicts with "delete my data." Mitigations: minimize PII in events (carry references, not raw PII), tokenize, or **crypto-shred** (per-subject encryption keys you can destroy).
- **Event authenticity / integrity:** without signing, a malicious producer can inject forged events that downstream services trust implicitly (events are often less scrutinized than API inputs). Consider signed events or authenticated, ACL-restricted producers, and validate schemas/payloads on consume.
- **Poison/abuse via DLQ:** DLQ contents may include sensitive payloads and need the same access controls and retention policy as the source.
- **Replay as an attack/risk vector:** replay tooling is powerful; restrict who can trigger replays and into which environments, since a careless replay can duplicate financial side effects.

The principle: treat the event log as **sensitive, long-lived, broadly-readable infrastructure** and apply least-privilege, encryption, data-minimization, and auditability accordingly.

### Q32. [Practical] How do you load-test and capacity-plan a high-throughput event platform?

**Scenario:** Provision a platform expected to handle 200k events/sec at peak with sub-second end-to-end latency.

**Approach:**
- **Model the workload realistically:** event size distribution, key cardinality (partition skew is the silent killer), produce/consume ratio, fan-out factor, and burst shape — not just average rate.
- **Partition sizing:** parallelism is capped by partition count, so size partitions for peak consumer parallelism *plus headroom*, accepting that over-partitioning has its own cost (more open files, longer rebalances, more metadata). Plan it; you can add but not easily remove partitions, and changing count reshuffles keys.
- **Producer tuning:** batching (`linger.ms`, `batch.size`), compression (lz4/zstd), and `acks` setting — `acks=all` for durability vs latency trade-off.
- **Consumer tuning:** `max.poll.records`, fetch sizes, and ensuring handler work is non-blocking.
- **Test with production-like data and chaos:** kill brokers mid-test (replication/ISR behavior), inject slow consumers (lag/backpressure), and replay-storm to validate DLQ and recovery.
- **Capacity headroom:** size for peak + N% with the ability to absorb a broker failure (so the cluster must run at <100/(replicas) of capacity to survive a node loss).

**Real benchmark color:** a single well-tuned Kafka broker can sustain hundreds of MB/s; LinkedIn historically ran Kafka at trillions of messages/day. The constraint is rarely raw broker throughput — it's **consumer processing speed, partition skew, and downstream backpressure**. So load-test the *consumers and their downstream dependencies*, not just the broker. Establish SLOs (p99 end-to-end latency, max lag) and alert on them.

### Q33. [Theory] How do you prevent an event-driven system from degenerating into a "distributed monolith"?

A distributed monolith is the worst of both worlds: the operational complexity of microservices with the tight coupling of a monolith — services that must be deployed together because an event change ripples everywhere. EDA makes this easy to stumble into. Preventive measures:

- **Bounded contexts and well-defined event contracts:** events should model stable business facts owned by one context, not leak internal implementation details. If changing a service's internals forces an event schema change, the boundary is wrong.
- **Prefer event-carried state transfer over chatty notification + callback** to remove runtime coupling (Q7).
- **Enforce schema compatibility** so producers and consumers deploy independently (Q14, Q27).
- **Avoid shared mutable state and synchronous chains disguised as events** (an "event" that demands an immediate reply is really an RPC).
- **Watch coupling metrics:** if a set of services always change and deploy together, that's a coupling smell — they may belong in one service.
- **Resist over-decomposition:** more services ≠ better. Split only along genuine business/scaling boundaries.

The litmus test: can each service be **developed, deployed, and reasoned about independently**? If not, you have distribution without decoupling — the worst outcome. The fix is usually about boundaries and contracts, not more middleware.

---

## ✅ Key Takeaways

- **Events are facts (past tense), commands are instructions (imperative), messages are the envelope.** Choosing events maximizes decoupling; choosing commands implies the sender knows the outcome.
- **At-least-once + idempotent consumers is the pragmatic default.** True end-to-end exactly-once across external side effects is generally impossible; Kafka EOS only holds within Kafka.
- **Solve the dual-write problem with the Transactional Outbox** (ideally via CDC/Debezium), never with naive write-then-publish.
- **Order is per-partition/per-key, not global.** Partition by the entity whose ordering matters; don't ask for more ordering than the domain needs.
- **Schema is a long-lived contract.** Use a registry, add only optional fields, version topics for breaking changes, and govern aggressively at scale.
- **Event streams (Kafka) enable replay and multi-consumer fan-out; queues (SQS/RabbitMQ) do destructive work distribution.** Pick per use case.
- **Eventual consistency is the price of decoupling** — design UX and logic to tolerate the lag.
- **EDA is not always the answer.** It hurts with strong-consistency needs, simple request/response flows, low scale, or immature ops. Hybrid (sync within a context, async across) is common and correct.
- **Observability (correlation IDs, distributed tracing, lag/DLQ alerting) is non-negotiable** for operating async systems.

## ⚠️ Common Pitfalls

- **Naive dual writes** (DB then broker) that silently diverge on crash — always use an outbox.
- **Forgetting idempotency**, leading to double-charges, double-emails, and duplicate side effects under at-least-once redelivery.
- **Assuming global ordering** or breaking per-key ordering with parallel competing consumers or retries (`max.in.flight > 1` without idempotence).
- **Breaking schema changes** (removing/renaming/retyping required fields) that take down consumers on deploy.
- **No DLQ / infinite retries** on poison messages causing head-of-line blocking and stalled partitions.
- **Treating events as RPC** — an "event" that demands an immediate synchronous reply is a coupling smell and a distributed-monolith seed.
- **Unbounded growth** of dedup and outbox tables, and ignoring consumer lag until users complain.
- **Adopting event sourcing/CQRS by default** for simple CRUD, drowning the team in incidental complexity.
- **Ignoring PII/GDPR** on long-retention, replayable logs (no crypto-shredding, raw PII in events).
- **Over-decomposition** into too many services, producing a distributed monolith where everything must deploy together.

## 📚 Further Reading

- **Martin Fowler — "What do you mean by 'Event-Driven'?"** (martinfowler.com) — the canonical taxonomy of event notification, ECST, event sourcing, and CQRS.
- **"Designing Data-Intensive Applications"** by Martin Kleppmann — chapters on replication, stream processing, consistency, and the unbundled database; essential for the consistency reasoning above.
- **"Building Event-Driven Microservices"** by Adam Bellemare — practical patterns for event-carried state transfer, schema management, and the data on the outside.
- **microservices.io (Chris Richardson)** — authoritative pattern catalog for Saga, Transactional Outbox, CQRS, and Event Sourcing, with Java/Spring examples.
- **Confluent documentation & "Kafka: The Definitive Guide"** (Narkhede, Shapira, Palino) — exactly-once semantics, transactions, partitioning, and operational tuning.
- **debezium.io documentation** — Change Data Capture and the outbox event router for production-grade outbox implementations.
