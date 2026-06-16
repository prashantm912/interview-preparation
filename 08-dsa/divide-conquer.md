# Divide & Conquer

Divide & Conquer (D&C) is the algorithmic strategy of breaking a problem into independent sub-problems of the same shape, solving them recursively, and combining their results. It powers merge sort, binary search, fast multiplication, FFT, closest-pair geometry, and the recurrence machinery (the Master Theorem) you are expected to apply on the whiteboard in seconds.

[← Back to master index](../README.md) | [← DSA index](README.md)

---

## Concept & Intuition

A divide-and-conquer algorithm has three phases:

1. **Divide** — split the input of size `n` into `a` sub-problems, each (typically) of size `n/b`.
2. **Conquer** — solve each sub-problem recursively; once the input is small enough (the *base case*) solve it directly.
3. **Combine** — merge the sub-solutions into a solution for the original problem.

The total cost is captured by a **recurrence**:

```
T(n) = a · T(n/b) + f(n)
        ▲        ▲       ▲
        |        |       └── combine cost (the "merge" step)
        |        └────────── size of each sub-problem
        └─────────────────── number of sub-problems
```

A recursion tree makes the cost visible. For merge sort `a = 2, b = 2, f(n) = Θ(n)`:

```
                  n            ← combine cost n at the root
                /   \
             n/2     n/2       ← total n at this level
            /  \     /  \
         n/4  n/4  n/4  n/4    ← total n at this level
         ...                   ← log₂ n levels, each costing Θ(n)
       1 1 1 ... (n leaves)
   Total = Θ(n) per level × Θ(log n) levels = Θ(n log n)
```

**When to use D&C**
- The problem decomposes into **independent** sub-problems (no overlap — that is where dynamic programming takes over with memoization).
- A cheap **combine** step exists (linear or sub-quadratic). If combining is as hard as the original problem, D&C buys nothing.
- The search space is **sorted / monotonic** (binary search) or has **geometric structure** (closest pair, skyline).
- You want to beat a naive bound: Karatsuba beats `O(n²)` multiplication; FFT beats `O(n²)` convolution; matrix exponentiation beats `O(n)` linear recurrences.

**Key invariants**
- **Disjoint sub-problems.** D&C assumes sub-problems do not share work; if they do, you are looking at DP.
- **Shrinking measure.** Each recursive call must operate on a strictly smaller input so recursion terminates. Always verify the base case is reachable.
- **Combine correctness.** The combine step must reconstruct the global answer from *local* answers plus *cross-boundary* information (e.g., the maximum subarray crossing the midpoint, or pairs straddling the dividing line in closest-pair).

### The Master Theorem

For `T(n) = a·T(n/b) + f(n)` with `a ≥ 1, b > 1`, compare `f(n)` with the *watershed* function `n^(log_b a)`:

| Case | Condition | Result |
|---|---|---|
| 1 | `f(n) = O(n^(log_b a − ε))` for some `ε > 0` | `T(n) = Θ(n^(log_b a))` (leaves dominate) |
| 2 | `f(n) = Θ(n^(log_b a) · logᵏ n)`, `k ≥ 0` | `T(n) = Θ(n^(log_b a) · log^(k+1) n)` |
| 3 | `f(n) = Ω(n^(log_b a + ε))` and regularity `a·f(n/b) ≤ c·f(n)`, `c < 1` | `T(n) = Θ(f(n))` (root dominates) |

Worked examples:
- Merge sort: `a=2, b=2`, `n^(log₂2)=n`, `f(n)=n` → Case 2 (`k=0`) → `Θ(n log n)`.
- Binary search: `a=1, b=2`, `n^(log₂1)=n⁰=1`, `f(n)=Θ(1)` → Case 2 → `Θ(log n)`.
- Karatsuba: `a=3, b=2`, `n^(log₂3)≈n^1.585`, `f(n)=Θ(n)` → Case 1 → `Θ(n^1.585)`.
- Strassen: `a=7, b=2`, `n^(log₂7)≈n^2.807` → Case 1 → `Θ(n^2.807)`.
- `T(n)=2T(n/2)+n log n`: watershed `n`, `f=n log n=Θ(n·log¹n)` → Case 2 (`k=1`) → `Θ(n log² n)`.

The **Akra–Bazzi** method generalizes the Master Theorem to unequal split sizes (e.g., `T(n) = T(n/5) + T(7n/10) + O(n)` for median-of-medians, which resolves to `Θ(n)`).

---

## Complexity Cheat-Sheet

| Algorithm | Recurrence | Time | Space | Notes |
|---|---|---|---|---|
| Merge sort | `2T(n/2)+Θ(n)` | `Θ(n log n)` | `O(n)` aux | Stable, predictable, external-sort friendly |
| Quicksort (avg) | `2T(n/2)+Θ(n)` | `Θ(n log n)` | `O(log n)` stack | In-place; worst `O(n²)` on bad pivots |
| Quicksort (worst) | `T(n−1)+Θ(n)` | `O(n²)` | `O(n)` | Sorted input + last-element pivot |
| Binary search | `T(n/2)+Θ(1)` | `O(log n)` | `O(1)` iter | Requires sorted / monotonic predicate |
| Max subarray (D&C) | `2T(n/2)+Θ(n)` | `O(n log n)` | `O(log n)` | Kadane does it in `O(n)` |
| Closest pair of points | `2T(n/2)+Θ(n)` | `O(n log n)` | `O(n)` | Strip check is `O(n)` after sort |
| Count inversions | `2T(n/2)+Θ(n)` | `O(n log n)` | `O(n)` | Piggybacks on merge sort |
| Karatsuba multiply | `3T(n/2)+Θ(n)` | `O(n^1.585)` | `O(n)` | Beats schoolbook `O(n²)` |
| Majority (Boyer–Moore) | linear scan | `O(n)` | `O(1)` | Beats D&C `O(n log n)` |
| Majority (D&C) | `2T(n/2)+Θ(n)` | `O(n log n)` | `O(log n)` | Combine merges candidate counts |
| Median of medians (select) | `T(n/5)+T(7n/10)+Θ(n)` | `O(n)` worst | `O(log n)` | Guaranteed-linear `k`-th smallest |
| Matrix exponentiation | `Θ(log k)` mults | `O(d³ log k)` | `O(d²)` | `d`×`d` matrix, exponent `k` |
| Skyline problem | `2T(n/2)+Θ(n)` | `O(n log n)` | `O(n)` | Merge two skylines like merge sort |

---

## Patterns & Recognition

Reach for divide & conquer when you see any of these signals:

- **"Sorted array" + "find / count something"** → binary search or a binary-search-on-answer (parametric search). Watch for the phrase "minimize the maximum" or "maximize the minimum".
- **"Count pairs (i, j) with i < j and some order relation"** → inversion-style counting during a merge sort.
- **Linear recurrence with a huge index** (`Fibonacci(10^18) mod p`, "number of paths after k steps") → matrix exponentiation / fast power. Anything of the form "k up to 10^9 or 10^18" rules out O(k) and screams `O(log k)`.
- **Geometric "closest / nearest" over points in a plane** → sort + divide on a coordinate + a narrow strip merge.
- **"Merge / overlay multiple ranges or shapes"** (skylines, intervals, segment merges) → recursively split the set, solve halves, merge like merge sort.
- **Multiplying very large numbers or polynomials** → Karatsuba (numbers) or FFT (polynomials/convolution).
- **"Find the k-th smallest with worst-case guarantee"** → quickselect (average linear) or median-of-medians (worst-case linear).
- **A brute force is `O(n²)` and the data has independent halves** → ask whether the combine step can be done in `O(n)` or `O(n log n)`; if so, D&C drops you to `O(n log n)`.

Recognition heuristic: if you can describe the answer for the *left half*, the answer for the *right half*, and the answer for things that *cross the boundary* — and the boundary case is cheap — divide and conquer is the natural fit.

---

## Coding Problems

### Problem 1: Binary Search (and First/Last Occurrence)

**Statement.** Given a sorted array `nums` and a target, return its index, or `-1` if absent. Follow-up: return the first and last index of the target.

**Constraints.** `1 ≤ n ≤ 10^5`, array sorted ascending, values fit in `int`.

**Approach.**
- *Brute force.* Linear scan, `O(n)`.
- *Optimal.* Halve the search interval each step. The invariant: if the target exists, it lies in `[lo, hi]`. Use `lo + (hi - lo) / 2` to avoid integer overflow.

```java
public class BinarySearch {

    // Returns any index of target, or -1.
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

    // Leftmost index where nums[i] == target, else -1 (lower-bound style).
    public int firstOccurrence(int[] nums, int target) {
        int lo = 0, hi = nums.length, ans = -1;   // hi exclusive
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (nums[mid] >= target) hi = mid;     // keep looking left
            else lo = mid + 1;
        }
        return (lo < nums.length && nums[lo] == target) ? lo : ans;
    }

    public int lastOccurrence(int[] nums, int target) {
        int lo = 0, hi = nums.length;
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (nums[mid] <= target) lo = mid + 1;  // keep looking right
            else hi = mid;
        }
        int idx = lo - 1;
        return (idx >= 0 && nums[idx] == target) ? idx : -1;
    }
}
```

**Dry run** on `nums=[1,3,3,3,7]`, `target=3`. `firstOccurrence`: `lo=0,hi=5,mid=2` → `nums[2]=3≥3` so `hi=2`; `mid=1` → `3≥3` so `hi=1`; `mid=0` → `1<3` so `lo=1`; loop ends, `nums[1]==3` → returns `1`. ✓

**Time:** `O(log n)`. **Space:** `O(1)`.

**Follow-ups.** Search in a rotated sorted array; find the peak element; binary-search-on-answer ("minimum eating speed", "split array largest sum"); `Arrays.binarySearch` return-value semantics (negative insertion point).

---

### Problem 2: Merge Sort

**Statement.** Sort an integer array in ascending order. Must be stable and `O(n log n)` worst case.

**Constraints.** `1 ≤ n ≤ 5·10^4`, values fit in `int`.

**Approach.**
- *Brute force.* Insertion/selection sort, `O(n²)`.
- *Optimal.* Recursively sort the two halves, then merge them with a linear two-pointer pass. The merge step is where ordered output is built, and keeping `<=` for the left element preserves **stability**.

```java
public class MergeSort {

    public void sort(int[] a) {
        if (a.length < 2) return;
        int[] aux = new int[a.length];
        sort(a, aux, 0, a.length - 1);
    }

    private void sort(int[] a, int[] aux, int lo, int hi) {
        if (lo >= hi) return;
        int mid = lo + (hi - lo) / 2;
        sort(a, aux, lo, mid);
        sort(a, aux, mid + 1, hi);
        merge(a, aux, lo, mid, hi);
    }

    private void merge(int[] a, int[] aux, int lo, int mid, int hi) {
        System.arraycopy(a, lo, aux, lo, hi - lo + 1);
        int i = lo, j = mid + 1;
        for (int k = lo; k <= hi; k++) {
            if (i > mid)               a[k] = aux[j++];     // left exhausted
            else if (j > hi)           a[k] = aux[i++];     // right exhausted
            else if (aux[i] <= aux[j]) a[k] = aux[i++];     // <= keeps stability
            else                       a[k] = aux[j++];
        }
    }
}
```

**Dry run** on `[3,1,2]`. Split → `[3]` and `[1,2]`; `[1,2]` splits into `[1]`,`[2]` → merges to `[1,2]`; final merge of `[3]` and `[1,2]`: compare 3 vs 1 → 1, 3 vs 2 → 2, left over 3 → result `[1,2,3]`. ✓

**Time:** `Θ(n log n)` (best = average = worst). **Space:** `O(n)` for `aux`.

**Follow-ups.** Sort a linked list (no `aux`, `O(1)` extra via splice — preferred for lists); external/merge sort for data that does not fit in RAM; bottom-up iterative merge sort; why Java's `Arrays.sort(Object[])` uses TimSort (a merge-sort variant) while primitives use dual-pivot quicksort.

---

### Problem 3: Maximum Subarray — Divide & Conquer

**Statement.** Find the contiguous subarray with the largest sum and return that sum.

**Constraints.** `1 ≤ n ≤ 10^5`, `−10^4 ≤ nums[i] ≤ 10^4`.

**Approach.**
- *Brute force.* All `O(n²)` subarrays.
- *Kadane.* `O(n)` running-max — the production answer.
- *D&C (asked to show recursion mastery).* The max subarray is entirely in the left half, entirely in the right half, or **crosses** the midpoint. The crossing sum is found by extending greedily left and right from `mid`.

```java
public class MaxSubarrayDC {

    public int maxSubArray(int[] nums) {
        return solve(nums, 0, nums.length - 1);
    }

    private int solve(int[] a, int lo, int hi) {
        if (lo == hi) return a[lo];
        int mid = lo + (hi - lo) / 2;
        int left  = solve(a, lo, mid);
        int right = solve(a, mid + 1, hi);
        int cross = crossSum(a, lo, mid, hi);
        return Math.max(cross, Math.max(left, right));
    }

    private int crossSum(int[] a, int lo, int mid, int hi) {
        int sum = 0, leftBest = Integer.MIN_VALUE;
        for (int i = mid; i >= lo; i--) {       // extend left from mid
            sum += a[i];
            leftBest = Math.max(leftBest, sum);
        }
        sum = 0;
        int rightBest = Integer.MIN_VALUE;
        for (int i = mid + 1; i <= hi; i++) {   // extend right from mid+1
            sum += a[i];
            rightBest = Math.max(rightBest, sum);
        }
        return leftBest + rightBest;            // must include both halves
    }

    // O(n) Kadane reference.
    public int kadane(int[] nums) {
        int best = nums[0], cur = nums[0];
        for (int i = 1; i < nums.length; i++) {
            cur = Math.max(nums[i], cur + nums[i]);
            best = Math.max(best, cur);
        }
        return best;
    }
}
```

**Dry run** on `[-2,1,-3,4,-1,2,1,-5,4]`. The crossing case around the `4,-1,2,1` region yields `6`, which beats both recursive halves; Kadane confirms `6`. ✓

**Time:** D&C `O(n log n)`, Kadane `O(n)`. **Space:** D&C `O(log n)` stack, Kadane `O(1)`.

**Follow-ups.** Return the actual indices, not just the sum; maximum *product* subarray (track min and max because of sign flips); circular maximum subarray (total − minimum-subarray); 2-D maximum sum rectangle (Kadane over column prefix sums).

---

### Problem 4: Count Inversions

**Statement.** Count pairs `(i, j)` with `i < j` and `nums[i] > nums[j]` — a measure of how unsorted the array is.

**Constraints.** `1 ≤ n ≤ 10^5`; the count can exceed `int`, so accumulate in a `long`.

**Approach.**
- *Brute force.* All pairs, `O(n²)`.
- *Optimal.* Merge sort, counting cross-inversions during the merge: when the right-half element is smaller than the current left-half element, every remaining left element forms an inversion with it.

```java
public class CountInversions {

    public long countInversions(int[] a) {
        int[] aux = new int[a.length];
        return sort(a, aux, 0, a.length - 1);
    }

    private long sort(int[] a, int[] aux, int lo, int hi) {
        if (lo >= hi) return 0;
        int mid = lo + (hi - lo) / 2;
        long count = sort(a, aux, lo, mid) + sort(a, aux, mid + 1, hi);
        count += merge(a, aux, lo, mid, hi);
        return count;
    }

    private long merge(int[] a, int[] aux, int lo, int mid, int hi) {
        System.arraycopy(a, lo, aux, lo, hi - lo + 1);
        long inv = 0;
        int i = lo, j = mid + 1;
        for (int k = lo; k <= hi; k++) {
            if (i > mid)               a[k] = aux[j++];
            else if (j > hi)           a[k] = aux[i++];
            else if (aux[i] <= aux[j]) a[k] = aux[i++];
            else {
                a[k] = aux[j++];
                inv += (mid - i + 1);   // all remaining left elems > aux[j]
            }
        }
        return inv;
    }
}
```

**Dry run** on `[2,4,1,3,5]`. Inversions: `(2,1),(4,1),(4,3)` → `3`. Merge sort detects each cross-inversion when a right element jumps ahead of remaining left elements. ✓

**Time:** `O(n log n)`. **Space:** `O(n)`.

**Follow-ups.** Count "reverse pairs" where `nums[i] > 2·nums[j]` (LeetCode 493 — count before merging without disturbing the `<=` rule); count smaller elements to the right of each index (merge sort with index tracking, or a BIT/Fenwick tree); relate inversions to the minimum adjacent swaps needed to sort.

---

### Problem 5: Majority Element — Boyer–Moore vs Divide & Conquer

