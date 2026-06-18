# Project Briefs — Answer Key for All 110 Projects
**Use this AFTER your own attempt, not before.** Each brief is the worked spec you'd produce by running `PROJECT-PLAYBOOK.md` (recon → spec → milestones → acceptance → break-it → compare → gate). Build the project from the 3-line description in `LEARNING-PROJECTS.md` first; *then* open the matching brief here to check what you missed. Reading it first robs you of the deduction practice that is the whole point.

**Legend:** 🟢 hours · 🟡 1–2 days · 🟠 3–5 days · 🔴 1–2 weeks. All Java.

**Brief shape:** Clone of · Recon · Prereqs · Spec (nouns/verbs) · Milestones · Acceptance (+ Aha test) · Break it · Compare to real · Gate (trade-off / 10× / what broke).

---

## Track 1 — Computer Systems Foundations

### P1 · HashMap 🟢
- **Clone of:** `java.util.HashMap`.
- **Recon:** JavaDoc of `HashMap`/`Map`; any "how HashMap works internally" explainer.
- **Prereqs:** none — start here.
- **Spec:** nouns: bucket array, entry (key,value,hash), load factor. verbs: `put`, `get`, `remove`, `resize`.
- **Milestones:** 1) separate-chaining map (array of linked lists), `put/get/remove`. 2) resize+rehash when size/capacity > load factor (0.75). 3) open-addressing variant (linear probing) for comparison.
- **Acceptance:** 1M random put/get matches a reference `HashMap`; resize preserves all entries. **Aha test:** feed keys with a constant `hashCode()` → observe get() degrade to O(n) (all in one bucket).
- **Break it:** all-colliding keys; null keys; resize during heavy load.
- **Compare to real:** JDK treeifies a bucket (linked list → red-black tree) at 8 collisions; you'll see why.
- **Gate:** *trade-off* load factor = space vs collision rate; *10×* bad hash → O(n) buckets; *what broke* lookups slowed under collisions until resize/better hash.

### P2 · Dynamic array (ArrayList) 🟢
- **Clone of:** `java.util.ArrayList`.
- **Recon:** `ArrayList` JavaDoc (grow policy); amortized-analysis explainer.
- **Prereqs:** none.
- **Spec:** nouns: backing array, size, capacity. verbs: `add`, `get`, `set`, `remove`, `grow`.
- **Milestones:** 1) array-backed list with `add/get`. 2) grow by 2× on full; count copies. 3) compare growth by +1 vs ×1.5 vs ×2.
- **Acceptance:** appending N items triggers O(log N) resizes; total copies ≈ 2N. **Aha test:** instrument copy count for +1 growth → ~N²/2 copies vs ~2N for doubling.
- **Break it:** add 10M elements; measure resize cost spikes.
- **Compare to real:** `ArrayList` grows ~1.5×; `ArrayDeque` doubles.
- **Gate:** *trade-off* growth factor = wasted space vs copy frequency; *10×* +1 growth = O(n²); *what broke* append got slow with linear growth.

### P3 · Thread pool (ExecutorService) 🟡
- **Clone of:** `java.util.concurrent.ThreadPoolExecutor`.
- **Recon:** `ExecutorService`/`ThreadPoolExecutor` JavaDoc (constructor knobs, rejection policies).
- **Prereqs:** P9 (producer-consumer) helps; `BlockingQueue`.
- **Spec:** verbs: `execute(Runnable)`, `submit(Callable)→Future`, `shutdown`, `shutdownNow`, `awaitTermination`. knobs: core/max pool size, keepAlive, work queue, rejection handler.
- **Milestones:** 1) N worker threads draining a `BlockingQueue`. 2) `Future` via `FutureTask`. 3) shutdown (drain) + shutdownNow (interrupt). 4) bounded queue + `RejectedExecutionHandler`; core-vs-max + keepAlive reaping.
- **Acceptance:** 10k tasks all run once; post-shutdown submit rejected; bounded-queue-full fires policy. **Aha test:** unbounded queue + slow tasks → heap climbs to OOM; bounded queue + CallerRuns → producer slows.
- **Break it:** concurrent submitters; kill mid-run; flood unbounded queue.
- **Compare to real:** `ThreadPoolExecutor` packs state+count in one `AtomicInteger ctl`.
- **Gate:** *trade-off* unbounded queue = throughput vs hidden memory risk; *10×* OOM; *what broke* JVM wouldn't exit (no shutdown), OOM under load.

### P4 · Memory allocator / object pool 🟡
- **Clone of:** a slab allocator / `apache-commons-pool`.
- **Recon:** "free list allocator" / slab allocation explainers.
- **Prereqs:** P2.
- **Spec:** nouns: arena (`byte[]`/`ByteBuffer`), free list, block. verbs: `alloc(size)`, `free(ptr)`.
- **Milestones:** 1) bump allocator (alloc only). 2) free list (reuse freed blocks). 3) size classes / slabs; measure fragmentation.
- **Acceptance:** alloc/free cycles reuse memory; fragmentation measured. **Aha test:** random alloc/free of mixed sizes → external fragmentation leaves "free" memory unusable.
- **Break it:** adversarial size pattern; double-free.
- **Compare to real:** jemalloc/tcmalloc size classes; JVM TLABs.
- **Gate:** *trade-off* pooling cuts GC pressure but risks leaks/fragmentation; *10×* fragmentation wastes "freed" memory; *what broke* couldn't satisfy a large alloc despite free space.

### P5 · Stack-machine bytecode interpreter 🟡
- **Clone of:** the JVM interpreter loop.
- **Recon:** "stack machine" / simple-VM tutorials; JVM instruction-set overview.
- **Prereqs:** none.
- **Spec:** nouns: instruction, operand stack, locals, program counter. verbs: decode-dispatch loop.
- **Milestones:** 1) instruction set (PUSH/POP/ADD/SUB/…). 2) control flow (JMP/JZ). 3) CALL/RET with frames.
- **Acceptance:** runs a factorial/fibonacci "program"; correct results. **Aha test:** trace the operand stack per instruction — see expression evaluation as stack pushes/pops.
- **Break it:** stack underflow; bad jump target.
- **Compare to real:** JVM's `bipush`/`iadd`/`invoke*`; register VMs (Lua) vs stack VMs.
- **Gate:** *trade-off* stack VM simple to compile to vs register VM faster; *10×* interpretation overhead (→ JIT); *what broke* stack imbalance on bad control flow.

### P6 · GC simulator (ref-counting + mark-sweep) 🟠
- **Clone of:** a tracing garbage collector.
- **Recon:** "garbage collection algorithms" (ref counting, mark-sweep, generational).
- **Prereqs:** P1.
- **Spec:** nouns: object graph, roots, reachability. verbs: `allocate`, `collect`.
- **Milestones:** 1) ref-counting collector. 2) mark-sweep from roots. 3) show ref-counting leaks a cycle that mark-sweep reclaims.
- **Acceptance:** unreachable objects freed; cycle test distinguishes the two. **Aha test:** build A↔B cycle with no roots → ref-counting never frees it; mark-sweep does.
- **Break it:** deep graphs (recursion depth); cyclic references.
- **Compare to real:** generational hypothesis; G1/ZGC regions.
- **Gate:** *trade-off* ref-counting incremental but leaks cycles; mark-sweep pauses; *10×* STW pause scales with live set; *what broke* leaked memory on cycles.

