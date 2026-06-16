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

## 🧩 Extended Problems — Set 1: Classic Easy → Medium

### Problem 9: Path with Maximum Probability (Dijkstra, multiplicative weights)

**Statement.** LeetCode 1514. An undirected graph has `n` nodes and `edges[i] = [a, b]` with success probability `succProb[i]`. Return the maximum probability of a path from `start` to `end`; return `0` if no path exists.

**Constraints.** `2 ≤ n ≤ 10⁴`, `0 ≤ edges.length ≤ 2·10⁴`, `0 ≤ succProb[i] ≤ 1`, no repeated edges.

**Approach.** Probabilities multiply along a path, and each factor is in `[0,1]`, so extending a path can only shrink the product — the same monotonicity Dijkstra needs, just with `max` instead of `min`. Run a **max-heap Dijkstra**: keep `prob[v]` = best probability to reach `v`, pop the most-probable frontier node first, and relax with `prob[u] * succProb`. Once `end` is popped its probability is final. (The classic `−log` trick — turning products into sums to use a min-heap — also works, but a max-heap is simpler and avoids floating-point log error.)

```
       0.5        0.5
   0 ────── 1 ────── 2          start=0, end=2
   │                  │
   └──── 0.2 ─────────┘         direct 0–2 = 0.2  vs  0–1–2 = 0.25  → 0.25 wins
```

```java
import java.util.*;

class Solution {
    public double maxProbability(int n, int[][] edges, double[] succProb, int start, int end) {
        List<double[]>[] adj = new List[n];
        for (int i = 0; i < n; i++) adj[i] = new ArrayList<>();
        for (int i = 0; i < edges.length; i++) {
            int a = edges[i][0], b = edges[i][1];
            adj[a].add(new double[]{b, succProb[i]});
            adj[b].add(new double[]{a, succProb[i]});
        }

        double[] prob = new double[n];
        prob[start] = 1.0;
        // max-heap on probability
        PriorityQueue<double[]> pq = new PriorityQueue<>((x, y) -> Double.compare(y[1], x[1]));
        pq.offer(new double[]{start, 1.0});

        while (!pq.isEmpty()) {
            double[] top = pq.poll();
            int u = (int) top[0];
            double p = top[1];
            if (u == end) return p;            // first settle = answer
            if (p < prob[u]) continue;          // stale entry
            for (double[] e : adj[u]) {
                int v = (int) e[0];
                double np = p * e[1];
                if (np > prob[v]) {             // relax toward larger probability
                    prob[v] = np;
                    pq.offer(new double[]{v, np});
                }
            }
        }
        return 0.0;
    }
}
```

