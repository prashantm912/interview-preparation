# Minimum Spanning Tree (Prim & Kruskal)

A **Minimum Spanning Tree (MST)** is the cheapest set of edges that connects every node of a weighted, undirected graph without forming a cycle. It is one of the most reusable graph patterns in interviews: the moment a problem says *"connect everything at minimum total cost,"* you are almost certainly looking at an MST. This guide covers the cut property, both classic algorithms (Prim and Kruskal), a Borůvka teaser, and the LeetCode problems that dress MST up in disguise.

[← Back to master index](../README.md) · [← DSA index](README.md)

---

## Concept & Intuition

Given a connected, undirected graph `G = (V, E)` with a weight `w(u, v)` on each edge, a **spanning tree** is a subset of `V−1` edges that touches all `V` vertices and contains no cycle. Among all possible spanning trees, the **minimum** one has the smallest total edge weight. (If the graph is disconnected you get a *minimum spanning forest* — one tree per connected component.)

Two facts make MSTs feel almost magical:

- A spanning tree of a connected graph always has exactly `V − 1` edges. Add one more edge and you create a cycle; remove one and you disconnect it.
- Greedy works. Unlike most graph problems, you do **not** need DP or backtracking — a locally cheapest choice, made carefully, is provably globally optimal.

### The Cut Property (why greedy is correct)

A **cut** partitions the vertices into two non-empty sets `(S, V−S)`. An edge **crosses** the cut if its endpoints land on opposite sides.

> **Cut Property:** For any cut, the minimum-weight edge crossing it is safe — it belongs to *some* MST.

Both Prim and Kruskal are just two strategies for repeatedly applying this property:

- **Prim** grows one tree. The cut is "vertices already in the tree" vs. "everyone else," and it always grabs the cheapest crossing edge.
- **Kruskal** considers edges globally cheapest-first; each accepted edge is the min crossing some cut between the two components it joins.

There is also a dual, the **Cycle Property**: the maximum-weight edge in any cycle is *never* in an MST (you could always drop it). Kruskal implicitly uses this — it rejects an edge precisely when adding it would close a cycle.

```
   Cut property in action (S = {A,B}, rest = {C,D,E})

        A ---2--- B
        |         |
        4         3      crossing edges: (A,C)=4, (B,C)=3, (B,D)=7
        |         |      cheapest crossing edge = (B,C)=3  --> SAFE, add it
        C ---5--- D ---1--- E
                   \____7___/
```

**When to use MST**

| Need | Reach for |
|------|-----------|
| Connect all nodes / points at minimum total cost | MST (Prim or Kruskal) |
| Graph is dense (E ≈ V²), or given as an adjacency matrix | Prim with a binary/Fibonacci heap |
| Graph is sparse (E ≈ V), edges given as a list | Kruskal with union-find |
| Need MST built incrementally as edges arrive | Kruskal / union-find |
| Massively parallel / distributed setting | Borůvka |
| Cluster `n` points into `k` groups | Build MST, remove the `k−1` heaviest edges |

**Key invariants**

- The set of chosen edges is always a forest (acyclic) and only ever merges components.
- After `V − 1` successful additions you are done; you never need to look further.
- MST weight is unique even when the tree itself is not (ties can yield multiple distinct MSTs of equal total weight).

---

## Complexity Cheat-Sheet

`V` = vertices, `E` = edges. For a simple connected graph `V − 1 ≤ E ≤ V(V−1)/2`.

| Operation / Algorithm | Time | Space | Notes |
|-----------------------|------|-------|-------|
| Prim (binary heap / PQ) | O(E log V) | O(V + E) | Like Dijkstra; best for sparse graphs |
| Prim (adjacency matrix, no heap) | O(V²) | O(V²) | Best for **dense** graphs |
| Prim (Fibonacci heap) | O(E + V log V) | O(V + E) | Theoretical optimum, rarely coded |
| Kruskal (sort + union-find) | O(E log E) = O(E log V) | O(V + E) | Dominated by the sort |
| Union-Find `find` / `union` | ~O(α(V)) amortized | O(V) | α = inverse Ackermann, effectively ≤ 4 |
| Borůvka | O(E log V) | O(V + E) | O(log V) rounds, easy to parallelize |
| Build MST then query weight | O(1) after build | O(V) | Store running total during build |

