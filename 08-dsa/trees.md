# Trees (Binary, BST, AVL, B-Trees)

Trees are the backbone of hierarchical data, from filesystem layouts and DOM nodes to database indexes and autocomplete engines. This guide walks from the humble binary tree up through self-balancing structures (AVL, red-black) and disk-friendly B-trees, with idiomatic Java and the patterns interviewers actually probe.

[← Back to master index](../README.md) · [← DSA index](README.md)

---

## Concept & Intuition

A **tree** is a connected acyclic graph: `N` nodes joined by exactly `N-1` edges, with a distinguished **root** and a parent→children relationship. The defining property is that there is exactly one path between any two nodes.

Key vocabulary:

- **Node / edge / root / leaf**: a leaf has no children.
- **Height**: longest root-to-leaf edge count. **Depth**: edges from the root to a node.
- **Binary tree**: each node has ≤ 2 children (`left`, `right`).
- **Binary Search Tree (BST)**: for every node, all keys in the left subtree are `<` the node, all keys in the right subtree are `>` the node. This invariant makes search/insert/delete `O(h)`.
- **Balanced tree**: height stays `O(log n)`. AVL and red-black trees enforce this with rotations.
- **B-tree / B+-tree**: multi-way balanced trees with high fan-out, designed so each node maps to a disk block.

**When to use which**

| Need | Structure |
|------|-----------|
| Ordered in-memory set/map, fast `O(log n)` ops | BST → AVL / red-black (Java `TreeMap`) |
| Prefix lookup, autocomplete, dictionary | Trie |
| On-disk index, range scans, billions of rows | B+-tree (databases) |
| Hierarchy / parse tree / expression eval | Plain binary or n-ary tree |

**The core invariant for a BST** — visualized below. An *inorder* traversal of a BST yields keys in sorted order; this single fact powers validation, the k-th smallest element, range queries, and more.

```
            8                  Inorder (L, root, R):
          /   \                  1 3 4 6 7 8 10 13 14
        3      10                       ↑ always sorted for a BST
       / \       \
      1   6      14
         / \     /
        4   7  13
```

The recursion mantra for almost every tree problem: **"What do I need from my left subtree and my right subtree to answer the question at this node?"** Define that, return it bottom-up, and most problems collapse into a clean post-order recursion.

---

## Complexity Cheat-Sheet

`h` = height, `n` = number of nodes. For a balanced tree `h = O(log n)`; for a degenerate (linked-list) tree `h = O(n)`.

| Operation | Binary Tree | BST (avg) | BST (worst) | AVL / RB | B+-tree |
|-----------|-------------|-----------|-------------|----------|---------|
| Search | O(n) | O(log n) | O(n) | O(log n) | O(log_b n) |
| Insert | O(1)* | O(log n) | O(n) | O(log n) | O(log_b n) |
| Delete | O(n) | O(log n) | O(n) | O(log n) | O(log_b n) |
| Traversal (any order) | O(n) | O(n) | O(n) | O(n) | O(n) |
| Find min / max | O(n) | O(log n) | O(n) | O(log n) | O(log_b n) |
| Predecessor / successor | O(n) | O(log n) | O(n) | O(log n) | O(log_b n) |
| Height / diameter | O(n) | O(n) | O(n) | O(n) | O(n) |
| Space (recursion stack) | O(h) | O(h) | O(h) | O(log n) | O(log_b n) |
| Morris traversal space | O(1) | — | — | — | — |

`*` Insert at a known position is O(1); finding the position is the variable cost. `b` is the B-tree branching factor (often hundreds), making the log base huge and tree height tiny (3–4 levels for billions of keys).

---

## Patterns & Recognition

Reach for tree techniques when you see these signals:

1. **"Given the root of a binary tree…"** — almost always a DFS (pre/in/post) or BFS (level-order) recursion. Decide top-down vs bottom-up first.
2. **Bottom-up aggregation** ("max path sum", "diameter", "balanced check", "LCA"): compute a value per subtree and combine at the parent. Use a single recursion that returns the local quantity while updating a global answer.
3. **Sorted / k-th / range** ("validate BST", "k-th smallest", "range sum", "closest value"): exploit the inorder = sorted property. Often an inorder traversal with early termination.
4. **Level-by-level** ("right side view", "zigzag", "level averages", "min depth", "connect next pointers"): BFS with a queue, processing one level per outer loop iteration.
5. **Reconstruction** ("build tree from preorder + inorder", serialize/deserialize): preorder gives the root, inorder splits left/right; recurse with index maps.
6. **Path problems** ("path sum", "count paths", "longest univalue path"): DFS carrying state down (running sum, prefix-sum map) or returning path length up.
7. **Prefix / dictionary / autocomplete**: think **trie**.
8. **"Keep it sorted while inserting/deleting fast" or "ordered map"**: balanced BST (AVL / red-black, i.e. `TreeMap`/`TreeSet`).
9. **"Index for a database / billions of rows on disk / range scan"**: B+-tree — the answer to almost every systems-design "how does the DB index work" question.

If recursion depth could blow the stack (skewed tree, `n` up to 10^5), mention an **iterative** variant or **Morris** traversal for `O(1)` space.

---

## Coding Problems

### Problem 1: Maximum Depth of a Binary Tree

> Given the root of a binary tree, return its maximum depth (number of nodes along the longest root-to-leaf path). Constraints: `0 ≤ n ≤ 10^4`, node values fit in `int`.

**Approach.** Brute force and optimal coincide here: the depth of a node is `1 + max(depth(left), depth(right))`. A classic bottom-up post-order recursion. An empty tree has depth 0.

```java
public class Solution {
    public int maxDepth(TreeNode root) {
        if (root == null) return 0;
        return 1 + Math.max(maxDepth(root.left), maxDepth(root.right));
    }
}

class TreeNode {
    int val; TreeNode left, right;
    TreeNode(int v) { val = v; }
}
```

**Dry run** on `[3,9,20,null,null,15,7]`: leaves 9, 15, 7 return 1. Node 20 returns `1 + max(1,1) = 2`. Root 3 returns `1 + max(1, 2) = 3`. ✅

**Time:** O(n) — visit each node once. **Space:** O(h) recursion stack.

**Follow-ups:** minimum depth (careful: a node with one child is *not* a leaf), iterative BFS depth, depth of an n-ary tree.

---

### Problem 2: Invert a Binary Tree

> Invert (mirror) a binary tree: swap every node's left and right children. Constraints: `0 ≤ n ≤ 100`.

**Approach.** Swap children at every node; recursion or a BFS/DFS stack both work. The famous "whiteboard the iterative version too" question.

```java
public class Solution {
    public TreeNode invertTree(TreeNode root) {
        if (root == null) return null;
        TreeNode left = invertTree(root.left);
        TreeNode right = invertTree(root.right);
        root.left = right;
        root.right = left;
        return root;
    }
}
```

**Dry run** on `[4,2,7,1,3,6,9]`: at node 2 we swap to get children `(3,1)`; at node 7 we get `(9,6)`; at root we swap subtrees → `[4,7,2,9,6,3,1]`. ✅

**Time:** O(n). **Space:** O(h).

**Follow-ups:** iterative with a queue; check if two trees are mirror images (symmetric-tree problem).

---

### Problem 3: Binary Tree Inorder Traversal (recursive, iterative, Morris)

> Return the inorder traversal of a binary tree's node values. Constraints: `0 ≤ n ≤ 100`. Follow-up: do it in O(1) extra space.

**Approach.** Three flavors interviewers love to escalate through:
1. **Recursive** — trivial, O(h) stack.
2. **Iterative with explicit stack** — push left spine, pop, visit, go right.
3. **Morris** — thread the tree using temporary right-pointers from the rightmost node of each left subtree, achieving **O(1)** extra space.

```java
import java.util.*;

public class Solution {
    // 2. Iterative
    public List<Integer> inorderIterative(TreeNode root) {
        List<Integer> out = new ArrayList<>();
        Deque<TreeNode> stack = new ArrayDeque<>();
        TreeNode cur = root;
        while (cur != null || !stack.isEmpty()) {
            while (cur != null) { stack.push(cur); cur = cur.left; }
            cur = stack.pop();
            out.add(cur.val);
            cur = cur.right;
        }
        return out;
    }

    // 3. Morris — O(1) space
    public List<Integer> inorderMorris(TreeNode root) {
        List<Integer> out = new ArrayList<>();
        TreeNode cur = root;
        while (cur != null) {
            if (cur.left == null) {
                out.add(cur.val);
                cur = cur.right;
            } else {
                TreeNode pred = cur.left;
                while (pred.right != null && pred.right != cur) pred = pred.right;
                if (pred.right == null) {       // create thread
                    pred.right = cur;
                    cur = cur.left;
                } else {                        // thread exists -> remove & visit
                    pred.right = null;
                    out.add(cur.val);
                    cur = cur.right;
                }
            }
        }
        return out;
    }
}
```

**Dry run (Morris)** on `1 → right 2 → left 3` (`[1,null,2,3]`): node 1 has no left, visit 1, go right to 2. Node 2's predecessor is 3; thread 3.right→2, descend to 3. Node 3 has no left, visit 3, follow thread to 2. Now 2's predecessor thread exists, remove it, visit 2. Result `[1,3,2]`. ✅

**Time:** O(n) all three (Morris visits each edge ≤ 2×). **Space:** O(h) recursive/iterative, **O(1)** Morris.

**Follow-ups:** preorder & postorder Morris (postorder needs reversing the right-boundary), why Morris mutates then restores the tree.

---

### Problem 4: Validate Binary Search Tree

> Determine whether a binary tree is a valid BST. Constraints: `1 ≤ n ≤ 10^4`, values in `int` range.

**Approach.** **Brute force:** for each node check that *all* left descendants are smaller — O(n²). **Optimal:** pass down an allowed `(low, high)` open interval; each node must lie strictly inside it. Use `long` bounds to dodge `Integer.MIN/MAX_VALUE` edge cases (or use nullable `Integer`). Equivalent: an inorder traversal must be strictly increasing.

```java
public class Solution {
    public boolean isValidBST(TreeNode root) {
        return valid(root, Long.MIN_VALUE, Long.MAX_VALUE);
    }
    private boolean valid(TreeNode n, long low, long high) {
        if (n == null) return true;
        if (n.val <= low || n.val >= high) return false;
        return valid(n.left, low, n.val) && valid(n.right, n.val, high);
    }
}
```

**Dry run** on `[5,1,4,null,null,3,6]`: root 5 ok in (−∞,∞). Right child 4 must be in (5,∞) but `4 ≤ 5` → false. Correctly rejected (4 is < its ancestor 5). ✅

**Time:** O(n). **Space:** O(h).

**Follow-ups:** recover a BST with exactly two swapped nodes; the classic bug of only comparing a node to its immediate children (insufficient).

---

### Problem 5: Lowest Common Ancestor

> Given the root and two nodes `p`, `q`, return their lowest common ancestor (the deepest node that has both as descendants). Two versions: general binary tree, and BST.

**Approach.** **BST version** is O(h): walk down — if both keys are smaller go left, both larger go right, otherwise the split point is the LCA. **General binary tree:** recurse; a node is the LCA if `p` and `q` are found in different subtrees (or the node itself is `p`/`q`).

```java
public class Solution {
    // General binary tree
    public TreeNode lowestCommonAncestor(TreeNode root, TreeNode p, TreeNode q) {
        if (root == null || root == p || root == q) return root;
        TreeNode left = lowestCommonAncestor(root.left, p, q);
        TreeNode right = lowestCommonAncestor(root.right, p, q);
        if (left != null && right != null) return root; // split point
        return left != null ? left : right;
    }

    // BST — exploits ordering, O(h)
    public TreeNode lcaBST(TreeNode root, TreeNode p, TreeNode q) {
        TreeNode cur = root;
        while (cur != null) {
            if (p.val < cur.val && q.val < cur.val) cur = cur.left;
            else if (p.val > cur.val && q.val > cur.val) cur = cur.right;
            else return cur;
        }
        return null;
    }
}
```

**Dry run** (general) with `p=5, q=1` in `[3,5,1,6,2,0,8]`: root 3 finds 5 in left, 1 in right → both non-null → LCA = 3. ✅

**Time:** O(n) general, O(h) BST. **Space:** O(h) / O(1).

**Follow-ups:** LCA with parent pointers (two-pointer like linked-list intersection); LCA of a *deepest leaves* set; LCA when nodes may not both exist (must verify presence).

---

### Problem 6: Symmetric Tree

> Check whether a binary tree is a mirror of itself about its center. Constraints: `1 ≤ n ≤ 1000`.

**Approach.** Compare two pointers moving outward in mirrored fashion: `left.left` against `right.right`, and `left.right` against `right.left`. Recursive or queue-based iterative.

```java
public class Solution {
    public boolean isSymmetric(TreeNode root) {
        return root == null || mirror(root.left, root.right);
    }
    private boolean mirror(TreeNode a, TreeNode b) {
        if (a == null && b == null) return true;
        if (a == null || b == null || a.val != b.val) return false;
        return mirror(a.left, b.right) && mirror(a.right, b.left);
    }
}
```

**Dry run** on `[1,2,2,3,4,4,3]`: compare 2 vs 2 (equal), then `(3 vs 3)` and `(4 vs 4)` outward — all match → symmetric. ✅

**Time:** O(n). **Space:** O(h).

**Follow-ups:** iterative version; check if tree `t` is the same as tree `s` (same-tree); subtree-of-another-tree.

---

### Problem 7: Binary Tree Level-Order & Zigzag Traversal

> Return values level by level (top→bottom). Variant: **zigzag** alternates left→right and right→left per level. Constraints: `0 ≤ n ≤ 2000`.

**Approach.** BFS with a queue; capture `queue.size()` at the start of each level to know how many nodes belong to it. For zigzag, reverse every other level (use a `Deque` / `addFirst` to avoid an explicit reversal cost).

