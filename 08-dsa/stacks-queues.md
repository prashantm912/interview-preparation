# Stacks & Queues

Stacks (LIFO) and queues (FIFO) are the two simplest *ordered-access* containers, yet they unlock an enormous family of interview problems: parenthesis matching, expression evaluation, monotonic-stack/deque sweeps, and amortized O(n) histogram and sliding-window tricks. This guide builds the intuition, the complexity facts, the recognition patterns, and a graded set of fully-solved Java problems.

[← Back to master index](../README.md) | [← DSA index](README.md)

---

## Concept & Intuition

A **stack** restricts access to one end. You can only `push` (add to top) and `pop` (remove from top), so the **last element in is the first out** (LIFO). Think of a stack of plates: you take the top plate, never the bottom.

A **queue** restricts access to two ends. You `enqueue` (add to back/tail) and `dequeue` (remove from front/head), so the **first element in is the first out** (FIFO). Think of a checkout line.

A **deque** ("deck", double-ended queue) generalizes both: you can push/pop at *both* ends in O(1). A deque can act as a stack or a queue, and it is the engine behind the sliding-window-maximum trick.

```
STACK (LIFO)                 QUEUE (FIFO)                  DEQUE (both ends)
   push ↓  ↑ pop                                            push/pop
   ┌────┐                     front          back            ↕      ↕
   │  3 │ ← top               ┌───┬───┬───┐                ┌───┬───┬───┐
   ├────┤                  →  │ 1 │ 2 │ 3 │ ←             │ a │ b │ c │
   │  2 │                     └───┴───┴───┘                └───┴───┴───┘
   ├────┤                  dequeue↑      ↑enqueue        head            tail
   │  1 │
   └────┘
```

**When to use a stack**
- You must process the *most recent* unmatched / unresolved item first (parentheses, function call frames, undo history, DFS, backtracking).
- You need to "remember" candidates and pop them when a *better* candidate arrives — the **monotonic stack** pattern (next greater element, histogram, stock span).

**When to use a queue**
- You must process items in arrival order (BFS, task scheduling, producer/consumer buffers, rate limiting).

**When to use a deque**
- You need a *sliding window* extremum, or you push/pop at both ends (0-1 BFS, palindrome checks, work-stealing).

**Core invariants to keep straight**
- A **monotonic stack** maintains elements in sorted order (increasing or decreasing). Before pushing `x`, you pop everything that violates monotonicity — and each pop is exactly the moment you "resolve" the popped element's answer. Each element is pushed once and popped once → **amortized O(1)** per element, O(n) overall.
- A **monotonic deque** holds *indices* whose values are monotonic; you evict from the front when an index falls out of the window, and evict from the back when a new value dominates.
- A queue-built-from-two-stacks relies on the identity: reversing twice restores order, so an *in* stack plus an *out* stack yields FIFO with amortized O(1) operations.

---

## Complexity Cheat-Sheet

| Structure / Operation | Time | Space | Notes |
|---|---|---|---|
| Stack `push` / `pop` / `peek` | O(1) | O(n) | Array-backed (`ArrayDeque`) or linked |
| Queue `enqueue` / `dequeue` / `peek` | O(1) | O(n) | `ArrayDeque` (avoid `LinkedList` micro-overhead) |
| Deque push/pop/peek either end | O(1) | O(n) | `ArrayDeque` is the workhorse |
| Min-Stack `getMin` | O(1) | O(n) | Auxiliary min stack or encoded deltas |
| Queue via 2 stacks (enqueue/dequeue) | amortized O(1), worst O(n) | O(n) | Each element moved at most once |
| Stack via 2 queues | push O(1)/O(n) tradeoff | O(n) | Pick costly-push or costly-pop variant |
| Valid parentheses | O(n) | O(n) | One pass + stack |
| Evaluate RPN | O(n) | O(n) | Operand stack |
| Infix → Postfix (Shunting-yard) | O(n) | O(n) | Operator stack |
| Next Greater Element (monotonic) | O(n) | O(n) | Each index pushed/popped once |
| Largest Rectangle in Histogram | O(n) | O(n) | Monotonic increasing stack of indices |
| Sliding Window Maximum (monotonic deque) | O(n) | O(k) | Deque of indices |
| Circular Queue (ring buffer) | O(1) all ops | O(k) | Fixed capacity, no shifting |
| Stock Span | O(n) | O(n) | Monotonic stack of (price, span) |

