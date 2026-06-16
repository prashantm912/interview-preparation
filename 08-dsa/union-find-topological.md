# Union-Find & Topological Sort

A practical, interview-focused deep dive into two graph workhorses: **Disjoint Set Union (Union-Find)** for connectivity / grouping problems, and **Topological Sort** for ordering tasks under dependency (DAG) constraints. Both show up constantly in FAANG loops because they convert messy "who is connected / what comes first" questions into clean near-linear algorithms.

[← Back to master index](../README.md) &nbsp;|&nbsp; [← DSA index](README.md)

---

## Concept & Intuition

### Disjoint Set Union (DSU / Union-Find)

DSU maintains a collection of **disjoint sets** and answers two questions efficiently:

- `find(x)` — which set (represented by a *root* / leader) does `x` belong to?
- `union(x, y)` — merge the two sets containing `x` and `y`.

The core trick: represent each set as a tree where every node points to a parent, and the root points to itself. Two elements are in the same set **iff** they share the same root. Two optimizations make this almost free:

1. **Path compression** — during `find`, re-point every visited node directly at the root, flattening the tree.
2. **Union by rank/size** — always attach the *smaller/shorter* tree under the *larger/taller* one, so trees stay shallow.

With **both** optimizations, any sequence of *m* operations on *n* elements runs in `O(m · α(n))`, where `α` is the inverse Ackermann function — effectively a constant (`α(n) ≤ 4` for any `n` you will ever see, even `n = 2^65536`).

```
Initial (5 singletons):        After union(0,1), union(2,3), union(1,2):

 0  1  2  3  4                       0
 ^  ^  ^  ^  ^                      / \
 |  |  |  |  |                     1   2
self self ...                          \
                                        3      4 (still alone)

find(3) walks 3 -> 2 -> 0, then PATH COMPRESSION re-points 3 (and 2) straight to 0:

   0
 / | \
1  2  3        4
```

**When to use DSU:** dynamic connectivity, "are these in the same group?", counting connected components, cycle detection in **undirected** graphs, Kruskal's MST, clustering/merging entities (accounts, friend circles), and grid percolation. It shines when edges arrive **incrementally** and you only ever *merge* (DSU does not support efficient deletion/splitting).

**Invariant:** every element has exactly one parent; following parents always terminates at a unique root per set; `find` is idempotent.

### Topological Sort

A **topological order** of a Directed Acyclic Graph (DAG) is a linear ordering of vertices such that for every directed edge `u → v`, `u` appears before `v`. It exists **iff** the graph has no cycle. Use it whenever you must order things subject to "X must happen before Y" constraints: build systems, course prerequisites, task scheduling, package/dependency resolution, spreadsheet recalculation, alien alphabet inference.

Two standard algorithms:

- **Kahn's algorithm (BFS / indegree)** — repeatedly remove vertices with indegree 0. If you can't drain all vertices, a cycle exists. Naturally produces the order and detects cycles.
- **DFS post-order** — finish a node only after all its descendants finish; push to a stack; reverse the stack. Cycle detection uses a three-color (white/gray/black) marking to spot a back-edge.

```
Edges: A→C, B→C, C→D            Indegrees: A:0 B:0 C:2 D:1
                                 Kahn queue starts with {A, B}
   A     B                       pop A -> C:1 ; pop B -> C:0 -> enqueue C
    \   /                        pop C -> D:0 -> enqueue D ; pop D
     v v                         Order: A, B, C, D  (one valid topo order)
      C
      |
      v
      D
```

**Invariant (Kahn):** at any moment the queue holds exactly the nodes whose every predecessor has already been output. A valid full order is emitted iff the count of output nodes equals `V`.

---

## Complexity Cheat-Sheet

