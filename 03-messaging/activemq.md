# ActiveMQ Interview Preparation Guide

Apache ActiveMQ is the most widely deployed open-source JMS message broker, providing reliable asynchronous messaging via the JMS API, with two distinct implementations today: ActiveMQ "Classic" (the original 5.x line) and ActiveMQ Artemis (the next-generation, high-throughput broker built on a non-blocking core). This guide takes you from JMS fundamentals to broker internals, networks of brokers, and cross-product trade-offs against RabbitMQ and Kafka.

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

### Q1. [Theory] What is ActiveMQ and where does it fit in a system architecture?

ActiveMQ is a **message-oriented middleware (MOM)** that decouples producers from consumers using asynchronous messaging. Instead of services calling each other synchronously (and failing together), a producer writes a message to the broker and moves on; consumers read at their own pace. This buys you **temporal decoupling** (the consumer can be down when the message is sent), **load leveling** (the broker absorbs spikes), and **fan-out** (one message reaches many consumers). ActiveMQ implements the **JMS (Jakarta Messaging / Java Message Service)** specification, so application code is written against a standard API rather than a proprietary one. It is typically the integration backbone in enterprise Java systems — order processing, event notification, ESB-style routing — anywhere you need guaranteed, ordered, transactional delivery within a single data center.

```
   Producer            Broker (ActiveMQ)            Consumer
  ┌────────┐    send   ┌──────────────────┐  deliver  ┌────────┐
  │ Service│ ───────►  │  Queue / Topic   │ ────────► │ Service│
  │   A    │           │  + persistence   │           │   B    │
  └────────┘           └──────────────────┘           └────────┘
                          (store & forward)
```

### Q2. [Theory] What is the difference between a Queue and a Topic (P2P vs Pub/Sub)?

This is the core JMS distinction. A **Queue** uses the **point-to-point (P2P)** model: each message is delivered to exactly **one** consumer, even if many consumers are attached (they compete, and the broker load-balances). The message stays in the queue until someone consumes it, so a consumer that connects later still gets the backlog. A **Topic** uses the **publish/subscribe (pub/sub)** model: each message is delivered to **all** current subscribers (fan-out). By default a topic is *non-durable* — if a subscriber is offline when a message is published, it misses it entirely (fire-and-hose). Use queues for **work distribution / commands** (process this order once) and topics for **broadcasting events** (the price changed, notify everyone).

```
   QUEUE (P2P)                          TOPIC (Pub/Sub)
   one message → one consumer           one message → all subscribers
   ┌───┐   ┌──► C1  (gets msg)          ┌───┐   ┌──► S1 (gets copy)
   │ Q │──►│                            │ T │──►├──► S2 (gets copy)
   └───┘   └──► C2  (idle)              └───┘   └──► S3 (gets copy)
```

### Q3. [Coding] Write a minimal JMS producer and consumer for a queue.

**Problem:** Send a `TextMessage` to a queue named `orders.in` and consume it. Use the Jakarta Messaging API (Jakarta EE 9+ / ActiveMQ Artemis or Classic 6.x).

```java
import jakarta.jms.*;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;

public class SimpleJms {

    public static void produce() throws JMSException {
        ConnectionFactory factory =
            new ActiveMQConnectionFactory("tcp://localhost:61616");
        // try-with-resources auto-closes Connection/Session
        try (JMSContext ctx = factory.createContext()) {       // JMS 2.0 simplified API
            Queue queue = ctx.createQueue("orders.in");
            ctx.createProducer()
               .setDeliveryMode(DeliveryMode.PERSISTENT)        // survive broker restart
               .send(queue, "order-42");
        }
    }

    public static void consume() throws JMSException {
        ConnectionFactory factory =
            new ActiveMQConnectionFactory("tcp://localhost:61616");
        try (JMSContext ctx = factory.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
            Queue queue = ctx.createQueue("orders.in");
            JMSConsumer consumer = ctx.createConsumer(queue);
            String body = consumer.receive(5000).getBody(String.class); // 5s timeout
            System.out.println("Received: " + body);
        }
    }
}
```