The amortized rows are the ones interviewers probe — be ready to justify them (see the Q&A section).

---

## Patterns & Recognition

Use these heuristics to *recognize* the structure before you start coding:

1. **"Match / nest / balance" → Stack.** Brackets, tags, nested expressions, validating that a sequence could be produced by push/pop operations. The most recent open thing must close first.
2. **"For each element, find the nearest greater/smaller element to the left/right" → Monotonic stack.** Keywords: *next greater*, *previous smaller*, *span*, *days until warmer*, *visible buildings*, *trapping rain water*, *largest rectangle*. If a brute force is O(n²) with two nested loops comparing each element to others, a monotonic stack usually collapses it to O(n).
3. **"Sliding window min/max as the window moves" → Monotonic deque.** Keywords: *window of size k*, *maximum of each subarray*, *shortest subarray with sum ≥ K*. You need both ends: evict stale front, evict dominated back.
4. **"Process in arrival order / level by level" → Queue.** BFS, multi-source BFS, task scheduling, streaming averages over a fixed window.
5. **"Fixed-size buffer that wraps around / most-recent-K" → Circular queue (ring buffer).** Telemetry buffers, rate limiters, audio/video frame buffers.
6. **"Implement X using only Y" → conversion problems.** Queue-from-stacks and stack-from-queues test whether you understand the order-reversal identity and amortized analysis.
7. **Recursion → explicit stack.** When asked to "do it iteratively" or when recursion depth risks a `StackOverflowError`, simulate the call stack with an explicit `Deque`.

Red flag for monotonic structures: you are computing, *for every position*, a relationship to the "nearest qualifying neighbor." That is almost always O(n) with a stack/deque instead of O(n²).

---

## Coding Problems

> All solutions use `java.util.ArrayDeque` as both stack and deque — it is faster than `Stack` (which is synchronized/legacy) and faster than `LinkedList` for queue duties. Avoid `null` elements in `ArrayDeque` (it forbids them).

### Problem 1: Valid Parentheses

**Statement.** Given a string `s` containing only `()[]{}`, determine if the brackets are correctly opened and closed in the right order. Constraints: `1 ≤ s.length ≤ 10^4`.

**Approach.**
- *Brute force:* repeatedly delete adjacent matching pairs `()`, `[]`, `{}` until no change; valid iff the string becomes empty. O(n²).
- *Optimal:* a stack. Push opening brackets; on a closing bracket, the top of the stack must be its matching opener, else fail. At the end the stack must be empty.

```java
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

class Solution {
    public boolean isValid(String s) {
        Map<Character, Character> close = Map.of(')', '(', ']', '[', '}', '{');
        Deque<Character> stack = new ArrayDeque<>();
        for (char c : s.toCharArray()) {
            if (close.containsKey(c)) {                 // closing bracket
                if (stack.isEmpty() || stack.pop() != close.get(c)) {
                    return false;
                }
            } else {                                    // opening bracket
                stack.push(c);
            }
        }
        return stack.isEmpty();
    }
}
```

**Walkthrough** for `"{[]}"`: push `{`, push `[`, see `]` → pop `[` matches, see `}` → pop `{` matches, stack empty → `true`. For `"(]"`: push `(`, see `]` → pop `(` ≠ `[` → `false`.

**Time:** O(n). **Space:** O(n).

**Follow-ups.** Return the *index* of the first invalid bracket. Allow other characters (ignore them). Find the *minimum* insertions/deletions to balance (LeetCode 921/1249). Validate XML/HTML tag nesting.

---

### Problem 2: Min Stack

**Statement.** Design a stack supporting `push`, `pop`, `top`, and `getMin`, all in O(1).

**Approach.**
- *Brute force:* scan for the min on each `getMin` → O(n) per call.
- *Optimal A:* keep a parallel `minStack` whose top is always the current minimum.
- *Optimal B (one stack):* store encoded deltas relative to the running min so no second stack is needed.