| Operation / Algorithm | Time | Space | Notes |
|---|---|---|---|
| DSU `find` (path compression + union by rank) | `O(α(n))` amortized | `O(1)` | ~constant in practice |
| DSU `union` | `O(α(n))` amortized | `O(1)` | |
| DSU build + `m` ops on `n` nodes | `O(n + m·α(n))` | `O(n)` | |
| DSU `find` (no optimizations, worst case) | `O(n)` | `O(1)` | degenerate linked list |
| Connected components via DSU | `O(V + E·α(V))` | `O(V)` | |
| Kruskal's MST (sort + DSU) | `O(E log E)` | `O(V + E)` | sort dominates |
| Topo sort — Kahn (BFS) | `O(V + E)` | `O(V + E)` | adjacency list + indegree array |
| Topo sort — DFS post-order | `O(V + E)` | `O(V + E)` | recursion/explicit stack |
| Cycle detect (undirected, DSU) | `O(V + E·α(V))` | `O(V)` | union fails ⇒ cycle |
| Cycle detect (directed, DFS colors / Kahn) | `O(V + E)` | `O(V)` | |

> `α(n)` = inverse Ackermann ≤ 4 for all practical `n`. `log* n` is an alternative bound for path compression alone.

---

## Patterns & Recognition

Reach for **Union-Find** when you see:

- "Number of connected components / provinces / islands / friend circles."
- "Are A and B connected?" asked many times, with edges added over time.
- "Add the minimum/find the edge that creates a cycle" in an **undirected** graph.
- "Merge accounts / group emails / cluster duplicates by shared attribute."
- "Minimum spanning tree" (Kruskal) or "connect all points with min cost."
- Grid problems where you union neighboring cells (percolation, number of islands II).
- A hidden equivalence relation: "equal/not-equal equations," "synonymous words," "swappable indices to make a string smallest."

Reach for **Topological Sort** when you see:

- "Order tasks/courses/builds given prerequisites."
- "Can you finish / is there a valid ordering?" → cycle detection on a **directed** graph.
- "Reconstruct an order/alphabet from pairwise constraints" (alien dictionary, recipe order).
- "Longest path in a DAG," "critical path," "course schedule with semesters/levels."
- Any dependency DAG where you process a node only after its prerequisites.

Quick discriminator: **undirected grouping ⇒ DSU. Directed ordering/dependency ⇒ topo sort.** Cycle detection works with *both* — DSU for undirected, DFS-colors/Kahn for directed.

---

## Coding Problems

A reusable, production-quality DSU appears in several solutions below:

```java
class DSU {
    int[] parent, rank;
    int count; // number of disjoint sets

    DSU(int n) {
        parent = new int[n];
        rank = new int[n];
        count = n;
        for (int i = 0; i < n; i++) parent[i] = i;
    }

    int find(int x) {              // path compression (iterative, no stack overflow)
        int root = x;
        while (root != parent[root]) root = parent[root];
        while (x != root) { int next = parent[x]; parent[x] = root; x = next; }
        return root;
    }

    boolean union(int a, int b) {  // union by rank; false if already joined
        int ra = find(a), rb = find(b);
        if (ra == rb) return false;
        if (rank[ra] < rank[rb]) { int t = ra; ra = rb; rb = t; }
        parent[rb] = ra;
        if (rank[ra] == rank[rb]) rank[ra]++;
        count--;
        return true;
    }
}
```

---

### Problem 1: Number of Connected Components in an Undirected Graph

**Statement.** Given `n` nodes labeled `0..n-1` and a list of undirected `edges`, return the number of connected components.
**Constraints.** `1 ≤ n ≤ 2000`, `0 ≤ edges.length ≤ n*(n-1)/2`, no self-loops or duplicate edges.

**Approach.**
- *Brute force:* BFS/DFS from each unvisited node, count the launches — `O(V + E)` time, `O(V + E)` space for the adjacency list.
- *Optimal (DSU):* start with `count = n`; each successful `union` merges two components, decrementing the count. The leftover `count` is the answer. Cleaner when edges stream in.

```java
class Solution {
    public int countComponents(int n, int[][] edges) {
        DSU dsu = new DSU(n);
        for (int[] e : edges) dsu.union(e[0], e[1]);
        return dsu.count;
    }
}
```

