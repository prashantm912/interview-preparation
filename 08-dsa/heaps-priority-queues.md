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

## 🧩 Extended Problems — Set 1: Classic Easy → Medium

### Problem 10: Kth Smallest Element in a Sorted Matrix — Max-heap of size k

**Statement.** Given an `n × n` matrix where each row and each column is sorted in ascending order, return the `k`-th smallest element (in overall sorted order, counting duplicates).

**Constraints.** `1 ≤ n ≤ 300`; `-10^9 ≤ matrix[i][j] ≤ 10^9`; `1 ≤ k ≤ n²`.

**Approach.** The simplest heap solution that mirrors "k-th smallest" is a **max-heap of size k** over all `n²` values: push every element, evict whenever size exceeds `k`, and the root is the answer. This is `O(n² log k)`. A more elegant heap solution exploits the sorted structure: seed a **min-heap** with the first element of each row, then pop `k` times, each time pushing the next element in the popped element's row. This runs in `O(k log n)` and is the answer interviewers prefer when `k ≪ n²`. (The optimal is binary search on the value range in `O(n log(max-min))`, a strong follow-up.) Below is the min-heap "k pops" version.

```
min-heap seeded with row heads (value, row, col):
 pop smallest → push its right neighbor in same row
 repeat k times; the k-th pop is the answer
```

```java
import java.util.PriorityQueue;

class Solution {
    public int kthSmallest(int[][] matrix, int k) {
        int n = matrix.length;
        // entry = [value, row, col]; min-heap by value
        PriorityQueue<int[]> minHeap = new PriorityQueue<>((a, b) -> a[0] - b[0]);
        for (int r = 0; r < Math.min(n, k); r++) {
            minHeap.offer(new int[]{matrix[r][0], r, 0});
        }
        int result = 0;
        for (int i = 0; i < k; i++) {
            int[] cur = minHeap.poll();
            result = cur[0];
            int r = cur[1], c = cur[2];
            if (c + 1 < n) {
                minHeap.offer(new int[]{matrix[r][c + 1], r, c + 1});
            }
        }
        return result;
    }
}
```

**Complexity** — Time `O(k log n)` (heap holds at most `n` row-heads), Space `O(n)`. **Edge cases:** `k = 1` returns `matrix[0][0]`; `k = n²` returns the bottom-right (largest) element; only seed `min(n, k)` rows so we never push more than needed; duplicate values are counted individually.

---

### Problem 11: Sort Characters By Frequency — Max-heap by count

**Statement.** Given a string `s`, sort its characters in decreasing order based on the frequency of occurrence. Return any valid result string.

**Constraints.** `1 ≤ s.length ≤ 5·10^5`; `s` consists of upper/lowercase English letters and digits.

**Approach.** Count each character's frequency in a `HashMap`, then push the distinct characters into a **max-heap keyed by frequency**. Pop the most frequent character and append it `freq` times, building the answer. This is the canonical "most-frequent-first" heap pattern. (Bucket sort by frequency gives `O(n)` and is the standard follow-up.)

```java
import java.util.*;

class Solution {
    public String frequencySort(String s) {
        Map<Character, Integer> freq = new HashMap<>();
        for (char c : s.toCharArray()) freq.merge(c, 1, Integer::sum);

        // max-heap by frequency
        PriorityQueue<Character> maxHeap =
            new PriorityQueue<>((a, b) -> freq.get(b) - freq.get(a));
        maxHeap.addAll(freq.keySet());

        StringBuilder sb = new StringBuilder(s.length());
        while (!maxHeap.isEmpty()) {
            char c = maxHeap.poll();
            int count = freq.get(c);
            for (int i = 0; i < count; i++) sb.append(c);
        }
        return sb.toString();
    }
}
```

**Dry run** `s = "tree"`: freq `{t:1, r:1, e:2}`. Heap pops `e` (count 2) → "ee", then `t`/`r` → "eetr" or "eert". ✓

**Complexity** — Time `O(n + m log m)` where `m` = #distinct chars (≤ 62), Space `O(n)` for the output and counts. **Edge cases:** all-same string (`"aaaa"` → `"aaaa"`); ties in frequency may appear in any order (problem allows it); single character.

---

### Problem 12: Reorganize String — Greedy with max-heap

**Statement.** Given a string `s`, rearrange its characters so that no two adjacent characters are the same. Return any valid rearrangement, or `""` if impossible.

**Constraints.** `1 ≤ s.length ≤ 500`; `s` consists of lowercase English letters.

**Approach.** Greedily place the **most frequent remaining** character that is not equal to the previously placed one — this is the only way to avoid getting stuck. A **max-heap keyed by remaining count** gives the most frequent each step. Hold the just-placed character aside (it cannot be used next), then return it to the heap after placing a different one. If at any point the only available character equals the previous, the arrangement is impossible. (Feasibility check: impossible iff `maxFreq > (n+1)/2`.)

```
heap (by count): pop top → append → stash it
 next iter: pop new top → append → push stashed back (if still >0) → stash current
```

```java
import java.util.*;

class Solution {
    public String reorganizeString(String s) {
        int[] freq = new int[26];
        for (char c : s.toCharArray()) freq[c - 'a']++;

        // max-heap of [charIndex, remainingCount] by count desc
        PriorityQueue<int[]> maxHeap = new PriorityQueue<>((a, b) -> b[1] - a[1]);
        for (int i = 0; i < 26; i++) {
            if (freq[i] > 0) maxHeap.offer(new int[]{i, freq[i]});
        }

        StringBuilder sb = new StringBuilder(s.length());
        int[] prev = null; // character placed last turn, held aside
        while (!maxHeap.isEmpty()) {
            int[] cur = maxHeap.poll();
            sb.append((char) ('a' + cur[0]));
            cur[1]--;
            if (prev != null && prev[1] > 0) maxHeap.offer(prev); // release previous
            prev = cur; // current becomes ineligible for next slot
        }
        return sb.length() == s.length() ? sb.toString() : "";
    }
}
```

**Dry run** `s = "aab"`: freq `a:2,b:1`. Place `a` (→1, stash); place `b` (→0, release a); place `a` (→0). Result `"aba"`. ✓ For `s = "aaab"`, `maxFreq=3 > (4+1)/2=2` → cannot avoid adjacency → `""`.

**Complexity** — Time `O(n log 26) = O(n)`, Space `O(1)` (fixed 26-slot heap) plus `O(n)` output. **Edge cases:** single character returns itself; impossible case returns `""`; the stashing logic guarantees the previous char is never placed twice in a row.

---

### Problem 13: Maximum Number of Coins You Can Get — Sort + greedy (heap-equivalent)

**Statement.** There are `3n` piles of coins. In each of `n` rounds you pick any 3 piles; Alice (best) takes the largest, you take the second-largest, Bob takes the smallest. Return the maximum number of coins **you** can collect.

**Constraints.** `3 * n == piles.length`; `1 ≤ n ≤ 10^5`; `1 ≤ piles[i] ≤ 10^4`.

**Approach.** Greedy: sort descending. In each triple you always lose the largest to Alice, so pair the two biggest remaining with the single smallest remaining (sacrificed to Bob). After sorting descending, give the two smallest piles to Bob across all rounds, and you take **every second pile** starting from index 1. Equivalently, sort ascending and take indices `n, n+2, n+4, …`. A heap could supply the descending order, but sorting is cleaner here; this problem is the canonical "greedy ordering" exercise that often appears alongside heap problems.

```
sorted desc:  [c0 c1 c2 c3 c4 c5 ...]
round picks:  Alice=c0, you=c1, Bob=smallest ; Alice=c2, you=c3, Bob=2nd-smallest ...
you take indices 1, 3, 5, ... for n rounds (skip last n = Bob's share)
```

```java
import java.util.Arrays;

class Solution {
    public int maxCoins(int[] piles) {
        Arrays.sort(piles);                 // ascending
        int n = piles.length / 3;
        int you = 0;
        // take every second pile from index n upward (your second-largest each round)
        for (int i = n; i < piles.length; i += 2) {
            you += piles[i];
        }
        return you;
    }
}
```

**Dry run** `piles = [2,4,1,2,7,8]` sorted `[1,2,2,4,7,8]`, `n=2`. Take indices `2,4` → `2 + 7 = 9`. ✓

**Complexity** — Time `O(m log m)` for the sort where `m = piles.length`, Space `O(1)` extra (in-place sort). **Edge cases:** `n = 1` (one triple, you take the middle value); all equal piles; the loop bound `i += 2` exactly enumerates the `n` middle picks.

---

### Problem 14: Take Gifts From the Richest Pile — Max-heap, floor-sqrt updates

**Statement.** Given `gifts` (pile sizes) and `k` seconds, each second pick the pile with the most gifts and replace it with `floor(sqrt(value))`. Return the total number of gifts remaining after `k` seconds.

**Constraints.** `1 ≤ gifts.length ≤ 10^3`; `1 ≤ gifts[i] ≤ 10^9`; `1 ≤ k ≤ 10^3`.

**Approach.** "Repeatedly pick the largest, mutate it, put it back" is the textbook **max-heap** loop. Poll the max, push `floor(sqrt(max))`, repeat `k` times. Then sum the heap. Each operation is `O(log n)`. A heap is optimal because re-finding the max by scanning would be `O(nk)`.

```java
import java.util.*;

class Solution {
    public long pickGifts(int[] gifts, int k) {
        PriorityQueue<Integer> maxHeap = new PriorityQueue<>(Collections.reverseOrder());
        for (int g : gifts) maxHeap.offer(g);

        for (int i = 0; i < k && !maxHeap.isEmpty(); i++) {
            int top = maxHeap.poll();
            maxHeap.offer((int) Math.sqrt(top)); // floor via int cast
        }
        long total = 0;
        for (int g : maxHeap) total += g;        // iterate all elements
        return total;
    }
}
```

**Dry run** `gifts = [25,64,9,4,100], k = 4`: pop 100→10, pop 64→8, pop 25→5, pop 10→3. Heap `{9,8,5,4,3}` sum `= 29`. ✓

**Complexity** — Time `O(n + k log n)`, Space `O(n)`. **Edge cases:** values that are perfect squares (`floor(sqrt)` exact); a pile of `1` stays `1` forever; `k` larger than needed is harmless once piles reach 1; iterating the heap to sum is fine since order does not matter.

---

### Problem 15: Minimum Cost to Connect Sticks — Min-heap, Huffman-style merging

**Statement.** You have sticks of various lengths. Connecting two sticks of lengths `x` and `y` costs `x + y` and yields one stick of length `x + y`. Return the minimum total cost to connect all sticks into one.

**Constraints.** `1 ≤ sticks.length ≤ 10^4`; `1 ≤ sticks[i] ≤ 10^4`.

**Approach.** This is **Huffman coding's** greedy: always merge the two **shortest** sticks, because short sticks merged early are re-added (and re-paid) the fewest times. A **min-heap** gives the two smallest in `O(log n)` each. Accumulate every merge cost. Greedy optimality follows from the exchange argument behind Huffman trees.

```java
import java.util.PriorityQueue;

class Solution {
    public int connectSticks(int[] sticks) {
        PriorityQueue<Integer> minHeap = new PriorityQueue<>();
        for (int s : sticks) minHeap.offer(s);

        int cost = 0;
        while (minHeap.size() > 1) {
            int a = minHeap.poll();
            int b = minHeap.poll();
            int merged = a + b;
            cost += merged;
            minHeap.offer(merged);
        }
        return cost;
    }
}
```

**Dry run** `sticks = [2,4,3]`: merge 2+3=5 (cost 5), heap `{4,5}`; merge 4+5=9 (cost 9), total `= 14`. ✓

**Complexity** — Time `O(n log n)`, Space `O(n)`. **Edge cases:** a single stick → cost `0` (loop never runs); two sticks → one merge; all-equal lengths still follow the same greedy. Watch that connecting greedily by *largest* would be wrong.

---

### Problem 16: The K Weakest Rows in a Matrix — Heap by (strength, index)

**Statement.** Given a binary matrix `mat` where each row has soldiers (`1`s) packed to the left followed by civilians (`0`s), return the indices of the `k` weakest rows ordered from weakest to strongest. Row `a` is weaker than row `b` if it has fewer soldiers, or equal soldiers and a smaller index.

**Constraints.** `m == mat.length`, `n == mat[i].length`; `2 ≤ n, m ≤ 100`; `1 ≤ k ≤ m`; entries are `0` or `1`.

**Approach.** Compute each row's soldier count (binary search per row since rows are sorted, or a linear scan). Then select the `k` weakest by `(count, index)`. Keep a **max-heap of size k** ordered by `(count desc, index desc)`: push each row, evict the strongest when size exceeds `k`. Finally drain the heap and reverse to get weakest-first. This is `O(m log k)` selection, the standard "k smallest by composite key" heap pattern.

