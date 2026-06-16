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
