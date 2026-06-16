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

## 🧩 Extended Problems — Set 1: Classic Easy → Medium

### Problem 12: Letter Combinations of a Phone Number — LeetCode 17

**Statement.** Given a string `digits` (`2`–`9`), return all letter combinations the number could spell on a classic phone keypad. `0 ≤ digits.length ≤ 4`; `'0'` and `'1'` do not appear.

**Approach.** A small Cartesian product realized as backtracking. Map each digit to its letters, then recurse one digit at a time: at depth `k`, append each letter of `digits[k]`'s string, recurse to depth `k+1`, and pop on the way back. The empty-input case must return an empty list (not a list containing `""`), which the early guard handles.

```
       keypad for "23"
        2 → a b c        3 → d e f
              "23"
        a /    | b    \ c
      ad ae af  bd be bf  cd ce cf      ← 3 × 3 = 9 leaves
```

```java
import java.util.*;

public class LetterCombinations {
    private static final String[] MAP = {
        "", "", "abc", "def", "ghi", "jkl",
        "mno", "pqrs", "tuv", "wxyz"
    };

    public List<String> letterCombinations(String digits) {
        List<String> res = new ArrayList<>();
        if (digits == null || digits.isEmpty()) return res;   // edge: "" → []
        backtrack(digits, 0, new StringBuilder(), res);
        return res;
    }

    private void backtrack(String digits, int idx, StringBuilder sb, List<String> res) {
        if (idx == digits.length()) {              // chose a letter for every digit
            res.add(sb.toString());
            return;
        }
        String letters = MAP[digits.charAt(idx) - '0'];
        for (int i = 0; i < letters.length(); i++) {
            sb.append(letters.charAt(i));          // choose
            backtrack(digits, idx + 1, sb, res);   // explore next digit
            sb.deleteCharAt(sb.length() - 1);      // un-choose
        }
    }
}
```

**Dry run** `"23"`: digit '2' → a,b,c; under 'a', digit '3' → ad,ae,af; similarly for b,c → 9 strings. ✓

**Complexity.** **Time** O(4ⁿ·n) where n = number of digits (≤4 letters each, O(n) to build each string). **Space** O(n) recursion + builder. **Edge cases:** empty input → `[]`; single digit → that digit's letters.

---

### Problem 13: Subsets II (with duplicates) — LeetCode 90

**Statement.** Given an integer array `nums` that **may contain duplicates**, return all possible subsets (the power set) **without duplicate subsets**, in any order. `1 ≤ nums.length ≤ 10`.

**Approach.** Sort first so equal values are adjacent. Use the start-index subset template, but at each level **skip a duplicate that is not the first choice at this level**: `if (i > start && nums[i] == nums[i-1]) continue;`. This guarantees that among equal elements we only ever extend with the leftmost run, so `[1,2]` is generated once even when `2` repeats.

```java
import java.util.*;

public class SubsetsII {
    public List<List<Integer>> subsetsWithDup(int[] nums) {
        Arrays.sort(nums);                          // group duplicates
        List<List<Integer>> res = new ArrayList<>();
        backtrack(nums, 0, new ArrayList<>(), res);
        return res;
    }

    private void backtrack(int[] nums, int start,
                           List<Integer> path, List<List<Integer>> res) {
        res.add(new ArrayList<>(path));             // every node is a subset
        for (int i = start; i < nums.length; i++) {
            if (i > start && nums[i] == nums[i - 1]) continue; // skip dup at this level
            path.add(nums[i]);                       // choose
            backtrack(nums, i + 1, path, res);       // explore (i+1: each used once)
            path.remove(path.size() - 1);            // un-choose
        }
    }
}
```

**Dry run** `[1,2,2]`: add `[]`; i=0 → `[1]` → i=1 `[1,2]` → i=2 `[1,2,2]`; back to `[1]`, i=2 skipped (dup, i>start); back to `[]`, i=1 → `[2]` → `[2,2]`; i=2 skipped. Result `[],[1],[1,2],[1,2,2],[2],[2,2]`. ✓

**Complexity.** **Time** O(n·2ⁿ). **Space** O(n) stack + path. **Edge cases:** all-equal array `[2,2,2]` → only `[],[2],[2,2],[2,2,2]`; the `i > start` guard (not `i > 0`) is essential so the first element of each level is kept.

---

### Problem 14: Combination Sum II — LeetCode 40

**Statement.** Given `candidates` (which **may contain duplicates**) and a `target`, return all unique combinations summing to `target`. **Each number may be used at most once.** `1 ≤ candidates.length ≤ 100`.

**Approach.** Sort, then backtrack recursing with `i + 1` (each element consumed once). Two prunings: the **sorted break** `if (c[i] > remaining) break;`, and the **same-level duplicate skip** `if (i > start && c[i] == c[i-1]) continue;` so that two equal candidates don't generate the same combination. Note the skip is keyed on the *level start*, not globally — using a duplicate *deeper* in the path is legitimate (e.g. `[1,1,6]`).

```java
import java.util.*;

public class CombinationSumII {
    public List<List<Integer>> combinationSum2(int[] candidates, int target) {
        Arrays.sort(candidates);
        List<List<Integer>> res = new ArrayList<>();
        backtrack(candidates, 0, target, new ArrayList<>(), res);
        return res;
    }

    private void backtrack(int[] c, int start, int remaining,
                           List<Integer> path, List<List<Integer>> res) {
        if (remaining == 0) {
            res.add(new ArrayList<>(path));
            return;
        }
        for (int i = start; i < c.length; i++) {
            if (c[i] > remaining) break;                       // PRUNE: sorted, too big
            if (i > start && c[i] == c[i - 1]) continue;       // skip dup at this level
            path.add(c[i]);
            backtrack(c, i + 1, remaining - c[i], path, res);  // i+1: used once
            path.remove(path.size() - 1);
        }
    }
}
```

**Dry run** `candidates=[10,1,2,7,6,1,5], target=8` → sorted `[1,1,2,5,6,7,10]`: yields `[1,1,6]`, `[1,2,5]`, `[1,7]`, `[2,6]`. The second `1` at the top level is skipped (so `[1,7]` is not produced twice), but `[1,1,6]` still uses both 1s because the deeper `1` is at `i == start`. ✓

**Complexity.** **Time** O(2ⁿ) worst, n = candidates length. **Space** O(n) depth. **Edge cases:** target smaller than every candidate → `[]`; many duplicates handled by the level-skip; no combination found → empty list.

---

### Problem 15: Combination Sum III — LeetCode 216

**Statement.** Find all combinations of `k` distinct numbers from `1..9` that sum to `n`. Each number is used at most once. Return the unique combinations. `1 ≤ k ≤ 9`, `1 ≤ n ≤ 60`.

**Approach.** Backtrack over the digits `1..9` with a start index (increasing → no permutation dups). Carry both the remaining slots (`k - path.size()`) and the remaining sum. Prune aggressively: stop when `remaining sum < 0`, and break the loop once a digit exceeds the remaining sum. A complete combination requires both `path.size() == k` **and** `remaining == 0`.

```java
import java.util.*;

public class CombinationSumIII {
    public List<List<Integer>> combinationSum3(int k, int n) {
        List<List<Integer>> res = new ArrayList<>();
        backtrack(1, k, n, new ArrayList<>(), res);
        return res;
    }

    private void backtrack(int start, int k, int remaining,
                           List<Integer> path, List<List<Integer>> res) {
        if (path.size() == k) {
            if (remaining == 0) res.add(new ArrayList<>(path));  // exactly k digits & sum hit
            return;
        }
        for (int d = start; d <= 9; d++) {
            if (d > remaining) break;                 // PRUNE: digit too large (sorted)
            path.add(d);
            backtrack(d + 1, k, remaining - d, path, res);
            path.remove(path.size() - 1);
        }
    }
}
```

**Dry run** `k=3, n=7`: `[1,2,4]` (sum 7) is the only one — `[1,2,3]` sums to 6 (then digits exhausted), `[1,3,...]` needs 3 → `[1,3,3]` illegal (distinct), so 4 closes it. Result `[[1,2,4]]`. ✓

**Complexity.** **Time** O(C(9,k)·k). **Space** O(k) depth. **Edge cases:** `n` exceeding `9+8+...` for k digits → `[]`; `n` too small likewise; the dual base-case check prevents partial-length matches.

---

### Problem 16: Permutations II (with duplicates) — LeetCode 47

**Statement.** Given a collection `nums` that **might contain duplicates**, return all **unique** permutations. `1 ≤ nums.length ≤ 8`.

**Approach.** Sort so equal values are adjacent, then use a `used[]` array. The duplicate-suppression rule: when picking `nums[i]`, skip it if it equals `nums[i-1]` **and the previous equal element has not been used in the current path** (`!used[i-1]`). This enforces a canonical left-to-right order among equal elements, so each multiset arrangement is emitted exactly once.

```java
import java.util.*;

public class PermutationsII {
    public List<List<Integer>> permuteUnique(int[] nums) {
        Arrays.sort(nums);
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
            if (used[i]) continue;
            // skip a duplicate whose identical predecessor is NOT yet placed in this path
            if (i > 0 && nums[i] == nums[i - 1] && !used[i - 1]) continue;
            used[i] = true; path.add(nums[i]);
            backtrack(nums, used, path, res);
            used[i] = false; path.remove(path.size() - 1);
        }
    }
}
```

**Dry run** `[1,1,2]`: first slot can be 1 (i=0) or 2 (i=2); i=1's `1` is skipped because `nums[0]` is unused. → `[1,1,2]`, `[1,2,1]`, `[2,1,1]`. Exactly 3 unique permutations. ✓

**Complexity.** **Time** O(n·n!) worst (fewer when duplicates collapse branches). **Space** O(n). **Edge cases:** all equal `[2,2,2]` → single permutation; the `!used[i-1]` condition (not `used[i-1]`) is the subtle correct direction.

---

### Problem 17: Fibonacci Number — recursion → memoization → bottom-up

**Statement.** Compute the `n`-th Fibonacci number, `F(0)=0, F(1)=1, F(n)=F(n-1)+F(n-2)`. `0 ≤ n ≤ 30` (or larger with `long`).

**Approach.** The naive double recursion is O(φⁿ) because it recomputes overlapping sub-problems. **Memoization** (top-down) caches each `F(i)` so every value is computed once → O(n) time, O(n) space. The **bottom-up** loop keeps only the last two values → O(n) time, O(1) space — the canonical "recursion has overlapping subproblems, switch to DP" lesson for this topic.

```
      naive tree for F(5) — note repeated nodes
                 F5
              /      \
            F4        F3
           /  \      /  \
         F3   F2   F2   F1     ← F3 computed twice, F2 three times …
```

