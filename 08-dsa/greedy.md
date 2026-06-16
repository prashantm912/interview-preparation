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

## 🧩 Extended Problems — Set 1: Classic Easy → Medium

### Problem 13: Lemonade Change (easy)

**Statement.** At a lemonade stand each glass costs `$5`. Customers pay with a `$5`, `$10`, or `$20` bill (array `bills` in queue order). You start with no change. Return whether you can give every customer correct change.
**Constraints.** `1 ≤ bills.length ≤ 10⁵`, `bills[i] ∈ {5, 10, 20}`.

**Approach.** Track only the count of `$5` and `$10` bills on hand (a `$20` is never used to make change, so it is dead weight). For a `$10` you must give back one `$5`. For a `$20` you owe `$15`: prefer one `$10` + one `$5` over three `$5` bills, because `$5` bills are more flexible (a `$10` customer can only be served with a `$5`). This is the greedy choice — spend the least-flexible bill first and conserve the flexible one. If any required change is unavailable, fail immediately.

```java
class Solution {
    public boolean lemonadeChange(int[] bills) {
        int five = 0, ten = 0;
        for (int b : bills) {
            if (b == 5) {
                five++;
            } else if (b == 10) {
                if (five == 0) return false;
                five--;
                ten++;
            } else { // b == 20, owe 15
                if (ten > 0 && five > 0) {   // prefer 10 + 5
                    ten--;
                    five--;
                } else if (five >= 3) {       // fall back to 5 + 5 + 5
                    five -= 3;
                } else {
                    return false;
                }
            }
        }
        return true;
    }
}
```

**Dry run.** `[5,5,10,20]`: collect two `$5`; `$10` → give one `$5` (five=1, ten=1); `$20` → ten>0 & five>0 → give `10+5` (five=0, ten=0). Returns `true`.

**Complexity** — Time `O(n)`, Space `O(1)`. **Edge cases:** first customer paying `$10`/`$20` (no change → false); long runs of `$20` after few `$5`s; giving three `$5`s only when no `$10` is available.

---

### Problem 14: Maximize Sum After K Negations (easy)

**Statement.** Given an array `nums` and integer `k`, you may negate an element (`x → -x`) exactly `k` times (the same index may be chosen repeatedly). Return the maximum possible sum.
**Constraints.** `1 ≤ nums.length ≤ 10⁴`, `-100 ≤ nums[i] ≤ 100`, `1 ≤ k ≤ 10⁴`.

**Approach.** Sort ascending and flip the most-negative elements first — each flip of a negative number gives the largest possible gain, so greedily targeting the smallest value each time is optimal. After exhausting the negatives (or `k`), any leftover flips must land on the element with the smallest absolute value; if remaining `k` is odd, that single element is negated once (flipping it twice is a no-op). Track the minimum absolute value during the pass so the parity fix is `O(1)`.

```java
import java.util.Arrays;

class Solution {
    public int largestSumAfterNegations(int[] nums, int k) {
        Arrays.sort(nums);
        int sum = 0, minAbs = Integer.MAX_VALUE;
        for (int i = 0; i < nums.length; i++) {
            if (nums[i] < 0 && k > 0) {  // flip a negative
                nums[i] = -nums[i];
                k--;
            }
            sum += nums[i];
            minAbs = Math.min(minAbs, nums[i]);
        }
        // leftover flips: only an odd remainder changes the sum
        if (k % 2 == 1) sum -= 2 * minAbs;
        return sum;
    }
}
```

**Dry run.** `nums=[-8,3,-5,-3,-2,5]`, `k=6`. Sort → `[-8,-5,-3,-2,3,5]`. Flip `-8,-5,-3,-2` (k=2 left) → `[8,5,3,2,3,5]`, minAbs=2, sum=26. k=2 even → no change. Answer `26`.

**Complexity** — Time `O(n log n)`, Space `O(1)`. **Edge cases:** `k` larger than the number of negatives; all positives (parity fix on the global min); zero present (flipping it absorbs all leftover parity for free).

---

### Problem 15: Two City Scheduling (medium)

**Statement.** `2n` people must be flown to two cities; `costs[i] = [aCost, bCost]` is the cost to send person `i` to city A or B. Send exactly `n` to each city. Return the minimum total cost.
**Constraints.** `costs.length == 2n`, `1 ≤ n ≤ 100`, `1 ≤ aCost, bCost ≤ 1000`.

**Approach.** Pretend everyone flies to city A, then "refund" by sending `n` of them to B instead. Sending person `i` to B instead of A changes the cost by `bCost − aCost`. To minimize total cost, pick the `n` people with the smallest (most negative) `bCost − aCost` — those who save the most (or lose the least) by going to B. Sort by that difference; first `n` go to A, last `n` go to B. The exchange argument: swapping any chosen B-person with an unchosen one of larger difference cannot lower the total.

```java
import java.util.Arrays;

class Solution {
    public int twoCitySchedCost(int[][] costs) {
        // sort by how much cheaper B is than A (ascending)
        Arrays.sort(costs, (x, y) ->
            Integer.compare(x[0] - x[1], y[0] - y[1]));
        int n = costs.length / 2, total = 0;
        for (int i = 0; i < costs.length; i++) {
            total += (i < n) ? costs[i][0]   // first n → city A
                             : costs[i][1];  // last n  → city B
        }
        return total;
    }
}
```

**Dry run.** `costs=[[10,20],[30,200],[400,50],[30,20]]` (n=2). Differences `a-b`: `-10, -170, 350, 10`. Sort → `[30,200](-170), [10,20](-10), [30,20](10), [400,50](350)`. First 2 to A: `30+10=40`; last 2 to B: `20+50=70`. Total `110`.

**Complexity** — Time `O(n log n)`, Space `O(1)` (in-place sort). **Edge cases:** all people cheaper for the same city (the `n`/`n` split still forces a balanced assignment); ties in the difference (any tie-break is optimal).

---

### Problem 16: Largest Number from Array (medium)

**Statement.** Given a list of non-negative integers `nums`, arrange them to form the largest possible number; return it as a string.
**Constraints.** `1 ≤ nums.length ≤ 100`, `0 ≤ nums[i] ≤ 10⁹`.

**Approach.** Sort with a custom comparator: `a` should come before `b` iff the concatenation `a+b` is lexicographically (and therefore numerically, since both have the same length) greater than `b+a`. This pairwise comparator is provably a *total order* and the greedy local choice (whichever ordering of two strings is bigger) composes into the global maximum. After sorting, concatenate. Handle the all-zeros case: the result must be `"0"`, not `"00..."`.

```
Why compare a+b vs b+a (not numeric value)?
  a = "3", b = "30"
  "3"+"30" = "330"   vs   "30"+"3" = "303"
  330 > 303  → "3" comes first.  Numeric compare (3 vs 30) is wrong.
```

```java
import java.util.Arrays;

class Solution {
    public String largestNumber(int[] nums) {
        String[] s = new String[nums.length];
        for (int i = 0; i < nums.length; i++) s[i] = Integer.toString(nums[i]);
        // descending: put the pair-order that yields the bigger concat first
        Arrays.sort(s, (a, b) -> (b + a).compareTo(a + b));
        if (s[0].equals("0")) return "0";    // all zeros
        StringBuilder sb = new StringBuilder();
        for (String x : s) sb.append(x);
        return sb.toString();
    }
}
```

**Dry run.** `nums=[3,30,34,5,9]`. Sorted by comparator → `9, 5, 34, 3, 30`. Concatenate → `"9534330"`.

**Complexity** — Time `O(n·L·log n)` where `L` is max digit length (string concat per compare), Space `O(n·L)`. **Edge cases:** all zeros → `"0"`; single element; values like `"3"` vs `"30"` where numeric intuition misleads.

---

### Problem 17: Minimum Number of Coins (Canonical Greedy) (easy)

**Statement.** Given a target `amount` and a canonical coin system (e.g. `{1, 2, 5, 10, 20, 50, 100, 200, 500, 1000}`), return the minimum number of coins/notes summing to `amount`. Assume an unlimited supply.
**Constraints.** `0 ≤ amount ≤ 10⁹`. Coins given in any order; system is canonical.