**Statement.** An element appearing more than `⌊n/2⌋` times is the majority element. It is guaranteed to exist; return it.

**Constraints.** `1 ≤ n ≤ 5·10^4`.

**Approach.**
- *Hash map count.* `O(n)` time, `O(n)` space.
- *Boyer–Moore voting.* `O(n)` time, `O(1)` space — the optimal answer. Maintain a candidate and a balance counter; equal votes cancel out, so the true majority survives.
- *D&C.* The majority of the whole array must be the majority of at least one half. Recurse, then in the combine step count occurrences of each half's candidate in the full range.

```java
public class MajorityElement {

    // Optimal: O(n) time, O(1) space.
    public int boyerMoore(int[] nums) {
        int candidate = nums[0], count = 0;
        for (int x : nums) {
            if (count == 0) candidate = x;
            count += (x == candidate) ? 1 : -1;
        }
        return candidate;   // guaranteed majority exists
    }

    // Divide & conquer: O(n log n).
    public int majorityDC(int[] nums) {
        return solve(nums, 0, nums.length - 1);
    }

    private int solve(int[] a, int lo, int hi) {
        if (lo == hi) return a[lo];
        int mid = lo + (hi - lo) / 2;
        int left  = solve(a, lo, mid);
        int right = solve(a, mid + 1, hi);
        if (left == right) return left;          // both halves agree
        int lc = count(a, left, lo, hi);
        int rc = count(a, right, lo, hi);
        return lc > rc ? left : right;           // pick the more frequent
    }

    private int count(int[] a, int val, int lo, int hi) {
        int c = 0;
        for (int i = lo; i <= hi; i++) if (a[i] == val) c++;
        return c;
    }
}
```

**Dry run** (Boyer–Moore) on `[2,2,1,1,1,2,2]`: candidate flips with the votes but `2` ends with positive count → returns `2`. ✓

**Time:** Boyer–Moore `O(n)`, D&C `O(n log n)` (`T(n)=2T(n/2)+O(n)`). **Space:** `O(1)` vs `O(log n)`.

**Follow-ups.** Majority Element II — all elements appearing more than `⌊n/3⌋` times (at most two; generalized Boyer–Moore with two candidates); verify the Boyer–Moore result with a second pass when existence is *not* guaranteed; streaming/Misra–Gries generalization for the top-k frequent elements.

---

### Problem 6: Pow(x, n) — Fast Exponentiation (Binary Exponentiation)

**Statement.** Implement `pow(x, n)` computing `x` raised to the integer power `n` (n may be negative).

**Constraints.** `−100 < x < 100`, `−2^31 ≤ n ≤ 2^31 − 1`.

**Approach.**
- *Brute force.* Multiply `x` n times, `O(n)`.
- *Optimal.* `x^n = (x^(n/2))² · x^(n mod 2)`. Halving the exponent each step gives `O(log n)`. Handle `n = Integer.MIN_VALUE` carefully — negating it overflows `int`, so widen to `long`.

```java
public class FastPower {

    public double myPow(double x, int n) {
        long e = n;                 // widen to avoid MIN_VALUE overflow
        if (e < 0) { x = 1 / x; e = -e; }
        double result = 1.0;
        while (e > 0) {
            if ((e & 1) == 1) result *= x;  // odd bit → fold current base in
            x *= x;                          // square the base
            e >>= 1;                         // drop the lowest bit
        }
        return result;
    }

    // Recursive D&C form (same Θ(log n)).
    public double powRec(double x, long n) {
        if (n == 0) return 1.0;
        if (n < 0)  return 1.0 / powRec(x, -n);
        double half = powRec(x, n / 2);
        return (n % 2 == 0) ? half * half : half * half * x;
    }
}
```

**Dry run** on `x=2, n=10` (binary `1010`): bit0=0 skip, square→4; bit1=1 result=4, square→16; bit2=0 square→256; bit3=1 result=4·256=1024. ✓

**Time:** `O(log n)`. **Space:** `O(1)` iterative, `O(log n)` recursive.

**Follow-ups.** Modular exponentiation `(x^n) mod m` for cryptography/hashing (use `long` and `% m` after each multiply); fast power under a custom monoid; this generalizes directly into the next problem (matrix fast power).

---

### Problem 7: Matrix Exponentiation — Fibonacci(n) in O(log n)

**Statement.** Compute the n-th Fibonacci number modulo `1_000_000_007`, where `n` can be as large as `10^18`.

**Constraints.** `0 ≤ n ≤ 10^18`, answer modulo `1e9+7`.

**Approach.**
- *DP.* `O(n)` — too slow when `n = 10^18`.
- *Optimal.* The transition `[F(n+1), F(n)] = M · [F(n), F(n−1)]` with `M = [[1,1],[1,0]]` means `M^n` encodes `F(n)`. Raise the matrix to the n-th power with binary exponentiation → `O(log n)` matrix multiplications, each `O(2³)`.

```java
public class MatrixFastPower {
    private static final long MOD = 1_000_000_007L;

    public long fib(long n) {
        if (n == 0) return 0;
        long[][] base = {{1, 1}, {1, 0}};
        long[][] r = matPow(base, n - 1);
        return r[0][0];                 // M^(n-1)[0][0] == F(n)
    }

    private long[][] matPow(long[][] m, long p) {
        long[][] result = {{1, 0}, {0, 1}};   // identity matrix
        while (p > 0) {
            if ((p & 1) == 1) result = mul(result, m);
            m = mul(m, m);
            p >>= 1;
        }
        return result;
    }

    private long[][] mul(long[][] a, long[][] b) {
        int n = a.length;
        long[][] c = new long[n][n];
        for (int i = 0; i < n; i++)
            for (int k = 0; k < n; k++) {
                if (a[i][k] == 0) continue;
                for (int j = 0; j < n; j++)
                    c[i][j] = (c[i][j] + a[i][k] * b[k][j]) % MOD;
            }
        return c;
    }
}
```

**Dry run** on `n=6`: `M^5[0][0] = 8 = F(6)`. ✓ (sequence 0,1,1,2,3,5,8 → index 6 is 8).

**Time:** `O(d³ log n)` (`d=2` here, so effectively `O(log n)`). **Space:** `O(d²)`.

**Follow-ups.** Any linear recurrence `a(n)=c₁a(n−1)+…+c_k a(n−k)` becomes a `k×k` companion matrix → `O(k³ log n)`; count paths of length exactly `k` in a graph via the k-th power of the adjacency matrix; tribonacci and "climbing stairs with big n"; combine with the modular fast-power from Problem 6.

---

### Problem 8: Closest Pair of Points (Senior / Hard)

**Statement.** Given `n` points in the plane, find the smallest Euclidean distance between any two of them.

**Constraints.** `2 ≤ n ≤ 10^5`; coordinates fit in `int`. Brute force `O(n²)` is too slow.

**Approach.**
- *Brute force.* Check all pairs, `O(n²)`.
- *Optimal D&C.* Sort by x. Split at the median x into left/right halves and recurse to get `d = min(dl, dr)`. The only remaining candidates are pairs that straddle the dividing line within a vertical **strip** of width `2d`. Sort the strip by y; a geometric packing argument proves each point need only be compared with the next ~7 points in y-order → the strip scan is `O(n)`, giving `O(n log n)` overall.

```java
import java.util.*;

public class ClosestPair {

    public double closest(int[][] pts) {
        int n = pts.length;
        int[][] byX = pts.clone();
        Arrays.sort(byX, (p, q) -> Integer.compare(p[0], q[0]));
        int[][] byY = byX.clone();
        Arrays.sort(byY, (p, q) -> Integer.compare(p[1], q[1]));
        return rec(byX, byY, 0, n - 1);
    }

    private double rec(int[][] byX, int[][] byY, int lo, int hi) {
        int n = hi - lo + 1;
        if (n <= 3) return brute(byX, lo, hi);   // small base case
        int mid = lo + (hi - lo) / 2;
        int midX = byX[mid][0];

        // Partition byY into left/right preserving y-order.
        int[][] leftY = new int[mid - lo + 1][];
        int[][] rightY = new int[hi - mid][];
        int li = 0, ri = 0;
        for (int[] p : byY) {
            if (insideRange(byX, lo, mid, p)) leftY[li++] = p;
            else if (insideRange(byX, mid + 1, hi, p)) rightY[ri++] = p;
        }

        double dl = rec(byX, leftY, lo, mid);
        double dr = rec(byX, rightY, mid + 1, hi);
        double d = Math.min(dl, dr);

        // Build the strip of points within d of the dividing line, in y-order.
        int[][] strip = new int[n][];
        int s = 0;
        for (int[] p : byY)
            if (Math.abs(p[0] - midX) < d) strip[s++] = p;

        for (int i = 0; i < s; i++)
            for (int j = i + 1; j < s && (strip[j][1] - strip[i][1]) < d; j++)
                d = Math.min(d, dist(strip[i], strip[j]));   // ≤7 comparisons
        return d;
    }

    // Helper to keep the example self-contained; in practice carry index ranges.
    private boolean insideRange(int[][] byX, int lo, int hi, int[] p) {
        for (int i = lo; i <= hi; i++)
            if (byX[i] == p) return true;
        return false;
    }

    private double brute(int[][] a, int lo, int hi) {
        double d = Double.MAX_VALUE;
        for (int i = lo; i <= hi; i++)
            for (int j = i + 1; j <= hi; j++)
                d = Math.min(d, dist(a[i], a[j]));
        return d;
    }

    private double dist(int[] p, int[] q) {
        double dx = p[0] - q[0], dy = p[1] - q[1];
        return Math.sqrt(dx * dx + dy * dy);
    }
}
```

> Note: the `insideRange` helper above is `O(n)` per call and is written for clarity; the textbook `O(n log n)` version carries explicit index bounds (or pre-tags each point with its side) so the partition stays linear. Always state this trade-off to the interviewer.

**Dry run** on `(0,0),(3,4),(1,1),(7,7)`: left half `{(0,0),(1,1)}` gives `√2≈1.414`; right half larger; strip confirms no straddling pair beats `1.414`. ✓

**Time:** `O(n log n)` (textbook version). **Space:** `O(n)`.

**Follow-ups.** Return the actual pair of points; 3-D closest pair; "given a set, is any pair within distance d?" via a uniform grid in expected `O(n)`; relate to Delaunay triangulation / k-d trees for repeated nearest-neighbor queries.

---

### Problem 9: The Skyline Problem (Hard)

**Statement.** Buildings are given as `[left, right, height]`. Produce the skyline as a list of `[x, height]` key points where the height changes, left to right.

**Constraints.** `0 ≤ buildings ≤ 10^4`; coordinates up to `2^31 − 1`.

**Approach.**
- *Brute force.* Sweep every x-coordinate and take the max overlapping height, `O(n·k)`.
- *Optimal D&C.* This is exactly merge sort over buildings. Recursively compute the skyline of the left half and the right half, then **merge** the two skylines with a two-pointer sweep, tracking the current height contributed by each side and emitting a key point only when the running max changes.

```java
import java.util.*;

public class Skyline {

    public List<int[]> getSkyline(int[][] buildings) {
        if (buildings.length == 0) return new ArrayList<>();
        return rec(buildings, 0, buildings.length - 1);
    }

    private List<int[]> rec(int[][] b, int lo, int hi) {
        if (lo == hi) {                              // single building
            List<int[]> res = new ArrayList<>();
            res.add(new int[]{b[lo][0], b[lo][2]});  // left edge, height
            res.add(new int[]{b[lo][1], 0});         // right edge drops to 0
            return res;
        }
        int mid = lo + (hi - lo) / 2;
        return merge(rec(b, lo, mid), rec(b, mid + 1, hi));
    }

    private List<int[]> merge(List<int[]> left, List<int[]> right) {
        List<int[]> res = new ArrayList<>();
        int i = 0, j = 0, h1 = 0, h2 = 0;
        while (i < left.size() && j < right.size()) {
            int x;
            int[] l = left.get(i), r = right.get(j);
            if (l[0] < r[0])      { x = l[0]; h1 = l[1]; i++; }
            else if (l[0] > r[0]) { x = r[0]; h2 = r[1]; j++; }
            else                  { x = l[0]; h1 = l[1]; h2 = r[1]; i++; j++; }
            int maxH = Math.max(h1, h2);
            if (res.isEmpty() || res.get(res.size() - 1)[1] != maxH)
                res.add(new int[]{x, maxH});         // emit only on change
        }
        while (i < left.size())  addIfChanged(res, left.get(i++));
        while (j < right.size()) addIfChanged(res, right.get(j++));
        return res;
    }

    private void addIfChanged(List<int[]> res, int[] p) {
        if (res.isEmpty() || res.get(res.size() - 1)[1] != p[1]) res.add(p);
    }
}
```

**Dry run** on `[[2,9,10],[3,7,15],[5,12,12]]`. Left skyline of building 1 = `[[2,10],[9,0]]`; merging the rest yields key points `[2,10],[3,15],[7,12],[12,0]`. ✓

**Time:** `O(n log n)`. **Space:** `O(n)`.

**Follow-ups.** Solve with a max-heap / `TreeMap` sweep-line instead of D&C and compare; output the silhouette as rectangles; handle buildings with non-integer coordinates; "rectangle area union" as a related sweep-line problem.

---

### Problem 10: Median of Medians — Worst-Case-Linear Selection (Expert)

**Statement.** Find the k-th smallest element (1-indexed) of an unsorted array in **guaranteed** `O(n)` worst-case time.

**Constraints.** `1 ≤ k ≤ n ≤ 10^6`. Sorting (`O(n log n)`) and average-`O(n)` quickselect are both rejected if the interviewer insists on worst-case linear.

**Approach.**
- *Sort.* `O(n log n)`.
- *Quickselect.* Average `O(n)`, but adversarial input degrades to `O(n²)`.
- *Median of medians.* Choose the pivot deterministically: split into groups of 5, take each group's median, recursively select the median of those medians. This pivot guarantees at least 30% of elements are discarded each round → `T(n) ≤ T(n/5) + T(7n/10) + O(n) = O(n)` by Akra–Bazzi.

```java
import java.util.*;

public class MedianOfMedians {

    public int kthSmallest(int[] a, int k) {           // k is 1-indexed
        return select(Arrays.copyOf(a, a.length), 0, a.length - 1, k - 1);
    }

    private int select(int[] a, int lo, int hi, int k) {
        while (true) {
            if (lo == hi) return a[lo];
            int pivot = medianOfMedians(a, lo, hi);
            int p = partition(a, lo, hi, pivot);
            if (k == p) return a[p];
            else if (k < p) hi = p - 1;
            else lo = p + 1;
        }
    }

    private int medianOfMedians(int[] a, int lo, int hi) {
        int n = hi - lo + 1;
        if (n <= 5) return medianValue(a, lo, hi);
        int numMedians = 0;
        for (int i = lo; i <= hi; i += 5) {
            int subHi = Math.min(i + 4, hi);
            int med = medianValue(a, i, subHi);
            swapValue(a, med, lo + numMedians);   // pack medians to the front
            numMedians++;
        }
        return select(a, lo, lo + numMedians - 1, lo + numMedians / 2);
    }

    private int medianValue(int[] a, int lo, int hi) {
        int[] tmp = Arrays.copyOfRange(a, lo, hi + 1);
        Arrays.sort(tmp);
        return tmp[tmp.length / 2];
    }

    // Lomuto partition around a pivot *value*; returns its final index.
    private int partition(int[] a, int lo, int hi, int pivotValue) {
        int pivotIdx = lo;
        for (int i = lo; i <= hi; i++) if (a[i] == pivotValue) { pivotIdx = i; break; }
        swap(a, pivotIdx, hi);
        int store = lo;
        for (int i = lo; i < hi; i++)
            if (a[i] < pivotValue) swap(a, store++, i);
        swap(a, store, hi);
        return store;
    }

    private void swapValue(int[] a, int value, int dest) {
        for (int i = dest; i < a.length; i++)
            if (a[i] == value) { swap(a, i, dest); return; }
    }
    private void swap(int[] a, int i, int j) { int t = a[i]; a[i] = a[j]; a[j] = t; }
}
```

**Dry run** on `a=[7,10,4,3,20,15], k=3`. Sorted view `[3,4,7,10,15,20]` → 3rd smallest is `7`. The deterministic pivot partitions around a near-median so the search converges to `7`. ✓

**Time:** `O(n)` worst case. **Space:** `O(log n)` recursion (plus small group buffers).

**Follow-ups.** Why groups of 5 and not 3 or 7 (group size 3 fails to give a linear recurrence; 5 is the smallest that works, 7 also works but with larger constants); randomized quickselect and its expected-linear proof; finding the true median; "k closest points to origin" via quickselect; the introselect hybrid (quickselect that falls back to median-of-medians on bad luck), which is what C++ `nth_element` uses.