```java
import java.util.*;

public class Fibonacci {
    // Top-down memoized recursion: each subproblem solved once.
    public long fibMemo(int n) {
        return go(n, new long[n + 1], new boolean[n + 1]);
    }
    private long go(int n, long[] memo, boolean[] done) {
        if (n < 2) return n;                 // F0=0, F1=1
        if (done[n]) return memo[n];         // cache hit → no recompute
        done[n] = true;
        return memo[n] = go(n - 1, memo, done) + go(n - 2, memo, done);
    }

    // Bottom-up, O(1) extra space.
    public long fib(int n) {
        if (n < 2) return n;
        long prev = 0, cur = 1;
        for (int i = 2; i <= n; i++) {
            long next = prev + cur;
            prev = cur;
            cur = next;
        }
        return cur;
    }
}
```

**Dry run** `fib(6)`: pairs (prev,cur) evolve 0,1 → 1,1 → 1,2 → 2,3 → 3,5 → 5,8 → return 8. ✓

**Complexity.** Naive **O(φⁿ)**; memoized/iterative **O(n)** time, O(n)/O(1) space. **Edge cases:** `n=0`→0, `n=1`→1; overflow past `F(92)` for `long` → use `BigInteger`.

---

### Problem 18: Pow(x, n) — fast exponentiation — LeetCode 50

**Statement.** Implement `pow(x, n)` (`x` a double, `n` an int) in O(log n). `-100 < x < 100`, `n` fits in a 32-bit signed int.

**Approach.** Exponentiation by squaring: `x^n = (x^(n/2))²`, multiplied by an extra `x` when `n` is odd. The interview trap is `n = Integer.MIN_VALUE`: negating it overflows. Promote `n` to a `long` before taking `-n`, or handle the sign as a `long`. Recurse on the halved exponent → O(log n) depth.

```java
public class PowXN {
    public double myPow(double x, int n) {
        long exp = n;                       // widen to avoid MIN_VALUE overflow on negate
        if (exp < 0) { x = 1 / x; exp = -exp; }
        return fastPow(x, exp);
    }

    private double fastPow(double x, long n) {
        if (n == 0) return 1.0;             // base case
        double half = fastPow(x, n / 2);    // one recursive call, exponent halved
        double sq = half * half;
        return (n % 2 == 1) ? sq * x : sq;  // odd → multiply one extra x
    }
}
```

**Dry run** `myPow(2.0, -3)`: x→0.5, exp→3; fastPow(0.5,3)=fastPow(0.5,1)²·0.5; fastPow(0.5,1)=fastPow(0.5,0)²·0.5=0.5; so 0.5²·0.5=0.125. ✓ (= 2⁻³)

**Complexity.** **Time** O(log n). **Space** O(log n) recursion stack. **Edge cases:** `n=0` → 1 for any x; `x=0, n>0` → 0; negative `n` with the `MIN_VALUE` overflow handled by the `long` widening.

---

### Problem 19: Reverse a Linked List (recursively) — LeetCode 206

**Statement.** Reverse a singly linked list and return the new head, using recursion.

**Approach.** Recurse to the tail; the deepest call returns the new head and bubbles it back unchanged up the stack. At each frame, make the next node point back to the current node (`head.next.next = head`) and sever `head.next` to avoid a cycle. The recursion depth equals the list length, so this is O(n) stack — a clean illustration of "do work on the way back up."

```
  1 → 2 → 3 → null
recurse to 3 (newHead). Unwinding:
  at 2: 2.next.next = 2  ⇒ 3 → 2 ; 2.next = null
  at 1: 1.next.next = 1  ⇒ 2 → 1 ; 1.next = null
  result: 3 → 2 → 1 → null
```

```java
public class ReverseListRecursive {
    static class ListNode {
        int val; ListNode next;
        ListNode(int v) { val = v; }
    }

    public ListNode reverseList(ListNode head) {
        if (head == null || head.next == null) return head;  // base: empty / last node
        ListNode newHead = reverseList(head.next);           // reverse the rest
        head.next.next = head;                               // make successor point back
        head.next = null;                                    // sever forward link
        return newHead;                                      // unchanged head of reversed list
    }
}
```

**Dry run** `1→2→3`: recursion bottoms at 3 (newHead=3); unwinding at 2 → `3→2`, at 1 → `2→1`; final `3→2→1→null`. ✓

**Complexity.** **Time** O(n). **Space** O(n) recursion stack. **Edge cases:** empty list (`null`) and single node both return immediately; an iterative version achieves O(1) space.

---

### Problem 20: Binary Tree Maximum Depth — LeetCode 104

**Statement.** Given the root of a binary tree, return its maximum depth — the number of nodes along the longest root-to-leaf path.

**Approach.** Pure structural recursion (post-order DFS): the depth of a node is `1 + max(depth(left), depth(right))`; an empty subtree has depth 0. Each node is visited once. This is the archetypal "answer defined in terms of sub-answers" recursion — no backtracking, no shared mutable state.

```java
public class MaxDepth {
    static class TreeNode {
        int val; TreeNode left, right;
        TreeNode(int v) { val = v; }
    }

    public int maxDepth(TreeNode root) {
        if (root == null) return 0;                              // base: empty subtree
        int left = maxDepth(root.left);
        int right = maxDepth(root.right);
        return 1 + Math.max(left, right);                        // combine
    }
}
```

**Dry run** tree `3 → (9, 20 → (15,7))`: depth(9)=1, depth(15)=depth(7)=1 → depth(20)=2 → depth(3)=1+max(1,2)=3. ✓

**Complexity.** **Time** O(n), every node once. **Space** O(h) recursion stack, h = tree height (O(n) worst for a skewed tree, O(log n) balanced). **Edge cases:** empty tree → 0; single node → 1; degenerate (linked-list-shaped) tree → depth n.

---

### Problem 21: Path Sum — root-to-leaf target — LeetCode 112

**Statement.** Given a binary tree and an integer `targetSum`, return `true` if there is a **root-to-leaf** path whose node values add up to `targetSum`. Values may be negative.

**Approach.** DFS that subtracts the current node's value from the remaining target as it descends. At a **leaf** (both children null), the path is valid iff the remaining target equals the leaf's value (i.e. `remaining - val == 0`). Short-circuit with `||` so we stop at the first qualifying path. The leaf check is essential — you may not stop at a `null` child of a one-armed node, or you'd accept partial paths.

```java
public class PathSum {
    static class TreeNode {
        int val; TreeNode left, right;
        TreeNode(int v) { val = v; }
    }

    public boolean hasPathSum(TreeNode root, int targetSum) {
        if (root == null) return false;                          // empty / fell off a branch
        if (root.left == null && root.right == null)             // leaf
            return targetSum == root.val;
        int rem = targetSum - root.val;
        return hasPathSum(root.left, rem) || hasPathSum(root.right, rem);
    }
}
```

**Dry run** path `5 → 4 → 11 → 2` with target 22: 22-5=17, 17-4=13, 13-11=2; leaf 2 → 2==2 true. ✓

**Complexity.** **Time** O(n). **Space** O(h) recursion stack. **Edge cases:** empty tree → always `false` (even if `targetSum==0`); negative values handled naturally; a single node equals target → `true`.

---

### Problem 22: Generalized Abbreviation — LeetCode 320

**Statement.** Given a word, return **all** generalized abbreviations: each maximal run of characters may be replaced by its count. For `"word"`, examples include `"word"`, `"1ord"`, `"w1rd"`, `"4"`, `"2rd"`, etc. `word.length ≤ 15`.

**Approach.** Per character, a binary choice: **keep** it (flushing any pending count first) or **abbreviate** it (increment a running count). At the end of the word, flush a trailing count. This produces all 2ⁿ abbreviations. Carrying the pending `count` as a parameter and flushing on a "keep" decision is the elegant way to merge consecutive abbreviated characters into one number.

```java
import java.util.*;

public class GeneralizedAbbreviation {
    public List<String> generateAbbreviations(String word) {
        List<String> res = new ArrayList<>();
        backtrack(word, 0, 0, new StringBuilder(), res);
        return res;
    }

    // count = run length of abbreviated chars pending a flush
    private void backtrack(String word, int idx, int count,
                           StringBuilder sb, List<String> res) {
        if (idx == word.length()) {
            int len = sb.length();
            if (count > 0) sb.append(count);     // flush trailing count
            res.add(sb.toString());
            sb.setLength(len);                   // restore (un-flush)
            return;
        }
        // choice 1: abbreviate word[idx] → grow the count, keep char out
        backtrack(word, idx + 1, count + 1, sb, res);

        // choice 2: keep word[idx] → flush pending count, then append the char
        int len = sb.length();
        if (count > 0) sb.append(count);
        sb.append(word.charAt(idx));
        backtrack(word, idx + 1, 0, sb, res);
        sb.setLength(len);                       // restore both appends
    }
}
```

**Dry run** `"ab"` (n=2 → 4 results): `ab`, `a1`, `1b`, `2`. The `"2"` comes from abbreviating both chars and flushing 2 at the end. ✓

**Complexity.** **Time** O(2ⁿ·n) (2ⁿ abbreviations, O(n) to materialize each). **Space** O(n) recursion + builder. **Edge cases:** empty word → `[""]`; single char → `["1","<char>"]`; the trailing flush is what produces all-digit forms like `"4"`.

---

### Problem 23: Decode Ways — count decodings — LeetCode 91

**Statement.** A message of digits is encoded with `A→1 … Z→26`. Return the **number** of ways to decode string `s`. Leading zeros are invalid (`"06"` is not `"6"`). `1 ≤ s.length ≤ 100`.

**Approach.** This is recursion with **overlapping subproblems** — count-only, so memoize rather than enumerate. From index `i`, you may consume one digit (if it is `1..9`) and/or two digits (if they form `10..26`); the count is the sum of the two sub-counts. Memoize on `i` to collapse the exponential branching to O(n). It is the canonical "looks like backtracking, but you only need a count → DP" problem.

```java
import java.util.*;

public class DecodeWays {
    public int numDecodings(String s) {
        int[] memo = new int[s.length()];
        Arrays.fill(memo, -1);                 // -1 = uncomputed
        return count(s, 0, memo);
    }

    private int count(String s, int i, int[] memo) {
        if (i == s.length()) return 1;         // consumed whole string → one valid decoding
        if (s.charAt(i) == '0') return 0;      // leading zero → dead branch
        if (memo[i] != -1) return memo[i];

        int ways = count(s, i + 1, memo);      // take one digit (1..9)
        if (i + 1 < s.length()) {              // try two digits
            int two = (s.charAt(i) - '0') * 10 + (s.charAt(i + 1) - '0');
            if (two <= 26) ways += count(s, i + 2, memo);
        }
        return memo[i] = ways;
    }
}
```