**Notes / edge cases:**
- `receive(timeout)` returns `null` if no message arrives in time — always null-check.
- JMS 2.0's `JMSContext` replaces the verbose JMS 1.1 `Connection` + `Session` + `MessageProducer` chain.
- For Classic (`javax.jms`) the imports are `javax.jms.*` and the factory is `org.apache.activemq.ActiveMQConnectionFactory`.
- **Time:** O(1) per send/receive. **Space:** O(1) client-side; the broker stores O(n) for n undelivered messages.

### Q4. [Theory] What is the difference between `javax.jms` and `jakarta.jms`?

This is purely a **namespace change** driven by Oracle transferring Java EE to the Eclipse Foundation. Java EE became **Jakarta EE**, and starting with **Jakarta EE 9** all packages moved from `javax.*` to `jakarta.*`. The JMS API itself (now "Jakarta Messaging") is functionally the same, but the import statements differ: `javax.jms.MessageProducer` vs `jakarta.jms.MessageProducer`. Practically: **Spring Boot 2.x / Java 8–11** uses `javax.jms`; **Spring Boot 3.x / Java 17+** uses `jakarta.jms`. ActiveMQ Classic 5.x exposes `javax`, ActiveMQ Classic 6.x and Artemis support `jakarta`. A common migration headache is mixing the two — a client built against `javax` will not satisfy a `jakarta` factory interface.

### Q5. [Practical] How do you start ActiveMQ and verify it is running?

For local development you download the binary, run `bin/activemq start` (Classic) or `bin/artemis run` (Artemis), then open the **web console** at `http://localhost:8161` (default creds `admin/admin` — *change these immediately*). The broker listens on **61616** for the OpenWire/Core TCP protocol, **5672** for AMQP, **61613** for STOMP, and **1883** for MQTT. To verify message flow, send a test message from the console's Queues tab and watch the enqueue/dequeue counters. In production you would instead run it as a managed service, harden the JAAS login config, disable unused transport connectors, and front it with monitoring (JMX → Prometheus). The default `admin/admin` and the open management port are the two most common security oversights I look for in a review.

---

## 🟡 Intermediate (3–7 yrs)

### Q6. [Theory] Explain JMS acknowledgement modes and when to use each.

Acknowledgement controls *when* the broker considers a message successfully consumed and removes it. JMS defines four modes (set when creating the session/context):

| Mode | Behaviour | Use case |
|------|-----------|----------|
| `AUTO_ACKNOWLEDGE` | Broker auto-acks after `onMessage`/`receive` returns | Default; simple, at-least-once on crash |
| `CLIENT_ACKNOWLEDGE` | App calls `message.acknowledge()` explicitly | Batch-ack many messages at once |
| `DUPS_OK_ACKNOWLEDGE` | Lazy/batched acks, duplicates possible | High throughput, idempotent consumers |
| `SESSION_TRANSACTED` | Acks are part of a transaction (commit/rollback) | Atomic multi-message units of work |

The critical subtlety: with `AUTO_ACKNOWLEDGE`, if the consumer crashes **after** processing but **before** the ack reaches the broker, the message is redelivered — so consumers must be **idempotent**. `CLIENT_ACKNOWLEDGE` acknowledges *all* messages received on that session up to and including the acked one, not just one — a frequent gotcha. For "exactly-processed-once" semantics within a database boundary, use transacted sessions (or XA) so the message ack and the DB write commit together.

### Q7. [Theory] What is a durable subscription and how does it differ from a non-durable one?

A normal topic subscriber only receives messages published **while it is connected** — disconnect and you lose everything sent in the interim. A **durable subscription** tells the broker to retain messages for a specific subscriber (identified by a **client ID + subscription name**) even while it is offline, then deliver the backlog on reconnect. This gives topics queue-like reliability for a known set of consumers. You set it up via `connection.setClientID("billing-service")` and `session.createDurableSubscriber(topic, "subA")` (JMS 1.1) or `ctx.createDurableConsumer(topic, "subA")` (JMS 2.0). **Caveat:** the broker now holds messages indefinitely for that subscription, so a durable subscriber that never reconnects becomes an **unbounded memory/disk leak** — you must `unsubscribe()` obsolete subscriptions. JMS 2.0 also added **shared durable subscriptions**, letting multiple consumers load-balance a single durable subscription (combining fan-out durability with consumer scaling).

