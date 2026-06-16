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

## 🧩 Extended Problems — Set 1: Classic Easy → Medium

### Problem 12: Palindrome Linked List — Slow/Fast + In-Place Reverse

**Statement.** Given the `head` of a singly linked list, return `true` if it reads the same forward and backward.

**Constraints.** `1 <= n <= 10^5`, `0 <= Node.val <= 9`. Aim for O(n) time and O(1) extra space.

**Approach.** The brute-force copies values into an `ArrayList` and uses two indices — O(n) space. The optimal O(1)-space method composes three list skills: find the middle with slow/fast, reverse the second half in place, then walk the first half and the reversed second half in lockstep comparing values. It is optimal because it touches each node a constant number of times and reuses the existing nodes rather than allocating. A clean version restores the list afterward (re-reversing the second half) so the input is left unmodified.

```
1 -> 2 -> 3 -> 2 -> 1
          ^slow stops here (first middle)
second half reversed: 1 -> 2
compare 1==1, 2==2  -> palindrome
```

```java
public class PalindromeList {
    static class ListNode { int val; ListNode next; ListNode(int v){val=v;} }

    public boolean isPalindrome(ListNode head) {
        if (head == null || head.next == null) return true;

        // 1. find the first middle (left half ends at slow)
        ListNode slow = head, fast = head;
        while (fast.next != null && fast.next.next != null) {
            slow = slow.next;
            fast = fast.next.next;
        }

        // 2. reverse the second half (everything after slow)
        ListNode second = reverse(slow.next);
        slow.next = null; // optional cut

        // 3. compare the two halves
        ListNode p1 = head, p2 = second;
        boolean ok = true;
        while (ok && p2 != null) {
            if (p1.val != p2.val) ok = false;
            p1 = p1.next;
            p2 = p2.next;
        }

        // 4. restore the list (re-reverse and reconnect)
        slow.next = reverse(second);
        return ok;
    }

    private ListNode reverse(ListNode head) {
        ListNode prev = null;
        while (head != null) {
            ListNode nx = head.next;
            head.next = prev;
            prev = head;
            head = nx;
        }
        return prev;
    }
}
```

**Complexity** — Time O(n), Space O(1). **Edge cases:** empty list and single node are trivially palindromes; odd length leaves the middle node unpaired (the shorter reversed half drives the loop, so it is skipped correctly); even length compares all nodes.

---

### Problem 13: Remove Duplicates from a Sorted List — One-Pass Pointer Skip

**Statement.** Given the head of a **sorted** singly linked list, delete all nodes that have duplicate values so each value appears once. Return the modified head.

**Constraints.** `0 <= n <= 300`, list is sorted ascending.

**Approach.** Because the list is sorted, duplicates are adjacent, so a single forward pass suffices. Keep a `cur` pointer; whenever `cur.val == cur.next.val`, splice out `cur.next` by setting `cur.next = cur.next.next`; otherwise advance `cur`. No dummy head is needed since the head value is always kept (we only ever delete the *second* of a duplicate pair). This is optimal: every node is visited once and no extra storage is used.

```java
public ListNode deleteDuplicates(ListNode head) {
    ListNode cur = head;
    while (cur != null && cur.next != null) {
        if (cur.val == cur.next.val) {
            cur.next = cur.next.next; // drop the duplicate, stay put
        } else {
            cur = cur.next;           // values differ, move on
        }
    }
    return head;
}
```

**Dry run** `1->1->2->3->3`: drop second 1 → `1->2->3->3`; advance to 2, advance to 3, drop second 3 → `1->2->3`.

**Complexity** — Time O(n), Space O(1). **Edge cases:** empty list returns `null`; all-equal list collapses to one node; do not advance `cur` after a deletion or you skip a possible triple duplicate.

---

### Problem 14: Remove Duplicates from a Sorted List II — Dummy Head + Lookahead

**Statement.** Given a **sorted** linked list, delete *all* nodes that have duplicate numbers, leaving only the distinct values. (`1->2->3->3->4->4->5` becomes `1->2->5`.)

**Constraints.** `0 <= n <= 300`, sorted ascending.

**Approach.** Unlike Problem 13, here a value with any duplicate is removed entirely, including the first occurrence — and that occurrence might be the head. A **dummy head** removes the special case. Keep `prev` pointing at the last confirmed-unique node. When `cur` starts a run of equal values, skip the whole run, then link `prev.next` past it; otherwise advance `prev`. Optimal at O(n) one pass.

```
dummy -> 1 -> 2 -> 3 -> 3 -> 4 -> 4 -> 5
prev^   cur^
... at the 3-run: skip both 3s, prev.next jumps to first 4
... at the 4-run: skip both 4s, prev.next jumps to 5
result: 1 -> 2 -> 5
```

```java
public ListNode deleteDuplicatesII(ListNode head) {
    ListNode dummy = new ListNode(0);
    dummy.next = head;
    ListNode prev = dummy, cur = head;
    while (cur != null) {
        if (cur.next != null && cur.val == cur.next.val) {
            int dup = cur.val;
            while (cur != null && cur.val == dup) cur = cur.next; // skip run
            prev.next = cur;       // bridge over the whole duplicate run
        } else {
            prev = cur;            // unique value, keep it
            cur = cur.next;
        }
    }
    return dummy.next;
}
```

**Complexity** — Time O(n), Space O(1). **Edge cases:** head itself duplicated (dummy handles it); a single trailing duplicate pair; empty list returns `null`.

---

### Problem 15: Linked List Cycle Length — Floyd Meeting Point + Count

**Statement.** Given a linked list that may contain a cycle, return the **length of the cycle** (number of nodes in the loop), or `0` if there is no cycle.

**Constraints.** `0 <= n <= 10^4`; cycle expressed via an internal back-pointer.

**Approach.** Use Floyd's tortoise & hare to obtain a meeting point inside the cycle (Problem 3). Once `slow == fast`, freeze one pointer and walk the other around the loop until it returns to the meeting node, counting steps — that count is exactly the cycle length. O(1) space; no hashing required.

```java
public int cycleLength(ListNode head) {
    ListNode slow = head, fast = head;
    while (fast != null && fast.next != null) {
        slow = slow.next;
        fast = fast.next.next;
        if (slow == fast) {                 // meeting point inside the loop
            int len = 1;
            ListNode p = slow.next;
            while (p != slow) {             // walk once around
                p = p.next;
                len++;
            }
            return len;
        }
    }
    return 0; // fast hit null → no cycle
}
```

**Complexity** — Time O(n) (O(n) to meet, O(L) to count, L ≤ n), Space O(1). **Edge cases:** no cycle returns 0; a self-loop (`node.next == node`) returns length 1; empty list returns 0.

---

### Problem 16: Odd-Even Linked List — Two-Chain Splice

**Statement.** Group all nodes at **odd positions** (1-indexed) together followed by the nodes at even positions, preserving relative order within each group, in place. (`1->2->3->4->5` becomes `1->3->5->2->4`.)

**Constraints.** `0 <= n <= 10^4`. O(1) space; reorder by pointers, not values.

**Approach.** Maintain two running tails: `odd` for the odd-indexed chain and `even` for the even-indexed chain, with `evenHead` remembering where the even chain starts. Walk the list weaving nodes alternately onto the two chains, then stitch the even head onto the end of the odd chain. Each node is moved once — optimal O(n)/O(1).

```
1 -> 2 -> 3 -> 4 -> 5
odd chain:  1 -> 3 -> 5
even chain: 2 -> 4
splice:     1 -> 3 -> 5 -> 2 -> 4
```

```java
public ListNode oddEvenList(ListNode head) {
    if (head == null || head.next == null) return head;
    ListNode odd = head, even = head.next, evenHead = even;
    while (even != null && even.next != null) {
        odd.next = even.next;   // next odd is the node after current even
        odd = odd.next;
        even.next = odd.next;   // next even is the node after the new odd
        even = even.next;
    }
    odd.next = evenHead;        // attach even chain after odd chain
    return head;
}
```

**Complexity** — Time O(n), Space O(1). **Edge cases:** lists of length 0/1/2 return unchanged; the loop condition `even != null && even.next != null` guards both the even tail and its successor.

---

### Problem 17: Add Two Numbers — Digit-by-Digit with Carry

**Statement.** Two non-negative integers are stored as linked lists in **reverse** order (ones digit first), one digit per node. Add them and return the sum as a linked list in the same form.

**Constraints.** `1 <= n, m <= 100`, `0 <= Node.val <= 9`, no leading zeros except the number 0 itself.

**Approach.** Reverse order is a gift: the heads are the least-significant digits, so you add left to right exactly like grade-school addition, propagating a `carry`. Use a dummy head to build the result. Continue while either list has digits or a carry remains. Optimal single pass over the longer input.

```
  2 -> 4 -> 3      (342)
+ 5 -> 6 -> 4      (465)
-----------------
  7 -> 0 -> 8      (807)
```

```java
public ListNode addTwoNumbers(ListNode l1, ListNode l2) {
    ListNode dummy = new ListNode(0), tail = dummy;
    int carry = 0;
    while (l1 != null || l2 != null || carry != 0) {
        int sum = carry;
        if (l1 != null) { sum += l1.val; l1 = l1.next; }
        if (l2 != null) { sum += l2.val; l2 = l2.next; }
        carry = sum / 10;
        tail.next = new ListNode(sum % 10);
        tail = tail.next;
    }
    return dummy.next;
}
```

**Complexity** — Time O(max(n, m)), Space O(max(n, m)) for the result. **Edge cases:** final carry creates a new most-significant node (`5->5` + `5` = `0->1`); unequal lengths; one input equal to 0.

**Follow-up (LeetCode 445):** if digits are stored in *forward* order, either reverse both lists first or push onto two stacks and pop while adding.

