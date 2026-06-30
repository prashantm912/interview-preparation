# Project Briefs — Answer Key for All 110 Projects
**Use this AFTER your own attempt, not before.** Each brief is the worked spec you'd produce by running `14-hands-on-projects/project-playbook.md` (recon → spec → milestones → acceptance → break-it → compare → gate). Build the project from the 3-line description in `14-hands-on-projects/learning-projects.md` first; *then* open the matching brief here to check what you missed. Reading it first robs you of the deduction practice that is the whole point.

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

## Track 7 — Caching & Storage Systems

### P63 · LRU vs LFU vs ARC vs 2Q 🟡
- **Clone of:** cache eviction policies (Caffeine, ZFS ARC).
- **Recon:** ARC paper; eviction-policy comparisons.
- **Prereqs:** P1.
- **Spec:** nouns: capacity, recency/frequency metadata. verbs: `get`, `put`, `evict`.
- **Milestones:** 1) LRU (HashMap + doubly-linked list). 2) LFU (frequency buckets). 3) ARC/2Q (balance recency+frequency); replay a real access trace.
- **Acceptance:** correct eviction; hit-rate comparison on a trace. **Aha test:** a scan-heavy trace evicts hot items under LRU; ARC resists scan pollution.
- **Break it:** scan that flushes the cache; zipfian skew.
- **Compare to real:** Caffeine (W-TinyLFU), ZFS ARC.
- **Gate:** *trade-off* no single policy wins; ARC adapts at metadata cost; *10×* working set > cache → thrash; *what broke* LRU evicted hot keys on a scan.

### P64 · Write-through / write-back / write-around 🟢
- **Clone of:** cache write policies.
- **Recon:** cache write-policy explainer.
- **Prereqs:** P63.
- **Spec:** nouns: cache, backing store, dirty flag. verbs: `read`, `write` (policy-dependent).
- **Milestones:** 1) write-through (write both). 2) write-back (write cache, flush later). 3) write-around (skip cache on write). Measure latency + consistency.
- **Acceptance:** each policy behaves per spec; write-back risks loss on crash. **Aha test:** crash with dirty write-back entries → data lost; write-through survives but is slower.
- **Break it:** crash before flush; stale reads (write-around).
- **Compare to real:** CPU caches; Redis as a cache-aside layer.
- **Gate:** *trade-off* durability vs write latency; *10×* write-back flush storms; *what broke* lost dirty data on crash.

### P65 · Cache stampede protection (singleflight) 🟢
- **Clone of:** Go `singleflight`; request coalescing.
- **Recon:** thundering-herd / cache-stampede articles.
- **Prereqs:** P3, P63.
- **Spec:** nouns: in-flight map (key→Future). verbs: `get` (coalesce concurrent misses).
- **Milestones:** 1) cache-aside with TTL. 2) coalesce concurrent misses to one backend call. 3) early/probabilistic refresh before expiry.
- **Acceptance:** N concurrent misses → 1 backend call. **Aha test:** expire a hot key, fire 1000 concurrent reads → without coalescing the DB gets 1000 hits; with it, 1.
- **Break it:** hot key expiry under load; backend slow.
- **Compare to real:** Go singleflight; Caffeine refresh-after-write.
- **Gate:** *trade-off* coalescing latency vs backend protection; *10×* stampede DoSes the DB; *what broke* DB melted on hot-key expiry.

### P66 · Edge cache with stale-while-revalidate 🟡
- **Clone of:** CDN caching (Cloudflare/Varnish).
- **Recon:** `Cache-Control: stale-while-revalidate`; SWR.
- **Prereqs:** P63, P3.
- **Spec:** nouns: entry (value, freshUntil, staleUntil). verbs: `get` (serve fresh / serve-stale + async refresh / block).
- **Milestones:** 1) TTL cache. 2) serve stale past freshUntil while refreshing async. 3) background revalidation.
- **Acceptance:** users get instant (possibly stale) responses; origin load smoothed. **Aha test:** under expiry, latency stays flat (stale served) instead of spiking to origin latency.
- **Break it:** origin down during refresh; stale-forever bug.
- **Compare to real:** Varnish, Cloudflare, HTTP SWR.
- **Gate:** *trade-off* freshness vs latency/availability; *10×* origin overload without SWR; *what broke* latency spikes on every expiry.

### P67 · Content-addressable store (dedup by hash) 🟡
- **Clone of:** git object store, Dropbox/Docker layers.
- **Recon:** content-addressable storage; git internals.
- **Prereqs:** P30.
- **Spec:** nouns: blob keyed by `hash(content)`, refs. verbs: `put(bytes)→hash`, `get(hash)`.
- **Milestones:** 1) store blobs under their SHA. 2) identical content stored once (dedup). 3) chunking so similar files share chunks.
- **Acceptance:** duplicate content stored once; integrity verifiable by hash. **Aha test:** store the same 100MB file twice → second `put` adds ~0 bytes.
- **Break it:** hash collisions (theoretical); huge files (chunking).
- **Compare to real:** git, IPFS, Docker layers, Dropbox.
- **Gate:** *trade-off* dedup savings vs hashing cost; *10×* without chunking, small edits re-store whole file; *what broke* no dedup until content-addressing.

### P68 · Object store with chunking + erasure coding 🟠
- **Clone of:** S3/Ceph durability.
- **Recon:** Reed-Solomon erasure coding basics; replication vs EC.
- **Prereqs:** P67.
- **Spec:** nouns: object → chunks → k data + m parity shards. verbs: `put` (split+encode), `get` (reconstruct from any k).
- **Milestones:** 1) chunk + replicate (3×). 2) Reed-Solomon (k+m) encode/decode. 3) reconstruct from any k shards; compare storage overhead.
- **Acceptance:** survives m shard losses; recovers object. **Aha test:** delete m shards → object still reconstructs; EC uses ~1.5× storage vs 3× for replication.
- **Break it:** lose >m shards; corrupt a shard.
- **Compare to real:** S3, Ceph, HDFS-EC.
- **Gate:** *trade-off* EC (cheap durability, CPU on repair) vs replication (simple, costly); *10×* replication storage cost; *what broke* couldn't recover beyond m losses.