### Q8. [Theory] Compare KahaDB and JDBC persistence in ActiveMQ Classic.

Persistence is what lets `PERSISTENT` messages survive a broker restart. **KahaDB** is the default in Classic — a file-based, append-only message store with a B-tree index (the metadata `db.data`) and rolling journal log files (`db-N.log`). It is fast (sequential writes), self-contained, and the recommended choice for most deployments. **JDBC persistence** stores messages in a relational database (three tables: `ACTIVEMQ_MSGS`, `ACTIVEMQ_ACKS`, `ACTIVEMQ_LOCK`). It is slower (random I/O, network round-trips, serialization overhead) but appealing when you already operate a highly-available clustered DB and want a single backup/HA story, or when you use the shared-JDBC lock for master/slave failover. A middle option is the **JDBC + journal (LevelDB-style) hybrid**, though LevelDB itself is deprecated. **Artemis** does not use KahaDB at all — it has its own high-performance append-only journal with optional libaio (native async I/O on Linux).

```
KahaDB layout                         JDBC layout
┌──────────────┐                      ┌────────────────────┐
│ db.data      │ ← B-tree index       │ ACTIVEMQ_MSGS      │ ← messages
│ db-1.log     │ ┐                    │ ACTIVEMQ_ACKS      │ ← durable sub acks
│ db-2.log     │ ├ journal segments   │ ACTIVEMQ_LOCK      │ ← HA master lock
│ db-3.log     │ ┘                    └────────────────────┘
└──────────────┘                          (shared DB)
fast, local, default                   slower, central HA
```

### Q9. [Theory] What is the difference between ActiveMQ Classic and ActiveMQ Artemis, and which should I pick in 2026?

They are two separate codebases under the same Apache umbrella. **Classic (5.x/6.x)** is the mature, battle-tested original with the broadest plugin/feature surface (advisory topics, the rich message-broker camel routes, decades of production hardening). **Artemis** descends from HornetQ (donated by Red Hat) and was built for **high throughput and low latency** using a non-blocking, asynchronous core, an efficient append-only journal, and an **address/queue routing model** that unifies queues and topics under "addresses" with routing types (ANYCAST = queue semantics, MULTICAST = topic semantics). Artemis is also what backs **Red Hat AMQ 7+**. The direction of travel: Artemis is the **strategic future** — new development focuses there, and it scales better. In 2026 I default to **Artemis for greenfield** (better performance, active investment, multi-protocol parity) and keep **Classic** only where a team depends on a Classic-specific feature or has a large existing 5.x footprint not worth migrating yet.

### Q10. [Practical] How do you integrate ActiveMQ with Spring Boot, and what changed between Boot 2 and Boot 3?

Spring Boot autoconfigures a `ConnectionFactory`, `JmsTemplate`, and listener container from `spring.activemq.*` (Classic) or `spring.artemis.*` (Artemis) properties. You consume with `@JmsListener` and produce with `JmsTemplate`. The big change: **Boot 2 uses `javax.jms`; Boot 3 uses `jakarta.jms`** (and requires Java 17+). Also, the classic starter is `spring-boot-starter-activemq` and the Artemis starter is `spring-boot-starter-artemis`.

```java
@Configuration
@EnableJms
public class JmsConfig {
    // Pooled factory avoids creating a connection per send (huge perf win)
    @Bean
    public JmsListenerContainerFactory<?> jmsFactory(ConnectionFactory cf,
            DefaultJmsListenerContainerFactoryConfigurer cfg) {
        var factory = new DefaultJmsListenerContainerFactory();
        cfg.configure(factory, cf);
        factory.setSessionTransacted(true);          // tie ack to local tx
        factory.setConcurrency("3-10");              // 3..10 concurrent consumers
        return factory;
    }
}

@Component
class OrderListener {
    private final JmsTemplate jms;
    OrderListener(JmsTemplate jms) { this.jms = jms; }

    @JmsListener(destination = "orders.in")
    public void onOrder(String body) {
        // process...
        jms.convertAndSend("orders.processed", body.toUpperCase());
    }
}
```