---

### Problem 18: Swap Nodes in Pairs — Dummy Head Pointer Rewire

**Statement.** Given a linked list, swap every two adjacent nodes and return the head. You must swap the **nodes**, not just the values. (`1->2->3->4` becomes `2->1->4->3`.)

**Constraints.** `0 <= n <= 100`. O(1) space.

**Approach.** A dummy head lets the first pair be swapped without special-casing. Keep `prev` before the pair; for each pair `(first, second)`, rewire `prev->second->first->rest` and advance `prev` to `first`. Pointer surgery only, so it is optimal in time and space; the recursive variant is elegant but costs O(n) stack.

```
prev -> 1 -> 2 -> 3 -> 4
becomes
prev -> 2 -> 1 -> 3 -> 4   (then prev jumps to node 1, repeat)
```

```java
public ListNode swapPairs(ListNode head) {
    ListNode dummy = new ListNode(0);
    dummy.next = head;
    ListNode prev = dummy;
    while (prev.next != null && prev.next.next != null) {
        ListNode first = prev.next, second = first.next;
        first.next = second.next;  // first now points past the pair
        second.next = first;       // second leads the pair
        prev.next = second;        // hook the pair onto prev
        prev = first;              // first is the tail of the swapped pair
    }
    return dummy.next;
}
```

**Complexity** — Time O(n), Space O(1). **Edge cases:** odd length leaves the last lone node untouched; empty/single-node list returns unchanged.

---

### Problem 19: Rotate List — Close-the-Ring, Cut at Offset

**Statement.** Given the head of a list, rotate it to the right by `k` places. (`1->2->3->4->5`, k=2 becomes `4->5->1->2->3`.)

**Constraints.** `0 <= n <= 500`, `0 <= k <= 2*10^9` (so `k` can far exceed the length).

**Approach.** First compute the length `len` and the tail. Since rotating by `len` is a no-op, reduce `k %= len`. Temporarily close the list into a ring (`tail.next = head`), then the new tail sits `len - k - 1` steps from the old head; cut there. Closing the ring makes the new boundaries a simple walk. Optimal at two passes, O(1) space.

```
1->2->3->4->5  (len=5), k=2  →  k%5=2
new tail at index len-k-1 = 2  (node 3)
new head = node 4   →   4->5->1->2->3
```

```java
public ListNode rotateRight(ListNode head, int k) {
    if (head == null || head.next == null || k == 0) return head;

    // 1. length + tail
    int len = 1;
    ListNode tail = head;
    while (tail.next != null) { tail = tail.next; len++; }

    k %= len;
    if (k == 0) return head;       // full rotation → unchanged

    // 2. close into a ring, then find the new tail
    tail.next = head;
    ListNode newTail = head;
    for (int i = 0; i < len - k - 1; i++) newTail = newTail.next;

    // 3. cut the ring
    ListNode newHead = newTail.next;
    newTail.next = null;
    return newHead;
}
```

**Complexity** — Time O(n), Space O(1). **Edge cases:** `k` a multiple of `len` returns the original; empty/single node returns unchanged; `k` larger than `len` handled by the modulo.

---

### Problem 20: Partition List — Two Buckets Around a Pivot

**Statement.** Given a list and a value `x`, reorder it so all nodes with value `< x` come before all nodes `>= x`, preserving the original relative order within each group. (`1->4->3->2->5->2`, x=3 becomes `1->2->2->4->3->5`.)

**Constraints.** `0 <= n <= 200`, `-100 <= Node.val, x <= 100`. Stable partition.

**Approach.** Build two separate chains with dummy heads — `less` for values below `x`, `greater` for the rest — appending each node to the appropriate tail to preserve order. Finally concatenate `less` then `greater`, and null-terminate the combined tail to avoid an accidental cycle. Stable, single pass, O(1) extra space.

```java
public ListNode partition(ListNode head, int x) {
    ListNode lessDummy = new ListNode(0), lessTail = lessDummy;
    ListNode geDummy   = new ListNode(0), geTail   = geDummy;
    for (ListNode cur = head; cur != null; cur = cur.next) {
        if (cur.val < x) { lessTail.next = cur; lessTail = cur; }
        else             { geTail.next   = cur; geTail   = cur; }
    }
    geTail.next = null;            // terminate the >= chain (critical!)
    lessTail.next = geDummy.next;  // stitch less -> greater
    return lessDummy.next;
}
```

**Complexity** — Time O(n), Space O(1). **Edge cases:** all nodes on one side (the other dummy stays empty and `geDummy.next` may be `null`); forgetting `geTail.next = null` reuses an old `next` and creates a cycle.

---

### Problem 21: Delete Node Given Only That Node — Copy-Forward Trick

**Statement.** Write a function to delete a node (not necessarily the tail) from a singly linked list, given **only access to that node** — you are not given the head.

**Constraints.** The node to delete is guaranteed not to be the tail and is in the list of size `>= 2`.

**Approach.** Without the predecessor you cannot relink the previous node's `next`. The trick: copy the *next* node's value into the current node, then bypass the next node. Effectively you delete the successor while making the current node impersonate it. This is the only O(1) deletion possible for a singly list when the head is unavailable, and it is why the constraint excludes the tail.

```
before:  ... -> [5] -> [1] -> [9] -> ...   (delete the [5] node)
copy 1 into current, then skip the original [1] node:
after:   ... -> [1] -> [9] -> ...
```

```java
public void deleteNode(ListNode node) {
    node.val = node.next.val;   // impersonate the successor
    node.next = node.next.next; // unlink the successor
}
```

**Complexity** — Time O(1), Space O(1). **Edge cases:** undefined for the tail (no successor to copy); the problem guarantees this never happens. The node's original value is overwritten, which is acceptable since that node is conceptually removed.

---

### Problem 22: Middle of the List — Delete the Middle Node

**Statement.** Given the head of a list, delete the **middle** node and return the head. For even length there are two middles; delete the second one. (`1->3->4->7->1->2->6` deletes `7`.)

**Constraints.** `1 <= n <= 10^5`.

**Approach.** Combine slow/fast traversal with a `prev` pointer trailing `slow`. When `fast` reaches the end, `slow` is at the node to delete and `prev` is just before it, so `prev.next = slow.next` unlinks it. A dummy head handles the single-node case (deleting the only node yields an empty list). One pass, O(1) space.

```java
public ListNode deleteMiddle(ListNode head) {
    if (head == null || head.next == null) return null; // single node → empty
    ListNode dummy = new ListNode(0);
    dummy.next = head;
    ListNode slow = head, fast = head, prev = dummy;
    while (fast != null && fast.next != null) {
        prev = slow;
        slow = slow.next;
        fast = fast.next.next;
    }
    prev.next = slow.next; // unlink the middle
    return dummy.next;
}
```

**Dry run** `1->2->3->4`: slow walks 1→2, prev=1; fast 1→3→null; delete node 3 (second middle) → `1->2->4`.

**Complexity** — Time O(n), Space O(1). **Edge cases:** single node returns `null`; two-node list deletes the second; the trailing `prev` keeps the predecessor without a second pass.

---

### Problem 23: Convert Sorted List to Balanced BST — Inorder Simulation

**Statement.** Given a singly linked list sorted ascending, build a **height-balanced** binary search tree from its elements and return the root.

**Constraints.** `0 <= n <= 2*10^4`, values sorted. Resulting tree height must differ by at most 1 across subtrees.

**Approach.** A balanced BST's inorder traversal is exactly the sorted sequence. Rather than repeatedly finding the middle (O(n log n)), do an **inorder simulation**: first count `n`, then recursively build the left subtree, consume the current list node as the root, and build the right subtree — advancing a shared list pointer in inorder order. The middle of each range naturally becomes a subtree root, giving balance in O(n).

```
sorted list: -10 -3 0 5 9
                       0
                     /   \
                  -3      9
                  /      /
               -10      5
```

```java
class TreeNode { int val; TreeNode left, right; TreeNode(int v){val=v;} }

public class SortedListToBST {
    private ListNode cur; // shared pointer advanced in inorder order

    public TreeNode sortedListToBST(ListNode head) {
        int n = 0;
        for (ListNode p = head; p != null; p = p.next) n++;
        cur = head;
        return build(0, n - 1);
    }

    private TreeNode build(int lo, int hi) {
        if (lo > hi) return null;
        int mid = lo + (hi - lo) / 2;
        TreeNode left = build(lo, mid - 1);   // left subtree first
        TreeNode root = new TreeNode(cur.val); // consume current node
        cur = cur.next;
        root.left = left;
        root.right = build(mid + 1, hi);       // then right subtree
        return root;
    }
}
```

**Why optimal:** each list node is consumed exactly once and the index bisection guarantees balanced heights, so it beats the O(n log n) repeated-middle approach.

**Complexity** — Time O(n), Space O(log n) recursion stack. **Edge cases:** empty list returns `null`; single node becomes a leaf root; the inorder ordering is what keeps `cur` synchronized with the index range.

---

### Problem 24: Reverse Nodes Between Positions m and n — One-Pass Sublist Reverse

**Statement.** Reverse the nodes of a list from position `left` to position `right` (1-indexed) in a single pass and return the head. (`1->2->3->4->5`, left=2, right=4 becomes `1->4->3->2->5`.)

**Constraints.** `1 <= left <= right <= n <= 500`. One pass, O(1) space.

**Approach.** Use a dummy head so reversing from position 1 is not special. Walk `prev` to the node just before `left`. Then apply the **head-insertion** technique: repeatedly take the node after the current sublist start and splice it to the front of the reversed region. After `right - left` insertions the sublist is reversed and its boundaries are correctly reconnected — all in one pass.

