# Greedy Algorithms

> Greedy algorithms build a solution piece by piece, always taking the locally optimal choice in the hope that these local optima compose into a global optimum. They are fast and elegant — but only *correct* when the problem has the right structure, which is why proving correctness matters as much as coding the algorithm.

[← Back to master index](../README.md) · [← DSA index](README.md)

---

## Concept & Intuition

A **greedy algorithm** makes a sequence of choices, each of which looks best *right now*, and never revisits a choice. There is no backtracking and no global search of the solution space. This makes greedy algorithms typically `O(n log n)` (dominated by a sort) or `O(n)`, far cheaper than dynamic programming or brute force.

Greedy works only when two properties hold:

1. **Greedy-choice property** — a globally optimal solution can be reached by making a locally optimal (greedy) choice. In other words, you never have to undo a greedy choice; some optimal solution contains it.
2. **Optimal substructure** — an optimal solution to the problem contains optimal solutions to its subproblems. (DP also requires this, but DP *combines* overlapping subproblems; greedy commits to one branch.)

### The exchange argument (how you prove a greedy is correct)

The canonical proof technique is the **exchange argument**:

> Take any optimal solution `O`. Show that you can transform `O` into the greedy solution `G` step by step, exchanging one element of `O` for the greedy choice, *without making the solution worse*. Since each exchange preserves optimality and the end state is `G`, the greedy solution is also optimal.

A related framing is **"greedy stays ahead"**: prove by induction that after `k` greedy choices, the greedy partial solution is at least as good as any other partial solution on some measured quantity (e.g. number of items packed, finish time).

### When greedy FAILS (and you need DP)

If a locally optimal choice can force you into a globally suboptimal corner, greedy is wrong. The classic example is **0/1 knapsack**: greedily taking the highest value-per-weight item can leave capacity that cannot be filled efficiently. DP is required because choices interact. The **coin change** problem also breaks greedy for arbitrary denominations (e.g. coins `{1, 3, 4}`, target `6`: greedy gives `4+1+1 = 3` coins, optimal is `3+3 = 2` coins).

```
Greedy decision flow:

   problem
      │
      ▼
  sort / order by a key  ──►  [activity end time, ratio, deadline, frequency...]
      │
      ▼
  scan once, keep an invariant
      │
   ┌──┴───────────────┐
   │ choice locally    │  commit, never undo
   │ optimal? YES ─────┼──► take it, update invariant
   │              NO ──┼──► skip it
   └──────────────────┘
      │
      ▼
  result is globally optimal  (IFF greedy-choice property holds)
```

The recurring shape: **find the right sorting/ordering key, then make one linear pass maintaining an invariant.** Choosing the key is the whole game.

---

## Complexity Cheat-Sheet

| Problem / Operation | Time | Space | Notes |
|---|---|---|---|
| Activity selection / interval scheduling | `O(n log n)` | `O(1)` | Dominated by sort on end time |
| Merge intervals | `O(n log n)` | `O(n)` | Sort by start; output list |
| Fractional knapsack | `O(n log n)` | `O(1)` | Sort by value/weight ratio |
| Huffman coding (build tree) | `O(n log n)` | `O(n)` | Min-heap of frequencies |
| Jump Game (reachability) | `O(n)` | `O(1)` | Track farthest reach |
| Jump Game II (min jumps) | `O(n)` | `O(1)` | BFS-by-levels on array |
| Gas Station | `O(n)` | `O(1)` | One pass, running deficit |
| Minimum platforms | `O(n log n)` | `O(n)` | Sort arrivals & departures |
| Task scheduling (cooldown) | `O(n)` | `O(1)` | Counts + math formula |
| Candy distribution | `O(n)` | `O(n)` | Two passes |
| Non-overlapping intervals | `O(n log n)` | `O(1)` | Sort by end |
| Coin change (greedy, canonical coins) | `O(n)` | `O(1)` | Only correct for canonical systems |

Greedy almost always costs the price of one sort plus one linear scan. Space is usually `O(1)` beyond input, or `O(n)` when an explicit output/auxiliary array is needed.

---

## Patterns & Recognition

Reach for greedy when you see these signals:

- **"Maximum number of …" / "minimum number of …"** on intervals, tasks, or resources, where each unit choice is independent once ordered.
- **Sorting unlocks the problem.** If sorting by *one* key (end time, ratio, deadline, frequency, size) makes the answer obvious in a single scan, it's almost certainly greedy.
- **A single scalar invariant** is enough to decide each step (running max reach, running balance, last chosen end time).
- **Scheduling / resource allocation** — meeting rooms, platforms, CPU intervals, classroom assignment.
- **Frequency / priority-driven construction** — build something using a heap that always pops the smallest/largest (Huffman, task scheduling, connecting ropes).

Red flags that mean **NOT greedy → use DP / search instead**:

- A choice now changes the *value* of future choices in a non-monotone way (0/1 knapsack, coin change with weird coins).
- You need to consider combinations, not an ordering (subset-sum, longest increasing subsequence by value).
- The problem asks to *count* the number of optimal ways, or has overlapping subproblems you must memoize.

**Interview tactic:** propose greedy, then immediately *stress-test it with a small counterexample*. If you can't break it in 60 seconds, sketch the exchange argument. If you break it, pivot to DP. Interviewers love seeing this discipline — committing to greedy without justification is the most common way candidates lose points.

---

## Coding Problems

### Problem 1: Assign Cookies (easy)

**Statement.** Each child `i` has a greed factor `g[i]` (minimum cookie size that satisfies them). Each cookie `j` has size `s[j]`. Assign at most one cookie per child to maximize the number of content children.
Constraints: `1 ≤ g.length ≤ 3·10⁴`, `0 ≤ s.length ≤ 3·10⁴`, values up to `2³¹−1`.

**Approach.**
- *Brute force:* try all assignments — exponential, infeasible.
- *Optimal greedy:* sort both arrays. Walk smallest cookie to smallest child; give the smallest cookie that satisfies the current (least greedy unmet) child. Satisfying the greediest possible child with the smallest sufficient cookie wastes nothing. Exchange argument: if an optimal solution gives child a larger cookie than needed, swapping in the smallest sufficient cookie can only free a larger cookie for someone else, never hurting.

```java
import java.util.Arrays;

class Solution {
    public int findContentChildren(int[] g, int[] s) {
        Arrays.sort(g);
        Arrays.sort(s);
        int child = 0, cookie = 0;
        while (child < g.length && cookie < s.length) {
            if (s[cookie] >= g[child]) {
                child++;          // this child is satisfied
            }
            cookie++;             // cookie is consumed either way
        }
        return child;
    }
}
```

**Dry run.** `g = [1,2,3]`, `s = [1,1]`. Sorted already. Cookie 1 ≥ greed 1 → child=1. Cookie 1 < greed 2 → skip. Cookies exhausted. Answer `1`.

**Time:** `O(n log n + m log m)`. **Space:** `O(1)` (in-place sort).

**Follow-ups.** What if each child can receive multiple cookies summing to their greed? (Becomes a different greedy/DP.) What if cookies have a cost and you have a budget?

---

### Problem 2: Best Time to Buy and Sell Stock II (easy)

**Statement.** Given daily prices, you may buy and sell any number of times (but hold at most one share). Maximize total profit.
Constraints: `1 ≤ prices.length ≤ 3·10⁴`, `0 ≤ prices[i] ≤ 10⁴`.

**Approach.**
- *Brute force / DP:* `O(n)` DP over holding/not-holding states works but is overkill.
- *Optimal greedy:* sum every positive consecutive difference. Any multi-day uptrend decomposes into a sum of consecutive gains, so capturing each upward step captures the maximum.

```java
class Solution {
    public int maxProfit(int[] prices) {
        int profit = 0;
        for (int i = 1; i < prices.length; i++) {
            if (prices[i] > prices[i - 1]) {
                profit += prices[i] - prices[i - 1];
            }
        }
        return profit;
    }
}
```

**Dry run.** `[7,1,5,3,6,4]`: gains at `1→5` (+4) and `3→6` (+3) = `7`.

**Time:** `O(n)`. **Space:** `O(1)`.

**Follow-ups.** Add a transaction fee (subtract from each captured gain, switch to DP). Limit to `k` transactions (DP, not greedy). Add a cooldown day.

---

### Problem 3: Activity Selection / Non-overlapping Intervals (easy–medium)

**Statement.** Given intervals `[start, end)`, return the minimum number of intervals to remove so the rest are non-overlapping (equivalently, select the maximum number of mutually compatible activities).
Constraints: `1 ≤ intervals.length ≤ 10⁵`.