```java
import java.util.ArrayDeque;
import java.util.Deque;

class MinStack {
    private final Deque<Integer> data = new ArrayDeque<>();
    private final Deque<Integer> mins = new ArrayDeque<>();   // top = current min

    public void push(int x) {
        data.push(x);
        mins.push(mins.isEmpty() ? x : Math.min(x, mins.peek()));
    }

    public void pop()      { data.pop(); mins.pop(); }
    public int top()       { return data.peek(); }
    public int getMin()    { return mins.peek(); }
}
```

**Walkthrough** push 5, 3, 7, 2: `mins` becomes 5 → 3 → 3 → 2. `getMin` = 2. After two pops, `mins` top = 3, matching the data.

**Time:** O(1) all ops. **Space:** O(n).

**Follow-ups.** O(1) `getMax` too (symmetric stack). The single-stack delta trick (push `2*x - min` when `x < min`, decode on pop) to use O(1) extra. A **max-frequency stack** (LeetCode 895) and a queue with O(1) min (use two min-stacks).

---

### Problem 3: Implement Queue using Stacks

**Statement.** Implement a FIFO queue (`push`, `pop`, `peek`, `empty`) using only stack operations.

**Approach.** Use an `in` stack for enqueues and an `out` stack for dequeues. When `out` is empty and a dequeue is requested, pour `in` into `out` (this reverses order, producing FIFO). Each element is moved from `in` to `out` at most once → **amortized O(1)**.

```java
import java.util.ArrayDeque;
import java.util.Deque;

class MyQueue {
    private final Deque<Integer> in = new ArrayDeque<>();
    private final Deque<Integer> out = new ArrayDeque<>();

    public void push(int x) { in.push(x); }

    public int pop() {
        peek();                 // ensure 'out' has the front
        return out.pop();
    }

    public int peek() {
        if (out.isEmpty()) {
            while (!in.isEmpty()) out.push(in.pop());
        }
        return out.peek();
    }

    public boolean empty() { return in.isEmpty() && out.isEmpty(); }
}
```

**Walkthrough** push 1,2,3 → `in=[3,2,1]`. First `peek` pours all into `out=[1,2,3]` (top=1). `pop` returns 1. push 4 → `in=[4]`. `pop` returns 2 from `out` without re-pouring.

**Time:** amortized O(1) per op (worst-case single op O(n)). **Space:** O(n).

**Follow-ups.** Prove the amortized bound with the accounting/potential method. Implement the reverse (stack from queues, Problem 4). Make it thread-safe.

---

### Problem 4: Implement Stack using Queues

**Statement.** Implement a LIFO stack using only queue operations.

**Approach.** Two designs; pick which op you want to be cheap.
- *Costly push (shown):* keep a single queue but, on push, rotate so the newest element sits at the front → `pop`/`top` are O(1), `push` is O(n).
- *Costly pop:* push is O(1); on pop, move all but the last element to a second queue.

```java
import java.util.ArrayDeque;
import java.util.Queue;

class MyStack {
    private final Queue<Integer> q = new ArrayDeque<>();

    public void push(int x) {
        q.add(x);
        for (int i = q.size() - 1; i > 0; i--) {   // rotate new element to front
            q.add(q.remove());
        }
    }

    public int pop()  { return q.remove(); }
    public int top()  { return q.peek(); }
    public boolean empty() { return q.isEmpty(); }
}
```

**Walkthrough** push 1 → `[1]`. push 2 → add → `[1,2]`, rotate 1 step → `[2,1]`. push 3 → `[2,1,3]`, rotate 2 → `[3,2,1]`. `pop` returns 3 (LIFO).

**Time:** push O(n), pop/top O(1). **Space:** O(n).

**Follow-ups.** Switch to the costly-pop variant and compare. Discuss which is better when reads vastly outnumber writes.

---

### Problem 5: Evaluate Reverse Polish Notation (RPN)

**Statement.** Evaluate an arithmetic expression in postfix form, e.g. `["2","1","+","3","*"]` → `((2+1)*3)=9`. Operators are `+ - * /`; division truncates toward zero. Constraints: valid expression, `1 ≤ tokens ≤ 10^4`.

**Approach.** Push numbers onto an operand stack. On an operator, pop the top two operands (mind operand order for `-` and `/`), apply, push the result. The final stack holds the answer.

