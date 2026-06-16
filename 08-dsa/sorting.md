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
