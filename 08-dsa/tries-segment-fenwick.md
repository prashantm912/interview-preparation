# Trie, Segment Tree & Fenwick Tree

Three specialized structures that turn otherwise quadratic problems into logarithmic ones: **Tries** for prefix/string queries, **Segment Trees** for flexible range queries with updates, and **Fenwick/Binary Indexed Trees (BIT)** for fast prefix aggregates. Together they form the backbone of search engines, range analytics, and competitive-programming "range query" problems.

[← Back to master index](../README.md) · [← DSA index](README.md)

---

## Concept & Intuition

### Trie (Prefix Tree)
A **Trie** is an n-ary tree where each edge is labeled with a character and each root-to-node path spells a prefix. Words sharing a prefix share a path, so lookups and prefix queries cost `O(L)` (length of the word) instead of `O(N·L)` over a list. Use it when you need **prefix matching, autocomplete, dictionary membership, or grid word search**.

**Invariant:** a node marks `isEnd = true` exactly when a path from the root to it spells a complete inserted word. Children are keyed by character (array of 26 for lowercase, or a `HashMap` for larger alphabets).

```
Insert: "cat", "car", "dog"
              (root)
              /     \
            c        d
            |        |
            a        o
           / \       |
          t*  r*     g*      (* = isEnd)
```
A `startsWith("ca")` walk stops at node `a` and returns true; `search("ca")` returns false because `a.isEnd` is false.

### Segment Tree
A **Segment Tree** is a balanced binary tree over array indices. Each leaf is one element; each internal node stores an aggregate (sum, min, max, gcd…) of its range. Built in `O(N)`, it answers any range query and point/range update in `O(log N)`. Choose it when the aggregate is **associative and you need both queries AND updates** — and especially when you need **range updates** via *lazy propagation*.

```
Array: [2, 1, 5, 3]   (sum tree)
                [0..3]=11
              /           \
        [0..1]=3        [2..3]=8
        /     \         /     \
    [0]=2   [1]=1   [2]=5   [3]=3
```
A query for `sum(1..2)` descends, combining `[1]=1` and `[2]=5` → 6.

**Lazy propagation invariant:** a `lazy[node]` value is a *pending* update that applies to the node's whole range but has not yet been pushed to its children. Always `push down` before recursing into children.

### Fenwick Tree / Binary Indexed Tree (BIT)
A **Fenwick Tree** is an array `tree[1..n]` where index `i` is responsible for the range `(i - lowbit(i), i]`, with `lowbit(i) = i & (-i)`. It gives prefix sums and point updates in `O(log N)` with far less code and memory than a segment tree. Use it for **prefix sums, point updates, counting inversions, and order statistics** — when the operation is invertible (so range = prefix(r) − prefix(l−1)).

```
lowbit moves:  query(i): i -= i&(-i)   update(i): i += i&(-i)
i=6 (110): covers (4,6]   parent in update = 8
i=4 (100): covers (0,4]
```

**Invariant:** `tree[i]` stores the partial aggregate of exactly the last `lowbit(i)` elements ending at `i`.

---

## Complexity Cheat-Sheet

| Structure | Operation | Time | Space |
|-----------|-----------|------|-------|
| Trie | insert / search / startsWith | `O(L)` | `O(Σ · N · L)` worst |
| Trie | delete | `O(L)` | — |
| Segment Tree | build | `O(N)` | `O(4N)` |
| Segment Tree | point update | `O(log N)` | — |
| Segment Tree | range query | `O(log N)` | — |
| Segment Tree | range update (lazy) | `O(log N)` | `O(4N)` extra for lazy |
| Fenwick (BIT) | build | `O(N log N)` or `O(N)` | `O(N)` |
| Fenwick (BIT) | point update | `O(log N)` | — |
| Fenwick (BIT) | prefix / range query | `O(log N)` | — |
| Fenwick 2D | update / query | `O(log²N)` | `O(N·M)` |

`L` = word length, `Σ` = alphabet size, `N` = number of elements.

---

## Patterns & Recognition

- **Words / prefixes / autocomplete / dictionary** → **Trie**. Keywords: "starts with", "common prefix", "spell-check", "replace words by root", "word search on a board".
- **Range query + updates, non-invertible aggregate (min/max/gcd)** → **Segment Tree**.
- **Range *update* a whole interval + range query** → **Segment Tree with lazy propagation**.
- **Prefix sums with point updates, or "count of elements ≤ x so far"** → **Fenwick Tree** (simplest, fastest constant).
- **Count inversions / smaller-after-self / reverse pairs** → coordinate-compress + **Fenwick**.
- **Static range query, no updates** → a plain prefix-sum array or **sparse table** beats both; don't over-engineer.
- Heuristic: if you only ever read, use prefix sums. If you read + point-update an invertible op, use Fenwick. If you need min/max/gcd or range updates, use a segment tree.

---

## Coding Problems

### Problem 1: Implement Trie (Insert / Search / StartsWith)
**Statement:** Build a `Trie` supporting `insert(word)`, `search(word)` (exact word present), and `startsWith(prefix)`. Words are lowercase `a–z`, total length ≤ 3·10⁴. (LeetCode 208.)

**Approach:** Brute force stores words in a `HashSet`; `search` is `O(1)` but `startsWith` is `O(N·L)` because you must scan every word. Optimal: a 26-ary trie gives `O(L)` for all three.

```java
class Trie {
    private final Trie[] next = new Trie[26];
    private boolean isEnd = false;

    public void insert(String word) {
        Trie node = this;
        for (char c : word.toCharArray()) {
            int i = c - 'a';
            if (node.next[i] == null) node.next[i] = new Trie();
            node = node.next[i];
        }
        node.isEnd = true;
    }

    private Trie walk(String s) {
        Trie node = this;
        for (char c : s.toCharArray()) {
            node = node.next[c - 'a'];
            if (node == null) return null;
        }
        return node;
    }

    public boolean search(String word) {
        Trie node = walk(word);
        return node != null && node.isEnd;
    }

    public boolean startsWith(String prefix) {
        return walk(prefix) != null;
    }
}
```
**Dry run:** `insert("app")`, `insert("apple")`. `search("app")` → walk lands on `p` with `isEnd=true` → true. `search("ap")` → walk lands on second `p`'s parent... actually node `p` (first) `isEnd=false` → false. `startsWith("ap")` → node exists → true.

**Time:** `O(L)` per op. **Space:** `O(26·total chars)`.
**Follow-ups:** support deletion (decrement counts), Unicode (use `HashMap<Character,Node>`), case-insensitive, count words with a given prefix (store `prefixCount`).

---

### Problem 2: Range Sum Query — Mutable (Fenwick)
**Statement:** Given an array, support `update(index, val)` and `sumRange(l, r)` interleaved. (LeetCode 307.)

**Approach:** Brute force: `update` is `O(1)`, but `sumRange` is `O(N)`. A prefix-sum array flips it (`O(1)` query, `O(N)` update). A **Fenwick tree** balances both at `O(log N)`.

```java
class NumArray {
    private final int[] tree;   // 1-indexed BIT
    private final int[] nums;
    private final int n;

    public NumArray(int[] nums) {
        this.n = nums.length;
        this.nums = new int[n];
        this.tree = new int[n + 1];
        for (int i = 0; i < n; i++) update(i, nums[i]);
    }

    public void update(int index, int val) {
        int delta = val - nums[index];
        nums[index] = val;
        for (int i = index + 1; i <= n; i += i & (-i)) tree[i] += delta;
    }

    private int prefix(int i) {           // sum of nums[0..i-1]
        int s = 0;
        for (; i > 0; i -= i & (-i)) s += tree[i];
        return s;
    }

    public int sumRange(int l, int r) {
        return prefix(r + 1) - prefix(l);
    }
}
```
**Dry run:** nums `[1,3,5]`. `sumRange(0,2)` = prefix(3) − prefix(0) = 9. `update(1,2)` sets delta = 2−3 = −1, propagates up. `sumRange(0,2)` now 8.

**Time:** `O(log N)` update/query, `O(N log N)` build. **Space:** `O(N)`.
**Follow-ups:** range update + point query (store deltas), 2D BIT for matrix, replace with segment tree if aggregate were min/max.

---

### Problem 3: Range Minimum Query (Segment Tree)
**Statement:** Support `update(i, val)` and `queryMin(l, r)` on an array. Aggregate is `min`, which is **not invertible**, so Fenwick won't work directly — use a segment tree.

**Approach:** Build a tree where each node holds the min of its range. Recurse for queries, combining only overlapping segments.

```java
class SegTreeMin {
    private final int[] tree;
    private final int n;

    SegTreeMin(int[] a) {
        n = a.length;
        tree = new int[4 * n];
        build(a, 1, 0, n - 1);
    }
    private void build(int[] a, int node, int lo, int hi) {
        if (lo == hi) { tree[node] = a[lo]; return; }
        int mid = (lo + hi) >>> 1;
        build(a, 2 * node, lo, mid);
        build(a, 2 * node + 1, mid + 1, hi);
        tree[node] = Math.min(tree[2 * node], tree[2 * node + 1]);
    }
    void update(int idx, int val) { update(1, 0, n - 1, idx, val); }
    private void update(int node, int lo, int hi, int idx, int val) {
        if (lo == hi) { tree[node] = val; return; }
        int mid = (lo + hi) >>> 1;
        if (idx <= mid) update(2 * node, lo, mid, idx, val);
        else update(2 * node + 1, mid + 1, hi, idx, val);
        tree[node] = Math.min(tree[2 * node], tree[2 * node + 1]);
    }
    int queryMin(int l, int r) { return query(1, 0, n - 1, l, r); }
    private int query(int node, int lo, int hi, int l, int r) {
        if (r < lo || hi < l) return Integer.MAX_VALUE;   // no overlap
        if (l <= lo && hi <= r) return tree[node];          // total overlap
        int mid = (lo + hi) >>> 1;
        return Math.min(query(2 * node, lo, mid, l, r),
                        query(2 * node + 1, mid + 1, hi, l, r));
    }
}
```
**Dry run:** `[5,2,7,1]`. `queryMin(0,2)` combines leaves 5,2,7 → 2. `update(1,9)` rebuilds parents; `queryMin(0,2)` → 5.

**Time:** `O(log N)` per op, `O(N)` build. **Space:** `O(4N)`.
**Follow-ups:** swap `Math.min` for max/gcd/sum, return the *index* of the minimum, persistent segment tree for versioned queries.

---

### Problem 4: Maximum XOR of Two Numbers in an Array (Bitwise Trie)
**Statement:** Given `nums`, return the maximum value of `nums[i] XOR nums[j]`. (LeetCode 421.) `0 ≤ nums[i] < 2³¹`.

**Approach:** Brute force is `O(N²)`. Optimal: insert each number's 32-bit representation into a **binary trie** (children 0/1, MSB first). For each number, greedily walk the trie choosing the opposite bit when possible to maximize XOR — `O(32N)`.

```java
class Solution {
    static class Node { Node[] c = new Node[2]; }
    public int findMaximumXOR(int[] nums) {
        Node root = new Node();
        for (int x : nums) {                       // insert
            Node cur = root;
            for (int b = 31; b >= 0; b--) {
                int bit = (x >> b) & 1;
                if (cur.c[bit] == null) cur.c[bit] = new Node();
                cur = cur.c[bit];
            }
        }
        int best = 0;
        for (int x : nums) {                       // query best partner
            Node cur = root;
            int cur_xor = 0;
            for (int b = 31; b >= 0; b--) {
                int bit = (x >> b) & 1;
                if (cur.c[bit ^ 1] != null) {       // prefer opposite bit
                    cur_xor |= (1 << b);
                    cur = cur.c[bit ^ 1];
                } else {
                    cur = cur.c[bit];
                }
            }
            best = Math.max(best, cur_xor);
        }
        return best;
    }
}
```
**Dry run:** `[3,10,5,25,2,8]`. The best pair is 5 (00101) XOR 25 (11001) = 28; walking 5 down the trie greedily flips toward 25's bits.

**Time:** `O(32N)`. **Space:** `O(32N)`.
**Follow-ups:** maximum XOR with an element ≤ a limit (LeetCode 1707, offline + trie), count pairs with XOR < K.

---

### Problem 5: Replace Words (Trie + Roots)
**Statement:** Given a `dictionary` of roots and a `sentence`, replace every word with the **shortest root** that is its prefix. (LeetCode 648.)

**Approach:** Brute force checks each word against every root: `O(words · roots · L)`. Optimal: insert all roots into a trie, then for each word walk the trie and stop at the first `isEnd` node — `O(total chars)`.

```java
class Solution {
    static class Node { Node[] c = new Node[26]; boolean end; }
    public String replaceWords(List<String> dictionary, String sentence) {
        Node root = new Node();
        for (String w : dictionary) {
            Node cur = root;
            for (char ch : w.toCharArray()) {
                int i = ch - 'a';
                if (cur.c[i] == null) cur.c[i] = new Node();
                cur = cur.c[i];
            }
            cur.end = true;
        }
        String[] words = sentence.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            sb.append(shortestRoot(root, w)).append(' ');
        }
        return sb.toString().trim();
    }
    private String shortestRoot(Node root, String w) {
        Node cur = root;
        for (int i = 0; i < w.length(); i++) {
            int idx = w.charAt(i) - 'a';
            if (cur.c[idx] == null) break;
            cur = cur.c[idx];
            if (cur.end) return w.substring(0, i + 1);
        }
        return w;
    }
}
```
**Dry run:** roots `["cat","bat","rat"]`, sentence `"the cattle was rattled"`. `"cattle"` walks `c-a-t` and hits `end` → replaced by `"cat"`. `"the"` matches no root → unchanged.

**Time:** `O(total chars)`. **Space:** `O(roots · L)`.
**Follow-ups:** longest matching root instead of shortest, multiple replacements, weighted roots.

---

### Problem 6: Count of Smaller Numbers After Self (Fenwick + Coordinate Compression)
**Statement:** For each `nums[i]`, count how many `nums[j]` with `j > i` are smaller. Return the counts array. (LeetCode 315.)

**Approach:** Brute force `O(N²)`. Optimal: traverse **right to left**, coordinate-compress values to ranks, and use a Fenwick tree to query "how many values strictly less than the current rank have I already seen?" — `O(N log N)`.

```java
class Solution {
    public List<Integer> countSmaller(int[] nums) {
        int n = nums.length;
        int[] sorted = nums.clone();
        Arrays.sort(sorted);
        // compress: rank 1..m
        TreeMap<Integer, Integer> rank = new TreeMap<>();
        int r = 0;
        for (int v : sorted) if (!rank.containsKey(v)) rank.put(v, ++r);
        int[] bit = new int[r + 1];
        Integer[] res = new Integer[n];
        for (int i = n - 1; i >= 0; i--) {
            int x = rank.get(nums[i]);
            res[i] = query(bit, x - 1);    // count of strictly smaller seen
            update(bit, x, r);
        }
        return Arrays.asList(res);
    }
    private void update(int[] bit, int i, int n) {
        for (; i <= n; i += i & (-i)) bit[i]++;
    }
    private int query(int[] bit, int i) {
        int s = 0;
        for (; i > 0; i -= i & (-i)) s += bit[i];
        return s;
    }
}
```
**Dry run:** `[5,2,6,1]` → ranks `{1:1,2:2,5:3,6:4}`. From the right: `1` (rank1) sees 0 → 0; `6` (rank4) sees {1} below → 1; `2` (rank2) sees {1} → 1; `5` (rank3) sees {1,2} → 2. Result `[2,1,1,0]`.

**Time:** `O(N log N)`. **Space:** `O(N)`.
**Follow-ups:** count inversions (sum the result), reverse pairs (LeetCode 493 needs careful querying of `2·nums[j]`), count of range sums.

---

### Problem 7: Range Update / Range Sum (Segment Tree with Lazy Propagation)
**Statement:** Support `updateRange(l, r, val)` (add `val` to every element in `[l,r]`) and `queryRange(l, r)` (sum). Both must be `O(log N)`.

**Approach:** A naive range update touches `O(N)` leaves. **Lazy propagation** defers child updates: store a pending `lazy[node]`, apply it to the node's aggregate immediately, and push down only when you must descend.

```java
class LazySegTree {
    private final long[] tree, lazy;
    private final int n;

    LazySegTree(int[] a) {
        n = a.length;
        tree = new long[4 * n];
        lazy = new long[4 * n];
        build(a, 1, 0, n - 1);
    }
    private void build(int[] a, int node, int lo, int hi) {
        if (lo == hi) { tree[node] = a[lo]; return; }
        int mid = (lo + hi) >>> 1;
        build(a, 2 * node, lo, mid);
        build(a, 2 * node + 1, mid + 1, hi);
        tree[node] = tree[2 * node] + tree[2 * node + 1];
    }
    private void applyLazy(int node, int lo, int hi, long val) {
        tree[node] += (hi - lo + 1) * val;   // sum grows by count * val
        lazy[node] += val;
    }
    private void pushDown(int node, int lo, int hi) {
        if (lazy[node] != 0) {
            int mid = (lo + hi) >>> 1;
            applyLazy(2 * node, lo, mid, lazy[node]);
            applyLazy(2 * node + 1, mid + 1, hi, lazy[node]);
            lazy[node] = 0;
        }
    }
    void updateRange(int l, int r, long val) { update(1, 0, n - 1, l, r, val); }
    private void update(int node, int lo, int hi, int l, int r, long val) {
        if (r < lo || hi < l) return;
        if (l <= lo && hi <= r) { applyLazy(node, lo, hi, val); return; }
        pushDown(node, lo, hi);
        int mid = (lo + hi) >>> 1;
        update(2 * node, lo, mid, l, r, val);
        update(2 * node + 1, mid + 1, hi, l, r, val);
        tree[node] = tree[2 * node] + tree[2 * node + 1];
    }
    long queryRange(int l, int r) { return query(1, 0, n - 1, l, r); }
    private long query(int node, int lo, int hi, int l, int r) {
        if (r < lo || hi < l) return 0;
        if (l <= lo && hi <= r) return tree[node];
        pushDown(node, lo, hi);
        int mid = (lo + hi) >>> 1;
        return query(2 * node, lo, mid, l, r)
             + query(2 * node + 1, mid + 1, hi, l, r);
    }
}
```
**Dry run:** `[0,0,0,0]`. `updateRange(0,2,5)` marks the covering nodes lazily; `queryRange(1,3)` pushes down to read indices 1,2 (=5 each) and 3 (=0) → 10.

