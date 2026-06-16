# Design a Stock Exchange / Order-Matching Engine

> A worked, interview-grade design of an electronic exchange: accept buy/sell orders, match them deterministically against a price-time-priority order book at microsecond latency, and guarantee that no two participants ever trade the same share twice — all while staying fair, auditable, and crash-recoverable.

[← Back to master index](../../README.md) · [← System Design index](../README.md)

---

## 1. Requirements

A matching engine looks like "a priority queue with a for-loop," but the interviewer is probing whether you understand *determinism, fairness, durability, and ultra-low latency under hard correctness constraints*. This is not a CRUD system — a single lost or reordered order is a regulatory and financial incident. Lead by clarifying scope before drawing.

### Functional requirements
- **Submit order**: a participant places a `LIMIT`, `MARKET`, `IOC` (immediate-or-cancel), or `FOK` (fill-or-kill) order for an instrument (symbol), with side (BUY/SELL), quantity, and price.
- **Cancel / replace**: a resting order can be cancelled, or amended (price/qty), as long as it hasn't already fully filled.
- **Match**: cross incoming orders against the resting **order book** following **price-time priority** (best price first; at equal price, earliest order first — FIFO).
- **Market data**: publish trade prints (last price, size) and order-book updates (Level 1: best bid/offer; Level 2: aggregated depth; Level 3: full order-by-order) to subscribers.
- **Order lifecycle / acks**: every order gets a deterministic acknowledgement — `ACCEPTED`, `REJECTED`, `PARTIALLY_FILLED`, `FILLED`, `CANCELLED`, `EXPIRED`.
- **Clearing handoff**: emit immutable **execution reports** (fills) downstream to clearing/settlement and the audit store.
- **Session control**: pre-open auction, continuous trading, closing auction, halts/circuit breakers.

### Non-functional requirements
- **Latency**: matching p99 **< 100 microseconds** (tick-to-trade inside the engine); gateway round-trip p99 **< 1 ms**. This is a latency game measured in micros and nanos, not millis.
- **Throughput**: **1–5 million orders/sec** aggregate peak across the venue; a single hot symbol (e.g. an index ETF) can sustain **200K–500K orders/sec** on one book.
- **Determinism**: given the same ordered input stream, the engine **must** produce byte-identical output. Replayability is a hard requirement (for recovery, testing, and dispute resolution).
- **Fairness**: strict price-time priority; no participant may be advantaged by anything other than being first. No reordering, no queue-jumping.
- **Durability**: every accepted order and every fill must survive a process or machine crash with **zero loss** (RPO = 0). A confirmed fill is a binding contract.
- **Availability**: **99.99%+** during trading hours; sub-second failover. An outage during market hours is front-page news (cf. real NYSE/Nasdaq halts).
- **Consistency**: a single order book is **strongly consistent and linearizable** — there is exactly one source of truth for "who owns this share." This is a CP system at its core.
- **Auditability / regulation**: full, tamper-evident, time-sequenced audit trail (MiFID II / Reg NMS / SEC Rule 613 CAT) with nanosecond timestamps.

### Clarifying questions a strong candidate asks
1. **One symbol or many?** A single book is a single-threaded sequential problem; the whole "distributed" story is *across* symbols. (Drives the sharding model.)
2. **What order types?** Just limit/market, or also stop, iceberg, pegged, IOC/FOK? (Drives matching complexity.)
3. **Latency tier** — are we a retail venue (ms is fine) or an HFT-grade exchange (sub-microsecond)? This changes *everything*: kernel bypass, busy-spin, no GC.
4. **Continuous matching only, or auctions too?** Open/close auctions use a different (uncross) algorithm.
5. **Do we own clearing/settlement** or just matching? (Settlement is T+1/T+2 and a different system — usually out of scope.)
6. **Regulatory regime?** Reg NMS order protection / MiFID II changes routing and audit obligations.
7. **Recovery target** — RPO must be 0 (no lost fills); what's the acceptable RTO (failover time) — seconds, or sub-second hot-standby?
8. **Fairness model** — pure price-time (FIFO), or pro-rata (common in futures/options)?

> The single most important framing: **the matching engine itself is deliberately single-threaded per book.** Concurrency is the enemy of determinism and fairness. We scale *out* by sharding symbols across engines, never by parallelizing one book.

---

## 2. Capacity Estimation

Real numbers, shown long-hand. A large national equities venue.

### Order ingestion QPS
```
Peak venue load        = 5,000,000 orders/sec  (incl. new + cancel + replace)
Cancel:new ratio       ~ 10:1   (HFT cancels dominate — most orders never trade)
  → new orders         ~ 450,000/sec
  → cancels/replaces   ~ 4,550,000/sec
Trades (fills)         ~ 100,000 trades/sec at peak (most orders rest or cancel)
```
Cancels vastly outnumber fills — in real markets >95% of orders are cancelled, never executed. The engine is optimized for **fast insert + fast cancel**, not just matching.

