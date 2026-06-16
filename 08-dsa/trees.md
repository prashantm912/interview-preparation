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