**Dry run.** `n=5`, edges `[[0,1],[1,2],[3,4]]`. Start count=5. union(0,1)→4, union(1,2)→3, union(3,4)→2. Node 0,1,2 form one set; 3,4 another. Answer **2**.

**Time:** `O(n + E·α(n))`. **Space:** `O(n)`.
**Follow-ups.** Return the size of the largest component (track a `size[]` array). Support `addEdge` queries online. Compare with BFS/DFS — DSU wins when edges arrive dynamically; BFS wins if you also need the actual component members.

---

### Problem 2: Graph Valid Tree

**Statement.** Given `n` nodes and `edges`, decide whether they form a valid tree.
**Constraints.** `1 ≤ n ≤ 2000`. A tree has exactly `n-1` edges **and** is fully connected with **no cycle**.

**Approach.** A graph is a tree iff: (1) edge count is exactly `n-1`, and (2) it is connected. With DSU, if any `union` returns `false`, an edge connects two already-joined nodes → a cycle → not a tree. After processing, exactly one component must remain.

```java
class Solution {
    public boolean validTree(int n, int[][] edges) {
        if (edges.length != n - 1) return false;   // tree has exactly n-1 edges
        DSU dsu = new DSU(n);
        for (int[] e : edges)
            if (!dsu.union(e[0], e[1])) return false; // cycle detected
        return dsu.count == 1;                      // fully connected
    }
}
```

**Dry run.** `n=5`, edges `[[0,1],[0,2],[0,3],[1,4]]`: 4 edges = n-1, all unions succeed, count→1 ⇒ **true**. With `[[0,1],[1,2],[2,3],[1,3]]` (n=4) the last union(1,3) fails ⇒ **false**.

**Time:** `O(n + E·α(n))`. **Space:** `O(n)`.
**Follow-ups.** Why is checking `edges == n-1` plus "no cycle" sufficient (it implies connectivity)? Solve with DFS while detecting back-edges. Extend to a forest of `k` trees.

---

### Problem 3: Redundant Connection

**Statement.** A tree of `n` nodes had one extra edge added, forming exactly one cycle. Given `edges` in input order, return the edge that can be removed so the result is a tree. If multiple, return the last one.
**Constraints.** `3 ≤ n ≤ 1000`, edges are `1`-indexed.

**Approach.** Process edges in order; the **first** edge whose two endpoints already share a root is the redundant one. Because we return edges in input order and the answer must be "the last such edge," processing left-to-right and returning the first `union` failure naturally yields the latest edge that closes a cycle.

```java
class Solution {
    public int[] findRedundantConnection(int[][] edges) {
        DSU dsu = new DSU(edges.length + 1); // nodes are 1..n
        for (int[] e : edges)
            if (!dsu.union(e[0], e[1])) return e;
        return new int[0];
    }
}
```

**Dry run.** `[[1,2],[1,3],[2,3]]`: union(1,2) ok, union(1,3) ok (1,2,3 joined), union(2,3) → both already root 1 → fail → return **[2,3]**.

**Time:** `O(n·α(n))`. **Space:** `O(n)`.
**Follow-ups.** *Redundant Connection II* (directed graph): the extra edge may create a node with two parents OR a cycle — handle both cases. Why does DSU not directly work for the directed variant without preprocessing?

---

### Problem 4: Course Schedule I (Can Finish)

**Statement.** `numCourses` courses labeled `0..n-1`; `prerequisites[i] = [a, b]` means you must take `b` before `a`. Return whether you can finish all courses.
**Constraints.** `1 ≤ numCourses ≤ 2000`, `0 ≤ prerequisites.length ≤ 5000`.

**Approach.** This is cycle detection on a **directed** graph; finishable ⇔ the dependency graph is a DAG. Use Kahn's algorithm: build indegrees, drain zero-indegree nodes, and check whether all `n` nodes were processed.

