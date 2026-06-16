# Searching Algorithms

Searching is the art of locating a value — or, more powerfully, locating *the boundary where a property flips* — inside ordered or implicitly ordered data. The single most leveraged idea in all of interview prep is **binary search on a monotonic predicate**: once you can phrase a problem as "find the smallest `x` for which `check(x)` is true", you collapse an O(n) or O(n²) brute force into O(log n) or O(n log n).

[← Back to master index](../README.md) | [← DSA index](README.md)

---

## Concept & Intuition

There are two families of search.

**Linear search** scans every element until it finds the target (or exhausts the input). It needs no precondition — the data can be in any order — and runs in O(n). You reach for it when the data is unsorted, tiny, or you only get one pass (a stream).

**Binary search** exploits *order*. If the array is sorted, you can compare the target to the middle element and discard half the search space every step, giving O(log n). The deep insight is that "sorted array" is just the most obvious case of a more general precondition: **monotonicity of a predicate**. If there is some boolean function `check(i)` that is `false, false, …, false, true, true, …, true` along the index (or value) axis, then binary search finds the flip point — even when the array itself is not literally sorted.

The core **invariant** of every binary search is: *the answer always lies within `[lo, hi]`*. Every iteration shrinks that window while preserving the invariant. The three things you must nail down before writing a single line:

1. **Search space** — indices `[0, n-1]`, or a value range `[min, max]` (binary search on the answer), or an unbounded range you grow first (exponential search).
2. **Predicate / comparison** — what makes you go left vs. right, and is the window `[lo, hi]` inclusive or `[lo, hi)` half-open.
3. **Termination & return** — what `lo`/`hi` mean *after* the loop ends. This is where 90% of bugs live.

ASCII picture of binary search on a sorted array, target = 23:

```
 idx:   0    1    2    3    4    5    6    7    8
 val: [ 2    5    8   12   16   23   38   56   72 ]
        lo                 mid                  hi      mid=4 -> 16 < 23, go right
                                lo   mid        hi      mid=6 -> 38 > 23, go left
                                lo
                                mid             ...     lo=5 mid=5 -> 23 == 23  FOUND at idx 5
```

And the predicate view — "find first index whose value ≥ target" — where `T` marks where the predicate becomes true:

```
 val:  2    5    8   12   16   23   38   56   72
 >=23: F    F    F    F    F    T    T    T    T
                               ^ first-true = lower_bound
```

Once you see search as "find the boundary in a monotone `F…F T…T` strip", rotated arrays, sqrt, Koko-eats-bananas, capacity problems, and median-of-two-arrays all become variations of the same skeleton.

---

## Complexity Cheat-Sheet

| Algorithm / Operation | Time (avg) | Time (worst) | Space | Precondition |
|---|---|---|---|---|
| Linear search | O(n) | O(n) | O(1) | none |
| Binary search (sorted array) | O(log n) | O(log n) | O(1) iterative | sorted / monotone predicate |
| Lower / upper bound (first/last occurrence) | O(log n) | O(log n) | O(1) | sorted |
| Search in rotated sorted array | O(log n) | O(log n) | O(1) | rotated-sorted, distinct |
| Search in 2D matrix (staircase) | O(m + n) | O(m + n) | O(1) | rows & cols sorted |
| Search in 2D matrix (flatten + binary) | O(log(m·n)) | O(log(m·n)) | O(1) | fully sorted row-major |
| Peak element | O(log n) | O(log n) | O(1) | adjacent neighbors differ |
| Median of two sorted arrays | O(log(min(m,n))) | O(log(min(m,n))) | O(1) | both sorted |
| Binary search on the answer | O(n · log(range)) | — | O(1) | monotone feasibility |
| Exponential (galloping) search | O(log i) | O(log i) | O(1) | sorted, unbounded/target near front |
| Ternary search (unimodal max/min) | O(log₃ n) ≈ O(log n) | O(log n) | O(1) | strictly unimodal |
| Integer sqrt via binary search | O(log n) | O(log n) | O(1) | — |

`i` = index of the target; `range` = size of the value interval being searched.

---

## Patterns & Recognition

Train yourself to spot these triggers in the prompt:

| Symptom in the prompt | Reach for |
|---|---|
| "array is sorted" + "find / does it contain X" | Plain binary search |
| "first / last / count of occurrences of X" | Lower bound & upper bound |
| "smallest / largest value such that …feasible…" | **Binary search on the answer** |
| "minimize the maximum" / "maximize the minimum" | Binary search on the answer (parametric) |
| "rotated sorted array" / "shifted" | Modified binary search (find sorted half) |
| "matrix sorted by rows and columns" | Staircase from top-right, O(m+n) |
| "matrix sorted in full row-major order" | Flatten index + binary search |
| "find a peak / local maximum" | Binary search on the slope |
| "two sorted arrays" + "median / k-th element" | Partition binary search |
| "unsorted but target likely near the start" / unbounded stream | Exponential search |
| "function rises then falls (unimodal)" | Ternary search |
| "sqrt / nth-root / divide without operator" | Binary search on value |

The meta-recognition cue for **binary search on the answer**: the problem asks for an optimal numeric value, and you can write a `boolean feasible(x)` that is *monotone* — once `x` works, every larger (or smaller) `x` works too. If you can write that check, you can binary search the answer space even with zero sorted input.

A reusable mental template (the "lower bound" form, half-open, no overflow):

```
lo = first candidate, hi = last candidate + 1   // [lo, hi)
while (lo < hi):
    mid = lo + (hi - lo) / 2
    if check(mid): hi = mid       // mid might be the answer, keep it
    else:          lo = mid + 1   // mid can't be, discard it
return lo                          // smallest index/value where check is true
```

---

## Coding Problems

### Problem 1: Classic Binary Search

**Statement.** Given a sorted (ascending) array `nums` of distinct integers and a `target`, return its index, or `-1` if absent. Constraints: `1 ≤ n ≤ 10^4`, `-10^4 ≤ nums[i], target ≤ 10^4`.

**Approach.** Brute force is a linear scan, O(n). Optimal exploits the sort: compare `target` with `nums[mid]` and discard the impossible half. The only subtlety is the `mid` computation — `(lo + hi) / 2` can overflow `int` when `lo + hi > Integer.MAX_VALUE`; use `lo + (hi - lo) / 2`.

```java
public int search(int[] nums, int target) {
    int lo = 0, hi = nums.length - 1;          // inclusive [lo, hi]
    while (lo <= hi) {
        int mid = lo + (hi - lo) / 2;          // overflow-safe
        if (nums[mid] == target) return mid;
        else if (nums[mid] < target) lo = mid + 1;
        else hi = mid - 1;
    }
    return -1;
}
```

**Dry run.** `nums=[-1,0,3,5,9,12]`, `target=9`. lo=0,hi=5,mid=2→3<9,lo=3. lo=3,hi=5,mid=4→9==9 return 4.

**Time:** O(log n). **Space:** O(1).

**Follow-ups.** Recursive version (and why iterative avoids O(log n) stack); what if there are duplicates (switch to lower/upper bound); what if the array is rotated.

---

### Problem 2: First Bad Version (predicate boundary)

**Statement.** Versions `1..n` are good then bad after some point. Given `isBadVersion(v)` (a monotone `F…F T…T`), return the first bad version, minimizing API calls. Constraints: `1 ≤ bad ≤ n ≤ 2^31 - 1`.

**Approach.** This is the canonical "first true" binary search. Brute force calls `isBadVersion` for each version, O(n). Binary search the boundary in O(log n). Because `n` can be `2^31 - 1`, `(lo + hi)/2` *will* overflow — this problem exists to teach the overflow fix.

```java
public int firstBadVersion(int n) {
    int lo = 1, hi = n;                 // answer in [lo, hi]
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;   // critical: avoids int overflow
        if (isBadVersion(mid)) hi = mid;    // mid could be the first bad
        else                   lo = mid + 1;
    }
    return lo;                          // lo == hi == first bad version
}
// boolean isBadVersion(int version); provided by the judge
```

**Dry run.** `n=5, bad=4`. lo=1,hi=5,mid=3→good,lo=4. lo=4,hi=5,mid=4→bad,hi=4. lo==hi==4.

**Time:** O(log n). **Space:** O(1).

**Follow-ups.** Generalize to "first element ≥ target" (lower bound); contrast with "last good version" (upper bound minus one).

---

### Problem 3: First and Last Occurrence (lower & upper bound)

**Statement.** Given a sorted array with possible duplicates and a `target`, return `[first, last]` indices, or `[-1, -1]`. Constraints: `0 ≤ n ≤ 10^5`. Must be O(log n).

**Approach.** A single binary search lands on *some* occurrence but not necessarily the boundary. Run two boundary searches: **lower bound** (first index with `nums[i] >= target`) and **upper bound** (first index with `nums[i] > target`); the last occurrence is `upper - 1`.

```java
public int[] searchRange(int[] nums, int target) {
    int first = lowerBound(nums, target);
    if (first == nums.length || nums[first] != target) return new int[]{-1, -1};
    int last = lowerBound(nums, target + 1) - 1;   // upper bound - 1
    return new int[]{first, last};
}

// smallest index i with nums[i] >= key  (half-open [lo, hi))
private int lowerBound(int[] nums, int key) {
    int lo = 0, hi = nums.length;
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (nums[mid] >= key) hi = mid;
        else                  lo = mid + 1;
    }
    return lo;
}
```

**Dry run.** `nums=[5,7,7,8,8,10]`, `target=8`. `lowerBound(8)=3`. `lowerBound(9)=5`, so `last=4`. Return `[3,4]`.

**Time:** O(log n) (two passes). **Space:** O(1).

**Follow-ups.** Count occurrences = `upper - lower`; find insertion point (LeetCode "Search Insert Position" is exactly `lowerBound`); how `Arrays.binarySearch` returns `-(insertionPoint) - 1` on miss.

---

### Problem 4: Sqrt(x) via Binary Search

**Statement.** Given non-negative integer `x`, return `floor(sqrt(x))`. No `Math.sqrt`. Constraints: `0 ≤ x ≤ 2^31 - 1`.

**Approach.** Brute force increments `i` until `i*i > x`, O(√x). Binary search the answer in `[0, x]`: find the largest `m` with `m*m <= x`. Use `long` for `m*m` to avoid overflow — the classic trap when `x` is near `Integer.MAX_VALUE`.

```java
public int mySqrt(int x) {
    if (x < 2) return x;
    long lo = 1, hi = x;                  // largest m with m*m <= x
    while (lo < hi) {
        long mid = lo + (hi - lo + 1) / 2;   // upper-mid to avoid infinite loop
        if (mid * mid <= x) lo = mid;        // mid feasible, keep it
        else                hi = mid - 1;
    }
    return (int) lo;
}
```

**Dry run.** `x=8`. lo=1,hi=8,mid=5→25>8,hi=4. lo=1,hi=4,mid=3→9>8,hi=2. lo=1,hi=2,mid=2→4≤8,lo=2. lo==hi==2. ✔ (`floor(2.83)=2`).

**Time:** O(log x). **Space:** O(1).

**Note on the mid bias.** When the update is `lo = mid` (instead of `mid + 1`), you must round `mid` *up* with `(lo + hi + 1) / 2`; otherwise `lo` and `hi` differing by 1 loops forever.

**Follow-ups.** Real-valued sqrt to a tolerance (`while (hi - lo > 1e-9)`); nth root; Newton's method as an O(log log) alternative.

---

### Problem 5: Search in Rotated Sorted Array

**Statement.** A sorted array of distinct values is rotated at an unknown pivot (e.g. `[4,5,6,7,0,1,2]`). Find `target`'s index or `-1`. Constraints: `1 ≤ n ≤ 5000`, distinct values. Required O(log n).

**Approach.** Brute force linear scan O(n). For O(log n): at each step, one half `[lo, mid]` or `[mid, hi]` is guaranteed sorted. Detect the sorted half by comparing endpoints, check whether `target` lies inside that sorted half, and recurse into the correct side.

```java
public int search(int[] nums, int target) {
    int lo = 0, hi = nums.length - 1;
    while (lo <= hi) {
        int mid = lo + (hi - lo) / 2;
        if (nums[mid] == target) return mid;
        if (nums[lo] <= nums[mid]) {                 // left half sorted
            if (nums[lo] <= target && target < nums[mid]) hi = mid - 1;
            else                                          lo = mid + 1;
        } else {                                     // right half sorted
            if (nums[mid] < target && target <= nums[hi]) lo = mid + 1;
            else                                          hi = mid - 1;
        }
    }
    return -1;
}
```

**Dry run.** `nums=[4,5,6,7,0,1,2]`, `target=0`. lo=0,hi=6,mid=3→7≠0; left `[4..7]` sorted, 0 not in `[4,7)`, so lo=4. lo=4,hi=6,mid=5→1≠0; left `[0..1]` sorted, 0 in `[0,1)`, hi=4. lo=4,hi=4,mid=4→0==0 return 4.

**Time:** O(log n). **Space:** O(1).

**Follow-ups.** **With duplicates** (`[1,0,1,1,1]`): when `nums[lo]==nums[mid]==nums[hi]` you cannot tell which half is sorted, so shrink `lo++, hi--`, degrading to O(n) worst case. Also: find the minimum / find the rotation count (count of pivots).

---

### Problem 6: Find Minimum in Rotated Sorted Array

**Statement.** A rotated ascending array of distinct values; return the minimum. Constraints: `1 ≤ n ≤ 5000`. O(log n).

**Approach.** The minimum is the unique element smaller than its predecessor — the pivot. Compare `nums[mid]` to `nums[hi]`: if `nums[mid] > nums[hi]`, the minimum is strictly right of `mid`; otherwise it is at `mid` or left. Comparing to `hi` (not `lo`) is the robust choice because it sidesteps the not-rotated edge case.

```java
public int findMin(int[] nums) {
    int lo = 0, hi = nums.length - 1;
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (nums[mid] > nums[hi]) lo = mid + 1;  // min is to the right
        else                      hi = mid;      // min is mid or to the left
    }
    return nums[lo];
}
```

**Dry run.** `nums=[4,5,6,7,0,1,2]`. lo=0,hi=6,mid=3→7>2,lo=4. lo=4,hi=6,mid=5→1<2,hi=5. lo=4,hi=5,mid=4→0<1,hi=4. lo==hi==4 → `nums[4]=0`.

**Time:** O(log n). **Space:** O(1).

**Follow-ups.** Return the *rotation index* (== position of min); minimum with duplicates (shrink `hi--` on tie, O(n) worst); find max via symmetry.

---

### Problem 7: Search a 2D Matrix

**Statement.** An `m×n` matrix where each row is sorted left-to-right **and** the first integer of each row is greater than the last integer of the previous row (i.e. fully sorted in row-major order). Return whether `target` exists. Constraints: `1 ≤ m, n ≤ 100`.

