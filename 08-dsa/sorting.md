# Sorting Algorithms

A deep, interview-grade tour of sorting: the classic comparison sorts (quicksort, mergesort, heapsort), the elementary O(n²) sorts, the linear non-comparison sorts (counting/radix/bucket), the theory (stability, in-place, the n·log n lower bound), what the JDK actually does, external sorting, and a battery of solved coding problems.

[← Back to master index](../README.md) &nbsp;|&nbsp; [← DSA index](README.md)

---

## Concept & Intuition

Sorting arranges elements into a total order (usually non-decreasing). It is the single most leveraged primitive in algorithm design: once data is sorted, binary search, two-pointer sweeps, greedy scheduling, dedup, and many DP transitions become trivial. Interviewers test sorting both directly ("implement quicksort") and indirectly ("sort first, then…").

**Two big families:**

- **Comparison sorts** decide order only by comparing pairs (`a < b`). They are bounded below by **Ω(n log n)** comparisons in the worst case (proof sketch below). Examples: quicksort, mergesort, heapsort, insertion/selection/bubble, TimSort.
- **Non-comparison sorts** exploit the *structure* of keys (they are small integers, fixed-width digits, uniformly distributed reals). By not comparing, they break the n log n barrier and reach **O(n + k)** or **O(d·(n + b))**. Examples: counting, radix, bucket.

**Key properties to reason about in interviews:**

| Property | Meaning | Why it matters |
|---|---|---|
| **Stable** | Equal keys keep their original relative order | Multi-key sorts ("sort by last name, then first"), sorting records |
| **In-place** | O(1) or O(log n) extra space | Memory-constrained / embedded / huge arrays |
| **Adaptive** | Faster on nearly-sorted input | Real data is often partially ordered |
| **Online** | Can sort as data streams in | Insertion sort can; quicksort/mergesort cannot |

**The n log n lower bound (intuition).** Any comparison sort is a binary decision tree: each comparison is a node with two children (`<` / `≥`). There are `n!` possible permutations, so the tree must have at least `n!` leaves. A binary tree with `n!` leaves has height ≥ `log₂(n!)`. By Stirling, `log₂(n!) = Θ(n log n)`. The height = worst-case number of comparisons, so no comparison sort beats Θ(n log n).

**ASCII: quicksort partition (Lomuto) on `[3,7,1,5,2]`, pivot = last = 2**

```
 i tracks boundary of "< pivot" region; j scans
 pivot = 2
 [3, 7, 1, 5, | 2]      i=-1
  j=0 3>=2 skip
  j=1 7>=2 skip
  j=2 1<2  -> i=0, swap a[0],a[2] -> [1,7,3,5,2]
  j=3 5>=2 skip
 place pivot: swap a[i+1]=a[1] with pivot -> [1,2,3,5,7]
                          ^ pivot index = 1
 recurse left [1]  and right [3,5,7]
```

**ASCII: top-down mergesort divide-and-conquer**

```
        [38,27,43,3,9,82,10]
        /                   \
   [38,27,43]            [3,9,82,10]
    /     \               /      \
 [38]  [27,43]        [3,9]    [82,10]
        /   \          / \       /  \
      [27] [43]      [3] [9]   [82] [10]
   merge up:  [27,43]  [3,9]  [10,82]
   merge:   [27,38,43]   [3,9,10,82]
   merge:   [3,9,10,27,38,43,82]
```

---

## Complexity Cheat-Sheet

| Algorithm | Best | Average | Worst | Space | Stable | In-place | Notes |
|---|---|---|---|---|---|---|---|
| **Bubble** | O(n)\* | O(n²) | O(n²) | O(1) | ✅ | ✅ | \*with early-exit flag; teaching only |
| **Selection** | O(n²) | O(n²) | O(n²) | O(1) | ❌ | ✅ | min # of swaps (n−1) |
| **Insertion** | O(n) | O(n²) | O(n²) | O(1) | ✅ | ✅ | adaptive, online, great for small/near-sorted |
| **Quicksort** | O(n log n) | O(n log n) | **O(n²)** | O(log n)† | ❌ | ✅ | fastest in practice; randomize pivot |
| **Mergesort (array)** | O(n log n) | O(n log n) | O(n log n) | O(n) | ✅ | ❌ | predictable; external sort base |
| **Mergesort (list)** | O(n log n) | O(n log n) | O(n log n) | O(log n) | ✅ | ✅‡ | only pointer rewiring; O(log n) stack |
| **Heapsort** | O(n log n) | O(n log n) | O(n log n) | O(1) | ❌ | ✅ | no worst case, but cache-unfriendly |
| **Counting** | O(n+k) | O(n+k) | O(n+k) | O(n+k) | ✅ | ❌ | k = key range |
| **Radix (LSD)** | O(d·(n+b)) | O(d·(n+b)) | O(d·(n+b)) | O(n+b) | ✅ | ❌ | d = digits, b = base |
| **Bucket** | O(n+k) | O(n+k) | O(n²) | O(n+k) | ✅§ | ❌ | needs uniform distribution |
| **TimSort** (JDK objects) | O(n) | O(n log n) | O(n log n) | O(n) | ✅ | ❌ | adaptive merge of natural runs |
| **Dual-pivot QS** (JDK primitives) | O(n log n) | O(n log n) | O(n²) | O(log n) | n/a | ✅ | `Arrays.sort(int[])` |

† Quicksort stack depth is O(log n) only if you recurse into the smaller side and loop on the larger (tail-call elimination); naive recursion is O(n).
‡ List mergesort needs no element copies, only O(log n) recursion stack.
§ Bucket stability depends on the per-bucket sort being stable.

---

## Patterns & Recognition

Reach for sorting when you see any of these signals:

- **"Find the k-th / top-k / median"** → quickselect (O(n) average) or a heap (O(n log k)).
- **"Merge / combine multiple sorted things"** → k-way merge with a min-heap; or merge step alone.
- **"Count inversions / smaller elements to the right"** → modified mergesort.
- **"Group / dedup / find pairs summing to X"** → sort then two-pointer / sliding window.
- **"Intervals" (merge, overlap, meeting rooms)** → sort by start (or end), then sweep.
- **"Keys are small integers / chars / fixed-width"** → counting or radix sort to beat n log n.
- **"Data doesn't fit in memory"** → external merge sort (sort chunks, k-way merge).
- **"Sort by field A, ties broken by field B, original order preserved"** → you need a **stable** sort (TimSort / `Collections.sort`).
- **"Nearly sorted" or "k away from position"** → insertion sort or a size-k heap (O(n log k)).

Rule of thumb in interviews: if a brute force is O(n²) and the input is unsorted, ask "does sorting (O(n log n)) unlock a linear sweep?" — it usually does.

---

## Coding Problems

### Problem 1: Implement Quicksort (Lomuto + Hoare, randomized)

**Statement.** Sort an `int[]` in place. Show both Lomuto and Hoare partition, and randomize the pivot to avoid the O(n²) worst case on sorted input. Constraints: `1 ≤ n ≤ 10^6`.

**Approach.** Quicksort picks a pivot, partitions the array so everything `< pivot` is left and `> pivot` is right, then recurses. **Lomuto** uses one scan pointer and the last element as pivot (simple, more swaps). **Hoare** uses two converging pointers (fewer swaps, ~3× faster, but the pivot is *not* placed at its final index). Worst case O(n²) happens when the pivot is always the min/max — fixed by random pivot selection. Recurse into the smaller partition and loop on the larger to bound stack at O(log n).

```java
import java.util.Random;

public class QuickSort {
    private static final Random RND = new Random();

    public static void sort(int[] a) {
        quick(a, 0, a.length - 1);
    }

    // Loop on larger side -> O(log n) stack depth.
    private static void quick(int[] a, int lo, int hi) {
        while (lo < hi) {
            int p = lomuto(a, lo, hi);           // pivot final index
            if (p - lo < hi - p) {               // recurse smaller, loop larger
                quick(a, lo, p - 1);
                lo = p + 1;
            } else {
                quick(a, p + 1, hi);
                hi = p - 1;
            }
        }
    }

    private static int lomuto(int[] a, int lo, int hi) {
        swap(a, lo + RND.nextInt(hi - lo + 1), hi); // randomize, move to end
        int pivot = a[hi], i = lo - 1;
        for (int j = lo; j < hi; j++) {
            if (a[j] < pivot) swap(a, ++i, j);
        }
        swap(a, ++i, hi);
        return i;
    }

    // Hoare alternative: returns split index j (pivot NOT placed at final pos).
    static int hoare(int[] a, int lo, int hi) {
        swap(a, lo + RND.nextInt(hi - lo + 1), lo);
        int pivot = a[lo], i = lo - 1, j = hi + 1;
        while (true) {
            do { i++; } while (a[i] < pivot);
            do { j--; } while (a[j] > pivot);
            if (i >= j) return j;                 // recurse [lo..j] and [j+1..hi]
            swap(a, i, j);
        }
    }

    private static void swap(int[] a, int i, int j) {
        int t = a[i]; a[i] = a[j]; a[j] = t;
    }
}
```

**Dry run** on `[3,1,2]`, suppose pivot lands on `2` (index 2). Scan: `j=0` `3≥2` skip; `j=1` `1<2` → `i=0`, swap → `[1,3,2]`; place pivot → swap `a[1],a[2]` → `[1,2,3]`. Pivot index 1; recurse `[1]` and `[3]` (both size 1, done).

**Time:** O(n log n) average, O(n²) worst (mitigated by randomization). **Space:** O(log n) stack.

**Follow-ups.** Why does Hoare do fewer swaps? (It only swaps when both pointers find out-of-place elements.) How to handle many duplicate keys? (**3-way / Dutch-flag partition** — see Problem 5.) Why is the pivot in Hoare not at its sorted position? (Both partitions may include elements equal to pivot.)

---

### Problem 2: Sort Colors (Dutch National Flag)

**Statement.** Array of `0/1/2` (red/white/blue). Sort in place in **one pass**, O(1) space. `n ≤ 300`. (LeetCode 75.)

**Approach.** Counting sort works in two passes. The classic answer is **three-way partitioning**: pointers `lo` (next 0 slot), `hi` (next 2 slot), and `i` (scanner). This is exactly the partition you'd use in 3-way quicksort.

```java
public class SortColors {
    public void sortColors(int[] a) {
        int lo = 0, i = 0, hi = a.length - 1;
        while (i <= hi) {
            if (a[i] == 0)      swap(a, i++, lo++);
            else if (a[i] == 2) swap(a, i, hi--);   // don't advance i: swapped-in value unknown
            else                i++;                 // a[i]==1
        }
    }
    private void swap(int[] a, int i, int j) { int t=a[i]; a[i]=a[j]; a[j]=t; }
}
```

**Dry run** `[2,0,1]`: `i=0` val 2 → swap(0,2)→`[1,0,2]`, hi=1; `i=0` val 1 → i=1; `i=1` val 0 → swap(1,0)→`[0,1,2]`, lo=1,i=2; `i>hi` stop.

**Time:** O(n). **Space:** O(1). **Follow-up.** Generalize to k colors → counting sort O(n+k). Why not advance `i` after swapping with `hi`? (The element brought from the back is unexamined.)

---

### Problem 3: Merge Sorted Array (in place, from the back)

**Statement.** `nums1` has length `m+n` with the first `m` valid and trailing `n` zeros; `nums2` has `n`. Merge into `nums1` sorted. (LeetCode 88.)

**Approach.** Merging from the front would overwrite unprocessed `nums1` values. Merge from the **back** into the free tail — no extra array.

```java
public class MergeSortedArray {
    public void merge(int[] a, int m, int[] b, int n) {
        int i = m - 1, j = n - 1, k = m + n - 1;
        while (j >= 0) {
            a[k--] = (i >= 0 && a[i] > b[j]) ? a[i--] : b[j--];
        }
    }
}
```

**Dry run** `a=[1,2,3,0,0,0],m=3,b=[2,5,6],n=3`: k=5 cmp 3<6→a[5]=6,j=2; cmp3<5→a[4]=5,j=1; cmp3>2→a[3]=3,i=2; cmp2>2? no→a[2]=2,j=0... yields `[1,2,2,3,5,6]`.

**Time:** O(m+n). **Space:** O(1). **Follow-up.** This *is* the merge step of mergesort; it underpins k-way merge and external sort.

---

### Problem 4: Kth Largest Element (Quickselect)

**Statement.** Return the k-th largest element in an unsorted array. `n ≤ 10^5`. (LeetCode 215.)

**Approach.** Sorting is O(n log n). A min-heap of size k is O(n log k). **Quickselect** averages **O(n)**: partition like quicksort but recurse only into the side containing the target index. Randomized pivot avoids O(n²).

```java
import java.util.Random;
public class Quickselect {
    private static final Random RND = new Random();
    public int findKthLargest(int[] a, int k) {
        int target = a.length - k;               // k-th largest = index target when ascending
        int lo = 0, hi = a.length - 1;
        while (lo < hi) {
            int p = partition(a, lo, hi);
            if (p == target) break;
            else if (p < target) lo = p + 1;
            else hi = p - 1;
        }
        return a[target];
    }
    private int partition(int[] a, int lo, int hi) {
        swap(a, lo + RND.nextInt(hi - lo + 1), hi);
        int pivot = a[hi], i = lo;
        for (int j = lo; j < hi; j++) if (a[j] < pivot) swap(a, i++, j);
        swap(a, i, hi);
        return i;
    }
    private void swap(int[] a, int i, int j){int t=a[i];a[i]=a[j];a[j]=t;}
}
```

**Dry run** `[3,2,1,5,6,4], k=2` → target index 4. Partition until index 4 holds its sorted value `5`; return `5`.

**Time:** O(n) average, O(n²) worst (use median-of-medians for guaranteed O(n)). **Space:** O(1). **Follow-up.** Top-k frequent elements (bucket sort by frequency); streaming top-k (size-k heap, since quickselect needs all data in memory).

---

### Problem 5: Sort a Linked List in O(n log n), O(1)-ish space (List Mergesort)

