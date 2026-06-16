# Recursion & Backtracking

Recursion is the art of solving a problem by reducing it to smaller copies of itself; backtracking is recursion that *builds candidates incrementally and abandons (un-does) a partial solution the moment it cannot possibly lead to a valid one*. Together they power the entire combinatorial-search family — subsets, permutations, N-Queens, Sudoku, word search, parenthesis generation — and they are the conceptual root of DFS, divide-and-conquer, and dynamic programming.

[← Back to master index](../README.md) | [← DSA index](README.md)

---

## Concept & Intuition

A **recursive function** solves a problem by:
1. **Base case(s)** — the smallest input(s) the function answers *directly*, with no further recursion. Missing or wrong base cases cause infinite recursion and a `StackOverflowError`.
2. **Recursive case** — express the answer in terms of one or more *strictly smaller* sub-problems, then combine their results. Each call must make measurable progress toward a base case (a decreasing "variant").

The machine implements recursion with a **call stack**: every call pushes a *stack frame* (parameters, locals, return address). When the base case returns, frames pop and combine on the way back up. This is why recursion depth — not total work — bounds the stack-space usage.

**Backtracking** is a refinement of recursion for *search* problems where you must enumerate or find configurations that satisfy constraints. The template is always the same three moves:

```
choose  →  explore (recurse)  →  un-choose (backtrack)
```

You make a choice, recurse to extend it, and on return you *undo* that choice so the slot is clean for the next candidate. The search forms a tree; backtracking is a DFS over that tree with **pruning** — you cut a branch as soon as the partial solution becomes infeasible, never expanding the dead subtree.

```
            SUBSETS of [1,2,3]  — the recursion / decision tree
                          [] (start)
                 include 1 /        \ skip 1
                      [1]              []
              inc2 /     \ skip2   inc2 /   \ skip2
                [1,2]    [1]      [2]        []
            inc3/  \   /   \    /    \      /   \
         [1,2,3][1,2][1,3][1] [2,3] [2]  [3]    []     ← 2^3 = 8 leaves
```

**When to reach for recursion / backtracking**
- The answer is naturally defined in terms of itself: trees, nested structures, divide-and-conquer (merge sort, binary search), grammar parsing.
- You must **enumerate all** combinations/permutations/partitions, or **find one / count** configurations meeting constraints (placements, colorings, paths).
- The problem has the phrase "generate all", "find all", "is there a way to", "place / fill / arrange", or a small branching factor with a manageable depth.

**Invariants to keep straight**
- The recursion **must shrink the problem** every call (smaller index, fewer remaining items, smaller board) — otherwise it never terminates.
- In backtracking, the shared mutable state (the partial path, a board, a `used[]` array) must be **exactly restored** after each recursive call. "Un-choose" mirrors "choose".
- When you store a partial result in your answer list, store a **copy** (`new ArrayList<>(path)`), because `path` keeps mutating.
- **Pruning correctness**: a branch you cut must be *provably* unable to yield a valid/optimal answer, or you change the result.

---

## Complexity Cheat-Sheet

Recursion cost = (number of nodes in the recursion tree) × (work per node). Backtracking is usually **exponential or factorial** because the output itself is that large.

| Problem / Operation | Time | Space (excl. output) | Notes |
|---|---|---|---|
| Generic recursion (n calls, O(1) each) | O(n) | O(n) stack | e.g. factorial, list sum |
| Binary recursion, halving (T(n)=2T(n/2)+O(1)) | O(n) | O(log n) | balanced divide |
| Naive Fibonacci T(n)=T(n-1)+T(n-2) | O(φⁿ)≈O(1.618ⁿ) | O(n) | memoize → O(n) |
| **Subsets / power set** | O(n·2ⁿ) | O(n) | 2ⁿ subsets, O(n) copy each |
| **Permutations** | O(n·n!) | O(n) | n! perms, O(n) copy each |
| **Combinations** C(n,k) | O(k·C(n,k)) | O(k) | choose-k |
| **Combination Sum** | O(2^t) worst, t=target/min | O(t/min) | depends on candidates |
| **Generate Parentheses** (n pairs) | O(4ⁿ/√n) Catalan | O(n) | Cₙ valid strings |
| **N-Queens** (count/all) | O(n!) (heavily pruned) | O(n) | bitmask prune |
| **Sudoku solver** | O(9^(empty cells)) worst | O(1) board fixed | constraint prune |
| **Word Search** (m×n grid, word L) | O(m·n·4^L) | O(L) | DFS each cell |
| **Palindrome Partitioning** | O(n·2ⁿ) | O(n) | 2ⁿ⁻¹ cut sets |
| **Rat in a Maze** (n×n) | O(4^(n²)) worst | O(n²) | prune blocked |
| Tail call (no JVM elimination) | O(n) time, O(n) stack | O(n) | Java does NOT optimize |

> The space column is the **auxiliary** recursion-stack/working space. The *output* of an enumeration problem is itself exponential, so total memory if you materialize all results is dominated by the output.

---

## Patterns & Recognition

