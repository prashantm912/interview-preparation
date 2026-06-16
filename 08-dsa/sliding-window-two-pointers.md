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

## 🧩 Extended Problems — Set 1: Classic Easy → Medium

### Problem 13: Valid Palindrome — Two pointers, opposite ends

**Statement.** Given a string `s`, return `true` if it is a palindrome after lowercasing and removing all non-alphanumeric characters; otherwise `false`.

**Constraints.** `1 ≤ s.length ≤ 2·10^5`; `s` consists of printable ASCII characters.

**Approach.** Building a cleaned copy and reversing it works but costs O(n) extra space. The opposite-ends two-pointer scans in place: `lo` from the front, `hi` from the back, each skipping non-alphanumeric characters, comparing the lowercased survivors. If any pair disagrees it cannot be a palindrome. Because each pointer only moves inward, the whole check is a single O(n) pass with O(1) space — optimal since we must read every character at least once.

```
 "A man, a plan"  (conceptually)
  ^lo          ^hi
  skip punctuation/spaces on each side, compare letters inward
  a == n? ...  compare until lo >= hi
```

```java
class Solution {
    public boolean isPalindrome(String s) {
        int lo = 0, hi = s.length() - 1;
        while (lo < hi) {
            while (lo < hi && !Character.isLetterOrDigit(s.charAt(lo))) lo++;
            while (lo < hi && !Character.isLetterOrDigit(s.charAt(hi))) hi--;
            if (Character.toLowerCase(s.charAt(lo)) != Character.toLowerCase(s.charAt(hi)))
                return false;
            lo++;
            hi--;
        }
        return true;
    }
}
```

**Complexity** — Time O(n), Space O(1). **Edge cases:** empty or all-punctuation strings (`",.,"`) collapse to `true`; single character is trivially a palindrome; mixed case handled by `toLowerCase`; the inner `lo < hi` guards prevent the skip loops from crossing.

---

### Problem 14: Move Zeroes — Fast/slow pointers (stable in-place compaction)

**Statement.** Given an integer array `nums`, move all `0`s to the end while keeping the relative order of the non-zero elements. Do it in place.

**Constraints.** `1 ≤ n ≤ 10^4`; `-2^31 ≤ nums[i] ≤ 2^31 - 1`.

**Approach.** A two-pass copy or repeated shifting is wasteful. With fast/slow pointers, `slow` marks the next slot to receive a non-zero value while `fast` scans. Each time `nums[fast] != 0`, swap it into `nums[slow]` and advance `slow`. Swapping (rather than overwrite-then-fill) keeps non-zeros in order and naturally pushes zeros rightward in one pass — O(n) time, O(1) space, and it minimizes writes because untouched-position swaps where `fast == slow` are no-ops.

```
 nums=[0,1,0,3,12]
 slow=0
 fast finds 1 -> swap(0,1): [1,0,0,3,12] slow=1
 fast finds 3 -> swap(1,3): [1,3,0,0,12] slow=2
 fast finds 12-> swap(2,12):[1,3,12,0,0] slow=3
```

```java
class Solution {
    public void moveZeroes(int[] nums) {
        int slow = 0;
        for (int fast = 0; fast < nums.length; fast++) {
            if (nums[fast] != 0) {
                int tmp = nums[slow];
                nums[slow] = nums[fast];
                nums[fast] = tmp;
                slow++;
            }
        }
    }
}
```

**Complexity** — Time O(n), Space O(1). **Edge cases:** all zeros (slow never advances, array unchanged and correct); no zeros (every swap is `i` with itself, order preserved); single element handled trivially.

---

### Problem 15: Remove Element — Fast/slow pointers

**Statement.** Given an array `nums` and a value `val`, remove all occurrences of `val` in place and return the new length `k`; the first `k` elements must hold the kept values (order may vary, remainder irrelevant).

**Constraints.** `0 ≤ n ≤ 100`; `0 ≤ nums[i], val ≤ 50`.

**Approach.** This is the canonical write-pointer compaction. `slow` is the write index for kept elements; `fast` scans the whole array. Whenever `nums[fast] != val`, copy it to `nums[slow]` and advance `slow`. Elements equal to `val` are simply skipped. One pass, O(n) time and O(1) space — optimal because each element must be examined once.

```java
class Solution {
    public int removeElement(int[] nums, int val) {
        int slow = 0;
        for (int fast = 0; fast < nums.length; fast++) {
            if (nums[fast] != val) {
                nums[slow++] = nums[fast];
            }
        }
        return slow;
    }
}
```

**Complexity** — Time O(n), Space O(1). **Edge cases:** empty array returns 0; all elements equal `val` returns 0; no element equals `val` copies the array onto itself and returns `n`.

---

### Problem 16: Reverse String — Two pointers, opposite ends

**Statement.** Reverse a character array `s` in place.

**Constraints.** `1 ≤ s.length ≤ 10^5`; `s[i]` is a printable ASCII character.

**Approach.** Swap the outermost pair and walk inward: `lo` from the start, `hi` from the end, swapping until they meet. This touches each element exactly once and needs no auxiliary buffer — the textbook opposite-ends two-pointer, O(n) time and O(1) space, which is optimal.

```java
class Solution {
    public void reverseString(char[] s) {
        int lo = 0, hi = s.length - 1;
        while (lo < hi) {
            char tmp = s[lo];
            s[lo] = s[hi];
            s[hi] = tmp;
            lo++;
            hi--;
        }
    }
}
```

**Complexity** — Time O(n), Space O(1). **Edge cases:** single character (loop body never executes, already reversed); even length (pointers cross between the middle two); odd length (middle element untouched, stays put).

---

### Problem 17: Squares of a Sorted Array — Two pointers, opposite ends (merge inward)

**Statement.** Given an array `nums` sorted in non-decreasing order (may contain negatives), return an array of the squares of each number, also sorted in non-decreasing order.

**Constraints.** `1 ≤ n ≤ 10^4`; `-10^4 ≤ nums[i] ≤ 10^4`; `nums` is sorted ascending.

**Approach.** Squaring then sorting is O(n log n). The key observation: the largest squares come from the array's extremes (the most negative or most positive values). Place two pointers at the ends; whichever has the larger absolute value contributes the next-largest square, which we fill into the result from the back. This merges the two implicitly-sorted halves in O(n) time — optimal.

```
 nums=[-4,-1,0,3,10]  -> abs ends compete
 |-4|=4 vs |10|=10 -> 100 goes last
 |-4|=4 vs |3|=3   -> 16 next
 ... fill result right-to-left
 result=[0,1,9,16,100]
```

```java
class Solution {
    public int[] sortedSquares(int[] nums) {
        int n = nums.length;
        int[] res = new int[n];
        int lo = 0, hi = n - 1, pos = n - 1;
        while (lo <= hi) {
            int left = nums[lo] * nums[lo];
            int right = nums[hi] * nums[hi];
            if (left > right) {
                res[pos--] = left;
                lo++;
            } else {
                res[pos--] = right;
                hi--;
            }
        }
        return res;
    }
}
```

**Complexity** — Time O(n), Space O(n) for the output (O(1) auxiliary). **Edge cases:** all negatives (filled effectively in reverse order); all non-negatives (right pointer dominates); single element; ties in absolute value handled by the `else` branch.

---

### Problem 18: Minimum Size Subarray Sum — Variable window (shortest valid)

**Statement.** Given an array of positive integers `nums` and a positive integer `target`, return the minimal length of a contiguous subarray whose sum is `≥ target`. If none exists, return `0`.

**Constraints.** `1 ≤ n ≤ 10^5`; `1 ≤ nums[i] ≤ 10^4`; `1 ≤ target ≤ 10^9`.

**Approach.** Because all elements are positive, the running sum increases as the window grows and decreases as it shrinks — monotonic feasibility, so a variable window works. Expand `right` adding to `sum`; whenever `sum ≥ target`, the window is valid, so record its length and shrink from `left` to find the *shortest* valid window ending here. Each index enters and leaves the window once: O(n) total, beating the O(n log n) prefix-sum + binary-search alternative.

```java
class Solution {
    public int minSubArrayLen(int target, int[] nums) {
        int left = 0, best = Integer.MAX_VALUE;
        long sum = 0;
        for (int right = 0; right < nums.length; right++) {
            sum += nums[right];
            while (sum >= target) {
                best = Math.min(best, right - left + 1);
                sum -= nums[left++];
            }
        }
        return best == Integer.MAX_VALUE ? 0 : best;
    }
}
```

**Complexity** — Time O(n), Space O(1). **Edge cases:** total sum below `target` returns 0; a single element already `≥ target` yields length 1; `long` accumulator guards against summing up to `10^5 · 10^4 = 10^9` safely.

---

### Problem 19: Find All Anagrams in a String — Fixed window with frequency match

**Statement.** Given strings `s` and `p`, return the start indices of all substrings of `s` that are anagrams of `p`.

**Constraints.** `1 ≤ s.length, p.length ≤ 3·10^4`; both consist of lowercase English letters.