---

### Bonus: Karatsuba Multiplication

**Statement.** Multiply two large non-negative integers represented as strings, faster than schoolbook `O(n²)`.

**Approach.** Split each number into high and low halves: `x = x1·B^m + x0`, `y = y1·B^m + y0`. Naively `xy` needs four sub-products; Karatsuba uses the identity below to need only **three**:

```
z2 = x1·y1
z0 = x0·y0
z1 = (x1+x0)(y1+y0) − z2 − z0   ← the trick: one multiply instead of two
xy = z2·B^(2m) + z1·B^m + z0
```

`T(n) = 3T(n/2) + O(n) = O(n^log₂3) ≈ O(n^1.585)`.

```java
import java.math.BigInteger;

public class Karatsuba {

    public BigInteger multiply(BigInteger x, BigInteger y) {
        int n = Math.max(x.bitLength(), y.bitLength());
        if (n <= 32) return x.multiply(y);          // base case: native multiply
        int m = n / 2;
        BigInteger mask = BigInteger.ONE.shiftLeft(m).subtract(BigInteger.ONE);
        BigInteger x0 = x.and(mask), x1 = x.shiftRight(m);
        BigInteger y0 = y.and(mask), y1 = y.shiftRight(m);

        BigInteger z2 = multiply(x1, y1);
        BigInteger z0 = multiply(x0, y0);
        BigInteger z1 = multiply(x1.add(x0), y1.add(y0)).subtract(z2).subtract(z0);

        return z2.shiftLeft(2 * m).add(z1.shiftLeft(m)).add(z0);
    }
}
```

**Time:** `O(n^1.585)`. **Space:** `O(n)` (recursion + intermediates).

**Follow-ups.** Toom–Cook (Toom-3) generalizes to `O(n^1.465)`; Schönhage–Strassen and Harvey–van der Hoeven `O(n log n)` via FFT; this is the same divide-the-digits idea behind fast polynomial multiplication.

---

## 🧩 Extended Problems — Set 1: Classic Easy → Medium

### Problem 11: Search in Rotated Sorted Array — Modified Binary Search

**Statement.** A sorted ascending array of distinct integers is rotated at an unknown pivot (e.g. `[4,5,6,7,0,1,2]`). Given `target`, return its index, or `-1`.

**Constraints.** `1 ≤ n ≤ 10^4`, all values distinct, `−10^4 ≤ nums[i], target ≤ 10^4`. Required `O(log n)`.

**Approach.** A naive scan is `O(n)`. The key observation: after rotation, when you split at `mid`, **at least one half is still fully sorted**. Compare `nums[lo]` with `nums[mid]` to decide which half is sorted, then check whether `target` falls inside that sorted half's value range — if so recurse there, else recurse in the other half. This keeps the `O(log n)` halving of plain binary search.

```
[4,5,6,7,0,1,2]  lo=0 hi=6 mid=3 (val 7)
 nums[lo]=4 <= nums[mid]=7  → LEFT half [4..7] is sorted
 target=0 not in [4,7]      → search RIGHT half
```

```java
public class SearchRotated {

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
}
```

**Dry run** on `[4,5,6,7,0,1,2]`, `target=0`: left half sorted, 0 not in `[4,7)` → `lo=4`; now `[0,1,2]`, `mid=5` val 1, left half `[0,1]` sorted, 0 in `[0,1)` → `hi=4`; `mid=4` val 0 → returns `4`. ✓

**Complexity.** Time `O(log n)`, space `O(1)`. **Edge cases:** single element; no rotation (already sorted); target absent; target at either boundary. With duplicates allowed (LeetCode 81) the `nums[lo] == nums[mid]` tie forces an `O(n)` worst case — shrink with `lo++` when `nums[lo]==nums[mid]==nums[hi]`.

---

### Problem 12: Find Minimum in Rotated Sorted Array — Binary Search on Order

**Statement.** A sorted ascending array of distinct values is rotated. Return the minimum element in `O(log n)`.

**Constraints.** `1 ≤ n ≤ 5000`, distinct values, `−5000 ≤ nums[i] ≤ 5000`.

**Approach.** The minimum is the unique "rotation point" where the order breaks. Compare `nums[mid]` with `nums[hi]`: if `nums[mid] > nums[hi]` the minimum must lie strictly to the right (`lo = mid + 1`); otherwise the minimum is at `mid` or to its left (`hi = mid`). Comparing against `hi` (not `lo`) avoids the ambiguity of the already-sorted case.

```java
public class FindMinRotated {

    public int findMin(int[] nums) {
        int lo = 0, hi = nums.length - 1;
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (nums[mid] > nums[hi]) lo = mid + 1;   // min is to the right
            else hi = mid;                            // min is mid or left
        }
        return nums[lo];                              // lo == hi == min index
    }
}
```

**Dry run** on `[4,5,6,7,0,1,2]`: `mid=3` val 7 > `nums[6]`=2 → `lo=4`; `mid=5` val 1 ≤ `nums[6]`=2 → `hi=5`; `mid=4` val 0 ≤ `nums[5]`=1 → `hi=4`; `lo==hi==4` → returns `0`. ✓

**Complexity.** Time `O(log n)`, space `O(1)`. **Edge cases:** no rotation returns `nums[0]`; single element; two elements. For LeetCode 154 (duplicates) add an `else hi--` branch when `nums[mid] == nums[hi]`, degrading worst case to `O(n)`.

---

### Problem 13: Search a 2D Matrix — Binary Search on a Flattened Grid

**Statement.** An `m×n` matrix has each row sorted ascending and each row's first integer greater than the previous row's last. Return whether `target` exists.

**Constraints.** `1 ≤ m, n ≤ 100`, `−10^4 ≤ matrix[i][j], target ≤ 10^4`.

**Approach.** Because rows concatenate into one globally sorted sequence, treat the grid as a virtual sorted array of length `m*n` and binary-search it. Map a flat index `mid` back to `(mid / n, mid % n)`. This is a single `O(log(m·n))` search rather than per-row searching.

```java
public class Search2DMatrix {

    public boolean searchMatrix(int[][] matrix, int target) {
        int m = matrix.length, n = matrix[0].length;
        int lo = 0, hi = m * n - 1;
        while (lo <= hi) {
            int mid = lo + (hi - lo) / 2;
            int val = matrix[mid / n][mid % n];      // unflatten
            if (val == target) return true;
            if (val < target) lo = mid + 1;
            else hi = mid - 1;
        }
        return false;
    }
}
```

**Dry run** on `[[1,3,5],[7,9,11]]`, `target=9`: `n=3`, `lo=0 hi=5 mid=2` → `matrix[0][2]=5 < 9` → `lo=3`; `mid=4` → `matrix[1][1]=9` → `true`. ✓

**Complexity.** Time `O(log(m·n))`, space `O(1)`. **Edge cases:** empty matrix / empty row (guard `matrix.length`); single cell; target smaller than first or larger than last element. Note LeetCode 240 ("each row and column sorted" but rows do not chain) needs the staircase `O(m+n)` walk instead.

---

### Problem 14: Sqrt(x) — Integer Square Root via Binary Search

**Statement.** Given a non-negative integer `x`, return `floor(sqrt(x))` without using built-in `sqrt`.

**Constraints.** `0 ≤ x ≤ 2^31 − 1`.

**Approach.** The answer is monotonic: `mid*mid ≤ x` is true for small `mid` and flips to false — a classic binary-search-on-answer over `[0, x]`. Use `long` for `mid*mid` to avoid `int` overflow near `2^31`. Keep the last `mid` that satisfied the predicate.

```java
public class IntegerSqrt {

    public int mySqrt(int x) {
        if (x < 2) return x;                       // 0 -> 0, 1 -> 1
        long lo = 1, hi = x, ans = 1;
        while (lo <= hi) {
            long mid = lo + (hi - lo) / 2;
            if (mid * mid <= x) { ans = mid; lo = mid + 1; }  // candidate, go higher
            else hi = mid - 1;
        }
        return (int) ans;
    }
}
```

**Dry run** on `x=8`: `lo=1 hi=8 mid=4` → 16>8 → `hi=3`; `mid=2` → 4≤8 → `ans=2,lo=3`; `mid=3` → 9>8 → `hi=2`; loop ends → returns `2`. ✓ (floor of √8 = 2.82).

**Complexity.** Time `O(log x)`, space `O(1)`. **Edge cases:** `x = 0` and `x = 1` (early return); perfect squares; the overflow guard via `long`. A Newton-iteration variant also runs in `O(log x)` with faster constants.

---

### Problem 15: Sort an Array — Merge Sort with O(n log n) Guarantee

**Statement.** Sort an integer array ascending. Required worst-case `O(n log n)`; no library sort.

**Constraints.** `1 ≤ n ≤ 5·10^4`, `−5·10^4 ≤ nums[i] ≤ 5·10^4`.

**Approach.** This is the canonical "implement an `O(n log n)` sort" prompt. Bottom-up merge sort avoids recursion overhead and stack risk: start with runs of width 1 and repeatedly merge adjacent runs of doubling width using a reusable auxiliary buffer. Stable and worst-case `O(n log n)`, unlike quicksort which can hit `O(n²)`.

```
width 1: [3][1][4][1][5]   merge pairs ->
width 2: [1 3][1 4][5]      merge pairs ->
width 4: [1 1 3 4][5]       merge ->
final:   [1 1 3 4 5]
```

```java
public class SortArrayMerge {

    public int[] sortArray(int[] nums) {
        int n = nums.length;
        int[] aux = new int[n];
        for (int width = 1; width < n; width *= 2) {
            for (int lo = 0; lo < n; lo += 2 * width) {
                int mid = Math.min(lo + width, n);
                int hi  = Math.min(lo + 2 * width, n);
                merge(nums, aux, lo, mid, hi);
            }
        }
        return nums;
    }

    private void merge(int[] a, int[] aux, int lo, int mid, int hi) {
        int i = lo, j = mid, k = lo;
        while (i < mid && j < hi) aux[k++] = (a[i] <= a[j]) ? a[i++] : a[j++];
        while (i < mid) aux[k++] = a[i++];
        while (j < hi)  aux[k++] = a[j++];
        System.arraycopy(aux, lo, a, lo, hi - lo);
    }
}
```

**Dry run** on `[3,1,4,1,5]`: width 1 → `[1,3][1,4][5]`; width 2 → `[1,1,3,4][5]`; width 4 → `[1,1,3,4,5]`. ✓

**Complexity.** Time `Θ(n log n)` best/avg/worst, space `O(n)` for `aux`. **Edge cases:** length 0 or 1 (the outer loop never runs); the `Math.min` clamps for the odd, ragged last run; negative values handled naturally.

---

### Problem 16: Kth Largest Element — Quickselect (Average Linear)

**Statement.** Return the k-th largest element in an unsorted array (the element at sorted position `n-k`).

**Constraints.** `1 ≤ k ≤ n ≤ 10^5`, `−10^4 ≤ nums[i] ≤ 10^4`.

**Approach.** Sorting is `O(n log n)`; a min-heap of size `k` is `O(n log k)`. Quickselect is the D&C optimum: partition around a **randomized** pivot (randomization defeats the sorted-input `O(n²)` adversary), and recurse into only the side containing rank `n-k`. Each step discards a partition, giving expected `T(n)=T(n/2)+O(n)=O(n)`.

```java
import java.util.Random;

public class KthLargestQuickselect {
    private final Random rnd = new Random();

    public int findKthLargest(int[] nums, int k) {
        int target = nums.length - k;                 // index in ascending order
        int lo = 0, hi = nums.length - 1;
        while (lo < hi) {
            int p = partition(nums, lo, hi);
            if (p == target) return nums[p];
            else if (p < target) lo = p + 1;
            else hi = p - 1;
        }
        return nums[lo];
    }

    private int partition(int[] a, int lo, int hi) {
        swap(a, lo + rnd.nextInt(hi - lo + 1), hi);   // random pivot to position hi
        int pivot = a[hi], store = lo;
        for (int i = lo; i < hi; i++)
            if (a[i] < pivot) swap(a, store++, i);
        swap(a, store, hi);                           // pivot into final slot
        return store;
    }

    private void swap(int[] a, int i, int j) { int t = a[i]; a[i] = a[j]; a[j] = t; }
}
```

**Dry run** on `[3,2,1,5,6,4]`, `k=2`: target index `= 6-2 = 4`. Quickselect partitions until the element landing at index 4 is fixed → `5`, the 2nd largest. ✓

**Complexity.** Time expected `O(n)`, worst `O(n²)` (mitigated by randomization), space `O(1)` extra (iterative). **Edge cases:** `k = 1` (max) and `k = n` (min); duplicates handled since equal elements collapse around the pivot; single-element array.

---

### Problem 17: Different Ways to Add Parentheses — Recursive Split

**Statement.** Given an expression of integers and `+ - *`, return all possible results from grouping numbers and operators with different parenthesizations.

**Constraints.** `1 ≤ expr.length ≤ 20`, results fit in `int`, at most ~19 operators.

**Approach.** This is textbook divide & conquer over operator positions. For each operator, split the string into a left and right sub-expression, recursively compute every value each side can produce, then combine each left value with each right value under that operator. A string with no operator is a single-number base case. (Memoizing on substrings turns repeated work into DP, but the pure D&C form is what interviewers ask for.)

```
"2*3-4*5"
 split at '*' -> left "2"        right "3-4*5"
 split at '-' -> left "2*3"      right "4*5"
 split at '*' -> left "2*3-4"    right "5"
 ... combine all left x right value pairs per operator
```

```java
import java.util.*;

public class DiffWaysToCompute {

    public List<Integer> diffWaysToCompute(String expr) {
        List<Integer> res = new ArrayList<>();
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '+' || c == '-' || c == '*') {
                List<Integer> left  = diffWaysToCompute(expr.substring(0, i));
                List<Integer> right = diffWaysToCompute(expr.substring(i + 1));
                for (int a : left)
                    for (int b : right) {
                        if (c == '+') res.add(a + b);
                        else if (c == '-') res.add(a - b);
                        else res.add(a * b);
                    }
            }
        }
        if (res.isEmpty()) res.add(Integer.parseInt(expr));   // pure number base case
        return res;
    }
}
```

**Dry run** on `"2-1-1"`: groupings `(2-(1-1))=2` and `((2-1)-1)=0` → `[2, 0]` (order may vary). ✓

**Complexity.** Time/space follow the Catalan number of parenthesizations, roughly `O(4^n / n^1.5)` results for `n` operators; memoization caps repeated substring work. **Edge cases:** single number (no operator); negative intermediate values; multi-digit numbers (`substring`/`parseInt` handle them).

---

### Problem 18: Convert Sorted Array to Balanced BST — Midpoint Recursion

**Statement.** Given an ascending-sorted array, build a height-balanced binary search tree.

**Constraints.** `1 ≤ n ≤ 10^4`, strictly increasing values, `−10^4 ≤ nums[i] ≤ 10^4`.

**Approach.** Choosing the middle element as the root keeps the two subtrees' sizes within one of each other, guaranteeing balance. Recurse on the left half for the left subtree and the right half for the right subtree — the array's sortedness gives the BST property for free. This is the inverse of binary search: instead of discarding a half, you make each half a subtree.

```java
public class SortedArrayToBST {

    public static class TreeNode {
        int val; TreeNode left, right;
        TreeNode(int v) { val = v; }
    }

    public TreeNode sortedArrayToBST(int[] nums) {
        return build(nums, 0, nums.length - 1);
    }

    private TreeNode build(int[] a, int lo, int hi) {
        if (lo > hi) return null;
        int mid = lo + (hi - lo) / 2;                // middle keeps it balanced
        TreeNode root = new TreeNode(a[mid]);
        root.left  = build(a, lo, mid - 1);
        root.right = build(a, mid + 1, hi);
        return root;
    }
}
```

**Dry run** on `[-10,-3,0,5,9]`: root `0` (mid), left subtree from `[-10,-3]` (root `-10`, right child `-3`), right subtree from `[5,9]` (root `5`, right child `9`). Height = 2, balanced. ✓

**Complexity.** Time `O(n)` (each element becomes exactly one node), space `O(log n)` recursion (output tree itself is `O(n)`). **Edge cases:** empty array → `null`; single element → leaf; even length picks the lower middle (any valid balanced tree is accepted).

---

### Problem 19: Maximum Depth of Binary Tree — Recursive Combine

**Statement.** Return the maximum depth (number of nodes along the longest root-to-leaf path) of a binary tree.

**Constraints.** `0 ≤ nodes ≤ 10^4`, `−100 ≤ val ≤ 100`.

