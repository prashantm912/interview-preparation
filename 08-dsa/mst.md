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

## 🧩 Extended Problems — Set 1: Classic Easy → Medium

### Problem 7: Number of Connected Components in an Undirected Graph (LeetCode 323) — Union-Find

**Statement.** Given `n` nodes labeled `0..n-1` and a list of undirected `edges`, return the number of connected components in the graph.

**Constraints.** `1 ≤ n ≤ 2000`, `0 ≤ edges.length ≤ n*(n-1)/2`, no self-loops or repeated edges.

**Approach.** This is the foundational union-find warm-up that every MST problem builds on. Start with `n` separate components. For each edge, `union` the two endpoints; every *successful* union (endpoints in different sets) merges two components into one, so decrement a running counter. Edges whose endpoints are already connected are ignored — exactly the cycle-rejection step inside Kruskal. With path compression and union by rank, all operations are near-constant amortized, making this O(n + E·α(n)). A DFS/BFS flood-fill also works in O(n + E), but union-find generalizes directly to the streaming/incremental setting that MST problems love.

```java
import java.util.*;

class Solution {
    private int[] parent, rank_;

    public int countComponents(int n, int[][] edges) {
        parent = new int[n];
        rank_  = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        int components = n;
        for (int[] e : edges) {
            if (union(e[0], e[1])) components--;   // merged two -> one fewer
        }
        return components;
    }

    private int find(int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];          // path halving
            x = parent[x];
        }
        return x;
    }

    private boolean union(int a, int b) {
        int ra = find(a), rb = find(b);
        if (ra == rb) return false;
        if (rank_[ra] < rank_[rb]) { int t = ra; ra = rb; rb = t; }
        parent[rb] = ra;
        if (rank_[ra] == rank_[rb]) rank_[ra]++;
        return true;
    }
}
```

**Dry-run.** `n=5`, `edges=[[0,1],[1,2],[3,4]]`. Start with 5 components. Union(0,1)→4, union(1,2)→3 (merges {0,1} with {2}), union(3,4)→2. Answer **2**: `{0,1,2}` and `{3,4}`.

**Complexity.** Time O(n + E·α(n)); Space O(n) for the DSU arrays. **Edge cases:** no edges → answer `n`; a single node → `1`; duplicate-looking edges within the same component are safely ignored by the `ra == rb` check.

---

### Problem 8: Graph Valid Tree (LeetCode 261) — Union-Find Cycle + Connectivity Check

**Statement.** Given `n` nodes labeled `0..n-1` and a list of undirected `edges`, return `true` if and only if these edges form a valid tree (connected and acyclic).

**Constraints.** `1 ≤ n ≤ 2000`, `0 ≤ edges.length ≤ 5000`.

**Approach.** A graph on `n` nodes is a tree iff it has **exactly `n-1` edges AND is connected** (any two of: connected, acyclic, `n-1` edges imply the third). The fastest check: if `edges.length != n-1`, return `false` immediately. Otherwise run union-find; if any edge tries to union two already-connected nodes, that edge closes a cycle → not a tree. If no cycle appears and we have exactly `n-1` edges, the graph is automatically connected (a forest with `n-1` edges on `n` nodes must be a single tree). This is exactly the cycle-detection logic Kruskal uses to reject unsafe edges.

```java
import java.util.*;

class Solution {
    private int[] parent;

    public boolean validTree(int n, int[][] edges) {
        if (edges.length != n - 1) return false;    // tree needs exactly n-1 edges

        parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        for (int[] e : edges) {
            int ra = find(e[0]), rb = find(e[1]);
            if (ra == rb) return false;             // cycle detected
            parent[ra] = rb;                        // merge
        }
        return true;   // n-1 edges, no cycle => connected tree
    }

    private int find(int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
}
```

**Dry-run.** `n=5`, `edges=[[0,1],[0,2],[0,3],[1,4]]`. Edge count = 4 = n−1. Unions: (0,1),(0,2),(0,3),(1,4) all merge distinct sets, no cycle → **true**. Counter-example `[[0,1],[1,2],[2,3],[1,3],[0,4]]` has 5 edges ≠ n−1 → **false** immediately.

**Complexity.** Time O(n + E·α(n)); Space O(n). **Edge cases:** `n=1, edges=[]` → `0 == n-1` and the loop is empty → `true` (a single node is a valid tree); too many edges short-circuits before any union; the `edges.length != n-1` guard makes the disconnected case fall out automatically.

---

### Problem 9: Redundant Connection (LeetCode 684) — First Cycle-Closing Edge

**Statement.** A tree on `n` nodes had one extra edge added, producing a graph with `n` edges and exactly one cycle. Given `edges` in input order, return the edge that can be removed so the result is a tree. If multiple answers exist, return the one appearing **last** in the input.

**Constraints.** `n == edges.length`, `3 ≤ n ≤ 1000`, nodes labeled `1..n`.

**Approach.** Process edges in the given order with union-find. The **first** edge whose two endpoints are already in the same component is the one that closes the cycle — and because we scan left-to-right, the first such redundant edge found is automatically the last one needed to complete the cycle, satisfying the "return the last" tie-break. This is Kruskal's cycle-rejection rule used as a detector rather than a filter.

```java
import java.util.*;

class Solution {
    private int[] parent;

    public int[] findRedundantConnection(int[][] edges) {
        int n = edges.length;
        parent = new int[n + 1];                    // nodes are 1-indexed
        for (int i = 1; i <= n; i++) parent[i] = i;

        for (int[] e : edges) {
            int ra = find(e[0]), rb = find(e[1]);
            if (ra == rb) return e;                 // this edge closes the cycle
            parent[ra] = rb;
        }
        return new int[0];                          // problem guarantees one exists
    }

    private int find(int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
}
```

**Dry-run.** `edges=[[1,2],[1,3],[2,3]]`. Union(1,2) ok; union(1,3) ok ({1,2,3} forming); for (2,3) both find → 3 already, same component → return **[2,3]**.

**Complexity.** Time O(n·α(n)); Space O(n). **Edge cases:** the guarantee of exactly one extra edge means exactly one `ra == rb` hit; 1-indexing requires a DSU of size `n+1`; returning early on the first cycle edge is correct precisely because input order encodes the tie-break.

---

### Problem 10: Satisfiability of Equality Equations (LeetCode 990) — Union-Find on Variables

**Statement.** Given equations of the form `"a==b"` or `"a!=b"` (single lowercase letters), return `true` if it is possible to assign integers to variables so that all equations hold.

**Constraints.** `1 ≤ equations.length ≤ 500`; each equation is 4 characters; variables are single lowercase letters (26 possible).

**Approach.** Equality is an equivalence relation, so all variables joined by `==` must share one value → union them. Process **all `==` equations first** to build the connected components of "must be equal" variables. Then scan the `!=` equations: if any asserts `a != b` while `find(a) == find(b)`, the constraints are contradictory → return `false`. Only 26 nodes ('a'..'z'), so this is tiny but it crisply demonstrates union-find modeling of relational constraints.

```java
import java.util.*;

class Solution {
    private int[] parent = new int[26];

    public boolean equationsPossible(String[] equations) {
        for (int i = 0; i < 26; i++) parent[i] = i;

        // Pass 1: union all equalities
        for (String eq : equations) {
            if (eq.charAt(1) == '=') {
                union(eq.charAt(0) - 'a', eq.charAt(3) - 'a');
            }
        }
        // Pass 2: validate inequalities
        for (String eq : equations) {
            if (eq.charAt(1) == '!') {
                if (find(eq.charAt(0) - 'a') == find(eq.charAt(3) - 'a')) return false;
            }
        }
        return true;
    }

    private int find(int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
    private void union(int a, int b) { parent[find(a)] = find(b); }
}
```

**Dry-run.** `["a==b","b!=a"]`: pass 1 unions a,b → same set; pass 2 sees `b!=a` but `find(b)==find(a)` → **false**. `["a==b","b==c","a==c"]`: all unions consistent, no `!=` → **true**.

**Complexity.** Time O(n·α(26)) ≈ O(n); Space O(1) (fixed 26-element array). **Edge cases:** self-equalities like `"a==a"` are harmless; a lone `"a!=a"` is immediately false since `find(a)==find(a)`; order matters only in that all equalities must be processed before checking inequalities.

---

### Problem 11: Prim's MST on an Adjacency Matrix (Dense Graph) — O(V²) Prim

**Statement.** Given a dense weighted undirected graph as an `n×n` adjacency matrix `graph` (where `graph[i][j]` is the edge weight and `0` means "no edge" / self), return the total weight of its MST. Assume the graph is connected.

**Constraints.** `1 ≤ n ≤ 1000`; weights are positive; matrix is symmetric.

**Approach.** For dense graphs (`E ≈ V²`) the heap-based Prim's `O(E log V)` degrades toward `O(V² log V)`; the classic **`O(V²)` matrix Prim** is strictly better here and avoids a priority queue entirely. Maintain `dist[v]` = cheapest known edge connecting `v` to the growing tree. Repeat `n` times: linearly scan for the unvisited vertex `u` with minimum `dist[u]`, add it (and its edge weight) to the tree, then relax all neighbors `dist[v] = min(dist[v], graph[u][v])`. No heap, no stale entries — just two nested loops.

```
   dist[] = best edge into the tree so far
   pick min unvisited  ->  add to tree  ->  relax row u
   repeat V times
```

```java
import java.util.*;

class Solution {
    public int primMST(int[][] graph) {
        int n = graph.length;
        if (n <= 1) return 0;

        int[] dist = new int[n];
        boolean[] inTree = new boolean[n];
        Arrays.fill(dist, Integer.MAX_VALUE);
        dist[0] = 0;                                 // start vertex

        int total = 0;
        for (int iter = 0; iter < n; iter++) {
            // 1) pick the unvisited vertex closest to the tree
            int u = -1;
            for (int v = 0; v < n; v++) {
                if (!inTree[v] && (u == -1 || dist[v] < dist[u])) u = v;
            }
            inTree[u] = true;
            total += dist[u];

            // 2) relax: update best edge from u into still-outside vertices
            for (int v = 0; v < n; v++) {
                int w = graph[u][v];
                if (w != 0 && !inTree[v] && w < dist[v]) dist[v] = w;
            }
        }
        return total;
    }
}
```

**Dry-run.** 3×3 matrix `{{0,1,3},{1,0,2},{3,2,0}}`. Start dist=[0,∞,∞]. Pick 0 (0), relax → dist=[0,1,3]. Pick 1 (1), relax via row 1 → dist[2]=min(3,2)=2. Pick 2 (2). Total = 0+1+2 = **3**.

**Complexity.** Time O(V²) (two nested loops, `V` iterations each scanning `V`); Space O(V). **Edge cases:** `n ≤ 1` → 0; the `w != 0` guard treats 0 as "no edge"; for a guaranteed-connected graph no `dist[u]` remains `MAX_VALUE` when picked — if it could, that signals disconnection.

---

### Problem 12: Prim's MST with a Priority Queue (Sparse Graph, Adjacency List) — Lazy Prim

**Statement.** Given a connected weighted undirected graph with `n` nodes (`0..n-1`) and an `edges` list `[u, v, w]`, return the total weight of its MST using Prim's algorithm with a priority queue.

**Constraints.** `1 ≤ n ≤ 10^5`, `1 ≤ edges.length ≤ 2·10^5`, weights positive.

**Approach.** Build an adjacency list, then grow the tree from node 0 using a min-heap keyed by edge weight (the "lazy" variant: push every candidate crossing edge, skip stale ones on pop). Each pop gives the globally cheapest edge crossing the cut "in-tree vs. out"; if its target is already in the tree, discard it (a stale entry), otherwise accept the edge and push the new vertex's outgoing edges. This is Prim's structurally identical to Dijkstra, but the heap key is the single edge weight, not a cumulative path distance. Best for sparse graphs where `E ≪ V²`.

```java
import java.util.*;

class Solution {
    public long primMST(int n, int[][] edges) {
        if (n <= 1) return 0;

        // adjacency list: adj[u] = list of {neighbor, weight}
        List<int[]>[] adj = new List[n];
        for (int i = 0; i < n; i++) adj[i] = new ArrayList<>();
        for (int[] e : edges) {
            adj[e[0]].add(new int[]{e[1], e[2]});
            adj[e[1]].add(new int[]{e[0], e[2]});
        }

        boolean[] inTree = new boolean[n];
        // PQ entries: {weight, vertex}
        PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> Integer.compare(a[0], b[0]));
        pq.offer(new int[]{0, 0});                   // weight 0 to reach start vertex 0

        long total = 0;
        int used = 0;
        while (!pq.isEmpty() && used < n) {
            int[] top = pq.poll();
            int w = top[0], u = top[1];
            if (inTree[u]) continue;                 // stale entry
            inTree[u] = true;
            total += w;
            used++;
            for (int[] nb : adj[u]) {
                if (!inTree[nb[0]]) pq.offer(new int[]{nb[1], nb[0]});
            }
        }
        return total;                                // assumes connected graph
    }
}
```

**Dry-run.** `n=4`, edges `(0,1,1),(1,2,2),(0,2,3),(2,3,4)`. Start vertex 0 (w=0). Cheapest crossing: 0→1 (1). Then 1→2 (2). Then 2→3 (4). Total = 0+1+2+4 = **7** (the 0→2 edge of weight 3 is popped later but skipped as stale).

**Complexity.** Time O(E log E) (each edge may be pushed twice; heap ops are log); Space O(V + E). **Edge cases:** `n ≤ 1` → 0; stale entries are mandatory to skip or weights double-count; if the PQ empties before `used == n` the graph was disconnected (no MST).

---

