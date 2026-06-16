# Apache Kafka — Interview Preparation Guide

Apache Kafka is a distributed, append-only commit log that powers real-time data pipelines, event streaming, and event-driven microservices at internet scale. This guide goes deep — from offsets and ISR to exactly-once semantics, KRaft, and the production fires you will actually be paged for.

[← Back to master index](../README.md)

## Table of Contents

- [🟢 Basic (0–2 yrs)](#-basic-02-yrs)
- [🟡 Intermediate (3–7 yrs)](#-intermediate-37-yrs)
- [🟠 Advanced (8–12 yrs)](#-advanced-812-yrs)
- [🔴 Expert (15+ yrs)](#-expert-15-yrs)

---

## 🟢 Basic (0–2 yrs)

### Q1. [Theory] What is Apache Kafka, and what problem does it solve?

Kafka is a distributed **commit log**: producers append immutable records to ordered, partitioned logs called topics, and consumers read them at their own pace by tracking an offset. Unlike a traditional message queue (RabbitMQ, ActiveMQ) where a broker pushes a message and deletes it once acknowledged, Kafka **retains** messages for a configured time/size regardless of consumption, so many independent consumers can replay the same data. This decouples producers from consumers in time and scale: a slow batch job and a real-time dashboard can read the same topic without affecting each other. Kafka solves the "N producers × M consumers" integration explosion by acting as a central, durable, high-throughput buffer. Its design favors sequential disk I/O and zero-copy transfer, which is why a single broker can sustain hundreds of MB/s.

### Q2. [Theory] Explain topics, partitions, and offsets.

A **topic** is a named logical stream (e.g. `orders`). Each topic is split into one or more **partitions**, which are the unit of parallelism and ordering. A partition is an ordered, immutable sequence of records; each record gets a monotonically increasing **offset** (0, 1, 2, …) that uniquely identifies it within that partition. Ordering is guaranteed only *within* a partition, never across partitions. The number of partitions caps consumer parallelism within a single consumer group — you can never have more active consumers than partitions. Choosing partition count is a foundational design decision because you can increase but not easily decrease it.

```
Topic: orders  (3 partitions)

Partition 0:  [0][1][2][3][4]            <- newest at the tail
Partition 1:  [0][1][2]
Partition 2:  [0][1][2][3][4][5][6]
                            ^ consumer offset (next record to read = 5)
```

### Q3. [Theory] What is a broker and what is a Kafka cluster?

A **broker** is a single Kafka server process that stores partition data on disk and serves produce/fetch requests. A **cluster** is a set of brokers that coordinate so that partitions and their replicas are distributed across machines for scalability and fault tolerance. Each partition has one broker acting as **leader** (handles all reads/writes) and zero or more **followers** that replicate it. One broker is elected the **controller**, responsible for cluster metadata — partition leadership, broker membership, and reassignment. Clients bootstrap by connecting to any broker, which returns metadata telling them which broker leads each partition, so they can route requests directly.

### Q4. [Practical] Write a minimal Kafka producer and consumer in Java.

**Problem:** Send and read a string message using the standard Java client.

```java
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import java.time.Duration;
import java.util.*;

public class Basic {
    static void produce() {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (Producer<String, String> producer = new KafkaProducer<>(p)) {
            // key = "cust-42" -> all records for this key land in the same partition
            producer.send(new ProducerRecord<>("orders", "cust-42", "order#1001"),
                (meta, ex) -> {
                    if (ex != null) ex.printStackTrace();
                    else System.out.printf("p=%d off=%d%n", meta.partition(), meta.offset());
                });
        } // close() flushes pending records
    }

    static void consume() {
        Properties c = new Properties();
        c.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        c.put(ConsumerConfig.GROUP_ID_CONFIG, "order-processors");
        c.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        c.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        c.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (Consumer<String, String> consumer = new KafkaConsumer<>(c)) {
            consumer.subscribe(List.of("orders"));
            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));
                for (ConsumerRecord<String, String> r : records) {
                    System.out.printf("k=%s v=%s p=%d off=%d%n",
                        r.key(), r.value(), r.partition(), r.offset());
                }
                consumer.commitSync(); // commit after processing this batch
            }
        }
    }
}
```

**Edge cases:** Always `close()` the producer (it flushes buffered batches); commit offsets only *after* successful processing to get at-least-once delivery; set `auto.offset.reset` so a brand-new group knows where to start (`earliest` for full replay, `latest` for live-only).

### Q5. [Theory] What is a consumer group and how does it enable scaling?

A consumer group is a set of consumers sharing a `group.id` that cooperatively consume a topic. Kafka assigns each partition to **exactly one** consumer in the group, so the group as a whole sees every record once while distributing load. Adding consumers (up to the partition count) increases throughput; beyond that count, extra consumers sit idle. Different groups are independent — each maintains its own offsets — so the same topic can feed a billing service, an analytics pipeline, and a search indexer simultaneously. Offsets are stored in the internal `__consumer_offsets` topic, making group progress durable and survivable across restarts.

### Q6. [Theory] What does `acks` do on the producer?

`acks` controls how many replicas must acknowledge a write before the producer considers it successful, trading durability for latency. `acks=0` is fire-and-forget (lowest latency, can silently lose data). `acks=1` waits only for the leader (data is lost if the leader crashes before followers replicate). `acks=all` (a.k.a. `-1`) waits for all **in-sync replicas** and, combined with `min.insync.replicas=2`, guarantees no committed message is lost as long as one in-sync replica survives. Production systems that care about data almost always use `acks=all`. The cost is higher latency because the write must propagate to replicas before acknowledgment.

### Q7. [Practical] How do you choose the number of partitions for a topic?

Start from your **throughput target** and **consumer parallelism**: estimate per-partition throughput (often 10–50 MB/s depending on hardware and record size) and divide your target by it, then make sure you have at least as many partitions as you want concurrent consumers. Account for future growth because increasing partitions later **breaks key-based ordering** (a key may move to a different partition). Avoid going wildly high — each partition costs file handles, memory, and replication overhead, and tens of thousands of partitions per broker slow controller failover and increase end-to-end latency. A common heuristic: target a few hundred to low thousands of partitions per broker total. In production I size for ~2x current peak and revisit, rather than over-provisioning to 1000 partitions "just in case."

---

## 🟡 Intermediate (3–7 yrs)

### Q8. [Theory] Explain replication, ISR, and the high-water mark.

Each partition is replicated across `replication.factor` brokers. One replica is the leader; the rest are followers that continuously fetch from the leader. The **In-Sync Replica (ISR)** set is the subset of replicas that are fully caught up (within `replica.lag.time.max.ms`). A record is **committed** only once all ISR members have it; the **high-water mark (HWM)** is the highest offset that is committed, and consumers can only read up to the HWM. This prevents a consumer from seeing data that could be lost if the leader fails. If a follower falls behind, it is dropped from the ISR; if it catches up, it rejoins. With `acks=all` + `min.insync.replicas=2` + `replication.factor=3`, you can lose one broker and still accept writes without data loss.

```
replication.factor = 3,  min.insync.replicas = 2

           Leader (B1)  off=10  ── HWM = 9  (B1,B2 have it; B3 lagging)
             │
   ┌─────────┼──────────┐
Follower B2 off=9      Follower B3 off=4   <- out of ISR (too far behind)
 (in ISR)               (not in ISR)

ISR = {B1, B2}   -> committed up to off=9, consumers read <= 9
```

### Q9. [Theory] What is producer idempotence and why does it matter?

Without idempotence, a producer retry after a network hiccup can write the same record twice (the broker persisted it but the ack was lost). The **idempotent producer** (`enable.idempotence=true`, default since Kafka 3.0) assigns each producer a Producer ID (PID) and a per-partition monotonic sequence number; the broker deduplicates by rejecting records whose sequence it has already seen. This guarantees **exactly-once delivery to a single partition within a single producer session**, eliminating duplicates from retries without sacrificing throughput. It requires `acks=all`, `max.in.flight.requests.per.connection<=5`, and `retries>0` — all set automatically when you enable it. It does *not* by itself give cross-partition or cross-session exactly-once; that needs transactions.

### Q10. [Practical] How does producer batching and compression affect throughput?

The producer accumulates records per partition into batches controlled by `batch.size` (bytes) and `linger.ms` (max wait before sending a partially full batch). A small `linger.ms` minimizes latency but produces tiny batches and lower throughput; raising it to 5–50ms lets batches fill, dramatically improving throughput and compression ratio at the cost of a little latency. **Compression** (`compression.type=lz4`, `zstd`, `snappy`, or `gzip`) is applied per batch — bigger batches compress better and cut network and disk usage. `zstd` gives the best ratio; `lz4`/`snappy` are faster with lower CPU. In production I typically use `linger.ms=10`, `batch.size=64KB`, `compression.type=zstd`, and size `buffer.memory` to absorb broker slowdowns. The compressed batch stays compressed all the way to disk and to the consumer (broker doesn't recompress), so compression also reduces broker storage.

### Q11. [Theory] Explain consumer group rebalancing and the rebalance protocols.

A rebalance reassigns partitions among group members and is triggered when a consumer joins/leaves, a consumer is deemed dead (missed heartbeats / `max.poll.interval.ms` exceeded), or partition count changes. The **group coordinator** (a broker) drives it. The classic **eager** protocol is "stop-the-world": every consumer revokes *all* its partitions, then they are reassigned — causing a processing pause proportional to group size. **Cooperative (incremental) rebalancing** (`CooperativeStickyAssignor`, default in newer clients) revokes only the partitions that actually need to move, so most consumers keep processing during a rebalance. **Static membership** (`group.instance.id`) lets a consumer that restarts quickly rejoin without triggering a rebalance at all (within `session.timeout.ms`), which is huge for rolling deploys.

### Q12. [Practical] A consumer keeps getting kicked out of the group mid-batch. Diagnose and fix.

This is almost always **`max.poll.interval.ms` exceeded**: processing a `poll()` batch takes longer than the allowed interval (default 5 min), so the coordinator assumes the consumer is dead and rebalances, and the offset commit then fails with a `CommitFailedException`. The heartbeat thread is separate from processing, so heartbeats alone passing doesn't help if processing stalls. Fixes, in order of preference: (1) reduce `max.poll.records` so each batch is smaller and faster; (2) speed up or parallelize per-record processing; (3) raise `max.poll.interval.ms` if the work is genuinely long; (4) offload slow work to a worker pool and use the pause/resume API so you keep polling. Avoid the lazy fix of just cranking the timeout sky-high — it masks the real latency and delays detection of truly dead consumers.

### Q13. [Coding] Implement a consumer with manual offset commits and graceful shutdown.

**Problem:** Process records at-least-once, commit offsets only after successful processing, and shut down cleanly without losing in-flight work.

```java
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import java.time.Duration;
import java.util.*;

public class SafeConsumer {
    private final KafkaConsumer<String, String> consumer;
    private volatile boolean running = true;

    SafeConsumer(KafkaConsumer<String, String> c) { this.consumer = c; }

    void run() {
        // From another thread (e.g. shutdown hook): consumer.wakeup();
        try {
            consumer.subscribe(List.of("orders"));
            while (running) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
                for (ConsumerRecord<String, String> r : records) {
                    process(r); // idempotent business logic
                    // commit offset = lastProcessed + 1
                    offsets.put(new TopicPartition(r.topic(), r.partition()),
                                new OffsetAndMetadata(r.offset() + 1));
                }
                if (!offsets.isEmpty()) consumer.commitSync(offsets);
            }
        } catch (WakeupException ignore) {
            // expected on shutdown
        } finally {
            try { consumer.commitSync(); } finally { consumer.close(); }
        }
    }

    void process(ConsumerRecord<String, String> r) { /* ... */ }
}
```

**Why offset + 1:** the committed offset is the *next* record to read, not the last one processed. **Edge cases:** a crash between `process()` and `commitSync()` reprocesses records → make `process()` idempotent (upsert by key, or dedupe on a record id). Use `wakeup()` (thread-safe) to break out of `poll()`; never call other consumer methods from another thread. **Complexity:** O(n) over records per poll; commit is O(p) over assigned partitions.

### Q14. [Theory] Compare log retention by time/size versus log compaction.

Standard **retention** deletes whole log segments once they exceed `retention.ms` or `retention.bytes` — good for event streams where old data has no value (clickstream, metrics). **Log compaction** (`cleanup.policy=compact`) instead guarantees Kafka retains *at least the last value for each key*, garbage-collecting superseded versions. Compaction turns a topic into a durable changelog / materialized table: replaying it reconstructs the latest state per key. It's the backbone of Kafka Streams state stores, `__consumer_offsets`, and database CDC topics. You can combine policies (`compact,delete`) to compact *and* eventually drop truly old keys. A subtle gotcha: compaction keeps the latest record per key including tombstones (null-value records that signal deletion), and tombstones themselves are retained for `delete.retention.ms` so downstream consumers can observe the delete.

```
cleanup.policy = compact

Before:  (k1,v1)(k2,v9)(k1,v2)(k3,v5)(k1,v3)(k2,vX)
After :              (k3,v5)(k1,v3)(k2,vX)   <- last value per key kept
```

### Q15. [Theory] What ordering guarantees does Kafka provide, and how do you preserve them?

Kafka guarantees ordering **only within a partition**. Records with the same key hash to the same partition (default partitioner), so per-key ordering is preserved as long as the key is set and partition count doesn't change. Across partitions there is no global order. Two things can silently break ordering: (1) producer retries with `max.in.flight.requests.per.connection > 1` and idempotence *disabled* can reorder on retry — enabling idempotence makes in-flight reordering safe up to 5; (2) increasing partition count remaps keys, so a key's new records land in a different partition than its history. If you need strict global order you must use a single partition (sacrificing parallelism), or push ordering responsibility downstream via sequence numbers/timestamps.

### Q16. [Practical] Design a partitioning strategy for an e-commerce order topic. (Industry example)

For an `orders` topic, partition by **customer ID** so all events for a customer (created → paid → shipped → delivered) stay ordered and a stateful consumer can build correct per-customer state. Trade-off: a few "whale" customers can create **hot partitions** that overwhelm one consumer. Mitigations: use a composite key (`customerId#regionShard`) to spread very hot keys, or detect skew and switch hot keys to a custom partitioner. This mirrors how companies like **Uber** partition trips by `tripId`/`driverId` to keep each entity's lifecycle ordered while scaling horizontally — Uber's uReplicator and trillions of messages/day rely on careful keying. If you instead need maximum throughput and don't care about per-entity order (e.g. firehose of metrics), use a null key so the sticky partitioner round-robins batches across partitions for even load.

### Q17. [Coding] Write a custom partitioner that routes "VIP" keys to dedicated partitions.

**Problem:** Send VIP customers (key prefixed `vip-`) to partitions 0–1 (low latency, isolated) and everyone else across the remaining partitions.

```java
import org.apache.kafka.clients.producer.Partitioner;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.PartitionInfo;
import java.util.List;
import java.util.Map;

public class VipPartitioner implements Partitioner {
    private static final int VIP_PARTITIONS = 2;

    @Override
    public int partition(String topic, Object key, byte[] keyBytes,
                         Object value, byte[] valueBytes, Cluster cluster) {
        List<PartitionInfo> parts = cluster.partitionsForTopic(topic);
        int total = parts.size();
        if (keyBytes == null) {
            // no key: spread non-VIP traffic over non-VIP partitions
            return VIP_PARTITIONS + (int) (Math.random() * (total - VIP_PARTITIONS));
        }
        String k = new String(keyBytes);
        if (k.startsWith("vip-")) {
            // hash within the VIP partition range so a VIP key is still stable
            return Math.floorMod(k.hashCode(), VIP_PARTITIONS);
        }
        // non-VIP: hash within remaining partitions, stable per key
        return VIP_PARTITIONS + Math.floorMod(k.hashCode(), total - VIP_PARTITIONS);
    }

    @Override public void close() {}
    @Override public void configure(Map<String, ?> configs) {}
}
// Wire up: props.put(ProducerConfig.PARTITIONER_CLASS_CONFIG, VipPartitioner.class.getName());
```

**Edge cases:** if `total <= VIP_PARTITIONS` the non-VIP math underflows — validate at startup. Using `Math.random()` for null keys defeats batching; prefer the built-in sticky behavior unless isolation matters. **Complexity:** O(1) per record. **Caveat:** changing partition count later remaps non-VIP keys, breaking their ordering — document this contract.

### Q18. [Practical] How do you monitor and respond to consumer lag in production?

Consumer lag = `log-end-offset` (latest produced) − `committed-offset` (consumer progress), per partition. Expose it via `kafka-consumer-groups.sh --describe`, JMX `records-lag-max`, or Burrow / Kafka Lag Exporter into Prometheus, and alert on **sustained growth** (a constant nonzero lag is fine; rising lag means consumers can't keep up). When lag spikes: check whether it's all partitions (under-provisioned consumers / slow downstream) or one partition (hot key / poison message stalling processing). Responses: scale consumers up to partition count, increase `max.poll.records` and parallelize processing, fix the slow downstream dependency, or temporarily route to a dead-letter topic to unblock. Track lag *in time* (seconds behind), not just record count, because record-count lag is meaningless without throughput context.

---

## 🟠 Advanced (8–12 yrs)

### Q19. [Theory] Explain exactly-once semantics (EOS) and Kafka transactions end-to-end.

EOS means each record affects downstream state exactly once even across failures. Kafka achieves it for **consume-process-produce** loops by combining the idempotent producer with **transactions**: the producer is configured with a stable `transactional.id`, calls `initTransactions()`, then wraps `beginTransaction()` → `send()` outputs → `sendOffsetsToTransaction()` (committing the *input* offsets atomically with the outputs) → `commitTransaction()`. The transaction coordinator writes markers to partitions, and consumers set `isolation.level=read_committed` so they never see records from aborted or in-flight transactions. The key insight: the consumer's offset commit and the produced output are written **atomically** to the same transaction, so on replay you don't double-process. EOS does *not* extend to external systems unless they participate (e.g. via an idempotent sink or two-phase pattern). It adds latency and coordinator overhead, so use it only where duplicates are genuinely unacceptable (payments, ledgers).

```
read_committed consumer view:

partition log:  [m1][m2][BEGIN_TXN][m3][m4][ABORT]  [m5][COMMIT]
visible to consumer:  m1, m2, m5      (m3,m4 aborted -> filtered out)
```

### Q20. [Coding] Implement a transactional consume-process-produce loop.

**Problem:** Read from `input`, transform, write to `output`, and commit input offsets atomically with outputs (exactly-once).

```java
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import java.time.Duration;
import java.util.*;

public class Eos {
    public static void main(String[] args) {
        Properties pp = new Properties();
        pp.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        pp.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "txn-orders-1"); // stable per instance
        pp.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        pp.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
        pp.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");

        Properties cp = new Properties();
        cp.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        cp.put(ConsumerConfig.GROUP_ID_CONFIG, "eos-group");
        cp.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        cp.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false); // offsets committed via the txn
        cp.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        cp.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(pp);
             KafkaConsumer<String, String> consumer = new KafkaConsumer<>(cp)) {

            producer.initTransactions();
            consumer.subscribe(List.of("input"));

            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));
                if (records.isEmpty()) continue;
                try {
                    producer.beginTransaction();
                    for (ConsumerRecord<String, String> r : records) {
                        producer.send(new ProducerRecord<>("output", r.key(), transform(r.value())));
                    }
                    // atomically commit the offsets we consumed as part of this txn
                    Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
                    for (TopicPartition tp : records.partitions()) {
                        long last = records.records(tp).get(records.records(tp).size() - 1).offset();
                        offsets.put(tp, new OffsetAndMetadata(last + 1));
                    }
                    producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata());
                    producer.commitTransaction();
                } catch (Exception e) {
                    producer.abortTransaction(); // nothing becomes visible
                }
            }
        }
    }
    static String transform(String v) { return v.toUpperCase(); }
}
```

**Edge cases:** `transactional.id` must be **stable and unique per logical producer** so zombie fencing works (a restarted instance fences the old one). If `abortTransaction()` itself fails or you hit `ProducerFencedException`, the producer is poisoned — recreate it. Don't share one transactional producer across threads.

### Q21. [Theory] What is the controller, and how did KRaft change cluster coordination?

Historically Kafka used **ZooKeeper** to store cluster metadata (broker registration, topic configs, ISR, partition assignments) and elect the controller broker. This was an operational burden (a separate system to run/secure/tune) and a scalability bottleneck — controller failover meant reloading all metadata from ZooKeeper, slow at hundreds of thousands of partitions. **KRaft** (KIP-500) replaces ZooKeeper with a built-in Raft quorum: a set of **controller** nodes maintains metadata as an internal `__cluster_metadata` log that brokers replicate as event-sourced state. This collapses two systems into one, makes failover near-instant (the new controller already has the log), and scales to millions of partitions. ZooKeeper mode was deprecated in 3.5, and **Kafka 4.0 (2025) removed ZooKeeper entirely** — KRaft is now the only mode. Migration from ZK to KRaft was supported via a dual-write bridge in the 3.x line.

```
KRaft topology:

  [Controller quorum: c1(leader) c2 c3]  <- Raft, holds __cluster_metadata log
            ▲ replicate metadata
   ┌────────┼────────┐
 Broker b1  Broker b2  Broker b3   <- apply metadata, serve data partitions
(Controllers and brokers can be combined nodes in dev, separate in prod.)
```

### Q22. [Practical] How do you tune a Kafka cluster for high throughput vs. low latency?

These goals conflict, so first decide which dominates. **For throughput:** large `batch.size` (64–256KB) and `linger.ms` (10–100ms), `compression.type=zstd`, many partitions for parallelism, large `socket.send/receive.buffer.bytes`, and consumer `fetch.min.bytes` raised so brokers return bigger fetches. Ensure enough page cache (Kafka relies on the OS page cache; give brokers RAM, don't set a huge JVM heap — 6–8GB heap is typical, the rest is page cache) and use fast disks (NVMe/RAID, XFS). **For latency:** `linger.ms=0`, smaller batches, `acks=1` only if durability allows, `fetch.min.bytes=1`, and keep partition counts moderate so end-to-end coordination is fast. Cross-cutting: pin replication with rack awareness, monitor under-replicated partitions, isolate the controller, and keep `num.io.threads`/`num.network.threads` aligned with cores. Always load-test with representative record sizes — tuning is workload-specific.

### Q23. [Theory] Explain Kafka Streams: its execution model, state stores, and exactly-once.

Kafka Streams is a JVM library (not a separate cluster) for building stateful stream processing apps directly on Kafka. A topology of `KStream`/`KTable`/`GlobalKTable` operators is split into **tasks**, one per input partition, so parallelism follows partitioning and scaling is "just run more instances." Stateful operations (aggregations, joins, windows) use local **state stores** (RocksDB by default) backed by a compacted **changelog topic**, so on failure or rebalance the state is restored by replaying the changelog onto a new instance. Streams provides exactly-once (`processing.guarantee=exactly_once_v2`) by wrapping each task's reads, state updates, and output writes in Kafka transactions. `KTable` represents a changelog (latest value per key — the table/stream duality), while `KStream` is an unbounded record stream. Time semantics (event-time, windowing, grace periods, suppression) make it suitable for real correctness-sensitive aggregations.

### Q24. [Theory] What is Kafka Connect and when do you use it over writing your own producer/consumer?

Kafka Connect is a framework for **scalable, fault-tolerant integration** between Kafka and external systems via reusable **connectors** (source = into Kafka, sink = out of Kafka). It runs as a cluster of workers; connectors are split into **tasks** spread across workers, with offset/state management, restarts, and rebalancing handled for you — no glue code. Use it instead of hand-rolled clients when you're doing standard integration: database CDC (Debezium), S3/HDFS sinks, Elasticsearch, JDBC, etc. It supports **Single Message Transforms (SMTs)** for lightweight per-record routing/masking and integrates with the Schema Registry via converters. You write custom producers/consumers only when you need bespoke business logic Connect can't express, or when an existing connector doesn't fit. The win is operational: declarative JSON config, exactly-once support for many sinks, and dead-letter-queue handling out of the box.

### Q25. [Theory] Why use a Schema Registry, and how do Avro/Protobuf compatibility modes work?

A Schema Registry (Confluent, Apicurio) stores versioned record schemas and assigns each a global ID; producers serialize with Avro/Protobuf/JSON-Schema and embed only the schema ID (not the full schema) in each message, so consumers fetch the schema by ID and deserialize. This decouples producers and consumers and enables **schema evolution** with enforced **compatibility**: `BACKWARD` (new schema reads old data — safe to add optional fields, common default), `FORWARD` (old schema reads new data), `FULL` (both), and `*_TRANSITIVE` variants checking against all prior versions. Avro needs a writer + reader schema and resolves differences via defaults; Protobuf evolves by reserving field numbers and never reusing tags. The registry rejects an incompatible schema at register time, turning a runtime deserialization disaster into a deploy-time error. **Security note:** lock down the registry (auth + TLS) and disable arbitrary schema deletion, because schema poisoning or compatibility downgrades can break every consumer.

### Q26. [Coding] Implement a windowed word count in Kafka Streams.

**Problem:** Count words per 1-minute tumbling window from a `lines` topic and emit to `counts`.

```java
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.*;
import java.time.Duration;
import java.util.Arrays;
import java.util.Properties;

public class WindowedWordCount {
    public static void main(String[] args) {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "wordcount-v1"); // = consumer group + changelog prefix
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());

        StreamsBuilder builder = new StreamsBuilder();
        builder.<String, String>stream("lines")
            .flatMapValues(line -> Arrays.asList(line.toLowerCase().split("\\W+")))
            .filter((k, word) -> word != null && !word.isEmpty())
            .groupBy((k, word) -> word)
            .windowedBy(TimeWindows.ofSizeAndGrace(Duration.ofMinutes(1), Duration.ofSeconds(10)))
            .count(Materialized.as("counts-store"))
            .toStream()
            .map((wk, cnt) -> KeyValue.pair(wk.key() + "@" + wk.window().start(), String.valueOf(cnt)))
            .to("counts");

        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        Runtime.getRuntime().addShutdownHook(new Thread(streams::close));
        streams.start();
    }
}
```

**Edge cases:** late records beyond the **grace period** are dropped — tune grace to your data's lateness. The windowed store grows; set a retention via `Materialized.withRetention(...)`. Changing `APPLICATION_ID_CONFIG` forks a brand-new app with fresh state (full reprocess). **Complexity:** O(words) per record; state size is O(distinct keys × live windows).

---

## 🔴 Expert (15+ yrs)

### Q27. [Theory] Walk through leader election and unclean leader election trade-offs.

When a partition leader fails, the controller picks a new leader from the **ISR** (preferring the head of the replica list / preferred leader). Because only ISR members are guaranteed to have all committed data up to the HWM, electing from the ISR guarantees **no committed data loss**. The danger case is when *all* ISR members are down but a non-ISR (stale) replica is alive. `unclean.leader.election.enable=false` (the safe default) keeps the partition **offline** until an ISR member returns — choosing consistency/availability of correct data over availability. Setting it `true` lets a stale replica become leader, restoring availability but **silently dropping** the records it never replicated and creating a log divergence. The right choice is domain-specific: a financial ledger must never elect unclean; a best-effort metrics pipeline might prefer staying up. Pair `replication.factor=3` + `min.insync.replicas=2` so single-broker failures never force this dilemma.

### Q28. [Practical] You're seeing "rebalance storms" — the group rebalances continuously and throughput collapses. Root-cause and remediate.

A rebalance storm is a feedback loop: a slow consumer misses `max.poll.interval.ms`, gets ejected, triggers a (stop-the-world, eager) rebalance, which pauses everyone, which makes processing slower, which ejects more consumers. Root causes: GC pauses or slow downstream blowing the poll interval; flaky pods / aggressive autoscaling churning membership; `session.timeout.ms` too tight for network jitter; or huge `max.poll.records` making each batch too slow. Remediation: switch to **cooperative sticky** assignment so rebalances aren't stop-the-world; add **static membership** (`group.instance.id`) so restarts don't rebalance; reduce `max.poll.records`; raise `max.poll.interval.ms` to a realistic ceiling; stabilize pods (proper readiness probes, anti-flap). I'd also check broker-side: an overloaded coordinator or under-replicated `__consumer_offsets` can slow rebalance completion. The structural fix is to make individual consumers reliably fast and membership stable, not to keep loosening timeouts.

### Q29. [Theory] How do you architect multi-region/disaster-recovery topologies with Kafka?

Two broad patterns. **Stretched cluster**: a single cluster spanning regions with rack-aware replica placement and `min.insync.replicas` tuned so a region can fail without data loss — gives strong consistency but is latency-sensitive and needs low inter-region RTT (works for nearby AZs/regions). **Replication-based**: independent clusters per region with **MirrorMaker 2** (or Confluent Replicator/Cluster Linking) asynchronously copying topics, consumer offsets, and ACLs. MM2 supports active-passive (DR standby) and active-active (each region serves local traffic, replicates to the other). The hard parts are **offset translation** (an offset in cluster A means nothing in cluster B; MM2 maintains a checkpoint topic so consumers can resume after failover) and **avoiding replication loops** (topic renaming/prefixing). RPO/RTO drive the choice: async replication means a nonzero RPO (data in flight at failover is lost) but lower latency; stretched clusters give near-zero RPO at the cost of write latency and operational complexity.

### Q30. [Practical] Design an event-sourcing + CDC backbone for a large org. (Case study)

**Scenario:** Decompose a monolith; many services need each other's data without point-to-point coupling. **Approach:** Use Kafka as the central log. Capture source-of-truth changes from each service's database via **Debezium CDC** (reads the DB transaction log, not the app) into per-table compacted topics — this is the **outbox pattern**'s scalable cousin and avoids dual-write inconsistency. Standardize schemas through the **Schema Registry** with `BACKWARD` compatibility so producers evolve without breaking consumers. Downstream, services materialize the views they need via **Kafka Streams** or Connect sinks (Elasticsearch for search, a data lake via S3 sink for analytics). **This is essentially LinkedIn's and Netflix's model** — LinkedIn invented Kafka precisely to be this "central nervous system," moving trillions of messages/day; Netflix's Keystone pipeline routes ~petabytes/day through Kafka into processing and storage. **Trade-offs:** eventual consistency between services, schema governance becomes an org-wide discipline, and you must plan topic retention/compaction and PII handling (field-level encryption or tokenization in transforms) up front. Governance and a clear data contract matter more than the tech once you pass a few dozen services.

### Q31. [Theory] What are the security mechanisms in Kafka and how do you harden a cluster?

Kafka security has three pillars. **Authentication**: TLS mutual auth, or SASL mechanisms — `SCRAM-SHA-512` (password, stored salted), `GSSAPI`/Kerberos (enterprise), or `OAUTHBEARER` (modern token-based, integrates with OIDC). **Encryption in transit**: TLS on broker listeners (separate internal/external listeners with different security protocols). **Authorization**: ACLs via the `AclAuthorizer` (KRaft-native StandardAuthorizer) granting principals operations (Read/Write/Describe) on resources (Topic, Group, Cluster, TransactionalId), or external RBAC. Hardening checklist: disable PLAINTEXT listeners, enforce mTLS or SASL on every listener, deny-by-default ACLs, restrict the inter-broker listener, lock down the Schema Registry and Connect REST APIs (which can otherwise read/write any topic), audit-log admin operations, rotate credentials, and consider **field-level / payload encryption** for PII since Kafka itself stores plaintext on disk (enable disk encryption at rest too). A common breach vector is an unauthenticated Connect/Streams admin endpoint exposed to the network — treat those as privileged.

### Q32. [Practical] A topic was created with too few partitions and is now a bottleneck. How do you safely increase partitions in production?

Increasing partitions (`kafka-topics.sh --alter --partitions N`) is **online and non-destructive to existing data**, but it has a sharp edge: the **default partitioner remaps keys**, so a given key's *new* records may land on a different partition than its history — **breaking per-key ordering and any consumer that assumes a key never moves**. So the plan: (1) confirm whether downstream consumers depend on per-key ordering or per-partition state (Streams state stores are especially sensitive — their changelog/store keying assumptions break). (2) If they don't (e.g. stateless or order-insensitive), just scale up and add consumers. (3) If they do, the safe path is usually to create a **new topic with the right partition count**, dual-write or replay via MM2/a migration job, cut consumers over once caught up, then retire the old topic — preserving a clean key→partition mapping. Always scale consumers to match, and never *decrease* partitions (unsupported — requires a new topic). I'd also reassess the *original* sizing mistake so it doesn't recur.

### Q33. [Behavioral] Tell me about a time you led the response to a major Kafka production incident.

Use **STAR**. *Situation:* describe the impact in business terms — e.g. "the payments consumer group fell hours behind during a Black Friday spike, delaying settlement." *Task:* "As the on-call lead I had to restore real-time processing without losing or double-processing payments." *Action:* explain how you triaged (confirmed it was lag concentrated on hot partitions from a few merchant keys), the immediate mitigation (scaled consumers to the partition count, raised `max.poll.records`, paused a non-critical enrichment step), and the structural fix (re-keyed with a sharded merchant key and added cooperative rebalancing + static membership). *Result:* quantify — "caught up within 40 minutes, zero duplicate settlements thanks to transactional EOS, and a post-incident change to lag-based alerting and capacity headroom." The signal interviewers want: calm prioritization under pressure, distinguishing mitigation from root-cause fix, data-driven decisions, blameless follow-through, and clear stakeholder communication. End with the lesson learned and how you made the system (and the team's runbook) more resilient.

### Q34. [Theory] How does Tiered Storage change Kafka operations and cost, and what are its limits?

**Tiered Storage** (KIP-405, production-ready in Kafka 3.6+) separates a "local" tier (recent data on broker disks) from a "remote" tier (object storage like S3/GCS) for older log segments. Brokers offload aged segments to cheap object storage and fetch them on demand when a consumer reads old offsets. The wins: drastically cheaper long retention (keep months/years without huge local disks), **faster broker recovery and reassignment** (less local data to replicate), and the ability to right-size local disks for the hot set. Trade-offs/limits: reads from the remote tier have higher latency and can pressure broker network/CPU during large historical replays; compacted topics aren't supported the same way; it adds an external dependency (object store availability and egress cost) and more moving parts to monitor. Operationally it shifts capacity planning from "how much disk for retention" to "how much disk for the hot window," which is usually a big cost reduction for log-heavy, replay-occasionally workloads (event sourcing, audit logs, ML feature stores).

### Q35. [Theory] When is Kafka the wrong tool, and what would you choose instead?

Kafka excels at high-throughput, ordered, replayable event streams — but it's a poor fit for several needs. **Per-message TTL, priority queues, or complex routing / delayed delivery** are awkward in Kafka; RabbitMQ or AWS SQS/SNS handle them natively. **Strict point-to-point request/reply** with low fan-out and per-message ack/nack semantics is simpler on a traditional broker. **Very low-latency, low-volume** messaging may not justify Kafka's operational weight. **Per-message arbitrary deletion** (e.g. GDPR "delete this one record") fights Kafka's append-only model — you need compaction + tombstones or crypto-shredding. And Kafka is **not a database**: ad-hoc queries, secondary indexes, and random point lookups belong in a real store (use Kafka to *feed* one). The senior signal is recognizing that "everything is an event" can be over-applied; choose Kafka when durability, replay, ordering, and fan-out at scale are the actual requirements, and a queue or database when they aren't.

---

## 🧩 Extended Questions — Set 1: Deeper Theory & Practical Operations

### 🟢 Basic — extended

#### Q36. [Theory] What is the difference between `subscribe()` and `assign()` on a consumer?

`subscribe()` enrolls the consumer in a **consumer group** and lets Kafka's group coordinator dynamically assign partitions, balance them across all members, and rebalance when membership changes. It is the dynamic, fault-tolerant path — if a consumer dies, its partitions are automatically redistributed to survivors. This is what you want for almost all application consumers because you get scaling and failover for free. The cost is that you don't control *which* partitions you get, and you're subject to rebalances.

`assign()` is **manual partition assignment**: you hand the consumer a fixed list of `TopicPartition` objects and Kafka does no group coordination at all (no rebalancing, no automatic failover). There's no group management, though you can still commit offsets if you set a `group.id`. Use `assign()` when you need deterministic control — e.g. a single consumer that must read a specific partition, a CDC tool tracking partitions explicitly, or a system implementing its own partition-to-worker mapping.

```java
// Dynamic, group-managed:
consumer.subscribe(List.of("orders"));

// Manual, no rebalancing:
consumer.assign(List.of(new TopicPartition("orders", 0),
                        new TopicPartition("orders", 2)));
consumer.seekToBeginning(consumer.assignment()); // full control over position
```

You cannot mix the two on the same consumer instance — calling `assign()` after `subscribe()` throws `IllegalStateException`. The mental model: `subscribe()` = "I'm part of a team, give me my fair share"; `assign()` = "I know exactly what I want, don't coordinate."

#### Q37. [Theory] What is the difference between `at-most-once`, `at-least-once`, and `exactly-once` delivery?

These three semantics describe what happens when failures interrupt the produce or consume path. **At-most-once** means a record is delivered zero or one times — never duplicated, but possibly lost. You get this by committing offsets *before* processing (or `acks=0` on the producer): if you crash after committing but before processing, that record is skipped forever. It's acceptable only for lossy data like sampled metrics or best-effort telemetry.

**At-least-once** means a record is delivered one or more times — never lost, but possibly duplicated. This is the most common default: commit offsets *after* processing, so a crash before commit just reprocesses the batch. The duplicates are the price of never losing data, and you handle them by making processing **idempotent** (upsert by key, dedupe on a record id).

**Exactly-once** means each record affects state precisely once despite failures — no loss, no duplicates. Kafka delivers this for consume-process-produce loops via the idempotent producer plus transactions (`isolation.level=read_committed` on the read side), atomically committing input offsets with output writes.

```
                  loss?   duplicates?   typical config
at-most-once       yes        no        commit before process / acks=0
at-least-once       no       yes        commit after process / acks=all
exactly-once        no        no        transactions + read_committed
```

The senior nuance: true end-to-end exactly-once requires the *sink* to participate (idempotent writes or transactional commit). Kafka's EOS covers the Kafka-to-Kafka loop, not arbitrary external systems.

#### Q38. [Practical] How do you produce and consume from the command line for quick debugging?

Kafka ships with console tools that are indispensable for triage without writing code. To produce, pipe lines into `kafka-console-producer.sh`; to consume, tail a topic with `kafka-console-consumer.sh`. Always include `--bootstrap-server` (the old `--broker-list` / `--zookeeper` flags are gone in modern versions).

```bash
# Produce a few messages (one per line, Ctrl-D to end)
kafka-console-producer.sh --bootstrap-server localhost:9092 \
  --topic orders --property "parse.key=true" --property "key.separator=:"
# then type:  cust-42:order#1001

# Consume from the beginning, showing keys and partitions
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic orders --from-beginning \
  --property print.key=true --property print.partition=true \
  --property print.offset=true

# Read exactly one partition from a specific offset
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic orders --partition 0 --offset 1500 --max-messages 10
```

For inspecting topic health, `kafka-topics.sh --describe --topic orders --bootstrap-server localhost:9092` shows partitions, leaders, replicas, and **ISR** — the first thing I check during an incident. The key debugging move is `--from-beginning` to replay, plus a throwaway `--group` or no group so you don't disturb a real consumer group's committed offsets. For Avro/Protobuf payloads you need `kafka-avro-console-consumer` (Confluent) with `--property schema.registry.url=...`, otherwise the bytes print as garbage.

#### Q39. [Theory] What is `__consumer_offsets` and why does it matter?

`__consumer_offsets` is an internal, **compacted** topic (50 partitions by default) where Kafka stores the committed offset for every consumer-group/topic/partition combination, plus group membership and coordinator state. When a consumer calls `commitSync()`/`commitAsync()`, it's really producing a record into this topic keyed by `(group, topic, partition)`; because the topic is compacted, only the latest offset per key survives, so the topic stays bounded even after billions of commits.

This design replaced the old ZooKeeper-based offset storage, which couldn't handle high commit rates. Storing offsets *in Kafka* means group progress is durable, replicated (`offsets.topic.replication.factor`, which you should set to 3 in production — a classic single-broker dev default of 1 is a data-loss trap), and survives restarts. The **group coordinator** for a group is the broker that leads the `__consumer_offsets` partition for that group's hash.

Operationally this matters in a few ways: if `__consumer_offsets` is under-replicated or its leader is overloaded, *every* group's commits and rebalances slow down — a cluster-wide symptom from one internal topic. You should never manually delete or repartition it. And to reset a group's position you use `kafka-consumer-groups.sh --reset-offsets` rather than touching the topic directly, which writes proper offset records the coordinator understands.

### 🟡 Intermediate — extended

#### Q40. [Practical] Compare the partition assignment strategies (Range, RoundRobin, Sticky, CooperativeSticky) and when to pick each.

The `partition.assignment.strategy` config controls how the coordinator maps partitions to consumers, and the choice affects balance quality and rebalance disruption. **RangeAssignor** (historical default) assigns contiguous ranges per topic, which can badly skew load when topics have few partitions — consumer 0 tends to get partition 0 of every topic, so co-partitioning for joins works but the first consumer is overloaded. **RoundRobinAssignor** spreads all topic-partitions evenly across consumers, giving better balance but reshuffling everything on each rebalance.

**StickyAssignor** keeps assignments as stable as possible across rebalances (minimizing partition movement, which preserves local caches/state) while still balancing — but it's still *eager* (stop-the-world). **CooperativeStickyAssignor** adds incremental rebalancing on top of stickiness: only the partitions that actually need to move are revoked, so most consumers keep processing through a rebalance. This is the modern default and the right choice for almost everything.

```
4 partitions, 2 consumers, then a 3rd joins:

Eager (Range/RoundRobin): ALL partitions revoked -> reassigned -> stop-the-world
Cooperative Sticky:       only 1-2 partitions move; others never pause
```

Pick **CooperativeSticky** by default. Use **Range** only when you specifically need co-partitioned topics aligned for a join and partition counts are large enough to balance. The migration from eager to cooperative requires a rolling upgrade through a config that supports both, because mixing eager and cooperative members in one group can deadlock the rebalance.

#### Q41. [Practical] Your producer is throwing `TimeoutException: Expiring N records` and `BufferExhaustedException`. What's happening and how do you fix it?

Both errors mean the producer's send buffer (`buffer.memory`, default 32MB) is backing up because records are being added faster than they can be acknowledged and drained. The producer accumulates records in memory; if the broker is slow, unreachable, or the partition leader is unavailable, batches sit in the buffer until they exceed `delivery.timeout.ms` and get **expired** (`TimeoutException`), or new `send()` calls block for `max.block.ms` and then fail with `BufferExhaustedException` when the buffer is full.

The first step is to find *why* sends aren't completing: check broker health and under-replicated partitions, network latency to brokers, whether `acks=all` plus a degraded ISR is stalling acks, and whether one slow partition leader is the culprit. Don't just enlarge the buffer — that delays the symptom. Real fixes depend on the cause:

```properties
# If brokers are healthy but throughput-bound, improve batching efficiency:
batch.size=65536
linger.ms=10
compression.type=zstd

# If bursts overwhelm a healthy cluster, give more headroom and backpressure time:
buffer.memory=67108864          # 64MB
max.block.ms=60000              # how long send() blocks before throwing
delivery.timeout.ms=120000      # total time including retries before expiring

# If a partition leader is flaky, ensure retries + idempotence are sane:
enable.idempotence=true
```

The structural insight: this is a **backpressure** signal. Either your cluster can't keep up (scale brokers/partitions, fix the slow leader) or your producer is bursting beyond capacity (throttle the application, batch more, or accept blocking via a larger `max.block.ms`). Silently raising `buffer.memory` to 1GB just turns a fast failure into a slow OOM.

#### Q42. [Theory] How does Kafka's storage layout work — segments, indexes, and why is it so fast?

Each partition is a directory of **segment** files, not one giant file. The active segment receives appends; once it reaches `segment.bytes` (default 1GB) or `segment.ms` age, it's rolled closed and a new active segment opens. Each segment has a `.log` (the records), a `.index` (offset → physical byte position, sparse), and a `.timeindex` (timestamp → offset, enabling `offsetsForTimes`). Retention and compaction operate at **segment granularity** — Kafka deletes whole old segments, which is why retention is cheap (an `unlink`, not a scan-and-rewrite).

Kafka's speed comes from leaning on the operating system rather than fighting it. Writes are **sequential appends** to the active segment, so even spinning disks sustain high throughput (no random seeks). Reads are served from the **OS page cache** — recently written data is still in cache, so consumers reading the tail rarely touch disk. And the broker uses **zero-copy** (`sendfile`) to transfer log bytes straight from page cache to the network socket without copying through user space or the JVM heap.

```
partition dir: orders-0/
  00000000000000000000.log    .index    .timeindex   <- segment (base offset 0)
  00000000000000150000.log    .index    .timeindex   <- segment (base offset 150000)
  00000000000000310000.log    .index    .timeindex   <- ACTIVE (appends here)

read offset 150042 -> binary-search .index for nearest -> seek into .log
```

This is also why you give the broker a **modest JVM heap and lots of RAM for page cache** (see the tuning question): Kafka's "cache" is the OS page cache, not the heap. Compression staying intact end-to-end (the broker never decompresses batches it just stores) preserves the zero-copy path. The whole design is "do less, let the kernel do the fast thing."

#### Q43. [Practical] How do you implement a dead-letter queue (DLQ) pattern in Kafka?

Kafka has no native DLQ or per-message retry/redelivery (unlike SQS), so you build the pattern yourself: when processing a record fails after your retry budget, you **produce it to a separate DLQ topic** with diagnostic headers, commit the original offset to move past it, and continue. This prevents a single **poison message** from blocking its entire partition while preserving the failed record for later inspection or replay.

```java
void handle(ConsumerRecord<String, String> r) {
    try {
        process(r);
    } catch (RetriableException e) {
        // transient: let it retry on next poll (don't commit past it)
        throw e;
    } catch (Exception poison) {
        // permanent failure: route to DLQ with context, then move on
        ProducerRecord<String, String> dlq =
            new ProducerRecord<>("orders.DLQ", r.key(), r.value());
        dlq.headers()
           .add("orig-topic", r.topic().getBytes())
           .add("orig-partition", Integer.toString(r.partition()).getBytes())
           .add("orig-offset", Long.toString(r.offset()).getBytes())
           .add("error", poison.getMessage().getBytes());
        dlqProducer.send(dlq);
        // offset for r will be committed normally -> partition unblocked
    }
}
```

The critical design decision is **distinguishing retriable from permanent failures**. A downstream timeout should be retried (don't DLQ it — you'll lose data on a blip); a deserialization error or business-rule violation is permanent and belongs in the DLQ. Kafka Connect and Spring Kafka give this to you declaratively (`errors.tolerance=all` + `errors.deadletterqueue.topic.name`, or `DefaultErrorHandler` with a `DeadLetterPublishingRecoverer`).

Operationally: monitor DLQ depth and alert on growth (a filling DLQ means something is systematically broken), include enough headers to replay or debug, and build a **replay tool** that reads the DLQ back into the source topic after you've fixed the bug. A subtle trap is sending to the DLQ but failing to commit the original offset — on restart you reprocess and re-DLQ, doubling records; commit only after the DLQ send succeeds.

#### Q44. [Theory] Explain the producer's `delivery.timeout.ms`, `request.timeout.ms`, `retries`, and `retry.backoff.ms` and how they interact.

These four configs govern how long a `send()` keeps trying before giving up, and getting them consistent matters because misalignment causes confusing failures. **`delivery.timeout.ms`** (default 120s) is the **upper bound on the entire send lifecycle** — from when `send()` returns to final success or failure, including time in the buffer, all retries, and backoffs. It's the config you reason about for end-to-end guarantees. **`request.timeout.ms`** (default 30s) bounds a *single* network round-trip to the broker waiting for an ack; exceeding it triggers a retry (if retries remain).

**`retries`** (default `Integer.MAX_VALUE` when idempotence is on) caps how many times a failed batch is resent, and **`retry.backoff.ms`** (default 100ms) is the pause between attempts to avoid hammering a struggling broker. In modern Kafka you generally leave `retries` effectively infinite and let `delivery.timeout.ms` be the real ceiling — that's cleaner than tuning a retry count, because "give up after 2 minutes total" is more meaningful than "give up after 7 tries."

```
delivery.timeout.ms (120s)  >=  linger.ms + request.timeout.ms
|<------------------------- total send budget --------------------------->|
[ in buffer ][ attempt1: request.timeout ][ backoff ][ attempt2 ] ... [ fail ]
```

The constraint to remember: `delivery.timeout.ms` must be `>= linger.ms + request.timeout.ms`, otherwise the client rejects the config. With idempotence enabled, retries are **safe from reordering** (the broker dedupes by sequence number), so you can retry freely without duplicating or reordering records. The common mistake is setting `retries=0` "to avoid duplicates" — that's both unnecessary (idempotence handles dupes) and harmful (a single transient blip now fails the send).

#### Q45. [Practical] How do you reset a consumer group's offsets safely in production?

You reset offsets when you need a group to reprocess data (after a bug fix) or skip ahead (to abandon a backlog). The tool is `kafka-consumer-groups.sh --reset-offsets`, and the cardinal rule is the **group must be inactive** — all its consumers stopped — because you can't move offsets out from under a live consumer. Always run with `--dry-run` first to preview exactly what will change, then re-run with `--execute`.

```bash
# 1) Preview (no changes) — reset to earliest for full reprocess
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group order-processors --topic orders \
  --reset-offsets --to-earliest --dry-run

# 2) Reset to a point in time (e.g. just before an incident)
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group order-processors --topic orders \
  --reset-offsets --to-datetime 2026-06-15T02:00:00.000 --execute

# 3) Shift back 1000 records on a single partition only
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group order-processors --topic orders:3 \
  --reset-offsets --shift-by -1000 --execute
```

The available targets are rich: `--to-earliest`, `--to-latest`, `--to-offset N`, `--to-datetime`, `--shift-by N` (forward/back), and `--to-current`. You can scope to specific topics or `topic:partition`. The biggest production risk is **reprocessing side effects** — if the consumer sends emails, charges cards, or calls external APIs, replaying from earliest can re-trigger all of that. So before resetting, confirm the consumer's processing is idempotent or temporarily disable side effects. For surgical control or to script it, you can also `seek()` programmatically inside a `ConsumerRebalanceListener`. Document the reset (who, when, why, target) because an unexplained offset jump looks identical to a bug during the next incident.

#### Q46. [Theory] What is the difference between `flush()`, `close()`, and what does `linger.ms` mean for ordering during shutdown?

`flush()` **blocks until all buffered records have been sent and acknowledged** (or failed), but leaves the producer open and reusable — use it when you need a synchronization barrier, e.g. ensuring a batch is durable before committing a checkpoint in your own system. It does not close connections. `close()` flushes *and then* releases all resources (sockets, the I/O thread, metrics); after `close()` the producer is dead. `close(Duration)` gives a bounded grace period — pending records get that long to complete, after which they're failed and the producer shuts down anyway, which matters in shutdown hooks where you can't hang forever.

`linger.ms` introduces a deliberate delay before sending a partially full batch, so records can accumulate for better batching/compression. During normal operation this is fine, but on shutdown you must `flush()`/`close()` so the lingering, not-yet-sent batch isn't silently dropped when the JVM exits — a common cause of "the last few messages vanished" bugs.

```java
try (Producer<String,String> p = new KafkaProducer<>(props)) {
    for (var rec : batch) p.send(rec);
    p.flush();   // guarantees this batch is acked before we proceed/checkpoint
    // ... do something that depends on durability ...
}                // try-with-resources calls close() -> flush again + release

// In a shutdown hook, bound it so you never hang:
Runtime.getRuntime().addShutdownHook(new Thread(() -> producer.close(Duration.ofSeconds(10))));
```

On ordering: with `enable.idempotence=true`, retries and in-flight batches stay correctly ordered per partition even across the flush, so a graceful `close()` preserves order. The danger is a non-graceful exit (kill -9, OOM) that bypasses `close()` — anything still lingering or in flight is lost. The rule of thumb: producers must always be closed deterministically (try-with-resources or a bounded shutdown hook), never left to GC.

#### Q47. [Practical] How do you handle schema evolution without breaking live consumers? Give a concrete example.

Schema evolution breaks consumers when a producer emits data an existing consumer can't deserialize, so the discipline is to enforce a **compatibility mode** in the Schema Registry and only make changes that mode permits. The most common production choice is **BACKWARD** compatibility: a new schema (used by consumers) can read data written with the *previous* schema. Under BACKWARD you may **add fields with defaults** and **remove fields**, but you may not add a required field without a default (old data has no value for it) or change a field's type incompatibly.

The safe rollout order under BACKWARD is **consumers first, then producers**: upgrade all consumers to the new schema (which can still read old data), and only then let producers start writing the new schema. If you do it backwards, producers emit new data that old consumers choke on.

```json
// v1
{"type":"record","name":"Order","fields":[
  {"name":"id","type":"string"},
  {"name":"amount","type":"double"}
]}

// v2 — BACKWARD-compatible: new optional field WITH a default
{"type":"record","name":"Order","fields":[
  {"name":"id","type":"string"},
  {"name":"amount","type":"double"},
  {"name":"currency","type":"string","default":"USD"}  // old records read as "USD"
]}
```

The registry rejects an incompatible registration at deploy time (`kafka-avro-console-producer` or the REST `compatibility` check), which is exactly the point — it converts a 3am deserialization outage into a failed CI step. For changes BACKWARD can't express (renaming, type changes), the clean path is a **new field alongside the old** (dual-write both, migrate readers, then deprecate) or a **new topic/major version** with a controlled consumer migration. Choose FORWARD if producers must lead (old consumers read new data), or FULL/TRANSITIVE for the strictest contracts. The anti-pattern is disabling compatibility checks to "unblock a deploy" — that just moves the breakage to runtime across every consumer at once.

### 🟠 Advanced — extended

#### Q48. [Theory] Explain how the idempotent producer's sequence numbers and the broker's deduplication actually work, including the `OutOfOrderSequenceException`.

When `enable.idempotence=true`, the producer requests a **Producer ID (PID)** from the broker via `InitProducerId`, and thereafter stamps every record batch with `(PID, partition, base sequence number)`. The sequence number is a monotonically increasing per-partition counter. Each partition leader tracks, in memory and in its log, the last sequence number it accepted from each PID. On receiving a batch it compares: if the sequence is exactly `last + 1`, it appends; if it's `<= last`, the batch is a **duplicate retry** and the broker silently acks it without re-appending (this is the dedup); if it's `> last + 1`, a batch was lost in between and the broker rejects it with `OutOfOrderSequenceException`.

This is why `max.in.flight.requests.per.connection` matters: with idempotence the broker can reorder up to 5 in-flight batches back into sequence order, so 5 is allowed and safe; above 5 the broker can't guarantee reassembly. The dedup window is bounded — the broker only remembers recent sequences (governed by retention and `transactional.id.expiration.ms` for transactional producers), so idempotence guards against retries within a session, not arbitrarily long gaps.

```
producer (PID=7, partition 0):
  batch seq 0..4   -> broker appends, last=4
  ack lost, retry seq 0..4 -> broker sees seq<=last -> DEDUP, re-acks, no double-write
  batch seq 5..9   -> broker appends, last=9
  (suppose seq 5..9 lost entirely) next batch seq 10 -> last+1 != 10 -> OutOfOrderSequenceException
```

An `OutOfOrderSequenceException` is a serious signal: it usually means data was lost between producer and broker (often from `acks` < all combined with a leader change, or a too-small ISR), and the idempotence guarantee is broken for that stream. The correct response is not to swallow it but to recreate the producer (resetting PID/sequence) and investigate the durability config — it's frequently a symptom of running idempotence with insufficient replication settings.

#### Q49. [Practical] You need to migrate from a 3-broker to a 6-broker cluster with zero downtime. Walk through the plan.

The goal is to expand capacity and rebalance partitions onto the new brokers without dropping writes or reads. Adding brokers to a Kafka cluster does **not** automatically move data — new brokers only host *new* partitions, leaving the old three hot. So the migration is really a **partition reassignment** exercise, and the tool is `kafka-reassign-partitions.sh`, done carefully with throttling.

```bash
# 1) Start the 3 new brokers (4,5,6) with unique broker.ids, same cluster.
#    Verify they join: kafka-broker-api-versions.sh / cluster metadata shows 6 brokers.

# 2) Generate a reassignment plan spreading partitions across all 6 brokers:
kafka-reassign-partitions.sh --bootstrap-server localhost:9092 \
  --topics-to-move-json-file topics.json \
  --broker-list "1,2,3,4,5,6" --generate > plan.json

# 3) Execute WITH a throttle so replication doesn't saturate the network:
kafka-reassign-partitions.sh --bootstrap-server localhost:9092 \
  --reassignment-json-file plan.json --execute \
  --throttle 50000000        # 50 MB/s cap on inter-broker replication

# 4) Watch progress; raise/lower throttle as needed:
kafka-reassign-partitions.sh --bootstrap-server localhost:9092 \
  --reassignment-json-file plan.json --verify
```

The critical operational concerns: **throttle the replication** (`--throttle`) so backfilling replicas onto the new brokers doesn't starve client traffic — an unthrottled reassignment of large topics can knock the cluster over, the single most common self-inflicted outage during expansion. Move topics in **batches**, not all at once, and watch under-replicated partition count and request latency throughout. Keep `replication.factor` unchanged; you're redistributing replicas, not changing durability. After reassignment, run a **preferred-leader election** (`kafka-leader-election.sh --election-type PREFERRED`) so leadership is balanced across all six brokers, not just replicas. Finally, remember to **remove the throttle** (it persists as a topic config until cleared) and re-verify metrics. Because leaders and ISRs update online and clients refresh metadata automatically, there's no downtime — but the whole thing lives or dies on throttling and incremental batching.

#### Q50. [Theory] How do consumer fetch sessions, `fetch.min.bytes`, `fetch.max.wait.ms`, and `max.partition.fetch.bytes` shape consumer performance?

A consumer doesn't fetch one record at a time; it issues **fetch requests** to brokers asking for data across all its assigned partitions, and these four configs tune the size/latency trade-off of those fetches. **`fetch.min.bytes`** (default 1) tells the broker the minimum data to accumulate before responding — raising it (e.g. 64KB) makes the broker wait and return larger, more efficient batches, improving throughput at the cost of latency. **`fetch.max.wait.ms`** (default 500) caps how long the broker waits to satisfy `fetch.min.bytes` before responding anyway, so latency is bounded even on a quiet topic.

**`max.partition.fetch.bytes`** (default ~1MB) limits how much data is returned *per partition* per fetch, while `fetch.max.bytes` caps the whole response. These interact with `max.poll.records` (a client-side cap on records returned per `poll()`): the consumer fetches bytes from the broker, then hands you at most `max.poll.records` from its internal buffer. **Fetch sessions** (KIP-227) make repeated fetches cheap — instead of re-sending the full partition list every time, the consumer establishes a session and sends incremental fetch requests, which dramatically reduces request overhead for consumers assigned many partitions.

```
high throughput:  fetch.min.bytes=65536, fetch.max.wait.ms=500  (big, lazy fetches)
low latency:      fetch.min.bytes=1,     fetch.max.wait.ms=10   (return ASAP)

per-poll record count = min(max.poll.records, buffered records)
buffered bytes governed by max.partition.fetch.bytes * #partitions, capped by fetch.max.bytes
```

The practical tuning insight: throughput problems on the consumer side are often *not* about adding consumers but about fetch efficiency — a consumer assigned 200 partitions with tiny `fetch.min.bytes` issues a storm of small fetches. Raise `fetch.min.bytes` and rely on `fetch.max.wait.ms` to bound latency. Conversely, `max.poll.records` is your lever for keeping each `poll()` fast enough to stay under `max.poll.interval.ms` — decouple "how much I fetch" (bytes) from "how much I process per loop" (records).

#### Q51. [Practical] How do you secure inter-service Kafka access with mTLS and ACLs end to end? Show the config.

End-to-end security means every connection is authenticated and every operation authorized, with nothing in plaintext on the wire. The two pillars here are **mTLS** (both broker and client present certificates, so the broker knows the client's identity from its cert's principal) and **ACLs** (deny-by-default authorization granting that principal specific operations on specific resources). You configure a `SSL` (or `SASL_SSL`) listener on the broker and require client auth.

```properties
# --- broker server.properties ---
listeners=SSL://0.0.0.0:9093
security.inter.broker.protocol=SSL
ssl.keystore.location=/etc/kafka/broker.keystore.jks
ssl.keystore.password=...
ssl.truststore.location=/etc/kafka/truststore.jks
ssl.truststore.password=...
ssl.client.auth=required                         # <- enforces mTLS
authorizer.class.name=org.apache.kafka.metadata.authorizer.StandardAuthorizer
allow.everyone.if.no.acl.found=false             # <- deny-by-default
super.users=User:CN=admin,OU=ops
```

```properties
# --- client (producer/consumer) ---
security.protocol=SSL
ssl.keystore.location=/etc/app/payments-service.keystore.jks  # client identity
ssl.keystore.password=...
ssl.truststore.location=/etc/app/truststore.jks
ssl.truststore.password=...
```

```bash
# Grant the payments-service principal least-privilege access:
kafka-acls.sh --bootstrap-server localhost:9093 \
  --add --allow-principal "User:CN=payments-service,OU=svc" \
  --producer --topic payments        # Write+Describe on topic payments
kafka-acls.sh --bootstrap-server localhost:9093 \
  --add --allow-principal "User:CN=payments-service,OU=svc" \
  --consumer --topic payments --group payments-group
```

The principal the broker authorizes is derived from the **certificate's Distinguished Name** (you can remap it with `ssl.principal.mapping.rules` to extract just the CN). Hardening details that matter in practice: set `allow.everyone.if.no.acl.found=false` (the deny-by-default that most "we got breached" stories lack), give each service its **own cert** so you can revoke one without rotating all, keep `super.users` tiny and audited, lock down the inter-broker listener separately, and don't forget that **Connect, Streams, and Schema Registry** are themselves Kafka clients needing their own principals and ACLs — an over-privileged Connect cluster is a classic lateral-movement path. Rotate certs before expiry and monitor `FailedAuthentication` metrics for misconfigured or hostile clients.

#### Q52. [Theory] What is the transaction coordinator, zombie fencing, and the `epoch` mechanism in Kafka transactions?

The **transaction coordinator** is a broker-side component (one per `transactional.id`, located via a hash into the internal `__transaction_state` topic) that manages the lifecycle of transactions: it assigns the PID, tracks which partitions a transaction has touched, and writes the `COMMIT`/`ABORT` **control markers** into the data partitions so that `read_committed` consumers know which records to expose. When a transactional producer calls `commitTransaction()`, the coordinator writes a prepare record, then markers to every involved partition, then a final commit record — a two-phase commit driven from the coordinator.

**Zombie fencing** solves the split-brain problem: imagine instance A of a service stalls (long GC), the orchestrator assumes it's dead and starts instance B with the **same** `transactional.id`, then A wakes up and tries to commit. Without fencing, both could write, double-processing data. Kafka prevents this with an **epoch**: each time a producer calls `initTransactions()` with a given `transactional.id`, the coordinator bumps the epoch for that id. The broker only accepts writes/commits from the **highest epoch seen**; A's stale epoch is now rejected with `ProducerFencedException`, killing the zombie.

```
transactional.id = "txn-orders-1"
  instance A: initTransactions() -> epoch 5, starts a txn...
  (A stalls; orchestrator starts B)
  instance B: initTransactions() -> epoch 6  (coordinator fences epoch 5)
  A wakes, commitTransaction() with epoch 5 -> ProducerFencedException -> A must die
```

This is why the `transactional.id` must be **stable per logical producer instance** (not random per process, or fencing can't work; not shared across truly concurrent producers, or they fence each other). When you catch `ProducerFencedException`, the correct action is to **close the producer and shut down that instance** — it has been superseded. The epoch + coordinator design is what lets Kafka offer exactly-once across consume-process-produce even amid the messy reality of slow JVMs, restarts, and orchestrators that declare things dead prematurely.

#### Q53. [Practical] A broker is showing high under-replicated partitions and rising request latency. Walk through your diagnosis.

Under-replicated partitions (URP > 0) mean some followers have fallen out of the ISR — replication can't keep up — and it's one of the two or three most important red-alert metrics. Rising request latency alongside it usually points to a resource saturation on one or more brokers. My diagnosis goes top-down from "which broker(s)" to "which resource."

First, **localize**: `kafka-topics.sh --describe --under-replicated-partitions` shows exactly which partitions and which broker is the lagging replica or the overloaded leader. If URPs cluster on one broker, that broker is likely the problem (hot, dying disk, or GC); if they're spread evenly, it's cluster-wide load or a network issue. Check whether a broker recently restarted (catching up is normal and transient) versus persistently behind (a real problem).

```bash
# Where are the under-replicated partitions?
kafka-topics.sh --bootstrap-server localhost:9092 --describe --under-replicated-partitions

# Key broker JMX metrics to pull (via your monitoring):
#   UnderReplicatedPartitions, IsrShrinksPerSec / IsrExpandsPerSec
#   RequestHandlerAvgIdlePercent  (low = request threads saturated)
#   NetworkProcessorAvgIdlePercent
#   TotalTimeMs for Produce/FetchFollower (where is latency spent?)
#   LogFlushRateAndTimeMs (disk slow?)
```

Then **find the bottleneck resource**: disk (iostat — a degraded disk or full disk slows appends and replication; `RequestHandlerAvgIdlePercent` near zero means I/O threads are blocked), network (a replication-heavy reassignment without a throttle is a classic cause), CPU/GC (long JVM pauses drop the broker out of ISR coordination), or page-cache pressure (too-small RAM forcing disk reads). Common root causes I rule in/out: an **unthrottled partition reassignment** saturating the network, a **failing disk** on one broker, **GC pauses** from too-large a heap, or simply **insufficient capacity** for current throughput. Remediation matches the cause: throttle/pause the reassignment, fail the bad disk out and reassign its partitions, fix GC/heap sizing, or add brokers. The meta-point in an interview: name the *specific metrics* you'd look at and the decision tree, not just "I'd check the logs."

### 🔴 Expert — extended

#### Q54. [Theory] Compare Kafka with Pulsar and with traditional brokers (RabbitMQ) at an architectural level. When does each win?

The deepest architectural difference is **storage coupling**. Kafka couples compute and storage on the broker — a broker owns its partition data on local disk and serves it, which gives the zero-copy, page-cache fast path but means scaling storage and compute together and makes rebalancing data-heavy. **Pulsar** separates them: brokers are stateless serving nodes and storage lives in **BookKeeper** bookies, so you can scale storage independently, and broker failover moves no data (a broker just picks up serving a ledger). Pulsar also has **tiered storage and multi-tenancy** built in from day one and supports both streaming and queue semantics natively. The cost is more moving parts (ZooKeeper + bookies + brokers historically) and a smaller ecosystem.

**RabbitMQ** (and classic brokers) is a different category entirely: a **smart broker, dumb consumer** model with per-message acknowledgment, flexible routing (exchanges, bindings, topic/fanout/direct), priorities, per-message TTL, and delayed delivery. It's optimized for **task queues and complex routing**, not for replayable high-throughput logs. Messages are typically removed after ack; there's no native long-retention replay.

```
                Kafka                 Pulsar                RabbitMQ
model           partitioned log       segmented log/ledger  queue + exchanges
storage         broker-local disk     BookKeeper (separate) broker memory/disk
replay          first-class           first-class           not native
routing         partition by key      partition/subscription rich (exchanges)
per-msg ack     no (offset-based)     yes (cursor + ack)    yes
priority/TTL    awkward               TTL yes               native
sweet spot      high-throughput       multi-tenant +        task queues,
                event streaming       elastic storage       routing, RPC
```

When each wins: **Kafka** for high-throughput, ordered, replayable event streams and a rich stream-processing ecosystem (Streams, Connect, ksqlDB) — the default for an event backbone. **Pulsar** when you need elastic independent scaling of storage, strong multi-tenancy/geo-replication out of the box, or both queue and stream semantics in one system. **RabbitMQ** when you need per-message routing, priorities, TTL/delayed delivery, and per-message acks for task distribution at modest volume. The senior answer resists "Kafka for everything" — the storage-compute coupling and the offset-vs-ack model are the axes that actually decide the fit.

#### Q55. [Practical] Design a GDPR-compliant "right to be forgotten" strategy on an append-only log. (Case study)

The core tension is that GDPR requires erasing an individual's personal data on request, but Kafka's log is **append-only and immutable** — you can't reach in and delete record #45,123. There are three viable strategies, and mature organizations usually combine them.

**(1) Compaction + tombstones.** If the topic is keyed by the subject's identifier and uses `cleanup.policy=compact`, you publish a **tombstone** (a record with the same key and a `null` value); compaction eventually removes all prior values for that key, and the tombstone itself is retained for `delete.retention.ms` so downstream consumers observe the deletion and purge their own materialized state. This works cleanly only when one key == one data subject and the topic is compacted.

**(2) Crypto-shredding (the scalable answer).** Encrypt each subject's personal fields with a **per-subject key** stored in an external key vault, and write only ciphertext to Kafka. To "forget" someone, you **delete their key** — the ciphertext remains in the immutable log but becomes permanently unreadable, which regulators accept as erasure. This avoids fighting the log's immutability entirely and scales to billions of records because deletion is O(1) key removal, not a log rewrite.

```
Producer:  PII fields -> encrypt(subjectKey[id]) -> ciphertext in Kafka record
Forget(id): vault.deleteKey(subjectKey[id])  -> all that subject's ciphertext is now garbage
            (also tombstone keyed topics + purge downstream stores)
```

**(3) Short retention + downstream as source of truth.** Keep PII topics on **short retention** so raw events age out naturally, treat a compacted/queryable store (with real deletes) as the authoritative copy, and use Kafka only as transport. The architectural decisions that make this tractable: **don't put raw PII in long-retention event topics** in the first place (tokenize or encrypt at the edge via a Connect SMT or producer interceptor), keep a **registry of where each subject's data flows** (Kafka is a fan-out — a delete must propagate to every Streams store, Connect sink, S3 lake, and search index), and document the **erasure lag** (compaction and downstream purge aren't instantaneous, so define an SLA acceptable to your DPO). The trap interviewers probe: naively trying to delete individual records from the log — it fights the design; crypto-shredding plus disciplined data minimization is the real answer.

#### Q56. [Theory] How does Kafka's quorum-based replication (ISR model) differ from Raft/Paxos majority quorums, and what are the trade-offs?

Most consensus systems (Raft, Paxos, and Kafka's own KRaft metadata layer) use **majority quorums**: a write is committed once a majority (e.g. 2 of 3, 3 of 5) acknowledges, and they tolerate `f` failures with `2f+1` nodes. Kafka's *data* replication deliberately does **not** use a majority quorum — it uses the **ISR (In-Sync Replica)** model, where a write is committed once **all replicas currently in the ISR** acknowledge it, and the ISR can shrink dynamically as slow replicas drop out.

The key consequence: with majority quorums, to tolerate `f` failures you need `2f+1` replicas (tolerate 2 failures = 5 replicas = 5x storage). With Kafka's ISR + `min.insync.replicas`, to tolerate `f` failures with no data loss you need only `f+1` in-sync replicas (tolerate 2 failures = 3 replicas with `min.insync.replicas=... ` tuned). This is far more **storage- and write-efficient** for a high-volume data log — you pay for `f+1` copies, not `2f+1`. The price is that Kafka separates the *commit* decision (all of ISR) from *leader election*, and relies on the controller (now itself a Raft quorum in KRaft) to manage the ISR membership and elect leaders only from the ISR.

```
tolerate 2 failures, no committed-data loss:
  Raft/Paxos majority:  need 5 replicas (commit on 3-of-5)
  Kafka ISR model:      need 3 replicas, min.insync.replicas=2... actually f+1 in ISR

ISR commit:  write acked when ALL of current ISR have it (not a fixed majority)
             ISR shrinks under lag -> still durable, but availability config matters
```

The trade-offs: the ISR model is more efficient and tolerant of *transient* slowness (a lagging replica just leaves the ISR rather than blocking the majority), but it pushes complexity into ISR-membership management and creates the **unclean-leader-election dilemma** — if the entire ISR is lost you must choose between availability (elect a stale replica, lose data) and consistency (stay offline). Pure majority quorums sidestep that specific dilemma but cost more replicas. Notably, Kafka uses *both* models in one system: ISR for bulk data (efficiency-optimized) and Raft (KRaft) for metadata (correctness-critical, low-volume) — a deliberate split that matches each mechanism to its workload.

#### Q57. [Practical] How would you debug "phantom" duplicate messages appearing downstream despite running with exactly-once configured?

"EOS is on but we still see duplicates" is a classic senior debugging scenario, and the resolution is almost always that **the duplicates are introduced outside Kafka's EOS boundary**, or EOS isn't actually fully configured. My method is to first establish *where* the duplication occurs, then check each link.

Step one: determine if the duplicate exists **in the Kafka output topic** or only **in the downstream sink/database**. Read the output topic with a `read_committed` console consumer and check for genuine duplicate records (same key, same payload, different offsets). If the topic is clean but the database has dupes, the problem is the **sink**, not Kafka — Kafka EOS guarantees the consume-process-produce loop, but a non-idempotent sink writing to Postgres can still double-write if it commits to the DB and then crashes before committing the Kafka offset. The fix is an idempotent sink (upsert by key) or a transactional outbox, not more Kafka config.

```bash
# Is the duplicate actually in the topic, or only downstream?
kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic output \
  --isolation-level read_committed --from-beginning \
  --property print.offset=true --property print.key=true | sort | uniq -c | sort -rn
```

If the duplicates *are* in the topic, check the EOS configuration for the common defects: a **consumer not set to `isolation.level=read_committed`** (so it reads aborted/uncommitted records), a **`transactional.id` that's random per restart** (so fencing never engages and a restarted instance re-emits), **mixing a transactional producer with manual `enable.auto.commit=true`** offset commits (offsets escape the transaction), or **sharing one transactional producer across threads**. Another subtle source is an **upstream** producer that wasn't idempotent feeding the input topic, so the "duplicates" were already in the input. And remember that with at-least-once *anywhere* in a multi-hop pipeline, EOS on one hop doesn't make the whole chain exactly-once. The disciplined answer: locate the duplication boundary first, verify `read_committed` + stable `transactional.id` + offsets-in-transaction, and treat the sink as the prime suspect when the topic itself is clean.

#### Q58. [Theory] What are the operational and consistency implications of `min.insync.replicas`, and how does it interact with `acks=all` and availability?

`min.insync.replicas` (often "min ISR") is a **topic/broker config** specifying the minimum number of in-sync replicas that must acknowledge a write for it to be considered committed — but it only takes effect with `acks=all`. The interaction is the heart of Kafka's durability-vs-availability tuning: `acks=all` means "wait for all *current* ISR members," and `min.insync.replicas` puts a **floor** on how small that ISR is allowed to get before the broker **refuses writes** with `NotEnoughReplicasException`.

The canonical safe configuration is `replication.factor=3`, `min.insync.replicas=2`, `acks=all`. This means: a write needs at least 2 in-sync replicas to succeed, so you can lose **one** broker and still accept writes (2 remain), and because every committed write is on at least 2 replicas, losing one broker never loses committed data. If a *second* broker fails (ISR drops to 1, below the min of 2), the partition becomes **write-unavailable** — producers get `NotEnoughReplicasException` — which is the system correctly choosing **consistency over availability**: it would rather reject writes than accept data it can't durably replicate.

```
RF=3, min.insync.replicas=2, acks=all
  ISR = {b1,b2,b3}  -> writes OK, tolerate 1 loss with no data loss
  ISR = {b1,b2}     -> writes OK (>= 2)
  ISR = {b1}        -> writes REJECTED (NotEnoughReplicasException) until a replica catches up
```

The common misconfigurations: setting `min.insync.replicas = replication.factor` (e.g. both 3) means **any single broker failure halts writes** — too brittle; setting `min.insync.replicas=1` with `acks=all` is **silently as weak as `acks=1`** because a single-member ISR satisfies it, so a leader crash can still lose data. The operational implication is that this config is a deliberate **availability trade**: with `min.insync.replicas=2` you accept that a double failure makes the partition read-only rather than risk data loss, and you size `replication.factor=3` precisely so normal single-broker failures never trip the floor. It must be set per durability-critical topic (and on internal topics like `__consumer_offsets`), not left at the default of 1.

#### Q59. [Practical] You're tasked with reducing a 50-broker cluster's storage cost by 60% without losing data or breaking consumers. What levers do you pull?

Storage cost on Kafka is `sum over topics of (avg throughput × retention × replication.factor)` plus the local-disk premium, so I'd attack each factor systematically, measuring first. Step zero is **profiling**: use `kafka-log-dirs.sh` and per-topic size metrics to find the heavy hitters — typically a handful of topics dominate, and blanket changes are wasteful.

```bash
# Find the storage hogs per broker/topic before changing anything
kafka-log-dirs.sh --bootstrap-server localhost:9092 --describe \
  --topic-list big-topic,other-topic | jq '...'   # sizes per partition/broker
```

The levers, roughly in order of impact-to-risk:

**(1) Tiered Storage (KIP-405) — usually the biggest win.** Offload aged segments to S3/GCS, keeping only the hot window on broker disks. For log-heavy, replay-occasionally workloads (event sourcing, audit, ML features) this can cut local storage 70–90% because object storage is ~5–10x cheaper per GB and isn't multiplied by `replication.factor` the same way. Consumers reading recent data are unaffected; only rare historical reads hit the remote tier with higher latency.

**(2) Retention review.** Audit `retention.ms` per topic — teams routinely set 7-day or 30-day retention "to be safe" on data nobody replays past a day. Right-sizing retention is free storage and breaks nothing if you confirm no consumer reads that far back (check the oldest committed offsets across all groups first).

**(3) Compression.** Ensure producers use `zstd` (better ratio than lz4/snappy/gzip); for already-running topics this only affects new data, but on text/JSON payloads zstd commonly yields 3–5x reduction versus uncompressed and beats older codecs. Verify the broker isn't recompressing.

**(4) Compaction where appropriate.** For changelog-style topics (latest-value-per-key), switching to `cleanup.policy=compact` (or `compact,delete`) collapses superseded versions, often a large reduction for CDC/state topics.

**(5) Replication-factor scrutiny (carefully).** RF directly multiplies storage; some non-critical topics may run RF=2 instead of 3 — but this trades durability and is the riskiest lever, so reserve it for genuinely low-value data and never for the durability-critical core.

The disciplined plan: profile to find the dominant topics, apply Tiered Storage + retention right-sizing + zstd to those (the safe high-impact trio), validate no consumer reads beyond the new retention, and roll changes per-topic with monitoring on consumer lag and historical-read latency. The 60% target is very achievable mostly from Tiered Storage and retention hygiene alone, without touching durability — which is the answer that shows judgment versus the naive "drop replication factor everywhere."

#### Q60. [Theory] Explain rack awareness, follower fetching, and how Kafka minimizes cross-AZ traffic costs in the cloud.

In a cloud deployment, **cross-AZ network traffic is billed** and is often a surprisingly large fraction of a Kafka bill, because by default every produce and consume crosses AZs whenever the client and the partition leader happen to be in different zones. Kafka offers two complementary mechanisms to manage this. **Rack awareness** (`broker.rack`, set to the AZ id) makes the controller spread a partition's replicas across **different racks/AZs**, so a whole-AZ outage can't take out all replicas of a partition — a correctness/availability win independent of cost.

**Follower fetching** (KIP-392, "fetch from closest replica") addresses the cost side: normally all consumer reads go to the partition **leader**, which may be in another AZ. With follower fetching, a consumer can read from the **in-sync follower in its own rack/AZ**. You set `client.rack` on the consumer to its AZ, and configure the broker's `replica.selector.class` to the rack-aware selector; the consumer is then directed to a local replica, eliminating cross-AZ read charges. Crucially this is still consistent — the consumer only reads up to the **high-water mark**, which the follower knows, so it never sees uncommitted data.

```
3 AZs, RF=3, leader in AZ-a:
  Without follower fetching: consumer in AZ-c reads from leader in AZ-a  -> cross-AZ $$$
  With follower fetching + client.rack=AZ-c: reads from the AZ-c replica  -> local, free
  (still bounded by HWM, so consistency preserved)
```

The trade-offs and limits: follower fetching only helps the **read** path — produces still go to the leader (writes can't be redirected), and inter-broker **replication** traffic still crosses AZs by necessity (that's the price of cross-AZ durability). So the cost model becomes "pay for replication + producer cross-AZ, save on consumer cross-AZ," which is a big win for read-heavy fan-out topics with many consumer groups. Operationally, rack awareness should always be on in multi-AZ clusters for availability; follower fetching is the deliberate cost optimization you add when cross-AZ read egress shows up in the bill. A subtlety: reading from a follower can have slightly higher latency and the follower must be in-sync, so during ISR shrink the consumer transparently falls back to the leader.

## 🧩 Extended Questions — Supplemental Set: Mixed Depth

### 🟢 Basic — extended

#### Q61. [Theory] What are Kafka record headers and when should you use them?

A Kafka record has a key, a value, a timestamp, and — since the 0.11 message format — an optional list of **headers**: small `(String, byte[])` pairs attached to the record alongside the payload. Headers carry **metadata about the message** that should travel with it but isn't part of the business value: a content-type, a schema version, a correlation/trace id for distributed tracing, the originating service, a tenant id for multi-tenant routing, or the diagnostic context for a dead-letter record. The point is to keep the *value* clean (just the domain event) while still propagating cross-cutting concerns.

The practical reason headers matter is that consumers and infrastructure (interceptors, Connect SMTs, stream processors) can read them **without deserializing the whole value**, which is cheaper and decouples routing/observability from the payload schema. Distributed tracing systems (OpenTelemetry, Brave) inject the trace context into headers so a trace spans producer → topic → consumer. The trap is over-using them: headers aren't indexed or queryable, they add per-record overhead, and putting business data in a header (rather than the value) hides it from schema governance and most tooling.

```java
ProducerRecord<String, byte[]> rec = new ProducerRecord<>("orders", key, value);
rec.headers()
   .add("content-type", "application/avro".getBytes())
   .add("schema-version", "3".getBytes())
   .add("trace-id", traceId.getBytes());
producer.send(rec);

// consumer side — read without touching the value
Header h = record.headers().lastHeader("trace-id");
String traceId = h == null ? null : new String(h.value());
```

Headers can repeat (a key can appear multiple times); use `lastHeader(key)` when you expect one. Keep them small — they're copied into every record and count against `max.message.bytes`.

#### Q62. [Practical] What does `ProducerRecord`'s callback / returned `RecordMetadata` give you, and how do you use it?

When you call `producer.send(record, callback)`, the send is **asynchronous**: it returns immediately (actually a `Future<RecordMetadata>`), and the broker's acknowledgment arrives later on the producer's I/O thread, at which point your callback fires with either a `RecordMetadata` (success) or an `Exception` (failure). `RecordMetadata` tells you exactly where the record landed — `topic()`, `partition()`, `offset()`, and the broker-assigned `timestamp()` — which is essential for logging, audit trails, correlating a produced event with its log position, or confirming which partition a key hashed to.

The callback is the right place to handle per-record outcomes because the `Future.get()` alternative **blocks** and destroys throughput. Use the async callback for fire-and-forward-with-logging, and reserve `get()` for cases where you genuinely must produce synchronously (e.g. a request/response API that can't return until the event is durable).

```java
producer.send(record, (RecordMetadata meta, Exception ex) -> {
    if (ex != null) {
        // Distinguish retriable (already retried internally) from fatal
        log.error("send failed for key {}: {}", record.key(), ex.toString());
        // route to a fallback / alert; do NOT block here
    } else {
        log.debug("ok topic={} p={} off={} ts={}",
            meta.topic(), meta.partition(), meta.offset(), meta.timestamp());
    }
});
```

Two gotchas: (1) the callback runs on the **single I/O thread**, so never do slow/blocking work in it — you'll stall every other send; hand off to a queue if needed. (2) Callbacks for records sent to the **same partition** are invoked in send order (guaranteed), but across partitions there's no ordering. If `offset() == -1`, the record was sent with `acks=0` so no offset was assigned.

#### Q63. [Theory] What is the difference between `CreateTime` and `LogAppendTime` for record timestamps?

Every Kafka record carries a timestamp, but its **meaning** depends on the topic's `message.timestamp.type` config. With **`CreateTime`** (the default), the timestamp is set by the **producer** — either explicitly in the `ProducerRecord` or, if omitted, the producer's wall clock at send time. This is **event time**: it reflects when the event actually happened (or was produced), which is what stream processing, windowing, and time-based retention should usually key off. With **`LogAppendTime`**, the **broker overwrites** the timestamp with its own clock when it appends the record to the log. This is **ingestion time**: monotonic per partition and immune to producer clock skew, but it loses the original event time.

The choice matters for several behaviors. Kafka Streams' event-time windowing, `offsetsForTimes()` lookups, and time-based retention (`retention.ms` is evaluated against the record timestamp) all read this timestamp. If you need correct event-time semantics (late data, windowed aggregations), keep `CreateTime` and make producers stamp the true event time. If you don't trust producer clocks and only care about ingestion order/age, `LogAppendTime` is safer but means a delayed/replayed producer can't express "this event is from yesterday."

```
CreateTime:     producer sets ts = when event occurred  -> event-time semantics
LogAppendTime:  broker overwrites ts = when appended    -> ingestion-time, no skew

retention.ms and offsetsForTimes() both use whichever timestamp is on the record.
```

A classic bug: a backfill job replays year-old data with `CreateTime` timestamps, and a topic with short `retention.ms` immediately deletes it because the records "look" expired. Either use `LogAppendTime` for backfill topics or be aware retention is timestamp-based.

#### Q64. [Practical] How do you create and configure topics programmatically with the AdminClient?

The `AdminClient` (Java) / `kafka-topics.sh` (CLI) is the management API for topics, configs, ACLs, consumer groups, and partitions. Creating topics in code (rather than relying on auto-creation) is the production-correct approach because **auto-topic-creation** (`auto.create.topics.enable`) makes topics with default partition count and replication factor — usually wrong, and a source of silent RF=1 topics. Explicit creation lets you set partitions, replication factor, and per-topic configs as a deliberate, version-controlled decision.

```java
import org.apache.kafka.clients.admin.*;
import java.util.*;
import java.util.concurrent.ExecutionException;

try (Admin admin = Admin.create(Map.of(
        AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092"))) {

    NewTopic orders = new NewTopic("orders", 12, (short) 3)  // 12 partitions, RF=3
        .configs(Map.of(
            "retention.ms", "604800000",        // 7 days
            "min.insync.replicas", "2",
            "cleanup.policy", "delete",
            "compression.type", "producer"));    // keep producer's codec

    CreateTopicsResult result = admin.createTopics(List.of(orders));
    try {
        result.all().get();                       // block until created
    } catch (ExecutionException e) {
        if (e.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException) {
            // idempotent startup: fine if it already exists
        } else throw e;
    }
}
```

**Edge cases:** `createTopics` is async — `result.all().get()` surfaces failures (e.g. `InvalidReplicationFactorException` if RF > broker count). For idempotent app startup, catch `TopicExistsException`. To change an existing topic you don't recreate it — use `admin.incrementalAlterConfigs(...)` for configs and `admin.createPartitions(...)` to add partitions (never to remove). Disable broker-side auto-creation in production and own topic provisioning explicitly (often via Terraform/GitOps rather than app code) so topic specs are reviewed and reproducible.

### 🟡 Intermediate — extended

#### Q65. [Theory] How does the default (sticky) partitioner choose a partition, and how did it change from the old round-robin behavior?

The default partitioner picks a partition by three rules, in order. **(1)** If the record has an explicit partition, use it. **(2)** If it has a key, hash the key (murmur2) modulo the partition count — this is what gives stable per-key ordering. **(3)** If the key is `null`, choose a partition for load balancing. It's rule (3) that changed significantly. The **old** `DefaultPartitioner` round-robined *every record* across partitions, which scattered records from one `send()` burst across many tiny per-partition batches — defeating batching and hurting throughput and compression.

The **sticky partitioner** (KIP-480, default since 2.4) fixes this for null-key records: it picks **one partition and sticks to it until the current batch is full or `linger.ms` elapses**, then switches to another partition. Over many batches the load is still spread evenly, but within a batch all records go to one partition, so batches fill properly. The result is larger batches, fewer requests, better compression, and measurably lower latency under load — without sacrificing balance over time.

```
Old round-robin (null key):  P0 P1 P2 P0 P1 P2 ...  -> 6 tiny batches
Sticky (null key):           P1 P1 P1 P1 (batch full) -> P2 P2 P2 ... -> few full batches
                             (still balanced across partitions over time)
```

Kafka 3.3+ refined this further with the **uniform sticky** / built-in partitioner that also accounts for **partition load** (avoiding slow brokers). The practical implication: for keyed records the partitioner is deterministic (so re-keying or changing partition count moves keys), and for keyless high-throughput firehoses you should rely on the built-in sticky behavior rather than writing a custom round-robin, which would reintroduce the small-batch problem.

#### Q66. [Coding] Implement a consumer that seeks to a specific timestamp using `offsetsForTimes`.

**Problem:** Reprocess everything a consumer group received since a given wall-clock time (e.g. "replay from 2 AM during the incident") without resetting to `earliest`.

```java
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.*;
import java.time.*;
import java.util.*;

public class SeekByTime {
    static void seekToTime(KafkaConsumer<String, String> consumer,
                           String topic, Instant from) {
        // Build a map of partition -> target timestamp (epoch millis)
        List<PartitionInfo> parts = consumer.partitionsFor(topic);
        Map<TopicPartition, Long> query = new HashMap<>();
        List<TopicPartition> tps = new ArrayList<>();
        for (PartitionInfo pi : parts) {
            TopicPartition tp = new TopicPartition(topic, pi.partition());
            tps.add(tp);
            query.put(tp, from.toEpochMilli());
        }

        consumer.assign(tps);                 // manual assignment for deterministic seek
        Map<TopicPartition, OffsetAndTimestamp> found = consumer.offsetsForTimes(query);

        for (TopicPartition tp : tps) {
            OffsetAndTimestamp ot = found.get(tp);
            if (ot != null) {
                consumer.seek(tp, ot.offset());          // first offset at/after timestamp
            } else {
                consumer.seekToEnd(List.of(tp));         // no record after that time -> tail
            }
        }
    }
}
```

`offsetsForTimes` uses the partition's **`.timeindex`** to binary-search for the first offset whose timestamp is `>= ` the requested time, returning `null` for partitions with no such record (all records older than the target), in which case you decide whether to skip or seek to end. **Edge cases:** the lookup uses the record *timestamp*, so `CreateTime` vs `LogAppendTime` determines what "since 2 AM" means; if producers stamp event time, you're seeking by event time. This is far better than `--reset-offsets --to-datetime` when you want it programmatically inside the app, and it works per partition so you can replay a subset. **Complexity:** O(log n) per partition via the time index.

#### Q67. [Practical] How do you offload slow processing to a worker pool using `pause()`/`resume()` without losing the group membership?

The single-threaded `poll()` loop creates a tension: you must call `poll()` within `max.poll.interval.ms` to stay in the group, but your per-record work may be slow (an external API call). Naively processing inline blows the poll interval and triggers a rebalance. The clean pattern is to **hand records to a worker pool**, **`pause()`** the partitions you've dispatched so `poll()` returns no new records (but still heartbeats and keeps membership), keep calling `poll()` to stay alive, and **`resume()`** once the workers drain — committing offsets only for completed work.

```java
ExecutorService pool = Executors.newFixedThreadPool(8);
Map<TopicPartition, Long> inFlightTo = new HashMap<>();  // highest dispatched offset+1

while (running) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));
    if (!records.isEmpty()) {
        for (TopicPartition tp : records.partitions()) {
            List<ConsumerRecord<String,String>> recs = records.records(tp);
            pool.submit(() -> recs.forEach(this::process));   // slow work off-thread
            inFlightTo.put(tp, recs.get(recs.size()-1).offset() + 1);
        }
        consumer.pause(records.partitions());                 // stop fetching more
    }
    // Resume partitions whose workers have finished; commit their completed offsets
    Map<TopicPartition, OffsetAndMetadata> done = collectCompleted();
    if (!done.isEmpty()) {
        consumer.commitSync(done);
        consumer.resume(done.keySet());
    }
}
```

The reason this works: `poll()` is what sends heartbeats *and* resets the `max.poll.interval.ms` timer, and a *paused* partition still counts as polled — so you keep your assignment indefinitely while the pool churns. The hard part is **offset accounting**: you can only commit an offset once *all* records below it have succeeded, so you must track completion per partition and commit the lowest contiguous completed offset (out-of-order completion means you can't just commit the highest). **Trade-off:** you've reintroduced ordering and at-least-once complexity — a record can be reprocessed if you crash after dispatch but before commit, so `process()` must stay idempotent. This is essentially what Spring Kafka's async/`ConcurrentMessageListenerContainer` and the Confluent Parallel Consumer library do for you.

#### Q68. [Theory] What are Kafka quotas and how do they protect a multi-tenant cluster?

Quotas are broker-enforced rate limits that prevent one client (or tenant) from monopolizing cluster resources — the noisy-neighbor defense for a shared cluster. There are two kinds. **Network/bandwidth quotas** cap produce throughput (`producer_byte_rate`) and fetch throughput (`consumer_byte_rate`) in bytes/sec. **Request quotas** (`request_percentage`) cap the share of broker request-handler and network-thread time a client may consume, which protects against a client that issues a storm of small or expensive requests even at low byte volume. Quotas are applied per **(user, client-id)**, per user, or per client-id, so you can throttle a specific tenant or a specific application.

The enforcement mechanism is **throttling, not rejection**: when a client exceeds its quota, the broker computes a delay and **holds the response** (and, in newer versions, mutes the channel) for that long, so the client naturally slows down without errors or data loss. The client sees increased latency and exposes a `produce/fetch-throttle-time` metric. This graceful backpressure is why quotas are safe to apply to live traffic — a throttled producer just slows, it doesn't fail.

```bash
# Limit a tenant's producers to 50 MB/s and consumers to 100 MB/s
kafka-configs.sh --bootstrap-server localhost:9092 --alter \
  --add-config 'producer_byte_rate=52428800,consumer_byte_rate=104857600' \
  --entity-type users --entity-name tenant-a

# Cap a chatty client's request-handler share to 200% (2 of N threads)
kafka-configs.sh --bootstrap-server localhost:9092 --alter \
  --add-config 'request_percentage=200' \
  --entity-type clients --entity-name batch-loader
```

The senior point: quotas are essential for **multi-tenant** or shared-platform Kafka where teams self-serve topics, and they pair with ACLs (authorization) and naming conventions (client-id discipline). Without them, one team's runaway backfill can starve every other workload's latency. Set a sensible **default** quota for all clients and raise it for vetted high-throughput jobs, rather than leaving the cluster unbounded.

#### Q69. [Coding] Write a Spring Kafka `@KafkaListener` with manual ack, error handling, and concurrency.

**Problem:** Consume `orders` with N concurrent threads, acknowledge manually after success, retry transient failures with backoff, and route poison messages to a DLQ.

```java
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.context.annotation.*;
import org.springframework.kafka.annotation.*;
import org.springframework.kafka.config.*;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.util.backoff.FixedBackOff;
import java.util.*;

@Configuration
@EnableKafka
public class OrderConsumerConfig {

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> factory(
            ConsumerFactory<String, String> cf, KafkaTemplate<String, String> template) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(cf);
        factory.setConcurrency(4);                       // 4 consumer threads in the group
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);

        // Retry 3x with 2s backoff, then publish to <topic>.DLT and move on
        var recoverer = new DeadLetterPublishingRecoverer(template);
        var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(2000L, 3));
        handler.addNotRetryableExceptions(IllegalArgumentException.class); // poison -> straight to DLT
        factory.setCommonErrorHandler(handler);
        return factory;
    }
}

@org.springframework.stereotype.Component
class OrderListener {
    @KafkaListener(topics = "orders", groupId = "order-processors")
    public void onMessage(String value, Acknowledgment ack) {
        process(value);    // throws RuntimeException -> handled by DefaultErrorHandler
        ack.acknowledge(); // commit offset only after success
    }
    private void process(String v) { /* idempotent business logic */ }
}
```

**Why this shape:** `setConcurrency(4)` spins up four consumers in the same group, so the container scales to four partitions automatically. `AckMode.MANUAL` plus `ack.acknowledge()` gives at-least-once with explicit commit-after-success. The `DefaultErrorHandler` centralizes retry/backoff and, via `DeadLetterPublishingRecoverer`, ships exhausted/poison records to `orders.DLT` with original-topic/partition/offset/exception headers added automatically. **Edge cases:** concurrency above the partition count just leaves extra threads idle; non-retryable exceptions (bad data) should be classified so you don't waste the retry budget; and the DLQ producer must itself be reliable (share the transactional `KafkaTemplate` if you need atomicity). This is the idiomatic Spring equivalent of the manual `pause`/DLQ patterns shown earlier, with the boilerplate handled by the framework.

#### Q70. [Practical] How do you handle messages larger than `max.message.bytes`, and what is the claim-check pattern?

Kafka enforces a maximum record size at several layers: the broker's `message.max.bytes` (default ~1MB), the topic-level `max.message.bytes`, the producer's `max.request.size`, and the consumer's `fetch.max.bytes` / `max.partition.fetch.bytes`. A record exceeding any of these is rejected (`RecordTooLargeException`) or won't be fetched, so large payloads need a deliberate strategy. Your three options are: raise the limits, compress, or stop putting big blobs in Kafka at all.

**Raising limits is the wrong default.** Large messages bloat the page cache, hurt the zero-copy fast path, inflate replication traffic, and one giant record can stall a partition. If you must support modestly larger records (say a few MB), you have to raise the limit **consistently** on broker, topic, producer, and consumer — a mismatch means producers succeed but consumers can't fetch, or vice versa. Compression (`zstd`) helps for compressible payloads and is essentially free to enable.

**The claim-check pattern is the scalable answer for genuinely large payloads** (images, PDFs, multi-MB documents). Write the blob to external object storage (S3/GCS) and publish only a **reference** (a URL/key plus metadata and a checksum) to Kafka. Consumers fetch the blob from object storage when needed. Kafka stays a fast, small-message event log; the heavy bytes live in a store built for them.

```
Claim-check:
  Producer:  put blob -> S3 (key = sha256)        Kafka record value:
             publish {bucket, key, size, sha256, contentType}   <- tiny reference

  Consumer:  read reference -> GET s3://bucket/key -> process blob
```

The trade-offs: the claim-check adds a second system in the write/read path (and its own failure/latency/lifecycle — you must garbage-collect orphaned blobs and align object-store retention with topic retention), and you lose Kafka's atomicity between the event and the blob (the blob upload and the Kafka publish aren't one transaction, so handle the ordering: upload first, then publish the reference, so a consumer never sees a reference to a missing blob). For the common case — events that are merely "a bit big" — prefer compression and modest limit increases; reserve the claim-check for true large-object workloads.

### 🟠 Advanced — extended

#### Q71. [Theory] What is a `ProducerInterceptor` / `ConsumerInterceptor` and what are legitimate uses (and abuses)?

Interceptors are plug-in hooks that let you observe and *mutate* records on the client side without changing application code. A **`ProducerInterceptor`** has `onSend()` (called before the record is serialized/partitioned — you can mutate or enrich it) and `onAcknowledgement()` (called when the broker acks or the send fails — good for metrics). A **`ConsumerInterceptor`** has `onConsume()` (called on the batch returned from `poll()` before the app sees it) and `onCommit()` (when offsets are committed). They're configured via `interceptor.classes` and chained in order.

Legitimate uses center on **cross-cutting concerns**: injecting/extracting distributed-tracing context into headers (this is how auto-instrumentation agents add Kafka spans without touching your code), emitting custom metrics (per-topic latency, payload sizes), enforcing org-wide tagging (tenant id, schema version), or audit logging. Because they sit transparently in the client, a platform team can ship a standard interceptor in a shared library and get observability across every app uniformly.

```java
public class TracingProducerInterceptor implements ProducerInterceptor<String, byte[]> {
    public ProducerRecord<String, byte[]> onSend(ProducerRecord<String, byte[]> r) {
        r.headers().add("trace-id", currentTraceId().getBytes());   // enrich, don't replace
        return r;
    }
    public void onAcknowledgement(RecordMetadata m, Exception e) { metrics.record(m, e); }
    public void close() {}
    public void configure(Map<String,?> c) {}
}
// props.put(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, TracingProducerInterceptor.class.getName());
```

The abuses to avoid: putting **business logic** in an interceptor (it becomes invisible, untestable magic — the record the app sent isn't what hits the topic), doing **blocking I/O** in `onSend`/`onConsume` (you stall the hot path), or **swallowing exceptions** — an interceptor that throws is logged and skipped, so a buggy interceptor silently stops enriching. Treat interceptors as observability/metadata plumbing owned by the platform, not as a place for application behavior. For routing/transform logic in pipelines, Connect SMTs or stream processors are the right layer.

#### Q72. [Practical] Explain replica fetcher tuning (`num.replica.fetchers`, `replica.fetch.*`) and when followers fall behind.

Followers replicate by running **replica fetcher threads** that pull from the leader exactly like a consumer does. The number of these threads per broker is `num.replica.fetchers` (default 1), and each thread handles the fetch for some set of (leader-broker) connections. On a busy broker with many partitions led by many other brokers, a single fetcher thread can become the bottleneck — it serializes replication for all partitions it owns, so followers fall behind, the ISR shrinks, and under-replicated partitions climb even though disk and network have headroom. Raising `num.replica.fetchers` (to, say, 4–8 on multi-core brokers) parallelizes replication across more threads.

The fetch-sizing knobs mirror the consumer ones: `replica.fetch.max.bytes` (per-partition data per fetch), `replica.fetch.response.max.bytes` (whole response), and `replica.fetch.wait.max.ms`. If these are too small relative to your record sizes, followers make many tiny fetches and can't keep up with a high-throughput leader; if too large, a fetch can hog memory. `replica.lag.time.max.ms` defines how long a follower can go without fully catching up before it's **ejected from the ISR** — too tight and transient blips cause ISR flapping (visible as `IsrShrinksPerSec`/`IsrExpandsPerSec` churn), too loose and a genuinely slow replica lingers in the ISR, weakening your durability guarantee.

```
Symptom: UnderReplicatedPartitions > 0 but disk/net not saturated, fetcher thread busy
Likely:  num.replica.fetchers too low -> one thread serializes replication
Fix:     raise num.replica.fetchers (e.g. 1 -> 4); re-check ISR shrink/expand rate

Symptom: ISR constantly shrinking/expanding (flapping)
Likely:  replica.lag.time.max.ms too tight for normal GC/IO jitter
Fix:     widen it modestly; investigate GC / disk latency on the lagging broker
```

The diagnostic discipline: distinguish *transient* lag (a broker just restarted and is catching up — normal, self-resolving) from *persistent* lag (a real bottleneck). When persistent, decide whether it's fetcher parallelism, fetch sizing, or a hardware problem (slow disk, network saturation, GC) on a specific broker, using the same top-down localization as any URP investigation. Replica fetcher tuning is most impactful right after expanding a cluster or on brokers leading an unusually large partition count.

#### Q73. [Coding] Implement a KStream-KTable join to enrich an event stream with reference data.

**Problem:** Enrich an `orders` stream with the customer's tier from a `customers` changelog (a `KTable`), emitting enriched orders to `orders-enriched`.

```java
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.*;
import java.util.Properties;

public class EnrichOrders {
    public static void main(String[] args) {
        Properties p = new Properties();
        p.put(StreamsConfig.APPLICATION_ID_CONFIG, "order-enricher-v1");
        p.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        p.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        p.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());

        StreamsBuilder b = new StreamsBuilder();

        // KTable: latest tier per customerId (compacted changelog -> a table)
        KTable<String, String> customers = b.table("customers");   // key = customerId

        // KStream of orders, keyed by customerId so the join co-partitions
        KStream<String, String> orders = b.stream("orders");       // key = customerId

        orders.join(customers,
                (orderValue, tier) -> orderValue + "|tier=" + (tier == null ? "UNKNOWN" : tier))
              .to("orders-enriched");

        KafkaStreams streams = new KafkaStreams(b.build(), p);
        Runtime.getRuntime().addShutdownHook(new Thread(streams::close));
        streams.start();
    }
}
```

The crucial correctness requirement is **co-partitioning**: a `KStream`-`KTable` join is *local* — each task joins the order partition against the **same-numbered** customer partition held in its local state store. That only works if both topics have the **same number of partitions** and use the **same key and partitioner** (both keyed by `customerId`). If they don't, the customer for a given order may live on a different task and the join silently misses (`null` tier). When the keys don't align you must **re-key** one side with `selectKey(...).repartition(...)` first, paying a repartition (network) cost.

**Semantics and edge cases:** a `KStream`-`KTable` join is **stream-driven** — it fires only when an *order* arrives, looking up the *current* table value (so a customer-tier update doesn't retroactively re-emit past orders; use a table-table join or a stream-stream windowed join for other semantics). An inner `join` drops orders whose customer isn't yet in the table (a startup race); `leftJoin` keeps them with a null tier (handled above). For large, fully-replicated reference data that every task needs regardless of partitioning, use a **`GlobalKTable`** instead (`b.globalTable("customers")`) — it's broadcast to every instance, removing the co-partitioning constraint at the cost of storing the full table on each node. **Complexity:** O(1) state lookup per order; state size is O(distinct customers per partition).

#### Q74. [Practical] How do you write reliable integration tests for Kafka code (Testcontainers / EmbeddedKafka)?

Unit tests with mocked clients miss the behaviors that actually break in production — serialization, partitioning, rebalances, offset commits, transactions — so Kafka code needs **integration tests against a real broker**. Two common approaches: **Testcontainers** spins up a real Kafka broker in Docker per test class (highest fidelity, exercises the real protocol and a real KRaft/broker), and **Spring's `EmbeddedKafka`** runs an in-JVM broker (faster startup, no Docker, slightly less production-like). Testcontainers is the modern default because it tests the actual broker image you run in prod and isn't tied to Spring.

```java
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class OrderPipelineIT {
    @org.testcontainers.junit.jupiter.Container
    static KafkaContainer kafka =
        new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @Test
    void produces_and_consumes_with_key() {
        String bs = kafka.getBootstrapServers();
        var pp = Map.of("bootstrap.servers", bs,
            "key.serializer", "org.apache.kafka.common.serialization.StringSerializer",
            "value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        try (var producer = new KafkaProducer<String,String>(new HashMap<>(pp))) {
            producer.send(new ProducerRecord<>("orders", "cust-1", "order#1")).get();
        }
        var cp = new HashMap<>(Map.of("bootstrap.servers", bs, "group.id", "it-grp",
            "auto.offset.reset", "earliest",
            "key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer",
            "value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer"));
        try (var consumer = new KafkaConsumer<String,String>(cp)) {
            consumer.subscribe(List.of("orders"));
            ConsumerRecords<String,String> recs = consumer.poll(Duration.ofSeconds(10));
            assertEquals(1, recs.count());
            var r = recs.iterator().next();
            assertEquals("cust-1", r.key());
        }
    }
}
```

The reliability pitfalls are mostly about **timing and isolation**. Kafka is asynchronous, so tests must **poll with a generous timeout** (or use Awaitility) rather than asserting immediately — a flaky test usually means it didn't wait for the record to be produced/replicated/fetched. Set `auto.offset.reset=earliest` so a fresh test group sees data produced before it subscribed. Use **unique topic/group names per test** (or a fresh container) to avoid cross-test contamination. For Streams topologies, prefer the **`TopologyTestDriver`** — it tests the topology deterministically *without any broker* (you pipe input records and read outputs synchronously), which is fast and ideal for logic, complemented by a few Testcontainers tests for the end-to-end wiring. The principle: test the real serialization/partitioning/commit behavior against a real broker, but isolate and wait deterministically.

#### Q75. [Theory] What is the request purgatory, and how do `acks=all` and fetch requests use delayed operations?

The **purgatory** is the broker's data structure for **delayed (parked) requests** — operations that can't be answered immediately and must wait for a condition. Two prominent users are `acks=all` produce requests and long-polling fetch requests. When a producer sends with `acks=all`, the leader appends the record but **can't acknowledge until all ISR followers have replicated it**; rather than blocking a request-handler thread, the leader parks the produce request in the purgatory and a handler thread is freed to do other work. When followers' fetch requests advance the high-water mark past the produce request's offset, the purgatory **completes** the parked request and the ack is sent.

Fetch requests use it symmetrically: a consumer (or follower) fetch with `fetch.min.bytes > 1` may not have enough data to satisfy immediately, so the broker parks it in the fetch purgatory until either `fetch.min.bytes` accumulates or `fetch.max.wait.ms` elapses, then completes it. This is what makes long-polling efficient — the broker doesn't busy-wait or return empty; it holds the request just long enough.

```
acks=all produce flow:
  leader appends record at offset X
  -> park produce request in produce-purgatory (waiting for ISR to reach X)
  followers fetch, replicate up to X, HWM advances to X
  -> purgatory completes the request -> ack sent to producer

fetch with min.bytes:
  not enough data -> park in fetch-purgatory
  -> completes when bytes >= fetch.min.bytes OR wait >= fetch.max.wait.ms
```

The mechanism matters operationally for two reasons. First, it's why a **degraded ISR stalls `acks=all` producers**: if a follower is slow, the produce request sits in purgatory until it times out (`request.timeout.ms` → retry), which surfaces as producer latency/timeouts — a direct link between replication health and producer-side symptoms. Second, purgatory size is a JMX-exposed health metric: a large, growing produce purgatory means acks aren't completing (replication problems), and a large fetch purgatory is normal for long-poll consumers. Understanding purgatory turns "my producer is timing out" into "which followers aren't advancing the HWM," which is the actionable diagnosis.

#### Q76. [Practical] How do you decommission a broker and reassign its partitions safely (and how do you cancel a bad reassignment)?

Removing a broker isn't just shutting it down — its replicas must first be **moved to other brokers**, or you lose redundancy (and possibly availability) for every partition it hosted. The procedure is the inverse of expansion: generate a reassignment plan that excludes the target broker, execute it **with a throttle**, verify completion, then shut the broker down. Skipping the reassignment and just killing the broker drops every partition it led/replicated into under-replicated (or offline, if it held the last ISR member) state.

```bash
# 1) Build a plan that moves all partitions OFF broker 7 onto brokers 1-6
kafka-reassign-partitions.sh --bootstrap-server localhost:9092 \
  --topics-to-move-json-file all-topics.json \
  --broker-list "1,2,3,4,5,6" --generate > drain-7.json   # 7 excluded

# 2) Execute with a throttle so the backfill doesn't saturate the network
kafka-reassign-partitions.sh --bootstrap-server localhost:9092 \
  --reassignment-json-file drain-7.json --execute --throttle 50000000

# 3) Verify: when complete, broker 7 holds no replicas -> safe to stop
kafka-reassign-partitions.sh --bootstrap-server localhost:9092 \
  --reassignment-json-file drain-7.json --verify
```

**Cancelling a bad reassignment** is the part people miss under pressure. Modern Kafka supports `--cancel`, which reverts in-progress partition moves back to their original assignment — invaluable when you launched an unthrottled reassignment that's melting the cluster and need to back out:

```bash
kafka-reassign-partitions.sh --bootstrap-server localhost:9092 \
  --reassignment-json-file drain-7.json --cancel   # revert in-flight moves
```

Operationally: always `--verify` shows both progress and whether the **throttle is still active** (the throttle persists as a topic/broker config and *must be removed* afterward, or it silently caps future replication — a classic "why is replication slow weeks later" mystery). Run a **preferred-leader election** after the move so leadership rebalances. And confirm `replication.factor` is preserved — you're relocating replicas, not reducing redundancy. For large fleets, tools like **Cruise Control** automate this (goal-based rebalancing, throttle management, and broker decommission) and are worth adopting over hand-rolled JSON plans once you pass a handful of brokers.

#### Q77. [Theory] Explain the table/stream duality and how `KTable`, `GlobalKTable`, and `KStream` differ in Kafka Streams.

The **stream/table duality** is the conceptual core of Kafka Streams: a **stream** is an unbounded sequence of independent events (a changelog of *facts* that happened), and a **table** is the *current state* you get by applying those changes — collapsing a changelog by key gives a table, and observing a table's changes gives back a stream. They're two views of the same data, and Streams lets you convert between them (`KStream.toTable()`, `KTable.toStream()`). This duality is why a compacted topic *is* a table (latest value per key) and why every stateful operation is backed by a changelog.

A **`KStream`** treats each record as an **append/insert** — two records with the same key are two distinct events (e.g. two clicks by the same user). Aggregations sum them up. A **`KTable`** treats each record as an **upsert** keyed by record key — a new value for an existing key *replaces* the old one, and a `null` value is a **delete** (tombstone). So `KTable` models evolving entity state (a user's current balance, a customer's current tier), and its updates can trigger downstream recomputation.

```
Input records (key, value):  (a,1) (b,2) (a,3) (a,null) (b,5)

As a KStream: 5 independent events  -> count = 5
As a KTable : upserts -> final table state = { b: 5 }   (a deleted by tombstone)
```

A **`GlobalKTable`** is a `KTable` **fully replicated to every application instance** rather than partitioned across tasks. The trade-off: a `KTable` is partitioned (each instance holds only its share of keys, so joins require **co-partitioning**), while a `GlobalKTable` holds the *entire* table on every node, so you can join a stream against it **without co-partitioning and on any key** — at the cost of every instance storing the whole dataset and bootstrapping it on startup. Use `KTable` for large, partitioned, evolving state that aligns with your stream's key; use `GlobalKTable` for small-to-moderate reference data (country codes, feature flags, a product catalog) you need available everywhere regardless of partitioning. Picking the wrong one shows up as either silent join misses (un-co-partitioned `KTable`) or memory blowup (`GlobalKTable` on a huge dataset).

### 🔴 Expert — extended

#### Q78. [Theory] How do Kafka Streams standby replicas and state restoration affect failover time, and how do you tune them?

A stateful Streams task keeps its state in a local **RocksDB store** backed by a compacted **changelog topic**. When an instance dies and its task moves to another instance, that instance must **rebuild the state by replaying the entire changelog** from the start of the compacted log into a fresh local store before it can resume processing. For a large store this restoration can take minutes, during which the partition's processing is stalled — the dominant contributor to Streams failover time (much larger than the rebalance itself).

**Standby replicas** (`num.standby.replicas`) attack this directly: Streams keeps `N` *warm copies* of each task's state on other instances, continuously updated from the changelog in the background. On failover, the task moves to an instance that already has a near-current standby store, so it only needs to catch up the last few records instead of replaying the whole changelog — turning a multi-minute restore into seconds. The cost is extra local disk and changelog read traffic on the standby instances (you're maintaining `1 + N` copies of every store), and the standby work competes for resources.

```
num.standby.replicas = 0:
  instance B dies -> task moves to C -> C replays FULL changelog -> minutes of downtime

num.standby.replicas = 1:
  C already holds a warm standby store -> catch up last few records -> seconds
  (cost: C maintains a second copy of B's state continuously)
```

Beyond standbys, several levers cut restoration time: **`acceptable.recovery.lag`** lets the assignor prefer an instance whose standby is "close enough" rather than waiting for a perfectly caught-up one; the **state directory must be persistent** (mount a durable volume in Kubernetes — `StatefulSet` with a PVC — so a pod restart reuses the existing RocksDB store instead of restoring from scratch, the single biggest practical win); and **static membership** + cooperative rebalancing avoid reshuffling tasks unnecessarily on rolling deploys. The senior framing: Streams failover cost is *state restoration*, not rebalancing, so the architecture decisions (standbys, persistent volumes, recovery-lag tuning) target getting a warm copy of state where the task lands, not making rebalances faster.

#### Q79. [Practical] You must achieve sub-10ms end-to-end (produce→consume) latency at high volume. Walk through every layer you'd tune.

Sub-10ms p99 end-to-end is aggressive and forces latency-over-throughput choices at *every* layer, plus a clear-eyed look at physics (cross-AZ RTT alone can blow the budget). I'd start by measuring the baseline end-to-end with representative payloads, because you can't tune what you can't see, and I'd track **p99/p999**, not averages.

**Producer:** `linger.ms=0` (don't wait to batch — accept smaller batches), a small `batch.size`, and decide on `acks`. `acks=all` adds the replication round-trip to the latency budget; if the durability requirement allows, `acks=1` is faster, but the honest answer is you usually keep `acks=all` and instead make replication fast. Keep `compression.type` light (`lz4`) or off (compression CPU is latency). Ensure `max.in.flight` and idempotence are set so retries don't reorder.

**Broker / cluster:** co-locate clients and partition leaders in the **same AZ** (cross-AZ adds ~1–2ms each way — often the whole budget); use **follower fetching** so consumers read locally. Fast NVMe and plenty of **page cache** RAM so tail reads never touch disk. Keep `replica.lag` tight and `num.replica.fetchers` high so `acks=all` acks complete fast. Avoid GC pauses — they spike p999 — by sizing a modest heap and using a low-pause collector (G1/ZGC). Watch the produce purgatory; a slow ISR directly adds produce latency.

**Consumer:** `fetch.min.bytes=1` and a small `fetch.max.wait.ms` (e.g. 1–10ms) so the broker returns immediately rather than waiting to fill a batch — this is the consumer-side equivalent of `linger.ms=0`. Small `max.poll.records` to keep each poll loop tight. Process inline (no handoff queue latency) if the work is trivial, or accept the queue cost if not.

```
Latency budget (~10ms p99) — rough allocation:
  producer send + serialize ............ ~0.5ms   (linger=0)
  network producer->leader ............. ~0.3ms   (same-AZ)
  leader append + acks=all replication . ~1-3ms   (fast ISR, NVMe, high fetchers)
  consumer fetch (min.bytes=1) ......... ~0.5ms   (max.wait small)
  network leader/follower->consumer .... ~0.3ms   (follower fetch, same-AZ)
  GC / scheduling jitter (the killer) .. budget the tail, not the mean
```

The senior judgment: most of the budget is **network RTT and GC jitter**, not Kafka config — so AZ-locality and pause-free JVMs matter more than micro-tuning `batch.size`. And there's a hard trade-off with throughput and durability: `linger.ms=0` + small fetches + tight batching sacrifices the efficiency that high-volume systems normally want, so you provision *more* brokers/partitions to carry the volume at low batch sizes. If after all this the budget still doesn't close (e.g. mandatory cross-region), the right answer is to push back on the 10ms requirement or change the topology, not to disable durability.

#### Q80. [Theory] Compare MirrorMaker 2, Confluent Replicator, and Cluster Linking for cross-cluster replication. What are the offset-translation and active-active pitfalls?

All three move data between clusters, but they differ in mechanism and operational model. **MirrorMaker 2** (open-source, built on Kafka Connect) runs as Connect source connectors that *consume* from the source cluster and *produce* into the target — so replicated records get **new offsets** in the target (the byte stream is copied, but offset N in source ≠ offset N in target). MM2 mitigates this with a **checkpoints topic** and **offset-sync topic** that record the source→target offset mapping per consumer group, plus a `RemoteClusterUtils`/`MirrorClient` API so a failed-over consumer can translate its last committed source offset into the right target offset. **Confluent Replicator** is a similar Connect-based approach with commercial tooling. **Cluster Linking** (Confluent) is fundamentally different: it replicates at the **byte/offset level**, preserving offsets exactly (a mirror topic is a true offset-identical copy), which eliminates offset translation entirely but requires Confluent infrastructure.

The **offset-translation pitfall** is the crux of DR failover with MM2: because offsets differ between clusters, a consumer that fails over to the DR cluster *cannot* simply resume at its last source offset — it must use the checkpoint/translation data, and translation is **approximate** (synced periodically, so failover can reprocess or skip a small window). You must design consumers to tolerate at-least-once at the failover boundary. With Cluster Linking, offsets match, so failover is cleaner.

```
MM2:  source off=1000  --replicate-->  target off=4250   (DIFFERENT)
      checkpoints topic maps group's source 1000 -> target 4250 (periodic, approximate)

Cluster Linking: source off=1000 --byte mirror--> target off=1000  (IDENTICAL)
```

The **active-active pitfall** is replication loops: if cluster A replicates topic `T` to B *and* B replicates `T` back to A, records ping-pong forever. MM2 solves this with **topic prefixing/remote naming** (a record from A appears in B as `A.T`, not `T`), so each cluster owns its local-origin topics and consumers read both local and remote-prefixed topics — but this means your applications must be **topology-aware** (know that the "same" logical topic is `T` locally and `A.T` remotely), and aggregation across both requires reading both. Other gotchas: replicating `__consumer_offsets` correctly, keeping ACLs/configs in sync, and the nonzero **RPO** inherent to async replication (in-flight data at the moment of source failure is lost). The senior answer: choose based on whether you can tolerate offset translation (MM2/Replicator, open or commercial) versus needing offset-identical mirrors (Cluster Linking), and design consumers for the at-least-once, topology-aware reality of active-active.

#### Q81. [Practical] How do you handle deserialization failures (a "poison pill") that crash the consumer before your code even runs?

A poison pill is a record whose **bytes can't be deserialized** — wrong schema, corrupt data, a producer that used a different serializer. The deserializer runs *inside* `poll()`, before your processing code, so a thrown `SerializationException` escapes `poll()`, and if unhandled it crashes the consumer; on restart it re-reads the *same* record and crashes again — an infinite crash loop that blocks the whole partition. This is nastier than a business-logic failure because your try/catch around `process()` never even gets a chance to run.

The clean fix is a **delegating/error-handling deserializer** that wraps the real one, catches the deserialization exception, and returns a sentinel (e.g. `null` or a tagged "failed" object) plus the raw bytes and exception in a header, so your code sees a record it can route to a DLQ instead of a crash. Spring Kafka ships `ErrorHandlingDeserializer` for exactly this; the vanilla-client equivalent is a custom wrapper:

```java
public class SafeDeserializer<T> implements Deserializer<T> {
    private final Deserializer<T> delegate;
    public SafeDeserializer(Deserializer<T> d) { this.delegate = d; }

    public T deserialize(String topic, Headers headers, byte[] data) {
        try {
            return delegate.deserialize(topic, headers, data);
        } catch (Exception e) {
            headers.add("deser-error", e.getClass().getName().getBytes());
            return null;          // app sees null -> route raw bytes to DLQ, commit, move on
        }
    }
    public T deserialize(String topic, byte[] data) { return deserialize(topic, null, data); }
}
```

```properties
# Spring Kafka idiomatic form:
spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
spring.kafka.consumer.properties.spring.deserializer.value.delegate.class=...AvroDeserializer
# + a DefaultErrorHandler with DeadLetterPublishingRecoverer to capture the bad record
```

The discipline around it: **preserve the raw bytes** (the DLQ record should carry the original `byte[]` and the error, since you can't re-serialize what you couldn't deserialize), **commit past the poison record** so the partition unblocks, and **alert on deserialization-error rate** because a spike means a producer shipped a bad schema or a compatibility check was bypassed. The deeper prevention is upstream: enforce **Schema Registry compatibility** so incompatible data can't be produced in the first place — a poison pill at the deserialization layer is usually evidence that schema governance failed. Never "fix" this by catching and *skipping silently*, which discards data with no record; route to a DLQ so it's recoverable.

#### Q82. [Theory] What is JBOD vs RAID for Kafka storage, how does Kafka handle a single failed disk, and what changed with KRaft?

Brokers can use **multiple log directories** (`log.dirs` = several mount points), and the two storage layouts are **JBOD** (Just a Bunch Of Disks — each disk is an independent filesystem, Kafka spreads partitions across them) and **RAID** (the OS/controller presents one logical volume, masking individual disk failures). The trade-off: **RAID-10** gives you transparent single-disk fault tolerance (a disk dies, the array keeps serving) but wastes capacity on mirroring *on top of* Kafka's own replication — you're paying twice for redundancy. **JBOD** uses raw capacity efficiently (no RAID overhead) and lets Kafka's replication be the redundancy layer, which is the design intent — but historically a single disk failure in JBOD was disruptive.

Kafka's behavior on a **single failed disk in JBOD** is the key evolution. Originally, a disk failure could take the **entire broker** offline (the broker crashed if any log dir failed), losing *all* its partitions even though most disks were healthy — a terrible blast radius. KIP-112/113 made the broker **tolerate a single log-dir failure**: it marks the failed dir's partitions offline and keeps serving the partitions on healthy disks, and those offline partitions are recovered from replicas on *other brokers*. So with replication factor ≥ 2, a single disk failure becomes a localized, recoverable event rather than a whole-broker outage.

```
JBOD broker, one disk dies (RF=3):
  disk1 (ok): partitions A,B  -> keep serving
  disk2 (DEAD): partitions C,D -> marked offline on this broker
                               -> still available via replicas on brokers 2 & 3
  -> replace disk, broker re-replicates C,D onto it; no data loss, partial-broker impact
```

What **KRaft changed**: in ZooKeeper mode, JBOD support had gaps and was discouraged for some configurations; KRaft initially *lacked* full JBOD support, but **KIP-858 restored JBOD on KRaft** (Kafka 3.7+), so multi-disk-without-RAID is again a first-class, supported layout under the modern metadata system. The practical guidance: for large clusters, **JBOD + replication factor 3** is the cost-efficient, idiomatic choice (let Kafka replicate; don't double-pay with RAID), provided you're on a version with mature JBOD support and you monitor per-disk health and offline-partition counts. RAID-10 remains reasonable for smaller clusters that prize operational simplicity over storage efficiency. Either way, **Kafka replication — not the disk layer — is your real durability guarantee**, so never run RF=1 and rely on RAID.

#### Q83. [Practical] Design a capacity-planning model for a new Kafka cluster: how many brokers, partitions, and what disk/network?

Capacity planning starts from **measured or estimated workload**, not vendor rules of thumb, and sizes for **peak plus headroom and failure**, not average. I drive it from four inputs: peak write throughput (MB/s), the **replication factor**, the **retention** period, and the **consumer fan-out** (how many consumer groups re-read the data, which multiplies read bandwidth). From those, storage and network fall out arithmetically, and partition count follows from parallelism needs.

**Storage:** `disk = peak_write_MBps × retention_seconds × replication_factor × (1 + headroom)`. For example, 100 MB/s writes × 7 days (604,800s) × RF 3 ≈ 169 TB raw, before headroom — keep disks below ~60–70% full so compaction/retention churn and reassignment have room, so plan ~250 TB, then divide by per-broker disk to get a broker floor. **Network:** ingress = write × RF (replication multiplies it) and egress = write × (number of consumer groups) + replication; a topic read by 5 groups produces ~5× the read bandwidth of the write. This often makes **network the binding constraint** before disk, especially with high fan-out, and is why follower fetching (local reads) matters for cost.

**Partitions:** size for `target_throughput / per_partition_throughput` *and* for desired consumer parallelism (consumers ≤ partitions), then check the **total partitions per broker** stays in a sane range (low thousands per broker is comfortable; tens of thousands strains controller/failover even under KRaft). **Brokers:** take the max of the disk-driven, network-driven, and partition-driven floors, then **add capacity for N+1 (or N+2) failure** — you must absorb a broker loss without saturating the survivors, so never run brokers at 100% of steady-state.

```
Worked example: 100 MB/s peak write, RF=3, 7d retention, 5 consumer groups
  storage:  100 * 604800 * 3 = ~169 TB raw -> ~250 TB with headroom
  ingress/broker network:  100 * 3 (replication) = 300 MB/s cluster write+replicate
  egress:   100 * 5 groups + replication ~= 500+ MB/s reads   <- often the real limit
  partitions: target parallelism (e.g. 60 consumers) -> >= 60 partitions, sized per-broker
  brokers:  max(disk floor, network floor, partition floor) + 1 spare for failure
```

The senior discipline: **measure with a representative load test** (`kafka-producer-perf-test.sh` / `kafka-consumer-perf-test.sh`) using your real record size and compression, because per-partition and per-broker throughput depend heavily on record size, batching, and disk/network hardware — a 100-byte-record cluster and a 100-KB-record cluster size completely differently. Plan for **growth** (over-provision partitions modestly since you can't shrink them, but not absurdly), leave **failure headroom**, and revisit as real metrics arrive. Increasingly, **Tiered Storage** changes the disk math entirely — you size local disk for the *hot window* and push long retention to object storage, decoupling retention from broker disk count.

#### Q84. [Behavioral] Tell me about a time you had to make a difficult trade-off between data consistency and system availability on a streaming platform.

Use **STAR**, and choose a story where the *tension itself* — not just the outcome — shows your judgment, because this question probes whether you understand that consistency vs. availability is a deliberate business decision, not a purely technical one. *Situation:* frame the conflict concretely — e.g. "Our order-events Kafka cluster spanned three AZs; during an AZ degradation, two of three replicas for several critical partitions went out of sync, and `acks=all` with `min.insync.replicas=2` started **rejecting writes** with `NotEnoughReplicasException`. The order-intake API began failing — an availability hit — precisely *because* the system was protecting consistency." *Task:* "As the owning engineer I had to decide, under pressure, whether to relax durability to restore order intake or hold the line and accept rejected orders."

*Action:* explain how you reasoned rather than just what you flipped. "I refused the tempting quick fix of enabling `unclean.leader.election` or dropping `min.insync.replicas` to 1 cluster-wide, because for *paid orders* a silent loss was unacceptable and would surface as charged-but-lost orders — a far worse incident than a brief intake pause. Instead I (1) had the API **buffer rejected writes** to a local durable queue with backpressure to the client, (2) prioritized **recovering the third replica** by reassigning those partitions' followers to healthy brokers in surviving AZs, and (3) made a **scoped, reversible** decision: for a genuinely low-value telemetry topic in the same cluster, I *did* temporarily relax durability, because there the trade-off ran the other way." The signal is **per-topic, value-driven** decisions and refusing a blast-radius-wide config change made in panic.

*Result:* quantify and close the loop — "Order intake recovered within ~25 minutes once ISR was restored; the buffer meant zero orders were lost or double-charged; and the postmortem produced concrete changes: rack-aware placement audited so no partition could lose two replicas in one AZ, alerting on `IsrShrinks` and `NotEnoughReplicas`, and a documented runbook that pre-decides the consistency/availability stance *per topic class* so the next on-call isn't improvising the trade-off at 3 AM." The lesson to articulate: the strongest move was deciding the trade-off **deliberately and differently per data class**, designing a path (buffering) that avoided the false binary, and turning a tense judgment call into pre-made policy so it's not re-litigated under stress. Interviewers want calm prioritization, awareness that defaults like `acks=all`/`min.insync.replicas` *are* the consistency choice, and the maturity to push complexity into policy and tooling rather than heroics.

#### Q85. [Theory] How does the consumer's offset-commit choice (`commitSync` vs `commitAsync`, auto vs manual) affect correctness and throughput, and what's the recommended hybrid?

Offset commits are where delivery semantics are actually decided, and the four dimensions — *sync vs async*, *auto vs manual*, *when*, and *granularity* — each trade correctness against throughput. **`enable.auto.commit=true`** (the default) commits the last polled offsets periodically (`auto.commit.interval.ms`) on the poll thread; it's simple but commits offsets for records you *may not have finished processing*, so a crash mid-batch can **lose** records (the offset advanced past unprocessed records) — at-most-once-ish, and unsafe for anything important. **Manual commit** (`enable.auto.commit=false`) lets you commit *after* successful processing, giving at-least-once.

Among manual commits, **`commitSync()`** blocks until the broker confirms and **retries on retriable errors**, so it's correct but adds latency on every commit — at high throughput, blocking per batch hurts. **`commitAsync()`** fires the commit and returns immediately (a callback reports the result), maximizing throughput, but it **does not retry** (retrying a stale async commit could overwrite a newer offset), so a failed async commit just means that commit was lost — usually fine because the *next* commit covers it, except at shutdown when there is no next commit.

```java
try {
    while (running) {
        var records = consumer.poll(Duration.ofMillis(200));
        process(records);
        consumer.commitAsync();        // fast path: don't block the loop
    }
} finally {
    try {
        consumer.commitSync();         // shutdown/rebalance: block + retry to nail it down
    } finally {
        consumer.close();
    }
}
```

The **recommended hybrid** (above) is the idiomatic pattern from *Kafka: The Definitive Guide*: use **`commitAsync()` in the hot loop** for throughput (lost commits self-heal on the next cycle) and a final **`commitSync()` in `finally`** (and inside a `ConsumerRebalanceListener.onPartitionsRevoked`) to guarantee the last offsets are durably committed before you give up the partitions — the one place where "the next commit will fix it" doesn't hold. For finer control, commit **per-partition offset maps** (`commitSync(Map)`) so a slow/failed partition doesn't roll back others, and remember the committed value is **last-processed offset + 1**. The correctness anchor across all of this: commit *after* processing for at-least-once and make processing idempotent; the sync/async choice is purely a throughput-vs-shutdown-safety optimization layered on top of that invariant.

#### Q86. [Practical] Your Kafka Streams app needs a clean reprocess from scratch after a logic bug. How do you reset it safely, and what gets deleted?

A Streams app accumulates three kinds of durable state tied to its `application.id`: **committed consumer offsets** (its read position on input topics), **local RocksDB state stores** (on each instance's disk), and **internal topics** (changelog topics backing the stores and repartition topics for re-keying). To truly reprocess "from scratch" you must reset *all three* consistently — just resetting offsets leaves stale state in the stores and changelogs, producing wrong results. The purpose-built tool is **`kafka-streams-application-reset.sh`**, which must be run while the app is **stopped** (all instances down), and it handles the cluster-side state; the local RocksDB dirs are cleaned by the app on next start (or via `KafkaStreams.cleanUp()`).

```bash
# App MUST be stopped first. Then:
kafka-streams-application-reset.sh --bootstrap-server localhost:9092 \
  --application-id order-enricher-v1 \
  --input-topics orders,customers \
  --to-earliest                       # reset input offsets to the beginning
# What it does cluster-side:
#   - resets the app's committed offsets on INPUT topics (here: to-earliest)
#   - resets offsets on internal REPARTITION topics and DELETES them
#   - resets offsets on intermediate (through) topics
#   - does NOT delete CHANGELOG topics (they hold state; deletion is separate/optional)
```

```java
// On the next startup, clean the LOCAL state stores before start():
KafkaStreams streams = new KafkaStreams(topology, props);
streams.cleanUp();   // wipes local RocksDB dirs so state is rebuilt, not reused
streams.start();
```

What the reset tool **does not** do is delete **changelog topics** by default — those are the durable state, and the tool resets/cleans repartition and intermediate topics but leaves changelogs (you delete them manually if you want the materialized state truly gone, e.g. `kafka-topics.sh --delete` on the `<app-id>-<store>-changelog` topics). The crucial subtlety: calling `cleanUp()` wipes the *local* stores so they get rebuilt — but if you didn't also clear/recreate the changelogs, the store rebuilds from the *old* changelog content, not from reprocessing. So for a genuine from-scratch reprocess after a *logic* bug, the full recipe is: stop all instances → run the reset tool (`--to-earliest`) → delete the changelog topics → `cleanUp()` + start, so stores are rebuilt by re-running the (fixed) topology over the input from the beginning.

The big alternative — and often the *safer* one in production — is to **not reset in place at all**: bump the `application.id` (e.g. `order-enricher-v2`). A new id is a brand-new app with fresh offsets, fresh stores, and fresh internal topics, so it reprocesses from the configured `auto.offset.reset` while the old app's state remains untouched for rollback. The trade-off is duplicate output during cutover (you run v2 alongside or after v1, then switch consumers of the output topic) and temporary double storage, but you avoid the risk of a half-completed manual reset and you get a clean rollback path. I'd reserve the in-place reset for dev/test and use the **versioned-application-id** strategy for production reprocessing.

#### Q87. [Theory] What observability — metrics, tracing, and lag semantics — would you put in place to operate Kafka at scale, and which signals are leading vs lagging indicators?

Operating Kafka at scale means instrumenting **three planes** — brokers, clients, and end-to-end flow — and, critically, distinguishing **leading indicators** (predict trouble) from **lagging indicators** (confirm damage). The classic mistake is alerting only on lagging signals (consumer lag already huge, partitions already offline) and getting paged after users are affected. Good observability catches the *trend* early.

**Broker plane (JMX):** the red-alert metrics are `UnderReplicatedPartitions` and `OfflinePartitionsCount` (both should be 0 — nonzero is leading/lagging depending on trend), `ActiveControllerCount` (exactly 1 across the cluster — 0 or 2 is a split-brain emergency), `IsrShrinksPerSec`/`IsrExpandsPerSec` (flapping is a *leading* indicator of replication stress before URP appears), `RequestHandlerAvgIdlePercent` and `NetworkProcessorAvgIdlePercent` (approaching 0 is a *leading* saturation signal), request `TotalTimeMs` percentiles (latency creeping up *before* timeouts), purgatory sizes, and per-disk usage/`LogFlushRateAndTimeMs`. JVM **GC pause time** is a leading indicator for both broker drops-from-ISR and consumer poll-interval breaches.

**Client plane:** producer `record-error-rate`, `record-retry-rate`, `request-latency`, and `buffer-available-bytes` (shrinking buffer is leading backpressure); consumer `records-lag-max`, `fetch-latency`, `commit-latency`, and `rebalance-rate` (frequent rebalances are a *leading* indicator of instability before lag explodes). **Lag in time, not just records** is the metric that matters to the business: "the consumer is 8 seconds behind" is meaningful; "12,000 records behind" is meaningless without throughput context. Compute time-lag by comparing the timestamp of the last-committed offset to now (Burrow and Kafka Lag Exporter do this), and alert on **sustained growth** — a steady nonzero lag is healthy; *rising* lag is the signal.

```
Leading (predict)                         Lagging (confirm damage)
  IsrShrinks/ExpandsPerSec flapping         UnderReplicatedPartitions > 0
  RequestHandlerAvgIdlePercent -> 0         OfflinePartitionsCount > 0
  GC pause time rising                      consumer time-lag large & growing
  rebalance-rate rising                     produce/fetch timeouts, errors
  producer buffer-available shrinking       data loss / SLA breach
```

**End-to-end:** wire **distributed tracing** (OpenTelemetry context propagated via record *headers* by producer/consumer interceptors) so you can follow a message producer → topic → consumer → downstream and attribute latency to the right hop — invaluable when "the pipeline is slow" needs to become "the enrichment consumer's DB call is the bottleneck." Add **synthetic end-to-end probes** (produce a canary record, measure time to consume) for a true black-box latency SLI. The operating philosophy: alert primarily on **leading indicators and trends** (ISR flapping, thread idle %, rising rebalance/lag *rate*, GC) so you act before the lagging red-alerts (offline partitions, huge time-lag, errors) ever fire — and always express consumer health in **time behind real-time**, which is the number that maps to user impact.

---

## ✅ Key Takeaways

- **Partitions are everything**: they define ordering (per-partition only), parallelism (consumers ≤ partitions), and key placement. Size them deliberately and remember you can't easily shrink them.
- **Durability is a config triad**: `acks=all` + `replication.factor=3` + `min.insync.replicas=2` survives one broker loss with no committed-data loss.
- **Idempotence is free and on by default** (Kafka 3.0+); **transactions + `read_committed`** give true exactly-once for consume-process-produce loops.
- **Rebalances are the #1 operational pain**: prefer **cooperative sticky** assignment and **static membership**, and keep per-poll processing fast under `max.poll.interval.ms`.
- **Retention vs. compaction**: delete for ephemeral streams, compact for changelogs/state — compaction underpins Streams stores, offsets, and CDC.
- **KRaft replaced ZooKeeper** (removed entirely in Kafka 4.0): faster failover, one fewer system, scales to millions of partitions.
- **Lag and under-replicated partitions** are your two most important health metrics — alert on *trends*, not absolute values.
- Kafka is a log, not a database, a TTL queue, or a router — match the tool to the requirement.

## ⚠️ Common Pitfalls

- Assuming **global ordering** — it only holds within a partition; a null key round-robins and increasing partitions remaps keys.
- Committing offsets **before** processing (or using auto-commit blindly) → data loss on crash; commit *after* successful processing for at-least-once.
- Cranking `max.poll.interval.ms` sky-high to "fix" rebalances instead of fixing slow processing — this just delays detecting dead consumers.
- Giving the broker a **huge JVM heap** and starving the OS page cache — Kafka throughput depends on page cache; keep heap modest (6–8GB).
- Forgetting that **idempotence ≠ exactly-once across partitions/sessions** — you still need transactions for EOS.
- Enabling **`unclean.leader.election`** on data you can't afford to lose, trading silent data loss for availability.
- Treating the **Schema Registry / Connect / Streams admin endpoints** as trusted internal-only and leaving them unauthenticated — they can read/write any topic.
- Hot partitions from skewed keys (a few "whale" entities) silently stalling one consumer while others idle.

## 📚 Further Reading

- *Kafka: The Definitive Guide, 2nd Edition* — Gwen Shapira, Todd Palino, Rajini Sivaram, Krit Petty (O'Reilly) — the canonical reference, updated for newer versions.
- *Designing Data-Intensive Applications* — Martin Kleppmann (O'Reilly) — chapters on logs, replication, and stream processing give the conceptual foundation.
- [Official Apache Kafka Documentation](https://kafka.apache.org/documentation/) — authoritative configs, protocol, and KRaft/Tiered Storage details.
- [Kafka Improvement Proposals (KIPs)](https://cwiki.apache.org/confluence/display/KAFKA/Kafka+Improvement+Proposals) — KIP-500 (KRaft), KIP-405 (Tiered Storage), KIP-98 (EOS/transactions).
- [Confluent Developer](https://developer.confluent.io/) — tutorials, courses, and patterns for Streams, Connect, and Schema Registry.
- *Kafka Streams in Action, 2nd Edition* — Bill Bejeck (Manning) — deep dive on stream processing and stateful topologies.