**Approach.**
- *Brute force:* try every subset — `O(2ⁿ)`.
- *Optimal greedy:* **sort by end time**, then greedily keep an interval whenever it starts at or after the last kept interval's end. Sorting by *earliest finish* leaves maximum room for the rest. Exchange argument: the activity that finishes first is in *some* optimal solution; swap it in for the first activity of any optimal solution without reducing the count.

```java
import java.util.Arrays;

class Solution {
    public int eraseOverlapIntervals(int[][] intervals) {
        Arrays.sort(intervals, (a, b) -> Integer.compare(a[1], b[1]));
        int kept = 0;
        long lastEnd = Long.MIN_VALUE;
        for (int[] iv : intervals) {
            if (iv[0] >= lastEnd) {   // compatible: keep it
                kept++;
                lastEnd = iv[1];
            }
        }
        return intervals.length - kept; // removals = total - kept
    }
}
```

**Dry run.** `[[1,2],[2,3],[3,4],[1,3]]`. Sort by end → `[1,2],[2,3],[1,3],[3,4]`. Keep `[1,2]` (end 2), keep `[2,3]` (3), `[1,3]` starts 1 < 3 skip, keep `[3,4]` (4). Kept 3, remove `4−3 = 1`.

**Time:** `O(n log n)`. **Space:** `O(1)`.

**Common pitfall.** Sorting by *start* time is wrong; a long early interval can crowd out two short ones. Always sort by end for max-compatible-set.

**Follow-ups.** Weighted activity selection (each interval has a value, maximize total value) is **not** greedy — it requires DP with binary search (`O(n log n)`). Be ready to explain why the unweighted greedy breaks.

---

### Problem 4: Merge Intervals (medium)

**Statement.** Merge all overlapping intervals and return the non-overlapping result.
Constraints: `1 ≤ intervals.length ≤ 10⁴`.

**Approach.**
- *Optimal greedy:* **sort by start**, then sweep. Maintain the current merged interval; if the next interval's start ≤ current end, extend the end; otherwise emit and start fresh. Sorting by start guarantees any overlap with already-merged territory is detected by comparing only against the running end.

```java
import java.util.*;

class Solution {
    public int[][] merge(int[][] intervals) {
        Arrays.sort(intervals, (a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> out = new ArrayList<>();
        int[] cur = intervals[0];
        for (int i = 1; i < intervals.length; i++) {
            if (intervals[i][0] <= cur[1]) {              // overlap
                cur[1] = Math.max(cur[1], intervals[i][1]);
            } else {
                out.add(cur);
                cur = intervals[i];
            }
        }
        out.add(cur);
        return out.toArray(new int[out.size()][]);
    }
}
```

**Dry run.** `[[1,3],[2,6],[8,10],[15,18]]`. cur=[1,3]; [2,6] overlaps → cur=[1,6]; [8,10] no → emit [1,6], cur=[8,10]; [15,18] no → emit [8,10], cur=[15,18]; emit. Result `[[1,6],[8,10],[15,18]]`.

**Time:** `O(n log n)`. **Space:** `O(n)` output.

**Follow-ups.** Insert one new interval into an already-sorted, already-merged list in `O(n)`. Compute total covered length. Stream intervals and answer merge queries online (interval tree).

---

### Problem 5: Jump Game & Jump Game II (medium)

**Statement A (Jump Game).** `nums[i]` is the max jump length from index `i`. Return whether you can reach the last index.
**Statement B (Jump Game II).** Return the *minimum* number of jumps to reach the last index (guaranteed reachable).
Constraints: `1 ≤ nums.length ≤ 10⁴`, `0 ≤ nums[i] ≤ 10⁵`.

**Approach.**
- *Reachability:* track the farthest index reachable so far. If the current index exceeds it, you're stuck. Greedy because reachability is monotone — extending reach never hurts.
- *Min jumps:* treat the array as an implicit BFS. Within the current jump's reach window, scan and record the farthest you could reach with one more jump; when you hit the window's right edge, "spend" a jump and advance the boundary. Greedy choice: always extend to the farthest reachable next boundary.