**Dry run** `"226"`: from 0 → take "2" then decode "26" (=> "2","6" or "26") = 2 ways; or take "22" then "6" = 1 way; total 3 → `"BZ","VF","BBF"`. ✓

**Complexity.** **Time** O(n) with memoization (each index solved once). **Space** O(n) memo + O(n) recursion. **Edge cases:** leading `'0'` → 0; embedded `"30"` (3 then leading 0) → 0; whole string of valid digits returns ≥ 1.

---

### Problem 24: Restore IP Addresses — LeetCode 93

**Statement.** Given a string `s` of digits, return all valid IPv4 addresses formed by inserting three dots. Each of the four parts is `0..255` with **no leading zeros** (except the single digit `"0"`). `1 ≤ s.length ≤ 20`.

**Approach.** Backtrack choosing the length (1–3 digits) of each of the four segments. Validity per segment: not empty, length ≤ 3, no leading zero unless it is exactly `"0"`, and numeric value ≤ 255. Prune hard: stop once you have 4 segments, and only accept when **both** four segments are placed **and** the whole string is consumed. Length-based pruning (remaining chars must fit in the remaining segments) keeps it tiny.

```java
import java.util.*;

public class RestoreIPAddresses {
    public List<String> restoreIpAddresses(String s) {
        List<String> res = new ArrayList<>();
        if (s.length() < 4 || s.length() > 12) return res;   // impossible lengths
        backtrack(s, 0, 0, new StringBuilder(), res);
        return res;
    }

    private void backtrack(String s, int start, int seg,
                           StringBuilder cur, List<String> res) {
        if (seg == 4) {
            if (start == s.length())                         // 4 parts AND all chars used
                res.add(cur.substring(0, cur.length() - 1)); // drop trailing '.'
            return;
        }
        for (int len = 1; len <= 3 && start + len <= s.length(); len++) {
            String part = s.substring(start, start + len);
            if (!isValid(part)) continue;                    // PRUNE invalid octet
            int mark = cur.length();
            cur.append(part).append('.');
            backtrack(s, start + len, seg + 1, cur, res);
            cur.setLength(mark);                             // backtrack the append
        }
    }

    private boolean isValid(String part) {
        if (part.length() > 1 && part.charAt(0) == '0') return false; // leading zero
        return Integer.parseInt(part) <= 255;                          // 0..255
    }
}
```

**Dry run** `"25525511135"`: a valid split is `255.255.11.135`; another is `255.255.111.35`. Segments like `256` or `01` are rejected by `isValid`. ✓

**Complexity.** **Time** O(1) in effect — the search tree is bounded (≤ 3⁴ = 81 leaves). **Space** O(1) extra besides output. **Edge cases:** length < 4 or > 12 → `[]`; `"0000"` → `["0.0.0.0"]`; leading-zero parts rejected; the dual base check (`seg==4` and `start==len`) prevents leftover digits.

---

## 🧩 Extended Problems — Set 2: Medium → Hard Variations & Follow-ups

### Problem 25: Subsets of Size K (k-Combinations of an array) — backtracking with pruning

**Statement.** Given a distinct-element array `nums` and an integer `k`, return **all** subsets of exactly size `k`. `0 ≤ k ≤ nums.length ≤ 16`.

**Approach.** A brute force generates the full power set (2ⁿ) and filters those whose size is `k` — correct but wasteful, touching 2ⁿ nodes. The **optimal** version is Combinations (Problem 3) lifted onto an arbitrary array: backtrack with a start index, and prune the loop bound so a branch is abandoned the moment too few elements remain to reach size `k`. With `need = k - path.size()`, the last usable start index is `n - need`, so `i` runs only while `i <= n - need`. This visits only C(n,k) leaves instead of 2ⁿ.

```
   choose-k tree for nums=[a,b,c,d], k=2  (start index advances, no revisits)
                       []
        a /      b |       c \   (d pruned: need=2, n-need=2)
        [a]      [b]       [c]
      b|c|d     c|d        d
   [a,b][a,c][a,d] [b,c][b,d] [c,d]      ← C(4,2)=6 leaves
```

```java
import java.util.*;

public class SubsetsOfSizeK {
    public List<List<Integer>> subsetsOfSizeK(int[] nums, int k) {
        List<List<Integer>> res = new ArrayList<>();
        if (k < 0 || k > nums.length) return res;        // impossible size
        backtrack(nums, 0, k, new ArrayList<>(), res);
        return res;
    }

    private void backtrack(int[] nums, int start, int k,
                           List<Integer> path, List<List<Integer>> res) {
        if (path.size() == k) {                          // exactly k chosen
            res.add(new ArrayList<>(path));
            return;
        }
        int need = k - path.size();
        // PRUNE: i can start at most at n - need and still leave enough elements
        for (int i = start; i <= nums.length - need; i++) {
            path.add(nums[i]);                           // choose
            backtrack(nums, i + 1, k, path, res);        // explore (i+1: no reuse)
            path.remove(path.size() - 1);                // un-choose
        }
    }
}
```

**Dry run** `nums=[5,6,7], k=2`: need starts at 2, bound = 3-2 = index 1. start 0 → [5] then 6,7 give [5,6],[5,7]; start 1 → [6,7]; start 2 pruned. Result `[[5,6],[5,7],[6,7]]`. ✓

**Complexity.** **Time** O(k·C(n,k)) (C(n,k) leaves, O(k) to copy each). **Space** O(k) recursion + path. **Edge cases:** `k=0` → `[[]]` (single empty subset); `k=n` → the whole array as one subset; `k<0` or `k>n` → empty list.

---

### Problem 26: Word Search II (many words via Trie) — LeetCode 212 (hard / senior)

**Statement.** Given an `m×n` board of letters and a list of `words`, return all words that can be formed by a path of horizontally/vertically adjacent cells (each cell used once per word). `m,n ≤ 12`, up to `3·10^4` words.

**Approach.** Running Word Search (Problem 8) once per word is `O(W·m·n·4^L)` — far too slow. The senior upgrade is to build a **Trie** of all words and DFS the board *once*, descending the Trie in lockstep with the path. A cell is only worth exploring if the current letter is a child of the current Trie node, so the entire dictionary is pruned simultaneously. Store the full word at terminal Trie nodes to collect hits without rebuilding strings. Two key optimizations: (1) set `node.word = null` after collecting to dedupe, and (2) prune dead Trie leaves to shrink the structure as words are found.

```
   Trie of {"oath","pea","eat","rain"} ; DFS board follows edges that exist
        root
      o   p   e   r
      a   e   a   a
      t   a   t   i
      h(•)    (•) n(•)
   At board cell 'o', only the 'o' edge is alive → all other words pruned at once.
```

```java
import java.util.*;

public class WordSearchII {
    static class TrieNode {
        TrieNode[] next = new TrieNode[26];
        String word;                                  // non-null only at a word end
    }

    public List<String> findWords(char[][] board, String[] words) {
        TrieNode root = buildTrie(words);
        List<String> res = new ArrayList<>();
        for (int r = 0; r < board.length; r++)
            for (int c = 0; c < board[0].length; c++)
                dfs(board, r, c, root, res);
        return res;
    }

    private TrieNode buildTrie(String[] words) {
        TrieNode root = new TrieNode();
        for (String w : words) {
            TrieNode cur = root;
            for (char ch : w.toCharArray()) {
                int i = ch - 'a';
                if (cur.next[i] == null) cur.next[i] = new TrieNode();
                cur = cur.next[i];
            }
            cur.word = w;
        }
        return root;
    }

    private void dfs(char[][] b, int r, int c, TrieNode node, List<String> res) {
        if (r < 0 || c < 0 || r >= b.length || c >= b[0].length) return;
        char ch = b[r][c];
        if (ch == '#' || node.next[ch - 'a'] == null) return;   // visited or no Trie edge → prune
        node = node.next[ch - 'a'];
        if (node.word != null) {                                // a word ends here
            res.add(node.word);
            node.word = null;                                   // dedupe: collect once
        }
        b[r][c] = '#';                                          // mark visited (choose)
        dfs(b, r + 1, c, node, res);
        dfs(b, r - 1, c, node, res);
        dfs(b, r, c + 1, node, res);
        dfs(b, r, c - 1, node, res);
        b[r][c] = ch;                                           // restore (un-choose)
    }
}
```

**Dry run** board containing `o,a,t,h` in an L-shape with words `{"oath","oat"}`: DFS from `o` descends Trie `o→a→t`, emits `"oat"` at `t`, then `→h` emits `"oath"`. Each found word's terminal `word` is nulled so a second path can't re-add it. ✓

**Complexity.** **Time** O(m·n·4^(L)) where L is the longest word length, but the Trie prunes the dictionary so it is independent of the *number* of words. Trie build O(total chars). **Space** O(total chars) Trie + O(L) recursion. **Edge cases:** empty word list → `[]`; duplicate words → reported once (nulling `word`); a single-cell board matching a length-1 word.

---

### Problem 27: Palindrome Partitioning II (minimum cuts) — LeetCode 132 (hard)

**Statement.** Given a string `s`, return the **minimum** number of cuts so every resulting substring is a palindrome. `1 ≤ s.length ≤ 2000`.

**Approach.** Set 1's enumeration (Problem 7) lists all partitions — exponential and pointless when we only need the *minimum count*. Since sub-problems overlap, this is a DP. Naively, plain recursion `minCut(i) = min over j of 1 + minCut(j+1)` for every palindromic prefix `s[i..j]` is O(2ⁿ); memoizing on `i` makes it O(n²). Precompute palindrome feasibility in an `isPal[i][j]` table (O(n²)), then `dp[i]` = min cuts for the suffix starting at `i`, filling right-to-left. The transition is the cleanest demonstration of "backtracking → memoized recursion → bottom-up DP" on this exact topic.

```java
import java.util.*;

public class PalindromePartitionII {
    public int minCut(String s) {
        int n = s.length();
        boolean[][] pal = new boolean[n][n];           // pal[i][j] = s[i..j] palindrome
        for (int j = 0; j < n; j++)
            for (int i = j; i >= 0; i--)
                pal[i][j] = s.charAt(i) == s.charAt(j) && (j - i < 2 || pal[i + 1][j - 1]);

        // dp[i] = min cuts needed for suffix s[i..n-1]; dp[n] = -1 (so a full palindrome → 0 cuts)
        int[] dp = new int[n + 1];
        dp[n] = -1;
        for (int i = n - 1; i >= 0; i--) {
            dp[i] = Integer.MAX_VALUE;
            for (int j = i; j < n; j++)
                if (pal[i][j])                          // s[i..j] palindrome → one piece
                    dp[i] = Math.min(dp[i], 1 + dp[j + 1]);
        }
        return dp[0];
    }
}
```