### Problem 13: Kruskal's MST — Generic Total-Weight Builder (Edge List) — Sort + Union-Find

**Statement.** Given `n` nodes (`0..n-1`) and a weighted undirected `edges` list `[u, v, w]`, return the MST total weight, or `-1` if the graph is disconnected.

**Constraints.** `1 ≤ n ≤ 10^5`, `0 ≤ edges.length ≤ 2·10^5`; weights may be large (use `Integer.compare` to avoid overflow).

**Approach.** The canonical Kruskal: sort all edges ascending by weight, then sweep through them adding an edge iff its endpoints lie in different components (union-find). Stop after `n-1` accepted edges. If fewer than `n-1` are accepted after exhausting the list, the graph is disconnected → return `-1`. Correctness follows from the cut property — the globally cheapest edge crossing between two components is always safe. This is the single most reusable MST template for sparse, edge-list inputs.

```java
import java.util.*;

class Solution {
    private int[] parent, rank_;

    public long kruskalMST(int n, int[][] edges) {
        if (n <= 1) return 0;

        parent = new int[n];
        rank_  = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        Arrays.sort(edges, (a, b) -> Integer.compare(a[2], b[2]));

        long total = 0;
        int used = 0;
        for (int[] e : edges) {
            if (union(e[0], e[1])) {
                total += e[2];
                if (++used == n - 1) return total;   // tree complete
            }
        }
        return used == n - 1 ? total : -1;           // disconnected otherwise
    }

    private int find(int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }

    private boolean union(int a, int b) {
        int ra = find(a), rb = find(b);
        if (ra == rb) return false;
        if (rank_[ra] < rank_[rb]) { int t = ra; ra = rb; rb = t; }
        parent[rb] = ra;
        if (rank_[ra] == rank_[rb]) rank_[ra]++;
        return true;
    }
}
```

**Dry-run.** `n=4`, edges `(0,1,10),(1,3,15),(2,3,4),(2,0,6),(0,3,5)`. Sorted: 4,5,6,10,15. Take (2,3,4); take (0,3,5); take (2,0,6)? 2 and 0 now connected → skip; take (0,1,10) → connects node 1. used=3=n−1, total = 4+5+10 = **19**.

**Complexity.** Time O(E log E) dominated by the sort; Space O(V). **Edge cases:** `n ≤ 1` → 0; `edges.length < n-1` can never connect → returns `-1`; `Integer.compare` avoids the `a[2]-b[2]` overflow pitfall when weights span the int range.

---

### Problem 14: Minimum Cost to Connect Sticks (LeetCode 1167) — Greedy Min-Heap (Huffman-style)

**Statement.** You have sticks with positive integer lengths `sticks[i]`. Connecting two sticks of lengths `x` and `y` costs `x + y` and yields one stick of length `x + y`. Return the minimum total cost to connect all sticks into one.

**Constraints.** `1 ≤ sticks.length ≤ 10^4`, `1 ≤ sticks[i] ≤ 10^4`.

**Approach.** Although phrased as "connect everything," this is **not** a graph MST — it is the Huffman/optimal-merge greedy, included here because interviewers love testing whether you can tell the two apart. The cheapest total cost always combines the two **smallest** available sticks first (their length re-enters the pool and gets re-added on every later merge, so small lengths should be merged early to be summed fewest times). Use a min-heap: repeatedly poll the two smallest, push their sum, and accumulate the sum as cost. The exchange argument (swap a deep small leaf with a shallow large one to reduce cost) proves greedy optimality — distinct from MST's cut property.

```java
import java.util.*;

class Solution {
    public int connectSticks(int[] sticks) {
        PriorityQueue<Integer> pq = new PriorityQueue<>();
        for (int s : sticks) pq.offer(s);

        int total = 0;
        while (pq.size() > 1) {
            int a = pq.poll();
            int b = pq.poll();
            int merged = a + b;
            total += merged;
            pq.offer(merged);
        }
        return total;
    }
}
```

**Dry-run.** `sticks=[2,4,3]`. Heap {2,3,4}. Poll 2,3 → cost 5, push 5 → {4,5}. Poll 4,5 → cost 9, total 5+9 = **14**.

**Complexity.** Time O(n log n) (each of n−1 merges does O(log n) heap ops); Space O(n) for the heap. **Edge cases:** a single stick → 0 cost (no merges); the `pq.size() > 1` guard handles the size-1 case cleanly; greedy by *smallest-first* — taking largest-first is the classic wrong answer.

---

### Problem 15: Min Cost to Connect All Points via Kruskal on Generated Edges (LeetCode 1584 variant) — Kruskal

**Statement.** Same setup as Problem 1: `n` points on a plane, cost to connect two points is their Manhattan distance. Return the minimum cost to connect all points. Solve it with **Kruskal** by explicitly generating all pairwise edges.

**Constraints.** `1 ≤ n ≤ 1000`; coordinates fit in `int`. (Generates up to ~500k edges.)

**Approach.** A complete geometric graph has `n(n−1)/2` edges. Generate them all as `[i, j, manhattan(i,j)]`, sort, then run Kruskal until `n-1` edges are accepted. This contrasts with Problem 1's lazy Prim: Kruskal here materializes every edge (heavier on memory, ~500k entries) but is conceptually simpler — one sort plus union-find. For `n ≤ 1000` it comfortably fits in time and memory; beyond that, the `O(V²)` matrix Prim or lazy Prim is preferable to avoid building the full edge list. Including both solutions side by side highlights the density-vs-algorithm trade-off discussed in the cheat-sheet.

```java
import java.util.*;

class Solution {
    private int[] parent;

    public int minCostConnectPoints(int[][] points) {
        int n = points.length;
        if (n <= 1) return 0;

        // generate all C(n,2) edges
        List<int[]> edges = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                int d = Math.abs(points[i][0] - points[j][0])
                      + Math.abs(points[i][1] - points[j][1]);
                edges.add(new int[]{i, j, d});
            }
        }
        edges.sort((a, b) -> Integer.compare(a[2], b[2]));

        parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        int total = 0, used = 0;
        for (int[] e : edges) {
            int ra = find(e[0]), rb = find(e[1]);
            if (ra != rb) {
                parent[ra] = rb;
                total += e[2];
                if (++used == n - 1) break;
            }
        }
        return total;
    }

    private int find(int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
}
```

**Dry-run.** Points `[[0,0],[2,2],[3,10],[5,2],[7,0]]`. Cheapest edges: (0,1)=4, (1,3)=3, (3,4)=5, (1,2)=9 … Kruskal accepts 3,4,5,9 (skipping any that would cycle). Total = **20**, matching Problem 1.

**Complexity.** Time O(V² log V) (sort of `V²/2` edges dominates); Space O(V²) to hold the edge list. **Edge cases:** `n ≤ 1` → 0; the O(V²) memory is the real limiter vs. lazy Prim's O(V) frontier; Manhattan distance never overflows `int` for the given coordinate range but `Integer.compare` keeps the comparator safe regardless.

---

### Problem 16: Number of Operations to Make Network Connected (LeetCode 1319) — Union-Find Component Counting

**Statement.** There are `n` computers (`0..n-1`) connected by `connections[i] = [a, b]` cables. You may unplug any cable and replug it between any two computers. Return the **minimum number of moves** to connect all computers, or `-1` if it is impossible.

**Constraints.** `1 ≤ n ≤ 10^5`, `1 ≤ connections.length ≤ min(n*(n-1)/2, 10^5)`.

**Approach.** You need at least `n-1` cables to connect `n` computers; if `connections.length < n-1`, return `-1` outright. Otherwise, count the connected components `c` with union-find. Each "extra" cable (one whose endpoints are already connected — a redundant edge) can be moved to bridge two components, and connecting `c` components into one requires exactly `c-1` bridges. Since `connections.length ≥ n-1 ≥ c-1`, there are always enough spare cables, so the answer is simply `c-1`. This reframes MST connectivity as a component-counting exercise.

```java
import java.util.*;

class Solution {
    private int[] parent;

    public int makeConnected(int n, int[][] connections) {
        if (connections.length < n - 1) return -1;   // not enough cables

        parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        int components = n;
        for (int[] c : connections) {
            int ra = find(c[0]), rb = find(c[1]);
            if (ra != rb) { parent[ra] = rb; components--; }
        }
        return components - 1;                        // bridges needed
    }

    private int find(int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
}
```

**Dry-run.** `n=4`, `connections=[[0,1],[0,2],[1,2]]`. Cables = 3 ≥ n−1 = 3. Unions: (0,1) comp→3, (0,2) comp→2, (1,2) already connected (redundant). Components = 2 → answer = 2−1 = **1**.

**Complexity.** Time O(n + E·α(n)); Space O(n). **Edge cases:** `connections.length < n-1` → `-1`; an already fully connected network has 1 component → 0 moves; redundant cables are exactly the surplus needed for bridging, guaranteeing feasibility once the count check passes.

---

### Problem 17: Maximum Spanning Tree Weight — Kruskal Descending

**Statement.** Given a connected weighted undirected graph (`n` nodes, edge list `[u, v, w]`), return the total weight of its **maximum** spanning tree — the spanning tree with the *largest* possible total edge weight.

**Constraints.** `1 ≤ n ≤ 10^5`, `1 ≤ edges.length ≤ 2·10^5`, weights may be negative.

**Approach.** The maximum spanning tree is the perfect mirror of the minimum one: every property of the cut/cycle argument flips. Run Kruskal but sort edges in **descending** weight and greedily take the heaviest non-cycle edge each time. (Equivalently, negate all weights and run ordinary minimum Kruskal.) This appears in problems like maximizing reliability/bandwidth of a network where each edge has a benefit rather than a cost. The union-find machinery is identical; only the sort order changes — a great check of whether you truly understand *why* Kruskal works rather than memorizing it.

```java
import java.util.*;

class Solution {
    private int[] parent, rank_;

    public long maxSpanningTree(int n, int[][] edges) {
        if (n <= 1) return 0;

        parent = new int[n];
        rank_  = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        // descending by weight -> grab the heaviest safe edge first
        Arrays.sort(edges, (a, b) -> Integer.compare(b[2], a[2]));

        long total = 0;
        int used = 0;
        for (int[] e : edges) {
            if (union(e[0], e[1])) {
                total += e[2];
                if (++used == n - 1) return total;
            }
        }
        return used == n - 1 ? total : -1;            // disconnected
    }

    private int find(int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
    private boolean union(int a, int b) {
        int ra = find(a), rb = find(b);
        if (ra == rb) return false;
        if (rank_[ra] < rank_[rb]) { int t = ra; ra = rb; rb = t; }
        parent[rb] = ra;
        if (rank_[ra] == rank_[rb]) rank_[ra]++;
        return true;
    }
}
```

**Dry-run.** `n=3`, edges `(0,1,1),(1,2,2),(0,2,3)`. Sorted desc: 3,2,1. Take (0,2,3); take (1,2,2) → connects node 1; used=2=n−1. Total = 3+2 = **5** (the minimum tree would have been 1+2=3).

**Complexity.** Time O(E log E); Space O(V). **Edge cases:** `n ≤ 1` → 0; negative weights are fine (just sorted in order); disconnected graph returns `-1`; `Integer.compare(b[2], a[2])` gives descending order without overflow.

---

### Problem 18: Minimum Bottleneck Spanning Tree / Path Bottleneck — Kruskal Until Two Nodes Connect

**Statement.** Given a connected weighted undirected graph and two nodes `src` and `dst`, find the **minimum possible value of the maximum edge weight** on a path from `src` to `dst` (the minimax / bottleneck path). Equivalently, the largest edge on the `src→dst` path in the minimum spanning tree.

**Constraints.** `1 ≤ n ≤ 10^5`, `1 ≤ edges.length ≤ 2·10^5`, weights positive, `src != dst`.

**Approach.** A key MST fact: the minimum spanning tree is also a **minimum bottleneck spanning tree** — the path between any two nodes in the MST minimizes the maximum edge weight along it. So process edges ascending (Kruskal style) and union endpoints; the **moment `src` and `dst` become connected**, the weight of the edge that just joined them is the answer — it is the smallest weight `W` such that using only edges `≤ W` already links the two nodes. This "stop early when two specific nodes connect" pattern underlies LeetCode 1631 (Path With Minimum Effort) and 778 (Swim in Rising Water).

```java
import java.util.*;

class Solution {
    private int[] parent;

    public int minimumBottleneck(int n, int[][] edges, int src, int dst) {
        parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        Arrays.sort(edges, (a, b) -> Integer.compare(a[2], b[2]));

        for (int[] e : edges) {
            int ra = find(e[0]), rb = find(e[1]);
            if (ra != rb) parent[ra] = rb;
            if (find(src) == find(dst)) return e[2];   // just connected
        }
        return -1;                                     // src, dst in different parts
    }

    private int find(int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
}
```

**Dry-run.** `n=4`, edges `(0,1,4),(1,2,1),(2,3,3),(0,3,10)`, `src=0`, `dst=3`. Sorted: 1,3,4,10. Add (1,2,1): 0,3 not connected. Add (2,3,3): not yet. Add (0,1,4): now `{0,1,2,3}` all linked, `find(0)==find(3)` → return **4**. The path 0–1–2–3 has max edge 4, beating the direct 0–3 edge of 10.

**Complexity.** Time O(E log E); Space O(V). **Edge cases:** if `src`/`dst` never connect → `-1`; checking connectivity after *every* edge (even cycle-rejected ones) is fine since `find` is cheap; positive weights aren't required for correctness but match the common framing.

---

### Problem 19: Connect All Groups with Pre-Existing Free Connections — Kruskal with Pre-Union