---

## Track 8 — Messaging & Streaming

### P69 · Mini message broker (Kafka-lite) 🔴
- **Clone of:** Apache Kafka (single node).
- **Recon:** Kafka protocol/log docs; Jay Kreps "The Log."
- **Prereqs:** P3, P33 (log), P49 (framing).
- **Spec:** nouns: topic, partition (append-only log), offset, segment+index, consumer group, coordinator. verbs: `produce`, `fetch(fromOffset)`, `commitOffset`, `subscribe`.
- **Milestones:** 1) on-disk append-only log per partition (offset, CRC, recovery). 2) sparse offset index. 3) segments + retention. 4) topics/partitions + key→partition. 5) request handling via P3 pool; per-partition serialized append. 6) consumer groups + rebalancing; offsets in an internal topic.
- **Acceptance:** durable ordered logs; key→same partition; group assigns each partition to one consumer; rebalance on join/leave. **Aha test:** wipe consumer state, replay from offset 0, rebuild a projection == live state.
- **Break it:** concurrent produce (offset gaps); kill mid-append (recovery); rebalance churn.
- **Compare to real:** Kafka `.log`/`.index`, `__consumer_offsets`, group coordinator. Deferred: replication→P54, exactly-once→P70.
- **Gate:** *trade-off* append-only (O(1) write, replay, ordering) vs no in-place update + retention need; *10×* one partition caps at one disk/consumer → add partitions; *what broke* data lost on restart and dup consumption until disk segments + group coordination.

### P70 · Exactly-once via idempotent producer + dedup 🟡
- **Clone of:** Kafka idempotent producer / EOS.
- **Recon:** Kafka idempotence (producer id + sequence numbers).
- **Prereqs:** P69.
- **Spec:** nouns: producer id, per-partition sequence number, broker dedup table. verbs: `send(seq)`, broker `dedupCheck`.
- **Milestones:** 1) at-least-once with retries → observe duplicates. 2) producer id + monotonic sequence. 3) broker rejects duplicate/old sequences.
- **Acceptance:** retried sends don't duplicate. **Aha test:** force a retry after a lost ack → without dedup you get a duplicate; with sequence numbers, deduped → "effectively once."
- **Break it:** producer restart (new id); out-of-order sequences.
- **Compare to real:** Kafka EOS, idempotent consumers.
- **Gate:** *trade-off* dedup state vs duplicate risk; *10×* dedup table growth; *what broke* duplicates on retry until sequencing.

### P71 · Stream processor with windows 🟠
- **Clone of:** Kafka Streams / Flink windowing.
- **Recon:** event-time vs processing-time; watermarks.
- **Prereqs:** P69.
- **Spec:** nouns: event (key, value, event-time), window (tumbling/sliding/session), watermark. verbs: `assignWindows`, `aggregate`, `emit`.
- **Milestones:** 1) tumbling windows on processing time. 2) event-time windows + watermarks for late data. 3) session windows.
- **Acceptance:** windowed aggregates correct; late events handled per policy. **Aha test:** feed out-of-order events → processing-time windows give wrong counts; event-time + watermark fixes it.
- **Break it:** very late events; watermark too aggressive (drops data).
- **Compare to real:** Flink/Kafka Streams windows.
- **Gate:** *trade-off* latency (early emit) vs completeness (wait for late); *10×* state size for long windows; *what broke* wrong aggregates on out-of-order data.

### P72 · Change Data Capture (CDC) from a WAL 🟡
- **Clone of:** Debezium.
- **Recon:** CDC via log tailing; reuses P33 WAL.
- **Prereqs:** P33, P69.
- **Spec:** nouns: WAL reader, change event, offset. verbs: `tailWal`, `emitChangeEvent`.
- **Milestones:** 1) tail the P33 WAL. 2) transform records → change events (insert/update/delete). 3) publish to the P69 broker; track position for resume.
- **Acceptance:** every committed DB change becomes a stream event, in order, resumable. **Aha test:** update a row → a change event appears downstream without app code emitting it.
- **Break it:** WAL truncation/rotation; resume after crash.
- **Compare to real:** Debezium, Postgres logical decoding.
- **Gate:** *trade-off* log-based CDC (no app change, ordered) vs coupling to WAL format; *10×* WAL read throughput; *what broke* missed/duplicated events on restart until offset tracking.

### P73 · Backpressure-aware pipeline 🟡
- **Clone of:** Reactive Streams (Project Reactor).
- **Recon:** Reactive Streams spec (demand/`request(n)`).
- **Prereqs:** P9.
- **Spec:** nouns: publisher, subscriber, demand, bounded buffer. verbs: `request(n)`, `onNext`, `onError`.
- **Milestones:** 1) push pipeline (no backpressure) → OOM with fast producer. 2) demand signaling (pull). 3) bounded buffers + drop/block strategies.
- **Acceptance:** fast producer + slow consumer stays bounded in memory. **Aha test:** remove demand signaling → heap grows unbounded; add `request(n)` → flat memory.
- **Break it:** infinitely fast source; consumer stall.
- **Compare to real:** Reactor/RxJava, gRPC flow control, TCP windows.
- **Gate:** *trade-off* throughput vs memory safety; *10×* unbounded buffering → OOM; *what broke* OOM until demand-based pull.