```properties
# application.properties (Spring Boot 3 + Artemis)
spring.artemis.mode=native
spring.artemis.broker-url=tcp://localhost:61616
spring.artemis.user=app
spring.artemis.password=${MQ_PASSWORD}
spring.jms.listener.acknowledge-mode=auto
```

**Production tip:** wrap the `ConnectionFactory` in a pooled one (`PooledJMS` / `JmsPoolConnectionFactory`). `JmsTemplate` opens and closes a connection+session per call by default, which is catastrophic for throughput — pooling is the single most important fix I make to naive Spring JMS code.

### Q11. [Coding] Implement a transactional consumer that writes to a DB and only acks on success.

**Problem:** Consume an order message, persist it, and ensure the message is *not* acknowledged (so it redelivers) if the DB write fails — without distributed XA.

```java
@Component
class TransactionalOrderConsumer {

    private final OrderRepository repo;
    TransactionalOrderConsumer(OrderRepository repo) { this.repo = repo; }

    // sessionTransacted=true on the container; Spring commits the JMS session
    // AFTER the method returns normally, rolls back (→ redelivery) on exception.
    @JmsListener(destination = "orders.in", containerFactory = "jmsFactory")
    public void handle(Order order) {
        try {
            repo.save(order);              // DB write
        } catch (DataAccessException e) {
            // throw → Spring rolls back the JMS session → message redelivered
            throw new RuntimeException("retryable DB failure", e);
        }
    }
}
```

**Approaches & trade-offs:**
1. **Transacted JMS session only (above):** message ack and processing are atomic *within JMS*, but the DB commit is a separate transaction → a tiny window where DB committed but ack rolled back ⇒ **duplicate**. Solution: make `save` idempotent (upsert on a business key).
2. **Full XA / `JtaTransactionManager`:** the JMS ack and DB commit share one global transaction (two-phase commit) → true atomicity. Cost: requires an XA-capable broker + datasource + transaction manager, and 2PC is slow and operationally heavy.
3. **Transactional outbox / dedup table (recommended at scale):** consume with auto-ack, write to DB + a `processed_message_ids` row in one local DB transaction; reject already-seen IDs. Avoids 2PC entirely.

**Edge cases:** poison messages that always fail will loop until they hit the **redelivery limit** (default 6 in Classic) and then go to the **Dead Letter Queue (`ActiveMQ.DLQ`)** — monitor it. **Time:** O(1) per message; **Space:** O(1) plus the dedup table O(n) for approach 3.

### Q12. [Practical] A consumer is processing messages but throughput is far below the producer rate. How do you diagnose and fix it?

I'd treat this as a classic backlog-building scenario and work it systematically:
1. **Confirm the symptom** via the web console / JMX: rising `QueueSize`, `EnqueueCount` >> `DequeueCount`, growing `MemoryPercentUsage`.
2. **Prefetch:** ActiveMQ pushes a batch of messages to each consumer (default prefetch 1000 for queues). If one slow consumer hoards a big prefetch buffer while others sit idle, throughput collapses — lower `jms.prefetchPolicy.queuePrefetch` (e.g. to 1 for slow, uneven workloads).
3. **Scale consumers:** increase the listener `concurrency` and add competing consumers on the queue (horizontal scale of the P2P model).
4. **Connection pooling:** confirm producers/consumers aren't recreating connections per message.
5. **Flow control / Producer Flow Control:** if the broker hit its memory/store limit it throttles producers — check `systemUsage` limits and whether disk is full.
6. **Slow processing:** if the bottleneck is downstream (DB, external API), no amount of broker tuning helps — make the handler async/batched or offload.

In production I'd add **per-destination metrics and alerts on queue depth** so this is caught before it becomes an incident, and I'd ensure consumers acknowledge promptly rather than holding a transacted session open across slow I/O.

---

## 🟠 Advanced (8–12 yrs)