```java
import java.util.ArrayDeque;
import java.util.Deque;

class Solution {
    public int evalRPN(String[] tokens) {
        Deque<Integer> st = new ArrayDeque<>();
        for (String t : tokens) {
            switch (t) {
                case "+": st.push(st.pop() + st.pop()); break;
                case "*": st.push(st.pop() * st.pop()); break;
                case "-": { int b = st.pop(), a = st.pop(); st.push(a - b); break; }
                case "/": { int b = st.pop(), a = st.pop(); st.push(a / b); break; }
                default:  st.push(Integer.parseInt(t));
            }
        }
        return st.pop();
    }
}
```

**Walkthrough** `["2","1","+","3","*"]`: push 2, push 1, `+` → pop 1,2 push 3; push 3 → stack `[3,3]`; `*` → pop 3,3 push 9 → answer 9.

**Time:** O(n). **Space:** O(n).

**Follow-ups.** Support unary minus, floating point, or parentheses-aware infix (Problem 6). Handle very large numbers with `long`/`BigInteger`. Detect malformed expressions.

---

### Problem 6: Infix to Postfix (Shunting-Yard)

**Statement.** Convert a fully-parenthesizable infix expression (single-digit/letter operands, operators `+ - * / ^`, parentheses) into postfix (RPN) using Dijkstra's shunting-yard algorithm.

**Approach.** Scan left to right. Output operands immediately. Use an operator stack: before pushing an operator, pop operators of *greater-or-equal* precedence (strictly greater for right-associative `^`). `(` always pushes; `)` pops until the matching `(`.

```java
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

class Solution {
    private static final Map<Character, Integer> PREC =
        Map.of('+', 1, '-', 1, '*', 2, '/', 2, '^', 3);

    public String toPostfix(String s) {
        StringBuilder out = new StringBuilder();
        Deque<Character> ops = new ArrayDeque<>();
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
            } else if (c == '(') {
                ops.push(c);
            } else if (c == ')') {
                while (!ops.isEmpty() && ops.peek() != '(') out.append(ops.pop());
                ops.pop();                              // discard '('
            } else {                                    // operator
                boolean rightAssoc = (c == '^');
                while (!ops.isEmpty() && ops.peek() != '('
                        && (PREC.get(ops.peek()) > PREC.get(c)
                            || (PREC.get(ops.peek()).equals(PREC.get(c)) && !rightAssoc))) {
                    out.append(ops.pop());
                }
                ops.push(c);
            }
        }
        while (!ops.isEmpty()) out.append(ops.pop());
        return out.toString();
    }
}
```

**Walkthrough** `a+b*c`: output `a`; push `+`; output `b`; `*` has higher precedence than `+` so push `*`; output `c`; drain stack → `*` then `+`. Result `abc*+`. For `a^b^c` (right-assoc), result is `abc^^`.

**Time:** O(n). **Space:** O(n).

**Follow-ups.** Convert to *prefix* (reverse + swap parens). Evaluate directly during the scan (two stacks: operands + operators). Support multi-character numbers, unary minus, and function calls.

---

### Problem 7: Next Greater Element (Monotonic Stack)

**Statement.** For each element in array `nums`, find the next element to its right that is strictly greater; if none, output `-1`. Constraints: `1 ≤ n ≤ 10^5`.

**Approach.**
- *Brute force:* for each `i`, scan right until a larger value → O(n²).
- *Optimal:* a **monotonic decreasing stack of indices**. Iterate left to right. While the current value exceeds the value at the stack's top index, that top element's "next greater" is the current value — pop and record. Push the current index. Unresolved indices get `-1`.

```java
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

class Solution {
    public int[] nextGreater(int[] nums) {
        int n = nums.length;
        int[] ans = new int[n];
        Arrays.fill(ans, -1);
        Deque<Integer> stack = new ArrayDeque<>();      // indices, values decreasing
        for (int i = 0; i < n; i++) {
            while (!stack.isEmpty() && nums[i] > nums[stack.peek()]) {
                ans[stack.pop()] = nums[i];
            }
            stack.push(i);
        }
        return ans;
    }
}
```

**Walkthrough** `nums=[2,1,2,4,3]`: i0 push0; i1 push1 (1<2); i2 val2>nums[1]=1→ans[1]=2 pop, 2 not > nums[0]=2, push2; i3 val4 pops indices 2 (ans=4) and 0 (ans=4), push3; i4 val3<4 push4. Result `[4,2,4,-1,-1]`.

