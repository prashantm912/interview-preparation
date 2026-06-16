# Arrays & Strings

Arrays and strings are the bedrock of coding interviews. Master the handful of reusable techniques here — prefix sums, two pointers, sliding windows, in-place tricks — and you will recognize the underlying pattern in a large fraction of every FAANG screen, even when the problem is dressed up in a different story.

[← Back to master index](../README.md) | [← DSA index](README.md)

---

## Concept & Intuition

An **array** is a contiguous block of memory holding elements of the same type, indexed `0..n-1`. Random access (`a[i]`) is O(1) because the address is `base + i * elementSize`. A **string** in most languages is an array of characters (in Java, `String` is immutable and backed by a `char[]`/`byte[]`). That immutability matters: any "modification" of a Java `String` allocates a new object, so for heavy mutation you reach for `StringBuilder` or `char[]`.

The reason arrays dominate interviews is that their flat, ordered, O(1)-indexable structure unlocks a small set of *linear-time* techniques that replace naive O(n²) or O(n³) brute force:

- **Prefix sums** — precompute cumulative totals so any range sum becomes one subtraction. Invariant: `prefix[i] = a[0] + ... + a[i-1]`, hence `sum(l..r) = prefix[r+1] - prefix[l]`.
- **Two pointers** — keep two indices moving toward (or with) each other. Works when the array is sorted or when you compare/partition from both ends. Invariant: everything outside the `[lo, hi]` window is already decided.
- **Sliding window** — a two-pointer variant where a contiguous window `[left, right]` grows and shrinks while maintaining a property (sum ≤ k, all-distinct chars, etc.). Invariant: the window is always *valid* after the inner `while` shrinks it.
- **In-place mutation** — overwrite the input using O(1) extra space, often with a slow/fast writer index. Invariant: `[0, write)` holds the finished answer.
- **Kadane** — running max-subarray via the recurrence `best_ending_here = max(x, best_ending_here + x)`.

A tiny picture of the sliding window expanding then shrinking to stay valid:

```
 index:   0   1   2   3   4   5
 value:  [a   b   c   a   b   b]
          L           R            window "abca" -> dup 'a', shrink L
              L       R            window "bca"  -> valid
              L           R        window "bcab" -> dup 'b', shrink L
                  L       R        window "cab"  -> valid, len 3 (best)
```

When to use what:

| Symptom in the prompt | Reach for |
|---|---|
| "sum/average of every subarray of size k" | Sliding window (fixed) |
| "longest/shortest subarray with property X" | Sliding window (variable) |
| "many range-sum queries" | Prefix sums |
| "sorted array, find pair/triple" | Two pointers |
| "partition into groups in place" | Dutch flag / two-three pointers |
| "max sum of any contiguous subarray" | Kadane |

---

## Complexity Cheat-Sheet

| Operation | Time | Space | Notes |
|---|---|---|---|
| Access `a[i]` | O(1) | O(1) | Address arithmetic |
| Search (unsorted) | O(n) | O(1) | Linear scan |
| Search (sorted) | O(log n) | O(1) | Binary search |
| Insert/delete at end (dynamic array) | O(1) amortized | O(1) | Doubling reallocation |
| Insert/delete at middle | O(n) | O(1) | Shift elements |
| Build prefix-sum array | O(n) | O(n) | One pass |
| Range-sum query (with prefix) | O(1) | — | One subtraction |
| Two-pointer / sliding window scan | O(n) | O(1)–O(k) | Each pointer moves ≤ n |
| Kadane's max subarray | O(n) | O(1) | Single pass |
| Sort (then two-pointer) | O(n log n) | O(1)–O(n) | Comparison sort |
| KMP string match | O(n + m) | O(m) | `m` = pattern length |
| Rabin–Karp (avg) | O(n + m) | O(1) | O(nm) worst case on hash collisions |
| String concat in loop (Java `+`) | O(n²) | O(n²) | Use `StringBuilder` instead |

---

## Patterns & Recognition

