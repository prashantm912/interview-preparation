# Graphs

Graphs model **relationships** — nodes (vertices) connected by edges. They power social networks, maps, dependency resolvers, compilers, and routing. This file covers traversal, connectivity, ordering, and coloring. Weighted shortest-path (Dijkstra/Bellman-Ford) and minimum spanning trees (Kruskal/Prim) get their own dedicated files.

[← Back to master index](../README.md) | [← DSA index](README.md)

---

## Concept & Intuition

A graph `G = (V, E)` is a set of vertices `V` and edges `E ⊆ V × V`. Edges can be **directed** (one-way, like Twitter follows) or **undirected** (mutual, like Facebook friends), and **weighted** (carry a cost) or **unweighted**.

**When to reach for a graph:** any time data has *connections* — "is A reachable from B?", "what is the order of dependencies?", "are these two things in the same group?", "what is the cheapest route?". If you find yourself drawing arrows between entities on a whiteboard, you are modeling a graph.

### Representations

| Representation | Space | Edge lookup `(u,v)` | Iterate neighbors of `u` | Good for |
|----------------|-------|---------------------|--------------------------|----------|
| **Adjacency list** | `O(V + E)` | `O(deg(u))` | `O(deg(u))` | Sparse graphs (most interview graphs) |
| **Adjacency matrix** | `O(V²)` | `O(1)` | `O(V)` | Dense graphs, frequent edge queries |
| **Edge list** | `O(E)` | `O(E)` | `O(E)` | Kruskal MST, simple input parsing |

In Java, the workhorse is an adjacency list: `List<List<Integer>>` or `Map<Integer, List<Integer>>`.

```
Undirected graph         Adjacency list
                         0: [1, 2]
   0 --- 1               1: [0, 2]
   | \   |               2: [0, 1, 3]
   |  \  |               3: [2]
   2 --- 3 ... wait
   actual edges: 0-1, 0-2, 1-2, 2-3

   0───1
   │ ╲ │
   │  ╲│
   2───3   (here 0-3 not present; 2-3 is)
```

### Core invariants

- **BFS** explores in *layers* (distance order). On an unweighted graph the first time you reach a node is via a shortest path. Uses a **queue** (FIFO).
- **DFS** explores as *deep as possible* before backtracking. Uses a **stack** (explicit or the call stack). It naturally exposes structure: tree edges, back edges (cycles), discovery/finish times.
- **Mark visited on enqueue/before recursing**, never only on dequeue — otherwise a node can be added to the frontier many times and you blow up to exponential work or revisit.

### Edge classification (DFS on directed graphs)
- **Tree edge** — to an unvisited node.
- **Back edge** — to an ancestor currently on the recursion stack ⇒ a **cycle**.
- **Forward / cross edge** — to an already-finished node.

This classification is the engine behind cycle detection, topological sort, and articulation points.

---

## Complexity Cheat-Sheet

Let `V` = vertices, `E` = edges. Adjacency-list representation unless noted.

| Operation | Time | Space |
|-----------|------|-------|
| Build adjacency list | `O(V + E)` | `O(V + E)` |
| BFS / DFS traversal | `O(V + E)` | `O(V)` |
| Connected components (undirected) | `O(V + E)` | `O(V)` |
| Cycle detection (directed, DFS colors) | `O(V + E)` | `O(V)` |
| Cycle detection (undirected, DFS/Union-Find) | `O(V + E)` / `O(E·α(V))` | `O(V)` |
| Bipartite check (2-coloring) | `O(V + E)` | `O(V)` |
| Topological sort (Kahn / DFS) | `O(V + E)` | `O(V)` |
| Number of islands (grid) | `O(R·C)` | `O(R·C)` |
| Articulation points / bridges (Tarjan) | `O(V + E)` | `O(V)` |
| Adjacency-matrix BFS/DFS | `O(V²)` | `O(V²)` |

`α` is the inverse Ackermann function (effectively constant) from Union-Find with path compression + union by rank.

---

## Patterns & Recognition

Recognize a graph problem when you see these signals:

1. **"Connected", "reachable", "groups", "regions"** → connected components via BFS/DFS or Union-Find. Grids ("islands", "regions", "provinces") are implicit graphs where each cell has up to 4 (or 8) neighbors.
2. **"Shortest path" on an unweighted graph / "minimum number of steps/moves"** → BFS. Each layer = one step. (Weighted → Dijkstra, in the shortest-path file.)
3. **"Order", "prerequisite", "build dependency", "before/after"** → topological sort (Kahn's BFS or DFS). Requires a **DAG**; a cycle means no valid order.
4. **"Can it be split into two sets so no edge stays within a set?" / "two-color"** → bipartite check.
5. **"Cycle?", "deadlock?", "will it finish?"** → cycle detection. Directed uses 3-color DFS or in-degree (Kahn); undirected uses DFS-with-parent or Union-Find.
6. **"Critical connection", "single point of failure"** → articulation points / bridges (Tarjan, low-link values).
7. **Transform one state into another via legal moves** → model states as nodes, moves as edges, then BFS/DFS (word ladder, sliding puzzle, lock combinations).
8. **Cost / weight on edges** → shortest-path or MST files.

Heuristic: BFS for *shortest/level*, DFS for *exhaustive/structural*, Union-Find for *dynamic connectivity/grouping*, Kahn for *ordering*.

---

## Coding Problems

### Problem 1: Build & Traverse a Graph (BFS and DFS)

**Statement.** Given `n` nodes labeled `0..n-1` and an undirected edge list, build an adjacency list and return BFS and DFS orders starting from node `0`.
**Constraints.** `1 ≤ n ≤ 10^5`, `0 ≤ edges.length ≤ 2·10^5`.

**Approach.** Build `List<List<Integer>>`. BFS with a queue + `visited[]`; DFS recursively. Mark visited at the moment you push/visit.

```java
import java.util.*;

public class GraphTraversal {
    static List<List<Integer>> buildGraph(int n, int[][] edges) {
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
        for (int[] e : edges) {
            adj.get(e[0]).add(e[1]);
            adj.get(e[1]).add(e[0]); // undirected
        }
        return adj;
    }

    static List<Integer> bfs(List<List<Integer>> adj, int src) {
        List<Integer> order = new ArrayList<>();
        boolean[] visited = new boolean[adj.size()];
        Queue<Integer> q = new ArrayDeque<>();
        q.offer(src);
        visited[src] = true;          // mark on enqueue
        while (!q.isEmpty()) {
            int u = q.poll();
            order.add(u);
            for (int v : adj.get(u)) {
                if (!visited[v]) {
                    visited[v] = true;
                    q.offer(v);
                }
            }
        }
        return order;
    }

    static List<Integer> dfs(List<List<Integer>> adj, int src) {
        List<Integer> order = new ArrayList<>();
        boolean[] visited = new boolean[adj.size()];
        dfsHelper(adj, src, visited, order);
        return order;
    }

    static void dfsHelper(List<List<Integer>> adj, int u, boolean[] visited, List<Integer> order) {
        visited[u] = true;
        order.add(u);
        for (int v : adj.get(u)) {
            if (!visited[v]) dfsHelper(adj, v, visited, order);
        }
    }
}
```

**Dry run.** `n=4`, edges `[[0,1],[0,2],[1,2],[2,3]]`. BFS from 0: visit 0, enqueue 1,2 → visit 1 (neighbor 2 already queued) → visit 2, enqueue 3 → visit 3. Order `[0,1,2,3]`. DFS from 0: 0→1→2→3, order `[0,1,2,3]`.

**Time:** `O(V + E)`. **Space:** `O(V)` (recursion/queue + visited).
**Follow-ups.** Iterative DFS with an explicit stack (avoid recursion overflow on deep graphs); BFS that records distance/parent; directed variant (drop the reverse edge).

---

### Problem 2: Flood Fill (LeetCode 733)

**Statement.** Given an `m×n` image of pixel colors, a start `(sr, sc)`, and a `newColor`, recolor the start pixel and all 4-directionally connected pixels of the same original color.
**Constraints.** `1 ≤ m, n ≤ 50`.

**Approach.** Classic grid DFS/BFS. Guard against the no-op case where `newColor == oldColor` (otherwise infinite recursion).

```java
public class FloodFill {
    public int[][] floodFill(int[][] image, int sr, int sc, int color) {
        int old = image[sr][sc];
        if (old == color) return image;          // critical guard
        fill(image, sr, sc, old, color);
        return image;
    }
    private void fill(int[][] img, int r, int c, int old, int color) {
        if (r < 0 || r >= img.length || c < 0 || c >= img[0].length || img[r][c] != old) return;
        img[r][c] = color;
        fill(img, r + 1, c, old, color);
        fill(img, r - 1, c, old, color);
        fill(img, r, c + 1, old, color);
        fill(img, r, c - 1, old, color);
    }
}
```

**Dry run.** `image=[[1,1,1],[1,1,0],[1,0,1]]`, start `(1,1)`, color `2`. Old color is 1; every 1 connected to (1,1) becomes 2 → `[[2,2,2],[2,2,0],[2,0,1]]`. The lone 1 at (2,2) is isolated by 0s, so it stays.

**Time:** `O(m·n)`. **Space:** `O(m·n)` recursion in the worst case.
**Follow-ups.** 8-directional fill; iterative BFS to avoid stack overflow on large images; "paint bucket" with tolerance threshold.

---

### Problem 3: Number of Islands (LeetCode 200)

**Statement.** Given an `m×n` grid of `'1'` (land) and `'0'` (water), count islands (groups of land connected 4-directionally).
**Constraints.** `1 ≤ m, n ≤ 300`.

**Approach.** Scan the grid. On each unvisited `'1'`, increment the count and flood-fill (sink) the whole island so it is counted once. DFS or BFS both work; here DFS that mutates the grid in place (no extra visited array).

```java
public class NumberOfIslands {
    public int numIslands(char[][] grid) {
        int count = 0;
        for (int r = 0; r < grid.length; r++)
            for (int c = 0; c < grid[0].length; c++)
                if (grid[r][c] == '1') {
                    count++;
                    sink(grid, r, c);
                }
        return count;
    }
    private void sink(char[][] g, int r, int c) {
        if (r < 0 || r >= g.length || c < 0 || c >= g[0].length || g[r][c] != '1') return;
        g[r][c] = '0';                  // mark visited by sinking
        sink(g, r + 1, c);
        sink(g, r - 1, c);
        sink(g, r, c + 1);
        sink(g, r, c - 1);
    }
}
```

**Dry run.** Grid `11000 / 11000 / 00100 / 00011`. First `'1'` at (0,0) sinks the top-left 2×2 block → count 1. Next unsunk `'1'` at (2,2) → count 2. Then (3,3)/(3,4) → count 3. Answer **3**.

**Time:** `O(m·n)`. **Space:** `O(m·n)` recursion worst case.
**Follow-ups.** Union-Find variant (good when grid is streamed / edges added dynamically — "Number of Islands II"); count island *area* / max area; preserve the input grid (use a separate `visited[][]`); diagonal connectivity.

---

### Problem 4: Connected Components / Number of Provinces (LeetCode 547)

**Statement.** `n` cities, `isConnected[i][j] == 1` if `i` and `j` are directly connected. A province is a maximal group of directly or indirectly connected cities. Return the number of provinces.
**Constraints.** `1 ≤ n ≤ 200`.

**Approach.** Adjacency matrix → undirected graph. Count components with DFS, or use Union-Find for a clean `α`-time solution.

```java
public class Provinces {
    public int findCircleNum(int[][] isConnected) {
        int n = isConnected.length;
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        int components = n;
        for (int i = 0; i < n; i++)
            for (int j = i + 1; j < n; j++)
                if (isConnected[i][j] == 1 && union(parent, i, j)) components--;
        return components;
    }
    private int find(int[] p, int x) {
        while (p[x] != x) { p[x] = p[p[x]]; x = p[x]; } // path halving
        return x;
    }
    private boolean union(int[] p, int a, int b) {
        int ra = find(p, a), rb = find(p, b);
        if (ra == rb) return false;
        p[ra] = rb;
        return true;
    }
}
```

**Dry run.** `[[1,1,0],[1,1,0],[0,0,1]]`: union(0,1) merges → components 3→2. City 2 isolated. Answer **2**.

**Time:** `O(n²·α(n))` (we scan the matrix). **Space:** `O(n)`.
**Follow-ups.** Return component sizes; DFS/BFS alternative; "Graph Valid Tree" (exactly one component AND `edges == n-1`, i.e. no cycle).

---

### Problem 5: Clone Graph (LeetCode 133)

**Statement.** Given a reference to a node in a connected undirected graph, return a deep copy. Each node has a value and a list of neighbors.
**Constraints.** `0 ≤ nodes ≤ 100`; graph is connected; no self-loops/repeated edges.

**Approach.** Traverse (BFS or DFS) while keeping a `HashMap<Node, Node>` from original to clone. The map doubles as the "visited" set and prevents infinite recursion on cycles.

```java
import java.util.*;

class Node {
    public int val;
    public List<Node> neighbors;
    public Node(int v) { val = v; neighbors = new ArrayList<>(); }
}

public class CloneGraph {
    private final Map<Node, Node> clones = new HashMap<>();

    public Node cloneGraph(Node node) {
        if (node == null) return null;
        if (clones.containsKey(node)) return clones.get(node);
        Node copy = new Node(node.val);
        clones.put(node, copy);               // record BEFORE recursing into neighbors
        for (Node nei : node.neighbors)
            copy.neighbors.add(cloneGraph(nei));
        return copy;
    }
}
```

**Dry run.** Node1↔Node2. Clone1 created and mapped; recurse to Node2 → Clone2 created, recurse back to Node1 which is already in the map, returning Clone1. Clone2.neighbors=[Clone1], Clone1.neighbors=[Clone2]. Deep copy with the cycle preserved.

**Time:** `O(V + E)`. **Space:** `O(V)` map + recursion.
**Follow-ups.** Iterative BFS version; clone a *directed* graph; "Copy List with Random Pointer" is the same map-old-to-new trick.

---

### Problem 6: Cycle Detection in an Undirected Graph

**Statement.** Given an undirected graph, determine whether it contains a cycle.
**Constraints.** `1 ≤ V ≤ 10^5`.

**Approach.** DFS tracking the parent: if we reach a visited node that is **not** the parent we came from, that is a back edge → cycle. Handle disconnected graphs by starting DFS from every unvisited vertex. (Union-Find is an equally clean alternative: a cycle exists iff an edge connects two vertices already in the same set.)

```java
import java.util.*;

public class UndirectedCycle {
    public boolean hasCycle(int n, List<List<Integer>> adj) {
        boolean[] visited = new boolean[n];
        for (int i = 0; i < n; i++)
            if (!visited[i] && dfs(adj, i, -1, visited)) return true;
        return false;
    }
    private boolean dfs(List<List<Integer>> adj, int u, int parent, boolean[] visited) {
        visited[u] = true;
        for (int v : adj.get(u)) {
            if (!visited[v]) {
                if (dfs(adj, v, u, visited)) return true;
            } else if (v != parent) {     // visited and not where we came from
                return true;
            }
        }
        return false;
    }
}
```

**Dry run.** Triangle 0-1, 1-2, 2-0. DFS 0(parent -1)→1(parent 0)→2(parent 1); from 2 we see neighbor 0 which is visited and ≠ parent(1) → cycle found.

**Time:** `O(V + E)`. **Space:** `O(V)`.
**Follow-ups.** Why the parent check fails with multi-edges/self-loops (handle separately); Union-Find version; count number of independent cycles (= `E - V + components`).

---

### Problem 7: Course Schedule — Cycle Detection in a Directed Graph (LeetCode 207)

**Statement.** `numCourses` courses `0..n-1` and prerequisite pairs `[a, b]` meaning you must take `b` before `a`. Return `true` if you can finish all courses (i.e., the graph is a DAG).
**Constraints.** `1 ≤ numCourses ≤ 2000`, `0 ≤ prerequisites.length ≤ 5000`.

**Approach (DFS 3-color).** `WHITE` = unvisited, `GRAY` = on the current recursion stack, `BLACK` = fully processed. Hitting a `GRAY` node = back edge = cycle.

```java
import java.util.*;

public class CourseSchedule {
    public boolean canFinish(int numCourses, int[][] prerequisites) {
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < numCourses; i++) adj.add(new ArrayList<>());
        for (int[] p : prerequisites) adj.get(p[1]).add(p[0]); // b -> a
        int[] color = new int[numCourses];                     // 0=white,1=gray,2=black
        for (int i = 0; i < numCourses; i++)
            if (color[i] == 0 && hasCycle(adj, i, color)) return false;
        return true;
    }
    private boolean hasCycle(List<List<Integer>> adj, int u, int[] color) {
        color[u] = 1;
        for (int v : adj.get(u)) {
            if (color[v] == 1) return true;                    // back edge
            if (color[v] == 0 && hasCycle(adj, v, color)) return true;
        }
        color[u] = 2;
        return false;
    }
}
```

**Dry run.** `numCourses=2`, prereqs `[[1,0],[0,1]]`. Edge 0→1 and 1→0. DFS 0(gray)→1(gray)→ neighbor 0 is gray → cycle → return `false`. With only `[[1,0]]` there is no cycle → `true`.

**Time:** `O(V + E)`. **Space:** `O(V + E)`.
**Follow-ups.** Kahn's BFS variant (next problem); detect cycle in a directed graph generally; "Course Schedule III" (greedy + heap, a different problem).

---

### Problem 8: Course Schedule II — Topological Sort via Kahn's Algorithm (LeetCode 210)

**Statement.** Same setup as Problem 7, but return *a valid ordering* of courses, or an empty array if impossible.
**Constraints.** Same as above.

**Approach.** Kahn's algorithm. Compute in-degrees; seed a queue with all in-degree-0 nodes; repeatedly pop a node into the result and decrement neighbors' in-degrees, enqueuing any that hit 0. If the result has fewer than `n` nodes, a cycle exists.

```java
import java.util.*;

public class CourseScheduleII {
    public int[] findOrder(int numCourses, int[][] prerequisites) {
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < numCourses; i++) adj.add(new ArrayList<>());
        int[] indeg = new int[numCourses];
        for (int[] p : prerequisites) {        // edge b -> a
            adj.get(p[1]).add(p[0]);
            indeg[p[0]]++;
        }
        Queue<Integer> q = new ArrayDeque<>();
        for (int i = 0; i < numCourses; i++)
            if (indeg[i] == 0) q.offer(i);

        int[] order = new int[numCourses];
        int idx = 0;
        while (!q.isEmpty()) {
            int u = q.poll();
            order[idx++] = u;
            for (int v : adj.get(u))
                if (--indeg[v] == 0) q.offer(v);
        }
        return idx == numCourses ? order : new int[0]; // cycle => incomplete
    }
}
```

**Dry run.** `numCourses=4`, prereqs `[[1,0],[2,0],[3,1],[3,2]]`. In-deg: 0→0,1→1,2→1,3→2. Queue=[0]. Pop 0 → 1,2 hit 0 → queue=[1,2]. Pop 1 → 3's in-deg 2→1. Pop 2 → 3's in-deg 1→0, enqueue 3. Pop 3. Order `[0,1,2,3]` (one valid topo order).

**Time:** `O(V + E)`. **Space:** `O(V + E)`.
**Follow-ups.** DFS topo sort (push to stack on finish, reverse); detect *unique* topological order (queue size never exceeds 1); "Alien Dictionary" (build edges from char ordering, then topo sort); lexicographically smallest order (use a `PriorityQueue` instead of a plain queue).

---

### Problem 9: Is Graph Bipartite? (LeetCode 785)

**Statement.** Given an undirected graph as an adjacency list, return `true` if it is bipartite — vertices can be split into two sets with every edge crossing between sets.
**Constraints.** `1 ≤ n ≤ 100`; may be disconnected; no self-loops.

**Approach.** 2-coloring via BFS/DFS. Color a start node, alternate colors across edges; if an edge ever connects two same-colored nodes, it is not bipartite. Equivalent statement: a graph is bipartite iff it has **no odd-length cycle**. Loop over all components.

```java
import java.util.*;

public class Bipartite {
    public boolean isBipartite(int[][] graph) {
        int n = graph.length;
        int[] color = new int[n];        // 0 = uncolored, 1 / -1 = two colors
        for (int i = 0; i < n; i++) {
            if (color[i] != 0) continue;
            Queue<Integer> q = new ArrayDeque<>();
            q.offer(i);
            color[i] = 1;
            while (!q.isEmpty()) {
                int u = q.poll();
                for (int v : graph[u]) {
                    if (color[v] == 0) {
                        color[v] = -color[u];
                        q.offer(v);
                    } else if (color[v] == color[u]) {
                        return false;     // same color on both ends
                    }
                }
            }
        }
        return true;
    }
}
```

**Dry run.** `graph=[[1,3],[0,2],[1,3],[0,2]]` (a 4-cycle 0-1-2-3-0). Color 0=1, neighbors 1,3=-1; from 1 color 2=1; from 3, neighbor 2 already 1 = -color(3=-1)=1 consistent → `true` (even cycle). A triangle would fail (odd cycle).

**Time:** `O(V + E)`. **Space:** `O(V)`.
**Follow-ups.** "Possible Bipartition" (build the dislike graph then 2-color); odd-cycle intuition; use Union-Find with a "buddy/enemy" trick.

---

### Problem 10: Word Ladder — Shortest Transformation (LeetCode 127)

**Statement.** Given `beginWord`, `endWord`, and a dictionary `wordList`, return the number of words in the shortest transformation sequence (each step changes exactly one letter and must be in the list), or `0` if none exists.
**Constraints.** Word length up to 10; `wordList.length ≤ 5000`; all lowercase.

**Approach.** Model each word as a node; edges connect words differing by one letter. Shortest path on an unweighted graph → **BFS**. Building edges by comparing all pairs is `O(N²·L)`; instead, for each word generate its `L·26` one-letter mutations and check membership in a `HashSet` — `O(N·L·26)`. Bidirectional BFS roughly halves the explored frontier.

```java
import java.util.*;

public class WordLadder {
    public int ladderLength(String beginWord, String endWord, List<String> wordList) {
        Set<String> dict = new HashSet<>(wordList);
        if (!dict.contains(endWord)) return 0;
        Queue<String> q = new ArrayDeque<>();
        q.offer(beginWord);
        Set<String> visited = new HashSet<>();
        visited.add(beginWord);
        int level = 1;                              // beginWord counts as 1
        while (!q.isEmpty()) {
            int size = q.size();
            for (int s = 0; s < size; s++) {
                String word = q.poll();
                if (word.equals(endWord)) return level;
                char[] arr = word.toCharArray();
                for (int i = 0; i < arr.length; i++) {
                    char original = arr[i];
                    for (char c = 'a'; c <= 'z'; c++) {
                        if (c == original) continue;
                        arr[i] = c;
                        String next = new String(arr);
                        if (dict.contains(next) && !visited.contains(next)) {
                            visited.add(next);
                            q.offer(next);
                        }
                    }
                    arr[i] = original;              // restore
                }
            }
            level++;
        }
        return 0;
    }
}
```

**Dry run.** begin `hit`, end `cog`, dict `[hot,dot,dog,lot,log,cog]`. Level1 `hit` → `hot`. Level2 `hot` → `dot,lot`. Level3 → `dog,log`. Level4 → `cog` reached → length **5** (`hit→hot→dot→dog→cog`).

**Time:** `O(N·L·26)` to explore (each word visited once). **Space:** `O(N·L)`.
**Follow-ups.** "Word Ladder II" — return *all* shortest sequences (BFS to build a parent DAG, then DFS to reconstruct); bidirectional BFS optimization; use wildcard buckets (`h*t`) as intermediate nodes for very large dictionaries.

---

### Problem 11: Rotting Oranges — Multi-Source BFS (LeetCode 994)

**Statement.** Grid cells are `0` (empty), `1` (fresh orange), `2` (rotten). Each minute, every fresh orange 4-adjacent to a rotten one rots. Return the minutes until no fresh orange remains, or `-1` if impossible.
**Constraints.** `1 ≤ m, n ≤ 10`.

**Approach.** Many rotten oranges spread simultaneously → **multi-source BFS**: seed the queue with *all* initially rotten cells, BFS layer by layer (each layer = one minute), count fresh oranges, and verify all rot.

```java
import java.util.*;

public class RottingOranges {
    public int orangesRotting(int[][] grid) {
        int m = grid.length, n = grid[0].length, fresh = 0;
        Queue<int[]> q = new ArrayDeque<>();
        for (int r = 0; r < m; r++)
            for (int c = 0; c < n; c++) {
                if (grid[r][c] == 2) q.offer(new int[]{r, c});
                else if (grid[r][c] == 1) fresh++;
            }
        if (fresh == 0) return 0;
        int minutes = 0;
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        while (!q.isEmpty() && fresh > 0) {
            int size = q.size();
            for (int s = 0; s < size; s++) {
                int[] cell = q.poll();
                for (int[] d : dirs) {
                    int nr = cell[0] + d[0], nc = cell[1] + d[1];
                    if (nr >= 0 && nr < m && nc >= 0 && nc < n && grid[nr][nc] == 1) {
                        grid[nr][nc] = 2;
                        fresh--;
                        q.offer(new int[]{nr, nc});
                    }
                }
            }
            minutes++;
        }
        return fresh == 0 ? minutes : -1;
    }
}
```

**Dry run.** `[[2,1,1],[1,1,0],[0,1,1]]`. Min1: rots (0,1) and (1,0). Min2: (0,2),(1,1). Min3: (2,1). Min4: (2,2). All rotten → **4**.

**Time:** `O(m·n)`. **Space:** `O(m·n)`.
**Follow-ups.** "Walls and Gates" / "01 Matrix" (multi-source BFS for nearest distance); why a single-source BFS per orange is wrong here; track which orange survives if some are unreachable.

---

### Problem 12 (Hard / Senior): Critical Connections in a Network — Bridges (LeetCode 1192)

**Statement.** `n` servers `0..n-1` connected by undirected `connections`. A *critical connection* (bridge) is an edge whose removal disconnects some servers. Return all bridges.
**Constraints.** `1 ≤ n ≤ 10^5`, `connections.length ≤ 10^5`; the graph is connected.

**Approach.** Tarjan's bridge-finding via DFS. Assign each node a `disc[u]` (discovery time) and `low[u]` (lowest discovery time reachable from `u`'s subtree using at most one back edge). For a tree edge `(u, v)`, if `low[v] > disc[u]`, then `v`'s subtree has no back edge to `u` or above — the edge `(u, v)` is a **bridge**. Skip the immediate parent edge (but handle parallel edges if present).