```java
class Solution {
    public boolean canJump(int[] nums) {
        int farthest = 0;
        for (int i = 0; i < nums.length; i++) {
            if (i > farthest) return false;          // gap we can't cross
            farthest = Math.max(farthest, i + nums[i]);
        }
        return true;
    }

    public int jump(int[] nums) {                    // min jumps
        int jumps = 0, curEnd = 0, farthest = 0;
        for (int i = 0; i < nums.length - 1; i++) {
            farthest = Math.max(farthest, i + nums[i]);
            if (i == curEnd) {                       // must jump now
                jumps++;
                curEnd = farthest;                   // new reachable boundary
                if (curEnd >= nums.length - 1) break;
            }
        }
        return jumps;
    }
}
```

**Dry run (jump II).** `[2,3,1,1,4]`. i=0: farthest=2, i==curEnd(0) → jumps=1, curEnd=2. i=1: farthest=max(2,4)=4. i=2: farthest=max(4,3)=4, i==curEnd(2) → jumps=2, curEnd=4 ≥ 4 → break. Answer `2`.

**Time:** `O(n)` both. **Space:** `O(1)`.

**Follow-ups.** Jump Game III (jump `±nums[i]`, reach any zero) → BFS/DFS, not greedy. Minimum jumps with a cost array → DP. Return the actual jump path.

---

### Problem 6: Gas Station (medium)

**Statement.** `gas[i]` is fuel at station `i`; `cost[i]` is fuel to drive from `i` to `i+1` (circular). Return the starting index to complete one loop, or `−1` if impossible. The answer is unique if it exists.
Constraints: `1 ≤ n ≤ 10⁵`.

**Approach.**
- *Brute force:* simulate from each start — `O(n²)`.
- *Optimal greedy:* if `sum(gas) < sum(cost)`, impossible. Otherwise a unique start exists. Track a running tank; whenever it goes negative at station `i`, no station in `[start..i]` can be the answer (each prefix from an earlier start would also fail by here), so reset `start = i+1` and zero the tank. **Greedy-stays-ahead** logic: skipping all candidates up to the failure point is always safe.

```java
class Solution {
    public int canCompleteCircuit(int[] gas, int[] cost) {
        int total = 0, tank = 0, start = 0;
        for (int i = 0; i < gas.length; i++) {
            int diff = gas[i] - cost[i];
            total += diff;
            tank  += diff;
            if (tank < 0) {       // can't reach i+1 from current start
                start = i + 1;
                tank = 0;
            }
        }
        return total >= 0 ? start : -1;
    }
}
```

**Dry run.** `gas=[1,2,3,4,5]`, `cost=[3,4,5,1,2]`. diffs `[-2,-2,-2,3,3]`. i=0 tank=-2<0→start=1,tank=0; i=1 tank=-2<0→start=2,tank=0; i=2 tank=-2<0→start=3,tank=0; i=3 tank=3; i=4 tank=6. total=0≥0 → start `3`.

**Time:** `O(n)`. **Space:** `O(1)`.

**Follow-ups.** Prove the reset is safe (exchange/contradiction argument). What if multiple answers are allowed — return all valid starts? Two-direction travel.

---

### Problem 7: Minimum Number of Platforms (medium)

**Statement.** Given arrival and departure times of trains at a station, find the minimum number of platforms so no train waits.
Constraints: `1 ≤ n ≤ 10⁵`. A train departing exactly when another arrives still needs separate platforms (treat arrival ≤ departure as overlap, per the common variant).

**Approach.**
- *Brute force:* for each train count concurrent trains — `O(n²)`.
- *Optimal greedy (two-pointer sweep):* sort arrivals and departures independently. Sweep a virtual clock; on an arrival, increment platforms; on a departure, decrement. The running max is the answer. This is equivalent to the max number of overlapping intervals.

```java
import java.util.Arrays;

class Solution {
    public int findPlatform(int[] arr, int[] dep) {
        Arrays.sort(arr);
        Arrays.sort(dep);
        int platforms = 0, maxPlatforms = 0;
        int i = 0, j = 0, n = arr.length;
        while (i < n && j < n) {
            if (arr[i] <= dep[j]) {     // a train arrives before next departs
                platforms++;
                i++;
                maxPlatforms = Math.max(maxPlatforms, platforms);
            } else {                    // a train departs, frees a platform
                platforms--;
                j++;
            }
        }
        return maxPlatforms;
    }
}
```