- **Contiguous subarray/substring + "optimize a quantity"** → almost always sliding window or Kadane. If all elements are non-negative and you want a sum/length constraint, the window is monotonic and shrinking is safe.
- **"Without repeating", "at most k distinct", "all chars covered"** → variable sliding window with a `HashMap`/`int[]` frequency table.
- **Sorted input + "find pair summing to target" / "remove duplicates" / "k-th from each end"** → two pointers. Sorting first is a legitimate move (O(n log n)) when order doesn't matter.
- **"Range sum / range count queries", or any "subarray sum equals k"** → prefix sums, often combined with a `HashMap` of seen prefixes.
- **"Do it in O(1) extra space" / "modify the array in place"** → slow/fast writer pointers, or reversal tricks (rotate array).
- **"Three categories / colors / partition"** → Dutch national flag (3-way partition).
- **"Substring search / pattern occurs in text"** → KMP (deterministic linear) or Rabin–Karp (rolling hash, great for multiple patterns).
- **Counting/grouping anagrams** → sorted-key or 26-length frequency signature as a `HashMap` key.
- **"Product/sum except self", "water trapped", "stock profit"** → prefix/suffix aggregate arrays or two-pointer with running max.

Interview tell: if brute force is O(n²) by scanning all pairs/subarrays, ask *"can I avoid recomputation by carrying state as I move one pointer?"* — that question is the doorway to nearly every optimal array technique.

---

## Coding Problems

### Problem 1: Two Sum (Sorted) — Two Pointers

**Statement.** Given a sorted array `numbers` and a target, return the 1-based indices of the two numbers that add up to the target. Exactly one solution exists.
**Constraints.** `2 ≤ n ≤ 3·10⁴`, array sorted ascending, values fit in `int`.

**Approach.** Brute force checks all pairs in O(n²). Because the array is sorted, use two pointers from both ends: if the sum is too big, the right pointer must move left (only way to decrease); if too small, the left pointer moves right. Each step eliminates one candidate, giving O(n).

```java
public int[] twoSum(int[] numbers, int target) {
    int lo = 0, hi = numbers.length - 1;
    while (lo < hi) {
        int sum = numbers[lo] + numbers[hi];
        if (sum == target) return new int[]{lo + 1, hi + 1};
        if (sum < target) lo++;     // need a bigger sum
        else hi--;                  // need a smaller sum
    }
    return new int[]{-1, -1};       // unreachable per constraints
}
```

**Walkthrough.** `[2,7,11,15]`, target 9: `lo=0,hi=3` → 2+15=17>9 → `hi=2` → 2+11=13>9 → `hi=1` → 2+7=9 → return `[1,2]`.

**Time:** O(n). **Space:** O(1).
**Follow-ups.** Unsorted input (use a HashMap for O(n) time, O(n) space); return all pairs; 3Sum/4Sum (sort + fix one index + two pointers).

---

### Problem 2: Best Time to Buy and Sell Stock — Kadane Flavor

**Statement.** Given daily prices, maximize profit from one buy then one later sell. Return 0 if no profit possible.
**Constraints.** `1 ≤ n ≤ 10⁵`, `0 ≤ price ≤ 10⁴`.

**Approach.** Brute force tries every buy/sell pair: O(n²). Instead, track the minimum price seen so far; at each day the best sale today is `price - minSoFar`. One pass, O(n).

```java
public int maxProfit(int[] prices) {
    int minSoFar = Integer.MAX_VALUE, best = 0;
    for (int p : prices) {
        if (p < minSoFar) minSoFar = p;     // cheapest day to have bought
        else best = Math.max(best, p - minSoFar);
    }
    return best;
}
```

**Walkthrough.** `[7,1,5,3,6,4]`: min→7,1,1,1,1,1; profit candidates 0,0,4,2,5,3 → best 5 (buy at 1, sell at 6).

**Time:** O(n). **Space:** O(1).
**Follow-ups.** Unlimited transactions (sum all upward deltas); at most k transactions (DP); with cooldown / transaction fee (state-machine DP).

---

### Problem 3: Move Zeroes — In-Place Two Pointers

**Statement.** Move all `0`s to the end while keeping the relative order of non-zero elements. Do it in place.
**Constraints.** `1 ≤ n ≤ 10⁴`.

**Approach.** A `write` pointer marks where the next non-zero goes. Scan with `read`; every non-zero is written forward and `write` advances. Finally fill the tail with zeros. O(n), O(1).

```java
public void moveZeroes(int[] nums) {
    int write = 0;
    for (int read = 0; read < nums.length; read++) {
        if (nums[read] != 0) nums[write++] = nums[read];
    }
    while (write < nums.length) nums[write++] = 0;
}
```