### Per-symbol concentration
```
~ 8,000 listed symbols, but load is heavily skewed (power law).
Top 1% of symbols (~80 names) carry ~50% of order flow:
  0.50 × 5,000,000 = 2,500,000 orders/sec across 80 books
  hottest single book ≈ 200,000 – 500,000 orders/sec
```
A single book MUST handle ~500K ops/sec on one thread → ~2 microseconds/op budget. This is why the hot path is lock-free, allocation-free, and cache-conscious.

### Market-data fan-out (the real bandwidth monster)
```
Trades + book updates    ≈ 1,000,000 events/sec (L2 deltas + prints)
Each event               ≈ 50 bytes (binary, e.g. ITCH-style)
Raw feed                 = 1M × 50 B = 50 MB/s per full feed
Subscribers              ≈ 5,000 (brokers, HFT firms, data vendors)
```
Naive unicast: `50 MB/s × 5,000 = 250 GB/s` — impossible on point-to-point. → **Multicast** the feed once; subscribers join the group. Multicast collapses 250 GB/s into 50 MB/s on the wire. (This is exactly why exchanges run UDP multicast market data.)

### Storage (audit / journal over 1 trading year)
```
Trading hours          ≈ 6.5 h/day × 3600 = 23,400 s/day
Orders/day             = 5,000,000/s × 23,400 s ≈ 1.17 × 10^11  (~117 billion msgs/day)
Per journal record     ≈ 100 bytes (order/event, binary, fixed-ish)
Per day                = 117B × 100 B ≈ 11.7 TB/day
Per year (~252 days)   = 11.7 TB × 252 ≈ 2.95 PB/year raw
With 3x replication    ≈ 8.8 PB/year on durable storage
```
Regulators require multi-year retention (CAT: 6 years). Hot journal stays on NVMe for the trading day; cold audit data tiers to object storage (S3 Glacier-class). ~3 PB/year/copy is large but bounded and append-only.

### Memory (the order book lives in RAM)
```
Open orders resting at peak per hot book ≈ 1,000,000 orders
Per order in book        ≈ 128 bytes (id, side, price, qty, ts, client ref, intrusive list ptrs)
Per hot book             = 1M × 128 B ≈ 128 MB
80 hot books             ≈ 10 GB; all 8,000 books fit in ≈ 64–128 GB per engine host
```
The entire active state is **in memory** — disk is only the durability journal, never on the matching hot path. A matching engine never does a disk read to match.

---

## 3. API Design

Two very different surfaces: a **binary order-entry protocol** (latency-critical, often FIX/SBE or a proprietary binary protocol over TCP, plus kernel-bypass), and a **multicast market-data feed** (broadcast). Shown in readable pseudo-form.

```
# ---- Order Entry (binary, e.g. FIX 4.4 / FIXT, or SBE over TCP/UDP) ----
NewOrderSingle {
  client_order_id   : string   # unique per session, idempotency key
  symbol            : string   # e.g. "AAPL"
  side              : BUY | SELL
  order_type        : LIMIT | MARKET | IOC | FOK | STOP
  qty               : int
  price             : decimal  # required for LIMIT
  tif               : DAY | GTC | IOC | FOK
  account / session : ...
} -> ExecutionReport { exec_type: NEW|ACK|REJECT, order_id, status, ts_ns }

CancelOrder { client_order_id, orig_order_id, symbol }
              -> ExecutionReport { exec_type: CANCELED | CANCEL_REJECT }

ReplaceOrder { orig_order_id, new_qty?, new_price?, symbol }
              -> ExecutionReport { exec_type: REPLACE | REJECT }
              # NOTE: a price change or qty *increase* LOSES time priority (new ts);
              # a pure qty *decrease* keeps priority. This is a real, exam-worthy rule.

# Every state change emits an ExecutionReport back to the submitting session:
ExecutionReport {
  order_id, client_order_id, exec_type, order_status,
  last_qty, last_px, leaves_qty, cum_qty, avg_px,
  transact_time_ns, exec_id     # exec_id is the immutable fill identity
}

# ---- Market Data (multicast UDP, e.g. ITCH-style, sequenced) ----
# Subscribers join a multicast group; every message carries a monotonic seq number.
AddOrder      { seq, ts_ns, order_ref, side, qty, symbol, price }   # L3
OrderExecuted { seq, ts_ns, order_ref, exec_qty, match_id }
OrderCancel   { seq, ts_ns, order_ref, canceled_qty }
TradePrint    { seq, ts_ns, symbol, price, qty, match_id }          # L1 last trade
# Gap recovery: a separate TCP "retransmission/snapshot" service replays missed seqs.
```