### P74 · DLQ + retry with exponential backoff + jitter 🟢
- **Clone of:** SQS/Kafka retry + DLQ.
- **Recon:** exponential backoff + jitter (AWS article).
- **Prereqs:** P69.
- **Spec:** nouns: retry topic, attempt count, DLQ. verbs: `process`, `retry(backoff)`, `deadLetter`.
- **Milestones:** 1) retry on failure with fixed delay. 2) exponential backoff. 3) jitter; max attempts → DLQ.
- **Acceptance:** transient failures recover; poison messages land in DLQ. **Aha test:** many consumers retrying in lockstep → synchronized retry storm; jitter spreads them out.
- **Break it:** permanent failure (infinite retry without max); retry storm.
- **Compare to real:** SQS redrive, Kafka retry topics.
- **Gate:** *trade-off* retry persistence vs giving up; *10×* thundering-herd retries; *what broke* retry storm until jitter + max attempts.

### P75 · Transactional outbox 🟡
- **Clone of:** the outbox pattern.
- **Recon:** dual-write problem; outbox + relay.
- **Prereqs:** P33, P69, P72.
- **Spec:** nouns: business table, outbox table (same DB txn), relay. verbs: `writeWithEvent` (one txn), `relayPoll→publish`.
- **Milestones:** 1) write business row + outbox row in one DB txn. 2) relay polls outbox → publishes to broker → marks sent. 3) (stretch) drive relay via CDC (P72).
- **Acceptance:** event published iff business data committed (no dual-write loss). **Aha test:** crash between DB commit and broker publish → relay still delivers the event (it's in the outbox).
- **Break it:** crash between commit and publish; duplicate publish (at-least-once).
- **Compare to real:** outbox in microservices; Debezium outbox router.
- **Gate:** *trade-off* reliability vs extra table/relay latency; *10×* outbox table growth; *what broke* lost events on dual-write until outbox.

---

## Track 9 — Architecture & Design Patterns

### P76 · Event sourcing + CQRS ledger 🟠
- **Clone of:** an event-sourced account ledger.
- **Recon:** event sourcing + CQRS articles.
- **Prereqs:** P69.
- **Spec:** nouns: event store (append-only), aggregate, projection (read model). verbs: `append(event)`, `replay→state`, `query(readModel)`.
- **Milestones:** 1) append domain events; rebuild aggregate by folding events. 2) projection consumer builds a read model. 3) rebuild read model by replay; snapshots for speed.
- **Acceptance:** state == fold over events; read model eventually consistent with writes. **Aha test:** delete the read model, replay events → identical state, with a full audit trail for free.
- **Break it:** projection lag; event schema evolution.
- **Compare to real:** EventStoreDB, Axon, banking ledgers.
- **Gate:** *trade-off* auditability/rebuild vs complexity + read lag; *10×* replay time (→ snapshots); *what broke* slow rebuild until snapshots.

### P77 · Saga: choreography vs orchestration 🟠
- **Clone of:** distributed-transaction sagas.
- **Recon:** saga pattern; compensation.
- **Prereqs:** P69, P56 (why not 2PC).
- **Spec:** nouns: steps, compensating actions, saga state. verbs: `execute(step)`, `compensate`.
- **Milestones:** 1) choreographed saga (order→inventory→payment via events). 2) compensation on failure (rollback prior steps). 3) orchestrated version (central coordinator); compare.
- **Acceptance:** failure mid-saga triggers compensations leaving a consistent state. **Aha test:** fail at payment → inventory + order compensate → system consistent without 2PC.
- **Break it:** compensation failure; non-idempotent steps.
- **Compare to real:** Temporal, Camunda, microservice sagas.
- **Gate:** *trade-off* choreography (decoupled, hard to trace) vs orchestration (visible, central); *10×* compensation complexity; *what broke* partial state until compensation logic.

### P78 · Hexagonal / ports-and-adapters refactor 🟡
- **Clone of:** clean/hexagonal architecture.
- **Recon:** ports-and-adapters; dependency inversion.
- **Prereqs:** none.
- **Spec:** nouns: domain core, ports (interfaces), adapters (DB/HTTP/queue). verbs: domain calls ports; adapters implement them.
- **Milestones:** 1) take a service with mixed concerns. 2) extract domain core with no framework imports. 3) push DB/HTTP behind port interfaces + adapters.
- **Acceptance:** domain unit-tests run with zero framework/IO. **Aha test:** swap the DB adapter (Postgres→in-memory) with no domain change.
- **Break it:** leaky abstractions; anemic domain.
- **Compare to real:** Spring + hexagonal; clean architecture.
- **Gate:** *trade-off* testability/flexibility vs upfront indirection; *10×* coupling rot without it; *what broke* untestable core until ports extracted.

### P79 · API gateway 🟠
- **Clone of:** Kong/Spring Cloud Gateway.
- **Recon:** gateway responsibilities; cross-cutting concerns.
- **Prereqs:** P43, P18, P93.
- **Spec:** nouns: routes, filters (auth/rate-limit/aggregate/cache). verbs: `route`, `preFilter`, `postFilter`.
- **Milestones:** 1) reverse-proxy routing by path. 2) auth (JWT) + rate limit (semaphore/token bucket) filters. 3) request aggregation + response caching.
- **Acceptance:** routes correctly; rejects unauthenticated/over-limit; aggregates downstream calls. **Aha test:** move auth/rate-limit out of services into the gateway → services get simpler.
- **Break it:** downstream slow (timeouts); auth bypass.
- **Compare to real:** Kong, Spring Cloud Gateway, Envoy.
- **Gate:** *trade-off* centralized cross-cutting vs a new bottleneck/SPOF; *10×* gateway throughput; *what broke* duplicated auth/limits until centralized.