### P7 · Regex engine (Thompson NFA) 🟠
- **Clone of:** `java.util.regex` (the NFA approach, not backtracking).
- **Recon:** Russ Cox "Regular Expression Matching Can Be Simple And Fast".
- **Prereqs:** P5 (parsing mindset).
- **Spec:** nouns: token, AST, NFA states/transitions. verbs: parse → compile to NFA → simulate.
- **Milestones:** 1) parse `*,+,?,|,()` to AST. 2) Thompson construction to NFA. 3) simulate with active-state set (no backtracking).
- **Acceptance:** matches/anchors correctly; linear time on adversarial inputs. **Aha test:** run `(a*)*b` against `aaaa…` on a backtracking engine (catastrophic) vs your NFA (linear) — see ReDoS.
- **Break it:** nested quantifiers; empty alternation.
- **Compare to real:** Java's regex backtracks (can ReDoS); RE2 uses NFA.
- **Gate:** *trade-off* NFA linear but no backreferences; *10×* backtracking → exponential; *what broke* nothing on your NFA — that's the point.

### P8 · JSON parser (recursive descent) 🟢
- **Clone of:** Jackson/Gson core parse.
- **Recon:** json.org grammar; "recursive descent parser" explainer.
- **Prereqs:** none.
- **Spec:** nouns: token (string/number/punct), node tree. verbs: `tokenize`, `parseValue/Object/Array`.
- **Milestones:** 1) lexer (handles strings/escapes/numbers). 2) recursive-descent parser → tree. 3) error messages with position.
- **Acceptance:** round-trips a sample document; rejects malformed JSON with a useful error. **Aha test:** parse nested structure and watch the recursion mirror the grammar.
- **Break it:** deep nesting (stack overflow); unterminated string; trailing comma.
- **Compare to real:** streaming (SAX/`JsonParser`) vs tree (DOM/`ObjectMapper`).
- **Gate:** *trade-off* tree parse simple vs streaming memory-light; *10×* deep nesting → stack overflow; *what broke* escape/number edge cases.

---

## Track 2 — Concurrency & Parallelism

### P9 · Producer-consumer, three ways 🟡
- **Clone of:** `BlockingQueue` usage patterns.
- **Recon:** `wait/notify` docs; `ArrayBlockingQueue` source; ring-buffer (Disruptor) overview.
- **Prereqs:** none.
- **Spec:** nouns: bounded buffer, producer, consumer. verbs: `put`, `take`.
- **Milestones:** 1) `synchronized` + `wait/notify`. 2) `BlockingQueue`. 3) lock-free ring buffer (single-producer/consumer). Benchmark all three.
- **Acceptance:** no lost/duplicated items; blocks correctly when full/empty. **Aha test:** drop a `notify` (vs `notifyAll`) → see a stuck consumer / missed signal.
- **Break it:** spurious wakeups; many producers/consumers.
- **Compare to real:** `ArrayBlockingQueue` (one lock) vs `LinkedBlockingQueue` (two locks) vs Disruptor.
- **Gate:** *trade-off* abstraction vs control/throughput; *10×* lock contention; *what broke* missed wakeup with `notify`.

### P10 · ReentrantLock with CAS 🟡
- **Clone of:** `ReentrantLock`/AQS.
- **Recon:** AbstractQueuedSynchronizer overview; CAS/`AtomicInteger`.
- **Prereqs:** P9.
- **Spec:** nouns: state (0=free), owner thread, wait queue. verbs: `lock`, `unlock`, `tryLock`.
- **Milestones:** 1) spinlock via CAS on state. 2) reentrancy (owner + hold count). 3) park/unpark wait queue instead of spinning.
- **Acceptance:** mutual exclusion under contention; reentrant acquire works. **Aha test:** compare CPU usage spinning vs parking under contention.
- **Break it:** unlock by non-owner; high contention.
- **Compare to real:** AQS uses a CLH queue + `LockSupport.park`.
- **Gate:** *trade-off* spin (low latency, burns CPU) vs park (cheap, slower wake); *10×* spin meltdown; *what broke* CPU pegged while spinning.

### P11 · Read-write lock 🟡
- **Clone of:** `ReentrantReadWriteLock`.
- **Recon:** its JavaDoc (fairness, downgrade).
- **Prereqs:** P10.
- **Spec:** nouns: reader count, writer flag. verbs: `readLock/Unlock`, `writeLock/Unlock`.
- **Milestones:** 1) many readers XOR one writer. 2) writer-preference to avoid writer starvation. 3) lock downgrade (write→read).
- **Acceptance:** concurrent readers proceed; writer is exclusive. **Aha test:** continuous readers → starve the writer; add write-preference → writer gets in.
- **Break it:** reader/writer storm; upgrade deadlock (read→write).
- **Compare to real:** `StampedLock` optimistic reads.
- **Gate:** *trade-off* read concurrency vs writer fairness; *10×* writer starvation; *what broke* writer never ran under reader load.

### P12 · Lock-free stack & queue 🟠
- **Clone of:** `ConcurrentLinkedQueue` (Michael-Scott).
- **Recon:** Treiber stack; Michael-Scott queue paper; ABA problem.
- **Prereqs:** P10.
- **Spec:** nouns: head/tail `AtomicReference`, node. verbs: `push/pop`, `enqueue/dequeue`.
- **Milestones:** 1) Treiber stack (CAS head). 2) M-S queue (CAS head+tail). 3) reproduce ABA, fix with `AtomicStampedReference`.
- **Acceptance:** correct under many threads; no lost nodes. **Aha test:** construct an ABA interleaving → corrupted structure; stamping fixes it.
- **Break it:** high contention; node reuse.
- **Compare to real:** `ConcurrentLinkedQueue` is M-S; GC saves you from much ABA in Java.
- **Gate:** *trade-off* lock-free progress vs complexity; *10×* CAS-retry livelock; *what broke* ABA corruption.

### P13 · Striped / sharded lock cache 🟢
- **Clone of:** `ConcurrentHashMap` lock striping.
- **Recon:** pre-Java8 CHM segments; `Striped` (Guava).
- **Prereqs:** P1, P10.
- **Spec:** nouns: N stripes (locks), buckets. verbs: `get/put` under `lock[hash % N]`.
- **Milestones:** 1) one global lock map. 2) N stripes. 3) benchmark throughput vs stripe count.
- **Acceptance:** thread-safe; throughput scales with stripes up to core count. **Aha test:** plot throughput as stripes 1→64 → contention drops then plateaus.
- **Break it:** all keys hash to one stripe.
- **Compare to real:** Java 8 CHM uses per-bin CAS + tree bins.
- **Gate:** *trade-off* more stripes = less contention, more memory; *10×* single-lock bottleneck; *what broke* throughput flat with one lock.

### P14 · Actor model 🟠
- **Clone of:** Akka actors.
- **Recon:** actor-model overview; Akka mailbox/dispatcher.
- **Prereqs:** P3, P9.
- **Spec:** nouns: actor, mailbox (queue), message, dispatcher. verbs: `send`, `receive`.
- **Milestones:** 1) actor = state + mailbox, single-threaded receive. 2) dispatcher (thread pool) runs ready actors. 3) supervision (restart on failure).
- **Acceptance:** no shared mutable state; messages processed one-at-a-time per actor. **Aha test:** thousands of actors, zero locks, no data races — concurrency without locks.
- **Break it:** mailbox overflow; blocking inside receive.
- **Compare to real:** Akka dispatchers, Erlang processes.
- **Gate:** *trade-off* no locks vs message-passing overhead; *10×* mailbox backpressure; *what broke* a blocking receive starved the dispatcher.

