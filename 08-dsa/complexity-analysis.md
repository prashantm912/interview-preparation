# Time & Space Complexity Analysis

A practical, interview-focused guide to reasoning about how algorithms scale: asymptotic notation, the Master Theorem, recurrences, amortized analysis, and the cost of common data-structure operations. Mastering this lets you defend *why* your solution is optimal — the single most-tested skill in a coding interview.

[← Back to master index](../README.md) · [← DSA index](README.md)

---

## Concept & Intuition

**Complexity analysis** measures how the *running time* (time complexity) and *extra memory* (space complexity) of an algorithm grow as the input size `n` grows toward infinity. We deliberately ignore constant factors and lower-order terms because, for large `n`, the dominant term decides everything. An `O(n²)` algorithm with a tiny constant still loses to an `O(n log n)` one once `n` is big enough.

We use **asymptotic notation** to describe these growth rates:

- **Big-O (`O`)** — an *upper bound*. `f(n) = O(g(n))` means `f` grows *no faster than* `g` (within a constant factor) for large `n`. Formally: there exist constants `c > 0` and `n₀` such that `0 ≤ f(n) ≤ c·g(n)` for all `n ≥ n₀`. This is the "worst case ceiling" we quote most often.
- **Big-Omega (`Ω`)** — a *lower bound*. `f(n) = Ω(g(n))` means `f` grows *at least as fast* as `g`: `0 ≤ c·g(n) ≤ f(n)` for `n ≥ n₀`. Useful for proving a problem *cannot* be solved faster than some bound (e.g. comparison sorting is `Ω(n log n)`).
- **Big-Theta (`Θ`)** — a *tight bound*. `f(n) = Θ(g(n))` iff `f = O(g)` **and** `f = Ω(g)`. The function is sandwiched: `c₁·g(n) ≤ f(n) ≤ c₂·g(n)`. This is the most precise statement and the one you should aim for when you fully understand an algorithm.

> **Key difference:** Big-O says "no worse than," Big-Omega says "no better than," and Big-Theta says "exactly this rate." People loosely say "O(n log n)" when they mean Θ, but in an interview, being precise about the distinction signals depth.

**Best / Average / Worst case** describe *which input* you analyze, and are orthogonal to O/Ω/Θ:
- **Worst case** — the slowest input of size `n` (e.g. quicksort on already-sorted data with a bad pivot → `O(n²)`). This is what we usually optimize for, because it guarantees an upper bound on every input.
- **Best case** — the fastest input (quicksort with perfect pivots → `Ω(n log n)`). Rarely useful alone.
- **Average case** — expected cost over a probability distribution of inputs (randomized quicksort → `Θ(n log n)` expected). Often what matters in practice.

A common confusion: you can talk about the worst case using Θ. "Worst-case time of insertion sort is Θ(n²)" is a fully valid, tight statement.

### A small mental model (ASCII)

```
 cost
  ^
  |                                          n!     2^n
  |                                       .   .
  |                                      .   .
  |                                  n^2.  .
  |                              .  .    .          n log n
  |                          .  .   .  .       . . . . . . .  n
  |                  .  .  . . . . . . . . . . . . . . . . . log n
  |         . . . . . . . . . . . . . . . . . . . . . . . . . 1 (constant)
  +-------------------------------------------------------------------> n
```

**Invariant to remember:** when combining steps, *sequential* code adds (`O(a) + O(b) = O(max(a,b))`) and *nested* code multiplies (`O(a) × O(b)`). When in doubt, count "how many times does the innermost statement execute as a function of n?"

**When to use this skill:** every single algorithmic interview. You must annotate each solution with time and space, justify it, and compare alternatives. Senior interviews push into amortized analysis and Master Theorem derivations.

---

## Complexity Cheat-Sheet

### Growth-rate ranking (slowest-growing → fastest-growing)

| Class | Name | Example operation |
|---|---|---|
| `O(1)` | Constant | Array index, hash lookup (avg) |
| `O(α(n))` | Inverse Ackermann | Union-Find with path compression |
| `O(log n)` | Logarithmic | Binary search, balanced-BST op |
| `O(√n)` | Root | Trial division primality, jump search |
| `O(n)` | Linear | Single pass over array |
| `O(n log n)` | Linearithmic | Merge sort, heap sort, fast comparison sort |
| `O(n²)` | Quadratic | Nested loops, bubble/insertion sort |
| `O(n³)` | Cubic | Naive matrix multiply, Floyd–Warshall |
| `O(2ⁿ)` | Exponential | Subsets, naive Fibonacci |
| `O(n!)` | Factorial | Permutations, brute-force TSP |

### Common data-structure operations (average / worst)

| Structure | Access | Search | Insert | Delete | Space |
|---|---|---|---|---|---|
| Array (static) | `O(1)` | `O(n)` | `O(n)` | `O(n)` | `O(n)` |
| Dynamic array (ArrayList) | `O(1)` | `O(n)` | `O(1)`* amortized | `O(n)` | `O(n)` |
| Singly linked list | `O(n)` | `O(n)` | `O(1)` head | `O(1)` head | `O(n)` |
| Stack / Queue | `O(n)` | `O(n)` | `O(1)` | `O(1)` | `O(n)` |
| Hash table | — | `O(1)` / `O(n)` | `O(1)` / `O(n)` | `O(1)` / `O(n)` | `O(n)` |
| Balanced BST (TreeMap) | `O(log n)` | `O(log n)` | `O(log n)` | `O(log n)` | `O(n)` |
| Binary heap | `O(1)` peek | `O(n)` | `O(log n)` | `O(log n)` | `O(n)` |
| Trie | — | `O(L)` | `O(L)` | `O(L)` | `O(Σ·N·L)` |
| Union-Find | — | `O(α(n))` | `O(α(n))` | — | `O(n)` |

\* See the amortized-analysis section for why dynamic-array append is `O(1)` amortized despite occasional `O(n)` resizes.

---

## Patterns & Recognition

Recognizing the target complexity *before* coding is a superpower. The constraints almost always leak the intended complexity:

| Constraint on `n` | Likely intended complexity |
|---|---|
| `n ≤ 10` | `O(n!)` or `O(2ⁿ)` — full search / permutations |
| `n ≤ 20–25` | `O(2ⁿ)` — bitmask DP, subset enumeration |
| `n ≤ 100` | `O(n³)` — DP over pairs, Floyd–Warshall |
| `n ≤ 1,000–5,000` | `O(n²)` — nested loops, 2-D DP |
| `n ≤ 10⁵–10⁶` | `O(n log n)` or `O(n)` — sort, two-pointer, sliding window |
| `n ≤ 10⁸+` | `O(n)` or `O(log n)` — linear pass or math |
| Huge / TB-scale | `O(log n)` or sublinear — binary search, hashing, streaming |

**Recognition heuristics:**

- **"Find pair/triplet"** with sorted-ability → think two-pointer/`O(n)` or `O(n²)` instead of brute `O(n³)`.
- **"Derive the recurrence"** for a divide-and-conquer routine → reach for the **Master Theorem**.
- **"Why is this amortized O(1)?"** → the interviewer wants aggregate / accounting / potential reasoning (dynamic array, hash resize, stack-with-min).
- **"Optimize memory"** → look for in-place algorithms (`O(1)` extra) or recursion that can be made iterative to kill the `O(depth)` call stack.
- **A loop whose index multiplies/divides** (`i *= 2`) → `O(log n)`, not `O(n)`.

---

## Coding Problems

### Problem 1: Determine the Big-O of a code snippet

**Statement.** Given the pseudo/Java loops below, return the tightest Big-O. Constraints: classic interview warm-up — you must justify, not just guess.

```java
// (a)
for (int i = 0; i < n; i++)                 // n iterations
    for (int j = 0; j < n; j++) sum++;      // n iterations each

// (b)
for (int i = 1; i < n; i *= 2) sum++;       // i doubles

// (c)
for (int i = 0; i < n; i++)
    for (int j = i; j < n; j++) sum++;      // inner shrinks
```

**Approach.** *Brute-force* mental model: simulate small `n`. *Optimal* mental model: count innermost executions as a closed-form sum.
- (a) `n × n = n²` → **O(n²)**.
- (b) `i` takes values `1, 2, 4, …, n`, i.e. `log₂ n` steps → **O(log n)**.
- (c) inner runs `n + (n-1) + … + 1 = n(n+1)/2` times → still **O(n²)** (the triangular sum is half of `n²`, but constants drop).

**Solution (a counting harness to verify intuition):**

```java
public class LoopCounter {
    static long countA(int n) {
        long ops = 0;
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) ops++;
        return ops;                       // == n*n
    }

    static long countB(int n) {
        long ops = 0;
        for (int i = 1; i < n; i *= 2) ops++;
        return ops;                       // == floor(log2 n)
    }

    static long countC(int n) {
        long ops = 0;
        for (int i = 0; i < n; i++)
            for (int j = i; j < n; j++) ops++;
        return ops;                       // == n*(n+1)/2
    }

    public static void main(String[] args) {
        for (int n : new int[]{4, 8, 16}) {
            System.out.printf("n=%d  A=%d  B=%d  C=%d%n",
                              n, countA(n), countB(n), countC(n));
        }
    }
}
```

**Dry-run** (`n = 8`): A = 64 (`8²`), B = 3 (`1→2→4`, stops at 8), C = 36 (`8·9/2`). Doubling `n` to 16 makes A jump to 256 (×4 = quadratic) and B only to 4 (+1 = logarithmic). The empirical multiplier confirms the class.

**Time:** the snippets are O(n²), O(log n), O(n²). **Space:** O(1) each (only `ops`).

**Follow-ups.** "What if loop (c)'s inner ran `j < i` instead of `j < n`?" (still Θ(n²)). "Two sequential loops, one O(n) one O(n²)?" → O(n²) (max dominates).

---

### Problem 2: Solve a recurrence with the Master Theorem

**Statement.** Give the asymptotic running time of: `T(n) = 2·T(n/2) + O(n)` (merge sort), `T(n) = 2·T(n/2) + O(1)` (binary-tree traversal), and `T(n) = T(n/2) + O(1)` (binary search). Constraints: must apply the theorem, not just recall the answer.

**Approach — the Master Theorem.** For `T(n) = a·T(n/b) + f(n)` with `a ≥ 1`, `b > 1`, compare `f(n)` to the *watershed* `n^(log_b a)`:

```
 Case 1: f(n) = O(n^(log_b a - ε))      → T(n) = Θ(n^(log_b a))      [leaves dominate]
 Case 2: f(n) = Θ(n^(log_b a) · log^k n) → T(n) = Θ(n^(log_b a) · log^(k+1) n)
 Case 3: f(n) = Ω(n^(log_b a + ε)) (+regularity) → T(n) = Θ(f(n))   [root dominates]
```

- **Merge sort:** `a=2, b=2 → n^(log₂2)=n`. `f(n)=n=Θ(n·log⁰n)` → Case 2 (k=0) → **Θ(n log n)**.
- **Tree traversal:** `a=2, b=2 → n`. `f(n)=O(1)` is below `n` → Case 1 → **Θ(n)**.
- **Binary search:** `a=1, b=2 → n^0 = 1`. `f(n)=O(1)=Θ(1·log⁰n)` → Case 2 → **Θ(log n)**.

**Solution (recurrence solver via recursion-tree summation, used to sanity-check):**

```java
public class MasterTheorem {
    /** Sum the recursion-tree cost for T(n)=a*T(n/b)+c*n^d down to n=1. */
    static double recurrenceCost(double n, int a, int b, int d, double c) {
        if (n < 1) return 0;
        double work = c * Math.pow(n, d);          // cost at this node level
        if (n <= 1) return work;
        return work + a * recurrenceCost(n / b, a, b, d, c);
    }

    static String classify(int a, int b, int d) {
        double crit = Math.log(a) / Math.log(b);   // log_b(a)
        if (d < crit) return "Case 1: Theta(n^" + crit + ")";
        if (d == crit) return "Case 2: Theta(n^" + d + " log n)";
        return "Case 3: Theta(n^" + d + ")";
    }

    public static void main(String[] args) {
        System.out.println("merge sort  -> " + classify(2, 2, 1)); // Case 2
        System.out.println("tree walk   -> " + classify(2, 2, 0)); // Case 1
        System.out.println("bin search  -> " + classify(1, 2, 0)); // Case 2
    }
}
```

**Dry-run.** For merge sort the recursion tree has `log₂ n` levels; each level does `Θ(n)` total work (the `2^i` nodes at level `i` each handle `n/2^i`), so total = `n · log n`. `classify(2,2,1)` reports Case 2, matching.

**Time:** the classification is O(1) arithmetic. **Space:** O(1) (the cost simulator is O(log n) stack but is only a check).

**Follow-ups.** "What about `T(n)=2T(n/2)+n/log n`?" (Master Theorem does **not** apply — the gap to the watershed is not polynomial; use Akra–Bazzi → Θ(n log log n)). "Strassen's `T(n)=7T(n/2)+O(n²)`?" → Case 1 → Θ(n^log₂7) ≈ Θ(n^2.81).

---

### Problem 3: Prove dynamic-array append is amortized O(1)

**Statement.** A dynamic array doubles its capacity when full. Show that `n` appends cost `O(n)` total, i.e. `O(1)` *amortized* per append, even though a single append can cost `O(n)`. Implement it.

**Approach.**
- *Brute-force claim:* "each append is O(n) because it might copy" — too pessimistic; copies are rare.
- *Aggregate method:* across `n` appends, resizes happen at sizes `1, 2, 4, …, ≤ n`. Total copy work = `1 + 2 + 4 + … + n < 2n = O(n)`. Divide by `n` appends → **O(1) amortized**.
- *Accounting method:* charge each append `3` credits: `1` for placing the element, `2` saved on the element. When the array doubles from size `k`, the `k` newest elements each carry `2` saved credits = `2k`, exactly funding the `k`-element copy of the *previous* full half. Credits never go negative → amortized cost ≤ 3 = O(1).
- *Potential method:* define potential `Φ = 2·size − capacity` (≥0 just after a resize). A non-resize append: actual 1, ΔΦ = +2 → amortized 3. A resizing append from full (`size=capacity=k`): actual `k+1`, but `Φ` drops from `k` to `2(k+1)-2k = 2`, ΔΦ = `2−k` → amortized `(k+1)+(2−k) = 3`. Constant either way.

**Solution:**

```java
public class DynamicArray {
    private int[] data = new int[1];
    private int size = 0;
    long totalCopyOps = 0;            // instrumentation for the proof

    public void add(int x) {
        if (size == data.length) {     // full -> double (the rare O(n) event)
            int[] bigger = new int[data.length * 2];
            for (int i = 0; i < size; i++) bigger[i] = data[i];
            totalCopyOps += size;      // count the copies
            data = bigger;
        }
        data[size++] = x;              // the common O(1) path
    }

    public int get(int i) { return data[i]; }   // O(1)
    public int size()    { return size; }

    public static void main(String[] args) {
        DynamicArray a = new DynamicArray();
        int n = 1_000_000;
        for (int i = 0; i < n; i++) a.add(i);
        System.out.printf("n=%d totalCopyOps=%d ratio=%.3f%n",
            n, a.totalCopyOps, (double) a.totalCopyOps / n);  // ratio < 1
    }
}
```