> An **articulation point** uses the same machinery: a non-root vertex `u` is an articulation point if it has a child `v` with `low[v] >= disc[u]`; the DFS root is one iff it has ≥ 2 DFS children. Bridges use strict `>`; articulation points use `>=`.

```java
import java.util.*;

public class CriticalConnections {
    private int timer = 0;
    private int[] disc, low;
    private List<List<Integer>> adj;
    private List<List<Integer>> bridges;

    public List<List<Integer>> criticalConnections(int n, List<List<Integer>> connections) {
        adj = new ArrayList<>();
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
        for (List<Integer> e : connections) {
            adj.get(e.get(0)).add(e.get(1));
            adj.get(e.get(1)).add(e.get(0));
        }
        disc = new int[n];
        low = new int[n];
        Arrays.fill(disc, -1);          // -1 = unvisited
        bridges = new ArrayList<>();
        for (int i = 0; i < n; i++)
            if (disc[i] == -1) dfs(i, -1);
        return bridges;
    }

    private void dfs(int u, int parent) {
        disc[u] = low[u] = timer++;
        for (int v : adj.get(u)) {
            if (v == parent) continue;            // don't go back over the edge we came from
            if (disc[v] == -1) {                  // tree edge
                dfs(v, u);
                low[u] = Math.min(low[u], low[v]);
                if (low[v] > disc[u])             // no back edge bypasses (u,v)
                    bridges.add(Arrays.asList(u, v));
            } else {                              // back edge
                low[u] = Math.min(low[u], disc[v]);
            }
        }
    }
}
```

**Dry run.** `n=4`, edges `0-1, 1-2, 2-0, 1-3`. DFS 0(disc0)→1(disc1)→2(disc2); 2 sees back edge to 0 → low[2]=0; back at 1, low[1]=min(1,0)=0, edge (1,2): low[2]=0 ≤ disc[1]=1, not a bridge; edge (0,1): low[1]=0 ≤ disc[0]=0, not a bridge. Then 1→3(disc3), 3 has no other edge, low[3]=3; edge (1,3): low[3]=3 > disc[1]=1 → **bridge (1,3)**. Output `[[1,3]]`. Correct: removing 1-3 isolates server 3, while the triangle 0-1-2 stays connected without any single bridge.

**Time:** `O(V + E)`. **Space:** `O(V + E)`.
**Follow-ups.** Find articulation points (cut vertices) with the `>=` rule and special root handling; build the 2-edge-connected components / bridge tree; handle parallel edges by tracking edge IDs rather than the parent vertex; relate to network reliability and SPOF analysis.

---

## 🧩 Extended Problems — Set 1: Classic Easy → Medium

### Problem 13: Find if Path Exists in Graph (LeetCode 1971) — BFS/DFS reachability

**Statement.** Given `n` nodes `0..n-1`, an undirected `edges` list, and a `source` and `destination`, return `true` if a path exists from `source` to `destination`.
**Constraints.** `1 ≤ n ≤ 2·10^5`, `0 ≤ edges.length ≤ 2·10^5`; no self-loops/duplicate edges.

**Approach.** Pure reachability — build an adjacency list and BFS/DFS from `source`, returning `true` the moment you reach `destination`. BFS with a queue is iterative and avoids stack overflow on the large `n` allowed here. The early `source == destination` check covers the trivial single-node case. This is optimal: any algorithm must in the worst case inspect every edge once.

```java
import java.util.*;

public class PathExists {
    public boolean validPath(int n, int[][] edges, int source, int destination) {
        if (source == destination) return true;
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
        for (int[] e : edges) {
            adj.get(e[0]).add(e[1]);
            adj.get(e[1]).add(e[0]);
        }
        boolean[] visited = new boolean[n];
        Queue<Integer> q = new ArrayDeque<>();
        q.offer(source);
        visited[source] = true;
        while (!q.isEmpty()) {
            int u = q.poll();
            for (int v : adj.get(u)) {
                if (v == destination) return true;
                if (!visited[v]) {
                    visited[v] = true;
                    q.offer(v);
                }
            }
        }
        return false;
    }
}
```

**Dry run.** `n=3`, edges `[[0,1],[1,2],[2,0]]`, source `0`, dest `2`. BFS from 0 visits 1, then sees 2 as a neighbor of 1 → returns `true`.

**Time:** `O(V + E)`. **Space:** `O(V + E)`. **Edge cases:** `source == destination` (return true immediately); fully disconnected graph (no edges) where source ≠ destination → false; large `n` favors BFS or Union-Find over recursive DFS.

---

### Problem 14: Max Area of Island (LeetCode 695) — Grid DFS with size accumulation

**Statement.** Given an `m×n` binary grid, return the area (number of `1` cells) of the largest island (4-directionally connected group of `1`s). Return `0` if there is no island.
**Constraints.** `1 ≤ m, n ≤ 50`.

**Approach.** Same scan-and-sink pattern as Number of Islands, but instead of counting islands, each DFS returns the *size* of the island it sank. Track the running maximum. Sinking (`grid[r][c] = 0`) doubles as the visited marker, so no extra array is needed.

```java
public class MaxAreaOfIsland {
    public int maxAreaOfIsland(int[][] grid) {
        int best = 0;
        for (int r = 0; r < grid.length; r++)
            for (int c = 0; c < grid[0].length; c++)
                if (grid[r][c] == 1)
                    best = Math.max(best, area(grid, r, c));
        return best;
    }
    private int area(int[][] g, int r, int c) {
        if (r < 0 || r >= g.length || c < 0 || c >= g[0].length || g[r][c] != 1) return 0;
        g[r][c] = 0;                       // sink to mark visited
        return 1 + area(g, r + 1, c) + area(g, r - 1, c)
                 + area(g, r, c + 1) + area(g, r, c - 1);
    }
}
```