Design notes:
- **`client_order_id` is the idempotency key**: a retried `NewOrderSingle` with a seen id is rejected as duplicate — never double-placed. Critical because the network *will* time out and clients *will* retry.
- **Order entry is request/response over reliable TCP** (you must know your order was accepted); **market data is fire-and-hose UDP multicast** with sequence numbers + a side-channel gap-fill (you tolerate loss and recover, you don't slow the feed for one slow subscriber).
- **Sequence numbers everywhere**: both the inbound order stream and the outbound MD feed are strictly sequenced so anything can be replayed and any gap detected.

---

## 4. Data Model

This system has almost no "database" on the hot path. The core data structures are **in-memory**, and the only persistent store is an **append-only journal**.

### The order book (in-memory, the heart of everything)
A book is two sides; each side is a **price-ordered map of price levels**, and each level is a **FIFO queue of orders** (intrusive doubly-linked list):

```
OrderBook(symbol):
  bids: sorted map  price (desc) -> PriceLevel
  asks: sorted map  price (asc)  -> PriceLevel

PriceLevel:
  price
  total_qty                 # aggregate, for fast L2 depth
  fifo: doubly-linked list of Order   # head = oldest = highest time priority

Order:
  order_id, client_order_id, side, price, qty, leaves_qty,
  ts_ns (arrival sequence), prev*, next*   # intrusive list pointers
  # plus an index entry:  order_id -> Order*  (hash map) for O(1) cancel
```

**Why these structures (the storage-engine choice for the hot path):**
- **Best price lookup must be O(1)** (you match against the top of book constantly). A balanced tree / skip-list / red-black map gives O(log P) on insert of a *new* price level, but P (distinct price levels) is small and most inserts hit an *existing* level → O(1) amortized. Many engines use a **flat price-indexed array** (price ladder) where each tick is a slot → true O(1) best-price and insert, at the cost of memory for the ladder. For liquid equities with tight tick grids, the array ladder wins.
- **Cancel must be O(1)**: a side `HashMap<order_id, Order*>` locates the node, and the intrusive linked-list lets us unlink in O(1) without scanning the level. Since cancels are 10x new orders, O(1) cancel is non-negotiable.
- **FIFO per level** enforces time priority directly.
- **No allocation on the hot path**: orders come from a pre-allocated **object pool / arena**; matching produces no garbage (in Java: zero-GC; in C++: no `malloc` in the loop).

### The durability journal (the only persistent thing on the hot path)
```
Journal (append-only, sequenced):  the input command log
  seq (monotonic) | ts_ns | session | NewOrder|Cancel|Replace | payload | crc
```
This is the **single source of truth**. The engine is a **deterministic state machine**: book_state = fold(journal). Replaying the journal from a snapshot exactly reconstructs the book. (Same insight as Kafka/Raft — the log is truth, the in-memory state is a materialized view.)

### Reference / cold data (off the hot path, conventional stores)
- **Instrument reference data** (symbols, tick sizes, lot sizes, trading status): small, read-mostly → in-memory map loaded at start, backed by Postgres.
- **Audit / execution-report store**: append-only, queried by regulators/compliance → columnar/time-series store (kdb+, ClickHouse, or a CAT pipeline) fed asynchronously from the MD/exec stream. Never on the matching path.

### Why NOT a general-purpose database in the loop
A SQL/NoSQL round-trip is **hundreds of microseconds to milliseconds** — that alone blows the entire latency budget. The book *must* be RAM-resident with custom structures; durability is achieved by **sequential journal append + replication**, exactly like a log-structured system, not by a transactional DB per order.

---

## 5. High-Level Architecture

```
   PARTICIPANTS (brokers, HFT, algos)
   ┌───────────┐   FIX/SBE over TCP        ┌──────────────────────────────────────┐
   │  client   │──(kernel-bypass NIC)─────►│         ORDER GATEWAY (FIX/SBE)        │
   │  algos    │◄──── ExecutionReport ─────│  auth · session · rate limit · risk    │
   └───────────┘                           │  pre-trade RISK CHECK (limits, fat-    │
                                           │  finger, credit) — inline, microsecs   │
                                           └───────────────────┬────────────────────┘
                                                               │ validated, sequenced commands
                                                               ▼
                                           ┌──────────────────────────────────────┐
                                           │        SEQUENCER (the arbiter)         │
                                           │  assigns global seq #, writes JOURNAL  │
                                           │  → THIS defines the canonical order    │
                                           └───────────────────┬────────────────────┘
                                        ┌──────────────────────┼──────────────────────┐
                                        │ replicate (Raft/      │ append-only journal   │
                                        │ aeron cluster)        ▼                       │
                                        ▼              ┌─────────────────┐              │
                              ┌──────────────────┐     │  JOURNAL (NVMe) │              │
                              │  HOT STANDBY      │     │  + replicas     │              │
                              │  matching engine  │     └─────────────────┘              │
                              │  (replays journal)│                                      │
                              └──────────────────┘                                      ▼
   route to the book's shard ──►  ┌────────────────── MATCHING ENGINE TIER ───────────────────┐
                                  │  single-threaded engine PER SYMBOL-SHARD (no locks)        │
                                  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
                                  │  │ Book: AAPL    │  │ Book: TSLA    │  │ Book: SPY    │ ... │
                                  │  │ price-time    │  │ price-time    │  │ price-time   │      │
                                  │  │ FIFO match    │  │ FIFO match    │  │ FIFO match   │      │
                                  │  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘      │
                                  └─────────┼─────────────────┼─────────────────┼──────────────┘
                                            │ fills + book deltas (sequenced)    │
                                            ▼                                    ▼
                          ┌──────────────────────────────┐      ┌──────────────────────────────┐
                          │  MARKET DATA PUBLISHER         │      │  CLEARING / AUDIT PIPELINE     │
                          │  UDP MULTICAST (ITCH) + gap    │      │  execution reports → clearing, │
                          │  recovery (TCP snapshot/retx)  │      │  CAT/regulatory store (async)  │
                          └───────────────┬────────────────┘      └────────────────────────────────┘
                                          ▼  multicast to 5,000 subscribers
                                   ┌──────────────┐
                                   │ subscribers   │
                                   └──────────────┘
```

### Component walkthrough (the order's journey)
1. **Order gateway**: terminates the FIX/SBE session, authenticates, applies **pre-trade risk checks** *inline* (price collars / fat-finger, position & credit limits, order-rate throttles, self-trade prevention). Rejecting here is cheaper than letting bad orders reach the book. Often runs on kernel-bypass (Solarflare/DPDK) to shave microseconds.
2. **Sequencer**: the *single* arbiter that assigns each command a **global monotonic sequence number** and writes it to the journal **before** anything is processed. This is what makes the system deterministic and fair: the sequencer defines "who was first," full stop. (See Deep Dive 6.2.)
3. **Journal + replication**: the sequenced command is durably appended (NVMe, fsync/group-commit) and replicated to the hot standby and journal replicas *before* the matching engine acts on it → RPO = 0.
4. **Matching engine**: routed to the correct **symbol shard**; a single-threaded engine matches the command against the in-memory book, producing fills + book deltas, all deterministic.
5. **Market-data publisher**: serializes fills and book updates into the sequenced multicast feed; a side TCP service serves snapshots and retransmissions for subscribers that gap.
6. **Clearing / audit pipeline**: execution reports flow asynchronously to clearing (T+1/T+2 settlement) and to the regulatory audit store — *off* the latency path.

---

## 6. Deep Dives

### 6.1 The matching algorithm — price-time priority, step by step

Continuous matching of an incoming **aggressive** order against the resting book:

```
match(incoming):                       # incoming = e.g. BUY LIMIT
  while incoming.leaves_qty > 0
        and best_ask exists
        and incoming.price >= best_ask.price:        # crosses the spread
      level   = best_ask                              # best price first
      resting = level.fifo.head                       # oldest first → time priority
      fill    = min(incoming.leaves_qty, resting.leaves_qty)

      execute(fill) at resting.price                  # PRICE = the RESTING order's price
                                                      # (price improvement goes to the taker)
      incoming.leaves_qty -= fill
      resting.leaves_qty  -= fill
      emit ExecutionReport(incoming, fill, resting.price)
      emit ExecutionReport(resting,  fill, resting.price)
      emit MarketData(TradePrint, OrderExecuted)

      if resting.leaves_qty == 0:
          level.fifo.pop_head(); remove from order index
          if level empty: remove price level

  if incoming.leaves_qty > 0:
      if incoming.type in {IOC}:  cancel remainder            # immediate-or-cancel
      elif incoming.type == FOK:  (this branch only if fully fillable — see below)
      else:                       rest incoming in book (becomes passive liquidity)
```

Subtleties an interviewer probes:
- **Trade price = the resting (passive) order's price**, not the incoming order's — the price-improving party is the one already in the book. A BUY @ 101 hitting an ASK resting @ 100 trades at **100**; the buyer gets 1.00 of improvement.
- **FOK (fill-or-kill)** must be checked atomically *before* executing: scan the book to confirm full quantity is available across levels; only then execute, else reject with zero fills. No partial state.
- **Market orders** have no price bound (or a wide collar); they walk the book until filled or liquidity exhausts — protected by price collars to avoid catastrophic fills in a thin book.
- **Self-trade prevention (STP)**: if the incoming order would match the same account's resting order, cancel one (or both) per the STP policy rather than wash-trade.

### 6.2 Determinism, the sequencer, and fairness

The matching engine is a **deterministic finite state machine**: `state_{n+1} = apply(state_n, command_n)`. For this to hold, two things are essential:

- **A single sequencer** establishes a total order over *all* commands before they touch any book. Whoever the sequencer stamps first *is* first — this is the definition of fairness. There is no clock-skew argument, no "my packet arrived first" dispute: the sequence number is the truth, and it's published in the audit trail.
- **No nondeterminism in the engine**: no wall-clock reads in matching logic (use the sequenced timestamp), no hash-map iteration order, no concurrency, no floating-point ambiguity (use fixed-point/integer ticks for prices). Same input log ⇒ byte-identical output, every time.

```
Why single-threaded beats multi-threaded for ONE book:
  - locks add latency + nondeterminism (lock-acquire order varies)
  - a single core doing pure in-memory work hits ~tens of millions of ops/sec
  - 500K ops/sec on one book fits comfortably in one pinned core
  → scale ACROSS books (shard by symbol), never WITHIN a book
```

This determinism is what powers crash recovery (replay the journal) and **regression testing** (replay a production day through a new engine build and diff the output — must be identical).

### 6.3 Durability & failover without losing a fill (RPO = 0)

A confirmed fill is a legal contract — it can never be lost. The pattern is **journal-then-replicate-then-act**:

```
On each command:
  1. Sequencer assigns seq #, appends to local journal (NVMe).
  2. Replicate the journaled command to ≥1 hot standby (and N journal replicas)
     via a consensus/replication protocol (Raft, or Aeron Cluster / LMAX-style).
  3. Only after the command is durably replicated does the engine MATCH it.
  4. The standby replays the same command stream → identical book state.
```

- **Hot/warm standby**: the standby engine consumes the *same* replicated, sequenced command stream and maintains an identical book. On primary failure, it's already warm → **sub-second failover**: promote standby, clients reconnect, resume from the last acked sequence number.
- **RPO = 0**: because we replicate *before* acting, any command that produced a visible ack/fill is already on the standby. Nothing acknowledged can be lost.
- **Snapshots + journal**: periodically snapshot the in-memory book; recovery = load latest snapshot + replay journal tail. Bounds replay time after a cold start.
- **Exactly-once on reconnect**: the `client_order_id` idempotency key + last-acked sequence number let a reconnecting client resync without double-submitting or missing acks.

CAP framing: a single book is **CP** — under a partition we must *stop matching* (lose availability) rather than risk two engines diverging on the same book (split-brain = the same share sold twice = catastrophic). Consistency and durability strictly dominate availability for the book; we buy availability back with fast failover, not with concurrent writers.

### 6.4 Scaling by sharding symbols (the only safe parallelism)

```
Engine host 1: books A–F   (single thread each / few threads, pinned cores)
Engine host 2: books G–M
Engine host 3: books N–Z + hot-symbol carve-outs (SPY, QQQ get dedicated cores)
```
- **Symbols are independent**: AAPL and TSLA never share a share, so their books never interact → embarrassingly parallel *across* symbols. Route each order to its symbol's shard.
- **Hot-symbol isolation**: a few names dominate flow; give the busiest their own dedicated engine/core so a quiet symbol on the same host isn't starved and a hot one has full headroom.
- **No cross-book transactions** in equities matching (one order touches one book), which is *why* this shards cleanly. (Cross-asset/spread products that touch multiple books are the hard exception — they need a coordinating engine or a synthetic combined book, and they break the clean sharding story.)
- **Capacity**: since one book ≈ 500K ops/sec on one core and we have ~5M/sec across 8,000 books, a few dozen pinned cores across a handful of hosts carry the venue — the bottleneck is **per-symbol** single-core throughput and **NIC/network**, not aggregate CPU.

### 6.5 Market-data fan-out at line rate

```
Naive unicast:  50 MB/s × 5,000 subs = 250 GB/s   → impossible
UDP multicast:  publish ONCE to a group; network replicates → 50 MB/s on the wire
```
- **Multicast over reliable unicast**: the engine sends each MD message once; switches/routers fan it out. A slow subscriber can never back-pressure the feed (fire-and-hose) — fairness demands everyone sees the same data at the same time.
- **Sequenced + gap recovery**: every MD message carries a monotonic `seq`. A subscriber that detects a gap requests a **retransmission** or a **snapshot** from a separate TCP service — the fast path never slows for a laggard.
- **A/B redundant feeds**: exchanges publish two independent multicast feeds (line A and line B) from separate infrastructure; subscribers arbitrate between them to fill gaps without a round-trip. Eliminates the retransmit latency for the common single-packet-loss case.
- **Fairness in distribution**: cable lengths to each colocated rack are even ("equal-length fiber") so no subscriber gets data nanoseconds earlier. Latency *fairness* is a regulatory and reputational concern, not just performance.

---

## 7. Scaling, Bottlenecks & Failure Handling

### What breaks first
1. **Single-book throughput** — one hot symbol's single core saturates. *Fix*: isolate it on a dedicated core/host; optimize the hot loop (cache lines, no allocation); ultimately a single book is bounded by one core — you cannot parallelize it without losing determinism.
2. **Market-data egress** — the fan-out, not matching, is the bandwidth wall. *Fix*: multicast, conflated/aggregated L2 feeds for slow subscribers, separate full vs. depth feeds.
3. **Gateway / risk-check latency** — pre-trade risk and FIX parsing add microseconds. *Fix*: kernel bypass (DPDK/Solarflare), SBE binary encoding over verbose FIX, precompiled risk rules.
4. **Journal write / replication latency** — fsync and cross-host replication are on the critical durability path. *Fix*: group commit, battery-backed NVMe, RDMA/Aeron for replication, co-located standby.
5. **GC pauses (if on JVM)** — a stop-the-world GC mid-session is a latency catastrophe. *Fix*: zero-allocation hot path, off-heap structures, or a non-GC language (C++/Rust). LMAX famously runs the JVM with effectively zero garbage.

### How to scale each axis
- **Order throughput** → shard by symbol across more engine cores/hosts; faster per-core hot loop. (No within-book scaling.)
- **Latency** → kernel bypass, busy-spin (no blocking/parking), CPU pinning + isolation, NUMA-aware memory, mechanical-sympathy data structures (ring buffers / LMAX Disruptor instead of queues with locks), co-location of clients.
- **Durability throughput** → group-commit the journal, batch replication, RDMA.
- **MD subscribers** → multicast scales to thousands "for free"; add edge/conflation services for slow consumers.

### Failure modes & mitigations
- **Primary engine crash** → hot standby (already replaying the same command stream) is promoted in sub-second; clients reconnect and resync from last acked seq. RPO = 0 because we replicated before matching.
- **Sequencer failure** → the sequencer is the SPOF for ordering; run it as a replicated state machine (Raft/Aeron Cluster) so a follower takes over with the same sequence continuity. Never two active sequencers (split-brain).
- **Split-brain / network partition** → strict **CP**: the side without quorum stops matching. Two engines matching the same book independently would sell the same share twice — unacceptable. We always choose "halt and recover" over "diverge."
- **Bad/erroneous orders (flash-crash risk)** → **circuit breakers / Limit Up-Limit Down (LULD)**: auto-halt a symbol when price moves beyond a band in a window; market-wide breakers halt the whole venue on a large index drop. Plus pre-trade price collars reject obviously bad prints.
- **Poison order / engine bug** → because everything is journaled, the exact input that triggered a crash is replayable in a test harness; deterministic replay turns "heisenbug in prod" into a reproducible unit test.
- **DR (datacenter loss)** → a synchronously-replicated standby site for RPO≈0 within a metro (latency-bounded), and async replication to a remote DR site (RPO>0, used only for total-region disaster, accepting some in-flight loss documented in the recovery plan).

---

## 8. Trade-offs & Alternatives

### Explicit decisions

| Decision | Chosen | Why / alternative |
|---|---|---|
| **Concurrency per book** | Single-threaded | Determinism + fairness; multi-threaded would add locks, nondeterminism, and split-brain risk. Scale across books instead. |
| **State location** | In-memory book | A DB round-trip blows the microsecond budget; durability comes from the journal, not a DB. |
| **Durability mechanism** | Journal + replicate-before-act | RPO=0 with low latency; synchronous per-order DB commit would be far too slow. |
| **Consistency** | CP per book (linearizable) | Selling the same share twice is catastrophic; we halt under partition rather than diverge. |
| **Matching rule** | Price-time (FIFO) | Fair, simple, deterministic. Pro-rata (futures/options) rewards size, not speed — a different fairness model. |
| **Order entry transport** | Reliable TCP (FIX/SBE) | You must know your order's fate. Market data uses lossy UDP multicast — opposite trade-off, on purpose. |
| **Market data** | UDP multicast + gap recovery | Only way to fan out to thousands without back-pressure or 250 GB/s of unicast. |
| **Sequencer** | Single replicated arbiter | One source of "who was first"; the foundation of fairness and determinism. |

### Alternatives & when they win
- **Pro-rata / size-priority matching** (common in interest-rate futures): at a price level, fills are allocated proportional to order size rather than strictly FIFO. Discourages the "many tiny resting orders to grab queue position" game; used where the venue wants to reward genuine size. Trade-off: more complex, and it changes microstructure incentives.
- **Frequent batch auctions** (e.g. some lit pools, IEX-style speed bumps): instead of continuous matching, batch orders into discrete intervals (e.g. every 1 ms) and uncross at a single clearing price. *Eliminates the latency race entirely* — being a microsecond faster buys nothing — at the cost of continuous price discovery. A legitimate "what would you change to neutralize HFT advantage" answer.
- **Pegged / iceberg / hidden orders**: add order types that reference the NBBO or hide displayed size. They complicate the matching loop and the fairness story (hidden liquidity vs. displayed-order priority rules).
- **Managed cloud build**: for a *low-latency-tier* (retail, crypto) venue, you can build on Kafka (as the sequenced journal) + a stateless matching service + Postgres for reference data, accepting millisecond latency. This is a perfectly good answer when the requirement is "an exchange," not "an HFT-grade exchange." The microsecond bare-metal/colo design is overkill — and prohibitively costly — if ms latency is acceptable.

### At 10x / 100x
- **10x order flow**: shard symbols across more dedicated cores/hosts; carve every hot name onto its own engine; move risk checks fully into kernel-bypass; conflate MD for non-latency-sensitive subscribers. The architecture doesn't fundamentally change — you add cores and isolate hot books.
- **100x / global multi-venue**: this becomes a *federation* of single-venue engines (the matching problem stays single-threaded per book — that's physics-of-fairness, not a scaling knob you can turn). Cross-venue order protection (Reg NMS routing to the best price across venues) and global market-data consolidation become the hard problems, not the matching engine itself. You also push harder on hardware: **FPGA/ASIC matching** for the very hottest books, where the entire match happens in silicon in tens of nanoseconds.

---

## Interview Q&A by Level

### 🟢 Basic

**Q. [Theory] What is an order book and what does "price-time priority" mean?**
An order book holds all resting (unmatched) buy orders (bids) and sell orders (asks) for one instrument. Price-time priority is the matching rule: the **best price** matches first (highest bid / lowest ask), and among orders at the *same* price, the **earliest-arriving** order matches first (FIFO). It's the fairness guarantee — you can only get ahead by offering a better price or being there sooner.

**Q. [Theory] What happens to an order that doesn't fully match immediately?**
For a `LIMIT` order, the unfilled remainder **rests** in the book as passive liquidity at its limit price, waiting for a future counterparty. For `IOC` (immediate-or-cancel) the remainder is cancelled. For `FOK` (fill-or-kill) the order is rejected entirely unless it can be filled in full at once. A `MARKET` order keeps walking the book until filled or liquidity runs out.

**Q. [Theory] At what price does a trade execute when a buy at 101 hits a sell resting at 100?**
At **100** — the resting (passive) order's price. The aggressive buyer was willing to pay up to 101 but receives price improvement to 100. The price-setting party is the one already resting in the book.

### 🟡 Intermediate

**Q. [Theory] Why is the matching engine single-threaded per book, and how do you still scale?**
A single book is a shared mutable resource where order matters absolutely — concurrency would introduce locks (latency + nondeterminism) and risk two threads selling the same share. A single pinned core handles tens of millions of in-memory ops/sec, far above any one symbol's load. We scale **across** symbols: each symbol's book is independent (AAPL and TSLA never share a share), so we shard symbols across cores/hosts. Parallelism is between books, never within one.

**Q. [Practical] How do you guarantee determinism, and why does it matter?**
A single **sequencer** assigns a global monotonic sequence number to every command before it touches a book, and the engine is a pure state machine with no wall-clock reads, no concurrency, no nondeterministic iteration, and integer (not float) prices. Same input sequence ⇒ byte-identical output. It matters for three reasons: crash recovery (replay the journal to rebuild state), regression testing (replay a production day through a new build and diff), and dispute resolution (the audit trail proves who was first).

**Q. [Practical] Order entry uses reliable TCP but market data uses lossy UDP multicast. Why the opposite choices?**
Order entry is a contract negotiation — you *must* know whether your order was accepted, so it needs reliable, acknowledged delivery (FIX/SBE over TCP) with an idempotency key for safe retries. Market data is a one-to-thousands broadcast where a single slow subscriber must never back-pressure everyone else, and unicasting 50 MB/s to 5,000 subscribers (250 GB/s) is impossible. So MD is fire-and-hose UDP multicast (sent once, replicated by the network) with sequence numbers and a side-channel gap-recovery service for anyone who misses a packet.

**Q. [Theory] What's the difference between Level 1, Level 2, and Level 3 market data?**
L1 is top-of-book: best bid/offer and last trade. L2 is aggregated depth: total quantity at each price level. L3 is full order-by-order detail: every individual resting order. They trade off bandwidth and information — HFT firms want L3, a retail app needs only L1.

### 🟠 Advanced

**Q. [Coding] Sketch the core data structures and the cancel path for O(1) cancellation.**
A book side is a price-ordered structure of price levels, each level a FIFO intrusive doubly-linked list, plus a global hash map for O(1) lookup:
```
class Order { long id, price, qty, leavesQty, tsSeq; Order prev, next; }   // intrusive node
class PriceLevel { long price, totalQty; Order head, tail; }               // FIFO
class BookSide {
    TreeMap<Long, PriceLevel> levels;          // or a flat price-ladder array for O(1)
    HashMap<Long, Order> index;                // orderId -> node, for O(1) cancel
}
void cancel(long orderId) {
    Order o = index.remove(orderId);           // O(1) locate
    if (o == null) return;                      // already filled/cancelled
    PriceLevel lvl = o.level;
    // unlink from the doubly-linked list in O(1) — no scan of the level
    if (o.prev != null) o.prev.next = o.next; else lvl.head = o.next;
    if (o.next != null) o.next.prev = o.prev; else lvl.tail = o.prev;
    lvl.totalQty -= o.leavesQty;
    if (lvl.head == null) levels.remove(lvl.price);   // empty level → drop
    pool.release(o);                            // back to object pool — no GC
}
```
The intrusive linked list (pointers inside the `Order` itself) plus the id→node map is what makes cancel O(1) — essential because cancels outnumber new orders ~10:1.

**Q. [Practical] How do you achieve RPO = 0 — never losing an acknowledged fill — without killing latency?**
Journal-then-replicate-then-act: the sequencer appends each command to a local NVMe journal and replicates it to a hot standby (and journal replicas) via a fast consensus/replication path (Raft or Aeron-style), and **only then** does the engine match it. Because replication happens *before* the order is acted on, anything that produced a visible ack or fill is already durable on the standby. Latency stays low via group-commit, battery-backed NVMe, and co-located RDMA replication — we batch and pipeline, we don't do a slow synchronous DB write per order.

**Q. [Theory] A symbol's price is crashing in milliseconds. What protects the market?**
Layered: pre-trade **price collars** reject orders priced absurdly far from the last trade (fat-finger protection); **Limit Up-Limit Down (LULD)** auto-halts a single symbol when its price moves beyond a percentage band within a rolling window; and **market-wide circuit breakers** halt the entire venue on a large index drop (e.g. S&P −7%/−13%/−20% thresholds). These trade continuous trading for stability, deliberately pausing to let liquidity and information re-gather.

**Q. [Behavioral] You discover a bug in the matching engine after a live trading session — some fills may have used the wrong price. Walk me through how you respond.**
First, contain and communicate: notify compliance and the incident commander immediately — this is potentially a regulatory and customer-money event, not a routine bug. Because everything is journaled deterministically, I'd **replay the exact production input stream** through both the buggy build and a corrected build and diff the outputs to identify precisely which fills are affected and by how much — turning a vague "some fills may be wrong" into an exact, evidence-backed list. I'd work with clearing and compliance on remediation (price adjustments / busted trades per exchange rules and regulator guidance), preserve the full audit trail, and only then do a blameless post-mortem: how did a non-deterministic or mistested change reach production, and what gate (replay-diff regression against a production day) do we add so it can't recur. The priorities are: stop the bleeding, quantify exactly with the journal, make affected parties whole within the rules, and harden the release process — in that order.

### 🔴 Expert

**Q. [Theory] Where does this system sit on CAP, and why is that non-negotiable for a single book?**
A single order book is strictly **CP** (consistent + partition-tolerant, sacrificing availability under partition). The book is the single source of truth for ownership of shares; if a network partition let two engine instances both match against "the same" book, they could each sell the same share, creating positions that don't net out — a financial and regulatory catastrophe with no clean recovery. So under partition the non-quorum side **halts** rather than diverges. We recover availability not by allowing concurrent writers (as an AP system would) but by **fast failover** to a hot standby that has been replaying the identical command stream. Consistency and durability strictly dominate availability here.

**Q. [Practical] How would you neutralize the speed advantage of HFT in your venue's design, and what's the cost?**
Replace continuous matching with **frequent batch auctions**: collect all orders arriving within a short interval (e.g. 1 ms) and uncross them at a single clearing price at the end of the interval, FIFO only as a tie-break. Within an interval, arriving a microsecond earlier confers no advantage — the latency race collapses because there's no "first" within a batch. Alternatively, an IEX-style **speed bump** (a fixed ~350 µs delay) neutralizes latency arbitrage on stale quotes. The cost is giving up *continuous* price discovery and adding a small, deterministic latency to every order; you trade microstructure fairness for a slightly less instantaneous market. It's a real design lever exchanges actually pull.

**Q. [Coding] How do you prevent a client double-submitting the same order across a network timeout and reconnect?**
Idempotency via `client_order_id` plus sequenced acks:
```
on NewOrderSingle(msg):
    if seen.contains(msg.client_order_id):          # dedup table per session
        resend cached ExecutionReport(msg.client_order_id)   # idempotent — no new order
        return
    seq = sequencer.next()
    journal.append(seq, msg)
    er  = engine.process(seq, msg)
    seen.put(msg.client_order_id, er)               # remember the outcome
    send er
# On reconnect, client replays from its last ACKED seq; the dedup table ensures
# any order the gateway already saw returns its original ExecutionReport,
# never a second placement.
```
The `client_order_id` is the idempotency key; the per-session dedup table returns the original outcome for any retried id; and the last-acked sequence number lets a reconnecting client resync the gap without re-sending already-accepted orders or missing acks.

**Q. [Theory] Where does the matching engine reach its hardware limit, and what's beyond software?**
The hard floor is **single-book single-core throughput plus network latency** — you cannot parallelize one book without breaking determinism/fairness, so one book is bounded by one core's ability to run the match loop, and the order's round-trip is bounded by NIC + switch + cable physics. Beyond software-on-CPU, the frontier is **FPGA/ASIC matching** for the very hottest symbols: the order parse, risk check, and match execute in dedicated silicon in tens of nanoseconds, and the NIC itself does kernel-bypass straight into the fabric. You also fight physics directly — **co-location** (clients rack-adjacent to the engine) and **equal-length fiber** to every rack so the speed of light through glass is fair. At that point you're optimizing nanoseconds and the bottleneck is literally the distance light travels, which is why exchanges sell colo space by the meter.

---

*Key takeaway: a matching engine is a masterclass in **deterministic, single-threaded, in-memory state machines**. The hard engineering isn't the match loop (it's a FIFO priority match) — it's the sequencer that defines fairness, journal-then-replicate durability for RPO=0, sharding by symbol as the only safe parallelism, multicast market-data fan-out, and choosing strict CP because selling the same share twice can never happen.*
