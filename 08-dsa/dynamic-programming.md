# Dynamic Programming (1D, 2D, Optimization)

Dynamic Programming (DP) is the art of solving a problem once by breaking it into overlapping subproblems and reusing their answers. Master the recurrence, and the code writes itself.

[← Back to master index](../README.md) | [← DSA index](README.md)

---

## Concept & Intuition

Dynamic Programming is an **optimization over plain recursion**. Wherever a recursive solution recomputes the same subproblem multiple times, DP caches each subproblem's answer and reuses it, collapsing exponential work into polynomial work.

A problem is a DP candidate when it has **both** of these properties:

1. **Optimal substructure** — the optimal answer to the whole problem can be built from optimal answers to its subproblems. (e.g. shortest path through a node uses shortest paths to that node.)
2. **Overlapping subproblems** — the same subproblems are solved again and again in the naive recursion. (Distinguishes DP from divide-and-conquer like merge sort, where subproblems are disjoint.)

There are two ways to implement a DP:

- **Memoization (top-down):** write the natural recursion, then add a cache (array/map). You solve only the subproblems you actually reach. Easy to derive, uses the call stack.
- **Tabulation (bottom-up):** solve subproblems from smallest to largest, filling a table iteratively. No recursion overhead, often allows space optimization. Requires you to know a valid evaluation order.

```
Naive recursion (fib):           Memoized — each node computed once:
            f(5)                          f(5)
          /      \                      /      \
        f(4)     f(3)                 f(4)    [f(3) cached]
       /    \    /   \               /    \
     f(3)  f(2) f(2) f(1)          f(3)  [f(2) cached]
     ...   (exponential)           ...   (linear)
```

**Invariant to keep straight:** when you read `dp[i]` (or `dp[i][j]`) in your transition, every state it depends on must already hold its final value. Get the iteration order wrong and you read stale data.

**The DP workflow that never fails:**
1. Define the **state** precisely — what does `dp[i]` (or `dp[i][j]`) *mean*?
2. Write the **recurrence/transition** between states.
3. Set **base cases**.
4. Decide the **iteration order** (top-down handles this automatically).
5. Read the **answer** out of the table.
6. Optionally **optimize space** (rolling array, keep only previous row/cols).

---

## Complexity Cheat-Sheet

| Pattern / Problem | Time | Space | Space-optimized |
|---|---|---|---|
| Climbing stairs / Fibonacci | O(n) | O(n) | **O(1)** |
| House robber | O(n) | O(n) | **O(1)** |
| Coin change (min coins) | O(n·amount) | O(amount) | O(amount) |
| Decode ways | O(n) | O(n) | **O(1)** |
| Unique paths (grid) | O(m·n) | O(m·n) | **O(n)** |
| Edit distance | O(m·n) | O(m·n) | **O(min(m,n))** |
| Longest Common Subsequence | O(m·n) | O(m·n) | **O(min(m,n))** |
| 0/1 Knapsack | O(n·W) | O(n·W) | **O(W)** |
| LIS (binary search) | **O(n log n)** | O(n) | O(n) |
| Subset sum / Partition | O(n·sum) | O(sum) | O(sum) |
| Matrix chain multiplication | O(n³) | O(n²) | O(n²) |
| Burst balloons | O(n³) | O(n²) | O(n²) |
| DP on tree | O(n) | O(n) | O(h) stack |
| Bitmask DP (TSP) | O(2ⁿ·n²) | O(2ⁿ·n) | O(2ⁿ·n) |

> Rule of thumb: DP time ≈ (number of states) × (work per transition).

---

## Patterns & Recognition

Reach for DP when you see these signals in the prompt:

- **"Count the number of ways…"** → DP that sums over choices (climbing stairs, decode ways, unique paths).
- **"Find the minimum / maximum … to achieve X"** → DP that takes min/max over choices (coin change, edit distance, knapsack).
- **"Is it possible to … / can we partition …"** → boolean DP (subset sum, word break).
- **"Longest / shortest subsequence / substring"** → 1D or 2D sequence DP (LIS, LCS).
- **Choices at each step that affect the future** ("take it or leave it", adjacency constraints) → state machine DP (house robber, stock trading).
- **A range `[i..j]` whose answer depends on a split point `k`** → interval DP (matrix chain, burst balloons).
- **Small `n` (≤ ~20) plus "visit every / subset of"** → bitmask DP (TSP, assignment).
- **Tree where a node's answer depends on children** → tree DP via post-order DFS.