```
dummy -> 1 -> 2 -> 3 -> 4 -> 5     left=2, right=4
prev^         (prev = node 1)
move 3 to front of sublist: 1 -> 3 -> 2 -> 4 -> 5
move 4 to front of sublist: 1 -> 4 -> 3 -> 2 -> 5
```

```java
public ListNode reverseBetween(ListNode head, int left, int right) {
    ListNode dummy = new ListNode(0);
    dummy.next = head;
    ListNode prev = dummy;
    for (int i = 0; i < left - 1; i++) prev = prev.next; // node before sublist

    ListNode start = prev.next;       // first node of the sublist (stays, moves right)
    ListNode then = start.next;       // node to be moved to the front
    for (int i = 0; i < right - left; i++) {
        start.next = then.next;       // detach 'then'
        then.next = prev.next;        // 'then' jumps to the front of the sublist
        prev.next = then;             // hook it under prev
        then = start.next;            // next node to move
    }
    return dummy.next;
}
```

**Complexity** — Time O(n), Space O(1). **Edge cases:** `left == right` reverses nothing (zero iterations); `left == 1` handled by the dummy; reversing the entire list when `left=1, right=n`.

---

## 🧩 Extended Problems — Set 2: Medium → Hard Variations & Follow-ups

### Problem 25: Reverse Nodes in k-Group — Block-wise In-Place Reversal

**Statement.** Given the `head` of a linked list, reverse the nodes `k` at a time and return the modified list. Nodes in a final group of fewer than `k` are left as-is. (`1->2->3->4->5`, k=2 becomes `2->1->4->3->5`; k=3 becomes `3->2->1->4->5`.)

**Constraints.** `1 <= k <= n <= 5000`, `0 <= Node.val <= 1000`. Only pointer changes are allowed (no value swaps). Target O(1) extra space.

**Approach.** The brute force pushes `k` nodes onto a stack and pops them to rebuild — O(k) space per group. The optimal approach reverses each block in place. Walk a `groupPrev` pointer that sits just before the block to reverse. First confirm a full group of `k` nodes exists (otherwise stop). Then reverse the block with the standard three-pointer flip, but bound it to exactly `k` nodes by reversing *into* the node just after the block. Finally re-stitch `groupPrev` to the new block head and advance `groupPrev` to the old block head (now the block tail). It is optimal because each node's `next` is rewritten exactly once.

```
k = 3
groupPrev               kth
   |                     |
dummy -> 1 -> 2 -> 3 ->  4 -> 5
reverse [1,2,3] in place, attach to dummy and to 4:
dummy -> 3 -> 2 -> 1 -> 4 -> 5
            groupPrev now at node 1 (block tail), repeat
```

```java
public class ReverseKGroup {
    static class ListNode { int val; ListNode next; ListNode(int v){val=v;} }

    public ListNode reverseKGroup(ListNode head, int k) {
        ListNode dummy = new ListNode(0);
        dummy.next = head;
        ListNode groupPrev = dummy;

        while (true) {
            // 1. find the k-th node from groupPrev; bail if fewer than k remain
            ListNode kth = groupPrev;
            for (int i = 0; i < k && kth != null; i++) kth = kth.next;
            if (kth == null) break;          // not enough nodes → leave as-is

            ListNode groupNext = kth.next;    // first node of the next group
            // 2. reverse the block [groupPrev.next .. kth] into groupNext
            ListNode prev = groupNext, curr = groupPrev.next;
            while (curr != groupNext) {
                ListNode nx = curr.next;
                curr.next = prev;
                prev = curr;
                curr = nx;
            }
            // 3. re-stitch: groupPrev -> kth(new head); old head is new tail
            ListNode oldHead = groupPrev.next;
            groupPrev.next = kth;
            groupPrev = oldHead;
        }
        return dummy.next;
    }
}
```

**Complexity** — Time O(n) (each node touched a constant number of times), Space O(1). **Edge cases:** `k == 1` returns the list unchanged; a trailing partial group is preserved; `k == n` reverses the whole list; empty list returns `null`.

---

### Problem 26: Sort a Linked List — Bottom-Up Merge Sort (O(1) space)

**Statement.** Sort a singly linked list in ascending order and return the new head. Target O(n log n) time and, for the senior variant, O(1) extra space.

**Constraints.** `0 <= n <= 5*10^4`, `-10^5 <= Node.val <= 10^5`.

**Approach.** Quicksort is poor on lists (no random access for partitioning, worst-case O(n²)), and top-down merge sort costs O(log n) recursion stack. The optimal **bottom-up merge sort** avoids recursion entirely: treat the list as runs of size 1, then iteratively merge adjacent runs of size 1, 2, 4, … doubling each pass. Each pass splits the list into pairs of `size`-length runs, merges them with the dummy-head merge routine, and stitches results back into one list. After `ceil(log2 n)` passes the list is sorted. Space is O(1) because we only manipulate pointers.

```
size=1:  (3)(1)(4)(2)  -> 1 3 | 2 4
size=2:  (1 3)(2 4)    -> 1 2 3 4
```

```java
public ListNode sortList(ListNode head) {
    if (head == null || head.next == null) return head;

    int n = 0;
    for (ListNode p = head; p != null; p = p.next) n++;

    ListNode dummy = new ListNode(0);
    dummy.next = head;

    for (int size = 1; size < n; size *= 2) {
        ListNode prev = dummy, curr = dummy.next;
        while (curr != null) {
            ListNode left = curr;
            ListNode right = split(left, size);   // cut left run, return next run head
            curr = split(right, size);            // cut right run, return rest
            prev = merge(left, right, prev);      // merge, return new tail
        }
    }
    return dummy.next;
}

// Walk `size` nodes from head, sever there, return the head of the remainder.
private ListNode split(ListNode head, int size) {
    if (head == null) return null;
    for (int i = 1; head.next != null && i < size; i++) head = head.next;
    ListNode rest = head.next;
    head.next = null;
    return rest;
}

// Merge two sorted runs onto `prev`, return the tail of the merged run.
private ListNode merge(ListNode a, ListNode b, ListNode prev) {
    ListNode curr = prev;
    while (a != null && b != null) {
        if (a.val <= b.val) { curr.next = a; a = a.next; }
        else                { curr.next = b; b = b.next; }
        curr = curr.next;
    }
    curr.next = (a != null) ? a : b;
    while (curr.next != null) curr = curr.next; // advance to the merged tail
    return curr;
}
```

**Complexity** — Time O(n log n), Space O(1) (true constant; no recursion). **Edge cases:** empty/single node returns unchanged; duplicate values stay stable because `<=` keeps `a` before `b`; null-termination inside `split` prevents accidental cycles.

---

### Problem 27: Add Two Numbers II — Forward-Order Digits via Stacks

**Statement.** Two non-negative integers are stored in linked lists where the **most significant digit comes first**. Add them and return the sum as a list in the same forward order. You may not modify the inputs (no reversing them in place).

**Constraints.** `1 <= n, m <= 100`, `0 <= Node.val <= 9`, no leading zeros except the value 0.

**Approach.** Forward order means the digits we must add first (least significant) are at the *tails*. Reversing both inputs would work but mutates them. The clean non-mutating approach pushes each list onto its own stack, then pops both stacks together so the least-significant digits meet first, propagating a carry. Crucially, build the result by **prepending** each new digit node to the front, so the final list ends up in forward order without a second reversal.

```
  7 -> 2 -> 4 -> 3     (7243)
+      5 -> 6 -> 4     ( 564)
stacks pop LSB-first; prepend each digit:
  7 -> 8 -> 0 -> 7     (7807)
```

```java
import java.util.Deque;
import java.util.ArrayDeque;

public ListNode addTwoNumbersII(ListNode l1, ListNode l2) {
    Deque<Integer> s1 = new ArrayDeque<>(), s2 = new ArrayDeque<>();
    for (ListNode p = l1; p != null; p = p.next) s1.push(p.val);
    for (ListNode p = l2; p != null; p = p.next) s2.push(p.val);

    ListNode head = null;   // we prepend, so head grows backwards
    int carry = 0;
    while (!s1.isEmpty() || !s2.isEmpty() || carry != 0) {
        int sum = carry;
        if (!s1.isEmpty()) sum += s1.pop();
        if (!s2.isEmpty()) sum += s2.pop();
        carry = sum / 10;
        ListNode node = new ListNode(sum % 10);
        node.next = head;   // prepend to keep most-significant at the front
        head = node;
    }
    return head;
}
```

**Complexity** — Time O(n + m), Space O(n + m) for the two stacks and the result. **Edge cases:** a final carry adds a new most-significant node (`9->9` + `1` = `1->0->0`); unequal lengths; either input being just `0`.

---

### Problem 28: Flatten a Multilevel Doubly Linked List — DFS with Child Splice

**Statement.** A doubly linked list has an extra `child` pointer that may point to a separate doubly linked list, which may itself have children, forming a multilevel structure. Flatten it into a single-level doubly linked list (depth-first), clearing every `child` pointer.

**Constraints.** `0 <= n <= 1000` total nodes across all levels.

**Approach.** Each child list must be spliced in *immediately after its parent* and before the parent's original `next` (preorder DFS). The iterative O(1)-extra-space method walks the list; whenever it meets a node with a child, it (1) saves `cur.next`, (2) links `cur.next` to the child and the child's `prev` back to `cur`, (3) nulls the `child` pointer, and (4) finds the tail of the just-spliced child chain and reconnects it to the saved `next`, fixing `prev` links. Because we then continue from `cur`, deeper children get flattened naturally when the walk reaches them.

```
1 - 2 - 3 - 4
        |
        7 - 8 - 9
            |
            11 - 12
flattened (DFS):
1 - 2 - 3 - 7 - 8 - 11 - 12 - 9 - 4
```

