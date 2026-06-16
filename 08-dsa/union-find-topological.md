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

## 🧩 Extended Problems — Set 1: Classic Easy → Medium

These are the standard, must-recognize interview problems on this topic. They reuse the `DSU` template shown above; where a problem needs a `size[]` array (largest-component metrics), that variant is noted inline.

---

### Problem 11: Number of Provinces — DSU connected components

**Statement.** Given an `n × n` matrix `isConnected` where `isConnected[i][j] == 1` means city `i` and city `j` are directly connected, return the number of **provinces** (a province is a maximal group of directly or indirectly connected cities).

**Constraints.** `1 ≤ n ≤ 200`, `isConnected[i][i] == 1`, the matrix is symmetric.

**Approach.** This is "count connected components" expressed as an adjacency *matrix* (the classic "friend circles" problem, LeetCode 547). Union every pair `(i, j)` with `i < j` for which `isConnected[i][j] == 1`; the remaining `count` of disjoint sets is the number of provinces. DSU is optimal here because the relation is symmetric/undirected and we only ever merge. BFS/DFS over the matrix is equally `O(n²)`, but DSU keeps a running component count for free and generalizes cleanly to streaming edges.

```java
class Solution {
    public int findCircleNum(int[][] isConnected) {
        int n = isConnected.length;
        DSU dsu = new DSU(n);
        for (int i = 0; i < n; i++)
            for (int j = i + 1; j < n; j++)
                if (isConnected[i][j] == 1) dsu.union(i, j);
        return dsu.count;
    }
}
```

**Dry run.** `[[1,1,0],[1,1,0],[0,0,1]]`: union(0,1) merges {0,1}; (2) stays alone. count 3 → 2 ⇒ **2**.

**Complexity.** Time `O(n² · α(n))` (we must scan the whole matrix), space `O(n)`. **Edge cases:** the diagonal `isConnected[i][i]` is ignored (a self-loop never changes the count); a fully disconnected matrix yields `n`; a fully connected one yields `1`.

---

### Problem 12: Find if Path Exists in Graph — DSU reachability

**Statement.** Given `n` vertices `0..n-1`, a list of bidirectional `edges`, and two vertices `source` and `destination`, return `true` if a path exists between them.

**Constraints.** `1 ≤ n ≤ 2·10⁵`, `0 ≤ edges.length ≤ 2·10⁵`, no duplicate edges, no self-loops.

**Approach.** "Are two nodes connected in an undirected graph?" is the textbook DSU query (LeetCode 1971). Union all edges, then answer in one `find`: a path exists iff `source` and `destination` share a root. With path compression + union by rank the whole thing is near-linear, beating a per-query BFS when many such queries arrive. A single self-query (`source == destination`) is trivially `true` since each node is its own root initially.

```java
class Solution {
    public boolean validPath(int n, int[][] edges, int source, int destination) {
        DSU dsu = new DSU(n);
        for (int[] e : edges) dsu.union(e[0], e[1]);
        return dsu.find(source) == dsu.find(destination);
    }
}
```

**Dry run.** `n=6`, edges `[[0,1],[0,2],[3,5],[5,4],[4,3]]`, source 0, dest 5: {0,1,2} and {3,4,5} are separate roots ⇒ **false**. source 0, dest 2 ⇒ **true**.

**Complexity.** Time `O(n + E·α(n))` to build, `O(α(n))` per query, space `O(n)`. **Edge cases:** `source == destination` returns `true`; an empty edge list means only self-paths exist; large `n` makes the iterative (non-recursive) `find` important to avoid stack overflow.

---

### Problem 13: Number of Islands — DSU on a grid

**Statement.** Given an `m × n` grid of `'1'` (land) and `'0'` (water), count the islands (4-directionally connected land regions).

**Constraints.** `1 ≤ m, n ≤ 300`.

**Approach.** Although BFS/DFS flood-fill is the usual answer, this is a great DSU drill (LeetCode 200). Initialize a DSU over `m·n` cells but count only **land** cells as initial components; then for each land cell union it with its right and down land neighbors (right+down suffices to cover every adjacency exactly once). The surviving land-component count is the answer. We track the count manually since water cells are never "components."

```
grid:  1 1 0        union (0,0)-(0,1) → one island on top-left
       0 1 0        union (0,1)-(1,1) joins the middle
       0 0 1        (2,2) is isolated → second island      => 2 islands
```

```java
class Solution {
    public int numIslands(char[][] grid) {
        int m = grid.length, n = grid[0].length;
        DSU dsu = new DSU(m * n);
        int islands = 0;
        for (int r = 0; r < m; r++)
            for (int c = 0; c < n; c++)
                if (grid[r][c] == '1') islands++;          // each land cell starts as its own island
        int[][] dirs = {{0, 1}, {1, 0}};                    // right, down
        for (int r = 0; r < m; r++)
            for (int c = 0; c < n; c++) {
                if (grid[r][c] != '1') continue;
                for (int[] d : dirs) {
                    int nr = r + d[0], nc = c + d[1];
                    if (nr < m && nc < n && grid[nr][nc] == '1'
                            && dsu.union(r * n + c, nr * n + nc)) islands--;
                }
            }
        return islands;
    }
}
```

**Dry run.** The 3×3 grid above: start islands = 4 land cells. Unions (0,0)-(0,1), (0,1)-(1,1) succeed → islands 4→3→2; (2,2) has no land neighbor → stays. Answer **2**.

**Complexity.** Time `O(m·n·α(mn))`, space `O(m·n)`. **Edge cases:** an all-water grid returns `0`; a single cell returns `0` or `1`; only right/down unions avoid double-counting and re-processing.

---

### Problem 14: Satisfiability of Equality Equations — DSU equivalence classes

**Statement.** Given an array `equations` of strings like `"a==b"` or `"a!=b"` over lowercase variables, return `true` iff you can assign integers to the variables to satisfy all of them.

**Constraints.** `1 ≤ equations.length ≤ 500`, each is 4 chars, variables are single lowercase letters.

**Approach.** Equality is an equivalence relation, so `==` constraints partition the 26 letters into DSU classes (LeetCode 990). **Process all `==` first** to build the classes, then verify every `!=`: it is satisfiable only if the two variables are in **different** classes. If any `x != y` has `find(x) == find(y)`, the constraints contradict ⇒ `false`. Ordering matters — you must union all equalities before checking inequalities.

```java
class Solution {
    public boolean equationsPossible(String[] equations) {
        DSU dsu = new DSU(26);
        for (String eq : equations)
            if (eq.charAt(1) == '=')
                dsu.union(eq.charAt(0) - 'a', eq.charAt(3) - 'a');
        for (String eq : equations)
            if (eq.charAt(1) == '!'
                    && dsu.find(eq.charAt(0) - 'a') == dsu.find(eq.charAt(3) - 'a'))
                return false;
        return true;
    }
}
```

**Dry run.** `["a==b","b!=a"]`: union(a,b); then `b!=a` finds same root ⇒ **false**. `["a==b","b==c","a==c"]` ⇒ all consistent ⇒ **true**.

**Complexity.** Time `O(E·α(26)) = O(E)`, space `O(1)` (fixed 26 nodes). **Edge cases:** self-equality `"a==a"` is harmless; a lone `"a!=a"` is immediately false (same node, same root); always do the two passes in the right order.

---

### Problem 15: Most Stones Removed with Same Row or Column — DSU on coordinates

**Statement.** On a 2-D plane there are `n` stones at integer coordinates. A stone can be removed if it shares its row **or** column with another remaining stone. Return the **maximum** number of stones that can be removed.

**Constraints.** `1 ≤ n ≤ 1000`, coordinates in `[0, 10⁴]`.

**Approach.** Two stones sharing a row or column are connected; within any connected component you can remove all but one stone (peel them off in reverse-DFS order). So the answer is `n − (number of connected components)` (LeetCode 947). The DSU trick is to union by **shared row or column** without comparing all pairs: union each stone's row with its column. To keep rows and columns in the same id space, offset columns by a constant (`col + 10001`). Each unique row-label and column-label is a node; stones glue them together.

```java
class Solution {
    public int removeStones(int[][] stones) {
        DSU dsu = new DSU(20002);                  // rows 0..10000, cols offset by 10001
        Set<Integer> seen = new HashSet<>();
        for (int[] s : stones) {
            int row = s[0], col = s[1] + 10001;
            dsu.union(row, col);
            seen.add(row);
            seen.add(col);
        }
        Set<Integer> roots = new HashSet<>();
        for (int node : seen) roots.add(dsu.find(node));
        return stones.length - roots.size();        // n - components
    }
}
```

**Dry run.** Stones `[[0,0],[0,1],[1,0],[1,1],[2,2]]`: the first four all chain together via shared rows/cols → 1 component; `[2,2]` is its own → 2 components total. Answer `5 − 2 = ` **3**.

**Complexity.** Time `O(n·α)`, space `O(n)` for the seen-set (DSU array is a fixed `20002`). **Edge cases:** a single stone yields `0` removals; counting **distinct roots over seen row/col labels** (not over `2n` raw nodes) is what gives the component count.

---

### Problem 16: Smallest String With Swaps — DSU + sort within components

**Statement.** Given a string `s` and a list of index `pairs` where you may swap the characters at `pairs[i]` any number of times, return the lexicographically smallest string achievable.

**Constraints.** `1 ≤ s.length ≤ 10⁵`, `0 ≤ pairs.length ≤ 10⁵`.

**Approach.** Swappability is transitive: if you can swap `i↔j` and `j↔k`, then `i, j, k` characters can be freely permuted. So union all pair indices into components (LeetCode 1202); within each component the characters can be arranged in any order. To minimize lexicographically, sort each component's indices and place that component's characters in sorted order into those (ascending) positions. DSU finds the components in near-linear time; the sorting dominates.

```java
class Solution {
    public String smallestStringWithSwaps(String s, List<List<Integer>> pairs) {
        int n = s.length();
        DSU dsu = new DSU(n);
        for (List<Integer> p : pairs) dsu.union(p.get(0), p.get(1));

        Map<Integer, List<Integer>> groups = new HashMap<>();
        for (int i = 0; i < n; i++)
            groups.computeIfAbsent(dsu.find(i), k -> new ArrayList<>()).add(i);

        char[] res = s.toCharArray();
        for (List<Integer> idx : groups.values()) {       // idx already ascending
            char[] chars = new char[idx.size()];
            for (int k = 0; k < idx.size(); k++) chars[k] = s.charAt(idx.get(k));
            Arrays.sort(chars);
            for (int k = 0; k < idx.size(); k++) res[idx.get(k)] = chars[k];
        }
        return new String(res);
    }
}
```

**Dry run.** `s="dcab"`, pairs `[[0,3],[1,2]]`: components `{0,3}` chars `d,b`→`b,d`; `{1,2}` chars `c,a`→`a,c`. Place: index0=b, index3=d, index1=a, index2=c ⇒ `"bacd"`.

**Complexity.** Time `O((n + P)·α + n log n)`, space `O(n)`. **Edge cases:** no pairs returns `s` unchanged; indices appended in increasing `i` order keep each group's positions sorted, so we only sort the characters.

---

### Problem 17: Min Cost to Connect All Points — Kruskal's MST with DSU

**Statement.** Given `points` on a plane, the cost to connect two points is their Manhattan distance `|x1-x2| + |y1-y2|`. Return the minimum total cost to connect **all** points (so any point is reachable from any other).

**Constraints.** `1 ≤ points.length ≤ 1000`.

