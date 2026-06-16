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

## 🧩 Extended Problems — Set 1: Classic Easy → Medium

### Problem 13: Two Sum (Unsorted) — HashMap Complement

**Statement.** Given an array `nums` and a `target`, return the indices of the two numbers that add up to `target`. Exactly one solution exists and you may not use the same element twice.
**Constraints.** `2 ≤ n ≤ 10⁴`, `-10⁹ ≤ nums[i], target ≤ 10⁹`, exactly one valid answer.

**Approach.** Brute force checks all pairs in O(n²). Since the array is unsorted, two pointers would require an O(n log n) sort that also destroys original indices. Instead make one pass with a `HashMap` from value → index. For each element `x`, the partner we need is `target - x`; if that complement is already in the map we have our pair. This is optimal at O(n) time because each lookup/insert is O(1) average, and it preserves the original indices.

```java
public int[] twoSum(int[] nums, int target) {
    Map<Integer, Integer> seen = new HashMap<>();   // value -> index
    for (int i = 0; i < nums.length; i++) {
        int need = target - nums[i];
        if (seen.containsKey(need)) {
            return new int[]{seen.get(need), i};
        }
        seen.put(nums[i], i);                       // store AFTER checking
    }
    return new int[]{-1, -1};                       // unreachable per constraints
}
```

**Walkthrough.** `[2,7,11,15]`, target 9: i=0 need 7 (not seen) → store {2:0}; i=1 need 2 (seen at 0) → return `[0,1]`.

**Complexity.** Time O(n), Space O(n). **Edge cases:** duplicate values like `[3,3]` target 6 work because we check the map *before* inserting the current element; negatives and large values fit because we key on the value itself.

---

### Problem 14: Valid Palindrome — Two Pointers with Filtering

**Statement.** Given a string `s`, return `true` if it is a palindrome considering only alphanumeric characters and ignoring case.
**Constraints.** `1 ≤ |s| ≤ 2·10⁵`, `s` is printable ASCII.

**Approach.** Building a cleaned, lowercased copy and comparing it to its reverse is O(n) time but O(n) extra space. The optimal in-place approach uses two pointers from both ends that *skip* non-alphanumeric characters and compare case-folded letters. Pointers converge in a single pass, so it is O(n) time and O(1) space.

```
"A man, a plan, a canal: Panama"
 ^                            ^      compare 'a' == 'a' (case-folded), advance
   ^                        ^        skip spaces/punct, compare 'm' == 'm'
        ... pointers meet in the middle -> palindrome
```

```java
public boolean isPalindrome(String s) {
    int i = 0, j = s.length() - 1;
    while (i < j) {
        while (i < j && !Character.isLetterOrDigit(s.charAt(i))) i++;
        while (i < j && !Character.isLetterOrDigit(s.charAt(j))) j--;
        if (Character.toLowerCase(s.charAt(i)) != Character.toLowerCase(s.charAt(j))) {
            return false;
        }
        i++; j--;
    }
    return true;
}
```

**Walkthrough.** `"A man, a plan, a canal: Panama"` → ignoring case/punctuation reads `amanaplanacanalpanama`, symmetric → `true`. `"race a car"` → `raceacar`, mismatch `r` vs `r`... actually `e` vs `c` fails → `false`.

**Complexity.** Time O(n), Space O(1). **Edge cases:** empty string and strings with only punctuation (e.g. `",.")`) are vacuously palindromes; single character is a palindrome; the inner `while (i < j)` guards prevent the skip loops from crossing.

---

### Problem 15: Contains Duplicate — HashSet Membership

**Statement.** Return `true` if any value appears at least twice in the array, `false` if every element is distinct.
**Constraints.** `1 ≤ n ≤ 10⁵`, `-10⁹ ≤ nums[i] ≤ 10⁹`.

**Approach.** Sorting and scanning adjacent pairs is O(n log n). The optimal approach streams the array into a `HashSet`; the moment `add` returns `false` (element already present) we have found a duplicate and return early. Average O(n) time, O(n) space — trading memory for speed.

```java
public boolean containsDuplicate(int[] nums) {
    Set<Integer> seen = new HashSet<>();
    for (int x : nums) {
        if (!seen.add(x)) return true;   // add() returns false if already present
    }
    return false;
}
```

**Walkthrough.** `[1,2,3,1]`: add 1,2,3 succeed; add 1 returns false → `true`. `[1,2,3,4]`: all adds succeed → `false`.

**Complexity.** Time O(n) average, Space O(n). **Edge cases:** single-element array returns `false`; if memory is constrained, the O(n log n) sort-then-scan variant uses O(1) extra space.

---

### Problem 16: Maximum Average Subarray I — Fixed Sliding Window

**Statement.** Find the contiguous subarray of length `k` with the maximum average and return that average value.
**Constraints.** `1 ≤ k ≤ n ≤ 10⁵`, `-10⁴ ≤ nums[i] ≤ 10⁴`.

**Approach.** Recomputing each window's sum is O(n·k). A fixed-size sliding window keeps a running `sum`: seed it with the first `k` elements, then slide by adding the entering element and subtracting the leaving one — O(1) per step. Maximizing the sum is equivalent to maximizing the average (divide by `k` at the end), and we use a `double` only for the final division to avoid precision loss during accumulation.

```java
public double findMaxAverage(int[] nums, int k) {
    long sum = 0;
    for (int i = 0; i < k; i++) sum += nums[i];   // first window
    long best = sum;
    for (int i = k; i < nums.length; i++) {
        sum += nums[i] - nums[i - k];             // slide: add new, drop old
        best = Math.max(best, sum);
    }
    return (double) best / k;
}
```

**Walkthrough.** `[1,12,-5,-6,50,3]`, k=4: first window sum 2; slide → 51 → 42; best 51 → average 12.75.

**Complexity.** Time O(n), Space O(1). **Edge cases:** `k == n` returns the average of the whole array; `long` accumulator prevents overflow when `n·max` exceeds `int`; negative values are handled because we compare sums directly.

---

### Problem 17: Plus One — Array Digit Arithmetic with Carry

**Statement.** Given a non-negative integer represented as an array of decimal digits (most significant first), add one and return the resulting digit array.
**Constraints.** `1 ≤ n ≤ 100`, each digit ∈ `0..9`, no leading zeros (except the number `0` itself).

**Approach.** Converting to an integer can overflow for large `n`, so we simulate grade-school addition directly on the array. Walk from the least significant digit: if a digit is less than 9, increment it and return immediately (no carry propagates). If it is 9, set it to 0 and continue the carry left. If we fall off the front, every digit was 9 (like `999`), so the answer is one digit longer with a leading `1`.

```java
public int[] plusOne(int[] digits) {
    for (int i = digits.length - 1; i >= 0; i--) {
        if (digits[i] < 9) {
            digits[i]++;
            return digits;          // no carry, done
        }
        digits[i] = 0;              // 9 -> 0, carry continues
    }
    int[] result = new int[digits.length + 1];
    result[0] = 1;                  // e.g. 999 -> 1000
    return result;                  // rest default to 0
}
```

**Walkthrough.** `[1,2,3]` → last digit 3<9 → `[1,2,4]`. `[9,9,9]` → all become 0, fall off front → `[1,0,0,0]`.

**Complexity.** Time O(n), Space O(1) for the common case, O(n) only on the all-nines carry. **Edge cases:** `[0]` → `[1]`; the all-nines case is the only one that grows the array; single-digit inputs handled by the loop.

---

### Problem 18: Merge Sorted Array — In-Place Merge from the Back

**Statement.** Merge `nums2` (length `n`) into `nums1` (length `m+n`, with the first `m` slots filled and the last `n` zeroed) so `nums1` becomes one sorted array, in place.
**Constraints.** `0 ≤ m, n ≤ 200`, both inputs already sorted ascending.

**Approach.** Merging from the front would overwrite unprocessed `nums1` elements. The trick is to fill from the **back**: a write pointer starts at index `m+n-1`, and we compare the largest remaining elements of each array, placing the bigger one at the write position and stepping that pointer down. Because we always write to a slot at or beyond both read pointers, no unread data is clobbered. O(m+n) time, O(1) space.

```
nums1 = [1,2,3,0,0,0]  m=3      nums2 = [2,5,6]  n=3
         i=2      w=5            j=2
 compare 3 vs 6 -> write 6 at w=5, j stays... j-- to 1, w=4
 compare 3 vs 5 -> write 5 at w=4, j=0,  w=3
 compare 3 vs 2 -> write 3 at w=3, i=1,  w=2 ... etc.
```

```java
public void merge(int[] nums1, int m, int[] nums2, int n) {
    int i = m - 1, j = n - 1, w = m + n - 1;
    while (j >= 0) {                       // once nums2 exhausted, nums1 already in place
        if (i >= 0 && nums1[i] > nums2[j]) {
            nums1[w--] = nums1[i--];
        } else {
            nums1[w--] = nums2[j--];
        }
    }
}
```

**Walkthrough.** `nums1=[1,2,3,0,0,0]`, `nums2=[2,5,6]` → writes 6,5,3,2,2,1 from the back → `[1,2,2,3,5,6]`.

**Complexity.** Time O(m+n), Space O(1). **Edge cases:** `n == 0` (loop never runs, `nums1` unchanged); `m == 0` (every slot filled from `nums2`); the `i >= 0` guard handles `nums1` running out before `nums2`.

---

### Problem 19: Find All Anagrams in a String — Sliding Window of Counts

**Statement.** Given strings `s` and `p`, return the start indices of all substrings of `s` that are anagrams of `p`.
**Constraints.** `1 ≤ |s|, |p| ≤ 3·10⁴`, lowercase English letters.

**Approach.** Checking each window by sorting is O(n·k log k). Instead keep a fixed window of width `|p|` and two 26-length frequency arrays. Slide the window one character at a time: increment the entering char's count and decrement the leaving char's count. A window is an anagram exactly when its count vector equals `p`'s. Comparing two 26-arrays is O(26) = O(1), giving overall O(n).

