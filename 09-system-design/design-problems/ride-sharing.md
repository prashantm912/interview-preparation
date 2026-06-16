# Design a Ride-Sharing Service (Uber / Lyft)

> A worked, interview-grade design of a ride-hailing platform: match riders to nearby drivers in seconds, track millions of moving GPS points in real time, price dynamically with surge, and run trips and payments reliably at city-by-city global scale.

[← Back to master index](../../README.md) · [← System Design index](../README.md)

---

## 1. Requirements

Ride-sharing is a *geospatial, real-time, money-handling* system. The interviewer is probing how you reason about location indexing, low-latency matching, the tension between supply and demand, and exactly-once trip/payment semantics. Scope it before drawing.

### Functional requirements
- **Driver location updates**: drivers stream GPS pings (~every 4 s) while online; the system tracks who is where.
- **Request a ride**: a rider submits pickup + destination; the system finds nearby available drivers and produces an ETA + fare estimate.
- **Matching / dispatch**: pick the best driver and offer the trip; handle accept/decline/timeout and re-offer.
- **Live trip tracking**: rider sees the driver's car move on a map in real time; driver follows turn-by-turn navigation.
- **Pricing**: base fare + distance + time, multiplied by a **surge** factor that reflects local supply/demand.
- **Trip lifecycle**: requested → matched → en-route-to-pickup → arrived → in-progress → completed → paid → rated.
- **Payments**: charge the rider, pay out the driver, handle splits, tips, refunds, and cancellations.
- **Ratings & history**: both parties rate each other; riders and drivers see trip history and receipts.

### Non-functional requirements
- **Scale**: ~5M concurrent online drivers, ~20M active riders at peak, **~25M ride requests/day**, **~1M location pings/sec** at peak (5M drivers × 1 ping / 4 s ≈ 1.25M/s).
- **Latency**: matching p99 **< 2 s** from request to a driver offer; location-ingest write path p99 **< 100 ms**; nearby-driver query p99 **< 200 ms**.
- **Availability**: **99.99%** for the request/match path — an outage in a city strands riders and drivers and is immediately visible. Degrade gracefully (looser matching) before failing.
- **Durability**: a *completed trip and its payment must never be lost*. Trip state and money events are the durable core; raw location pings are ephemeral.
- **Consistency**: a driver must be offered to **at most one rider at a time** (matching needs strong consistency on driver state); location reads can be eventually consistent (a 1–2 s stale car position is fine).
- **Security**: authenticated apps, encrypted location data, PCI-DSS-compliant payment handling (tokenize cards, never store PANs), fraud detection on both sides.

### Clarifying questions a strong candidate asks
1. **Single city or global?** Geo-partitioning by city/region is the backbone of scaling this — it changes everything.
2. **Match latency budget?** Sub-2-second matching forces in-memory geo indexes, not database `ST_Distance` queries.
3. **What does "best driver" mean** — nearest by straight-line, nearest by ETA (road network), or an optimization across many pending requests? Batch matching vs greedy nearest changes the design.
4. **Surge pricing required?** It couples pricing to a live supply/demand signal per geo-cell.
5. **Pooled rides (UberPool/Lyft Line)?** Shared rides turn matching into a routing/optimization problem (much harder).
6. **Payment model** — charge at completion only, or pre-auth at request time? Drives the payment state machine.
7. **Consistency on driver state** — can a driver ever be double-offered? (No — this is the one place we need strong consistency.)

> The make-or-break question is **geo-partitioning**: Uber doesn't run one global matcher. Demand is intensely local (a rider in NYC only matches NYC drivers), so we shard by city/region and keep each city's hot location index in memory. State this early — it frames every later decision.

---

## 2. Capacity Estimation

Real numbers, shown long-hand. Assume a 5-year horizon.

### Location-ingest write QPS (the firehose)
```
5,000,000 online drivers, 1 GPS ping every 4 seconds
5,000,000 ÷ 4 s = 1,250,000 pings/sec  (~1.25M WPS sustained)
Peak factor ~1.6x (rush hour)  →  ~2,000,000 pings/sec peak
```
This is the dominant write load by far — bigger than ride requests by ~5 orders of magnitude. The location pipeline is what we engineer around.

### Ride-request QPS
```
25,000,000 requests/day ÷ 86,400 s/day ≈ 290 requests/sec  (avg)
Peak factor ~5x (Friday night, surge)  →  ~1,500 requests/sec peak
```
Modest in raw QPS — but each request triggers a nearby-driver query, an ETA computation, a dispatch loop, and pricing. The *fan-out work per request* is the cost, not the request count.

### Nearby-driver read QPS
```
Each ride request → ~1 geo "drivers near me" query.
Plus riders viewing the map before requesting: assume 4x browse:request.
1,500 req/s × (1 + 4) ≈ 7,500 geo reads/sec peak
```