### Q13. [Theory] Explain a Network of Brokers. How does message forwarding and the "stuck messages" problem work?

A **network of brokers** connects multiple ActiveMQ brokers so that consumers on one broker can receive messages produced on another — used for geographic distribution, scaling consumers beyond one broker, and store-and-forward across data centers. Brokers establish **network connectors** that propagate **consumer demand**: when broker B has a consumer for `orders.in` but the message is on broker A, A forwards it to B on demand (demand-forwarding bridge, driven by advisory messages). It is **not** a shared cluster — each broker has its own store; messages move by forwarding.

```
        Network Connector (demand-forwarding)
  ┌──────────┐  advisory: "B has consumer"   ┌──────────┐
  │ Broker A │ ◄────────────────────────────  │ Broker B │
  │  msg →   │ ─────────────────────────────► │  → C2    │
  └──────────┘        forward message          └──────────┘
     ▲ C1 (idle)
```

The infamous **"stuck messages"** problem: with multiple network hops or returning consumers, messages can get forwarded to a broker whose consumer then disappears, leaving them stranded. Mitigations: set a sensible `networkTTL` (max hops), enable `decreaseNetworkConsumerPriority` so local consumers are preferred over remote ones, use **conduit subscriptions** to collapse duplicate demand, and prefer **replicated/HA pairs** rather than deep mesh topologies. In Artemis the equivalent is **cluster connections** with message redistribution, which handle this more gracefully.

### Q14. [Theory] How do you achieve high availability and what changed from master/slave to modern HA?

The goal is no message loss and fast failover when a broker dies. **Classic** historically offered **shared-storage master/slave**: both brokers point at the same KahaDB directory (on shared NFS/SAN) or the same JDBC `ACTIVEMQ_LOCK` table; whoever grabs the exclusive file/DB lock becomes master, the other waits as a hot standby. Failover is automatic because clients use a **`failover:` transport URI** (`failover:(tcp://b1:61616,tcp://b2:61616)`) that reconnects to whichever broker is live. The old **pure (non-shared) master/slave** replication was removed. **Artemis** provides built-in HA via **replication** (master streams journal to a backup over the network — no shared disk needed, ideal for cloud) or **shared-store**, plus **quorum/pluggable lock managers (ZooKeeper-based)** to avoid split-brain. Modern practice (especially on Kubernetes / Red Hat AMQ) favors **Artemis replication with a quorum** over fragile shared-NFS setups, because NFS locking semantics under failure were a perennial source of data corruption and dual-master incidents.

### Q15. [Practical] How would you design exactly-once-style processing on top of ActiveMQ, given JMS is at-least-once?

JMS guarantees **at-least-once** for persistent messages — you cannot get true exactly-once delivery, so the real goal is **exactly-once *effect*** (idempotent processing). My production design:
1. **Producer side — dedup ID:** stamp every message with a stable business idempotency key (`JMSMessageID` is broker-assigned and changes on resend, so use your own header).
2. **Broker side — persistence + transactions:** send persistently inside a transacted session so a producer crash never sends a partial batch.
3. **Consumer side — idempotent write + dedup store:** in a single **local DB transaction**, insert the message ID into a `processed_ids` table with a unique constraint and apply the side effect; a duplicate insert fails → skip the side effect. This is the **transactional outbox/inbox** pattern and avoids XA's 2PC cost.
4. **Poison handling:** configure a **redelivery policy** with exponential backoff and a DLQ so a permanently-failing message doesn't block the queue.

Trade-off vs full XA: XA gives atomicity across JMS+DB but is slow, hard to operate, and many cloud datasources don't support it well. The idempotent-inbox approach is what I actually ship — it's resilient, cheap, and DB-native.

### Q16. [Theory] Compare ActiveMQ vs RabbitMQ vs Kafka. When do you choose each?