**Approach.** For a *canonical* system the greedy is optimal: repeatedly take the largest coin not exceeding the remaining amount. Sort denominations descending and, for each, take `remaining / coin` of them (integer division), then reduce the remainder by the modulo. This works because in a canonical system, the largest-coin choice is always contained in some optimal solution — proven per-system (e.g. by Pearson's test). It is **not** valid for arbitrary systems (the classic `{1,3,4}`, target `6` counterexample: greedy `4+1+1`=3 coins, optimal `3+3`=2).

```java
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;

class Solution {
    public int minCoins(int amount, int[] coins) {
        Arrays.sort(coins);              // ascending
        int count = 0;
        for (int i = coins.length - 1; i >= 0 && amount > 0; i--) {
            if (coins[i] <= amount) {
                count += amount / coins[i];
                amount %= coins[i];
            }
        }
        return amount == 0 ? count : -1; // -1 if unrepresentable
    }
}
```

**Dry run.** `amount=93`, coins `{1,2,5,10,20,50}`. Take 50 (1, rem 43), 20×2 (rem 3), 2 (rem 1), 1 (rem 0). Count `1+2+1+1 = 5`.

**Complexity** — Time `O(n log n)` for the sort then `O(n)`, Space `O(1)`. **Edge cases:** `amount = 0` → `0` coins; no `1`-coin present so a remainder is unrepresentable → return `-1`; non-canonical systems silently give wrong answers (must use DP).

---

### Problem 18: Boats to Save People (medium)

**Statement.** Each person has weight `people[i]`; each boat carries at most `limit` and at most **two** people whose combined weight ≤ `limit`. Return the minimum number of boats.
**Constraints.** `1 ≤ people.length ≤ 5·10⁴`, `1 ≤ people[i] ≤ limit ≤ 3·10⁴`.

**Approach.** Sort ascending and use two pointers. Always try to pair the heaviest remaining person (`right`) with the lightest (`left`); if they fit together, both board and advance both pointers, otherwise the heaviest goes alone. The greedy insight: the heaviest person must take a boat regardless, and the best companion for them is the lightest available — if even the lightest does not fit, no one fits, so pairing the lightest with the heaviest never wastes capacity that could have done better.

```
people sorted: [1, 2, 2, 3]  limit=3
   left→ ... ←right
   1 + 3 > 3  → 3 alone        boats=1, right--
   1 + 2 = 3  → pair           boats=2, left++ right--
   left>right → done
```

```java
import java.util.Arrays;

class Solution {
    public int numRescueBoats(int[] people, int limit) {
        Arrays.sort(people);
        int left = 0, right = people.length - 1, boats = 0;
        while (left <= right) {
            if (people[left] + people[right] <= limit) {
                left++;          // lightest pairs with heaviest
            }
            right--;             // heaviest always boards
            boats++;
        }
        return boats;
    }
}
```

**Dry run.** `people=[3,2,2,1]`, `limit=3`. Sort → `[1,2,2,3]`. left=0,right=3: 1+3=4>3 → 3 alone, boats=1, right=2. left=0,right=2: 1+2=3 → pair, boats=2, left=1,right=1. left=1,right=1: 2+2=4>3 → alone, boats=3, right=0. Answer `3`.

**Complexity** — Time `O(n log n)`, Space `O(1)`. **Edge cases:** single person; everyone at exactly `limit` (all solo); two pointers crossing (`left == right` boards one person).

---

### Problem 19: Minimum Number of Increments to Make Array Non-decreasing / Min Deletions (medium)

**Statement.** Given an array `nums`, return the minimum number of element-removals so the remaining array is sorted in non-decreasing order *and* the standard greedy "keep running max" decision is used. (Equivalently: count how many elements break monotonicity, removing each violator as it appears.)
**Constraints.** `1 ≤ nums.length ≤ 10⁵`, values fit in `int`.

**Approach.** Sweep left to right keeping the last *kept* value. Whenever the current element is ≥ the last kept value it joins the result for free; otherwise it is a violation and must be removed (counted). This greedy — keep an element iff it does not break non-decreasing order against the running maximum — minimizes removals because keeping a smaller-than-previous element could only force removing even more later. It mirrors the interval "keep by running boundary" pattern applied to a scalar.

```java
class Solution {
    public int minDeletions(int[] nums) {
        int removals = 0;
        int lastKept = Integer.MIN_VALUE;
        for (int v : nums) {
            if (v >= lastKept) {
                lastKept = v;     // keep it
            } else {
                removals++;       // breaks order → remove
            }
        }
        return removals;
    }
}
```

**Dry run.** `nums=[1,3,2,4,3,5]`. keep 1; keep 3; 2<3 → remove (1); keep 4; 3<4 → remove (2); keep 5. Removals `2` → remaining `[1,3,4,5]`.

**Complexity** — Time `O(n)`, Space `O(1)`. **Edge cases:** already sorted (0 removals); strictly decreasing (`n−1` removals, keep only the first); duplicates allowed (`>=` keeps equal values). Note: minimizing removals this way is greedy-correct only for the "keep running max" variant; the unconstrained *longest non-decreasing subsequence* removal count needs `O(n log n)` patience sorting.

---

### Problem 20: Partition Labels (medium)

**Statement.** Given a string `s`, partition it into as many parts as possible so each letter appears in at most one part. Return the list of part sizes.
**Constraints.** `1 ≤ s.length ≤ 500`, lowercase English letters only.

**Approach.** First record the last index of every character. Then sweep: extend the current partition's right boundary to the farthest last-occurrence of any character seen so far. When the scan index reaches that boundary, every letter inside the window is fully contained — close the partition greedily (the smallest valid cut point) and start a new one. Cutting at the earliest possible boundary maximizes the number of partitions.

```
s = a b a b c b a c a d e f e g d e h i j h k l i j
last[a]=8 last[b]=5 last[c]=7 ...
window grows to max(last[seen]); cut when i == window end.
```

```java
import java.util.List;
import java.util.ArrayList;

class Solution {
    public List<Integer> partitionLabels(String s) {
        int[] last = new int[26];
        for (int i = 0; i < s.length(); i++) last[s.charAt(i) - 'a'] = i;
        List<Integer> res = new ArrayList<>();
        int start = 0, end = 0;
        for (int i = 0; i < s.length(); i++) {
            end = Math.max(end, last[s.charAt(i) - 'a']);
            if (i == end) {              // partition fully closed
                res.add(end - start + 1);
                start = i + 1;
            }
        }
        return res;
    }
}
```

**Dry run.** `s="ababcbacadefegdehijhklij"`. First partition closes at index 8 (`"ababcbaca"`, size 9), then `"defegde"` (size 7), then `"hijhklij"` (size 8). Result `[9,7,8]`.

**Complexity** — Time `O(n)`, Space `O(1)` (fixed 26-entry table). **Edge cases:** all distinct letters (each is its own partition); a single character repeated (one partition); a character spanning the whole string forces one part.

---

### Problem 21: Minimum Add to Make Parentheses Valid (medium)

**Statement.** Given a string `s` of `(` and `)`, return the minimum number of single-character insertions to make it valid (every `(` matched by a later `)` and vice versa).
**Constraints.** `1 ≤ s.length ≤ 1000`, characters are `(` or `)`.

**Approach.** Scan once, tracking `open` = unmatched `(` so far. On `(` increment `open`. On `)`, match it against an open paren if one exists (`open--`); otherwise it is an unmatchable `)` requiring an inserted `(` (`add++`). At the end, every still-unmatched `(` needs a `)`, so add `open`. This greedy matches each `)` to the closest preceding unmatched `(` — the locally cheapest choice — and accumulates only forced insertions.

```java
class Solution {
    public int minAddToMakeValid(String s) {
        int open = 0, add = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                open++;
            } else {                 // c == ')'
                if (open > 0) open--; // match an existing '('
                else add++;          // need to insert a '('
            }
        }
        return add + open;           // leftover '(' each need a ')'
    }
}
```

**Dry run.** `s="())("`. `(` open=1; `)` match open=0; `)` no open → add=1; `(` open=1. Result `add+open = 1+1 = 2`.

**Complexity** — Time `O(n)`, Space `O(1)`. **Edge cases:** already valid (`0`); all opens `"((("` → 3; all closes `")))"` → 3; empty-after-processing balanced nesting.

---

### Problem 22: Remove K Digits to Make Smallest Number (medium)

**Statement.** Given a non-negative integer as a string `num` and an integer `k`, remove exactly `k` digits so the resulting number is the smallest possible. Return it as a string (no leading zeros, `"0"` if empty).
**Constraints.** `1 ≤ k ≤ num.length ≤ 10⁵`, `num` has no leading zeros except `"0"` itself.

**Approach.** Use a monotonic increasing stack. Scan digits left to right; while the stack top is larger than the current digit and we still have removals left, pop it — removing a larger high-order digit before a smaller one always shrinks the number. After the pass, if removals remain, drop from the end (the tail is non-decreasing, so removing the largest trailing digits helps). Finally strip leading zeros. The greedy choice (eliminate the leftmost descent) is optimal because the most significant differing digit dominates magnitude.

```
num="1432219", k=3
push 1; push 4; 4>3 pop(k=2) push 3; 3>2 pop(k=1) push 2;
push 2; push 1; ... → "1219"
```

```java
class Solution {
    public String removeKdigits(String num, int k) {
        StringBuilder st = new StringBuilder();   // used as a stack
        for (int i = 0; i < num.length(); i++) {
            char d = num.charAt(i);
            while (k > 0 && st.length() > 0 && st.charAt(st.length() - 1) > d) {
                st.deleteCharAt(st.length() - 1);  // pop bigger high digit
                k--;
            }
            st.append(d);
        }
        st.setLength(st.length() - k);             // remove leftover from tail
        // strip leading zeros
        int i = 0;
        while (i < st.length() && st.charAt(i) == '0') i++;
        String res = st.substring(i);
        return res.isEmpty() ? "0" : res;
    }
}
```

**Dry run.** `num="10200"`, `k=1`. push 1; 1>0 pop (k=0), push 0; push 2; push 0; push 0 → `"0200"`; no leftover; strip leading zero → `"200"`.

**Complexity** — Time `O(n)` (each digit pushed/popped at most once), Space `O(n)`. **Edge cases:** removing all digits (`k == length` → `"0"`); leading zeros after removal; already increasing digits (remove from tail); single digit.

---

### Problem 23: Queue Reconstruction by Height (medium)

**Statement.** Given people as `[h, k]` where `h` is height and `k` is the number of people in front who are at least as tall, reconstruct the queue.
**Constraints.** `1 ≤ people.length ≤ 2000`, distinct enough to have a valid reconstruction.

**Approach.** Sort by height **descending**, breaking ties by `k` ascending. Then insert each person into a list at index `k`. The greedy correctness: when inserting the tallest people first, everyone already placed is ≥ the current person, so inserting at position `k` puts exactly `k` taller-or-equal people in front. Shorter people inserted later do not affect the `k`-counts of taller ones (they slot in without disturbing the relative count). Each insertion fixes that person's constraint permanently.

```java
import java.util.*;

class Solution {
    public int[][] reconstructQueue(int[][] people) {
        // tallest first; among equal heights, smaller k first
        Arrays.sort(people, (a, b) ->
            a[0] != b[0] ? b[0] - a[0] : a[1] - b[1]);
        List<int[]> list = new ArrayList<>();
        for (int[] p : people) {
            list.add(p[1], p);          // insert at index k
        }
        return list.toArray(new int[list.size()][]);
    }
}
```

**Dry run.** `[[7,0],[4,4],[7,1],[5,0],[6,1],[5,2]]`. Sorted desc → `[7,0],[7,1],[6,1],[5,0],[5,2],[4,4]`. Insert: `[7,0]`; `[7,1]`→idx1; `[6,1]`→idx1; `[5,0]`→idx0; `[5,2]`→idx2; `[4,4]`→idx4. Result `[[5,0],[7,0],[5,2],[6,1],[4,4],[7,1]]`.

**Complexity** — Time `O(n²)` (list insertions are `O(n)` each), Space `O(n)`. **Edge cases:** single person; everyone the same height (pure `k`-ordering); `k = 0` always inserts at the front of placed-so-far.

---

### Problem 24: Score After Flipping Matrix (medium)

**Statement.** Given a binary matrix `grid`, you may toggle any entire row or any entire column (flip all its bits) any number of times. Each row read as a binary number; maximize the sum of all row values.
**Constraints.** `1 ≤ m, n ≤ 20`, entries `0`/`1`.

**Approach.** Two greedy steps. (1) The leftmost (most significant) bit dominates a row's value, so flip any row whose first bit is `0` to make every row start with `1`. (2) For each remaining column, flipping it is worthwhile iff it has more `0`s than `1`s — a column contributes its place value times the count of `1`s, so we greedily maximize ones per column independently. Both decisions are locally optimal on independent contributions, so they compose to the global maximum.

```java
class Solution {
    public int matrixScore(int[][] grid) {
        int m = grid.length, n = grid[0].length;
        // Step 1: make first column all 1s by flipping rows
        for (int i = 0; i < m; i++) {
            if (grid[i][0] == 0) {
                for (int j = 0; j < n; j++) grid[i][j] ^= 1;
            }
        }
        int score = 0;
        for (int j = 0; j < n; j++) {
            int ones = 0;
            for (int i = 0; i < m; i++) ones += grid[i][j];
            int best = Math.max(ones, m - ones);   // flip column if more 0s
            score += best * (1 << (n - 1 - j));     // place value of column j
        }
        return score;
    }
}
```

**Dry run.** `grid=[[0,0,1,1],[1,0,1,0],[1,1,0,0]]`. After row-flips (row0 starts 0 → flip): `[1,1,0,0],[1,0,1,0],[1,1,0,0]`. Column ones: c0=3, c1=2, c2=1→flip to 2, c3=0→flip to 3. Score `3·8 + 2·4 + 2·2 + 3·1 = 24+8+4+3 = 39`.

**Complexity** — Time `O(m·n)`, Space `O(1)` (in place). **Edge cases:** single row or column; already all-ones first column; columns tied at half `0`s/`1`s (`max` keeps it as is). Note: this mutates `grid` in place; clone first if the caller needs the original.

---

### Problem 25: Valid Parenthesis String with Wildcards (medium)

**Statement.** Given a string `s` containing `(`, `)`, and `*`, where `*` can be treated as `(`, `)`, or an empty string, return whether `s` can be made a valid parenthesization.
**Constraints.** `1 ≤ s.length ≤ 100`.

**Approach.** Greedily track the **range** of possible open-paren counts `[lo, hi]` rather than committing to an interpretation of each `*`. On `(`, both bounds rise; on `)`, both fall; on `*`, `lo` falls (treat as `)`) and `hi` rises (treat as `(`). Clamp `lo` at `0` (you can never have negative open parens — extra `)` interpretations are simply discarded). If `hi` ever drops below `0`, too many `)` appeared with no way to balance → invalid. At the end the string is valid iff `lo == 0` (some interpretation closes everything). This greedy interval-tracking avoids exponential branching over `*` choices.

```
s = "(*))"
char (  lo=1 hi=1
char *  lo=0 hi=2   (* could be ')','' ,'(')
char )  lo=0 hi=1   (lo clamped at 0)
char )  lo=0 hi=0
end: lo==0 → valid
```

```java
class Solution {
    public boolean checkValidString(String s) {
        int lo = 0, hi = 0;   // range of possible open-paren counts
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') { lo++; hi++; }
            else if (c == ')') { lo--; hi--; }
            else { lo--; hi++; }      // '*' : ')' lowers lo, '(' raises hi
            if (hi < 0) return false; // too many ')' even at most-open
            if (lo < 0) lo = 0;       // can't go below zero open parens
        }
        return lo == 0;
    }
}
```

**Dry run.** `s="(*)"`. `(` lo=1,hi=1; `*` lo=0,hi=2; `)` lo=-1→clamp 0, hi=1. End lo=0 → `true`.

**Complexity** — Time `O(n)`, Space `O(1)`. **Edge cases:** all stars `"***"` (valid — all empty); leading `)` (hi goes negative → false); unbalanced opens with too few stars to close; empty-string interpretation of every `*`.

---

## 🧩 Extended Problems — Set 2: Medium → Hard Variations & Follow-ups

### Problem 26: Jump Game VII — Reach End with a Jump Range (medium)

**Statement.** Given a binary string `s` (`'0'` is walkable, `'1'` is blocked) and two integers `minJump`, `maxJump`, you start at index `0` (always `'0'`). From index `i` you may jump to any `j` with `i + minJump ≤ j ≤ i + maxJump` and `s[j] == '0'`. Return whether index `n−1` is reachable.
**Constraints.** `2 ≤ s.length ≤ 10⁵`, `1 ≤ minJump ≤ maxJump < s.length`, `s[0] == '0'`.

**Approach.**
- *Brute force:* BFS/DFS where each reachable index pushes every index in its `[i+minJump, i+maxJump]` window — `O(n · maxJump)`, which is up to `10¹⁰`.
- *Optimal greedy + sliding window:* process indices left to right with a `reachable[]` flag. Index `i` is reachable iff `s[i]=='0'` and at least one reachable index exists in the window `[i−maxJump, i−minJump]`. Maintain a running count of reachable indices in that shrinking/growing window via a prefix-style counter `cnt` — add `reachable[i−minJump]` as it enters and subtract `reachable[i−maxJump−1]` as it leaves. The greedy insight: we never need to know *which* predecessor reached us, only that *some* predecessor in the window did, so a single integer suffices.

```
i moves right; window of valid predecessors is [i-maxJump, i-minJump]
   ... [###### window ######] gap i
        ^subtract on left      ^add on right when index i-minJump enters
reachable[i] = (s[i]=='0') && (window count > 0)
```

```java
class Solution {
    public boolean canReach(String s, int minJump, int maxJump) {
        int n = s.length();
        boolean[] reachable = new boolean[n];
        reachable[0] = true;
        int cnt = 0;                 // reachable count in current window
        for (int i = 1; i < n; i++) {
            if (i - minJump >= 0 && reachable[i - minJump]) cnt++;   // enters window
            if (i - maxJump - 1 >= 0 && reachable[i - maxJump - 1]) cnt--; // leaves window
            if (s.charAt(i) == '0' && cnt > 0) reachable[i] = true;
        }
        return reachable[n - 1];
    }
}
```

**Dry run.** `s="011010"`, `minJump=2`, `maxJump=3`. reachable[0]=true. i=2: index0 enters cnt=1, s[2]='1' blocked. i=3: index1 enters (false), s[3]='0', cnt=1>0 → reachable. i=4: index2 enters (false); index0 leaves cnt=0; s[4]='1'. i=5: index3 enters (true) cnt=1; index1 leaves(false); s[5]='0', cnt>0 → reachable. Answer `true`.

**Complexity** — Time `O(n)`, Space `O(n)`. **Edge cases:** `n−1` is `'1'` (unreachable by definition); window never overlaps a reachable index; first jump already lands past `n−1`.

---

### Problem 27: Minimum Taps to Water a Garden (hard)

**Statement.** A garden spans `[0, n]`. Tap `i` at position `i` has range `ranges[i]`, watering `[i − ranges[i], i + ranges[i]]`. Return the minimum number of taps to water the whole garden, or `−1` if impossible.
**Constraints.** `1 ≤ n ≤ 10⁴`, `0 ≤ ranges[i] ≤ 100`, `ranges.length == n + 1`.

**Approach.** This is **Jump Game II in disguise.** Convert each tap into an interval, then for every left endpoint `l` record the farthest right endpoint reachable: `maxReach[l] = max(l + range)` (clamp left at `0`). Now run the greedy min-jumps sweep: scan positions `0..n−1`, extend `farthest` to `maxReach[i]`, and when the scan index reaches the current segment boundary `curEnd`, "spend" a tap and jump the boundary to `farthest`. If `farthest` never advances past the current position, the garden has a gap → return `−1`.
- *Why greedy beats DP here:* the interval-cover DP is `O(n²)`; the reachability greedy is `O(n)` because covering `[0,n]` with fewest intervals where each start offers a best reach is exactly the BFS-levels argument from Jump Game II.

```java
class Solution {
    public int minTaps(int n, int[] ranges) {
        int[] maxReach = new int[n + 1];
        for (int i = 0; i <= n; i++) {
            int l = Math.max(0, i - ranges[i]);
            int r = Math.min(n, i + ranges[i]);
            maxReach[l] = Math.max(maxReach[l], r);
        }
        int taps = 0, curEnd = 0, farthest = 0;
        for (int i = 0; i <= n; i++) {
            if (i > farthest) return -1;     // gap: unreachable
            if (i > curEnd) {                // must open a new tap
                taps++;
                curEnd = farthest;
            }
            farthest = Math.max(farthest, maxReach[i]);
        }
        return taps;
    }
}
```

**Dry run.** `n=5`, `ranges=[3,4,1,1,0,0]`. Intervals: tap0→[0,3], tap1→[0,5], tap2→[1,3], tap3→[2,4]. maxReach[0]=5. i=0: farthest=5. Loop never finds i>curEnd until... i=1..5 all ≤ farthest=5; curEnd starts 0, at i=1 i>curEnd → taps=1, curEnd=5. No more opens. Answer `1`.

**Complexity** — Time `O(n)`, Space `O(n)`. **Edge cases:** a tap with range `0` covers only its own point (useless for spanning); position `0` not covered → `−1`; the final tap must reach exactly `n`.

---

### Problem 28: Video Stitching — Minimum Clips to Cover [0, time] (medium)

**Statement.** Given clips `clips[i] = [start, end]` and an integer `time`, return the minimum number of clips that together cover `[0, time]`, or `−1` if impossible.
**Constraints.** `1 ≤ clips.length ≤ 100`, `0 ≤ start ≤ end ≤ 100`, `1 ≤ time ≤ 100`.

**Approach.** Same family as Min Taps / Jump Game II. Build `maxReach[s]` = the farthest end among clips starting at or before `s` (bucket by start). Greedily sweep: maintain `curEnd` (end of coverage guaranteed by clips used so far) and `nextEnd` (farthest reachable using one more clip among starts ≤ curEnd). When the scan pointer passes `curEnd`, commit a clip and advance `curEnd = nextEnd`; if `nextEnd` didn't advance, there's a coverage gap → `−1`.
- *Brute force alternative:* sort by start, DP over covered length — `O(n²)` or `O(n·time)`. The greedy is `O(n + time)`.

```java
class Solution {
    public int videoStitching(int[][] clips, int time) {
        int[] maxReach = new int[time + 1];
        for (int[] c : clips) {
            if (c[0] <= time) {
                maxReach[c[0]] = Math.max(maxReach[c[0]], c[1]);
            }
        }
        int count = 0, curEnd = 0, nextEnd = 0, i = 0;
        while (curEnd < time) {
            while (i <= curEnd && i <= time) {       // clips usable now
                nextEnd = Math.max(nextEnd, maxReach[i]);
                i++;
            }
            if (nextEnd <= curEnd) return -1;        // no progress → gap
            count++;
            curEnd = nextEnd;
        }
        return count;
    }
}
```

**Dry run.** `clips=[[0,2],[4,6],[8,10],[1,9],[1,5],[5,9]]`, `time=10`. maxReach[0]=2, maxReach[1]=9, maxReach[4]=6, maxReach[5]=9, maxReach[8]=10. Round1: i=0 reach2; count=1 curEnd=2. Round2: i=1,2 reach max(9)=9; count=2 curEnd=9. Round3: i=3..9, maxReach[5]=9,maxReach[8]=10 → nextEnd=10; count=3 curEnd=10. Answer `3`.

**Complexity** — Time `O(n + time)`, Space `O(time)`. **Edge cases:** no clip starts at `0` → immediate `−1`; clips entirely past `time` ignored; a single clip `[0, time]` returns `1`.

---

### Problem 29: Non-overlapping Intervals — Maximize Total Weight (follow-up: why greedy breaks → DP) (hard)

**Statement.** Each interval `[start, end, weight]` has a value. Select a subset of mutually non-overlapping intervals maximizing total weight. (This is the *weighted* generalization of the classic activity-selection from Problem 3.)
**Constraints.** `1 ≤ n ≤ 5·10⁴`, weights positive.

**Approach.**
- *Why the unweighted greedy fails:* sorting by earliest finish and counting maximizes the *number* of intervals, but a single high-weight long interval can beat many low-weight short ones. The exchange argument from Problem 3 relied on every interval being worth `1`; once weights differ, swapping the earliest-finishing interval in can strictly lose value. So greedy-by-end is provably wrong here.
- *Correct approach: DP with binary search (weighted interval scheduling).* Sort by end. For interval `i`, let `p(i)` be the last interval that ends ≤ `start[i]` (found by binary search). Then `dp[i] = max(dp[i−1], weight[i] + dp[p(i)+1...])`. Define `dp[i]` = best using the first `i` (end-sorted) intervals: `dp[i] = max(skip i, take i + dp[p(i)])`. This is `O(n log n)`. Greedy still does the *sorting and binary search* heavy lifting — the only non-greedy piece is keeping both "take" and "skip" branches.

```java
import java.util.Arrays;

class Solution {
    public int maxWeightNonOverlapping(int[][] intervals) {
        Arrays.sort(intervals, (a, b) -> Integer.compare(a[1], b[1])); // by end
        int n = intervals.length;
        int[] ends = new int[n];
        for (int i = 0; i < n; i++) ends[i] = intervals[i][1];
        int[] dp = new int[n + 1];   // dp[i] = best over first i intervals
        for (int i = 1; i <= n; i++) {
            int start = intervals[i - 1][0], w = intervals[i - 1][2];
            int p = lastEndAtMost(ends, start, i - 1);  // index in [0, i-1)
            int take = w + dp[p + 1];
            dp[i] = Math.max(dp[i - 1], take);
        }
        return dp[n];
    }

    // largest j in [0, hi) with ends[j] <= target; returns -1 if none
    private int lastEndAtMost(int[] ends, int target, int hi) {
        int lo = 0, res = -1, h = hi - 1;
        while (lo <= h) {
            int mid = (lo + h) >>> 1;
            if (ends[mid] <= target) { res = mid; lo = mid + 1; }
            else h = mid - 1;
        }
        return res;
    }
}
```

**Dry run.** Intervals `[[1,3,5],[2,5,6],[4,6,5],[6,7,4]]` sorted by end: same order, ends `[3,5,6,7]`. dp[1]=5. i=2 start2 → p=-1, take=6, dp[2]=max(5,6)=6. i=3 start4 → p=0 (end3≤4), take=5+dp[1]=10, dp[3]=max(6,10)=10. i=4 start6 → p=2 (end6≤6), take=4+dp[3]=14, dp[4]=max(10,14)=14. Answer `14`.

**Complexity** — Time `O(n log n)`, Space `O(n)`. **Edge cases:** all intervals overlap (pick max single weight); disjoint intervals (take all); equal ends with different starts (binary search on ends handles ties via `<=`).

---

### Problem 30: IPO — Maximize Capital with k Projects (hard)

**Statement.** You can complete at most `k` projects starting with capital `w`. Project `i` needs `capital[i]` to start and yields `profits[i]` (added to capital on completion). Each project once. Maximize final capital.
**Constraints.** `1 ≤ k ≤ 10⁵`, `0 ≤ w ≤ 10⁹`, arrays up to `10⁵`.

**Approach.** Double-greedy with two heaps. Sort projects by `capital` ascending. Maintain a **max-heap of profits** for all currently affordable projects. Each of the `k` rounds: unlock every project whose capital ≤ current `w` (push its profit to the max-heap), then greedily take the highest-profit affordable project (pop the heap) and add it to `w`. This is optimal because taking the largest available profit each round never reduces future options — more capital only *unlocks* more projects (monotone), so a locally maximal profit grab stays ahead.
- *Why not just sort by profit?* A high-profit project may be unaffordable now; affordability changes as capital grows, so you must re-evaluate the affordable set each round — hence the lazy-unlock heap.

```java
import java.util.*;

class Solution {
    public int findMaximizedCapital(int k, int w, int[] profits, int[] capital) {
        int n = profits.length;
        int[][] proj = new int[n][2];
        for (int i = 0; i < n; i++) { proj[i][0] = capital[i]; proj[i][1] = profits[i]; }
        Arrays.sort(proj, (a, b) -> Integer.compare(a[0], b[0]));  // by capital asc
        PriorityQueue<Integer> affordable = new PriorityQueue<>(Collections.reverseOrder());
        int idx = 0;
        for (int round = 0; round < k; round++) {
            while (idx < n && proj[idx][0] <= w) {     // unlock affordable
                affordable.offer(proj[idx][1]);
                idx++;
            }
            if (affordable.isEmpty()) break;           // nothing affordable
            w += affordable.poll();                    // take max profit
        }
        return w;
    }
}
```

**Dry run.** `k=2`, `w=0`, `profits=[1,2,3]`, `capital=[0,1,1]`. Sorted by capital: `(0,1),(1,2),(1,3)`. Round1: unlock (0,1) → heap{1}; take 1, w=1. Round2: unlock (1,2),(1,3) → heap{3,2}; take 3, w=4. Answer `4`.

**Complexity** — Time `O(n log n + k log n)`, Space `O(n)`. **Edge cases:** no project affordable initially (`w` too low → return `w`); `k` exceeds number of projects (stop when heap empties); all capitals `0` (pure max-profit selection).

---

### Problem 31: Maximum Performance of a Team (hard)

**Statement.** Given `n` engineers with `speed[i]` and `efficiency[i]`, pick at most `k` engineers to maximize *performance* = `(sum of chosen speeds) × (minimum chosen efficiency)`. Return the answer modulo `10⁹+7`.
**Constraints.** `1 ≤ k ≤ n ≤ 10⁵`, values up to `10⁵`.

**Approach.** Sort engineers by **efficiency descending**. Iterate: when you consider engineer `i`, fix them as the team's *minimum-efficiency* member — every engineer already seen has efficiency ≥ `eff[i]`, so they are valid teammates. To maximize the speed-sum under this minimum, keep a **min-heap of the chosen speeds of size ≤ k−1** among the earlier (higher-efficiency) engineers, plus `speed[i]`. Track a running `speedSum`; if the heap exceeds `k−1`, evict the smallest speed. The greedy choice: among engineers with efficiency ≥ current minimum, the largest speeds maximize the product — and processing efficiency-descending lets each engineer act as the pivot minimum exactly once.

```
sort by efficiency DESC:  e1 ≥ e2 ≥ ... ≥ en
when at i, min-eff = eff[i]; best speed-sum = speed[i] + (top k-1 speeds before i)
min-heap of size k-1 holds the best earlier speeds; evict smallest when full
```

```java
import java.util.*;

class Solution {
    public int maxPerformance(int n, int[] speed, int[] efficiency, int k) {
        int[][] eng = new int[n][2];
        for (int i = 0; i < n; i++) { eng[i][0] = efficiency[i]; eng[i][1] = speed[i]; }
        Arrays.sort(eng, (a, b) -> Integer.compare(b[0], a[0])); // efficiency desc
        PriorityQueue<Integer> minSpeeds = new PriorityQueue<>(); // smallest speed on top
        long speedSum = 0, best = 0;
        for (int[] e : eng) {
            int eff = e[0], sp = e[1];
            minSpeeds.offer(sp);
            speedSum += sp;
            if (minSpeeds.size() > k) {           // keep at most k engineers
                speedSum -= minSpeeds.poll();     // drop slowest
            }
            best = Math.max(best, speedSum * eff);
        }
        return (int) (best % 1_000_000_007L);
    }
}
```

**Dry run.** `speed=[2,10,3,1,5,8]`, `efficiency=[5,4,3,9,7,2]`, `k=2`. Sort by eff desc: (9,1),(7,5),(5,2),(4,10),(3,3),(2,8). At (7,5): heap{1,5} sum6, perf=6·7=42. At (4,10): add10 sum16, size3>2 → drop1 sum15, perf=15·4=60. Best stays `60`.

**Complexity** — Time `O(n log n)`, Space `O(n)`. **Edge cases:** `k = 1` (best single `speed × efficiency`); ties in efficiency (any order valid since the pivot's efficiency is the same); overflow — accumulate in `long`, mod only at the end.

---

### Problem 32: Car Pooling — Capacity Feasibility (medium)

**Statement.** A car has `capacity` seats and only drives east. Given trips `trips[i] = [numPassengers, from, to]`, return whether all pickups/drop-offs fit without ever exceeding capacity.
**Constraints.** `1 ≤ trips.length ≤ 1000`, `1 ≤ numPassengers ≤ 100`, `0 ≤ from < to ≤ 1000`, `1 ≤ capacity ≤ 10⁵`.

**Approach.** This is the **interval-overlap / minimum-platforms sweep** specialized to a capacity check. Use a difference array over the location axis: add passengers at `from`, subtract them at `to` (drop-off frees the seat exactly at `to`). Sweep locations left to right accumulating the running occupancy; if it ever exceeds `capacity`, fail. The greedy/sweep insight is identical to counting maximum concurrent intervals — we only need the running peak, not which trips overlap.
- *Heap alternative:* sort events `(location, delta)` and process in order — `O(n log n)`. The bounded coordinate range (`≤ 1000`) lets the difference array do it in `O(n + maxLoc)`.

```java
class Solution {
    public boolean carPooling(int[][] trips, int capacity) {
        int[] diff = new int[1001];        // locations 0..1000
        for (int[] t : trips) {
            diff[t[1]] += t[0];            // board at 'from'
            diff[t[2]] -= t[0];            // leave at 'to'
        }
        int occupancy = 0;
        for (int loc = 0; loc <= 1000; loc++) {
            occupancy += diff[loc];
            if (occupancy > capacity) return false;
        }
        return true;
    }
}
```

**Dry run.** `trips=[[2,1,5],[3,3,7]]`, `capacity=4`. diff[1]+=2, diff[5]-=2, diff[3]+=3, diff[7]-=3. Sweep: loc1→2, loc3→5 > 4 → `false`.

**Complexity** — Time `O(n + maxLoc)`, Space `O(maxLoc)`. **Edge cases:** drop-off and pickup at the same location (drop-off applied first via `to` decrement → correct); a single trip exceeding capacity; trips that never overlap.

---

### Problem 33: Maximum Units on a Truck (Bounded Greedy) (medium)

**Statement.** Given `boxTypes[i] = [numberOfBoxes, unitsPerBox]` and a truck holding at most `truckSize` boxes, maximize total units loaded.
**Constraints.** `1 ≤ boxTypes.length ≤ 1000`, values up to `1000`, `1 ≤ truckSize ≤ 10⁶`.

**Approach.** A bounded form of fractional knapsack where "fraction" is replaced by "take whole boxes from a type." Sort box types by `unitsPerBox` descending and greedily fill: take as many boxes as possible from the densest type, then the next, until the truck is full. Because every box occupies exactly one slot, taking the highest-units box for each slot is optimal by a direct exchange argument — replacing any loaded box with a denser unloaded one never decreases units.

```java
import java.util.Arrays;

class Solution {
    public int maximumUnits(int[][] boxTypes, int truckSize) {
        Arrays.sort(boxTypes, (a, b) -> Integer.compare(b[1], a[1])); // units desc
        int units = 0;
        for (int[] bt : boxTypes) {
            if (truckSize == 0) break;
            int take = Math.min(bt[0], truckSize);   // boxes from this type
            units += take * bt[1];
            truckSize -= take;
        }
        return units;
    }
}
```

**Dry run.** `boxTypes=[[1,3],[2,2],[3,1]]`, `truckSize=4`. Sort by units desc → `[1,3],[2,2],[3,1]`. Take 1×3 (size3), 2×2 (size1, +4), 1×1 (+1). Units `3+4+1 = 8`.

**Complexity** — Time `O(n log n)`, Space `O(1)`. **Edge cases:** `truckSize` larger than all boxes (load everything); a single box type; ties in units (order irrelevant since each slot is one box).

---

### Problem 34: Reorganize String — No Two Adjacent Equal (hard)

**Statement.** Rearrange string `s` so no two adjacent characters are the same. Return any valid arrangement, or `""` if impossible.
**Constraints.** `1 ≤ s.length ≤ 500`, lowercase letters.

**Approach.** Greedy with a max-heap keyed by remaining frequency. Feasibility first: if any character's count exceeds `⌈n/2⌉`, no arrangement exists. Then repeatedly pop the **most frequent** remaining character, append it, and *hold it aside* until the next character is placed (so you never place the same char twice in a row). Push the held-back character once a different one has been emitted. Greedily placing the most frequent char as early and as often as legally possible prevents it from "piling up" at the end — the classic exchange argument for spread-out scheduling (same skeleton as Task Scheduler).

```
heap by freq desc.  prev = char just placed (cooling down).
pop top → append → decrement → if prev still has count, push prev back → prev = top
```

```java
import java.util.*;

class Solution {
    public String reorganizeString(String s) {
        int[] freq = new int[26];
        for (char c : s.toCharArray()) freq[c - 'a']++;
        int n = s.length();
        // (char, count); max-heap by count
        PriorityQueue<int[]> heap = new PriorityQueue<>((a, b) -> b[1] - a[1]);
        for (int c = 0; c < 26; c++) {
            if (freq[c] > (n + 1) / 2) return "";   // infeasible
            if (freq[c] > 0) heap.offer(new int[]{c, freq[c]});
        }
        StringBuilder sb = new StringBuilder();
        int[] prev = null;                          // cooling-down char
        while (!heap.isEmpty()) {
            int[] cur = heap.poll();
            sb.append((char) ('a' + cur[0]));
            cur[1]--;
            if (prev != null && prev[1] > 0) heap.offer(prev); // re-enable previous
            prev = cur;                             // current now cools down
        }
        return sb.length() == n ? sb.toString() : "";
    }
}
```

**Dry run.** `s="aab"`. freq a=2,b=1, n=3, ⌈3/2⌉=2 ok. heap{a:2,b:1}. pop a→"a", prev=a(1). pop b→"ab", push a back, prev=b(0). pop a→"aba". Result `"aba"`.

**Complexity** — Time `O(n log 26) = O(n)`, Space `O(1)` (fixed alphabet). **Edge cases:** single character (`"a"` valid); one char dominates (`"aaab"` → `""`); even split (`"aabb"` → `"abab"`).

---

### Problem 35: Minimum Deletions for Unique Character Frequencies (medium)

**Statement.** Given a string `s`, delete the minimum number of characters so that no two distinct characters have the same frequency. Return the deletion count.
**Constraints.** `1 ≤ s.length ≤ 10⁵`, lowercase letters.

**Approach.** Count each character's frequency. Sort frequencies descending and greedily resolve collisions: keep a `set` (or track the last "allowed" value) of frequencies already used. For each frequency, while it is non-zero and already taken, decrement it (one deletion each) until it becomes unused or hits `0`. Greedily lowering the current frequency just enough to dodge taken values minimizes deletions — pushing it lower than necessary would only delete more. Processing in descending order ensures larger frequencies grab the high slots, leaving room below.

```java
import java.util.*;

class Solution {
    public int minDeletions(String s) {
        int[] freq = new int[26];
        for (char c : s.toCharArray()) freq[c - 'a']++;
        Set<Integer> used = new HashSet<>();
        int deletions = 0;
        for (int f : freq) {
            while (f > 0 && used.contains(f)) {   // collision → delete one
                f--;
                deletions++;
            }
            if (f > 0) used.add(f);
        }
        return deletions;
    }
}
```

**Dry run.** `s="aaabbbcc"`. freq a=3,b=3,c=2. Process 3 → used{3}. Process 3 → taken, 3→2 (del1), 2 free → used{3,2}. Process 2 → taken, 2→1 (del2), used{3,2,1}. Deletions `2`.

**Complexity** — Time `O(n + K log K)` where `K ≤ 26` (effectively `O(n)`), Space `O(K)`. **Edge cases:** all frequencies already distinct (0 deletions); many characters sharing one frequency (cascading decrements); a frequency forced to `0` (that character fully removed — frequency `0` is allowed to repeat).

---

### Problem 36: Wiggle Subsequence — Longest Alternating Run (medium)

**Statement.** A wiggle sequence has strictly alternating up/down differences. Return the length of the longest wiggle *subsequence* of `nums` (elements need not be contiguous).
**Constraints.** `1 ≤ nums.length ≤ 1000`, values fit in `int`.

**Approach.**
- *DP baseline:* `up[i]`/`down[i]` = longest wiggle ending at `i` rising/falling — `O(n²)`, or `O(n)` with rolling states.
- *Optimal greedy (count direction changes):* the answer equals `1 +` the number of times the difference sign *flips* as you scan. Track `up` and `down` counters: on a rise set `up = down + 1`; on a fall set `down = up + 1`; equal elements change nothing. Each direction flip extends the alternating chain by exactly one, and flat stretches contribute nothing — so counting flips greedily yields the longest possible wiggle without enumerating subsequences.

```
nums:  1   7   4   9   2   5
diff:    +   -   +   -   +     (5 flips counted as alternations)
up/down each climbs on its turn; answer = max(up, down)
```

```java
class Solution {
    public int wiggleMaxLength(int[] nums) {
        if (nums.length < 2) return nums.length;
        int up = 1, down = 1;
        for (int i = 1; i < nums.length; i++) {
            if (nums[i] > nums[i - 1]) up = down + 1;
            else if (nums[i] < nums[i - 1]) down = up + 1;
            // equal: neither changes
        }
        return Math.max(up, down);
    }
}
```

**Dry run.** `nums=[1,17,5,10,13,15,10,5,16,8]`. Flips: +,-,+,+,+,-,-,+,- → meaningful alternations give up/down climbing to `7`. Answer `7`.

**Complexity** — Time `O(n)`, Space `O(1)`. **Edge cases:** all equal (`[2,2,2]` → length 1); strictly monotone (length 2); single element (length 1); leading/trailing flats absorbed without inflating the count.

---

### Problem 37: Hand of Straights — Partition into Consecutive Groups (medium)

**Statement.** Given an integer array `hand` and a `groupSize`, determine whether the cards can be rearranged into groups of exactly `groupSize` consecutive increasing integers.
**Constraints.** `1 ≤ hand.length ≤ 10⁴`, `1 ≤ groupSize ≤ hand.length`, values fit in `int`.

**Approach.** Count card frequencies in a `TreeMap` (sorted keys). Greedily, the **smallest remaining card must be the start of some group** — nothing smaller exists to precede it. So repeatedly take the minimum key `m` and require `m, m+1, ..., m+groupSize−1` to all be present, decrementing each (removing keys that hit zero). If any required consecutive card is missing, partition is impossible. This greedy is forced: there is no choice about where the smallest card goes, so committing it to the lowest group is always correct.
- *Frequency-map alternative without TreeMap:* sort the array and use a HashMap, iterating cards in sorted order — same `O(n log n)`.

```java
import java.util.*;

class Solution {
    public boolean isNStraightHand(int[] hand, int groupSize) {
        if (hand.length % groupSize != 0) return false;
        TreeMap<Integer, Integer> count = new TreeMap<>();
        for (int c : hand) count.merge(c, 1, Integer::sum);
        while (!count.isEmpty()) {
            int start = count.firstKey();               // smallest must lead a group
            for (int card = start; card < start + groupSize; card++) {
                Integer cnt = count.get(card);
                if (cnt == null) return false;          // missing consecutive card
                if (cnt == 1) count.remove(card);
                else count.put(card, cnt - 1);
            }
        }
        return true;
    }
}
```

**Dry run.** `hand=[1,2,3,6,2,3,4,7,8]`, `groupSize=3`. Map sorted: {1,2:2,3:2,4,6,7,8}. start1 → use 1,2,3 → {2,3,4,6,7,8}. start2 → use 2,3,4 → {6,7,8}. start6 → use 6,7,8 → empty. `true`.

**Complexity** — Time `O(n log n)` (TreeMap ops), Space `O(n)`. **Edge cases:** length not divisible by `groupSize` → immediate `false`; `groupSize == 1` (always true); duplicates that span multiple parallel groups; a gap in consecutiveness → `false`. (This is identical to LeetCode "Divide Array in Sets of K Consecutive Numbers.")

---

### Problem 38: Minimum Cost to Connect Sticks (medium)

**Statement.** You have sticks of given lengths. Combining two sticks of lengths `x` and `y` costs `x + y` and yields one stick of length `x + y`. Return the minimum total cost to combine all sticks into one.
**Constraints.** `1 ≤ sticks.length ≤ 10⁴`, `1 ≤ sticks[i] ≤ 10⁴`.

**Approach.** This is **Huffman coding's optimal-merge pattern** (same as "connect ropes"). Use a min-heap; repeatedly pop the two smallest sticks, pay their sum, and push the combined stick back. Greedily merging the two cheapest available pieces keeps small lengths from being re-added (and thus re-paid) many times — a stick's length contributes to the cost once per merge it participates in, so the smallest lengths should be merged earliest (deepest in the merge tree). The exchange argument is exactly Huffman's: swapping a less-frequent (shorter) stick deeper never increases total weighted depth.

```java
import java.util.PriorityQueue;

class Solution {
    public int connectSticks(int[] sticks) {
        PriorityQueue<Integer> heap = new PriorityQueue<>();
        for (int s : sticks) heap.offer(s);
        int cost = 0;
        while (heap.size() > 1) {
            int a = heap.poll();          // two shortest
            int b = heap.poll();
            int merged = a + b;
            cost += merged;               // pay the combine cost
            heap.offer(merged);
        }
        return cost;
    }
}
```

**Dry run.** `sticks=[2,4,3]`. Merge 2+3=5 (cost5), heap{4,5}. Merge 4+5=9 (cost14). Total `14`. (Merging 2+4 first would cost 6+9=15 — worse, confirming greedy.)

**Complexity** — Time `O(n log n)`, Space `O(n)`. **Edge cases:** a single stick (cost `0`, no merges); two sticks (one merge); large sums — use `long` if lengths/counts could overflow `int` (here bounded, `int` suffices). This generalizes to k-way merge (merge `k` sticks per step) which needs the heap padded so `(n−1) % (k−1) == 0`.

---

### Problem 39: Minimize Maximum Pair Sum in Array (medium)

**Statement.** Pair up the `n` (even) elements of `nums` into `n/2` pairs to minimize the **maximum** pair sum across all pairs. Return that minimized maximum.
**Constraints.** `2 ≤ nums.length ≤ 10⁵`, `n` even, `1 ≤ nums[i] ≤ 10⁵`.

**Approach.** Sort ascending, then pair the smallest with the largest using two pointers (`i` from the front, `j` from the back). The greedy claim: pairing extremes balances the sums so the largest sum is as small as possible. Exchange argument — if the biggest element were paired with anything other than the smallest, some other pair would have to absorb a larger partner for the biggest element's old mate, raising the maximum. Therefore opposite-end pairing minimizes the peak sum. Take the max over the `n/2` pairings.

```
sorted: [1, 2, 3, 4, 5, 6]
pair:    1+6=7   2+5=7   3+4=7    →  max = 7  (any other pairing has a pair > 7)
```

```java
import java.util.Arrays;

class Solution {
    public int minPairSum(int[] nums) {
        Arrays.sort(nums);
        int i = 0, j = nums.length - 1, maxSum = 0;
        while (i < j) {
            maxSum = Math.max(maxSum, nums[i] + nums[j]);
            i++;
            j--;
        }
        return maxSum;
    }
}
```

**Dry run.** `nums=[3,5,2,3]`. Sort → `[2,3,3,5]`. Pairs: 2+5=7, 3+3=6. Max `7`.

**Complexity** — Time `O(n log n)`, Space `O(1)` (in-place sort). **Edge cases:** exactly two elements (one pair); all equal (max = `2·value`); already sorted (no extra cost). Contrast: *maximizing* the minimum pair sum or minimizing the *sum of maxes* are different objectives needing different pairings.

---

## 🧩 Extended Problems — Set 3: Hard / Expert & Optimization

### Problem 40: Candy Distribution in O(1) Extra Space — Slope Counting (hard)

**Statement.** Same rules as the classic Candy problem (Problem 8): `n` children in a line with `ratings[i]`, each gets ≥ 1 candy, and a child with a strictly higher rating than a neighbor gets strictly more candies. Return the minimum total — but using only `O(1)` extra space (no `candy[]` array).
**Constraints.** `1 ≤ n ≤ 2·10⁴`, `0 ≤ ratings[i] ≤ 2·10⁴`.

**Approach.** Walk once and decompose the array into monotone runs. Track the length of the current `up` slope and `down` slope and the length of the last increasing run (`peak`). Each plateau (equal ratings) resets both slopes to 0 (that child only needs 1). The candies for an up-run of length `u` sum to `1+2+…+u`; for a down-run of length `d`, `1+2+…+d`. The shared peak between an up-run and the following down-run must count once at `max(u, d)+1`, which the formula handles by adding the down triangle and, if the down-run is at least as long as the prior up-run, an extra unit to lift the peak. This reproduces the two-pass answer in a single pass with no array — the classic `O(1)`-space follow-up.

```
ratings:  1  2  3  2  1
          └ up=2 ┘ peak
                └ down=2 ┘
candies:  1  2  3  2  1   (peak raised because down >= up)
total = (1+2+3) + (1+2) , peak counted once → handled by +(down>=up?…)
```

```java
class Solution {
    public int candy(int[] ratings) {
        int n = ratings.length;
        if (n == 0) return 0;
        int total = 1;            // first child gets 1
        int up = 0, down = 0, peak = 0;
        for (int i = 1; i < n; i++) {
            if (ratings[i] > ratings[i - 1]) {       // rising
                up++; down = 0; peak = up;
                total += up + 1;                     // 1 base + up increment
            } else if (ratings[i] == ratings[i - 1]) { // plateau
                up = down = peak = 0;
                total += 1;
            } else {                                  // falling
                up = 0; down++;
                // add the down step; if it overtakes the peak, lift the peak by 1
                total += down + (peak >= down ? 0 : 1);
            }
        }
        return total;
    }
}
```

**Dry run.** `ratings=[1,2,3,2,1]`. total=1. i=1 up=1 → +2 (=3). i=2 up=2 → +3 (=6). i=3 down=1, peak=2≥1 → +1 (=7). i=4 down=2, peak=2≥2 → +2 (=9). Total `9` (candies `1,2,3,2,1`).

**Complexity** — Time `O(n)`, Space `O(1)`. **Edge cases:** strictly increasing then decreasing (peak must be the max of both slope lengths +1); long plateaus reset slopes; single child (`1`); the `peak >= down` test is what avoids double-counting the shared summit.

---

### Problem 41: Course Schedule III — Maximize Courses Taken by Deadline (hard)

**Statement.** Each course `[duration, lastDay]` must finish on or before `lastDay`; courses run sequentially starting at day 1. Return the maximum number of courses you can take.
**Constraints.** `1 ≤ courses.length ≤ 10⁴`, `1 ≤ duration, lastDay ≤ 10⁴`.

**Approach.** Sort courses by deadline ascending — you must consider tighter deadlines first because a course with an early deadline can never be deferred. Maintain a running `time` (total duration committed) and a **max-heap of chosen durations**. For each course, tentatively take it: `time += duration`. If `time` now exceeds the course's deadline, the schedule is infeasible, so evict the *longest* course taken so far (the heap top) if it is longer than the current one — swapping a long course for a shorter one keeps the same count but frees time, never hurting future choices. This exchange (drop the heaviest committed course when over budget) is the greedy crux.

```
sort by deadline.  heap = durations of currently-kept courses (max on top).
for each course: time += dur; push dur.
  if time > deadline: time -= heap.pop()  (drop longest, keep count optimal)
answer = heap.size()
```

```java
import java.util.*;

class Solution {
    public int scheduleCourse(int[][] courses) {
        Arrays.sort(courses, (a, b) -> Integer.compare(a[1], b[1])); // by deadline
        PriorityQueue<Integer> taken = new PriorityQueue<>(Collections.reverseOrder());
        int time = 0;
        for (int[] c : courses) {
            int dur = c[0], deadline = c[1];
            time += dur;
            taken.offer(dur);
            if (time > deadline) {           // over budget → drop the longest
                time -= taken.poll();
            }
        }
        return taken.size();
    }
}
```

**Dry run.** `courses=[[100,200],[200,1300],[1000,1250],[2000,3200]]`. Sort by deadline → `[100,200],[1000,1250],[200,1300],[2000,3200]`. Take 100 (time100). Take 1000 (time1100≤1250). Take 200 (time1300≤1300). Take 2000 (time3300>3200 → drop 2000, time1300). Kept 3. Answer `3`.

**Complexity** — Time `O(n log n)`, Space `O(n)`. **Edge cases:** a single course longer than its own deadline (never taken — gets pushed then immediately popped); all courses share a deadline (greedy keeps the shortest ones); ties in deadline (order among them is irrelevant to the count).

---

### Problem 42: Minimum Number of Refueling Stops (hard)

**Statement.** A car starts with `startFuel` and must travel `target` miles. Stations `stations[i] = [position, fuel]` lie en route. Each mile burns one unit of fuel. Return the minimum number of refueling stops to reach `target`, or `−1` if impossible.
**Constraints.** `1 ≤ target, startFuel ≤ 10⁹`, `0 ≤ stations.length ≤ 500`, positions strictly increasing.

**Approach.** Greedy with a **max-heap of fuel passed but not yet used**. Drive forward; as you pass each station, *defer* the decision by pushing its fuel onto a max-heap rather than refueling immediately. Whenever your current reach is insufficient to make the next station (or the target), retroactively "refuel" by popping the largest deferred fuel — this is optimal because if you must add fuel, adding the biggest available tank minimizes the number of stops (greedy-stays-ahead on distance per stop). If the heap empties before you can advance, it is impossible. The trick of deferring choices and committing the best one lazily is the senior-level pattern here.

```
positions →  s1   s2   s3        target
fuel passed pushed to max-heap; pop biggest only when stuck.
reach = startFuel + (sum of fuels actually committed)
```

```java
import java.util.*;

class Solution {
    public int minRefuelStops(int target, int startFuel, int[][] stations) {
        PriorityQueue<Integer> heap = new PriorityQueue<>(Collections.reverseOrder());
        long reach = startFuel;
        int stops = 0, i = 0, n = stations.length;
        while (reach < target) {
            while (i < n && stations[i][0] <= reach) {  // bank all reachable fuel
                heap.offer(stations[i][1]);
                i++;
            }
            if (heap.isEmpty()) return -1;              // stuck, nothing to burn
            reach += heap.poll();                       // commit the largest tank
            stops++;
        }
        return stops;
    }
}
```

**Dry run.** `target=100`, `startFuel=10`, `stations=[[10,60],[20,30],[30,30],[60,40]]`. reach=10. Bank [10→60]. reach<100 → pop60, reach70, stops1. Bank [20→30],[30→30],[60→40] → heap{40,30,30}. reach70<100 → pop40, reach110, stops2 ≥100. Answer `2`.

**Complexity** — Time `O(n log n)`, Space `O(n)`. **Edge cases:** `startFuel ≥ target` (0 stops); no stations and not enough fuel (`−1`); a station exactly at `reach` boundary is bankable; positions must be passed before their fuel can be used (the `<= reach` gate enforces it). Use `long` for `reach` to avoid overflow near `10⁹`.

---

### Problem 43: Create Maximum Number from Two Arrays (hard)

**Statement.** Given two integer-digit arrays `nums1`, `nums2` and an integer `k`, create the maximum number of length `k` by picking digits from both (each array's relative order preserved) and interleaving them. Return the `k` digits.
**Constraints.** `0 ≤ k ≤ nums1.length + nums2.length`, digits `0–9`.

**Approach.** Decompose into two greedy subproblems. (1) **Max subsequence of length `t`** from a single array via a monotonic stack: while the stack top is smaller than the current digit and enough digits remain to still reach length `t`, pop — this keeps the largest leading digits. (2) **Merge** two such subsequences into the lexicographically largest interleaving by repeatedly taking from whichever array is "greater" at the current suffix comparison. Try every split `i + j = k` with `i` digits from `nums1` and `j` from `nums2`, build the best candidate, and keep the overall maximum. Both the pick-max-subsequence and the greedy-merge are exchange-argument-optimal; the outer loop over splits handles the cross-array allocation.

```java
import java.util.*;

class Solution {
    public int[] maxNumber(int[] nums1, int[] nums2, int k) {
        int m = nums1.length, n = nums2.length;
        int[] best = new int[k];
        for (int i = Math.max(0, k - n); i <= Math.min(k, m); i++) {
            int[] cand = merge(maxSub(nums1, i), maxSub(nums2, k - i));
            if (greater(cand, 0, best, 0)) best = cand;
        }
        return best;
    }

    // largest subsequence of length t (monotonic-stack greedy)
    private int[] maxSub(int[] nums, int t) {
        int[] stack = new int[t];
        int top = -1, n = nums.length;
        for (int i = 0; i < n; i++) {
            while (top >= 0 && stack[top] < nums[i] && (n - i) > (t - 1 - top)) top--;
            if (top + 1 < t) stack[++top] = nums[i];
        }
        return stack;
    }

    // greedy lexicographic merge
    private int[] merge(int[] a, int[] b) {
        int[] res = new int[a.length + b.length];
        int i = 0, j = 0, r = 0;
        while (i < a.length || j < b.length) {
            res[r++] = greater(a, i, b, j) ? a[i++] : b[j++];
        }
        return res;
    }

    // is a[i:] lexicographically >= b[j:] ?
    private boolean greater(int[] a, int i, int[] b, int j) {
        while (i < a.length && j < b.length && a[i] == b[j]) { i++; j++; }
        return j == b.length || (i < a.length && a[i] > b[j]);
    }
}
```

**Dry run.** `nums1=[3,4,6,5]`, `nums2=[9,1,2,5,8,3]`, `k=5`. Best split takes `[9,8,3]` from nums2 and `[6,5]` from nums1, merged to `[9,8,6,5,3]`. Answer `[9,8,6,5,3]`.

**Complexity** — Time `O(k·(m+n+k))` over all splits and merges, Space `O(k)`. **Edge cases:** `k = 0` (empty); one array empty (single-array max subsequence); equal digits during merge (suffix comparison decides correctly, avoiding a wrong early pick); `k` equals total length (use everything).

---

### Problem 44: Patching Array — Fewest Additions to Cover [1, n] (hard)

**Statement.** Given a sorted array `nums` and integer `n`, return the minimum number of patches (numbers you may add anywhere) so that every integer in `[1, n]` is expressible as a sum of some subset of the array.
**Constraints.** `1 ≤ nums.length ≤ 1000`, `1 ≤ nums[i] ≤ 10⁴`, `1 ≤ n ≤ 2³¹−1`.

**Approach.** Maintain `miss` = the smallest sum **not yet** coverable; initially `1`, meaning `[1, miss)` is fully covered. If the next array element `nums[i] ≤ miss`, using it extends coverage to `[1, miss + nums[i])`, so advance `miss += nums[i]`. Otherwise there is a gap: greedily patch with exactly `miss` itself — the largest number that still keeps `[1, miss)` contiguous — which doubles coverage to `[1, 2·miss)`. Patching with `miss` is provably optimal: any smaller patch covers less, any larger leaves a gap below it. Repeat until `miss > n`. This doubling argument gives a logarithmic number of patches in the worst case.

```
covered window [1, miss).  invariant: everything below miss is representable.
 nums[i] <= miss  → free extension: miss += nums[i]
 else             → patch miss:     miss += miss (patches++)
```

```java
class Solution {
    public int minPatches(int[] nums, int n) {
        long miss = 1;          // smallest sum not yet representable
        int patches = 0, i = 0;
        while (miss <= n) {
            if (i < nums.length && nums[i] <= miss) {
                miss += nums[i];   // extend coverage for free
                i++;
            } else {
                miss += miss;      // patch with 'miss', doubling coverage
                patches++;
            }
        }
        return patches;
    }
}
```

**Dry run.** `nums=[1,3]`, `n=6`. miss=1: nums[0]=1≤1 → miss=2,i=1. miss=2: nums[1]=3>2 → patch 2, miss=4,patches=1. miss=4: nums[1]=3≤4 → miss=7,i=2. miss=7>6 → stop. Answer `1` (add `2`).

**Complexity** — Time `O(m + log n)` where `m = nums.length`, Space `O(1)`. **Edge cases:** array already covers `[1,n]` (0 patches); empty array needs ~`log₂ n` patches; `miss` can exceed `int` near `n = 2³¹−1` so use `long`; numbers in `nums` larger than `miss` are skipped until coverage catches up.

---

### Problem 45: Split Array into Consecutive Subsequences (hard)

**Statement.** Given a **sorted** integer array `nums`, determine whether it can be split into one or more subsequences of consecutive increasing integers, each of length at least 3.
**Constraints.** `1 ≤ nums.length ≤ 10⁴`, values fit in `int`, `nums` is sorted ascending.

**Approach.** Two hash maps: `count[x]` = how many of value `x` remain, and `tail[x]` = how many existing subsequences end at `x` (and could be extended by `x+1`). For each number `x` in order: if it can **append** to an existing subsequence ending at `x−1` (`tail[x-1] > 0`), do that greedily — extending is always preferred over starting a new run because a started run needs two more numbers. Otherwise try to **start** a new run `x, x+1, x+2` (require `count[x+1] > 0` and `count[x+2] > 0`). If neither is possible, fail. Preferring extension over creation is the greedy exchange argument: leaving a danglable tail unextended can only force an impossible short run later.

```java
import java.util.*;

class Solution {
    public boolean isPossible(int[] nums) {
        Map<Integer, Integer> count = new HashMap<>();
        Map<Integer, Integer> tail = new HashMap<>();   // runs ending at key
        for (int x : nums) count.merge(x, 1, Integer::sum);
        for (int x : nums) {
            if (count.get(x) == 0) continue;             // already consumed
            if (tail.getOrDefault(x - 1, 0) > 0) {       // extend a run
                tail.merge(x - 1, -1, Integer::sum);
                tail.merge(x, 1, Integer::sum);
            } else if (count.getOrDefault(x + 1, 0) > 0
                    && count.getOrDefault(x + 2, 0) > 0) { // start x,x+1,x+2
                count.merge(x + 1, -1, Integer::sum);
                count.merge(x + 2, -1, Integer::sum);
                tail.merge(x + 2, 1, Integer::sum);
            } else {
                return false;                            // x can neither extend nor start
            }
            count.merge(x, -1, Integer::sum);
        }
        return true;
    }
}
```

**Dry run.** `nums=[1,2,3,3,4,5]`. Start run 1,2,3 (consume). At second 3: no tail at 2 (extended already used), start 3,4,5 (consume). All used → `true` (`[1,2,3]` and `[3,4,5]`).

**Complexity** — Time `O(n)`, Space `O(n)`. **Edge cases:** length < 3 (`false`); duplicates that must seed parallel runs; a value that can neither extend nor seed a triple (`[1,2,3,4,4,5,5]` style) → `false`; the order of checking extend-before-start is essential for correctness.

---

### Problem 46: Remove Duplicate Letters — Smallest Lexicographic Result (hard)

**Statement.** Given a string `s`, remove duplicate letters so that every letter appears exactly once and the result is the smallest in lexicographic order among all such unique-letter results. Return that string.
**Constraints.** `1 ≤ s.length ≤ 10⁴`, lowercase letters. (Identical to "Smallest Subsequence of Distinct Characters.")

**Approach.** Monotonic-stack greedy with a last-occurrence guard. Precompute `last[c]` = the final index of each character. Scan; if the character is already on the stack, skip it. Otherwise, while the stack top is larger than the current character **and** that top character appears again later (`last[top] > i`), pop it — we can safely defer it to its later occurrence to get a smaller prefix. Push the current character and mark it present. This greedily minimizes each position from the most significant end while guaranteeing every distinct letter still gets placed (the `last[] > i` check prevents popping a letter we would never see again).

```
s = "cbacdcbc"
stack grows; pop top t if t > cur AND t reappears later.
result builds the lexicographically smallest distinct-letter subsequence → "acdb"
```

```java
class Solution {
    public String removeDuplicateLetters(String s) {
        int[] last = new int[26];
        for (int i = 0; i < s.length(); i++) last[s.charAt(i) - 'a'] = i;
        boolean[] inStack = new boolean[26];
        StringBuilder st = new StringBuilder();       // monotonic stack
        for (int i = 0; i < s.length(); i++) {
            int c = s.charAt(i) - 'a';
            if (inStack[c]) continue;                 // keep first chance only
            while (st.length() > 0) {
                int top = st.charAt(st.length() - 1) - 'a';
                if (top > c && last[top] > i) {       // safe to pop (appears later)
                    st.deleteCharAt(st.length() - 1);
                    inStack[top] = false;
                } else break;
            }
            st.append((char) ('a' + c));
            inStack[c] = true;
        }
        return st.toString();
    }
}
```

**Dry run.** `s="bcabc"`. last b=3,c=4,a=2. i0 'b'→"b". i1 'c'→"bc". i2 'a': pop 'c'(last4>2), pop 'b'(last3>2) → "a", push → "a". i3 'b'→"ab". i4 'c'→"abc". Result `"abc"`.

**Complexity** — Time `O(n)` (each char pushed/popped once), Space `O(1)` (fixed alphabet arrays). **Edge cases:** all distinct (returns input order minimized only where pops are safe); all same letter (`"aaaa"` → `"a"`); the `last[top] > i` guard is what prevents dropping a letter's only remaining occurrence.

---

### Problem 47: Smallest Range Covering Elements from K Lists (hard)

**Statement.** Given `k` sorted integer lists, find the smallest range `[a, b]` that includes at least one number from each list (smallest by width; tie-break by smaller `a`).
**Constraints.** `1 ≤ k ≤ 3500`, total elements up to `10⁵`, values fit in `int`.

**Approach.** A greedy frontier with a min-heap, the multi-list merge pattern. Put the first element of each list into a min-heap and track the current `max` across the heap's elements. The range `[heapMin, max]` always covers one element per list. Repeatedly pop the minimum (the bottleneck shrinking the range from the left), record the range if it improved, then push the *next* element from the same list — advancing the smallest pointer is the only move that can possibly shrink the range, since the max is fixed until a new element raises it. Stop when any list is exhausted (its minimum can no longer be raised). Always advancing the current minimum is the greedy invariant.

```
heap holds one frontier element per list; max = largest frontier value.
range = [heap.min, max].  pop min (advance that list) → may shrink width.
stop when the popped list has no next element.
```

```java
import java.util.*;

class Solution {
    public int[] smallestRange(List<List<Integer>> nums) {
        // heap entries: [value, listIndex, elementIndex]
        PriorityQueue<int[]> heap =
            new PriorityQueue<>((a, b) -> Integer.compare(a[0], b[0]));
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < nums.size(); i++) {
            int v = nums.get(i).get(0);
            heap.offer(new int[]{v, i, 0});
            max = Math.max(max, v);
        }
        int bestLo = 0, bestHi = Integer.MAX_VALUE;
        while (true) {
            int[] top = heap.poll();
            int lo = top[0];
            if (max - lo < bestHi - bestLo) {        // strictly smaller width
                bestLo = lo; bestHi = max;
            }
            int li = top[1], ei = top[2];
            if (ei + 1 == nums.get(li).size()) break; // list exhausted → done
            int next = nums.get(li).get(ei + 1);
            max = Math.max(max, next);
            heap.offer(new int[]{next, li, ei + 1});
        }
        return new int[]{bestLo, bestHi};
    }
}
```

**Dry run.** `[[4,10,15,24],[0,9,12,20],[5,18,22,30]]`. Frontier {4,0,5} max5 → range [0,5] w5. Advance 0→9, max9, frontier{4,9,5}→ pop4 range[4,9] w5 (not smaller). Advance 4→10,max10 ... eventually smallest is `[20,24]` width 4. Answer `[20,24]`.

**Complexity** — Time `O(N log k)` where `N` = total elements, Space `O(k)`. **Edge cases:** single list (range is `[x,x]` for its min); identical numbers across lists (width 0); a very short list ends the search early; strict-improvement comparison ensures the smallest-`a` tie-break.

---

### Problem 48: Minimum Number of Increments on Subarrays to Form Target (hard)

**Statement.** Starting from an all-zero array, in one operation you choose a contiguous subarray and increment every element by 1. Return the minimum number of operations to turn the zero array into `target`.
**Constraints.** `1 ≤ target.length ≤ 10⁵`, `1 ≤ target[i] ≤ 10⁵`.

**Approach.** Think of building `target` as a skyline of horizontal bricks. The first element costs `target[0]` operations outright. For each subsequent element, you only pay for the *increase* over the previous element: when `target[i] > target[i-1]`, those extra `target[i] − target[i-1]` units must begin new operations here (they cannot be extensions of bricks covering the lower neighbor). When `target[i] ≤ target[i-1]`, every needed brick can extend a brick already covering the taller predecessor — cost 0. Summing positive consecutive rises is the greedy optimum, since any operation can be stretched maximally to the right for free.

```
target:  3 1 5 4 2
costs:   3 +0 +4 +0 +0   (only positive rises add operations)
total = 3 + 4 = 7
```

```java
class Solution {
    public int minNumberOperations(int[] target) {
        int ops = target[0];                 // build the first column
        for (int i = 1; i < target.length; i++) {
            if (target[i] > target[i - 1]) {
                ops += target[i] - target[i - 1];   // new bricks start here
            }
        }
        return ops;
    }
}
```

**Dry run.** `target=[3,1,1,2]`. ops=3. i=1 1<3 +0. i=2 1=1 +0. i=3 2>1 +1. Total `4`.

**Complexity** — Time `O(n)`, Space `O(1)`. **Edge cases:** strictly increasing array (`ops = target[n-1]`); all-equal array (`ops = value`); single element (`ops = target[0]`); large values stay within `int` since `ops ≤ Σtarget` but each step is bounded — use `long` accumulation if `n·max` could exceed `int`.

---

### Problem 49: Minimum Cost to Hire K Workers (hard)

**Statement.** `n` workers have `quality[i]` and minimum wage expectation `wage[i]`. To hire a group of exactly `k`, every hired worker must be paid in proportion to their quality relative to others in the group, and at least their minimum wage. Return the least total wage to hire any `k` workers. (Pay = `quality[i] × (group's max wage/quality ratio)`.)
**Constraints.** `1 ≤ k ≤ n ≤ 10⁴`, values up to `10⁴`.

**Approach.** The group's pay multiplier is its **maximum** `wage/quality` ratio. Sort workers by that ratio ascending; iterate, treating each worker as the group's *ratio-setting* member (everyone before has a smaller or equal ratio, so they're valid cheaper-rate teammates). To minimize `ratio × Σquality`, keep a **max-heap of qualities** of size `k`, evicting the largest quality when the heap exceeds `k`, and maintain `sumQuality`. At each worker, if the heap holds `k` members, the candidate cost is `ratio × sumQuality`; take the minimum. Sorting by ratio fixes the multiplier; the max-heap greedily holds the `k` smallest qualities seen so far under that multiplier — the dual-greedy crux (mirrors Maximum Performance, Problem 31).

```java
import java.util.*;

class Solution {
    public double mincostToHireWorkers(int[] quality, int[] wage, int k) {
        int n = quality.length;
        double[][] w = new double[n][2];          // [ratio, quality]
        for (int i = 0; i < n; i++) {
            w[i][0] = (double) wage[i] / quality[i];
            w[i][1] = quality[i];
        }
        Arrays.sort(w, (a, b) -> Double.compare(a[0], b[0])); // ratio asc
        PriorityQueue<Double> maxQ = new PriorityQueue<>(Collections.reverseOrder());
        double sumQ = 0, best = Double.MAX_VALUE;
        for (double[] worker : w) {
            sumQ += worker[1];
            maxQ.offer(worker[1]);
            if (maxQ.size() > k) sumQ -= maxQ.poll();   // drop largest quality
            if (maxQ.size() == k) {
                best = Math.min(best, sumQ * worker[0]); // ratio sets the wage
            }
        }
        return best;
    }
}
```

**Dry run.** `quality=[10,20,5]`, `wage=[70,50,30]`, `k=2`. Ratios: 7.0, 2.5, 6.0. Sort by ratio → (2.5,q20),(6.0,q5),(7.0,q10). At (6.0): heap{20,5} sumQ25, cost 25·6=150. At (7.0): add10 sumQ35, size3>2 → drop20 sumQ15, cost 15·7=105. Best `105.0`.

**Complexity** — Time `O(n log n)`, Space `O(n)`. **Edge cases:** `k = 1` (min over `wage[i]`); workers with identical ratios (any order valid); the heap must reach size `k` before a cost is recorded; floating-point comparison (use `Double.compare`, accept tiny epsilon in answers).

---

### Problem 50: Largest Palindromic Number from Digit Counts (medium–hard)

**Statement.** Given a string `num` of digits (`'0'`–`'9'`), pick a subset and rearrange to form the **largest** integer palindrome (no leading zeros). Return it as a string, or `"0"` if the only achievable palindrome is zero.
**Constraints.** `1 ≤ num.length ≤ 10⁵`, digits only.

**Approach.** Greedy on digit counts. Count each digit. Build the left half from the highest digit down: take `count/2` pairs of each digit, **skipping leading zeros** (do not start the half with `0`). The middle is the single largest digit with an odd leftover count (placed only if a half exists or it is the sole non-zero center). Mirror the half for the right side. The greedy choice — place the biggest available pair as far left as possible — maximizes the most significant positions first, which dominates magnitude. Careful zero handling avoids `"00…0"`: if no non-zero pair exists, the answer is a single best center digit (or `"0"`).

```
counts → take pairs high→low for the left half (no leading 0)
left = "97" + middle "9" + right "79"  →  "97979"
```

```java
class Solution {
    public String largestPalindrome(String num) {
        int[] cnt = new int[10];
        for (char c : num.toCharArray()) cnt[c - '0']++;
        StringBuilder half = new StringBuilder();
        for (int d = 9; d >= 0; d--) {
            if (d == 0 && half.length() == 0) break;   // no leading zeros in the half
            int pairs = cnt[d] / 2;
            for (int p = 0; p < pairs; p++) half.append((char) ('0' + d));
            cnt[d] -= pairs * 2;
        }
        // pick the largest remaining odd digit as the middle
        char middle = 0;
        for (int d = 9; d >= 0; d--) {
            if (cnt[d] > 0) { middle = (char) ('0' + d); break; }
        }
        if (half.length() == 0) {                      // no pairs usable
            return middle == 0 ? "0" : String.valueOf(middle);
        }
        StringBuilder res = new StringBuilder(half);
        if (middle != 0) res.append(middle);
        res.append(half.reverse());                    // mirror (half now reversed)
        return res.toString();
    }
}
```

**Dry run.** `num="00009"`. cnt[0]=4, cnt[9]=1. Half: d=9 pairs0; d=0 but half empty → break, half="". middle = '9'. half empty → return `"9"`. (Leading zeros correctly suppressed.)

**Complexity** — Time `O(n)`, Space `O(1)` (10-entry count + `O(n)` output). **Edge cases:** all zeros → `"0"`; single non-zero digit (that digit); only pairs of zeros and one larger center (`"00009"` → `"9"`); a clean even split with no center; the `half.reverse()` mutates `half`, so build `res` from a copy first (here we append the original then reverse for the mirror).

---

### Problem 51: Minimum Deletions to Make Character Frequencies Balanced via Heap — Hard Variant (hard)

**Statement.** Given a string `s` and an integer `k`, you may delete characters; the string is "k-balanced" when the difference between the maximum and minimum character frequency (over characters that still appear) is at most `k`. Return the minimum deletions to make `s` k-balanced. (Frequencies that drop to 0 mean the character is removed entirely and excluded.)
**Constraints.** `1 ≤ s.length ≤ 10⁵`, `0 ≤ k ≤ 10⁵`, lowercase letters.

**Approach.** Collect the non-zero frequencies. The optimal final configuration fixes a *lowest kept frequency* `f`; every kept character must have frequency in `[f, f+k]`, and any character with frequency `< f` is either fully deleted or… cannot be raised, so it must be removed entirely. For each candidate lower bound `f` (only the distinct existing frequencies need testing — a standard pruning), the cost is: for each character with frequency `c`, if `c < f` delete all `c` (remove the char), else if `c > f+k` delete `c−(f+k)` down to the cap, else 0. Take the minimum cost over all candidate `f`. Greedy/optimization insight: the window width is fixed at `k`, so only the window's lower edge is a free variable, and it suffices to try each present frequency as that edge (`O(26²)` here, generalizing to `O(D²)` for `D` distinct frequencies).

```
freqs (sorted): [1, 2, 5, 9],  k = 3
try lower edge f = 5 → window [5,8]:
   1<5 delete 1 | 2<5 delete 2 | 5 ok | 9>8 delete 1   → cost 4
try every present f, keep the minimum.
```

```java
class Solution {
    public int minDeletionsBalanced(String s, int k) {
        int[] freq = new int[26];
        for (char c : s.toCharArray()) freq[c - 'a']++;
        int best = Integer.MAX_VALUE;
        // candidate lower edges: each present frequency value
        for (int idx = 0; idx < 26; idx++) {
            int f = freq[idx];
            if (f == 0) continue;                 // must be a frequency we actually keep
            int cost = 0;
            for (int c : freq) {
                if (c == 0) continue;
                if (c < f) cost += c;             // can't reach the floor → delete char
                else if (c > f + k) cost += c - (f + k); // trim down to the cap
            }
            best = Math.min(best, cost);
        }
        return best == Integer.MAX_VALUE ? 0 : best;
    }
}
```

**Dry run.** `s="aaabbbcceeeeee"`, `k=2`. freqs a=3,b=3,c=2,e=6. Try f=3 → window[3,5]: c=2<3 delete2; e=6>5 delete1; a,b ok → cost3. Try f=2 → window[2,4]: e=6>4 delete2 → cost2. Try f=6 → window[6,8]: a3,b3,c2 all <6 delete 3+3+2=8 → cost8. Best `2`.

**Complexity** — Time `O(n + 26²) = O(n)` (generally `O(n + D²)` for `D` distinct frequencies), Space `O(1)`. **Edge cases:** already balanced (`0`); `k` huge enough to cover everything (`0`); a single distinct character (always balanced, `0`); choosing the lower edge so that low-frequency characters are dropped entirely versus trimmed — the per-`f` cost captures both; only *present* frequencies are valid lower edges, which prunes the search.

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