```java
class Node {
    int val;
    Node prev, next, child;
    Node(int v){ val = v; }
}

public Node flatten(Node head) {
    Node cur = head;
    while (cur != null) {
        if (cur.child != null) {
            Node next = cur.next;        // save original successor
            Node child = cur.child;

            cur.next = child;            // splice child in
            child.prev = cur;
            cur.child = null;            // clear child pointer

            Node tail = child;           // find the tail of the child chain
            while (tail.next != null) tail = tail.next;

            tail.next = next;            // reconnect to the saved successor
            if (next != null) next.prev = tail;
        }
        cur = cur.next;
    }
    return head;
}
```

**Complexity** — Time O(n) (each node visited once by the outer walk; tails found once per child), Space O(1). **Edge cases:** empty list returns `null`; a child at the tail (`next == null`) needs the null check before setting `next.prev`; deeply nested children flatten correctly because the walk descends into spliced chains.

---

### Problem 29: Reverse a Sublist Plus a Tail-Twist — Reverse Then Re-Reverse Comparison

**Statement.** Reverse the list nodes from position `left` to position `right`, **and** the nodes from `right+1` to the end, in a single function (i.e. split the list at `right` and reverse each part independently). Return the head. This generalizes the classic "reverse between" with a second segment, a common follow-up that tests boundary stitching.

**Constraints.** `1 <= left <= right <= n <= 1000`.

**Approach.** Build on the head-insertion reverse for the `[left, right]` window (Problem 24), but also expose the boundary nodes so the trailing segment `[right+1, n]` can be reversed and reattached. Walk `prev` to just before `left`, reverse the middle window by head-insertion, and capture the window's new tail (the original `left` node). The original `start.next` after the loop is the first node of the tail segment; reverse that segment iteratively and link it back. Two passes conceptually but one linear traversal of each region — O(1) space.

```
list: 1 2 3 4 5 6   left=2 right=4
mid [2..4] reversed: 1 4 3 2 | 5 6
tail [5..6] reversed:        | 6 5
result: 1 4 3 2 6 5
```

```java
public ListNode reverseSublistAndTail(ListNode head, int left, int right) {
    ListNode dummy = new ListNode(0);
    dummy.next = head;
    ListNode prev = dummy;
    for (int i = 0; i < left - 1; i++) prev = prev.next;

    // head-insertion reverse of [left, right]
    ListNode start = prev.next, then = start.next;
    for (int i = 0; i < right - left; i++) {
        start.next = then.next;
        then.next = prev.next;
        prev.next = then;
        then = start.next;
    }
    // now 'start' is the tail of the reversed window; start.next begins the tail segment
    ListNode tailHead = start.next;
    start.next = reverseAll(tailHead);  // reverse [right+1 .. n] and reattach
    return dummy.next;
}

private ListNode reverseAll(ListNode node) {
    ListNode prev = null;
    while (node != null) {
        ListNode nx = node.next;
        node.next = prev;
        prev = node;
        node = nx;
    }
    return prev;
}
```

**Complexity** — Time O(n), Space O(1). **Edge cases:** `right == n` makes the tail segment empty (`reverseAll(null)` returns `null`); `left == right` reverses no middle node but still reverses the tail; `left == 1` is handled by the dummy.

---

### Problem 30: Remove Zero-Sum Consecutive Nodes — Prefix Sum + HashMap

**Statement.** Given the head of a linked list, repeatedly delete consecutive sequences of nodes that sum to 0 until none remain, then return the head. (`1->2->-3->3->1` → `3->1`; `1->2->3->-3->4` → `1->2->4`.)

**Constraints.** `1 <= n <= 1000`, `-1000 <= Node.val <= 1000`.

**Approach.** The brute force repeatedly scans for a zero-sum window — O(n²) or worse with restarts. The optimal trick uses **prefix sums**: a zero-sum segment `(i, j]` exists exactly when the running prefix sum at `i` equals the prefix sum at `j`. Walk once recording, for each prefix value, the *last* node that produced it in a `HashMap<Integer, Node>`. If a prefix value repeats, every node strictly between the earlier occurrence and the current node sums to 0, so link the earlier node directly to `current.next`. A dummy head gives prefix sum 0 a real anchor so leading zero-sum runs are removed. Two passes over the list.

```
dummy(0) 1(1) 2(3) -3(0) 3(3) 1(4)
prefix 0 seen at dummy; prefix 0 seen again at node -3
=> remove nodes after dummy up to -3  → dummy -> 3 -> 1
prefix 3 seen at old node 2 (gone) and re-seen — second pass uses last occurrence
```

```java
import java.util.HashMap;
import java.util.Map;

public ListNode removeZeroSumSublists(ListNode head) {
    ListNode dummy = new ListNode(0);
    dummy.next = head;

    // Pass 1 records the LAST node for each prefix sum.
    Map<Integer, ListNode> last = new HashMap<>();
    int prefix = 0;
    for (ListNode cur = dummy; cur != null; cur = cur.next) {
        prefix += cur.val;
        last.put(prefix, cur);   // overwrite → keeps the furthest node
    }

    // Pass 2 jumps each node to the node after the last one sharing its prefix.
    prefix = 0;
    for (ListNode cur = dummy; cur != null; cur = cur.next) {
        prefix += cur.val;
        cur.next = last.get(prefix).next; // skip any zero-sum span between
    }
    return dummy.next;
}
```

**Complexity** — Time O(n), Space O(n) for the map. **Edge cases:** the whole list summing to 0 collapses to empty (`dummy.next` becomes `null`); nested/overlapping zero-sum spans are handled because pass 2 always uses the *last* node for each prefix; single-node lists with value 0 are removed.

---

### Problem 31: Split Linked List in k Parts — Size Bookkeeping

**Statement.** Given the head of a list and an integer `k`, split it into `k` consecutive parts. Parts should be as equal as possible: no two parts differ in size by more than one, and earlier parts are never smaller than later parts. Some parts may be empty (`null`). Return an array of the `k` part heads.

**Constraints.** `0 <= n <= 1000`, `1 <= k <= 50`.

**Approach.** First compute the length `n`. Each part gets `base = n / k` nodes, and the first `rem = n % k` parts get one extra node. Walk the list, and for each part cut after `base` (or `base + 1`) nodes by null-terminating that part's tail and remembering its head. This is optimal: a single counting pass plus a single cutting pass, O(k) extra space for the output array.

```
n=10, k=3 → base=3, rem=1
sizes: 4, 3, 3
[1 2 3 4] [5 6 7] [8 9 10]
```

```java
public ListNode[] splitListToParts(ListNode head, int k) {
    int n = 0;
    for (ListNode p = head; p != null; p = p.next) n++;

    int base = n / k, rem = n % k;
    ListNode[] parts = new ListNode[k];

    ListNode cur = head;
    for (int i = 0; i < k && cur != null; i++) {
        parts[i] = cur;
        int size = base + (i < rem ? 1 : 0);   // first 'rem' parts get one extra
        for (int j = 1; j < size; j++) cur = cur.next; // walk to this part's tail
        ListNode next = cur.next;
        cur.next = null;                         // sever
        cur = next;
    }
    return parts;
}
```

**Complexity** — Time O(n + k), Space O(k) for the result array (O(1) auxiliary). **Edge cases:** `k > n` leaves trailing entries as `null` (array default); `n == 0` returns `k` nulls; when `rem == 0` all non-empty parts are equal; the `size = base + ...` math guarantees the "earlier parts not smaller" rule.

---

### Problem 32: Insert Into a Sorted Circular Linked List — Boundary-Aware Splice

**Statement.** Given a pointer to a node in a **sorted circular** singly linked list (ascending, the tail links back to the head), insert a new value so the list stays sorted and remains circular. Return a pointer to any node in the list. The given node may be any node; the list may be empty (`null`).

**Constraints.** `0 <= n <= 5*10^4`, values may contain duplicates.

**Approach.** Because the list is circular and you start at an arbitrary node, you walk pairs `(cur, cur.next)` looking for the correct gap. Three insertion cases: (1) normal slot where `cur.val <= value <= cur.next.val`; (2) the "wrap" point where the list's max meets its min (`cur.val > cur.next.val`) and `value` is either `>= max` or `<= min` — insert at the boundary; (3) you looped all the way back to the start (all values equal, or value fits nowhere special) — insert anywhere. Tracking the start node lets you stop after one full loop, giving O(n) time and O(1) space.

```
sorted circular: 1 -> 3 -> 4 -> (back to 1)
insert 2:  find gap 1<=2<=3  → 1 -> 2 -> 3 -> 4 -> (1)
insert 5:  at wrap 4>1 and 5>=4 → ... 4 -> 5 -> 1 ...
insert 0:  at wrap 4>1 and 0<=1 → ... 4 -> 0 -> 1 ...
```

```java
class Node { int val; Node next; Node(int v){ val = v; } }

public Node insert(Node head, int insertVal) {
    Node node = new Node(insertVal);
    if (head == null) {            // empty list → single self-looping node
        node.next = node;
        return node;
    }
    Node cur = head;
    while (true) {
        if (cur.val <= insertVal && insertVal <= cur.next.val) {
            break;                                  // normal in-between slot
        }
        if (cur.val > cur.next.val) {               // wrap point (max -> min)
            if (insertVal >= cur.val || insertVal <= cur.next.val) break;
        }
        cur = cur.next;
        if (cur == head) break;                     // full loop: all equal, insert here
    }
    node.next = cur.next;
    cur.next = node;
    return head;
}
```

**Complexity** — Time O(n) worst case (one full traversal), Space O(1). **Edge cases:** empty list creates a self-pointing node; all values equal (loop terminates via the `cur == head` guard); inserting a new global min or max happens at the wrap point; single-node list links the new node both ways correctly.