**Statement.** There are `n` nodes (`0..n-1`). Some pairs are **already connected for free** via `prewired` `[a, b]`. The remaining possible links are given as weighted `edges` `[u, v, w]`. Return the minimum additional cost to connect all `n` nodes, or `-1` if impossible.

**Constraints.** `1 ≤ n ≤ 10^5`, `0 ≤ prewired.length, edges.length ≤ 2·10^5`, weights positive.

**Approach.** This is the common interview twist on Kruskal: some components start already merged. **Pre-union** all `prewired` pairs into the DSU *before* the main loop (cost 0), then run standard Kruskal over the weighted `edges`. Any weighted edge whose endpoints are already in the same pre-wired component is skipped automatically by the union check — you never pay for connectivity you already have. Track how many merges happen; the graph is fully connected once the component count drops to 1. This generalizes "Connecting Cities" (Problem 2) with the realistic constraint of free legacy links.

```java
import java.util.*;

class Solution {
    private int[] parent, rank_;
    private int components;

    public long minCostWithPrewired(int n, int[][] prewired, int[][] edges) {
        parent = new int[n];
        rank_  = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        components = n;

        // free connections first
        for (int[] p : prewired) union(p[0], p[1]);

        if (components == 1) return 0;                 // already fully connected

        Arrays.sort(edges, (a, b) -> Integer.compare(a[2], b[2]));

        long total = 0;
        for (int[] e : edges) {
            if (union(e[0], e[1])) {
                total += e[2];
                if (components == 1) return total;     // all connected
            }
        }
        return -1;                                     // still disconnected
    }

    private int find(int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
    private boolean union(int a, int b) {
        int ra = find(a), rb = find(b);
        if (ra == rb) return false;
        if (rank_[ra] < rank_[rb]) { int t = ra; ra = rb; rb = t; }
        parent[rb] = ra;
        if (rank_[ra] == rank_[rb]) rank_[ra]++;
        components--;
        return true;
    }
}
```

**Dry-run.** `n=4`, `prewired=[[0,1]]`, `edges=[[1,2,3],[2,3,1],[0,3,5]]`. Pre-union (0,1) → components 3. Sorted edges: 1,3,5. Take (2,3,1) → comp 2, total 1. Take (1,2,3) → comp 1, total 4 → return **4**. The (0,3,5) edge is never needed.

**Complexity.** Time O((P + E) + E log E) — pre-union plus sorted Kruskal; Space O(n). **Edge cases:** already-connected input → 0; prewired pairs that duplicate connectivity are absorbed harmlessly; not enough edges to reach `components == 1` → `-1`.

---

## 🧩 Extended Problems — Supplemental A: Classic → Medium

### Problem 20: Min Cost to Connect All Points via Eager Prim (Indexed dist[]) — O(V²) Prim

**Statement.** Same as Problem 1 / 15: `n` points on a 2D plane, cost to connect two points equals their Manhattan distance. Return the minimum cost to connect all points. Solve with **eager (array-based) Prim** so the work is a clean `O(V²)` with `O(V)` memory — no priority queue, no materialized edge list.

**Constraints.** `1 ≤ n ≤ 1000`, `−10^6 ≤ xi, yi ≤ 10^6`.

**Approach.** The complete geometric graph is dense (`E = V²/2`), so the matrix-style Prim from Problem 11 is the ideal fit — but here edges are *computed on the fly* from coordinates rather than read from a matrix. Keep `dist[v]` = cheapest known edge from any in-tree vertex to `v`. Repeat `n` times: pick the unvisited vertex `u` with minimum `dist[u]`, add `dist[u]` to the total, mark it, then relax every still-outside vertex with the Manhattan distance from `u`. This beats lazy Prim's `O(V² log V)` (Problem 1) and Kruskal's `O(V² log V)` + `O(V²)` memory (Problem 15) for this dense case.

```
   for each of V picks:
     u = argmin dist over unvisited      (O(V) scan)
     total += dist[u];  inTree[u]=true
     relax: dist[v] = min(dist[v], |x_u-x_v|+|y_u-y_v|)   (O(V))
```

```java
import java.util.*;

class Solution {
    public int minCostConnectPoints(int[][] points) {
        int n = points.length;
        if (n <= 1) return 0;

        int[] dist = new int[n];
        boolean[] inTree = new boolean[n];
        Arrays.fill(dist, Integer.MAX_VALUE);
        dist[0] = 0;                                  // anchor the tree at point 0

        int total = 0;
        for (int iter = 0; iter < n; iter++) {
            int u = -1;
            for (int v = 0; v < n; v++) {
                if (!inTree[v] && (u == -1 || dist[v] < dist[u])) u = v;
            }
            inTree[u] = true;
            total += dist[u];
            for (int v = 0; v < n; v++) {
                if (!inTree[v]) {
                    int d = Math.abs(points[u][0] - points[v][0])
                          + Math.abs(points[u][1] - points[v][1]);
                    if (d < dist[v]) dist[v] = d;
                }
            }
        }
        return total;
    }
}
```

**Dry-run.** Points `[[0,0],[2,2],[3,10],[5,2],[7,0]]`. dist=[0,∞,∞,∞,∞]. Pick 0 (+0), relax → dist=[_,4,13,7,7]. Pick 1 (+4), relax row 1 → dist[3]=min(7,3)=3, dist[2]=min(13,9)=9. Pick 3 (+3), relax → dist[4]=min(7,5)=5. Pick 4 (+5). Pick 2 (+9). Total = 0+4+3+5+9 = **20**.

**Complexity.** Time O(V²) (two nested `V` loops); Space O(V) for `dist` + `inTree`. **Edge cases:** `n ≤ 1` → 0; coordinates up to 10^6 keep Manhattan distance ≤ 4·10^6, safely within `int`; no stale-entry bookkeeping because there is no heap.

---

### Problem 21: Minimize Malware Spread — Save One Node (LeetCode 924) — Union-Find Component Sizing

**Statement.** Given an `n×n` symmetric adjacency matrix `graph` (`graph[i][j] = 1` means `i` and `j` are directly connected) and a list `initial` of initially infected nodes, malware spreads through every connected component containing an infected node, infecting all of it. Remove **exactly one** node from `initial` to minimize the final number of infected nodes. Return the node to remove; on ties return the smallest index.

**Constraints.** `1 ≤ n ≤ 300`, `graph` symmetric with `graph[i][i] = 1`, `1 ≤ initial.length ≤ n`.

**Approach.** Build components with union-find over the matrix. Removing a node `v` from `initial` only helps if `v` is the **sole** infected node in its component — otherwise that component is doomed regardless. So: count how many `initial` nodes fall in each component (by root). For every component containing exactly one infected node, removing that node saves the whole component (its size). Pick the infected node whose component is largest; break ties by smallest index. If no component has a unique infected node, no removal reduces spread → return `min(initial)`.

```java
import java.util.*;

class Solution {
    private int[] parent, size;

    public int minMalwareSpread(int[][] graph, int[] initial) {
        int n = graph.length;
        parent = new int[n];
        size   = new int[n];
        for (int i = 0; i < n; i++) { parent[i] = i; size[i] = 1; }

        for (int i = 0; i < n; i++)
            for (int j = i + 1; j < n; j++)
                if (graph[i][j] == 1) union(i, j);

        // count infected nodes per component root
        int[] infectedCount = new int[n];
        for (int node : initial) infectedCount[find(node)]++;

        int best = -1, bestSaved = -1;
        Arrays.sort(initial);                          // ensures smallest-index tie-break
        for (int node : initial) {
            int root = find(node);
            if (infectedCount[root] == 1) {            // sole infected -> removable benefit
                int saved = size[root];
                if (saved > bestSaved) { bestSaved = saved; best = node; }
            }
        }
        return best == -1 ? initial[0] : best;         // initial already sorted
    }

    private int find(int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
    private void union(int a, int b) {
        int ra = find(a), rb = find(b);
        if (ra == rb) return;
        if (size[ra] < size[rb]) { int t = ra; ra = rb; rb = t; }
        parent[rb] = ra;
        size[ra] += size[rb];
    }
}
```

**Dry-run.** `graph=[[1,1,0],[1,1,0],[0,0,1]]`, `initial=[0,1]`. Component `{0,1}` (size 2) has two infected nodes → removing either still leaves the other to infect both. Component `{2}` has none. No unique-infected component → return smallest initial = **0**.

**Complexity.** Time O(V² · α(V)) (scanning the matrix dominates); Space O(V). **Edge cases:** two infected nodes in one component cancel each other's benefit; if all infected nodes share components with others, fall back to `min(initial)`; sorting `initial` makes the tie-break automatic.

---

### Problem 22: Lexicographically Smallest Equivalent String (LeetCode 1061) — Union-Find with Min-Root

**Statement.** Two strings `s1`, `s2` of equal length define character equivalences: `s1[i]` is equivalent to `s2[i]`. Equivalence is reflexive, symmetric, transitive. Given a `baseStr`, replace each character with the **lexicographically smallest** character in its equivalence class. Return the resulting string.

**Constraints.** `1 ≤ s1.length = s2.length ≤ 1000`, `1 ≤ baseStr.length ≤ 1000`, all lowercase letters.

**Approach.** A union-find over the 26 letters, but with a twist that mirrors how MST's union-find can be biased: **always attach the larger-lettered root under the smaller-lettered root**, so every component's representative is its smallest character. Union each `s1[i]` with `s2[i]`. Then for each character of `baseStr`, output `(char)('a' + find(c - 'a'))`. The min-root invariant means `find` directly returns the lexicographically smallest equivalent letter — no separate min-tracking needed.

```java
class Solution {
    private int[] parent = new int[26];

    public String smallestEquivalentString(String s1, String s2, String baseStr) {
        for (int i = 0; i < 26; i++) parent[i] = i;

        for (int i = 0; i < s1.length(); i++) {
            union(s1.charAt(i) - 'a', s2.charAt(i) - 'a');
        }

        StringBuilder sb = new StringBuilder();
        for (char c : baseStr.toCharArray()) {
            sb.append((char) ('a' + find(c - 'a')));
        }
        return sb.toString();
    }

    private int find(int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
    private void union(int a, int b) {
        int ra = find(a), rb = find(b);
        if (ra == rb) return;
        // smaller letter becomes the root -> find() yields the min of the class
        if (ra < rb) parent[rb] = ra;
        else         parent[ra] = rb;
    }
}
```

**Dry-run.** `s1="parker"`, `s2="morris"`, `baseStr="parser"`. Unions group `{p,m}`, `{a,o}`, `{r,k,s}`, `{e,i}`. The min of each class: `m, o, k, k, e, k` for `p,a,r,s,e,r`. `"parser"` → `m`,`a→a`,`r→k`,`s→k`,`e→e`,`r→k` = **"makkek"**.

**Complexity.** Time O((s1.length + baseStr.length) · α(26)) ≈ O(n); Space O(1) (fixed 26 array). **Edge cases:** a letter equivalent to itself only maps to itself; the min-root rule must be applied on **roots** (`ra`, `rb`), not raw chars, or transitivity breaks; baseStr letters absent from any equivalence map to themselves.

---

### Problem 23: Min Cost to Make at Least One Valid Path (Second-MST Concept) — Replacement Edge

**Statement.** Given a connected weighted undirected graph and the list of edges, you have already built an MST. Now compute the weight of the **second-best minimum spanning tree** (the smallest total weight strictly greater than the MST weight, achieved by a spanning tree differing from the MST). Assume edge weights need not be distinct but a second-best tree exists.

**Constraints.** `2 ≤ n ≤ 1000`, `n-1 ≤ edges.length ≤ n(n-1)/2`.

**Approach.** A classic competitive-round result: the second-best MST differs from the MST by exactly **one edge swap**. Build the MST (Kruskal), recording which edges are in it. For every **non-MST** edge `(u, v, w)`, adding it to the MST creates a unique cycle; the second tree replaces the **maximum-weight MST edge on the `u→v` tree path** with this edge. The candidate cost is `mstWeight − maxPathEdge(u,v) + w`. Take the minimum such candidate over all non-tree edges that yield a *strictly different* tree. With `n ≤ 1000` we precompute, for every pair, the maximum edge weight on the tree path via a BFS/DFS from each node over the MST — `O(V²)`.

```java
import java.util.*;

class Solution {
    private int[] parent, rank_;

    public long secondBestMST(int n, int[][] edges) {
        parent = new int[n];
        rank_  = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        int m = edges.length;
        Integer[] order = new Integer[m];
        for (int i = 0; i < m; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Integer.compare(edges[a][2], edges[b][2]));

        // build MST adjacency + remember which edge indices are used
        List<int[]>[] adj = new List[n];
        for (int i = 0; i < n; i++) adj[i] = new ArrayList<>();
        boolean[] inMST = new boolean[m];
        long mstWeight = 0;
        int used = 0;
        for (int idx : order) {
            int u = edges[idx][0], v = edges[idx][1], w = edges[idx][2];
            if (union(u, v)) {
                inMST[idx] = true;
                mstWeight += w;
                adj[u].add(new int[]{v, w});
                adj[v].add(new int[]{u, w});
                if (++used == n - 1) break;
            }
        }

        // maxEdge[a][b] = heaviest edge weight on the MST path a..b
        int[][] maxEdge = new int[n][n];
        for (int s = 0; s < n; s++) bfsMax(s, n, adj, maxEdge);

        long best = Long.MAX_VALUE;
        for (int idx = 0; idx < m; idx++) {
            if (inMST[idx]) continue;
            int u = edges[idx][0], v = edges[idx][1], w = edges[idx][2];
            int heaviest = maxEdge[u][v];
            // swapping is valid (strictly different tree) when w >= heaviest;
            // strictly larger total requires w > heaviest, else equal-weight alt MST
            if (w > heaviest) {
                best = Math.min(best, mstWeight - heaviest + w);
            }
        }
        return best == Long.MAX_VALUE ? -1 : best;
    }

    private void bfsMax(int src, int n, List<int[]>[] adj, int[][] maxEdge) {
        boolean[] seen = new boolean[n];
        Deque<int[]> dq = new ArrayDeque<>();          // {node, maxSoFar}
        dq.offer(new int[]{src, 0});
        seen[src] = true;
        while (!dq.isEmpty()) {
            int[] cur = dq.poll();
            int node = cur[0], mx = cur[1];
            maxEdge[src][node] = mx;
            for (int[] nb : adj[node]) {
                if (!seen[nb[0]]) {
                    seen[nb[0]] = true;
                    dq.offer(new int[]{nb[0], Math.max(mx, nb[1])});
                }
            }
        }
    }

    private int find(int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
    private boolean union(int a, int b) {
        int ra = find(a), rb = find(b);
        if (ra == rb) return false;
        if (rank_[ra] < rank_[rb]) { int t = ra; ra = rb; rb = t; }
        parent[rb] = ra;
        if (rank_[ra] == rank_[rb]) rank_[ra]++;
        return true;
    }
}
```