**Statement.** Sort a singly linked list. Required: O(n log n) time and constant *extra* space (only the recursion stack). (LeetCode 148.)

**Approach.** Quicksort on lists is awkward (no random access). **Mergesort is ideal for lists**: split with slow/fast pointers, recurse, and merge by rewiring `next` pointers — zero element copies. The top-down version uses O(log n) stack; a bottom-up version is truly O(1) space.

```java
public class SortList {
    public ListNode sortList(ListNode head) {
        if (head == null || head.next == null) return head;
        // split into two halves
        ListNode slow = head, fast = head.next;
        while (fast != null && fast.next != null) { slow = slow.next; fast = fast.next.next; }
        ListNode right = slow.next; slow.next = null;
        return merge(sortList(head), sortList(right));
    }
    private ListNode merge(ListNode a, ListNode b) {
        ListNode dummy = new ListNode(0), tail = dummy;
        while (a != null && b != null) {
            if (a.val <= b.val) { tail.next = a; a = a.next; }  // <= keeps stability
            else                { tail.next = b; b = b.next; }
            tail = tail.next;
        }
        tail.next = (a != null) ? a : b;
        return dummy.next;
    }
    static class ListNode { int val; ListNode next; ListNode(int v){val=v;} }
}
```

**Dry run** `4→2→1→3`: split `4→2` / `1→3`; recurse → `2→4`, `1→3`; merge → `1→2→3→4`.

**Time:** O(n log n). **Space:** O(log n) stack (O(1) for bottom-up). **Follow-up.** Why mergesort over quicksort for lists? (Sequential access, stable, no quadratic blowup.) Convert top-down to bottom-up to hit strict O(1) space.

---

### Problem 6: Merge k Sorted Lists (k-way merge)

**Statement.** Merge `k` sorted linked lists into one. Total `N` nodes. (LeetCode 23.)

**Approach.** Merging one by one is O(kN). Better: a **min-heap** of the current head of each list → pop the smallest, push its successor, O(N log k). Equivalent to divide-and-conquer pairwise merging, also O(N log k). This is the in-memory core of external sorting.

```java
import java.util.PriorityQueue;
public class MergeKLists {
    public ListNode mergeKLists(ListNode[] lists) {
        PriorityQueue<ListNode> pq = new PriorityQueue<>((x, y) -> x.val - y.val);
        for (ListNode l : lists) if (l != null) pq.offer(l);
        ListNode dummy = new ListNode(0), tail = dummy;
        while (!pq.isEmpty()) {
            ListNode n = pq.poll();
            tail.next = n; tail = n;
            if (n.next != null) pq.offer(n.next);
        }
        return dummy.next;
    }
    static class ListNode { int val; ListNode next; ListNode(int v){val=v;} }
}
```

**Time:** O(N log k). **Space:** O(k) heap. **Follow-up.** Divide-and-conquer merge (same complexity, no heap). Merge k *sorted arrays* / files → identical pattern, basis of external merge sort.

---

### Problem 7: Maximum Gap (Radix / Bucket sort — beat n log n)

**Statement.** Given an unsorted array, return the maximum difference between successive elements in sorted order, in **O(n) time and space**. `n ≤ 10^5`. (LeetCode 164.)

**Approach.** A comparison sort is O(n log n) and disallowed by spirit of the problem. Two linear options: **radix (LSD) sort** then scan; or the **pigeonhole/bucket** trick — with `n` numbers spanning `[min,max]`, the max gap ≥ `ceil((max-min)/(n-1))`, so place numbers into buckets of that width; the answer never lies *within* a bucket, only *between* adjacent non-empty buckets.

```java
public class MaximumGap {
    public int maximumGap(int[] a) {
        int n = a.length;
        if (n < 2) return 0;
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int x : a) { min = Math.min(min, x); max = Math.max(max, x); }
        if (min == max) return 0;

        int bucketSize = Math.max(1, (max - min) / (n - 1));
        int count = (max - min) / bucketSize + 1;
        int[] bMin = new int[count], bMax = new int[count];
        boolean[] used = new boolean[count];
        java.util.Arrays.fill(bMin, Integer.MAX_VALUE);
        java.util.Arrays.fill(bMax, Integer.MIN_VALUE);

        for (int x : a) {
            int idx = (x - min) / bucketSize;
            bMin[idx] = Math.min(bMin[idx], x);
            bMax[idx] = Math.max(bMax[idx], x);
            used[idx] = true;
        }
        int prevMax = min, gap = 0;
        for (int i = 0; i < count; i++) {
            if (!used[i]) continue;
            gap = Math.max(gap, bMin[i] - prevMax);   // gap across the empty span
            prevMax = bMax[i];
        }
        return gap;
    }
}
```

**Dry run** `[3,6,9,1]`: min1,max9,n4 → bucketSize=(8)/3=2, count=5. Buckets by `(x-1)/2`: 1→b0,3→b1,6→b2,9→b4. Scan gaps: 3-1=2, 6-3=3, 9-6=3 → max **3**.

**Time:** O(n). **Space:** O(n). **Follow-up.** Implement the radix variant (sort by 8-bit chunks, 4 passes for 32-bit ints). When does bucket sort degrade to O(n²)? (Skewed distribution dumping everything in one bucket.)

---

### Problem 8: Count of Smaller Numbers After Self (Mergesort + index tracking)

**Statement.** For each `nums[i]`, count elements to its right that are smaller. Return the counts array. `n ≤ 10^5`. (LeetCode 315 — hard.)

**Approach.** Brute force O(n²). The trick: a **modified mergesort** on (value, original-index) pairs. While merging, when we take an element from the **right** half before a left element, every remaining left element gets credited — those right elements are smaller *and* originally to the right. (A BIT/Fenwick tree also solves it; mergesort is the canonical sort-based answer.)

```java
public class CountSmaller {
    private int[] counts, indices, tmp;

    public java.util.List<Integer> countSmaller(int[] nums) {
        int n = nums.length;
        counts = new int[n]; indices = new int[n]; tmp = new int[n];
        for (int i = 0; i < n; i++) indices[i] = i;
        mergeSort(nums, 0, n - 1);
        java.util.List<Integer> res = new java.util.ArrayList<>();
        for (int c : counts) res.add(c);
        return res;
    }

    private void mergeSort(int[] nums, int lo, int hi) {
        if (lo >= hi) return;
        int mid = (lo + hi) >>> 1;
        mergeSort(nums, lo, mid);
        mergeSort(nums, mid + 1, hi);
        merge(nums, lo, mid, hi);
    }

    private void merge(int[] nums, int lo, int mid, int hi) {
        int i = lo, j = mid + 1, k = lo, rightCount = 0;
        while (i <= mid && j <= hi) {
            if (nums[indices[j]] < nums[indices[i]]) {       // right elem smaller
                rightCount++;
                tmp[k++] = indices[j++];
            } else {                                          // left elem settles
                counts[indices[i]] += rightCount;             // credit smaller-right seen so far
                tmp[k++] = indices[i++];
            }
        }
        while (i <= mid) { counts[indices[i]] += rightCount; tmp[k++] = indices[i++]; }
        while (j <= hi)  { tmp[k++] = indices[j++]; }
        System.arraycopy(tmp, lo, indices, lo, hi - lo + 1);
    }
}
```

**Dry run** `[5,2,6,1]` → counts `[2,1,1,0]` (5 has 2,1 smaller right; 2 has 1; 6 has 1; 1 has 0).

**Time:** O(n log n). **Space:** O(n). **Follow-up.** Count global **inversions** (LeetCode equivalent: sum of all counts) — same merge, accumulate a single total. Count "reverse pairs" where `a[i] > 2·a[j]` — add a counting pass before merging.

---

### Problem 9: External Sort — sort a 100 GB file with 1 GB RAM (design + merge core)

**Statement.** A file has more records than fit in memory. Produce a sorted file. (Classic systems/senior question.)

**Approach.** **Two-phase external merge sort.** Phase 1: read the file in memory-sized chunks, sort each in RAM (TimSort/quicksort), write each sorted "run" to a temp file. Phase 2: **k-way merge** the runs with a min-heap, streaming output. If runs exceed the merge fan-in, do multiple merge passes. I/O dominates; the in-memory merge is the same heap-based k-way merge as Problem 6.

```java
import java.io.*;
import java.util.*;

public class ExternalSort {
    // Phase 1: create sorted runs.
    static List<File> makeRuns(BufferedReader in, int maxLines) throws IOException {
        List<File> runs = new ArrayList<>();
        List<Integer> buf = new ArrayList<>(maxLines);
        String line;
        while ((line = in.readLine()) != null) {
            buf.add(Integer.parseInt(line.trim()));
            if (buf.size() >= maxLines) runs.add(flush(buf));
        }
        if (!buf.isEmpty()) runs.add(flush(buf));
        return runs;
    }
    private static File flush(List<Integer> buf) throws IOException {
        Collections.sort(buf);                       // TimSort, O(m log m)
        File f = File.createTempFile("run", ".txt");
        try (BufferedWriter w = new BufferedWriter(new FileWriter(f))) {
            for (int x : buf) { w.write(Integer.toString(x)); w.newLine(); }
        }
        buf.clear();
        return f;
    }

    // Phase 2: k-way merge with a min-heap of one reader per run.
    static void merge(List<File> runs, BufferedWriter out) throws IOException {
        PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> a[0] - b[0]); // [value, runIdx]
        BufferedReader[] rs = new BufferedReader[runs.size()];
        for (int i = 0; i < runs.size(); i++) {
            rs[i] = new BufferedReader(new FileReader(runs.get(i)));
            String l = rs[i].readLine();
            if (l != null) pq.offer(new int[]{Integer.parseInt(l.trim()), i});
        }
        while (!pq.isEmpty()) {
            int[] top = pq.poll();
            out.write(Integer.toString(top[0])); out.newLine();
            String l = rs[top[1]].readLine();
            if (l != null) pq.offer(new int[]{Integer.parseInt(l.trim()), top[1]});
        }
        for (BufferedReader r : rs) r.close();
    }
}
```

**Walkthrough.** 100 GB / 1 GB ≈ 100 runs. One min-heap entry per run → only 100 ints + buffered readers in RAM at once. Each record is read/written O(passes) times; with a single merge pass, that's 2 reads + 2 writes total.

**Time:** O(N log N) comparisons; I/O O(N · passes). **Space:** O(k) in RAM. **Follow-ups.** Reduce passes via larger fan-in or **replacement selection** (produces runs ~2× memory size). Distributed variant → MapReduce/Spark sort, terabyte-sort benchmarks. Stable external sort → tag records with sequence numbers.

---

## 🧩 Extended Problems — Set 1: Classic Easy → Medium

### Problem 10: Insertion Sort (implement + why it's the small-array fallback)

**Statement.** Sort an `int[]` in place using insertion sort. Discuss why production sorts (TimSort, dual-pivot quicksort) fall back to it for small subarrays. `1 ≤ n ≤ 5000`.

**Approach.** Insertion sort grows a sorted prefix `a[0..i-1]` and inserts `a[i]` into its correct slot by shifting larger elements one position right. It is **stable** (we stop shifting on the first element `≤ key`), **adaptive** (O(n) on already-sorted input because the inner loop never fires), and **in-place**. Its low constant factor and cache-friendly sequential writes make it the fastest choice on tiny or nearly-sorted arrays — which is exactly why TimSort/introsort delegate to it below a threshold (~32–64 elements).

```
insert key=2 into sorted prefix [1,3,5 | 2,4]
   key=2, compare 5>2 shift -> [1,3,_,5,4]
          compare 3>2 shift -> [1,_,3,5,4]
          compare 1>2? no, place -> [1,2,3,5,4]
```

```java
public class InsertionSort {
    public static void sort(int[] a) {
        for (int i = 1; i < a.length; i++) {
            int key = a[i], j = i - 1;
            while (j >= 0 && a[j] > key) {   // ">" (not ">=") preserves stability
                a[j + 1] = a[j];
                j--;
            }
            a[j + 1] = key;
        }
    }
}
```

**Dry run** `[3,1,2]`: i=1 key=1 shift 3 → `[1,3,2]`; i=2 key=2 shift 3 → `[1,2,3]`.

**Complexity.** Time O(n²) average/worst, **O(n) best** (sorted input); Space O(1). **Edge cases:** empty/single-element array (loop body never runs), all-equal array (no shifts, O(n)), reverse-sorted (worst case, every element shifts to the front).

---

### Problem 11: Selection Sort (fewest writes)

**Statement.** Sort an `int[]` using selection sort. Note the property that distinguishes it from insertion/bubble. `1 ≤ n ≤ 5000`.

**Approach.** Selection sort repeatedly finds the minimum of the unsorted suffix and swaps it into the next position. Its defining property is that it performs **exactly `n-1` swaps** — the minimum possible — regardless of input, which is valuable when *writes are expensive* (e.g., flash memory wear). It is **not stable** (a swap can leapfrog an equal key) and **not adaptive** (always Θ(n²) comparisons even on sorted input).

```java
public class SelectionSort {
    public static void sort(int[] a) {
        for (int i = 0; i < a.length - 1; i++) {
            int min = i;
            for (int j = i + 1; j < a.length; j++) {
                if (a[j] < a[min]) min = j;
            }
            if (min != i) swap(a, i, min);   // at most n-1 swaps total
        }
    }
    private static void swap(int[] a, int i, int j) { int t=a[i]; a[i]=a[j]; a[j]=t; }
}
```

**Dry run** `[64,25,12,22]`: pick 12→`[12,25,64,22]`; pick 22→`[12,22,64,25]`; pick 25→`[12,22,25,64]`.

**Complexity.** Time Θ(n²) in all cases; Space O(1); swaps O(n). **Edge cases:** already-sorted input still costs Θ(n²) comparisons; the `min != i` guard avoids no-op self-swaps; single element loops zero times.

---

### Problem 12: Bubble Sort with Early-Exit

