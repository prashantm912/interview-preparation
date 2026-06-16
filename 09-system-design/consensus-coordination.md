# Consensus & Coordination

A staff-engineer's interview reference for the algorithms and infrastructure that let unreliable nodes agree: Paxos and Raft, the leader-election problem, the coordination services (ZooKeeper, etcd, Consul) that productionize them, and the primitives built on top — distributed locks, leases, quorums, fencing, and split-brain avoidance. The throughline is *why* coordination is expensive and how real systems keep it off the hot path. Knowledge current through 2026.

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

### Q1. [Theory] What problem does distributed consensus actually solve, and why is it hard?

**Consensus** is getting a group of nodes to agree on a single value (or, more usefully, an ordered sequence of values — a *log*) even though nodes can crash, restart, and messages can be delayed, reordered, or lost. The formal properties a consensus protocol must satisfy are **agreement** (no two correct nodes decide different values), **validity** (the decided value was actually proposed by someone), and **termination** (correct nodes eventually decide). Get all three despite failures and you have consensus.

It is hard because of the combination of *partial failure* and *asynchrony*. A node that is slow is indistinguishable from a node that has crashed — there's no reliable way to tell "it's dead" from "the reply is still in flight." So you can't simply wait for everyone; you must make progress with only a subset responding, yet still guarantee that two subsets never decide differently. The FLP impossibility result formalizes that in a fully asynchronous network you can't guarantee both safety and termination with even one crash, so real protocols lean on timeouts (partial synchrony) to make progress.

The reason this matters in practice: almost every "hard" distributed problem reduces to consensus. Electing a single leader, deciding cluster membership, agreeing on a configuration change, implementing a distributed lock, committing a transaction across replicas — all are consensus in disguise. That's why a handful of consensus implementations (etcd, ZooKeeper, Consul) sit underneath an enormous amount of infrastructure: solve it once, well, and reuse it.

### Q2. [Theory] What is leader election and why do so many systems elect a single leader?

**Leader election** is the process of having a group of nodes agree on exactly one of them to act as the coordinator (primary) for some period. The leader typically becomes the single writer or the single decision-maker; followers replicate from it and step in only if it fails.

The reason single-leader designs are everywhere is that they make correctness *dramatically* simpler. With one writer, you get a natural total order of operations (the order the leader chose), you avoid write-write conflicts entirely, and reasoning about the system collapses to "what did the leader do, and did the followers catch up." Compare that to a leaderless/multi-master system where concurrent writes to the same key must be detected (vector clocks) and merged (CRDTs, last-write-wins) — far more complex. Single-leader is the default for Kafka partitions, primary/replica databases, Raft/Zab clusters, and most HA control planes.

The cost is that the leader is a throughput bottleneck (everything funnels through it) and a failure point (you need fast, *safe* failover). The whole discipline of leader election exists to make that failover safe: ensure that when the old leader dies or is partitioned away, exactly one new leader emerges, and the old one can't keep acting as if it's still in charge. That last clause — preventing two simultaneous leaders — is the entire game, and it's why naive "just promote a replica" failover causes split-brain.

### Q3. [Theory] What is a quorum, and why do consensus clusters use an odd number of nodes?

A **quorum** is the minimum number of nodes that must participate for a decision to count. In consensus systems the quorum is a strict **majority**: `⌊N/2⌋ + 1` of N nodes. The magic of majority quorums is that *any two majorities overlap in at least one node*. Since a node won't vote for two conflicting decisions, that overlap guarantees you can never get two conflicting majorities — which is exactly how split-brain is prevented.

```
N = 5, majority = 3
Decision A needs:  [n1][n2][n3]
Decision B needs:       [n3][n4][n5]
                         ↑ overlap (n3) — n3 refuses to back both → only one wins
```

Odd numbers are preferred because they maximize fault tolerance *per node added* without ever allowing a tie. With `N=3` you tolerate 1 failure (majority 2); with `N=4` you *also* tolerate only 1 failure (majority 3) — the fourth node bought you nothing for availability and added cost and latency. Worse, an even cluster can split exactly in half (2 vs 2) with neither side holding a majority, so *neither* side can make progress: total unavailability. Odd counts (3, 5, 7) avoid the tie and give you `f` tolerated failures for `2f+1` nodes.

This is why you see `3` and `5` constantly in etcd/ZooKeeper deployments. Five nodes (tolerates 2 failures) is the common sweet spot for important control planes; seven is rare because each added member slows every commit (more nodes to hear back from) for diminishing fault-tolerance gains.

### Q4. [Theory] What is split-brain, and what's the simplest mechanism that prevents it?

**Split-brain** is when a network partition divides a cluster into groups that can't talk to each other, and *more than one group decides it's in charge* — each elects its own leader and accepts writes. The two sides then diverge, and reconciling conflicting writes afterward is painful or impossible (which write to the same bank balance wins?). It's the defining failure of naive primary/replica failover: the replica can't tell "primary is dead" from "I'm partitioned away from a perfectly healthy primary," so if it self-promotes, you now have two primaries.

```
            ╳ partition ╳
   [n1] [n2]      |      [n3] [n4] [n5]
   2 nodes        |      3 nodes
   minority       |      majority
   → cannot get   |      → has majority,
     a majority   |        keeps serving
   → goes read-   |
     only/down    |
```

The simplest robust prevention is the **majority quorum** from Q3: require a strict majority to elect a leader or commit a write. Because at most one partition can contain a majority, at most one side makes progress; the minority side must stop accepting writes (become read-only or unavailable). This is a deliberate trade of availability for consistency on the minority side — exactly the CP choice in CAP.

For clusters split across just two data centers (where a 50/50 split is possible), you add a lightweight **witness / tiebreaker** node in a third location so one side always holds the majority. Quorum is necessary but, as we'll see, not *sufficient* — a node can still *believe* it's leader during a pause, which is why leases and fencing tokens exist on top.

### Q5. [Practical] You have a cron job that must run on exactly one of your N app servers. How do you coordinate that?

The naive approaches fail: running it on all N servers does the work N times (and may double-charge, double-email, etc.), and hard-coding it to "server 1" means it silently stops when server 1 is down. You need a coordinated, fault-tolerant choice of a single runner — a leader-election problem.

The clean answer is to lean on a coordination service rather than build consensus yourself. With **etcd**, you acquire a lease and put a key with that lease; the holder is the leader and runs the job, renewing the lease as a heartbeat. If it dies, the lease expires, the key vanishes, and another instance acquires it. With **ZooKeeper**, you use the standard leader-election recipe: each candidate creates an ephemeral sequential znode under a parent, and the one with the lowest sequence number is leader; the ephemeral node disappears automatically when its session dies.

```bash
# etcd-style: only one wins the lease-backed key; it becomes the runner
etcdctl lease grant 15                      # → lease 694d...; 15s TTL
etcdctl put --lease=694d... /cron/leader "host-A"   # succeeds → I'm leader
# host-A keeps the lease alive (keepalive heartbeat) while it runs the job
# if host-A dies, lease expires in ≤15s, key is deleted, others can acquire
```

The subtlety to call out in an interview: the job must tolerate the *gap* and the *overlap* at failover. If the leader dies mid-run, another node will pick it up — so the job should be **idempotent** (safe to re-run) and ideally checkpoint progress. And during a leader handover there can be a brief window where the old leader (paused, not yet noticing its lease lapsed) and the new one both think they're leader, so for truly exclusive side effects you also want a fencing token (covered later). For most internal cron jobs, "idempotent + lease-based single runner" is the pragmatic, correct design.

### Q6. [Theory] What is a lease, and how is it different from a lock?

A **lease** is a lock with a built-in expiry: a grant of some right (to be leader, to hold a lock, to own a resource) that is valid only for a bounded time and must be **renewed** to stay alive. If the holder stops renewing — because it crashed, hung, or got partitioned — the lease lapses automatically and someone else can take over. This is the key difference from a plain lock: a classic lock with no timeout, held by a process that crashes, is held *forever* (a deadlock); a lease self-heals because it expires.

The reason leases are the workhorse of distributed coordination is that they convert an *unanswerable* network question into an *answerable* local one. "Is the leader still alive?" can't be answered reliably over a network (a slow node looks dead). But "has my lease expired?" is answered by looking at the local clock against the grant time. So the leader can decide *for itself* to stop acting once its lease *could* have lapsed, and a would-be successor knows to wait until the old lease *must* have lapsed before taking over. Their authoritative windows don't overlap — provided clock skew is accounted for.

```
Leader holds lease until t=10s, renews every 3s
 t=0   acquire, valid → 10
 t=3   renew,   valid → 13     (heartbeat)
 t=6   renew,   valid → 16
 t=6.. leader GC-pauses for 12s — no renewals
 t=16  lease EXPIRES; leader must self-demote at/just-before 16
 t=16  successor sees expiry, waits skew margin, becomes leader
```

The danger lives in that pause case: a paused leader might wake at t=18 still *believing* it's leader. So a safe design has the leader stop serving a safety margin *before* nominal expiry (accounting for clock skew), and pairs leases with fencing tokens so even a confused zombie can't do damage. ZooKeeper sessions, etcd leases, and Chubby leases are all this pattern.

### Q7. [Practical] What is ZooKeeper, and what kinds of problems do teams use it for?