---

### Problem 33: Plus One on a Linked List — Reverse or Right-most Non-Nine

**Statement.** A non-negative integer is represented as a linked list of digits in **forward** order (most significant first). Add one to it and return the resulting list. (`1->2->3` → `1->2->4`; `9->9` → `1->0->0`.)

**Constraints.** `1 <= n <= 100`, `0 <= Node.val <= 9`, no leading zeros except 0.

**Approach.** The carry propagates from the least-significant (tail) end, but the list runs head-first, so naïvely you'd need to go backward. Two clean strategies: (A) reverse the list, add one with carry, reverse back — O(n), O(1) space; (B) the slick **right-most non-nine** trick: find the last digit that is not 9, increment it, and set every digit after it to 0; if every digit is 9, prepend a new leading 1. Approach B avoids reversal and is shown; it works because adding one only affects the trailing run of 9s and the digit just before them.

```
1 -> 2 -> 9 -> 9
last non-9 is the '2'; increment it, zero the rest:
1 -> 3 -> 0 -> 0
all nines (9 -> 9) → prepend 1:  1 -> 0 -> 0
```

```java
public ListNode plusOne(ListNode head) {
    ListNode dummy = new ListNode(0); // guards the all-nines case
    dummy.next = head;
    ListNode lastNotNine = dummy;

    for (ListNode cur = head; cur != null; cur = cur.next) {
        if (cur.val != 9) lastNotNine = cur;
    }
    lastNotNine.val += 1;                       // increment the pivot digit
    for (ListNode cur = lastNotNine.next; cur != null; cur = cur.next) {
        cur.val = 0;                            // zero out the trailing 9-run
    }
    return (dummy.val == 1) ? dummy : dummy.next; // dummy became 1 only if all nines
}
```

**Complexity** — Time O(n), Space O(1). **Edge cases:** all nines prepends a new leading 1 (handled because the dummy is the `lastNotNine` and gets incremented from 0 to 1); single digit `9` → `1->0`; trailing zeros after the pivot are correctly reset.

---

### Problem 34: Next Greater Node in Linked List — Monotonic Stack

**Statement.** For each node in the list, find the value of the first node *after* it whose value is strictly greater; if none exists, use 0. Return these answers as an array indexed by node position. (`2->1->5` → `[5,5,0]`; `2->7->4->3->5` → `[7,0,5,5,0]`.)

**Constraints.** `1 <= n <= 10^4`, `1 <= Node.val <= 10^9`.

**Approach.** The brute force compares every node with all later nodes — O(n²). The optimal approach is the classic **monotonic decreasing stack**, the same as "next greater element" on arrays. First materialize the values into an `ArrayList` (one pass) so we can index by position. Walk left to right keeping a stack of *indices* whose answers are still pending. When the current value exceeds the value at the stack's top index, that index's next-greater is found — pop and fill it. Push the current index. Every index is pushed and popped once, giving linear time.

```
values:  2 7 4 3 5
i=0 push0                 stack[0]
i=1 v=7>2 pop0 ans[0]=7   push1   stack[1]
i=2 v=4<7 push2           stack[1,2]
i=3 v=3<4 push3           stack[1,2,3]
i=4 v=5>3 pop3 ans[3]=5; 5>4 pop2 ans[2]=5; 5<7 push4
end: stack[1,4] → ans[1]=0, ans[4]=0
result: [7,0,5,5,0]
```

```java
import java.util.ArrayList;
import java.util.List;
import java.util.Deque;
import java.util.ArrayDeque;

public int[] nextLargerNodes(ListNode head) {
    List<Integer> vals = new ArrayList<>();
    for (ListNode p = head; p != null; p = p.next) vals.add(p.val);

    int n = vals.size();
    int[] ans = new int[n];
    Deque<Integer> stack = new ArrayDeque<>(); // holds indices with pending answers
    for (int i = 0; i < n; i++) {
        while (!stack.isEmpty() && vals.get(stack.peek()) < vals.get(i)) {
            ans[stack.pop()] = vals.get(i);    // i is the next greater for that index
        }
        stack.push(i);
    }
    // indices left on the stack have no greater node → already 0 (array default)
    return ans;
}
```

**Complexity** — Time O(n), Space O(n) for the values list, the stack, and the answer array. **Edge cases:** strictly decreasing list yields all zeros; strictly increasing list pops one per step; single node returns `[0]`; equal values do not count (strict `<` keeps ties pending).

---

### Problem 35: Merge In Between Linked Lists — Splice a Sublist by Index

**Statement.** Given `list1`, two indices `a` and `b`, and `list2`, remove `list1`'s nodes from index `a` to `b` (inclusive) and splice `list2` into the gap. Return `list1`'s head. (`list1 = 0->1->2->3->4->5`, a=2, b=4, `list2 = 100->101` → `0->1->100->101->5`.)

**Constraints.** `3 <= n1 <= 10^4`, `0 < a <= b < n1 - 1`, `1 <= n2 <= 10^4`.

**Approach.** Pure index-walking pointer surgery. Walk one pointer to the node *before* `a` (`before`, at index `a-1`) and another to the node *after* `b` (`after`, at index `b+1`). Then link `before.next = list2`, walk `list2` to its tail, and link that tail to `after`. No allocation, single pass over the touched region; the bulk of the cost is locating the two boundaries and walking list2 once.

```
list1: 0 1 [2 3 4] 5     a=2 b=4
before = node1 (index a-1), after = node5 (index b+1)
node1 -> list2(100 101) -> node5
result: 0 1 100 101 5
```

```java
public ListNode mergeInBetween(ListNode list1, int a, int b, ListNode list2) {
    ListNode before = list1;
    for (int i = 0; i < a - 1; i++) before = before.next;   // stop at index a-1

    ListNode after = before;
    for (int i = a - 1; i <= b; i++) after = after.next;     // walk to index b+1

    before.next = list2;                 // attach list2 head
    ListNode tail = list2;
    while (tail.next != null) tail = tail.next; // find list2 tail
    tail.next = after;                   // attach the remainder of list1
    return list1;
}
```

**Complexity** — Time O(n1 + n2), Space O(1). **Edge cases:** `a == 1` keeps only the head before the splice (`before` is index 0); `b == n1 - 2` makes `after` the last node; the removed segment is simply orphaned (garbage-collected); constraints guarantee `before` and `after` are non-null.

---

### Problem 36: Swapping Nodes in a List — Two-Pointer Value or Node Swap

**Statement.** Given the head of a list and an integer `k` (1-indexed), swap the values of the `k`-th node from the beginning and the `k`-th node from the end, and return the head. (`1->2->3->4->5`, k=2 → `1->4->3->2->5`.)

**Constraints.** `1 <= k <= n <= 10^5`, `0 <= Node.val <= 100`.

**Approach.** The brute force computes the length, then two separate walks locate each node — two passes. The optimal **single pass with a fixed gap**: advance a `first` pointer `k-1` steps to reach the k-th-from-start node, then run a `second` pointer from the head while a runner continues from `first` to the end; when the runner hits the tail, `second` sits at the k-th-from-end node. Swap the two values. (Swapping values is acceptable here per the problem; a pointer swap is far more error-prone and unnecessary.) One traversal, O(1) space.

```
1 2 3 4 5   k=2
first lands on node2 (k-th from start)
runner goes from first to tail; second trails from head
runner reaches 5 when second = node4 (k-th from end)
swap values 2 and 4 → 1 4 3 2 5
```

```java
public ListNode swapNodes(ListNode head, int k) {
    ListNode first = head;
    for (int i = 1; i < k; i++) first = first.next; // k-th from start

    ListNode second = head, runner = first;
    while (runner.next != null) {                   // keep the gap, slide to the end
        runner = runner.next;
        second = second.next;
    }
    int tmp = first.val;                            // swap the two values
    first.val = second.val;
    second.val = tmp;
    return head;
}
```

**Complexity** — Time O(n) (single pass), Space O(1). **Edge cases:** `k` from both ends pointing at the same node (odd length, middle) swaps a value with itself — harmless; `k == 1` swaps head and tail; the gap technique avoids a second length-counting pass.

---

### Problem 37: Linked List in Binary Tree — Match a Downward Path

**Statement.** Given a linked list `head` and a binary tree `root`, return `true` if there is a **downward path** in the tree (parent to child, not necessarily root-to-leaf) whose node values, in order, equal the linked list. (Useful crossover problem testing list traversal against tree DFS.)

**Constraints.** Tree node count `1 <= N <= 2500`, list length `1 <= L <= 100`, values `1 <= val <= 100`.

**Approach.** The brute force checks the list against every possible starting tree node. The standard approach is a **double DFS**: an outer DFS visits every tree node as a candidate path start; an inner DFS tries to match the list downward from that node, advancing the list pointer only while values agree, branching into both children. The match succeeds when the list pointer runs off the end (`head == null`). Worst case is O(N·min(L, height)) but in practice prunes quickly because mismatches abort early.

```
list: 4 -> 2 -> 8
tree:        1
            / \
           4   ...
            \
             2
            / \
           1   8     match 4->2->8 down the right spine → true
```

```java
class TreeNode { int val; TreeNode left, right; TreeNode(int v){ val = v; } }

public boolean isSubPath(ListNode head, TreeNode root) {
    if (root == null) return false;
    // try to start the match at this node, else recurse into children
    return matchFrom(head, root)
        || isSubPath(head, root.left)
        || isSubPath(head, root.right);
}

// Does the list match a downward path starting exactly at 'node'?
private boolean matchFrom(ListNode head, TreeNode node) {
    if (head == null) return true;            // consumed the whole list → matched
    if (node == null) return false;           // ran out of tree before list ended
    if (node.val != head.val) return false;   // value mismatch
    return matchFrom(head.next, node.left)
        || matchFrom(head.next, node.right);
}
```

