# Linked Lists

Linked lists are linear collections of nodes connected by pointers, trading random access for O(1) structural edits and dynamic growth. They are the backbone of dozens of classic interview problems and of real systems like LRU caches, free-list allocators, and adjacency lists.

[← Back to master index](../README.md) &nbsp;|&nbsp; [← DSA index](README.md)

---

## Concept & Intuition

A **linked list** stores each element in a `Node` that holds a value and one or more references to other nodes. Unlike arrays, the elements are *not* contiguous in memory — you reach element *k* only by walking from the head through *k* pointers.

**Three canonical flavors:**

- **Singly linked** — each node points to `next` only. Forward traversal, O(1) head insert/delete.
- **Doubly linked** — each node points to `next` and `prev`. Backward traversal, O(1) delete given a node reference (no predecessor search). Used inside `LinkedList`, `LinkedHashMap`, and LRU caches.
- **Circular** — the tail's `next` points back to the head (singly) or head/tail are mutually linked (doubly). Models round-robin scheduling, ring buffers, Josephus problem.

**When to reach for a linked list:**

- You need O(1) insert/delete at a known position and rarely random-index.
- You're building a queue/deque/stack where ends churn constantly.
- The problem *is* a list-manipulation problem (reverse, merge, reorder, cycle).

**Invariants to protect during interviews:**

1. The `head` reference must always point to the real first node (or `null` for empty).
2. The last node's `next` must be `null` — unless the list is intentionally circular.
3. When you splice, **save the node you're about to orphan before you overwrite a pointer**, or you'll lose the rest of the list.
4. A **dummy/sentinel** head node removes the special-case branch for "modifying the head," which is where most bugs live.

```
Singly:   head
           |
           v
         [ 1 | * ]--->[ 2 | * ]--->[ 3 | / ]      ( / = null )

Doubly:  null<--[ 1 ]<==>[ 2 ]<==>[ 3 ]-->null

Circular:  [ 1 ]-->[ 2 ]-->[ 3 ]--+
             ^------------------- +
```

The dominant solution techniques are: **dummy head**, **two pointers (slow/fast)**, **runner with a gap**, **in-place pointer reversal**, and **hashmap-augmented lists** (LRU, random pointer).

---

## Complexity Cheat-Sheet

| Operation | Singly | Doubly | Array (for contrast) |
|---|---|---|---|
| Access by index | O(n) | O(n) | **O(1)** |
| Search by value | O(n) | O(n) | O(n) |
| Insert at head | **O(1)** | **O(1)** | O(n) |
| Insert at tail (tail ptr) | O(1) | O(1) | O(1) amortized |
| Insert at tail (no tail ptr) | O(n) | O(n) | O(1) amortized |
| Delete head | **O(1)** | **O(1)** | O(n) |
| Delete given node ref | O(n)\* | **O(1)** | O(n) |
| Reverse | O(n) / O(1) space | O(n) | O(n) |
| Detect cycle (Floyd) | O(n) time, **O(1) space** | — | — |
| Space per node | 1 ptr | 2 ptrs | 0 extra |

\*Singly needs the predecessor; if you only hold the node, you can fake deletion by copying the next node's value (works for any node except the tail).

---

## Patterns & Recognition

| Signal in the prompt | Technique to apply |
|---|---|
| "Detect a loop / does it terminate?" | Floyd's tortoise & hare (slow/fast) |
| "Find the middle" / "is it a palindrome?" | Slow advances 1, fast advances 2 |
| "k-th from the end" / "remove Nth from end" | Two pointers with a fixed gap of k |
| "Reverse" / "swap pairs" / "reorder" | In-place pointer rewiring, optional dummy head |
| "Merge sorted lists" | Dummy head + tail-stitching; k lists → min-heap or divide-and-conquer |
| "Modify near the head may happen" | Add a dummy/sentinel node |
| "O(1) get and put with capacity" | HashMap + doubly linked list (LRU) |
| "Clone with extra pointers" | HashMap original→copy, or interleaving trick |
| "Intersection / shared tail" | Length diff or two-pointer pointer-swap |