**Statement.** Implement bubble sort with the optimization that detects an already-sorted array and stops early. `1 ≤ n ≤ 5000`.

**Approach.** Bubble sort repeatedly walks the array swapping adjacent out-of-order pairs; after pass `k` the largest `k` elements have "bubbled" to the end. A `swapped` flag turns it **adaptive**: if a full pass makes zero swaps, the array is sorted and we return — giving the **O(n) best case** on sorted input. It is stable (only swaps on strict `>`), in-place, but otherwise dominated by insertion sort in practice.

```java
public class BubbleSort {
    public static void sort(int[] a) {
        int n = a.length;
        for (int i = 0; i < n - 1; i++) {
            boolean swapped = false;
            for (int j = 0; j < n - 1 - i; j++) {   // last i elements already placed
                if (a[j] > a[j + 1]) { swap(a, j, j + 1); swapped = true; }
            }
            if (!swapped) break;                     // no swaps -> already sorted
        }
    }
    private static void swap(int[] a, int i, int j) { int t=a[i]; a[i]=a[j]; a[j]=t; }
}
```

**Dry run** `[5,1,4,2]`: pass1 →`[1,4,2,5]` (swaps); pass2 →`[1,2,4,5]`; pass3 no swaps → break.

**Complexity.** Time O(n²) average/worst, **O(n) best**; Space O(1). **Edge cases:** sorted input exits after one pass; the `n-1-i` bound prevents touching the already-sorted tail; single/empty array does nothing.

---

### Problem 13: Merge Sort (top-down, stable)

**Statement.** Implement a stable mergesort on an `int[]` with guaranteed O(n log n) worst-case time. `1 ≤ n ≤ 10^6`.

**Approach.** Divide the array in half, recursively sort each half, then **merge** the two sorted halves into an auxiliary buffer. Unlike quicksort, mergesort has **no quadratic worst case** and is **stable** (use `<=` so left-half elements win ties, preserving original order). The cost is O(n) auxiliary space. This is the array analog of Problem 5's list mergesort and the in-memory core of external sort.

```java
public class MergeSort {
    public static void sort(int[] a) {
        if (a.length < 2) return;
        sort(a, new int[a.length], 0, a.length - 1);
    }
    private static void sort(int[] a, int[] aux, int lo, int hi) {
        if (lo >= hi) return;
        int mid = (lo + hi) >>> 1;
        sort(a, aux, lo, mid);
        sort(a, aux, mid + 1, hi);
        merge(a, aux, lo, mid, hi);
    }
    private static void merge(int[] a, int[] aux, int lo, int mid, int hi) {
        System.arraycopy(a, lo, aux, lo, hi - lo + 1);
        int i = lo, j = mid + 1;
        for (int k = lo; k <= hi; k++) {
            if (i > mid)               a[k] = aux[j++];          // left exhausted
            else if (j > hi)           a[k] = aux[i++];          // right exhausted
            else if (aux[i] <= aux[j]) a[k] = aux[i++];          // "<=" keeps stability
            else                       a[k] = aux[j++];
        }
    }
}
```

**Dry run** `[3,1,2]`: split `[3]`/`[1,2]` → `[1,2]`; merge `[3]`+`[1,2]` → `[1,2,3]`.

**Complexity.** Time O(n log n) in all cases; Space O(n) buffer + O(log n) stack. **Edge cases:** empty/single array returns immediately; one auxiliary buffer reused across all merges avoids per-call allocation; `(lo+hi)>>>1` avoids midpoint overflow.

---

### Problem 14: Heap Sort (in-place, O(1) space)

**Statement.** Sort an `int[]` in place using a binary heap, with O(n log n) worst case and O(1) extra space. `1 ≤ n ≤ 10^6`.

**Approach.** Build a **max-heap** in place via bottom-up `heapify` (O(n)). Then repeatedly swap the root (current max) to the end of the unsorted region and sift the new root down, shrinking the heap by one each time. The sorted suffix grows from the right. Heapsort is **in-place** and has no O(n²) worst case (unlike quicksort), but it is **not stable** and cache-unfriendly, so it is usually slower than quicksort in practice.

```
heapify [4,10,3,5,1] -> max-heap [10,5,3,4,1]
extract: swap root<->last -> [1,5,3,4,|10] sift -> [5,4,3,1,|10]
repeat -> [4,1,3,|5,10] -> [3,1,|4,5,10] -> [1,|3,4,5,10] -> sorted
```

```java
public class HeapSort {
    public static void sort(int[] a) {
        int n = a.length;
        for (int i = n / 2 - 1; i >= 0; i--) siftDown(a, i, n);   // build max-heap O(n)
        for (int end = n - 1; end > 0; end--) {
            swap(a, 0, end);                                       // max to its final slot
            siftDown(a, 0, end);                                   // restore heap on [0,end)
        }
    }
    private static void siftDown(int[] a, int i, int size) {
        while (true) {
            int l = 2 * i + 1, r = 2 * i + 2, largest = i;
            if (l < size && a[l] > a[largest]) largest = l;
            if (r < size && a[r] > a[largest]) largest = r;
            if (largest == i) break;
            swap(a, i, largest);
            i = largest;
        }
    }
    private static void swap(int[] a, int i, int j) { int t=a[i]; a[i]=a[j]; a[j]=t; }
}
```

**Dry run** `[3,1,2]`: heapify→`[3,1,2]`; swap(0,2)→`[2,1,3]` sift→`[2,1,3]`; swap(0,1)→`[1,2,3]`.

**Complexity.** Time O(n log n) all cases; Space O(1). **Edge cases:** single/empty array (build loop and extract loop both skip); duplicates handled fine (not stable, but correct order); build-heap is O(n), not O(n log n).

---

### Problem 15: Counting Sort (stable, non-negative keys)

**Statement.** Sort an `int[]` whose values lie in `[0, k]` in O(n + k) time. Make it **stable**. `1 ≤ n ≤ 10^6`, `0 ≤ a[i] ≤ k`.

**Approach.** Count occurrences of each key, convert counts to **prefix sums** (so `count[v]` becomes the end-exclusive position for value `v`), then place each element into the output by walking the input **right-to-left** and decrementing — this right-to-left placement is what makes counting sort **stable**. It beats the n log n bound because it never compares keys; it only works when `k` is not much larger than `n`.

```java
public class CountingSort {
    public static int[] sort(int[] a, int k) {       // values in [0, k]
        int[] count = new int[k + 1];
        for (int x : a) count[x]++;
        for (int v = 1; v <= k; v++) count[v] += count[v - 1];   // prefix sums
        int[] out = new int[a.length];
        for (int i = a.length - 1; i >= 0; i--) {     // right-to-left -> stable
            out[--count[a[i]]] = a[i];
        }
        return out;
    }
}
```

**Dry run** `a=[2,0,2,1], k=2`: counts `[1,1,2]` → prefix `[1,2,4]`; place from right: 1→idx1, 2→idx3, 0→idx0, 2→idx2 → `[0,1,2,2]`.

**Complexity.** Time O(n + k); Space O(n + k). **Edge cases:** negatives require offsetting by `min`; huge `k` relative to `n` makes it memory-prohibitive (fall back to comparison sort); empty array returns empty.

---

### Problem 16: Radix Sort (LSD, non-negative integers)

**Statement.** Sort an `int[]` of non-negative integers using LSD radix sort in O(d·(n + b)) time. `1 ≤ n ≤ 10^6`.

**Approach.** Process keys digit by digit from **least-significant to most-significant**, using a **stable** counting sort (base `b`, here 10) on each digit. Stability of the per-digit pass guarantees that ordering established by lower digits survives, so after the most-significant digit the array is fully sorted. With base 10 and 32-bit ints, `d ≤ 10` passes; using base 256 (byte chunks) needs only 4 passes.

```java
public class RadixSort {
    public static void sort(int[] a) {              // non-negative ints
        int max = 0;
        for (int x : a) max = Math.max(max, x);
        int[] out = new int[a.length];
        for (int exp = 1; max / exp > 0; exp *= 10) {     // one pass per decimal digit
            int[] count = new int[10];
            for (int x : a) count[(x / exp) % 10]++;
            for (int d = 1; d < 10; d++) count[d] += count[d - 1];
            for (int i = a.length - 1; i >= 0; i--) {       // stable placement
                int digit = (a[i] / exp) % 10;
                out[--count[digit]] = a[i];
            }
            System.arraycopy(out, 0, a, 0, a.length);
        }
    }
}
```

**Dry run** `[170,45,75,90]`: by units →`[170,90,45,75]`; by tens →`[170,45,75,90]`; by hundreds →`[45,75,90,170]`.

**Complexity.** Time O(d·(n + b)), d = digit count, b = base; Space O(n + b). **Edge cases:** all-zero / single-element array (loop condition `max/exp>0` ends immediately); negatives need sign handling (offset or separate negative pass); large values just mean more passes.

---

### Problem 17: Merge Two Sorted Arrays (return new array)

**Statement.** Given two sorted `int[]` arrays, return a new sorted array containing all elements. (The standalone merge primitive behind mergesort.)

**Approach.** Two-pointer merge: walk both arrays simultaneously, always copying the smaller current element. Using `<=` keeps elements of `a` before equal elements of `b` (stable merge). After one array is exhausted, bulk-copy the remainder. This is the building block reused in k-way merge and external sort.

```java
public class MergeTwoSorted {
    public static int[] merge(int[] a, int[] b) {
        int[] r = new int[a.length + b.length];
        int i = 0, j = 0, k = 0;
        while (i < a.length && j < b.length) {
            r[k++] = (a[i] <= b[j]) ? a[i++] : b[j++];   // "<=" -> stable
        }
        while (i < a.length) r[k++] = a[i++];
        while (j < b.length) r[k++] = b[j++];
        return r;
    }
}
```

**Dry run** `a=[1,3,5], b=[2,4]`: 1,2,3,4,5 → `[1,2,3,4,5]`.

**Complexity.** Time O(m + n); Space O(m + n) for the result. **Edge cases:** one or both arrays empty (a tail-copy loop handles it); duplicates across arrays preserved; equal lengths or wildly unequal lengths both fine.

---

### Problem 18: Sort Array By Parity

**Statement.** Given an integer array, move all even elements to the front and all odd elements to the back. Any valid ordering is accepted. In place, O(1) space. (LeetCode 905.)

**Approach.** This is a one-sided partition (Lomuto-style): maintain a boundary `i` for the next even slot, scan with `j`, and whenever `a[j]` is even, swap it into position `i` and advance `i`. Exactly the partition primitive from quicksort, specialized to the predicate "is even". One pass, no extra array.

```java
public class SortArrayByParity {
    public int[] sortArrayByParity(int[] a) {
        int i = 0;
        for (int j = 0; j < a.length; j++) {
            if ((a[j] & 1) == 0) {        // even -> push to front region
                int t = a[i]; a[i] = a[j]; a[j] = t;
                i++;
            }
        }
        return a;
    }
}
```

**Dry run** `[3,1,2,4]`: j=2 even→swap(0,2)→`[2,1,3,4]`,i=1; j=3 even→swap(1,3)→`[2,4,3,1]`,i=2 → evens first.

**Complexity.** Time O(n); Space O(1). **Edge cases:** all-even or all-odd arrays (one stays put, the other never swaps); empty array returns empty; relative order within even/odd groups is not preserved (acceptable here).

---

### Problem 19: Height Checker

**Statement.** `heights[i]` is a student's height; the expected order is non-decreasing. Return how many indices are out of place versus the sorted order. `1 ≤ n ≤ 100`, `1 ≤ heights[i] ≤ 100`. (LeetCode 1051.)

**Approach.** Compare the array to its sorted copy and count mismatched positions. Because heights are bounded by 100, a **counting sort** produces the expected order in O(n + k) without comparisons — a clean demonstration that sorting unlocks the answer and that bounded keys let us beat n log n.

```java
public class HeightChecker {
    public int heightChecker(int[] heights) {
        int[] count = new int[101];                  // heights in [1,100]
        for (int h : heights) count[h]++;
        int idx = 0, mismatches = 0;
        for (int h = 1; h <= 100; h++) {
            while (count[h]-- > 0) {                  // emit expected order
                if (heights[idx++] != h) mismatches++;
            }
        }
        return mismatches;
    }
}
```

**Dry run** `[1,1,4,2,1,3]` → expected `[1,1,1,2,3,4]`; positions 2,4,5 differ → **3**.

**Complexity.** Time O(n + k); Space O(k). **Edge cases:** already-sorted input returns 0; all-equal heights return 0; the bounded range (≤100) is what enables the counting-sort approach.

---

### Problem 20: Relative Sort Array (custom order via counting)

**Statement.** Sort `arr1` so elements appear in the relative order given by `arr2`; elements of `arr1` not in `arr2` go at the end in ascending order. Values in `[0, 1000]`. (LeetCode 1122.)

**Approach.** Values are bounded by 1000, so **counting sort** is ideal. Tally `arr1` into a `count[]` of size 1001. First emit each value of `arr2` in order, draining its count; then sweep `0..1000` and append any leftover values (those absent from `arr2`) in natural ascending order. O(n + m + k) with no comparisons.

```java
public class RelativeSortArray {
    public int[] relativeSortArray(int[] arr1, int[] arr2) {
        int[] count = new int[1001];
        for (int x : arr1) count[x]++;
        int[] res = new int[arr1.length];
        int k = 0;
        for (int v : arr2) {                          // values in arr2 order
            while (count[v]-- > 0) res[k++] = v;
        }
        for (int v = 0; v <= 1000; v++) {             // leftovers ascending
            while (count[v]-- > 0) res[k++] = v;
        }
        return res;
    }
}
```

**Dry run** `arr1=[2,3,1,3,2,4,6], arr2=[2,1,3]` → emit 2,2,1,3,3 then leftovers 4,6 → `[2,2,1,3,3,4,6]`.

**Complexity.** Time O(n + m + k); Space O(k). **Edge cases:** values in `arr1` but not `arr2` (handled by the second sweep); duplicates (counts handle multiplicity); `arr2` is a permutation subset of distinct values per constraints.