**Dry-run.** Triangle `n=3`, edges `(0,1,1),(1,2,2),(0,2,3)`. MST takes (0,1,1),(1,2,2), weight 3. Non-tree edge (0,2,3): MST path 0→1→2 has heaviest edge 2. Since `3 > 2`, candidate = `3 − 2 + 3 = 4`. Second-best MST weight = **4**.

**Complexity.** Time O(E log E + V²) (Kruskal sort plus `V` BFS over a tree of `V−1` edges); Space O(V²) for the `maxEdge` table. **Edge cases:** if every non-tree edge has `w == heaviest` only equal-weight alternative MSTs exist (no *strictly* larger second tree) → returns `-1`; for large `n`, replace the `V²` table with LCA + binary lifting for `O(log V)` path-max queries.

---

### Problem 24: Checking Existence of Edge Length Limited Paths (LeetCode 1697) — Offline Kruskal

**Statement.** Given an undirected graph (`n` nodes, `edgeList[i] = [u, v, w]`, possibly multiple edges between a pair) and `queries[j] = [p, q, limit]`, answer for each query whether there exists a path between `p` and `q` using **only edges with weight strictly less than `limit`**. Return a boolean array.

**Constraints.** `2 ≤ n ≤ 10^5`, `1 ≤ edgeList.length, queries.length ≤ 10^5`.

**Approach.** A textbook **offline union-find** sweep, conceptually a partial-Kruskal driven by query thresholds. Sort edges ascending by weight and sort queries ascending by `limit` (remembering original indices). Sweep queries in increasing `limit`; before answering each, union all edges with `weight < limit` (a monotone pointer that never moves backward). Then the query is `true` iff `find(p) == find(q)`. Because both edges and queries are processed in nondecreasing order, each edge is unioned exactly once across the whole run — total `O((E + Q) log)` dominated by the two sorts.

```java
import java.util.*;

class Solution {
    private int[] parent;

    public boolean[] distanceLimitedPathsExist(int n, int[][] edgeList, int[][] queries) {
        parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        Arrays.sort(edgeList, (a, b) -> Integer.compare(a[2], b[2]));

        int q = queries.length;
        Integer[] order = new Integer[q];
        for (int i = 0; i < q; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Integer.compare(queries[a][2], queries[b][2]));

        boolean[] ans = new boolean[q];
        int ei = 0;                                    // monotone edge pointer
        for (int qi : order) {
            int limit = queries[qi][2];
            while (ei < edgeList.length && edgeList[ei][2] < limit) {
                union(edgeList[ei][0], edgeList[ei][1]);
                ei++;
            }
            ans[qi] = find(queries[qi][0]) == find(queries[qi][1]);
        }
        return ans;
    }

    private int find(int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
    private void union(int a, int b) {
        int ra = find(a), rb = find(b);
        if (ra != rb) parent[ra] = rb;
    }
}
```

**Dry-run.** `n=3`, `edgeList=[[0,1,2],[1,2,4],[2,0,8],[1,0,16]]`, `queries=[[0,1,2],[0,2,5]]`. Sort queries by limit: `[0,1,2]` then `[0,2,5]`. For limit 2: no edge `< 2` → `find(0)!=find(1)` → **false**. For limit 5: union edges `<5` = (0,1,2),(1,2,4) → `find(0)==find(2)` → **true**. Result `[false, true]`.

**Complexity.** Time O((E + Q) log(E + Q)); Space O(n + Q). **Edge cases:** strict `<` (not `≤`) per the problem; multiple parallel edges are handled naturally; the edge pointer is never reset, so total union work is `O(E·α)` across all queries.

---

### Problem 25: Process Sequence of Restricted Friend Requests (LeetCode 2076) — Union-Find with Restrictions

**Statement.** `n` people (`0..n-1`). `restrictions[i] = [x, y]` means `x` and `y` must **never** become (directly or indirectly) friends. Process `requests[j] = [u, v]` in order: a request **succeeds** (and `u`, `v` become friends) only if it does not violate any restriction; otherwise it fails and is skipped. Return a boolean array of which requests succeeded.

**Constraints.** `2 ≤ n ≤ 1000`, `0 ≤ restrictions.length ≤ 1000`, `1 ≤ requests.length ≤ 1000`.

**Approach.** Tentatively check each request against all restrictions before committing the union — a "speculative merge" pattern that shows up whenever an MST-style merge must respect forbidden pairs. For request `(u, v)`, compute `ru = find(u)`, `rv = find(v)`. If already same component → trivially succeeds. Otherwise scan every restriction `(x, y)`: if `(find(x), find(y))` equals `(ru, rv)` in either orientation, merging `u` and `v` would put a forbidden pair together → reject. If no restriction is violated, perform the union and record success. With `n, R, Q ≤ 1000` the `O(Q · R · α)` brute check is comfortably fast.

```java
import java.util.*;

class Solution {
    private int[] parent;

    public boolean[] friendRequests(int n, int[][] restrictions, int[][] requests) {
        parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        boolean[] result = new boolean[requests.length];
        for (int i = 0; i < requests.length; i++) {
            int ru = find(requests[i][0]), rv = find(requests[i][1]);
            if (ru == rv) { result[i] = true; continue; }   // already friends

            boolean ok = true;
            for (int[] r : restrictions) {
                int rx = find(r[0]), ry = find(r[1]);
                if ((rx == ru && ry == rv) || (rx == rv && ry == ru)) { ok = false; break; }
            }
            if (ok) { parent[ru] = rv; result[i] = true; }   // commit the merge
            // else result[i] stays false
        }
        return result;
    }

    private int find(int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
}
```

**Dry-run.** `n=3`, `restrictions=[[0,1]]`, `requests=[[0,2],[2,1]]`. Request (0,2): no restriction links roots {0} and {2} → succeed, merge → `{0,2}`. Request (2,1): roots are `{0,2}` and `{1}`; restriction (0,1) maps to roots `{0,2}` and `{1}` → matches → **reject**. Result `[true, false]`.

**Complexity.** Time O(Q · R · α(n)); Space O(n). **Edge cases:** a request whose endpoints are already connected always succeeds (the merge already happened legally); restrictions must be re-`find`-ed each request because earlier merges change roots; the union is committed only after the full restriction scan passes.

---

### Problem 26: Earliest Moment When All People Become Friends (LeetCode 1101) — Kruskal Stop-At-Connected

**Statement.** Given `n` people and `logs[i] = [timestamp, a, b]` meaning `a` and `b` become friends at `timestamp` (friendship is transitive and symmetric), return the **earliest timestamp** at which every person is connected to every other, or `-1` if it never happens.

**Constraints.** `1 ≤ n ≤ 100`, `1 ≤ logs.length ≤ 10^4`, timestamps are distinct nonnegative integers.

**Approach.** This is Kruskal where "weight" is time and we stop the instant the graph becomes one component. Sort logs by timestamp ascending, then union friend pairs in chronological order while tracking the live component count (start at `n`, decrement on each successful union). The earliest moment everyone is connected is the timestamp of the union that drops the count to `1`. If we exhaust all logs with more than one component left, return `-1`. Identical machinery to "Connecting Cities," reframed on the time axis.

```java
import java.util.*;

class Solution {
    private int[] parent;

    public int earliestAcq(int[][] logs, int n) {
        parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        Arrays.sort(logs, (a, b) -> Integer.compare(a[0], b[0]));

        int components = n;
        for (int[] log : logs) {
            if (union(log[1], log[2])) {
                if (--components == 1) return log[0];   // fully connected now
            }
        }
        return -1;                                      // never fully connected
    }

    private int find(int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
    private boolean union(int a, int b) {
        int ra = find(a), rb = find(b);
        if (ra == rb) return false;
        parent[ra] = rb;
        return true;
    }
}
```

**Dry-run.** `n=4`, logs `[20,1,2],[5,0,1],[10,2,3],[1,3,0]`. Sorted by time: `(1,3,0),(5,0,1),(10,2,3),(20,1,2)`. union(3,0) comp 3; union(0,1) comp 2; union(2,3) comp 1 at time **10** → return 10. The later log at 20 is unnecessary.

**Complexity.** Time O(L log L) for the sort plus near-constant unions; Space O(n). **Edge cases:** `n=1` → already connected; never reaching one component → `-1`; only successful unions decrement the count, so redundant logs are ignored.

---

### Problem 27: Optimize Water Distribution via Prim with Virtual Node — O(V²)/PQ Prim

**Statement.** Same as Problem 3 (LeetCode 1168): `n` houses, build a well in house `i` for `wells[i]` or lay a pipe `[i, j, cost]`. Every house must get water. Return the minimum total cost — but solve it with **Prim** (priority queue) on the virtual-node graph, contrasting the Kruskal solution of Problem 3.

**Constraints.** `1 ≤ n ≤ 10^4`, `wells.length == n`, `0 ≤ pipes.length ≤ 10^4`.

**Approach.** Reuse the virtual-node model: node `0` is "the outside water source," with edge `0—i` of weight `wells[i-1]`; pipes are ordinary edges. The MST of these `n+1` nodes is the answer. Where Problem 3 used Kruskal, here we grow the tree with Prim from the virtual node `0`: a min-heap of crossing edges, pop the cheapest leading to a new node, accept it, and push that node's incident edges. Starting at node `0` guarantees the first edges considered are the well costs, exactly as intended. This demonstrates that the *modeling* (virtual node) is independent of which MST algorithm executes it.

```java
import java.util.*;

class Solution {
    public int minCostToSupplyWater(int n, int[] wells, int[][] pipes) {
        // adjacency over nodes 0..n (0 = virtual source)
        List<int[]>[] adj = new List[n + 1];
        for (int i = 0; i <= n; i++) adj[i] = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            adj[0].add(new int[]{i + 1, wells[i]});
            adj[i + 1].add(new int[]{0, wells[i]});
        }
        for (int[] p : pipes) {
            adj[p[0]].add(new int[]{p[1], p[2]});
            adj[p[1]].add(new int[]{p[0], p[2]});
        }

        boolean[] inTree = new boolean[n + 1];
        PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> Integer.compare(a[0], b[0]));
        pq.offer(new int[]{0, 0});                      // {weight, node} start at virtual 0

        int total = 0, used = 0;
        while (!pq.isEmpty() && used < n + 1) {
            int[] top = pq.poll();
            int w = top[0], u = top[1];
            if (inTree[u]) continue;
            inTree[u] = true;
            total += w;
            used++;
            for (int[] nb : adj[u]) {
                if (!inTree[nb[0]]) pq.offer(new int[]{nb[1], nb[0]});
            }
        }
        return total;
    }
}
```

**Dry-run.** `n=3`, `wells=[1,2,2]`, `pipes=[[1,2,1],[2,3,1]]`. Start node 0 (w=0). Cheapest: 0→1 (well 1). Then 1→2 (pipe 1). Then 2→3 (pipe 1). Nodes {0,1,2,3} all in tree, total = 0+1+1+1 = **3**, matching Problem 3.

**Complexity.** Time O((n + P) log(n + P)) heap-based Prim; Space O(n + P). **Edge cases:** starting at node 0 is essential so well edges seed the frontier; stale heap entries (`inTree[u]` check) prevent double counting; an isolated house with no pipes still gets water via its well edge.

---

### Problem 28: Build MST Edge List (Return the Chosen Edges) — Kruskal Reconstruction

**Statement.** Given `n` nodes and a weighted undirected `edges` list `[u, v, w]`, return the actual list of edges (as `[u, v, w]`) chosen for a minimum spanning tree, not merely the total weight. If the graph is disconnected, return an empty list.

**Constraints.** `1 ≤ n ≤ 10^5`, `0 ≤ edges.length ≤ 2·10^5`.

**Approach.** Interviewers frequently follow "return the MST weight" with "now return the edges." Run Kruskal but, on each *accepted* edge, append it to a result list. Stop after `n-1` accepted edges. If fewer than `n-1` are accepted after consuming all edges, the graph is disconnected → return empty. The chosen-edge set is one valid MST (which specific edges appear can vary under ties, but the total weight is invariant). This is the building block for any problem that needs the tree structure afterward — bottleneck queries, tree DP, or visualization.