**Heuristic:** if you find yourself wanting to "go back one node," either keep a `prev` pointer as you walk, or use a dummy head so you never special-case the front.

---

## Coding Problems

### Problem 1: Reverse a Singly Linked List

> Given the `head` of a singly linked list, reverse it and return the new head.
> Constraints: `0 <= n <= 5000`, `-5000 <= Node.val <= 5000`.

**Approach.** Brute force would copy values into an array, reverse, and rebuild — O(n) extra space. The optimal approach rewires `next` pointers in place: walk the list carrying `prev`, and at each node flip its `next` to point backward. The classic three-pointer dance. A recursive variant reverses the tail first, then fixes the local link.

```java
public class ReverseList {
    static class ListNode { int val; ListNode next; ListNode(int v){val=v;} }

    // Iterative — O(1) space
    public ListNode reverse(ListNode head) {
        ListNode prev = null, curr = head;
        while (curr != null) {
            ListNode next = curr.next; // save before overwrite
            curr.next = prev;          // flip the link
            prev = curr;               // advance prev
            curr = next;               // advance curr
        }
        return prev; // prev is the new head
    }

    // Recursive — O(n) stack space
    public ListNode reverseRec(ListNode head) {
        if (head == null || head.next == null) return head;
        ListNode newHead = reverseRec(head.next);
        head.next.next = head; // make the next node point back to us
        head.next = null;      // we become the new tail
        return newHead;
    }
}
```

**Dry run** on `1->2->3`:
- prev=∅, curr=1 → save 2, 1.next=∅, prev=1, curr=2
- prev=1, curr=2 → save 3, 2.next=1, prev=2, curr=3
- prev=2, curr=3 → save ∅, 3.next=2, prev=3, curr=∅ → return `3->2->1`.

**Time:** O(n). **Space:** O(1) iterative, O(n) recursive.

**Follow-ups:** reverse only between positions *m..n* (reverse a sublist); reverse in groups of *k* (LeetCode 25); reverse a doubly linked list (swap `next`/`prev` per node).

---

### Problem 2: Find the Middle of a Linked List

> Return the middle node. If two middles exist (even length), return the second.
> Constraints: `1 <= n <= 100`.

**Approach.** Brute force counts length then walks n/2 — two passes. Optimal does one pass with slow/fast pointers: when `fast` reaches the end, `slow` sits at the middle.

```java
public ListNode middleNode(ListNode head) {
    ListNode slow = head, fast = head;
    while (fast != null && fast.next != null) {
        slow = slow.next;
        fast = fast.next.next;
    }
    return slow; // returns 2nd middle for even length
}
```

To return the **first** middle (useful for splitting before a merge sort), stop one step earlier with `while (fast.next != null && fast.next.next != null)`.

**Dry run** on `1->2->3->4->5`: slow visits 1→2→3; fast visits 1→3→5(null next) → returns `3`.

**Time:** O(n). **Space:** O(1).

**Follow-ups:** split a list into two halves; use the first-middle variant inside merge sort on a list.

---

### Problem 3: Detect a Cycle (Floyd's Algorithm)

> Return `true` if the list contains a cycle.
> Constraints: cycle indicated by an internal back-pointer; `0 <= n <= 10^4`.

**Approach.** Brute force stores visited nodes in a `HashSet` — O(n) space. Floyd's tortoise & hare uses O(1) space: a fast pointer moving twice as fast will eventually lap a slow pointer inside any cycle.

```java
public boolean hasCycle(ListNode head) {
    ListNode slow = head, fast = head;
    while (fast != null && fast.next != null) {
        slow = slow.next;
        fast = fast.next.next;
        if (slow == fast) return true; // they met inside the loop
    }
    return false; // fast fell off the end → no cycle
}
```

