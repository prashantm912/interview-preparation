# Shortest Path Algorithms (Dijkstra, Bellman-Ford, Floyd-Warshall)

Shortest-path algorithms find the minimum-cost route between vertices in a graph. The right choice depends on edge weights (none / non-negative / negative), whether you need single-source or all-pairs results, and the graph's density. This guide covers BFS, 0-1 BFS, Dijkstra, Bellman-Ford, Floyd-Warshall, and a practical A* introduction.

[← Back to master index](../README.md) | [← DSA index](README.md)

---

## Concept & Intuition

A weighted graph `G = (V, E)` assigns a cost `w(u, v)` to each edge. The **single-source shortest path (SSSP)** problem computes `dist[v]` = minimum total weight from a fixed source `s` to every other vertex `v`. The **all-pairs shortest path (APSP)** problem computes it for every ordered pair.

Every correct algorithm rests on one invariant — **edge relaxation**:

```
if dist[u] + w(u, v) < dist[v]:
    dist[v] = dist[u] + w(u, v)
    parent[v] = u   // for path reconstruction
```

Relaxation never makes a distance worse, and the final `dist[]` is correct once no edge can be relaxed further. The algorithms differ only in the *order* in which they relax edges and how many times.

**The toolbox, by edge type:**

| Edge weights | Best tool | Why |
|---|---|---|
| Unweighted (all = 1) | BFS | Layer expansion = distance |
| Only 0 and 1 | 0-1 BFS (deque) | No log factor needed |
| Non-negative | Dijkstra (min-heap) | Greedy "closest first" is provably optimal |
| Any (incl. negative) | Bellman-Ford | `V−1` global relaxation rounds |
| Any, all-pairs, small V | Floyd-Warshall | `O(V³)` DP over intermediate vertices |

**Why Dijkstra fails on negative edges.** Dijkstra's greedy invariant is: once a vertex is popped from the priority queue, its distance is final. This holds only if every remaining edge is non-negative — extending a path can never decrease its cost. A negative edge breaks this: a vertex popped early as "settled" might later be reached more cheaply through a negative detour, but Dijkstra never revisits it.

```
        (10)
   A ─────────► C        Dijkstra settles C at 10,
   │            ▲        then discovers A→B→C = 1 + (-100) = -99.
 (1)│           │(-100)  Too late: C is already locked. Wrong answer.
   ▼            │
   B ───────────┘
```

**Invariants to remember:**
- BFS / Dijkstra: a popped/visited vertex's distance is final.
- Bellman-Ford: after `k` rounds, `dist[v]` is correct for all shortest paths using `≤ k` edges. A relaxation possible in round `V` proves a negative cycle.
- Floyd-Warshall: after processing intermediate vertex `k`, `dist[i][j]` uses only intermediates from `{0..k}`.

---

## Complexity Cheat-Sheet

`V` = vertices, `E` = edges. Dijkstra times assume a binary heap.

| Algorithm | Time | Space | Negative edges? | Output |
|---|---|---|---|---|
| BFS (unweighted) | `O(V + E)` | `O(V)` | N/A | SSSP |
| 0-1 BFS (deque) | `O(V + E)` | `O(V)` | N/A (0/1 only) | SSSP |
| Dijkstra (binary heap) | `O((V + E) log V)` | `O(V + E)` | No | SSSP |
| Dijkstra (Fibonacci heap) | `O(E + V log V)` | `O(V + E)` | No | SSSP |
| Bellman-Ford | `O(V · E)` | `O(V)` | Yes (+ detects neg cycle) | SSSP |
| SPFA (BF queue variant) | `O(V · E)` worst, fast avg | `O(V)` | Yes | SSSP |
| Floyd-Warshall | `O(V³)` | `O(V²)` | Yes (no neg cycle) | APSP |
| Johnson's | `O(V·E + V² log V)` | `O(V²)` | Yes | APSP (sparse) |
| A* | `O(E)` best, exp worst | `O(V)` | No | s→t with heuristic |