**Dry-run.** Appending 5 elements with capacity starting at 1: capacities grow `1→2→4→8`. Copies: at the 2nd add copy 1, at 3rd copy 2, at 5th copy 4 → total 7 copies for 5 adds. For `n = 10⁶`, `totalCopyOps ≈ 10⁶`, so the ratio is `< 1` copy/add — empirically constant.

**Time:** `add` = O(1) amortized, O(n) worst single call. **Space:** O(n).

**Follow-ups.** "Why grow by ×2 and not +1 each time?" (+1 → resize every add → `1+2+…+n = Θ(n²)` total = O(n) per add). "Why is ×1.5 sometimes preferred?" (better memory reuse / less peak overhead; still amortized O(1)). "Shrinking policy?" (shrink at ¼ full, not ½, to avoid thrashing).

---

### Problem 4: Compare two solutions and pick the better complexity (Two Sum)

**Statement.** Given an array `nums` and a `target`, return indices of two numbers summing to `target`. `1 ≤ n ≤ 10⁴`; exactly one solution. Compare brute force vs. optimal and argue the trade-off.

**Approach.**
- *Brute force:* check every pair → `O(n²)` time, `O(1)` space. Fine for `n = 10⁴`? `10⁸` ops — borderline TLE.
- *Sort + two-pointer:* `O(n log n)` time, `O(n)` to remember original indices. Loses original positions, so needs bookkeeping.
- *Optimal hash map:* one pass storing `value → index`; for each `x`, look up `target − x`. `O(n)` time, `O(n)` space. This is the standard answer: trade memory for time.

**Solution (optimal):**

```java
import java.util.*;

public class TwoSum {
    public int[] twoSum(int[] nums, int target) {
        Map<Integer, Integer> seen = new HashMap<>();   // value -> index
        for (int i = 0; i < nums.length; i++) {
            int need = target - nums[i];
            if (seen.containsKey(need)) {
                return new int[]{ seen.get(need), i };   // found complement earlier
            }
            seen.put(nums[i], i);
        }
        throw new IllegalArgumentException("no solution");
    }

    public static void main(String[] args) {
        System.out.println(Arrays.toString(
            new TwoSum().twoSum(new int[]{2, 7, 11, 15}, 9)));  // [0, 1]
    }
}
```

**Dry-run** (`nums=[2,7,11,15], target=9`): i=0 → need 7, map empty, store `{2:0}`. i=1 → need 2, found at index 0 → return `[0,1]`. One pass, no second loop.

**Time:** O(n) average (hash lookups O(1)). **Space:** O(n). The brute force is O(n²)/O(1) — for `n=10⁴` the hash version is ~10,000× fewer operations.

**Follow-ups.** "Worst-case hash collisions?" (degrades to O(n²); Java 8+ treeifies buckets → O(n log n)). "Sorted input?" (two-pointer gives O(n) time, O(1) space — beats hashing on memory). "Return all pairs / 3-Sum?" (3-Sum: sort + two-pointer → O(n²)).

---

### Problem 5: Worst-case vs amortized — Min Stack in O(1)

**Statement.** Design a stack supporting `push`, `pop`, `top`, and `getMin`, all in `O(1)` *worst-case* time. `≤ 3·10⁴` operations.

**Approach.**
- *Brute force:* scan the stack on each `getMin` → `O(n)`. Rejected.
- *Auxiliary min-stack:* keep a second stack whose top always holds the current minimum. Push the smaller of (`x`, current min); pop both together. Every operation is genuinely **O(1) worst-case** (not just amortized) at the cost of O(n) extra space.
- *Optimization:* store `(value, runLength)` pairs in the min-stack to compress repeated minima — same big-O, less constant memory.

**Solution:**

```java
import java.util.*;

public class MinStack {
    private final Deque<Integer> stack = new ArrayDeque<>();
    private final Deque<Integer> mins  = new ArrayDeque<>();  // running minima

    public void push(int x) {
        stack.push(x);
        mins.push(mins.isEmpty() ? x : Math.min(x, mins.peek()));
    }

    public void pop() {
        stack.pop();
        mins.pop();                 // keep the two stacks in lockstep
    }

    public int top()    { return stack.peek(); }
    public int getMin() { return mins.peek(); }   // O(1) worst-case

    public static void main(String[] args) {
        MinStack s = new MinStack();
        s.push(5); s.push(2); s.push(7);
        System.out.println(s.getMin()); // 2
        s.pop();
        System.out.println(s.getMin()); // 2
        s.pop();
        System.out.println(s.getMin()); // 5
    }
}
```

**Dry-run.** push 5 → mins `[5]`; push 2 → mins `[5,2]`; push 7 → mins `[5,2,2]`. getMin = 2. pop (7) → mins `[5,2]`, getMin = 2. pop (2) → mins `[5]`, getMin = 5. The min stack mirrors the value stack height-for-height.

**Time:** all operations O(1) worst-case. **Space:** O(n).

**Follow-ups.** "Reduce the extra space?" (store min only when it changes, with counts). "Min *queue*?" (harder — use two stacks or a monotonic deque, amortized O(1)). "Why is this *worst-case* O(1) but dynamic-array append only *amortized* O(1)?" (here every op does bounded work; there a single op can copy n elements).

---

### Problem 6 (Hard / Senior): Sliding Window Maximum — derive and defend O(n)

**Statement.** Given array `nums` and window size `k`, return the max of every contiguous window. `1 ≤ k ≤ n ≤ 10⁵`. Naively this is `O(n·k)` — derive an `O(n)` algorithm and prove the linear bound via amortized analysis.

**Approach.**
- *Brute force:* for each of `n−k+1` windows, scan `k` elements → `O(n·k)` = up to `10¹⁰` → TLE.
- *Heap:* push all, store `(value,index)`, lazily evict stale tops → `O(n log n)`, `O(n)` space.
- *Optimal monotonic deque:* keep indices in a deque whose values are *strictly decreasing*. The front is always the window max. Each index is pushed once and popped at most once → total deque ops = `2n` → **O(n)**. This is an *aggregate* amortized argument: although one step may pop many elements, the cumulative pops across all `n` steps cannot exceed `n`.

**Solution:**

```java
import java.util.*;

public class SlidingWindowMax {
    public int[] maxSlidingWindow(int[] nums, int k) {
        int n = nums.length;
        int[] result = new int[n - k + 1];
        Deque<Integer> dq = new ArrayDeque<>();   // holds INDICES, values decreasing

        for (int i = 0; i < n; i++) {
            // 1) drop indices that fell out of the window on the left
            if (!dq.isEmpty() && dq.peekFirst() <= i - k) dq.pollFirst();
            // 2) drop smaller values from the back (they can never be max again)
            while (!dq.isEmpty() && nums[dq.peekLast()] < nums[i]) dq.pollLast();
            dq.offerLast(i);
            // 3) once the first full window is formed, record the front (the max)
            if (i >= k - 1) result[i - k + 1] = nums[dq.peekFirst()];
        }
        return result;
    }

    public static void main(String[] args) {
        System.out.println(Arrays.toString(
            new SlidingWindowMax().maxSlidingWindow(
                new int[]{1, 3, -1, -3, 5, 3, 6, 7}, 3)));
        // [3, 3, 5, 5, 6, 7]
    }
}
```

**Dry-run** (`nums=[1,3,-1,-3,5,3,6,7], k=3`):
```
i=0 v=1  dq=[0]
i=1 v=3  pop 0 (1<3)         dq=[1]
i=2 v=-1                     dq=[1,2]  window done -> max nums[1]=3
i=3 v=-3                     dq=[1,2,3] front 1 in window -> max 3
i=4 v=5  pop 3,2 (<5), front 1 expired -> max nums[4]=5  dq=[4]
i=5 v=3                      dq=[4,5]  -> max 5
i=6 v=6  pop 5,4 (<6)        dq=[6]    -> max 6
i=7 v=7  pop 6 (<7)          dq=[7]    -> max 7
```
Result `[3,3,5,5,6,7]`.

**Time:** O(n) — amortized O(1) per element (each index enters and leaves the deque once). **Space:** O(k) for the deque, plus O(n−k+1) output.

**Follow-ups.** "Prove the O(n) bound rigorously" (aggregate: ≤ n pushes and ≤ n pops total → ≤ 2n ops). "Sliding window *minimum*?" (flip the comparison). "Streaming / infinite input?" (the deque approach works online; the heap approach leaks memory without lazy deletion). "k can change per query?" (sparse table for static array → O(n log n) build, O(1) query).

---

## 🧩 Extended Problems — Set 1: Classic Easy → Medium

### Problem 7: Contains Duplicate — Hash Set vs Sort

**Statement.** Given an integer array `nums`, return `true` if any value appears at least twice, `false` if every element is distinct. Compare the time/space trade-offs of the candidate approaches.

**Constraints.** `1 ≤ n ≤ 10⁵`, `−10⁹ ≤ nums[i] ≤ 10⁹`.

**Approach.** The brute-force pairwise comparison is `O(n²)` time / `O(1)` space — far too slow at `n = 10⁵` (`10¹⁰` ops). Sorting first makes duplicates adjacent so a single linear scan detects them: `O(n log n)` time, `O(1)` extra space (if you may mutate the input). The standard optimal answer trades memory for time: stream elements into a `HashSet`; the first element whose insertion fails is a duplicate. Each insert/lookup is `O(1)` average, so the whole pass is `O(n)` time / `O(n)` space. We pick the hash set because the constraint `n ≤ 10⁵` does not demand in-place work, and linear time is the best achievable (you must look at every element at least once → `Ω(n)` lower bound).

```java
import java.util.*;

public class ContainsDuplicate {
    public boolean containsDuplicate(int[] nums) {
        Set<Integer> seen = new HashSet<>();
        for (int x : nums) {
            if (!seen.add(x)) return true;   // add() returns false if already present
        }
        return false;
    }

    public static void main(String[] args) {
        System.out.println(new ContainsDuplicate().containsDuplicate(new int[]{1, 2, 3, 1})); // true
        System.out.println(new ContainsDuplicate().containsDuplicate(new int[]{1, 2, 3, 4})); // false
    }
}
```

**Complexity.** Time `O(n)` average (`O(n²)` adversarial-collision worst case; Java 8+ treeifies buckets to `O(n log n)`). Space `O(n)`. **Edge cases:** single element → `false`; all identical → returns on the second element; empty array (if allowed) → `false`.

---

### Problem 8: Valid Anagram — Counting in O(n)

**Statement.** Given two strings `s` and `t`, return `true` iff `t` is an anagram of `s` (same multiset of characters).

**Constraints.** `1 ≤ |s|, |t| ≤ 5·10⁴`; lowercase English letters in the base version.

**Approach.** Sorting both strings and comparing is `O(n log n)`. The optimal solution exploits the tiny, fixed alphabet (26 letters): tally character frequencies in a fixed-size `int[26]`, incrementing for `s` and decrementing for `t`. If all counters return to zero, the strings are anagrams. A length mismatch is an instant `false`. Because the count array has constant size, the frequency table is `O(1)` space and the pass is `O(n)` — strictly better than the sort.

```
 s = "anagram"   t = "nagaram"
 count after +s/-t for each letter:  a:0 n:0 g:0 r:0 m:0  -> all zero -> anagram
```

```java
public class ValidAnagram {
    public boolean isAnagram(String s, String t) {
        if (s.length() != t.length()) return false;
        int[] count = new int[26];
        for (int i = 0; i < s.length(); i++) {
            count[s.charAt(i) - 'a']++;
            count[t.charAt(i) - 'a']--;
        }
        for (int c : count) if (c != 0) return false;
        return true;
    }

    public static void main(String[] args) {
        System.out.println(new ValidAnagram().isAnagram("anagram", "nagaram")); // true
        System.out.println(new ValidAnagram().isAnagram("rat", "car"));         // false
    }
}
```

**Complexity.** Time `O(n)`; space `O(1)` for fixed alphabet (use a `HashMap<Character,Integer>` → `O(k)` space for arbitrary Unicode). **Edge cases:** unequal lengths short-circuit; empty strings are anagrams of each other; Unicode/emoji needs the map variant.

---

### Problem 9: Maximum Subarray (Kadane) — O(n) one pass

**Statement.** Find the contiguous subarray with the largest sum and return that sum.

**Constraints.** `1 ≤ n ≤ 10⁵`, `−10⁴ ≤ nums[i] ≤ 10⁴`.

**Approach.** Brute force tries all `O(n²)` subarrays (or `O(n³)` if you re-sum each). Kadane's algorithm reduces this to a single linear scan via dynamic programming: let `best` be the maximum subarray sum ending at the current index. Either extend the previous best (`best + x`) or restart at `x` — whichever is larger. Track the global maximum separately. The key insight is that a negative running prefix can never help a later subarray, so we drop it. This is optimal: you must read all `n` elements (`Ω(n)`), and Kadane hits that bound with `O(1)` memory.

```java
public class MaximumSubarray {
    public int maxSubArray(int[] nums) {
        int best = nums[0];      // max subarray sum ending here
        int answer = nums[0];    // global max
        for (int i = 1; i < nums.length; i++) {
            best = Math.max(nums[i], best + nums[i]);
            answer = Math.max(answer, best);
        }
        return answer;
    }

    public static void main(String[] args) {
        System.out.println(new MaximumSubarray().maxSubArray(
            new int[]{-2, 1, -3, 4, -1, 2, 1, -5, 4})); // 6  -> [4,-1,2,1]
    }
}
```

**Complexity.** Time `O(n)`; space `O(1)`. **Edge cases:** all-negative array returns the largest (least negative) single element — handled because we seed both vars with `nums[0]` rather than `0`; single element returns itself.

---

### Problem 10: Best Time to Buy and Sell Stock — single pass

**Statement.** Given daily prices, choose one buy day and a later sell day to maximize profit; return the max profit, or `0` if no profitable trade exists.

**Constraints.** `1 ≤ n ≤ 10⁵`, `0 ≤ prices[i] ≤ 10⁴`.

**Approach.** Checking all `(buy, sell)` pairs is `O(n²)`. The optimal trick: sweep left to right tracking the minimum price seen so far. At each day, the best profit ending today is `price − minSoFar`; keep the running maximum. This works because the best sell day only needs the cheapest price among all earlier days, which we maintain in `O(1)`. One pass, no extra storage.

```
 prices: 7 1 5 3 6 4
 minSoFar: 7 1 1 1 1 1
 profit:   0 0 4 2 5 3   -> max = 5  (buy@1, sell@6)
```