**Dry run.** Grid `[[1,1,0],[0,1,0],[0,0,1]]`. DFS at (0,0) sinks the connected block (0,0),(0,1),(1,1) → area 3. Lone `1` at (2,2) → area 1. Max = **3**.

**Time:** `O(m·n)`. **Space:** `O(m·n)` recursion in the worst case (one giant island). **Edge cases:** all water → 0; entire grid is one island → `m·n`; preserve input by using a separate `visited[][]` if mutation is disallowed.

---

### Problem 15: Number of Connected Components in an Undirected Graph (LeetCode 323) — Union-Find

**Statement.** Given `n` nodes `0..n-1` and an undirected `edges` list, return the number of connected components.
**Constraints.** `1 ≤ n ≤ 2000`, `0 ≤ edges.length ≤ n·(n-1)/2`; no self-loops/duplicate edges.

**Approach.** Start with `n` components (each node its own). Each edge that unites two *different* sets reduces the component count by one. Union-Find with path compression and union by rank runs in near-constant amortized time per operation. (A DFS over an adjacency list counting starts is an equally valid `O(V+E)` alternative.)

```java
public class CountComponents {
    private int[] parent, rank_;

    public int countComponents(int n, int[][] edges) {
        parent = new int[n];
        rank_ = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        int components = n;
        for (int[] e : edges)
            if (union(e[0], e[1])) components--;
        return components;
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

**Dry run.** `n=5`, edges `[[0,1],[1,2],[3,4]]`. union(0,1): 5→4. union(1,2): 4→3. union(3,4): 3→2. Components = **2** ({0,1,2}, {3,4}).

**Time:** `O(n + E·α(n))`. **Space:** `O(n)`. **Edge cases:** no edges → `n` components; a self-loop edge `[i,i]` would be a no-op union (ignored); all nodes connected → 1.

---

### Problem 16: Keys and Rooms (LeetCode 841) — Directed reachability via DFS

**Statement.** `n` rooms labeled `0..n-1`; room `i` contains a list of keys `rooms[i]` to other rooms. Starting in room `0` (unlocked), return `true` if you can visit every room.
**Constraints.** `1 ≤ n ≤ 1000`, total keys `≤ 3000`; keys are valid room labels.

**Approach.** This is reachability in a directed graph where `rooms` *is* the adjacency list. DFS/BFS from room 0, marking visited; at the end check whether the visited count equals `n`. Marking on visit prevents revisiting rooms (and infinite loops if keys form cycles).

```java
import java.util.*;

public class KeysAndRooms {
    public boolean canVisitAllRooms(List<List<Integer>> rooms) {
        int n = rooms.size();
        boolean[] visited = new boolean[n];
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(0);
        visited[0] = true;
        int count = 1;
        while (!stack.isEmpty()) {
            int room = stack.pop();
            for (int key : rooms.get(room)) {
                if (!visited[key]) {
                    visited[key] = true;
                    count++;
                    stack.push(key);
                }
            }
        }
        return count == n;
    }
}
```

**Dry run.** `rooms=[[1],[2],[3],[]]`. Visit 0→1→2→3, count reaches 4 = n → `true`. For `[[1,3],[3,0,1],[2],[0]]`, room 2 is never reachable → count 3 ≠ 4 → `false`.

**Time:** `O(V + E)` where `E` is total keys. **Space:** `O(V)`. **Edge cases:** single room `[[]]` → trivially true; key referencing room 0 (already visited) is skipped; unreachable room → false.

---

### Problem 17: Find the Town Judge (LeetCode 997) — Degree counting on a directed graph

**Statement.** In a town of `n` people `1..n`, the judge trusts nobody and is trusted by everyone else. Given `trust` pairs `[a, b]` (a trusts b), return the judge's label, or `-1`.
**Constraints.** `1 ≤ n ≤ 1000`, `0 ≤ trust.length ≤ 10^4`; pairs are distinct.

**Approach.** A degree-counting trick rather than a traversal. The judge has in-degree `n-1` (trusted by everyone else) and out-degree `0` (trusts nobody). Track `net[p] = indegree - outdegree`; the judge is the unique person with `net == n-1`. Counting both degrees in one pass avoids two separate arrays.

```java
public class TownJudge {
    public int findJudge(int n, int[][] trust) {
        int[] net = new int[n + 1];        // indegree - outdegree
        for (int[] t : trust) {
            net[t[0]]--;                   // a trusts someone -> out
            net[t[1]]++;                   // b is trusted     -> in
        }
        for (int p = 1; p <= n; p++)
            if (net[p] == n - 1) return p;
        return -1;
    }
}
```

**Dry run.** `n=3`, trust `[[1,3],[2,3]]`. net[1]=-1, net[2]=-1, net[3]=+2. n-1=2 → person 3 has net 2 → judge is **3**.

**Time:** `O(n + E)`. **Space:** `O(n)`. **Edge cases:** `n=1` with no trust → person 1 is the judge (net 0 == n-1=0); two candidates can never both reach `n-1`; a person who trusts someone can never be the judge (net < n-1).

---

### Problem 18: Find Center of Star Graph (LeetCode 1791) — Degree / first-two-edges trick

**Statement.** A star graph has one center connected to every other node and no other edges. Given the `edges` of such a graph on `n` nodes, return the center.
**Constraints.** `3 ≤ n ≤ 10^5`; the input is guaranteed to be a valid star graph.

**Approach.** Because it is guaranteed a star, the center is the common endpoint of any two edges. Inspect just the first two edges: the node appearing in both is the center — `O(1)`. (A general fallback: the center is the node with degree `n-1`, found by degree counting in `O(E)`.)

```java
public class StarGraphCenter {
    public int findCenter(int[][] edges) {
        int a = edges[0][0], b = edges[0][1];
        // the center is whichever of a,b also appears in the second edge
        return (a == edges[1][0] || a == edges[1][1]) ? a : b;
    }
}
```

**Dry run.** edges `[[1,2],[2,3],[4,2]]`. First edge {1,2}, second {2,3}; `2` appears in both → center is **2**.

**Time:** `O(1)`. **Space:** `O(1)`. **Edge cases:** the guarantee of a valid star is what makes the two-edge shortcut sound — without it you must count degrees; the minimum `n=3` still has ≥ 2 edges so `edges[1]` is always present.

---

### Problem 19: Shortest Path in Binary Matrix (LeetCode 1091) — 8-directional BFS

**Statement.** In an `n×n` binary grid, a clear path from top-left `(0,0)` to bottom-right `(n-1,n-1)` moves through `0` cells 8-directionally. Return the length (number of cells visited) of the shortest clear path, or `-1`.
**Constraints.** `1 ≤ n ≤ 100`; cells are `0` or `1`.

**Approach.** Unweighted shortest path on a grid with 8 neighbors → BFS. Seed the queue with the start (if it is `0`), marking cells visited as you enqueue. Track distance per layer; the first time you pop the target, its distance is optimal. Mutating the grid to `1` on visit avoids a separate visited array.

```
8 directions from a cell X:
  ↖ ↑ ↗      (-1,-1)(-1,0)(-1,+1)
  ← X →      ( 0,-1)       ( 0,+1)
  ↙ ↓ ↘      (+1,-1)(+1,0)(+1,+1)
```

```java
import java.util.*;

public class ShortestPathBinaryMatrix {
    public int shortestPathBinaryMatrix(int[][] grid) {
        int n = grid.length;
        if (grid[0][0] != 0 || grid[n - 1][n - 1] != 0) return -1;
        int[][] dirs = {{-1,-1},{-1,0},{-1,1},{0,-1},{0,1},{1,-1},{1,0},{1,1}};
        Queue<int[]> q = new ArrayDeque<>();
        q.offer(new int[]{0, 0});
        grid[0][0] = 1;                    // mark visited
        int dist = 1;
        while (!q.isEmpty()) {
            int size = q.size();
            for (int s = 0; s < size; s++) {
                int[] cell = q.poll();
                if (cell[0] == n - 1 && cell[1] == n - 1) return dist;
                for (int[] d : dirs) {
                    int nr = cell[0] + d[0], nc = cell[1] + d[1];
                    if (nr >= 0 && nr < n && nc >= 0 && nc < n && grid[nr][nc] == 0) {
                        grid[nr][nc] = 1;
                        q.offer(new int[]{nr, nc});
                    }
                }
            }
            dist++;
        }
        return -1;
    }
}
```

**Dry run.** `[[0,1],[1,0]]`. Start (0,0) clear; diagonally reaches (1,1) in one step → path length **2**.

**Time:** `O(n²)` (each cell enqueued once). **Space:** `O(n²)`. **Edge cases:** start or end blocked → -1; `n=1` with `grid[0][0]==0` → length 1; the diagonal moves are what distinguish this from 4-directional grid BFS.

---

### Problem 20: 01 Matrix — Nearest 0 via Multi-Source BFS (LeetCode 542)

**Statement.** Given an `m×n` binary matrix, return a matrix where each cell holds its distance to the nearest `0` (4-directional steps).
**Constraints.** `1 ≤ m, n ≤ 10^4`, `m·n ≤ 10^4`; at least one `0` is present.

**Approach.** Running a BFS from each `1` is `O((mn)²)`. Instead, invert it: seed a queue with **all** `0` cells at distance 0 (multi-source BFS) and let the wavefront expand outward. The first time a `1` cell is reached gives its nearest-zero distance. Use `-1` (or `Integer.MAX_VALUE`) as the "unvisited" sentinel for `1` cells.

```java
import java.util.*;

public class ZeroOneMatrix {
    public int[][] updateMatrix(int[][] mat) {
        int m = mat.length, n = mat[0].length;
        int[][] dist = new int[m][n];
        Queue<int[]> q = new ArrayDeque<>();
        for (int r = 0; r < m; r++)
            for (int c = 0; c < n; c++) {
                if (mat[r][c] == 0) q.offer(new int[]{r, c});
                else dist[r][c] = -1;       // mark unvisited
            }
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        while (!q.isEmpty()) {
            int[] cell = q.poll();
            for (int[] d : dirs) {
                int nr = cell[0] + d[0], nc = cell[1] + d[1];
                if (nr >= 0 && nr < m && nc >= 0 && nc < n && dist[nr][nc] == -1) {
                    dist[nr][nc] = dist[cell[0]][cell[1]] + 1;
                    q.offer(new int[]{nr, nc});
                }
            }
        }
        return dist;
    }
}
```

**Dry run.** `[[0,0,0],[0,1,0],[1,1,1]]`. The center `1` is adjacent to a `0` → 1. Bottom-row `1`s: (2,0) and (2,2) are adjacent to `0`s above → 1; (2,1) reaches a `0` in 2 steps → 2. Result `[[0,0,0],[0,1,0],[1,2,1]]`.

**Time:** `O(m·n)`. **Space:** `O(m·n)`. **Edge cases:** all zeros → all distances 0; single `0` with surrounding `1`s grows distances outward; multi-source seeding is essential — per-cell BFS would TLE.

---

### Problem 21: Surrounded Regions — Border DFS (LeetCode 130)

**Statement.** Given an `m×n` board of `'X'` and `'O'`, capture all regions of `'O'` that are *fully* surrounded by `'X'` by flipping them to `'X'`. An `'O'` connected (4-directionally) to a border is never captured.
**Constraints.** `1 ≤ m, n ≤ 200`.

**Approach.** Invert the logic: instead of finding surrounded regions, find the *safe* ones. DFS/BFS from every `'O'` on the border, marking all reachable `'O'`s with a temporary sentinel `'#'`. After the sweep, every remaining `'O'` is surrounded → flip to `'X'`; every `'#'` was border-connected → restore to `'O'`.

```java
public class SurroundedRegions {
    public void solve(char[][] board) {
        int m = board.length, n = board[0].length;
        for (int r = 0; r < m; r++) {
            mark(board, r, 0);
            mark(board, r, n - 1);
        }
        for (int c = 0; c < n; c++) {
            mark(board, 0, c);
            mark(board, m - 1, c);
        }
        for (int r = 0; r < m; r++)
            for (int c = 0; c < n; c++) {
                if (board[r][c] == 'O') board[r][c] = 'X';   // surrounded
                else if (board[r][c] == '#') board[r][c] = 'O'; // safe
            }
    }
    private void mark(char[][] b, int r, int c) {
        if (r < 0 || r >= b.length || c < 0 || c >= b[0].length || b[r][c] != 'O') return;
        b[r][c] = '#';
        mark(b, r + 1, c);
        mark(b, r - 1, c);
        mark(b, r, c + 1);
        mark(b, r, c - 1);
    }
}
```

**Dry run.** `[[X,X,X],[X,O,X],[X,X,X]]`. No border `'O'`, so nothing marked safe; the central `'O'` is surrounded → flipped to `'X'`. If an `'O'` touched the border, it (and its connected group) would survive.

**Time:** `O(m·n)`. **Space:** `O(m·n)` recursion worst case. **Edge cases:** all border `'O'`s survive; a 1×n or m×1 board where every cell is on the border (nothing captured); no `'O'`s at all is a no-op.

---

### Problem 22: Pacific Atlantic Water Flow — Reverse Multi-Source DFS (LeetCode 417)

**Statement.** Given an `m×n` matrix of heights, water flows from a cell to 4-adjacent cells of `height ≤` current. The Pacific borders the top and left edges; the Atlantic the bottom and right. Return all cells from which water can reach **both** oceans.
**Constraints.** `1 ≤ m, n ≤ 200`.

**Approach.** Forward simulation per cell is `O((mn)²)`. Reverse it: from each ocean's border cells, DFS *uphill* (to neighbors with `height ≥` current) to mark every cell that can drain into that ocean. Run two traversals (one per ocean) producing two boolean grids; the answer is their intersection.

```java
import java.util.*;

public class PacificAtlantic {
    private int m, n;
    private int[][] h;
    private final int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};

    public List<List<Integer>> pacificAtlantic(int[][] heights) {
        h = heights; m = h.length; n = h[0].length;
        boolean[][] pac = new boolean[m][n], atl = new boolean[m][n];
        for (int r = 0; r < m; r++) {
            dfs(r, 0, pac);          // left edge -> Pacific
            dfs(r, n - 1, atl);      // right edge -> Atlantic
        }
        for (int c = 0; c < n; c++) {
            dfs(0, c, pac);          // top edge -> Pacific
            dfs(m - 1, c, atl);      // bottom edge -> Atlantic
        }
        List<List<Integer>> res = new ArrayList<>();
        for (int r = 0; r < m; r++)
            for (int c = 0; c < n; c++)
                if (pac[r][c] && atl[r][c]) res.add(Arrays.asList(r, c));
        return res;
    }
    private void dfs(int r, int c, boolean[][] ocean) {
        ocean[r][c] = true;
        for (int[] d : dirs) {
            int nr = r + d[0], nc = c + d[1];
            if (nr >= 0 && nr < m && nc >= 0 && nc < n
                && !ocean[nr][nc] && h[nr][nc] >= h[r][c]) // uphill (reverse flow)
                dfs(nr, nc, ocean);
        }
    }
}
```

**Dry run.** A peak cell on a corner reaches both oceans trivially. The border cells are seeded directly (a top-left cell is both Pacific-reachable and, if tall enough to chain to a right/bottom edge, Atlantic-reachable). Intersection yields the divide cells.

**Time:** `O(m·n)` (each cell visited at most once per ocean). **Space:** `O(m·n)`. **Edge cases:** single cell touches all borders → in the answer; flat plateaus flow both ways (the `>=` comparison handles equal heights); large grids are fine since work is linear.

---

### Problem 23: Minimum Number of Vertices to Reach All Nodes (LeetCode 1557) — In-degree on a DAG

**Statement.** Given a directed acyclic graph with `n` nodes `0..n-1` and an `edges` list, return the smallest set of vertices from which all nodes are reachable.
**Constraints.** `2 ≤ n ≤ 10^5`, `1 ≤ edges.length ≤ min(n·(n-1)/2, 10^5)`; the graph is a DAG.

**Approach.** A node with in-degree `> 0` is reachable from some other node, so it never needs to be a starting vertex. A node with in-degree `0` cannot be reached by anyone, so it *must* be in the answer. Therefore the unique minimal set is exactly the set of in-degree-0 vertices — computed by one pass over the edges. No traversal needed.

```java
import java.util.*;