### P15 · False sharing demo + fix 🟢
- **Clone of:** `@Contended` / JMH cache-line studies.
- **Recon:** "false sharing" articles; CPU cache line (64B).
- **Prereqs:** P9, P99 (JMH).
- **Spec:** nouns: two counters, cache line. verbs: increment from two threads.
- **Milestones:** 1) two adjacent `long`s incremented by two threads. 2) pad to separate cache lines / `@Contended`. 3) JMH before/after.
- **Acceptance:** padded version markedly faster. **Aha test:** ~2–10× slowdown purely from two fields sharing a 64B line.
- **Break it:** array-of-counters per thread, adjacent.
- **Compare to real:** JDK uses `@jdk.internal.vm.annotation.Contended` (LongAdder cells).
- **Gate:** *trade-off* padding wastes memory to win speed; *10×* invisible contention; *what broke* throughput tanked with no visible lock.

### P16 · Fork-join parallel merge sort 🟡
- **Clone of:** `ForkJoinPool`/`Arrays.parallelSort`.
- **Recon:** `RecursiveTask`, work-stealing; Amdahl's law.
- **Prereqs:** P3.
- **Spec:** nouns: task, fork/join, sequential cutoff. verbs: `compute`, `fork`, `join`.
- **Milestones:** 1) recursive merge sort. 2) fork halves as `RecursiveTask`. 3) tune sequential cutoff; measure speedup vs cores.
- **Acceptance:** sorted output; speedup approaches core count above cutoff. **Aha test:** speedup plateaus well below N× cores — Amdahl + merge overhead.
- **Break it:** cutoff too small (task overhead dominates).
- **Compare to real:** `parallelSort` cutoff ~8192.
- **Gate:** *trade-off* parallelism vs task/merge overhead; *10×* coordination cost; *what broke* tiny cutoff = slower than sequential.

### P17 · Scatter-gather with CompletableFuture 🟢
- **Clone of:** async aggregation (API gateway fan-out).
- **Recon:** `CompletableFuture` (`thenCombine`, `allOf`, `orTimeout`).
- **Prereqs:** P3.
- **Spec:** nouns: futures, combiner, timeout, fallback. verbs: `supplyAsync`, `allOf`, `exceptionally`.
- **Milestones:** 1) fan out N async calls. 2) combine via `allOf`. 3) per-call timeout + fallback.
- **Acceptance:** aggregates results; one slow/failed call doesn't hang the whole. **Aha test:** remove timeouts → one stuck dependency hangs the request forever.
- **Break it:** one call never returns; one throws.
- **Compare to real:** gateway aggregation, Resilience4j timeouts.
- **Gate:** *trade-off* parallel latency win vs complexity; *10×* a slow dep without timeout; *what broke* request hung on one dependency.

### P18 · Semaphore rate limiter & bulkhead 🟢
- **Clone of:** Resilience4j Bulkhead; `Semaphore`.
- **Recon:** `Semaphore` JavaDoc; bulkhead pattern.
- **Prereqs:** P3.
- **Spec:** nouns: permits, partitions. verbs: `acquire`, `release`, `tryAcquire(timeout)`.
- **Milestones:** 1) cap concurrency with a `Semaphore`. 2) separate permit pools per workload. 3) `tryAcquire` with timeout → reject fast.
- **Acceptance:** concurrency capped; one pool's exhaustion doesn't affect the other. **Aha test:** flood workload A → with shared permits B stalls; with bulkheads B is unaffected.
- **Break it:** never-released permit (leak); more waiters than permits.
- **Compare to real:** Resilience4j semaphore vs threadpool bulkhead.
- **Gate:** *trade-off* isolation vs resource duplication; *10×* shared-pool exhaustion cascades; *what broke* one workload sank the other until bulkheaded.

---

## Track 3 — Data Structures From Scratch

### P19 · Skip list 🟡
- **Clone of:** Redis sorted set (zset); `ConcurrentSkipListMap`.
- **Recon:** Pugh skip-list paper; Redis `t_zset` source.
- **Prereqs:** P1.
- **Spec:** nouns: levels, towers, forward pointers. verbs: `insert`, `search`, `delete`, `range`.
- **Milestones:** 1) levels via coin-flip; insert/search. 2) delete + range scan. 3) verify expected O(log n) height.
- **Acceptance:** ordered ops match a `TreeMap`; height ≈ log₂n. **Aha test:** randomized levels give O(log n) with **no rotations** — contrast with a balanced tree.
- **Break it:** adversarial insert order; all same key.
- **Compare to real:** Redis uses skip list + hash for zsets (range + O(1) lookup).
- **Gate:** *trade-off* simplicity (no rebalancing) vs probabilistic guarantees; *10×* tall towers rare but possible; *what broke* nothing structural — that's its charm.

### P20 · B-tree / B+tree 🟠
- **Clone of:** the index structure behind SQL databases.
- **Recon:** B+tree explainer; CMU DB indexes lecture.
- **Prereqs:** P19.
- **Spec:** nouns: node (page) with order m, keys, child pointers, leaf links. verbs: `search`, `insert` (split), `delete` (merge), `range`.
- **Milestones:** 1) in-memory B+tree (search/insert with node split). 2) delete with merge/borrow. 3) leaf-linked range scan.
- **Acceptance:** stays balanced; range scans walk leaves. **Aha test:** with high fan-out (m=100s), height stays 3–4 for millions of keys → few disk seeks.
- **Break it:** sequential inserts (right-heavy splits); delete underflow.
- **Compare to real:** Postgres/MySQL use B+trees; high fan-out minimizes page reads.
- **Gate:** *trade-off* fan-out (fewer levels) vs node size; *10×* still ~4 levels; *what broke* splits/merges off-by-one.

### P21 · LSM tree + SSTables 🟠
- **Clone of:** RocksDB/Cassandra storage.
- **Recon:** O'Neil LSM paper; RocksDB wiki.
- **Prereqs:** P19, P23.
- **Spec:** nouns: memtable (skip list), immutable SSTable (sorted file), compaction, levels. verbs: `put`, `get`, `flush`, `compact`.
- **Milestones:** 1) memtable + flush to sorted SSTable. 2) get checks memtable then SSTables newest→oldest. 3) leveled compaction; Bloom filter per SSTable.
- **Acceptance:** writes fast; reads find latest value across SSTables. **Aha test:** count SSTables touched per read before/after Bloom filters → read amplification, then its fix.
- **Break it:** many overlapping SSTables (read amplification); compaction starvation.
- **Compare to real:** RocksDB leveled vs size-tiered compaction.
- **Gate:** *trade-off* write speed vs read/space amplification; *10×* unbounded SSTables; *what broke* reads slowed until compaction + Bloom.

### P22 · Trie + radix/Patricia tree 🟡
- **Clone of:** prefix index / IP routing table.
- **Recon:** trie vs radix-tree explainers.
- **Prereqs:** P1.
- **Spec:** nouns: node per char, children map, terminal flag (radix: compressed edges). verbs: `insert`, `search`, `prefixSearch`.
- **Milestones:** 1) char trie insert/search. 2) prefix enumeration (autocomplete). 3) radix compression (merge single-child chains).
- **Acceptance:** prefix queries return all matches; radix uses fewer nodes. **Aha test:** memory drop from trie → radix on a real word list.
- **Break it:** huge alphabet; very long shared prefixes.
- **Compare to real:** Linux routing uses LPC-trie; DB prefix indexes.
- **Gate:** *trade-off* trie speed vs memory; radix fixes memory; *10×* node explosion; *what broke* memory blew up before compression.