```java
public class BestTimeToBuySellStock {
    public int maxProfit(int[] prices) {
        int minPrice = Integer.MAX_VALUE;
        int maxProfit = 0;
        for (int p : prices) {
            if (p < minPrice) minPrice = p;
            else if (p - minPrice > maxProfit) maxProfit = p - minPrice;
        }
        return maxProfit;
    }

    public static void main(String[] args) {
        System.out.println(new BestTimeToBuySellStock().maxProfit(new int[]{7, 1, 5, 3, 6, 4})); // 5
        System.out.println(new BestTimeToBuySellStock().maxProfit(new int[]{7, 6, 4, 3, 1}));     // 0
    }
}
```

**Complexity.** Time `O(n)`; space `O(1)`. **Edge cases:** monotonically decreasing prices → profit `0`; single day → `0`; equal prices → `0`.

---

### Problem 11: Two-Pointer on Sorted Array — Two Sum II in O(n)/O(1)

**Statement.** Given a 1-indexed array `numbers` sorted in non-decreasing order, return the two indices whose values sum to `target` (exactly one solution exists).

**Constraints.** `2 ≤ n ≤ 3·10⁴`, sorted input, answer guaranteed unique.

**Approach.** Two Sum via hash map (Problem 4) is `O(n)` time but `O(n)` space. Because the input is *sorted*, we can do better on memory with two pointers: `lo` at the start, `hi` at the end. If `numbers[lo] + numbers[hi]` is too small, only increasing the left value can help → `lo++`; if too large → `hi--`; if equal, return. Each step moves a pointer inward, so the pointers meet after at most `n` steps. This achieves `O(n)` time with `O(1)` space — the sorted structure lets us beat the hash approach's memory.

```
 numbers = [2, 7, 11, 15], target = 9
 lo=0 hi=3 -> 2+15=17 > 9 -> hi=2
 lo=0 hi=2 -> 2+11=13 > 9 -> hi=1
 lo=0 hi=1 -> 2+7 =9  == 9 -> return [1, 2]  (1-indexed)
```

```java
public class TwoSumSorted {
    public int[] twoSum(int[] numbers, int target) {
        int lo = 0, hi = numbers.length - 1;
        while (lo < hi) {
            int sum = numbers[lo] + numbers[hi];
            if (sum == target) return new int[]{lo + 1, hi + 1}; // 1-indexed
            if (sum < target) lo++;
            else hi--;
        }
        throw new IllegalArgumentException("no solution");
    }

    public static void main(String[] args) {
        java.util.Arrays.stream(new TwoSumSorted().twoSum(new int[]{2, 7, 11, 15}, 9))
            .forEach(System.out::println); // 1, 2
    }
}
```

**Complexity.** Time `O(n)`; space `O(1)`. **Edge cases:** exactly two elements; negative numbers (still works — only ordering matters); duplicates that form the pair.

---

### Problem 12: Binary Search — the canonical O(log n)

**Statement.** Given a sorted array and a `target`, return its index, or `−1` if absent.

**Constraints.** `1 ≤ n ≤ 10⁴`, strictly sorted, distinct values.

**Approach.** A linear scan is `O(n)`. Binary search exploits the sorted order: compare the target to the midpoint and discard half the search space each step, so after `k` comparisons the candidate range is `n / 2ᵏ`; it shrinks to one element in `log₂ n` steps → `O(log n)`. The classic correctness subtlety is computing the midpoint as `lo + (hi − lo) / 2` to avoid `int` overflow when `lo + hi` exceeds `Integer.MAX_VALUE`. This is provably optimal for comparison-based search on a sorted array (`Ω(log n)` by the decision-tree argument).

```java
public class BinarySearch {
    public int search(int[] nums, int target) {
        int lo = 0, hi = nums.length - 1;
        while (lo <= hi) {
            int mid = lo + (hi - lo) / 2;   // overflow-safe midpoint
            if (nums[mid] == target) return mid;
            if (nums[mid] < target) lo = mid + 1;
            else hi = mid - 1;
        }
        return -1;
    }

    public static void main(String[] args) {
        System.out.println(new BinarySearch().search(new int[]{-1, 0, 3, 5, 9, 12}, 9)); // 4
        System.out.println(new BinarySearch().search(new int[]{-1, 0, 3, 5, 9, 12}, 2)); // -1
    }
}
```

**Complexity.** Time `O(log n)`; space `O(1)` (iterative; the recursive form is `O(log n)` stack). **Edge cases:** target smaller/larger than all elements → `−1`; single element; the `lo <= hi` (inclusive) bound is required so the last candidate is examined.

---

### Problem 13: Reverse a Linked List — O(n)/O(1) iterative

**Statement.** Reverse a singly linked list and return the new head.

**Constraints.** `0 ≤ n ≤ 5·10³`.

**Approach.** The recursive reversal is elegant but uses `O(n)` stack space — and on a 5,000-node list a deep recursion risks stack overflow in the JVM (no tail-call optimization). The iterative three-pointer technique rewires each `next` pointer in place: keep `prev`, `curr`, and a saved `next`. Walk forward once, pointing each node back at its predecessor. This is `O(n)` time and `O(1)` space, and is the answer interviewers expect precisely because it dodges the recursion-stack cost — a direct application of the "convert recursion to iteration to kill O(depth) space" principle.

```
 1 -> 2 -> 3 -> null
 step: prev=null curr=1 ; redirect 1->null, advance
       prev=1    curr=2 ; redirect 2->1
       prev=2    curr=3 ; redirect 3->2
 result: 3 -> 2 -> 1 -> null
```

```java
public class ReverseLinkedList {
    static class ListNode {
        int val; ListNode next;
        ListNode(int v) { val = v; }
    }

    public ListNode reverseList(ListNode head) {
        ListNode prev = null, curr = head;
        while (curr != null) {
            ListNode next = curr.next;  // save the rest
            curr.next = prev;           // reverse the link
            prev = curr;                // advance prev
            curr = next;                // advance curr
        }
        return prev;                    // new head
    }

    public static void main(String[] args) {
        ListNode a = new ListNode(1); a.next = new ListNode(2); a.next.next = new ListNode(3);
        ListNode r = new ReverseLinkedList().reverseList(a);
        for (ListNode p = r; p != null; p = p.next) System.out.print(p.val + " "); // 3 2 1
    }
}
```

**Complexity.** Time `O(n)`; space `O(1)`. **Edge cases:** empty list (`head == null`) returns `null`; single node returns itself unchanged.

---

### Problem 14: Linked List Cycle — Floyd's O(n)/O(1)

**Statement.** Determine whether a singly linked list contains a cycle.

**Constraints.** `0 ≤ n ≤ 10⁴`.

**Approach.** A `HashSet` of visited nodes detects a revisit in `O(n)` time but `O(n)` space. Floyd's tortoise-and-hare reaches the same `O(n)` time at `O(1)` space: advance a slow pointer one step and a fast pointer two steps per iteration. If the list ends (`fast == null`), there is no cycle. If a cycle exists, the fast pointer eventually laps the slow one and they coincide — because once both are inside the loop the gap between them shrinks by one each step, guaranteeing a meeting within one loop length. The memory win is why this is the canonical answer.

```java
public class LinkedListCycle {
    static class ListNode { int val; ListNode next; ListNode(int v){ val = v; } }

    public boolean hasCycle(ListNode head) {
        ListNode slow = head, fast = head;
        while (fast != null && fast.next != null) {
            slow = slow.next;        // +1
            fast = fast.next.next;   // +2
            if (slow == fast) return true;
        }
        return false;                // fast hit the end
    }

    public static void main(String[] args) {
        ListNode a = new ListNode(1), b = new ListNode(2), c = new ListNode(3);
        a.next = b; b.next = c; c.next = b;  // cycle: 3 -> 2
        System.out.println(new LinkedListCycle().hasCycle(a)); // true
    }
}
```

**Complexity.** Time `O(n)`; space `O(1)`. **Edge cases:** empty list → `false`; single node with no self-loop → `false`; self-loop (`node.next == node`) → `true`.

---

### Problem 15: Valid Parentheses — stack in O(n)

**Statement.** Given a string of `()[]{}`, determine whether the brackets are correctly opened and closed in the right order.

**Constraints.** `1 ≤ n ≤ 10⁴`.

**Approach.** Bracket matching is inherently a last-in-first-out problem, so a stack is the natural and optimal tool. Push every opening bracket; on a closing bracket, the top of the stack must be its exact partner — if it is not (or the stack is empty), reject immediately. At the end the stack must be empty (every opener closed). One pass with stack pushes/pops gives `O(n)` time; the stack can hold up to `n/2` openers → `O(n)` space. You cannot do better than linear because each character must be inspected.

```java
import java.util.*;

public class ValidParentheses {
    public boolean isValid(String s) {
        Deque<Character> stack = new ArrayDeque<>();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '(' : stack.push(')'); break;   // push the EXPECTED closer
                case '[' : stack.push(']'); break;
                case '{' : stack.push('}'); break;
                default  :                            // a closing bracket
                    if (stack.isEmpty() || stack.pop() != c) return false;
            }
        }
        return stack.isEmpty();
    }

    public static void main(String[] args) {
        System.out.println(new ValidParentheses().isValid("()[]{}")); // true
        System.out.println(new ValidParentheses().isValid("(]"));     // false
        System.out.println(new ValidParentheses().isValid("([)]"));   // false
    }
}
```

**Complexity.** Time `O(n)`; space `O(n)`. **Edge cases:** odd length always fails; a lone closing bracket fails on the empty-stack check; trailing unclosed openers fail the final emptiness test.

---

### Problem 16: Merge Two Sorted Lists — O(n+m)/O(1)

**Statement.** Merge two sorted linked lists into one sorted list and return its head.

**Constraints.** `0 ≤ n, m ≤ 50`, values already sorted ascending.

**Approach.** This is the merge step of merge sort. Use a dummy head node so we never special-case the first element, and a `tail` pointer to append to. At each step splice the smaller of the two current nodes, advancing that list. When one list runs out, attach the remainder of the other in `O(1)`. Each node is visited exactly once, giving `O(n + m)` time. Because we relink existing nodes rather than allocating new ones, the extra space is `O(1)` (the dummy node aside).

```
 l1: 1 -> 2 -> 4
 l2: 1 -> 3 -> 4
 out: 1 -> 1 -> 2 -> 3 -> 4 -> 4
```

```java
public class MergeTwoSortedLists {
    static class ListNode { int val; ListNode next; ListNode(int v){ val = v; } }

    public ListNode mergeTwoLists(ListNode l1, ListNode l2) {
        ListNode dummy = new ListNode(0), tail = dummy;
        while (l1 != null && l2 != null) {
            if (l1.val <= l2.val) { tail.next = l1; l1 = l1.next; }
            else                  { tail.next = l2; l2 = l2.next; }
            tail = tail.next;
        }
        tail.next = (l1 != null) ? l1 : l2;  // attach the leftover tail
        return dummy.next;
    }

    public static void main(String[] args) {
        ListNode a = new ListNode(1); a.next = new ListNode(2); a.next.next = new ListNode(4);
        ListNode b = new ListNode(1); b.next = new ListNode(3); b.next.next = new ListNode(4);
        ListNode m = new MergeTwoSortedLists().mergeTwoLists(a, b);
        for (ListNode p = m; p != null; p = p.next) System.out.print(p.val + " "); // 1 1 2 3 4 4
    }
}
```

**Complexity.** Time `O(n + m)`; space `O(1)`. **Edge cases:** either list empty → return the other; equal heads (use `<=` for stability); both empty → `null`.

---

### Problem 17: Move Zeroes — in-place O(n)/O(1)

**Statement.** Move all `0`s to the end of an array while keeping the relative order of the non-zero elements, modifying the array in place.

**Constraints.** `1 ≤ n ≤ 10⁴`. Minimize the number of writes.

**Approach.** A naive solution builds a new array (`O(n)` extra space) or repeatedly shifts on each zero (`O(n²)`). The two-pointer in-place technique keeps a `write` index marking the next slot for a non-zero value. Scan once: whenever you read a non-zero, place it at `write` and advance `write`. After the scan, every slot from `write` to the end is filled with `0`. This is `O(n)` time, `O(1)` space, and writes each element at most once on the first phase — optimal because relative order must be preserved and every element must be examined.

```
 nums = [0, 1, 0, 3, 12]
 after compaction: [1, 3, 12, _, _]  write=3
 zero-fill tail:    [1, 3, 12, 0, 0]
```

```java
public class MoveZeroes {
    public void moveZeroes(int[] nums) {
        int write = 0;
        for (int x : nums) {
            if (x != 0) nums[write++] = x;   // compact non-zeros forward
        }
        while (write < nums.length) nums[write++] = 0;  // fill the tail
    }

    public static void main(String[] args) {
        int[] nums = {0, 1, 0, 3, 12};
        new MoveZeroes().moveZeroes(nums);
        System.out.println(java.util.Arrays.toString(nums)); // [1, 3, 12, 0, 0]
    }
}
```

**Complexity.** Time `O(n)`; space `O(1)`. **Edge cases:** no zeros → array unchanged; all zeros → unchanged; single element handled by the same loop.

---

### Problem 18: Majority Element — Boyer–Moore Voting O(n)/O(1)

**Statement.** Given an array where one element appears more than `⌊n/2⌋` times, return that majority element.

**Constraints.** `1 ≤ n ≤ 5·10⁴`; a majority element is guaranteed to exist.

**Approach.** Sorting and taking the middle element is `O(n log n)`; a `HashMap` of counts is `O(n)` time but `O(n)` space. The Boyer–Moore voting algorithm achieves `O(n)` time and `O(1)` space. Keep a `candidate` and a `count`. When `count` hits zero, adopt the current element as the new candidate. Increment `count` when the current element matches the candidate, decrement otherwise. Because the majority element occurs more than half the time, every cancellation pairs it with a non-majority element, and the surplus guarantees it survives as the final candidate. This is the textbook constant-space win.

```
 nums = [2,2,1,1,1,2,2]
 x=2 cand=2 cnt=1 | x=2 cnt=2 | x=1 cnt=1 | x=1 cnt=0
 x=1 cand=1 cnt=1 | x=2 cnt=0 | x=2 cand=2 cnt=1  -> 2
```

```java
public class MajorityElement {
    public int majorityElement(int[] nums) {
        int candidate = 0, count = 0;
        for (int x : nums) {
            if (count == 0) candidate = x;          // adopt new candidate
            count += (x == candidate) ? 1 : -1;     // vote
        }
        return candidate;
    }

    public static void main(String[] args) {
        System.out.println(new MajorityElement().majorityElement(new int[]{2, 2, 1, 1, 1, 2, 2})); // 2
        System.out.println(new MajorityElement().majorityElement(new int[]{3, 3, 4}));             // 3
    }
}
```

