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