### P80 · Sidecar proxy (mesh data-plane basics) 🟠
- **Clone of:** Envoy/Istio sidecar.
- **Recon:** service mesh data plane; sidecar pattern.
- **Prereqs:** P43, P44, P89.
- **Spec:** nouns: local proxy intercepting in/out traffic, policies. verbs: transparent proxy + retries/mTLS/metrics.
- **Milestones:** 1) proxy that forwards a service's traffic. 2) add retries + timeouts transparently. 3) mTLS + metrics emission, no app change.
- **Acceptance:** the app gets resilience + mTLS + metrics without code changes. **Aha test:** add retries/mTLS by deploying the sidecar — app binary untouched.
- **Break it:** proxy crash; double-encryption; latency overhead.
- **Compare to real:** Envoy/Istio, Linkerd.
- **Gate:** *trade-off* zero-touch cross-cutting vs latency/ops overhead; *10×* per-hop proxy latency; *what broke* app coupling to resilience libs until sidecar.

### P81 · Plugin architecture (SPI + classloaders) 🟡
- **Clone of:** Java SPI / Kafka Connect plugins.
- **Recon:** `ServiceLoader`, classloader isolation.
- **Prereqs:** none.
- **Spec:** nouns: plugin interface, provider registry, isolated classloader. verbs: `discover`, `load`, `invoke`.
- **Milestones:** 1) `ServiceLoader`-based discovery. 2) load plugins from external jars. 3) classloader isolation (conflicting deps).
- **Acceptance:** drop a jar → new behavior without recompiling host. **Aha test:** two plugins depending on different versions of a lib coexist via separate classloaders.
- **Break it:** classloader leaks; version conflicts.
- **Compare to real:** Kafka Connect, IDE plugins, JDBC drivers.
- **Gate:** *trade-off* extensibility vs classloader complexity; *10×* plugin dep conflicts; *what broke* jar hell until isolation.

### P82 · Feature-flag system 🟡
- **Clone of:** LaunchDarkly/Unleash.
- **Recon:** feature-flag patterns; gradual rollout.
- **Prereqs:** none.
- **Spec:** nouns: flag, targeting rules, rollout %, segments. verbs: `isEnabled(flag, context)`.
- **Milestones:** 1) boolean flags from config. 2) % rollout (hash user → bucket). 3) targeting rules + kill switch; live update.
- **Acceptance:** consistent per-user bucketing; instant kill switch. **Aha test:** roll a feature to 5% by hashing user id → same users always get the same answer (sticky).
- **Break it:** flag flicker (inconsistent hashing); stale config.
- **Compare to real:** LaunchDarkly, Unleash, Flipper.
- **Gate:** *trade-off* decouples deploy from release vs flag debt; *10×* flag evaluation latency; *what broke* users flickered until sticky hashing.

### P83 · Workflow / state-machine engine 🟠
- **Clone of:** Temporal/Camunda.
- **Recon:** durable workflow engines; state machines.
- **Prereqs:** P33, P76.
- **Spec:** nouns: workflow definition (states/transitions), durable state, timers. verbs: `start`, `signal`, `resume`.
- **Milestones:** 1) state machine executes steps. 2) persist state after each step (durable). 3) crash → resume from last persisted step; timers.
- **Acceptance:** a multi-step workflow survives a crash mid-flight and resumes. **Aha test:** kill the engine between steps → restart → it continues exactly where it left off.
- **Break it:** non-deterministic steps; duplicate execution on resume.
- **Compare to real:** Temporal, Camunda, AWS Step Functions.
- **Gate:** *trade-off* durability/resumability vs determinism constraints; *10×* persisted-state volume; *what broke* re-ran steps on resume until idempotency/checkpointing.

### P84 · Multi-tenancy: row → schema → DB-per-tenant 🟡
- **Clone of:** SaaS tenant isolation.
- **Recon:** multi-tenancy isolation models.
- **Prereqs:** none.
- **Spec:** nouns: tenant id, isolation level (shared row / schema / database). verbs: `routeTenant`, scoped queries.
- **Milestones:** 1) shared tables + `tenant_id` filter (+ row-level security). 2) schema-per-tenant. 3) DB-per-tenant routing.
- **Acceptance:** no cross-tenant data leakage at each level. **Aha test:** a forgotten `tenant_id` filter leaks data in the shared model → why RLS/schema isolation exists.
- **Break it:** missing tenant filter; noisy-neighbor.
- **Compare to real:** Salesforce (shared), per-schema SaaS, dedicated-DB enterprise tiers.
- **Gate:** *trade-off* isolation vs cost/ops per tenant; *10×* shared-model noisy neighbor; *what broke* data leak on a missed filter.

### P85 · Strangler-fig monolith migration 🟡
- **Clone of:** incremental monolith decomposition.
- **Recon:** Strangler Fig pattern (Fowler).
- **Prereqs:** P79.
- **Spec:** nouns: façade/router, legacy monolith, new service. verbs: `route` (slice → new vs old).
- **Milestones:** 1) façade in front of the monolith. 2) carve one slice into a new service; route it there. 3) migrate data + dual-write/verify; retire the old slice.
- **Acceptance:** a feature runs in the new service with no big-bang cutover. **Aha test:** flip one route to the new service; the rest stays on the monolith — incremental, reversible.
- **Break it:** data consistency during dual-write; rollback.
- **Compare to real:** classic monolith-to-microservices migrations.
- **Gate:** *trade-off* incremental safety vs temporary dual-system complexity; *10×* data sync load; *what broke* inconsistency during migration until dual-write/verify.

---

## Track 10 — Observability & Reliability

### P86 · Metrics library (counter/gauge/histogram + t-digest) 🟡
- **Clone of:** Micrometer/Prometheus client.
- **Recon:** t-digest paper; histogram vs summary in Prometheus.
- **Prereqs:** none.
- **Spec:** nouns: counter, gauge, histogram, t-digest. verbs: `increment`, `record`, `percentile(q)`, `scrape`.
- **Milestones:** 1) counters + gauges + scrape endpoint. 2) histogram buckets. 3) t-digest for accurate streaming p50/p99.
- **Acceptance:** percentiles within tolerance vs exact; cheap memory. **Aha test:** average latency hides a bad p99; compute p99 from your t-digest and see the tail.
- **Break it:** high-cardinality labels (memory blowup); skewed latency.
- **Compare to real:** Micrometer, Prometheus histograms, t-digest.
- **Gate:** *trade-off* accuracy vs memory (buckets/t-digest); *10×* label cardinality explosion; *what broke* averages lied about the tail.