**Time:** O(n) — each index pushed/popped once. **Space:** O(n).

**Follow-ups.** **Circular** array (LeetCode 503): iterate `2n` times with `i % n`. **Next greater in another array** (LeetCode 496): precompute a map. **Previous smaller / next smaller** by flipping the comparison. **Daily Temperatures** (store index distance). **Trapping Rain Water** and **Sum of Subarray Minimums** are stack relatives.

---

### Problem 8: Stock Span

**Statement.** The span of a stock's price on a day is the number of consecutive days up to and including today where the price was ≤ today's price. Implement `next(price)` returning today's span as a stream. Constraints: up to `10^4` calls.

**Approach.** A **monotonic decreasing stack** of `(price, span)` pairs. For a new price, pop all stack entries with price ≤ today, accumulating their spans (plus 1 for today), then push the merged entry. Amortized O(1) per call because each price is pushed/popped once.

```java
import java.util.ArrayDeque;
import java.util.Deque;

class StockSpanner {
    private final Deque<int[]> stack = new ArrayDeque<>();   // {price, span}

    public int next(int price) {
        int span = 1;
        while (!stack.isEmpty() && stack.peek()[0] <= price) {
            span += stack.pop()[1];
        }
        stack.push(new int[]{price, span});
        return span;
    }
}
```

**Walkthrough** prices 100, 80, 60, 70, 60, 75, 85 → spans 1, 1, 1, 2, 1, 4, 6. On 75: pop 60(span1) and 70(span2) and 60... 75 absorbs the run, yielding 4; on 85 it absorbs everything below it → 6.

**Time:** amortized O(1) per `next`, O(n) total. **Space:** O(n).

**Follow-ups.** Span looking *forward* instead of backward. Span with a strict `<` comparison. Reset/rolling-window variants. Relate to "previous greater element."

---

### Problem 9: Design Circular Queue (Ring Buffer)

**Statement.** Implement `MyCircularQueue(k)` with `enQueue`, `deQueue`, `Front`, `Rear`, `isEmpty`, `isFull`, all O(1) and using fixed O(k) memory.

**Approach.** A fixed array with `head` index and a `count`. The tail position is computed as `(head + count) % capacity`, so no element shifting is ever required — indices wrap around modulo the capacity.

```java
class MyCircularQueue {
    private final int[] buf;
    private final int cap;
    private int head = 0, count = 0;

    public MyCircularQueue(int k) { cap = k; buf = new int[k]; }

    public boolean enQueue(int value) {
        if (isFull()) return false;
        buf[(head + count) % cap] = value;
        count++;
        return true;
    }

    public boolean deQueue() {
        if (isEmpty()) return false;
        head = (head + 1) % cap;
        count--;
        return true;
    }

    public int Front() { return isEmpty() ? -1 : buf[head]; }
    public int Rear()  { return isEmpty() ? -1 : buf[(head + count - 1) % cap]; }
    public boolean isEmpty() { return count == 0; }
    public boolean isFull()  { return count == cap; }
}
```

**Walkthrough** k=3: enQueue 1,2,3 fills it (`isFull`=true); the 4th enQueue fails. deQueue advances `head` to index 1; now enQueue 4 writes to index `(1+2)%3=0`, reusing the freed slot. `Front`=2, `Rear`=4.

**Time:** O(1) all ops. **Space:** O(k).

**Follow-ups.** **Circular Deque** (LeetCode 641, insert/delete both ends). Auto-growing ring buffer (double capacity, recopy). Lock-free single-producer/single-consumer ring buffer for concurrency. Use it as a fixed-window moving average.

---

### Problem 10: Sliding Window Maximum (Monotonic Deque) — *Hard*

**Statement.** Given `nums` and window size `k`, return the maximum of each contiguous window of size `k` as it slides one step at a time. Constraints: `1 ≤ k ≤ n ≤ 10^5`.