**Why it works:** inside a cycle of length *L*, the gap between fast and slow shrinks by 1 each step, so they must collide within *L* steps.

**Time:** O(n). **Space:** O(1).

**Follow-ups:** Problem 4 (find the cycle's entry); compute the cycle length (count steps from meeting point back to itself).

---

### Problem 4: Find and Remove the Cycle's Start

> Return the node where the cycle begins, or `null`. Then bonus: break the cycle.

**Approach.** After Floyd's meeting point, reset one pointer to head and advance both at speed 1 — they meet at the cycle entry. Proof: let the non-cycle prefix be *a*, the entry-to-meeting distance be *b*. Slow traveled `a+b`, fast traveled `2(a+b)` and is also `a+b+kL`, so `a = kL - b`, meaning a pointer from head and one from the meeting point converge exactly at the entry.

```java
public ListNode detectCycleStart(ListNode head) {
    ListNode slow = head, fast = head;
    while (fast != null && fast.next != null) {
        slow = slow.next;
        fast = fast.next.next;
        if (slow == fast) {                 // phase 1: meeting
            ListNode p = head;
            while (p != slow) {             // phase 2: find entry
                p = p.next;
                slow = slow.next;
            }
            return p;                       // cycle entry node
        }
    }
    return null;
}

public void removeCycle(ListNode head) {
    ListNode entry = detectCycleStart(head);
    if (entry == null) return;
    ListNode p = entry;
    while (p.next != entry) p = p.next; // walk to node just before entry
    p.next = null;                       // sever the loop
}
```

**Time:** O(n). **Space:** O(1).

**Follow-ups:** LeetCode 287 (Find the Duplicate Number) maps array indices to a virtual linked list and reuses this exact algorithm.

---

### Problem 5: Merge Two Sorted Lists

> Splice two sorted lists into one sorted list and return its head.
> Constraints: both lists sorted ascending; total `0 <= n <= 100`.

**Approach.** Use a **dummy head** so you never special-case the first append. Walk both lists, always attaching the smaller current node to the growing tail. Append the leftover tail at the end (it's already sorted).

```java
public ListNode mergeTwoLists(ListNode a, ListNode b) {
    ListNode dummy = new ListNode(0), tail = dummy;
    while (a != null && b != null) {
        if (a.val <= b.val) { tail.next = a; a = a.next; }
        else                { tail.next = b; b = b.next; }
        tail = tail.next;
    }
    tail.next = (a != null) ? a : b; // attach remainder
    return dummy.next;
}
```

**Dry run** `1->3` & `2->4`: pick 1, pick 2, pick 3, then attach remainder `4` → `1->2->3->4`.

**Time:** O(n+m). **Space:** O(1) (reuses existing nodes). A recursive version is O(n+m) stack.

**Follow-ups:** merge while removing duplicates; merge in descending order; this is the merge step of list merge sort.

---

### Problem 6: Remove the N-th Node From the End

> Remove the n-th node from the end and return the head.
> Constraints: `1 <= n <= length`.

**Approach.** Brute force: compute length, then walk to `length - n`. Optimal one-pass: advance a `fast` pointer *n* steps ahead, then move `fast` and `slow` together; when `fast` hits the end, `slow` sits just before the target. A dummy head elegantly handles "remove the head itself."

```java
public ListNode removeNthFromEnd(ListNode head, int n) {
    ListNode dummy = new ListNode(0);
    dummy.next = head;
    ListNode fast = dummy, slow = dummy;
    for (int i = 0; i < n; i++) fast = fast.next; // open a gap of n
    while (fast.next != null) {                   // move both to the end
        fast = fast.next;
        slow = slow.next;
    }
    slow.next = slow.next.next;                    // unlink target
    return dummy.next;
}
```

**Dry run** `1->2->3->4->5`, n=2: gap → fast at 2; advance until fast=5, slow=3; `slow.next = 5` skips node 4 → `1->2->3->5`.

**Time:** O(n). **Space:** O(1).

**Follow-ups:** return the n-th from end *without* deleting; handle `n > length` defensively.

---

### Problem 7: Intersection of Two Linked Lists

> Return the node where two singly lists merge, or `null`. They share a common tail.
> Constraints: no cycles; lists may differ in length.

**Approach.** Brute force hashes one list's nodes — O(n) space. The elegant O(1)-space trick: two pointers each traverse `listA` then `listB`. After at most `lenA + lenB` steps both have walked the same total distance, so they align at the intersection (or both reach `null`).

```java
public ListNode getIntersectionNode(ListNode headA, ListNode headB) {
    if (headA == null || headB == null) return null;
    ListNode a = headA, b = headB;
    while (a != b) {                 // equal references, not equal values
        a = (a == null) ? headB : a.next;
        b = (b == null) ? headA : b.next;
    }
    return a; // intersection node, or null if disjoint
}
```

**Why it terminates:** if they intersect, the pointer-swap equalizes the offset; if disjoint, both become `null` simultaneously and the loop ends.

**Time:** O(n+m). **Space:** O(1).

**Follow-ups:** length-difference variant (advance the longer list by `|lenA-lenB|` first); detect intersection when one list has a cycle.

---

### Problem 8: Reorder List

> Given `L0 -> L1 -> ... -> Ln-1 -> Ln`, reorder to `L0 -> Ln -> L1 -> Ln-1 -> L2 -> ...` in place.
> Constraints: `1 <= n <= 5*10^4`; do not modify node values, only pointers.

**Approach.** Composite of earlier skills: (1) find the middle with slow/fast, (2) reverse the second half, (3) interleave the two halves. No extra array allowed for senior-level versions.

```java
public void reorderList(ListNode head) {
    if (head == null || head.next == null) return;

    // 1. find middle (first-middle variant so left half is >= right)
    ListNode slow = head, fast = head;
    while (fast.next != null && fast.next.next != null) {
        slow = slow.next;
        fast = fast.next.next;
    }

    // 2. reverse the second half
    ListNode second = slow.next;
    slow.next = null;            // cut into two lists
    ListNode prev = null;
    while (second != null) {
        ListNode nx = second.next;
        second.next = prev;
        prev = second;
        second = nx;
    }
    second = prev;               // head of reversed second half

    // 3. weave the two halves together
    ListNode first = head;
    while (second != null) {
        ListNode f = first.next, s = second.next;
        first.next = second;
        second.next = f;
        first = f;
        second = s;
    }
}
```

**Dry run** `1->2->3->4`: middle cut → `1->2` and `3->4`; reverse → `4->3`; weave → `1->4->2->3`.

**Time:** O(n). **Space:** O(1).

**Follow-ups:** check palindrome (steps 1–2 then compare halves); odd-length handling (`1->2->3->4->5` → `1->5->2->4->3`).

---

### Problem 9: Copy List With Random Pointer

> Each node has `next` and a `random` pointer to any node or `null`. Return a deep copy.
> Constraints: `0 <= n <= 1000`.

**Approach.** HashMap approach: map each original node to its clone, then a second pass wires `next`/`random` via the map — O(n) space. The senior-level O(1)-extra-space trick interleaves copies into the original list, sets `random` using positional adjacency, then unzips the two lists.

```java
class Node { int val; Node next, random; Node(int v){val=v;} }

public Node copyRandomList(Node head) {
    if (head == null) return null;

    // 1. clone each node right after its original: A->A'->B->B'...
    for (Node cur = head; cur != null; cur = cur.next.next) {
        Node copy = new Node(cur.val);
        copy.next = cur.next;
        cur.next = copy;
    }

    // 2. assign random pointers for the copies
    for (Node cur = head; cur != null; cur = cur.next.next) {
        cur.next.random = (cur.random != null) ? cur.random.next : null;
    }

    // 3. detach the copy list from the original
    Node dummy = new Node(0), copyTail = dummy;
    for (Node cur = head; cur != null; cur = cur.next) {
        copyTail.next = cur.next;     // grab the clone
        copyTail = copyTail.next;
        cur.next = cur.next.next;     // restore original next
    }
    return dummy.next;
}
```

**Why step 2 works:** because each clone sits immediately after its original, `cur.random.next` is exactly the clone of `cur.random`.

**Time:** O(n). **Space:** O(1) extra (excluding the output). The HashMap version is O(n) time, O(n) space but easier to reason about under interview pressure.

**Follow-ups:** state the HashMap solution first for clarity, then offer the interleaving optimization; handle graphs with arbitrary back-references (clone graph generalization).

---

### Problem 10: Merge k Sorted Lists (Hard)

> Merge `k` sorted linked lists into one sorted list.
> Constraints: `0 <= k <= 10^4`, total nodes up to `10^4`.

**Approach.** Brute force concatenates all and sorts: O(N log N). Better: a **min-heap** of the current heads gives O(N log k). Equally good: **divide-and-conquer** pairwise merging, also O(N log k) with O(1) extra space beyond recursion. The heap version is shown — it's the most natural to explain and extends to streaming input.

```java
import java.util.PriorityQueue;

public ListNode mergeKLists(ListNode[] lists) {
    PriorityQueue<ListNode> pq =
        new PriorityQueue<>((x, y) -> Integer.compare(x.val, y.val));
    for (ListNode node : lists)
        if (node != null) pq.offer(node);

    ListNode dummy = new ListNode(0), tail = dummy;
    while (!pq.isEmpty()) {
        ListNode min = pq.poll();   // smallest head across all lists
        tail.next = min;
        tail = tail.next;
        if (min.next != null) pq.offer(min.next); // push its successor
    }
    return dummy.next;
}

// Divide-and-conquer alternative — O(N log k), no heap
public ListNode mergeKListsDC(ListNode[] lists) {
    if (lists == null || lists.length == 0) return null;
    int interval = 1, n = lists.length;
    while (interval < n) {
        for (int i = 0; i + interval < n; i += 2 * interval)
            lists[i] = mergeTwoLists(lists[i], lists[i + interval]);
        interval *= 2;
    }
    return lists[0];
}

private ListNode mergeTwoLists(ListNode a, ListNode b) {
    ListNode dummy = new ListNode(0), tail = dummy;
    while (a != null && b != null) {
        if (a.val <= b.val) { tail.next = a; a = a.next; }
        else                { tail.next = b; b = b.next; }
        tail = tail.next;
    }
    tail.next = (a != null) ? a : b;
    return dummy.next;
}
```

**Why O(N log k):** the heap holds at most `k` elements, so each of the `N` total nodes incurs an O(log k) push/pop. Divide-and-conquer performs `log k` merge rounds, each touching all `N` nodes.

**Time:** O(N log k). **Space:** O(k) for the heap; O(1) extra (plus O(log k) recursion conceptually) for divide-and-conquer.

**Follow-ups:** merge k sorted *arrays* or *iterators*; this is the merge engine behind external sort and LSM-tree compaction.

---

### Problem 11: LRU Cache (Hard — HashMap + Doubly Linked List)

> Design `LRUCache(capacity)` with `get(key)` and `put(key, value)` both in **O(1)**.
> Evict the least-recently-used entry when full. `1 <= capacity <= 3000`.

**Approach.** A `HashMap<Integer, Node>` gives O(1) lookup; a **doubly linked list** maintains usage order. The most-recently-used sits next to a `head` sentinel, the least-recently-used next to a `tail` sentinel. Every access moves a node to the front; eviction pops from the back. Sentinels eliminate null checks on the ends.

```java
import java.util.HashMap;

public class LRUCache {
    private static class Node {
        int key, val;
        Node prev, next;
        Node(int k, int v) { key = k; val = v; }
    }

    private final int capacity;
    private final HashMap<Integer, Node> map = new HashMap<>();
    private final Node head = new Node(0, 0); // most-recent side
    private final Node tail = new Node(0, 0); // least-recent side

    public LRUCache(int capacity) {
        this.capacity = capacity;
        head.next = tail;
        tail.prev = head;
    }

    public int get(int key) {
        Node node = map.get(key);
        if (node == null) return -1;
        moveToFront(node);   // mark as most-recently-used
        return node.val;
    }

    public void put(int key, int value) {
        Node node = map.get(key);
        if (node != null) {
            node.val = value;
            moveToFront(node);
            return;
        }
        if (map.size() == capacity) {       // evict LRU
            Node lru = tail.prev;
            remove(lru);
            map.remove(lru.key);
        }
        Node fresh = new Node(key, value);
        map.put(key, fresh);
        addFront(fresh);
    }

    private void addFront(Node n) {
        n.next = head.next;
        n.prev = head;
        head.next.prev = n;
        head.next = n;
    }

    private void remove(Node n) {
        n.prev.next = n.next;
        n.next.prev = n.prev;
    }

    private void moveToFront(Node n) {
        remove(n);
        addFront(n);
    }
}
```

**Dry run** capacity 2: `put(1,1)`, `put(2,2)`, `get(1)`→1 (moves 1 to front), `put(3,3)` evicts key 2 (now LRU), `get(2)`→-1.

**Time:** O(1) for both `get` and `put`. **Space:** O(capacity).

**Follow-ups:** LFU cache (frequency buckets, LeetCode 460); thread-safe LRU (`synchronized` or a `ConcurrentHashMap` + locking); Java one-liner using `LinkedHashMap` with `removeEldestEntry` overridden; TTL-based eviction.

---

## Interview Q&A by Level

### 🟢 Basic

**Q: Array vs linked list — when do you pick each?**
Arrays give O(1) random access and cache-friendly contiguous memory; linked lists give O(1) insert/delete at known positions and grow without reallocation. Pick arrays for read-heavy/index-heavy work, lists for churn-heavy ends or when you hold node references.

**Q: Why use a dummy/sentinel head?**
It guarantees there's always a node "before" the first real node, so operations that might change the head (insert at front, delete the head) use the same code path as everywhere else — fewer null branches, fewer bugs.

**Q: How do you detect the end of a singly list?**
The last node's `next` is `null`. Loop with `while (curr != null)`; to peek at the next node safely use `while (curr != null && curr.next != null)`.

### 🟡 Intermediate

**Q: How does the slow/fast pointer find the middle, and what's the edge difference between odd and even lengths?**
Fast moves twice as fast, so when it exits, slow is halfway. For even length the loop condition decides whether you land on the first or second middle — `fast != null && fast.next != null` gives the second; `fast.next != null && fast.next.next != null` gives the first.

**Q: Prove Floyd's algorithm always finds a cycle if one exists.**
Once both pointers are inside the cycle, the distance between them changes by exactly one each step (fast gains one on slow). In a cycle of length L that distance cycles through all residues mod L, so it must hit 0 within L steps — a guaranteed collision.

**Q: Why does the intersection two-pointer trick work?**
Each pointer walks `lenA + lenB` nodes total by switching heads at the end. That equalizes the head start, so they reach the shared node simultaneously; if the lists are disjoint both arrive at `null` together and the loop ends.

### 🟠 Advanced

**Q: Explain the amortized cost of LRU operations and why a plain array won't do.**
`get`/`put` are O(1) *worst case*, not just amortized: the HashMap lookup is O(1), and the DLL splice (`remove` + `addFront`) is constant pointer surgery. An array-backed LRU would need O(n) to shift elements on every access to maintain order, so the DLL is essential.

**Q: Walk through the O(1)-space copy-with-random-pointer trick.**
Interleave each clone right after its original (`A→A'→B→B'`), so `cur.random.next` *is* the clone of `cur.random` — set randoms in one pass. Then unzip the interleaved list back into original and copy lists, restoring the originals' `next` pointers.

**Q: When does recursive reversal blow up, and how do you mitigate?**
Recursion uses O(n) stack; on a 10^6-node list it overflows. Use the iterative three-pointer version, or convert deep recursion to an explicit stack. Languages with tail-call optimization could keep recursion, but the JVM does not optimize tail calls.

### 🔴 Expert

**Q: How do linked lists appear in production systems at scale?**
LinkedHashMap and Java's LRU/access-ordered maps; the intrusive doubly linked free lists in memory allocators (jemalloc/tcmalloc); the merge step in external merge sort and LSM-tree compaction (merge-k-lists); ring buffers in lock-free queues; adjacency lists in graph engines; and the chain in separate-chaining hash tables.

**Q: How would you make an LRU cache concurrent without a global lock?**
Options: shard the cache into N independent LRU segments keyed by `hash(key) % N` (like `ConcurrentHashMap`'s historic segments) to reduce contention; or approximate LRU with a CLOCK / second-chance algorithm and atomic reference bits, avoiding the strict ordering that forces serialization on every read. Caffeine uses TinyLFU with ring buffers and batched replay to keep reads lock-free.

**Q: Why are linked lists cache-unfriendly, and when does that dominate Big-O?**
Nodes are scattered across the heap, so each pointer hop is a potential cache miss; an O(n) array scan can be 10–50x faster than an O(n) list scan despite identical asymptotics. For large, traversal-heavy workloads, prefer arrays or array-backed deques (`ArrayDeque`) over `LinkedList`.

**Q: Can you sort a linked list in O(n log n) with O(1) extra space?**
Yes — bottom-up (iterative) merge sort on the list: repeatedly merge sublists of size 1, 2, 4, … using the dummy-head merge routine. It avoids the recursion stack of top-down merge sort and the random access that disqualifies quicksort.

---

## ⚠️ Common Pitfalls

- **Losing the rest of the list.** Always save `curr.next` *before* you overwrite `curr.next`.
- **Forgetting to null-terminate.** After reversing or splitting, the new tail's `next` must be set to `null`, or you create an accidental cycle.
- **Off-by-one with the runner.** For "Nth from end," open the gap with exactly `n` advances *on a dummy head*; advancing on `head` directly breaks when removing the first node.
- **Comparing values instead of references** in intersection problems — use `==` on nodes, not `.val`.
- **Even/odd middle confusion.** Decide up front whether you need the first or second middle and pick the matching loop condition.
- **Heap comparator on null** in merge-k — never offer `null` heads into the `PriorityQueue`.
- **LRU: updating the map but not the DLL** (or vice versa) — keep both in lockstep; on eviction remove from *both*.
- **Recursion depth** — deep recursive reversal/merge overflows the stack on long lists; prefer iterative.
- **Mutating during traversal** without holding a stable reference to where you'll resume.

---

## 📚 Further Reading

- *Introduction to Algorithms* (CLRS), Ch. 10 — list ADTs and sentinels.
- *Cracking the Coding Interview*, Ch. 2 — linked-list problem patterns.
- LeetCode tag **Linked List**: 206 (Reverse), 141/142 (Cycle), 876 (Middle), 21/23 (Merge), 19 (Remove Nth), 143 (Reorder), 138 (Copy Random), 160 (Intersection), 146/460 (LRU/LFU), 25 (Reverse k-group).
- Floyd, R. — cycle detection ("tortoise and hare").
- Caffeine cache (Ben Manes) — TinyLFU design notes for modern eviction at scale.
- Java docs: `LinkedList`, `LinkedHashMap` (`accessOrder`, `removeEldestEntry`), `ArrayDeque`, `PriorityQueue`.

---

[← Back to master index](../README.md) &nbsp;|&nbsp; [← DSA index](README.md)