public class MinVerticesToReachAll {
    public List<Integer> findSmallestSetOfVertices(int n, List<List<Integer>> edges) {
        boolean[] hasIncoming = new boolean[n];
        for (List<Integer> e : edges) hasIncoming[e.get(1)] = true;
        List<Integer> res = new ArrayList<>();
        for (int i = 0; i < n; i++)
            if (!hasIncoming[i]) res.add(i);
        return res;
    }
}
```

**Dry run.** `n=6`, edges `[[0,1],[0,2],[2,5],[3,4],[4,2]]`. Nodes with incoming: 1,2,5,4. In-degree 0: 0 and 3 → answer `[0, 3]`.

**Time:** `O(n + E)`. **Space:** `O(n)`. **Edge cases:** isolated node (no edges touching it) has in-degree 0 → must be included; a single source pointing to all others → answer is just that source; DAG guarantee means no cycle can "cover" an in-degree-0 node.

---

### Problem 24: Maximum Depth of N-ary Tree (LeetCode 559) — DFS/BFS on a tree (special graph)

**Statement.** Given the root of an N-ary tree (each node has a list of children), return its maximum depth — the number of nodes along the longest root-to-leaf path.
**Constraints.** Number of nodes `≤ 10^4`; depth `≤ 1000`.

**Approach.** A tree is an acyclic connected graph, so traversal applies directly. Recursive DFS: the depth of a node is `1 + max(depth of children)`, with an empty/null node contributing `0`. (A level-order BFS counting layers is the iterative equivalent and avoids deep recursion.)

```java
import java.util.*;

class Node {
    public int val;
    public List<Node> children;
    public Node() { children = new ArrayList<>(); }
    public Node(int v) { val = v; children = new ArrayList<>(); }
}

public class NaryTreeMaxDepth {
    public int maxDepth(Node root) {
        if (root == null) return 0;
        int best = 0;
        for (Node child : root.children)
            best = Math.max(best, maxDepth(child));
        return best + 1;                   // count this node
    }
}
```

**Dry run.** Root with children A, B; A has children C, D. Depth(C)=Depth(D)=1, Depth(A)=2, Depth(B)=1, Depth(root)=1+max(2,1)=**3**.

**Time:** `O(V)` (each node visited once). **Space:** `O(H)` recursion where `H` is the height (up to `O(V)` for a degenerate chain). **Edge cases:** null root → 0; a single node → 1; a wide-but-shallow tree → 2; deep chains favor an iterative BFS to avoid stack overflow.

---

### Problem 25: Reorder Routes to Make All Paths Lead to the City Zero (LeetCode 1466) — DFS on a tree with edge direction

**Statement.** `n` cities `0..n-1` form a tree via `n-1` directed `connections` `[a, b]` (a road from `a` to `b`). Return the minimum number of edges that must be reversed so every city can reach city `0`.
**Constraints.** `2 ≤ n ≤ 5·10^4`; the underlying undirected graph is a tree.

**Approach.** Build an *undirected* adjacency list but tag each edge with its original direction (e.g. store the neighbor plus a flag: `1` if the edge points *away* from the current node in the original graph). DFS/BFS outward from city `0`. Walking from `0` toward a child along an edge that originally pointed *outward* means that road leads away from `0` and must be reversed — count it. Edges pointing back toward `0` are already correct. Since it is a tree, each edge is examined exactly once.

```
Original (arrows = direction):           Goal: everyone reaches 0
   0 → 1 → 3                              edges away from 0 (when walking
   ↑                                      out from 0) must be reversed.
   2

Walking out from 0:
  0→1 points outward  -> reverse (cost 1)
  1→3 points outward  -> reverse (cost 1)
  2→0 points inward   -> already ok (cost 0)
  total reversals = 2
```

```java
import java.util.*;

public class ReorderRoutes {
    public int minReorder(int n, int[][] connections) {
        // adj.get(u) holds {neighbor, isOriginalDirection}
        List<List<int[]>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
        for (int[] c : connections) {
            adj.get(c[0]).add(new int[]{c[1], 1});   // real edge u->v : going u->v costs 1
            adj.get(c[1]).add(new int[]{c[0], 0});   // phantom reverse : going v->u costs 0
        }
        boolean[] visited = new boolean[n];
        return dfs(adj, 0, visited);
    }
    private int dfs(List<List<int[]>> adj, int u, boolean[] visited) {
        visited[u] = true;
        int count = 0;
        for (int[] edge : adj.get(u)) {
            int v = edge[0], cost = edge[1];
            if (!visited[v]) count += cost + dfs(adj, v, visited);
        }
        return count;
    }
}
```

**Dry run.** `n=6`, connections `[[0,1],[1,3],[2,3],[4,0],[4,5]]`. DFS from 0: edge 0→1 is original (cost 1); 1→3 original (cost 1); 3→2 is a phantom-reverse of 2→3 (cost 0); 0←4 phantom-reverse of 4→0 (cost 0); 4→5 original (cost 1). Total reversals = **3**.

**Time:** `O(n)` (tree has `n-1` edges, each visited once). **Space:** `O(n)` for the adjacency list and recursion. **Edge cases:** a star already pointing into 0 → 0 reversals; a chain `0→1→2→…` → `n-1` reversals; the tree guarantee means no cycle handling is needed beyond the `visited` check.

---

### Bonus: Graph Coloring (greedy m-coloring)

**Concept.** Assign colors to vertices so no two adjacent vertices share a color, using ≤ `m` colors. Deciding whether `m` colors suffice (the **chromatic number ≤ m** problem) is **NP-complete** for `m ≥ 3`; a backtracking search is the standard exact method. Bipartite testing is exactly the 2-coloring special case (solvable in `O(V+E)`). A greedy coloring (color each vertex with the smallest color not used by its neighbors) uses at most `Δ + 1` colors where `Δ` is the max degree — useful for register allocation and exam/interval scheduling.

```java
import java.util.*;

public class GraphColoring {
    // returns true if the graph can be colored with m colors (backtracking)
    public boolean canColor(boolean[][] adj, int m) {
        int n = adj.length;
        int[] color = new int[n];   // 0 means uncolored; colors 1..m
        return solve(adj, m, color, 0);
    }
    private boolean solve(boolean[][] adj, int m, int[] color, int v) {
        if (v == adj.length) return true;
        for (int c = 1; c <= m; c++) {
            if (isSafe(adj, color, v, c)) {
                color[v] = c;
                if (solve(adj, m, color, v + 1)) return true;
                color[v] = 0;        // backtrack
            }
        }
        return false;
    }
    private boolean isSafe(boolean[][] adj, int[] color, int v, int c) {
        for (int i = 0; i < adj.length; i++)
            if (adj[v][i] && color[i] == c) return false;
        return true;
    }
}
```

**Time:** `O(m^V)` worst case (NP-complete). **Space:** `O(V)`.
**Follow-ups.** Why greedy ordering matters (Welsh–Powell sorts by degree); register allocation in compilers; map coloring (planar graphs need ≤ 4 colors).

---

## 🧩 Extended Problems — Set 2: Medium → Hard Variations & Follow-ups

### Problem 26: Course Schedule IV — Reachability / Transitive Closure (LeetCode 1462)

**Statement.** `numCourses` courses and prerequisite pairs `[a, b]` (take `a` before `b`). Given a list of `queries [u, v]`, return for each whether `u` is a prerequisite of `v` (directly or transitively).
**Constraints.** `2 ≤ numCourses ≤ 100`, prerequisites form a DAG, `1 ≤ queries.length ≤ 10^4`.

**Approach.** Brute force per query is a BFS/DFS from `u` checking if `v` is reachable — `O(Q·(V+E))`. With many queries the optimal move is to precompute the **transitive closure** once. Two clean ways: (1) **Floyd–Warshall style** boolean closure `reach[i][j]` in `O(V³)`; (2) **DFS/BFS from every node** caching its reachable set in `O(V·(V+E))`. With `V ≤ 100` the `O(V³)` Floyd closure is simplest and answers each query in `O(1)`. The progression matters: precomputation pays off precisely because queries vastly outnumber vertices here.

```
reach[i][j] = i can reach j.  Floyd update:
  reach[i][j] |= reach[i][k] && reach[k][j]   for every intermediate k
```

```java
import java.util.*;

public class CourseScheduleIV {
    public List<Boolean> checkIfPrerequisite(int numCourses, int[][] prerequisites, int[][] queries) {
        boolean[][] reach = new boolean[numCourses][numCourses];
        for (int[] p : prerequisites) reach[p[0]][p[1]] = true;   // direct edge a -> b
        // transitive closure
        for (int k = 0; k < numCourses; k++)
            for (int i = 0; i < numCourses; i++)
                if (reach[i][k])                                  // small prune
                    for (int j = 0; j < numCourses; j++)
                        if (reach[k][j]) reach[i][j] = true;
        List<Boolean> ans = new ArrayList<>(queries.length);
        for (int[] q : queries) ans.add(reach[q[0]][q[1]]);
        return ans;
    }
}
```

**Dry run.** prereqs `[[1,2],[1,0],[2,0]]`, query `[1,0]`. Direct `1→2`,`1→0`,`2→0`. Closure adds nothing new for `[1,0]` (already direct) → `true`. Query `[0,1]` → `false` (no path).

**Complexity.** Time `O(V³ + Q)`; space `O(V²)`. **Edge cases:** self-query `[u,u]` → `false` (a course is not its own prerequisite here); disconnected nodes → all `false`; the DAG guarantee means no infinite reachability loops.

---

### Problem 27: Cheapest Flights Within K Stops — Bellman-Ford / BFS by Layers (LeetCode 787)

**Statement.** `n` cities and weighted directed `flights [from, to, price]`. Find the cheapest price from `src` to `dst` using **at most `k` stops** (i.e. at most `k+1` edges), or `-1`.
**Constraints.** `1 ≤ n ≤ 100`, `0 ≤ flights.length ≤ n·(n-1)`, `0 ≤ k < n`.

**Approach.** Plain Dijkstra is wrong because the cheapest route may use *more* edges than allowed; the hop limit is the real constraint. The clean fit is **Bellman-Ford bounded to `k+1` relaxation rounds**: after round `r`, `dist[v]` is the cheapest cost to reach `v` using ≤ `r` edges. Crucially, each round must relax against a **snapshot** of the previous round's distances (clone the array) so a single round cannot chain multiple edges. This is equivalently a level-by-level BFS over (city, edgesUsed) states. `O(k·E)` time.

```
Round r relaxes one more edge layer:
  next[v] = min(next[v], prev[u] + w)  for every edge (u,v,w)
  prev is frozen during the round -> exactly r edges allowed after r rounds
```

```java
import java.util.*;

public class CheapestFlightsKStops {
    public int findCheapestPrice(int n, int[][] flights, int src, int dst, int k) {
        int[] dist = new int[n];
        Arrays.fill(dist, Integer.MAX_VALUE);
        dist[src] = 0;
        for (int r = 0; r <= k; r++) {                 // k stops => k+1 edges
            int[] prev = dist.clone();                 // snapshot: only one new edge per round
            for (int[] f : flights) {
                int u = f[0], v = f[1], w = f[2];
                if (prev[u] != Integer.MAX_VALUE && prev[u] + w < dist[v])
                    dist[v] = prev[u] + w;
            }
        }
        return dist[dst] == Integer.MAX_VALUE ? -1 : dist[dst];
    }
}
```

**Dry run.** `n=3`, flights `[[0,1,100],[1,2,100],[0,2,500]]`, src 0, dst 2, k=1. Round0 (≤1 edge): dist[1]=100, dist[2]=500. Round1 (≤2 edges): relax 1→2 using prev[1]=100 → dist[2]=200. Answer **200** (uses 1 stop). With `k=0`, only the direct 0→2 = **500**.

**Complexity.** Time `O((k+1)·E)`; space `O(n)`. **Edge cases:** `src == dst` → 0; no route within `k` stops → -1; forgetting the snapshot clone lets one round traverse several edges (a classic bug).

---

### Problem 28: Word Ladder II — All Shortest Transformations (LeetCode 126)

**Statement.** Given `beginWord`, `endWord`, and `wordList`, return **all** shortest transformation sequences from `beginWord` to `endWord` (each step changes one letter and stays in the list).
**Constraints.** Word length ≤ 5; `wordList.length ≤ 500`; lowercase letters.

**Approach.** The naive "find shortest length, then DFS every path" recomputes too much. Optimal is two phases: (1) **BFS layer by layer** to build a predecessor map (a shortest-path DAG), advancing one level at a time and recording for each newly discovered word *all* words in the previous layer that reach it; stop at the level where `endWord` first appears so no longer paths are recorded. (2) **DFS/backtrack** from `endWord` through the predecessor map back to `beginWord`, emitting each reconstructed path reversed. Removing a whole layer from the dictionary after processing it prevents revisiting and keeps only shortest paths.

```java
import java.util.*;

public class WordLadderII {
    public List<List<String>> findLadders(String beginWord, String endWord, List<String> wordList) {
        List<List<String>> res = new ArrayList<>();
        Set<String> dict = new HashSet<>(wordList);
        if (!dict.contains(endWord)) return res;
        Map<String, List<String>> parents = new HashMap<>();   // word -> who reaches it
        Set<String> level = new HashSet<>();
        level.add(beginWord);
        boolean found = false;
        while (!level.isEmpty() && !found) {
            dict.removeAll(level);                              // prevent using current layer again
            Set<String> next = new HashSet<>();
            for (String word : level) {
                char[] arr = word.toCharArray();
                for (int i = 0; i < arr.length; i++) {
                    char original = arr[i];
                    for (char c = 'a'; c <= 'z'; c++) {
                        if (c == original) continue;
                        arr[i] = c;
                        String cand = new String(arr);
                        if (dict.contains(cand)) {
                            if (cand.equals(endWord)) found = true;
                            next.add(cand);
                            parents.computeIfAbsent(cand, z -> new ArrayList<>()).add(word);
                        }
                    }
                    arr[i] = original;
                }
            }
            level = next;
        }
        if (found) {
            LinkedList<String> path = new LinkedList<>();
            path.add(endWord);
            backtrack(endWord, beginWord, parents, path, res);
        }
        return res;
    }
    private void backtrack(String word, String begin, Map<String, List<String>> parents,
                           LinkedList<String> path, List<List<String>> res) {
        if (word.equals(begin)) { res.add(new ArrayList<>(path)); return; }
        for (String p : parents.getOrDefault(word, Collections.emptyList())) {
            path.addFirst(p);
            backtrack(p, begin, parents, path, res);
            path.removeFirst();
        }
    }
}
```

**Dry run.** begin `hit`, end `cog`, dict `[hot,dot,dog,lot,log,cog]`. BFS layers: {hit}→{hot}→{dot,lot}→{dog,log}→{cog}. Parents let backtracking reconstruct `[hit,hot,dot,dog,cog]` and `[hit,hot,lot,log,cog]`. Both length 5.

**Complexity.** Time `O(N·L·26 + P)` where `P` is total path output size; space `O(N·L)`. **Edge cases:** `endWord` absent → empty list; multiple equal-length paths returned; `beginWord` may or may not be in the dictionary (handled by starting the level with it explicitly).

---

### Problem 29: Number of Islands II — Online Union-Find (LeetCode 305)

**Statement.** An `m×n` grid starts all water. Given a sequence of `positions` that turn cells to land one at a time, return after each addition the current number of islands.
**Constraints.** `1 ≤ m, n ≤ 1000`, `1 ≤ positions.length ≤ 10^4`.

**Approach.** Re-running a full grid scan after each addition is `O(K·m·n)` and too slow. Because land is **added incrementally**, dynamic connectivity is the perfect tool: **Union-Find**. Map cell `(r,c)` to id `r*n + c`. Adding a cell increments the island count by one, then for each of its (up to 4) already-land neighbors, union — each successful union (distinct roots) decrements the count. Path compression + union by rank give near-constant amortized cost. Guard against re-adding the same cell.

```java
import java.util.*;