**Approach.**
- *Brute force:* recompute the max of each window → O(n·k).
- *Heap:* a max-heap with lazy deletion → O(n log n).
- *Optimal O(n):* a **monotonic decreasing deque of indices**. The front always holds the index of the current window's maximum. For each new index `i`: (1) evict the front if it has slid out of the window (`<= i - k`); (2) evict from the back every index whose value is `<=` `nums[i]` (they can never be a future max while `i` is in range); (3) push `i`; (4) once the first window is formed, record `nums[front]`.

```java
import java.util.ArrayDeque;
import java.util.Deque;

class Solution {
    public int[] maxSlidingWindow(int[] nums, int k) {
        int n = nums.length;
        int[] ans = new int[n - k + 1];
        Deque<Integer> dq = new ArrayDeque<>();          // indices, values decreasing
        for (int i = 0; i < n; i++) {
            if (!dq.isEmpty() && dq.peekFirst() <= i - k) dq.pollFirst();   // out of window
            while (!dq.isEmpty() && nums[dq.peekLast()] <= nums[i]) dq.pollLast();
            dq.offerLast(i);
            if (i >= k - 1) ans[i - k + 1] = nums[dq.peekFirst()];
        }
        return ans;
    }
}
```

**Walkthrough** `nums=[1,3,-1,-3,5,3,6,7], k=3`. Window [1,3,-1] → deque front index1 (val3) → max 3. Slide: -3 added → 3 still front → 3. 5 added: it evicts everything (3,-1,-3) from the back, front becomes 5 → 5. Then 5 → 6 → 7. Output `[3,3,5,5,6,7]`.

**Time:** O(n) — each index is added and removed from the deque at most once. **Space:** O(k).

**Follow-ups.** Sliding window *minimum* (flip the comparison). Sliding window *median* (two heaps or a balanced BST). **Shortest subarray with sum ≥ K** (LeetCode 862, monotonic deque over prefix sums). Streaming variant where `k` changes.

---

### Problem 11: Largest Rectangle in Histogram — *Hard / Senior*

**Statement.** Given bar heights of unit width, find the area of the largest axis-aligned rectangle that fits entirely under the histogram. Constraints: `1 ≤ n ≤ 10^5`, `0 ≤ height ≤ 10^4`.

**Approach.**
- *Brute force:* for each pair `(i, j)` take the min height × width → O(n²) (or O(n²) with running min).
- *Optimal O(n):* a **monotonic increasing stack of indices**. Each bar, when popped, is the *shortest* bar of the rectangle it bounds; its width spans from the element below it on the stack (exclusive) to the current index (exclusive). A trailing sentinel height of `0` flushes the stack at the end.

```java
import java.util.ArrayDeque;
import java.util.Deque;

class Solution {
    public int largestRectangleArea(int[] h) {
        int n = h.length, best = 0;
        Deque<Integer> stack = new ArrayDeque<>();       // indices, heights increasing
        for (int i = 0; i <= n; i++) {
            int cur = (i == n) ? 0 : h[i];               // sentinel flushes remaining bars
            while (!stack.isEmpty() && h[stack.peek()] >= cur) {
                int height = h[stack.pop()];
                int leftBound = stack.isEmpty() ? -1 : stack.peek();
                int width = i - leftBound - 1;
                best = Math.max(best, height * width);
            }
            stack.push(i);
        }
        return best;
    }
}
```

**Walkthrough** `h=[2,1,5,6,2,3]`. When the bar of height 2 (index 4) arrives, it pops 6 (width 1 → area 6) and 5 (width 2 → area 10). The best rectangle is `5×6` at indices 2-3? No — the popped-5 spans indices 2..3 giving width 2, height 5 → 10. Final answer **10**.

**Time:** O(n) — each index pushed/popped once. **Space:** O(n).

**Follow-ups.** **Maximal Rectangle** in a binary matrix (LeetCode 85: run this per row over cumulative heights → O(rows·cols)). **Trapping Rain Water** (a sibling monotonic-stack problem). Largest *square*. The "stack of indices + sentinel" trick is the senior-level signal interviewers look for.

---

## Interview Q&A by Level

### 🟢 Basic

**Q: Stack vs queue in one sentence each?**
A stack is LIFO (last-in-first-out); a queue is FIFO (first-in-first-out).

**Q: Why prefer `ArrayDeque` over `Stack` in Java?**
`java.util.Stack` extends `Vector` and is synchronized (legacy, slower) and allows index access that breaks the abstraction. `ArrayDeque` is a faster, unsynchronized resizable-array deque recommended by the JDK docs for both stack and queue roles. Just remember it forbids `null` elements.

