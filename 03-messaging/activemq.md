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

## 🧩 Extended Questions — Set 1: Deeper Theory & Practical Operations

### 🟢 Basic — extended

#### Q23. [Theory] What is the difference between PERSISTENT and NON_PERSISTENT delivery mode, and what does it cost?

JMS lets the producer set a per-message **delivery mode** that tells the broker whether to write the message to durable storage before acknowledging the send. With `PERSISTENT` (the JMS default), the broker writes the message to its journal/store and `fsync`s (depending on sync policy) **before** acking the producer's `send()`. If the broker crashes and restarts, the message is recovered. With `NON_PERSISTENT`, the broker keeps the message **in memory only** — `send()` returns faster (no disk round-trip) but a broker crash, or even a flush under memory pressure, loses the message.

The cost is throughput vs durability. Persistent sends are bounded by disk write/`fsync` latency; non-persistent sends are bounded only by memory and network. A common mistake is using `PERSISTENT` for high-volume, disposable data (heartbeats, cache-invalidation pings, live telemetry) where loss is harmless — you pay a large disk tax for no benefit. Conversely, marking financial events `NON_PERSISTENT` to "go faster" is how you silently lose money on a restart.

```java
ctx.createProducer()
   .setDeliveryMode(DeliveryMode.NON_PERSISTENT)   // memory-only, fast, lossy
   .send(queue, "heartbeat");                        // fine for disposable data

ctx.createProducer()
   .setDeliveryMode(DeliveryMode.PERSISTENT)        // durable, survives restart
   .send(queue, "payment-settled");                  // required for money
```

Note that **persistence requires more than the delivery mode**: the destination must not be a non-durable topic with no subscribers, and the broker's persistence adapter must be enabled (`persistent="true"` in `activemq.xml`, which is the default). If you set the broker to `persistent="false"`, even `PERSISTENT` messages are held in memory — a surprising gotcha when someone copies a "fast" dev config into production.

#### Q24. [Practical] How do you inspect, browse, and purge a queue without consuming its messages?

The everyday operational need is to *look at* a queue's contents or clear a stuck one without writing throwaway consumer code. Three tools cover almost everything: the **web console**, the **Artemis CLI / `activemq-admin`**, and **JMX**. The web console (port 8161) shows per-destination counters (`QueueSize`, `EnqueueCount`, `DequeueCount`, `InflightCount`, consumer count) and lets you browse the first N messages, move/copy/delete individual messages, and purge the whole queue with one button — invaluable during an incident.

A **QueueBrowser** lets application code peek non-destructively: it iterates a snapshot of the messages without acknowledging or removing them, which is perfect for diagnostics or admin dashboards.

```java
try (JMSContext ctx = factory.createContext()) {
    QueueBrowser browser = ctx.createBrowser(ctx.createQueue("orders.in"));
    Enumeration<?> e = browser.getEnumeration();
    int count = 0;
    while (e.hasMoreElements()) {                 // snapshot — does NOT consume
        Message m = (Message) e.nextElement();
        System.out.println(m.getJMSMessageID() + " ts=" + m.getJMSTimestamp());
        count++;
    }
    System.out.println("Approx depth: " + count);
}
```

For scripted operations, the Artemis CLI is the cleanest:

```bash
# Artemis: list queues with depth, then purge one
./artemis queue stat --url tcp://localhost:61616 --user admin --password secret
./artemis address show
./artemis queue purge --name orders.DLQ --url tcp://localhost:61616 \
   --user admin --password secret
```

Two cautions: `QueueBrowser` only sees messages that are *not* currently dispatched/in-flight to a consumer, so its count can differ from the broker's `QueueSize` under active load — never use a browser count as an exact gauge. And purging is irreversible; on a production DLQ I always **browse and export first**, then purge.

#### Q25. [Theory] What are JMS message headers, properties, and selectors, and how are selectors used?

A JMS message has three parts: the **body** (text, bytes, map, object, stream), the **headers** (standard `JMS*` fields the provider sets or you set — `JMSMessageID`, `JMSTimestamp`, `JMSCorrelationID`, `JMSReplyTo`, `JMSExpiration`, `JMSPriority`, `JMSRedelivered`), and **properties** (your own typed key/value metadata: `setStringProperty`, `setIntProperty`, etc.). The separation matters: the body is the payload your domain cares about; headers/properties are routing and filtering metadata the messaging layer reads **without deserializing the body**.

A **message selector** is a SQL92-like boolean expression evaluated by the broker against a message's headers and properties to decide whether a given consumer receives it. This gives you **content-based filtering at the broker** so a consumer only pulls the subset it cares about, rather than receiving everything and discarding most of it.

```java
// Producer tags messages with filterable properties
Message m = ctx.createTextMessage(payload);
m.setStringProperty("region", "EU");
m.setIntProperty("priority", 9);
ctx.createProducer().send(queue, m);

// Consumer subscribes only to high-priority EU messages
JMSConsumer c = ctx.createConsumer(
    queue, "region = 'EU' AND priority > 5");      // SQL92 selector
```

The trade-offs are real. Selectors are evaluated broker-side, so a queue with many consumers each using a narrow selector forces the broker to scan undelivered messages per consumer — this can become a CPU and scanning bottleneck, and messages that match *no* consumer's selector pile up indefinitely. For high-fan-out filtering, it's often better to route at publish time (separate destinations, or Artemis address routing / `JMSXGroupID`) than to lean on heavy selectors. Selectors shine for **occasional, low-cardinality filtering** (a request/reply consumer selecting on its own `JMSCorrelationID`), not as a general-purpose routing engine.

#### Q26. [Practical] How do you configure a Dead Letter Queue and a redelivery policy, and how do you replay messages from the DLQ?

A **Dead Letter Queue (DLQ)** is where the broker parks messages that exceeded their redelivery limit or expired, so a single poison message doesn't block the queue forever. In **Classic** the default DLQ is the shared `ActiveMQ.DLQ`; you usually want an **individual DLQ per destination** so you can tell *what* failed. The **redelivery policy** controls how many times and how fast a failed message is retried before being dead-lettered.

```xml
<!-- activemq.xml (Classic): per-destination DLQ + redelivery -->
<destinationPolicy>
  <policyMap><policyEntries>
    <policyEntry queue=">">
      <deadLetterStrategy>
        <!-- one DLQ per queue: ActiveMQ.DLQ.orders.in, etc. -->
        <individualDeadLetterStrategy queuePrefix="DLQ." useQueueForQueueMessages="true"/>
      </deadLetterStrategy>
    </policyEntry>
  </policyEntries></policyMap>
</destinationPolicy>
```

```java
// Client-side redelivery policy (Classic ActiveMQConnectionFactory)
RedeliveryPolicy policy = new RedeliveryPolicy();
policy.setMaximumRedeliveries(5);          // then → DLQ (default 6)
policy.setInitialRedeliveryDelay(1000);    // 1s
policy.setRedeliveryDelay(2000);
policy.setUseExponentialBackOff(true);
policy.setBackOffMultiplier(2.0);          // 1s, 2s, 4s, 8s...
factory.setRedeliveryPolicy(policy);
```

In **Artemis** the same concept lives in `address-settings`: `max-delivery-attempts`, `redelivery-delay`, `redelivery-delay-multiplier`, and `dead-letter-address`. The operationally important part is **what you do after** a message lands in the DLQ. DLQ messages are not garbage — they're a fix-and-replay backlog. The standard pattern is to **alert on DLQ depth**, inspect a sample to find the root cause (bad payload? downstream outage? schema change?), fix it, then **move messages back** to the original queue using the console's "move" action or the Artemis CLI:

```bash
# Replay: move everything from the DLQ back to the live queue after a fix
./artemis queue move --source DLQ.orders.in --target orders.in \
   --url tcp://localhost:61616 --user admin --password secret
```

The anti-pattern I flag in reviews is treating the DLQ as a black hole — no monitoring, no replay tooling — so genuine business events silently die there. A DLQ without an alert and a replay runbook is just a slower way to lose messages.

### 🟡 Intermediate — extended

#### Q27. [Theory] What is consumer prefetch, and how does it create the "slow consumer holds the backlog" problem?

ActiveMQ is a **push** broker: rather than make consumers poll, it proactively dispatches a batch of messages to each consumer and buffers them client-side. The size of that batch is the **prefetch limit** (default 1000 for queues, 32766 for topics, 1 for a few special cases). Prefetch is a throughput optimization — it amortizes network round-trips so a fast consumer isn't waiting for the next message after each ack. The messages sitting in a consumer's prefetch buffer are **dispatched but unacknowledged** ("in-flight"); the broker won't give them to anyone else.

The classic failure mode: imagine one queue, prefetch 1000, and two consumers — one fast, one slow. The broker may dispatch a big chunk to the slow consumer, which then sits on those 1000 messages working through them one at a time, while the fast consumer starves because its prefetch buffer drained and the remaining messages are "owned" (in-flight) by the slow one. Aggregate throughput collapses to the slow consumer's rate, even though you scaled out.

```
prefetch = 1000 (bad for uneven consumers)        prefetch = 1 (fair, slower)
broker ──1000──► [slow C1]  (hoards backlog)       broker ──1──► [slow C1]
       ──  0  ──► [fast C2]  (starving!)                  ──1──► [fast C2]  ◄ grabs next
```

The fix is to **lower prefetch when consumer speeds are uneven or processing is slow/long** — often to 1 — so the broker re-balances after each ack and a slow consumer can't monopolize work. Set it on the connection URI (`tcp://host:61616?jms.prefetchPolicy.queuePrefetch=1`) or per-destination. The trade-off: prefetch 1 maximizes fairness but adds a network round-trip per message, hurting throughput for fast, uniform consumers — so the right value is workload-dependent. Rule of thumb I use: **high prefetch for many uniform fast consumers; prefetch 1 for few slow/uneven consumers or strict load-balancing.**

#### Q28. [Practical] Your `JMSMessageID`-based dedup isn't catching duplicates after a broker restart. What's wrong and how do you fix it?

This is a real and common bug. `JMSMessageID` is **assigned by the broker/provider when the message is sent**, and it is **not stable across a resend**. If a producer's `send()` fails ambiguously (it timed out, but the broker may have actually persisted the message) and the producer retries, the retried message gets a *new* `JMSMessageID`. Similarly, when ActiveMQ **redelivers** a message after a consumer failure, it's the same `JMSMessageID` — but broker-generated IDs cannot be relied on for end-to-end idempotency across producer retries. So dedup keyed on `JMSMessageID` misses exactly the duplicates that matter: producer-side resends.

The fix is to dedup on a **producer-generated, business-stable idempotency key** carried as an application property, derived from the domain event (e.g. `orderId + eventType + version`) so the same logical event always maps to the same key regardless of how many times it's sent.

```java
// Producer: stable key derived from business identity, NOT JMSMessageID
String idemKey = order.getId() + ":" + "ORDER_PLACED";  // deterministic
Message m = ctx.createTextMessage(json);
m.setStringProperty("idempotencyKey", idemKey);
ctx.createProducer().send(queue, m);
```

```java
// Consumer: dedup table with a UNIQUE constraint, in ONE local DB transaction
@Transactional
public void handle(Order order, String idemKey) {
    try {
        dedupRepo.insert(idemKey);    // PK / UNIQUE on idempotencyKey
    } catch (DuplicateKeyException dup) {
        return;                        // already processed — skip side effect
    }
    orderRepo.save(order);             // side effect, atomic with the dedup insert
}
```

Two further refinements: ActiveMQ Classic and Artemis both offer a **broker-side duplicate detection** feature (Artemis honors the `_AMQ_DUPL_ID` property; Classic can enable `enableAudit`/`auditDepth` on the producer connection to suppress duplicates within a window) — but the audit window is bounded and resets on restart, so it's a defense-in-depth layer, not a substitute for the DB-backed inbox. And remember to set a **retention/expiry on the dedup table** (e.g. delete keys older than the maximum plausible redelivery window) so it doesn't grow unbounded. The durable, restart-proof guarantee always lives in **your** datastore keyed on **your** idempotency key.

#### Q29. [Theory] Compare synchronous request/reply over JMS with temporary queues vs `JMSReplyTo` + correlation ID.

Even though messaging is fundamentally asynchronous, **request/reply** is a common pattern: the requester sends a message and blocks (or awaits) a correlated response. There are two main implementations and they have very different operational characteristics. The first uses a **temporary queue**: the requester creates a `TemporaryQueue` (auto-deleted when its connection closes), sets it as `JMSReplyTo`, and the responder sends the reply there. The second uses a **single shared reply queue** plus `JMSCorrelationID`: every requester listens on the same reply destination with a selector on its own correlation ID.

```java
// Pattern A: temporary queue per request (simple, isolated)
TemporaryQueue replyTo = ctx.createTemporaryQueue();
Message req = ctx.createTextMessage(payload);
req.setJMSReplyTo(replyTo);
ctx.createProducer().send(requestQueue, req);
Message reply = ctx.createConsumer(replyTo).receive(5000);   // block for reply
```

```java
// Pattern B: shared reply queue + correlation selector (scales better)
String corrId = UUID.randomUUID().toString();
Message req = ctx.createTextMessage(payload);
req.setJMSReplyTo(sharedReplyQueue);
req.setJMSCorrelationID(corrId);
ctx.createProducer().send(requestQueue, req);
Message reply = ctx.createConsumer(
    sharedReplyQueue, "JMSCorrelationID = '" + corrId + "'").receive(5000);
```

| Aspect | Temp queue per request | Shared reply queue + correlation ID |
|--------|------------------------|--------------------------------------|
| Setup cost | Creates/destroys a destination per request | One long-lived destination |
| Broker overhead | High under load (destination churn, advisory traffic) | Low |
| Isolation | Strong — each reply has its own queue | Selector-based filtering on one queue |
| Scaling | Poor at high request rates | Good |
| Reconnect/failover | Temp queue dies with the connection → lost replies | Survives, replies still routable |

The deeper trade-off: temporary queues are dead simple and well-isolated but generate destination churn that hammers the broker at high request rates, and they evaporate on a connection drop (so an in-flight reply is lost on failover). The shared-queue/correlation approach scales far better and survives reconnects, but relies on selectors and needs care to avoid one slow consumer's selector scanning impacting others. For high-throughput RPC I prefer the shared-reply-queue pattern (or Spring's `JmsTemplate.sendAndReceive`, which manages correlation for you). And the honest senior take: **synchronous request/reply over a message broker is often an anti-pattern** — if you genuinely need low-latency synchronous semantics, an HTTP/gRPC call is usually the better tool; reach for JMS request/reply mainly when you need the broker's buffering, decoupling, or load-leveling alongside the reply.

#### Q30. [Practical] How do you configure connection pooling correctly, and what breaks if you pool the raw `ActiveMQConnectionFactory`?

A JMS `Connection` is expensive (TCP socket, authentication, broker-side bookkeeping); a `Session` is cheap-ish but single-threaded and not concurrency-safe. The naive Spring `JmsTemplate` (and any code that does `factory.createConnection()` per message) opens and tears down a full connection+session for every send, which destroys throughput and floods the broker with connection churn. The fix is a **pooling connection factory** — `JmsPoolConnectionFactory` from the `pooled-jms` library (the successor to the old `activemq-pool`/`PooledConnectionFactory`).

```java
@Bean
public ConnectionFactory pooledFactory() {
    var amq = new org.apache.activemq.ActiveMQConnectionFactory("tcp://broker:61616");
    var pool = new org.messaginghub.pooled.jms.JmsPoolConnectionFactory();
    pool.setConnectionFactory(amq);
    pool.setMaxConnections(8);            // physical connections shared in the pool
    pool.setMaxSessionsPerConnection(500);
    pool.setConnectionIdleTimeout(30_000);
    pool.setBlockIfSessionPoolIsFull(true);
    return pool;                          // hand THIS to JmsTemplate / listener factory
}
```

The subtle, often-missed rule: **pooling is for producers, not for message-driven consumers.** A `DefaultMessageListenerContainer` / `@JmsListener` manages its own long-lived connections and sessions sized by its `concurrency` setting; wrapping it in a session pool can cause sessions to be returned to the pool while the container still expects to own them, leading to weird redelivery and "session closed" errors. Use the pooled factory for `JmsTemplate`-style sends; let listener containers hold the raw (or a lightly-cached) factory.

Two more landmines. First, **pool sizing**: `maxConnections * maxSessionsPerConnection` is your concurrency ceiling; size it against the broker's connection limits and your thread count, and enable `blockIfSessionPoolIsFull` so you backpressure instead of throwing. Second, **don't double-pool**: if you use Spring's `CachingConnectionFactory` *and* `JmsPoolConnectionFactory`, you get confusing behavior — `CachingConnectionFactory` caches a single connection and is fine for simple producer use, while `pooled-jms` is the right choice when you need multiple physical connections or are running in a non-Spring/XA context. Pick one layer deliberately.

#### Q31. [Theory] What are Virtual Topics and Composite Destinations in ActiveMQ Classic, and what problem do they solve?

Plain JMS topics have a sharp limitation: to load-balance topic consumers you need durable subscriptions, but a single durable subscription is consumed by **one** connection at a time — you can't naturally fan out a topic to multiple *groups* of consumers where each group also scales horizontally. **Virtual Topics** solve this elegantly. A producer publishes to a topic named `VirtualTopic.Orders`; the broker automatically materializes a **physical queue per logical consumer group** named `Consumer.<group>.VirtualTopic.Orders`. Each group reads from its own queue (so groups get independent copies — pub/sub fan-out), and within a group you can attach many competing consumers (so each group also scales like a queue). You get fan-out *and* horizontal scaling without managing durable subscriptions.