### P23 · Bloom filter + counting Bloom 🟢
- **Clone of:** Bloom filters in Cassandra/HBase/Redis.
- **Recon:** Bloom-filter math (m bits, k hashes, false-positive rate).
- **Prereqs:** none.
- **Spec:** nouns: bit array, k hash functions. verbs: `add`, `mightContain`; counting: `remove`.
- **Milestones:** 1) bit array + k hashes (derive k from m,n). 2) measure false-positive rate vs theory. 3) counting Bloom (counters instead of bits) for deletes.
- **Acceptance:** no false negatives; FP rate matches formula. **Aha test:** tune m/k → watch FP rate trade against memory exactly as the formula predicts.
- **Break it:** over-fill (n ≫ designed); deletes on plain Bloom (can't).
- **Compare to real:** SSTable Bloom filters skip disk reads (ties to P21/P35).
- **Gate:** *trade-off* memory vs FP rate; *10×* over-fill → FP→1; *what broke* couldn't delete from a plain Bloom.

### P24 · HyperLogLog 🟡
- **Clone of:** Redis `PFCOUNT`.
- **Recon:** Flajolet HLL paper; antirez's HLL post.
- **Prereqs:** P23.
- **Spec:** nouns: registers, leading-zero count, harmonic-mean estimate. verbs: `add`, `count`, `merge`.
- **Milestones:** 1) register array; track max leading zeros per bucket. 2) cardinality estimate + bias correction. 3) merge two HLLs (union).
- **Acceptance:** estimates within ~2% of true cardinality on millions. **Aha test:** count distinct over 10M items using ~1.5KB — accuracy with near-zero memory.
- **Break it:** small cardinalities (need linear-counting fallback).
- **Compare to real:** Redis HLL ~12KB for 0.81% error.
- **Gate:** *trade-off* tiny memory vs approximate; *10×* memory stays flat; *what broke* small-N bias before correction.

### P25 · Count-Min Sketch 🟡
- **Clone of:** stream heavy-hitter counters.
- **Recon:** Cormode-Muthukrishnan CMS paper.
- **Prereqs:** P23.
- **Spec:** nouns: d×w counter matrix, d hashes. verbs: `add(key)`, `estimate(key)`.
- **Milestones:** 1) matrix + hashes; increment row per hash. 2) estimate = min across rows. 3) top-k via a heap of candidates.
- **Acceptance:** estimates ≥ true count, error bounded. **Aha test:** track frequencies of millions of keys in fixed memory; min-across-rows beats single-hash collisions.
- **Break it:** skewed streams; tiny width.
- **Compare to real:** used in network telemetry, Spark.
- **Gate:** *trade-off* fixed memory vs overestimate; *10×* width too small → error grows; *what broke* single row overcounted until min-of-d.

### P26 · Fenwick + segment tree 🟡
- **Clone of:** range-query structures.
- **Recon:** BIT (Fenwick) and segment-tree tutorials.
- **Prereqs:** none.
- **Spec:** nouns: tree-over-array, range, lazy tag. verbs: `update`, `rangeQuery`.
- **Milestones:** 1) Fenwick prefix-sum (point update, prefix query). 2) segment tree (range query). 3) lazy propagation (range update).
- **Acceptance:** matches brute force; O(log n) ops. **Aha test:** 10⁶ updates+queries fast vs O(n) recompute.
- **Break it:** range-update without lazy (TLE); overflow.
- **Compare to real:** competitive programming; analytics range aggregates.
- **Gate:** *trade-off* preprocessing vs query speed; *10×* O(n) scan dies; *what broke* range update slow before lazy.

### P27 · Red-black or AVL tree 🟠
- **Clone of:** `TreeMap`.
- **Recon:** RB-tree rules / AVL rotations.
- **Prereqs:** P20 mindset.
- **Spec:** nouns: nodes, color/height, rotations. verbs: `insert`, `delete`, rebalance.
- **Milestones:** 1) BST insert. 2) rotations + rebalance (pick AVL or RB). 3) delete with rebalance.
- **Acceptance:** stays balanced (height ≤ 2log n); ordered iteration. **Aha test:** insert sorted keys → BST degrades to a list; balancing keeps it O(log n).
- **Break it:** sorted/adversarial inserts; delete cases.
- **Compare to real:** `TreeMap`=RB; AVL stricter (more rotations, faster reads).
- **Gate:** *trade-off* AVL read-optimized vs RB write-optimized; *10×* unbalanced → O(n); *what broke* delete rebalancing cases.

### P28 · Binary + Fibonacci heap 🟡
- **Clone of:** `PriorityQueue`; Dijkstra's heap.
- **Recon:** binary heap; Fibonacci-heap decrease-key.
- **Prereqs:** P2.
- **Spec:** nouns: heap array / tree roots. verbs: `insert`, `extractMin`, `decreaseKey`.
- **Milestones:** 1) binary heap (array). 2) Dijkstra using it. 3) Fibonacci heap; compare decrease-key cost.
- **Acceptance:** correct min extraction; Dijkstra shortest paths. **Aha test:** decrease-key is O(log n) in binary heap vs O(1) amortized in Fibonacci — see why dense-graph Dijkstra cares.
- **Break it:** many decrease-keys; duplicate priorities.
- **Compare to real:** JDK `PriorityQueue` is a binary heap (no decrease-key).
- **Gate:** *trade-off* Fibonacci theory-fast vs constant-factor heavy; *10×* decrease-key cost; *what broke* binary-heap decrease-key was O(n) to find the node.

### P29 · Union-Find with path compression 🟢
- **Clone of:** connectivity / Kruskal's MST.
- **Recon:** disjoint-set union, path compression + union by rank.
- **Prereqs:** none.
- **Spec:** nouns: parent array, rank. verbs: `find`, `union`.
- **Milestones:** 1) naive find/union. 2) union by rank. 3) path compression.
- **Acceptance:** correct components; near-O(1) amortized. **Aha test:** time find() before/after path compression on deep chains → near-flat.
- **Break it:** long chains without compression.
- **Compare to real:** Kruskal, image connected-components.
- **Gate:** *trade-off* near-constant via two tiny optimizations; *10×* without them O(n) finds; *what broke* deep trees slow before compression.

### P30 · Merkle tree 🟡
- **Clone of:** git objects, Dynamo/Cassandra anti-entropy.
- **Recon:** Merkle-tree explainer.
- **Prereqs:** none.
- **Spec:** nouns: leaf hashes, internal node = hash(children), root. verbs: `build`, `rootHash`, `diff`.
- **Milestones:** 1) build tree of hashes over blocks. 2) compare two roots. 3) descend to find differing leaves.
- **Acceptance:** identical data → identical root; one change flips root + path. **Aha test:** diff two near-identical 1M-block sets by exchanging O(log n) hashes, not all data.
- **Break it:** odd leaf counts; reordering.
- **Compare to real:** git trees, Cassandra repair, blockchains.
- **Gate:** *trade-off* hashing overhead vs cheap diff; *10×* full compare wasteful; *what broke* odd-node handling.