```java
class Solution {
    public boolean canFinish(int numCourses, int[][] prerequisites) {
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < numCourses; i++) adj.add(new ArrayList<>());
        int[] indeg = new int[numCourses];
        for (int[] p : prerequisites) { adj.get(p[1]).add(p[0]); indeg[p[0]]++; }

        Deque<Integer> q = new ArrayDeque<>();
        for (int i = 0; i < numCourses; i++) if (indeg[i] == 0) q.offer(i);

        int seen = 0;
        while (!q.isEmpty()) {
            int u = q.poll(); seen++;
            for (int v : adj.get(u)) if (--indeg[v] == 0) q.offer(v);
        }
        return seen == numCourses; // false ⇒ a cycle blocked some courses
    }
}
```

**Dry run.** `n=2`, prereq `[[1,0]]`: indeg [0,1], queue [0]; pop 0 → indeg[1]→0 → queue [1]; pop 1; seen=2 ⇒ **true**. With `[[1,0],[0,1]]` both indegrees stay ≥1, queue empty immediately, seen=0 ⇒ **false**.

**Time:** `O(V + E)`. **Space:** `O(V + E)`.
**Follow-ups.** Implement with DFS three-color cycle detection. What if prerequisites can repeat (multigraph)? Detect *which* courses are part of the cycle.

---

### Problem 5: Course Schedule II (Return an Order)

**Statement.** Same setup; return **any** valid order to finish all courses, or an empty array if impossible.
**Constraints.** As Problem 4.

**Approach.** Kahn's algorithm, but record the dequeue order. If the recorded order has fewer than `numCourses` entries, a cycle exists → return `[]`.

```java
class Solution {
    public int[] findOrder(int numCourses, int[][] prerequisites) {
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < numCourses; i++) adj.add(new ArrayList<>());
        int[] indeg = new int[numCourses];
        for (int[] p : prerequisites) { adj.get(p[1]).add(p[0]); indeg[p[0]]++; }

        Deque<Integer> q = new ArrayDeque<>();
        for (int i = 0; i < numCourses; i++) if (indeg[i] == 0) q.offer(i);

        int[] order = new int[numCourses];
        int idx = 0;
        while (!q.isEmpty()) {
            int u = q.poll();
            order[idx++] = u;
            for (int v : adj.get(u)) if (--indeg[v] == 0) q.offer(v);
        }
        return idx == numCourses ? order : new int[0];
    }
}
```

**Dry run.** `n=4`, prereq `[[1,0],[2,0],[3,1],[3,2]]`. indeg: 0:0,1:1,2:1,3:2. Start q=[0]; pop 0 → 1,2 hit 0 → q=[1,2]; pop 1 → 3→1; pop 2 → 3→0 → q=[3]; pop 3. Order **[0,1,2,3]** (also `[0,2,1,3]`).

**Time:** `O(V + E)`. **Space:** `O(V + E)`.
**Follow-ups.** Use a `PriorityQueue` for the *lexicographically smallest* order. Produce a *DFS* order. Group into parallel semesters (see Problem 9).

---

### Problem 6: Accounts Merge

**Statement.** `accounts[i] = [name, email1, email2, ...]`. Two accounts belong to the same person if they share any email (names may collide across different people). Merge accounts; return each as `[name, sorted emails...]`.
**Constraints.** `1 ≤ accounts ≤ 1000`, total emails ≤ ~10⁴.

**Approach.** Treat each account **index** as a DSU node. Map every email to the first account index that owns it; when a later account reuses that email, `union` the two account indices. Finally, gather emails by root, sort, and prepend the name.

```java
class Solution {
    public List<List<String>> accountsMerge(List<List<String>> accounts) {
        int n = accounts.size();
        DSU dsu = new DSU(n);
        Map<String, Integer> emailToId = new HashMap<>();
        for (int i = 0; i < n; i++) {
            for (int j = 1; j < accounts.get(i).size(); j++) {
                String email = accounts.get(i).get(j);
                if (emailToId.containsKey(email)) dsu.union(i, emailToId.get(email));
                else emailToId.put(email, i);
            }
        }
        Map<Integer, TreeSet<String>> merged = new HashMap<>();
        for (Map.Entry<String, Integer> e : emailToId.entrySet()) {
            int root = dsu.find(e.getValue());
            merged.computeIfAbsent(root, k -> new TreeSet<>()).add(e.getKey());
        }
        List<List<String>> res = new ArrayList<>();
        for (Map.Entry<Integer, TreeSet<String>> e : merged.entrySet()) {
            List<String> account = new ArrayList<>();
            account.add(accounts.get(e.getKey()).get(0)); // name
            account.addAll(e.getValue());                 // sorted emails
            res.add(account);
        }
        return res;
    }
}
```