public class NumberOfIslandsII {
    private int[] parent, rank_;
    private int count;

    public List<Integer> numIslands2(int m, int n, int[][] positions) {
        parent = new int[m * n];
        rank_ = new int[m * n];
        Arrays.fill(parent, -1);                 // -1 = water (not yet land)
        count = 0;
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        List<Integer> res = new ArrayList<>(positions.length);
        for (int[] p : positions) {
            int r = p[0], c = p[1], id = r * n + c;
            if (parent[id] != -1) { res.add(count); continue; }  // duplicate add
            parent[id] = id;
            count++;
            for (int[] d : dirs) {
                int nr = r + d[0], nc = c + d[1], nid = nr * n + nc;
                if (nr >= 0 && nr < m && nc >= 0 && nc < n && parent[nid] != -1)
                    union(id, nid);
            }
            res.add(count);
        }
        return res;
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
        count--;                                 // two islands merged into one
    }
}
```

**Dry run.** `m=3,n=3`, positions `[[0,0],[0,1],[1,2],[2,1]]`. Add (0,0)→1. Add (0,1) unions with (0,0)→still 1. Add (1,2) (no land neighbor)→2. Add (2,1) (no land neighbor)→3. Result `[1,1,2,3]`.

**Complexity.** Time `O(K·α(m·n))`; space `O(m·n)`. **Edge cases:** duplicate positions must not double-count; cells with no land neighbors create a fresh island; merging two large islands counts as a single decrement.

---

### Problem 30: Graph Valid Tree — Connectivity + Acyclicity (LeetCode 261)

**Statement.** Given `n` nodes `0..n-1` and an undirected `edges` list, return `true` iff the graph forms a valid tree.
**Constraints.** `1 ≤ n ≤ 2000`; no self-loops or duplicate edges.

**Approach.** A graph is a tree iff it is **connected** and **acyclic**. A key shortcut: a tree on `n` nodes has exactly `n-1` edges. So first reject if `edges.length != n-1`. Given exactly `n-1` edges, "connected" and "acyclic" become equivalent (either implies the other), so it suffices to check one. Two optimal approaches: **Union-Find** (any union of two nodes already in the same set ⇒ cycle ⇒ false; otherwise after processing all edges it is a tree); or a **single BFS/DFS** verifying all `n` nodes are reachable from node 0. Union-Find shown for its `α` time and clean cycle test.

```java
public class GraphValidTree {
    private int[] parent;

    public boolean validTree(int n, int[][] edges) {
        if (edges.length != n - 1) return false;       // necessary edge-count check
        parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int[] e : edges)
            if (!union(e[0], e[1])) return false;       // cycle detected
        return true;                                    // n-1 edges + no cycle => connected tree
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

**Dry run.** `n=5`, edges `[[0,1],[0,2],[0,3],[1,4]]`. 4 edges == n-1. All unions succeed (no repeated roots) → connected, acyclic → **true**. Adding `[1,2]` would give 5 edges ≠ 4 → false immediately.

**Complexity.** Time `O(n·α(n))`; space `O(n)`. **Edge cases:** `n=1` with no edges → true (single-node tree); `edges.length != n-1` short-circuits both the disconnected and cyclic failures; self-loops/duplicate edges are excluded by constraints but would otherwise need explicit handling.

---

### Problem 31: Redundant Connection — First Cycle Edge via Union-Find (LeetCode 684)

**Statement.** A tree on `n` nodes had one extra edge added, forming exactly one cycle. Given the `edges` in input order, return the edge that can be removed so the result is a tree — if multiple, return the one appearing **last** in the input.
**Constraints.** `3 ≤ n ≤ 1000`; the graph is connected with exactly one cycle.

**Approach.** Process edges in order with **Union-Find**. The first edge whose two endpoints are *already* in the same set is the one that closes the cycle; since we scan in input order, it is automatically the last such edge among the candidates. Return it immediately. This is `O(n·α(n))` and far cleaner than repeatedly removing an edge and testing connectivity (`O(n²)`).

```java
public class RedundantConnection {
    private int[] parent;

    public int[] findRedundantConnection(int[][] edges) {
        int n = edges.length;
        parent = new int[n + 1];                 // nodes are 1-indexed
        for (int i = 1; i <= n; i++) parent[i] = i;
        for (int[] e : edges) {
            if (!union(e[0], e[1])) return e;     // endpoints already connected -> cycle edge
        }
        return new int[0];                        // unreachable per constraints
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

**Dry run.** edges `[[1,2],[1,3],[2,3]]`. union(1,2) ok; union(1,3) ok (1 and 3 join); union(2,3): find(2)=find(3) (same set) → cycle → return `[2,3]`.

**Complexity.** Time `O(n·α(n))`; space `O(n)`. **Edge cases:** the "return the last" rule is satisfied for free by in-order scanning; nodes are 1-indexed so size the parent array `n+1`; the directed variant ("Redundant Connection II", LC 685) additionally must handle a node with two parents — a meaningfully harder follow-up.

---

### Problem 32: Minimum Height Trees — Topological Leaf-Trimming (LeetCode 310)

**Statement.** Given a tree of `n` nodes and `n-1` undirected `edges`, a node chosen as root yields a rooted tree of some height. Return all root labels that minimize the tree height (the *centroids*, 1 or 2 of them).
**Constraints.** `1 ≤ n ≤ 2·10^4`; the input is a valid tree.

**Approach.** Trying every root with a BFS is `O(n²)` and times out. Insight: the answer is the **center(s)** of the tree, and a tree has at most two centers. Find them by **iteratively trimming leaves** (a topological-sort-flavored BFS on an undirected tree): repeatedly remove all current leaves (degree-1 nodes) layer by layer; the last 1 or 2 nodes remaining are the centroids. This is `O(n)` because every node is removed once. It mirrors Kahn's algorithm but uses degree-1 (leaves) instead of in-degree-0.

```
Peel leaves inward; the deepest "core" is the center:
   leaves ──► next leaves ──► ... ──► 1 or 2 center nodes left
```

```java
import java.util.*;

public class MinimumHeightTrees {
    public List<Integer> findMinHeightTrees(int n, int[][] edges) {
        if (n == 1) return Collections.singletonList(0);
        List<Set<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) adj.add(new HashSet<>());
        for (int[] e : edges) {
            adj.get(e[0]).add(e[1]);
            adj.get(e[1]).add(e[0]);
        }
        List<Integer> leaves = new ArrayList<>();
        for (int i = 0; i < n; i++)
            if (adj.get(i).size() == 1) leaves.add(i);

        int remaining = n;
        while (remaining > 2) {
            remaining -= leaves.size();
            List<Integer> next = new ArrayList<>();
            for (int leaf : leaves) {
                int neighbor = adj.get(leaf).iterator().next();
                adj.get(neighbor).remove(leaf);
                if (adj.get(neighbor).size() == 1) next.add(neighbor);
            }
            leaves = next;
        }
        return leaves;                              // the 1 or 2 centroids
    }
}
```

**Dry run.** `n=6`, edges `[[3,0],[3,1],[3,2],[3,4],[5,4]]`. Initial leaves {0,1,2,5}. Trim them → 3 now has degree 1, 4 degree 1; remaining = 2 → stop. Centroids `[3,4]`.

**Complexity.** Time `O(n)`; space `O(n)`. **Edge cases:** `n=1` → `[0]` (handle before trimming); `n=2` → both nodes are centers; a path graph always yields its middle 1 or 2 nodes.

---

### Problem 33: Accounts Merge — Union-Find over Emails (LeetCode 721)

**Statement.** Each account is `[name, email1, email2, ...]`. Two accounts belong to the same person if they share any email. Merge accounts: output each person's name followed by their emails sorted, deduplicated.
**Constraints.** `1 ≤ accounts.length ≤ 1000`, total emails ≤ 10^4.

**Approach.** Model **emails as graph nodes**; within one account all its emails are connected, and shared emails bridge accounts. Use **Union-Find on emails**: assign each distinct email an integer id, union all emails inside the same account, and remember each email's owner name. After unioning, group emails by their set root, sort each group, and prepend the name. This `O(N·α + N log N)` approach is cleaner than building an explicit graph and BFS-ing components.

```java
import java.util.*;

public class AccountsMerge {
    private int[] parent;
    private int find(int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
    private void union(int a, int b) { parent[find(a)] = find(b); }

    public List<List<String>> accountsMerge(List<List<String>> accounts) {
        Map<String, Integer> emailId = new HashMap<>();
        Map<String, String> emailName = new HashMap<>();
        int id = 0;
        for (List<String> acc : accounts)
            for (int i = 1; i < acc.size(); i++) {
                String email = acc.get(i);
                if (!emailId.containsKey(email)) emailId.put(email, id++);
                emailName.put(email, acc.get(0));
            }
        parent = new int[id];
        for (int i = 0; i < id; i++) parent[i] = i;
        for (List<String> acc : accounts)
            for (int i = 2; i < acc.size(); i++)
                union(emailId.get(acc.get(i - 1)), emailId.get(acc.get(i)));

        Map<Integer, TreeSet<String>> groups = new HashMap<>();
        for (Map.Entry<String, Integer> e : emailId.entrySet())
            groups.computeIfAbsent(find(e.getValue()), z -> new TreeSet<>()).add(e.getKey());

        List<List<String>> res = new ArrayList<>();
        for (TreeSet<String> emails : groups.values()) {
            List<String> row = new ArrayList<>();
            row.add(emailName.get(emails.first()));
            row.addAll(emails);
            res.add(row);
        }
        return res;
    }
}
```

**Dry run.** accounts `[[John,a,b],[John,b,c],[Mary,d]]`. Emails a,b,c get ids; within acc0 union(a,b); acc1 union(b,c) → {a,b,c} one set. Mary's d alone. Output `[John,a,b,c]` and `[Mary,d]`.

**Complexity.** Time `O(N log N)` dominated by sorting emails; space `O(N)`. **Edge cases:** same name different people (don't merge unless emails overlap); a single email account; the `TreeSet` handles dedup + sort in one structure.

---

### Problem 34: Evaluate Division — Weighted Graph DFS (LeetCode 399)

**Statement.** Given equations `a / b = value`, answer queries `x / y` returning the computed ratio or `-1.0` if undeterminable.
**Constraints.** `1 ≤ equations.length ≤ 20`, `1 ≤ queries.length ≤ 20`, values in `(0, 20]`.

**Approach.** Build a **directed weighted graph**: edge `a→b` with weight `value` and `b→a` with `1/value`. A query `x/y` is the product of edge weights along any path from `x` to `y` (ratios multiply). DFS (or BFS) from `x` accumulating the product; if `y` is reached, return it. Unknown variables or disconnected pairs → `-1.0`. (A Union-Find with weights to a representative, or a Floyd–Warshall closure of ratios, are alternatives; DFS is simplest at this scale.)

```
a/b=2, b/c=3  =>  a/c = 2*3 = 6  (multiply weights along the path)
   a --2--> b --3--> c
```

```java
import java.util.*;

public class EvaluateDivision {
    public double[] calcEquation(List<List<String>> equations, double[] values,
                                 List<List<String>> queries) {
        Map<String, Map<String, Double>> g = new HashMap<>();
        for (int i = 0; i < equations.size(); i++) {
            String a = equations.get(i).get(0), b = equations.get(i).get(1);
            g.computeIfAbsent(a, z -> new HashMap<>()).put(b, values[i]);
            g.computeIfAbsent(b, z -> new HashMap<>()).put(a, 1.0 / values[i]);
        }
        double[] res = new double[queries.size()];
        for (int i = 0; i < queries.size(); i++) {
            String x = queries.get(i).get(0), y = queries.get(i).get(1);
            if (!g.containsKey(x) || !g.containsKey(y)) res[i] = -1.0;
            else res[i] = dfs(g, x, y, 1.0, new HashSet<>());
        }
        return res;
    }
    private double dfs(Map<String, Map<String, Double>> g, String cur, String target,
                       double product, Set<String> seen) {
        if (cur.equals(target)) return product;
        seen.add(cur);
        for (Map.Entry<String, Double> nei : g.get(cur).entrySet()) {
            if (seen.contains(nei.getKey())) continue;
            double r = dfs(g, nei.getKey(), target, product * nei.getValue(), seen);
            if (r != -1.0) return r;
        }
        return -1.0;
    }
}
```

**Dry run.** equations `[[a,b],[b,c]]`, values `[2,3]`. Query `a/c`: DFS a→b (×2) → b→c (×3) → reach c → 6.0. Query `x/x` for unknown `x` → -1.0 (not in graph). Query `a/a` → 1.0 (start equals target).

**Complexity.** Time `O(Q·(V+E))`; space `O(V+E)`. **Edge cases:** variable not present → -1.0; `x/x` for a *known* variable → 1.0; cycles are bounded by the `seen` set so no infinite recursion.

---

### Problem 35: Shortest Path with Obstacle Elimination — BFS over (cell, k) States (LeetCode 1293)

**Statement.** In an `m×n` grid of `0` (empty) and `1` (obstacle), starting at `(0,0)`, reach `(m-1,n-1)` in the fewest steps (4-directional) while eliminating **at most `k`** obstacles. Return the step count or `-1`.
**Constraints.** `1 ≤ m, n ≤ 40`, `1 ≤ m·n`, `0 ≤ k ≤ m·n`.

**Approach.** A plain grid BFS over cells is insufficient because reaching a cell with *more remaining eliminations* can unlock shorter future paths — so state must include the budget. Model the state as `(row, col, remainingK)` and run **BFS over this expanded state space**. Visited set keyed on `(r, c, remaining)`; BFS guarantees the first arrival at the target is the shortest. A strong optimization: if `k ≥ m + n - 2`, you can always walk the Manhattan-shortest path, so return `m + n - 2` immediately. Also, when revisiting a cell, only proceed if you arrive with *more* budget than before (track best remaining per cell) to prune states.

```java
import java.util.*;

public class ShortestPathObstacleElimination {
    public int shortestPath(int[][] grid, int k) {
        int m = grid.length, n = grid[0].length;
        if (k >= m + n - 2) return m + n - 2;          // straight Manhattan path always possible
        int[][] bestRemaining = new int[m][n];
        for (int[] row : bestRemaining) Arrays.fill(row, -1);
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        Queue<int[]> q = new ArrayDeque<>();           // {r, c, remainingK}
        q.offer(new int[]{0, 0, k - grid[0][0]});
        bestRemaining[0][0] = k - grid[0][0];
        int steps = 0;
        while (!q.isEmpty()) {
            int size = q.size();
            for (int s = 0; s < size; s++) {
                int[] cur = q.poll();
                int r = cur[0], c = cur[1], rem = cur[2];
                if (r == m - 1 && c == n - 1) return steps;
                for (int[] d : dirs) {
                    int nr = r + d[0], nc = c + d[1];
                    if (nr < 0 || nr >= m || nc < 0 || nc >= n) continue;
                    int nrem = rem - grid[nr][nc];
                    if (nrem < 0) continue;             // not enough budget to break this obstacle
                    if (nrem <= bestRemaining[nr][nc]) continue;  // a better/equal state already queued
                    bestRemaining[nr][nc] = nrem;
                    q.offer(new int[]{nr, nc, nrem});
                }
            }
            steps++;
        }
        return -1;
    }
}
```

**Dry run.** `grid=[[0,0,0],[1,1,0],[0,0,0],[0,1,1],[0,0,0]]`, `k=1`. BFS finds a 10-step path that breaks exactly one obstacle. With `k=0` the obstacle wall blocks the short route, forcing a longer detour or `-1` if none exists.

**Complexity.** Time `O(m·n·k)` states; space `O(m·n·k)` (or `O(m·n)` with the best-remaining prune). **Edge cases:** start cell itself an obstacle consumes one elimination; `k` large → instant Manhattan answer; unreachable target → -1.

---

### Problem 36: Course Schedule with Lexicographically Smallest / Unique Order — Kahn Variants

**Statement.** Variation of topological sort: (a) return the **lexicographically smallest** valid topological order of a DAG; (b) determine whether the topological order is **unique** (a *Hamiltonian path* exists in the DAG, e.g. LeetCode 444 "Sequence Reconstruction").
**Constraints.** `1 ≤ n ≤ 10^4`, edges form a DAG (else no order).

**Approach.** Start from Kahn's algorithm. For (a), replace the plain queue with a **min-heap (`PriorityQueue`)**: always emit the smallest available in-degree-0 node. This makes the order lexicographically minimal at `O(E + V log V)`. For (b), the order is **unique iff at every step exactly one node has in-degree 0** — i.e. the ready queue never holds more than one candidate. If the queue ever holds ≥ 2, multiple valid orders exist (not unique); if it empties before producing all `n`, there is a cycle. Both are small twists on the same Kahn skeleton; understanding why exposes the structure of topological orderings.

```java
import java.util.*;

public class TopoSortVariants {
    // (a) lexicographically smallest topological order, or empty if a cycle exists
    public int[] smallestTopoOrder(int n, int[][] edges) {
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
        int[] indeg = new int[n];
        for (int[] e : edges) { adj.get(e[0]).add(e[1]); indeg[e[1]]++; }
        PriorityQueue<Integer> pq = new PriorityQueue<>();
        for (int i = 0; i < n; i++) if (indeg[i] == 0) pq.offer(i);
        int[] order = new int[n];
        int idx = 0;
        while (!pq.isEmpty()) {
            int u = pq.poll();
            order[idx++] = u;
            for (int v : adj.get(u)) if (--indeg[v] == 0) pq.offer(v);
        }
        return idx == n ? order : new int[0];
    }