**Density rule of thumb:** for dense graphs (`E ≈ V²`), Dijkstra with a heap is `O(V² log V)` while a simple `O(V²)` array-based Dijkstra or `O(V³)` Floyd-Warshall may be competitive. For sparse graphs, the heap version wins.

---

## Patterns & Recognition

Reach for a shortest-path algorithm when you see these signals:

- **"Minimum cost / time / effort to get from X to Y"** on a grid or explicit graph → SSSP. Weighted? Dijkstra. Unweighted? BFS.
- **"Minimum number of steps / moves / transformations"** → unweighted BFS (each step costs 1). Word Ladder, knight moves, rotting oranges.
- **Grid where moves cost 0 or 1** (e.g., walls you may break, doors) → 0-1 BFS with a deque.
- **Edges can be negative** (refunds, profit, currency arbitrage) → Bellman-Ford. If you must *detect* a profitable cycle → run an extra round.
- **"Shortest path between every pair"** or repeated queries on a small graph (`V ≤ ~400`) → Floyd-Warshall.
- **"At most K edges / stops / transfers"** → Bellman-Ford-style DP bounded by K rounds (Cheapest Flights), or BFS by layers.
- **Single target with a good distance estimate** (geographic, Manhattan) → A* to prune the search.
- **Probabilities / multiplicative weights** (max success probability) → transform with `−log`, then Dijkstra, or run a max-heap variant.
- **Modeling trick:** treat states as nodes. "Cheapest path where you may use ≤ k coupons" → node = `(city, couponsUsed)`.

If the problem mentions DAGs specifically, a single topological-sort pass relaxes edges in `O(V + E)` and handles negative weights too.

---

## Coding Problems

### Problem 1: Shortest Path in Binary Matrix (BFS, unweighted)

LeetCode 1091. Given an `n x n` binary matrix `grid`, return the length of the shortest **clear path** from top-left `(0,0)` to bottom-right `(n-1,n-1)`, moving in 8 directions through cells with value `0`. Return `-1` if no path. Constraints: `1 ≤ n ≤ 100`.

**Approach.** All moves cost 1, so this is an unweighted shortest path → BFS. Each BFS layer is one more step. Brute-force DFS would explore exponentially many paths and not guarantee the shortest; BFS visits each cell once.

```java
import java.util.*;

class Solution {
    public int shortestPathBinaryMatrix(int[][] grid) {
        int n = grid.length;
        if (grid[0][0] != 0 || grid[n - 1][n - 1] != 0) return -1;

        int[][] dirs = {{-1,-1},{-1,0},{-1,1},{0,-1},{0,1},{1,-1},{1,0},{1,1}};
        Queue<int[]> q = new ArrayDeque<>();
        q.offer(new int[]{0, 0});
        grid[0][0] = 1;               // mark visited
        int path = 1;

        while (!q.isEmpty()) {
            int sz = q.size();
            for (int i = 0; i < sz; i++) {
                int[] cell = q.poll();
                int r = cell[0], c = cell[1];
                if (r == n - 1 && c == n - 1) return path;
                for (int[] d : dirs) {
                    int nr = r + d[0], nc = c + d[1];
                    if (nr >= 0 && nr < n && nc >= 0 && nc < n && grid[nr][nc] == 0) {
                        grid[nr][nc] = 1;   // mark on enqueue to avoid dup
                        q.offer(new int[]{nr, nc});
                    }
                }
            }
            path++;
        }
        return -1;
    }
}
```

**Dry-run** on `[[0,1],[1,0]]`: start `(0,0)`, path=1. Neighbors include `(1,1)` (diagonal, value 0) → enqueue. Next layer path=2, poll `(1,1)` which is the target → return 2. Correct.

**Time:** `O(n²)` — each cell enqueued once. **Space:** `O(n²)` for the queue.

**Follow-ups:** 4-directional version (LC 1730); track the actual path via a parent map; bidirectional BFS to halve the explored frontier on large grids.

---

### Problem 2: 01 Matrix (Multi-source BFS)

LeetCode 542. Given a matrix of `0`s and `1`s, return a matrix where each cell holds the distance to the nearest `0`. Constraints: up to `10⁴ × 10⁴` cells.

