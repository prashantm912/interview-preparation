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