**Walkthrough.** `[0,1,0,3,12]`: writes 1 then 3 then 12 → `[1,3,12,3,12]`, write=3; tail-fill → `[1,3,12,0,0]`.

**Time:** O(n). **Space:** O(1).
**Follow-ups.** Minimize total writes (swap only when needed); move zeros to front; remove a given value in place (LeetCode 27).

---

### Problem 4: Maximum Subarray (Kadane's Algorithm)

**Statement.** Find the contiguous subarray with the largest sum; return that sum.
**Constraints.** `1 ≤ n ≤ 10⁵`, values may be negative.

**Approach.** Brute force sums every subarray: O(n²). Kadane keeps `cur` = best sum *ending at the current index*. Either extend the previous run or restart at the current element: `cur = max(x, cur + x)`. Track the global max.

```java
public int maxSubArray(int[] nums) {
    int cur = nums[0], best = nums[0];
    for (int i = 1; i < nums.length; i++) {
        cur = Math.max(nums[i], cur + nums[i]);  // extend or restart
        best = Math.max(best, cur);
    }
    return best;
}
```

**Walkthrough.** `[-2,1,-3,4,-1,2,1,-5,4]`: cur evolves -2,1,-2,4,3,5,6,1,5; best = 6 (subarray `[4,-1,2,1]`).

**Time:** O(n). **Space:** O(1).
**Follow-ups.** Return the indices, not just the sum; maximum product subarray (track min too, because of negatives); circular maximum subarray; max sum with at most one deletion.

---

### Problem 5: Longest Substring Without Repeating Characters — Sliding Window

**Statement.** Return the length of the longest substring with all distinct characters.
**Constraints.** `0 ≤ n ≤ 5·10⁴`, any ASCII/Unicode chars.

**Approach.** Brute force checks every substring: O(n²) or worse. Use a variable window `[left, right]` and a map of `char → last index`. When the current char was seen *inside* the window, jump `left` past its previous position. The window always stays duplicate-free.

```java
public int lengthOfLongestSubstring(String s) {
    Map<Character, Integer> last = new HashMap<>();
    int left = 0, best = 0;
    for (int right = 0; right < s.length(); right++) {
        char c = s.charAt(right);
        if (last.containsKey(c) && last.get(c) >= left) {
            left = last.get(c) + 1;             // shrink past the duplicate
        }
        last.put(c, right);
        best = Math.max(best, right - left + 1);
    }
    return best;
}
```

**Walkthrough.** `"abcabcbb"`: window grows `a,ab,abc`; at second `a` left jumps to 1 → `bca`; at second `b` left→2 → `cab`; best length stays 3.

**Time:** O(n). **Space:** O(min(n, charset)).
**Follow-ups.** Longest substring with **at most k distinct** chars; longest with **at most two** distinct; return the substring itself; case-insensitive variant.

---

### Problem 6: Valid Anagram & Group Anagrams — Frequency Signatures

**Statement.** (a) Decide if `t` is an anagram of `s`. (b) Group a list of strings into anagram clusters.
**Constraints.** Lowercase English letters; up to 10⁴ words for grouping.

**Approach.** Two strings are anagrams iff their character counts match. For a single check, a 26-length count array suffices in O(n). For grouping, build a canonical key per word — either the sorted characters, or a count signature — and bucket by key in a `HashMap`.

```java
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

public List<List<String>> groupAnagrams(String[] strs) {
    Map<String, List<String>> groups = new HashMap<>();
    for (String w : strs) {
        char[] key = w.toCharArray();
        Arrays.sort(key);                          // canonical form
        groups.computeIfAbsent(new String(key), k -> new ArrayList<>()).add(w);
    }
    return new ArrayList<>(groups.values());
}
```

**Walkthrough.** `["eat","tea","tan","ate","nat","bat"]`: keys `aet,aet,ant,aet,ant,abt` → `{aet:[eat,tea,ate], ant:[tan,nat], abt:[bat]}`.

**Time:** anagram check O(n); grouping O(N·L log L) with sorting (L = word length), or O(N·L) using a count-signature key. **Space:** O(N·L).
**Follow-ups.** Unicode input (sort code points); use the 26-count string `"a1b0c2..."` as key to drop the sort; find all anagrams of a pattern in a text (sliding window of counts).

---

### Problem 7: Product of Array Except Self — Prefix/Suffix Products

