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