**Dry run.** `arr=[900,940,950,1100,1500,1800]`, `dep=[910,1200,1120,1130,1900,2000]` (sorted dep `[910,1120,1130,1200,1900,2000]`). Sweep peaks at 3 platforms around 950–1100. Answer `3`.

**Time:** `O(n log n)`. **Space:** `O(n)` (sorted copies, or `O(1)` if sorting in place).

**Follow-ups.** Return *which* trains share a platform (sweep with a min-heap of departure times). Each platform has a cleaning buffer. This is identical to the **Meeting Rooms II** problem — mention the equivalence.

---

### Problem 8: Candy Distribution (medium–hard)

**Statement.** `n` children stand in a line with integer ratings. Each child must get at least one candy, and a child with a higher rating than an immediate neighbor must get more candies than that neighbor. Return the minimum total candies.
Constraints: `1 ≤ n ≤ 2·10⁴`, `0 ≤ ratings[i] ≤ 2·10⁴`.

**Approach.**
- *Brute force:* repeatedly fix violations until stable — `O(n²)` worst case.
- *Optimal greedy (two passes):* give everyone 1. Left→right: if `rating[i] > rating[i-1]`, set `candy[i] = candy[i-1] + 1`. Right→left: if `rating[i] > rating[i+1]`, set `candy[i] = max(candy[i], candy[i+1] + 1)`. Each pass satisfies one directional constraint; the `max` reconciles both. Greedy because we only ever raise a value by the minimum needed.

```java
class Solution {
    public int candy(int[] ratings) {
        int n = ratings.length;
        int[] candy = new int[n];
        java.util.Arrays.fill(candy, 1);
        for (int i = 1; i < n; i++) {
            if (ratings[i] > ratings[i - 1]) candy[i] = candy[i - 1] + 1;
        }
        for (int i = n - 2; i >= 0; i--) {
            if (ratings[i] > ratings[i + 1]) {
                candy[i] = Math.max(candy[i], candy[i + 1] + 1);
            }
        }
        int total = 0;
        for (int c : candy) total += c;
        return total;
    }
}
```

**Dry run.** `ratings=[1,0,2]`. fill→`[1,1,1]`. L→R: i=1 0<1 skip; i=2 2>0 → `[1,1,2]`. R→L: i=1 0<2 skip; i=0 1>0 → max(1,2)=2 → `[2,1,2]`. Total `5`.

**Time:** `O(n)`. **Space:** `O(n)` (an `O(1)`-space slope-counting variant exists).

**Follow-ups.** `O(1)` extra space via counting ascending/descending run lengths. Circular line (first and last are neighbors). Strictly-greater becomes greater-or-equal.

---

### Problem 9: Task Scheduler with Cooldown (hard)

**Statement.** Given task labels and an integer `n`, each identical task must be separated by at least `n` cooldown intervals. Each interval runs one task or is idle. Return the minimum number of intervals to finish all tasks.
Constraints: `1 ≤ tasks.length ≤ 10⁴`, `0 ≤ n ≤ 100`.

**Approach.**
- *Greedy with a heap (intuition):* always schedule the most frequent remaining task that is off cooldown; idle if forced. `O(total · log 26)`.
- *Optimal closed-form greedy:* the bottleneck is the most frequent task. With max frequency `fMax` occurring in `nMax` tasks, the schedule is a grid of `(fMax − 1)` full rows of width `(n + 1)` plus a final partial row of `nMax`. The answer is `max(tasks.length, (fMax − 1)·(n + 1) + nMax)` — `tasks.length` wins when there are enough distinct tasks to fill all idle slots.

```java
class Solution {
    public int leastInterval(char[] tasks, int n) {
        int[] freq = new int[26];
        for (char t : tasks) freq[t - 'A']++;
        int fMax = 0;
        for (int f : freq) fMax = Math.max(fMax, f);
        int nMax = 0;                       // how many tasks hit fMax
        for (int f : freq) if (f == fMax) nMax++;
        int frame = (fMax - 1) * (n + 1) + nMax;
        return Math.max(tasks.length, frame);
    }
}
```

**Dry run.** `tasks=[A,A,A,B,B,B]`, `n=2`. fMax=3, nMax=2. frame=(3−1)·3+2=8. tasks.length=6. Answer `max(6,8)=8` → `A B _ A B _ A B`.