**Dry run.** Accounts: `["John", a, b]`, `["John", c]`, `["John", a, d]`. Email `a` first seen at index 0; account 2 reuses `a` → union(2,0). Roots: {0,2} → {a,b,d}, {1} → {c}. Output: `["John", a, b, d]` and `["John", c]`.

**Time:** `O(N·K·α(N) + E log E)` for sorting emails (E total emails). **Space:** `O(E)`.
**Follow-ups.** Use email strings directly as DSU keys via a `Map<String,String> parent`. Handle merging on *phone numbers too*. What changes if names must be unique identifiers?

---

### Problem 7: Number of Islands II (Online)

**Statement.** An `m × n` grid starts all water. Given a stream of `positions` turning cells to land, return the number of islands **after each** addition.
**Constraints.** `1 ≤ m, n ≤ 1000`, positions up to ~10⁴.

**Approach.** Map each land cell to a 1-D id `r*n + c`. When a cell becomes land, increment the island count, then `union` it with each already-land 4-neighbor (each successful union merges and decrements). This is the canonical online-connectivity use of DSU; BFS/DFS would re-scan the whole grid per step (`O(k·mn)`).

```java
class Solution {
    public List<Integer> numIslands2(int m, int n, int[][] positions) {
        DSU dsu = new DSU(m * n);
        boolean[] land = new boolean[m * n];
        int islands = 0;
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        List<Integer> res = new ArrayList<>();
        for (int[] p : positions) {
            int r = p[0], c = p[1], id = r * n + c;
            if (land[id]) { res.add(islands); continue; } // duplicate add
            land[id] = true;
            islands++;
            for (int[] d : dirs) {
                int nr = r + d[0], nc = c + d[1], nid = nr * n + nc;
                if (nr >= 0 && nr < m && nc >= 0 && nc < n && land[nid]
                        && dsu.union(id, nid)) islands--;
            }
            res.add(islands);
        }
        return res;
    }
}
```

**Dry run.** `3×3`, positions `[[0,0],[0,1],[1,2],[2,1]]`. Add (0,0): islands=1. Add (0,1): neighbor (0,0) land → union → islands=1. Add (1,2): no land neighbor → islands=2. Add (2,1): no land neighbor → islands=3. Result **[1,1,2,3]**.

**Time:** `O(k·α(mn))` for `k` positions. **Space:** `O(mn)`.
**Follow-ups.** Handle duplicate positions (done above). 8-directional connectivity. Memory: use a `HashMap` parent for huge sparse grids instead of an `m*n` array.

---

### Problem 8: Alien Dictionary

**Statement.** A list of `words` sorted lexicographically by an unknown alien alphabet. Return any valid character order, or `""` if none exists.
**Constraints.** `1 ≤ words.length ≤ 100`, lowercase letters.

**Approach.** Compare each adjacent pair of words; the first differing character gives a directed edge `c1 → c2` (c1 before c2). Edge case: if `word1` is a strict prefix of a *shorter* following word (e.g. `"abc"` before `"ab"`), the input is invalid → return `""`. Then run a topological sort over the letters that actually appear.