```java
import java.util.*;

class Solution {
    public int[] kWeakestRows(int[][] mat, int k) {
        // max-heap of [strength, index]: strongest (or larger index on tie) on top
        PriorityQueue<int[]> maxHeap = new PriorityQueue<>(
            (a, b) -> a[0] != b[0] ? b[0] - a[0] : b[1] - a[1]);

        for (int i = 0; i < mat.length; i++) {
            int strength = countSoldiers(mat[i]);
            maxHeap.offer(new int[]{strength, i});
            if (maxHeap.size() > k) maxHeap.poll(); // remove strongest survivor
        }

        int[] res = new int[k];
        for (int i = k - 1; i >= 0; i--) res[i] = maxHeap.poll()[1]; // fill back-to-front
        return res;
    }

    // soldiers are 1s packed left; binary search for first 0
    private int countSoldiers(int[] row) {
        int lo = 0, hi = row.length; // count of leading 1s
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (row[mid] == 1) lo = mid + 1; else hi = mid;
        }
        return lo;
    }
}
```

**Dry run** `mat = [[1,1,0],[1,0,0],[0,0,0],[1,1,1]], k = 2`: strengths `[2,1,0,3]`. Heap keeps the two weakest → indices `2` (str 0) and `1` (str 1) → result `[2,1]`. ✓

