# Design a Distributed Message Queue (Kafka-like)

A worked, interview-grade design for a horizontally-scalable, durable, partitioned commit-log system in the style of Apache Kafka — the kind of distributed log that backs event streaming, async decoupling, and CDC pipelines at companies operating at millions of messages per second.

[← Back to master index](../../README.md) · [← System Design index](../README.md)

---

## 1. Requirements

A distributed message queue (more precisely, a *distributed commit log*) lets producers publish records to named **topics**, durably persists them, and lets consumers read them at their own pace. Kafka, Pulsar, Redpanda, AWS Kinesis, and (loosely) SQS/Pub-Sub all live in this space. We will design a Kafka-like system because it exposes the hardest distributed-systems problems: partitioning, replication, leader election, offset management, and tunable delivery semantics.

### Functional requirements

- **Publish**: Producers send records (key, value, headers, timestamp) to a topic. The system returns an acknowledgement once durably stored.
- **Subscribe / consume**: Consumers read records from a topic, tracking their own position (offset). Multiple independent consumer applications can read the same topic.
- **Ordering**: Records with the same key must be delivered in publish order (per-partition total order).
- **Consumer groups**: A group of consumer instances cooperatively divides partitions among themselves so each partition is processed by exactly one member; the group scales horizontally.
- **Retention**: Records are retained by time (e.g., 7 days) or size, independent of whether they've been consumed. Optionally **log compaction** keeps only the latest value per key.
- **Replay**: A consumer can reset its offset and re-read history (the log is the source of truth, not a transient buffer).
- **Delivery semantics**: Support at-least-once (default), at-most-once, and exactly-once (idempotent + transactional) processing.

### Non-functional requirements

| Dimension | Target |
|---|---|
| **Throughput** | 10M messages/sec aggregate write, ~30M/sec read (fan-out 3x) |
| **Latency** | p99 produce-to-ack < 10 ms (acks=1), < 50 ms (acks=all); end-to-end p99 < 100 ms |
| **Durability** | No acknowledged message lost as long as < N replicas fail simultaneously; survive single-AZ loss |
| **Availability** | 99.99% for the cluster; a topic-partition is writable as long as its leader or an in-sync replica is alive |
| **Retention** | 7 days default, configurable to "forever" via tiered storage |
| **Scale** | Thousands of brokers, hundreds of thousands of partitions, PBs of data |
| **Consistency** | Per-partition linearizable writes (single leader); cross-partition ordering NOT guaranteed |

### Clarifying questions a candidate should ask

1. **Ordering scope** — global total order, or per-key/per-partition order? (Global order kills throughput; almost always per-partition.)
2. **Delivery guarantee** — at-least-once enough, or is exactly-once required? This dramatically changes producer/consumer complexity.
3. **Message size** — KB-scale records (typical) or large blobs (push payloads to object storage, queue references)?
4. **Push or pull** — do consumers pull (Kafka) or does the broker push (RabbitMQ/SQS-ish)? Affects backpressure design.
5. **Read pattern** — streaming tail reads (near head of log) vs. heavy historical replay (cold reads)?
6. **Retention model** — time/size-based deletion, or key-compacted (changelog/CDC)?
7. **Multi-tenancy / multi-region** — single cluster per region with mirroring, or stretched cluster?

---

## 2. Capacity Estimation

Assume: **10M messages/sec** peak write, **average record size 1 KB** (including key, headers, framing), **replication factor 3**, **7-day retention**, **read fan-out of 3** (three consumer groups read every record).

### Write throughput & ingress bandwidth

```
Writes              = 10,000,000 msg/s
Raw write bytes     = 10M × 1 KB        = 10 GB/s  (client → leader)
Replicated bytes    = 10 GB/s × (RF-1)  = 20 GB/s  (leader → 2 followers)
Total inter-broker  = 10 + 20           = 30 GB/s of network into the broker tier
```

### Read bandwidth

```
Consumer reads      = 10M × 3 groups    = 30,000,000 msg/s
Read bytes          = 30 GB/s (served from leaders; mostly page cache for tail reads)
```

So the broker fleet pushes roughly **10 GB/s in + 20 GB/s replication + 30 GB/s out ≈ 60 GB/s** of network. At ~25 Gbps (~3 GB/s) usable per NIC, that's **~20 brokers just for bandwidth headroom**; in practice you run more for storage/CPU and partition spread (say 100–300 brokers).