### P87 · Distributed tracing from scratch 🟠
- **Clone of:** OpenTelemetry / Jaeger.
- **Recon:** W3C Trace Context; span/trace model.
- **Prereqs:** P86.
- **Spec:** nouns: trace id, span id, parent, context propagation. verbs: `startSpan`, `inject` (headers), `extract`, `export`.
- **Milestones:** 1) spans with timing + parent links. 2) propagate trace context across service calls (headers). 3) export + assemble a trace tree; sampling.
- **Acceptance:** one request shows a connected span tree across services. **Aha test:** follow one slow request across 3 services and see exactly which span is slow.
- **Break it:** lost context across async hops; sampling bias.
- **Compare to real:** OpenTelemetry, Jaeger, Zipkin.
- **Gate:** *trade-off* visibility vs overhead (→ sampling); *10×* trace volume/storage; *what broke* broken traces across async boundaries until context propagation.

### P88 · Structured logging + correlation IDs 🟢
- **Clone of:** MDC + structured logging.
- **Recon:** SLF4J MDC; JSON logging.
- **Prereqs:** none.
- **Spec:** nouns: structured event (JSON), correlation id, context (MDC). verbs: `log(kv...)`, propagate id.
- **Milestones:** 1) JSON log events with fields. 2) correlation id per request (MDC). 3) propagate id across threads/async + downstream calls.
- **Acceptance:** filter all logs for one request by correlation id across components. **Aha test:** grep one correlation id → the full story of a request across services/threads.
- **Break it:** id lost across thread-pool handoff; async context.
- **Compare to real:** SLF4J MDC, ELK, structured logging.
- **Gate:** *trade-off* structure/queryability vs log volume; *10×* log volume cost; *what broke* lost correlation across async until context propagation.

### P89 · Circuit breaker + bulkhead + retry 🟡
- **Clone of:** Resilience4j.
- **Recon:** circuit-breaker state machine (closed/open/half-open).
- **Prereqs:** P18.
- **Spec:** nouns: failure window, states, thresholds. verbs: `execute(supplier)`, state transitions.
- **Milestones:** 1) failure-rate window → open on threshold. 2) open rejects fast; half-open trial after cooldown. 3) combine with bulkhead + retry; then diff vs Resilience4j.
- **Acceptance:** stops calling a failing dependency; recovers via half-open. **Aha test:** make a dependency fail → breaker opens (fast-fail), recovers → half-open → closed.
- **Break it:** flapping dependency; retry amplification.
- **Compare to real:** Resilience4j, Hystrix.
- **Gate:** *trade-off* fast-fail protection vs rejecting maybe-OK calls; *10×* retry storms; *what broke* cascading failure until the breaker opened.

### P90 · Adaptive load shedding / concurrency limits 🟠
- **Clone of:** Netflix concurrency-limits.
- **Recon:** Netflix "Performance Under Load"; AIMD, Little's Law.
- **Prereqs:** P18, P89.
- **Spec:** nouns: in-flight limit, latency signal, AIMD controller. verbs: `acquire`/reject, adjust limit.
- **Milestones:** 1) static concurrency limit (reject over it). 2) AIMD: grow limit while latency OK, shrink on latency rise. 3) priority-aware shedding.
- **Acceptance:** stays responsive under overload by rejecting early. **Aha test:** ramp load past capacity → fixed limit collapses (latency explodes); adaptive sheds and holds latency flat.
- **Break it:** latency oscillation; bursty load.
- **Compare to real:** Netflix concurrency-limits, TCP congestion control.
- **Gate:** *trade-off* reject some requests vs degrade all; *10×* overload meltdown; *what broke* latency exploded until adaptive shedding.

### P91 · Chaos-injection tool + game day 🟡
- **Clone of:** Chaos Monkey / Toxiproxy.
- **Recon:** chaos engineering principles; fault injection.
- **Prereqs:** an earlier service (e.g., P89).
- **Spec:** nouns: fault types (latency, error, kill), blast radius. verbs: `inject(fault)`, `runExperiment`.
- **Milestones:** 1) inject latency/errors into a dependency call. 2) random instance kill. 3) run a game day with a hypothesis + steady-state metric.
- **Acceptance:** system's resilience (or lack) is observed under faults. **Aha test:** inject a dependency outage → watch your P89 breaker actually trip (or discover it doesn't).
- **Break it:** blast radius too large; no steady-state metric.
- **Compare to real:** Chaos Monkey, Gremlin, Toxiproxy.
- **Gate:** *trade-off* controlled risk vs discovering failures in prod; *10×* uncontrolled blast radius; *what broke* a resilience assumption that was false.

### P92 · SLO + error-budget burn-rate alerting 🟢
- **Clone of:** SRE SLO alerting.
- **Recon:** Google SRE workbook (SLO, error budget, burn rate).
- **Prereqs:** P86.
- **Spec:** nouns: SLI, SLO target, error budget, burn rate. verbs: `computeSLI`, `burnRate`, `alert`.
- **Milestones:** 1) define an SLI (success rate) + SLO (99.9%). 2) compute error budget consumption. 3) multi-window burn-rate alert.
- **Acceptance:** alerts on fast budget burn, not on every error. **Aha test:** a brief blip doesn't page; sustained burn does — alert on budget, not noise.
- **Break it:** alert fatigue (too sensitive); slow burn missed.
- **Compare to real:** Google SRE, Prometheus burn-rate rules.
- **Gate:** *trade-off* sensitivity vs noise; *10×* alert fatigue; *what broke* paged on every blip until burn-rate windows.

---

## Track 11 — Security Engineering

