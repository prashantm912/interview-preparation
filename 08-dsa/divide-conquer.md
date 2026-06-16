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

## Interview Q&A by Level

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