**Dry run** `"aab"`: `pal` marks `aa` and singletons. dp[3]=-1; dp[2] ("b") = 1+dp[3] = 0; dp[1] ("a","ab"→only "a") = 1+dp[2]=1; dp[0]: "a"→1+dp[1]=2, "aa"→1+dp[2]=1, "aab"✗ → min = 1. Answer **1** (`"aa" | "b"`). ✓

**Complexity.** **Time** O(n²) (palindrome table + DP). **Space** O(n²) for the table, O(n) for `dp`. **Edge cases:** whole string already a palindrome → 0 cuts (the `dp[n]=-1` seed handles this); single char → 0; all distinct chars → n-1 cuts.

---

### Problem 28: Combination Sum IV (count ordered combinations) — LeetCode 377

**Statement.** Given distinct positive integers `nums` and a `target`, return the **number** of combinations that sum to `target`, where **different orderings count as different** (so `[1,2]` and `[2,1]` are two). `1 ≤ nums.length ≤ 200`, `target ≤ 1000`.

**Approach.** Despite "combination" in the name, order matters → it is really counting *sequences*. Pure recursion `count(rem) = Σ count(rem - num)` over all `num ≤ rem` is exponential because the same remainder is reached many ways. Memoize on the remaining target (`O(target · n)`), or write the equivalent bottom-up DP `dp[t] = Σ dp[t - num]`. Because order matters, the loop over `nums` is *inside* the loop over the target — the reverse order would count unordered combinations (the Coin Change II distinction worth calling out in interviews).

```java
import java.util.*;

public class CombinationSumIV {
    // Top-down memoized recursion.
    public int combinationSum4(int[] nums, int target) {
        Integer[] memo = new Integer[target + 1];
        return count(nums, target, memo);
    }

    private int count(int[] nums, int rem, Integer[] memo) {
        if (rem == 0) return 1;                  // one full ordered combination
        if (memo[rem] != null) return memo[rem];
        int total = 0;
        for (int num : nums)
            if (num <= rem) total += count(nums, rem - num, memo);
        return memo[rem] = total;
    }

    // Equivalent bottom-up DP (order matters → target loop OUTSIDE, nums INSIDE).
    public int combinationSum4DP(int[] nums, int target) {
        int[] dp = new int[target + 1];
        dp[0] = 1;
        for (int t = 1; t <= target; t++)
            for (int num : nums)
                if (num <= t) dp[t] += dp[t - num];
        return dp[target];
    }
}
```

**Dry run** `nums=[1,2,3], target=4`: dp[0]=1; dp[1]=dp[0]=1; dp[2]=dp[1]+dp[0]=2; dp[3]=dp[2]+dp[1]+dp[0]=4; dp[4]=dp[3]+dp[2]+dp[1]=4+2+1=7. The 7 ordered combinations of 4. ✓

**Complexity.** **Time** O(target · n). **Space** O(target). **Edge cases:** `target=0` → 1 (the empty combination); no `num ≤ target` → 0; result can overflow `int` for large inputs (LeetCode guarantees it fits — otherwise use `long`).

---

### Problem 29: Word Break II (return all sentences) — LeetCode 140 (hard)

**Statement.** Given a string `s` and a dictionary `wordDict`, return **all** sentences (space-separated) where each word is in the dictionary. `1 ≤ s.length ≤ 20`, dictionary up to 1000 words.

**Approach.** Backtracking alone re-solves the same suffix repeatedly (e.g. `"catsanddog"` re-explores `"sanddog"` from multiple prefixes), so the worst case is exponential *and* wasteful. The professional version is **memoized backtracking**: a `Map<Integer, List<String>>` caches, for each start index, the list of all sentences buildable from that suffix. For each start, try every dictionary word that matches the prefix, recurse on the remainder, and prepend the word to each returned sub-sentence. Memoization makes overlapping suffix work happen once. (When only feasibility is needed, this collapses to the boolean Word Break I DP.)

```java
import java.util.*;

public class WordBreakII {
    public List<String> wordBreak(String s, List<String> wordDict) {
        Set<String> dict = new HashSet<>(wordDict);
        return dfs(s, 0, dict, new HashMap<>());
    }

    private List<String> dfs(String s, int start, Set<String> dict,
                             Map<Integer, List<String>> memo) {
        if (memo.containsKey(start)) return memo.get(start);
        List<String> res = new ArrayList<>();
        if (start == s.length()) {                       // reached the end
            res.add("");                                 // sentinel: empty tail sentence
            return res;
        }
        for (int end = start + 1; end <= s.length(); end++) {
            String word = s.substring(start, end);
            if (!dict.contains(word)) continue;          // PRUNE: not a dictionary word
            for (String sub : dfs(s, end, dict, memo)) { // all sentences for the remainder
                res.add(sub.isEmpty() ? word : word + " " + sub);
            }
        }
        memo.put(start, res);                            // cache this suffix's answers
        return res;
    }
}
```

**Dry run** `s="catsanddog"`, dict=`{cat,cats,and,sand,dog}`: from 0, "cat"→ suffix "sanddog" yields "sand dog"; "cats"→ suffix "anddog" yields "and dog". Result `["cat sand dog","cats and dog"]`. The suffix "dog" is computed once and reused. ✓

**Complexity.** **Time** O(n² · 2ⁿ) worst (the number of sentences can be exponential; memoization removes *recomputation*, not output size). **Space** O(n²) memo + recursion. **Edge cases:** unbreakable string → `[]`; whole string is one dictionary word → single sentence; the `""` sentinel cleanly handles the join at the tail.

---

### Problem 30: Partition to K Equal Sum Subsets — LeetCode 698 (hard)

**Statement.** Given an array `nums` and integer `k`, return `true` if `nums` can be partitioned into `k` non-empty subsets all with equal sum. `1 ≤ k ≤ nums.length ≤ 16`, values ≤ 10^4.

**Approach.** If `sum % k != 0` it is immediately impossible; the target per bucket is `sum / k`. Brute force tries every assignment of each element to a bucket (k^n). The optimized backtracking fills **one bucket at a time** to the target, then recurses to fill the next, which drastically narrows the tree. Three crucial prunes: (1) **sort descending** and place large items first so failures surface early; (2) skip an element if it would overflow the current bucket; (3) the symmetry-breaking trick — if adding `nums[i]` to an *empty* current bucket fails, the whole branch fails (no other arrangement of an empty bucket helps), so `break`. A `used[]` array marks consumed elements.

```java
import java.util.*;

public class PartitionKEqualSum {
    public boolean canPartitionKSubsets(int[] nums, int k) {
        int sum = 0;
        for (int x : nums) sum += x;
        if (sum % k != 0) return false;                  // not evenly divisible
        int target = sum / k;
        Arrays.sort(nums);                               // ascending; we iterate from the back
        if (nums[nums.length - 1] > target) return false;// an element exceeds a bucket
        return backtrack(nums, new boolean[nums.length], k, 0, nums.length - 1, target, target);
    }

    private boolean backtrack(int[] nums, boolean[] used, int k,
                              int filledBuckets, int start, int curRem, int target) {
        if (filledBuckets == k) return true;             // all buckets completed
        if (curRem == 0)                                 // current bucket full → start a new one
            return backtrack(nums, used, k, filledBuckets + 1, nums.length - 1, target, target);
        for (int i = start; i >= 0; i--) {
            if (used[i] || nums[i] > curRem) continue;   // taken or doesn't fit
            used[i] = true;                              // choose
            if (backtrack(nums, used, k, filledBuckets, i - 1, curRem - nums[i], target))
                return true;
            used[i] = false;                             // un-choose
            // symmetry-break: if this element couldn't start/finish a bucket, give up this branch
            if (curRem == target || curRem == nums[i]) break;
        }
        return false;
    }
}
```

**Dry run** `nums=[4,3,2,3,5,2,1], k=4`, sum=20, target=5: fill bucket1 with 5; bucket2 with 4+1; bucket3 with 3+2; bucket4 with 3+2 → all four reach 5 → `true`. ✓

**Complexity.** **Time** O(k · 2ⁿ) worst (each element in/out per bucket, bounded by the prunes). **Space** O(n) used + recursion. **Edge cases:** `sum % k != 0` → false; any element > target → false; `k=1` → always true (whole array); `k=n` → true iff all elements equal.

---

### Problem 31: Letter Case Permutation — LeetCode 784

**Statement.** Given a string `s` of letters and digits, return **all** strings obtainable by independently changing the case of each letter (digits unchanged). `1 ≤ s.length ≤ 12`.

**Approach.** A per-character branching: a digit forces a single choice (keep it); a letter branches into lowercase and uppercase. The recursion depth equals the string length, and the number of leaves is 2^(number of letters). Build the result with a `char[]` mutated in place — set the character, recurse, and (for letters) flip and recurse again. No explicit "undo" is needed because each branch overwrites the same index before recursing, leaving siblings unaffected.

```
   "a1b" → letters a,b branch; digit 1 fixed
                  a1b ............ (start)
           a /              \ A
        a1b                  A1b
      b /  \ B            b /   \ B
   a1b  a1B            A1b     A1B      ← 2^2 = 4 results
```

```java
import java.util.*;

public class LetterCasePermutation {
    public List<String> letterCasePermutation(String s) {
        List<String> res = new ArrayList<>();
        backtrack(s.toCharArray(), 0, res);
        return res;
    }

    private void backtrack(char[] arr, int idx, List<String> res) {
        if (idx == arr.length) {
            res.add(new String(arr));                    // snapshot
            return;
        }
        if (Character.isLetter(arr[idx])) {
            arr[idx] = Character.toLowerCase(arr[idx]);  // branch 1: lowercase
            backtrack(arr, idx + 1, res);
            arr[idx] = Character.toUpperCase(arr[idx]);  // branch 2: uppercase
            backtrack(arr, idx + 1, res);
        } else {
            backtrack(arr, idx + 1, res);                // digit: single path
        }
    }
}
```

**Dry run** `"a1"`: idx0 letter → 'a' recurse → idx1 digit '1' → idx2 add "a1"; flip to 'A' → add "A1". Result `["a1","A1"]`. ✓

**Complexity.** **Time** O(2^L · n) where L = number of letters, n = string length. **Space** O(n) recursion + char array. **Edge cases:** all digits → single result (the original string); all letters → 2ⁿ results; mixed handled per character.

---

### Problem 32: Beautiful Arrangement (count) — LeetCode 526