    // (b) is the topological order unique? (queue never holds >1 ready node)
    public boolean uniqueTopoOrder(int n, int[][] edges) {
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
        int[] indeg = new int[n];
        for (int[] e : edges) { adj.get(e[0]).add(e[1]); indeg[e[1]]++; }
        Deque<Integer> q = new ArrayDeque<>();
        for (int i = 0; i < n; i++) if (indeg[i] == 0) q.offer(i);
        int seen = 0;
        while (!q.isEmpty()) {
            if (q.size() > 1) return false;          // a choice exists => not unique
            int u = q.poll();
            seen++;
            for (int v : adj.get(u)) if (--indeg[v] == 0) q.offer(v);
        }
        return seen == n;                            // false also covers a cycle
    }
}
```

**Dry run (a).** `n=3`, edges `[[1,0],[2,0]]`. In-deg 0 nodes: {1,2} → heap pops 1 then 2, each frees nothing until both done; 0 emitted last → `[1,2,0]` (smallest). **Dry run (b).** Same graph: ready queue starts {1,2} (size 2) → returns `false` (orders `[1,2,0]` and `[2,1,0]` both valid).

**Complexity.** Time `O(E + V log V)` for (a), `O(V + E)` for (b); space `O(V + E)`. **Edge cases:** a cycle yields fewer than `n` emitted nodes → empty / not-unique; a single chain `a→b→c` is the unique order; disconnected DAG components break uniqueness (multiple roots ready at once).

---

### Problem 37: Alien Dictionary — Build Edges then Topological Sort (LeetCode 269)

**Statement.** Given a sorted list of `words` from an alien language using lowercase letters, derive any valid ordering of the alphabet. Return `""` if the ordering is invalid/contradictory.
**Constraints.** `1 ≤ words.length ≤ 100`, total characters ≤ 10^4.

**Approach.** Two-phase: (1) **derive edges** — adjacent words give exactly one ordering fact: the first differing character `c1` vs `c2` implies `c1` comes before `c2`. Critically, if a word is a **prefix of its predecessor** (e.g. `["abc","ab"]`), the input is invalid → return `""`. (2) **Topological sort** (Kahn) over the seen characters; if a cycle exists (fewer chars emitted than seen), the ordering is contradictory → `""`. Only characters that actually appear are included.

```java
import java.util.*;

public class AlienDictionary {
    public String alienOrder(String[] words) {
        Map<Character, Set<Character>> adj = new HashMap<>();
        Map<Character, Integer> indeg = new HashMap<>();
        for (String w : words)
            for (char ch : w.toCharArray()) {
                adj.putIfAbsent(ch, new HashSet<>());
                indeg.putIfAbsent(ch, 0);
            }
        for (int i = 0; i + 1 < words.length; i++) {
            String a = words[i], b = words[i + 1];
            int len = Math.min(a.length(), b.length()), j = 0;
            while (j < len && a.charAt(j) == b.charAt(j)) j++;
            if (j == len) {
                if (a.length() > b.length()) return "";   // prefix violation: "abc" before "ab"
            } else {
                char c1 = a.charAt(j), c2 = b.charAt(j);
                if (adj.get(c1).add(c2)) indeg.merge(c2, 1, Integer::sum);
            }
        }
        Queue<Character> q = new ArrayDeque<>();
        for (char ch : indeg.keySet()) if (indeg.get(ch) == 0) q.offer(ch);
        StringBuilder sb = new StringBuilder();
        while (!q.isEmpty()) {
            char u = q.poll();
            sb.append(u);
            for (char v : adj.get(u))
                if (indeg.merge(v, -1, Integer::sum) == 0) q.offer(v);
        }
        return sb.length() == indeg.size() ? sb.toString() : "";  // cycle => contradiction
    }
}
```

**Dry run.** words `["wrt","wrf","er","ett","rftt"]`. Edges: t→f (wrt vs wrf), w→e (wrt vs er), r→t (er vs ett), e→r (ett vs rftt). Kahn yields e.g. `"wertf"`. A contradictory input like `["z","x","z"]` produces a cycle z↔x → `""`.

**Complexity.** Time `O(C)` where `C` is total characters (edges are `O(unique pairs)`); space `O(1)` alphabet-bounded (≤ 26 nodes). **Edge cases:** prefix-followed-by-shorter-word is invalid; a single word → any order of its letters; cycle in derived edges → `""`.

---

### Problem 38: Strongly Connected Components — Kosaraju's Two-Pass DFS

**Statement.** Given a directed graph, partition its vertices into **strongly connected components** (SCCs): maximal sets where every vertex is reachable from every other. Return the number of SCCs (or the components themselves).
**Constraints.** `1 ≤ V ≤ 10^5`, `0 ≤ E ≤ 2·10^5`.

**Approach.** **Kosaraju's algorithm** in two DFS passes. Pass 1: DFS the original graph, pushing each vertex onto a stack on *finish* (post-order). Pass 2: process vertices in **reverse finish order**, running DFS on the **transposed** graph (all edges reversed); each DFS tree in this pass is exactly one SCC. The intuition: finishing order on the original graph orders components topologically; reversing edges then confines each traversal to a single SCC. `O(V+E)`. (Tarjan's single-pass low-link algorithm is an alternative with the same complexity; Kosaraju is easier to reason about.)

```
1) DFS G, record finish order (post-order) on a stack.
2) Transpose G (reverse every edge).
3) Pop vertices in reverse-finish order; each DFS on G^T = one SCC.
```

```java
import java.util.*;

public class Kosaraju {
    public int countSCC(int n, List<List<Integer>> adj) {
        boolean[] visited = new boolean[n];
        Deque<Integer> finish = new ArrayDeque<>();
        for (int i = 0; i < n; i++)
            if (!visited[i]) dfs1(adj, i, visited, finish);   // pass 1: order by finish time

        List<List<Integer>> rev = new ArrayList<>();
        for (int i = 0; i < n; i++) rev.add(new ArrayList<>());
        for (int u = 0; u < n; u++)
            for (int v : adj.get(u)) rev.get(v).add(u);        // transpose

        Arrays.fill(visited, false);
        int scc = 0;
        while (!finish.isEmpty()) {
            int u = finish.pop();
            if (!visited[u]) { dfs2(rev, u, visited); scc++; } // each tree = one SCC
        }
        return scc;
    }
    private void dfs1(List<List<Integer>> adj, int u, boolean[] vis, Deque<Integer> finish) {
        vis[u] = true;
        for (int v : adj.get(u)) if (!vis[v]) dfs1(adj, v, vis, finish);
        finish.push(u);                                        // post-order
    }
    private void dfs2(List<List<Integer>> rev, int u, boolean[] vis) {
        vis[u] = true;
        for (int v : rev.get(u)) if (!vis[v]) dfs2(rev, v, vis);
    }
}
```

**Dry run.** Edges `0→1, 1→2, 2→0, 2→3, 3→4, 4→3`. Pass 1 finish order (top of stack first) ~ `0,1,2,3,4`-ish. Pass 2 on the transpose isolates `{0,1,2}` (a 3-cycle) and `{3,4}` (a 2-cycle) → **2 SCCs**. Vertices in a cycle land in the same component.

**Complexity.** Time `O(V + E)` (two passes + transpose); space `O(V + E)`. **Edge cases:** a DAG → every vertex is its own SCC (`V` components); a single big cycle → 1 SCC; deep graphs may overflow recursion — convert the DFS passes to explicit stacks for `V = 10^5`.

---

## 🧩 Extended Problems — Supplemental: Medium → Expert

### Problem 39: Strongly Connected Components — Tarjan's Single-Pass Low-Link

**Statement.** Given a directed graph, find its strongly connected components (SCCs) in a **single** DFS pass (no graph transpose). Return the number of SCCs.
**Constraints.** `1 ≤ V ≤ 10^5`, `0 ≤ E ≤ 2·10^5`.

**Approach.** Tarjan maintains `disc[u]` (discovery index) and `low[u]` (the lowest disc reachable from `u`'s DFS subtree using tree edges plus *one* back/cross edge **into a node still on the stack**). Keep an explicit stack of nodes whose SCC is not yet closed, plus an `onStack[]` flag. On a tree edge, recurse then `low[u] = min(low[u], low[v])`; on an edge to an on-stack node, `low[u] = min(low[u], disc[v])`. When `low[u] == disc[u]`, `u` is the **root of an SCC**: pop the stack down to and including `u` — those nodes form one component. One pass, `O(V+E)`, versus Kosaraju's two passes + transpose.

```
disc/low converge at an SCC root:
  push nodes on a stack as discovered; an edge to an
  on-stack node lowers low[]. low[u]==disc[u] => pop SCC.
```

```java
import java.util.*;

public class TarjanSCC {
    private int timer = 0, sccCount = 0;
    private int[] disc, low;
    private boolean[] onStack;
    private Deque<Integer> stack;
    private List<List<Integer>> adj;

    public int countSCC(int n, List<List<Integer>> graph) {
        adj = graph;
        disc = new int[n];
        low = new int[n];
        onStack = new boolean[n];
        Arrays.fill(disc, -1);
        stack = new ArrayDeque<>();
        for (int i = 0; i < n; i++)
            if (disc[i] == -1) dfs(i);
        return sccCount;
    }
    private void dfs(int u) {
        disc[u] = low[u] = timer++;
        stack.push(u);
        onStack[u] = true;
        for (int v : adj.get(u)) {
            if (disc[v] == -1) {                  // tree edge
                dfs(v);
                low[u] = Math.min(low[u], low[v]);
            } else if (onStack[v]) {              // back/cross edge into open SCC
                low[u] = Math.min(low[u], disc[v]);
            }
        }
        if (low[u] == disc[u]) {                  // u is an SCC root
            int w;
            do {
                w = stack.pop();
                onStack[w] = false;
            } while (w != u);
            sccCount++;
        }
    }
}
```

**Dry run.** Edges `0→1, 1→2, 2→0, 2→3, 3→4, 4→3`. DFS pushes 0,1,2; the edge `2→0` (0 on stack) sets `low[2]=0`, propagating `low[1]=low[0]=0`. Node 3 starts a fresh subtree: 3,4 with back edge `4→3` ⇒ `low[3]=disc[3]`, popping `{4,3}` as one SCC; later `low[0]==disc[0]` pops `{2,1,0}`. Total **2 SCCs**.

**Complexity.** Time `O(V + E)`; space `O(V)`. **Edge cases:** a DAG → `V` singleton SCCs; one giant cycle → 1 SCC; recursion depth on `V=10^5` may need an explicit-stack rewrite; only edges into *on-stack* nodes lower `low` (finished cross edges must be ignored).

---

### Problem 40: Articulation Points (Cut Vertices) — Tarjan with Root Special-Case

**Statement.** In a connected undirected graph, find all **articulation points**: vertices whose removal increases the number of connected components.
**Constraints.** `1 ≤ V ≤ 10^5`, `0 ≤ E ≤ 2·10^5`.

**Approach.** Same `disc`/`low` machinery as bridges (Problem 12) but with the `>=` rule and root handling. For a non-root vertex `u` with a DFS child `v`, if `low[v] >= disc[u]` then `v`'s subtree has **no** back edge climbing above `u`, so removing `u` disconnects that subtree → `u` is an articulation point. The DFS **root** is special: it is an articulation point iff it has **≥ 2 DFS children** (removing it splits those subtrees). Count children explicitly for the root.

> Bridges use strict `>` on an *edge*; articulation points use `>=` on a *vertex*. The root exception is the classic gotcha — the `>=` rule never fires for the root because it has no parent edge to climb past.

```java
import java.util.*;

public class ArticulationPoints {
    private int timer = 0;
    private int[] disc, low;
    private boolean[] isAP;
    private List<List<Integer>> adj;

    public List<Integer> findArticulationPoints(int n, List<List<Integer>> graph) {
        adj = graph;
        disc = new int[n];
        low = new int[n];
        isAP = new boolean[n];
        Arrays.fill(disc, -1);
        for (int i = 0; i < n; i++)
            if (disc[i] == -1) dfs(i, -1);
        List<Integer> res = new ArrayList<>();
        for (int i = 0; i < n; i++) if (isAP[i]) res.add(i);
        return res;
    }
    private void dfs(int u, int parent) {
        disc[u] = low[u] = timer++;
        int children = 0;
        for (int v : adj.get(u)) {
            if (v == parent) continue;
            if (disc[v] == -1) {                       // tree edge
                children++;
                dfs(v, u);
                low[u] = Math.min(low[u], low[v]);
                if (parent != -1 && low[v] >= disc[u]) // non-root cut rule
                    isAP[u] = true;
            } else {
                low[u] = Math.min(low[u], disc[v]);    // back edge
            }
        }
        if (parent == -1 && children > 1) isAP[u] = true; // root with ≥2 children
    }
}
```

**Dry run.** Path-with-triangle: edges `0-1, 1-2, 2-0, 1-3`. DFS root 0: child 1 (triangle keeps `low[2]` low so 0 is not cut). From 1, child 3 has `low[3]=disc[3] > disc[1]` and `low[3] >= disc[1]` ⇒ **1 is an articulation point** (removing 1 isolates 3). Output `[1]`.

**Complexity.** Time `O(V + E)`; space `O(V)`. **Edge cases:** a single edge `u-v` → neither endpoint is an AP (each has only one neighbor); a star center is an AP; disconnected input needs the outer loop and per-component root handling; parallel edges require edge-id tracking like bridges.

---

### Problem 41: Reconstruct Itinerary — Eulerian Path via Hierholzer (LeetCode 332)

**Statement.** Given a list of airline `tickets [from, to]`, reconstruct the itinerary starting at `"JFK"`, using every ticket exactly once. If multiple valid itineraries exist, return the one with the **smallest lexical order** when read as a single string.
**Constraints.** `1 ≤ tickets.length ≤ 300`; a valid Eulerian path is guaranteed to exist.

**Approach.** Using every edge exactly once is an **Eulerian path**. Build an adjacency multiset per airport, sorted ascending so we always try the lexicographically smallest next airport. Run **Hierholzer's algorithm**: greedy DFS consuming edges; when a node has no unused outgoing edges, prepend it to the route (post-order). The post-order accumulation correctly handles getting "stuck" — the dead-end airport lands at the tail, and the final reversed/prepended order is the valid Euler path. A min-heap (`PriorityQueue`) per node yields the smallest edge in `O(log)` per pop.

```
Hier(node): while node has edges, take smallest -> recurse;
            then push node to front of route (post-order).
Getting stuck early just means that airport is the last leg.
```

```java
import java.util.*;