Since `E ≤ V²`, `log E ≤ 2 log V`, so `O(E log E)` and `O(E log V)` are the same complexity class.

---

## Patterns & Recognition

Train yourself to spot these signals:

1. **"Minimum cost to connect / link / join all ___."** Cities, points, islands, computers, houses — if every entity must end up in one connected component and you minimize total cost, it is an MST.
2. **Undirected + weighted + connectivity goal.** MST is undirected only. If the problem is directed (e.g., minimum arborescence), it is *not* a plain MST — that is Chu–Liu/Edmonds.
3. **A complete graph implied by geometry.** "Cost to connect two points = Manhattan distance" gives you `V²/2` implicit edges → think Prim (dense) or Kruskal on generated edges.
4. **A "virtual node" trick.** "Build a well at a house (cost `w[i]`) OR lay a pipe between houses." Model the well as an edge from a virtual node 0 to house `i` with weight `w[i]`, then run MST over `n+1` nodes. (See *Optimize Water Distribution*.)
5. **Clustering / max-spacing.** "Divide into k clusters maximizing the minimum inter-cluster distance" → build MST, cut the `k−1` largest edges.
6. **"Critical / pseudo-critical edges."** Asks which edges appear in every MST vs. some MST → repeated MST builds with edges forced/excluded.

**Choosing the algorithm in the room:**

- Edges given as a list and graph is sparse → **Kruskal** (least code, just sort + DSU).
- Dense graph or adjacency matrix → **Prim** (`O(V²)` matrix version or PQ version).
- Both are correct everywhere; pick based on edge density and what is easiest to implement under pressure. Kruskal’s union-find is usually the fastest to write correctly.

---

## Coding Problems

### Problem 1: Min Cost to Connect All Points (LeetCode 1584)

**Statement.** You are given `n` points on a 2D plane, `points[i] = [xi, yi]`. The cost to connect two points is their Manhattan distance `|xi−xj| + |yi−yj|`. Return the minimum cost to connect all points so that there is exactly one path between any two points.

**Constraints.** `1 ≤ n ≤ 1000`, `−10^6 ≤ xi, yi ≤ 10^6`, all points distinct.

**Approach.**
- *Brute force:* enumerate all spanning trees — exponential, infeasible.
- *Optimal:* this is a complete graph with `n(n−1)/2` implicit edges. With `n ≤ 1000`, that is ~500k edges — fine for either algorithm. **Prim with a PQ** is natural because we never need to materialize all edges at once; we lazily push neighbors as we expand the tree.

```java
import java.util.*;

class Solution {
    public int minCostConnectPoints(int[][] points) {
        int n = points.length;
        if (n <= 1) return 0;

        boolean[] inMST = new boolean[n];
        // PQ entries: {cost, pointIndex}
        PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> a[0] - b[0]);
        pq.offer(new int[]{0, 0});            // start from point 0, cost 0
        int total = 0, used = 0;

        while (used < n) {
            int[] top = pq.poll();
            int cost = top[0], u = top[1];
            if (inMST[u]) continue;           // stale entry, skip
            inMST[u] = true;
            total += cost;
            used++;
            // push edges from u to every not-yet-included point
            for (int v = 0; v < n; v++) {
                if (!inMST[v]) {
                    int d = Math.abs(points[u][0] - points[v][0])
                          + Math.abs(points[u][1] - points[v][1]);
                    pq.offer(new int[]{d, v});
                }
            }
        }
        return total;
    }
}
```

**Dry-run.** Points `[[0,0],[2,2],[3,10],[5,2],[7,0]]`. Start at point 0, cost 0. Cheapest crossing edge is 0→1 (dist 4). From {0,1} the cheapest to a new point is 1→3 (dist 3). From {0,1,3} cheapest is 3→4 (dist 5), then 1→2 (dist 9). Total = 0+4+3+5+9 = 20.

**Time:** O(V² log V) here because we push up to `V` edges per pop (≈ E = V²). **Space:** O(V) for `inMST` plus O(E) worst-case for the PQ.

**Follow-ups.** Switch to the `O(V²)` matrix Prim to drop the log factor for this dense case. What if edges are Euclidean (sqrt)? Compare squared distances? No — sqrt is monotonic so order is preserved, but the *total* must use real distances. How would you handle 10^6 points (→ spatial structures, Delaunay triangulation reduces candidate edges to O(n))?

---