```java
class Solution {
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
            int min = Math.min(a.length(), b.length()), j = 0;
            while (j < min && a.charAt(j) == b.charAt(j)) j++;
            if (j == min) {
                if (a.length() > b.length()) return ""; // "abc" before "ab" => invalid
            } else if (adj.get(a.charAt(j)).add(b.charAt(j))) {
                indeg.merge(b.charAt(j), 1, Integer::sum);
            }
        }
        Deque<Character> q = new ArrayDeque<>();
        for (var e : indeg.entrySet()) if (e.getValue() == 0) q.offer(e.getKey());
        StringBuilder sb = new StringBuilder();
        while (!q.isEmpty()) {
            char u = q.poll(); sb.append(u);
            for (char v : adj.get(u)) if (indeg.merge(v, -1, Integer::sum) == 0) q.offer(v);
        }
        return sb.length() == indeg.size() ? sb.toString() : ""; // cycle ⇒ ""
    }
}
```

**Dry run.** `["wrt","wrf","er","ett","rftt"]`. Pairs give edges: `t→f`, `w→e`, `r→t`, `e→r`. Indeg-0 start: `w`. Order resolves to `"wertf"` (one valid answer). A cycle (e.g. `["z","x","z"]`) leaves nodes unprocessed → `""`.

**Time:** `O(C)` where `C` = total characters across all words. **Space:** `O(1)` (≤26 letters and edges).
**Follow-ups.** Detect the prefix-invalidity edge case (common bug). Return the lexicographically smallest order (min-heap). Why can't DSU solve this (direction matters)?

---

### Problem 9: Course Schedule III / "Parallel Courses" — Minimum Semesters (Senior-Level)

**Statement.** Given `n` courses (`1..n`) and a directed `relations[i] = [a, b]` meaning `a` must precede `b`, in one semester you may take **any** number of courses whose prerequisites are all done. Return the **minimum number of semesters** to finish all courses, or `-1` if impossible (cycle).
**Constraints.** `1 ≤ n ≤ 5000`, `relations.length ≤ 5000`.

**Approach.** This is **level-order (layered) topological sort** — the answer equals the **longest path length** (in nodes) of the DAG. Run Kahn's algorithm one full BFS *level* at a time: every node currently at indegree 0 is taken in the same semester. The number of levels is the answer. If we finish fewer than `n` courses, a cycle exists → `-1`. This is also the template for "longest path in a DAG" and critical-path scheduling.

```java
class Solution {
    public int minimumSemesters(int n, int[][] relations) {
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i <= n; i++) adj.add(new ArrayList<>());
        int[] indeg = new int[n + 1];
        for (int[] r : relations) { adj.get(r[0]).add(r[1]); indeg[r[1]]++; }

        Deque<Integer> q = new ArrayDeque<>();
        for (int i = 1; i <= n; i++) if (indeg[i] == 0) q.offer(i);

        int semesters = 0, studied = 0;
        while (!q.isEmpty()) {
            semesters++;
            for (int sz = q.size(); sz > 0; sz--) { // drain exactly one level
                int u = q.poll(); studied++;
                for (int v : adj.get(u)) if (--indeg[v] == 0) q.offer(v);
            }
        }
        return studied == n ? semesters : -1;
    }
}
```

**Dry run.** `n=3`, relations `[[1,3],[2,3]]`. indeg: 1:0,2:0,3:2. Semester 1: take {1,2} → 3 drops to 0. Semester 2: take {3}. studied=3=n ⇒ **2**. With `[[1,2],[2,3],[3,1]]` no node ever hits indegree 0 → studied=0 ⇒ **-1**.

**Time:** `O(V + E)`. **Space:** `O(V + E)`.
**Follow-ups.** Add a per-semester cap `k` on courses (greedy/DP variant — the actual LeetCode 1494 "Parallel Courses II" is NP-hard with bitmask DP). Return the actual course list per semester. Compute the longest path with weighted course durations (DAG DP).

---

### Problem 10: Sentence Similarity II (DSU with String Keys)

**Statement.** Given `pairs` of similar words (similarity is transitive) and two sentences `s1`, `s2`, decide whether they are similar — same length, and each `s1[i]` is similar to `s2[i]`.
**Constraints.** word counts and pairs up to ~10³.