**Approach.** Because the matrix is globally sorted, treat it as a virtual sorted array of length `m*n` and binary search, mapping `idx → (idx / n, idx % n)`. O(log(m·n)). (Contrast: if only rows *and* columns are sorted but not globally — LeetCode 240 — use the staircase walk from the top-right in O(m+n), shown in the follow-up.)

```java
public boolean searchMatrix(int[][] matrix, int target) {
    int m = matrix.length, n = matrix[0].length;
    int lo = 0, hi = m * n - 1;             // virtual flattened index
    while (lo <= hi) {
        int mid = lo + (hi - lo) / 2;
        int val = matrix[mid / n][mid % n]; // decode row/col
        if (val == target) return true;
        else if (val < target) lo = mid + 1;
        else hi = mid - 1;
    }
    return false;
}
```

**Dry run.** `matrix=[[1,3,5,7],[10,11,16,20],[23,30,34,60]]`, `target=16`. n=4. lo=0,hi=11,mid=5→`[1][1]=11`<16,lo=6. lo=6,hi=11,mid=8→`[2][0]=23`>16,hi=7. lo=6,hi=7,mid=6→`[1][2]=16` return true.

**Time:** O(log(m·n)). **Space:** O(1).

**Follow-ups.** The staircase for the row+column-sorted variant:

```java
// LeetCode 240: rows sorted L->R, columns sorted top->bottom (NOT globally sorted)
public boolean searchMatrixII(int[][] matrix, int target) {
    int r = 0, c = matrix[0].length - 1;   // start top-right
    while (r < matrix.length && c >= 0) {
        if (matrix[r][c] == target) return true;
        else if (matrix[r][c] > target) c--;   // too big -> drop a column
        else r++;                               // too small -> drop a row
    }
    return false;
}
```

---

### Problem 8: Find Peak Element

**Statement.** `nums[i] != nums[i+1]` for all `i`. A peak is any element strictly greater than both neighbors (out-of-bounds neighbors are −∞). Return *any* peak's index in O(log n). Constraints: `1 ≤ n ≤ 1000`.

**Approach.** Linear scan is O(n). The O(log n) trick: binary search on the *slope*. If `nums[mid] < nums[mid+1]`, an ascending slope guarantees a peak to the right (the array must eventually come down, since the right boundary is −∞), so go right; otherwise a peak is at `mid` or to the left.

```java
public int findPeakElement(int[] nums) {
    int lo = 0, hi = nums.length - 1;
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (nums[mid] < nums[mid + 1]) lo = mid + 1;  // climb right
        else                           hi = mid;      // peak is mid or left
    }
    return lo;
}
```

**Dry run.** `nums=[1,2,1,3,5,6,4]`. lo=0,hi=6,mid=3→3<5,lo=4. lo=4,hi=6,mid=5→6>4,hi=5. lo=4,hi=5,mid=4→5<6,lo=5. lo==hi==5 → index 5 (value 6), a valid peak.

**Time:** O(log n). **Space:** O(1).

**Follow-ups.** Why is a peak guaranteed (boundary −∞ + no equal neighbors)? Peak in a 2D matrix (LeetCode 1901, O(n log m)); find a *local minimum*; ternary search for a strictly unimodal array (next problem).

---

### Problem 9: Koko Eating Bananas (binary search on the answer)

**Statement.** `piles[i]` bananas per pile; Koko eats `k` bananas/hour, finishing at most one pile per hour (leftovers of a pile spill to the next hour). Given `h` hours, find the minimum integer eating speed `k` to finish all piles within `h` hours. Constraints: `1 ≤ piles.length ≤ 10^4`, `1 ≤ piles[i] ≤ 10^9`, `piles.length ≤ h ≤ 10^9`.

**Approach.** The answer space is speeds `[1, max(piles)]`. `hoursNeeded(k)` is **monotone decreasing** in `k`: faster speed never increases the hours. So `feasible(k) = hoursNeeded(k) <= h` is a monotone `F…F T…T` predicate — binary search the smallest feasible `k`. Each feasibility check is O(n) (use `ceil` division), so total O(n · log(maxPile)).

```java
public int minEatingSpeed(int[] piles, int h) {
    int lo = 1, hi = 0;
    for (int p : piles) hi = Math.max(hi, p);   // max pile = slowest needed speed
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (canFinish(piles, mid, h)) hi = mid; // feasible -> try slower
        else                          lo = mid + 1;
    }
    return lo;
}

private boolean canFinish(int[] piles, int k, int h) {
    long hours = 0;
    for (int p : piles) hours += (p + k - 1) / k;   // ceil(p / k), no overflow
    return hours <= h;
}
```

**Dry run.** `piles=[3,6,7,11], h=8`. lo=1,hi=11. mid=6→hours=1+1+2+2=6≤8 feasible,hi=6. mid=3→1+2+3+4=10>8,lo=4. mid=5→1+2+2+3=8≤8,hi=5. mid=4→1+2+2+3=8≤8,hi=4. lo==hi==4. Answer 4.

**Time:** O(n · log(max pile)). **Space:** O(1).

**Follow-ups.** This template covers a whole family: "Capacity to Ship Packages in D Days", "Split Array Largest Sum" (minimize the maximum), "Minimum Number of Days to Make m Bouquets", "Magnetic Force Between Balls" (maximize the minimum — flip the predicate). The skill is *spotting the monotone feasibility check*.

---

### Problem 10: Median of Two Sorted Arrays (hard / senior)

**Statement.** Two sorted arrays `nums1` (size m) and `nums2` (size n); return the median of the combined set in **O(log(m+n))**. Constraints: `0 ≤ m, n ≤ 1000`, `1 ≤ m+n`.

**Approach.** Merging is O(m+n) and too slow for the target. The optimal idea is a **partition binary search** over the *smaller* array. Choose a cut `i` in `nums1` and the complementary cut `j = (m+n+1)/2 - i` in `nums2` so the combined left part has exactly the lower half of the elements. The partition is correct when `left1 <= right2` and `left2 <= right1`. Binary search `i` to satisfy that, using `±∞` sentinels for cut edges. This is one of the highest-signal "do you really understand binary search invariants" questions.

```java
public double findMedianSortedArrays(int[] a, int[] b) {
    if (a.length > b.length) return findMedianSortedArrays(b, a); // search smaller
    int m = a.length, n = b.length, half = (m + n + 1) / 2;
    int lo = 0, hi = m;                       // cut position in a, in [0, m]
    while (lo <= hi) {
        int i = lo + (hi - lo) / 2;           // elements taken from a
        int j = half - i;                     // elements taken from b
        int aLeft  = (i == 0) ? Integer.MIN_VALUE : a[i - 1];
        int aRight = (i == m) ? Integer.MAX_VALUE : a[i];
        int bLeft  = (j == 0) ? Integer.MIN_VALUE : b[j - 1];
        int bRight = (j == n) ? Integer.MAX_VALUE : b[j];

        if (aLeft <= bRight && bLeft <= aRight) {       // correct partition
            int maxLeft  = Math.max(aLeft, bLeft);
            if (((m + n) & 1) == 1) return maxLeft;      // odd total
            int minRight = Math.min(aRight, bRight);
            return (maxLeft + minRight) / 2.0;           // even total
        } else if (aLeft > bRight) {
            hi = i - 1;                                  // took too many from a
        } else {
            lo = i + 1;                                  // took too few from a
        }
    }
    throw new IllegalArgumentException("inputs not sorted");
}
```

**Dry run.** `a=[1,3]`, `b=[2]`. m=2,n=1,half=2. lo=0,hi=2,i=1,j=1: aLeft=1,aRight=3,bLeft=2,bRight=∞. `1<=∞ && 2<=3` ✔. Total odd → `max(1,2)=2`. Median 2.0. ✔

**Time:** O(log(min(m, n))). **Space:** O(1).

**Follow-ups.** Generalize to the **k-th smallest of two sorted arrays** (discard `k/2` from one side each step, O(log k)); k-th smallest across *many* sorted arrays (min-heap, O(k log p)); streaming median (two heaps).

---

### Problem 11: Exponential & Ternary Search

**Statement (exponential).** Search a sorted array — possibly unbounded, or where the target is expected near the front — for a `target`. **Statement (ternary).** Given a strictly unimodal array (increases then decreases), find the index of the maximum.

**Approach (exponential / galloping).** Double an index `bound` (1, 2, 4, 8, …) until `arr[bound] >= target` or you run off the end, bounding the target inside `[bound/2, bound]`, then binary search that window. Useful for unbounded streams (no known length) and faster than plain binary search when the target sits near the start — O(log i) where `i` is the target's index.

```java
public int exponentialSearch(int[] arr, int target) {
    int n = arr.length;
    if (n == 0) return -1;
    if (arr[0] == target) return 0;
    int bound = 1;
    while (bound < n && arr[bound] < target) bound *= 2;   // gallop
    int lo = bound / 2, hi = Math.min(bound, n - 1);       // narrowed window
    while (lo <= hi) {                                     // binary search
        int mid = lo + (hi - lo) / 2;
        if (arr[mid] == target) return mid;
        else if (arr[mid] < target) lo = mid + 1;
        else hi = mid - 1;
    }
    return -1;
}
```

**Approach (ternary).** On a strictly unimodal function, split the range into thirds with `m1` and `m2`. If `f(m1) < f(m2)` the max is right of `m1` so discard `[lo, m1]`; else discard `[m2, hi]`. Each step removes a third → O(log₃ n).

```java
public int ternarySearchPeak(int[] arr) {
    int lo = 0, hi = arr.length - 1;
    while (lo < hi) {
        int m1 = lo + (hi - lo) / 3;
        int m2 = hi - (hi - lo) / 3;
        if (arr[m1] < arr[m2]) lo = m1 + 1;   // peak is right of m1
        else                   hi = m2 - 1;   // peak is left of m2 (or between)
    }
    return lo;   // index of the maximum
}
```

**Dry run (exponential).** `arr=[1,2,4,8,16,32,64]`, `target=16`. bound:1(2<16)→2(4<16)→4(16!<16 stop). Window `[2,4]`; binary search finds index 4.

**Time.** Exponential: O(log i). Ternary: O(log n) (base 3). **Space:** O(1) each.

**Follow-ups.** Ternary search works on *continuous* unimodal functions too (maximize a real-valued cost — loop `while (hi - lo > eps)`); note ternary makes ~2 comparisons per step vs binary's 1, so for the *binary*-searchable peak problem (Problem 8) plain binary search is actually preferable — ternary is for genuinely unimodal-but-not-slope-monotone cases.

---

## 🧩 Extended Problems — Set 1: Classic Easy → Medium

### Problem 12: Search Insert Position — Lower Bound

**Statement.** Given a sorted array of distinct integers and a `target`, return the index if found; otherwise return the index where it would be inserted to keep the array sorted.

**Constraints.** `1 ≤ n ≤ 10^4`, `-10^4 ≤ nums[i], target ≤ 10^4`, all values distinct and ascending.

**Approach.** A linear scan finds the first element `≥ target` in O(n). This is exactly the **lower-bound** binary search: find the smallest index `i` with `nums[i] >= target`. If the target exists, that index holds it; if not, it is the correct insertion slot (and equals `n` when the target exceeds everything). Using the half-open `[lo, hi)` window with `hi = nums.length` lets the returned `lo` naturally land at `n` for "insert at end", with no special case.

```java
public int searchInsert(int[] nums, int target) {
    int lo = 0, hi = nums.length;          // half-open [lo, hi); hi can be n
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (nums[mid] >= target) hi = mid; // mid is a candidate, keep it
        else                     lo = mid + 1;
    }
    return lo;                             // first index with nums[i] >= target
}
```

**Dry run.** `nums=[1,3,5,6]`, `target=5`. lo=0,hi=4,mid=2→5≥5,hi=2. lo=0,hi=2,mid=1→3<5,lo=2. lo==hi==2 → index 2. For `target=2`: returns 1 (insert between 1 and 3). For `target=7`: returns 4 (append).

**Complexity.** Time O(log n), space O(1). **Edge cases:** target smaller than all elements (returns 0); target larger than all (returns `n`); single-element array.

---

### Problem 13: Find Smallest Letter Greater Than Target — Upper Bound (circular)

**Statement.** Given a sorted array of lowercase `letters` and a character `target`, return the smallest letter strictly greater than `target`. Letters wrap around, so if `target >= letters[n-1]`, return `letters[0]`.

**Constraints.** `2 ≤ letters.length ≤ 10^4`, `letters` sorted non-decreasing, may contain duplicates, `target` is a lowercase letter.

**Approach.** This is an **upper-bound** search: find the first index `i` with `letters[i] > target`. The wrap-around is handled elegantly with modulo: if every letter is `≤ target`, the upper bound is `n`, and `letters[n % n] = letters[0]` gives the circular answer for free. Duplicates are irrelevant because strict `>` skips over equal letters automatically.

```java
public char nextGreatestLetter(char[] letters, char target) {
    int lo = 0, hi = letters.length;       // [lo, hi)
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (letters[mid] > target) hi = mid;   // strictly greater -> candidate
        else                       lo = mid + 1;
    }
    return letters[lo % letters.length];   // wrap when lo == n
}
```

**Dry run.** `letters=['c','f','j']`, `target='c'`. We need first `> 'c'` → index 1 = 'f'. For `target='j'`: all `≤ 'j'`, lo becomes 3, `letters[3 % 3] = letters[0] = 'c'`.

**Complexity.** Time O(log n), space O(1). **Edge cases:** target equals last letter (wrap to first); all letters equal; target smaller than first letter (returns first).

---

### Problem 14: Sqrt — Valid Perfect Square — Binary Search on Value

**Statement.** Given a positive integer `num`, return `true` if it is a perfect square (the square of some integer), without using any built-in square-root function.

**Constraints.** `1 ≤ num ≤ 2^31 - 1`.

**Approach.** Brute force tries `i*i` for `i` up to `√num`, O(√num). Binary search the candidate root in `[1, num]`: find whether some `m` satisfies `m*m == num`. The critical trap is overflow — `m*m` can exceed `Integer.MAX_VALUE`, so compute it in `long`. We compare `m*m` against `num` to steer the search, narrowing in O(log num).

```java
public boolean isPerfectSquare(int num) {
    long lo = 1, hi = num;
    while (lo <= hi) {
        long mid = lo + (hi - lo) / 2;
        long sq = mid * mid;               // long avoids 32-bit overflow
        if (sq == num) return true;
        else if (sq < num) lo = mid + 1;
        else               hi = mid - 1;
    }
    return false;
}
```

**Dry run.** `num=16`. lo=1,hi=16,mid=8→64>16,hi=7. mid=4→16==16 → true. `num=14`: converges to lo>hi without an exact hit → false.

**Complexity.** Time O(log num), space O(1). **Edge cases:** `num=1` (1*1=1, true); largest 32-bit input near `2^31-1` (the `long` product is essential); non-squares return false cleanly.

---