### P93 · JWT sign/verify (HS256 + RS256) 🟡
- **Clone of:** `jjwt`/Nimbus JWT.
- **Recon:** RFC 7519/7515 (JWS); base64url; HMAC/RSA.
- **Prereqs:** none.
- **Spec:** nouns: header.payload.signature, claims, key. verbs: `sign`, `verify`, `validateClaims`.
- **Milestones:** 1) base64url encode header+payload. 2) HS256 (HMAC) sign/verify. 3) RS256 (RSA) sign/verify + claim checks (exp/iss/aud).
- **Acceptance:** valid tokens verify; tampered/expired rejected. **Aha test:** craft an `alg:none` token (or swap RS256→HS256 with the public key) → a naive verifier accepts it. That's the classic JWT vuln.
- **Break it:** alg confusion; missing exp check; key confusion.
- **Compare to real:** jjwt, Nimbus, Auth0.
- **Gate:** *trade-off* stateless tokens vs no revocation; *10×* token size/verify cost; *what broke* `alg:none`/alg-confusion bypass until strict alg+key validation.

### P94 · OAuth2 auth-code + PKCE flow 🟠
- **Clone of:** "Login with Google."
- **Recon:** RFC 6749 (auth code) + RFC 7636 (PKCE).
- **Prereqs:** P93.
- **Spec:** nouns: client, auth server, resource server, code, code_verifier/challenge, tokens. verbs: authorize → code → token → access.
- **Milestones:** 1) auth-code flow (authorize, redirect with code, exchange for token). 2) PKCE (verifier/challenge). 3) resource server validates access token (P93).
- **Acceptance:** full three-party flow yields a usable access token; PKCE prevents code interception. **Aha test:** intercept the auth code without the verifier → token exchange fails (why PKCE exists for public clients).
- **Break it:** stolen code without PKCE; redirect-uri mismatch.
- **Compare to real:** Google/Okta/Auth0, Spring Authorization Server.
- **Gate:** *trade-off* delegated auth vs flow complexity; *10×* token validation load; *what broke* code interception until PKCE.

### P95 · Password storage done right 🟢
- **Clone of:** Spring Security `PasswordEncoder`.
- **Recon:** bcrypt/argon2; salt/pepper/work factor; OWASP.
- **Prereqs:** none.
- **Spec:** nouns: salt, work factor, hash, optional pepper. verbs: `hash(password)`, `verify`.
- **Milestones:** 1) salted bcrypt hash + verify. 2) tune work factor (timing). 3) pepper (server-side secret); upgrade-on-login.
- **Acceptance:** same password → different stored hashes (unique salt); verify works. **Aha test:** time a fast hash (SHA-256) vs bcrypt → why fast hashes are crackable at scale and slow KDFs aren't.
- **Break it:** unsalted (rainbow tables); too-low work factor.
- **Compare to real:** bcrypt/argon2id, Spring `DelegatingPasswordEncoder`.
- **Gate:** *trade-off* hashing cost vs crack resistance; *10×* fast-hash crackability; *what broke* identical hashes/rainbow risk until salting.

### P96 · TLS/mTLS handshake + cert-chain validation 🟡
- **Clone of:** JSSE / mutual TLS.
- **Recon:** TLS handshake; X.509 chain; CA trust.
- **Prereqs:** P43.
- **Spec:** nouns: keystore, truststore, cert chain, CA. verbs: handshake, validate chain, present client cert.
- **Milestones:** 1) one-way TLS server (self-signed). 2) custom CA → sign server+client certs. 3) mTLS: both sides validate chains.
- **Acceptance:** only certs signed by the trusted CA connect; bad chains rejected. **Aha test:** present a cert from an untrusted CA → handshake fails; from the trusted CA → succeeds.
- **Break it:** expired cert; hostname mismatch; self-signed without trust.
- **Compare to real:** JSSE, service-mesh mTLS, Let's Encrypt.
- **Gate:** *trade-off* mTLS strong identity vs cert lifecycle ops; *10×* cert rotation at scale; *what broke* trust failures until chain/CA set up right.

### P97 · AES-GCM at rest + envelope encryption 🟡
- **Clone of:** cloud KMS envelope encryption.
- **Recon:** AES-GCM (AEAD); envelope encryption / data keys.
- **Prereqs:** none.
- **Spec:** nouns: master key, data key, ciphertext + IV + tag. verbs: `encrypt`, `decrypt`, `generateDataKey`.
- **Milestones:** 1) AES-GCM encrypt/decrypt (IV + auth tag). 2) envelope: random data key encrypts data; master key encrypts data key. 3) store encrypted data key with ciphertext; key rotation.
- **Acceptance:** decrypt only with the master key; tampering detected (GCM tag). **Aha test:** rotate the master key by re-encrypting only data keys (not terabytes of data) — the envelope win.
- **Break it:** IV reuse (catastrophic for GCM); tampered ciphertext.
- **Compare to real:** AWS KMS, Google Cloud KMS, Vault.
- **Gate:** *trade-off* envelope = cheap rotation vs key-management complexity; *10×* re-encrypting all data on rotation; *what broke* IV reuse / no integrity until GCM + unique IVs.

### P98 · HMAC request signing (SigV4-style) 🟢
- **Clone of:** AWS Signature V4.
- **Recon:** AWS SigV4; HMAC; replay prevention.
- **Prereqs:** P93 (HMAC).
- **Spec:** nouns: canonical request, signing key, signature, timestamp/nonce. verbs: `sign(request)`, `verify`.
- **Milestones:** 1) canonicalize request → HMAC signature. 2) server recomputes + compares. 3) timestamp + nonce to block replay.
- **Acceptance:** tampered requests rejected; replays blocked. **Aha test:** the secret never travels — server recomputes the signature from the shared key; capture+replay an old signed request → rejected by timestamp/nonce.
- **Break it:** clock skew (timestamp window); nonce store growth.
- **Compare to real:** AWS SigV4, webhook signatures (Stripe/GitHub).
- **Gate:** *trade-off* no secret-in-transit vs canonicalization fiddliness; *10×* nonce store size; *what broke* replay attacks until timestamp+nonce.