**Approach.** Transitive similarity = equivalence classes ⇒ DSU over **string keys**. Union every pair; two words are similar iff they share a root (or are identical). A string-keyed DSU using a `HashMap` is a frequently asked variation of the integer DSU.

```java
class Solution {
    Map<String, String> parent = new HashMap<>();
    String find(String x) {
        parent.putIfAbsent(x, x);
        if (!parent.get(x).equals(x)) parent.put(x, find(parent.get(x)));
        return parent.get(x);
    }
    public boolean areSentencesSimilarTwo(String[] s1, String[] s2, List<List<String>> pairs) {
        if (s1.length != s2.length) return false;
        for (List<String> p : pairs) parent.put(find(p.get(0)), find(p.get(1)));
        for (int i = 0; i < s1.length; i++)
            if (!s1[i].equals(s2[i]) && !find(s1[i]).equals(find(s2[i]))) return false;
        return true;
    }
}
```

**Dry run.** pairs `[[great, fine],[fine, good]]`, s1=`["great"]`, s2=`["good"]`. union great~fine~good → same root. `find("great")==find("good")` ⇒ **true**.

**Time:** `O((P + L)·α)` amortized. **Space:** `O(P)`.
**Follow-ups.** Why does *Sentence Similarity I* (non-transitive) NOT need DSU (just a symmetric set of pairs)? Add union by rank to the string DSU. Compare with building an adjacency graph + DFS.

---

## Interview Q&A by Level

### 🟢 Basic

- **What does Union-Find do?** Maintains disjoint sets with near-constant `find` (which set?) and `union` (merge) operations.
- **What is a "root" / representative?** The unique node in a set that points to itself; two elements are in the same set iff `find` returns the same root.
- **What is a topological sort and when does it exist?** A linear ordering of a directed graph's vertices respecting all edges `u→v`; it exists **iff** the graph is acyclic.
- **Which problems use DSU vs topo sort?** Undirected grouping/connectivity ⇒ DSU; directed ordering/dependencies ⇒ topo sort.
- **How do you count connected components with DSU?** Start `count = n`, decrement on each successful `union`.

### 🟡 Intermediate

- **Why two optimizations, not one?** Path compression flattens trees over time; union by rank/size prevents tall trees forming. Together they give `O(α(n))`; either alone is asymptotically worse (`O(log n)` amortized).
- **Union by rank vs union by size — difference?** Rank is an upper bound on tree height (cheap to maintain); size is the node count (useful when you need component sizes). Both keep trees shallow; pick size when the problem asks for largest-component metrics.
- **Kahn vs DFS topo sort — when to prefer each?** Kahn (BFS) is iterative, naturally detects cycles by a node-count check, and supports level/semester layering. DFS is concise and great when you already need DFS finishing times, but watch recursion depth on large graphs.
- **How do you detect a cycle in an undirected graph with DSU?** If `union(u, v)` finds both already share a root, that edge closes a cycle.
- **How do you detect a cycle in a directed graph?** Kahn: processed-count `< V`. DFS: a gray (in-progress) node revisited = back-edge = cycle.

### 🟠 Advanced

- **Explain the amortized α(n) bound.** Tarjan proved that with union by rank + path compression, any sequence of `m` operations on `n` elements costs `O(m·α(n))`, where `α` is the inverse Ackermann function — it grows so slowly it is ≤ 4 for any conceivable input, so operations are effectively constant-time.
- **Why can't DSU detect cycles in a directed graph directly?** DSU only models symmetric reachability (undirected connectivity); it loses edge direction, so it can't tell a tree edge from a back-edge in a digraph (hence Redundant Connection II needs extra handling).
- **Can DSU support deletion/splitting?** Not efficiently — it's a *union-only* structure. Deletion needs different tools (e.g., link-cut trees, offline processing in reverse, or Euler-tour trees).
- **How do you produce the lexicographically smallest topological order?** Replace Kahn's FIFO queue with a min-heap (`PriorityQueue`); cost becomes `O(V log V + E)`.
- **What's the relationship between topo sort and longest path in a DAG?** Process vertices in topological order and relax edges; this computes longest (or shortest) paths in `O(V+E)` — impossible in general graphs but linear on DAGs (and the basis of critical-path scheduling).

