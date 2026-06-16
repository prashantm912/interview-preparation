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

## 🧩 Extended Problems — Set 1: Classic Easy → Medium

### Problem 16: Fibonacci Number — Bottom-Up Rolling Variables

**Statement.** Compute the `n`-th Fibonacci number where `F(0)=0`, `F(1)=1`, and `F(n)=F(n-1)+F(n-2)`.

**Constraints.** `0 ≤ n ≤ 30` (fits in `int`; use `long` if `n` grows).

**Approach.** The naive recursion `F(n)=F(n-1)+F(n-2)` recomputes the same subproblems exponentially (O(φⁿ)). Because each state depends only on the two immediately preceding ones, we never need the full table — two rolling variables capture all the state we read. We iterate forward from the base cases, which is the canonical "linear recurrence with constant window" pattern and is provably optimal: every Fibonacci number must be touched at least once, so O(n) is a lower bound for this iterative formulation.

```java
public int fib(int n) {
    if (n < 2) return n;          // base cases F(0)=0, F(1)=1
    int prev2 = 0, prev1 = 1;     // F(i-2), F(i-1)
    for (int i = 2; i <= n; i++) {
        int cur = prev1 + prev2;
        prev2 = prev1;
        prev1 = cur;
    }
    return prev1;
}
```

**Complexity.** Time O(n), space O(1). **Edge cases:** `n=0` returns 0 and `n=1` returns 1 via the early return; for very large `n` the value overflows `int` (switch to `long`/`BigInteger`, or use matrix exponentiation for O(log n)).

---

### Problem 17: Tribonacci Number — Three-Variable Recurrence

**Statement.** The Tribonacci sequence is `T(0)=0, T(1)=1, T(2)=1`, and `T(n)=T(n-1)+T(n-2)+T(n-3)`. Return `T(n)`.

**Constraints.** `0 ≤ n ≤ 37` (answer fits in a 32-bit signed integer).

**Approach.** Identical philosophy to Fibonacci but with a window of three. Each state depends on the previous three, so we keep three rolling variables and slide them forward. This avoids both the exponential recursion blow-up and the O(n) array of full tabulation. The only subtlety versus Fibonacci is the extra base case `T(2)=1`, which we seed before the loop.

```
window slides each step:
[t0  t1  t2] -> cur = t0+t1+t2
 drop t0, shift left, append cur
```

```java
public int tribonacci(int n) {
    if (n == 0) return 0;
    if (n <= 2) return 1;                 // T(1)=T(2)=1
    int t0 = 0, t1 = 1, t2 = 1;
    for (int i = 3; i <= n; i++) {
        int cur = t0 + t1 + t2;
        t0 = t1; t1 = t2; t2 = cur;
    }
    return t2;
}
```

**Complexity.** Time O(n), space O(1). **Edge cases:** `n` of 0, 1, 2 are handled by the early returns; the result stays within `int` for the stated constraint (`T(37) = 1132436852`).

---

### Problem 18: N-th Tribonacci as Stairs — Climbing Stairs with 1/2/3 Steps

**Statement.** You can climb 1, 2, or 3 steps at a time. Count the distinct ways to reach the top of an `n`-step staircase.

**Constraints.** `0 ≤ n ≤ 37` (answer fits in `int`).

**Approach.** Counting problems that allow a fixed set of step sizes are classic additive DP: `ways(n) = ways(n-1) + ways(n-2) + ways(n-3)`, because the last move is one of 1/2/3 steps, and those are mutually exclusive, disjoint sets of paths. Base cases: `ways(0)=1` (the single empty climb), `ways(1)=1`, `ways(2)=2`. This is the "count the number of ways" signal answered by summing over choices, space-optimized to three variables.

```java
public int climbStairsThree(int n) {
    if (n == 0) return 1;
    if (n == 1) return 1;
    if (n == 2) return 2;
    int w0 = 1, w1 = 1, w2 = 2;   // ways to reach steps 0,1,2
    for (int i = 3; i <= n; i++) {
        int cur = w0 + w1 + w2;
        w0 = w1; w1 = w2; w2 = cur;
    }
    return w2;
}
```

**Complexity.** Time O(n), space O(1). **Edge cases:** `n=0` (one way: do nothing); watch for overflow if step sizes or `n` are increased — promote to `long`.

---

### Problem 19: Maximum Subarray — Kadane's Algorithm

**Statement.** Find the contiguous subarray with the largest sum and return that sum.

**Constraints.** `1 ≤ n ≤ 10⁵`, `-10⁴ ≤ nums[i] ≤ 10⁴`; the array can be entirely negative.