**Approach.** The smallest, cleanest divide & conquer on trees: the depth of a node is `1 + max(depth(left), depth(right))`. Solve both subtrees recursively (conquer) and combine with `max`. The empty tree is the base case at depth 0.

```java
public class MaxDepth {

    public static class TreeNode {
        int val; TreeNode left, right;
        TreeNode(int v) { val = v; }
    }

    public int maxDepth(TreeNode root) {
        if (root == null) return 0;                          // base case
        int left  = maxDepth(root.left);
        int right = maxDepth(root.right);
        return 1 + Math.max(left, right);                    // combine
    }
}
```

**Dry run** on `3 -> (9), (20 -> 15,7)`: depth(9)=1, depth(15)=depth(7)=1 → depth(20)=2 → root = `1 + max(1,2) = 3`. ✓

**Complexity.** Time `O(n)` (visits each node once), space `O(h)` recursion where `h` is the tree height (`O(n)` worst case for a skewed tree, `O(log n)` if balanced). **Edge cases:** empty tree → 0; single node → 1; degenerate linked-list-shaped tree risks deep recursion.

---

### Problem 20: Balanced Binary Tree — Height Check with Early Exit

**Statement.** Determine whether a binary tree is height-balanced: for every node, the heights of its two subtrees differ by at most 1.

**Constraints.** `0 ≤ nodes ≤ 5000`, `−10^4 ≤ val ≤ 10^4`.

**Approach.** A naive top-down check recomputes heights repeatedly → `O(n²)`. The D&C optimum computes height bottom-up and signals imbalance through a sentinel: a helper returns the subtree height, or `-1` the moment any subtree is unbalanced. Once `-1` propagates up, recursion short-circuits. Single post-order pass → `O(n)`.

```java
public class BalancedTree {

    public static class TreeNode {
        int val; TreeNode left, right;
        TreeNode(int v) { val = v; }
    }

    public boolean isBalanced(TreeNode root) {
        return height(root) != -1;
    }

    private int height(TreeNode node) {
        if (node == null) return 0;
        int left = height(node.left);
        if (left == -1) return -1;                       // left already unbalanced
        int right = height(node.right);
        if (right == -1) return -1;                      // right already unbalanced
        if (Math.abs(left - right) > 1) return -1;       // this node unbalanced
        return 1 + Math.max(left, right);
    }
}
```

**Dry run** on `1 -> (2 -> (3->(4),4),3)` (a left-heavy tree): the deep left chain makes some node's subtree heights differ by 2 → height returns `-1` → `isBalanced` false. For `[3,9,20,null,null,15,7]` heights differ by ≤1 everywhere → true. ✓

**Complexity.** Time `O(n)` (each node visited once thanks to bottom-up reuse), space `O(h)` recursion. **Edge cases:** empty tree is balanced (`height` 0); single node balanced; the early `-1` exit avoids the naive `O(n²)` recomputation.

---

### Problem 21: Construct Binary Tree from Preorder and Inorder Traversal

**Statement.** Given `preorder` and `inorder` traversals of a tree with unique values, reconstruct the tree.

**Constraints.** `1 ≤ n ≤ 3000`, values unique, both arrays are valid permutations of the same node set.

**Approach.** The first element of `preorder` is always the root. Locating it in `inorder` splits that array into the left subtree (everything before) and right subtree (everything after), whose sizes tell you how to slice `preorder`. Recurse on each side — pure divide & conquer. A `HashMap` from value to inorder index makes the split `O(1)`, giving overall `O(n)`.

```
preorder: [3 | 9 | 20 15 7]      root = 3
inorder:  [9 | 3 | 15 20 7]      left = {9}, right = {15,20,7}
```

```java
import java.util.*;

public class BuildTreePreIn {

    public static class TreeNode {
        int val; TreeNode left, right;
        TreeNode(int v) { val = v; }
    }

    private Map<Integer, Integer> idx;       // value -> index in inorder
    private int pre;                          // running preorder cursor

    public TreeNode buildTree(int[] preorder, int[] inorder) {
        idx = new HashMap<>();
        for (int i = 0; i < inorder.length; i++) idx.put(inorder[i], i);
        pre = 0;
        return build(preorder, 0, inorder.length - 1);
    }

    private TreeNode build(int[] preorder, int inLo, int inHi) {
        if (inLo > inHi) return null;
        int rootVal = preorder[pre++];
        TreeNode root = new TreeNode(rootVal);
        int mid = idx.get(rootVal);                       // split point in inorder
        root.left  = build(preorder, inLo, mid - 1);      // build left first (preorder order)
        root.right = build(preorder, mid + 1, inHi);
        return root;
    }
}
```

**Dry run** on `preorder=[3,9,20,15,7]`, `inorder=[9,3,15,20,7]`: root 3, inorder split `[9]`/`[15,20,7]`; left subtree = node 9; right subtree root 20 with children 15 and 7. ✓

**Complexity.** Time `O(n)` (each node built once, `O(1)` index lookups), space `O(n)` for the map plus `O(h)` recursion. **Edge cases:** single node; left- or right-skewed trees (one side of every split is empty); the preorder cursor must advance left-then-right to stay in sync.

---

### Problem 22: Merge k Sorted Lists — Divide & Conquer Pairwise Merge

**Statement.** Merge `k` sorted linked lists into one sorted list.

**Constraints.** `0 ≤ k ≤ 10^4`, total nodes `≤ 10^5`, each list sorted ascending.

**Approach.** Merging lists one-by-one into an accumulator is `O(k·N)`. The D&C optimum pairs up lists and merges halves recursively — exactly merge sort's merge tree over the *lists*. With `log k` levels and `O(N)` work merging at each level (every node touched once per level), total cost is `O(N log k)`, matching the min-heap approach but with simpler constants.

```
[L0 L1 L2 L3 L4]
 merge(L0,L1) merge(L2,L3) [L4]   ->  3 lists
 merge(.., ..)            [L4]    ->  2 lists
 merge(.., L4)                    ->  1 list   (log k levels)
```

```java
public class MergeKLists {

    public static class ListNode {
        int val; ListNode next;
        ListNode(int v) { val = v; }
    }

    public ListNode mergeKLists(ListNode[] lists) {
        if (lists == null || lists.length == 0) return null;
        return merge(lists, 0, lists.length - 1);
    }

    private ListNode merge(ListNode[] lists, int lo, int hi) {
        if (lo == hi) return lists[lo];
        int mid = lo + (hi - lo) / 2;
        ListNode left  = merge(lists, lo, mid);
        ListNode right = merge(lists, mid + 1, hi);
        return mergeTwo(left, right);
    }

    private ListNode mergeTwo(ListNode a, ListNode b) {
        ListNode dummy = new ListNode(0), tail = dummy;
        while (a != null && b != null) {
            if (a.val <= b.val) { tail.next = a; a = a.next; }
            else                { tail.next = b; b = b.next; }
            tail = tail.next;
        }
        tail.next = (a != null) ? a : b;          // attach the remainder
        return dummy.next;
    }
}
```

**Dry run** on lists `[1->4->5], [1->3->4], [2->6]`: merge first two → `1->1->3->4->4->5`, merge with `2->6` → `1->1->2->3->4->4->5->6`. ✓

**Complexity.** Time `O(N log k)` where `N` is total nodes, space `O(log k)` recursion (the merge itself is iterative, `O(1)`). **Edge cases:** empty array → `null`; some lists `null` (handled by `mergeTwo`); a single list returned as-is; `k = 0`.

---

## 🧩 Extended Problems — Set 2: Medium → Hard Variations & Follow-ups

### Problem 23: Median of Two Sorted Arrays — Binary Search on the Partition

**Statement.** Given two sorted arrays `nums1` and `nums2` of sizes `m` and `n`, return the median of the combined sorted array in `O(log(m+n))` time.

**Constraints.** `0 ≤ m, n ≤ 1000`, `1 ≤ m + n ≤ 2000`, values fit in `int`.

**Approach.**
- *Brute force.* Merge both arrays (`O(m+n)`) and index the middle — simple but too slow for the required bound.
- *Optimal.* Binary-search a **partition** of the smaller array. Choose `i` elements from `nums1` and `j = half - i` from `nums2` so the left side holds exactly half the elements. A partition is valid when `maxLeft1 ≤ minRight2` and `maxLeft2 ≤ minRight1`. Searching `i` over the *smaller* array bounds the work to `O(log(min(m,n)))`. Use `±∞` sentinels for the empty edges so boundary cases need no special handling.

```
nums1: [1 3 | 8 9 15]      left side = i elements of nums1 + j of nums2
nums2: [7 | 11 18 19 21]   want maxLeft <= minRight on both sides
         ^ move i left/right by binary search until balanced
```

```java
public class MedianTwoSorted {

    public double findMedianSortedArrays(int[] a, int[] b) {
        if (a.length > b.length) return findMedianSortedArrays(b, a); // search smaller
        int m = a.length, n = b.length, half = (m + n + 1) / 2;
        int lo = 0, hi = m;
        while (lo <= hi) {
            int i = lo + (hi - lo) / 2;       // take i from a
            int j = half - i;                 // take j from b
            int aLeft  = (i == 0) ? Integer.MIN_VALUE : a[i - 1];
            int aRight = (i == m) ? Integer.MAX_VALUE : a[i];
            int bLeft  = (j == 0) ? Integer.MIN_VALUE : b[j - 1];
            int bRight = (j == n) ? Integer.MAX_VALUE : b[j];
            if (aLeft <= bRight && bLeft <= aRight) {           // valid partition
                int maxLeft = Math.max(aLeft, bLeft);
                if (((m + n) & 1) == 1) return maxLeft;         // odd total
                int minRight = Math.min(aRight, bRight);
                return (maxLeft + minRight) / 2.0;              // even total
            } else if (aLeft > bRight) hi = i - 1;              // too many from a
            else lo = i + 1;                                    // too few from a
        }
        throw new IllegalArgumentException("inputs not sorted");
    }
}
```

**Dry run** on `a=[1,3]`, `b=[2]` (odd total 3, `half=2`): try `i=1,j=1` → `aLeft=1,aRight=3,bLeft=2,bRight=∞`; `1≤∞` and `2≤3` valid → `maxLeft=max(1,2)=2`. ✓

**Complexity.** Time `O(log(min(m,n)))`, space `O(1)`. **Edge cases:** one array empty (`MIN/MAX` sentinels cover it); even vs odd total length; all of one array smaller than the other.

---

### Problem 24: Reverse Pairs (LeetCode 493) — Merge Sort with a Pre-Merge Count

**Statement.** Count pairs `(i, j)` with `i < j` and `nums[i] > 2·nums[j]`.

**Constraints.** `1 ≤ n ≤ 5·10^4`, `−2^31 ≤ nums[i] ≤ 2^31 − 1`; use `long` for the comparison to avoid overflow.

**Approach.**
- *Brute force.* All pairs, `O(n²)`.
- *Optimal.* This is the count-inversions pattern with a twist: the `> 2·nums[j]` relation is **not** the same as the merge's ordering relation, so you cannot count during the standard merge. Instead, before merging the two already-sorted halves, run a separate two-pointer pass that counts, for each left element, how many right elements satisfy `left[i] > 2·right[j]`. Because both halves are sorted, that pointer only moves forward → `O(n)` per merge. Then merge normally.

```
left  (sorted): [ ... a_i ... ]   advance j while  a_i > 2*b_j
right (sorted): [ ... b_j ... ]   add (j - midStart) to the count
```

```java
public class ReversePairs {

    public int reversePairs(int[] nums) {
        int[] aux = new int[nums.length];
        return (int) sort(nums, aux, 0, nums.length - 1);
    }

    private long sort(int[] a, int[] aux, int lo, int hi) {
        if (lo >= hi) return 0;
        int mid = lo + (hi - lo) / 2;
        long count = sort(a, aux, lo, mid) + sort(a, aux, mid + 1, hi);

        // Count cross pairs BEFORE merging (both halves sorted).
        int j = mid + 1;
        for (int i = lo; i <= mid; i++) {
            while (j <= hi && (long) a[i] > 2L * a[j]) j++;
            count += (j - (mid + 1));
        }

        merge(a, aux, lo, mid, hi);
        return count;
    }

    private void merge(int[] a, int[] aux, int lo, int mid, int hi) {
        System.arraycopy(a, lo, aux, lo, hi - lo + 1);
        int i = lo, j = mid + 1;
        for (int k = lo; k <= hi; k++) {
            if (i > mid)               a[k] = aux[j++];
            else if (j > hi)           a[k] = aux[i++];
            else if (aux[i] <= aux[j]) a[k] = aux[i++];
            else                       a[k] = aux[j++];
        }
    }
}
```

**Dry run** on `[1,3,2,3,1]`: reverse pairs are `(3,1)` at indices `(1,4)` and `(3,1)` at `(3,4)` → `2`. The pre-merge scan in each level catches exactly these. ✓

**Complexity.** Time `O(n log n)` (the counting pointer is monotonic, so it adds `O(n)` per level), space `O(n)`. **Edge cases:** `2L * a[j]` overflow if done in `int` (cast to `long`); negative values; all-equal arrays (zero pairs).

---

### Problem 25: Count of Smaller Numbers After Self — Merge Sort with Index Tracking

**Statement.** For each `nums[i]`, count how many elements to its right are strictly smaller. Return the counts array.

**Constraints.** `1 ≤ n ≤ 10^5`, `−10^4 ≤ nums[i] ≤ 10^4`.

**Approach.**
- *Brute force.* For each `i`, scan the suffix, `O(n²)`.
- *Optimal.* A per-element inversion count. Sort **indices** (not values) by their values with merge sort. When merging, an index from the right half placed ahead of remaining left-half indices means those left indices each saw one smaller element on their right; equivalently, when we take a left index, the number of right-half elements *already emitted* (hence smaller) is added to that index's count.

```java
import java.util.*;

public class CountSmallerAfterSelf {

    public List<Integer> countSmaller(int[] nums) {
        int n = nums.length;
        int[] counts = new int[n];
        int[] idx = new int[n], aux = new int[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        sort(nums, idx, aux, counts, 0, n - 1);
        List<Integer> res = new ArrayList<>(n);
        for (int c : counts) res.add(c);
        return res;
    }

    private void sort(int[] nums, int[] idx, int[] aux, int[] counts, int lo, int hi) {
        if (lo >= hi) return;
        int mid = lo + (hi - lo) / 2;
        sort(nums, idx, aux, counts, lo, mid);
        sort(nums, idx, aux, counts, mid + 1, hi);

        int i = lo, j = mid + 1, k = lo, rightCount = 0;
        while (i <= mid && j <= hi) {
            if (nums[idx[j]] < nums[idx[i]]) {     // right element is smaller
                rightCount++;                       // it sits before idx[i]
                aux[k++] = idx[j++];
            } else {
                counts[idx[i]] += rightCount;       // all such right elems counted
                aux[k++] = idx[i++];
            }
        }
        while (i <= mid) { counts[idx[i]] += rightCount; aux[k++] = idx[i++]; }
        while (j <= hi)  aux[k++] = idx[j++];
        System.arraycopy(aux, lo, idx, lo, hi - lo + 1);
    }
}
```

**Dry run** on `[5,2,6,1]`: answers are `[2,1,1,0]` (5 has {2,1} smaller right; 2 has {1}; 6 has {1}; 1 has none). Merge tracking reproduces this. ✓

**Complexity.** Time `O(n log n)`, space `O(n)`. **Edge cases:** strictly-smaller (use `<`, not `<=`, so equal values are not counted); duplicates; single element returns `[0]`. A Fenwick/BIT over coordinate-compressed values is an alternative `O(n log n)`.

---

### Problem 26: Count of Range Sum (LeetCode 327) — Merge Sort over Prefix Sums

**Statement.** Count the number of range sums `S(i,j) = nums[i] + … + nums[j]` (`i ≤ j`) that lie in `[lower, upper]` inclusive.

**Constraints.** `1 ≤ n ≤ 10^5`, `−2^31 ≤ nums[i] ≤ 2^31 − 1`, `−10^5 ≤ lower ≤ upper ≤ 10^5`. Prefix sums need `long`.

**Approach.**
- *Brute force.* All `O(n²)` ranges.
- *Optimal.* Let `P[k]` be prefix sums (`P[0]=0`). A range sum `S(i,j) = P[j+1] − P[i]` lies in `[lower, upper]` iff `lower ≤ P[j+1] − P[i] ≤ upper`. Sort prefix sums with merge sort; while merging two sorted halves, for each left index `i` count how many right-half values `P[j]` satisfy `P[j] − P[i] ∈ [lower, upper]` using two monotonic pointers. This counts cross-pairs (`i` in left half, `j` in right half, `i < j`) in `O(n)` per level.