```
Publisher ──► topic://VirtualTopic.Orders
                       │  (broker auto-fans-out to per-group queues)
        ┌──────────────┼───────────────────┐
        ▼                                   ▼
queue://Consumer.Billing.VirtualTopic.Orders   queue://Consumer.Shipping.VirtualTopic.Orders
   ├─► billing-consumer-1  (competing)            ├─► shipping-consumer-1
   └─► billing-consumer-2  (competing)            └─► shipping-consumer-2
```

```java
// Producer publishes to the virtual topic
ctx.createProducer().send(ctx.createTopic("VirtualTopic.Orders"), msg);

// Billing group consumes from its own auto-created queue (scales horizontally)
ctx.createConsumer(ctx.createQueue("Consumer.Billing.VirtualTopic.Orders"));
```

**Composite Destinations** are a related but distinct feature: a single send can be fanned out to *several* named destinations at once by listing them comma-separated (`orders.in,orders.audit`), or you configure a destination to forward copies to others. This is "one publish, many destinations" routing done at the broker. The key distinction: Virtual Topics are the idiomatic Classic answer to "durable, scalable pub/sub for multiple consumer groups," while composite destinations are simple static fan-out/mirroring. In **Artemis**, the address model with **MULTICAST** routing and multiple queues bound to one address provides the Virtual Topic capability natively — each bound queue is effectively a consumer group — which is why migrating Virtual Topic patterns to Artemis means rethinking them as addresses with multiple queues.

#### Q32. [Practical] Producers intermittently get "Channel was inactive for too long" or connection drops. How do you make clients resilient?

That error means the OpenWire transport's **inactivity monitor** detected no traffic (data or keep-alive) within the negotiated window and tore the connection down — usually caused by network blips, a paused broker (long GC, flow control), or an idle connection traversing a firewall/load balancer that silently drops idle TCP. Resilience comes from three layers: the **failover transport**, sensible **keep-alive/timeout tuning**, and **application-level retry/idempotency**.

The single most important piece is the **failover transport**, which transparently reconnects and (optionally) replays in-flight work to another or the same broker:

```
failover:(tcp://broker1:61616,tcp://broker2:61616)?initialReconnectDelay=100&maxReconnectAttempts=-1&randomize=true&timeout=5000
```