```java
public List<Integer> findAnagrams(String s, String p) {
    List<Integer> res = new ArrayList<>();
    int n = s.length(), k = p.length();
    if (n < k) return res;
    int[] need = new int[26], win = new int[26];
    for (int i = 0; i < k; i++) {
        need[p.charAt(i) - 'a']++;
        win[s.charAt(i) - 'a']++;
    }
    if (Arrays.equals(need, win)) res.add(0);
    for (int i = k; i < n; i++) {
        win[s.charAt(i) - 'a']++;          // add entering char
        win[s.charAt(i - k) - 'a']--;      // remove leaving char
        if (Arrays.equals(need, win)) res.add(i - k + 1);
    }
    return res;
}
```

**Walkthrough.** `s="cbaebabacd"`, `p="abc"`: window `cba` (idx 0) matches; later `bac` (idx 6) matches → `[0,6]`.

**Complexity.** Time O(n) (each slide plus an O(26) compare), Space O(1) (two fixed 26-arrays). **Edge cases:** `|s| < |p|` returns empty; the initial window must be checked before the slide loop or index 0 matches are missed.

---

### Problem 20: Longest Common Prefix — Vertical Scanning

**Statement.** Find the longest common prefix string among an array of strings; return `""` if there is none.
**Constraints.** `1 ≤ n ≤ 200`, `0 ≤ |strs[i]| ≤ 200`, lowercase English letters.

**Approach.** Vertical scanning compares characters column by column across all strings simultaneously. At column `j`, take the character from the first string and verify every other string has the same character at `j`; the first mismatch (or any string shorter than `j`) ends the prefix. This short-circuits early and never builds intermediate strings, running in O(S) where S is the total number of characters — optimal since you must at least read the answer's characters.

```java
public String longestCommonPrefix(String[] strs) {
    if (strs.length == 0) return "";
    for (int j = 0; j < strs[0].length(); j++) {
        char c = strs[0].charAt(j);
        for (int i = 1; i < strs.length; i++) {
            if (j >= strs[i].length() || strs[i].charAt(j) != c) {
                return strs[0].substring(0, j);
            }
        }
    }
    return strs[0];                         // first string is the prefix
}
```

**Walkthrough.** `["flower","flow","flight"]`: column 0 `f`=f=f, col 1 `l`=l=l, col 2 `o`≠`i` → return `"fl"`.

**Complexity.** Time O(S) where S = sum of all lengths (worst case), Space O(1) excluding output. **Edge cases:** an empty string in the array forces an immediate `""`; a single string returns itself; identical strings return the full string.

---

### Problem 21: Remove Duplicates from Sorted Array — Slow/Fast Pointers

**Statement.** Given a sorted array, remove duplicates in place so each element appears once, and return the new length `k`; the first `k` slots must hold the unique values in order.
**Constraints.** `1 ≤ n ≤ 3·10⁴`, sorted ascending.

**Approach.** Because the array is sorted, equal values are adjacent. A `slow` pointer marks the end of the deduplicated region; a `fast` pointer scans ahead. Whenever `nums[fast]` differs from the last kept value `nums[slow]`, we advance `slow` and copy the new value there. This overwrites in place with O(1) extra space and a single O(n) pass.

```java
public int removeDuplicates(int[] nums) {
    if (nums.length == 0) return 0;
    int slow = 0;
    for (int fast = 1; fast < nums.length; fast++) {
        if (nums[fast] != nums[slow]) {
            nums[++slow] = nums[fast];      // keep the new distinct value
        }
    }
    return slow + 1;                        // count = last index + 1
}
```

**Walkthrough.** `[0,0,1,1,1,2,2,3,3,4]`: kept values land at indices 0..4 as `0,1,2,3,4` → returns 5.

**Complexity.** Time O(n), Space O(1). **Edge cases:** all-equal array returns 1; already-distinct array returns `n` with no copies needed; the `length == 0` guard avoids touching `nums[0]`. Follow-up "allow at most two duplicates" compares against `nums[slow-1]` instead.

---

### Problem 22: Reverse String / Reverse Words — Two Pointers In Place

**Statement.** (a) Reverse a `char[]` in place. (b) Reverse the order of words in a sentence, collapsing extra spaces.
**Constraints.** (a) `1 ≤ n ≤ 10⁵`. (b) `1 ≤ |s| ≤ 10⁴`, words separated by one or more spaces, possible leading/trailing spaces.

**Approach.** (a) Swap the outermost pair and walk both pointers inward until they meet — O(n) time, O(1) space, the canonical two-pointer reversal. (b) The classic interview answer trims, splits on whitespace, and reassembles in reverse; a more in-place flavor reverses the whole char array then reverses each word back, but in Java the split-and-join version is idiomatic and clear. We show both routines.

```java
public void reverseString(char[] s) {
    int i = 0, j = s.length - 1;
    while (i < j) {
        char t = s[i]; s[i++] = s[j]; s[j--] = t;
    }
}

public String reverseWords(String s) {
    String[] parts = s.trim().split("\\s+");   // collapse runs of spaces
    StringBuilder sb = new StringBuilder();
    for (int i = parts.length - 1; i >= 0; i--) {
        sb.append(parts[i]);
        if (i > 0) sb.append(' ');
    }
    return sb.toString();
}
```

**Walkthrough.** (a) `['h','e','l','l','o']` → `['o','l','l','e','h']`. (b) `"  the sky  is blue "` → trim/split → `[the, sky, is, blue]` → `"blue is sky the"`.

**Complexity.** (a) Time O(n), Space O(1). (b) Time O(n), Space O(n) for the split parts and builder. **Edge cases:** single word returns itself; multiple/leading/trailing spaces collapse to single separators; `StringBuilder` avoids the O(n²) trap of `+=` concatenation.

---

### Problem 23: Subarray Sum Equals K — Prefix Sum + HashMap

**Statement.** Count the number of contiguous subarrays whose elements sum to exactly `k`. Values may be negative.
**Constraints.** `1 ≤ n ≤ 2·10⁴`, `-1000 ≤ nums[i] ≤ 1000`, `-10⁷ ≤ k ≤ 10⁷`.

**Approach.** A sliding window fails because negative numbers make the running sum non-monotonic, so shrinking is unsound. Instead use prefix sums: a subarray `(j, i]` sums to `k` iff `prefix[i] - prefix[j] = k`, i.e. `prefix[j] = prefix[i] - k`. Sweep once, maintaining a `HashMap` of how many times each prefix sum has occurred; at each index add the count of `prefix - k` already seen. Seed the map with `{0:1}` to count subarrays starting at index 0. O(n) time.

```java
public int subarraySum(int[] nums, int k) {
    Map<Integer, Integer> count = new HashMap<>();
    count.put(0, 1);                       // empty prefix
    int prefix = 0, ans = 0;
    for (int x : nums) {
        prefix += x;
        ans += count.getOrDefault(prefix - k, 0);   // subarrays ending here
        count.merge(prefix, 1, Integer::sum);
    }
    return ans;
}
```

**Walkthrough.** `[1,1,1]`, k=2: prefixes 1,2,3; at prefix 2 we find `prefix-k=0` (count 1) → +1; at prefix 3 we find `prefix-k=1` (count 1) → +1 → total 2.

**Complexity.** Time O(n), Space O(n). **Edge cases:** the `{0:1}` seed is essential or subarrays starting at index 0 are missed; negatives and zeros are handled naturally; `k = 0` counts zero-sum subarrays correctly.

---

### Problem 24: Missing Number — XOR / Gauss Sum

**Statement.** Given an array of `n` distinct numbers drawn from the range `[0, n]`, return the single missing number.
**Constraints.** `1 ≤ n ≤ 10⁴`, all values distinct and in `[0, n]`.

**Approach.** Sorting is O(n log n) and a boolean seen-array is O(n) space. Two O(n)-time, O(1)-space tricks exist. The **Gauss sum** computes the expected total `n(n+1)/2` and subtracts the actual sum — the difference is the missing value (use `long`/careful typing to avoid overflow, or accumulate the difference directly). The **XOR** method exploits that `x ^ x = 0`: XOR together all indices `0..n` and all array values; every present number cancels, leaving the missing one. XOR avoids overflow entirely, so it is shown as primary.

```java
public int missingNumber(int[] nums) {
    int xor = nums.length;                 // start with n (the top of the range)
    for (int i = 0; i < nums.length; i++) {
        xor ^= i ^ nums[i];                // cancel index i and value nums[i]
    }
    return xor;
}
```

**Walkthrough.** `[3,0,1]` (n=3): xor starts 3; ^0^3 → 0; ^1^0 → 1; ^2^1 → 2 → missing 2.

**Complexity.** Time O(n), Space O(1). **Edge cases:** missing `0` or missing `n` (the boundaries) are handled because the loop seeds with `n` and XORs every index; XOR never overflows, unlike the sum approach where `n(n+1)/2` could exceed `int` for large `n`.

---

### Problem 25: Container With Most Water — Two Pointers Greedy

**Statement.** Given heights `height[i]` of vertical lines, find two lines that together with the x-axis form a container holding the most water; return that maximum area.
**Constraints.** `2 ≤ n ≤ 10⁵`, `0 ≤ height[i] ≤ 10⁴`.

**Approach.** Brute force tries every pair in O(n²). The area between lines `l` and `r` is `min(height[l], height[r]) × (r - l)`, bounded by the shorter line. Start with the widest container (both ends) and move the pointer at the *shorter* line inward: keeping the shorter line can never increase the area (width shrinks and the height is still capped by that short line), so advancing it is the only move that could possibly help. This greedy two-pointer sweep is O(n).

```
height = [1,8,6,2,5,4,8,3,7]
          l=0                 r=8
 area = min(1,7)*8 = 8 ; height[l]<height[r] -> move l
          l=1             r=8
 area = min(8,7)*7 = 49 (best) ; height[r]<height[l] -> move r ... etc.
```

```java
public int maxArea(int[] height) {
    int l = 0, r = height.length - 1, best = 0;
    while (l < r) {
        int area = Math.min(height[l], height[r]) * (r - l);
        best = Math.max(best, area);
        if (height[l] < height[r]) l++;   // move the shorter side inward
        else r--;
    }
    return best;
}
```

**Walkthrough.** `[1,8,6,2,5,4,8,3,7]`: the best container is between index 1 (height 8) and index 8 (height 7): `min(8,7) × 7 = 49`.