### P31 · Consistent hashing ring + virtual nodes 🟡
- **Clone of:** Dynamo/Cassandra partitioning; sharded caches.
- **Recon:** consistent-hashing paper/explainer; vnodes.
- **Prereqs:** P1.
- **Spec:** nouns: hash ring, nodes, virtual nodes. verbs: `addNode`, `removeNode`, `route(key)`.
- **Milestones:** 1) ring with `TreeMap` (key→next node clockwise). 2) add/remove node → measure keys moved. 3) virtual nodes for balance.
- **Acceptance:** adding a node moves ~1/N keys (not all); load balanced with vnodes. **Aha test:** compare modulo-N hashing (almost all keys move when N changes) vs ring (~1/N move).
- **Break it:** few nodes without vnodes (imbalance); hot key.
- **Compare to real:** Cassandra vnodes, Memcached/Ketama.
- **Gate:** *trade-off* vnodes balance vs metadata; *10×* modulo rehash catastrophe; *what broke* skewed load without vnodes.

### P32 · Rope 🟡
- **Clone of:** text-editor buffer.
- **Recon:** rope data-structure explainer.
- **Prereqs:** P27 (balanced trees).
- **Spec:** nouns: tree of string chunks, weights. verbs: `insert`, `delete`, `index`, `concat`, `split`.
- **Milestones:** 1) tree of substrings with subtree-length weights. 2) index/insert/delete via split+concat. 3) rebalance.
- **Acceptance:** edits at arbitrary positions are O(log n), not O(n) copies. **Aha test:** insert in the middle of a 10MB string → no full copy.
- **Break it:** many small edits (fragmentation); deep unbalanced tree.
- **Compare to real:** editors/VCS for large files.
- **Gate:** *trade-off* O(log n) edits vs overhead for small strings; *10×* `String` copy = O(n); *what broke* unbalanced rope degraded.

---

## Track 4 — Database Internals

### P33 · KV store with a Write-Ahead Log 🟡
- **Clone of:** the WAL in every RDBMS; Bitcask.
- **Recon:** WAL / redo-log explainer; fsync semantics.
- **Prereqs:** P1.
- **Spec:** nouns: append-only log, record (op,key,value,crc), in-memory index. verbs: `put`, `get`, `recover`.
- **Milestones:** 1) append op to log + fsync, then update memory. 2) rebuild index by replaying log on startup. 3) CRC + truncate torn tail on crash.
- **Acceptance:** data survives restart; crash mid-write recovers to last intact record. **Aha test:** `kill -9` during a put → restart → log replays cleanly up to the torn record.
- **Break it:** crash mid-fsync; corrupt a record.
- **Compare to real:** Postgres WAL, SQLite journal, Kafka log.
- **Gate:** *trade-off* fsync durability vs write latency; *10×* log grows (needs compaction); *what broke* torn write until CRC + truncate.

### P34 · On-disk B+tree index 🟠
- **Clone of:** an RDBMS index file.
- **Recon:** page layout, slotted pages; ties to P20.
- **Prereqs:** P20, P33.
- **Spec:** nouns: fixed-size pages on disk, page id, node split/merge. verbs: `search`, `insert`, `rangeScan`.
- **Milestones:** 1) page file with `RandomAccessFile`/`FileChannel`. 2) B+tree nodes as pages; split on overflow. 3) range scan via leaf links.
- **Acceptance:** survives restart; point + range lookups correct. **Aha test:** measure page reads per lookup → ~tree height (3–4), not N.
- **Break it:** page-size overflow; concurrent writers.
- **Compare to real:** InnoDB 16KB pages; SQLite pages.
- **Gate:** *trade-off* page size vs read granularity; *10×* still ~4 reads; *what broke* node split across page boundary.

### P35 · LSM-based KV store 🔴
- **Clone of:** RocksDB.
- **Recon:** RocksDB wiki; ties to P21/P23.
- **Prereqs:** P19, P21, P23, P33.
- **Spec:** nouns: memtable, WAL, SSTable (+sparse index + Bloom), compaction. verbs: `put/get/delete`, `flush`, `compact`.
- **Milestones:** 1) memtable (skip list) + WAL. 2) flush to SSTable w/ index + Bloom. 3) read path (memtable→SSTables). 4) leveled compaction + tombstone deletes.
- **Acceptance:** durable, fast writes; reads return latest; compaction bounds SSTable count. **Aha test:** measure read amplification dropping as Bloom filters/compaction kick in.
- **Break it:** crash before flush (WAL recovers); compaction backlog.
- **Compare to real:** RocksDB levels; Cassandra.
- **Gate:** *trade-off* write speed vs read/space amplification + compaction CPU; *10×* SSTable explosion; *what broke* reads degraded until Bloom + compaction.

### P36 · MVCC + snapshot isolation 🟠
- **Clone of:** Postgres MVCC.
- **Recon:** "PostgreSQL Internals" concurrency chapter (interdb.jp).
- **Prereqs:** P33.
- **Spec:** nouns: row versions (xmin/xmax), transaction id, snapshot. verbs: `begin`, `read`, `write`, `commit`.
- **Milestones:** 1) each write creates a new version stamped with txid. 2) reads see versions visible to their snapshot. 3) commit/abort visibility + dead-version cleanup (vacuum).
- **Acceptance:** readers never block writers; repeatable reads within a txn. **Aha test:** long reader sees a consistent snapshot while writers commit around it.
- **Break it:** write skew; version bloat without vacuum.
- **Compare to real:** Postgres xmin/xmax + VACUUM; MySQL undo logs.
- **Gate:** *trade-off* read concurrency vs version bloat; *10×* bloat without vacuum; *what broke* stale/garbage versions accumulated.

### P37 · SQL parser + executor 🟠
- **Clone of:** a query engine front-to-back.
- **Recon:** relational operators; Volcano iterator model.
- **Prereqs:** P8 (parsing), P34.
- **Spec:** nouns: AST, logical plan, operators (scan/filter/project/join). verbs: `parse`, `execute` (`next()` iterator).
- **Milestones:** 1) parse `SELECT cols FROM t WHERE pred`. 2) iterator operators (scan→filter→project). 3) JOIN (nested-loop, then hash join).
- **Acceptance:** correct rows for multi-table queries. **Aha test:** swap nested-loop for hash join on a big join → see the operator tree pull rows lazily.
- **Break it:** cross join blowup; null handling.
- **Compare to real:** Postgres executor nodes; Calcite.
- **Gate:** *trade-off* nested-loop simple vs hash-join scalable; *10×* O(n²) join; *what broke* join slow until hashing.

### P38 · Cost-based query planner 🟠
- **Clone of:** the optimizer.
- **Recon:** cardinality estimation; selectivity; join ordering.
- **Prereqs:** P37.
- **Spec:** nouns: statistics (row counts, histograms), plan alternatives, cost model. verbs: `estimate`, `choosePlan`.
- **Milestones:** 1) collect stats. 2) cost index-scan vs seq-scan via selectivity. 3) order joins by estimated cardinality.
- **Acceptance:** picks the cheaper plan; explains its choice. **Aha test:** low-selectivity predicate → planner chooses seq scan over index (and you see why).
- **Break it:** stale stats; correlated columns.
- **Compare to real:** `EXPLAIN ANALYZE`; Postgres planner.
- **Gate:** *trade-off* planning time vs execution savings; *10×* bad stats → bad plan; *what broke* index scan chosen when seq scan was cheaper.