```java
public class CountRangeSum {

    public int countRangeSum(int[] nums, int lower, int upper) {
        int n = nums.length;
        long[] pre = new long[n + 1];
        for (int i = 0; i < n; i++) pre[i + 1] = pre[i] + nums[i];
        long[] aux = new long[n + 1];
        return sort(pre, aux, 0, n, lower, upper);
    }

    private int sort(long[] p, long[] aux, int lo, int hi, int lower, int upper) {
        if (lo >= hi) return 0;
        int mid = lo + (hi - lo) / 2;
        int count = sort(p, aux, lo, mid, lower, upper)
                  + sort(p, aux, mid + 1, hi, lower, upper);

        int low = mid + 1, high = mid + 1;            // [low, high) of valid P[j]
        for (int i = lo; i <= mid; i++) {
            while (low <= hi && p[low] - p[i] < lower) low++;
            while (high <= hi && p[high] - p[i] <= upper) high++;
            count += high - low;
        }

        // merge the two sorted halves of p into aux
        int i = lo, j = mid + 1, k = lo;
        while (i <= mid && j <= hi) aux[k++] = (p[i] <= p[j]) ? p[i++] : p[j++];
        while (i <= mid) aux[k++] = p[i++];
        while (j <= hi)  aux[k++] = p[j++];
        System.arraycopy(aux, lo, p, lo, hi - lo + 1);
        return count;
    }
}
```

**Dry run** on `nums=[-2,5,-1]`, `lower=-2, upper=2`: valid range sums are `[-2,-2]=-2`, `[0,2]=2`, `[2,2]=-1` → `3`. The merge counting reproduces 3. ✓

**Complexity.** Time `O(n log n)`, space `O(n)`. **Edge cases:** prefix sum overflow (use `long`); `lower == upper`; all-negative arrays; the two pointers `low`/`high` are monotonic across each left scan, preserving linearity.

---

### Problem 27: Search in Rotated Sorted Array II (with Duplicates) — Binary Search Degradation

**Statement.** A sorted ascending array that **may contain duplicates** is rotated at an unknown pivot. Return whether `target` exists.

**Constraints.** `1 ≤ n ≤ 5·10^4`, `−10^4 ≤ nums[i], target ≤ 10^4`.

**Approach.** With distinct values (Problem 11) you decide which half is sorted by comparing `nums[lo]` and `nums[mid]`. Duplicates break this: when `nums[lo] == nums[mid] == nums[hi]` you cannot tell which side is sorted. The fix is to shrink the window by one on each end (`lo++, hi--`) in that ambiguous case, then proceed as before. This is the canonical "follow-up" that degrades the worst case from `O(log n)` to `O(n)` (e.g. `[2,2,2,2,3,2,2]`).

```java
public class SearchRotatedII {

    public boolean search(int[] nums, int target) {
        int lo = 0, hi = nums.length - 1;
        while (lo <= hi) {
            int mid = lo + (hi - lo) / 2;
            if (nums[mid] == target) return true;
            if (nums[lo] == nums[mid] && nums[mid] == nums[hi]) {
                lo++; hi--;                              // ambiguous: shrink both ends
            } else if (nums[lo] <= nums[mid]) {          // left half sorted
                if (nums[lo] <= target && target < nums[mid]) hi = mid - 1;
                else lo = mid + 1;
            } else {                                     // right half sorted
                if (nums[mid] < target && target <= nums[hi]) lo = mid + 1;
                else hi = mid - 1;
            }
        }
        return false;
    }
}
```

**Dry run** on `[2,5,6,0,0,1,2]`, `target=0`: `mid=3` val 0 → returns `true`. On `[1,0,1,1,1]`, `target=0`: `nums[lo]=nums[mid]=nums[hi]=1` → shrink to `[0,1,1]`, then find 0. ✓

**Complexity.** Time `O(log n)` average, `O(n)` worst (many duplicates), space `O(1)`. **Edge cases:** all elements equal; target absent; the shrink branch must come first so the sorted-half logic never sees the ambiguous case.

---

### Problem 28: Find K-th Smallest Pair Distance — Binary Search on the Answer

**Statement.** Given an integer array, return the k-th smallest absolute difference `|nums[i] − nums[j]|` over all pairs `i < j`.

**Constraints.** `2 ≤ n ≤ 10^4`, `0 ≤ nums[i] ≤ 10^6`, `1 ≤ k ≤ n(n−1)/2`.

**Approach.**
- *Brute force.* Generate all `O(n²)` distances, sort, index `k` — `O(n² log n)`, too slow.
- *Optimal (parametric binary search).* The answer lies in `[0, max − min]`. For a candidate distance `d`, the count of pairs with distance `≤ d` is **monotonic** in `d`. Sort the array, then count those pairs in `O(n)` with a sliding window (for each right end, advance the left end while the window width exceeds `d`). Binary-search the smallest `d` whose count is `≥ k`. This "binary search on the answer" pattern is a D&C cousin: each step halves the value range.

```
sorted: [1 1 3 ...]   for distance d, count pairs with gap <= d
         lo......hi    advance lo while nums[hi]-nums[lo] > d
         count += hi - lo  for each hi
```

```java
import java.util.Arrays;

public class KthSmallestPairDistance {

    public int smallestDistancePair(int[] nums, int k) {
        Arrays.sort(nums);
        int lo = 0, hi = nums[nums.length - 1] - nums[0];
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (countPairsAtMost(nums, mid) >= k) hi = mid;   // enough pairs, shrink d
            else lo = mid + 1;
        }
        return lo;
    }

    // Pairs with distance <= d, counted via sliding window in O(n).
    private int countPairsAtMost(int[] nums, int d) {
        int count = 0, left = 0;
        for (int right = 0; right < nums.length; right++) {
            while (nums[right] - nums[left] > d) left++;
            count += right - left;                            // pairs ending at right
        }
        return count;
    }
}
```

**Dry run** on `[1,3,1]`, `k=1`: sorted `[1,1,3]`, distances `{0,2,2}` → 1st smallest is `0`. Binary search converges to `d=0` since `countPairsAtMost(0)=1≥1`. ✓

**Complexity.** Time `O(n log n + n log(maxDist))`, space `O(1)` beyond sorting. **Edge cases:** duplicate values give distance 0; `k=1` (minimum gap); the window pointer `left` never moves backward, keeping the count linear.

---

### Problem 29: Split Array Largest Sum — Minimize the Maximum (Binary Search on Answer)

**Statement.** Split `nums` into `m` non-empty contiguous subarrays so the largest subarray sum is minimized; return that minimized largest sum.

**Constraints.** `1 ≤ m ≤ n ≤ 1000`, `0 ≤ nums[i] ≤ 10^6`.

**Approach.**
- *DP.* `O(n²·m)` — correct but slow.
- *Optimal (binary search on answer).* The feasible answer ranges from `max(nums)` (no subarray can be smaller than its largest single element) to `sum(nums)` (one part). For a candidate cap `x`, greedily count how many subarrays are needed so none exceeds `x` — this is monotonic: a larger `x` needs fewer parts. Binary-search the smallest `x` that needs `≤ m` parts. The greedy feasibility check is `O(n)`, so the total is `O(n log(sum))`.

```java
public class SplitArrayLargestSum {

    public int splitArray(int[] nums, int m) {
        long lo = 0, hi = 0;
        for (int x : nums) { lo = Math.max(lo, x); hi += x; }   // [max, sum]
        while (lo < hi) {
            long mid = lo + (hi - lo) / 2;
            if (partsNeeded(nums, mid) <= m) hi = mid;          // feasible, try smaller
            else lo = mid + 1;
        }
        return (int) lo;
    }

    // Minimum subarrays so each sum <= cap.
    private int partsNeeded(int[] nums, long cap) {
        int parts = 1;
        long cur = 0;
        for (int x : nums) {
            if (cur + x > cap) { parts++; cur = x; }            // start a new part
            else cur += x;
        }
        return parts;
    }
}
```

**Dry run** on `nums=[7,2,5,10,8]`, `m=2`: answer `18` (`[7,2,5]=14` and `[10,8]=18`). Binary search over `[10,32]` converges to `18` as the smallest cap needing `≤2` parts. ✓

**Complexity.** Time `O(n log(sum − max))`, space `O(1)`. **Edge cases:** `m=1` returns the full sum; `m=n` returns `max(nums)`; zeros in the array; `lo` initialized to `max` so a single huge element is respected. This is the same template as "Koko eating bananas" and "capacity to ship packages within D days".

---

### Problem 30: The Skyline Problem — Heap Sweep-Line vs Divide & Conquer

**Statement.** Buildings `[left, right, height]`; produce skyline key points `[x, height]` left to right (follow-up to the D&C Problem 9: solve it with a sweep line and compare).

**Constraints.** `0 ≤ buildings ≤ 10^4`; coordinates up to `2^31 − 1`.

**Approach.** Problem 9 merged skylines like merge sort (`O(n log n)`, `O(n)` space, elegant recursion). The **sweep-line** alternative processes critical x-events left to right, maintaining a multiset of "active" heights in a `TreeMap` (value → count). At each x where buildings start or end, update the active set and emit a key point whenever the current max height changes. Both are `O(n log n)`; the sweep line is often easier to reason about for the "rectangle union" family, while D&C generalizes to parallelization. Knowing both, and when to pick each, is the interview payoff.

```
events sorted by x:  (start lowers nothing, raises active set)
  start L: insert height   end R: remove height
  emit [x, max(active)] whenever max changes
```

```java
import java.util.*;

public class SkylineSweep {

    public List<List<Integer>> getSkyline(int[][] buildings) {
        List<int[]> events = new ArrayList<>();
        for (int[] b : buildings) {
            events.add(new int[]{b[0], -b[2]});   // start: negative height marker
            events.add(new int[]{b[1],  b[2]});   // end:   positive height marker
        }
        // Sort by x; ties: starts (negative) before ends, taller start first.
        events.sort((p, q) -> p[0] != q[0] ? p[0] - q[0] : p[1] - q[1]);

        List<List<Integer>> res = new ArrayList<>();
        TreeMap<Integer, Integer> active = new TreeMap<>();
        active.put(0, 1);                          // ground level always present
        int prevMax = 0;
        for (int[] e : events) {
            int h = Math.abs(e[1]);
            if (e[1] < 0) active.merge(h, 1, Integer::sum);     // building starts
            else {                                              // building ends
                int c = active.get(h);
                if (c == 1) active.remove(h); else active.put(h, c - 1);
            }
            int curMax = active.lastKey();
            if (curMax != prevMax) {
                res.add(Arrays.asList(e[0], curMax));
                prevMax = curMax;
            }
        }
        return res;
    }
}
```

**Dry run** on `[[2,9,10],[3,7,15],[5,12,12]]`: events processed left to right yield `[2,10],[3,15],[7,12],[12,0]`. ✓ (Same answer as the D&C version.)

**Complexity.** Time `O(n log n)` (sort + `TreeMap` ops), space `O(n)`. **Edge cases:** ground-level sentinel `0` so the last building drops to 0; tie-breaking by encoding starts as negative heights (a taller overlapping start must register before a shorter); empty input → empty list.

---

### Problem 31: Maximum Product Subarray — Sign-Aware Combine

**Statement.** Find the contiguous subarray with the largest **product** and return that product.

**Constraints.** `1 ≤ n ≤ 2·10^4`, `−10 ≤ nums[i] ≤ 10`, the answer fits in a 32-bit integer.

**Approach.**
- *Brute force.* All `O(n²)` subarrays.
- *D&C variation.* As in max-subarray (Problem 3) the best is in the left half, the right half, or **crosses** the midpoint — but products flip sign on negatives, so the crossing case must track both the maximum and **minimum** prefix/suffix products around `mid` (a negative crossing min can become the max when multiplied by another negative).
- *Optimal linear.* The standard answer keeps a running `maxHere` and `minHere`, swapping them on a negative element. Both are shown; the linear scan is what you ship.

```java
public class MaxProductSubarray {

    // O(n) running min/max — the optimal.
    public int maxProduct(int[] nums) {
        int best = nums[0], maxHere = nums[0], minHere = nums[0];
        for (int i = 1; i < nums.length; i++) {
            int x = nums[i];
            if (x < 0) { int t = maxHere; maxHere = minHere; minHere = t; } // sign flip
            maxHere = Math.max(x, maxHere * x);
            minHere = Math.min(x, minHere * x);
            best = Math.max(best, maxHere);
        }
        return best;
    }

    // O(n log n) D&C, tracking sign-aware crossing products.
    public int maxProductDC(int[] nums) {
        return solve(nums, 0, nums.length - 1)[0];   // [best, prefixMax, prefixMin, suffixMax, suffixMin]... simplified below
    }

    // Returns {best, maxPrefix, minPrefix, maxSuffix, minSuffix, totalProduct}
    private int[] solve(int[] a, int lo, int hi) {
        if (lo == hi) {
            int v = a[lo];
            return new int[]{v, v, v, v, v, v};
        }
        int mid = lo + (hi - lo) / 2;
        int[] L = solve(a, lo, mid), R = solve(a, mid + 1, hi);

        int best = Math.max(L[0], R[0]);
        // crossing max product = best suffix of left * best prefix of right (sign-aware)
        int crossMax = Math.max(L[3] * R[1], L[4] * R[2]);
        int crossMin = Math.min(L[3] * R[2], L[4] * R[1]);
        best = Math.max(best, crossMax);

        int total = L[5] * R[5];
        int maxPrefix = Math.max(L[1], L[5] * R[1]);
        int minPrefix = Math.min(L[2], L[5] * R[2]);
        int maxSuffix = Math.max(R[3], R[5] * L[3]);
        int minSuffix = Math.min(R[4], R[5] * L[4]);
        // ensure min/max prefixes consider the sign-flipped counterparts
        maxPrefix = Math.max(maxPrefix, L[5] * R[2]);
        minPrefix = Math.min(minPrefix, L[5] * R[1]);
        maxSuffix = Math.max(maxSuffix, R[5] * L[4]);
        minSuffix = Math.min(minSuffix, R[5] * L[3]);

        return new int[]{best, maxPrefix, minPrefix, maxSuffix, minSuffix, total};
    }
}
```

**Dry run** on `[2,3,-2,4]` (linear): `maxHere` walks `2,6,-2,4`; `minHere` walks `2,3,-12,-48`; best stays `6`. ✓ On `[-2,3,-4]` the two negatives multiply to give `24`.

**Complexity.** Linear scan `O(n)` time, `O(1)` space; D&C `O(n log n)` time, `O(log n)` stack. **Edge cases:** a single zero resets the running products (the linear version handles this since `max(x, maxHere*x)` picks `x`); single negative element; the sign-swap is the crux — forgetting it gives wrong answers on `[-2,-3]`.

---

### Problem 32: Maximum Sum of Two Non-Overlapping / Circular Maximum Subarray

**Statement.** (Maximum Sum Circular Subarray, LeetCode 918.) Given a **circular** integer array, find the maximum possible sum of a non-empty subarray, where the subarray may wrap around the end back to the front.

**Constraints.** `1 ≤ n ≤ 3·10^4`, `−3·10^4 ≤ nums[i] ≤ 3·10^4`.

**Approach.** This is the standard follow-up to maximum subarray (Problem 3). Two cases: (1) the best subarray does **not** wrap — ordinary Kadane maximum; (2) it **wraps** — equivalently the total sum minus the *minimum* (non-wrapping) subarray, because removing the smallest middle chunk leaves the wrapped remainder. The answer is `max(case1, total − minSubarray)`, with one guard: if every element is negative, `total − minSubarray = 0` (empty) is invalid, so fall back to case 1.

```
non-wrap:   [ ........ best ........ ]
wrap:       [ best_tail ........ best_head ]  ==  total - min_middle
```

```java
public class MaxCircularSubarray {

    public int maxSubarraySumCircular(int[] nums) {
        int total = 0;
        int maxSum = nums[0], curMax = 0;     // Kadane for max
        int minSum = nums[0], curMin = 0;     // Kadane for min
        for (int x : nums) {
            curMax = Math.max(curMax + x, x);
            maxSum = Math.max(maxSum, curMax);
            curMin = Math.min(curMin + x, x);
            minSum = Math.min(minSum, curMin);
            total += x;
        }
        // If all numbers are negative, maxSum is the (negative) answer.
        return (maxSum > 0) ? Math.max(maxSum, total - minSum) : maxSum;
    }
}
```

**Dry run** on `[5,-3,5]`: non-wrap max `5`; total `7`, min subarray `-3`, wrap = `7 − (−3) = 10` → answer `10` (`5 + 5` wrapping). ✓ On `[-3,-2,-3]` all negative → `−2`.