**Statement.** Count permutations `perm` of `1..n` such that for every position `i` (1-indexed), either `perm[i] % i == 0` or `i % perm[i] == 0`. `1 ≤ n ≤ 15`.

**Approach.** Naively enumerating all n! permutations and testing each is far too slow at n=15. The key insight: **check the divisibility constraint at the moment you place a number**, so any violating branch is pruned immediately — we never extend an arrangement that already breaks the rule. Backtrack position by position (1..n); at position `pos`, try each unused value `v` that satisfies `v % pos == 0 || pos % v == 0`, mark it used, recurse, unmark. Only a count is needed, so no path is stored. (A bitmask + memoization on `(pos, usedMask)` is the further optimization.)

```java
public class BeautifulArrangement {
    public int countArrangement(int n) {
        return backtrack(n, 1, new boolean[n + 1]);
    }

    private int backtrack(int n, int pos, boolean[] used) {
        if (pos > n) return 1;                           // a full valid arrangement
        int count = 0;
        for (int v = 1; v <= n; v++) {
            if (used[v]) continue;
            if (v % pos == 0 || pos % v == 0) {          // PRUNE: place only if constraint holds
                used[v] = true;                          // choose
                count += backtrack(n, pos + 1, used);    // explore
                used[v] = false;                         // un-choose
            }
        }
        return count;
    }
}
```

**Dry run** `n=2`: pos1 accepts v=1 (1%1==0) → pos2 v=2 (2%2==0) → count 1; pos1 v=2 (2%1==0) → pos2 v=1 (1%... 2%1==0) → count 2. Answer **2**. ✓

**Complexity.** **Time** O(k) where k ≤ n! is the number of valid arrangements actually visited (the constraint prunes most branches; far below n! in practice). **Space** O(n) used + recursion. **Edge cases:** `n=1` → 1; the constraint always holds at `pos=1` (every v divides... `pos % v` with pos=1 means 1%v, true only for v=1, but v%1==0 always) so position 1 accepts any value.

---

### Problem 33: Expression Add Operators — LeetCode 282 (hard / senior)

**Statement.** Given a digit string `num` and a `target`, insert binary `+`, `-`, `*` (or nothing) between digits so the resulting expression evaluates to `target`; return all such expressions. `1 ≤ num.length ≤ 10`.

**Approach.** Backtrack over where each operand ends. The subtlety is `*`, which binds tighter than `+`/`-`: to evaluate left-to-right in one pass, carry the running `value` **and** the value of the last appended operand (`prev`). For `+`, push `cur` and set `prev = cur`; for `-`, push `-cur` and `prev = -cur`; for `*`, undo the last addition by computing `value - prev + prev * cur` and set `prev = prev * cur`. A second subtlety is the **no-leading-zero** rule: an operand may be `"0"` but not `"05"`, so break after a single `0`. Use `long` to avoid overflow during evaluation.

```java
import java.util.*;

public class ExpressionAddOperators {
    public List<String> addOperators(String num, int target) {
        List<String> res = new ArrayList<>();
        backtrack(num, target, 0, 0L, 0L, new StringBuilder(), res);
        return res;
    }

    // value = expression value so far; prev = value of the last operand (for * precedence)
    private void backtrack(String num, int target, int start, long value, long prev,
                           StringBuilder expr, List<String> res) {
        if (start == num.length()) {
            if (value == target) res.add(expr.toString());
            return;
        }
        for (int i = start; i < num.length(); i++) {
            if (i > start && num.charAt(start) == '0') break;   // no leading zero (e.g. "05")
            long cur = Long.parseLong(num.substring(start, i + 1));
            int len = expr.length();
            if (start == 0) {                                   // first operand: no operator
                expr.append(cur);
                backtrack(num, target, i + 1, cur, cur, expr, res);
            } else {
                expr.append('+').append(cur);                  // addition
                backtrack(num, target, i + 1, value + cur, cur, expr, res);
                expr.setLength(len);

                expr.append('-').append(cur);                  // subtraction
                backtrack(num, target, i + 1, value - cur, -cur, expr, res);
                expr.setLength(len);

                expr.append('*').append(cur);                  // multiplication: rebind prev
                backtrack(num, target, i + 1, value - prev + prev * cur, prev * cur, expr, res);
            }
            expr.setLength(len);                                // backtrack the operand/operator
        }
    }
}
```

**Dry run** `num="232", target=8`: `2+3*2` → value path: 2, then +3 → value 5 prev 3, then *2 → 5 - 3 + 3*2 = 8 ✓; `2*3+2` → 2, *3 → 6 prev 6, +2 → 8 ✓. Result includes `["2+3*2","2*3+2"]`. ✓

**Complexity.** **Time** O(4ⁿ) — at each of n-1 gaps choose one of {nothing, +, -, *}, times O(n) string work. **Space** O(n) recursion + builder. **Edge cases:** leading-zero operands rejected; multiplication precedence handled via `prev`; overflow guarded by `long`; single-digit `num` with no operators.

---

### Problem 34: Gray Code (recursive reflection) — LeetCode 89

**Statement.** Return an `n`-bit Gray code sequence: a list of all `2ⁿ` integers `0..2ⁿ-1` where consecutive entries (and the first/last, cyclically) differ in exactly one bit. `1 ≤ n ≤ 16`.

**Approach.** The recursive *reflect-and-prefix* construction: the `n`-bit sequence is the `(n-1)`-bit sequence (each prefixed with `0`), followed by the `(n-1)`-bit sequence **reversed** with each prefixed with `1`. Reflection guarantees the single-bit-change property at the seam and the cyclic wrap. Base case: 1-bit sequence is `[0, 1]`. There is also a one-line closed form `g(i) = i ^ (i >> 1)`; the recursive build illuminates *why* it works and is the textbook divide-and-conquer answer.

```
   build G(2) from G(1)=[0,1]:
      lower half (prefix 0):  00, 01            -> 0, 1
      upper half (reflect, prefix 1): 11, 10    -> 3, 2
      G(2) = [0, 1, 3, 2]   (each step flips one bit; 2 -> 0 wraps by one bit)
```

```java
import java.util.*;

public class GrayCodeRecursive {
    public List<Integer> grayCode(int n) {
        List<Integer> res = new ArrayList<>();
        res.add(0);                                  // base: G(0) = [0]
        for (int bit = 0; bit < n; bit++) {          // add one bit per round via reflection
            int high = 1 << bit;
            for (int i = res.size() - 1; i >= 0; i--) // reflect existing list
                res.add(res.get(i) | high);           // prefix the reflected half with the new bit
        }
        return res;
    }

    // Pure recursive variant (divide-and-conquer), for the same result.
    public List<Integer> grayCodeRecursive(int n) {
        if (n == 0) { List<Integer> base = new ArrayList<>(); base.add(0); return base; }
        List<Integer> prev = grayCodeRecursive(n - 1);
        List<Integer> res = new ArrayList<>(prev);    // lower half: prefix 0 (values unchanged)
        int high = 1 << (n - 1);
        for (int i = prev.size() - 1; i >= 0; i--)    // upper half: reflected, prefix 1
            res.add(prev.get(i) | high);
        return res;
    }
}
```

**Dry run** `n=2`: start [0]; bit0 reflect → [0,1]; bit1 reflect [0,1]→ add 1|2=3, 0|2=2 → [0,1,3,2]. Consecutive XORs: 0^1=1, 1^3=2, 3^2=1, and wrap 2^0=2 — each a power of two (one bit). ✓

**Complexity.** **Time** O(2ⁿ) (each value produced once). **Space** O(2ⁿ) output; recursion depth O(n). **Edge cases:** `n=0` → `[0]`; `n=1` → `[0,1]`; the reflection guarantees the cyclic single-bit property automatically.

---

### Problem 35: All Paths From Source to Target (DAG) — LeetCode 797

**Statement.** Given a DAG of `n` nodes (`0..n-1`) as an adjacency list `graph` where `graph[i]` lists the nodes reachable from `i`, return **all** paths from node `0` to node `n-1`. `2 ≤ n ≤ 15`.

**Approach.** Classic DFS backtracking on a graph: extend the current path to each neighbor, recurse, and pop on return. Because the graph is a **DAG** (acyclic), no `visited` set is required — you can never loop back, so every walk from `0` terminates. Append the snapshot when the current node is the target. This is the graph analogue of subset/permutation enumeration, where the "choices" are out-edges rather than array indices.

```java
import java.util.*;

public class AllPathsSourceTarget {
    public List<List<Integer>> allPathsSourceTarget(int[][] graph) {
        List<List<Integer>> res = new ArrayList<>();
        List<Integer> path = new ArrayList<>();
        path.add(0);                                 // start at source
        dfs(graph, 0, path, res);
        return res;
    }

    private void dfs(int[][] graph, int node, List<Integer> path, List<List<Integer>> res) {
        if (node == graph.length - 1) {              // reached target
            res.add(new ArrayList<>(path));          // snapshot
            return;
        }
        for (int next : graph[node]) {               // each out-edge is a choice
            path.add(next);                          // choose
            dfs(graph, next, path, res);             // explore
            path.remove(path.size() - 1);            // un-choose
        }
    }
}
```

**Dry run** `graph=[[1,2],[3],[3],[]]` (target 3): from 0 → 1 → 3 gives [0,1,3]; backtrack to 0 → 2 → 3 gives [0,2,3]. Result `[[0,1,3],[0,2,3]]`. ✓

**Complexity.** **Time** O(2ⁿ · n) worst (a DAG can have exponentially many source-target paths; O(n) to copy each). **Space** O(n) recursion + path. **Edge cases:** `n=2` with edge `0→1` → single path `[0,1]`; a node with no out-edges that isn't the target → that branch dies silently; the DAG property removes the need for cycle tracking.

---

### Problem 36: Sequential Digits (combinatorial enumeration in range) — LeetCode 1291

**Statement.** A number has *sequential digits* if each digit is one more than the previous (e.g. `123`, `4567`). Given `low` and `high`, return the sorted list of all sequential-digit integers in `[low, high]`. `10 ≤ low ≤ high ≤ 10^9`.

**Approach.** Every sequential number is determined by a starting digit `s` (1..9) and a length `len`; the digits are `s, s+1, …` and must stay ≤ 9. Recursively build each candidate by appending the next digit, collecting it when it falls in range. Because the candidate space is tiny (at most 36 sequential numbers exist below 10 digits), this near-brute-force enumeration is optimal. Sort the collected values at the end. The recursion cleanly expresses "extend the run by one digit while ≤ 9."