### P39 · Buffer pool / page cache 🟡
- **Clone of:** InnoDB buffer pool / `shared_buffers`.
- **Recon:** buffer-manager design; clock/LRU eviction.
- **Prereqs:** P34, P63 (eviction).
- **Spec:** nouns: frames, page table, pin count, dirty flag. verbs: `fetchPage`, `unpin`, `flush`, `evict`.
- **Milestones:** 1) fixed frame pool + page table. 2) LRU/clock eviction of unpinned pages. 3) dirty-page write-back.
- **Acceptance:** hot pages stay cached; dirty pages flushed before eviction. **Aha test:** measure hit rate as pool size grows; evicting a dirty page forces a write.
- **Break it:** all pages pinned (no frame); thrashing (pool < working set).
- **Compare to real:** Postgres clock-sweep; InnoDB LRU.
- **Gate:** *trade-off* pool size vs memory; *10×* thrash when working set > pool; *what broke* evicted a dirty page without flushing → data loss.

### P40 · Two-phase locking + deadlock detection 🟡
- **Clone of:** an RDBMS lock manager.
- **Recon:** 2PL, wait-for graphs.
- **Prereqs:** P10.
- **Spec:** nouns: lock table, shared/exclusive locks, wait-for graph. verbs: `acquire`, `release`, `detectDeadlock`.
- **Milestones:** 1) S/X locks per key; 2PL (grow then shrink). 2) blocking on conflict. 3) wait-for graph cycle detection + victim abort.
- **Acceptance:** serializable schedules; deadlocks detected and broken. **Aha test:** craft T1→T2→T1 lock cycle → detector aborts a victim.
- **Break it:** lock convoy; livelock on repeated victim choice.
- **Compare to real:** Postgres deadlock detector; MySQL.
- **Gate:** *trade-off* strictness vs concurrency; *10×* lock contention/deadlocks; *what broke* hung txns until cycle detection.

### P41 · Primary-replica log shipping 🟡
- **Clone of:** Postgres streaming replication.
- **Recon:** WAL shipping; sync vs async replication.
- **Prereqs:** P33.
- **Spec:** nouns: primary WAL, replica, apply position, lag. verbs: `ship`, `apply`, `lag`.
- **Milestones:** 1) stream WAL records to a replica. 2) replica applies in order. 3) measure lag; async vs sync ack.
- **Acceptance:** replica converges to primary; lag observable. **Aha test:** write on primary, read stale on replica → the read-your-writes gap.
- **Break it:** replica falls behind; network partition.
- **Compare to real:** Postgres `wal_sender`; MySQL binlog.
- **Gate:** *trade-off* async (fast, stale) vs sync (consistent, slow); *10×* replica lag; *what broke* stale reads on replica.

### P42 · Columnar store + RLE/dictionary encoding 🟡
- **Clone of:** Parquet/ClickHouse storage.
- **Recon:** columnar vs row storage; RLE, dictionary encoding.
- **Prereqs:** P33.
- **Spec:** nouns: per-column files, encodings, row groups. verbs: `write`, `scanColumn`, `aggregate`.
- **Milestones:** 1) store each column contiguously. 2) RLE + dictionary encoding. 3) aggregate scan; compare to row store.
- **Acceptance:** aggregates read only needed columns; compression ratio measured. **Aha test:** `SUM(col)` over millions reads one column, skips the rest → OLAP win; point lookup is worse than row store.
- **Break it:** wide row reconstruction; high-cardinality column (dictionary bloat).
- **Compare to real:** Parquet, ClickHouse, Redshift.
- **Gate:** *trade-off* OLAP scan/compression vs OLTP point access; *10×* row store scans everything; *what broke* row reconstruction slow in columnar.

---

## Track 5 — Networking & Protocols

### P43 · HTTP/1.1 server from raw TCP sockets 🟡
- **Clone of:** Tomcat/the servlet container's HTTP layer.
- **Recon:** RFC 7230 (request line, headers); `ServerSocket`.
- **Prereqs:** P3.
- **Spec:** nouns: socket, request (method/path/headers/body), response. verbs: `accept`, `parseRequest`, `writeResponse`.
- **Milestones:** 1) accept loop, parse request line + headers, return 200. 2) keep-alive (reuse connection). 3) chunked transfer + basic routing.
- **Acceptance:** serves real browsers/curl; keep-alive reuses sockets. **Aha test:** watch with `tcpdump` — HTTP is just text over a TCP byte stream.
- **Break it:** malformed headers; slowloris (partial request); huge body.
- **Compare to real:** Tomcat connectors; Netty HTTP codec.
- **Gate:** *trade-off* thread-per-connection simple vs limited; *10×* C10k problem (→ P44); *what broke* parser on header edge cases.

### P44 · Reactor server with Java NIO Selector 🟠
- **Clone of:** Netty's event loop; Redis/nginx I/O model.
- **Recon:** Reactor pattern; `Selector`/`SelectionKey`/non-blocking channels.
- **Prereqs:** P43, P47.
- **Spec:** nouns: selector, channels, interest ops, event loop. verbs: `select`, handle READ/WRITE.
- **Milestones:** 1) single-thread selector echo server. 2) per-connection read/write buffers + partial reads. 3) multi-reactor (boss/worker) for cores.
- **Acceptance:** thousands of connections on a few threads. **Aha test:** hold 10k idle connections on 1 thread — impossible with thread-per-connection.
- **Break it:** partial writes (backpressure); slow client blocking the loop.
- **Compare to real:** Netty `EventLoopGroup`; epoll.
- **Gate:** *trade-off* scalability vs complexity (callback style); *10×* a blocking op stalls all connections; *what broke* partial read/write framing.

### P45 · WebSocket server from scratch 🟡
- **Clone of:** `@ServerEndpoint` / Spring WebSocket.
- **Recon:** RFC 6455 (handshake, frame format, masking).
- **Prereqs:** P43.
- **Spec:** nouns: upgrade handshake, frame (fin/opcode/mask/len/payload). verbs: `handshake`, `readFrame`, `writeFrame`, `ping/pong`.
- **Milestones:** 1) HTTP Upgrade + `Sec-WebSocket-Accept` handshake. 2) parse/emit frames (mask client→server). 3) ping/pong + close handshake.
- **Acceptance:** a browser connects and echoes; control frames handled. **Aha test:** compute the `Sec-WebSocket-Accept` SHA1 yourself — see the upgrade is just a header dance over the same TCP socket.
- **Break it:** unmasked client frame (must reject); fragmented messages.
- **Compare to real:** Tomcat/Jetty WS; STOMP on top.
- **Gate:** *trade-off* persistent duplex vs connection cost; *10×* connection state memory; *what broke* masking/framing bugs.

### P46 · Length-prefixed binary RPC framework 🟠
- **Clone of:** gRPC/Thrift transport.
- **Recon:** message framing; request/response correlation IDs.
- **Prereqs:** P43, P49.
- **Spec:** nouns: frame (len-prefix + correlationId + payload), method registry, futures. verbs: `call(method,args)→Future`, `dispatch`.
- **Milestones:** 1) length-prefixed framing over TCP. 2) serialize args/results; method registry. 3) correlation IDs for async multiplexed calls.
- **Acceptance:** concurrent calls over one connection return to the right caller. **Aha test:** remove length prefixes → messages run together; TCP is a stream, not messages.
- **Break it:** interleaved responses; partial frames.
- **Compare to real:** gRPC over HTTP/2 streams; Thrift.
- **Gate:** *trade-off* multiplexing vs head-of-line blocking; *10×* correlation-map growth; *what broke* message boundaries without framing.