| Dimension | ActiveMQ | RabbitMQ | Kafka |
|-----------|----------|----------|-------|
| Model | JMS broker (queue/topic), smart broker | AMQP broker, exchanges + routing, smart broker | Distributed log, dumb broker / smart consumer |
| Delivery | Push, at-least-once, broker tracks acks | Push, flexible routing | Pull, offset-based, replayable |
| Ordering | Per-destination (groups for partial) | Per-queue | Per-partition |
| Retention | Until consumed (then deleted) | Until consumed | Time/size based — **messages kept after read** |
| Throughput | High (Artemis) | High | Very high (millions/s) |
| Best for | Enterprise Java/JMS, transactions, request/reply | Complex routing, microservice RPC, per-message logic | Event streaming, log aggregation, replay, analytics |

The mental model: **ActiveMQ/RabbitMQ are *brokers*** that deliver-then-delete and track per-message state — great for **commands and task queues** where each message is consumed once and you want transactional, JMS-standard semantics. **Kafka is a *distributed commit log*** — consumers track their own offset, messages are **retained and replayable**, and it shines for **high-volume event streaming, multiple independent consumer groups, and reprocessing history**. I pick **ActiveMQ** when I'm in a JMS/Java enterprise shop needing transactions and standards compliance, **RabbitMQ** when I need rich routing topologies and lightweight RPC, and **Kafka** when I need durable event streams, replay, or extreme throughput. Choosing Kafka as a "queue" or ActiveMQ for "stream replay" is a common architectural mismatch.

### Q17. [Practical] Walk through a real-world incident: the broker store fills up and producers block. What happened and how do you prevent it?

**Case study (order-processing platform):** During a Black-Friday traffic spike, a downstream fulfillment consumer slowed (its DB was overloaded). Messages piled up in `orders.in`; KahaDB store usage hit the configured `storeUsage` limit; ActiveMQ's **Producer Flow Control** kicked in and **blocked the producers' `send()` calls**, which cascaded back into the order API's request threads → API latency spiked → upstream timeouts. From the outside it looked like the API was broken, but the root cause was a stuck consumer plus an unbounded queue.

**Resolution & prevention:**
- Immediate: scaled fulfillment consumers and lowered their prefetch to spread load; the backlog drained.
- **Set `systemUsage` limits deliberately** (memory, store, temp) and decide per-destination whether you want **flow control (block)** or **fast-fail / spool-to-disk** — blocking producers is sometimes worse than rejecting.
- Add **DLQ + expiry (`timeToLive`)** so stale orders don't accumulate forever.
- **Monitor queue depth and store-usage percentage** with alerting; treat rising depth as the leading indicator.
- Add a **circuit breaker** so the API sheds load gracefully instead of blocking on `send()`.

The lesson I emphasize: a message broker turns a *consumer* failure into a *producer* problem if you haven't bounded the queue — capacity planning and backpressure policy are not optional.

---

## 🔴 Expert (15+ yrs)

### Q18. [Theory] How does ActiveMQ's persistence guarantee durability, and what are the failure-mode trade-offs of journal sync settings?

Durability hinges on **when the write to disk is actually flushed (`fsync`)**. KahaDB (Classic) and the Artemis journal are append-only logs; a `PERSISTENT` send is acknowledged to the producer only after the broker writes the journal record. The trade-off lever is **sync vs async journaling**: with synchronous sync, every persistent message forces an `fsync` before ack — durable even on power loss, but throughput is bounded by disk fsync latency. With periodic/batched sync (Artemis `journal-buffer-timeout`, or Classic `enableJournalDiskSyncs=false`), the broker batches writes and acks earlier — far higher throughput, but a hard crash can lose the last few un-synced milliseconds of messages. Artemis mitigates this with **journal buffering + group commit** and **libaio** (native async I/O) so it can fsync batches with minimal latency, plus a separate **paging** mechanism: when an address exceeds its memory limit, messages page to disk rather than blocking, decoupling durability from memory pressure. The expert judgement call is matching the sync policy to the SLA: financial transactions get sync-every-write on replicated storage; high-volume telemetry can tolerate batched sync.

### Q19. [Theory] Discuss message ordering guarantees and how to preserve order while scaling consumers.