---

### Problem 21: Largest Number (custom comparator on string concat)

**Statement.** Given non-negative integers, arrange them to form the largest possible number; return it as a string. `1 ≤ n ≤ 100`. (LeetCode 179.)

**Approach.** Sort by a **custom comparator**: for two numbers `x`, `y` (as strings), put `x` before `y` iff `x+y > y+x` (the concatenation that reads larger wins). This pairwise rule is a valid total order (it is transitive), so a standard comparison sort yields the global optimum. Guard the all-zeros case so we return `"0"` rather than `"000"`.

```java
import java.util.Arrays;
public class LargestNumber {
    public String largestNumber(int[] nums) {
        String[] s = new String[nums.length];
        for (int i = 0; i < nums.length; i++) s[i] = Integer.toString(nums[i]);
        Arrays.sort(s, (x, y) -> (y + x).compareTo(x + y));   // descending by concat
        if (s[0].equals("0")) return "0";                     // all zeros
        StringBuilder sb = new StringBuilder();
        for (String t : s) sb.append(t);
        return sb.toString();
    }
}
```

**Dry run** `[3,30,34,5,9]`: comparator orders → `9,5,34,3,30` → `"9534330"`.

**Complexity.** Time O(n·L·log n) where L is max digit length (string concat per comparison); Space O(n·L). **Edge cases:** all zeros → `"0"`; single element → its own string; differing lengths handled by the concat comparison (`"3"` vs `"30"` → "330" vs "303").

---

### Problem 22: Sort Array by Increasing Frequency

**Statement.** Sort an integer array in increasing order of frequency; elements with the same frequency are ordered by **decreasing** value. `1 ≤ n ≤ 100`, `-100 ≤ a[i] ≤ 100`. (LeetCode 1636.)

**Approach.** Count frequencies in a hash map, then sort with a **two-key comparator**: primary key = frequency ascending, tiebreak = value descending. Boxing to `Integer[]` lets us use a stable comparator-based sort; the comparator encodes the exact ordering the problem demands.

```java
import java.util.*;
public class FrequencySort {
    public int[] frequencySort(int[] nums) {
        Map<Integer, Integer> freq = new HashMap<>();
        for (int x : nums) freq.merge(x, 1, Integer::sum);
        Integer[] boxed = new Integer[nums.length];
        for (int i = 0; i < nums.length; i++) boxed[i] = nums[i];
        Arrays.sort(boxed, (a, b) ->
            freq.get(a).equals(freq.get(b))
                ? b - a                          // same freq -> larger value first
                : freq.get(a) - freq.get(b));    // else lower freq first
        int[] res = new int[nums.length];
        for (int i = 0; i < nums.length; i++) res[i] = boxed[i];
        return res;
    }
}
```

**Dry run** `[1,1,2,2,2,3]`: freqs 1→2, 2→3, 3→1; order by freq asc → 3 (f1), then 1 (f2), then 2 (f3) → `[3,1,1,2,2,2]`.

**Complexity.** Time O(n log n); Space O(n). **Edge cases:** all-distinct values (all frequency 1 → sorted by value descending); all-equal values (single group); the tiebreak `b - a` is safe here because values fit in `[-100,100]` (no overflow).

---

## 🧩 Extended Problems — Set 2: Medium → Hard Variations & Follow-ups

### Problem 23: 3-Way Quicksort (Dutch-Flag partition for many duplicates)

**Statement.** Sort an `int[]` that may contain **massive numbers of duplicate keys** (e.g., millions of values drawn from a tiny domain). A naive 2-way quicksort degrades toward O(n²) on such input because equal keys are repeatedly re-partitioned. Achieve near-linear time when duplicates dominate. `1 ≤ n ≤ 10^6`.