**Q: What real systems use a stack?**
Function call frames, expression evaluation, undo/redo, browser back button, DFS/backtracking, and the JVM's own operand stack.

**Q: What real systems use a queue?**
BFS, OS process/IO scheduling, message brokers (Kafka, RabbitMQ), print spoolers, request buffering, and breadth-first crawlers.

**Q: How do you detect stack underflow/overflow?**
Underflow = pop/peek on empty (check `isEmpty()`). Overflow = pushing past a fixed capacity (relevant for ring buffers and recursion depth → `StackOverflowError`).

### 🟡 Intermediate

**Q: What is a monotonic stack and what problems does it solve?**
A stack kept in sorted (increasing or decreasing) order by popping violators before each push. It answers "nearest greater/smaller element" style questions — next greater element, stock span, daily temperatures, histogram, trapping rain water — turning O(n²) brute force into O(n).

**Q: Why is the two-stack queue amortized O(1) and not O(1) worst-case?**
A single `dequeue` that triggers a transfer is O(n) in the worst case. But each element is moved from `in` to `out` exactly once over its lifetime, so across any sequence of `m` operations the total work is O(m) → amortized O(1). One *individual* operation can still be O(n).

**Q: Stack-from-two-queues: where does the cost go?**
You choose: costly push (rotate on insert, O(n) push / O(1) pop) or costly pop (O(1) push / O(n) pop). There is no way to make both O(1) with queues, unlike the two-stack queue's amortized win.

**Q: When do you store indices vs values in a monotonic stack/deque?**
Store **indices** whenever you need positional information — width in the histogram, distance in daily temperatures, or window-boundary checks in sliding-window-maximum. Store values only when position is irrelevant.

**Q: How does a circular queue avoid shifting elements?**
It tracks `head` and `count` (or `head`/`tail`) and computes positions modulo capacity, so dequeue just advances `head` and enqueue writes at `(head + count) % cap` — no array compaction.

### 🟠 Advanced

**Q: Prove the O(n) bound for largest-rectangle / next-greater using amortized analysis.**
Use the *aggregate* (or potential) method: every index is pushed exactly once and popped at most once. The total number of stack operations across the whole loop is therefore ≤ 2n. The per-iteration `while` loop can spin many times, but those pops are "paid for" by earlier pushes, so the *summed* cost is O(n).

**Q: How would you implement a queue with O(1) `min` (or `max`)?**
Combine two min-stacks into a queue (the Problem 3 pattern), where each stack is itself a Min-Stack. The queue's minimum is `min(min(in), min(out))`. Each element still moves once → amortized O(1) for enqueue, dequeue, and getMin.

**Q: How do you choose between a heap and a monotonic deque for sliding-window max?**
A heap gives O(n log n) and handles arbitrary "top-k" or median queries but needs lazy deletion for stale entries. The monotonic deque is O(n) and O(k) space but works only for a single extremum over a *contiguous* window. Prefer the deque when you need just min or max of a fixed-size sliding window.

**Q: Recursion vs explicit stack — when convert?**
Convert when recursion depth can exceed the JVM stack (deep trees, long linked lists, large graphs) risking `StackOverflowError`, or when you need to pause/resume traversal. An explicit `ArrayDeque` lets you control memory and capacity.

**Q: What's the difference between a deque, a circular buffer, and a priority queue?**
A deque allows O(1) insert/remove at both ends (order preserved). A circular buffer is a fixed-capacity FIFO over a ring. A priority queue (heap) returns the highest-priority element regardless of insertion order, with O(log n) operations — it is *not* FIFO.

### 🔴 Expert

**Q: Design a lock-free SPSC ring buffer for a high-throughput pipeline.**
Use a power-of-two capacity so index wrap is a bitmask (`idx & (cap-1)`) instead of modulo. Maintain separate `head` (consumer) and `tail` (producer) cursors with appropriate memory ordering (acquire/release / `volatile` + `VarHandle`), pad cursors to separate cache lines to avoid false sharing, and never block — full means producer spins or drops. This underpins Disruptor-style and audio/networking pipelines.