---

## Track 12 — Performance Engineering

### P99 · JMH microbenchmark suite 🟢
- **Clone of:** correct microbenchmarking.
- **Recon:** JMH; JIT warmup, dead-code elimination pitfalls.
- **Prereqs:** an earlier project to benchmark.
- **Spec:** nouns: benchmark, warmup/measurement iterations, `Blackhole`. verbs: `@Benchmark`, consume results.
- **Milestones:** 1) naive `nanoTime` loop (wrong). 2) JMH benchmark with warmup. 3) `Blackhole`/return to defeat dead-code elimination.
- **Acceptance:** stable, reproducible numbers; naive vs JMH differ a lot. **Aha test:** a naive loop "proves" code is free (JIT eliminated it); JMH shows the real cost.
- **Break it:** measuring a constant; no warmup.
- **Compare to real:** JMH (the standard).
- **Gate:** *trade-off* rigor/time vs quick-but-wrong; *10×* misleading conclusions; *what broke* the JIT optimized away the thing you measured.

### P100 · Allocation elimination 🟡
- **Clone of:** low-GC hot paths.
- **Recon:** escape analysis; primitive collections; allocation rate vs GC.
- **Prereqs:** P99.
- **Spec:** nouns: allocation rate, object pool, primitive collections. verbs: pool/reuse, avoid boxing.
- **Milestones:** 1) measure allocation rate of a hot path. 2) remove boxing + use primitive collections (e.g., Eclipse Collections). 3) object pooling where justified; remeasure GC.
- **Acceptance:** allocation rate + GC pauses drop measurably. **Aha test:** GC pauses correlate with *allocation rate*, not heap size — cut allocations, cut pauses.
- **Break it:** pooling that outlives usefulness (leaks); premature optimization.
- **Compare to real:** Netty pooled buffers, LMAX.
- **Gate:** *trade-off* speed vs complexity/leak risk; *10×* allocation-driven GC pressure; *what broke* GC churn until allocations cut.

### P101 · Off-heap storage 🟡
- **Clone of:** Chronicle/Ehcache off-heap.
- **Recon:** `ByteBuffer.allocateDirect`, Foreign Memory API.
- **Prereqs:** P100.
- **Spec:** nouns: direct/native memory, serialization layout. verbs: `put`, `get` against off-heap.
- **Milestones:** 1) store a large dataset in a direct `ByteBuffer`. 2) Foreign Memory API (MemorySegment). 3) compare GC + access latency vs on-heap.
- **Acceptance:** big data off-heap with no GC impact; access works. **Aha test:** put gigabytes off-heap → GC pause times stay flat (GC doesn't scan it).
- **Break it:** native memory leak (no GC to save you); alignment.
- **Compare to real:** Chronicle Map, Ehcache, Netty direct buffers.
- **Gate:** *trade-off* GC avoidance vs manual memory management; *10×* on-heap GC scan cost; *what broke* native leak (no GC backstop).

### P102 · Zero-copy file transfer 🟢
- **Clone of:** Kafka's `sendfile` throughput.
- **Recon:** `FileChannel.transferTo`; sendfile syscall; user/kernel copies.
- **Prereqs:** P43.
- **Spec:** nouns: file channel, socket channel, copy path. verbs: `transferTo`.
- **Milestones:** 1) serve a file via read-into-buffer-then-write (copies). 2) `transferTo` zero-copy. 3) benchmark throughput + CPU.
- **Acceptance:** zero-copy higher throughput, lower CPU. **Aha test:** measure CPU/throughput — avoiding user-space copies is why Kafka is fast.
- **Break it:** small files (overhead); TLS (breaks zero-copy).
- **Compare to real:** Kafka, nginx sendfile.
- **Gate:** *trade-off* throughput vs not applicable with transforms/TLS; *10×* copy overhead; *what broke* CPU-bound copying until transferTo.

### P103 · Batching & pipelining 🟢
- **Clone of:** Kafka producer batching / Redis pipelining.
- **Recon:** batching vs latency; Nagle's algorithm.
- **Prereqs:** P46.
- **Spec:** nouns: batch buffer, linger time, max batch. verbs: `add`, `flush(onSizeOrTime)`.
- **Milestones:** 1) one request per round trip (baseline). 2) batch N requests / linger ms. 3) measure latency vs throughput curve.
- **Acceptance:** batching raises throughput; find the latency knee. **Aha test:** plot latency vs throughput as batch size grows → the classic trade-off curve and its knee.
- **Break it:** linger too long (latency); batch too big (memory).
- **Compare to real:** Kafka `linger.ms`/`batch.size`, Redis pipelining, JDBC batch.
- **Gate:** *trade-off* throughput vs latency; *10×* per-request overhead without batching; *what broke* latency spiked when batching too aggressively.

### P104 · Profile + fix a slow service 🟡
- **Clone of:** real performance debugging.
- **Recon:** async-profiler; flame graphs.
- **Prereqs:** P99 + an earlier service.
- **Spec:** nouns: CPU/alloc/lock profile, flame graph, hot path. verbs: profile → identify → fix → re-measure.
- **Milestones:** 1) profile an earlier project under load. 2) read the flame graph; find the true hot path. 3) fix + re-profile to confirm.
- **Acceptance:** a measured speedup tied to the profiled hotspot. **Aha test:** your guess about "what's slow" is wrong; the profiler points elsewhere.
- **Break it:** optimizing the wrong thing; profiler overhead.
- **Compare to real:** async-profiler, JFR, flame graphs.
- **Gate:** *trade-off* measure-first vs guess; *10×* wasted effort on cold paths; *what broke* your intuition — the profiler corrected it.