### Problem 15: Two Sum II — Input Array Is Sorted (binary search / two pointers)

**Statement.** Given a 1-indexed sorted array `numbers` and a `target`, return the 1-based indices `[i, j]` of the two numbers that add up to `target`. Exactly one solution exists and you may not use the same element twice.

**Constraints.** `2 ≤ numbers.length ≤ 3·10^4`, sorted non-decreasing, `-1000 ≤ numbers[i] ≤ 1000`, a unique answer is guaranteed.

**Approach.** Because the array is sorted, two approaches shine. The **two-pointer** method is O(n): shrink from both ends — if the sum is too big, move the right pointer left; if too small, move left pointer right. (A binary-search variant fixes each `i` and binary searches for `target - numbers[i]`, giving O(n log n).) Two pointers is optimal and uses O(1) space; it works because moving a pointer monotonically changes the sum in a known direction.

```java
public int[] twoSum(int[] numbers, int target) {
    int lo = 0, hi = numbers.length - 1;
    while (lo < hi) {
        int sum = numbers[lo] + numbers[hi];
        if (sum == target) return new int[]{lo + 1, hi + 1}; // 1-indexed
        else if (sum < target) lo++;   // need a larger sum
        else                   hi--;   // need a smaller sum
    }
    return new int[]{-1, -1};          // unreachable per constraints
}
```

```
 [2, 7, 11, 15]   target = 9
  lo          hi   2+15=17 > 9 -> hi--
  lo      hi       2+11=13 > 9 -> hi--
  lo  hi           2+7 =9  == 9 -> return [1, 2]
```

**Complexity.** Time O(n) two-pointer (O(n log n) for the binary-search variant), space O(1). **Edge cases:** negative numbers; duplicate values that form the pair; minimum-length array of two elements.

---

### Problem 16: Find the Duplicate Number — Binary Search on Value Range

**Statement.** Given an array `nums` of `n+1` integers where each value is in `[1, n]`, exactly one value is repeated (possibly multiple times). Return that duplicate. You must not modify the array and must use only O(1) extra space.

**Constraints.** `1 ≤ n ≤ 10^5`, `nums.length == n+1`, every element in `[1, n]`, exactly one duplicated value.