### 🔴 Expert

- **How does DSU scale to a distributed / streaming setting?** For massive graphs you shard nodes and merge partial DSUs (boundary edges reconciled via map-reduce-style "label propagation" or repeated union of cross-shard roots). Incremental connectivity over edge streams is a natural DSU fit; fully dynamic (with deletions) requires holm-de-Lichtenberg-Thorup style structures or offline reversal.
- **Real-world uses of DSU?** Kruskal's MST in network design, image segmentation / connected-component labeling, type unification in compilers (Hindley-Milner), Hoshen-Kopelman percolation in physics, friend/cluster grouping, and `git`-style merge of equivalence classes.
- **Real-world uses of topo sort?** Build systems (Make/Bazel target ordering), package managers (apt/npm dependency resolution), spreadsheet recalculation order, task schedulers, deadlock-free resource ordering, data-pipeline DAGs (Airflow), and instruction scheduling in compilers.
- **How do you parallelize execution of a DAG?** Use layered (Kahn) topo sort: all indegree-0 nodes in a layer are mutually independent and can run concurrently; the number of layers is the critical-path length and the theoretical minimum makespan with unlimited workers.
- **Persistent / rollback DSU?** "Union-Find with rollback" stores the rank/parent changes on a stack and avoids path compression (which is irreversible), enabling undo for offline divide-and-conquer on queries and dynamic-connectivity problems — `O(log n)` per op instead of `α(n)`, but reversible.

---

## ⚠️ Common Pitfalls

- **Forgetting one DSU optimization.** Path compression *without* union by rank can still degrade; include both for the `α(n)` guarantee. Recursive `find` without compression can stack-overflow on a degenerate chain.
- **Initializing `parent[i] = i` incorrectly** (e.g., all zeros) — every element must start as its own root.
- **Off-by-one with 1-indexed nodes** (Redundant Connection uses `1..n`): size the DSU array to `n+1`.
- **Recursive DFS topo sort overflowing the stack** on deep graphs — prefer iterative Kahn, or raise the stack / use an explicit stack.
- **Building edges in the wrong direction** for prerequisites: `[a, b]` "b before a" means edge `b → a`. Reversing it silently produces a wrong order.
- **Missing the prefix edge case in Alien Dictionary** (`"abc"` before `"ab"` is invalid) — a classic source of wrong-answer.
- **Confusing cycle detection methods:** DSU works for *undirected* cycles only; directed graphs need DFS-colors or Kahn's count check.
- **Not handling disconnected nodes in topo sort** — every vertex (even with no edges) must be seeded into the indegree-0 queue/output.
- **Mutating the indegree array but forgetting to seed all initial zeros**, or counting processed nodes incorrectly, leading to false cycle reports.
- **Assuming a unique topological order** — most DAGs have many valid orders; tests that compare to one specific order need a min-heap (lexicographic) variant.

---

## 📚 Further Reading

- *Introduction to Algorithms* (CLRS), Ch. 21 "Data Structures for Disjoint Sets" (proof of the α(n) bound) and Ch. 22 "Topological Sort."
- Tarjan, R. E., "Efficiency of a Good But Not Linear Set Union Algorithm" (1975) — the inverse-Ackermann analysis.
- Sedgewick & Wayne, *Algorithms, 4th ed.* — Union-Find chapter and the dynamic-connectivity case study.
- CP-Algorithms: "Disjoint Set Union" and "Topological Sorting" (with rollback / small-to-large variants).
- LeetCode lists: Union-Find (323, 261, 684, 685, 721, 305, 737, 990, 1319) and Topological Sort (207, 210, 269, 444, 1136, 2050, 310).
- Competitive Programmer's Handbook (Laaksonen) — DSU and DAG dynamic programming chapters.

[← Back to master index](../README.md) &nbsp;|&nbsp; [← DSA index](README.md)