**Complexity** — Time O(N · min(L, H)) where H is tree height, Space O(H) recursion stack. **Edge cases:** single-node list matches any tree node sharing its value; the list longer than every downward path returns `false`; the outer DFS must restart the match at each node (a path can begin anywhere, not just the root).

---

## 🧩 Extended Problems — Set 3: Hard / Expert & Optimization

### Problem 38: Reverse k-Group, Then Reverse Leftover — Bidirectional Block Reversal

**Statement.** Given the `head` of a list and an integer `k`, reverse every full group of `k` nodes (like Problem 25), but for the **final group of fewer than `k` nodes, reverse it too** instead of leaving it as-is. (`1->2->3->4->5`, k=3 becomes `3->2->1->5->4`.)

**Constraints.** `1 <= k <= n <= 10^5`, `0 <= Node.val <= 10^9`. Pointer changes only, O(1) extra space.

**Approach.** This is the senior-bar twist on Reverse-Nodes-in-k-Group: the partial tail must also flip. The clean way is a single helper that reverses *up to* `k` nodes starting at a head and returns both the new head and the next segment's start, plus a flag for whether it reversed a full group. We always reverse the current block regardless of size, then recursively (or iteratively) stitch the reversed block's tail to the reversed remainder. Because the partial group is always the last one, reversing it never breaks an earlier full group. It is optimal: each `next` pointer is rewritten exactly once.

```
k = 3
1 2 3 | 4 5            full block [1 2 3], partial [4 5]
reverse full  -> 3 2 1
reverse partial -> 5 4
stitch        -> 3 2 1 5 4
```

```java
public class ReverseKGroupWithTail {
    static class ListNode { int val; ListNode next; ListNode(int v){val=v;} }

    public ListNode reverseKGroupAll(ListNode head, int k) {
        if (head == null) return null;
        // reverse the first up-to-k nodes
        ListNode prev = null, curr = head;
        int count = 0;
        while (curr != null && count < k) {
            ListNode nx = curr.next;
            curr.next = prev;
            prev = curr;
            curr = nx;
            count++;
        }
        // `head` is now this block's tail; recurse on the remainder
        head.next = reverseKGroupAll(curr, k);
        return prev; // new head of this block
    }
}
```

**Complexity** — Time O(n), Space O(n/k) recursion (convert to iteration with a saved tail pointer for true O(1)). **Edge cases:** `k == 1` returns the list unchanged; `k >= n` reverses the whole list once; the trailing partial group flips correctly because the recursion naturally reaches it last; empty list returns `null`.

---

### Problem 39: Merge k Sorted Lists — Divide & Conquer With Bound Analysis

**Statement.** Merge `k` sorted lists into one (Hard, LeetCode 23), but the senior framing asks for the **divide-and-conquer** solution and a defense of why it matches the heap's O(N log k) while using only O(log k) auxiliary space, plus correct handling of `null` slots in the input array.

**Constraints.** `0 <= k <= 10^4`, total nodes `N <= 10^4`. Some `lists[i]` may be `null`.

**Approach.** Pair up the `k` lists and merge each pair, halving the count every round; after `log2(k)` rounds a single list remains. Each round touches every node once across all merges, so total work is `N` per round times `log k` rounds = O(N log k). The recursion depth is O(log k), better than the heap's O(k) resident memory. The crux is the `mergeRange` recursion on index bounds so we never allocate a new array, and `mergeTwo` tolerates `null` operands.

```
lists: A B C D E   (k=5)
round1: merge(A,B) merge(C,D) E      -> AB CD E
round2: merge(AB,CD) E               -> ABCD E
round3: merge(ABCD,E)                -> ABCDE
```

```java
public ListNode mergeKLists(ListNode[] lists) {
    if (lists == null || lists.length == 0) return null;
    return mergeRange(lists, 0, lists.length - 1);
}

private ListNode mergeRange(ListNode[] lists, int lo, int hi) {
    if (lo == hi) return lists[lo];          // single list (may be null)
    int mid = lo + (hi - lo) / 2;
    ListNode left = mergeRange(lists, lo, mid);
    ListNode right = mergeRange(lists, mid + 1, hi);
    return mergeTwo(left, right);
}

private ListNode mergeTwo(ListNode a, ListNode b) {
    ListNode dummy = new ListNode(0), tail = dummy;
    while (a != null && b != null) {
        if (a.val <= b.val) { tail.next = a; a = a.next; }
        else                { tail.next = b; b = b.next; }
        tail = tail.next;
    }
    tail.next = (a != null) ? a : b;          // handles null operands too
    return dummy.next;
}
```

**Complexity** — Time O(N log k), Space O(log k) recursion. **Edge cases:** empty array returns `null`; all-`null` slots merge to `null`; a single non-null list returns itself; the `mergeTwo` null-tail attach covers any empty operand.

---

### Problem 40: Find the Duplicate Number — Array as a Virtual Linked List (Floyd)

**Statement.** Given an array `nums` of `n + 1` integers where each value is in `[1, n]`, exactly one value is duplicated (possibly many times). Find the duplicate **without modifying the array** and using **O(1) extra space**. (LeetCode 287.)

**Constraints.** `1 <= n <= 10^5`, you may not sort or use a `boolean[]` seen array (those break the space/immutability rules).

**Approach.** This is the famous reduction of a linked-list cycle problem onto an array. Treat each index `i` as a node whose `next` pointer is `nums[i]`; following `i -> nums[i]` forms a functional graph. Because values lie in `[1, n]` and there are `n+1` slots, two indices map to the same value — that collision is the entry of a cycle, and the entry node's index equals the duplicate value. Apply Floyd's tortoise & hare exactly as on a list: phase 1 finds a meeting point, phase 2 walks from the start to find the cycle entry, which is the duplicate.

```
nums = [1,3,4,2,2]   index -> nums[index]
0 -> 1 -> 3 -> 2 -> 4 -> 2 -> 4 ...   cycle entered at value 2
duplicate = 2
```

```java
public int findDuplicate(int[] nums) {
    int slow = nums[0], fast = nums[0];
    do {                                   // phase 1: find a meeting point
        slow = nums[slow];
        fast = nums[nums[fast]];
    } while (slow != fast);

    slow = nums[0];                        // phase 2: find cycle entry
    while (slow != fast) {
        slow = nums[slow];
        fast = nums[fast];
    }
    return slow;                           // entry index == duplicate value
}
```

**Complexity** — Time O(n), Space O(1). **Edge cases:** the duplicate appearing many times still yields one cycle entry; the array is never mutated; `do-while` is essential so the two pointers start apart before the equality test.

---

### Problem 41: LFU Cache — HashMap + Frequency Buckets of Doubly Linked Lists

**Statement.** Design `LFUCache(capacity)` with O(1) `get` and `put`. Evict the **least-frequently-used** key; break ties by least-recently-used among the minimum frequency. (LeetCode 460, Hard.)

**Constraints.** `1 <= capacity <= 10^4`, up to `2*10^5` operations.

**Approach.** Augment the LRU design with frequency tracking. Keep `keyToVal` and `keyToFreq` maps, plus `freqToList`: a map from a frequency to a doubly linked list (here a `LinkedHashSet`, which preserves insertion order so the oldest key at a frequency is first). Track `minFreq`. On access, move a key from its current frequency bucket to `freq+1`, updating `minFreq` if its old bucket emptied at `minFreq`. On insert past capacity, evict the first key in the `minFreq` bucket (least-frequent, and oldest among those). Every step is amortized O(1) because each operation touches a constant number of buckets.

```
buckets:  freq1 -> {A, B}      (A oldest)
          freq2 -> {C}
minFreq = 1
get(A): A moves to freq2 -> freq1 {B}, freq2 {C, A}
evict when full: pop first of minFreq bucket -> B
```

```java
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;

public class LFUCache {
    private final int capacity;
    private int minFreq = 0;
    private final Map<Integer, Integer> keyToVal = new HashMap<>();
    private final Map<Integer, Integer> keyToFreq = new HashMap<>();
    private final Map<Integer, LinkedHashSet<Integer>> freqToKeys = new HashMap<>();

    public LFUCache(int capacity) { this.capacity = capacity; }

    public int get(int key) {
        if (!keyToVal.containsKey(key)) return -1;
        touch(key);                       // bump its frequency
        return keyToVal.get(key);
    }

    public void put(int key, int value) {
        if (capacity == 0) return;
        if (keyToVal.containsKey(key)) {
            keyToVal.put(key, value);
            touch(key);
            return;
        }
        if (keyToVal.size() >= capacity) {            // evict LFU/LRU
            LinkedHashSet<Integer> minBucket = freqToKeys.get(minFreq);
            int evict = minBucket.iterator().next();  // oldest at min freq
            minBucket.remove(evict);
            keyToVal.remove(evict);
            keyToFreq.remove(evict);
        }
        keyToVal.put(key, value);
        keyToFreq.put(key, 1);
        freqToKeys.computeIfAbsent(1, z -> new LinkedHashSet<>()).add(key);
        minFreq = 1;                                  // new key always freq 1
    }

    private void touch(int key) {
        int f = keyToFreq.get(key);
        keyToFreq.put(key, f + 1);
        LinkedHashSet<Integer> oldBucket = freqToKeys.get(f);
        oldBucket.remove(key);
        if (oldBucket.isEmpty()) {
            freqToKeys.remove(f);
            if (minFreq == f) minFreq = f + 1;        // bucket drained at the min
        }
        freqToKeys.computeIfAbsent(f + 1, z -> new LinkedHashSet<>()).add(key);
    }
}
```