### Location storage (raw pings — ephemeral) over a day
```
1.25M pings/s × 86,400 s ≈ 1.08 × 10^11 pings/day  (~108 billion/day)
Per ping ≈ 40 B (driver_id 8, lat 8, lng 8, ts 8, heading/speed 8)
108e9 × 40 B ≈ 4.3 TB/day of raw pings
```
We do **not** durably keep raw pings forever. The live index holds only the *latest* position per driver (5M × ~64 B ≈ **320 MB** — trivially in RAM). Historical pings go to cheap object storage / a time-series store with short retention (e.g. 30–90 days for support/disputes), then sampled/aggregated.

### Trip storage (the durable core) over 5 years
```
25M trips/day × 365 × 5 = 45,625,000,000  ≈ 4.6 × 10^10 trips (~46 billion)
Per trip record ≈ 1 KB (ids, timestamps, route polyline ref, fare breakdown, status history)
46e9 × 1 KB ≈ 4.6 × 10^13 B ≈ 46 TB raw
With replication (3x) + indexes (~1.5x): 46 × 3 × 1.5 ≈ 207 TB
```
Trips are large but bounded and sit in a sharded transactional store. Payments mirror this volume in a separate PCI-scoped ledger.

### Memory — the live geo index
```
Latest position per online driver only:
5,000,000 drivers × ~64 B (id, lat, lng, ts, status, vehicle_type) ≈ 320 MB raw
With geo-index structure overhead (cell → driver lists, ~5x): ≈ 1.6 GB
Sharded per city across the dispatch fleet → a few GB per region, all in RAM.
```
The entire live map of every driver on Earth fits in a couple of GB. That's the key insight: **keep the matching index in memory, not in a database.**

### Bandwidth
```
Ingest: 2M pings/s × ~40 B ≈ 80 MB/s  (modest; the QPS, not the bytes, is the cost)
Live tracking out: riders watching ~3M active trips × 1 driver-position push / 4 s
   3,000,000 ÷ 4 × ~50 B ≈ 37 MB/s  (also modest)
```
Bandwidth is not the constraint — **write QPS on ingest and matching latency** are.

---

## 3. API Design

Mix of protocols by access pattern: **WebSocket / persistent gRPC streams** for high-frequency location and live trip events; **REST/HTTPS** for request-response operations (request ride, pricing, payments). Mobile-optimized, token-authenticated.

```http
# --- Driver location stream (high frequency, persistent) ---
WS  /v1/driver/location          # bidirectional WebSocket / gRPC stream
→ client sends every ~4s:
    { "driver_id":"d_42", "lat":40.7128, "lng":-74.0060,
      "heading":270, "speed":12.4, "ts":1718536800, "status":"available" }
→ server may push:  { "type":"trip_offer", "trip_id":"t_99", "expires_in":15, ... }

# --- Rider requests a ride ---
POST /v1/rides
Authorization: Bearer <token>
{ "rider_id":"r_7", "pickup":{"lat":40.71,"lng":-74.00},
  "destination":{"lat":40.75,"lng":-73.99}, "vehicle_type":"uberx" }
→ 202 Accepted
  { "trip_id":"t_99", "status":"matching",
    "fare_estimate":{"low":1450,"high":1700,"currency":"USD","surge":1.4},
    "eta_pickup_sec":240 }

# --- Pricing / fare estimate (no commitment) ---
GET /v1/pricing/estimate?pickup_lat=..&pickup_lng=..&dest_lat=..&dest_lng=..&vehicle_type=uberx
→ 200 { "base":300,"per_km":120,"per_min":25,"surge":1.4,"estimate_low":1450,"estimate_high":1700 }

# --- Driver responds to an offer ---
POST /v1/trips/{trip_id}/respond
{ "driver_id":"d_42", "action":"accept" }   # or "decline"
→ 200 { "status":"matched", "rider":{...}, "pickup":{...}, "route_polyline":"..." }
→ 409 Conflict     # offer already expired / taken — re-offered elsewhere

# --- Live trip tracking (rider side) ---
WS  /v1/trips/{trip_id}/track
→ pushes driver position + status transitions in near real time

# --- Trip lifecycle transitions (idempotent) ---
POST /v1/trips/{trip_id}/arrived        # driver at pickup
POST /v1/trips/{trip_id}/start          # rider on board
POST /v1/trips/{trip_id}/complete       { "end_location":{...}, "distance_km":6.2, "duration_min":18 }
POST /v1/trips/{trip_id}/cancel         { "by":"rider","reason":"..." }

# --- Ratings ---
POST /v1/trips/{trip_id}/rating         { "by":"rider","stars":5,"tip_cents":300 }
```
Design notes: location uses a **persistent stream** (a new HTTPS request every 4 s for 5M drivers would be brutal on connection setup). Lifecycle transitions carry an **idempotency key** (e.g. `trip_id + transition`) so a retried `complete` doesn't double-charge. The match is asynchronous: `POST /rides` returns `202` immediately with `status: matching`, and the result arrives over the rider's tracking stream.