```java
import java.util.*;

public class SequentialDigits {
    public List<Integer> sequentialDigits(int low, int high) {
        List<Integer> res = new ArrayList<>();
        for (int start = 1; start <= 9; start++)         // each possible first digit
            build(start, start, low, high, res);
        Collections.sort(res);
        return res;
    }

    // value = number built so far; last = its final digit
    private void build(int value, int last, int low, int high, List<Integer> res) {
        if (value > high) return;                        // PRUNE: already past the range
        if (value >= low) res.add(value);                // in range → collect
        if (last == 9) return;                           // cannot extend (no digit after 9)
        build(value * 10 + (last + 1), last + 1, low, high, res);  // append next digit
    }
}
```

**Dry run** `low=100, high=300`: start 1 builds 1,12,123 (123 in range), 1234>300 stop; start 2 builds 2,23,234 (234 in range), stop; start 3 → 3,34,345>300 stop. Collected {123,234}, sorted. ✓

**Complexity.** **Time** O(1) effectively — at most ~36 sequential numbers exist, plus an O(k log k) sort over them. **Space** O(1) besides output, recursion depth ≤ 9. **Edge cases:** no sequential number in range → `[]`; `low==high` that is itself sequential → singleton; the `last==9` guard stops runs like `789` from illegally extending.

---

### Problem 37: The k-th Permutation Sequence — LeetCode 60 (factorial number system)

**Statement.** Given `n` and `k`, return the `k`-th permutation (1-indexed, lexicographic order) of `1..n` **without enumerating** all permutations. `1 ≤ n ≤ 9`, `1 ≤ k ≤ n!`.

**Approach.** Generating all n! permutations and indexing is O(n·n!) — unacceptable. The optimal method exploits the **factorial number system**: the first digit is fixed by how many *blocks* of `(n-1)!` permutations fit into `k`. With `k` made 0-indexed, `index = k / (n-1)!` selects the first unused number, and `k %= (n-1)!` recurses on the rest with that number removed. This is O(n²) (the removal from a list) — far better than enumeration. Maintaining a list of unused numbers keeps the lexicographic order intact.

```
   n=3, k=3 (1-indexed) -> k0 = 2.  factorials: 2! = 2
   index = 2 / 2 = 1 -> pick numbers[1] = 2 ; k0 %= 2 -> 0 ; remaining [1,3]
   index = 0 / 1 = 0 -> pick 1 ; remaining [3]
   index = 0          -> pick 3
   result = "213"   (perms in order: 123,132,213,231,312,321 ; the 3rd is 213) ✓
```

```java
import java.util.*;

public class KthPermutation {
    public String getPermutation(int n, int k) {
        int[] fact = new int[n + 1];
        fact[0] = 1;
        List<Integer> numbers = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            fact[i] = fact[i - 1] * i;                   // factorials up to n!
            numbers.add(i);                              // available digits 1..n
        }
        k--;                                             // convert to 0-indexed
        StringBuilder sb = new StringBuilder();
        for (int i = n; i >= 1; i--) {
            int index = k / fact[i - 1];                 // which block → which unused number
            sb.append(numbers.remove(index));            // fix that digit, remove from pool
            k %= fact[i - 1];                            // descend into the chosen block
        }
        return sb.toString();
    }
}
```

**Dry run** `n=4, k=9`: k0=8, fact=[1,1,2,6,24]. i=4: 8/6=1 → pick numbers[1]=2, k=8%6=2, pool [1,3,4]; i=3: 2/2=1 → pick 3, k=0, pool [1,4]; i=2: 0/1=0 → pick 1, pool [4]; i=1: pick 4 → "2314". (The 9th permutation of 1234.) ✓

**Complexity.** **Time** O(n²) (each `list.remove` is O(n)). **Space** O(n) for the list and factorials. **Edge cases:** `n=1` → `"1"`; `k=1` → the identity `"123…n"`; `k=n!` → the fully descending permutation; the 0-indexing conversion (`k--`) is the classic off-by-one trap.

---

## 🧩 Extended Problems — Supplemental: Medium → Expert

### Problem 38: Tower of Hanoi — classic divide-and-conquer recursion

**Statement.** Move `n` disks from peg `A` to peg `C` using auxiliary peg `B`, obeying: one disk per move, never place a larger disk on a smaller one. Return the sequence of moves. `1 ≤ n ≤ 20`.

**Approach.** The textbook recursive decomposition: move the top `n-1` disks from source to auxiliary (using destination as helper), move the largest disk from source to destination, then move the `n-1` disks from auxiliary to destination (using source as helper). Each call shrinks `n` by 1. Total moves = `2ⁿ - 1` (provably optimal).

```
   move 3 disks A → C using B
     1) move 2 disks A → B (using C)
     2) move disk 3 A → C
     3) move 2 disks B → C (using A)
   Total: 2³ - 1 = 7 moves
```

```java
import java.util.*;

public class TowerOfHanoi {
    public List<String> solve(int n) {
        List<String> moves = new ArrayList<>();
        hanoi(n, 'A', 'C', 'B', moves);
        return moves;
    }

    private void hanoi(int n, char from, char to, char via, List<String> moves) {
        if (n == 0) return;                          // base case: nothing to move
        hanoi(n - 1, from, via, to, moves);          // step 1: top n-1 to auxiliary
        moves.add("Move disk " + n + " from " + from + " to " + to);
        hanoi(n - 1, via, to, from, moves);          // step 3: n-1 from aux to dest
    }
}
```

**Dry run** `n=2`: hanoi(1,A,B,C) → "Move disk 1 A→B"; "Move disk 2 A→C"; hanoi(1,B,C,A) → "Move disk 1 B→C". Three moves = 2² - 1. ✓

**Complexity.** **Time** O(2ⁿ) (exponential — recurrence T(n) = 2T(n-1) + 1). **Space** O(n) recursion depth. **Edge cases:** `n=0` returns no moves; `n=1` returns a single move; the move count is provably minimal — there is no faster algorithm.

---

### Problem 39: Unique Paths III (Hamiltonian-style grid DFS) — LeetCode 980 (hard)

**Statement.** Given an `m×n` grid with values `1` (start), `2` (end), `0` (empty), `-1` (obstacle), return the number of paths from `1` to `2` that **walk over every non-obstacle cell exactly once**. `1 ≤ m·n ≤ 20`.

**Approach.** This is a Hamiltonian path count, NP-hard in general but tractable here because the grid is tiny. Count `empty` (`0`) cells plus the start cell — the required path length. DFS from `1`, marking visited cells with a sentinel (`-1`), trying all 4 directions. When we reach `2`, the path is valid iff we've stepped on exactly `empty + 1` non-obstacle cells (start + every empty + end). Restore the sentinel on backtrack.

```java
public class UniquePathsIII {
    private int rows, cols, ans;

    public int uniquePathsIII(int[][] grid) {
        rows = grid.length; cols = grid[0].length;
        int sr = 0, sc = 0, remaining = 1;           // count cells we must step on
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++) {
                if (grid[r][c] == 0) remaining++;
                else if (grid[r][c] == 1) { sr = r; sc = c; }
            }
        ans = 0;
        dfs(grid, sr, sc, remaining);
        return ans;
    }

    private void dfs(int[][] g, int r, int c, int remaining) {
        if (r < 0 || c < 0 || r >= rows || c >= cols || g[r][c] == -1) return;
        if (g[r][c] == 2) {                          // reached end
            if (remaining == 0) ans++;               // used every required cell
            return;
        }
        int saved = g[r][c];
        g[r][c] = -1;                                // mark visited (choose)
        dfs(g, r + 1, c, remaining - 1);
        dfs(g, r - 1, c, remaining - 1);
        dfs(g, r, c + 1, remaining - 1);
        dfs(g, r, c - 1, remaining - 1);
        g[r][c] = saved;                             // restore (un-choose)
    }
}
```

**Dry run** grid `[[1,0,0,0],[0,0,0,0],[0,0,2,-1]]`: there are 11 non-obstacle cells; DFS explores all Hamiltonian-style paths landing on `2` after stepping on every other open cell. Answer: 2.

**Complexity.** **Time** O(4^(m·n)) worst (Hamiltonian). **Space** O(m·n) recursion. **Edge cases:** end unreachable → 0; start/end adjacent with no empty cells in between → 1 iff `remaining=0`; obstacles partition the grid → 0.

---

### Problem 40: Matchsticks to Square — LeetCode 473 (hard / bucket-fill backtracking)

**Statement.** Given an array `matchsticks`, return `true` if you can use them all to form a square (4 equal-length sides). `1 ≤ matchsticks.length ≤ 15`, each length ≤ 10⁸.

**Approach.** Equivalent to partitioning into 4 equal-sum subsets. Compute `side = sum/4` (fail if not divisible); sort **descending** so big sticks fail fast. Maintain 4 bucket sums; for each stick, try placing it in each bucket whose remaining capacity allows. Two strong prunes: (1) if `buckets[i] == buckets[i-1]` skip — symmetric, won't yield new outcomes; (2) place the first (largest) stick only into bucket 0, again by symmetry. This collapses 4^n to a few thousand nodes.

```java
import java.util.*;

public class Matchsticks {
    public boolean makesquare(int[] matchsticks) {
        int sum = 0;
        for (int x : matchsticks) sum += x;
        if (sum == 0 || sum % 4 != 0) return false;
        int side = sum / 4;
        // sort descending for early pruning
        Integer[] arr = new Integer[matchsticks.length];
        for (int i = 0; i < arr.length; i++) arr[i] = matchsticks[i];
        Arrays.sort(arr, Collections.reverseOrder());
        if (arr[0] > side) return false;
        int[] buckets = new int[4];
        return dfs(arr, 0, buckets, side);
    }

    private boolean dfs(Integer[] arr, int idx, int[] buckets, int side) {
        if (idx == arr.length) {
            return buckets[0] == side && buckets[1] == side && buckets[2] == side;
        }
        for (int b = 0; b < 4; b++) {
            if (buckets[b] + arr[idx] > side) continue;
            // symmetry prune: skip equal earlier bucket
            int j = b;
            boolean dup = false;
            while (--j >= 0) if (buckets[j] == buckets[b]) { dup = true; break; }
            if (dup) continue;
            buckets[b] += arr[idx];                   // choose
            if (dfs(arr, idx + 1, buckets, side)) return true;
            buckets[b] -= arr[idx];                   // un-choose
        }
        return false;
    }
}
```

**Dry run** `[1,1,2,2,2]`: sum=8, side=2; sorted desc [2,2,2,1,1]; place 2→b0, 2→b1, 2→b2, 1→b3, 1→b3 fails (b3=2)... try other orderings — final result `true` (`2,2,2,1+1`).