**Time:** `O(L)` where `L = tasks.length` (the 26-fixed loops are constant). **Space:** `O(1)`.

**Follow-ups.** Return an actual valid schedule string (use a max-heap + cooldown queue). Cooldown differs per task. Unlimited task types / large alphabet (heap version scales without the 26 assumption).

---

### Problem 10: Fractional Knapsack (medium)

**Statement.** Given items with `value[i]` and `weight[i]` and a knapsack capacity `W`, maximize total value. You **may take fractions** of an item.
Constraints: `1 ≤ n ≤ 10⁵`, positive weights and values.

**Approach.**
- *Optimal greedy:* sort by **value/weight ratio** descending. Take whole items greedily; when an item doesn't fit, take the fraction that fills the remaining capacity. Exchange argument: if an optimal solution takes less of a higher-ratio item to take more of a lower-ratio item, swapping toward the higher ratio increases value — contradiction. (Contrast: **0/1 knapsack forbids fractions**, the swap is impossible, and greedy fails → DP.)

```java
import java.util.Arrays;

class Solution {
    public double fractionalKnapsack(int W, int[] value, int[] weight) {
        int n = value.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        // sort indices by descending value/weight ratio
        Arrays.sort(idx, (a, b) ->
            Double.compare((double) value[b] / weight[b],
                           (double) value[a] / weight[a]));
        double total = 0;
        int remaining = W;
        for (int i : idx) {
            if (remaining <= 0) break;
            if (weight[i] <= remaining) {
                total += value[i];           // take whole item
                remaining -= weight[i];
            } else {
                total += value[i] * ((double) remaining / weight[i]); // fraction
                remaining = 0;
            }
        }
        return total;
    }
}
```

**Dry run.** `W=50`, items `(60,10),(100,20),(120,30)` → ratios `6,5,4`. Take item1 whole (val 60, rem 40), item2 whole (val 160, rem 20), fraction 20/30 of item3 (val += 120·2/3 = 80) → `240.0`.

**Time:** `O(n log n)`. **Space:** `O(n)` for the index array (`O(1)` if sorting structs in place).