**Approach.** Progress from the standard 2-way partition (Problem 1) to a **3-way partition** (Bentley–McIlroy / Dijkstra's Dutch National Flag). Pick a random pivot `v`, then sweep a scanner `i` while maintaining three regions: `[lo, lt)` holds `< v`, `[lt, i)` holds `== v`, and `(gt, hi]` holds `> v`. Crucially we **recurse only on the `<` and `>` regions** — the entire equal block is already final and never re-examined. On an array of all-equal keys this is a single linear scan with **zero recursion**, turning the classic O(n²) duplicate trap into O(n).

```
partition state on pivot v:
  lo            lt        i            gt           hi
  [  < v ...  ][ == v ...][ ? ?? ... ][  ... > v   ]
                          ^scanner
  a[i] < v : swap(lt,i), lt++, i++     (extend "<" region)
  a[i] > v : swap(i,gt), gt--          (i stays: incoming value unknown)
  a[i] == v: i++                       (extend "==" region)
```

```java
import java.util.Random;

public class ThreeWayQuickSort {
    private static final Random RND = new Random();

    public static void sort(int[] a) { sort(a, 0, a.length - 1); }

    private static void sort(int[] a, int lo, int hi) {
        if (lo >= hi) return;
        int v = a[lo + RND.nextInt(hi - lo + 1)];   // random pivot value
        int lt = lo, i = lo, gt = hi;
        while (i <= gt) {
            if (a[i] < v)      swap(a, lt++, i++);
            else if (a[i] > v) swap(a, i, gt--);     // do NOT advance i
            else               i++;                  // a[i] == v
        }
        sort(a, lo, lt - 1);                          // only the "< v" side
        sort(a, gt + 1, hi);                          // only the "> v" side
    }

    private static void swap(int[] a, int i, int j) { int t=a[i]; a[i]=a[j]; a[j]=t; }
}
```

**Dry run** `[2,1,2,0,2,1,0]`, pivot `v=2`: scanner pushes `0,1` left and `2`s into the middle, `1,0,1,0` settle left of the equal block → `[1,0,1,0,2,2,2]`, then recurse on `[1,0,1,0]` only.

**Complexity.** Time O(n log n) average, **O(n)** when keys come from O(1) distinct values; worst still O(n²) only with distinct adversarial keys (random pivot makes it improbable). Space O(log n) stack. **Edge cases:** all-equal array (one linear pass, no recursion); already-sorted (random pivot avoids skew); the "don't advance `i` after a `> v` swap" rule mirrors the Dutch-flag bug from Problem 2.

---

### Problem 24: Kth Smallest with Median-of-Medians (guaranteed O(n))

**Statement.** Return the k-th smallest element of an unsorted array with a **worst-case O(n)** guarantee — no randomization, no probabilistic argument. `1 ≤ k ≤ n ≤ 10^5`.

**Approach.** Randomized quickselect (Problem 4) is O(n) *expected* but O(n²) worst case. The **median-of-medians** (BFPRT) pivot rule makes it deterministic O(n): split into groups of 5, find each group's median by tiny insertion sort, recursively select the median **of those medians**, and use it as the pivot. This pivot is guaranteed to be greater than at least ~30% and less than ~30% of elements, so each recursion discards a constant fraction. The recurrence `T(n) = T(n/5) + T(7n/10) + O(n)` solves to O(n) because `1/5 + 7/10 < 1`.

```java
public class MedianOfMedians {
    public int kthSmallest(int[] a, int k) {        // 1-indexed k
        return select(a, 0, a.length - 1, k - 1);    // 0-indexed target
    }

    private int select(int[] a, int lo, int hi, int k) {
        while (true) {
            if (lo == hi) return a[lo];
            int pivot = medianOfMedians(a, lo, hi);
            int p = partition(a, lo, hi, pivot);
            if (k == p) return a[k];
            else if (k < p) hi = p - 1;
            else            lo = p + 1;
        }
    }

    private int medianOfMedians(int[] a, int lo, int hi) {
        int n = hi - lo + 1;
        if (n <= 5) { insertion(a, lo, hi); return a[lo + n / 2]; }
        int write = lo;
        for (int i = lo; i <= hi; i += 5) {
            int sub = Math.min(i + 4, hi);
            insertion(a, i, sub);
            swap(a, write++, i + (sub - i) / 2);      // collect medians at the front
        }
        return medianOfMedians(a, lo, write - 1);     // median of the medians
    }

    private int partition(int[] a, int lo, int hi, int pivotVal) {
        int pIdx = lo;
        while (a[pIdx] != pivotVal) pIdx++;
        swap(a, pIdx, hi);                            // move pivot to end (Lomuto)
        int i = lo;
        for (int j = lo; j < hi; j++) if (a[j] < pivotVal) swap(a, i++, j);
        swap(a, i, hi);
        return i;
    }

    private void insertion(int[] a, int lo, int hi) {
        for (int i = lo + 1; i <= hi; i++) {
            int key = a[i], j = i - 1;
            while (j >= lo && a[j] > key) { a[j + 1] = a[j]; j--; }
            a[j + 1] = key;
        }
    }
    private void swap(int[] a, int i, int j) { int t=a[i]; a[i]=a[j]; a[j]=t; }
}
```

**Dry run** `[7,10,4,3,20,15], k=3` → deterministic pivot partitions until index 2 holds its sorted value `7`; return `7`.

**Complexity.** Time **O(n) worst case**; Space O(log n) stack. **Edge cases:** `n ≤ 5` handled directly by insertion sort; duplicate-heavy input is fine (the `!=` scan finds the chosen pivot value); k=1 (min) and k=n (max) both reduce naturally. In practice randomized quickselect is faster (better constants) — median-of-medians matters only when a worst-case guarantee is required.

---

### Problem 25: Wiggle Sort II (rearrange to a[0] < a[1] > a[2] < ...)

**Statement.** Reorder `nums` so that `nums[0] < nums[1] > nums[2] < nums[3] ...`. Unlike Wiggle Sort I, **strict** inequalities are required even with duplicates. `1 ≤ n ≤ 5·10^4`. (LeetCode 324 — medium/hard.)

**Approach.** Brute force: sort, then split into a smaller half and a larger half and interleave. The subtlety is duplicates near the median: naively interleaving adjacent halves can place two equal medians side by side. The fix is to fill **odd indices first (descending from the larger half)** and **even indices next (descending from the smaller half)** so the two copies of the median are pushed as far apart as possible. The simplest correct version sorts (O(n log n)) and places into a buffer; the optimal version replaces the sort with median-of-medians + 3-way partition for O(n) time, O(1) space (virtual indexing), which interviewers may ask you to describe.

```java
import java.util.Arrays;

public class WiggleSortII {
    // O(n log n) time, O(n) space — clear and correct, the expected baseline.
    public void wiggleSort(int[] nums) {
        int n = nums.length;
        int[] s = nums.clone();
        Arrays.sort(s);
        int[] out = new int[n];
        // larger half -> odd indices, smaller half -> even indices, both descending
        int mid = (n - 1) / 2, hi = n - 1;
        for (int i = 1; i < n; i += 2) out[i] = s[hi--];   // big values on peaks
        for (int i = 0; i < n; i += 2) out[i] = s[mid--];  // small values in valleys
        System.arraycopy(out, 0, nums, 0, n);
    }
}
```

**Why descending placement works.** For `[1,1,1,2,2,2]`, sorted halves are `[1,1,1]` and `[2,2,2]`. Filling odd indices from the top of the big half and even indices from the top of the small half yields `[1,2,1,2,1,2]` — the equal medians never touch. Filling ascending would risk `1,1` adjacency.

**Dry run** `[1,5,1,1,6,4]` → sorted `[1,1,1,4,5,6]`; even idx get `4,1,1` (mid=2 down), odd idx get `6,5,1` → `[4,6,1,5,1,1]`-style valid wiggle (e.g. `[1,6,1,5,1,4]`).

**Complexity.** Baseline Time O(n log n), Space O(n); optimal Time O(n), Space O(1) with median-of-medians + 3-way partition + virtual indexing. **Edge cases:** odd vs even `n` (the `(n-1)/2` split handles both); all-equal input has **no valid arrangement** (strict inequality impossible) — clarify with the interviewer; single element is trivially valid.

---

### Problem 26: Maximum Number of Events / Interval Scheduling (sort + sweep)

**Statement.** Given meeting intervals `[[start, end], ...]`, return the **minimum number of conference rooms** required so that no two overlapping meetings share a room. `1 ≤ n ≤ 10^4`. (LeetCode 253, Meeting Rooms II.)

**Approach.** This is the canonical "sort then sweep" interval problem. Two equivalent optimal formulations: (1) **min-heap of end times** — sort by start, and for each meeting, if the earliest-ending room is free (`heap.peek() <= start`) reuse it, else allocate a new room; the heap size is the answer. (2) **Chronological event sweep** — sort all starts and all ends separately, then walk a two-pointer timeline incrementing a counter on each start and decrementing on each end, tracking the peak. Both are O(n log n) dominated by the sort. The heap version generalizes cleanly to "assign actual room ids."

```java
import java.util.*;

public class MeetingRoomsII {
    // Heap-of-end-times approach.
    public int minMeetingRooms(int[][] intervals) {
        if (intervals.length == 0) return 0;
        Arrays.sort(intervals, (x, y) -> Integer.compare(x[0], y[0]));   // by start
        PriorityQueue<Integer> ends = new PriorityQueue<>();             // min-heap of end times
        for (int[] m : intervals) {
            if (!ends.isEmpty() && ends.peek() <= m[0]) ends.poll();     // a room freed up
            ends.offer(m[1]);                                            // occupy a room
        }
        return ends.size();                                              // peak concurrency
    }

    // Alternative: chronological event sweep, O(n log n), O(n).
    public int minMeetingRoomsSweep(int[][] intervals) {
        int n = intervals.length;
        int[] starts = new int[n], finishes = new int[n];
        for (int i = 0; i < n; i++) { starts[i] = intervals[i][0]; finishes[i] = intervals[i][1]; }
        Arrays.sort(starts); Arrays.sort(finishes);
        int rooms = 0, peak = 0, i = 0, j = 0;
        while (i < n) {
            if (starts[i] < finishes[j]) { rooms++; i++; peak = Math.max(peak, rooms); }
            else { rooms--; j++; }                                       // a meeting ended
        }
        return peak;
    }
}
```

**Dry run** `[[0,30],[5,10],[15,20]]`: sorted by start; `[0,30]`→heap{30}; `[5,10]` 30>5 new room→{10,30}; `[15,20]` 10<=15 reuse→{20,30}. Peak size **2**.

**Complexity.** Time O(n log n); Space O(n). **Edge cases:** touching intervals `[1,5],[5,9]` do not overlap (use `<=` to reuse / `<` in the sweep comparison); single meeting → 1; empty → 0; meetings with `start == end` (zero-length) handled by the boundary comparison.

---

### Problem 27: Merge Intervals (sort by start, coalesce)

**Statement.** Given a collection of intervals, merge all **overlapping** intervals and return the non-overlapping result. `1 ≤ n ≤ 10^4`. (LeetCode 56.)

**Approach.** Sorting by **start** time is the unlock: once sorted, overlaps can only occur between consecutive intervals, so a single left-to-right sweep suffices. Keep a "current" interval; if the next interval's start is `<=` the current end, extend the current end to `max(end, next.end)`; otherwise the current interval is finalized and the next becomes current. This converts an O(n²) all-pairs overlap check into O(n log n) (sort) + O(n) (sweep).

```
sorted by start:
  [1,3] [2,6] [8,10] [15,18]
   |---cur---|
   [1,3] vs [2,6]: 2<=3 -> merge -> [1,6]
   [1,6] vs [8,10]: 8>6  -> emit [1,6], cur=[8,10]
   [8,10] vs [15,18]: 15>10 -> emit [8,10], cur=[15,18]
  result: [1,6] [8,10] [15,18]
```

```java
import java.util.*;

public class MergeIntervals {
    public int[][] merge(int[][] intervals) {
        Arrays.sort(intervals, (a, b) -> Integer.compare(a[0], b[0]));   // by start
        List<int[]> res = new ArrayList<>();
        int[] cur = intervals[0];
        for (int i = 1; i < intervals.length; i++) {
            int[] nxt = intervals[i];
            if (nxt[0] <= cur[1]) {                       // overlap (touching counts)
                cur[1] = Math.max(cur[1], nxt[1]);        // extend
            } else {
                res.add(cur);                             // finalize
                cur = nxt;
            }
        }
        res.add(cur);
        return res.toArray(new int[res.size()][]);
    }
}
```

**Dry run** `[[1,3],[2,6],[8,10],[15,18]]` → `[[1,6],[8,10],[15,18]]`.

**Complexity.** Time O(n log n); Space O(n) output (O(log n) extra if sorting in place). **Edge cases:** fully nested intervals `[1,10],[2,5]` (the `max` keeps the outer end); touching `[1,4],[4,5]` merge because of `<=`; single interval returns itself; already-disjoint input emits everything unchanged.

---

### Problem 28: Non-overlapping Intervals (greedy, sort by end)

**Statement.** Given intervals, return the **minimum number of intervals to remove** so the rest are non-overlapping. `1 ≤ n ≤ 10^5`. (LeetCode 435.)

**Approach.** This is the classic **activity-selection** greedy, and the key insight is to **sort by end time** (not start). Greedily keep the interval that ends earliest; it leaves the most room for the rest. Walk through, tracking the end of the last kept interval; whenever the next interval starts before that end (overlap), remove it (increment the counter) and keep the one with the smaller end — which, since we sorted by end, is the already-kept one, so we simply skip the newcomer. Sorting by start instead would force extra bookkeeping; sorting by end makes the greedy choice provably optimal via an exchange argument.

```java
import java.util.*;

public class NonOverlappingIntervals {
    public int eraseOverlapIntervals(int[][] intervals) {
        if (intervals.length == 0) return 0;
        Arrays.sort(intervals, (a, b) -> Integer.compare(a[1], b[1]));   // by END time
        int kept = 1, end = intervals[0][1];
        for (int i = 1; i < intervals.length; i++) {
            if (intervals[i][0] >= end) {       // no overlap -> keep it
                kept++;
                end = intervals[i][1];
            }                                    // else overlaps -> drop (counted below)
        }
        return intervals.length - kept;          // total minus max non-overlapping kept
    }
}
```

**Dry run** `[[1,2],[2,3],[3,4],[1,3]]` sorted by end `[1,2],[2,3],[1,3],[3,4]`: keep `[1,2]` (end2); `[2,3]` 2>=2 keep (end3); `[1,3]` 1<3 drop; `[3,4]` 3>=3 keep. Kept 3, removed **1**.

**Complexity.** Time O(n log n); Space O(1) extra. **Edge cases:** touching endpoints `[1,2],[2,3]` are non-overlapping (`>=`); identical intervals (all but one removed); single interval → 0 removals; the "sort by end" choice is what makes the greedy optimal — sorting by start is a common wrong answer.

---

### Problem 29: Top K Frequent Elements (bucket sort by frequency, O(n))

**Statement.** Return the `k` most frequent elements. Beat the obvious O(n log n) "sort by frequency." `1 ≤ k ≤ #distinct ≤ n ≤ 10^5`. (LeetCode 347.)

**Approach.** Progression: (1) count with a hash map, then sort entries by frequency → O(n log n). (2) heap of size k → O(n log k). (3) **bucket sort** → **O(n)**: a frequency can be at most `n`, so create `n+1` buckets where `bucket[f]` lists all values occurring exactly `f` times, then walk buckets from high frequency to low and collect until we have `k` values. Because frequencies are bounded integers in `[1, n]`, this is a counting/bucket sort that sidesteps comparison entirely.

```java
import java.util.*;

public class TopKFrequent {
    public int[] topKFrequent(int[] nums, int k) {
        Map<Integer, Integer> freq = new HashMap<>();
        for (int x : nums) freq.merge(x, 1, Integer::sum);

        // bucket[f] = list of values with frequency f
        List<Integer>[] bucket = new List[nums.length + 1];
        for (Map.Entry<Integer, Integer> e : freq.entrySet()) {
            int f = e.getValue();
            if (bucket[f] == null) bucket[f] = new ArrayList<>();
            bucket[f].add(e.getKey());
        }

        int[] res = new int[k];
        int idx = 0;
        for (int f = bucket.length - 1; f >= 1 && idx < k; f--) {   // high freq first
            if (bucket[f] == null) continue;
            for (int v : bucket[f]) {
                res[idx++] = v;
                if (idx == k) break;
            }
        }
        return res;
    }
}
```

**Dry run** `nums=[1,1,1,2,2,3], k=2`: freq {1:3, 2:2, 3:1}; buckets f3→[1], f2→[2], f1→[3]; collect from top → `[1,2]`.

**Complexity.** Time **O(n)**; Space O(n). **Edge cases:** `k == #distinct` (returns all distinct values); ties in frequency (any order accepted by the problem); single element; the bucket array sized `n+1` since the max possible frequency is `n`.

---

### Problem 30: H-Index (counting sort on bounded citations)

**Statement.** Given a researcher's `citations[]`, return the **h-index**: the largest `h` such that at least `h` papers have `≥ h` citations each. `1 ≤ n ≤ 5000`. (LeetCode 274.)

**Approach.** The sort-and-scan answer sorts citations and finds the largest `h` where `citations[n-h] >= h` (O(n log n)). But the h-index is at most `n`, so any citation count above `n` is no more useful than `n` — which means a **counting sort** with buckets clamped to `n` gives **O(n)**. Tally how many papers have exactly `c` citations (clamping `c > n` into bucket `n`), then sweep from the highest citation count downward accumulating a running paper count; the first point where the accumulated count `≥ c` is the h-index.

```java
public class HIndex {
    public int hIndex(int[] citations) {
        int n = citations.length;
        int[] count = new int[n + 1];               // count[c] = #papers with c citations (c>n clamped)
        for (int c : citations) count[Math.min(c, n)]++;
        int papers = 0;
        for (int c = n; c >= 0; c--) {              // from most citations downward
            papers += count[c];
            if (papers >= c) return c;              // h papers each with >= c citations
        }
        return 0;
    }
}
```

**Dry run** `[3,0,6,1,5]`, n=5: count buckets — 0:1,1:1,3:1,5:1,6→clamp5:1 → count[5]=2,count[3]=1,count[1]=1,count[0]=1. Sweep c=5 papers=2 (<5); c=4 papers=2 (<4); c=3 papers=3 (>=3) → **3**.

**Complexity.** Time **O(n)**; Space O(n). **Edge cases:** all-zero citations → 0; a single paper with huge citations clamps to bucket `n`=1 → h=1; the `Math.min(c, n)` clamp is essential to bound the bucket array and is the trick that beats the comparison sort.

---

### Problem 31: Reverse Pairs (mergesort, count a[i] > 2·a[j])

**Statement.** Count pairs `(i, j)` with `i < j` and `nums[i] > 2 · nums[j]`. `1 ≤ n ≤ 5·10^4`; values fit in `int` but `2·nums[j]` can overflow. (LeetCode 493 — hard; a follow-up to inversion counting in Problem 8.)

**Approach.** This generalizes inversion counting. A modified **mergesort** sorts the halves; *before* the normal merge, run a **separate counting pass** over the two sorted halves with a two-pointer scan: for each left element, advance a right pointer while `nums[i] > 2·nums[j]`, accumulating the count. Because both halves are sorted, the right pointer only moves forward across the whole half — total O(n) per merge level, O(n log n) overall. Keep the counting pass separate from the merge because the `> 2·x` condition is not the same as the `<` used to order elements. Use `long` to avoid overflow in `2·nums[j]`.

```java
public class ReversePairs {
    public int reversePairs(int[] nums) {
        return mergeSort(nums, 0, nums.length - 1, new int[nums.length]);
    }

    private int mergeSort(int[] a, int lo, int hi, int[] tmp) {
        if (lo >= hi) return 0;
        int mid = (lo + hi) >>> 1;
        int count = mergeSort(a, lo, mid, tmp) + mergeSort(a, mid + 1, hi, tmp);

        // counting pass: both halves are now sorted
        int j = mid + 1;
        for (int i = lo; i <= mid; i++) {
            while (j <= hi && (long) a[i] > 2L * a[j]) j++;
            count += j - (mid + 1);
        }

        merge(a, lo, mid, hi, tmp);
        return count;
    }

    private void merge(int[] a, int lo, int mid, int hi, int[] tmp) {
        int i = lo, j = mid + 1, k = lo;
        while (i <= mid && j <= hi) tmp[k++] = (a[i] <= a[j]) ? a[i++] : a[j++];
        while (i <= mid) tmp[k++] = a[i++];
        while (j <= hi)  tmp[k++] = a[j++];
        System.arraycopy(tmp, lo, a, lo, hi - lo + 1);
    }
}
```

**Dry run** `[1,3,2,3,1]` → reverse pairs `(3,1)` from index1, `(3,1)` from index3 → **2**.

**Complexity.** Time O(n log n); Space O(n). **Edge cases:** overflow — `2L * a[j]` with negatives (e.g. `nums[i]=2, nums[j]=-3` → `2 > -6` counts) must use `long`; the counting pass uses `>` (strict); arrays of size 0/1 return 0; the two-pointer `j` is reset per merge but advances monotonically within it.

---

### Problem 32: Pancake Sorting (sort using only prefix reversals)

**Statement.** Sort an array using only the operation `flip(k)` that reverses the first `k` elements. Return the sequence of `k` values used; at most `10·n` flips. `1 ≤ n ≤ 100`, values are a permutation of `1..n`. (LeetCode 969.)

**Approach.** A selection-sort variant constrained to prefix reversals. Repeatedly place the current largest unsorted value at the end of the unsorted region using **two flips**: find the max in `[0, size)`, flip it to the front (bringing it to index 0), then flip the whole prefix of length `size` to send it to position `size-1`. Shrink `size` and repeat. Each element costs at most 2 flips, so the total is ≤ `2n` flips — comfortably within the `10n` budget. This shows how a sorting algorithm adapts when the only allowed primitive is a prefix reversal (the classic "burnt pancake" problem).

```
sort [3,2,4,1] by prefix flips, place max at the back each round:
  size=4: max=4 at idx2 -> flip(3) -> [4,2,3,1] -> flip(4) -> [1,3,2,4]
  size=3: max=3 at idx1 -> flip(2) -> [3,1,2,4] -> flip(3) -> [2,1,3,4]
  size=2: max=2 at idx0 -> flip(1) noop -> flip(2) -> [1,2,3,4]
```

```java
import java.util.*;

public class PancakeSort {
    public List<Integer> pancakeSort(int[] a) {
        List<Integer> ops = new ArrayList<>();
        for (int size = a.length; size > 1; size--) {
            int maxIdx = 0;
            for (int i = 1; i < size; i++) if (a[i] > a[maxIdx]) maxIdx = i;
            if (maxIdx == size - 1) continue;             // already in place
            if (maxIdx != 0) { flip(a, maxIdx + 1); ops.add(maxIdx + 1); }  // max -> front
            flip(a, size); ops.add(size);                 // front -> back of unsorted region
        }
        return ops;
    }

    private void flip(int[] a, int k) {                   // reverse a[0..k-1]
        for (int i = 0, j = k - 1; i < j; i++, j--) { int t=a[i]; a[i]=a[j]; a[j]=t; }
    }
}
```

**Dry run** `[3,2,4,1]` → flips `[3,4,2,3,2]` (or similar), ending `[1,2,3,4]`.

**Complexity.** Time O(n²) (each of `n` rounds scans for the max); flips ≤ `2n`; Space O(1) besides the output list. **Edge cases:** value already at the back (skip both flips); `flip(1)` is a no-op but still avoided via the `maxIdx != 0` guard; already-sorted input emits no flips.

---

### Problem 33: Sort an Almost-Sorted (k-sorted) Array with a Min-Heap

**Statement.** Each element is at most `k` positions away from its sorted position (a "k-sorted" array). Sort it in **O(n log k)** time and O(k) space — beating a full O(n log n) sort by exploiting the bound. `1 ≤ k ≤ n ≤ 10^6`.

**Approach.** Because every element is within `k` of its final spot, the smallest remaining element is always within the next `k+1` candidates. Maintain a **min-heap of size `k+1`**: prime it with the first `k+1` elements, then repeatedly poll the minimum (the next sorted value) and push the next input element. The heap never exceeds `k+1`, so each push/poll is O(log k) → O(n log k) total. This is a streaming-friendly specialization: a full heapsort would be O(n log n), but the locality bound lets us cap the heap.

```java
import java.util.*;

public class KSortedArray {
    public void sort(int[] a, int k) {
        PriorityQueue<Integer> heap = new PriorityQueue<>(k + 1);
        int write = 0;
        for (int i = 0; i < a.length; i++) {
            heap.offer(a[i]);
            if (heap.size() > k) {                 // heap holds the window of candidates
                a[write++] = heap.poll();           // smallest so far is final here
            }
        }
        while (!heap.isEmpty()) a[write++] = heap.poll();   // drain the tail
    }
}
```

**Dry run** `a=[6,5,3,2,8,10,9], k=3`: heap fills with `6,5,3`; offer 2 (size4>3) poll→2; offer 8 poll→3; offer 10 poll→5; offer 9 poll→6; drain → 8,9,10 → `[2,3,5,6,8,9,10]`.

**Complexity.** Time **O(n log k)**; Space O(k). **Edge cases:** `k >= n` degrades to ordinary heapsort O(n log n); `k = 0` (already sorted) just streams through; the heap of size `k+1` (not `k`) is required because an element `k` away needs `k+1` candidates in view. Note: the loop uses `> k` so the resident heap size is `k+1` after the first poll.

---

### Problem 34: Custom Sort String (counting / index-map ordering)

**Statement.** Given an `order` string defining a permutation of some characters, and a string `s`, return `s` rearranged so its characters follow the relative order in `order`; characters not present in `order` may go anywhere. `order` and `s` ≤ 200, lowercase letters. (LeetCode 791.)

**Approach.** Two clean techniques. (1) **Counting sort keyed by the custom order**: tally character frequencies in `s`, then emit characters in `order` sequence draining their counts, then append the leftovers — O(n + m), no comparisons, mirroring Problem 20's relative-sort pattern. (2) **Comparator sort**: map each character to its rank in `order` (absent → large/0) and sort `s` by that rank — O(n log n). The counting version is strictly better and is the expected answer when the key domain is small (26 letters).

```java
public class CustomSortString {
    public String customSortString(String order, String s) {
        int[] count = new int[26];
        for (char c : s.toCharArray()) count[c - 'a']++;
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : order.toCharArray()) {           // emit in custom order
            while (count[c - 'a']-- > 0) sb.append(c);
        }
        for (char c = 'a'; c <= 'z'; c++) {            // leftovers (not in order)
            while (count[c - 'a']-- > 0) sb.append(c);
        }
        return sb.toString();
    }
}
```

**Dry run** `order="cba", s="abcd"`: counts a1,b1,c1,d1; emit `c,b,a` then leftover `d` → `"cbad"`.

**Complexity.** Time O(n + m); Space O(1) (fixed 26-int table). **Edge cases:** characters in `s` not in `order` (second loop appends them); duplicates (counts handle multiplicity); empty `s` returns empty; `order` containing letters absent from `s` (their counts are 0, contribute nothing).

---

### Problem 35: Minimum Number of Swaps to Sort an Array (cycle decomposition)

**Statement.** Given an array of distinct integers, return the **minimum number of swaps** needed to sort it ascending. `1 ≤ n ≤ 10^5`. (Classic; related to selection-sort swap counting.)

**Approach.** Sorting itself is O(n log n), but the *minimum swap count* is a permutation property, not a sorting cost. Pair each value with its index, sort by value to learn each element's target position, then decompose the permutation into **cycles**: a cycle of length `L` needs exactly `L − 1` swaps to resolve (rotate everyone into place). Summing over all cycles, the answer is `n − (number of cycles)`. We detect cycles with a `visited` array, following `i → target(i)` until we return to the start. This is O(n log n) for the sort plus O(n) for the traversal.

```
[4,3,2,1] target positions after sorting:
  value 4 -> idx3, 3 -> idx2, 2 -> idx1, 1 -> idx0
  cycles: (0<->3) length2 -> 1 swap, (1<->2) length2 -> 1 swap
  total = 4 - 2 cycles = 2 swaps
```

```java
import java.util.*;

public class MinSwapsToSort {
    public int minSwaps(int[] a) {
        int n = a.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Arrays.sort(idx, (x, y) -> Integer.compare(a[x], a[y]));   // idx[p] = original pos of p-th smallest

        boolean[] seen = new boolean[n];
        int swaps = 0;
        for (int i = 0; i < n; i++) {
            if (seen[i] || idx[i] == i) continue;       // fixed point or already counted
            int cycleLen = 0, j = i;
            while (!seen[j]) { seen[j] = true; j = idx[j]; cycleLen++; }
            swaps += cycleLen - 1;                       // a length-L cycle costs L-1 swaps
        }
        return swaps;
    }
}
```

**Dry run** `[2,8,5,4,1]` → sorted order maps to cycles totaling **5 − 2 = 3** swaps.

**Complexity.** Time O(n log n); Space O(n). **Edge cases:** already-sorted (every element is a fixed point → 0 swaps); a single big reverse-rotation cycle costs `n−1`; duplicates break the "distinct" assumption (the cycle argument needs a well-defined target — clarify or use stable tie-breaking); single element → 0.

---

## 🧩 Extended Problems — Set 3: Hard / Expert & Optimization

### Problem 36: Count of Range Sum (mergesort on prefix sums)

**Statement.** Given `nums` and bounds `[lower, upper]`, count the number of range sums `S(i,j) = nums[i] + ... + nums[j-1]` that lie in `[lower, upper]` inclusive. `1 ≤ n ≤ 10^5`; sums may overflow `int`. (LeetCode 327 — hard.)

**Approach.** Build prefix sums `P[0..n]` where `P[k] = nums[0] + ... + nums[k-1]`. A range sum `S(i,j) = P[j] − P[i]`, so we need to count pairs `i < j` with `lower ≤ P[j] − P[i] ≤ upper`, i.e. `P[j] − upper ≤ P[i] ≤ P[j] − lower`. This is an order-statistic counting problem identical in spirit to inversion counting (Problem 8/31): run a **modified mergesort over `P`**, and before merging each pair of sorted halves, use a two-pointer scan (`lo`, `hi` pointers over the right half for each left element) to count valid `(i, j)` pairs in O(n) per level. Use `long` for prefix sums to avoid overflow.

```
left half (sorted P[i])      right half (sorted P[j])
for each P[i] in left:
   advance lo while P[j]-P[i] <  lower   (too small)
   advance hi while P[j]-P[i] <= upper   (still in range)
   add (hi - lo) valid j's
both pointers move forward only -> O(n) per merge level
```

```java
public class CountRangeSum {
    private long lower, upper;
    private long[] prefix, tmp;

    public int countRangeSum(int[] nums, int lower, int upper) {
        this.lower = lower; this.upper = upper;
        int n = nums.length;
        prefix = new long[n + 1];
        for (int i = 0; i < n; i++) prefix[i + 1] = prefix[i] + nums[i];
        tmp = new long[n + 1];
        return mergeCount(0, n);                       // sort & count over P[0..n]
    }

    private int mergeCount(int lo, int hi) {           // inclusive lo, inclusive hi
        if (lo >= hi) return 0;
        int mid = (lo + hi) >>> 1;
        int count = mergeCount(lo, mid) + mergeCount(mid + 1, hi);

        // counting pass: both halves sorted ascending
        int l = mid + 1, r = mid + 1;
        for (int i = lo; i <= mid; i++) {
            while (l <= hi && prefix[l] - prefix[i] < lower) l++;   // first in range
            while (r <= hi && prefix[r] - prefix[i] <= upper) r++;  // first beyond range
            count += r - l;
        }

        merge(lo, mid, hi);
        return count;
    }

    private void merge(int lo, int mid, int hi) {
        int i = lo, j = mid + 1, k = lo;
        while (i <= mid && j <= hi) tmp[k++] = (prefix[i] <= prefix[j]) ? prefix[i++] : prefix[j++];
        while (i <= mid) tmp[k++] = prefix[i++];
        while (j <= hi)  tmp[k++] = prefix[j++];
        System.arraycopy(tmp, lo, prefix, lo, hi - lo + 1);
    }
}
```

**Dry run** `nums=[-2,5,-1], lower=-2, upper=2`. Prefix `[0,-2,3,2]`. Valid range sums: `S(0,1)=-2`, `S(2,3)=-1`, `S(0,3)=2` → **3**.

**Complexity.** Time O(n log n); Space O(n). **Edge cases:** overflow — prefix sums must be `long` (sum of 10^5 ints near `int` max overflows); negative numbers handled naturally (the two-pointer monotonicity still holds because each half is sorted); single element; `lower == upper` (count exact sums); all-zero array with `lower ≤ 0 ≤ upper` counts every subarray.

---

### Problem 37: Maximum Gap via In-Place Radix (LSD, beat n log n with O(n) space)

**Statement.** Same as Problem 7 (max difference between successive sorted elements in O(n)), but implement the **radix-sort variant** explicitly and discuss why base-256 (4 passes for 32-bit) is the engineering sweet spot over base-10 (10 passes). `n ≤ 10^6`, `0 ≤ a[i] ≤ 2^31−1`.

**Approach.** Sort the array with **LSD radix sort using base 256** — process the 32-bit key in four 8-bit chunks from least to most significant, each pass a stable counting sort over 256 buckets. Four passes regardless of value magnitude (versus up to 10 for base-10), and each pass touches `n + 256` work, so total is `4·(n + 256) = O(n)`. After sorting, one linear scan finds the maximum adjacent gap. The base choice trades pass count `d = ⌈bits / log2(base)⌉` against per-pass bucket cost `O(n + base)`; base 256 minimizes `d·(n + base)` for 32-bit integers.

```java
public class MaximumGapRadix {
    public int maximumGap(int[] a) {
        int n = a.length;
        if (n < 2) return 0;
        radixSort(a);
        int gap = 0;
        for (int i = 1; i < n; i++) gap = Math.max(gap, a[i] - a[i - 1]);
        return gap;
    }

    private void radixSort(int[] a) {                 // non-negative ints, base 256
        int n = a.length;
        int[] out = new int[n];
        for (int shift = 0; shift < 32; shift += 8) { // 4 passes
            int[] count = new int[257];
            for (int x : a) count[((x >>> shift) & 0xFF) + 1]++;   // offset by 1 for prefix
            for (int b = 0; b < 256; b++) count[b + 1] += count[b];
            for (int x : a) out[count[(x >>> shift) & 0xFF]++] = x; // stable, left-to-right
            System.arraycopy(out, 0, a, 0, n);                     // copy sorted-by-byte back
        }
    }
}
```

**Dry run** `[3,6,9,1]` → radix sorts to `[1,3,6,9]`; adjacent gaps `2,3,3` → max **3** (matches Problem 7's bucket answer).

**Complexity.** Time O(n) (4 fixed passes, each O(n + 256)); Space O(n). **Edge cases:** `n < 2` → 0; this "count+1 offset, then place left-to-right" idiom keeps the pass stable (essential for LSD correctness); negative values would need a sign-bit flip trick (XOR the top bit) — out of scope here as values are non-negative; values needing fewer than 4 bytes still cost 4 passes (the high-byte pass is a cheap no-op shuffle).

---

### Problem 38: Sort with Limited Distinct Values in O(n) (Dutch flag generalized to k buckets)

**Statement.** An array contains only `k` distinct values (e.g., a known small set of category codes), with `k ≪ n`. Sort it in **O(n) time** without a general comparison sort, and discuss the in-place k-partition trade-off. `1 ≤ n ≤ 10^7`, `2 ≤ k ≤ 1000`, values mapped to `0..k−1`.

**Approach.** With only `k` distinct keys, a **counting sort over `k` buckets** is O(n + k) = O(n) since `k ≪ n` — tally each value, then overwrite the array in order. If `k = 3` this is exactly Sort Colors (Problem 2). The follow-up interviewers push on: counting sort here is stable-by-construction for primitives and uses O(k) extra space, while a true **in-place k-way Dutch-flag partition** (multiple region pointers) avoids the rewrite but is fiddly and only worth it when k is tiny; for general small k, the count-and-overwrite is simplest and cache-friendly.

```java
public class SortKDistinct {
    public void sort(int[] a, int k) {                // values in [0, k-1]
        int[] count = new int[k];
        for (int x : a) count[x]++;                   // O(n)
        int idx = 0;
        for (int v = 0; v < k; v++) {                 // O(n + k) overwrite in order
            int c = count[v];
            while (c-- > 0) a[idx++] = v;
        }
    }
}
```

**Dry run** `a=[2,0,1,2,1,0], k=3`: counts `[2,2,2]`; overwrite → `[0,0,1,1,2,2]`.

**Complexity.** Time O(n + k) = O(n); Space O(k). **Edge cases:** `k = 2` is a boolean partition; values outside `[0,k−1]` would index out of bounds (validate input or clamp); all-same value (one bucket holds everything); the overwrite assumes values *are* the keys — if they carry satellite data, switch to the stable placement form (prefix sums) of Problem 15.

---

### Problem 39: Maximum Performance of a Team (sort by one key, heap on the other)

**Statement.** Given `n` engineers each with `speed[i]` and `efficiency[i]`, pick at most `k` of them to maximize `(sum of chosen speeds) × (min chosen efficiency)`. Return it modulo `1e9+7`. `1 ≤ k ≤ n ≤ 10^5`. (LeetCode 1383 — hard.)

**Approach.** The product couples a **sum** (speed) with a **min** (efficiency), so brute force over subsets is exponential. The unlock: **sort engineers by efficiency descending**. Iterate in that order; when we consider engineer `i`, every engineer already seen has efficiency `≥ efficiency[i]`, so if `i` is the minimum-efficiency member, the team is any subset of the seen-so-far engineers (plus `i`) — and to maximize the speed sum under the size-`k` cap we greedily keep the `k` largest speeds via a **min-heap of speeds**. At each step, `efficiency[i]` is the candidate min, `(heapSum) × efficiency[i]` is a candidate answer. This pairs a sort on one dimension with a heap on the other — a hallmark senior pattern.

```java
import java.util.*;

public class MaxPerformance {
    public int maxPerformance(int n, int[] speed, int[] efficiency, int k) {
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Arrays.sort(idx, (x, y) -> efficiency[y] - efficiency[x]);   // efficiency descending

        PriorityQueue<Integer> minSpeed = new PriorityQueue<>();     // keep k largest speeds
        long sum = 0, best = 0;
        for (int i : idx) {
            minSpeed.offer(speed[i]);
            sum += speed[i];
            if (minSpeed.size() > k) sum -= minSpeed.poll();         // drop smallest speed
            best = Math.max(best, sum * efficiency[i]);              // i is current min efficiency
        }
        return (int) (best % 1_000_000_007L);
    }
}
```

**Dry run** `speed=[2,10,3,1,5,8], eff=[5,4,3,9,7,2], k=2`. Sorted by eff desc: idx with eff 9,7,5,4,3,2. Best comes from speeds {5,10}? Walking: eff9 speed1 →1×9=9; eff7 speed5, sum6 →6×7=42; eff5 speed2, heap{5,2,1}>2 drop1 sum7 →7×5=35; eff4 speed10 sum drop2→ sum15 →15×4=60 → **best 60**.

**Complexity.** Time O(n log n) (sort) + O(n log k) (heap); Space O(n). **Edge cases:** apply the modulo only at the **end** (taking it during accumulation breaks the `max` comparison); `k = n` (no eviction); single engineer; `long` for `sum * efficiency` to avoid overflow before the modulo (each can be ~10^5, product ~10^10·10^5 fits in long after the running sum).

---

### Problem 40: Create Maximum Number (greedy + merge of two sequences)

**Statement.** Given two int-digit arrays `nums1`, `nums2` and an integer `k`, create the **maximum number of length `k`** by picking digits from the two arrays preserving each array's relative order, interleaving them however you like. Return the `k` digits. `0 ≤ k ≤ m + n`. (LeetCode 321 — hard.)

**Approach.** Decompose: choose `i` digits from `nums1` and `k − i` from `nums2`. For each split, pick the **maximum subsequence of a given length** from each array (a monotonic-stack greedy: pop smaller trailing digits while we can still afford to), then **merge** the two maximum subsequences into the largest interleaving (greedy lexicographic merge — when prefixes tie, look ahead at the full remaining suffixes to break the tie). Try every valid `i` and keep the best result. The "merge two sequences into the largest" step is a sorting/merging primitive specialized to maximize, not order.

```java
import java.util.*;

public class CreateMaximumNumber {
    public int[] maxNumber(int[] nums1, int[] nums2, int k) {
        int m = nums1.length, n = nums2.length;
        int[] best = new int[k];
        for (int i = Math.max(0, k - n); i <= Math.min(k, m); i++) {
            int[] cand = merge(maxSubseq(nums1, i), maxSubseq(nums2, k - i));
            if (greater(cand, 0, best, 0)) best = cand;
        }
        return best;
    }

    // largest subsequence of length t from arr, preserving order (monotonic stack)
    private int[] maxSubseq(int[] arr, int t) {
        int[] stack = new int[t];
        int top = 0, drop = arr.length - t;          // how many we may discard
        for (int x : arr) {
            while (top > 0 && drop > 0 && stack[top - 1] < x) { top--; drop--; }
            if (top < t) stack[top++] = x; else drop--;
        }
        return stack;
    }

    // merge into the lexicographically largest interleaving
    private int[] merge(int[] a, int[] b) {
        int[] r = new int[a.length + b.length];
        int i = 0, j = 0, k = 0;
        while (i < a.length || j < b.length) {
            r[k++] = greater(a, i, b, j) ? a[i++] : b[j++];   // tie -> compare suffixes
        }
        return r;
    }

    // is suffix a[i..] lexicographically greater than b[j..]?
    private boolean greater(int[] a, int i, int[] b, int j) {
        while (i < a.length && j < b.length && a[i] == b[j]) { i++; j++; }
        return j == b.length || (i < a.length && a[i] > b[j]);
    }
}
```

**Dry run** `nums1=[3,4,6,5], nums2=[9,1,2,5,8,3], k=5` → answer `[9,8,6,5,3]`.

**Complexity.** Time O(k·(m + n + k²)) worst (per split: subsequence O(m+n), merge with suffix compares O(k²)); Space O(k). **Edge cases:** `k = 0` → empty; one array empty (only that split is valid); the `greater` tie-break on equal prefixes is essential (`[6,7]` vs `[6,0,...]` must prefer the array whose suffix is larger); duplicate digits handled by the suffix comparison.

---

### Problem 41: Find Median from Data Stream (two heaps, online order statistic)

**Statement.** Design a data structure supporting `addNum(int)` and `findMedian()` on a growing stream. Both should be efficient; you cannot re-sort the whole stream on each query. `≤ 5·10^4` calls. (LeetCode 295 — hard.)

**Approach.** Re-sorting on each `findMedian` is O(n log n) per call. The optimal **online** structure keeps the data partitioned around the median using **two heaps**: a **max-heap `lo`** for the smaller half and a **min-heap `hi`** for the larger half, maintaining the invariants `lo.size() ∈ {hi.size(), hi.size()+1}` and `max(lo) ≤ min(hi)`. Insertion routes the element and rebalances in O(log n); the median is `lo.peek()` (odd total) or the average of the two tops (even total) in O(1). This is the canonical "incremental sorting / running order statistic" answer.

```
        max-heap lo            min-heap hi
   [ smaller half ]  | median |  [ larger half ]
   top = largest of lo          top = smallest of hi
   invariant: every lo element <= every hi element
              size(lo) == size(hi)  or  size(lo) == size(hi)+1
```

```java
import java.util.*;

public class MedianFinder {
    private final PriorityQueue<Integer> lo = new PriorityQueue<>(Collections.reverseOrder()); // max-heap
    private final PriorityQueue<Integer> hi = new PriorityQueue<>();                            // min-heap

    public void addNum(int num) {
        lo.offer(num);                       // always push to lo first
        hi.offer(lo.poll());                 // move its max to hi (keeps lo <= hi ordering)
        if (hi.size() > lo.size()) lo.offer(hi.poll());  // rebalance: lo holds the extra
    }

    public double findMedian() {
        return lo.size() > hi.size()
            ? lo.peek()                      // odd count -> middle is lo's top
            : (lo.peek() + hi.peek()) / 2.0; // even count -> average of the two tops
    }
}
```

**Dry run** add 1 → lo[1]; add 2 → lo[1], hi[2]; median (1+2)/2 = **1.5**; add 3 → lo[2,1], hi[3]; median **2**.

**Complexity.** `addNum` O(log n); `findMedian` O(1); Space O(n). **Edge cases:** empty stream (`findMedian` before any add is undefined — guard if required); single element (`lo` holds it, returns it); the push-to-`lo`-then-shuffle pattern guarantees the cross-heap ordering without an explicit comparison; integer average uses `/2.0` to avoid truncation.

---

### Problem 42: Sliding Window Median (balanced multiset / two heaps with lazy deletion)

**Statement.** Given `nums` and window size `k`, return the median of each contiguous window as it slides. `1 ≤ k ≤ n ≤ 10^5`. (LeetCode 480 — hard.)

**Approach.** Naively sorting each window is O(n·k log k). The clean optimal is a **balanced ordered multiset** (`TreeMap` of value → count, or in Java a `TreeMap`/two-`TreeSet` trick) supporting insert, remove, and median lookup in O(log k). The two-heaps approach (Problem 41) also works but needs **lazy deletion** because heaps cannot remove arbitrary elements. Here we use two `TreeSet`s of indices ordered by `(value, index)` so duplicates are distinguishable: `left` (lower half) and `right` (upper half), kept balanced; the median reads from the boundary in O(log k), and each slide does one insert + one remove.

```java
import java.util.*;

public class SlidingWindowMedian {
    public double[] medianSlidingWindow(int[] nums, int k) {
        Comparator<Integer> byVal = (a, b) ->
            nums[a] != nums[b] ? Integer.compare(nums[a], nums[b]) : Integer.compare(a, b);
        TreeSet<Integer> left = new TreeSet<>(byVal.reversed());  // lower half, max at first()
        TreeSet<Integer> right = new TreeSet<>(byVal);            // upper half, min at first()

        double[] res = new double[nums.length - k + 1];
        for (int i = 0; i < nums.length; i++) {
            right.add(i); left.add(right.pollFirst());            // route then rebalance
            if (left.size() > right.size()) right.add(left.pollFirst());

            if (i >= k - 1) {
                res[i - k + 1] = left.size() == right.size()
                    ? ((double) nums[left.first()] + nums[right.first()]) / 2.0
                    : nums[right.first()];                        // right holds the extra when odd
                int out = i - k + 1;                              // index leaving the window
                if (!left.remove(out)) right.remove(out);         // remove from whichever set holds it
                if (left.size() > right.size()) right.add(left.pollFirst());
                if (right.size() > left.size() + 1) left.add(right.pollFirst());
            }
        }
        return res;
    }
}
```

**Dry run** `nums=[1,3,-1,-3,5,3,6,7], k=3` → medians `[1, -1, -1, 3, 5, 6]`.

**Complexity.** Time O(n log k); Space O(k). **Edge cases:** overflow in the even-median average — cast to `double` *before* adding (two `Integer.MAX_VALUE`s sum overflows `int`); ordering by `(value, index)` is what lets the set hold duplicate values; `k = 1` (each element is its own median); the rebalancing after removal restores the size invariant in both directions.

---

### Problem 43: Minimum Number of Swaps to Make Sequences Increasing (DP over sorted constraint)

**Statement.** Given two integer arrays `A` and `B` of equal length, in one operation you may swap `A[i]` with `B[i]`. Return the **minimum swaps** to make BOTH `A` and `B` strictly increasing (a valid answer always exists). `2 ≤ n ≤ 10^5`. (LeetCode 801 — hard.)

**Approach.** This is "sorting under a swap constraint," solved by **DP**, not by a sort itself — interviewers use it to test whether you recognize that the strictly-increasing requirement at each adjacent pair is a local constraint. Track two states per index: `keep[i]` = min swaps to make `A[0..i]`, `B[0..i]` increasing with position `i` **not** swapped, and `swap[i]` = same but with position `i` **swapped**. Transition by comparing the adjacent pair against both arrangements of the previous position. Only the previous two values matter, so it runs in O(n) time, O(1) space.

```java
public class MinSwapsIncreasing {
    public int minSwap(int[] A, int[] B) {
        int keep = 0, swap = 1;                       // index 0: 0 swaps to keep, 1 to swap
        for (int i = 1; i < A.length; i++) {
            int nk = Integer.MAX_VALUE, ns = Integer.MAX_VALUE;
            if (A[i] > A[i - 1] && B[i] > B[i - 1]) {  // both stay sorted with no new swap
                nk = Math.min(nk, keep);               // prev not swapped, this not swapped
                ns = Math.min(ns, swap + 1);           // prev swapped, this swapped too
            }
            if (A[i] > B[i - 1] && B[i] > A[i - 1]) {  // cross-comparison valid
                nk = Math.min(nk, swap);               // prev swapped, this not swapped
                ns = Math.min(ns, keep + 1);           // prev not swapped, this swapped
            }
            keep = nk; swap = ns;
        }
        return Math.min(keep, swap);
    }
}
```

**Dry run** `A=[1,3,5,4], B=[1,2,3,7]`: at i=3 the pair `(4,7)` vs prev `(5,3)` — swapping index 3 gives `A=[..5,7], B=[..3,4]` increasing → **1** swap.

**Complexity.** Time O(n); Space O(1). **Edge cases:** at least one of the two `if`s is always satisfiable (guaranteed by "an answer exists"), so `nk`/`ns` never stay `MAX_VALUE`; strictly increasing means `>` not `>=`; `n = 2` is the base transition; the two states must both be carried — collapsing to one loses the swapped branch needed later.

---

### Problem 44: Maximum Distance in Arrays (sort by extremes / running min-max)

**Statement.** Given `m` arrays, each **individually sorted ascending**, pick one element from two **different** arrays to maximize `|a − b|`. Return the max distance. `2 ≤ m ≤ 10^5`, total elements ≤ 10^5. (LeetCode 624 — medium/hard variant.)

**Approach.** The naive O(m²) compares every pair of arrays' extremes. The optimal O(m) sweep exploits that each array is already sorted, so its only relevant values are its **first (min)** and **last (max)**. Track the running `min` and `max` seen across **previously processed** arrays; for the current array, the best distance using a *different* array is `max(curMax − runningMin, runningMax − curMin)`. Update the running extremes after computing, guaranteeing the two endpoints come from different arrays. This is "sort already done; just sweep the boundary values."

```java
import java.util.*;

public class MaxDistanceArrays {
    public int maxDistance(List<List<Integer>> arrays) {
        int res = 0;
        List<Integer> first = arrays.get(0);
        int min = first.get(0);                       // smallest seen so far
        int max = first.get(first.size() - 1);        // largest seen so far
        for (int i = 1; i < arrays.size(); i++) {
            List<Integer> cur = arrays.get(i);
            int lo = cur.get(0), hi = cur.get(cur.size() - 1);
            res = Math.max(res, Math.abs(hi - min));   // current max vs earlier min
            res = Math.max(res, Math.abs(max - lo));   // earlier max vs current min
            min = Math.min(min, lo);
            max = Math.max(max, hi);
        }
        return res;
    }
}
```

**Dry run** `[[1,2,3],[4,5],[1,2,3]]`: start min1 max3; arr2 lo4 hi5 → |5−1|=4, |3−4|=1 → res4, min1 max5; arr3 lo1 hi3 → |3−1|=2,|5−1|=4 → res **4**.

**Complexity.** Time O(m); Space O(1). **Edge cases:** must not pick both endpoints from the same array (the "update running extremes *after* comparing" ordering enforces this); arrays of size 1 (min == max for that array); negative values (use the signed difference, `abs` covers both directions); exactly two arrays reduces to comparing their cross-extremes.

---

### Problem 45: Minimum Cost to Connect Sticks / Optimal Merge Pattern (greedy with a heap)

**Statement.** Given lengths of `n` sticks, connecting two sticks of lengths `x` and `y` costs `x + y` and yields a stick of length `x + y`. Connect all sticks into one; return the **minimum total cost**. `1 ≤ n ≤ 10^4`. (LeetCode 1167; equivalently the optimal-merge / Huffman pattern.)

**Approach.** This is the **optimal merge pattern** (the cost structure of Huffman coding). Greedily always merge the **two smallest** current sticks, because shorter sticks merged early get re-added (and re-paid) more times — so we want the smallest values to participate in the most additions. A **min-heap** delivers the two smallest in O(log n); push back their sum and repeat. Total work is O(n log n). Trying to sort once is insufficient because each merge creates a new value that must be re-inserted in sorted order — exactly what the heap maintains incrementally.

```java
import java.util.*;

public class ConnectSticks {
    public int connectSticks(int[] sticks) {
        PriorityQueue<Integer> pq = new PriorityQueue<>();
        for (int s : sticks) pq.offer(s);
        int cost = 0;
        while (pq.size() > 1) {
            int a = pq.poll(), b = pq.poll();         // two smallest
            int merged = a + b;
            cost += merged;                            // pay to connect
            pq.offer(merged);                          // re-insert combined stick
        }
        return cost;
    }
}
```

**Dry run** `[2,4,3]`: merge 2+3=5 (cost5), heap{4,5}; merge 4+5=9 (cost+9=14) → **14**.

**Complexity.** Time O(n log n); Space O(n). **Edge cases:** single stick → cost 0 (the `size() > 1` guard exits immediately); equal lengths handled by the heap naturally; large sums can overflow `int` for big inputs — use `long` if lengths and `n` are large (here constraints keep it within `int`); the greedy is provably optimal by an exchange argument (same as Huffman).

---

### Problem 46: Hand of Straights / Divide Array into Consecutive Groups (sort + greedy counting)

**Statement.** Given `hand` of card values and a group size `k` (a.k.a. `groupSize`), determine whether the cards can be rearranged into groups of `k` **consecutive** values each. `1 ≤ n ≤ 10^4`. (LeetCode 846 / 1296.)

**Approach.** The greedy works on **sorted-by-value frequencies**: the smallest remaining card *must* start a group (nothing smaller can absorb it), so that group is forced to be `[v, v+1, ..., v+k−1]`. Use a `TreeMap` (a balanced BST that keeps keys sorted) to always grab the current minimum value, then decrement counts for the `k` consecutive values, failing if any is missing. The sorted structure is what makes the forced-group greedy correct and efficient: O(n log n) overall, dominated by maintaining sorted order.

```java
import java.util.*;

public class HandOfStraights {
    public boolean isNStraightHand(int[] hand, int k) {
        if (hand.length % k != 0) return false;       // must divide evenly
        TreeMap<Integer, Integer> count = new TreeMap<>();
        for (int c : hand) count.merge(c, 1, Integer::sum);

        while (!count.isEmpty()) {
            int start = count.firstKey();              // smallest remaining must lead a group
            for (int v = start; v < start + k; v++) {
                Integer cnt = count.get(v);
                if (cnt == null) return false;         // missing consecutive value -> impossible
                if (cnt == 1) count.remove(v);
                else count.put(v, cnt - 1);
            }
        }
        return true;
    }
}
```

**Dry run** `hand=[1,2,3,6,2,3,4,7,8], k=3`: groups `[1,2,3]`, `[2,3,4]`, `[6,7,8]` → **true**.

**Complexity.** Time O(n log n) (TreeMap ops over n distinct values, each group walk O(k)); Space O(n). **Edge cases:** `n % k != 0` → immediately false; `k = 1` (always true, each card its own group); duplicates within the needed range consumed correctly via counts; a value gap inside a forced group fails fast.

---

### Problem 47: Maximum Number of Tasks with Workers (sort both + greedy with pills, binary search)

**Statement.** `tasks[i]` is a strength requirement, `workers[j]` a strength; a worker can do a task if `strength ≥ requirement`. You have `pills` magic pills, each adding `strength` to one worker. Maximize the number of tasks completed (one task per worker). `1 ≤ tasks, workers ≤ 5·10^4`. (LeetCode 2071 — hard.)

**Approach.** Both arrays sorted, then **binary search on the answer** `c` (can we complete `c` tasks?), with a greedy feasibility check. To check `c`: take the `c` **easiest** tasks and the `c` **strongest** workers. Process tasks from hardest (of these `c`) downward; for each, if the strongest available worker can do it unaided, use them; otherwise try the *weakest* worker who can do it **with a pill** (found by binary search / a `TreeMultiset`-like balanced structure), spending one pill. If neither works, `c` is infeasible. Monotonicity (if `c` works, `c−1` works) justifies the outer binary search → O(n log²n).

```java
import java.util.*;

public class MaxTaskAssign {
    public int maxTaskAssign(int[] tasks, int[] workers, int pills, int strength) {
        Arrays.sort(tasks);
        Arrays.sort(workers);
        int lo = 0, hi = Math.min(tasks.length, workers.length), ans = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (canAssign(tasks, workers, pills, strength, mid)) { ans = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        return ans;
    }

    private boolean canAssign(int[] tasks, int[] workers, int pills, int strength, int c) {
        if (c == 0) return true;
        // easiest c tasks; strongest c workers in a multiset (TreeMap value->count)
        TreeMap<Integer, Integer> avail = new TreeMap<>();
        for (int i = workers.length - c; i < workers.length; i++) avail.merge(workers[i], 1, Integer::sum);
        int p = pills;
        for (int t = c - 1; t >= 0; t--) {                 // hardest of the c tasks first
            int need = tasks[t];
            Integer strongest = avail.lastKey();
            if (strongest >= need) {                        // do it without a pill
                remove(avail, strongest);
            } else {
                if (p == 0) return false;                   // no pill -> cannot place this task
                Integer w = avail.ceilingKey(need - strength); // weakest worker that a pill lifts to >= need
                if (w == null) return false;
                remove(avail, w);
                p--;
            }
        }
        return true;
    }

    private void remove(TreeMap<Integer, Integer> m, int key) {
        int v = m.get(key);
        if (v == 1) m.remove(key); else m.put(key, v - 1);
    }
}
```

**Dry run** `tasks=[3,2,1], workers=[0,3,3], pills=1, strength=1`: `c=3` check fails (worker 0 can't even with pill reach task needing... ) → binary search settles at **3** for this instance per LeetCode (`[0+1≥1, 3≥2, 3≥3]`).

**Complexity.** Time O(n log²n) (binary search × O(n log n) feasibility); Space O(n). **Edge cases:** `c = 0` trivially feasible; `pills = 0` reduces to a pure greedy match; `ceilingKey(need - strength)` finds the *weakest* worker a pill can lift to the requirement (saving strong workers for harder tasks — the key greedy insight); spending a pill on the weakest qualifying worker, not the strongest, is what makes the greedy optimal.

---

## Interview Q&A by Level

### 🟢 Basic

- **Q: Difference between stable and unstable sort?** A stable sort preserves the relative order of equal keys. Mergesort/insertion/counting are stable; quicksort/heapsort/selection are not. Matters for multi-key sorting.
- **Q: Which simple sort is best for nearly-sorted or tiny arrays?** Insertion sort — O(n) on nearly-sorted, adaptive, low constant factor; that's why TimSort and dual-pivot quicksort fall back to it for small subarrays.
- **Q: Best/worst case of quicksort?** Best/average O(n log n); worst O(n²) when the pivot is repeatedly the extreme (e.g., sorted input with last-element pivot). Randomize the pivot.
- **Q: Why is bubble sort almost never used?** O(n²) with a high constant and many writes; selection sort minimizes writes; insertion sort beats it on real data.

### 🟡 Intermediate

- **Q: When does counting/radix beat quicksort?** When keys are integers in a small range `k` (counting, O(n+k)) or fixed-width digits (radix, O(d·n)). Non-comparison sorts dodge the n log n bound.
- **Q: Lomuto vs Hoare partition?** Lomuto is simpler (one pointer, pivot ends at final index) but does more swaps and degrades badly on duplicates. Hoare uses two pointers, ~3× fewer swaps, but the pivot isn't placed at its final position.
- **Q: How do you make quicksort's stack O(log n)?** Recurse into the smaller partition and iterate (tail-eliminate) on the larger; depth ≤ log₂n.
- **Q: Why mergesort for linked lists?** No random access needed; merge is pure pointer rewiring (no copies), it's stable, and it avoids quicksort's O(n²) risk.

### 🟠 Advanced

- **Q: Prove the Ω(n log n) lower bound.** Comparison sorts form a binary decision tree with ≥ n! leaves (one per permutation); height ≥ log₂(n!) = Θ(n log n) by Stirling. Worst case = tree height.
- **Q: How does TimSort work?** It finds existing ascending/descending **runs**, extends short runs with binary insertion sort to a minimum run length (~32–64), then merges runs using a stack with invariants (run lengths roughly Fibonacci-like) and **galloping mode** to skip large equal stretches. Adaptive: O(n) on already-sorted data, O(n log n) worst.
- **Q: Why does the JDK use different sorts for primitives vs objects?** `Arrays.sort(int[])` uses **dual-pivot quicksort** (no stability needed for primitives, in-place, cache-friendly). `Arrays.sort(Object[])` / `Collections.sort` use **TimSort** because object sorts must be **stable** and benefit from adaptivity on partially ordered data.
- **Q: How do you make quicksort robust to many duplicates?** **3-way partition** (Dutch flag): split into `<`, `==`, `>`; equal elements aren't recursed, giving O(n) on all-equal arrays.

### 🔴 Expert

- **Q: Dual-pivot quicksort — why two pivots?** Partitioning into three regions (`< p1`, `p1 ≤ x ≤ p2`, `> p2`) reduces the number of element moves and improves cache/branch behavior versus single-pivot; empirically ~10% faster, which is why Java 7+ adopted it for primitives.
- **Q: Defend against the quicksort O(n²) DoS attack.** Deterministic pivots (e.g., median-of-three) are exploitable with adversarial input (the "killer" sequence that broke older JDKs). Mitigations: randomized pivot, introspection. **Introsort** starts with quicksort, counts recursion depth, and switches to **heapsort** once depth exceeds ~2·log₂n — guaranteeing O(n log n) worst case while keeping quicksort's speed (C++ `std::sort`).
- **Q: How to sort terabytes across a cluster?** Distributed external sort: range-partition keys (sampling for balanced ranges), sort partitions locally (each an external sort), then concatenate. This is the MapReduce shuffle/sort and the basis of TeraSort. Watch for skew (hot keys) → salting or dynamic repartition.
- **Q: Amortized/space trade in heapsort vs mergesort at scale?** Heapsort is O(1) space and worst-case O(n log n) but cache-hostile (poor locality) → slower in practice. Mergesort is cache-friendly and the natural base for external/parallel sort but needs O(n) buffer. Choose by whether memory or locality is the binding constraint.
- **Q: Can you sort in O(n) generally?** No comparison sort can. Non-comparison sorts (counting/radix/bucket) achieve it only under key-structure assumptions (bounded range, fixed width, or uniform distribution). State the assumption explicitly in interviews.

---

## ⚠️ Common Pitfalls

- **Overflow in midpoint:** use `int mid = lo + (hi - lo) / 2;` or `(lo + hi) >>> 1`, never `(lo + hi) / 2`.
- **Quicksort on sorted input with fixed pivot** → O(n²). Always randomize or use median-of-three.
- **Forgetting stability requirements:** sorting records by a secondary key then a primary key only works if both passes are stable.
- **Dutch-flag bug:** after swapping with the `hi` pointer, do **not** advance the scanner — the incoming element is unexamined.
- **Comparator contract violations:** `(a, b) -> a - b` overflows for large ints (use `Integer.compare`); inconsistent comparators throw `IllegalArgumentException: Comparison method violates its general contract!` under TimSort.
- **Counting/radix on negative or huge-range keys:** offset by `min`, or split sign, or bound the range — otherwise memory blows up.
- **`Arrays.sort` worst case:** the primitive dual-pivot quicksort is O(n²) on adversarial input; if you control untrusted input and need guarantees, box to `Integer[]` (TimSort) or shuffle first.
- **Recursion depth:** naive quicksort recursing into both sides can blow the stack on sorted input; recurse smaller-side-first.
- **Bucket sort assumption:** non-uniform data collapses everything into one bucket → O(n²).

## 📚 Further Reading

- CLRS, *Introduction to Algorithms* — Ch. 6 (heapsort), 7 (quicksort), 8 (linear-time sorts, lower bound), 8.3 (radix).
- Sedgewick & Wayne, *Algorithms (4th ed.)* — quicksort 3-way, mergesort, priority-queue sorts; `algs4` Java code.
- OpenJDK source: `java.util.DualPivotQuicksort`, `java.util.TimSort`, `java.util.ComparableTimSort`.
- Tim Peters' original TimSort listsort.txt description; Peter McIlroy, "Optimistic Sorting and Information Theoretic Complexity."
- Knuth, *TAOCP Vol. 3: Sorting and Searching* — external sorting, replacement selection, merge patterns.
- Jon Bentley & Doug McIlroy, "Engineering a Sort Function" (the practical quicksort tuning paper).
- LeetCode tag: Sorting; problems 75, 88, 148, 23, 215, 164, 315, 912.

---

[← Back to master index](../README.md) &nbsp;|&nbsp; [← DSA index](README.md)