**Approach.** Define `dp[i]` = the maximum sum of a subarray **ending exactly at** index `i`. The choice at each step is whether to extend the previous best subarray or start fresh at `i`: `dp[i] = max(nums[i], dp[i-1] + nums[i])`. The global answer is `max(dp[i])`. Because `dp[i]` depends only on `dp[i-1]`, we keep a single running value (Kadane's algorithm). This is optimal — a single O(n) pass — and correctly handles all-negative inputs because we never force-include an empty subarray.

```java
public int maxSubArray(int[] nums) {
    int best = nums[0];      // global maximum
    int cur = nums[0];       // best sum ending at current index
    for (int i = 1; i < nums.length; i++) {
        cur = Math.max(nums[i], cur + nums[i]);   // extend or restart
        best = Math.max(best, cur);
    }
    return best;
}
```

**Dry run ([-2,1,-3,4,-1,2,1,-5,4]):** cur evolves -2,1,-2,4,3,5,6,1,5; best peaks at 6 for subarray [4,-1,2,1].

**Complexity.** Time O(n), space O(1). **Edge cases:** single element returns that element; all-negative arrays return the least-negative value (we seed with `nums[0]`, never 0). To recover indices, track the start when `cur` restarts.

---

### Problem 20: Maximum Product Subarray — Track Min and Max

**Statement.** Find the contiguous subarray with the largest **product** and return that product.

**Constraints.** `1 ≤ n ≤ 2·10⁴`, `-10 ≤ nums[i] ≤ 10`; the product fits in a 32-bit integer for the given test set.

**Approach.** Unlike sum, a negative number flips the sign, so the smallest (most negative) running product can become the largest after multiplying by another negative. We therefore track **both** `maxEnd` and `minEnd` ending at each index. On encountering `x`, the candidates are `x`, `maxEnd*x`, and `minEnd*x`; the new max is the largest, the new min the smallest. Zeros naturally reset both to `x`. This generalizes Kadane to multiplicative state where sign matters.

```java
public int maxProduct(int[] nums) {
    int maxEnd = nums[0], minEnd = nums[0], best = nums[0];
    for (int i = 1; i < nums.length; i++) {
        int x = nums[i];
        if (x < 0) { int t = maxEnd; maxEnd = minEnd; minEnd = t; } // swap on negative
        maxEnd = Math.max(x, maxEnd * x);
        minEnd = Math.min(x, minEnd * x);
        best = Math.max(best, maxEnd);
    }
    return best;
}
```

**Dry run ([2,3,-2,4]):** at -2, swap then maxEnd=max(-2, ...)=-2, minEnd=-12; at 4, maxEnd=max(4,-8)=4, best stays 6 from prefix [2,3].

**Complexity.** Time O(n), space O(1). **Edge cases:** single element; arrays with zeros (reset); pairs of negatives that turn into a large positive product; the swap-on-negative trick avoids a separate branch for each ordering.

---

### Problem 21: House Robber II — Circular Street

**Statement.** Same robbery rules (no two adjacent houses), but the houses are arranged in a **circle**, so the first and last houses are adjacent.

**Constraints.** `1 ≤ n ≤ 100`, `0 ≤ nums[i] ≤ 1000`.

**Approach.** The circular adjacency means we cannot rob both house `0` and house `n-1`. Split into two independent linear House-Robber problems: (a) houses `0..n-2` (allow first, forbid last) and (b) houses `1..n-1` (forbid first, allow last). The answer is the max of the two. Each linear pass is the standard `dp[i] = max(dp[i-1], dp[i-2] + nums[i])` rolled into two variables. A single house is a special case handled before splitting.

```java
public int rob(int[] nums) {
    int n = nums.length;
    if (n == 1) return nums[0];
    return Math.max(robLine(nums, 0, n - 2),   // exclude last
                    robLine(nums, 1, n - 1));   // exclude first
}
private int robLine(int[] nums, int lo, int hi) {
    int prev2 = 0, prev1 = 0;
    for (int i = lo; i <= hi; i++) {
        int cur = Math.max(prev1, prev2 + nums[i]);
        prev2 = prev1;
        prev1 = cur;
    }
    return prev1;
}
```

**Dry run ([2,3,2]):** line[0..1]=max(2,3)=3; line[1..2]=max(3,2)=3; answer 3 (cannot take both 2s as they are circularly adjacent).

**Complexity.** Time O(n), space O(1). **Edge cases:** `n=1` (return the only house); `n=2` (the two ranges each reduce to a single house, correctly returning `max(nums[0], nums[1])`).

---

### Problem 22: Coin Change II — Count Combinations

**Statement.** Given coin denominations and a target `amount`, count the number of distinct **combinations** (order does not matter) that sum to `amount`. Each coin may be used unlimited times.

**Constraints.** `1 ≤ coins.length ≤ 300`, `0 ≤ amount ≤ 5000`, distinct coin values.

**Approach.** This is unbounded knapsack in *counting* mode. `dp[a]` = number of ways to make amount `a`. The crucial ordering: loop **coins on the outside, amount on the inside**. Processing one coin fully before the next guarantees each combination is counted once in a canonical order (non-decreasing coin index), avoiding the permutation double-counting that the inner-coins ordering would cause. Inner loop runs ascending so a coin can be reused (unbounded semantics).

```
coins outer => each combination uses coins in fixed order
=> {1,2} and {2,1} counted as the SAME combination
```

```java
public int change(int amount, int[] coins) {
    int[] dp = new int[amount + 1];
    dp[0] = 1;                          // one way to make 0: take nothing
    for (int c : coins) {               // coins OUTER
        for (int a = c; a <= amount; a++) {
            dp[a] += dp[a - c];
        }
    }
    return dp[amount];
}
```

**Dry run (amount=5, coins=[1,2,5]):** after coin 1 → all dp[a]=1; after coin 2 → dp=[1,1,2,2,3,3]; after coin 5 → dp[5]=4. Combos: {5},{2,2,1},{2,1,1,1},{1×5}.

**Complexity.** Time O(amount · coins), space O(amount). **Edge cases:** `amount=0` returns 1; if no combination exists `dp[amount]=0`; contrast with Coin Change I (minimum coins) which uses the opposite intent and inner ordering does not matter there.

---

### Problem 23: Combination Sum IV — Count Permutations

**Statement.** Given an array of distinct positive integers `nums` and a `target`, count the number of ordered sequences (permutations of choices) that sum to `target`. Elements may be reused.

**Constraints.** `1 ≤ nums.length ≤ 200`, `1 ≤ nums[i] ≤ 1000`, `1 ≤ target ≤ 1000`; the answer fits in a 32-bit integer for the given tests.

**Approach.** This is the mirror image of Coin Change II: here **order matters**, so `(1,3)` and `(3,1)` are different. The fix is to swap the loop nesting — put **target on the outside, nums on the inside**. `dp[t] = Σ dp[t - num]` for every `num ≤ t`, because any sequence summing to `t` ends with some `num`, and the prefix is an independently counted sequence summing to `t-num`. This is the classic "loop order encodes combinations vs permutations" lesson.

```java
public int combinationSum4(int[] nums, int target) {
    int[] dp = new int[target + 1];
    dp[0] = 1;                          // empty sequence sums to 0
    for (int t = 1; t <= target; t++) { // target OUTER
        for (int num : nums) {
            if (num <= t) dp[t] += dp[t - num];
        }
    }
    return dp[target];
}
```

**Dry run (nums=[1,2,3], target=4):** dp=[1,1,2,4,7]. The 7 sequences for 4 include (1,3),(3,1),(2,2),(1,1,2),(1,2,1),(2,1,1),(1,1,1,1).

**Complexity.** Time O(target · n), space O(target). **Edge cases:** `target` reachable by a single element; potential overflow if intermediate sums exceed `int` (the problem guarantees the final fits, but a defensive `long dp[]` is wise); contrast loop ordering directly against Problem 22.

---

### Problem 24: Word Break — Boolean Sequence DP

**Statement.** Given a string `s` and a dictionary of words, determine whether `s` can be segmented into a space-separated sequence of one or more dictionary words.

**Constraints.** `1 ≤ |s| ≤ 300`, `1 ≤ wordDict.size() ≤ 1000`, `1 ≤ word length ≤ 20`.

**Approach.** `dp[i]` = can the prefix `s[0..i)` be fully segmented? `dp[0]=true` (empty prefix). For each end `i`, scan split points `j < i`: if `dp[j]` is true and `s[j..i)` is in the dictionary, then `dp[i]=true`. Store the dictionary in a `HashSet` for O(1) lookups. This is the canonical boolean "is it possible to partition" DP; greedy/leftmost-longest matching fails (e.g. dictionary `{a, aa, aaa}` style traps).

```java
public boolean wordBreak(String s, List<String> wordDict) {
    Set<String> dict = new HashSet<>(wordDict);
    int n = s.length();
    boolean[] dp = new boolean[n + 1];
    dp[0] = true;
    for (int i = 1; i <= n; i++) {
        for (int j = 0; j < i; j++) {
            if (dp[j] && dict.contains(s.substring(j, i))) {
                dp[i] = true;
                break;                  // one valid split is enough
            }
        }
    }
    return dp[n];
}
```

**Dry run (s="leetcode", dict=["leet","code"]):** dp[4]=true via "leet"; dp[8]=true via dp[4]+"code". Returns true.

**Complexity.** Time O(n² · L) where L is the substring/hash cost, space O(n) plus the set. **Edge cases:** empty dictionary (false unless `s` empty); repeated/overlapping words; to bound inner work, limit `j` to `i - maxWordLen`.

---

### Problem 25: Minimum Path Sum — Grid DP

**Statement.** Given an `m × n` grid of non-negative numbers, find a path from top-left to bottom-right that minimizes the sum of values along it, moving only right or down.

**Constraints.** `1 ≤ m, n ≤ 200`, `0 ≤ grid[i][j] ≤ 200`.

**Approach.** `dp[i][j]` = minimum cost to reach cell `(i,j)` = `grid[i][j] + min(dp[i-1][j], dp[i][j-1])`. The first row and column have only one way in (accumulate). Since each row needs only the row above and the cell to the left, we collapse to a 1D array updated in place: `dp[j] += min(dp[j] (above), dp[j-1] (left))`. This is the min-cost twin of Unique Paths.

```
dp[j-1]  dp[j]      <- dp[j] currently holds the "above" value
   \      |            after update dp[j] = grid + min(above, left)
    \     v
     -> dp[j]
```

```java
public int minPathSum(int[][] grid) {
    int m = grid.length, n = grid[0].length;
    int[] dp = new int[n];
    dp[0] = grid[0][0];
    for (int j = 1; j < n; j++) dp[j] = dp[j - 1] + grid[0][j];  // first row
    for (int i = 1; i < m; i++) {
        dp[0] += grid[i][0];                                     // first column
        for (int j = 1; j < n; j++) {
            dp[j] = grid[i][j] + Math.min(dp[j], dp[j - 1]);     // above vs left
        }
    }
    return dp[n - 1];
}
```

**Dry run ([[1,3,1],[1,5,1],[4,2,1]]):** optimal path 1→3→1→1→1 = 7.

**Complexity.** Time O(m·n), space O(n). **Edge cases:** single cell (return it); single row/column (pure prefix sum); negative weights would require Bellman-Ford-style handling, not this monotone DP.

---

### Problem 26: Triangle — Minimum Path Sum Bottom-Up

**Statement.** Given a triangle represented as a list of rows, find the minimum path sum from top to bottom, where from index `j` in a row you may move to index `j` or `j+1` in the next row.

**Constraints.** `1 ≤ rows ≤ 200`, row `i` has `i+1` elements, `-10⁴ ≤ value ≤ 10⁴`.

**Approach.** Working top-down forces awkward boundary handling, so we go **bottom-up**: initialize `dp` with the last row, then for each row above, `dp[j] = triangle[i][j] + min(dp[j], dp[j+1])`. Because the next row is shorter by one, the two children `dp[j]` and `dp[j+1]` are always valid and we can overwrite in place left-to-right. After processing the top row, `dp[0]` holds the answer. This uses a single 1D array sized to the last (widest) row.

```
row i:        a   b   c        dp[j] = val + min(child_left, child_right)
row i+1:     w   x   y   z     children of b are x and y
```

```java
public int minimumTotal(List<List<Integer>> triangle) {
    int n = triangle.size();
    int[] dp = new int[n + 1];                 // extra slot avoids bounds check
    for (int i = n - 1; i >= 0; i--) {
        List<Integer> row = triangle.get(i);
        for (int j = 0; j <= i; j++) {
            dp[j] = row.get(j) + Math.min(dp[j], dp[j + 1]);
        }
    }
    return dp[0];
}
```

**Dry run ([[2],[3,4],[6,5,7],[4,1,8,3]]):** bottom-up dp → row2 becomes [7,6,10], row1 [9,10], top 2+min(9,10)=11.

**Complexity.** Time O(n²) over all elements, space O(n). **Edge cases:** single-row triangle returns its only element; negative values are fine since we only ever take minimums; the trailing zero slot lets `dp[j+1]` read safely on the bottom row.

---

### Problem 27: Best Time to Buy and Sell Stock — One Transaction

**Statement.** Given daily prices, maximize profit from a single buy followed by a later sell. If no profit is possible, return 0.

**Constraints.** `1 ≤ n ≤ 10⁵`, `0 ≤ price ≤ 10⁴`.

**Approach.** Frame it as a one-pass DP over two states: `minPrice` = cheapest day seen so far (best buy), and `best` = max profit achievable if we sell today. For each price, `best = max(best, price - minPrice)`, then update `minPrice`. This is a degenerate state machine (hold-cash vs hold-stock) collapsed to two scalars. It is optimal at O(n) because every price must be examined to know the running minimum.

```java
public int maxProfit(int[] prices) {
    int minPrice = Integer.MAX_VALUE;   // best buy so far
    int best = 0;                       // max profit so far
    for (int p : prices) {
        if (p < minPrice) minPrice = p;          // cheaper buy
        else best = Math.max(best, p - minPrice); // sell today
    }
    return best;
}
```

**Dry run ([7,1,5,3,6,4]):** minPrice tracks 7→1; best peaks at 6-1=5 (buy day 1, sell day 4).

**Complexity.** Time O(n), space O(1). **Edge cases:** strictly decreasing prices return 0 (never sell at a loss); single day returns 0; the `else` branch correctly never sells on the same day we found a new minimum.

---

### Problem 28: Best Time to Buy and Sell Stock with Cooldown — State Machine DP

**Statement.** You may complete as many transactions as you like, but after you sell you must rest one day (cooldown) before buying again. Maximize total profit.

**Constraints.** `1 ≤ n ≤ 5000`, `0 ≤ price ≤ 1000`.

**Approach.** Model three daily states: `hold` = max profit while holding a share, `sold` = max profit on the day we just sold (cooldown begins tomorrow), and `rest` = max profit while idle and free to buy. Transitions per day with price `p`:
`hold' = max(hold, rest - p)` (keep, or buy from a rested state),
`sold' = hold + p` (sell what we held),
`rest' = max(rest, sold)` (stay idle, or finish a cooldown). The answer is `max(sold, rest)` at the end, since we never want to finish holding a share.

```
   buy            sell
rest ----> hold ------> sold
 ^  \______________________/ cooldown (sold -> rest next day)
 |__________ stay idle ______|
```

```java
public int maxProfit(int[] prices) {
    int hold = Integer.MIN_VALUE / 2;   // can't hold before buying
    int sold = 0, rest = 0;
    for (int p : prices) {
        int prevSold = sold;
        sold = hold + p;                       // sell today
        hold = Math.max(hold, rest - p);        // keep or buy (from rest)
        rest = Math.max(rest, prevSold);        // idle or just finished cooldown
    }
    return Math.max(sold, rest);
}
```

**Dry run ([1,2,3,0,2]):** optimal buy@1, sell@3, cooldown@0, buy@0? actually buy@1 sell@2-... best total profit = 3 (buy 1 sell 2, cooldown, buy 0 sell 2).

**Complexity.** Time O(n), space O(1). **Edge cases:** single day yields 0; the `MIN_VALUE/2` sentinel prevents overflow when adding `p`; using `prevSold` ensures the cooldown rule (a sell-day cannot transition straight back to buying).

---

## 🧩 Extended Problems — Set 2: Medium → Hard Variations & Follow-ups

### Problem 29: Best Time to Buy and Sell Stock IV — At Most k Transactions

**Statement.** Given daily `prices` and an integer `k`, maximize profit using at most `k` buy/sell transactions (you must sell before buying again, one share at a time).

**Constraints.** `0 ≤ k ≤ 100`, `1 ≤ n ≤ 1000`, `0 ≤ price ≤ 1000`.

**Approach.** Start from the natural 2D table `dp[t][i]` = best profit using at most `t` transactions over the first `i` days. The transition either rests (`dp[t][i-1]`) or sells on day `i` having bought on some earlier day `j`: `dp[t][i] = max(dp[t][i-1], price[i] + max_{j<i}(dp[t-1][j] - price[j]))`. The naive triple loop is O(k·n²). The optimization keeps a running `best = max(dp[t-1][j] - price[j])` as `i` advances, dropping a loop to O(k·n). We then collapse to two rolling rows. A key shortcut: when `k ≥ n/2` there is no transaction limit, so we fall back to the simple "sum every positive delta" greedy — this prevents allocating a huge table for large `k`.

```java
public int maxProfit(int k, int[] prices) {
    int n = prices.length;
    if (n == 0 || k == 0) return 0;
    if (k >= n / 2) {                       // unlimited transactions
        int profit = 0;
        for (int i = 1; i < n; i++)
            if (prices[i] > prices[i - 1]) profit += prices[i] - prices[i - 1];
        return profit;
    }
    int[] prev = new int[n];                // dp for t-1 transactions
    int[] cur = new int[n];
    for (int t = 1; t <= k; t++) {
        int best = -prices[0];              // max(dp[t-1][j] - price[j]) so far
        cur[0] = 0;
        for (int i = 1; i < n; i++) {
            cur[i] = Math.max(cur[i - 1], prices[i] + best);
            best = Math.max(best, prev[i] - prices[i]);
        }
        int[] tmp = prev; prev = cur; cur = tmp;   // swap rows
    }
    return prev[n - 1];
}
```

**Dry run (k=2, prices=[3,2,6,5,0,3]):** transaction 1 captures 6-2=4; transaction 2 captures 3-0=3; total 7.

**Complexity.** Time O(k·n) (after the `k ≥ n/2` shortcut), space O(n). **Edge cases:** `k=0` or empty prices → 0; the `k ≥ n/2` branch avoids both overflow and an O(k·n) table when `k` is enormous relative to `n`; monotone-decreasing prices yield 0.

---

### Problem 30: Longest Palindromic Subsequence — Interval / LCS Variant

**Statement.** Return the length of the longest subsequence of string `s` that reads the same forwards and backwards.

**Constraints.** `1 ≤ |s| ≤ 1000`, lowercase English letters.

**Approach.** Two equivalent views. (1) **LCS view:** the longest palindromic subsequence equals `LCS(s, reverse(s))` — O(n²) and easy to remember. (2) **Interval-DP view (shown below):** `dp[i][j]` = LPS length within `s[i..j]`. If the ends match, `dp[i][j] = 2 + dp[i+1][j-1]`; otherwise `dp[i][j] = max(dp[i+1][j], dp[i][j-1])`. We fill by increasing substring length so the smaller intervals `[i+1][j-1]`, `[i+1][j]`, `[i][j-1]` are ready. The interval form makes reconstruction and the "ends match" logic explicit, which is why it is preferred when you must also recover the palindrome.

```
fill order = by interval length:
len 1 (diagonal) -> len 2 -> ... -> len n
dp[i][j] reads dp[i+1][j-1] (down-left), dp[i+1][j] (down), dp[i][j-1] (left)
```

```java
public int longestPalindromeSubseq(String s) {
    int n = s.length();
    int[][] dp = new int[n][n];
    for (int i = n - 1; i >= 0; i--) {
        dp[i][i] = 1;                                   // single char palindrome
        for (int j = i + 1; j < n; j++) {
            if (s.charAt(i) == s.charAt(j))
                dp[i][j] = 2 + dp[i + 1][j - 1];
            else
                dp[i][j] = Math.max(dp[i + 1][j], dp[i][j - 1]);
        }
    }
    return dp[0][n - 1];
}
```

**Dry run ("bbbab"):** the answer is 4 ("bbbb"); ends "b...b" match repeatedly, accumulating length.

**Complexity.** Time O(n²), space O(n²) (reducible to O(n) with two rows). **Edge cases:** single char → 1; no repeated characters → 1; iterating `i` descending guarantees `dp[i+1][...]` is already computed.

---

### Problem 31: Palindromic Substrings — Count Contiguous Palindromes

**Statement.** Count how many **contiguous substrings** of `s` are palindromes (each occurrence counted separately).

**Constraints.** `1 ≤ |s| ≤ 1000`.

**Approach.** The boolean DP `pal[i][j]` = is `s[i..j]` a palindrome? holds when `s[i]==s[j]` and the inside `pal[i+1][j-1]` is a palindrome (with lengths ≤ 2 being trivially palindromic). Count every `true`. This is O(n²) time and space. A cleaner O(1)-space alternative is **expand-around-center**: every palindrome has a center (a character for odd length, a gap for even length); there are `2n-1` centers, and expanding each costs O(n). Both are O(n²) time; the center method is shown for its lower memory and because it directly enumerates each palindrome.

```
2n-1 centers:  a b a
odd center at each char; even center between each pair
expand outward while s[l]==s[r]
```

```java
public int countSubstrings(String s) {
    int n = s.length(), count = 0;
    for (int center = 0; center < 2 * n - 1; center++) {
        int l = center / 2;
        int r = l + center % 2;                 // odd: r=l ; even: r=l+1
        while (l >= 0 && r < n && s.charAt(l) == s.charAt(r)) {
            count++;
            l--; r++;
        }
    }
    return count;
}
```

**Dry run ("aaa"):** centers yield "a","a","a","aa","aa","aaa" = 6 palindromic substrings.

**Complexity.** Time O(n²), space O(1). **Edge cases:** single char → 1; all-identical string gives `n(n+1)/2`; Manacher's algorithm pushes this to O(n) if needed.

---

### Problem 32: Longest Common Substring — Contiguous Match

**Statement.** Given strings `a` and `b`, return the length of their longest **contiguous** common substring (not subsequence).

**Constraints.** `1 ≤ |a|,|b| ≤ 1000`.

**Approach.** This is the contiguous cousin of LCS. `dp[i][j]` = length of the longest common suffix of `a[0..i)` and `b[0..j)`. When `a[i-1]==b[j-1]`, the run extends: `dp[i][j] = dp[i-1][j-1] + 1`; on any mismatch the run **resets to 0** (the difference from LCS, where a mismatch carries forward the max). Track the global maximum. Because each row depends only on the previous row, collapse to two rolling rows.

```java
public int longestCommonSubstring(String a, String b) {
    int m = a.length(), n = b.length(), best = 0;
    int[] prev = new int[n + 1], cur = new int[n + 1];
    for (int i = 1; i <= m; i++) {
        for (int j = 1; j <= n; j++) {
            if (a.charAt(i - 1) == b.charAt(j - 1)) {
                cur[j] = prev[j - 1] + 1;
                best = Math.max(best, cur[j]);
            } else {
                cur[j] = 0;                     // reset on mismatch
            }
        }
        int[] tmp = prev; prev = cur; cur = tmp;
        Arrays.fill(cur, 0);                    // clear reused row
    }
    return best;
}
```

**Dry run ("abcde","abfce"):** longest contiguous match is "ab" → length 2 (note "ce" is not contiguous in both).

**Complexity.** Time O(m·n), space O(n). **Edge cases:** no common character → 0; the explicit `Arrays.fill(cur,0)` after the swap is essential so a stale value from two rows ago is not read as a live suffix length.

---

### Problem 33: Distinct Subsequences — Count Occurrences of t in s

**Statement.** Given strings `s` and `t`, count the number of distinct subsequences of `s` that equal `t`.

**Constraints.** `1 ≤ |s|,|t| ≤ 1000`; the answer fits in a 32-bit signed integer for the given tests.

**Approach.** `dp[i][j]` = number of ways the first `i` characters of `s` form the first `j` characters of `t`. We always have the option to **skip** `s[i-1]` (`dp[i-1][j]`). If `s[i-1]==t[j-1]` we may additionally **use** it, matching against `t[j-1]` (`+ dp[i-1][j-1]`). Base: `dp[i][0]=1` (the empty target is matched exactly one way — delete everything). Iterating `j` **descending** lets us compress to a single 1D array, because `dp[j]` then still holds the previous row's `dp[j-1]` when read.

```java
public int numDistinct(String s, String t) {
    int n = t.length();
    long[] dp = new long[n + 1];
    dp[0] = 1;                                  // empty t matched one way
    for (int i = 1; i <= s.length(); i++) {
        char cs = s.charAt(i - 1);
        for (int j = n; j >= 1; j--) {          // descending => 1D safe
            if (cs == t.charAt(j - 1)) dp[j] += dp[j - 1];
        }
    }
    return (int) dp[n];
}
```

**Dry run (s="rabbbit", t="rabbit"):** the three `b`s give 3 distinct ways to pick the two `b`s in "rabbit"; answer 3.

**Complexity.** Time O(|s|·|t|), space O(|t|). **Edge cases:** `t` longer than `s` → 0; identical strings → 1; we use `long` internally because counts can grow large before fitting back into `int`.

---

### Problem 34: Interleaving String — 2D Boolean DP

**Statement.** Given `s1`, `s2`, `s3`, decide whether `s3` is formed by interleaving `s1` and `s2` while preserving the relative order of characters within each.

**Constraints.** `0 ≤ |s1|,|s2| ≤ 100`, `|s3| ≤ 200`.

**Approach.** A necessary first check: `|s1| + |s2| == |s3|`, else immediately false. `dp[i][j]` = can `s3[0..i+j)` be formed from `s1[0..i)` and `s2[0..j)`? The last character of that prefix of `s3` came either from `s1` (so `dp[i-1][j]` and `s1[i-1]==s3[i+j-1]`) or from `s2` (so `dp[i][j-1]` and `s2[j-1]==s3[i+j-1]`). Greedy matching fails because a character available in both strings forces a branch — exactly the overlapping-subproblem signal. We compress to a 1D array over `j`.

```
        s2 ->
   . a b c
 . T
s1 d
 |  a
 v  e
dp[j] uses dp[j] (from above = s1 move) and dp[j-1] (from left = s2 move)
```

```java
public boolean isInterleave(String s1, String s2, String s3) {
    int m = s1.length(), n = s2.length();
    if (m + n != s3.length()) return false;
    boolean[] dp = new boolean[n + 1];
    for (int i = 0; i <= m; i++) {
        for (int j = 0; j <= n; j++) {
            if (i == 0 && j == 0) { dp[j] = true; continue; }
            boolean fromS1 = i > 0 && dp[j] && s1.charAt(i - 1) == s3.charAt(i + j - 1);
            boolean fromS2 = j > 0 && dp[j - 1] && s2.charAt(j - 1) == s3.charAt(i + j - 1);
            dp[j] = fromS1 || fromS2;
        }
    }
    return dp[n];
}
```

**Dry run (s1="aab", s2="axy", s3="aaxaby"):** length 3+3=6 matches; the DP threads characters and returns true.

**Complexity.** Time O(m·n), space O(n). **Edge cases:** length mismatch short-circuits to false; empty `s1` or `s2` reduces to a direct equality check; in the 1D form `dp[j]` (above) must be read before it is overwritten — the loop order already guarantees that.

---

### Problem 35: Regular Expression Matching — '.' and '*' DP

**Statement.** Implement matching for pattern `p` against string `s`, where `.` matches any single character and `*` matches zero or more of the **preceding** element. The match must cover the entire string.

**Constraints.** `1 ≤ |s| ≤ 20`, `1 ≤ |p| ≤ 30`; `p` is well-formed (`*` always follows a valid token).

**Approach.** `dp[i][j]` = does `s[0..i)` match `p[0..j)`? The tricky token is `*`, which pairs with `p[j-2]`. Two sub-cases when `p[j-1]=='*'`: (1) **zero occurrences** — drop the `x*` pair: `dp[i][j-2]`; (2) **one or more** — if `p[j-2]` matches `s[i-1]` (equal or `.`), consume one char of `s`: `dp[i-1][j]`. Otherwise (`p[j-1]` is a normal char or `.`) it is a plain single-char match: `dp[i-1][j-1]` gated on the characters matching. The base row must let patterns like `a*b*` match the empty string.

```java
public boolean isMatch(String s, String p) {
    int m = s.length(), n = p.length();
    boolean[][] dp = new boolean[m + 1][n + 1];
    dp[0][0] = true;
    for (int j = 1; j <= n; j++)                 // empty s vs patterns like a*b*
        if (p.charAt(j - 1) == '*') dp[0][j] = dp[0][j - 2];
    for (int i = 1; i <= m; i++) {
        for (int j = 1; j <= n; j++) {
            char pc = p.charAt(j - 1);
            if (pc == '*') {
                char prev = p.charAt(j - 2);
                dp[i][j] = dp[i][j - 2];          // zero of preceding
                if (prev == '.' || prev == s.charAt(i - 1))
                    dp[i][j] |= dp[i - 1][j];     // one more of preceding
            } else if (pc == '.' || pc == s.charAt(i - 1)) {
                dp[i][j] = dp[i - 1][j - 1];      // single-char match
            }
        }
    }
    return dp[m][n];
}
```

**Dry run (s="aab", p="c*a*b"):** `c*` matches zero c's, `a*` matches "aa", `b` matches "b" → true.

**Complexity.** Time O(m·n), space O(m·n). **Edge cases:** empty `s` with `a*`-style patterns (handled by the base row); `*` at index 1 always has a valid preceding token by the well-formedness constraint; `.` against any single char.

---

### Problem 36: Wildcard Matching — '?' and '*' DP

**Statement.** Match pattern `p` against string `s`, where `?` matches any single character and `*` matches any sequence (including empty). The match must be full.

**Constraints.** `0 ≤ |s|,|p| ≤ 2000`.

**Approach.** Semantically simpler than regex `*` because here `*` stands alone (not tied to a preceding token). `dp[i][j]`: does `s[0..i)` match `p[0..j)`? If `p[j-1]=='*'`, it either matches **empty** (`dp[i][j-1]`) or **absorbs one more char** of `s` (`dp[i-1][j]`). If `p[j-1]` is `?` or equals `s[i-1]`, it is a single match (`dp[i-1][j-1]`). The base row marks leading `*`s as able to match the empty string. The two-branch `*` rule is the whole problem; greedy backtracking also works but is harder to bound, whereas this DP is cleanly O(m·n).

```java
public boolean isMatch(String s, String p) {
    int m = s.length(), n = p.length();
    boolean[] dp = new boolean[n + 1];
    dp[0] = true;
    for (int j = 1; j <= n; j++)                 // leading '*' run matches empty s
        if (p.charAt(j - 1) == '*') dp[j] = dp[j - 1];
    for (int i = 1; i <= m; i++) {
        boolean diag = dp[0];                    // dp[i-1][j-1] before overwrite
        dp[0] = false;                           // empty pattern can't match non-empty s
        for (int j = 1; j <= n; j++) {
            boolean tmp = dp[j];                 // save dp[i-1][j] for next diag
            char pc = p.charAt(j - 1);
            if (pc == '*')
                dp[j] = dp[j - 1] || dp[j];      // empty (left) or absorb (above)
            else if (pc == '?' || pc == s.charAt(i - 1))
                dp[j] = diag;                    // single match
            else
                dp[j] = false;
            diag = tmp;
        }
    }
    return dp[n];
}
```

**Dry run (s="adceb", p="*a*b"):** leading `*` absorbs "", `a` matches, `*` absorbs "dce", `b` matches → true.

**Complexity.** Time O(m·n), space O(n). **Edge cases:** pattern all `*` matches anything including empty; empty pattern matches only empty `s`; the `diag` bookkeeping is what makes the 1D compression correct (we need the old diagonal before it is overwritten).

---

### Problem 37: Maximal Square — Largest All-Ones Square in a Matrix

**Statement.** Given a binary matrix of `'0'`/`'1'`, find the area of the largest square containing only `1`s.

**Constraints.** `1 ≤ rows, cols ≤ 300`.

**Approach.** `dp[i][j]` = side length of the largest all-ones square whose **bottom-right corner** is `(i,j)`. If `matrix[i][j]=='1'`, a square of side `s` here requires squares of side `s-1` at the three neighbors above, left, and above-left: `dp[i][j] = 1 + min(dp[i-1][j], dp[i][j-1], dp[i-1][j-1])`. The `min` is the bottleneck — the new square can only be as big as the smallest of the three supporting squares plus one. Track the global max side; the answer is its square. We compress to a 1D array, carefully preserving the diagonal value.

```
dp[i-1][j-1]  dp[i-1][j]
dp[i][j-1]    dp[i][j]   = 1 + min(the other three)  (if cell is '1')
```

```java
public int maximalSquare(char[][] matrix) {
    int rows = matrix.length, cols = matrix[0].length, best = 0;
    int[] dp = new int[cols + 1];
    for (int i = 1; i <= rows; i++) {
        int diag = 0;                            // dp[i-1][j-1]
        for (int j = 1; j <= cols; j++) {
            int tmp = dp[j];                     // dp[i-1][j] before overwrite
            if (matrix[i - 1][j - 1] == '1') {
                dp[j] = 1 + Math.min(diag, Math.min(dp[j], dp[j - 1]));
                best = Math.max(best, dp[j]);
            } else {
                dp[j] = 0;
            }
            diag = tmp;
        }
    }
    return best * best;
}
```

**Dry run:** a 2×2 block of `1`s yields side 2 at its bottom-right, area 4; the `min` rule prevents counting an L-shape as a square.

**Complexity.** Time O(rows·cols), space O(cols). **Edge cases:** all zeros → 0; a single `1` → area 1; resetting `dp[j]=0` on a `0` cell is what enforces "only ones."

---

### Problem 38: Dungeon Game — Reverse-Direction Grid DP

**Statement.** A knight starts at the top-left of an `m × n` dungeon and must reach the princess at the bottom-right moving only right or down. Each cell adds (or subtracts) health. Find the **minimum initial health** so the knight's health stays ≥ 1 at every cell.

**Constraints.** `1 ≤ m, n ≤ 200`, cell values in `[-1000, 1000]`.

**Approach.** A forward DP fails because maximizing health on arrival does not minimize the required start — the constraint is a *floor at every step*, which couples future needs back to the present. So we fill **bottom-right to top-left**: `dp[i][j]` = minimum health needed **upon entering** `(i,j)` to survive to the end. We must have at least `1` after applying the cell, and at least `dp` of the cheaper neighbor before it: `need = min(dp[i+1][j], dp[i][j+1]) - dungeon[i][j]`, clamped to ≥ 1. This reverse direction is the crux; it is what lets a single min/max recurrence respect the "never drop below 1" invariant.

```java
public int calculateMinimumHP(int[][] dungeon) {
    int m = dungeon.length, n = dungeon[0].length;
    int[][] dp = new int[m + 1][n + 1];
    for (int[] row : dp) Arrays.fill(row, Integer.MAX_VALUE);
    dp[m][n - 1] = dp[m - 1][n] = 1;             // one step past the princess
    for (int i = m - 1; i >= 0; i--) {
        for (int j = n - 1; j >= 0; j--) {
            int need = Math.min(dp[i + 1][j], dp[i][j + 1]) - dungeon[i][j];
            dp[i][j] = Math.max(1, need);        // health must stay >= 1
        }
    }
    return dp[0][0];
}
```

**Dry run ([[-2,-3,3],[-5,-10,1],[10,30,-5]]):** the optimal path needs 7 initial health (right→right→down→down survives with the tightest floor).

**Complexity.** Time O(m·n), space O(m·n) (reducible to O(n)). **Edge cases:** a single cell → `max(1, 1 - value)`; positive-only dungeon still requires at least 1; the two seeded `1`s let the bottom-right cell read valid neighbors.

---

### Problem 39: Word Break II — Reconstruct All Segmentations

**Statement.** Given `s` and a dictionary, return **all** sentences where `s` is segmented into a space-separated sequence of dictionary words.

**Constraints.** `1 ≤ |s| ≤ 20` for full enumeration (output can be exponential); larger `s` with a feasibility guard.

**Approach.** Word Break I only decides feasibility; here we must enumerate, so pure tabulation is not enough — we use **memoized recursion that returns lists**. `solve(start)` returns every segmentation of `s[start..]`. For each end `i` where `s[start..i)` is a dictionary word, recurse on `i` and prepend the word to each returned suffix sentence. Memoizing by `start` avoids recomputing the suffix lists, which is the overlapping-subproblems win. A preliminary Word-Break-I feasibility check (the boolean DP) prunes inputs that have no segmentation at all, preventing exponential dead-end exploration.

```java
public List<String> wordBreak(String s, List<String> wordDict) {
    Set<String> dict = new HashSet<>(wordDict);
    Map<Integer, List<String>> memo = new HashMap<>();
    return solve(s, 0, dict, memo);
}
private List<String> solve(String s, int start, Set<String> dict,
                           Map<Integer, List<String>> memo) {
    if (memo.containsKey(start)) return memo.get(start);
    List<String> res = new ArrayList<>();
    if (start == s.length()) { res.add(""); return res; }   // empty suffix sentinel
    for (int end = start + 1; end <= s.length(); end++) {
        String word = s.substring(start, end);
        if (dict.contains(word)) {
            for (String tail : solve(s, end, dict, memo)) {
                res.add(tail.isEmpty() ? word : word + " " + tail);
            }
        }
    }
    memo.put(start, res);
    return res;
}
```

**Dry run (s="catsanddog", dict=[cat,cats,and,sand,dog]):** returns ["cats and dog", "cat sand dog"].

**Complexity.** Time O(n² · 2ⁿ) worst case (output-bound), space O(n · output). **Edge cases:** no segmentation → empty list; overlapping prefixes ("cat"/"cats") both explored; the empty-string sentinel cleanly joins words without trailing spaces.

---

### Problem 40: Stone Game / Optimal Strategy — Minimax Interval DP

**Statement.** Two players alternately take a coin from either end of a row of values `v[0..n-1]`, both playing optimally to maximize their own total. Return the maximum score the first player can guarantee.

**Constraints.** `1 ≤ n ≤ 1000`, `0 ≤ v[i] ≤ 10⁴`.

**Approach.** This is a minimax game cast as interval DP. Define `dp[i][j]` = the maximum score difference (current player minus opponent) achievable on subarray `v[i..j]`. The current player takes `v[i]` (leaving the opponent to play optimally on `[i+1..j]`, whose difference we subtract) or `v[j]` similarly: `dp[i][j] = max(v[i] - dp[i+1][j], v[j] - dp[i][j-1])`. Subtraction encodes the role swap — the opponent's advantage becomes our disadvantage. From the final difference `d = dp[0][n-1]` and total sum `S`, the first player's score is `(S + d) / 2`. Fill by increasing interval length.

```java
public int stoneGameScore(int[] v) {
    int n = v.length, sum = 0;
    for (int x : v) sum += x;
    int[][] dp = new int[n][n];
    for (int i = 0; i < n; i++) dp[i][i] = v[i];     // one coin left
    for (int len = 2; len <= n; len++) {
        for (int i = 0; i + len - 1 < n; i++) {
            int j = i + len - 1;
            dp[i][j] = Math.max(v[i] - dp[i + 1][j], v[j] - dp[i][j - 1]);
        }
    }
    int diff = dp[0][n - 1];
    return (sum + diff) / 2;                          // player 1's actual score
}
```

**Dry run (v=[5,3,4,5]):** dp[0][3]=6 (difference); sum=17; player1 = (17+6)/2 = ... player1 guarantees taking the two 5s → score 10, opponent 7, diff 3; with optimal end-picks the guaranteed score is 10.

**Complexity.** Time O(n²), space O(n²). **Edge cases:** single coin → that value; if you only need *whether* player 1 wins, check `dp[0][n-1] > 0`; the difference encoding sidesteps tracking whose turn it is explicitly.

---

### Problem 41: Longest Increasing Path in a Matrix — Memoized DFS DP

**Statement.** Given an `m × n` integer matrix, return the length of the longest strictly increasing path, moving up/down/left/right (no diagonals, no revisits).

**Constraints.** `1 ≤ m, n ≤ 200`, values in `[-2³¹, 2³¹-1]`.

**Approach.** Because moves must strictly increase, the path can never form a cycle — the cells form a DAG ordered by value. That makes it a DP on a DAG: `longest(r,c)` = `1 + max(longest(neighbor))` over neighbors with a strictly greater value. We memoize each cell so it is computed once; without memoization the DFS is exponential. This is "longest path in a DAG" solved by top-down DFS, and the strict-increase guarantee is exactly what supplies the acyclic ordering (no explicit topological sort needed).

```java
private static final int[][] DIRS = {{1,0},{-1,0},{0,1},{0,-1}};
public int longestIncreasingPath(int[][] matrix) {
    int m = matrix.length, n = matrix[0].length;
    int[][] memo = new int[m][n];
    int best = 0;
    for (int i = 0; i < m; i++)
        for (int j = 0; j < n; j++)
            best = Math.max(best, dfs(matrix, i, j, memo));
    return best;
}
private int dfs(int[][] g, int r, int c, int[][] memo) {
    if (memo[r][c] != 0) return memo[r][c];
    int best = 1;
    for (int[] d : DIRS) {
        int nr = r + d[0], nc = c + d[1];
        if (nr >= 0 && nr < g.length && nc >= 0 && nc < g[0].length
                && g[nr][nc] > g[r][c]) {
            best = Math.max(best, 1 + dfs(g, nr, nc, memo));
        }
    }
    return memo[r][c] = best;
}
```

**Dry run ([[9,9,4],[6,6,8],[2,1,1]]):** the path 1→2→6→9 has length 4 (the longest increasing route).

**Complexity.** Time O(m·n) (each cell memoized, constant out-degree), space O(m·n) for the cache plus recursion stack. **Edge cases:** single cell → 1; a plateau of equal values cannot be traversed (strict increase), so each contributes only 1; deep recursion on a large monotone grid may need an explicit stack to avoid overflow.

---

## 🧩 Extended Problems — Set 3: Hard / Expert & Optimization

### Problem 42: Russian Doll Envelopes — 2D LIS in O(n log n)

**Statement.** Each envelope has a width and height `[w, h]`. One envelope fits inside another only if both its width and height are strictly larger. Return the maximum number of envelopes you can nest (Russian-doll style).

**Constraints.** `1 ≤ n ≤ 10⁵`, `1 ≤ w, h ≤ 10⁵`.

**Approach.** Naively this is a 2D longest-chain problem, but the O(n²) pairwise DP times out at 10⁵. The trick collapses it to a 1D **Longest Increasing Subsequence**: sort by width **ascending**, and for ties in width sort by height **descending**. The descending-height tie-break is the crux — it guarantees that envelopes of equal width can never be chained (a later equal-width envelope has a smaller-or-equal height, so it cannot extend a strictly-increasing run). After sorting, run patience-sorting LIS on the heights alone. Because width is already monotone non-decreasing, any strictly-increasing run of heights corresponds to a valid nesting.

```
sort: width asc, height desc on ties
 [2,3] [5,4] [6,4] [6,7]   ->  heights: 3 4 4 7
 LIS on heights (strict) = [3,4,7] length 3   (the two w=6 never both used)
```

```java
public int maxEnvelopes(int[][] envelopes) {
    Arrays.sort(envelopes, (a, b) ->
        a[0] != b[0] ? a[0] - b[0] : b[1] - a[1]);   // width asc, height desc
    int[] tails = new int[envelopes.length];
    int size = 0;
    for (int[] e : envelopes) {
        int h = e[1], lo = 0, hi = size;
        while (lo < hi) {                            // lower_bound: first tail >= h
            int mid = (lo + hi) >>> 1;
            if (tails[mid] < h) lo = mid + 1;
            else hi = mid;
        }
        tails[lo] = h;
        if (lo == size) size++;
    }
    return size;
}
```

**Dry run ([[5,4],[6,4],[6,7],[2,3]]):** sorted → [2,3],[5,4],[6,7],[6,4]; heights 3,4,7,4; LIS = 3 ([2,3]⊂[5,4]⊂[6,7]).

**Complexity.** Time O(n log n), space O(n). **Edge cases:** equal widths must use descending heights or you would wrongly nest same-width envelopes; a single envelope returns 1; strict inequality on **both** dimensions is what the tie-break enforces.

---

### Problem 43: Number of Longest Increasing Subsequences — Count with Lengths

**Statement.** Given `nums`, return how many **distinct** longest increasing subsequences it has (count, not the length).

**Constraints.** `1 ≤ n ≤ 2000`, `-10⁶ ≤ nums[i] ≤ 10⁶`.

**Approach.** Augment the O(n²) LIS DP with a parallel count array. `len[i]` = length of the longest increasing subsequence ending at `i`; `cnt[i]` = how many such subsequences end at `i`. For each `j < i` with `nums[j] < nums[i]`: if `len[j] + 1 > len[i]` we found a strictly longer ending, so reset `len[i] = len[j]+1` and `cnt[i] = cnt[j]`; if `len[j] + 1 == len[i]` we found another way of the same length, so accumulate `cnt[i] += cnt[j]`. The answer sums `cnt[i]` over all `i` whose `len[i]` equals the global maximum length. The reset-vs-accumulate distinction is the whole subtlety.

```java
public int findNumberOfLIS(int[] nums) {
    int n = nums.length, maxLen = 0, result = 0;
    int[] len = new int[n], cnt = new int[n];
    for (int i = 0; i < n; i++) {
        len[i] = cnt[i] = 1;
        for (int j = 0; j < i; j++) {
            if (nums[j] < nums[i]) {
                if (len[j] + 1 > len[i]) {           // found a longer chain
                    len[i] = len[j] + 1;
                    cnt[i] = cnt[j];                 // inherit its count
                } else if (len[j] + 1 == len[i]) {   // another chain of same length
                    cnt[i] += cnt[j];
                }
            }
        }
        if (len[i] > maxLen) { maxLen = len[i]; result = cnt[i]; }
        else if (len[i] == maxLen) result += cnt[i];
    }
    return result;
}
```

**Dry run ([1,3,5,4,7]):** longest length is 4; chains 1,3,4,7 and 1,3,5,7 → count 2.

**Complexity.** Time O(n²), space O(n). **Edge cases:** strictly decreasing array → every element is its own LIS of length 1, count `n`; duplicates do not extend (strict `<`); an O(n log n) version is possible with Fenwick trees but is rarely required at this constraint.

---

### Problem 44: Cherry Pickup II — Two Robots, 3D Grid DP

**Statement.** A grid of cherries; robot A starts at top-left corner of the top row, robot B at top-right corner. Both move down one row per step, choosing among down-left / down / down-right. They collect cherries (a cell is collected once even if both stand on it). Maximize total cherries when both reach the bottom row.

**Constraints.** `1 ≤ rows, cols ≤ 70`.

**Approach.** Both robots advance one row per step in lockstep, so the shared state is just `(row, colA, colB)` — a 3D DP. `dp[r][a][b]` = max cherries collectible from row `r` onward with the robots in columns `a` and `b`. At each row, add `grid[r][a]` plus `grid[r][b]` (but only once if `a == b`), then take the best over the 3×3 = 9 combinations of next columns. Processing row-by-row lets us keep only two 2D layers (rolling), dropping space from O(rows·cols²) to O(cols²). Independent per-robot DP fails because the "shared cell counted once" coupling forces a joint state.

```java
public int cherryPickup(int[][] grid) {
    int rows = grid.length, cols = grid[0].length;
    int NEG = Integer.MIN_VALUE / 2;
    int[][] cur = new int[cols][cols], next;
    for (int[] row : cur) Arrays.fill(row, NEG);
    cur[0][cols - 1] = grid[0][0] + (cols > 1 ? grid[0][cols - 1] : 0);
    for (int r = 1; r < rows; r++) {
        next = new int[cols][cols];
        for (int[] row : next) Arrays.fill(row, NEG);
        for (int a = 0; a < cols; a++) {
            for (int b = 0; b < cols; b++) {
                if (cur[a][b] == NEG) continue;
                for (int da = -1; da <= 1; da++) {
                    for (int db = -1; db <= 1; db++) {
                        int na = a + da, nb = b + db;
                        if (na < 0 || na >= cols || nb < 0 || nb >= cols) continue;
                        int gain = grid[r][na] + (na == nb ? 0 : grid[r][nb]);
                        next[na][nb] = Math.max(next[na][nb], cur[a][b] + gain);
                    }
                }
            }
        }
        cur = next;
    }
    int best = 0;
    for (int a = 0; a < cols; a++)
        for (int b = 0; b < cols; b++)
            best = Math.max(best, cur[a][b]);
    return best;
}
```

**Dry run (3×4 grid):** the two robots greedily cannot both grab overlapping richest cells; the joint DP picks the column pair per row maximizing the combined, de-duplicated harvest.

**Complexity.** Time O(rows·cols²·9) = O(rows·cols²), space O(cols²) with rolling layers. **Edge cases:** `cols == 1` (both robots share the only column — collect it once each row, not twice); the `na == nb` guard prevents double counting; `NEG/2` sentinel avoids overflow.

---

### Problem 45: Minimum Cost to Cut a Stick — Interval DP over Cut Positions

**Statement.** A wooden stick of length `n`; you must make every cut in the array `cuts` (positions along the stick). The cost of one cut equals the current length of the piece being cut. Cuts can be performed in any order; choose the order minimizing total cost.

**Constraints.** `1 ≤ n ≤ 10⁶`, `1 ≤ cuts.length ≤ 100`, all cut positions distinct and in `(0, n)`.

**Approach.** The cost depends only on which cut is made **first** within a segment, because that splits the segment into two independent subproblems. Sort the cut positions and pad with sentinels `0` and `n`, giving an array `c[0..m+1]`. `dp[i][j]` = min cost to make all the cuts strictly between positions `c[i]` and `c[j]`. For each interior cut `k` (made first in this segment), `dp[i][j] = (c[j] - c[i]) + min(dp[i][k] + dp[k][j])`. The segment length `c[j] - c[i]` is the cost paid for whichever cut goes first, regardless of which `k` — so it factors out of the inner min. Iterate by increasing gap `j - i`.

```java
public int minCost(int n, int[] cuts) {
    int m = cuts.length;
    int[] c = new int[m + 2];
    System.arraycopy(cuts, 0, c, 1, m);
    c[0] = 0; c[m + 1] = n;
    Arrays.sort(c);
    int[][] dp = new int[m + 2][m + 2];
    for (int len = 2; len <= m + 1; len++) {          // gap between boundaries
        for (int i = 0; i + len <= m + 1; i++) {
            int j = i + len;
            int best = Integer.MAX_VALUE;
            for (int k = i + 1; k < j; k++)           // first cut in (i, j)
                best = Math.min(best, dp[i][k] + dp[k][j]);
            dp[i][j] = (best == Integer.MAX_VALUE ? 0 : best) + (c[j] - c[i]);
        }
    }
    return dp[0][m + 1];
}
```

**Dry run (n=7, cuts=[1,3,4,5]):** the optimal order yields total cost 16 (versus 25 for the naive left-to-right order).

**Complexity.** Time O(m³) where `m = cuts.length` (not `n`), space O(m²). **Edge cases:** a segment with no interior cut costs 0 (the `best == MAX_VALUE` guard); `n` is huge but irrelevant to complexity since only cut positions index the DP; positions must be sorted with the `0`/`n` sentinels added.

---

### Problem 46: Minimum Difficulty of a Job Schedule — Partition DP with Range Maxima

**Statement.** Given `jobDifficulty[]` (jobs must be done in order, dependencies are linear) and `d` days, partition the jobs into `d` non-empty contiguous groups. A day's difficulty is the max difficulty among its jobs; total difficulty is the sum over days. Minimize the total. Return -1 if `jobs < d`.

**Constraints.** `1 ≤ jobs ≤ 300`, `1 ≤ d ≤ 10`, `0 ≤ difficulty ≤ 1000`.

**Approach.** `dp[k][i]` = min total difficulty to schedule the first `i` jobs over exactly `k` days. The last day handles a contiguous block `jobs[p..i)`; its contribution is the max in that block, and the prefix `jobs[0..p)` was scheduled in `k-1` days: `dp[k][i] = min over p of (dp[k-1][p] + max(jobs[p..i)))`. We extend the block leftward while tracking its running max in O(1), so each `(k, i)` pair costs O(i) rather than recomputing the max. Total O(d·n²). We keep two rolling rows (`k-1` and `k`). Feasibility requires at least one job per day.

```java
public int minDifficulty(int[] jobs, int d) {
    int n = jobs.length;
    if (n < d) return -1;
    int INF = Integer.MAX_VALUE / 2;
    int[] prev = new int[n + 1];                      // dp for k-1 days
    Arrays.fill(prev, INF);
    prev[0] = 0;                                      // 0 jobs in 0 days
    for (int k = 1; k <= d; k++) {
        int[] cur = new int[n + 1];
        Arrays.fill(cur, INF);
        for (int i = k; i <= n; i++) {                // need >= k jobs for k days
            int blockMax = 0;
            for (int p = i; p >= k; p--) {            // last day = jobs[p-1 .. i-1]
                blockMax = Math.max(blockMax, jobs[p - 1]);
                if (prev[p - 1] < INF)
                    cur[i] = Math.min(cur[i], prev[p - 1] + blockMax);
            }
        }
        prev = cur;
    }
    return prev[n];
}
```

**Dry run (jobs=[6,5,4,3,2,1], d=2):** best split is [6,5,4,3,2] (max 6) and [1] (max 1) → 7.

**Complexity.** Time O(d·n²), space O(n). **Edge cases:** `n < d` is infeasible (return -1); `d == 1` collapses to the global max; the inner loop maintains `blockMax` incrementally instead of an O(n) re-scan per split point.

---

### Problem 47: Frog Jump — Reachability DP with Variable Step Sizes

**Statement.** A frog crosses a river on stones at given positions (sorted, strictly increasing). It starts on stone 0. If its last jump was `k` units, the next jump must be `k-1`, `k`, or `k+1` units (and positive). The first jump must be exactly 1 unit. Determine whether the frog can reach the last stone.

**Constraints.** `2 ≤ stones.length ≤ 2000`, `0 ≤ stones[i] ≤ 2³¹-1`, `stones[0] == 0`.

**Approach.** The state is `(stone, lastJumpSize)`: reachability depends on both where you are and the jump that got you there, because that jump constrains the next ones. We map each stone position to a set of jump sizes that can land **on** it. Start: stone 0 with jump size 0 (so the first real jump of size 1 is allowed via `k+1`). For each stone, for each arriving jump `k`, try `k-1, k, k+1`; if the resulting position is an actual stone, record that jump size as reachable there. A `HashMap<position, Set<jumpSize>>` cleanly handles the sparse, large-valued positions (an array indexed by position would be too big). The frog succeeds iff the last stone has any reachable jump size.

```java
public boolean canCross(int[] stones) {
    Map<Integer, Set<Integer>> jumps = new HashMap<>();
    for (int s : stones) jumps.put(s, new HashSet<>());
    jumps.get(0).add(0);                              // arrive at stone 0 with jump 0
    for (int s : stones) {
        for (int k : jumps.get(s)) {
            for (int next = k - 1; next <= k + 1; next++) {
                if (next > 0 && jumps.containsKey(s + next)) {
                    jumps.get(s + next).add(next);    // can land on s+next with jump `next`
                }
            }
        }
    }
    return !jumps.get(stones[stones.length - 1]).isEmpty();
}
```

**Dry run ([0,1,3,5,6,8,12,17]):** jumps grow 1→2→2→3→4→5→… reaching 17 with a valid size; returns true. ([0,1,2,3,4,8,9,11]) fails to bridge the gap to 8 → false.

**Complexity.** Time O(n²) (each stone holds at most O(n) distinct jump sizes), space O(n²) worst case. **Edge cases:** if `stones[1] != 1` the frog cannot make the mandatory first jump → false; large position values rule out an array-indexed DP; the seed jump `0` at stone 0 is what permits the forced size-1 opening jump.

---

### Problem 48: Maximal Rectangle — Largest All-Ones Rectangle via Histogram DP

**Statement.** Given a binary matrix of `'0'`/`'1'`, find the area of the largest rectangle containing only `1`s.

**Constraints.** `1 ≤ rows, cols ≤ 200`.

**Approach.** Reduce the 2D problem to a sequence of 1D "Largest Rectangle in Histogram" problems. Process rows top to bottom maintaining a histogram `heights[j]` = number of consecutive `1`s ending at row `i` in column `j` (a column-wise DP: `heights[j] = matrix[i][j]=='1' ? heights[j]+1 : 0`). After updating the histogram for row `i`, the largest all-ones rectangle whose bottom edge sits on row `i` equals the largest rectangle in that histogram, solvable in O(cols) with a monotonic stack. Taking the max across all rows gives the answer. The stack computes, for each bar, how far it can extend left and right while staying the shortest — the classic histogram trick.

```java
public int maximalRectangle(char[][] matrix) {
    int rows = matrix.length, cols = matrix[0].length, best = 0;
    int[] heights = new int[cols];
    for (int i = 0; i < rows; i++) {
        for (int j = 0; j < cols; j++)
            heights[j] = matrix[i][j] == '1' ? heights[j] + 1 : 0;   // histogram DP
        best = Math.max(best, largestRectangleArea(heights));
    }
    return best;
}
private int largestRectangleArea(int[] h) {
    int n = h.length, best = 0;
    Deque<Integer> stack = new ArrayDeque<>();         // indices, increasing heights
    for (int i = 0; i <= n; i++) {
        int cur = (i == n) ? 0 : h[i];                 // sentinel flushes the stack
        while (!stack.isEmpty() && h[stack.peek()] >= cur) {
            int height = h[stack.pop()];
            int width = stack.isEmpty() ? i : i - stack.peek() - 1;
            best = Math.max(best, height * width);
        }
        stack.push(i);
    }
    return best;
}
```

**Dry run:** for the row that makes `heights = [2,1,3,3]`, the histogram pass finds the 3×2 rectangle (area 6) spanning the two height-3 bars.

**Complexity.** Time O(rows·cols), space O(cols). **Edge cases:** all zeros → 0; a single `1` → 1; the trailing-zero sentinel in the histogram routine guarantees every bar is popped and measured.

---

### Problem 49: Burst Balloons Variant — Minimum Cost to Merge Stones (k-way Interval DP)

**Statement.** There are `n` piles of stones in a row. In each move you merge **exactly `k`** consecutive piles into one, paying a cost equal to the total stones in those `k` piles. Merge everything into a single pile at minimum total cost, or return -1 if impossible.

**Constraints.** `1 ≤ n ≤ 30`, `2 ≤ k ≤ 30`, `1 ≤ stones[i] ≤ 100`.

**Approach.** Feasibility first: each merge reduces the pile count by `k-1`, so we can reach one pile iff `(n - 1) % (k - 1) == 0`. Use prefix sums for O(1) range totals. `dp[i][j]` = min cost to merge `stones[i..j]` into the **fewest possible** piles (which is `1` if the count `j-i+1` is congruent to 1 mod `k-1`, else a few leftover piles that the parent interval will finish merging). Transition: split into a left part that collapses to one pile and a right part, stepping the split index by `k-1` so the left always reduces to a single pile: `dp[i][j] = min(dp[i][m] + dp[m+1][j])` for `m = i, i+(k-1), …`. When the whole range can become one pile, add the cost `sum(i..j)` of that final k-way merge. This generalizes burst-balloons-style interval DP to k-way splits.

```java
public int mergeStones(int[] stones, int k) {
    int n = stones.length;
    if ((n - 1) % (k - 1) != 0) return -1;
    int[] prefix = new int[n + 1];
    for (int i = 0; i < n; i++) prefix[i + 1] = prefix[i] + stones[i];
    int[][] dp = new int[n][n];
    for (int len = k; len <= n; len++) {
        for (int i = 0; i + len - 1 < n; i++) {
            int j = i + len - 1;
            dp[i][j] = Integer.MAX_VALUE;
            for (int m = i; m < j; m += (k - 1))      // left collapses to one pile
                dp[i][j] = Math.min(dp[i][j], dp[i][m] + dp[m + 1][j]);
            if ((len - 1) % (k - 1) == 0)             // this range can become one pile
                dp[i][j] += prefix[j + 1] - prefix[i];
        }
    }
    return dp[0][n - 1];
}
```

**Dry run (stones=[3,2,4,1], k=2):** merge to total cost 20: (3,2)=5, (4,1)=5, then (5,5)... optimal ordering yields 20.

**Complexity.** Time O(n³ / k) ≈ O(n³), space O(n²). **Edge cases:** `(n-1) % (k-1) != 0` → -1 (cannot reduce to one pile); a length below `k` needs no merge (cost 0, handled by the `len >= k` loop start); prefix sums avoid recomputing range totals.

---

### Problem 50: Largest Sum of Averages — Partition into k Groups (Layered DP)

**Statement.** Partition `nums` into at most `k` contiguous non-empty groups. The score is the sum of each group's average. Return the maximum achievable score.

**Constraints.** `1 ≤ n ≤ 100`, `1 ≤ k ≤ n`, `0 ≤ nums[i] ≤ 10⁴`.

**Approach.** Averaging is not greedy-friendly — a locally large average can prevent a better global split — so we need DP. `dp[g][i]` = best score partitioning `nums[i..]` into at most `g` groups. With one group left, the only option is the average of the whole suffix. With `g > 1` groups, choose where the first group ends: `dp[g][i] = max over j>i of (avg(i..j) + dp[g-1][j])`. Prefix sums give O(1) averages. Because each layer `g` reads only layer `g-1`, two rolling 1D arrays suffice. The "at most k" (rather than "exactly k") is naturally handled because adding an empty further split never helps, and we always allow taking the whole suffix as one group.

```java
public double largestSumOfAverages(int[] nums, int k) {
    int n = nums.length;
    double[] prefix = new double[n + 1];
    for (int i = 0; i < n; i++) prefix[i + 1] = prefix[i] + nums[i];
    double[] dp = new double[n + 1];                  // g = 1 layer: avg of suffix
    for (int i = 0; i < n; i++)
        dp[i] = (prefix[n] - prefix[i]) / (n - i);
    for (int g = 2; g <= k; g++) {
        double[] cur = new double[n + 1];
        for (int i = 0; i < n; i++) {
            cur[i] = (prefix[n] - prefix[i]) / (n - i);   // option: rest as one group
            for (int j = i + 1; j < n; j++) {
                double avg = (prefix[j] - prefix[i]) / (j - i);
                cur[i] = Math.max(cur[i], avg + dp[j]);
            }
        }
        dp = cur;
    }
    return dp[0];
}
```

**Dry run (nums=[9,1,2,3,9], k=3):** best split [9],[1,2,3],[9] → 9 + 2 + 9 = 20.

**Complexity.** Time O(k·n²), space O(n). **Edge cases:** `k == 1` → average of the whole array; `k == n` → sum of all elements (each its own group); prefix sums in `double` keep averages exact enough for the given range.

---

### Problem 51: Minimum Number of Refueling Stops — DP over Reachable Distance

**Statement.** A car starts with `startFuel` units of fuel and must reach a target `target` miles away (1 mile per unit of fuel). Gas stations are at `stations[i] = [position, fuel]`. Return the minimum number of refueling stops to reach the target, or -1 if impossible.

**Constraints.** `1 ≤ target, startFuel ≤ 10⁹`, `0 ≤ stations.length ≤ 500`, stations sorted by position.

**Approach.** Invert the usual "state = stops, value = position" framing. `dp[t]` = the maximum distance reachable using exactly `t` refueling stops. Initialize `dp[0] = startFuel`. Process stations left to right; for a station at `position` with `fuel`, update stops counts **from high to low** (so each station is used at most once per stop count, the 0/1-knapsack ordering): if `dp[t] >= position` we could have reached this station with `t` stops, so refueling here gives `dp[t+1] = max(dp[t+1], dp[t] + fuel)`. After processing all stations, the answer is the smallest `t` with `dp[t] >= target`. This is an elegant O(n²) DP; a priority-queue greedy also solves it in O(n log n), but the DP makes the "maximize distance per stop count" structure explicit.

```java
public int minRefuelStops(int target, int startFuel, int[][] stations) {
    int n = stations.length;
    long[] dp = new long[n + 1];                      // dp[t] = max distance with t stops
    dp[0] = startFuel;
    for (int[] s : stations) {
        int pos = s[0], fuel = s[1];
        for (int t = n; t >= 0; t--) {                // high->low: 0/1 use of station
            if (dp[t] >= pos) dp[t + 1] = Math.max(dp[t + 1], dp[t] + fuel);
        }
    }
    for (int t = 0; t <= n; t++)
        if (dp[t] >= target) return t;
    return -1;
}
```

**Dry run (target=100, startFuel=10, stations=[[10,60],[20,30],[30,30],[60,40]]):** dp grows so 2 stops (refuel at 10 then 60) reach ≥ 100 → answer 2.

**Complexity.** Time O(n²), space O(n). **Edge cases:** `startFuel >= target` → 0 stops; no stations and insufficient fuel → -1; the descending `t` loop prevents reusing one station across multiple stop counts in a single pass; `long` avoids overflow since distances reach 10⁹.

---

### Problem 52: Partition Array for Maximum Sum — Bounded-Window Partition DP

**Statement.** Partition `arr` into contiguous subarrays of length **at most `k`**. After partitioning, every element of a subarray becomes that subarray's maximum. Return the largest possible sum of the resulting array.

**Constraints.** `1 ≤ n ≤ 500`, `1 ≤ k ≤ n`, `0 ≤ arr[i] ≤ 10⁹`.

**Approach.** `dp[i]` = maximum achievable sum for the prefix `arr[0..i)`. The last subarray ends at `i` and has some length `len` between `1` and `k`; it contributes `max(arr[i-len .. i)) * len`, and the rest was the prefix `dp[i-len]`. So `dp[i] = max over len in [1..k] of (dp[i-len] + maxInWindow * len)`. We extend the window leftward, tracking its running max in O(1), giving O(n·k) overall. This is a clean 1D partition DP: greedy (always cut at the local max) fails because absorbing a slightly smaller neighbor can lift many elements to a higher max.

```java
public int maxSumAfterPartitioning(int[] arr, int k) {
    int n = arr.length;
    int[] dp = new int[n + 1];                        // dp[i] = best sum for first i elems
    for (int i = 1; i <= n; i++) {
        int windowMax = 0, best = 0;
        for (int len = 1; len <= k && len <= i; len++) {
            windowMax = Math.max(windowMax, arr[i - len]);   // extend window left
            best = Math.max(best, dp[i - len] + windowMax * len);
        }
        dp[i] = best;
    }
    return dp[n];
}
```

**Dry run (arr=[1,15,7,9,2,5,10], k=3):** best partition [1,15,7],[9],[2,5,10] → 15·3 + 9 + 10·3 = 84.

**Complexity.** Time O(n·k), space O(n). **Edge cases:** `k == 1` → sum unchanged; `k == n` → `max(arr) * n`; the running `windowMax` avoids an inner O(k) re-scan; values up to 10⁹ fit since the running products stay within `int` only if `n·max` does — promote to `long` if constraints widen.

---

### Problem 53: Minimum Window / Allocate Mailboxes — Cost-Precompute Partition DP

**Statement.** Given `houses` positions on a street and `k` mailboxes to place, assign each house to its nearest mailbox; minimize the total distance from every house to its allocated mailbox.

**Constraints.** `1 ≤ houses.length ≤ 100`, `1 ≤ k ≤ houses.length`, distinct positions up to 10⁴.

**Approach.** Two layers of insight. (1) **Cost of one mailbox** serving a contiguous block of sorted houses `[i..j]` is minimized by placing it at the **median**, and that minimal cost is `Σ |house - median|`; we precompute `cost[i][j]` for all blocks in O(n²) using the two-pointer median sum. (2) Since an optimal assignment always serves **contiguous** sorted houses per mailbox (no benefit to interleaving), the placement becomes a partition DP: `dp[k][i]` = min cost to serve the first `i` houses with `k` mailboxes; `dp[k][i] = min over p of (dp[k-1][p] + cost[p][i-1])`. Sort houses first. This separates an O(n²) precompute from an O(k·n²) partition, a common "precompute interval cost, then partition" template.

```java
public int minDistance(int[] houses, int k) {
    Arrays.sort(houses);
    int n = houses.length;
    int[][] cost = new int[n][n];
    for (int i = 0; i < n; i++) {
        for (int j = i; j < n; j++) {                 // cost of one mailbox for [i..j]
            int lo = i, hi = j, c = 0;
            while (lo < hi) { c += houses[hi] - houses[lo]; lo++; hi--; }
            cost[i][j] = c;                           // == sum of |house - median|
        }
    }
    int INF = Integer.MAX_VALUE / 2;
    int[] prev = new int[n + 1];
    Arrays.fill(prev, INF);
    prev[0] = 0;
    for (int boxes = 1; boxes <= k; boxes++) {
        int[] cur = new int[n + 1];
        Arrays.fill(cur, INF);
        for (int i = boxes; i <= n; i++)
            for (int p = boxes - 1; p < i; p++)       // last box serves houses[p..i-1]
                if (prev[p] < INF)
                    cur[i] = Math.min(cur[i], prev[p] + cost[p][i - 1]);
        prev = cur;
    }
    return prev[n];
}
```

**Dry run (houses=[1,4,8,10,20], k=3):** group {1,4} (cost 3), {8,10} (cost 2), {20} (cost 0) → total 5.

**Complexity.** Time O(n² + k·n²) = O(k·n²), space O(n²) for the cost table. **Edge cases:** `k == n` → each house its own mailbox, cost 0; `k == 1` → cost of the single block (sum of distances to the overall median); sorting is mandatory since the contiguity argument assumes ordered houses.

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