### Problem 2: Connecting Cities With Minimum Cost (LeetCode 1135)

**Statement.** There are `n` cities labeled `1..n`. `connections[i] = [a, b, cost]` means connecting `a` and `b` costs `cost`. Return the minimum cost to connect all cities; if impossible, return `−1`.

**Constraints.** `1 ≤ n ≤ 10^4`, `1 ≤ connections.length ≤ 10^4`.

**Approach.**
- The graph is given as an **edge list** and is sparse → **Kruskal + union-find** is the textbook fit.
- Sort edges by cost, union endpoints if they are in different components, accumulate cost. If we end with fewer than `n−1` accepted edges, the graph was disconnected → return `−1`.

```java
import java.util.*;

class Solution {
    private int[] parent, rank_;

    public int minimumCost(int n, int[][] connections) {
        parent = new int[n + 1];
        rank_  = new int[n + 1];
        for (int i = 1; i <= n; i++) parent[i] = i;

        Arrays.sort(connections, (a, b) -> a[2] - b[2]);

        int total = 0, edgesUsed = 0;
        for (int[] e : connections) {
            if (union(e[0], e[1])) {          // joined two components
                total += e[2];
                if (++edgesUsed == n - 1) break;
            }
        }
        return edgesUsed == n - 1 ? total : -1;
    }

    private int find(int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];    // path halving
            x = parent[x];
        }
        return x;
    }

    private boolean union(int a, int b) {
        int ra = find(a), rb = find(b);
        if (ra == rb) return false;           // would create a cycle
        if (rank_[ra] < rank_[rb]) { int t = ra; ra = rb; rb = t; }
        parent[rb] = ra;
        if (rank_[ra] == rank_[rb]) rank_[ra]++;
        return true;
    }
}
```

**Dry-run.** `n=3`, `connections=[[1,2,5],[1,3,6],[2,3,1]]`. Sorted: `(2,3,1),(1,2,5),(1,3,6)`. Take (2,3,1) → join {2,3}, total 1, edges 1. Take (1,2,5) → join {1} with {2,3}, total 6, edges 2 = n−1 → stop. Answer 6.

**Time:** O(E log E) for the sort; union-find ops are near-constant. **Space:** O(V) for the DSU arrays.

**Follow-ups.** What if some cities are already connected for free (pre-union them)? What if you must keep a specific edge in the result (force-union it first, add its cost)? Return the actual edges used, not just the cost.

---

### Problem 3: Optimize Water Distribution in a Village (LeetCode 1168)

**Statement.** There are `n` houses. You can either build a well in house `i` at cost `wells[i]`, or lay a pipe between houses `i` and `j` at cost `pipes[k] = [i, j, cost]` (1-indexed houses). Every house must have water. Return the minimum total cost.

**Constraints.** `1 ≤ n ≤ 10^4`, `0 ≤ wells.length = n`, `0 ≤ pipes.length ≤ 10^4`.

**Approach.** This is the classic **virtual node** trick. A well is "water arriving from outside." Add a virtual node `0`; connect it to house `i` with an edge of weight `wells[i]`. Now *every* source of water — well or pipe — is an edge, and the cheapest way to give all `n` houses water while staying connected is just the MST over `n + 1` nodes.

```java
import java.util.*;

class Solution {
    private int[] parent;

    public int minCostToSupplyWater(int n, int[] wells, int[][] pipes) {
        // Build edge list including virtual node 0 -> house i with cost wells[i]
        List<int[]> edges = new ArrayList<>();
        for (int i = 0; i < n; i++) edges.add(new int[]{0, i + 1, wells[i]});
        for (int[] p : pipes) edges.add(new int[]{p[0], p[1], p[2]});

        edges.sort((a, b) -> a[2] - b[2]);

        parent = new int[n + 1];
        for (int i = 0; i <= n; i++) parent[i] = i;

        int total = 0, edgesUsed = 0;
        for (int[] e : edges) {
            int ra = find(e[0]), rb = find(e[1]);
            if (ra != rb) {
                parent[ra] = rb;
                total += e[2];
                if (++edgesUsed == n) break;  // n edges connect n+1 nodes
            }
        }
        return total;
    }

    private int find(int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }
}
```