### P47 · Single-threaded event loop 🟡
- **Clone of:** Node.js/Redis event loop.
- **Recon:** event-loop + reactor; timers; "don't block the loop."
- **Prereqs:** none.
- **Spec:** nouns: task queue, timer heap, I/O readiness. verbs: `submit`, `setTimeout`, `run`.
- **Milestones:** 1) task queue processed by one thread. 2) timer wheel/heap for delayed tasks. 3) integrate non-blocking I/O readiness.
- **Acceptance:** ordered task execution; timers fire on time. **Aha test:** run a blocking task in the loop → everything else stalls (the "don't block the loop" lesson).
- **Break it:** a CPU-heavy callback; timer storms.
- **Compare to real:** libuv, Redis, Netty loop.
- **Gate:** *trade-off* no locks/simple vs one slow task blocks all; *10×* a blocking call kills throughput; *what broke* latency spiked on a blocking callback.

### P48 · Connection pool from scratch 🟡
- **Clone of:** HikariCP.
- **Recon:** HikariCP design notes; pool sizing.
- **Prereqs:** P3, P18.
- **Spec:** nouns: idle/active sets, max size, validation, leak timeout. verbs: `borrow(timeout)`, `return`, `evictIdle`.
- **Milestones:** 1) fixed pool, borrow/return via `BlockingQueue`. 2) max size + borrow timeout. 3) validation on borrow + leak detection.
- **Acceptance:** never exceeds max; borrow blocks/timeouts when exhausted. **Aha test:** leak connections (never return) → pool exhausts → all callers time out (the cascade).
- **Break it:** slow consumer holding connections; validation of dead conns.
- **Compare to real:** HikariCP `ConcurrentBag`.
- **Gate:** *trade-off* pool size vs DB load; *10×* exhaustion cascade; *what broke* leaked connections starved everyone.

### P49 · Varint + protobuf-style wire format 🟢
- **Clone of:** Protobuf/Kafka encoding.
- **Recon:** protobuf encoding (varint, zigzag, tag-length-value).
- **Prereqs:** none.
- **Spec:** nouns: varint, field tag (field#+wiretype), TLV. verbs: `writeVarint`, `readVarint`, `encode/decode message`.
- **Milestones:** 1) varint encode/decode. 2) zigzag for signed. 3) TLV message with field tags; skip unknown fields.
- **Acceptance:** round-trips integers/messages compactly; forward-compatible (skips unknown). **Aha test:** small numbers take 1 byte vs fixed 4/8 → why varint saves space.
- **Break it:** very large numbers; truncated stream.
- **Compare to real:** Protobuf, Kafka record format.
- **Gate:** *trade-off* compactness vs CPU to decode; *10×* fixed-width waste; *what broke* sign handling before zigzag.

### P50 · Gossip / epidemic membership protocol 🟠
- **Clone of:** Cassandra/Serf gossip.
- **Recon:** SWIM / epidemic-protocol papers.
- **Prereqs:** P52 (clocks help).
- **Spec:** nouns: member list, heartbeat/version per node, gossip round. verbs: `gossip(peer)`, `merge(state)`.
- **Milestones:** 1) nodes hold a member map with versions. 2) periodic gossip to random peers; merge newer versions. 3) detect join/leave; convergence time.
- **Acceptance:** membership converges across the cluster without a central registry. **Aha test:** kill a node → news of its failure spreads to all in O(log n) rounds.
- **Break it:** partition; conflicting updates.
- **Compare to real:** Cassandra gossiper; HashiCorp Serf (SWIM).
- **Gate:** *trade-off* eventual convergence vs no central point; *10×* gossip bandwidth; *what broke* slow/incorrect convergence on partition.

### P51 · Phi-accrual failure detector 🟡
- **Clone of:** Cassandra/Akka failure detector.
- **Recon:** Hayashibara φ-accrual paper.
- **Prereqs:** P50.
- **Spec:** nouns: heartbeat arrival history, φ suspicion value. verbs: `heartbeat()`, `phi()`.
- **Milestones:** 1) record inter-arrival times. 2) compute φ from the distribution. 3) threshold → suspect.
- **Acceptance:** adapts to network jitter; suspects only on real outage. **Aha test:** raise latency variance → fixed-timeout detector false-positives, φ-accrual adapts.
- **Break it:** bursty network; long GC pause on the monitored node.
- **Compare to real:** Cassandra/Akka.
- **Gate:** *trade-off* sensitivity vs false positives; *10×* fixed timeout wrong under jitter; *what broke* false "dead" calls before adaptive φ.

---

## Track 6 — Distributed Systems

### P52 · Lamport + vector clocks 🟡
- **Clone of:** causality tracking in Dynamo-style systems.
- **Recon:** Lamport "Time, Clocks…" paper; vector-clock explainer.
- **Prereqs:** none.
- **Spec:** nouns: per-node counter (Lamport) / vector. verbs: `tick`, `send`, `receive(merge)`, `compare`.
- **Milestones:** 1) Lamport scalar clock across simulated nodes. 2) vector clock. 3) classify event pairs: causal vs concurrent.
- **Acceptance:** vector clocks correctly detect concurrency. **Aha test:** two concurrent updates are flagged concurrent (not ordered) — "there is no global now."
- **Break it:** clock skew; many nodes (vector size).
- **Compare to real:** Dynamo vector clocks; hybrid logical clocks.
- **Gate:** *trade-off* Lamport (total order, loses concurrency info) vs vector (detects concurrency, O(N) size); *10×* vector size with N nodes; *what broke* couldn't distinguish concurrent from causal with scalar clock.

### P53 · Leader election: Bully → Raft-style 🟡
- **Clone of:** Raft leader election.
- **Recon:** Bully algorithm; Raft §5.2 (elections, randomized timeouts).
- **Prereqs:** P52.
- **Spec:** nouns: term, votes, election timeout, states (follower/candidate/leader). verbs: `requestVote`, `heartbeat`.
- **Milestones:** 1) Bully election by id. 2) term-based: timeout → candidate → request votes → majority wins. 3) randomized timeouts to avoid split votes.
- **Acceptance:** exactly one leader per term; re-elects after leader death. **Aha test:** synchronized timeouts → repeated split votes; randomization breaks the tie.
- **Break it:** simultaneous candidates; partition (two leaders different terms).
- **Compare to real:** Raft, ZAB.
- **Gate:** *trade-off* election speed vs split-vote risk; *10×* election storms; *what broke* split votes until randomized timeouts.

### P54 · Raft consensus 🔴
- **Clone of:** etcd/Consul Raft.
- **Recon:** Raft paper (Figure 2 is the spec); raft.github.io visualizer.
- **Prereqs:** P52, P53.
- **Spec:** nouns: log entries, term, commitIndex, nextIndex, state machine. verbs: `RequestVote`, `AppendEntries`, `apply`.
- **Milestones:** 1) elections (from P53). 2) log replication via AppendEntries + consistency check. 3) commit on majority + apply to state machine. 4) safety: log-matching, election restriction. 5) (stretch) snapshot + membership change.
- **Acceptance:** replicated log stays consistent across crashes; committed entries never lost. **Aha test:** kill the leader mid-replication → new leader, no committed entry lost, no divergence.
- **Break it:** partition + rejoin; stale leader; log conflicts.
- **Compare to real:** etcd, Consul, Kafka KRaft. Follow Figure 2 exactly.
- **Gate:** *trade-off* strong consistency vs needing a majority (availability); *10×* throughput capped by leader+fsync; *what broke* log divergence until log-matching + election restriction.