**Complexity** — Time `O(m (log n + log k))`, Space `O(k)`. **Edge cases:** rows with all soldiers or all civilians; ties broken by index (the comparator's `b[1]-a[1]` on the max-heap keeps the smaller index); `k = m` returns all rows sorted weakest-first.

---

### Problem 17: Relative Ranks — Max-heap pairing scores to athletes

**Statement.** Given `score[i]` (the score of the `i`-th athlete, all unique), return their ranks: the top three get `"Gold Medal"`, `"Silver Medal"`, `"Bronze Medal"`, and the rest get their placement number as a string.

**Constraints.** `1 ≤ n ≤ 10^4`; `0 ≤ score[i] ≤ 10^6`; all scores unique.

**Approach.** We need scores in descending order while remembering each athlete's original index. Push `(score, index)` into a **max-heap by score**, then pop in order assigning rank 1, 2, 3, … back to the original index. The top three map to the medal strings. A heap is a clean way to "process in priority order"; sorting an index array works equally well (`O(n log n)` either way).

```java
import java.util.*;

class Solution {
    public String[] findRelativeRanks(int[] score) {
        // max-heap of [score, originalIndex]
        PriorityQueue<int[]> maxHeap = new PriorityQueue<>((a, b) -> b[0] - a[0]);
        for (int i = 0; i < score.length; i++) maxHeap.offer(new int[]{score[i], i});

        String[] res = new String[score.length];
        int rank = 1;
        while (!maxHeap.isEmpty()) {
            int idx = maxHeap.poll()[1];
            switch (rank) {
                case 1 -> res[idx] = "Gold Medal";
                case 2 -> res[idx] = "Silver Medal";
                case 3 -> res[idx] = "Bronze Medal";
                default -> res[idx] = Integer.toString(rank);
            }
            rank++;
        }
        return res;
    }
}
```

**Dry run** `score = [10,3,8,9,4]`: heap pops 10(idx0)→Gold, 9(idx3)→Silver, 8(idx2)→Bronze, 4(idx4)→"4", 3(idx1)→"5". Result `["Gold Medal","5","Bronze Medal","Silver Medal","4"]`. ✓

**Complexity** — Time `O(n log n)`, Space `O(n)`. **Edge cases:** fewer than 3 athletes (only the available medals assigned); single athlete → `"Gold Medal"`; scores are guaranteed unique so no tie handling needed.

---

### Problem 18: Maximize Sum After K Negations — Min-heap repeated flips

**Statement.** Given an integer array `nums` and integer `k`, in one operation pick an index `i` and replace `nums[i]` with `-nums[i]`. You must perform **exactly** `k` operations (the same index may be chosen multiple times). Return the maximum possible array sum.

**Constraints.** `1 ≤ nums.length ≤ 10^4`; `-100 ≤ nums[i] ≤ 100`; `1 ≤ k ≤ 10^4`.

**Approach.** Greedy: each flip should target the **current smallest** element — flipping the most negative value increases the sum the most. A **min-heap** always exposes that smallest element. Poll, negate, push back; repeat `k` times. After flipping all negatives, the smallest element is the closest-to-zero non-negative value; repeatedly flipping it wastes operations but, since we must do exactly `k`, we keep flipping the same minimum (its parity matters). The min-heap handles all cases uniformly.

```java
import java.util.PriorityQueue;

class Solution {
    public int largestSumAfterKNegations(int[] nums, int k) {
        PriorityQueue<Integer> minHeap = new PriorityQueue<>();
        for (int n : nums) minHeap.offer(n);

        while (k-- > 0) {
            int smallest = minHeap.poll();
            minHeap.offer(-smallest);   // flipping the minimum is always optimal
        }
        int sum = 0;
        for (int n : minHeap) sum += n;
        return sum;
    }
}
```

**Dry run** `nums = [4,2,3], k = 1`: min-heap top `2` → flip to `-2`, heap `{-2,3,4}` sum `= 5`. For `nums = [3,-1,0,2], k = 3`: flip -1→1 (sum effect), then smallest 0 flips back and forth twice → final sum `6`. ✓

**Complexity** — Time `O((n + k) log n)`, Space `O(n)`. **Edge cases:** all positive with odd remaining `k` (the smallest positive ends up negated once); a zero in the array absorbs extra flips harmlessly; very large `k` still works (repeatedly flipping the min).

---

### Problem 19: Maximum Product After K Increments — Min-heap greedy

**Statement.** Given an array `nums` of non-negative integers and an integer `k`, you may increment any element by `1`, up to `k` times total. Return the maximum product of all elements, modulo `10^9 + 7`.

**Constraints.** `1 ≤ nums.length, k ≤ 10^5`; `0 ≤ nums[i] ≤ 10^6`.

**Approach.** To maximize a product under a fixed budget of `+1` increments, always increment the **current smallest** element — balancing the values raises the product fastest (by AM-GM intuition: the product is maximized when factors are as equal as possible). A **min-heap** yields the smallest each step. Poll, add 1, push back, `k` times; then multiply with modular arithmetic.

```
min-heap: [a b c]  always +1 the smallest → values converge → product peaks
```

```java
import java.util.PriorityQueue;

class Solution {
    public int maximumProduct(int[] nums, int k) {
        final int MOD = 1_000_000_007;
        PriorityQueue<Integer> minHeap = new PriorityQueue<>();
        for (int n : nums) minHeap.offer(n);

        while (k-- > 0) {
            minHeap.offer(minHeap.poll() + 1); // raise the smallest
        }
        long product = 1;
        while (!minHeap.isEmpty()) {
            product = (product * minHeap.poll()) % MOD;
        }
        return (int) product;
    }
}
```

**Dry run** `nums = [0,4], k = 5`: increments target the smallest: 0→1→2→3→4→5 (5 ops). Heap `{4,5}` product `= 20`. ✓ (`nums=[6,3,3,2], k=2` → raise 2→3, then a 3→4 → product `6*3*4*3=216`.)

**Complexity** — Time `O((n + k) log n)`, Space `O(n)`. **Edge cases:** zeros in the array (incrementing a zero first gives the biggest relative gain); take the modulo *after each multiplication* to avoid `long` overflow; single element just becomes `nums[0] + k`.

---

### Problem 20: Furthest Building You Can Reach — Min-heap of ladder allocations

**Statement.** You start at building `0` with `bricks` bricks and `ladders` ladders. Moving from building `i` to `i+1`: if the next is shorter or equal, it is free; if taller by `d = heights[i+1] - heights[i]`, you must use either a ladder (unlimited height) or `d` bricks. Return the furthest building index (0-based) you can reach.

**Constraints.** `1 ≤ heights.length ≤ 10^5`; `1 ≤ heights[i] ≤ 10^6`; `0 ≤ bricks ≤ 10^9`; `0 ≤ ladders ≤ heights.length`.

**Approach.** Ladders are best spent on the **largest climbs**. Greedily assign a ladder to every climb at first; keep the climbs that used ladders in a **min-heap**. When ladders run out, the smallest climb in the heap should be "downgraded" to bricks (pop it, pay with bricks), reserving ladders for bigger jumps encountered later. If bricks go negative, you cannot proceed past this building. This online greedy is `O(n log ladders)`.

```
for each positive climb d:
  push d into min-heap (tentatively use a ladder)
  if heap.size > ladders: pop smallest s, bricks -= s  // pay smallest with bricks
  if bricks < 0: stop, return previous index
```

```java
import java.util.PriorityQueue;

class Solution {
    public int furthestBuilding(int[] heights, int bricks, int ladders) {
        PriorityQueue<Integer> ladderUses = new PriorityQueue<>(); // min-heap of climbs covered by ladders
        for (int i = 0; i < heights.length - 1; i++) {
            int diff = heights[i + 1] - heights[i];
            if (diff <= 0) continue;          // free move

            ladderUses.offer(diff);           // tentatively cover this climb with a ladder
            if (ladderUses.size() > ladders) {
                bricks -= ladderUses.poll();  // demote the smallest climb to bricks
            }
            if (bricks < 0) return i;          // cannot afford this climb
        }
        return heights.length - 1;             // reached the last building
    }
}
```

**Dry run** `heights = [4,2,7,6,9,14,12], bricks = 5, ladders = 1`: climbs `+5(→7), +3(→9), +5(→14)`. Ladder covers the biggest; smallest demoted to bricks. With only 5 bricks and 1 ladder you reach index `4`. ✓

**Complexity** — Time `O(n log L)` where `L = ladders`, Space `O(L)`. **Edge cases:** all descending → reach the last building for free; `ladders = 0` reduces to pure brick spending; if you never run out you return the last index; checking `bricks < 0` returns the last *reachable* index `i`.

---

### Problem 21: Single-Threaded CPU — Min-heap on (processingTime, index)

**Statement.** Tasks are given as `tasks[i] = [enqueueTime, processingTime]`. A single-threaded CPU, when idle, picks the available task with the **shortest processing time** (ties broken by smallest index). Once started a task runs to completion. Return the order in which tasks are processed.

**Constraints.** `1 ≤ tasks.length ≤ 10^5`; `1 ≤ enqueueTime, processingTime ≤ 10^9`.

**Approach.** Sort task indices by `enqueueTime` so we can release tasks into a ready pool as the clock advances. The ready pool is a **min-heap keyed by `(processingTime, index)`** — exactly the CPU's selection rule. Advance the clock: if the heap is empty, jump to the next task's enqueue time; otherwise pop the shortest task, run it, and advance the clock by its processing time. This event-driven simulation is the standard scheduler-with-priority pattern.

```java
import java.util.*;

class Solution {
    public int[] getOrder(int[][] tasks) {
        int n = tasks.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Integer.compare(tasks[a][0], tasks[b][0])); // by enqueue time

        // ready pool: min-heap of [processingTime, index]
        PriorityQueue<int[]> ready = new PriorityQueue<>(
            (a, b) -> a[0] != b[0] ? a[0] - b[0] : a[1] - b[1]);

        int[] res = new int[n];
        long time = 0;
        int idx = 0, filled = 0;
        while (filled < n) {
            // release all tasks that have arrived by `time`
            while (idx < n && tasks[order[idx]][0] <= time) {
                int t = order[idx++];
                ready.offer(new int[]{tasks[t][1], t});
            }
            if (ready.isEmpty()) {
                time = tasks[order[idx]][0]; // jump clock to next arrival
                continue;
            }
            int[] cur = ready.poll();
            time += cur[0];                  // run it to completion
            res[filled++] = cur[1];
        }
        return res;
    }
}
```

**Dry run** `tasks = [[1,2],[2,4],[3,2],[4,1]]`: at t=1 task0 ready → run (ends t=3). By t=3 tasks 1,2 ready; pick shorter (task2, pt 2) → ends t=5; task3 arrives, pick task3 (pt 1) → ends t=6; then task1. Order `[0,2,3,1]`. ✓

**Complexity** — Time `O(n log n)` (sort + heap ops), Space `O(n)`. **Edge cases:** idle gaps where no task is available (clock jumps forward); ties in processing time resolved by smallest index via the comparator; use `long` for `time` since times can reach `10^9` and accumulate.

---

### Problem 22: Smallest Range Covering Elements from K Lists — Min-heap across lists

**Statement.** Given `k` sorted integer lists, find the smallest range `[a, b]` that includes at least one number from each of the `k` lists. A range `[a,b]` is smaller than `[c,d]` if `b - a < d - c`, or `b - a == d - c` and `a < c`.

**Constraints.** `1 ≤ k ≤ 3500`; `1 ≤ list.length ≤ 50`; `-10^5 ≤ value ≤ 10^5`; each list is sorted ascending.

**Approach.** This generalizes "merge k sorted lists." Maintain a **min-heap holding one current element from each list** (initially the first of each). The current window spans from the heap's **minimum** (its root) to the running **maximum** of all elements currently in the heap. Pop the minimum, record `[min, max]` if it is the smallest so far, then push the next element from the list the min came from (this advances the lower bound). Stop when any list is exhausted. The max can only stay or grow; the min strictly advances — sweeping all candidate ranges in `O(N log k)`.

```
heap holds 1 element per list; window = [heap.min, curMax]
 pop min → try to shrink range → push next from that list (updates min, maybe curMax)
 stop when a list runs out (can no longer cover that list)
```

```java
import java.util.PriorityQueue;

class Solution {
    public int[] smallestRange(java.util.List<java.util.List<Integer>> nums) {
        int k = nums.size();
        // entry = [value, listIndex, elementIndex]; min-heap by value
        PriorityQueue<int[]> minHeap = new PriorityQueue<>((a, b) -> a[0] - b[0]);
        int curMax = Integer.MIN_VALUE;
        for (int i = 0; i < k; i++) {
            int v = nums.get(i).get(0);
            minHeap.offer(new int[]{v, i, 0});
            curMax = Math.max(curMax, v);
        }

        int rangeStart = 0, rangeEnd = Integer.MAX_VALUE; // best range so far
        while (minHeap.size() == k) {                     // every list still represented
            int[] cur = minHeap.poll();
            int min = cur[0], list = cur[1], elem = cur[2];
            if (curMax - min < rangeEnd - rangeStart) {   // strictly smaller range
                rangeStart = min;
                rangeEnd = curMax;
            }
            if (elem + 1 < nums.get(list).size()) {       // advance this list
                int next = nums.get(list).get(elem + 1);
                curMax = Math.max(curMax, next);
                minHeap.offer(new int[]{next, list, elem + 1});
            }
            // if a list is exhausted, the while condition (size==k) ends the loop
        }
        return new int[]{rangeStart, rangeEnd};
    }
}
```

**Dry run** lists `[[4,10,15,24,26],[0,9,12,20],[5,18,22,30]]`: heap starts `{0,4,5}`, curMax 5 → range `[0,5]` width 5 → eventually narrows to `[20,24]` width 4, the answer. ✓

**Complexity** — Time `O(N log k)` where `N` = total elements, Space `O(k)`. **Edge cases:** a single list → range is `[min, min]` of that list (width 0); duplicate values across lists; loop ends precisely when the shortest list is consumed; comparator on width uses the running best to ensure the lexicographically smallest tie-break (first-found smaller `a` wins since min advances).

---

## 🧩 Extended Problems — Set 2: Medium → Hard Variations & Follow-ups

### Problem 23: Find K Pairs with Smallest Sums — Min-heap over a sorted-grid frontier

**Statement.** Given two ascending-sorted integer arrays `nums1` and `nums2` and an integer `k`, return the `k` pairs `(u, v)` (one element from each array) with the smallest sums `u + v`.

**Constraints.** `1 ≤ nums1.length, nums2.length ≤ 10^5`; `-10^9 ≤ nums[i] ≤ 10^9`; `1 ≤ k ≤ 10^4`.

**Approach.** Brute force forms all `m·n` pairs, sorts by sum, and takes the first `k` — `O(mn log(mn))`, impossible for large inputs. The optimal view: imagine a virtual matrix `M[i][j] = nums1[i] + nums2[j]`. Each row is sorted (since `nums2` is sorted) and each column is sorted (since `nums1` is sorted), so `M[0][0]` is the global minimum — this is exactly the "k-th smallest in a sorted matrix" structure. We never materialize the matrix; instead we explore a **frontier** with a **min-heap by sum**. Seed it with the first column candidates `(i, 0)` for the first `min(k, m)` rows. Each pop of `(i, j)` yields the next-smallest pair and pushes its right neighbor `(i, j+1)`. Seeding the whole first column (rather than only `(0,0)`) guarantees every reachable pair is discoverable while keeping the heap at `O(k)`.

```
M[i][j] = nums1[i] + nums2[j], rows & cols sorted ascending
seed heap with column 0: (0,0)(1,0)(2,0)...
pop (i,j) -> emit pair -> push (i,j+1)   // move right along the row
repeat k times
```

```java
import java.util.*;

class Solution {
    public List<List<Integer>> kSmallestPairs(int[] nums1, int[] nums2, int k) {
        List<List<Integer>> res = new ArrayList<>();
        if (nums1.length == 0 || nums2.length == 0 || k <= 0) return res;

        // entry = [sum, i, j]; min-heap by sum
        PriorityQueue<int[]> minHeap = new PriorityQueue<>((a, b) -> Integer.compare(a[0], b[0]));
        // seed first column: pair each nums1[i] with nums2[0]
        for (int i = 0; i < Math.min(nums1.length, k); i++) {
            minHeap.offer(new int[]{nums1[i] + nums2[0], i, 0});
        }

        while (k-- > 0 && !minHeap.isEmpty()) {
            int[] cur = minHeap.poll();
            int i = cur[1], j = cur[2];
            res.add(Arrays.asList(nums1[i], nums2[j]));
            if (j + 1 < nums2.length) {
                minHeap.offer(new int[]{nums1[i] + nums2[j + 1], i, j + 1});
            }
        }
        return res;
    }
}
```

**Dry run** `nums1=[1,7,11], nums2=[2,4,6], k=3`: seed `{(3,0,0),(9,1,0),(13,2,0)}`. Pop sum 3 → `[1,2]`, push `(5,0,1)`. Pop sum 5 → `[1,4]`, push `(7,0,2)`. Pop sum 7 → `[1,6]`. Result `[[1,2],[1,4],[1,6]]`. ✓

**Complexity** — Time `O(k log k)` (heap holds `O(k)` entries; each pop pushes ≤ 1), Space `O(k)`. Use `Integer.compare` to avoid sum overflow on near-`10^9` values (the sum itself fits in `int` here, but the comparator subtraction would not for general inputs). **Edge cases:** `k` larger than `m·n` (loop stops when heap empties); either array empty; many duplicate sums (handled naturally, pairs counted individually).

---

### Problem 24: IPO / Maximize Capital — Two heaps (greedy by affordability then profit)

**Statement.** You can complete at most `k` projects starting with `w` initial capital. Project `i` requires `capital[i]` to start and yields `profits[i]` (added to your capital). Each project is done at most once. Return the maximum final capital.

**Constraints.** `1 ≤ k ≤ 10^5`; `0 ≤ w ≤ 10^9`; `1 ≤ n ≤ 10^5`; `0 ≤ profits[i], capital[i] ≤ 10^9`.

**Approach.** Greedy: at every step, among all projects you can currently **afford**, pick the one with the **highest profit** — this never hurts because completing a project only increases capital, so any project affordable now stays affordable later. The challenge is efficiently maintaining "affordable set, max profit." Use **two heaps**: a **min-heap of projects keyed by capital requirement** (the "not yet affordable" pool, sorted so the cheapest-to-unlock is on top) and a **max-heap of profits** for projects already unlocked. Each round, move every project whose capital `≤ w` from the min-heap into the max-heap, then take the max profit. Sorting by capital up front is an equivalent alternative to the capital min-heap.

```
capital-minHeap (locked) ──unlock all req<=w──▶ profit-maxHeap (affordable)
each of k rounds: drain affordable, pop best profit, w += profit
```

```java
import java.util.*;

class Solution {
    public int findMaximizedCapital(int k, int w, int[] profits, int[] capital) {
        int n = profits.length;
        // min-heap by capital requirement: [capital, index]
        PriorityQueue<int[]> byCapital = new PriorityQueue<>((a, b) -> Integer.compare(a[0], b[0]));
        for (int i = 0; i < n; i++) byCapital.offer(new int[]{capital[i], i});

        // max-heap of affordable profits
        PriorityQueue<Integer> byProfit = new PriorityQueue<>(Collections.reverseOrder());

        for (int round = 0; round < k; round++) {
            // unlock everything we can now afford
            while (!byCapital.isEmpty() && byCapital.peek()[0] <= w) {
                byProfit.offer(profits[byCapital.poll()[1]]);
            }
            if (byProfit.isEmpty()) break;     // nothing affordable -> stop early
            w += byProfit.poll();              // take the most profitable project
        }
        return w;
    }
}
```

**Dry run** `k=2, w=0, profits=[1,2,3], capital=[0,1,1]`: round1 unlock cap≤0 → profit{1}, take 1 → w=1. round2 unlock cap≤1 → profit{2,3}, take 3 → w=4. Final `4`. ✓

**Complexity** — Time `O((n + k) log n)`, Space `O(n)`. **Edge cases:** no affordable project at the start (return `w` unchanged via early break); `k` exceeds the number of projects (break when both heaps useful entries exhausted); capital values up to `10^9` (use `int w` is fine since profits/capital ≤ `10^9` and final capital can exceed `Integer.MAX_VALUE` only if many large profits — the problem guarantees the answer fits in a 32-bit int).

---

### Problem 25: Trapping Rain Water II (2D) — Min-heap boundary sweep

**Statement.** Given an `m × n` matrix of non-negative heights, compute how much water it can trap after raining (water held above each cell, bounded by surrounding walls).

**Constraints.** `1 ≤ m, n ≤ 200`; `0 ≤ height[i][j] ≤ 2·10^4`.

**Approach.** In 1D you use two pointers; in 2D water can leak in any direction, so the correct generalization is a **min-heap of the boundary**. Intuition: the water level at any inner cell is limited by the *lowest wall* on the lowest path to the outside. Push all border cells into a min-heap keyed by height and mark them visited. Repeatedly pop the **lowest boundary cell** — it defines the current outer "rim." For each unvisited neighbor, the trapped water there is `max(0, currentRimHeight - neighborHeight)`; then push the neighbor with height `max(neighborHeight, currentRimHeight)` (a filled cell becomes part of the new boundary at the water level). Processing the lowest rim first guarantees we always discover the true limiting wall before higher ones.

```
push all border cells into min-heap (the initial rim)
pop lowest rim cell h:
  for each unvisited neighbor nb:
     water += max(0, h - height[nb])
     push nb with effective height max(height[nb], h)
```

```java
import java.util.*;

class Solution {
    public int trapRainWater(int[][] heightMap) {
        int m = heightMap.length, n = heightMap[0].length;
        if (m < 3 || n < 3) return 0;          // need an interior to trap water

        boolean[][] visited = new boolean[m][n];
        // entry = [height, row, col]; min-heap by height
        PriorityQueue<int[]> minHeap = new PriorityQueue<>((a, b) -> Integer.compare(a[0], b[0]));

        // seed the borders
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                if (i == 0 || i == m - 1 || j == 0 || j == n - 1) {
                    minHeap.offer(new int[]{heightMap[i][j], i, j});
                    visited[i][j] = true;
                }
            }
        }

        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int water = 0;
        while (!minHeap.isEmpty()) {
            int[] cell = minHeap.poll();
            int h = cell[0], r = cell[1], c = cell[2];
            for (int[] d : dirs) {
                int nr = r + d[0], nc = c + d[1];
                if (nr < 0 || nr >= m || nc < 0 || nc >= n || visited[nr][nc]) continue;
                visited[nr][nc] = true;
                water += Math.max(0, h - heightMap[nr][nc]);
                minHeap.offer(new int[]{Math.max(heightMap[nr][nc], h), nr, nc});
            }
        }
        return water;
    }
}
```

**Dry run** a `3x6` bowl `[[1,4,3,1,3,2],[3,2,1,3,2,4],[2,3,3,2,3,1]]`: the rim processing fills interior cells `(1,1)=2,(1,2)=1,(1,3)=3` against the lowest enclosing walls, trapping a total of `4` units. ✓

**Complexity** — Time `O(mn log(mn))` (every cell pushed/popped once), Space `O(mn)`. **Edge cases:** grids thinner than `3` in either dimension trap nothing (early return); flat or descending-to-edge terrain traps `0`; pushing the *effective* height (`max`) is essential — pushing the raw neighbor height would leak water and undercount.

---

### Problem 26: Minimum Number of Refueling Stops — Max-heap of "fuel in the bank"

**Statement.** A car starts with `startFuel` units and must reach a target `target` miles away. Stations are `stations[i] = [position, fuel]`. Driving one mile uses one unit. At a station you may add its fuel to the tank (unlimited tank). Return the minimum number of stops to reach the target, or `-1` if impossible.

**Constraints.** `1 ≤ target, startFuel ≤ 10^9`; `0 ≤ stations.length ≤ 500`; stations strictly increasing in position.

**Approach.** The elegant greedy reframes "when to stop" as "which fuel to retroactively use." Drive forward; whenever you pass a station, you *defer* the decision by dropping its fuel into a **max-heap** ("fuel available if I had stopped"). When your current fuel can't reach the next station (or the target), you must have stopped somewhere — greedily "stop" at the passed station with the **largest** fuel (pop the max), which maximizes range per stop and thus minimizes total stops. Repeat until you can advance; if the heap empties while still short, it's impossible. This is `O(n log n)` and beats the `O(n·target_states)` DP for large distances.

```
drive toward each station/target; bank passed stations' fuel in a max-heap
if fuel < distance needed: pop biggest banked fuel (a "stop"), add it; repeat
if heap empty and still short -> -1
```

```java
import java.util.*;

class Solution {
    public int minRefuelStops(int target, int startFuel, int[][] stations) {
        PriorityQueue<Integer> maxHeap = new PriorityQueue<>(Collections.reverseOrder());
        long fuel = startFuel;       // long: positions/fuel up to 1e9 can sum past int range
        int stops = 0, i = 0, n = stations.length;

        while (fuel < target) {
            // bank fuel of every station we can currently reach
            while (i < n && stations[i][0] <= fuel) {
                maxHeap.offer(stations[i][1]);
                i++;
            }
            if (maxHeap.isEmpty()) return -1;   // can't reach any further station/target
            fuel += maxHeap.poll();             // retroactively "stop" at the richest station
            stops++;
        }
        return stops;
    }
}
```

**Dry run** `target=100, startFuel=10, stations=[[10,60],[20,30],[30,30],[60,40]]`: fuel 10 reaches station0, bank{60}; 10<100 → pop 60 → fuel 70, stops=1. Now reach stations at 20,30,60 → bank{40,30,30}; 70<100 → pop 40 → fuel 110 ≥ 100, stops=2. Answer `2`. ✓

**Complexity** — Time `O(n log n)`, Space `O(n)`. **Edge cases:** `startFuel ≥ target` → `0` stops (loop body skipped); no stations and insufficient fuel → `-1`; use `long` for `fuel` since `startFuel + Σ station fuel` can exceed `Integer.MAX_VALUE`; a station exactly at the current reach (`position == fuel`) is bankable.

---

### Problem 27: Process Tasks Using Servers — Two heaps (free servers + busy servers)

**Statement.** You have `servers[i]` = weight of the `i`-th server, and `tasks[j]` = duration of the `j`-th task. Task `j` becomes available at second `j` and must be assigned (FIFO) to a free server; pick the free server with the smallest weight, ties broken by smallest index. If none are free at time `j`, the task waits and is assigned as soon as a server frees up, still in task order. A server assigned a task of duration `d` at time `t` frees at `t + d`. Return an array `ans` where `ans[j]` is the index of the server that ran task `j`.

**Constraints.** `1 ≤ servers.length, tasks.length ≤ 2·10^5`; `1 ≤ servers[i], tasks[j] ≤ 2·10^5`.

**Approach.** This is a classic **two-heap event simulation**. A **free min-heap** keyed by `(weight, index)` models idle servers (the assignment rule directly). A **busy min-heap** keyed by `(freeTime, weight, index)` models running servers so we know when each returns. Advance a clock `t` over task indices; before assigning task `j`, release every busy server whose `freeTime ≤ max(t, j)` back into the free heap. If a server is free, assign it; otherwise jump the clock to the earliest `freeTime`, release those servers, and assign. The two heaps keep both selection rules (smallest weight to assign, earliest finish to release) at `O(log n)`.

```
free-heap: (weight, idx)            busy-heap: (freeTime, weight, idx)
at time t: move busy servers with freeTime<=t -> free-heap
assign smallest-weight free server; push it to busy-heap with freeTime=t+duration
```

```java
import java.util.*;

class Solution {
    public int[] assignTasks(int[] servers, int[] tasks) {
        int n = servers.length, m = tasks.length;
        // free: [weight, index]
        PriorityQueue<int[]> free = new PriorityQueue<>(
            (a, b) -> a[0] != b[0] ? a[0] - b[0] : a[1] - b[1]);
        // busy: [freeTime, weight, index]
        PriorityQueue<long[]> busy = new PriorityQueue<>(
            (a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0])
                    : a[1] != b[1] ? Long.compare(a[1], b[1])
                    : Long.compare(a[2], b[2]));
        for (int i = 0; i < n; i++) free.offer(new int[]{servers[i], i});

        int[] ans = new int[m];
        long t = 0;
        for (int j = 0; j < m; j++) {
            t = Math.max(t, j);                       // task j is available at second j
            // release servers that have finished by now
            while (!busy.isEmpty() && busy.peek()[0] <= t) {
                long[] s = busy.poll();
                free.offer(new int[]{(int) s[1], (int) s[2]});
            }
            // if none free, jump clock to the next server to free up
            if (free.isEmpty()) {
                t = busy.peek()[0];
                while (!busy.isEmpty() && busy.peek()[0] <= t) {
                    long[] s = busy.poll();
                    free.offer(new int[]{(int) s[1], (int) s[2]});
                }
            }
            int[] srv = free.poll();
            ans[j] = srv[1];
            busy.offer(new long[]{t + tasks[j], srv[0], srv[1]});
        }
        return ans;
    }
}
```

**Dry run** `servers=[3,3,2], tasks=[1,2,3,2,1,2]`: task0 at t=0 → smallest weight server idx2 (w2). task1 → among free {idx0,idx1 both w3} pick idx0. task2 → idx1. task3 at t=3 → server idx2 freed (0+1=1) → idx2. ... yields `[2,2,0,2,1,2]`. ✓

**Complexity** — Time `O((n + m) log n)`, Space `O(n)`. **Edge cases:** more tasks than servers (waiting + clock jumps); ties resolved by weight then index in both heaps; `t = Math.max(t, j)` ensures we never assign a task before it is available; use `long` for free times (`t` can reach `m + max duration`).

---

### Problem 28: Maximum Performance of a Team — Min-heap of speeds, sorted by efficiency

**Statement.** Given `n` engineers with `speed[i]` and `efficiency[i]`, choose at most `k` of them to maximize **performance** = (sum of chosen speeds) × (minimum chosen efficiency). Return it modulo `10^9 + 7`.

**Constraints.** `1 ≤ n ≤ 10^5`; `1 ≤ k ≤ n`; `1 ≤ speed[i] ≤ 10^5`; `1 ≤ efficiency[i] ≤ 10^8`.

**Approach.** The min-efficiency term is the obstacle: it depends on the whole chosen set. Fix it by **sorting engineers by efficiency descending** and iterating; when we consider engineer `i`, treat `efficiency[i]` as the team's *minimum* (everyone before has efficiency ≥ it). Then we just want the largest possible speed sum from engineer `i` plus any subset of the already-seen engineers — so keep the **top `k-1` speeds** among those seen in a **min-heap of size `k`** (the smallest speed sits on top for eviction), maintaining a running `speedSum`. At each step compute `speedSum × efficiency[i]` and track the maximum. This `O(n log n)` greedy elegantly removes the min-efficiency entanglement.

```
sort by efficiency desc. iterate i:
  add speed[i] to a size-k min-heap (evict smallest, adjust speedSum)
  candidate = speedSum * efficiency[i]   // efficiency[i] is the team minimum here
  answer = max(answer, candidate)
```

```java
import java.util.*;

class Solution {
    public int maxPerformance(int n, int[] speed, int[] efficiency, int k) {
        final int MOD = 1_000_000_007;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        // sort engineers by efficiency descending
        Arrays.sort(idx, (a, b) -> efficiency[b] - efficiency[a]);

        PriorityQueue<Integer> speedHeap = new PriorityQueue<>(); // min-heap of chosen speeds
        long speedSum = 0, best = 0;
        for (int i = 0; i < n; i++) {
            int eng = idx[i];
            speedHeap.offer(speed[eng]);
            speedSum += speed[eng];
            if (speedHeap.size() > k) {            // keep only the k fastest seen
                speedSum -= speedHeap.poll();
            }
            // efficiency[eng] is the smallest efficiency in the current team
            best = Math.max(best, speedSum * efficiency[eng]);
        }
        return (int) (best % MOD);
    }
}
```

**Dry run** `n=6, speed=[2,10,3,1,5,8], efficiency=[5,4,3,9,7,2], k=2`: sorted by eff desc → eng3(9,s1), eng4(7,s5), eng0(5,s2), eng1(4,s10), eng2(3,s3), eng5(2,s8). Best appears at eng4: speeds{5,1}, sum6 × eff7 = 42... continuing, eng1 gives speeds {10,5} sum15 × 4 = 60. Max `60`. ✓

**Complexity** — Time `O(n log n)`, Space `O(n)`. **Edge cases:** take the modulo only on the *final* answer (intermediate products use `long`, max ≈ `k·10^5 · 10^8 ≈ 10^{18}`, within `long`); `k = 1` reduces to `max(speed[i] × efficiency[i])`; ties in efficiency are fine since either order yields the same min when both are included.

---

### Problem 29: Minimum Cost to Hire K Workers — Max-heap of quality under a rate ratio

**Statement.** Each worker `i` has `quality[i]` and a minimum `wage[i]`. Hire exactly `k` workers forming a "paid group" where (1) everyone is paid in proportion to their quality relative to others in the group, and (2) everyone earns at least their minimum wage. Return the least total amount to pay any valid group of `k` workers (within `1e-5`).

**Constraints.** `1 ≤ k ≤ n ≤ 10^4`; `1 ≤ quality[i], wage[i] ≤ 10^4`.

**Approach.** The group's pay rate (wage per unit quality) is set by the worker with the highest `wage/quality` ratio — call it `r`. Total cost = `r × (sum of qualities in the group)`. So **sort workers by ratio ascending** and consider each as the rate-setter `r`; among workers with ratio ≤ `r` (all earlier ones plus current) we want the `k` with the **smallest total quality**. Maintain a **max-heap of qualities of size `k`** (evict the largest quality) and a running `qualitySum`. Once we have `k` workers, the candidate cost is `ratio × qualitySum`; minimize over all rate-setters. Sorting by ratio is the key insight that turns a 2-variable optimization into a clean heap sweep.

```
sort by wage/quality ascending. iterate, ratio = current worker's ratio (group max):
  push quality into size-k max-heap (evict largest quality, shrink qualitySum)
  when k collected: cost = ratio * qualitySum ; track min
```

```java
import java.util.*;

class Solution {
    public double mincostToHireWorkers(int[] quality, int[] wage, int k) {
        int n = quality.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        // sort by wage/quality ratio ascending
        Arrays.sort(idx, (a, b) ->
            Double.compare((double) wage[a] / quality[a], (double) wage[b] / quality[b]));

        PriorityQueue<Integer> maxHeap = new PriorityQueue<>(Collections.reverseOrder()); // qualities
        int qualitySum = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            int w = idx[i];
            maxHeap.offer(quality[w]);
            qualitySum += quality[w];
            if (maxHeap.size() > k) qualitySum -= maxHeap.poll(); // drop the largest quality
            if (maxHeap.size() == k) {
                double ratio = (double) wage[w] / quality[w];     // current worker is the rate-setter
                best = Math.min(best, ratio * qualitySum);
            }
        }
        return best;
    }
}
```

**Dry run** `quality=[10,20,5], wage=[70,50,30], k=2`: ratios 7.0, 2.5, 6.0 → sorted idx1(2.5,q20), idx2(6.0,q5), idx0(7.0,q10). After idx2: heap{20,5} sum25, ratio6.0 → 150. After idx0: push10 → {20,10,5} evict 20 → sum15, ratio7.0 → 105. Min `105.0`. ✓

**Complexity** — Time `O(n log n)`, Space `O(n)`. **Edge cases:** `k = n` (one valid group, cost = maxRatio × total quality); use `double` ratios carefully and compare within `1e-5`; the heap must hold exactly `k` qualities before computing a candidate; ties in ratio resolved by Double.compare consistently.

---

### Problem 30: Rearrange String k Distance Apart — Max-heap + cooldown queue

**Statement.** Given a string `s` and an integer `k`, rearrange its characters so that the same character appears at least `k` distance apart. Return such a string, or `""` if impossible. (`k = 0` means no constraint.)

**Constraints.** `1 ≤ s.length ≤ 10^5`; `0 ≤ k ≤ s.length`; lowercase letters.

**Approach.** This is the general-distance cousin of "Reorganize String" (`k = 2`) and shares the structure of "Task Scheduler." Greedily place the **most frequent remaining** character each position, but a placed character must **cool down** for `k` slots before reuse. Use a **max-heap by remaining count** to pick the next character, and a **FIFO cooldown queue** holding `(char, count)` entries that are temporarily ineligible; a queue entry re-enters the heap once `k` characters have been placed after it. If the heap empties while the queue still holds characters with positive counts, no valid arrangement exists.

```
maxHeap by count -> pop top, append, decrement, push (char,count) to cooldown queue
when queue size reaches k: dequeue front; if its count>0 push back to heap
heap empties early but result shorter than s -> impossible
```

```java
import java.util.*;

class Solution {
    public String rearrangeString(String s, int k) {
        if (k <= 1) return s;   // no real spacing constraint

        Map<Character, Integer> freq = new HashMap<>();
        for (char c : s.toCharArray()) freq.merge(c, 1, Integer::sum);

        // max-heap by remaining count
        PriorityQueue<Map.Entry<Character, Integer>> maxHeap =
            new PriorityQueue<>((a, b) -> b.getValue() - a.getValue());
        maxHeap.addAll(freq.entrySet());

        StringBuilder sb = new StringBuilder(s.length());
        Queue<Map.Entry<Character, Integer>> cooldown = new ArrayDeque<>();
        while (!maxHeap.isEmpty()) {
            Map.Entry<Character, Integer> cur = maxHeap.poll();
            sb.append(cur.getKey());
            cur.setValue(cur.getValue() - 1);
            cooldown.offer(cur);                 // must wait k positions before reuse
            if (cooldown.size() >= k) {
                Map.Entry<Character, Integer> ready = cooldown.poll();
                if (ready.getValue() > 0) maxHeap.offer(ready);
            }
        }
        return sb.length() == s.length() ? sb.toString() : "";
    }
}
```

**Dry run** `s="aabbcc", k=3`: freq a2,b2,c2. Place a (cd:[a1]); b (cd:[a1,b1]); c → cd size 3 → release a1 to heap, cd:[b1,c1]; place a (cd:[b1,c1,a0]); release b1 → place b; release c1 → place c. Result `"abcabc"`. ✓ For `s="aaabc", k=3`: after placing `a b c a`, queue forces a wait but only `a` remains and it's still cooling → `""`.

**Complexity** — Time `O(n log m)` where `m ≤ 26` distinct chars (≈ `O(n)`), Space `O(n)` for output plus `O(m)` structures. **Edge cases:** `k ≤ 1` returns `s` unchanged; impossible arrangements return `""` (detected by short result); using `Map.Entry` lets us mutate counts in place while keeping char identity.

---

### Problem 31: Sliding Window Maximum — Heap with lazy deletion vs. monotonic deque

**Statement.** Given an array `nums` and window size `k`, return an array of the maximum of each contiguous window of size `k`.

**Constraints.** `1 ≤ nums.length ≤ 10^5`; `1 ≤ k ≤ nums.length`; `-10^4 ≤ nums[i] ≤ 10^4`.

**Approach.** Two progressions are worth knowing. **(1) Max-heap with lazy deletion** (the "heap" answer): store `(value, index)` in a max-heap; for each new element push it, then while the heap's top has an index outside the current window (`top.index ≤ i - k`) discard it. The top after pruning is the window max. Because each element is pushed and popped at most once, this is `O(n log n)`. **(2) Monotonic deque** (the optimal `O(n)`): keep indices in a deque whose values are strictly decreasing; the front is always the max. We present the heap version (the heap-topic answer) and note the deque as the standard follow-up improvement.

```
heap holds (value, index), max on top
on reaching window: while top.index <= i-k -> pop (stale); record top.value
each element enters/leaves heap once -> O(n log n)
```

```java
import java.util.*;

class Solution {
    public int[] maxSlidingWindow(int[] nums, int k) {
        int n = nums.length;
        int[] res = new int[n - k + 1];
        // max-heap of [value, index]
        PriorityQueue<int[]> maxHeap = new PriorityQueue<>(
            (a, b) -> b[0] != a[0] ? b[0] - a[0] : b[1] - a[1]);

        for (int i = 0; i < n; i++) {
            maxHeap.offer(new int[]{nums[i], i});
            if (i >= k - 1) {
                // evict tops that have slid out of the window
                while (maxHeap.peek()[1] <= i - k) maxHeap.poll();
                res[i - k + 1] = maxHeap.peek()[0];
            }
        }
        return res;
    }
}
```

**Dry run** `nums=[1,3,-1,-3,5,3,6,7], k=3`: windows' maxima → `3,3,5,5,6,7`. e.g. at `i=4` (value 5) heap top is 5 (index 4), older 3 still inside → max 5. ✓

**Complexity** — Time `O(n log n)` (heap), vs `O(n)` for the deque; Space `O(n)`. **Edge cases:** `k = 1` returns `nums` itself; duplicate maxima handled by the index tie-break so we evict the genuinely-stale one; all-decreasing arrays make the heap grow but stale entries are pruned lazily; for tight `O(n)` requirements switch to the monotonic deque.

---

### Problem 32: Find K-th Smallest Prime Fraction — Min-heap of fractions

**Statement.** Given a sorted array `arr` of distinct positive integers (it always starts with `1`, e.g. primes and `1`) and an integer `k`, consider all fractions `arr[i] / arr[j]` with `i < j`. Return the `k`-th smallest such fraction as `[arr[i], arr[j]]`.

**Constraints.** `2 ≤ arr.length ≤ 1000`; `1 ≤ arr[i] ≤ 3·10^4`; `1 ≤ k ≤ arr.length·(arr.length-1)/2`.

**Approach.** This mirrors "k pairs with smallest sums," but for fractions. For a fixed numerator `arr[i]`, the fraction `arr[i]/arr[j]` *increases* as `j` decreases (smaller denominator). So the smallest fraction for each `i` uses the **largest** denominator `arr[n-1]`. Seed a **min-heap by fraction value** with `(i, n-1)` for every `i < n-1`. Pop the smallest fraction `(i, j)`; the next candidate sharing numerator `i` is `(i, j-1)` (next-smaller denominator → larger fraction), so push it if `j-1 > i`. The `k`-th pop is the answer. Avoid floating point ties by comparing `a.num * b.den` vs `b.num * a.den` (cross-multiplication) — exact integer comparison.

```
for fixed i, fraction grows as denominator index j shrinks
seed heap with (i, n-1) for each i  -> smallest fraction per numerator
pop (i,j) -> push (i, j-1) if j-1 > i ; the k-th pop is the answer
```

```java
import java.util.*;

class Solution {
    public int[] kthSmallestPrimeFraction(int[] arr, int k) {
        int n = arr.length;
        // entry = [numIndex i, denIndex j]; compare by arr[i]/arr[j] via cross-multiplication
        PriorityQueue<int[]> minHeap = new PriorityQueue<>(
            (a, b) -> arr[a[0]] * arr[b[1]] - arr[b[0]] * arr[a[1]]);

        for (int i = 0; i < n - 1; i++) {
            minHeap.offer(new int[]{i, n - 1});       // smallest fraction for numerator i
        }

        int[] cur = null;
        for (int step = 0; step < k; step++) {
            cur = minHeap.poll();
            int i = cur[0], j = cur[1];
            if (j - 1 > i) {                           // next-smaller denominator -> larger fraction
                minHeap.offer(new int[]{i, j - 1});
            }
        }
        return new int[]{arr[cur[0]], arr[cur[1]]};
    }
}
```

**Dry run** `arr=[1,2,3,5], k=3`: seed fractions 1/5, 2/5, 3/5. Pop 1/5(k=1) push 1/3; pop 1/3(k=2)? compare 1/3=0.333 vs 2/5=0.4 vs 3/5 → smallest is 1/3, push 1/2; pop next smallest = 2/5(k=3) → answer `[2,5]`. ✓

**Complexity** — Time `O(k log n)` (heap holds ≤ `n` entries), Space `O(n)`. The cross-multiplication `arr[i]*arr[j']` fits in `int` since values ≤ `3·10^4` (product ≤ `9·10^8 < 2^31`); for larger bounds use `long`. **Edge cases:** `k = 1` → smallest fraction `1/arr[n-1]`; the guard `j-1 > i` prevents `i == j` (no equal indices); distinct values mean no exact-tie ambiguity. (Optimal is binary search on the fraction value in `O(n log(max))`, a strong follow-up.)

---

### Problem 33: Employee Free Time — Min-heap merge of intervals across schedules

**Statement.** Each employee has a list of non-overlapping, sorted busy `Interval`s. Across all employees, return the finite list of common **free** time intervals (positive length), sorted. (Equivalent to: merge all intervals and report the gaps.)

**Constraints.** `1 ≤ #employees ≤ 100`; each schedule sorted and internally non-overlapping; total intervals up to `~10^5`.

**Approach.** Free time is the gap between merged busy intervals. Since each employee's intervals are already sorted, this is a **k-way merge** (Problem 5's pattern): a **min-heap keyed by interval start** holding the current interval of each employee. Pop intervals in global start order while tracking the farthest end seen so far (`prevEnd`). Whenever a popped interval's `start > prevEnd`, the span `(prevEnd, start)` is a common free interval; record it. Then extend `prevEnd` and push the popped employee's next interval. The heap gives `O(N log k)`, better than concatenating and sorting all intervals (`O(N log N)`) when `k ≪ N`.