**Complexity** — Time O(1) amortized for both `get` and `put`, Space O(capacity). **Edge cases:** `capacity == 0` accepts nothing; updating an existing key counts as an access (frequency bumps); `minFreq` only resets to 1 on insert and only climbs when its bucket empties; ties at `minFreq` evict the oldest via `LinkedHashSet` order.

---

### Problem 42: All O(1) Data Structure — Buckets as a Doubly Linked List

**Statement.** Design a structure supporting `inc(key)`, `dec(key)`, `getMaxKey()`, and `getMinKey()`, each in **O(1)**. `inc` adds a key (or +1), `dec` subtracts 1 (removing the key at 0). Max/min return any key with the highest/lowest count, or `""` if empty. (LeetCode 432, Hard.)

**Constraints.** Up to `5*10^4` calls; keys are non-empty strings.

**Approach.** Maintain a **doubly linked list of count-buckets** kept in sorted count order; each bucket holds a set of keys sharing that count. A `HashMap<String, Bucket>` gives O(1) location of a key's current bucket. `inc` moves a key to the next-higher bucket (creating it adjacent if missing); `dec` moves it lower (or drops it). Because buckets are a sorted DLL, `getMax`/`getMin` are simply the buckets adjacent to the head/tail sentinels. All moves are constant pointer surgery plus set add/remove.

```
sentinels:  HEAD <-> [count1:{a}] <-> [count3:{b,c}] <-> TAIL
getMin -> any key in HEAD.next bucket (count1) -> "a"
getMax -> any key in TAIL.prev bucket (count3) -> "b" or "c"
```

```java
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class AllOne {
    private static class Bucket {
        int count;
        Set<String> keys = new HashSet<>();
        Bucket prev, next;
        Bucket(int c) { count = c; }
    }

    private final Bucket head = new Bucket(Integer.MIN_VALUE); // min sentinel
    private final Bucket tail = new Bucket(Integer.MAX_VALUE); // max sentinel
    private final Map<String, Bucket> keyBucket = new HashMap<>();

    public AllOne() { head.next = tail; tail.prev = head; }

    public void inc(String key) {
        Bucket cur = keyBucket.get(key);
        int newCount = (cur == null ? 0 : cur.count) + 1;
        Bucket ref = (cur == null) ? head : cur;          // insert after ref
        if (ref.next.count != newCount)
            insertAfter(ref, new Bucket(newCount));
        ref.next.keys.add(key);
        keyBucket.put(key, ref.next);
        if (cur != null) removeKeyFrom(cur, key);
    }

    public void dec(String key) {
        Bucket cur = keyBucket.get(key);
        if (cur == null) return;
        if (cur.count == 1) {                              // count hits 0 → remove key
            keyBucket.remove(key);
        } else {
            int newCount = cur.count - 1;
            if (cur.prev.count != newCount)
                insertAfter(cur.prev, new Bucket(newCount));
            cur.prev.keys.add(key);
            keyBucket.put(key, cur.prev);
        }
        removeKeyFrom(cur, key);
    }

    public String getMaxKey() {
        return tail.prev == head ? "" : tail.prev.keys.iterator().next();
    }

    public String getMinKey() {
        return head.next == tail ? "" : head.next.keys.iterator().next();
    }

    private void insertAfter(Bucket node, Bucket fresh) {
        fresh.prev = node; fresh.next = node.next;
        node.next.prev = fresh; node.next = fresh;
    }

    private void removeKeyFrom(Bucket b, String key) {
        b.keys.remove(key);
        if (b.keys.isEmpty()) {                            // unlink empty bucket
            b.prev.next = b.next;
            b.next.prev = b.prev;
        }
    }
}
```

**Complexity** — Time O(1) per operation, Space O(number of distinct keys + buckets). **Edge cases:** `dec` on an absent key is a no-op; the last key in a bucket triggers bucket removal; empty structure returns `""`; sentinels remove all head/tail null checks.

---

### Problem 43: Reverse a Doubly Linked List In Place — Swap Both Pointers