### P55 · Distributed lock with fencing tokens 🟡
- **Clone of:** ZooKeeper/Redlock locks.
- **Recon:** Kleppmann "How to do distributed locking."
- **Prereqs:** P52.
- **Spec:** nouns: lock key, lease/TTL, monotonic fencing token. verbs: `acquire→token`, `release`, fenced `write(token)`.
- **Milestones:** 1) lock via a store with TTL. 2) monotonic fencing token per acquire. 3) resource rejects writes with stale tokens.
- **Acceptance:** only the current holder writes; a paused old holder is fenced out. **Aha test:** simulate a GC pause that makes an old holder resume after lease expiry → its write is rejected by the fencing token.
- **Break it:** clock skew on TTL; holder pause.
- **Compare to real:** ZooKeeper zxid, Chubby.
- **Gate:** *trade-off* lease safety vs liveness on holder death; *10×* lock service load; *what broke* a paused holder corrupted data until fencing.

### P56 · Two-phase commit (and feel it block) 🟡
- **Clone of:** XA / distributed transactions.
- **Recon:** 2PC protocol; its blocking failure mode.
- **Prereqs:** P52.
- **Spec:** nouns: coordinator, participants, prepare/commit phases. verbs: `prepare`, `commit`, `abort`.
- **Milestones:** 1) coordinator sends prepare; participants vote. 2) commit if all yes. 3) kill coordinator after prepare → participants block holding locks.
- **Acceptance:** atomic across participants when healthy. **Aha test:** crash the coordinator post-prepare → participants hang indefinitely (the unavailability that motivates sagas).
- **Break it:** coordinator crash; participant timeout.
- **Compare to real:** XA, Postgres prepared txns; why microservices avoid it.
- **Gate:** *trade-off* atomicity vs availability (blocking); *10×* lock hold during blocking; *what broke* participants stuck on coordinator failure.

### P57 · Quorum reads/writes (Dynamo R+W>N) 🟡
- **Clone of:** Dynamo/Cassandra tunable consistency.
- **Recon:** Dynamo paper (N/R/W); quorum intuition.
- **Prereqs:** P52.
- **Spec:** nouns: N replicas, R read quorum, W write quorum. verbs: `write(W acks)`, `read(R responses)`.
- **Milestones:** 1) N replicas store versions. 2) write waits for W acks, read for R. 3) show R+W>N gives latest; R+W≤N gives stale.
- **Acceptance:** with R+W>N reads see latest write. **Aha test:** set R+W≤N → reproduce a stale read; bump to R+W>N → consistent.
- **Break it:** replica down; concurrent writes (need vector clocks/LWW).
- **Compare to real:** Cassandra `QUORUM`/`ONE`/`ALL`.
- **Gate:** *trade-off* consistency vs latency/availability (the R/W dial); *10×* tail latency at high R/W; *what broke* stale reads at R+W≤N.

### P58 · CRDTs: G-Counter, PN-Counter, OR-Set 🟠
- **Clone of:** Riak/Redis CRDTs; collaborative editing primitives.
- **Recon:** Shapiro CRDT paper; crdt.tech.
- **Prereqs:** P52.
- **Spec:** nouns: per-replica state, merge (join/LUB) function. verbs: `update`, `merge`, `value`.
- **Milestones:** 1) G-Counter (per-node counts, merge=max). 2) PN-Counter (inc+dec). 3) OR-Set (add/remove with unique tags).
- **Acceptance:** replicas converge regardless of merge order (commutative/idempotent). **Aha test:** apply updates in different orders on different replicas → all converge to the same value, no coordination.
- **Break it:** remove-then-add races (OR-Set tags); counter overflow.
- **Compare to real:** Riak, Redis CRDTs, Automerge/Yjs.
- **Gate:** *trade-off* coordination-free convergence vs metadata growth; *10×* tombstone/tag growth; *what broke* naive set lost concurrent add/remove until tagging.

### P59 · Anti-entropy with Merkle trees 🟡
- **Clone of:** Cassandra repair.
- **Recon:** Merkle anti-entropy (reuses P30).
- **Prereqs:** P30, P57.
- **Spec:** nouns: per-replica Merkle tree over key ranges. verbs: `buildTree`, `compareRoots`, `syncDifferingRanges`.
- **Milestones:** 1) build Merkle trees on two replicas. 2) compare roots → descend to differing leaves. 3) transfer only differing keys.
- **Acceptance:** diverged replicas reconcile transferring minimal data. **Aha test:** one differing key among millions → found by exchanging O(log n) hashes, not all data.
- **Break it:** large divergence; concurrent writes during repair.
- **Compare to real:** Cassandra `nodetool repair`; Dynamo.
- **Gate:** *trade-off* hash CPU vs network savings; *10×* full data transfer; *what broke* whole-dataset compare before Merkle.

### P60 · Eventually consistent KV store (mini-Dynamo) 🟠
- **Clone of:** Amazon Dynamo.
- **Recon:** Dynamo paper (integration of all pieces).
- **Prereqs:** P31, P52, P57, P59.
- **Spec:** nouns: ring (P31), replicas, vector clocks (P52), quorum (P57), anti-entropy (P59). verbs: `get`, `put` with N/R/W.
- **Milestones:** 1) route keys via consistent hashing to N replicas. 2) quorum read/write + vector-clock conflict detection. 3) read-repair + Merkle anti-entropy; hinted handoff.
- **Acceptance:** available under node failure; converges; conflicts surfaced. **Aha test:** the *integration* is the lesson — partition the cluster, write both sides, heal → conflicts detected and merged.
- **Break it:** partition + concurrent writes; replica permanently lost.
- **Compare to real:** Dynamo, Cassandra, Riak.
- **Gate:** *trade-off* always-writable vs conflict handling; *10×* coordination/repair load; *what broke* lost updates until vector clocks + read-repair.

### P61 · Chandy-Lamport snapshot 🟡
- **Clone of:** Flink distributed checkpoints.
- **Recon:** Chandy-Lamport paper; Flink checkpointing.
- **Prereqs:** P52.
- **Spec:** nouns: process state, channel state, marker messages. verbs: `initiateSnapshot`, `onMarker`, record channel.
- **Milestones:** 1) processes exchanging messages. 2) marker-based snapshot: record own state on first marker, record channel state until markers arrive. 3) assemble consistent global state.
- **Acceptance:** snapshot is causally consistent without pausing the system. **Aha test:** snapshot a running computation → the global state is one that *could* have existed, even though no global pause happened.
- **Break it:** in-flight messages; FIFO channel assumption.
- **Compare to real:** Flink checkpoint barriers.
- **Gate:** *trade-off* consistent snapshot vs FIFO-channel requirement; *10×* marker/channel overhead; *what broke* inconsistent cut without channel recording.

### P62 · Sharded counter with hotspot mitigation 🟢
- **Clone of:** high-write counters (likes/views).
- **Recon:** write-sharding pattern; `LongAdder`.
- **Prereqs:** P31.
- **Spec:** nouns: N sub-counters, key→shard. verbs: `increment` (random shard), `total` (sum).
- **Milestones:** 1) single counter (contended). 2) N sub-counters incremented independently. 3) read = sum; tune N.
- **Acceptance:** write throughput scales with shards; reads sum correctly. **Aha test:** single counter caps under contention; sharding linearizes write throughput.
- **Break it:** read consistency (sum not atomic); too many shards (slow reads).
- **Compare to real:** `LongAdder` cells; Cassandra counters; Firestore distributed counters.
- **Gate:** *trade-off* write scale vs read cost/consistency; *10×* single-counter contention; *what broke* writes bottlenecked on one hot row.

---