**Complexity.** Time O(n), Space O(1). **Edge cases:** exactly two lines returns their single area; ties in height can move either pointer (correct either way); all-zero heights yield 0. Note this differs from Trapping Rain Water (Problem 11) — here we *maximize a single pair's area* rather than sum trapped water over all bars.

---

## 🧩 Extended Problems — Set 2: Medium → Hard Variations & Follow-ups

### Problem 26: 3Sum — Sort + Two Pointers (Fix One, Sweep Two)

**Statement.** Given an integer array `nums`, return all unique triplets `[a, b, c]` such that `a + b + c == 0`. The solution set must not contain duplicate triplets.
**Constraints.** `3 ≤ n ≤ 3000`, `-10⁵ ≤ nums[i] ≤ 10⁵`.

**Approach.** Brute force over all triples is O(n³). The progression: sort the array (O(n log n)), then **fix one index `i`** and reduce the remaining problem to *Two Sum on a sorted subarray* (Problem 1) solved with two pointers in O(n). That gives O(n²) overall. The sort is what makes both deduplication and the two-pointer convergence possible: after fixing `i`, a pair too small moves `lo` right, too large moves `hi` left. Skip duplicate values at `i`, `lo`, and `hi` to avoid emitting the same triplet twice. A useful pruning: once `nums[i] > 0`, no triplet can sum to zero (all later values are ≥ `nums[i]`), so break.

```java
public List<List<Integer>> threeSum(int[] nums) {
    Arrays.sort(nums);
    List<List<Integer>> res = new ArrayList<>();
    int n = nums.length;
    for (int i = 0; i < n - 2; i++) {
        if (nums[i] > 0) break;                       // can't reach 0 anymore
        if (i > 0 && nums[i] == nums[i - 1]) continue; // skip dup pivot
        int lo = i + 1, hi = n - 1;
        while (lo < hi) {
            int sum = nums[i] + nums[lo] + nums[hi];
            if (sum < 0) {
                lo++;
            } else if (sum > 0) {
                hi--;
            } else {
                res.add(Arrays.asList(nums[i], nums[lo], nums[hi]));
                while (lo < hi && nums[lo] == nums[lo + 1]) lo++; // skip dups
                while (lo < hi && nums[hi] == nums[hi - 1]) hi--;
                lo++; hi--;
            }
        }
    }
    return res;
}
```

**Walkthrough.** `[-1,0,1,2,-1,-4]` → sorted `[-4,-1,-1,0,1,2]`. Fix `-1`(i=1): lo=`-1`,hi=`2`→sum 0 → `[-1,-1,2]`; advance → lo=`0`,hi=`1`→sum 0 → `[-1,0,1]`. Fix the second `-1` is skipped as a dup. Result `[[-1,-1,2],[-1,0,1]]`.

**Complexity.** Time O(n²), Space O(1) extra (ignoring output and sort). **Edge cases:** fewer than 3 elements yields empty; all zeros `[0,0,0]` returns one triplet thanks to dedup; the three skip loops are each guarded by `lo < hi`. **Follow-ups.** 3Sum Closest (track the closest sum instead of exact zero); 4Sum (two nested fixed indices + two pointers, O(n³)); count triplets with sum < target (each valid `hi` contributes `hi - lo` pairs).

---

### Problem 27: Minimum Window Substring — Variable Window with Need Counter

**Statement.** Given strings `s` and `t`, return the smallest substring of `s` containing every character of `t` (including multiplicity). Return `""` if none exists.
**Constraints.** `1 ≤ |s|, |t| ≤ 10⁵`, any ASCII characters.

**Approach.** Brute force checks every substring: O(n²) windows times O(n) validation. The optimal solution is a **variable sliding window** with a `need` count map and a single `missing` counter tracking how many required characters are still unmet. Expand `right`, decrementing `missing` only when a *required* character's deficit is consumed. Once `missing == 0` the window is valid; then contract `left` as far as possible while it stays valid, recording the best window each time before a required char would drop out.

```
s = A D O B E C O D E B A N C ,  t = A B C
expand right until valid: [A D O B E C] missing->0
contract left:           [A D O B E C] -> drop A breaks -> record len 6
... eventually best window = [B A N C] (len 4)
```

```java
public String minWindow(String s, String t) {
    if (s.length() < t.length()) return "";
    int[] need = new int[128];
    for (char c : t.toCharArray()) need[c]++;
    int missing = t.length(), left = 0, bestLen = Integer.MAX_VALUE, bestStart = 0;
    for (int right = 0; right < s.length(); right++) {
        if (need[s.charAt(right)]-- > 0) missing--;   // consumed a required char
        while (missing == 0) {                        // window valid, try shrink
            if (right - left + 1 < bestLen) {
                bestLen = right - left + 1;
                bestStart = left;
            }
            if (need[s.charAt(left)]++ == 0) missing++; // about to drop a required char
            left++;
        }
    }
    return bestLen == Integer.MAX_VALUE ? "" : s.substring(bestStart, bestStart + bestLen);
}
```

**Walkthrough.** `s="ADOBECODEBANC"`, `t="ABC"`: the window expands until `ADOBEC` is valid, contracts, and after sweeping the minimal valid window is `BANC` (length 4).

**Complexity.** Time O(|s| + |t|) — each character enters and leaves the window once. Space O(1) (fixed 128-entry table). **Edge cases:** `t` longer than `s` returns `""`; duplicate chars in `t` handled by counting multiplicity; the `need[..]-- > 0` / `++ == 0` idiom lets surplus characters go negative without falsely decrementing `missing`. **Follow-ups.** Minimum window with at most k missing; smallest window containing all distinct chars of `s` itself; substring containing all of `t` as a *subsequence* (different DP).

---

### Problem 28: Longest Substring with At Most K Distinct Characters — Variable Window

**Statement.** Return the length of the longest substring of `s` that contains at most `k` distinct characters.
**Constraints.** `0 ≤ k ≤ |s| ≤ 5·10⁴`.

**Approach.** This is the canonical generalization of "at most two distinct" (LeetCode 159) and a stepping stone to many window problems. Maintain a frequency map of characters inside the window. Expand `right`; whenever the number of distinct keys exceeds `k`, shrink from `left`, removing characters until a key's count hits zero and is evicted, restoring the invariant `distinct ≤ k`. Record the best length each step. The window is always the longest valid one ending at `right`.

```java
public int lengthOfLongestSubstringKDistinct(String s, int k) {
    if (k == 0) return 0;
    int[] freq = new int[128];
    int distinct = 0, left = 0, best = 0;
    for (int right = 0; right < s.length(); right++) {
        if (freq[s.charAt(right)]++ == 0) distinct++;   // new distinct char
        while (distinct > k) {
            if (--freq[s.charAt(left)] == 0) distinct--; // evict a char
            left++;
        }
        best = Math.max(best, right - left + 1);
    }
    return best;
}
```

**Walkthrough.** `s="eceba"`, k=2: window grows `e,ec,ece`(2 distinct, len 3); adding `b` → 3 distinct → shrink past first `e`/`c` to `ceb`... best length stays 3 (`ece`).

**Complexity.** Time O(n) — each pointer advances at most `n`. Space O(min(n, charset)). **Edge cases:** `k == 0` returns 0; `k ≥ distinct(s)` returns `|s|`; empty string returns 0. **Follow-ups.** Exactly k distinct = atMost(k) − atMost(k−1); longest substring with at most k *repeating* allowed; with a `HashMap` for arbitrary Unicode keys.

---

### Problem 29: Maximum Product Subarray — Track Min and Max Together

**Statement.** Find the contiguous subarray with the largest product and return that product.
**Constraints.** `1 ≤ n ≤ 2·10⁴`, `-10 ≤ nums[i] ≤ 10`, the answer fits in a 32-bit int.

**Approach.** Kadane (Problem 4) tracks only a running max because addition is monotone, but **multiplication flips sign with negatives**: a large *negative* product can become the maximum after multiplying by another negative. So we carry *both* the max and min product ending at the current index. When the current element is negative, the roles swap — yesterday's min (a big negative) times a negative becomes today's max candidate. At each step `curMax = max(x, x·prevMax, x·prevMin)` and symmetrically for `curMin`.

```java
public int maxProduct(int[] nums) {
    int max = nums[0], min = nums[0], best = nums[0];
    for (int i = 1; i < nums.length; i++) {
        int x = nums[i];
        if (x < 0) { int t = max; max = min; min = t; } // negative flips extremes
        max = Math.max(x, max * x);
        min = Math.min(x, min * x);
        best = Math.max(best, max);
    }
    return best;
}
```

**Walkthrough.** `[2,3,-2,4]`: at 2 →max2; at 3→max6; at −2→swap then max=−2, min=−12; at 4→max4,min=−48 → best 6. `[-2,3,-4]`: ends with max 24 (`-2·3·-4`).

**Complexity.** Time O(n), Space O(1). **Edge cases:** single negative element returns itself; zeros reset both products to the next element (because `max(x, anything·0)` favors `x`); the sign-swap must happen *before* the max/min update. **Follow-ups.** Return the subarray bounds; maximum product with at most one element removed; product subarray modulo prime.

---

### Problem 30: Sliding Window Maximum — Monotonic Deque

**Statement.** Given an array `nums` and window size `k`, return the maximum of each contiguous window of size `k` as the window slides left to right.
**Constraints.** `1 ≤ k ≤ n ≤ 10⁵`, `-10⁴ ≤ nums[i] ≤ 10⁴`.