**Complexity.** Time `O(n)` single pass, space `O(1)`. **Edge cases:** all-negative array (must not return 0); single element; the `maxSum > 0` guard distinguishes "has a positive element" from "all negative". A D&C version exists but Kadane-twice is strictly better here.

---

### Problem 33: K Closest Points to Origin — Quickselect Partition

**Statement.** Given an array of points and an integer `k`, return the `k` points closest to the origin (any order).

**Constraints.** `1 ≤ k ≤ points.length ≤ 10^5`, `−10^4 ≤ xi, yi ≤ 10^4`.

**Approach.**
- *Sort.* Sort by squared distance, take the first `k` — `O(n log n)`.
- *Heap.* A max-heap of size `k` — `O(n log k)`.
- *Optimal expected linear (quickselect).* We do not need the `k` closest *sorted*, only the set. Partition around a random pivot by squared distance (avoid `sqrt`, it is monotonic) and recurse only into the side containing index `k`. Expected `O(n)`. After the partition that lands the pivot at index `k`, the prefix `[0, k)` is exactly the answer.

```java
import java.util.Random;

public class KClosestPoints {
    private final Random rnd = new Random();

    public int[][] kClosest(int[][] points, int k) {
        int lo = 0, hi = points.length - 1;
        while (lo < hi) {
            int p = partition(points, lo, hi);
            if (p == k) break;            // first k are settled
            else if (p < k) lo = p + 1;
            else hi = p - 1;
        }
        return java.util.Arrays.copyOfRange(points, 0, k);
    }

    private int partition(int[][] pts, int lo, int hi) {
        swap(pts, lo + rnd.nextInt(hi - lo + 1), hi);
        long pivot = dist(pts[hi]);
        int store = lo;
        for (int i = lo; i < hi; i++)
            if (dist(pts[i]) < pivot) swap(pts, store++, i);
        swap(pts, store, hi);
        return store;
    }

    private long dist(int[] p) { return (long) p[0] * p[0] + (long) p[1] * p[1]; }
    private void swap(int[][] a, int i, int j) { int[] t = a[i]; a[i] = a[j]; a[j] = t; }
}
```

**Dry run** on `points=[[1,3],[-2,2]]`, `k=1`: distances `10` and `8`; quickselect places `[-2,2]` (dist 8) in the prefix of length 1 → returns `[[-2,2]]`. ✓

**Complexity.** Time expected `O(n)`, worst `O(n²)` (randomization mitigates), space `O(1)` extra. **Edge cases:** `k = n` (return all, loop never partitions past); duplicate distances; squared distance uses `long` to avoid overflow at `±10^4`.

---

### Problem 34: Beautiful Array (LeetCode 932) — Constructive Divide & Conquer

**Statement.** Return any permutation of `1..n` that is "beautiful": for every `i < j` there is no `k` with `i < k < j` such that `2·A[k] = A[i] + A[j]` (no three-term arithmetic progression centered at an interior index).

**Constraints.** `1 ≤ n ≤ 1000`.

**Approach.** Pure constructive D&C with a number-theoretic insight: if an array of size `m` is beautiful, then mapping `x → 2x−1` (all odds) and `x → 2x` (all evens) and concatenating odds-then-evens preserves beautifulness for size `2m`. Why: for any triple with one odd and one even outer element, their sum is odd and `2·A[k]` is even, so no progression can straddle the odd/even boundary; inside each half the property is inherited. Build the array for `⌈n/2⌉` (odds) and `⌊n/2⌋` (evens) recursively, remap, and concatenate.

```
beautiful(1) = [1]
beautiful(n): L = beautiful(ceil(n/2)) -> odds 2x-1
              R = beautiful(floor(n/2)) -> evens 2x
              return [odds... , evens...]
```

```java
import java.util.*;

public class BeautifulArray {

    public int[] beautifulArray(int n) {
        return build(n).stream().mapToInt(Integer::intValue).toArray();
    }

    private List<Integer> build(int n) {
        List<Integer> res = new ArrayList<>();
        if (n == 1) { res.add(1); return res; }
        List<Integer> odds  = build((n + 1) / 2);   // will map to 2x-1
        List<Integer> evens = build(n / 2);          // will map to 2x
        for (int x : odds)  res.add(2 * x - 1);      // odd numbers <= n
        for (int x : evens) res.add(2 * x);          // even numbers <= n
        return res;
    }
}
```

**Dry run** on `n=4`: `build(2)` for odds → `build(1)=[1]`, `build(1)=[1]` → `[1, 2]`; remap odds `[1,2]→[1,3]`, evens `[1,2]→[2,4]` → `[1,3,2,4]`. Check: no interior `k` with `2A[k]=A[i]+A[j]`. ✓

**Complexity.** Time `O(n log n)` (each level produces `n` elements over `log n` levels), space `O(n log n)` for the intermediate lists (`O(n)` if built in place). **Edge cases:** `n=1` base case; the `(n+1)/2` vs `n/2` split exactly partitions `1..n` into ⌈⌉ odds and ⌊⌋ evens after remapping; values never exceed `n` because `2x−1 ≤ n` and `2x ≤ n` for the respective half sizes.

---

### Problem 35: Longest Substring with At Least K Repeating Characters — Divide on the Splitter

**Statement.** Given a string `s` and integer `k`, return the length of the longest substring in which **every** character appears at least `k` times.

**Constraints.** `1 ≤ s.length ≤ 10^4`, `s` is lowercase English, `1 ≤ k ≤ 10^5`.

**Approach.**
- *Brute force.* Check all `O(n²)` substrings, each `O(n)` to validate → `O(n³)` (or `O(n²)` with rolling counts).
- *Optimal D&C.* If a character `c` appears in `s` fewer than `k` times, then **no** valid substring can contain `c` — so `c` is a hard splitter. Partition `s` on every occurrence of every such under-frequent character and recurse on the pieces; the answer is the max over the pieces. If no character is under-frequent, the whole window is valid and we return its length. Each split strictly shrinks the problem, and there are at most 26 distinct splitters.

```
s = "ababbc", k = 2
 'c' occurs once (< 2) -> splitter at the 'c'
 recurse on "ababb"  -> all chars appear >= 2 -> length 5
```

```java
public class LongestSubstringKRepeats {

    public int longestSubstring(String s, int k) {
        return divide(s, 0, s.length(), k);
    }

    private int divide(String s, int start, int end, int k) {
        if (end - start < k) return 0;                 // too short to qualify
        int[] count = new int[26];
        for (int i = start; i < end; i++) count[s.charAt(i) - 'a']++;

        for (int i = start; i < end; i++) {
            if (count[s.charAt(i) - 'a'] < k) {         // splitter found
                int j = i + 1;
                while (j < end && count[s.charAt(j) - 'a'] < k) j++; // skip run of bad chars
                return Math.max(divide(s, start, i, k), divide(s, j, end, k));
            }
        }
        return end - start;                            // every char already >= k
    }
}
```

**Dry run** on `s="aaabb"`, `k=3`: counts `a=3, b=2`; `b` is under-frequent → split on the `b`s, recurse on `"aaa"` → all `a≥3` → length `3`. ✓

**Complexity.** Time `O(26·n) = O(n)` per recursion level with at most `O(26)` distinct splitting characters, giving `O(n·26)` overall in practice (worst case bounded by the number of distinct characters); space `O(26)` plus `O(n)` recursion depth worst case. **Edge cases:** `k=1` (whole string valid); window shorter than `k` returns 0; a string with all distinct under-frequent characters splits down to empty pieces. A sliding-window variant fixing the number of distinct characters also solves it in `O(26·n)`.

---

## 🧩 Extended Problems — Supplemental: Medium → Expert

### Problem 36: Find Peak Element — Binary Search on a Slope

**Statement.** Given an array where `nums[i] != nums[i+1]`, return the index of *any* peak — an element strictly greater than both its neighbors. Imagine `nums[-1] = nums[n] = -∞`. Required `O(log n)`.

**Constraints.** `1 ≤ n ≤ 10^4`, distinct adjacent values, `−2^31 ≤ nums[i] ≤ 2^31 − 1`.

**Approach.**
- *Brute force.* Linear scan comparing each element with its neighbors — `O(n)`.
- *Optimal.* Binary search on a sliding "slope". Compare `nums[mid]` with `nums[mid+1]`: if it is rising, a peak must exist to the right (the slope must eventually fall because of the `-∞` boundary on the right); otherwise a peak lies at `mid` or to the left. Each step halves the window → `O(log n)`.

```
nums:  1 2 3 1
              ^ peak at index 2
       mid=1: nums[1]=2 < nums[2]=3 -> go right (rising slope)
       lo=2, hi=3, mid=2: nums[2]=3 > nums[3]=1 -> hi=mid -> answer 2
```

```java
public class FindPeakElement {

    public int findPeakElement(int[] nums) {
        int lo = 0, hi = nums.length - 1;
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (nums[mid] < nums[mid + 1]) lo = mid + 1;   // rising → peak to the right
            else hi = mid;                                 // falling or equal → peak here or left
        }
        return lo;                                         // lo == hi at a peak
    }
}
```

**Dry run** on `[1,2,1,3,5,6,4]`: `mid=3` val 3 < `nums[4]`=5 → `lo=4`; `mid=5` val 6 > `nums[6]`=4 → `hi=5`; `mid=4` val 5 < `nums[5]`=6 → `lo=5`; loop ends → returns `5`. ✓

**Complexity.** Time `O(log n)`, space `O(1)`. **Edge cases:** single element is its own peak; strictly increasing array returns `n-1`; strictly decreasing returns `0`; the `nums[mid] != nums[mid+1]` precondition guarantees the slope decision is unambiguous.

---

### Problem 37: Find Minimum in Rotated Sorted Array II — Duplicate-Tolerant Binary Search

**Statement.** A sorted ascending array that **may contain duplicates** is rotated at an unknown pivot (e.g. `[2,2,2,0,1]`). Return the minimum element.

**Constraints.** `1 ≤ n ≤ 5000`, `−5000 ≤ nums[i] ≤ 5000`.

**Approach.** Problem 12 compared `nums[mid]` with `nums[hi]` to drive a `log n` search. Duplicates break the `==` case: when `nums[mid] == nums[hi]` you cannot tell whether the minimum is left or right (e.g. `[3,3,1,3]` vs `[3,1,3,3]`). The safe move is `hi--` — it cannot skip the minimum because there is still a copy of `nums[hi]` at `mid`. This degrades the worst case to `O(n)` (e.g. all-equal arrays) but stays `O(log n)` on average.

```java
public class FindMinRotatedII {

    public int findMin(int[] nums) {
        int lo = 0, hi = nums.length - 1;
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (nums[mid] > nums[hi])      lo = mid + 1;   // min strictly right
            else if (nums[mid] < nums[hi]) hi = mid;       // min at mid or left
            else                            hi--;          // ambiguous: safely shrink
        }
        return nums[lo];
    }
}
```

**Dry run** on `[2,2,2,0,1]`: `mid=2` val 2 == `nums[4]`=1? No, 2>1 → `lo=3`; `mid=3` val 0 < `nums[4]`=1 → `hi=3`; `lo==hi==3` → returns `0`. ✓ On `[10,1,10,10,10]`: `mid=2` val 10 == `nums[4]`=10 → `hi=3`; `mid=1` val 1 < `nums[3]`=10 → `hi=1`; `mid=0` val 10 > `nums[1]`=1 → `lo=1` → returns `1`. ✓

**Complexity.** Time `O(log n)` average, `O(n)` worst (all duplicates), space `O(1)`. **Edge cases:** no rotation → `nums[0]`; all-equal array degrades but still correct; one-element array.

---

### Problem 38: Search in a Sorted Matrix II — Staircase Search

**Statement.** Each row of an `m×n` integer matrix is sorted ascending and each column is also sorted ascending, but rows do **not** chain (the last of row `i` may exceed the first of row `i+1`). Return whether `target` exists.

**Constraints.** `1 ≤ m, n ≤ 300`, `−10^9 ≤ matrix[i][j], target ≤ 10^9`.

**Approach.**
- *Brute force.* Scan every cell, `O(m·n)`.
- *Per-row binary search.* `O(m log n)` — better but still ignores column order.
- *Optimal staircase walk.* Start at the top-right (or bottom-left) corner. At `(r, c)`, the element is the max of its row and the min of its column on the candidate sub-grid: if it equals target → done; if it is greater, the entire column below `c` is also greater so move left; if it is smaller, the row above is smaller so move down. Each step eliminates a row or a column → `O(m + n)`.

```
matrix:                start here -> (0, n-1)
[ 1  4  7 11 15]                              .
[ 2  5  8 12 19]   target 5: 15>5 left          target 20: 15<20 down
[ 3  6  9 16 22]            11>5 left                    19<20 down
[10 13 14 17 24]             7>5 left                    22>20 left
[18 21 23 26 30]             4<5 down                    16<20 down
                             5  found.                   24>20 left, 17<20 down
                                                         26>20 left, 23>20 left,
                                                         21>20 left, 18<20 done? no -> miss
```

```java
public class Search2DMatrixII {

    public boolean searchMatrix(int[][] matrix, int target) {
        if (matrix.length == 0 || matrix[0].length == 0) return false;
        int r = 0, c = matrix[0].length - 1;                // start top-right
        while (r < matrix.length && c >= 0) {
            int v = matrix[r][c];
            if (v == target) return true;
            else if (v > target) c--;                       // eliminate column c
            else r++;                                       // eliminate row r
        }
        return false;
    }
}
```

**Dry run** on the matrix above, `target=5`: `(0,4)=15>5 → c=3`; `(0,3)=11>5 → c=2`; `(0,2)=7>5 → c=1`; `(0,1)=4<5 → r=1`; `(1,1)=5` → `true`. ✓

**Complexity.** Time `O(m + n)`, space `O(1)`. **Edge cases:** empty matrix; target outside the value range; the divide-and-conquer recursive version (split the matrix into four quadrants around the middle element and discard at least one) is `O(n^log₄3) ≈ O(n^1.58)` — worse than the linear staircase but a textbook D&C exercise.

---

### Problem 39: Search Matrix — Recursive Quadrant Divide & Conquer

**Statement.** Same as Problem 38 (each row and each column sorted ascending). Solve with a recursive D&C that splits the matrix into four quadrants. This is the divide-and-conquer formulation explicitly asked for in some interviews.

**Constraints.** `1 ≤ m, n ≤ 300`, `−10^9 ≤ matrix[i][j], target ≤ 10^9`.

**Approach.** Pick the center element `M = matrix[r][c]`. If `M == target` done. Otherwise:
- If `M > target`, the bottom-right quadrant is **all** greater than target → discard it; recurse on the other three (top-left, top-right, bottom-left).
- If `M < target`, the top-left quadrant is **all** less than target → discard it; recurse on the other three (top-right, bottom-left, bottom-right).

Each recursive call shrinks by ~25%, giving `T(n²) = 3T(n²/4) + O(1) = O((n²)^(log₄3)) = O(n^log₂3) ≈ O(n^1.585)`. Slower than the `O(m+n)` staircase but a clean D&C example with a non-trivial Master Theorem application.

```
+---+---+
| TL| TR|    if M < target -> drop TL, recurse TR, BL, BR
+---+---+    if M > target -> drop BR, recurse TL, TR, BL
| BL| BR|
+---+---+
```

```java
public class SearchMatrixDC {

    public boolean searchMatrix(int[][] m, int target) {
        return search(m, target, 0, 0, m.length - 1, m[0].length - 1);
    }

    private boolean search(int[][] m, int target, int r1, int c1, int r2, int c2) {
        if (r1 > r2 || c1 > c2) return false;
        if (target < m[r1][c1] || target > m[r2][c2]) return false;   // range prune

        int rm = (r1 + r2) >>> 1, cm = (c1 + c2) >>> 1;
        int v = m[rm][cm];
        if (v == target) return true;
        if (v > target) {
            // target cannot be in bottom-right quadrant (rm+1..r2, cm+1..c2)
            return search(m, target, r1, c1, rm - 1, c2)              // top strip
                || search(m, target, rm, c1, r2, cm - 1);              // bottom-left
        } else {
            // target cannot be in top-left quadrant (r1..rm, c1..cm)
            return search(m, target, r1, cm + 1, rm, c2)              // top-right
                || search(m, target, rm + 1, c1, r2, c2);              // bottom strip
        }
    }
}
```

**Dry run** on the 5×5 matrix from Problem 38, `target=5`: center `(2,2)=9 > 5` → recurse top strip `(0,0)-(1,4)` and bottom-left `(2,0)-(4,1)`. In the top strip center `(0,2)=7 > 5` → recurse `(0,0)-(-,1)` and `(0,0)-(1,1)`; eventually hits `matrix[1][0]=2 < 5` then `matrix[1][1]=5` → `true`. ✓