**Follow-ups.** **Why does this break for 0/1 knapsack?** (Counterexample: `W=4`, items `(value=3,w=3),(value=4,w=4)`; greedy by ratio picks the `3` then can't fit the `4`, optimal picks the `4`.) Items with both a weight and a volume constraint (multi-dimensional → harder).

---

### Problem 11: Huffman Coding (senior / hard)

**Statement.** Given characters with frequencies, build an optimal prefix-free binary code that minimizes total encoded length `Σ freq[c]·depth[c]`. Return the minimum total cost (sum over all merges).
Constraints: `2 ≤ n ≤ 10⁵`.

**Approach.**
- *Optimal greedy (Huffman):* repeatedly pop the **two smallest** frequencies from a min-heap, merge them into a node of combined frequency (this is one "cost" contribution), and push the merge back. The two least-frequent symbols are siblings at the deepest level in some optimal tree — the exchange argument swaps them down without increasing cost. Total cost = sum of all merge sums = the weighted path length.

```java
import java.util.PriorityQueue;

class Solution {
    public long huffmanCost(int[] freq) {
        PriorityQueue<Long> heap = new PriorityQueue<>();
        for (int f : freq) heap.offer((long) f);
        long cost = 0;
        while (heap.size() > 1) {
            long a = heap.poll();           // two smallest
            long b = heap.poll();
            long merged = a + b;
            cost += merged;                 // each merge adds to total path length
            heap.offer(merged);
        }
        return cost;
    }
}
```

**Dry run.** `freq=[5,9,12,13,16,45]`. Merge 5+9=14 (cost 14). Heap `[12,13,14,16,45]`. Merge 12+13=25 (cost 39). `[14,16,25,45]`. Merge 14+16=30 (cost 69). `[25,30,45]`. Merge 25+30=55 (cost 124). `[45,55]`. Merge 45+55=100 (cost 224). Total `224`.

**Time:** `O(n log n)`. **Space:** `O(n)`.

**Follow-ups.** Reconstruct the actual codeword for each symbol (store tree nodes, not just longs). Why is Huffman *not* the absolute best compressor? (Arithmetic coding beats it on fractional bits; Huffman is optimal only among *prefix codes with integer bit lengths*.) "Connect ropes to minimize cost" and "minimum cost to merge stones (k=2)" are the same greedy.

---

### Problem 12: Minimum Number of Arrows to Burst Balloons (medium — interval bonus)

**Statement.** Balloons are intervals `[xstart, xend]` on a number line. An arrow shot at `x` bursts every balloon whose interval contains `x`. Return the minimum arrows to burst all balloons.
Constraints: `1 ≤ n ≤ 10⁵`.

**Approach.**
- *Optimal greedy:* sort by **end** coordinate. Shoot an arrow at the first balloon's end; it bursts every balloon that starts ≤ that point. Move past all such balloons, then shoot again at the next uncovered balloon's end. Same skeleton as activity selection, counting *arrows* (groups) instead of removals.

```java
import java.util.Arrays;

class Solution {
    public int findMinArrowShots(int[][] points) {
        Arrays.sort(points, (a, b) -> Integer.compare(a[1], b[1]));
        int arrows = 1;
        long arrowX = points[0][1];
        for (int[] p : points) {
            if (p[0] > arrowX) {     // balloon starts after current arrow → new arrow
                arrows++;
                arrowX = p[1];
            }
        }
        return arrows;
    }
}
```

**Dry run.** `[[10,16],[2,8],[1,6],[7,12]]`. Sort by end → `[1,6],[2,8],[7,12],[10,16]`. arrowX=6; `[2,8]` start 2≤6 ok; `[7,12]` start 7>6 → arrows=2, arrowX=12; `[10,16]` start 10≤12 ok. Answer `2`.

**Time:** `O(n log n)`. **Space:** `O(1)`. (Use `Long`/careful comparator to avoid `Integer.MIN_VALUE` overflow in the sort.)

**Follow-ups.** Maximize balloons burst with exactly `k` arrows. Arrows that travel and burst along a diagonal. This is the dual of "max non-overlapping intervals."

---

## Interview Q&A by Level

### 🟢 Basic

**Q: What is a greedy algorithm in one sentence?**
A: An algorithm that builds a solution by repeatedly making the choice that looks best at the moment and never reconsidering it.

**Q: What two properties must a problem have for greedy to be correct?**
A: The greedy-choice property (a local optimum leads to a global optimum) and optimal substructure (optimal solutions contain optimal sub-solutions).

**Q: Give an everyday example of greedy.**
A: Making change with the fewest coins in a *canonical* currency (e.g. US coins) — always take the largest coin ≤ remaining amount.

**Q: Why is sorting so common in greedy solutions?**
A: The greedy choice is usually "process items in a specific order." Sorting establishes that order (by end time, ratio, frequency, deadline) so a single linear scan can make the right local decision each step.

### 🟡 Intermediate

**Q: How do you prove a greedy algorithm is correct?**
A: Two standard techniques — the **exchange argument** (transform any optimal solution into the greedy one without making it worse) and **greedy stays ahead** (induct that the greedy partial solution dominates any other on a chosen metric after each step).

**Q: When does greedy fail and you need DP instead?**
A: When a local choice changes the value of future choices non-monotonically, so committing early can trap you in a suboptimum. Classic cases: 0/1 knapsack, coin change with arbitrary denominations, weighted interval scheduling.

**Q: Why sort by end time (not start time) in activity selection?**
A: The activity that finishes earliest leaves the most remaining time for others and is provably in some optimal set. Sorting by start can let one long early activity block several short ones.

**Q: Activity selection vs. Dijkstra — both greedy?**
A: Yes. Dijkstra greedily finalizes the closest unvisited vertex, relying on non-negative edges so a finalized distance is never improved later. Negative edges break the greedy invariant (use Bellman-Ford).

### 🟠 Advanced

**Q: Explain the exchange argument for Huffman coding.**
A: In an optimal prefix tree the two lowest-frequency symbols can be assumed to be sibling leaves at maximum depth: if they weren't, swapping them with whatever is deepest does not increase the weighted path length (lower frequency × greater depth is no worse). Merging them is therefore optimal, and the argument recurses on the reduced alphabet.

**Q: Are greedy algorithms always faster than DP?**
A: Generally yes (`O(n log n)` vs. `O(n·W)` or `O(n²)`), because greedy commits to one path instead of exploring overlapping subproblems. The trade-off is applicability: greedy is correct far less often.

**Q: Where does the matroid theory connection come in?**
A: A greedy algorithm is *guaranteed* optimal when the problem's feasible sets form a **matroid** (the greedy algorithm on a weighted matroid yields a max-weight independent set — this is exactly Kruskal's MST). Matroid theory is the formal generalization of "when does greedy work."