**Complexity.** **Time** O(4^n) worst, drastically pruned. **Space** O(n) recursion. **Edge cases:** sum not divisible by 4 → false; any stick > side → false; n < 4 → false.

---

### Problem 41: Flood Fill — LeetCode 733 (DFS recursion on grid)

**Statement.** Given an `m×n` image, a starting pixel `(sr, sc)`, and a new color, replace the color of the starting pixel and all 4-connected pixels of the same original color. Return the modified image.

**Approach.** Pure DFS recursion. Save the original color; if it equals the new color, return immediately (avoids infinite recursion on a same-color call). Otherwise recursively repaint the current pixel and its 4 neighbors that still hold the original color. No explicit `visited` array is needed because the color change itself marks visited.

```java
public class FloodFill {
    public int[][] floodFill(int[][] image, int sr, int sc, int newColor) {
        int original = image[sr][sc];
        if (original != newColor) dfs(image, sr, sc, original, newColor);
        return image;
    }

    private void dfs(int[][] img, int r, int c, int original, int newColor) {
        if (r < 0 || c < 0 || r >= img.length || c >= img[0].length) return;
        if (img[r][c] != original) return;            // PRUNE: different color
        img[r][c] = newColor;                          // repaint = mark visited
        dfs(img, r + 1, c, original, newColor);
        dfs(img, r - 1, c, original, newColor);
        dfs(img, r, c + 1, original, newColor);
        dfs(img, r, c - 1, original, newColor);
    }
}
```

**Dry run** `image=[[1,1,1],[1,1,0],[1,0,1]], sr=1,sc=1, newColor=2`: starting at (1,1)=1, repaint to 2; neighbors with 1 cascade — final image `[[2,2,2],[2,2,0],[2,0,1]]`. The pixel (2,2)=1 is isolated from the start (not 4-connected) so it stays. ✓

**Complexity.** **Time** O(m·n) — each cell visited at most once. **Space** O(m·n) recursion depth worst-case. **Edge cases:** start pixel already the new color → unchanged image (guard prevents infinite recursion); single pixel → repainted; entire grid same color → all repainted.

---

### Problem 42: Number of Islands — LeetCode 200 (connected components via DFS recursion)

**Statement.** Given an `m×n` grid of `'1'` (land) and `'0'` (water), count the number of distinct islands (4-connected groups of land). `1 ≤ m, n ≤ 300`.

**Approach.** Scan the grid; on each unvisited `'1'`, increment the count and DFS-flood that island, marking every connected land cell as visited (overwrite with `'0'` to avoid an auxiliary `visited[][]`). The DFS does the connected-component bookkeeping; each cell is touched once amortized.

```java
public class NumberOfIslands {
    public int numIslands(char[][] grid) {
        int count = 0;
        for (int r = 0; r < grid.length; r++)
            for (int c = 0; c < grid[0].length; c++)
                if (grid[r][c] == '1') {
                    count++;
                    sink(grid, r, c);                 // flood the island
                }
        return count;
    }

    private void sink(char[][] g, int r, int c) {
        if (r < 0 || c < 0 || r >= g.length || c >= g[0].length || g[r][c] != '1') return;
        g[r][c] = '0';                                // mark visited by sinking
        sink(g, r + 1, c);
        sink(g, r - 1, c);
        sink(g, r, c + 1);
        sink(g, r, c - 1);
    }
}
```

**Dry run** `[[1,1,0],[0,1,0],[0,0,1]]`: first '1' at (0,0) sinks (0,0),(0,1),(1,1) → count 1; then (2,2) sinks alone → count 2. ✓

**Complexity.** **Time** O(m·n). **Space** O(m·n) recursion depth worst case (BFS via a queue avoids deep stacks for huge grids). **Edge cases:** all water → 0; all land → 1; single cell → 0 or 1.

---

### Problem 43: Subsets Sum to Target (decision) — feasibility via include/exclude recursion

**Statement.** Given an array `nums` of positive integers and a target `S`, decide whether any subset sums to `S`. `1 ≤ nums.length ≤ 30`.

**Approach.** Classic 0/1 recursion: for each element, branch on **include** vs **exclude**. Pure recursion is O(2ⁿ). Memoize on `(index, remaining)` to collapse overlapping work to O(n·S). Two strong prunes: short-circuit when `remaining == 0` (success), and skip the include branch when `nums[i] > remaining`. This is the recursive precursor to the Subset Sum DP and to 0/1 Knapsack.

```java
import java.util.*;

public class SubsetSum {
    public boolean canSum(int[] nums, int target) {
        Map<Long, Boolean> memo = new HashMap<>();
        return dfs(nums, 0, target, memo);
    }

    private boolean dfs(int[] nums, int i, int rem, Map<Long, Boolean> memo) {
        if (rem == 0) return true;                            // base: target met
        if (i == nums.length || rem < 0) return false;        // exhausted / overshot
        long key = ((long) i << 32) | (rem & 0xffffffffL);
        Boolean cached = memo.get(key);
        if (cached != null) return cached;
        // include OR exclude nums[i]
        boolean ok = (nums[i] <= rem && dfs(nums, i + 1, rem - nums[i], memo))
                  || dfs(nums, i + 1, rem, memo);
        memo.put(key, ok);
        return ok;
    }
}
```

**Dry run** `nums=[3,34,4,12,5,2], target=9`: include 3 → rem 6 → include 4 → rem 2 → include 2 → rem 0 → true. ✓

**Complexity.** **Time** O(n·S) with memoization (O(2ⁿ) without). **Space** O(n·S) memo + O(n) recursion. **Edge cases:** `target=0` → true (empty subset); negative numbers complicate memoization (use a bottom-up DP with offset).

---

### Problem 44: Stickers to Spell Word — LeetCode 691 (hard / bitmask + memoized recursion)

**Statement.** Given a list of sticker strings and a target word, return the minimum number of stickers needed (with arbitrary reuse) to form `target` by rearranging their letters, or `-1` if impossible. `1 ≤ target.length ≤ 15`.

**Approach.** Encode the remaining letters of `target` as a **bitmask** over its 15 positions (since target is short). Recursively try every sticker; for each, consume from the current bitmask whichever target letters it provides, then recurse on the reduced mask. Cache results in `memo[mask]`. Key prune: skip stickers that don't include the *first uncovered* target letter — otherwise progress is impossible on this branch, slashing the branching factor.

```java
import java.util.*;

public class StickersToSpellWord {
    public int minStickers(String[] stickers, String target) {
        int n = target.length();
        int[] memo = new int[1 << n];
        Arrays.fill(memo, -1);
        memo[0] = 0;                                          // empty target → 0 stickers
        return dfs(stickers, target, (1 << n) - 1, memo);
    }

    private int dfs(String[] stickers, String target, int mask, int[] memo) {
        if (memo[mask] != -1) return memo[mask];
        int n = target.length(), best = Integer.MAX_VALUE;
        // find first uncovered position to force progress
        int firstBit = Integer.numberOfTrailingZeros(mask);
        char need = target.charAt(firstBit);
        for (String s : stickers) {
            if (s.indexOf(need) < 0) continue;                // PRUNE: no help with required letter
            int[] count = new int[26];
            for (char c : s.toCharArray()) count[c - 'a']++;
            int newMask = mask;
            for (int i = 0; i < n; i++) {
                if (((newMask >> i) & 1) == 1) {
                    int ci = target.charAt(i) - 'a';
                    if (count[ci] > 0) {
                        count[ci]--;
                        newMask ^= (1 << i);                  // clear that bit
                    }
                }
            }
            int sub = dfs(stickers, target, newMask, memo);
            if (sub != Integer.MAX_VALUE) best = Math.min(best, 1 + sub);
        }
        memo[mask] = best;
        return best;
    }
}
```

**Dry run** stickers=`["with","example","science"]`, target=`"thehat"` (n=6). After several recursive calls the algorithm finds `"with"+"with"+"example"+"example"+... `, ultimately needing 3 stickers; `memo` ensures each remaining-mask is solved once.

**Complexity.** **Time** O(2^n · S · n) where S = total sticker characters. **Space** O(2^n). **Edge cases:** if any letter in `target` appears in no sticker → return `-1` (`Integer.MAX_VALUE` propagates); empty target → 0.

---

### Problem 45: Regular Expression Matching — LeetCode 10 (hard / recursive matching with memoization)

**Statement.** Implement regex match supporting `.` (any single char) and `*` (zero or more of the preceding element). Return `true` iff the *entire* string `s` matches pattern `p`. `1 ≤ s.length, p.length ≤ 20`.

**Approach.** Recurse on `(i, j)` = current positions in `s`, `p`. Two cases drive the recursion. **(1)** Next pattern char is `*`: either skip the `x*` group (`j+2`), or — if the current char matches — consume one char of `s` (`i+1`) and keep `j`. **(2)** Otherwise consume one matching char from each. Without memoization it's exponential due to overlapping `(i, j)` states; with a 2D memo it becomes O(m·n).

```java
public class RegexMatch {
    public boolean isMatch(String s, String p) {
        Boolean[][] memo = new Boolean[s.length() + 1][p.length() + 1];
        return dp(0, 0, s, p, memo);
    }

    private boolean dp(int i, int j, String s, String p, Boolean[][] memo) {
        if (memo[i][j] != null) return memo[i][j];
        boolean ans;
        if (j == p.length()) {                                // pattern exhausted
            ans = i == s.length();
        } else {
            boolean firstMatch = i < s.length()
                    && (p.charAt(j) == s.charAt(i) || p.charAt(j) == '.');
            if (j + 1 < p.length() && p.charAt(j + 1) == '*') {
                // option A: zero occurrences (skip "x*")  OR  option B: consume one from s
                ans = dp(i, j + 2, s, p, memo)
                   || (firstMatch && dp(i + 1, j, s, p, memo));
            } else {
                ans = firstMatch && dp(i + 1, j + 1, s, p, memo);
            }
        }
        return memo[i][j] = ans;
    }
}
```

**Dry run** `s="aab", p="c*a*b"`: skip `c*` (zero) → at `a*b`; consume two `a`s → at `b`; match `b` → true. ✓

**Complexity.** **Time** O(m·n) with memo (each state computed once). **Space** O(m·n). **Edge cases:** empty `s` with pattern `"a*b*"` → true (all groups zero); pattern `".*"` matches any `s`; mis-placed `*` (first char) is by spec invalid.

---

### Problem 46: Robot Room Cleaner — LeetCode 489 (hard / DFS on unknown grid)

**Statement.** A robot in an unknown rectangular room with obstacles must clean every accessible cell. You may only call `move()`, `turnLeft()`, `turnRight()`, `clean()` — coordinates are not given. Visit every cell.