**Statement.** Return `out[i] = product of all elements except `nums[i]``, **without using division** and in O(n).
**Constraints.** `2 ≤ n ≤ 10⁵`; the full product fits in a 32-bit int; zeros allowed.

**Approach.** Division is banned (and breaks on zeros). For each index the answer is `(product of everything to the left) × (product of everything to the right)`. Compute left products in one forward pass, then fold in right products in a backward pass, reusing the output array so only O(1) extra space is used.

```java
public int[] productExceptSelf(int[] nums) {
    int n = nums.length;
    int[] out = new int[n];
    out[0] = 1;
    for (int i = 1; i < n; i++) out[i] = out[i - 1] * nums[i - 1]; // prefix
    int right = 1;
    for (int i = n - 1; i >= 0; i--) {
        out[i] *= right;          // multiply by suffix product
        right *= nums[i];
    }
    return out;
}
```

**Walkthrough.** `[1,2,3,4]`: prefix → `[1,1,2,6]`; backward with `right` = 1,4,12,24 → `[24,12,8,6]`.

**Time:** O(n). **Space:** O(1) extra (output array doesn't count).
**Follow-ups.** Handle one/two zeros explicitly; division-allowed one-liner with zero handling; sum-except-self; range-product queries.

---

### Problem 8: Sort Colors — Dutch National Flag

**Statement.** Sort an array of `0,1,2` in place in a single pass (no counting sort two-pass).
**Constraints.** `1 ≤ n ≤ 300`, values ∈ {0,1,2}.

**Approach.** Three pointers partition the array into `<low` zeros, the middle ones, and `>high` twos. `mid` scans; a `0` swaps to the `low` boundary, a `2` swaps to the `high` boundary (and we *don't* advance `mid` because the swapped-in value is unexamined), a `1` just advances. Invariant: `[0,low)`=0s, `[low,mid)`=1s, `(high,n)`=2s.

```java
public void sortColors(int[] nums) {
    int low = 0, mid = 0, high = nums.length - 1;
    while (mid <= high) {
        if (nums[mid] == 0) {
            swap(nums, low++, mid++);
        } else if (nums[mid] == 1) {
            mid++;
        } else {                    // nums[mid] == 2
            swap(nums, mid, high--); // re-examine swapped-in value
        }
    }
}
private void swap(int[] a, int i, int j) { int t = a[i]; a[i] = a[j]; a[j] = t; }
```

**Walkthrough.** `[2,0,2,1,1,0]`: swap(0,5)→`[0,0,2,1,1,2]`(high=4); 0→swap & advance →`[0,0,...]`(low=mid=2); 2→swap(2,4)→`[0,0,1,1,2,2]`(high=3); 1,1 advance; done.

**Time:** O(n). **Space:** O(1).
**Follow-ups.** Generalize to k colors (counting sort O(n+k)); partition around a pivot (quicksort core); flag with arbitrary comparator.

---

### Problem 9: Merge Intervals — Sort + Sweep

**Statement.** Given a list of intervals `[start, end]`, merge all overlapping intervals.
**Constraints.** `1 ≤ n ≤ 10⁴`.

**Approach.** Brute-force pairwise merging is O(n²). Sort by start; then sweep once, extending the last merged interval whenever the current start is ≤ the last end, otherwise opening a new interval. Sorting makes overlaps adjacent.

```java
public int[][] merge(int[][] intervals) {
    Arrays.sort(intervals, (a, b) -> Integer.compare(a[0], b[0]));
    List<int[]> merged = new ArrayList<>();
    for (int[] cur : intervals) {
        if (merged.isEmpty() || merged.get(merged.size() - 1)[1] < cur[0]) {
            merged.add(cur);                                   // no overlap
        } else {
            merged.get(merged.size() - 1)[1] =
                Math.max(merged.get(merged.size() - 1)[1], cur[1]); // extend
        }
    }
    return merged.toArray(new int[merged.size()][]);
}
```

**Walkthrough.** `[[1,3],[2,6],[8,10],[15,18]]` → sorted same → merge `[1,3]`&`[2,6]`→`[1,6]`; `[8,10]` disjoint; `[15,18]` disjoint → `[[1,6],[8,10],[15,18]]`.

**Time:** O(n log n). **Space:** O(n) for output (O(log n)–O(n) sort overhead).
**Follow-ups.** Insert one interval into a sorted non-overlapping list (LeetCode 57); interval intersection of two lists; minimum meeting rooms (sweep with a min-heap); employee free time.

---

### Problem 10: Rotate Array by k — Reversal Trick (In Place)

**Statement.** Rotate the array to the right by `k` steps, in place, using O(1) extra space.
**Constraints.** `1 ≤ n ≤ 10⁵`, `0 ≤ k ≤ 10⁹`.

**Approach.** The clean O(1)-space trick: reverse the whole array, then reverse the first `k` and the remaining `n-k`. Each reversal flips order; doing all three lands every element in its rotated slot. Don't forget `k %= n`.

```java
public void rotate(int[] nums, int k) {
    int n = nums.length;
    k %= n;
    reverse(nums, 0, n - 1);
    reverse(nums, 0, k - 1);
    reverse(nums, k, n - 1);
}
private void reverse(int[] a, int i, int j) {
    while (i < j) { int t = a[i]; a[i++] = a[j]; a[j--] = t; }
}
```

**Walkthrough.** `[1,2,3,4,5,6,7]`, k=3: reverse all → `[7,6,5,4,3,2,1]`; reverse first 3 → `[5,6,7,4,3,2,1]`; reverse rest → `[5,6,7,1,2,3,4]`.

**Time:** O(n). **Space:** O(1).
**Follow-ups.** Rotate left by k (reverse halves the other way); cyclic-replacement method using GCD cycles; rotate a matrix 90°.

---

### Problem 11: Trapping Rain Water — Two Pointers (Senior-Level)

**Statement.** Given non-negative bar heights of unit width, compute how much rain water is trapped.
**Constraints.** `0 ≤ n ≤ 2·10⁴`, `0 ≤ height ≤ 10⁵`.

**Approach.** Water above bar `i` is `min(maxLeft, maxRight) − height[i]`. The O(n)-space version precomputes left/right max arrays. The optimal O(1)-space version uses two pointers: whichever side has the smaller running max is the side whose water is *fully determined* (the other side guarantees a taller wall), so we settle that bar and advance inward.

```java
public int trap(int[] height) {
    int l = 0, r = height.length - 1;
    int leftMax = 0, rightMax = 0, water = 0;
    while (l < r) {
        if (height[l] < height[r]) {
            leftMax = Math.max(leftMax, height[l]);
            water += leftMax - height[l];   // right wall guaranteed taller
            l++;
        } else {
            rightMax = Math.max(rightMax, height[r]);
            water += rightMax - height[r];
            r--;
        }
    }
    return water;
}
```

**Walkthrough.** `[0,1,0,2,1,0,1,3,2,1,2,1]` → 6 units. E.g. the dip at index 2 (height 0) traps `min(1,3)-0 = 1`; the trough at indices 4–6 traps 1+2+1, etc., summing to 6.

**Time:** O(n). **Space:** O(1).
**Follow-ups.** Trapping rain water II (2-D grid, min-heap from the border); container with most water (different objective — maximize area between two lines); histogram largest rectangle (monotonic stack).

---

### Problem 12: Implement strStr() with KMP (and Rabin–Karp) — String Matching (Senior-Level)

**Statement.** Find the first index where pattern `needle` occurs in text `haystack`, or `-1`.
**Constraints.** `1 ≤ |needle| ≤ |haystack| ≤ 10⁴`+, lowercase letters.

**Approach.** Naive matching re-scans on every mismatch: O(n·m). **KMP** precomputes a *longest-proper-prefix-that-is-also-suffix* (LPS / failure) table so that on a mismatch we shift the pattern by as much as the matched prefix allows — never re-reading text. Total O(n + m). **Rabin–Karp** slides a rolling hash over the text and only does a character check when hashes match — O(n + m) average, ideal for searching many patterns at once.

```java
// ---- KMP ----
public int strStr(String haystack, String needle) {
    int n = haystack.length(), m = needle.length();
    int[] lps = buildLps(needle);
    int i = 0, j = 0;                       // i over text, j over pattern
    while (i < n) {
        if (haystack.charAt(i) == needle.charAt(j)) {
            i++; j++;
            if (j == m) return i - m;       // full match
        } else if (j > 0) {
            j = lps[j - 1];                 // fall back, don't move i
        } else {
            i++;
        }
    }
    return -1;
}
private int[] buildLps(String p) {
    int[] lps = new int[p.length()];
    int len = 0;
    for (int i = 1; i < p.length(); ) {
        if (p.charAt(i) == p.charAt(len)) lps[i++] = ++len;
        else if (len > 0) len = lps[len - 1];
        else lps[i++] = 0;
    }
    return lps;
}

// ---- Rabin–Karp (rolling hash) ----
public int strStrRK(String text, String pat) {
    int n = text.length(), m = pat.length();
    if (m == 0) return 0;
    if (m > n) return -1;
    long base = 256, mod = 1_000_000_007L;
    long high = 1, ph = 0, th = 0;
    for (int i = 0; i < m - 1; i++) high = (high * base) % mod;
    for (int i = 0; i < m; i++) {
        ph = (ph * base + pat.charAt(i)) % mod;
        th = (th * base + text.charAt(i)) % mod;
    }
    for (int i = 0; i + m <= n; i++) {
        if (ph == th && text.substring(i, i + m).equals(pat)) return i; // verify
        if (i + m < n) {
            th = (th - text.charAt(i) * high % mod + mod) % mod; // drop left char
            th = (th * base + text.charAt(i + m)) % mod;          // add right char
        }
    }
    return -1;
}
```

**Walkthrough (KMP).** Pattern `"aabaa"` → LPS `[0,1,0,1,2]`. Matching against `"aabaacaabaa"`: on the mismatch at the `c`, instead of restarting we use `lps` to keep the already-matched `"aa"` prefix, so the text pointer never backs up. Match found at index 6.

**Time:** KMP O(n + m); Rabin–Karp O(n + m) average, O(n·m) worst case (adversarial hash collisions). **Space:** KMP O(m); RK O(1).
**Follow-ups.** Count all occurrences (don't stop at first); search multiple patterns simultaneously (Aho–Corasick / multi-pattern RK); shortest palindrome via KMP on `s + "#" + reverse(s)`; repeated-substring-pattern detection using the LPS table.

---

## Interview Q&A by Level

### 🟢 Basic

**Q: Why is array access O(1)?**
Because elements are stored contiguously; the address of `a[i]` is computed directly as `base + i × elementSize`, with no traversal.

**Q: Difference between an array and a Java `ArrayList`?**
An array has a fixed length and can hold primitives; `ArrayList` is a resizable wrapper around an `Object[]` that grows by reallocation (amortized O(1) append) and only stores objects (autoboxing for primitives).

**Q: Why is Java `String` immutable, and what's the consequence in loops?**
Immutability enables safe sharing, hashing, and string-pool interning. The consequence: building a string with `+=` in a loop is O(n²) because each concatenation copies. Use `StringBuilder` for O(n).

**Q: What's the time to insert at the front of an array?**
O(n) — every existing element must shift right by one.

### 🟡 Intermediate

**Q: Explain the amortized O(1) for dynamic-array append.**
When the backing array is full, capacity doubles and all elements are copied (O(n) for that one op). But doublings are rare; across `n` appends the total copy work is `n + n/2 + n/4 + ... < 2n`, so the *average* per append is O(1) — this is amortized (aggregate) analysis.

**Q: Fixed vs. variable sliding window — how do you tell them apart?**
Fixed window: the size `k` is given, you slide a constant-width window (add the entering element, remove the leaving one). Variable window: you expand `right` greedily and shrink `left` only while a constraint is violated; the window size floats to satisfy "longest/shortest with property X".

**Q: When does sorting first actually help, despite its O(n log n) cost?**
When the problem is order-insensitive and sorting unlocks a linear technique: two pointers for pair sums, adjacency for duplicates/merging intervals, or grouping anagrams. The O(n log n) sort is dominated by the savings over O(n²) brute force.

**Q: Why does the two-pointer trapping-rain-water solution settle the smaller side?**
If `height[l] < height[r]`, then *some* wall on the right is at least `height[r] > leftMax-candidate`, so the water over the left bar is bounded solely by `leftMax`. That side is fully determined and safe to finalize.

### 🟠 Advanced

**Q: Why is KMP O(n + m) and not O(n·m)?**
The text pointer `i` never decreases. On a mismatch we only move the pattern pointer `j` backward via the LPS table; across the whole run `j` increases at most `n` times and decreases at most that many, so total work is linear. The LPS build is itself O(m) by the same amortized argument.

**Q: What is the LPS / failure function conceptually?**
`lps[i]` = length of the longest proper prefix of `pattern[0..i]` that is also a suffix of it. It tells you, after matching `i+1` characters and then hitting a mismatch, how many characters you can keep without rechecking the text.

**Q: When is Rabin–Karp preferable to KMP?**
When searching for **many** patterns of the same length (precompute all pattern hashes and compare each window once), for 2-D pattern matching, or for plagiarism/dedup via rolling hashes. Its weakness is the O(n·m) worst case under hash collisions, mitigated by a good modulus / double hashing.

**Q: How would you find a subarray summing to exactly k with negatives present?**
Sliding window fails with negatives (sum isn't monotonic). Use prefix sums plus a `HashMap` of `prefixSum → earliest index/count`: a subarray `(j, i]` sums to `k` iff `prefix[i] − prefix[j] = k`, so look up `prefix[i] − k`. O(n).

### 🔴 Expert

**Q: Real-world systems use of these techniques?**
Prefix sums power range-query analytics and 2-D image integral-images for fast box filters. Rolling hashes underpin rsync's block deduplication, content-defined chunking, and plagiarism detectors. Sliding windows are the basis of rate limiters and streaming aggregations (e.g., "requests in the last 60s"). Merge-intervals logic shows up in calendar scheduling and genomic range merging.

**Q: How do you scale "top-k frequent / heavy hitters" when the array doesn't fit in memory?**
Single machine: count with a HashMap then a size-k min-heap (O(n log k)), or QuickSelect for O(n) average. Streaming / unbounded: approximate with Count–Min Sketch + a heap, or the Misra–Gries (Space-Saving) algorithm, trading exactness for sublinear space. Distributed: map-reduce partial counts then merge.

**Q: Cache behavior — why can an O(n) array scan beat an O(n) linked-list scan in practice?**
Arrays are contiguous, so sequential access is cache-line-friendly and prefetchable; a linked list chases pointers across scattered addresses, causing cache misses. Big-O hides the constant factor that memory locality dominates on modern hardware.

**Q: How do you make Rabin–Karp robust against adversarial collisions?**
Use a large random prime modulus chosen at runtime (so an attacker can't precompute collisions) and/or double hashing with two independent (base, mod) pairs; only do the expensive character-by-character verification when both hashes agree.

---

## ⚠️ Common Pitfalls

- **Off-by-one in windows/prefix sums.** Decide once whether ranges are inclusive or exclusive and keep `prefix` length `n+1` with `prefix[0]=0` to avoid special-casing the empty prefix.
- **Forgetting `k %= n` in rotation** — large `k` causes index-out-of-bounds or pointless full rotations.
- **Integer overflow** — range sums, products (`product except self`), and Rabin–Karp hashes can overflow `int`; use `long` and modular arithmetic. Also `(lo + hi) / 2` overflows; use `lo + (hi - lo) / 2`.
- **Mutating a Java `String` in a loop with `+`** — quadratic; switch to `StringBuilder`.
- **Sliding window with negative numbers** — shrinking on a sum threshold is invalid because the sum isn't monotonic; switch to prefix-sum + HashMap.
- **Dutch flag: advancing `mid` after a `2`-swap** — you must re-examine the swapped-in element, so do *not* increment `mid` in that branch.
- **HashMap "seen index" staleness in longest-substring** — only treat a repeat as a duplicate if its stored index is `≥ left`; otherwise it's outside the current window.
- **Comparing `String` with `==`** in Java compares references; use `.equals()` (this bites Rabin–Karp verification).
- **Two pointers on unsorted data** — the technique assumes sortedness for pair-sum problems; sort first or use a hash map.

---

## 📚 Further Reading

- *Cracking the Coding Interview*, Gayle Laakmann McDowell — Arrays & Strings chapter.
- *Introduction to Algorithms* (CLRS) — string matching (KMP, Rabin–Karp), amortized analysis.
- *Elements of Programming Interviews in Java* — arrays, strings, and primitive techniques.
- *Algorithm Design Manual*, Steven Skiena — practical pattern catalog.
- LeetCode tag tracks: **Two Pointers**, **Sliding Window**, **Prefix Sum**, **String** — work the Top-Interview-150 list.
- Sedgewick & Wayne, *Algorithms (4th ed.)* — substring search chapter with KMP, Boyer–Moore, Rabin–Karp.

[← Back to master index](../README.md) | [← DSA index](README.md)
