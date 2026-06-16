# Sliding Window & Two Pointers

Two of the highest-leverage linear-time techniques in coding interviews. Almost any prompt asking for the *longest*, *shortest*, *best*, or *count of* contiguous subarrays/substrings — or for pairs/triples in a sorted array — collapses from O(n²)/O(n³) brute force to O(n) once you spot the pattern.

[← Back to master index](../README.md) | [← DSA index](README.md)

---

## Concept & Intuition

Both techniques exploit the same idea: instead of recomputing an answer from scratch for every candidate range, you **maintain a small amount of state and slide indices**, reusing the work you already did. Each index moves monotonically, so the total work is bounded by the number of moves — linear, not quadratic.

### Two pointers

You keep two indices and move them according to a rule. There are two flavors:

- **Opposite ends (converging).** Start `lo = 0`, `hi = n-1` and walk toward the middle. Works when the array is **sorted** or when a decision at the ends is unambiguous (e.g. Container With Most Water, two-sum on a sorted array, palindrome check). Invariant: *everything outside `[lo, hi]` has already been decided and can never be the answer.*
- **Fast / slow (same direction).** Both pointers start near the left; one (`fast`/`read`) scans ahead, the other (`slow`/`write`) lags behind marking a boundary. This is the engine behind in-place array compaction (remove duplicates) and cycle detection in linked lists (Floyd's tortoise & hare). Invariant: *`[0, slow)` is the finished/answer region.*

### Sliding window

A sliding window is a *same-direction two-pointer* specialization where the region `[left, right]` is **contiguous** and you maintain a running property (a sum, a character-frequency map, a distinct-count, etc.).

- **Fixed window** — the width `k` is given. Slide one step at a time: add `arr[right]`, remove `arr[right-k]`. O(1) per step.
- **Variable window** — the width changes to keep an *invariant* satisfied. Expand `right` to include more; when the window becomes **invalid**, shrink from `left` until it is valid again (or, for "exactly k" counting problems, until the constraint is met). The key insight: because `left` only ever moves forward, the inner `while` loop is amortized O(1) per element — the whole scan is O(n) even though it looks nested.

The decisive question that picks fixed vs variable: **"Is the window size handed to me, or do I have to discover it?"**

```
 Variable window — "longest substring without repeating chars" on "abcabb"

 index:   0   1   2   3   4   5
 char :  [a   b   c   a   b   b]
          L           R            "abca"  -> dup 'a' at idx0; shrink L
              L       R            "bca"   -> valid, len 3  (best so far)
              L           R        "bcab"  -> dup 'b' at idx1; shrink L
                  L       R        "cab"   -> valid, len 3
                  L           R    "cabb"  -> dup 'b'; shrink L past idx4
                          L   R    "bb" -> dup; shrink; "b" valid
```

```
 Two pointers, opposite ends — Container With Most Water

 height: [1  8  6  2  5  4  8  3  7]
          L                       R     area = min(1,7)*8 = 8  ; move L (shorter)
             L                    R     area = min(8,7)*7 = 49 ; move R (shorter)
             ...                        keep moving the shorter wall inward
```

**Why moving the shorter wall is safe:** the area is bounded by the shorter wall. Moving the taller wall in can only keep or shrink the width *and* never raise the limiting height, so it can never improve the answer — therefore the shorter wall is the only move that can.

### When to use it

- Contiguous subarray/substring + an optimization or count → **sliding window**.
- Sorted array + find pair/triple summing to a target → **two pointers, opposite ends**.
- Compact / dedupe / partition an array in place with O(1) space → **fast/slow pointers**.

---

## Complexity Cheat-Sheet

| Operation / Pattern | Time | Space | Notes |
|---|---|---|---|
| Fixed-window scan | O(n) | O(1)–O(k) | Add right, drop left each step |
| Variable-window scan | O(n) | O(1)–O(Σ) | `Σ` = alphabet / distinct keys |
| Two pointers, opposite ends | O(n) | O(1) | Often after an O(n log n) sort |
| Fast/slow in-place compaction | O(n) | O(1) | Write index lags read index |
| 3-Sum (sort + two pointers) | O(n²) | O(1)–O(n) | n outer × n inner |
| 4-Sum (sort + two pointers) | O(n³) | O(1)–O(n) | Two fixed loops + two pointers |
| Subarray product < k | O(n) | O(1) | Each index enters/leaves once |
| Min window substring | O(n + m) | O(Σ) | `m` = pattern length |
| Sort prerequisite (if needed) | O(n log n) | O(log n)–O(n) | Comparison sort dominates |

The recurring theme: **each pointer moves at most `n` times across the entire run**, so even nested-looking code is linear (or, for k-Sum, the outer fixed loops multiply it).

---

## Patterns & Recognition

Train yourself to map prompt phrasing to technique:

| Phrase in the prompt | Technique | Window type |
|---|---|---|
| "subarray/substring of size **k**" (avg, max sum) | Sliding window | Fixed |
| "**longest** substring/subarray such that …" | Sliding window | Variable (maximize while valid) |
| "**shortest/minimum** subarray such that …" | Sliding window | Variable (minimize once valid) |
| "**count** subarrays where …" | Sliding window | Variable (`right-left+1` trick) |
| "**at most k** distinct / replacements / zeros" | Sliding window | Variable |
| "sorted array, find pair/triple summing to T" | Two pointers | Opposite ends |
| "max area / most water / trap rain" | Two pointers | Opposite ends |
| "remove/move/dedupe **in place**, O(1) space" | Two pointers | Fast/slow |
| "cycle in linked list / find middle" | Two pointers | Fast/slow (Floyd) |

**Recognition heuristics**

1. *Contiguity* is the tell for windows — if you can reorder elements freely, a window probably won't help (reach for sorting/hashing instead).
2. *Monotonic feasibility* enables the window: if a window of size `w` is valid, is every smaller window also valid (or every larger one invalid)? If yes, expanding/shrinking is sound.
3. *"Sorted"* in the constraints is a loud hint for opposite-ends two pointers — the order lets you decide which pointer to move from the comparison alone.
4. The **"count subarrays" → "at most" decomposition**: `exactly(k) = atMost(k) − atMost(k−1)`. Counting "at most k" with a window is usually trivial; counting "exactly k" directly is not.

---

## Coding Problems

### Problem 1: Maximum Average Subarray I (fixed window)

> Given an integer array `nums` and an integer `k`, find the contiguous subarray of length `k` with the maximum average and return that average. Constraints: `1 ≤ k ≤ n ≤ 10^5`, `-10^4 ≤ nums[i] ≤ 10^4`.

**Approach.** Brute force computes the sum of every length-`k` window separately: O(n·k). The fixed-window insight is that adjacent windows overlap in `k-1` elements, so each slide is just `+nums[right] − nums[right-k]`, giving O(n).

```java
class Solution {
    public double findMaxAverage(int[] nums, int k) {
        long sum = 0;
        for (int i = 0; i < k; i++) sum += nums[i];   // first window
        long best = sum;
        for (int right = k; right < nums.length; right++) {
            sum += nums[right] - nums[right - k];      // slide by one
            best = Math.max(best, sum);
        }
        return (double) best / k;
    }
}
```

**Dry run** on `nums=[1,12,-5,-6,50,3], k=4`: first window sum `1+12-5-6=2`. Slide → `2 +50 -1 = 51`. Slide → `51 +3 -12 = 42`. Best = 51, average `51/4 = 12.75`.

**Time:** O(n). **Space:** O(1).

**Follow-ups.** "Now find the *smallest* window whose sum ≥ target" (switch to variable). "What if `k` queries arrive online?" (precompute prefix sums for O(1) per query). Note the `long` accumulator to dodge overflow when values and `k` are large.

---

### Problem 2: Remove Duplicates from Sorted Array (fast/slow)

> Given a **sorted** array `nums`, remove duplicates in place so each element appears once, return the new length `k`, and the first `k` elements must hold the result. O(1) extra space.

**Approach.** Brute force builds a new array or set — but that violates the O(1)-space rule. With fast/slow pointers, `slow` marks the last unique slot written; `fast` scans ahead. Whenever `nums[fast] != nums[slow]`, we found a new value, so advance `slow` and copy.

```java
class Solution {
    public int removeDuplicates(int[] nums) {
        if (nums.length == 0) return 0;
        int slow = 0;                              // [0..slow] are unique
        for (int fast = 1; fast < nums.length; fast++) {
            if (nums[fast] != nums[slow]) {
                slow++;
                nums[slow] = nums[fast];
            }
        }
        return slow + 1;
    }
}
```

**Dry run** on `[0,0,1,1,1,2]`: fast hits `1` (≠0) → slow=1, nums=[0,1,...]; skips the next `1`s; fast hits `2` → slow=2, nums=[0,1,2,...]. Returns 3.

**Time:** O(n). **Space:** O(1).

**Follow-ups.** "Allow each element **at most twice**" (compare `nums[fast]` against `nums[slow-1]`). "Remove all instances of a given `val`" (Remove Element — same skeleton, condition `nums[fast] != val`). "Move all zeros to the end" (write non-zeros, then fill).

---

### Problem 3: Max Consecutive Ones III (variable window, "at most k")

> Given a binary array `nums` and an integer `k`, you may flip at most `k` zeros to ones. Return the length of the longest run of consecutive 1s achievable.

**Approach.** Brute force tries every start and counts how far you can extend while flips ≤ k: O(n²). The window invariant is "**at most `k` zeros inside `[left, right]`**". Expand `right`; if zeros exceed `k`, shrink `left` until valid again. The maximum window width seen is the answer.

```java
class Solution {
    public int longestOnes(int[] nums, int k) {
        int left = 0, zeros = 0, best = 0;
        for (int right = 0; right < nums.length; right++) {
            if (nums[right] == 0) zeros++;
            while (zeros > k) {                 // window invalid: shrink
                if (nums[left] == 0) zeros--;
                left++;
            }
            best = Math.max(best, right - left + 1);
        }
        return best;
    }
}
```

**Dry run** on `nums=[1,1,0,0,1,1,1,0,1], k=2`: window grows to `[1,1,0,0,1,1,1]` (2 zeros, len 7). At idx7 a 3rd zero forces `left` past the first zero (now 2 zeros), window `[0,1,1,1,0,1]`. Best stays 7... continuing yields max 7? Actually extending to idx8 keeps 2 zeros from idx3: best becomes `right(8)-left(3)+1=6`. Maximum is **7**.

**Time:** O(n). **Space:** O(1).

**Follow-ups.** "Max Consecutive Ones I" is the `k=0` special case. "Longest subarray of 1s after deleting exactly one element" (`k=1`, but subtract one because a deletion is mandatory). This same "at most k bad elements" skeleton solves dozens of LeetCode mediums.

---

### Problem 4: Longest Substring Without Repeating Characters

> Given a string `s`, return the length of the longest substring with no repeating characters. `0 ≤ s.length ≤ 5·10^4`.

**Approach.** Brute force checks all substrings for uniqueness: O(n³) (or O(n²) with a per-start set). The window invariant is "**all characters in `[left, right]` are distinct**". Keep a map of `char → last index`. When the incoming char was seen *inside the current window*, jump `left` to just past its previous position.

```java
class Solution {
    public int lengthOfLongestSubstring(String s) {
        int[] last = new int[128];
        java.util.Arrays.fill(last, -1);
        int left = 0, best = 0;
        for (int right = 0; right < s.length(); right++) {
            char c = s.charAt(right);
            if (last[c] >= left) {            // duplicate within window
                left = last[c] + 1;
            }
            last[c] = right;
            best = Math.max(best, right - left + 1);
        }
        return best;
    }
}
```

**Dry run** on `"abcabcbb"`: window grows `a,ab,abc` (len 3). At idx3 `a` (last=0 ≥ left) → left=1, window `bca`. Continues at len 3 each time `bcb` collapses; answer **3**.

**Time:** O(n) — each index processed once. **Space:** O(min(n, Σ)) for the alphabet.

**Follow-ups.** "Longest substring with **at most k distinct** characters" (track a frequency map, shrink when `map.size() > k`). "Longest substring with **exactly k distinct**" (use the at-most decomposition). "Return the substring itself" (track the best `left`).

---

### Problem 5: Subarray Product Less Than K

> Count the number of contiguous subarrays where the product of all elements is **strictly less than** `k`. `nums[i] ≥ 1`, `0 ≤ k ≤ 10^6`.

**Approach.** Brute force enumerates all subarrays and multiplies: O(n²). Because every element is ≥ 1, the product is monotonic as the window grows — so a window works. Maintain a running product; while it's ≥ `k`, divide out `nums[left]` and advance `left`. **The counting trick:** when the window `[left, right]` is valid, it contributes `right - left + 1` new subarrays — all the subarrays *ending at `right`*.

```java
class Solution {
    public int numSubarrayProductLessThanK(int[] nums, int k) {
        if (k <= 1) return 0;                 // no positive product < 1 possible
        int left = 0, count = 0;
        long prod = 1;
        for (int right = 0; right < nums.length; right++) {
            prod *= nums[right];
            while (prod >= k) {               // shrink until valid
                prod /= nums[left];
                left++;
            }
            count += right - left + 1;        // subarrays ending at right
        }
        return count;
    }
}
```

**Dry run** on `nums=[10,5,2,6], k=100`: right=0 prod=10 → +1. right=1 prod=50 → +2 (`[10,5],[5]`). right=2 prod=100 ≥100 → drop 10 (prod=10), +2. right=3 prod=60 → +3. Total **8**.

**Time:** O(n). **Space:** O(1).

**Follow-ups.** "Why does this break with zeros or negatives?" — monotonicity is lost; you'd need prefix/log tricks or a different method. "Count subarrays with **sum** less than k for positive integers" is the additive twin of this problem.

---

### Problem 6: Fruit Into Baskets (longest subarray with ≤ 2 distinct)

> You have two baskets, each holding one type of fruit, and you pick fruit moving right starting from any tree. `fruits[i]` is the type of tree `i`. Return the maximum number of fruits you can collect — i.e. the longest subarray containing **at most 2 distinct values**.

**Approach.** This is the classic "at most k distinct" window with `k = 2`. Maintain a `type → count` map. Expand `right`; if the map exceeds 2 keys, shrink `left`, decrementing counts and removing keys that hit zero.

```java
class Solution {
    public int totalFruit(int[] fruits) {
        java.util.Map<Integer, Integer> count = new java.util.HashMap<>();
        int left = 0, best = 0;
        for (int right = 0; right < fruits.length; right++) {
            count.merge(fruits[right], 1, Integer::sum);
            while (count.size() > 2) {                 // too many types
                int f = fruits[left];
                if (count.merge(f, -1, Integer::sum) == 0) count.remove(f);
                left++;
            }
            best = Math.max(best, right - left + 1);
        }
        return best;
    }
}
```

**Dry run** on `[1,2,3,2,2]`: window `[1,2]` len2; adding `3` → 3 types, shrink past `1` → `[2,3]`; then `2,2` extend → `[3,2,2]` len 3? window is `[2,3,2,2]`? After removing type 1, left points at index1 (value2): window `2,3,2,2` has types {2,3}=2, len **4**. Answer 4.

**Time:** O(n). **Space:** O(1) (map holds ≤ 3 keys transiently).

**Follow-ups.** Generalize to "at most k distinct" by parameterizing the `2`. "What if baskets are unlimited but each can hold only `c` fruits?" becomes a 2-D constraint. Interviewers love asking you to *recognize* this is the same problem as Longest Substring with At Most K Distinct.

---

### Problem 7: Longest Repeating Character Replacement

> Given a string `s` of uppercase letters and an integer `k`, you may replace **at most `k`** characters with any letter. Return the length of the longest substring of a single repeated letter achievable.

**Approach.** A window `[left, right]` is *valid* if `(windowLength − countOfMostFrequentChar) ≤ k` — i.e. the non-majority chars (which we'd have to replace) are within budget. Track a 26-letter frequency array and `maxFreq`. **Subtlety:** we never need to decrease `maxFreq` when shrinking; a stale-high `maxFreq` only means we won't *grow* the best, which is fine because `best` already captured the larger window. This keeps it O(n) with no recomputation.

```java
class Solution {
    public int characterReplacement(String s, int k) {
        int[] freq = new int[26];
        int left = 0, maxFreq = 0, best = 0;
        for (int right = 0; right < s.length(); right++) {
            int c = s.charAt(right) - 'A';
            freq[c]++;
            maxFreq = Math.max(maxFreq, freq[c]);
            if ((right - left + 1) - maxFreq > k) {   // too many to replace
                freq[s.charAt(left) - 'A']--;
                left++;                                // window slides, size never shrinks
            }
            best = Math.max(best, right - left + 1);
        }
        return best;
    }
}
```

**Dry run** on `s="AABABBA", k=1`: window grows to `AABA` (maxFreq A=3, len4, replacements 1 ≤ k) → best 4. At idx4 `B`, `AABAB` needs 2 replacements > 1 → slide left, window stays size 4. Continues; answer **4**.

**Time:** O(n). **Space:** O(1) (26 letters).

**Follow-ups.** "Why use `if` not `while`?" — because the window only ever slides (never shrinks), the answer can only come from a window at least as large as the current best, so a single slide per step suffices. "Generalize beyond 26 letters" → swap the array for a HashMap. "Return the actual substring."

---

### Problem 8: Two Sum II — Input Array Is Sorted (opposite ends)

> Given a **1-indexed sorted** array `numbers` and a `target`, return the two indices (1-based) whose values sum to `target`. Exactly one solution exists; O(1) extra space.

**Approach.** Brute force is O(n²); binary search per element is O(n log n). The opposite-ends two-pointer exploits the sort: if `nums[lo]+nums[hi] < target` the sum is too small, so the *only* way to increase it is `lo++`; if too large, `hi--`. Each step eliminates one candidate definitively.

```java
class Solution {
    public int[] twoSum(int[] numbers, int target) {
        int lo = 0, hi = numbers.length - 1;
        while (lo < hi) {
            int sum = numbers[lo] + numbers[hi];
            if (sum == target) return new int[]{lo + 1, hi + 1};
            if (sum < target) lo++;            // need a bigger value
            else hi--;                          // need a smaller value
        }
        return new int[]{-1, -1};              // unreachable per constraints
    }
}
```

**Dry run** on `[2,7,11,15], target=9`: `2+15=17>9` → hi--; `2+11=13>9` → hi--; `2+7=9` → return `[1,2]`.

**Time:** O(n). **Space:** O(1).

**Follow-ups.** "Array is **not** sorted" → use a HashMap (classic Two Sum, O(n) time / O(n) space). "Find **all** pairs / count pairs summing to target" (advance both pointers past duplicates). This is the building block for 3-Sum and 4-Sum below.

---

### Problem 9: Container With Most Water (opposite ends, greedy)

> Given `height[]` where each element is a vertical line, find two lines that with the x-axis form a container holding the most water. Return that maximum area. `2 ≤ n ≤ 10^5`.

**Approach.** Brute force checks all pairs: O(n²). Two pointers at the ends: `area = min(height[lo], height[hi]) * (hi − lo)`. Always move the **shorter** wall inward — moving the taller one can't raise the limiting height and only shrinks width, so it can never beat the current best.

```java
class Solution {
    public int maxArea(int[] height) {
        int lo = 0, hi = height.length - 1, best = 0;
        while (lo < hi) {
            int h = Math.min(height[lo], height[hi]);
            best = Math.max(best, h * (hi - lo));
            if (height[lo] < height[hi]) lo++; // discard the shorter wall
            else hi--;
        }
        return best;
    }
}
```

**Dry run** on `[1,8,6,2,5,4,8,3,7]`: `min(1,7)*8=8`, move lo; `min(8,7)*7=49`, move hi; subsequent areas stay ≤ 49. Answer **49**.

**Time:** O(n). **Space:** O(1).

**Follow-ups.** "Trapping Rain Water" is the harder cousin — same opposite-ends idea but you accumulate trapped water using `leftMax`/`rightMax`. "Prove the greedy is optimal" (the exchange argument above is the expected answer). "Ties — which wall to move?" (either works; the optimum can't lie strictly inside both equal walls).

---

### Problem 10: 3-Sum (sort + two pointers)

> Return **all unique triplets** `[a, b, c]` from `nums` with `a + b + c = 0`. `3 ≤ n ≤ 3000`.

**Approach.** Brute force over all triples is O(n³) plus dedup pain. Sort the array (O(n log n)); fix index `i`, then run an opposite-ends two-pointer (`lo`, `hi`) over the remainder to find pairs summing to `-nums[i]`. Sorting makes duplicate-skipping trivial: skip equal `i`, and after a hit skip equal `lo`/`hi`.

```java
class Solution {
    public java.util.List<java.util.List<Integer>> threeSum(int[] nums) {
        java.util.Arrays.sort(nums);
        java.util.List<java.util.List<Integer>> res = new java.util.ArrayList<>();
        int n = nums.length;
        for (int i = 0; i < n - 2; i++) {
            if (nums[i] > 0) break;                       // all later are bigger
            if (i > 0 && nums[i] == nums[i - 1]) continue; // skip dup pivot
            int lo = i + 1, hi = n - 1;
            while (lo < hi) {
                int sum = nums[i] + nums[lo] + nums[hi];
                if (sum == 0) {
                    res.add(java.util.Arrays.asList(nums[i], nums[lo], nums[hi]));
                    while (lo < hi && nums[lo] == nums[lo + 1]) lo++; // skip dups
                    while (lo < hi && nums[hi] == nums[hi - 1]) hi--;
                    lo++; hi--;
                } else if (sum < 0) lo++;
                else hi--;
            }
        }
        return res;
    }
}
```

**Dry run** on `[-1,0,1,2,-1,-4]` → sorted `[-4,-1,-1,0,1,2]`: pivot `-4` finds nothing; pivot `-1` (idx1) → `lo,hi` find `(-1,0,1)`; pivot `-1` (idx2) skipped as dup of finding `(−1,2,−1)`? actually yields `[-1,-1,2]`. Result `[[-1,-1,2],[-1,0,1]]`.

**Time:** O(n²). **Space:** O(1) extra (ignoring output and sort stack).

**Follow-ups.** "3-Sum Closest" (track the best `|sum − target|` instead of equality). "3-Sum Smaller" (count triplets `< target` using the product-style window count). Leads directly to k-Sum.

---

### Problem 11: 4-Sum / k-Sum (generalized, senior-level)

> Return all **unique quadruplets** summing to `target`. Then generalize to arbitrary `k`. Watch for overflow — `target` and elements can each reach `10^9`.

**Approach.** Sort, then recurse: `kSum` fixes one index and calls `(k-1)Sum` on the suffix until the base case `k == 2`, which is the opposite-ends two-pointer. Each recursion level adds an O(n) loop, so 4-Sum is O(n³), and general k-Sum is O(n^{k-1}). Two senior-level details: **(1) prune** — if the smallest possible sum at this level already exceeds target, or the largest can't reach it, break early; **(2) use `long`** to avoid 32-bit overflow.

```java
class Solution {
    public java.util.List<java.util.List<Integer>> fourSum(int[] nums, int target) {
        java.util.Arrays.sort(nums);
        return kSum(nums, target, 0, 4);
    }

    private java.util.List<java.util.List<Integer>> kSum(int[] nums, long target, int start, int k) {
        java.util.List<java.util.List<Integer>> res = new java.util.ArrayList<>();
        int n = nums.length;
        if (start == n) return res;
        // pruning: even the smallest/largest k picks can't reach target
        long avg = target / k;
        if (nums[start] > avg || nums[n - 1] < avg) return res;

        if (k == 2) return twoSum(nums, target, start);

        for (int i = start; i < n - k + 1; i++) {
            if (i > start && nums[i] == nums[i - 1]) continue;       // skip dup
            for (java.util.List<Integer> sub : kSum(nums, target - nums[i], i + 1, k - 1)) {
                java.util.List<Integer> quad = new java.util.ArrayList<>();
                quad.add(nums[i]);
                quad.addAll(sub);
                res.add(quad);
            }
        }
        return res;
    }

    private java.util.List<java.util.List<Integer>> twoSum(int[] nums, long target, int start) {
        java.util.List<java.util.List<Integer>> res = new java.util.ArrayList<>();
        int lo = start, hi = nums.length - 1;
        while (lo < hi) {
            long sum = (long) nums[lo] + nums[hi];
            if (sum < target || (lo > start && nums[lo] == nums[lo - 1])) lo++;
            else if (sum > target || (hi < nums.length - 1 && nums[hi] == nums[hi + 1])) hi--;
            else {
                res.add(new java.util.ArrayList<>(java.util.Arrays.asList(nums[lo++], nums[hi--])));
            }
        }
        return res;
    }
}
```

**Dry run** on `nums=[1,0,-1,0,-2,2], target=0` → sorted `[-2,-1,0,0,1,2]`: fix `-2`, fix `-1` → twoSum on `[0,0,1,2]` for target 3 → `(1,2)` ⇒ `[-2,-1,1,2]`; fix `-2`, fix `0` → twoSum target 2 → `(0,2)` ⇒ `[-2,0,0,2]`; and so on. Result includes `[[-2,-1,1,2],[-2,0,0,2],[-1,0,0,1]]`.

**Time:** O(n^{k−1}) (4-Sum = O(n³)). **Space:** O(k) recursion depth + output.

**Follow-ups.** "Why `long` in twoSum?" — `nums[lo] + nums[hi]` can overflow `int`. "Could a HashMap beat this?" — for fixed small k, the sort + pointers approach is cleaner and matches the O(n^{k−1}) lower bound for k-Sum without hashing's worst-case blowup. "Count distinct quadruplets only" vs "all index tuples" changes the dedup strategy.

---

### Problem 12: Minimum Window Substring (hard)

> Given strings `s` and `t`, return the **smallest** substring of `s` containing every character of `t` (including multiplicities). If none exists, return `""`. `1 ≤ s.length, t.length ≤ 10^5`.

**Approach.** Brute force checks every substring against `t`'s multiset: O(n²·Σ). The variable window here *expands to become valid, then contracts to become minimal*. Keep `need[]` (required counts) and a `missing` counter of characters still owed. Expand `right`, decrementing `need`; when `missing == 0` the window is valid, so contract `left` as far as possible while staying valid, recording the smallest window seen.

```java
class Solution {
    public String minWindow(String s, String t) {
        if (s.length() < t.length()) return "";
        int[] need = new int[128];
        for (char c : t.toCharArray()) need[c]++;
        int missing = t.length();
        int left = 0, bestLen = Integer.MAX_VALUE, bestStart = 0;

        for (int right = 0; right < s.length(); right++) {
            char c = s.charAt(right);
            if (need[c]-- > 0) missing--;                 // this char was needed
            while (missing == 0) {                        // window valid: try to shrink
                if (right - left + 1 < bestLen) {
                    bestLen = right - left + 1;
                    bestStart = left;
                }
                char lc = s.charAt(left++);
                if (++need[lc] > 0) missing++;            // gave one back → invalid
            }
        }
        return bestLen == Integer.MAX_VALUE ? "" : s.substring(bestStart, bestStart + bestLen);
    }
}
```

**Dry run** on `s="ADOBECODEBANC", t="ABC"`: first valid window `ADOBEC` (len6); shrinks but `A` is essential so left stops at A. Later `CODEBA...NC` yields `BANC` (len4), then the minimal `BANC`. Answer `"BANC"`.

The dual-counter trick (`need[c]-- > 0` and `++need[lc] > 0`) lets the **same array** track both surplus and deficit: positive `need` means still owed, zero/negative means satisfied or surplus. That is why it's O(n) with O(Σ) space rather than re-scanning.

**Time:** O(n + m). **Space:** O(Σ) (128 for ASCII, or the distinct chars of `t`).

**Follow-ups.** "Smallest window containing all **distinct** chars of `s` itself." "Permutation in String / Find All Anagrams" are the *fixed*-window siblings (window size = `t.length()`, check exact match). "Stream version where `s` arrives one char at a time" forces you to keep the incremental counters — exactly what this design already does.

---

## Interview Q&A by Level

### 🟢 Basic

**Q: What is the core difference between the two-pointer and sliding-window techniques?**
Sliding window is a *special case* of two pointers where both indices move in the same direction and the region between them is a **contiguous** subarray/substring whose property you maintain. General two pointers (opposite ends) need not bound a contiguous "active" region you're optimizing — they're often partitioning or pairing.

**Q: How do you decide between a fixed and a variable window?**
If the problem hands you the window size `k`, use a fixed window (slide by adding the entering and removing the leaving element). If you must *discover* the size that satisfies some property ("longest/shortest/at most k …"), use a variable window that expands and contracts.

**Q: Why is a sliding-window scan O(n) even though it has a nested loop?**
Because `left` and `right` each advance at most `n` times across the whole run and never move backward. The inner `while` is amortized — total inner iterations across all outer steps is bounded by `n`, not multiplied by it.

### 🟡 Intermediate

**Q: For Container With Most Water, why is moving the shorter wall always correct?**
The area is `min(left, right) × width`. Moving the taller wall keeps the limiting (shorter) height the same or lower while strictly reducing width, so it can never improve the area. Moving the shorter wall is the only move that could raise the limiting height, so it's the sole candidate for improvement — an exchange/greedy argument.

**Q: Explain the `exactly(k) = atMost(k) − atMost(k−1)` trick.**
Counting subarrays with *exactly* k distinct elements (or k odd numbers, etc.) directly is awkward, but counting *at most* k with a window is easy: each valid window ending at `right` contributes `right−left+1` subarrays. So compute `atMost(k)` and `atMost(k−1)` with two window passes and subtract.

**Q: In Longest Repeating Character Replacement, why isn't `maxFreq` recomputed when shrinking?**
The window never shrinks below the best size already found; it only slides. A stale (too-high) `maxFreq` can only *prevent* growth, never produce an over-count, and `best` already recorded any larger valid window. So skipping the recompute is safe and keeps it O(n).

### 🟠 Advanced

**Q: Give the amortized-analysis argument for variable windows precisely.**
Use the accounting method: charge each element 2 credits when `right` includes it — 1 pays for its inclusion, 1 is saved to pay for its eventual removal by `left`. Since `left` removes each element at most once, total work is ≤ 2n = O(n). No element is paid for twice.

**Q: When does a sliding window *fail*, and what's the prerequisite that makes it valid?**
It requires **monotonic feasibility**: extending the window must move the property in one consistent direction (e.g. product/sum only grows with non-negative elements; distinct-count only grows or stays). Subarray-product-less-than-k breaks with zeros/negatives because the product is no longer monotonic — the shrink condition becomes meaningless.

**Q: Real-world systems that use these patterns?**
Fixed windows power **streaming rate limiters** (requests in the last `k` seconds) and moving averages in time-series/telemetry. Variable windows underlie **TCP's sliding-window flow control** and log/anomaly detectors ("longest span under threshold"). Fast/slow pointers detect cycles in linked structures and back GC reachability scans. k-Sum-style pointer sweeps appear in computational-geometry sweeps.

### 🔴 Expert

**Q: How would you scale "longest substring with ≤ k distinct chars" to a stream of billions of events that doesn't fit in memory?**
A single forward pass with a frequency map and two cursors is already streaming-friendly *if* the window fits in memory. If even the window is too large, you bound memory with a **count-min sketch** or approximate distinct-counter (HyperLogLog) for the distinct-count test, accepting bounded error, and you checkpoint window boundaries to disk. The pointers themselves stay O(1); the challenge is the per-key state.

**Q: 3-Sum is O(n²). Can it be beaten?**
Not meaningfully in general — 3-Sum is *3SUM-hard*; sub-quadratic algorithms exist only with strong assumptions (small integer ranges via FFT/bitset convolution, giving roughly O(n + U log U) where U is the value range) and the famous Grønlund–Pettie result shaved it to slightly sub-quadratic by polylog factors, but no truly faster comparison-based algorithm is known. In an interview, O(n²) sort + two pointers is the expected optimal.

**Q: How do you parallelize a fixed-window aggregate (e.g. windowed sum) across cores?**
Use a **prefix-sum (scan) decomposition**: compute a parallel prefix sum, then each window result is `prefix[r+1] − prefix[l]`, embarrassingly parallel and O(log n) span on a PRAM/GPU. For associative-but-not-invertible aggregates (max/min), use a **monotonic deque** per partition or a sparse table for O(1) range queries, since you can't "subtract" the leaving element.

**Q: Trapping Rain Water has multiple solutions — rank them.**
Brute force per-column O(n²); precomputed `leftMax`/`rightMax` arrays O(n) time + O(n) space; **two-pointer O(n) time + O(1) space** by advancing whichever side has the smaller running max (the optimal answer); and a monotonic-stack O(n) approach that fills water horizontally. Two pointers is the senior favorite for its space optimality.

---

## ⚠️ Common Pitfalls

- **Off-by-one on window length.** The length of `[left, right]` inclusive is `right − left + 1`, not `right − left`. This single mistake breaks most window problems.
- **Forgetting to remove the leaving element.** In fixed windows you must subtract `arr[right − k]` (or decrement its count); in variable windows the shrink loop must undo the state of `arr[left]` before advancing. Asymmetric add/remove logic is the #1 silent bug.
- **Integer overflow.** Sums/products of large or many elements overflow 32-bit `int`. Use `long` accumulators (fixed-window sums, subarray products, k-Sum comparisons).
- **Applying a window where monotonicity doesn't hold.** Negative numbers or zeros break product/sum windows; non-contiguous requirements break windows entirely. Check the invariant first.
- **Duplicate triplets/quadruplets in k-Sum.** You must skip equal pivots *and* skip equal `lo`/`hi` after a hit — skipping only one place leaves duplicates.
- **Shrinking with `while` vs `if`.** Use `while` when the window may need multiple contractions to regain validity (most cases); the `if` shortcut is valid only when the window strictly slides and never shrinks (Longest Repeating Character Replacement). Mixing them up gives wrong answers or wrong complexity.
- **`maxFreq` over-maintenance.** Recomputing the max frequency on every shrink turns an O(n) solution into O(26n) or O(n²) needlessly.
- **Empty / boundary inputs.** Empty string, `k = 0`, `t` longer than `s`, single-element arrays — guard these explicitly; many templates assume `right ≥ k` or `lo < hi` and crash otherwise.
- **Mutating a Java `String` in a loop.** Strings are immutable; concatenating in a window loop is accidental O(n²). Index into the original or use a `StringBuilder`.

---

## 📚 Further Reading

- *Cracking the Coding Interview*, Gayle Laakmann McDowell — Arrays & Strings chapter (two-pointer and window primitives).
- *Elements of Programming Interviews in Java* — chapters on arrays and the "invariant" framing of pointer problems.
- *Introduction to Algorithms* (CLRS), 4th ed. — amortized analysis (Ch. 16) underpins the linear-time argument; sorting bounds for the k-Sum prerequisite.
- LeetCode tag drills: **Sliding Window** and **Two Pointers** — work the curated "Grind 75" / NeetCode 150 lists in easy→hard order.
- Grønlund & Pettie, *"Threesomes, Degenerates, and Love Triangles"* (FOCS 2014) — the sub-quadratic 3SUM result, for the expert-level discussion.
- *Competitive Programmer's Handbook*, Antti Laaksonen — concise, free PDF covering two pointers and sliding windows with contest framing.

[← Back to master index](../README.md) | [← DSA index](README.md)