**Approach.** Naive: BFS from every `1` → `O((mn)²)`. Optimal: **multi-source BFS** — seed the queue with *all* zeros at distance 0 and expand outward simultaneously. The first time a `1` is reached, it is via the nearest zero.

```java
import java.util.*;

class Solution {
    public int[][] updateMatrix(int[][] mat) {
        int m = mat.length, n = mat[0].length;
        int[][] dist = new int[m][n];
        Queue<int[]> q = new ArrayDeque<>();
        for (int[] row : dist) Arrays.fill(row, -1);  // -1 = unvisited

        for (int i = 0; i < m; i++)
            for (int j = 0; j < n; j++)
                if (mat[i][j] == 0) { dist[i][j] = 0; q.offer(new int[]{i, j}); }

        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        while (!q.isEmpty()) {
            int[] c = q.poll();
            for (int[] d : dirs) {
                int nr = c[0] + d[0], nc = c[1] + d[1];
                if (nr >= 0 && nr < m && nc >= 0 && nc < n && dist[nr][nc] == -1) {
                    dist[nr][nc] = dist[c[0]][c[1]] + 1;
                    q.offer(new int[]{nr, nc});
                }
            }
        }
        return dist;
    }
}
```

**Dry-run** on `[[0,0,0],[0,1,0],[0,0,0]]`: all zeros enqueued at 0. The center `1` is reached from any adjacent 0 at distance 1. Result center = 1.

**Time:** `O(mn)`. **Space:** `O(mn)`.

**Follow-ups:** Walls and Gates (LC 286, same multi-source idea); replace BFS with DP two-pass (top-left then bottom-right) for `O(1)` extra space.

---

### Problem 3: Network Delay Time (Dijkstra, single source)

LeetCode 743. `times[i] = [u, v, w]` is a directed edge with travel time `w`. Starting from node `k`, return the time for all `n` nodes to receive the signal, or `-1` if some node is unreachable. Constraints: `1 ≤ n ≤ 100`, `1 ≤ times.length ≤ 6000`, `1 ≤ w ≤ 100`.

**Approach.** Non-negative weights, single source, "time for the *last* node" = the maximum of all shortest distances → classic Dijkstra. Build an adjacency list, run a min-heap Dijkstra, answer is `max(dist)` if all reachable.

```java
import java.util.*;

class Solution {
    public int networkDelayTime(int[][] times, int n, int k) {
        List<int[]>[] adj = new List[n + 1];
        for (int i = 1; i <= n; i++) adj[i] = new ArrayList<>();
        for (int[] t : times) adj[t[0]].add(new int[]{t[1], t[2]});

        int[] dist = new int[n + 1];
        Arrays.fill(dist, Integer.MAX_VALUE);
        dist[k] = 0;

        // min-heap ordered by current distance
        PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> a[1] - b[1]);
        pq.offer(new int[]{k, 0});

        while (!pq.isEmpty()) {
            int[] top = pq.poll();
            int node = top[0], d = top[1];
            if (d > dist[node]) continue;          // stale entry, skip
            for (int[] e : adj[node]) {
                int nei = e[0], nd = d + e[1];
                if (nd < dist[nei]) {              // relax
                    dist[nei] = nd;
                    pq.offer(new int[]{nei, nd});
                }
            }
        }

        int ans = 0;
        for (int i = 1; i <= n; i++) {
            if (dist[i] == Integer.MAX_VALUE) return -1;
            ans = Math.max(ans, dist[i]);
        }
        return ans;
    }
}
```

**Dry-run** with `times=[[2,1,1],[2,3,1],[3,4,1]]`, `n=4`, `k=2`: pop `2`(0) → relax `1`→1, `3`→1. Pop `1`(1) no out-edges. Pop `3`(1) → relax `4`→2. Pop `4`(2). `dist=[_,1,0,1,2]`, max = 2.

**Key detail:** the `if (d > dist[node]) continue;` line is the lazy-deletion trick — we never decrease-key, we just push duplicates and discard stale pops. This is why heap size can reach `O(E)`.

**Time:** `O(E log V)`. **Space:** `O(V + E)`.