**Time:** `O(log N)` per op. **Space:** `O(4N)` tree + `O(4N)` lazy.
**Follow-ups:** range *assign* (replace, not add — lazy needs a "has-assignment" flag), range min/max with lazy, combine add + min (Chtholly/segment-beats).

---

### Problem 8: Word Search II (Trie + DFS Backtracking) — Hard
**Statement:** Given an `m×n` board of letters and a list of `words`, return all words found on the board (adjacent cells horizontally/vertically, no cell reused per word). (LeetCode 212.) Up to 3·10⁴ words.

**Approach:** Running DFS per word is `O(words · m·n · 4^L)` — far too slow. Build **one trie** of all words, then DFS the board *once*, advancing the trie pointer in lockstep. Prune dead branches; store the full word at terminal nodes to avoid rebuilding strings. A classic optimization is to **clip leaves** after a word is found.

```java
class Solution {
    static class Node {
        Node[] c = new Node[26];
        String word;            // non-null at terminal => the word
    }
    private int rows, cols;
    private char[][] board;
    private final List<String> result = new ArrayList<>();

    public List<String> findWords(char[][] board, String[] words) {
        Node root = build(words);
        this.board = board; rows = board.length; cols = board[0].length;
        for (int r = 0; r < rows; r++)
            for (int col = 0; col < cols; col++)
                dfs(r, col, root);
        return result;
    }
    private Node build(String[] words) {
        Node root = new Node();
        for (String w : words) {
            Node cur = root;
            for (char ch : w.toCharArray()) {
                int i = ch - 'a';
                if (cur.c[i] == null) cur.c[i] = new Node();
                cur = cur.c[i];
            }
            cur.word = w;
        }
        return root;
    }
    private void dfs(int r, int col, Node parent) {
        if (r < 0 || r >= rows || col < 0 || col >= cols) return;
        char ch = board[r][col];
        if (ch == '#') return;                  // visited
        Node node = parent.c[ch - 'a'];
        if (node == null) return;               // prune: no such prefix
        if (node.word != null) {                // found a word
            result.add(node.word);
            node.word = null;                   // avoid duplicates
        }
        board[r][col] = '#';
        dfs(r + 1, col, node);
        dfs(r - 1, col, node);
        dfs(r, col + 1, node);
        dfs(r, col - 1, node);
        board[r][col] = ch;                     // restore
    }
}
```
**Dry run:** board with `oath` spelled along a path and trie containing `"oath"`: DFS from `o` follows the trie to the `h` node where `word="oath"`, adds it, nulls it so a second path won't re-add.

**Time:** `O(m·n·4^L)` worst, but the trie prunes almost everything in practice. **Space:** `O(total chars)` for the trie.
**Follow-ups:** also remove fully-consumed trie branches to speed later cells, return words with their starting coordinates, allow diagonal moves, handle very large alphabets via `HashMap` children.

---

### Problem 9: 2D Range Sum — Mutable (2D Fenwick) — Senior
**Statement:** Given an `m×n` matrix, support `update(row, col, val)` and `sumRegion(r1,c1,r2,c2)`. (LeetCode 308.) Updates and queries interleave heavily.

**Approach:** Extend the 1D BIT to two dimensions: `tree[i][j]` covers a 2D rectangle defined by `lowbit` on each axis. Both operations cost `O(log m · log n)`.

```java
class NumMatrix {
    private final int[][] tree;
    private final int[][] nums;
    private final int m, n;

    public NumMatrix(int[][] matrix) {
        m = matrix.length; n = matrix[0].length;
        tree = new int[m + 1][n + 1];
        nums = new int[m][n];
        for (int i = 0; i < m; i++)
            for (int j = 0; j < n; j++) update(i, j, matrix[i][j]);
    }
    public void update(int row, int col, int val) {
        int delta = val - nums[row][col];
        nums[row][col] = val;
        for (int i = row + 1; i <= m; i += i & (-i))
            for (int j = col + 1; j <= n; j += j & (-j))
                tree[i][j] += delta;
    }
    private int prefix(int row, int col) {        // sum of [0..row-1][0..col-1]
        int s = 0;
        for (int i = row; i > 0; i -= i & (-i))
            for (int j = col; j > 0; j -= j & (-j))
                s += tree[i][j];
        return s;
    }
    public int sumRegion(int r1, int c1, int r2, int c2) {
        return prefix(r2 + 1, c2 + 1) - prefix(r1, c2 + 1)
             - prefix(r2 + 1, c1) + prefix(r1, c1);   // inclusion-exclusion
    }
}
```
**Dry run:** A 3×3 of ones. `sumRegion(0,0,1,1)` = prefix(2,2) − prefix(0,2) − prefix(2,0) + prefix(0,0) = 4. `update(1,1,5)` then `sumRegion(0,0,1,1)` = 8.

**Time:** `O(log m · log n)` per op. **Space:** `O(m·n)`.
**Follow-ups:** 2D range-update/range-query (four BITs), immutable version uses a static 2D prefix-sum array in `O(1)` query.

---

## 🧩 Extended Problems — Set 1: Classic Easy → Medium

### Problem 10: Add and Search Word — Wildcard Matching (Trie + DFS)
**Statement.** Design a data structure that supports `addWord(word)` and `search(word)`, where `search` may contain the wildcard `'.'` that matches **any single letter**. Words are lowercase `a–z`. (LeetCode 211 — "Design Add and Search Words Data Structure".)

**Constraints.** ≤ 10⁴ calls total; word length ≤ 25; up to ~3·10⁴ stored characters.

**Approach.** A `HashSet` cannot answer wildcard queries without scanning every word (`O(N·L)` per search). A **trie** lets a normal letter follow exactly one edge in `O(1)`, while a `'.'` branches into **all 26 children** via DFS. Because each `'.'` multiplies the search frontier, worst-case search is `O(26^k · L)` where `k` is the number of dots, but real queries have few dots so it is effectively `O(L)`. Insertion is plain trie insertion.

```
search("b.d") on {bad, bbd}:
        root
         |b
        (b)
       /    \
     a        b
     |d*      |d*
 match '.' -> try every child of (b): a-branch reaches d*, success
```

```java
class WordDictionary {
    private static class Node {
        Node[] c = new Node[26];
        boolean end;
    }
    private final Node root = new Node();

    public void addWord(String word) {
        Node cur = root;
        for (char ch : word.toCharArray()) {
            int i = ch - 'a';
            if (cur.c[i] == null) cur.c[i] = new Node();
            cur = cur.c[i];
        }
        cur.end = true;
    }

    public boolean search(String word) {
        return dfs(word, 0, root);
    }

    private boolean dfs(String word, int idx, Node node) {
        if (node == null) return false;
        if (idx == word.length()) return node.end;
        char ch = word.charAt(idx);
        if (ch == '.') {
            for (Node child : node.c)
                if (child != null && dfs(word, idx + 1, child)) return true;
            return false;
        }
        return dfs(word, idx + 1, node.c[ch - 'a']);
    }
}
```
**Dry run.** Add `"bad","dad","mad"`. `search("pad")` → no `p` child of root → false. `search(".ad")` → root tries every child; `b`,`d`,`m` each lead to `a-d*` → true. `search("b..")` → from `b`, both dots expand and reach an end node → true.

**Complexity.** `addWord` `O(L)`; `search` `O(L)` without dots, `O(26^k·L)` worst with `k` dots. **Space.** `O(26 · total chars)`. **Edge cases.** empty word (root.end), all-dots query (matches any stored word of that length), querying before any insert (returns false).

---

### Problem 11: Longest Common Prefix (Trie)
**Statement.** Given an array of strings, return the longest common prefix shared by **all** of them; return `""` if none. (LeetCode 14.)

**Constraints.** 1 ≤ strings ≤ 200; each length ≤ 200; lowercase English letters.

**Approach.** The classic vertical-scan solution is `O(N·minLen)` and is simplest, but the canonical *trie* framing is instructive and reusable: insert every word, then walk down from the root **as long as the current node has exactly one child and is not the end of a shorter word**. The moment a node branches (≥2 children) or terminates a word, the common prefix ends. This generalizes to "longest prefix shared by a queried subset" once the trie exists.

```java
class Solution {
    static class Node {
        Node[] c = new Node[26];
        boolean end;
        int childCount;
    }
    public String longestCommonPrefix(String[] strs) {
        if (strs.length == 0) return "";
        Node root = new Node();
        for (String w : strs) {
            if (w.isEmpty()) return "";          // empty word kills any prefix
            Node cur = root;
            for (char ch : w.toCharArray()) {
                int i = ch - 'a';
                if (cur.c[i] == null) { cur.c[i] = new Node(); cur.childCount++; }
                cur = cur.c[i];
            }
            cur.end = true;
        }
        StringBuilder sb = new StringBuilder();
        Node cur = root;
        // walk while exactly one path forward and no word ends here
        while (cur.childCount == 1 && !cur.end) {
            int next = -1;
            for (int i = 0; i < 26; i++) if (cur.c[i] != null) { next = i; break; }
            sb.append((char) ('a' + next));
            cur = cur.c[next];
        }
        return sb.toString();
    }
}
```
**Dry run.** `["flower","flow","flight"]`: walk `f`(childCount 1) → `l`(1) → at this node two children `o` and `i` (childCount 2) → stop. Prefix `"fl"`. `["dog","cat"]`: root has 2 children → empty.

**Complexity.** Time `O(total chars)` to build + `O(prefix length)` to walk. **Space.** `O(total chars)`. **Edge cases.** any empty string → `""`; single string → itself; a word that is a prefix of another (`["ab","abc"]` → `"ab"`, caught by `cur.end`).

---

### Problem 12: Range Sum Query — Immutable (Prefix Sums)
**Statement.** Given an immutable array, answer many `sumRange(l, r)` queries in `O(1)`. (LeetCode 303.)

**Constraints.** Up to 10⁴ queries; `-10⁵ ≤ nums[i] ≤ 10⁵`; length ≤ 10⁴.

**Approach.** This is the *baseline* that motivates Fenwick/segment trees: when there are **no updates**, you never need a tree. Precompute `pre[i] = nums[0] + … + nums[i-1]` once in `O(N)`; then `sumRange(l,r) = pre[r+1] − pre[l]` in `O(1)`. Recognizing this prevents over-engineering — a BIT here would be strictly worse (`O(log N)` query, more memory).

```
nums  = [ -2, 0, 3, -5, 2, -1 ]
pre   = [0, -2, -2, 1, -4, -2, -3]   (pre[0]=0, pre[i+1]=pre[i]+nums[i])
sumRange(2,5) = pre[6] - pre[2] = -3 - (-2) = -1
```

```java
class NumArray {
    private final long[] pre;          // pre[i] = sum of nums[0..i-1]
    public NumArray(int[] nums) {
        pre = new long[nums.length + 1];
        for (int i = 0; i < nums.length; i++) pre[i + 1] = pre[i] + nums[i];
    }
    public int sumRange(int left, int right) {
        return (int) (pre[right + 1] - pre[left]);
    }
}
```
**Dry run.** `[-2,0,3,-5,2,-1]`. `sumRange(0,2)` = `pre[3]-pre[0]` = 1. `sumRange(2,5)` = `pre[6]-pre[2]` = `-3-(-2)` = -1.

**Complexity.** Build `O(N)`, query `O(1)`. **Space.** `O(N)`. **Edge cases.** `l == r` (single element), `l == 0` (uses `pre[0]=0`), use `long` to avoid overflow when many large values accumulate.

---

### Problem 13: Map Sum Pairs (Trie with Prefix Aggregates)
**Statement.** Implement `MapSum`: `insert(key, val)` stores/overwrites a key-value pair, and `sum(prefix)` returns the total of all values whose key **starts with** `prefix`. (LeetCode 677.)

**Constraints.** ≤ 50 calls each; keys lowercase, length ≤ 50; values are integers.

**Approach.** Brute force keeps a map and scans all keys per `sum` (`O(N·L)`). The trie answer stores a running `sum` field on **every node along a key's path**, so `sum(prefix)` is just a walk to the prefix node returning its aggregate in `O(L)`. The subtlety is **overwrites**: if a key is reinserted with a new value, propagate the **delta** (`new − old`) so node sums stay correct.

```java
class MapSum {
    private static class Node {
        Node[] c = new Node[26];
        int sum;                       // sum of all values passing through here
    }
    private final Node root = new Node();
    private final Map<String, Integer> vals = new HashMap<>();

    public void insert(String key, int val) {
        int delta = val - vals.getOrDefault(key, 0);
        vals.put(key, val);
        Node cur = root;
        for (char ch : key.toCharArray()) {
            int i = ch - 'a';
            if (cur.c[i] == null) cur.c[i] = new Node();
            cur = cur.c[i];
            cur.sum += delta;          // update aggregate on every prefix node
        }
    }

    public int sum(String prefix) {
        Node cur = root;
        for (char ch : prefix.toCharArray()) {
            int i = ch - 'a';
            if (cur.c[i] == null) return 0;
            cur = cur.c[i];
        }
        return cur.sum;
    }
}
```
**Dry run.** `insert("apple",3)` → nodes `a,p,p,l,e` each `sum=3`. `sum("ap")` → node `p` → 3. `insert("app",2)` → delta 2 on `a,p,p`; now `sum("ap")` = 5. `insert("apple",5)` → delta `5-3=2` on the apple path; `sum("apple")` = 5.

**Complexity.** `insert` and `sum` both `O(L)`. **Space.** `O(26 · total chars)`. **Edge cases.** reinserting an existing key (delta handles it), prefix not present (returns 0), prefix equal to a full key.

---

### Problem 14: Range Sum of a Binary Search Tree — via Fenwick on Inorder (Counting variant)
**Statement.** Given `n`, process two kinds of operations on an initially empty multiset of integers in `[1, n]`: `add(x)` inserts `x`, and `countLessOrEqual(x)` returns how many stored values are `≤ x`. This is the core "order statistics / rank" primitive behind problems like *Count of Smaller Numbers* and online median tracking.

**Constraints.** `1 ≤ x ≤ n ≤ 10⁵`; up to 10⁵ operations interleaved.

**Approach.** A sorted list gives `O(N)` insert; a balanced BST works but is heavy. A **Fenwick tree of frequencies** is ideal: `add(x)` does `update(x, +1)`, and `countLessOrEqual(x)` is the prefix sum `query(x)` — both `O(log n)`. This is the canonical "BIT as a frequency/rank table over a bounded value range" pattern; coordinate-compress first if values are sparse.

