# Heaps & Priority Queues

A **heap** is a complete binary tree stored in an array that keeps the smallest (or largest) element instantly accessible at the root. It is the engine behind the **priority queue** — the go-to structure whenever you repeatedly need "the next most important element" without paying to fully sort the data.

[← Back to master index](../README.md) &nbsp;|&nbsp; [← DSA index](README.md)

---

## Concept & Intuition

A **binary heap** is a *complete* binary tree (every level full except possibly the last, which fills left-to-right) that satisfies the **heap-order property**:

- **Min-heap:** every parent ≤ its children → the global minimum sits at the root.
- **Max-heap:** every parent ≥ its children → the global maximum sits at the root.

Because the tree is complete, it has no "holes" and can be packed into a plain array — no pointers, perfect cache locality.

### Array representation (0-indexed)

For a node at index `i`:

```
parent(i)      = (i - 1) / 2
leftChild(i)   = 2*i + 1
rightChild(i)  = 2*i + 2
```

A min-heap `[1, 3, 6, 5, 9, 8]` is the array view of this tree:

```
            1            index 0
          /   \
        3       6        index 1, 2
       / \     /
      5   9   8          index 3, 4, 5

array: [ 1 , 3 , 6 , 5 , 9 , 8 ]
idx:     0   1   2   3   4   5
```

Note: a heap is **partially ordered**, not sorted. `array[1] = 3` and `array[2] = 6` are both valid children of `1`; there is no left-to-right ordering guarantee. The only invariant is the parent/child relationship.

### The two core operations

Everything reduces to restoring the heap property after a single violation:

- **Sift-up (bubble-up / percolate-up):** used by `insert`. Append the new element at the end, then swap it upward while it is smaller than its parent (min-heap). At most `O(log n)` swaps — the tree height.
- **Sift-down (bubble-down / heapify):** used by `extractMin`. Move the last element to the root (overwriting the extracted min), then swap it downward with its **smaller** child until order is restored. Again `O(log n)`.

```
sift-up insert(2):              sift-down after extracting 1:
append 2 → [1,3,6,5,9,8,2]      move last (8) to root → [8,3,6,5,9]
2 < parent 6 → swap             8 > min child 3 → swap → [3,8,6,5,9]
2 < parent 1? no → stop         8 > min child 5 → swap → [3,5,6,8,9]
```

### build-heap is O(n), not O(n log n)

Naively inserting `n` elements one-by-one costs `O(n log n)`. But if you already have the array, **Floyd's build-heap** sift-downs each node starting from the last internal node `(n/2 - 1)` down to index `0`. The math: nodes near the bottom (the majority) have tiny sift-down distance, so the sum `Σ (nodes at height h) · h` converges to **O(n)**. This is a classic interview "gotcha".

### When to use a heap

Use a heap when you need **repeated access to the min/max** while the data set changes, or when you only care about the **top-k** rather than a full ordering:

- Streaming / online data where a full sort is impossible (you can't sort an infinite stream).
- "Top-k", "k-th largest", "k closest", "merge k sorted" problems.
- Dijkstra / Prim / A\* — repeatedly pull the cheapest frontier node.
- Event-driven simulation, OS task scheduling, rate limiting, Huffman coding.

**When NOT to:** if you need the whole thing sorted once → just sort (`O(n log n)`, better constants, cache-friendly). If you need order *plus* range queries or ordered iteration → a balanced BST / `TreeMap`. A heap gives you fast min/max but **no efficient search, no ordered traversal, no efficient arbitrary delete** (without an index map).

---

## Complexity Cheat-Sheet

| Operation | Time | Notes |
|---|---|---|
| `peek` (find min/max) | **O(1)** | Root is `array[0]`. |
| `insert` / `offer` | **O(log n)** | Append + sift-up. |
| `extractMin` / `poll` | **O(log n)** | Remove root, move last up, sift-down. |
| `build-heap` (heapify array) | **O(n)** | Floyd's bottom-up sift-down. |
| `decreaseKey` (sift-up at index) | **O(log n)** | Needs an index map to locate the element. |
| `delete` arbitrary element | **O(log n)** with index map / **O(n)** to find first | `PriorityQueue.remove(obj)` in Java is **O(n)** (linear scan). |
| `search` for arbitrary value | **O(n)** | Heaps are not searchable. |
| Heapsort (in place) | **O(n log n)** time, **O(1)** extra space | Build max-heap, then repeatedly extract to the end. |
| Space | **O(n)** | Plain array; no pointer overhead. |

> **k-th largest of n elements:** a size-`k` min-heap gives **O(n log k)** time and **O(k)** space — better than sorting (`O(n log n)`) when `k ≪ n`.

---

## Patterns & Recognition

Reach for a heap when you see these signals in a problem statement:

1. **"k-th largest / k-th smallest / k closest / k most frequent"** → fixed-size heap of size `k`. Keep a *min*-heap for "largest", a *max*-heap for "smallest" (counter-intuitive but correct: you evict the worst of the survivors).
2. **"Top k"** or **"k best"** without needing them sorted internally → size-`k` heap, `O(n log k)`.
3. **"Merge k sorted lists/arrays/streams"** → heap of the `k` current heads.
4. **"Median of a stream" / "running median"** → **two heaps** (max-heap for the lower half, min-heap for the upper half) kept balanced.
5. **"Schedule tasks / minimize time / maximize value, always pick the best available"** → greedy with a heap (often paired with sorting by a secondary key).
6. **"Process events in order of priority / time"** → priority queue (Dijkstra, simulation).
7. **Sliding window min/max/median** → heap (with lazy deletion) or a monotonic deque (deque is `O(n)` for pure min/max; heap shines for median).

**Min-heap vs max-heap mental model:** to keep the *k largest* elements, use a **min-heap of size k** — the smallest survivor sits on top, ready to be kicked out the moment something bigger arrives.

---

## Coding Problems

### Problem 1: Kth Largest Element in an Array

> Given an integer array `nums` and integer `k`, return the k-th largest element (in sorted order, not the k-th distinct). Constraints: `1 ≤ k ≤ nums.length ≤ 10^5`, `-10^4 ≤ nums[i] ≤ 10^4`.

**Approach.** Brute force: sort descending and index `k-1` → `O(n log n)`. Optimal for `k ≪ n`: maintain a **min-heap of size k**. The root is always the smallest of the k largest seen so far, i.e. exactly the k-th largest once all elements are processed. (Quickselect gives average `O(n)` but worst-case `O(n²)`; the heap is the safe interview answer.)

```java
import java.util.PriorityQueue;

class Solution {
    public int findKthLargest(int[] nums, int k) {
        PriorityQueue<Integer> minHeap = new PriorityQueue<>(); // natural order = min-heap
        for (int n : nums) {
            minHeap.offer(n);
            if (minHeap.size() > k) {
                minHeap.poll();           // evict the smallest survivor
            }
        }
        return minHeap.peek();            // smallest of the k largest = k-th largest
    }
}
```

**Dry run** `nums=[3,2,1,5,6,4], k=2`: heap grows `[3]→[2,3]`; add 1 → `[1,2,3]` size>2 poll 1 → `[2,3]`; add 5 → `[2,3,5]` poll 2 → `[3,5]`; add 6 → `[3,5,6]` poll 3 → `[5,6]`; add 4 → `[4,5,6]` poll 4 → `[5,6]`. Root = **5**. ✓

**Time:** `O(n log k)`. **Space:** `O(k)`.

**Follow-ups:** k-th *smallest* (use a max-heap of size k via `Collections.reverseOrder()`); achieve `O(n)` average (Quickselect / `nth_element`); k-th largest in a *stream* (Problem 8 below).

---

### Problem 2: Last Stone Weight

> Each turn smash the two heaviest stones; if equal both vanish, else the difference returns. Return the weight of the last stone (or 0). Constraints: `1 ≤ stones.length ≤ 30`, `1 ≤ stones[i] ≤ 1000`.

**Approach.** We always need the two *largest* → **max-heap**. Poll twice, push the difference if non-zero. Brute force re-sorting each turn is `O(n² log n)`; the heap makes each turn `O(log n)`.

```java
import java.util.PriorityQueue;
import java.util.Collections;

class Solution {
    public int lastStoneWeight(int[] stones) {
        PriorityQueue<Integer> maxHeap = new PriorityQueue<>(Collections.reverseOrder());
        for (int s : stones) maxHeap.offer(s);
        while (maxHeap.size() > 1) {
            int a = maxHeap.poll();      // heaviest
            int b = maxHeap.poll();      // 2nd heaviest
            if (a != b) maxHeap.offer(a - b);
        }
        return maxHeap.isEmpty() ? 0 : maxHeap.peek();
    }
}
```

**Dry run** `[2,7,4,1,8,1]` → max-heap top order 8,7,4,2,1,1. Smash 8,7→1 push → {4,2,1,1,1}. Smash 4,2→2 push → {2,1,1,1}. Smash 2,1→1 push → {1,1,1}. Smash 1,1→0 → {1}. Answer **1**. ✓

**Time:** `O(n log n)`. **Space:** `O(n)`.

**Follow-ups:** return the *sequence* of smashes; what if you may pick *any* two (then it becomes a different greedy/DP problem).

---

### Problem 3: K Closest Points to Origin

> Return the `k` points closest to `(0,0)` by Euclidean distance. Constraints: `1 ≤ k ≤ points.length ≤ 10^4`.

**Approach.** "k closest" → keep a **max-heap of size k** keyed by squared distance (no need for `sqrt` — monotonic). When the heap exceeds `k`, evict the *farthest*; what remains are the k closest. `O(n log k)` beats sorting all distances (`O(n log n)`).

```java
import java.util.PriorityQueue;

class Solution {
    public int[][] kClosest(int[][] points, int k) {
        // max-heap by squared distance; root = farthest among current survivors
        PriorityQueue<int[]> maxHeap =
            new PriorityQueue<>((a, b) -> dist(b) - dist(a));
        for (int[] p : points) {
            maxHeap.offer(p);
            if (maxHeap.size() > k) maxHeap.poll();   // drop the farthest
        }
        int[][] res = new int[k][2];
        for (int i = 0; i < k; i++) res[i] = maxHeap.poll();
        return res;
    }
    private int dist(int[] p) { return p[0] * p[0] + p[1] * p[1]; }
}
```

**Time:** `O(n log k)`. **Space:** `O(k)`.

**Follow-ups:** Quickselect for `O(n)` average; k-farthest points (flip the comparator to a min-heap); Manhattan distance instead of Euclidean.

---

### Problem 4: Top K Frequent Elements

> Return the `k` most frequent elements. Constraints: answer is unique; `1 ≤ k ≤ #distinct ≤ n ≤ 10^5`.

**Approach.** Count frequencies in a `HashMap`, then keep a **min-heap of size k** keyed by frequency. Min-heap so the *least frequent* survivor is on top for eviction. `O(n log k)`. (Bucket sort gives `O(n)` — a strong follow-up.)

```java
import java.util.*;

class Solution {
    public int[] topKFrequent(int[] nums, int k) {
        Map<Integer, Integer> freq = new HashMap<>();
        for (int n : nums) freq.merge(n, 1, Integer::sum);

        // min-heap by frequency: smallest frequency on top
        PriorityQueue<Integer> heap =
            new PriorityQueue<>((a, b) -> freq.get(a) - freq.get(b));
        for (int key : freq.keySet()) {
            heap.offer(key);
            if (heap.size() > k) heap.poll();   // evict least frequent survivor
        }
        int[] res = new int[k];
        for (int i = 0; i < k; i++) res[i] = heap.poll();
        return res;
    }
}
```

**Dry run** `nums=[1,1,1,2,2,3], k=2`: freq `{1:3, 2:2, 3:1}`. Heap (size 2 cap): offer 1, offer 2 → `{2,1}`; offer 3 (freq 1) size>2 → poll least freq (3) → `{2,1}`. Result `[2,1]`. ✓

**Time:** `O(n + m log k)` where `m` = #distinct. **Space:** `O(m)`.

**Follow-ups:** `O(n)` via **bucket sort** (index = frequency, value = list of keys, scan buckets from high to low); top-k frequent *words* with lexicographic tie-break (custom comparator); top-k over a stream.

---

### Problem 5: Merge k Sorted Lists

> Merge `k` sorted linked lists into one sorted list. Constraints: `0 ≤ k ≤ 10^4`, total nodes ≤ `10^4`.

**Approach.** Brute force: collect all values, sort, rebuild → `O(N log N)`. Optimal: a **min-heap of the current head node of each list** (heap size ≤ k). Poll the smallest, append it, push its `next`. `O(N log k)`.

```java
import java.util.PriorityQueue;

class ListNode { int val; ListNode next; ListNode(int v){val=v;} }

class Solution {
    public ListNode mergeKLists(ListNode[] lists) {
        PriorityQueue<ListNode> heap =
            new PriorityQueue<>((a, b) -> a.val - b.val);
        for (ListNode l : lists) if (l != null) heap.offer(l);

        ListNode dummy = new ListNode(0), tail = dummy;
        while (!heap.isEmpty()) {
            ListNode node = heap.poll();
            tail.next = node;
            tail = node;
            if (node.next != null) heap.offer(node.next);
        }
        return dummy.next;
    }
}
```

**Dry run** lists `[1→4→5], [1→3→4], [2→6]`: heap heads {1,1,2}. Poll 1(list0)→push 4; poll 1(list1)→push 3; poll 2→push 6; poll 3→push 4; poll 4(list1)→none; poll 4(list0)→push5; poll5; poll6. Output `1→1→2→3→4→4→5→6`. ✓

**Time:** `O(N log k)`. **Space:** `O(k)` for the heap.

**Follow-ups:** divide-and-conquer pairwise merge (same `O(N log k)`, often faster constants, no heap); merge k sorted *arrays* / *iterators*; external merge sort for data that doesn't fit in memory.

---

### Problem 6: Task Scheduler

> CPU tasks `A–Z`; identical tasks must be `n` units apart (cooldown). Return the minimum number of CPU intervals (busy or idle) to finish all tasks. Constraints: `1 ≤ tasks.length ≤ 10^4`, `0 ≤ n ≤ 100`.

**Approach.** Greedy: in each round, run the **most frequent remaining** tasks first to spread them out. A **max-heap of counts** + a cooldown queue simulates this. There is also a famous `O(n)` math formula, but the heap simulation is the intuitive interview answer.

```java
import java.util.*;

class Solution {
    public int leastInterval(char[] tasks, int n) {
        int[] freq = new int[26];
        for (char c : tasks) freq[c - 'A']++;

        PriorityQueue<Integer> maxHeap = new PriorityQueue<>(Collections.reverseOrder());
        for (int f : freq) if (f > 0) maxHeap.offer(f);

        // queue of [remainingCount, timeWhenAvailableAgain]
        Deque<int[]> cooldown = new ArrayDeque<>();
        int time = 0;
        while (!maxHeap.isEmpty() || !cooldown.isEmpty()) {
            time++;
            if (!maxHeap.isEmpty()) {
                int remaining = maxHeap.poll() - 1;          // run one instance
                if (remaining > 0) cooldown.offer(new int[]{remaining, time + n});
            }
            // any task whose cooldown elapsed re-enters the heap
            if (!cooldown.isEmpty() && cooldown.peek()[1] == time) {
                maxHeap.offer(cooldown.poll()[0]);
            }
        }
        return time;
    }
}
```

**Dry run** `tasks=[A,A,A,B,B,B], n=2`: heap {3,3}. t1 run A(→2, avail t4); t2 run B(→2, avail t5); t3 idle (heap empty, A not ready); t4 A ready, run A(→1, avail t7); t5 B ready & A queued... sequence yields `A B _ A B _ A B` = **8** intervals. ✓

**Time:** `O(T)` where `T` = total intervals (≤ `tasks.length + idles`); heap ops are `O(log 26)=O(1)`. **Space:** `O(26)=O(1)`.

**Follow-ups:** derive the closed-form `max((maxFreq-1)*(n+1)+numMax, tasks.length)`; return the actual schedule string; tasks with *different* cooldowns; "Reorganize String" (same most-frequent-first pattern).

---

### Problem 7: Find Median from Data Stream (Two Heaps)

> Design a structure that supports `addNum(int)` and `findMedian()` over a growing stream. Constraints: up to `5·10^4` calls; `findMedian` must be `O(1)`.

**Approach.** Keep two heaps splitting the data at the median:
- `lo` = **max-heap** of the smaller half (top = largest of the small half),
- `hi` = **min-heap** of the larger half (top = smallest of the large half).

Invariants: `lo.size == hi.size` or `lo.size == hi.size + 1`, and `lo.top ≤ hi.top`. The median is `lo.top` (odd count) or the average of the two tops (even). Each insert is `O(log n)`; median is `O(1)`.

```java
import java.util.*;

class MedianFinder {
    private final PriorityQueue<Integer> lo = new PriorityQueue<>(Collections.reverseOrder()); // max-heap
    private final PriorityQueue<Integer> hi = new PriorityQueue<>();                            // min-heap

    public void addNum(int num) {
        lo.offer(num);                 // 1) always push to lo
        hi.offer(lo.poll());           // 2) move lo's max to hi (keeps lo.top <= hi.top)
        if (hi.size() > lo.size()) {   // 3) rebalance so lo >= hi
            lo.offer(hi.poll());
        }
    }

    public double findMedian() {
        if (lo.size() > hi.size()) return lo.peek();
        return (lo.peek() + hi.peek()) / 2.0;
    }
}
```

**Dry run** add 1: lo→hi shuffle leaves lo=[1], hi=[]; median 1. add 2: lo=[1], hi=[2]; median (1+2)/2=1.5. add 3: lo=[2,1], hi=[3]; median 2. ✓

**Time:** `addNum` `O(log n)`, `findMedian` `O(1)`. **Space:** `O(n)`.

**Follow-ups:** **(a)** values bounded in `[0,100]` → use a count array, `O(1)` insert; **(b)** 99% of values in `[0,100]` → buckets for the common range + heaps for outliers; **(c)** *sliding window* median (next problem); **(d)** removing arbitrary numbers → lazy deletion with a "to-delete" map.

---

### Problem 8: Kth Largest in a Stream

> Class initialized with `k` and an initial array; `add(val)` returns the k-th largest element seen so far. Constraints: many `add` calls.

**Approach.** Maintain a **min-heap capped at size k**. Its root is the running k-th largest. Each `add` is `O(log k)`. This is the streaming version of Problem 1 and a frequent "design a class" warm-up.

```java
import java.util.PriorityQueue;

class KthLargest {
    private final PriorityQueue<Integer> minHeap = new PriorityQueue<>();
    private final int k;

    public KthLargest(int k, int[] nums) {
        this.k = k;
        for (int n : nums) add(n);
    }

    public int add(int val) {
        minHeap.offer(val);
        if (minHeap.size() > k) minHeap.poll();
        return minHeap.peek();   // k-th largest so far
    }
}
```

**Time:** `O(log k)` per `add`, `O(m log k)` to build from `m` initial elements. **Space:** `O(k)`.

**Follow-ups:** k-th *smallest* in a stream (max-heap); top-k of a *distributed* stream (per-shard heaps merged); approximate top-k at scale (Count-Min Sketch + heap, the "heavy hitters" pattern).

---

### Problem 9 (Hard / Senior): Sliding Window Median

> Given `nums` and window size `k`, return the median of every contiguous window of size `k`. Constraints: `1 ≤ k ≤ nums.length ≤ 10^5`; values can be near `Integer.MIN/MAX`.

**Approach.** This is "median of a stream" plus a **removal** requirement as the window slides. Naively, `PriorityQueue.remove(obj)` is `O(k)`, giving `O(nk)`. The senior trick is **lazy deletion**: keep the two-heap structure, but when an element leaves the window mark it in a `HashMap` and only physically pop it when it surfaces at a heap top. We track *balance* (logical sizes) explicitly so rebalancing stays correct despite stale entries lurking inside the heaps.

> Use `long` for the median average to avoid `Integer` overflow when two near-`MAX` values are added.

```java
import java.util.*;

class Solution {
    public double[] medianSlidingWindow(int[] nums, int k) {
        // max-heap (small half) and min-heap (large half)
        PriorityQueue<Integer> lo = new PriorityQueue<>(Collections.reverseOrder());
        PriorityQueue<Integer> hi = new PriorityQueue<>();
        Map<Integer, Integer> delayed = new HashMap<>(); // value -> times to skip
        int balance = 0;                                  // effective lo.size - hi.size
        double[] res = new double[nums.length - k + 1];

        for (int i = 0; i < nums.length; i++) {
            // --- add nums[i] ---
            if (lo.isEmpty() || nums[i] <= lo.peek()) { lo.offer(nums[i]); balance++; }
            else { hi.offer(nums[i]); balance--; }

            // --- remove the element leaving the window ---
            if (i >= k) {
                int out = nums[i - k];
                delayed.merge(out, 1, Integer::sum);
                if (out <= lo.peek()) balance--; else balance++;
            }

            // --- rebalance logical sizes to {+1 or 0} ---
            if (balance < 0)      { lo.offer(hi.poll()); balance += 2; }
            else if (balance > 1) { hi.offer(lo.poll()); balance -= 2; }

            // --- purge stale tops ---
            prune(lo, delayed);
            prune(hi, delayed);

            // --- record median once the first full window is formed ---
            if (i >= k - 1) {
                res[i - k + 1] = (k % 2 == 1)
                    ? (double) lo.peek()
                    : ((long) lo.peek() + hi.peek()) / 2.0; // long avoids overflow
            }
        }
        return res;
    }

    private void prune(PriorityQueue<Integer> heap, Map<Integer, Integer> delayed) {
        while (!heap.isEmpty()) {
            int top = heap.peek();
            Integer cnt = delayed.get(top);
            if (cnt == null) break;
            if (cnt == 1) delayed.remove(top); else delayed.put(top, cnt - 1);
            heap.poll();
        }
    }
}
```

**Why it is correct.** `balance` mirrors the *logical* size difference (additions and the to-be-removed element both adjust it immediately), so rebalancing decisions ignore stale entries. Stale entries only matter when they reach a top — `prune` removes them lazily right before we read a median, so every `peek` returns a live value.

**Dry run** `nums=[1,3,-1,-3,5,3,6,7], k=3`: windows give medians `1, -1, -1, 3, 5, 6` — matching the expected output. ✓

**Time:** `O(n log n)` (amortized; each element is inserted and lazily removed once). **Space:** `O(n)`.

**Follow-ups:** sliding window *max* — prefer a **monotonic deque** (`O(n)`); supporting duplicate values (the count map already handles this); an order-statistics tree / indexed balanced BST as an alternative for `O(log n)` exact deletes.

---

## Interview Q&A by Level

### 🟢 Basic

- **Q: What is the difference between a heap and a binary search tree?** A heap is only *partially* ordered (parent vs child), giving `O(1)` min/max but no efficient search or ordered traversal. A BST is *fully* ordered left-to-right, giving `O(log n)` search and sorted iteration but `O(log n)` (not `O(1)`) min/max.
- **Q: Is a heap always a binary tree?** No — `d`-ary heaps and Fibonacci heaps exist. But the *binary* heap is the default because of its simple array layout.
- **Q: Is the heap array sorted?** No. Only the parent-child order holds. `[1,3,6,5,9,8]` is a valid min-heap but not a sorted array.
- **Q: How do you make a max-heap in Java?** `new PriorityQueue<>(Collections.reverseOrder())` or a custom comparator `(a,b) -> b - a` (prefer `Integer.compare(b,a)` to avoid overflow).

### 🟡 Intermediate

- **Q: Why is build-heap O(n) and not O(n log n)?** Because most nodes are near the bottom with short sift-down distances. Summing `Σ nodes(h)·h` over heights converges: `n · Σ h/2^h = n · 2 = O(n)`.
- **Q: For "k largest", do you use a min-heap or a max-heap?** A **min-heap of size k**. The smallest of the k survivors sits on top and is evicted the instant a larger element arrives — leaving the k largest. (Use a size-`k` max-heap for the k *smallest*.)
- **Q: Heap vs sorting for top-k?** Heap is `O(n log k)` vs sort `O(n log n)`. When `k ≪ n` the heap wins; it also works on streams where sorting is impossible. If you need *all* elements ordered, just sort.
- **Q: What's the cost of `PriorityQueue.remove(Object)` in Java?** `O(n)` — it does a linear scan to find the element, then `O(log n)` to re-heapify. For frequent arbitrary deletes use lazy deletion or an index map.

### 🟠 Advanced

- **Q: Explain the two-heaps median pattern and its invariants.** Max-heap for the lower half, min-heap for the upper half; keep `|sizes|` differing by ≤ 1 and `lo.top ≤ hi.top`. Median is the top of the bigger heap, or the average of both tops. `O(log n)` insert, `O(1)` query.
- **Q: How do you delete an arbitrary element from a heap efficiently?** Maintain a `value → index` map; to delete, swap the target with the last element, remove it, then sift-up *and* sift-down the moved element. `O(log n)`. This is also how `decreaseKey` is implemented for Dijkstra/Prim.
- **Q: Heapsort — stable? In place? Why isn't it the default sort?** In place (`O(1)` extra) and `O(n log n)` worst case, but **not stable** and has **poor cache locality** (it jumps across the array), so quicksort/mergesort usually beat it in practice. Introsort uses heapsort as a fallback to guarantee `O(n log n)`.
- **Q: When does a d-ary heap help?** Increasing arity reduces height (`log_d n`), speeding up `decreaseKey`-heavy workloads (e.g., 4-ary heaps in dense Dijkstra) at the cost of more comparisons per sift-down.

### 🔴 Expert

- **Q: Amortized complexity of Fibonacci heaps and why it matters.** Fibonacci heaps give `O(1)` amortized `insert` and `decreaseKey`, and `O(log n)` amortized `extractMin`. This improves Dijkstra to `O(E + V log V)`. In practice the large constants and poor cache behavior mean binary or pairing heaps usually win; Fibonacci heaps are mostly of theoretical importance.
- **Q: How would you find approximate top-k over a massive distributed stream (heavy hitters)?** Combine a **Count-Min Sketch** (sublinear-space approximate frequency counter) with a **size-k min-heap** of candidate heavy hitters; per shard, then merge. Trades exactness for bounded memory.
- **Q: How does a priority queue power Dijkstra and how do stale entries arise?** You pop the cheapest frontier node repeatedly. Without `decreaseKey`, you push duplicate (distance, node) pairs and skip a popped node if its recorded distance is stale — the "lazy deletion" trick, the same idea used in Problem 9.
- **Q: Design a timer/scheduler service.** A min-heap keyed by expiry timestamp; the dispatcher sleeps until `heap.peek()` fires, pops due timers, and reschedules recurring ones. This is the core of timer wheels' simpler cousin and of OS/event-loop scheduling (combined with hierarchical timer wheels for `O(1)` insert at scale).

---

## ⚠️ Common Pitfalls

- **Comparator overflow.** `(a, b) -> a - b` overflows for values near `Integer.MIN/MAX`. Use `Integer.compare(a, b)` (or `Long.compare`). Same for median averaging — cast to `long`/`double` *before* adding.
- **Min vs max confusion for top-k.** For the *k largest* you need a *min*-heap (evict the smallest). Getting this backwards is the most common bug in these problems.
- **Forgetting the size cap.** Letting the heap grow to `n` instead of capping at `k` silently degrades you from `O(n log k)` to `O(n log n)` and `O(n)` space.
- **Assuming heap iteration is sorted.** `PriorityQueue`'s iterator and `toString()` return *heap* order, not sorted order. To get sorted output you must `poll()` repeatedly.
- **`O(n)` `remove(Object)`.** Repeatedly calling `pq.remove(x)` in a loop is a hidden `O(n²)`. Use lazy deletion.
- **Mutating elements after insertion.** Changing a field used by the comparator after the object is in the heap corrupts the heap order — re-insert or use `decreaseKey` instead.
- **Off-by-one in array indices.** `leftChild = 2*i+1`, `rightChild = 2*i+2`, `parent = (i-1)/2` for **0-indexed** heaps. The 1-indexed formulas (`2i`, `2i+1`, `i/2`) are different — don't mix them.
- **Empty-heap access.** `peek()`/`poll()` return `null` on an empty `PriorityQueue` (they don't throw); `element()`/`remove()` throw. Guard with `isEmpty()`.

## 📚 Further Reading

- CLRS, *Introduction to Algorithms*, ch. 6 (Heapsort) and 19 (Fibonacci Heaps).
- Sedgewick & Wayne, *Algorithms* (4th ed.), §2.4 Priority Queues.
- Java API: `java.util.PriorityQueue` (binary heap), `java.util.PriorityBlockingQueue` (thread-safe).
- LeetCode tag **Heap (Priority Queue)** — problems 215, 347, 23, 295, 621, 480, 703, 973, 1046.
- Cormen's build-heap analysis and the amortized analysis of Fibonacci heaps (potential method).

[← Back to master index](../README.md) &nbsp;|&nbsp; [← DSA index](README.md)