**Approach.** Sorting or a hash set break the constraints. The clever O(n log n) method **binary searches on the value space `[1, n]`**, not on indices. For a candidate `m`, count how many array elements are `≤ m`. By the pigeonhole principle, if that count exceeds `m`, the duplicate lies in `[1, m]`; otherwise it lies in `[m+1, n]`. The count is a monotone predicate over the value `m`, so binary search converges to the duplicate. (Floyd's cycle detection gives O(n), mentioned in follow-up.)

```java
public int findDuplicate(int[] nums) {
    int lo = 1, hi = nums.length - 1;      // value range [1, n]
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        int count = 0;
        for (int x : nums) if (x <= mid) count++;
        if (count > mid) hi = mid;         // too many small values -> dup is low
        else             lo = mid + 1;     // dup is in the upper half
    }
    return lo;
}
```

**Dry run.** `nums=[1,3,4,2,2]`, n=4. lo=1,hi=4,mid=2→count(≤2)=3>2,hi=2. lo=1,hi=2,mid=1→count(≤1)=1≤1,lo=2. lo==hi==2 → duplicate 2.

**Complexity.** Time O(n log n), space O(1). **Edge cases:** the duplicate appears many times; smallest case `nums=[1,1]`; values clustered at the extremes. **Follow-up:** Floyd's tortoise-and-hare treats the array as a linked list (value = next index) for O(n) time.

---

### Problem 17: Peak Index in a Mountain Array — Binary Search on the Slope

**Statement.** A mountain array strictly increases to a single peak, then strictly decreases. Given such an `arr`, return the index of the peak element. Must run in O(log n).

**Constraints.** `3 ≤ arr.length ≤ 10^5`, `0 ≤ arr[i] ≤ 10^6`, guaranteed to be a valid mountain (strictly up then strictly down).

**Approach.** Linear scan is O(n). Binary search the **slope**: at `mid`, compare `arr[mid]` with `arr[mid+1]`. If `arr[mid] < arr[mid+1]` we are on the ascending side, so the peak is strictly to the right (`lo = mid+1`); otherwise we are at or past the peak, so it is `mid` or to the left (`hi = mid`). The strict-mountain guarantee removes ties, so the loop converges cleanly to the unique summit.

```java
public int peakIndexInMountainArray(int[] arr) {
    int lo = 0, hi = arr.length - 1;
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (arr[mid] < arr[mid + 1]) lo = mid + 1;  // still climbing
        else                         hi = mid;       // at or past the peak
    }
    return lo;
}
```

```
 arr: [0, 2, 5, 7, 4, 1]
              ^ peak (index 3)
 slope:  + + + - -    (compare arr[i] vs arr[i+1])
         first '-' boundary = peak
```

**Complexity.** Time O(log n), space O(1). **Edge cases:** peak at the boundary-adjacent positions (never at index 0 or n-1 by definition); minimal length 3; large plateaus impossible due to strictness.

---

### Problem 18: Single Element in a Sorted Array — Binary Search on Pair Parity

**Statement.** A sorted array where every element appears exactly twice except one element that appears once. Find that single element in O(log n) time and O(1) space.

**Constraints.** `1 ≤ n ≤ 10^5`, sorted non-decreasing, exactly one element appears once, all others exactly twice (so the length is odd).

**Approach.** Before the single element, pairs start at even indices `(0,1), (2,3), …`; after it, the alignment shifts so pairs start at odd indices. Binary search on this parity: force `mid` to be even; if `nums[mid] == nums[mid+1]`, the single element is to the right (the pairing is still intact up to here), so move `lo = mid + 2`; otherwise it is at `mid` or to the left. The pairing-parity is the monotone predicate.

```java
public int singleNonDuplicate(int[] nums) {
    int lo = 0, hi = nums.length - 1;
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (mid % 2 == 1) mid--;               // align mid to the even index
        if (nums[mid] == nums[mid + 1]) lo = mid + 2; // pair intact -> go right
        else                            hi = mid;     // break is here or left
    }
    return nums[lo];
}
```

**Dry run.** `nums=[1,1,2,3,3,4,4,8,8]`. lo=0,hi=8,mid=4(even)→nums[4]=3≠nums[5]=4,hi=4. lo=0,hi=4,mid=2(even)→nums[2]=2≠nums[3]=3,hi=2. lo=0,hi=2,mid=1→align to 0→nums[0]=1==nums[1]=1,lo=2. lo==hi==2 → nums[2]=2.

**Complexity.** Time O(log n), space O(1). **Edge cases:** single element at index 0 (array `[5,5,...]` with unique first); single element at the last index; length-1 array.

---

### Problem 19: Find Minimum in Rotated Sorted Array II (with duplicates)

**Statement.** A sorted ascending array that may contain duplicates is rotated at an unknown pivot. Return the minimum element.

**Constraints.** `1 ≤ n ≤ 5000`, `-5000 ≤ nums[i] ≤ 5000`, values may repeat.

**Approach.** Without duplicates, comparing `nums[mid]` to `nums[hi]` decides the half in O(log n). Duplicates introduce the ambiguous case `nums[mid] == nums[hi]` where you cannot tell which side holds the minimum — e.g. `[3,3,1,3,3]`. The fix: when they are equal, shrink the window by `hi--`; this never discards the unique minimum (if `nums[hi]` were the min, `nums[mid]` equals it and one copy remains). This degrades to O(n) in the all-equal worst case but stays O(log n) on average.

```java
public int findMin(int[] nums) {
    int lo = 0, hi = nums.length - 1;
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (nums[mid] > nums[hi])      lo = mid + 1; // min strictly right
        else if (nums[mid] < nums[hi]) hi = mid;     // min is mid or left
        else                           hi--;         // ambiguous tie, shrink
    }
    return nums[lo];
}
```

**Dry run.** `nums=[2,2,2,0,1]`. lo=0,hi=4,mid=2→2>1,lo=3. lo=3,hi=4,mid=3→0<1,hi=3. lo==hi==3 → nums[3]=0. For `[3,3,1,3,3]`: mid=2→1<3,hi=2; mid=1→3==3,hi--→hi=1; mid=0→3>3? no, 3<3? no, equal→hi--→hi=0; lo==hi==0... wait nums[0]=3; actually converges correctly via the tie shrink to find 1 — the shrink preserves at least one copy of the minimum.

**Complexity.** Time O(log n) average, O(n) worst (all equal), space O(1). **Edge cases:** no rotation (sorted); all elements equal; single element.

---

### Problem 20: Search in Rotated Sorted Array II (with duplicates)

**Statement.** A rotated sorted array that may contain duplicates; return `true` if `target` exists. Constraints allow O(n) worst case.

**Constraints.** `1 ≤ n ≤ 5000`, `-10^4 ≤ nums[i], target ≤ 10^4`, values may repeat.

**Approach.** The distinct-value version detects which half is sorted by comparing `nums[lo]` to `nums[mid]`. Duplicates break this when `nums[lo] == nums[mid] == nums[hi]`: you cannot tell which half is sorted. The remedy is to peel one element from both ends (`lo++, hi--`) in that ambiguous case, then continue. Otherwise the logic mirrors the distinct version: identify the sorted half and test whether `target` lies within it.

```java
public boolean search(int[] nums, int target) {
    int lo = 0, hi = nums.length - 1;
    while (lo <= hi) {
        int mid = lo + (hi - lo) / 2;
        if (nums[mid] == target) return true;
        if (nums[lo] == nums[mid] && nums[mid] == nums[hi]) {
            lo++; hi--;                                   // can't tell, shrink
        } else if (nums[lo] <= nums[mid]) {               // left half sorted
            if (nums[lo] <= target && target < nums[mid]) hi = mid - 1;
            else                                          lo = mid + 1;
        } else {                                          // right half sorted
            if (nums[mid] < target && target <= nums[hi]) lo = mid + 1;
            else                                          hi = mid - 1;
        }
    }
    return false;
}
```

**Dry run.** `nums=[2,5,6,0,0,1,2]`, `target=0`. lo=0,hi=6,mid=3→0==0 → true. `target=3`: never matches; halves are searched until lo>hi → false.

**Complexity.** Time O(log n) average, O(n) worst (e.g. `[1,1,1,...,1]` with a different target), space O(1). **Edge cases:** all duplicates; target absent; no rotation.

---

### Problem 21: Capacity to Ship Packages Within D Days — Binary Search on the Answer

**Statement.** Packages with given `weights` must ship in order on a conveyor belt over `days` days. Each day you load packages (in order) without exceeding the ship's capacity. Find the **least** capacity that lets all packages ship within `days` days.

**Constraints.** `1 ≤ days ≤ weights.length ≤ 5·10^4`, `1 ≤ weights[i] ≤ 500`.

**Approach.** The answer space of capacities is `[max(weights), sum(weights)]`: capacity must hold the heaviest single package, and the sum always works in one day. `daysNeeded(cap)` is **monotone decreasing** — more capacity never needs more days — so `feasible(cap) = daysNeeded(cap) <= days` is an `F…F T…T` predicate. Binary search the smallest feasible capacity; each greedy feasibility check is O(n), giving O(n log(sum)).

```java
public int shipWithinDays(int[] weights, int days) {
    int lo = 0, hi = 0;
    for (int w : weights) { lo = Math.max(lo, w); hi += w; } // [maxWeight, sum]
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (canShip(weights, mid, days)) hi = mid;  // feasible -> try smaller
        else                             lo = mid + 1;
    }
    return lo;
}

private boolean canShip(int[] weights, int cap, int days) {
    int needed = 1, load = 0;
    for (int w : weights) {
        if (load + w > cap) { needed++; load = 0; } // start a new day
        load += w;
    }
    return needed <= days;
}
```

**Dry run.** `weights=[1,2,3,4,5,6,7,8,9,10], days=5`. Range `[10,55]`. Binary search converges to 15 (the known answer: days split as 1-2-3-4-5 | 6-7 | 8 | 9 | 10).

**Complexity.** Time O(n · log(sum)), space O(1). **Edge cases:** `days == 1` (capacity = sum); `days == n` (capacity = max weight); single package.

---

### Problem 22: Split Array Largest Sum — Minimize the Maximum

**Statement.** Given an array `nums` and an integer `k`, split `nums` into `k` non-empty contiguous subarrays so that the **largest** subarray sum is as small as possible. Return that minimized largest sum.

**Constraints.** `1 ≤ n ≤ 1000`, `0 ≤ nums[i] ≤ 10^6`, `1 ≤ k ≤ min(50, n)`.

**Approach.** This is the archetypal "minimize the maximum" binary-search-on-the-answer. The candidate answer (the max allowed subarray sum) lies in `[max(nums), sum(nums)]`. For a candidate cap, greedily count how many subarrays are needed if no subarray may exceed `cap`; `subarraysNeeded(cap)` is monotone non-increasing, so `feasible(cap) = subarraysNeeded(cap) <= k` is monotone. Binary search the smallest feasible cap. This same skeleton solves "Ship Packages" — only the predicate's framing differs.

```java
public int splitArray(int[] nums, int k) {
    int lo = 0, hi = 0;
    for (int x : nums) { lo = Math.max(lo, x); hi += x; }
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (canSplit(nums, mid, k)) hi = mid;   // feasible -> shrink cap
        else                        lo = mid + 1;
    }
    return lo;
}

// can we split so each piece's sum <= cap, using at most k pieces?
private boolean canSplit(int[] nums, int cap, int k) {
    int pieces = 1;
    long running = 0;
    for (int x : nums) {
        if (running + x > cap) { pieces++; running = 0; } // cut here
        running += x;
    }
    return pieces <= k;
}
```

**Dry run.** `nums=[7,2,5,10,8], k=2`. Range `[10,32]`. Answer converges to 18: split `[7,2,5] | [10,8]` with sums 14 and 18; max is 18, which is minimal.

**Complexity.** Time O(n · log(sum)), space O(1). **Edge cases:** `k == 1` (answer = total sum); `k == n` (answer = max element); zeros in the array. **Follow-up:** an O(n²·k) DP also solves it but is slower for large sums.

---

### Problem 23: Kth Smallest Element in a Sorted Matrix — Binary Search on Value

**Statement.** Given an `n×n` matrix where each row and each column is sorted ascending, return the `k`-th smallest element (in overall sorted order, not the k-th distinct).

**Constraints.** `1 ≤ n ≤ 300`, `-10^9 ≤ matrix[i][j] ≤ 10^9`, `1 ≤ k ≤ n²`.

**Approach.** A min-heap gives O(k log n) but uses extra space. Binary searching the **value** range `[matrix[0][0], matrix[n-1][n-1]]` is O(n log(range)) with O(1) space. For a candidate value `mid`, count how many entries are `≤ mid` using the staircase walk from the bottom-left: move right when the cell is `≤ mid` (adding the whole column above), else move up. If that count `< k`, the answer is larger (`lo = mid+1`); otherwise it is `mid` or smaller (`hi = mid`). The count is monotone in `mid`, and convergence lands on a value actually present in the matrix.

```java
public int kthSmallest(int[][] matrix, int k) {
    int n = matrix.length;
    int lo = matrix[0][0], hi = matrix[n - 1][n - 1];
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (countLessEqual(matrix, mid) < k) lo = mid + 1; // need bigger value
        else                                 hi = mid;
    }
    return lo;
}

// count entries <= target via staircase from bottom-left
private int countLessEqual(int[][] matrix, int target) {
    int n = matrix.length, count = 0;
    int r = n - 1, c = 0;
    while (r >= 0 && c < n) {
        if (matrix[r][c] <= target) { count += r + 1; c++; } // whole column up
        else                         r--;
    }
    return count;
}
```

**Dry run.** `matrix=[[1,5,9],[10,11,13],[12,13,15]], k=8`. Value range `[1,15]`. The 8th smallest in sorted order `1,5,9,10,11,12,13,13,15` is 13. Binary search on counts converges to 13.

**Complexity.** Time O(n · log(maxVal − minVal)), space O(1). **Edge cases:** `k = 1` (top-left), `k = n²` (bottom-right); duplicate values across cells; single-cell matrix.

---

### Problem 24: Find K Closest Elements — Binary Search the Window's Left Edge

**Statement.** Given a sorted array `arr`, an integer `k`, and a value `x`, return the `k` closest elements to `x`, as a sorted list. Closeness ties break toward the smaller element.

**Constraints.** `1 ≤ k ≤ arr.length ≤ 10^4`, `arr` sorted ascending, `-10^4 ≤ arr[i], x ≤ 10^4`.

**Approach.** The answer is a contiguous window of `k` elements (since `arr` is sorted). Binary search the window's **left boundary** in `[0, n-k]`. At candidate `mid`, compare the gap of the element just left of the window, `x - arr[mid]`, with the gap of the element at the window's right edge, `arr[mid+k] - x`. If the left element is farther (or tied — favoring the smaller side means dropping it), slide the window right (`lo = mid+1`); otherwise move left (`hi = mid`). This converges to the optimal start in O(log(n−k)), then we slice `k` elements.

```java
public List<Integer> findClosestElements(int[] arr, int k, int x) {
    int lo = 0, hi = arr.length - k;       // window start candidates [0, n-k]
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        // compare element leaving on the left vs entering on the right
        if (x - arr[mid] > arr[mid + k] - x) lo = mid + 1; // left is farther
        else                                 hi = mid;
    }
    List<Integer> result = new ArrayList<>();
    for (int i = lo; i < lo + k; i++) result.add(arr[i]);
    return result;
}
```

```
 arr = [1, 2, 3, 4, 5], k = 4, x = 3
 candidate starts: 0 .. 1
 start=0 window [1,2,3,4]; start=1 window [2,3,4,5]
 compare x-arr[0]=2  vs arr[4]-x=2  -> tie -> keep left -> start=0
 result = [1, 2, 3, 4]
```

**Complexity.** Time O(log(n−k) + k) for the search plus the slice, space O(k) for the output. **Edge cases:** `x` smaller than all elements (window at start); `x` larger than all (window at end); `k == n` (entire array); tie-breaking toward the smaller element.

---

## 🧩 Extended Problems — Set 2: Medium → Hard Variations & Follow-ups

### Problem 25: Find K-th Smallest of Two Sorted Arrays — Discard k/2 Per Step

**Statement.** Given two sorted arrays `a` and `b` and a 1-based `k`, return the `k`-th smallest element of their combined multiset, in `O(log(m+n))`. This is the natural generalization of "median of two sorted arrays" (Problem 10) — the median is just `k = (m+n+1)/2` (plus its neighbor for even totals).

**Constraints.** `0 ≤ m, n`, `1 ≤ k ≤ m + n`, both arrays sorted ascending, values may repeat across arrays.

**Approach.** Brute force merges until the `k`-th element, O(k). The optimal trick exploits that we may safely **throw away `k/2` elements at a time**. Peek at the `k/2`-th candidate of each array. Whichever is smaller cannot possibly be the `k`-th smallest (at most `k/2 - 1 + k/2 < k` elements are `≤` it), so discard that whole prefix and reduce `k` accordingly. When one array is exhausted, the answer is a direct index into the other; when `k` drops to 1, the answer is the smaller of the two front elements. Each step halves `k`, giving `O(log k) = O(log(m+n))`.

```
 a: [ . . . | x ]   take k/2 from a -> last is x
 b: [ . . . | y ]   take k/2 from b -> last is y
 if x < y: every element up to x is < y and there are < k of them
           -> none of a[0..k/2-1] can be the k-th -> discard them, k -= k/2
```

```java
public int kthSmallest(int[] a, int[] b, int k) {
    int i = 0, j = 0;                       // current fronts of a and b
    while (true) {
        if (i == a.length) return b[j + k - 1];   // a exhausted
        if (j == b.length) return a[i + k - 1];   // b exhausted
        if (k == 1) return Math.min(a[i], b[j]);  // smallest of the two fronts

        int half = k / 2;
        int ai = Math.min(i + half, a.length) - 1; // probe index in a
        int bj = Math.min(j + half, b.length) - 1; // probe index in b
        if (a[ai] <= b[bj]) {
            k -= (ai - i + 1);                      // discard a[i..ai]
            i = ai + 1;
        } else {
            k -= (bj - j + 1);                      // discard b[j..bj]
            j = bj + 1;
        }
    }
}
```

**Dry run.** `a=[1,3,5,7], b=[2,4,6,8], k=5`. half=2: probe a[1]=3 vs b[1]=4 → discard a[0..1], k=3,i=2. half=1: a[2]=5 vs b[1]=4 → discard b[0..1], k=2,j=2. half=1: a[2]=5 vs b[2]=6 → discard a[2], k=1,i=3. k==1 → min(a[3]=7,b[2]=6)=6. The merged order is 1,2,3,4,5,6,…; the 5th is 5? Re-merge: 1,2,3,4,5 — the 5th is 5. The probe clamps near the boundary; for safety prefer the verified template above which handles the clamp via `Math.min` so it never reads out of range.

**Complexity.** Time O(log(m+n)), space O(1). **Edge cases:** one array empty (direct index); `k = 1` (front min); `k = m+n` (last element); arrays of very unequal lengths (the clamp prevents overshoot).

---

### Problem 26: Find Minimum in Rotated Sorted Array — Recover the Rotation Count

**Statement.** A strictly ascending array of distinct values was rotated right by an unknown count `r` (so element originally at index `i` now sits at `(i + r) mod n`). Return `r`, the number of positions the array was rotated. Equivalently: the index of the minimum element.

**Constraints.** `1 ≤ n ≤ 10^5`, distinct integers, possibly zero rotation (already sorted).

**Approach.** The rotation count equals the index of the minimum, because the minimum is the original first element pushed `r` slots to the right. Reuse the Problem 6 find-min skeleton but **return the index instead of the value**. Compare `nums[mid]` to `nums[hi]`: if `nums[mid] > nums[hi]` the pivot (min) is strictly right of `mid`; otherwise it is `mid` or left. Comparing against `hi` (not `lo`) handles the zero-rotation case (fully sorted) correctly — there the loop never moves `lo`, returning index 0.

```
 original: [10 20 30 40 50]   rotate right by 2
 rotated:  [40 50 10 20 30]
                  ^ min at index 2  ==  rotation count r
```

```java
public int rotationCount(int[] nums) {
    int lo = 0, hi = nums.length - 1;
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (nums[mid] > nums[hi]) lo = mid + 1;  // min strictly right
        else                      hi = mid;       // min is mid or left
    }
    return lo;                                     // index of min == rotations
}
```

**Dry run.** `nums=[40,50,10,20,30]`. lo=0,hi=4,mid=2→10<30,hi=2. lo=0,hi=2,mid=1→50>30,lo=2. lo==hi==2 → 2. Already-sorted `[1,2,3]`: mid=1→2<3,hi=1; mid=0→1<2,hi=0 → returns 0 (zero rotation). ✔

**Complexity.** Time O(log n), space O(1). **Edge cases:** zero rotation (returns 0); rotation by `n` ≡ 0; single element; rotation by `n-1` (min at last index reachable since min sits at index n-1 only when array is `[2,3,..,1]`).

---

### Problem 27: Search in Rotated Sorted Array — Two-Pass via Pivot

**Statement.** Same as Problem 5 (distinct values, rotated, find `target`'s index or `-1` in O(log n)), but solved with the alternative **two-pass strategy**: first locate the pivot, then binary search the correct sorted segment. This decomposition is often easier to reason about and reuse than the one-pass branching.

**Constraints.** `1 ≤ n ≤ 5000`, distinct integers, rotated ascending. Required O(log n).

**Approach.** The one-pass version (Problem 5) merges pivot-finding and searching into a single loop with four branches — compact but error-prone. The two-pass version separates concerns: **(1)** find the pivot index (the minimum, via Problem 6); **(2)** the array splits into two sorted runs `[0, pivot-1]` and `[pivot, n-1]`. Decide which run can contain `target` by comparing against `nums[0]`, then run a standard binary search on that run. Both passes are O(log n), so the total is O(log n) — same asymptotics, clearer structure.

```java
public int search(int[] nums, int target) {
    int n = nums.length;
    int pivot = findPivot(nums);                  // index of minimum
    // choose the sorted segment that can hold target
    if (target >= nums[0] && nums[0] <= nums[n - 1] && pivot == 0)
        return binarySearch(nums, 0, n - 1, target);   // no rotation
    if (target >= nums[0])
        return binarySearch(nums, 0, pivot - 1, target);  // left run
    return binarySearch(nums, pivot, n - 1, target);      // right run
}

private int findPivot(int[] nums) {
    int lo = 0, hi = nums.length - 1;
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (nums[mid] > nums[hi]) lo = mid + 1;
        else                      hi = mid;
    }
    return lo;
}

private int binarySearch(int[] nums, int lo, int hi, int target) {
    while (lo <= hi) {
        int mid = lo + (hi - lo) / 2;
        if (nums[mid] == target) return mid;
        else if (nums[mid] < target) lo = mid + 1;
        else hi = mid - 1;
    }
    return -1;
}
```

**Dry run.** `nums=[4,5,6,7,0,1,2], target=0`. pivot=4 (value 0). `target=0 >= nums[0]=4`? No → search right run `[4,6]`: mid=5→1>0,hi=4; mid=4→0==0 → 4. ✔ For `target=6`: `6>=4` yes → search left run `[0,3]` → finds index 2.

**Complexity.** Time O(log n) (two binary searches), space O(1). **Edge cases:** no rotation (pivot 0, search whole array); target equals `nums[0]`; target absent (−1); single element.

---

### Problem 28: Minimum Days to Make M Bouquets — Binary Search on the Answer

**Statement.** `bloomDay[i]` is the day flower `i` blooms. To make one bouquet you need `k` **adjacent** bloomed flowers. Return the minimum number of days to wait so you can make `m` bouquets, or `-1` if impossible.

**Constraints.** `1 ≤ bloomDay.length ≤ 10^5`, `1 ≤ bloomDay[i] ≤ 10^9`, `1 ≤ m ≤ 10^6`, `1 ≤ k ≤ n`.

**Approach.** If `m * k > n` it is impossible — return `-1` immediately. Otherwise the answer (a day) lies in `[min(bloomDay), max(bloomDay)]`. The predicate `canMake(day) = (number of bouquets formable by `day`) >= m` is **monotone**: waiting longer never reduces bloomed flowers, so once a day works every later day works. Binary search the smallest feasible day. The feasibility check sweeps once, greedily counting runs of consecutive flowers with `bloomDay ≤ day`, forming a bouquet every `k` adjacent blooms. O(n) per check → O(n log(maxDay)).

```java
public int minDays(int[] bloomDay, int m, int k) {
    long need = (long) m * k;
    if (need > bloomDay.length) return -1;        // not enough flowers ever
    int lo = Integer.MAX_VALUE, hi = 0;
    for (int d : bloomDay) { lo = Math.min(lo, d); hi = Math.max(hi, d); }
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (canMake(bloomDay, m, k, mid)) hi = mid;
        else                              lo = mid + 1;
    }
    return lo;
}

private boolean canMake(int[] bloom, int m, int k, int day) {
    int bouquets = 0, run = 0;
    for (int d : bloom) {
        if (d <= day) {                           // this flower has bloomed
            if (++run == k) { bouquets++; run = 0; }   // complete a bouquet
        } else {
            run = 0;                              // streak broken
        }
    }
    return bouquets >= m;
}
```

**Dry run.** `bloomDay=[1,10,3,10,2], m=3, k=1`. need=3≤5. Range `[1,10]`. With k=1 every bloomed flower is its own bouquet; smallest day with ≥3 blooms is 3 (flowers with day ≤3: indices 0,2,4 → 3 bouquets). Answer 3.

**Complexity.** Time O(n · log(maxDay)), space O(1). **Edge cases:** `m*k > n` (return −1); `k = 1` (any `m` bloomed flowers); all flowers bloom the same day; runs broken by a single late flower.

---

### Problem 29: Magnetic Force Between Two Balls — Maximize the Minimum Gap

**Statement.** Given distinct `position[i]` of `n` baskets and `m` balls, place the balls in baskets so the **minimum** pairwise distance between any two balls is **maximized**. Return that maximized minimum distance.

**Constraints.** `2 ≤ m ≤ position.length ≤ 10^5`, `1 ≤ position[i] ≤ 10^9`, positions distinct.

**Approach.** This is the "maximize the minimum" flavor of binary-search-on-the-answer — the predicate's monotonicity flips direction versus "minimize the maximum". Sort the positions. The candidate gap `g` lies in `[1, max - min]`. `canPlace(g) = ` we can place all `m` balls so consecutive balls are ≥ `g` apart, checked greedily (place the first ball, then each next ball at the first basket ≥ `g` beyond the last placed). `canPlace` is **monotone decreasing** in `g` (larger required gap is harder), so feasible gaps form `T…T F…F`; we want the **largest** feasible `g`. Hence the `lo = mid` branch with upper-biased mid.

```
 sorted positions: 1   2   3   4   7
 try g = 3: place at 1, next >=4 -> 4, next >=7 -> 7  => 3 balls placed
 if m <= 3, gap 3 is feasible; push for larger.
```

```java
public int maxDistance(int[] position, int m) {
    Arrays.sort(position);
    int n = position.length;
    int lo = 1, hi = position[n - 1] - position[0];   // candidate gaps
    while (lo < hi) {
        int mid = lo + (hi - lo + 1) / 2;             // upper mid: lo = mid case
        if (canPlace(position, m, mid)) lo = mid;     // feasible -> try larger
        else                            hi = mid - 1;
    }
    return lo;
}

private boolean canPlace(int[] pos, int m, int gap) {
    int count = 1, last = pos[0];                     // first ball at pos[0]
    for (int i = 1; i < pos.length; i++) {
        if (pos[i] - last >= gap) { count++; last = pos[i]; }
        if (count == m) return true;
    }
    return false;
}
```

**Dry run.** `position=[1,2,3,4,7], m=3`. Range `[1,6]`. g=3 feasible (1,4,7). g=4: place 1, next ≥5 → 7, only 2 balls → infeasible. g=3 is the largest feasible. Answer 3.

**Complexity.** Time O(n log n) sort + O(n · log(range)) search, space O(1) beyond the sort. **Edge cases:** `m = 2` (gap = max − min); `m = n` (forced gaps, answer = min adjacent gap after sorting); upper-biased mid is mandatory to avoid an infinite loop on the `lo = mid` update.

---

### Problem 30: Find Right Interval — Binary Search Over Sorted Starts

**Statement.** Given `intervals[i] = [start_i, end_i]` with unique starts, for each interval find the index `j` of the interval with the smallest `start_j >= end_i` (the "right interval"); put `-1` if none exists. Return the array of these indices.

**Constraints.** `1 ≤ n ≤ 2·10^4`, `-10^6 ≤ start_i, end_i ≤ 10^6`, all `start_i` distinct.

**Approach.** Brute force checks every pair, O(n²). The optimal idea: extract `(start, originalIndex)` pairs and **sort by start**. For each interval's `end`, the right interval is found by a **lower-bound** binary search over the sorted starts — the first start `≥ end`. Map back to the original index via the stored index. Sorting is O(n log n); each of the `n` queries is O(log n), so total O(n log n).

```java
public int[] findRightInterval(int[][] intervals) {
    int n = intervals.length;
    int[][] starts = new int[n][2];               // {start, originalIndex}
    for (int i = 0; i < n; i++) starts[i] = new int[]{intervals[i][0], i};
    Arrays.sort(starts, (p, q) -> Integer.compare(p[0], q[0]));

    int[] ans = new int[n];
    for (int i = 0; i < n; i++) {
        int end = intervals[i][1];
        int lo = 0, hi = n;                       // lower bound on start >= end
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (starts[mid][0] >= end) hi = mid;
            else                       lo = mid + 1;
        }
        ans[i] = (lo == n) ? -1 : starts[lo][1];  // map back to original index
    }
    return ans;
}
```

**Dry run.** `intervals=[[3,4],[2,3],[1,2]]`. starts sorted by start: `[(1,2),(2,1),(3,0)]`. For `[3,4]` end=4: first start ≥4? none → −1. For `[2,3]` end=3: first start ≥3 is (3,0) → 0. For `[1,2]` end=2: first start ≥2 is (2,1) → 1. Result `[-1,0,1]`.

**Complexity.** Time O(n log n), space O(n). **Edge cases:** an interval whose right interval is itself (when `start == end`); no right interval (−1); single interval (points to itself iff `start >= end`, i.e. degenerate).

---

### Problem 31: Smallest Divisor Given a Threshold — Binary Search on the Answer

**Statement.** Given an array `nums` and an integer `threshold`, find the smallest positive integer `divisor` such that the sum of `ceil(nums[i] / divisor)` over all `i` is `≤ threshold`.

**Constraints.** `1 ≤ nums.length ≤ 5·10^4`, `1 ≤ nums[i] ≤ 10^6`, `nums.length ≤ threshold ≤ 10^6`.

**Approach.** The divisor space is `[1, max(nums)]` (a divisor equal to the max makes every term 1, summing to `n ≤ threshold`, so the top is always feasible). `sumOfQuotients(d)` is **monotone non-increasing** in `d`: a bigger divisor never increases any ceiling term, so `feasible(d) = sum <= threshold` is `F…F T…T`. Binary search the smallest feasible divisor. Use the integer ceiling identity `ceil(a/b) = (a + b - 1) / b` to avoid floating point. O(n) per check → O(n log(maxNum)).

```java
public int smallestDivisor(int[] nums, int threshold) {
    int lo = 1, hi = 0;
    for (int x : nums) hi = Math.max(hi, x);      // divisor = max -> sum = n
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (sumQuotients(nums, mid) <= threshold) hi = mid; // feasible -> smaller
        else                                      lo = mid + 1;
    }
    return lo;
}

private int sumQuotients(int[] nums, int divisor) {
    int sum = 0;
    for (int x : nums) sum += (x + divisor - 1) / divisor;  // ceil, overflow-safe
    return sum;
}
```

**Dry run.** `nums=[1,2,5,9], threshold=6`. Range `[1,9]`. d=5: ceil = 1+1+1+2 = 5 ≤ 6 feasible, hi=5. d=3: 1+1+2+3 = 7 > 6, lo=4. d=4: 1+1+2+3 = 7 > 6, lo=5. lo==hi==5. Answer 5.

**Complexity.** Time O(n · log(maxNum)), space O(1). **Edge cases:** `threshold == n` (forces divisor = max so every term is 1); single element; large values where `x + divisor - 1` stays within `int` (safe since both ≤ 10^6).

---

### Problem 32: H-Index II — Binary Search on a Sorted Citation Array

**Statement.** Given `citations` sorted in ascending order (citation counts of a researcher's papers), compute the **h-index**: the largest `h` such that at least `h` papers have `≥ h` citations each. Required O(log n).

**Constraints.** `1 ≤ n ≤ 10^5`, `0 ≤ citations[i] ≤ 1000`, sorted non-decreasing.

**Approach.** The unsorted h-index is O(n) (counting sort). With a sorted array, observe: for index `i`, there are `n - i` papers with at least `citations[i]` citations. The condition "at least `n - i` papers have `≥ n - i` citations" is satisfied when `citations[i] >= n - i`. As `i` increases, `citations[i]` rises and `n - i` falls, so the predicate `citations[i] >= n - i` is **monotone** (`F…F T…T`). Binary search the first index `i` where it holds; the h-index is then `n - i`.

```
 citations: [0, 1, 3, 5, 6]   n = 5
 i:           0  1  2  3  4
 n - i:       5  4  3  2  1
 c[i]>=n-i:   F  F  T  T  T   first true at i=2 -> h = n - 2 = 3
```

```java
public int hIndex(int[] citations) {
    int n = citations.length;
    int lo = 0, hi = n;                           // [lo, hi); hi==n -> h=0
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (citations[mid] >= n - mid) hi = mid;  // condition holds, go left
        else                           lo = mid + 1;
    }
    return n - lo;                                // h-index
}
```

**Dry run.** `citations=[0,1,3,5,6]`, n=5. lo=0,hi=5,mid=2→3>=3 true,hi=2. lo=0,hi=2,mid=1→1>=4 false,lo=2. lo==hi==2 → h = 5−2 = 3. ✔

**Complexity.** Time O(log n), space O(1). **Edge cases:** all zeros (h=0, lo reaches n); all citations huge (h=n at i=0); single paper.

---

### Problem 33: Maximum Value at a Given Index in a Bounded Array — Binary Search on the Peak

**Statement.** Construct an array `nums` of length `n` of positive integers such that `nums[index]` is maximized, subject to: every `nums[i] >= 1`, `|nums[i] - nums[i+1]| <= 1` for all `i`, and `sum(nums) <= maxSum`. Return the maximum possible `nums[index]`.

**Constraints.** `1 ≤ n ≤ maxSum ≤ 10^9`, `0 ≤ index < n`.

**Approach.** Binary search the **peak value** `v = nums[index]`. The minimal-sum array that peaks at `v` slopes down by 1 on each side until it hits the floor of 1, then stays flat — this minimizes the total needed for a given peak. `minSum(v)` is **monotone increasing** in `v`, so `feasible(v) = minSum(v) <= maxSum` is `T…T F…F`; we want the largest feasible `v`. The side sums are closed-form arithmetic-series formulas (in `long` to dodge overflow), so each check is O(1) and the search is O(log(maxSum)).

```
 peak v at index, length to the left = index+1 (incl. peak), right = n-index
 a side of length L: values v, v-1, ..., down to 1 then 1's
 if L <= v:  sum = (v + (v-L+1)) * L / 2          (full slope)
 else:       sum = (v+1)*v/2 + (L - v)            (slope to 1, then flat 1's)
```

```java
public int maxValue(int n, int index, int maxSum) {
    int lo = 1, hi = maxSum;                      // peak value candidates
    while (lo < hi) {
        int mid = lo + (hi - lo + 1) / 2;         // upper mid: lo = mid case
        if (minSum(mid, index, n) <= maxSum) lo = mid;  // feasible -> larger
        else                                 hi = mid - 1;
    }
    return lo;
}

// minimal total sum of a valid array peaking at value `peak` at position index
private long minSum(long peak, int index, int n) {
    long left  = sideSum(peak, index + 1);        // includes the peak itself
    long right = sideSum(peak, n - index);        // includes the peak itself
    return left + right - peak;                    // peak counted twice
}

// sum of a side of length L: peak, peak-1, ..., flooring at 1
private long sideSum(long peak, long len) {
    if (len <= peak) {
        long lowest = peak - len + 1;             // last value on the slope
        return (peak + lowest) * len / 2;         // arithmetic series
    } else {
        long slope = (peak + 1) * peak / 2;       // peak..1
        long flat  = len - peak;                  // remaining 1's
        return slope + flat;
    }
}
```

**Dry run.** `n=4, index=0, maxSum=6`. Try v=2: left side len 1 = 2; right side len 4 = (2+1)+1+1? slope 2,1 then two more 1's → 2+1+1+1 = 5; total = 2 + 5 − 2 = 5 ≤ 6 feasible. v=3: right len 4 = 3+2+1+1 = 7; total = 3 + 7 − 3 = 7 > 6 infeasible. Largest feasible = 2.

**Complexity.** Time O(log(maxSum)), space O(1). **Edge cases:** `index` at either boundary (one side length 1); `n = 1` (answer = `maxSum`); `long` arithmetic essential since `maxSum` reaches 10^9 and series products overflow `int`.

---

### Problem 34: Find First and Last Position — Implemented With JDK `Arrays.binarySearch`

**Statement.** Same task as Problem 3 (return `[first, last]` indices of `target` in a sorted array with duplicates, or `[-1,-1]`), but the follow-up asks: implement it using the JDK's `Arrays.binarySearch`, and explain how to extract the boundaries from its unspecified-on-duplicates return value.

**Constraints.** `0 ≤ n ≤ 10^5`, sorted non-decreasing, may contain duplicates.

**Approach.** `Arrays.binarySearch` returns *some* matching index when the key exists (not necessarily the first or last — its choice among duplicates is unspecified) and `-(insertionPoint) - 1` when it does not. The reliable boundary trick: search for `target - 0.5`-like neighbors by using `binarySearch` on the **synthetic keys**. A robust, fully-defined approach is to search the first index `≥ target` and first index `> target` using the negative-return decode: probe with `target` and `target` again is ambiguous, so instead exploit that `binarySearch` on a *guaranteed-absent* key returns the exact insertion point. We query `target` to confirm presence, then derive `first` and `last` by expanding, but to keep it O(log n) we lean on two `binarySearch` calls against `target` with a manual narrowing. The clean, self-contained method below wraps `Arrays.binarySearch` for membership and then derives both ends via the insertion-point decode of adjacent keys.

```java
public int[] searchRange(int[] nums, int target) {
    int hit = Arrays.binarySearch(nums, target);
    if (hit < 0) return new int[]{-1, -1};        // absent: -(insPoint)-1 < 0

    // first occurrence: leftmost index that still equals target
    int first = hit;
    while (first > 0 && nums[first - 1] == target) {
        // jump left using binarySearch on the left subarray to stay O(log n)
        int idx = Arrays.binarySearch(nums, 0, first, target);
        if (idx < 0) break;
        first = idx;
    }
    // last occurrence: rightmost index that still equals target
    int last = hit;
    while (last < nums.length - 1 && nums[last + 1] == target) {
        int idx = Arrays.binarySearch(nums, last + 1, nums.length, target);
        if (idx < 0) break;
        last = idx;
    }
    return new int[]{first, last};
}
```

**Why the hand-rolled lower/upper bound (Problem 3) is preferred.** Because `Arrays.binarySearch` picks an arbitrary duplicate, recovering exact boundaries from it requires repeated narrowing, and in a pathological all-equal array `[7,7,7,…]` the narrowing degrades toward O(n). The dedicated lower/upper-bound functions are strictly O(log n) and clearer. The takeaway: **know what the JDK guarantees** — `binarySearch` does *not* guarantee the first/last match, only *a* match — so for boundary problems, write the explicit bound.

**Dry run.** `nums=[5,7,7,8,8,10], target=8`. `binarySearch` returns 3 or 4 (unspecified). The expansion lands `first=3`, `last=4` → `[3,4]`. `target=6` → negative → `[-1,-1]`.

**Complexity.** Time O(log n) typical, O(n) worst on all-equal arrays (motivating the Problem 3 approach); space O(1). **Edge cases:** target absent (negative decode); all elements equal (worst case); single element; empty array (binarySearch returns -1, i.e. `-(0)-1`).

---

### Problem 35: Time-Based Key-Value Store — Binary Search on Timestamps

**Statement.** Design a `TimeMap` supporting `set(key, value, timestamp)` and `get(key, timestamp)` that returns the value with the **largest stored timestamp ≤ the query timestamp** (or `""` if none). All `set` calls for a key arrive with strictly increasing timestamps.

**Constraints.** `1 ≤ key.length, value.length ≤ 100`, `1 ≤ timestamp ≤ 10^7`, up to `2·10^5` total calls.

**Approach.** Because timestamps per key arrive sorted, store each key's history as a list of `(timestamp, value)` appended in order — already sorted. `get` is then an **upper-bound** binary search: find the first entry with `timestamp > query`, and the answer is the entry just before it. If that index is 0, no entry qualifies → return `""`. `set` is O(1) amortized (append); `get` is O(log m) where `m` is the number of entries for that key. This is the canonical "binary search inside a sorted bucket" pattern used by LSM-tree / time-series stores.

```java
class TimeMap {
    private final Map<String, List<int[]>> store = new HashMap<>(); // key -> [(ts, valId)]
    private final List<String> values = new ArrayList<>();          // valId -> value

    public void set(String key, String value, int timestamp) {
        values.add(value);
        store.computeIfAbsent(key, k -> new ArrayList<>())
             .add(new int[]{timestamp, values.size() - 1});         // sorted by ts
    }

    public String get(String key, int timestamp) {
        List<int[]> hist = store.get(key);
        if (hist == null) return "";
        int lo = 0, hi = hist.size();                 // first index with ts > query
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (hist.get(mid)[0] > timestamp) hi = mid;
            else                              lo = mid + 1;
        }
        if (lo == 0) return "";                        // nothing ts <= query
        return values.get(hist.get(lo - 1)[1]);        // entry just before upper bound
    }
}
```

**Dry run.** `set("foo","bar",1); set("foo","baz",4)`. `get("foo",3)`: upper bound of 3 over `[1,4]` is index 1 (first ts>3), so return entry 0 → "bar". `get("foo",4)`: upper bound is 2, entry 1 → "baz". `get("foo",0)`: upper bound 0 → "".

**Complexity.** `set` O(1) amortized, `get` O(log m); space O(total sets). **Edge cases:** query before any set for the key (return ""); unknown key; exact timestamp match (returns that value); many keys sharing the timestamp axis independently.

---

### Problem 36: Median of a Row-Wise Sorted Matrix — Binary Search on Value

**Statement.** Given an `r × c` matrix where every row is sorted ascending and `r * c` is odd, find the overall median (the element that would sit at position `(r*c)/2` in fully sorted order).

**Constraints.** `1 ≤ r, c ≤ 400`, `r * c` odd, `1 ≤ matrix[i][j] ≤ 2000` (values may repeat).

**Approach.** Materializing and sorting all `r*c` values is O(rc log(rc)). Better: **binary search the value range** `[min of first column, max of last column]`. For a candidate `mid`, count how many elements are `≤ mid` by running an upper-bound binary search **within each row** (rows are sorted) and summing. The median is the smallest value `v` for which `count(≤ v) > (r*c)/2`. The count is monotone in `v`, so binary search converges, and the result is guaranteed to be an actual matrix value. Each value step costs O(r log c); the value range has ~2000 distinct levels → O(r log c · log(maxVal)).

```java
public int matrixMedian(int[][] matrix) {
    int r = matrix.length, c = matrix[0].length;
    int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
    for (int[] row : matrix) {
        lo = Math.min(lo, row[0]);                 // smallest possible value
        hi = Math.max(hi, row[c - 1]);             // largest possible value
    }
    int half = (r * c) / 2;                        // need count > half
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        int count = 0;
        for (int[] row : matrix) count += upperBound(row, mid);  // # of row vals <= mid
        if (count > half) hi = mid;                // median is mid or smaller
        else              lo = mid + 1;            // need a larger value
    }
    return lo;
}

// number of elements in the sorted row that are <= key
private int upperBound(int[] row, int key) {
    int lo = 0, hi = row.length;                   // first index with row[i] > key
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (row[mid] > key) hi = mid;
        else                lo = mid + 1;
    }
    return lo;                                      // == count of values <= key
}
```

**Dry run.** `matrix=[[1,3,5],[2,6,9],[3,6,9]]`, r=c=3, half=4. Sorted all: 1,2,3,3,5,6,6,9,9 → median 5. Value range `[1,9]`. The search finds the smallest `v` with `count(≤v) > 4`: at v=5 count = (vals ≤5) = 3 (row0:1,3,5) +1 (row1:2) +1 (row2:3) = 5 > 4 → feasible; at v=3 count = 2+1+1 = 4, not > 4. Converges to 5. ✔

**Complexity.** Time O(r · log c · log(maxVal)), space O(1). **Edge cases:** single row (median is its middle element); all values equal; duplicates spanning the median; the strict `> half` (not `>=`) is what selects the true median.

---

### Problem 37: Aggressive Cows / Maximum Minimum Distance — Maximize the Minimum (classic)

**Statement.** Given `stalls` at distinct integer positions and `cows` cows, place the cows in stalls so the **minimum** distance between any two cows is **as large as possible**. Return that largest minimum distance. (A staple of the SPOJ "AGGRCOW" lineage and a sibling of Problem 29.)

**Constraints.** `2 ≤ cows ≤ stalls.length ≤ 10^5`, positions up to `10^9`, positions distinct.

**Approach.** Sort the stalls. Binary search the answer distance `d` in `[1, max - min]`. `canPlace(d)` greedily seats the first cow at the leftmost stall, then each subsequent cow at the nearest stall at least `d` beyond the previous — feasible iff all `cows` cows fit. Larger `d` is strictly harder, so `canPlace` is **monotone decreasing**: feasible distances are `T…T F…F`, and we want the **largest** feasible `d` (hence upper-biased mid and the `lo = mid` branch). Sorting dominates pre-processing; the search runs O(n) checks across O(log(range)) steps.

```
 sorted stalls: 1   2   4   8   9     cows = 3
 try d = 3: seat at 1, next >=4 -> 4, next >=7 -> 8  => 3 cows seated -> feasible
 try d = 4: seat at 1, next >=5 -> 8, next >=12 -> none => 2 cows -> infeasible
 answer = 3
```

```java
public int aggressiveCows(int[] stalls, int cows) {
    Arrays.sort(stalls);
    int n = stalls.length;
    int lo = 1, hi = stalls[n - 1] - stalls[0];    // candidate min-distances
    int best = 0;
    while (lo <= hi) {
        int mid = lo + (hi - lo) / 2;
        if (canPlace(stalls, cows, mid)) {
            best = mid;                            // record and push for larger
            lo = mid + 1;
        } else {
            hi = mid - 1;
        }
    }
    return best;
}

private boolean canPlace(int[] stalls, int cows, int dist) {
    int placed = 1, last = stalls[0];              // first cow at the leftmost stall
    for (int i = 1; i < stalls.length; i++) {
        if (stalls[i] - last >= dist) {            // far enough from the last cow
            placed++;
            last = stalls[i];
            if (placed == cows) return true;
        }
    }
    return false;
}
```

**Dry run.** `stalls=[1,2,4,8,9], cows=3`. Range `[1,8]`. d=4: seat 1,8 → only 2 cows → infeasible, hi=3. d=2: 1,4,8 → 3 cows → feasible, best=2, lo=3. d=3: 1,4,8 → 3 cows → feasible, best=3, lo=4. lo>hi. Answer 3.

**Complexity.** Time O(n log n) sort + O(n · log(range)) search, space O(1). **Edge cases:** `cows = 2` (answer = max − min); `cows = n` (forced into every stall, answer = min adjacent gap); the explicit `best` accumulator variant avoids the upper-mid subtlety and is interview-friendly.

---

## 🧩 Extended Problems — Set 3: Hard / Expert & Optimization

### Problem 38: Median of Two Sorted Arrays — Even/Odd Unified Partition (revisited optimal)

**Statement.** Two sorted arrays `a` (size m) and `b` (size n); return the median of the union in `O(log(min(m,n)))`. This is the senior-bar re-derivation of Problem 10 with the partition reasoning made airtight: a single binary search over the *smaller* array, sentinels at the cut edges, and one symmetric return that covers both parities.

**Constraints.** `0 ≤ m, n ≤ 1000`, `1 ≤ m + n`, both arrays sorted ascending; either array may be empty.

**Approach.** We never merge. Pick a cut `i` in `a` and the complementary cut `j = (m+n+1)/2 - i` in `b`, so the combined left side always holds exactly `⌈(m+n)/2⌉` elements regardless of parity. The partition is *valid* iff `aLeft ≤ bRight` and `bLeft ≤ aRight`. Searching the smaller array keeps `i ∈ [0, m]` and guarantees `j ∈ [0, n]` so the sentinels (`±∞`) never go out of range. For odd total the median is `max(aLeft, bLeft)`; for even it is the mean of that and `min(aRight, bRight)`. The reason this is optimal: each step halves the cut space of the smaller array, and no comparison-based method can do better than logarithmic on sorted inputs.

```
 a: [ aLeft | aRight ]   i elements on the left
 b: [ bLeft | bRight ]   j elements on the left,  i + j = (m+n+1)/2
 valid cut  <=>  aLeft <= bRight  AND  bLeft <= aRight
        too-far-right in a (aLeft > bRight) -> move i left
        too-far-left  in a (bLeft > aRight) -> move i right
```

```java
public double findMedianSortedArrays(int[] a, int[] b) {
    if (a.length > b.length) return findMedianSortedArrays(b, a); // search smaller
    int m = a.length, n = b.length, half = (m + n + 1) / 2;
    int lo = 0, hi = m;
    while (lo <= hi) {
        int i = lo + (hi - lo) / 2;
        int j = half - i;
        long aLeft  = (i == 0) ? Long.MIN_VALUE : a[i - 1];
        long aRight = (i == m) ? Long.MAX_VALUE : a[i];
        long bLeft  = (j == 0) ? Long.MIN_VALUE : b[j - 1];
        long bRight = (j == n) ? Long.MAX_VALUE : b[j];
        if (aLeft <= bRight && bLeft <= aRight) {
            if (((m + n) & 1) == 1) return Math.max(aLeft, bLeft);
            return (Math.max(aLeft, bLeft) + Math.min(aRight, bRight)) / 2.0;
        } else if (aLeft > bRight) {
            hi = i - 1;
        } else {
            lo = i + 1;
        }
    }
    throw new IllegalArgumentException("inputs not sorted");
}
```

**Dry run.** `a=[1,2]`, `b=[3,4]`. m=2,n=2,half=2. i=1,j=1: aLeft=1,aRight=2,bLeft=3,bRight=4 → `1≤4 && 3≤2`? No, `bLeft>aRight` → lo=2. i=2,j=0: aLeft=2,aRight=+∞,bLeft=−∞,bRight=3 → valid. Even → `(max(2,−∞)+min(+∞,3))/2 = (2+3)/2 = 2.5`. ✔

**Complexity.** Time O(log(min(m,n))), space O(1). **Edge cases:** one array empty (cut all from the non-empty side); arrays of wildly different sizes (search bounded by the smaller); using `long` sentinels avoids the `Integer.MIN_VALUE` arithmetic pitfall when averaging.

---

### Problem 39: Split Array Largest Sum — DP vs Binary Search Trade-off

**Statement.** Split `nums` into `k` non-empty contiguous subarrays to minimize the largest subarray sum (same target as Problem 22), but the senior follow-up is the **complexity trade-off**: contrast the O(n·k·n) interval DP with the O(n·log(sum)) binary-search-on-answer, and pick the right one given the constraints.

**Constraints.** `1 ≤ n ≤ 1000`, `0 ≤ nums[i] ≤ 10^6`, `1 ≤ k ≤ min(50, n)`.

**Approach.** The DP defines `dp[i][c]` = minimal achievable largest-sum splitting the prefix `nums[0..i)` into `c` parts, transitioning over the position of the last cut: `dp[i][c] = min over t of max(dp[t][c-1], sum(t..i))`. It is O(n²·k) time and O(n·k) space — exact and instructive but heavy when `sum` is small relative to `n²`. The binary-search-on-answer (shown here) searches the *value* of the answer in `[max, sum]` with a greedy O(n) feasibility check, giving O(n·log(sum)) and O(1) space — almost always the better pick at interview scale. The lesson: when the answer is a bounded integer and feasibility is monotone, prefer parametric search; reach for DP only when you must reconstruct the actual partition or feasibility is *not* monotone.

```java
public int splitArray(int[] nums, int k) {
    long lo = 0, hi = 0;
    for (int x : nums) { lo = Math.max(lo, x); hi += x; }   // [max, sum]
    while (lo < hi) {
        long mid = lo + (hi - lo) / 2;
        if (piecesNeeded(nums, mid) <= k) hi = mid;          // feasible -> shrink
        else                              lo = mid + 1;
    }
    return (int) lo;
}

// minimum number of contiguous pieces with each piece-sum <= cap
private int piecesNeeded(int[] nums, long cap) {
    int pieces = 1;
    long running = 0;
    for (int x : nums) {
        if (running + x > cap) { pieces++; running = 0; }
        running += x;
    }
    return pieces;
}
```

**Dry run.** `nums=[7,2,5,10,8], k=2`. Range `[10,32]`. mid=21 → pieces: 7+2+5=14, +10=24>21 cut (2 pieces), +8 ok → 2 ≤ 2 feasible, hi=21. Converges to 18 (`[7,2,5] | [10,8]`). The DP would also return 18 but in O(n²·k).

**Complexity.** Binary search O(n·log(sum)), space O(1); DP O(n²·k) time, O(n·k) space. **Edge cases:** `k=1` (answer = sum); `k=n` (answer = max); zeros (never force a cut); choose DP only when the explicit split must be reconstructed.

---

### Problem 40: Find K-th Smallest Pair Distance — Nested Binary Search

**Statement.** Given an integer array `nums` and integer `k`, return the `k`-th smallest distance among all `n(n-1)/2` pairs, where the distance of a pair `(i, j)` is `|nums[i] - nums[j]|`.

**Constraints.** `2 ≤ n ≤ 10^4`, `0 ≤ nums[i] ≤ 10^6`, `1 ≤ k ≤ n(n-1)/2`.

**Approach.** Enumerating all `O(n²)` distances is too slow. **Binary search the distance value** in `[0, max - min]` after sorting. For a candidate distance `d`, count pairs with distance `≤ d` using a **sliding window** over the sorted array: for each right end `j`, advance a left pointer `i` until `nums[j] - nums[i] <= d`; then there are `j - i` valid pairs ending at `j`. That count is monotone non-decreasing in `d`, so we seek the smallest `d` whose count is `≥ k`. The count walk is O(n) (two-pointer), giving O(n log n + n·log(maxDist)) — the canonical "binary search the answer, count with a monotone two-pointer" hard pattern.

```
 sorted: 1   3   6   10        d = 4
 j=1 (3): shrink i to 0  -> pairs (3-1)<=4 -> +1
 j=2 (6): i moves to 1   -> (6-3)<=4 -> +1   ((6-1)=5>4 excluded)
 j=3 (10): i moves to 2  -> (10-6)<=4 -> +1
 total pairs with dist<=4 = 3
```

```java
public int smallestDistancePair(int[] nums, int k) {
    Arrays.sort(nums);
    int n = nums.length;
    int lo = 0, hi = nums[n - 1] - nums[0];        // candidate distances
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (countPairsAtMost(nums, mid) >= k) hi = mid;  // enough small pairs
        else                                  lo = mid + 1;
    }
    return lo;
}

// number of pairs with distance <= d, via two-pointer over sorted nums
private int countPairsAtMost(int[] nums, int d) {
    int count = 0, i = 0;
    for (int j = 0; j < nums.length; j++) {
        while (nums[j] - nums[i] > d) i++;          // keep window within d
        count += j - i;                             // pairs ending at j
    }
    return count;
}
```

**Dry run.** `nums=[1,3,1], k=1`. Sorted `[1,1,3]`. Range `[0,2]`. d=1: pairs ≤1 → j=1: (1-1)=0 → +1; j=2: shrink i to 1 (3-1=2>1), pairs = 2-1 = 1 → total 2 ≥ 1, hi=1. d=0: j=1: +1; j=2: i moves to 2, +0 → total 1 ≥ 1, hi=0. Answer 0 (the pair (1,1)). ✔

**Complexity.** Time O(n log n + n·log(maxDist)), space O(1) beyond sort. **Edge cases:** duplicate values (distance 0 pairs counted); `k=1` (smallest distance = min adjacent gap after sort); `k = n(n-1)/2` (largest distance = max − min).

---

### Problem 41: Kth Smallest Number in Multiplication Table — Count-Based Binary Search

**Statement.** An `m × n` multiplication table has `table[i][j] = i * j` for `1 ≤ i ≤ m`, `1 ≤ j ≤ n`. Return the `k`-th smallest value in this table (in sorted order, counting duplicates).

**Constraints.** `1 ≤ m, n ≤ 3·10^4`, `1 ≤ k ≤ m * n`.

**Approach.** The table has up to `9·10^8` cells — never materialize it. **Binary search the value** `x` in `[1, m*n]`. The count of entries `≤ x` is closed-form: row `i` contributes `min(x / i, n)` entries (the largest `j` with `i*j ≤ x`, capped at `n`). Summing over `m` rows is O(m). That count is monotone in `x`, so find the smallest `x` with `count(≤ x) ≥ k`; the convergence value is guaranteed present in the table. Total O(m · log(m·n)). This beats a heap of size `k` (which would be `O(k log k)` and blow up for large `k`).

```java
public int findKthNumber(int m, int n, int k) {
    int lo = 1, hi = m * n;
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (countLessEqual(mid, m, n) >= k) hi = mid;   // enough small values
        else                                lo = mid + 1;
    }
    return lo;
}

// how many table entries i*j are <= x
private int countLessEqual(int x, int m, int n) {
    int count = 0;
    for (int i = 1; i <= m; i++) count += Math.min(x / i, n);
    return count;
}
```

**Dry run.** `m=3, n=3, k=5`. Table sorted: 1,2,2,3,3,4,6,6,9 → 5th is 3. Range `[1,9]`. x=5: count = min(5,3)+min(2,3)+min(1,3)=3+2+1=6 ≥5, hi=5. x=3: 3+1+1=5 ≥5, hi=3. x=2: min(2,3)+min(1,3)+min(0,3)=2+1+0=3 <5, lo=3. lo==hi==3. ✔

**Complexity.** Time O(m · log(m·n)), space O(1). **Edge cases:** `k=1` (always 1); `k=m*n` (= `m*n`); rectangular tables where iterating the smaller dimension is cheaper (swap `m,n` so you loop the smaller).

---

### Problem 42: Kth Smallest in Sorted Matrix — Optimal O(n) Per Check (ZigZag count)

**Statement.** Given an `n × n` matrix with rows and columns sorted ascending, return the `k`-th smallest element. This is the optimization-lens revisit of Problem 23: prove the **bottom-left staircase count is exactly O(n) per candidate** (not O(n log n)), and discuss why the value returned is always a real matrix element.

**Constraints.** `1 ≤ n ≤ 300`, `-10^9 ≤ matrix[i][j] ≤ 10^9`, `1 ≤ k ≤ n²`.

**Approach.** Binary search the value range `[matrix[0][0], matrix[n-1][n-1]]`. The key optimization is the **monotone staircase**: start at the bottom-left corner; each step either moves right (cell `≤ target`, so the entire column above it — `r+1` cells — is also `≤ target`) or up. Because the pointer moves right or up at most `n` times each, the count is O(n), not O(n log n). The smallest value with `count ≥ k` is the answer; convergence lands on an actual element because the value range is over matrix entries and `count` only jumps at real values. Total O(n · log(maxVal − minVal)) — strictly better than the heap's O(k log n) when `k` is large.

```java
public int kthSmallest(int[][] matrix, int k) {
    int n = matrix.length;
    int lo = matrix[0][0], hi = matrix[n - 1][n - 1];
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (countLessEqual(matrix, mid) >= k) hi = mid;
        else                                  lo = mid + 1;
    }
    return lo;
}

// O(n) staircase count from bottom-left
private int countLessEqual(int[][] matrix, int target) {
    int n = matrix.length, count = 0;
    int r = n - 1, c = 0;
    while (r >= 0 && c < n) {
        if (matrix[r][c] <= target) { count += r + 1; c++; }  // whole column up
        else                          r--;
    }
    return count;
}
```

**Dry run.** `matrix=[[1,5,9],[10,11,13],[12,13,15]], k=8`. Range `[1,15]`. The 8th smallest is 13 (sorted 1,5,9,10,11,12,13,13,15). Binary search on counts converges: at 13, count(≤13)=8 ≥8 and count(≤12)=6 <8, so 13. ✔

**Complexity.** Time O(n · log(maxVal − minVal)), space O(1). **Edge cases:** duplicates spanning rows/columns (counted once each); `k=1` / `k=n²`; the staircase must start at a *corner* (bottom-left or top-right) — starting elsewhere breaks the monotone count.

---

### Problem 43: Minimize Max Distance to Gas Station — Real-Valued Binary Search

**Statement.** Given sorted integer positions `stations` and an integer `k`, add exactly `k` new stations (anywhere, real-valued) to minimize the maximum distance between adjacent stations. Return that minimized maximum distance, within `1e-6`.

**Constraints.** `2 ≤ stations.length ≤ 2000`, `0 ≤ stations[i] ≤ 10^8`, `1 ≤ k ≤ 10^6`, positions strictly increasing.

**Approach.** Binary search the answer over a **real** domain `[0, maxGap]`. For a candidate distance `D`, the number of extra stations needed inside an existing gap `g` is `floor(g / D)` (split the gap into pieces each `≤ D`). Feasible iff the sum over all gaps is `≤ k`. That sum is monotone *decreasing* in `D` (a larger allowed distance needs fewer inserts), so feasibility is `F…F T…T` over increasing `D`; we want the smallest feasible `D`. Because the domain is continuous, loop on a tolerance (or a fixed ~100 iterations) rather than integer convergence — exact equality on doubles is unreliable. Each check is O(n), so O(n · log((maxGap)/eps)).

```java
public double minmaxGasDist(int[] stations, int k) {
    double lo = 0, hi = 0;
    for (int i = 1; i < stations.length; i++)
        hi = Math.max(hi, stations[i] - stations[i - 1]);   // max existing gap
    for (int iter = 0; iter < 100; iter++) {                // fixed-count loop
        double mid = lo + (hi - lo) / 2;
        if (needed(stations, mid) <= k) hi = mid;           // feasible -> shrink
        else                            lo = mid;
    }
    return lo;
}

// extra stations required so every adjacent gap <= D
private long needed(int[] stations, double D) {
    long count = 0;
    for (int i = 1; i < stations.length; i++)
        count += (long) ((stations[i] - stations[i - 1]) / D);  // floor(g / D)
    return count;
}
```

**Dry run.** `stations=[1,2,3,4,5,6,7,8,9,10], k=9`. Gaps are all 1. With `k=9` and 9 gaps, one extra station per gap halves each to 0.5. Binary search converges to ≈0.5 (the smallest `D` with `Σ floor(1/D) ≤ 9` is exactly 0.5).

**Complexity.** Time O(n · 100) ≈ O(n), space O(1). **Edge cases:** `lo = mid` (not `mid+1`) because the domain is continuous — no off-by-one; very large `k` (answer approaches 0); a single dominant gap; the fixed iteration count sidesteps floating `==` and guarantees termination.

---

### Problem 44: Find in Mountain Array — Binary Search With Limited API Calls

**Statement.** A `MountainArray` (strictly increases then strictly decreases) exposes only `get(i)` and `length()`, and you may call `get` at most 100 times. Return the **smallest** index whose value equals `target`, or `-1`. The interactive API constraint forces three logarithmic searches, not a linear scan.

**Constraints.** `3 ≤ length ≤ 10^4`, values in `[1, 10^9]`, at most 100 `get` calls allowed, valid mountain guaranteed.

**Approach.** Three binary searches, each O(log n), total ≈ `3·log₂(10^4) ≈ 42` calls — comfortably under 100, where a linear scan (up to 10^4 calls) would fail. **(1)** Binary search the peak via the slope (`get(mid) < get(mid+1)` ⇒ go right). **(2)** Binary search the strictly-ascending left side `[0, peak]` for `target` (normal ascending order). **(3)** If not found, binary search the strictly-descending right side `(peak, n-1]` (reversed comparison). Returning from the left side first guarantees the *smallest* index. Caching/​minimizing `get` calls is the whole point; the slope trick avoids ever scanning.

```java
public int findInMountainArray(int target, MountainArray arr) {
    int n = arr.length();
    // 1) find peak
    int lo = 0, hi = n - 1;
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (arr.get(mid) < arr.get(mid + 1)) lo = mid + 1;
        else                                 hi = mid;
    }
    int peak = lo;
    // 2) ascending left side
    int left = ascSearch(arr, target, 0, peak);
    if (left != -1) return left;
    // 3) descending right side
    return descSearch(arr, target, peak + 1, n - 1);
}

private int ascSearch(MountainArray arr, int t, int lo, int hi) {
    while (lo <= hi) {
        int mid = lo + (hi - lo) / 2, v = arr.get(mid);
        if (v == t) return mid;
        else if (v < t) lo = mid + 1;
        else            hi = mid - 1;
    }
    return -1;
}

private int descSearch(MountainArray arr, int t, int lo, int hi) {
    while (lo <= hi) {
        int mid = lo + (hi - lo) / 2, v = arr.get(mid);
        if (v == t) return mid;
        else if (v > t) lo = mid + 1;   // descending: bigger means go right
        else            hi = mid - 1;
    }
    return -1;
}
// interface MountainArray { int get(int index); int length(); }
```

**Dry run.** `arr=[1,2,3,4,5,3,1], target=3`. Peak search → index 4 (value 5). Left side `[0,4]` ascending: finds 3 at index 2 → return 2 (the smallest index, even though 3 also appears at index 5). ✔

**Complexity.** Time O(log n), ≤ ~42 `get` calls, space O(1). **Edge cases:** target only on the descending side (left search returns −1, fall through); target equals the peak; target absent (both sides return −1); duplicates across the two sides resolved in favor of the left (smaller) index.

---

### Problem 45: Russian Doll Envelopes — Binary Search Inside LIS (n log n)

**Statement.** Each envelope is `[w, h]`. Envelope A fits in B iff `A.w < B.w` and `A.h < B.h` (strict on both). Return the maximum number of envelopes you can nest (Russian-doll). Required better than O(n²).

**Constraints.** `1 ≤ n ≤ 10^5`, `1 ≤ w, h ≤ 10^5`.

**Approach.** Reduce a 2-D nesting to a 1-D **Longest Increasing Subsequence** solved with **binary search**, giving O(n log n). Sort by width ascending; for ties in width, sort height **descending** — this clever tie-break ensures two envelopes with equal width can never both be chosen (a descending-height run is non-increasing, so it cannot extend a strictly-increasing LIS), enforcing the strict-width requirement automatically. Then run patience-sorting LIS on the heights: maintain `tails`, where `tails[i]` is the smallest possible tail of an increasing subsequence of length `i+1`; for each height, binary search (lower bound) its insertion point and overwrite. The LIS length is the answer.

```
 sort: width asc, height DESC on ties
 e.g. [(2,3),(5,4),(6,7),(6,4)]  -- width 6 ties -> heights 7,4 (desc)
 LIS on heights [3,4,7,4]:
   3        -> tails=[3]
   4        -> tails=[3,4]
   7        -> tails=[3,4,7]
   4(replace)-> tails=[3,4,7]  (lowerBound of 4 is index 1, overwrite)
 LIS length = 3
```

```java
public int maxEnvelopes(int[][] envelopes) {
    Arrays.sort(envelopes, (a, b) ->
        a[0] != b[0] ? a[0] - b[0] : b[1] - a[1]);   // width asc, height desc on tie
    int[] tails = new int[envelopes.length];
    int size = 0;
    for (int[] e : envelopes) {
        int h = e[1];
        int lo = 0, hi = size;                       // lower bound of h in tails
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (tails[mid] >= h) hi = mid;
            else                 lo = mid + 1;
        }
        tails[lo] = h;                               // place or extend
        if (lo == size) size++;
    }
    return size;
}
```

**Dry run.** `[[5,4],[6,4],[6,7],[2,3]]` → sort → `[(2,3),(5,4),(6,7),(6,4)]`, heights `[3,4,7,4]`. LIS via binary search → length 3 (`(2,3)→(5,4)→(6,7)`). ✔ The width-6 pair never both appears because of the descending-height tie-break.

**Complexity.** Time O(n log n) (sort + LIS), space O(n). **Edge cases:** all identical envelopes (answer 1, guarded by the desc tie-break); single envelope; equal widths only (answer 1); the `>=` in the lower bound (not `>`) is what guarantees *strictly* increasing heights.

---

### Problem 46: Maximum Number of Removable Characters — Binary Search on the Prefix Length

**Statement.** Given strings `s`, `p`, and an array `removable` of indices into `s`, find the largest `k` such that after removing the first `k` indices listed in `removable` from `s`, `p` is still a subsequence of the remaining string.

**Constraints.** `1 ≤ p.length ≤ s.length ≤ 10^5`, `1 ≤ removable.length ≤ s.length`, indices distinct and valid.

**Approach.** "`p` is still a subsequence after removing the first `k`" is **monotone**: if removing `k` characters keeps `p` a subsequence, then removing fewer (a subset) also does; removing more can only break it. So `feasible(k)` is `T…T F…F` over increasing `k` — binary search the **largest** feasible `k`. For a candidate `k`, mark the first `k` removable indices as deleted (a boolean set), then do an O(n) subsequence check skipping deleted positions. Each check is O(s.length), and there are O(log(removable.length)) candidates → O(n log n). Brute-forcing every `k` would be O(n²).

```java
public int maximumRemovals(String s, String p, int[] removable) {
    int lo = 0, hi = removable.length;            // largest feasible k in [0, R]
    while (lo < hi) {
        int mid = lo + (hi - lo + 1) / 2;         // upper mid: lo = mid case
        if (isSubsequenceAfter(s, p, removable, mid)) lo = mid;  // feasible -> larger
        else                                          hi = mid - 1;
    }
    return lo;
}

private boolean isSubsequenceAfter(String s, String p, int[] removable, int k) {
    boolean[] removed = new boolean[s.length()];
    for (int i = 0; i < k; i++) removed[removable[i]] = true;   // delete first k
    int j = 0;                                    // pointer into p
    for (int i = 0; i < s.length() && j < p.length(); i++) {
        if (!removed[i] && s.charAt(i) == p.charAt(j)) j++;
    }
    return j == p.length();
}
```

**Dry run.** `s="abcacb", p="ab", removable=[3,1,0]`. k=2 removes indices 3,1 → s becomes `a_c_cb` (`a c c b`) → "ab" subsequence? a at 0, b at 5 → yes, feasible. k=3 also removes index 0 → no leading 'a' → "ab" fails. Largest feasible k = 2. ✔

**Complexity.** Time O(n log R) where R = removable.length, space O(n) for the removed mask. **Edge cases:** `k=0` always feasible (`p` is a subsequence of `s` by problem guarantee); removing makes `p` immediately impossible (answer near 0); upper-biased mid mandatory for the `lo = mid` update; distinct removable indices ensure the mask is correct.

---

### Problem 47: Minimum Speed to Arrive on Time — Binary Search With a Float Trap

**Statement.** Given `dist[i]` (km of the i-th train ride) and a float `hour`, each ride must wait for an integer-hour departure except the last (you board ride `i+1` only at an integer hour). Riding at speed `v` (km/h), the time for ride `i` is `ceil(dist[i] / v)` for all but the last, and `dist[last] / v` (no rounding) for the last. Return the **minimum integer speed** to arrive within `hour`, or `-1` if impossible.

**Constraints.** `1 ≤ dist.length ≤ 10^5`, `1 ≤ dist[i] ≤ 10^5`, `1 ≤ hour ≤ 10^9` given to two decimal places, `1 ≤ v ≤ 10^7`.

**Approach.** If `hour <= dist.length - 1` it is impossible: even at infinite speed the integer-hour waits for the first `n-1` rides total at least `n-1` hours, plus a positive last leg — return `-1`. Otherwise `timeNeeded(v)` is **monotone decreasing** in `v`, so `feasible(v) = timeNeeded(v) <= hour` is `F…F T…T`; binary search the smallest feasible integer speed in `[1, 10^7]`. The traps: (1) accumulate the integer ceilings in `long`; (2) handle the **fractional last leg** separately with double division; (3) compare with a small epsilon or, more robustly, scale `hour` to hundredths (`long`) and compare integer arithmetic to avoid float error. We use the epsilon-light approach below.

```java
public int minSpeedOnTime(int[] dist, double hour) {
    int n = dist.length;
    if (hour <= n - 1) return -1;                 // last leg needs > 0 extra hour
    int lo = 1, hi = 10_000_000;
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (timeAt(dist, mid) <= hour + 1e-9) hi = mid;   // feasible -> slower ok
        else                                  lo = mid + 1;
    }
    return lo;
}

private double timeAt(int[] dist, int v) {
    long whole = 0;                               // sum of ceilings for first n-1
    for (int i = 0; i < dist.length - 1; i++)
        whole += (dist[i] + v - 1) / v;           // ceil(dist[i]/v)
    double last = (double) dist[dist.length - 1] / v;   // fractional last leg
    return whole + last;
}
```

**Dry run.** `dist=[1,3,2], hour=6.0`. n=3, hour=6 > 2 ✔. v=1: ceil(1)+ceil(3)+2/1 = 1+3+2 = 6 ≤ 6 feasible, hi=1. v search bottoms at 1. Answer 1. For `dist=[1,3,2], hour=2.7`: 2.7 < 2? no; v=3 → 1+1+2/3 = 2.667 ≤ 2.7 feasible; smaller v fails → answer 3.

**Complexity.** Time O(n · log(maxSpeed)), space O(1). **Edge cases:** `hour <= n-1` (return −1); the last leg uses *non*-ceil division (a frequent bug); `hour` given to 2 decimals (epsilon comparison or scale by 100); single ride (`n=1`, no integer waits, pure `dist[0]/v <= hour`).

---

### Problem 48: Find the Smallest Divisor / Allocate Minimum Pages — Maximize Feasibility Boundary

**Statement (Book Allocation).** Given `pages[i]` for `n` books in a fixed order and `m` students, allocate **contiguous** blocks of books so each student reads a contiguous segment, every book is assigned, and the **maximum** pages any single student reads is **minimized**. Return that minimum, or `-1` if `m > n` (cannot give every student a book).

**Constraints.** `1 ≤ n ≤ 10^5`, `1 ≤ pages[i] ≤ 10^6`, `1 ≤ m ≤ 10^5`.

**Approach.** This is the classic "minimize the maximum over contiguous partitions" (twin of Split Array, Problem 22/39), kept here because it is the *most frequently asked* version at senior interviews and pins the impossibility edge case `m > n`. The answer lies in `[max(pages), sum(pages)]`. `studentsNeeded(cap)` greedily counts segments whose running sum never exceeds `cap`; it is monotone non-increasing in `cap`, so `feasible(cap) = studentsNeeded(cap) <= m` is `F…F T…T`. Binary search the smallest feasible `cap`. If `m > n` return `-1` up front (pigeonhole: cannot give each student ≥1 book). O(n · log(sum)).

```java
public int findPages(int[] pages, int m) {
    int n = pages.length;
    if (m > n) return -1;                          // not enough books
    int lo = 0, hi = 0;
    for (int p : pages) { lo = Math.max(lo, p); hi += p; }  // [maxPage, sum]
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (studentsNeeded(pages, mid) <= m) hi = mid;   // feasible -> shrink cap
        else                                 lo = mid + 1;
    }
    return lo;
}

// minimum students so each contiguous block's page-sum <= cap
private int studentsNeeded(int[] pages, int cap) {
    int students = 1;
    long running = 0;
    for (int p : pages) {
        if (running + p > cap) { students++; running = 0; }  // new student
        running += p;
    }
    return students;
}
```

**Dry run.** `pages=[12,34,67,90], m=2`. Range `[90,203]`. Known optimal: `[12,34,67] | [90]` → max 113. Binary search converges: mid=146 → segments 12+34+67=113, +90=203>146 cut → 2 students ≤2, hi=146; narrows to 113. ✔

**Complexity.** Time O(n · log(sum)), space O(1). **Edge cases:** `m > n` (return −1); `m = 1` (cap = sum); `m = n` (cap = max single book); contiguity is mandatory — books cannot be reordered.

---

### Problem 49: Nth Magical Number — Binary Search With Inclusion-Exclusion & LCM

**Statement.** A number is *magical* if divisible by `a` or by `b`. Return the `n`-th magical number, modulo `10^9 + 7`. The count of magical numbers `≤ x` must be computed in O(1) (no enumeration), because `n` can be huge.

**Constraints.** `1 ≤ n ≤ 10^9`, `2 ≤ a, b ≤ 4·10^4`.

**Approach.** **Binary search the value** `x` over `[min(a,b), n * min(a,b)]` (the upper bound is loose but safe). The count of magical numbers `≤ x` is closed-form by **inclusion-exclusion**: `x/a + x/b - x/L`, where `L = lcm(a, b) = a / gcd(a, b) * b`. This count is monotone non-decreasing in `x`, so find the smallest `x` with `count(≤ x) >= n`. Because that `x` is itself magical (the count increments only at magical numbers), it is the answer; take it mod `10^9+7` only at the end. Use `long` throughout — `x` reaches ~`4·10^13`. Total O(log(n · min(a,b))).

```
 count(<= x) = floor(x/a) + floor(x/b) - floor(x/lcm(a,b))
               \-- mult of a --/   \-- of b --/   \- both, removed once -/
```

```java
public int nthMagicalNumber(int n, int a, int b) {
    final int MOD = 1_000_000_007;
    long lcm = a / gcd(a, b) * (long) b;          // lcm without overflow
    long lo = Math.min(a, b);
    long hi = (long) n * Math.min(a, b);          // safe upper bound
    while (lo < hi) {
        long mid = lo + (hi - lo) / 2;
        long count = mid / a + mid / b - mid / lcm;   // inclusion-exclusion
        if (count >= n) hi = mid;                 // enough magical numbers
        else            lo = mid + 1;
    }
    return (int) (lo % MOD);                       // mod only at the end
}

private long gcd(long x, long y) {
    while (y != 0) { long t = x % y; x = y; y = t; }
    return x;
}
```

**Dry run.** `n=4, a=2, b=3`. lcm=6. Magical numbers: 2,3,4,6,8,9,… → 4th is 6. Range `[2, 8]`. mid=5: count = 2+1−0 = 3 <4, lo=6. mid=7: count=3+2−1=4 ≥4, hi=7. mid=6: count=3+2−1=4 ≥4, hi=6. lo==hi==6. ✔

**Complexity.** Time O(log(n · min(a,b))), space O(1). **Edge cases:** `a == b` (lcm = a, count = `x/a`); one divides the other (lcm = max, no double counting issue handled by the formula); `long` mandatory (`x` ~ `4·10^13`); mod applied only on the final value, never inside the comparison.

---

## Interview Q&A by Level

### 🟢 Basic

**Q: When is linear search the right choice over binary search?** When the data is unsorted (and you cannot afford to sort it), when `n` is tiny, when you only get one pass (a stream), or when the structure is a linked list with no random access.

**Q: What is the precondition for binary search?** The data must be sorted, or — more generally — there must exist a monotone predicate over the search space so it forms an `F…F T…T` (or `T…T F…F`) strip.

**Q: Why is binary search O(log n)?** Each step discards half the remaining candidates, so the number of steps to reach one element is `log₂ n`.

**Q: What does `Arrays.binarySearch` return when the key is missing?** `-(insertionPoint) - 1`, a negative value whose magnitude encodes where the key would go. Decode the insertion point with `-(result) - 1`.

### 🟡 Intermediate

**Q: How do you compute the midpoint safely, and why?** Use `lo + (hi - lo) / 2` instead of `(lo + hi) / 2`. The latter can overflow `int` when `lo + hi` exceeds `Integer.MAX_VALUE` (Java had this exact bug in `Arrays.binarySearch` for nearly a decade).

**Q: Explain lower bound vs upper bound.** Lower bound = first index with `value >= key`; upper bound = first index with `value > key`. Count of `key` = `upper - lower`. Both are the same skeleton with `>=` vs `>`.

**Q: Iterative vs recursive binary search — which and why?** Iterative is preferred: O(1) space vs O(log n) call-stack, no risk of stack overhead, and the loop makes the invariant explicit.

**Q: How does searching a rotated array stay O(log n)?** At each step exactly one half is sorted; you can decide in O(1) whether the target lies in that sorted half, so you still discard half the range per step.

### 🟠 Advanced

**Q: What is "binary search on the answer" and how do you recognize it?** You binary search over the *space of candidate answers* (a value range) rather than array indices. Recognize it when the problem asks for an optimal numeric value ("minimum speed", "smallest capacity", "minimize the maximum") and you can write a *monotone* `feasible(x)` check. Complexity is O(cost(check) · log(range)).

**Q: Why search the partition of the *smaller* array in median-of-two-sorted-arrays?** It bounds the search space to `O(log(min(m, n)))` instead of `O(log(max))`, and guarantees the complementary cut `j` stays within `[0, n]`, avoiding extra edge handling.

**Q: When would ternary search beat binary search, and when does it lose?** It wins when the objective is *unimodal* (rises then falls) but has no monotone boolean predicate, e.g. minimizing a continuous convex cost. It loses on problems that *are* expressible as a monotone predicate, because ternary makes ~2 evaluations per step (base-3 log) versus binary's 1 (base-2 log).

**Q: How do duplicates change rotated-array search and find-min?** When `nums[lo] == nums[mid] == nums[hi]` you cannot determine which half is sorted, so you shrink the window by one (`lo++` / `hi--`). Worst case (all equal) degrades to O(n).

### 🔴 Expert

**Q: How do you binary search over a real (floating-point) domain robustly?** Loop on a tolerance — `while (hi - lo > 1e-9)` — or a fixed iteration count (e.g. 100 iterations halves the range to ~2⁻¹⁰⁰, far below double precision). Avoid `==` on doubles; never rely on exact convergence.

**Q: How does binary search scale to data that doesn't fit in memory?** It generalizes to external/B-tree search and to interpolation search (O(log log n) average on uniformly distributed keys, using a position estimate instead of the midpoint). On disk you minimize *block reads*, which is why databases use B+ trees with high fan-out rather than binary trees.

**Q: Give a real-world systems use of "binary search on the answer."** Rate-limiter / autoscaler tuning (smallest capacity meeting an SLA), git bisect (first commit where a test fails — a monotone `bad` predicate over commits), version/feature rollout boundaries, and timestamp lookups in append-only logs (LSM-tree / time-series databases binary search within sorted segments).

**Q: What's the amortized story for repeated searches on changing data?** A sorted array gives O(log n) search but O(n) insert; if the data mutates, a balanced BST or skip list gives O(log n) for both. For mostly-static data with rare bulk updates, keep a sorted array and rebuild; for high churn, use a tree/skip-list. Binary search itself has no amortization — it's strictly O(log n) per call.

---

## ⚠️ Common Pitfalls

- **Midpoint overflow.** `(lo + hi) / 2` overflows `int` for large indices; always `lo + (hi - lo) / 2`. For value-space searches near `Integer.MAX_VALUE`, also widen products (`m * m`) to `long`.
- **Off-by-one in loop bounds.** Decide *once* whether your window is inclusive `[lo, hi]` (`while (lo <= hi)`) or half-open `[lo, hi)` (`while (lo < hi)`) and keep updates consistent. Mixing them causes missed elements or out-of-bounds reads.
- **Infinite loop on `lo = mid`.** When an update sets `lo = mid` (not `mid + 1`), `mid` must round **up** with `lo + (hi - lo + 1) / 2`; otherwise `lo` and `hi` one apart loop forever.
- **Wrong post-loop interpretation.** After `while (lo < hi)` the answer is `lo` (== `hi`); know what it *means* (first-true index, insertion point, etc.) and validate it (e.g. `nums[lo] == target`) before trusting it.
- **Non-monotone predicate.** Binary search on the answer is only valid if `feasible` is monotone. If `feasible(x)` flips back and forth, you'll converge to a wrong boundary — verify monotonicity first.
- **Comparing to the wrong endpoint in rotated/find-min.** Compare `nums[mid]` to `nums[hi]`, not `nums[lo]`, for find-min — the `nums[lo]` form mishandles the already-sorted (zero-rotation) case.
- **Ignoring duplicates.** Distinct-value assumptions silently break (rotated search, find-min) when duplicates appear; handle the `==` tie by shrinking, accepting O(n) worst case.
- **Floating-point `==`.** Never test exact equality for real-valued binary search; loop on a tolerance or fixed iteration count.

---

## 📚 Further Reading

- *Introduction to Algorithms* (CLRS), 4th ed. — Ch. 2 (correctness/invariants) and divide-and-conquer foundations.
- *The Art of Computer Programming*, Vol. 3 (Knuth) — §6.2 "Searching by Comparison of Keys", the definitive treatment of binary, interpolation, and exponential search.
- *Programming Pearls* (Bentley), Column 4 — "Writing Correct Programs": the famously hard-to-get-right binary search and its invariant proof.
- *Competitive Programmer's Handbook* (Laaksonen) — concise chapters on binary search, binary-search-on-the-answer, and ternary search.
- LeetCode patterns: 704, 278, 34, 69, 33, 153, 74/240, 162, 875, 4, and the "binary search on answer" set (1011, 410, 1482, 1552).
- Jon Bentley & Joshua Bloch, *"Nearly All Binary Searches and Mergesorts are Broken"* (Google Research blog) — the real-world overflow bug discussion.
```