**Follow-ups:** return the actual slowest path; what if edges can be re-added dynamically (use a `decrease-key` structure or rebuild); negative weights would force Bellman-Ford instead.

---

### Problem 4: Path With Minimum Effort (Dijkstra on a grid, custom cost)

LeetCode 1631. In a `rows x cols` height grid, a route's **effort** is the maximum absolute height difference between consecutive cells. Find the minimum effort path from top-left to bottom-right. Constraints: up to `100 x 100`, heights up to `10⁶`.

**Approach.** The cost of a path is not a sum but a **max** of edge differences — a minimax path. Dijkstra still works because the "distance" (current max-diff to reach a cell) is monotonic and we always settle the smallest-effort frontier first. Relaxation uses `max(currentEffort, |Δheight|)` instead of addition.

```java
import java.util.*;

class Solution {
    public int minimumEffortPath(int[][] h) {
        int m = h.length, n = h[0].length;
        int[][] effort = new int[m][n];
        for (int[] row : effort) Arrays.fill(row, Integer.MAX_VALUE);
        effort[0][0] = 0;

        // {effort, row, col}
        PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> a[0] - b[0]);
        pq.offer(new int[]{0, 0, 0});
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};

        while (!pq.isEmpty()) {
            int[] cur = pq.poll();
            int e = cur[0], r = cur[1], c = cur[2];
            if (r == m - 1 && c == n - 1) return e;   // first settle = answer
            if (e > effort[r][c]) continue;
            for (int[] d : dirs) {
                int nr = r + d[0], nc = c + d[1];
                if (nr >= 0 && nr < m && nc >= 0 && nc < n) {
                    int ne = Math.max(e, Math.abs(h[nr][nc] - h[r][c]));
                    if (ne < effort[nr][nc]) {
                        effort[nr][nc] = ne;
                        pq.offer(new int[]{ne, nr, nc});
                    }
                }
            }
        }
        return 0;
    }
}
```

**Dry-run** on `[[1,2,2],[3,8,2],[5,3,5]]`: the path `1→2→2→2→5` (top row then down right column) yields max diffs of 1,0,0,3 → effort 2, which Dijkstra returns as minimal versus the steeper `1→3→5→3→5` route.

**Time:** `O(mn log(mn))`. **Space:** `O(mn)`.

**Follow-ups:** Swim in Rising Water (LC 778, same minimax pattern); binary search on the answer + BFS/Union-Find as an alternative; the closely related "maximize minimum" path.

---

### Problem 5: Cheapest Flights Within K Stops (Bellman-Ford, bounded edges)

LeetCode 787. Given `n` cities and `flights[i] = [from, to, price]`, find the cheapest price from `src` to `dst` using **at most `k` stops** (i.e., `k+1` edges). Return `-1` if impossible. Constraints: `1 ≤ n ≤ 100`, edges up to `n*(n-1)/2`.

**Approach.** Plain Dijkstra fails because the cheapest route may exceed the stop limit, and a more expensive route may be valid — the "settled" invariant breaks once a hop constraint is added. The clean fix is **Bellman-Ford run exactly `k+1` times**: after `i` rounds, `dist[v]` is the cheapest cost using at most `i` edges. Critically, each round must relax from a **snapshot** of the previous round so a single round never uses more than one new edge.

```java
import java.util.*;

class Solution {
    public int findCheapestPrice(int n, int[][] flights, int src, int dst, int k) {
        int[] dist = new int[n];
        Arrays.fill(dist, Integer.MAX_VALUE);
        dist[src] = 0;

        for (int i = 0; i <= k; i++) {              // k+1 edges allowed
            int[] snapshot = dist.clone();          // freeze previous round
            for (int[] f : flights) {
                int u = f[0], v = f[1], w = f[2];
                if (snapshot[u] != Integer.MAX_VALUE && snapshot[u] + w < dist[v]) {
                    dist[v] = snapshot[u] + w;
                }
            }
        }
        return dist[dst] == Integer.MAX_VALUE ? -1 : dist[dst];
    }
}
```