**Dry-run.** `n=3`, `wells=[1,2,2]`, `pipes=[[1,2,1],[2,3,1]]`. Virtual edges: (0,1,1),(0,2,2),(0,3,2). Sorted cheapest: (0,1,1)=1, (1,2,1)=1, (2,3,1)=1, … Take (0,1,1): house 1 gets a well, total 1. Take (1,2,1): connect 1–2, total 2. Take (2,3,1): connect 2–3, total 3. All 3 houses watered → answer **3**.

**Time:** O((n + P) log(n + P)). **Space:** O(n + P).

**Follow-ups.** Why the virtual node and not "min(well, cheapest pipe)" greedily per house? Because a house can be cheaper to supply *via* a neighbor that itself has a well. What if a house can hold at most one well total (still fine — at most one virtual edge is chosen per component)? Multiple water sources of different types → multiple virtual nodes.

---

### Problem 4: Find Critical and Pseudo-Critical Edges in an MST (LeetCode 1489)

**Statement.** Given a weighted undirected connected graph with `n` nodes and an edge list, find all **critical** edges (present in *every* MST) and **pseudo-critical** edges (present in *at least one* MST). Return them as indices into the original edge list.

**Constraints.** `2 ≤ n ≤ 100`, `1 ≤ edges.length ≤ min(200, n(n−1)/2)`.

**Approach.** Senior-level. First compute the MST weight `base`. Then for each edge:
- **Critical** if *excluding* it forces the MST weight to increase (or makes the graph disconnected).
- **Pseudo-critical** (and not critical) if *forcing* it into the tree first still yields weight `base`.

We run Kruskal `O(E)` times — fine because `E ≤ 200`.

```java
import java.util.*;

class Solution {
    public List<List<Integer>> findCriticalAndPseudoCriticalEdges(int n, int[][] edges) {
        int m = edges.length;
        Integer[] order = new Integer[m];
        for (int i = 0; i < m; i++) order[i] = i;
        // sort edge indices by weight, keep original index for the answer
        Arrays.sort(order, (a, b) -> edges[a][2] - edges[b][2]);

        int base = kruskal(n, edges, order, -1, -1);
        List<Integer> critical = new ArrayList<>(), pseudo = new ArrayList<>();

        for (int i = 0; i < m; i++) {
            // exclude edge i: if weight grows or disconnects -> critical
            if (kruskal(n, edges, order, i, -1) > base) {
                critical.add(i);
            } else if (kruskal(n, edges, order, -1, i) == base) {
                // force edge i first; same weight -> appears in some MST
                pseudo.add(i);
            }
        }
        return Arrays.asList(critical, pseudo);
    }

    // skip = edge index to forbid, force = edge index to include first
    private int kruskal(int n, int[][] edges, Integer[] order, int skip, int force) {
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        int total = 0, count = 0;

        if (force != -1) {
            total += edges[force][2];
            union(parent, edges[force][0], edges[force][1]);
            count++;
        }
        for (int idx : order) {
            if (idx == skip || idx == force) continue;
            int u = edges[idx][0], v = edges[idx][1];
            if (find(parent, u) != find(parent, v)) {
                union(parent, u, v);
                total += edges[idx][2];
                if (++count == n - 1) break;
            }
        }
        return count == n - 1 ? total : Integer.MAX_VALUE; // disconnected
    }

    private int find(int[] p, int x) {
        while (p[x] != x) { p[x] = p[p[x]]; x = p[x]; }
        return x;
    }
    private void union(int[] p, int a, int b) { p[find(p, a)] = find(p, b); }
}
```

**Dry-run (idea).** Compute `base`. For an edge whose removal disconnects the graph (a bridge) or strictly raises the total, it must be in every MST → critical. For a non-critical edge, force it in; if you can still reach `base`, it lives in *some* MST → pseudo-critical. Edges that are neither are always replaceable by something cheaper.

**Time:** O(E² · α(V)) — `E` Kruskal runs, each `O(E α)` after one shared sort. **Space:** O(V + E).

**Follow-ups.** Why does "exclude raises weight" capture criticality but "force keeps weight" capture pseudo-criticality? What about parallel edges of equal weight (ties)? How would you speed this up using Tarjan’s bridge/2-edge-connectivity ideas on the MST?

---

### Problem 5: Maximum Spacing k-Clustering (MST-based clustering)

**Statement.** You are given `n` items and pairwise distances `edges[i] = [a, b, dist]`. Partition the items into exactly `k` clusters so that the **spacing** — the minimum distance between any two points in different clusters — is **maximized**. Return that maximum spacing.

