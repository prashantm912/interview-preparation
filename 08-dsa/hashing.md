# Hash Tables & Maps

Hash tables trade memory for speed, turning average-case `O(n)` lookups into `O(1)` by mapping keys to array indices through a hash function. They are the single most useful data structure in coding interviews — the moment you see "have I seen this before?", "count frequencies", or "find a pair that sums to X", a hash map is usually the answer.

[← Back to master index](../README.md) | [← DSA index](README.md)

---

## Concept & Intuition

A **hash table** stores key-value pairs in a backing array (the *buckets*). A **hash function** `h(key)` converts a key into an integer, and `index = h(key) % capacity` picks the bucket. If the function distributes keys uniformly, each bucket holds roughly one entry, so `get`/`put`/`remove` are `O(1)` on average.

**When to use it**
- You need fast membership tests (`Set`) or key→value lookups (`Map`).
- You need to count occurrences (frequency maps).
- You need to deduplicate, group, or index data by some derived key.
- You want to convert an `O(n²)` "check every pair" loop into `O(n)` by remembering what you've seen.

**Invariants you must protect**
1. Equal keys (`a.equals(b)`) **must** produce equal hash codes (`a.hashCode() == b.hashCode()`). Violating this loses entries silently.
2. A key's hash code must not change while it lives in the table (use immutable keys).
3. `load factor = size / capacity` is kept below a threshold (Java's default `0.75`) by *resizing* — doubling capacity and rehashing — to keep buckets short.

**Collision resolution.** Two distinct keys can hash to the same bucket. Two strategies dominate:

```
SEPARATE CHAINING                  OPEN ADDRESSING (linear probing)
each bucket = a linked list        all entries live in the array itself

 idx                                idx
 [0] -> (key:"cat") -> (key:"dog")  [0] (empty)
 [1] -> null                        [1] ("cat")
 [2] -> ("ape")                     [2] ("ape")
 [3] -> ("ox") -> ("elk")           [3] ("ox")
                                    [4] ("elk")  <- bumped from [3] on collision
```

- **Separate chaining**: each bucket points to a list (Java 8+ converts long chains to balanced trees → worst case `O(log n)`). Simple, tolerant of high load factors, but uses extra pointers.
- **Open addressing**: on collision, probe for the next free slot (linear, quadratic, or double hashing). Cache-friendly, no pointers, but suffers *clustering* and needs tombstones on deletion. Load factor must stay well under 1.

---

## Complexity Cheat-Sheet

| Operation | Average | Worst (bad hash / adversarial) | Notes |
|-----------|---------|--------------------------------|-------|
| `put` / insert | O(1) | O(n) chaining, O(log n) Java treeified | Amortized O(1) including resizes |
| `get` / lookup | O(1) | O(n) / O(log n) | |
| `remove` | O(1) | O(n) / O(log n) | Open addressing needs tombstones |
| `containsKey` | O(1) | O(n) | |
| Resize / rehash | O(n) | O(n) | Amortized into inserts |
| Iterate all entries | O(n + capacity) | O(n + capacity) | Includes empty buckets |
| Space | O(n) | O(n) | Plus unused capacity (~1.33×n at 0.75 LF) |

**Amortized insert.** Doubling on resize means `n` inserts cost `O(n)` total (geometric series `1 + 2 + 4 + … + n ≈ 2n`), so each insert is `O(1)` amortized even though individual resizes are `O(n)`.

---

## Patterns & Recognition

Reach for a hash map / set when you notice any of these signals:

- **"Find a pair / triple that satisfies a relation"** → store complements as you scan (two-sum family).
- **"Count / frequency / how many times"** → `Map<element, count>`.
- **"Group by a property"** → key = the canonical form of that property (sorted string, signature).
- **"Subarray / substring with property X"** → prefix-sum or sliding-window state stored in a map.
- **"First/last unique", "duplicate", "distinct"** → seen-set or count map.
- **"Top K", "K most frequent"** → frequency map + heap or bucket sort.
- **"Cache with eviction", "design X"** → hash map for O(1) access + auxiliary structure (doubly linked list, heap) for ordering.
- **"O(n) expected, O(1) lookup"** stated in constraints → strong hint a hash structure is intended.

The core mental move: **trade space for time by remembering past state keyed by something you can compute on the fly.**

---

## Coding Problems

### Problem 1: Two Sum

Given an array `nums` and a target `target`, return the indices of the two numbers that add up to `target`. Exactly one solution exists; you may not use the same element twice.
Constraints: `2 ≤ nums.length ≤ 10⁴`, `-10⁹ ≤ nums[i], target ≤ 10⁹`.

**Approach.** Brute force checks every pair in `O(n²)`. Optimal: as you scan, for each `x` ask "have I already seen `target - x`?" Store value→index in a map for `O(1)` lookups.

```java
import java.util.*;

class Solution {
    public int[] twoSum(int[] nums, int target) {
        Map<Integer, Integer> seen = new HashMap<>(); // value -> index
        for (int i = 0; i < nums.length; i++) {
            int complement = target - nums[i];
            if (seen.containsKey(complement)) {
                return new int[] { seen.get(complement), i };
            }
            seen.put(nums[i], i);
        }
        return new int[0]; // unreachable given the guarantee
    }
}
```

**Walkthrough.** `nums = [2,7,11,15], target = 9`. i=0: need 7, not seen, store {2:0}. i=1: need 2, found at index 0 → return `[0,1]`.

**Time:** O(n) **Space:** O(n)

**Follow-ups:** Return values instead of indices; sorted input (use two pointers, O(1) space); count *all* pairs that sum to target; 3-Sum / 4-Sum (sort + fix one, then two-pointer or hash inner loop).

---

### Problem 2: Contains Duplicate

Return `true` if any value appears at least twice in `nums`.
Constraints: `1 ≤ nums.length ≤ 10⁵`.

**Approach.** Brute force compares all pairs `O(n²)`. Sorting gives `O(n log n)`. Optimal uses a hash set: insert each value, return `true` on the first failed insert.

```java
import java.util.*;

class Solution {
    public boolean containsDuplicate(int[] nums) {
        Set<Integer> seen = new HashSet<>();
        for (int x : nums) {
            if (!seen.add(x)) return true; // add returns false if already present
        }
        return false;
    }
}
```

**Walkthrough.** `[1,2,3,1]`: add 1✓, 2✓, 3✓, add 1 → already present → `true`.

**Time:** O(n) **Space:** O(n)

**Follow-ups:** "Contains Duplicate II" (duplicate within distance k → sliding-window set); "Contains Duplicate III" (values within t and indices within k → bucketed map / TreeSet).

---

### Problem 3: Valid Anagram

Given two strings `s` and `t`, return `true` if `t` is an anagram of `s`.
Constraints: `1 ≤ s.length, t.length ≤ 5·10⁴`, lowercase English letters.

**Approach.** Sorting both is `O(n log n)`. Optimal: count characters. A fixed 26-slot array beats a `HashMap` here since the alphabet is known and small.

```java
class Solution {
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
}
```

**Walkthrough.** `s="anagram", t="nagaram"`: increments from `s` and decrements from `t` cancel to all zeros → `true`.

**Time:** O(n) **Space:** O(1) (fixed 26 ints)

**Follow-ups:** Unicode input (use a `HashMap<Character,Integer>`); "Group Anagrams" (next problem); "Find All Anagrams in a String" (sliding window of counts).

---

### Problem 4: Group Anagrams

Given an array of strings, group anagrams together. Return a list of groups in any order.
Constraints: `1 ≤ strs.length ≤ 10⁴`, `0 ≤ strs[i].length ≤ 100`, lowercase letters.

**Approach.** Two strings are anagrams iff they share a canonical key. Two key choices: the **sorted string** (`O(k log k)` per word) or a **count signature** like `"a2b1c0…"` (`O(k)` per word). Bucket words by that key in a map.

```java
import java.util.*;

class Solution {
    public List<List<String>> groupAnagrams(String[] strs) {
        Map<String, List<String>> groups = new HashMap<>();
        for (String s : strs) {
            char[] cnt = new char[26];
            for (char c : s.toCharArray()) cnt[c - 'a']++;
            String key = new String(cnt); // O(k) signature, faster than sorting
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }
        return new ArrayList<>(groups.values());
    }
}
```

**Walkthrough.** `["eat","tea","tan","ate","nat","bat"]`. "eat","tea","ate" all map to the signature with one each of a,e,t → one group; "tan","nat" → another; "bat" → its own. Result: `[[eat,tea,ate],[tan,nat],[bat]]`.

**Time:** O(n·k) with count key (k = max word length) **Space:** O(n·k)

**Follow-ups:** Group by sorted key vs count key trade-off; group shifted strings ("Group Shifted Strings"); handle uppercase / Unicode.

---

### Problem 5: First Unique Character in a String

Given a string `s`, return the index of the first non-repeating character, or `-1` if none exists.
Constraints: `1 ≤ s.length ≤ 10⁵`, lowercase letters.

**Approach.** Two passes: first count every character, then scan left-to-right for the first with count 1. A fixed array keeps it `O(1)` extra space.

```java
class Solution {
    public int firstUniqChar(String s) {
        int[] count = new int[26];
        for (int i = 0; i < s.length(); i++) count[s.charAt(i) - 'a']++;
        for (int i = 0; i < s.length(); i++) {
            if (count[s.charAt(i) - 'a'] == 1) return i;
        }
        return -1;
    }
}
```

**Walkthrough.** `s="leetcode"`: counts l1,e3,t1,c1,o1,d1. First index with count 1 is `l` at index 0 → return `0`. For `"loveleetcode"` the answer is index 2 (`v`).

**Time:** O(n) **Space:** O(1)

**Follow-ups:** Stream of characters where you must answer the "first unique so far" after each insert (use a `LinkedHashMap` + queue); first unique *word*; case-insensitive.

---

### Problem 6: Subarray Sum Equals K

Given an integer array `nums` and integer `k`, return the number of **contiguous subarrays** whose sum equals `k`.
Constraints: `1 ≤ nums.length ≤ 2·10⁴`, `-1000 ≤ nums[i] ≤ 1000`, `-10⁷ ≤ k ≤ 10⁷`. Note: values can be negative, so sliding window does **not** work.

**Approach.** Brute force sums every subarray in `O(n²)`. Optimal uses **prefix sums + hash map**. If `prefix[j] - prefix[i] = k`, the subarray `(i, j]` sums to `k`. So while scanning, for each running sum `pre`, count how many earlier prefix sums equal `pre - k`. Store prefix-sum frequencies in a map, seeding `{0:1}` for subarrays starting at index 0.

```java
import java.util.*;

class Solution {
    public int subarraySum(int[] nums, int k) {
        Map<Integer, Integer> prefixCount = new HashMap<>();
        prefixCount.put(0, 1); // empty prefix
        int sum = 0, result = 0;
        for (int x : nums) {
            sum += x;
            result += prefixCount.getOrDefault(sum - k, 0);
            prefixCount.merge(sum, 1, Integer::sum);
        }
        return result;
    }
}
```

**Walkthrough.** `nums=[1,1,1], k=2`. map={0:1}. x=1→sum=1, need −1 (0 found), map={0:1,1:1}. x=1→sum=2, need 0 (1 found)→result=1, map adds 2. x=1→sum=3, need 1 (1 found)→result=2. Answer `2`.

**Time:** O(n) **Space:** O(n)

**Follow-ups:** Longest subarray summing to k (store first index of each prefix sum); subarray sum divisible by k (key on `sum % k`, handle negatives); count subarrays with at most/exactly K odd numbers (prefix counts of parity); contiguous array of equal 0s and 1s.

---

### Problem 7: Top K Frequent Elements

Given an array `nums` and integer `k`, return the `k` most frequent elements (any order).
Constraints: `1 ≤ nums.length ≤ 10⁵`, `k` is in `[1, distinct count]`.

**Approach.** Count frequencies in a map. Then either (a) a **min-heap of size k** giving `O(n log k)`, or (b) **bucket sort** by frequency giving `O(n)` since frequencies are bounded by `n`. The heap is the canonical interview answer; bucket sort is the optimal follow-up.

```java
import java.util.*;

class Solution {
    public int[] topKFrequent(int[] nums, int k) {
        Map<Integer, Integer> freq = new HashMap<>();
        for (int x : nums) freq.merge(x, 1, Integer::sum);

        // Min-heap ordered by frequency; keep only k largest.
        PriorityQueue<Integer> heap =
            new PriorityQueue<>((a, b) -> freq.get(a) - freq.get(b));
        for (int key : freq.keySet()) {
            heap.offer(key);
            if (heap.size() > k) heap.poll(); // evict smallest frequency
        }

        int[] res = new int[k];
        for (int i = k - 1; i >= 0; i--) res[i] = heap.poll();
        return res;
    }
}
```

**Bucket-sort variant (O(n)):**

```java
import java.util.*;

class Solution {
    public int[] topKFrequentBucket(int[] nums, int k) {
        Map<Integer, Integer> freq = new HashMap<>();
        for (int x : nums) freq.merge(x, 1, Integer::sum);

        List<Integer>[] bucket = new List[nums.length + 1]; // index = frequency
        for (var e : freq.entrySet()) {
            int f = e.getValue();
            if (bucket[f] == null) bucket[f] = new ArrayList<>();
            bucket[f].add(e.getKey());
        }
        int[] res = new int[k];
        int idx = 0;
        for (int f = bucket.length - 1; f >= 0 && idx < k; f--) {
            if (bucket[f] == null) continue;
            for (int v : bucket[f]) {
                if (idx == k) break;
                res[idx++] = v;
            }
        }
        return res;
    }
}
```

**Walkthrough.** `nums=[1,1,1,2,2,3], k=2`. freq={1:3,2:2,3:1}. Heap keeps {1,2} (frequencies 3,2). Bucket: index3→[1], index2→[2], index1→[3]; scan high→low, take 1 then 2 → `[1,2]`.

**Time:** O(n log k) heap, O(n) bucket **Space:** O(n)

**Follow-ups:** Top K frequent *words* (tie-break alphabetically — custom comparator); streaming top-K (count-min sketch / approximate); K closest points (same heap pattern).

---

### Problem 8: LRU Cache (design)

Design a cache with capacity `c` supporting `get(key)` and `put(key, value)` in **O(1)**. When full, evict the **least recently used** entry. Accessing or updating a key makes it most recently used.
Constraints: up to `2·10⁵` operations.

**Approach.** A `HashMap` gives O(1) lookup but no ordering. A **doubly linked list** gives O(1) move-to-front and O(1) tail removal. Combine them: map `key → node`, list ordered most-recent (head) → least-recent (tail). On access, unlink the node and reinsert at head; on overflow, drop the tail. Java's `LinkedHashMap` does this internally, but interviewers want the hand-rolled version.

```java
import java.util.*;

class LRUCache {
    private static class Node {
        int key, val;
        Node prev, next;
        Node(int k, int v) { key = k; val = v; }
    }

    private final int capacity;
    private final Map<Integer, Node> map = new HashMap<>();
    private final Node head = new Node(0, 0); // dummy MRU sentinel
    private final Node tail = new Node(0, 0); // dummy LRU sentinel

    public LRUCache(int capacity) {
        this.capacity = capacity;
        head.next = tail;
        tail.prev = head;
    }

    public int get(int key) {
        Node n = map.get(key);
        if (n == null) return -1;
        moveToFront(n);
        return n.val;
    }

    public void put(int key, int value) {
        Node n = map.get(key);
        if (n != null) {
            n.val = value;
            moveToFront(n);
            return;
        }
        if (map.size() == capacity) {
            Node lru = tail.prev;
            remove(lru);
            map.remove(lru.key);
        }
        Node fresh = new Node(key, value);
        map.put(key, fresh);
        addToFront(fresh);
    }

    private void remove(Node n) {
        n.prev.next = n.next;
        n.next.prev = n.prev;
    }

    private void addToFront(Node n) {
        n.next = head.next;
        n.prev = head;
        head.next.prev = n;
        head.next = n;
    }

    private void moveToFront(Node n) {
        remove(n);
        addToFront(n);
    }
}
```

**Walkthrough.** capacity 2. `put(1,1)`, `put(2,2)` → list head[2,1]tail. `get(1)`→1, moves 1 to front → [1,2]. `put(3,3)` → full, evict tail (key 2) → [3,1]. `get(2)`→ −1. `get(1)`→1.

**Time:** O(1) per operation **Space:** O(capacity)

**Follow-ups:** Thread-safe LRU (segment locking / `ConcurrentHashMap` + striped locks); TTL expiry; **LFU** (next problem); using `LinkedHashMap` with `removeEldestEntry`.

---

### Problem 9: LFU Cache (design — hard / senior)

Design a cache with capacity `c` and O(1) `get`/`put` that evicts the **least frequently used** key. On a frequency tie, evict the **least recently used** among them.
Constraints: up to `2·10⁵` operations.

**Approach.** This is the classic "true O(1) LFU" design. Maintain three structures:
1. `vals: key → value`
2. `counts: key → frequency`
3. `lists: frequency → LinkedHashSet<key>` — buckets of keys at each frequency, with insertion order preserving LRU *within* a frequency.

Track `minFreq` (the current lowest frequency present). On access, move the key from bucket `f` to bucket `f+1`; if bucket `f` empties and `f == minFreq`, increment `minFreq`. On eviction, remove the first (oldest) key in bucket `minFreq`. `LinkedHashSet` gives O(1) add/remove plus ordering.

```java
import java.util.*;

class LFUCache {
    private final int capacity;
    private int minFreq = 0;
    private final Map<Integer, Integer> vals = new HashMap<>();
    private final Map<Integer, Integer> counts = new HashMap<>();
    private final Map<Integer, LinkedHashSet<Integer>> lists = new HashMap<>();

    public LFUCache(int capacity) {
        this.capacity = capacity;
    }

    public int get(int key) {
        if (!vals.containsKey(key)) return -1;
        touch(key);
        return vals.get(key);
    }

    public void put(int key, int value) {
        if (capacity <= 0) return;
        if (vals.containsKey(key)) {
            vals.put(key, value);
            touch(key);
            return;
        }
        if (vals.size() >= capacity) {
            LinkedHashSet<Integer> minList = lists.get(minFreq);
            int evict = minList.iterator().next(); // oldest at min frequency
            minList.remove(evict);
            vals.remove(evict);
            counts.remove(evict);
        }
        vals.put(key, value);
        counts.put(key, 1);
        lists.computeIfAbsent(1, k -> new LinkedHashSet<>()).add(key);
        minFreq = 1;
    }

    // Bump a key's frequency by one, maintaining buckets and minFreq.
    private void touch(int key) {
        int f = counts.get(key);
        counts.put(key, f + 1);
        lists.get(f).remove(key);
        if (lists.get(f).isEmpty() && f == minFreq) minFreq++;
        lists.computeIfAbsent(f + 1, k -> new LinkedHashSet<>()).add(key);
    }
}
```

**Walkthrough.** capacity 2. `put(1,1)` freq{1:1}, `put(2,2)` freq{2:1}, minFreq=1. `get(1)`→1, key1 to freq 2. `put(3,3)`: full, evict min-freq bucket (freq 1) oldest = key2; insert 3 at freq1, minFreq=1. `get(2)`→ −1. `get(3)`→3 (freq1→2). `put(4,4)`: full, evict min freq bucket — keys at freq2 are {1,3}? No: key3 now freq2, key1 freq2, key4 new freq1 forces evicting freq1... evicts the freq-1 oldest. The tie-break (LRU within frequency) is exactly what `LinkedHashSet` ordering provides.

**Time:** O(1) per operation **Space:** O(capacity)

**Follow-ups:** Aging / decay so old-but-once-hot keys don't dominate forever; combine TTL; why naive LFU with a heap is `O(log n)` not `O(1)`.

---

### Problem 10: Design HashMap from scratch

Implement `MyHashMap` without using any built-in hash library: `put(key,value)`, `get(key)` (return −1 if absent), `remove(key)`. Keys/values are non-negative ints.

**Approach.** Use **separate chaining**: a fixed array of buckets, each a linked list. Hash with `key % capacity`. A prime-ish bucket count reduces clustering. (Production maps would also resize on load factor — shown in the follow-up note.)

```java
class MyHashMap {
    private static class Entry {
        int key, value;
        Entry next;
        Entry(int k, int v) { key = k; value = v; }
    }

    private static final int SIZE = 769; // prime reduces collisions
    private final Entry[] buckets = new Entry[SIZE];

    private int index(int key) { return Integer.hashCode(key) % SIZE; }

    public void put(int key, int value) {
        int i = index(key);
        for (Entry e = buckets[i]; e != null; e = e.next) {
            if (e.key == key) { e.value = value; return; }
        }
        Entry head = new Entry(key, value);
        head.next = buckets[i];
        buckets[i] = head; // prepend
    }

    public int get(int key) {
        for (Entry e = buckets[index(key)]; e != null; e = e.next) {
            if (e.key == key) return e.value;
        }
        return -1;
    }

    public void remove(int key) {
        int i = index(key);
        Entry dummy = new Entry(-1, -1);
        dummy.next = buckets[i];
        Entry prev = dummy;
        while (prev.next != null) {
            if (prev.next.key == key) { prev.next = prev.next.next; break; }
            prev = prev.next;
        }
        buckets[i] = dummy.next;
    }
}
```

**Walkthrough.** `put(1,1)`, `put(769,2)` collide (both `% 769` map close) — chain handles it. `get(1)`→1, `get(769)`→2, `remove(1)`, `get(1)`→ −1.

**Time:** O(1) average, O(n/SIZE) per bucket **Space:** O(SIZE + n)

**Follow-ups:** Add dynamic resizing when load factor > 0.75 (allocate `2×` buckets, rehash all entries); implement with open addressing + tombstones; generic keys requiring proper `hashCode`/`equals`.

---

## 🧩 Extended Problems — Set 1: Classic Easy → Medium

### Problem 11: Intersection of Two Arrays — Hash Set Membership

**Statement.** Given two integer arrays `nums1` and `nums2`, return their intersection. Each element in the result must be **unique** and you may return it in any order.

**Constraints.** `1 ≤ nums1.length, nums2.length ≤ 1000`, `0 ≤ nums1[i], nums2[i] ≤ 1000`.

**Approach.** "Which elements appear in both?" is a pure membership question, so hash both arrays into sets and intersect. Put the first array into a `HashSet` for O(1) lookups, then scan the second array; whenever an element is present in the set, add it to a result set (which also enforces uniqueness) and remove it from the lookup set to avoid duplicate work. This is O(n+m) versus the O(n·m) brute-force pair check or O(n log n + m log m) sort-and-merge. Hashing wins when no order is required.

```java
import java.util.*;

class Solution {
    public int[] intersection(int[] nums1, int[] nums2) {
        Set<Integer> pool = new HashSet<>();
        for (int x : nums1) pool.add(x);

        Set<Integer> result = new HashSet<>();
        for (int x : nums2) {
            if (pool.remove(x)) result.add(x); // remove returns true only on first hit
        }

        int[] out = new int[result.size()];
        int i = 0;
        for (int v : result) out[i++] = v;
        return out;
    }
}
```

**Walkthrough.** `nums1=[4,9,5]`, `nums2=[9,4,9,8,4]`. pool={4,9,5}. Scan nums2: 9→remove→result{9}; 4→remove→result{9,4}; 9 already removed→skip; 8 not in pool; 4 already removed. Result `[9,4]`.

**Complexity** — Time O(n+m), Space O(n) for the lookup set. **Edge cases:** no common elements (return empty array); duplicates within one array collapse via the set; identical arrays return their distinct values. (Follow-up "Intersection II" keeps multiplicity — use a frequency map and decrement counts instead of a set.)

---

### Problem 12: Single Number — XOR vs Hash Set

**Statement.** Every element in `nums` appears **twice** except for one element that appears once. Find that single element.

**Constraints.** `1 ≤ nums.length ≤ 3·10⁴` (length is odd), `-3·10⁴ ≤ nums[i] ≤ 3·10⁴`, exactly one element is unique.

**Approach.** The textbook O(1)-space trick is XOR (`a^a=0`, `a^0=a`), but the hashing answer is what an interviewer expects when generalizing the problem. Maintain a set: add a number on first sight, remove it on second sight. After the full scan only the unique element remains. This generalizes cleanly to "appears once vs any even number of times" and to non-integer/object keys where XOR is unavailable, at the cost of O(n) space. Both are shown.

```java
import java.util.*;

class Solution {
    // O(1) space — the canonical optimal answer.
    public int singleNumber(int[] nums) {
        int acc = 0;
        for (int x : nums) acc ^= x;
        return acc;
    }

    // O(n) space — the hash-set variant that generalizes to objects.
    public int singleNumberSet(int[] nums) {
        Set<Integer> seen = new HashSet<>();
        for (int x : nums) {
            if (!seen.add(x)) seen.remove(x); // toggle membership
        }
        return seen.iterator().next();
    }
}
```

**Walkthrough.** `[4,1,2,1,2]`. XOR: 4^1^2^1^2 = 4^(1^1)^(2^2) = 4^0^0 = 4. Set: add4, add1, add2, remove1, remove2 → {4} → 4.

**Complexity** — XOR: Time O(n), Space O(1). Set: Time O(n), Space O(n). **Edge cases:** single-element array returns that element; negatives handled by XOR identically. ("Single Number II/III" — appears thrice or two singles — need bit-counting or grouping by a distinguishing bit.)

---

### Problem 13: Happy Number — Cycle Detection via Hash Set

**Statement.** A number is *happy* if repeatedly replacing it with the sum of the squares of its digits eventually reaches `1`. Numbers that loop forever in a cycle are unhappy. Return `true` if `n` is happy.

**Constraints.** `1 ≤ n ≤ 2³¹ − 1`.

**Approach.** The sequence of "sum of squared digits" either reaches 1 or enters a cycle that never includes 1. Detecting "have I been here before?" is exactly a seen-set: record each number; if it reaches 1 → happy; if a value repeats → an infinite loop → unhappy. The state space is bounded (for any int the squared-digit sum quickly drops below ~243), so the set stays tiny. Floyd's tortoise-and-hare gives the same answer in O(1) space, but the hash set is the clearer interview answer for "cycle in a derived sequence".

```java
import java.util.*;

class Solution {
    public boolean isHappy(int n) {
        Set<Integer> seen = new HashSet<>();
        while (n != 1 && seen.add(n)) { // add fails (false) when n repeats
            n = squareDigitSum(n);
        }
        return n == 1;
    }

    private int squareDigitSum(int n) {
        int sum = 0;
        while (n > 0) {
            int d = n % 10;
            sum += d * d;
            n /= 10;
        }
        return sum;
    }
}
```

**Walkthrough.** `n=19`: 1²+9²=82 → 8²+2²=68 → 6²+8²=100 → 1²+0²+0²=1 → happy. `n=2`: 4→16→37→58→89→145→42→20→4 (4 repeats) → unhappy.

**Complexity** — Time O(log n) per transform, and the number of distinct states visited is bounded by a constant, so effectively O(log n) overall; Space O(constant) due to the bounded state space. **Edge cases:** `n=1` immediately happy; the cycle for unhappy numbers always passes through 4.

---

### Problem 14: Word Pattern — Bijection with Two Maps

**Statement.** Given a `pattern` and a string `s` of space-separated words, return `true` if `s` follows the pattern — i.e. there is a **bijection** between letters in `pattern` and words in `s`.

**Constraints.** `1 ≤ pattern.length ≤ 300`, lowercase letters; `1 ≤ s.length ≤ 3000`; words are lowercase, single-space separated.

**Approach.** A bijection requires mapping in **both** directions to be consistent. Use two maps: `char → word` and `word → char`. Walk the pattern and the split words in lockstep; at each position verify (or establish) that the letter maps to this word *and* this word maps back to this letter. Either direction conflicting fails. One map alone is insufficient: `pattern="ab", s="dog dog"` would pass with only `char→word` checking, but it must fail since two letters map to the same word.

```java
import java.util.*;

class Solution {
    public boolean wordPattern(String pattern, String s) {
        String[] words = s.split(" ");
        if (pattern.length() != words.length) return false;

        Map<Character, String> charToWord = new HashMap<>();
        Map<String, Character> wordToChar = new HashMap<>();

        for (int i = 0; i < words.length; i++) {
            char c = pattern.charAt(i);
            String w = words[i];
            if (charToWord.containsKey(c) && !charToWord.get(c).equals(w)) return false;
            if (wordToChar.containsKey(w) && wordToChar.get(w) != c) return false;
            charToWord.put(c, w);
            wordToChar.put(w, c);
        }
        return true;
    }
}
```

**Walkthrough.** `pattern="abba", s="dog cat cat dog"`: a→dog, b→cat, b→cat✓, a→dog✓ → `true`. `pattern="abba", s="dog dog dog dog"`: a→dog, then b→dog but dog already maps to a → `false`.

**Complexity** — Time O(n) over total characters, Space O(distinct mappings). **Edge cases:** length mismatch between pattern and word count → false immediately; trailing spaces create empty words; identical structure but reused word across different letters must fail. (This is the "Isomorphic Strings" problem at the word level.)

---

### Problem 15: Isomorphic Strings — Consistent Character Mapping

**Statement.** Given strings `s` and `t`, return `true` if they are **isomorphic** — the characters in `s` can be replaced to get `t` with a consistent one-to-one mapping (no two characters map to the same character, and order is preserved).

**Constraints.** `1 ≤ s.length ≤ 5·10⁴`, `s.length == t.length`, any ASCII characters.

**Approach.** Same bijection idea as Word Pattern but on characters. Maintain two maps (`s[i]→t[i]` and `t[i]→s[i]`) and verify consistency in both directions at each index. A neat O(1)-space variant for ASCII uses two `int[256]` arrays storing the *last index* at which each character was seen in `s` and in `t`; the strings are isomorphic iff those "last seen" indices stay equal at every position. Both directions matter: `"ab"→"aa"` must fail.

```java
class Solution {
    public boolean isIsomorphic(String s, String t) {
        int[] mapS = new int[256]; // last (index+1) seen for each s char
        int[] mapT = new int[256];
        for (int i = 0; i < s.length(); i++) {
            char cs = s.charAt(i), ct = t.charAt(i);
            if (mapS[cs] != mapT[ct]) return false; // mismatched first-occurrence pattern
            mapS[cs] = i + 1; // +1 so default 0 means "unseen"
            mapT[ct] = i + 1;
        }
        return true;
    }
}
```

**Walkthrough.** `s="egg", t="add"`: e/a both new(0==0), store 1/1; g/d new(0==0), store 2/2; g/d seen(2==2) → `true`. `s="foo", t="bar"`: f/b new; o/a new(0); o/r → mapS[o]=2 but mapT[r]=0, 2≠0 → `false`.

**Complexity** — Time O(n), Space O(1) (fixed 256-slot arrays). **Edge cases:** equal-length guarantee removes a length check; the `+1` offset is essential so an unseen char (default 0) is distinguishable from index 0; works for any byte-range characters.

---

### Problem 16: Ransom Note — Frequency Counting

**Statement.** Given two strings `ransomNote` and `magazine`, return `true` if `ransomNote` can be constructed using the letters of `magazine`. Each letter in `magazine` may be used at most once.

**Constraints.** `1 ≤ ransomNote.length, magazine.length ≤ 10⁵`, lowercase English letters.

**Approach.** Count how many of each letter the magazine supplies, then "spend" letters as the note demands them. Build a 26-slot frequency array from `magazine`, then for each character of `ransomNote` decrement the count and fail if it ever goes negative (the note needs a letter the magazine has exhausted). The fixed array beats a `HashMap` because the alphabet is tiny and known. Single pass over each string after counting.

```java
class Solution {
    public boolean canConstruct(String ransomNote, String magazine) {
        if (ransomNote.length() > magazine.length()) return false;
        int[] freq = new int[26];
        for (int i = 0; i < magazine.length(); i++) freq[magazine.charAt(i) - 'a']++;
        for (int i = 0; i < ransomNote.length(); i++) {
            if (--freq[ransomNote.charAt(i) - 'a'] < 0) return false;
        }
        return true;
    }
}
```

**Walkthrough.** `ransomNote="aa", magazine="aab"`: freq a2,b1. Note: a→1, a→0 (never negative) → `true`. `ransomNote="aa", magazine="ab"`: freq a1,b1. Note: a→0, a→−1 → `false`.

**Complexity** — Time O(n+m), Space O(1). **Edge cases:** note longer than magazine → false fast; empty note → trivially true; the early length check is a cheap optimization, not required for correctness.

---

### Problem 17: Longest Substring Without Repeating Characters — Sliding Window + Hash Map

**Statement.** Given a string `s`, find the length of the longest substring without repeating characters.

**Constraints.** `0 ≤ s.length ≤ 5·10⁴`, any ASCII characters.

**Approach.** Use a sliding window `[left, right]` and a map `char → last index seen`. As `right` advances, if the current character was seen *inside* the current window, jump `left` to just past its previous position so the window stays repeat-free. Track the max window width. Storing the last index (rather than a boolean) lets `left` jump directly instead of shrinking one step at a time, keeping it O(n) with a single pass.

```
s = a b c a b c b b
        ↑       ↑
       left    right   window slides right, left jumps past duplicates
```

```java
import java.util.*;

class Solution {
    public int lengthOfLongestSubstring(String s) {
        Map<Character, Integer> last = new HashMap<>(); // char -> last index
        int best = 0, left = 0;
        for (int right = 0; right < s.length(); right++) {
            char c = s.charAt(right);
            if (last.containsKey(c) && last.get(c) >= left) {
                left = last.get(c) + 1; // skip past the previous occurrence
            }
            last.put(c, right);
            best = Math.max(best, right - left + 1);
        }
        return best;
    }
}
```

**Walkthrough.** `s="abcabcbb"`: window grows to "abc" (3). At second `a` (idx3) left jumps to 1; window "bca","cab","abc" stay length 3. At repeated `b`s the window collapses. Best = 3. `"pwwkew"` → "wke" → 3.

**Complexity** — Time O(n), Space O(min(n, alphabet)). **Edge cases:** empty string → 0; all-identical string → 1; the `last.get(c) >= left` guard is critical so a duplicate *outside* the current window doesn't wrongly move `left` backward.

---

### Problem 18: Longest Consecutive Sequence — Hash Set, O(n)

**Statement.** Given an unsorted array `nums`, return the length of the longest run of **consecutive** integers. The algorithm must run in O(n).

**Constraints.** `0 ≤ nums.length ≤ 10⁵`, `-10⁹ ≤ nums[i] ≤ 10⁹`.

**Approach.** Sorting gives O(n log n), but the constraint demands O(n), which a hash set delivers. Put all numbers in a set. A number `x` is the **start** of a sequence only if `x-1` is absent; for each such start, walk upward (`x+1, x+2, …`) counting the run. Because we only extend from starts, each element is visited at most twice total, giving amortized O(n). Without the "is it a start?" check you'd re-walk overlapping runs and degrade to O(n²).

```java
import java.util.*;

class Solution {
    public int longestConsecutive(int[] nums) {
        Set<Integer> set = new HashSet<>();
        for (int x : nums) set.add(x);

        int best = 0;
        for (int x : set) {
            if (set.contains(x - 1)) continue; // not a sequence start
            int length = 1, cur = x;
            while (set.contains(cur + 1)) { cur++; length++; }
            best = Math.max(best, length);
        }
        return best;
    }
}
```

**Walkthrough.** `nums=[100,4,200,1,3,2]`. set={100,4,200,1,3,2}. Start 100 (no 99): run 1. Start 1 (no 0): 1,2,3,4 → run 4. 4,3,2 are skipped (predecessor exists). Start 200: run 1. Best = 4.

**Complexity** — Time O(n) amortized, Space O(n). **Edge cases:** empty array → 0; duplicates collapse in the set and don't inflate counts; large value range is fine since the set keys on values, not indices.

---

### Problem 19: 4Sum II — Pair-Sum Hash Map

**Statement.** Given four integer arrays `A`, `B`, `C`, `D` all of length `n`, count the number of tuples `(i,j,k,l)` such that `A[i]+B[j]+C[k]+D[l] == 0`.

**Constraints.** `1 ≤ n ≤ 200`, `-2²⁸ ≤ values ≤ 2²⁸`.

**Approach.** A 4-nested loop is O(n⁴). Split the four arrays into two halves: precompute every pairwise sum `A[i]+B[j]` into a map `sum → count`. Then for every pair `(k,l)` from `C` and `D`, look up how many `A+B` pairs equal `-(C[k]+D[l])` and add that count. This converts O(n⁴) into O(n²) — the classic "meet in the middle with a hash map" technique for fixed-count sum problems.

```java
import java.util.*;

class Solution {
    public int fourSumCount(int[] A, int[] B, int[] C, int[] D) {
        Map<Integer, Integer> abSum = new HashMap<>();
        for (int a : A)
            for (int b : B)
                abSum.merge(a + b, 1, Integer::sum);

        int count = 0;
        for (int c : C)
            for (int d : D)
                count += abSum.getOrDefault(-(c + d), 0);
        return count;
    }
}
```

**Walkthrough.** `A=[1,2], B=[-2,-1], C=[-1,2], D=[0,2]`. abSum collects sums of A+B: 1+−2=−1, 1+−1=0, 2+−2=0, 2+−1=1 → {−1:1, 0:2, 1:1}. For each c+d we add abSum[−(c+d)]. The two valid tuples yield count `2`.

**Complexity** — Time O(n²), Space O(n²) for the pair-sum map. **Edge cases:** many tuples can share the same sum so counts (not booleans) are essential; sums can exceed `int` range only if values are extreme — here `2²⁸ × 2` fits in `int`, but `long` keys are safer for wider inputs.

---

### Problem 20: Find All Anagrams in a String — Sliding Window of Counts

**Statement.** Given strings `s` and `p`, return the start indices of all substrings of `s` that are anagrams of `p`.

**Constraints.** `1 ≤ s.length, p.length ≤ 3·10⁴`, lowercase English letters.

**Approach.** An anagram of `p` is any window in `s` of length `|p|` with an identical character-count profile. Slide a fixed-width window of size `|p|` across `s`, maintaining a 26-slot count of the window. Compare to `p`'s count at each step. To avoid an O(26) comparison every step, track a single `matches` counter of how many of the 26 letters currently have equal counts, updating it incrementally as one character enters and one leaves — giving true O(n).

```
s = c b a e b a b a c d   p = abc  (window width 3)
    [c b a]                 counts match abc → index 0
      [b a e]               no
        ... slide one char at a time, add right, drop left
```

```java
import java.util.*;

class Solution {
    public List<Integer> findAnagrams(String s, String p) {
        List<Integer> res = new ArrayList<>();
        if (s.length() < p.length()) return res;

        int[] need = new int[26], window = new int[26];
        for (char c : p.toCharArray()) need[c - 'a']++;

        int w = p.length();
        for (int i = 0; i < s.length(); i++) {
            window[s.charAt(i) - 'a']++;          // add entering char
            if (i >= w) window[s.charAt(i - w) - 'a']--; // drop leaving char
            if (i >= w - 1 && Arrays.equals(window, need)) res.add(i - w + 1);
        }
        return res;
    }
}
```

**Walkthrough.** `s="cbaebabacd", p="abc"`. Window "cba" (idx0) matches → add 0. Windows slide; "bac" at index 6 matches → add 6. Result `[0,6]`.

**Complexity** — Time O(n) (the `Arrays.equals` over 26 fixed slots is O(1)), Space O(1). **Edge cases:** `p` longer than `s` → empty; overlapping anagrams (e.g. "abab" in "ababab") are all reported; the leaving-char decrement only kicks in once the window is full.

---

### Problem 21: Two Sum III — Data Structure Design with a Count Map

**Statement.** Design a structure that supports `add(number)` to store a number and `find(value)` returning `true` if any pair of stored numbers sums to `value`.

**Constraints.** Up to `10⁴` calls to `add`/`find`; values fit in `int`; duplicates are allowed and a number may pair with another copy of itself.

**Approach.** Keep a frequency map `number → count`. `add` increments the count in O(1). `find(value)` scans the distinct stored keys; for each key `x` it asks whether the complement `value - x` exists — with the subtlety that if `x` *is* its own complement (`x == value - x`), there must be at least **two** copies of `x`. This trades a slightly slower `find` (O(distinct)) for O(1) `add`; the alternative (precompute all pair sums on add) makes `add` O(n) and is worse when adds dominate.

```java
import java.util.*;

class TwoSum {
    private final Map<Integer, Integer> counts = new HashMap<>();

    public void add(int number) {
        counts.merge(number, 1, Integer::sum);
    }

    public boolean find(int value) {
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            int x = e.getKey(), complement = value - x;
            if (complement == x) {
                if (e.getValue() >= 2) return true; // needs two copies of x
            } else if (counts.containsKey(complement)) {
                return true;
            }
        }
        return false;
    }
}
```

**Walkthrough.** add(1), add(3), add(5). find(4): 1+3 → `true`. find(7): 3+? need 4 (no), 5+? need 2 (no), 1+? need 6 (no) → `false`. After add(3): find(6) → 3 appears twice → `true`.

**Complexity** — `add` O(1); `find` O(distinct keys); Space O(distinct keys). **Edge cases:** a number pairing with its own duplicate requires the count≥2 check; negative values and `value` itself work via plain arithmetic; an empty structure returns false.

---

### Problem 22: Continuous Subarray Sum — Prefix Remainder Map

**Statement.** Given an array `nums` and integer `k`, return `true` if there is a **contiguous subarray of length at least 2** whose elements sum to a multiple of `k` (i.e. sum `= n·k` for some integer `n ≥ 0`).

**Constraints.** `1 ≤ nums.length ≤ 10⁵`, `0 ≤ nums[i] ≤ 10⁹`, `1 ≤ k ≤ 2³¹ − 1`.

**Approach.** If two prefix sums share the same remainder mod `k`, the subarray between them has a sum divisible by `k` (their difference is a multiple of `k`). So track `remainder → earliest index` while scanning. When the same remainder recurs at an index at least 2 apart, return true. Seed the map with `{0: -1}` so a prefix that is itself a multiple of `k` (starting at index 0) is caught with correct length. The earliest-index storage is what enforces the length-≥2 requirement.

```java
import java.util.*;

class Solution {
    public boolean checkSubarraySum(int[] nums, int k) {
        Map<Integer, Integer> firstIndex = new HashMap<>();
        firstIndex.put(0, -1); // remainder 0 "seen" before index 0
        int sum = 0;
        for (int i = 0; i < nums.length; i++) {
            sum += nums[i];
            int r = sum % k;
            if (firstIndex.containsKey(r)) {
                if (i - firstIndex.get(r) >= 2) return true;
            } else {
                firstIndex.put(r, i); // keep only the earliest index for this remainder
            }
        }
        return false;
    }
}
```

**Walkthrough.** `nums=[23,2,4,6,7], k=6`. prefix remainders: 23%6=5(idx0), (23+2)=25%6=1(idx1), (25+4)=29%6=5 → remainder 5 seen at idx0, distance 2−0=2 ≥2 → `true` (subarray [2,4] sums to 6).

**Complexity** — Time O(n), Space O(min(n, k)). **Edge cases:** zeros — `[0,0]` with any `k≥1` is true (two equal prefix remainders); the seed `{0:-1}` handles a prefix that is itself a multiple of k; only the first occurrence of each remainder is stored to maximize subarray length.

---

### Problem 23: Subarrays with Sum Divisible by K — Prefix Remainder Counting

**Statement.** Given an integer array `nums` and integer `k`, return the number of (non-empty) contiguous subarrays whose sum is divisible by `k`.

**Constraints.** `1 ≤ nums.length ≤ 3·10⁴`, `-10⁴ ≤ nums[i] ≤ 10⁴`, `2 ≤ k ≤ 10⁴`. Values can be negative.

**Approach.** Same prefix-remainder insight as Problem 22, but now we *count* pairs rather than detect one. For each prefix sum, compute its remainder mod `k`; every earlier prefix with the same remainder forms a divisible subarray with the current one. So keep a frequency map `remainder → count`, seeded with `{0: 1}` (the empty prefix), and for each position add the current remainder's running count before incrementing it. The key subtlety with negatives: Java's `%` can return a negative result, so **normalize** the remainder to `[0, k)` with `((sum % k) + k) % k`.

```java
import java.util.*;

class Solution {
    public int subarraysDivByK(int[] nums, int k) {
        Map<Integer, Integer> remCount = new HashMap<>();
        remCount.put(0, 1); // empty prefix
        int sum = 0, result = 0;
        for (int x : nums) {
            sum += x;
            int r = ((sum % k) + k) % k; // normalize negatives into [0, k)
            result += remCount.getOrDefault(r, 0); // pair with all earlier same-remainder prefixes
            remCount.merge(r, 1, Integer::sum);
        }
        return result;
    }
}
```

**Walkthrough.** `nums=[4,5,0,-2,-3,1], k=5`. Remainders of prefixes (normalized): 4,4,4,3,0,1. Map starts {0:1}. As equal remainders accumulate, pairs form: e.g. the three prefixes with remainder 4 yield C(3,2)=3 subarrays, the two with remainder 0 (the seed plus prefix index 4) yield 1 more, etc. Total `7`.

**Complexity** — Time O(n), Space O(k). **Edge cases:** negative sums require the normalization step or counts are missed; the `{0:1}` seed counts subarrays starting at index 0; an all-zero array yields C(n+1,2) subarrays. (Contrast with Problem 6 "Subarray Sum Equals K", which keys on the raw prefix sum, not the remainder.)

---

## 🧩 Extended Problems — Set 2: Medium → Hard Variations & Follow-ups

### Problem 24: Two Sum II / Count All Pairs — Sorted Two-Pointer vs Hash Map

**Statement.** Two related follow-ups to classic Two Sum. (a) **Two Sum II**: the input array is **sorted ascending**; return the 1-indexed positions of the two numbers adding to `target` (exactly one solution). (b) **Count all pairs**: given an arbitrary array, count *how many* unordered index pairs `(i, j)` sum to `target` (duplicates may produce many pairs).

**Constraints.** `2 ≤ n ≤ 3·10⁴`, values fit in `int`, `target` fits in `int`.

**Approach.** When the array is *sorted*, the optimal answer is not a hash map at all — it is **two pointers** from both ends, moving `left` right (sum too small) or `right` left (sum too big). That uses O(1) extra space versus the map's O(n), which is the whole point of the follow-up: exploit the sortedness you are given. For the *counting* variant on an unsorted array, sorting would destroy index identity, so a hash map is the right tool: keep a running `count → frequency` map of values seen so far, and for each `x` add the number of earlier elements equal to `target - x`. This counts each pair exactly once (the later element always pairs with all matching earlier ones), correctly handling duplicates.

```
Two Sum II (sorted):   [2, 7, 11, 15]  target 18
                        L           R     2+15=17 < 18 -> L++
                           L        R     7+15=22 > 18 -> R--
                           L     R        7+11=18  -> found (2,3) 1-indexed
```

```java
import java.util.*;

class Solution {
    // (a) Sorted input -> two pointers, O(1) space.
    public int[] twoSumSorted(int[] numbers, int target) {
        int left = 0, right = numbers.length - 1;
        while (left < right) {
            int sum = numbers[left] + numbers[right];
            if (sum == target) return new int[] { left + 1, right + 1 }; // 1-indexed
            if (sum < target) left++;
            else right--;
        }
        return new int[0]; // unreachable given the guarantee
    }

    // (b) Unsorted input, count ALL pairs -> hash map of running frequencies.
    public long countPairs(int[] nums, int target) {
        Map<Integer, Integer> seen = new HashMap<>();
        long pairs = 0;
        for (int x : nums) {
            pairs += seen.getOrDefault(target - x, 0); // each earlier match forms a pair
            seen.merge(x, 1, Integer::sum);
        }
        return pairs;
    }
}
```

**Walkthrough.** (a) `numbers=[2,7,11,15], target=9`: 2+15=17>9→R--; 2+11=13>9→R--; 2+7=9→return `[1,2]`. (b) `nums=[1,1,2,2], target=3`: x=1 (need 2, none)→seen{1:1}; x=1 (need 2, none)→seen{1:2}; x=2 (need 1, found 2)→pairs=2, seen{1:2,2:1}; x=2 (need 1, found 2)→pairs=4. Answer `4`.

**Complexity** — Two-pointer: Time O(n), Space O(1). Counting map: Time O(n), Space O(n). **Edge cases:** the sorted variant needs strictly `left < right` so an element never pairs with itself; the counting variant adds *before* inserting `x` so the pair `(x, x)` is only counted against earlier copies, never itself; use `long` for the count since duplicate-heavy arrays can overflow `int`.

---

### Problem 25: 4Sum — Sort + Two-Pointer with Hash-Set Dedup

**Statement.** Given an array `nums` and a `target`, return **all unique quadruplets** `[a, b, c, d]` such that `a + b + c + d == target`. The solution set must not contain duplicate quadruplets.

**Constraints.** `1 ≤ n ≤ 200`, `-10⁹ ≤ nums[i], target ≤ 10⁹`.

**Approach.** Brute force is O(n⁴). The standard optimal is **sort, then fix the outer two indices and two-pointer the inner pair**, giving O(n³). The hashing angle is the *deduplication*: after sorting, skip equal neighbors at each of the four positions so duplicate quadruplets are never emitted — an implicit "have I produced this combination before?" check done by ordering rather than a `HashSet<List>`. (You *could* dedup with a set of canonicalized tuples, but neighbor-skipping is cleaner and avoids hashing large keys.) Use `long` for the running sum because four `int` values near `10⁹` overflow.

```java
import java.util.*;

class Solution {
    public List<List<Integer>> fourSum(int[] nums, int target) {
        Arrays.sort(nums);
        int n = nums.length;
        List<List<Integer>> res = new ArrayList<>();
        for (int i = 0; i < n - 3; i++) {
            if (i > 0 && nums[i] == nums[i - 1]) continue;        // skip dup a
            for (int j = i + 1; j < n - 2; j++) {
                if (j > i + 1 && nums[j] == nums[j - 1]) continue; // skip dup b
                int lo = j + 1, hi = n - 1;
                while (lo < hi) {
                    long sum = (long) nums[i] + nums[j] + nums[lo] + nums[hi];
                    if (sum == target) {
                        res.add(Arrays.asList(nums[i], nums[j], nums[lo], nums[hi]));
                        while (lo < hi && nums[lo] == nums[lo + 1]) lo++; // skip dup c
                        while (lo < hi && nums[hi] == nums[hi - 1]) hi--; // skip dup d
                        lo++; hi--;
                    } else if (sum < target) lo++;
                    else hi--;
                }
            }
        }
        return res;
    }
}
```

**Walkthrough.** `nums=[1,0,-1,0,-2,2], target=0` → sorted `[-2,-1,0,0,1,2]`. Fixing `-2,-1` two-pointers find `[1,2]` → `[-2,-1,1,2]`; fixing `-2,0` find `[0,2]` → `[-2,0,0,2]`; fixing `-1,0` find `[0,1]` → `[-1,0,0,1]`. The neighbor-skips suppress the repeats from the two `0`s.

**Complexity** — Time O(n³), Space O(1) extra (ignoring output and sort). **Edge cases:** `long` sum prevents overflow; the four independent neighbor-skips are each necessary to dedup their position; `n < 4` produces an empty list naturally via the loop bounds.

---

### Problem 26: Longest Subarray with Sum Equals K — First-Index Prefix Map

**Statement.** Given an array `nums` (may contain negatives) and integer `k`, return the **length of the longest** contiguous subarray summing to exactly `k`. Return `0` if none exists.

**Constraints.** `1 ≤ n ≤ 10⁵`, `-10⁴ ≤ nums[i] ≤ 10⁴`, `k` fits in `int`.

**Approach.** This is the "maximize length" sibling of Problem 6 ("count subarrays"). Because values can be negative, sliding window fails; use prefix sums in a map. The crucial difference from the counting version: to maximize length we want the **earliest** index at which each prefix sum first appeared, so a later index minus that earliest index is as large as possible. Therefore store `prefixSum → first index` and use `putIfAbsent` (never overwrite). For each running sum `pre`, if `pre - k` was seen earlier at index `j`, the subarray `(j, i]` sums to `k` with length `i - j`; track the max. Seed `{0 : -1}` so a subarray starting at index 0 measures its length correctly.

```java
import java.util.*;

class Solution {
    public int longestSubarrayWithSumK(int[] nums, int k) {
        Map<Integer, Integer> firstIndex = new HashMap<>();
        firstIndex.put(0, -1); // empty prefix ends "before" index 0
        int sum = 0, best = 0;
        for (int i = 0; i < nums.length; i++) {
            sum += nums[i];
            Integer j = firstIndex.get(sum - k);
            if (j != null) best = Math.max(best, i - j);
            firstIndex.putIfAbsent(sum, i); // keep EARLIEST index for each prefix sum
        }
        return best;
    }
}
```

**Walkthrough.** `nums=[1,-1,5,-2,3], k=3`. Prefix sums: 1,0,5,3,6 at indices 0..4. Seed {0:-1}. i=0 sum=1, need -2 (no), store {1:0}. i=1 sum=0, need -3 (no), 0 already seeded so don't overwrite. i=2 sum=5, need 2 (no), store {5:2}. i=3 sum=3, need 0 → seen at -1 → length 3-(-1)=4 → best=4. i=4 sum=6, need 3 (no). Answer `4`.

**Complexity** — Time O(n), Space O(n). **Edge cases:** `putIfAbsent` is mandatory — overwriting would shorten candidate subarrays; `{0:-1}` seed handles subarrays anchored at index 0; if no subarray sums to `k`, `best` stays 0.

---

### Problem 27: Contiguous Array (Equal 0s and 1s) — Prefix Balance Map

**Statement.** Given a binary array `nums`, return the length of the longest contiguous subarray with an **equal number of 0s and 1s**.

**Constraints.** `1 ≤ n ≤ 10⁵`, each `nums[i]` is `0` or `1`.

**Approach.** Reframe the problem so prefix sums apply: treat each `0` as `-1` and each `1` as `+1`. A subarray has equal 0s and 1s **iff** its transformed sum is `0`, i.e. iff two prefix sums (the "running balance") are equal. So track the running balance and store `balance → first index` in a map. Whenever the same balance recurs at index `i` having first appeared at `j`, the subarray `(j, i]` is balanced with length `i - j`. As in Problem 26 we keep the *earliest* index per balance to maximize length, seeding `{0 : -1}`.

```
nums:     0   1   0   1   1   0
mapped:  -1  +1  -1  +1  +1  -1
balance: -1   0  -1   0   1   0   (with start balance 0 at index -1)
          balance 0 first at -1, recurs at 1,3,5 -> longest = 5-(-1)=... index 5 gives 6
```

```java
import java.util.*;

class Solution {
    public int findMaxLength(int[] nums) {
        Map<Integer, Integer> firstIndex = new HashMap<>();
        firstIndex.put(0, -1); // balance 0 "seen" before the array starts
        int balance = 0, best = 0;
        for (int i = 0; i < nums.length; i++) {
            balance += (nums[i] == 1) ? 1 : -1;
            Integer j = firstIndex.get(balance);
            if (j != null) best = Math.max(best, i - j);
            else firstIndex.put(balance, i); // earliest index for this balance
        }
        return best;
    }
}
```

**Walkthrough.** `nums=[0,1,0]`: balances -1(i0), 0(i1), -1(i2). Seed {0:-1}. i0 balance -1 new→store. i1 balance 0 seen at -1→length 1-(-1)=2→best=2. i2 balance -1 seen at 0→length 2-0=2. Answer `2`.

**Complexity** — Time O(n), Space O(n). **Edge cases:** all-same array (e.g. all 1s) → best stays 0; the `else` ensures we never overwrite the earliest index; the `{0:-1}` seed is what lets a balanced prefix starting at index 0 be measured.

---

### Problem 28: Minimum Window Substring — Two Hash Maps + Sliding Window

**Statement.** Given strings `s` and `t`, return the **smallest substring** of `s` that contains every character of `t` (including duplicates). Return `""` if no such window exists.

**Constraints.** `1 ≤ s.length, t.length ≤ 10⁵`, any ASCII characters.

**Approach.** This is the hardest classic sliding-window-plus-hashing problem. Build a `need` count map from `t`. Expand a window by moving `right`; maintain a window count map and a `formed` counter = how many *distinct* required characters currently meet their required count. When `formed == required` (number of distinct chars in `t`), the window is valid, so try to **shrink** from `left` to minimize length, decrementing counts and dropping below `formed` when a required character's count falls under its need. Record the best window each time it is valid. Each character enters and leaves the window once → O(|s| + |t|).

```
s = A D O B E C O D E B A N C    t = ABC
        shrink when window has A,B,C all satisfied
best window = "BANC"
```

```java
import java.util.*;

class Solution {
    public String minWindow(String s, String t) {
        if (s.length() < t.length()) return "";
        Map<Character, Integer> need = new HashMap<>();
        for (char c : t.toCharArray()) need.merge(c, 1, Integer::sum);
        int required = need.size();

        Map<Character, Integer> window = new HashMap<>();
        int formed = 0, left = 0;
        int bestLen = Integer.MAX_VALUE, bestStart = 0;

        for (int right = 0; right < s.length(); right++) {
            char c = s.charAt(right);
            window.merge(c, 1, Integer::sum);
            if (need.containsKey(c) && window.get(c).intValue() == need.get(c).intValue()) {
                formed++;
            }
            while (formed == required) {           // window is valid -> try to shrink
                if (right - left + 1 < bestLen) {
                    bestLen = right - left + 1;
                    bestStart = left;
                }
                char lc = s.charAt(left);
                window.merge(lc, -1, Integer::sum);
                if (need.containsKey(lc) && window.get(lc) < need.get(lc)) {
                    formed--;                      // dropped below required count
                }
                left++;
            }
        }
        return bestLen == Integer.MAX_VALUE ? "" : s.substring(bestStart, bestStart + bestLen);
    }
}
```

**Walkthrough.** `s="ADOBECODEBANC", t="ABC"`. The window first becomes valid at "ADOBEC" (len 6); shrinking can't help there. Later "CODEBANC" → shrinks to "BANC" (len 4) which is the minimum. Answer `"BANC"`.

**Complexity** — Time O(|s| + |t|), Space O(|alphabet|). **Edge cases:** `t` longer than `s` → `""`; duplicate chars in `t` require count equality (`==`), not mere presence; using `.intValue()` avoids the `Integer` autobox `==` identity trap for values outside −128..127.

---

### Problem 29: Substring with Concatenation of All Words — Word-Frequency Hashing

**Statement.** Given a string `s` and an array `words` of strings **all the same length**, return the start indices of substrings in `s` that are a concatenation of **every** word in `words` exactly once, in **any order**, with no characters in between.

**Constraints.** `1 ≤ words.length ≤ 5000`, all words equal length `wlen` (`1..30`), `1 ≤ s.length ≤ 10⁴`.

**Approach.** Because every word has equal length `wlen` and the total concatenation length is fixed (`wlen × words.length`), the problem reduces to: at which start indices does a sliding window of words match the required multiset? Build a `need` map of word frequencies. The optimal trick is to run `wlen` independent sliding windows — one for each offset `0 .. wlen-1` — stepping `wlen` characters at a time so each window aligns to word boundaries. Within each offset, keep a window word-count map; when a word's count exceeds its need, shrink from the left by whole words; when the window holds exactly `words.length` words, record the start. This is O(wlen × (n/wlen)) = O(n × wlen) overall, far better than re-checking every index from scratch.

```java
import java.util.*;

class Solution {
    public List<Integer> findSubstring(String s, String[] words) {
        List<Integer> res = new ArrayList<>();
        int wlen = words[0].length(), count = words.length, total = wlen * count;
        if (s.length() < total) return res;

        Map<String, Integer> need = new HashMap<>();
        for (String w : words) need.merge(w, 1, Integer::sum);

        for (int offset = 0; offset < wlen; offset++) {
            int left = offset, formed = 0;
            Map<String, Integer> window = new HashMap<>();
            for (int right = offset; right + wlen <= s.length(); right += wlen) {
                String w = s.substring(right, right + wlen);
                if (!need.containsKey(w)) {          // unknown word resets the window
                    window.clear();
                    formed = 0;
                    left = right + wlen;
                    continue;
                }
                window.merge(w, 1, Integer::sum);
                formed++;
                while (window.get(w) > need.get(w)) { // too many of w -> drop from left
                    String lw = s.substring(left, left + wlen);
                    window.merge(lw, -1, Integer::sum);
                    formed--;
                    left += wlen;
                }
                if (formed == count) res.add(left);   // exact multiset match
            }
        }
        return res;
    }
}
```

**Walkthrough.** `s="barfoothefoobarman", words=["foo","bar"]` (wlen 3). Offset 0: window reads "bar","foo" → 2 words matching need → record start 0; continues, hits "the" (unknown) → reset; later "foo","bar" → record start 9. Answer `[0,9]`.

**Complexity** — Time O(n × wlen), Space O(words.length × wlen). **Edge cases:** unknown word clears the window cleanly; duplicate words in `words` are handled by count comparison; `s` shorter than total length returns empty immediately.

---

### Problem 30: Group Shifted Strings — Canonical Difference Key

**Statement.** Two strings are in the same group if one can be obtained from the other by **shifting every character by the same amount** (with wraparound, `z+1 = a`). Given a list of strings, group all shifted-equivalent strings together. For example `"abc"`, `"bcd"`, `"xyz"` belong together.

**Constraints.** `1 ≤ strs.length ≤ 200`, `0 ≤ strs[i].length ≤ 50`, lowercase letters.

**Approach.** This generalizes Group Anagrams (Problem 4): the grouping key must be *invariant under shifting*. Anchor each string by its first character: compute the sequence of **differences between consecutive characters modulo 26**. Two strings are shift-equivalent iff their difference sequences are identical, because a uniform shift changes every character by the same constant, leaving consecutive differences unchanged. Use that difference signature as the map key. The modulo handles wraparound (e.g. `"az"` → diff `(z-a)=25`; `"ba"` → diff `(a-b)=-1 ≡ 25 mod 26`, same group).

```
"abc": diffs (b-a, c-b)        = (1,1)
"bcd": diffs (c-b, d-c)        = (1,1)   -> same key as "abc"
"xyz": diffs (y-x, z-y)        = (1,1)   -> same key
"az" : diff  (z-a)=25
"ba" : diff  (a-b)=-1 mod26=25 -> same key as "az"
```

```java
import java.util.*;

class Solution {
    public List<List<String>> groupStrings(String[] strs) {
        Map<String, List<String>> groups = new HashMap<>();
        for (String s : strs) {
            groups.computeIfAbsent(key(s), k -> new ArrayList<>()).add(s);
        }
        return new ArrayList<>(groups.values());
    }

    private String key(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < s.length(); i++) {
            int diff = (s.charAt(i) - s.charAt(i - 1) + 26) % 26; // normalize wraparound
            sb.append(diff).append(',');                          // ',' delimits multi-digit diffs
        }
        return sb.toString(); // empty string for length-1 inputs -> their own group
    }
}
```

**Walkthrough.** `["abc","bcd","acef","xyz","az","ba","a","z"]`. Keys: "abc"/"bcd"/"xyz" → "1,1," together; "acef" → "2,2,1,"; "az"/"ba" → "25," together; "a"/"z" → "" together. Four groups.

**Complexity** — Time O(n·k) (k = max length), Space O(n·k). **Edge cases:** length-1 strings all share the empty key (correct — any single char shifts to any other); the `+26` before `% 26` normalizes negative differences; the comma delimiter prevents diffs like `1,2` colliding with `12`.

---

### Problem 31: Subarrays with Exactly K Distinct Integers — atMost(K) − atMost(K−1)

**Statement.** Given an array `nums`, return the number of contiguous subarrays that contain **exactly** `k` distinct integers.

**Constraints.** `1 ≤ n ≤ 2·10⁴`, `1 ≤ nums[i], k ≤ n`.

**Approach.** "Exactly K" is awkward for a single sliding window because the window can't cleanly expand or contract on an exact target. The classic transformation: **exactly(K) = atMost(K) − atMost(K−1)**. `atMost(K)` counts subarrays with at most `K` distinct values, which *is* a clean sliding window: maintain a `value → count` frequency map of the window, expand `right`, and shrink `left` whenever the distinct count exceeds `K`; each valid right endpoint contributes `right - left + 1` subarrays. Subtracting `atMost(K−1)` leaves exactly those with precisely `K` distinct. The hash map tracks distinctness in O(1) per step.

```java
import java.util.*;

class Solution {
    public int subarraysWithKDistinct(int[] nums, int k) {
        return atMost(nums, k) - atMost(nums, k - 1);
    }

    private int atMost(int[] nums, int k) {
        if (k < 0) return 0;
        Map<Integer, Integer> freq = new HashMap<>();
        int left = 0, count = 0;
        for (int right = 0; right < nums.length; right++) {
            if (freq.merge(nums[right], 1, Integer::sum) == 1) k--; // new distinct value
            while (k < 0) {                                         // too many distinct -> shrink
                if (freq.merge(nums[left], -1, Integer::sum) == 0) k++;
                left++;
            }
            count += right - left + 1; // all subarrays ending at right with <= k distinct
        }
        return count;
    }
}
```

**Walkthrough.** `nums=[1,2,1,2,3], k=2`. `atMost(2)` counts windows with ≤2 distinct = 12; `atMost(1)` = 5. Exactly-2 = 12 − 5 = `7` (e.g. [1,2], [2,1], [1,2,1], [2,1,2], [1,2,1,2], [1,2], [2]... matching the 7 subarrays with exactly two distinct values).

**Complexity** — Time O(n) (two linear passes), Space O(n). **Edge cases:** `atMost(k-1)` with `k=1` calls `atMost(0)` which correctly returns the count of empty-distinct windows (0 for non-empty arrays since any element is 1 distinct); the `merge` return value (new count) detects entering/leaving distinct values without a separate size lookup.

---

### Problem 32: Longest Substring with At Most K Distinct Characters — Window + Map

**Statement.** Given a string `s` and integer `k`, return the length of the longest substring that contains **at most `k` distinct characters**.

**Constraints.** `1 ≤ s.length ≤ 5·10⁴`, `0 ≤ k ≤ s.length`, any ASCII characters.

**Approach.** A single sliding window suffices here (no exact-count subtraction needed). Maintain a `char → count` map for the current window. Expand `right`; when the number of distinct characters (map size) exceeds `k`, shrink from `left`, decrementing counts and removing keys that hit zero, until the window is valid again. Track the maximum window width seen while valid. Each character is added and removed at most once, so it is O(n). Storing counts (not just presence) is essential: a character only truly leaves the window when its count reaches zero.

```java
import java.util.*;

class Solution {
    public int lengthOfLongestSubstringKDistinct(String s, int k) {
        if (k == 0) return 0;
        Map<Character, Integer> count = new HashMap<>();
        int left = 0, best = 0;
        for (int right = 0; right < s.length(); right++) {
            count.merge(s.charAt(right), 1, Integer::sum);
            while (count.size() > k) {              // too many distinct chars
                char lc = s.charAt(left);
                if (count.merge(lc, -1, Integer::sum) == 0) count.remove(lc);
                left++;
            }
            best = Math.max(best, right - left + 1);
        }
        return best;
    }
}
```

**Walkthrough.** `s="eceba", k=2`. Window grows "e","ec","ece" (2 distinct, len 3). At "eceb" → 3 distinct → shrink to "ceb"→ still 3 → shrink to "eb" (drop "e","c") len 2. At "eba" → "ba" len 2. Best = 3 ("ece"). For `k=3` the answer would be 4 ("eceb").

**Complexity** — Time O(n), Space O(min(n, alphabet)). **Edge cases:** `k=0` → 0 immediately (no characters allowed); when a count hits zero we must `remove` the key or `size()` overcounts distinctness; all-identical strings return their full length for any `k ≥ 1`.

---

### Problem 33: Insert Delete GetRandom O(1) — HashMap + Dynamic Array

**Statement.** Design `RandomizedSet` supporting `insert(val)`, `remove(val)`, and `getRandom()` — each in **average O(1)**. `getRandom` must return any present element with uniform probability.

**Constraints.** Up to `2·10⁵` operations; `getRandom` is only called when the set is non-empty.

**Approach.** A `HashSet` gives O(1) insert/remove but cannot pick a uniformly random element in O(1) (iteration is O(n)). The trick is to pair a **dynamic array** (for O(1) random indexing) with a **HashMap `val → index`** (for O(1) presence and locating). Insert appends to the array and records its index. The clever part is *remove*: to delete from the middle of an array in O(1), **swap the target with the last element**, update the moved element's index in the map, then pop the tail. `getRandom` just indexes the array at a random position. This swap-with-last pattern is the canonical interview move for O(1) array deletion.

```
array: [a, b, c, d]   map: {a:0, b:1, c:2, d:3}
remove(b): swap b<->d -> [a, d, c, b], fix map d:1, pop tail -> [a, d, c]
```

```java
import java.util.*;

class RandomizedSet {
    private final List<Integer> values = new ArrayList<>();
    private final Map<Integer, Integer> index = new HashMap<>(); // val -> position in values
    private final Random rng = new Random();

    public boolean insert(int val) {
        if (index.containsKey(val)) return false;
        index.put(val, values.size());
        values.add(val);
        return true;
    }

    public boolean remove(int val) {
        Integer pos = index.get(val);
        if (pos == null) return false;
        int last = values.size() - 1;
        int lastVal = values.get(last);
        values.set(pos, lastVal);        // move last element into the hole
        index.put(lastVal, pos);         // fix its recorded index
        values.remove(last);             // O(1) tail removal
        index.remove(val);
        return true;
    }

    public int getRandom() {
        return values.get(rng.nextInt(values.size()));
    }
}
```

**Walkthrough.** insert(1)→[1]{1:0}; insert(2)→[1,2]{1:0,2:1}; remove(1): swap with last (2)→values[0]=2, map{2:0}, pop→[2]; insert(2)→false (present). getRandom over [2] → 2.

**Complexity** — All operations average O(1); Space O(n). **Edge cases:** removing the last element swaps with itself (harmless); duplicate insert returns false without mutating; `getRandom` assumes non-empty per the contract. (Follow-up "RandomizedCollection" allowing duplicates stores a `val → Set<index>` instead.)

---

### Problem 34: All O(1) Data Structure — HashMap + Doubly Linked List of Count Buckets

**Statement.** Design `AllOne` supporting `inc(key)`, `dec(key)` (remove if count hits 0), `getMaxKey()`, and `getMinKey()` — **all in O(1)**. Return `""` for max/min when empty.

**Constraints.** Up to `5·10⁴` calls; keys are non-empty strings.

**Approach.** A frequency `HashMap` alone gives O(1) inc/dec but O(n) max/min. The senior-level design pairs the map with a **doubly linked list of count buckets**, kept sorted by count. Each bucket holds a `count` and a `LinkedHashSet<String>` of keys at that count; buckets are ordered ascending, so the head is the min count and the tail is the max. A `HashMap<String, Bucket>` locates a key's current bucket in O(1). On `inc`, move the key to the adjacent higher bucket (creating it if absent); on `dec`, move it lower (or drop it). Because we only ever step to a neighboring bucket, every operation is O(1), and head/tail give min/max instantly.

```java
import java.util.*;

class AllOne {
    private static class Bucket {
        int count;
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        Bucket prev, next;
        Bucket(int c) { count = c; }
    }

    private final Bucket head = new Bucket(Integer.MIN_VALUE); // sentinel (min side)
    private final Bucket tail = new Bucket(Integer.MAX_VALUE); // sentinel (max side)
    private final Map<String, Bucket> where = new HashMap<>();

    public AllOne() {
        head.next = tail;
        tail.prev = head;
    }

    public void inc(String key) {
        Bucket cur = where.get(key);
        int newCount = (cur == null ? 0 : cur.count) + 1;
        Bucket prevBucket = (cur == null ? head : cur);
        if (prevBucket.next.count != newCount) {
            insertAfter(prevBucket, new Bucket(newCount));
        }
        prevBucket.next.keys.add(key);
        where.put(key, prevBucket.next);
        if (cur != null) removeKeyFromBucket(cur, key);
    }

    public void dec(String key) {
        Bucket cur = where.get(key);
        if (cur == null) return;
        if (cur.count == 1) {
            where.remove(key);
        } else {
            int newCount = cur.count - 1;
            if (cur.prev.count != newCount) {
                insertAfter(cur.prev, new Bucket(newCount));
            }
            cur.prev.keys.add(key);
            where.put(key, cur.prev);
        }
        removeKeyFromBucket(cur, key);
    }

    public String getMaxKey() {
        return tail.prev == head ? "" : tail.prev.keys.iterator().next();
    }

    public String getMinKey() {
        return head.next == tail ? "" : head.next.keys.iterator().next();
    }

    private void insertAfter(Bucket a, Bucket b) {
        b.prev = a; b.next = a.next;
        a.next.prev = b; a.next = b;
    }

    private void removeKeyFromBucket(Bucket b, String key) {
        b.keys.remove(key);
        if (b.keys.isEmpty()) { // unlink empty bucket
            b.prev.next = b.next;
            b.next.prev = b.prev;
        }
    }
}
```

**Walkthrough.** inc("a")→bucket1{a}; inc("b")→bucket1{a,b}; inc("a")→bucket2{a}, bucket1{b}. getMaxKey→"a" (tail.prev=bucket2). getMinKey→"b" (head.next=bucket1). dec("b")→count was 1→removed, bucket1 empties and unlinks. getMinKey→"a".

**Complexity** — All operations O(1); Space O(number of distinct keys). **Edge cases:** empty structure returns `""` for max/min via sentinel checks; an empty bucket is unlinked immediately so head/tail always point at real counts; `dec` on an absent key is a no-op.

---

### Problem 35: Maximum Frequency Stack — Stack-of-Stacks Hashing

**Statement.** Design `FreqStack` with `push(val)` and `pop()`. `pop` removes and returns the **most frequent** element; on a frequency tie, it returns the element **closest to the top** (most recently pushed among the tied).

**Constraints.** Up to `2·10⁴` operations; `0 ≤ val ≤ 10⁹`.

**Approach.** Two hash maps cooperate. `freq: val → current count` tracks how many copies of each value are present. `group: frequency → stack of values` is the key idea: when a value reaches frequency `f`, push it onto the stack for level `f`. Track `maxFreq`. On `pop`, the answer is the top of the stack at level `maxFreq` (most recent among the most frequent — the per-level stack gives the recency tie-break for free); decrement that value's freq and lower `maxFreq` if its level empties. Every operation is O(1). The insight is that pushing a value onto *each* frequency level it passes through lets a later `dec` naturally fall back to the next-most-frequent.

```java
import java.util.*;

class FreqStack {
    private final Map<Integer, Integer> freq = new HashMap<>();
    private final Map<Integer, Deque<Integer>> group = new HashMap<>();
    private int maxFreq = 0;

    public void push(int val) {
        int f = freq.merge(val, 1, Integer::sum);   // new frequency of val
        maxFreq = Math.max(maxFreq, f);
        group.computeIfAbsent(f, k -> new ArrayDeque<>()).push(val);
    }

    public int pop() {
        Deque<Integer> top = group.get(maxFreq);
        int val = top.pop();                         // most-recent at the highest frequency
        freq.merge(val, -1, Integer::sum);
        if (top.isEmpty()) maxFreq--;                // that frequency level is now empty
        return val;
    }
}
```

**Walkthrough.** push 5,7,5,7,4,5. freq{5:3,7:2,4:1}, maxFreq=3. group: lvl1=[5,7,4], lvl2=[5,7], lvl3=[5]. pop→lvl3 top=5 (freq→2, lvl3 empties, maxFreq=2). pop→lvl2 top=7 (freq→1, maxFreq stays 2). pop→lvl2 top=5. pop→lvl1 top=4 (maxFreq→1). Sequence popped: 5,7,5,4.

**Complexity** — `push`/`pop` O(1); Space O(n). **Edge cases:** a tie always resolves to the most recently pushed because each level is a LIFO stack; `maxFreq` only decreases by 1 per pop because a value at the top level was, by construction, also pushed at every lower level; popping never underflows under the problem's guarantee that pop follows a push.

---

### Problem 36: Brick Wall — Frequency Map of Edge Positions

**Statement.** A wall is a list of rows; each row is a list of brick widths summing to the same total. Draw a vertical line top-to-bottom; it "crosses" a brick unless it falls exactly on an edge between two bricks. Return the **minimum number of bricks crossed** (the line cannot be drawn along the wall's left or right border).

**Constraints.** `1 ≤ rows ≤ 10⁴`, total bricks `≤ 2·10⁴`, brick widths are positive.

**Approach.** Crossing fewer bricks means passing through more *gaps* (edges) aligned at the same horizontal offset. So the answer is `rows − (max number of rows sharing an edge at some interior offset)`. Compute, for each row, the running prefix sums of brick widths *excluding* the last edge (the right border, which is illegal). Count how often each prefix offset occurs across all rows in a `HashMap<offset, count>`. The offset with the highest count is the best place for the line; subtract that count from the number of rows. This converts an otherwise geometric problem into pure frequency counting.

```
rows (widths):       gap offsets (prefix sums, excl. last):
[1,2,2,1]            1, 3, 5
[3,1,2]              3, 4
[1,3,2]              1, 4
[2,4]                2
[3,1,2]              3, 4
[1,3,1,1]            1, 4, 5
offset 4 occurs 4 times -> minimum crossed = 6 rows - 4 = 2
```

```java
import java.util.*;

class Solution {
    public int leastBricks(List<List<Integer>> wall) {
        Map<Integer, Integer> edgeCount = new HashMap<>();
        int maxAligned = 0;
        for (List<Integer> row : wall) {
            int offset = 0;
            for (int i = 0; i < row.size() - 1; i++) { // skip the last brick's right edge
                offset += row.get(i);
                int c = edgeCount.merge(offset, 1, Integer::sum);
                maxAligned = Math.max(maxAligned, c);
            }
        }
        return wall.size() - maxAligned;
    }
}
```

**Walkthrough.** For the wall above, offset 4 is shared by 4 rows (the most of any interior edge), so the line drawn at offset 4 crosses `6 − 4 = 2` bricks. If no interior edges align at all (e.g. every row is a single brick), `maxAligned` stays 0 and the answer is `rows` — the line must cross every row.

**Complexity** — Time O(total bricks), Space O(distinct edge offsets). **Edge cases:** excluding the final brick of each row is essential — counting the right border would always give `rows` aligned there but the line can't be drawn on the border; a wall of single-brick rows has no interior edges → answer equals row count; equal row totals are guaranteed by the problem.

---

## 🧩 Extended Problems — Set 3: Hard / Expert & Optimization

### Problem 37: First Missing Positive — In-Place Hashing, O(1) Extra Space

**Statement.** Given an unsorted integer array `nums`, return the **smallest positive integer** (`≥ 1`) that does **not** appear in it. The algorithm must run in **O(n)** time and use **O(1)** auxiliary space.

**Constraints.** `1 ≤ nums.length ≤ 10⁵`, `-2³¹ ≤ nums[i] ≤ 2³¹ − 1`.

**Approach.** A hash set of present values trivially solves this in O(n) time but O(n) space — the whole challenge is the O(1)-space constraint. The key insight: the answer must lie in `[1, n+1]`, so only values in `[1, n]` matter. Use the **array itself as a hash table** with the bijection `value v → index v-1`. Walk the array placing each in-range value `v` into slot `v-1` by swapping, repeating until the current slot holds the right value or an out-of-range/duplicate value. After this "cyclic sort", scan for the first index `i` where `nums[i] != i+1`; that `i+1` is the answer. If all slots are correct, the answer is `n+1`.

```
nums = [3, 4, -1, 1]   (n=4, answer must be in 1..5)
place each v in slot v-1 via swaps:
  [3,4,-1,1] -> swap 3 to idx2 -> [-1,4,3,1]
             -> swap 4 to idx3 -> [-1,1,3,4]
             -> swap -1? out of range, skip
             -> swap 1 to idx0 -> [1,-1,3,4]
scan: idx1 holds -1 (not 2) -> answer = 2
```

```java
class Solution {
    public int firstMissingPositive(int[] nums) {
        int n = nums.length;
        for (int i = 0; i < n; i++) {
            // Keep swapping nums[i] to its correct slot until it can't move.
            while (nums[i] >= 1 && nums[i] <= n && nums[nums[i] - 1] != nums[i]) {
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
}
```

**Walkthrough.** `[7,8,9,11,12]` (n=5): every value is out of range `[1,5]`, so no swaps occur; scan finds idx0 holds 7≠1 → answer `1`. `[1,2,0]`: cyclic sort gives `[1,2,0]` (0 out of range stays); idx2 holds 0≠3 → answer `3`.

**Complexity** — Time O(n) amortized (each swap places one value permanently into its correct slot, so total swaps ≤ n); Space O(1). **Edge cases:** the `nums[nums[i]-1] != nums[i]` guard prevents infinite loops on duplicates (e.g. `[1,1]`); values ≤ 0 and > n are ignored; the answer can be `n+1` when the array is exactly a permutation of `1..n`.

---

### Problem 38: Longest Duplicate Substring — Rabin–Karp Rolling Hash + Binary Search

**Statement.** Given a string `s`, return **any** longest substring that appears at least twice in `s` (overlaps allowed). Return `""` if no substring repeats.

**Constraints.** `2 ≤ s.length ≤ 3·10⁴`, lowercase English letters.

**Approach.** "Longest duplicated substring" has the monotonic property: if a duplicate of length `L` exists, so does one of length `L-1`. So **binary search the answer length** `L` in `[1, n-1]`, and for each `L` ask "is there a repeated substring of exactly length `L`?" — a question answered in O(n) with a **Rabin–Karp rolling polynomial hash**: slide a window of width `L`, roll the hash in O(1) per step, and store seen hashes in a `HashMap<hash, startIndex>`. On a hash collision, verify the actual substrings match (defends against false positives). A large modulus (or two independent moduli) keeps collisions rare. This is `O(n log n)` versus the `O(n²)` of hashing every substring or building a suffix automaton.

```java
import java.util.*;

class Solution {
    private static final long BASE = 131;
    private static final long MOD = 1_000_000_007L;

    public String longestDupSubstring(String s) {
        int n = s.length();
        int lo = 1, hi = n - 1, bestStart = -1, bestLen = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int start = findDup(s, mid);
            if (start != -1) {          // duplicate of length mid exists -> try longer
                bestStart = start;
                bestLen = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return bestStart == -1 ? "" : s.substring(bestStart, bestStart + bestLen);
    }

    // Returns a start index of some length-L substring that repeats, or -1.
    private int findDup(String s, int L) {
        long hash = 0, power = 1;
        for (int i = 0; i < L; i++) {
            hash = (hash * BASE + s.charAt(i)) % MOD;
            if (i < L - 1) power = (power * BASE) % MOD;
        }
        Map<Long, List<Integer>> seen = new HashMap<>();
        seen.computeIfAbsent(hash, k -> new ArrayList<>()).add(0);

        for (int i = 1; i + L <= s.length(); i++) {
            // Roll: drop leftmost char, add new rightmost char.
            hash = ((hash - s.charAt(i - 1) * power % MOD + MOD) % MOD) * BASE % MOD;
            hash = (hash + s.charAt(i + L - 1)) % MOD;
            List<Integer> bucket = seen.get(hash);
            if (bucket != null) {
                for (int j : bucket) {           // verify to rule out hash collisions
                    if (s.regionMatches(j, s, i, L)) return i;
                }
            }
            seen.computeIfAbsent(hash, k -> new ArrayList<>()).add(i);
        }
        return -1;
    }
}
```

**Walkthrough.** `s="banana"` (n=6). Binary search lengths 1..5. L=3: windows "ban","ana","nan","ana" — "ana" recurs (verified) → duplicate exists, try longer. L=4: "bana","anan","nana" — none repeat → shrink. Converges to bestLen 3 → returns `"ana"`.

**Complexity** — Time O(n log n) average (log n binary-search steps × O(n) rolling hash), Space O(n). **Edge cases:** explicit `regionMatches` verification is essential — a single modulus has a small but nonzero collision chance; no repeat returns `""`; overlaps are permitted, so storing every start index is correct.

---

### Problem 39: Sliding Window Maximum via Hashing/Counting — Monotonic Deque + Index Map

**Statement.** Given an array `nums` and window size `k`, return an array of the maximum of each contiguous window of size `k`.

**Constraints.** `1 ≤ nums.length ≤ 10⁵`, `1 ≤ k ≤ nums.length`, values fit in `int`.

**Approach.** The optimal O(n) solution is a **monotonic deque** storing *indices* in decreasing-value order; the front is always the current window's max. A hash map (`index → ` membership) is implicit in the deque, but the real "hashing" angle is the alternative **lazy-deletion heap**: a max-heap of `(value, index)` plus a check that the popped top's index is still inside the window — values that fall out of the window are skipped lazily rather than searched and removed (which a binary heap can't do in O(log n) by value). Both are shown; the deque is strictly better at O(n) vs the heap's O(n log n).

```java
import java.util.*;

class Solution {
    // Optimal: monotonic deque of indices, O(n).
    public int[] maxSlidingWindow(int[] nums, int k) {
        int n = nums.length;
        int[] res = new int[n - k + 1];
        Deque<Integer> dq = new ArrayDeque<>(); // indices, values decreasing front->back
        for (int i = 0; i < n; i++) {
            if (!dq.isEmpty() && dq.peekFirst() <= i - k) dq.pollFirst(); // drop out-of-window
            while (!dq.isEmpty() && nums[dq.peekLast()] <= nums[i]) dq.pollLast(); // pop smaller
            dq.offerLast(i);
            if (i >= k - 1) res[i - k + 1] = nums[dq.peekFirst()];
        }
        return res;
    }

    // Alternative: max-heap with lazy deletion, O(n log n).
    public int[] maxSlidingWindowHeap(int[] nums, int k) {
        int n = nums.length;
        int[] res = new int[n - k + 1];
        // Order by value desc; ties don't matter for the max.
        PriorityQueue<int[]> heap = new PriorityQueue<>((a, b) -> b[0] - a[0]); // [value, index]
        for (int i = 0; i < n; i++) {
            heap.offer(new int[] { nums[i], i });
            if (i >= k - 1) {
                while (heap.peek()[1] <= i - k) heap.poll(); // discard stale maxima lazily
                res[i - k + 1] = heap.peek()[0];
            }
        }
        return res;
    }
}
```

**Walkthrough.** `nums=[1,3,-1,-3,5,3,6,7], k=3`. Deque tracks decreasing maxima: windows give 3,3,5,5,6,7 → `[3,3,5,5,6,7]`. The heap version pushes all and lazily evicts any top whose index ≤ i−k before reading the max.

**Complexity** — Deque: Time O(n), Space O(k). Heap: Time O(n log n), Space O(n). **Edge cases:** `k=1` returns the array unchanged; the deque must pop from the front by index and from the back by value; lazy deletion never removes an in-window element prematurely because we only evict stale tops.

---

### Problem 40: Number of Atoms — Nested Parsing with Hash Maps & Stack

**Statement.** Given a chemical formula string (atoms with optional counts, nested parentheses with multipliers), return the count of each atom in **sorted order**, e.g. `"K4(ON(SO3)2)2"` → `"K4N2O14S4"`. An element name is an uppercase letter optionally followed by lowercase letters; a count is an optional integer (absent means 1).

**Constraints.** `1 ≤ formula.length ≤ 1000`; valid formula; counts fit in `int`.

**Approach.** Parentheses create scopes whose contents are multiplied — a classic **stack of frequency maps**. Push a fresh `Map<String,Integer>` on `(`, and on `)` read the trailing multiplier, scale the popped scope's counts, and merge them into the now-top scope. Atoms parse into the current top map. At the end, the bottom map holds totals; emit keys in sorted order using a `TreeMap` (which gives lexicographic ordering for free). This is the canonical hard parsing problem where hash maps carry per-scope accumulation and the stack carries nesting.

```java
import java.util.*;

class Solution {
    public String countOfAtoms(String formula) {
        Deque<Map<String, Integer>> stack = new ArrayDeque<>();
        stack.push(new HashMap<>());
        int i = 0, n = formula.length();

        while (i < n) {
            char c = formula.charAt(i);
            if (c == '(') {
                stack.push(new HashMap<>());
                i++;
            } else if (c == ')') {
                i++;
                int mult = readNumber(formula, i);
                i = skipNumber(formula, i);
                Map<String, Integer> top = stack.pop();
                Map<String, Integer> below = stack.peek();
                for (var e : top.entrySet()) {
                    below.merge(e.getKey(), e.getValue() * mult, Integer::sum);
                }
            } else { // an element name
                int start = i++;
                while (i < n && Character.isLowerCase(formula.charAt(i))) i++;
                String name = formula.substring(start, i);
                int count = readNumber(formula, i);
                i = skipNumber(formula, i);
                stack.peek().merge(name, count, Integer::sum);
            }
        }

        Map<String, Integer> totals = new TreeMap<>(stack.pop()); // sorted by element name
        StringBuilder sb = new StringBuilder();
        for (var e : totals.entrySet()) {
            sb.append(e.getKey());
            if (e.getValue() > 1) sb.append(e.getValue());
        }
        return sb.toString();
    }

    private int readNumber(String s, int i) { // returns the number at i, or 1 if none
        int num = 0; boolean any = false;
        while (i < s.length() && Character.isDigit(s.charAt(i))) {
            num = num * 10 + (s.charAt(i++) - '0');
            any = true;
        }
        return any ? num : 1;
    }

    private int skipNumber(String s, int i) {
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        return i;
    }
}
```

**Walkthrough.** `"K4(ON(SO3)2)2"`: parse K4 → {K:4}. `(` push. ON → {O:1,N:1}. `(` push. SO3 → {S:1,O:3}. `)2` → scale by 2 → {S:2,O:6}, merge into below → {O:7,N:1,S:2}. `)2` → scale by 2 → {O:14,N:2,S:4}, merge into bottom → {K:4,O:14,N:2,S:4}. TreeMap sorts → `"K4N2O14S4"`.

**Complexity** — Time O(n log m) (m distinct atoms, for the final sort), Space O(n). **Edge cases:** implicit count of 1 is omitted in output; nested parentheses multiply cumulatively; a `TreeMap` yields the required lexicographic ordering without an explicit sort step.

---

### Problem 41: Substring with At Most K Distinct via Bucketed TreeMap — Contains Duplicate III

**Statement.** Given `nums`, indices difference bound `indexDiff` (`k`), and value difference bound `valueDiff` (`t`), return `true` if there exist two **distinct** indices `i, j` such that `|i - j| ≤ k` and `|nums[i] - nums[j]| ≤ t`.

**Constraints.** `2 ≤ nums.length ≤ 10⁵`, `-10⁹ ≤ nums[i] ≤ 10⁹`, `0 ≤ k ≤ n`, `0 ≤ t ≤ 2³¹ − 1`.

**Approach.** The brute O(n·k) check is too slow at the limits. The expert trick is **bucketing**: map each value to a bucket of width `t+1` so that any two numbers within `t` of each other land in the **same or an adjacent** bucket. Maintain a `HashMap<bucketId, value>` over a sliding window of size `k`; only one value per bucket needs storing (if two share a bucket, they already differ by ≤ t → answer). For each new value, check its own bucket and the two neighbors, then evict the value that leaves the window. Use `Math.floorDiv` to bucket negatives correctly, and `long` to avoid overflow when subtracting near-`int`-extreme values. This is O(n). (A `TreeSet.floor/ceiling` window gives O(n log k) — the bucket map is the optimization.)

```java
import java.util.*;

class Solution {
    public boolean containsNearbyAlmostDuplicate(int[] nums, int k, int t) {
        if (t < 0 || k <= 0) return false;
        Map<Long, Long> bucket = new HashMap<>();
        long width = (long) t + 1;
        for (int i = 0; i < nums.length; i++) {
            long id = Math.floorDiv((long) nums[i], width); // floor handles negatives
            if (bucket.containsKey(id)) return true;        // same bucket -> within t
            if (bucket.containsKey(id - 1) && Math.abs(nums[i] - bucket.get(id - 1)) < width) return true;
            if (bucket.containsKey(id + 1) && Math.abs(nums[i] - bucket.get(id + 1)) < width) return true;
            bucket.put(id, (long) nums[i]);
            if (i >= k) bucket.remove(Math.floorDiv((long) nums[i - k], width)); // slide window
        }
        return false;
    }
}
```

**Walkthrough.** `nums=[1,5,9,1,5,9], k=2, t=3`, width=4. Buckets: 1→0, 5→1, 9→2, then evict 1; next 1→0 (bucket 0 was evicted), 5→1, 9→2 — no two within distance 2 share/neighbor with diff ≤3 → `false`. `nums=[1,2,3,1], k=3, t=0`, width=1: 1→1,2→2,3→3,1→1 again within window → same bucket → `true`.

**Complexity** — Time O(n), Space O(min(n, k)). **Edge cases:** `width = t+1` makes "same bucket ⇒ within t"; `floorDiv` (not `/`) is required so negative values bucket consistently; `long` cast prevents `nums[i] - other` overflow; `k ≤ 0` or `t < 0` are immediately false.

---

### Problem 42: Maximum Size Subarray Sum Equals K with Two Constraints — Prefix Map Optimization

**Statement.** Given `nums` (with negatives) and integer `k`, return the maximum length of a contiguous subarray summing to `k`, **and** simultaneously report the count of such maximum-length subarrays — in a single O(n) pass.

**Constraints.** `1 ≤ n ≤ 10⁵`, `-10⁴ ≤ nums[i] ≤ 10⁴`, `k` fits in `int`.

**Approach.** A naive approach runs two passes (one for the max length à la Problem 26, another to count). To do both in one pass we store, per prefix sum, **both the earliest index and how many earlier prefixes would yield the current best**. Concretely keep `firstIndex: prefixSum → earliest index` (for max length) and, separately, track the running best length and a counter that resets whenever a strictly longer subarray is found and increments on ties. The subtlety: counting *maximum-length* subarrays requires knowing how many `(j, i]` pairs achieve the current max length — since earliest index gives a unique longest reach per right endpoint, we increment the counter once per right endpoint that ties the best. This fuses two map-driven scans into one.

```java
import java.util.*;

class Solution {
    // Returns [maxLength, countOfMaxLengthSubarrays].
    public int[] maxLenAndCount(int[] nums, int k) {
        Map<Integer, Integer> firstIndex = new HashMap<>();
        firstIndex.put(0, -1);
        int sum = 0, best = 0, count = 0;
        for (int i = 0; i < nums.length; i++) {
            sum += nums[i];
            Integer j = firstIndex.get(sum - k);
            if (j != null) {
                int len = i - j;
                if (len > best) { best = len; count = 1; }   // new strictly-longer subarray
                else if (len == best && best > 0) count++;    // ties at the current max length
            }
            firstIndex.putIfAbsent(sum, i); // earliest index maximizes reachable length
        }
        return new int[] { best, count };
    }
}
```

**Walkthrough.** `nums=[1,-1,5,-2,3], k=3`: prefix sums 1,0,5,3,6. At i=3 (sum 3) need 0 → first at −1 → len 4 → best=4,count=1. No other length-4 subarray sums to 3 → result `[4, 1]`. For `nums=[1,0,-1], k=0` the longest-sum-0 subarrays of length 2 are `[1,? ]`… here only `[ -1? ]` etc.; ties would bump the counter.

**Complexity** — Time O(n), Space O(n). **Edge cases:** `putIfAbsent` keeps the earliest index so each right endpoint yields its single longest qualifying subarray; the `best > 0` guard prevents counting zero-length matches; an empty result is `[0, 0]`.

---

### Problem 43: Smallest Range Covering Elements from K Lists — Hash Count + Heap

**Statement.** You have `k` **sorted** integer lists. Find the smallest range `[a, b]` (smallest `b - a`, ties broken by smaller `a`) that includes **at least one number from each** of the `k` lists.

**Constraints.** `1 ≤ k ≤ 3500`, total elements `≤ 5·10⁴`, each list sorted ascending.

**Approach.** Merge-style sliding window over the union of all elements, tagged by which list each came from. Conceptually flatten all values into `(value, listId)` pairs sorted by value, then slide a window that must contain ≥1 element from every list — tracked by a **frequency map `listId → count`** and a `covered` counter of how many lists currently have a positive count. Expand the right edge until all `k` lists are covered, then shrink the left to minimize, updating the best range. The classic optimal variant uses a **min-heap of the current front element of each list** plus tracking the running maximum, advancing the list whose front is the heap minimum — the heap is the "which list to advance" oracle. Both rely on hashing/counting list membership.

```java
import java.util.*;

class Solution {
    public int[] smallestRange(List<List<Integer>> nums) {
        int k = nums.size();
        // Heap of [value, listId, indexInList], ordered by value.
        PriorityQueue<int[]> heap = new PriorityQueue<>((a, b) -> a[0] - b[0]);
        int curMax = Integer.MIN_VALUE;
        for (int i = 0; i < k; i++) {
            int v = nums.get(i).get(0);
            heap.offer(new int[] { v, i, 0 });
            curMax = Math.max(curMax, v);
        }
        int bestLo = 0, bestHi = Integer.MAX_VALUE;
        while (heap.size() == k) {                 // window covers all k lists
            int[] top = heap.poll();
            int curMin = top[0], list = top[1], idx = top[2];
            if (curMax - curMin < bestHi - bestLo) { // strictly smaller range
                bestLo = curMin;
                bestHi = curMax;
            }
            if (idx + 1 < nums.get(list).size()) {  // advance the list that had the min
                int next = nums.get(list).get(idx + 1);
                curMax = Math.max(curMax, next);
                heap.offer(new int[] { next, list, idx + 1 });
            } // else: this list is exhausted -> heap shrinks below k -> stop
        }
        return new int[] { bestLo, bestHi };
    }
}
```

**Walkthrough.** Lists `[[4,10,15,24,26],[0,9,12,20],[5,18,22,30]]`. Heap starts with 4,0,5 (max 10? no — max of fronts is 5). Advancing the minimum each step, the window covering all three narrows to `[20,24]` (b−a=4) which beats earlier candidates → return `[20,24]`.

**Complexity** — Time O(N log k) (N total elements), Space O(k). **Edge cases:** the moment any list is exhausted the window can no longer cover all `k` lists, so the loop terminates; strict `<` comparison plus initializing `bestHi-bestLo` to a huge gap gives the tie-break toward smaller `a` naturally; single-element lists work.

---

### Problem 44: Palindrome Pairs — Hash Map of Reversed Words

**Statement.** Given a list of **unique** words, return all pairs of distinct indices `(i, j)` such that the concatenation `words[i] + words[j]` is a palindrome.

**Constraints.** `1 ≤ words.length ≤ 5000`, `0 ≤ words[i].length ≤ 300`, lowercase letters.

**Approach.** Brute force concatenates and checks every ordered pair: O(n² · k). The optimized approach hashes each **word's reverse → its index**, then for each word splits it at every position into `left | right` and uses palindrome structure: (1) if `right` is a palindrome and the reverse of `left` exists as another word, then `word + thatWord` is a palindrome; (2) symmetrically, if `left` is a palindrome and the reverse of `right` exists, that word placed in front works. Careful handling of the empty-string split and de-duplication of the two cases avoids double counting. This brings it to O(n · k²) — each of n words does O(k) splits each costing O(k) for the palindrome check — a major win when n ≫ k.

```java
import java.util.*;

class Solution {
    public List<List<Integer>> palindromePairs(String[] words) {
        Map<String, Integer> revIndex = new HashMap<>();
        for (int i = 0; i < words.length; i++) {
            revIndex.put(new StringBuilder(words[i]).reverse().toString(), i);
        }
        List<List<Integer>> res = new ArrayList<>();
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            for (int cut = 0; cut <= w.length(); cut++) {
                String left = w.substring(0, cut);
                String right = w.substring(cut);
                // Case A: left is a palindrome -> a word == reverse(right) can prepend.
                if (isPalindrome(left)) {
                    Integer j = revIndex.get(right);
                    if (j != null && j != i) res.add(Arrays.asList(j, i));
                }
                // Case B: right is a palindrome -> a word == reverse(left) can append.
                // cut < length avoids re-emitting the full-word split handled by Case A.
                if (cut < w.length() && isPalindrome(right)) {
                    Integer j = revIndex.get(left);
                    if (j != null && j != i) res.add(Arrays.asList(i, j));
                }
            }
        }
        return res;
    }

    private boolean isPalindrome(String s) {
        int l = 0, r = s.length() - 1;
        while (l < r) if (s.charAt(l++) != s.charAt(r--)) return false;
        return true;
    }
}
```

**Walkthrough.** `words=["abcd","dcba","lls","s","sssll"]`. revIndex maps "dcba"→0, "abcd"→1, "sll"→2, "s"→3, "llsss"→4. For "abcd": split gives right "dcba"'s reverse exists → pair (0,1) etc. Result includes `[0,1],[1,0],[3,2],[2,4]`.

**Complexity** — Time O(n · k²), Space O(n · k). **Edge cases:** the empty string pairs with any palindrome word (handled by the `cut == length` split); `cut < w.length()` in Case B prevents the empty `right` from double-emitting the same pair Case A already covers; `j != i` excludes a word pairing with itself.

---

### Problem 45: LRU Cache with O(1) — LinkedHashMap accessOrder Optimization

**Statement.** Re-implement the LRU cache (Problem 8) using the **minimal idiomatic Java** approach and explain why it is O(1), then state when the hand-rolled doubly-linked-list version (Problem 8) is still preferable. Support `get(key)` and `put(key, value)` with capacity `c`.

**Constraints.** Up to `2·10⁵` operations; integer keys/values.

**Approach.** `LinkedHashMap` maintains a doubly linked list of entries internally; constructing it with `accessOrder = true` makes every `get`/`put` move the touched entry to the tail (most recently used). Overriding `removeEldestEntry` to return `true` when `size() > capacity` causes the eldest (LRU, at the head) entry to be evicted automatically on insertion. This is the production-grade one-liner: it reuses the JDK's already-O(1) linked-hash machinery instead of re-deriving it. The hand-rolled version (Problem 8) is preferable when you need custom eviction policies, thread-safe striping, or to demonstrate the data-structure design in an interview — but for correctness and brevity, this wins.

```java
import java.util.*;

class LRUCache {
    private final LinkedHashMap<Integer, Integer> map;

    public LRUCache(int capacity) {
        // accessOrder=true -> get/put reorder entries by recency.
        map = new LinkedHashMap<>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, Integer> eldest) {
                return size() > capacity; // evict the least-recently-used on overflow
            }
        };
    }

    public int get(int key) {
        return map.getOrDefault(key, -1); // getOrDefault still triggers access reordering on hit
    }

    public void put(int key, int value) {
        map.put(key, value);
    }
}
```

**Walkthrough.** capacity 2. `put(1,1)`, `put(2,2)` → order [1,2]. `get(1)` → moves 1 to tail → [2,1]. `put(3,3)` → size 3 > 2 → evict head (2) → [1,3]. `get(2)` → −1. Matches Problem 8's behaviour exactly.

**Complexity** — O(1) per operation; Space O(capacity). **Edge cases:** `getOrDefault` does trigger access-order reordering on a hit (it calls the internal `get`); `accessOrder=true` is mandatory — the default insertion-order map would evict the wrong entry; subclassing the map inline is the standard idiom and avoids a separate field for capacity-aware eviction.

---

### Problem 46: Count Distinct Substrings — Rolling Hash with Double Modulus

**Statement.** Given a string `s`, count the number of **distinct** non-empty substrings of `s`.

**Constraints.** `1 ≤ s.length ≤ 2000` (so O(n²) substring enumeration is acceptable; the challenge is O(1) hashing per substring).

**Approach.** There are O(n²) substrings; the trick is hashing each in O(1) so the total is O(n²) rather than O(n³). Precompute prefix polynomial hashes so any substring hash is a constant-time range query: `hash(i, j) = (h[j+1] - h[i] · base^(j-i+1)) mod M`. Store every substring hash in a `HashSet` for distinctness; the set's final size is the answer. To make collisions astronomically unlikely, use a **double hash** — two independent (base, modulus) pairs combined into one `long` key (`h1 * MOD2 + h2`). This is the standard competitive-programming alternative to a suffix automaton / suffix array (which give true O(n) but are far more code).

```java
import java.util.*;

class Solution {
    public int countDistinctSubstrings(String s) {
        int n = s.length();
        long MOD1 = 1_000_000_007L, MOD2 = 998_244_353L, B1 = 131, B2 = 137;
        long[] h1 = new long[n + 1], h2 = new long[n + 1];
        long[] p1 = new long[n + 1], p2 = new long[n + 1];
        p1[0] = p2[0] = 1;
        for (int i = 0; i < n; i++) {
            h1[i + 1] = (h1[i] * B1 + s.charAt(i)) % MOD1;
            h2[i + 1] = (h2[i] * B2 + s.charAt(i)) % MOD2;
            p1[i + 1] = p1[i] * B1 % MOD1;
            p2[i + 1] = p2[i] * B2 % MOD2;
        }
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < n; i++) {
            for (int j = i; j < n; j++) {
                long len = j - i + 1;
                long sub1 = ((h1[j + 1] - h1[i] * p1[(int) len] % MOD1) % MOD1 + MOD1) % MOD1;
                long sub2 = ((h2[j + 1] - h2[i] * p2[(int) len] % MOD2) % MOD2 + MOD2) % MOD2;
                seen.add(sub1 * MOD2 + sub2); // combine into a single 64-bit key
            }
        }
        return seen.size();
    }
}
```

**Walkthrough.** `s="aaa"`: substrings are "a"(×3 → 1 distinct), "aa"(×2 → 1), "aaa"(1). Distinct count = 3. `s="abc"`: all 6 substrings (a,b,c,ab,bc,abc) are distinct → 6.

**Complexity** — Time O(n²), Space O(n²) for the hash set (number of distinct substrings). **Edge cases:** the `+ MOD` before final `% MOD` keeps the subtraction non-negative; double hashing makes the combined key collision probability negligible; for `n` up to a few thousand this comfortably fits, but for `n ≈ 10⁵` a suffix automaton (true O(n)) is required.

---

### Problem 47: Longest Substring with At Least K Repeating Characters — Divide & Conquer with Counts

**Statement.** Given a string `s` and integer `k`, return the length of the longest substring in which **every** character appears at least `k` times.

**Constraints.** `1 ≤ s.length ≤ 10⁴`, `1 ≤ k ≤ 10⁵`, lowercase English letters.

**Approach.** A plain sliding window fails because the "at least k" constraint isn't monotonic. Two strong solutions exist; both use frequency counting. (1) **Divide and conquer:** count all 26 characters in the current segment; any character whose total count is `> 0` but `< k` can **never** be inside a valid substring, so it is a hard splitter — recurse on the pieces between such characters. (2) **Sliding window parameterized by `numUnique`:** for each target number of distinct characters `t` in `1..26`, run a window constrained to exactly `t` distinct chars and check that all of them meet the `k` threshold (tracking a `countAtLeastK` counter) — this restores monotonicity, giving O(26·n). The divide-and-conquer version is shown for its elegance.

```java
class Solution {
    public int longestSubstring(String s, int k) {
        return divide(s, 0, s.length(), k);
    }

    private int divide(String s, int start, int end, int k) {
        if (end - start < k) return 0;
        int[] count = new int[26];
        for (int i = start; i < end; i++) count[s.charAt(i) - 'a']++;

        for (int i = start; i < end; i++) {
            if (count[s.charAt(i) - 'a'] < k) {     // this char can't be in any valid substring
                int left = divide(s, start, i, k);  // split here and recurse on both sides
                // skip a run of consecutive splitter chars
                int next = i + 1;
                while (next < end && count[s.charAt(next) - 'a'] < k) next++;
                int right = divide(s, next, end, k);
                return Math.max(left, right);
            }
        }
        return end - start; // no splitter found -> whole segment is valid
    }
}
```

**Walkthrough.** `s="aaabb", k=3`: counts a3,b2; 'b' appears 2<3 → splitter. Recurse on "aaa" (all a's count 3 ≥3 → length 3) and on "" → max 3. `s="ababbc", k=2`: 'c' count 1<2 → split → "ababb" all chars ≥2 → length 5.

**Complexity** — Time O(26·n) ≈ O(n) in practice (recursion depth bounded by alphabet size), Space O(26) per frame. **Edge cases:** segment shorter than `k` returns 0 immediately; a run of splitter characters is skipped together to avoid redundant recursion; a segment with no splitter is wholly valid and returns its full length.

---

### Problem 48: Tuple with Same Product — Pair-Product Frequency Map

**Statement.** Given an array `nums` of **distinct** positive integers, return the number of tuples `(a, b, c, d)` such that `a·b == c·d`, where `a, b, c, d` are distinct elements of `nums`. Each valid set of four values is counted with all its orderings.

**Constraints.** `1 ≤ nums.length ≤ 1000`, `1 ≤ nums[i] ≤ 10⁴`, all elements distinct.

**Approach.** Enumerating all 4-element combinations is O(n⁴). Instead count, for each **product value**, how many unordered pairs `{i, j}` produce it — a `HashMap<product, pairCount>` filled by the O(n²) double loop over `i < j`. Any two distinct pairs that share the same product form a valid tuple. If a product is made by `m` pairs, the number of ways to choose 2 of those pairs is `C(m, 2) = m·(m−1)/2`, and each such pair-of-pairs corresponds to **8 ordered tuples** (`a·b == c·d` has 2 ways to order the first pair × 2 for the second × 2 to swap which pair is first = 8). Summing `8 · C(m,2)` over all products gives the answer in O(n²). Distinctness of input guarantees the two pairs never share an element.

```java
import java.util.*;

class Solution {
    public int tupleSameProduct(int[] nums) {
        Map<Integer, Integer> productCount = new HashMap<>();
        int n = nums.length, result = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                int p = nums[i] * nums[j];
                int prev = productCount.getOrDefault(p, 0);
                result += prev * 8;          // each earlier pair pairs with this one -> 8 orderings
                productCount.put(p, prev + 1);
            }
        }
        return result;
    }
}
```

**Walkthrough.** `nums=[2,3,4,6]`: products 2·3=6, 2·4=8, 2·6=12, 3·4=12, 3·6=18, 4·6=24. Product 12 occurs twice (pairs {2,6} and {3,4}). When the second 12-pair is processed, `prev=1` → adds 8. Answer `8`. `nums=[1,2,4,5,10]`: 1·10=10 and 2·5=10 share product → 8 tuples.

**Complexity** — Time O(n²), Space O(n²) worst case (distinct products). **Edge cases:** input distinctness ensures the two contributing pairs are element-disjoint (no need to subtract degenerate tuples); products of two `10⁴` values reach `10⁸`, which fits in `int`; fewer than two matching pairs for every product yields 0.

---

## Interview Q&A by Level

### 🟢 Basic

**Q: What is a hash function and what makes a good one?**
A function mapping keys to integer indices. A good one is fast, deterministic, and distributes keys uniformly to minimize collisions. Same input always yields the same output.

**Q: Why are hash map operations O(1) on average but O(n) worst case?**
With uniform distribution each bucket holds ~1 entry, so operations touch a constant number of slots. In the worst case (every key collides, or an adversary picks colliding keys), all entries land in one bucket and you scan them linearly.

**Q: Difference between `HashMap`, `HashSet`, `TreeMap`, `LinkedHashMap`?**
`HashMap`: key-value, no order, O(1). `HashSet`: keys only (backed by a HashMap). `TreeMap`: sorted by key, O(log n), red-black tree. `LinkedHashMap`: HashMap that preserves insertion (or access) order via a linked list.

**Q: Why use a fixed `int[26]` instead of a HashMap for character counting?**
Known small alphabet → array indexing is faster (no hashing, better cache locality) and uses constant space. Use a map only when the key space is large or unknown.

### 🟡 Intermediate

**Q: Explain the `hashCode`/`equals` contract.**
If `a.equals(b)` then `a.hashCode() == b.hashCode()` (mandatory). The converse need not hold — unequal objects may share a hash code (a collision). Overriding `equals` without `hashCode` breaks map lookups: the object lands in one bucket but is searched for in another.

**Q: What is the load factor and why resize?**
`load factor = entries / buckets`. As it rises, chains lengthen and operations degrade toward O(n). Resizing (typically at 0.75 in Java) doubles capacity and rehashes, restoring short chains. It costs O(n) but is amortized O(1) per insert.

**Q: Chaining vs open addressing — trade-offs?**
Chaining tolerates load factors ≥ 1, deletes simply, but uses pointer overhead and has worse cache locality. Open addressing is cache-friendly and pointer-free but needs load factor < 1, suffers clustering, and requires tombstones for deletion.

**Q: How does Java 8+ HashMap handle long collision chains?**
When a bucket's chain exceeds 8 entries (and capacity ≥ 64), it converts to a red-black tree, bounding worst case at O(log n) instead of O(n). It reverts to a list when the bucket shrinks below 6.

**Q: Why prefix sums + a map for "subarray sum = k" instead of sliding window?**
Sliding window needs monotonic prefix sums (all-positive numbers) to know when to shrink. With negatives the sum is non-monotonic, so prefix-sum frequency counting is required.

### 🟠 Advanced

**Q: Walk through the amortized analysis of resizing.**
With doubling, the i-th resize copies `2^i` elements. Over `n` inserts the total copy work is `1 + 2 + 4 + … + n ≈ 2n = O(n)`, so amortized O(1) per insert. Using a fixed *increment* (e.g. +10) instead would make it O(n) amortized — quadratic total.

**Q: What are tombstones and why does open addressing need them?**
On deletion you can't simply blank a slot — a later probe for a different key might stop early at the gap and miss it. A tombstone (special "deleted" marker) keeps probe chains intact; lookups skip past it, inserts may reuse it. Too many tombstones force a rehash.

**Q: How do hash-collision DoS attacks work and how do you defend?**
An attacker sends keys engineered to collide (e.g. HTTP params), degrading the map to O(n) per op and stalling the server. Defenses: randomized seeds / keyed hashing (SipHash), treeify long chains (Java), or per-request key limits.

**Q: When would you deliberately choose `TreeMap` over `HashMap`?**
When you need ordered iteration, range queries (`subMap`, `floorKey`, `ceilingKey`), or nearest-neighbor lookups — capabilities a hash map can't provide. You accept O(log n) for that ordering.

### 🔴 Expert

**Q: What is consistent hashing and what problem does it solve?**
In a distributed cache/database, naive sharding via `hash(key) % N` remaps almost every key when `N` changes (a node added/removed), causing a thundering cache miss. **Consistent hashing** places both nodes and keys on a hash ring (e.g. 0…2³²−1); a key belongs to the next node clockwise. Adding/removing a node only remaps keys in one arc — about `1/N` of keys move instead of nearly all. **Virtual nodes** (each physical node hashed to many ring points) smooth out load imbalance. Used by DynamoDB, Cassandra, memcached clients, and CDNs.

```
Hash ring (clockwise):  [NodeA] --k1--> [NodeB] --k2,k3--> [NodeC] --k4--> (wraps to NodeA)
Add NodeD between B and C: only k2,k3 may move to D; k1,k4 untouched.
```

**Q: Compare consistent hashing with rendezvous (HRW) hashing.**
Rendezvous hashing computes `hash(key, node)` for every node and picks the max; it gives even distribution and minimal disruption without a ring or virtual nodes, at O(N) per lookup vs O(log N) ring search. Consistent hashing scales better with many nodes and supports ordered ring operations.

**Q: How would you build a concurrent hash map with high throughput?**
Avoid one global lock. Use lock striping / segmentation (Java 7 `ConcurrentHashMap`), or per-bin CAS + synchronized on the first node with treeification (Java 8+). Reads are largely lock-free via `volatile` reads; resizing is cooperative (multiple threads help transfer bins).

**Q: What is a Bloom filter and how does it relate to hashing?**
A space-efficient probabilistic set using `k` hash functions over a bit array. It answers "definitely not present" or "possibly present" — no false negatives, tunable false-positive rate, and no element storage. Used as a front filter to avoid expensive disk/network lookups (e.g. LSM-tree DBs, Cassandra, CDNs).

---

## ⚠️ Common Pitfalls

- **Overriding `equals` but not `hashCode`** (or vice versa) — entries vanish from maps/sets. Always override both, or use immutable keys / records.
- **Mutating a key after insertion** so its hash code changes — the entry becomes unreachable.
- **Using sliding window for "subarray sum = k" with negatives** — it's incorrect; use prefix-sum maps.
- **Forgetting to seed `{0:1}`** in prefix-sum problems — misses subarrays that start at index 0.
- **Integer overflow** in prefix sums / hash computations — use `long` when values are large.
- **Autoboxing surprises** — `Integer` caches only −128…127; comparing boxed values with `==` outside that range fails. Use `.equals()` or primitives.
- **Iterating a map while modifying it** — throws `ConcurrentModificationException`; collect keys first or use an iterator's `remove()`.
- **Assuming iteration order** in a plain `HashMap` — it is unspecified; use `LinkedHashMap`/`TreeMap` if order matters.
- **Open addressing without tombstones** — deletions break probe sequences and lose entries.
- **Choosing a power-of-two table size with a weak hash** — low bits dominate; Java mixes high bits into low (`h ^ (h >>> 16)`) to compensate. Roll your own carefully.

---

## 📚 Further Reading

- *Introduction to Algorithms* (CLRS), Ch. 11 — Hash Tables (chaining, open addressing, universal hashing).
- *The Algorithm Design Manual* (Skiena) — practical hashing and dictionary structures.
- Java `HashMap` / `ConcurrentHashMap` / `LinkedHashMap` source and Javadoc — treeification, resizing, hash spreading.
- Karger et al., "Consistent Hashing and Random Trees" (1997) — the original distributed-caching paper.
- Thaler & Driscoll, "Rendezvous (Highest Random Weight) Hashing."
- Bloom (1970), "Space/Time Trade-offs in Hash Coding with Allowable Errors."
- LeetCode tags: *Hash Table*, *Design*, *Sliding Window*, *Prefix Sum* — drill the two-sum, anagram, prefix-sum, and cache-design families.

[← Back to master index](../README.md) | [← DSA index](README.md)