**Dry-run** with `n=3`, `flights=[[0,1,100],[1,2,100],[0,2,500]]`, `src=0`, `dst=2`, `k=1`. Round 0 (≤1 edge): `dist[1]=100`, `dist[2]=500`. Round 1 (≤2 edges) from the snapshot `[0,100,500]`: `0→1` still 100, `1→2` gives `100+100=200 < 500` → `dist[2]=200`. With `k=0`, only round 0 runs and the answer is 500. Matches expected output.

**Why the snapshot matters:** without `clone()`, within one round you could chain `0→1→2` in the same iteration, effectively using 2 edges when only 1 was budgeted, corrupting the hop count.

**Time:** `O(k · E)`. **Space:** `O(n)`.

**Follow-ups:** solve with a `(node, stopsUsed)` state in Dijkstra; what if you also have a budget cap; multi-criteria (price *and* time) shortest path.

---

### Problem 6: Find the City With the Smallest Number of Neighbors (Floyd-Warshall, all-pairs)

LeetCode 1334. With `n` cities and weighted bidirectional `edges`, find the city that can reach the fewest others within a `distanceThreshold`; on a tie return the city with the greatest index. Constraints: `2 ≤ n ≤ 100`.

**Approach.** We need the shortest distance between *every* pair → all-pairs. `n ≤ 100` makes `O(n³)` Floyd-Warshall ideal and simplest. After building the `dist` matrix, count reachable cities per node.

```java
import java.util.*;

class Solution {
    public int findTheCity(int n, int[][] edges, int distanceThreshold) {
        int INF = 1_000_000_000;
        int[][] dist = new int[n][n];
        for (int[] row : dist) Arrays.fill(row, INF);
        for (int i = 0; i < n; i++) dist[i][i] = 0;
        for (int[] e : edges) {
            dist[e[0]][e[1]] = e[2];
            dist[e[1]][e[0]] = e[2];
        }

        // Floyd-Warshall: k is the intermediate vertex
        for (int k = 0; k < n; k++)
            for (int i = 0; i < n; i++)
                for (int j = 0; j < n; j++)
                    if (dist[i][k] + dist[k][j] < dist[i][j])
                        dist[i][j] = dist[i][k] + dist[k][j];

        int best = -1, bestCount = n + 1;
        for (int i = 0; i < n; i++) {
            int count = 0;
            for (int j = 0; j < n; j++)
                if (i != j && dist[i][j] <= distanceThreshold) count++;
            if (count <= bestCount) {   // <= keeps the larger index on ties
                bestCount = count;
                best = i;
            }
        }
        return best;
    }
}
```

**Dry-run** with `n=4`, `edges=[[0,1,3],[1,2,1],[1,3,4],[2,3,1]]`, threshold 4. After FW, city 3 reaches {2 (dist 1), 1 (dist 4)} → 2 neighbors; city 0 reaches more. Tie cities resolve to the largest index. Answer: 3.