**Approach.** Maintain virtual coordinates `(r, c)` and a direction index. DFS from the start: clean the current cell, mark `(r, c)` visited, try each of 4 directions; if `move()` succeeds, recurse; on return, **back up** by moving forward, then turn 180° to face the original direction. Two `turnRight`s realign for the next neighbor. The "go-back" choreography is the critical backtracking detail since the grid is opaque.

```java
import java.util.*;

public class RobotRoomCleaner {
    interface Robot {
        boolean move();
        void turnLeft();
        void turnRight();
        void clean();
    }
    private static final int[] DR = {-1, 0, 1, 0};   // up, right, down, left
    private static final int[] DC = {0, 1, 0, -1};

    public void cleanRoom(Robot robot) {
        dfs(robot, 0, 0, 0, new HashSet<>());
    }

    private void dfs(Robot robot, int r, int c, int dir, Set<String> visited) {
        robot.clean();
        visited.add(r + "," + c);
        for (int i = 0; i < 4; i++) {
            int nd = (dir + i) % 4;
            int nr = r + DR[nd], nc = c + DC[nd];
            if (!visited.contains(nr + "," + nc) && robot.move()) {
                dfs(robot, nr, nc, nd, visited);
                // back-up: face opposite, move, restore facing
                robot.turnRight(); robot.turnRight();
                robot.move();
                robot.turnRight(); robot.turnRight();
            }
            robot.turnRight();                        // try next direction
        }
    }
}
```

**Dry run** 2×2 room with robot at (0,0) facing up: cleans (0,0); tries up (blocked, wall); turns right (now facing right) → moves to (0,1) → recurses → cleans (0,1) → after exploring all 4 from (0,1), backtracks to (0,0); continues remaining rotations. Every reachable cell is cleaned exactly once.

**Complexity.** **Time** O(4^(N - M)) where N = free cells, M = visited so far — practically O(N) since each cell is DFS'd once. **Space** O(N) for `visited` + O(N) recursion. **Edge cases:** completely enclosed start cell → only that cell cleaned; the back-up motion must always succeed (we came from there).

---

### Problem 47: Wildcard Matching — LeetCode 44 (hard / recursive matching with `?` and `*`)

**Statement.** Implement wildcard pattern matching with `?` (any single char) and `*` (any sequence including empty). Return true iff the whole string matches the whole pattern. `0 ≤ s.length, p.length ≤ 2000`.

**Approach.** Recurse on `(i, j)`. If `p[j]` is `?` or matches `s[i]`, advance both. If `p[j]` is `*`, branch: either it matches **zero** chars (`j+1`) or it consumes **one more** char of `s` (`i+1`, stay at `j`). Memoize on `(i, j)` to defeat the exponential branching that consecutive `*`s cause. A neat optimization: collapse consecutive `*`s in the pattern (`**` ≡ `*`) before recursing.

```java
public class WildcardMatch {
    public boolean isMatch(String s, String p) {
        Boolean[][] memo = new Boolean[s.length() + 1][p.length() + 1];
        return dp(0, 0, s, p, memo);
    }

    private boolean dp(int i, int j, String s, String p, Boolean[][] memo) {
        if (memo[i][j] != null) return memo[i][j];
        boolean ans;
        if (j == p.length()) {
            ans = i == s.length();
        } else if (p.charAt(j) == '*') {
            ans = dp(i, j + 1, s, p, memo)                          // '*' matches empty
                  || (i < s.length() && dp(i + 1, j, s, p, memo));  // '*' eats one more char
        } else if (i < s.length()
                && (p.charAt(j) == '?' || p.charAt(j) == s.charAt(i))) {
            ans = dp(i + 1, j + 1, s, p, memo);
        } else {
            ans = false;
        }
        return memo[i][j] = ans;
    }
}
```

**Dry run** `s="adceb", p="*a*b"`: `*` matches "" → at `a*b`; consume `a` → at `*b`; `*` matches "dce" via repeated +1 → consume `b` → match. ✓

**Complexity.** **Time** O(m·n) with memo. **Space** O(m·n). **Edge cases:** empty `p` matches only empty `s`; pattern of only `*` matches anything; long runs of `*` benefit from pre-collapsing the pattern.

---

### Problem 48: Permutations Iterator (lazy next-permutation) — recursion-replacement enumerator

**Statement.** Implement an iterator over permutations of a string `s` (distinct chars) in lexicographic order, exposing `hasNext()` and `next()`. Avoid materializing all `n!` permutations.

**Approach.** Sort the string ascending — this is the lexicographically first permutation. `next()` returns the current permutation and advances via the classic **next-permutation** algorithm (which is itself the iterative crystallization of the recursive permutation walk): find the rightmost `i` with `a[i] < a[i+1]`; swap `a[i]` with the rightmost element greater than `a[i]`; reverse the suffix `a[i+1..]`. When no such `i` exists, exhaustion is reached. O(n) per `next()`, O(1) extra space — far better than the recursive enumeration when only a few permutations are needed.

```java
import java.util.*;

public class PermutationIterator {
    private char[] arr;
    private boolean done;

    public PermutationIterator(String s) {
        arr = s.toCharArray();
        Arrays.sort(arr);                            // start at the smallest perm
        done = false;
    }

    public boolean hasNext() { return !done; }

    public String next() {
        if (done) throw new NoSuchElementException();
        String result = new String(arr);
        advance();
        return result;
    }

    private void advance() {
        int n = arr.length, i = n - 2;
        while (i >= 0 && arr[i] >= arr[i + 1]) i--;  // find descent point
        if (i < 0) { done = true; return; }          // last perm reached
        int j = n - 1;
        while (arr[j] <= arr[i]) j--;
        char t = arr[i]; arr[i] = arr[j]; arr[j] = t;
        for (int l = i + 1, r = n - 1; l < r; l++, r--) {
            t = arr[l]; arr[l] = arr[r]; arr[r] = t;
        }
    }
}
```

**Dry run** `"abc"`: emits `abc`, `acb`, `bac`, `bca`, `cab`, `cba` — six permutations in lex order, each computed in O(n) on demand.

**Complexity.** **Time** O(n) per `next()`, O(n·n!) total to enumerate all. **Space** O(n). **Edge cases:** single char → 1 permutation then `done`; empty string → exactly one empty permutation; this is the deterministic-order alternative to recursive permutation generation.

---

### Problem 49: Sum of All Subset XOR Totals — LeetCode 1863 (subset enumeration with aggregation)

**Statement.** Given an array `nums`, the *XOR total* of a subset is the XOR of all its elements (empty subset = 0). Return the **sum** of XOR totals across all 2ⁿ subsets. `1 ≤ nums.length ≤ 12`.

**Approach.** Direct recursion: at each index, branch on include vs exclude, threading the running XOR. At a leaf, add the XOR to a global sum. This is the canonical subset-recursion lifted from "build the subset" to "aggregate over subsets". The elegant follow-up: a bit-level analysis shows the answer equals `OR(nums) * 2^(n-1)` — each bit set anywhere contributes half the subsets — but the recursive aggregation is what an interviewer first wants to see.

```java
public class SubsetXORSum {
    public int subsetXORSum(int[] nums) {
        return dfs(nums, 0, 0);
    }

    private int dfs(int[] nums, int i, int xorSoFar) {
        if (i == nums.length) return xorSoFar;                // each subset contributes its XOR
        // include nums[i] OR exclude it
        return dfs(nums, i + 1, xorSoFar ^ nums[i])
             + dfs(nums, i + 1, xorSoFar);
    }

    // Optimal closed-form (O(n)) once the bit-counting insight is seen.
    public int subsetXORSumFast(int[] nums) {
        int or = 0;
        for (int x : nums) or |= x;
        return or << (nums.length - 1);
    }
}
```

**Dry run** `nums=[1,3]`: subsets [], [1], [3], [1,3] → XORs 0,1,3,2 → sum 6. Recursion explores 4 leaves; closed form: OR=3, n-1=1 → 3<<1 = 6. ✓

**Complexity.** **Time** O(2ⁿ) recursion (O(n) closed form). **Space** O(n) recursion depth. **Edge cases:** single element → that element itself; all zeros → 0; uses the recursion to *aggregate* rather than enumerate — a useful pattern for any "sum over subsets" problem.

---

### Problem 50: Splitting a String into Descending Consecutive Values — LeetCode 1849 (hard backtracking on cut positions)

**Statement.** Given a string `s` of digits, decide if it can be split into 2+ non-empty substrings whose numeric values are **strictly descending by 1**. `1 ≤ s.length ≤ 20`. Leading zeros allowed in each piece.

**Approach.** Backtrack over the first split point: try each length `1..s.length-1` for the first number; then recursively check whether the remainder begins with `firstNum - 1`, followed by `firstNum - 2`, and so on. Use `long` (digits ≤ 20 ⇒ values overflow `int`). The branching factor on the first piece is at most 20 — recursion on the rest is greedy because the next expected value is fixed. Prune aggressively: if the remaining string's prefix isn't exactly the expected value, abort that branch.

```java
public class SplitDescending {
    public boolean splitString(String s) {
        for (int len = 1; len < s.length(); len++) {          // try each first-number length
            long first = Long.parseLong(s.substring(0, len));
            if (check(s, len, first)) return true;
        }
        return false;
    }

    // verify remainder is first-1, first-2, ... down to 0 or end of string
    private boolean check(String s, int start, long expected) {
        if (start == s.length()) return true;                  // consumed everything → success
        long next = expected - 1;
        if (next < 0) return false;
        String wanted = Long.toString(next);
        // also allow leading zeros, but value comparison is by parsed Long
        for (int end = start + 1; end <= s.length(); end++) {
            long val = Long.parseLong(s.substring(start, end));
            if (val == next) {
                if (check(s, end, next)) return true;
            }
            if (val > next) break;                             // PRUNE: only grows from here
        }
        return false;
    }
}
```

**Dry run** `s="10009998"`: first="1000" (1000), next must be 999 → "999" matches → next 998 → "8" parsed as 8, no... try a different first. Try first="1000" then "9998" → ... eventually first="1000", "999", "8" fails (8 ≠ 998). Real example: `"050043"` → 50, 49, ... no; `"100908"` → splits as 100,99,98? "100","99","08" → parsed 8 ≠ 98. Real success: `"1234"` → 1,2,3,4 ascending so false. `"54321"` → 5,4,3,2,1 ✓.

**Complexity.** **Time** O(n²) (n first-length choices × greedy O(n) remainder). **Space** O(n) recursion. **Edge cases:** single digit string → false (need 2+ pieces); strings beginning with many zeros must still parse — `"00000"` splits as `0,0,...` but values must strictly decrease, so false; the `long` widening is critical for 20-digit strings.

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