**Complexity.** Time `O(n^1.585)` for `n × n`, space `O(log n)` recursion. **Edge cases:** target outside `[topLeft, bottomRight]` of any sub-rectangle is pruned in `O(1)`; degenerate 1×n or n×1 matrices reduce to plain binary search; empty matrix guarded by the `r1>r2` check.

---

### Problem 40: Find K-th Smallest Element in Sorted Matrix — Binary Search on Value

**Statement.** Given an `n × n` matrix where each row and each column is sorted ascending, return the k-th smallest element (1-indexed) in the matrix.

**Constraints.** `1 ≤ n ≤ 300`, `1 ≤ k ≤ n²`, `−10^9 ≤ matrix[i][j] ≤ 10^9`.

**Approach.**
- *Brute force.* Flatten + sort, `O(n² log n²)`.
- *Heap.* Min-heap over the row-wise frontier, `O(k log n)`.
- *Optimal binary search on value.* The answer lies in `[matrix[0][0], matrix[n-1][n-1]]`. For a candidate value `x`, count how many entries are `≤ x` via the same staircase walk used in Problem 38 (start bottom-left, step right while `≤ x`, otherwise step up) in `O(n)`. The count is monotonic in `x`, so binary-search the smallest `x` whose count is `≥ k`. Total `O(n log(maxVal − minVal))`.

```
sorted-on-both-axes matrix; staircase counts ≤ x in O(n):
   start (n-1, 0): go up if matrix[r][c] > x, else add (r+1) and go right
```

```java
public class KthSmallestSortedMatrix {

    public int kthSmallest(int[][] matrix, int k) {
        int n = matrix.length;
        int lo = matrix[0][0], hi = matrix[n - 1][n - 1];
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (countLE(matrix, mid) < k) lo = mid + 1;     // not enough ≤ mid → go higher
            else hi = mid;                                   // enough → mid is feasible
        }
        return lo;
    }

    private int countLE(int[][] m, int x) {
        int n = m.length, count = 0;
        int r = n - 1, c = 0;
        while (r >= 0 && c < n) {
            if (m[r][c] <= x) { count += r + 1; c++; }       // whole column up to r is ≤ x
            else r--;
        }
        return count;
    }
}
```

**Dry run** on `[[1,5,9],[10,11,13],[12,13,15]]`, `k=8`: answer `13`. Binary-search over `[1,15]`: e.g. `mid=8`, count=2 (<8) → `lo=9`; `mid=12`, count=6 (<8) → `lo=13`; `mid=13`, count=8 (≥8) → `hi=13` → returns `13`. ✓

**Complexity.** Time `O(n log(max − min))` for value-range search, space `O(1)`. **Edge cases:** `k=1` returns `matrix[0][0]`; `k=n²` returns `matrix[n-1][n-1]`; duplicate values handled because the answer must itself be in the matrix (the binary search lands on a value that achieves `count ≥ k` with `count(mid-1) < k`, forcing `mid` to be a matrix entry).

---

### Problem 41: Sum of Distances in Tree — Rerooting Divide & Conquer

**Statement.** Given an undirected tree with `n` nodes (0-indexed), for every node `i` return `answer[i]` = the sum of distances from `i` to all other nodes.

**Constraints.** `1 ≤ n ≤ 3·10^4`. The input is a valid tree.

**Approach.**
- *Brute force.* BFS from every node, `O(n²)`.
- *Optimal rerooting D&C.* Root the tree at node `0`. In one post-order pass compute `count[v]` = subtree size of `v`, and `answer[0]` = sum of depths. Then a second pre-order pass *rerouts* the answer to every other node using the identity

```
   answer[child] = answer[parent] - count[child] + (n - count[child])
                   ^^^^^^^^^^^^^^^   ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
                   inherit from p     when we move from parent to child:
                                      the count[child] nodes get 1 step closer
                                      the (n - count[child]) others get 1 step farther
```

This is a classic D&C trick on trees: solve the root, then derive all other roots in `O(1)` each by combining the local subtree sizes — total `O(n)`.

```java
import java.util.*;

public class SumOfDistancesInTree {
    private List<List<Integer>> adj;
    private int[] count, answer;
    private int n;

    public int[] sumOfDistancesInTree(int n, int[][] edges) {
        this.n = n;
        adj = new ArrayList<>();
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
        for (int[] e : edges) { adj.get(e[0]).add(e[1]); adj.get(e[1]).add(e[0]); }
        count = new int[n];
        answer = new int[n];
        Arrays.fill(count, 1);

        postOrder(0, -1);     // fills count[] and answer[0] = sum of depths
        preOrder(0, -1);      // reroots to every other node
        return answer;
    }

    private void postOrder(int node, int parent) {
        for (int child : adj.get(node)) {
            if (child == parent) continue;
            postOrder(child, node);
            count[node] += count[child];
            answer[node] += answer[child] + count[child];   // child contributes its subtree
        }
    }

    private void preOrder(int node, int parent) {
        for (int child : adj.get(node)) {
            if (child == parent) continue;
            answer[child] = answer[node] - count[child] + (n - count[child]);
            preOrder(child, node);
        }
    }
}
```

**Dry run** on `n=6`, edges `[[0,1],[0,2],[2,3],[2,4],[2,5]]`. Rooted at 0: `count = [6,1,4,1,1,1]`, `answer[0] = 0+1+1+2+2+2 = 8`. Rerooting to node 2: `answer[2] = 8 - 4 + 2 = 6`; to node 3: `answer[3] = 6 - 1 + 5 = 10`; matches the brute force. ✓

**Complexity.** Time `O(n)` for both passes, space `O(n)` for the adjacency list and recursion. **Edge cases:** `n=1` → `[0]`; line-shaped tree (deep recursion — JVM stack may need increasing); the rerooting identity assumes `count[child]` was computed relative to the original root, which it is.

---

### Problem 42: Cherry Pickup II — D&C Top-Down with Memoization (Robot Pair)

**Statement.** Given an `m × n` grid of cherry counts, two robots start at `(0,0)` and `(0,n-1)` and both must reach the bottom row. Each step, both move down one row simultaneously into one of three adjacent columns. They pick all cherries on their cells (only once even if both visit the same cell). Return the maximum cherries they can pick.

**Constraints.** `2 ≤ m ≤ 70`, `2 ≤ n ≤ 70`, `0 ≤ grid[i][j] ≤ 100`.