| Signal in the prompt | Likely technique |
|---|---|
| "Generate / return **all** subsets / combinations / permutations" | Backtracking with choose-explore-unchoose |
| "Find **all** solutions" or "**count** the ways to place/fill/arrange" | Constraint backtracking with pruning |
| "Does a path exist", "can the board be filled" | Backtracking returning `boolean`, short-circuit on first success |
| Grid + "find word / path / region" | DFS backtracking with `visited` marking + restore |
| "Partition string/array into valid pieces" | Backtracking over cut positions |
| Problem defined on **trees / nested structure** | Plain recursion (DFS) |
| "Divide the problem in half" / sorted | Divide-and-conquer recursion |
| Overlapping sub-problems, optimal substructure | Recursion **+ memoization** → DP |

**Decision heuristics**
- **Backtracking vs DP**: if you must *list* every configuration → backtracking. If you only need a *count / min / max / boolean* and sub-problems **overlap** → memoize/DP (don't enumerate).
- **`for`-loop start index** prevents duplicate combinations (subsets, combination sum). A `used[]`/swap prevents reusing an element within one permutation.
- **Sort first** when you need to (a) prune with "if current > remaining, break", or (b) skip duplicates with `if (i>start && a[i]==a[i-1]) continue;`.
- **Pruning** is the single biggest lever: feasibility checks before recursing (constraint propagation), bounding (remaining < needed → cut), and symmetry breaking.

---

## Coding Problems

### Problem 1: Power / Factorial — recursion mechanics & base cases

**Statement.** Implement `factorial(n)` and fast exponentiation `pow(x, n)` for `0 ≤ n ≤ 10^9`, where `pow` must run in O(log n). Demonstrate clean base cases.

**Approach.** Factorial is the textbook linear recursion: `n! = n × (n-1)!`, base case `0! = 1`. Naive `pow` multiplies x n times → O(n). The **optimal** uses *exponentiation by squaring*: `x^n = (x^(n/2))² ` (times `x` if n is odd) — halving n each call gives O(log n) and O(log n) stack.

```java
public class Power {
    // n! — linear recursion. Base case stops at 0.
    public long factorial(int n) {
        if (n <= 1) return 1;           // base case: 0! = 1! = 1
        return n * factorial(n - 1);    // recursive case shrinks n
    }

    // x^n in O(log n) via squaring. Handles negative exponents.
    public double pow(double x, long n) {
        if (n < 0) { x = 1 / x; n = -n; }
        return fastPow(x, n);
    }

    private double fastPow(double x, long n) {
        if (n == 0) return 1.0;              // base case
        double half = fastPow(x, n / 2);     // one recursive call, n halved
        double sq = half * half;
        return (n % 2 == 1) ? sq * x : sq;   // fix-up for odd exponent
    }
}
```

**Dry run.** `pow(2,10)` → `fastPow(2,10)` = `half²` where `half=fastPow(2,5)` = `(fastPow(2,2))²·2`; `fastPow(2,2)=(fastPow(2,1))²`; `fastPow(2,1)=(fastPow(2,0))²·2 = 1·1·2 = 2`; back up: 2²=4, ·2 = 8, then 8²·... wait carefully: `fastPow(2,2)=2²=4`, `fastPow(2,5)=4²·2=32`, `fastPow(2,10)=32²=1024`. ✓

**Time:** factorial O(n); pow **O(log n)**. **Space:** O(n) and O(log n) stack respectively.

**Follow-ups.** Iterative squaring (no stack); modular exponentiation `(x^n) mod m` (crypto); overflow-safe factorial with `BigInteger`; why Java throws `StackOverflowError` near ~10⁴–10⁵ depth.

---

### Problem 2: Subsets (Power Set) — LeetCode 78

**Statement.** Given a distinct-element array `nums` (length ≤ 10), return all 2ⁿ subsets in any order.

**Approach.** Two canonical backtracking shapes. **(a) Include/exclude** per element — a binary decision tree of depth n. **(b) Start-index loop** — at each level, try every later element as the next member; every node *is* a subset. Both are O(n·2ⁿ). A bitmask iteration is the non-recursive alternative.

```java
import java.util.*;

public class Subsets {
    public List<List<Integer>> subsets(int[] nums) {
        List<List<Integer>> res = new ArrayList<>();
        backtrack(nums, 0, new ArrayList<>(), res);
        return res;
    }

    private void backtrack(int[] nums, int start,
                           List<Integer> path, List<List<Integer>> res) {
        res.add(new ArrayList<>(path));        // every node is a valid subset → snapshot
        for (int i = start; i < nums.length; i++) {
            path.add(nums[i]);                 // choose
            backtrack(nums, i + 1, path, res); // explore (i+1 = no reuse, no dup)
            path.remove(path.size() - 1);      // un-choose (backtrack)
        }
    }

    // Bitmask alternative — no recursion.
    public List<List<Integer>> subsetsBitmask(int[] nums) {
        int n = nums.length;
        List<List<Integer>> res = new ArrayList<>();
        for (int mask = 0; mask < (1 << n); mask++) {
            List<Integer> sub = new ArrayList<>();
            for (int i = 0; i < n; i++)
                if ((mask & (1 << i)) != 0) sub.add(nums[i]);
            res.add(sub);
        }
        return res;
    }
}
```

**Dry run** for `[1,2]`: add `[]`; i=0 → add 1 → add `[1]`; i=1 → add 2 → add `[1,2]`; backtrack to `[1]`, backtrack to `[]`; i=1 → add 2 → add `[2]`. Result `[], [1], [1,2], [2]`.

**Time:** O(n·2ⁿ). **Space:** O(n) stack + path.

**Follow-ups.** **Subsets II** (with duplicates) — sort, then `if (i>start && nums[i]==nums[i-1]) continue;`. Subsets of a given size k. Lexicographic order. Streaming (don't store all).

---

### Problem 3: Combinations — LeetCode 77

**Statement.** Return all `C(n, k)` combinations of integers `1..n` taken `k` at a time. `1 ≤ k ≤ n ≤ 20`.

**Approach.** Backtrack with a start index so combinations are increasing (no permutation duplicates). **Pruning**: if the remaining numbers `n - i + 1` are fewer than the slots still needed `k - path.size()`, abandon — this turns a naive 2ⁿ into the tight C(n,k) tree.

```java
import java.util.*;

public class Combinations {
    public List<List<Integer>> combine(int n, int k) {
        List<List<Integer>> res = new ArrayList<>();
        backtrack(1, n, k, new ArrayList<>(), res);
        return res;
    }

    private void backtrack(int start, int n, int k,
                           List<Integer> path, List<List<Integer>> res) {
        if (path.size() == k) {                       // base case: full combo
            res.add(new ArrayList<>(path));
            return;
        }
        int need = k - path.size();
        // PRUNE: i can go at most up to n - need + 1 and still leave enough numbers
        for (int i = start; i <= n - need + 1; i++) {
            path.add(i);
            backtrack(i + 1, n, k, path, res);
            path.remove(path.size() - 1);
        }
    }
}
```

**Dry run** `n=4,k=2`: start 1 → [1] → 2,3,4 give [1,2],[1,3],[1,4]; start 2 → [2,3],[2,4]; start 3 → [3,4]; start 4 pruned (need=2, upper bound = 4-2+1=3, loop body skipped). 6 = C(4,2). ✓

**Time:** O(k·C(n,k)). **Space:** O(k).

**Follow-ups.** Combinations with repetition. Generate in lexicographic vs Gray-code order. Why the prune bound is `n - need + 1`.

---

### Problem 4: Permutations — LeetCode 46

**Statement.** Return all `n!` orderings of a distinct array (n ≤ 8).

**Approach.** Two clean shapes: **(a) `used[]` boolean** — pick any unused element at each depth; **(b) in-place swap** — swap index `start` with each `i ≥ start`, recurse, swap back (O(1) extra space, no `used[]`). Both O(n·n!).

```java
import java.util.*;

public class Permutations {
    // (a) used[] approach
    public List<List<Integer>> permute(int[] nums) {
        List<List<Integer>> res = new ArrayList<>();
        backtrack(nums, new boolean[nums.length], new ArrayList<>(), res);
        return res;
    }
    private void backtrack(int[] nums, boolean[] used,
                           List<Integer> path, List<List<Integer>> res) {
        if (path.size() == nums.length) {
            res.add(new ArrayList<>(path));
            return;
        }
        for (int i = 0; i < nums.length; i++) {
            if (used[i]) continue;          // skip elements already in path
            used[i] = true; path.add(nums[i]);          // choose
            backtrack(nums, used, path, res);            // explore
            used[i] = false; path.remove(path.size()-1); // un-choose
        }
    }

    // (b) swap approach — fewer allocations
    public List<List<Integer>> permuteSwap(int[] nums) {
        List<List<Integer>> res = new ArrayList<>();
        dfs(nums, 0, res);
        return res;
    }
    private void dfs(int[] a, int start, List<List<Integer>> res) {
        if (start == a.length) {
            List<Integer> p = new ArrayList<>();
            for (int x : a) p.add(x);
            res.add(p);
            return;
        }
        for (int i = start; i < a.length; i++) {
            swap(a, start, i);
            dfs(a, start + 1, res);
            swap(a, start, i);           // restore
        }
    }
    private void swap(int[] a, int i, int j) { int t=a[i]; a[i]=a[j]; a[j]=t; }
}
```

**Dry run** `[1,2,3]`, swap variant: start=0 fixes 1,2,3 in turn; for 1 fixed, start=1 swaps 2/3 → 123,132; for 2 fixed → 213,231; for 3 fixed → 321,312. 6 perms. ✓

**Time:** O(n·n!). **Space:** O(n) (used[] approach) / O(n) stack (swap).

**Follow-ups.** **Permutations II** (duplicates) — sort + `if (i>0 && nums[i]==nums[i-1] && !used[i-1]) continue;`. Next-permutation (single step, O(n)). k-th permutation by factorial number system (no enumeration).

---

### Problem 5: Combination Sum — LeetCode 39

**Statement.** Given distinct positive `candidates` and a `target`, return all unique combinations summing to `target`. **Each candidate may be reused unlimited times.** `candidates ≤ 30`, values ≤ 40.

**Approach.** Backtrack with a start index; because reuse is allowed, recurse with `i` (not `i+1`) to keep using the current candidate, but never go backwards (avoids permutation duplicates). **Sort + prune**: once `candidate > remaining`, all later candidates are larger too, so `break`.

```java
import java.util.*;

public class CombinationSum {
    public List<List<Integer>> combinationSum(int[] candidates, int target) {
        Arrays.sort(candidates);                       // enables the break-prune
        List<List<Integer>> res = new ArrayList<>();
        backtrack(candidates, 0, target, new ArrayList<>(), res);
        return res;
    }
    private void backtrack(int[] c, int start, int remaining,
                           List<Integer> path, List<List<Integer>> res) {
        if (remaining == 0) {                          // exact hit
            res.add(new ArrayList<>(path));
            return;
        }
        for (int i = start; i < c.length; i++) {
            if (c[i] > remaining) break;               // PRUNE (sorted): too big, stop
            path.add(c[i]);
            backtrack(c, i, remaining - c[i], path, res); // i (not i+1) → reuse allowed
            path.remove(path.size() - 1);
        }
    }
}
```

**Dry run** `candidates=[2,3,6,7], target=7`: 2→2→2 (rem 1, then 2>1 break) backtrack; 2,2,3=7 ✓; 2,3,... ; 7=7 ✓. Result `[[2,2,3],[7]]`. ✓

**Time:** O(2^(target/min)) worst. **Space:** O(target/min) depth.

**Follow-ups.** **Combination Sum II** (each used once, with duplicates) → recurse `i+1` and skip dup `if(i>start && c[i]==c[i-1]) continue;`. **Combination Sum III** (k numbers from 1–9). **Coin Change** (count *minimum* coins) → switch to DP since only the count matters and sub-problems overlap.

---

### Problem 6: Generate Parentheses — LeetCode 22

**Statement.** Given `n` pairs, generate all well-formed parenthesis strings. `1 ≤ n ≤ 8`. There are the Catalan number `Cₙ` of them.

**Approach.** Brute force = generate all 2^(2n) strings and validate — wasteful. **Optimal**: build character by character, tracking open/close counts. Prune with two invariants that *guarantee* validity, so we never generate a malformed string: you may add `'('` while `open < n`, and `')'` only while `close < open`.

```java
import java.util.*;

public class GenerateParentheses {
    public List<String> generateParenthesis(int n) {
        List<String> res = new ArrayList<>();
        backtrack(new StringBuilder(), 0, 0, n, res);
        return res;
    }
    private void backtrack(StringBuilder sb, int open, int close,
                           int n, List<String> res) {
        if (sb.length() == 2 * n) {            // used all 2n slots → complete & valid
            res.add(sb.toString());
            return;
        }
        if (open < n) {                        // can still open
            sb.append('(');
            backtrack(sb, open + 1, close, n, res);
            sb.deleteCharAt(sb.length() - 1);  // backtrack
        }
        if (close < open) {                    // can close only if an unmatched '(' exists
            sb.append(')');
            backtrack(sb, open, close + 1, n, res);
            sb.deleteCharAt(sb.length() - 1);
        }
    }
}
```

**Dry run** `n=2`: `(` → `((` → `(()` → `(())` ✓ ; `(` → `()` → `()(` → `()()` ✓. Output `["(())","()()"]`. ✓

**Time:** O(4ⁿ/√n) (Catalan). **Space:** O(n) stack.

**Follow-ups.** Count only → Catalan formula `C(2n,n)/(n+1)`, no enumeration. Multiple bracket types `()[]{}`. k-th valid sequence in lexicographic order.

---

### Problem 7: Palindrome Partitioning — LeetCode 131

**Statement.** Partition string `s` (length ≤ 16) so that every substring is a palindrome; return all such partitions.

**Approach.** Backtrack over **cut positions**: from `start`, try every end `i`; if `s[start..i]` is a palindrome, fix that piece and recurse from `i+1`. The palindrome check is the pruning constraint. Precomputing an `isPal[i][j]` DP table makes each check O(1) (overall O(n²) table + O(n·2ⁿ) enumeration).

```java
import java.util.*;

public class PalindromePartition {
    public List<List<String>> partition(String s) {
        int n = s.length();
        boolean[][] pal = new boolean[n][n];           // pal[i][j] = s[i..j] palindrome
        for (int j = 0; j < n; j++)
            for (int i = j; i >= 0; i--)
                pal[i][j] = s.charAt(i) == s.charAt(j) && (j - i < 2 || pal[i+1][j-1]);

        List<List<String>> res = new ArrayList<>();
        backtrack(s, 0, pal, new ArrayList<>(), res);
        return res;
    }
    private void backtrack(String s, int start, boolean[][] pal,
                           List<String> path, List<List<String>> res) {
        if (start == s.length()) {                     // consumed whole string
            res.add(new ArrayList<>(path));
            return;
        }
        for (int i = start; i < s.length(); i++) {
            if (!pal[start][i]) continue;              // PRUNE: skip non-palindromic cut
            path.add(s.substring(start, i + 1));       // choose this palindrome piece
            backtrack(s, i + 1, pal, path, res);       // explore the rest
            path.remove(path.size() - 1);              // backtrack
        }
    }
}
```

**Dry run** `"aab"`: start 0 — "a" palindrome → recurse start1: "a"→ start2 "b" → done `["a","a","b"]`; "ab" not palindrome skip. Back at start0: "aa" palindrome → start2 "b" → `["aa","b"]`; "aab" not palindrome. Output `[["a","a","b"],["aa","b"]]`. ✓

**Time:** O(n·2ⁿ). **Space:** O(n²) table + O(n) stack.

**Follow-ups.** **Palindrome Partitioning II** (minimum cuts) → DP, not enumeration. Return only the count. Longest palindromic-substring connection.

---

### Problem 8: Word Search — LeetCode 79

**Statement.** Given an `m×n` board of chars and a `word`, return `true` if the word exists via a path of horizontally/vertically adjacent cells, each cell used at most once. `m,n ≤ 6`, word ≤ 15.

**Approach.** From each cell, DFS matching `word[k]`. Mark a visited cell in place (set to a sentinel like `'#'`), recurse in 4 directions, then **restore** it on the way out (backtracking the grid). Short-circuit (`return true`) the instant a full match is found — no need to explore further.

```java
public class WordSearch {
    public boolean exist(char[][] board, String word) {
        int m = board.length, n = board[0].length;
        for (int r = 0; r < m; r++)
            for (int c = 0; c < n; c++)
                if (dfs(board, word, 0, r, c)) return true;
        return false;
    }
    private boolean dfs(char[][] b, String w, int k, int r, int c) {
        if (k == w.length()) return true;                    // matched all chars
        if (r < 0 || c < 0 || r >= b.length || c >= b[0].length
                || b[r][c] != w.charAt(k)) return false;     // out of bounds / mismatch → prune
        char tmp = b[r][c];
        b[r][c] = '#';                                       // mark visited (choose)
        boolean found = dfs(b, w, k+1, r+1, c) || dfs(b, w, k+1, r-1, c)
                     || dfs(b, w, k+1, r, c+1) || dfs(b, w, k+1, r, c-1);
        b[r][c] = tmp;                                       // restore (un-choose)
        return found;
    }
}
```

**Dry run** `board=[[A,B],[C,D]]`, `word="ABD"`: start (0,0)=A match; mark; go right (0,1)=B match k=2; from B try neighbors → (1,1)=D match k=3 → return true. ✓

**Time:** O(m·n·4^L) where L = word length. **Space:** O(L) stack.

**Follow-ups.** **Word Search II** (many words) → build a **Trie** of words and DFS once, pruning by Trie nodes — the senior upgrade. Count distinct paths. Diagonal moves allowed.

---

### Problem 9: Rat in a Maze (all paths) — classic backtracking

**Statement.** An `n×n` grid `maze` where `1` = open, `0` = blocked. The rat starts at `(0,0)`, must reach `(n-1,n-1)`, moving Down/Left/Right/Up. Return all paths as strings of moves (`D,L,R,U`), in lexicographic order, without revisiting a cell.

**Approach.** DFS from the source, maintaining a `visited` matrix. Try directions in sorted order `D,L,R,U` so the output is lexicographic. **Prune** invalid moves (out of bounds, blocked, already visited) before recursing. Mark on entry, append the move, recurse; on return pop the move and unmark — textbook grid backtracking.

```java
import java.util.*;

public class RatInMaze {
    private static final int[] dr = { 1, 0, 0, -1 };   // D, L, R, U
    private static final int[] dc = { 0, -1, 1, 0 };
    private static final char[] dirCh = { 'D', 'L', 'R', 'U' };

    public List<String> findPaths(int[][] maze) {
        List<String> res = new ArrayList<>();
        int n = maze.length;
        if (n == 0 || maze[0][0] == 0 || maze[n-1][n-1] == 0) return res;
        boolean[][] visited = new boolean[n][n];
        dfs(maze, 0, 0, visited, new StringBuilder(), res);
        return res;
    }
    private void dfs(int[][] m, int r, int c, boolean[][] vis,
                     StringBuilder path, List<String> res) {
        int n = m.length;
        if (r == n - 1 && c == n - 1) {                // reached destination
            res.add(path.toString());
            return;
        }
        vis[r][c] = true;                              // choose: enter cell
        for (int d = 0; d < 4; d++) {                  // D,L,R,U → lexicographic
            int nr = r + dr[d], nc = c + dc[d];
            if (nr >= 0 && nr < n && nc >= 0 && nc < n
                    && !vis[nr][nc] && m[nr][nc] == 1) {   // PRUNE invalid moves
                path.append(dirCh[d]);
                dfs(m, nr, nc, vis, path, res);
                path.deleteCharAt(path.length() - 1);  // backtrack the move
            }
        }
        vis[r][c] = false;                             // un-choose: leave cell
    }
}
```

**Dry run** 2×2 all-open maze: from (0,0) D→(1,0) then R→(1,1) gives "DR"; backtrack, R→(0,1) then D→(1,1) gives "RD". Output `["DR","RD"]`. ✓

**Time:** O(4^(n²)) worst (heavily pruned in practice). **Space:** O(n²) visited + O(n²) recursion depth.

**Follow-ups.** Shortest path (BFS instead). Count paths only (DP if no revisit constraint along a DAG of moves). Allow only Down/Right (becomes a clean DP grid). Diagonal moves.

---

### Problem 10: N-Queens — LeetCode 51 (hard / senior)

**Statement.** Place `n` queens on an `n×n` board so none attack another (no shared row, column, or diagonal). Return all distinct board configurations. `1 ≤ n ≤ 9`.

**Approach.** Place exactly one queen per **row**, recursing row by row — this kills the "same row" conflict by construction. For each row, try every column that is not under attack. The senior trick is **O(1) constraint checks using three boolean/bitmask sets**: a `cols` set, a "↘ diagonal" set keyed by `row+col`, and a "↙ anti-diagonal" set keyed by `row-col+n-1`. This prunes the n! search to a few thousand nodes even at n=9. Bitmasks make it the fastest known approach.

```java
import java.util.*;

public class NQueens {
    public List<List<String>> solveNQueens(int n) {
        List<List<String>> res = new ArrayList<>();
        int[] queenCol = new int[n];                 // queenCol[r] = column of queen in row r
        boolean[] cols = new boolean[n];
        boolean[] diag = new boolean[2 * n - 1];     // r + c
        boolean[] anti = new boolean[2 * n - 1];     // r - c + n - 1
        backtrack(0, n, queenCol, cols, diag, anti, res);
        return res;
    }
    private void backtrack(int row, int n, int[] qc, boolean[] cols,
                           boolean[] diag, boolean[] anti, List<List<String>> res) {
        if (row == n) {                              // all rows placed → a solution
            res.add(build(qc, n));
            return;
        }
        for (int col = 0; col < n; col++) {
            int d = row + col, a = row - col + n - 1;
            if (cols[col] || diag[d] || anti[a]) continue;   // PRUNE: under attack
            cols[col] = diag[d] = anti[a] = true;            // choose
            qc[row] = col;
            backtrack(row + 1, n, qc, cols, diag, anti, res);// explore next row
            cols[col] = diag[d] = anti[a] = false;           // un-choose
        }
    }
    private List<String> build(int[] qc, int n) {
        List<String> board = new ArrayList<>();
        for (int r = 0; r < n; r++) {
            char[] line = new char[n];
            Arrays.fill(line, '.');
            line[qc[r]] = 'Q';
            board.add(new String(line));
        }
        return board;
    }
}
```

**Dry run** `n=4`: row0 col1 placed; row1 col3 (col0/2 blocked by diagonals); row2 has no safe col → backtrack; eventually row0=1,row1=3,row2=0,row3=2 works (`.Q..` / `...Q` / `Q...` / `..Q.`), and its mirror. Two solutions. ✓

**Time:** O(n!) upper bound, vastly reduced by pruning. **Space:** O(n) for the sets + recursion.

**Follow-ups.** **N-Queens II** (count only → drop board building, use a single bitmask int for speed; e.g. `available = ((1<<n)-1) & ~(cols|diag|anti)`, iterate with `p = avail & -avail`). Distinct solutions up to symmetry. Generalize to "no three in a line."

---

### Problem 11: Sudoku Solver — LeetCode 37 (hard / senior)

**Statement.** Fill a partially filled 9×9 board (`'.'` = empty) so each row, column, and 3×3 box contains digits 1–9 exactly once. A unique solution is guaranteed; mutate the board in place.

**Approach.** Backtracking with **constraint propagation**. Maintain bitmask sets for each row, column, and box so candidacy checks are O(1). Find the next empty cell, try each *legal* digit, recurse, and undo on failure. The senior optimization is the **most-constrained-variable (MRV) heuristic**: always fill the empty cell with the *fewest* legal candidates first — this collapses the search tree dramatically.

```java
public class SudokuSolver {
    private int[] rows = new int[9], colsM = new int[9], boxes = new int[9];

    public void solveSudoku(char[][] board) {
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                if (board[r][c] != '.') place(board, r, c, board[r][c] - '0');
        solve(board);
    }

    private boolean solve(char[][] b) {
        int br = -1, bc = -1, bestCount = 10, bestMask = 0;
        for (int r = 0; r < 9; r++)                       // MRV: pick fewest-candidate cell
            for (int c = 0; c < 9; c++)
                if (b[r][c] == '.') {
                    int used = rows[r] | colsM[c] | boxes[box(r, c)];
                    int avail = ~used & 0x1FF;            // 9 candidate bits
                    int cnt = Integer.bitCount(avail);
                    if (cnt == 0) return false;           // dead end → prune
                    if (cnt < bestCount) { bestCount = cnt; br = r; bc = c; bestMask = avail; }
                }
        if (br == -1) return true;                        // no empty cell → solved

        for (int m = bestMask; m != 0; m &= (m - 1)) {    // iterate candidate bits
            int bit = m & (-m);
            int d = Integer.numberOfTrailingZeros(bit) + 1;
            b[br][bc] = (char) ('0' + d);
            place(b, br, bc, d);                          // choose
            if (solve(b)) return true;                    // explore
            unplace(b, br, bc, d);                        // un-choose
            b[br][bc] = '.';
        }
        return false;
    }

    private int box(int r, int c) { return (r / 3) * 3 + c / 3; }
    private void place(char[][] b, int r, int c, int d) {
        int bit = 1 << (d - 1);
        rows[r] |= bit; colsM[c] |= bit; boxes[box(r, c)] |= bit;
    }
    private void unplace(char[][] b, int r, int c, int d) {
        int bit = 1 << (d - 1);
        rows[r] &= ~bit; colsM[c] &= ~bit; boxes[box(r, c)] &= ~bit;
    }
}
```

**Dry run (sketch).** The solver scans for the empty cell with the smallest candidate set (say a cell that can only be `4`), forced-fills it, which shrinks neighbors' candidate sets, cascading forced placements; only genuine branch points cause recursion + backtrack. With MRV + bitmasks a standard puzzle solves in microseconds.

**Time:** O(9^(empty cells)) worst case; near-linear with MRV pruning. **Space:** O(1) extra (board mutated in place) plus recursion depth ≤ 81.

**Follow-ups.** Count all solutions (drop the early `return true`). Validate an already-filled board (LeetCode 36). Generate puzzles with a unique solution. Dancing Links (Algorithm X) — the exact-cover formulation that solves Sudoku as a constraint matrix.

---

## Interview Q&A by Level

### 🟢 Basic

**Q: What two parts must every recursive function have?**
A base case (terminates without recursing) and a recursive case that calls itself on a *strictly smaller* sub-problem so it converges toward the base case.

**Q: What causes a `StackOverflowError`?**
Recursion that never reaches a base case (missing/wrong base, or the sub-problem doesn't shrink), or a depth deeper than the JVM stack (~10⁴–10⁵ frames). Each call consumes a stack frame.

**Q: What is the "choose / explore / un-choose" template?**
The backtracking loop body: make a choice (mutate shared state), recurse to extend it, then undo the choice so the state is clean for the next candidate.

**Q: Why do we add `new ArrayList<>(path)` instead of `path` to the results?**
`path` is a single mutable object reused across the whole search; adding the reference would store an object that keeps changing. You must snapshot a copy.

**Q: How does recursion relate to a stack data structure?**
The call stack *is* a stack: each call pushes a frame, each return pops one. Any recursion can be rewritten iteratively with an explicit stack.

### 🟡 Intermediate

**Q: How do you compute the time complexity of a backtracking solution?**
Number of nodes in the recursion tree × work per node. For subsets that's 2ⁿ nodes × O(n) copy = O(n·2ⁿ); permutations n! × O(n); the output size usually dominates.

**Q: How do you avoid duplicate combinations vs duplicate permutations?**
Combinations: use a **start index** so you only move forward. Permutations: use a `used[]`/swap so each element appears once per arrangement. For input *with duplicates*, sort and `if (i>start && a[i]==a[i-1]) continue;` (combinations) or the `!used[i-1]` variant (permutations).

**Q: What is pruning and why does it matter?**
Cutting a branch the moment the partial solution is provably infeasible (or can't beat the best). It doesn't change the answer but can turn an intractable tree into a fast search — N-Queens goes from n! to a few thousand nodes.

**Q: When should you prefer DP over backtracking?**
When you only need a count/optimum/boolean (not the enumeration) *and* sub-problems overlap. Memoizing the recursion collapses exponential work to polynomial (e.g., naive Fibonacci O(φⁿ) → O(n)).

**Q: Difference between backtracking and plain DFS?**
DFS is the traversal order; backtracking is DFS over a *state-space tree* that explicitly undoes choices and prunes infeasible branches. All backtracking is DFS, not all DFS is backtracking.

### 🟠 Advanced

**Q: What is tail recursion, and does Java optimize it?**
A call is tail-recursive if the recursive call is the *last* operation (nothing happens to its result). Functional languages reuse the frame (tail-call optimization) for O(1) stack. **The JVM does not perform TCO** (it would break stack-trace/security semantics), so deep tail recursion still overflows — convert to a loop manually.

**Q: How do you convert recursion to iteration?**
For tail recursion → a simple loop updating an accumulator. For general recursion → simulate the call stack with an explicit `Deque`, pushing "continuation" state. Tree traversals, for example, become an explicit-stack DFS or Morris traversal (O(1) space via threading).

**Q: Explain the Master Theorem intuition for divide-and-conquer.**
For `T(n) = a·T(n/b) + f(n)`, compare `f(n)` with `n^(log_b a)`. If the recursion work dominates → O(n^(log_b a)); if balanced → O(n^(log_b a)·log n) (e.g., merge sort: a=2,b=2 → O(n log n)); if the combine step dominates → O(f(n)).

**Q: What heuristics shrink a constraint-satisfaction search?**
MRV (most-constrained variable — fill the cell with fewest options first), LCV (least-constraining value), forward checking / constraint propagation, and symmetry breaking. Sudoku and N-Queens both benefit from MRV + bitmask domains.

**Q: Why are bitmasks used in N-Queens and Sudoku?**
A set of "used columns/diagonals/digits" fits in an `int`; membership/add/remove are single bitwise ops, and `avail & -avail` extracts the lowest candidate, making per-node work O(1) and the inner loop branch-free-ish — orders of magnitude faster than array scans.

### 🔴 Expert

**Q: How would you parallelize a large backtracking search?**
Split the top few levels of the state-space tree into independent sub-trees and farm them to a fork/join pool or worker threads (root-splitting). Each task explores its subtree with its own mutable copy; merge results. Work-stealing (e.g., `ForkJoinPool`) balances uneven subtrees. Beware shared-state contention — clone the board/used-set per task.

**Q: What is iterative deepening and when do you use it?**
IDDFS runs DFS with an increasing depth limit (1,2,3,…). It gets BFS's optimal-shallow-solution property with DFS's O(depth) memory — used when the tree is huge/infinite and the solution is shallow (puzzle solvers, IDA* for the 15-puzzle).

**Q: How does Dancing Links (Algorithm X) relate to backtracking?**
It models exact-cover problems (Sudoku, N-Queens, pentomino tiling) as a sparse 0/1 matrix and uses a doubly-linked structure where covering/uncovering columns is O(1) and *self-restoring* — backtracking becomes pointer relinking. Far faster than naive constraint backtracking on exact-cover problems.

**Q: How do you bound memory when the output is exponential?**
Stream results instead of materializing them (emit each solution to a consumer/`Iterator` and discard), or only keep aggregates (count, best). The recursion stack stays O(depth); you never hold all 2ⁿ/n! results at once.

**Q: Real-world systems built on recursion/backtracking?**
Regex engines (NFA backtracking), SAT/CSP solvers, type inference & parsers (recursive descent), query planners exploring join orders, dependency resolvers (package managers), game AI (minimax with alpha-beta pruning — backtracking with bounding), and route/layout/scheduling optimizers.

---

## ⚠️ Common Pitfalls

- **Forgetting to copy the path** — `res.add(path)` stores a reference that keeps mutating; use `new ArrayList<>(path)`.
- **Not restoring state after recursion** — every "choose" must have a matching "un-choose"; a missed `path.remove(...)`, `used[i]=false`, or grid `restore` corrupts sibling branches.
- **Missing / unreachable base case** → infinite recursion → `StackOverflowError`.
- **Sub-problem doesn't shrink** — recursing with the same `start` index (when reuse isn't intended) or not advancing in a grid loops forever.
- **Duplicate results** — using `i` instead of `i+1` when reuse is disallowed, or forgetting the sort + skip-duplicate guard for inputs with repeats.
- **Wrong recursion-with-reuse index** — Combination Sum recurses `i` (reuse) vs Combination Sum II recurses `i+1` (each once); mixing them is a classic bug.
- **Assuming Java optimizes tail calls** — it does not; deep recursion needs an explicit loop/stack.
- **Pruning that's too aggressive** — cutting a branch that *could* yield a valid answer silently drops solutions; prune only on provable infeasibility.
- **Shared mutable state across threads** when parallelizing — clone per task.
- **Using backtracking where DP fits** — enumerating overlapping sub-problems blows up exponentially when a memoized count would be polynomial.

---

## 📚 Further Reading

- **Cormen, Leiserson, Rivest, Stein — *Introduction to Algorithms (CLRS)*** — recurrences, the Master Theorem, divide-and-conquer.
- **Sedgewick & Wayne — *Algorithms, 4th ed.*** — recursion, backtracking, and the standard problem set.
- **Skiena — *The Algorithm Design Manual*** — an excellent backtracking chapter with a reusable template and pruning advice.
- **Knuth — *TAOCP Vol. 4, "Dancing Links" (Algorithm X)*** — the exact-cover technique behind fast Sudoku/tiling solvers.
- **LeetCode "Backtracking" tag** & the *Explore → Recursion II* card — graded practice (Subsets, Permutations, Combination Sum, N-Queens, Sudoku, Word Search II).
- **MIT 6.006 / 6.046** lectures on recursion, divide-and-conquer, and search.
- Related guides in this repo: [Stacks & Queues](stacks-queues.md) (explicit-stack recursion), [Complexity Analysis](complexity-analysis.md) (recurrences), and the trees/graphs guides (DFS is recursion).