**Greedy-vs-DP tiebreaker:** if a locally optimal choice can be proven to stay globally optimal, use greedy (it's faster). If a local choice might hurt you later, you need DP to consider all options. Coin change with arbitrary denominations is the classic "greedy fails, DP wins" example.

---

## Coding Problems

### Problem 1: Climbing Stairs

You climb a staircase of `n` steps; each move is 1 or 2 steps. How many distinct ways to reach the top? Constraints: `1 ≤ n ≤ 45`.

**Approach.** Brute force enumerates every path: `f(n) = f(n-1) + f(n-2)` recursively — O(2ⁿ). Recognize it's Fibonacci: each state depends only on the two before it, so we keep two variables → O(1) space.

```java
public int climbStairs(int n) {
    if (n <= 2) return n;
    int prev2 = 1, prev1 = 2; // ways to reach step 1 and step 2
    for (int i = 3; i <= n; i++) {
        int cur = prev1 + prev2;
        prev2 = prev1;
        prev1 = cur;
    }
    return prev1;
}
```

**Dry run (n = 4):** start prev2=1, prev1=2. i=3 → cur=3; i=4 → cur=5. Answer 5: {1+1+1+1, 1+1+2, 1+2+1, 2+1+1, 2+2}.

**Time:** O(n). **Space:** O(1).
**Follow-ups:** steps of 1..k → sliding window sum; count ways with a cost per step → min-cost climbing stairs.

---

### Problem 2: Min Cost Climbing Stairs

Given `cost[i]` to step on stair `i`, you may start at index 0 or 1, climb 1 or 2 each move, and pay the cost of the stair you step on. Reach past the last stair for minimum total cost. Constraints: `2 ≤ n ≤ 1000`.

**Approach.** `dp[i]` = min cost to reach stair `i`. To stand on `i`, come from `i-1` or `i-2`: `dp[i] = cost[i] + min(dp[i-1], dp[i-2])`. The "top" is index `n`, reachable from `n-1` or `n-2`. Two rolling variables suffice.

```java
public int minCostClimbingStairs(int[] cost) {
    int n = cost.length;
    int prev2 = 0, prev1 = 0; // cost to reach stair 0 and 1 (free to start there)
    for (int i = 2; i <= n; i++) {
        int cur = Math.min(prev1 + cost[i - 1], prev2 + cost[i - 2]);
        prev2 = prev1;
        prev1 = cur;
    }
    return prev1;
}
```

**Dry run (cost = [10,15,20]):** i=2 → min(0+15, 0+10)=10; i=3(top) → min(10+20, 0+15)=15. Answer 15.

**Time:** O(n). **Space:** O(1).
**Follow-ups:** k-step moves; reconstruct the path taken (store parent pointers).

---

### Problem 3: House Robber

Rob houses along a street, each holding `nums[i]` cash, but you cannot rob two adjacent houses. Maximize loot. Constraints: `1 ≤ n ≤ 100`, `0 ≤ nums[i] ≤ 400`.

**Approach.** State machine: at house `i` you either **rob** it (`nums[i] + best up to i-2`) or **skip** it (`best up to i-1`). `dp[i] = max(dp[i-1], dp[i-2] + nums[i])`. Greedy fails (e.g. `[2,1,1,2]`). Roll two variables.

```java
public int rob(int[] nums) {
    int prev2 = 0; // best loot two houses back
    int prev1 = 0; // best loot one house back
    for (int x : nums) {
        int cur = Math.max(prev1, prev2 + x);
        prev2 = prev1;
        prev1 = cur;
    }
    return prev1;
}
```

**Dry run ([2,7,9,3,1]):** cur sequence → 2, 7, max(7,2+9)=11, max(11,7+3)=11, max(11,11+1)=12. Answer 12 = rob houses 7+9+? no: 2+9+1=12.

**Time:** O(n). **Space:** O(1).
**Follow-ups:** **House Robber II** (houses in a circle → run linear DP twice, excluding first or last); **House Robber III** (binary tree → tree DP, below).

---

### Problem 4: Decode Ways

A message of digits is encoded where 'A'→1 … 'Z'→26. Count decodings of string `s`. Constraints: `1 ≤ n ≤ 100`, `s` contains digits; may contain leading zeros that make some prefixes invalid.

**Approach.** `dp[i]` = number of ways to decode the first `i` characters. From position `i`: take one digit if it's 1–9 (`dp[i-1]`), and take two digits if they form 10–26 (`dp[i-2]`). Zeros are the trap: '0' is only valid as part of "10" or "20".

```java
public int numDecodings(String s) {
    int n = s.length();
    if (n == 0 || s.charAt(0) == '0') return 0;
    int prev2 = 1; // dp[i-2], empty string
    int prev1 = 1; // dp[i-1], first char valid
    for (int i = 2; i <= n; i++) {
        int cur = 0;
        char one = s.charAt(i - 1);
        if (one != '0') cur += prev1;                 // single-digit decode
        int two = Integer.parseInt(s.substring(i - 2, i));
        if (two >= 10 && two <= 26) cur += prev2;      // two-digit decode
        prev2 = prev1;
        prev1 = cur;
    }
    return prev1;
}
```

**Dry run ("226"):** i=2 "22": one='2'(+1)→prev1=1; two=22(+1)→prev2=1 ⇒ cur=2. i=3 "26": one='6'(+2); two=26(+1) ⇒ cur=3. Answer 3: {2 2 6, 22 6, 2 26}.

**Time:** O(n). **Space:** O(1).
**Follow-ups:** with '*' wildcards (Decode Ways II, careful counting); return all decodings (backtracking, exponential output).

---

### Problem 5: Coin Change (Minimum Coins)

Given coin denominations and a target `amount`, return the fewest coins summing to it, or -1 if impossible. Constraints: `1 ≤ coins.length ≤ 12`, `0 ≤ amount ≤ 10⁴`.

**Approach.** Greedy fails for denominations like `[1,3,4]`, amount 6 (greedy 4+1+1=3 coins; optimal 3+3=2). Unbounded knapsack DP: `dp[a]` = min coins for amount `a`; `dp[a] = 1 + min(dp[a - c])` over coins `c ≤ a`.

```java
public int coinChange(int[] coins, int amount) {
    int[] dp = new int[amount + 1];
    Arrays.fill(dp, amount + 1); // sentinel "infinity"
    dp[0] = 0;
    for (int a = 1; a <= amount; a++) {
        for (int c : coins) {
            if (c <= a) dp[a] = Math.min(dp[a], dp[a - c] + 1);
        }
    }
    return dp[amount] > amount ? -1 : dp[amount];
}
```

**Dry run (coins=[1,2,5], amount=11):** dp builds … dp[11]=min(dp[10],dp[9],dp[6])+1 = 2+1 = 3 (5+5+1).

**Time:** O(amount · coins). **Space:** O(amount).
**Follow-ups:** **Coin Change II** — count the number of combinations (loop coins outer, amount inner, to avoid permutation double-counting); bounded coins; reconstruct the actual coin set.

---

### Problem 6: Longest Increasing Subsequence (O(n log n))

Return the length of the longest strictly increasing subsequence of `nums`. Constraints: `1 ≤ n ≤ 2500` (n log n version handles up to ~10⁵).

**Approach.** O(n²) DP: `dp[i]` = LIS ending at `i`, `dp[i] = 1 + max(dp[j])` for `j<i, nums[j]<nums[i]`. The optimal **patience-sorting** trick: maintain `tails`, where `tails[k]` = smallest possible tail of an increasing subsequence of length `k+1`. For each number, binary-search its insertion point. Replacing keeps tails minimal so future numbers extend more easily.

```java
public int lengthOfLIS(int[] nums) {
    int[] tails = new int[nums.length];
    int size = 0;
    for (int x : nums) {
        int lo = 0, hi = size;
        while (lo < hi) {              // lower_bound: first tail >= x
            int mid = (lo + hi) >>> 1;
            if (tails[mid] < x) lo = mid + 1;
            else hi = mid;
        }
        tails[lo] = x;                 // extend or replace
        if (lo == size) size++;
    }
    return size;
}
```

**Dry run ([10,9,2,5,3,7,101,18]):** tails evolves [10]→[9]→[2]→[2,5]→[2,3]→[2,3,7]→[2,3,7,101]→[2,3,7,18]. size=4.

**Time:** O(n log n). **Space:** O(n).
**Follow-ups:** non-decreasing → use `upper_bound`; **count** of LISs (segment tree / Fenwick); reconstruct the subsequence (store predecessor indices alongside positions); 2D variant (Russian doll envelopes).

---

### Problem 7: Unique Paths (2D Grid)

A robot at the top-left of an `m × n` grid moves only right or down to reach the bottom-right. Count distinct paths. Constraints: `1 ≤ m, n ≤ 100`.

**Approach.** `dp[i][j]` = paths to cell `(i,j)` = `dp[i-1][j] + dp[i][j-1]`. First row/column are all 1. Since each row needs only the row above, collapse to a 1D array.

```java
public int uniquePaths(int m, int n) {
    int[] dp = new int[n];
    Arrays.fill(dp, 1);              // top row: one path each
    for (int i = 1; i < m; i++) {
        for (int j = 1; j < n; j++) {
            dp[j] += dp[j - 1];      // dp[j]=above, dp[j-1]=left (already updated)
        }
    }
    return dp[n - 1];
}
```

**Dry run (m=3,n=3):** row1 [1,1,1] → row2 [1,2,3] → row3 [1,3,6]. Answer 6.

**Time:** O(m·n). **Space:** O(n).
**Follow-ups:** **Unique Paths II** (obstacles → set `dp[j]=0` on obstacle); minimum **path sum** (take min instead of sum, add cell cost); count paths with diagonal moves; combinatorial closed form C(m+n-2, m-1).

---

### Problem 8: 0/1 Knapsack

Given `n` items with `weight[i]` and `value[i]` and capacity `W`, maximize total value taking each item at most once. Constraints: `1 ≤ n ≤ 1000`, `1 ≤ W ≤ 10⁴`.

**Approach.** `dp[i][w]` = best value using first `i` items within capacity `w`: either skip item (`dp[i-1][w]`) or take it (`value[i] + dp[i-1][w-weight[i]]`). The "0/1" (each item once) is why we use the previous row. Collapse to 1D and iterate weight **descending** so each item is used at most once.

```java
public int knapsack(int[] weight, int[] value, int W) {
    int[] dp = new int[W + 1];
    for (int i = 0; i < weight.length; i++) {
        for (int w = W; w >= weight[i]; w--) {   // reverse → 0/1 semantics
            dp[w] = Math.max(dp[w], dp[w - weight[i]] + value[i]);
        }
    }
    return dp[W];
}
```

**Dry run (w=[1,3,4,5], v=[1,4,5,7], W=7):** optimal takes items of weight 3 and 4 → value 4+5 = 9. dp[7] resolves to 9.

**Time:** O(n·W). **Space:** O(W).
**Follow-ups:** **unbounded** knapsack (iterate weight *ascending*); **bounded** (binary-split counts); fractional (greedy, not DP); reconstruct chosen items (keep 2D table and backtrack). Note O(n·W) is *pseudo-polynomial*.

---

### Problem 9: Longest Common Subsequence

Given strings `a` and `b`, return the length of their longest common subsequence (characters in order, not necessarily contiguous). Constraints: `1 ≤ |a|,|b| ≤ 1000`.

**Approach.** `dp[i][j]` = LCS of `a[0..i)` and `b[0..j)`. If `a[i-1]==b[j-1]`, `dp[i][j]=dp[i-1][j-1]+1`; else `max(dp[i-1][j], dp[i][j-1])`. Only the previous row is needed → two rolling rows.

```java
public int longestCommonSubsequence(String a, String b) {
    int m = a.length(), n = b.length();
    int[] prev = new int[n + 1], cur = new int[n + 1];
    for (int i = 1; i <= m; i++) {
        for (int j = 1; j <= n; j++) {
            if (a.charAt(i - 1) == b.charAt(j - 1))
                cur[j] = prev[j - 1] + 1;
            else
                cur[j] = Math.max(prev[j], cur[j - 1]);
        }
        int[] tmp = prev; prev = cur; cur = tmp; // swap rows
    }
    return prev[n];
}
```

**Dry run ("abcde","ace"):** matches a,c,e → LCS length 3.

**Time:** O(m·n). **Space:** O(n).
**Follow-ups:** print the LCS (need full 2D table, backtrack); **longest common substring** (contiguous → reset to 0 on mismatch); shortest common supersequence (`m+n−LCS`); edit distance (next problem).

---

### Problem 10: Edit Distance (Levenshtein)

Find the minimum number of single-character insertions, deletions, or substitutions to turn `word1` into `word2`. Constraints: `0 ≤ |word1|,|word2| ≤ 500`.

**Approach.** `dp[i][j]` = edits to convert `word1[0..i)` to `word2[0..j)`. If last chars match, no cost: `dp[i-1][j-1]`. Else 1 + min of insert (`dp[i][j-1]`), delete (`dp[i-1][j]`), replace (`dp[i-1][j-1]`). Base: converting to/from empty string costs the other length.

```java
public int minDistance(String w1, String w2) {
    int m = w1.length(), n = w2.length();
    int[] prev = new int[n + 1];
    for (int j = 0; j <= n; j++) prev[j] = j;     // delete-all base row
    for (int i = 1; i <= m; i++) {
        int[] cur = new int[n + 1];
        cur[0] = i;                               // insert-all base column
        for (int j = 1; j <= n; j++) {
            if (w1.charAt(i - 1) == w2.charAt(j - 1))
                cur[j] = prev[j - 1];
            else
                cur[j] = 1 + Math.min(prev[j - 1], Math.min(prev[j], cur[j - 1]));
        }
        prev = cur;
    }
    return prev[n];
}
```

**Dry run ("horse"→"ros"):** horse→rorse (replace h→r) →rose (remove r) →ros (remove e) = 3.

**Time:** O(m·n). **Space:** O(n).
**Follow-ups:** weighted operation costs; **one edit distance** (O(n) two-pointer); Damerau–Levenshtein (adjacent transposition); reconstruct the edit script.

---

### Problem 11: Partition Equal Subset Sum

Determine whether `nums` can be split into two subsets with equal sum. Constraints: `1 ≤ n ≤ 200`, `1 ≤ nums[i] ≤ 100`.

**Approach.** Total must be even; target = total/2. This is **subset-sum**, a boolean 0/1 knapsack: `dp[s]` = can we form sum `s`? `dp[s] |= dp[s - num]`, iterating `s` **descending** so each number is used once.

```java
public boolean canPartition(int[] nums) {
    int total = 0;
    for (int x : nums) total += x;
    if ((total & 1) == 1) return false;
    int target = total / 2;
    boolean[] dp = new boolean[target + 1];
    dp[0] = true;
    for (int num : nums) {
        for (int s = target; s >= num; s--) {
            dp[s] = dp[s] || dp[s - num];
        }
    }
    return dp[target];
}
```

**Dry run ([1,5,11,5]):** total=22, target=11. Reachable sums include 11 (5+5+1 or 11). Returns true.

**Time:** O(n·sum). **Space:** O(sum).
**Follow-ups:** **Target Sum** (assign +/- signs → transform to subset sum); minimize the difference of two subset sums; count the number of subsets reaching target.

---

### Problem 12: Matrix Chain Multiplication (Interval DP)

Given dimensions where matrix `i` is `p[i-1] × p[i]`, find the minimum number of scalar multiplications to compute the product `A₁·A₂·…·Aₙ`. Constraints: `2 ≤ p.length ≤ 100`.

**Approach.** Multiplication is associative; only the parenthesization changes the cost. `dp[i][j]` = min cost to multiply matrices `i..j`. Try every split `k`: `dp[i][j] = min(dp[i][k] + dp[k+1][j] + p[i-1]·p[k]·p[j])`. Iterate by increasing chain length so sub-intervals are ready first — the canonical interval-DP order.

```java
public int matrixChainOrder(int[] p) {
    int n = p.length - 1;                 // number of matrices
    int[][] dp = new int[n + 1][n + 1];
    for (int len = 2; len <= n; len++) {
        for (int i = 1; i + len - 1 <= n; i++) {
            int j = i + len - 1;
            dp[i][j] = Integer.MAX_VALUE;
            for (int k = i; k < j; k++) {
                int cost = dp[i][k] + dp[k + 1][j] + p[i - 1] * p[k] * p[j];
                dp[i][j] = Math.min(dp[i][j], cost);
            }
        }
    }
    return dp[1][n];
}
```

**Dry run (p=[10,20,30,40,30]):** optimal parenthesization yields 30000 multiplications versus 88000+ for the worst order.

**Time:** O(n³). **Space:** O(n²).
**Follow-ups:** print the optimal parenthesization (store split index); generalizes to optimal BST and polygon triangulation. Same skeleton as the next problem.

---

### Problem 13: Burst Balloons (Hard, Interval DP)

Balloons `nums[i]` give coins `nums[left]·nums[i]·nums[right]` when burst, where left/right are the *currently adjacent* balloons. Maximize coins. Boundaries act as 1. Constraints: `1 ≤ n ≤ 300`.

**Approach.** The trick is to think about which balloon is burst **last** in a range, because then its neighbors are exactly the range boundaries (which never disappear). Pad with 1s. `dp[i][j]` = max coins from bursting all balloons strictly between `i` and `j`. For the last balloon `k`: `dp[i][j] = max(dp[i][k] + dp[k][j] + a[i]·a[k]·a[j])`.

```java
public int maxCoins(int[] nums) {
    int n = nums.length;
    int[] a = new int[n + 2];
    a[0] = a[n + 1] = 1;
    for (int i = 0; i < n; i++) a[i + 1] = nums[i];
    int[][] dp = new int[n + 2][n + 2];
    for (int len = 1; len <= n; len++) {           // window size between boundaries
        for (int i = 1; i + len - 1 <= n; i++) {
            int j = i + len - 1;                   // inclusive balloons i..j
            for (int k = i; k <= j; k++) {         // k burst last in (i-1, j+1)
                int coins = a[i - 1] * a[k] * a[j + 1]
                          + dp[i][k - 1] + dp[k + 1][j];
                dp[i][j] = Math.max(dp[i][j], coins);
            }
        }
    }
    return dp[1][n];
}
```

**Dry run ([3,1,5,8]):** optimal order bursts 1, then 5, then 3, then 8 → 3·1·5 + 3·5·8 + 1·3·8 + 1·8·1 = 167.

**Time:** O(n³). **Space:** O(n²).
**Follow-ups:** "remove boxes" (3D state, harder); minimum cost to merge stones; why "burst first" fails (neighbors change unpredictably) but "burst last" works (boundaries fixed).

---

### Problem 14: House Robber III (DP on Trees)

Houses form a binary tree; you cannot rob a node and its direct child on the same night. Maximize loot. Constraints: `1 ≤ nodes ≤ 10⁴`.

**Approach.** Tree DP via post-order DFS. Each node returns a pair: `{rob, skip}`. If we **rob** this node, we must skip both children: `node.val + leftSkip + rightSkip`. If we **skip** it, each child independently picks its better option: `max(leftRob, leftSkip) + max(rightRob, rightSkip)`.

```java
public int rob(TreeNode root) {
    int[] res = dfs(root);
    return Math.max(res[0], res[1]);
}
// returns {rob this node, skip this node}
private int[] dfs(TreeNode node) {
    if (node == null) return new int[]{0, 0};
    int[] l = dfs(node.left);
    int[] r = dfs(node.right);
    int rob  = node.val + l[1] + r[1];
    int skip = Math.max(l[0], l[1]) + Math.max(r[0], r[1]);
    return new int[]{rob, skip};
}
```

**Dry run (root 3, children 2 & 3, grandchildren 3 & 1):** rob root path = 3 + 3 + 1 = 7. Answer 7.

**Time:** O(n) (each node visited once). **Space:** O(h) recursion stack.
**Follow-ups:** **diameter of tree**, **max path sum**, **independent set** on a tree — all the same post-order "return info upward" template; convert to iterative DFS to avoid stack overflow on skewed trees.

---

### Problem 15: Traveling Salesman — Bitmask DP (Senior/Hard)

Given an `n × n` distance matrix, find the minimum-cost Hamiltonian cycle starting and ending at city 0 (visit every city exactly once). Constraints: `2 ≤ n ≤ 16`.

**Approach.** Brute force is `(n-1)!` permutations — infeasible past ~12. Bitmask DP encodes the **set of visited cities** as the bits of an integer. `dp[mask][i]` = min cost of a path that has visited exactly the cities in `mask` and currently sits at city `i`. Transition: extend to an unvisited city `j`. There are `2ⁿ · n` states and `n` transitions each → `O(2ⁿ · n²)`, feasible for n ≤ ~18.

```java
public int tsp(int[][] dist) {
    int n = dist.length, FULL = (1 << n) - 1;
    int[][] dp = new int[1 << n][n];
    for (int[] row : dp) Arrays.fill(row, Integer.MAX_VALUE / 2);
    dp[1][0] = 0;                                   // start at city 0, only it visited
    for (int mask = 1; mask <= FULL; mask++) {
        for (int i = 0; i < n; i++) {
            if (dp[mask][i] >= Integer.MAX_VALUE / 2) continue;
            if ((mask & (1 << i)) == 0) continue;   // i must be in the visited set
            for (int j = 0; j < n; j++) {
                if ((mask & (1 << j)) != 0) continue; // skip already-visited j
                int next = mask | (1 << j);
                dp[next][j] = Math.min(dp[next][j], dp[mask][i] + dist[i][j]);
            }
        }
    }
    int best = Integer.MAX_VALUE;
    for (int i = 1; i < n; i++)                      // close the cycle back to 0
        best = Math.min(best, dp[FULL][i] + dist[i][0]);
    return best;
}
```

**Dry run (4 cities):** `dp[0001][0]=0`; the algorithm grows masks one bit at a time, and `dp[1111][i] + dist[i][0]` over all `i` gives the optimal tour length.

**Time:** O(2ⁿ · n²). **Space:** O(2ⁿ · n).
**Follow-ups:** **Hamiltonian path** (drop the return edge); assignment problem; "shortest path visiting all nodes"; iterate masks low→high so subsets are always computed first (the key invariant); held–Karp is this exact algorithm.

---

## Interview Q&A by Level

### 🟢 Basic

**Q: What two properties make a problem solvable by DP?**
Optimal substructure (optimal whole built from optimal parts) and overlapping subproblems (the same subproblems recur). Without overlap, plain divide-and-conquer is enough.

**Q: Memoization vs tabulation — what's the difference?**
Memoization is top-down: recurse naturally and cache results, solving only reached subproblems. Tabulation is bottom-up: fill a table iteratively from base cases. Memoization is easier to write; tabulation avoids recursion overhead and enables space optimization.

**Q: Why does the naive recursive Fibonacci take exponential time?**
It recomputes overlapping subproblems — `f(n-2)` is computed by both `f(n)` and `f(n-1)`, branching into ~2ⁿ calls. Caching collapses it to O(n).

**Q: How do you estimate a DP's time complexity?**
States × work per transition. Climbing stairs: n states × O(1) = O(n). Edit distance: m·n states × O(1) = O(m·n).

### 🟡 Intermediate

**Q: In 0/1 knapsack with a 1D array, why iterate weight descending?**
Descending order ensures `dp[w-weight[i]]` still reflects the *previous* item row, so each item is counted at most once. Ascending order reuses the current item (that's the *unbounded* knapsack).

**Q: How do you convert a top-down solution to bottom-up?**
Identify the state and its dependencies, choose an iteration order so every dependency is computed first, replace recursion with loops, and translate base cases into the table's initial values.

**Q: When does greedy fail and force DP?**
When a locally optimal choice can be globally suboptimal. Coin change `[1,3,4]` for 6: greedy picks 4+1+1 (3 coins), DP finds 3+3 (2). If no exchange argument proves greedy correct, use DP.

**Q: Why is 0/1 knapsack called "pseudo-polynomial"?**
Its O(n·W) runtime is polynomial in the *value* W but exponential in the number of *bits* needed to represent W. That's why subset-sum (a knapsack special case) is NP-complete despite the DP.

### 🟠 Advanced

**Q: Explain the O(n log n) LIS algorithm.**
Maintain `tails[k]` = smallest tail of any increasing subsequence of length k+1. For each element, binary-search the first tail ≥ it and replace (or append). The array length is the LIS length. `tails` is not the actual subsequence but its minimal-tail invariant keeps options open.

**Q: What characterizes interval DP and what's the iteration order?**
The state is a range `[i..j]` whose answer depends on a split point inside. Iterate by **increasing interval length** so all shorter sub-intervals are solved first. Matrix chain and burst balloons are textbook cases; the latter's insight is to pick the balloon burst *last*.

**Q: How does bitmask DP represent state, and what's its limit?**
A subset of up to ~20 elements is encoded as integer bits; `dp[mask][...]` indexes by visited set. There are 2ⁿ masks, so n ≤ ~20 is the practical ceiling (held–Karp TSP: O(2ⁿ·n²)).

**Q: Describe the tree-DP template.**
Post-order DFS where each node returns aggregated info (often a tuple of "include / exclude" states) computed from its children's returns. Used for tree max-path-sum, independent set, and rerooting techniques.

### 🔴 Expert

**Q: How can edit distance run in O(min(m,n)) space yet still recover the alignment?**
Forward DP needs only two rows for the *value*. To reconstruct in linear space, use **Hirschberg's algorithm**: divide-and-conquer that computes the optimal split column via forward and reverse linear-space passes, achieving O(m·n) time and O(min(m,n)) space with full traceback.

**Q: What is the Knuth–Yao / divide-and-conquer optimization, and when does it apply?**
When the cost function satisfies the quadrangle inequality (monotone optimal split points), interval DP can drop from O(n³) to O(n²) (Knuth) and some 1D layered DPs from O(n²) to O(n log n) via divide-and-conquer or the convex-hull trick (for linear transitions). Used in optimal BST and certain partitioning problems.

**Q: How do you scale DP beyond a single machine or to huge state spaces?**
Options: (1) **state-space reduction** — prune dominated/unreachable states, exploit symmetry; (2) **approximate DP / value iteration** in RL where exact tables are infeasible; (3) **matrix exponentiation** to compute linear-recurrence DP (like Fibonacci-style counting) in O(log n); (4) for embarrassingly layered DPs, parallelize each layer across workers since cells within a layer are independent.

**Q: Real-world systems built on DP?**
Sequence alignment (Needleman–Wunsch, Smith–Waterman) in bioinformatics; `diff` and version-control merges (LCS); spell-check and fuzzy search (edit distance); speech recognition and HMM decoding (Viterbi); query optimizers (Selinger join ordering); resource allocation and scheduling (knapsack/bin-packing relaxations); seam carving for image resizing.

---

## ⚠️ Common Pitfalls

- **Wrong iteration direction.** 0/1 knapsack needs descending weight; unbounded needs ascending. Reversing it silently changes the problem.
- **Off-by-one in indices.** Sequence DP commonly uses `dp[i]` for the first `i` characters (size `n+1`); mixing 0-based and 1-based access corrupts base cases.
- **Forgetting base cases or initializing to the wrong sentinel.** Min-DP should start at `+∞` (use a safe value like `amount+1`, not `Integer.MAX_VALUE` which overflows on `+1`).
- **Stale reads after space optimization.** When collapsing to 1D, confirm whether a cell needs the *old* or *new* value before you overwrite it (LCS needs the diagonal from the previous row — save it).
- **Treating decode/zero edge cases carelessly.** Leading zeros, "0" alone, and "30" (not decodable) trip up Decode Ways.
- **Using DP where greedy suffices (or vice versa).** Activity selection is greedy; coin change with arbitrary coins is DP. Misjudging wastes time or produces wrong answers.
- **Stack overflow in deep memoized recursion.** Skewed trees or n≈10⁵ chains can blow the call stack — switch to tabulation or an explicit stack.
- **Ignoring pseudo-polynomial blow-up.** O(n·W) explodes when W is large (e.g. 10⁹); you may need meet-in-the-middle or a different model.

---

## 📚 Further Reading

- *Introduction to Algorithms* (CLRS), Ch. 14–15 — DP foundations, matrix chain, LCS, optimal BST.
- *Algorithm Design* (Kleinberg & Tardos), Ch. 6 — DP design philosophy and reductions.
- *Competitive Programmer's Handbook* (Antti Laaksonen) — concise DP, bitmask DP, and optimizations.
- *Dynamic Programming for Coding Interviews* (Meenakshi & Kamal Rawat) — interview-focused drills.
- LeetCode **DP Study Plan** and Topic tag; AtCoder **Educational DP Contest (EDPC)** — 26 graded DP problems.
- Erik Demaine's **MIT 6.006 / 6.046** DP lectures (free online) — top-down/bottom-up and parent-pointer reconstruction.
- USACO Guide (Gold/Platinum) — interval, bitmask, digit, and tree DP with editorials.

[← Back to master index](../README.md) | [← DSA index](README.md)