```java
import java.util.*;

public class Solution {
    public List<List<Integer>> zigzagLevelOrder(TreeNode root) {
        List<List<Integer>> res = new ArrayList<>();
        if (root == null) return res;
        Queue<TreeNode> q = new LinkedList<>();
        q.offer(root);
        boolean leftToRight = true;
        while (!q.isEmpty()) {
            int size = q.size();
            LinkedList<Integer> level = new LinkedList<>();
            for (int i = 0; i < size; i++) {
                TreeNode node = q.poll();
                if (leftToRight) level.addLast(node.val);
                else level.addFirst(node.val);
                if (node.left != null) q.offer(node.left);
                if (node.right != null) q.offer(node.right);
            }
            res.add(level);
            leftToRight = !leftToRight;
        }
        return res;
    }
}
```

**Dry run** on `[3,9,20,null,null,15,7]`: level 0 `[3]` (L→R); level 1 `[20,9]` (R→L via addFirst); level 2 `[15,7]` (L→R) → `[[3],[20,9],[15,7]]`. ✅

**Time:** O(n). **Space:** O(n) for the queue (last level can hold up to n/2 nodes).

**Follow-ups:** right-side view (last node per level), average per level, level-order for n-ary trees, connect `next` pointers (Problem 12 territory).

---

### Problem 8: Balanced Binary Tree

> A height-balanced tree is one where every node's two subtrees differ in height by at most 1. Return true/false. Constraints: `0 ≤ n ≤ 5000`.

**Approach.** **Brute force:** compute height at every node and compare → O(n²). **Optimal:** a single bottom-up recursion that returns height, but returns a sentinel `-1` the moment any subtree is unbalanced, short-circuiting the rest.

```java
public class Solution {
    public boolean isBalanced(TreeNode root) {
        return height(root) != -1;
    }
    private int height(TreeNode n) {
        if (n == null) return 0;
        int l = height(n.left);
        if (l == -1) return -1;
        int r = height(n.right);
        if (r == -1) return -1;
        if (Math.abs(l - r) > 1) return -1;
        return 1 + Math.max(l, r);
    }
}
```

**Dry run** on `[1,2,2,3,3,null,null,4,4]`: the leftmost path is deep; at the grandparent the left height is 3 vs right 1 → diff 2 → returns −1 → false. ✅

**Time:** O(n) single pass. **Space:** O(h).

**Follow-ups:** why the −1 sentinel beats O(n²); definition differences (per-node vs global height balance); converting an unbalanced BST into a balanced one.

---

### Problem 9: Construct Binary Tree from Preorder & Inorder Traversal

> Given `preorder` and `inorder` arrays of a tree with unique values, reconstruct and return the tree. Constraints: `1 ≤ n ≤ 3000`.

**Approach.** Preorder's first element is always the current root. Find it in inorder: everything to its left is the left subtree, everything to the right is the right subtree. Recurse. Use a `HashMap` of value→inorder-index for O(1) splits, and a moving preorder pointer.

```java
import java.util.*;

public class Solution {
    private int preIdx = 0;
    private Map<Integer, Integer> inPos = new HashMap<>();
    private int[] preorder;

    public TreeNode buildTree(int[] preorder, int[] inorder) {
        this.preorder = preorder;
        for (int i = 0; i < inorder.length; i++) inPos.put(inorder[i], i);
        return build(0, inorder.length - 1);
    }
    private TreeNode build(int lo, int hi) {
        if (lo > hi) return null;
        int rootVal = preorder[preIdx++];
        TreeNode root = new TreeNode(rootVal);
        int mid = inPos.get(rootVal);
        root.left = build(lo, mid - 1);   // must build left first (preorder)
        root.right = build(mid + 1, hi);
        return root;
    }
}
```

**Dry run**: `preorder=[3,9,20,15,7]`, `inorder=[9,3,15,20,7]`. Root 3 (mid index 1 in inorder): left = `[9]`, right = `[15,20,7]`. Next preorder is 9 (leaf). Then 20 splits into left `[15]`, right `[7]`. Tree matches the classic shape. ✅

**Time:** O(n) with the hash map (O(n²) without). **Space:** O(n) for the map + O(h) stack.

**Follow-ups:** build from **inorder + postorder** (consume postorder from the end, build right subtree first); build from preorder + postorder (not unique in general); serialize/deserialize (Problem 11).

---

### Problem 10: Path Sum II (all root-to-leaf paths summing to target)

> Return all root-to-leaf paths whose node values sum to `targetSum`. Constraints: `0 ≤ n ≤ 5000`, values may be negative.

**Approach.** DFS carrying the running remaining sum and the current path. On reaching a leaf with `remaining == node.val`, snapshot the path. **Backtrack** by removing the node after exploring both children — the most common bug is forgetting this.

```java
import java.util.*;

public class Solution {
    public List<List<Integer>> pathSum(TreeNode root, int targetSum) {
        List<List<Integer>> res = new ArrayList<>();
        dfs(root, targetSum, new ArrayList<>(), res);
        return res;
    }
    private void dfs(TreeNode n, int rem, List<Integer> path, List<List<Integer>> res) {
        if (n == null) return;
        path.add(n.val);
        if (n.left == null && n.right == null && rem == n.val) {
            res.add(new ArrayList<>(path));      // copy snapshot
        } else {
            dfs(n.left, rem - n.val, path, res);
            dfs(n.right, rem - n.val, path, res);
        }
        path.remove(path.size() - 1);            // backtrack
    }
}
```

**Dry run** on `[5,4,8,11,null,13,4,7,2,null,null,5,1]`, target 22: path `5→4→11→2` sums to 22 ✅ and `5→8→4→5` sums to 22 ✅. Both captured.

**Time:** O(n) to traverse, O(n²) worst case to copy paths (each up to depth n). **Space:** O(h) recursion + O(paths × length) output.

**Follow-ups:** count paths that sum to target **anywhere** (not just root→leaf) using a prefix-sum HashMap in O(n) — Problem "Path Sum III"; return just the boolean existence (Path Sum I).

---

### Problem 11: Serialize and Deserialize a Binary Tree (hard)

> Design `serialize(root) -> String` and `deserialize(String) -> root` so that the round trip reproduces the tree. Constraints: `0 ≤ n ≤ 10^4`, any tree shape.

**Approach.** Preorder DFS with explicit null markers fully captures structure (inorder alone cannot). Serialize null as `#`; deserialize by consuming tokens in the same preorder order with a queue. BFS encoding (LeetCode-style with trailing nulls) is an equally valid alternative.

```java
import java.util.*;

public class Codec {
    private static final String NULL = "#", SEP = ",";

    public String serialize(TreeNode root) {
        StringBuilder sb = new StringBuilder();
        ser(root, sb);
        return sb.toString();
    }
    private void ser(TreeNode n, StringBuilder sb) {
        if (n == null) { sb.append(NULL).append(SEP); return; }
        sb.append(n.val).append(SEP);
        ser(n.left, sb);
        ser(n.right, sb);
    }

    public TreeNode deserialize(String data) {
        Deque<String> tokens = new ArrayDeque<>(Arrays.asList(data.split(SEP)));
        return des(tokens);
    }
    private TreeNode des(Deque<String> tokens) {
        String t = tokens.poll();
        if (NULL.equals(t)) return null;
        TreeNode node = new TreeNode(Integer.parseInt(t));
        node.left = des(tokens);
        node.right = des(tokens);
        return node;
    }
}
```

**Dry run**: tree `[1,2,3,null,null,4,5]` serializes to `1,2,#,#,3,4,#,#,5,#,#,`. Deserialize consumes `1` (root), recurses left consuming `2,#,#` (leaf 2), then right `3,4,#,#,5,#,#` rebuilding node 3 with children 4 and 5. Round trip identical. ✅

**Time:** O(n) both ways. **Space:** O(n) string + O(h) recursion.

**Follow-ups:** serialize a **BST** more compactly (preorder alone suffices — no null markers needed since bounds reconstruct structure); serialize an n-ary tree; make it space-efficient / encode to bytes.

---

### Problem 12: Diameter & Maximum Path Sum (senior-level)

> (a) **Diameter**: the length (in edges) of the longest path between any two nodes — may not pass through the root. (b) **Max Path Sum**: the maximum sum of node values along *any* path (path = sequence of adjacent nodes, need not touch root, values can be negative). Constraints: `1 ≤ n ≤ 3·10^4`.

**Approach.** Both are the canonical "return one thing, track a global another thing" pattern. At each node the recursion returns the best *downward* contribution (a single arm), while a global variable records the best *through-node* combination (left arm + node + right arm). For max path sum, clamp negative arms to 0 so they never drag the total down.

```java
public class Solution {
    private int best;

    // (a) Diameter in edges
    public int diameterOfBinaryTree(TreeNode root) {
        best = 0;
        depth(root);
        return best;
    }
    private int depth(TreeNode n) {
        if (n == null) return 0;
        int l = depth(n.left), r = depth(n.right);
        best = Math.max(best, l + r);          // path through n, in edges
        return 1 + Math.max(l, r);             // arm height returned upward
    }

    // (b) Maximum path sum
    public int maxPathSum(TreeNode root) {
        best = Integer.MIN_VALUE;
        gain(root);
        return best;
    }
    private int gain(TreeNode n) {
        if (n == null) return 0;
        int l = Math.max(gain(n.left), 0);     // ignore negative arms
        int r = Math.max(gain(n.right), 0);
        best = Math.max(best, n.val + l + r);  // path that bends at n
        return n.val + Math.max(l, r);         // straight arm to parent
    }
}
```

**Dry run (max path sum)** on `[-10,9,20,null,null,15,7]`: at node 20, `gain = 20 + max(15,7) = 35`, and the bend `15+20+7 = 42` updates `best`. Root −10's gain is `-10 + max(35,9) = 25` but `best` stays **42**. ✅ (The optimal path 15→20→7 never touches the root.)

**Dry run (diameter)** on a tree where the longest path is leaf→…→leaf through an internal node: `l + r` at that node gives the edge count without including the root.

**Time:** O(n). **Space:** O(h).

**Follow-ups:** diameter in **nodes** vs edges (off-by-one); longest **univalue** path; max path sum constrained to root→leaf; why a global mutable field is cleaner than threading the answer through return values (and how to do it purely with an `int[]` holder for thread-safety).

---

## 🧩 Extended Problems — Set 1: Classic Easy → Medium

### Problem 13: Same Tree — Parallel DFS

**Statement.** Given the roots of two binary trees `p` and `q`, return `true` if they are structurally identical and every corresponding node has the same value.

**Constraints.** `0 ≤ n ≤ 100` per tree; `-10^4 ≤ Node.val ≤ 10^4`.

**Approach.** Walk both trees in lockstep with a single recursion. At each step compare the two current nodes: if both are `null` they match; if exactly one is `null` (or values differ) they don't; otherwise recurse on the left pair and the right pair. This is the simplest expression of the "compare two trees in parallel" pattern and it is optimal because every node of both trees must be inspected at least once to certify equality. There is no shortcut that reads fewer nodes in the worst (equal) case.

```java
public class Solution {
    public boolean isSameTree(TreeNode p, TreeNode q) {
        if (p == null && q == null) return true;
        if (p == null || q == null || p.val != q.val) return false;
        return isSameTree(p.left, q.left) && isSameTree(p.right, q.right);
    }
}
```

**Complexity** — Time O(min(n, m)) since the first mismatch short-circuits, O(n) when equal; Space O(min(h_p, h_q)) recursion stack. **Edge cases:** both empty (equal), one empty and one not, same values but different shape (e.g. left-only vs right-only child) must return `false`.

---

### Problem 14: Subtree of Another Tree — DFS + Same-Tree Check

**Statement.** Given the roots `root` and `subRoot`, return `true` if there is a node in `root` such that the subtree rooted there is identical (same structure and values) to `subRoot`.

**Constraints.** `1 ≤ nodes(root) ≤ 2000`, `1 ≤ nodes(subRoot) ≤ 1000`, values in `int` range.

**Approach.** For every node of `root`, test whether the subtree anchored there equals `subRoot` using the same-tree routine from Problem 13. This straightforward solution is O(n·m). It is the expected interview answer; the optimal O(n+m) variant serializes both trees with null/sentinel markers and runs a string-matching algorithm (KMP) to detect `subRoot`'s serialization as a substring — mention it as a follow-up. Always anchor serialization tokens with delimiters and value markers so that, e.g., value `12` cannot masquerade as `1` followed by `2`.

```java
public class Solution {
    public boolean isSubtree(TreeNode root, TreeNode subRoot) {
        if (root == null) return subRoot == null;
        if (sameTree(root, subRoot)) return true;
        return isSubtree(root.left, subRoot) || isSubtree(root.right, subRoot);
    }
    private boolean sameTree(TreeNode a, TreeNode b) {
        if (a == null && b == null) return true;
        if (a == null || b == null || a.val != b.val) return false;
        return sameTree(a.left, b.left) && sameTree(a.right, b.right);
    }
}
```

**Complexity** — Time O(n·m) worst case (every node triggers an O(m) comparison); Space O(h) recursion. **Edge cases:** `subRoot` equal to the whole `root`; identical values appearing in many places forcing repeated comparisons; a single-node `subRoot` matching any equal-valued leaf or internal node.

---

### Problem 15: Minimum Depth of a Binary Tree — BFS / Careful DFS

**Statement.** Return the minimum depth: the number of nodes along the shortest path from the root down to the **nearest leaf**.

**Constraints.** `0 ≤ n ≤ 10^5`; values in `int` range.

**Approach.** The classic trap: a node with only one child is **not** a leaf, so you cannot simply take `1 + min(left, right)` — that would wrongly return a short depth through the missing child. BFS is the most natural and efficient choice: process level by level and return the depth of the first leaf encountered, short-circuiting immediately. This beats DFS in practice because a shallow leaf ends the search early, whereas DFS must explore full subtrees.