```java
class RankCounter {
    private final int[] tree;          // 1-indexed frequency BIT
    private final int n;

    public RankCounter(int n) {
        this.n = n;
        this.tree = new int[n + 1];
    }
    public void add(int x) {           // insert one occurrence of value x
        for (int i = x; i <= n; i += i & (-i)) tree[i]++;
    }
    public int countLessOrEqual(int x) {   // how many stored values <= x
        int s = 0;
        for (int i = Math.min(x, n); i > 0; i -= i & (-i)) s += tree[i];
        return s;
    }
    public int countInRange(int lo, int hi) {        // values in [lo, hi]
        if (hi < lo) return 0;
        return countLessOrEqual(hi) - countLessOrEqual(lo - 1);
    }
}
```
**Dry run.** `n=8`. `add(3); add(5); add(3)`. `countLessOrEqual(4)` = 2 (two 3's). `countLessOrEqual(5)` = 3. `countInRange(4,8)` = `q(8)-q(3)` = `3-2` = 1.

**Complexity.** `add`/`query` `O(log n)`. **Space.** `O(n)`. **Edge cases.** duplicate values (frequencies accumulate), `x` clamped to `n` in queries, empty multiset returns 0, `lo-1 = 0` handled by the loop terminating.

---

### Problem 15: Number of Longest Increasing Subsequence — value-indexed BIT (LIS counting)
**Statement.** Given `nums`, return the number of **longest** strictly increasing subsequences. (LeetCode 673.)

**Constraints.** 1 ≤ length ≤ 2000; values fit in `int`.

**Approach.** The `O(N²)` DP is standard, but a **Fenwick tree keyed by value** that stores a `(maxLen, count)` pair per prefix turns it into `O(N log N)`. Coordinate-compress values to ranks. Sweeping left to right, for each `x` query the BIT over ranks `< rank(x)` for the best `(len, cnt)` among smaller values; the new state is `(len+1, cnt or 1)`; then merge it into the BIT at `rank(x)`. The merge combines two `(len,cnt)` pairs by taking the larger length, summing counts on ties.

```java
class Solution {
    private long[] bestLen;   // per BIT node: best length
    private long[] bestCnt;   // per BIT node: count achieving that length
    private int m;

    public int findNumberOfLIS(int[] nums) {
        int[] sorted = nums.clone();
        Arrays.sort(sorted);
        // compress to ranks 1..m (dedup)
        int[] uniq = Arrays.stream(sorted).distinct().toArray();
        m = uniq.length;
        bestLen = new long[m + 1];
        bestCnt = new long[m + 1];

        long bestLenAll = 0, bestCntAll = 0;
        for (int x : nums) {
            int r = lowerBound(uniq, x) + 1;          // rank in 1..m
            long[] q = query(r - 1);                  // best among smaller values
            long len = q[0] + 1;
            long cnt = (q[0] == 0) ? 1 : q[1];        // start fresh if none smaller
            update(r, len, cnt);
            if (len > bestLenAll) { bestLenAll = len; bestCntAll = cnt; }
            else if (len == bestLenAll) bestCntAll += cnt;
        }
        return (int) bestCntAll;
    }

    private int lowerBound(int[] a, int x) {          // first index with a[i] == x
        int lo = 0, hi = a.length;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (a[mid] < x) lo = mid + 1; else hi = mid; }
        return lo;
    }

    // merge a (len,cnt) state into node i and ancestors
    private void update(int i, long len, long cnt) {
        for (; i <= m; i += i & (-i)) {
            if (bestLen[i] < len) { bestLen[i] = len; bestCnt[i] = cnt; }
            else if (bestLen[i] == len) bestCnt[i] += cnt;
        }
    }
    // best (len,cnt) over ranks [1..i]
    private long[] query(int i) {
        long len = 0, cnt = 0;
        for (; i > 0; i -= i & (-i)) {
            if (bestLen[i] > len) { len = bestLen[i]; cnt = bestCnt[i]; }
            else if (bestLen[i] == len) cnt += bestCnt[i];
        }
        return new long[]{len, cnt};
    }
}
```
**Dry run.** `[1,3,5,4,7]`: LIS length 4 (`1,3,4,7` and `1,3,5,7`) → answer 2. Sweeping, value 7 queries smaller values and finds two distinct length-3 subsequences ending below 7, summing counts to 2.

**Complexity.** `O(N log N)`. **Space.** `O(N)`. **Edge cases.** all equal values (LIS length 1, count = N), strictly-increasing requirement (query `r-1`, not `r`, to exclude equal values), single element (answer 1).

---

### Problem 16: Search Suggestions System (Trie / Sorted + Prefix)
**Statement.** Given `products` and a `searchWord`, after each typed character of `searchWord` return up to **3** lexicographically smallest products that share the typed prefix. (LeetCode 1268.)

**Constraints.** ≤ 1000 products; total chars ≤ 2·10⁴; lowercase letters.

**Approach.** Sort the products once; then for each prefix, the 3 answers are the first ≤3 products at/after the prefix's lower bound (binary search), since sorting groups equal prefixes contiguously. This is `O(M log M + Q log M)`. The **trie** variant stores at each node the (up to) 3 smallest completions, giving `O(L)` per prefix and naturally supporting streaming inserts — shown below because it is the data-structure-centric answer.

```java
class Solution {
    static class Node {
        Node[] c = new Node[26];
        // up to 3 smallest words passing through this node
        List<String> top = new ArrayList<>(3);
    }
    public List<List<String>> suggestedProducts(String[] products, String searchWord) {
        Node root = new Node();
        Arrays.sort(products);                       // lexicographic
        for (String p : products) insert(root, p);

        List<List<String>> res = new ArrayList<>();
        Node cur = root;
        boolean dead = false;
        for (int i = 0; i < searchWord.length(); i++) {
            if (!dead) {
                Node nxt = cur.c[searchWord.charAt(i) - 'a'];
                if (nxt == null) dead = true; else cur = nxt;
            }
            res.add(dead ? List.of() : cur.top);
        }
        return res;
    }
    private void insert(Node root, String word) {
        Node cur = root;
        for (char ch : word.toCharArray()) {
            int i = ch - 'a';
            if (cur.c[i] == null) cur.c[i] = new Node();
            cur = cur.c[i];
            if (cur.top.size() < 3) cur.top.add(word);   // words inserted in sorted order
        }
    }
}
```
**Dry run.** products `["mobile","mouse","moneypot","monitor","mousepad"]`, search `"mouse"`. After `"m"` → `[mobile,moneypot,monitor]`; after `"mou"` → `[mouse,mousepad]`; after `"mouse"` → `[mouse,mousepad]`.

**Complexity.** Build `O(M log M + total chars)`; query `O(L)`. **Space.** `O(total chars)` plus ≤3 strings/node. **Edge cases.** prefix matches nothing midway (all later prefixes empty, tracked by `dead`), fewer than 3 matches, duplicate products.

---

### Problem 17: Find the Maximum (Static) — Range Maximum Query via Segment Tree
**Statement.** Support `update(i, val)` and `queryMax(l, r)` returning the maximum in `[l, r]`. Like RMQ but for `max`, which is non-invertible so Fenwick is awkward — a segment tree is the clean choice. (Mirrors LeetCode 307-style mutable queries.)

**Constraints.** ≤ 3·10⁴ elements; interleaved updates/queries up to 10⁵.

**Approach.** Identical skeleton to the min segment tree but combining with `Math.max`. The recursion classifies each node as *no overlap* (return `−∞`), *total overlap* (return stored max), or *partial* (recurse both children). Updates rewrite a leaf and recompute ancestors. This is the textbook RMQ-with-updates template; for a static array prefer a sparse table (`O(1)` query).

```java
class SegTreeMax {
    private final int[] tree;
    private final int n;

    SegTreeMax(int[] a) {
        n = a.length;
        tree = new int[4 * n];
        build(a, 1, 0, n - 1);
    }
    private void build(int[] a, int node, int lo, int hi) {
        if (lo == hi) { tree[node] = a[lo]; return; }
        int mid = (lo + hi) >>> 1;
        build(a, 2 * node, lo, mid);
        build(a, 2 * node + 1, mid + 1, hi);
        tree[node] = Math.max(tree[2 * node], tree[2 * node + 1]);
    }
    void update(int idx, int val) { update(1, 0, n - 1, idx, val); }
    private void update(int node, int lo, int hi, int idx, int val) {
        if (lo == hi) { tree[node] = val; return; }
        int mid = (lo + hi) >>> 1;
        if (idx <= mid) update(2 * node, lo, mid, idx, val);
        else            update(2 * node + 1, mid + 1, hi, idx, val);
        tree[node] = Math.max(tree[2 * node], tree[2 * node + 1]);
    }
    int queryMax(int l, int r) { return query(1, 0, n - 1, l, r); }
    private int query(int node, int lo, int hi, int l, int r) {
        if (r < lo || hi < l) return Integer.MIN_VALUE;   // no overlap
        if (l <= lo && hi <= r) return tree[node];        // total overlap
        int mid = (lo + hi) >>> 1;
        return Math.max(query(2 * node, lo, mid, l, r),
                        query(2 * node + 1, mid + 1, hi, l, r));
    }
}
```
**Dry run.** `[1,4,2,3]`. `queryMax(1,3)` → max(4,2,3) = 4. `update(1,0)` → leaf becomes 0; `queryMax(1,3)` → max(0,2,3) = 3.

**Complexity.** `O(log N)` per op, build `O(N)`. **Space.** `O(4N)`. **Edge cases.** single-element range, `Integer.MIN_VALUE` sentinel for empty overlap (safe since real values exceed it for max), out-of-range guards prevent index errors.

---

### Problem 18: Longest Word in Dictionary (Trie + BFS/DFS)
**Statement.** Given `words`, return the longest word that can be **built one character at a time** by other words in the list (every prefix of the answer must itself be a word). Tie-break by smallest lexicographic order. (LeetCode 720.)

**Constraints.** ≤ 1000 words; total chars ≤ 10⁶; lowercase letters.

**Approach.** Insert all words into a trie marking `isEnd`. Then DFS only along nodes whose `isEnd` is true at **every step** (so every prefix is a real word). Among all reachable end-nodes, keep the longest, breaking ties lexicographically — achieved naturally by exploring children `a→z` and only updating on strictly longer, or equal-length-but-smaller paths (the first found at a given depth in `a→z` order is smallest).

```java
class Solution {
    static class Node { Node[] c = new Node[26]; boolean end; }
    private String best = "";

    public String longestWord(String[] words) {
        Node root = new Node();
        for (String w : words) {
            Node cur = root;
            for (char ch : w.toCharArray()) {
                int i = ch - 'a';
                if (cur.c[i] == null) cur.c[i] = new Node();
                cur = cur.c[i];
            }
            cur.end = true;
        }
        dfs(root, new StringBuilder());
        return best;
    }
    private void dfs(Node node, StringBuilder path) {
        for (int i = 0; i < 26; i++) {               // a..z keeps lex order
            Node child = node.c[i];
            if (child != null && child.end) {        // every prefix must be a word
                path.append((char) ('a' + i));
                if (path.length() > best.length()) best = path.toString();
                dfs(child, path);
                path.deleteCharAt(path.length() - 1);
            }
        }
    }
}
```
**Dry run.** `["w","wo","wor","worl","world"]` → `"world"` (each prefix present). `["a","banana","app","appl","ap","apply","apple"]` → `"apple"` (built `a→ap→app→appl→apple`; `apply` ties length but `apple` < `apply`).

**Complexity.** Build `O(total chars)`; DFS `O(total chars)`. **Space.** `O(total chars)`. **Edge cases.** no buildable word (returns `""`), single-letter words always buildable, lexicographic tie-break enforced by `a→z` iteration and strict `>` comparison.

---

### Problem 19: Online Majority Element / Frequency Count in Range — Merge Sort Tree-lite via Fenwick of buckets
**Statement.** Given a fixed array, answer offline queries `countEqual(l, r, x)` = number of positions in `[l, r]` whose value equals `x`. A clean classic approach when `x` ranges over few distinct values is a **map from value → sorted list of indices** plus binary search; we show that plus the Fenwick-friendly framing.

**Constraints.** ≤ 10⁵ elements and queries; values fit in `int`.

**Approach.** For each value, store its occurrence indices in ascending order (they are naturally ascending if you scan left→right). Then `countEqual(l, r, x)` = (number of indices of `x` that are `≤ r`) − (number `< l`), each found by binary search (`upperBound`/`lowerBound`) in `O(log occ)`. This is the standard "indices bucketed by value" trick that underlies range-frequency and the *majority-in-range* problem (LeetCode 1157 uses the same buckets).

```java
class RangeFreq {
    private final Map<Integer, int[]> pos = new HashMap<>();

    public RangeFreq(int[] arr) {
        Map<Integer, List<Integer>> tmp = new HashMap<>();
        for (int i = 0; i < arr.length; i++)
            tmp.computeIfAbsent(arr[i], k -> new ArrayList<>()).add(i);
        for (Map.Entry<Integer, List<Integer>> e : tmp.entrySet()) {
            List<Integer> l = e.getValue();
            int[] a = new int[l.size()];
            for (int i = 0; i < a.length; i++) a[i] = l.get(i);
            pos.put(e.getKey(), a);                 // already ascending
        }
    }
    // count of indices in [l, r] whose value == x
    public int countEqual(int l, int r, int x) {
        int[] idx = pos.get(x);
        if (idx == null) return 0;
        return upperBound(idx, r) - lowerBound(idx, l);
    }
    private int lowerBound(int[] a, int key) {       // first index with a[i] >= key
        int lo = 0, hi = a.length;
        while (lo < hi) { int m = (lo + hi) >>> 1; if (a[m] < key) lo = m + 1; else hi = m; }
        return lo;
    }
    private int upperBound(int[] a, int key) {       // first index with a[i] > key
        int lo = 0, hi = a.length;
        while (lo < hi) { int m = (lo + hi) >>> 1; if (a[m] <= key) lo = m + 1; else hi = m; }
        return lo;
    }
}
```
**Dry run.** `arr=[2,2,1,2,3,2]`. Buckets: `2→[0,1,3,5]`, `1→[2]`, `3→[4]`. `countEqual(1,4,2)` = `upperBound([0,1,3,5],4) - lowerBound(...,1)` = `3 - 1` = 2 (positions 1 and 3).

**Complexity.** Build `O(N)`; query `O(log occ)`. **Space.** `O(N)`. **Edge cases.** value absent (returns 0), `l > r` (binary search yields 0), all elements equal, single occurrence.

---

### Problem 20: Counting Bits Prefix — Range Sum with Point Increment (Fenwick basics)
**Statement.** Process a stream of `m` events on positions `1..n`: `inc(p)` adds 1 at position `p`, and `rangeSum(l, r)` returns the total increments in `[l, r]`. This is the most elementary Fenwick application and the building block for sweep-line counting.

**Constraints.** `1 ≤ p, l, r ≤ n ≤ 2·10⁵`; up to 2·10⁵ operations.

**Approach.** A plain array makes `rangeSum` `O(N)`; prefix sums make `inc` `O(N)`. The **Fenwick tree** gives both `O(log n)`. Range sum is the invertible decomposition `prefix(r) − prefix(l−1)`. This problem isolates the BIT mechanics (the `i += i&(-i)` / `i -= i&(-i)` walks) without coordinate compression or pairing, making it the cleanest demonstration of why a BIT exists.

```
update(p): climb p, p+lowbit, ...   query(i): descend i, i-lowbit, ...
n=8, inc(5): touches 5(101),6(110),8(1000)
prefix(6): 6(110)+4(100) = tree[6]+tree[4]
```

```java
class FenwickCounter {
    private final long[] tree;
    private final int n;

    public FenwickCounter(int n) {
        this.n = n;
        this.tree = new long[n + 1];     // 1-indexed
    }
    public void inc(int p) {             // add 1 at position p
        for (int i = p; i <= n; i += i & (-i)) tree[i]++;
    }
    public void add(int p, long delta) { // generalized point update
        for (int i = p; i <= n; i += i & (-i)) tree[i] += delta;
    }
    private long prefix(int i) {         // sum of positions [1..i]
        long s = 0;
        for (; i > 0; i -= i & (-i)) s += tree[i];
        return s;
    }
    public long rangeSum(int l, int r) {
        if (r < l) return 0;
        return prefix(r) - prefix(l - 1);
    }
}
```
**Dry run.** `n=8`. `inc(5); inc(5); inc(2)`. `rangeSum(1,4)` = `prefix(4)-prefix(0)` = 1 (only position 2). `rangeSum(5,8)` = `prefix(8)-prefix(4)` = 2.

**Complexity.** `inc`/`add`/`rangeSum` all `O(log n)`. **Space.** `O(n)`. **Edge cases.** `l == 1` (uses `prefix(0)=0`), `r < l` guarded, `long` accumulator avoids overflow under heavy increments.

---

### Problem 21: Maximum Sum Subarray with Single Point Update — Segment Tree storing 4 aggregates
**Statement.** Support `update(i, val)` and `queryMaxSubarray()` returning the maximum-sum contiguous subarray of the whole array (subarray must be non-empty). This is the classic "GSS" segment-tree problem (SPOJ GSS1/GSS3).

**Constraints.** ≤ 10⁵ elements, ≤ 10⁵ updates; values may be negative.

**Approach.** Each node stores **four** values over its range: `total` (sum), `pre` (best prefix sum), `suf` (best suffix sum), and `best` (best subarray sum). Merging children: `total = L.total + R.total`; `pre = max(L.pre, L.total + R.pre)`; `suf = max(R.suf, R.total + L.suf)`; `best = max(L.best, R.best, L.suf + R.pre)` — the last term stitches a subarray crossing the midpoint. This generalizes Kadane to support updates in `O(log N)`.

```java
class MaxSubarraySegTree {
    private static class Node {
        long total, pre, suf, best;
        Node(long v) { total = pre = suf = best = v; }   // single element
    }
    private final Node[] tree;
    private final int n;

    MaxSubarraySegTree(int[] a) {
        n = a.length;
        tree = new Node[4 * n];
        build(a, 1, 0, n - 1);
    }
    private Node merge(Node l, Node r) {
        Node m = new Node(0);
        m.total = l.total + r.total;
        m.pre  = Math.max(l.pre, l.total + r.pre);
        m.suf  = Math.max(r.suf, r.total + l.suf);
        m.best = Math.max(Math.max(l.best, r.best), l.suf + r.pre);
        return m;
    }
    private void build(int[] a, int node, int lo, int hi) {
        if (lo == hi) { tree[node] = new Node(a[lo]); return; }
        int mid = (lo + hi) >>> 1;
        build(a, 2 * node, lo, mid);
        build(a, 2 * node + 1, mid + 1, hi);
        tree[node] = merge(tree[2 * node], tree[2 * node + 1]);
    }
    void update(int idx, int val) { update(1, 0, n - 1, idx, val); }
    private void update(int node, int lo, int hi, int idx, int val) {
        if (lo == hi) { tree[node] = new Node(val); return; }
        int mid = (lo + hi) >>> 1;
        if (idx <= mid) update(2 * node, lo, mid, idx, val);
        else            update(2 * node + 1, mid + 1, hi, idx, val);
        tree[node] = merge(tree[2 * node], tree[2 * node + 1]);
    }
    long queryMaxSubarray() { return tree[1].best; }
}
```
**Dry run.** `[-2,1,-3,4,-1,2,1,-5,4]`. `queryMaxSubarray()` = 6 (`4,-1,2,1`). `update(3,-4)` (4→-4) recomputes the root; the best subarray becomes `2,1` = 3.

**Complexity.** `O(log N)` per update, `O(1)` global query, `O(N)` build. **Space.** `O(4N)` nodes. **Edge cases.** all-negative array (returns the largest single element, since `best` starts at the element value), single element, crossing-midpoint subarray captured by `l.suf + r.pre`.

---

### Problem 22: Stream of Characters (Reversed Trie of Words — Aho-Corasick-lite)
**Statement.** Implement `StreamChecker(words)`; each call `query(letter)` appends a letter to a growing stream and returns `true` if **any** word in `words` is a **suffix** of the stream so far. (LeetCode 1032.)

**Constraints.** ≤ 2000 words; word length ≤ 200; ≤ 4·10⁴ queries; lowercase letters.

**Approach.** Re-scanning the stream per query is `O(stream · maxLen)`. The trick: insert every word **reversed** into a trie. Keep the last `maxLen` characters of the stream in a deque. On each query, walk the trie from the stream's newest character backward; if you hit an `isEnd` node, some word is a suffix. Walking at most `maxLen` steps per query gives `O(maxLen)` per call — effectively an inverted-suffix matcher (Aho-Corasick is the asymptotically optimal upgrade).

```
words = ["cd","f","kl"] inserted reversed: "dc","f","lk"
stream so far: ... a b c d   query('d') walks d -> c in reversed trie -> isEnd("dc") -> true
```

```java
class StreamChecker {
    static class Node { Node[] c = new Node[26]; boolean end; }
    private final Node root = new Node();
    private final Deque<Character> stream = new ArrayDeque<>();
    private int maxLen = 0;

    public StreamChecker(String[] words) {
        for (String w : words) {
            maxLen = Math.max(maxLen, w.length());
            Node cur = root;
            for (int i = w.length() - 1; i >= 0; i--) {   // insert reversed
                int idx = w.charAt(i) - 'a';
                if (cur.c[idx] == null) cur.c[idx] = new Node();
                cur = cur.c[idx];
            }
            cur.end = true;
        }
    }
    public boolean query(char letter) {
        stream.addFirst(letter);                           // newest at front
        if (stream.size() > maxLen) stream.removeLast();   // keep only what we need
        Node cur = root;
        for (char ch : stream) {                            // newest -> oldest
            cur = cur.c[ch - 'a'];
            if (cur == null) return false;
            if (cur.end) return true;
        }
        return false;
    }
}
```
**Dry run.** words `["cd","f","kl"]`. queries `a,b,c,d,e,f`. After `d`: stream front is `d,c,b,a`; walk `d→c` hits `end` (reversed `"cd"`) → true. After `f`: walk `f` hits `end` → true.

**Complexity.** Build `O(total chars)`; each `query` `O(maxLen)`. **Space.** `O(total chars)` trie + `O(maxLen)` deque. **Edge cases.** word longer than current stream (walk terminates at `null`), single-character words (immediate `end`), overlapping suffixes (returns true on the first match), duplicate words (harmless).

---

## 🧩 Extended Problems — Set 2: Medium → Hard Variations & Follow-ups

### Problem 23: Range Add + Range Sum — Two Fenwick Trees (BIT range-update / range-query)
**Statement.** Support `rangeAdd(l, r, val)` (add `val` to every element in `[l,r]`) and `rangeSum(l, r)` (sum of `[l,r]`), both in `O(log N)`, using only Fenwick trees — no segment tree. This is the classic "can a BIT do range updates?" follow-up to Problem 2.

**Constraints.** `1 ≤ N ≤ 2·10⁵`; up to `2·10⁵` interleaved operations; values fit in `long` after accumulation.

**Approach.** A single BIT does point-update/range-query (Problem 2) or, via difference arrays, range-update/point-query — but not both. The trick is to maintain **two** BITs, `B1` and `B2`, encoding a difference array `d` where `d[i] = a[i] - a[i-1]`. A range add `[l,r] += v` becomes `d[l] += v`, `d[r+1] -= v`. The prefix sum `a[1]+…+a[i]` can be shown to equal

```
prefix(i) = Σ_{k=1..i} a[k]
          = (i) * Σ_{k=1..i} d[k]  -  Σ_{k=1..i} (k-1)*d[k]
```

so we keep `B1` tracking `d[k]` and `B2` tracking `(k-1)*d[k]`. A range add updates both BITs at `l` and `r+1`; a prefix query combines them. Range sum is `prefix(r) − prefix(l−1)`. Both ops touch `O(log N)` nodes in two trees → `O(log N)`.

```
brute force:           rangeAdd O(N), rangeSum O(N)
one BIT + diff array:  rangeAdd O(log N), but pointQuery only
two BITs (this):       rangeAdd O(log N), rangeSum O(log N)   <-- optimal w/o segtree
```

```java
class RangeBIT {
    private final long[] b1, b2;   // 1-indexed
    private final int n;

    public RangeBIT(int n) {
        this.n = n;
        b1 = new long[n + 2];
        b2 = new long[n + 2];
    }
    private void add(long[] b, int i, long v) {
        for (; i <= n; i += i & (-i)) b[i] += v;
    }
    private long sum(long[] b, int i) {
        long s = 0;
        for (; i > 0; i -= i & (-i)) s += b[i];
        return s;
    }
    // add v to every element in [l, r]
    public void rangeAdd(int l, int r, long v) {
        add(b1, l, v);
        add(b1, r + 1, -v);
        add(b2, l, v * (l - 1));
        add(b2, r + 1, -v * r);
    }
    private long prefix(int i) {              // sum of a[1..i]
        return sum(b1, i) * i - sum(b2, i);
    }
    public long rangeSum(int l, int r) {
        if (r < l) return 0;
        return prefix(r) - prefix(l - 1);
    }
}
```
**Dry run.** `n=5`. `rangeAdd(2,4,3)` makes the conceptual array `[0,3,3,3,0]`. `rangeSum(1,3)` = `prefix(3)-prefix(0)` = `6 - 0` = 6. `rangeAdd(1,5,1)` → array `[1,4,4,4,1]`; `rangeSum(1,5)` = 14.

**Complexity.** `rangeAdd` and `rangeSum` both `O(log N)`; build `O(N log N)` or `O(N)` with difference seeding. **Space.** `O(N)`. **Edge cases.** `r+1 > n` (loops skip out-of-range indices safely since the arrays are sized `n+2`), `l == r` (single element), use `long` to avoid overflow in `v*(l-1)` and `prefix`.

---

### Problem 24: Reverse Pairs — Fenwick / Merge Sort (count i<j with nums[i] > 2·nums[j])
**Statement.** Count pairs `(i, j)` with `i < j` and `nums[i] > 2·nums[j]`. (LeetCode 493.) This is the harder cousin of *Count of Smaller Numbers After Self* (Problem 6) because the comparison value is **doubled**, breaking the naive single coordinate space.

**Constraints.** `1 ≤ length ≤ 5·10⁴`; values can be negative and up to `±2³¹`, so doubling can overflow `int`.

**Approach.** Brute force is `O(N²)`. The Fenwick approach coordinate-compresses **both** the raw values `nums[i]` and the doubled probe values `2·nums[j]` into one sorted-unique array of `long`. Sweep **left to right**: before inserting `nums[j]`, count how many already-inserted `nums[i]` exceed `2·nums[j]` — i.e. how many inserted values have rank strictly greater than `rank(2·nums[j])`. With a frequency BIT that is `total_inserted − query(rank(2·nums[j]))`. Then insert `nums[j]` at its own rank. Each step is `O(log N)`. Using `long` everywhere avoids the doubling overflow that bites `int` solutions.

```java
class Solution {
    private int[] tree;
    private int size;

    public int reversePairs(int[] nums) {
        int n = nums.length;
        // build a sorted-unique coordinate set of values and 2*values
        long[] all = new long[2 * n];
        for (int i = 0; i < n; i++) {
            all[i] = nums[i];
            all[i + n] = 2L * nums[i];
        }
        long[] sortedUniq = Arrays.stream(all).sorted().distinct().toArray();
        size = sortedUniq.length;
        tree = new int[size + 1];

        int count = 0, inserted = 0;
        for (int j = 0; j < n; j++) {
            // # of already-inserted nums[i] with nums[i] > 2*nums[j]
            int r = rank(sortedUniq, 2L * nums[j]);     // 1-indexed rank of 2*nums[j]
            count += inserted - query(r);               // strictly greater
            int ri = rank(sortedUniq, nums[j]);
            update(ri);                                 // insert nums[j]
            inserted++;
        }
        return count;
    }
    private int rank(long[] a, long key) {              // # of a[i] <= key (1-indexed)
        int lo = 0, hi = a.length;                      // upper_bound
        while (lo < hi) { int m = (lo + hi) >>> 1; if (a[m] <= key) lo = m + 1; else hi = m; }
        return lo;                                      // count of values <= key
    }
    private void update(int i) { for (; i <= size; i += i & (-i)) tree[i]++; }
    private int query(int i) { int s = 0; for (; i > 0; i -= i & (-i)) s += tree[i]; return s; }
}
```
**Dry run.** `[1,3,2,3,1]`. Pairs with `nums[i] > 2·nums[j]`: `(3 at idx1, 1 at idx4)` since `3 > 2`, `(3 at idx3, 1 at idx4)` since `3 > 2`. Answer = 2. Sweeping, when `j=4` (value 1, `2·1=2`), inserted = {1,3,2,3}; values strictly > 2 are the two 3's → +2.

**Complexity.** `O(N log N)`. **Space.** `O(N)`. **Edge cases.** overflow — use `2L * nums[j]` (`long`); negative numbers handled by compression; duplicates handled because `query` counts `≤ key` and we subtract from `inserted`; empty/one-element array → 0.

---

### Problem 25: Count of Range Sum — Fenwick on Prefix Sums (l ≤ sum(i..j) ≤ u)
**Statement.** Count the number of range sums `S(i,j) = nums[i] + … + nums[j]` that lie in `[lower, upper]` inclusive. (LeetCode 327.)

**Constraints.** `1 ≤ length ≤ 10⁵`; values up to `±2³¹`; `lower ≤ upper`, both fit in `long`.

**Approach.** Let `P[0..n]` be prefix sums (`P[0]=0`). Then `S(i,j) = P[j+1] − P[i]`, and a range sum lies in `[lower,upper]` iff for some `i < k`: `P[k] − P[i] ∈ [lower,upper]`, i.e. `P[i] ∈ [P[k]−upper, P[k]−lower]`. Brute force over all `(i,k)` is `O(N²)`. Optimal: coordinate-compress all prefix values together with the needed query bounds `P[k]−lower` and `P[k]−upper`, then sweep `k = 1..n` maintaining a **frequency Fenwick** of already-seen `P[i]` (`i < k`). For each `k`, add `countInRange(P[k]−upper, P[k]−lower)` from the BIT, then insert `P[k]`. Insert `P[0]=0` first. Each step `O(log N)`.

```java
class Solution {
    private int[] tree;
    private int size;

    public int countRangeSum(int[] nums, int lower, int upper) {
        int n = nums.length;
        long[] pre = new long[n + 1];
        for (int i = 0; i < n; i++) pre[i + 1] = pre[i] + nums[i];

        // coordinate set: all prefix values plus the two query bounds per prefix
        TreeSet<Long> set = new TreeSet<>();
        for (long p : pre) {
            set.add(p);
            set.add(p - lower);
            set.add(p - upper);
        }
        long[] sortedUniq = set.stream().mapToLong(Long::longValue).toArray();
        size = sortedUniq.length;
        tree = new int[size + 1];

        int count = 0;
        // sweep k = 0..n: before inserting pre[k], count earlier pre[i] in [pre[k]-upper, pre[k]-lower]
        for (long p : pre) {
            int lo = rankGE(sortedUniq, p - upper);     // 1-indexed
            int hi = rankLE(sortedUniq, p - lower);
            if (lo <= hi) count += query(hi) - query(lo - 1);
            update(rankExact(sortedUniq, p));           // insert this prefix
        }
        return count;
    }
    // first index (1-indexed) with value >= key
    private int rankGE(long[] a, long key) {
        int lo = 0, hi = a.length;
        while (lo < hi) { int m = (lo + hi) >>> 1; if (a[m] < key) lo = m + 1; else hi = m; }
        return lo + 1;
    }
    // last index (1-indexed) with value <= key
    private int rankLE(long[] a, long key) {
        int lo = 0, hi = a.length;
        while (lo < hi) { int m = (lo + hi) >>> 1; if (a[m] <= key) lo = m + 1; else hi = m; }
        return lo; // count of values <= key == last 1-indexed pos
    }
    private int rankExact(long[] a, long key) {         // exact position, 1-indexed
        int lo = 0, hi = a.length - 1;
        while (lo < hi) { int m = (lo + hi) >>> 1; if (a[m] < key) lo = m + 1; else hi = m; }
        return lo + 1;
    }
    private void update(int i) { for (; i <= size; i += i & (-i)) tree[i]++; }
    private int query(int i) { int s = 0; for (; i > 0; i -= i & (-i)) s += tree[i]; return s; }
}
```
**Dry run.** `nums=[-2,5,-1]`, `lower=-2`, `upper=2`. Prefix `[0,-2,3,2]`. Valid range sums: `[-2,-1]`(=-2... actually) — the three qualifying ranges are `[0,0]=-2`, `[2,2]=-1`, `[0,2]=2` → answer 3, matching the BIT count.

**Complexity.** `O(N log N)`. **Space.** `O(N)`. **Edge cases.** overflow (all sums `long`); the `P[0]=0` baseline must be inserted (handled by iterating over all of `pre` including index 0); `lower==upper`; values all equal.

---

### Problem 26: Concatenated XOR / Maximum XOR With an Element From Array (Bitwise Trie + offline sort)
**Statement.** Given `nums` and queries `[x_i, m_i]`, for each query return the maximum `x_i XOR nums[j]` over all `nums[j] ≤ m_i`, or `-1` if no such element. (LeetCode 1707.) This is the constrained follow-up to *Maximum XOR of Two Numbers* (Problem 4).

**Constraints.** `1 ≤ nums.length, queries.length ≤ 10⁵`; `0 ≤ values < 2³⁰`.

**Approach.** Without the `≤ m_i` constraint this is Problem 4. The constraint makes a per-query trie scan invalid because some numbers must be excluded. **Offline trick:** sort `nums` ascending and sort queries by `m_i` ascending. Maintain a bitwise trie and a pointer that **inserts numbers `≤ m_i` lazily** as queries advance. Since both are sorted, every number is inserted once total. For each query, if any number is in the trie, do the standard greedy "pick opposite bit" walk; else answer `-1`. Total `O((N + Q) · 30 + N log N + Q log Q)`.

```
nums sorted:    [0, 1, 2, 3, 4]
queries by m:   (x=1,m=2) -> insert 0,1,2 then greedy XOR
                (x=5,m=4) -> insert 3,4   then greedy XOR over {0..4}
```

```java
class Solution {
    static class Node { Node[] c = new Node[2]; }
    private static final int BITS = 30;

    public int[] maximizeXor(int[] nums, int[][] queries) {
        Arrays.sort(nums);
        int q = queries.length;
        Integer[] order = new Integer[q];
        for (int i = 0; i < q; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> queries[a][1] - queries[b][1]);   // by m ascending

        int[] ans = new int[q];
        Node root = new Node();
        int ptr = 0;
        boolean anyInserted = false;
        for (int qi : order) {
            int x = queries[qi][0], m = queries[qi][1];
            while (ptr < nums.length && nums[ptr] <= m) {
                insert(root, nums[ptr]);
                anyInserted = true;
                ptr++;
            }
            ans[qi] = (ptr == 0 && !anyInserted) || ptr == 0 ? -1 : query(root, x, ptr > 0);
            if (ptr == 0) ans[qi] = -1;       // no element <= m yet
        }
        return ans;
    }
    private void insert(Node root, int v) {
        Node cur = root;
        for (int b = BITS - 1; b >= 0; b--) {
            int bit = (v >> b) & 1;
            if (cur.c[bit] == null) cur.c[bit] = new Node();
            cur = cur.c[bit];
        }
    }
    private int query(Node root, int x, boolean hasAny) {
        if (!hasAny) return -1;
        Node cur = root;
        int res = 0;
        for (int b = BITS - 1; b >= 0; b--) {
            int bit = (x >> b) & 1;
            if (cur.c[bit ^ 1] != null) { res |= (1 << b); cur = cur.c[bit ^ 1]; }
            else cur = cur.c[bit];
        }
        return res;
    }
}
```
**Dry run.** `nums=[0,1,2,3,4]`, query `[3,1]`: numbers `≤1` are `{0,1}`. `3 XOR 1 = 2`, `3 XOR 0 = 3` → max 3. Query `[5,7]`: all inserted; `5 XOR 2 = 7` is best.

**Complexity.** `O((N+Q)·30 + N log N + Q log Q)`. **Space.** `O(N·30)` trie + `O(Q)` ordering. **Edge cases.** no number `≤ m` (return `-1`, guarded by `ptr == 0`); duplicate numbers (harmless, trie de-dups paths); `m` smaller than every element.

---

### Problem 27: Kth Smallest Number in Multiplication Table — Segment-Tree-Free Binary Search on Answer
**Statement.** Given an `m × n` multiplication table (`table[i][j] = i·j`, 1-indexed), return the `k`-th smallest value. (LeetCode 668.) Demonstrates "binary search on the answer + an `O(rows)` counting predicate," the technique that often replaces a segment/Fenwick count when values are implicit.

**Constraints.** `1 ≤ m, n ≤ 3·10⁴`; `1 ≤ k ≤ m·n` (up to ~9·10⁸, so no explicit table).

**Approach.** Building the table is `O(mn)` — too big. Binary search the answer value `v` in `[1, m·n]`. The predicate `count(v)` = number of table entries `≤ v` is computed in `O(m)`: row `i` contributes `min(v / i, n)` entries. The smallest `v` with `count(v) ≥ k` is the answer. This is the same "rank query" a Fenwick provides, but computed in closed form, so we trade the data structure for an `O(m log(mn))` search. Worth knowing as the alternative when the value domain is huge but the rank function is cheap.

```java
class Solution {
    public int findKthNumber(int m, int n, int k) {
        int lo = 1, hi = m * n;
        while (lo < hi) {
            int v = lo + (hi - lo) / 2;
            if (countLessEqual(v, m, n) >= k) hi = v;   // enough numbers <= v
            else lo = v + 1;
        }
        return lo;
    }
    // how many table entries are <= v
    private long countLessEqual(int v, int m, int n) {
        long count = 0;
        for (int i = 1; i <= m; i++) count += Math.min(v / i, n);
        return count;
    }
}
```
**Dry run.** `m=3, n=3, k=5`. Table sorted: `1,2,2,3,3,4,6,6,9`. The 5th is 3. Binary search converges: `count(3)=5 ≥ 5`, `count(2)=3 < 5` → answer 3.

**Complexity.** `O(m · log(m·n))`. **Space.** `O(1)`. **Edge cases.** `k = 1` (answer 1), `k = m·n` (answer `m·n`), large products handled with `long` accumulator; the `≥ k` lower-bound search guarantees the returned value is actually present in the table.

---

### Problem 28: Falling Squares — Coordinate-Compressed Segment Tree with Range-Assign Lazy
**Statement.** Squares drop one by one; each `squares[i] = [left, sideLength]` lands on the tallest surface beneath its `[left, left+side)` footprint. After each drop, report the current global maximum height. (LeetCode 699.)

**Constraints.** `1 ≤ squares.length ≤ 1000`; coordinates up to `10⁸`, so direct indexing is impossible.

**Approach.** Each drop needs the **max height over a horizontal interval** (range-max query) and then a **range assignment** (set the interval to `baseHeight + side`). That is a segment tree with **range-assign lazy** (different from add-lazy in Problem 7). Coordinates are huge but there are ≤ 2000 distinct endpoints, so **coordinate-compress** them to a small index space. Each drop is `O(log K)` where `K` is the number of distinct coordinates. The running global max is tracked alongside.

```java
class Solution {
    private long[] tree, lazy;     // max height + pending assignment
    private boolean[] hasLazy;
    private int size;

    public List<Integer> fallingSquares(int[][] squares) {
        // 1. collect & compress coordinates (use half-open intervals)
        TreeSet<Integer> coords = new TreeSet<>();
        for (int[] s : squares) { coords.add(s[0]); coords.add(s[0] + s[1] - 1); }
        Map<Integer, Integer> idx = new HashMap<>();
        int r = 0;
        for (int c : coords) idx.put(c, r++);
        size = r;
        tree = new long[4 * size];
        lazy = new long[4 * size];
        hasLazy = new boolean[4 * size];

        List<Integer> res = new ArrayList<>();
        long best = 0;
        for (int[] s : squares) {
            int l = idx.get(s[0]);
            int rr = idx.get(s[0] + s[1] - 1);
            long base = query(1, 0, size - 1, l, rr);
            long top = base + s[1];
            assign(1, 0, size - 1, l, rr, top);
            best = Math.max(best, top);
            res.add((int) best);
        }
        return res;
    }
    private void applyAssign(int node, long val) { tree[node] = val; lazy[node] = val; hasLazy[node] = true; }
    private void pushDown(int node) {
        if (hasLazy[node]) {
            applyAssign(2 * node, lazy[node]);
            applyAssign(2 * node + 1, lazy[node]);
            hasLazy[node] = false;
        }
    }
    private void assign(int node, int lo, int hi, int l, int r, long val) {
        if (r < lo || hi < l) return;
        if (l <= lo && hi <= r) { applyAssign(node, val); return; }
        pushDown(node);
        int mid = (lo + hi) >>> 1;
        assign(2 * node, lo, mid, l, r, val);
        assign(2 * node + 1, mid + 1, hi, l, r, val);
        tree[node] = Math.max(tree[2 * node], tree[2 * node + 1]);
    }
    private long query(int node, int lo, int hi, int l, int r) {
        if (r < lo || hi < l) return 0;
        if (l <= lo && hi <= r) return tree[node];
        pushDown(node);
        int mid = (lo + hi) >>> 1;
        return Math.max(query(2 * node, lo, mid, l, r),
                        query(2 * node + 1, mid + 1, hi, l, r));
    }
}
```
**Dry run.** `[[1,2],[2,3],[6,1]]`. Drop 1 over `[1,2]` → height 2, best 2. Drop 2 over `[2,4]` overlaps the first at x=2 (base 2) → height 5, best 5. Drop 3 over `[6,6]` lands on ground → height 1, best stays 5. Result `[2,5,5]`.

**Complexity.** `O(K log K)` total where `K ≤ 2·length`. **Space.** `O(K)`. **Edge cases.** range-**assign** lazy overwrites (not adds), so order of `applyAssign` matters; half-open footprint handled by using `left+side-1` as the right endpoint; non-overlapping squares land on the ground (base 0).

---

### Problem 29: My Calendar III — Maximum K-Booking via Difference / Segment Tree (range-add range-max)
**Statement.** Implement `MyCalendarThree`; `book(start, end)` adds a half-open event `[start, end)` and returns the maximum number of events that overlap at any single point so far (the "k-booking"). (LeetCode 732.)

**Constraints.** ≤ 400 calls; `0 ≤ start < end ≤ 10⁹`.

**Approach.** Brute force keeps a sorted map of breakpoints (a difference array): `+1` at `start`, `-1` at `end`, then sweep to find the running max — `O(N)` per call, `O(N²)` total, which actually passes given ≤400 calls. The data-structure answer is a **dynamic / coordinate-compressed segment tree** supporting **range-add (+1 on `[start,end)`) + range-max query**. With lazy add propagation each booking is `O(log C)`. Below is the clean `TreeMap` difference-array solution (the optimal-for-constraints choice) followed by the structural insight.

```java
class MyCalendarThree {
    // breakpoint -> net change in active count
    private final TreeMap<Integer, Integer> delta = new TreeMap<>();

    public int book(int start, int end) {
        delta.merge(start, 1, Integer::sum);     // one more event starts
        delta.merge(end, -1, Integer::sum);      // one event ends
        int active = 0, max = 0;
        for (int change : delta.values()) {      // sweep left to right
            active += change;
            max = Math.max(max, active);
        }
        return max;
    }
}
```

For larger input limits, replace the sweep with a lazy segment tree over compressed coordinates:

```java
// Lazy "range +1, range max" core (sketch for scale):
// applyLazy(node, v): tree[node] += v; lazy[node] += v;
// book: rangeAdd(start, end-1, +1); return tree[root];
class LazyMaxSegTree {
    private final long[] tree, lazy;
    private final int n;
    LazyMaxSegTree(int n) { this.n = n; tree = new long[4 * n]; lazy = new long[4 * n]; }
    private void apply(int node, long v) { tree[node] += v; lazy[node] += v; }
    private void push(int node) { if (lazy[node] != 0) { apply(2*node, lazy[node]); apply(2*node+1, lazy[node]); lazy[node] = 0; } }
    void add(int l, int r) { add(1, 0, n - 1, l, r); }
    private void add(int node, int lo, int hi, int l, int r) {
        if (r < lo || hi < l) return;
        if (l <= lo && hi <= r) { apply(node, 1); return; }
        push(node);
        int mid = (lo + hi) >>> 1;
        add(2*node, lo, mid, l, r);
        add(2*node+1, mid+1, hi, l, r);
        tree[node] = Math.max(tree[2*node], tree[2*node+1]);
    }
    long max() { return tree[1]; }
}
```
**Dry run.** `book(10,20)`→1, `book(50,60)`→1, `book(10,40)`→2 (overlaps first on `[10,20)`), `book(5,15)`→3 (`[10,15)` now has three events), `book(5,10)`→3, `book(25,55)`→3.

**Complexity.** TreeMap sweep: `O(N)` per call, `O(N²)` total (fine for ≤400 calls). Lazy segment tree: `O(log C)` per call. **Space.** `O(N)` or `O(C)`. **Edge cases.** half-open intervals (use `end` exclusive — `-1` at `end`, or `end-1` as inclusive right in the seg tree), touching-but-not-overlapping events (`[10,20)` and `[20,30)` do not stack), repeated identical bookings.

---

### Problem 30: Range Module — Interval Set with TreeMap (add/remove/query ranges)
**Statement.** Implement `RangeModule`: `addRange(l, r)` tracks the half-open interval `[l, r)`, `removeRange(l, r)` stops tracking it, and `queryRange(l, r)` returns whether **every** real number in `[l, r)` is currently tracked. (LeetCode 715.)

**Constraints.** ≤ 10⁴ calls; `1 ≤ l < r ≤ 10⁹` (huge domain → no array, no plain segment tree without dynamic nodes).

**Approach.** A coordinate-compressed segment tree is awkward because intervals are added/removed online. The cleaner industrial answer is an **interval set kept in a `TreeMap<start, end>` of disjoint merged intervals**. `addRange` finds neighbors via `floorKey`/`ceilingKey`, merges overlaps, and collapses them into one entry. `removeRange` splits straddling intervals. `queryRange` checks a single `floorEntry` covers `[l, r)`. Each op is `O(log N + k)` where `k` is the number of intervals touched (amortized small). This "merge intervals in a balanced BST" pattern is the practical alternative to a dynamic segment tree for online interval coverage.

```java
class RangeModule {
    // start -> end, disjoint, non-adjacent merged half-open intervals
    private final TreeMap<Integer, Integer> map = new TreeMap<>();

    public void addRange(int left, int right) {
        Integer s = map.floorKey(left);
        if (s != null && map.get(s) >= left) left = Math.min(left, s);
        else s = map.ceilingKey(left);
        // absorb every interval that overlaps [left, right)
        while (s != null && s <= right) {
            int e = map.get(s);
            left = Math.min(left, s);
            right = Math.max(right, e);
            map.remove(s);
            s = map.ceilingKey(s + 1 > s ? s : s + 1);  // next key strictly after removed
            s = map.ceilingKey(left);                    // simpler: re-seek from left
        }
        map.put(left, right);
    }

    public boolean queryRange(int left, int right) {
        Integer s = map.floorKey(left);
        return s != null && map.get(s) >= right;
    }

    public void removeRange(int left, int right) {
        Integer s = map.floorKey(right);
        while (s != null && map.get(s) > left) {
            int e = map.get(s);
            map.remove(s);
            if (s < left)  map.put(s, left);     // keep left fragment
            if (e > right) map.put(right, e);    // keep right fragment
            s = map.floorKey(right);
        }
    }
}
```
**Dry run.** `addRange(10,20)` → `{10:20}`. `removeRange(14,16)` → `{10:14, 16:20}`. `queryRange(10,14)` → `floorEntry(10)=10:14`, `14 ≥ 14` → true. `queryRange(13,15)` → `floorEntry(13)=10:14`, `14 ≥ 15` false → false. `queryRange(16,17)` → `16:20`, `20 ≥ 17` → true.

**Complexity.** `addRange`/`removeRange` `O(log N + k)` amortized, `queryRange` `O(log N)`. **Space.** `O(N)` intervals. **Edge cases.** half-open semantics (adjacency `[1,2)+[2,3)` may merge or not depending on `>=` vs `>` — here `>= left` merges touching); fully-contained removal splits into two fragments; query spanning a gap returns false; empty map.

---

### Problem 31: Sliding Window Maximum — Monotonic Deque vs Segment Tree
**Statement.** Given `nums` and window size `k`, return the maximum of each contiguous window of size `k`. (LeetCode 239.) A staple where the structure choice (deque vs segment tree) is the interview point.

**Constraints.** `1 ≤ length ≤ 10⁵`; `1 ≤ k ≤ length`.

**Approach.** A segment tree answers each window's range-max in `O(log N)` → `O(N log N)` total, and is the natural answer **if elements were also being updated**. But for a pure read-only sliding maximum, a **monotonic deque** of indices gives `O(N)`: maintain indices in decreasing value order; the front is always the current window's max. Pop expired indices off the front and smaller values off the back. The progression brute-force `O(Nk)` → segment tree `O(N log N)` → deque `O(N)` is the expected discussion.

```
deque holds indices, values decreasing:
nums=[1,3,-1,-3,5], k=3
i=0: [0]            -> window not full
i=1: 3>1 pop 0 -> [1]
i=2: [1,2]          -> window [1,3,-1] max=nums[1]=3
i=3: [1,2,3]        -> front 1 expired? 1 > 3-3=0 keep -> max=3
i=4: 5 pops all -> [4]-> max=5
```

```java
class Solution {
    public int[] maxSlidingWindow(int[] nums, int k) {
        int n = nums.length;
        int[] res = new int[n - k + 1];
        Deque<Integer> dq = new ArrayDeque<>();   // indices, values decreasing
        for (int i = 0; i < n; i++) {
            // drop indices that have left the window
            while (!dq.isEmpty() && dq.peekFirst() <= i - k) dq.pollFirst();
            // drop smaller values from the back (they can never be a max while nums[i] stands)
            while (!dq.isEmpty() && nums[dq.peekLast()] <= nums[i]) dq.pollLast();
            dq.offerLast(i);
            if (i >= k - 1) res[i - k + 1] = nums[dq.peekFirst()];
        }
        return res;
    }
}
```
**Dry run.** `nums=[1,3,-1,-3,5,3,6,7]`, `k=3` → `[3,3,5,5,6,7]`. At `i=4` (value 5) the deque is cleared because 5 exceeds all kept values, so the window `[-1,-3,5]` max is 5.

**Complexity.** `O(N)` time, `O(k)` space for the deque (vs `O(N log N)` / `O(4N)` for the segment tree). **Edge cases.** `k == 1` (output equals input), all-decreasing array (deque grows then shrinks per window), duplicate values (`<=` ensures the newest equal index wins so expiry is correct), `k == n` (single window).

---

### Problem 32: Number of Distinct Substrings — Suffix-style Trie / Suffix Automaton intuition
**Statement.** Count the number of **distinct non-empty substrings** of a string `s`. (LeetCode 1698 variant / classic.)

**Constraints.** Trie approach: `|s| ≤ ~2000` (`O(n²)` nodes). For `|s| ≤ 10⁵` you need a suffix automaton / suffix array (mentioned for completeness).

**Approach.** Every substring of `s` is a prefix of some suffix of `s`. Insert **all suffixes** into a trie; each *new node created* corresponds to exactly one new distinct substring (the path to it). So the answer is the total number of nodes created (excluding the root). This is `O(n²)` time and space — clean and interview-appropriate for moderate `n`. For large `n`, a **suffix automaton** counts distinct substrings in `O(n)` as `Σ (len[v] − len[link[v]])`, which is the asymptotically optimal upgrade.

```
s = "aba"
suffixes: "aba","ba","a"
trie nodes (excluding root): a, ab, aba, b, ba  -> 5 distinct substrings
{a, b, ab, ba, aba}  -> count 5
```

```java
class Solution {
    static class Node { Node[] c = new Node[26]; }

    // O(n^2) suffix-trie counting of distinct substrings
    public int countDistinctSubstrings(String s) {
        Node root = new Node();
        int nodes = 0;
        int n = s.length();
        for (int i = 0; i < n; i++) {            // each suffix s[i..]
            Node cur = root;
            for (int j = i; j < n; j++) {
                int idx = s.charAt(j) - 'a';
                if (cur.c[idx] == null) { cur.c[idx] = new Node(); nodes++; }
                cur = cur.c[idx];
            }
        }
        return nodes;                            // each new node == one new substring
    }
}
```
**Dry run.** `s="aaa"`. Suffixes `"aaa","aa","a"`. Trie creates nodes for `a`, `aa`, `aaa` → 3 distinct substrings, which is correct (`{a, aa, aaa}`).

**Complexity.** `O(n²)` time and space for the suffix trie. **Space.** `O(n²)`. **Edge cases.** empty string → 0; all-identical characters → `n` distinct substrings; the suffix-automaton upgrade is needed for `n > ~2000` to avoid quadratic memory.

---

### Problem 33: Maximum Genetic Difference Query — Bitwise Trie on a Tree (DFS insert/remove)
**Statement.** Given a rooted tree where each node has an integer label = its node id, and queries `(node, val)`, for each query return the maximum `val XOR x` where `x` is the id of any ancestor of `node` (including `node` and the root). (LeetCode 1938.) Combines the XOR trie (Problem 4) with tree DFS.

**Constraints.** ≤ 10⁵ nodes and queries; ids `< 2¹⁸` (18 bits suffice).

**Approach.** Process queries **offline during a DFS**. A bitwise trie holds the ids currently on the root-to-current-node path. On entering a node, **insert** its id (with a count per trie node so removal is exact); answer all queries attached to this node by the greedy opposite-bit XOR walk; recurse into children; on leaving, **remove** the id (decrement counts). Thus the trie always contains exactly the current node's ancestors. Each insert/remove/query is `O(18)`.

```java
class Solution {
    static class Node { Node[] c = new Node[2]; int cnt; }
    private static final int BITS = 18;
    private final Node root = new Node();
    private List<Integer>[] children;
    private List<int[]>[] queriesAt;   // each: {val, queryIndex}
    private int[] ans;

    public int[] maxGeneticDifference(int[] parents, int[][] queries) {
        int n = parents.length;
        children = new List[n];
        for (int i = 0; i < n; i++) children[i] = new ArrayList<>();
        int rootNode = -1;
        for (int i = 0; i < n; i++) {
            if (parents[i] == -1) rootNode = i;
            else children[parents[i]].add(i);
        }
        queriesAt = new List[n];
        for (int i = 0; i < n; i++) queriesAt[i] = new ArrayList<>();
        for (int i = 0; i < queries.length; i++)
            queriesAt[queries[i][0]].add(new int[]{queries[i][1], i});
        ans = new int[queries.length];
        dfs(rootNode);
        return ans;
    }
    private void dfs(int node) {
        insert(node, +1);
        for (int[] q : queriesAt[node]) ans[q[1]] = queryMaxXor(q[0]);
        for (int ch : children[node]) dfs(ch);
        insert(node, -1);                 // remove on the way out
    }
    private void insert(int v, int delta) {
        Node cur = root;
        for (int b = BITS - 1; b >= 0; b--) {
            int bit = (v >> b) & 1;
            if (cur.c[bit] == null) cur.c[bit] = new Node();
            cur = cur.c[bit];
            cur.cnt += delta;
        }
    }
    private int queryMaxXor(int val) {
        Node cur = root;
        int res = 0;
        for (int b = BITS - 1; b >= 0; b--) {
            int bit = (val >> b) & 1;
            Node opp = cur.c[bit ^ 1];
            if (opp != null && opp.cnt > 0) { res |= (1 << b); cur = opp; }
            else cur = cur.c[bit];        // forced same bit (guaranteed present: path has >=1 id)
        }
        return res;
    }
}
```
**Dry run.** parents `[-1,0,1,1]` (chain/branch), query `(node=3, val=7)`: ancestors of 3 are `{3,1,0}`. `7 XOR 0=7`, `7 XOR 1=6`, `7 XOR 3=4` → max 7.

**Complexity.** `O((N + Q)·18)`. **Space.** `O(N·18)` trie + `O(N + Q)` adjacency/queries. **Edge cases.** recursion depth (use iterative DFS / increase stack if the tree is a long chain — up to 10⁵ deep); count-based removal so shared trie nodes stay valid; a node's own id is a valid ancestor (inserted before answering its queries).

---

### Problem 34: Create Sorted Array Through Instructions — Fenwick Two-Sided Rank Cost
**Statement.** Insert the elements of `instructions` one by one into an initially empty list; the cost of inserting `x` is `min(#elements already present strictly less than x, #strictly greater than x)`. Return the total cost modulo `1e9+7`. (LeetCode 1649.)

**Constraints.** `1 ≤ length ≤ 10⁵`; `1 ≤ value ≤ 10⁵`.

**Approach.** Brute force is `O(N²)`. With a **frequency Fenwick** over the value domain: when inserting `x` after having inserted `i` elements, `less = query(x-1)` and `greater = i − query(x)` (note `query(x)` counts `≤ x`, so subtract duplicates of `x` too). Cost is `min(less, greater)`. Then `update(x, +1)`. Values ≤ 10⁵ so no compression needed (compress if sparse). Each step `O(log V)`.

```java
class Solution {
    private static final int MOD = 1_000_000_007;
    private int[] tree;
    private int maxV;

    public int createSortedArray(int[] instructions) {
        maxV = 0;
        for (int x : instructions) maxV = Math.max(maxV, x);
        tree = new int[maxV + 1];
        long cost = 0;
        int inserted = 0;
        for (int x : instructions) {
            int less = query(x - 1);                 // strictly less than x
            int greater = inserted - query(x);       // strictly greater than x
            cost = (cost + Math.min(less, greater)) % MOD;
            update(x);
            inserted++;
        }
        return (int) cost;
    }
    private void update(int i) { for (; i <= maxV; i += i & (-i)) tree[i]++; }
    private int query(int i) { int s = 0; for (; i > 0; i -= i & (-i)) s += tree[i]; return s; }
}
```
**Dry run.** `[1,5,6,2]`. Insert 1: less=0, greater=0 → 0. Insert 5: less=1(only 1), greater=0 → 0. Insert 6: less=2, greater=0 → 0. Insert 2: less=1(the 1), greater=2(5,6) → min=1. Total cost 1.

**Complexity.** `O(N log V)`. **Space.** `O(V)`. **Edge cases.** duplicates (`query(x)` includes equal values, so `greater` excludes them correctly; `less = query(x-1)` excludes them too); modulo applied to the running sum; `query(0)=0` when `x=1`.

---

### Problem 35: Longest Duplicate Substring — Binary Search + Trie/Hashing (hard string)
**Statement.** Given a string `s`, return any longest substring that appears **at least twice** in `s` (overlaps allowed); return `""` if none. (LeetCode 1044.)

**Constraints.** `2 ≤ |s| ≤ 3·10⁵`; lowercase letters. (Needs near-linear methods; naive trie is `O(n²)`.)

**Approach.** The answer length is **monotonic**: if a duplicate of length `L` exists, so does one of length `L−1`. So **binary search `L`** in `[1, n−1]`; the predicate "does some length-`L` substring repeat?" is answered by **rolling hash (Rabin-Karp)** in `O(n)`: hash every length-`L` window, store hashes in a set, and on a collision verify (or trust a double-hash to avoid the `O(L)` check). Total `O(n log n)`. A suffix-trie/suffix-automaton solves it directly but a suffix trie is `O(n²)` memory; the binary-search + hashing hybrid is the standard interview-feasible optimum. (Suffix array + LCP is the textbook `O(n log n)`/`O(n)` alternative.)

```java
class Solution {
    private static final long MOD = (1L << 61) - 1;   // large Mersenne prime
    private long base;

    public String longestDupSubstring(String s) {
        int n = s.length();
        long[] h = new long[n + 1];      // prefix hashes
        long[] pw = new long[n + 1];     // base powers
        base = 131 + new java.util.Random().nextInt(1000);
        pw[0] = 1;
        for (int i = 0; i < n; i++) {
            h[i + 1] = mulmod(h[i], base) + s.charAt(i) + 1;
            if (h[i + 1] >= MOD) h[i + 1] -= MOD;
            pw[i + 1] = mulmod(pw[i], base);
        }
        int lo = 1, hi = n - 1, start = -1, bestLen = 0;
        while (lo <= hi) {
            int L = (lo + hi) >>> 1;
            int pos = findDup(s, h, pw, L);
            if (pos >= 0) { start = pos; bestLen = L; lo = L + 1; }   // try longer
            else hi = L - 1;                                         // try shorter
        }
        return start < 0 ? "" : s.substring(start, start + bestLen);
    }
    // returns start index of some repeated length-L substring, or -1
    private int findDup(String s, long[] h, long[] pw, int L) {
        int n = s.length();
        HashMap<Long, List<Integer>> seen = new HashMap<>();
        for (int i = 0; i + L <= n; i++) {
            long hash = subHash(h, pw, i, i + L);
            List<Integer> list = seen.get(hash);
            if (list != null) {
                for (int j : list)                                  // verify to beat collisions
                    if (s.regionMatches(i, s, j, L)) return i;
            }
            seen.computeIfAbsent(hash, k -> new ArrayList<>()).add(i);
        }
        return -1;
    }
    private long subHash(long[] h, long[] pw, int l, int r) {       // hash of s[l..r-1]
        long res = h[r] - mulmod(h[l], pw[r - l]) % MOD;
        res %= MOD;
        if (res < 0) res += MOD;
        return res;
    }
    // 128-bit safe multiply mod (2^61 - 1)
    private long mulmod(long a, long b) {
        return java.math.BigInteger.valueOf(a)
                .multiply(java.math.BigInteger.valueOf(b))
                .mod(java.math.BigInteger.valueOf(MOD)).longValue();
    }
}
```
**Dry run.** `s="banana"`. Binary search on length: length 3 `"ana"` appears at indices 1 and 3 → duplicate exists, try longer; length 4 has no repeat → answer `"ana"` (length 3).

**Complexity.** `O(n log n)` expected (hashing). **Space.** `O(n)`. **Edge cases.** no duplicate (`return ""`); overlapping duplicates allowed (`"aaaa"` → `"aaa"`); hash collisions defended by `regionMatches` verification; randomized base reduces adversarial-collision risk.

---

## 🧩 Extended Problems — Supplemental: Medium → Expert

### Problem 36: Implement Magic Dictionary — Trie + One-Change DFS
**Statement.** Build a `MagicDictionary`: `buildDict(words)` stores a list of distinct words, and `search(word)` returns `true` iff you can change **exactly one** character of `word` to a different character so the result is in the dictionary. (LeetCode 676.)

**Constraints.** ≤ 100 words; word length ≤ 100; ≤ 100 `search` calls; lowercase letters.

**Approach.** A brute force compares `word` against every dictionary word of equal length, allowing exactly one mismatch — `O(N·L)` per search. The **trie** answer walks the word through the trie carrying a `usedChange` flag; at each position you may follow the matching child (no change) or any other child (consuming the one allowed change). Success requires landing on an `isEnd` node with **exactly one** change used. The DFS prunes whole subtrees the moment the change budget is exceeded.

```
search("hello") against {"hello","leetcode"}:
  must change EXACTLY one char -> exact match "hello" is NOT accepted
  search("hhllo") -> change index1 'h'->'e' reaches "hello" -> true
```

```java
class MagicDictionary {
    private static class Node { Node[] c = new Node[26]; boolean end; }
    private final Node root = new Node();

    public void buildDict(String[] dictionary) {
        for (String w : dictionary) {
            Node cur = root;
            for (char ch : w.toCharArray()) {
                int i = ch - 'a';
                if (cur.c[i] == null) cur.c[i] = new Node();
                cur = cur.c[i];
            }
            cur.end = true;
        }
    }

    public boolean search(String word) {
        return dfs(root, word, 0, false);
    }

    private boolean dfs(Node node, String w, int idx, boolean changed) {
        if (idx == w.length()) return changed && node.end;   // exactly one change
        int target = w.charAt(idx) - 'a';
        for (int i = 0; i < 26; i++) {
            if (node.c[i] == null) continue;
            if (i == target) {
                if (dfs(node.c[i], w, idx + 1, changed)) return true;
            } else if (!changed) {
                if (dfs(node.c[i], w, idx + 1, true)) return true;
            }
        }
        return false;
    }
}
```
**Dry run.** Dict `["hello","leetcode"]`. `search("hello")` → only the no-change path reaches the end, `changed=false` → false. `search("hhllo")` → at index 1, follow `e` child consuming the change, rest matches → true. `search("hell")` → length mismatch, no end → false.

**Complexity.** `buildDict` `O(total chars)`; `search` `O(26·L)` worst (one branch may fork once). **Space.** `O(26·total chars)`. **Edge cases.** exact match is rejected (must change exactly one), length mismatch prunes immediately, querying before build returns false.

---

### Problem 37: Concatenated Words — Trie + DP over the Dictionary
**Statement.** Given a list of distinct `words`, return all words that are **concatenations of at least two** shorter words from the same list. (LeetCode 472.)

**Constraints.** ≤ 10⁴ words; total chars ≤ 10⁵; lowercase letters.

**Approach.** Insert all words into a trie. For each candidate word, run a DP: `dp[i]` = can the prefix `word[0..i)` be split into ≥1 dictionary words. Walk the trie from position `i` wherever `dp[i]` is reachable; each time the walk hits an `isEnd` node at position `j`, mark `dp[j]` reachable. A word qualifies if `dp[len]` is reachable using **at least two** pieces — enforced by skipping the trivial whole-word match (track piece count, or forbid consuming the entire word as a single piece). The trie keeps each split scan at `O(L)` per start instead of hashing every substring.

```java
class Solution {
    static class Node { Node[] c = new Node[26]; boolean end; }
    private final Node root = new Node();

    public List<String> findAllConcatenatedWordsInADict(String[] words) {
        for (String w : words) if (!w.isEmpty()) insert(w);
        List<String> res = new ArrayList<>();
        for (String w : words) if (isConcatenated(w)) res.add(w);
        return res;
    }
    private void insert(String w) {
        Node cur = root;
        for (char ch : w.toCharArray()) {
            int i = ch - 'a';
            if (cur.c[i] == null) cur.c[i] = new Node();
            cur = cur.c[i];
        }
        cur.end = true;
    }
    // can w be split into >= 2 dictionary words?
    private boolean isConcatenated(String w) {
        int n = w.length();
        if (n == 0) return false;
        // dp[i] = min number of pieces to form w[0..i); -1 if unreachable
        int[] dp = new int[n + 1];
        Arrays.fill(dp, -1);
        dp[0] = 0;
        for (int i = 0; i < n; i++) {
            if (dp[i] < 0) continue;
            Node cur = root;
            for (int j = i; j < n; j++) {
                Node nxt = cur.c[w.charAt(j) - 'a'];
                if (nxt == null) break;
                cur = nxt;
                // forbid using the whole word as a single piece
                if (cur.end && !(i == 0 && j == n - 1)) {
                    int pieces = dp[i] + 1;
                    if (dp[j + 1] < 0 || pieces < dp[j + 1]) dp[j + 1] = pieces;
                }
            }
        }
        return dp[n] >= 2;
    }
}
```
**Dry run.** `["cat","cats","dog","catsdog"]`. For `"catsdog"`: split `cats`(dp[4]=1) then `dog`(dp[7]=2) → `dp[7]=2 ≥ 2` → qualifies. For `"cat"`: only the whole-word match exists, which is forbidden → not concatenated.

**Complexity.** `O(Σ L² )` worst over all words (DP per word is `O(L²)`), trie walks make the inner scan tight. **Space.** `O(total chars)` trie + `O(L)` DP. **Edge cases.** empty strings skipped during insert; the whole-word-as-single-piece is forbidden so single dictionary words don't self-qualify; a word may be reused (e.g. `"dogdog"`).

---

### Problem 38: Camelcase Matching — Trie of Patterns + Subsequence Walk
**Statement.** Given `queries` (CamelCase identifiers) and a `pattern`, return for each query whether `pattern` can be turned into the query by inserting **only lowercase** letters (uppercase letters of the query must be matched exactly and in order, as a subsequence). (LeetCode 1023.)

**Constraints.** ≤ 100 queries; lengths ≤ 100; mix of upper/lowercase letters.

**Approach.** A query matches when (1) the pattern is a subsequence of the query, and (2) **every uppercase** letter of the query is consumed by the pattern (you may only *insert* lowercase, never uppercase). The single-pattern version is a two-pointer scan in `O(L)`. The data-structure framing — useful when there are **many patterns** — inserts all patterns into a trie and DFS-walks each query: a lowercase query char may either advance the trie (match) or be skipped (an inserted lowercase), while an uppercase query char **must** advance the trie. Below is the canonical two-pointer solution plus the trie generalization in comments.

```java
class Solution {
    public List<Boolean> camelMatch(String[] queries, String pattern) {
        List<Boolean> res = new ArrayList<>();
        for (String q : queries) res.add(matches(q, pattern));
        return res;
    }
    private boolean matches(String q, String p) {
        int j = 0;                              // pointer into pattern
        for (int i = 0; i < q.length(); i++) {
            char ch = q.charAt(i);
            if (j < p.length() && ch == p.charAt(j)) {
                j++;                            // consume a pattern char
            } else if (Character.isUpperCase(ch)) {
                return false;                   // an unmatched uppercase => fail
            }
            // else: extra lowercase in q, allowed (an "inserted" letter)
        }
        return j == p.length();                 // all of pattern consumed
    }
    // Many-patterns variant: build a Trie of patterns, DFS each query;
    // uppercase query char => must follow the trie edge (no skip),
    // lowercase => follow edge if present OR skip as an inserted letter.
}
```
**Dry run.** `pattern="FB"`. Query `"FooBar"`: `F` matches `F`(j=1), `o,o` lowercase skipped, `B` matches `B`(j=2), `a,r` skipped → `j==2` → true. Query `"FooBarTest"`: trailing uppercase `T` is not in pattern and not lowercase → false. Query `"ForceFeedBack"`: `F`(j=1), then uppercase `F` in "Force"? — `F` matches pattern `B`? no, and it's uppercase → false.

**Complexity.** `O(Σ |query|)` for the two-pointer scan. **Space.** `O(1)` (or `O(total pattern chars)` for the trie variant). **Edge cases.** pattern longer than query (`j` never reaches end → false), query all lowercase (matches iff pattern is a subsequence), empty pattern (true iff query has no uppercase).

---

### Problem 39: K-Query — Count Elements ≤ K in a Range (Merge Sort Tree)
**Statement.** Given a static array and queries `(l, r, k)`, return the number of indices `i ∈ [l, r]` with `nums[i] ≤ k`. (Classic SPOJ KQUERY / "merge sort tree".)

**Constraints.** ≤ 10⁵ elements and queries; values fit in `int`; offline or online both fine.

**Approach.** A frequency Fenwick handles "≤ k over a *prefix*" but not an arbitrary `[l, r]` window without going offline. The **merge sort tree** is a segment tree where each node stores its range's values in **sorted order** (built by merging children, exactly like merge sort). A query descends to the `O(log N)` canonical nodes covering `[l, r]`; in each, a binary search (`upperBound(k)`) counts values `≤ k` in `O(log N)`. Total `O(log²N)` per query, `O(N log N)` space.

```
node ranges store SORTED values:
        [0..3] {1,2,3,5}
       /              \
   [0..1]{2,3}     [2..3]{1,5}
query (l=0,r=3,k=3): upperBound(3) in {1,2,3,5} = 3
```

```java
class MergeSortTree {
    private final int[][] tree;     // tree[node] = sorted values of its range
    private final int n;

    MergeSortTree(int[] a) {
        n = a.length;
        tree = new int[4 * n][];
        build(a, 1, 0, n - 1);
    }
    private void build(int[] a, int node, int lo, int hi) {
        if (lo == hi) { tree[node] = new int[]{a[lo]}; return; }
        int mid = (lo + hi) >>> 1;
        build(a, 2 * node, lo, mid);
        build(a, 2 * node + 1, mid + 1, hi);
        tree[node] = merge(tree[2 * node], tree[2 * node + 1]);
    }
    private int[] merge(int[] x, int[] y) {
        int[] r = new int[x.length + y.length];
        int i = 0, j = 0, k = 0;
        while (i < x.length && j < y.length)
            r[k++] = (x[i] <= y[j]) ? x[i++] : y[j++];
        while (i < x.length) r[k++] = x[i++];
        while (j < y.length) r[k++] = y[j++];
        return r;
    }
    // count of indices in [l, r] with value <= k
    int query(int l, int r, int k) { return query(1, 0, n - 1, l, r, k); }
    private int query(int node, int lo, int hi, int l, int r, int k) {
        if (r < lo || hi < l) return 0;
        if (l <= lo && hi <= r) return upperBound(tree[node], k);
        int mid = (lo + hi) >>> 1;
        return query(2 * node, lo, mid, l, r, k)
             + query(2 * node + 1, mid + 1, hi, l, r, k);
    }
    private int upperBound(int[] a, int key) {     // # of a[i] <= key
        int lo = 0, hi = a.length;
        while (lo < hi) { int m = (lo + hi) >>> 1; if (a[m] <= key) lo = m + 1; else hi = m; }
        return lo;
    }
}
```
**Dry run.** `a=[2,3,1,5]`. `query(0,3,3)` → root sorted `{1,2,3,5}`, `upperBound(3)=3`. `query(2,3,3)` → node `{1,5}`, `upperBound(3)=1` (only the 1).

**Complexity.** Build `O(N log N)`; query `O(log²N)`. **Space.** `O(N log N)`. **Edge cases.** `k` smaller than all values (returns 0), `k` ≥ all (returns range length), single-element range, supports updates only with a balanced BST per node (heavier) — prefer a wavelet/persistent tree if updates are required.

---

### Problem 40: K-th Smallest in a Subarray — Persistent Segment Tree
**Statement.** Given a static array, answer queries `(l, r, k)`: the `k`-th smallest value among `nums[l..r]`. (SPOJ MKTHNUM / "persistent segment tree" classic.)

**Constraints.** ≤ 10⁵ elements and queries; values arbitrary `int` (coordinate-compressed).

**Approach.** Coordinate-compress values to ranks `1..m`. Build a **persistent segment tree over the value domain**: version `i` is version `i−1` with `+1` inserted at `rank(nums[i])`, creating only `O(log m)` new nodes per version (the rest is shared). A query on `[l, r]` uses versions `root[r]` and `root[l−1]`; the count of values in any value-range within `[l, r]` is `tree[root[r]] − tree[root[l−1]]`. Walk both roots downward, comparing the left child's count difference to `k` to decide which half holds the `k`-th smallest — `O(log m)` per query.

```
versions chained; query subtracts two prefix versions:
count of value-bucket in [l..r] = node(root_r) - node(root_{l-1})
descend left while (leftCount >= k) else go right with k -= leftCount
```

```java
class Solution {
    private int[] left, right, cnt;     // node pools
    private int idx = 0;
    private int[] roots;
    private int[] sortedUniq;
    private int m;

    public int[] kthSmallest(int[] nums, int[][] queries) {
        int n = nums.length;
        sortedUniq = Arrays.stream(nums).distinct().sorted().toArray();
        m = sortedUniq.length;
        // base tree (2m nodes) + n updates each adding ~ceil(log2 m)+1 nodes
        int logm = 32 - Integer.numberOfLeadingZeros(Math.max(1, m));
        int maxNodes = 2 * m + n * (logm + 2) + 10;
        left = new int[maxNodes];
        right = new int[maxNodes];
        cnt = new int[maxNodes];
        roots = new int[n + 1];
        roots[0] = build(1, m);
        for (int i = 0; i < n; i++) {
            int r = rank(nums[i]);
            roots[i + 1] = update(roots[i], 1, m, r);
        }
        int[] ans = new int[queries.length];
        for (int qi = 0; qi < queries.length; qi++) {
            int l = queries[qi][0], rr = queries[qi][1], k = queries[qi][2];
            // roots[l] is the prefix BEFORE index l; roots[rr + 1] the prefix THROUGH rr
            int valRank = query(roots[l], roots[rr + 1], 1, m, k);
            ans[qi] = sortedUniq[valRank - 1];
        }
        return ans;
    }
    private int build(int lo, int hi) {
        int node = ++idx;
        if (lo == hi) return node;
        int mid = (lo + hi) >>> 1;
        left[node] = build(lo, mid);
        right[node] = build(mid + 1, hi);
        return node;
    }
    private int update(int prev, int lo, int hi, int pos) {
        int node = ++idx;
        left[node] = left[prev];
        right[node] = right[prev];
        cnt[node] = cnt[prev] + 1;
        if (lo == hi) return node;
        int mid = (lo + hi) >>> 1;
        if (pos <= mid) left[node] = update(left[prev], lo, mid, pos);
        else            right[node] = update(right[prev], mid + 1, hi, pos);
        return node;
    }
    // k-th smallest among versions (uPrev = root[l-1], uCur = root[r]) ; here pass roots[l], roots[r+1]
    private int query(int uPrev, int uCur, int lo, int hi, int k) {
        if (lo == hi) return lo;
        int mid = (lo + hi) >>> 1;
        int leftCount = cnt[left[uCur]] - cnt[left[uPrev]];
        if (leftCount >= k) return query(left[uPrev], left[uCur], lo, mid, k);
        return query(right[uPrev], right[uCur], mid + 1, hi, k - leftCount);
    }
    private int rank(int v) {                 // 1-indexed rank of v
        int lo = 0, hi = sortedUniq.length - 1, ans = 0;
        while (lo <= hi) { int md = (lo + hi) >>> 1; if (sortedUniq[md] <= v) { ans = md; lo = md + 1; } else hi = md - 1; }
        return ans + 1;
    }
}
```
> Note: for a query `(l, r, k)` (0-indexed inclusive) the call is `query(roots[l], roots[r + 1], 1, m, k)` — `roots[l]` is the prefix *before* index `l`, `roots[r+1]` the prefix through `r`. The bucket count over `[l, r]` is the node-count difference of these two persistent versions.

**Dry run.** `nums=[2,1,3,4]`, query `(l=0,r=2,k=2)` → subarray `{2,1,3}`, sorted `{1,2,3}`, 2nd smallest = 2. The descent compares left-child counts of `roots[3]` minus `roots[0]` to pick the bucket holding value 2.

**Complexity.** Build `O(N log m)`; each query `O(log m)`. **Space.** `O(N log m)` nodes. **Edge cases.** duplicate values (counts accumulate per rank), `k=1` (minimum), `k = r−l+1` (maximum), coordinate compression maps back via `sortedUniq[rank-1]`.

---

### Problem 41: Range GCD with Point Update — Segment Tree (non-invertible aggregate)
**Statement.** Support `update(i, val)` and `queryGcd(l, r)` = the greatest common divisor of `nums[l..r]`. (Classic; GCD is associative but **not invertible**, so Fenwick can't do it — a segment tree is the clean fit.)

**Constraints.** ≤ 10⁵ elements; values up to `10⁹`; interleaved ops up to 10⁵.

**Approach.** GCD is associative and idempotent but you cannot "subtract" a prefix gcd, ruling out Fenwick and prefix arrays for the mutable case. A segment tree storing `gcd` per node answers each query by combining the `O(log N)` covering segments with `Math.gcd`. The identity element for the no-overlap case is `0` because `gcd(0, x) = x`. Point update rewrites a leaf and recomputes ancestor gcds.

```java
class SegTreeGcd {
    private final long[] tree;
    private final int n;

    SegTreeGcd(int[] a) {
        n = a.length;
        tree = new long[4 * n];
        build(a, 1, 0, n - 1);
    }
    private static long gcd(long a, long b) {       // gcd(0,x)=x is the identity
        while (b != 0) { long t = a % b; a = b; b = t; }
        return a;
    }
    private void build(int[] a, int node, int lo, int hi) {
        if (lo == hi) { tree[node] = a[lo]; return; }
        int mid = (lo + hi) >>> 1;
        build(a, 2 * node, lo, mid);
        build(a, 2 * node + 1, mid + 1, hi);
        tree[node] = gcd(tree[2 * node], tree[2 * node + 1]);
    }
    void update(int idx, int val) { update(1, 0, n - 1, idx, val); }
    private void update(int node, int lo, int hi, int idx, int val) {
        if (lo == hi) { tree[node] = val; return; }
        int mid = (lo + hi) >>> 1;
        if (idx <= mid) update(2 * node, lo, mid, idx, val);
        else            update(2 * node + 1, mid + 1, hi, idx, val);
        tree[node] = gcd(tree[2 * node], tree[2 * node + 1]);
    }
    long queryGcd(int l, int r) { return query(1, 0, n - 1, l, r); }
    private long query(int node, int lo, int hi, int l, int r) {
        if (r < lo || hi < l) return 0;             // identity for gcd
        if (l <= lo && hi <= r) return tree[node];
        int mid = (lo + hi) >>> 1;
        return gcd(query(2 * node, lo, mid, l, r),
                   query(2 * node + 1, mid + 1, hi, l, r));
    }
}
```
**Dry run.** `[12, 6, 9, 4]`. `queryGcd(0,2)` → `gcd(12,6,9)` = 3. `update(2, 18)` → `queryGcd(0,2)` = `gcd(12,6,18)` = 6. `queryGcd(3,3)` = 4.

**Complexity.** `O(log N · log V)` per op (the inner `gcd` costs `O(log V)`), build `O(N log V)`. **Space.** `O(4N)`. **Edge cases.** `0` as the no-overlap identity (`gcd(0,x)=x`), single-element range, values up to `10⁹` use `long` to be safe, range with a zero element makes gcd the gcd of the rest.

---

### Problem 42: Number of Pairs Satisfying Inequality — Fenwick over (nums1 − nums2)
**Statement.** Given arrays `nums1`, `nums2` of equal length and an integer `diff`, count pairs `(i, j)` with `i < j` and `nums1[i] − nums1[j] ≤ nums2[i] − nums2[j] + diff`. (LeetCode 2426.)

**Constraints.** `1 ≤ length ≤ 10⁵`; values in `[-10⁴, 10⁴]`; `diff` fits in `int`.

**Approach.** Rearrange: let `a[i] = nums1[i] − nums2[i]`. The condition becomes `a[i] − a[j] ≤ diff`, i.e. `a[i] ≤ a[j] + diff`. Sweep `j` from left to right; for each `j` count earlier indices `i < j` with `a[i] ≤ a[j] + diff` — a classic prefix-count answered by a **frequency Fenwick** over the value domain of `a` (offset to make indices positive, then coordinate-compress, or just shift since the range is bounded). Insert `a[j]` after counting. Each step `O(log N)`.

```java
class Solution {
    private int[] tree;
    private int size;

    public long numberOfPairs(int[] nums1, int[] nums2, int diff) {
        int n = nums1.length;
        long[] a = new long[n];
        for (int i = 0; i < n; i++) a[i] = (long) nums1[i] - nums2[i];

        // coordinate set: every a[j] and every probe value a[j] + diff
        TreeSet<Long> set = new TreeSet<>();
        for (long v : a) { set.add(v); set.add(v + diff); }
        long[] sorted = set.stream().mapToLong(Long::longValue).toArray();
        size = sorted.length;
        tree = new int[size + 1];

        long count = 0;
        for (int j = 0; j < n; j++) {
            int r = rankLE(sorted, a[j] + diff);     // # of inserted a[i] <= a[j] + diff
            count += query(r);
            update(rankExact(sorted, a[j]));         // insert a[j]
        }
        return count;
    }
    private int rankLE(long[] s, long key) {         // count of values <= key (1-indexed)
        int lo = 0, hi = s.length;
        while (lo < hi) { int m = (lo + hi) >>> 1; if (s[m] <= key) lo = m + 1; else hi = m; }
        return lo;
    }
    private int rankExact(long[] s, long key) {      // exact 1-indexed position
        int lo = 0, hi = s.length - 1;
        while (lo < hi) { int m = (lo + hi) >>> 1; if (s[m] < key) lo = m + 1; else hi = m; }
        return lo + 1;
    }
    private void update(int i) { for (; i <= size; i += i & (-i)) tree[i]++; }
    private int query(int i) { int s = 0; for (; i > 0; i -= i & (-i)) s += tree[i]; return s; }
}
```
**Dry run.** `nums1=[3,2,5]`, `nums2=[2,2,1]`, `diff=1`. `a=[1,0,4]`. Pairs `i<j` with `a[i] ≤ a[j]+1`: `(0,1)`: `1 ≤ 0+1=1` ✓; `(0,2)`: `1 ≤ 4+1` ✓; `(1,2)`: `0 ≤ 5` ✓ → 3.

**Complexity.** `O(N log N)`. **Space.** `O(N)`. **Edge cases.** negative `a` values handled by compression, `diff` negative, duplicates (`rankLE` counts `≤`), `long` used for `a` to avoid `int` subtraction overflow.

---

### Problem 43: Longest Increasing Subsequence II — Segment Tree of Max DP (bounded gap)
**Statement.** Given `nums` and integer `k`, return the length of the longest strictly increasing subsequence where consecutive chosen elements differ by at most `k` (`0 < nums[j] − nums[i] ≤ k`). (LeetCode 2407.)

**Constraints.** `1 ≤ length ≤ 10⁵`; `1 ≤ nums[i], k ≤ 10⁵`.

**Approach.** The `O(N²)` DP `dp[x]` = best LIS ending at value `x` becomes `O(N log V)` with a **segment tree keyed by value** storing `max` LIS length. For each value `v` in order, query the **max over the value window `[v−k, v−1]`**, set `dp[v] = thatMax + 1`, and point-update position `v` in the tree. The answer is the global max. The window query is exactly a range-max on the value-indexed segment tree — Fenwick is awkward here because max isn't invertible.

```
value-indexed segment tree, range-max:
for v in nums:  best = queryMax(v-k, v-1); dp = best+1; update(v, dp)
```

```java
class Solution {
    private int[] tree;
    private int size;

    public int lengthOfLIS(int[] nums, int k) {
        int max = 0;
        for (int v : nums) max = Math.max(max, v);
        size = max;
        tree = new int[4 * (size + 1)];
        int ans = 0;
        for (int v : nums) {
            int lo = Math.max(1, v - k);
            int best = (lo <= v - 1) ? queryMax(1, 1, size, lo, v - 1) : 0;
            int dp = best + 1;
            ans = Math.max(ans, dp);
            update(1, 1, size, v, dp);
        }
        return ans;
    }
    private void update(int node, int lo, int hi, int pos, int val) {
        if (lo == hi) { tree[node] = Math.max(tree[node], val); return; }
        int mid = (lo + hi) >>> 1;
        if (pos <= mid) update(2 * node, lo, mid, pos, val);
        else            update(2 * node + 1, mid + 1, hi, pos, val);
        tree[node] = Math.max(tree[2 * node], tree[2 * node + 1]);
    }
    private int queryMax(int node, int lo, int hi, int l, int r) {
        if (r < lo || hi < l) return 0;
        if (l <= lo && hi <= r) return tree[node];
        int mid = (lo + hi) >>> 1;
        return Math.max(queryMax(2 * node, lo, mid, l, r),
                        queryMax(2 * node + 1, mid + 1, hi, l, r));
    }
}
```
**Dry run.** `nums=[4,2,1,4,3,4,5,8,15]`, `k=3`. The answer is 5 (`1,3,4,5,8`). Processing `8`, the window `[5,7]` yields the best 4 (ending at 5) → `dp=5`. `15`'s window `[12,14]` is empty → `dp=1`.

**Complexity.** `O(N log V)` where `V = max value`. **Space.** `O(V)`. **Edge cases.** `v - k < 1` clamped to 1, empty window (`lo > v-1`) gives best 0 → `dp=1`, strict increase enforced by querying up to `v-1` (not `v`), duplicate values don't extend each other.

---

### Problem 44: Count Good Triplets in an Array — Two Fenwick Sweeps
**Statement.** Given two permutations `nums1`, `nums2` of `[0..n−1]`, count triples of values that appear in increasing position order in **both** arrays (a "good triplet" is a set of three values whose relative order of positions is the same in both arrays). (LeetCode 2179.)

**Constraints.** `3 ≤ n ≤ 10⁵`; both are permutations of `0..n−1`.

**Approach.** Map each value to its position in `nums2`, giving an array `pos` indexed by `nums1`'s order: `pos[i] = position-in-nums2 of nums1[i]`. A good triplet centered at index `i` (in `nums1` order) needs a value to its **left in both** (`left` = values before `i` in `nums1` with smaller `pos`) and a value to its **right in both** (`right` = values after `i` in `nums1` with larger `pos`). Compute `left[i]` with a forward **Fenwick** over `pos` values (count of seen positions `< pos[i]`), and `right[i]` = `(n−1−i) − (count of already-seen-from-right with larger pos)`, derived symmetrically. Sum `left[i] · right[i]`. Two `O(N log N)` sweeps.

```java
class Solution {
    public long goodTriplets(int[] nums1, int[] nums2) {
        int n = nums1.length;
        int[] posInB = new int[n];
        for (int i = 0; i < n; i++) posInB[nums2[i]] = i;
        // p[i] = position in nums2 of the i-th value of nums1
        int[] p = new int[n];
        for (int i = 0; i < n; i++) p[i] = posInB[nums1[i]];

        long[] leftSmaller = new long[n];     // values before i (in nums1) with smaller pos
        long[] rightLarger = new long[n];     // values after i  (in nums1) with larger  pos

        int[] bit = new int[n + 1];
        for (int i = 0; i < n; i++) {
            leftSmaller[i] = query(bit, p[i]);          // # earlier with pos < p[i] (pos+1 strictly)
            update(bit, p[i] + 1, n);
        }
        Arrays.fill(bit, 0);
        for (int i = n - 1; i >= 0; i--) {
            int seenWithLargerPos = query(bit, n) - query(bit, p[i] + 1);   // among already seen (to the right)
            rightLarger[i] = seenWithLargerPos;
            update(bit, p[i] + 1, n);
        }
        long ans = 0;
        for (int i = 0; i < n; i++) ans += leftSmaller[i] * rightLarger[i];
        return ans;
    }
    private void update(int[] bit, int i, int n) { for (; i <= n; i += i & (-i)) bit[i]++; }
    private int query(int[] bit, int i) { int s = 0; for (; i > 0; i -= i & (-i)) s += bit[i]; return s; }
}
```
**Dry run.** `nums1=[2,0,1,3]`, `nums2=[0,1,2,3]`. `posInB=[0,1,2,3]`, `p=[2,0,1,3]`. For value `3` (index 3 in nums1, p=3): `leftSmaller=3` (positions 0,1,2 all smaller, but only those appearing earlier in nums1 — here 2,0,1 → 3), `rightLarger=0`. Summing `left·right` across centers yields **1** good triplet `(0,1,3)`.

**Complexity.** `O(N log N)`. **Space.** `O(N)`. **Edge cases.** permutations guarantee distinct positions (no ties), `n < 3` impossible per constraints but would give 0, the product `left·right` needs `long` to avoid overflow (`~n²` terms).

---

### Problem 45: Minimum Integer After K Adjacent Swaps — Fenwick for Shifting Costs
**Statement.** Given a numeric string `num` and an integer `k`, return the smallest string obtainable by at most `k` **adjacent** swaps of digits. (LeetCode 1505.)

**Constraints.** `1 ≤ |num| ≤ 3·10⁴`; `1 ≤ k ≤ 10⁹`; digits `0–9`.

**Approach.** Greedily build the result left to right: for each output position, pick the smallest digit reachable within the remaining swap budget. The cost to bring a chosen digit to the front is the number of **not-yet-used digits currently before it** — which shifts as earlier digits are removed. A **Fenwick tree** tracks which original indices are still present, so the real move cost = `(original index) − (number of already-used indices before it)` = `bit.query(index)`. Maintain, for each digit `0–9`, a queue of its original indices; among affordable candidates choose the smallest digit, mark its index used (`bit.update(idx, +1)` and decrement the live count). Each placement is `O(10 · log N)`.

```java
class Solution {
    public String minInteger(String num, int k) {
        int n = num.length();
        // queues of original indices for each digit 0..9
        Deque<Integer>[] pos = new ArrayDeque[10];
        for (int d = 0; d < 10; d++) pos[d] = new ArrayDeque<>();
        for (int i = 0; i < n; i++) pos[num.charAt(i) - '0'].addLast(i);

        boolean[] used = new boolean[n];
        int[] bit = new int[n + 1];          // counts USED indices, for shift correction
        StringBuilder sb = new StringBuilder();

        for (int placed = 0; placed < n; placed++) {
            for (int d = 0; d < 10; d++) {
                if (pos[d].isEmpty()) continue;
                int idx = pos[d].peekFirst();
                // current position = idx - (#used before idx); cost to move to front of remaining
                int cost = idx - query(bit, idx);
                if (cost <= k) {
                    k -= cost;
                    sb.append((char) ('0' + d));
                    pos[d].pollFirst();
                    update(bit, idx + 1, n);   // mark idx used (1-indexed)
                    break;
                }
            }
        }
        return sb.toString();
    }
    private void update(int[] bit, int i, int n) { for (; i <= n; i += i & (-i)) bit[i]++; }
    private int query(int[] bit, int i) { int s = 0; for (; i > 0; i -= i & (-i)) s += bit[i]; return s; }
}
```
**Dry run.** `num="4321"`, `k=4`. Bring `1`(idx3) to front: cost `3 − 0 = 3 ≤ 4` → `k=1`, result `"1"`. Among remaining `4,3,2`: `2`(idx2) costs `2 − 0 = 2 > 1` (used idx3 lies after, so nothing before is used); `3`(idx1) costs `1 − 0 = 1 ≤ 1` → `k=0`, result `"13"`. Among `4,2`: `2`(idx2) now costs `2 − 1 = 1 > 0` (idx1 is used and lies before), so we fall back to `4`(idx0) cost 0, then `2` → `"1342"`.

**Complexity.** `O(N · 10 · log N)`. **Space.** `O(N)`. **Edge cases.** `k` larger than any needed (fully sorted ascending), leading-zero digits move just like any other (no special-casing needed since we build greedily), `k=0` returns `num` unchanged, equal digits keep stable relative order via the per-digit queue.

---

### Problem 46: Count Pairs With XOR in a Range — Bitwise Trie of Prefix Counts
**Statement.** Given `nums` and bounds `low, high`, count pairs `(i, j)` with `i < j` and `low ≤ nums[i] XOR nums[j] ≤ high`. (LeetCode 1803.)

**Constraints.** `1 ≤ length ≤ 2·10⁴`; `1 ≤ nums[i], low, high ≤ 2·10⁴` (15 bits).

**Approach.** Reduce "in `[low, high]`" to two "`< limit`" counts: `f(high+1) − f(low)`, where `f(limit)` = number of pairs with XOR `< limit`. To compute `f(limit)` with a **bitwise trie storing a count at every node**: insert numbers one by one; for each new `x`, walk the trie from the MSB. At bit `b` of `limit`: if that bit is 1, every number sharing the path-so-far but taking the bit that makes XOR's bit 0 contributes a guaranteed `< limit` pair (add that subtree's count), then descend the branch keeping XOR's bit = 1; if the bit is 0, you must descend the branch keeping XOR's bit = 0. Each insert+count is `O(15)`.

```java
class Solution {
    static class Node { Node[] c = new Node[2]; int count; }
    private static final int BITS = 15;       // 2*10^4 < 2^15
    private final Node root = new Node();

    public int countPairs(int[] nums, int low, int high) {
        int ans = 0;
        for (int x : nums) {
            ans += countLess(x, high + 1) - countLess(x, low);
            insert(x);
        }
        return ans;
    }
    private void insert(int x) {
        Node cur = root;
        for (int b = BITS - 1; b >= 0; b--) {
            int bit = (x >> b) & 1;
            if (cur.c[bit] == null) cur.c[bit] = new Node();
            cur = cur.c[bit];
            cur.count++;
        }
    }
    // # of already-inserted y with (x XOR y) < limit
    private int countLess(int x, int limit) {
        Node cur = root;
        int res = 0;
        for (int b = BITS - 1; b >= 0 && cur != null; b--) {
            int xb = (x >> b) & 1;
            int lb = (limit >> b) & 1;
            if (lb == 1) {
                // taking y-bit = xb makes XOR bit 0 (< limit guaranteed for this whole subtree)
                if (cur.c[xb] != null) res += cur.c[xb].count;
                cur = cur.c[xb ^ 1];          // continue where XOR bit = 1 (still possibly < limit)
            } else {
                cur = cur.c[xb];              // must keep XOR bit = 0
            }
        }
        return res;
    }
}
```
**Dry run.** `nums=[1,4,2,7]`, `low=2, high=6`. Pairs and XORs: `1^4=5`,`1^2=3`,`1^7=6`,`4^2=6`,`4^7=3`,`2^7=5`. In `[2,6]`: 5,3,6,6,3,5 → all six qualify → 6. The trie computes `f(7) − f(2)` to the same total.

**Complexity.** `O(N · 15)`. **Space.** `O(N · 15)` trie nodes. **Edge cases.** `low = 1` (the lower `f(low)` still excludes XOR `0`, but pairs have distinct indices not necessarily distinct values), insertion order makes each pair counted once (`i < j`), single element → 0, duplicate values produce XOR 0 which is `< low` for `low ≥ 1` so excluded.

---

### Problem 47: Number of Distinct Elements in Subarrays — Offline Fenwick (HH on a tree)
**Statement.** Given a static array and offline queries `(l, r)`, return the number of **distinct** values in `nums[l..r]`. (Classic "DQUERY" / SPOJ; the offline-BIT technique.)

**Constraints.** ≤ 10⁵ elements and queries; values fit in `int`.

**Approach.** Sort queries by **right endpoint** ascending. Sweep `r` from left to right, maintaining a Fenwick where position `i` holds `1` iff `i` is the **most recent** occurrence of `nums[i]` seen so far. When you advance to a new index `r`, if `nums[r]` appeared earlier at index `prev`, set `bit[prev] -= 1` (it's no longer the latest) and set `bit[r] += 1`. Then any query ending at `r` answers `distinct(l, r) = prefixSum(r) − prefixSum(l−1)`, because each distinct value contributes exactly one `1` at its latest position within `[1, r]`. Each step `O(log N)`.

```
keep only the LATEST occurrence of each value marked as 1:
nums = [1,2,1,3]    after r=2 (the second 1): unmark index0, mark index2
query (l=0,r=2) distinct = sum[0..2] with marks at {idx1(val2), idx2(val1)} = 2
```

```java
class Solution {
    public int[] distinctInRange(int[] nums, int[][] queries) {
        int n = nums.length, q = queries.length;
        Integer[] order = new Integer[q];
        for (int i = 0; i < q; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> queries[a][1] - queries[b][1]);   // by right endpoint

        int[] bit = new int[n + 1];
        Map<Integer, Integer> lastPos = new HashMap<>();               // value -> latest index
        int[] ans = new int[q];
        int r = 0;                                                     // next index to include
        for (int oi : order) {
            int ql = queries[oi][0], qr = queries[oi][1];
            while (r <= qr) {                                          // extend coverage to qr
                Integer prev = lastPos.get(nums[r]);
                if (prev != null) update(bit, prev + 1, n, -1);        // unmark old latest
                update(bit, r + 1, n, +1);                             // mark new latest
                lastPos.put(nums[r], r);
                r++;
            }
            ans[oi] = query(bit, qr + 1) - query(bit, ql);             // distinct in [ql, qr]
        }
        return ans;
    }
    private void update(int[] bit, int i, int n, int delta) { for (; i <= n; i += i & (-i)) bit[i] += delta; }
    private int query(int[] bit, int i) { int s = 0; for (; i > 0; i -= i & (-i)) s += bit[i]; return s; }
}
```
**Dry run.** `nums=[1,2,1,3]`, queries `[[0,2],[1,3]]`. Sorted by right: `[0,2]` then `[1,3]`. Extending to `r=2`: marks at idx1(val2) and idx2(val1, idx0 unmarked) → `distinct(0,2)= q(3)-q(0)=2`. Extending to `r=3`: mark idx3 → `distinct(1,3)= q(4)-q(1)=3` (values 2,1,3).

**Complexity.** `O((N + Q) log N + Q log Q)`. **Space.** `O(N + Q)`. **Edge cases.** repeated values only count once (latest-occurrence trick), queries must be sorted offline, `l=0` uses `query(bit, 0)=0`, empty range impossible if `l ≤ r`.

---

### Problem 48: Handling Sum Queries After Update — Lazy Segment Tree with Range Flip
**Statement.** Given a binary array `nums1` and an integer array `nums2`, process queries: type 1 `flip(l, r)` flips every bit of `nums1[l..r]`; type 2 `add(p)` does `nums2[i] += p · nums1[i]` for all `i`; type 3 returns the current sum of `nums2`. Return the answers to all type-3 queries. (LeetCode 2569.)

**Constraints.** `1 ≤ length ≤ 10⁵`; ≤ 10⁵ queries; values fit in `long`.

**Approach.** Observe that `add(p)` increases `sum(nums2)` by `p · (count of 1s in nums1)`. So you only need to maintain **how many 1s are currently in `nums1`** under range flips — a **lazy segment tree** over `nums1` storing the count of ones per node, with a boolean `flip` lazy tag. Flipping a range turns `ones` into `(rangeSize − ones)`; the lazy tag toggles. Keep a running `long total = sum(nums2)`; type 2 adds `p · ones(root)`; type 3 records `total`. Each flip is `O(log N)`.

```
node stores ones-count; flip lazy toggles ones -> (size - ones)
sum(nums2) tracked separately; add(p): total += p * tree.totalOnes()
```

```java
class Solution {
    private long[] ones;       // count of 1s in each node's range
    private boolean[] flip;    // lazy flip tag
    private int n;

    public long[] handleQuery(int[] nums1, int[] nums2, int[][] queries) {
        n = nums1.length;
        ones = new long[4 * n];
        flip = new boolean[4 * n];
        build(nums1, 1, 0, n - 1);

        long total = 0;
        for (int v : nums2) total += v;

        List<Long> out = new ArrayList<>();
        for (int[] q : queries) {
            if (q[0] == 1) {
                update(1, 0, n - 1, q[1], q[2]);
            } else if (q[0] == 2) {
                total += (long) q[1] * ones[1];        // add p * (#ones)
            } else {
                out.add(total);
            }
        }
        long[] ans = new long[out.size()];
        for (int i = 0; i < ans.length; i++) ans[i] = out.get(i);
        return ans;
    }
    private void build(int[] a, int node, int lo, int hi) {
        if (lo == hi) { ones[node] = a[lo]; return; }
        int mid = (lo + hi) >>> 1;
        build(a, 2 * node, lo, mid);
        build(a, 2 * node + 1, mid + 1, hi);
        ones[node] = ones[2 * node] + ones[2 * node + 1];
    }
    private void applyFlip(int node, int lo, int hi) {
        ones[node] = (hi - lo + 1) - ones[node];       // ones become zeros and vice versa
        flip[node] = !flip[node];
    }
    private void pushDown(int node, int lo, int hi) {
        if (flip[node]) {
            int mid = (lo + hi) >>> 1;
            applyFlip(2 * node, lo, mid);
            applyFlip(2 * node + 1, mid + 1, hi);
            flip[node] = false;
        }
    }
    private void update(int node, int lo, int hi, int l, int r) {
        if (r < lo || hi < l) return;
        if (l <= lo && hi <= r) { applyFlip(node, lo, hi); return; }
        pushDown(node, lo, hi);
        int mid = (lo + hi) >>> 1;
        update(2 * node, lo, mid, l, r);
        update(2 * node + 1, mid + 1, hi, l, r);
        ones[node] = ones[2 * node] + ones[2 * node + 1];
    }
}
```
**Dry run.** `nums1=[1,0,1]`, `nums2=[0,0,0]`, queries `[[1,1,1],[2,2,0],[3,0,0]]`. Flip `[1,1]` → `nums1=[1,1,1]`, ones=3. `add(2)` → `total += 2·3 = 6`. Type 3 → 6.

**Complexity.** `O((N + Q) log N)`. **Space.** `O(4N)`. **Edge cases.** type-2 reads the root's `ones` count directly (`O(1)` after lazy maintenance), `total` and the `p·ones` product use `long` to avoid overflow, flip lazy is a toggle (applying twice cancels), empty flip range guarded by overlap checks.

---

## Interview Q&A by Level

### 🟢 Basic
- **What is a trie and why is it faster than a hash set for prefix queries?** A trie shares prefixes along tree paths, so `startsWith` is `O(L)`; a hash set must scan all words for prefixes (`O(N·L)`).
- **When do you pick a Fenwick tree over a segment tree?** When the aggregate is invertible (sum, count) and you only need prefix/point operations — less code, half the memory, smaller constant.
- **Why is segment-tree space `4N`?** A segment tree over `N` leaves can have a height that wastes up to ~2× the next power-of-two; `4N` safely bounds the array form regardless of `N`.
- **What does `i & (-i)` compute?** The lowest set bit (`lowbit`), which equals the size of the range index `i` is responsible for in a BIT.

### 🟡 Intermediate
- **Explain lazy propagation.** Defer interval updates by storing a pending value on a node, applying it to the node's aggregate immediately, and pushing it to children only when a query/update must descend. Keeps range updates at `O(log N)`.
- **How do you count inversions with a BIT?** Coordinate-compress values, sweep left to right, and for each element query "how many already-inserted values are greater" (or sweep right and query smaller); accumulate.
- **Why can't a Fenwick tree do range-min directly?** Min isn't invertible — you can't subtract a prefix-min to get an interval min. (A BIT can do range-min with restrictions and two trees, but it's awkward; use a segment tree or sparse table.)
- **How do you delete from a trie?** Walk to the word, unset `isEnd`, then prune nodes upward while they have no children and aren't word ends (often tracked via a child-count or reference count).

### 🟠 Advanced
- **Give the amortized argument for why lazy propagation stays `O(log N)`.** Each query/update visits `O(log N)` nodes; `pushDown` does `O(1)` work per visited node, and the number of nodes touched at each level is bounded by a constant (at most 2 partial segments per level), so total work is `O(log N)`.
- **Compare segment tree vs BIT vs sparse table for range-min.** BIT: poor fit. Segment tree: `O(N)` build, `O(log N)` query, supports updates. Sparse table: `O(N log N)` build, `O(1)` query, but immutable. Pick sparse table for static idempotent queries (min/max/gcd), segment tree if updates are needed.
- **What is a persistent segment tree and when is it used?** A versioned segment tree where each update creates `O(log N)` new nodes sharing the rest, enabling queries on any historical version — used for "k-th smallest in a range" (mergesort/wavelet alternatives) and offline range problems.
- **How would a trie support fuzzy/edit-distance matching for spell-check?** Run a DFS over the trie carrying a row of a Levenshtein DP table; prune branches whose minimum possible distance already exceeds the threshold.

### 🔴 Expert
- **How do search engines use tries at scale?** Production autocomplete uses compressed/radix tries (PATRICIA) or finite-state transducers (FSTs, as in Lucene) to fit dictionaries in memory, often sharded by prefix and ranked by frequency/weight stored at terminal nodes; ternary search trees trade some speed for far less memory than 26-way nodes.
- **How do you scale range analytics beyond a single machine?** Partition the key space, keep per-shard Fenwick/segment structures, and merge partial aggregates (associativity makes this clean for sum/min/max). For append-only time-series, Fenwick trees over fixed buckets give `O(log N)` rolling-window sums; columnar stores precompute hierarchical prefix aggregates (the same idea as a segment tree).
- **Segment tree beats / "Chtholly tree" — what problem do they solve?** Segment-tree-beats handles tag combinations (e.g. range-`min`-assign + range-sum) that ordinary lazy can't, by recursing further but bounding total work amortized to `O(N log² N)`. Chtholly (ODT) exploits random/assign-heavy data to keep intervals few.
- **Iterative vs recursive segment trees — why does it matter in production?** The iterative bottom-up form (Al.Cash style) removes recursion overhead and is cache-friendlier, important in hot paths; the trade-off is that lazy propagation is significantly harder to express iteratively, so recursive forms dominate when range updates are needed.

---

## ⚠️ Common Pitfalls
- **Fenwick is 1-indexed.** Off-by-one between the external 0-based array and internal `tree[1..n]` is the #1 bug; convert with `index + 1`.
- **Segment-tree array too small.** Always size `4N`; `2N` only works for the iterative bottom-up variant on a power-of-two padded array.
- **Forgetting `pushDown`.** Reading children without first pushing the parent's lazy value yields stale aggregates.
- **Lazy for "assign" vs "add".** Addition accumulates (`lazy += val`); assignment overwrites and needs a separate "assigned" flag, otherwise a later add gets lost or doubled.
- **Trie memory blow-up.** 26-pointer nodes are wasteful for sparse/large alphabets; switch to `HashMap` children or a ternary search tree.
- **Coordinate compression mistakes.** Counting problems break if ranks aren't deduplicated or if you query the wrong inclusive/exclusive bound (`query(x-1)` for strictly-smaller).
- **Integer overflow in sum trees.** Use `long` for segment/Fenwick sums when values × count can exceed `int`.
- **Reverse pairs (LeetCode 493) with BIT.** Comparing `nums[j]` against `2·nums[i]` needs the doubled values compressed into the same coordinate space — easy to mismatch.

## 📚 Further Reading
- *Competitive Programming 4* (Halim & Halim) — chapters on BIT and segment trees.
- CP-Algorithms: [Fenwick Tree](https://cp-algorithms.com/data_structures/fenwick.html), [Segment Tree](https://cp-algorithms.com/data_structures/segment_tree.html).
- Peter Brass, *Advanced Data Structures* — tries, radix/PATRICIA trees, persistence.
- Al.Cash, "Efficient and easy segment trees" (Codeforces blog) — iterative segment trees.
- Lucene/FST internals and Google's "Smart Compose / autocomplete" engineering blogs — tries and FSTs in real search systems.
- LeetCode tags: `Trie`, `Segment Tree`, `Binary Indexed Tree` — problems 208, 211, 212, 307, 308, 315, 327, 421, 493, 648, 699, 715.