public class ReconstructItinerary {
    private Map<String, PriorityQueue<String>> graph = new HashMap<>();
    private LinkedList<String> route = new LinkedList<>();

    public List<String> findItinerary(List<List<String>> tickets) {
        for (List<String> t : tickets)
            graph.computeIfAbsent(t.get(0), z -> new PriorityQueue<>()).add(t.get(1));
        dfs("JFK");
        return route;
    }
    private void dfs(String airport) {
        PriorityQueue<String> dests = graph.get(airport);
        while (dests != null && !dests.isEmpty())
            dfs(dests.poll());            // consume smallest edge first
        route.addFirst(airport);          // post-order: prepend on the way back
    }
}
```

**Dry run.** tickets `[[JFK,SFO],[JFK,ATL],[SFO,ATL],[ATL,JFK],[ATL,SFO]]`. From JFK the smaller dest is ATL; DFS explores ATL→JFK→SFO→ATL→SFO (dead end). Post-order prepends build `["JFK","ATL","JFK","SFO","ATL","SFO"]` — the lexically smallest valid Euler path.

**Complexity.** Time `O(E log E)` (heap ordering of edges); space `O(E)`. **Edge cases:** a node may be revisited multiple times (multigraph); dead-ending mid-traversal is expected and resolved by post-order; the problem guarantees an Euler path so no validity check is needed.

---

### Problem 42: Find Eventual Safe States — Reverse Topo / 3-Color DFS (LeetCode 802)

**Statement.** A directed graph node is **safe** if every path starting from it leads to a terminal node (out-degree 0) — i.e. it cannot reach any cycle. Return all safe nodes in ascending order.
**Constraints.** `1 ≤ n ≤ 10^4`, `0 ≤ edges ≤ 4·10^4`.

**Approach.** A node is safe iff it is **not part of, and cannot reach, any cycle**. Two clean methods. (1) **3-color DFS**: `WHITE`/`GRAY`/`BLACK`; a node is safe iff its DFS finishes without hitting a `GRAY` (on-stack) node — color it `BLACK` (safe). Any node that touches a cycle stays `GRAY`-tainted and is unsafe. (2) **Reverse Kahn**: reverse all edges and run topological peeling from out-degree-0 (original terminal) nodes; everything peeled is safe. The DFS coloring is shown — it naturally caches each node's safety.

```java
import java.util.*;

public class EventualSafeStates {
    public List<Integer> eventualSafeNodes(int[][] graph) {
        int n = graph.length;
        int[] color = new int[n];          // 0=white, 1=gray(on stack), 2=black(safe)
        List<Integer> res = new ArrayList<>();
        for (int i = 0; i < n; i++)
            if (dfs(graph, i, color)) res.add(i);
        return res;
    }
    private boolean dfs(int[][] graph, int u, int[] color) {
        if (color[u] != 0) return color[u] == 2;   // memoized: black=safe, gray=unsafe
        color[u] = 1;                              // gray: on current path
        for (int v : graph[u])
            if (!dfs(graph, v, color)) return false;  // reaches a cycle => unsafe
        color[u] = 2;                              // all paths safe
        return true;
    }
}
```

**Dry run.** `graph=[[1,2],[2,3],[5],[0],[5],[],[]]`. Nodes 5,6 are terminal → safe. Node 4→5 → safe. Node 2→5 → safe. Nodes 0→1→2 but 1→3→0 forms a cycle (0,1,3) → 0,1,3 unsafe. Safe nodes `[2,4,5,6]`.

**Complexity.** Time `O(V + E)` (each node colored once); space `O(V)`. **Edge cases:** a self-loop makes a node unsafe; terminal nodes are trivially safe; the memoized color doubles as cycle detection so no separate visited set is needed.

---

### Problem 43: Minimum Days to Disconnect Island — Articulation Insight + Brute Force (LeetCode 1568)

**Statement.** Given a binary grid (1 = land), return the **minimum number of days** to disconnect the island — each day you may flip one land cell to water — so that the number of land islands is not exactly one (either 0 or ≥ 2).
**Constraints.** `1 ≤ m, n ≤ 30`.

**Approach.** Key theorem: the answer is always **0, 1, or 2**. If the grid already has ≠ 1 island → **0**. Otherwise, if any single land cell is an **articulation point** of the land graph (or the island has ≤ 2 cells), removing it disconnects → **1**. Otherwise **2** (removing any corner of the island always reduces it to a state removable in one more day — a 2×2-or-larger blob can always be cut in 2). The robust implementation for the small constraint: count islands; if ≠1 return 0; try removing each land cell and re-count (if any gives ≠1, return 1); else return 2. `O((mn)²)` is fine for `30×30`.

```java
public class MinDaysToDisconnect {
    public int minDays(int[][] grid) {
        if (countIslands(grid) != 1) return 0;
        int m = grid.length, n = grid[0].length;
        for (int r = 0; r < m; r++)
            for (int c = 0; c < n; c++)
                if (grid[r][c] == 1) {
                    grid[r][c] = 0;
                    if (countIslands(grid) != 1) { grid[r][c] = 1; return 1; }
                    grid[r][c] = 1;
                }
        return 2;
    }
    private int countIslands(int[][] grid) {
        int m = grid.length, n = grid[0].length, count = 0;
        boolean[][] seen = new boolean[m][n];
        for (int r = 0; r < m; r++)
            for (int c = 0; c < n; c++)
                if (grid[r][c] == 1 && !seen[r][c]) { count++; sink(grid, seen, r, c); }
        return count;
    }
    private void sink(int[][] g, boolean[][] seen, int r, int c) {
        if (r < 0 || r >= g.length || c < 0 || c >= g[0].length || g[r][c] == 0 || seen[r][c]) return;
        seen[r][c] = true;
        sink(g, seen, r + 1, c); sink(g, seen, r - 1, c);
        sink(g, seen, r, c + 1); sink(g, seen, r, c - 1);
    }
}
```

**Dry run.** `[[0,1,1,0],[0,1,1,0],[0,0,0,0]]` — one 2×2 island. Removing any single cell leaves an L-shape that is still connected (1 island), so no day-1 cut works → answer **2**. A 1×2 island `[[1,1]]` → removing one cell yields 0 islands → **1**.

**Complexity.** Time `O((m·n)²)`; space `O(m·n)`. **Edge cases:** already disconnected or empty → 0; a single land cell → removing it gives 0 islands → 1; the proof that the answer never exceeds 2 lets the brute force stop early.

---

### Problem 44: Detonate the Maximum Bombs — Directed Reachability DFS (LeetCode 2101)

**Statement.** Each bomb is `[x, y, r]` (position and blast radius). Detonating a bomb triggers every bomb whose center lies within its radius, which chains. Return the maximum number of bombs detonated by choosing the best single starting bomb.
**Constraints.** `1 ≤ bombs.length ≤ 100`; coordinates and radius up to `10^5`.

**Approach.** Build a **directed** graph: edge `i → j` if bomb `j`'s center is within bomb `i`'s radius (the relation is *not* symmetric — a big bomb reaches a small one but not vice versa). Use squared distances to avoid floating point: `dx*dx + dy*dy <= r_i * r_i`. Then for each starting node, run DFS/BFS counting reachable nodes; the maximum over all starts is the answer. With `n ≤ 100`, `O(n³)` (n starts × O(n²) edges/DFS) is comfortable.

```java
import java.util.*;

public class DetonateBombs {
    public int maximumDetonation(int[][] bombs) {
        int n = bombs.length;
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                long dx = bombs[i][0] - bombs[j][0];
                long dy = bombs[i][1] - bombs[j][1];
                long r = bombs[i][2];
                if (dx * dx + dy * dy <= r * r) adj.get(i).add(j); // i can trigger j
            }
        int best = 0;
        for (int i = 0; i < n; i++) {
            boolean[] visited = new boolean[n];
            best = Math.max(best, dfs(adj, i, visited));
            if (best == n) break;
        }
        return best;
    }
    private int dfs(List<List<Integer>> adj, int u, boolean[] visited) {
        visited[u] = true;
        int count = 1;
        for (int v : adj.get(u))
            if (!visited[v]) count += dfs(adj, v, visited);
        return count;
    }
}
```

**Dry run.** bombs `[[2,1,3],[6,1,4]]`. Distance² = 16; bomb0 radius²=9 < 16 (cannot reach bomb1); bomb1 radius²=16 == 16 (reaches bomb0). Start at 1 → detonates {1,0} = 2; start at 0 → {0} = 1. Answer **2**.

**Complexity.** Time `O(n³)` (edges `O(n²)`, a DFS per start); space `O(n²)`. **Edge cases:** asymmetric reach is the central trap (do not build an undirected graph); use `long` to avoid overflow when squaring `10^5`; equality `==` counts a bomb exactly on the boundary as triggered.

---

### Problem 45: Number of Operations to Make Network Connected — Union-Find Redundant Edges (LeetCode 1319)

**Statement.** `n` computers `0..n-1` and `connections [a,b]` (existing cables). You may unplug any cable and reconnect it between any two computers. Return the **minimum operations** to connect all computers, or `-1` if impossible.
**Constraints.** `1 ≤ n ≤ 10^5`, `1 ≤ connections.length ≤ min(n·(n-1)/2, 10^5)`.

**Approach.** To connect `c` components into one you need exactly `c - 1` additional cables. Those cables must come from **redundant** existing ones (a cable whose endpoints are already connected). So: if `connections.length < n - 1`, there are too few cables to ever connect `n` nodes → **-1**. Otherwise count components `c` with Union-Find; the answer is `c - 1` (there are always enough redundant cables because total cables `≥ n-1`). One pass, `α`-time.

```java
public class MakeNetworkConnected {
    private int[] parent;