---

## 4. Data Model

Four stores, each chosen for its access pattern.

### 1. Live driver location index — **in-memory geospatial** (Redis Geo / custom QuadTree/H3 service)
The matching query is "give me available drivers within X of this point, fast," at thousands of QPS, against data that changes every 4 s. A disk-backed DB cannot keep up. Hold it in memory:
```
Per-city in-memory index, keyed by geo-cell (S2/H3/Geohash):
  cell_id  →  { driver_id, lat, lng, ts, vehicle_type, status }  (sorted set / list)
Also: driver_id → current cell_id   (so we can move a driver between cells on each ping)
```
- **Why Redis Geo or a custom H3/QuadTree service**: O(log n) or O(1)-ish radius queries entirely in RAM; the data is ephemeral (rebuildable from the next round of pings), so durability isn't required here.
- The index is **partitioned by city/region** — a NYC dispatch node never sees London drivers.

### 2. Trips — **sharded relational / NewSQL** (PostgreSQL with Citus, or CockroachDB / Spanner)
A trip has a strict state machine and is the unit of money. We want transactions, secondary indexes, and a clear consistency story:
```
Table: trips
  trip_id        UUID    PRIMARY KEY
  rider_id       UUID    (indexed)
  driver_id      UUID    (indexed, nullable until matched)
  status         ENUM    requested|matching|matched|en_route|arrived|in_progress|completed|cancelled
  pickup_geo     POINT
  dropoff_geo    POINT
  route_ref      STRING  -- pointer to route polyline in object store
  fare_cents     INT
  surge_factor   DECIMAL
  city_id        STRING  -- SHARD KEY
  requested_at   TIMESTAMP
  completed_at   TIMESTAMP
  state_history  JSONB   -- transition log for audit
```
- **Shard key = `city_id`** (geo-locality): a trip's reads/writes all happen within one region, so they stay on one shard — no cross-shard transactions on the hot path.
- A state machine in a transactional store gives us **at-most-once matching** and a clean audit trail.

### 3. Payments / ledger — **separate PCI-scoped transactional store** (double-entry ledger)
```
Table: ledger_entries
  entry_id     UUID
  trip_id      UUID
  account      STRING   -- rider, driver, platform_fee, taxes
  amount_cents INT      -- signed; debits + credits sum to zero per trip
  type         ENUM     charge|payout|refund|tip|fee
  status       ENUM     pending|authorized|captured|settled|failed
  idempotency_key STRING UNIQUE
```
Double-entry + idempotency keys guarantee no double-charge and a fully auditable money trail. Card data is **tokenized** via the PSP (Stripe/Braintree); we never store PANs.

### 4. Historical locations & analytics — **time-series / columnar** (Cassandra, or S3 + ClickHouse)
Raw pings stream here for ~30–90 days (disputes, fraud, ETA model training), then are aggregated/sampled. Append-only, high-volume, queried by `(trip_id, time)` — a natural fit for wide-column/time-series.

### Why this split
| Data | Mutation rate | Durability need | Store |
|---|---|---|---|
| Live driver positions | 1.25M/s, overwrite | None (rebuildable) | In-memory geo index |
| Trips | ~1.5K/s, state machine | Critical | Sharded NewSQL |
| Money | ~1.5K/s, ledger | Absolute | PCI ledger DB |
| Raw ping history | 1.25M/s, append | Low / short TTL | Time-series / object store |

Putting the location firehose in the same store as money would either crush the transactional DB or compromise the ledger — they have opposite requirements, so they get opposite stores.

---

## 5. High-Level Architecture