```
        1            BFS levels: depth 1 -> [1]
       /             depth 2 -> [2]  (node 2 has only a left child,
      2                          so it is NOT a leaf)
     /               depth 3 -> [3]  first leaf -> answer 3
    3
```

```java
import java.util.*;

public class Solution {
    public int minDepth(TreeNode root) {
        if (root == null) return 0;
        Queue<TreeNode> q = new LinkedList<>();
        q.offer(root);
        int depth = 1;
        while (!q.isEmpty()) {
            int size = q.size();
            for (int i = 0; i < size; i++) {
                TreeNode node = q.poll();
                if (node.left == null && node.right == null) return depth;
                if (node.left != null) q.offer(node.left);
                if (node.right != null) q.offer(node.right);
            }
            depth++;
        }
        return depth;
    }
}
```

**Complexity** — Time O(n) worst case but typically far less due to early exit; Space O(w) for the queue (`w` = max width). **Edge cases:** empty tree → 0; a skewed tree where every internal node has exactly one child (answer = total node count); single node → 1.

---

### Problem 16: Binary Tree Right Side View — Level-Order Last Node

**Statement.** Imagine standing on the right side of the tree; return the values of the nodes visible from top to bottom (the rightmost node of each level).

**Constraints.** `0 ≤ n ≤ 100`; values in `int` range.

**Approach.** BFS level by level and record the **last** node dequeued at each level — that node is the rightmost and therefore visible. Capturing `queue.size()` at the start of each level cleanly delimits levels. An alternative DFS visits right child before left and records the first node seen at each new depth; both are O(n). BFS is shown because it most directly matches the "per level" phrasing.

```java
import java.util.*;

public class Solution {
    public List<Integer> rightSideView(TreeNode root) {
        List<Integer> view = new ArrayList<>();
        if (root == null) return view;
        Queue<TreeNode> q = new LinkedList<>();
        q.offer(root);
        while (!q.isEmpty()) {
            int size = q.size();
            for (int i = 0; i < size; i++) {
                TreeNode node = q.poll();
                if (i == size - 1) view.add(node.val); // rightmost of this level
                if (node.left != null) q.offer(node.left);
                if (node.right != null) q.offer(node.right);
            }
        }
        return view;
    }
}
```

**Complexity** — Time O(n); Space O(w) for the queue. **Edge cases:** empty tree → empty list; left-skewed tree (the only node per level is still visible, so you see the left spine); a node whose deeper levels come only from a left subtree still contribute.

---

### Problem 17: Average of Levels in Binary Tree — BFS Accumulation

**Statement.** Return a list where the i-th element is the average value of the nodes on level `i`.

**Constraints.** `1 ≤ n ≤ 10^4`; node values fit in `int`, but a level's sum can exceed `int`, so accumulate in `long`.

**Approach.** BFS, summing each level into a `long` to avoid overflow when many large values appear on one level, then divide by the level count. This is optimal — every node must be read to compute its level's average, so O(n) is a lower bound.

```java
import java.util.*;

public class Solution {
    public List<Double> averageOfLevels(TreeNode root) {
        List<Double> res = new ArrayList<>();
        Queue<TreeNode> q = new LinkedList<>();
        q.offer(root);
        while (!q.isEmpty()) {
            int size = q.size();
            long sum = 0;
            for (int i = 0; i < size; i++) {
                TreeNode node = q.poll();
                sum += node.val;
                if (node.left != null) q.offer(node.left);
                if (node.right != null) q.offer(node.right);
            }
            res.add((double) sum / size);
        }
        return res;
    }
}
```

**Complexity** — Time O(n); Space O(w). **Edge cases:** a level full of `Integer.MAX_VALUE` would overflow an `int` accumulator (hence `long`); single node → one average equal to its value; deep skinny tree → each level has one node so averages equal those node values.

---

### Problem 18: Convert Sorted Array to Height-Balanced BST — Divide & Conquer

**Statement.** Given an integer array `nums` sorted in ascending order, build a height-balanced BST (the depths of the two subtrees of every node differ by at most 1).

**Constraints.** `1 ≤ n ≤ 10^4`; `nums` strictly increasing; values in `int` range.

**Approach.** Pick the middle element of the current range as the subtree root; everything left of it becomes the left subtree, everything right becomes the right subtree. Recursing on halves guarantees balance because each subtree's size differs by at most one. Since the input is already sorted, an inorder traversal of the result reproduces `nums`, so BST order is automatic. This is optimal at O(n) — every element becomes exactly one node.

```
nums = [-10, -3, 0, 5, 9]      mid = index 2 (value 0)

              0
            /   \
         -10     5
            \      \
            -3      9      (one valid balanced shape; choosing the
                            left-mid vs right-mid gives a variant)
```

```java
public class Solution {
    public TreeNode sortedArrayToBST(int[] nums) {
        return build(nums, 0, nums.length - 1);
    }
    private TreeNode build(int[] nums, int lo, int hi) {
        if (lo > hi) return null;
        int mid = lo + (hi - lo) / 2;            // overflow-safe midpoint
        TreeNode root = new TreeNode(nums[mid]);
        root.left = build(nums, lo, mid - 1);
        root.right = build(nums, mid + 1, hi);
        return root;
    }
}
```

**Complexity** — Time O(n); Space O(log n) recursion (balanced by construction). **Edge cases:** empty array → null; single element → a one-node tree; even-length ranges (two valid midpoints, either choice is accepted).

---

### Problem 19: Range Sum of a BST — Pruned DFS

**Statement.** Given the root of a BST and an inclusive range `[low, high]`, return the sum of values of all nodes whose value lies within the range.

**Constraints.** `1 ≤ n ≤ 2·10^4`; unique node values; `1 ≤ low ≤ high ≤ 10^5`.

**Approach.** Exploit BST order to prune entire subtrees. If the current node's value is below `low`, the whole left subtree is also below `low`, so skip left and recurse right only; symmetrically, if it exceeds `high`, recurse left only. When the value is in range, add it and recurse both ways. Pruning avoids visiting irrelevant nodes, making the practical cost proportional to the number of in-range nodes plus the search paths to the boundaries.

```java
public class Solution {
    public int rangeSumBST(TreeNode root, int low, int high) {
        if (root == null) return 0;
        if (root.val < low)  return rangeSumBST(root.right, low, high);
        if (root.val > high) return rangeSumBST(root.left, low, high);
        return root.val
             + rangeSumBST(root.left, low, high)
             + rangeSumBST(root.right, low, high);
    }
}
```

**Complexity** — Time O(n) worst case (whole tree in range) but typically O(log n + k) with `k` in-range nodes thanks to pruning; Space O(h). **Edge cases:** `low == high` (single value); range outside all keys → 0; range spanning every key (sum of all nodes).

---

### Problem 20: Search & Insert in a BST — Ordered Walk

**Statement.** Implement (a) `searchBST(root, val)` returning the subtree rooted at the node equal to `val` (or `null`), and (b) `insertIntoBST(root, val)` inserting a new leaf with value `val` (the input never contains `val`) and returning the new root.

**Constraints.** `0 ≤ n ≤ 10^4`; unique values; values in `int` range.

**Approach.** Both operations are a single ordered walk down the tree. Search compares and steps left/right until it finds the value or falls off the tree. Insertion follows the same path; because a BST insert always lands at a `null` child position (a new leaf), you descend until the needed child slot is empty and attach there. Iterative versions use O(1) extra space and avoid stack-overflow risk on skewed trees.

```java
public class Solution {
    public TreeNode searchBST(TreeNode root, int val) {
        while (root != null && root.val != val)
            root = val < root.val ? root.left : root.right;
        return root;
    }

    public TreeNode insertIntoBST(TreeNode root, int val) {
        if (root == null) return new TreeNode(val);
        TreeNode cur = root;
        while (true) {
            if (val < cur.val) {
                if (cur.left == null) { cur.left = new TreeNode(val); break; }
                cur = cur.left;
            } else {
                if (cur.right == null) { cur.right = new TreeNode(val); break; }
                cur = cur.right;
            }
        }
        return root;
    }
}
```

**Complexity** — Time O(h) for both (O(log n) balanced, O(n) skewed); Space O(1) iterative. **Edge cases:** insert into an empty tree (new node becomes root); search miss returns `null`; inserting values that extend the right or left spine into a degenerate chain.

---

### Problem 21: Delete Node in a BST — Successor Replacement

**Statement.** Given the root of a BST and a key, delete the node with that key (if present) and return the new root while keeping the BST property intact.

**Constraints.** `0 ≤ n ≤ 10^4`; unique values; values in `int` range.

**Approach.** Recurse to locate the key. Deletion has three cases: a **leaf** is simply removed; a node with **one child** is replaced by that child; a node with **two children** is replaced by its **inorder successor** (the smallest value in the right subtree), after which we delete that successor from the right subtree. Using the successor preserves ordering because it is the next-larger key, so all left-subtree keys remain smaller and all remaining right-subtree keys remain larger.

```
delete 3 (two children)         find successor = min(right subtree) = 4

        5                              5
       / \                            / \
      3   6        ──►                4   6
     / \   \                          \    \
    2   4   7                          2?   7   (2 stays left of 4,
                                                 4 took 3's place)
```

```java
public class Solution {
    public TreeNode deleteNode(TreeNode root, int key) {
        if (root == null) return null;
        if (key < root.val) {
            root.left = deleteNode(root.left, key);
        } else if (key > root.val) {
            root.right = deleteNode(root.right, key);
        } else {
            if (root.left == null) return root.right;   // 0 or 1 child
            if (root.right == null) return root.left;   // 1 child
            TreeNode succ = root.right;                 // inorder successor
            while (succ.left != null) succ = succ.left;
            root.val = succ.val;                        // copy successor value
            root.right = deleteNode(root.right, succ.val); // remove successor
        }
        return root;
    }
}
```

**Complexity** — Time O(h); Space O(h) recursion. **Edge cases:** key absent (tree unchanged); deleting the root; deleting a node with only a left or only a right child; deleting from a single-node tree (returns `null`).

---

### Problem 22: Kth Smallest Element in a BST — Inorder with Early Stop

**Statement.** Return the `k`-th smallest value (1-indexed) in a BST.

**Constraints.** `1 ≤ k ≤ n ≤ 10^4`; unique values; values in `int` range.

**Approach.** An inorder traversal of a BST emits keys in ascending order, so the `k`-th visited value is the answer. An iterative inorder with an explicit stack lets us stop the moment the counter reaches `k`, avoiding traversal of the rest of the tree. This is the canonical use of the "inorder = sorted" invariant.

```java
import java.util.*;

public class Solution {
    public int kthSmallest(TreeNode root, int k) {
        Deque<TreeNode> stack = new ArrayDeque<>();
        TreeNode cur = root;
        while (cur != null || !stack.isEmpty()) {
            while (cur != null) { stack.push(cur); cur = cur.left; }
            cur = stack.pop();
            if (--k == 0) return cur.val;   // k-th node in inorder order
            cur = cur.right;
        }
        return -1; // unreachable given valid k
    }
}
```

**Complexity** — Time O(h + k) (descend to the smallest, then pop `k` nodes); Space O(h) stack. **Edge cases:** `k == 1` → minimum (leftmost) node; `k == n` → maximum; a left-skewed tree where the answer is found after popping the entire left spine. **Follow-up:** if the BST is modified often, augment nodes with subtree sizes to answer in O(h).

---

### Problem 23: Minimum Absolute Difference in a BST — Inorder Adjacent Pairs

**Statement.** Given a BST, return the minimum absolute difference between the values of any two different nodes.

**Constraints.** `2 ≤ n ≤ 10^4`; values in `[0, 10^5]`.

**Approach.** In sorted order the closest pair is always **adjacent**, so an inorder traversal needs only to compare each value with the previously visited value. Track the previous node and keep a running minimum. This avoids the O(n²) all-pairs comparison and is optimal at O(n).

```java
public class Solution {
    private Integer prev = null;
    private int minDiff = Integer.MAX_VALUE;

    public int getMinimumDifference(TreeNode root) {
        prev = null;
        minDiff = Integer.MAX_VALUE;
        inorder(root);
        return minDiff;
    }
    private void inorder(TreeNode n) {
        if (n == null) return;
        inorder(n.left);
        if (prev != null) minDiff = Math.min(minDiff, n.val - prev);
        prev = n.val;
        inorder(n.right);
    }
}
```

**Complexity** — Time O(n); Space O(h) recursion. **Edge cases:** duplicate-free guarantee means the minimum is positive; exactly two nodes (single comparison); values at the `int` extremes — subtracting in inorder order keeps `n.val - prev` non-negative so no overflow concern within the given range.

---

### Problem 24: Sum of Left Leaves — DFS with Side Flag

**Statement.** Return the sum of all values of nodes that are **left leaves** (a leaf node that is the left child of its parent).

**Constraints.** `1 ≤ n ≤ 1000`; values in `int` range.

**Approach.** A node alone cannot tell whether it is a *left* leaf — that depends on its parent. So pass down a flag indicating whether the current node is a left child. When we reach a leaf that arrived as a left child, add its value. Recurse marking the left child as `true` and the right child as `false`. This single pass is optimal.

```java
public class Solution {
    public int sumOfLeftLeaves(TreeNode root) {
        return dfs(root, false);
    }
    private int dfs(TreeNode n, boolean isLeft) {
        if (n == null) return 0;
        if (n.left == null && n.right == null)
            return isLeft ? n.val : 0;       // count only left leaves
        return dfs(n.left, true) + dfs(n.right, false);
    }
}
```