### Storage

```
Per second stored (pre-replication) = 10 GB/s
Per day                             = 10 GB/s × 86,400 s ≈ 864 TB/day
7-day retention                     = 864 TB × 7      ≈ 6.05 PB
With RF=3                           = 6.05 PB × 3     ≈ 18.1 PB on disk
```

~18 PB. With ~16 TB disks and ~10 disks/broker (160 TB usable, leave headroom → ~120 TB), you need **~150 brokers** for storage alone. Storage, not CPU, is the dominant cost — which is why **tiered storage** (offload cold segments to S3) matters: keep only ~1 day hot on local NVMe (~2.6 PB w/ RF=3, ~22 brokers' worth) and the rest in object storage at 1/5 the cost.

### Partition count

To sustain 10M msg/s with each partition comfortably handling ~50k msg/s (a safe single-leader-thread number), you need **~200 partitions minimum** for throughput. But partitions also bound consumer parallelism (max consumers per group = partition count). For headroom and future scaling, provision **1,000–4,000 partitions** for the busiest topics. Total cluster partitions (across all topics, × RF) can reach **hundreds of thousands** — which is exactly why metadata management (KRaft) had to replace ZooKeeper.

### Memory

Tail reads are served from the OS **page cache**, not heap. Budget per broker: ~64–128 GB RAM, mostly page cache so recent segments stay resident. JVM/process heap stays small (4–8 GB) — the design deliberately leans on the kernel, not application caching.

---

## 3. API Design

The contract is a small set of RPCs over a binary protocol (length-prefixed framing, batched). Shown here in pseudo-REST/gRPC form for readability.

```
# ---- Producer ----
Produce(topic, partition?, records[], acks, idempotent_id?) -> {base_offset, log_append_time, error?}
  records[] = [{key, value, headers, timestamp}]
  acks ∈ { 0 (fire-and-forget), 1 (leader only), all (all in-sync replicas) }
  partition? : if null, partitioner picks (hash(key) % N, or sticky round-robin for null keys)

# ---- Consumer ----
Subscribe(group_id, topics[])                       -> assignment (set of partitions)
Fetch(topic, partition, fetch_offset, max_bytes, max_wait_ms)
                                                    -> {records[], high_watermark, log_start_offset}
CommitOffsets(group_id, [{topic, partition, offset, metadata}]) -> {error?}
SeekToOffset(group_id, topic, partition, offset)    # rewind / replay
ListOffsets(topic, partition, timestamp|earliest|latest) -> offset

# ---- Group coordination ----
JoinGroup(group_id, member_id, protocols) -> {generation_id, leader_id, members}
SyncGroup(group_id, generation_id, assignments) -> {assignment}
Heartbeat(group_id, member_id, generation_id) -> {error?}   # liveness; triggers rebalance on timeout

# ---- Admin ----
CreateTopic(name, partitions, replication_factor, configs{retention.ms, cleanup.policy, ...})
DescribeCluster() -> {controller, brokers[], topics[]}

# ---- Transactions (exactly-once) ----
InitProducerId(transactional_id) -> {producer_id, epoch}
BeginTxn(); SendOffsetsToTxn(group_id, offsets); CommitTxn() | AbortTxn()
```

Design notes:
- **Batching is first-class**: `Produce`/`Fetch` carry batches of records, not single messages — this is the single biggest throughput lever (amortizes RPC, compression, and disk I/O).
- **`max_wait_ms` enables long-poll**: consumers pull, but the broker holds the request open until `max_bytes` accumulate or the timeout fires — low latency without busy-polling.
- **Compression** is applied per-batch (lz4/zstd) and stored compressed; consumers decompress, so disk and network both benefit.

---

## 4. Data Model

The core data structure is an **append-only log per partition** — not a SQL table, not a generic NoSQL store. Records are addressed by a monotonic 64-bit **offset**.

### On-disk layout

A partition is a directory of **segment files**. Each segment is a `.log` file (the records) plus index files:

```
topic-orders-3/                 # partition 3 of topic "orders"
  00000000000000000000.log      # records, offset 0 .. 1.04M
  00000000000000000000.index    # offset -> byte position (sparse)
  00000000000000000000.timeindex# timestamp -> offset (sparse)
  00000000000001048576.log      # next segment, base offset = 1,048,576
  00000000000001048576.index
  ...
  leader-epoch-checkpoint        # epoch -> start offset (for truncation correctness)
```

Record on disk (simplified):

```
RecordBatch:
  base_offset (8) | batch_length (4) | partition_leader_epoch (4)
  | magic (1) | crc (4) | attributes (2) [compression, txn flag]
  | last_offset_delta | first_timestamp | producer_id | producer_epoch
  | base_sequence | record_count
  | [ length | attrs | timestamp_delta | offset_delta | key | value | headers ]...
```

### Why a log, not SQL/NoSQL

- **Append-only sequential writes** hit ~the disk's sequential throughput (hundreds of MB/s even on HDD, GB/s on NVMe), vs. random B-tree writes. No update-in-place, no fragmentation, no write amplification.
- **Reads are sequential range scans** by offset → `sendfile()` zero-copy from page cache to socket. A SQL row store or LSM/B-tree would add index overhead and break zero-copy.
- **Immutability** makes replication trivially correct (followers replay the leader's log byte-for-byte) and makes replay/time-travel free.

### Metadata store

Cluster metadata — topics, partitions, replica assignments, leaders, ISR (in-sync replica) sets, configs — is itself a **replicated log** managed by the **KRaft** controller quorum (a Raft group of 3–5 controllers). This replaced ZooKeeper because external coordination (a) became a scaling bottleneck at hundreds of thousands of partitions and (b) added an operational dependency. KRaft stores metadata as a compacted internal topic, so the same log primitive powers both data and control planes.

### Consumer offsets

Committed offsets live in an internal **compacted** topic `__consumer_offsets` (keyed by `group_id, topic, partition`). Compaction keeps only the latest committed offset per key — so the topic stays small and recovery just replays it. This is a beautiful example of the log being reused as a durable KV store.

---

## 5. High-Level Architecture

```
                                  ┌──────────────────────────────────────┐
                                  │      KRaft Controller Quorum (Raft)    │
                                  │   (leader election, ISR, metadata)     │
                                  │   ctrl-1*    ctrl-2    ctrl-3           │
                                  └───────────────▲────────────────────────┘
                                       metadata    │ pushes metadata deltas
                                       reads/writes │
   PRODUCERS                                        │                         CONSUMERS (groups)
 ┌───────────┐   Produce(acks)   ┌─────────────────┴───────────────────┐  Fetch    ┌────────────┐
 │ partitioner├──────────────────►│            BROKER FLEET             │◄──────────┤ group A    │
 │ batch+lz4 │                   │                                     │   long-poll│  c1 c2 c3  │
 └───────────┘                   │  ┌────────── Broker 1 ───────────┐  │            └────────────┘
 ┌───────────┐                   │  │ Leader: orders-0, orders-3     │  │           ┌────────────┐
 │ producer 2├──────────────────►│  │ Follower: orders-1 (replicate) │  │◄──────────┤ group B    │
 └───────────┘                   │  │   page cache → sendfile()      │  │            │  c1 c2     │
                                  │  └────────────────────────────────┘  │           └────────────┘
                                  │  ┌────────── Broker 2 ───────────┐  │
                                  │  │ Leader: orders-1               │  │     ┌───────────────────┐
                                  │  │ Follower: orders-0, orders-3   │◄─┼─────┤ Group Coordinator │
                                  │  └────────────────────────────────┘  │     │ (assigns partitions│
                                  │  ┌────────── Broker 3 ───────────┐  │     │  per group)        │
                                  │  │ Follower: orders-0, orders-1   │  │     └───────────────────┘
                                  │  └────────────────────────────────┘  │
                                  └───────────────┬─────────────────────┘
                                                  │ offload cold segments
                                                  ▼
                                  ┌──────────────────────────────────────┐
                                  │   TIERED STORAGE (S3 / object store)   │
                                  │   cold segments, cheap, infinite      │
                                  └──────────────────────────────────────┘
```

### Component walkthrough

- **Producer**: Serializes the record, runs a **partitioner** (`hash(key) % numPartitions`, or *sticky* round-robin for null keys to keep batches full), buffers records into per-partition batches, compresses, and sends to the **partition leader**. Waits for the configured `acks`.
- **Broker**: A stateful node that hosts a set of partition replicas. For partitions where it is **leader**, it accepts writes, appends to the log, and serves reads. For partitions where it is **follower**, it continuously fetches from the leader to stay in sync. Brokers exploit the OS page cache and `sendfile()` for zero-copy reads.
- **KRaft controller quorum**: A small Raft group that is the source of truth for cluster metadata: which broker leads each partition, the ISR set, topic configs. It handles **leader election** when a broker dies and propagates metadata deltas to brokers.
- **Group coordinator**: A broker-resident role (one per group, chosen by hashing the group id) that manages consumer group membership, heartbeats, and partition **assignment/rebalancing**.
- **Tiered storage**: Cold log segments are offloaded to object storage (S3) and transparently fetched on read, decoupling retention from local disk.

### Write path (acks=all)

1. Producer sends a batch to the leader of partition P.
2. Leader appends to its local log (just an in-memory page + eventual fsync), advancing the **Log End Offset (LEO)**.
3. Followers in the **ISR** fetch the new records and append to their logs.
4. Once all ISR replicas have replicated up to offset X, the leader advances the **High Watermark (HW)** to X. Only records ≤ HW are visible to consumers (committed).
5. Leader returns `base_offset` to the producer. With `acks=all`, the ack waits for HW advancement → no acknowledged record is lost unless every ISR replica fails.

### Read path

Consumer issues `Fetch(offset, max_bytes, max_wait_ms)`; the leader locates the byte position via the sparse `.index`, and `sendfile()`s a contiguous run of bytes (often already compressed) straight from page cache to the socket. Consumers commit offsets back to `__consumer_offsets`.

---

## 6. Deep Dives

### 6.1 Partitioning & ordering

A topic is split into N partitions; **each partition is an independent totally-ordered log**. Ordering guarantees are *per partition only* — there is no cross-partition order. This is the central trade-off that lets the system scale linearly: parallelism = partition count.

- **Keyed routing**: `partition = hash(key) % N` guarantees all records for a key land in one partition → per-key order preserved (e.g., all events for `user_42` are ordered).
- **The repartitioning trap**: because routing is `% N`, *changing N reshuffles keys to different partitions*, breaking per-key ordering for in-flight data. Mitigations: over-provision partitions up front; use consistent-hashing-style schemes; or accept a one-time ordering discontinuity at expansion. Kafka only allows *increasing* partition count for this reason, and even that changes key→partition mapping.
- **Hot partitions**: a skewed key (one `user_id` with 1000x traffic) overloads one leader. Mitigations: composite keys (`user_id#bucket`), explicit custom partitioner, or splitting the hot key across partitions and re-ordering downstream. There is no free lunch — you trade ordering for balance.
- **Choosing N**: bounded below by throughput (target/per-partition-ceiling) and consumer parallelism (max consumers per group = N); bounded above by metadata overhead, leader-election time, and end-to-end latency (more partitions → smaller batches → less efficiency). Typical busy topic: low thousands.

### 6.2 Replication, ISR & leader election (the heart of durability)

Each partition has RF replicas: one **leader** + (RF-1) **followers**. The **ISR (In-Sync Replica set)** is the subset of replicas caught up to within `replica.lag.time.max.ms` of the leader.

- **High Watermark**: the highest offset replicated to *all* ISR members. Consumers only see ≤ HW. This is what makes acknowledged data durable: a record is "committed" only once every ISR replica has it.
- **`acks=all` + `min.insync.replicas=2`**: the producer's write isn't acked until ≥2 ISR replicas hold it. If ISR shrinks below 2 (e.g., RF=3 and 2 followers lag out), the leader **rejects writes** (`NotEnoughReplicas`) rather than risk data loss — choosing **availability sacrifice over durability sacrifice**.
- **Leader election**: when a leader dies, the controller elects a new leader **from the ISR** — guaranteeing the new leader has all committed data. **Unclean leader election** (electing a non-ISR replica) is disabled by default: it would trade durability for availability by promoting a replica that may be missing committed records.
- **Leader epochs**: every leadership term gets a monotonically increasing epoch. Followers use the leader-epoch history to correctly **truncate** divergent suffixes after a leader change, fixing the classic log-divergence bug where HW-based truncation alone could lose or duplicate data.

**CAP framing**: within a partition the design is **CP** — on a partition/network split, the minority side stops accepting writes (loses availability) to preserve consistency and durability. The `unclean.leader.election` and `min.insync.replicas` knobs let an operator *slide toward AP* (accept writes/availability, risk losing committed data). This tunability is the exam-worthy insight.

### 6.3 Offset management, consumer groups & rebalancing

Consumers are **stateless about position** on the broker side — the broker never tracks "who read what." Instead each consumer tracks its **offset** and commits it to `__consumer_offsets`. This is why the same record can be read by many groups, replayed, or skipped — the log is immutable and the cursor lives with the consumer.

- **Consumer groups**: members of a group split the topic's partitions so each partition is owned by exactly one member. Add members → partitions redistribute (up to N members useful). This is horizontal scaling for *reads/processing*.
- **Rebalancing**: when a member joins/leaves/dies (heartbeat timeout), the **group coordinator** triggers a rebalance and reassigns partitions. Naive ("eager") rebalancing causes a **stop-the-world** pause — all members revoke everything, then re-acquire. **Cooperative (incremental) rebalancing** only moves the partitions that must move, avoiding the global pause; **static membership** (fixed `group.instance.id`) avoids rebalances entirely on rolling restarts.
- **Commit timing dictates delivery semantics**:
  - *Commit before processing* → **at-most-once** (crash after commit, before work → record lost).
  - *Commit after processing* → **at-least-once** (crash after work, before commit → reprocessed). This is the sensible default; downstream must be idempotent.
- **Offset reset policy** (`auto.offset.reset`): when a group has no committed offset (new group or offset expired), start at `earliest` (replay all) or `latest` (only new). A frequent production footgun.

### 6.4 Delivery semantics: at-least-once → exactly-once

| Semantic | How | Cost |
|---|---|---|
| **At-most-once** | acks=0/1, commit before processing | Fast, lossy on crash |
| **At-least-once** | acks=all, commit after processing | Default; **duplicates possible** |
| **Exactly-once (EOS)** | Idempotent producer + transactions | Higher latency/complexity |

True end-to-end exactly-once requires two pieces:

1. **Idempotent producer**: each producer gets a `producer_id` + monotonic **sequence number** per partition. The leader dedups: a retried batch with a sequence it has already seen is ack'd but not re-appended. This eliminates *producer-retry* duplicates (a network timeout that caused a blind retry).
2. **Transactions**: a producer can atomically write to multiple partitions *and* commit its consumer offsets in one transaction (`sendOffsetsToTxn` + `commitTxn`). A **transaction coordinator** writes markers to the log; consumers with `isolation.level=read_committed` skip aborted records. This gives atomic **consume→process→produce** — the foundation of exactly-once stream processing.

**Caveat for the interview**: "exactly-once" holds *within the Kafka boundary* (Kafka-to-Kafka). The moment you write to an external system (a DB, an email send) without an idempotency key or a two-phase/transactional outbox, you're back to at-least-once at the edge. The honest answer is "effectively-once via idempotency," not magic.

### 6.5 Log-structured storage, zero-copy & retention

Performance comes from working *with* the OS, not around it:

- **Sequential append**: writes go to the active segment's tail. The page cache absorbs writes; `fsync` is periodic/configurable (durability comes from *replication*, not synchronous fsync — a deliberate choice to keep latency low).
- **Zero-copy reads**: `sendfile()` moves bytes from page cache directly to the NIC, skipping user space and extra copies. Combined with the consumer-side decompression, the broker barely touches record contents.
- **Retention** runs a background thread:
  - **Delete policy** (`cleanup.policy=delete`): drop whole segments older than `retention.ms` or beyond `retention.bytes`. Cheap (unlink files), no per-record work.
  - **Compaction** (`cleanup.policy=compact`): for keyed topics, keep only the *latest* value per key (and tombstones for deletes). Turns the log into a durable snapshot/changelog — perfect for `__consumer_offsets`, CDC, and materialized-view rebuilds. A background compaction thread merges segments, preserving the most recent record per key.
- **Tiered storage**: hot segments on local NVMe; sealed cold segments moved to S3 and fetched on demand. Decouples retention from disk capacity → "store forever" economically, and makes broker scaling/rebalancing far cheaper (less local data to move).

---

## 7. Scaling, Bottlenecks & Failure Handling

### What breaks first

1. **Hot partition / hot broker** — skewed keys concentrate load on one leader. *Fix*: better partitioner, composite keys, more partitions, leader rebalancing across brokers.
2. **Rebalance storms** — large consumer groups with flapping members cause repeated stop-the-world rebalances. *Fix*: cooperative rebalancing, static membership, tuned `session.timeout.ms`.
3. **Consumer lag** — consumers fall behind the head; lag grows toward retention and risks data expiry before consumption. *Fix*: alert on lag, add consumers (up to partition count), scale partitions, or shed load.
4. **Disk fills** — retention misconfig or a lagging follower pinning old segments. *Fix*: tiered storage, retention enforcement, monitoring.
5. **ISR shrink under load** — followers can't keep up, ISR collapses to the leader; with `min.insync.replicas=2` writes start failing. *Fix*: faster replication network, fewer leaders per broker, throttle.

### How to scale each axis

- **Write throughput** → add partitions (more leaders, spread across more brokers) and add brokers. Producers batch + compress harder.
- **Read throughput** → add consumer instances (≤ partition count) and consumer groups; rely on page cache for tail reads; add **read replicas / follower fetching** (`fetch.from.follower` for rack-locality reduces cross-AZ egress).
- **Storage** → tiered storage to S3; bigger/more disks; shorter retention.
- **Brokers** → adding a broker triggers partition reassignment (move replicas to balance load). Tiered storage makes this near-instant since cold data needn't be copied.

### Replication & DR

- Within a region: RF=3 spread across **3 AZs** (rack awareness ensures replicas of a partition land in different AZs) → survive a full AZ outage with no data loss.
- Cross-region: asynchronous **MirrorMaker 2** (or a stretched cluster with `min.insync.replicas` per region). Async mirroring means **RPO > 0** on regional failover — acknowledge that data in flight may be lost. A stretched 2.5-region (two data + one tiebreaker) cluster gives RPO=0 at the cost of cross-region write latency.

### Backpressure, circuit breakers, poison pills

- **Pull model = built-in backpressure**: consumers fetch at their own rate; a slow consumer simply lags, it doesn't crash the broker. This is the key advantage over push (a push broker must buffer or drop when consumers can't keep up; SQS handles this with visibility timeouts + redrive).
- **Producer backpressure**: when the producer's send buffer (`buffer.memory`) fills (broker slow / ISR shrunk), `send()` blocks or throws — surfacing pressure to the application instead of unbounded memory growth.
- **Quotas**: per-client byte-rate and request-rate quotas protect the cluster from a noisy tenant (the broker throttles by delaying responses).
- **Poison pills / DLQ**: a record that repeatedly fails processing blocks its partition (you can't skip past it without advancing offset). Solution: catch, route the bad record to a **dead-letter topic**, commit, and continue. The consumer framework owns this — the broker has no concept of "failed delivery."

---

## 8. Trade-offs & Alternatives

### Explicit decisions

| Decision | Chosen | Why / alternative |
|---|---|---|
| **Pull vs push** | Pull (consumer-driven) | Natural backpressure, batching, replay. Push (RabbitMQ/SQS) lowers latency but needs flow control + per-message ack tracking. |
| **Per-partition order** | Yes; no global order | Global order serializes everything → no horizontal scale. |
| **Durability source** | Replication, not fsync | Replicate to ISR for low-latency acks; relying on synchronous fsync per record would tank throughput. |
| **Consistency** | CP per partition, tunable | `min.insync.replicas` + unclean election slide toward AP if the business prefers availability over durability. |
| **Metadata store** | KRaft (self-hosted Raft) | Removes ZooKeeper bottleneck/dependency; scales to millions of partitions. |
| **Storage engine** | Append-only segmented log | Sequential I/O + zero-copy; vs. LSM/B-tree which add write/read amplification and break sendfile. |
| **Offset ownership** | Consumer-side cursor | Enables multi-subscriber + replay; broker stays dumb/stateless about consumption. |

### vs. SQS / Pub-Sub / Pulsar

- **AWS SQS**: a *queue*, not a log. No replay (consumed → deleted after ack/visibility timeout), no ordering (standard) or limited ordering (FIFO), push-ish with visibility timeouts and DLQ/redrive built in. Best when you want zero ops and don't need replay or high fan-out. Kafka wins on throughput, replay, and multi-consumer fan-out.
- **Google Pub/Sub**: push *and* pull, auto-scaling, global, ack-per-message with redelivery. Great managed fan-out; weaker on strict per-key ordering and on the "log as system of record" replay story.
- **Apache Pulsar**: similar feature set but **separates compute (brokers) from storage (BookKeeper)** — stateless brokers, easier elasticity, but more moving parts and a second consensus system. A legitimate "what would you change at 100x" answer.
- **Redpanda**: Kafka-compatible, C++/thread-per-core, no JVM/page-cache reliance, lower tail latency — same design, different implementation choices.

### At 10x / 100x scale

- **10x (100M msg/s)**: lean hard on **tiered storage** (local disk becomes a cache), **cooperative rebalancing + static membership** to kill rebalance pain, **follower fetching** to cut cross-AZ egress cost, and per-tenant **quotas**. Split mega-topics; automate partition rebalancing.
- **100x**: move to **compute/storage separation** (Pulsar/BookKeeper or Kafka-on-S3 designs like WarpStream/Freight where brokers are stateless and S3 is the log) — elasticity and cost (no triple-replicated local NVMe; S3 already replicates) become the dominant concerns, and stateless brokers make autoscaling and multi-AZ trivial. Trade local-disk latency for object-store latency, mitigated by aggressive caching and batching.

---

## Interview Q&A by Level

### 🟢 Basic

**Q: What is a partition and why does the system need them?**
A partition is an independent, totally-ordered append-only log within a topic. Partitions are the unit of parallelism: more partitions = more producer/consumer throughput and more consumer-group concurrency. They're also the unit of ordering — order is guaranteed *within* a partition, not across.

**Q: What is an offset?**
A monotonically increasing 64-bit id assigned to each record within a partition. It's the record's address and the consumer's cursor. Consumers track and commit offsets; the broker doesn't track who read what.

**Q: How does the same record get delivered to multiple applications?**
Each application uses its own **consumer group** with its own committed offsets. The log is immutable, so any number of groups can read it independently, at different speeds, replaying as needed. This is fan-out.

**Q: Why does the queue use a pull model?**
So consumers control their own rate (natural backpressure), can batch fetches efficiently, and can rewind to replay. A slow consumer just lags; it never overwhelms the broker.

### 🟡 Intermediate

**Q: How is ordering guaranteed, and what's its scope?**
Per-partition total order only. Producers route records by `hash(key) % N`, so all records for a given key land in one partition and are stored/served in publish order. There is **no** global ordering across partitions — that's the price of horizontal scale.

**Q: Walk me through the three `acks` modes.**
`acks=0`: fire-and-forget, no ack, lowest latency, lossy. `acks=1`: leader persists and acks before followers replicate — fast, but a leader crash before replication loses the record. `acks=all`: ack only after all in-sync replicas have it (combined with `min.insync.replicas`) — durable, higher latency.

**Q: How do you achieve at-least-once vs at-most-once?**
It's purely *when you commit the offset*. Commit **after** processing → at-least-once (crash before commit ⇒ reprocess; make downstream idempotent). Commit **before** processing → at-most-once (crash before processing ⇒ lost). At-least-once is the sane default.

**Q: What is log compaction and when would you use it?**
A retention mode that keeps only the latest value per key (plus tombstones for deletes), instead of deleting by time. Use it when the topic represents *current state*, not events — e.g., `__consumer_offsets`, CDC changelogs, or a "user profile by id" topic you want to fully rebuild from.

### 🟠 Advanced

**Q: Explain ISR, High Watermark, and how they make data durable.**
The ISR is the set of replicas caught up to the leader. The High Watermark is the highest offset replicated to *all* ISR members; consumers only see records ≤ HW. A write with `acks=all` is acked only once it's in the HW, i.e., on every ISR replica. So a committed record survives as long as one ISR replica survives, and leader election (only from the ISR) guarantees the new leader has every committed record.

**Q: What happens on leader failure, and what are leader epochs for?**
The controller elects a new leader from the ISR (so no committed data is lost). Leader epochs — a monotonically increasing term number per leadership — let followers correctly truncate any divergent log suffix from the old leader. Without epochs, HW-based truncation alone could silently lose or duplicate records after a failover. Unclean leader election (electing outside the ISR) is off by default because it trades durability for availability.

**Q: Why are consumer-group rebalances painful, and how do you reduce the pain?**
A rebalance reassigns partitions when membership changes. Eager rebalancing is stop-the-world: everyone revokes all partitions and pauses processing. Fixes: **cooperative/incremental** rebalancing (only move partitions that must move), **static membership** (fixed `group.instance.id` so rolling restarts don't trigger reassignment), and tuned heartbeat/session timeouts so transient blips don't look like departures.

**Q: How do you get exactly-once?**
Two mechanisms. Idempotent producer (`producer_id` + per-partition sequence numbers) lets the broker dedup retried batches, killing producer-side duplicates. Transactions atomically commit writes across partitions *and* the consumed offsets, with a transaction coordinator and `read_committed` consumers skipping aborted records — giving atomic consume-process-produce. It's exactly-once *within Kafka*; crossing to an external system still needs idempotency keys or a transactional outbox.

### 🔴 Expert

**Q: Where does this system sit on CAP, and how is that tunable?**
Per partition it's **CP**: under a partition, the minority side stops accepting writes to preserve consistency/durability. But it's *tunable* — `min.insync.replicas` controls how many replicas must ack, and `unclean.leader.election.enable=true` lets you promote an out-of-sync replica to stay available at the risk of losing committed data, sliding the partition toward **AP**. The interview-grade point: the same system can be operated as CP or AP per topic via config; it's a spectrum, not a binary.

**Q: How would you handle a single hot key carrying 1000x the traffic of others?**
`hash(key)` pins it to one partition/leader → that broker saturates. Options, all with trade-offs: (1) composite key `key#bucket` to fan it across M partitions, sacrificing strict per-key order (re-sequence downstream if needed); (2) a custom partitioner that spreads the hot key while keeping cold keys stable; (3) isolate the hot tenant on a dedicated topic/cluster with quotas. There's no way to keep strict single-key order *and* spread the load — you must give up one.

**Q: Why was ZooKeeper removed (KRaft), and why does it matter at scale?**
ZooKeeper was an external coordination service holding all cluster metadata. At hundreds of thousands of partitions, metadata propagation and watch fan-out became a bottleneck and failover was slow; it was also a separate system to operate and a split-brain risk. KRaft folds metadata into an internal **Raft-replicated compacted log** owned by a controller quorum. Result: metadata scales like a regular topic, leadership failover is much faster, and there's one fewer system to run — reusing the same log primitive that powers the data plane.

**Q: How would you cut cost and improve elasticity at 100x scale, and what do you give up?**
Move to **compute/storage separation** with object storage as the system of record (Pulsar+BookKeeper, or Kafka-on-S3 designs like WarpStream/Freight). Brokers become stateless caches in front of S3, so autoscaling, AZ-failure tolerance, and rebalancing become trivial (no local triple-replicated NVMe to copy), and S3 already gives 11-nines durability cheaply. The cost is **latency**: S3 round-trips are tens of milliseconds vs sub-ms local disk, so you batch aggressively and cache hot segments — accepting higher p99 produce latency in exchange for radically lower cost and operational simplicity. You also take on a dependency on object-store availability and must engineer around its consistency/throughput limits.

**Q: A consumer is falling behind and lag is approaching the retention window. Walk through your response.**
First, confirm it's real lag (committed offset vs HW) and whether it's broad (all partitions) or a few (likely hot/skewed partitions or a poison pill stalling one partition). Short term: add consumers up to partition count, increase fetch sizes/parallelism, and temporarily extend retention so data isn't expired before it's read. If a poison pill is stalling a partition, route the bad record to a DLQ and commit past it. Structurally: if the bottleneck is per-partition throughput, increase partitions (accepting the key-remap caveat); if it's processing cost, scale the consumer's downstream or shard the work. Throughout, alert on lag as a first-class SLI — lag, not broker CPU, is the metric that signals data-loss risk.

---

*Key references for further study: the Kafka commit-log design, KRaft/Raft for metadata, ISR + leader-epoch replication, idempotent/transactional producers for EOS, log compaction, and tiered/object-store storage architectures (Pulsar, WarpStream).*