**Loop order is sacred:** `k` must be the outermost loop. Putting `i` or `j` outside breaks the DP invariant (you'd use intermediates not yet finalized).

**Time:** `O(n³)`. **Space:** `O(n²)`.

**Follow-ups:** detect a negative cycle (`dist[i][i] < 0` after FW); reconstruct paths with a `next[i][j]` matrix; Johnson's algorithm for sparse all-pairs with negative edges.

---

### Problem 7: Cheapest Path With Reconstruction (Dijkstra + parent tracking)

A common interview extension: return not just the cost but the **actual sequence of nodes** on the shortest path from `src` to `dst`. Given `n`, directed weighted `edges`, `src`, `dst`, output the path as a list (empty if unreachable).

**Approach.** Run Dijkstra while maintaining a `parent[]` array updated on every successful relaxation. After the run, walk `parent[]` backward from `dst` to `src` and reverse. This is the standard, reusable reconstruction pattern.

```java
import java.util.*;

class Solution {
    public List<Integer> shortestPath(int n, int[][] edges, int src, int dst) {
        List<int[]>[] adj = new List[n];
        for (int i = 0; i < n; i++) adj[i] = new ArrayList<>();
        for (int[] e : edges) adj[e[0]].add(new int[]{e[1], e[2]});

        long[] dist = new long[n];
        int[] parent = new int[n];
        Arrays.fill(dist, Long.MAX_VALUE);
        Arrays.fill(parent, -1);
        dist[src] = 0;

        PriorityQueue<long[]> pq = new PriorityQueue<>((a, b) -> Long.compare(a[1], b[1]));
        pq.offer(new long[]{src, 0});

        while (!pq.isEmpty()) {
            long[] top = pq.poll();
            int u = (int) top[0];
            long d = top[1];
            if (d > dist[u]) continue;
            if (u == dst) break;                    // early exit once dst settled
            for (int[] e : adj[u]) {
                int v = e[0]; long nd = d + e[1];
                if (nd < dist[v]) {
                    dist[v] = nd;
                    parent[v] = u;                  // record predecessor
                    pq.offer(new long[]{v, nd});
                }
            }
        }

        List<Integer> path = new ArrayList<>();
        if (dist[dst] == Long.MAX_VALUE) return path;   // unreachable
        for (int at = dst; at != -1; at = parent[at]) path.add(at);
        Collections.reverse(path);
        return path;
    }
}
```

**Dry-run** with edges `0→1(1), 1→3(1), 0→2(5), 2→3(1)`, `src=0`, `dst=3`: Dijkstra settles `3` via `0→1→3` (cost 2) before `0→2→3` (cost 6). `parent` = `{1:0, 3:1, 2:0}`. Backtrack from 3: `3 → 1 → 0`, reverse → `[0, 1, 3]`.

**Time:** `O(E log V)`. **Space:** `O(V + E)` plus `O(V)` for `parent`.

**Follow-ups:** count the *number* of shortest paths (LC 1976 — accumulate path counts during relaxation); return all shortest paths (store a list of predecessors per node); lexicographically smallest shortest path (tie-break parents).

---

### Problem 8: Number of Ways to Arrive at Destination (Senior-level: Dijkstra + DP counting)

LeetCode 1976 (Hard). A city has `n` intersections and bidirectional `roads[i] = [u, v, time]`. Return the number of distinct shortest-time paths from `0` to `n-1`, modulo `10⁹+7`. Constraints: `1 ≤ n ≤ 200`, up to `n*(n-1)/2` roads, times up to `10⁹`.

**Approach.** Two coupled subproblems: (1) find shortest times with Dijkstra, (2) count how many distinct paths achieve that time. Maintain `ways[v]`: when we **improve** `dist[v]`, copy `ways[u]`; when we find an **equal** distance via a different predecessor, **add** `ways[u]`. Use `long` for time accumulation (`10⁹` times up to ~`200` edges overflows `int`) and modular arithmetic for counts.

```java
import java.util.*;

class Solution {
    public int countPaths(int n, int[][] roads) {
        long MOD = 1_000_000_007L;
        List<long[]>[] adj = new List[n];
        for (int i = 0; i < n; i++) adj[i] = new ArrayList<>();
        for (int[] r : roads) {
            adj[r[0]].add(new long[]{r[1], r[2]});
            adj[r[1]].add(new long[]{r[0], r[2]});
        }

        long[] dist = new long[n];
        long[] ways = new long[n];
        Arrays.fill(dist, Long.MAX_VALUE);
        dist[0] = 0;
        ways[0] = 1;

        PriorityQueue<long[]> pq = new PriorityQueue<>((a, b) -> Long.compare(a[1], b[1]));
        pq.offer(new long[]{0, 0});

        while (!pq.isEmpty()) {
            long[] top = pq.poll();
            int u = (int) top[0];
            long d = top[1];
            if (d > dist[u]) continue;               // stale
            for (long[] e : adj[u]) {
                int v = (int) e[0];
                long nd = d + e[1];
                if (nd < dist[v]) {                  // strictly better path
                    dist[v] = nd;
                    ways[v] = ways[u];               // inherit count
                    pq.offer(new long[]{v, nd});
                } else if (nd == dist[v]) {          // another shortest path
                    ways[v] = (ways[v] + ways[u]) % MOD;
                }
            }
        }
        return (int) ways[n - 1];
    }
}
```

**Dry-run** (simplified): if `n-1` is reachable by two equal-cost routes through predecessors `a` and `b`, the first relaxation sets `ways = ways[a]`, and the later equal-distance relaxation does `ways += ways[b]`. The total counts both routes. The `else if` branch is the heart of the DP.

**Why `long`:** with times up to `10⁹` and ~`200` hops, a path cost can reach `~2 × 10¹¹`, far beyond `int`'s `2.1 × 10⁹`. Distances must use `long`; only the *counts* are reduced mod `10⁹+7`.

**Time:** `O(E log V)`. **Space:** `O(V + E)`.

**Follow-ups:** count paths within `k` stops (combine with Bellman-Ford layering); shortest path with the fewest edges among all minimum-cost paths; second-shortest path (track best and second-best distance per node).

---

## Interview Q&A by Level

### 🟢 Basic

**Q: When do you use BFS instead of Dijkstra?**
When every edge has the same weight (effectively unweighted). BFS gives the shortest path in `O(V+E)` with no priority queue. Using Dijkstra here just adds an unnecessary `log` factor.

**Q: What is edge relaxation?**
The operation `if dist[u] + w(u,v) < dist[v] then dist[v] = dist[u] + w(u,v)`. Every shortest-path algorithm is a different scheduling of relaxations.

**Q: Why can't Dijkstra handle negative weights?**
Its greedy invariant — a popped vertex's distance is final — relies on the fact that extending a path never lowers its cost. A negative edge can reduce the cost of an already-settled vertex, which Dijkstra never revisits.

**Q: What data structure powers an efficient Dijkstra?**
A min-priority queue (binary heap), keyed by tentative distance, giving `O((V+E) log V)`.

### 🟡 Intermediate

**Q: How does Bellman-Ford detect a negative cycle?**
After `V−1` rounds all shortest paths are final (a simple path has at most `V−1` edges). If any edge can still be relaxed in a `V`-th round, a vertex on or reachable from a negative cycle exists.

**Q: Why is the loop order `k, i, j` mandatory in Floyd-Warshall?**
`dist[i][j]` after iteration `k` must represent the shortest path using only intermediate vertices in `{0..k}`. Making `k` outermost guarantees `dist[i][k]` and `dist[k][j]` are already finalized for that `k` before they're combined.

**Q: What is the "stale entry" check `if (d > dist[node]) continue;`?**
Because we push duplicates instead of doing decrease-key, the heap may contain outdated larger distances for a node. We skip any pop whose stored distance exceeds the best known. This keeps correctness while allowing a simpler heap.

**Q: How does 0-1 BFS work and why is it faster?**
Use a double-ended queue. A 0-weight edge pushes to the **front**, a 1-weight edge to the **back**. This keeps the deque monotonic (like Dijkstra's settling order) but avoids the heap, giving `O(V+E)`.

### 🟠 Advanced

**Q: Explain A* and the requirement on its heuristic.**
A* orders the frontier by `f(n) = g(n) + h(n)`, where `g` is the cost so far and `h` estimates the remaining cost to the target. If `h` is **admissible** (never overestimates) A* returns the optimal path; if `h` is also **consistent** (`h(u) ≤ w(u,v) + h(v)`), no node is expanded twice. With `h ≡ 0`, A* degenerates to Dijkstra.

**Q: When would you use Johnson's algorithm over Floyd-Warshall?**
For all-pairs shortest paths on a **sparse** graph that may contain negative edges. Johnson's reweights edges to be non-negative (via a Bellman-Ford pass), then runs Dijkstra from each vertex: `O(V·E + V² log V)`, beating `O(V³)` when `E ≪ V²`.

**Q: How do you model "shortest path with state" problems?**
Expand the node into `(vertex, extraState)`. Examples: `(city, stopsUsed)` for K-stop flights, `(cell, keysCollected)` for keys-and-doors grids, `(node, parityOfEdges)`. Run Dijkstra/BFS on this product graph.

**Q: How do you handle multiplicative weights (max-probability path)?**
Either run a *max*-heap Dijkstra multiplying probabilities (valid because probabilities are in `[0,1]`, so paths only shrink — the greedy invariant holds), or take `−log(p)` to turn products into sums and run standard Dijkstra.

### 🔴 Expert

**Q: What is the amortized cost of Dijkstra with a Fibonacci heap and why isn't it used in practice?**
`decrease-key` is `O(1)` amortized and `extract-min` is `O(log V)`, giving `O(E + V log V)`. In practice the large constant factors and poor cache behavior make a binary heap (or a d-ary heap) faster on real inputs, so Fibonacci heaps are mostly theoretical.

**Q: How do real-world routing engines scale shortest paths to continent-sized graphs?**
They precompute. **Contraction Hierarchies** add shortcut edges by contracting low-importance nodes, enabling millisecond queries. **A* with landmarks (ALT)** uses precomputed distances to landmark nodes as a tighter heuristic. **Hub labeling** stores per-node distance labels for near-constant-time queries. Plain Dijkstra is too slow at that scale.

**Q: How would you parallelize or distribute SSSP?**
Δ-stepping buckets vertices by distance ranges to expose parallelism between Dijkstra (strict ordering) and Bellman-Ford (no ordering). For distributed graphs, partition vertices across machines and run BSP-style supersteps (Pregel / GraphX), exchanging boundary relaxations each round — essentially distributed Bellman-Ford.

**Q: Dijkstra is provably optimal — what's the proof sketch?**
By induction on the order of extraction. When vertex `u` is popped with distance `d`, assume for contradiction a shorter path `P` exists. `P` must leave the settled set via some edge `(x,y)` with `y` unsettled. Since all weights are non-negative, `dist[y] ≤ len(P) < d`, so `y` would have been popped before `u` — contradiction. Hence `d` is optimal.

---

## ⚠️ Common Pitfalls

- **Using Dijkstra with negative edges.** Silently returns wrong answers — no exception thrown. Switch to Bellman-Ford.
- **Integer overflow.** Initializing distances to `Integer.MAX_VALUE` and then adding a weight overflows to negative, creating phantom shortcuts. Use a sentinel like `1e9`, check for `MAX_VALUE` before relaxing, or use `long`.
- **Forgetting the stale-pop check** in heap Dijkstra — works but degrades performance, and reusing the popped distance instead of `dist[node]` can be subtly wrong.
- **Cheapest Flights without a snapshot.** Relaxing in place lets one Bellman-Ford round chain multiple edges, violating the K-stop budget. Clone the distance array each round.
- **Wrong Floyd-Warshall loop order.** `k` must be outermost. Any other order silently produces incorrect distances.
- **Marking BFS cells visited on dequeue instead of enqueue** — allows the same cell to be queued many times, blowing up memory and time.
- **Tie-breaking errors** in "smallest index on tie" / "fewest edges among shortest paths" — read the tie rule carefully; `<` vs `<=` flips the result.
- **Counting paths with `int`** in LC 1976-style problems — distances overflow `int`; keep distances in `long`, counts mod `10⁹+7`.
- **Assuming undirected when the graph is directed** (or vice versa) — Network Delay is directed; the City problem is undirected (add both directions).

## 📚 Further Reading

- *Introduction to Algorithms* (CLRS), Ch. 24–25 — Bellman-Ford, Dijkstra, Floyd-Warshall, Johnson's with proofs.
- *Algorithms* by Sedgewick & Wayne, Ch. 4.4 — shortest paths with excellent visualizations.
- Stanford CS161 / MIT 6.006 lecture notes on graph algorithms.
- "Engineering Route Planning Algorithms" (Delling, Sanders, Schultes, Wagner) — Contraction Hierarchies and real-world scaling.
- LeetCode tag **Shortest Path**: 743, 787, 1091, 1334, 1514, 1631, 1976, 2045, 2203.
- Competitive Programmer's Handbook (Laaksonen), Ch. 13 — concise SSSP/APSP reference and 0-1 BFS.

[← Back to master index](../README.md) | [← DSA index](README.md)