**Approach.**
- *Brute force / two-pass greedy.* Counter-example exists; you must move both robots in lock-step.
- *Optimal D&C with memoization.* Both robots advance one row per step, so the state is `(row, col1, col2)` — at most `m · n · n` states. Each state branches into `3 × 3 = 9` next-state options (each robot's column shift in `{-1, 0, +1}`). Memoize. The combine step at each state is the max over the 9 children, plus the cells' cherry contribution (avoid double-counting when `col1 == col2`).

```
row r:    robotA at c1, robotB at c2
          gain = grid[r][c1] + (c1 != c2 ? grid[r][c2] : 0)
row r+1:  best of grid[r+1][c1+dc1][c2+dc2] over (dc1, dc2) in {-1,0,1}^2
```

```java
public class CherryPickupII {
    private Integer[][][] memo;
    private int m, n;
    private int[][] grid;

    public int cherryPickup(int[][] g) {
        this.grid = g;
        this.m = g.length;
        this.n = g[0].length;
        memo = new Integer[m][n][n];
        return dp(0, 0, n - 1);
    }

    private int dp(int r, int c1, int c2) {
        if (c1 < 0 || c1 >= n || c2 < 0 || c2 >= n) return Integer.MIN_VALUE;
        if (memo[r][c1][c2] != null) return memo[r][c1][c2];

        int gain = grid[r][c1] + (c1 != c2 ? grid[r][c2] : 0);
        if (r == m - 1) return memo[r][c1][c2] = gain;

        int best = 0;
        for (int d1 = -1; d1 <= 1; d1++)
            for (int d2 = -1; d2 <= 1; d2++)
                best = Math.max(best, dp(r + 1, c1 + d1, c2 + d2));

        return memo[r][c1][c2] = gain + best;
    }
}
```

**Dry run** on `[[3,1,1],[2,5,1],[1,5,5],[2,1,1]]`: optimal pair-path picks up `24` (robotA: 3→5→5→1, robotB: 1→1→5→1 with overlap handling). The 9-way branching at each row, memoized, returns 24. ✓

**Complexity.** Time `O(m · n² · 9) = O(m n²)`, space `O(m n²)` memo. **Edge cases:** single column (both robots forced to the same cell — count once); `m=2` base case after one step; out-of-grid columns return `MIN_VALUE` so they are never chosen.

---

### Problem 43: Burst Balloons — Interval D&C with Memoization

**Statement.** Given `n` balloons numbered `0..n-1` with values `nums[i]`, bursting balloon `i` earns `nums[left] · nums[i] · nums[right]` where `left, right` are the indices of the balloons immediately adjacent at the moment of bursting (treat off-grid as `1`). Return the maximum coins after bursting all balloons.

**Constraints.** `1 ≤ n ≤ 300`, `0 ≤ nums[i] ≤ 100`.

**Approach.** Trying every burst order is `O(n!)`. The trick is to flip the question: instead of asking "which balloon to burst first", ask **"which balloon is burst last in interval `(i, j)`"**. If `k` is last in `(i, j)` (exclusive on both ends, padded with `1` sentinels), all other balloons in `(i, j)` are already gone, so bursting `k` yields `nums[i] · nums[k] · nums[j]`, and the two sub-intervals `(i, k)` and `(k, j)` are independent → pure divide & conquer with memoization.

```
dp(i, j) = max over k in (i, j) of:
              dp(i, k) + nums[i]·nums[k]·nums[j] + dp(k, j)
```

```java
public class BurstBalloons {

    public int maxCoins(int[] nums) {
        int n = nums.length;
        int[] a = new int[n + 2];
        a[0] = a[n + 1] = 1;
        for (int i = 0; i < n; i++) a[i + 1] = nums[i];
        int[][] memo = new int[n + 2][n + 2];
        return solve(a, 0, n + 1, memo);
    }

    private int solve(int[] a, int i, int j, int[][] memo) {
        if (j - i < 2) return 0;                       // no balloons strictly between
        if (memo[i][j] != 0) return memo[i][j];
        int best = 0;
        for (int k = i + 1; k < j; k++) {              // k = last balloon burst in (i, j)
            int coins = a[i] * a[k] * a[j]
                      + solve(a, i, k, memo)
                      + solve(a, k, j, memo);
            best = Math.max(best, coins);
        }
        return memo[i][j] = best;
    }
}
```

**Dry run** on `[3,1,5,8]`: optimal order burst `1, 5, 3, 8` → `3·1·5 + 3·5·8 + 1·3·8 + 1·8·1 = 15+120+24+8 = 167`. The D&C with `k = last` reproduces 167 by picking `k=4 (the 8)` last in `(0, n+1)`, then optimally splitting. ✓

**Complexity.** Time `O(n³)` (`O(n²)` intervals × `O(n)` split points), space `O(n²)` memo. **Edge cases:** empty array → 0; single balloon worth `nums[0]·1·1 = nums[0]`; the padding-with-1 sentinels remove the off-grid special case.

---

### Problem 44: Longest Nice Substring — Recursive Splitter on Case Mismatch

**Statement.** A string `s` is "nice" if for every English letter that appears, **both** its lowercase and uppercase forms also appear. Return the longest nice substring; ties broken by earliest position.

**Constraints.** `1 ≤ s.length ≤ 100`, `s` consists of letters only.

**Approach.** Same shape as Problem 35: identify a character that **cannot** belong to any nice substring containing the current window, and split on it. Here the disqualifier is a letter that appears in only one case. Build the set of characters present; any character whose opposite case is missing is a hard splitter — recurse on the pieces and return the longest result. If no splitter exists, the whole window is already nice.

```
"YazaAay"
 'Y' has no 'y' lower (wait it does), 'z' has no 'Z'    -> split at 'z'
 left "Ya"  -> 'Y' no 'y' inside this window? we recheck per window
 right "aAay" -> all letters paired -> length 4
```

```java
public class LongestNiceSubstring {

    public String longestNiceSubstring(String s) {
        if (s.length() < 2) return "";
        java.util.Set<Character> chars = new java.util.HashSet<>();
        for (char c : s.toCharArray()) chars.add(c);

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char opp = Character.isLowerCase(c) ? Character.toUpperCase(c) : Character.toLowerCase(c);
            if (!chars.contains(opp)) {                          // c is a splitter
                String left  = longestNiceSubstring(s.substring(0, i));
                String right = longestNiceSubstring(s.substring(i + 1));
                return (left.length() >= right.length()) ? left : right;
            }
        }
        return s;                                                // already nice
    }
}
```

**Dry run** on `"YazaAay"`: chars `{Y,a,z,A,y}`. Splitters: `Y` (lowercase `y` present? yes), `z` (no `Z`) → splitter found at index 2. Recurse `"Ya"` (splitter `Y` since no `y` here, splitter `a` since no `A`) → returns `""`. Recurse `"aAay"`: chars `{a,A,y}`, `y` splitter (no `Y`) → recurse `"aAa"` → fully nice → returns `"aAa"`; recurse `""` → `""`. Answer `"aAa"`, length 3. ✓

**Complexity.** Time `T(n) = T(left) + T(right) + O(n)` with at most 26 distinct splitters, bounded by `O(n · log n)` average and `O(n²)` worst case; space `O(n)` recursion. **Edge cases:** empty or length-1 string returns `""`; string already nice returns itself; on a tie the *left* substring wins via the `>=` comparison.

---

### Problem 45: All Possible Full Binary Trees — Catalan-Style Recursion

**Statement.** A *full binary tree* is one where every node has 0 or 2 children. Given an odd integer `n`, return a list of all structurally distinct full binary trees with exactly `n` nodes (each node's value is `0`).

**Constraints.** `1 ≤ n ≤ 20`, `n` is odd (otherwise no full binary tree exists).

**Approach.** A full binary tree of `n` nodes (`n` odd) has a root plus a left subtree of `i` nodes and a right subtree of `n - 1 - i` nodes, where both `i` and `n - 1 - i` are odd. Recurse on all such splits, take the Cartesian product of left and right options, and prepend a fresh root. Memoize by `n` because the same sub-count is requested many times — the count itself is the `(n-1)/2`-th Catalan number, growing fast.

```
fbt(7) = roots whose (leftSize, rightSize) ∈ {(1,5),(3,3),(5,1)}
         for each split: left ∈ fbt(leftSize), right ∈ fbt(rightSize)
         -> result count = sum of products = Catalan(3) = 5
```

```java
import java.util.*;

public class AllFullBinaryTrees {

    public static class TreeNode {
        int val; TreeNode left, right;
        TreeNode(int v) { val = v; }
    }

    private final Map<Integer, List<TreeNode>> memo = new HashMap<>();

    public List<TreeNode> allPossibleFBT(int n) {
        if ((n & 1) == 0) return Collections.emptyList();
        if (memo.containsKey(n)) return memo.get(n);

        List<TreeNode> res = new ArrayList<>();
        if (n == 1) { res.add(new TreeNode(0)); memo.put(n, res); return res; }

        for (int l = 1; l < n; l += 2) {                       // odd left sizes
            int r = n - 1 - l;                                  // odd right sizes
            for (TreeNode left : allPossibleFBT(l))
                for (TreeNode right : allPossibleFBT(r)) {
                    TreeNode root = new TreeNode(0);
                    root.left = left;
                    root.right = right;
                    res.add(root);
                }
        }
        memo.put(n, res);
        return res;
    }
}
```

**Dry run** on `n=7`: splits `(1,5),(3,3),(5,1)`. `fbt(1)=1` tree, `fbt(3)=1`, `fbt(5)=2` → counts `1·2 + 1·1 + 2·1 = 5` distinct trees, matching Catalan(3). ✓

**Complexity.** Time and space `O(C(n))` where `C` is Catalan-style growth (`~4^k/k^1.5` with `k = (n-1)/2`); each unique sub-count is built once thanks to memoization. **Edge cases:** even `n` returns empty list; `n=1` returns a single leaf; the trees share child node objects across results (acceptable here, but if downstream mutates the tree, deep-clone each child).

---

### Problem 46: Beautiful Subarrays Count — Merge Sort Over Prefix XOR

**Statement.** A subarray is "beautiful" if you can pair up its elements and zero each pair out by AND-ing equal powers of 2 and subtracting (equivalently: the XOR of the subarray is 0). Given `nums`, return the number of beautiful subarrays. (This is the LeetCode 2588 variant of the inversion-counting template.)

**Constraints.** `1 ≤ n ≤ 10^5`, `1 ≤ nums[i] ≤ 10^6`.

**Approach.** A subarray `nums[i..j]` has XOR 0 iff prefix-XOR `P[j+1] == P[i]`. So the answer is the number of *equal* pairs in the prefix-XOR array. A hash map gives `O(n)`, but the inversion-style merge-sort approach generalizes to harder relations and is the D&C pattern for this kind of pair-counting. Sort prefix-XOR with merge sort; during each merge, count equal pairs across the divide using a monotone window (run lengths of equal values on both sides).

```java
public class BeautifulSubarraysMerge {

    public long beautifulSubarrays(int[] nums) {
        int n = nums.length;
        int[] pre = new int[n + 1];
        for (int i = 0; i < n; i++) pre[i + 1] = pre[i] ^ nums[i];
        int[] aux = new int[n + 1];
        return sort(pre, aux, 0, n);
    }

    private long sort(int[] p, int[] aux, int lo, int hi) {
        if (lo >= hi) return 0;
        int mid = lo + (hi - lo) / 2;
        long count = sort(p, aux, lo, mid) + sort(p, aux, mid + 1, hi);

        // Count cross pairs with p[i] == p[j] across the divide.
        int i = lo, j = mid + 1;
        while (i <= mid) {
            while (j <= hi && p[j] < p[i]) j++;
            int k = j;
            while (k <= hi && p[k] == p[i]) k++;             // run of equals in right half
            int rightEquals = k - j;
            int iRun = i;
            while (iRun <= mid && p[iRun] == p[i]) iRun++;   // run of equals in left half
            int leftEquals = iRun - i;
            count += (long) leftEquals * rightEquals;
            i = iRun;
        }

        // Merge two sorted halves.
        int a = lo, b = mid + 1, w = lo;
        while (a <= mid && b <= hi) aux[w++] = (p[a] <= p[b]) ? p[a++] : p[b++];
        while (a <= mid) aux[w++] = p[a++];
        while (b <= hi)  aux[w++] = p[b++];
        System.arraycopy(aux, lo, p, lo, hi - lo + 1);
        return count;
    }
}
```

**Dry run** on `nums=[4,3,1,2,4]`: prefix-XOR `[0,4,7,6,4,0]`. Equal pairs: `(0,0)` at indices (0,5), `(4,4)` at (1,4) → `2` beautiful subarrays: `[4,3,1,2,4]` and `[3,1,2]`. ✓ The merge-pass counting reproduces 2.

**Complexity.** Time `O(n log n)`, space `O(n)`. **Edge cases:** all distinct prefix-XOR → 0; a length-1 subarray cannot be beautiful (its XOR is `nums[0] > 0`); the equal-run scan in each merge is monotone, keeping the per-level work linear. A hash map alternative is `O(n)` expected — D&C is the asked-for variant in some interview contexts.

---

### Problem 47: Maximum Points You Can Obtain from Cards — Binary-Search-on-Answer / D&C Reflection

**Statement.** Given a row of cards each with a point value and an integer `k`, you must take exactly `k` cards, one at a time from either end of the row, to maximize total points.

**Constraints.** `1 ≤ k ≤ cardPoints.length ≤ 10^5`, `1 ≤ cardPoints[i] ≤ 10^4`.

**Approach.** Equivalent reformulation (this is the D&C reflection): instead of "pick `k` from the ends", pick `i` from the front and `k - i` from the back for `i ∈ [0, k]`. The answer is `max over i of prefixSum[i] + suffixSum[k - i]`. Precomputing the prefix and suffix sums turns the search into `O(k)` with `O(n)` preprocessing. This is the "divide the work between two endpoints" trick reused in capacity / median problems.

```
cards: [1, 2, 3, 4, 5, 6, 1], k=3
i=0 from front, 3 from back -> 1+6+5 = 12   (best)
i=1                            1 + 6+1 = 8
i=2                            1+2 + 1 = 4
i=3                            1+2+3   = 6
```

```java
public class MaxScoreFromCards {

    public int maxScore(int[] cardPoints, int k) {
        int n = cardPoints.length;
        int total = 0;
        for (int v : cardPoints) total += v;
        if (k == n) return total;                     // take all

        // We *leave behind* a contiguous window of size n - k with minimum sum.
        int windowSize = n - k;
        int windowSum = 0;
        for (int i = 0; i < windowSize; i++) windowSum += cardPoints[i];
        int minWindow = windowSum;
        for (int i = windowSize; i < n; i++) {
            windowSum += cardPoints[i] - cardPoints[i - windowSize];
            minWindow = Math.min(minWindow, windowSum);
        }
        return total - minWindow;
    }
}
```

**Dry run** on `[1,2,3,4,5,6,1]`, `k=3`: `total=22`, `windowSize=4`, window sums `[1+2+3+4=10, 2+3+4+5=14, 3+4+5+6=18, 4+5+6+1=16]`, min `10` → answer `22 − 10 = 12`. ✓

**Complexity.** Time `O(n)` (one prefix sum + one sliding window), space `O(1)`. **Edge cases:** `k == n` returns the full sum; `windowSize == 0` (handled by the early return); arrays of length 1.

---

### Problem 48: Stickers to Spell Word — D&C with Memoization on Letter Bag

**Statement.** Given `stickers[]` (each a string) and a `target` string, you may use unlimited copies of each sticker and rearrange/discard letters from them. Return the minimum number of stickers needed to spell `target`, or `-1` if impossible.

**Constraints.** `1 ≤ stickers.length ≤ 50`, sticker length up to 10, `1 ≤ target.length ≤ 15`, lowercase letters.

**Approach.** Reduce `target` to a **multiset of remaining letters** (canonicalized as a sorted string). The recursion `dp(remaining)` chooses one sticker that supplies at least one letter still needed, subtracts the overlap from `remaining`, and recurses → `1 + dp(remaining')`. Memoize by the canonical string. The clever pruning is to fix the first remaining letter and only try stickers that contain it — this avoids exploring permutations that would lead to identical sub-states. `target.length ≤ 15` keeps the state space manageable.

```
target "thehat"  letters sorted -> "aehhtt"
choose sticker covering 'a': subtract -> "ehhtt" -> recurse
```

```java
import java.util.*;

public class StickersToSpellWord {
    private Map<String, Integer> memo;
    private int[][] stickerCounts;

    public int minStickers(String[] stickers, String target) {
        stickerCounts = new int[stickers.length][26];
        for (int i = 0; i < stickers.length; i++)
            for (char c : stickers[i].toCharArray())
                stickerCounts[i][c - 'a']++;

        memo = new HashMap<>();
        memo.put("", 0);
        int ans = dp(target);
        return ans == Integer.MAX_VALUE ? -1 : ans;
    }

    private int dp(String remaining) {
        if (memo.containsKey(remaining)) return memo.get(remaining);
        int[] need = new int[26];
        for (char c : remaining.toCharArray()) need[c - 'a']++;

        int best = Integer.MAX_VALUE;
        char first = remaining.charAt(0);              // pruning: cover the first letter
        for (int[] sticker : stickerCounts) {
            if (sticker[first - 'a'] == 0) continue;
            // Build the remaining multiset after consuming this sticker once.
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 26; i++) {
                int left = need[i] - sticker[i];
                for (int k = 0; k < left; k++) sb.append((char) ('a' + i));
            }
            int sub = dp(sb.toString());
            if (sub != Integer.MAX_VALUE) best = Math.min(best, 1 + sub);
        }
        memo.put(remaining, best);
        return best;
    }
}
```

**Dry run** on `stickers = ["with","example","science"]`, `target = "thehat"`. Canonical `"aehhtt"`. The "with" and "example" stickers together can cover the bag in `3` (e.g. `example` provides `a,e`; `with` provides `t,h`; `with` again provides `t,h`). ✓

**Complexity.** Time worst case `O(S · 2^T · T)` where `S = stickers.length` and `T = target.length` (each distinct sub-multiset is processed once; for `T ≤ 15` the canonical-string state space is bounded), space `O(2^T · T)` for the memo. **Edge cases:** no sticker contains the first remaining letter → that branch returns `MAX_VALUE` and the caller signals `-1`; sticker that exactly matches a single letter degrades to brute force but still correct; the first-letter pruning is crucial — without it the search blows up.

---

### 🟢 Basic

**Q: What are the three steps of a divide-and-conquer algorithm?**
Divide the problem into sub-problems, conquer (solve) them recursively, and combine the results. The base case stops the recursion.

**Q: How does D&C differ from dynamic programming?**
D&C assumes sub-problems are *independent* (no shared work). DP applies when sub-problems *overlap*, so you memoize to avoid recomputation. Merge sort is D&C; Fibonacci-with-memo is DP.

**Q: Why is the midpoint written as `lo + (hi - lo) / 2`?**
To avoid integer overflow. `(lo + hi)` can exceed `Integer.MAX_VALUE` for large indices; the subtraction form never does. This was a famous real-world bug in `java.util.Arrays.binarySearch`.

**Q: Give the recurrence for merge sort and solve it.**
`T(n) = 2T(n/2) + Θ(n)`. By the Master Theorem Case 2, `T(n) = Θ(n log n)`.

### 🟡 Intermediate

**Q: State the Master Theorem and apply it to binary search.**
For `T(n)=aT(n/b)+f(n)`, compare `f(n)` to `n^(log_b a)`. Binary search: `a=1,b=2,f=Θ(1)`, watershed `n⁰=1`, so Case 2 → `Θ(log n)`.

**Q: Why is quicksort `O(n²)` worst case but preferred in practice?**
Worst case occurs on already-sorted input with a poor (first/last) pivot. In practice randomized or median-of-three pivots make `O(n²)` astronomically unlikely, and quicksort's in-place, cache-friendly partitioning beats merge sort's `O(n)` extra memory and pointer chasing.

**Q: How does counting inversions reuse merge sort?**
During the merge, whenever a right-half element is placed before remaining left-half elements, those left elements are all greater, so each contributes an inversion. Accumulate `(mid - i + 1)` at each such step.

**Q: When would you choose Boyer–Moore over the D&C majority algorithm?**
Almost always — Boyer–Moore is `O(n)` time, `O(1)` space versus D&C's `O(n log n)`. D&C is mainly an interview exercise to show you can frame a counting problem recursively.

### 🟠 Advanced

**Q: Explain the strip optimization in closest-pair and why it is `O(n)`.**
After recursing, candidates crossing the divide must lie within a strip of width `2d`. Within that strip, sorting by y and a packing argument show each point can have at most a constant number (~7) of neighbors closer than `d`, so the strip scan is linear, preserving the `O(n log n)` total.

**Q: Why does median-of-medians use groups of 5?**
Groups of 5 guarantee the pivot exceeds at least `3⌈n/10⌉ ≈ 30%` of elements (and is below another 30%), yielding the recurrence `T(n) ≤ T(n/5) + T(7n/10) + O(n)`. Because `1/5 + 7/10 < 1`, this solves to `O(n)`. Group size 3 gives `T(n/3)+T(2n/3)+O(n)`, which is *not* linear; 7 works but with worse constants.

**Q: How does matrix exponentiation accelerate linear recurrences?**
Encode the recurrence as a constant transition matrix `M` acting on the state vector. Then the n-th state is `M^n` applied to the initial state. Binary exponentiation computes `M^n` in `O(log n)` matrix multiplies, turning an `O(n)` DP into `O(k³ log n)` for a `k`-term recurrence.

**Q: What is the amortized / aggregate cost analysis behind merge sort's `O(n log n)`?**
Each of the `log n` levels of the recursion tree does `Θ(n)` total merging work (the sub-arrays at each level partition the input). `log n` levels × `Θ(n)` per level = `Θ(n log n)`. This per-level accounting is the recursion-tree method underpinning Master Theorem Case 2.

### 🔴 Expert

**Q: When does the Master Theorem fail, and what do you use instead?**
It fails when (a) `f(n)` is not polynomially smaller/larger than the watershed (the "gap" cases, e.g. `f(n)=n/log n`), (b) sub-problems have unequal sizes, or (c) the regularity condition in Case 3 does not hold. Use the **Akra–Bazzi** method or direct recursion-tree summation. Median-of-medians' uneven split is the canonical Akra–Bazzi case.

**Q: How do you parallelize divide-and-conquer, and what limits the speedup?**
Independent sub-problems fork onto separate threads/cores (Java's `ForkJoinPool` / `RecursiveTask`, or Cilk's `spawn`/`sync`). The *combine* step is the serial bottleneck; by Brent's theorem / Amdahl's law the span (critical path) for parallel merge sort is `Θ(log³ n)` if you also parallelize the merge, capping achievable speedup. Stop forking below a grain-size threshold to avoid task-overhead dominating.

**Q: Why is FFT-based multiplication asymptotically better than Karatsuba, and when does it actually win?**
FFT computes the convolution of digit arrays in `O(n log n)` versus Karatsuba's `O(n^1.585)`. But FFT has large constant factors and floating-point precision concerns (or needs NTT over a finite field). It only wins for very large operands — thousands of digits — which is why `BigInteger.multiply` in modern JDKs switches from schoolbook → Karatsuba → Toom-3 → Schönhage–Strassen as size grows.

**Q: How does external merge sort scale to data larger than RAM?**
Split the file into RAM-sized runs, sort each in memory, write them back, then do a k-way merge with a min-heap streaming from each run. The number of merge passes is `⌈log_k(runs)⌉`; choosing `k` to match available buffers and minimizing disk seeks (sequential reads) is the engineering crux behind database sort operators and MapReduce shuffles.

**Q: Quickselect vs introselect vs median-of-medians for production selection?**
Randomized quickselect is expected `O(n)` with tiny constants and is the practical default. Median-of-medians guarantees worst-case `O(n)` but has large constants. **Introselect** (used by C++ `std::nth_element`) runs quickselect but counts recursion depth, falling back to median-of-medians only when progress stalls — getting quickselect's speed with the worst-case guarantee.

---

## ⚠️ Common Pitfalls

- **Overflow in the midpoint.** Always `lo + (hi - lo) / 2`. Same care for `Integer.MIN_VALUE` in fast power (widen to `long` before negating).
- **Off-by-one in binary-search bounds.** Be deliberate about inclusive `[lo, hi]` vs half-open `[lo, hi)`; mixing the two causes infinite loops or missed elements. Pick one convention and keep `lo`/`hi` updates consistent (`mid+1` / `mid` / `mid-1`).
- **Forgetting the cross-boundary case.** In max-subarray, closest-pair, and skyline, the answer can straddle the divide. Omitting the combine/cross logic silently returns wrong answers that pass small tests.
- **Counting inversions: `int` overflow.** The count can be `~n²/2`; accumulate in `long`. Also keep the `<=` (not `<`) in the merge or you mis-handle equal keys and break stability.
- **Unstable merge.** Using `<` instead of `<=` when choosing the left element makes merge sort unstable — matters when sorting records by a secondary key.
- **Quicksort worst case.** Last-element pivot on sorted data is `O(n²)` and blows the recursion stack. Randomize the pivot or use median-of-three, and recurse on the smaller partition first to bound stack depth to `O(log n)`.
- **D&C where DP belongs.** If you find yourself recomputing the same sub-problem (overlapping sub-problems), switch to memoization/DP — naive D&C there is exponential.
- **Recursion depth / `StackOverflowError`.** Deep recursion on `10^5`+ elements can overflow the JVM stack; prefer iterative binary search and tail-recursion-elimination where possible, or increase `-Xss`.
- **Closest-pair partition cost.** Re-scanning to partition points by side turns the algorithm into `O(n log² n)` or worse; carry index bounds or pre-tag each point's side to keep the split linear.

---

## 📚 Further Reading

- *Introduction to Algorithms* (CLRS), 4th ed. — Chapters 2 (merge sort), 4 (recurrences, Master & Akra–Bazzi), 9 (median & order statistics), 33 (computational geometry / closest pair).
- *Algorithm Design* — Kleinberg & Tardos, Chapter 5 (Divide and Conquer: inversions, closest pair, integer multiplication).
- *Algorithms* — Sedgewick & Wayne (and the companion Coursera course) — merge sort, quicksort, and quickselect with empirical analysis.
- Competitive Programmer's Handbook (Antti Laaksonen) — binary search on the answer, matrix exponentiation, fast power.
- *The Art of Computer Programming*, Vol. 3 (Knuth) — sorting and selection, external sorting depth.
- LeetCode practice set: 704 Binary Search, 53 Maximum Subarray, 169/229 Majority Element, 50 Pow(x,n), 493 Reverse Pairs, 215 Kth Largest Element, 973 K Closest Points, 218 The Skyline Problem, 327 Count of Range Sum.
- CP-Algorithms (cp-algorithms.com) — Karatsuba, FFT/NTT, binary exponentiation, and matrix-power write-ups with proofs.