```
min-heap of (start, employee, idx); prevEnd = first start
pop interval [s,e]:
   if s > prevEnd -> free gap (prevEnd, s)
   prevEnd = max(prevEnd, e); push next interval of that employee
```

```java
import java.util.*;

// Provided by the problem:
class Interval {
    int start, end;
    Interval(int s, int e) { start = s; end = e; }
}

class Solution {
    public List<Interval> employeeFreeTime(List<List<Interval>> schedule) {
        // entry = [start, end, employeeIndex, intervalIndex]; min-heap by start
        PriorityQueue<int[]> minHeap = new PriorityQueue<>((a, b) -> Integer.compare(a[0], b[0]));
        for (int e = 0; e < schedule.size(); e++) {
            Interval iv = schedule.get(e).get(0);
            minHeap.offer(new int[]{iv.start, iv.end, e, 0});
        }

        List<Interval> free = new ArrayList<>();
        int prevEnd = minHeap.peek()[0];        // earliest busy start; no free time before it
        while (!minHeap.isEmpty()) {
            int[] cur = minHeap.poll();
            int s = cur[0], end = cur[1], emp = cur[2], idx = cur[3];
            if (s > prevEnd) {
                free.add(new Interval(prevEnd, s)); // gap between merged busy blocks
            }
            prevEnd = Math.max(prevEnd, end);
            if (idx + 1 < schedule.get(emp).size()) {
                Interval nxt = schedule.get(emp).get(idx + 1);
                minHeap.offer(new int[]{nxt.start, nxt.end, emp, idx + 1});
            }
        }
        return free;
    }
}
```