**Constraints.** Treat as a connected graph; `2 ≤ k ≤ n ≤ 10^4`.

**Approach.** A beautiful application of the cut/cycle property. Run Kruskal but **stop early**: keep merging the closest pair of clusters until exactly `k` clusters remain. The next edge Kruskal *would* have added — the cheapest edge still crossing between two of those `k` clusters — is precisely the maximum spacing. (Equivalently: build the full MST and delete its `k−1` heaviest edges; the largest deleted edge is the spacing.)

```java
import java.util.*;

class Solution {
    private int[] parent;

    public int maximumSpacing(int n, int k, int[][] edges) {
        parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        Arrays.sort(edges, (a, b) -> a[2] - b[2]);

        int clusters = n;
        for (int[] e : edges) {
            int ra = find(e[0]), rb = find(e[1]);
            if (ra != rb) {
                if (clusters == k) {
                    // first edge crossing two of the k clusters = spacing
                    return e[2];
                }
                parent[ra] = rb;
                clusters--;
            }
        }
        return -1; // fewer than k clusters possible (graph too connected)
    }

    private int find(int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
}
```

**Dry-run.** 5 points, want `k=2`. Merge cheapest pairs until 2 clusters remain: say after merges we have clusters `{0,1,2}` and `{3,4}`. The next inter-cluster edge Kruskal inspects has weight 8 — that is the closest any two different-cluster points get, so maximum spacing = 8.

**Time:** O(E log E). **Space:** O(V).

**Follow-ups.** Why is greedily merging the closest pair optimal (exchange argument on the cut property)? How does this relate to single-linkage hierarchical clustering (it *is* single-linkage cut at level k)? Outliers can chain clusters together — how would k-means differ?

---

### Problem 6: Number of MSTs / Connect Components with Two Sources, and a Borůvka build (Hard / senior)

**Statement.** Given a connected weighted undirected graph (`V` nodes, edge list), return the total weight of an MST **and** the number of connected components if you only keep edges with weight `≤ T` (a threshold query). Implement the MST using **Borůvka’s algorithm** so it parallelizes naturally.

**Constraints.** `1 ≤ V ≤ 10^5`, `1 ≤ E ≤ 2·10^5`, distinct edge weights assumed (simplifies Borůvka tie-handling).

**Approach.** Borůvka proceeds in **rounds**. In each round every current component finds its single cheapest outgoing edge, then all those edges are added at once (each merges at least two components, so the component count at least halves → `O(log V)` rounds). With distinct weights there is never a cycle conflict. After the MST is built, a threshold query is a one-pass count of components formed by edges `≤ T` over a fresh DSU.

```java
import java.util.*;

class Solution {
    private int[] parent, rank_;

    public long boruvkaMST(int V, int[][] edges) {
        parent = new int[V];
        rank_  = new int[V];
        for (int i = 0; i < V; i++) parent[i] = i;

        long total = 0;
        int components = V;

        while (components > 1) {
            // cheapest[c] = index of cheapest edge leaving component c (-1 = none)
            int[] cheapest = new int[V];
            Arrays.fill(cheapest, -1);

            for (int i = 0; i < edges.length; i++) {
                int ru = find(edges[i][0]), rv = find(edges[i][1]);
                if (ru == rv) continue;            // internal edge
                int w = edges[i][2];
                if (cheapest[ru] == -1 || w < edges[cheapest[ru]][2]) cheapest[ru] = i;
                if (cheapest[rv] == -1 || w < edges[cheapest[rv]][2]) cheapest[rv] = i;
            }

            boolean merged = false;
            for (int c = 0; c < V; c++) {
                int idx = cheapest[c];
                if (idx == -1) continue;
                int ru = find(edges[idx][0]), rv = find(edges[idx][1]);
                if (ru != rv) {
                    union(ru, rv);
                    total += edges[idx][2];
                    components--;
                    merged = true;
                }
            }
            if (!merged) break;                    // graph was disconnected
        }
        return total;
    }

    /** Count components using only edges with weight <= T (threshold query). */
    public int componentsAtThreshold(int V, int[][] edges, int T) {
        int[] p = new int[V];
        for (int i = 0; i < V; i++) p[i] = i;
        int comp = V;
        for (int[] e : edges) {
            if (e[2] > T) continue;
            int ra = findArr(p, e[0]), rb = findArr(p, e[1]);
            if (ra != rb) { p[ra] = rb; comp--; }
        }
        return comp;
    }

    private int find(int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
    private void union(int a, int b) {
        int ra = find(a), rb = find(b);
        if (ra == rb) return;
        if (rank_[ra] < rank_[rb]) { int t = ra; ra = rb; rb = t; }
        parent[rb] = ra;
        if (rank_[ra] == rank_[rb]) rank_[ra]++;
    }
    private int findArr(int[] p, int x) {
        while (p[x] != x) { p[x] = p[p[x]]; x = p[x]; }
        return x;
    }
}
```