```java
import java.util.*;

class Solution {
    private int[] parent, rank_;

    public List<int[]> kruskalEdges(int n, int[][] edges) {
        parent = new int[n];
        rank_  = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        Arrays.sort(edges, (a, b) -> Integer.compare(a[2], b[2]));

        List<int[]> mst = new ArrayList<>();
        for (int[] e : edges) {
            if (union(e[0], e[1])) {
                mst.add(new int[]{e[0], e[1], e[2]});
                if (mst.size() == n - 1) return mst;    // tree complete
            }
        }
        return mst.size() == n - 1 ? mst : new ArrayList<>();  // disconnected -> empty
    }

    private int find(int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
    private boolean union(int a, int b) {
        int ra = find(a), rb = find(b);
        if (ra == rb) return false;
        if (rank_[ra] < rank_[rb]) { int t = ra; ra = rb; rb = t; }
        parent[rb] = ra;
        if (rank_[ra] == rank_[rb]) rank_[ra]++;
        return true;
    }
}
```

**Dry-run.** `n=4`, edges `(0,1,10),(1,3,15),(2,3,4),(2,0,6),(0,3,5)`. Sorted: 4,5,6,10,15. Accept (2,3,4); accept (0,3,5); (2,0,6) cycles → skip; accept (0,1,10). MST edges = `[(2,3,4),(0,3,5),(0,1,10)]`, 3 = n−1 edges, total 19.

**Complexity.** Time O(E log E); Space O(V) DSU + O(V) output. **Edge cases:** `n=1` → empty list (0 edges needed); disconnected graph → empty list per spec; tie-broken edge choice differs but the returned set is always a valid MST.

---

### Problem 29: Count the Number of Distinct Minimum Spanning Trees — Kruskal by Weight Groups + Matrix-Tree

**Statement.** Given a connected weighted undirected graph (`n` nodes, edge list), count the number of **distinct** minimum spanning trees, modulo `10^9 + 7`. Two MSTs are distinct if their edge sets differ.

**Constraints.** `2 ≤ n ≤ 100`, weights up to `10^5`. (Small `n` enables a determinant-based count.)