**Approach.** Recomputing each window's max is O(n·k); a max-heap is O(n log n) but needs lazy deletion. The optimal O(n) uses a **monotonic decreasing deque holding indices**. Invariant: indices in the deque have strictly decreasing values, so the front is always the current window's max. For each new index: pop from the back while its value is ≤ the incoming value (those can never be a max again — they're older *and* smaller), then push. Pop from the front if it has scrolled out of the window (`index ≤ right - k`). Each index is pushed and popped at most once → O(n).

```
nums=[1,3,-1,-3,5,3,6,7] k=3
deque holds indices, values decreasing front->back
window [1,3,-1] -> front idx of 3 -> max 3
window [3,-1,-3]-> max 3 ; [-1,-3,5]->5 ; ... -> [3,3,5,5,6,7]
```

```java
public int[] maxSlidingWindow(int[] nums, int k) {
    int n = nums.length;
    int[] res = new int[n - k + 1];
    Deque<Integer> dq = new ArrayDeque<>();        // indices, values decreasing
    for (int i = 0; i < n; i++) {
        if (!dq.isEmpty() && dq.peekFirst() <= i - k) dq.pollFirst(); // out of window
        while (!dq.isEmpty() && nums[dq.peekLast()] <= nums[i]) dq.pollLast();
        dq.offerLast(i);
        if (i >= k - 1) res[i - k + 1] = nums[dq.peekFirst()];
    }
    return res;
}
```

**Walkthrough.** `[1,3,-1,-3,5,3,6,7]`, k=3 → `[3,3,5,5,6,7]`. When `5` arrives it evicts `-1` and `-3` from the back; its index becomes the new front max.

**Complexity.** Time O(n) (amortized — each index enters/leaves the deque once), Space O(k). **Edge cases:** `k == 1` returns the array itself; `k == n` returns a single global max; the front-eviction check must precede the back-eviction so a stale max never leaks. **Follow-ups.** Sliding window minimum (monotonic increasing deque); first negative in each window; constrained subsequence sum (deque-optimized DP).

---

### Problem 31: Next Permutation — In-Place Lexicographic Step

**Statement.** Rearrange `nums` into the lexicographically next greater permutation in place. If it is already the largest, wrap to the smallest (ascending) order. Use O(1) extra space.
**Constraints.** `1 ≤ n ≤ 10⁴`.

**Approach.** This is a classic "find the algorithm, not the data structure" problem. Scan from the right to find the first index `i` where `nums[i] < nums[i+1]` — the *pivot*. Everything to its right is a non-increasing suffix (already the largest arrangement of those elements). To make the smallest increase, find the rightmost element greater than the pivot, swap them, then reverse the suffix to turn it from descending into ascending (its smallest order). If no pivot exists, the whole array is descending → reverse it entirely.

```
[1,2,3,?]  pivot at value 2 (nums[i]<nums[i+1])
find rightmost > 2 in suffix [3] -> 3 ; swap -> [1,3,2]
reverse suffix after pivot pos -> [1,3,2] (already minimal)
```

```java
public void nextPermutation(int[] nums) {
    int n = nums.length, i = n - 2;
    while (i >= 0 && nums[i] >= nums[i + 1]) i--;       // find pivot
    if (i >= 0) {
        int j = n - 1;
        while (nums[j] <= nums[i]) j--;                 // rightmost > pivot
        swap(nums, i, j);
    }
    reverse(nums, i + 1, n - 1);                        // suffix to ascending
}
private void swap(int[] a, int i, int j) { int t = a[i]; a[i] = a[j]; a[j] = t; }
private void reverse(int[] a, int i, int j) {
    while (i < j) { int t = a[i]; a[i++] = a[j]; a[j--] = t; }
}
```

**Walkthrough.** `[1,2,3]`→`[1,3,2]`; `[3,2,1]` has no pivot → reverse → `[1,2,3]`; `[1,1,5]`→`[1,5,1]`.

**Complexity.** Time O(n), Space O(1). **Edge cases:** strictly descending array wraps to ascending (`i == -1`, reverse all); duplicates handled by `>=`/`<=` comparisons; single element is unchanged. **Follow-ups.** Previous permutation (mirror the comparisons); k-th permutation directly via factorial number system; permutation rank.

---

### Problem 32: Find Minimum in Rotated Sorted Array — Modified Binary Search

**Statement.** A sorted ascending array of distinct values was rotated at an unknown pivot. Find the minimum element in O(log n).
**Constraints.** `1 ≤ n ≤ 5000`, all values unique.

**Approach.** Linear scan is O(n). Because the array is *piecewise* sorted, binary search still applies if we reason about which half is "normal". Compare `nums[mid]` with `nums[hi]`: if `nums[mid] > nums[hi]`, the rotation point (and thus the minimum) lies strictly to the right, so move `lo = mid + 1`. Otherwise the minimum is at `mid` or to its left, so `hi = mid`. We compare against `hi` rather than `lo` because that uniquely identifies the unsorted side. The loop converges to the single rotation point.

```
[4,5,6,7,0,1,2]   lo=0 hi=6 mid=3 nums[3]=7 > nums[6]=2 -> lo=4
[          0,1,2] lo=4 hi=6 mid=5 nums[5]=1 < nums[6]=2 -> hi=5
                  lo=4 hi=5 mid=4 nums[4]=0 < nums[5]=1 -> hi=4 -> min=0
```

```java
public int findMin(int[] nums) {
    int lo = 0, hi = nums.length - 1;
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (nums[mid] > nums[hi]) lo = mid + 1;  // min in right half
        else hi = mid;                           // min at mid or left
    }
    return nums[lo];
}
```

**Walkthrough.** `[4,5,6,7,0,1,2]` → converges to index 4, value 0. A non-rotated array `[1,2,3]` immediately satisfies `nums[mid] <= nums[hi]` and returns `nums[0]=1`.

**Complexity.** Time O(log n), Space O(1). **Edge cases:** non-rotated array returns the first element; single element returns itself; using `lo + (hi-lo)/2` avoids overflow. **Follow-ups.** With duplicates (LeetCode 154 — when `nums[mid] == nums[hi]`, do `hi--`, degrading to O(n) worst case); search a target in a rotated array (LeetCode 33); find the rotation count.

---

### Problem 33: Search in Rotated Sorted Array — One-Pass Binary Search

**Statement.** Given a rotated ascending array of distinct integers and a `target`, return its index or `-1`, in O(log n).
**Constraints.** `1 ≤ n ≤ 5000`, all values unique, `-10⁴ ≤ values, target ≤ 10⁴`.

**Approach.** A two-step approach finds the pivot then binary-searches one segment. The cleaner one-pass approach: at each `mid`, exactly one half `[lo, mid]` or `[mid, hi]` is *sorted* (no rotation point inside it). Detect the sorted half by comparing endpoints, then check whether the target lies within that sorted half's value range — if so, recurse into it; otherwise recurse into the other half. Each step halves the search space while correctly handling the rotation.

```java
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
```

**Walkthrough.** `[4,5,6,7,0,1,2]`, target 0: mid=3(7), left half `[4..7]` sorted but 0∉[4,7) → search right; mid=5(1), right half... 0∉(1,2] → search left; mid=4(0) → found at index 4.

**Complexity.** Time O(log n), Space O(1). **Edge cases:** target absent returns −1; `nums[lo] <= nums[mid]` uses `<=` so a two-element window is classified correctly; single element compared directly. **Follow-ups.** Rotated array with duplicates (LeetCode 81 — `nums[lo]==nums[mid]==nums[hi]` forces shrinking both ends, O(n) worst); find first/last occurrence; search a 2-D rotated matrix.

---

### Problem 34: Longest Repeating Character Replacement — Window with Max-Frequency Bound

**Statement.** Given a string `s` of uppercase letters and an integer `k`, you may replace at most `k` characters with any letter. Return the length of the longest substring containing a single repeated letter achievable after at most `k` replacements.
**Constraints.** `1 ≤ |s| ≤ 10⁵`, `0 ≤ k ≤ |s|`.

**Approach.** A window `[left, right]` is *feasible* when `(windowLength − countOfMostFrequentChar) ≤ k`: that difference is exactly how many characters we'd have to replace to make the window uniform. Expand `right`, tracking each letter's frequency and the running `maxFreq`. When the window becomes infeasible, slide `left` forward by one (the window never shrinks below its best size — a subtle but correct optimization: `maxFreq` is not decreased on shrink, so `best` only ever reflects a genuinely-seen larger valid window). The answer is the maximum window width observed.

```java
public int characterReplacement(String s, int k) {
    int[] freq = new int[26];
    int left = 0, maxFreq = 0, best = 0;
    for (int right = 0; right < s.length(); right++) {
        maxFreq = Math.max(maxFreq, ++freq[s.charAt(right) - 'A']);
        while (right - left + 1 - maxFreq > k) {   // too many replacements needed
            freq[s.charAt(left) - 'A']--;
            left++;
        }
        best = Math.max(best, right - left + 1);
    }
    return best;
}
```

**Walkthrough.** `s="AABABBA"`, k=1: window grows to `AABA` (maxFreq A=3, replacements=1 ≤1, len 4); pushing further needs 2 replacements → slide; best length is 4.

**Complexity.** Time O(n) (26-letter alphabet, each char visited by both pointers once), Space O(1). **Edge cases:** `k ≥ |s|` returns `|s|`; `k == 0` returns the longest run of one letter; the window using `if` vs `while` are both correct here since we shift by one — `while` is shown for clarity. **Follow-ups.** Arbitrary alphabet via `HashMap`; longest substring with at most k zeros flipped to ones (Max Consecutive Ones III — same template); minimum replacements to make all equal.

---

### Problem 35: Group Shifted Strings — Canonical Difference Signature

**Statement.** Two strings belong to the same shifting sequence if each can be obtained from the other by shifting every character by the same amount (mod 26), e.g. `"abc" → "bcd" → "xyz"`. Group all strings of the input that belong to the same sequence.
**Constraints.** `1 ≤ n ≤ 10⁴`, lowercase letters, variable lengths.

**Approach.** This is a "find the right canonical key" problem in the same family as Group Anagrams (Problem 6), but the key is different. Strings in one shift-group share the same *sequence of consecutive character differences* (mod 26). Compute a signature from those gaps — e.g. `"abc"` → diffs `(1,1)` and `"bcd"` → diffs `(1,1)` collide, while length differences are naturally separated because shorter strings have fewer gaps. Adding 26 before the modulo keeps differences non-negative when characters wrap.

```java
public List<List<String>> groupStrings(String[] strings) {
    Map<String, List<String>> groups = new HashMap<>();
    for (String s : strings) {
        StringBuilder key = new StringBuilder();
        for (int i = 1; i < s.length(); i++) {
            int diff = (s.charAt(i) - s.charAt(i - 1) + 26) % 26; // wrap-safe gap
            key.append(diff).append(',');                         // delimiter avoids 1|12 collisions
        }
        groups.computeIfAbsent(key.toString(), x -> new ArrayList<>()).add(s);
    }
    return new ArrayList<>(groups.values());
}
```

**Walkthrough.** `["abc","bcd","acef","xyz","az","ba","a","z"]`: `abc`/`bcd`/`xyz` → key `1,1,` group together; `az`/`ba` → key `25,` together; `a`/`z` → empty key (length-1) together; `acef` → key `2,2,1,` alone.

**Complexity.** Time O(N·L) (L = average length, building each key), Space O(N·L). **Edge cases:** single-character strings share the empty-string key and group together; the comma delimiter prevents `"1"+"2"` colliding with `"12"`; the `+26` is essential for wraps like `z→a`. **Follow-ups.** Group by rotation instead of shift; group anagrams (Problem 6) shares the bucket-by-canonical-key pattern; case-insensitive variant.

---

### Problem 36: Maximum Sum Circular Subarray — Kadane Twice

**Statement.** Given a *circular* integer array, find the maximum possible sum of a non-empty subarray, where the subarray may wrap around the end into the beginning.
**Constraints.** `1 ≤ n ≤ 3·10⁴`, `-3·10⁴ ≤ nums[i] ≤ 3·10⁴`.

**Approach.** There are two cases. (1) The optimal subarray does **not** wrap — that is plain Kadane (Problem 4) giving `maxStraight`. (2) It **does** wrap — equivalently, the *unselected* middle elements form the *minimum* subarray, so the wrapped max is `totalSum − minStraight`. Run Kadane once for the max and once (with flipped comparisons) for the min, in a single pass. The answer is `max(maxStraight, total − minStraight)`. The crucial edge case: if every element is negative, `total − minStraight` equals 0 (empty middle), which is invalid since the subarray must be non-empty — so fall back to `maxStraight`.

```java
public int maxSubarraySumCircular(int[] nums) {
    int total = 0, curMax = 0, best = Integer.MIN_VALUE;
    int curMin = 0, worst = Integer.MAX_VALUE;
    for (int x : nums) {
        curMax = Math.max(x, curMax + x);
        best = Math.max(best, curMax);
        curMin = Math.min(x, curMin + x);
        worst = Math.min(worst, curMin);
        total += x;
    }
    return best < 0 ? best : Math.max(best, total - worst); // all-negative guard
}
```

**Walkthrough.** `[5,-3,5]`: straight max 7 (`[5,-3,5]`? = 7); wrap = total(7) − min(−3) = 10 (`[5]...[5]` wrapping) → answer 10. `[-3,-2,-3]`: best=−2 < 0 → return −2.

**Complexity.** Time O(n), Space O(1). **Edge cases:** all-negative array returns the single largest (least negative) element via the `best < 0` guard; single element returns itself; the two Kadanes run in one fused loop. **Follow-ups.** Return indices of the wrapping subarray; circular *minimum* subarray; max sum with exactly one wrap allowed.

---

### Problem 37: First Missing Positive — Index-as-Hash In-Place Placement

**Statement.** Given an unsorted array, find the smallest missing positive integer in O(n) time and O(1) extra space.
**Constraints.** `1 ≤ n ≤ 10⁵`, `-2³¹ ≤ nums[i] ≤ 2³¹ − 1`.

**Approach.** The answer is necessarily in `[1, n+1]` (with `n` slots, the smallest missing positive can't exceed `n+1`). A hash set gives O(n) time but O(n) space — banned. The trick is to use the array itself as a hash table via **cyclic placement**: put each value `v` in `[1, n]` at index `v−1` by swapping, ignoring out-of-range and already-correct values. After this rearrangement, scan for the first index `i` where `nums[i] != i+1`; that `i+1` is the answer. If all positions are correct, the answer is `n+1`.

```
[3,4,-1,1]  place each v at index v-1
swap 3->idx2: [-1,4,3,1] ; swap 4->idx3: [-1,1,3,4] ; swap -1 skip ; swap 1->idx0:[1,-1,3,4]
scan: idx1 holds -1 != 2  -> answer 2
```

```java
public int firstMissingPositive(int[] nums) {
    int n = nums.length;
    for (int i = 0; i < n; i++) {
        // place nums[i] at its home index while it's in range and not already there
        while (nums[i] > 0 && nums[i] <= n && nums[nums[i] - 1] != nums[i]) {
            int target = nums[i] - 1;
            int tmp = nums[target];
            nums[target] = nums[i];
            nums[i] = tmp;
        }
    }
    for (int i = 0; i < n; i++) {
        if (nums[i] != i + 1) return i + 1;
    }
    return n + 1;
}
```

**Walkthrough.** `[3,4,-1,1]` → after placement `[1,-1,3,4]` → first mismatch at index 1 (holds −1, expected 2) → answer 2. `[1,2,3]` → all correct → answer 4.

**Complexity.** Time O(n) — each swap puts one value home permanently, so total swaps ≤ n. Space O(1). **Edge cases:** the `nums[nums[i]-1] != nums[i]` guard prevents an infinite swap loop on duplicates (e.g. `[1,1]`); values ≤ 0 and > n are skipped; empty conceptual input returns 1. **Follow-ups.** k-th missing positive; first missing positive in a sorted array (binary search); smallest missing non-negative.

---

### Problem 38: Text Justification — Greedy Line Packing + Even Spacing

**Statement.** Given an array of `words` and a `maxWidth`, format the text so each line is exactly `maxWidth` characters, fully justified (extra spaces distributed as evenly as possible, with leftover spaces going to the left gaps). The last line is left-justified.
**Constraints.** `1 ≤ words.length ≤ 300`, `1 ≤ word.length ≤ maxWidth ≤ 100`.

**Approach.** This is a heavy implementation/edge-case problem common at senior loops. Greedily pack as many words onto a line as fit (a word fits if current length + word length + at least one space between each pair ≤ `maxWidth`). Then distribute spaces: with `gaps = wordsOnLine − 1` slots and `totalSpaces = maxWidth − totalWordChars`, each gap gets `totalSpaces / gaps` spaces and the leftmost `totalSpaces % gaps` gaps get one extra. Two special cases get left-justification (pad spaces only on the right): a single-word line (no gaps to distribute into) and the final line.

```java
public List<String> fullJustify(String[] words, int maxWidth) {
    List<String> res = new ArrayList<>();
    int i = 0, n = words.length;
    while (i < n) {
        int j = i, lineLen = 0;                       // [i, j) words on this line
        while (j < n && lineLen + (j - i) + words[j].length() <= maxWidth) {
            lineLen += words[j].length();             // +(j-i) accounts for min spaces
            j++;
        }
        int count = j - i, spaces = maxWidth - lineLen, gaps = count - 1;
        StringBuilder sb = new StringBuilder();
        if (gaps == 0 || j == n) {                    // single word OR last line: left-justify
            for (int w = i; w < j; w++) {
                if (w > i) sb.append(' ');
                sb.append(words[w]);
            }
            while (sb.length() < maxWidth) sb.append(' ');
        } else {
            int base = spaces / gaps, extra = spaces % gaps;
            for (int w = i; w < j; w++) {
                sb.append(words[w]);
                if (w < j - 1) {
                    int pad = base + (w - i < extra ? 1 : 0); // leftmost gaps get +1
                    for (int s = 0; s < pad; s++) sb.append(' ');
                }
            }
        }
        res.add(sb.toString());
        i = j;
    }
    return res;
}
```

**Walkthrough.** `["This","is","an","example","of","text","justification."]`, maxWidth 16: line 1 packs `This is an` → `"This    is    an"` (spaces 4,4); the last line `justification.` is left-justified and right-padded.

**Complexity.** Time O(total characters), Space O(maxWidth) per line. **Edge cases:** a single word wider than others still left-justifies (no gaps); the last line never internally redistributes; `lineLen + (j - i)` cleverly counts the mandatory single spaces while greedily packing. **Follow-ups.** Right-justify or center; minimize raggedness via DP (Knuth's line-breaking, the TeX algorithm); wrap with hyphenation.

---

## 🧩 Extended Problems — Set 3: Hard / Expert & Optimization

### Problem 39: Median of Two Sorted Arrays — Partition Binary Search

**Statement.** Given two sorted arrays `nums1` and `nums2`, return the median of the combined sorted array in O(log(min(m, n))) time.
**Constraints.** `0 ≤ m, n ≤ 1000`, `1 ≤ m + n`, values fit in `int`.

**Approach.** Merging is O(m + n); the staff-bar answer binary-searches a *partition*. We split the smaller array at index `i` and the larger at `j = (m + n + 1)/2 − i` so the left side holds exactly half the elements. The partition is correct when `maxLeft1 ≤ minRight2` and `maxLeft2 ≤ minRight1` — i.e. everything on the left is ≤ everything on the right. Binary-search `i` on the smaller array (so the search range is `O(log min)`), moving left when `maxLeft1 > minRight2`. Sentinels `±∞` handle the empty-side edges. Median is then the boundary max/avg.

```
nums1 = [1,3]   nums2 = [2]   total=3, half=2
i picks split in nums1, j = half - i fills the rest from nums2
[ left half | right half ]  ->  maxLeft = max(1, ...), minRight = min(...)
```

```java
public double findMedianSortedArrays(int[] a, int[] b) {
    if (a.length > b.length) return findMedianSortedArrays(b, a); // search the smaller
    int m = a.length, n = b.length, half = (m + n + 1) / 2;
    int lo = 0, hi = m;
    while (lo <= hi) {
        int i = lo + (hi - lo) / 2;     // elements taken from a
        int j = half - i;               // elements taken from b
        int maxL1 = (i == 0) ? Integer.MIN_VALUE : a[i - 1];
        int minR1 = (i == m) ? Integer.MAX_VALUE : a[i];
        int maxL2 = (j == 0) ? Integer.MIN_VALUE : b[j - 1];
        int minR2 = (j == n) ? Integer.MAX_VALUE : b[j];
        if (maxL1 <= minR2 && maxL2 <= minR1) {            // correct partition
            int leftMax = Math.max(maxL1, maxL2);
            if (((m + n) & 1) == 1) return leftMax;        // odd total
            int rightMin = Math.min(minR1, minR2);
            return (leftMax + rightMin) / 2.0;             // even total
        } else if (maxL1 > minR2) {
            hi = i - 1;                 // took too many from a
        } else {
            lo = i + 1;                 // took too few from a
        }
    }
    throw new IllegalArgumentException("inputs not sorted");
}
```

**Walkthrough.** `a=[1,3]`, `b=[2]`: half=2. Try `i=1,j=1`: maxL1=1≤minR2=∞, maxL2=2≤minR1=3 → valid; total odd → leftMax = max(1,2) = 2.

**Complexity.** Time O(log(min(m, n))), Space O(1). **Edge cases:** one empty array (all sentinels resolve via the empty side); odd vs even total handled separately; recursing on the smaller array bounds the search and prevents `j` going out of range.

---

### Problem 40: Largest Rectangle in Histogram — Monotonic Increasing Stack

**Statement.** Given heights of adjacent unit-width bars, find the area of the largest rectangle that fits entirely within the histogram.
**Constraints.** `1 ≤ n ≤ 10⁵`, `0 ≤ heights[i] ≤ 10⁴`.

**Approach.** Brute force checks every pair of bars: O(n²). The optimal O(n) keeps a **stack of indices with non-decreasing heights**. When the current bar is shorter than the stack top, that top bar can no longer extend rightward, so we pop it and compute the rectangle whose height is the popped bar and whose width spans from the new stack top (exclusive) to the current index (exclusive). A sentinel height of 0 appended at the end flushes the stack. Each index is pushed and popped once.

```
heights = [2,1,5,6,2,3]
push 2; bar 1 < 2 -> pop 2, area 2*1; push 1; push 5; push 6;
bar 2 < 6 -> pop 6 area 6*1; pop 5 area 5*2=10 (best); ...
```

```java
public int largestRectangleArea(int[] heights) {
    int n = heights.length, best = 0;
    Deque<Integer> stack = new ArrayDeque<>();   // indices, heights non-decreasing
    for (int i = 0; i <= n; i++) {
        int h = (i == n) ? 0 : heights[i];       // sentinel flushes the stack
        while (!stack.isEmpty() && heights[stack.peek()] >= h) {
            int height = heights[stack.pop()];
            int leftBound = stack.isEmpty() ? -1 : stack.peek();
            int width = i - leftBound - 1;       // span between new top and i
            best = Math.max(best, height * width);
        }
        stack.push(i);
    }
    return best;
}
```

**Walkthrough.** `[2,1,5,6,2,3]`: the rectangle of height 5 over bars `[5,6]` spans width 2 (area 10) is the best; final answer 10.

**Complexity.** Time O(n) (each index pushed/popped once), Space O(n). **Edge cases:** strictly increasing input is only resolved by the trailing sentinel; equal heights are popped via `>=` so width is computed correctly; all-zero heights yield 0. **Follow-ups.** Maximal rectangle in a binary matrix (run this per row over a histogram of column heights — Problem 41); maximal square; trapping rain water via the same stack.

---

### Problem 41: Maximal Rectangle in a Binary Matrix — Histogram Per Row

**Statement.** Given a `rows × cols` binary matrix of `'0'`/`'1'`, find the area of the largest rectangle containing only `1`s.
**Constraints.** `1 ≤ rows, cols ≤ 200`.

**Approach.** Reduce to the histogram problem (Problem 40). Process rows top to bottom, maintaining a running `heights[c]` = number of consecutive `1`s ending at the current row in column `c` (reset to 0 on a `0`). After updating each row, the largest rectangle whose *bottom* sits on this row equals the largest rectangle in that histogram. Taking the max across all rows gives the global answer. This converts an O((rows·cols)²)-ish brute force into O(rows·cols).

```java
public int maximalRectangle(char[][] matrix) {
    if (matrix.length == 0 || matrix[0].length == 0) return 0;
    int cols = matrix[0].length, best = 0;
    int[] heights = new int[cols];
    for (char[] row : matrix) {
        for (int c = 0; c < cols; c++) {
            heights[c] = (row[c] == '1') ? heights[c] + 1 : 0;  // extend or reset
        }
        best = Math.max(best, largestRectangleArea(heights));
    }
    return best;
}

private int largestRectangleArea(int[] heights) {
    int n = heights.length, best = 0;
    Deque<Integer> stack = new ArrayDeque<>();
    for (int i = 0; i <= n; i++) {
        int h = (i == n) ? 0 : heights[i];
        while (!stack.isEmpty() && heights[stack.peek()] >= h) {
            int height = heights[stack.pop()];
            int left = stack.isEmpty() ? -1 : stack.peek();
            best = Math.max(best, height * (i - left - 1));
        }
        stack.push(i);
    }
    return best;
}
```

**Walkthrough.** Matrix rows building heights `[1,0,1,0,0] → [2,0,2,1,1] → [3,1,3,2,2] → [4,0,0,3,0]`: the third row's histogram yields the maximal rectangle of area 6.

**Complexity.** Time O(rows · cols), Space O(cols). **Edge cases:** empty matrix returns 0; an all-zero row resets every height; a single `1` returns 1. **Follow-ups.** Maximal square (track `min(left,up,upleft)+1` DP); count submatrices of all ones; largest rectangle with at most k zeros.

---

### Problem 42: Sliding Window Median — Two Heaps with Lazy Deletion

**Statement.** Return the median of every contiguous window of size `k` as it slides across `nums`.
**Constraints.** `1 ≤ k ≤ n ≤ 10⁵`, values fit in `int` (use `long`/careful averaging to avoid overflow).

**Approach.** A sorted multiset per window is O(n·k log k). The optimal balances two heaps: a `max-heap` of the smaller half and a `min-heap` of the larger half, kept within one element of equal size so the median is the top(s). The twist for a *sliding* window is removal of the element leaving the window — heaps don't support arbitrary delete in O(log k), so we use **lazy deletion**: a `HashMap` of "to-be-deleted" counts, and we purge stale tops whenever they surface, while tracking a `balance` to keep sizes correct.

```java
public double[] medianSlidingWindow(int[] nums, int k) {
    PriorityQueue<Integer> lo = new PriorityQueue<>(Collections.reverseOrder()); // smaller half
    PriorityQueue<Integer> hi = new PriorityQueue<>();                            // larger half
    Map<Integer, Integer> deleted = new HashMap<>();
    double[] res = new double[nums.length - k + 1];
    int balance = 0;                                  // (effective hi size) - (effective lo size)
    for (int i = 0; i < nums.length; i++) {
        // insert
        if (lo.isEmpty() || nums[i] <= lo.peek()) { lo.offer(nums[i]); balance--; }
        else { hi.offer(nums[i]); balance++; }
        // remove leaving element lazily
        if (i >= k) {
            int out = nums[i - k];
            deleted.merge(out, 1, Integer::sum);
            if (!lo.isEmpty() && out <= lo.peek()) balance++;
            else balance--;
        }
        // rebalance counts (move one across)
        if (balance < 0) { hi.offer(lo.poll()); balance += 2; }
        else if (balance > 0) { lo.offer(hi.poll()); balance -= 2; }
        // purge stale tops
        prune(lo, deleted);
        prune(hi, deleted);
        if (i >= k - 1) {
            res[i - k + 1] = (k % 2 == 1)
                ? (double) lo.peek()
                : ((double) lo.peek() + hi.peek()) / 2.0;  // even: average the two middles
        }
    }
    return res;
}
private void prune(PriorityQueue<Integer> pq, Map<Integer, Integer> deleted) {
    while (!pq.isEmpty() && deleted.getOrDefault(pq.peek(), 0) > 0) {
        deleted.merge(pq.peek(), -1, Integer::sum);
        pq.poll();
    }
}
```

**Walkthrough.** `nums=[1,3,-1,-3,5,3,6,7]`, k=3 → `[1.0, -1.0, -1.0, 3.0, 5.0, 6.0]`. When `1` leaves, it is marked deleted and pruned the next time it reaches a heap top.

**Complexity.** Time O(n log k), Space O(k). **Edge cases:** even `k` averages the two middles as `double` to avoid `int` overflow (`((long)a + b)/2.0` if values are near `Integer.MAX_VALUE`); the invariant keeps `lo` either equal to or one larger than `hi`, so `lo.peek()` is always the lower median; lazy deletion keeps effective sizes correct even though physical heap sizes drift. **Follow-ups.** Sliding window mode; window k-th smallest; use an order-statistics tree (`TreeMap` of counts) instead of heaps for cleaner deletion.

---

### Problem 43: Count of Range Sum — Merge Sort on Prefix Sums

**Statement.** Given `nums`, count how many contiguous subarrays have a sum in the inclusive range `[lower, upper]`.
**Constraints.** `1 ≤ n ≤ 10⁵`, `-2³¹ ≤ nums[i] ≤ 2³¹ − 1`, `-10⁵ ≤ lower ≤ upper ≤ 10⁵` (sums need `long`).

**Approach.** A subarray sum `(i, j]` equals `prefix[j] − prefix[i]`. We need, for each `j`, the count of earlier prefixes `prefix[i]` with `prefix[j] − upper ≤ prefix[i] ≤ prefix[j] − lower`. Brute force is O(n²). The classic divide-and-conquer counts cross-pairs during a **merge sort over the prefix-sum array**: after both halves are sorted, for each left-half prefix slide two pointers over the right half to count valid `prefix[j]`. Merging sorts in place for the next level. Total O(n log n).

```java
public int countRangeSum(int[] nums, int lower, int upper) {
    int n = nums.length;
    long[] prefix = new long[n + 1];
    for (int i = 0; i < n; i++) prefix[i + 1] = prefix[i] + nums[i];
    return mergeCount(prefix, 0, n + 1, lower, upper, new long[n + 1]);
}
private int mergeCount(long[] sums, int lo, int hi, int lower, int upper, long[] buf) {
    if (hi - lo <= 1) return 0;
    int mid = (lo + hi) >>> 1;
    int count = mergeCount(sums, lo, mid, lower, upper, buf)
              + mergeCount(sums, mid, hi, lower, upper, buf);
    int j = mid, kk = mid, t = mid, r = lo;       // count cross pairs, then merge
    for (int i = lo; i < mid; i++) {
        while (j < hi && sums[j] - sums[i] < lower) j++;   // first >= lower diff
        while (kk < hi && sums[kk] - sums[i] <= upper) kk++; // first > upper diff
        while (t < hi && sums[t] < sums[i]) buf[r++] = sums[t++];
        buf[r++] = sums[i];
        count += kk - j;
    }
    while (t < hi) buf[r++] = sums[t++];
    System.arraycopy(buf, lo, sums, lo, hi - lo);
    return count;
}
```

**Walkthrough.** `nums=[-2,5,-1]`, lower=-2, upper=2: valid subarrays are `[-2]`, `[-1]`, `[5,-1]→4`? No — sums `-2,5,-1,3,4,2`; those in `[-2,2]` are `-2,-1,2` → count 3.

**Complexity.** Time O(n log n), Space O(n). **Edge cases:** `long` prefixes prevent overflow when summing `int` extremes; the two pointers `j`/`kk` only advance (monotone over sorted halves); single-element ranges work via the `hi - lo <= 1` base case. **Follow-ups.** Count smaller numbers after self (same merge-count skeleton); count range sum with a BIT/Fenwick tree over compressed prefixes; reverse pairs.

---

### Problem 44: Shortest Subarray with Sum at Least K — Monotonic Deque on Prefix Sums

**Statement.** Given an integer array `nums` (which may contain **negative** numbers) and an integer `k`, return the length of the shortest non-empty subarray with sum at least `k`, or `-1` if none exists.
**Constraints.** `1 ≤ n ≤ 10⁵`, `-10⁵ ≤ nums[i] ≤ 10⁵`, `1 ≤ k ≤ 10⁹`.

**Approach.** With negatives, the plain "shrinking window" of Minimum Size Subarray Sum is unsound. Build prefix sums `P`; a subarray `(i, j]` has sum `P[j] − P[i] ≥ k`. For each `j` we want the *largest* `i < j` with `P[i] ≤ P[j] − k`, and the *closest* such `i`. Maintain a **monotonic increasing deque of prefix indices**: (1) from the front, while `P[j] − P[front] ≥ k`, record `j − front` and pop the front (it can never give a shorter answer later); (2) from the back, pop indices whose prefix is `≥ P[j]` (a later, smaller-or-equal prefix dominates them). Each index enters and leaves once → O(n).

```java
public int shortestSubarray(int[] nums, int k) {
    int n = nums.length;
    long[] p = new long[n + 1];
    for (int i = 0; i < n; i++) p[i + 1] = p[i] + nums[i];
    Deque<Integer> dq = new ArrayDeque<>();      // indices, prefix increasing
    int best = Integer.MAX_VALUE;
    for (int j = 0; j <= n; j++) {
        while (!dq.isEmpty() && p[j] - p[dq.peekFirst()] >= k) {
            best = Math.min(best, j - dq.pollFirst());   // shortest ending at j
        }
        while (!dq.isEmpty() && p[dq.peekLast()] >= p[j]) {
            dq.pollLast();                                // dominated prefix
        }
        dq.offerLast(j);
    }
    return best == Integer.MAX_VALUE ? -1 : best;
}
```

**Walkthrough.** `nums=[2,-1,2]`, k=3: prefixes `[0,2,1,3]`. At j=3 (P=3): P3−P0=3≥3 → length 3; also P3−P2=2<3. The back-pop earlier discarded index 1 (P=2) when index 2 (P=1) arrived. Answer 3.

**Complexity.** Time O(n), Space O(n). **Edge cases:** negatives are precisely why the deque (not a shrinking window) is required; no qualifying subarray returns −1; `long` prefixes avoid overflow. **Follow-ups.** Non-negative-only version reduces to a simple two-pointer window (Minimum Size Subarray Sum, O(n) O(1)); maximum-length subarray with sum ≤ k; constrained subsequence sum.

---

### Problem 45: Substring with Concatenation of All Words — Multi-Pointer Fixed Window

**Statement.** Given a string `s` and an array `words` of equal-length strings, return all starting indices in `s` of substrings that are a concatenation of every word in `words` exactly once, in any order, with no intervening characters.
**Constraints.** `1 ≤ |s| ≤ 10⁴`, `1 ≤ words.length ≤ 5000`, all words share length `1 ≤ L ≤ 30`.

**Approach.** Let `L` = word length, `W` = number of words, `total = L·W`. Naively checking each start with a fresh count map is O(|s|·W). The optimization: run a **sliding window in steps of `L`**, but anchored at each of the `L` possible offsets `0..L−1`. For a fixed offset we slide a window of `W` words, adding the entering word and removing words from the left when a word's count exceeds its need or a foreign word appears — exactly the Minimum-Window/anagram idea but on word tokens. This visits each position O(1) amortized times → O(L · (|s|/L)) = O(|s|) word operations.

```java
public List<Integer> findSubstring(String s, String[] words) {
    List<Integer> res = new ArrayList<>();
    int W = words.length, L = words[0].length(), total = W * L, n = s.length();
    if (n < total) return res;
    Map<String, Integer> need = new HashMap<>();
    for (String w : words) need.merge(w, 1, Integer::sum);
    for (int offset = 0; offset < L; offset++) {
        int left = offset, count = 0;
        Map<String, Integer> window = new HashMap<>();
        for (int right = offset; right + L <= n; right += L) {
            String word = s.substring(right, right + L);
            if (need.containsKey(word)) {
                window.merge(word, 1, Integer::sum);
                count++;
                while (window.get(word) > need.get(word)) {     // too many of this word
                    String leftWord = s.substring(left, left + L);
                    window.merge(leftWord, -1, Integer::sum);
                    left += L;
                    count--;
                }
                if (count == W) {                                // full match
                    res.add(left);
                    String leftWord = s.substring(left, left + L);
                    window.merge(leftWord, -1, Integer::sum);
                    left += L;
                    count--;
                }
            } else {                                             // foreign word: reset window
                window.clear();
                count = 0;
                left = right + L;
            }
        }
    }
    return res;
}
```

**Walkthrough.** `s="barfoothefoobarman"`, `words=["foo","bar"]`, L=3: offset 0 finds `barfoo` at index 0; later `foobar` at index 9 → `[0, 9]`.

**Complexity.** Time O(|s| · L) in the worst case of substring slicing (each of L offsets does an O(|s|/L) walk creating length-L substrings), effectively O(|s| · L); Space O(W · L) for the maps. **Edge cases:** duplicate words handled by counting multiplicity; a foreign word fully resets the window; `n < total` short-circuits to empty. **Follow-ups.** Words of differing lengths (becomes a much harder backtracking/DP problem); count matches only; stream `s` for online matching.

---

### Problem 46: Minimum Number of K Consecutive Bit Flips — Greedy + Difference Array

**Statement.** You may flip any `k` *consecutive* bits at a time. Return the minimum number of flips to turn the array (of `0`/`1`) into all `1`s, or `-1` if impossible.
**Constraints.** `1 ≤ k ≤ n ≤ 10⁵`, `nums[i] ∈ {0, 1}`.

**Approach.** Scan left to right; the leftmost `0` *must* be fixed by a flip starting exactly here (no earlier start remains, and a later start would leave it 0). This greedy choice is forced and optimal. The naive flip of `k` cells each time is O(n·k). The optimization tracks the **net parity of flips currently affecting index `i`** using a difference-array trick: a `flipped[i]` marker (set at the start, cleared `k` steps later) plus a running `current` parity tells us each cell's effective value in O(1), so the whole scan is O(n).

```java
public int minKBitFlips(int[] nums, int k) {
    int n = nums.length, flips = 0, current = 0;
    int[] diff = new int[n + 1];           // diff[i] toggles the active-flip parity at i
    for (int i = 0; i < n; i++) {
        current ^= diff[i];                // flips whose window ends here drop off
        if ((nums[i] ^ current) == 0) {    // effective value is 0 -> must flip starting at i
            if (i + k > n) return -1;      // window would run off the end
            flips++;
            current ^= 1;                  // this flip affects [i, i+k)
            diff[i + k] ^= 1;              // schedule its expiration
        }
    }
    return flips;
}
```

**Walkthrough.** `nums=[0,1,0]`, k=1: i=0 effective 0 → flip (flips=1), schedule expire at 1; i=1 current resets, value 1, ok; i=2 value 0 → flip (flips=2). Answer 2. `nums=[1,1,0]`, k=2: i=2 needs a flip but `2+2>3` → return −1.

**Complexity.** Time O(n), Space O(n) for the diff array (O(1) if you reuse `nums` to encode the marker). **Edge cases:** impossible tail (`i + k > n`) returns −1; the XOR parity correctly composes overlapping flips; already-all-ones returns 0. **Follow-ups.** Minimum flips with arbitrary target pattern; flip to make alternating; bulb-switching parity puzzles use the same difference-array parity trick.

---

### Problem 47: Maximum Sum of 3 Non-Overlapping Subarrays — Prefix Sums + DP Pointers

**Statement.** Given `nums` and a length `k`, find three non-overlapping subarrays each of length `k` with maximum total sum, and return their starting indices (lexicographically smallest on ties).
**Constraints.** `1 ≤ n ≤ 2·10⁴`, `1 ≤ k`, `3k ≤ n`.

**Approach.** First reduce each length-`k` window to a single value `win[i]` = sum of `nums[i..i+k)` via prefix sums in O(n). Now we must pick three indices `i < j < l` with `j ≥ i + k`, `l ≥ j + k`, maximizing `win[i] + win[j] + win[l]`. Precompute, for each position, the index of the **best window to the left** (`left[]`) and the **best window to the right** (`right[]`), each in one pass with lexicographic tie-breaking (strict `>` for left, `>=` for right). Then fix the *middle* window `j` and combine with the best left and right — O(n) total.

```java
public int[] maxSumOfThreeSubarrays(int[] nums, int k) {
    int n = nums.length, m = n - k + 1;
    long[] prefix = new long[n + 1];
    for (int i = 0; i < n; i++) prefix[i + 1] = prefix[i] + nums[i];
    long[] win = new long[m];
    for (int i = 0; i < m; i++) win[i] = prefix[i + k] - prefix[i];

    int[] left = new int[m];                 // best window index in [0..i]
    int best = 0;
    for (int i = 0; i < m; i++) {
        if (win[i] > win[best]) best = i;    // strict -> earliest on ties
        left[i] = best;
    }
    int[] right = new int[m];                // best window index in [i..m-1]
    best = m - 1;
    for (int i = m - 1; i >= 0; i--) {
        if (win[i] >= win[best]) best = i;   // >= -> earliest on ties when scanning back
        right[i] = best;
    }

    int[] ans = new int[]{-1, -1, -1};
    long max = Long.MIN_VALUE;
    for (int j = k; j <= m - 1 - k; j++) {        // middle start j leaves room for left & right
        int i = left[j - k], l = right[j + k];
        long total = win[i] + win[j] + win[l];
        if (total > max) { max = total; ans = new int[]{i, j, l}; }
    }
    return ans;
}
```

**Walkthrough.** `nums=[1,2,1,2,6,7,5,1]`, k=2: window sums `[3,3,3,8,13,12,6]`. Best triple is indices `0,3,5` with sums `3 + 8 + 12 = 23`.

**Complexity.** Time O(n), Space O(n). **Edge cases:** the strict-vs-`>=` comparison directions produce the lexicographically smallest tuple; `3k ≤ n` is guaranteed so a valid triple always exists; middle bounds `j ∈ [k, m−1−k]` keep room for left and right windows. **Follow-ups.** Generalize to `t` non-overlapping subarrays (2-D DP `dp[t][i]`); allow variable lengths; maximize with a gap constraint.

---

### Problem 48: Longest Duplicate Substring — Binary Search + Rabin–Karp

**Statement.** Given a string `s`, return any longest substring that appears at least twice in `s` (overlaps allowed); return `""` if none repeats.
**Constraints.** `2 ≤ |s| ≤ 3·10⁴`, lowercase English letters.

**Approach.** The answer length is **monotonic**: if a duplicate of length `L` exists, so does one of every length `< L` (a prefix of it). So binary-search the length `L` and, for each candidate, test "does some length-`L` substring repeat?" using **Rabin–Karp rolling hashes** stored in a `HashSet`. Computing all `n − L + 1` rolling hashes is O(n) per check; binary search adds a `log n` factor → O(n log n) average. We store the start index per hash and verify on collision to defend against false positives.

```java
public String longestDupSubstring(String s) {
    int n = s.length();
    long mod = (1L << 61) - 1, base = 256;   // large Mersenne-ish modulus
    int[] a = new int[n];
    for (int i = 0; i < n; i++) a[i] = s.charAt(i) - 'a' + 1;
    int lo = 1, hi = n - 1, start = -1, len = 0;
    while (lo <= hi) {
        int L = lo + (hi - lo) / 2;
        int pos = search(a, L, base, mod, s);
        if (pos >= 0) { start = pos; len = L; lo = L + 1; }  // found -> try longer
        else hi = L - 1;                                     // none -> try shorter
    }
    return start < 0 ? "" : s.substring(start, start + len);
}
private int search(int[] a, int L, long base, long mod, String s) {
    long hash = 0, power = 1;
    for (int i = 0; i < L; i++) hash = (hash * base + a[i]) % mod;   // hash of first window
    for (int i = 0; i < L - 1; i++) power = (power * base) % mod;    // base^(L-1) for removal
    Map<Long, List<Integer>> seen = new HashMap<>();
    seen.computeIfAbsent(hash, x -> new ArrayList<>()).add(0);
    for (int i = 1; i + L <= a.length; i++) {
        hash = (hash - a[i - 1] * power % mod + mod) % mod;   // drop left char
        hash = (hash * base + a[i + L - 1]) % mod;            // add right char
        List<Integer> bucket = seen.get(hash);
        if (bucket != null) {
            for (int j : bucket) if (s.regionMatches(j, s, i, L)) return i; // verify
        }
        seen.computeIfAbsent(hash, x -> new ArrayList<>()).add(i);
    }
    return -1;
}
```

**Walkthrough.** `s="banana"`: binary search finds length 3 works (`"ana"` at indices 1 and 3), length 4 fails → answer `"ana"`.

**Complexity.** Time O(n log n) average (each binary-search step is an O(n) rolling-hash scan; verification cost is amortized small), Space O(n). **Edge cases:** no repeat returns `""`; overlapping duplicates like `"aaaa"` are allowed (different start indices); collision verification via `regionMatches` guards against hash false positives. **Follow-ups.** Use a suffix automaton / suffix array + LCP for a deterministic O(n) or O(n log n) without hashing; double hashing to make collisions astronomically unlikely; longest substring repeated at least `t` times.

---

### Problem 49: Split Array Largest Sum — Binary Search on the Answer

**Statement.** Split `nums` into `m` non-empty contiguous subarrays so that the largest subarray sum is minimized; return that minimized largest sum.
**Constraints.** `1 ≤ n ≤ 1000`, `1 ≤ m ≤ n`, `0 ≤ nums[i] ≤ 10⁶`.

**Approach.** A 2-D DP over `(prefix, splits)` is O(n²·m). The optimal "binary search on the answer" exploits monotonicity: for a candidate cap `X`, the minimum number of pieces needed (greedily starting a new piece whenever adding the next element would exceed `X`) is a *non-increasing* function of `X`. So binary-search `X` in `[max(nums), sum(nums)]`: if the greedy piece count `≤ m`, the cap is feasible and we try smaller; otherwise larger. The lower bound is `max(nums)` (no single element can be split) and the upper bound is the total sum (one piece).

```java
public int splitArray(int[] nums, int m) {
    long lo = 0, hi = 0;
    for (int x : nums) { lo = Math.max(lo, x); hi += x; }   // [maxElem, totalSum]
    while (lo < hi) {
        long mid = lo + (hi - lo) / 2;
        if (piecesNeeded(nums, mid) <= m) hi = mid;          // feasible -> shrink cap
        else lo = mid + 1;                                   // infeasible -> raise cap
    }
    return (int) lo;
}
private int piecesNeeded(int[] nums, long cap) {
    int pieces = 1;
    long running = 0;
    for (int x : nums) {
        if (running + x > cap) { pieces++; running = x; }    // start a new piece
        else running += x;
    }
    return pieces;
}
```

**Walkthrough.** `nums=[7,2,5,10,8]`, m=2: search in `[10, 32]`. Cap 18 needs pieces `[7,2,5],[10,8]` = 2 ≤ 2 feasible; cap 17 needs 3 pieces → infeasible. Converges to 18.

**Complexity.** Time O(n · log(sum)), Space O(1). **Edge cases:** `m == 1` returns the total sum; `m == n` returns `max(nums)`; zeros are absorbed without forcing new pieces. **Follow-ups.** Capacity to ship packages within D days (identical template); koko eating bananas; minimize the maximum distance to gas station (binary search on a real-valued answer).

---

### Problem 50: Minimum Window Subsequence — Two-Pass Sweep DP

**Statement.** Given strings `s` and `t`, find the minimum-length contiguous substring `w` of `s` such that `t` is a **subsequence** of `w` (characters in order, not necessarily contiguous). Return `""` if none exists; return the leftmost on ties.
**Constraints.** `1 ≤ |s| ≤ 2·10⁴`, `1 ≤ |t| ≤ 100`.

**Approach.** This is subtly harder than Minimum Window *Substring* (Problem 27) because order matters and characters needn't be adjacent — a frequency window doesn't apply. The elegant O(|s|·|t|) sweep: walk a pointer forward through `s` matching `t` character by character; when the last char of `t` is matched (a forward match found at some `end`), **walk backward** from `end`, matching `t` in reverse, to find the tightest `start` for this match. Record the window if it's the shortest so far, then restart the forward scan from `start + 1`. Each forward match triggers one backward tightening, giving the optimal leftmost minimum window.

```
s = a b c d e b d d e  ,  t = b d e
forward: match b(1) d(3) e(4)  -> end=4
backward from 4: e(4) d(3) b(1) -> start=1  -> window s[1..4]="bcde" len4
restart forward at 2 ... a shorter window "bdde"? -> tracks the minimum
```

```java
public String minWindow(String s, String t) {
    int n = s.length(), m = t.length();
    int bestStart = -1, bestLen = Integer.MAX_VALUE;
    int i = 0;                                   // pointer in s
    while (i < n) {
        int ti = 0;                              // pointer in t (forward)
        int j = i;
        while (j < n) {                          // forward match
            if (s.charAt(j) == t.charAt(ti)) {
                ti++;
                if (ti == m) break;              // matched all of t, end = j
            }
            j++;
        }
        if (ti < m) break;                       // no further full match possible
        int end = j;                             // inclusive end of this match
        int k = m - 1;
        while (true) {                           // backward tighten to leftmost start
            if (s.charAt(j) == t.charAt(k)) { if (k == 0) break; k--; }
            j--;
        }
        int start = j;                           // tightest start for this end
        if (end - start + 1 < bestLen) {
            bestLen = end - start + 1;
            bestStart = start;
        }
        i = start + 1;                           // restart just after this start
    }
    return bestStart == -1 ? "" : s.substring(bestStart, bestStart + bestLen);
}
```

**Walkthrough.** `s="abcdebdde"`, `t="bde"`: first forward match ends at index 4 (`b..d..e`), backward tighten gives `"bcde"` (len 4); restarting finds `"bdde"` (indices 5–8, len 4) — leftmost minimum is `"bcde"`.

**Complexity.** Time O(|s| · |t|) worst case (each forward match may rescan), Space O(1). **Edge cases:** `t` not a subsequence anywhere returns `""`; restarting at `start + 1` (not `end + 1`) is essential to catch overlapping shorter windows; equal-length ties keep the leftmost because we only replace on strictly shorter. **Follow-ups.** DP table `dp[i][j]` = earliest start matching `t[0..j]` ending at `s[i]` for an alternative O(|s|·|t|) formulation; shortest supersequence; count distinct windows.

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