Strict FIFO and parallelism are fundamentally in tension. A single queue with **one consumer** preserves order trivially but doesn't scale. Add **competing consumers** and order is lost — message 1 and 2 may be processed concurrently by different consumers. ActiveMQ's answer is **Message Groups**: set the `JMSXGroupID` header, and the broker pins all messages with the same group ID to the **same consumer** (consistent hashing), guaranteeing order *within a group* while still load-balancing *across* groups — analogous to Kafka partition keys. The trade-off: a "hot" group (e.g. one very busy account) becomes a single-consumer bottleneck, and consumer failover causes a group to migrate (with a brief reordering risk at the boundary). For exclusive-consumer ordering across an entire destination, the `consumer.exclusive=true` option designates one consumer as primary with automatic failover. The principle: **partition the ordering requirement to the narrowest key that the business actually needs** (per-account, per-aggregate) rather than demanding global order, which never scales.

### Q20. [Practical] You're migrating a large ActiveMQ Classic 5.x estate to Artemis. How do you approach it?

I treat this as a multi-quarter program, not a flip:
1. **Inventory & feature audit:** catalog destinations, durable subs, message volumes, and any **Classic-specific features** (advisory-topic usage, specific redelivery plugins, Camel routes embedded in the broker). Some have no 1:1 Artemis equivalent and need redesign.
2. **Protocol bridge / coexistence:** Artemis speaks **OpenWire**, so Classic clients can often connect to Artemis with minimal change; run both brokers and **bridge** queues during transition so consumers can be cut over gradually.
3. **Address model mapping:** translate Classic queues/topics into Artemis **addresses** with ANYCAST/MULTICAST routing types and configure `address-settings` (DLQ, expiry, redelivery, paging) — this is where most behavioral surprises live.
4. **Persistence & HA redesign:** drop KahaDB/shared-NFS in favor of Artemis **replication + quorum**; validate failover and message survival under chaos testing.
5. **Performance baseline:** load-test against production-like volumes; Artemis tuning knobs (journal type, paging thresholds, buffer timeout) differ from Classic.
6. **Cutover per service** behind feature flags, with the old broker as rollback, and **dual-read verification** on critical queues.

The risk I flag loudest: silent behavioral differences in redelivery/DLQ/ordering semantics. I'd write **contract tests** that assert delivery semantics and run them against both brokers before trusting the migration.

### Q21. [Behavioral] Tell me about a time you had to choose between ActiveMQ and another technology, and how you drove the decision.

Frame this with a structured narrative (Situation–Task–Action–Result). A strong answer sounds like: *"We were adding an event pipeline to a Java-heavy order platform. The team's instinct was Kafka because it was fashionable, but our actual requirement was reliable per-order command delivery with JMS transactions and request/reply — not stream replay or analytics. Task: make a defensible, reversible decision. Action: I ran a weighted decision matrix (delivery semantics, ops burden, team JMS expertise, transactional needs, replay needs), prototyped both for a week against our real volume, and showed Kafka added significant operational overhead (ZooKeeper at the time, partition management) for capabilities we didn't need, while ActiveMQ Artemis met the latency and transactional requirements with our existing skill set. Result: we shipped on Artemis, kept Kafka on the roadmap for the genuine analytics use case that emerged a year later, and avoided running a streaming platform we'd have operated poorly."* The signal interviewers want: you chose based on **requirements and trade-offs, not hype**, you **de-risked with a spike**, and you separated the **command** use case from the **streaming** one rather than forcing one tool to do both.

### Q22. [Theory] What are the key security considerations when operating ActiveMQ in production?