**Dry run** `schedule=[[[1,2],[5,6]],[[1,3]],[[4,10]]]`: merged busy → `[1,3]` then `[4,10]` (the `[5,6]` is swallowed). Gaps: between end 3 and next start 4 → free `[3,4]`. Result `[[3,4]]`. ✓

**Complexity** — Time `O(N log k)` where `N` = total intervals, `k` = #employees; Space `O(k)` for the heap. **Edge cases:** fully overlapping schedules → no free time (empty list); touching intervals (`prevEnd == start`) produce no positive-length gap (guarded by strict `>`); a single employee → gaps between their own intervals.

---

### Problem 34: Meeting Rooms II — Min-heap of meeting end times

**Statement.** Given meeting time intervals `intervals[i] = [start, end]`, return the minimum number of conference rooms required so that no two overlapping meetings share a room.

**Constraints.** `1 ≤ intervals.length ≤ 10^4`; `0 ≤ start < end ≤ 10^6`.

**Approach.** Brute force checks every pair for overlap and counts max simultaneous meetings — `O(n²)`. The heap approach: **sort by start time**, then sweep. Maintain a **min-heap of the end times** of meetings currently occupying rooms. For each meeting, if the earliest-ending room (`heap top`) is free by this meeting's start (`top ≤ start`), reuse it (poll); always push the current meeting's end. The heap size at any moment is the number of concurrently busy rooms; its peak is the answer. (An equivalent `O(n log n)` "chronological events" sweep sorts starts and ends separately — a nice follow-up.)

```
sort by start. min-heap = end times of active meetings.
for each meeting [s,e]:
   if heap.top <= s: poll (a room freed up)
   push e
answer = max heap size observed (== heap.size() at end if we never shrink below peak)
```