**Dry-run.** Graph with edges `(0,1,1),(1,2,2),(0,2,3),(2,3,4)`. Round 1: component {0} picks edge 1 (w=1), {1} picks w=1, {2} picks w=2, {3} picks w=4. Adding the distinct cheapest edges merges {0,1}, then {1,2}, then {2,3}. After round 1 we may already be down to 1–2 components; a second round (if needed) adds the remaining cheapest crossing edge. Total = 1 + 2 + 4 = 7. A threshold query `T=2` keeps edges `(0,1),(1,2)` → components {0,1,2} and {3} → answer 2.

**Time:** O(E log V) — `O(log V)` rounds, each scanning all edges. **Space:** O(V). Borůvka’s round structure is what makes it the basis of parallel/distributed MST (GPU, MapReduce).

**Follow-ups.** How do you break ties safely in Borůvka without distinct weights (tie-break by edge index to avoid two components each "claiming" the other and double-adding)? Combine Borůvka’s first few rounds with Prim/Kruskal for the famous near-linear randomized MST. How would you answer many threshold queries efficiently (sort once, offline union-find / Kruskal reconstruction tree a.k.a. *Kruskal tree*)?

---

## Interview Q&A by Level

### 🟢 Basic

**Q: What is a spanning tree, and how many edges does it have?**
A connected, acyclic subgraph that touches every vertex. For `V` vertices it has exactly `V − 1` edges.

**Q: What makes a spanning tree *minimum*?**
Among all spanning trees, it has the smallest total edge weight. The tree itself may not be unique, but the minimum total weight is.

**Q: Name the two classic MST algorithms and their core data structure.**
Prim (grow one tree, uses a priority queue), Kruskal (sort all edges, uses union-find / disjoint set union).

**Q: Does edge direction matter for MST?**
No — MST is defined on undirected graphs. The directed analogue (minimum spanning arborescence) needs Chu–Liu/Edmonds, not Prim/Kruskal.

### 🟡 Intermediate

**Q: State the cut property and why it justifies greedy.**
For any partition of vertices into two non-empty sets, the cheapest edge crossing the partition is in some MST. Both algorithms repeatedly add such safe edges, so greedy never makes a regrettable choice.

**Q: When do you prefer Prim over Kruskal?**
Dense graphs (`E ≈ V²`) or adjacency-matrix input favor Prim (the `O(V²)` matrix version avoids sorting all `V²` edges). Sparse graphs given as edge lists favor Kruskal.

**Q: How is Prim related to Dijkstra?**
Same skeleton: a PQ keyed by a "best edge to reach this vertex" value, lazy deletion of stale entries. The difference is the key — Prim uses the *single edge weight* to the frontier, Dijkstra uses the *cumulative path distance* from the source.

**Q: How does Kruskal detect a cycle?**
Before adding an edge `(u, v)`, it checks `find(u) == find(v)`. If both endpoints are already in the same component, adding the edge would close a cycle, so it is skipped (cycle property).

### 🟠 Advanced

**Q: Give the amortized cost of union-find operations and why.**
With both *union by rank/size* and *path compression*, a sequence of `m` operations on `n` elements runs in `O(m · α(n))`, where α is the inverse Ackermann function — effectively ≤ 4 for any realistic input. Either optimization alone gives `O(log n)`; together they give near-constant.

**Q: How do you handle a disconnected graph?**
You get a minimum spanning *forest*. In Kruskal, fewer than `V − 1` edges are accepted → detect and report (e.g., return −1 in "connect all cities"). In Prim, the PQ empties before all vertices are reached.

**Q: Are MSTs unique?**
The total weight is unique. The tree is unique **iff** all edge weights are distinct. With ties, multiple MSTs of equal weight can exist — which is exactly what the critical/pseudo-critical problem probes.

