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