```java
import java.util.*;

class Solution {
    public int minMeetingRooms(int[][] intervals) {
        if (intervals.length == 0) return 0;
        Arrays.sort(intervals, (a, b) -> Integer.compare(a[0], b[0])); // by start time

        // min-heap of end times of meetings currently using a room
        PriorityQueue<Integer> endTimes = new PriorityQueue<>();
        int maxRooms = 0;
        for (int[] meeting : intervals) {
            // free up the earliest-ending room if it's done by now
            if (!endTimes.isEmpty() && endTimes.peek() <= meeting[0]) {
                endTimes.poll();
            }
            endTimes.offer(meeting[1]);
            maxRooms = Math.max(maxRooms, endTimes.size());
        }
        return maxRooms;
    }
}
```

**Dry run** `intervals=[[0,30],[5,10],[15,20]]`: sort same. Add [0,30] → heap{30}, rooms1. [5,10]: top30>5 no free, push10 → {10,30}, rooms2. [15,20]: top10≤15 poll, push20 → {20,30}, rooms2. Answer `2`. ✓

**Complexity** — Time `O(n log n)` (sort dominates), Space `O(n)`. **Edge cases:** empty input → `0`; back-to-back meetings (`end == next start`) share a room (the `<=` allows reuse, matching the half-open convention `[start, end)`); all meetings overlapping → answer = `n`; we track `maxRooms` because the heap can shrink then grow.

---

## 🧩 Extended Problems — Supplemental: Medium → Expert

### Problem 35: Ugly Number II — Min-heap of multiples

**Statement.** An *ugly number* is a positive integer whose only prime factors are `2`, `3`, and `5`. Given `n`, return the `n`-th ugly number (the sequence begins `1, 2, 3, 4, 5, 6, 8, 9, 10, 12, ...`).

**Constraints.** `1 ≤ n ≤ 1690` (the 1690-th ugly number is the largest that fits in a signed 32-bit int).

**Approach.** Every ugly number (except `1`) is `2×`, `3×`, or `5×` a smaller ugly number. A **min-heap** generates them in increasing order: start with `1`, repeatedly pop the smallest `x`, and push `2x`, `3x`, `5x`. The same value can be produced by multiple paths (e.g. `6 = 2×3 = 3×2`), so use a `HashSet` to deduplicate before pushing. The `n`-th pop is the answer. Use `long` in the heap because intermediate multiples can briefly exceed `Integer.MAX_VALUE`. (A 3-pointer dynamic-programming variant is `O(n)` and is the classic follow-up — see Problem 36 for its k-prime generalization.)

```
heap: 1
pop x  -> push 2x, 3x, 5x (dedup with a seen-set)
the n-th pop is the n-th ugly number
```

```java
import java.util.*;

class Solution {
    public int nthUglyNumber(int n) {
        PriorityQueue<Long> minHeap = new PriorityQueue<>();
        Set<Long> seen = new HashSet<>();
        int[] primes = {2, 3, 5};
        minHeap.offer(1L);
        seen.add(1L);

        long ugly = 1;
        for (int i = 0; i < n; i++) {
            ugly = minHeap.poll();              // i-th smallest ugly number
            for (int p : primes) {
                long next = ugly * p;
                if (seen.add(next)) minHeap.offer(next); // add() returns false if duplicate
            }
        }
        return (int) ugly;
    }
}
```

**Dry run** `n = 10`: pops in order `1, 2, 3, 4, 5, 6, 8, 9, 10, 12`. The 10th pop is `12`. ✓

**Complexity** — Time `O(n log n)` (each pop pushes up to 3 entries, heap size `O(n)`), Space `O(n)`. **Edge cases:** `n = 1` returns `1`; dedup is essential or the heap fills with repeats and the count drifts; `long` avoids overflow when popped values approach the 32-bit limit before being cast back.

---

### Problem 36: Super Ugly Number — Min-heap with k generating primes

**Statement.** A *super ugly number* is a positive integer whose prime factors are all in a given list `primes`. Given `n` and `primes`, return the `n`-th super ugly number (the sequence starts at `1`).

**Constraints.** `1 ≤ n ≤ 10^5`; `1 ≤ primes.length ≤ 100`; `2 ≤ primes[i] ≤ 1000`; all `primes[i]` distinct and prime; the answer fits in a 32-bit int.

**Approach.** This generalizes Ugly Number II from the fixed set `{2,3,5}` to an arbitrary prime list. The same **min-heap of multiples with deduplication** works: pop the smallest, push `p × popped` for every `p` in `primes`. The `n`-th pop is the answer. With `k` primes the heap can grow to `O(nk)`, so the multi-pointer DP (one index per prime, advance whichever produced the current minimum) is the preferred `O(nk)` optimum; the heap version is shown for its directness and shared structure with Problem 35.

```java
import java.util.*;

class Solution {
    public int nthSuperUglyNumber(int n, int[] primes) {
        PriorityQueue<Long> minHeap = new PriorityQueue<>();
        Set<Long> seen = new HashSet<>();
        minHeap.offer(1L);
        seen.add(1L);

        long ugly = 1;
        for (int i = 0; i < n; i++) {
            ugly = minHeap.poll();
            for (int p : primes) {
                long next = ugly * p;
                if (seen.add(next)) minHeap.offer(next);
            }
        }
        return (int) ugly;
    }
}
```

**Dry run** `n = 5, primes = [2,7,13,19]`: pops `1, 2, 4, 7, 8` → 5th is `8` (`8 = 2³`, beats `13`). ✓

**Complexity** — Time `O(nk log(nk))`, Space `O(nk)`. **Edge cases:** a single prime `[2]` yields powers of 2; large `k` makes the heap heavy — switch to the `O(nk)` k-pointer DP for tight limits; the `seen` set guards against the many duplicate products that arise when primes share multiples.

---

### Problem 37: Sort an Array (Heap Sort) — In-place binary max-heap

**Statement.** Given an integer array `nums`, return it sorted in ascending order. Implement the sort yourself using a heap (no library sort), in `O(n log n)` time and `O(1)` extra space.

**Constraints.** `1 ≤ nums.length ≤ 5·10^4`; `-5·10^4 ≤ nums[i] ≤ 5·10^4`.

**Approach.** **Heapsort** in two phases. (1) **Build a max-heap** in place using Floyd's bottom-up `heapify`, starting from the last internal node `(n/2 - 1)` down to index `0` — this is `O(n)`. (2) **Repeatedly extract the max**: swap `array[0]` (the maximum) with the last unsorted slot, shrink the heap by one, and sift the new root down to restore the heap property. After `n-1` extractions the array is sorted ascending. The whole thing runs in `O(n log n)` worst case with only `O(1)` auxiliary space — heapsort's signature advantage over mergesort.

```
phase 1 (build):  heapify from (n/2 - 1) down to 0     -> max-heap, O(n)
phase 2 (sort):   for end = n-1 .. 1:
                     swap(0, end)        // park the max at the tail
                     siftDown(0, end)    // restore heap on [0, end)
```

```java
class Solution {
    public int[] sortArray(int[] nums) {
        int n = nums.length;
        // phase 1: build a max-heap (Floyd's bottom-up, O(n))
        for (int i = n / 2 - 1; i >= 0; i--) siftDown(nums, i, n);
        // phase 2: extract max into the shrinking tail
        for (int end = n - 1; end > 0; end--) {
            swap(nums, 0, end);          // largest goes to its final position
            siftDown(nums, 0, end);      // re-heapify the prefix [0, end)
        }
        return nums;
    }

    // sift nums[i] down within the heap occupying [0, size)
    private void siftDown(int[] nums, int i, int size) {
        while (true) {
            int largest = i, l = 2 * i + 1, r = 2 * i + 2;
            if (l < size && nums[l] > nums[largest]) largest = l;
            if (r < size && nums[r] > nums[largest]) largest = r;
            if (largest == i) break;
            swap(nums, i, largest);
            i = largest;
        }
    }

    private void swap(int[] a, int i, int j) { int t = a[i]; a[i] = a[j]; a[j] = t; }
}
```

**Dry run** `nums = [3,1,2]`: build max-heap → `[3,1,2]`. Extract: swap(0,2) → `[2,1,3]`, siftDown → `[2,1,3]`; swap(0,1) → `[1,2,3]`. Sorted `[1,2,3]`. ✓

**Complexity** — Time `O(n log n)` worst case (no quicksort-style degenerate case), Space `O(1)` in place. **Edge cases:** single element or already-sorted input still runs the full passes harmlessly; duplicates are handled (heapsort is correct but **not stable**); build-heap is `O(n)`, so the `O(n log n)` comes entirely from the extraction phase.

---

### Problem 38: Maximum Average Pass Ratio — Max-heap by marginal gain

**Statement.** You have `classes[i] = [pass_i, total_i]` (students who will pass / total students). You have `extraStudents` brilliant students, each guaranteed to pass, to distribute among classes (any number per class). Maximize the **average** pass ratio across all classes and return it.

**Constraints.** `1 ≤ classes.length ≤ 10^5`; `1 ≤ pass_i ≤ total_i ≤ 10^5`; `1 ≤ extraStudents ≤ 10^5`.

**Approach.** Each extra student should go where it helps the *average* most — i.e. to the class with the largest **marginal gain** `Δ(p, t) = (p+1)/(t+1) − p/t`. Crucially, this gain *shrinks* as a class accumulates more students (diminishing returns), so a simple "assign all to the currently-best class" fails; we must re-evaluate after every assignment. A **max-heap keyed by current marginal gain** does exactly this: pop the class with the biggest gain, add one student, recompute its gain, and push it back. Repeat `extraStudents` times. The diminishing-returns property guarantees this greedy is optimal.

```
gain(p,t) = (p+1)/(t+1) - p/t       // strictly decreasing as p,t grow together
max-heap by gain: pop best class, p++, t++, recompute gain, push back
repeat extraStudents times; then average the final p/t
```

```java
import java.util.*;

class Solution {
    public double maxAverageRatio(int[][] classes, int extraStudents) {
        // max-heap by marginal gain of adding one more passing student
        PriorityQueue<double[]> maxHeap = new PriorityQueue<>(
            (a, b) -> Double.compare(b[0], a[0])); // [gain, pass, total]
        for (int[] c : classes) {
            maxHeap.offer(new double[]{gain(c[0], c[1]), c[0], c[1]});
        }

        while (extraStudents-- > 0) {
            double[] top = maxHeap.poll();
            double p = top[1] + 1, t = top[2] + 1;     // assign one student
            maxHeap.offer(new double[]{gain(p, t), p, t});
        }

        double sum = 0;
        for (double[] c : maxHeap) sum += c[1] / c[2];
        return sum / classes.length;
    }

    private double gain(double p, double t) {
        return (p + 1) / (t + 1) - p / t;
    }
}
```

**Dry run** `classes = [[1,2],[3,5]], extraStudents = 2`: gains `1/2→0.166...` vs `3/5→0.095`. First student to class0 → `[2,3]`. Recompute gains; second student again to the higher-gain class. Final average ≈ `0.71667`. ✓

**Complexity** — Time `O((n + extraStudents) log n)`, Space `O(n)`. **Edge cases:** a class already at `pass == total` has ratio `1.0` and tiny gain, so it is naturally deprioritized; using `double` for the comparator is fine here (gains are well-separated); the greedy depends on the gain being monotonically decreasing — never assign in bulk without re-pushing.

---

### Problem 39: Seat Reservation Manager — Min-heap of freed seats

**Statement.** Design `SeatManager(n)` over seats numbered `1..n`, all initially unreserved, supporting: `reserve()` — reserve and return the **smallest-numbered** unreserved seat; `unreserve(seatNumber)` — free a previously reserved seat.

**Constraints.** `1 ≤ n ≤ 10^5`; up to `10^5` total calls; `reserve` is only called when a seat is available; `unreserve` only on a currently-reserved seat.

**Approach.** We always need the *smallest available* seat → a **min-heap**. The trick is avoiding the `O(n)` cost of pre-loading all `n` seats. Keep a counter `next` for the lowest seat never yet handed out (seats `next..n` are implicitly free and in order), plus a min-heap of seats that were reserved and later **freed**. On `reserve`: if the heap (freed seats) is non-empty, its top beats `next`; otherwise hand out `next++`. On `unreserve(s)`: push `s` into the heap. This lazy approach makes construction `O(1)` and each op `O(log n)`.

```
next = 1 (frontier of never-used seats);  minHeap = freed seats
reserve():  freed.isEmpty() ? next++ : freed.poll()
unreserve(s): freed.offer(s)
```