**ZooKeeper** is a coordination service: a small, strongly-consistent, highly-available store that exposes a filesystem-like API of **znodes** (a hierarchical namespace of small data nodes). It isn't a general database — you don't put your application data in it — it's purpose-built to hold the *coordination metadata* that a cluster of services needs to agree on, and to do so safely under failures. Internally it replicates via the **Zab** (ZooKeeper Atomic Broadcast) consensus protocol across an ensemble of (usually 3 or 5) servers, so it survives a minority of failures while keeping a single consistent view.

What makes it useful are a few primitives: **ephemeral znodes** that automatically disappear when the client session that created them ends (perfect for "is this node alive?" presence and for locks that auto-release on crash); **sequential znodes** that get a monotonically increasing suffix (the basis for fair locks and leader election); and **watches**, one-shot notifications that fire when a znode changes (so clients react to events instead of polling).

```
/services
   /payments
      /lock-0000000017   (ephemeral+sequential)  ← lowest seq = lock holder
      /lock-0000000018   (waiting; watches 0017)
   /config
      /db-url            (persistent, watched by all payment nodes)
```

Teams use it for **leader election**, **distributed locks**, **group membership / service discovery** (who's alive right now), **configuration management** (push a config change and all watchers react), and **distributed barriers/queues**. Historically it was the coordination backbone of Hadoop, HBase, Kafka (for controller election and metadata, before KRaft), and Solr. The mental model: ZooKeeper is where you store the *small, critical, must-be-consistent* truths a distributed system coordinates around — not your bulk data.

### Q8. [Coding] Implement a simple lease-based "single leader" guard that a service can call to check if it should act.

**Problem:** Many instances run the same code. Each periodically calls `tryRenewLeadership()`. Exactly one should believe it's leader at a time. Model a lease against a shared store (here abstracted as a `LeaseStore` with an atomic compare-and-set). The guard must self-demote if it fails to renew before the lease expires.

```java
import java.time.Duration;

public class LeaseLeaderGuard {
    interface LeaseStore {
        // Atomically: if no valid lease OR lease is held by `me`, extend it to
        // expiry=now+ttl and return true. Otherwise return false. CAS-based.
        boolean acquireOrRenew(String key, String me, long expiryEpochMs);
    }

    private final LeaseStore store;
    private final String key;
    private final String me;          // unique instance id
    private final long ttlMs;
    private final long skewMs;        // clock-skew safety margin
    private volatile long heldUntilMs = 0;

    public LeaseLeaderGuard(LeaseStore store, String key, String me,
                            Duration ttl, Duration skew) {
        this.store = store; this.key = key; this.me = me;
        this.ttlMs = ttl.toMillis(); this.skewMs = skew.toMillis();
    }

    /** Call on a timer (e.g. every ttl/3). Returns true if WE are leader. */
    public boolean tryRenewLeadership() {
        long now = System.currentTimeMillis();
        if (store.acquireOrRenew(key, me, now + ttlMs)) {
            heldUntilMs = now + ttlMs;
            return true;
        }
        heldUntilMs = 0;              // lost the lease
        return false;
    }

    /** Call before doing leader-only work. Self-demotes near expiry. */
    public boolean isLeaderNow() {
        // Stop acting a safety margin BEFORE nominal expiry to cover skew/pause.
        return System.currentTimeMillis() < (heldUntilMs - skewMs);
    }
}
```

**Time:** O(1) per call (one store round trip on renew). **Space:** O(1) per instance.

**Edge cases and why it's built this way:** the `acquireOrRenew` must be a *single atomic* compare-and-set in the store (a Lua script in Redis, a transaction in etcd) — checking "is it free?" then "take it" in two steps races, and two instances become leader. The `skewMs` margin in `isLeaderNow()` is the critical safety bit: a GC pause can leave `heldUntilMs` in the future while the real lease has already been handed to someone else, so we stop acting *early*. This guard alone still permits a brief overlap window during pathological pauses; for exclusive side effects you must add a **fencing token** (Q19) at the resource so a zombie's writes are rejected. Finally, the renew timer should fire well before expiry (commonly `ttl/3`) so one missed heartbeat doesn't immediately demote you.

---

## 🟡 Intermediate (3–7 yrs)

### Q9. [Theory] Walk through basic Paxos: the Prepare/Promise and Accept/Accepted phases. What is each phase protecting against?

**Basic Paxos** gets a set of nodes to agree on a *single* value despite crashes and lost messages, using three roles (a node can play several): **proposers** suggest values, **acceptors** vote, **learners** observe the outcome. The protocol runs in two phases built around monotonically increasing, globally-unique **proposal numbers**.

**Phase 1 — Prepare/Promise.** A proposer picks a proposal number `n` higher than any it has used and sends `Prepare(n)` to a majority of acceptors. Each acceptor, if `n` is higher than any prepare it has already promised, replies `Promise(n)` — a commitment *not* to accept any proposal numbered less than `n` — and crucially includes *the highest-numbered value it has already accepted, if any*. This phase is doing two jobs: it's a lightweight leader-election-for-this-round (only the highest `n` gets promises), and it's *discovering any value that might already be chosen* so the proposer doesn't overwrite it.

**Phase 2 — Accept/Accepted.** If the proposer got promises from a majority, it sends `Accept(n, v)`. The value `v` is *not* free to choose: if any acceptor reported a previously-accepted value in Phase 1, the proposer **must** propose the value with the highest accepted proposal number it saw; only if none reported a value may it propose its own. Acceptors accept `(n, v)` unless they've since promised a higher number. Once a majority accepts, the value is **chosen**.

```
Proposer P            Acceptors (need majority)
  Prepare(n) ───────► A1 A2 A3
  ◄─ Promise(n, prevAccepted?) ── (highest accepted value, if any)
  pick v = highest prevAccepted, else my own value
  Accept(n, v) ─────► A1 A2 A3
  ◄─ Accepted(n, v) ──  majority → CHOSEN
```

The genius — and the source of Paxos's reputation for being hard — is the Phase-1 rule that forces a new proposer to *adopt the possibly-chosen value rather than impose its own*. That's what guarantees **safety**: once a value could have been chosen by a majority, every higher-numbered round is steered to the same value, so two different values can never both be chosen. The two phases protect against, respectively, stale/competing proposers (Promise refuses lower numbers) and overwriting an already-decided value (the must-adopt rule).

### Q10. [Theory] What is Multi-Paxos, and why isn't basic Paxos used directly to build a replicated log?

Basic Paxos decides *one* value. A real system needs to agree on a *sequence* of values — a replicated log of commands, one per "slot/index" — so the obvious approach is to run a separate Paxos instance per log slot. That works for safety but is wildly inefficient: every single command would pay two full round trips (Prepare then Accept) plus the risk of *dueling proposers*, where two proposers keep pre-empting each other with ever-higher proposal numbers and neither makes progress (a liveness failure — the FLP escape hatch is needed).

**Multi-Paxos** is the standard optimization: elect a stable **distinguished proposer (leader)** and let it run Phase 1 *once* for a whole range of future slots. Having established itself as the highest proposal number across the log, the leader can then commit each new command with only **Phase 2** (a single round trip of `Accept`), because there's no competing proposer to discover. Phase 1 only re-runs when leadership changes (a leader crashes and a new one takes over).

```
Basic Paxos per slot:  Prepare + Accept  → 2 RTT per command
Multi-Paxos steady:    Prepare ONCE (leader election),
                       then Accept-only  → 1 RTT per command
```

The reason basic Paxos isn't used directly is precisely this: it under-specifies the log, leadership, membership changes, and the steady-state optimization, leaving enormous engineering gaps. Lamport's papers describe the safety core beautifully but a practitioner has to invent the rest. This gap is the entire motivation for Raft, which bakes in a strong always-present leader, a concrete log structure, and explicit leadership/membership-change procedures — making Multi-Paxos-equivalent behavior the *default, specified* path rather than something you reconstruct.

### Q11. [Theory] Walk through Raft leader election in detail, including the term mechanism and the up-to-date vote rule.

Raft divides time into **terms** — monotonically increasing integers that act as a logical clock and as an epoch number. At most one leader exists per term. Every node is a **follower**, **candidate**, or **leader**. Followers expect periodic `AppendEntries` heartbeats from the leader; if a follower hears nothing for its randomized **election timeout** (commonly 150–300 ms), it suspects the leader is gone and starts an election.

To start an election, the follower **increments its term**, becomes a candidate, votes for itself, and sends `RequestVote` RPCs to all peers carrying its term and its last log entry's index+term. Each node grants **at most one vote per term** and only to the *first* candidate it hears from in that term whose log is **at least as up-to-date** as its own. A candidate that collects votes from a **majority** becomes leader and immediately starts sending heartbeats to suppress further elections. If it discovers a higher term (from a peer or a heartbeat), it steps down to follower.

```
Follower ──(no heartbeat for election timeout)──► Candidate
   ▲                                                  │
   │ (sees higher term, or new leader's heartbeat)    │ wins majority
   │                                                  ▼
   └────────────── steps down ◄─────────────────── Leader
                                            (sends AppendEntries heartbeats)
```

Two design choices make this work. **Randomized timeouts** desynchronize followers so they rarely all become candidates at once; if a split vote *does* happen (no one gets a majority), everyone times out again at different randomized times and one usually wins the next round — self-correcting without any extra machinery. The **up-to-date vote restriction** is the subtle safety lever: a node refuses to vote for a candidate whose log is behind its own (by comparing last-entry term, then index). This guarantees the new leader already holds every committed entry, so the leader never has to *receive* missing committed data — it only ever *pushes* its log to followers. That single restriction is what gives Raft its Leader Completeness property and prevents committed entries from being lost across elections.

### Q12. [Practical] Compare ZooKeeper, etcd, and Consul. When would you pick each?

All three are strongly-consistent, highly-available coordination stores backed by a consensus protocol (ZooKeeper uses Zab; etcd and Consul use Raft), and all provide the same core value: a place to safely store small, critical coordination data with leader election, locks, watches, and ephemeral/TTL keys. The differences are in API, ecosystem, and bundled features.

| Aspect | ZooKeeper | etcd | Consul |
|---|---|---|---|
| Consensus | Zab | Raft | Raft |
| API | Custom (znodes), client libs | gRPC / HTTP, JSON | HTTP/DNS, JSON |
| Data model | Hierarchical znodes | Flat sorted key space (MVCC) | KV + rich service catalog |
| Liveness primitive | Ephemeral nodes + sessions | Leases + keepalive | Sessions + TTL/health checks |
| Watch model | One-shot watches | Streaming watch from a revision | Blocking queries / watches |
| Standout feature | Mature, battle-tested recipes | Kubernetes' datastore; clean MVCC | Built-in service discovery, health checks, DNS, multi-DC, service mesh |
| Typical home | Hadoop/Kafka-era big-data stacks | Cloud-native / Kubernetes | Service discovery & mesh, HashiCorp stack |

**Pick etcd** when you're in the cloud-native/Kubernetes world (it *is* the Kubernetes control-plane store), you want a clean gRPC key-value API with MVCC revisions (great fencing tokens), and you primarily need config + leader election + locks. **Pick Consul** when service discovery and health checking are first-class needs — it bundles a service catalog, DNS interface, health checks, multi-datacenter support, and a service mesh, so it's more than a raw coordination store. **Pick ZooKeeper** when you're integrating with an ecosystem built around it (older Kafka, HBase, Solr, Hadoop) or you specifically want its very mature, well-understood recipes and you already operate it.

The honest staff-level take: for a greenfield system, etcd or Consul are usually the better default (modern APIs, active cloud-native momentum), and ZooKeeper is chosen mostly for ecosystem compatibility. All three solve the *same* underlying problem; don't run more than one if you can avoid it — each is an operationally sensitive, quorum-based dependency you must place across failure domains and back up carefully.

### Q13. [Coding] Implement the ZooKeeper distributed-lock recipe (ephemeral sequential znodes) in pseudocode/Java.

**Problem:** Build a mutually-exclusive lock across many processes using ZooKeeper. It must be *fair* (FIFO), must not stampede (avoid the herd effect where everyone wakes on every release), and must auto-release if the holder crashes.

```java
public class ZkLock {
    private final ZooKeeper zk;
    private final String lockRoot;   // e.g. "/locks/payments"
    private String myNode;           // my created znode path

    public void lock() throws Exception {
        // 1. Create my EPHEMERAL + SEQUENTIAL node. Ephemeral => auto-deleted
        //    if my session dies (crash-safe release). Sequential => fair order.
        myNode = zk.create(lockRoot + "/lock-",
                           new byte[0],
                           OPEN_ACL_UNSAFE,
                           CreateMode.EPHEMERAL_SEQUENTIAL);

        while (true) {
            List<String> children = zk.getChildren(lockRoot, false);
            Collections.sort(children);                  // by sequence suffix
            String smallest = children.get(0);

            if (myNode.endsWith(smallest)) {
                return;                                  // I hold the lock
            }
            // 2. Watch ONLY my immediate predecessor — not the whole set.
            String myName = myNode.substring(myNode.lastIndexOf('/') + 1);
            String predecessor = predecessorOf(children, myName);

            CountDownLatch latch = new CountDownLatch(1);
            Stat exists = zk.exists(lockRoot + "/" + predecessor,
                                    event -> latch.countDown());
            if (exists != null) {
                latch.await();                           // sleep until predecessor goes
            }
            // loop re-checks: predecessor gone may mean it crashed, not unlocked
        }
    }

    public void unlock() throws Exception {
        zk.delete(myNode, -1);   // releasing => everyone behind me re-evaluates
        myNode = null;
    }
}
```

**Time:** acquire is O(children) per check; with predecessor-watching, each release wakes exactly **one** waiter. **Space:** O(1) per client.

**Why each choice matters — this is the whole point of the recipe:**
- **Ephemeral** node: if the lock holder crashes, its session ends and ZooKeeper deletes the node automatically, so the lock can't be held forever by a dead process (no manual timeout needed).
- **Sequential** suffix: gives a globally agreed FIFO order, so the lock is *fair* — no starvation.
- **Watch only your predecessor**, not the parent: if all 500 waiters watched the parent, every release would wake all 500 (the **herd effect**) and they'd all re-read and re-watch — O(N²) thundering herd. Watching just the node immediately ahead of you means a release wakes exactly one client.

**Edge cases:** the watch is one-shot, so you re-read and re-establish it on every wakeup (the `while(true)` loop). A disconnected session means you may have *lost* the lock without knowing — production clients (Apache Curator's `InterProcessMutex`) handle session-loss by treating the lock as lost and surfacing it, and you should still apply a **fencing token** for correctness-critical resources because even ZooKeeper can't stop a GC-paused holder from waking and acting.

### Q14. [Theory] What guarantees does ZooKeeper actually provide (and not provide), and what is the "sync" gotcha for reads?

ZooKeeper provides several precise guarantees that you must understand to use it correctly. Writes are **linearizable** and **totally ordered**: all writes go through the leader and are sequenced by Zab into a single global order, stamped with a monotonically increasing **zxid** (ZooKeeper transaction id). For a single client, it guarantees **FIFO client order** — your operations are applied in the order you issued them. Updates are **atomic** (no partial writes) and **durable** once acknowledged by a quorum.

The crucial *non*-guarantee: **reads are not linearizable by default.** To be fast, any follower can serve a read from its *local* replica without contacting the leader. That replica might be slightly behind, so you can read **stale** data — you might not see a write that has already been committed elsewhere. ZooKeeper only promises that reads are *sequentially consistent* and that you never see data "from the future" or go backwards in your own session. If you need a guaranteed-fresh read, you must call **`sync()`** first, which forces the follower to catch up to the leader before serving your read.

```
Client writes /config = v2 via leader  (committed)
Another client reads /config from a lagging follower → may still see v1 !
   To force freshness:  zk.sync("/config")  then  zk.getData("/config")
```

This matters enormously for coordination correctness. People wrongly assume "ZooKeeper is strongly consistent, so my read is current" and build logic on stale reads — e.g. reading the leader znode and acting, when leadership has already changed. The right mental model: **ZooKeeper gives linearizable writes and a consistent global order, but stale reads unless you `sync()`**, and even then there's a window. For leadership and locks you rely on the *ordering and ephemeral* guarantees plus fencing, not on the freshness of a casual read.

### Q15. [Practical] Your team wants a distributed lock. Walk through the decision between Redis (Redlock) and a consensus store, and what you'd ship.

The first and most important move is to ask the question that determines *everything*: **what happens if two clients hold the lock at the same time?** Locks split cleanly into two categories. **Efficiency locks** prevent *wasted work* — e.g. de-duplicating a cache-refresh or a batch job — where double execution costs money/CPU but causes no corruption. **Correctness locks** protect an invariant — e.g. "only one writer mutates this record" — where double execution causes data corruption, double-charges, or lost updates.

For **efficiency locks**, a simple Redis lock is fine and fast: `SET lockkey ownerToken NX PX 30000` (set if-not-exists with a 30s expiry), and delete it (checking the token via a Lua script) to release. Redlock — acquiring on a majority of independent Redis masters — adds redundancy for the same efficiency use case. The well-known critique (Kleppmann vs. antirez) is that **Redlock is unsafe for correctness** because it relies on timing assumptions: if a client acquires the lock and then suffers a GC pause longer than the TTL, the lock expires, another client acquires it, and when the first wakes it *still believes it holds the lock* — two holders, with no mechanism to stop the zombie from acting.

```
Client A: SET lock NX PX 30000 → OK, holds lock
A: --- 40s GC pause ---            (lock TTL of 30s expires mid-pause)
Client B: SET lock NX PX 30000 → OK, now B holds lock
A: wakes up, still thinks it holds lock → writes!   ← CORRUPTION
```

For **correctness locks**, ship a consensus-backed lock: **etcd** (`lock` API: a lease-backed key whose `mod_revision` is a natural, monotonically increasing fencing token) or **ZooKeeper** (the ephemeral-sequential recipe, with the zxid as the fence). Both survive partitions safely (CP) and give you a fencing token essentially for free. Then enforce the token at the *resource*: the protected store rejects any write carrying a token lower than the highest it has seen, so even a paused zombie can't corrupt anything.

What I'd actually ship: for de-duping work, single-node Redis `SET NX PX` (don't even bother with Redlock's complexity unless the Redis SPOF is unacceptable). For anything where two holders means corruption, an etcd/ZooKeeper lock **plus fencing tokens at the resource** — because no lock service, not even a consensus one, can by itself stop a paused client from believing it still holds the lock.

### Q16. [Coding] Implement leader election over etcd using a lease and a campaign on a key.

**Problem:** Multiple instances compete to be leader. Use an etcd lease so leadership auto-releases on crash, and ensure exactly one instance wins. Show the campaign + keepalive + resign flow.

```java
import io.etcd.jetcd.*;
import io.etcd.jetcd.lease.*;
import io.etcd.jetcd.election.*;
import java.nio.charset.StandardCharsets;

public class EtcdLeaderElector {
    private final Client client;
    private final String electionName = "/election/payments-controller";
    private final String candidateId;     // unique per instance
    private long leaseId;

    public EtcdLeaderElector(Client client, String candidateId) {
        this.client = client; this.candidateId = candidateId;
    }

    /** Blocks until THIS instance becomes leader. */
    public LeaderKey runForLeader() throws Exception {
        // 1. Grant a lease (TTL). Leadership is tied to this lease.
        leaseId = client.getLeaseClient().grant(10).get().getID();

        // 2. Keep the lease alive forever in the background = heartbeat.
        //    If this process dies/hangs, keepalives stop, lease expires (≤10s),
        //    leadership is released automatically.
        client.getLeaseClient().keepAlive(leaseId, new StreamObserver<>() {
            public void onNext(LeaseKeepAliveResponse r) { /* renewed */ }
            public void onError(Throwable t) { /* lease lost — must step down */ }
            public void onCompleted() {}
        });

        // 3. Campaign: etcd's Election API guarantees exactly one leader for the
        //    election key. This call BLOCKS until we win (predecessors resign/die).
        ByteSequence name  = bs(electionName);
        ByteSequence value = bs(candidateId);
        CampaignResponse resp =
            client.getElectionClient().campaign(name, leaseId, value).get();

        return resp.getLeader();   // holds the key + revision (our fencing token!)
    }

    /** Voluntarily give up leadership. */
    public void resign(LeaderKey key) throws Exception {
        client.getElectionClient().resign(key).get();
        client.getLeaseClient().revoke(leaseId).get();
    }

    private static ByteSequence bs(String s) {
        return ByteSequence.from(s, StandardCharsets.UTF_8);
    }
}
```

**Time:** `campaign` blocks until predecessors are gone (FIFO by revision); steady-state keepalive is one cheap RPC per interval. **Space:** O(1).

**Why this is safe and what to watch:** etcd's Election API serializes candidates by the **create-revision** of their keys (lowest wins — same fairness idea as ZooKeeper's sequential nodes), so exactly one leader exists. The **lease + keepAlive** is the liveness mechanism: leadership is *bound to the lease*, so a crash or a long hang stops the keepalives and the lease expires, automatically vacating leadership for the next candidate. The returned `LeaderKey` carries a **revision** that serves as a fencing token — pass it to downstream writes so a stale ex-leader is rejected. The trap to avoid: treating `onError` on the keepalive stream as benign. If you lose the lease, you are **no longer leader** even though your code is still running — you must immediately stop all leader-only work, exactly the zombie-leader hazard. Set the TTL with care: too low causes spurious failovers on transient blips; too high slows recovery.

---

## 🟠 Advanced (8–12 yrs)

### Q17. [Theory] Why can a Raft/Paxos leader only commit entries from its own current term directly? Explain the classic "committed entry overwritten" bug it prevents.

This is one of the subtlest and most important safety rules in Raft, and a favorite deep-dive question. The rule: a leader marks an entry **committed** only once it has replicated an entry **from its own current term** to a majority. It must *not* conclude that an older entry (from a previous term) is committed merely because it now sits on a majority of logs. Older entries get committed *indirectly* — they become committed when a current-term entry above them commits, which by the Log Matching property carries everything below it.

The reason is a real bug that an earlier "commit by majority replication, regardless of term" rule allowed. Consider the canonical scenario from the Raft paper:

```
Term:    1  2          Leaders come and go; an entry from term 2 is replicated
S1: [1][2]             to a majority by a leader, then that leader crashes BEFORE
S2: [1][2]             committing it. A new leader (term 3+) from a different log
S3: [1]                could legitimately get elected (its log is "up to date
S4: [1]                enough"), write its own entry, and OVERWRITE the term-2
S5: [1][3]  ← new      entry on S1/S2 — even though term-2 sat on a majority.
            leader
```

If the system had *declared that term-2 entry committed* simply because it reached a majority, a client would have been told "your write is durable" — and then a perfectly legal subsequent election overwrites it. That's a catastrophic safety violation: a committed entry vanishing.

The fix is exactly the current-term rule. By refusing to *declare* an old-term entry committed until a new-term entry on top of it reaches a majority, Raft ensures that the act of committing also "locks in" everything beneath it via a leader that, by the up-to-date vote rule, can never be ousted by someone lacking that entry. The deep lesson for an architect: **"replicated to a majority" is necessary but not sufficient for "committed"** — the term/leadership context is part of the commit condition, and ignoring it reintroduces a data-loss bug that looks correct in the happy path and only bites during leader churn.

### Q18. [Practical] How do consensus systems handle membership changes (adding/removing nodes) safely, and why is the naive approach dangerous?

Changing the set of voting members is dangerous because the *definition of "majority" changes mid-flight*. If you switch directly from an old configuration `C_old` to a new one `C_new`, there can be a moment when the cluster splits such that a majority of `C_old` and a majority of `C_new` exist *simultaneously and disjointly* — and each could elect its own leader or commit a different value. That's split-brain caused by the reconfiguration itself.

```
C_old = {A,B,C}        C_new = {A,B,C,D,E}
During a naive switch, a partition could give:
  {A,B}  → majority of C_old (2 of 3)  → elects leader X
  {C,D,E}→ majority of C_new (3 of 5)  → elects leader Y
TWO leaders, both "legitimate" under different configs. ✗
```

Raft solves this with **joint consensus** (a two-phase change): the cluster transitions through an intermediate config `C_old,new` in which decisions require majorities of **both** `C_old` *and* `C_new`. Because every decision in the transition needs overlap in both old and new majorities, no disjoint pair of majorities can form, so safety holds throughout. Once `C_old,new` is committed, it switches to `C_new` alone. Many implementations use the simpler **single-server change** restriction instead: add or remove only *one* node at a time, which is provably safe because a one-node difference can't create two disjoint majorities (the old and new majorities always overlap).

The practical guidance: **never change membership by editing config files and restarting nodes** — that's the naive direct switch and it can corrupt the cluster. Use the consensus protocol's reconfiguration mechanism (etcd's `member add/remove`, which adds one at a time and recommends adding a **learner** first), add nodes as non-voting **learners** so they catch up on the log *before* they count toward quorum (a freshly-added empty voter can stall commits), and change one member at a time. Reconfiguration bugs are a notorious source of production consensus outages precisely because they look like simple ops but interact with the deepest safety invariant.

### Q19. [Coding] Implement fencing-token enforcement at a resource so a stale lock holder's writes are rejected.

**Problem:** A lock service hands out a monotonically increasing fencing token with each grant. The protected resource must accept a write only if its token is greater than any token it has already honored, so a paused/zombie ex-holder writing with an old token is rejected — even though it still believes it holds the lock.

```java
import java.util.concurrent.atomic.AtomicLong;

public class FencedResource {
    // Highest fencing token this resource has ever accepted a write under.
    private final AtomicLong highestSeenToken = new AtomicLong(0);

    /**
     * Apply a write only if `token` is at least the highest we've honored.
     * Returns true if applied; false if fenced off (stale holder).
     */
    public boolean write(long token, Runnable applyWrite) {
        while (true) {
            long current = highestSeenToken.get();
            if (token < current) {
                // A newer holder has already acted. This caller is a zombie.
                return false;                       // FENCED — reject
            }
            // CAS the high-water mark up to this token before applying.
            if (highestSeenToken.compareAndSet(current, token)) {
                applyWrite.run();
                return true;
            }
            // CAS lost a race; re-read and retry.
        }
    }
}
```

```
Holder A granted token=33, then GC-pauses.
Lock expires; Holder B granted token=34, writes → resource records 34.
A wakes, still "holds" lock, writes with token=33:
   33 < 34  → write(33) returns false → REJECTED. Invariant preserved. ✓
```

**Time:** O(1) amortized (a CAS loop, uncontended in practice). **Space:** O(1).

**Why this is the linchpin of correct locking:** every lock service from Q15/Q16 can guarantee "at most one leader *per term/grant*," but **none can stop a process from *believing* it's the leader during a pause** — that's an unanswerable network/timing question (Two Generals). Fencing moves the safety check to the one place that *can* decide authoritatively: the resource itself, using a *local monotonic counter*. The token must come from the lock service's own monotonic source — etcd's `mod_revision`, ZooKeeper's `zxid`/sequence number, or a Raft index — so it's genuinely ordered. **Edge cases:** the resource must persist `highestSeenToken` durably (otherwise a resource restart resets the fence and a zombie slips through); the check-and-apply must be atomic with respect to the *actual write* (here the CAS gates the `applyWrite`, but in a real DB you'd enforce it as `UPDATE ... WHERE token > stored_token` in the same transaction); and every path to the resource must enforce fencing, or a single un-fenced path defeats the whole scheme.

### Q20. [Practical] You're running a 5-node etcd cluster and it suddenly can't accept writes, though some reads work. Diagnose and remediate.

First, recognize what etcd is *supposed* to do: it's a CP (Raft) system, so when it can't form a majority it **deliberately refuses writes** to preserve consistency — read-only or write-unavailable is often correct behavior, not a bug. The job is to find out *why* there's no quorum.

**Diagnosis, in order.** Check `etcd_server_has_leader` — if it's 0 on the nodes, there is no leader and thus no writes. Check `etcd_server_leader_changes_seen_total` — a rising count means election churn (flapping leadership). Look at how many members are actually healthy (`etcdctl endpoint health --cluster`): with 5 nodes you need 3 for a majority, so if 3+ are down/unreachable, writes *correctly* stop. Common root causes: (1) **lost quorum** — too many members down or partitioned, so no majority exists; (2) **slow disk** — etcd commits every write to disk via `fdatasync`; a degraded disk blows past the heartbeat/election timeouts and causes constant re-elections (watch `etcd_disk_wal_fsync_duration_seconds` and `etcd_disk_backend_commit_duration_seconds`); (3) the **database size limit** — etcd has a default `--quota-backend-bytes` (~2 GiB historically) and when exceeded the cluster goes into a **read-only alarm (NOSPACE)** that blocks writes until you compact and defragment; (4) **clock/network** issues causing partition.

**Remediation by cause.** If it's the NOSPACE alarm: compact old revisions (`etcdctl compact <rev>`), `defrag` each member, then disarm the alarm (`etcdctl alarm disarm`) — and fix the root cause (something not compacting, or a too-small quota). If it's lost quorum because members are *down*: restart/recover them; if a member is permanently dead, `member remove` it and add a fresh one (one at a time, as a learner first). If you've catastrophically lost a majority and can't recover them, the last resort is to **restore from a snapshot** onto a new single-member cluster and re-grow it — accepting the loss of any writes after the snapshot. If it's slow disk: move etcd to fast local SSD/NVMe (etcd is brutally sensitive to fsync latency) and tune `--heartbeat-interval`/`--election-timeout`.

The staff-level framing: the failure modes for a consensus store are *quorum, disk latency, and space*, in that order of how often they bite. The architectural prevention is placement (spread the 5 members across failure domains so no single rack/AZ holds the majority), dedicated fast disks, automated compaction/defrag, monitoring on `has_leader` and fsync latency, and **regular snapshots** so the worst-case restore path actually works when you need it.

### Q21. [Theory] What is the difference between a session/heartbeat-based liveness model and a lease, and how does ZooKeeper's session expiry actually work?

These are closely related but distinct. A **heartbeat** is just a periodic "I'm alive" signal; a **session** is the server-side notion of a *continuous relationship* with a client, kept alive by heartbeats, that owns resources (like ephemeral nodes) which are destroyed when the session ends; a **lease** is a *time-bounded grant of a right* that must be renewed. ZooKeeper sessions are the clearest production example of how these compose.

When a ZooKeeper client connects, it establishes a **session** with a negotiated **timeout** (e.g. 10s). The client library sends pings (heartbeats) at roughly `timeout/3` to keep it alive. The critical and frequently-misunderstood detail: **session expiry is decided by the ZooKeeper ensemble (the leader), not by the client.** If the leader hasn't heard from a client within the timeout, *it* declares the session dead, deletes the client's ephemeral nodes, and fires the corresponding watches — and it does this through Zab so all servers agree. The client might still be alive but partitioned; from the cluster's authoritative view, it's gone.

```
Client                         ZK ensemble (leader is authoritative)
  ping ──────────────────────► session alive, expiry pushed out
  ping ──────────────────────► ...
  (client partitioned, no pings)
                               leader: timeout elapsed → SESSION EXPIRED
                               → delete client's ephemeral znodes (locks, leader marker)
                               → fire watches; another client takes over
  client reconnects later, learns "your session expired" → must rebuild state
```

This is exactly why the **zombie/stale-holder problem** is unavoidable at the lock layer: there's a window where the *cluster* has expired your session and given your lock to someone else, but *you* (a paused or partitioned client) haven't found out yet and still think you hold it. A correct client must treat session-loss/`Expired` events as "I have lost everything — locks, leadership, ephemeral state — and must stop acting and rebuild." And because the client can't *prevent* this window, resource-side fencing (Q19) remains mandatory for correctness. The general principle: liveness in distributed systems is always judged by the *authority* (the quorum), on its clock, never self-asserted by the participant.

### Q22. [Practical] Design a leader-election-based HA scheme for a stateful singleton service (e.g., a scheduler or a Kafka-style controller). What can go wrong?

The goal: run a single active instance of a control component (a scheduler, a controller, a coordinator) with hot standbys ready to take over fast, with no split-brain. The architecture pairs a consensus store for election with careful handling of the failover seams.

```
            ┌──────── etcd / ZooKeeper (3 or 5 nodes) ────────┐
            │   election key + lease (fencing token = rev)     │
            └───────────────────▲──────────────────────────────┘
                                 │ campaign / keepalive
        ┌──────────┐      ┌──────────┐      ┌──────────┐
        │ scheduler│      │ scheduler│      │ scheduler│
        │ ACTIVE   │      │ standby  │      │ standby  │
        │ (leader) │      │          │      │          │
        └────┬─────┘      └──────────┘      └──────────┘
             │ all writes carry fencing token = lease revision
             ▼
        protected state store / work queue (enforces token monotonicity)
```

**Design.** Each instance campaigns for leadership via etcd/ZooKeeper (Q16/Q13). The winner becomes active; standbys block on the campaign and are ready to take over the instant the leader's lease lapses. The leader continually renews its lease as a heartbeat. Crucially, **every externally-visible action the leader takes carries the fencing token** (the lease revision / zxid), and the downstream resource enforces token monotonicity (Q19). On losing the lease (crash, pause, partition), the leader must *immediately stop* all leader work.

**What goes wrong — the seams:**
- **Zombie leader / dual activity at handover.** The old leader is GC-paused past its lease; a new leader is elected; both briefly think they're active. Fencing tokens are what make this *safe* — without them, two schedulers both dispatch the same job. This is non-negotiable for a stateful singleton.
- **Failover gap.** Between the old leader dying and the lease expiring + new leader catching up, *nothing* is active. Bound it by tuning the lease TTL (shorter = faster failover but more spurious failovers) and by having standbys pre-warm their state so promotion is instant.
- **State recovery.** A scheduler usually has in-memory state (what's scheduled, in-flight). The new leader must reconstruct it — from a durable log, the work queue, or a checkpoint — before acting, or it'll double-dispatch or drop work. This recovery time *is* your real failover time, often dominating the lease TTL.
- **Flapping.** Aggressive timeouts + transient network blips cause leadership to bounce, each bounce paying the recovery cost. Add hysteresis and don't set the lease TTL near your GC pause times.

Real systems do exactly this: Kafka's controller (KRaft now runs a Raft quorum internally; the older model elected the controller via ZooKeeper), Kubernetes controllers use `leader-election` over a `Lease` object in etcd, and HDFS NameNode HA uses ZooKeeper's `ZKFailoverController` with fencing (it will literally STONITH/`fence` the old NameNode to prevent split-brain). The recurring lesson: **election picks one leader; fencing + bounded leases + state recovery are what make the *failover* correct.**

### Q23. [Theory] How does Zab (ZooKeeper Atomic Broadcast) differ from Raft and Paxos, and why does ZooKeeper need a "broadcast" protocol rather than plain consensus?

Zab is ZooKeeper's consensus protocol, and while it's in the same family as Raft/Multi-Paxos (leader-based, majority quorums, replicated ordered log), it's framed as an **atomic broadcast** (totally-ordered reliable broadcast) protocol because that framing matches ZooKeeper's exact need: deliver every state update to every replica, **in the same total order**, so all replicas converge to identical state. ZooKeeper is a *replicated state machine* where the "machine" is the znode tree, and atomic broadcast of the write stream is precisely what keeps the replicas identical.

Zab has two phases. **Recovery/leader-activation:** after a leader fails, a new leader is elected and a **synchronization** phase ensures the new leader has the highest committed history and brings followers up to date *before* serving new requests — this is where Zab is stricter than a generic protocol, because ZooKeeper's primary-order guarantee requires that the new leader strictly extends the previous epoch's committed prefix. **Broadcast:** in steady state the leader assigns each write a **zxid** — a 64-bit value of `(epoch, counter)` where `epoch` increments on each new leadership and `counter` increments per write within an epoch — proposes it to followers, and commits once a quorum acknowledges (a two-phase, Paxos-like commit).

```
zxid = (epoch, counter)
  epoch  → bumped on every new leader (like Raft's term / Paxos proposal #)
  counter→ per-write sequence within that leader's reign
This gives a strict TOTAL ORDER of all writes → atomic broadcast.
```

The differences worth naming: Zab's zxid epoch is essentially Raft's term; both prevent old leaders from corrupting order. The main conceptual distinction is the explicit **primary-order** property Zab guarantees — if a primary broadcasts updates a, then b, every replica delivers a before b, *and* a new primary's updates are ordered after all of the previous primary's delivered updates. Raft achieves equivalent ordering through its log-matching + leader-completeness machinery. In practice they're functionally close; ZooKeeper predates Raft and chose the broadcast framing because the product *is* a replicated store needing totally-ordered delivery, not a generic "agree on one value" library. The interview-relevant point: Zab, Raft, and Multi-Paxos are three points in the same design space (leader + epoch/term + majority-committed ordered log); the framing differs more than the fundamentals do.

### Q24. [Practical] You need a distributed counter / sequence generator that's correct under concurrency across many nodes. Compare the coordination options.

The requirement determines the design entirely, so first pin down two axes: must the values be **strictly monotonic / gapless**, and how much **throughput** do you need? Coordination is expensive, so the art is buying only as much as the invariant demands.

| Approach | Coordination cost | Monotonic? | Gapless? | Throughput | Notes |
|---|---|---|---|---|---|
| Consensus counter (etcd CAS / Raft) | High (quorum per increment) | Yes | Yes | Low (~thousands/s) | Strongest; the counter is itself a consensus decision |
| DB sequence / `AUTO_INCREMENT` | Medium (single primary) | Yes | Usually (gaps on rollback) | Medium | Single writer is the bottleneck and SPOF |
| **Segment/range allocation (Hi/Lo)** | Low (amortized) | Yes (within issuer) | No (gaps on crash) | High | Each node leases a block (e.g. 1000 ids), serves locally |
| Snowflake-style IDs | None (after setup) | Roughly time-ordered | No | Very high | `timestamp | nodeId | seq`; needs unique node ids |
| CRDT counter (PN-Counter) | None | No (eventually converges) | No | Very high | Available under partition; no global order |

The key insight is that **strict monotonicity across nodes is a consensus-hard problem** — every increment must be globally ordered, which means a quorum round trip per value, capping throughput at the consensus rate. So you only pay that when you truly need a single global gapless sequence (rare). The usual production answer is the **segment/Hi-Lo pattern**: a node atomically leases a *block* of ids from a central allocator (a single CAS on etcd or a DB row), then hands them out locally with no further coordination, refilling when the block runs low. This amortizes one coordination op over (say) 1000 ids, giving near-local throughput while keeping ids unique and monotonic *per issuer* — at the cost of **gaps** (a node that crashes mid-block wastes the rest) and only *rough* global ordering.

If you don't need monotonicity at all, just uniqueness and rough time-ordering (e.g. primary keys at scale), **Snowflake-style IDs** eliminate coordination entirely after you've assigned each node a unique id — `timestamp || nodeId || per-ms-sequence` — which is why Twitter, Instagram, and others use this for high-volume id generation. The trap to avoid in an interview: reaching for a consensus counter or a single DB sequence "to be safe" when the requirement was merely *unique* ids — that needlessly puts a coordination bottleneck on your hottest path. Match the coordination to the invariant: gapless-global → consensus; monotonic-per-issuer + high throughput → segments; unique + rough order → Snowflake; available-under-partition → CRDT.

---

## 🔴 Expert (15+ yrs)

### Q25. [Theory] How does Google Chubby embody the philosophy "centralize coordination so applications don't have to do consensus themselves," and what design lessons came out of it?

Chubby is Google's distributed lock service (Burrows, OSDI 2006) and the spiritual ancestor of ZooKeeper, etcd, and Consul. Its defining design *philosophy* is the interview-worthy part: rather than give every application a consensus *library* (Paxos) to embed, Google built a single, highly-reliable, easy-to-use **coordination service** with a familiar interface — a small filesystem of files/directories you can lock, read, and write, with sessions and event notifications. The bet, which proved right, is that most engineers should *never* implement Paxos; they should call a service that does it correctly once.

Chubby runs a cell of (typically 5) replicas using Paxos to maintain a consistent replicated database, with one elected **master** that holds a master lease and serves all reads and writes (so reads are linearizable, unlike ZooKeeper's default). Clients hold **sessions** with **KeepAlives** and leases; ephemeral state and locks vanish when a session lapses. Crucially, Chubby provides **fencing via sequence numbers** ("sequencers") that clients pass to protected services, which validate them — the original productionized fencing-token pattern.

Several hard-won lessons came out of the Chubby paper that shaped everything after it:
- **People (mis)used it as a name service / config store**, hammering it with reads and watches far beyond locking — which is why Google added client-side caching and aggressive event notification, and why ZooKeeper/etcd are explicitly designed for that read-heavy "coordination kernel" usage.
- **Coarse-grained locking** is the right model: locks held for hours/days (leader election, "who owns this shard") rather than fine-grained per-operation locks. Coarse locks generate little load on the lock service and tolerate brief lock-service unavailability; fine-grained locking would make the lock service a hot-path bottleneck.
- **A lock service is better than a Paxos library** for most teams because it externalizes the hard part, provides an intuitive API, and offers event notification and caching that a bare consensus library doesn't.
- **Developers don't reason well about failures** — the availability of the lock service became a hidden dependency for systems that assumed it was always up, teaching the importance of graceful degradation when coordination is briefly unavailable.

The enduring architectural principle: **make consensus a service with a tiny, sharp API and let it be the single source of truth for coordination, keeping it off the high-throughput data path.** Every modern coordination system is a variation on Chubby's thesis.

### Q26. [Theory] Compare consensus-based coordination, leader leases for reads, and "leaderless" quorum systems for serving linearizable reads cheaply. What are the trade-offs?

Serving a *write* with linearizability essentially requires a consensus round (quorum agreement), but *reads* are where mature systems get clever, because naively every linearizable read would also need a full quorum round trip — expensive. Three families of techniques trade differently.

**Read from the leader after confirming leadership (read index / quorum read).** The leader can serve a linearizable read without writing to the log if it first confirms it's *still* the leader at the moment of the read — typically by exchanging heartbeats with a quorum (the "ReadIndex" optimization in etcd/Raft) to prove no newer leader has emerged, then waiting until its state machine has applied up to that index. This avoids a disk write but still pays a network round trip to a quorum. Cheaper than a full log append, still linearizable.

**Leader leases for local reads.** Stronger optimization: the leader holds a *time-bounded lease* (renewed via heartbeats) during which it's guaranteed no other leader can exist. Within the lease — minus a clock-skew margin — the leader can serve reads **purely locally**, no network round trip at all, and still be linearizable. This is how CockroachDB serves fast follower-free reads and how Chubby's master serves reads. The cost is a hard dependency on **bounded clock skew**: if real skew exceeds the assumed bound, the lease guarantee breaks and you can serve a stale read believing it's fresh. That's why these systems pin down `max_offset` and crash/restart on suspected skew violations.

**Leaderless quorum reads (Dynamo-style `R + W > N`).** No leader at all; a read contacts `R` replicas and a write `W`, with `R + W > N` ensuring overlap. This buys availability and avoids a leader bottleneck, but the "linearizability" it offers is weaker and subtler — bare quorum overlap guarantees you *see* a recent write but not a total order; concurrent writes still need conflict resolution, and true linearizability requires extra mechanisms (read-repair plus careful write coordination, e.g. Cassandra's `LWT`/Paxos for the few ops that need it). You pay in read latency (wait for R replicas, often the slow tail) and in conflict-resolution complexity.

```
                    network RTTs per linearizable read   skew dependency   bottleneck
Leader log-write read       1+ (quorum) + disk            no                leader
ReadIndex (quorum read)     1 (quorum heartbeat)          no                leader
Leader lease (local read)   0 (within lease)              YES (bounded)     leader
Leaderless R+W>N            1 (R replicas, tail-bound)    no                none
```

The expert judgment: **leader leases give the cheapest linearizable reads but mortgage correctness on clock-skew bounds; ReadIndex is the safe middle (no skew assumption, one round trip); leaderless quorums trade the leader bottleneck for weaker guarantees and conflict-resolution complexity.** Which you pick depends on whether you can trust your clocks (TrueTime/tight NTP → leases are great), whether the leader is a throughput problem (then leaderless or sharded leaders), and how strict your read freshness truly must be.

### Q27. [Practical] You're architecting coordination for a system with thousands of nodes. How do you avoid the single-consensus-group bottleneck while keeping the guarantees you need?

The anti-pattern is one giant Raft/Paxos/Zab group spanning thousands of nodes. Consensus throughput is gated by the leader and by the majority round trip, and large groups suffer terrible commit latency, frequent re-election storms, and a single leader that becomes a hot spot. Consensus *does not scale by adding members* — adding members makes it *slower and less available per write*, not faster. So the architecture must keep each consensus group small and multiply the groups.

The layered design that production systems converge on:

```
┌──────────────────────────────────────────────────────────────┐
│ Data plane: keyspace SHARDED into many ranges,                  │
│ each range = its own small (3–5 node) Raft group with own       │
│ leader. Thousands of independent groups → horizontal consensus. │
│   [range A: raft] [range B: raft] [range C: raft] ...           │
└───────────────────────▲─────────────────────────────────────────┘
                         │ "which range lives where" (topology)
┌──────────────────────────────────────────────────────────────┐
│ Control/metadata plane: a SMALL consensus group (or hierarchy)  │
│ tracking shard→node mapping, leadership, rebalancing decisions. │
└───────────────────────▲─────────────────────────────────────────┘
                         │
┌──────────────────────────────────────────────────────────────┐
│ Membership/failure detection: GOSSIP (SWIM) across all nodes —  │
│ eventually consistent, no coordinator, scales to thousands.      │
└──────────────────────────────────────────────────────────────┘
```

The three layers each match coordination strength to need: (1) **Membership and failure detection via gossip/SWIM** — this is soft state that tolerates seconds of staleness, so it must *not* go through consensus; gossip scales to thousands with bounded per-node message load and no coordinator (Consul's Serf, Cassandra's gossiper). (2) **Sharded consensus for the data** — partition the keyspace and give each shard its own 3–5 node Raft group with its own leader; this is exactly how CockroachDB ("ranges"), TiKV ("regions"), Spanner ("Paxos groups per tablet"), and YugabyteDB ("tablets") scale linearizable writes horizontally — total throughput grows with the number of groups because their leaders are spread across machines. (3) **A small metadata group** that records which shard lives where, itself a consensus group but tiny and rarely written.

The hard parts to call out: **cross-shard transactions** now need 2PC layered *over* the per-shard Raft groups (each shard is a participant whose "durability" is its own consensus) — added latency and deadlock care. **Rebalancing** (splitting/merging/moving ranges as load shifts) must be online, throttled, and itself coordinated through the metadata group without stalling traffic. **Avoiding a metadata hot spot**: clients cache the topology with an epoch/version and invalidate on a miss, so they don't hammer the metadata group on every request. And **leadership balance**: if leaders cluster on a few nodes you recreate the bottleneck, so systems actively rebalance leases across the fleet. The governing principle: **use strong consensus narrowly (many small sharded groups) and weak/eventual coordination broadly (gossip for membership), never one monolithic group** — and accept that cross-group atomicity costs you a 2PC.

### Q28. [Behavioral] Tell me about a time you had to decide between building coordination logic yourself versus adopting a coordination service, or had to fix a homegrown one that went wrong. How did you drive it?

A strong answer shows judgment about *when* to depend on consensus, the cost of getting it wrong, and how you led the change — framed with something like STAR.

*Situation/Task:* A team had built a homegrown leader-election scheme for a critical batch processor using a "leader" row in the primary database: whoever held it ran the jobs, with a `last_heartbeat` timestamp and a rule that another node could steal leadership if the heartbeat was older than 30 seconds. It worked in testing but caused two production incidents where **two nodes ran as leader simultaneously**, double-processing financial batches.

*Action:* Rather than just say "use ZooKeeper," I diagnosed *why* it failed, because the team was attached to the simplicity. The root cause was textbook: the heartbeat-staleness check had no defense against a **GC pause** — node A would pause for 40s, node B would steal leadership (heartbeat looked stale), and node A would wake up still believing it was leader and write. It was the zombie-leader problem with no fencing. I reproduced it deterministically by injecting a pause, which turned an abstract risk into a concrete, undeniable demo. Then I laid out the options with honest trade-offs: harden the homegrown approach (add fencing tokens enforced at the job's output store) versus adopt etcd for election. I recommended **etcd for the election plus fencing tokens at the resource**, because the homegrown lease logic kept reinventing consensus subtly wrong, and fencing was needed *either way*. I de-risked the migration with a parallel run (etcd electing alongside the old scheme, comparing decisions) behind a flag before cutover.

*Result:* Double-processing went to zero; the failover path got faster (lease-based vs. a 30s staleness poll); and the team stopped maintaining tricky consensus-adjacent code. The lasting principle I got the team to adopt: **don't hand-roll consensus — leader election, locks, and "exactly one of us" are consensus problems, and the failure modes (GC pauses, partitions, clock skew) are exactly the ones humans forget.** Use a battle-tested coordination service, and *always* fence at the resource because no election scheme can prevent a paused node from believing it's still in charge.

The meta-point interviewers look for: I led with a reproduction (data over assertion), respected the team's preference for simplicity by explaining the *specific* flaw rather than dismissing their work, presented reversible/low-risk migration steps, and extracted a durable engineering principle the team internalized — not just a one-off fix.

### Q29. [Theory] What is the relationship between consensus, atomic broadcast, and a replicated state machine — and why are they considered "equivalent"?

This is a foundational equivalence that distinguishes someone who *uses* consensus from someone who *understands its place in the theory*. The three concepts are inter-reducible — solving any one lets you solve the others — which is why a single protocol implementation (Raft, Zab, Multi-Paxos) serves all three roles.

**Consensus** is agreeing on one value. **Atomic broadcast** (total-order broadcast) is delivering a stream of messages to all nodes such that every node delivers the *same messages in the same order*. **Replicated state machine (RSM)** is the architectural pattern: take a deterministic state machine, feed every replica the *same sequence of commands in the same order*, and every replica ends in the *same state* — giving you a fault-tolerant service from unreliable parts.

The reductions: atomic broadcast is just *repeated consensus* — agreeing on "what is the message at slot 1? slot 2? slot 3?" is a sequence of consensus instances, which is exactly Multi-Paxos / the Raft log. Conversely, you can build consensus from atomic broadcast (broadcast your proposal; the first delivered value is the decision). And an RSM is *built on* atomic broadcast: the totally-ordered command stream is the broadcast, and applying it deterministically yields replicated state. So:

```
Consensus  ◄──repeat──►  Atomic Broadcast (ordered log)  ──feeds──►  Replicated State Machine
 (agree on    one value      agree on the ORDER of a            apply commands in order
  one value)                  stream of values                  → identical replicas
```

Why it matters for an architect: it tells you that **anything you can phrase as "a deterministic state machine driven by an agreed-upon command log" can be made fault-tolerant via consensus** — and that's the design pattern behind etcd (the state machine is the key-value store), ZooKeeper (the znode tree), Kafka with KRaft (the metadata log), and replicated databases. It also clarifies the *cost model*: every state change is a consensus decision, so you keep the state machine's command rate modest and push bulk data off the log. And it explains why determinism is sacred in these systems — a non-deterministic command (using wall-clock time, random numbers, or unordered map iteration) makes replicas diverge even with a perfectly agreed-upon log, silently breaking the RSM guarantee. The deepest practical bug class in consensus-backed systems is *non-determinism in the state machine*, not the consensus protocol itself.

### Q30. [Practical] A multi-region service must coordinate, but cross-region consensus adds ~150ms per decision. How do you design coordination that's both globally correct and fast?

The tension is fundamental: linearizable coordination across regions means a quorum round trip across the planet (~150ms+ for cross-continent RTT), which is intolerable on a hot path, yet some invariants are genuinely global. The architecture is about **shrinking and localizing the globally-coordinated surface** so the slow path is rare and small.

```
Most operations: served LOCALLY in-region (no cross-region hop)
Rare operations needing global order: pay the cross-region consensus cost
                                       (small, bounded set)
```

The toolkit, roughly in order of preference:

1. **Pin authority per key to a home region (geo-partitioning).** Make each piece of coordinated state *owned* by one region, so writes to it are a *single-region* consensus (fast, ~few ms) and only cross-region for the rare access from elsewhere. An account's ledger, a tenant's data, a shard's leader all live "at home." This is how Spanner's placement, CockroachDB's `REGIONAL BY ROW`, and most global systems avoid global consensus on the common case — *the trick is that "global" data is mostly accessed locally if you partition by locality of access.*

2. **Use leader leases for local reads** (Q26) so reads in the home region are local and linearizable without a round trip, mortgaging it on bounded clock skew (which TrueTime/tight-NTP regions can provide).

3. **Make the rare global operations explicitly slow and rare.** Truly global invariants (a globally-unique username, a cross-region transfer) go through a deliberate cross-region consensus or 2PC and you *accept* the latency for that small set — and you design the product so those are not on the interactive hot path (asynchronous, queued, "pending" states).

4. **Push as much as possible to eventual consistency / CRDTs.** Anything that doesn't need a global order — counters, presence, caches, feeds — uses gossip/CRDT replication with no cross-region coordination at all, converging in the background.

5. **Idempotency + sagas for cross-region workflows** instead of holding a global lock/transaction across the slow link: each region does its local, fenced, idempotent step, and compensations handle failure — converting an impossible-to-hold global transaction into a resilient, eventually-consistent workflow.

The expert framing mirrors the consistency-kernel idea: **identify the tiny set of truly-global invariants and pay full cross-region consensus only for them; geo-partition so the "global" state is locally-owned and locally-coordinated for the common case; and make everything else eventually consistent with idempotent, fenced, compensating workflows.** A regional partition then degrades a region (its home keys pause or go read-only) rather than taking down the planet, and 99.9% of operations never cross an ocean. The failure to avoid is treating the whole system as needing one global coordination tier — that imposes 150ms on everything and a global availability ceiling for an invariant set that's usually a fraction of a percent of operations.

### Q31. [Theory] Why is wall-clock time so dangerous in coordination, and how do leases, fencing, and bounded-skew assumptions each handle it?

Wall-clock (physical) time is treacherous in coordination because it is *not* a reliable, agreed-upon, monotonic quantity across machines: NTP can step the clock **backwards**, clocks **drift** at different rates, virtual machines **pause** and resume with a jump, and there is no bound on skew between two nodes unless you *engineer* one. Any coordination decision that assumes "if X seconds have passed on my clock, then Y is true elsewhere" can be violated by a clock that jumped, drifted, or by a process that was frozen. This is the hidden root of a huge fraction of split-brain and double-execution bugs.

Each coordination mechanism handles time differently, and understanding the gradient is the expert distinction:
- **Leases** depend on time but *defensively*: the holder stops acting a safety **margin before** nominal expiry, and the successor waits the margin **after**, so even with bounded skew their authoritative windows don't overlap. The lease's correctness rests on an *assumed bound* on clock drift over the lease duration. If that bound is violated (a 40s GC pause against a 30s lease), the lease guarantee alone fails — which is exactly why leases are necessary but not sufficient.
- **Fencing tokens** *eliminate* the time dependency for the final safety check. Instead of trusting any clock, the resource trusts a **monotonic counter**: a higher token always means "more recent authority," regardless of what any clock says. This is why fencing is the *correctness backstop* under which leases are merely a *liveness/efficiency* optimization. A monotonic logical counter is the one thing immune to clock pathology.
- **Bounded-skew assumptions** (TrueTime, HLC `max_offset`) make the time dependency *explicit and managed*. TrueTime *measures* uncertainty (`[earliest, latest]` from atomic clocks/GPS) and waits it out (commit-wait), so it's safe by construction within the measured bound. HLC keeps timestamps close to wall-clock while preserving causality, and databases like CockroachDB add read-uncertainty restarts and crash on suspected skew beyond `max_offset`. The honest difference: Spanner *measures* the uncertainty; HLC systems *assume* a bound and add safeguards for violations.

```
Trust gradient for coordination correctness:
  pure wall-clock  ──unsafe──►  bounded-skew (assumed)  ──►  bounded-skew (measured, TrueTime)
  monotonic logical counter (fencing/Raft index)  ──►  SAFE regardless of clocks
```

The synthesis an architect must hold: **never gate a correctness decision on raw wall-clock time across machines.** Use leases for liveness (fast failover) but always back them with a monotonic fencing token for correctness, because the token is the only thing immune to pauses and skew. Reserve wall-clock-based coordination for systems that have *engineered* a measured or tightly-bounded uncertainty (Spanner's TrueTime), and even then understand you're trading hardware/latency for that safety. The recurring bugs — zombie leaders, double-executed jobs, lost-then-resurrected locks — are almost always a clock or pause assumption that quietly didn't hold.

### Q32. [Practical] How do you operate, monitor, and disaster-recover a consensus-backed coordination cluster (etcd/ZooKeeper) so it doesn't become your biggest outage risk?

A coordination cluster is a paradox: it exists to make your system reliable, but because so much depends on it, *it* becomes a critical single dependency whose outage can cascade everywhere. Operating it well is a distinct discipline from operating stateless services.

**Placement and sizing.** Spread members across failure domains so no single rack/AZ holds a majority — the most common self-inflicted outage is putting 3 of 5 etcd members in one AZ, so that AZ's loss kills quorum. Use 5 members for important clusters (tolerates 2 failures); avoid even counts. For two-DC topologies add a witness in a third location. Give each member a **dedicated fast disk** (NVMe/SSD): consensus `fdatasync`s every write, and these systems are pathologically sensitive to fsync latency — a slow disk causes endless re-elections.

**Monitoring — the signals that actually predict outages:**
- `has_leader` (must be 1) and `leader_changes_total` (flapping = trouble).
- Disk fsync / WAL latency (`etcd_disk_wal_fsync_duration_seconds`, backend commit duration) — the leading indicator of consensus health.
- **Database size vs. quota** and revision/compaction lag — the NOSPACE alarm (Q20) is a classic write-blocking outage.
- Quorum health: how many members are reachable, and *which failure domains* they're in.
- Latency of proposals and client request rate (catch a misbehaving client hammering watches — the Chubby lesson from Q25).

**Disaster recovery — practiced, not theoretical:**
- Take **regular snapshots** (`etcdctl snapshot save`) and, critically, **rehearse the restore** — an untested backup is a guess. Know your RPO (data since last snapshot) and RTO.
- Know the **lost-quorum runbook**: if a majority is permanently lost, you restore a snapshot to a new single-member cluster and re-grow it (one learner at a time), accepting loss of writes after the snapshot. This is irreversible and stressful — having run it in a game day beforehand is the difference between a 20-minute and a 4-hour outage.
- Automate **compaction and defrag** so the quota alarm never fires unexpectedly.
- Do **membership changes one at a time, learner-first** (Q18) — reconfiguration is a top outage cause.

**Blast-radius reduction.** Treat the coordination cluster as a dependency that *will* occasionally be briefly unavailable and design clients to degrade gracefully: cache the last-known coordination state with stale-while-revalidate, don't let a brief leader election crash every dependent service, and use coarse-grained locks (Chubby's lesson) so the lock service isn't on every request's hot path. Isolate coordination clusters per-domain so one team's misuse doesn't take down everyone's.

The staff-level synthesis: **a consensus cluster's failure modes are quorum loss, disk latency, space/quota, and reconfiguration mistakes** — monitor `has_leader` and fsync latency as your top signals, place members across failure domains, automate compaction, rehearse snapshot restore, and make every client tolerant of brief coordination unavailability. The cluster that "just works" for years and then has a catastrophic, un-recovered outage is almost always one where backups were never tested and members were all in one AZ. Reliability of the coordinator is *operational discipline*, not just the correctness of Raft.

---

## ✅ Key Takeaways

- **Almost every hard distributed problem is consensus in disguise** — leader election, locks, membership, config, commit. Solve it once with a proven service (etcd/ZooKeeper/Consul) instead of hand-rolling it.
- **Majority quorums prevent split-brain** because any two majorities overlap; use odd node counts (3, 5) and spread members across failure domains. The minority side must stop accepting writes (the CP choice).
- **Single-leader designs win on simplicity** (a natural total order, no write conflicts) at the cost of a bottleneck and the need for *safe* failover — which is the entire discipline of leader election.
- **Raft = strong leader + terms + up-to-date vote rule + log matching.** The current-term commit rule prevents the "committed entry overwritten" bug. Zab and Multi-Paxos are points in the same design space (leader + epoch/term + majority-committed ordered log).
- **Leases give liveness; fencing tokens give correctness.** No lock service can stop a GC-paused node from *believing* it's still the leader, so always enforce a monotonic fencing token at the resource for correctness-critical locks.
- **Reads need not pay a full quorum:** ReadIndex (one round trip, no skew assumption) and leader leases (local reads, mortgaged on bounded clock skew) make linearizable reads cheap. Leaderless `R+W>N` trades the leader bottleneck for weaker guarantees.
- **Never gate correctness on raw wall-clock time across machines** — clocks step, drift, and VMs pause. Use monotonic logical counters (fencing, Raft index) for the safety check; reserve wall-clock coordination for systems with measured/bounded uncertainty (TrueTime/HLC).
- **Scale consensus by sharding into many small groups** (CockroachDB ranges, TiKV regions, Spanner Paxos groups) plus gossip for membership — never one monolithic group for thousands of nodes; cross-group atomicity then costs a 2PC.
- **Consensus ≡ atomic broadcast ≡ replicated state machine.** Anything expressible as a deterministic state machine driven by an agreed command log can be made fault-tolerant — but determinism is sacred (wall-clock/random in the state machine silently diverges replicas).

## ⚠️ Common Pitfalls

- **Hand-rolling leader election** (a "leader row" with heartbeat-staleness) with no defense against GC pauses → two simultaneous leaders. Use a coordination service *and* fencing.
- **Even-numbered consensus clusters** that split 50/50 with no majority, or placing the majority of members in one failure domain (one AZ loss = total quorum loss).
- **Treating "replicated to a majority" as "committed"** without the current-term rule, reintroducing the committed-entry-overwritten data-loss bug.
- **Using Redlock (or any TTL-only lock) for correctness** without fencing tokens — the GC-pause/zombie-holder problem makes it unsafe for anything where double-execution corrupts.
- **Assuming ZooKeeper reads are fresh** — reads are served from possibly-stale local replicas; you must `sync()` for freshness, and even then rely on ordering/ephemeral guarantees, not casual reads.
- **Changing membership by editing configs and restarting** instead of using the protocol's reconfiguration (one-at-a-time, learner-first), risking disjoint-majority split-brain.
- **Ignoring keepalive/session-loss errors** — losing your lease/session means you are *no longer leader* even though your process runs; you must immediately stop all leader-only work.
- **One giant consensus group for thousands of nodes** — consensus gets slower and less available as you add members; shard into many small groups and gossip membership.
- **Putting bulk/high-throughput data through the consensus log** instead of keeping the command rate modest; and **non-determinism in the replicated state machine** (wall-clock, random, unordered iteration) silently diverging replicas.
- **Operating the coordination cluster casually** — untested snapshot restores, slow disks causing re-election storms, the NOSPACE quota alarm blocking writes, and no graceful client degradation when coordination is briefly unavailable.

## 📚 Further Reading

- **Diego Ongaro & John Ousterhout — *In Search of an Understandable Consensus Algorithm (Raft)*** (USENIX ATC 2014): the original Raft paper; pair with the interactive visualization at [raft.github.io](https://raft.github.io) and Ongaro's PhD thesis for membership-change and log-compaction detail.
- **Leslie Lamport — *Paxos Made Simple*** (2001) and *The Part-Time Parliament* (1998): the source material for Paxos; *Paxos Made Live* (Chandra, Griesemer, Redstone, Google, 2007) is the essential "what it actually takes to build it" companion.
- **Mike Burrows — *The Chubby Lock Service for Loosely-Coupled Distributed Systems*** (OSDI 2006): the design philosophy, coarse-grained locking, sequencers (fencing), and operational lessons that shaped ZooKeeper/etcd.
- **Hunt, Konar, Junqueira, Reed — *ZooKeeper: Wait-free Coordination for Internet-scale Systems*** (USENIX ATC 2010) and **Junqueira, Reed, Serafini — *Zab*** (DSN 2011): ZooKeeper's API/guarantees and the Zab protocol.
- **Martin Kleppmann — *How to do distributed locking*** (2016 blog) and antirez's Redlock rebuttal: the canonical fencing-token debate — read both sides; also *Designing Data-Intensive Applications* (O'Reilly) Ch. 8–9 for consensus, linearizability, and total-order broadcast.
- **etcd documentation & the Raft thesis**, the **Consul/Serf SWIM** materials, and the **Jepsen analyses** ([jepsen.io](https://jepsen.io)) of etcd, ZooKeeper, and Consul — invaluable real-world "what actually breaks under partition" evidence.