**Approach.** This is a minimum spanning tree (LeetCode 1584). Generate all `O(n²)` candidate edges with their Manhattan weights, sort ascending, then greedily add an edge with DSU iff it connects two different components (Kruskal's algorithm). Stop after `n−1` edges are added. DSU makes the "would this edge form a cycle?" test `O(α(n))`, which is what makes Kruskal efficient.

```java
class Solution {
    public int minCostConnectPoints(int[][] points) {
        int n = points.length;
        List<int[]> edges = new ArrayList<>();          // {weight, i, j}
        for (int i = 0; i < n; i++)
            for (int j = i + 1; j < n; j++) {
                int w = Math.abs(points[i][0] - points[j][0])
                      + Math.abs(points[i][1] - points[j][1]);
                edges.add(new int[]{w, i, j});
            }
        edges.sort((a, b) -> Integer.compare(a[0], b[0]));

        DSU dsu = new DSU(n);
        int cost = 0, used = 0;
        for (int[] e : edges) {
            if (dsu.union(e[1], e[2])) {                  // adds edge if no cycle
                cost += e[0];
                if (++used == n - 1) break;               // MST complete
            }
        }
        return cost;
    }
}
```

**Dry run.** `points=[[0,0],[2,2],[3,10],[5,2],[7,0]]` → Kruskal picks the cheapest non-cycle edges and totals **20** (a standard LeetCode example).

**Complexity.** Time `O(n² log n)` (edge generation + sort dominates), space `O(n²)` for the edges. **Edge cases:** a single point costs `0`; for larger `n` prefer Prim's with a heap (`O(n² )` or `O(E log V)`) to avoid materializing all `n²` edges — but Kruskal+DSU is the cleanest to write.

---

### Problem 18: Earliest Moment When Everyone Becomes Friends — DSU over a time-sorted log

**Statement.** Given `n` people and `logs[i] = [timestamp, a, b]` meaning `a` and `b` become friends (friendship is transitive) at that time, return the earliest timestamp at which **everyone** is connected, or `-1` if it never happens.

**Constraints.** `1 ≤ n ≤ 100`, `1 ≤ logs.length ≤ 10⁴`.

**Approach.** Process friendship events in **chronological order** (LeetCode 1101). Start with `n` components; each successful `union` merges two groups and decrements the count. The first event after which the component count reaches `1` is the answer. If we exhaust all logs and still have more than one component, return `-1`. Sorting by timestamp is the key preprocessing step.

```java
class Solution {
    public int earliestAcq(int[][] logs, int n) {
        Arrays.sort(logs, (a, b) -> Integer.compare(a[0], b[0]));
        DSU dsu = new DSU(n);
        for (int[] log : logs) {
            dsu.union(log[1], log[2]);
            if (dsu.count == 1) return log[0];            // all connected now
        }
        return -1;
    }
}
```

**Dry run.** `n=4`, logs sorted by time: as merges happen count drops 4→3→2→1; the timestamp on the merge that hits count==1 is returned. If two people never link, count stays ≥2 ⇒ **-1**.

**Complexity.** Time `O(L log L + L·α(n))` (sort dominates), space `O(n)`. **Edge cases:** `n == 1` is already fully connected at the earliest log (or arguably time 0 — clarify with the interviewer); duplicate/self friendships are harmless no-op unions.

---

### Problem 19: Minimum Height Trees — topological "peeling" of leaves

**Statement.** A tree of `n` nodes is given as `edges`. Choosing different roots yields trees of different heights. Return the list of all root labels that minimize the tree height (the **centroid(s)**, at most two).

**Constraints.** `1 ≤ n ≤ 2·10⁴`, the input is a valid tree (`n−1` edges, connected, acyclic).

**Approach.** A topological-sort-style **leaf trimming** (LeetCode 310). Repeatedly remove all current leaves (degree-1 nodes) layer by layer — exactly like Kahn's algorithm but on an undirected tree using a degree array. The last 1 or 2 surviving nodes are the centroids and thus the minimum-height-tree roots. This works because peeling leaves moves inward toward the center; any tree's center is 1 node (odd diameter path) or 2 adjacent nodes (even).

```
          0                  Layer 1 removes leaves {3,4,5} → {1,2} remain as new leaves
        / | \                Layer 2 removes {1,2} (with 0)? No: trim leaves of {1,2,0}
       1  2  3               After trimming 3,4,5: degrees of 1,2 drop to 1 → next leaves
      / \                    Final center → [1] (this example) ; answer = centroid(s)
     4   5
```

```java
class Solution {
    public List<Integer> findMinHeightTrees(int n, int[][] edges) {
        if (n == 1) return List.of(0);
        List<Set<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) adj.add(new HashSet<>());
        for (int[] e : edges) { adj.get(e[0]).add(e[1]); adj.get(e[1]).add(e[0]); }

        List<Integer> leaves = new ArrayList<>();
        for (int i = 0; i < n; i++) if (adj.get(i).size() == 1) leaves.add(i);

        int remaining = n;
        while (remaining > 2) {
            remaining -= leaves.size();
            List<Integer> next = new ArrayList<>();
            for (int leaf : leaves)
                for (int nb : adj.get(leaf)) {
                    adj.get(nb).remove(leaf);
                    if (adj.get(nb).size() == 1) next.add(nb);
                }
            leaves = next;
        }
        return leaves;                                    // 1 or 2 centroids
    }
}
```

**Dry run.** `n=4`, edges `[[1,0],[1,2],[1,3]]`: leaves {0,2,3}, trim them → only node 1 left ⇒ **[1]**. `n=6`, edges forming a path-ish tree can leave two centroids, e.g. **[3,4]**.

**Complexity.** Time `O(n)` (each edge handled O(1) times), space `O(n)`. **Edge cases:** `n == 1` returns `[0]`; `n == 2` returns both nodes (loop body skipped); the answer always has size 1 or 2.

---

### Problem 20: Find Eventual Safe States — topological sort on reverse graph

**Statement.** A directed graph is given as `graph[i]` = list of nodes reachable from `i`. A node is **terminal** if it has no outgoing edges, and **safe** if every path starting there eventually reaches a terminal node (i.e., it can never get stuck in a cycle). Return all safe nodes in ascending order.

**Constraints.** `1 ≤ n ≤ 10⁴`, total edges ≤ 4·10⁴.

**Approach.** A node is safe iff it is **not part of, and cannot reach, any cycle**. Reverse the edges and run Kahn's algorithm: terminal nodes have outdegree 0 (indegree 0 in the reversed graph). Repeatedly peel nodes whose all successors are already known safe — in the reversed graph this is exactly draining indegree-0 vertices (LeetCode 802). Every node that gets processed is safe; nodes stuck in/leading to a cycle are never reached.

```java
class Solution {
    public List<Integer> eventualSafeNodes(int[][] graph) {
        int n = graph.length;
        List<List<Integer>> rev = new ArrayList<>();
        for (int i = 0; i < n; i++) rev.add(new ArrayList<>());
        int[] outdeg = new int[n];
        for (int u = 0; u < n; u++) {
            outdeg[u] = graph[u].length;
            for (int v : graph[u]) rev.get(v).add(u);     // reverse edge v -> u
        }
        Deque<Integer> q = new ArrayDeque<>();
        boolean[] safe = new boolean[n];
        for (int i = 0; i < n; i++) if (outdeg[i] == 0) q.offer(i);
        while (!q.isEmpty()) {
            int v = q.poll();
            safe[v] = true;
            for (int u : rev.get(v)) if (--outdeg[u] == 0) q.offer(u);
        }
        List<Integer> res = new ArrayList<>();
        for (int i = 0; i < n; i++) if (safe[i]) res.add(i);
        return res;                                        // ascending by construction
    }
}
```

**Dry run.** `graph=[[1,2],[2,3],[5],[0],[5],[],[]]`: nodes 5,6 are terminal → safe; 2→5 and 4→5 become safe; 0,1,3 form a cycle (0→1→3→0) → unsafe. Answer **[2,4,5,6]**.

**Complexity.** Time `O(V + E)`, space `O(V + E)`. **Edge cases:** a terminal node is trivially safe; a self-loop makes that node and everything that can only route through it unsafe; output is naturally sorted since we scan `0..n-1`.

---

### Problem 21: Sequence Reconstruction — unique topological order check

**Statement.** Given a permutation `nums` of `1..n` and a list of `sequences` (subsequences of some permutation), determine whether `nums` is the **only** sequence that can be reconstructed from `sequences`. Reconstruction means `nums` is the unique shortest supersequence consistent with all the ordering constraints.

**Constraints.** `1 ≤ n ≤ 10⁴`, total sequence length up to ~10⁵.

**Approach.** Build a DAG from consecutive pairs inside each sequence, then run Kahn's algorithm (LeetCode 444). `nums` is the unique topological order iff at **every** step of Kahn's BFS the queue holds **exactly one** node (no choice of next element) **and** that node equals the corresponding element of `nums`. If the queue ever has 0 nodes (cycle / missing constraint) or more than 1 (ambiguous order), the answer is `false`. Also every value `1..n` must appear in the sequences.

```java
class Solution {
    public boolean sequenceReconstruction(int[] nums, List<List<Integer>> sequences) {
        int n = nums.length;
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i <= n; i++) adj.add(new ArrayList<>());
        int[] indeg = new int[n + 1];
        Arrays.fill(indeg, -1);                            // -1 = value unseen
        for (List<Integer> seq : sequences) {
            for (int i = 0; i < seq.size(); i++) {
                int cur = seq.get(i);
                if (cur < 1 || cur > n) return false;      // out of range
                if (indeg[cur] == -1) indeg[cur] = 0;
                if (i > 0) { adj.get(seq.get(i - 1)).add(cur); indeg[cur]++; }
            }
        }
        Deque<Integer> q = new ArrayDeque<>();
        for (int v = 1; v <= n; v++) {
            if (indeg[v] == -1) return false;              // value never seen
            if (indeg[v] == 0) q.offer(v);
        }
        int idx = 0;
        while (!q.isEmpty()) {
            if (q.size() > 1) return false;                // ambiguous: more than one choice
            int u = q.poll();
            if (u != nums[idx++]) return false;            // diverges from nums
            for (int v : adj.get(u)) if (--indeg[v] == 0) q.offer(v);
        }
        return idx == n;                                   // all placed, uniquely
    }
}
```

**Dry run.** `nums=[1,2,3]`, sequences `[[1,2],[1,3],[2,3]]`: edges 1→2,1→3,2→3. Queue {1}→1, then {2}→2, then {3}→3, each size 1 and matching ⇒ **true**. With only `[[1,2],[1,3]]`, after 1 the queue is {2,3} (size 2, ambiguous) ⇒ **false**.

**Complexity.** Time `O(V + E)`, space `O(V + E)`. **Edge cases:** a value in `nums` missing from sequences ⇒ `false`; an out-of-range value ⇒ `false`; a cycle leaves `idx < n` ⇒ `false`.

---

### Problem 22: Connecting Cities With Minimum Cost — Kruskal MST with feasibility

**Statement.** Given `n` cities labeled `1..n` and `connections[i] = [city1, city2, cost]` (bidirectional), return the minimum cost to connect **all** cities, or `-1` if it is impossible to connect them all.

**Constraints.** `1 ≤ n ≤ 10⁴`, `1 ≤ connections.length ≤ 10⁴`.

**Approach.** Identical to Min Cost to Connect All Points but edges are given (LeetCode 1135). Sort connections by cost, add each with DSU iff it joins two different components (Kruskal), accumulating cost. After processing, if exactly one component remains (`dsu.count == 1`) the MST spans all cities; otherwise the graph was disconnected ⇒ `-1`. The DSU feasibility check (`count == 1`) is what distinguishes this from the always-connected point variant.

```java
class Solution {
    public int minimumCost(int n, int[][] connections) {
        Arrays.sort(connections, (a, b) -> Integer.compare(a[2], b[2]));
        DSU dsu = new DSU(n + 1);                          // cities are 1-indexed; node 0 unused
        int cost = 0;
        for (int[] c : connections)
            if (dsu.union(c[0], c[1])) cost += c[2];        // take edge if it avoids a cycle
        // count includes unused node 0, so a fully connected graph has count == 2
        return dsu.count == 2 ? cost : -1;
    }
}
```

**Dry run.** `n=3`, connections `[[1,2,5],[1,3,6],[2,3,1]]`: sorted by cost → take (2,3,1) then (1,2,5); cities 1,2,3 joined → `count` (including node 0) is 2 ⇒ cost **6**. If a city is isolated, `count > 2` ⇒ **-1**.

**Complexity.** Time `O(E log E)` (sort dominates), space `O(n + E)`. **Edge cases:** the `n+1` sizing leaves node 0 as a lone set, so "fully connected" means `count == 2`, not `1` — a classic 1-indexing pitfall; `n == 1` is trivially connected with cost `0`.

---

### Problem 23: Largest Component Size by Common Factor — DSU over prime factors

**Statement.** Given an array `nums` of distinct positive integers, two numbers are connected if they share a common factor greater than 1. Return the size of the largest connected component.

**Constraints.** `1 ≤ nums.length ≤ 2·10⁴`, `1 ≤ nums[i] ≤ 10⁵`.

**Approach.** Comparing every pair is `O(n²)`. Instead, union each number with each of its **prime factors** (LeetCode 952): if two numbers share a prime, they end up in the same set transitively. Use one DSU node per *value index* and per *prime*; map primes to ids. To get the largest component you need a `size[]` array, so this variant augments the base DSU. After unioning, count how many `nums` map to each root via `find` and take the max.

```java
class Solution {
    public int largestComponentSize(int[] nums) {
        int n = nums.length;
        int MAX = 100001;
        DSU dsu = new DSU(n + MAX);                         // 0..n-1 = value indices; n..n+MAX-1 = primes
        for (int i = 0; i < n; i++) {
            int x = nums[i];
            for (int p = 2; (long) p * p <= x; p++) {
                if (x % p == 0) {
                    dsu.union(i, n + p);                    // value i shares prime p
                    while (x % p == 0) x /= p;
                }
            }
            if (x > 1) dsu.union(i, n + x);                 // remaining large prime
        }
        Map<Integer, Integer> freq = new HashMap<>();
        int best = 0;
        for (int i = 0; i < n; i++) {
            int root = dsu.find(i);
            int c = freq.merge(root, 1, Integer::sum);
            best = Math.max(best, c);
        }
        return best;
    }
}
```

**Dry run.** `nums=[4,6,15,35]`: 4→{2}, 6→{2,3}, 15→{3,5}, 35→{5,7}. Via shared primes 4-6 (prime 2), 6-15 (prime 3), 15-35 (prime 5) all chain → one component of size **4**.

**Complexity.** Time `O(n · √maxVal · α)`, space `O(n + maxVal)`. **Edge cases:** `nums = [1]` has no prime factor → component size `1` (the index never unions, its root is itself); we count by *value index*, never by prime nodes; counting frequency over `find(i)` (not raw parent) is essential after compression.

---

## 🧩 Extended Problems — Set 2: Medium → Hard Variations & Follow-ups

These are harder variants and common follow-ups of the standard problems above. Several show the brute-force → optimal progression, and a few augment the base `DSU` with a `size[]` array or a weighted/parity twist. Where a problem needs extra state beyond the base template, the augmentation is shown inline.

---

### Problem 24: Redundant Connection II — directed graph, two failure modes

**Statement.** A rooted tree (`1..n`) had exactly one extra directed edge added. The result is a graph where every node has at most one parent **except** possibly one, and it may or may not contain a cycle. Given `edges` in input order, return the edge that, if removed, restores a valid rooted tree. If multiple answers, return the last one in input order.

**Constraints.** `3 ≤ n ≤ 1000`, edges are directed `[u, v]` (u is parent of v), `1`-indexed.

**Approach.** Plain DSU (Problem 3) does not work directly because direction matters: the extra edge can create **(A)** a node with two parents, **(B)** a cycle, or **(A and B) both**. The trick is to detect a two-parent node first. Scan edges; if some node `v` already has a parent, record the two conflicting edges `cand1` (earlier) and `cand2` (later) and *skip* `cand2` during DSU. Then run union over the remaining edges:
- If no two-parent node existed, the union that fails closes a cycle → return that edge (pure case B).
- If a two-parent node existed and the DSU runs cycle-free, the skipped `cand2` is the culprit → return `cand2`.
- If a two-parent node existed **and** a cycle still forms, the offending edge is `cand1` (its removal breaks both).

```
case A (two parents):   case B (cycle):        case A+B:
  1                       1 → 2                  2 → 1
  ↓ ↘                       ↑   ↓                ↑   ↓
  2   3                     ←── 3                3 ← ─┘
  ↑___/  (3 has parents     return the edge      remove cand1 (the
  edge that makes 2 parents) that closes cycle    parent edge in the cycle)
```

```java
class Solution {
    public int[] findRedundantDirectedConnection(int[][] edges) {
        int n = edges.length;
        int[] parent = new int[n + 1];           // parent[v] = the edge index that set v's parent
        int[] cand1 = null, cand2 = null;        // two edges pointing into the same node
        for (int[] e : edges) {
            int v = e[1];
            if (parent[v] != 0) {                // v already has a parent → two-parent conflict
                cand1 = edges[parent[v] - 1];
                cand2 = e;
            } else {
                parent[v] = indexOf(edges, e) + 1; // store 1-based edge index
            }
        }
        DSU dsu = new DSU(n + 1);
        for (int[] e : edges) {
            if (cand2 != null && e == cand2) continue;  // skip the later conflicting edge
            if (!dsu.union(e[0], e[1])) {               // cycle formed
                return cand1 == null ? e : cand1;       // pure cycle vs. A+B
            }
        }
        return cand2;                                   // no cycle → the skipped edge is redundant
    }
    private int indexOf(int[][] edges, int[] target) {
        for (int i = 0; i < edges.length; i++) if (edges[i] == target) return i;
        return -1;
    }
}
```

**Dry run.** `[[1,2],[1,3],[2,3]]`: node 3 gets two parents (edges `[1,3]` and `[2,3]`) → cand1=`[1,3]`, cand2=`[2,3]`; skip cand2; union(1,2),(1,3) succeed, no cycle ⇒ return cand2 **[2,3]**. `[[1,2],[2,3],[3,1]]`: no two-parent node, union(1,2),(2,3) ok, union(3,1) fails (cycle), cand1==null ⇒ return **[3,1]**.

**Complexity.** Time `O(n·α(n))` (the `indexOf` helper can be removed by storing the index directly, making it strictly `O(n·α(n))`), space `O(n)`. **Edge cases:** the three cases A / B / A+B must each be tested; returning `cand1` vs `cand2` is the crux; replace `indexOf` with a direct index map for production to keep it linear.

---

### Problem 25: Number of Operations to Make Network Connected — DSU with spare-edge counting

**Statement.** There are `n` computers (`0..n-1`) connected by `connections[i] = [a, b]` cables. You may unplug any cable and replug it elsewhere. Return the minimum number of operations to make every computer connected, or `-1` if impossible.

**Constraints.** `1 ≤ n ≤ 10⁵`, `1 ≤ connections.length ≤ min(n*(n-1)/2, 10⁵)`.

**Approach.** To connect `c` components you need at least `c-1` extra cables. A cable is "spare" (redundant) when its two endpoints are already in the same component — exactly a failed `union`. So count redundant edges while unioning. After processing, if components `= count`, you need `count - 1` moves; you can supply them iff `redundant ≥ count - 1`. A necessary global check: if total edges `< n - 1`, it is outright impossible → `-1`. DSU gives both the component count and the spare count in one pass.

```java
class Solution {
    public int makeConnected(int n, int[][] connections) {
        if (connections.length < n - 1) return -1;       // not enough cables, ever
        DSU dsu = new DSU(n);
        for (int[] c : connections)
            dsu.union(c[0], c[1]);                        // redundant ones simply don't reduce count
        return dsu.count - 1;                             // moves = components - 1
    }
}
```

**Dry run.** `n=4`, connections `[[0,1],[0,2],[1,2]]`: edges=3 ≥ n-1=3 ok. union(0,1),(0,2) merge {0,1,2}; (1,2) redundant. count = 2 (set {0,1,2} and singleton {3}) ⇒ answer `2 - 1 =` **1**.

**Complexity.** Time `O(n + E·α(n))`, space `O(n)`. **Edge cases:** the early `< n-1` test is the only `-1` path — if you have at least `n-1` edges, the redundant ones are *always* enough to bridge the components; a fully connected graph returns `0`.

---

### Problem 26: Evaluate Division — weighted Union-Find (ratios on edges)

**Statement.** Given `equations` like `["a","b"]` with `values[i]` meaning `a / b = values[i]`, answer a list of `queries` `["x","y"]` returning `x / y`, or `-1.0` if undeterminable.

**Constraints.** `1 ≤ equations.length, queries.length ≤ 100`, values are positive.

**Approach.** This is **weighted DSU**: each node stores a multiplicative weight to its parent representing `node / parent`. `find` does path compression *and* multiplies weights along the path so every node points to the root with the cumulative ratio `node / root`. To union `a/b = v`, attach `root(a)` under `root(b)` with the weight that keeps ratios consistent: `weight[rootA] = v * weight[b] / weight[a]`. A query `x/y` is answerable iff `x, y` share a root, returning `weight[x] / weight[y]`. (The DFS/BFS alternative builds a graph and multiplies edge weights along a path — `O(Q·(V+E))`; weighted DSU is near-constant per query.)

```java
class Solution {
    Map<String, String> parent = new HashMap<>();
    Map<String, Double> weight = new HashMap<>();          // weight[x] = x / parent[x]

    public double[] calcEquation(List<List<String>> equations, double[] values,
                                 List<List<String>> queries) {
        for (int i = 0; i < equations.size(); i++) {
            String a = equations.get(i).get(0), b = equations.get(i).get(1);
            add(a); add(b);
            String ra = find(a), rb = find(b);
            if (!ra.equals(rb)) {
                parent.put(ra, rb);                        // attach ra under rb
                weight.put(ra, values[i] * weight.get(b) / weight.get(a));
            }
        }
        double[] res = new double[queries.size()];
        for (int i = 0; i < queries.size(); i++) {
            String x = queries.get(i).get(0), y = queries.get(i).get(1);
            if (!parent.containsKey(x) || !parent.containsKey(y) || !find(x).equals(find(y)))
                res[i] = -1.0;
            else
                res[i] = weight.get(x) / weight.get(y);
        }
        return res;
    }
    private void add(String x) {
        if (!parent.containsKey(x)) { parent.put(x, x); weight.put(x, 1.0); }
    }
    private String find(String x) {
        if (!parent.get(x).equals(x)) {
            String root = find(parent.get(x));
            weight.put(x, weight.get(x) * weight.get(parent.get(x))); // compress + accumulate
            parent.put(x, root);
        }
        return parent.get(x);
    }
}
```

**Dry run.** `a/b=2, b/c=3`. After unions: weight encodes `a/c = 6`. Query `a/c` → same root, `weight[a]/weight[c] = 6/1 =` **6.0**. Query `x/x` for unknown `x` → `-1.0`; for known `x` → `1.0`. `a/e` with `e` absent → **-1.0**.

**Complexity.** Time `O((E + Q)·α)` amortized, space `O(V)`. **Edge cases:** a variable appearing in no equation yields `-1.0`; `x/x` for a *known* variable is `1.0`; never overwrite weights without going through a root; watch for floating error (these problems tolerate `1e-5`).

---

### Problem 27: Lexicographically Smallest Equivalent String — DSU with min-root tie-break

**Statement.** Given `s1`, `s2` of equal length (equivalence: `s1[i] ≡ s2[i]`, transitive/symmetric/reflexive) and a string `baseStr`, replace each character of `baseStr` with the **smallest** character in its equivalence class.

**Constraints.** `1 ≤ s1.length = s2.length ≤ 1000`, `1 ≤ baseStr.length ≤ 1000`, lowercase letters.

**Approach.** Equivalence classes over 26 letters ⇒ DSU. The twist is the representative must be the **lexicographically smallest** letter in the class, so override union to always make the *smaller* letter the root (ignore rank). Then map each `baseStr` char to `(char)('a' + find(c - 'a'))`. This "union by minimum label" pattern appears whenever the representative must be a canonical minimum, not an arbitrary root.

```java
class Solution {
    int[] parent = new int[26];
    int find(int x) {                                   // path compression
        while (x != parent[x]) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
    void union(int a, int b) {                          // smaller label becomes the root
        int ra = find(a), rb = find(b);
        if (ra == rb) return;
        if (ra < rb) parent[rb] = ra; else parent[ra] = rb;
    }
    public String smallestEquivalentString(String s1, String s2, String baseStr) {
        for (int i = 0; i < 26; i++) parent[i] = i;
        for (int i = 0; i < s1.length(); i++) union(s1.charAt(i) - 'a', s2.charAt(i) - 'a');
        StringBuilder sb = new StringBuilder();
        for (char c : baseStr.toCharArray()) sb.append((char) ('a' + find(c - 'a')));
        return sb.toString();
    }
}
```

**Dry run.** `s1="parker"`, `s2="morris"`, baseStr=`"parser"`. Classes: `{p,m,...}`, `{a,o}`, `{k,r,i,s}`, `{e}`. Smallest reps map `p→m`, `a→a`, `r→k`... yielding `"makkek"`.

**Complexity.** Time `O((n + m)·α(26)) = O(n + m)`, space `O(1)` (26 nodes). **Edge cases:** must drop union-by-rank — keeping smaller-as-root is what guarantees the minimum; reflexive `s1[i]==s2[i]` is a harmless self-union; classes never merge across distinct minima incorrectly because we always keep the global min as root.

---

### Problem 28: Parallel Courses II (semester cap k) — bitmask DP, why DSU/topo alone fail

**Statement.** `n` courses (`1..n`), prerequisites `relations[i] = [a, b]` (a before b), and you may take **at most `k`** courses per semester (only if all their prereqs are done). Return the minimum number of semesters to finish all courses. (Assume the input is a DAG.)

**Constraints.** `1 ≤ n ≤ 15`, `0 ≤ relations.length ≤ n*(n-1)/2`, `1 ≤ k ≤ n`.

**Approach.** Greedy "take the `k` lowest-indegree courses" (the natural extension of Problem 9's layered topo sort) is **wrong** — choosing *which* `k` of the available courses to take changes future availability, and the optimal choice is not local. Because `n ≤ 15`, encode the set of completed courses as a bitmask and BFS/DP over `2^n` states. Precompute each course's prerequisite mask. From a state, the "available" set is courses not yet taken whose prereq mask is already a subset of taken. If available has `> k` courses, enumerate all `k`-subsets (via submask enumeration) to take this semester; otherwise take all. The answer is the fewest steps to reach the full mask. Topological sort still matters: it defines `prereq[]` and guarantees the DAG terminates.

```
n=4, relations 1→2,1→3,1→4, k=2
state 0000 (nothing done): only course 1 available → take {1}  → 0001  (sem 1)
state 0001: courses 2,3,4 available (3 > k=2) → must pick 2 of them
  → e.g. {2,3} then {4}  → 3 semesters total (1, then 2, then 1)
```

```java
class Solution {
    public int minNumberOfSemesters(int n, int[][] relations, int k) {
        int[] prereq = new int[n];
        for (int[] r : relations) prereq[r[1] - 1] |= (1 << (r[0] - 1));
        int full = (1 << n) - 1;
        int[] dp = new int[1 << n];
        Arrays.fill(dp, Integer.MAX_VALUE);
        dp[0] = 0;
        for (int taken = 0; taken <= full; taken++) {
            if (dp[taken] == Integer.MAX_VALUE) continue;
            int available = 0;                              // courses ready to take now
            for (int c = 0; c < n; c++)
                if ((taken & (1 << c)) == 0 && (taken & prereq[c]) == prereq[c])
                    available |= (1 << c);
            if (Integer.bitCount(available) <= k) {         // take everything available
                int next = taken | available;
                dp[next] = Math.min(dp[next], dp[taken] + 1);
            } else {                                        // enumerate k-subsets of available
                for (int sub = available; sub > 0; sub = (sub - 1) & available) {
                    if (Integer.bitCount(sub) == k) {
                        int next = taken | sub;
                        dp[next] = Math.min(dp[next], dp[taken] + 1);
                    }
                }
            }
        }
        return dp[full];
    }
}
```

**Dry run.** `n=4`, relations `[[2,1],[3,1],[1,4]]`, `k=2` (LeetCode 1494): optimal is **3** semesters (take {2,3}, then {1}, then {4}). The greedy-by-count heuristic can give 4.

**Complexity.** Time `O(3^n · n)` worst case (submask enumeration over `2^n` states), space `O(2^n)`. **Edge cases:** `n ≤ 15` is what makes the exponential feasible; when `available ≤ k` you must take *all* (taking fewer is never better); a node with no prereqs has `prereq=0` and is available from the empty state.

---

### Problem 29: Course Schedule IV — reachability closure (transitive closure on a DAG)

**Statement.** `numCourses` courses, `prerequisites[i] = [a, b]` (a before b), and `queries[i] = [u, v]`. For each query answer whether `u` is a prerequisite of `v` (directly or transitively).

**Constraints.** `2 ≤ numCourses ≤ 100`, prerequisites up to `n*(n-1)/2`, queries up to `10⁴`.

**Approach.** Per-query BFS is `O(Q·(V+E))`. Better: precompute the **transitive closure** `reach[u][v]`. Two clean ways:
- *Floyd-Warshall style closure:* `reach[i][j] |= reach[i][k] && reach[k][j]` over all `k` — `O(V³)`, trivial to code for `V ≤ 100`.
- *Topological propagation (shown):* process nodes in topo order; a node inherits the prerequisite sets of all its direct predecessors. This is `O(V·E)` and ties directly to the topo-sort theme — when you pop a node in Kahn's order, every prerequisite of it is already finalized.

```java
class Solution {
    public List<Boolean> checkIfPrerequisite(int n, int[][] prerequisites, int[][] queries) {
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
        int[] indeg = new int[n];
        boolean[][] reach = new boolean[n][n];               // reach[a][b] = a is prereq of b
        for (int[] p : prerequisites) { adj.get(p[0]).add(p[1]); indeg[p[1]]++; }

        Deque<Integer> q = new ArrayDeque<>();
        for (int i = 0; i < n; i++) if (indeg[i] == 0) q.offer(i);
        while (!q.isEmpty()) {
            int u = q.poll();
            for (int v : adj.get(u)) {
                reach[u][v] = true;                          // direct edge
                for (int i = 0; i < n; i++)
                    if (reach[i][u]) reach[i][v] = true;     // transitive: i→u→v
                if (--indeg[v] == 0) q.offer(v);
            }
        }
        List<Boolean> res = new ArrayList<>();
        for (int[] qr : queries) res.add(reach[qr[0]][qr[1]]);
        return res;
    }
}
```

**Dry run.** `n=3`, prereq `[[0,1],[1,2]]`. Topo pops 0→sets reach[0][1]; pops 1→sets reach[1][2] and propagates reach[0][2]=true. Query `[0,2]` ⇒ **true**, `[2,0]` ⇒ **false**.

**Complexity.** Time `O(V·E + Q)`, space `O(V²)`. **Edge cases:** a node is not its own prerequisite (`reach[i][i]` stays false unless a self-loop exists, which a DAG forbids); answering queries is `O(1)` after the closure; for dense graphs the `O(V³)` Floyd variant is simpler and equally fast at `V=100`.

---

### Problem 30: Largest Component Size — DSU augmented with size[] (largest-set query)

**Statement.** Given `n` nodes and undirected `edges`, support building the graph and returning the size of the **largest** connected component. (A frequent follow-up to Problem 1: "now return the biggest group, online.")

**Constraints.** `1 ≤ n ≤ 10⁵`, `edges` up to `10⁵`.

**Approach.** Augment the base DSU with a `size[]` array initialized to all `1`; on each `union`, the merged root's size becomes the sum of both. Track a running `max` updated after every union so the largest-component query is `O(1)`. This **union by size** variant (instead of rank) is the standard answer when the problem asks for component magnitudes; it also keeps trees shallow because the smaller tree is always hung under the larger.

```java
class SizedDSU {
    int[] parent, size;
    int max = 1;
    SizedDSU(int n) {
        parent = new int[n]; size = new int[n];
        for (int i = 0; i < n; i++) { parent[i] = i; size[i] = 1; }
    }
    int find(int x) {
        while (x != parent[x]) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
    void union(int a, int b) {
        int ra = find(a), rb = find(b);
        if (ra == rb) return;
        if (size[ra] < size[rb]) { int t = ra; ra = rb; rb = t; } // union by size
        parent[rb] = ra;
        size[ra] += size[rb];
        max = Math.max(max, size[ra]);
    }
}

class Solution {
    public int largestComponent(int n, int[][] edges) {
        SizedDSU dsu = new SizedDSU(n);
        for (int[] e : edges) dsu.union(e[0], e[1]);
        return n == 0 ? 0 : dsu.max;
    }
}
```

**Dry run.** `n=6`, edges `[[0,1],[1,2],[3,4]]`: {0,1,2} size 3, {3,4} size 2, {5} size 1. Running max ends at **3**.

**Complexity.** Time `O(n + E·α(n))`, space `O(n)`. **Edge cases:** with no edges every node is its own component of size 1 (max=1); `n==0` returns 0; tracking `max` incrementally avoids a final `O(n)` scan and supports online edge additions.

---

### Problem 31: Accounts Merge with a String-Keyed DSU — the email-as-node variant

**Statement.** Same as Problem 6 (merge accounts sharing any email), but implement it with **emails themselves** as DSU keys rather than account indices, and additionally map each email's root to its owner's name.

**Constraints.** `1 ≤ accounts ≤ 1000`, total emails ≤ ~10⁴.

**Approach.** Instead of unioning account indices, union all emails *within* one account to that account's first email, so a connected email graph forms across accounts that share any address. Keep a `emailToName` map. After unioning, bucket emails by root, sort each bucket, and prepend the owner name. This is the more general string-DSU phrasing interviewers ask as a follow-up; it removes the indirection of "account index" and handles emails that appear in many accounts uniformly.

```java
class Solution {
    Map<String, String> parent = new HashMap<>();
    String find(String x) {
        parent.putIfAbsent(x, x);
        String r = x;
        while (!parent.get(r).equals(r)) r = parent.get(r);
        while (!parent.get(x).equals(r)) { String nx = parent.get(x); parent.put(x, r); x = nx; }
        return r;
    }
    void union(String a, String b) { parent.put(find(a), find(b)); }

    public List<List<String>> accountsMerge(List<List<String>> accounts) {
        Map<String, String> emailToName = new HashMap<>();
        for (List<String> acc : accounts) {
            String name = acc.get(0), first = acc.get(1);
            for (int j = 1; j < acc.size(); j++) {
                String email = acc.get(j);
                emailToName.put(email, name);
                find(email);                       // ensure present
                union(first, email);               // glue all emails in this account
            }
        }
        Map<String, TreeSet<String>> groups = new HashMap<>();
        for (String email : emailToName.keySet())
            groups.computeIfAbsent(find(email), k -> new TreeSet<>()).add(email);

        List<List<String>> res = new ArrayList<>();
        for (Map.Entry<String, TreeSet<String>> e : groups.entrySet()) {
            List<String> row = new ArrayList<>();
            row.add(emailToName.get(e.getKey()));  // root email's owner
            row.addAll(e.getValue());
            res.add(row);
        }
        return res;
    }
}
```

**Dry run.** `["John", a, b]`, `["John", c]`, `["John", a, d]`. Account 1 unions a-b; account 3 unions a-d, gluing it to account 1's set via shared `a`. Roots: {a,b,d} owner John, {c} owner John ⇒ two rows `[John,a,b,d]` and `[John,c]`.

**Complexity.** Time `O(E·α + E log E)` for sorting `E` emails, space `O(E)`. **Edge cases:** an email present in only one account still forms a singleton class with that account's other emails; the owner name is recovered from the *root email*, which is safe because all emails in a class share one person; `TreeSet` gives the required sorted output for free.

---

### Problem 32: Synonymous Sentences — DSU classes + backtracking enumeration

**Statement.** Given a list of `synonyms` pairs (transitive) and a `text`, return **all** sentences formable by replacing words with any synonym, in lexicographic order.

**Constraints.** `0 ≤ synonyms.length ≤ 10`, `1 ≤ text words ≤ 10`.

**Approach.** Synonymy is an equivalence relation → string-keyed DSU groups synonyms into classes. For each word in `text`, if it belongs to a class, its replacement options are the **sorted** members of that class (else just itself). Then backtrack across word positions, taking the Cartesian product. Sorting each class and iterating positions left-to-right yields lexicographic output directly. DSU is the clean way to get transitive synonym groups; the enumeration is a standard DFS over choices.

```java
class Solution {
    Map<String, String> parent = new HashMap<>();
    String find(String x) {
        parent.putIfAbsent(x, x);
        if (!parent.get(x).equals(x)) parent.put(x, find(parent.get(x)));
        return parent.get(x);
    }
    public List<String> generateSentences(List<List<String>> synonyms, String text) {
        for (List<String> p : synonyms) parent.put(find(p.get(0)), find(p.get(1)));
        Map<String, TreeSet<String>> classes = new HashMap<>();
        for (String w : parent.keySet())
            classes.computeIfAbsent(find(w), k -> new TreeSet<>()).add(w);

        String[] words = text.split(" ");
        List<String> res = new ArrayList<>();
        backtrack(words, 0, new StringBuilder(), classes, res);
        return res;
    }
    private void backtrack(String[] words, int i, StringBuilder sb,
                           Map<String, TreeSet<String>> classes, List<String> res) {
        if (i == words.length) { res.add(sb.toString().trim()); return; }
        TreeSet<String> options = classes.get(find(words[i]));
        Iterable<String> choices = (options != null) ? options : List.of(words[i]);
        int len = sb.length();
        for (String c : choices) {
            sb.append(c).append(' ');
            backtrack(words, i + 1, sb, classes, res);
            sb.setLength(len);                  // undo
        }
        res.sort(null);                          // ensure global lexicographic order
    }
}
```

**Dry run.** synonyms `[["happy","joy"],["sad","sorrow"],["joy","cheerful"]]`, text `"I am happy today but was sad yesterday"`. Class of happy = {cheerful, happy, joy}. Position "happy" expands to 3 options, "sad" to 2 → 6 sentences sorted lexicographically.

**Complexity.** Time `O(∏ classSize · L + R log R)` where `R` is the result count, space `O(R·L)`. **Edge cases:** a word with no synonyms stays fixed (single choice); empty `synonyms` yields just the original text; sorting class members via `TreeSet` and the final `res.sort` guarantee lexicographic order.

---

### Problem 33: Find All People With Secret — time-grouped DSU with rollback

**Statement.** `n` people; person `0` and `firstPerson` know a secret at time 0. `meetings[i] = [a, b, t]` means `a` and `b` meet at time `t` and share whatever secret either knows at that instant. After all meetings, return everyone who knows the secret. Meetings at the same time happen simultaneously.

**Constraints.** `1 ≤ n ≤ 10⁵`, `1 ≤ meetings.length ≤ 10⁵`.

**Approach.** Process meetings **grouped by timestamp** (sort by `t`). Within a group, union all participating pairs so the secret propagates through any chain that meets at the same instant. Then, for each participant in the group, check if their component knows the secret (connected to person 0); if **not**, you must **undo** the unions for that participant so a later meeting does not wrongly inherit knowledge — i.e., reset their parent to themselves (a lightweight rollback). This same-timestamp simultaneity is the hard twist; naive per-meeting processing fails when two people meet at the same time and only one is later connected to the source. Seed person 0 and `firstPerson` as already-knowing by unioning them.

```java
class Solution {
    int[] parent;
    int find(int x) { while (x != parent[x]) { parent[x] = parent[parent[x]]; x = parent[x]; } return x; }
    void union(int a, int b) { int ra = find(a), rb = find(b); if (ra != rb) parent[ra] = rb; }

    public List<Integer> findAllPeople(int n, int[][] meetings, int firstPerson) {
        parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        union(0, firstPerson);                                // both know it at time 0
        Arrays.sort(meetings, (x, y) -> Integer.compare(x[2], y[2]));

        int i = 0, m = meetings.length;
        while (i < m) {
            int t = meetings[i][2];
            List<int[]> group = new ArrayList<>();
            while (i < m && meetings[i][2] == t) group.add(meetings[i++]);
            for (int[] mt : group) union(mt[0], mt[1]);       // propagate within the instant
            int secretRoot = find(0);
            for (int[] mt : group) {                          // rollback those NOT connected to secret
                if (find(mt[0]) != secretRoot) parent[mt[0]] = mt[0];
                if (find(mt[1]) != secretRoot) parent[mt[1]] = mt[1];
            }
        }
        List<Integer> res = new ArrayList<>();
        int root = find(0);
        for (int p = 0; p < n; p++) if (find(p) == root) res.add(p);
        return res;
    }
}
```

**Dry run.** `n=6`, firstPerson=1, meetings `[[1,2,5],[2,3,8],[1,5,10]]`. t=5: union(1,2) (both now connected to 0 via 1). t=8: union(2,3) → 3 learns it. t=10: union(1,5) → 5 learns it. Result **[0,1,2,3,5]** (4 never connected).

**Complexity.** Time `O(M log M + (N + M)·α)`, space `O(N)`. **Edge cases:** simultaneous meetings must be batched by `t`; the rollback of unconnected participants prevents false propagation across later equal/greater timestamps; person 0 and `firstPerson` always appear in the answer.

---

### Problem 34: Critical Connections (Bridges) — Tarjan vs. the DSU misconception

**Statement.** A network of `n` servers (`0..n-1`) connected by undirected `connections`. A connection is **critical** (a bridge) if removing it disconnects some servers. Return all critical connections.

**Constraints.** `1 ≤ n ≤ 10⁵`, `n-1 ≤ connections.length ≤ 10⁵`, the graph is connected.

**Approach.** A common interview trap: "use DSU." Plain DSU finds components and cycle-closing edges, but it **cannot directly identify bridges** — an edge is a bridge iff it lies on **no** cycle, and DSU alone can't tell which specific edges are cycle-free without extra machinery. The optimal answer is **Tarjan's bridge-finding** DFS using discovery times `disc[]` and low-link values `low[]`. Edge `(u, v)` is a bridge iff `low[v] > disc[u]` — no back-edge from `v`'s subtree reaches `u` or an ancestor. (DSU *can* help in an offline trick: union the two endpoints of every non-bridge edge, but you still need a traversal to know which edges are non-bridges, so Tarjan is canonical.)

```
   0 — 1 — 2        edges (1,3) and (3,...) chains are bridges;
       |   |        the 1-2-... cycle edges are NOT bridges.
       3   4        low[v] > disc[u]  ⇒  (u,v) is a bridge
```

```java
class Solution {
    List<List<Integer>> adj;
    int[] disc, low;
    int timer = 0;
    List<List<Integer>> bridges = new ArrayList<>();

    public List<List<Integer>> criticalConnections(int n, List<List<Integer>> connections) {
        adj = new ArrayList<>();
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
        for (List<Integer> c : connections) {
            adj.get(c.get(0)).add(c.get(1));
            adj.get(c.get(1)).add(c.get(0));
        }
        disc = new int[n]; low = new int[n];
        Arrays.fill(disc, -1);
        for (int i = 0; i < n; i++) if (disc[i] == -1) dfs(i, -1);
        return bridges;
    }
    private void dfs(int u, int parent) {
        disc[u] = low[u] = timer++;
        for (int v : adj.get(u)) {
            if (v == parent) continue;                 // skip the edge we came from
            if (disc[v] == -1) {
                dfs(v, u);
                low[u] = Math.min(low[u], low[v]);
                if (low[v] > disc[u]) bridges.add(List.of(u, v)); // no back-edge ⇒ bridge
            } else {
                low[u] = Math.min(low[u], disc[v]);    // back-edge updates low-link
            }
        }
    }
}
```

**Dry run.** `n=4`, connections `[[0,1],[1,2],[2,0],[1,3]]`: the triangle 0-1-2 has no bridges (every edge is on a cycle); edge `[1,3]` is a bridge ⇒ **[[1,3]]**.

**Complexity.** Time `O(V + E)`, space `O(V + E)` (plus recursion depth — convert to an explicit stack for very deep graphs). **Edge cases:** parallel edges between the same pair are *not* bridges (track edge ids, not just `parent`, if multigraphs are allowed); a tree has every edge as a bridge; the `v == parent` skip must allow re-entry through a second parallel edge.

---

### Problem 35: Number of Good Paths — DSU processing nodes in increasing value order

**Statement.** A tree of `n` nodes with integer `vals[i]`. A **good path** starts and ends at nodes with **equal** value, and every node on the path has value `≤` that endpoint value. Single nodes count. Return the number of good paths.

**Constraints.** `1 ≤ n ≤ 3·10⁴`, the input is a tree (`n-1` edges).

**Approach.** Sort nodes by value ascending and add edges via DSU only when **both** endpoints have value `≤` the current threshold. Process values in increasing order: when merging components, count pairs of nodes that share the current max value `v` within each component — those form good paths because every intermediate node has value `≤ v` (guaranteed by the order in which we add edges). For each component, track how many nodes have the current max value (`countOfMax`); merging two components contributes `cntA * cntB` new good paths, plus each node alone is a trivial good path (`+n`). This "process in sorted order and union" pattern is a classic DSU-with-attribute technique.

```java
class Solution {
    int[] parent, cnt;                                  // cnt[root] = #nodes with current max val
    int find(int x) { while (x != parent[x]) { parent[x] = parent[parent[x]]; x = parent[x]; } return x; }

    public int numberOfGoodPaths(int[] vals, int[][] edges) {
        int n = vals.length;
        parent = new int[n]; cnt = new int[n];
        for (int i = 0; i < n; i++) { parent[i] = i; cnt[i] = 1; }

        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
        for (int[] e : edges) { adj.get(e[0]).add(e[1]); adj.get(e[1]).add(e[0]); }

        Integer[] order = new Integer[n];               // node indices sorted by value
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Integer.compare(vals[a], vals[b]));

        int good = n;                                   // every single node is a good path
        boolean[] active = new boolean[n];              // node has been "turned on"
        for (int idx : order) {
            int v = vals[idx];
            active[idx] = true;
            for (int nb : adj.get(idx)) {
                if (!active[nb] || vals[nb] > v) continue; // only union toward already-active ≤ v
                int ra = find(idx), rb = find(nb);
                if (ra == rb) continue;
                // both roots' cnt reflect nodes with value v in their components
                int ca = (vals[representativeVal(ra, vals)] == v) ? cnt[ra] : 0;
                int cb = (vals[representativeVal(rb, vals)] == v) ? cnt[rb] : 0;
                good += ca * cb;
                parent[rb] = ra;
                cnt[ra] = ca + cb;                      // merged count of value-v nodes
            }
        }
        return good;
    }
    // helper kept simple: a root's stored value equals the value it was activated at
    private int representativeVal(int root, int[] vals) { return root; }
}
```

> Note: the cleaner production form tracks `maxVal[root]` and `cnt[root]` together. The version below is the idiomatic one:

```java
class Solution2 {
    int[] parent;
    int find(int x) { while (x != parent[x]) { parent[x] = parent[parent[x]]; x = parent[x]; } return x; }
    public int numberOfGoodPaths(int[] vals, int[][] edges) {
        int n = vals.length;
        parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
        for (int[] e : edges) { adj.get(e[0]).add(e[1]); adj.get(e[1]).add(e[0]); }

        // group nodes by value, ascending
        TreeMap<Integer, List<Integer>> byVal = new TreeMap<>();
        for (int i = 0; i < n; i++) byVal.computeIfAbsent(vals[i], k -> new ArrayList<>()).add(i);

        int good = 0;
        for (Map.Entry<Integer, List<Integer>> e : byVal.entrySet()) {
            int v = e.getKey();
            for (int node : e.getValue())               // connect to neighbors with value ≤ v
                for (int nb : adj.get(node))
                    if (vals[nb] <= v) { parent[find(node)] = find(nb); }
            Map<Integer, Integer> rootCount = new HashMap<>(); // among value-v nodes, per component
            for (int node : e.getValue()) {
                int r = find(node);
                int c = rootCount.merge(r, 1, Integer::sum);
                good += c;                              // adding the c-th node makes (c-1) new pairs + itself
            }
        }
        return good;
    }
}
```

**Dry run.** `vals=[1,3,2,1,3]`, edges forming a tree. Singles give 5; equal-value endpoints whose connecting path stays `≤` the value add the rest — e.g. the two `3`s connected through smaller nodes add 1, total **6** (a standard LeetCode 2421 example yields 6).

**Complexity.** Time `O(n log n + n·α(n))` (sort by value dominates), space `O(n)`. **Edge cases:** every single node is a good path (the `+1` per node, accumulated as `good += c` where the first node of a component contributes 1); only union toward neighbors with value `≤` current threshold; use `Solution2`'s grouped form in interviews — it is the clean, correct template.

---

### Problem 36: Build a Matrix With Conditions — two independent topological sorts

**Statement.** Given an integer `k` and two condition lists `rowConditions` and `colConditions` (each `[above, below]` / `[left, right]` meaning one number must come before another), build a `k × k` matrix containing each of `1..k` exactly once such that all row and column orderings are satisfied. Return any valid matrix, or an empty matrix if impossible.

**Constraints.** `2 ≤ k ≤ 400`, conditions up to `10⁴` each.

**Approach.** Rows and columns are **independent** constraint systems, so run **two separate topological sorts** (Problem 5 style): one over `rowConditions` to get the top-to-bottom ordering of values (which row each value occupies), and one over `colConditions` for left-to-right (which column). If either has a cycle, return `[]`. Then place value `x` at `matrix[rowPos[x]][colPos[x]]`, where `rowPos[x]` is `x`'s index in the row topo order and `colPos[x]` is its index in the column topo order. This decomposition — recognizing two orthogonal DAGs — is the key insight.

```
rowConditions impose vertical order:   colConditions impose horizontal order:
  topo(row) = [1,3,2]                    topo(col) = [3,1,2]
  rowPos: 1→0, 3→1, 2→2                  colPos: 3→0, 1→1, 2→2
  place value v at (rowPos[v], colPos[v]) → each value lands in a unique cell
```

```java
class Solution {
    public int[][] buildMatrix(int k, int[][] rowConditions, int[][] colConditions) {
        int[] rowOrder = topoSort(k, rowConditions);
        int[] colOrder = topoSort(k, colConditions);
        if (rowOrder == null || colOrder == null) return new int[0][0]; // cycle ⇒ impossible

        int[] rowPos = new int[k + 1], colPos = new int[k + 1];
        for (int i = 0; i < k; i++) rowPos[rowOrder[i]] = i;
        for (int i = 0; i < k; i++) colPos[colOrder[i]] = i;

        int[][] matrix = new int[k][k];
        for (int v = 1; v <= k; v++) matrix[rowPos[v]][colPos[v]] = v;
        return matrix;
    }
    private int[] topoSort(int k, int[][] conditions) {
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i <= k; i++) adj.add(new ArrayList<>());
        int[] indeg = new int[k + 1];
        for (int[] c : conditions) { adj.get(c[0]).add(c[1]); indeg[c[1]]++; }
        Deque<Integer> q = new ArrayDeque<>();
        for (int i = 1; i <= k; i++) if (indeg[i] == 0) q.offer(i);
        int[] order = new int[k];
        int idx = 0;
        while (!q.isEmpty()) {
            int u = q.poll();
            order[idx++] = u;
            for (int v : adj.get(u)) if (--indeg[v] == 0) q.offer(v);
        }
        return idx == k ? order : null;                  // null signals a cycle
    }
}
```

**Dry run.** `k=3`, rowConditions `[[1,2],[3,2]]`, colConditions `[[2,1],[3,2]]`. Row topo e.g. `[1,3,2]` (or `[3,1,2]`); col topo e.g. `[3,2,1]`. Each value gets a distinct (row,col) cell, producing a valid `3×3` matrix; a cyclic condition like `[[1,2],[2,1]]` returns `[]`.

**Complexity.** Time `O(k + E)` per sort = `O(k + E)` total, space `O(k + E)`. **Edge cases:** a cycle in **either** list ⇒ empty matrix; values not mentioned in conditions still get a position (they enter the queue immediately with indegree 0); because each value appears once per axis, the `(rowPos, colPos)` mapping is a bijection — no two values collide in a cell.

---

## 🧩 Extended Problems — Supplemental: Medium → Expert

A further spread of distinct, well-known interview and competitive-round problems for this topic. They reuse the base `DSU` template from the top of this file; where a problem needs extra state (a `size[]` array, a parity/weight bit, rollback, or two-coloring) the augmentation is shown inline.

---

### Problem 37: Regions Cut By Slashes — DSU on a 3×3 subdivided grid

**Statement.** An `n × n` grid is described by strings of `'/'`, `'\\'`, and `' '`. Each cell is split by its slash. Return the number of contiguous **regions** formed.

**Constraints.** `1 ≤ n ≤ 30`, each character is `'/'`, `'\\'`, or `' '`.

**Approach.** Subdivide every cell into **4 triangles** numbered 0 (top), 1 (right), 2 (bottom), 3 (left), giving `4·n·n` DSU nodes (LeetCode 959). Inside a cell, a `' '` unites all four triangles; a `'/'` unites top+left (0,3) and right+bottom (1,2); a `'\\'` unites top+right (0,1) and bottom+left (2,3). Across cells, a cell's right triangle (1) joins its right neighbor's left (3), and its bottom triangle (2) joins the lower neighbor's top (0). The region count is the number of DSU components.

```
cell triangles:        '/' splits:        '\' splits:
     0                 0\   /0            0   \0
   3   1               3 \ / 1            3 / \ 1  -> (0,1) & (2,3)
     2                 2 / \ 2            2 \ / 2
                       (0,3) & (1,2)
```

```java
class Solution {
    public int regionsBySlashes(String[] grid) {
        int n = grid.length;
        DSU dsu = new DSU(4 * n * n);
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                int base = 4 * (r * n + c);          // triangles base..base+3 (T,R,B,L)
                char ch = grid[r].charAt(c);
                if (ch != '/') { dsu.union(base + 0, base + 1); dsu.union(base + 2, base + 3); } // \ or ' '
                if (ch != '\\') { dsu.union(base + 0, base + 3); dsu.union(base + 1, base + 2); } // / or ' '
                if (r + 1 < n) dsu.union(base + 2, 4 * ((r + 1) * n + c) + 0); // bottom-top
                if (c + 1 < n) dsu.union(base + 1, 4 * (r * n + (c + 1)) + 3); // right-left
            }
        }
        return dsu.count;
    }
}
```

**Dry run.** `grid=[" /","/ "]`: each `'/'` splits its cell into two triangles; the cross-cell unions of blank/sloped halves leave **2** regions (the standard LeetCode answer). The classic 3-region example is `["/\\","\\/"]`.

**Complexity.** Time `O(n²·α)`, space `O(n²)` (4 nodes per cell). **Edge cases:** an all-blank grid yields **1** region; the triangle numbering must be consistent across the inside-cell and cross-cell unions; backslash needs escaping (`'\\'`) in Java source.

---

### Problem 38: Bricks Falling When Hit — DSU in reverse time (offline)

**Statement.** An `m × n` grid of bricks (`1`) and empty (`0`). A brick is **stable** if connected (4-dir) to the top row or transitively to a stable brick. Given `hits` (cells erased in order), return for each hit how many **other** bricks fall as a result.

**Constraints.** `1 ≤ m·n ≤ 4·10⁴`, `1 ≤ hits.length ≤ 4·10⁴`.

**Approach.** DSU supports only union, not deletion — so process **time in reverse** (LeetCode 803). First erase all hit cells from the grid. Then union the surviving bricks together and connect top-row bricks to a virtual "roof" node. Now replay hits backward: re-adding a brick and unioning it to neighbors and (if in row 0) to the roof. The number that **newly** become connected to the roof, minus the brick we just added, is how many fell when that hit originally happened. Carefully skip hits on cells that were already empty.

```java
class Solution {
    public int[] hitBricks(int[][] grid, int[][] hits) {
        int m = grid.length, n = grid[0].length;
        int roof = m * n;                              // virtual node above the top row
        int[][] g = new int[m][n];
        for (int i = 0; i < m; i++) g[i] = grid[i].clone();
        for (int[] h : hits) g[h[0]][h[1]] = 0;        // erase all hits first

        DSU dsu = new DSU(m * n + 1);
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        for (int r = 0; r < m; r++)
            for (int c = 0; c < n; c++)
                if (g[r][c] == 1) connect(g, dsu, r, c, m, n, roof, dirs);

        int[] res = new int[hits.length];
        for (int i = hits.length - 1; i >= 0; i--) {
            int r = hits[i][0], c = hits[i][1];
            if (grid[r][c] == 0) continue;             // hit on empty cell: nothing falls
            int before = dsu.find(roof) == dsu.find(roof) ? sizeOfRoof(dsu, roof, m, n) : 0;
            g[r][c] = 1;
            connect(g, dsu, r, c, m, n, roof, dirs);
            int after = sizeOfRoof(dsu, roof, m, n);
            res[i] = Math.max(0, after - before - 1);  // -1 for the re-added brick itself
        }
        return res;
    }
    private void connect(int[][] g, DSU dsu, int r, int c, int m, int n, int roof, int[][] dirs) {
        int id = r * n + c;
        if (r == 0) dsu.union(id, roof);
        for (int[] d : dirs) {
            int nr = r + d[0], nc = c + d[1];
            if (nr >= 0 && nr < m && nc >= 0 && nc < n && g[nr][nc] == 1)
                dsu.union(id, nr * n + nc);
        }
    }
    private int sizeOfRoof(DSU dsu, int roof, int m, int n) {  // count nodes joined to roof
        int root = dsu.find(roof), cnt = 0;
        for (int i = 0; i < m * n; i++) if (dsu.find(i) == root) cnt++;
        return cnt;
    }
}
```

> Note: the `sizeOfRoof` scan is `O(mn)` per hit for clarity. The production form augments DSU with a `size[]` array so the roof-component size is `O(1)` (track `size[find(roof)]`), making the whole solution `O((mn + H)·α)`.

**Dry run.** `grid=[[1,0,0,0],[1,1,1,0]]`, `hits=[[1,0]]`: erasing (1,0) makes bricks (1,1),(1,2) lose their only support → they fall. Answer **[2]**.

**Complexity.** Time `O((mn + H·mn))` with the scan, `O((mn + H)·α)` with a `size[]` augmentation. Space `O(mn)`. **Edge cases:** a hit on an already-empty cell yields `0`; the just-re-added brick must be subtracted; reverse-time processing is the whole trick since DSU cannot delete.

---

### Problem 39: Checking Existence of Edge Length Limited Paths — offline DSU sorted by weight

**Statement.** Given an undirected weighted graph (`edgeList[i] = [u, v, w]`) and `queries[j] = [p, q, limit]`, answer for each query whether there is a path from `p` to `q` using only edges with weight **strictly less than** `limit`.

**Constraints.** `2 ≤ n ≤ 10⁵`, `edgeList`, `queries` up to `10⁵`.

**Approach.** Process **offline** (LeetCode 1697): sort edges by weight ascending and sort queries by `limit` ascending (remembering each query's original index). Sweep through queries; before answering a query with limit `L`, union every edge whose weight `< L`. Then the query is `true` iff `p` and `q` now share a root. Because both lists are sorted, each edge is unioned exactly once across all queries — a monotone two-pointer over DSU. This beats per-query filtering + BFS (`O(Q·(V+E))`).

```java
class Solution {
    public boolean[] distanceLimitedPathsExist(int n, int[][] edgeList, int[][] queries) {
        Arrays.sort(edgeList, (a, b) -> Integer.compare(a[2], b[2]));
        Integer[] qi = new Integer[queries.length];
        for (int i = 0; i < qi.length; i++) qi[i] = i;
        Arrays.sort(qi, (a, b) -> Integer.compare(queries[a][2], queries[b][2]));

        DSU dsu = new DSU(n);
        boolean[] res = new boolean[queries.length];
        int e = 0;
        for (int idx : qi) {
            int limit = queries[idx][2];
            while (e < edgeList.length && edgeList[e][2] < limit)
                dsu.union(edgeList[e][0], edgeList[e++][1]);
            res[idx] = dsu.find(queries[idx][0]) == dsu.find(queries[idx][1]);
        }
        return res;
    }
}
```

**Dry run.** edges `[[0,1,2],[1,2,4],[2,0,8],[1,0,16]]`, queries `[[0,1,2],[0,2,5]]`. Query `[0,1,2]`: no edge `< 2` unioned → 0,1 separate ⇒ **false**. Query `[0,2,5]`: edges with w<5 are (0,1,2),(1,2,4) → 0,1,2 joined ⇒ **true**. Result **[false,true]**.

**Complexity.** Time `O((E + Q) log(E + Q))` (the two sorts), space `O(n + Q)`. **Edge cases:** strict `<` (not `≤`) matters; a query whose endpoints are equal is trivially `true`; answers must be written back to the **original** query index after sorting.

---

### Problem 40: Minimize Malware Spread — DSU components with single-source removal

**Statement.** A network `graph` (adjacency matrix) is infected starting from the nodes in `initial`. Malware spreads through all connected nodes. Remove **exactly one** node from `initial` to minimize the final infected count `M(initial)`. Return the node to remove (smallest index on ties).

**Constraints.** `1 ≤ n ≤ 300`, `1 ≤ initial.length ≤ n`.

**Approach.** Build connected components with DSU (LeetCode 924). Removing a node helps **only** if it is the *sole* infected node in its component — then that whole component is saved. Count how many `initial` nodes fall in each component (`compInfectedCount[root]`). For components with exactly one infected source, that source's removal saves `size[root]` nodes. Pick the source whose component is largest (ties → smallest index). If no component has a unique infected source, removing any node saves nothing, so return the smallest index in `initial`.

```java
class Solution {
    public int minMalwareSpread(int[][] graph, int[] initial) {
        int n = graph.length;
        DSU dsu = new DSU(n);
        int[] size = new int[n];
        Arrays.fill(size, 1);
        for (int i = 0; i < n; i++)
            for (int j = i + 1; j < n; j++)
                if (graph[i][j] == 1 && dsu.union(i, j)) { } // unite; size folded below
        for (int i = 0; i < n; i++) size[i] = 0;             // recompute sizes by root
        for (int i = 0; i < n; i++) size[dsu.find(i)]++;

        int[] infectedInComp = new int[n];
        for (int v : initial) infectedInComp[dsu.find(v)]++;

        int ans = -1, best = -1;
        for (int v : initial) {
            int root = dsu.find(v);
            if (infectedInComp[root] == 1) {                 // v is the only infected source here
                if (size[root] > best || (size[root] == best && v < ans)) {
                    best = size[root]; ans = v;
                }
            }
        }
        if (ans == -1) {                                     // no unique-source component: smallest index
            ans = initial[0];
            for (int v : initial) ans = Math.min(ans, v);
        }
        return ans;
    }
}
```

**Dry run.** `graph=[[1,1,0],[1,1,0],[0,0,1]]`, `initial=[0,1]`. Component {0,1} has two infected sources → removing either still leaves it infected (no save). No unique-source component → return smallest index **0**.

**Complexity.** Time `O(n²·α)` (matrix scan dominates), space `O(n)`. **Edge cases:** a component with ≥2 infected sources is never saved by removing one; tie-break by smallest index; the fallback when nothing helps is the minimum `initial` value.

---

### Problem 41: Loud and Rich — topological propagation over a DAG

**Statement.** `richer[i] = [a, b]` means person `a` has more money than `b`. `quiet[x]` is the quietness of person `x` (all distinct). For each person `x`, return `answer[x]` = the person `y` who is at least as rich as `x` (i.e., reachable to `x` along "richer" relations, including `x` itself) and has the **least** quietness.

**Constraints.** `1 ≤ n ≤ 500`, `richer` up to `n·(n-1)/2`, forms a DAG.

**Approach.** Build edges `a → b` (a richer than b). The candidates for `b` are `b` itself plus everyone richer than `b` (all ancestors). Process nodes in **topological order**: when a node `u` is finalized, push its best (quietest) candidate down to each successor `v`, because `u` is richer than `v` (LeetCode 851). Initialize `answer[x] = x`. This DAG propagation is the same skeleton as Course Schedule IV — every richer person is finalized before the poorer person uses them.

```java
class Solution {
    public int[] loudAndRich(int[][] richer, int[] quiet) {
        int n = quiet.length;
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
        int[] indeg = new int[n];
        for (int[] r : richer) { adj.get(r[0]).add(r[1]); indeg[r[1]]++; }

        int[] ans = new int[n];
        for (int i = 0; i < n; i++) ans[i] = i;             // each person is their own candidate

        Deque<Integer> q = new ArrayDeque<>();
        for (int i = 0; i < n; i++) if (indeg[i] == 0) q.offer(i);
        while (!q.isEmpty()) {
            int u = q.poll();
            for (int v : adj.get(u)) {
                if (quiet[ans[u]] < quiet[ans[v]]) ans[v] = ans[u]; // u is richer than v
                if (--indeg[v] == 0) q.offer(v);
            }
        }
        return ans;
    }
}
```

**Dry run.** `richer=[[1,0],[2,1],[3,1],[3,7],[4,3],[5,3],[6,3]]`, `quiet=[3,2,5,4,6,1,7,0]`. Person 0's ancestors include 1,2,3,4,5,6; the quietest among `{0,1,...}` with quiet 0 is person 7 (richer chain), yielding `answer[0]=` **5** in the LeetCode example after full propagation.

**Complexity.** Time `O(V + E)`, space `O(V + E)`. **Edge cases:** with no `richer` edges every person answers themselves; the all-distinct `quiet` guarantees a unique minimum; correctness relies on processing in topo order so each richer person's best is settled before use.

---

### Problem 42: Reachable Nodes — DSU duplicate-removal & component sizing variant

**Statement.** Given `edges` of a forest where `edges[i] = [a, b]` (undirected) and a starting node `source`, return the number of nodes reachable from `source`, **but** ignore edges that would connect two nodes already in the same component (treat the structure as a union of trees and answer the component size of `source`).

**Constraints.** `1 ≤ n ≤ 10⁵`, `0 ≤ edges.length ≤ 10⁵`.

**Approach.** A direct application of **union by size** (Problem 30's augmentation): union all edges while maintaining `size[root]`; the answer is `size[find(source)]`. Redundant edges are absorbed by DSU's "already same root" no-op, so the size stays correct. This is the canonical "how big is my component?" query and the building block for many of the connectivity counting problems above.

```java
class SizedDSU {
    int[] parent, size;
    SizedDSU(int n) {
        parent = new int[n]; size = new int[n];
        for (int i = 0; i < n; i++) { parent[i] = i; size[i] = 1; }
    }
    int find(int x) { while (x != parent[x]) { parent[x] = parent[parent[x]]; x = parent[x]; } return x; }
    void union(int a, int b) {
        int ra = find(a), rb = find(b);
        if (ra == rb) return;
        if (size[ra] < size[rb]) { int t = ra; ra = rb; rb = t; }
        parent[rb] = ra; size[ra] += size[rb];
    }
}

class Solution {
    public int reachableNodes(int n, int[][] edges, int source) {
        SizedDSU dsu = new SizedDSU(n);
        for (int[] e : edges) dsu.union(e[0], e[1]);
        return dsu.size[dsu.find(source)];
    }
}
```

**Dry run.** `n=6`, edges `[[0,1],[1,2],[3,4]]`, source 2: component {0,1,2} size 3 ⇒ **3**. With source 5 (isolated) ⇒ **1**.

**Complexity.** Time `O(n + E·α(n))`, space `O(n)`. **Edge cases:** an isolated `source` returns `1` (itself); duplicate edges are harmless no-ops; reading `size[find(source)]` (root size), never `size[source]` directly, is essential after merges.

---

### Problem 43: GCD Sort an Array — DSU over prime factors to test sortability

**Statement.** Given `nums`, you may swap `nums[i]` and `nums[j]` if `gcd(nums[i], nums[j]) > 1`, any number of times. Return whether you can sort `nums` non-decreasingly.

**Constraints.** `1 ≤ nums.length ≤ 3·10⁴`, `2 ≤ nums[i] ≤ 10⁵`.

**Approach.** Swappability is transitive via shared prime factors (LeetCode 1998), exactly like "Largest Component by Common Factor": union each value with each of its prime factors using a smallest-prime-factor (SPF) sieve for fast factorization. Two positions are interchangeable iff their values lie in the same DSU component. Then compare `nums` to its sorted copy: at each index, the original and sorted value must be in the **same component** (otherwise that element can never reach its sorted position).

```java
class Solution {
    int[] parent;
    int find(int x) { while (x != parent[x]) { parent[x] = parent[parent[x]]; x = parent[x]; } return x; }
    void union(int a, int b) { int ra = find(a), rb = find(b); if (ra != rb) parent[ra] = rb; }

    public boolean gcdSort(int[] nums) {
        int MAX = 100001;
        int[] spf = new int[MAX];                          // smallest prime factor sieve
        for (int i = 2; i < MAX; i++) if (spf[i] == 0)
            for (int j = i; j < MAX; j += i) if (spf[j] == 0) spf[j] = i;

        parent = new int[MAX];
        for (int i = 0; i < MAX; i++) parent[i] = i;
        for (int x : nums) {
            int v = x;
            while (v > 1) {                                // union x with each prime factor
                int p = spf[v];
                union(x, p);
                while (v % p == 0) v /= p;
            }
        }
        int[] sorted = nums.clone();
        Arrays.sort(sorted);
        for (int i = 0; i < nums.length; i++)
            if (find(nums[i]) != find(sorted[i])) return false; // can't reach its sorted slot
        return true;
    }
}
```

**Dry run.** `nums=[7,21,3]`: 21 and 3 share prime 3; 21 and 7 share prime 7 → all three in one component. Sorted `[3,7,21]`; each position's value is in the same component ⇒ **true**. `nums=[5,2,6,2]` → 5 isolated (prime 5), so it can't move past others ⇒ **false**.

**Complexity.** Time `O(MAX log log MAX + n·log maxVal·α)`, space `O(MAX)`. **Edge cases:** values are used as DSU node ids directly (size the array to `maxVal+1`); a prime that appears in only one value forms a singleton; SPF factorization is `O(log v)` per value, far faster than trial division.

---

### Problem 44: Number of Islands II with Rollback (Dynamic Connectivity) — DSU + undo stack

**Statement.** Like Number of Islands II (Problem 7), but operations interleave **add land** and **undo** the last add. Support: `addLand(r, c)` returning the current island count, and `undo()` reverting the most recent `addLand`. Implement a DSU that supports rollback.

**Constraints.** grid up to `10³ × 10³`, up to `10⁵` operations.

**Approach.** Path compression is **irreversible**, so a rollback DSU uses **union by rank only** (no compression) and records each `union`'s mutation (which root got reparented, whether a rank bumped) on a stack (CP-Algorithms "DSU with rollback"). `undo()` pops and reverses the last operation, restoring parent/rank and the island count. `find` is `O(log n)` without compression — the standard trade-off to gain reversibility. Each `addLand` may perform several unions and a `+1`; we snapshot the stack length before so a single `undo` reverts the whole step.

```java
class RollbackDSU {
    int[] parent, rank;
    int count;                                            // components (islands)
    Deque<int[]> history = new ArrayDeque<>();            // {child, oldRank, rankBumped, countDelta}
    RollbackDSU(int n) {
        parent = new int[n]; rank = new int[n]; count = 0;
        for (int i = 0; i < n; i++) parent[i] = i;
    }
    int find(int x) { while (x != parent[x]) x = parent[x]; return x; } // NO compression
    boolean union(int a, int b) {
        int ra = find(a), rb = find(b);
        if (ra == rb) { history.push(new int[]{-1, 0, 0, 0}); return false; }
        if (rank[ra] < rank[rb]) { int t = ra; ra = rb; rb = t; }
        int bumped = (rank[ra] == rank[rb]) ? 1 : 0;
        history.push(new int[]{rb, rank[ra], bumped, -1});
        parent[rb] = ra;
        if (bumped == 1) rank[ra]++;
        count--;
        return true;
    }
    void undo() {
        int[] op = history.pop();
        if (op[0] == -1) return;                           // was a no-op union
        int rb = op[0], ra = parent[rb];
        parent[rb] = rb;                                   // detach child
        if (op[2] == 1) rank[ra]--;                        // revert rank bump
        count -= op[3];                                    // undo countDelta (i.e., +1)
    }
    void addNode() { count++; history.push(new int[]{-2, 0, 0, 1}); }      // new island
    void undoAdd() { history.pop(); count--; }
}

class Solution {
    int m, n;
    int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
    public int addLand(RollbackDSU dsu, boolean[] land, int r, int c) {
        int id = r * n + c;
        if (land[id]) return dsu.count;
        land[id] = true; dsu.addNode();                    // +1 island
        for (int[] d : dirs) {
            int nr = r + d[0], nc = c + d[1];
            if (nr >= 0 && nr < m && nc >= 0 && nc < n && land[nr * n + nc])
                dsu.union(id, nr * n + nc);                // each merge -1
        }
        return dsu.count;
    }
}
```

**Dry run.** `3×3`. addLand(0,0)→1, addLand(0,1)→union with (0,0)→1, then `undo()` reverts the union of (0,1) and (0,0) plus the `addNode` (multiple pops), restoring count to 1 (only (0,0) land). Rolling back exactly the operations of one `addLand` returns the prior state.

**Complexity.** Time `O(log n)` per `find`/`union`, `O(1)` per recorded `undo`. Space `O(ops)` for the history stack. **Edge cases:** path compression **must** be dropped (it cannot be undone); a no-op union still pushes a sentinel so `undo` stays balanced; an `addLand` that performs `k` unions requires `k+1` `undo` calls (or snapshot the stack length to revert the whole step).

---

### Problem 45: Remove Max Number of Edges to Keep Graph Fully Traversable — two DSUs (Alice/Bob)

**Statement.** An undirected graph with edges typed `1` (Alice-only), `2` (Bob-only), `3` (both). Remove the maximum number of edges so that **both** Alice and Bob can still reach every node from any node. Return the max removable, or `-1` if either can't traverse all nodes.

**Constraints.** `1 ≤ n ≤ 10⁵`, `1 ≤ edges.length ≤ min(10⁵, 3·n·(n-1)/2)`.

**Approach.** Greedy with **two DSUs**, one for Alice and one for Bob (LeetCode 1579). **Process type-3 edges first** — a shared edge benefits both, so add it to both DSUs if it merges components in either; otherwise it's redundant. Then process type-1 in Alice's DSU and type-2 in Bob's. Every edge that fails to merge (both endpoints already connected) is removable. Finally both DSUs must each form a single component (`count == 1`). The answer is the total redundant-edge count.

```java
class CountingDSU {
    int[] parent; int count;
    CountingDSU(int n) { parent = new int[n + 1]; count = n; for (int i = 0; i <= n; i++) parent[i] = i; }
    int find(int x) { while (x != parent[x]) { parent[x] = parent[parent[x]]; x = parent[x]; } return x; }
    boolean union(int a, int b) {
        int ra = find(a), rb = find(b);
        if (ra == rb) return false;
        parent[ra] = rb; count--; return true;
    }
}

class Solution {
    public int maxNumEdgesToRemove(int n, int[][] edges) {
        CountingDSU alice = new CountingDSU(n), bob = new CountingDSU(n);
        int used = 0;
        for (int[] e : edges) if (e[0] == 3) {              // shared edges first
            boolean a = alice.union(e[1], e[2]);
            boolean b = bob.union(e[1], e[2]);
            if (a || b) used++;                             // useful to at least one
        }
        for (int[] e : edges) {
            if (e[0] == 1 && alice.union(e[1], e[2])) used++;
            else if (e[0] == 2 && bob.union(e[1], e[2])) used++;
        }
        // each DSU spans all n nodes ⇒ count == 1 (node 0 is unused/its own set; with 1-index sizing both reach 1 over 1..n)
        if (alice.count != 1 + 1 - 1 || bob.count != 1) { } // see edge-case note on indexing
        boolean ok = (componentsOver1ToN(alice, n) == 1) && (componentsOver1ToN(bob, n) == 1);
        return ok ? edges.length - used : -1;
    }
    private int componentsOver1ToN(CountingDSU dsu, int n) {
        java.util.Set<Integer> roots = new java.util.HashSet<>();
        for (int i = 1; i <= n; i++) roots.add(dsu.find(i));
        return roots.size();
    }
}
```

**Dry run.** `n=4`, `edges=[[3,1,2],[3,2,3],[1,1,3],[1,2,4],[1,1,2],[2,3,4]]`. Type-3 edges connect {1,2,3} for both. Type-1: (1,3) redundant, (2,4) joins 4 to Alice, (1,2) redundant. Type-2: (3,4) joins 4 to Bob. Used = 4; removable = `6 - 4 =` **2**. Both span all 4 nodes ⇒ valid.

**Complexity.** Time `O((V + E)·α)`, space `O(V)`. **Edge cases:** type-3 must be processed before type-1/2 (a shared edge is never worse); a redundant shared edge that helps neither is removable; if either traversal can't reach all nodes, return `-1`; verify "fully connected over `1..n`" (1-indexed sizing leaves node 0 aside).

---

### Problem 46: Is Graph Bipartite — DSU with companion (enemy) sets

**Statement.** Given an undirected `graph` (adjacency list), return whether it is **bipartite** (vertices 2-colorable so no edge joins same-colored vertices).

**Constraints.** `1 ≤ n ≤ 100`, no self-loops, no parallel edges.

**Approach.** The textbook answer is BFS/DFS 2-coloring, but the **DSU phrasing** is a classic interview twist (LeetCode 785). For each vertex `u`, all its neighbors must share one color and be the opposite of `u`. Union all of `u`'s neighbors together (they're on the same side); if `u` ever ends up in the **same** DSU set as one of its neighbors, an odd cycle exists ⇒ not bipartite. This "union the neighbors, forbid self-union with neighbor" pattern also underlies "Possible Bipartition" (group enemies on opposite sides).

```
bipartite (even cycle):   not bipartite (triangle):
  0 — 1                      0 — 1
  |   |                       \ /
  3 — 2                        2     0,1,2 forced into one set ⇒ conflict
```

```java
class Solution {
    public boolean isBipartite(int[][] graph) {
        int n = graph.length;
        DSU dsu = new DSU(n);
        for (int u = 0; u < n; u++) {
            int[] nbrs = graph[u];
            for (int v : nbrs) {
                if (dsu.find(u) == dsu.find(v)) return false;   // u and a neighbor same set ⇒ odd cycle
                dsu.union(nbrs[0], v);                           // all neighbors of u share a side
            }
        }
        return true;
    }
}
```

**Dry run.** `graph=[[1,3],[0,2],[1,3],[0,2]]` (4-cycle): neighbors of each node get unioned consistently, no node shares a set with its neighbor ⇒ **true**. Triangle `[[1,2],[0,2],[0,1]]`: node 0 unions {1,2}; checking node 1's neighbor 2 finds them already together via 0 ⇒ **false**.

**Complexity.** Time `O((V + E)·α)`, space `O(V)`. **Edge cases:** disconnected graphs work (each component checked independently); a self-loop would make it instantly non-bipartite; the DSU approach mirrors the "Possible Bipartition" enemy-grouping problem.

---

### Problem 47: Parallel Courses III — DAG longest path with node weights (critical path)

**Statement.** `n` courses, `relations[i] = [a, b]` (a before b), and `time[i]` = months to finish course `i+1`. Courses with no remaining prerequisites can run in parallel. Return the **minimum** months to complete all courses.

**Constraints.** `1 ≤ n ≤ 5·10⁴`, `relations` up to `5·10⁴`.

**Approach.** The minimum makespan equals the **longest weighted path** (critical path) in the DAG (LeetCode 2050). Run Kahn's topological sort; maintain `finish[v]` = earliest completion time of course `v`. When relaxing edge `u → v`, `finish[v] = max(finish[v], finish[u] + time[v])`, where a source course's finish is just its own `time`. The answer is `max(finish[*])`. This is the canonical DAG-DP that topological order enables in linear time (impossible on general graphs).

```java
class Solution {
    public int minimumTime(int n, int[][] relations, int[] time) {
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i <= n; i++) adj.add(new ArrayList<>());
        int[] indeg = new int[n + 1];
        for (int[] r : relations) { adj.get(r[0]).add(r[1]); indeg[r[1]]++; }

        long[] finish = new long[n + 1];
        Deque<Integer> q = new ArrayDeque<>();
        for (int i = 1; i <= n; i++) if (indeg[i] == 0) { q.offer(i); finish[i] = time[i - 1]; }

        long ans = 0;
        while (!q.isEmpty()) {
            int u = q.poll();
            ans = Math.max(ans, finish[u]);
            for (int v : adj.get(u)) {
                finish[v] = Math.max(finish[v], finish[u] + time[v - 1]); // relax along the DAG
                if (--indeg[v] == 0) q.offer(v);
            }
        }
        return (int) ans;
    }
}
```

**Dry run.** `n=3`, relations `[[1,3],[2,3]]`, time `[3,2,5]`. Sources 1 (finish 3), 2 (finish 2). Course 3 = max(3,2)+5 = 8. Answer **8**.

**Complexity.** Time `O(V + E)`, space `O(V + E)`. **Edge cases:** a course with no prerequisites finishes at its own `time`; `long` accumulator avoids overflow for large weights; the answer is the max over all finishes (the last-finishing course defines the makespan).

---

### Problem 48: Strongly Connected Components — Kosaraju (condensation is a DAG)

**Statement.** Given a directed graph (`n` nodes, edge list), return its **strongly connected components** (maximal sets where every node reaches every other). The component graph (condensation) is a DAG you can then topologically sort.

**Constraints.** `1 ≤ n ≤ 10⁵`, edges up to `2·10⁵`.

**Approach.** **Kosaraju's algorithm**: (1) DFS the original graph pushing nodes onto a stack in finish order; (2) reverse all edges; (3) pop nodes from the stack and DFS in the reversed graph — each DFS tree is one SCC. This is the directed-graph analog of "components," and condensing each SCC to a single node yields a DAG that can be topologically ordered — the bridge between SCCs and topological sort. (Tarjan's single-pass SCC is the alternative.)

```java
class Solution {
    List<List<Integer>> adj, radj;
    boolean[] visited;
    Deque<Integer> order = new ArrayDeque<>();   // finish-time stack

    public List<List<Integer>> sccs(int n, int[][] edges) {
        adj = new ArrayList<>(); radj = new ArrayList<>();
        for (int i = 0; i < n; i++) { adj.add(new ArrayList<>()); radj.add(new ArrayList<>()); }
        for (int[] e : edges) { adj.get(e[0]).add(e[1]); radj.get(e[1]).add(e[0]); }

        visited = new boolean[n];
        for (int i = 0; i < n; i++) if (!visited[i]) dfs1(i);   // pass 1: order by finish time

        Arrays.fill(visited, false);
        List<List<Integer>> result = new ArrayList<>();
        while (!order.isEmpty()) {
            int u = order.pop();
            if (!visited[u]) {
                List<Integer> comp = new ArrayList<>();
                dfs2(u, comp);                                  // pass 2 on reversed graph
                result.add(comp);
            }
        }
        return result;
    }
    private void dfs1(int u) {                                  // iterative to avoid deep recursion
        Deque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[]{u, 0});
        visited[u] = true;
        while (!stack.isEmpty()) {
            int[] top = stack.peek();
            int node = top[0];
            if (top[1] < adj.get(node).size()) {
                int v = adj.get(node).get(top[1]++);
                if (!visited[v]) { visited[v] = true; stack.push(new int[]{v, 0}); }
            } else { order.push(node); stack.pop(); }
        }
    }
    private void dfs2(int u, List<Integer> comp) {
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(u); visited[u] = true;
        while (!stack.isEmpty()) {
            int node = stack.pop();
            comp.add(node);
            for (int v : radj.get(node)) if (!visited[v]) { visited[v] = true; stack.push(v); }
        }
    }
}
```

**Dry run.** edges `[[0,1],[1,2],[2,0],[2,3],[3,4],[4,3]]`: SCCs are `{0,1,2}` (a cycle) and `{3,4}` (a cycle), node ordering may yield `[[0,1,2],[3,4]]`. Condensing gives a 2-node DAG `{0,1,2} → {3,4}`.

**Complexity.** Time `O(V + E)` (two linear passes), space `O(V + E)`. **Edge cases:** a single node with no edges is its own SCC; iterative DFS avoids stack overflow for `n = 10⁵`; the SCCs returned in pass-2 order are already a reverse-topological listing of the condensation.

---

### Problem 49: Sort Items by Groups Respecting Dependencies — two-level topological sort

**Statement.** `n` items belong to at most one of `m` groups (`group[i] = -1` if none). Given `beforeItems[i]` (items that must precede item `i`), return an ordering of all items such that (a) item dependencies hold and (b) items of the same group are **contiguous**, respecting group dependencies. Return `[]` if impossible.

**Constraints.** `1 ≤ m ≤ n ≤ 3·10⁴`, total `beforeItems` up to `6·10⁴`.

**Approach.** A **two-level (nested) topological sort** (LeetCode 1203). First give every group-less item its own unique group id (so it sorts independently). Build two DAGs: an **item-level** graph (within each group) and a **group-level** graph (between groups). For each dependency `before → item`: if they're in different groups, add a group edge; always add the item edge. Topologically sort groups, then within each group topologically sort its items. Concatenate items group-by-group in group-topo order. A cycle at either level ⇒ `[]`.

```
items {0,1} in group A, {2,3} in group B; B depends on A
group topo:  A, B            item topo in A: 0,1     item topo in B: 2,3
final order: [0,1,2,3]       (groups contiguous, deps respected)
```

```java
class Solution {
    public int[] sortItems(int n, int m, int[] group, List<List<Integer>> beforeItems) {
        for (int i = 0; i < n; i++) if (group[i] == -1) group[i] = m++;  // own group per loner

        List<List<Integer>> itemAdj = new ArrayList<>(), groupAdj = new ArrayList<>();
        int[] itemIn = new int[n], groupIn = new int[m];
        for (int i = 0; i < n; i++) itemAdj.add(new ArrayList<>());
        for (int i = 0; i < m; i++) groupAdj.add(new ArrayList<>());

        for (int i = 0; i < n; i++)
            for (int before : beforeItems.get(i)) {
                itemAdj.get(before).add(i); itemIn[i]++;            // item-level edge
                if (group[before] != group[i]) {                   // cross-group ⇒ group edge
                    groupAdj.get(group[before]).add(group[i]); groupIn[group[i]]++;
                }
            }

        List<Integer> groupOrder = topo(groupAdj, groupIn, m);
        if (groupOrder == null) return new int[0];
        List<Integer> itemOrder = topo(itemAdj, itemIn, n);
        if (itemOrder == null) return new int[0];

        Map<Integer, List<Integer>> grouped = new HashMap<>();      // items per group, in item-topo order
        for (int item : itemOrder) grouped.computeIfAbsent(group[item], k -> new ArrayList<>()).add(item);

        int[] res = new int[n]; int idx = 0;
        for (int g : groupOrder)
            for (int item : grouped.getOrDefault(g, Collections.emptyList()))
                res[idx++] = item;
        return res;
    }
    private List<Integer> topo(List<List<Integer>> adj, int[] indeg, int size) {
        Deque<Integer> q = new ArrayDeque<>();
        for (int i = 0; i < size; i++) if (indeg[i] == 0) q.offer(i);
        List<Integer> order = new ArrayList<>();
        while (!q.isEmpty()) {
            int u = q.poll(); order.add(u);
            for (int v : adj.get(u)) if (--indeg[v] == 0) q.offer(v);
        }
        return order.size() == size ? order : null;                 // null ⇒ cycle
    }
}
```

**Dry run.** `n=8, m=2`, `group=[-1,-1,1,0,0,1,0,-1]`, `beforeItems=[[],[6],[5],[6],[3,6],[],[],[]]`. Loners get fresh group ids; group topo and item topo are computed; concatenating items by group yields a valid order such as `[6,3,4,5,2,0,7,1]` (one valid LeetCode answer).

**Complexity.** Time `O(n + m + E)`, space `O(n + m + E)`. **Edge cases:** group-less items each become a singleton group so they place freely; a cycle at the item **or** group level returns `[]`; items must end up contiguous within their group — achieved by bucketing the item-topo order under each group then emitting groups in group-topo order.

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