**Approach.** A naive per-window sort is O(n·k log k). Instead, slide a fixed window of width `p.length()` and keep a 26-letter frequency difference against `p`. Maintain a `matches` counter of how many letters currently have the exact required count; when `matches == 26`, the window is an anagram. Adding the entering char and removing the leaving char each adjust at most one bucket, so the slide is O(1) and the whole scan O(n).

```java
class Solution {
    public java.util.List<Integer> findAnagrams(String s, String p) {
        java.util.List<Integer> res = new java.util.ArrayList<>();
        int n = s.length(), m = p.length();
        if (n < m) return res;
        int[] need = new int[26], win = new int[26];
        for (char c : p.toCharArray()) need[c - 'a']++;
        for (int i = 0; i < n; i++) {
            win[s.charAt(i) - 'a']++;
            if (i >= m) win[s.charAt(i - m) - 'a']--;   // drop the leaving char
            if (i >= m - 1 && java.util.Arrays.equals(win, need)) {
                res.add(i - m + 1);
            }
        }
        return res;
    }
}
```

**Complexity** — Time O(n) (the 26-length array compare is O(1)), Space O(1). **Edge cases:** `p` longer than `s` returns empty; identical `s` and `p` returns `[0]`; repeated anagrams overlap and are all reported.

---

### Problem 20: Permutation in String — Fixed window boolean match

**Statement.** Given strings `s1` and `s2`, return `true` if `s2` contains a permutation of `s1` as a contiguous substring.

**Constraints.** `1 ≤ s1.length, s2.length ≤ 10^4`; both consist of lowercase English letters.

**Approach.** This is the boolean sibling of Find All Anagrams: slide a window of width `s1.length()` over `s2` and check whether its letter frequencies equal `s1`'s. Rather than comparing full arrays each step, track a `matches` count of letters at their exact target frequency, updating it incrementally as one char enters and another leaves; return early on the first full match. O(n) time, O(1) space.

```java
class Solution {
    public boolean checkInclusion(String s1, String s2) {
        int m = s1.length(), n = s2.length();
        if (m > n) return false;
        int[] need = new int[26], win = new int[26];
        for (char c : s1.toCharArray()) need[c - 'a']++;
        for (int i = 0; i < n; i++) {
            win[s2.charAt(i) - 'a']++;
            if (i >= m) win[s2.charAt(i - m) - 'a']--;
            if (i >= m - 1 && java.util.Arrays.equals(win, need)) return true;
        }
        return false;
    }
}
```

**Complexity** — Time O(n), Space O(1). **Edge cases:** `s1` longer than `s2` returns `false` immediately; the first window is only tested once it reaches full width (`i >= m - 1`); single-character `s1` reduces to a membership check.

---

### Problem 21: Maximum Number of Vowels in a Substring of Given Length — Fixed window

**Statement.** Given a string `s` and an integer `k`, return the maximum number of vowels (`a, e, i, o, u`) in any substring of length `k`.

**Constraints.** `1 ≤ k ≤ s.length ≤ 10^5`; `s` consists of lowercase English letters.

**Approach.** Counting vowels in every length-`k` window independently is O(n·k). The fixed-window trick: maintain a running vowel count; when sliding, add 1 if the entering char is a vowel and subtract 1 if the leaving char was a vowel. Track the running maximum. O(n) with O(1) space; you can short-circuit once the count reaches `k`.

```java
class Solution {
    public int maxVowels(String s, int k) {
        int count = 0, best = 0;
        for (int i = 0; i < s.length(); i++) {
            if (isVowel(s.charAt(i))) count++;
            if (i >= k && isVowel(s.charAt(i - k))) count--;
            if (i >= k - 1) best = Math.max(best, count);
        }
        return best;
    }
    private boolean isVowel(char c) {
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u';
    }
}
```

**Complexity** — Time O(n), Space O(1). **Edge cases:** `k == s.length()` evaluates exactly one window; a window of all vowels returns `k`; no vowels returns 0.

---

### Problem 22: Longest Subarray of 1's After Deleting One Element — Variable window (at most one zero)

**Statement.** Given a binary array `nums`, you must delete exactly one element. Return the size of the longest non-empty subarray of all `1`s in the resulting array.

**Constraints.** `1 ≤ n ≤ 10^5`; `nums[i]` is `0` or `1`.

