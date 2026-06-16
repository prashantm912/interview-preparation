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