**Complexity.** Time `O(n)`; space `O(1)`. **Edge cases:** single element returns itself; majority at the very front or back still survives. If the majority guarantee is dropped, add a second verification pass (`O(n)`) to confirm the candidate.

---

### Problem 19: Fibonacci — exponential recursion vs O(n) DP

**Statement.** Compute the `n`-th Fibonacci number and contrast the complexity of naive recursion, memoized recursion, and bottom-up iteration.

**Constraints.** `0 ≤ n ≤ 90` (fits in a signed 64-bit `long`).

**Approach.** Naive recursion `fib(n) = fib(n−1) + fib(n−2)` rebuilds overlapping subproblems, spawning roughly `φⁿ` calls → `O(2ⁿ)` time, a textbook exponential blow-up (and `O(n)` recursion-stack space). Top-down memoization caches each `fib(k)` once, collapsing the call tree to `O(n)` time / `O(n)` space. The bottom-up iterative form is strictly best: roll two variables forward, giving `O(n)` time and `O(1)` space. This problem is the canonical illustration of how eliminating redundant recomputation moves you from exponential to linear.

```
 naive call tree for fib(5) — note repeated fib(2):
            fib(5)
          /        \
      fib(4)       fib(3)
      /    \       /    \
   fib(3) fib(2) fib(2) fib(1)   ... exponential duplication
```

```java
public class Fibonacci {
    // O(n) time, O(1) space — the optimal iterative form
    public long fib(int n) {
        if (n < 2) return n;
        long a = 0, b = 1;
        for (int i = 2; i <= n; i++) {
            long next = a + b;
            a = b;
            b = next;
        }
        return b;
    }

    public static void main(String[] args) {
        System.out.println(new Fibonacci().fib(10)); // 55
        System.out.println(new Fibonacci().fib(50)); // 12586269025
    }
}
```

**Complexity.** Iterative: time `O(n)`, space `O(1)`. (Naive recursion `O(2ⁿ)`/`O(n)`; memoized `O(n)`/`O(n)`.) **Edge cases:** `n = 0 → 0`, `n = 1 → 1`; beyond `n = 92` the result overflows `long` (use `BigInteger`).

---

## 🧩 Extended Problems — Set 2: Medium → Hard Variations & Follow-ups

### Problem 20: Search in Rotated Sorted Array — modified binary search O(log n)

**Statement.** A strictly ascending array was rotated at an unknown pivot (e.g. `[0,1,2,4,5,6,7]` → `[4,5,6,7,0,1,2]`). Given `target`, return its index or `−1`. This is the classic Medium follow-up to plain binary search (Problem 12).

**Constraints.** `1 ≤ n ≤ 5·10³`, all values distinct, the array is a rotation of a sorted array.

**Approach.** The brute-force linear scan is `O(n)` and throws away the sorted structure. To keep `O(log n)`, observe that when you split the rotated array at `mid`, **at least one half is still fully sorted**. Compare `nums[lo]` to `nums[mid]`: if `nums[lo] ≤ nums[mid]` the left half `[lo, mid]` is sorted; otherwise the right half `[mid, hi]` is sorted. Decide which sorted half could contain `target` by a simple range check, and discard the other half — exactly the binary-search halving step, so we retain `O(log n)`. The progression brute `O(n)` → modified binary search `O(log n)` is the whole point of the question.

```
 [4,5,6,7,0,1,2]  target=0
 lo=0 hi=6 mid=3(7): left [4..7] sorted; 0 not in [4,7] -> go right  lo=4
 lo=4 hi=6 mid=5(1): left [0..1] sorted; 0 in [0,1)     -> go left   hi=4
 lo=4 hi=4 mid=4(0): hit -> index 4
```

```java
public class SearchRotatedArray {
    public int search(int[] nums, int target) {
        int lo = 0, hi = nums.length - 1;
        while (lo <= hi) {
            int mid = lo + (hi - lo) / 2;
            if (nums[mid] == target) return mid;
            if (nums[lo] <= nums[mid]) {                 // left half sorted
                if (nums[lo] <= target && target < nums[mid]) hi = mid - 1;
                else lo = mid + 1;
            } else {                                     // right half sorted
                if (nums[mid] < target && target <= nums[hi]) lo = mid + 1;
                else hi = mid - 1;
            }
        }
        return -1;
    }

    public static void main(String[] args) {
        System.out.println(new SearchRotatedArray().search(new int[]{4,5,6,7,0,1,2}, 0)); // 4
        System.out.println(new SearchRotatedArray().search(new int[]{4,5,6,7,0,1,2}, 3)); // -1
    }
}
```

**Complexity.** Time `O(log n)`; space `O(1)`. **Edge cases:** no rotation (already sorted) still works; single element; target absent → `−1`. **Follow-up:** with *duplicates* allowed, the `nums[lo] == nums[mid]` ambiguity forces a `lo++` fallback, degrading the worst case to `O(n)`.

---

### Problem 21: Median of Two Sorted Arrays — O(log(min(m,n)))

**Statement.** Given two sorted arrays `a` and `b`, return the median of their combined multiset without merging them. This is the hardest classic divide-the-search-space problem.

**Constraints.** `0 ≤ m, n`, `1 ≤ m + n`, both inputs sorted ascending.

**Approach.** Brute force merges both arrays (`O(m+n)` time/space) and reads the middle — correct but ignores the sorted structure and the logarithmic target the constraints hint at. The optimal trick is a **binary search on the partition point**, not on values. Binary-search the smaller array for a cut `i`; the cut in the other array is forced to `j = (m+n+1)/2 − i` so the left side holds exactly half the elements. A partition is valid when `aLeft ≤ bRight` and `bLeft ≤ aRight`. Adjust `i` left/right based on which inequality fails. Searching only the smaller array gives `O(log(min(m,n)))`. The `±∞` sentinels remove all empty-side edge cases.

```
 left half (k elements)        | right half
 a: ... a[i-1] | a[i] ...       cut i in array a
 b: ... b[j-1] | b[j] ...       cut j = k - i
 valid iff a[i-1] <= b[j] AND b[j-1] <= a[i]
```

```java
public class MedianTwoSortedArrays {
    public double findMedianSortedArrays(int[] a, int[] b) {
        if (a.length > b.length) return findMedianSortedArrays(b, a); // search smaller
        int m = a.length, n = b.length, half = (m + n + 1) / 2;
        int lo = 0, hi = m;
        while (lo <= hi) {
            int i = lo + (hi - lo) / 2;     // cut in a
            int j = half - i;               // cut in b
            int aL = (i == 0) ? Integer.MIN_VALUE : a[i - 1];
            int aR = (i == m) ? Integer.MAX_VALUE : a[i];
            int bL = (j == 0) ? Integer.MIN_VALUE : b[j - 1];
            int bR = (j == n) ? Integer.MAX_VALUE : b[j];
            if (aL <= bR && bL <= aR) {     // correct partition
                int leftMax = Math.max(aL, bL);
                if (((m + n) & 1) == 1) return leftMax;                 // odd total
                int rightMin = Math.min(aR, bR);
                return (leftMax + rightMin) / 2.0;                      // even total
            } else if (aL > bR) hi = i - 1; // a's cut too far right
            else lo = i + 1;                // a's cut too far left
        }
        throw new IllegalArgumentException("inputs not sorted");
    }

    public static void main(String[] args) {
        System.out.println(new MedianTwoSortedArrays()
            .findMedianSortedArrays(new int[]{1,3}, new int[]{2}));     // 2.0
        System.out.println(new MedianTwoSortedArrays()
            .findMedianSortedArrays(new int[]{1,2}, new int[]{3,4}));   // 2.5
    }
}
```

**Complexity.** Time `O(log(min(m,n)))`; space `O(1)`. **Edge cases:** one array empty (handled by sentinels and the swap); even vs odd total length; all of one array smaller than the other.

---

### Problem 22: Kth Largest Element — Quickselect average O(n) vs heap O(n log k)

**Statement.** Return the `k`-th largest element in an unsorted array (the `k`-th in sorted-descending order, not the `k`-th distinct).

**Constraints.** `1 ≤ k ≤ n ≤ 10⁵`.