### P105 · GC tuning experiment (G1 vs ZGC) 🟡
- **Clone of:** JVM GC selection.
- **Recon:** G1 vs ZGC; pause-time vs throughput.
- **Prereqs:** P104.
- **Spec:** nouns: GC algorithm, pause distribution, allocation rate. verbs: run under load, capture GC logs.
- **Milestones:** 1) load test under G1 (capture pauses). 2) same under ZGC. 3) compare p99 pause + throughput.
- **Acceptance:** pause-time vs throughput trade-off shown with data. **Aha test:** ZGC slashes p99 pauses but G1 may give more raw throughput — see the trade-off, not dogma.
- **Break it:** under-sized heap; allocation storm.
- **Compare to real:** G1 (default), ZGC/Shenandoah (low-pause).
- **Gate:** *trade-off* pause time vs throughput/CPU; *10×* pause amplification under load; *what broke* p99 latency from GC until the right collector.

---

## Track 13 — AI/ML Systems

### P106 · Vector similarity search from scratch 🟡
- **Clone of:** a vector DB (FAISS/pgvector).
- **Recon:** cosine/L2 distance; HNSW paper.
- **Prereqs:** none.
- **Spec:** nouns: embeddings, distance metric, ANN graph. verbs: `add(vector)`, `search(query, k)`.
- **Milestones:** 1) brute-force top-k cosine. 2) measure latency as N grows. 3) HNSW graph (layered) for approximate search.
- **Acceptance:** top-k correct (brute force); HNSW fast with high recall. **Aha test:** brute force is O(N) per query and dies at scale; HNSW gives near-instant approximate results.
- **Break it:** high dimensions (curse of dimensionality); recall vs speed.
- **Compare to real:** FAISS, pgvector, HNSW in Lucene.
- **Gate:** *trade-off* exact (slow) vs ANN (fast, approximate recall); *10×* brute-force O(N); *what broke* query latency until ANN.

### P107 · Embedding cache + semantic dedup 🟢
- **Clone of:** LLM cost optimization.
- **Recon:** embedding caching; semantic similarity threshold.
- **Prereqs:** P106, P63.
- **Spec:** nouns: query→embedding cache, similarity threshold. verbs: `embed(cached)`, `findSimilar`.
- **Milestones:** 1) cache embeddings by text hash. 2) collapse near-duplicate queries (cosine > threshold) to one answer. 3) measure cost/latency saved.
- **Acceptance:** repeated/similar queries skip recompute + LLM call. **Aha test:** "What is X?" and "Explain X" map to the same cached answer above the threshold → cost drop.
- **Break it:** threshold too loose (wrong answers); cache staleness.
- **Compare to real:** GPTCache, semantic caches.
- **Gate:** *trade-off* cost savings vs wrong-match risk; *10×* LLM bill without caching; *what broke* over-aggressive dedup returned wrong answers.

### P108 · Semantic router / classifier over embeddings 🟢
- **Clone of:** intent routing without an LLM call.
- **Recon:** nearest-centroid classification; semantic routing.
- **Prereqs:** P106.
- **Spec:** nouns: route centroids (example embeddings), query embedding. verbs: `route(query)→nearest centroid`.
- **Milestones:** 1) embed example utterances per route. 2) route a query by nearest centroid. 3) confidence threshold → fallback.
- **Acceptance:** routes correctly without calling an LLM. **Aha test:** pick the right tool/prompt with a cheap vector comparison instead of an expensive LLM classification call.
- **Break it:** ambiguous queries; overlapping routes.
- **Compare to real:** semantic-router libraries, RAG query routing.
- **Gate:** *trade-off* cheap/fast vs less flexible than an LLM; *10×* LLM cost for routing; *what broke* misroutes on ambiguous input until thresholds.

### P109 · LLM cost-control gateway 🟡
- **Clone of:** an LLM proxy/gateway.
- **Recon:** token budgeting; rate limiting; fallback models.
- **Prereqs:** P18, P63, P79.
- **Spec:** nouns: token budget, rate limiter, response cache, model tiers. verbs: `route(request)`, enforce budget, cache, fallback.
- **Milestones:** 1) proxy LLM calls + count tokens. 2) per-user budgets + rate limits. 3) response cache + cheap-model fallback on budget/limit.
- **Acceptance:** caps spend; degrades gracefully (cheaper model) under limits. **Aha test:** productionizing an LLM is the *same* reliability/cost engineering (rate limit, cache, fallback) as any API — reuses P18/P63/P89.
- **Break it:** token miscount; cache poisoning; provider outage.
- **Compare to real:** LiteLLM, Portkey, cloud AI gateways.
- **Gate:** *trade-off* cost/limits vs quality (fallback model); *10×* unbounded spend; *what broke* runaway cost until budgets + caching.

### P110 · Mini feature store (online/offline parity) 🟡
- **Clone of:** Feast.
- **Recon:** feature store; training/serving skew.
- **Prereqs:** P33, P63.
- **Spec:** nouns: feature definitions, offline store (batch), online store (low-latency), point-in-time join. verbs: `getOnline`, `getHistorical`.
- **Milestones:** 1) offline store (compute features from history). 2) online store (fast lookup for serving). 3) ensure the *same* transformation feeds both; point-in-time correctness.
- **Acceptance:** training and serving use identical feature logic. **Aha test:** introduce a transform difference between offline and online → model degrades in prod (training/serving skew) → fix by sharing the definition.
- **Break it:** time leakage (future data in training); skew.
- **Compare to real:** Feast, Tecton.
- **Gate:** *trade-off* consistency/infra vs simplicity; *10×* serving lookup latency; *what broke* silent accuracy loss from train/serve skew.

---

*All 110 briefs complete (P1–P110). Work each project from `14-hands-on-projects/learning-projects.md` using `14-hands-on-projects/project-playbook.md`, then check yourself here.*