```
        ┌────────────┐                         ┌────────────┐
        │ Driver App │  GPS ping / 4s          │ Rider App  │ request ride / track
        └─────┬──────┘                         └─────┬──────┘
              │ WebSocket/gRPC                        │ HTTPS + WS
              ▼                                       ▼
   ┌────────────────────────┐            ┌────────────────────────┐
   │  Location Gateway       │            │  API Gateway / LB       │
   │  (sticky, per-region)   │            │  (GeoDNS → nearest rgn) │
   └───────────┬────────────┘            └───────────┬────────────┘
               │ pings                                │ request
               ▼                                      ▼
   ┌────────────────────────┐            ┌────────────────────────┐
   │ Location Ingest Service │            │   Trip / Ride Service   │
   │ - validate, throttle    │            │ - create trip (FSM)     │
   │ - update geo index      │            │ - call pricing          │
   └───────────┬────────────┘            └────────┬─────────┬──────┘
               │ upsert                            │ query   │ create
               ▼                                   │ nearby  │ trip
   ┌────────────────────────┐                      ▼         ▼
   │  In-Memory Geo Index    │◄────query────┐ ┌──────────┐ ┌──────────────┐
   │  (Redis Geo / H3, per   │              └─│ Matching │ │  Trip DB     │
   │   city, in RAM)         │────candidates──▶│ /Dispatch│ │ (sharded by  │
   └───────────┬────────────┘                 │  Service │ │  city)       │
               │ raw pings (async)             └────┬─────┘ └──────────────┘
               ▼                                    │ offer (WS push)
   ┌────────────────────────┐                       ▼
   │ Kafka  (ping firehose)  │              ┌────────────────┐
   └───────────┬────────────┘              │ Notification /  │
               ▼                            │ Push (offers,   │
   ┌────────────────────────┐              │ APNs/FCM)       │
   │ Time-series / S3 +      │              └────────────────┘
   │ ClickHouse (history,    │
   │ ETA model, analytics)   │     ┌─────────────────┐   ┌──────────────┐
   └────────────────────────┘     │ Pricing/Surge   │   │ Payment Svc  │
                                   │ Service (per    │   │ + Ledger     │──▶ PSP
                                   │  geo-cell)      │   │ (PCI)        │
                                   └─────────────────┘   └──────────────┘
```

### Component walkthrough (request flow)
1. **Location gateway** holds the persistent driver streams (sticky per region) and forwards each ping to the **location ingest service**, which validates it and **upserts the driver's position into the in-memory geo index** (moving them between cells if needed). The raw ping is also fire-and-forgotten onto **Kafka** for history/analytics — never on the hot write path.
2. A **rider request** hits the **API gateway** (GeoDNS routes to the nearest region) → the **trip service** creates a trip row in `matching` state and asks the **pricing service** for a fare (including the current surge for that geo-cell).
3. The **matching/dispatch service** queries the geo index for available drivers near the pickup, ranks them (by ETA over the road network, not straight-line distance), and **offers** the top candidate via a push over their WebSocket. On accept, it atomically flips the driver to `assigned` and the trip to `matched`; on decline/timeout it re-offers the next candidate.
4. The **rider's tracking stream** receives the match and then the driver's live position (relayed from the geo index) until pickup, through the trip, to completion.
5. On **complete**, the trip service finalizes fare (actual distance/time), and the **payment service** captures the charge and writes double-entry **ledger** records via the PSP — idempotently.
6. **Pricing/surge** continuously reads supply (drivers available per cell) vs demand (open requests per cell) and updates a surge multiplier per geo-cell.

All app services are **stateless and horizontally scaled**; the only stateful pieces are the geo index (in RAM, partitioned), the trip/payment DBs (sharded), and the connection gateways (sticky sessions).

---

## 6. Deep Dives

### 6.1 Geospatial indexing — how do we find "drivers near me" in milliseconds?
A naive `SELECT ... WHERE distance(driver, pickup) < r` over 5M rows is hopeless at 7.5K QPS. We need a **spatial index**, in memory. Three common encodings:

**(a) Geohash** — interleave lat/lng bits into a base32 string; a prefix defines a rectangular cell. "Near me" = drivers sharing my prefix (plus the 8 neighbor cells, because the target can sit at a cell edge).
- Pros: dead simple, string-prefix lookups, works in plain Redis.
- Cons: rectangular cells distort badly near the poles; cell size jumps in discrete steps; edge/neighbor handling is fiddly.

**(b) QuadTree** — recursively subdivide space; a node splits into 4 when it holds too many drivers.
- Pros: **adapts to density** — dense downtown cells subdivide finely, empty highway cells stay coarse, keeping per-cell driver counts bounded.
- Cons: must rebalance as drivers move; more complex than a flat hash; rebuild cost.

**(c) Google S2 / Uber H3 (hexagonal)** — map the sphere to hierarchical cells. **H3 uses hexagons**, so every neighbor is equidistant (no diagonal-vs-orthogonal distortion), which makes radius and "ring" queries clean. This is what Uber actually uses.
- Pros: uniform neighbor distance, multiple resolutions, great for surge cells too.
- Cons: library dependency; hex math is less intuitive.

```
Query pattern (H3):
  origin_cell = h3.latLngToCell(pickup_lat, pickup_lng, res=8)   # ~0.7 km hexes
  search_cells = h3.gridDisk(origin_cell, k=2)                   # origin + 2 rings
  candidates = union(index[cell] for cell in search_cells where status==available)
  rank candidates by road-network ETA, return top N
```
**Decision: H3 (or S2) for the index, QuadTree if density is wildly uneven and we want self-balancing.** Whatever the encoding, the index lives **in memory, partitioned by city**, and is updated on every ping. Because a driver moves cells only occasionally, most pings are a cheap in-place position update, not a re-bucket.