**Approach.** Three rungs of a clear progression. (1) **Full sort** then index: `O(n log n)`. (2) **Min-heap of size k**: keep the `k` largest seen; the heap root is the answer. `O(n log k)` time, `O(k)` space — best when `k ≪ n` or data streams in. (3) **Quickselect** (Hoare's selection): partition around a random pivot like quicksort but recurse into *only* the side containing the target rank. The expected work is `n + n/2 + n/4 + … = O(n)`; the worst case is `O(n²)` (adversarial/poor pivots), tamed by random pivot choice. Quickselect is the textbook "average linear" selection answer; the heap is the safer choice under streaming or worst-case-sensitivity.

```java
import java.util.*;

public class KthLargest {
    private final Random rng = new Random();

    public int findKthLargest(int[] nums, int k) {
        int target = nums.length - k;          // k-th largest = index (n-k) when ascending
        int lo = 0, hi = nums.length - 1;
        while (lo < hi) {
            int p = partition(nums, lo, hi);
            if (p == target) break;
            else if (p < target) lo = p + 1;
            else hi = p - 1;
        }
        return nums[target];
    }

    private int partition(int[] a, int lo, int hi) {
        int pivotIdx = lo + rng.nextInt(hi - lo + 1);
        swap(a, pivotIdx, hi);                 // move pivot to end
        int pivot = a[hi], store = lo;
        for (int i = lo; i < hi; i++)
            if (a[i] < pivot) swap(a, store++, i);
        swap(a, store, hi);                    // place pivot at its sorted position
        return store;
    }

    private void swap(int[] a, int i, int j) { int t = a[i]; a[i] = a[j]; a[j] = t; }

    public static void main(String[] args) {
        System.out.println(new KthLargest().findKthLargest(new int[]{3,2,1,5,6,4}, 2));       // 5
        System.out.println(new KthLargest().findKthLargest(new int[]{3,2,3,1,2,4,5,5,6}, 4)); // 4
    }
}
```

**Complexity.** Quickselect: expected `O(n)`, worst `O(n²)`, space `O(1)`. (Heap variant: `O(n log k)` / `O(k)`.) **Edge cases:** `k = 1` (max) or `k = n` (min); duplicates handled by strict `< pivot` partitioning; single element. **Follow-up:** worst-case-linear selection uses median-of-medians (`O(n)` guaranteed, large constant).

---

### Problem 23: Longest Substring Without Repeating Characters — sliding window O(n)

**Statement.** Return the length of the longest substring of `s` containing no repeated character. A core variable-size sliding-window problem.

**Constraints.** `0 ≤ |s| ≤ 5·10⁴`, any ASCII/Unicode characters.

**Approach.** Brute force enumerates every substring and checks uniqueness — `O(n²)` substrings × `O(n)` check = `O(n³)`, or `O(n²)` with an incremental set. The optimal sliding window keeps a `[left, right]` window that always holds distinct characters. Store each character's last index in a map. When `right` meets a character already inside the window, jump `left` to just past that character's previous position (never moving `left` backward). Each index is visited by `right` once and `left` advances monotonically, so it is amortized `O(n)` despite the nested-looking logic.

```
 s = "abcabcbb"
 right scans; left jumps past the duplicate's last index
 window "abc" (len 3) is the longest before the first repeat at index 3
```

```java
import java.util.*;

public class LongestUniqueSubstring {
    public int lengthOfLongestSubstring(String s) {
        Map<Character, Integer> last = new HashMap<>();  // char -> last index seen
        int best = 0, left = 0;
        for (int right = 0; right < s.length(); right++) {
            char c = s.charAt(right);
            if (last.containsKey(c) && last.get(c) >= left) {
                left = last.get(c) + 1;          // shrink window past the duplicate
            }
            last.put(c, right);
            best = Math.max(best, right - left + 1);
        }
        return best;
    }

    public static void main(String[] args) {
        System.out.println(new LongestUniqueSubstring().lengthOfLongestSubstring("abcabcbb")); // 3
        System.out.println(new LongestUniqueSubstring().lengthOfLongestSubstring("bbbbb"));    // 1
        System.out.println(new LongestUniqueSubstring().lengthOfLongestSubstring("pwwkew"));   // 3
    }
}
```

**Complexity.** Time `O(n)`; space `O(min(n, Σ))` where `Σ` is the alphabet size. **Edge cases:** empty string → `0`; all identical → `1`; all distinct → `n`. The `last.get(c) >= left` guard is essential so a stale index outside the window does not wrongly shrink it.

---

### Problem 24: Minimum Window Substring — sliding window O(n + m)

**Statement.** Given strings `s` and `t`, return the smallest substring of `s` that contains every character of `t` (including multiplicities), or `""` if none exists. A Hard escalation of the sliding-window pattern.

**Constraints.** `1 ≤ |s|, |t| ≤ 10⁵`.

**Approach.** Brute force tries all `O(n²)` substrings and checks each against `t`'s frequency map → `O(n²·m)`. The optimal two-phase window: expand `right` until the window is *feasible* (contains all required characters with counts), then contract `left` as far as possibility allows, recording the smallest feasible window. A `need` count array plus a single `formed` counter (how many distinct required chars are fully satisfied) lets each expansion/contraction be `O(1)`. Each pointer sweeps `s` once → `O(n + m)`. The progression is brute `O(n²·m)` → linear window by never re-scanning settled prefixes.

```java
public class MinimumWindowSubstring {
    public String minWindow(String s, String t) {
        if (s.length() < t.length()) return "";
        int[] need = new int[128];
        for (char c : t.toCharArray()) need[c]++;
        int required = t.length();          // total chars still needed (with multiplicity)
        int left = 0, bestLen = Integer.MAX_VALUE, bestStart = 0;
        for (int right = 0; right < s.length(); right++) {
            if (need[s.charAt(right)]-- > 0) required--;     // consumed a needed char
            while (required == 0) {                          // window feasible -> shrink
                if (right - left + 1 < bestLen) {
                    bestLen = right - left + 1;
                    bestStart = left;
                }
                if (need[s.charAt(left)]++ == 0) required++;  // about to drop a needed char
                left++;
            }
        }
        return bestLen == Integer.MAX_VALUE ? "" : s.substring(bestStart, bestStart + bestLen);
    }

    public static void main(String[] args) {
        System.out.println(new MinimumWindowSubstring().minWindow("ADOBECODEBANC", "ABC")); // BANC
        System.out.println(new MinimumWindowSubstring().minWindow("a", "aa"));              // ""
    }
}
```

**Complexity.** Time `O(n + m)`; space `O(1)` (fixed 128-entry table). **Edge cases:** `t` longer than `s` → `""`; no feasible window → `""`; duplicate chars in `t` handled by the `> 0` / `== 0` count guards.

---

### Problem 25: Trapping Rain Water — brute O(n²) → two-pointer O(n)/O(1)

**Statement.** Given non-negative bar heights, compute how much rainwater is trapped between them after rain.

**Constraints.** `0 ≤ n ≤ 2·10⁴`, `0 ≤ height[i] ≤ 10⁵`.

**Approach.** Water above bar `i` equals `min(maxLeft[i], maxRight[i]) − height[i]`. (1) **Brute force:** recompute both maxima by scanning for each `i` → `O(n²)`. (2) **Precomputed arrays:** two passes fill `maxLeft`/`maxRight`, then one pass sums → `O(n)` time but `O(n)` space. (3) **Two-pointer optimal:** maintain `leftMax`/`rightMax` while converging `l` and `r`. Whichever side has the smaller running max bounds the water there, so we can safely add water for that side and advance it. This collapses the auxiliary arrays into two scalars → `O(n)` time, `O(1)` space — the canonical optimal.

```
 height = [0,1,0,2,1,0,1,3,2,1,2,1]   trapped = 6
 the smaller of (leftMax,rightMax) dictates the water level at each step
```

```java
public class TrappingRainWater {
    public int trap(int[] height) {
        int l = 0, r = height.length - 1;
        int leftMax = 0, rightMax = 0, water = 0;
        while (l < r) {
            if (height[l] < height[r]) {              // left wall is the limiter
                leftMax = Math.max(leftMax, height[l]);
                water += leftMax - height[l];
                l++;
            } else {                                  // right wall is the limiter
                rightMax = Math.max(rightMax, height[r]);
                water += rightMax - height[r];
                r--;
            }
        }
        return water;
    }

    public static void main(String[] args) {
        System.out.println(new TrappingRainWater().trap(
            new int[]{0,1,0,2,1,0,1,3,2,1,2,1})); // 6
        System.out.println(new TrappingRainWater().trap(new int[]{4,2,0,3,2,5})); // 9
    }
}
```

**Complexity.** Time `O(n)`; space `O(1)`. **Edge cases:** fewer than 3 bars → `0`; monotonic heights trap nothing; flat plateau traps nothing. **Follow-up:** the 2-D "Trapping Rain Water II" needs a min-heap boundary sweep → `O(mn log(mn))`.

---

### Problem 26: Group Anagrams — sort-key O(n·k log k) vs count-key O(n·k)

**Statement.** Group a list of strings so that anagrams of one another land in the same group. A follow-up to Valid Anagram (Problem 8).

**Constraints.** `1 ≤ N ≤ 10⁴` strings, each of length `k ≤ 100`, lowercase English.

**Approach.** Every anagram class needs a canonical key. (1) **Sort-key:** sort each string's characters; anagrams produce identical keys. Building each key costs `O(k log k)`, so total `O(N·k log k)`. (2) **Count-key (optimal here):** since the alphabet is fixed at 26, build a key from the 26-length frequency vector (e.g. `"#1#0#2..."`). Each key costs `O(k)` to build, giving `O(N·k)` — strictly better when `k` is large. Both bucket strings into a `HashMap<key, List>`. The count-key wins because it removes the per-string `log k` sorting factor.

```java
import java.util.*;

public class GroupAnagrams {
    public List<List<String>> groupAnagrams(String[] strs) {
        Map<String, List<String>> groups = new HashMap<>();
        for (String s : strs) {
            int[] count = new int[26];
            for (char c : s.toCharArray()) count[c - 'a']++;
            StringBuilder key = new StringBuilder();
            for (int i = 0; i < 26; i++) key.append('#').append(count[i]);  // canonical key
            groups.computeIfAbsent(key.toString(), z -> new ArrayList<>()).add(s);
        }
        return new ArrayList<>(groups.values());
    }

    public static void main(String[] args) {
        System.out.println(new GroupAnagrams().groupAnagrams(
            new String[]{"eat","tea","tan","ate","nat","bat"}));
        // [[eat, tea, ate], [tan, nat], [bat]] (order may vary)
    }
}
```

**Complexity.** Time `O(N·k)` with the count-key (`O(N·k log k)` with the sort-key); space `O(N·k)` for the keys and groups. **Edge cases:** empty strings group together; single string → one group; all distinct → `N` singleton groups.

---

### Problem 27: Merge k Sorted Lists — heap O(N log k) vs sequential O(N·k)

**Statement.** Merge `k` sorted linked lists into one sorted list. The scaling follow-up to merging two lists (Problem 16).

**Constraints.** `k` lists, `N` total nodes across them.

**Approach.** (1) **Sequential merge:** fold lists one at a time into an accumulator. Merging the accumulator (growing toward `N`) with each list `k` times costs `O(N·k)`. (2) **Min-heap of `k` heads:** poll the global minimum, append it, push its successor. The heap holds at most `k` nodes, so each of the `N` poll/push pairs costs `O(log k)` → `O(N log k)`. (3) **Divide-and-conquer pairwise merge** matches `O(N log k)` with `O(1)` extra (recursion stack aside). The heap is the cleanest optimal — the `log k` factor (not `log N`) is the key complexity insight.

```
 heap holds one frontier node per list (<= k):
   poll min -> append -> push its .next -> repeat   => each op O(log k)
```

```java
import java.util.*;

public class MergeKSortedLists {
    static class ListNode { int val; ListNode next; ListNode(int v){ val = v; } }

    public ListNode mergeKLists(ListNode[] lists) {
        PriorityQueue<ListNode> pq = new PriorityQueue<>((a, b) -> a.val - b.val);
        for (ListNode node : lists) if (node != null) pq.offer(node);
        ListNode dummy = new ListNode(0), tail = dummy;
        while (!pq.isEmpty()) {
            ListNode smallest = pq.poll();
            tail.next = smallest;
            tail = tail.next;
            if (smallest.next != null) pq.offer(smallest.next);
        }
        return dummy.next;
    }

    public static void main(String[] args) {
        ListNode a = new ListNode(1); a.next = new ListNode(4); a.next.next = new ListNode(5);
        ListNode b = new ListNode(1); b.next = new ListNode(3); b.next.next = new ListNode(4);
        ListNode c = new ListNode(2); c.next = new ListNode(6);
        ListNode m = new MergeKSortedLists().mergeKLists(new ListNode[]{a, b, c});
        for (ListNode p = m; p != null; p = p.next) System.out.print(p.val + " "); // 1 1 2 3 4 4 5 6
    }
}
```

**Complexity.** Time `O(N log k)`; space `O(k)` for the heap. **Edge cases:** empty array or all-null lists → `null`; a single list returns unchanged; lists of unequal length handled naturally as the heap drains.

---

### Problem 28: Counting Sort & the linear-time-sort lower-bound exception

**Statement.** Sort an array of integers known to lie in a bounded range `[0, K]`. Explain how this beats the `Ω(n log n)` comparison-sort lower bound and when it applies.

**Constraints.** `1 ≤ n ≤ 10⁶`, `0 ≤ nums[i] ≤ K` with `K` comparable to `n`.

**Approach.** The `Ω(n log n)` bound applies only to **comparison-based** sorts (decision-tree argument). Counting sort never compares elements: it tallies how many times each value occurs in a `count[0..K]` array, then rebuilds the output by emitting each value `count[value]` times. Work is `O(n)` to count plus `O(K)` to sweep the buckets → `O(n + K)` time, `O(K)` space. It is linear precisely when `K = O(n)`; if `K ≫ n` (e.g. 64-bit keys), the `O(K)` term dominates and radix sort or a comparison sort is preferable. This is the canonical "when can sorting be linear?" interview question.

```
 nums = [2,5,3,0,2,3,0,3]  K=5
 count = [2,0,2,3,0,1]   (two 0s, two 2s, three 3s, one 5)
 emit  = [0,0,2,2,3,3,3,5]
```

```java
public class CountingSort {
    public int[] sort(int[] nums, int K) {
        int[] count = new int[K + 1];
        for (int x : nums) count[x]++;        // tally occurrences  -> O(n)
        int[] out = new int[nums.length];
        int idx = 0;
        for (int v = 0; v <= K; v++)          // sweep buckets in order -> O(K)
            while (count[v]-- > 0) out[idx++] = v;
        return out;
    }

    public static void main(String[] args) {
        int[] r = new CountingSort().sort(new int[]{2,5,3,0,2,3,0,3}, 5);
        System.out.println(java.util.Arrays.toString(r)); // [0, 0, 2, 2, 3, 3, 3, 5]
    }
}
```

**Complexity.** Time `O(n + K)`; space `O(n + K)`. **Edge cases:** `K ≫ n` makes it wasteful (use radix sort); negative values need an offset; the stable variant (using a prefix-sum placement) is required when sorting records by an integer key. **Follow-up:** radix sort applies counting sort digit-by-digit → `O(d·(n + b))` for `d` digits in base `b`, sorting 32/64-bit integers in effectively linear time.

---

### Problem 29: Power x^n — fast exponentiation O(log n)

**Statement.** Compute `x` raised to the integer power `n` (which may be negative). Contrast the naive `O(n)` multiply loop with binary exponentiation.

**Constraints.** `−2³¹ ≤ n ≤ 2³¹ − 1`, `−100 ≤ x ≤ 100`.

**Approach.** The naive loop multiplies `x` by itself `|n|` times → `O(n)`, which is far too slow when `n ≈ 2³¹`. Binary (fast) exponentiation halves the exponent each step using `x^n = (x^{n/2})² · x^{n%2}`: square the base and consume one bit of the exponent per iteration, multiplying the result only when that bit is set. After `log₂ n` iterations the exponent reaches zero → `O(log n)`. A multiplicative/halving loop on the exponent is the tell-tale `O(log n)` pattern. Handle negative `n` by inverting `x` and using `long` to negate `Integer.MIN_VALUE` safely.

```
 x^13, 13 = 1101b
 bit set?  ->  multiply result by current square
 squares:  x, x^2, x^4, x^8 ;  result = x^8 * x^4 * x^1 = x^13
```

```java
public class FastPower {
    public double myPow(double x, int n) {
        long exp = n;                 // widen to avoid overflow on -Integer.MIN_VALUE
        if (exp < 0) { x = 1 / x; exp = -exp; }
        double result = 1.0;
        while (exp > 0) {
            if ((exp & 1) == 1) result *= x;  // consume a set bit
            x *= x;                           // square the base
            exp >>= 1;                        // next bit
        }
        return result;
    }

    public static void main(String[] args) {
        System.out.println(new FastPower().myPow(2.0, 10));  // 1024.0
        System.out.println(new FastPower().myPow(2.0, -2));  // 0.25
    }
}
```

**Complexity.** Time `O(log n)`; space `O(1)` (iterative; recursive form uses `O(log n)` stack). **Edge cases:** `n = 0 → 1`; `n = Integer.MIN_VALUE` handled by the `long` widening; `x = 0` with negative `n` is undefined (division by zero). **Follow-up:** the same trick gives `O(log n)` matrix exponentiation for Fibonacci/linear recurrences.

---

### Problem 30: Maximal Square — 2-D DP O(mn) and the O(n) space optimization

**Statement.** In a binary matrix of `0`s and `1`s, find the area of the largest square containing only `1`s.

**Constraints.** `1 ≤ m, n ≤ 300`.

**Approach.** Brute force checks every possible square at every cell → up to `O((mn)²)`. The DP insight: let `dp[i][j]` be the side length of the largest all-`1` square whose bottom-right corner is `(i,j)`. If the cell is `1`, it can extend the squares to its top, left, and top-left, bounded by the smallest: `dp[i][j] = 1 + min(dp[i−1][j], dp[i][j−1], dp[i−1][j−1])`. Track the global max side. This is `O(mn)` time, `O(mn)` space. Because each cell only reads the previous row and the current row's left neighbor, you can compress to a single rolling row plus one `prev` scalar → **`O(n)` space**, the standard memory follow-up.

```
 dp recurrence (bottom-right corner):
   dp[i][j] = 1 + min( up, left, up-left )   when grid[i][j] == 1
   answer   = (max dp)^2
```

```java
public class MaximalSquare {
    public int maximalSquare(char[][] grid) {
        int m = grid.length, n = grid[0].length, best = 0;
        int[] dp = new int[n + 1];     // rolling row, 1-indexed columns
        int prev = 0;                  // dp[i-1][j-1] from the previous iteration
        for (int i = 1; i <= m; i++) {
            prev = 0;
            for (int j = 1; j <= n; j++) {
                int temp = dp[j];                       // save dp[i-1][j] before overwrite
                if (grid[i - 1][j - 1] == '1') {
                    dp[j] = 1 + Math.min(dp[j], Math.min(dp[j - 1], prev));
                    best = Math.max(best, dp[j]);
                } else {
                    dp[j] = 0;
                }
                prev = temp;                            // becomes dp[i-1][j-1] for next j
            }
        }
        return best * best;
    }

    public static void main(String[] args) {
        char[][] grid = {
            {'1','0','1','0','0'},
            {'1','0','1','1','1'},
            {'1','1','1','1','1'},
            {'1','0','0','1','0'}
        };
        System.out.println(new MaximalSquare().maximalSquare(grid)); // 4 (2x2 square)
    }
}
```

**Complexity.** Time `O(mn)`; space `O(n)` with the rolling row (`O(mn)` for the full table). **Edge cases:** all-`0` matrix → `0`; single row/column → at most a `1×1` square; the `prev` scalar must be reset each row so it does not leak the previous row's diagonal.

---

### Problem 31: Word Break — DP O(n²) and why memoized recursion avoids exponential blow-up

**Statement.** Given a string `s` and a dictionary `wordDict`, determine whether `s` can be segmented into a space-separated sequence of dictionary words.

**Constraints.** `1 ≤ |s| ≤ 300`, dictionary size ≤ 1000, word length ≤ 20.

**Approach.** Naive recursion tries every split point and recurses on the suffix — overlapping suffixes are recomputed, giving `O(2ⁿ)` in the worst case (e.g. `"aaaa...ab"` with dictionary `{a, aa, aaa, ...}`). Memoizing by suffix start index collapses it to `O(n)` distinct subproblems. The bottom-up DP makes this explicit: `dp[i]` = "the prefix `s[0..i)` is segmentable". `dp[0] = true`; `dp[i]` is true if some `j < i` has `dp[j]` true **and** `s[j..i)` is a dictionary word. Two nested loops over positions, with an `O(1)`-ish hash-set membership (treating word length as bounded), give `O(n²)`. The progression exponential recursion → memoization → `O(n²)` DP is the lesson.

```
 dp[i] = OR over j<i of ( dp[j] AND s[j..i) in dict )
 s="leetcode" dict={leet,code}: dp[4]=true(leet), dp[8]=true(code) -> segmentable
```

```java
import java.util.*;

public class WordBreak {
    public boolean wordBreak(String s, List<String> wordDict) {
        Set<String> dict = new HashSet<>(wordDict);
        int n = s.length();
        boolean[] dp = new boolean[n + 1];
        dp[0] = true;                                  // empty prefix is segmentable
        for (int i = 1; i <= n; i++) {
            for (int j = 0; j < i; j++) {
                if (dp[j] && dict.contains(s.substring(j, i))) {
                    dp[i] = true;
                    break;                             // i is segmentable; stop early
                }
            }
        }
        return dp[n];
    }

    public static void main(String[] args) {
        System.out.println(new WordBreak().wordBreak("leetcode", Arrays.asList("leet","code"))); // true
        System.out.println(new WordBreak().wordBreak("catsandog",
            Arrays.asList("cats","dog","sand","and","cat")));                                     // false
    }
}
```

**Complexity.** Time `O(n²)` substring/lookup pairs (each `substring` is `O(n)` to build, so a pedantic bound is `O(n³)`; bounding word length by `L` tightens it to `O(n·L)` checks). Space `O(n)` for `dp` plus the dictionary set. **Edge cases:** empty string is trivially segmentable; a single unmatched character → `false`; repeated tokens reuse dictionary words freely.

---

### Problem 32: LRU Cache — O(1) get/put via hash map + doubly linked list

**Statement.** Design a Least-Recently-Used cache with fixed `capacity` supporting `get(key)` and `put(key, value)` in `O(1)` worst-case time. A staple design-and-complexity follow-up.

**Constraints.** `1 ≤ capacity ≤ 3000`, up to `10⁵` operations.

**Approach.** A plain `HashMap` gives `O(1)` lookup but cannot tell you the *least recently used* key in `O(1)`. Scanning for the LRU entry would be `O(n)`. The optimal structure pairs a **hash map** (`key → node`) with a **doubly linked list** ordered by recency: most-recently-used at the head, least-recently-used at the tail. `get`/`put` locate the node via the map in `O(1)`, then unlink and move it to the head in `O(1)` (a doubly linked list makes removal `O(1)` because each node knows its predecessor). On overflow, evict the tail node and drop its key from the map. Every operation is genuinely `O(1)` worst-case. Sentinel head/tail nodes eliminate null-edge bookkeeping.

```
 MRU <-> ... <-> LRU
 head[*] <-> A <-> B <-> C <-> tail[*]
 touch(B): unlink B, splice after head -> head <-> B <-> A <-> C <-> tail
 evict:    remove node before tail (C)
```

```java
import java.util.*;

public class LRUCache {
    private static class Node {
        int key, value;
        Node prev, next;
        Node(int k, int v) { key = k; value = v; }
    }

    private final int capacity;
    private final Map<Integer, Node> map = new HashMap<>();
    private final Node head = new Node(0, 0);   // sentinel MRU side
    private final Node tail = new Node(0, 0);   // sentinel LRU side

    public LRUCache(int capacity) {
        this.capacity = capacity;
        head.next = tail;
        tail.prev = head;
    }

    public int get(int key) {
        Node node = map.get(key);
        if (node == null) return -1;
        moveToFront(node);
        return node.value;
    }

    public void put(int key, int value) {
        Node node = map.get(key);
        if (node != null) {
            node.value = value;
            moveToFront(node);
            return;
        }
        if (map.size() == capacity) {            // evict LRU (node before tail)
            Node lru = tail.prev;
            unlink(lru);
            map.remove(lru.key);
        }
        Node fresh = new Node(key, value);
        map.put(key, fresh);
        insertFront(fresh);
    }

    private void unlink(Node n) { n.prev.next = n.next; n.next.prev = n.prev; }
    private void insertFront(Node n) {
        n.next = head.next; n.prev = head;
        head.next.prev = n; head.next = n;
    }
    private void moveToFront(Node n) { unlink(n); insertFront(n); }

    public static void main(String[] args) {
        LRUCache c = new LRUCache(2);
        c.put(1, 1); c.put(2, 2);
        System.out.println(c.get(1)); // 1 (1 now MRU)
        c.put(3, 3);                  // evicts key 2 (LRU)
        System.out.println(c.get(2)); // -1
        c.put(4, 4);                  // evicts key 1
        System.out.println(c.get(1)); // -1
        System.out.println(c.get(3)); // 3
        System.out.println(c.get(4)); // 4
    }
}
```

**Complexity.** Time `O(1)` worst-case for both `get` and `put`; space `O(capacity)`. **Edge cases:** `get` on a missing key → `−1`; updating an existing key refreshes its value and recency without changing size; capacity `1` evicts on every distinct insert. **Follow-up:** `LFU` cache needs frequency buckets to stay `O(1)`; a thread-safe variant guards the list with a lock or uses `ConcurrentHashMap` + striping.

---

## 🧩 Extended Problems — Set 3: Hard / Expert & Optimization

### Problem 33: Maximum Subarray Sum — divide-and-conquer O(n log n) vs Kadane O(n)

**Statement.** Re-solve maximum subarray sum (cf. Problem 9) with a *divide-and-conquer* algorithm, derive its recurrence with the Master Theorem, and explain why Kadane's `O(n)` strictly dominates it. The point is reasoning about *two* correct algorithms with different complexities.

**Constraints.** `1 ≤ n ≤ 10⁵`, `−10⁴ ≤ nums[i] ≤ 10⁴`.

**Approach.** Split the array in half. The maximum subarray lies entirely in the left half, entirely in the right half, or *crosses* the midpoint. The first two are solved recursively; the crossing case is found in `O(n)` by extending greedily left and right from the center. This yields the recurrence `T(n) = 2·T(n/2) + O(n)`, which is Master-Theorem Case 2 (`a=2, b=2, f(n)=n=Θ(n^{log₂2})`) → **Θ(n log n)**. Kadane's one-pass DP achieves `Θ(n)` because the cross-boundary information it needs (best suffix ending here) is carried forward incrementally instead of recomputed per level. So divide-and-conquer is asymptotically *worse* here — a useful counterexample to the reflex that D&C always wins.

```
         [.... full array ....]
        /                       \
   left half                right half        each level does O(n) crossing work
   (recurse)                (recurse)          log n levels  ->  O(n log n)
        \         crossing sum         /
         spans the midpoint (linear scan)
```

```java
public class MaxSubarrayDivideConquer {
    public int maxSubArray(int[] nums) {
        return solve(nums, 0, nums.length - 1);
    }

    private int solve(int[] a, int lo, int hi) {
        if (lo == hi) return a[lo];                 // base case: single element
        int mid = lo + (hi - lo) / 2;
        int leftBest  = solve(a, lo, mid);          // best fully in left half
        int rightBest = solve(a, mid + 1, hi);      // best fully in right half
        int crossBest = crossSum(a, lo, mid, hi);   // best straddling the midpoint
        return Math.max(crossBest, Math.max(leftBest, rightBest));
    }

    private int crossSum(int[] a, int lo, int mid, int hi) {
        int sum = 0, leftMax = Integer.MIN_VALUE;
        for (int i = mid; i >= lo; i--) { sum += a[i]; leftMax = Math.max(leftMax, sum); }
        sum = 0; int rightMax = Integer.MIN_VALUE;
        for (int i = mid + 1; i <= hi; i++) { sum += a[i]; rightMax = Math.max(rightMax, sum); }
        return leftMax + rightMax;                  // must include at least one element each side
    }

    public static void main(String[] args) {
        System.out.println(new MaxSubarrayDivideConquer().maxSubArray(
            new int[]{-2, 1, -3, 4, -1, 2, 1, -5, 4})); // 6
    }
}
```

**Complexity.** Time `Θ(n log n)`; space `O(log n)` recursion stack. (Kadane: `Θ(n)` / `O(1)` — strictly better.) **Edge cases:** all-negative array returns the least-negative element because `crossSum` is forced to take at least one element from each side and the base case returns the single value; single element returns itself.

---

### Problem 34: 3Sum — sort + two-pointer O(n²) and why O(n² / w) or better is hard

**Statement.** Find all unique triplets `(a, b, c)` in `nums` with `a + b + c = 0`. Beat the `O(n³)` brute force and argue why `O(n²)` is the practical optimum.

**Constraints.** `3 ≤ n ≤ 3·10³`, `−10⁵ ≤ nums[i] ≤ 10⁵`; the answer set must contain no duplicate triplets.

**Approach.** Brute force enumerates all `O(n³)` triples. The optimal interview answer sorts the array (`O(n log n)`) and, for each fixed first element `nums[i]`, runs a two-pointer scan over the remaining sorted suffix to find pairs summing to `−nums[i]` in `O(n)`. The outer loop runs `n` times → `O(n²)` total, which dominates the sort. Sorting also makes de-duplication trivial: skip equal consecutive values at all three positions. No general subquadratic algorithm is known for 3Sum over arbitrary integers — it is conjectured to require `Ω(n²)` (the "3SUM-hardness" reductions in computational geometry build on exactly this), so `O(n²)` is the bar.

```
 sorted: [-4,-1,-1,0,1,2]
 fix i=-4 -> two-pointer over the rest looking for sum 4
 fix i=-1 -> lo/hi converge:  (-1,-1,2)? no  ... (-1,0,1) = 0  record
 skip duplicate i=-1 the second time to avoid repeating triplets
```

```java
import java.util.*;

public class ThreeSum {
    public List<List<Integer>> threeSum(int[] nums) {
        Arrays.sort(nums);
        List<List<Integer>> res = new ArrayList<>();
        int n = nums.length;
        for (int i = 0; i < n - 2; i++) {
            if (nums[i] > 0) break;                       // smallest is positive -> no zero sum
            if (i > 0 && nums[i] == nums[i - 1]) continue; // skip duplicate first element
            int lo = i + 1, hi = n - 1;
            while (lo < hi) {
                int sum = nums[i] + nums[lo] + nums[hi];
                if (sum < 0) lo++;
                else if (sum > 0) hi--;
                else {
                    res.add(Arrays.asList(nums[i], nums[lo], nums[hi]));
                    while (lo < hi && nums[lo] == nums[lo + 1]) lo++; // skip dup
                    while (lo < hi && nums[hi] == nums[hi - 1]) hi--; // skip dup
                    lo++; hi--;
                }
            }
        }
        return res;
    }

    public static void main(String[] args) {
        System.out.println(new ThreeSum().threeSum(new int[]{-1, 0, 1, 2, -1, -4}));
        // [[-1, -1, 2], [-1, 0, 1]]
    }
}
```

**Complexity.** Time `O(n²)`; space `O(1)` extra beyond the output (sorting is in place, ignoring sort's recursion). **Edge cases:** fewer than 3 elements → empty; all zeros → single `[0,0,0]` triplet via the dedup skips; the `nums[i] > 0` early break prunes the impossible tail. **Follow-up:** `kSum` generalizes recursively to `O(n^{k−1})`.

---

### Problem 35: Build a Heap in O(n) — defend the linear bound

**Statement.** Given an arbitrary array, turn it into a binary max-heap. Prove that bottom-up `heapify` is `Θ(n)`, *not* `Θ(n log n)`, and contrast with the naive "insert one by one" build.

**Constraints.** `1 ≤ n ≤ 10⁶`.

**Approach.** Inserting `n` elements one at a time, each sifting up `O(log n)`, costs `O(n log n)`. The bottom-up method instead sifts *down* every internal node starting from the last parent (`index n/2 − 1`) up to the root. The trick is that a node at height `h` does only `O(h)` work, and there are at most `⌈n / 2^{h+1}⌉` nodes at height `h`. The total is `Σ_{h=0}^{log n} (n / 2^{h+1})·O(h) = O(n · Σ h/2^h) = O(n · 2) = O(n)`, because `Σ h/2^h` converges to 2. The leaves (half the nodes) cost nothing, and only the rare root pays the full `log n` — so the work is dominated by the cheap many, giving linear time.

```
 height h:   #nodes ≈ n/2^{h+1}    work per node = O(h)
   leaves    h=0     n/2            0
             h=1     n/4            1
             h=2     n/8            2     Σ (n/2^{h+1})·h  ->  O(n)
   ...
   root      h=log n  1             log n
```

```java
public class HeapifyLinear {
    public void buildMaxHeap(int[] a) {
        for (int i = a.length / 2 - 1; i >= 0; i--)   // last internal node down to root
            siftDown(a, i, a.length);
    }

    private void siftDown(int[] a, int i, int n) {
        while (true) {
            int largest = i, l = 2 * i + 1, r = 2 * i + 2;
            if (l < n && a[l] > a[largest]) largest = l;
            if (r < n && a[r] > a[largest]) largest = r;
            if (largest == i) return;                 // heap property restored
            int t = a[i]; a[i] = a[largest]; a[largest] = t;
            i = largest;                              // continue sinking
        }
    }

    public static void main(String[] args) {
        int[] a = {3, 1, 6, 5, 2, 4};
        new HeapifyLinear().buildMaxHeap(a);
        System.out.println(java.util.Arrays.toString(a)); // root a[0] is the max (6)
        System.out.println("root=" + a[0]);
    }
}
```

**Complexity.** Time `Θ(n)` for the build (vs `O(n log n)` for repeated insert); space `O(1)` in place. **Edge cases:** already-a-heap array does `O(1)` per node (immediate return) but the scan is still `O(n)`; single element is trivially a heap; `n/2 − 1` correctly identifies the last parent for any `n ≥ 1`.

---

### Problem 36: Largest Rectangle in Histogram — monotonic stack O(n)

**Statement.** Given bar heights, find the area of the largest axis-aligned rectangle that fits under the histogram. Derive the `O(n)` algorithm and justify the amortized bound.

**Constraints.** `1 ≤ n ≤ 10⁵`, `0 ≤ heights[i] ≤ 10⁴`.

**Approach.** For each bar, the largest rectangle using it as the *shortest* bar spans from the first smaller bar on its left to the first smaller bar on its right. Brute force computes these spans in `O(n²)`. The optimal solution keeps a **monotonic increasing stack** of bar indices. When the incoming bar is shorter than the stack top, that top's right boundary is the current index and its left boundary is the new top after popping — so its maximal rectangle is finalized in `O(1)`. A sentinel `0` at the end flushes the stack. Each index is pushed once and popped once → total work `O(n)` by the aggregate amortized argument (≤ `n` pushes + ≤ `n` pops).

```
 heights = [2,1,5,6,2,3]
 stack monotonic increasing; on a drop, pop & finalize the popped bar's rectangle
 the 5,6 pair yields width 2 -> area 10 (the maximum here)
```

```java
import java.util.*;

public class LargestRectangleHistogram {
    public int largestRectangleArea(int[] heights) {
        int n = heights.length, maxArea = 0;
        Deque<Integer> stack = new ArrayDeque<>();   // indices, heights increasing
        for (int i = 0; i <= n; i++) {
            int h = (i == n) ? 0 : heights[i];       // sentinel flushes remaining bars
            while (!stack.isEmpty() && heights[stack.peek()] >= h) {
                int height = heights[stack.pop()];
                int width = stack.isEmpty() ? i : i - stack.peek() - 1;
                maxArea = Math.max(maxArea, height * width);
            }
            stack.push(i);
        }
        return maxArea;
    }

    public static void main(String[] args) {
        System.out.println(new LargestRectangleHistogram().largestRectangleArea(
            new int[]{2, 1, 5, 6, 2, 3})); // 10
        System.out.println(new LargestRectangleHistogram().largestRectangleArea(
            new int[]{2, 4})); // 4
    }
}
```

**Complexity.** Time `O(n)` (each index pushed/popped once); space `O(n)` for the stack. **Edge cases:** strictly increasing heights are all flushed by the sentinel; a single bar gives its own area; equal heights handled by the `>=` pop condition so widths combine correctly. **Follow-up:** "Maximal Rectangle" in a binary matrix runs this per row over cumulative heights → `O(mn)`.

---

### Problem 37: Median from a Data Stream — two heaps, O(log n) insert / O(1) query

**Statement.** Design a structure that ingests numbers one at a time and reports the running median at any point. Optimize insertion and query.

**Constraints.** Up to `5·10⁴` `addNum`/`findMedian` calls; values fit in `int`.

**Approach.** Re-sorting on each query is `O(n log n)` per call; inserting into a sorted list is `O(n)` (the shift). The optimal design keeps two heaps splitting the data at the median: a **max-heap** for the lower half and a **min-heap** for the upper half. Maintain the invariant that the max-heap has the same size as the min-heap or exactly one more. Insertion pushes/rebalances in `O(log n)`; the median is then either the max-heap root (odd count) or the average of the two roots (even count), both `O(1)`. The two-heap structure is the canonical streaming-median answer because it keeps the "boundary" elements at the heap tops without ever fully sorting.

```
 lower half (max-heap)        upper half (min-heap)
   ... <= median             median <= ...
   top = largest of lower    top = smallest of upper
 sizes: |lower| == |upper|  OR  |lower| == |upper| + 1
```

```java
import java.util.*;

public class MedianFinder {
    private final PriorityQueue<Integer> lower = new PriorityQueue<>(Collections.reverseOrder()); // max-heap
    private final PriorityQueue<Integer> upper = new PriorityQueue<>();                            // min-heap

    public void addNum(int num) {
        lower.offer(num);                 // always push to lower first
        upper.offer(lower.poll());        // move its max to upper to keep order across halves
        if (upper.size() > lower.size())  // rebalance so lower is never smaller
            lower.offer(upper.poll());
    }

    public double findMedian() {
        if (lower.size() > upper.size()) return lower.peek();     // odd count
        return (lower.peek() + upper.peek()) / 2.0;               // even count
    }

    public static void main(String[] args) {
        MedianFinder mf = new MedianFinder();
        mf.addNum(1); mf.addNum(2);
        System.out.println(mf.findMedian()); // 1.5
        mf.addNum(3);
        System.out.println(mf.findMedian()); // 2.0
    }
}
```

**Complexity.** `addNum` `O(log n)`; `findMedian` `O(1)`; space `O(n)`. **Edge cases:** first element makes `lower` size 1 → median is that element; duplicate values distribute across heaps without breaking the invariant; the unconditional push-to-lower then move-to-upper guarantees correct cross-half ordering. **Follow-up:** for a *sliding-window* median, replace the heaps with an indexed/order-statistics balanced BST (`TreeMap` of counts) to support `O(log n)` removal.

---

### Problem 38: Sieve of Eratosthenes — O(n log log n) and the segmented optimization

**Statement.** Count the primes below `n`. Improve on trial division and explain the `O(n log log n)` bound; then sketch the segmented sieve for memory.

**Constraints.** `0 ≤ n ≤ 5·10⁶`.

**Approach.** Testing each number by trial division up to `√k` costs `Σ √k = O(n√n)`. The sieve instead marks composites: starting from each prime `p`, cross out `p², p²+p, …`. The total marking work is `Σ_{p ≤ n} n/p`, and by Mertens' theorem the sum of reciprocals of primes up to `n` is `≈ ln ln n`, giving **`O(n log log n)`** — almost linear. Two micro-optimizations matter for the senior bar: start crossing out at `p²` (smaller multiples were already handled by smaller primes), and only iterate `p` while `p² ≤ n`. Memory is `O(n)`; the *segmented* sieve processes the range in `√n`-sized blocks using only the primes up to `√n`, cutting working memory to `O(√n)` for huge `n`.

```
 cross out multiples starting at p^2:
   p=2: 4 6 8 10 ...      p=3: 9 15 21 ...     p=5: 25 35 ...
 work = n/2 + n/3 + n/5 + ...  =  n·Σ(1/p)  ≈  n·ln ln n
```

```java
public class SieveOfEratosthenes {
    public int countPrimes(int n) {
        if (n < 3) return 0;                       // no primes below 2
        boolean[] composite = new boolean[n];      // composite[i] == true means i is NOT prime
        int count = 0;
        for (int p = 2; p < n; p++) {
            if (composite[p]) continue;
            count++;
            if ((long) p * p < n)                  // guard avoids int overflow on p*p
                for (int multiple = p * p; multiple < n; multiple += p)
                    composite[multiple] = true;
        }
        return count;
    }

    public static void main(String[] args) {
        System.out.println(new SieveOfEratosthenes().countPrimes(10));  // 4 (2,3,5,7)
        System.out.println(new SieveOfEratosthenes().countPrimes(100)); // 25
    }
}
```

**Complexity.** Time `O(n log log n)`; space `O(n)` (segmented: `O(√n)`). **Edge cases:** `n ≤ 2 → 0`; the `(long) p * p` cast prevents overflow when `p ≈ √Integer.MAX_VALUE`; starting at `p²` rather than `2p` is correct because every smaller multiple has a smaller prime factor. **Follow-up:** the *linear* sieve (Euler's) marks each composite exactly once → `O(n)`, at the cost of storing the smallest prime factor.

---

### Problem 39: Range Sum Query Immutable — prefix sums, O(1) query after O(n) build

**Statement.** Preprocess an array so that any range-sum query `sum(i, j)` is answered in `O(1)`. Contrast against the naive per-query scan and discuss the build/query trade-off.

**Constraints.** `1 ≤ n ≤ 10⁴`, up to `10⁴` queries.

**Approach.** Answering each query by scanning is `O(n)` per query → `O(q·n)` overall, fine for few queries but wasteful when `q` is large. The optimal preprocessing builds a **prefix-sum array** `pre[k] = nums[0] + … + nums[k−1]` in one `O(n)` pass; then `sum(i, j) = pre[j+1] − pre[i]` in `O(1)`. This is the classic "pay once, query forever" trade: `O(n)` build / `O(n)` space / `O(1)` query, amortizing beautifully across many queries. It only works because the array is *immutable*; updates would invalidate the prefix sums (that variant needs a Fenwick/segment tree for `O(log n)` update + query).

```
 nums = [ -2, 0, 3, -5, 2, -1 ]
 pre  = [0, -2, -2, 1, -4, -2, -3]    pre[k] = sum of first k elements
 sum(2,5) = pre[6] - pre[2] = -3 - (-2) = -1
```

```java
public class RangeSumImmutable {
    private final int[] pre;            // pre[k] = sum of nums[0..k-1]

    public RangeSumImmutable(int[] nums) {
        pre = new int[nums.length + 1];
        for (int k = 0; k < nums.length; k++)
            pre[k + 1] = pre[k] + nums[k];   // O(n) one-time build
    }

    public int sumRange(int i, int j) {       // inclusive [i, j]
        return pre[j + 1] - pre[i];           // O(1)
    }

    public static void main(String[] args) {
        RangeSumImmutable rs = new RangeSumImmutable(new int[]{-2, 0, 3, -5, 2, -1});
        System.out.println(rs.sumRange(0, 2)); // 1
        System.out.println(rs.sumRange(2, 5)); // -1
        System.out.println(rs.sumRange(0, 5)); // -3
    }
}
```

**Complexity.** Build `O(n)` / space `O(n)`; query `O(1)`. **Edge cases:** `i == j` returns a single element; whole-array sum is `pre[n] − pre[0]`; the `n+1` sized prefix with `pre[0]=0` removes the special case for `i == 0`. **Follow-up:** 2-D immutable range sums use a 2-D prefix with inclusion–exclusion → `O(mn)` build, `O(1)` query; mutable ranges need a Binary Indexed Tree → `O(log n)`.

---

### Problem 40: Course Schedule — topological sort O(V + E) and cycle detection

**Statement.** Given `numCourses` and prerequisite pairs `[a, b]` (take `b` before `a`), determine whether all courses can be finished — i.e. whether the prerequisite digraph is acyclic.

**Constraints.** `1 ≤ V ≤ 2000` courses, `0 ≤ E ≤ 5000` prerequisites.

**Approach.** A naive cycle check that re-explores from every node can blow up. The optimal answer is **Kahn's algorithm** (BFS topological sort): compute each node's in-degree, enqueue all zero-in-degree nodes, and repeatedly remove a node and decrement its neighbors' in-degrees, enqueuing any that hit zero. Each vertex is dequeued once and each edge relaxed once → `O(V + E)`. If the number of processed nodes is less than `V`, the leftover nodes form a cycle (their in-degrees never reach zero), so the schedule is impossible. `O(V + E)` is optimal because you must at least read every vertex and edge.

```
 in-degree:  0 -> []   1 -> [0]   (edge 1->0 means 0 depends on 1... build adj accordingly)
 queue zero-in-degree nodes; pop, relax neighbors; cycle iff processed < V
```

```java
import java.util.*;

public class CourseSchedule {
    public boolean canFinish(int numCourses, int[][] prerequisites) {
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < numCourses; i++) adj.add(new ArrayList<>());
        int[] indegree = new int[numCourses];
        for (int[] p : prerequisites) {         // edge b -> a (take b before a)
            adj.get(p[1]).add(p[0]);
            indegree[p[0]]++;
        }
        Deque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < numCourses; i++) if (indegree[i] == 0) queue.offer(i);
        int processed = 0;
        while (!queue.isEmpty()) {
            int course = queue.poll();
            processed++;
            for (int next : adj.get(course))
                if (--indegree[next] == 0) queue.offer(next);
        }
        return processed == numCourses;          // all consumed => acyclic
    }

    public static void main(String[] args) {
        System.out.println(new CourseSchedule().canFinish(2, new int[][]{{1, 0}}));            // true
        System.out.println(new CourseSchedule().canFinish(2, new int[][]{{1, 0}, {0, 1}}));    // false (cycle)
    }
}
```

**Complexity.** Time `O(V + E)`; space `O(V + E)` for the adjacency list and queue. **Edge cases:** no prerequisites → trivially `true`; a self-loop `[a, a]` is an immediate cycle (its in-degree never drops to 0); disconnected components are each processed independently. **Follow-up:** to return an actual ordering, record the dequeue order; DFS-based topo sort (postorder + recursion-stack cycle detection) is the `O(V + E)` alternative.

---

### Problem 41: Edit Distance — 2-D DP O(mn) time, O(min(m,n)) space optimization

**Statement.** Compute the minimum number of single-character insertions, deletions, or substitutions to transform `word1` into `word2` (Levenshtein distance). Optimize the space.

**Constraints.** `0 ≤ m, n ≤ 500`.

**Approach.** Naive recursion branches three ways per character → `O(3^{m+n})`. The DP defines `dp[i][j]` = edit distance between the first `i` chars of `word1` and the first `j` of `word2`. If the characters match, `dp[i][j] = dp[i−1][j−1]`; otherwise `1 + min(insert dp[i][j−1], delete dp[i−1][j], replace dp[i−1][j−1])`. Filling the table is `O(mn)` time and `O(mn)` space. The key optimization: each cell depends only on the previous row and the current row's left neighbor, so a single rolling 1-D array of length `min(m,n)+1` plus one diagonal scalar reduces memory to **`O(min(m,n))`** — the standard senior follow-up when one dimension is huge.

```
        ""  r   o   s
   ""    0  1   2   3
   h     1  1   2   3
   o     2  2   1   2
   r     3  2   2   2      dp[m][n] = 3  (horse -> ros)
   ...
```

```java
public class EditDistance {
    public int minDistance(String word1, String word2) {
        if (word1.length() < word2.length())            // ensure word2 is the shorter dimension
            return minDistance(word2, word1);
        int m = word1.length(), n = word2.length();
        int[] prev = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;        // transforming "" into word2[0..j)
        for (int i = 1; i <= m; i++) {
            int[] curr = new int[n + 1];
            curr[0] = i;                                 // transforming word1[0..i) into ""
            for (int j = 1; j <= n; j++) {
                if (word1.charAt(i - 1) == word2.charAt(j - 1)) {
                    curr[j] = prev[j - 1];               // characters match: no new op
                } else {
                    curr[j] = 1 + Math.min(prev[j - 1],  // replace
                              Math.min(prev[j],          // delete from word1
                                       curr[j - 1]));    // insert into word1
                }
            }
            prev = curr;                                 // roll the row forward
        }
        return prev[n];
    }

    public static void main(String[] args) {
        System.out.println(new EditDistance().minDistance("horse", "ros"));      // 3
        System.out.println(new EditDistance().minDistance("intention", "execution")); // 5
    }
}
```

**Complexity.** Time `O(mn)`; space `O(min(m,n))` with the rolling row (`O(mn)` for the full table). **Edge cases:** either string empty → the other's length (all inserts/deletes); identical strings → `0`; the swap to make `word2` shorter bounds the array length by the smaller dimension.

---

### Problem 42: Maximum Profit With K Transactions — DP O(nk) and the O(n) collapse when k is large

**Statement.** Given daily `prices` and an integer `k`, maximize total profit from at most `k` buy–sell transactions (no overlapping positions). Optimize for the regime where `k` is large relative to `n`.

**Constraints.** `1 ≤ n ≤ 10³`, `0 ≤ k ≤ 100`, `0 ≤ prices[i] ≤ 10³`.

**Approach.** The general DP tracks, for each transaction count `t` and day `d`, the best profit. The clean formulation keeps two running arrays per transaction: `buy[t]` = best balance after the `t`-th buy, `sell[t]` = best profit after the `t`-th sell. Each day updates all `k` transactions in `O(k)` → `O(nk)` time, `O(k)` space. The critical optimization: a transaction needs at least 2 days, so if `k ≥ n/2` the constraint is non-binding and the problem collapses to "sum every positive daily delta" — the unlimited-transactions greedy — in `O(n)` time / `O(1)` space. Recognizing this regime switch is the senior-level insight; without it the code wastes time and memory (and can even overflow naive table sizes) when `k` is huge.

```
 if k >= n/2: take every upward step:  Σ max(0, prices[i]-prices[i-1])
 else: buy[t] = max(buy[t], sell[t-1] - price);  sell[t] = max(sell[t], buy[t] + price)
```

```java
public class MaxProfitKTransactions {
    public int maxProfit(int k, int[] prices) {
        int n = prices.length;
        if (n == 0 || k == 0) return 0;
        if (k >= n / 2) {                       // unlimited-transactions regime -> O(n)
            int profit = 0;
            for (int i = 1; i < n; i++)
                if (prices[i] > prices[i - 1]) profit += prices[i] - prices[i - 1];
            return profit;
        }
        int[] buy  = new int[k + 1];
        int[] sell = new int[k + 1];
        java.util.Arrays.fill(buy, Integer.MIN_VALUE);  // can't have sold before buying
        for (int price : prices) {
            for (int t = 1; t <= k; t++) {
                buy[t]  = Math.max(buy[t],  sell[t - 1] - price); // buy t-th lot
                sell[t] = Math.max(sell[t], buy[t] + price);      // sell t-th lot
            }
        }
        return sell[k];
    }

    public static void main(String[] args) {
        System.out.println(new MaxProfitKTransactions().maxProfit(2, new int[]{3,2,6,5,0,3})); // 7
        System.out.println(new MaxProfitKTransactions().maxProfit(2, new int[]{2,4,1}));        // 2
    }
}
```

**Complexity.** Time `O(nk)` general, `O(n)` in the large-`k` regime; space `O(k)` (`O(1)` in the collapse). **Edge cases:** `k = 0` or single day → `0`; the `Integer.MIN_VALUE` seed for `buy` prevents a phantom sell before any buy; the `k ≥ n/2` guard both speeds it up and sidesteps allocating an oversized table.

---

### Problem 43: Word Ladder — BFS shortest path O(N·L²) and the bidirectional optimization

**Statement.** Given `beginWord`, `endWord`, and a `wordList`, return the length of the shortest transformation sequence where each step changes exactly one letter and every intermediate word is in the list (`0` if impossible).

**Constraints.** word length `L ≤ 10`, `1 ≤ N ≤ 5000` words, lowercase letters.

**Approach.** This is an unweighted shortest-path problem, so **BFS** gives the minimum number of steps. Building edges by comparing every pair of words is `O(N²·L)`. The faster neighbor generation uses *wildcard patterns*: for each word, replace each position with `*` to form `L` generic patterns, bucketing words that share a pattern. Generating a node's neighbors is then `O(L²)` (L positions × L-length string reconstruction), and BFS visits each of `N` words once → `O(N·L²)`. The senior optimization is **bidirectional BFS**: search forward from `begin` and backward from `end` simultaneously, expanding the smaller frontier each round; the meeting in the middle cuts the explored branching from `b^d` to `2·b^{d/2}`, often an order-of-magnitude speedup on dense graphs.

```
 patterns:  hit -> *it, h*t, hi*    dictionary words sharing a pattern are neighbors
 BFS layers from beginWord; first time endWord appears = shortest length
 bidirectional: meet in the middle -> ~ b^{d/2} instead of b^d
```

```java
import java.util.*;

public class WordLadder {
    public int ladderLength(String beginWord, String endWord, List<String> wordList) {
        Set<String> dict = new HashSet<>(wordList);
        if (!dict.contains(endWord)) return 0;
        Set<String> beginSet = new HashSet<>(), endSet = new HashSet<>();
        beginSet.add(beginWord); endSet.add(endWord);
        int steps = 1;
        while (!beginSet.isEmpty() && !endSet.isEmpty()) {
            if (beginSet.size() > endSet.size()) {        // always expand the smaller frontier
                Set<String> tmp = beginSet; beginSet = endSet; endSet = tmp;
            }
            Set<String> next = new HashSet<>();
            for (String word : beginSet) {
                char[] chars = word.toCharArray();
                for (int i = 0; i < chars.length; i++) {
                    char original = chars[i];
                    for (char c = 'a'; c <= 'z'; c++) {
                        chars[i] = c;
                        String candidate = new String(chars);
                        if (endSet.contains(candidate)) return steps + 1; // frontiers met
                        if (dict.remove(candidate)) next.add(candidate);  // visit once
                    }
                    chars[i] = original;
                }
            }
            beginSet = next;
            steps++;
        }
        return 0;
    }

    public static void main(String[] args) {
        System.out.println(new WordLadder().ladderLength("hit", "cog",
            Arrays.asList("hot","dot","dog","lot","log","cog"))); // 5
    }
}
```

**Complexity.** Time `O(N·L²)` (`L` positions × 26 letters × `O(L)` string build per word); space `O(N·L)` for the frontiers/dictionary. Bidirectional BFS keeps the same worst-case bound but halves the search depth in practice. **Edge cases:** `endWord` not in the list → `0`; `begin == end` (typically `1`); `dict.remove` marks words visited to avoid revisiting and infinite loops.

---

### Problem 44: Single Number II — bitwise O(n)/O(1) vs hashmap O(n)/O(n)

**Statement.** Every element in `nums` appears exactly three times except one, which appears once. Find that single element using constant extra space.

**Constraints.** `1 ≤ n ≤ 3·10⁴`, `−2³¹ ≤ nums[i] < 2³¹`; exactly one element appears once, all others exactly three times.

**Approach.** A `HashMap` of counts solves it in `O(n)` time but `O(n)` space, and the XOR trick that works for "appears twice" fails here because three identical values do not cancel. The constant-space solution treats each of the 32 bit positions independently: if you sum the bits at position `p` across all numbers, every triple contributes a multiple of 3, so `sum % 3` recovers the lone element's bit at `p`. The elegant `O(1)`-space finite-state version uses two accumulators, `ones` and `twos`, that track bits seen `1 mod 3` and `2 mod 3` times; each bit cycles `00 → ones → twos → 00` and is zeroed on its third appearance. This is the textbook "generalize XOR with modular bit-counting" question.

```
 per bit, count mod 3:  0 -> 1 -> 2 -> 0  ...
 ones holds bits appearing 1 (mod 3) times; twos holds bits appearing 2 (mod 3) times
 a bit reaching the third sighting is cleared from both -> only the unique bit survives in ones
```

```java
public class SingleNumberII {
    public int singleNumber(int[] nums) {
        int ones = 0, twos = 0;
        for (int x : nums) {
            ones = (ones ^ x) & ~twos;   // add x to ones unless it's already in twos
            twos = (twos ^ x) & ~ones;   // add x to twos unless it's now in ones
        }
        return ones;                     // the element seen exactly once
    }

    public static void main(String[] args) {
        System.out.println(new SingleNumberII().singleNumber(new int[]{2, 2, 3, 2}));          // 3
        System.out.println(new SingleNumberII().singleNumber(new int[]{0, 1, 0, 1, 0, 1, 99})); // 99
    }
}
```

**Complexity.** Time `O(n)` (single pass, `O(1)` work per element); space `O(1)`. **Edge cases:** negative numbers work because the bit arithmetic operates on the two's-complement representation uniformly; a single-element array returns it directly; the result is order-independent. **Follow-up:** the general "every element appears `k` times except one" uses `⌈log₂ k⌉` counters with mod-`k` bit arithmetic, or a `bitCount % k` per-position scan in `O(32n)`.

---

## Interview Q&A by Level

### 🟢 Basic

**Q: What's the difference between O, Ω, and Θ?**
O is an asymptotic *upper* bound (≤), Ω a *lower* bound (≥), Θ a *tight* bound (both). `f = Θ(g)` exactly when `f = O(g)` and `f = Ω(g)`.

**Q: Is `O(2n)` different from `O(n)`?**
No. Big-O drops constant factors, so `O(2n) = O(n)`. Likewise `O(n + 100) = O(n)` and `O(n² + n) = O(n²)`.

**Q: Why do we ignore constants and lower-order terms?**
Because asymptotic analysis predicts behavior for *large* `n`, where the highest-order term dominates and machine-specific constants are irrelevant for comparing algorithms.

**Q: What is the time complexity of binary search and why?**
O(log n): each step halves the search space, so it takes at most `log₂ n` steps to reach a single element.

**Q: Best, average, worst case of quicksort?**
Best/average Θ(n log n); worst Θ(n²) (already-sorted input with poor pivot). Randomized pivoting makes the worst case astronomically unlikely.

### 🟡 Intermediate

**Q: How do you analyze a loop where the index doubles?**
`for (i = 1; i < n; i *= 2)` runs `log₂ n` times → O(log n). Multiplicative index growth ⇒ logarithmic count.

**Q: Sequential vs nested loops?**
Sequential: add then take the max → `O(a) + O(b) = O(max(a,b))`. Nested: multiply → `O(a·b)`.

**Q: State the Master Theorem informally.**
For `T(n)=a·T(n/b)+f(n)`, compare `f(n)` with `n^(log_b a)`: if `f` is polynomially smaller → leaves dominate (Case 1); if equal up to logs → Case 2 adds a log; if `f` is polynomially larger (and regular) → root dominates (Case 3).

**Q: What is amortized analysis and when does it matter?**
It bounds the *average* cost per operation across a sequence, even if individual ops are expensive. Crucial for dynamic arrays (resize), hash tables (rehash), and Union-Find. It is *not* the same as average-case (which is probabilistic over inputs); amortized is a worst-case guarantee over a sequence.

**Q: Hash table operations — average vs worst?**
Average O(1) for insert/search/delete with good hashing and load factor. Worst O(n) if everything collides into one bucket (Java 8+ treeifies long buckets → O(log n)).

### 🟠 Advanced

**Q: Explain the three amortized methods on the dynamic array.**
*Aggregate:* total copy work `1+2+4+…+n < 2n` ⇒ O(1)/op. *Accounting:* charge 3 per append; saved credits fund future copies. *Potential:* `Φ = 2·size − capacity`; both resize and non-resize appends have amortized cost 3.

**Q: When does the Master Theorem fail?**
When `f(n)` is not polynomially separated from the watershed (e.g. `f(n)=n/log n` for `T(n)=2T(n/2)+n/log n`), when `a` or `b` aren't constants, or the regularity condition fails in Case 3. Use the recursion-tree method or Akra–Bazzi instead.

**Q: How does recursion affect space complexity?**
Each active call frame consumes stack space, so recursion depth `d` adds `O(d)` space even if it allocates nothing else. A recursive DFS on a skewed tree is O(n) space; balanced is O(log n). Tail-call-style conversion to iteration can reduce it to O(1) (the JVM does *not* optimize tail calls, so do it manually for deep recursion).

**Q: Prove comparison sorting is Ω(n log n).**
A comparison sort is a decision tree with `n!` leaves (one per permutation). A binary tree with `n!` leaves has height ≥ `log₂(n!) = Θ(n log n)` (Stirling). The height equals worst-case comparisons ⇒ Ω(n log n).

**Q: Why is Union-Find effectively O(1)?**
With union-by-rank + path compression, `m` operations cost `O(m·α(n))`, where `α` is the inverse Ackermann function — below 5 for any conceivable `n`. So it's near-constant but not strictly O(1).

### 🔴 Expert

**Q: Distinguish amortized, average-case, and worst-case, with an example where they all differ.**
Worst-case = slowest single op on the worst input. Average-case = expected over an input distribution. Amortized = worst-case *per-op average over a sequence*, no probability. A dynamic array append: worst-case O(n) (single resize), amortized O(1) (sequence), and these say nothing about input distribution. Hash lookup: worst O(n), average O(1) (probabilistic). They answer different questions.

**Q: How would complexity reasoning change at TB scale / distributed systems?**
Asymptotics still rank algorithms, but constants (disk I/O, network round-trips, cache misses) often dominate. You shift to *external-memory* / *cache-oblivious* models counting block transfers (`O(n/B logₘ/B n/B)` for external sort), and to communication complexity in distributed settings. An O(n) algorithm doing random disk seeks can lose to an O(n log n) one that's sequential.

**Q: Give a real-world example where a worse asymptotic complexity wins.**
Insertion sort (O(n²)) beats merge sort (O(n log n)) for tiny `n` (< ~16) due to low constants and cache locality — which is why Timsort and introsort fall back to insertion sort on small runs. Similarly, naive matrix multiply often beats Strassen for small matrices.

**Q: How do you amortize across a data structure that both grows and shrinks?**
Use a potential function that stays non-negative through both directions. For a dynamic array that grows at full and shrinks at ¼-full, `Φ = |2·size − capacity|`-style potentials keep both expansion and contraction amortized O(1), and the asymmetric thresholds (grow at 1, shrink at ¼) prevent oscillation thrashing where alternating add/remove repeatedly resizes.

**Q: What is the complexity of building a heap?**
`O(n)`, not `O(n log n)`. Bottom-up `heapify` does work `∑ (n/2^h)·O(h)` over heights `h`, which converges to `O(n)` because most nodes are near the leaves and sift down only a little.

---

## ⚠️ Common Pitfalls

- **Confusing O with Θ.** Saying "O(n log n)" when the input forces exactly that is technically a weaker claim than Θ. Be precise when asked.
- **Forgetting the recursion stack in space.** A recursive solution that allocates nothing still uses O(depth) space. Interviewers love catching "this is O(1) space" on a recursive function.
- **Treating amortized as average-case.** Amortized is a *worst-case guarantee* over a sequence with no probability involved.
- **Mis-summing nested loops.** `for j = i..n` is still Θ(n²), not O(n) — the triangular sum is half of `n²`, and constants drop.
- **Assuming hash operations are always O(1).** Adversarial keys or bad hash functions degrade to O(n). Mention the average-vs-worst distinction.
- **Growing a dynamic array by a constant.** `+1` per resize gives Θ(n²) total, *not* amortized O(1) — only multiplicative growth works.
- **Applying the Master Theorem blindly.** Check that `f(n)` is *polynomially* separated and that `a, b` are constants; otherwise it doesn't apply.
- **Counting string/`substring` cost as O(1).** In Java, `s.substring`, `+` concatenation in a loop, and `String.contains` hide O(L) or O(n²) costs.
- **Ignoring input vs auxiliary space.** "In-place" usually means O(1) *auxiliary* space; the input itself doesn't count, but be explicit.
- **`O(log n)` base confusion.** Logarithm bases differ by a constant factor (`log_a n = log_b n / log_b a`), so the base is irrelevant in Big-O — don't write `O(log₂ n)` as if the base mattered.

---

## 📚 Further Reading

- Cormen, Leiserson, Rivest, Stein — *Introduction to Algorithms* (CLRS), ch. 3 (asymptotics), 4 (recurrences & Master Theorem), 17 (amortized analysis). The definitive reference.
- Skiena — *The Algorithm Design Manual*, ch. 2 (algorithm analysis) — intuition-first.
- Sedgewick & Wayne — *Algorithms* (4th ed.) and the companion Coursera course — empirical "doubling test" methodology.
- *Big-O Cheat Sheet* — bigocheatsheet.com — quick visual reference for data-structure and sorting complexities.
- Demaine — MIT 6.046 / 6.851 (Advanced Data Structures) lecture notes — for amortization, potential method, and external-memory models.
- Akra & Bazzi (1998) — the generalization of the Master Theorem for recurrences it can't handle.

[← Back to master index](../README.md) · [← DSA index](README.md)