    public int makeConnected(int n, int[][] connections) {
        if (connections.length < n - 1) return -1;     // not enough cables
        parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        int components = n;
        for (int[] e : connections)
            if (union(e[0], e[1])) components--;
        return components - 1;                          // edges needed to join components
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

**Dry run.** `n=4`, connections `[[0,1],[0,2],[1,2]]`. 3 cables ≥ n-1=3. union(0,1),(0,2) merge {0,1,2}; (1,2) is redundant. Components = 2 ({0,1,2},{3}). Answer `2-1 = 1` (move the redundant cable to connect 3).

**Complexity.** Time `O(n + E·α(n))`; space `O(n)`. **Edge cases:** fewer than `n-1` cables → -1 immediately; already fully connected → 0 operations; the count of redundant edges is automatically sufficient once the `n-1` floor is met.

---

### Problem 46: Satisfiability of Equality Equations — Union-Find Two-Phase (LeetCode 990)

**Statement.** Given equations of the form `"a==b"` and `"a!=b"` over single-letter variables, return `true` iff some assignment of integers satisfies all of them.
**Constraints.** `1 ≤ equations.length ≤ 500`; variables are single lowercase letters.

**Approach.** Equality is transitive — process all `==` equations first with **Union-Find**, merging the variables that must be equal. Then check every `!=` equation: if its two variables are in the **same set**, they are forced equal yet required unequal → contradiction → `false`. Two phases matter: unioning all equalities before testing inequalities ensures the disjoint sets reflect the full equality closure. 26 fixed nodes (`a..z`).

```java
public class SatisfiabilityEquations {
    private int[] parent = new int[26];

    public boolean equationsPossible(String[] equations) {
        for (int i = 0; i < 26; i++) parent[i] = i;
        for (String e : equations)                       // phase 1: unite equals
            if (e.charAt(1) == '=')
                union(e.charAt(0) - 'a', e.charAt(3) - 'a');
        for (String e : equations)                       // phase 2: check not-equals
            if (e.charAt(1) == '!')
                if (find(e.charAt(0) - 'a') == find(e.charAt(3) - 'a'))
                    return false;
        return true;
    }
    private int find(int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
    private void union(int a, int b) { parent[find(a)] = find(b); }
}
```

**Dry run.** `["a==b","b!=a"]`: union(a,b) → same set; then `b!=a` finds equal roots → **false**. `["a==b","b==c","a==c"]`: all unite consistently, no `!=` → **true**.

**Complexity.** Time `O(N·α(26))` ≈ `O(N)`; space `O(1)` (fixed 26). **Edge cases:** self-inequality `"a!=a"` is always false (same root); ordering of equations does not matter once equalities precede the inequality checks; only 26 possible nodes.

---

### Problem 47: Regions Cut By Slashes — Union-Find on Sub-Cells (LeetCode 959)

**Statement.** An `n×n` grid where each cell is `'/'`, `'\\'`, or `' '` (space). The slashes divide the unit square into regions. Return the number of contiguous regions.
**Constraints.** `1 ≤ n ≤ 30`.

**Approach.** Split each cell into **4 triangular sub-cells** numbered top=0, right=1, bottom=2, left=3. A `' '` cell unions all 4; a `'/'` unions {top,left} and {right,bottom}; a `'\\'` unions {top,right} and {bottom,left}. Across cells: a cell's **bottom (2)** unions with the cell-below's **top (0)**, and its **right (1)** unions with the cell-to-the-right's **left (3)**. The region count is the number of disjoint sets among the `4·n·n` sub-cells. This converts an awkward geometry problem into clean dynamic connectivity.

```
Sub-cell indexing inside one unit square:
        0 (top)
   3 (left)  1 (right)
        2 (bottom)
'/'  separates {0,3} | {1,2}
'\\' separates {0,1} | {2,3}
' '  all four connected
```

```java
public class RegionsBySlashes {
    private int[] parent;
    private int count;

    public int regionsBySlashes(String[] grid) {
        int n = grid.length;
        parent = new int[4 * n * n];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        count = 4 * n * n;
        for (int r = 0; r < n; r++)
            for (int c = 0; c < n; c++) {
                int base = 4 * (r * n + c);
                char ch = grid[r].charAt(c);
                if (ch == '/') { union(base + 0, base + 3); union(base + 1, base + 2); }
                else if (ch == '\\') { union(base + 0, base + 1); union(base + 2, base + 3); }
                else { union(base, base + 1); union(base + 1, base + 2); union(base + 2, base + 3); }
                if (r + 1 < n) union(base + 2, 4 * ((r + 1) * n + c) + 0);   // bottom-top
                if (c + 1 < n) union(base + 1, 4 * (r * n + (c + 1)) + 3);   // right-left
            }
        return count;
    }
    private int find(int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
    private void union(int a, int b) {
        int ra = find(a), rb = find(b);
        if (ra == rb) return;
        parent[ra] = rb;
        count--;
    }
}
```

**Dry run.** `[" /","/ "]`. The two `'/'` cells split their squares; cross-cell unions merge the spaces. Working through the 16 sub-cells leaves **3** regions.

**Complexity.** Time `O(n²·α)`; space `O(n²)`. **Edge cases:** an all-spaces grid → 1 region (everything connected); a single `'/'` in a `1×1` grid → 2 regions; the escaped backslash `'\\'` in Java source represents one literal `\`.

---

### Problem 48: Shortest Bridge — Island DFS + Multi-Source BFS (LeetCode 934)

**Statement.** A binary grid contains exactly **two** islands of `1`s. Return the minimum number of `0`s that must be flipped to connect them (the length of the shortest bridge).
**Constraints.** `2 ≤ n ≤ 100` (grid is `n×n`); exactly two islands.

**Approach.** Two stages. (1) **DFS** to find and mark the *first* island, pushing all its cells into a BFS queue (multi-source frontier) and tagging them `2`. (2) **Multi-source BFS** outward from the entire first island simultaneously, expanding through water; the number of BFS layers crossed before touching the second island (`1`) is the shortest bridge. Starting BFS from every cell of island one at once guarantees the minimum flips without testing pairs of cells.

```java
import java.util.*;

public class ShortestBridge {
    private final int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};

    public int shortestBridge(int[][] grid) {
        int n = grid.length;
        Queue<int[]> q = new ArrayDeque<>();
        boolean found = false;
        for (int r = 0; r < n && !found; r++)
            for (int c = 0; c < n && !found; c++)
                if (grid[r][c] == 1) { dfs(grid, r, c, q); found = true; }   // mark island 1

        int steps = 0;
        while (!q.isEmpty()) {
            int size = q.size();
            for (int s = 0; s < size; s++) {
                int[] cell = q.poll();
                for (int[] d : dirs) {
                    int nr = cell[0] + d[0], nc = cell[1] + d[1];
                    if (nr < 0 || nr >= n || nc < 0 || nc >= n) continue;
                    if (grid[nr][nc] == 1) return steps;   // reached island 2
                    if (grid[nr][nc] == 0) {
                        grid[nr][nc] = 2;                  // mark visited water
                        q.offer(new int[]{nr, nc});
                    }
                }
            }
            steps++;
        }
        return -1; // unreachable per constraints
    }
    private void dfs(int[][] grid, int r, int c, Queue<int[]> q) {
        if (r < 0 || r >= grid.length || c < 0 || c >= grid[0].length || grid[r][c] != 1) return;
        grid[r][c] = 2;
        q.offer(new int[]{r, c});
        for (int[] d : dirs) dfs(grid, r + d[0], c + d[1], q);
    }
}
```

**Dry run.** `[[0,1],[1,0]]`. DFS marks the first `1` (say at (0,1)); BFS from it: step 0 expands to (0,0) water → marked; step 1 expands and touches the `1` at (1,0) → return **1** (flip one `0`).

**Complexity.** Time `O(n²)` (each cell touched a constant number of times); space `O(n²)`. **Edge cases:** islands adjacent diagonally still need ≥ 1 flip; marking water as `2` prevents BFS re-expansion; the two-island guarantee removes the need to count islands first.

---

### Problem 49: Bus Routes — BFS over a Route Graph (LeetCode 815)

**Statement.** `routes[i]` is the list of stops bus `i` cycles through. Starting at `source`, return the **fewest buses** to reach `target` (0 if `source == target`), or `-1` if impossible.
**Constraints.** `1 ≤ routes.length ≤ 500`, total stops ≤ 10^5.

**Approach.** The trap is treating *stops* as nodes — the natural unit is **buses (routes)**. Build `stopToRoutes`: which routes serve each stop. BFS where each layer = boarding one more bus: start from all routes serving `source`; from a route, you can transfer to any **unvisited route sharing a stop**. Track visited routes (and visited stops to skip already-expanded ones). The BFS depth when `target` is found equals the bus count. Mark routes visited to avoid reprocessing a route's whole stop list repeatedly.

```java
import java.util.*;

public class BusRoutes {
    public int numBusesToDestination(int[][] routes, int source, int target) {
        if (source == target) return 0;
        Map<Integer, List<Integer>> stopToRoutes = new HashMap<>();
        for (int r = 0; r < routes.length; r++)
            for (int stop : routes[r])
                stopToRoutes.computeIfAbsent(stop, z -> new ArrayList<>()).add(r);

        Queue<Integer> q = new ArrayDeque<>();        // queue of stops reached
        Set<Integer> visitedStops = new HashSet<>();
        boolean[] visitedRoutes = new boolean[routes.length];
        q.offer(source);
        visitedStops.add(source);
        int buses = 0;
        while (!q.isEmpty()) {
            int size = q.size();
            buses++;                                  // board one more bus this layer
            for (int s = 0; s < size; s++) {
                int stop = q.poll();
                for (int r : stopToRoutes.getOrDefault(stop, Collections.emptyList())) {
                    if (visitedRoutes[r]) continue;
                    visitedRoutes[r] = true;
                    for (int next : routes[r]) {
                        if (next == target) return buses;
                        if (visitedStops.add(next)) q.offer(next);
                    }
                }
            }
        }
        return -1;
    }
}
```

**Dry run.** routes `[[1,2,7],[3,6,7]]`, source 1, target 6. Layer 1: board route 0 (serves stop 1) → reach stops 2,7. 7 is on route 1 → board route 1 (layer 2) → reach 3,6 → `target` 6 found at **2** buses.

**Complexity.** Time `O(sum of stops)` (each route expanded once); space `O(sum of stops)`. **Edge cases:** `source == target` → 0; target on no route → -1; visiting routes (not stops) bounds work so a route's stop list is scanned once.

---

### Problem 50: Open the Lock — BFS over Combination States (LeetCode 752)

**Statement.** A 4-wheel lock starts at `"0000"`; each move turns one wheel up or down by one digit (wrap-around). Given a set of `deadends` (forbidden states) and a `target`, return the minimum moves to reach the target, or `-1`.
**Constraints.** `deadends.length ≤ 500`; `target` is a 4-digit string.

**Approach.** Each lock state is a node; an edge connects states differing by one `±1` wheel turn (8 neighbors per state). Minimum moves on an unweighted state graph → **BFS** from `"0000"`. Treat `deadends` as walls (a visited/blocked set). Generate neighbors by, for each of the 4 positions, turning the digit up and down with modulo-10 wrap. Early-exit if `"0000"` is itself a deadend. Bidirectional BFS halves the frontier for large search spaces (10^4 states).

```java
import java.util.*;

public class OpenTheLock {
    public int openLock(String[] deadends, String target) {
        Set<String> dead = new HashSet<>(Arrays.asList(deadends));
        if (dead.contains("0000")) return -1;
        if (target.equals("0000")) return 0;
        Queue<String> q = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        q.offer("0000");
        visited.add("0000");
        int turns = 0;
        while (!q.isEmpty()) {
            int size = q.size();
            turns++;
            for (int s = 0; s < size; s++) {
                String cur = q.poll();
                for (String next : neighbors(cur)) {
                    if (next.equals(target)) return turns;
                    if (!dead.contains(next) && visited.add(next)) q.offer(next);
                }
            }
        }
        return -1;
    }
    private List<String> neighbors(String s) {
        List<String> res = new ArrayList<>(8);
        char[] a = s.toCharArray();
        for (int i = 0; i < 4; i++) {
            char orig = a[i];
            a[i] = (char) ('0' + (orig - '0' + 1) % 10);   // turn up
            res.add(new String(a));
            a[i] = (char) ('0' + (orig - '0' + 9) % 10);   // turn down
            res.add(new String(a));
            a[i] = orig;
        }
        return res;
    }
}
```

**Dry run.** deadends `["0201","0101","0102","1212","2002"]`, target `"0202"`. BFS from `0000` routes around the deadends (e.g. `0000→1000→1100→1200→1201→1202→0202`) → **6** moves. If a deadend ring fully encloses the target, returns -1.

**Complexity.** Time `O(10^4 · 8)` states/edges; space `O(10^4)`. **Edge cases:** start `"0000"` is a deadend → -1; target already `"0000"` → 0; deadends checked before enqueue so they are never expanded.

---

### Problem 51: Snakes and Ladders — BFS on a Boustrophedon Board (LeetCode 909)

**Statement.** An `n×n` board labeled `1..n²` in **boustrophedon** (snake/zig-zag) order from bottom-left. From square `s` you roll a die (1–6) to `s+1..s+6`; if a destination has a snake or ladder (`board[r][c] != -1`), you must jump to that label. Return the least number of moves to reach `n²`, or `-1`.
**Constraints.** `2 ≤ n ≤ 20`.

**Approach.** Squares are nodes; from each square there are up to 6 die-roll edges (then a forced snake/ladder jump). Minimum moves on an unweighted graph → **BFS** over labels `1..n²`. The crux is the **label→(row,col) conversion** for the zig-zag layout: rows fill bottom-up, and within a row the column direction alternates. Compute distance per BFS layer; the first time `n²` is dequeued is optimal. Track visited labels to avoid cycles created by snakes.

```
Boustrophedon (n=3): bottom row left→right, next row right→left, ...
  row2: 9  8  7
  row1: 4  5  6
  row0: 1  2  3   (label 1 = bottom-left)
```

```java
import java.util.*;

public class SnakesAndLadders {
    public int snakesAndLadders(int[][] board) {
        int n = board.length, target = n * n;
        boolean[] visited = new boolean[target + 1];
        Queue<Integer> q = new ArrayDeque<>();
        q.offer(1);
        visited[1] = true;
        int moves = 0;
        while (!q.isEmpty()) {
            int size = q.size();
            for (int s = 0; s < size; s++) {
                int label = q.poll();
                if (label == target) return moves;
                for (int d = 1; d <= 6 && label + d <= target; d++) {
                    int next = label + d;
                    int[] rc = toRC(next, n);
                    if (board[rc[0]][rc[1]] != -1) next = board[rc[0]][rc[1]]; // snake/ladder
                    if (!visited[next]) { visited[next] = true; q.offer(next); }
                }
            }
            moves++;
        }
        return -1;
    }
    // convert 1-indexed label to (row, col) in boustrophedon order
    private int[] toRC(int label, int n) {
        int idx = label - 1;
        int row = idx / n;
        int col = idx % n;
        if (row % 2 == 1) col = n - 1 - col;          // odd rows go right→left
        int r = n - 1 - row;                          // labels start from the bottom
        return new int[]{r, col};
    }
}
```

**Dry run.** `n=6` board with a ladder from 2→15 and 4→14 etc. (LeetCode example). BFS: from 1 the die can reach 2 → ladder to 15; layering continues until square 36 is dequeued at **4** moves.

**Complexity.** Time `O(n²)` (each square enqueued once, 6 edges each); space `O(n²)`. **Edge cases:** a snake/ladder landing on the start of another is *not* chained (jump only once per roll); reaching exactly `n²` ends; if no roll sequence reaches the end → -1.

---

## Interview Q&A by Level

### 🟢 Basic
- **Q: When would you choose an adjacency list over a matrix?** Use a list for sparse graphs (`E ≪ V²`) — `O(V+E)` space and fast neighbor iteration. Use a matrix when the graph is dense or you need `O(1)` edge existence queries.
- **Q: BFS vs DFS — when to use which?** BFS for shortest path / minimum steps on unweighted graphs and level-by-level processing; DFS for exhaustive exploration, cycle detection, topological order, and connectivity structure. BFS uses a queue, DFS a stack/recursion.
- **Q: Why mark a node visited when you enqueue, not when you dequeue?** To avoid adding the same node to the queue multiple times, which wastes work and can balloon memory. Marking on enqueue guarantees each node enters the frontier once.
- **Q: How do you detect a cycle in an undirected graph?** DFS tracking the parent — a visited neighbor that is not the parent is a back edge ⇒ cycle. Or Union-Find: an edge joining two vertices already in the same set forms a cycle.

### 🟡 Intermediate
- **Q: How does cycle detection differ between directed and undirected graphs?** Undirected: parent-aware DFS or Union-Find. Directed: 3-color DFS (a `GRAY`/on-stack node = back edge) or Kahn's algorithm (if fewer than `V` nodes get ordered, a cycle exists). The undirected parent trick is *wrong* for directed graphs.
- **Q: What is a topological sort and when does it exist?** A linear ordering of a DAG's vertices such that every edge `u→v` has `u` before `v`. It exists iff the graph is acyclic. Kahn's algorithm builds it from in-degree-0 nodes; DFS builds it by reversing finish order.
- **Q: How do you check if a graph is bipartite, and what's the intuition?** Try a 2-coloring with BFS/DFS; a conflict means not bipartite. A graph is bipartite iff it contains no odd-length cycle.
- **Q: Why is BFS correct for shortest paths only on unweighted graphs?** BFS explores in nondecreasing distance order, so the first time it reaches a node is via a minimum-edge path. With weights, a longer-hop path can be cheaper, so you need Dijkstra (non-negative) or Bellman-Ford.

### 🟠 Advanced
- **Q: Explain Tarjan's low-link values and how they find bridges/articulation points.** `disc[u]` is the DFS discovery time; `low[u]` is the smallest discovery time reachable from `u`'s subtree using at most one back edge. Tree edge `(u,v)` is a bridge if `low[v] > disc[u]`; `u` is an articulation point if some child has `low[v] >= disc[u]` (root: ≥ 2 children). All in one `O(V+E)` DFS.
- **Q: How would you find shortest paths with all distinct sequences (Word Ladder II)?** BFS layer by layer to record predecessors forming a shortest-path DAG, stopping at the level where `endWord` first appears, then DFS/backtrack through the predecessor map to enumerate all shortest sequences.
- **Q: Union-Find vs DFS for connectivity — trade-offs?** DFS computes components in one `O(V+E)` pass over a static graph. Union-Find shines for *dynamic* connectivity (edges added incrementally, e.g. "Number of Islands II") and runs in near-constant amortized time per op with path compression + union by rank.
- **Q: Multi-source BFS — when and why?** When many sources spread simultaneously (rotting oranges, nearest-gate distance). Seed the queue with all sources at distance 0; each BFS layer advances all fronts together, giving correct simultaneous-spread timing in one pass instead of one BFS per source.

### 🔴 Expert
- **Q: How do graph algorithms scale to billions of edges that don't fit in memory?** Use external/streaming or distributed frameworks: Pregel / "think like a vertex" (Apache Giraph, Spark GraphX) where vertices exchange messages in supersteps; partition the graph (edge-cut vs vertex-cut) to balance load and minimize cross-partition communication; compress with CSR (compressed sparse row) for cache efficiency.
- **Q: What's the amortized complexity of Union-Find and why?** With both path compression and union by rank/size, a sequence of `m` operations on `n` elements runs in `O(m·α(n))`, where `α` is the inverse Ackermann function — ≤ 4 for any practical input, effectively constant.
- **Q: Real-world uses of these techniques?** Topological sort → build systems (Make, Bazel), task schedulers, spreadsheet recalculation. BFS shortest path → web crawling, social-network degrees of separation, GPS on unweighted maps. Bridges/articulation points → network reliability and SPOF detection. Graph coloring → register allocation, frequency assignment, exam scheduling. Bipartite matching → job assignment, ad allocation.
- **Q: How do you detect cycles in an enormous directed dependency graph efficiently and report the cycle?** Iterative DFS (avoid stack overflow) with a color array; maintain an explicit recursion-path stack so that when you hit a `GRAY` node you can slice the stack from that node to reconstruct the actual cycle for a useful error message — exactly what package managers and build tools do.

---

## ⚠️ Common Pitfalls

- **Marking visited too late.** Mark on enqueue (BFS) or before recursing (DFS); marking only on dequeue lets duplicates pile up.
- **Forgetting disconnected components.** Loop over *all* vertices for components, cycle detection, bipartite, and topo sort — don't assume one connected blob.
- **Undirected parent check on directed graphs.** The `v != parent` cycle test is only valid for undirected graphs; directed graphs need 3-color DFS or Kahn.
- **Flood-fill infinite loop.** In flood fill, return early when `newColor == oldColor`, or you recurse forever.
- **Recursion stack overflow.** Deep graphs (a long chain of `10^5` nodes) overflow the call stack; convert DFS to an explicit stack for large `V`.
- **Off-by-one in Word Ladder length.** The sequence length counts words (nodes), not transformations (edges); `beginWord` is level 1.
- **Building word-ladder edges in `O(N²)`.** Generate per-position mutations and hash-check membership instead of comparing every pair.
- **Mutating input grids when forbidden.** Sinking islands mutates the grid; if you must preserve it, use a separate `visited[][]`.
- **Bridge detection and parallel edges.** Skipping only the parent *vertex* mislabels parallel edges; track edge IDs when multi-edges are possible.
- **Kahn's cycle check.** If the produced order has fewer than `V` nodes, the graph has a cycle — don't forget this validation.

## 📚 Further Reading

- *Introduction to Algorithms* (CLRS), Ch. 22–26 — BFS, DFS, topological sort, SCC, MST, shortest paths.
- *Algorithm Design Manual* (Skiena), Ch. 5–6 — practical graph traversal and design.
- *Competitive Programming* (Halim & Halim) — Tarjan's bridges/articulation points, problem catalog.
- *Algorithms* (Sedgewick & Wayne), graph chapters — clear Java implementations and Union-Find analysis.
- LeetCode "Graph" and "Union Find" tags; the **Graph Explorer** card on the [Tech Interview Handbook](https://www.techinterviewhandbook.org/).
- See the companion **shortest-path** and **minimum-spanning-tree** files in this section for weighted-graph algorithms (Dijkstra, Bellman-Ford, Floyd-Warshall, Kruskal, Prim).

[← Back to master index](../README.md) | [← DSA index](README.md)