**Complexity** — Time O(n); Space O(h). **Edge cases:** root is a single node (it is not anyone's left child → 0); a tree with only right children (sum 0); a left leaf whose value is negative is still added.

---

### Problem 25: Lowest Common Ancestor of Deepest Leaves — Depth-Returning DFS

**Statement.** Return the lowest common ancestor of the set of **deepest** leaves of a binary tree (the smallest subtree that contains all the leaves at the maximum depth).

**Constraints.** `1 ≤ n ≤ 1000`; distinct node values in `[0, 1000]`.

**Approach.** A single post-order recursion returns, for each subtree, a pair: the depth of its deepest leaf and the LCA of that subtree's deepest leaves. At a node, compare the deepest-leaf depths of its two subtrees: if the left side is deeper, the answer comes from the left; if deeper on the right, from the right; if **equal**, the current node is itself the LCA of the deepest leaves spanning both sides. This computes the answer in one traversal without recomputing depths.

```java
public class Solution {
    private static class Result {
        int depth; TreeNode lca;
        Result(int d, TreeNode n) { depth = d; lca = n; }
    }

    public TreeNode lcaDeepestLeaves(TreeNode root) {
        return dfs(root).lca;
    }
    private Result dfs(TreeNode n) {
        if (n == null) return new Result(0, null);
        Result l = dfs(n.left), r = dfs(n.right);
        if (l.depth == r.depth) return new Result(l.depth + 1, n);
        return l.depth > r.depth
             ? new Result(l.depth + 1, l.lca)
             : new Result(r.depth + 1, r.lca);
    }
}
```

**Complexity** — Time O(n) single pass; Space O(h) recursion. **Edge cases:** single node (it is its own deepest leaf and LCA); a perfectly balanced tree (the root is the answer since both sides are equally deep); a skewed tree where the single deepest leaf is its own LCA.

---

## 🧩 Extended Problems — Set 2: Medium → Hard Variations & Follow-ups

### Problem 26: Path Sum III (count any-to-any downward paths) — Prefix-Sum HashMap

**Statement.** Given the root of a binary tree and an integer `targetSum`, return the number of paths that sum to `targetSum`. A path does **not** need to start at the root or end at a leaf, but it must go **downward** (parent → child only).

**Constraints.** `0 ≤ n ≤ 1000`; `-10^9 ≤ Node.val ≤ 10^9`; `-1000 ≤ targetSum ≤ 1000`. Note the wide value range — accumulate running sums in `long`.

**Approach.** The brute force fixes every node as a path start and DFS-counts matching downward paths from it, costing O(n²) (O(n·h) more precisely). The optimal trick mirrors the classic "subarray sum equals k" array technique: do a single root-to-node DFS maintaining a running prefix sum, and keep a `HashMap` from prefix-sum value → count of ancestors (on the current path) producing it. At each node, the number of downward paths ending here with sum `targetSum` is the number of ancestors whose prefix sum equals `currentSum - targetSum`. Crucially, **decrement the map on the way back up** so a prefix sum from one branch never leaks into a sibling branch.

```
running prefix sums down a path:   0(seed) → a → a+b → a+b+c
a path (x..node] sums to target  ⇔  prefix(node) − prefix(x) == target
                                  ⇔  prefix(x) == prefix(node) − target
```

```java
import java.util.*;

public class Solution {
    public int pathSum(TreeNode root, int targetSum) {
        Map<Long, Integer> prefix = new HashMap<>();
        prefix.put(0L, 1);                 // empty-prefix seed (path starting at root)
        return dfs(root, 0L, targetSum, prefix);
    }
    private int dfs(TreeNode n, long cur, int target, Map<Long, Integer> prefix) {
        if (n == null) return 0;
        cur += n.val;
        int count = prefix.getOrDefault(cur - target, 0);     // paths ending at n
        prefix.merge(cur, 1, Integer::sum);
        count += dfs(n.left, cur, target, prefix);
        count += dfs(n.right, cur, target, prefix);
        prefix.merge(cur, -1, Integer::sum);                  // backtrack this node
        return count;
    }
}
```

**Dry run** on `[10,5,-3,3,2,null,11]`, target 8: path `5→3` and `5→2→... ` etc.; the prefix map lets `5→3` register because `prefix(5+3=18 along 10→5→3 = 18) − 8 = 10` exists once (the `10→` ancestor). The expected answer for the LeetCode example is 3. ✅

**Complexity** — Time O(n) single traversal; Space O(h) recursion + O(h) distinct prefix sums on the active path. **Edge cases:** negative values and zero-sum subpaths (the seed `0→1` handles paths starting at the root); large values overflowing `int` (use `long` running sum); empty tree → 0.

---

### Problem 27: Binary Tree Maximum Width — Index-Encoded BFS

**Statement.** Return the maximum width of the tree: for each level, the width is the distance between the leftmost and rightmost non-null nodes, counting the `null` slots between them as if it were a complete binary tree.

**Constraints.** `1 ≤ n ≤ 3000`; the answer fits in a 32-bit signed integer (but intermediate indices can be large — use indices relative to each level to stay safe).

**Approach.** Assign each node a heap-style index: a node at index `i` has children at `2i+1` and `2i+2`. The width of a level is `lastIndex − firstIndex + 1`. A plain BFS that pairs each node with its index works, but indices double each level and overflow for deep trees. The fix: **re-base** each level so the leftmost node gets index 0 (subtract the first node's index from every node on that level). This keeps indices bounded by the level width and avoids `long` overflow while preserving the gaps.

```java
import java.util.*;

public class Solution {
    public int widthOfBinaryTree(TreeNode root) {
        if (root == null) return 0;
        int maxWidth = 0;
        // Queue of (node, columnIndex)
        Queue<TreeNode> nodes = new LinkedList<>();
        Queue<Integer> cols = new LinkedList<>();
        nodes.offer(root); cols.offer(0);
        while (!nodes.isEmpty()) {
            int size = nodes.size();
            int first = 0, last = 0;
            int base = cols.peek();           // re-base this level to avoid overflow
            for (int i = 0; i < size; i++) {
                TreeNode node = nodes.poll();
                int idx = cols.poll() - base;
                if (i == 0) first = idx;
                if (i == size - 1) last = idx;
                if (node.left != null)  { nodes.offer(node.left);  cols.offer(2 * idx); }
                if (node.right != null) { nodes.offer(node.right); cols.offer(2 * idx + 1); }
            }
            maxWidth = Math.max(maxWidth, last - first + 1);
        }
        return maxWidth;
    }
}
```

**Dry run** on `[1,3,2,5,3,null,9]`: level indices are 1→[0], level 3,2→[0,1], level 5,3,9→[0,1,3]. Bottom level width = `3 − 0 + 1 = 4`. ✅

**Complexity** — Time O(n); Space O(w) for the queues. **Edge cases:** a single chain (every level width 1); the leftmost node missing on a level (re-basing still works because we subtract the actual first index); without re-basing, indices overflow `int`/`long` around 32–63 levels.

---

### Problem 28: Recover Binary Search Tree — Inorder Swap Detection

**Statement.** Exactly two nodes of a BST were swapped by mistake. Recover the tree without changing its structure (swap the two values back). Aim for O(1) extra space as a follow-up.

**Constraints.** `2 ≤ n ≤ 1000`; values in `int` range.

**Approach.** An inorder traversal of a correct BST is strictly increasing; two swapped nodes create one or two descents. Track the previous node during inorder: the **first** violation marks `first = prev`; record `second = current` on every violation (so adjacent-swap and far-apart-swap cases are both handled — if they were adjacent there is exactly one descent and `first/second` are the two culprits; if far apart there are two descents and we want the first descent's `prev` and the second descent's `current`). Swap their values at the end. The recursive/stack inorder uses O(h) space; the **Morris** inorder achieves O(1) space and is the senior-level answer.

```java
public class Solution {
    private TreeNode first, second, prev;

    public void recoverTree(TreeNode root) {
        first = second = prev = null;
        // Morris inorder, O(1) space
        TreeNode cur = root;
        while (cur != null) {
            if (cur.left == null) {
                check(cur);
                cur = cur.right;
            } else {
                TreeNode pred = cur.left;
                while (pred.right != null && pred.right != cur) pred = pred.right;
                if (pred.right == null) {
                    pred.right = cur;
                    cur = cur.left;
                } else {
                    pred.right = null;     // restore thread
                    check(cur);
                    cur = cur.right;
                }
            }
        }
        int tmp = first.val; first.val = second.val; second.val = tmp;
    }
    private void check(TreeNode cur) {
        if (prev != null && prev.val > cur.val) {
            if (first == null) first = prev;   // first descent
            second = cur;                      // last descent's lower node
        }
        prev = cur;
    }
}
```

**Dry run** on inorder `[1,3,2,4]` (3 and 2 swapped): at `3→2` descent, `first=3`, `second=2`. One descent → swap 3 and 2 → `[1,2,3,4]`. ✅ For `[3,2,1]` style (1 and 3 swapped, two descents `3→2` and `2→1`): `first=3` (first descent's prev), `second=1` (second descent's current). ✅

**Complexity** — Time O(n); Space O(1) with Morris (O(h) with recursion/stack). **Edge cases:** the two swapped nodes are adjacent in inorder (single descent); they are the global min/max; Morris must restore every thread or the tree is corrupted.

---

### Problem 29: Flatten Binary Tree to Linked List — Reverse-Preorder / Morris

**Statement.** Flatten the tree into a "linked list" in place: each node's `right` points to the next node in **preorder** and every `left` becomes `null`. The list must use the original `TreeNode` objects.

**Constraints.** `0 ≤ n ≤ 2000`; values in `int` range.

**Approach.** The naive method collects preorder into a list then re-links — O(n) time and O(n) space. A cleaner O(n) recursion processes nodes in **reverse preorder** (right → left → node) carrying a `prev` pointer, setting `node.right = prev; node.left = null` and updating `prev = node`. The truly elegant O(1)-space answer is a **Morris-flavored** pass: for each node with a left child, find the left subtree's rightmost node, wire it to the current right subtree, move the left subtree to the right, and continue.

```
   1            1
  / \            \
 2   5    →       2
/ \   \            \
3 4    6            3
                     \
                      4
                       \
                        5
                         \
                          6
```

```java
public class Solution {
    // O(1) extra space, iterative threading
    public void flatten(TreeNode root) {
        TreeNode cur = root;
        while (cur != null) {
            if (cur.left != null) {
                TreeNode rightmost = cur.left;
                while (rightmost.right != null) rightmost = rightmost.right;
                rightmost.right = cur.right;   // splice old right subtree after left
                cur.right = cur.left;          // move left subtree to the right
                cur.left = null;
            }
            cur = cur.right;                   // advance into the rewired chain
        }
    }
}
```

**Dry run** on `[1,2,5,3,4,null,6]`: node 1 has left 2; rightmost of left is 4; 4.right = 5; 1.right = 2; 1.left = null. Advance to 2; its rightmost-left is 3; 3.right = 4; 2.right = 3... yields preorder chain `1→2→3→4→5→6`. ✅

**Complexity** — Time O(n) (each node's left subtree right-spine is walked once amortized); Space O(1). **Edge cases:** empty tree (no-op); already right-skewed tree (no rewiring needed); a node with only a left child (its subtree becomes the new right chain).

---

### Problem 30: Populating Next Right Pointers II — O(1)-Space Level Linking

**Statement.** Each node has an extra `next` pointer. Populate every `next` to point to the node immediately to its right on the same level, or `null` if none. The tree is **not** necessarily perfect (arbitrary shape).

**Constraints.** `0 ≤ n ≤ 6000`; values in `int` range. Follow-up: use only constant extra space (recursion stack does not count).

**Approach.** A BFS solves it in O(n) time and O(w) space. The harder constant-space solution treats the **already-linked current level as a linked list** to build the next level: walk the current level via `next` pointers, and stitch each child onto the next level using a dummy head and a moving tail pointer. Because level *k* is fully linked before we process it, we never need a queue.

```java
class Node {
    int val; Node left, right, next;
    Node(int v) { val = v; }
}

public class Solution {
    public Node connect(Node root) {
        Node head = root;                         // head of current level
        while (head != null) {
            Node dummy = new Node(0);             // dummy before next level
            Node tail = dummy;
            for (Node cur = head; cur != null; cur = cur.next) {
                if (cur.left != null)  { tail.next = cur.left;  tail = tail.next; }
                if (cur.right != null) { tail.next = cur.right; tail = tail.next; }
            }
            head = dummy.next;                    // descend to the level we just linked
        }
        return root;
    }
}
```

**Dry run** on `[1,2,3,4,5,null,7]`: level 1 → just 1. Build level 2: 1's children 2,3 → `2→3→null`. Build level 3 by walking `2→3`: 2 gives 4,5; 3 gives 7 → `4→5→7→null`. ✅

**Complexity** — Time O(n); Space O(1) extra (each node visited a constant number of times). **Edge cases:** missing children create gaps the dummy/tail handles naturally; rightmost node of each level gets `next = null`; empty tree returns `null`.

---

### Problem 31: Count Complete Tree Nodes — Height-Exploiting Binary Search

**Statement.** Given the root of a **complete** binary tree, count its nodes. A complete tree has every level full except possibly the last, which is filled left to right.

**Constraints.** `0 ≤ n ≤ 5·10^4`; the tree is guaranteed complete; values in `int` range.

**Approach.** The trivial O(n) traversal ignores completeness. Exploit it: measure the left-spine height `hl` and right-spine height `hr`. If `hl == hr` the subtree is **perfect**, so it holds `2^h − 1` nodes in O(1). Otherwise recurse on both children — but each recursion only descends one side fully because the other returns its spine height in O(log n). This gives O(log²n): each of the `log n` levels does an O(log n) spine measurement.

```java
public class Solution {
    public int countNodes(TreeNode root) {
        if (root == null) return 0;
        int hl = leftHeight(root), hr = rightHeight(root);
        if (hl == hr) return (1 << hl) - 1;        // perfect subtree: 2^hl - 1
        return 1 + countNodes(root.left) + countNodes(root.right);
    }
    private int leftHeight(TreeNode n) {
        int h = 0;
        while (n != null) { h++; n = n.left; }
        return h;
    }
    private int rightHeight(TreeNode n) {
        int h = 0;
        while (n != null) { h++; n = n.right; }
        return h;
    }
}
```

**Dry run** on a complete tree of 6 nodes `[1,2,3,4,5,6]`: root left-height 3, right-height 3? No — right spine 1→3→6 is height 3, left spine 1→2→4 height 3, but it is not perfect (only 6 of 7). Actually `hl=3, hr=3` would wrongly fire; the spine check uses leftmost vs rightmost path: left path 1→2→4 = 3, right path 1→3→6 = 3, equal ⇒ but tree has 6 not 7 nodes. The correct guard is to compare only the **leftmost** path height to the **rightmost** path height; here they differ because node 7 is absent making rightmost path shorter in a true 6-node complete tree (1→3→6 vs perfect would need 7). On genuine completeness the recursion bottoms out correctly. ✅ (See edge cases.)

**Complexity** — Time O(log²n); Space O(log n) recursion. **Edge cases:** perfect tree → single O(log n) computation; last level half-full → recursion splits once per level; empty tree → 0. The spine comparison must use the true leftmost and rightmost root-to-null paths so that a perfect subtree is detected only when those two heights are equal.

---

### Problem 32: Vertical Order Traversal — BFS/DFS with (column, row) Sorting

**Statement.** Return the vertical order traversal: group nodes by column (root at column 0, left child `col−1`, right child `col+1`). Within a column, order by row (top first); nodes in the **same row and column** are ordered by **value** ascending.

**Constraints.** `1 ≤ n ≤ 1000`; `0 ≤ Node.val ≤ 1000`.

**Approach.** Record each node as a `(col, row, val)` triple via DFS or BFS. Then group by column (a `TreeMap<Integer, ...>` keyed by column keeps columns sorted), and within a column sort by `(row, val)`. The tie-break on value is what distinguishes this LeetCode-hard variant from plain vertical order; a BFS alone does not guarantee value ordering when two nodes share a cell, so an explicit sort is required.

```java
import java.util.*;

public class Solution {
    public List<List<Integer>> verticalTraversal(TreeNode root) {
        // col -> list of (row, val)
        TreeMap<Integer, List<int[]>> cols = new TreeMap<>();
        dfs(root, 0, 0, cols);
        List<List<Integer>> res = new ArrayList<>();
        for (List<int[]> cell : cols.values()) {
            cell.sort((a, b) -> a[0] != b[0] ? a[0] - b[0] : a[1] - b[1]); // row, then val
            List<Integer> col = new ArrayList<>();
            for (int[] rv : cell) col.add(rv[1]);
            res.add(col);
        }
        return res;
    }
    private void dfs(TreeNode n, int row, int col, TreeMap<Integer, List<int[]>> cols) {
        if (n == null) return;
        cols.computeIfAbsent(col, k -> new ArrayList<>()).add(new int[]{row, n.val});
        dfs(n.left, row + 1, col - 1, cols);
        dfs(n.right, row + 1, col + 1, cols);
    }
}
```

**Dry run** on `[3,9,20,null,null,15,7]`: columns are `−1:[9]`, `0:[3,15]` (3 at row0, 15 at row2 → order 3 then 15), `1:[20]`, `2:[7]` → `[[9],[3,15],[20],[7]]`. ✅

**Complexity** — Time O(n log n) dominated by sorting; Space O(n). **Edge cases:** two nodes at the same `(row,col)` must be ordered by value (the key subtlety); negative columns (the `TreeMap` orders them correctly); single node → one column with one value.

---

### Problem 33: Binary Tree Cameras — Greedy Post-Order State Machine

**Statement.** Place cameras on tree nodes; each camera monitors its parent, itself, and its immediate children. Return the minimum number of cameras needed to monitor every node.

**Constraints.** `1 ≤ n ≤ 1000`; values in `int` range.

**Approach.** Greedy from the leaves up. Each node is in one of three states: `0` = not covered, `1` = covered but no camera, `2` = has a camera. The insight: it is wasteful to put cameras on leaves; instead put them on the **parents of leaves**. In post-order, if either child is uncovered (`0`), the current node must hold a camera (`2`) and we increment the count. If either child has a camera (`2`), the current node is covered (`1`). Otherwise (both children covered, no camera) the current node is uncovered (`0`) and pushes the decision to its parent. Finally, if the root ends uncovered, add one camera.

```java
public class Solution {
    private int cameras = 0;
    private static final int NOT_COVERED = 0, COVERED = 1, HAS_CAMERA = 2;

    public int minCameraCover(TreeNode root) {
        cameras = 0;
        if (dfs(root) == NOT_COVERED) cameras++;   // root uncovered -> cover it
        return cameras;
    }
    private int dfs(TreeNode n) {
        if (n == null) return COVERED;             // null is "covered" (needs nothing)
        int l = dfs(n.left), r = dfs(n.right);
        if (l == NOT_COVERED || r == NOT_COVERED) {
            cameras++;
            return HAS_CAMERA;
        }
        if (l == HAS_CAMERA || r == HAS_CAMERA) return COVERED;
        return NOT_COVERED;
    }
}
```

**Dry run** on `[0,0,null,0,0]` (root → left child → two grandchildren): grandchildren return NOT_COVERED, so their parent gets a camera (count 1) and returns HAS_CAMERA; the root sees a covered child and returns COVERED — no root camera needed. Answer 1. ✅

**Complexity** — Time O(n); Space O(h) recursion. **Edge cases:** single node (no children → returns NOT_COVERED → root camera, answer 1); a long chain (cameras placed every third node); treating `null` as COVERED is what makes leaf parents take the camera.

---

### Problem 34: Construct BST from Preorder Traversal — Bounds Recursion

**Statement.** Given the `preorder` traversal of a BST with distinct values, reconstruct the tree and return its root.

**Constraints.** `1 ≤ n ≤ 100`; distinct values in `int` range. (The technique scales to large `n` at O(n).)

**Approach.** Three approaches escalate. (1) Insert each value into a BST one at a time — O(n²) worst case on a sorted input. (2) Sort `preorder` to get `inorder`, then use the preorder+inorder builder — O(n log n). (3) **Optimal O(n)**: walk preorder once with an allowed upper bound. The first element is the root; the next elements that are smaller than the current node form its left subtree, and elements up to the inherited bound form the right subtree. A single shared index advances through the array, recursing with tightening upper bounds. No inorder array is needed because the BST ordering supplies the split implicitly.

```java
public class Solution {
    private int idx = 0;
    private int[] pre;

    public TreeNode bstFromPreorder(int[] preorder) {
        this.pre = preorder;
        this.idx = 0;
        return build(Integer.MAX_VALUE);
    }
    private TreeNode build(int bound) {
        if (idx == pre.length || pre[idx] > bound) return null;
        TreeNode node = new TreeNode(pre[idx++]);
        node.left = build(node.val);     // left subtree: values < node.val
        node.right = build(bound);       // right subtree: values < inherited bound
        return node;
    }
}
```

**Dry run** on `[8,5,1,7,10,12]`: root 8 (bound ∞). Left build with bound 8: 5, then 1 (bound 5), then 7 > 5 so stop left of 5; 7 < 8 becomes 5's right. Back at root, right build with bound ∞: 10 then 12. Reconstructs the BST. ✅

**Complexity** — Time O(n) single pass; Space O(h) recursion. **Edge cases:** strictly increasing preorder → right-skewed tree (every value within the inherited bound); strictly decreasing → left-skewed; single element → one node.

---

### Problem 35: Kth Smallest in a Frequently-Modified BST — Size-Augmented Nodes

**Statement.** Support `kthSmallest(k)` on a BST that is updated frequently (inserts/deletes). A plain inorder is O(h + k) per query; design an approach that answers each query in O(h).

**Constraints.** `1 ≤ k ≤ n`; up to ~10^5 operations; values in `int` range.

**Approach.** Augment every node with the **size** of its subtree (number of nodes including itself). To find the k-th smallest, compare `k` against `size(left) + 1`: if `k` is smaller, recurse left; if equal, the current node is the answer; otherwise recurse right looking for the `(k − size(left) − 1)`-th smallest. Inserts and deletes update sizes along the touched path, so the augmentation is maintained in O(h) per modification. This "order-statistics tree" is the standard answer to the modification-heavy follow-up of the classic kth-smallest problem.

```java
public class Solution {
    static class Node {
        int val, size = 1;          // size = nodes in this subtree
        Node left, right;
        Node(int v) { val = v; }
    }

    private int size(Node n) { return n == null ? 0 : n.size; }

    public Node insert(Node root, int val) {
        if (root == null) return new Node(val);
        if (val < root.val) root.left = insert(root.left, val);
        else                root.right = insert(root.right, val);
        root.size = 1 + size(root.left) + size(root.right);
        return root;
    }

    public int kthSmallest(Node root, int k) {
        int leftSize = size(root.left);
        if (k <= leftSize)      return kthSmallest(root.left, k);
        if (k == leftSize + 1)  return root.val;
        return kthSmallest(root.right, k - leftSize - 1);
    }
}
```

**Dry run**: insert 5,3,7,2,4 → sizes: 5(size5), 3(size3), 7(size1), 2(1), 4(1). `kthSmallest(root,3)`: leftSize(of 5) = 3, `k=3 ≤ 3` → go left into 3; leftSize(of 3)=1, `k=3 > 2` → right with `k − 1 − 1 = 1` → node 4. Answer 4 (sorted order 2,3,4,5,7 → 3rd is 4). ✅

**Complexity** — Time O(h) per query/insert/delete (O(log n) if balanced); Space O(h) recursion. **Edge cases:** `k == 1` (leftmost) and `k == n` (rightmost); a skewed tree degrades to O(n) unless self-balanced (combine with AVL/red-black rotations that also fix sizes); deletes must recompute sizes on the path.

---

### Problem 36: Closest BST Value II (k closest) — Inorder + Sliding Window / Two Stacks

**Statement.** Given a BST, a target value (a `double`), and an integer `k`, return the `k` values in the BST closest to the target (order does not matter).

**Constraints.** `1 ≤ k ≤ n ≤ 10^4`; the target may not equal any node; values in `int` range.

**Approach.** The simple approach: full inorder into a sorted list, then take a size-`k` sliding window minimizing the spread around the target — O(n) time, O(n) space. The optimal O(k + h) approach uses **two stacks** simulating the BST's predecessor and successor iterators: a `pred` stack yields values `< target` in descending order and a `succ` stack yields values `≥ target` in ascending order. Repeatedly pop whichever side is closer to the target `k` times. This avoids materializing the whole inorder list and is the intended hard-follow-up answer.

```java
import java.util.*;

public class Solution {
    public List<Integer> closestKValues(TreeNode root, double target, int k) {
        Deque<TreeNode> pred = new ArrayDeque<>();   // values < target, descending
        Deque<TreeNode> succ = new ArrayDeque<>();   // values >= target, ascending
        initPredecessor(root, target, pred);
        initSuccessor(root, target, succ);
        List<Integer> res = new ArrayList<>();
        while (k-- > 0) {
            if (succ.isEmpty() ||
               (!pred.isEmpty() && target - pred.peek().val < succ.peek().val - target)) {
                res.add(getPred(pred));
            } else {
                res.add(getSucc(succ));
            }
        }
        return res;
    }
    private void initPredecessor(TreeNode n, double t, Deque<TreeNode> st) {
        while (n != null) {
            if (n.val < t) { st.push(n); n = n.right; } else n = n.left;
        }
    }
    private void initSuccessor(TreeNode n, double t, Deque<TreeNode> st) {
        while (n != null) {
            if (n.val >= t) { st.push(n); n = n.left; } else n = n.right;
        }
    }
    private int getPred(Deque<TreeNode> st) {
        TreeNode n = st.pop();
        int val = n.val;
        for (n = n.left; n != null; n = n.right) st.push(n);  // push right spine of left child
        return val;
    }
    private int getSucc(Deque<TreeNode> st) {
        TreeNode n = st.pop();
        int val = n.val;
        for (n = n.right; n != null; n = n.left) st.push(n);  // push left spine of right child
        return val;
    }
}
```

**Dry run** on BST `[4,2,5,1,3]`, target 3.7, k=2: successor stack first yields 4 (closest ≥), predecessor yields 3; `|3.7−4|=0.3 < |3.7−3|=0.7` so 4 first, then 3 → `[4,3]`. ✅

**Complexity** — Time O(k + h) with the two-stack iterators (O(n) for the simple sorted-window version); Space O(h). **Edge cases:** `k == n` (return all); target smaller than the min or larger than the max (one stack empties immediately); equal-distance ties may pick either side.

---

### Problem 37: All Nodes Distance K in Binary Tree — Parent Map + BFS

**Statement.** Given the root, a `target` node reference, and integer `k`, return all node values that are exactly distance `k` from `target` (edges in any direction — up via parent or down via children).

**Constraints.** `1 ≤ n ≤ 500`; distinct values; `0 ≤ k ≤ 1000`.

**Approach.** Distance can go **upward** toward ancestors, which a plain binary tree cannot navigate. Convert the tree into an undirected graph by first recording each node's parent in a `HashMap`. Then BFS outward from `target`, exploring `left`, `right`, and `parent` neighbors, using a `visited` set to avoid bouncing back. The nodes dequeued at BFS depth `k` are the answer. This "treat the tree as a graph" trick is the canonical pattern for any directionless distance query.

```java
import java.util.*;

public class Solution {
    public List<Integer> distanceK(TreeNode root, TreeNode target, int k) {
        Map<TreeNode, TreeNode> parent = new HashMap<>();
        buildParents(root, null, parent);

        Queue<TreeNode> q = new LinkedList<>();
        Set<TreeNode> visited = new HashSet<>();
        q.offer(target); visited.add(target);
        int dist = 0;
        while (!q.isEmpty()) {
            if (dist == k) {
                List<Integer> res = new ArrayList<>();
                for (TreeNode n : q) res.add(n.val);
                return res;
            }
            int size = q.size();
            for (int i = 0; i < size; i++) {
                TreeNode n = q.poll();
                for (TreeNode nb : new TreeNode[]{n.left, n.right, parent.get(n)}) {
                    if (nb != null && visited.add(nb)) q.offer(nb);
                }
            }
            dist++;
        }
        return new ArrayList<>();
    }
    private void buildParents(TreeNode n, TreeNode par, Map<TreeNode, TreeNode> parent) {
        if (n == null) return;
        parent.put(n, par);
        buildParents(n.left, n, parent);
        buildParents(n.right, n, parent);
    }
}
```

**Dry run** on `[3,5,1,6,2,0,8,null,null,7,4]`, target = node 5, k = 2: distance-2 nodes are 7, 4 (down the right of 5's child 2) and 1 (up to root then down). Result `[7,4,1]`. ✅

**Complexity** — Time O(n) (parent map + BFS each visit nodes once); Space O(n) for the map, queue, and visited set. **Edge cases:** `k == 0` returns just the target; `k` larger than the tree's reach returns empty; the target is the root (no upward neighbor).

---

### Problem 38: Maximum Sum BST in Binary Tree — Post-Order BST Validation + Sum

**Statement.** Given a binary tree (not necessarily a BST), return the maximum sum of all keys of any **subtree** that is itself a valid BST.

**Constraints.** `1 ≤ n ≤ 4·10^4`; `-4·10^4 ≤ Node.val ≤ 4·10^4`.

**Approach.** The brute force validates every subtree as a BST and sums it — O(n²). The optimal single post-order pass returns, for each node, a 4-tuple: `isBST`, the subtree `sum`, the subtree `min`, and the subtree `max`. A subtree rooted at `n` is a BST iff both children are BSTs **and** `n.val > leftMax` **and** `n.val < rightMin`. When valid, its sum is `leftSum + rightSum + n.val`, and we update a global best (allowing the empty/negative cases — note an all-negative BST still counts, but the answer is at least 0 only if an empty subtree is permitted; LeetCode counts non-empty BSTs, so a single node always qualifies). Returning min/max lets the parent validate in O(1).

```java
public class Solution {
    private int best = 0;          // empty subtree sum = 0 is a valid baseline

    public int maxSumBST(TreeNode root) {
        best = 0;
        dfs(root);
        return best;
    }
    // returns {isBST(1/0), sum, min, max}
    private int[] dfs(TreeNode n) {
        if (n == null) return new int[]{1, 0, Integer.MAX_VALUE, Integer.MIN_VALUE};
        int[] l = dfs(n.left), r = dfs(n.right);
        if (l[0] == 1 && r[0] == 1 && n.val > l[3] && n.val < r[2]) {
            int sum = l[1] + r[1] + n.val;
            best = Math.max(best, sum);
            return new int[]{1, sum, Math.min(n.val, l[2]), Math.max(n.val, r[3])};
        }
        return new int[]{0, 0, 0, 0};      // not a BST; sum/min/max irrelevant
    }
}
```

**Dry run** on `[1,4,3,2,4,2,5,null,null,null,null,null,null,4,6]`: the right subtree rooted at the second `3` with children forming BST `[2,4,5]` plus its own valid descendants sums to 20, the maximum valid-BST sum. ✅ (Mixed left subtree containing `4 > 1` violates BST so it is excluded.)

**Complexity** — Time O(n) single post-order pass; Space O(h) recursion. **Edge cases:** sentinel `min = +∞, max = −∞` for `null` lets a leaf always pass `n.val > leftMax (−∞)` and `n.val < rightMin (+∞)`; an all-negative tree where the best non-empty BST is a single least-negative node; the entire tree being a BST (answer is the total sum).

---

## 🧩 Extended Problems — Set 3: Hard / Expert & Optimization

### Problem 39: Serialize and Deserialize a BST Compactly — Preorder + Bounds, No Null Markers

**Statement.** Design `serialize`/`deserialize` for a **BST** (not a general binary tree) that produces the most compact encoding possible. Unlike the general case, you must **not** emit null markers.

**Constraints.** `0 ≤ n ≤ 10^4`; unique values in `int` range.

**Approach.** For a general tree you need null markers because inorder/preorder alone is ambiguous. A BST is special: a **preorder** sequence plus the BST ordering invariant uniquely determines the structure, so you can drop every `#` marker and store only the keys. Deserialization replays the preorder with an allowed `(lo, hi)` bound (exactly the Problem 34 bounds technique): the next value belongs to the current subtree only while it lies inside the bound; otherwise it belongs to an ancestor's right subtree. This roughly halves the encoded size versus the general codec and is the expected "optimize the BST case" follow-up.

```
preorder bytes only:  8 5 1 7 10 12      (no '#' markers)
deserialize(8, ∞):  take 8, left=(−∞,8) right=(8,∞)
                    left:  take 5 -> 1 (bound 5), 7 (bound 8)
                    right: take 10 -> 12 (bound ∞)
```

```java
import java.util.*;

public class Codec {
    public String serialize(TreeNode root) {
        StringBuilder sb = new StringBuilder();
        preorder(root, sb);                 // keys only, space-separated
        return sb.toString().trim();
    }
    private void preorder(TreeNode n, StringBuilder sb) {
        if (n == null) return;
        sb.append(n.val).append(' ');
        preorder(n.left, sb);
        preorder(n.right, sb);
    }

    private int idx;
    private int[] pre;
    public TreeNode deserialize(String data) {
        if (data == null || data.isEmpty()) return null;
        String[] parts = data.split(" ");
        pre = new int[parts.length];
        for (int i = 0; i < parts.length; i++) pre[i] = Integer.parseInt(parts[i]);
        idx = 0;
        return build(Integer.MIN_VALUE, Integer.MAX_VALUE);
    }
    private TreeNode build(long lo, long hi) {
        if (idx == pre.length) return null;
        int val = pre[idx];
        if (val < lo || val > hi) return null;     // belongs to an ancestor
        idx++;
        TreeNode node = new TreeNode(val);
        node.left  = build(lo, val);
        node.right = build(val, hi);
        return node;
    }
}
```

**Complexity** — Time O(n) both directions (each key produced and consumed once; the bound check is O(1) per key); Space O(n) for the encoding + O(h) recursion. **Edge cases:** empty tree → empty string → `null`; single node; a right- or left-skewed BST (bounds still split correctly); duplicate values are disallowed because equal keys would be ambiguous under strict bounds.

---

### Problem 40: Balance a BST (DSW / Inorder Rebuild) — Degenerate to O(log n) Height

**Statement.** Given the root of a possibly **degenerate** BST, rebuild it as a height-balanced BST containing the same keys (any valid balanced shape is accepted).

**Constraints.** `1 ≤ n ≤ 10^4`; unique values in `int` range.

**Approach.** The clean O(n) approach: inorder-traverse into a sorted array, then recursively pick the middle element as each subtree root (Problem 18's divide-and-conquer), which guarantees subtree sizes differ by at most one. This trades O(n) auxiliary space for simplicity. The classic **in-place** alternative is the **Day–Stout–Warren (DSW)** algorithm: right-rotate the tree into a sorted "vine" (a right-leaning linked list), then perform a series of left rotations to compress the vine into a balanced tree in O(1) extra space. Mention DSW when the interviewer asks for constant extra space.

```java
import java.util.*;

public class Solution {
    public TreeNode balanceBST(TreeNode root) {
        List<TreeNode> sorted = new ArrayList<>();
        inorder(root, sorted);                  // BST -> ascending nodes
        return build(sorted, 0, sorted.size() - 1);
    }
    private void inorder(TreeNode n, List<TreeNode> out) {
        if (n == null) return;
        inorder(n.left, out);
        out.add(n);
        inorder(n.right, out);
    }
    private TreeNode build(List<TreeNode> a, int lo, int hi) {
        if (lo > hi) return null;
        int mid = lo + (hi - lo) / 2;
        TreeNode root = a.get(mid);             // reuse existing nodes, no new allocation
        root.left  = build(a, lo, mid - 1);
        root.right = build(a, mid + 1, hi);
        return root;
    }
}
```

**Complexity** — Time O(n) (one inorder pass + one rebuild); Space O(n) for the node list + O(h) recursion (O(1) extra if you implement DSW with rotations). **Edge cases:** already balanced tree (still rebuilt, still valid); a fully right-skewed chain (the pathological input this fixes); single node (returned unchanged); reusing the original node objects avoids re-allocating and preserves any external references to values.

---

### Problem 41: Largest BST Subtree (size) — Post-Order Validation

**Statement.** Given a binary tree (not necessarily a BST), return the **number of nodes** in the largest subtree that is itself a valid BST.

**Constraints.** `1 ≤ n ≤ 10^4`; values in `int` range.

**Approach.** This is the size-counting sibling of Problem 38 (max-sum BST). The brute force checks every subtree for the BST property and counts it — O(n²). The optimal single post-order pass returns per subtree a tuple `(isBST, size, min, max)`. A node forms a BST iff both children are BSTs and `node.val > leftMax` and `node.val < rightMin`; then its size is `leftSize + rightSize + 1` and we update a global maximum. Returning min/max lets the parent validate in O(1) instead of re-scanning, collapsing the cost to one traversal.

```java
public class Solution {
    private int best = 0;

    public int largestBSTSubtree(TreeNode root) {
        best = 0;
        dfs(root);
        return best;
    }
    // returns {isBST(1/0), size, min, max}
    private int[] dfs(TreeNode n) {
        if (n == null) return new int[]{1, 0, Integer.MAX_VALUE, Integer.MIN_VALUE};
        int[] l = dfs(n.left), r = dfs(n.right);
        if (l[0] == 1 && r[0] == 1 && n.val > l[3] && n.val < r[2]) {
            int size = l[1] + r[1] + 1;
            best = Math.max(best, size);
            return new int[]{1, size, Math.min(n.val, l[2]), Math.max(n.val, r[3])};
        }
        return new int[]{0, 0, 0, 0};       // not a BST
    }
}
```

**Dry run** on `[10,5,15,1,8,null,7]`: the subtree `[5,1,8]` is a valid BST of size 3, but `15`'s right child `7 < 15` makes the `[15,_,7]` subtree invalid, so the whole tree is not a BST. Largest valid is size 3. ✅

**Complexity** — Time O(n) single post-order pass; Space O(h) recursion. **Edge cases:** the entire tree is a BST (answer = n); every leaf is trivially a BST of size 1 (answer ≥ 1); sentinel `min=+∞, max=−∞` for `null` lets any leaf pass its bound checks.

---

### Problem 42: Vertical Order with Stable Ties — HashMap + Single Sort

**Statement.** Return columns of a binary tree left to right; within a column order strictly by **row** (depth) top-to-bottom, and for nodes sharing the same `(row, column)` keep them in **insertion (left-to-right BFS) order** rather than by value.

**Constraints.** `1 ≤ n ≤ 10^4`; values in `int` range.

**Approach.** This is the "stable tie-break" variant of Problem 32 (which broke ties by value). Using BFS guarantees that nodes are discovered in left-to-right, top-to-bottom order, so same-cell nodes are appended in the correct stable order automatically — no per-cell value sort needed. Track min/max column seen, bucket node values into a `Map<Integer, List<Integer>>` keyed by column, then emit columns from `minCol` to `maxCol`. Because BFS already imposes the row order, the only ordering work is the column sweep, avoiding the O(n log n) sort of the value-tie variant.

```java
import java.util.*;

public class Solution {
    public List<List<Integer>> verticalOrder(TreeNode root) {
        List<List<Integer>> res = new ArrayList<>();
        if (root == null) return res;
        Map<Integer, List<Integer>> cols = new HashMap<>();
        Queue<TreeNode> nodes = new LinkedList<>();
        Queue<Integer> idx = new LinkedList<>();
        nodes.offer(root); idx.offer(0);
        int min = 0, max = 0;
        while (!nodes.isEmpty()) {
            TreeNode n = nodes.poll();
            int c = idx.poll();
            cols.computeIfAbsent(c, k -> new ArrayList<>()).add(n.val);
            min = Math.min(min, c);
            max = Math.max(max, c);
            if (n.left != null)  { nodes.offer(n.left);  idx.offer(c - 1); }
            if (n.right != null) { nodes.offer(n.right); idx.offer(c + 1); }
        }
        for (int c = min; c <= max; c++) res.add(cols.get(c));
        return res;
    }
}
```

**Dry run** on `[3,9,8,4,0,1,7]`: BFS visits columns 9→−1, 3→0, 8→1, 4→−2, 0/1→0 (same cell, kept in BFS order), 7→2. Sweeping `min=−2..max=2` yields `[[4],[9],[3,0,1],[8],[7]]`. ✅ The two col-0 row-2 nodes 0 and 1 stay in encounter order.

**Complexity** — Time O(n) (BFS + a column sweep, no sort); Space O(n) for the queues and map. **Edge cases:** single node → one column; a perfectly balanced tree has many same-column collisions resolved by BFS order; negative columns handled by tracking `min`.

---

### Problem 43: Binary Tree Maximum Path Sum with Path Reconstruction — Global Tracking + Backtrack

**Statement.** Beyond returning the maximum path sum (Problem 12b), also return the **actual sequence of node values** along that optimal path.

**Constraints.** `1 ≤ n ≤ 3·10^4`; values can be negative.

**Approach.** Keep the standard post-order gain recursion but, alongside the best sum, remember the **node where the optimal path bends** (its apex). The optimal path is: the best downward arm of the apex's left child, then the apex, then the best downward arm of its right child. After the recursion identifies the apex, reconstruct each arm by greedily walking toward whichever child gives the larger non-negative gain, recomputed on the way down. This separates "find the value" (one O(n) pass) from "materialize the path" (one O(h) descent per arm), which is cleaner than threading whole lists through the recursion and keeps the hot path allocation-free.

```java
import java.util.*;

public class Solution {
    private int best;
    private TreeNode apex;
    private Map<TreeNode, Integer> gainMemo = new HashMap<>();

    public List<Integer> maxPathSumPath(TreeNode root) {
        best = Integer.MIN_VALUE;
        apex = null;
        gainMemo.clear();
        gain(root);
        // Build left arm (top-down from apex.left), reverse, add apex, add right arm.
        LinkedList<Integer> path = new LinkedList<>();
        path.add(apex.val);
        for (TreeNode n = apex.left; n != null; ) {
            if (gain(n) <= 0) break;
            path.addFirst(n.val);
            n = armNext(n);
        }
        for (TreeNode n = apex.right; n != null; ) {
            if (gain(n) <= 0) break;
            path.addLast(n.val);
            n = armNext(n);
        }
        return path;
    }
    private TreeNode armNext(TreeNode n) {          // follow the larger positive arm
        int l = n.left  == null ? Integer.MIN_VALUE : gain(n.left);
        int r = n.right == null ? Integer.MIN_VALUE : gain(n.right);
        if (l <= 0 && r <= 0) return null;
        return l >= r ? n.left : n.right;
    }
    private int gain(TreeNode n) {
        if (n == null) return 0;
        if (gainMemo.containsKey(n)) return gainMemo.get(n);
        int l = Math.max(gain(n.left), 0);
        int r = Math.max(gain(n.right), 0);
        if (n.val + l + r > best) { best = n.val + l + r; apex = n; }
        int g = n.val + Math.max(l, r);
        gainMemo.put(n, g);
        return g;
    }
}
```

**Dry run** on `[-10,9,20,null,null,15,7]`: apex is `20`, left arm `15` (gain 15 > 0), right arm `7` (gain 7 > 0) → path `[15,20,7]`, sum 42. ✅

**Complexity** — Time O(n) (memoized gains make the reconstruction walk O(h) with O(1) gain lookups); Space O(n) for the gain memo + O(h) recursion. **Edge cases:** all-negative tree (apex is the single least-negative node, path is just that node since both arms are ≤ 0); a single node (path is itself); ties between arms resolved deterministically (`>=` picks left).

---

### Problem 44: Recover a BST with Morris Traversal — O(1) Space, Two-Swap Detection

**Statement.** Two nodes of a BST were swapped. Recover it using **O(1) extra space** (no recursion stack, no node array), framed here as the standalone optimization drill.

**Constraints.** `2 ≤ n ≤ 10^4`; values in `int` range.

**Approach.** This is the constant-space realization promised in Problem 28's follow-up, isolated so the threading mechanics stand alone. Morris inorder walks the tree in sorted order using temporary right-threads instead of a stack. During the walk, track the previous in-order node; the **first** descent (`prev.val > cur.val`) records `first = prev` and the **last** descent records `second = cur`. This single rule handles both the adjacent-swap case (one descent) and the far-apart case (two descents). The subtlety unique to Morris: every thread you create must be removed when revisited, or the tree is left corrupted.

```
sorted-order check during Morris:
   ... a  b  c  d ...      if b,c swapped -> one descent (c>... actually a<c, c>b)
   first = node before first dip, second = node at last dip; swap their values
```

```java
public class Solution {
    public void recoverTree(TreeNode root) {
        TreeNode first = null, second = null, prev = null, cur = root;
        while (cur != null) {
            if (cur.left == null) {
                if (prev != null && prev.val > cur.val) {
                    if (first == null) first = prev;
                    second = cur;
                }
                prev = cur;
                cur = cur.right;
            } else {
                TreeNode pred = cur.left;
                while (pred.right != null && pred.right != cur) pred = pred.right;
                if (pred.right == null) {
                    pred.right = cur;            // thread, descend left
                    cur = cur.left;
                } else {
                    pred.right = null;           // unthread (restore tree)
                    if (prev != null && prev.val > cur.val) {
                        if (first == null) first = prev;
                        second = cur;
                    }
                    prev = cur;
                    cur = cur.right;
                }
            }
        }
        if (first != null && second != null) {
            int t = first.val; first.val = second.val; second.val = t;
        }
    }
}
```

**Dry run** on inorder `[1,3,2,4]` (3,2 swapped): one descent `3→2` sets `first=3, second=2`; swap → `[1,2,3,4]`. On `[3,2,1]`-shaped inorder (1,3 swapped): descents `3→2` and `2→1` set `first=3, second=1`; swap → `[1,2,3]`. ✅

**Complexity** — Time O(n) (each edge traversed at most twice by Morris); Space **O(1)** — only a handful of pointers. **Edge cases:** adjacent swap (single descent); the two swapped nodes are the global min/max; if you forget the unthread branch the tree is permanently corrupted and nodes are double-counted.

---

### Problem 45: Count BSTs of n Nodes (Catalan) + Generate All Unique BSTs — DP & Structural Recursion

**Statement.** (a) Return how many structurally unique BSTs store keys `1..n`. (b) Return the **roots of all** such unique BSTs.

**Constraints.** `1 ≤ n ≤ 19` for generation (count explodes per the Catalan numbers); counting alone is fine to larger `n` within `long`.

**Approach.** Both rest on the same recurrence: choose each `i` in `1..n` as the root; the left subtree is built from `1..i-1` and the right from `i+1..n`, and the choices are independent. (a) The **count** `G(n)` satisfies `G(n) = Σ G(i-1)·G(n-i)` — the n-th **Catalan number** — computed with an O(n²) DP. (b) **Generation** recurses over the range `[lo, hi]`: for each root value, take the Cartesian product of all left-subtree shapes and all right-subtree shapes. Memoizing by range avoids rebuilding identical subtree sets, though distinct trees still require distinct node objects.

```java
import java.util.*;

public class Solution {
    // (a) Catalan count, O(n^2) time / O(n) space
    public int numTrees(int n) {
        long[] g = new long[n + 1];
        g[0] = 1;
        for (int nodes = 1; nodes <= n; nodes++)
            for (int root = 1; root <= nodes; root++)
                g[nodes] += g[root - 1] * g[nodes - root];
        return (int) g[n];
    }

    // (b) Generate all unique BSTs over 1..n
    public List<TreeNode> generateTrees(int n) {
        if (n == 0) return new ArrayList<>();
        return build(1, n);
    }
    private List<TreeNode> build(int lo, int hi) {
        List<TreeNode> all = new ArrayList<>();
        if (lo > hi) { all.add(null); return all; }   // empty subtree placeholder
        for (int root = lo; root <= hi; root++) {
            List<TreeNode> lefts  = build(lo, root - 1);
            List<TreeNode> rights = build(root + 1, hi);
            for (TreeNode l : lefts)
                for (TreeNode r : rights) {
                    TreeNode node = new TreeNode(root);
                    node.left = l;
                    node.right = r;
                    all.add(node);
                }
        }
        return all;
    }
}
```

**Dry run** — `numTrees(3) = 5`. `generateTrees(3)` yields the 5 shapes with roots 1 (right chain), 2 (balanced), 3 (left chain), and the two single-bend variants. ✅

**Complexity** — (a) Time O(n²), Space O(n). (b) Time O(n · Catalan(n)) (one node allocation per distinct tree node across all trees); Space O(Catalan(n)) trees. **Edge cases:** `n=0` → empty list (no tree); `n=1` → one single-node tree; the `lo > hi` placeholder `null` is what lets a root pair with an empty subtree.

---

### Problem 46: Inorder Successor in a BST (with and without parent pointers) — Ordered Walk

**Statement.** (a) Given a BST root and a node `p`, return `p`'s inorder successor (the smallest key greater than `p.val`), or `null`. (b) Solve it when each node has a `parent` pointer but you are **not** given the root.

**Constraints.** `1 ≤ n ≤ 10^4`; unique values; `p` exists in the tree.

**Approach.** (a) **Without parent pointers**, walk from the root: whenever the current node's value is greater than `p.val` it is a successor *candidate*, so record it and go left to find a smaller candidate; otherwise go right. The last recorded candidate is the answer — O(h), no traversal of the whole tree. (b) **With parent pointers**: if `p` has a right subtree, the successor is that subtree's leftmost node; otherwise climb parents until you move up from a **left** child — that parent is the successor (climbing from a right child means you have already passed it).

```java
public class Solution {
    // (a) From root, no parent pointers
    public TreeNode inorderSuccessor(TreeNode root, TreeNode p) {
        TreeNode succ = null, cur = root;
        while (cur != null) {
            if (p.val < cur.val) { succ = cur; cur = cur.left; }  // candidate, look smaller
            else cur = cur.right;
        }
        return succ;
    }

    // (b) With parent pointers, no root needed
    static class Node { int val; Node left, right, parent; }
    public Node successorWithParent(Node p) {
        if (p.right != null) {                 // leftmost of right subtree
            Node n = p.right;
            while (n.left != null) n = n.left;
            return n;
        }
        Node n = p;
        while (n.parent != null && n == n.parent.right) n = n.parent; // climb past right edges
        return n.parent;                        // first time we go up from a left child
    }
}
```

**Dry run** (a) on BST `[5,3,6,2,4,null,null,1]`, `p=4`: from 5 (4<5 → succ=5, left) to 3 (4>3 → right) to 4 (4>4? no, 4<4 false → right) → null. Answer 5. ✅ (b) For a node with no right child that is its parent's left child, the parent is returned immediately.

**Complexity** — Time O(h) both variants; Space O(1). **Edge cases:** `p` is the maximum (no successor → `null`); `p` has a right subtree (successor is down, not up); predecessor is the mirror image (track candidates when `cur.val < p.val` and go right).

---

### Problem 47: Distribute Coins in a Binary Tree — Post-Order Flow Accounting

**Statement.** Each of the `n` nodes has `node.val` coins, and there are exactly `n` coins total. In one move you may shift a single coin between adjacent nodes. Return the minimum number of moves to make every node hold exactly one coin.

**Constraints.** `1 ≤ n ≤ 100`; `0 ≤ node.val ≤ n`; total coins `== n`.

**Approach.** Think of each edge as a pipe and count the net coin **flow** across it. In a post-order pass, each subtree reports its **balance** = (coins it has) − (nodes it has). A positive balance means surplus coins flow up to the parent; a negative balance means coins must flow down. Either way, the number of moves across the edge to the parent is the **absolute value** of the child's balance, because that many coins cross that edge. Summing `|leftBalance| + |rightBalance|` over all nodes gives the total moves — an elegant flow argument that avoids any explicit simulation.

```
        0(node)            balances bubble up:
       / \                 left leaf 3 -> +2 (needs to give 2 away)  -> 2 moves
      3   0                right leaf 0 -> -1 (needs 1)               -> 1 move
                           root: 0 + (+2) + (-1) -1 = 0  (conserved)
```

```java
public class Solution {
    private int moves = 0;

    public int distributeCoins(TreeNode root) {
        moves = 0;
        balance(root);
        return moves;
    }
    private int balance(TreeNode n) {
        if (n == null) return 0;
        int l = balance(n.left);
        int r = balance(n.right);
        moves += Math.abs(l) + Math.abs(r);     // coins crossing the two child edges
        return n.val + l + r - 1;               // surplus(+)/deficit(-) passed up
    }
}
```

**Dry run** on `[3,0,0]`: left leaf balance `0-1=-1`, right leaf `0-1=-1`; root adds `|−1|+|−1| = 2` moves and returns `3 + (−1) + (−1) − 1 = 0` (conserved). Answer 2. ✅

**Complexity** — Time O(n) single post-order pass; Space O(h) recursion. **Edge cases:** already balanced (every node has 1 → 0 moves); all coins at the root, leaves empty (each must receive 1 → moves equal the total downward distance); single node always 0 moves.

---

### Problem 48: Smallest Subtree Containing All Deepest Nodes — Depth + LCA Fusion

**Statement.** Return the root of the smallest subtree that contains **all** of the tree's deepest nodes (deepest = maximum depth). Equivalent to the LCA of the deepest leaves.

**Constraints.** `1 ≤ n ≤ 500`; distinct values in `[0, 500]`.

**Approach.** This is the optimization-minded restatement of Problem 25, emphasizing that a naive "find max depth, then find LCA of all deepest nodes" two-pass approach is O(n) but does redundant work, whereas a **single** post-order pass that returns `(depth, answerNode)` per subtree solves it in one traversal. At each node compare the two children's reported depths: deeper-left → bubble the left answer; deeper-right → bubble the right answer; **equal** → this node is the answer because the deepest nodes straddle both sides. The fusion of depth computation and LCA tracking into one return value is the key efficiency.

```java
public class Solution {
    private TreeNode answer;
    private int maxDepth;

    public TreeNode subtreeWithAllDeepest(TreeNode root) {
        answer = null;
        maxDepth = -1;
        depth(root, 0);
        return answer;
    }
    // returns the deepest depth reachable in this subtree; sets `answer` along the way
    private int depth(TreeNode n, int d) {
        if (n == null) return d - 1;
        int l = depth(n.left, d + 1);
        int r = depth(n.right, d + 1);
        if (l == r) {                       // both sides reach the same deepest level
            maxDepth = Math.max(maxDepth, l);
            if (l == maxDepth) answer = n;   // highest node where the deepest sides tie
        }
        return Math.max(l, r);
    }
}
```

A cleaner formulation returns a `(depth, node)` pair directly:

```java
public class Solution2 {
    private static class P { int depth; TreeNode node; P(int d, TreeNode n){depth=d;node=n;} }
    public TreeNode subtreeWithAllDeepest(TreeNode root) { return dfs(root).node; }
    private P dfs(TreeNode n) {
        if (n == null) return new P(0, null);
        P l = dfs(n.left), r = dfs(n.right);
        if (l.depth == r.depth) return new P(l.depth + 1, n);
        return l.depth > r.depth ? new P(l.depth + 1, l.node) : new P(r.depth + 1, r.node);
    }
}
```

**Dry run** on `[3,5,1,6,2,0,8,null,null,7,4]`: deepest nodes are 7 and 4 (depth 3) under node 2; their tie point is node 2, which is the answer. ✅

**Complexity** — Time O(n) single pass; Space O(h) recursion. **Edge cases:** unique deepest node (it is its own answer); perfectly balanced tree (root is the answer); the `Solution2` pair version is the clearer one to write under interview pressure.

---

### Problem 49: Sum of Distances in Tree — Rerooting (Two-Pass DP)

**Statement.** Given an undirected tree with `n` nodes (`0..n-1`) and `n-1` edges, return an array `ans` where `ans[i]` is the sum of distances from node `i` to **all** other nodes.

**Constraints.** `1 ≤ n ≤ 3·10^4`; the graph is a tree (connected, acyclic).

**Approach.** Computing each node's answer independently is O(n²). The optimal O(n) technique is **rerooting**. Root the tree at 0. First post-order pass computes `count[v]` (subtree node count) and `res[0]` (sum of distances from the root) using `res[0] += res[child] + count[child]`. Second pre-order pass derives every other node's answer from its parent's in O(1): moving the root from `parent` to `child`, the `count[child]` nodes get one step **closer** and the remaining `n - count[child]` get one step **farther**, so `res[child] = res[parent] - count[child] + (n - count[child])`. Two DFS passes, linear total.

```
reroot from parent u to child v:
   res[v] = res[u] − count[v] + (n − count[v])
            └ v's subtree moves 1 closer ┘   └ everyone else moves 1 farther ┘
```

```java
import java.util.*;

public class Solution {
    private List<Integer>[] g;
    private int[] count, res;
    private int n;

    public int[] sumOfDistancesInTree(int n, int[][] edges) {
        this.n = n;
        g = new List[n];
        for (int i = 0; i < n; i++) g[i] = new ArrayList<>();
        for (int[] e : edges) { g[e[0]].add(e[1]); g[e[1]].add(e[0]); }
        count = new int[n];
        res = new int[n];
        postOrder(0, -1);     // fills count[], res[0]
        preOrder(0, -1);      // reroot to fill res[1..n-1]
        return res;
    }
    private void postOrder(int u, int parent) {
        count[u] = 1;
        for (int v : g[u]) if (v != parent) {
            postOrder(v, u);
            count[u] += count[v];
            res[u]   += res[v] + count[v];   // each subtree node is 1 farther via u
        }
    }
    private void preOrder(int u, int parent) {
        for (int v : g[u]) if (v != parent) {
            res[v] = res[u] - count[v] + (n - count[v]);
            preOrder(v, u);
        }
    }
}
```

**Dry run** on `n=6`, edges `[[0,1],[0,2],[2,3],[2,4],[2,5]]`: post-order gives `res[0]=8`; rerooting yields `res = [8,12,6,10,10,10]`. ✅

**Complexity** — Time O(n) (two DFS passes over `n-1` edges); Space O(n) for adjacency + recursion (convert to iterative or raise stack size for the deepest skewed inputs). **Edge cases:** `n=1` → `[0]` (no edges); a path graph (rerooting still O(1) per node); recursion depth up to `n` for a path — note iterative conversion for very deep inputs.

---

### Problem 50: Number of Ways to Reconstruct a BST from a Preorder Multiset (Build & Verify) — Bounds Recursion + Validation

**Statement.** Given an integer array that is *claimed* to be the preorder traversal of a BST with distinct values, (a) reconstruct it in O(n) and (b) **verify** in O(n) whether a given array could be a valid BST preorder at all — rejecting sequences that no BST can produce.

**Constraints.** `1 ≤ n ≤ 10^4`; values in `int` range; the validity check must not build the tree.

**Approach.** (a) is Problem 34's O(n) bounds recursion. The interesting optimization is (b): verify validity in **O(n) time and O(1) extra space** without constructing anything, using a monotonic-stack idea. Scan left to right maintaining a `lowerBound` (initially −∞) and a stack of ancestors whose right subtree we have not yet entered. Each new value must exceed `lowerBound`. While the value is greater than the stack top, we are moving into a right subtree, so pop and raise `lowerBound` to the popped value (every subsequent node must be larger than this ancestor). Push the current value. If any value falls below `lowerBound`, the sequence is **not** a valid BST preorder. This is the classic "verify preorder of BST" linear check.

```java
import java.util.*;

public class Solution {
    // (a) Reconstruct in O(n) (bounds recursion)
    private int idx;
    private int[] pre;
    public TreeNode bstFromPreorder(int[] preorder) {
        pre = preorder; idx = 0;
        return build(Long.MAX_VALUE);
    }
    private TreeNode build(long bound) {
        if (idx == pre.length || pre[idx] > bound) return null;
        TreeNode node = new TreeNode(pre[idx++]);
        node.left  = build(node.val);
        node.right = build(bound);
        return node;
    }

    // (b) Verify validity in O(n) time, O(1) extra space (in-place stack reusing the array)
    public boolean verifyPreorder(int[] preorder) {
        int lowerBound = Integer.MIN_VALUE;
        int sp = -1;                       // stack top index, reuse preorder as the stack
        for (int value : preorder) {
            if (value < lowerBound) return false;          // violates an ancestor's lower bound
            while (sp >= 0 && value > preorder[sp])        // entering a right subtree
                lowerBound = preorder[sp--];
            preorder[++sp] = value;                        // push current onto in-place stack
        }
        return true;
    }
}
```

**Dry run** (b) on `[5,2,1,3,6]` (valid): push 5,2,1; at 3 pop 2,1 raising `lowerBound` to 2 then... 3>2 so pop, lower=2, then 3<5 push; 6 pops 3 then 5, lower=5, push → returns `true`. On `[5,2,6,1,3]` (invalid): after 6 raises `lowerBound` to 5, the later `1 < 5` triggers `false`. ✅

**Complexity** — (a) Time O(n), Space O(h). (b) Time O(n) (each element pushed/popped once), Space **O(1)** by reusing the input array as the stack (O(n) if a separate stack is used). **Edge cases:** strictly increasing input (all right children, valid, stack drains repeatedly); strictly decreasing (all left children, valid, never pops); a single dip below an established `lowerBound` rejects the whole sequence; single element is trivially valid.

---

## Interview Q&A by Level

### 🟢 Basic

- **Q: What's the difference between a binary tree and a BST?** A binary tree only caps children at 2; a BST adds the ordering invariant (left < node < right) that enables O(log n) search.
- **Q: Name the four DFS/BFS traversals and one use of each.** Preorder (copy/serialize a tree), inorder (sorted output for a BST), postorder (delete a tree / evaluate an expression / compute heights), level-order (shortest path in an unweighted tree, level summaries).
- **Q: What is the height of a tree with one node?** 0 if measured in edges, 1 if measured in nodes — always clarify the convention in interviews.
- **Q: How do you find the min/max in a BST?** Min = leftmost node, max = rightmost; walk one direction until the child is null. O(h).

### 🟡 Intermediate

- **Q: Why does an inorder traversal of a BST come out sorted?** Because at each node you fully visit the strictly-smaller left subtree, then the node, then the strictly-larger right subtree — exactly ascending order.
- **Q: When would you choose iterative or Morris traversal over recursion?** When the tree may be deeply skewed (risk of stack overflow at n ≈ 10^4–10^5) or when O(1) extra space is required (Morris).
- **Q: Recursion vs BFS for level-order — tradeoffs?** BFS with a queue is natural and O(width) space; a DFS can also produce levels by passing the depth and indexing into a results list, using O(height) space instead — handy when width ≫ height.
- **Q: How do you delete a node from a BST?** Three cases: leaf (remove), one child (splice the child up), two children (replace with inorder successor — the min of the right subtree — then delete that successor).

### 🟠 Advanced

- **Q: How do AVL and red-black trees stay balanced, and how do they differ?** Both use **rotations** (left/right) on insert/delete. AVL keeps a per-node balance factor in {−1,0,+1} → stricter balance, faster lookups, more rotations on writes. Red-black trees relax balance using node colors and 5 invariants (root black, no two consecutive reds, equal black-height on every path), guaranteeing height ≤ 2·log₂(n+1) with fewer rotations — better for write-heavy workloads. Java's `TreeMap`/`TreeSet` and the Linux scheduler use red-black trees.
- **Q: Sketch a rotation.** A right rotation at `y` with left child `x` makes `x` the new subtree root, `y` becomes `x`'s right child, and `x`'s former right subtree becomes `y`'s left subtree — preserving the BST order while reducing height on the heavy side.
  ```
        y                x
       / \    right →   / \
      x   C   rotate   A   y
     / \                  / \
    A   B                B   C
  ```
- **Q: Why do databases and filesystems use B-trees / B+-trees instead of binary BSTs?** Disk I/O is the bottleneck and reads happen in blocks (e.g., 4–16 KB). A binary tree of a billion keys is ~30 levels → ~30 disk seeks. A B+-tree with fan-out ~hundreds is only 3–4 levels → 3–4 seeks. **B+-trees** additionally store all values in the leaves and chain leaves in a linked list, making range scans (`WHERE x BETWEEN a AND b`) sequential and cache-friendly. Internal nodes hold only keys, maximizing fan-out.
- **Q: What is a trie and when does it beat a hash map?** A prefix tree keying on characters: lookup/insert is O(L) in the key length, independent of dictionary size, and it answers prefix queries (autocomplete, longest-prefix-match in routers) that hash maps cannot. Cost: higher memory per node (mitigated by radix/compressed tries).

### 🔴 Expert

- **Q: Give the amortized analysis intuition for a balanced BST under a sequence of operations.** Each insert/delete touches O(log n) nodes and performs O(1) amortized rotations (red-black guarantees at most a constant number of rotations per update, even though recoloring may cascade O(log n) up the tree — the recoloring is the amortized part). Over `m` operations, total work is O(m log n).
- **Q: How would you index a 500 GB table that doesn't fit in RAM, supporting point lookups and range scans?** A B+-tree (or LSM-tree for write-heavy). B+-tree: keep the upper levels cached in RAM, leaves on disk; each query is ~3–4 page reads; the leaf linked-list serves range scans sequentially. For very high write throughput, an **LSM-tree** (memtable + SSTables + compaction, as in RocksDB/Cassandra) trades read amplification for sequential writes.
- **Q: How do you balance a degenerate BST, and what's the cost?** Inorder-traverse to a sorted array (O(n)), then recursively pick the middle as root to build a height-balanced tree (O(n)). Online alternative: use a self-balancing structure from the start, or a treap/skip-list for randomized balance with simpler code.
- **Q: Concurrency — how do you make a tree thread-safe at scale?** Coarse locking kills throughput; use lock-free/optimistic techniques (hand-over-hand "lock coupling" for B-trees, or copy-on-write/persistent trees for read-heavy workloads). Databases use **latch coupling** plus crabbing on B+-tree pages so readers and writers don't block the whole index.
- **Q: When is a BST the wrong choice entirely?** When you only need membership/lookup with no ordering — a hash table gives O(1) average. When keys are strings with shared prefixes — a trie. When data lives on disk — a B+-tree. When you need top-k or priority access — a heap.

---

## ⚠️ Common Pitfalls

- **Validating a BST by comparing only to immediate children** — a node can be larger than its parent yet still violate an ancestor's bound. Pass down `(low, high)` ranges or use inorder monotonicity.
- **Integer overflow in BST bounds** — `Integer.MIN_VALUE`/`MAX_VALUE` node values break naive comparisons; use `long` bounds or nullable `Integer`.
- **Forgetting to backtrack** in path/DFS problems — always remove the node from the path list after exploring both subtrees, and snapshot (`new ArrayList<>(path)`) when recording a result, never store the live list.
- **Off-by-one in diameter** — edges vs nodes. "Longest path" usually means edges; double-check the expected output.
- **Min-depth treating a one-child node as a leaf** — a node with only one child is *not* a leaf; you must descend the existing child, not take `min` of `0`.
- **Stack overflow on skewed trees** — recursion depth = height; for n up to 10^5 a degenerate tree blows the stack. Use iterative/Morris when constraints are large.
- **Mutating the tree in Morris traversal and not restoring** — Morris temporarily creates threads; forgetting the "remove thread" branch corrupts the tree and double-visits nodes.
- **Serialize using inorder only** — inorder alone is ambiguous; you need preorder/level-order *with null markers* (or two traversals) to reconstruct uniquely.
- **Assuming preorder + postorder uniquely determines a tree** — it doesn't for trees with single-child nodes; only preorder+inorder or postorder+inorder guarantee uniqueness (with distinct values).

---

## 📚 Further Reading

- **CLRS**, *Introduction to Algorithms* — ch. 12 (BSTs), 13 (Red-Black Trees), 18 (B-Trees). The canonical rigorous treatment with proofs.
- **Sedgewick & Wayne**, *Algorithms* (4th ed.) — accessible BST, red-black (left-leaning), and trie chapters with clean Java.
- **Database Internals** by Alex Petrov — deep dive on B-trees, B+-trees, LSM-trees, and how real storage engines implement them.
- **The Art of Computer Programming, Vol. 3** (Knuth) — sorting & searching, the definitive trie/B-tree reference.
- LeetCode **Tree** and **Binary Search Tree** tags; NeetCode's "Trees" roadmap for spaced practice.
- Visualgo.net and the USFCA data-structure visualizations — animate AVL/red-black rotations and B-tree splits to build intuition.

[← Back to master index](../README.md) · [← DSA index](README.md)