**Approach.** Equivalent to finding the longest window containing **at most one zero**, then subtracting one because a deletion is mandatory (the window's reported length includes one slot that must be removed). Expand `right`; when the window holds more than one zero, shrink `left` past a zero. The answer is `max(right - left + 1) - 1`. O(n) time, O(1) space.

```java
class Solution {
    public int longestSubarray(int[] nums) {
        int left = 0, zeros = 0, best = 0;
        for (int right = 0; right < nums.length; right++) {
            if (nums[right] == 0) zeros++;
            while (zeros > 1) {
                if (nums[left] == 0) zeros--;
                left++;
            }
            best = Math.max(best, right - left + 1);
        }
        return best - 1;   // one element is always deleted
    }
}
```

**Complexity** — Time O(n), Space O(1). **Edge cases:** all `1`s (window spans the array, answer `n - 1` since a `1` must be deleted); all `0`s (window never exceeds one zero of length 1, answer 0); a single element returns 0.

---

### Problem 23: Backspace String Compare — Two pointers scanning from the back

**Statement.** Given two strings `s` and `t` where `#` means a backspace, return `true` if they are equal after applying the backspaces.

**Constraints.** `1 ≤ s.length, t.length ≤ 200`; characters are lowercase letters or `#`.

**Approach.** Building both results with a stack is O(n) space. The space-optimal method scans both strings from the right with two pointers: at each side, skip characters that are deleted by pending `#`s (counting skips), then compare the next surviving characters. Walking backward lets us resolve each backspace before reaching the char it deletes, so we never need to store the processed string — O(n) time, O(1) space.

```java
class Solution {
    public boolean backspaceCompare(String s, String t) {
        int i = s.length() - 1, j = t.length() - 1;
        while (i >= 0 || j >= 0) {
            i = nextValid(s, i);
            j = nextValid(t, j);
            if (i >= 0 && j >= 0) {
                if (s.charAt(i) != t.charAt(j)) return false;
            } else if (i >= 0 || j >= 0) {
                return false;   // one ran out before the other
            }
            i--;
            j--;
        }
        return true;
    }
    private int nextValid(String str, int idx) {
        int skip = 0;
        while (idx >= 0) {
            if (str.charAt(idx) == '#') { skip++; idx--; }
            else if (skip > 0) { skip--; idx--; }
            else break;
        }
        return idx;
    }
}
```

**Complexity** — Time O(n + m), Space O(1). **Edge cases:** leading backspaces with nothing to delete (`"#a"` -> `"a"`); both reduce to empty (`"a#"` vs `"b#"` both empty -> `true`); unequal surviving lengths caught by the one-ran-out check.

---

### Problem 24: Sort Array By Parity — Two pointers, opposite ends (in-place partition)

**Statement.** Given an integer array `nums`, return any arrangement where all even elements precede all odd elements.

**Constraints.** `1 ≤ n ≤ 5000`; `0 ≤ nums[i] ≤ 5000`.

**Approach.** A Lomuto/Hoare-style partition with opposite-ends pointers does it in place. `lo` advances over evens from the left; `hi` retreats over odds from the right; when `nums[lo]` is odd and `nums[hi]` is even, swap them. Because each pointer only moves toward the other, the array is partitioned in one O(n) pass with O(1) space — better than building two lists.

```java
class Solution {
    public int[] sortArrayByParity(int[] nums) {
        int lo = 0, hi = nums.length - 1;
        while (lo < hi) {
            if (nums[lo] % 2 == 0) {
                lo++;                       // even already on the correct side
            } else if (nums[hi] % 2 == 1) {
                hi--;                       // odd already on the correct side
            } else {                        // nums[lo] odd, nums[hi] even -> swap
                int tmp = nums[lo];
                nums[lo] = nums[hi];
                nums[hi] = tmp;
                lo++;
                hi--;
            }
        }
        return nums;
    }
}
```

**Complexity** — Time O(n), Space O(1). **Edge cases:** all even or all odd (no swaps, returns unchanged); the partition is not stable (relative order may change), which the problem permits; single element returns immediately.

---

### Problem 25: Number of Subarrays with Sum at Most K (Positive Integers) — Variable window count

**Statement.** Given an array of positive integers `nums` and an integer `k`, count the number of contiguous subarrays whose sum is **at most** `k`.

**Constraints.** `1 ≤ n ≤ 10^5`; `1 ≤ nums[i] ≤ 10^4`; `0 ≤ k ≤ 10^9`.

**Approach.** With all-positive elements the sum is monotonic in the window size, so a counting window applies — the additive twin of "Subarray Product Less Than K". Expand `right` adding to `sum`; while `sum > k`, shrink from `left`. For each valid `right`, every subarray ending at `right` and starting anywhere in `[left, right]` is valid, contributing `right - left + 1` to the count. O(n) time, O(1) space.

```
 each valid window [left..right] adds (right-left+1) subarrays
 (all subarrays that END at `right`)
```

```java
class Solution {
    public long numSubarraysSumAtMostK(int[] nums, int k) {
        int left = 0;
        long sum = 0, count = 0;
        for (int right = 0; right < nums.length; right++) {
            sum += nums[right];
            while (left <= right && sum > k) {
                sum -= nums[left++];
            }
            count += right - left + 1;   // subarrays ending at right
        }
        return count;
    }
}
```

**Complexity** — Time O(n), Space O(1). **Edge cases:** `k = 0` with positive elements yields 0 (every single element exceeds 0, window stays empty so `right - left + 1 == 0`); a single element `≤ k` contributes 1; `count` and `sum` use `long` since the count can reach `n·(n+1)/2 ≈ 5·10^9` and sums approach `10^9`.

---

## 🧩 Extended Problems — Set 2: Medium → Hard Variations & Follow-ups

### Problem 26: Trapping Rain Water — Two pointers, opposite ends (O(1) space)

**Statement.** Given `n` non-negative integers `height[]` representing an elevation map where each bar has width 1, compute how much water is trapped after raining.

**Constraints.** `0 ≤ n ≤ 2·10^4`; `0 ≤ height[i] ≤ 10^5`.

**Approach.** Water above bar `i` equals `min(maxLeft[i], maxRight[i]) − height[i]`. The brute force recomputes both maxima per column in O(n²). Precomputing `leftMax`/`rightMax` arrays gives O(n) time but O(n) space. The optimal two-pointer trick drops the arrays: walk `lo` and `hi` inward, tracking running `leftMax`/`rightMax`. Whichever side has the **smaller running max** is the binding constraint for its column — the opposite side is guaranteed to hold at least that much, so we can settle that column immediately and move that pointer.

```
 height = [0,1,0,2,1,0,1,3,2,1,2,1]
 lo ->                          <- hi
 if leftMax <= rightMax: water at lo = leftMax - height[lo]; lo++
 else                  : water at hi = rightMax - height[hi]; hi--
 the smaller wall bounds the trapped water, so it is safe to commit it
```

```java
class Solution {
    public int trap(int[] height) {
        int lo = 0, hi = height.length - 1;
        int leftMax = 0, rightMax = 0, water = 0;
        while (lo < hi) {
            if (height[lo] <= height[hi]) {
                leftMax = Math.max(leftMax, height[lo]);
                water += leftMax - height[lo];      // leftMax is the binding wall
                lo++;
            } else {
                rightMax = Math.max(rightMax, height[hi]);
                water += rightMax - height[hi];
                hi--;
            }
        }
        return water;
    }
}
```

**Dry run** on `[0,1,0,2,1,0,1,3,2,1,2,1]`: the pointers converge committing each column against the smaller running max; the trapped total is **6**.

**Complexity** — Time O(n), Space O(1). **Edge cases:** empty or single-bar arrays trap 0; strictly increasing or decreasing profiles trap 0; flat plateaus contribute nothing. The `height[lo] <= height[hi]` comparison (not the running maxima) is what guarantees the chosen side's running max is the true binding constraint.

---

### Problem 27: Subarrays with K Different Integers — atMost(k) − atMost(k−1)

**Statement.** Given an integer array `nums` and an integer `k`, return the number of contiguous subarrays containing **exactly** `k` distinct integers.

**Constraints.** `1 ≤ n ≤ 2·10^4`; `1 ≤ nums[i] ≤ n`; `1 ≤ k ≤ n`.

**Approach.** Counting windows with *exactly* k distinct values directly is awkward because a window can't be both expanded and contracted to a single canonical state. The decomposition `exactly(k) = atMost(k) − atMost(k−1)` sidesteps this: counting "at most k distinct" with a window is easy — for each `right`, every subarray ending at `right` whose window stays within `k` distinct contributes `right − left + 1`. Run the at-most helper twice and subtract. This is the canonical hard application of the at-most counting identity.

```java
class Solution {
    public int subarraysWithKDistinct(int[] nums, int k) {
        return atMost(nums, k) - atMost(nums, k - 1);
    }

    private int atMost(int[] nums, int k) {
        if (k < 0) return 0;
        java.util.Map<Integer, Integer> count = new java.util.HashMap<>();
        int left = 0, total = 0;
        for (int right = 0; right < nums.length; right++) {
            count.merge(nums[right], 1, Integer::sum);
            while (count.size() > k) {
                int x = nums[left++];
                if (count.merge(x, -1, Integer::sum) == 0) count.remove(x);
            }
            total += right - left + 1;          // subarrays ending at right
        }
        return total;
    }
}
```

**Dry run** on `nums=[1,2,1,2,3], k=2`: `atMost(2)=12`, `atMost(1)=5`, so exactly-2 = **7** (the subarrays `[1,2],[2,1],[1,2],[1,2,1],[2,1,2],[1,2,1,2]` plus `[2,3]`).

**Complexity** — Time O(n) (two passes), Space O(k) for the frequency map. **Edge cases:** `k = 1` reduces to counting maximal equal-value runs via `atMost(1) − atMost(0)` with `atMost(0)=0`; `k` larger than the number of distinct values yields 0 for `exactly`.

---

### Problem 28: Sliding Window Maximum — Monotonic deque (fixed window)

**Statement.** Given an array `nums` and window size `k`, return an array of the maximum of each contiguous window of size `k` as it slides left to right.

**Constraints.** `1 ≤ n ≤ 10^5`; `-10^4 ≤ nums[i] ≤ 10^4`; `1 ≤ k ≤ n`.

**Approach.** Brute force scans each window for its max: O(n·k). A max-heap of `(value, index)` gives O(n log n) but lazily holds stale entries. The optimal O(n) uses a **monotonic deque of indices** whose values are strictly decreasing. Before pushing `right`, pop all smaller values from the back (they can never be the max while `right` is in the window). Pop the front when it falls out of the window (`index ≤ right − k`). The front is always the current window's maximum. Each index is pushed and popped at most once, so total work is O(n).

```
 nums=[1,3,-1,-3,5,3], k=3   deque holds indices, values decreasing
 add 1 -> [1]
 add 3 -> pop 1 (3>1) -> [3]          window not full yet
 add -1 -> [3,-1]                     max=3
 add -3 -> [3,-1,-3]; front 3 out?    max=3
 add 5  -> pop -3,-1,3 -> [5]         max=5
 add 3  -> [5,3]                      max=5
 result = [3,3,5,5]
```

```java
class Solution {
    public int[] maxSlidingWindow(int[] nums, int k) {
        int n = nums.length;
        int[] res = new int[n - k + 1];
        java.util.Deque<Integer> dq = new java.util.ArrayDeque<>();  // indices, values desc
        for (int right = 0; right < n; right++) {
            while (!dq.isEmpty() && nums[dq.peekLast()] <= nums[right]) dq.pollLast();
            dq.offerLast(right);
            if (dq.peekFirst() <= right - k) dq.pollFirst();         // evict out-of-window
            if (right >= k - 1) res[right - k + 1] = nums[dq.peekFirst()];
        }
        return res;
    }
}
```

**Dry run** on `nums=[1,3,-1,-3,5,3,6,7], k=3` gives `[3,3,5,5,6,7]`.

**Complexity** — Time O(n) amortized, Space O(k) for the deque. **Edge cases:** `k = 1` returns the array unchanged; `k = n` returns a single global max; all-equal arrays keep the deque size 1 because `<=` pops equal values, avoiding stale duplicates.

---

### Problem 29: Sliding Window Minimum / Constrained Subarray — Two monotonic deques

**Statement.** Return the length of the **longest** continuous subarray such that the absolute difference between any two elements is `≤ limit`.

**Constraints.** `1 ≤ n ≤ 10^5`; `1 ≤ nums[i] ≤ 10^9`; `0 ≤ limit ≤ 10^9`.

**Approach.** A window is valid iff `max − min ≤ limit`. To test this in O(1) as the window moves, maintain **two monotonic deques**: a decreasing deque for the running maximum and an increasing deque for the running minimum (both holding indices). Expand `right`; if `max − min > limit`, advance `left`, popping any deque front whose index drops out. The maximum window width seen is the answer. Each index enters and leaves each deque once → O(n).

```java
class Solution {
    public int longestSubarray(int[] nums, int limit) {
        java.util.Deque<Integer> maxd = new java.util.ArrayDeque<>(); // values desc
        java.util.Deque<Integer> mind = new java.util.ArrayDeque<>(); // values asc
        int left = 0, best = 0;
        for (int right = 0; right < nums.length; right++) {
            while (!maxd.isEmpty() && nums[maxd.peekLast()] <= nums[right]) maxd.pollLast();
            while (!mind.isEmpty() && nums[mind.peekLast()] >= nums[right]) mind.pollLast();
            maxd.offerLast(right);
            mind.offerLast(right);
            while (nums[maxd.peekFirst()] - nums[mind.peekFirst()] > limit) {
                if (maxd.peekFirst() == left) maxd.pollFirst();
                if (mind.peekFirst() == left) mind.pollFirst();
                left++;
            }
            best = Math.max(best, right - left + 1);
        }
        return best;
    }
}
```

**Dry run** on `nums=[8,2,4,7], limit=4`: window `[8,2]` has diff 6 > 4 → shrink; `[2,4]` diff 2, `[2,4,7]` diff 5 > 4 → shrink to `[4,7]`; longest valid length is **2**.

**Complexity** — Time O(n), Space O(n). **Edge cases:** `limit = 0` forces all-equal windows; a strictly monotone array shrinks frequently; single element always yields 1. The `left` shrink references `nums` values, so use `long` only if values plus limit could overflow (here `int` suffices since differences fit in `int`).

---

### Problem 30: Minimum Window Subsequence — Two pointers, expand-then-contract

**Statement.** Given strings `s1` and `s2`, return the **minimum-length contiguous substring** of `s1` such that `s2` is a *subsequence* of it. If multiple have the same length, return the leftmost; if none exists, return `""`.

**Constraints.** `1 ≤ s1.length ≤ 2·10^4`; `1 ≤ s2.length ≤ 100`.

**Approach.** Unlike Minimum Window Substring (a multiset match), here `s2` must appear **in order** as a subsequence, so a frequency window is insufficient. The two-pointer technique: scan forward matching `s2` greedily; once the last char of `s2` is matched at some `end`, walk **backward** from `end` re-matching `s2` in reverse to find the tightest `start`. That backward pass guarantees minimality of the window ending at `end`. Restart the forward scan from `start + 1`. Each forward+backward sweep is bounded, giving O(n·m) worst case but linear in practice.

```
 s1 = "abcdebdde", s2 = "bde"
 forward match b(1) d(3) e(4)  -> end=4
 backward from 4: e(4) d(3) b(1) -> start=1  window "bcde" (len4)
 restart at 2 ... later finds "bdde"? no, "bde" via b(5)d(6)e(8)
 best leftmost minimal = "bcde"
```

```java
class Solution {
    public String minWindow(String s1, String s2) {
        int n = s1.length(), m = s2.length();
        int start = -1, len = Integer.MAX_VALUE;
        int i = 0;
        while (i < n) {
            int j = 0;
            while (i < n) {                       // forward: match s2 in order
                if (s1.charAt(i) == s2.charAt(j)) {
                    if (++j == m) break;
                }
                i++;
            }
            if (j < m) break;                     // s2 cannot be completed
            int end = i;                          // index of last matched char
            j = m - 1;
            while (j >= 0) {                      // backward: tighten the start
                if (s1.charAt(i) == s2.charAt(j)) j--;
                if (j >= 0) i--;
            }
            i++;                                  // i now points at the tight start
            if (end - i + 1 < len) {
                len = end - i + 1;
                start = i;
            }
            i++;                                  // restart forward scan one past start
        }
        return start == -1 ? "" : s1.substring(start, start + len);
    }
}
```

**Dry run** on `s1="abcdebdde", s2="bde"`: first window `"bcde"` (len 4); a later forward/backward pass finds `"bdde"` (len 4), but `"bcde"` is leftmost-and-not-longer, so the answer is `"bcde"`.

**Complexity** — Time O(n·m) worst case, Space O(1). **Edge cases:** `s2` not a subsequence of any window returns `""`; `s2` longer than `s1` returns `""`; equal characters handled because matching is order-sensitive, not frequency-based.

---

### Problem 31: Longest Substring with At Most K Distinct Characters — Variable window

**Statement.** Given a string `s` and an integer `k`, return the length of the longest substring containing **at most `k` distinct** characters.

**Constraints.** `0 ≤ s.length ≤ 5·10^4`; `0 ≤ k ≤ s.length`; `s` may contain any characters.

**Approach.** This generalizes "Fruit Into Baskets" (which fixes `k = 2`) and "Longest Substring Without Repeating" (the `distinct = window length` view). Maintain a `char → count` map. Expand `right`; whenever the map exceeds `k` keys, shrink from `left`, decrementing counts and removing zeroed keys. Because the distinct-count only grows when adding and only shrinks when removing, feasibility is monotonic — the window is valid. O(n) time, O(k) space.

```java
class Solution {
    public int lengthOfLongestSubstringKDistinct(String s, int k) {
        if (k == 0) return 0;
        java.util.Map<Character, Integer> count = new java.util.HashMap<>();
        int left = 0, best = 0;
        for (int right = 0; right < s.length(); right++) {
            count.merge(s.charAt(right), 1, Integer::sum);
            while (count.size() > k) {
                char c = s.charAt(left++);
                if (count.merge(c, -1, Integer::sum) == 0) count.remove(c);
            }
            best = Math.max(best, right - left + 1);
        }
        return best;
    }
}
```

**Dry run** on `s="eceba", k=2`: window grows `e, ec, ece` (len 3, distinct {e,c}); adding `b` makes 3 distinct → shrink to `ceb`? after removing leading `e`s window becomes `ceb` len 3; final best **3**.

**Complexity** — Time O(n), Space O(k). **Edge cases:** `k = 0` returns 0 (no characters allowed); `k ≥ distinct(s)` returns `s.length()`; empty string returns 0. Pairing this with the at-most decomposition (`exactly = atMost(k) − atMost(k−1)`) solves the "exactly k distinct" variant.

---

### Problem 32: Count Number of Nice Subarrays — Odd-count window via atMost

**Statement.** Given an integer array `nums` and an integer `k`, a subarray is *nice* if it contains exactly `k` odd numbers. Return the number of nice subarrays.

**Constraints.** `1 ≤ n ≤ 5·10^4`; `1 ≤ nums[i] ≤ 10^5`; `1 ≤ k ≤ n`.

**Approach.** Treat every odd number as a `1` and every even as a `0`; then a nice subarray is one whose "sum" of odd-flags equals exactly `k`. This is the at-most decomposition again: `exactly(k) = atMost(k) − atMost(k−1)`, where `atMost(t)` counts subarrays with at most `t` odd numbers using a window that shrinks when the odd-count exceeds `t`. Each valid `right` contributes `right − left + 1`. O(n) per pass, two passes total.

```java
class Solution {
    public int numberOfSubarrays(int[] nums, int k) {
        return atMost(nums, k) - atMost(nums, k - 1);
    }

    private int atMost(int[] nums, int k) {
        if (k < 0) return 0;
        int left = 0, odd = 0, total = 0;
        for (int right = 0; right < nums.length; right++) {
            if ((nums[right] & 1) == 1) odd++;
            while (odd > k) {
                if ((nums[left++] & 1) == 1) odd--;
            }
            total += right - left + 1;
        }
        return total;
    }
}
```

**Dry run** on `nums=[1,1,2,1,1], k=3`: `atMost(3)=15`, `atMost(2)=13`, so nice = **2** (`[1,1,2,1]` and `[1,2,1,1]`).

**Complexity** — Time O(n), Space O(1). **Edge cases:** `k` larger than the total odd count gives 0; arrays of all evens give 0 for any `k ≥ 1`; the `(x & 1)` parity test handles large values without modulo cost.

---

### Problem 33: Replace the Substring for Balanced String — Variable window (minimize)

**Statement.** A string `s` of length `n` (a multiple of 4) contains only `Q, W, E, R`. It is *balanced* if each character appears exactly `n/4` times. Return the minimum length of a contiguous substring you can replace (with any characters) to make `s` balanced; return 0 if already balanced.

**Constraints.** `1 ≤ n ≤ 10^5`; `n` is a multiple of 4; `s` consists of `Q, W, E, R`.

**Approach.** A window is *replaceable* iff the characters **outside** it already satisfy the balance — i.e. for every letter, its count outside the window is `≤ n/4` (the freed slots inside can absorb the surplus). Count global frequencies, then slide a window; subtract the window's chars to get the outside counts. Shrink `left` as long as the outside is feasible, tracking the shortest such window. This is a "minimize the window" variant where the validity test is on the complement. O(n) time, O(1) space.

```java
class Solution {
    public int balancedString(String s) {
        int n = s.length(), need = n / 4;
        int[] cnt = new int[128];
        for (char c : s.toCharArray()) cnt[c]++;
        if (ok(cnt, need)) return 0;                  // already balanced

        int left = 0, best = n;
        for (int right = 0; right < n; right++) {
            cnt[s.charAt(right)]--;                    // char enters window -> outside count drops
            while (left <= right && ok(cnt, need)) {   // outside is feasible: shrink
                best = Math.min(best, right - left + 1);
                cnt[s.charAt(left++)]++;               // char leaves window -> back outside
            }
        }
        return best;
    }

    private boolean ok(int[] cnt, int need) {
        return cnt['Q'] <= need && cnt['W'] <= need && cnt['E'] <= need && cnt['R'] <= need;
    }
}
```

**Dry run** on `s="QWER"` returns 0 (already balanced). On `s="QQWE"`: global counts `Q=2,W=1,E=1,R=0`; the minimal replaceable window is one `Q` (length **1**) since replacing it with `R` balances all four.

**Complexity** — Time O(n) (the `ok` check is O(1) over a fixed alphabet), Space O(1). **Edge cases:** already-balanced returns 0; a string of a single repeated letter needs a window of `3n/4`; the complement-feasibility framing is what makes this a window problem rather than brute force.

---

### Problem 34: Maximum Points You Can Obtain from Cards — Inverse fixed window

**Statement.** You have `cardPoints[]` and may take exactly `k` cards, each from either the **front or the back** of the row. Maximize the total points taken.

**Constraints.** `1 ≤ n ≤ 10^5`; `1 ≤ cardPoints[i] ≤ 10^4`; `1 ≤ k ≤ n`.

**Approach.** Taking `k` cards from the two ends is equivalent to **leaving a contiguous middle window of size `n − k`**. So maximizing the taken points is the same as **minimizing the sum of a fixed window of width `n − k`**. Compute the total, slide the min-sum window, and subtract. This inversion turns a tricky "choose from either end" problem into a standard fixed-window minimum. O(n) time, O(1) space.

```
 take k from ends  <=>  leave a window of size n-k in the middle
 answer = total - min(window sum of size n-k)
```

```java
class Solution {
    public int maxScore(int[] cardPoints, int k) {
        int n = cardPoints.length, w = n - k;
        int total = 0;
        for (int x : cardPoints) total += x;
        if (w == 0) return total;                 // take everything

        int windowSum = 0;
        for (int i = 0; i < w; i++) windowSum += cardPoints[i];
        int minWindow = windowSum;
        for (int right = w; right < n; right++) {
            windowSum += cardPoints[right] - cardPoints[right - w];
            minWindow = Math.min(minWindow, windowSum);
        }
        return total - minWindow;
    }
}
```

**Dry run** on `cardPoints=[1,2,3,4,5,6,1], k=3`: `w = 4`, the minimum window sum of width 4 is `1+2+3+4=10`, total `22`, so answer `22 − 10 = 12`.

**Complexity** — Time O(n), Space O(1). **Edge cases:** `k == n` leaves an empty window → take the whole total; `k == 1` reduces to `max(first, last)`; all-equal cards give `k · value`.

---

### Problem 35: Get Equal Substrings Within Budget — Variable window with cost

**Statement.** Given strings `s` and `t` of equal length and an integer `maxCost`, the cost of changing `s[i]` to `t[i]` is `|s[i] − t[i]|` (ASCII). Return the maximum length of a contiguous substring of `s` you can convert into the corresponding substring of `t` with total cost `≤ maxCost`.

**Constraints.** `1 ≤ s.length = t.length ≤ 10^5`; `0 ≤ maxCost ≤ 10^6`; lowercase letters.

**Approach.** Precompute per-index cost `c[i] = |s[i] − t[i]|`; the problem becomes "longest subarray of `c` with sum `≤ maxCost`" over non-negative values — a textbook variable window. Expand `right` adding `c[right]`; when the running cost exceeds `maxCost`, shrink `left`. Track the longest valid window. Non-negativity guarantees monotonic feasibility, so O(n) time, O(1) space.

```java
class Solution {
    public int equalSubstring(String s, String t, int maxCost) {
        int left = 0, cost = 0, best = 0;
        for (int right = 0; right < s.length(); right++) {
            cost += Math.abs(s.charAt(right) - t.charAt(right));
            while (cost > maxCost) {
                cost -= Math.abs(s.charAt(left) - t.charAt(left));
                left++;
            }
            best = Math.max(best, right - left + 1);
        }
        return best;
    }
}
```

**Dry run** on `s="abcd", t="bcdf", maxCost=3`: per-index costs `[1,1,1,2]`; window `[1,1,1]` sums to 3 (len 3); adding `2` exceeds budget → shrink; best length **3**.

**Complexity** — Time O(n), Space O(1). **Edge cases:** `maxCost = 0` returns the longest run where `s[i] == t[i]`; identical strings return the full length; a single index whose cost exceeds the budget yields a window that skips it (length 0 there).

---

### Problem 36: Subarrays with Sum Exactly K (handles negatives) — Why the window fails, and the prefix-sum fix

**Statement.** Given an integer array `nums` (which may include negatives and zeros) and an integer `k`, return the number of contiguous subarrays whose sum equals exactly `k`.

**Constraints.** `1 ≤ n ≤ 2·10^4`; `-1000 ≤ nums[i] ≤ 1000`; `-10^7 ≤ k ≤ 10^7`.

**Approach.** This is the classic case where **a sliding window does NOT work**: with negatives, growing the window can decrease the sum, so feasibility is not monotonic and the shrink condition is meaningless. The correct linear method uses **prefix sums in a hash map**: maintain a running prefix `sum`, and the number of subarrays ending at `right` with sum `k` equals the number of earlier prefixes equal to `sum − k`. Store prefix-sum frequencies as you go. Recognizing *when the window breaks* and reaching for prefix sums is the senior-level takeaway.

```
 window assumes: extend -> sum grows; shrink -> sum drops  (FALSE with negatives)
 fix: count[sum] = how many prefixes had this value
      subarrays ending at i with sum k = count[prefix_i - k]
```

```java
class Solution {
    public int subarraySum(int[] nums, int k) {
        java.util.Map<Long, Integer> count = new java.util.HashMap<>();
        count.put(0L, 1);                      // empty prefix
        long sum = 0;
        int result = 0;
        for (int x : nums) {
            sum += x;
            result += count.getOrDefault(sum - k, 0);
            count.merge(sum, 1, Integer::sum);
        }
        return result;
    }
}
```

**Dry run** on `nums=[1,-1,0], k=0`: prefixes `0,1,0,0`; matches accumulate to **3** (`[1,-1]`, `[0]`, `[1,-1,0]`).

**Complexity** — Time O(n), Space O(n) for the prefix-sum map. **Edge cases:** all-positive arrays *could* use a window, but this method handles negatives uniformly; `k = 0` with zeros counts every zero-sum span; the seeded `0L → 1` entry is essential to count prefixes that equal `k` outright.

---

### Problem 37: Minimum Number of K Consecutive Bit Flips — Window with a difference array

**Statement.** Given a binary array `nums` and an integer `k`, in one operation you flip a contiguous subarray of length exactly `k`. Return the minimum number of operations to make every element `1`, or `-1` if impossible.

**Constraints.** `1 ≤ n ≤ 10^5`; `1 ≤ k ≤ n`; `nums[i] ∈ {0, 1}`.

**Approach.** Greedily scan left to right: a `0` at index `i` must be fixed by a flip starting exactly at `i` (any earlier start would have been forced sooner, any later start can't reach back). The challenge is computing each element's *current* value after prior flips in O(1). Use a **sliding flip-count with a difference array**: `flipped` tracks how many active flips cover the current index; a marker (or `nums[i - k] += 2` style tag) tells us when a flip window expires. The element's effective parity is `(nums[i] + flipped) % 2`. This is a window-of-influence technique, O(n) time, O(1) extra space using in-place tagging.

```java
class Solution {
    public int minKBitFlips(int[] nums, int k) {
        int n = nums.length, flipped = 0, ops = 0;
        // use values >= 2 as in-place markers for "a flip ended here"
        for (int i = 0; i < n; i++) {
            if (i >= k && nums[i - k] > 1) {       // a flip started at i-k expires now
                nums[i - k] -= 2;
                flipped--;
            }
            if ((nums[i] + flipped) % 2 == 0) {    // effective value is 0 -> must flip
                if (i + k > n) return -1;          // not enough room to flip
                nums[i] += 2;                      // tag: a flip starts here
                flipped++;
                ops++;
            }
        }
        return ops;
    }
}
```

**Dry run** on `nums=[0,0,0,1,0,1,1,0], k=3`: flips start at indices 0, 1, 4, 5 → **3** operations? Tracing the greedy yields the LeetCode answer of **3**.

**Complexity** — Time O(n), Space O(1) (in-place tagging; a separate diff array would be O(n)). **Edge cases:** if a needed flip would extend past the end, return -1; an all-ones array needs 0 operations; `k = 1` flips each zero independently. The expiry check `i >= k` with the `>1` marker is the sliding-window bookkeeping that keeps `flipped` accurate.

---

### Problem 38: Count Pairs with Difference ≤ T after Sorting — Two pointers, same direction

**Statement.** Given an integer array `nums` and a non-negative integer `t`, count the number of index pairs `(i, j)` with `i < j` such that `|nums[i] − nums[j]| ≤ t`.

**Constraints.** `1 ≤ n ≤ 10^5`; `-10^9 ≤ nums[i] ≤ 10^9`; `0 ≤ t ≤ 2·10^9`.

**Approach.** Order does not matter for the condition, so **sort first** (O(n log n)); now `|nums[i] − nums[j]|` for `i < j` becomes `nums[j] − nums[i]`. Use a same-direction two-pointer window: for each `right`, advance `left` while `nums[right] − nums[left] > t`. Every index in `[left, right − 1]` pairs validly with `right`, contributing `right − left` pairs. Because `left` only moves forward, the scan after sorting is O(n). This shows two pointers working on a *sorted-but-not-given-sorted* array — a common follow-up framing.

```java
class Solution {
    public long countPairs(int[] nums, int t) {
        java.util.Arrays.sort(nums);
        int left = 0;
        long count = 0;
        for (int right = 0; right < nums.length; right++) {
            while ((long) nums[right] - nums[left] > t) left++;
            count += right - left;            // pairs (left..right-1, right)
        }
        return count;
    }
}
```

**Dry run** on `nums=[1,3,1], t=1` → sorted `[1,1,3]`: right=0 adds 0; right=1 (`1−1=0≤1`) adds 1; right=2 (`3−1=2>1` shrink left to 2) adds 0. Total **1** pair (`(1,1)`).

**Complexity** — Time O(n log n) dominated by the sort, Space O(1) auxiliary (or O(log n) sort stack). **Edge cases:** `t = 0` counts equal-value pairs; a strictly increasing array spaced beyond `t` yields 0; the `(long)` cast prevents overflow when computing differences of extreme values.

---

## 🧩 Extended Problems — Supplemental: Medium → Expert

### Problem 39: Subarrays with At Most K Distinct via the At-Most Helper — Variable window foundation

**Statement.** Given an array `nums` and an integer `k`, return the number of contiguous subarrays containing **at most** `k` distinct integers. This is the building block for the `exactly(k) = atMost(k) − atMost(k−1)` decomposition.

**Constraints.** `1 ≤ n ≤ 2·10^4`; `1 ≤ nums[i] ≤ n`; `0 ≤ k ≤ n`.

**Approach.** Maintain a `value → count` map and a window `[left, right]` whose distinct-count never exceeds `k`. Expand `right`; if the map grows beyond `k` keys, shrink `left`, removing zeroed entries. For each valid `right`, every subarray ending at `right` and starting anywhere in `[left, right]` is valid, contributing `right − left + 1`. The distinct-count is monotonic under expand/shrink, so feasibility is well defined. O(n) time, O(k) space — the canonical helper you reuse for "exactly k" problems.

```
 each valid right contributes (right - left + 1)
 = "all subarrays ending at right whose distinct count <= k"
```

```java
class Solution {
    public int subarraysAtMostKDistinct(int[] nums, int k) {
        if (k < 0) return 0;
        java.util.Map<Integer, Integer> count = new java.util.HashMap<>();
        int left = 0, total = 0;
        for (int right = 0; right < nums.length; right++) {
            count.merge(nums[right], 1, Integer::sum);
            while (count.size() > k) {
                int x = nums[left++];
                if (count.merge(x, -1, Integer::sum) == 0) count.remove(x);
            }
            total += right - left + 1;
        }
        return total;
    }
}
```

**Complexity** — Time O(n), Space O(k). **Edge cases:** `k = 0` returns 0 (no subarray can hold zero distinct values when elements exist); `k ≥ distinct(nums)` returns `n·(n+1)/2`; the map's transient size never exceeds `k + 1` between the violation and the shrink.

---

### Problem 40: Longest Mountain in Array — Two pointers, expand from peaks

**Statement.** A *mountain* is a subarray of length `≥ 3` that strictly increases then strictly decreases. Given `arr`, return the length of the longest mountain, or `0` if none exists.

**Constraints.** `1 ≤ n ≤ 10^4`; `0 ≤ arr[i] ≤ 10^4`.

**Approach.** Scan left to right looking for a *peak* index `i` where `arr[i-1] < arr[i] > arr[i+1]`. From each peak, expand two pointers outward — `l` while strictly increasing toward the peak, `r` while strictly decreasing away. The mountain length is `r − l + 1`. Jump `i` to `r` after each peak to avoid re-counting. Each index is visited O(1) times across the whole run, giving an O(n) single-pass solution superior to the O(n²) "try every starting index" approach.

```
 arr=[2,1,4,7,3,2,5]
 peak at i=3 (4<7>3)
 expand left: 7>4>1 stop -> l=1
 expand right: 7>3>2 stop -> r=5
 length = 5 - 1 + 1 = 5
```

```java
class Solution {
    public int longestMountain(int[] arr) {
        int n = arr.length, best = 0, i = 1;
        while (i < n - 1) {
            if (arr[i - 1] < arr[i] && arr[i] > arr[i + 1]) {  // peak
                int l = i - 1, r = i + 1;
                while (l > 0 && arr[l - 1] < arr[l]) l--;
                while (r < n - 1 && arr[r] > arr[r + 1]) r++;
                best = Math.max(best, r - l + 1);
                i = r;                                          // skip past this mountain
            } else {
                i++;
            }
        }
        return best;
    }
}
```

**Complexity** — Time O(n), Space O(1). **Edge cases:** `n < 3` returns 0; plateaus (equal neighbors) break both strict slopes so they don't form peaks; multiple disjoint mountains are each evaluated once because `i` jumps to `r`.

---

### Problem 41: Maximum Erasure Value — Longest subarray with all-unique elements

**Statement.** Given an array of positive integers `nums`, return the maximum sum of a contiguous subarray whose elements are all **unique**.

**Constraints.** `1 ≤ n ≤ 10^5`; `1 ≤ nums[i] ≤ 10^4`.

**Approach.** The "all unique" invariant is identical to "longest substring without repeating characters", so a variable window works. Maintain a hash set of values in the window and a running sum. When `nums[right]` is already in the set, shrink from `left`, subtracting and removing values until the duplicate is gone. Track the maximum running sum across all valid windows. Each element enters and leaves the set at most once → O(n) time and O(n) space.

```java
class Solution {
    public int maximumUniqueSubarray(int[] nums) {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        int left = 0, sum = 0, best = 0;
        for (int right = 0; right < nums.length; right++) {
            while (!seen.add(nums[right])) {        // duplicate inside window
                sum -= nums[left];
                seen.remove(nums[left++]);
            }
            sum += nums[right];
            best = Math.max(best, sum);
        }
        return best;
    }
}
```

**Complexity** — Time O(n), Space O(n). **Edge cases:** all-distinct array returns the total sum; all-equal array returns one element; a single element is trivially unique. Because `nums[i] ≥ 1`, the running sum is non-negative and tracking `best` after each `right` is sufficient.

---

### Problem 42: Maximum Sum of Two Non-Overlapping Subarrays — Sliding window with prefix sums

**Statement.** Given `nums` and integers `firstLen` and `secondLen`, return the maximum sum of elements in two **non-overlapping** contiguous subarrays of those lengths (in either order).

**Constraints.** `1 ≤ firstLen, secondLen`; `firstLen + secondLen ≤ n ≤ 1000`; `0 ≤ nums[i] ≤ 1000`.

**Approach.** Compute prefix sums for O(1) window sums. Sweep one position at a time, treating the position as the end of the *second* window; track the best `firstLen`-window sum that ends at or before the start of the current `secondLen` window. Do this twice — once with first-then-second, once with second-then-first — and take the max. O(n) time, O(n) space (prefix array); the technique generalizes to k disjoint windows by holding `k − 1` running best-prefixes.

```java
class Solution {
    public int maxSumTwoNoOverlap(int[] nums, int firstLen, int secondLen) {
        int n = nums.length;
        int[] pre = new int[n + 1];
        for (int i = 0; i < n; i++) pre[i + 1] = pre[i] + nums[i];
        return Math.max(best(pre, firstLen, secondLen), best(pre, secondLen, firstLen));
    }

    private int best(int[] pre, int L, int M) {
        int n = pre.length - 1, maxL = 0, ans = 0;
        for (int i = L + M; i <= n; i++) {
            maxL = Math.max(maxL, pre[i - M] - pre[i - M - L]);   // best L-window ending by i-M
            ans = Math.max(ans, maxL + pre[i] - pre[i - M]);      // + current M-window
        }
        return ans;
    }
}
```

**Complexity** — Time O(n), Space O(n). **Edge cases:** `firstLen + secondLen == n` evaluates exactly one split; equal lengths still need both orderings tried (the helper's asymmetry depends on which window is to the left); zero-valued elements are fine.

---

### Problem 43: Grumpy Bookstore Owner — Fixed window over a "bonus" array

**Statement.** A shop has `customers[i]` people at minute `i`; `grumpy[i] ∈ {0,1}` indicates if the owner is grumpy that minute (grumpy minutes lose those customers' satisfaction). The owner may use a "secret technique" for **exactly `minutes` consecutive minutes** to suppress grumpiness in that window. Return the maximum number of satisfied customers possible.

**Constraints.** `1 ≤ n ≤ 2·10^4`; `0 ≤ customers[i] ≤ 1000`; `grumpy[i] ∈ {0,1}`; `1 ≤ minutes ≤ n`.

**Approach.** Customers from non-grumpy minutes are always satisfied — sum them as a baseline. The window's *gain* is the sum of `customers[i] * grumpy[i]` over the window — the customers we'd otherwise lose. Slide a fixed window of width `minutes`, tracking that gain's maximum. Answer is baseline + maxGain. This is a "transform-to-a-bonus-array" idiom that recurs in problems with a one-shot intervention. O(n) time, O(1) space.

```java
class Solution {
    public int maxSatisfied(int[] customers, int[] grumpy, int minutes) {
        int base = 0;
        for (int i = 0; i < customers.length; i++) {
            if (grumpy[i] == 0) base += customers[i];
        }
        int gain = 0, maxGain = 0;
        for (int i = 0; i < customers.length; i++) {
            if (grumpy[i] == 1) gain += customers[i];
            if (i >= minutes && grumpy[i - minutes] == 1) gain -= customers[i - minutes];
            maxGain = Math.max(maxGain, gain);
        }
        return base + maxGain;
    }
}
```

**Complexity** — Time O(n), Space O(1). **Edge cases:** owner never grumpy → maxGain 0 → answer = baseline = total customers; always grumpy → answer = max window sum; `minutes == n` → maxGain is the entire grumpy-weighted total.

---

### Problem 44: Number of Substrings Containing All Three Characters — Variable window count via "leftmost valid"

**Statement.** Given a string `s` containing only `a`, `b`, `c`, return the number of substrings containing **at least one** of each of `a`, `b`, and `c`.

**Constraints.** `3 ≤ s.length ≤ 5·10^4`.

**Approach.** Expand `right`; once the window contains all three characters, every substring extending further right is also valid. Specifically, when `[left, right]` first becomes valid (after shrinking `left` as much as possible while still valid), every starting index in `[0, left]` paired with the current `right` produces a valid substring — and *every* extension of that ending past `right` is also valid. The cleanest formulation: for each `right`, after maintaining the window so it's the *smallest valid window ending at `right`*, all `(s.length() − right)` extensions count, and each of the `left + 1` start positions ≤ `left` works for it. Equivalent: add `left + 1` to the count once the window is valid. Total O(n).

```java
class Solution {
    public int numberOfSubstrings(String s) {
        int[] count = new int[3];
        int left = 0, total = 0;
        for (int right = 0; right < s.length(); right++) {
            count[s.charAt(right) - 'a']++;
            while (count[0] > 0 && count[1] > 0 && count[2] > 0) {
                count[s.charAt(left++) - 'a']--;
            }
            total += left;   // (left) starting indices yield valid substrings ending at right
        }
        return total;
    }
}
```

**Dry run** on `s="abcabc"`: index 2 (`c`) gives the first valid window `abc`; shrink to `bc` (invalid), so `left=1` and total += 1. Index 3 (`a`) makes `bca` valid → shrink to `ca`, left=2, total += 2. Continues; final total **10**.

**Complexity** — Time O(n), Space O(1) (the count array is length 3). **Edge cases:** `s.length() < 3` returns 0; strings missing any of `a, b, c` return 0; long stretches of one letter contribute nothing until all three are present.

---

### Problem 45: Smallest Range Covering Elements from K Lists — K-way two pointers (heap-driven window)

**Statement.** Given `k` lists of sorted integers, find the smallest range `[a, b]` that contains **at least one number from each** of the `k` lists. If multiple ranges have the same width, return the one with the smallest `a`.

**Constraints.** `1 ≤ k ≤ 3500`; total elements `≤ 3500·k`; values fit in `int`.

**Approach.** Use a min-heap of `(value, listIndex, indexInList)` to always know the current minimum, and track the *maximum* across the heap. The current range is `[min, max]`. To shrink it, pop the min and push the next element from its list; if a list is exhausted, stop — no further range covers all `k` lists. Record the best range whenever the heap is full. This is conceptually a *k-pointer* sliding window where the heap orders the moves. O(N log k) time, O(k) space, where N is the total element count.

```
 lists: [4,10,15,24,26], [0,9,12,20], [5,18,22,30]
 start with mins {4,0,5} -> range [0,5] width 5
 pop 0 push 9      -> {4,9,5}, max=9, range [4,9] width 5
 pop 4 push 10     -> {10,9,5}, max=10, range [5,10] width 5
 ... best width is 5
```

```java
class Solution {
    public int[] smallestRange(java.util.List<java.util.List<Integer>> nums) {
        java.util.PriorityQueue<int[]> pq = new java.util.PriorityQueue<>((a, b) -> a[0] - b[0]);
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < nums.size(); i++) {
            int v = nums.get(i).get(0);
            pq.offer(new int[]{v, i, 0});
            max = Math.max(max, v);
        }
        int bestLo = pq.peek()[0], bestHi = max;
        while (true) {
            int[] top = pq.poll();
            if (max - top[0] < bestHi - bestLo) { bestLo = top[0]; bestHi = max; }
            int li = top[1], idx = top[2] + 1;
            if (idx == nums.get(li).size()) break;          // a list ran out -> done
            int v = nums.get(li).get(idx);
            max = Math.max(max, v);
            pq.offer(new int[]{v, li, idx});
        }
        return new int[]{bestLo, bestHi};
    }
}
```

**Complexity** — Time O(N log k), Space O(k). **Edge cases:** any list of length 1 forces termination after one pop on that list; lists with identical singleton values give a width-0 range; ties in width are resolved by the strict `<` keeping the leftmost best.

---

### Problem 46: Trapping Rain Water II — Heap-bounded BFS (the 2-D water analogy)

**Statement.** Given an `m × n` matrix `heightMap[][]` of non-negative integers representing the elevation of a 2-D surface, compute the total volume of water trapped after raining.

**Constraints.** `1 ≤ m, n ≤ 200`; `0 ≤ heightMap[i][j] ≤ 2·10^4`.

**Approach.** The 1-D trick of "the shorter wall bounds the water" generalizes to 2-D as **the lowest cell on the current boundary**. Push all border cells into a min-heap; repeatedly pop the lowest boundary cell and visit its 4-neighbors. For each unvisited neighbor, the water it can hold equals `max(0, currentBoundaryHeight − neighborHeight)`; then push the neighbor with height `max(currentBoundaryHeight, neighborHeight)` (effectively raising the water level it presents to its own neighbors). This is the classic Dijkstra-flavored expansion of the 1-D two-pointer logic. O(m·n·log(m·n)) time.

```java
class Solution {
    public int trapRainWater(int[][] heightMap) {
        int m = heightMap.length, n = heightMap[0].length;
        if (m < 3 || n < 3) return 0;
        boolean[][] seen = new boolean[m][n];
        java.util.PriorityQueue<int[]> pq = new java.util.PriorityQueue<>((a, b) -> a[2] - b[2]);
        for (int i = 0; i < m; i++) {
            pq.offer(new int[]{i, 0, heightMap[i][0]});
            pq.offer(new int[]{i, n - 1, heightMap[i][n - 1]});
            seen[i][0] = seen[i][n - 1] = true;
        }
        for (int j = 1; j < n - 1; j++) {
            pq.offer(new int[]{0, j, heightMap[0][j]});
            pq.offer(new int[]{m - 1, j, heightMap[m - 1][j]});
            seen[0][j] = seen[m - 1][j] = true;
        }
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        int water = 0;
        while (!pq.isEmpty()) {
            int[] cur = pq.poll();
            for (int[] d : dirs) {
                int r = cur[0] + d[0], c = cur[1] + d[1];
                if (r < 0 || r >= m || c < 0 || c >= n || seen[r][c]) continue;
                seen[r][c] = true;
                water += Math.max(0, cur[2] - heightMap[r][c]);
                pq.offer(new int[]{r, c, Math.max(cur[2], heightMap[r][c])});
            }
        }
        return water;
    }
}
```

**Complexity** — Time O(m·n·log(m·n)), Space O(m·n). **Edge cases:** any dimension < 3 traps 0 (no interior cell); a strictly-increasing or "bowl" surface stresses the heap evenly; pushing `max(boundary, cellHeight)` is the critical step — pushing only `cellHeight` would let water leak through low neighbors.

---

### Problem 47: Substring with Concatenation of All Words — Fixed window of word-tokens

**Statement.** Given a string `s` and a list `words` of equal-length strings, return all starting indices in `s` of substrings that are a **concatenation of every word in `words` exactly once** (in any order).

**Constraints.** `1 ≤ s.length ≤ 10^4`; `1 ≤ words.length ≤ 5000`; word length `L` ≤ 30; all words equal length.

**Approach.** Fix a starting offset `o ∈ [0, L)`; the string then splits into a sequence of `L`-length tokens at positions `o, o+L, o+2L, …`. Slide a fixed *token-window* of size `words.length` across these tokens, maintaining a frequency map; compare against the target frequency. Aborting on a never-needed token jumps `left` past it. Each character is touched O(1) per starting offset, giving O(n·L) total — much better than the naive O(n · words.length · L).

```java
class Solution {
    public java.util.List<Integer> findSubstring(String s, String[] words) {
        java.util.List<Integer> res = new java.util.ArrayList<>();
        int L = words[0].length(), k = words.length, n = s.length();
        if (n < L * k) return res;
        java.util.Map<String, Integer> need = new java.util.HashMap<>();
        for (String w : words) need.merge(w, 1, Integer::sum);

        for (int offset = 0; offset < L; offset++) {
            int left = offset, matched = 0;
            java.util.Map<String, Integer> win = new java.util.HashMap<>();
            for (int right = offset; right + L <= n; right += L) {
                String w = s.substring(right, right + L);
                if (!need.containsKey(w)) {                      // reset
                    win.clear();
                    matched = 0;
                    left = right + L;
                    continue;
                }
                win.merge(w, 1, Integer::sum);
                matched++;
                while (win.get(w) > need.get(w)) {               // too many of w
                    String leftWord = s.substring(left, left + L);
                    win.merge(leftWord, -1, Integer::sum);
                    matched--;
                    left += L;
                }
                if (matched == k) {
                    res.add(left);
                    // slide one word forward
                    String leftWord = s.substring(left, left + L);
                    win.merge(leftWord, -1, Integer::sum);
                    matched--;
                    left += L;
                }
            }
        }
        return res;
    }
}
```

**Complexity** — Time O(n·L), Space O(k·L). **Edge cases:** `n < L·k` returns empty; duplicate words are handled by frequency counts, not just set membership; an off-grid match at offset `o' ≠ o` is found by trying all `L` starting offsets.

---

### Problem 48: K-Diff Pairs in an Array — Two pointers on a sorted array

**Statement.** Given an array `nums` and an integer `k`, return the number of **unique** pairs `(a, b)` in the array with `a − b = k` (or equivalently `|a − b| = k`). Pairs are unique by value, not by index.

**Constraints.** `1 ≤ n ≤ 10^4`; `-10^7 ≤ nums[i] ≤ 10^7`; `0 ≤ k ≤ 10^7`.

**Approach.** After sorting, every pair satisfying `b − a = k` has `a` on the left and `b` on the right. Use same-direction two pointers `lo < hi`: if `nums[hi] − nums[lo] < k` advance `hi`; if `> k` advance `lo`; if equal, record and **skip duplicates** on both sides. Because each pointer moves forward only, the post-sort scan is O(n). Sorting cost O(n log n) dominates. The `k = 0` case is special — pairs are pairs of equal values, counted via runs.

```java
class Solution {
    public int findPairs(int[] nums, int k) {
        if (k < 0) return 0;
        java.util.Arrays.sort(nums);
        int lo = 0, hi = 1, pairs = 0;
        while (hi < nums.length) {
            if (lo == hi || nums[hi] - nums[lo] < k) {
                hi++;
            } else if (nums[hi] - nums[lo] > k) {
                lo++;
            } else {
                pairs++;
                int val = nums[lo];
                while (lo < nums.length && nums[lo] == val) lo++;   // skip dup left
                hi = Math.max(hi + 1, lo);
            }
        }
        return pairs;
    }
}
```

**Dry run** on `nums=[3,1,4,1,5], k=2` → sorted `[1,1,3,4,5]`: pair `(1,3)`; pair `(3,5)`. Answer **2**.

**Complexity** — Time O(n log n), Space O(1) auxiliary (or O(log n) sort stack). **Edge cases:** `k = 0` returns the number of values appearing ≥ 2 times; negative `k` is invalid by the absolute-difference reading and returns 0; the `hi = max(hi+1, lo)` line prevents `hi` from being left behind after `lo` jumps over duplicates.

---

### Problem 49: 3-Sum Closest — Sort + two pointers tracking the closest sum

**Statement.** Given an integer array `nums` and a `target`, find three integers in `nums` whose sum is **closest** to `target`. Return that closest sum. Exactly one solution exists.

**Constraints.** `3 ≤ n ≤ 1000`; `-1000 ≤ nums[i] ≤ 1000`; `-10^4 ≤ target ≤ 10^4`.

**Approach.** Sort. For each pivot `i`, run opposite-ends two pointers (`lo`, `hi`) over the suffix; at each step compare the current `nums[i] + nums[lo] + nums[hi]` against the best `|sum − target|` seen, then move `lo++` if the sum is below target or `hi--` if above. Sorting allows monotone movement; the O(n²) total beats brute-force O(n³). A small extra optimization: skip equal pivots to avoid redundant work, and break early when `nums[i] * 3` already exceeds target plus current best (rarely needed at `n ≤ 1000`).

```java
class Solution {
    public int threeSumClosest(int[] nums, int target) {
        java.util.Arrays.sort(nums);
        int best = nums[0] + nums[1] + nums[2];
        for (int i = 0; i < nums.length - 2; i++) {
            int lo = i + 1, hi = nums.length - 1;
            while (lo < hi) {
                int sum = nums[i] + nums[lo] + nums[hi];
                if (Math.abs(sum - target) < Math.abs(best - target)) best = sum;
                if (sum == target) return sum;        // exact match - cannot improve
                if (sum < target) lo++;
                else hi--;
            }
        }
        return best;
    }
}
```

**Complexity** — Time O(n²), Space O(1) extra (or O(log n) sort stack). **Edge cases:** ties in `|sum − target|` keep the earlier candidate (the `<` not `<=`); an exact match short-circuits the loop; arrays with all-equal elements still produce a defined answer through the unchanged pointer movement.

---

### Problem 50: Maximum Number of Visible Points — Sliding window on sorted angles (circular)

**Statement.** Given `points` (a list of `[x, y]`) and your `location` and viewing angle `angle` (in degrees), return the maximum number of points you can see simultaneously by pointing in some direction. Points at your exact location are *always* visible.

**Constraints.** `1 ≤ points.length ≤ 10^5`; `0 ≤ angle ≤ 360`; coordinates fit in `int`.

**Approach.** Compute each point's polar angle relative to `location` using `Math.atan2`, in degrees. Sort the angles; to handle the wrap-around (a 5°–355° window also covers 355°→360°→0°→5°), duplicate the sorted angles with `+ 360` appended. Now slide a variable window over the augmented array: for each `right`, advance `left` while `angles[right] − angles[left] > angle`. The largest `right − left + 1` is the maximum visible count among non-coincident points; finally add the number of points coincident with `location`. Sort dominates at O(n log n); the window sweep is O(n).

```java
class Solution {
    public int visiblePoints(java.util.List<java.util.List<Integer>> points, int angle, java.util.List<Integer> location) {
        int lx = location.get(0), ly = location.get(1);
        java.util.List<Double> angles = new java.util.ArrayList<>();
        int same = 0;
        for (java.util.List<Integer> p : points) {
            int dx = p.get(0) - lx, dy = p.get(1) - ly;
            if (dx == 0 && dy == 0) { same++; continue; }
            angles.add(Math.toDegrees(Math.atan2(dy, dx)));
        }
        java.util.Collections.sort(angles);
        int m = angles.size();
        // duplicate +360 for wrap-around
        for (int i = 0; i < m; i++) angles.add(angles.get(i) + 360.0);

        int left = 0, best = 0;
        for (int right = 0; right < angles.size(); right++) {
            while (angles.get(right) - angles.get(left) > angle + 1e-9) left++;
            best = Math.max(best, right - left + 1);
        }
        return best + same;
    }
}
```

**Complexity** — Time O(n log n), Space O(n). **Edge cases:** `angle == 360` lets you see every non-coincident point; all points coincide with `location` → answer = `points.size()`; floating-point comparison uses a small epsilon to avoid boundary off-by-one; duplicating the array (instead of modular subtraction) is the cleanest way to handle the circular window.

---

### Problem 51: Longest Substring with At Least K Repeating Characters — Window over fixed unique-count (expert)

**Statement.** Given a string `s` and an integer `k`, return the length of the longest substring in which **every** character appears at least `k` times.

**Constraints.** `1 ≤ s.length ≤ 10^4`; `1 ≤ k ≤ 10^5`; `s` consists of lowercase English letters.

**Approach.** A direct sliding window doesn't apply because the validity condition ("all chars hit ≥ k") is *not monotonic* in window size — extending might add a new under-quota char. The senior trick: **iterate `t` from 1 to 26 and, for each fixed `t`, find the longest window containing exactly `t` distinct characters such that all of them reach count `k`**. With `t` fixed, the distinct-count is monotonic, so a window works: track `unique` (distinct chars) and `noLessThanK` (chars whose count has reached `k`); shrink while `unique > t`; whenever `unique == t == noLessThanK`, the window is valid. Take the best across all `t`. Total O(26 · n) = O(n) time, O(1) space. The classical divide-and-conquer also works but this window framing is elegant and fully linear.

```java
class Solution {
    public int longestSubstring(String s, int k) {
        int best = 0;
        int totalUnique = (int) s.chars().distinct().count();
        for (int t = 1; t <= totalUnique; t++) {
            best = Math.max(best, longestWithExactlyTUnique(s, k, t));
        }
        return best;
    }

    private int longestWithExactlyTUnique(String s, int k, int t) {
        int[] cnt = new int[26];
        int left = 0, unique = 0, noLessThanK = 0, best = 0;
        for (int right = 0; right < s.length(); right++) {
            int c = s.charAt(right) - 'a';
            if (cnt[c]++ == 0) unique++;
            if (cnt[c] == k) noLessThanK++;
            while (unique > t) {
                int lc = s.charAt(left++) - 'a';
                if (cnt[lc]-- == k) noLessThanK--;
                if (cnt[lc] == 0) unique--;
            }
            if (unique == t && noLessThanK == t) {
                best = Math.max(best, right - left + 1);
            }
        }
        return best;
    }
}
```

**Dry run** on `s="ababbc", k=2`: with `t = 2` ({a,b}), the window `ababb` gives `unique=2, noLessThanK=2` (a:2, b:3) — length 5. No higher `t` improves it. Answer **5**.

**Complexity** — Time O(26·n) = O(n), Space O(1). **Edge cases:** `k > s.length()` returns 0; `k = 1` returns `s.length()` (every char satisfies trivially); a string of one repeated letter returns its length when `k ≤ count` else 0. The `t` outer loop is what tames the otherwise non-monotonic invariant — this is the canonical "fix-a-parameter then window" senior pattern.

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