**Complexity** — Time `O(E log V)` (heap of duplicates), Space `O(V + E)`. **Edge cases:** start == end (return 1.0, handled since it's popped first); disconnected end (loop drains, returns 0.0); zero-probability edges never improve a node so they are effectively pruned.

---

### Problem 10: Minimum Cost to Reach Destination in Time (Dijkstra on state, bounded)

**Statement.** LeetCode 2045-adjacent / 1928. An undirected weighted graph (edge weight = travel time) plus per-node `passingFees`. Find the minimum total fee to travel from city `0` to city `n-1` such that total time `≤ maxTime`. Return `-1` if impossible.

**Constraints.** `2 ≤ n ≤ 1000`, `1 ≤ maxTime ≤ 1000`, `1 ≤ time ≤ 1000`, fees up to `1000`.

**Approach.** Two competing objectives — minimize **fee** under a **time** budget — so plain shortest-time or shortest-fee Dijkstra is insufficient. Model the state as `(city, timeUsed)` and run Dijkstra ordered by **accumulated fee**. Track `minTime[city]` = least time seen at that city: only expand a state if it arrives with strictly less time than any previous visit, since a costlier-fee but later-time state can never beat it. The first time we pop city `n-1`, its fee is minimal.

```java
import java.util.*;

class Solution {
    public int minCost(int maxTime, int[][] edges, int[] passingFees) {
        int n = passingFees.length;
        List<int[]>[] adj = new List[n];
        for (int i = 0; i < n; i++) adj[i] = new ArrayList<>();
        for (int[] e : edges) {
            adj[e[0]].add(new int[]{e[1], e[2]});
            adj[e[1]].add(new int[]{e[0], e[2]});
        }

        int[] minTime = new int[n];
        Arrays.fill(minTime, Integer.MAX_VALUE);
        // {fee, city, time}  ordered by fee
        PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> a[0] - b[0]);
        pq.offer(new int[]{passingFees[0], 0, 0});
        minTime[0] = 0;

        while (!pq.isEmpty()) {
            int[] cur = pq.poll();
            int fee = cur[0], u = cur[1], t = cur[2];
            if (u == n - 1) return fee;          // lowest fee within time
            for (int[] e : adj[u]) {
                int v = e[0], nt = t + e[1];
                if (nt > maxTime) continue;      // over budget
                if (nt < minTime[v]) {           // only worth it if faster than before
                    minTime[v] = nt;
                    pq.offer(new int[]{fee + passingFees[v], v, nt});
                }
            }
        }
        return -1;
    }
}
```

**Complexity** — Time `O(E log E)` (each edge relaxed at most for an improving time), Space `O(V + E)`. **Edge cases:** `maxTime` smaller than any single edge from 0 (returns -1); start fee always included (seed with `passingFees[0]`); multiple edges between the same pair are kept and handled naturally.

---

### Problem 11: Shortest Path in a Grid with Obstacles Elimination (BFS on state)

**Statement.** LeetCode 1293. Given an `m x n` grid of `0` (empty) and `1` (obstacle), starting at `(0,0)` and ending at `(m-1,n-1)`, you may eliminate at most `k` obstacles. Return the minimum number of steps, or `-1`.

**Constraints.** `1 ≤ m, n ≤ 40`, `1 ≤ k ≤ m*n`.

**Approach.** All moves cost 1 → BFS, but a cell's reachability depends on remaining eliminations, so the **state is `(row, col, eliminationsUsed)`**. Track `visited[r][c][used]` to avoid revisiting a state with the same-or-worse budget. A classic optimization: if `k ≥ m + n - 2` (the Manhattan distance), the answer is just `m + n - 2` because you can punch straight through. BFS guarantees the first arrival at the target is the fewest steps.

```java
import java.util.*;

class Solution {
    public int shortestPath(int[][] grid, int k) {
        int m = grid.length, n = grid[0].length;
        if (k >= m + n - 2) return m + n - 2;     // can go straight

        boolean[][][] visited = new boolean[m][n][k + 1];
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        Queue<int[]> q = new ArrayDeque<>();      // {r, c, used}
        q.offer(new int[]{0, 0, 0});
        visited[0][0][0] = true;
        int steps = 0;

        while (!q.isEmpty()) {
            int sz = q.size();
            for (int i = 0; i < sz; i++) {
                int[] cur = q.poll();
                int r = cur[0], c = cur[1], used = cur[2];
                if (r == m - 1 && c == n - 1) return steps;
                for (int[] d : dirs) {
                    int nr = r + d[0], nc = c + d[1];
                    if (nr < 0 || nr >= m || nc < 0 || nc >= n) continue;
                    int nused = used + grid[nr][nc];   // +1 if obstacle
                    if (nused <= k && !visited[nr][nc][nused]) {
                        visited[nr][nc][nused] = true;
                        q.offer(new int[]{nr, nc, nused});
                    }
                }
            }
            steps++;
        }
        return -1;
    }
}
```

**Complexity** — Time `O(m·n·k)` (each state visited once), Space `O(m·n·k)`. **Edge cases:** start or end is an obstacle (start counts toward nothing since we begin there; the straight-through shortcut covers most large-`k` cases); single cell grid returns 0; `k` larger than total obstacles handled by the shortcut.

---

### Problem 12: Find the Minimum Time to Reach the Last Room I (Dijkstra with wait constraint)

**Statement.** LeetCode 3341 (Medium). `moveTime[i][j]` is the earliest second you are allowed to **start** moving into room `(i,j)`. You start at `(0,0)` at time 0; each move to an adjacent room takes 1 second. Return the minimum time to reach `(n-1, m-1)`.

**Constraints.** `2 ≤ n, m ≤ 750`, `0 ≤ moveTime[i][j] ≤ 10⁹`.

**Approach.** This is a Dijkstra where the cost to enter a neighbor is not constant: you must **wait** until `moveTime[nr][nc]` before starting the 1-second move, so arrival time `= max(currentTime, moveTime[nr][nc]) + 1`. Arrival time is still monotonic non-decreasing as you settle nodes, so Dijkstra's greedy invariant holds. Use `long` because `moveTime` reaches `10⁹` and accumulates.

```java
import java.util.*;

class Solution {
    public int minTimeToReach(int[][] moveTime) {
        int n = moveTime.length, m = moveTime[0].length;
        long[][] best = new long[n][m];
        for (long[] row : best) Arrays.fill(row, Long.MAX_VALUE);
        best[0][0] = 0;

        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        // {time, r, c}
        PriorityQueue<long[]> pq = new PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
        pq.offer(new long[]{0, 0, 0});

        while (!pq.isEmpty()) {
            long[] cur = pq.poll();
            long t = cur[0];
            int r = (int) cur[1], c = (int) cur[2];
            if (r == n - 1 && c == m - 1) return (int) t;
            if (t > best[r][c]) continue;           // stale
            for (int[] d : dirs) {
                int nr = r + d[0], nc = c + d[1];
                if (nr < 0 || nr >= n || nc < 0 || nc >= m) continue;
                long arrive = Math.max(t, moveTime[nr][nc]) + 1;
                if (arrive < best[nr][nc]) {
                    best[nr][nc] = arrive;
                    pq.offer(new long[]{arrive, nr, nc});
                }
            }
        }
        return -1;  // unreachable (won't happen in a fully connected grid)
    }
}
```

**Complexity** — Time `O(n·m·log(n·m))`, Space `O(n·m)`. **Edge cases:** `moveTime[0][0]` is irrelevant since you begin there at 0; large `moveTime` forces waiting (the `max` handles it); use `long` to avoid overflow when `moveTime ≈ 10⁹`.

---

### Problem 13: Minimum Cost to Make at Least One Valid Path in a Grid (0-1 BFS)

**Statement.** LeetCode 1368. Each cell of an `m x n` grid has a sign (1=right, 2=left, 3=down, 4=up) pointing to the next cell you'd visit for free. You may change one cell's sign for cost 1. Return the minimum cost to travel from `(0,0)` to `(m-1,n-1)`.

**Constraints.** `1 ≤ m, n ≤ 100`.

**Approach.** Moving in the sign's direction costs 0; any other direction costs 1 (you "rewire" the sign). With edge weights restricted to `{0, 1}`, **0-1 BFS** with a deque is optimal — no heap needed. Push 0-cost moves to the **front** of the deque and 1-cost moves to the **back**, keeping the deque sorted by cost. The first time the target is popped, its cost is final.

```
sign legend:  1 → right   2 → left   3 → down   4 → up
free move follows the arrow; any turn costs 1 (rewire the sign)
```

```java
import java.util.*;

class Solution {
    public int minCost(int[][] grid) {
        int m = grid.length, n = grid[0].length;
        // dir index: 1=right,2=left,3=down,4=up  ->  map to (dr,dc)
        int[][] dirs = {{0,1},{0,-1},{1,0},{-1,0}};  // order matches signs 1..4
        int[][] cost = new int[m][n];
        for (int[] row : cost) Arrays.fill(row, Integer.MAX_VALUE);
        cost[0][0] = 0;

        Deque<int[]> dq = new ArrayDeque<>();   // {r, c}
        dq.offerFirst(new int[]{0, 0});

        while (!dq.isEmpty()) {
            int[] cur = dq.pollFirst();
            int r = cur[0], c = cur[1];
            for (int dir = 0; dir < 4; dir++) {
                int nr = r + dirs[dir][0], nc = c + dirs[dir][1];
                if (nr < 0 || nr >= m || nc < 0 || nc >= n) continue;
                int w = (grid[r][c] == dir + 1) ? 0 : 1;   // free if sign matches
                if (cost[r][c] + w < cost[nr][nc]) {
                    cost[nr][nc] = cost[r][c] + w;
                    if (w == 0) dq.offerFirst(new int[]{nr, nc});  // front
                    else        dq.offerLast(new int[]{nr, nc});   // back
                }
            }
        }
        return cost[m - 1][n - 1];
    }
}
```

**Complexity** — Time `O(m·n)` (each cell relaxed a constant number of times), Space `O(m·n)`. **Edge cases:** single cell returns 0; the deque may hold a cell more than once, but the `cost[r][c] + w < cost[nr][nc]` guard discards stale relaxations; signs always point in-bounds-or-not — out-of-bounds simply skipped.

---

### Problem 14: As Far from Land as Possible (Multi-source BFS, max distance)

**Statement.** LeetCode 1162. Given an `n x n` grid of `0` (water) and `1` (land), find the water cell whose distance to the nearest land is maximized, using Manhattan distance via 4-directional steps. Return that maximum, or `-1` if the grid is all land or all water.

**Constraints.** `1 ≤ n ≤ 100`.

**Approach.** "Nearest land for every water cell" is multi-source BFS seeded from **all land cells** simultaneously. Expanding outward layer by layer, the *last* water cell reached is the farthest from any land — its distance equals the number of BFS layers. This is the dual of the 01-Matrix problem (there we wanted each cell's distance; here we want the maximum of those distances).

```java
import java.util.*;

class Solution {
    public int maxDistance(int[][] grid) {
        int n = grid.length;
        Queue<int[]> q = new ArrayDeque<>();
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                if (grid[i][j] == 1) q.offer(new int[]{i, j});

        if (q.isEmpty() || q.size() == n * n) return -1;  // all water or all land

        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        int dist = -1;
        while (!q.isEmpty()) {
            int sz = q.size();
            dist++;
            for (int i = 0; i < sz; i++) {
                int[] cell = q.poll();
                for (int[] d : dirs) {
                    int nr = cell[0] + d[0], nc = cell[1] + d[1];
                    if (nr >= 0 && nr < n && nc >= 0 && nc < n && grid[nr][nc] == 0) {
                        grid[nr][nc] = 1;            // mark visited
                        q.offer(new int[]{nr, nc});
                    }
                }
            }
        }
        return dist;   // last layer expanded = farthest distance
    }
}
```

**Complexity** — Time `O(n²)`, Space `O(n²)`. **Edge cases:** all-land or all-water grids return -1 (guarded up front); `dist` starts at -1 so that after processing the land layer (distance 0) the first water layer yields 1.

---

### Problem 15: Rotting Oranges (Multi-source BFS, time to fill)

**Statement.** LeetCode 994. In an `m x n` grid, `0`=empty, `1`=fresh orange, `2`=rotten. Each minute, a rotten orange rots all 4-adjacent fresh oranges. Return the minimum minutes until no fresh orange remains, or `-1` if some fresh orange can never rot.

**Constraints.** `1 ≤ m, n ≤ 10`.

**Approach.** Rotting spreads one ring per minute from all currently-rotten oranges → multi-source BFS. Seed the queue with every rotten orange and count fresh oranges. Each BFS layer is one minute; rot all fresh neighbors, decrementing the fresh count. If fresh oranges remain when BFS finishes, they were unreachable → return `-1`.

```java
import java.util.*;

class Solution {
    public int orangesRotting(int[][] grid) {
        int m = grid.length, n = grid[0].length;
        Queue<int[]> q = new ArrayDeque<>();
        int fresh = 0;
        for (int i = 0; i < m; i++)
            for (int j = 0; j < n; j++) {
                if (grid[i][j] == 2) q.offer(new int[]{i, j});
                else if (grid[i][j] == 1) fresh++;
            }

        if (fresh == 0) return 0;        // nothing to rot

        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        int minutes = 0;
        while (!q.isEmpty() && fresh > 0) {
            int sz = q.size();
            minutes++;
            for (int i = 0; i < sz; i++) {
                int[] cell = q.poll();
                for (int[] d : dirs) {
                    int nr = cell[0] + d[0], nc = cell[1] + d[1];
                    if (nr >= 0 && nr < m && nc >= 0 && nc < n && grid[nr][nc] == 1) {
                        grid[nr][nc] = 2;   // rot it
                        fresh--;
                        q.offer(new int[]{nr, nc});
                    }
                }
            }
        }
        return fresh == 0 ? minutes : -1;
    }
}
```

**Complexity** — Time `O(m·n)`, Space `O(m·n)`. **Edge cases:** no fresh oranges initially returns 0; isolated fresh orange unreachable by rot returns -1; the `fresh > 0` loop guard avoids counting an extra empty minute after the last orange rots.

---

### Problem 16: Word Ladder (Unweighted BFS over transformations)

**Statement.** LeetCode 127. Given `beginWord`, `endWord`, and a `wordList`, return the number of words in the shortest transformation sequence (each step changes exactly one letter and must be in `wordList`), or `0` if none exists.

**Constraints.** word length up to 10, `wordList.length ≤ 5000`, lowercase letters.

**Approach.** Each one-letter change costs 1, so this is an unweighted shortest path → BFS over a word graph. Building edges explicitly is `O(W²·L)`; instead, generate neighbors on the fly by trying every position × 26 letters and checking a `HashSet` for membership in `O(L·26)` per word. BFS by layers; the answer is the layer index when `endWord` is first reached (length includes both endpoints).

```java
import java.util.*;

class Solution {
    public int ladderLength(String beginWord, String endWord, List<String> wordList) {
        Set<String> dict = new HashSet<>(wordList);
        if (!dict.contains(endWord)) return 0;

        Queue<String> q = new ArrayDeque<>();
        q.offer(beginWord);
        dict.remove(beginWord);
        int level = 1;

        while (!q.isEmpty()) {
            int sz = q.size();
            for (int i = 0; i < sz; i++) {
                String word = q.poll();
                if (word.equals(endWord)) return level;
                char[] chars = word.toCharArray();
                for (int p = 0; p < chars.length; p++) {
                    char original = chars[p];
                    for (char ch = 'a'; ch <= 'z'; ch++) {
                        if (ch == original) continue;
                        chars[p] = ch;
                        String next = new String(chars);
                        if (dict.remove(next)) {   // contains + remove (mark visited)
                            q.offer(next);
                        }
                    }
                    chars[p] = original;           // restore for next position
                }
            }
            level++;
        }
        return 0;
    }
}
```

**Complexity** — Time `O(W·L·26)` where `W = |wordList|`, `L = word length`; Space `O(W·L)`. **Edge cases:** `endWord` absent from dict returns 0; `beginWord` equal to `endWord` is handled when `endWord` is in the dict and reached at level 1; removing from the set on enqueue prevents revisiting and infinite loops.

---

### Problem 17: Minimum Genetic Mutation (Unweighted BFS, fixed alphabet)

**Statement.** LeetCode 433. Genes are 8-character strings over `{A,C,G,T}`. Given `startGene`, `endGene`, and a `bank` of valid genes, return the minimum number of single-character mutations to get from start to end (each intermediate must be in `bank`), or `-1`.

**Constraints.** gene length is exactly 8, `bank.length ≤ 10`.

**Approach.** Identical structure to Word Ladder but with a 4-letter alphabet and fixed length 8. Unweighted BFS: from a gene, try every position × `{A,C,G,T}`, keep mutations present in the bank, expand layer by layer. The number of layers to first reach `endGene` is the answer. The small bank makes brute force trivially fast.

```java
import java.util.*;

class Solution {
    public int minMutation(String startGene, String endGene, String[] bank) {
        Set<String> dict = new HashSet<>(Arrays.asList(bank));
        if (!dict.contains(endGene)) return -1;

        char[] choices = {'A', 'C', 'G', 'T'};
        Queue<String> q = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        q.offer(startGene);
        visited.add(startGene);
        int mutations = 0;

        while (!q.isEmpty()) {
            int sz = q.size();
            for (int i = 0; i < sz; i++) {
                String gene = q.poll();
                if (gene.equals(endGene)) return mutations;
                char[] chars = gene.toCharArray();
                for (int p = 0; p < 8; p++) {
                    char original = chars[p];
                    for (char ch : choices) {
                        if (ch == original) continue;
                        chars[p] = ch;
                        String next = new String(chars);
                        if (dict.contains(next) && visited.add(next)) {
                            q.offer(next);
                        }
                    }
                    chars[p] = original;
                }
            }
            mutations++;
        }
        return -1;
    }
}
```

**Complexity** — Time `O(8·4·B)` where `B = |bank|`, Space `O(B)`. **Edge cases:** `endGene` not in bank returns -1; start equals end returns 0 (reached at mutation count 0); a `visited` set guards against cycles among valid genes.

---

### Problem 18: Bus Routes (BFS over routes, not stops)

**Statement.** LeetCode 815. `routes[i]` is the list of stops bus `i` cycles through. Starting at `source` and wanting to reach `target`, return the least number of **buses** to take, or `-1`.

**Constraints.** `1 ≤ routes.length ≤ 500`, total stops up to `10⁵`.

**Approach.** Minimizing the number of buses = unweighted shortest path where each "step" boards one bus. The efficient model treats **routes as graph nodes**: two routes are connected if they share a stop. Build `stopToRoutes` (which buses serve each stop), BFS starting from all routes serving `source`, and finish when a visited route serves `target`. The BFS layer count is the number of buses.

```
stops:  S --bus0-- A --bus0-- T          source=S, target=T
        S --bus1-- B                      one bus (bus0) reaches T  → 1
```

```java
import java.util.*;

class Solution {
    public int numBusesToDestination(int[][] routes, int source, int target) {
        if (source == target) return 0;

        // stop -> list of bus (route) indices serving it
        Map<Integer, List<Integer>> stopToRoutes = new HashMap<>();
        for (int i = 0; i < routes.length; i++)
            for (int stop : routes[i])
                stopToRoutes.computeIfAbsent(stop, x -> new ArrayList<>()).add(i);

        Queue<Integer> q = new ArrayDeque<>();   // route indices
        boolean[] visitedRoute = new boolean[routes.length];
        // seed with every route serving the source stop
        for (int r : stopToRoutes.getOrDefault(source, Collections.emptyList())) {
            q.offer(r);
            visitedRoute[r] = true;
        }

        int buses = 1;
        while (!q.isEmpty()) {
            int sz = q.size();
            for (int i = 0; i < sz; i++) {
                int route = q.poll();
                for (int stop : routes[route]) {
                    if (stop == target) return buses;          // reached on this many buses
                    for (int next : stopToRoutes.getOrDefault(stop, Collections.emptyList())) {
                        if (!visitedRoute[next]) {
                            visitedRoute[next] = true;
                            q.offer(next);
                        }
                    }
                }
            }
            buses++;
        }
        return -1;
    }
}
```

**Complexity** — Time `O(sum of route lengths)` since each route and each stop-route pair is processed once; Space `O(total stops + routes)`. **Edge cases:** `source == target` returns 0 immediately; source served by no route returns -1 (queue empty); a `visitedRoute` array (not a stop-visited set) prevents reprocessing the same bus.

---

### Problem 19: Get Watched Videos by Friends (Layered BFS, level extraction)

**Statement.** LeetCode 1311. Given a social network (`friends[i]` = friend list of person `i`), each person's `watchedVideos`, an `id`, and a `level`, return the list of videos watched by all people exactly at BFS distance `level` from `id`, sorted by frequency then alphabetically.

**Constraints.** `1 ≤ n ≤ 100`, friendships symmetric.

**Approach.** "People exactly `level` hops away" is a BFS that stops at a specific layer. Run standard layered BFS from `id`, marking visited; when the layer counter equals `level`, the current queue holds exactly the target people. Then tally video frequencies across those people and sort: ascending by count, then lexicographically by title.

```java
import java.util.*;

class Solution {
    public List<String> watchedVideosByFriends(List<List<String>> watchedVideos,
                                                int[][] friends, int id, int level) {
        int n = friends.length;
        boolean[] visited = new boolean[n];
        Queue<Integer> q = new ArrayDeque<>();
        q.offer(id);
        visited[id] = true;

        for (int lvl = 0; lvl < level; lvl++) {     // advance exactly `level` layers
            int sz = q.size();
            for (int i = 0; i < sz; i++) {
                int person = q.poll();
                for (int f : friends[person]) {
                    if (!visited[f]) {
                        visited[f] = true;
                        q.offer(f);
                    }
                }
            }
        }

        // q now holds all people at distance == level
        Map<String, Integer> freq = new HashMap<>();
        for (int person : q)
            for (String v : watchedVideos.get(person))
                freq.merge(v, 1, Integer::sum);

        List<String> result = new ArrayList<>(freq.keySet());
        result.sort((a, b) -> {
            int c = freq.get(a) - freq.get(b);       // ascending frequency
            return c != 0 ? c : a.compareTo(b);      // tie: lexicographic
        });
        return result;
    }
}
```

**Complexity** — Time `O(V + E + K log K)` where `K` = number of distinct videos at that level; Space `O(V + K)`. **Edge cases:** `level` larger than the graph's reach leaves an empty queue → empty result; `id` itself is excluded once `level ≥ 1` (it is visited and never re-added); symmetric friendship handled by the `visited` guard.

---

### Problem 20: Shortest Bridge (BFS flood-fill + multi-source BFS)

**Statement.** LeetCode 934. An `n x n` binary grid contains exactly two islands of `1`s. Flip the fewest `0`s to `1`s to connect the two islands. Return that minimum number of flips (the length of the shortest bridge).

**Constraints.** `2 ≤ n ≤ 100`, exactly two islands.

**Approach.** Two phases. (1) **Flood-fill** (DFS or BFS) the first island found, marking its cells (e.g., as `2`) and seeding a queue with all of them. (2) **Multi-source BFS** outward from the entire first island through water; the number of layers expanded before touching the second island (a `1` cell) is the bridge length. Expanding from the whole island at once guarantees the minimal gap.

```java
import java.util.*;

class Solution {
    public int shortestBridge(int[][] grid) {
        int n = grid.length;
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        Queue<int[]> q = new ArrayDeque<>();

        // Phase 1: find and flood-fill the first island, seeding the BFS frontier
        boolean found = false;
        for (int i = 0; i < n && !found; i++)
            for (int j = 0; j < n && !found; j++)
                if (grid[i][j] == 1) { dfs(grid, i, j, q, dirs, n); found = true; }

        // Phase 2: multi-source BFS until we hit the second island
        int steps = 0;
        while (!q.isEmpty()) {
            int sz = q.size();
            for (int i = 0; i < sz; i++) {
                int[] cell = q.poll();
                for (int[] d : dirs) {
                    int nr = cell[0] + d[0], nc = cell[1] + d[1];
                    if (nr < 0 || nr >= n || nc < 0 || nc >= n) continue;
                    if (grid[nr][nc] == 1) return steps;   // reached second island
                    if (grid[nr][nc] == 0) {
                        grid[nr][nc] = 2;                  // mark visited water
                        q.offer(new int[]{nr, nc});
                    }
                }
            }
            steps++;
        }
        return -1;  // unreachable given the two-island guarantee
    }

    private void dfs(int[][] grid, int r, int c, Queue<int[]> q, int[][] dirs, int n) {
        if (r < 0 || r >= n || c < 0 || c >= n || grid[r][c] != 1) return;
        grid[r][c] = 2;                 // mark as part of island 1
        q.offer(new int[]{r, c});       // seed the expansion frontier
        for (int[] d : dirs) dfs(grid, r + d[0], c + d[1], q, dirs, n);
    }
}
```

**Complexity** — Time `O(n²)` (each cell visited a constant number of times), Space `O(n²)`. **Edge cases:** islands adjacent with a 1-cell gap return 1; the flood-fill marks the source island as `2` so BFS won't mistake it for the target; `steps` counts water cells flipped, which equals the bridge length since the first `1` reached is the destination shore.

---

### Problem 21: Jump Game III (BFS reachability on an array)

**Statement.** LeetCode 1306. Given an integer array `arr` and a start index, at index `i` you may jump to `i + arr[i]` or `i - arr[i]`. Return `true` if you can reach **any** index with value `0`.

**Constraints.** `1 ≤ arr.length ≤ 5·10⁴`, `0 ≤ arr[i] < arr.length`, `0 ≤ start < arr.length`.

**Approach.** Indices are graph nodes; each index has up to two outgoing edges (`±arr[i]`). Reachability of a zero-valued cell is a plain BFS (or DFS) from `start`, marking visited indices to avoid cycles. Although this is a reachability question rather than a distance one, it is the canonical "implicit graph traversal" warm-up and trivially extends to "minimum jumps to reach a 0" by counting BFS layers.

```java
import java.util.*;

class Solution {
    public boolean canReach(int[] arr, int start) {
        int n = arr.length;
        boolean[] visited = new boolean[n];
        Queue<Integer> q = new ArrayDeque<>();
        q.offer(start);
        visited[start] = true;

        while (!q.isEmpty()) {
            int i = q.poll();
            if (arr[i] == 0) return true;
            for (int next : new int[]{i + arr[i], i - arr[i]}) {
                if (next >= 0 && next < n && !visited[next]) {
                    visited[next] = true;
                    q.offer(next);
                }
            }
        }
        return false;
    }
}
```

**Complexity** — Time `O(n)` (each index enqueued at most once), Space `O(n)`. **Edge cases:** `arr[start] == 0` returns true immediately; jumps that go out of bounds are skipped; the `visited` array makes cycles terminate, so a no-zero or unreachable-zero array returns false.

---

## 🧩 Extended Problems — Set 2: Medium → Hard Variations & Follow-ups

### Problem 22: Swim in Rising Water (Dijkstra / minimax, with binary-search alternative) — Minimax path

**Statement.** LeetCode 778 (Hard). In an `n x n` grid, `grid[i][j]` is the elevation. At time `t`, water level is `t`; you can move 4-directionally between two cells only if **both** elevations are `≤ t`. Starting at `(0,0)`, return the least time `t` to reach `(n-1,n-1)`.

**Constraints.** `1 ≤ n ≤ 50`, `0 ≤ grid[i][j] < n²`, all values distinct.

**Approach.** The time to reach a cell along a path equals the **maximum elevation** on that path (you wait for the highest cell to be submerged). So this is a minimax-path problem: minimize the path's maximum elevation — exactly the structure of "Path With Minimum Effort," but the cost is `max(elevation)` rather than `max(diff)`.

- **Brute force:** binary search the answer `t` over `[0, n²−1]` and BFS/DFS checking whether `(0,0)→(n-1,n-1)` is connected using only cells `≤ t`. Cost `O(n² log n²)`.
- **Optimal (Dijkstra):** treat the "distance" to a cell as the minimum possible path-maximum. Pop the smallest-maximum frontier first; relax with `max(curMax, grid[nr][nc])`. The first time `(n-1,n-1)` is popped, its value is the answer. Same asymptotics but single pass and cleaner.

```
elevation along a path:  start … 7 … 12 … 3 … target
time to traverse = 12  (must wait until the tallest cell is submerged)
goal: pick the path whose tallest cell is as small as possible
```

```java
import java.util.*;

class Solution {
    public int swimInWater(int[][] grid) {
        int n = grid.length;
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        int[][] best = new int[n][n];
        for (int[] row : best) Arrays.fill(row, Integer.MAX_VALUE);
        best[0][0] = grid[0][0];

        // {pathMax, r, c}
        PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> a[0] - b[0]);
        pq.offer(new int[]{grid[0][0], 0, 0});

        while (!pq.isEmpty()) {
            int[] cur = pq.poll();
            int t = cur[0], r = cur[1], c = cur[2];
            if (r == n - 1 && c == n - 1) return t;   // first settle = answer
            if (t > best[r][c]) continue;             // stale
            for (int[] d : dirs) {
                int nr = r + d[0], nc = c + d[1];
                if (nr < 0 || nr >= n || nc < 0 || nc >= n) continue;
                int nt = Math.max(t, grid[nr][nc]);
                if (nt < best[nr][nc]) {
                    best[nr][nc] = nt;
                    pq.offer(new int[]{nt, nr, nc});
                }
            }
        }
        return -1;  // unreachable in a connected grid
    }
}
```

**Complexity** — Time `O(n² log n)`, Space `O(n²)`. **Edge cases:** `n == 1` returns `grid[0][0]`; the start cell's own elevation seeds the path-max (you wait for it too); distinct values mean no tie ambiguity, but the code is correct regardless.

---

### Problem 23: Reachable Nodes In Subdivided Graph (Dijkstra on a weighted graph) — Budgeted reach

**Statement.** LeetCode 882 (Hard). An undirected graph has `edges[i] = [u, v, cnt]` meaning the edge is subdivided into `cnt` new intermediate nodes (so its true length is `cnt + 1`). Starting at node `0` with a move budget of `maxMoves`, return how many nodes (original + subdivided) are reachable within `maxMoves` moves.

**Constraints.** `0 ≤ edges.length ≤ min(n·(n−1)/2, 10⁴)`, `0 ≤ cnt ≤ 10⁴`, `1 ≤ n ≤ 3000`, `0 ≤ maxMoves ≤ 10⁹`.

**Approach.** Materializing every subdivided node is too many (up to `~10⁸`). Instead run Dijkstra on the **original** graph to get `dist[v]` = shortest distance to each original node. Then count reachable nodes in two parts: (1) every original node with `dist[v] ≤ maxMoves`; (2) for each edge `(u,v,cnt)`, the subdivided nodes reachable from the `u` side are `max(0, maxMoves − dist[u])`, from the `v` side `max(0, maxMoves − dist[v])`, but their sum is capped at `cnt` (don't double-count the middle). This is the key insight — you reach intermediate nodes from both endpoints.

```java
import java.util.*;

class Solution {
    public int reachableNodes(int[][] edges, int maxMoves, int n) {
        List<int[]>[] adj = new List[n];
        for (int i = 0; i < n; i++) adj[i] = new ArrayList<>();
        for (int[] e : edges) {
            int w = e[2] + 1;                 // true edge length
            adj[e[0]].add(new int[]{e[1], w});
            adj[e[1]].add(new int[]{e[0], w});
        }

        long[] dist = new long[n];
        Arrays.fill(dist, Long.MAX_VALUE);
        dist[0] = 0;
        PriorityQueue<long[]> pq = new PriorityQueue<>((a, b) -> Long.compare(a[1], b[1]));
        pq.offer(new long[]{0, 0});
        while (!pq.isEmpty()) {
            long[] top = pq.poll();
            int u = (int) top[0]; long d = top[1];
            if (d > dist[u]) continue;
            for (int[] e : adj[u]) {
                long nd = d + e[1];
                if (nd < dist[e[0]]) { dist[e[0]] = nd; pq.offer(new long[]{e[0], nd}); }
            }
        }

        int reached = 0;
        for (int i = 0; i < n; i++)
            if (dist[i] <= maxMoves) reached++;        // original nodes

        for (int[] e : edges) {
            long a = dist[e[0]] == Long.MAX_VALUE ? 0 : Math.max(0, maxMoves - dist[e[0]]);
            long b = dist[e[1]] == Long.MAX_VALUE ? 0 : Math.max(0, maxMoves - dist[e[1]]);
            reached += (int) Math.min(e[2], a + b);    // subdivided nodes, capped
        }
        return reached;
    }
}
```

**Complexity** — Time `O(E log V)`, Space `O(V + E)`. **Edge cases:** unreachable endpoint contributes 0 from that side (guard `Long.MAX_VALUE`); `a + b` can exceed `cnt` so the `min` prevents over-counting the shared middle; `maxMoves = 0` reaches only node 0.

---

### Problem 24: Currency Arbitrage Detection (Bellman-Ford on −log, negative cycle) — Profitable cycle

**Statement.** Classic interview / SPOJ-style. Given `rate[i][j]` = units of currency `j` obtained per unit of currency `i`, determine whether an arbitrage opportunity exists: a cycle of conversions returning **more** of the starting currency than you began with.

**Constraints.** `1 ≤ n ≤ 100` currencies; rates are positive doubles.

**Approach.** A cycle is profitable when the product of rates `> 1`. Take negative logs: maximizing a product becomes minimizing a sum, and `product > 1 ⇔ Σ(−log rate) < 0`. So an arbitrage cycle is exactly a **negative-weight cycle** in the graph with edge weight `w(i,j) = −log(rate[i][j])`. Bellman-Ford detects it: relax all edges `V−1` times, then if any edge still relaxes on a `V`-th pass, a negative cycle (= arbitrage) exists. Dijkstra cannot be used — weights go negative.

```java
class Solution {
    public boolean hasArbitrage(double[][] rate) {
        int n = rate.length;
        double[][] w = new double[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                w[i][j] = -Math.log(rate[i][j]);

        double[] dist = new double[n];
        // Source-independent detection: start all at 0 (virtual super-source),
        // so any reachable negative cycle will be found.
        // dist already initialized to 0.0 for all nodes.

        for (int iter = 0; iter < n - 1; iter++)        // V-1 relaxation rounds
            for (int u = 0; u < n; u++)
                for (int v = 0; v < n; v++)
                    if (dist[u] + w[u][v] < dist[v] - 1e-9)
                        dist[v] = dist[u] + w[u][v];

        for (int u = 0; u < n; u++)                     // V-th round: still relaxable?
            for (int v = 0; v < n; v++)
                if (dist[u] + w[u][v] < dist[v] - 1e-9)
                    return true;                         // negative cycle = arbitrage
        return false;
    }
}
```

**Complexity** — Time `O(V³)` (dense, `E = V²`), Space `O(V²)`. **Edge cases:** use an epsilon (`1e-9`) to absorb floating-point noise so equal cycles aren't misreported; seeding all `dist[]=0` acts as a virtual super-source so the detection is source-independent; self-loops with rate 1 give weight 0 and never trigger.

---

### Problem 25: Network Delay — Brute-force Bellman-Ford vs Optimal Dijkstra — Approach comparison

**Statement.** Re-solve "signal broadcast" (the Network Delay setting): directed edges `times[i] = [u, v, w]`, source `k`, `n` nodes; return the time for the last node to receive the signal, or `-1`. Here we contrast two correct approaches and explain when each wins.

**Constraints.** `1 ≤ n ≤ 100`, up to `6000` edges, `1 ≤ w ≤ 100` (non-negative).

**Approach.** Two valid algorithms:

- **Bellman-Ford (brute force):** relax all `E` edges `V−1` times; simple, handles negative weights, but `O(V·E)`. Good baseline / fallback when weights might be negative.
- **Dijkstra (optimal here):** weights are non-negative, so the greedy min-heap settles each node once in `O(E log V)` — strictly better for this constraint. The progression to remember: *start with Bellman-Ford correctness, then exploit non-negativity to upgrade to Dijkstra.*

Below is the Bellman-Ford version (the Dijkstra version appears as Problem 3); they must agree on every non-negative instance.

```java
import java.util.*;

class Solution {
    public int networkDelayTime(int[][] times, int n, int k) {
        long INF = Long.MAX_VALUE / 4;
        long[] dist = new long[n + 1];
        Arrays.fill(dist, INF);
        dist[k] = 0;

        for (int iter = 1; iter < n; iter++) {       // V-1 rounds
            boolean changed = false;
            for (int[] t : times) {
                int u = t[0], v = t[1], w = t[2];
                if (dist[u] + w < dist[v]) {
                    dist[v] = dist[u] + w;
                    changed = true;
                }
            }
            if (!changed) break;                     // early exit: converged
        }

        long ans = 0;
        for (int i = 1; i <= n; i++) {
            if (dist[i] >= INF) return -1;
            ans = Math.max(ans, dist[i]);
        }
        return (int) ans;
    }
}
```

**Complexity** — Bellman-Ford Time `O(V·E)`, Space `O(V)`; Dijkstra Time `O(E log V)`. **Edge cases:** `INF/4` sentinel prevents overflow when adding `w`; the `changed` flag gives early termination once distances stabilize; an unreachable node keeps `INF` → return `-1`.

---

### Problem 26: Minimum Obstacle Removal to Reach Corner (0-1 BFS vs Dijkstra) — Weighted grid

**Statement.** LeetCode 2290 (Hard). Grid of `0` (empty) and `1` (obstacle). From `(0,0)` to `(m-1,n-1)`, moving 4-directionally, return the **minimum number of obstacles to remove** to make a path.

**Constraints.** `1 ≤ m, n ≤ 10⁵` with `m·n ≤ 10⁵`.

**Approach.** Entering an empty cell costs 0; entering an obstacle costs 1 (you remove it). Edge weights are in `{0,1}`, so **0-1 BFS** with a deque is optimal at `O(m·n)` — strictly better than a heap-based Dijkstra (`O(m·n·log(m·n))`), which would also be correct. Push 0-cost moves to the deque **front** and 1-cost moves to the **back** to keep it monotonic by cost. The first pop of the target gives the minimum removals.

```java
import java.util.*;

class Solution {
    public int minimumObstacles(int[][] grid) {
        int m = grid.length, n = grid[0].length;
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        int[][] cost = new int[m][n];
        for (int[] row : cost) Arrays.fill(row, Integer.MAX_VALUE);
        cost[0][0] = grid[0][0];

        Deque<int[]> dq = new ArrayDeque<>();   // {r, c}
        dq.offerFirst(new int[]{0, 0});

        while (!dq.isEmpty()) {
            int[] cur = dq.pollFirst();
            int r = cur[0], c = cur[1];
            if (r == m - 1 && c == n - 1) return cost[r][c];
            for (int[] d : dirs) {
                int nr = r + d[0], nc = c + d[1];
                if (nr < 0 || nr >= m || nc < 0 || nc >= n) continue;
                int w = grid[nr][nc];                     // 0 or 1
                if (cost[r][c] + w < cost[nr][nc]) {
                    cost[nr][nc] = cost[r][c] + w;
                    if (w == 0) dq.offerFirst(new int[]{nr, nc});
                    else        dq.offerLast(new int[]{nr, nc});
                }
            }
        }
        return cost[m - 1][n - 1];
    }
}
```

**Complexity** — Time `O(m·n)` (0-1 BFS), Space `O(m·n)`. **Edge cases:** start cell may itself be an obstacle (`cost[0][0] = grid[0][0]` accounts for it); single-cell grid returns `grid[0][0]`; the relaxation guard discards the duplicate deque entries 0-1 BFS naturally produces.

---

### Problem 27: Path With Maximum Minimum Value (Dijkstra max-heap / Union-Find) — Maximin path

**Statement.** LeetCode 1102 (Premium). In an `m x n` grid of values, the **score** of a path from `(0,0)` to `(m-1,n-1)` (4-directional) is the **minimum** cell value on it. Return the maximum achievable score.

**Constraints.** `1 ≤ m, n ≤ 100`, `0 ≤ grid[i][j] ≤ 10⁹`.

**Approach.** The dual of "Swim in Rising Water": there we minimized a path's maximum; here we **maximize a path's minimum** (a maximin / widest-path problem). Run a **max-heap Dijkstra**: the "distance" to a cell is the best (largest) achievable path-minimum to reach it. Pop the cell with the largest path-min first; relax with `min(curMin, neighborValue)`. The value when `(m-1,n-1)` is popped is the answer.

- **Alternative (Union-Find):** sort cells descending by value, activate them one by one, union with active neighbors; the answer is the value at which `(0,0)` and `(m-1,n-1)` first become connected. Also `O(mn log(mn))`.

```java
import java.util.*;

class Solution {
    public int maximumMinimumPath(int[][] grid) {
        int m = grid.length, n = grid[0].length;
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        int[][] best = new int[m][n];
        for (int[] row : best) Arrays.fill(row, -1);
        best[0][0] = grid[0][0];

        // max-heap on path-minimum: {pathMin, r, c}
        PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> b[0] - a[0]);
        pq.offer(new int[]{grid[0][0], 0, 0});

        while (!pq.isEmpty()) {
            int[] cur = pq.poll();
            int v = cur[0], r = cur[1], c = cur[2];
            if (r == m - 1 && c == n - 1) return v;     // first settle = answer
            if (v < best[r][c]) continue;               // stale
            for (int[] d : dirs) {
                int nr = r + d[0], nc = c + d[1];
                if (nr < 0 || nr >= m || nc < 0 || nc >= n) continue;
                int nv = Math.min(v, grid[nr][nc]);
                if (nv > best[nr][nc]) {
                    best[nr][nc] = nv;
                    pq.offer(new int[]{nv, nr, nc});
                }
            }
        }
        return -1;
    }
}
```

**Complexity** — Time `O(mn log(mn))`, Space `O(mn)`. **Edge cases:** `1x1` grid returns `grid[0][0]`; the start and target cell values both bound the answer (their min caps any path); max-heap ensures the first arrival at the target is optimal.

---

### Problem 28: Shortest Path Visiting All Nodes (BFS over bitmask state) — TSP-style traversal

**Statement.** LeetCode 847 (Hard). Given an undirected connected graph as an adjacency list (`graph[i]` = neighbors of `i`), return the length of the shortest walk that visits **every** node. You may start and end anywhere and revisit nodes and edges.

**Constraints.** `1 ≤ n ≤ 12`, the graph is connected.

**Approach.** Because nodes may be revisited, a plain BFS over nodes is insufficient — we must track **which nodes have been visited**. The state is `(currentNode, visitedMask)` where `visitedMask` is an `n`-bit set. Edges are unweighted (each move costs 1) → BFS over the `n · 2ⁿ` state space. Seed the queue with all `(i, 1<<i)` (start anywhere). The first time any state reaches `mask == (1<<n)-1` (all visited), the BFS depth is the answer. `n ≤ 12` keeps `12 · 4096 ≈ 49k` states tractable.

```
state = (node, mask).  Start from every node simultaneously.
goal mask = 1111...1 (all n bits set).  BFS depth = shortest walk length.
```

```java
import java.util.*;

class Solution {
    public int shortestPathLength(int[][] graph) {
        int n = graph.length;
        if (n == 1) return 0;
        int full = (1 << n) - 1;

        boolean[][] visited = new boolean[n][1 << n];
        Queue<int[]> q = new ArrayDeque<>();   // {node, mask}
        for (int i = 0; i < n; i++) {
            q.offer(new int[]{i, 1 << i});
            visited[i][1 << i] = true;
        }

        int steps = 0;
        while (!q.isEmpty()) {
            int sz = q.size();
            for (int s = 0; s < sz; s++) {
                int[] cur = q.poll();
                int node = cur[0], mask = cur[1];
                if (mask == full) return steps;
                for (int nei : graph[node]) {
                    int nmask = mask | (1 << nei);
                    if (!visited[nei][nmask]) {
                        visited[nei][nmask] = true;
                        q.offer(new int[]{nei, nmask});
                    }
                }
            }
            steps++;
        }
        return -1;  // unreachable given connectivity
    }
}
```

**Complexity** — Time `O(n² · 2ⁿ)` (each of `n·2ⁿ` states scans up to `n` neighbors), Space `O(n · 2ⁿ)`. **Edge cases:** `n == 1` returns 0; starting from every node avoids picking a wrong start; the per-state `visited` array (not per-node) is essential — the same node with different masks are distinct states.

---

### Problem 29: Minimum Cost to Reach Last Room II (Dijkstra with alternating step time) — Stateful move cost

**Statement.** LeetCode 3342 (Medium/Hard). Like "Reach the Last Room I," but moves **alternate cost**: the time to move to an adjacent room is `1` second, then `2`, then `1`, then `2`, … You may only start moving into room `(i,j)` at or after `moveTime[i][j]`. From `(0,0)` at time 0, return the minimum time to reach `(n-1,m-1)`.

**Constraints.** `2 ≤ n, m ≤ 750`, `0 ≤ moveTime[i][j] ≤ 10⁹`.

**Approach.** The move cost depends on **parity of steps taken so far**: even-numbered move costs 1, odd costs 2 (or vice versa — encode by `(r+c) % 2` since each move flips parity from `(0,0)`). Arrival at a neighbor is `max(currentTime, moveTime[nr][nc]) + stepCost`, where `stepCost = ((r+c) % 2 == 0) ? 1 : 2`. Arrival time is still monotonic, so Dijkstra holds. Use `long` for `10⁹` waits.

```java
import java.util.*;

class Solution {
    public int minTimeToReach(int[][] moveTime) {
        int n = moveTime.length, m = moveTime[0].length;
        long[][] best = new long[n][m];
        for (long[] row : best) Arrays.fill(row, Long.MAX_VALUE);
        best[0][0] = 0;

        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        PriorityQueue<long[]> pq = new PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
        pq.offer(new long[]{0, 0, 0});           // {time, r, c}

        while (!pq.isEmpty()) {
            long[] cur = pq.poll();
            long t = cur[0];
            int r = (int) cur[1], c = (int) cur[2];
            if (r == n - 1 && c == m - 1) return (int) t;
            if (t > best[r][c]) continue;
            int stepCost = ((r + c) % 2 == 0) ? 1 : 2;   // alternates with parity
            for (int[] d : dirs) {
                int nr = r + d[0], nc = c + d[1];
                if (nr < 0 || nr >= n || nc < 0 || nc >= m) continue;
                long arrive = Math.max(t, moveTime[nr][nc]) + stepCost;
                if (arrive < best[nr][nc]) {
                    best[nr][nc] = arrive;
                    pq.offer(new long[]{arrive, nr, nc});
                }
            }
        }
        return -1;
    }
}
```

**Complexity** — Time `O(n·m·log(n·m))`, Space `O(n·m)`. **Edge cases:** parity of `(r+c)` correctly encodes step count because every move changes `(r+c)` by `±1`; large `moveTime` forces waiting via `max`; `long` avoids overflow at `10⁹`.

---

### Problem 30: Find the Shortest Cycle in a Graph (BFS from every edge) — Girth

**Statement.** LeetCode 2608 (Hard). Given a bidirectional graph with `n` nodes and `edges`, return the length of the **shortest cycle** (girth), or `-1` if the graph is acyclic.

**Constraints.** `2 ≤ n ≤ 1000`, no repeated edges, no self-loops.

**Approach.** Unweighted graph, so BFS distances suffice. The standard girth technique: for **each edge `(u, v)`**, temporarily ignore that edge, BFS the shortest path from `u` to `v` without it, and `1 + that distance` is the smallest cycle through edge `(u,v)`. Taking the minimum over all edges gives the girth.

- **Simpler equivalent (per-source BFS):** BFS from each node; if during BFS you reach an already-visited node that is **not your parent**, you've closed a cycle of length `dist[cur] + dist[neighbor] + 1`. Minimize over all sources. This avoids removing edges and is what we implement below — `O(V·E)` total.

```java
import java.util.*;

class Solution {
    public int findShortestCycle(int n, int[][] edges) {
        List<Integer>[] adj = new List[n];
        for (int i = 0; i < n; i++) adj[i] = new ArrayList<>();
        for (int[] e : edges) { adj[e[0]].add(e[1]); adj[e[1]].add(e[0]); }

        int ans = Integer.MAX_VALUE;
        for (int start = 0; start < n; start++)
            ans = Math.min(ans, bfs(adj, n, start));
        return ans == Integer.MAX_VALUE ? -1 : ans;
    }

    private int bfs(List<Integer>[] adj, int n, int start) {
        int[] dist = new int[n];
        int[] par = new int[n];
        Arrays.fill(dist, -1);
        Arrays.fill(par, -1);
        dist[start] = 0;
        Queue<Integer> q = new ArrayDeque<>();
        q.offer(start);
        int best = Integer.MAX_VALUE;

        while (!q.isEmpty()) {
            int u = q.poll();
            for (int v : adj[u]) {
                if (dist[v] == -1) {                 // tree edge
                    dist[v] = dist[u] + 1;
                    par[v] = u;
                    q.offer(v);
                } else if (v != par[u]) {            // non-parent back edge → cycle
                    best = Math.min(best, dist[u] + dist[v] + 1);
                }
            }
        }
        return best;
    }
}
```

**Complexity** — Time `O(V · E)` (a BFS per source), Space `O(V)`. **Edge cases:** acyclic graph never closes a cycle → returns `-1`; multiple components are fine since each start node's BFS only explores its component; the `v != par[u]` guard prevents counting the immediate tree edge as a cycle.

---

### Problem 31: Modified Floyd-Warshall with Path Reconstruction (APSP + next-matrix) — All-pairs paths

**Statement.** Common follow-up to LC 1334-style problems. Given `n` nodes and weighted directed `edges` (no negative cycle), answer queries: print the actual shortest **path** (sequence of nodes) between any pair `(i, j)`, not just its cost.

**Constraints.** `1 ≤ n ≤ 400`; weights may be negative but no negative cycle.

**Approach.** Run Floyd-Warshall, but alongside `dist[i][j]` maintain a `next[i][j]` matrix: `next[i][j]` = the first node to step to when going from `i` toward `j`. Initialize `next[i][j] = j` for every direct edge. During relaxation, when a path through `k` improves `dist[i][j]`, set `next[i][j] = next[i][k]` (the route to `j` now starts the same way as the route to `k`). Reconstruct by repeatedly following `next` from `i` until reaching `j`.

```java
import java.util.*;

class Solution {
    static final int INF = 1_000_000_000;
    int[][] dist, next;

    public void build(int n, int[][] edges) {
        dist = new int[n][n];
        next = new int[n][n];
        for (int[] row : dist) Arrays.fill(row, INF);
        for (int[] row : next) Arrays.fill(row, -1);
        for (int i = 0; i < n; i++) { dist[i][i] = 0; next[i][i] = i; }
        for (int[] e : edges) {
            dist[e[0]][e[1]] = e[2];
            next[e[0]][e[1]] = e[1];
        }
        for (int k = 0; k < n; k++)
            for (int i = 0; i < n; i++)
                for (int j = 0; j < n; j++)
                    if (dist[i][k] != INF && dist[k][j] != INF
                            && dist[i][k] + dist[k][j] < dist[i][j]) {
                        dist[i][j] = dist[i][k] + dist[k][j];
                        next[i][j] = next[i][k];        // route to j starts like route to k
                    }
    }

    public List<Integer> path(int i, int j) {
        List<Integer> res = new ArrayList<>();
        if (next[i][j] == -1) return res;               // no path
        for (int at = i; at != j; at = next[at][j]) res.add(at);
        res.add(j);
        return res;
    }
}
```

**Complexity** — Build Time `O(n³)`, Space `O(n²)`; each path query `O(path length)`. **Edge cases:** `next[i][j] == -1` means unreachable → empty path; `i == j` returns `[i]`; the `dist[i][k] != INF` guard prevents sentinel overflow from forging phantom edges through unreachable nodes.

---

### Problem 32: Cheapest Flights Within K Stops — State-Dijkstra alternative (Dijkstra on `(node, stops)`) — Modeling

**Statement.** Re-solve LC 787 (cheapest price from `src` to `dst` using at most `k` stops) with a **different algorithm** than the Bellman-Ford solution shown earlier, to illustrate the "node = `(city, stopsUsed)`" state-expansion pattern.

**Constraints.** `1 ≤ n ≤ 100`, edges up to `n·(n−1)/2`, prices up to `10⁴`.

**Approach.** Expand the state to `(city, stopsUsed)` and run Dijkstra ordered by **accumulated cost**. From a popped state, only expand if `stopsUsed ≤ k`. Track the best cost seen per `(city, stops)` so we don't requeue worse states. Because the priority queue is ordered by cost, the first time we pop `dst` (with any valid stop count) we have the cheapest price. This trades the simple `O(k·E)` Bellman-Ford for a state-space Dijkstra — useful when you also want the *cheapest* among same-stop ties or need an early exit.

```java
import java.util.*;

class Solution {
    public int findCheapestPrice(int n, int[][] flights, int src, int dst, int k) {
        List<int[]>[] adj = new List[n];
        for (int i = 0; i < n; i++) adj[i] = new ArrayList<>();
        for (int[] f : flights) adj[f[0]].add(new int[]{f[1], f[2]});

        // minStopsAtCost is implicit; track best stops to reach each node
        int[] bestStops = new int[n];
        Arrays.fill(bestStops, Integer.MAX_VALUE);

        // {cost, city, stopsUsed}; ordered by cost
        PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> a[0] - b[0]);
        pq.offer(new int[]{0, src, 0});

        while (!pq.isEmpty()) {
            int[] cur = pq.poll();
            int cost = cur[0], u = cur[1], stops = cur[2];
            if (u == dst) return cost;                  // cost-ordered → cheapest
            if (stops > k) continue;                    // out of stop budget
            // prune: only proceed if this state reaches u with fewer stops than before
            if (stops >= bestStops[u]) continue;
            bestStops[u] = stops;
            for (int[] e : adj[u]) {
                pq.offer(new int[]{cost + e[1], e[0], stops + 1});
            }
        }
        return -1;
    }
}
```

**Complexity** — Time `O(E·K·log(...))` in the worst case (states bounded by stops), Space `O(V + E)`. **Edge cases:** `src == dst` returns 0 (popped first at cost 0); the `stops >= bestStops[u]` prune keeps the state space finite without it the queue can blow up; pruning on stops (not cost) is required because a costlier path with fewer stops may still enable a cheaper completion.

---

### Problem 33: Minimum Weighted Subgraph With Two Sources (Three Dijkstra runs) — Meeting point

**Statement.** LeetCode 2203 (Hard). Directed weighted graph with `n` nodes. Find the minimum total edge weight of a subgraph such that both `src1` and `src2` can reach a common `dest`. Return `-1` if impossible.

**Constraints.** `3 ≤ n ≤ 10⁵`, up to `10⁵` edges, weights up to `10⁵`.

**Approach.** The optimal subgraph is two paths `src1 → meet` and `src2 → meet` sharing the tail `meet → dest` (the shared portion is counted once). For every candidate meeting node `m`, total cost `= dist(src1, m) + dist(src2, m) + dist(m, dest)`. Compute three shortest-distance arrays:
1. `d1[]` = Dijkstra from `src1` on the graph,
2. `d2[]` = Dijkstra from `src2` on the graph,
3. `dr[]` = Dijkstra from `dest` on the **reversed** graph (= distances *to* dest).

Then minimize `d1[m] + d2[m] + dr[m]` over all `m`. Use `long` — three `10⁵`-weight paths can exceed `int`.

```
        src1 ──┐
               ├──► meet ──► dest        cost = d1[meet] + d2[meet] + dr[meet]
        src2 ──┘                          (shared meet→dest counted once)
```

```java
import java.util.*;

class Solution {
    public long minimumWeight(int n, int[][] edges, int src1, int src2, int dest) {
        List<long[]>[] g  = build(n, edges, false);
        List<long[]>[] gr = build(n, edges, true);   // reversed

        long[] d1 = dijkstra(g, src1, n);
        long[] d2 = dijkstra(g, src2, n);
        long[] dr = dijkstra(gr, dest, n);

        long ans = Long.MAX_VALUE;
        for (int m = 0; m < n; m++) {
            if (d1[m] == Long.MAX_VALUE || d2[m] == Long.MAX_VALUE || dr[m] == Long.MAX_VALUE)
                continue;
            ans = Math.min(ans, d1[m] + d2[m] + dr[m]);
        }
        return ans == Long.MAX_VALUE ? -1 : ans;
    }

    private List<long[]>[] build(int n, int[][] edges, boolean rev) {
        List<long[]>[] adj = new List[n];
        for (int i = 0; i < n; i++) adj[i] = new ArrayList<>();
        for (int[] e : edges) {
            if (!rev) adj[e[0]].add(new long[]{e[1], e[2]});
            else      adj[e[1]].add(new long[]{e[0], e[2]});
        }
        return adj;
    }

    private long[] dijkstra(List<long[]>[] adj, int s, int n) {
        long[] dist = new long[n];
        Arrays.fill(dist, Long.MAX_VALUE);
        dist[s] = 0;
        PriorityQueue<long[]> pq = new PriorityQueue<>((a, b) -> Long.compare(a[1], b[1]));
        pq.offer(new long[]{s, 0});
        while (!pq.isEmpty()) {
            long[] top = pq.poll();
            int u = (int) top[0]; long d = top[1];
            if (d > dist[u]) continue;
            for (long[] e : adj[u]) {
                long nd = d + e[1];
                if (nd < dist[(int) e[0]]) { dist[(int) e[0]] = nd; pq.offer(new long[]{e[0], nd}); }
            }
        }
        return dist;
    }
}
```

**Complexity** — Time `O(E log V)` (three Dijkstra runs), Space `O(V + E)`. **Edge cases:** the meeting node may equal `src1`, `src2`, or `dest` (handled — distance to self is 0); any of the three distances being `MAX_VALUE` means that meeting node is invalid; the shared tail is correctly counted once because `dr[m]` is added a single time.

---

### Problem 34: The Maze II — Shortest Stopping Distance (Dijkstra with rolling moves) — Sliding ball

**Statement.** LeetCode 505 (Medium). A ball in a maze (`0`=empty, `1`=wall) rolls until it hits a wall, only then can it change direction. Given `start` and `destination`, return the shortest **distance** (number of empty cells traversed) for the ball to stop at the destination, or `-1`.

**Constraints.** `1 ≤ m, n ≤ 100`.

**Approach.** Unlike a step-by-step grid BFS, each "move" rolls the ball multiple cells in one direction until a wall, so edge weights vary (= cells rolled) → **Dijkstra**, not plain BFS. Nodes are stopping positions. From a stop, try all 4 directions, roll to the next wall, and the edge weight is the number of cells traversed. Relax `dist[stop]` with `dist[cur] + rolled`. The ball must **stop** exactly at the destination (rolling through it doesn't count).

```java
import java.util.*;

class Solution {
    public int shortestDistance(int[][] maze, int[] start, int[] destination) {
        int m = maze.length, n = maze[0].length;
        int[][] dist = new int[m][n];
        for (int[] row : dist) Arrays.fill(row, Integer.MAX_VALUE);
        dist[start[0]][start[1]] = 0;
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};

        // {distance, r, c}
        PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> a[0] - b[0]);
        pq.offer(new int[]{0, start[0], start[1]});

        while (!pq.isEmpty()) {
            int[] cur = pq.poll();
            int d = cur[0], r = cur[1], c = cur[2];
            if (r == destination[0] && c == destination[1]) return d;
            if (d > dist[r][c]) continue;
            for (int[] dir : dirs) {
                int nr = r, nc = c, steps = 0;
                // roll until the next cell would be a wall or out of bounds
                while (nr + dir[0] >= 0 && nr + dir[0] < m
                        && nc + dir[1] >= 0 && nc + dir[1] < n
                        && maze[nr + dir[0]][nc + dir[1]] == 0) {
                    nr += dir[0]; nc += dir[1]; steps++;
                }
                if (d + steps < dist[nr][nc]) {
                    dist[nr][nc] = d + steps;
                    pq.offer(new int[]{d + steps, nr, nc});
                }
            }
        }
        return -1;
    }
}
```

**Complexity** — Time `O(m·n·log(m·n) · max(m,n))` (rolling adds a linear factor per relaxation), Space `O(m·n)`. **Edge cases:** start equals destination returns 0; the ball that can't stop on the destination (only passes through) is correctly excluded since only stopping cells are relaxed; a `steps == 0` roll (already against a wall) doesn't create progress.

---

### Problem 35: Second Minimum Time to Reach Destination (BFS with signal timing) — Second-shortest

**Statement.** LeetCode 2045 (Hard). A bidirectional graph of `n` nodes, all edges take `time` to traverse. Traffic signals flip green/red every `change` minutes (you wait if you arrive on red). Return the **second minimum** time to go from `1` to `n` (strictly greater than the minimum time).

**Constraints.** `2 ≤ n ≤ 10⁴`, edges up to `min(2·10⁴, n(n-1)/2)`, `1 ≤ time, change ≤ 10³`.

**Approach.** All edges equal weight → distances are determined by **number of edges**, so BFS layers give times. We need the second-shortest *distinct* arrival count at node `n`. Track two best edge-counts per node, `dist1[v]` (fewest edges) and `dist2[v]` (next strictly-greater). BFS allowing each node to be settled at most twice; the answer is the time corresponding to `dist2[n]` edges. Then convert edge-count to real time, **adding signal waits**: before each move, if the current time falls in a red window (`(t / change) % 2 == 1`), wait until the next green.

```java
import java.util.*;

class Solution {
    public int secondMinimum(int n, int[][] edges, int time, int change) {
        List<Integer>[] adj = new List[n + 1];
        for (int i = 1; i <= n; i++) adj[i] = new ArrayList<>();
        for (int[] e : edges) { adj[e[0]].add(e[1]); adj[e[1]].add(e[0]); }

        int[] dist1 = new int[n + 1], dist2 = new int[n + 1];
        Arrays.fill(dist1, Integer.MAX_VALUE);
        Arrays.fill(dist2, Integer.MAX_VALUE);
        dist1[1] = 0;

        Queue<int[]> q = new ArrayDeque<>();   // {node, edgeCount}
        q.offer(new int[]{1, 0});
        while (!q.isEmpty()) {
            int[] cur = q.poll();
            int u = cur[0], d = cur[1];
            for (int v : adj[u]) {
                int nd = d + 1;
                if (nd < dist1[v]) {            // new best
                    dist1[v] = nd;
                    q.offer(new int[]{v, nd});
                } else if (nd > dist1[v] && nd < dist2[v]) {   // strictly second best
                    dist2[v] = nd;
                    q.offer(new int[]{v, nd});
                }
            }
        }

        // convert dist2[n] edges to real time, accounting for red-light waits
        int t = 0;
        for (int i = 0; i < dist2[n]; i++) {
            if ((t / change) % 2 == 1) t = (t / change + 1) * change;  // wait for green
            t += time;
        }
        return t;
    }
}
```

**Complexity** — Time `O(V + E)` (each node settled at most twice), Space `O(V + E)`. **Edge cases:** because every edge has equal weight, the second-minimum edge count is always exactly `dist1[n] + 1` or `+2`, but tracking `dist2` generically is robust; the red-light formula `(t/change)%2==1` detects a red window; if no second path of distinct length exists in a connected graph, one always does for `n ≥ 2` per the problem guarantee.

---

## 🧩 Extended Problems — Supplemental: Medium → Expert

### Problem 36: Path with Minimum Cost in a Grid (Dijkstra on weighted cells)

**Statement.** LeetCode 2812. Given an `m x n` grid where each cell has a positive cost, find a 4-directional path from `(0,0)` to `(m-1,n-1)` minimizing the **sum** of cell costs entered (including both endpoints). The grid may be huge.

**Constraints.** `1 ≤ m, n ≤ 100`, `1 ≤ grid[i][j] ≤ 10⁵`.

**Approach.** Cells with positive weights → classic Dijkstra where edge weight to enter cell `(r,c)` equals `grid[r][c]`. Seed `dist[0][0] = grid[0][0]` because the source cell counts. Pop the smallest-cost frontier and relax neighbors with `dist[r][c] + grid[nr][nc]`.

```
+---+---+---+        path cost = 1 + 2 + 4 = 7
| 1 | 2 | 9 |        best path: (0,0)→(0,1)→(1,1)
+---+---+---+
| 5 | 4 | 1 |
+---+---+---+
```

```java
import java.util.*;

class Solution {
    public int minPathCost(int[][] grid) {
        int m = grid.length, n = grid[0].length;
        int[][] dist = new int[m][n];
        for (int[] row : dist) Arrays.fill(row, Integer.MAX_VALUE);
        dist[0][0] = grid[0][0];

        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        // {cost, r, c}
        PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> a[0] - b[0]);
        pq.offer(new int[]{grid[0][0], 0, 0});

        while (!pq.isEmpty()) {
            int[] cur = pq.poll();
            int d = cur[0], r = cur[1], c = cur[2];
            if (r == m - 1 && c == n - 1) return d;
            if (d > dist[r][c]) continue;
            for (int[] dd : dirs) {
                int nr = r + dd[0], nc = c + dd[1];
                if (nr < 0 || nr >= m || nc < 0 || nc >= n) continue;
                int nd = d + grid[nr][nc];
                if (nd < dist[nr][nc]) {
                    dist[nr][nc] = nd;
                    pq.offer(new int[]{nd, nr, nc});
                }
            }
        }
        return -1;
    }
}
```

**Complexity** — Time `O(m·n·log(m·n))`, Space `O(m·n)`. **Edge cases:** `1x1` grid returns `grid[0][0]`; both endpoints included; positive weights guarantee Dijkstra correctness without negative-edge concerns.

---

### Problem 37: Minimum Score of a Path Between Two Cities (Union-Find / BFS on components) — Bottleneck path

**Statement.** LeetCode 2492. Given an undirected weighted graph with `n` cities and `roads[i] = [a, b, dist]`, the **score** of a path from city 1 to city `n` is the **minimum** edge weight on it. Return the minimum possible score among all paths (a path may revisit nodes/edges).

**Constraints.** `2 ≤ n ≤ 10⁵`, up to `10⁵` roads, weights up to `10⁴`.

**Approach.** Since paths may revisit edges, the answer is simply the **smallest edge weight in the entire connected component containing city 1**. (You can always detour through the smallest edge in your component and come back.) So: BFS/DFS the component of node 1, track the minimum weight of any edge encountered. No actual shortest-path computation needed — this problem looks shortest-path-flavored but reduces to component scanning.

```java
import java.util.*;

class Solution {
    public int minScore(int n, int[][] roads) {
        List<int[]>[] adj = new List[n + 1];
        for (int i = 1; i <= n; i++) adj[i] = new ArrayList<>();
        for (int[] r : roads) {
            adj[r[0]].add(new int[]{r[1], r[2]});
            adj[r[1]].add(new int[]{r[0], r[2]});
        }

        boolean[] visited = new boolean[n + 1];
        Queue<Integer> q = new ArrayDeque<>();
        q.offer(1);
        visited[1] = true;
        int best = Integer.MAX_VALUE;

        while (!q.isEmpty()) {
            int u = q.poll();
            for (int[] e : adj[u]) {
                best = Math.min(best, e[1]);       // track minimum edge in component
                if (!visited[e[0]]) {
                    visited[e[0]] = true;
                    q.offer(e[0]);
                }
            }
        }
        return best;
    }
}
```

**Complexity** — Time `O(V + E)`, Space `O(V + E)`. **Edge cases:** component containing 1 may not include `n` — the problem guarantees connectivity; we still report the min edge regardless of whether `n` is reachable; even self-loops or duplicates handled naturally since we just track the smallest edge weight observed.

---

### Problem 38: Word Ladder II — Enumerate All Shortest Sequences (BFS + DFS backtrack) — All shortest paths

**Statement.** LeetCode 126 (Hard). Given `beginWord`, `endWord`, and `wordList`, return **all** the shortest transformation sequences from begin to end (each step changes one letter and stays in the dict). Empty list if none.

**Constraints.** word length ≤ 5, `wordList.length` ≤ 500.

**Approach.** Two phases. (1) **BFS layer by layer** from `beginWord`, building a `parents` map: for each word `w`, list its predecessors on a shortest path. Within a layer, remove a word from the dict only **after** the whole layer is processed, so multiple parents (equal-distance predecessors) are recorded. (2) **DFS backtrack** from `endWord` through `parents` to enumerate every shortest path; reverse and emit.

```java
import java.util.*;

class Solution {
    public List<List<String>> findLadders(String beginWord, String endWord, List<String> wordList) {
        List<List<String>> res = new ArrayList<>();
        Set<String> dict = new HashSet<>(wordList);
        if (!dict.contains(endWord)) return res;
        dict.remove(beginWord);

        Map<String, List<String>> parents = new HashMap<>();
        Set<String> level = new HashSet<>();
        level.add(beginWord);
        boolean found = false;

        while (!level.isEmpty() && !found) {
            Set<String> next = new HashSet<>();
            for (String word : level) {
                char[] chars = word.toCharArray();
                for (int p = 0; p < chars.length; p++) {
                    char original = chars[p];
                    for (char ch = 'a'; ch <= 'z'; ch++) {
                        if (ch == original) continue;
                        chars[p] = ch;
                        String nxt = new String(chars);
                        if (dict.contains(nxt)) {
                            parents.computeIfAbsent(nxt, x -> new ArrayList<>()).add(word);
                            next.add(nxt);
                            if (nxt.equals(endWord)) found = true;
                        }
                    }
                    chars[p] = original;
                }
            }
            dict.removeAll(next);       // remove AFTER layer completes
            level = next;
        }

        if (found) {
            List<String> path = new ArrayList<>();
            path.add(endWord);
            dfs(endWord, beginWord, parents, path, res);
        }
        return res;
    }

    private void dfs(String word, String begin, Map<String, List<String>> parents,
                     List<String> path, List<List<String>> res) {
        if (word.equals(begin)) {
            List<String> copy = new ArrayList<>(path);
            Collections.reverse(copy);
            res.add(copy);
            return;
        }
        if (!parents.containsKey(word)) return;
        for (String par : parents.get(word)) {
            path.add(par);
            dfs(par, begin, parents, path, res);
            path.remove(path.size() - 1);
        }
    }
}
```

**Complexity** — Time exponential in the number of shortest paths (output-sensitive); BFS itself `O(W·L·26)`. Space `O(W·L)` for `parents`. **Edge cases:** end not in dict → empty; multiple disjoint shortest sequences enumerated; removing words only after a layer keeps multi-parent links alive.

---

### Problem 39: Shortest Path to Get All Keys (BFS over bitmask state) — Keys-and-doors

**Statement.** LeetCode 864 (Hard). A grid contains `@` (start), `.` (empty), `#` (wall), lowercase letters (keys), uppercase (locks). Collect **all** keys in fewest steps; a lock can be traversed only if you hold the corresponding key. Return the steps, or `-1`.

**Constraints.** `1 ≤ m, n ≤ 30`, at most 6 keys.

**Approach.** State is `(row, col, keyMask)`. Each key is one bit (`a→0, b→1, ...`). All moves cost 1 → BFS over the product state space. From `(r,c,mask)`, try 4 neighbors: skip walls; if it's a lock and the corresponding bit is unset, skip; if it's a key, OR its bit into the mask. Stop when `mask == fullKeysMask`. The bitmask state is essential — same cell with different key sets is a distinct, possibly unexplored state.

```java
import java.util.*;

class Solution {
    public int shortestPathAllKeys(String[] grid) {
        int m = grid.length, n = grid[0].length();
        int sr = 0, sc = 0, full = 0;
        for (int i = 0; i < m; i++)
            for (int j = 0; j < n; j++) {
                char ch = grid[i].charAt(j);
                if (ch == '@') { sr = i; sc = j; }
                else if (ch >= 'a' && ch <= 'f') full |= 1 << (ch - 'a');
            }

        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        boolean[][][] visited = new boolean[m][n][1 << 6];
        Queue<int[]> q = new ArrayDeque<>();  // {r, c, mask}
        q.offer(new int[]{sr, sc, 0});
        visited[sr][sc][0] = true;
        int steps = 0;

        while (!q.isEmpty()) {
            int sz = q.size();
            for (int s = 0; s < sz; s++) {
                int[] cur = q.poll();
                int r = cur[0], c = cur[1], mask = cur[2];
                if (mask == full) return steps;
                for (int[] d : dirs) {
                    int nr = r + d[0], nc = c + d[1];
                    if (nr < 0 || nr >= m || nc < 0 || nc >= n) continue;
                    char ch = grid[nr].charAt(nc);
                    if (ch == '#') continue;
                    int nmask = mask;
                    if (ch >= 'A' && ch <= 'F' && (mask & (1 << (ch - 'A'))) == 0) continue;
                    if (ch >= 'a' && ch <= 'f') nmask |= 1 << (ch - 'a');
                    if (!visited[nr][nc][nmask]) {
                        visited[nr][nc][nmask] = true;
                        q.offer(new int[]{nr, nc, nmask});
                    }
                }
            }
            steps++;
        }
        return -1;
    }
}
```

**Complexity** — Time `O(m·n·2^k)` where `k = #keys ≤ 6`, Space `O(m·n·2^k)`. **Edge cases:** zero keys → answer 0; start cell counts as visited with mask 0; locks unreachable without the right key naturally never expand into a "stuck" mask.

---

### Problem 40: Number of Restricted Paths from First to Last Node (Dijkstra + DP on DAG) — Counting with order

**Statement.** LeetCode 1786 (Medium-Hard). Undirected weighted graph; a **restricted path** from `1` to `n` only visits nodes with strictly decreasing `distanceToLastNode`. Count restricted paths from `1` to `n` modulo `10⁹+7`.

**Constraints.** `2 ≤ n ≤ 2·10⁴`, up to `4·10⁴` edges, weights `1..10⁴`.

**Approach.** Two phases. (1) Run **Dijkstra from `n`** to get `dist[i]` = shortest distance to the last node. (2) The "strictly decreasing" rule turns the graph into an implicit DAG (edges only from larger-`dist` nodes to smaller-`dist` ones). Sort nodes by `dist` ascending and DP: `ways[i] = sum(ways[j])` over neighbors `j` of `i` with `dist[j] < dist[i]`. Base: `ways[n] = 1`. Answer: `ways[1] % MOD`. The Dijkstra + DAG-DP pattern is a standard "count paths along shortest-distance order" trick.

```java
import java.util.*;

class Solution {
    public int countRestrictedPaths(int n, int[][] edges) {
        long MOD = 1_000_000_007L;
        List<long[]>[] adj = new List[n + 1];
        for (int i = 1; i <= n; i++) adj[i] = new ArrayList<>();
        for (int[] e : edges) {
            adj[e[0]].add(new long[]{e[1], e[2]});
            adj[e[1]].add(new long[]{e[0], e[2]});
        }

        long[] dist = new long[n + 1];
        Arrays.fill(dist, Long.MAX_VALUE);
        dist[n] = 0;
        PriorityQueue<long[]> pq = new PriorityQueue<>((a, b) -> Long.compare(a[1], b[1]));
        pq.offer(new long[]{n, 0});
        while (!pq.isEmpty()) {
            long[] top = pq.poll();
            int u = (int) top[0]; long d = top[1];
            if (d > dist[u]) continue;
            for (long[] e : adj[u]) {
                long nd = d + e[1];
                if (nd < dist[(int) e[0]]) {
                    dist[(int) e[0]] = nd;
                    pq.offer(new long[]{e[0], nd});
                }
            }
        }

        // process nodes in ascending dist[] order
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i + 1;
        Arrays.sort(order, (a, b) -> Long.compare(dist[a], dist[b]));

        long[] ways = new long[n + 1];
        ways[n] = 1;
        for (int u : order) {
            if (u == n) continue;
            for (long[] e : adj[u]) {
                int v = (int) e[0];
                if (dist[v] < dist[u]) {            // strictly decreasing
                    ways[u] = (ways[u] + ways[v]) % MOD;
                }
            }
        }
        return (int) ways[1];
    }
}
```

**Complexity** — Time `O(E log V + V log V)`, Space `O(V + E)`. **Edge cases:** if 1 and n are disconnected, `ways[1] = 0`; ties in `dist[]` don't matter because the DP only adds from strictly smaller neighbors; modular arithmetic guards against overflow.

---

### Problem 41: Minimum Number of Operations to Convert Time (BFS on string state) — Implicit graph

**Statement.** LeetCode 2224. Given `current` and `correct` times as `"HH:MM"`, in one operation you can add 1, 5, 15, or 60 minutes to `current`. Return the minimum operations to make `current == correct`.

**Constraints.** Both times are valid `HH:MM`, `current ≤ correct` within a day.

**Approach.** Plain greedy works (peel off 60s, then 15s, then 5s, then 1s) but a generic shortest-path lens is illuminating: each state is the current time (in minutes since midnight). Edges add `{1, 5, 15, 60}` with unit cost. BFS from `current` to `correct` returns the fewest operations. With ≤ 1440 states this is trivially fast. The BFS view generalizes naturally to "non-divisible coin sets" where greedy fails.

```java
import java.util.*;

class Solution {
    public int convertTime(String current, String correct) {
        int a = toMinutes(current), b = toMinutes(correct);
        int diff = b - a;
        // greedy is optimal because {60, 15, 5, 1} is a canonical coin system
        int ops = 0;
        for (int coin : new int[]{60, 15, 5, 1}) {
            ops += diff / coin;
            diff %= coin;
        }
        return ops;
    }

    // BFS version (general, works for any coin set):
    public int convertTimeBFS(String current, String correct) {
        int a = toMinutes(current), b = toMinutes(correct);
        int[] coins = {1, 5, 15, 60};
        int[] dist = new int[b - a + 1];
        Arrays.fill(dist, -1);
        dist[0] = 0;
        Queue<Integer> q = new ArrayDeque<>();
        q.offer(0);
        while (!q.isEmpty()) {
            int x = q.poll();
            if (x == b - a) return dist[x];
            for (int c : coins) {
                int nx = x + c;
                if (nx <= b - a && dist[nx] == -1) {
                    dist[nx] = dist[x] + 1;
                    q.offer(nx);
                }
            }
        }
        return -1;
    }

    private int toMinutes(String s) {
        return Integer.parseInt(s.substring(0, 2)) * 60 + Integer.parseInt(s.substring(3));
    }
}
```

**Complexity** — Greedy `O(1)`; BFS `O((b−a) · |coins|)`. Space `O(b−a)`. **Edge cases:** `current == correct` returns 0; greedy works because the coin set is canonical — if the set were arbitrary (e.g., `{1,3,4}`), only the BFS / DP version is safe.

---

### Problem 42: Frog Jump (BFS / DP on stone+lastJump state) — State expansion

**Statement.** LeetCode 403 (Hard). A frog starts at stone `0` and must reach the last stone in `stones[]` (sorted ascending). If the last jump was `k`, the next jump must be `k-1`, `k`, or `k+1` (and positive). Return whether the frog can reach the last stone.

**Constraints.** `2 ≤ stones.length ≤ 2000`, `0 ≤ stones[i] ≤ 2³¹−1`, first stone is 0, second is 1.

**Approach.** State is `(stoneIndex, lastJumpSize)`. BFS (or DFS with memo) explores reachable states. From `(i, k)`, try jumps of `k-1, k, k+1`; each lands on `stones[i] + jump` if that value exists (use a `HashMap<value, index>`). Reach last stone → true. Pure reachability question, but state expansion is the shortest-path generalization — replace with BFS layers to get the **fewest** jumps.

```java
import java.util.*;

class Solution {
    public boolean canCross(int[] stones) {
        Map<Integer, Integer> pos = new HashMap<>();
        for (int i = 0; i < stones.length; i++) pos.put(stones[i], i);
        int last = stones[stones.length - 1];

        // state = (stone value, last jump). BFS.
        Set<Long> seen = new HashSet<>();
        Queue<int[]> q = new ArrayDeque<>();
        q.offer(new int[]{0, 0});             // at stone 0 with 0 prior jump
        seen.add(0L);

        while (!q.isEmpty()) {
            int[] cur = q.poll();
            int s = cur[0], k = cur[1];
            for (int dk = -1; dk <= 1; dk++) {
                int j = k + dk;
                if (j <= 0) continue;          // must move forward
                int ns = s + j;
                if (!pos.containsKey(ns)) continue;
                if (ns == last) return true;
                long key = ((long) ns << 16) | j;
                if (seen.add(key)) q.offer(new int[]{ns, j});
            }
        }
        return false;
    }
}
```

**Complexity** — Time `O(n²)` (each stone × at most `n` jump sizes), Space `O(n²)`. **Edge cases:** second stone must be at index 1 (problem guarantees this for the first jump = 1); jumps must be strictly positive (`j <= 0` skip); if `stones[1] != 1` no first move possible → returns false naturally.

---

### Problem 43: Minimum Time to Visit All Points (Chebyshev distance) — Closed-form shortest path

**Statement.** LeetCode 1266. Given a list of integer points, return the minimum time to visit all in order; each second you can move 1 unit in 8 directions (including diagonals).

**Constraints.** `1 ≤ points.length ≤ 100`, coordinates in `[-1000, 1000]`.

**Approach.** Between two points `(x1,y1)` and `(x2,y2)`, with 8-directional unit moves the minimum steps is the **Chebyshev distance**: `max(|x2-x1|, |y2-y1|)`. (Diagonal moves let you advance both axes simultaneously, so the slower axis dictates the cost.) Sum the pairwise distances. This is the BFS-on-grid problem reduced to a closed form once you recognize the metric.

```
8-directional 1-step neighborhood:    Chebyshev distance:
  D . D                                d((x1,y1),(x2,y2))
  . O .                                = max(|x2-x1|, |y2-y1|)
  D . D                                "octile" metric
```

```java
class Solution {
    public int minTimeToVisitAllPoints(int[][] points) {
        int total = 0;
        for (int i = 1; i < points.length; i++) {
            int dx = Math.abs(points[i][0] - points[i - 1][0]);
            int dy = Math.abs(points[i][1] - points[i - 1][1]);
            total += Math.max(dx, dy);
        }
        return total;
    }
}
```

**Complexity** — Time `O(n)`, Space `O(1)`. **Edge cases:** single point returns 0; negative coordinates handled by `Math.abs`; visiting in order means we never optimize the route (no TSP).

---

### Problem 44: Cycle Length Queries in a Tree (LCA shortest path) — Tree pathfinding

**Statement.** LeetCode 2509 (Hard). A complete binary tree with `2^n − 1` nodes labeled 1..N (parent of `i` is `i/2`). For each query `[a, b]`, add an edge between `a` and `b` and return the length of the resulting cycle.

**Constraints.** `1 ≤ n ≤ 30`, up to `10⁵` queries.

**Approach.** The cycle length equals 1 + the shortest path between `a` and `b` in the tree. In a heap-indexed binary tree, the shortest path between two nodes goes through their **LCA**: repeatedly replace the larger of `a, b` with its parent (`a/2` or `b/2`), counting steps, until they meet. Total cycle length = `steps + 1` (for the new added edge). No BFS needed — the heap-index structure gives O(log N) per query.

```java
class Solution {
    public int[] cycleLengthQueries(int n, int[][] queries) {
        int[] res = new int[queries.length];
        for (int i = 0; i < queries.length; i++) {
            int a = queries[i][0], b = queries[i][1];
            int steps = 1;                    // edge we add
            while (a != b) {
                if (a > b) a /= 2;
                else b /= 2;
                steps++;
            }
            res[i] = steps;
        }
        return res;
    }
}
```

**Complexity** — Time `O(Q · n)` (each query at most `O(depth) = O(n)`), Space `O(Q)` for output. **Edge cases:** `a == b` would give a self-loop cycle of length 1 (problem guarantees `a != b`); `n` up to 30 keeps the depth bounded; works for any heap-indexed tree, not just perfect ones.

---

### Problem 45: Detonate Maximum Bombs (BFS reachability on geometric graph) — Geometric SSSP

**Statement.** LeetCode 2101 (Medium). Each bomb has center `(x, y)` and radius `r`. Detonating bomb `i` ignites every bomb `j` whose center lies within `r_i` of `i`'s center, recursively. Return the maximum number of bombs you can detonate by choosing one to start.

**Constraints.** `1 ≤ n ≤ 100`, coordinates and radii fit in `int`.

**Approach.** Build a **directed** graph: edge `i → j` exists if `dist(i, j) ≤ r_i` (note: directed because the inverse may not hold for differing radii). For each bomb as a starting node, BFS/DFS to count how many bombs become reachable; track the maximum. Use squared distances to avoid floating-point comparison (`dx² + dy² ≤ r²`). Use `long` for the squared sums — coordinates up to `~10⁵` make `dx²` up to `10¹⁰`, blowing `int`.

```java
import java.util.*;

class Solution {
    public int maximumDetonation(int[][] bombs) {
        int n = bombs.length;
        List<Integer>[] adj = new List[n];
        for (int i = 0; i < n; i++) adj[i] = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                long dx = bombs[i][0] - bombs[j][0];
                long dy = bombs[i][1] - bombs[j][1];
                long r = bombs[i][2];
                if (dx * dx + dy * dy <= r * r) adj[i].add(j);
            }
        }

        int best = 0;
        for (int start = 0; start < n; start++) {
            boolean[] visited = new boolean[n];
            Queue<Integer> q = new ArrayDeque<>();
            q.offer(start);
            visited[start] = true;
            int count = 0;
            while (!q.isEmpty()) {
                int u = q.poll();
                count++;
                for (int v : adj[u]) if (!visited[v]) { visited[v] = true; q.offer(v); }
            }
            best = Math.max(best, count);
        }
        return best;
    }
}
```

**Complexity** — Time `O(n³)` (`n²` to build + `n` BFS each `O(n + E)` where `E ≤ n²`), Space `O(n²)`. **Edge cases:** single bomb returns 1; identical positions still respect radius rule; `long` is essential — `int` overflow silently miscomputes adjacency for distant pairs.

---

### Problem 46: Escape the Spreading Fire (Binary search + BFS) — Adversarial timing

**Statement.** LeetCode 2258 (Hard). On a grid, `0`=empty, `1`=fire, `2`=wall. Each minute, fire spreads to 4-adjacent empty cells. You start at `(0,0)` and want to reach `(m-1,n-1)`. Return the maximum minutes you can **stay put before moving** while still safely reaching the goal (you arrive exactly when fire would also arrive — that's allowed only at the goal). Return `-1` if impossible, `10⁹` if you can wait forever.

**Constraints.** `2 ≤ m, n ≤ 300`.

**Approach.** Two ingredients. (1) **Multi-source BFS from all fires** to compute `fireTime[r][c]`. (2) **Binary search** on the wait time `t`: simulate yourself starting at minute `t` (you arrive at cell `(r,c)` at minute `t + bfsDist((0,0),(r,c))`); you may enter only cells where fire arrives **strictly after** you — except the goal, where ties are allowed. The largest valid `t` is the answer. Check `t = 10⁹` to detect "infinite wait."

```java
import java.util.*;

class Solution {
    int m, n;
    int[][] fireTime;
    int[][] grid;
    int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};

    public int maximumMinutes(int[][] g) {
        grid = g;
        m = g.length; n = g[0].length;
        fireTime = new int[m][n];
        for (int[] row : fireTime) Arrays.fill(row, Integer.MAX_VALUE);

        // multi-source BFS from all fire cells
        Queue<int[]> q = new ArrayDeque<>();
        for (int i = 0; i < m; i++)
            for (int j = 0; j < n; j++)
                if (g[i][j] == 1) { fireTime[i][j] = 0; q.offer(new int[]{i, j}); }
        while (!q.isEmpty()) {
            int[] c = q.poll();
            for (int[] d : dirs) {
                int nr = c[0] + d[0], nc = c[1] + d[1];
                if (nr < 0 || nr >= m || nc < 0 || nc >= n) continue;
                if (g[nr][nc] != 0) continue;
                if (fireTime[nr][nc] != Integer.MAX_VALUE) continue;
                fireTime[nr][nc] = fireTime[c[0]][c[1]] + 1;
                q.offer(new int[]{nr, nc});
            }
        }

        // binary search on wait time
        int lo = 0, hi = m * n, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (canEscape(mid)) { ans = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        return ans == m * n ? 1_000_000_000 : ans;
    }

    private boolean canEscape(int wait) {
        int[][] arrive = new int[m][n];
        for (int[] row : arrive) Arrays.fill(row, Integer.MAX_VALUE);
        arrive[0][0] = wait;
        if (fireTime[0][0] <= wait) return false;
        Queue<int[]> q = new ArrayDeque<>();
        q.offer(new int[]{0, 0});
        while (!q.isEmpty()) {
            int[] c = q.poll();
            int t = arrive[c[0]][c[1]] + 1;
            for (int[] d : dirs) {
                int nr = c[0] + d[0], nc = c[1] + d[1];
                if (nr < 0 || nr >= m || nc < 0 || nc >= n) continue;
                if (grid[nr][nc] != 0) continue;
                if (arrive[nr][nc] <= t) continue;
                boolean isGoal = (nr == m - 1 && nc == n - 1);
                if (isGoal ? fireTime[nr][nc] < t : fireTime[nr][nc] <= t) continue;
                arrive[nr][nc] = t;
                if (isGoal) return true;
                q.offer(new int[]{nr, nc});
            }
        }
        return false;
    }
}
```

**Complexity** — Time `O(m·n·log(m·n))` (binary search × BFS), Space `O(m·n)`. **Edge cases:** fire can't reach goal → infinite wait, return `10⁹`; goal allows tie with fire (the `<` vs `<=` distinction is critical); walls neither catch fire nor are walkable.

---

### Problem 47: Find Edges in the Shortest Paths (Dijkstra forward + backward) — Edge-on-some-SP

**Statement.** LeetCode 3123 (Hard). Undirected weighted graph; for each edge, report whether it lies on **at least one** shortest path between node 0 and node `n-1`.

**Constraints.** `2 ≤ n ≤ 5·10⁴`, up to `min(5·10⁴, n(n-1)/2)` edges, weights up to `10⁵`.

**Approach.** An edge `(u, v, w)` lies on some shortest 0→(n-1) path iff `dist0[u] + w + distN[v] == bestTotal` or `dist0[v] + w + distN[u] == bestTotal`, where `dist0` is Dijkstra from `0` and `distN` is Dijkstra from `n-1`. Run both, then test each edge in `O(1)`. If `dist0[n-1] == ∞`, no path exists and every edge gets `false`. This is the canonical "is-edge-on-any-shortest-path" pattern — pivotal for routing-engine debug tools.

```java
import java.util.*;

class Solution {
    public boolean[] findAnswer(int n, int[][] edges) {
        List<long[]>[] adj = new List[n];
        for (int i = 0; i < n; i++) adj[i] = new ArrayList<>();
        for (int i = 0; i < edges.length; i++) {
            int[] e = edges[i];
            adj[e[0]].add(new long[]{e[1], e[2], i});
            adj[e[1]].add(new long[]{e[0], e[2], i});
        }

        long[] d0 = dijkstra(adj, 0, n);
        long[] dN = dijkstra(adj, n - 1, n);
        boolean[] res = new boolean[edges.length];
        long best = d0[n - 1];
        if (best == Long.MAX_VALUE) return res;     // no path → all false

        for (int i = 0; i < edges.length; i++) {
            int u = edges[i][0], v = edges[i][1];
            long w = edges[i][2];
            if (d0[u] != Long.MAX_VALUE && dN[v] != Long.MAX_VALUE
                    && d0[u] + w + dN[v] == best) { res[i] = true; continue; }
            if (d0[v] != Long.MAX_VALUE && dN[u] != Long.MAX_VALUE
                    && d0[v] + w + dN[u] == best) { res[i] = true; }
        }
        return res;
    }

    private long[] dijkstra(List<long[]>[] adj, int s, int n) {
        long[] dist = new long[n];
        Arrays.fill(dist, Long.MAX_VALUE);
        dist[s] = 0;
        PriorityQueue<long[]> pq = new PriorityQueue<>((a, b) -> Long.compare(a[1], b[1]));
        pq.offer(new long[]{s, 0});
        while (!pq.isEmpty()) {
            long[] top = pq.poll();
            int u = (int) top[0]; long d = top[1];
            if (d > dist[u]) continue;
            for (long[] e : adj[u]) {
                long nd = d + e[1];
                if (nd < dist[(int) e[0]]) {
                    dist[(int) e[0]] = nd;
                    pq.offer(new long[]{e[0], nd});
                }
            }
        }
        return dist;
    }
}
```

**Complexity** — Time `O(E log V)` (two Dijkstras), Space `O(V + E)`. **Edge cases:** disconnected source/sink → return all-false; an undirected edge is checked in both orientations; multiple parallel edges with different weights are each evaluated independently.

---

### Problem 48: K-th Shortest Path (Modified Dijkstra / Eppstein's idea) — Multi-shortest

**Statement.** Given a directed weighted graph and integers `src`, `dst`, `k`, return the cost of the `k`-th shortest path (paths may repeat nodes/edges) from `src` to `dst`, or `-1` if fewer than `k` paths exist. (Variant of LC 2386 "Find the K-Sum of an Array" applied to graphs; classic competitive problem.)

**Constraints.** `1 ≤ n ≤ 1000`, edges up to `10⁵`, `k ≤ 200`, weights up to `10⁴`.

**Approach.** The clean generalization of Dijkstra: keep a min-heap of `(cost, node)` and a counter `cnt[v]` = how many times `v` has been popped. Pop the smallest, increment `cnt[v]`; the `k`-th time `dst` is popped, that is the answer. To bound work, skip popping `v` once `cnt[v] == k` (we can never need a `k+1`-th arrival at any intermediate). This is the well-known "K-shortest paths via repeated Dijkstra-style relaxation" trick — simple, correct, and `O(k·E·log)`.

```
heap pops at dst:  1st = shortest, 2nd = second shortest, ..., k-th = answer
each intermediate node may legitimately be popped up to k times because
distinct paths to dst may share prefixes through it.
```

```java
import java.util.*;

class Solution {
    public long kthShortestPath(int n, int[][] edges, int src, int dst, int k) {
        List<long[]>[] adj = new List[n];
        for (int i = 0; i < n; i++) adj[i] = new ArrayList<>();
        for (int[] e : edges) adj[e[0]].add(new long[]{e[1], e[2]});

        int[] cnt = new int[n];
        PriorityQueue<long[]> pq = new PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
        pq.offer(new long[]{0L, src});           // {cost, node}

        while (!pq.isEmpty()) {
            long[] top = pq.poll();
            long cost = top[0];
            int u = (int) top[1];
            if (cnt[u] == k) continue;           // already popped k times — skip
            cnt[u]++;
            if (u == dst && cnt[u] == k) return cost;
            for (long[] e : adj[u]) {
                pq.offer(new long[]{cost + e[1], e[0]});
            }
        }
        return -1;                                // fewer than k paths exist
    }
}
```

**Complexity** — Time `O(k · E · log(k · E))`, Space `O(k · V + E)`. **Edge cases:** `k = 1` degenerates to standard Dijkstra; multiple paths of equal cost each consume one "pop" of `dst`; if the graph has no cycle between `src` and `dst`, fewer than `k` paths may exist → loop drains and returns `-1`.

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