**Q: How do you scale a queue beyond one machine?**
Move to a distributed log/broker (Kafka, Pulsar, SQS). Partition for throughput, replicate for durability, and accept that strict global FIFO ordering is only guaranteed *within* a partition. Consumers track offsets; back-pressure is handled by bounded queues and flow control. Trade-offs: exactly-once vs at-least-once delivery, ordering vs parallelism.

**Q: Why does `ArrayDeque` outperform `LinkedList` despite both being deques?**
`ArrayDeque` stores elements contiguously → cache-friendly, no per-node object/pointer overhead, fewer allocations and less GC pressure. `LinkedList` allocates a node per element and chases pointers, hurting locality. The only edge cases favoring a linked structure are O(1) removal from the *middle* via an iterator.

**Q: Sketch the proof that the histogram stack never misses the optimal rectangle.**
Every maximal rectangle is bounded by some shortest bar `b`. In the algorithm, `b` is popped exactly when the first strictly-shorter bar to its right appears (or at the sentinel), and the left boundary is the nearest shorter bar on its left (the element beneath it on the stack). Thus when `b` is popped we compute *its* maximal width precisely, and since every bar is popped once, every candidate maximal rectangle is examined.

**Q: How do you handle very large or streaming RPN / window problems that don't fit in memory?**
Process as a stream with a bounded structure: RPN needs only an operand stack of depth proportional to expression nesting; sliding-window-max needs only an O(k) deque. For unbounded input, externalize the stack/deque to disk-backed structures or shard the stream, and watch for integer overflow by switching to `long`/`BigInteger`.

---

## ⚠️ Common Pitfalls

- **`Stack` vs `ArrayDeque` push direction.** `ArrayDeque.push/pop` operate on the *head*; `addLast/pollFirst` give FIFO. Mixing them silently breaks order — pick a consistent mental model (treat `push/pop/peek` as stack, `offer/poll/peek` as queue).
- **`null` in `ArrayDeque`.** It throws `NullPointerException`; you cannot use `null` as a sentinel. Use a separate boolean or a sentinel value.
- **Operand order for `-` and `/` in RPN.** Pop `b` then `a`, compute `a - b` / `a / b`, not `b - a`. A common bug.
- **Strict vs non-strict comparison in monotonic stacks.** `>` vs `>=` decides whether equal heights are merged; getting it wrong over- or under-counts widths in the histogram and mishandles duplicates in next-greater. Be deliberate.
- **Forgetting the sentinel** (`0` height at the end of the histogram, or the empty-stack check) leaves bars unflushed and undercounts the area.
- **Window-eviction order in sliding-window-max.** Evict the stale front *before* (or consistently relative to) pushing, and store indices not values so you can test `front <= i - k`.
- **Amortized ≠ worst-case.** Don't claim two-stack queue ops are O(1) worst-case; they are amortized O(1).
- **`getMin` after `pop` in Min-Stack.** Always pop the auxiliary min stack in lockstep with the data stack, or the min becomes stale.
- **Integer overflow** in histogram area (`height * width`) and RPN — use `long` when constraints are large.
- **Empty-structure access.** Calling `pop`/`peek`/`Front` on an empty stack/queue must be guarded; return a sentinel or throw deliberately, not accidentally.

---

## 📚 Further Reading

- *CLRS, Introduction to Algorithms* — Ch. 10 (Stacks, Queues, Linked Lists) and Ch. 17 (Amortized Analysis, the accounting and potential methods).
- *Algorithms, 4th ed.* — Sedgewick & Wayne — bag/stack/queue APIs and resizing-array vs linked-list implementations.
- Dijkstra's **Shunting-Yard** algorithm — the canonical infix→postfix/prefix conversion.
- LeetCode tag drills: **Stack**, **Monotonic Stack**, **Queue**, **Monotonic Queue / Sliding Window**. Key IDs: 20, 155, 232, 225, 150, 496/503, 739, 84, 85, 239, 862, 901, 622/641, 42.
- The **LMAX Disruptor** paper — production lock-free ring buffer design and the cost of false sharing.
- Java docs for `java.util.ArrayDeque`, `Deque`, and `PriorityQueue` — performance notes and contract details.

[← Back to master index](../README.md) | [← DSA index](README.md)