- `maxReconnectAttempts=-1` → retry forever; `timeout=5000` → fail a blocked `send()` after 5s instead of hanging indefinitely (critical so a broker outage doesn't freeze your request threads).
- `randomize=true` spreads clients across brokers; `initialReconnectDelay`/`maxReconnectDelay` add backoff.

Tune the inactivity/keep-alive so legitimate idle connections aren't killed but dead ones are detected quickly. The OpenWire `wireFormat.maxInactivityDuration` (default 30000ms) governs this; through a LB you often *lower* it so dead peers are spotted fast, or set TCP `keepAlive=true`. And critically, set **`timeout` on the failover URI and a send timeout** so a stalled broker surfaces as a fast exception your circuit breaker can react to, rather than a hung thread.

```java
// Application layer: treat reconnect as expected, not exceptional
factory.setUseAsyncSend(false);          // ensure send() blocks until broker ack (durability)
// Wrap sends in a retry-with-backoff + circuit breaker (Resilience4j),
// and make consumers idempotent because failover can cause redelivery.
```

The mindset I push: in a networked system **connection loss is normal, not exceptional**. Design for it — failover URI with a finite `send` timeout, idempotent consumers (because reconnection can replay unacked messages), and a circuit breaker so a broker outage degrades gracefully instead of blocking application threads. Clients that use a bare `tcp://` URI with no failover and no timeout are the ones that turn a 3-second broker hiccup into a cascading outage.

### 🟠 Advanced — extended

#### Q33. [Theory] Explain Producer Flow Control and the broker's `systemUsage` (memory/store/temp) limits. How do they interact?

ActiveMQ bounds its own resource consumption through **`systemUsage`** limits: `memoryUsage` (heap budget for messages held in RAM), `storeUsage` (disk budget for the persistent store/KahaDB/journal), and `tempUsage` (disk for messages spooled out of memory, e.g. non-persistent messages that overflow). Each destination also has its own `memoryLimit` cursor budget. When a destination or the broker approaches these limits, **Producer Flow Control (PFC)** engages: the broker stops acknowledging `send()` calls, which **blocks the producer** (for sync sends) until space frees up. This is intentional backpressure — it protects the broker from OOM/disk-full at the cost of slowing producers.

```xml
<systemUsage>
  <systemUsage>
    <memoryUsage><memoryUsage percentOfJvmHeap="70"/></memoryUsage>
    <storeUsage><storeUsage limit="50 gb"/></storeUsage>     <!-- KahaDB cap -->
    <tempUsage><tempUsage limit="20 gb"/></tempUsage>
  </systemUsage>
</systemUsage>

<policyEntry queue=">" producerFlowControl="true" memoryLimit="64mb">
  <!-- per-destination cursor budget; PFC blocks producers when exceeded -->
</policyEntry>
```

The interactions cause the subtle incidents. **Persistent messages** are governed by `storeUsage`: when the store fills, persistent producers block regardless of memory. **Non-persistent messages** are governed by `memoryUsage`, and when memory fills they can spool to `tempUsage` disk (if a non-persistent cursor is configured) rather than block — but if temp also fills, producers block. A frequent surprise: someone sees producers blocking and concludes "the queue is full," but the real cause is `storeUsage` at 100% because *another, unrelated* queue's backlog consumed the shared store. The limits are **broker-wide budgets shared across destinations**, so one runaway queue can throttle producers everywhere.

The senior decision is **block vs fast-fail vs spool**. PFC blocking is safe but turns a consumer outage into a producer outage (and into upstream latency). The alternatives: set `producerFlowControl="false"` with `sendFailIfNoSpace="true"` so over-limit sends throw immediately (let the application shed load), or rely on temp-spooling for non-persistent data, or — best — bound queues with **TTL/expiry** and **alert on usage percentage** so you never reach 100%. There is no universally correct policy; you choose per the destination's business meaning (drop telemetry, but never silently drop payments).

#### Q34. [Practical] Walk through diagnosing a broker with rising heap, long GC pauses, and periodic stalls. What's your methodology?

I work this as a layered investigation, because "broker is slow and pausing" has several distinct root causes that look identical from the outside. First, **confirm it's GC** with the JVM's own evidence: enable GC logging (`-Xlog:gc*` on Java 11+), check pause durations and frequency, and watch heap-after-GC trending upward (a leak) vs sawtooth-but-stable (just churn). Correlate the broker stalls with the GC pauses — if a 4-second stall matches a 4-second full GC, the broker isn't broken, the heap is mismanaged.

```bash
# JVM-level evidence first
jcmd <pid> GC.heap_info
jcmd <pid> GC.class_histogram | head -40      # what's consuming heap?
# Broker-level evidence via JMX
# org.apache.activemq:type=Broker,brokerName=*  → MemoryPercentUsage, StorePercentUsage
# per-destination QueueSize, EnqueueCount, ConsumerCount, InFlightCount
```

The most common ActiveMQ-specific cause is **deep queues held in memory**: a destination's message cursor pulls messages into heap, and a large backlog (slow/absent consumers) inflates heap until GC thrashes. The histogram will show `ActiveMQMessage`/`Message` subclasses dominating. The fix isn't "more heap" — it's draining the backlog (scale consumers, lower prefetch), bounding the queue (TTL/expiry), and ensuring cursors page to disk (`storeUsage`/cursor settings) rather than hoarding RAM. A second cause is **too many destinations or durable subscriptions** (e.g. temp-queue churn from a request/reply anti-pattern, or thousands of abandoned durable subs), each carrying per-destination overhead — visible as a high destination count in JMX.

Other layers to rule in/out: **`ObjectMessage` payloads** that are huge or graph-heavy bloat heap on deserialize; **disk** — if `storeUsage` is near 100% or the disk is slow, the broker stalls on `fsync` and *looks* like a GC pause but isn't (check `iostat`/journal sync latency); and **large messages** that should be chunked/streamed (Artemis large-message support) rather than held whole. My deliverable from an incident like this is always twofold: the immediate mitigation (drain/scale/restart with more headroom) **and** the structural fix (bound the queue, fix the consumer, right-size heap, add usage alerts) so it doesn't recur — a broker that needs a daily restart is a design defect, not an ops task.

#### Q35. [Theory] How does Artemis's address/queue/routing-type model unify queues and topics, and how does it map to JMS?

Artemis replaces Classic's two-kinds-of-destination world (Queue, Topic) with a single, more general abstraction: an **address** is a named routing endpoint, and bound to it are one or more **queues** (the actual message stores). The address's **routing type** decides how an incoming message is distributed to the bound queues:

- **ANYCAST** — messages on the address are load-balanced across the bound queues, and *within* a queue across its consumers → **point-to-point / JMS Queue semantics** (one consumer gets each message).
- **MULTICAST** — each bound queue receives a **copy** of every message → **publish/subscribe / JMS Topic semantics** (fan-out); each subscriber's queue is its own copy.

```
ANYCAST address "orders"                MULTICAST address "events"
   └─ queue "orders"                       ├─ queue "audit"     (gets a copy)
        ├─► consumer A  (competes)          ├─ queue "billing"   (gets a copy)
        └─► consumer B  (competes)          └─ queue "search"    (gets a copy)
   = JMS Queue                             = JMS Topic (each queue = a subscriber)
```

This unification is powerful because it makes previously-special features fall out naturally. A **durable topic subscription** is just a durable queue bound to a MULTICAST address. A **Virtual Topic consumer group** (Classic) becomes "multiple queues bound to one MULTICAST address," each queue scaling horizontally with competing consumers — fan-out *and* scale-out with no special construct. **Shared subscriptions** are several consumers on one MULTICAST-bound queue.

```xml
<!-- broker.xml: address with both routing types possible -->
<address name="events">
  <multicast>
    <queue name="billing"/>
    <queue name="audit"/>
  </multicast>
</address>
<address name="orders">
  <anycast><queue name="orders"/></anycast>
</address>
```

JMS clients still see `Queue` and `Topic` — Artemis maps them to addresses by convention (the `jakarta.jms.Queue` "foo" maps to an ANYCAST address/queue "foo"; a `Topic` "bar" maps to a MULTICAST address "bar"). The practical consequence for migrators: behavior that was implicit in Classic (a topic is fan-out; a queue is P2P) is now **explicit configuration**, and getting the routing type wrong is the number-one Artemis surprise — e.g. declaring an address ANYCAST when the app expects topic fan-out, so only one of your "subscribers" ever receives a message.

#### Q36. [Practical] Design the broker topology, persistence, and HA for a multi-datacenter, high-availability ActiveMQ deployment.

I separate two concerns that people conflate: **HA within a datacenter** (survive a broker node failure with no message loss) and **geographic distribution** (route/replicate messages across datacenters). They use different mechanisms and have different consistency stories.

For **intra-DC HA**, with Artemis I use a **live/backup pair with replication** plus a **quorum** (ZooKeeper or the pluggable lock manager) to prevent split-brain. The live broker streams its journal to the backup over the network (no shared disk, so it works in cloud); on live failure the backup activates, and clients on the `failover:` URI reconnect automatically. I avoid shared-NFS shared-store HA where possible because NFS lock semantics under partition have historically caused dual-master corruption — quorum-backed replication is the safer modern default.

```
Datacenter A (primary)                 Datacenter B (DR)
┌───────────────────────┐              ┌───────────────────────┐
│ Artemis LIVE  ◄─repl─► │              │ Artemis LIVE  ◄─repl─► │
│ Artemis BACKUP        │              │ Artemis BACKUP        │
│   + ZK quorum (3 nodes)│              │   + ZK quorum         │
└──────────┬────────────┘              └──────────┬────────────┘
           │      cluster connection / bridge      │
           └───────────── async over WAN ──────────┘
   clients: failover:(tcp://A-live,tcp://A-backup)   (+ DR URI for DR cutover)
```

For **cross-DC**, the honest answer is you almost never want **synchronous** replication over a WAN — the latency destroys throughput and a WAN partition stalls the primary. Instead I use **asynchronous bridging**: an Artemis **cluster connection** or a **broker bridge** that forwards messages from DC-A to DC-B's broker (or mirrors a DLQ/event stream) on a best-effort, store-and-forward basis. This accepts that DC-B may lag DC-A by some seconds and that a hard primary loss could lose the un-forwarded tail — a deliberate RPO trade-off, documented and signed off by the business. For DR, clients carry a secondary `failover:` URI (or DNS-based redirection) so they can be pointed at DC-B during a regional outage.

The cross-cutting decisions: **persistence** uses the Artemis journal on fast local SSD with `journal-type=ASYNCIO` (libaio) and a sync policy matched to the SLA (sync-per-write for money, group-commit/batched for telemetry); **capacity** is planned with bounded queues, TTL, and paging thresholds so a DR-link outage doesn't fill the store; and **everything is chaos-tested** — I kill the live node, sever the WAN link, and fill the disk in a staging rig and verify the documented RPO/RTO actually hold. The biggest architectural mistake here is assuming a network-of-brokers mesh gives you HA — it gives you *distribution*, not *redundancy*; HA is the local replicated pair, distribution is the bridge, and you need both.

#### Q37. [Theory] What is split-brain in a broker HA pair, how does it cause data loss/duplication, and how do quorum and fencing prevent it?

**Split-brain** is when a network partition (or a slow/paused live broker that *appears* dead) causes a backup broker to promote itself to live while the original live is still running — now **two brokers both believe they are the master** for the same data. Clients on the `failover:` URI may connect to either; messages get produced to and consumed from both independently. When the partition heals, you have two divergent journals: messages that exist on one and not the other (loss when you pick a "winner") and messages processed twice (duplication). It is the most dangerous failure mode in messaging HA because it silently corrupts the very durability guarantee the HA was meant to provide.

```
Normal:   LIVE ──repl──► BACKUP            Split-brain (partition):
                                            LIVE ✗──X──✗ BACKUP-now-also-LIVE
client ──► LIVE                             clientset1 ──► LIVE
                                            clientset2 ──► BACKUP(thinks it's live)
                                            → two divergent journals
```

The classic trigger is a shared-store/NFS setup where the lock appears released (NFS lock recovery after a blip) or a replication pair with **no tiebreaker**: if the backup loses contact with the live, it cannot distinguish "live died" from "I am the one who got partitioned," and a naive backup assumes the former and activates. The defense is a **quorum** — an odd-numbered third party (ZooKeeper ensemble, or Artemis's pluggable quorum/lock manager) that arbitrates. A broker may only become or remain live if it can reach a **majority** of the quorum. During a partition, only the side that retains majority stays/becomes live; the minority side **steps down** (it cannot win the vote), so there is never more than one live. This is why Artemis replication strongly recommends a quorum and why an even number of voting members is a misconfiguration (no majority is possible in a 50/50 split).

**Fencing** is the complementary guarantee: when a broker loses quorum it must actively **stop serving** (shut down its acceptors / refuse clients) rather than passively assume someone else took over — otherwise a delayed live could keep accepting writes after a new live activated. Artemis's vote-based activation plus the quorum's majority requirement provides this. The practical guidance I give: run a **3-node (or 5-node) quorum** in a *separate failure domain* from the brokers, never co-locate all quorum members with one broker, prefer **replication + quorum over shared-NFS**, and **chaos-test the partition** explicitly — induce a network split and confirm exactly one broker stays live and the other fences itself off. HA you haven't partition-tested is HA you don't actually have.

#### Q38. [Practical] How do you load-test an ActiveMQ broker and establish a capacity baseline before going to production?

Capacity testing a broker is different from testing a stateless service because the broker's behavior is **stateful and non-linear** — it performs fine until a usage limit, the disk, or GC tips it into a cliff. My goal is to find those cliffs in staging, not in production. I start by defining the **workload shape that matters**: message size distribution, persistent vs non-persistent ratio, queue vs topic, producer/consumer counts, ack mode, and — crucially — the **steady-state vs burst** profile and the **consumer:producer rate ratio** (because a broker only stays healthy if consumers keep up).

```bash
# Artemis ships a built-in benchmarking/perf tool
./artemis perf producer --destination queue://load.test \
   --message-size 1024 --rate 20000 --duration 600 --persistent
./artemis perf consumer --destination queue://load.test --threads 8
# Classic: use the JMeter JMS sampler or activemq-perftest / Maven perf plugin
```

I run a sequence of tests rather than one: (1) **throughput ceiling** — ramp producer rate with consumers keeping pace until latency degrades, recording the sustainable msgs/sec and MB/sec for *your* message size and sync policy; (2) **backlog/soak** — deliberately slow consumers so the queue grows, and observe how the broker behaves as it approaches `memoryUsage`/`storeUsage` (does PFC engage gracefully? does GC thrash? where's the cliff?); (3) **failover under load** — kill the live broker mid-test and measure reconnect time, redelivery volume, and any loss; (4) **endurance** — run hours/days to catch slow leaks (durable-sub growth, file-descriptor leaks, store fragmentation). Throughout, I capture broker JMX metrics (queue depth, usage %, dispatch/ack rates), JVM GC logs, and OS disk latency (`iostat`) — the broker, the JVM, and the disk are three separate potential bottlenecks.

The output is a **documented baseline with headroom rules**, not a single number: "this broker sustains ~X msgs/s of 1KB persistent messages with consumers keeping pace; queue depth alert at Y; store-usage alert at 70%; failover completes in ~Z seconds with ~N redeliveries." I then set production limits and alerts *below* the observed cliffs so PFC/GC pressure never actually triggers in prod. The mistake I most often correct: teams test peak throughput on an empty broker and declare victory, never testing the **degraded** regimes (deep backlog, slow disk, failover) where real incidents happen — the cliff, not the ceiling, is what takes you down at 2am.

### 🔴 Expert — extended

#### Q39. [Theory] Explain how the KahaDB journal, index, and log-file cleanup work, and what causes a KahaDB store to grow without bound.

KahaDB persists messages as an **append-only journal**: incoming persistent messages, acks, and transaction commands are written sequentially to rolling log files (`db-<n>.log`, default 32MB each). A separate **B-tree index** (`db.data`, with `db.redo` for crash recovery of the index) maps message IDs and destinations to their location in the journal, so reads don't scan the log. Append-only sequential writes are why KahaDB is fast — but they mean the journal only ever *grows* during normal operation; space is reclaimed not by deleting individual messages in place, but by **discarding whole log files** once every message they contain has been consumed and acknowledged.

```
db-1.log  [m1(acked) m2(acked) m3(acked)]   ← fully acked → eligible for GC/removal
db-2.log  [m4(acked) m5(UNACKED) m6(acked)] ← ONE unacked msg pins the WHOLE file
db-3.log  [m7 m8 ... ]  (current write file)
db.data   B-tree index → (destination, msgId) ⇒ (logFile, offset)
```

The critical, counterintuitive rule: **a log file can only be removed when *all* references in it are gone.** A single un-acknowledged or undelivered message (or a still-open transaction, or an un-consumed durable subscription's message) **pins the entire 32MB file** — and transitively, files cannot be reclaimed out of order in a way that strands the index. So the canonical "KahaDB grows forever" incident is almost never a true leak; it's **one stuck reference holding old files hostage** while new writes keep allocating new files. The usual culprits: (1) an **abandoned durable subscription** that still "owns" old messages, so their log files can never be freed; (2) a **slow or dead consumer** leaving messages unacked; (3) a **lingering open transaction / prepared XA transaction** that was never committed or rolled back; (4) a destination with a huge backlog. The store balloons even though "the queues look mostly empty," because the *oldest unacked reference*, not the *count of messages*, governs reclamation.

Diagnosis and fixes follow directly: find the **oldest unacknowledged message / oldest pending transaction** (JMX `org.apache.activemq:type=Broker` exposes store usage; inspect per-destination depth and durable subscriptions in the console), then **unsubscribe dead durable subs**, drain or DLQ the stuck consumer's backlog, and resolve dangling prepared transactions (`recover()` and commit/rollback). Preventatively: set **message TTL/expiry** so nothing lives forever, **monitor `StorePercentUsage`**, periodically audit durable subscriptions for abandonment, and size `journalMaxFileLength`/`cleanupInterval` deliberately. The expert framing: KahaDB growth is a *reference-retention* problem, not a *volume* problem — chase the oldest pinning reference, not the total message count.

#### Q40. [Theory] Compare achieving transactional integrity via XA/2PC vs the transactional-outbox pattern, including the failure modes of each.

When a unit of work must atomically (a) consume a JMS message and (b) commit a database change, there are two serious approaches, and choosing between them is one of the more consequential architecture decisions in a messaging system. **XA / two-phase commit (2PC)** enlists both the JMS broker and the database as resources in a single **global transaction** coordinated by a transaction manager. In phase one the coordinator asks both resources to *prepare* (durably promise they can commit); if both vote yes, phase two tells both to *commit*. This gives genuine atomicity across the two resources — the message ack and the DB write either both happen or neither does.

```
XA / 2PC                                  Transactional Outbox/Inbox
TM ──prepare──► JMS  ─yes─┐               consume (auto-ack) ──┐
TM ──prepare──► DB   ─yes─┤               BEGIN db tx          │ single LOCAL
   ──commit───► JMS        │ atomic        INSERT inbox(idemKey)│ db transaction
   ──commit───► DB         ┘  (slow)       apply side effect    │
                                           COMMIT ──────────────┘
in-doubt window if TM crashes              dedup makes redelivery safe
between prepare and commit                 (at-least-once + idempotent = once-effect)
```

XA's failure mode is the **in-doubt / heuristic transaction**. If the coordinator crashes *after* resources have prepared but *before* commit, those resources hold locks and stay "prepared," waiting for a recovery decision; if a resource unilaterally resolves (a *heuristic* outcome) the two can diverge — exactly the data corruption 2PC was meant to prevent. Recovery requires the TM's transaction log to survive and replay, which means the TM itself becomes a stateful, must-not-lose-its-log component. Add that 2PC is **slow** (extra network round-trips, locks held across the prepare window), many cloud-managed databases and brokers support XA poorly or not at all, and operating a reliable distributed TM is genuinely hard — and you see why XA, while *correct*, is increasingly avoided.

The **transactional outbox/inbox** pattern sidesteps 2PC entirely by reducing the problem to a **single local database transaction**. On the consume side (inbox): process with simple auto-ack, but inside one local DB transaction insert the message's **business idempotency key** (unique constraint) and apply the side effect; if the message redelivers (because the ack was lost), the duplicate-key insert fails and you skip the side effect — at-least-once delivery + idempotent application = exactly-once *effect*. On the produce side (outbox): write the domain change and an "outbox" row in one local transaction, then a separate relay publishes outbox rows to the broker and marks them sent (re-publishing on crash, since the consumer is idempotent). Its failure mode is **benign duplicates** (a relay crash may republish) — handled by the same idempotency, never by losing or double-applying business effects. The trade-off is honest: outbox accepts duplicate *delivery* and a small publish *latency* in exchange for never needing 2PC, no in-doubt corruption, and working on any plain database. My default at scale is the outbox/inbox; I reserve XA for the rare case of a true multi-resource atomic requirement on infrastructure that genuinely supports it and where duplicate effects are unacceptable even momentarily.

#### Q41. [Practical] A consumer is "losing" messages intermittently in production. Walk through how you prove where they go and rule out actual loss.

"We're losing messages" is one of the highest-stakes and most over-diagnosed reports, so my first move is to **refuse to assume loss** and instead instrument the full path to *prove* where each message ends up. Real broker-level loss of `PERSISTENT` messages is rare; the usual explanations are (in rough order): messages **expired** (TTL) and were silently discarded, went to the **DLQ** after redelivery exhaustion, were sent **NON_PERSISTENT** and dropped on a restart/flow-control, were filtered out by an unintended **selector**, were consumed by an **unexpected competing consumer** (a stray instance, or a network-of-brokers forward), or were never actually **committed** by the producer (transacted session never committed, or async send failed silently).

```
Trace the path with the broker's own evidence:
EnqueueCount  ── did the broker ever receive it?      (no → producer-side problem)
DequeueCount  ── was it delivered & acked?
ExpiredCount  ── did TTL discard it?                  (often the silent culprit)
DLQ depth     ── did it dead-letter after retries?
InflightCount ── is it stuck unacked on a slow consumer?
Forwarded     ── did a network connector ship it elsewhere?
```

Concretely I (1) enable/confirm **per-message tracing**: stamp a correlation/idempotency key at produce time and log it at every hop (produce, broker enqueue via advisory topics, consume, ack, DLQ); ActiveMQ's **advisory topics** (`ActiveMQ.Advisory.*`) emit events for sends, deliveries, expirations, discards, and DLQ-ing — subscribe to them in a diagnostic consumer to watch the message's lifecycle live. (2) Check the JMX counters above per destination — `ExpiredCount > 0` immediately points at TTL; a non-zero DLQ points at poison/redelivery; `EnqueueCount` not incrementing means the producer never succeeded (so it's not the broker at all). (3) Verify the **producer actually committed** — a transacted session whose `commit()` is skipped on an exception, or `useAsyncSend=true` swallowing a failure, looks exactly like "the broker lost it" but the message never arrived.

The resolution then matches the proven cause: lengthen or remove an over-aggressive TTL; fix the poison-message handler and replay the DLQ; switch disposable-but-important data from NON_PERSISTENT to PERSISTENT; remove the stray consumer or bound `networkTTL`; make producer commits robust (explicit commit in a finally/transaction, synchronous sends for critical paths). The principle I insist on: **never "fix" message loss by guessing** — instrument the path, read the broker's own counters and advisories, and prove the disposition of the missing messages. Nine times out of ten the messages weren't lost; they went somewhere the team didn't think to look, and the real bug is the missing observability.

#### Q42. [Theory] How do advisory topics work, and how can they be used (and misused) for monitoring and control?

**Advisory topics** are special system topics under the `ActiveMQ.Advisory.>` namespace that the broker publishes to automatically as internal events occur — without any application opting in. They turn the broker's internal lifecycle into a stream you can subscribe to like any other topic. Events include consumer/producer start and stop (`ActiveMQ.Advisory.Consumer.Queue.<name>`), destination creation/deletion, connection start/stop, message delivery and acknowledgement, **message expiration and discarding**, messages sent to the **DLQ**, slow-consumer detection, full-destination/usage events, and master/slave broker advisories. This is the mechanism that powers the demand-forwarding logic in a network of brokers (a remote broker learns "a consumer now exists for X" via the consumer advisory).

```java
// Watch for messages being dead-lettered, live, with no producer cooperation
Topic dlqAdvisory = ctx.createTopic("ActiveMQ.Advisory.MessageDLQd.Queue.orders.in");
ctx.createConsumer(dlqAdvisory).setMessageListener(msg -> {
    // ActiveMQMessage carries the original message + a reason in properties
    alert("Message dead-lettered on orders.in: " + msg.getJMSMessageID());
});
// Other useful ones:
//   ActiveMQ.Advisory.Consumer.Queue.>      → consumer attach/detach
//   ActiveMQ.Advisory.Expired.Queue.>       → TTL discards (the silent-loss culprit)
//   ActiveMQ.Advisory.FULL.>                → destination hit its memory limit
//   ActiveMQ.Advisory.SlowConsumer.Topic.>  → slow consumer detection
```

Used well, advisories are a powerful **passive observability and control plane**: you can build real-time dashboards of consumer presence, get pushed notifications when messages expire or dead-letter (closing the "silent loss" gap from the previous question), detect slow consumers, and react to destinations going full — all without instrumenting producers/consumers. Some control patterns (e.g. waiting for a consumer to be present before sending, or temporary-queue cleanup) are built on advisories under the hood.

The misuse and costs are equally important to know. Advisories are **on by default in Classic** and generate real traffic and broker work — on a very high-throughput broker, per-message advisories (delivery/ack advisories) can add meaningful overhead, so you **disable the chatty ones** (`advisorySupport="false"` globally, or selectively) when you don't need them. They also create destinations: subscribing broadly to `ActiveMQ.Advisory.>` and never cleaning up, or a network of brokers exchanging advisories, can produce surprising destination counts and traffic. And because advisories are themselves topic messages, a **non-durable** advisory subscriber misses events while disconnected — so an advisory-based "audit" is best-effort unless made durable. Artemis takes a different approach (management notifications via the management address and a notification queue) rather than the Classic `ActiveMQ.Advisory.>` convention, so advisory-based tooling is a Classic-specific pattern to redesign on migration. The senior judgement: advisories are excellent for **lifecycle/exception events** (DLQ, expiry, full, consumer presence) and a poor idea to leave fully enabled for **per-message** events on a hot broker.

#### Q43. [Practical] You need zero-downtime broker maintenance (patching, config changes, scaling). How do you achieve it?

True zero-downtime broker maintenance is achievable but only if the system was *designed* for it; you cannot bolt it on during the change window. The foundation is that **every client connects via the `failover:` transport** to more than one endpoint, and that **consumers are idempotent** (because any reconnection or failover can replay unacknowledged in-flight messages). Given that, the workhorse for patching an HA pair is a **rolling restart with controlled failover**: with an Artemis live/backup replicated pair, you take down the **backup** first, patch and restart it, let it re-sync the journal from the live, then **gracefully fail over** the live to the now-patched backup (so the backup becomes live), patch the old live, and let it rejoin as backup. Clients ride the failover URI across the brief switchover; no message is lost because the journal was replicated and unacked messages redeliver.

```
Rolling patch of an Artemis HA pair (clients on failover:(live,backup)):
1. stop BACKUP ─► patch ─► start ─► wait for journal re-sync (in-sync!)
2. graceful failover LIVE → BACKUP   (clients reconnect to new live)
3. patch old LIVE ─► start as BACKUP ─► re-sync
   ── invariant: at every step exactly one in-sync live is serving ──
```

The non-obvious requirement is **draining before stopping**, so you don't strand in-flight work. Artemis supports **graceful shutdown / scaledown** that lets a broker finish delivering and, in a cluster, redistribute its remaining messages to other live brokers before it exits; you also stop *accepting new* connections/producers on the node first (or shift them via the load balancer / failover randomization) and let consumers drain the local queues. For a single (non-HA) broker there is no truly zero-downtime patch — which is itself the argument for running HA pairs in production. For **scaling** consumers (not the broker), you simply add/remove consumer instances; competing consumers on a queue absorb the change with no broker action, and Artemis cluster connections **redistribute** messages to where consumers exist.

The decisions and caveats I emphasize: **config changes are not all hot-reloadable** — Artemis reloads much of `broker.xml` (address-settings, security-settings) live on a configurable interval, but acceptor/journal/HA changes need a restart, so categorize each change as hot vs restart-requiring before the window. **Schema/protocol-incompatible changes** (e.g. a wire-format or persistence-format bump across a major version) may break the rolling assumption that live and backup can interoperate mid-upgrade — read the upgrade notes and, if so, plan a maintenance window rather than a rolling restart. And I always **rehearse the rolling restart in staging under load**, including a forced mid-restart failover, because the failure you discover during the dry run is the one you'd otherwise discover during the production change. Zero-downtime is a property of HA + idempotency + drain-before-stop + hot-vs-cold change classification — not a button.

#### Q44. [Theory] How does flow control and credit-based backpressure differ across OpenWire, AMQP 1.0, and STOMP, and why does it matter in a multi-protocol broker?

ActiveMQ (especially Artemis) is **multi-protocol** — the same broker can accept OpenWire, AMQP 1.0, STOMP, and MQTT on different acceptors — and each protocol has its **own native flow-control model**, which the broker must reconcile against its single internal resource budget. Understanding this matters because a backpressure problem can manifest completely differently depending on which protocol the misbehaving client speaks, and a fix tuned for one protocol may not apply to another.

**OpenWire** (ActiveMQ's native wire format) uses **prefetch** (push N messages to the consumer, covered earlier) plus broker-side **Producer Flow Control** that blocks `send()` when usage limits are hit. Backpressure is largely **broker-driven and consumer-buffer-oriented**. **AMQP 1.0**, by contrast, has **credit-based flow control built into the protocol itself**: the receiver explicitly grants the sender a number of *link credits*, and the sender may only transfer that many messages before it must wait for more credit. This is a true end-to-end, per-link windowing mechanism — far more granular and standardized than OpenWire prefetch — and it means an AMQP consumer that grants little credit naturally throttles its sender without relying on broker PFC. **STOMP** is the simplest protocol and historically had **no built-in flow control**; backpressure relies on TCP itself (the broker stops reading the socket) plus STOMP's optional `ack`/`receipt` frames, so a naive STOMP client can more easily overwhelm itself or the broker.

```
Backpressure mechanism by protocol
OpenWire : prefetch window (push) + broker Producer Flow Control (block send)
AMQP 1.0 : link credit (receiver grants N; sender bounded by credit)  ← protocol-native
STOMP    : TCP backpressure + optional ack/receipt; no credit windowing
MQTT     : QoS levels + inflight-window for QoS1/2; receive-maximum (MQTT5)
```

Why it matters operationally: in a multi-protocol broker the **internal `systemUsage`/address limits are the shared, authoritative budget**, and each protocol's native flow control is a *front-end* the broker maps onto that budget. So an AMQP client that aggressively grants credit, an OpenWire client with prefetch 1000, and a STOMP client with no flow control are all competing for the same memory/store, but you tune them with different knobs (link credit / `amqpCredits` and `amqpLowCredits` for AMQP, `prefetch` for OpenWire, socket/ack behavior for STOMP). When diagnosing "the broker is throttling," the *first* question is **which protocol** the affected client uses, because the lever differs entirely. The deeper point is that ActiveMQ's value as a multi-protocol broker comes with the responsibility of understanding that "backpressure" is not one mechanism — it's a protocol-specific front-end (credit vs prefetch vs raw TCP) reconciled against one shared internal resource model, and mismatches there are a subtle but real source of production surprises.

#### Q45. [Practical] How do you handle large messages (multi-MB payloads) without blowing up broker heap?

The default assumption in a broker is that messages are small (kilobytes), held in memory while in flight, and counted against the `memoryUsage` budget. Push multi-MB payloads through naively and you blow heap: a few hundred concurrent 10MB messages is gigabytes of RAM, GC thrashes, and the broker stalls. There are three legitimate strategies, in increasing order of how I like them.

First, **don't put large blobs in the broker at all** — use the **claim-check pattern**: store the payload in object storage (S3/blob/DB), and send only a small reference message (URL + checksum) through ActiveMQ. The broker stays fast and small; the consumer fetches the blob directly. This is my default for anything over a few hundred KB, because it keeps the messaging layer doing what it's good at (coordination) and the storage layer doing what it's good at (bulk bytes).

```java
// Claim-check: broker carries a pointer, not the payload
String key = blobStore.put(bigPayloadBytes);          // → S3/DB
Message m = ctx.createTextMessage("{\"blobKey\":\"" + key + "\",\"sha256\":\"...\"}");
ctx.createProducer().send(queue, m);                  // tiny message on the broker
```

Second, if the bytes *must* flow through the broker, **Artemis has native large-message support**: messages above `min-large-message-size` are streamed to disk on the broker and delivered as a stream rather than being held whole in heap, so memory stays bounded regardless of payload size. You consume them via the streaming API (`getBodyLength`/`saveToOutputStream`) instead of materializing the whole body. Classic's equivalent is **blob messages** (`BlobMessage`) which offload the payload to an out-of-band store/FTP/HTTP location referenced by the message. Third — and this is the anti-pattern — sending huge `BytesMessage`/`ObjectMessage` payloads on a Classic broker with default settings, which holds them in memory and is exactly how the heap-exhaustion incidents happen. The senior rule: **the broker is a coordination layer, not a file server** — prefer claim-check, use Artemis large-message streaming when bytes must transit, and never route multi-MB payloads through default in-memory message handling.

#### Q46. [Theory] What is the difference between exclusive consumers, message groups, and shared subscriptions for controlling delivery?

These three features all shape *which consumer gets which messages*, but they solve different problems and are easy to confuse. An **exclusive consumer** (`consumer.exclusive=true` on a queue) designates a **single** consumer as the sole recipient for the *entire* destination, with automatic failover to another waiting consumer if it dies. It gives you **strict total ordering** across the whole queue (one consumer = no concurrency = FIFO) at the cost of zero horizontal scaling — it's the "I need every message on this queue processed in order by one worker" tool.

**Message groups** (`JMSXGroupID` header) are finer-grained: the broker pins all messages sharing a group ID to the **same** consumer (consistent hashing), so order is preserved *per group* while *different groups* load-balance across consumers. This is the scalable middle ground — order where the business needs it (per account, per aggregate) and parallelism everywhere else. You close a group by sending `JMSXGroupSeq = -1` to release the pin.

```
Exclusive consumer        Message groups                Shared subscription
1 queue → 1 consumer      groupA→C1, groupB→C2          1 durable topic sub
(total order, no scale)   (per-group order + scale)     → many consumers (fan-out + scale)
```

**Shared subscriptions** (JMS 2.0 `createSharedConsumer` / `createSharedDurableConsumer`) are a *topic* feature, not a queue one: they let **multiple consumers load-balance a single (optionally durable) topic subscription**, combining pub/sub fan-out with consumer scaling — previously impossible because a classic durable sub allowed only one active consumer. The distinction that matters in an interview: exclusive consumers and message groups are about **ordering and affinity on queues**; shared subscriptions are about **scaling consumers on topics**. Reaching for an exclusive consumer when you only needed per-key ordering needlessly throttles you to one worker; reaching for message groups when you needed topic fan-out misses the model entirely.

#### Q47. [Practical] Design a monitoring and alerting strategy for ActiveMQ in production. Which metrics matter and what are the thresholds?

Effective broker monitoring is built around the insight that **queue depth and its rate of change are the leading indicators** of nearly every incident — a consumer outage, a slow downstream, a poison-message loop, and a capacity problem all surface first as a growing backlog. So the metric I alert on most aggressively is per-destination **`QueueSize`** plus the derivative `EnqueueCount - DequeueCount` rate (is the backlog growing, and how fast?). Everything else is either a resource ceiling or a quality signal.

```
Metric (JMX → Prometheus)            What it tells you          Alert threshold (example)
QueueSize / depth                    backlog building           warn at sustained growth; page on > SLA
Enqueue vs Dequeue rate              producers outpacing consumers  page if dequeue ≈ 0 with enqueue > 0
StorePercentUsage                    disk store nearing limit   warn 70%, page 85%  ← before PFC engages
MemoryPercentUsage / TempPercentUsage heap/temp pressure         warn 70%, page 90%
DLQ depth                            poison/failed messages     page on > 0 (or > small N) — never ignore
ExpiredCount                         silent TTL discards        alert on unexpected increase
ConsumerCount per destination        consumers present?         page if drops to 0 on a live queue
InFlightCount stuck                  unacked, slow consumer     warn if high and not draining
Connection count / churn             leak or reconnect storm    warn on abnormal churn
JVM GC pause / heap-after-GC         broker stall risk          page on long pauses / rising baseline
Failover/HA: is-backup-in-sync       HA actually protecting you page if replication out of sync
```

The collection path is **JMX → an exporter (jmx_exporter / the ActiveMQ Prometheus plugin) → Prometheus → Grafana + Alertmanager**. The thresholds I set deliberately **below the cliffs found in load testing** (Q38): alert on `StorePercentUsage` at 70%, well before 100% triggers Producer Flow Control, because by the time PFC engages, producers are already blocking and the incident is live. Two alerts people forget but I always add: **DLQ depth > 0** (a non-empty DLQ is a real business event silently failing — it should page, paired with the replay runbook from Q26) and **`ConsumerCount` dropping to zero on a queue that should always have consumers** (catches a deployment that killed all workers before the backlog becomes visible). And for HA deployments, **monitor that the backup is in-sync** — an HA pair whose replication silently fell out of sync is HA you only *think* you have. The philosophy: alert on the **leading** indicator (growing depth, falling consumer count) so you act before the **lagging** indicator (store full, PFC, outage) ever fires.

#### Q48. [Theory] Explain XA transactions and the role of the transaction recovery log when integrating ActiveMQ with a database via a JTA transaction manager.

When ActiveMQ participates in a **JTA/XA global transaction** alongside a database, both resources are coordinated by an external **transaction manager** (Narayana/JBoss TM, Atomikos, Bitronix, or an app-server TM) implementing the **X/Open XA** protocol. The broker exposes an `XAConnectionFactory`/`XASession` whose work enlists in the global transaction via an `XAResource`; the TM drives the two-phase commit — `prepare` both resources, and if both vote commit, `commit` both. The application code looks deceptively simple because the container/TM hides the choreography:

```java
@Transactional   // JtaTransactionManager spanning JMS XAResource + JDBC XAResource
public void process(Order o) {
    Message m = jmsTemplate.receive("orders.in");   // JMS work enlisted in global tx
    orderRepo.save(toOrder(m));                      // JDBC work enlisted in SAME global tx
    // commit → TM runs 2PC: prepare(JMS), prepare(DB); commit(JMS), commit(DB)
}                                                     // both commit atomically, or neither
```

The component most people overlook is the **transaction recovery log** owned by the transaction manager. During 2PC there is a vulnerable window: after both resources have *prepared* (durably promised to commit and are holding locks), the TM records the decision in its **recovery log** and then issues commits. If the TM process crashes between prepare and the commit broadcast, those resources are left **in-doubt** — locked, waiting. On restart the TM reads its recovery log, finds the in-doubt transactions, queries each resource's prepared-transaction list (`XAResource.recover()`), and replays the recorded decision to drive each to a consistent outcome. This is why the **TM's recovery log must be on durable, surviving storage** and why the TM is a genuinely stateful component: lose that log and you cannot deterministically resolve in-doubt transactions, risking **heuristic** outcomes where ActiveMQ and the DB diverge (a message committed but the DB rolled back, or vice versa).

The practical implications: XA gives true cross-resource atomicity but demands an XA-capable broker (Artemis and Classic both provide `XAConnectionFactory`), an XA-capable datasource, a TM with a reliable recovery log, and acceptance of 2PC's latency and lock-holding cost. Failures to plan for include **dangling prepared transactions** pinning KahaDB log files (Q39) when a TM never completes recovery, and managed-cloud datasources/brokers with weak XA support. This is precisely why many teams (Q40) prefer the transactional-outbox pattern — it trades XA's hard atomicity for at-least-once + idempotency and eliminates the recovery-log and in-doubt machinery entirely. Reserve XA for genuine multi-resource atomic requirements on infrastructure that fully supports it.

#### Q49. [Practical] How do you secure the JMX/Jolokia management interface and prevent the known ActiveMQ RCE attack classes?

ActiveMQ has produced several serious, widely-exploited remote-code-execution CVE classes, and two recurring vectors are the **management interface** (JMX/Jolokia) and **OpenWire/`ObjectMessage` deserialization** — so hardening these is the first thing I check in any security review. The management interface is dangerous because Jolokia exposes JMX over HTTP, and JMX operations can instantiate and invoke beans; an exposed, unauthenticated, or weakly-authenticated Jolokia endpoint has been leveraged to load arbitrary code (the broker can be coerced into creating MBeans that execute attacker-controlled classes). The mitigations are concrete:

```
JMX/Jolokia hardening checklist
- Never expose 8161 (web console + Jolokia) to untrusted networks; bind to localhost
  or a management VLAN, front with a reverse proxy + auth, firewall it off.
- Require authentication on Jolokia and the console; remove default admin/admin.
- Apply Jolokia access policy (jolokia-access.xml): allowlist only the MBeans/operations
  you actually need; deny everything else (especially MBean creation/class loading).
- Run a patched broker version — the management RCEs were fixed in specific releases.
- Disable the web console entirely on brokers that don't need it.
```

The second class is **deserialization**. ActiveMQ's OpenWire protocol historically deserialized Java objects from the wire, and `ObjectMessage` deserializes attacker-influenced bytes; with vulnerable gadget classes on the classpath this becomes RCE (this is the lineage of the notorious OpenWire deserialization CVE). The defenses: set **`setTrustedPackages(...)`** (or `trustAllPackages=false`, which is the safe default in current versions) on the connection factory so only allowlisted packages can be deserialized, **avoid `ObjectMessage` entirely** in favor of a safe serialization format (JSON, Protobuf, Avro) where the broker only ever moves bytes, and again **patch to a fixed version** — the worst RCEs were addressed by tightening OpenWire deserialization and the marshallers.

```java
// Restrict deserialization to known-safe packages (defense in depth)
factory.setTrustedPackages(java.util.Arrays.asList("com.acme.domain", "java.lang"));
// Better: don't use ObjectMessage at all — send JSON/Protobuf bytes you deserialize yourself.
```

Rounding out the posture: enable **TLS** on connectors so credentials and payloads aren't in cleartext, lock down **per-destination authorization** (least privilege — a producer can't consume or create arbitrary destinations), disable **unused transport connectors** (don't run STOMP/MQTT acceptors you don't use — every open protocol is attack surface), and keep the broker off the public internet. The meta-lesson I stress: ActiveMQ's most damaging CVEs were not exotic — they were **default-on management endpoints** and **unrestricted deserialization** reachable by anyone who could connect, so "patch + authenticate + don't deserialize untrusted objects + don't expose management" closes the overwhelming majority of real-world risk.

#### Q50. [Theory] Why is synchronous request/reply over a message broker often an anti-pattern, and when is it nonetheless justified?

Layering blocking request/reply on top of an inherently asynchronous, store-and-forward broker fights the medium, and understanding *why* separates senior engineers from those who reach for JMS reflexively. A broker is optimized for **decoupling in time** — the producer fires and forgets, the consumer processes whenever it can, and the broker buffers in between. Synchronous request/reply throws that away: the caller now **blocks a thread** waiting for a correlated response, so you pay all the broker's overhead (an extra network hop to the broker, persistence/journaling, queueing, a reply hop back) to emulate something a direct HTTP/gRPC call does in **one** round trip with less latency and far simpler failure semantics. You've added a stateful intermediary to a fundamentally synchronous interaction.

The failure modes get worse, not better. Latency is **higher and less predictable** (your request can queue behind a backlog). **Timeouts are murky** — when the reply doesn't arrive, did the request not get processed, get processed but the reply was lost, or is it merely slow? With request/reply over a broker you often can't tell, and the request may *still* be processed after you've given up, causing duplicate effects. **Temporary-queue-per-request** (Q29) churns destinations and dies on reconnect, losing in-flight replies; the **shared-reply-queue** variant adds selector-scanning load. And you've coupled the caller's availability to the responder's in real time — the very coupling the broker was supposed to remove — while keeping all the broker's operational weight.

It is nonetheless justified in specific situations: when you genuinely need the broker's **buffering/load-leveling or guaranteed delivery** on the request leg (the responder may be temporarily down and you want the request durably queued, with the reply arriving later), when you're integrating with a **legacy/third-party system that only speaks JMS** and exposes no HTTP API, when you need **protocol bridging or transactional enlistment** of the request within a larger JMS/XA unit of work, or when the "reply" is naturally asynchronous and you only occasionally correlate. The senior heuristic: **if you need a fast, synchronous answer, use a synchronous transport (HTTP/gRPC); use JMS request/reply only when you specifically want the broker's durability, decoupling, or buffering on the request path and can tolerate asynchronous, correlated, possibly-duplicated replies.** Spring's `JmsTemplate.sendAndReceive` makes the pattern *easy*, which is precisely why it gets overused — easy to write is not the same as appropriate to use.

#### Q51. [Practical] A team reports that messages are being processed out of order despite using a single queue. Diagnose the likely causes.

"Single queue, but out-of-order processing" is a common and instructive complaint, because a single queue **does not by itself guarantee ordered *processing*** — it only guarantees ordered *delivery* under specific conditions, all of which are easy to violate. My diagnosis walks the small list of things that break ordering, fastest-to-check first. The overwhelmingly likely cause is **multiple competing consumers**: with two or more consumers (or a listener `concurrency` greater than 1), the broker dispatches message 1 to consumer A and message 2 to consumer B, and they process **concurrently** — A might be slower, so message 2 finishes first. The queue delivered them in order; parallel processing reordered them. This is by design (it's how queues scale) and is the cause ~80% of the time.

```
One queue, concurrency > 1  →  ORDER LOST
                 ┌─► C1: processes msg1 (slow)
queue [m1 m2] ──►│
                 └─► C2: processes msg2 (fast → finishes first)   ← reordered

Fixes (pick the narrowest that meets the real requirement):
- need total order      → exclusive consumer (consumer.exclusive=true), 1 worker
- need per-key order    → JMSXGroupID per business key (scales across keys)
- concurrency=1         → single-threaded listener (no scaling)
```

If concurrency is genuinely 1 and order still breaks, I check the next causes: (1) **redelivery interleaving** — a message that fails and is redelivered after a backoff delay re-enters *behind* later messages, so a poison message with retries reorders the stream around it; (2) **priority messages** — if `JMSPriority` / prioritizedMessages is enabled, higher-priority messages jump ahead of earlier lower-priority ones by design; (3) **multiple producers** with no global sequencing — "order" was never well-defined because two producers' messages have no inherent order, only per-producer order; (4) **a network of brokers** forwarding messages, where forwarding latency reorders across brokers; (5) **non-persistent + persistent mixing** or **expiry** removing messages mid-stream. 

The resolution is to first pin down **what ordering the business actually requires** — almost never *global* order, usually *per-entity* (per account, per order ID). Then apply the **narrowest** mechanism: `JMSXGroupID` keyed on that entity (preserves per-key order while still scaling across keys — the right answer most of the time), or an **exclusive consumer**/concurrency-1 only if true total order is required (accepting the single-worker throughput cap). I also make consumers tolerant of redelivery reordering via idempotency and, where order matters within a unit, sequence numbers the consumer can detect gaps in. The teaching point: a queue gives ordered *delivery to the dispatcher*, not ordered *completion across parallel workers* — if you scaled consumers, you opted out of order, and the fix is to scope ordering to a key, not to serialize the whole queue.

#### Q52. [Behavioral] Describe a time you diagnosed and resolved a production messaging incident under pressure. What did you learn?

I'd answer this with a structured Situation–Task–Action–Result narrative that demonstrates calm methodology, not heroics. A strong version: *"Situation: on a payments platform, our order-confirmation emails and downstream fulfillment stopped during a peak sales event; the on-call alert was 'orders.in queue depth climbing fast' and customer support was lighting up about missing confirmations. Task: restore processing without losing or double-processing any payment events, and do it fast — every minute was a backlog of real customer orders. Action: I resisted the urge to just restart the broker (which would have masked the cause and risked redelivery storms). I pulled up the broker JMX/console first and read the evidence: `EnqueueCount` was climbing, `DequeueCount` was flat at zero, and `ConsumerCount` on the queue was 0 — so the broker was healthy and producers were fine; the **consumers had all died**. A deploy 20 minutes earlier had shipped a bug that threw on startup, so every consumer instance crash-looped and none registered. I rolled back the consumer deployment, consumers re-registered, prefetch was lowered temporarily to spread the backlog evenly, and the queue drained in a few minutes. Because consumers were idempotent (dedup on a business key), the redelivery of in-flight messages caused no duplicate charges. Result: full recovery in about 15 minutes, zero lost or double-processed payments, and a clean post-incident timeline because I'd read the counters instead of guessing."*

The reflection is where the seniority shows. The lessons I'd cite: first, **read the broker's own evidence before acting** — `ConsumerCount = 0` instantly localized the fault to the consumers, not the broker or producers, and saved me from a pointless broker restart. Second, the incident was **caught late** because we alerted on queue *depth* but not on **consumer count dropping to zero** on a critical queue; I added that leading-indicator alert afterward (Q47), so a future "all consumers died" pages us in seconds, not minutes. Third, **idempotent consumers turned a scary redelivery into a non-event** — that design choice, made months earlier, is what let me recover aggressively without fear of double-charging. Fourth, I drove a blameless postmortem whose action items were *structural* (the consumer-count alert, a startup health-check gate in the deploy pipeline so a crash-looping consumer fails the rollout automatically) rather than "be more careful." The interviewer signal: under pressure I diagnosed from data, chose the least-risky corrective action, leaned on prior good design (idempotency), and converted the incident into durable prevention rather than a one-off fix.

## 🧩 Extended Questions — Supplemental Set: Mixed Depth

### 🟢 Basic — extended

#### Q53. [Theory] What are message priority and expiration (TTL), and how reliably does ActiveMQ honour them?

JMS lets a producer attach two scheduling hints to a message: **priority** (`JMSPriority`, 0–9, default 4) and **time-to-live** (`setTimeToLive(ms)`, which the broker turns into an absolute `JMSExpiration` timestamp). Priority is a *delivery-ordering* hint — higher-priority messages should be dispatched ahead of lower-priority ones. TTL is an *expiry* contract — once the wall-clock passes `JMSExpiration`, the message is no longer eligible for delivery and the broker discards it (optionally routing it to the DLQ or an expiry destination).

The honest senior framing is that **both are best-effort, not guarantees**. Priority ordering is only honoured if the destination has prioritization enabled (`prioritizedMessages="true"` in the destination policy); without it, ActiveMQ ignores priority and delivers FIFO. Even when enabled, messages already dispatched into a consumer's prefetch buffer won't be re-sorted by a newly-arrived higher-priority message, and persistent-store ordering adds further caveats. TTL is more dependable but has a subtlety: expiry is evaluated lazily (when the broker next touches the message, e.g. on dispatch or during a periodic sweep), so an "expired" message can linger in the store briefly past its deadline, and the broker's clock — not the producer's — is authoritative, so clock skew across hosts matters.

```java
ctx.createProducer()
   .setPriority(9)                 // urgent — only honoured if prioritizedMessages=true
   .setTimeToLive(30_000)          // discard if not consumed within 30s
   .send(queue, "price-tick");
```

```xml
<!-- activemq.xml: enable priority ordering on a destination (off by default) -->
<policyEntry queue="quotes.>" prioritizedMessages="true"/>
```

The practical guidance: use TTL aggressively for time-sensitive, perishable data (live quotes, cache invalidations, "process within N seconds" commands) because it bounds backlog growth and prevents stale work — this is one of the cheapest ways to stop a slow consumer from accumulating an unbounded store. Treat priority as a soft hint for a small number of genuinely-urgent messages, not as a scheduling engine; if you need hard prioritization, use **separate destinations** (a `quotes.urgent` queue with dedicated consumers) rather than relying on `JMSPriority`, because separate queues give you predictable, observable, independently-scalable lanes.

#### Q54. [Coding] Write a consumer using an asynchronous `MessageListener` and explain its threading model versus synchronous `receive()`.

**Problem:** Consume from `orders.in` asynchronously with a callback, and explain who runs `onMessage` and what that implies for thread-safety.

```java
import jakarta.jms.*;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;

public class AsyncConsumer {
    public static void main(String[] args) throws Exception {
        ConnectionFactory factory =
            new ActiveMQConnectionFactory("tcp://localhost:61616");
        JMSContext ctx = factory.createContext(JMSContext.AUTO_ACKNOWLEDGE);
        Queue queue = ctx.createQueue("orders.in");

        JMSConsumer consumer = ctx.createConsumer(queue);
        consumer.setMessageListener(msg -> {                 // async callback
            try {
                String body = msg.getBody(String.class);
                System.out.println("Processed: " + body);
                // onMessage runs on the JMS session's delivery thread —
                // it is single-threaded PER session, so this method is
                // never called concurrently for the same consumer.
            } catch (JMSException e) {
                throw new RuntimeException(e);                // → redelivery on auto-ack? No:
                // auto-ack acks BEFORE onMessage returns logic varies; prefer transacted
            }
        });
        // main thread does NOT block; delivery happens on the session thread.
        Runtime.getRuntime().addShutdownHook(new Thread(ctx::close));
        Thread.currentThread().join();                        // keep JVM alive
    }
}
```

**Threading model — the key teaching point:** A JMS `Session` (and the `JMSContext` that wraps one) is **single-threaded by contract**. When you register a `MessageListener`, the JMS provider delivers messages to `onMessage` **serially on a single dedicated session thread** — it will never invoke your listener concurrently for the same session. This is why a `Session` must not be shared across application threads, and why your `onMessage` body doesn't need internal synchronization for session state. To process messages **in parallel**, you create **multiple sessions/consumers** (each gets its own delivery thread) — which is exactly what Spring's `DefaultMessageListenerContainer` does when you set `concurrency="3-10"`: it spins up N sessions, each single-threaded, giving N-way parallelism with per-session serial delivery.

**Async vs sync trade-offs:** `consumer.receive(timeout)` is **synchronous/pull** — your thread blocks until a message arrives or the timeout fires, giving you precise control over *when* you pull (useful for batch windows, rate limiting, or pulling on your own scheduler). `MessageListener.onMessage` is **asynchronous/push** — the provider drives delivery as fast as prefetch allows, which is higher-throughput and the natural fit for always-on consumers, but you cede control of pacing to the broker's prefetch. **Edge cases:** with `AUTO_ACKNOWLEDGE`, throwing from `onMessage` does *not* reliably trigger redelivery (the ack semantics around listener exceptions are murky) — for reliable retry use a **transacted session** and let the rollback redeliver, or `CLIENT_ACKNOWLEDGE` with explicit control. **Time:** O(1) per message; **Space:** O(prefetch) buffered per consumer.

#### Q55. [Theory] What is the difference between a `QueueBrowser` peek and an actual consume, and what are the limits of browsing?

A `QueueBrowser` lets you **look at messages without removing them** — it returns an `Enumeration` over a *snapshot* of the queue's contents, reading each message non-destructively (no acknowledgement, no state change). A real consume, by contrast, **dispatches** the message to the consumer, marks it in-flight, and removes it from the queue once acknowledged. The distinction matters because browsing is the safe primitive for diagnostics, admin dashboards, and "what's stuck in here?" investigations, whereas consuming is the destructive operation that actually drains the queue.

The limits are important and frequently misunderstood. First, a browser **only sees messages that are not currently dispatched/in-flight** to a consumer — messages sitting in a consumer's prefetch buffer are invisible to the browser, so a browse count can be **lower** than the broker's reported `QueueSize` under active load. Never treat a browser count as an exact depth gauge; use the JMX `QueueSize` for that. Second, the browser sees a **snapshot taken when the enumeration starts**; messages enqueued after you begin iterating may or may not appear, and messages consumed by others mid-iteration may vanish — it is explicitly *not* a transactional view. Third, browsing a very deep queue can be expensive (the broker may page messages back into memory to serve the browse), so browsing a million-message backlog is itself a load event.

```java
QueueBrowser browser = ctx.createBrowser(ctx.createQueue("orders.in"));
Enumeration<?> e = browser.getEnumeration();
while (e.hasMoreElements()) {
    Message m = (Message) e.nextElement();    // NON-destructive snapshot view
    // Inspect headers/properties; message stays on the queue for real consumers
}
browser.close();   // browsers hold broker resources — always close
```

The practical uses: building an admin "inspect queue" screen, sampling a DLQ to find the failure reason before replaying (Q26), or asserting in a test that a message landed where expected. The anti-pattern is using a browser as a **leader-election or work-claiming mechanism** ("browse to find work, then consume it") — there's an inherent race between browse and consume, and another consumer can grab the message in between; for work distribution you simply consume with competing consumers and let the broker do the assignment.

### 🟡 Intermediate — extended

#### Q56. [Practical] How do you schedule delayed or future-dated message delivery in ActiveMQ?

ActiveMQ Classic has a built-in **scheduler** (backed by its own KahaDB-based job store) that lets a producer ask the broker to **hold a message and deliver it later** — without the producer staying alive or running its own timer. You enable it on the broker (`schedulerSupport="true"` on the `<broker>` element) and then set scheduler properties on each message: a one-shot delay, or a repeating schedule with a period and repeat count, or even a cron expression. This is genuinely useful for "remind me in 30 minutes," retry-with-backoff that survives consumer restarts, delayed order cancellation, and batch-window triggering — logic you'd otherwise build with a database table and a polling job.

```java
import org.apache.activemq.ScheduledMessage;

Message m = ctx.createTextMessage("cancel-if-unpaid:order-42");
// deliver 30 minutes from now (one-shot delay, in ms)
m.setLongProperty(ScheduledMessage.AMQ_SCHEDULED_DELAY, 30 * 60_000L);
// OR a repeating schedule: every 60s, up to 5 times
// m.setLongProperty(ScheduledMessage.AMQ_SCHEDULED_PERIOD, 60_000L);
// m.setIntProperty(ScheduledMessage.AMQ_SCHEDULED_REPEAT, 5);
// OR a cron expression
// m.setStringProperty(ScheduledMessage.AMQ_SCHEDULED_CRON, "0 0 * * *");
ctx.createProducer().send(queue, m);
```

```xml
<!-- activemq.xml (Classic) -->
<broker schedulerSupport="true" ... >
```

The behaviour to understand: the scheduled message **does not appear in the target queue at all** until its fire time — it lives in the broker's scheduler store, so consumers and even queue browsers don't see it early (you inspect pending scheduled jobs via the JMX scheduler MBean or the web console's "Scheduled" view). This is both the feature and the gotcha: people are surprised that `send()` "succeeded" but nothing landed in the queue. In **Artemis**, the equivalent is the JMS 2.0-standard **delivery delay** (`producer.setDeliveryDelay(ms)`) plus the `_AMQ_SCHED_DELIVERY` property for an absolute scheduled time — cleaner because it's part of the JMS API rather than a Classic-proprietary extension.

The trade-offs and cautions: the scheduler store is a **single point of contention** and adds load, so scheduling millions of far-future messages is an anti-pattern — for large-scale delayed work a dedicated scheduler (Quartz, a DB-backed job table, or a delay-queue design) often scales better. Scheduled messages also interact with HA — they're persisted in the scheduler store, so they survive restart, but you must ensure the scheduler store is included in your HA/replication story. And for **retry-with-backoff**, broker-side scheduling (re-send with an increasing `AMQ_SCHEDULED_DELAY`) is a clean alternative to client-side redelivery delays because the backoff survives a consumer crash.

#### Q57. [Theory] Explain wildcard/hierarchical destinations and how composite addressing changes consumer design.

ActiveMQ supports **hierarchical destination names** using dot-separated segments (`orders.eu.priority`, `orders.us.standard`) and lets consumers subscribe with **wildcards** across that hierarchy. There are three wildcard tokens in Classic: `.` separates path segments, `*` matches **exactly one** segment, and `>` matches **one or more** trailing segments (recursive). So `orders.*.priority` matches `orders.eu.priority` and `orders.us.priority` but not `orders.eu.fast.priority`, while `orders.>` matches everything under `orders`. Artemis uses the same idea with `#` (multi-level) and `*` (single-level) on **addresses**. This turns destination naming into a lightweight, broker-evaluated routing taxonomy.

```
Hierarchy:           orders.eu.priority   orders.eu.standard   orders.us.priority
Consumer subscribes to:
   orders.eu.>       → eu.priority, eu.standard            (all EU)
   orders.*.priority → eu.priority, us.priority            (all priority, any region)
   orders.>          → everything under orders             (firehose)
```

```java
// One consumer drains every EU order regardless of sub-type
ctx.createConsumer(ctx.createQueue("orders.eu.>"));
// Topic fan-out: subscribe to all "priority" events across regions
ctx.createConsumer(ctx.createTopic("orders.*.priority"));
```

Why this changes consumer design: instead of either one giant undifferentiated queue (where you filter every message with a selector and discard most) or N hardcoded queues (rigid, requires redeploys to add a category), you publish to **specific, structured names** and let consumers subscribe to exactly the slice they need via wildcards. Adding a new region or category is just a new name — existing `orders.>` consumers pick it up automatically, and new specialized consumers can carve out `orders.eu.priority` without touching producers. It's content-based routing done by *naming convention* rather than by selector scanning, so it's far cheaper than selectors for the broker.

The caveats are real, though. Wildcard subscriptions on **topics** are powerful but a `>` subscriber becomes a firehose that's easy to overwhelm. On **queues**, wildcard *consuming* semantics are more nuanced (a wildcard queue consumer effectively consumes from multiple physical queues, and the load-balancing/ordering across them needs thought). And the taxonomy is a **public contract** — once consumers depend on `orders.eu.>`, renaming the hierarchy breaks them, so design the naming scheme deliberately (region.priority.type ordering, stable segments) the way you'd design a URL scheme or a topic key in Kafka. The senior point: hierarchical names + wildcards are the idiomatic ActiveMQ alternative to heavy selector use, but the hierarchy is an API you must version and govern.

#### Q58. [Coding] Show how to test a JMS producer/consumer with an embedded broker (JUnit) and with Testcontainers.

**Problem:** Write fast, reliable integration tests for JMS code without depending on an external broker. Show both an **embedded in-VM broker** (fastest) and a **Testcontainers** approach (closest to production).

```java
// Option A: Artemis embedded broker (in-VM) — fast, no Docker, great for unit/IT tests
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import jakarta.jms.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddedBrokerTest {
    static EmbeddedActiveMQ broker;

    @BeforeAll
    static void start() throws Exception {
        var cfg = new ConfigurationImpl();
        cfg.setPersistenceEnabled(false);                 // in-memory: fast + isolated
        cfg.setSecurityEnabled(false);
        cfg.addAcceptorConfiguration("invm", "vm://0");    // in-VM transport, no sockets
        broker = new EmbeddedActiveMQ();
        broker.setConfiguration(cfg);
        broker.start();
    }

    @AfterAll
    static void stop() throws Exception { broker.stop(); }

    @Test
    void roundTrips() throws Exception {
        var f = new ActiveMQConnectionFactory("vm://0");
        try (JMSContext ctx = f.createContext()) {
            Queue q = ctx.createQueue("test.q");
            ctx.createProducer().send(q, "hello");
            String got = ctx.createConsumer(q).receiveBody(String.class, 2000);
            assertEquals("hello", got);
        }
    }
}
```

```java
// Option B: Testcontainers — runs a real broker in Docker, closest to prod behavior
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class TestcontainersBrokerTest {
    @Container
    static GenericContainer<?> mq =
        new GenericContainer<>("apache/activemq-artemis:latest")
            .withExposedPorts(61616)
            .withEnv("ARTEMIS_USER", "admin")
            .withEnv("ARTEMIS_PASSWORD", "admin");

    @Test
    void roundTrips() throws Exception {
        String url = "tcp://" + mq.getHost() + ":" + mq.getMappedPort(61616);
        var f = new org.apache.activemq.artemis.jms.client
                    .ActiveMQConnectionFactory(url, "admin", "admin");
        try (jakarta.jms.JMSContext ctx = f.createContext()) {
            var q = ctx.createQueue("test.q");
            ctx.createProducer().send(q, "hello");
            assertEquals("hello", ctx.createConsumer(q).receiveBody(String.class, 5000));
        }
    }
}
```

**Trade-offs and when to use each:** The **embedded in-VM broker** (`vm://0`, persistence disabled) is the right default for the bulk of your tests — it starts in milliseconds, needs no Docker, is fully isolated per test class, and exercises real broker routing/ack/selector logic. Its limitation is fidelity: it won't catch wire-protocol issues, version-specific behavior, persistence/journal quirks, or HA/failover semantics. **Testcontainers** runs the *actual* broker image you ship, over a real TCP socket, so it catches OpenWire/AMQP wire issues, config-file behavior, and protocol parity — at the cost of Docker dependency and seconds (not ms) of startup. **My layering:** embedded broker for the fast inner loop (per-class, dozens of tests), Testcontainers for a smaller suite of "does it really work against the real broker image" integration tests, and a staging broker for failover/HA chaos tests (which neither unit approach can simulate). **Edge cases to test deliberately:** redelivery on rollback, DLQ routing after max attempts, selector filtering, and that your idempotency/dedup logic actually suppresses a redelivered duplicate — these are the behaviors that break silently in production.

#### Q59. [Practical] How do you tune message cursors and memory in ActiveMQ Classic to handle a large backlog without OOM?

ActiveMQ Classic uses **message cursors** to decide how messages move between disk (the store) and memory while waiting for consumers. Understanding cursors is the key to surviving deep backlogs without an out-of-memory crash, because the cursor type determines whether a million-message queue tries to hold all million in heap or pages them through a bounded window. There are three cursor strategies: the **Store cursor** (the default for persistent messages — it keeps a working set in memory and pages the rest from the store, so the queue can be far larger than RAM), the **VM cursor** (everything in memory — fast but blows up on large backlogs, used for non-persistent), and the **File cursor** (spills to temp disk files when memory fills, used for non-persistent overflow).

```xml
<!-- activemq.xml: per-destination memory + cursor tuning for big-backlog tolerance -->
<policyEntry queue=">" memoryLimit="64mb" producerFlowControl="false">
  <pendingQueuePolicy>
    <storeCursor/>          <!-- page from store; bounded heap working set -->
  </pendingQueuePolicy>
</policyEntry>

<!-- For non-persistent that may overflow, spill to temp disk instead of OOM: -->
<policyEntry topic="events.>">
  <pendingMessageLimitStrategy>
    <constantPendingMessageLimitStrategy limit="5000"/>  <!-- bound slow-topic backlog -->
  </pendingMessageLimitStrategy>
</policyEntry>
```

The interplay with `systemUsage` (Q33) is where the tuning lives. The per-destination `memoryLimit` is the cursor's heap budget for that queue; the broker-wide `memoryUsage` is the sum-of-all ceiling. With the **Store cursor**, a deep persistent backlog stays bounded in heap (it pages from KahaDB), so the failure mode isn't OOM but **slower dispatch** as the cursor faults messages back in from disk — which is the *correct* trade-off (degrade throughput, don't crash). The classic OOM happens when someone uses a memory-heavy cursor, sets `memoryLimit` too high relative to heap, or runs many destinations whose combined `memoryLimit` exceeds `memoryUsage`.

The practical tuning recipe I apply: (1) keep `storeCursor` for persistent queues so backlogs page rather than OOM; (2) set per-destination `memoryLimit` modestly (tens of MB) and ensure the sum across hot destinations leaves headroom under `memoryUsage` (which itself should be a fraction of heap, e.g. 70%); (3) bound slow/non-critical topics with `constantPendingMessageLimitStrategy` so a non-durable topic backlog can't grow without limit; (4) decide the backpressure policy per destination (PFC block vs spool vs drop, from Q33). The mental model: **cursors let a queue be bigger than RAM by paging through the store** — your job is to size the memory windows so the working set fits, the rest pages gracefully, and one runaway destination can't starve the others of the shared `memoryUsage` budget. "Add more heap" is almost never the right fix; "make the cursor page and bound the destination" is.

#### Q60. [Coding] Implement broker-side duplicate detection in Artemis and explain its window semantics.

**Problem:** Use Artemis's built-in duplicate detection so an accidental producer resend (e.g. after an ambiguous timeout) is dropped by the broker, and explain why it's defense-in-depth rather than a complete idempotency solution.

```java
import org.apache.activemq.artemis.api.core.Message;   // Core API constant
import jakarta.jms.*;

// Producer: stamp a stable duplicate-detection id derived from business identity.
// Artemis recognizes the special property "_AMQ_DUPL_ID".
String duplId = order.getId() + ":ORDER_PLACED";       // deterministic, NOT random
TextMessage m = ctx.createTextMessage(json);
m.setStringProperty("_AMQ_DUPL_ID", duplId);           // Message.HDR_DUPLICATE_DETECTION_ID
ctx.createProducer().send(queue, m);

// If the SAME _AMQ_DUPL_ID is sent again within the broker's id-cache window,
// the broker silently drops the second copy (for a non-transacted send) or
// throws on commit (for a transacted send) — the message never reaches consumers.
```

```xml
<!-- broker.xml: size the duplicate-id cache (per address) -->
<address-settings>
  <address-setting match="orders">
    <id-cache-size>20000</id-cache-size>      <!-- remembers last N dupl-ids -->
  </address-setting>
</address-settings>
<core>
  <persist-id-cache>true</persist-id-cache>    <!-- survive broker restart -->
</core>
```

**How the window works — the critical limitation:** Artemis keeps a **bounded, fixed-size cache** of recently-seen `_AMQ_DUPL_ID` values (the `id-cache-size`). When a new message arrives, the broker checks its id against the cache; a hit means "duplicate, drop it," a miss means "accept and add to cache." Because the cache is **bounded (LRU-style eviction)**, a duplicate that arrives *after* its id has aged out of the cache (more than `id-cache-size` distinct ids later) will **not** be detected — it's a sliding window of the last N ids, not an infinite memory. With `persist-id-cache=true` the cache survives restart; without it, a restart resets the window and a post-restart resend slips through.

**Why it's defense-in-depth, not a full solution:** This catches the *common, near-in-time* duplicate — a producer that resends within seconds after an ambiguous failure — cheaply and at the broker, before consumers ever see it. But it cannot guarantee end-to-end exactly-once *effect* because (1) the window is finite, so a delayed duplicate beyond N ids escapes; (2) a restart without persistence resets it; (3) it only dedups *delivery*, not the *processing side effect* if your consumer itself is non-idempotent for other reasons. So I use it as a **first-line filter** layered on top of the durable, restart-proof guarantee that lives in the consumer's DB-backed **idempotency/inbox table** (Q28, Q40): the broker dedup absorbs the bulk of cheap retries, and the inbox catches everything the window misses. **Time:** O(1) cache lookup per message; **Space:** O(id-cache-size) broker memory per address.

### 🟠 Advanced — extended

#### Q61. [Theory] How does Artemis paging work, and how does it differ from Classic's cursors and from blocking flow control?

Artemis handles memory pressure with **paging**, a mechanism distinct from both Classic's message cursors and from Producer Flow Control's blocking. Each **address** has a `max-size-bytes` budget for messages held in memory. While an address is under that budget, messages live in memory for fast routing and dispatch. When an address **exceeds** `max-size-bytes`, Artemis switches that address into **paging mode**: incoming messages are written to sequential **page files** on disk (under the address's paging directory) instead of being kept in memory, and they're read back in page-sized chunks as consumers catch up. Crucially, paging is **per-address** and the producer is **not blocked** — sends keep succeeding, the data just goes to disk.

```
Artemis address lifecycle under load (address-full-policy = PAGE):
  in-memory  ──exceeds max-size-bytes──►  PAGING (write to page files on disk)
     fast routing                            sends still accepted, no producer block
  consumers drain ──falls below──►  back to in-memory
```

```xml
<!-- broker.xml: choose the address-full behavior explicitly -->
<address-setting match="#">
  <max-size-bytes>100MB</max-size-bytes>
  <page-size-bytes>10MB</page-size-bytes>
  <address-full-policy>PAGE</address-full-policy>   <!-- PAGE | BLOCK | DROP | FAIL -->
</address-setting>
```

The contrast with the other two mechanisms is the heart of the question. **Classic cursors** (Q59) also page persistent messages from the store, but the model is the older message-cursor abstraction tied to KahaDB; Artemis paging is a first-class, address-level, page-file mechanism designed into its non-blocking core. **Producer Flow Control / BLOCK** stops accepting sends to protect the broker — it converts a consumer problem into a producer problem (Q17, Q33). Paging instead **absorbs the overflow onto disk and keeps producers flowing**, decoupling durability/throughput from RAM. That's why Artemis's default `address-full-policy` of `PAGE` is generally preferable to blocking for most workloads: a slow consumer causes disk usage to grow (which you monitor and bound) rather than freezing producers.

The trade-offs and decision points: paging trades **memory pressure for disk pressure and some dispatch latency** (paged messages must be read back), and unbounded paging just relocates the "store fills up" problem to the page directory — so you still need TTL/expiry and `global-max-size` limits, and you still monitor disk. The four `address-full-policy` choices encode a deliberate business decision per address: **PAGE** (absorb to disk — default, best for most), **BLOCK** (backpressure producers — for when you must not drop and disk is precious), **DROP** (silently discard new messages — only for truly disposable data like live telemetry), and **FAIL** (throw to the producer — let the application shed load explicitly). The senior framing: Artemis paging is *graceful overflow*, Classic cursors are *the older paging model*, and flow-control blocking is *backpressure* — and choosing PAGE vs BLOCK vs DROP vs FAIL per address is one of the most consequential Artemis tuning decisions, made by the message's business meaning, not a global default.

#### Q62. [Practical] How do you deploy and operate ActiveMQ Artemis on Kubernetes, and what are the failure-mode pitfalls?

Running a stateful, replicated broker on Kubernetes is very different from running a stateless service, and the pitfalls cluster around **identity, storage, and quorum**. The right primitive is a **StatefulSet**, not a Deployment, because each broker needs a **stable network identity** (so a backup always knows which live to pair with and clients' `failover:` lists stay valid) and **stable persistent storage** (the journal and paging must survive pod rescheduling). Each broker gets its own `PersistentVolumeClaim` via `volumeClaimTemplates`, backed by a storage class with the right performance (the journal wants low-latency SSD; networked storage with high fsync latency murders throughput).

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata: { name: artemis }
spec:
  serviceName: artemis-hl          # headless service → stable DNS per pod
  replicas: 2                      # live + backup pair (quorum runs separately)
  template:
    spec:
      containers:
      - name: artemis
        image: apache/activemq-artemis:2.x
        ports: [{ containerPort: 61616 }, { containerPort: 8161 }]
        volumeMounts:
        - { name: data, mountPath: /var/lib/artemis/data }
        readinessProbe:            # do NOT route clients to a not-yet-live backup
          tcpSocket: { port: 61616 }
        livenessProbe:
          httpGet: { path: /, port: 8161 }
  volumeClaimTemplates:
  - metadata: { name: data }
    spec:
      accessModes: [ReadWriteOnce]
      resources: { requests: { storage: 50Gi } }
      storageClassName: fast-ssd   # low-fsync-latency SSD, not slow networked disk
```

The **failure-mode pitfalls** are where teams get burned. First, **quorum and split-brain (Q37)**: Artemis replication needs a tiebreaker, so on K8s you run a **ZooKeeper ensemble (3 nodes) in separate failure domains/anti-affinity** as the quorum manager — co-locating quorum members on the same node as the brokers means a node loss can take out broker *and* its quorum vote, defeating the purpose. Second, **probes that lie**: a naive `readinessProbe` that just checks the port is open will route clients to a **backup that isn't live**, so the probe must reflect actual broker liveness/role — otherwise failover sends traffic into a black hole. Third, **storage**: `ReadWriteOnce` PVCs on slow networked storage cause journal fsync latency spikes that look like broker stalls (Q34); and you must *never* let two pods mount the same shared-store volume simultaneously (the K8s scheduler can briefly double-schedule during failover) — which is another reason replication beats shared-store on K8s. Fourth, **client connectivity**: clients outside the cluster need stable, addressable endpoints (a `Service` per broker or a properly configured ingress/LoadBalancer that preserves per-broker addressing for `failover:`), and the broker's advertised connector host must match what clients can actually reach (a common "connects then immediately disconnects" bug when the broker advertises its internal pod IP).

The operational reality: I strongly prefer the **ArtemisCloud / AMQ Operator** over hand-rolled StatefulSets, because the operator encodes the hard-won knowledge — it manages the StatefulSet, the quorum, the per-broker services, rolling upgrades with controlled failover (Q43), and scaling with message redistribution — rather than leaving you to rediscover the split-brain and probe pitfalls in production. The meta-point I stress: Kubernetes makes *stateless* workloads easy and *stateful, consensus-dependent* workloads deceptively hard; a message broker is squarely the latter, so treat HA on K8s as a quorum-and-storage problem first, and chaos-test pod eviction + node loss + network partition before trusting it.

#### Q63. [Theory] Compare OpenWire, the Artemis Core protocol, and AMQP 1.0 as wire protocols. Why does the choice matter?

ActiveMQ brokers speak several wire protocols, and they are *not* interchangeable abstractions — each has different capabilities, performance characteristics, and interoperability stories. **OpenWire** is ActiveMQ Classic's native binary protocol: rich (it carries advisories, supports the full Classic feature set, prefetch-based flow control) but **proprietary to ActiveMQ** and historically the source of the deserialization RCEs (Q49) because it marshalled Java objects on the wire. Artemis *also* supports OpenWire (for Classic-client compatibility), which is why a Classic client can often point at Artemis with no code change (Q20). **Artemis Core protocol** is Artemis's own native protocol — the most efficient way to talk to Artemis, exposing its full feature set (large messages, addresses/routing types, advanced flow control) with minimal overhead; the Artemis JMS client uses it under the hood. **AMQP 1.0** is the **ISO/OASIS-standard, vendor-neutral** protocol: any AMQP 1.0 client (Qpid, Azure SDKs, Python `proton`, .NET, Go) can talk to Artemis, with protocol-native **credit-based flow control** (Q44) and a self-describing type system.

```
Protocol        Native to        Interop                 Flow control        Notable
OpenWire        Classic          ActiveMQ only           prefetch + PFC      advisories; legacy RCE history
Core            Artemis          Artemis only            credit-like         most efficient for Artemis
AMQP 1.0        vendor-neutral   any AMQP1 client/broker  link credit         standard; cross-language
STOMP           simple/text      any STOMP client        TCP + ack/receipt   trivial clients, web/scripts
MQTT            IoT              any MQTT client          QoS + inflight win  pub/sub for devices
```

Why the choice matters in practice: it's primarily a **client-ecosystem and portability decision**. If you're an all-Java shop on Artemis, the **Core protocol** (via the Artemis JMS client) gives you the best performance and full feature access — use it. If you need **non-Java clients** (Python data pipelines, .NET services, mobile, polyglot microservices), **AMQP 1.0** is the right choice because it's standardized and every language has a mature client — and critically, AMQP gives you **broker portability**: an AMQP 1.0 client can talk to Artemis, Azure Service Bus, Qpid, or RabbitMQ (with the AMQP plugin) with minimal change, so you're not locked to one broker. **OpenWire** I now reach for only for **Classic interop/migration** — keeping existing Classic clients working against Artemis during a migration (Q20) — not for greenfield, partly because of its proprietary nature and deserialization history.

The deeper lesson: in a multi-protocol broker, "which protocol" is an architectural choice with real consequences for performance (Core > AMQP > STOMP for raw throughput on Artemis), interoperability (AMQP wins for polyglot), security surface (every enabled acceptor is attack surface — disable unused ones, Q49), and even flow-control behavior (Q44). The mistake is treating the protocol as an invisible implementation detail; on a serious deployment you choose it deliberately per client type, enable only the acceptors you use, and standardize on AMQP 1.0 when portability and language diversity matter more than squeezing the last bit of Artemis-native throughput.

#### Q64. [Practical] How do you configure and use Artemis diverts and core bridges, and when do you choose each over a network of brokers?

Artemis offers three distinct message-movement primitives that are easy to conflate but solve different problems: **diverts**, **core bridges**, and **cluster connections**. A **divert** reroutes or copies messages *within a single broker* — when a message arrives at a source address, the divert sends it to a target address, either **exclusively** (the message goes only to the target, rerouting it) or **non-exclusively** (the message goes to both the original target *and* a copy to the divert target, i.e. tapping/auditing). Diverts can apply a filter and even a message transformation, all in-broker. A **core bridge** moves messages **from a queue on this broker to an address on a *remote* broker** over a connection — a one-directional, configured, store-and-forward link, optionally with a transformer and reconnection. A **cluster connection** is the symmetric, demand-aware clustering mechanism that load-balances and *redistributes* messages across a cluster of brokers based on where consumers are.

```xml
<!-- broker.xml: non-exclusive divert taps a copy of every order to an audit address -->
<diverts>
  <divert name="audit-tap">
    <address>orders</address>
    <forwarding-address>audit.orders</forwarding-address>
    <exclusive>false</exclusive>            <!-- copy, don't reroute -->
    <filter string="region = 'EU'"/>        <!-- optional selective tap -->
  </divert>
</diverts>

<!-- Core bridge: forward a local queue to a remote broker's address (one-way) -->
<bridges>
  <bridge name="to-dr">
    <queue-name>orders</queue-name>
    <forwarding-address>orders</forwarding-address>
    <static-connectors><connector-ref>dr-broker</connector-ref></static-connectors>
    <reconnect-attempts>-1</reconnect-attempts>   <!-- retry forever -->
  </bridge>
</bridges>
```

When to choose each: use a **divert** for **in-broker routing/auditing/tapping** — e.g. mirroring every payment to an audit queue, splitting a stream by region, or implementing a wiretap pattern, all without an extra broker or client. Use a **core bridge** for **deterministic, one-directional store-and-forward to a specific remote broker** — e.g. shipping a DLQ or an event stream to a DR datacenter (Q36), aggregating from edge brokers to a central one, or feeding a downstream system on another broker; a bridge is configured and predictable (you say exactly what goes where), unlike demand-driven forwarding. Use a **cluster connection** for **symmetric scaling within a datacenter** — multiple peer brokers that load-balance producers and **redistribute** messages to wherever the consumers currently are.

The contrast with a Classic **network of brokers** (Q13) is the punchline. A network of brokers is **demand-forwarding**: it propagates consumer demand via advisories and forwards messages toward consumers dynamically, which is flexible but prone to the **stuck-message / message-loop** problems in deep meshes. Artemis's model deliberately *separates concerns*: bridges for explicit point-to-point forwarding, cluster connections for symmetric load-balancing with redistribution, and diverts for local routing — each is more predictable than a general demand-forwarding mesh. So my decision rule: **divert** for local routing/audit, **core bridge** for explicit one-way cross-broker/cross-DC forwarding, **cluster connection** for intra-DC scale-out, and I avoid building deep multi-hop forwarding topologies altogether — predictable, purpose-built links beat an emergent mesh whose routing you can't reason about at 2am.

#### Q65. [Theory] What are last-value queues and retroactive/recovery policies, and what problems do they solve?

These are two specialized delivery features that address the "I only care about the *latest* state, not every event" and "I joined late and need recent history" problems respectively. A **last-value queue** (Artemis `last-value` / `last-value-key`) collapses messages that share a **last-value key** so the queue retains only the **most recent** message per key — when a new message arrives with a key that already has a pending message, the old one is *replaced*. This is perfect for **state/snapshot semantics**: stock price ticks, device status, configuration values, dashboard gauges — where a consumer that's behind shouldn't grind through 10,000 stale prices for symbol X but should jump straight to the current one. It bounds backlog naturally (one message per key) and means a slow or reconnecting consumer always gets *current* state, not a history replay.

```xml
<!-- broker.xml: a last-value queue keyed on the "symbol" property -->
<address name="quotes">
  <anycast>
    <queue name="quotes">
      <last-value-key>symbol</last-value-key>   <!-- keep only latest per symbol -->
    </queue>
  </anycast>
</address>
```

```java
m.setStringProperty("symbol", "AAPL");          // key; new AAPL replaces pending AAPL
ctx.createProducer().send(ctx.createQueue("quotes"), m);
```

A **retroactive consumer** (Classic) / recovery policy addresses the opposite-but-related "late joiner" problem on **topics**: normally a non-durable topic subscriber misses everything published before it connected, but a retroactive consumer can receive a configurable amount of **recent history** on subscribe. Classic implements this with **subscription recovery policies** per topic — `FixedCountSubscriptionRecoveryPolicy` (keep the last N messages), `FixedSizedSubscriptionRecoveryPolicy` (keep the last X bytes), `TimedSubscriptionRecoveryPolicy` (keep messages from the last T ms), or `LastImageSubscriptionRecoveryPolicy` (keep only the most recent — last-value semantics for topics). A consumer marked `consumer.retroactive=true` then receives that retained window on connect.

```xml
<!-- Classic: keep the last 100 messages on a topic for late-joining retroactive consumers -->
<policyEntry topic="market.>">
  <subscriptionRecoveryPolicy>
    <fixedCountSubscriptionRecoveryPolicy maximumSize="100"/>
  </subscriptionRecoveryPolicy>
</policyEntry>
```

The problems they solve and the trade-offs: both features bridge the gap between **event semantics** (every message matters, the default) and **state semantics** (only the current value matters) — without forcing you to bolt a database or a Kafka-style compacted topic onto your broker. Last-value queues are essentially **broker-side log compaction keyed by an attribute**, and the LastImage recovery policy is the topic equivalent. The caveats: last-value queues change delivery semantics (intermediate values are *intentionally lost* — never use them where every event must be processed, e.g. financial transactions); and retroactive/recovery policies hold extra messages in memory/store, so the retained window is a memory cost you bound deliberately. The senior framing: reach for these when the consumer genuinely wants **"the current state"** rather than **"the full history"** — they turn the broker into a lightweight state cache and elegantly solve the slow-consumer-replaying-stale-data and the late-joiner-needs-context problems that otherwise drive people to misuse Kafka or add a cache layer.

### 🔴 Expert — extended

#### Q66. [Theory] Explain how a broker plugin/interceptor works in Artemis, and give a concrete production use.

Artemis exposes an **interceptor / broker-plugin** extension model that lets you run custom code at well-defined points in the message lifecycle **inside the broker**, without forking it. There are two related mechanisms. **Protocol interceptors** (`Interceptor` for incoming/outgoing packets) sit at the wire level and can inspect or reject packets as they flow through an acceptor — useful for low-level protocol enforcement. The richer mechanism is the **`ActiveMQServerPlugin`** interface, which provides high-level callbacks for broker events: `beforeSend`/`afterSend`, `beforeMessageRoute`/`afterMessageRoute`, `beforeDeliver`/`afterDeliver`, `beforeCreateQueue`, connection created/destroyed, `messageAcknowledged`, `messageExpired`, and more. You implement only the hooks you need and register the plugin in `broker.xml`.

```java
import org.apache.activemq.artemis.core.server.plugin.ActiveMQServerMessagePlugin;
import org.apache.activemq.artemis.core.server.*;
import org.apache.activemq.artemis.api.core.RoutingType;

public class TenantStampPlugin implements ActiveMQServerMessagePlugin {
    @Override
    public void beforeSend(ServerSession session, /*tx*/ Object tx,
                           org.apache.activemq.artemis.api.core.Message message,
                           boolean direct, boolean noAutoCreateQueue) {
        // Stamp/validate every inbound message centrally, at the broker.
        if (message.getStringProperty("tenantId") == null) {
            // reject untenanted messages broker-side — no client can bypass this
            throw new RuntimeException("tenantId required");
        }
        message.putLongProperty("brokerIngestTs", System.currentTimeMillis());
    }
}
```

```xml
<!-- broker.xml -->
<broker-plugins>
  <broker-plugin class-name="com.acme.TenantStampPlugin"/>
</broker-plugins>
```

A concrete production use I'd actually ship: **centralized, tamper-proof message governance and audit**. Putting enrichment/validation in a `beforeSend`/`beforeMessageRoute` plugin means *every* message — regardless of which client, language, or protocol produced it — is uniformly stamped (broker ingest timestamp, trace id), validated (mandatory headers like `tenantId` or schema version present), or routed (multi-tenant isolation, attaching messages to per-tenant addresses), with **no way for a client to bypass it** because it runs in the broker, not the client library. Other strong uses: an **audit plugin** that records every `messageAcknowledged`/`messageExpired`/DLQ event to an external sink (a cleaner, lower-overhead alternative to advisory-topic subscriptions, Q42), or a **metrics plugin** emitting fine-grained per-destination latency.

The trade-offs and cautions are serious because this code runs **in the broker's hot path**. A slow or blocking plugin (a synchronous DB call in `beforeSend`) throttles *every* message and can stall the broker — plugin code must be fast, non-blocking, and exception-safe (an unhandled exception can reject messages or destabilize routing). It's also a **deployment coupling**: the plugin jar must be on the broker classpath and versioned with the broker, so you've taken on operating custom code inside critical infrastructure. The senior judgement: reach for a broker plugin when you need a **cross-cutting guarantee that must not be bypassable client-side** (governance, multi-tenant isolation, uniform audit) and the logic is genuinely lightweight; for anything heavyweight or business-specific, do it in a client-side interceptor or a dedicated processing service instead — the broker is shared, critical infrastructure, and every line of custom code in its hot path is shared, critical risk.

#### Q67. [Practical] A slow consumer is degrading a topic for all subscribers. How does ActiveMQ detect and handle slow consumers, and how do you configure it?

On a **topic**, every subscriber gets a copy of each message, which creates a specific failure mode: one slow or stuck subscriber causes the broker to **retain messages it can't yet deliver to that subscriber**, inflating memory/store and — if the slow subscriber shares resources — degrading delivery to the *healthy* subscribers too. ActiveMQ Classic addresses this with **slow-consumer detection and an abort policy**: the broker monitors how far behind each subscriber is (pending message count / dispatch lag) and can **forcibly disconnect (abort) a consumer that falls too far behind**, freeing the resources it was pinning so the rest of the system stays healthy. The philosophy is deliberate: in a fan-out system it can be better to **sacrifice the one broken subscriber** than to let it drag down everyone.

```xml
<!-- activemq.xml (Classic): abort consumers that lag too far behind on a topic -->
<policyEntry topic="events.>" >
  <slowConsumerStrategy>
    <abortSlowConsumerStrategy abortConnection="false"
                               maxSlowDuration="30000"      <!-- 30s behind -->
                               maxSlowCount="-1"
                               checkPeriod="5000"/>
  </slowConsumerStrategy>
  <!-- bound how many messages we'll hold for a lagging non-durable subscriber -->
  <pendingMessageLimitStrategy>
    <constantPendingMessageLimitStrategy limit="2000"/>
  </pendingMessageLimitStrategy>
</policyEntry>
```

There are two complementary levers. The **`pendingMessageLimitStrategy`** (`constantPendingMessageLimitStrategy` or `prefetchRatePendingMessageLimitStrategy`) bounds how many messages the broker will hold for a **non-durable** topic subscriber that's falling behind — once exceeded, the broker **discards old messages** for that subscriber (acceptable precisely because it's non-durable / non-guaranteed). The **`abortSlowConsumerStrategy`** goes further and disconnects the offending consumer entirely (optionally tearing down its whole connection with `abortConnection="true"`). Slow-consumer **advisory events** (`ActiveMQ.Advisory.SlowConsumer.*`, Q42) let you observe and alert on this without waiting for an abort.

The decisions and caveats: this is fundamentally a **non-durable topic** mitigation — for **durable** subscriptions and **queues** you generally *don't* want to silently discard or forcibly abort, because the whole point is guaranteed delivery; there the right answer is the backlog/paging/PFC machinery (Q33, Q59, Q61) plus fixing or scaling the consumer, not discarding its data. Aborting a slow consumer also just *moves* the problem if that consumer immediately reconnects and falls behind again — so abort is a pressure-relief valve, not a fix; the durable fix is making the consumer fast enough (scale, lower per-message work, lower prefetch) or accepting that this subscriber needs queue-with-backlog semantics instead of live topic semantics. The senior framing: slow-consumer abort/discard is the broker protecting the *many* from the *one* on best-effort fan-out — configure it for non-durable, observability-style topics where lagging data is worthless anyway, and never reach for it as a substitute for capacity-planning a guaranteed-delivery path.

#### Q68. [Coding] Implement a STOMP producer/consumer (e.g. a lightweight or web client) against ActiveMQ and explain when STOMP is the right choice.

**Problem:** Many clients (web frontends via WebSocket, scripting languages, embedded/IoT-adjacent tools) can't or shouldn't pull in a heavy JMS/JAR client. Show producing and consuming over **STOMP**, the simple text protocol ActiveMQ exposes (port 61613, or over WebSocket), and explain the trade-offs.

```python
# Python consumer/producer using stomp.py against ActiveMQ's STOMP acceptor.
import stomp, time

class Listener(stomp.ConnectionListener):
    def on_message(self, frame):
        print("Received:", frame.body)
        # With client-ack, acknowledge explicitly so the broker removes it.
        conn.ack(frame.headers["message-id"], frame.headers["subscription"])

conn = stomp.Connection([("localhost", 61613)])
conn.set_listener("", Listener())
conn.connect("admin", "admin", wait=True)

# Subscribe with client-individual ack (explicit, per-message acknowledgement)
conn.subscribe(destination="/queue/orders.in", id="sub-1", ack="client-individual")

# Produce a message (persistent so it survives broker restart)
conn.send(destination="/queue/orders.in", body="order-42",
          headers={"persistent": "true"})

time.sleep(2)
conn.disconnect()
```

```javascript
// Browser/Node via STOMP-over-WebSocket (e.g. @stomp/stompjs) — JMS in a web app
import { Client } from "@stomp/stompjs";
const client = new Client({
  brokerURL: "ws://localhost:61614/stomp",         // ActiveMQ WS+STOMP acceptor
  connectHeaders: { login: "admin", passcode: "admin" },
  onConnect: () => {
    client.subscribe("/topic/prices", (msg) => console.log("tick:", msg.body),
                     { ack: "auto" });
    client.publish({ destination: "/topic/prices", body: "AAPL 187.42" });
  },
});
client.activate();
```

**Mapping and semantics:** STOMP destinations use a textual convention — `/queue/NAME` and `/topic/NAME` — which the broker maps to real queues/topics, so a STOMP producer and a JMS consumer interoperate on the *same* destination (one of STOMP's best properties: it's a thin client onto the same broker, fully interoperable with JMS/AMQP clients). Acknowledgement is via the `ack` header: `auto` (broker assumes delivery on send), `client` (cumulative — acks this and all prior), and `client-individual` (per-message, the safe choice for reliable processing). Persistence and other JMS-ish features are set via headers (`persistent`, `expires`, `priority`, custom properties just become frame headers).

**When STOMP is the right choice:** STOMP shines for **lightweight, polyglot, or browser clients** where a full JMS/AMQP stack is overkill or impossible — a web dashboard subscribing to live updates over WebSocket, a shell script or Python tool that needs to drop a message on a queue, an embedded device, or quick operational tooling. It's trivially implementable (a human-readable, line-oriented protocol) and interoperates with your JMS apps on shared destinations. **When it's the wrong choice / cautions:** STOMP has **no built-in flow control** beyond TCP backpressure and ack/receipt frames (Q44), so a naive high-throughput STOMP producer can overwhelm itself or the broker; it lacks the rich features and efficiency of the Core/AMQP protocols; and treating untrusted STOMP clients (especially from browsers) as authoritative is a **security concern** — validate/sanitize their input and lock down authorization (Q49), because a browser-reachable STOMP-over-WS endpoint is a public-facing entry point. The senior rule: use **STOMP for the edge** (web, scripts, light/polyglot clients) and the **Core/AMQP protocol for high-throughput backend services**, all interoperating on the same destinations — but never let STOMP's simplicity lull you into skipping flow-control and authorization thinking on the clients that use it.

#### Q69. [Theory] How does message redelivery and the redelivery counter actually work across consumer crashes, transaction rollbacks, and broker restarts?

Redelivery is more subtle than "retry N times," and the details matter because they determine when a message reaches the DLQ, whether the retry count survives a restart, and whether redelivery delays are enforced client-side or broker-side. The fundamental rule: a persistent message is removed from the queue only when it is **acknowledged**. Anything that prevents an ack — a consumer crash, a transacted session rolled back, a `recover()` call, an explicit `session.rollback()`, or exceeding a transaction — leaves the message **un-acked**, so the broker **redelivers** it. On redelivery, the broker sets the `JMSRedelivered` header to `true` and increments a **redelivery counter** so the redelivery policy can eventually give up and dead-letter the message.

```
Lifecycle of a failing message (max-redeliveries = 3):
  deliver (count 0) ─► consumer throws/rolls back ─► redeliver (count 1, JMSRedelivered=true)
                    ─► fails ─► redeliver (count 2) ─► fails ─► redeliver (count 3)
                    ─► STILL fails ─► exceeded → route to DLQ (with original headers + reason)
```

The crucial distinction is **where the redelivery counter lives**, because it determines crash-survival. In **Classic**, the redelivery *delay* and much of the retry bookkeeping are **client-side** (in the `ActiveMQConnectionFactory`'s `RedeliveryPolicy`) — which means if the **consumer process crashes**, the in-client redelivery count is lost; the message simply becomes un-dispatched again and, when redelivered to a *new* consumer, the count effectively resets (the broker tracks delivery attempts via the message's `redeliveryCounter`, but the backoff-delay machinery is client-side). So a poison message that crashes the JVM each time may *never* reach the DLQ under pure client-side redelivery, because it keeps getting freshly redelivered. The robust fix is broker-side handling: Classic's **`redeliveryPlugin`** (a broker plugin) moves redelivery scheduling into the broker so the count and backoff survive consumer crashes, and **Artemis tracks redelivery on the broker** natively via `address-settings` (`max-delivery-attempts`, `redelivery-delay`) — so the count persists across consumer crashes *and* (because it's in the message/journal) across **broker restarts**.

```xml
<!-- Artemis: broker-side redelivery — survives consumer crash AND broker restart -->
<address-setting match="orders">
  <max-delivery-attempts>5</max-delivery-attempts>
  <redelivery-delay>2000</redelivery-delay>
  <redelivery-delay-multiplier>2.0</redelivery-delay-multiplier>
  <max-redelivery-delay>60000</max-redelivery-delay>
  <dead-letter-address>DLQ</dead-letter-address>
</address-setting>
```

The behaviors to reason about: a **transaction rollback** redelivers the *entire* batch of messages consumed in that transaction (not just the one that failed), so a poison message can cause repeated redelivery of its innocent batch-mates — a reason to keep transacted batches small or process one-at-a-time for poison-prone streams. A **broker restart** in Classic with client-side redelivery resets in-flight client counters, whereas Artemis's persisted counter resumes. And **redelivery interleaving reorders** the stream (Q51) because a delayed redelivery re-enters behind newer messages. The senior takeaways: prefer **broker-side redelivery** (Artemis native, or Classic's `redeliveryPlugin`) for any poison-resistant guarantee, because client-side-only redelivery silently fails to dead-letter JVM-crashing poison messages; keep transacted consume batches small; and **always pair redelivery with a DLQ + monitoring** (Q26, Q47) so the messages that exhaust retries are caught and replayable rather than lost or looping forever.

#### Q70. [Practical] Walk through capacity-planning and tuning the Artemis journal (type, sync, buffer) for a target throughput and durability SLA.

Journal tuning is where Artemis's durability SLA meets physics, and the levers interact in ways that punish cargo-culting. The journal is an **append-only log**; every persistent operation (send, ack, commit) appends a record, and the durability question is **when those appends are flushed to stable storage**. The three primary knobs are **journal type** (`ASYNCIO` via Linux libaio, `NIO` portable Java, or `MAPPED`), **sync policy** (`journal-sync-transactional` / `journal-sync-non-transactional` — whether each commit/append forces an fsync), and the **buffering** (`journal-buffer-timeout` and `journal-buffer-size`, which control group-commit batching).

```xml
<!-- broker.xml: a balanced high-throughput, durable config on Linux SSD -->
<core>
  <journal-type>ASYNCIO</journal-type>          <!-- libaio: best throughput on Linux -->
  <journal-sync-transactional>true</journal-sync-transactional>
  <journal-sync-non-transactional>true</journal-sync-non-transactional>
  <journal-buffer-timeout>20000</journal-buffer-timeout>  <!-- ns: group-commit window -->
  <journal-file-size>10485760</journal-file-size>          <!-- 10MB per journal file -->
  <journal-min-files>10</journal-min-files>
  <journal-max-io>4096</journal-max-io>          <!-- ASYNCIO queue depth -->
</core>
```

The mechanism that makes high throughput *and* durability coexist is **group commit**, governed by `journal-buffer-timeout`. Instead of issuing one fsync per message (which caps you at the disk's fsync rate — maybe a few thousand/sec on a spinning disk, more on SSD/NVMe), Artemis **batches** all the appends that arrive within the buffer-timeout window and fsyncs them **together**, then acks all those producers at once. So the timeout is a **latency-vs-throughput dial**: a longer window batches more (higher throughput, higher per-message latency), a shorter window acks faster (lower latency, more fsyncs, lower ceiling). `ASYNCIO` (libaio) lets the broker keep many I/O operations in flight (`journal-max-io`) so the fsync of one batch overlaps the construction of the next — this is why ASYNCIO substantially outperforms NIO on Linux and is the production default there; `NIO` is the portable fallback (Windows/macOS, or non-libaio environments), and `MAPPED` (memory-mapped) is an option for certain workloads.

The capacity-planning method I follow: (1) **fix the durability SLA first** — "no acknowledged message may be lost on power failure" forces `journal-sync-*=true` (every commit fsynced); a softer SLA ("may lose the last few ms of telemetry on a hard crash") permits relaxing sync or accepting a larger buffer window, trading a small loss window for big throughput. (2) **Benchmark on the actual disk** with `artemis perf` (Q38) and the real message size and persistent ratio, because the journal ceiling is **disk-fsync-bound**, not CPU-bound — a slow networked disk or a cloud volume with poor fsync latency will cap you regardless of config (and is the #1 cause of "Artemis is slow" tickets). (3) **Tune `journal-buffer-timeout` against measured latency**: start at the default, raise it while throughput climbs and per-message latency stays within SLA, stop when latency breaches. (4) **Size journal files and min-files** so compaction/cleanup (Q39-analogue) isn't constantly churning, and ensure the journal lives on **dedicated low-latency storage** separate from paging if possible.

The trade-off table I keep in my head: `ASYNCIO + sync=true + tuned buffer-timeout` is the sweet spot for "durable and fast on Linux"; `sync=false` buys throughput at the cost of a crash-loss window (only for genuinely disposable data); `NIO` is for portability not performance; and **no journal config rescues slow storage** — if the fsync latency is high, fix the disk (local NVMe over networked volumes for the journal) before touching XML. The senior framing: journal tuning is **matching the group-commit batching and sync policy to a *stated* durability SLA on *measured* hardware** — never tune for throughput without first writing down exactly how much data you're allowed to lose on a power cut, because that number, not a benchmark, dictates the sync policy.

#### Q71. [Theory] What is the difference between `useAsyncSend`, synchronous sends, and transacted sends — and how does each affect durability and throughput?

These three send modes sit on a spectrum trading **throughput against the strength of the producer's delivery guarantee**, and confusing them is a common cause of silent message loss. A **synchronous send** (the default for persistent messages in Classic) means `producer.send()` **blocks until the broker has persisted the message and returned an acknowledgement** — when the call returns, you *know* the broker has the message durably. An **async send** (`useAsyncSend=true`, or the JMS 2.0 `CompletionListener` API) means `send()` returns **immediately** after handing the message to the transport, *before* the broker confirms — far higher throughput (no round-trip per message), but a failure (broker rejects it, connection drops, flow control) is reported **asynchronously or not at all on the calling thread**, so a naive caller can believe a send succeeded that actually failed. A **transacted send** batches multiple sends and makes them atomic: nothing is visible to consumers until `commit()`, and a `rollback()` discards the whole batch.

```java
// Synchronous (default for persistent): send() returns only after broker ack — safest
producer.send(queue, msg);                          // blocks; on return, broker has it

// Async (Classic flag): fire-and-forget transport handoff — fastest, weakest guarantee
((ActiveMQConnectionFactory) factory).setUseAsyncSend(true);
producer.send(queue, msg);                          // returns before broker confirms

// JMS 2.0 async with completion callback — async speed WITH failure visibility
ctx.createProducer().setAsync(new CompletionListener() {
    public void onCompletion(Message m) { /* broker confirmed */ }
    public void onException(Message m, Exception e) { /* handle the failure! */ }
}).send(queue, msg);

// Transacted: atomic batch — all-or-nothing, visible only on commit
JMSContext tx = factory.createContext(JMSContext.SESSION_TRANSACTED);
tx.createProducer().send(queue, m1);
tx.createProducer().send(queue, m2);
tx.commit();                                        // both appear, or neither
```

The durability and throughput implications, which are the crux: **synchronous persistent sends** give the strongest single-message guarantee (the broker fsynced it before you continued) but are **bounded by round-trip + fsync latency**, so a single-threaded synchronous persistent producer is slow — this is correct for "every message is money." **Async sends** dramatically increase throughput because the producer pipelines many sends without waiting, but they **weaken the guarantee**: with the bare `useAsyncSend=true` flag (no completion listener), a send failure may be swallowed, so it's only safe for non-persistent/disposable data *or* when paired with the JMS 2.0 `CompletionListener` so you actually observe failures. Interestingly, **non-persistent sends are async by default** (no durability to wait for), while **persistent sends are sync by default** (you wait for the fsync ack) — `useAsyncSend=true` overrides that to make even persistent sends async, which is the dangerous footgun: people enable it for speed and silently lose persistent messages on failure they never noticed.

The senior decision framework: use **synchronous persistent sends** for critical, must-not-lose-silently paths (payments, orders) where the round-trip cost is acceptable; use **async sends with a `CompletionListener`** when you need high throughput on important data *and* are willing to handle failures in the callback (the right way to go fast without going blind); use **bare `useAsyncSend`** only for genuinely disposable, non-persistent, high-volume data (telemetry, metrics) where occasional silent loss is fine; and use **transacted sends** when a group of messages must appear atomically (e.g. don't publish the "order created" event until the whole batch is ready) or to tie sends into a larger unit of work. The trap I flag in reviews: someone sets `useAsyncSend=true` on a persistent payments producer "to improve throughput," turning every ambiguous network blip into an invisible lost payment — async speed without a completion listener is throughput bought with silent data loss.

#### Q72. [Practical] You must migrate a live, high-volume queue to a new broker with zero message loss and minimal downtime. Design the cutover.

This is the operational sibling of the Q20 migration question, focused specifically on the **live cutover mechanics** for a single high-volume queue where you cannot afford to lose a message or stop the world. The core principle is **drain-then-switch with overlap**, never a hard flip — you keep the old broker accepting and draining while you bring the new one online, then shift producers and consumers in a controlled order so that at no instant is a message both un-drained from the old broker and un-acceptable on the new one.

```
Cutover sequence (old broker O, new broker N, queue "orders.in"):
 1. Stand up N; clients already use failover:(O,N) OR are config-flag-switchable.
 2. Bridge: configure a one-way forward O.orders.in ──► N.orders.in (Artemis core
    bridge / Classic network connector). Now anything on O flows to N.
 3. Switch PRODUCERS to N (flag/DNS). New messages now land on N.
 4. Let CONSUMERS keep draining O until O.orders.in depth hits 0 (the bridge also
    pushes O's backlog to N). Watch EnqueueCount=DequeueCount, depth→0.
 5. Switch CONSUMERS to N. They now drain N (which has both forwarded + new msgs).
 6. Verify O fully empty (incl. in-flight, scheduled, DLQ), then decommission O.
```

The design decisions that make this safe: **producers switch before consumers**, so new traffic immediately accumulates on N while old consumers finish O — this guarantees no message is produced to a broker that nothing will drain. A **one-way bridge from O to N** (Q64) sweeps O's existing backlog over to N so you don't have to wait for O's consumers to drain everything; alternatively, you let O's consumers drain O naturally and only switch consumers once depth is zero (slower but bridge-free). **Idempotent consumers (Q15, Q28) are non-negotiable** here, because the overlap window and any bridge re-forwarding can produce duplicates — the dedup/inbox table is what lets you cut over aggressively without fear. **`failover:` URIs listing both brokers** (or a feature flag / DNS switch) make the producer/consumer redirection a config change, not a redeploy, so you can move fast and roll back instantly.

The things people forget, which cause the "we lost messages in the migration" post-mortem: **scheduled/delayed messages** (Q56) live in a separate scheduler store and are *not* swept by a normal queue bridge — you must explicitly migrate or wait them out. **The DLQ** must be migrated or drained too (a non-empty old DLQ is real failed business events). **In-flight/un-acked messages** on O at switchover redeliver to O's remaining consumers — don't decommission O until `InFlightCount` and depth are both truly zero, not just "looks empty." **Ordering** (Q51) can be disrupted during overlap (some messages drain from O, newer ones from N) — if strict per-key order matters, use message-group affinity and accept a brief reorder risk at the boundary, or quiesce producers momentarily for that key. And **wire/version compatibility** — if O and N can't bridge directly (incompatible protocols/versions), you bridge via a protocol both speak (OpenWire/AMQP) or via a small relay app.

My actual playbook: rehearse the entire sequence in staging under representative load (including a forced rollback mid-cutover), script the producer/consumer switch as a single flag flip, build a **verification step** that reconciles message counts/ids between O and N (proving zero loss, not assuming it), keep O running read-only as the rollback path for a defined window after cutover, and only decommission once N has run clean through a peak cycle. The senior framing: zero-loss live migration is **producers-first, drain-with-overlap, idempotent-consumers, reconcile-don't-assume** — the failure mode is always the thing that lives *outside* the main queue (scheduled messages, DLQ, in-flight, ordering) that a naive "just point everything at the new broker" flip silently strands.

#### Q73. [Theory] How do JMS sessions, connections, and threads relate, and what are the concurrency rules a correct client must obey?

Getting the JMS concurrency model right is foundational, because the spec makes specific thread-safety guarantees and violating them produces intermittent, hard-to-reproduce corruption that looks like broker bugs but is client misuse. The hierarchy is: a **`Connection`** is a heavyweight, thread-safe object representing a physical link to the broker (TCP socket + authentication); a **`Session`** is a lightweight, **single-threaded context** created from a connection that provides a transaction/acknowledgement scope and produces `MessageProducer`/`MessageConsumer` objects; and producers/consumers belong to their session. The headline rule from the JMS spec: **a `Connection` is safe for concurrent use by multiple threads, but a `Session` and its child producers/consumers are NOT** — a session is intended to be used by **one thread at a time**.

```
Connection (thread-safe, share freely)
   ├─ Session A  (single-threaded → use on ONE thread only)
   │     ├─ MessageProducer
   │     └─ MessageConsumer  (its onMessage runs serially on the session's thread)
   └─ Session B  (single-threaded → a SEPARATE thread for parallelism)
         └─ MessageConsumer

Rule: parallelism = MORE SESSIONS, not sharing one session across threads.
```

This single-threaded-session rule drives correct design. To process messages **concurrently**, you do **not** share one session across worker threads — you create **one session per thread** (each from the same shared connection), so each thread has its own ack/transaction scope and its own serial delivery. This is exactly what Spring's `DefaultMessageListenerContainer` does internally when you set `concurrency`: N sessions, each single-threaded. A second rule: a `MessageListener`'s `onMessage` is invoked **serially on the session's single delivery thread**, so within one session your listener never runs concurrently — but that also means a slow `onMessage` blocks all delivery on that session, which is why you scale with multiple sessions rather than doing async work *inside* one onMessage. A third subtlety: the JMS 2.0 `JMSContext` bundles a connection+session into one object for ergonomics, and the same rule applies — a `JMSContext` is single-threaded; for concurrency, create multiple contexts (they can share an underlying connection).

The concrete failure modes from violating these rules: sharing a `Session` or `MessageProducer` across threads causes **interleaved writes on the wire**, corrupted state, `ConcurrentModificationException`-style errors deep in the client library, lost or duplicated acks, and transaction boundaries that mix work from different threads — all intermittent and load-dependent, so they pass tests and fail in production. Other rules a correct client obeys: **close sessions/consumers/connections** (they hold broker-side resources — leaks show up as climbing connection/session counts, Q34); don't call `Connection.stop()`/`close()` from within an `onMessage` on a delivery thread of that connection (deadlock risk); and remember `Session.commit()`/`rollback()` affect **all** work on that session, so don't co-mingle unrelated units of work in one session. The senior framing: the JMS threading contract is **"share the connection, never share the session; scale by adding sessions, not threads-per-session"** — and virtually every "the JMS client is flaky under load" bug I've debugged traced back to a single session (or `JMSContext`, or `JmsTemplate` misuse) being hammered by multiple threads against the spec.

#### Q74. [Practical] How do you enforce multi-tenancy and resource isolation on a shared ActiveMQ broker so one tenant can't starve others?

Running one broker for many tenants (teams, customers, services) is economical but dangerous, because ActiveMQ's resource budgets (`systemUsage`, store, memory) are **broker-wide by default** — so one tenant's runaway backlog or message storm can trigger Producer Flow Control or fill the store for *everyone* (the "noisy neighbor" problem, foreshadowed in Q33). Isolation has to be **designed in across four dimensions**: namespacing, authorization, resource quotas, and observability.

**Namespacing** comes first: give each tenant a destination prefix (`tenantA.orders.in`, `tenantB.events.>`) using the hierarchical naming scheme (Q57), so every tenant's destinations are identifiable and addressable as a group by wildcard. **Authorization** then locks tenants into their namespace — per-destination security settings (Q22/Q49) so `tenantA`'s credentials can only send/consume/create under `tenantA.>` and cannot touch `tenantB.>` or system/DLQ destinations. This prevents both accidental cross-talk and malicious access.

```xml
<!-- Artemis broker.xml: per-tenant quota + authorization via address-settings + security -->
<address-settings>
  <address-setting match="tenantA.#">
    <max-size-bytes>200MB</max-size-bytes>          <!-- per-tenant memory budget -->
    <address-full-policy>PAGE</address-full-policy>  <!-- tenant overflow pages, not blocks all -->
    <max-delivery-attempts>5</max-delivery-attempts>
    <dead-letter-address>tenantA.DLQ</dead-letter-address>
  </address-setting>
  <address-setting match="tenantB.#">
    <max-size-bytes>200MB</max-size-bytes>
    <address-full-policy>PAGE</address-full-policy>
  </address-setting>
</address-settings>
<security-settings>
  <security-setting match="tenantA.#">
    <permission type="send"    roles="tenantA"/>
    <permission type="consume" roles="tenantA"/>
    <permission type="createNonDurableQueue" roles="tenantA"/>
  </security-setting>
</security-settings>
```

**Resource quotas** are the mechanism that actually delivers isolation, and Artemis's **per-address `max-size-bytes` + `address-full-policy=PAGE`** is the key tool: each tenant's addresses get a bounded memory budget, and when a tenant exceeds it, *that tenant's* addresses page to disk (or block/drop per policy) **without** consuming the shared memory budget that other tenants rely on. You also bound the **global** budget (`global-max-size`) and ensure the sum of tenant budgets is planned against it. In Classic, the analog is per-`policyEntry` `memoryLimit` plus per-destination flow control (Q33, Q59). Critically, **per-tenant DLQs and expiry/TTL** stop one tenant's poison/backlog from filling shared dead-letter space. **Observability** closes the loop: per-tenant metrics (queue depth, usage %, DLQ depth, Q47) tagged by namespace so you can see *which* tenant is misbehaving and alert/throttle them specifically.

The honest senior caveat: per-destination quotas give **soft isolation**, not the **hard isolation** of separate brokers. A single broker still shares one JVM heap, one set of journal/disk I/O, one thread pool, and one network stack — so a tenant can still cause **CPU/GC/disk-IO contention** (a flood of tiny messages, huge payloads, or pathological selector use) that no `max-size-bytes` setting fully contains. For tenants with strict isolation/compliance/SLA requirements, the right answer is **broker-per-tenant** (or per-tenant broker cluster), accepting the higher operational cost for true blast-radius containment. The decision framework I use: shared broker with namespacing + authorization + per-address quotas + per-tenant observability for **cooperative, similar-scale internal tenants**; dedicated brokers for **untrusted, regulated, or wildly-asymmetric tenants** where one could realistically saturate shared CPU/IO. Multi-tenancy on a broker is a **blast-radius** decision: per-address quotas shrink the blast radius of a memory/store overrun, but only physical separation contains a CPU/IO/GC overrun — choose the level of isolation that matches the worst tenant you'll actually host.

#### Q75. [Theory] Explain end-to-end how a persistent message flows through Artemis from `send()` to consumer ack, naming each component it touches.

Tracing the full path of a single persistent message is the integrative question that ties together routing, journaling, paging, dispatch, and acknowledgement — and being able to narrate it cleanly demonstrates that you understand the broker as a system rather than a black box. I'll follow one persistent message on an ANYCAST address from the producer's `send()` to the consumer's ack.

```
Producer.send() ─► [acceptor: protocol decode] ─► [security check] ─► [server session]
   ─► [address + routing-type resolution] ─► [routing to bound queue(s)]
   ─► [journal append + (group-commit) fsync] ─► broker ACKs the producer's send
   ─► message in queue (memory, or PAGED to disk if address over max-size-bytes)
   ─► [dispatch to a consumer per its credit/prefetch] ─► consumer.onMessage / receive
   ─► consumer ACK ─► [journal append: ack record] ─► message marked deletable
   ─► [later: journal compaction reclaims fully-acked journal files]
```

Step by step: the producer's `send()` arrives at an **acceptor** (the configured transport endpoint for OpenWire/Core/AMQP/STOMP), which **decodes** the protocol into the broker's internal message representation. The broker performs a **security/authorization check** (can this principal send to this address?). The work is attached to a **server session** (the broker-side counterpart of the client session, carrying transaction context). The broker then resolves the **address** and its **routing type** — for ANYCAST it will route the message to **one** of the queues bound to the address; for MULTICAST it would copy to **every** bound queue (Q35). Routing produces a reference in the target **queue**.

Because the message is **persistent**, the broker **appends a record to the journal** and — depending on `journal-sync-*` and `journal-buffer-timeout` (Q70) — fsyncs it (often via **group commit**, batching with other concurrent sends) before **acknowledging the producer's `send()`**: this is the durability boundary; when `send()` returns, the message is on stable storage. The message reference now sits in the queue, held **in memory** for fast dispatch, *unless* the address has exceeded `max-size-bytes`, in which case it's **paged to disk** (Q61) and read back when consumers catch up. The broker **dispatches** the message to a consumer subject to that consumer's flow-control window (OpenWire prefetch, AMQP link credit, Q44) — for ANYCAST, competing consumers each get distinct messages. The client library hands it to `onMessage`/`receive`.

Finally the consumer **acknowledges** (auto-ack after onMessage, client-ack explicitly, or transaction commit). The ack travels back to the broker, which **appends an ack record to the journal** — note the message isn't physically erased in place; the ack marks the original message reference as consumed. Only when **every** message reference in a journal file has been acked does that file become eligible for **compaction/cleanup** (the Artemis analog of KahaDB's whole-file reclamation, Q39) — which is why one un-acked message can pin journal space. The expert framing this question rewards: the durability guarantee lives at the **journal-append-before-producer-ack** boundary; memory vs disk residence is governed by **paging**; delivery pacing is governed by **per-protocol flow control**; and reclamation is **ack-driven, whole-file compaction** — so message *loss* requires a failure before the journal fsync, message *duplication* requires an ack that didn't durably register (hence idempotent consumers), and store *growth* is an un-acked-reference problem, not a volume problem. That single mental model explains the answers to half the other questions in this guide.

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