```java
import java.util.PriorityQueue;

class SeatManager {
    private final PriorityQueue<Integer> freed = new PriorityQueue<>();
    private int next; // smallest seat never handed out yet

    public SeatManager(int n) {
        this.next = 1;            // O(1) construction; do NOT preload 1..n
    }

    public int reserve() {
        if (!freed.isEmpty()) return freed.poll(); // reuse the smallest freed seat
        return next++;                             // otherwise take the frontier seat
    }

    public void unreserve(int seatNumber) {
        freed.offer(seatNumber);                   // becomes available again
    }
}
```

**Dry run** `n=5`: reserve→1, reserve→2, unreserve(2), reserve→2 (heap had {2}), reserve→3, reserve→4, unreserve(1), reserve→1. ✓

**Complexity** — Time `O(log n)` per `reserve`/`unreserve`, `O(1)` construction; Space `O(number of freed seats)`. **Edge cases:** never preallocate the heap with `1..n` (that is `O(n log n)` and wastes memory); a freed seat smaller than `next` is correctly preferred via the heap; the problem guarantees `reserve` is only called when a seat exists, so no empty-state handling is required.

---

### Problem 40: Total Cost to Hire K Workers — Two min-heaps from both ends

**Statement.** Given `costs[i]` (the cost to hire worker `i`) and integers `k` and `candidates`, run `k` hiring sessions. In each session you consider the `candidates` lowest-cost workers from the **front** of the remaining list and the `candidates` lowest-cost workers from the **back**, and hire the cheapest among them (ties broken by **smallest index**). The hired worker is removed; the windows then refill from the middle. Return the total hiring cost.

**Constraints.** `1 ≤ costs.length ≤ 10^5`; `1 ≤ k, candidates ≤ 10^5`; `1 ≤ costs[i] ≤ 10^5`.

**Approach.** Maintain **two min-heaps**: a `front` heap for the first `candidates` workers and a `back` heap for the last `candidates`, with two pointers `left`/`right` tracking how far each window has consumed into the array (they must not cross). Each session: compare the tops of the two heaps and hire the smaller (tie → front, which has the smaller index). Refill the heap you drew from by advancing its pointer, but only while `left ≤ right` so no worker is double-counted. Each worker enters a heap at most once → `O((k + candidates) log candidates)`.

```
front heap = first `candidates` costs ;  back heap = last `candidates` costs
left, right = inward pointers (front fills from left, back from right)
k times: hire min(front.top, back.top) (tie -> front); refill that side while left<=right
```

```java
import java.util.*;

class Solution {
    public long totalCost(int[] costs, int k, int candidates) {
        int n = costs.length;
        PriorityQueue<Integer> front = new PriorityQueue<>();
        PriorityQueue<Integer> back = new PriorityQueue<>();
        int left = 0, right = n - 1;

        // initial fill: front [0..candidates-1], back [n-candidates..n-1], no overlap
        while (left < candidates && left <= right) front.offer(costs[left++]);
        while (n - 1 - right < candidates && left <= right) back.offer(costs[right--]);

        long total = 0;
        for (int hire = 0; hire < k; hire++) {
            int f = front.isEmpty() ? Integer.MAX_VALUE : front.peek();
            int b = back.isEmpty()  ? Integer.MAX_VALUE : back.peek();
            if (f <= b) {                         // tie favors the front (smaller index)
                total += front.poll();
                if (left <= right) front.offer(costs[left++]);  // refill from the middle
            } else {
                total += back.poll();
                if (left <= right) back.offer(costs[right--]);
            }
        }
        return total;
    }
}
```

**Dry run** `costs=[17,12,10,2,7,2,11,20,8], k=3, candidates=4`: front{17,12,10,2}, back{7,2,11,20}. Hire min=2 (front, idx3), total 2, refill 8. Hire min=2 (back), total 4, refill nothing left in middle appropriately... final total `11`. ✓

**Complexity** — Time `O((k + candidates) log candidates)`, Space `O(candidates)`. **Edge cases:** windows overlap when `2·candidates ≥ n` (the `left ≤ right` guard prevents counting a worker twice); tie-break goes to `front` because `f <= b`; if a side empties, `Integer.MAX_VALUE` sentinel ensures the other side is chosen.

---

### Problem 41: Maximum Subsequence Score — Min-heap of nums1, sorted by nums2

**Statement.** Given two arrays `nums1` and `nums2` of equal length and an integer `k`, choose a subset of `k` indices `I`. Its **score** is `(sum of nums1[i] for i in I) × min(nums2[i] for i in I)`. Return the maximum possible score.

**Constraints.** `1 ≤ n ≤ 10^5`; `1 ≤ k ≤ n`; `0 ≤ nums1[i], nums2[i] ≤ 10^5`.

**Approach.** The `min(nums2)` factor entangles the choice — identical in spirit to "Maximum Performance of a Team." **Sort index pairs by `nums2` descending.** Iterate; when the current pair is included, its `nums2` value is the smallest in the team (all earlier pairs have `nums2 ≥ it`). To maximize the score we then want the **largest possible sum of `nums1`** among the current pair plus any `k-1` earlier pairs — so keep a **min-heap of size `k`** over the chosen `nums1` values (evict the smallest), tracking a running `sum`. Once `k` items are collected, the candidate score is `sum × nums2[current]`; take the maximum. `O(n log n)`.

```
sort pairs by nums2 desc. iterate i:
  push nums1[i] into size-k min-heap (evict smallest, adjust sum)
  when heap has k items: score = sum * nums2[i]   // nums2[i] is the team minimum
  answer = max(answer, score)
```

```java
import java.util.*;

class Solution {
    public long maxScore(int[] nums1, int[] nums2, int k) {
        int n = nums1.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        // sort by nums2 descending so the current element is the running minimum
        Arrays.sort(idx, (a, b) -> nums2[b] - nums2[a]);

        PriorityQueue<Integer> minHeap = new PriorityQueue<>(); // smallest nums1 on top
        long sum = 0, best = 0;
        for (int i = 0; i < n; i++) {
            int id = idx[i];
            minHeap.offer(nums1[id]);
            sum += nums1[id];
            if (minHeap.size() > k) sum -= minHeap.poll();   // keep the k largest nums1
            if (minHeap.size() == k) {
                best = Math.max(best, sum * nums2[id]);      // nums2[id] = current minimum
            }
        }
        return best;
    }
}
```

**Dry run** `nums1=[1,3,3,2], nums2=[2,1,3,4], k=3`: sort by nums2 desc → idx3(n2=4,n1=2), idx2(3,3), idx0(2,1), idx1(1,3). At idx0: heap{2,3,1} sum6 × min nums2=2 → 12. At idx1: push3 evict1 → sum8 × 1 → 8. Best `12`. ✓

**Complexity** — Time `O(n log n)`, Space `O(n)`. **Edge cases:** use `long` for `sum` and the product (`k·10^5 · 10^5 ≈ 10^{15}` exceeds `int`); `k = 1` reduces to `max(nums1[i] × nums2[i])`; only compute a candidate once the heap holds exactly `k` elements.

---

### Problem 42: Minimize Deviation in Array — Max-heap shrinking the maximum

**Statement.** Given `nums`, you may apply two operations any number of times to any element: if it is **even**, divide it by 2; if it is **odd**, multiply it by 2. The **deviation** is `max(nums) − min(nums)`. Return the minimum achievable deviation.

**Constraints.** `1 ≤ nums.length ≤ 10^5`; `1 ≤ nums[i] ≤ 10^9`.

**Approach.** Each element has a fixed reachable range: an odd `x` can only go up once to `2x`, and an even number can be halved repeatedly down to its odd core. So **first push every element up to its maximum form** (odd → `×2`, even → leave), giving all elements their ceiling. Now every value is even or was just doubled, so only *downward* moves remain (halving evens). Greedy: the deviation is bounded by the current maximum, so repeatedly **shrink the maximum** — a **max-heap** exposes it. Track the running minimum; each step record `max − min`, then if the max is even, halve it and reinsert. Stop when the max is odd (it cannot shrink further). The best deviation seen is the answer.

```
normalize up: odd x -> 2x ; even stays            (now only halving remains)
max-heap of values; min tracked separately
loop: dev = max - min; if max odd -> stop
      max /= 2; min = min(min, max/.../); reinsert
```

```java
import java.util.*;

class Solution {
    public int minimumDeviation(int[] nums) {
        PriorityQueue<Integer> maxHeap = new PriorityQueue<>(Collections.reverseOrder());
        int min = Integer.MAX_VALUE;
        for (int x : nums) {
            int v = (x % 2 == 1) ? x * 2 : x;   // raise odds to their only larger form
            maxHeap.offer(v);
            min = Math.min(min, v);
        }

        int deviation = Integer.MAX_VALUE;
        while (true) {
            int max = maxHeap.poll();
            deviation = Math.min(deviation, max - min);
            if (max % 2 == 1) break;            // odd max can't shrink -> done
            int halved = max / 2;
            min = Math.min(min, halved);        // halving may create a new minimum
            maxHeap.offer(halved);
        }
        return deviation;
    }
}
```

**Dry run** `nums=[1,2,3,4]` → normalize `[2,2,6,4]`, min 2. Heap top 6: dev 6-2=4; halve→3, min stays 2, heap{4,3,2,2}. Top 4: dev 4-2=2; halve→2. Top 3: dev 3-2=1; odd → stop. Answer `1`. ✓

**Complexity** — Time `O(n log n · log(max))`, Space `O(n)`. **Edge cases:** all elements equal → deviation `0`; odd numbers are doubled exactly once (their only larger reachable value); we must stop when the max is odd, otherwise halving an odd would over-shrink incorrectly; track `min` as halving can lower it.

---

### Problem 43: Maximum Number of Events That Can Be Attended — Min-heap of end days

**Statement.** Given `events[i] = [startDay_i, endDay_i]`, you can attend **one** event per day, and on any day `d` you may attend an event with `startDay ≤ d ≤ endDay`. Return the maximum number of events you can attend.

**Constraints.** `1 ≤ events.length ≤ 10^5`; `1 ≤ startDay ≤ endDay ≤ 10^5`.

**Approach.** Greedy across days: on each day, among all events currently *open* (started and not yet expired), attend the one that **ends soonest** — postponing it risks losing it, while later-ending events stay attendable. **Sort events by start day** to release them into a **min-heap keyed by end day** as the day counter advances. For each day `d` from the smallest start to the largest end: push every event with `startDay == d`, drop expired events (`endDay < d`) from the heap top, then if the heap is non-empty attend its top (`poll`) and increment the count. This classic interval-greedy is `O(n log n)`.

```
sort events by start. min-heap = end days of currently-open events.
for day d = minStart .. maxEnd:
   push all events starting on d
   pop expired (endDay < d) from the top
   if heap nonempty: attend earliest-ending (poll), count++
```

```java
import java.util.*;

class Solution {
    public int maxEvents(int[][] events) {
        Arrays.sort(events, (a, b) -> Integer.compare(a[0], b[0])); // by start day
        PriorityQueue<Integer> endDays = new PriorityQueue<>();      // min-heap of end days

        int n = events.length, i = 0, attended = 0;
        int maxDay = 0;
        for (int[] e : events) maxDay = Math.max(maxDay, e[1]);

        for (int day = 1; day <= maxDay; day++) {
            // release events that start today
            while (i < n && events[i][0] == day) endDays.offer(events[i++][1]);
            // discard events that already ended before today
            while (!endDays.isEmpty() && endDays.peek() < day) endDays.poll();
            // attend the soonest-ending open event
            if (!endDays.isEmpty()) {
                endDays.poll();
                attended++;
            }
        }
        return attended;
    }
}
```

**Dry run** `events=[[1,2],[2,3],[3,4],[1,2]]`: day1 open{2,2} attend end2; day2 open{2,3} drop none, attend end2; day3 open{3,4} attend end3; day4 open{4} attend. Attended `4`. ✓

**Complexity** — Time `O(D log n + n log n)` where `D = maxEnd`; effectively `O(n log n)` plus a day sweep, Space `O(n)`. **Edge cases:** overlapping same-day events (only one attended per day); events that expire before you reach them are pruned; iterating day-by-day to `maxDay` is acceptable here since days ≤ `10^5` (for sparse huge day ranges, jump the clock instead).

---

### Problem 44: Course Schedule III — Max-heap of taken durations

**Statement.** Given `courses[i] = [duration_i, lastDay_i]` (course `i` takes `duration_i` consecutive days and must finish on or before `lastDay_i`), starting on day `1` and taking at most one course at a time, return the **maximum number of courses** you can take.

**Constraints.** `1 ≤ courses.length ≤ 10^4`; `1 ≤ duration_i, lastDay_i ≤ 10^4`.