Security spans authentication, authorization, transport, and exposure:
- **Authentication:** never ship the default `admin/admin`. Use a JAAS login module — `PropertiesLoginModule` for simple setups, **LDAP** for enterprise, and rotate credentials. Artemis supports pluggable security managers.
- **Authorization:** lock down **per-destination permissions** (who can create/send/consume on which queues/topics) via the authorization plugin / Artemis `security-settings`. Apply least privilege — a producer service shouldn't be able to consume or create arbitrary destinations.
- **Transport encryption:** enable **TLS** on the connectors (`ssl://`/`tcp+ssl`) so OpenWire/AMQP/STOMP traffic and credentials aren't in cleartext; consider **mutual TLS** for service-to-service auth.
- **Attack surface:** disable unused transport connectors (don't expose MQTT/STOMP if you only use OpenWire), restrict the **Jolokia/JMX management** endpoint (a known RCE vector — e.g. the Jolokia-based CVEs in older versions), keep the broker off the public internet, and patch promptly.
- **Message-level:** validate/sanitize message bodies (especially STOMP/MQTT from less-trusted clients), and beware **Java deserialization** of `ObjectMessage` — restrict trusted packages (`setTrustedPackages`) or avoid `ObjectMessage` entirely, since unrestricted deserialization has produced serious ActiveMQ CVEs (notably the OpenWire deserialization RCE).

The deserialization and management-endpoint issues are the ones I check first in any audit — they've each produced real, widely-exploited CVEs.

---

## ✅ Key Takeaways

- **Queues = P2P (one consumer), Topics = Pub/Sub (all subscribers).** Durable subscriptions give topics queue-like reliability for known consumers — but must be unsubscribed when obsolete.
- **JMS guarantees at-least-once**, so consumers must be **idempotent**; achieve exactly-once *effect* with a dedup/inbox table, not by chasing exactly-once delivery.
- **Acknowledgement mode and transactions** decide when a message is "done" — `SESSION_TRANSACTED` + idempotent DB writes is the pragmatic durable pattern; reserve XA for true cross-resource atomicity.
- **KahaDB is the fast default (Classic); Artemis uses its own journal** with paging and async I/O. JDBC persistence trades speed for centralized HA.
- **Artemis is the strategic future** — pick it for greenfield; Classic remains for legacy/feature-specific needs. `javax.jms` → `jakarta.jms` tracks Boot 2 → Boot 3 / Java 17+.
- **Bound your queues and set backpressure policy** — a consumer outage becomes a producer outage via Producer Flow Control if you don't.
- **Choose the right tool:** ActiveMQ/RabbitMQ for commands/tasks (deliver-then-delete); Kafka for replayable event streams.

## ⚠️ Common Pitfalls

- Using `JmsTemplate` without a **pooled connection factory** — opens/closes a connection per send, destroying throughput.
- Assuming `CLIENT_ACKNOWLEDGE` acks a single message — it acks **all** messages received on the session up to that point.
- Forgetting that **non-durable topic subscribers miss messages** sent while offline; using a topic where a durable sub or queue was needed.
- Leaving **default `admin/admin`** credentials and the JMX/Jolokia management port exposed — both are documented attack vectors.
- Using `ObjectMessage` with unrestricted deserialization — a serious, exploited RCE class; restrict trusted packages or avoid it.
- Building **deep network-of-brokers meshes** and hitting stuck-message / message-loop problems; prefer HA pairs and bounded `networkTTL`.
- Relying on **shared-NFS master/slave** for HA — NFS locking under failure has caused split-brain and corruption; prefer Artemis replication + quorum.
- Demanding **global message ordering** with many consumers — use `JMSXGroupID` to scope ordering to a business key instead.
- Not setting **TTL/expiry and DLQ monitoring**, letting stale and poison messages accumulate until the store fills.

## 📚 Further Reading

- *ActiveMQ in Action* — Bruce Snyder, Dejan Bosanac, Rob Davies (Manning) — the canonical Classic deep-dive.
- *Apache ActiveMQ Artemis User Manual* — https://activemq.apache.org/components/artemis/documentation/ (addresses, HA replication, journal, paging).
- *Apache ActiveMQ Classic Documentation* — https://activemq.apache.org/components/classic/ (KahaDB, networks of brokers, redelivery).
- *Jakarta Messaging (JMS) Specification* — https://jakarta.ee/specifications/messaging/ — the authoritative API/semantics reference.
- *Enterprise Integration Patterns* — Gregor Hohpe & Bobby Woolf — messaging design patterns (idempotent receiver, dead letter channel, competing consumers).
- *Spring Framework JMS / Spring Boot Messaging docs* — https://docs.spring.io/spring-boot/reference/messaging/ — `@JmsListener`, container factories, transaction integration.