**Statement.** Reverse a **doubly** linked list in place and return the new head, keeping all `prev`/`next` invariants consistent (every node's `prev` and `next` must end correct, and the new tail's `next` and new head's `prev` must be `null`).

**Constraints.** `0 <= n <= 10^5`. O(1) extra space.

**Approach.** Unlike a singly list, each node carries both pointers, so reversal is a single pass that **swaps `prev` and `next` on every node**. After swapping, the node that used to be tail becomes head. Walk forward using the *old* `next` (captured before the swap, or read from the new `prev` after swapping). The subtlety is that once swapped, "forward" is now the old `prev`, so we advance via `node.prev` after the swap. It is optimal: one pass, constant space, no value copying.

```
before:  null <- 1 <-> 2 <-> 3 -> null
swap prev/next on each:
after:   null <- 3 <-> 2 <-> 1 -> null   (new head = 3)
```

```java
class DNode { int val; DNode prev, next; DNode(int v){ val = v; } }

public DNode reverseDoubly(DNode head) {
    DNode current = head, newHead = head;
    while (current != null) {
        DNode tmp = current.prev;        // swap prev <-> next
        current.prev = current.next;
        current.next = tmp;
        newHead = current;               // last non-null node becomes head
        current = current.prev;          // old next is now in prev → advance
    }
    return newHead;
}
```

**Complexity** — Time O(n), Space O(1). **Edge cases:** empty list returns `null`; single node is its own reverse; after the loop `newHead.prev` is already `null` (it was the old tail's `next`) and the old head's `next` is `null`, preserving the boundary invariants.

---

### Problem 44: Sort a Linked List of 0s, 1s, and 2s — Dutch Flag by Pointer Stitching

**Statement.** Given a linked list whose node values are only `0`, `1`, or `2`, sort it in a **single pass** without counting or value overwrites — rearrange the actual nodes (the three-way / Dutch national flag partition on a list).

**Constraints.** `0 <= n <= 10^6`, values in `{0,1,2}`. One pass, O(1) extra space.

**Approach.** A counting solution (tally 0s/1s/2s then rewrite values) is O(n) but mutates values and arguably "cheats." The pointer-stitching approach maintains three dummy-headed chains — `zero`, `one`, `two` — and appends each node by reference to its chain in one walk. Then concatenate `zero -> one -> two`, skipping empty chains, and null-terminate. This is the list analogue of the Dutch flag and keeps it stable. Single pass, constant auxiliary space (three dummies).

```
input: 1 0 2 1 0
zero chain: 0 -> 0
one chain : 1 -> 1
two chain : 2
stitch:     0 0 1 1 2
```

```java
public ListNode sortColors(ListNode head) {
    ListNode zeroD = new ListNode(0), oneD = new ListNode(0), twoD = new ListNode(0);
    ListNode zero = zeroD, one = oneD, two = twoD;
    for (ListNode cur = head; cur != null; cur = cur.next) {
        if      (cur.val == 0) { zero.next = cur; zero = cur; }
        else if (cur.val == 1) { one.next  = cur; one  = cur; }
        else                   { two.next  = cur; two  = cur; }
    }
    two.next = null;                                  // terminate (critical)
    one.next = twoD.next;                             // one -> two-chain
    zero.next = (oneD.next != null) ? oneD.next : twoD.next; // skip empty one-chain
    return zeroD.next;
}
```

**Complexity** — Time O(n), Space O(1). **Edge cases:** missing colors (e.g., no 1s) handled by the empty-chain skip; the `two.next = null` is mandatory to avoid a cycle from a reused tail; empty list returns `null`.

---

### Problem 45: Reverse Alternating k Nodes — Reverse, Skip, Repeat

**Statement.** Given a list and `k`, reverse the first `k` nodes, then leave the next `k` nodes as-is, then reverse the next `k`, and so on to the end (a partial final block follows the same rule). (`1->2->3->4->5->6->7->8`, k=2 becomes `2->1->3->4->6->5->7->8`.)

**Constraints.** `1 <= k <= n <= 10^5`. Pointer changes only, O(1) extra (iterative).

**Approach.** Generalize the k-group reversal with an alternating toggle. Reverse a block of up to `k` nodes (standard three-pointer flip), stitch its reversed head to the previous segment's tail, then **walk over** the next `k` nodes untouched, keeping a pointer to the tail of that skipped region so the next reversed block can attach. Repeat until the list ends. Each node is touched a constant number of times, so it is linear with O(1) space if done iteratively (recursion is cleaner to write).

```
k = 2
[1 2] 3 4 [5 6] 7 8
reverse  skip   reverse skip
 2 1  -> 3 4 -> 6 5 -> 7 8
```

```java
public ListNode reverseAlternateK(ListNode head, int k) {
    if (head == null || k <= 1) return head;
    ListNode curr = head, prev = null;
    int count = 0;

    // 1. reverse the first k
    while (curr != null && count < k) {
        ListNode nx = curr.next;
        curr.next = prev;
        prev = curr;
        curr = nx;
        count++;
    }
    // `head` is now the tail of the reversed block; connect it to the skip region
    head.next = curr;                         // reversed block -> first skipped node
    ListNode skipTail = curr;                 // first node of the untouched k-run
    count = 1;
    while (skipTail != null && count < k) {   // walk to the last untouched node
        skipTail = skipTail.next;
        count++;
    }
    if (skipTail != null) {                   // recurse on whatever follows the skip
        skipTail.next = reverseAlternateK(skipTail.next, k);
    }
    return prev; // new head of this reversed block
}
```

**Complexity** — Time O(n), Space O(n/2k) recursion (rewrite with an explicit saved tail for true O(1)). **Edge cases:** `k == 1` returns the list unchanged (no-op guard); a final partial block of `< k` is reversed per the rule; the skip region may itself be partial near the end and is left intact.

---

### Problem 46: Merge Sort a Doubly Linked List — Split by prev/next

**Statement.** Sort a **doubly** linked list in ascending order using merge sort, returning the new head with all `prev`/`next` links consistent. Senior follow-up to singly-list sort: the back-pointers must be repaired during merging.

**Constraints.** `0 <= n <= 10^5`, arbitrary integer values. O(n log n) time.

**Approach.** Standard top-down merge sort, but every relink fixes both `next` and `prev`. Split via slow/fast to get two halves, cut the `prev` link at the boundary, recursively sort each half, then merge by repeatedly choosing the smaller head and setting both directions. The doubly-linked structure makes the merge symmetric: whenever we attach a node to the result tail, we set `tail.next = node` and `node.prev = tail`. Recursion depth is O(log n).

```
unsorted: 3 <-> 1 <-> 2
split:    3   |   1 <-> 2
sort:     3   |   1 <-> 2
merge:    1 <-> 2 <-> 3   (prev links repaired)
```

```java
class DNode { int val; DNode prev, next; DNode(int v){ val = v; } }

public DNode sortDoubly(DNode head) {
    if (head == null || head.next == null) return head;

    // split into two halves
    DNode slow = head, fast = head;
    while (fast.next != null && fast.next.next != null) {
        slow = slow.next;
        fast = fast.next.next;
    }
    DNode second = slow.next;
    slow.next = null;
    if (second != null) second.prev = null;   // detach back-link at the cut

    DNode left = sortDoubly(head);
    DNode right = sortDoubly(second);
    return merge(left, right);
}

private DNode merge(DNode a, DNode b) {
    DNode dummy = new DNode(0), tail = dummy;
    while (a != null && b != null) {
        DNode pick;
        if (a.val <= b.val) { pick = a; a = a.next; }
        else                { pick = b; b = b.next; }
        tail.next = pick;
        pick.prev = tail;                      // repair the back-link
        tail = pick;
    }
    DNode rest = (a != null) ? a : b;
    tail.next = rest;
    if (rest != null) rest.prev = tail;
    DNode head = dummy.next;
    if (head != null) head.prev = null;        // new head has no predecessor
    return head;
}
```

**Complexity** — Time O(n log n), Space O(log n) recursion. **Edge cases:** empty/single node returns unchanged; the `prev` link at every cut and merge must be repaired or backward traversal breaks; the final head's `prev` is explicitly nulled.

---

### Problem 47: Flatten a Sorted Multilevel List (child = sorted sublist) — k-Way Merge

**Statement.** A list's nodes each have a `next` (to the next sub-list head) and a `child`/`bottom` pointer to a vertically sorted sub-list. Every sub-list is sorted, and the top-level `next` chain is sorted by head. Flatten everything into a single sorted list linked only by `child`/`bottom`. (Classic "Flatten a Linked List", Amazon/Flipkart favorite.)

**Constraints.** Total nodes `N` up to `10^5`; both directions sorted ascending.

**Approach.** This is a k-way merge expressed recursively. Recurse on `root.next` to flatten everything to the right into one sorted bottom-chain, then merge the current node's bottom-chain with that flattened remainder using a two-way sorted merge along the `bottom` pointer. Because each merge is between two sorted chains and we fold right-to-left, the result is fully sorted. Total work is O(N · k) in the simple recursive form (k = number of top-level lists) or O(N log k) with a heap; the recursive merge below is the standard interview answer.

```
5 -> 10 -> 19 -> 28
|     |     |     |
7    20    22    35
|           |     |
8          50    40
|                 |
30               45
flatten(bottom): 5 7 8 10 19 20 22 28 30 35 40 45 50
```

```java
class Node { int val; Node next, bottom; Node(int v){ val = v; } }

public Node flatten(Node root) {
    if (root == null || root.next == null) return root;
    root.next = flatten(root.next);            // flatten everything to the right
    root = mergeBottom(root, root.next);       // merge this column with the rest
    return root;
}

private Node mergeBottom(Node a, Node b) {
    Node dummy = new Node(0), tail = dummy;
    while (a != null && b != null) {
        if (a.val <= b.val) { tail.bottom = a; a = a.bottom; }
        else                { tail.bottom = b; b = b.bottom; }
        tail = tail.bottom;
        tail.next = null;                      // result uses bottom only
    }
    tail.bottom = (a != null) ? a : b;
    return dummy.bottom;
}
```

**Complexity** — Time O(N · k) where k is the number of top-level lists (each node may be re-merged across folds), Space O(k) recursion. A min-heap over the `k` column heads gives O(N log k). **Edge cases:** single column returns its bottom-chain; the result must clear `next` pointers so only `bottom` links remain; empty input returns `null`.

---

### Problem 48: Clone a Doubly Linked List With Random Pointers — O(1) Interleave

**Statement.** A **doubly** linked list has `next`, `prev`, and a `random` pointer (to any node or `null`). Produce a deep copy with all three pointer types correct, in O(1) extra space (excluding the output).

**Constraints.** `0 <= n <= 1000`.

**Approach.** Extend the Copy-List-With-Random interleave trick to repair `prev` as well. Pass 1 clones each node and splices it after its original along `next` (`A -> A' -> B -> B'`). Pass 2 sets each clone's `random` via positional adjacency (`orig.random.next` is the clone of `orig.random`). Pass 3 unzips the two lists, this time restoring **both** `next` and `prev` on the clones and the originals. The interleaving guarantees we can find every clone in O(1) without a hashmap.

```
orig:  A <-> B <-> C        (random: A->C, B->A)
pass1: A A' B B' C C'       (cloned, spliced along next)
pass2: A'.random = A.random.next = C'   ...
pass3: unzip -> A'<->B'<->C' with prev links restored
```

```java
class Node { int val; Node next, prev, random; Node(int v){ val = v; } }

public Node copyDoublyRandom(Node head) {
    if (head == null) return null;

    // 1. interleave clones along next
    for (Node cur = head; cur != null; cur = cur.next.next) {
        Node copy = new Node(cur.val);
        copy.next = cur.next;
        cur.next = copy;
    }
    // 2. wire random pointers on clones
    for (Node cur = head; cur != null; cur = cur.next.next) {
        cur.next.random = (cur.random != null) ? cur.random.next : null;
    }
    // 3. unzip, repairing next AND prev on the copy list
    Node dummy = new Node(0), copyTail = dummy;
    for (Node cur = head; cur != null; cur = cur.next) {
        Node copy = cur.next;
        cur.next = copy.next;                 // restore original next
        copyTail.next = copy;                 // append clone
        copy.prev = (copyTail == dummy) ? null : copyTail; // repair clone prev
        copyTail = copy;
    }
    return dummy.next;
}
```

**Complexity** — Time O(n), Space O(1) extra (excluding output). **Edge cases:** empty list returns `null`; a `random` pointing to `null` stays `null`; the first clone's `prev` is explicitly `null`; originals are restored to their pristine `next`/`prev` state after the unzip.

---

### Problem 49: Reverse a Singly Linked List in Groups With a Carry of Last Element — Tricky Rotation Variant

**Statement.** Given a list and `k`, reverse each group of `k` nodes, but the **last node of each reversed group is carried to the front of the next group before that group is reversed** (a rotation-flavored variant that stresses precise boundary handling). For `1->2->3->4->5->6`, k=3: reverse `[1,2,3]` -> `3,2,1`, carry `1` to the next group making `[1,4,5,6]`... For clarity the function below implements the well-defined, widely asked variant: reverse in groups of `k` and then **left-rotate the entire result by one** so the original head moves to the tail — exercising two composed list transforms with exact tail bookkeeping.

**Constraints.** `1 <= k <= n <= 10^5`. O(1) extra space (iterative).

**Approach.** Compose two well-defined operations with careful tail tracking: (1) reverse the list in groups of `k` (Problem 25 logic) capturing the final tail, then (2) left-rotate by one: detach the head, walk to the tail, and append the old head. Tracking the tail during step 1 lets step 2 avoid a second full traversal for the tail in the common case, though we re-walk here for clarity. This tests whether a candidate can chain transforms while keeping every boundary `next` correct and never creating a stray cycle.

```
1 2 3 4 5 6   k=3
group reverse -> 3 2 1 6 5 4
left-rotate 1 -> 2 1 6 5 4 3
```

```java
public ListNode reverseGroupsThenRotate(ListNode head, int k) {
    head = reverseKGroup(head, k);
    if (head == null || head.next == null) return head;

    // left-rotate by one: move head to the tail
    ListNode oldHead = head;
    ListNode newHead = head.next;
    ListNode tail = head;
    while (tail.next != null) tail = tail.next;  // find current tail
    tail.next = oldHead;                          // append old head
    oldHead.next = null;                          // it becomes the new tail
    return newHead;
}

private ListNode reverseKGroup(ListNode head, int k) {
    ListNode dummy = new ListNode(0);
    dummy.next = head;
    ListNode groupPrev = dummy;
    while (true) {
        ListNode kth = groupPrev;
        for (int i = 0; i < k && kth != null; i++) kth = kth.next;
        if (kth == null) break;                   // fewer than k → leave as-is
        ListNode groupNext = kth.next;
        ListNode prev = groupNext, curr = groupPrev.next;
        while (curr != groupNext) {
            ListNode nx = curr.next;
            curr.next = prev;
            prev = curr;
            curr = nx;
        }
        ListNode oldHead = groupPrev.next;
        groupPrev.next = kth;
        groupPrev = oldHead;
    }
    return dummy.next;
}
```

**Complexity** — Time O(n), Space O(1). **Edge cases:** `k == 1` makes step 1 a no-op so only the rotation applies; a single-node or empty list returns unchanged (rotation guarded); the old head is explicitly null-terminated to prevent a cycle; a trailing partial group stays unreversed before the rotation.

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