**Q: Real-world systems that use greedy?**
A: Huffman/DEFLATE compression, Dijkstra in routing protocols (OSPF), Kruskal/Prim for network design, CPU/job schedulers (shortest-job-first, EDF), cache eviction heuristics, and bandwidth/interval allocation.

### 🔴 Expert

**Q: Prove the gas-station reset is correct.**
A: Suppose the tank goes negative first at station `j` when starting from `s`. For any start `s' ∈ [s, j]`, the prefix sum of `gas−cost` from `s` to any point ≥ that from `s'` (since the segment `[s, s')` had non-negative running balance up to `j`), so `s'` also fails by `j`. Thus none of `[s..j]` can be the answer, and resetting to `j+1` skips only doomed candidates. Combined with `total ≥ 0` guaranteeing existence, the surviving `start` is the unique answer.

**Q: How would you make Task Scheduler produce an actual ordering at scale (large alphabet, streaming)?**
A: Use a max-heap keyed by remaining count plus a cooldown FIFO queue holding `(count, readyTime)`. Each tick, pop the highest-count ready task, decrement, push it to the cooldown queue with `readyTime = now + n + 1`; release tasks back to the heap when their ready time arrives. This is `O(L log k)` and avoids the closed-form's fixed-alphabet assumption.

**Q: Greedy vs. DP for coin change — when is greedy provably correct?**
A: Only for **canonical coin systems**, where greedy yields the optimal count for every amount. Determining whether a system is canonical is itself non-trivial (Pearson's algorithm checks it in `O(n³)`). For arbitrary denominations you must use DP (`O(amount · coins)`).

**Q: How does the choice between greedy and DP affect approximation algorithms?**
A: Many NP-hard problems use greedy for *approximation guarantees*: greedy set cover achieves an `H(n) ≈ ln n` ratio, and greedy gives a `1 − 1/e` bound for monotone submodular maximization. Here greedy isn't exact but is provably *close*, which is often the best achievable in polynomial time.

---

## ⚠️ Common Pitfalls

- **Not proving correctness.** "It looks right" is not a proof. Always sketch an exchange argument or a counterexample before committing in an interview.
- **Wrong sort key.** Sorting intervals by start instead of end (or by value instead of value/weight ratio) silently produces wrong answers that pass small tests.
- **Applying greedy to 0/1 knapsack or general coin change.** These need DP; greedy gives plausible-but-wrong answers.
- **Integer overflow in comparators.** `(a, b) -> a[1] - b[1]` overflows for large/negative bounds (e.g. `Integer.MIN_VALUE`); use `Integer.compare` or `Long`.
- **Off-by-one in interval overlap.** Decide up front whether `[1,2]` and `[2,3]` overlap (closed vs. half-open) — it changes platform counts, arrow counts, and merges.
- **Forgetting feasibility checks.** Gas Station needs the `total ≥ 0` check; Jump Game needs the unreachable-gap check. The greedy scan alone isn't enough.
- **Mutating shared state during sort.** Sorting the original `intervals[]` then reusing it elsewhere can corrupt later logic; clone if needed.
- **Assuming a unique answer.** Some greedy problems have multiple optimal solutions; if the prompt wants a *specific* one, define the tie-break explicitly.

## 📚 Further Reading

- *Introduction to Algorithms* (CLRS), Ch. 16 — greedy algorithms, activity selection, Huffman, and the matroid theory of greedy.
- *Algorithm Design* (Kleinberg & Tardos), Ch. 4 — exchange arguments and "greedy stays ahead" with full proofs.
- *The Algorithm Design Manual* (Skiena), §1.4 and §6 — practical guidance on when greedy works.
- Stanford CS161 / MIT 6.006 lecture notes on greedy correctness proofs.
- LeetCode tag **Greedy** and the **Interval** problem set for graded practice.
- Codeforces EDU "Greedy" module and competitive-programming editorials for exchange-argument drills.

---

[← Back to master index](../README.md) · [← DSA index](README.md)