### 6.2 Matching / dispatch — nearest, ETA-based, or batch optimization?
**Greedy nearest** (offer the closest available driver) is simple but locally myopic: it can leave a slightly-farther rider unserved while a perfect driver is "wasted" on someone the next-nearest driver could have taken.

**ETA-based ranking** beats straight-line distance: a driver 200 m away across a river with no bridge has a worse ETA than one 1 km away on the same road. So we rank candidates by **predicted road-network travel time** (from the ETA service / routing engine), not Euclidean distance.

**Batch matching** (Uber's approach in dense markets): instead of matching each request the instant it arrives, **accumulate requests over a short window (1–2 s) per geo-cell** and solve a global assignment (a min-cost bipartite matching / Hungarian-style optimization) over the batch.
```
Every ~2s, per cell:
  R = open requests,  D = available drivers
  cost[i][j] = ETA(driver j → rider i)  (+ penalties for surge fairness, pool detours)
  assignment = min_cost_matching(R, D)    # globally minimizes total wait
  emit offers
```
- **Why batch wins**: globally minimizes total pickup time and increases match rate vs first-come greedy; lets us optimize for pool-ride detours and driver utilization.
- **Cost**: adds up to ~2 s latency (well within the 2 s SLO) and complexity. In sparse markets we fall back to greedy nearest (no benefit to batching when there's one driver).

**Concurrency / at-most-once offer**: a driver must never be offered to two riders simultaneously. We enforce this with an **atomic compare-and-set on driver state** (`available → offered`) in the index or a fast lock (Redis `SET NX` with a short TTL = the offer window). If the driver declines/times out, the lock releases and they're offered to the next rider. This is the one place we choose **strong consistency over availability**.

### 6.3 Surge pricing — turning supply/demand into a multiplier
Surge is a control loop that **raises price where demand exceeds supply**, which (a) rations scarce supply to those who value it most and (b) lures more drivers into the area.
```
Per H3 surge cell, on a rolling window:
  demand = open_requests + recent_request_rate
  supply = available_drivers
  ratio  = demand / max(supply, 1)
  surge  = clamp( f(ratio), 1.0, 5.0 )      # e.g. piecewise / sigmoid, capped
```
- **Computed per geo-cell** (coarser than the matching cell — surge varies block-to-block but we smooth it) and updated every few seconds from the same supply/demand signals matching already tracks.
- **Locked at request time**: the rider sees and accepts a surge multiplier *before* requesting; the fare estimate freezes that multiplier for a short window so the price can't jump mid-confirmation.
- **Smoothing & caps** prevent jarring oscillation (price flapping 1.0↔2.0 every few seconds) and PR-disastrous spikes during emergencies (regulatory caps).
- **Trade-off**: surge is economically efficient and self-balancing but reputationally sensitive. Alternatives like queueing or driver incentives sidestep public-price backlash at the cost of worse market clearing.

### 6.4 Trip state machine & exactly-once payment
A trip is a **finite state machine** persisted transactionally; money moves only on legal transitions.
```
requested → matching → matched → en_route → arrived → in_progress → completed → paid
        ↘ cancelled (with fee rules depending on which state we cancelled from)
```
- Each transition is an **idempotent** operation keyed by `(trip_id, transition)`. A driver app retrying `complete` over a flaky network must not charge twice — the idempotency key makes the second call a no-op returning the same result.
- **Payment capture** writes **double-entry ledger** rows in one transaction (debit rider, credit driver + platform fee + tax), each tagged with a unique idempotency key the PSP also honors. If the capture fails, the trip sits in `completed/payment_pending` and a retry worker reconciles — we never lose the obligation.
- **Why a state machine + ledger**: it makes illegal transitions impossible (can't `complete` a trip that was never `started`), gives a full audit trail for disputes, and isolates the money domain (PCI scope) from everything else.

### 6.5 Real-time location delivery at the ingest firehose (1M+ writes/sec)
The 1.25M pings/sec is the system's hardest sustained load. How we keep it cheap:
- **Persistent connections, not requests**: a WebSocket/gRPC stream per driver avoids TLS+TCP handshake per ping. Gateways are sticky and hold ~100K connections each → ~50 gateway nodes for 5M drivers.
- **Hot path does almost nothing durable**: ingest updates the **in-memory** geo index (a single overwrite of `driver_id → {lat,lng,ts}`) and pushes the raw ping to **Kafka** asynchronously. No synchronous DB write on the ping path.
- **Region-local**: pings for NYC drivers terminate on NYC-region gateways and update the NYC index shard — no cross-region traffic.
- **Adaptive ping rate**: idle/stationary drivers ping less often (every 8–10 s); drivers on an active trip ping faster (2 s) for smooth rider tracking. This alone can halve the firehose.
- **Backpressure**: if a region is overloaded, the gateway tells clients to reduce ping frequency rather than dropping the index update — stale-by-a-few-seconds positions are acceptable.

---

## 7. Scaling, Bottlenecks & Failure Handling

**What breaks first?** Order of stress: (1) the **location ingest firehose** (raw write QPS), (2) the **per-region geo index / dispatch CPU** during local surge events, (3) **gateway connection limits**, (4) trip DB write hotspots in a booming city, (5) payment/PSP throughput.

- **Geo-partition by city/region** is the master scaling lever. Each region runs its own dispatch fleet, geo index, and trip DB shard. A spike in Lagos doesn't touch São Paulo's capacity. New cities = new partitions, near-linear scaling.
- **Stateless services autoscale horizontally**: location ingest, trip, pricing, and matching services sit behind autoscaling groups; add nodes for throughput.
- **Geo index scaling**: shard each city's index further by sub-region cells when one city outgrows a node; replicate hot cells. The index is rebuildable, so we can lose and reconstruct a replica from the next ping round in seconds.
- **Trip / ledger scaling**: NewSQL (CockroachDB/Spanner/Citus) shards by `city_id` and adds nodes; because trip and payment work is region-local, there are no cross-shard transactions on the hot path.
- **Connection gateways**: scale by adding nodes (each ~100K conns); a consistent-hash ring maps drivers to gateways, and reconnect logic re-homes a driver if a gateway dies.
- **Failure modes & mitigations**:
  - *Geo-index node dies*: drivers' positions vanish for that shard until the next ping (≤4 s) repopulates a standby replica — brief, self-healing, no durable loss.
  - *Dispatch overloaded during surge*: shed load by widening the match window slightly and capping candidate fan-out; never block the ingest path.
  - *Trip DB partition down*: that city can't accept new rides (fail fast with a clear "no cars available") while in-flight trips continue from cached state; failover to a replica.
  - *Payment/PSP outage*: complete the trip and queue the charge; a reconciliation worker captures later — the rider isn't blocked, the obligation isn't lost.
  - *Driver app loses connectivity mid-trip*: buffer pings on-device and replay on reconnect; trip state is server-authoritative so it survives the gap.
- **Thundering herd on a big event** (concert lets out): surge throttles demand, batch matching absorbs the request burst over short windows, and pre-positioning incentives (computed from historical demand) move supply in beforehand.
- **DR**: trip and ledger data replicate cross-region with backups; the geo index needs no DR (ephemeral). GeoDNS fails a region's traffic over to a neighbor at the cost of higher pickup ETAs.

---

## 8. Trade-offs & Alternatives

- **In-memory geo index vs PostGIS/database geo queries**: an in-memory H3/QuadTree index gives sub-millisecond radius queries at 7.5K QPS over data that mutates every 4 s; a database `ST_DWithin` can't sustain that and adds disk latency. **Chosen: in-memory**, accepting that it's ephemeral (which is fine — positions are reconstructed continuously).
- **Greedy nearest vs batch matching**: greedy is simple and instant; batch optimization raises match rate and cuts total wait but adds ~2 s and complexity. **Chosen: batch in dense markets, greedy fallback in sparse ones.**
- **Straight-line vs ETA ranking**: Euclidean distance is cheap but wrong near rivers/highways; ETA over the road graph is what riders feel. **Chosen: ETA-based**, with straight-line as a coarse pre-filter to shrink the candidate set.
- **Strong vs eventual consistency**: location reads are eventually consistent (stale-by-seconds is fine); driver-offer state and trip/payment transitions are strongly consistent (no double-offer, no double-charge). **CAP**: the matching/payment path is **CP** (reject/retry rather than risk a double assignment or charge); the location-tracking path is **AP** (show a slightly stale car rather than nothing).
- **Surge pricing vs queueing/incentives**: surge clears the market efficiently but is reputationally costly; pure queueing avoids price backlash but serves fewer riders and frustrates drivers. **Chosen: capped, smoothed surge** as the primary lever plus driver incentives for pre-positioning.
- **One global system vs geo-partitioned**: a single global matcher is simpler conceptually but ignores that demand is local and creates a global SPOF/bottleneck. **Chosen: geo-partition by city** — the foundation of the whole design.

**At 10x scale**: push surge/ETA computation to **regional edge clusters**, shard each megacity's index across many nodes by sub-region H3 cells, and move ETA prediction to pre-computed road-segment travel-time tables refreshed in near-real-time.

**Under tight budget / single city (a startup MVP)**: collapse to one region — PostGIS or Redis Geo for the index, a single Postgres for trips+payments, greedy nearest matching, flat pricing. It'll comfortably serve one city; you add geo-partitioning, batch matching, and surge only as you expand.

---

## Interview Q&A by Level

### 🟢 Basic
**Q. [Theory] Why can't we just store driver locations in a SQL table and query `WHERE distance < radius`?**
A. At 5M drivers updating every 4 s (~1.25M writes/sec) and thousands of "near me" reads/sec, a disk-backed table doing a full or even indexed distance scan can't keep up — each query would touch huge numbers of rows and pay disk latency. We keep an **in-memory geospatial index** (Redis Geo / H3 / QuadTree) instead: positions are ephemeral and rebuildable from the next ping, so we trade durability we don't need for the speed we do.

**Q. [Theory] Why use a persistent connection for driver location instead of a normal HTTP POST every few seconds?**
A. Opening a fresh TLS+TCP connection 5M times every 4 s would burn enormous CPU on handshakes and dominate latency. A persistent **WebSocket/gRPC stream** per driver amortizes the handshake once, lets the server push trip offers back down the same channel, and keeps each ping to a tiny frame.

**Q. [Practical] What's the high-level flow from "rider taps request" to "driver accepts"?**
A. The request creates a trip in `matching` state and gets a fare estimate from pricing. The matching service queries the in-memory geo index for available drivers near the pickup, ranks them by road-network ETA, and pushes an offer to the best candidate over their stream. On accept, the driver atomically flips to `assigned` and the trip to `matched`; the rider's tracking stream gets the match. Decline/timeout re-offers the next candidate.

### 🟡 Intermediate
**Q. [Theory] What is geo-partitioning and why is it the backbone of this design?**
A. Demand is intensely local — a NYC rider only ever matches NYC drivers. So we shard the entire system (geo index, dispatch fleet, trip DB) **by city/region**. This bounds each matcher's working set to one city (fits in RAM), eliminates a global bottleneck/SPOF, isolates failures (a surge in one city doesn't strain another), and scales near-linearly as we add cities.

**Q. [Theory] Compare Geohash, QuadTree, and H3 for the location index.**
A. **Geohash** is a simple base32 prefix scheme — easy but with rectangular cells that distort near the poles and awkward edge/neighbor handling. **QuadTree** subdivides by density, so dense downtowns get fine cells and empty areas stay coarse, but it needs rebalancing as drivers move. **H3 (hexagons)** gives uniform neighbor distance and clean ring queries at multiple resolutions — which is why Uber uses it; the cost is a library dependency and less intuitive hex math.

**Q. [Practical] How do you rank candidate drivers — and why not just pick the closest?**
A. Rank by **predicted road-network ETA**, not straight-line distance. A car 200 m away across a river with no bridge is farther in travel time than one 1 km down the same road. We use straight-line distance only as a cheap pre-filter to shrink the candidate set, then score the survivors by ETA from the routing/ETA service.

**Q. [Practical] How does surge pricing get computed and applied?**
A. Per geo-cell, on a rolling window, we compute `demand/supply` from the same signals matching already tracks (open requests vs available drivers), map that ratio to a multiplier, and **clamp/smooth** it (e.g. 1.0–5.0) to avoid flapping and runaway spikes. The multiplier is **locked at request time** so the rider accepts a known price; it can't jump mid-confirmation.

### 🟠 Advanced
**Q. [Coding] Implement the core "find available drivers near a pickup" query using an H3-style index. Show the data structure and the lookup.**
A. Keep a per-city index mapping H3 cell → set of drivers, plus a reverse map so each ping is a cheap move:
```python
# index: cell_id -> {driver_id: (lat, lng, ts, status)}
# driver_cell: driver_id -> cell_id
RES = 8  # ~0.7 km hexes

def on_ping(driver_id, lat, lng, ts, status):
    cell = h3.latlng_to_cell(lat, lng, RES)
    old = driver_cell.get(driver_id)
    if old is not None and old != cell:
        index[old].pop(driver_id, None)        # move out of old cell
    index.setdefault(cell, {})[driver_id] = (lat, lng, ts, status)
    driver_cell[driver_id] = cell

def nearby_available(pickup_lat, pickup_lng, k=2, limit=10):
    origin = h3.latlng_to_cell(pickup_lat, pickup_lng, RES)
    cells = h3.grid_disk(origin, k)             # origin + k rings of hexes
    cands = []
    for c in cells:
        for did, (lat, lng, ts, status) in index.get(c, {}).items():
            if status == "available":
                cands.append((did, lat, lng))
    # coarse pre-sort by haversine, then the matcher re-ranks the top by road ETA
    cands.sort(key=lambda d: haversine(pickup_lat, pickup_lng, d[1], d[2]))
    return cands[:limit]
```
Most pings are an in-place dict update (O(1)); only an occasional cell change touches two cells. The radius query scans a bounded number of hexes (`k` rings), so its cost is independent of total fleet size.

**Q. [Practical] How do you guarantee a driver is never offered to two riders at the same time?**
A. Treat the driver's availability as a lock. Before offering, do an **atomic compare-and-set** `available → offered` (e.g. Redis `SET driver:{id} offered NX EX 15`). Only the request that wins the CAS may offer; the offer holds for the window (15 s). On accept we transition to `assigned`; on decline/timeout the key expires and the next request can claim them. This is a deliberate **CP** choice — we'd rather reject/retry than risk a double assignment.

**Q. [Coding] How do you make trip completion and charging idempotent so a retried request never double-charges?**
A. Key every money-moving transition with a stable idempotency key and enforce uniqueness in the ledger:
```sql
-- complete is retried by the flaky driver app; second call must be a no-op
BEGIN;
  INSERT INTO ledger_entries (entry_id, trip_id, type, amount_cents, idempotency_key)
  VALUES (gen_uuid(), :trip_id, 'charge', :amount, :trip_id || ':charge')
  ON CONFLICT (idempotency_key) DO NOTHING;     -- duplicate => no-op
  UPDATE trips SET status='completed', completed_at=now()
  WHERE trip_id=:trip_id AND status='in_progress';  -- guarded transition
COMMIT;
```
The unique `idempotency_key` makes the second insert a no-op, and the status-guarded `UPDATE` makes the transition legal only once. The PSP capture carries the same key so the payment processor also dedupes.

### 🔴 Expert
**Q. [Theory] Explain batch matching and when it beats greedy nearest, with the trade-offs.**
A. Greedy matches each request to the closest driver the instant it arrives — locally fine, globally suboptimal (it can "waste" an ideal driver on one rider and strand another). **Batch matching** accumulates requests per cell over a short window (~2 s) and solves a **min-cost bipartite assignment** (Hungarian-style) where cost is ETA (plus pool/fairness penalties), globally minimizing total wait and raising match rate. It wins in **dense markets** with many simultaneous requests; it costs ~2 s of latency and real algorithmic complexity, so in **sparse markets** (one driver around) we fall back to greedy nearest where batching adds nothing.

**Q. [Theory] Walk through your consistency model in CAP terms across the whole system.**
A. It's deliberately mixed. **Location tracking is AP** — showing a car 1–2 s stale is fine, so we favor availability and never block ingest on durability. **Matching, trip state, and payments are CP** — a driver-offer CAS, the trip state machine, and the double-entry ledger all reject/retry rather than risk a double-offer or double-charge. Geo-partitioning means most consistency is enforced within a single region's store, so we rarely pay for cross-region coordination on the hot path.

**Q. [Practical] Redesign the location pipeline for 10x scale (~12M+ pings/sec) and global low latency.**
A. Push everything to **regional edge clusters**: gateways, ingest, and the geo index all live in-region, so pings never cross oceans. Shard each megacity's index across many nodes by **sub-region H3 cells** and replicate hot cells. Use **adaptive ping rates** (idle drivers slow down, on-trip speed up) to cut the firehose at the source. Keep the raw-ping Kafka stream regional and only ship **aggregates** to a central analytics store. ETA prediction moves to pre-computed, continuously-refreshed road-segment travel-time tables so ranking stays O(1) per candidate even as fleets grow.

**Q. [Behavioral] Surge pricing once tripled fares during a city emergency and caused public outrage. As the engineer who owns pricing, how do you respond?**
A. First, **own the impact, not just the algorithm** — the system did what it was designed to do, but the outcome was unacceptable, and that's on us to fix. Short term: I'd push a **hard surge cap** (or disable surge) for the affected region immediately via a config flag, ideally one operations can flip without a deploy, and verify with stakeholders. Medium term: build **emergency detection** (news/government feeds, anomalous demand patterns) that auto-caps surge in declared emergencies, plus a clear audit log of why a cap fired. I'd bring legal/policy in early since several jurisdictions regulate this, write a blameless postmortem, and add a pre-launch review gate for pricing changes. The lesson I'd articulate to the team: an economically "correct" model can still be the wrong product decision, and pricing systems need guardrails and a human override, not just optimization.

---

*Key takeaway: a ride-sharing service is fundamentally a real-time geospatial matching system on top of a money-safe transactional core. The interesting engineering is keeping the live driver index in memory and partitioned by city, ranking by road-network ETA (often via batch optimization), clearing supply/demand with capped surge, and wrapping trips and payments in an idempotent state machine — choosing AP for location and CP for matching and money.*