**Q: How does MST enable clustering?**
Build the MST and remove the `k − 1` heaviest edges; the remaining `k` components are the clusters. This is single-linkage hierarchical clustering and it maximizes the minimum inter-cluster spacing.

### 🔴 Expert

**Q: What is the best known asymptotic complexity for MST?**
Prim with a Fibonacci heap is `O(E + V log V)`. Chazelle’s deterministic algorithm runs in `O(E α(E, V))`. Karger–Klein–Tarjan give an expected-linear `O(E)` randomized algorithm (using Borůvka steps plus random sampling). In practice Kruskal/Prim with a binary heap dominate because the constants are tiny.

**Q: Why is Borůvka the basis for parallel MST?**
Each round is embarrassingly parallel: every component independently finds its cheapest outgoing edge, and merges happen in bulk. With `O(log V)` rounds it maps cleanly onto GPUs and MapReduce, unlike Prim’s inherently sequential frontier growth.

**Q: How would you answer "is edge e in every MST / some MST / no MST" quickly across many queries?**
Build a *Kruskal reconstruction tree* (a.k.a. MST/min-bottleneck tree). The heaviest edge on the tree path between `u` and `v` is the bottleneck; an edge `(u,v,w)` is in some MST iff `w` equals that bottleneck, in every MST iff it is the unique min crossing edge of its cut. Combined with LCA this answers each query in `O(log V)`.

**Q: How do you scale MST to graphs that don’t fit in memory (billions of edges)?**
External-memory / streaming Borůvka, or distributed frameworks: partition edges, run local Borůvka contractions, shuffle contracted graphs, repeat. The semi-streaming model maintains a spanning forest in `O(V log V)` space using a single pass and union-find.

---

## ⚠️ Common Pitfalls

- **Forgetting the disconnected case.** Always count accepted edges; if `< V − 1`, the MST does not exist. Returning a partial total silently is a classic bug.
- **Stale PQ entries in Prim.** When you find a cheaper edge to a vertex, you *push* a new entry rather than decrease-key. You must skip already-finalized vertices (`if (inMST[u]) continue;`) or you double-count.
- **Union-find without optimizations.** Skipping path compression *and* union by rank degrades `find` to `O(V)`, turning Kruskal into `O(EV)`. Always include at least one (ideally both).
- **Off-by-one on node labels.** "Cities 1..n" vs. 0-indexed arrays — size your DSU `n + 1` or remap. The virtual-node trick (node 0) also needs `n + 1` slots.
- **Comparator overflow.** `(a, b) -> a[2] - b[2]` overflows if weights span the full int range. Use `Integer.compare(a[2], b[2])` when weights can be large or negative.
- **Assuming MST handles negative weights specially.** It doesn’t need to — MST is correct with negative edge weights (unlike shortest-path algorithms). No special handling required.
- **Confusing MST with shortest-path tree.** The MST minimizes *total* edge weight; it does **not** minimize the distance between any specific pair of nodes. Do not use an MST to answer shortest-path queries.
- **Borůvka double-counting on ties.** With equal weights, two components can each pick the same edge or pick each other; tie-break deterministically (by edge index) and re-check `find` before merging.

---

## 📚 Further Reading

- *Introduction to Algorithms* (CLRS), Chapter 23 — Minimum Spanning Trees, with full proofs of the cut and cycle properties.
- *Algorithms* by Sedgewick & Wayne, Chapter 4.3 — superb lazy/eager Prim and Kruskal implementations and visualizations.
- *Algorithm Design* by Kleinberg & Tardos — the exchange-argument correctness proofs and the k-clustering application.
- Tarjan, *Data Structures and Network Algorithms* — the original union-find amortized analysis.
- Karger, Klein, Tarjan (1995), "A Randomized Linear-Time Algorithm to Find Minimum Spanning Trees" — the expected-linear result.
- LeetCode practice set: 1584 (Connect All Points), 1135 (Connect Cities), 1168 (Water Distribution), 1489 (Critical/Pseudo-Critical Edges), 1319 (Make Network Connected), 684/685 (Redundant Connection — union-find warm-ups).
- cp-algorithms.com — concise write-ups of Prim, Kruskal, Borůvka, and DSU with code.

[← Back to master index](../README.md) · [← DSA index](README.md)