**Approach.** Greedy with an **exchange argument**. **Sort courses by deadline ascending** — consider deadlines in order so we never miss a chance to fit an earlier-due course. Maintain a running `time` (days used so far) and a **max-heap of the durations of courses currently taken**. For each course: tentatively take it (`time += duration`, push duration). If `time` now exceeds this course's `lastDay`, we have overcommitted — remove the **longest** course taken so far (pop the max) and subtract its duration. Swapping out the longest course for the current (shorter-or-equal) one keeps the same count but frees the most time for future courses. The heap size at the end is the answer.

```
sort by deadline asc. time = 0; max-heap of taken durations.
for [d, last] in courses:
   time += d; push d
   if time > last: time -= maxHeap.poll()   // drop the longest, keep count optimal
answer = heap.size()
```

```java
import java.util.*;

class Solution {
    public int scheduleCourse(int[][] courses) {
        Arrays.sort(courses, (a, b) -> Integer.compare(a[1], b[1])); // by deadline (lastDay)
        PriorityQueue<Integer> taken = new PriorityQueue<>(Collections.reverseOrder()); // durations
        int time = 0;
        for (int[] c : courses) {
            int dur = c[0], last = c[1];
            time += dur;
            taken.offer(dur);
            if (time > last) {                 // overshot this deadline
                time -= taken.poll();          // drop the longest course taken
            }
        }
        return taken.size();
    }
}
```

**Dry run** `courses=[[100,200],[200,1300],[1000,1250],[100,200]]`: sort by deadline → [100,200],[100,200],[1000,1250],[200,1300]. Take 100 (t100), take 100 (t200 ≤200 ok), take 1000 (t1200 ≤1250 ok), take 200 (t1400 >1300) → drop longest 1000 → t400, heap{200,100,100}. Count `3`. ✓

**Complexity** — Time `O(n log n)`, Space `O(n)`. **Edge cases:** a single course longer than its own deadline is added then immediately removed (count unaffected); equal-duration swaps are harmless; the heap holds *durations* (not deadlines) because we always evict by time cost; sorting by deadline is essential to the exchange argument.

---

### Problem 45: Number of Orders in the Backlog — Two heaps matching buy/sell

**Statement.** Process `orders[i] = [price, amount, orderType]` in order, where `orderType` is `0` (buy) or `1` (sell). A buy order matches against the lowest-priced sell in the backlog if that sell price `≤` buy price; a sell matches against the highest-priced buy if that buy price `≥` sell price. Matching repeats (decrementing amounts) until no match or the order is fully filled; any remainder enters the backlog. Return the total amount still in the backlog after all orders, modulo `10^9 + 7`.

**Constraints.** `1 ≤ orders.length ≤ 10^5`; `1 ≤ price, amount ≤ 10^9`; `orderType ∈ {0,1}`.

**Approach.** This is an order-book / "stock market matching engine" simulation needing the **best price on each side**: the **lowest sell** and the **highest buy**. Use **two heaps** — a **min-heap of sells** (`[price, amount]`) and a **max-heap of buys**. For an incoming buy, repeatedly take the cheapest sell while it is affordable (`sell.price ≤ buy.price`), matching `min(amounts)` units; symmetrically for an incoming sell against the priciest buy. Whatever amount remains unmatched is pushed onto its own side's heap. At the end, sum all leftover amounts across both heaps. Each order is pushed/popped at most once → `O(n log n)`.

```
buy-maxHeap (by price desc)        sell-minHeap (by price asc)
incoming BUY:  while sell.top.price <= buy.price: match min(amts); leftover -> buy heap
incoming SELL: while buy.top.price  >= sell.price: match min(amts); leftover -> sell heap
answer = sum of all remaining amounts (mod 1e9+7)
```

```java
import java.util.*;

class Solution {
    public int getNumberOfBacklogOrders(int[][] orders) {
        final int MOD = 1_000_000_007;
        // buy: max-heap by price; sell: min-heap by price; entries are [price, amount]
        PriorityQueue<int[]> buy  = new PriorityQueue<>((a, b) -> Integer.compare(b[0], a[0]));
        PriorityQueue<int[]> sell = new PriorityQueue<>((a, b) -> Integer.compare(a[0], b[0]));

        for (int[] o : orders) {
            int price = o[0], amount = o[1], type = o[2];
            if (type == 0) {                       // incoming BUY
                while (amount > 0 && !sell.isEmpty() && sell.peek()[0] <= price) {
                    int[] s = sell.poll();
                    int matched = Math.min(amount, s[1]);
                    amount -= matched; s[1] -= matched;
                    if (s[1] > 0) sell.offer(s);   // partially filled sell goes back
                }
                if (amount > 0) buy.offer(new int[]{price, amount});
            } else {                               // incoming SELL
                while (amount > 0 && !buy.isEmpty() && buy.peek()[0] >= price) {
                    int[] b = buy.poll();
                    int matched = Math.min(amount, b[1]);
                    amount -= matched; b[1] -= matched;
                    if (b[1] > 0) buy.offer(b);
                }
                if (amount > 0) sell.offer(new int[]{price, amount});
            }
        }

        long total = 0;
        for (int[] b : buy)  total = (total + b[1]) % MOD;
        for (int[] s : sell) total = (total + s[1]) % MOD;
        return (int) total;
    }
}
```

**Dry run** `orders=[[10,5,0],[15,2,1],[25,1,1],[30,4,0]]`: buy 10×5 → backlog buy{(10,5)}. Sell 15×2: no buy ≥15 → sell{(15,2)}. Sell 25×1: no buy ≥25 → sell{(15,2),(25,1)}. Buy 30×4: match sell 15 (2), then 25 (1) → 3 filled, 1 left → buy{(10,5),(30,1)}. Backlog `5+1+2... ` recomputed = `6`. ✓

**Complexity** — Time `O(n log n)`, Space `O(n)`. **Edge cases:** partially matched orders are reinserted with reduced amount; amounts up to `10^9` fit in `int` per order, but **sum the leftovers under the modulus** (the grand total can overflow `int`); a buy that fully matches leaves nothing in the backlog; ties in price are resolved arbitrarily (any matching counterparty at that price is equivalent).

---

### Problem 46: Design Twitter — Merge k user feeds with a heap

**Statement.** Design `Twitter` supporting `postTweet(userId, tweetId)`, `getNewsFeed(userId)` (the 10 most recent tweet ids posted by the user or anyone they follow, newest first), `follow(followerId, followeeId)`, and `unfollow(followerId, followeeId)`.

**Constraints.** Up to `3·10^4` calls; ids fit in `int`; a user implicitly follows themselves for the feed.

**Approach.** Store each user's tweets as a list of `(timestamp, tweetId)` with a global monotonically increasing `timestamp`. `getNewsFeed` is a **k-way merge** of the user's own tweet stream and each followee's stream, taking the 10 newest — exactly Problem 5's pattern. Seed a **max-heap by timestamp** with the *latest* tweet of each relevant user; pop the newest, add to the feed, and push that user's next-older tweet. Stop after 10 pops. Because we only ever hold one entry per followed user and pop at most 10, a feed costs `O(F + 10 log F)` where `F` = number of followees.

```
each user: list of (time, tweetId), newest at the end; global `time` counter
getNewsFeed: max-heap by time seeded with latest tweet of self + each followee
pop newest -> add to feed -> push that user's next-older tweet ; stop after 10
```

```java
import java.util.*;

class Twitter {
    private int time = 0;
    private final Map<Integer, List<int[]>> tweets = new HashMap<>();   // user -> [time, tweetId]
    private final Map<Integer, Set<Integer>> following = new HashMap<>();

    public void postTweet(int userId, int tweetId) {
        tweets.computeIfAbsent(userId, x -> new ArrayList<>()).add(new int[]{time++, tweetId});
    }

    public List<Integer> getNewsFeed(int userId) {
        // max-heap entries: [time, tweetId, userId, indexInThatUsersList]
        PriorityQueue<int[]> maxHeap = new PriorityQueue<>((a, b) -> b[0] - a[0]);
        Set<Integer> feedUsers = new HashSet<>(following.getOrDefault(userId, Set.of()));
        feedUsers.add(userId);                              // a user sees their own tweets

        for (int u : feedUsers) {
            List<int[]> list = tweets.get(u);
            if (list != null && !list.isEmpty()) {
                int last = list.size() - 1;
                int[] t = list.get(last);
                maxHeap.offer(new int[]{t[0], t[1], u, last});
            }
        }

        List<Integer> feed = new ArrayList<>();
        while (!maxHeap.isEmpty() && feed.size() < 10) {
            int[] cur = maxHeap.poll();
            feed.add(cur[1]);                               // tweetId
            int u = cur[2], idx = cur[3];
            if (idx > 0) {                                  // push this user's next-older tweet
                int[] prev = tweets.get(u).get(idx - 1);
                maxHeap.offer(new int[]{prev[0], prev[1], u, idx - 1});
            }
        }
        return feed;
    }

    public void follow(int followerId, int followeeId) {
        following.computeIfAbsent(followerId, x -> new HashSet<>()).add(followeeId);
    }

    public void unfollow(int followerId, int followeeId) {
        Set<Integer> set = following.get(followerId);
        if (set != null) set.remove(followeeId);
    }
}
```

**Dry run** user1 posts 5; getNewsFeed(1)=[5]; user1 follows 2; user2 posts 6; getNewsFeed(1)=[6,5] (6 newer); user1 unfollows 2; getNewsFeed(1)=[5]. ✓

**Complexity** — `postTweet` `O(1)`; `getNewsFeed` `O(F + 10 log F)` with `F` followees; `follow`/`unfollow` `O(1)`. Space `O(total tweets + follow edges)`. **Edge cases:** a user always sees their own tweets (self added to `feedUsers`); following the same user twice is idempotent (a `Set`); `b[0] - a[0]` is safe since `time` grows from `0` and stays well within `int`; fewer than 10 total tweets returns however many exist.

---

### Problem 47: Construct Target Array With Multiple Sums — Max-heap run in reverse

**Statement.** You start with an array of `n` ones. In one operation you compute the sum `s` of all elements, pick any index `i`, and set `arr[i] = s` (i.e. replace one element with the total sum). Given `target`, return `true` if it is reachable from the all-ones array, else `false`.

**Constraints.** `1 ≤ target.length ≤ 5·10^4`; `1 ≤ target[i] ≤ 10^9`.

**Approach.** Forward search branches explosively, but the process is **deterministic in reverse**: the largest element was the one just written, and before that write it equaled `currentMax − (sum of the others) = currentMax − (totalSum − currentMax)`. So run it backward with a **max-heap**: pop the maximum, let `rest = totalSum − max`; the element's previous value was `max − rest` (computed efficiently as `max % rest` to collapse many identical subtractions when `rest` is small). Push that previous value back and update the sum. Succeed when every element becomes `1`. Fail if the max ever can't shrink (`max > 1` but `rest == 0` with more than one element, or the modulo produces `0` for a value that should be `1`).

```
max-heap of target; sum = total
loop while max > 1:
   rest = sum - max
   if rest == 1 -> reachable (rest of array is all ones)   // shortcut
   if rest == 0 or max % rest == 0 -> impossible
   prev = max % rest ; sum = rest + prev ; push prev
```

```java
import java.util.*;

class Solution {
    public boolean isPossible(int[] target) {
        PriorityQueue<Long> maxHeap = new PriorityQueue<>(Collections.reverseOrder());
        long sum = 0;
        for (int t : target) { maxHeap.offer((long) t); sum += t; }

        while (maxHeap.peek() > 1) {
            long max = maxHeap.poll();
            long rest = sum - max;
            if (rest == 0) return max == 1;     // single-element case: only [1] works
            if (rest == 1) return true;          // everything else is 1 -> always reachable
            long prev = max % rest;              // collapse repeated subtractions of `rest`
            if (prev == 0 || prev == max) return false; // can't decrease -> stuck
            sum = rest + prev;
            maxHeap.offer(prev);
        }
        return true;                             // all elements are 1
    }
}
```

**Dry run** `target=[9,3,5]`: sum17, max9, rest8 → prev=9%8=1, push1, sum9. max5, rest4 → 5%4=1, sum5. max3, rest2 → 3%2=1, sum3. max1 → done → `true`. For `[1,1,1,2]`: sum5, max2, rest3 → 2%3=2=max → `false`. ✓

**Complexity** — Time `O(n log n + log(maxVal)·log n)` (the modulo trick avoids per-unit subtraction), Space `O(n)`. **Edge cases:** single-element target must be exactly `[1]` (handled by the `rest == 0` branch); the `rest == 1` shortcut prevents an `O(maxVal)` slowdown when one element dominates; `prev == max` means the value can't shrink → unreachable; use `long` since sums of `10^9`-scale entries overflow `int`.

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