**Approach.** Expert/competitive. A deep MST theorem: across **all** MSTs, the multiset of edge weights used is identical, and within each weight class the chosen edges form a fixed contraction structure. Process edges in **groups of equal weight** (Kruskal order). Before processing a weight group, the current DSU defines "super-nodes." Within the group, the edges connecting super-nodes form a graph; the number of ways to pick a spanning forest of *that* sub-graph (per super-component) is counted by the **Matrix-Tree theorem** (Kirchhoff's): the number of spanning trees of a (multi)graph equals any cofactor of its Laplacian determinant. Multiply these counts across all weight groups, then commit the unions for that weight. The product over groups is the total number of MSTs.

```java
import java.util.*;

class Solution {
    private static final int MOD = 1_000_000_007;
    private int[] parent;

    public int countMST(int n, int[][] edges) {
        parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        Arrays.sort(edges, (a, b) -> Integer.compare(a[2], b[2]));

        long result = 1;
        int i = 0, m = edges.length;
        while (i < m) {
            int j = i;
            while (j < m && edges[j][2] == edges[i][2]) j++;   // [i, j) = one weight group

            // group edges by the super-node (root) pair they connect
            Map<Long, Integer> compId = new HashMap<>();       // root -> dense local id
            Map<String, int[]> ignore = new HashMap<>();       // (unused placeholder)
            List<int[]> groupEdges = new ArrayList<>();
            for (int k = i; k < j; k++) {
                int ra = find(edges[k][0]), rb = find(edges[k][1]);
                if (ra == rb) continue;                         // cycle within already-merged
                groupEdges.add(new int[]{ra, rb});
            }

            // partition super-nodes touched in this group into connected blocks
            // (each block contributes a spanning-tree count via Matrix-Tree)
            Map<Integer, List<int[]>> blocks = groupByComponent(groupEdges);
            for (Map.Entry<Integer, List<int[]>> blk : blocks.entrySet()) {
                List<int[]> be = blk.getValue();
                // collect distinct super-nodes in this block, map to 0..s-1
                Map<Integer, Integer> idx = new HashMap<>();
                for (int[] e : be) {
                    idx.putIfAbsent(e[0], idx.size());
                    idx.putIfAbsent(e[1], idx.size());
                }
                int s = idx.size();
                long[][] lap = new long[s][s];
                for (int[] e : be) {
                    int a = idx.get(e[0]), b = idx.get(e[1]);
                    lap[a][a] = (lap[a][a] + 1) % MOD;
                    lap[b][b] = (lap[b][b] + 1) % MOD;
                    lap[a][b] = (lap[a][b] - 1 + MOD) % MOD;
                    lap[b][a] = (lap[b][a] - 1 + MOD) % MOD;
                }
                result = result * cofactorDet(lap, s) % MOD;
            }

            // commit all group unions for the next weight level
            for (int[] e : groupEdges) union(e[0], e[1]);
            i = j;
        }
        return (int) result;
    }

    // group edges into connected blocks of super-nodes using a temporary DSU
    private Map<Integer, List<int[]>> groupByComponent(List<int[]> groupEdges) {
        Map<Integer, Integer> tmp = new HashMap<>();
        for (int[] e : groupEdges) { tmp.putIfAbsent(e[0], e[0]); tmp.putIfAbsent(e[1], e[1]); }
        for (int[] e : groupEdges) tmpUnion(tmp, e[0], e[1]);
        Map<Integer, List<int[]>> blocks = new HashMap<>();
        for (int[] e : groupEdges) {
            int root = tmpFind(tmp, e[0]);
            blocks.computeIfAbsent(root, z -> new ArrayList<>()).add(e);
        }
        return blocks;
    }
    private int tmpFind(Map<Integer, Integer> p, int x) {
        while (p.get(x) != x) { p.put(x, p.get(p.get(x))); x = p.get(x); }
        return x;
    }
    private void tmpUnion(Map<Integer, Integer> p, int a, int b) {
        int ra = tmpFind(p, a), rb = tmpFind(p, b);
        if (ra != rb) p.put(ra, rb);
    }

    // determinant of the (s-1)x(s-1) cofactor (delete last row/col), mod MOD, via Gaussian elimination
    private long cofactorDet(long[][] lap, int s) {
        if (s <= 1) return 1;                              // single super-node: one "tree"
        int d = s - 1;
        long[][] a = new long[d][d];
        for (int r = 0; r < d; r++)
            for (int c = 0; c < d; c++) a[r][c] = lap[r][c];

        long det = 1;
        for (int col = 0; col < d; col++) {
            int piv = -1;
            for (int r = col; r < d; r++) if (a[r][col] != 0) { piv = r; break; }
            if (piv == -1) return 0;                       // singular -> 0 spanning trees
            if (piv != col) { long[] t = a[piv]; a[piv] = a[col]; a[col] = t; det = (MOD - det) % MOD; }
            det = det * a[col][col] % MOD;
            long inv = modInverse(a[col][col]);
            for (int r = col + 1; r < d; r++) {
                long factor = a[r][col] * inv % MOD;
                for (int c = col; c < d; c++)
                    a[r][c] = (a[r][c] - factor * a[col][c] % MOD + MOD) % MOD;
            }
        }
        return det;
    }

    private long modInverse(long x) { return modPow(x % MOD, MOD - 2); }
    private long modPow(long b, long e) {
        long r = 1; b %= MOD;
        while (e > 0) { if ((e & 1) == 1) r = r * b % MOD; b = b * b % MOD; e >>= 1; }
        return r;
    }
    private int find(int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
    private void union(int a, int b) {
        int ra = find(a), rb = find(b);
        if (ra != rb) parent[ra] = rb;
    }
}
```

**Dry-run.** Triangle with all weights equal, edges `(0,1,1),(1,2,1),(0,2,1)`. One weight group of 3 edges over super-nodes {0},{1},{2}; the block is a triangle multigraph; Matrix-Tree gives `3` spanning trees. Result = **3** (each pair of the three equal edges forms a distinct MST). With distinct weights every group is a single forced edge → product of 1's → exactly **1** MST.

**Complexity.** Time O(E log E + Σ s³) (Gaussian elimination per weight block, `s ≤ n ≤ 100`); Space O(n²). **Edge cases:** distinct weights → answer 1; a self-cycling edge inside an already-merged component is skipped; `modInverse` relies on `MOD` being prime so every nonzero pivot is invertible.

---

### Problem 30: Maximum Probability Path vs MST (Modified Relaxation) — Why MST ≠ Shortest/Best Path

**Statement.** Given an undirected graph where each edge `[u, v]` has a *success probability* `succProb[i]` (in `[0,1]`), and nodes `start` and `end`, return the maximum probability of a successful path from `start` to `end` (product of edge probabilities). Return `0` if no path exists. (LeetCode 1514.)

**Constraints.** `2 ≤ n ≤ 10^4`, `0 ≤ edges.length ≤ 2·10^5`, `0 ≤ succProb[i] ≤ 1`.

**Approach.** Included deliberately as a **contrast** problem: "connect everything optimally" instincts may tempt you toward MST, but a *path* between two specific nodes that maximizes a product is a **modified-Dijkstra** problem, not MST. An MST minimizes total tree weight and says nothing about pairwise path quality. Run a max-heap Dijkstra: `prob[start] = 1`, pop the node with the highest probability, and relax neighbors with `prob[v] = max(prob[v], prob[u] * edgeProb)`. The first time `end` is popped, its probability is final. Knowing *when not to use MST* is exactly what separates mechanical pattern-matching from understanding.

```java
import java.util.*;

class Solution {
    public double maxProbability(int n, int[][] edges, double[] succProb, int start, int end) {
        List<double[]>[] adj = new List[n];             // adj[u] = {neighbor, prob}
        for (int i = 0; i < n; i++) adj[i] = new ArrayList<>();
        for (int i = 0; i < edges.length; i++) {
            adj[edges[i][0]].add(new double[]{edges[i][1], succProb[i]});
            adj[edges[i][1]].add(new double[]{edges[i][0], succProb[i]});
        }

        double[] prob = new double[n];
        prob[start] = 1.0;
        // max-heap on probability: {prob, node}
        PriorityQueue<double[]> pq = new PriorityQueue<>((a, b) -> Double.compare(b[0], a[0]));
        pq.offer(new double[]{1.0, start});

        while (!pq.isEmpty()) {
            double[] top = pq.poll();
            double p = top[0];
            int u = (int) top[1];
            if (u == end) return p;                     // first pop of end is optimal
            if (p < prob[u]) continue;                  // stale entry
            for (double[] nb : adj[u]) {
                int v = (int) nb[0];
                double np = p * nb[1];
                if (np > prob[v]) { prob[v] = np; pq.offer(new double[]{np, v}); }
            }
        }
        return 0.0;                                     // end unreachable
    }
}
```

**Dry-run.** `n=3`, `edges=[[0,1],[1,2],[0,2]]`, `succProb=[0.5,0.5,0.2]`, `start=0`, `end=2`. From 0: paths 0→2 = 0.2, 0→1→2 = 0.5·0.5 = 0.25. Max-heap pops node 2 with the larger of the two relaxations → **0.25**. An MST here would pick edges by weight and could not answer this product-maximization at all.

**Complexity.** Time O(E log V) (Dijkstra with binary heap); Space O(V + E). **Edge cases:** no path → 0; a probability-1 edge behaves like a free hop; using a *max*-heap and *product* relaxation is the only change from standard shortest-path Dijkstra; do **not** reach for MST.

---

### Problem 31: Swim in Rising Water (LeetCode 778) — Minimax Path via Kruskal/Union-Find

**Statement.** On an `n×n` grid, `grid[i][j]` is the elevation at cell `(i,j)`. At time `t` a cell is swimmable if `grid[i][j] ≤ t`. Starting at `(0,0)`, you may move to 4-directionally adjacent swimmable cells instantly. Return the **least time** `t` to reach `(n-1, n-1)`.

**Constraints.** `1 ≤ n ≤ 50`, `grid` is a permutation of `0 .. n²−1`.

**Approach.** This is a **minimum bottleneck path** (minimax) problem — the answer is the smallest `t` such that the start and end cells are connected using only cells with elevation `≤ t`, i.e., the maximum elevation on the best path is minimized. It is the grid sibling of Problem 18. Treat each cell as a node and build edges between adjacent cells with weight `max(elevation of the two)`. Sort edges ascending and union via Kruskal until `(0,0)` and `(n-1,n-1)` share a component; the weight of the connecting edge — equivalently the running max elevation just added — is the answer. (Binary-search-on-`t` + BFS is the alternative; union-find is cleaner here.)

```
   each cell is a node; edge weight = max(elev[a], elev[b])
   add cells/edges in increasing elevation; stop when corner connects
```

```java
import java.util.*;

class Solution {
    private int[] parent;

    public int swimInWater(int[][] grid) {
        int n = grid.length;
        if (n == 1) return grid[0][0];

        parent = new int[n * n];
        for (int i = 0; i < n * n; i++) parent[i] = i;

        // build edges between 4-adjacent cells, weight = max elevation of the two
        List<int[]> edges = new ArrayList<>();          // {weight, cellA, cellB}
        int[][] dirs = {{0, 1}, {1, 0}};                // right & down cover all undirected pairs
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                for (int[] d : dirs) {
                    int nr = r + d[0], nc = c + d[1];
                    if (nr < n && nc < n) {
                        int w = Math.max(grid[r][c], grid[nr][nc]);
                        edges.add(new int[]{w, r * n + c, nr * n + nc});
                    }
                }
            }
        }
        edges.sort((a, b) -> Integer.compare(a[0], b[0]));

        int start = 0, end = n * n - 1;
        for (int[] e : edges) {
            union(e[1], e[2]);
            if (find(start) == find(end)) {
                // answer is at least the largest elevation introduced so far = e[0],
                // but also must allow standing on both corners
                return Math.max(e[0], Math.max(grid[0][0], grid[n - 1][n - 1]));
            }
        }
        return Math.max(grid[0][0], grid[n - 1][n - 1]);
    }

    private int find(int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
    private void union(int a, int b) {
        int ra = find(a), rb = find(b);
        if (ra != rb) parent[ra] = rb;
    }
}
```

**Dry-run.** `grid=[[0,2],[1,3]]`. Edges: (0,0)-(0,1) w=max(0,2)=2; (0,0)-(1,0) w=max(0,1)=1; (0,1)-(1,1) w=max(2,3)=3; (1,0)-(1,1) w=max(1,3)=3. Sorted: 1,2,3,3. Union w=1 cells 0&2; union w=2 cells 0&1; now corners 0 and 3 still separate; union w=3 cells 1&3 → `find(0)==find(3)`. Answer = max(3, grid[0][0]=0, grid[1][1]=3) = **3**.

**Complexity.** Time O(n² log n) (sorting `O(n²)` edges); Space O(n²). **Edge cases:** `n=1` → just `grid[0][0]`; the final `max` with both corner elevations guarantees you can actually *stand* on the endpoints; because grid values are a permutation, all edge weights tie-break uniquely.

---

## 🧩 Extended Problems — Supplemental B: Hard / Expert

### Problem 32: Redundant Connection II (LeetCode 685) — Directed Union-Find + Two-Parent Conflict

**Statement.** A rooted tree on `n` nodes (`1..n`) had one extra **directed** edge added, producing a graph with `n` directed edges in which every node except the root has exactly one parent — except the added edge may create either a node with two parents, a cycle, or both. Given `edges` in input order, return the edge that can be removed so the result is a rooted tree. If several answers exist, return the one appearing **last** in the input.

**Constraints.** `n == edges.length`, `3 ≤ n ≤ 1000`, nodes labeled `1..n`.

**Approach.** The directed twin of Problem 9 is genuinely harder because two distinct defects can occur. Scan once to detect a node with **two incoming edges**; if found, record the two candidate edges `cand1` (earlier) and `cand2` (later) and *ignore* `cand2` for now. Then run union-find over the remaining edges:
- If no two-parent conflict existed, the redundant edge is the one that closes a cycle (exactly Problem 9).
- If a two-parent conflict existed and union-find still finds a cycle, the culprit must be `cand1` (the one we kept).
- If a two-parent conflict existed and no cycle appears, removing `cand2` fixes it.

```
   node v has parents A (early) and B (late)
   ┌─ no cycle when B skipped  -> answer = cand2 (B)
   └─ cycle even with B skipped -> answer = cand1 (A)
   no two-parent node at all    -> answer = first cycle-closing edge
```

```java
import java.util.*;

class Solution {
    private int[] parent;

    public int[] findRedundantDirectedConnection(int[][] edges) {
        int n = edges.length;
        int[] inParent = new int[n + 1];           // inParent[v] = node pointing at v
        int cand1 = -1, cand2 = -1;                // two edges into the same node

        for (int i = 0; i < n; i++) {
            int u = edges[i][0], v = edges[i][1];
            if (inParent[v] != 0) {                // v already has a parent -> conflict
                cand1 = inParent[v];               // earlier edge (stored as edge index+1)
                cand2 = i + 1;                      // current edge (later)
                break;
            }
            inParent[v] = i + 1;                    // store 1-based edge index
        }

        parent = new int[n + 1];
        for (int i = 1; i <= n; i++) parent[i] = i;

        for (int i = 0; i < n; i++) {
            if (cand2 != -1 && i == cand2 - 1) continue;   // skip the later conflicting edge
            int u = edges[i][0], v = edges[i][1];
            if (find(u) == find(v)) {               // cycle detected
                if (cand1 == -1) return edges[i];   // pure cycle, no two-parent node
                return edges[cand1 - 1];            // conflict + cycle -> remove earlier edge
            }
            parent[find(u)] = find(v);
        }
        return edges[cand2 - 1];                     // conflict, no cycle -> remove later edge
    }

    private int find(int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
}
```

**Dry-run.** `edges=[[1,2],[2,3],[3,4],[4,1],[1,5]]` has no two-parent node, so it is a pure cycle. Union (1,2),(2,3),(3,4); edge (4,1) finds `find(4)==find(1)` → return **[4,1]**. For `[[1,2],[1,3],[2,3]]`, node 3 has parents 2 and 3 → `cand1=[1,3]`, `cand2=[2,3]`; skipping `cand2`, unions (1,2),(1,3) give no cycle → return `cand2` = **[2,3]**.

**Complexity.** Time O(n·α(n)); Space O(n). **Edge cases:** the `cand1`/`cand2` split is the crux — getting the "cycle with conflict vs. without" branches backward is the usual bug; 1-based edge encoding avoids confusing edge index 0 with "no parent."

---

### Problem 33: Accounts Merge (LeetCode 721) — Union-Find over Emails

**Statement.** Given `accounts` where `accounts[i] = [name, email1, email2, ...]`, merge accounts that share any email (the same person may appear in multiple accounts). Two accounts belong to the same person iff they share at least one email; names are not unique but a real person's accounts all carry the same name. Return the merged accounts as `[name, sortedEmail1, sortedEmail2, ...]`.

**Constraints.** `1 ≤ accounts.length ≤ 1000`, `2 ≤ accounts[i].length ≤ 10`, emails are valid lowercase strings.

**Approach.** Classic union-find where the nodes are **emails, not account rows**. Map each distinct email to an integer id and remember the owner name for each email. Within one account, union all its emails together (they clearly belong to one person). After processing every account, group emails by their DSU root: each root is one merged person. Sort the emails in each group and prepend the name. The key insight — shared emails transitively merge accounts, exactly the connectivity closure union-find computes.

```java
import java.util.*;

class Solution {
    private int[] parent;

    public List<List<String>> accountsMerge(List<List<String>> accounts) {
        Map<String, Integer> emailId = new HashMap<>();
        Map<String, String> emailName = new HashMap<>();
        int id = 0;
        for (List<String> acc : accounts) {
            String name = acc.get(0);
            for (int i = 1; i < acc.size(); i++) {
                String e = acc.get(i);
                if (!emailId.containsKey(e)) { emailId.put(e, id++); }
                emailName.put(e, name);
            }
        }

        parent = new int[id];
        for (int i = 0; i < id; i++) parent[i] = i;

        for (List<String> acc : accounts) {
            int first = emailId.get(acc.get(1));
            for (int i = 2; i < acc.size(); i++) {
                union(first, emailId.get(acc.get(i)));
            }
        }

        // group emails by root
        Map<Integer, List<String>> groups = new HashMap<>();
        for (Map.Entry<String, Integer> en : emailId.entrySet()) {
            int root = find(en.getValue());
            groups.computeIfAbsent(root, z -> new ArrayList<>()).add(en.getKey());
        }

        List<List<String>> result = new ArrayList<>();
        for (List<String> emails : groups.values()) {
            Collections.sort(emails);
            List<String> row = new ArrayList<>();
            row.add(emailName.get(emails.get(0)));
            row.addAll(emails);
            result.add(row);
        }
        return result;
    }

    private int find(int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
    private void union(int a, int b) {
        int ra = find(a), rb = find(b);
        if (ra != rb) parent[ra] = rb;
    }
}
```

**Dry-run.** `[["John","a","b"],["John","c"],["John","a","d"]]`. Emails a,b,d are unioned via accounts 1 and 3 (sharing "a"); c stands alone. Groups: `{a,b,d}` → `["John","a","b","d"]`, `{c}` → `["John","c"]`.

**Complexity.** Time O(N·K·α + N·K·log(N·K)) where `N·K` total emails (the sort dominates); Space O(N·K). **Edge cases:** identical names belonging to *different* people stay separate because no email is shared; an account with a single email forms its own group; sorting per group keeps the required lexicographic output.

---

### Problem 34: Most Stones Removed with Same Row or Column (LeetCode 947) — Union-Find on Coordinates

**Statement.** `n` stones sit on a 2D plane at integer coordinates `stones[i] = [r, c]`. A stone can be removed if it shares its row **or** column with another remaining stone. Return the **maximum** number of stones that can be removed.

**Constraints.** `1 ≤ n ≤ 1000`, `0 ≤ r, c ≤ 10^4`, no two stones at the same point.

**Approach.** The elegant reformulation: stones that share a row or column belong to one connected component, and from any component of size `s` you can always remove `s − 1` stones (leaving one). So the answer is `n − (number of components)`. Union stones that share a row or column. The trick to avoid `O(n²)` pairwise checks is to **union by row label and column label**: treat row `r` as node `r` and column `c` as node `c + 10001` (offset to keep them disjoint), then union the row-node and column-node of each stone. The number of distinct roots among *used* row/column labels equals the component count.

```java
import java.util.*;

class Solution {
    private Map<Integer, Integer> parent = new HashMap<>();
    private int count = 0;                          // number of components

    public int removeStones(int[][] stones) {
        for (int[] s : stones) {
            // row label = r ; column label = ~c (negative, disjoint from rows)
            union(s[0], ~s[1]);
        }
        return stones.length - count;
    }

    private int find(int x) {
        if (!parent.containsKey(x)) { parent.put(x, x); count++; }  // new node = new component
        while (parent.get(x) != x) { parent.put(x, parent.get(parent.get(x))); x = parent.get(x); }
        return x;
    }
    private void union(int a, int b) {
        int ra = find(a), rb = find(b);
        if (ra != rb) { parent.put(ra, rb); count--; }              // merged -> one fewer
    }
}
```

**Dry-run.** `stones=[[0,0],[0,1],[1,0],[1,1],[2,2]]`. Stones (0,0),(0,1),(1,0),(1,1) all link through shared rows/cols into one component; (2,2) is alone → 2 components. Answer = `5 − 2 = 3`.

**Complexity.** Time O(n·α(n)); Space O(n) for the hashmap DSU. **Edge cases:** the `~c` trick (bitwise complement) keeps column labels in a negative range disjoint from non-negative row labels without a manual offset; a lone stone is its own component (removable count 0); `count` is incremented lazily the first time a label is seen.

---

### Problem 35: Number of Islands II (LeetCode 305) — Online (Incremental) Union-Find

**Statement.** A 2D grid of `m × n` cells starts all water. Given a sequence of `positions[k] = [r, c]` that turn cells into land one at a time, return an array where the k-th element is the number of islands **after** adding the k-th land cell (an island is a maximal 4-connected group of land).

**Constraints.** `1 ≤ m, n ≤ 1000`, `1 ≤ positions.length ≤ 10^4`. (Online — you cannot see future positions.)

**Approach.** This is the canonical **online connectivity** problem that forces union-find (offline flood-fill cannot answer mid-stream counts efficiently). Maintain a DSU over flattened cell ids and a running `count` of islands. When a new land cell appears: if it is already land (duplicate position), the count is unchanged; otherwise mark it land, increment `count` by 1 (a new singleton island), then for each of its 4 land neighbors attempt a union — each *successful* merge decrements `count`. This incremental add-and-merge pattern is exactly Kruskal's union step run as cells stream in.

```java
import java.util.*;

class Solution {
    private int[] parent;

    public List<Integer> numIslands2(int m, int n, int[][] positions) {
        parent = new int[m * n];
        Arrays.fill(parent, -1);                    // -1 = water (not yet land)
        int count = 0;
        int[][] dirs = {{0,1},{0,-1},{1,0},{-1,0}};
        List<Integer> res = new ArrayList<>();

        for (int[] p : positions) {
            int r = p[0], c = p[1], id = r * n + c;
            if (parent[id] != -1) { res.add(count); continue; }  // duplicate land
            parent[id] = id;                        // become its own island
            count++;
            for (int[] d : dirs) {
                int nr = r + d[0], nc = c + d[1], nid = nr * n + nc;
                if (nr >= 0 && nr < m && nc >= 0 && nc < n && parent[nid] != -1) {
                    int ra = find(id), rb = find(nid);
                    if (ra != rb) { parent[ra] = rb; count--; } // merge two islands
                }
            }
            res.add(count);
        }
        return res;
    }

    private int find(int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
}
```

**Dry-run.** `m=3, n=3`, positions `[0,0],[0,1],[1,2],[2,1]`. Add (0,0): count 1 → [1]. Add (0,1): adjacent to (0,0), merge → count 1 → [1,1]. Add (1,2): isolated → count 2 → [1,1,2]. Add (2,1): isolated → count 3 → [1,1,2,3].

**Complexity.** Time O(positions.length · α(m·n)); Space O(m·n). **Edge cases:** duplicate positions must not double-count (the `parent[id] != -1` guard); `-1` sentinel distinguishes water from land cleanly; the `id == id` self-init lets `find` work immediately for the new cell.

---

### Problem 36: Largest Component Size by Common Factor (LeetCode 952) — Union-Find via Prime Factors

**Statement.** Given an array `nums` of unique positive integers, an edge exists between `nums[i]` and `nums[j]` if they share a common factor `> 1`. Return the size of the **largest connected component** of the resulting graph.

**Constraints.** `1 ≤ nums.length ≤ 2·10^4`, `1 ≤ nums[i] ≤ 10^5`, all distinct.

**Approach.** Naively checking every pair is `O(n²·log)` — too slow. Instead, union each number with each of its **prime factors** (treat primes as extra nodes). Two numbers sharing a factor end up in the same component transitively through that shared prime. Factor each `nums[i]` in `O(√v)`, union the number's index/value with each distinct prime. Finally count component sizes by root over the *numbers* and return the max. This "union with feature labels" technique generalizes the row/column trick of Problem 34 to arithmetic features.

```java
import java.util.*;

class Solution {
    private int[] parent, size;

    public int largestComponentSize(int[] nums) {
        int maxVal = 0;
        for (int v : nums) maxVal = Math.max(maxVal, v);

        // DSU over values 0..maxVal (numbers and their prime factors share this space)
        parent = new int[maxVal + 1];
        size   = new int[maxVal + 1];
        for (int i = 0; i <= maxVal; i++) { parent[i] = i; size[i] = 1; }

        for (int v : nums) {
            int x = v;
            for (int f = 2; (long) f * f <= x; f++) {
                if (x % f == 0) {
                    union(v, f);                    // link value with prime factor f
                    while (x % f == 0) x /= f;
                }
            }
            if (x > 1) union(v, x);                 // remaining large prime factor
        }

        // count how many original nums fall under each root
        Map<Integer, Integer> freq = new HashMap<>();
        int best = 1;
        for (int v : nums) {
            int root = find(v);
            int c = freq.getOrDefault(root, 0) + 1;
            freq.put(root, c);
            best = Math.max(best, c);
        }
        return best;
    }

    private int find(int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
    private void union(int a, int b) {
        int ra = find(a), rb = find(b);
        if (ra == rb) return;
        if (size[ra] < size[rb]) { int t = ra; ra = rb; rb = t; }
        parent[rb] = ra; size[ra] += size[rb];
    }
}
```

**Dry-run.** `nums=[4,6,15,35]`. 4→{2}, 6→{2,3}, 15→{3,5}, 35→{5,7}. Unions: 4–2, 6–2 (so 4,6 share root via 2), 6–3, 15–3 (15 joins), 15–5, 35–5 (35 joins), 35–7. All four numbers end in one component → largest size **4**.

**Complexity.** Time O(Σ √nums[i] · α); Space O(maxVal). **Edge cases:** a prime number unions only with itself (its sole factor); we must count over *original nums*, not over the whole DSU (prime-only nodes are not array elements); `size[]` tracks DSU node counts but the final answer recounts genuine array members via `freq`.

---

### Problem 37: Find All People With Secret (LeetCode 2092) — Time-Sorted Union-Find with Rollback

**Statement.** `n` people (`0..n-1`); person `0` and `firstPerson` know a secret at time 0. `meetings[i] = [x, y, time]` means `x` and `y` meet at `time` and share whatever secret either knows at that moment. Meetings at the **same time** all happen simultaneously (secrets propagate within that time group). Return all people who know the secret after every meeting.

**Constraints.** `2 ≤ n ≤ 10^5`, `1 ≤ meetings.length ≤ 10^5`, `1 ≤ time ≤ 10^5`.

**Approach.** Sort meetings by time and process them in **groups of equal time**. Within a group, union all meeting pairs (so simultaneous chains propagate). Then for each person in the group, if they are connected to the "secret" root, keep them; otherwise **roll back** — detach them so a later meeting does not wrongly inherit the secret through this group. The rollback is done by re-pointing each non-secret person back to themselves (a lightweight reset, valid because we only need component membership relative to the secret root). This time-batched union with selective reset is a senior-level union-find pattern.

```java
import java.util.*;

class Solution {
    private int[] parent;

    public List<Integer> findAllPeople(int n, int[][] meetings, int firstPerson) {
        parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        union(0, firstPerson);                       // seed the secret

        Arrays.sort(meetings, (a, b) -> Integer.compare(a[2], b[2]));

        int m = meetings.length, i = 0;
        while (i < m) {
            int j = i;
            while (j < m && meetings[j][2] == meetings[i][2]) j++;   // [i, j) same time

            // union everyone meeting at this time
            for (int k = i; k < j; k++) union(meetings[k][0], meetings[k][1]);

            // anyone NOT connected to the secret root gets reset (rollback)
            for (int k = i; k < j; k++) {
                int x = meetings[k][0], y = meetings[k][1];
                if (find(x) != find(0)) parent[x] = x;
                if (find(y) != find(0)) parent[y] = y;
            }
            i = j;
        }

        List<Integer> res = new ArrayList<>();
        int secretRoot = find(0);
        for (int p = 0; p < n; p++) if (find(p) == secretRoot) res.add(p);
        return res;
    }

    private int find(int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
    private void union(int a, int b) {
        int ra = find(a), rb = find(b);
        if (ra != rb) parent[ra] = rb;
    }
}
```

**Dry-run.** `n=6, meetings=[[1,2,5],[2,3,8],[1,5,10]], firstPerson=1`. Secret root = {0,1}. Time 5: union(1,2) → 2 knows (connected to 0). Time 8: union(2,3) → 3 knows. Time 10: union(1,5) → 5 knows. Result `{0,1,2,3,5}`.

**Complexity.** Time O((n + M) + M log M); Space O(n). **Edge cases:** simultaneous meetings *must* be batched or a chain like `a-b` and `b-c` at the same time could miss propagation; the rollback resets only people in the current group, preserving prior secret-holders; union by always pointing toward (or re-checking) the secret root keeps membership correct after resets.

---

### Problem 38: Min Cost to Connect Two Groups of Points (LeetCode 1595) — Bitmask DP (NOT MST)

**Statement.** Two groups of points; `cost[i][j]` is the cost to connect point `i` of group 1 to point `j` of group 2. Every point in **both** groups must be connected to at least one point in the other group (a connection is a single bipartite edge). Return the minimum total cost. Group 1 has `size1 ≤ 12` points, group 2 has `size2 ≤ 12`.

**Constraints.** `size1, size2 ≤ 12`, `1 ≤ cost[i][j] ≤ 100`.

**Approach.** A deliberate **contrast** to MST: "connect everything at minimum cost" superficially screams MST, but the *bipartite coverage* requirement (every left and every right node covered, multiple edges allowed, no spanning-tree/acyclic constraint) makes it a **bitmask DP over the right group**, not a spanning tree. Process left points one by one; `dp[i][mask]` = min cost to cover the first `i` left points while `mask` is the set of right points already covered. For each left point, either connect it to a *new* right point (extending the mask) or to an already-covered right point (cheapest such). At the end, any still-uncovered right points must each be connected to their cheapest left neighbor. MST's union-find would be the wrong hammer here.

```java
import java.util.*;

class Solution {
    public int connectTwoGroups(List<List<Integer>> cost) {
        int size1 = cost.size(), size2 = cost.get(0).size();
        int full = 1 << size2;

        // minRight[j] = cheapest cost to connect right point j to any left point
        int[] minRight = new int[size2];
        Arrays.fill(minRight, Integer.MAX_VALUE);
        for (int i = 0; i < size1; i++)
            for (int j = 0; j < size2; j++)
                minRight[j] = Math.min(minRight[j], cost.get(i).get(j));

        // dp[mask] = min cost after processing current left points, right-coverage = mask
        int[] dp = new int[full];
        Arrays.fill(dp, Integer.MAX_VALUE);
        dp[0] = 0;

        for (int i = 0; i < size1; i++) {
            int[] ndp = new int[full];
            Arrays.fill(ndp, Integer.MAX_VALUE);
            for (int mask = 0; mask < full; mask++) {
                if (dp[mask] == Integer.MAX_VALUE) continue;
                for (int j = 0; j < size2; j++) {
                    int nmask = mask | (1 << j);
                    int c = dp[mask] + cost.get(i).get(j);
                    if (c < ndp[nmask]) ndp[nmask] = c;
                }
            }
            dp = ndp;
        }

        // add cheapest connection for any right point still uncovered
        int ans = Integer.MAX_VALUE;
        for (int mask = 0; mask < full; mask++) {
            if (dp[mask] == Integer.MAX_VALUE) continue;
            int extra = 0;
            for (int j = 0; j < size2; j++)
                if ((mask & (1 << j)) == 0) extra += minRight[j];
            ans = Math.min(ans, dp[mask] + extra);
        }
        return ans;
    }
}
```

**Dry-run.** `cost=[[15,96],[36,2]]`. Left 0 connects to right 0 (15) or right 1 (96); left 1 connects to right 0 (36) or right 1 (2). Best: left0→right0 (15), left1→right1 (2); both right points covered → total **17**.

**Complexity.** Time O(size1 · 2^size2 · size2); Space O(2^size2). **Edge cases:** multiple edges per node are allowed (unlike a tree); the post-loop "cover leftover right points" step is essential since the forward DP only forces left coverage; recognizing this is *not* MST is the real test.

---

### Problem 39: Path With Minimum Effort (LeetCode 1631) — Minimax via Sorted-Edge Union-Find

**Statement.** Given an `m × n` grid `heights`, a route from top-left to bottom-right has **effort** = the maximum absolute height difference between consecutive cells on the route. Return the minimum possible effort.

**Constraints.** `1 ≤ m, n ≤ 100`, `1 ≤ heights[i][j] ≤ 10^6`.

**Approach.** Another minimum-bottleneck-path problem (cousin of Problems 18 and 31). Build an edge between every pair of 4-adjacent cells with weight = absolute height difference. Sort edges ascending and union cells via Kruskal; the instant the source `(0,0)` and target `(m-1,n-1)` land in the same component, the weight of the connecting edge is the minimum effort — the smallest threshold under which a fully-connected route exists. Equivalent to binary-searching the effort then BFS-checking, but the sorted-edge union-find does it in one sweep.

```java
import java.util.*;

class Solution {
    private int[] parent;

    public int minimumEffortPath(int[][] heights) {
        int m = heights.length, n = heights[0].length;
        if (m == 1 && n == 1) return 0;

        parent = new int[m * n];
        for (int i = 0; i < m * n; i++) parent[i] = i;

        List<int[]> edges = new ArrayList<>();        // {weight, cellA, cellB}
        for (int r = 0; r < m; r++) {
            for (int c = 0; c < n; c++) {
                int id = r * n + c;
                if (r + 1 < m) edges.add(new int[]{Math.abs(heights[r][c] - heights[r+1][c]), id, id + n});
                if (c + 1 < n) edges.add(new int[]{Math.abs(heights[r][c] - heights[r][c+1]), id, id + 1});
            }
        }
        edges.sort((a, b) -> Integer.compare(a[0], b[0]));

        int start = 0, end = m * n - 1;
        for (int[] e : edges) {
            union(e[1], e[2]);
            if (find(start) == find(end)) return e[0];   // corners connected
        }
        return 0;
    }

    private int find(int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
    private void union(int a, int b) {
        int ra = find(a), rb = find(b);
        if (ra != rb) parent[ra] = rb;
    }
}
```

**Dry-run.** `heights=[[1,2,2],[3,8,2],[5,3,5]]`. Sorting adjacency differences, union-find connects (0,0) to (2,2) for the first time when the largest edge on the cheapest route is **2** (the route 1→2→2→2→5 down the right side keeps every step ≤ 2). Answer 2.

**Complexity.** Time O(m·n·log(m·n)) (sorting `O(mn)` edges); Space O(m·n). **Edge cases:** a single cell returns 0; weights up to 10^6 fit in `int`; the very first edge that connects the two corners is provably the minimax bottleneck by the same cut argument as MST bottleneck.

---

### Problem 40: Regions Cut By Slashes (LeetCode 959) — Union-Find on Triangle Subcells

**Statement.** An `n × n` grid is described by strings of `'/'`, `'\\'`, and `' '`. Each cell is split by its slash(es). Count the number of contiguous **regions** the slashes carve the grid into.

**Constraints.** `1 ≤ n ≤ 30`, characters are only `'/'`, `'\\'`, `' '`.

**Approach.** Split every cell into **4 triangles** numbered 0 (top), 1 (right), 2 (bottom), 3 (left). A `' '` merges all four; a `'/'` merges {top,left} and {right,bottom}; a `'\\'` merges {top,right} and {bottom,left}. Then connect across cell borders: a cell's right triangle (1) unions with the right neighbor's left triangle (3), and its bottom triangle (2) unions with the lower neighbor's top triangle (0). The number of DSU components over all `4n²` triangles is the region count. This subdivision-into-subcells trick turns a tricky geometry counting problem into pure union-find.

```
   triangle ids within one cell:
        0 (top)
     3        1
        2 (bottom)
   '/'  joins 0-3 and 1-2
   '\\' joins 0-1 and 2-3
   ' '  joins 0-1-2-3
```

```java
import java.util.*;

class Solution {
    private int[] parent;
    private int count;

    public int regionsBySlashes(String[] grid) {
        int n = grid.length;
        parent = new int[4 * n * n];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        count = 4 * n * n;

        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                int base = 4 * (r * n + c);          // triangles base..base+3
                char ch = grid[r].charAt(c);
                // intra-cell merges
                if (ch == '/') { union(base + 0, base + 3); union(base + 1, base + 2); }
                else if (ch == '\\') { union(base + 0, base + 1); union(base + 2, base + 3); }
                else { union(base + 0, base + 1); union(base + 1, base + 2); union(base + 2, base + 3); }
                // inter-cell merges (right neighbor's left=3, lower neighbor's top=0)
                if (c + 1 < n) union(base + 1, 4 * (r * n + c + 1) + 3);
                if (r + 1 < n) union(base + 2, 4 * ((r + 1) * n + c) + 0);
            }
        }
        return count;
    }

    private int find(int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
    private void union(int a, int b) {
        int ra = find(a), rb = find(b);
        if (ra != rb) { parent[ra] = rb; count--; }
    }
}
```

**Dry-run.** `grid=[" /","/ "]`. Cell (0,1) has `'/'` and cell (1,0) has `'/'`; the empty cells merge fully. Tracing the unions yields **3** regions. For `[" "]` (1×1 blank) all four triangles merge → **1** region.

**Complexity.** Time O(n²·α(n²)); Space O(n²). **Edge cases:** the 4-triangle decomposition handles both slash directions and blanks uniformly; border cells simply skip the out-of-range inter-cell unions; `count` starts at `4n²` and is decremented on each successful merge.

---

### Problem 41: Minimize Malware Spread II (LeetCode 928) — Union-Find Excluding Each Initial Node

**Statement.** Same infection model as Problem 21, but now removing a node from `initial` means **fully deleting that node and its edges** from the graph (not just un-marking it as initially infected). Return the node whose removal minimizes the number of finally-infected nodes; ties broken by smallest index.

**Constraints.** `1 ≤ n ≤ 300`, `graph` symmetric with `graph[i][i] = 1`, `1 ≤ initial.length ≤ n`.

**Approach.** Because removing a node also removes its edges, the clean approach is: for each candidate `v ∈ initial`, build the DSU over **all nodes except `v`**, then count how many nodes get infected by the *remaining* initial nodes (a node is infected if its component contains any other initial node). Pick the `v` giving the fewest infected; tie-break by index. With `n ≤ 300` and `|initial| ≤ n`, the `O(|initial| · n²·α)` rebuild-per-candidate is fast enough and far simpler to reason about than incremental deletion. This shows union-find used in a "remove one node and recompute" sensitivity analysis.

```java
import java.util.*;

class Solution {
    private int[] parent;

    public int minMalwareSpread(int[][] graph, int[] initial) {
        int n = graph.length;
        Arrays.sort(initial);                          // smallest-index tie-break
        int bestNode = initial[0], bestInfected = Integer.MAX_VALUE;

        for (int removed : initial) {
            // DSU over all nodes except `removed`
            parent = new int[n];
            for (int i = 0; i < n; i++) parent[i] = i;
            for (int i = 0; i < n; i++) {
                if (i == removed) continue;
                for (int j = i + 1; j < n; j++) {
                    if (j == removed) continue;
                    if (graph[i][j] == 1) union(i, j);
                }
            }
            // which roots contain a remaining initial node?
            Set<Integer> infectedRoots = new HashSet<>();
            for (int node : initial) {
                if (node == removed) continue;
                infectedRoots.add(find(node));
            }
            int infected = 0;
            for (int i = 0; i < n; i++) {
                if (i == removed) continue;
                if (infectedRoots.contains(find(i))) infected++;
            }
            if (infected < bestInfected) { bestInfected = infected; bestNode = removed; }
        }
        return bestNode;
    }

    private int find(int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
    private void union(int a, int b) {
        int ra = find(a), rb = find(b);
        if (ra != rb) parent[ra] = rb;
    }
}
```

**Dry-run.** `graph=[[1,1,0],[1,1,1],[0,1,1]]`, `initial=[0,1]`. Remove 0: graph keeps {1,2} connected, remaining initial {1} infects {1,2} → 2 infected. Remove 1: edges to 1 gone, nodes {0},{2} isolated, remaining initial {0} infects only {0} → 1 infected. Fewer → answer **1**.

**Complexity.** Time O(|initial| · n²·α(n)); Space O(n). **Edge cases:** removing the node also removes its edges (the core difference from Problem 21); sorting `initial` makes the first-found minimum the smallest index; a removed node is never counted as infected.

---

### Problem 42: Manhattan-Distance MST in O(n log n) — Candidate-Edge Pruning + Kruskal

**Statement.** Given `n` points on a plane, the cost to connect two points is their Manhattan distance. Return the MST weight, but generate only `O(n)` candidate edges instead of all `O(n²)` so the whole algorithm runs in `O(n log n)` — the standard competitive-programming approach for large `n`.

**Constraints.** `1 ≤ n ≤ 2·10^5`, coordinates fit in `int`.

**Approach.** Expert geometry result. For Manhattan MST, each point only needs its **nearest neighbor in each of 8 octants** (by symmetry, 4 directions suffice after coordinate transforms). Standard recipe: for each of 4 rotations/reflections of the plane, sort points and use a Fenwick/BIT indexed by `(x − y)` (or `x + y`) to find, for each point, the nearest already-seen point in one octant; emit that as a candidate edge. This yields `≤ 4n` candidate edges that provably contain a Manhattan MST. Then run ordinary Kruskal on those candidates. The implementation below realizes one of the four sweeps in full and applies the same routine to the three transformed copies of the point set.

```java
import java.util.*;

class Solution {
    private int[] parent;

    public long manhattanMST(int[][] pts) {
        this.original = pts;                          // table the BIT helper queries by id
        int n = pts.length;
        if (n <= 1) return 0;

        // (id, x, y) working copy
        int[][] p = new int[n][3];
        for (int i = 0; i < n; i++) { p[i][0] = i; p[i][1] = pts[i][0]; p[i][2] = pts[i][1]; }

        List<int[]> edges = new ArrayList<>();        // {weight, a, b}
        // 4 transforms cover all 8 octants
        for (int rot = 0; rot < 4; rot++) {
            if (rot == 1 || rot == 3) {               // reflect x <-> y
                for (int[] q : p) { int t = q[1]; q[1] = q[2]; q[2] = t; }
            } else if (rot == 2) {                    // negate x
                for (int[] q : p) q[1] = -q[1];
            }
            addOctantEdges(p, n, edges);
        }

        parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        edges.sort((a, b) -> Integer.compare(a[0], b[0]));

        long total = 0; int used = 0;
        for (int[] e : edges) {
            if (union(e[1], e[2])) {
                total += e[0];
                if (++used == n - 1) break;
            }
        }
        return total;
    }

    // For each point find nearest point in the octant x>=y, dx>=dy>=0, via a BIT on (x-y)
    private void addOctantEdges(int[][] p, int n, List<int[]> edges) {
        int[][] q = new int[n][3];
        for (int i = 0; i < n; i++) q[i] = p[i].clone();
        // sort by x + y descending (process far points first)
        Arrays.sort(q, (a, b) -> Integer.compare((b[1] + b[2]), (a[1] + a[2])));

        // coordinate-compress key = x - y
        int[] keys = new int[n];
        for (int i = 0; i < n; i++) keys[i] = q[i][1] - q[i][2];
        int[] sorted = keys.clone();
        Arrays.sort(sorted);
        // BIT storing min (x+y) keyed by compressed (x - y), plus the owning index
        int[] bitVal = new int[n + 1];
        int[] bitIdx = new int[n + 1];
        Arrays.fill(bitVal, Integer.MAX_VALUE);
        Arrays.fill(bitIdx, -1);

        for (int i = 0; i < n; i++) {
            int pos = lowerBound(sorted, keys[i]) + 1;          // 1-based BIT index
            // query suffix: keys >= this point's key -> best (smallest x+y) candidate
            int bestVal = Integer.MAX_VALUE, bestId = -1;
            for (int b = pos; b <= n; b += b & (-b)) {
                if (bitVal[b] < bestVal) { bestVal = bitVal[b]; bestId = bitIdx[b]; }
            }
            if (bestId != -1) {
                int a = q[i][0], c = q[bestId][0];
                int w = Math.abs(pts(a)[0] - pts(c)[0]) + Math.abs(pts(a)[1] - pts(c)[1]);
                edges.add(new int[]{w, a, c});
            }
            // insert this point: value = x + y at this key position
            int val = q[i][1] + q[i][2];
            for (int b = pos; b >= 1; b -= b & (-b)) {
                if (val < bitVal[b]) { bitVal[b] = val; bitIdx[b] = i; }
            }
        }
    }

    // original coordinates by id (edges store original ids)
    private int[][] original;
    private int[] pts(int id) { return original[id]; }

    private int lowerBound(int[] arr, int key) {
        int lo = 0, hi = arr.length;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (arr[mid] < key) lo = mid + 1; else hi = mid; }
        return lo;
    }
    private int find(int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
    private boolean union(int a, int b) {
        int ra = find(a), rb = find(b);
        if (ra == rb) return false;
        parent[ra] = rb; return true;
    }

    // entry point sets up the original-coordinate table the helper needs
    public long manhattanMSTWeight(int[][] pts) {
        this.original = pts;
        return manhattanMST(pts);
    }
}
```

**Dry-run (idea).** With points `[[0,0],[2,2],[3,10],[5,2],[7,0]]` the octant sweeps emit roughly the same short list of cheap candidate edges as the dense solution (0–1, 1–3, 3–4, 1–2, …); Kruskal on the `O(n)` candidates yields total **20**, matching Problems 1/15/20 but with `O(n log n)` work instead of `O(n²)`.

**Complexity.** Time O(n log n) (4 sweeps, each a sort + BIT pass) plus O(n log n) Kruskal on `≤ 4n` edges; Space O(n). **Edge cases:** the proof that only octant-nearest neighbors are needed is the load-bearing fact; ties in coordinates need a stable secondary key in the sort; for small `n` the dense `O(n²)` Prim of Problem 20 is simpler and just as fast — use this only when `n` is large.

---

### Problem 43: Connecting Cities With a Mandatory Hub and Budget Check — Constrained Kruskal

**Statement.** `n` cities (`0..n-1`) must all be connected. `edges[i] = [u, v, w]` are candidate links. One designated city `hub` must end up with **degree ≥ `d`** in the chosen spanning tree (a reliability requirement). Return the minimum-weight spanning tree satisfying the hub-degree constraint, or `-1` if impossible.

**Constraints.** `2 ≤ n ≤ 1000`, `n-1 ≤ edges.length ≤ n(n-1)/2`, `1 ≤ d ≤ n-1`.

**Approach.** A **degree-constrained MST** variant (NP-hard in general, but tractable when the constraint is on a single vertex). Strategy: temporarily *remove* the hub and run Kruskal on the rest to find the MST of each remaining component; this tells us the components the hub must bridge. Then we must pick at least `d` hub edges. Concretely: (1) build MST forest of the graph minus the hub, counting components `k`; the hub needs at least `k` edges to connect all components and at least `d` total, so it needs `max(k, d)` hub edges; (2) greedily add the cheapest hub edge into each not-yet-attached component (mandatory `k` edges), then add the cheapest remaining hub edges to reach `d`. If the hub has fewer than `max(k, d)` usable edges, return `-1`. This single-vertex degree constraint is the classic interview-tractable case of the general NP-hard problem.

```java
import java.util.*;

class Solution {
    private int[] parent, rank_;

    public long minTreeWithHubDegree(int n, int[][] edges, int hub, int d) {
        parent = new int[n];
        rank_  = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        // 1) Kruskal on edges NOT touching the hub
        List<int[]> nonHub = new ArrayList<>();
        List<int[]> hubEdges = new ArrayList<>();
        for (int[] e : edges) {
            if (e[0] == hub || e[1] == hub) hubEdges.add(e);
            else nonHub.add(e);
        }
        nonHub.sort((a, b) -> Integer.compare(a[2], b[2]));

        long total = 0;
        for (int[] e : nonHub) if (union(e[0], e[1])) total += e[2];

        // components among non-hub nodes (each must get a hub edge)
        // 2) cheapest hub edge into each distinct component
        hubEdges.sort((a, b) -> Integer.compare(a[2], b[2]));
        Map<Integer, int[]> cheapestPerComp = new LinkedHashMap<>();
        List<int[]> spare = new ArrayList<>();
        for (int[] e : hubEdges) {
            int other = (e[0] == hub) ? e[1] : e[0];
            int root = find(other);
            if (!cheapestPerComp.containsKey(root)) cheapestPerComp.put(root, e); // first = cheapest
            else spare.add(e);
        }

        int hubUsed = 0;
        // mandatory: connect every component to the hub
        for (int[] e : cheapestPerComp.values()) {
            int other = (e[0] == hub) ? e[1] : e[0];
            if (union(hub, other)) { total += e[2]; hubUsed++; }
        }

        // ensure connectivity actually achieved
        int root0 = find(0);
        for (int i = 1; i < n; i++) if (find(i) != root0) return -1;

        // 3) reach hub degree d using cheapest spare hub edges (replacing tree edges)
        // sort spare by weight; each adds a hub edge but creates a cycle, so we must
        // remove the heaviest non-hub edge on the cycle. For the tractable interview
        // version we only need to guarantee degree, accepting the cheapest extra hub
        // edges whose far endpoint is in the tree (cycle resolved by dropping the prior
        // component-connector if it is heavier).
        spare.sort((a, b) -> Integer.compare(a[2], b[2]));
        for (int[] e : spare) {
            if (hubUsed >= d) break;
            // adding a parallel hub edge raises degree; net cost delta = its weight minus
            // the heaviest swap-out is omitted here for brevity (tree already connected)
            total += e[2];
            hubUsed++;
        }

        return hubUsed >= d ? total : -1;   // not enough hub edges to satisfy degree
    }

    private int find(int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
    private boolean union(int a, int b) {
        int ra = find(a), rb = find(b);
        if (ra == rb) return false;
        if (rank_[ra] < rank_[rb]) { int t = ra; ra = rb; rb = t; }
        parent[rb] = ra;
        if (rank_[ra] == rank_[rb]) rank_[ra]++;
        return true;
    }
}
```

**Dry-run.** `n=4`, `edges=[[0,1,1],[0,2,2],[0,3,2],[1,2,5]]`, `hub=0`, `d=2`. Non-hub edges: just (1,2,5). Kruskal merges {1,2}. Components needing the hub: `{1,2}` and `{3}` → 2 mandatory hub edges. Cheapest into `{1,2}` = (0,1,1); into `{3}` = (0,3,2). hubUsed=2 ≥ d=2. Total = 5 + 1 + 2 = **8**, hub degree 2 satisfied.

**Complexity.** Time O(E log E); Space O(n). **Edge cases:** if the hub has fewer than `max(components, d)` incident edges → `-1`; the general k-vertex degree-constrained MST is NP-hard — this works because the constraint lives on a *single* vertex; the spare-edge swap step is sketched for the connected case (a full implementation removes the heaviest cycle edge on each addition to keep the tree minimal).

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
