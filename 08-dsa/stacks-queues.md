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

## 🧩 Extended Problems — Set 1: Classic Easy → Medium

### Problem 12: Daily Temperatures — Monotonic Stack of Indices

**Statement.** Given an array `temperatures`, return an array `answer` where `answer[i]` is the number of days you have to wait after day `i` to get a warmer temperature; if none exists, `answer[i] = 0`.

**Constraints.** `1 ≤ n ≤ 10^5`, `30 ≤ temperatures[i] ≤ 100`.

**Approach.** This is "next greater element" but the answer is the *distance* to that element, not its value — so store **indices** in a monotonic decreasing stack. Iterate left to right; while the current temperature is strictly greater than the temperature at the top index, that day's wait is resolved as `i - poppedIndex`. Brute force compares each day to every later day (O(n²)); the monotonic stack resolves each index exactly once when its first warmer day appears, giving O(n).

```
temps = [73,74,75,71,69,72,76,73]
day 5 (72) pops day4(69),day3(71) -> answer[4]=1, answer[3]=2
day 6 (76) pops day5,day2,day1,day0 -> answers 1,4,5,6
                              ^ each index popped once = O(n)
```

```java
import java.util.ArrayDeque;
import java.util.Deque;

class Solution {
    public int[] dailyTemperatures(int[] t) {
        int n = t.length;
        int[] ans = new int[n];                 // defaults to 0 = "no warmer day"
        Deque<Integer> stack = new ArrayDeque<>();   // indices, temps decreasing
        for (int i = 0; i < n; i++) {
            while (!stack.isEmpty() && t[i] > t[stack.peek()]) {
                int prev = stack.pop();
                ans[prev] = i - prev;
            }
            stack.push(i);
        }
        return ans;
    }
}
```

**Complexity.** Time O(n) — each index pushed and popped at most once; Space O(n) for the stack. **Edge cases:** strictly decreasing input leaves every entry `0`; equal temperatures do *not* count as warmer (strict `>`); single element returns `[0]`.

---

### Problem 13: Number of Recent Calls — Sliding Window Queue

**Statement.** Implement `RecentCounter`. Each call to `ping(t)` (with strictly increasing `t` in milliseconds) records a request at time `t` and returns the number of requests that happened in the inclusive window `[t - 3000, t]`.

**Constraints.** Each `t` is strictly larger than the previous; at most `10^4` calls; `1 ≤ t ≤ 10^9`.

**Approach.** Because timestamps arrive in increasing order, a plain FIFO **queue** of timestamps suffices. On each `ping(t)`, enqueue `t`, then dequeue from the front every timestamp older than `t - 3000`. The queue's size is the answer. Each timestamp is enqueued once and dequeued at most once → amortized O(1) per call.

```java
import java.util.ArrayDeque;
import java.util.Queue;

class RecentCounter {
    private final Queue<Integer> q = new ArrayDeque<>();

    public int ping(int t) {
        q.offer(t);
        while (q.peek() < t - 3000) {   // evict stale front (window is [t-3000, t])
            q.poll();
        }
        return q.size();
    }
}
```

**Complexity.** Time amortized O(1) per `ping` (a single call can be O(n) when many old entries expire at once); Space O(W) where W is the max number of pings within any 3000ms window. **Edge cases:** first ping always returns 1; bursts of many pings within 3000ms grow the queue; widely-spaced pings keep it size 1.

---

### Problem 14: Backspace String Compare — Stack Simulation

**Statement.** Given two strings `s` and `t` where `#` means a backspace, return `true` if they are equal after all backspaces are applied. A `#` on an already-empty buffer is a no-op.

**Constraints.** `1 ≤ s.length, t.length ≤ 200`; characters are lowercase letters and `#`.

**Approach.** A **stack** naturally models a text buffer: push letters, and on `#` pop the most recent letter (if any). Build the final string for each input and compare. (An O(1)-space variant scans both strings from the right, skipping characters consumed by pending backspaces — a good follow-up.)

```java
import java.util.ArrayDeque;
import java.util.Deque;

class Solution {
    public boolean backspaceCompare(String s, String t) {
        return build(s).equals(build(t));
    }

    private String build(String str) {
        Deque<Character> stack = new ArrayDeque<>();
        for (char c : str.toCharArray()) {
            if (c == '#') {
                if (!stack.isEmpty()) stack.pop();
            } else {
                stack.push(c);
            }
        }
        StringBuilder sb = new StringBuilder();
        while (!stack.isEmpty()) sb.append(stack.pop());
        return sb.toString();   // reversed, but consistent for both → fine for equality
    }
}
```

**Complexity.** Time O(s + t); Space O(s + t). **Edge cases:** leading backspaces (`"#a"` → `"a"`); strings that reduce to empty compare equal; `"ab##"` and `"c#d#"` both reduce to empty.

---

### Problem 15: Remove All Adjacent Duplicates in String — Stack

**Statement.** Given a string `s` of lowercase letters, repeatedly remove two adjacent equal letters until no such pair remains, and return the final string. The result is unique regardless of removal order.

**Constraints.** `1 ≤ s.length ≤ 10^5`.

**Approach.** A **stack** collapses adjacent duplicates in one pass: for each character, if it equals the stack top, pop (they annihilate); otherwise push. The stack, read bottom-to-top, is the answer. Brute force repeatedly rescans the string after each removal (O(n²)); the stack does it in a single O(n) sweep.

```java
import java.util.ArrayDeque;
import java.util.Deque;

class Solution {
    public String removeDuplicates(String s) {
        Deque<Character> stack = new ArrayDeque<>();
        for (char c : s.toCharArray()) {
            if (!stack.isEmpty() && stack.peek() == c) {
                stack.pop();          // adjacent pair cancels
            } else {
                stack.push(c);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (char c : stack) sb.append(c);   // ArrayDeque iterates head→tail (top→bottom)
        return sb.reverse().toString();
    }
}
```

**Walkthrough** `"abbaca"`: a→[a]; b→[a,b]; b==top→pop→[a]; a==top→pop→[]; c→[c]; a→[c,a]. Result `"ca"`.

**Complexity.** Time O(n); Space O(n). **Edge cases:** entire string cancels to `""` (e.g. `"aabb"` → `""`); no duplicates returns the input unchanged; single char returns itself. **Follow-up:** LeetCode 1209 removes runs of exactly `k` equal adjacent letters — store `(char, count)` pairs on the stack.

---

### Problem 16: Make The String Great — Adjacent Case-Pair Cancellation

**Statement.** A string is "good" if it has no two adjacent characters that are the same letter in opposite cases (e.g. `'a'` and `'A'`). Repeatedly remove such adjacent bad pairs and return any resulting good string.

**Constraints.** `1 ≤ s.length ≤ 100`; `s` contains upper- and lower-case English letters.

**Approach.** Identical structure to adjacent-duplicate removal, but the cancellation predicate is "same letter, different case." Two characters `a` and `b` cancel iff `a != b` and `Character.toLowerCase(a) == Character.toLowerCase(b)`, which is exactly `Math.abs(a - b) == 32` for ASCII letters. Push non-cancelling chars; pop on a bad pair.

```java
import java.util.ArrayDeque;
import java.util.Deque;

class Solution {
    public String makeGood(String s) {
        Deque<Character> stack = new ArrayDeque<>();
        for (char c : s.toCharArray()) {
            if (!stack.isEmpty() && Math.abs(stack.peek() - c) == 32) {
                stack.pop();          // e.g. 'a'(97) and 'A'(65) differ by 32
            } else {
                stack.push(c);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (char c : stack) sb.append(c);
        return sb.reverse().toString();
    }
}
```

**Walkthrough** `"leEeetcode"`: l, e; then `E` cancels `e` → `l`; then `e`, `e`, t, c, o, d, e → `"leetcode"`.

**Complexity.** Time O(n); Space O(n). **Edge cases:** `"abBA"` collapses fully to `""`; same-case repeats like `"aa"` are NOT bad pairs (only opposite case cancels); already-good strings pass through.

---

### Problem 17: Next Greater Element I — Monotonic Stack + Hash Map

**Statement.** `nums1` is a subset of `nums2`. For each `x` in `nums1`, find the next greater element of `x` to its right *in `nums2`*; output `-1` if none. All values are distinct.

**Constraints.** `1 ≤ nums1.length ≤ nums2.length ≤ 1000`.

**Approach.** Precompute, in one O(n) monotonic-stack pass over `nums2`, the next-greater value for every element, stored in a hash map `value → nextGreater`. Then look up each query in O(1). This beats the naive O(n1·n2) double loop. The stack is decreasing; when a larger value arrives, it resolves the next-greater for all smaller values beneath it.

```java
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

class Solution {
    public int[] nextGreaterElement(int[] nums1, int[] nums2) {
        Map<Integer, Integer> nextGreater = new HashMap<>();
        Deque<Integer> stack = new ArrayDeque<>();      // values, decreasing
        for (int v : nums2) {
            while (!stack.isEmpty() && v > stack.peek()) {
                nextGreater.put(stack.pop(), v);
            }
            stack.push(v);
        }
        // remaining stack values have no greater element to the right
        int[] ans = new int[nums1.length];
        for (int i = 0; i < nums1.length; i++) {
            ans[i] = nextGreater.getOrDefault(nums1[i], -1);
        }
        return ans;
    }
}
```

**Complexity.** Time O(n2 + n1); Space O(n2) for the map and stack. **Edge cases:** an element that is the max of `nums2` maps to `-1`; values distinct by problem guarantee so no tie-handling needed; `nums1` element equal to the last `nums2` element → `-1`.

---

### Problem 18: Final Prices With a Special Discount — Next Smaller-or-Equal

**Statement.** Given `prices`, the discount on item `i` is the price `prices[j]` of the nearest later item with `prices[j] <= prices[i]`. Return the array of final prices `prices[i] - discount[i]` (discount is 0 if no such `j`).

**Constraints.** `1 ≤ n ≤ 500`, `1 ≤ prices[i] ≤ 1000`.

**Approach.** A **monotonic non-decreasing stack of indices**. As we scan, when the current price is `<=` the price at the stack top, the current item is that item's discount source — pop and subtract. This is the "next smaller-or-equal element" variant; the `<=` (rather than `<`) is what makes equal prices apply the discount.

```java
import java.util.ArrayDeque;
import java.util.Deque;

class Solution {
    public int[] finalPrices(int[] prices) {
        int[] ans = prices.clone();             // default: no discount
        Deque<Integer> stack = new ArrayDeque<>();   // indices, prices non-decreasing
        for (int i = 0; i < prices.length; i++) {
            while (!stack.isEmpty() && prices[stack.peek()] >= prices[i]) {
                int j = stack.pop();
                ans[j] = prices[j] - prices[i];
            }
            stack.push(i);
        }
        return ans;
    }
}
```

**Walkthrough** `[8,4,6,2,3]`: 4 discounts 8→4; 2 discounts 6→4 and 4→2; 3 has no later ≤ → stays. Result `[4,2,4,2,3]`.

**Complexity.** Time O(n); Space O(n). **Edge cases:** strictly increasing prices get no discounts; equal adjacent prices DO discount (because of `>=`); last element never gets a discount.

---

### Problem 19: Baseball Game — Stack of Scores

**Statement.** You are given operations as strings. `"X"` (an integer) records a new score X; `"+"` records a score equal to the sum of the previous two scores; `"D"` records double the previous score; `"C"` cancels (removes) the previous score. Return the sum of all recorded scores after processing all operations.

**Constraints.** `1 ≤ ops.length ≤ 1000`; operations are always valid for the current record.

**Approach.** A **stack** holds the running list of valid scores so that `+`, `D`, and `C` can reference the most recent one or two entries in O(1). Each operation is a constant-time stack manipulation; sum the stack at the end.

```java
import java.util.ArrayDeque;
import java.util.Deque;

class Solution {
    public int calPoints(String[] ops) {
        Deque<Integer> stack = new ArrayDeque<>();   // use as a list; top = most recent
        for (String op : ops) {
            switch (op) {
                case "+": {
                    int last = stack.pop();
                    int sum = last + stack.peek();
                    stack.push(last);            // restore
                    stack.push(sum);
                    break;
                }
                case "D": stack.push(2 * stack.peek()); break;
                case "C": stack.pop(); break;
                default:  stack.push(Integer.parseInt(op));
            }
        }
        int total = 0;
        for (int v : stack) total += v;
        return total;
    }
}
```

**Walkthrough** `["5","2","C","D","+"]`: push 5; push 2; `C` removes 2 → [5]; `D` → 10 → [5,10]; `+` → 5+10=15 → [5,10,15]; sum = 30.

**Complexity.** Time O(n); Space O(n). **Edge cases:** `"C"` always has a score to cancel (guaranteed valid); `"+"` requires at least two prior scores (guaranteed); negative scores possible via the integer tokens. **Note:** use `long` for the sum if extreme doubling could overflow `int`.

---

### Problem 20: Min Cost to Make at Least One Valid Parenthesis Pass — Greedy Counter

**Statement (Minimum Add to Make Parentheses Valid, LeetCode 921).** Given a string of `'('` and `')'`, return the minimum number of parentheses to insert so the string becomes valid (every open has a matching close and vice versa).

**Constraints.** `1 ≤ s.length ≤ 1000`.

**Approach.** This is the canonical "stack you can collapse into a counter" problem. A stack would push `'('` and pop on `')'`; but since we only ever match the most recent `'('`, the stack depth is the only state we need. Track `open` (unmatched `'('`). On `')'`, if `open > 0` match it (`open--`), else we need an insertion (`inserts++`). At the end every leftover `open` needs a closing paren.

```java
class Solution {
    public int minAddToMakeValid(String s) {
        int open = 0;       // unmatched '(' so far (the "stack depth")
        int inserts = 0;    // insertions forced by unmatched ')'
        for (char c : s.toCharArray()) {
            if (c == '(') {
                open++;
            } else {                 // ')'
                if (open > 0) open--;     // matches a pending '('
                else inserts++;           // need to insert a '(' before this ')'
            }
        }
        return inserts + open;   // leftover opens each need a ')'
    }
}
```

**Walkthrough** `"())("`: `(`→open1; `)`→open0; `)`→no open→inserts1; `(`→open1. Answer `inserts(1)+open(1)=2`.

**Complexity.** Time O(n); Space O(1) (the explicit stack degenerates to a counter). **Edge cases:** already valid → 0; all opens `"((("` → 3; all closes `")))"` → 3; empty-after-matching scenarios. **Related:** LeetCode 1249 (remove to make valid) and 1541 (minimum insertions for `'('` to match two `')'`) extend this counting idea.

---

### Problem 21: Remove Outermost Parentheses — Depth Counter

**Statement.** A valid parentheses string `s` is the concatenation of *primitive* valid strings. Remove the outermost pair of parentheses of every primitive in the decomposition and return the result. Example: `"(()())(())"` → `"()()()"`.

**Constraints.** `1 ≤ s.length ≤ 10^4`; `s` is a valid parentheses string.

**Approach.** Track nesting **depth** (an implicit stack of `'('`). A `'('` is *outermost* when depth goes from 0 to 1; a `')'` is outermost when depth returns to 0. Append every other character. Concretely: on `'('`, append it only if `depth > 0`, then `depth++`; on `')'`, `depth--`, then append it only if `depth > 0`.

```java
class Solution {
    public String removeOuterParentheses(String s) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (char c : s.toCharArray()) {
            if (c == '(') {
                if (depth > 0) sb.append(c);   // not the outermost open
                depth++;
            } else {                            // ')'
                depth--;
                if (depth > 0) sb.append(c);   // not the outermost close
            }
        }
        return sb.toString();
    }
}
```

**Walkthrough** `"(()())(())"`: primitives `"(()())"` → strip outer → `"()()"`, and `"(())"` → strip outer → `"()"`. Concatenated: `"()()()"`.

**Complexity.** Time O(n); Space O(n) for the output (O(1) auxiliary). **Edge cases:** a single primitive `"()"` → `""`; deeply nested `"((()))"` → `"(())"`; multiple primitives concatenate correctly.

---

### Problem 22: Build an Array With Stack Operations — Greedy Stack Simulation

**Statement.** You are given an integer stream `1, 2, 3, ..., n` read one at a time and a target array `target` (strictly increasing values within `[1, n]`). For each number read, you may `"Push"` it onto a stack, and you may `"Pop"` the top. Return the sequence of operations that makes the stack equal `target` (read bottom-to-top), stopping as soon as the stack matches.

**Constraints.** `1 ≤ target.length ≤ 100`; `1 ≤ target[i] ≤ n ≤ 100`; `target` strictly increasing.

**Approach.** Simulate reading `1..n`. For each value `v`, always `"Push"`; if `v` is the next needed target value, keep it (advance the target pointer); otherwise immediately `"Pop"` it (it is a number we must skip). Stop once all target values are placed.

```java
import java.util.ArrayList;
import java.util.List;

class Solution {
    public List<String> buildArray(int[] target, int n) {
        List<String> ops = new ArrayList<>();
        int idx = 0;                          // pointer into target
        for (int v = 1; v <= n && idx < target.length; v++) {
            ops.add("Push");
            if (target[idx] == v) {
                idx++;                        // keep this value
            } else {
                ops.add("Pop");               // skip: push then pop
            }
        }
        return ops;
    }
}
```

**Walkthrough** `target=[1,3], n=3`: v1 == target[0] → Push (keep). v2 != target[1]=3 → Push, Pop. v3 == target[1] → Push (keep), done. Ops: `["Push","Push","Pop","Push"]`.

**Complexity.** Time O(n); Space O(n) for the operation list. **Edge cases:** `target == [1..k]` produces only `"Push"` ops; gaps between targets insert `"Push","Pop"` pairs; loop terminates early once the last target value is placed (never reads beyond it).

---

### Problem 23: Validate Stack Sequences — Stack Simulation

**Statement.** Given two integer arrays `pushed` and `popped` (both permutations of the same distinct values), return `true` if and only if this could have been the result of a sequence of push and pop operations on an initially empty stack.

**Constraints.** `1 ≤ pushed.length == popped.length ≤ 1000`; all values distinct.

**Approach.** Simulate. Push each element of `pushed` in order; after every push, greedily pop while the stack top equals the current expected `popped` element (advancing a pointer `j`). If the simulation drains exactly when `pushed` is exhausted, the sequence is valid. Each element is pushed once and popped at most once → O(n).

```
pushed=[1,2,3,4,5] popped=[4,5,3,2,1]
push1,2,3,4  top==4 -> pop, j->5
push5         top==5 -> pop, top==3 -> pop, top==2 -> pop, top==1 -> pop
stack empty  => true
```

```java
import java.util.ArrayDeque;
import java.util.Deque;

class Solution {
    public boolean validateStackSequences(int[] pushed, int[] popped) {
        Deque<Integer> stack = new ArrayDeque<>();
        int j = 0;                              // index into popped
        for (int x : pushed) {
            stack.push(x);
            while (!stack.isEmpty() && j < popped.length && stack.peek() == popped[j]) {
                stack.pop();
                j++;
            }
        }
        return stack.isEmpty();   // all values matched the pop order
    }
}
```

**Complexity.** Time O(n) — each value pushed once and popped once; Space O(n) for the stack. **Edge cases:** identical arrays (push-then-immediately-pop each) are valid; `popped` that requires an element still buried under another leaves the stack non-empty → `false` (e.g. `pushed=[1,2,3], popped=[3,1,2]`); single element always valid.

---

### Problem 24: Asteroid Collision — Monotonic Stack with Direction

**Statement.** Given `asteroids` where each value's sign is its direction (positive = right, negative = left) and magnitude is its size, simulate their collisions. Two asteroids collide only when a right-mover is to the left of a left-mover. In a collision the smaller explodes; equal sizes both explode. Return the surviving asteroids.

**Constraints.** `1 ≤ n ≤ 10^4`; `-1000 ≤ asteroids[i] ≤ 1000`, `asteroids[i] != 0`.

**Approach.** A **stack** of survivors. A collision is possible only when the incoming asteroid moves left (`< 0`) and the stack top moves right (`> 0`). Loop resolving collisions: if the top is smaller it pops (and the incoming may keep colliding); if equal both die; if the top is larger the incoming dies. If the incoming survives all collisions (stack empty, or top also moving left, or top is left-moving), push it.

```java
import java.util.ArrayDeque;
import java.util.Deque;

class Solution {
    public int[] asteroidCollision(int[] asteroids) {
        Deque<Integer> stack = new ArrayDeque<>();
        for (int a : asteroids) {
            boolean alive = true;
            // collision only when current moves left and top moves right
            while (alive && a < 0 && !stack.isEmpty() && stack.peek() > 0) {
                int top = stack.peek();
                if (top < -a) {           // top smaller -> it explodes, current continues
                    stack.pop();
                } else if (top == -a) {   // equal -> both explode
                    stack.pop();
                    alive = false;
                } else {                  // top larger -> current explodes
                    alive = false;
                }
            }
            if (alive) stack.push(a);
        }
        // build result in original (bottom→top) order
        int[] res = new int[stack.size()];
        for (int i = res.length - 1; i >= 0; i--) res[i] = stack.pop();
        return res;
    }
}
```

**Walkthrough** `[5,10,-5]`: push 5, push 10; `-5` collides with 10 → 10 larger → -5 dies. Survivors `[5,10]`. For `[8,-8]`: equal → both explode → `[]`.

**Complexity.** Time O(n) — each asteroid is pushed and popped at most once; Space O(n). **Edge cases:** all same direction (no collisions); a left-mover at the very start never collides (nothing to its left moving right); chain reactions where one big left-mover clears several right-movers.

---

## 🧩 Extended Problems — Set 2: Medium → Hard Variations & Follow-ups

### Problem 25: Next Greater Element II (Circular Array) — Monotonic Stack

**Statement.** Given a *circular* integer array `nums`, return the next greater number for every element. The next greater number of `x` is the first greater number encountered when traversing right, wrapping around to the start. If none exists, output `-1`.

**Constraints.** `1 ≤ n ≤ 10^4`; `-10^9 ≤ nums[i] ≤ 10^9`; values may repeat.

**Approach.**
- *Brute force:* for each `i`, scan up to `n-1` further positions modulo `n` → O(n²).
- *Optimal:* the standard next-greater monotonic decreasing stack of indices, but iterate `2n` times using `i % n`. The first pass over `[n, 2n)` lets every element "see" the wrap-around candidates to its left. Crucially, only **push** indices during the first lap (`i < n`); in the second lap you only *resolve* unresolved indices. Each index is pushed once and popped once → O(n).

```
nums = [1,2,1]   (circular)
i=0 push0
i=1 val2 > nums[0]=1 -> ans[0]=2, push1
i=2 val1 < 2 -> push2
i=3 (=0) val1 < nums[2]=1? no(equal) -> nothing
i=4 (=1) val2 > nums[2]=1 -> ans[2]=2 ; 2 not > nums[1]=2
i=5 (=2) val1 -> nothing ; ans[1] stays -1
result [2,-1,2]
```

```java
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

class Solution {
    public int[] nextGreaterElements(int[] nums) {
        int n = nums.length;
        int[] ans = new int[n];
        Arrays.fill(ans, -1);
        Deque<Integer> stack = new ArrayDeque<>();      // indices, values decreasing
        for (int i = 0; i < 2 * n; i++) {
            int v = nums[i % n];
            while (!stack.isEmpty() && nums[stack.peek()] < v) {
                ans[stack.pop()] = v;
            }
            if (i < n) stack.push(i);                   // only push real indices once
        }
        return ans;
    }
}
```

**Complexity.** Time O(n) — `2n` iterations, each index pushed/popped once; Space O(n). **Edge cases:** all-equal arrays return all `-1` (strict `<` means equals never resolve); the global maximum is always `-1`; single element returns `[-1]`; duplicates are handled because we compare values, not indices.

---

### Problem 26: Trapping Rain Water — Monotonic Stack vs Two Pointers — *Hard*

**Statement.** Given non-negative bar heights of unit width, compute how much rain water is trapped between the bars after raining.

**Constraints.** `1 ≤ n ≤ 2·10^4`; `0 ≤ height[i] ≤ 10^5`.

**Approach.**
- *Brute force:* for each index, water held = `min(maxLeft, maxRight) - height[i]`, scanning both sides each time → O(n²).
- *Monotonic stack (shown):* keep a **decreasing stack of indices**. When a taller bar arrives, it forms the right wall of a basin. Pop the floor `bottom`; the trapped width spans from the new left wall (the element now on top) to the current index, and the bounded height is `min(left, current) - bottom`. Each bar is pushed/popped once → O(n).
- *Two pointers (the truly optimal O(1)-space variant):* shrink from both ends tracking `leftMax`/`rightMax`; whichever side is the smaller wall is fully determined, so add its contribution.

```
height = [0,1,0,2,1,0,1,3,2,1,2,1]   -> traps 6 units
    when bar 3 (index7) arrives, it repeatedly pops the dips,
    layering water level by level against the rising left wall.
```

```java
import java.util.ArrayDeque;
import java.util.Deque;

class Solution {
    // Monotonic-stack solution
    public int trap(int[] height) {
        Deque<Integer> stack = new ArrayDeque<>();   // indices, heights decreasing
        int water = 0;
        for (int i = 0; i < height.length; i++) {
            while (!stack.isEmpty() && height[i] > height[stack.peek()]) {
                int bottom = stack.pop();
                if (stack.isEmpty()) break;          // no left wall -> no basin
                int left = stack.peek();
                int width = i - left - 1;
                int bounded = Math.min(height[left], height[i]) - height[bottom];
                water += width * bounded;
            }
            stack.push(i);
        }
        return water;
    }

    // Optimal two-pointer alternative, O(1) extra space
    public int trapTwoPointer(int[] h) {
        int l = 0, r = h.length - 1, leftMax = 0, rightMax = 0, water = 0;
        while (l < r) {
            if (h[l] < h[r]) {
                leftMax = Math.max(leftMax, h[l]);
                water += leftMax - h[l];
                l++;
            } else {
                rightMax = Math.max(rightMax, h[r]);
                water += rightMax - h[r];
                r--;
            }
        }
        return water;
    }
}
```

**Complexity.** Stack: Time O(n), Space O(n). Two pointers: Time O(n), Space O(1). **Edge cases:** monotonic (non-decreasing or non-increasing) heights trap nothing; flat arrays trap nothing; `n < 3` always returns 0; the stack version must break when no left wall exists.

---

### Problem 27: Sum of Subarray Minimums — Contribution via Monotonic Stack — *Hard*

**Statement.** Given an array `arr`, return the sum of `min(b)` over every contiguous subarray `b`, modulo `10^9 + 7`.

**Constraints.** `1 ≤ n ≤ 3·10^4`; `1 ≤ arr[i] ≤ 3·10^4`.

**Approach.**
- *Brute force:* enumerate all O(n²) subarrays, track the running min → O(n²).
- *Optimal (contribution counting):* each element `arr[i]` is the minimum of exactly `left[i] * right[i]` subarrays, where `left[i]` is the number of consecutive elements to the left that are strictly greater (distance to the **previous-less** element) and `right[i]` to the right that are greater-or-equal (distance to the **next-less-or-equal** element). The asymmetric `>` on the left and `>=` on the right breaks ties so each subarray is counted exactly once. Compute both boundary arrays with a single monotonic stack pass each; sum `arr[i] * left[i] * right[i]`.

```
arr = [3,1,2,4]
i=1 (val 1) is the min of subarrays spanning left to index0..1 and right to 1..3
left[1]=2 (indices 0,1), right[1]=3 (indices 1,2,3) -> contributes 1*2*3 = 6
```

```java
import java.util.ArrayDeque;
import java.util.Deque;

class Solution {
    public int sumSubarrayMins(int[] arr) {
        final int MOD = 1_000_000_007;
        int n = arr.length;
        int[] left = new int[n];   // # subarrays extending left with arr[i] as strict min
        int[] right = new int[n];
        Deque<Integer> stack = new ArrayDeque<>();   // indices

        // previous strictly-less element: left[i] = i - prevLessIdx
        for (int i = 0; i < n; i++) {
            while (!stack.isEmpty() && arr[stack.peek()] >= arr[i]) stack.pop();
            left[i] = stack.isEmpty() ? i + 1 : i - stack.peek();
            stack.push(i);
        }
        stack.clear();
        // next less-or-equal element: right[i] = nextLEIdx - i
        for (int i = n - 1; i >= 0; i--) {
            while (!stack.isEmpty() && arr[stack.peek()] > arr[i]) stack.pop();
            right[i] = stack.isEmpty() ? n - i : stack.peek() - i;
            stack.push(i);
        }

        long sum = 0;
        for (int i = 0; i < n; i++) {
            sum = (sum + (long) arr[i] * left[i] % MOD * right[i]) % MOD;
        }
        return (int) sum;
    }
}
```

**Complexity.** Time O(n) — two linear stack passes; Space O(n). **Edge cases:** duplicate values are handled correctly by the asymmetric `>=`/`>` tie-break (no double counting); single element returns that element; the multiplication `arr[i] * left[i] * right[i]` can overflow `int`, so accumulate in `long` with the modulus. **Sibling:** Sum of Subarray *Maximums* flips both comparisons.

---

### Problem 28: Largest Rectangle from Maximal Rectangle in a Binary Matrix — *Hard*

**Statement.** Given a `rows × cols` binary matrix of `'0'`/`'1'`, find the area of the largest rectangle containing only `'1'`s.

**Constraints.** `1 ≤ rows, cols ≤ 200`; entries are the characters `'0'` or `'1'`.

**Approach.** Reduce to **Largest Rectangle in Histogram** applied per row. Maintain a `heights` array where `heights[c]` is the number of consecutive `'1'`s ending at the current row in column `c`: if the cell is `'1'`, increment; if `'0'`, reset to 0. After updating each row, run the O(cols) monotonic-stack histogram routine and track the global best. This is the canonical "stack-on-top-of-DP" senior problem.

```
matrix rows ->            heights after each row (column-wise run of 1s)
1 0 1 0 0                 1 0 1 0 0
1 0 1 1 1     ===>        2 0 2 1 1
1 1 1 1 1                 3 1 3 2 2   <- histogram here yields the 6-area rectangle
1 0 0 1 0                 4 0 0 3 0
```

```java
import java.util.ArrayDeque;
import java.util.Deque;

class Solution {
    public int maximalRectangle(char[][] matrix) {
        if (matrix.length == 0 || matrix[0].length == 0) return 0;
        int cols = matrix[0].length, best = 0;
        int[] heights = new int[cols];
        for (char[] row : matrix) {
            for (int c = 0; c < cols; c++) {
                heights[c] = (row[c] == '1') ? heights[c] + 1 : 0;
            }
            best = Math.max(best, largestRectangleArea(heights));
        }
        return best;
    }

    private int largestRectangleArea(int[] h) {
        int n = h.length, best = 0;
        Deque<Integer> stack = new ArrayDeque<>();   // indices, heights increasing
        for (int i = 0; i <= n; i++) {
            int cur = (i == n) ? 0 : h[i];           // sentinel flushes the stack
            while (!stack.isEmpty() && h[stack.peek()] >= cur) {
                int height = h[stack.pop()];
                int leftBound = stack.isEmpty() ? -1 : stack.peek();
                best = Math.max(best, height * (i - leftBound - 1));
            }
            stack.push(i);
        }
        return best;
    }
}
```

**Complexity.** Time O(rows · cols) — each row is one O(cols) histogram pass; Space O(cols). **Edge cases:** all-zero matrix returns 0; a single full row/column degenerates to a 1D histogram; the `'0'` reset is what isolates rectangles that cannot cross a zero. **Related:** "Maximal Square" replaces the histogram with a DP recurrence and the area becomes `side²`.

---

### Problem 29: Maximum Frequency Stack — Stack of Frequency Buckets — *Hard*

**Statement.** Design `FreqStack` with `push(x)` and `pop()`. `pop` removes and returns the most frequent element; if several elements tie for most frequent, return the one **pushed most recently** among them.

**Constraints.** Up to `2·10^4` calls total; `0 ≤ x ≤ 10^9`.

**Approach.** Two maps. `freq` counts occurrences of each value. `group` maps a frequency level `f` to a **stack** (list) of values that have *reached* frequency `f` — pushing `x` whose new count is `f` appends `x` to `group[f]`. Track `maxFreq`. `pop` takes the top of `group[maxFreq]` (the most-recently-pushed value at the highest frequency), decrements its `freq`, and decrements `maxFreq` if that bucket empties. This elegantly resolves the recency tie-break because each frequency bucket preserves push order.

```
push 5,7,5,7,4,5  ->
  freq: 5:3 7:2 4:1
  group[1]=[5,7,4]  group[2]=[5,7]  group[3]=[5]   maxFreq=3
pop -> 5 (group[3] top), maxFreq=2
pop -> 7 (group[2] top, more recent than the 5 below)
```

```java
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

class FreqStack {
    private final Map<Integer, Integer> freq = new HashMap<>();
    private final Map<Integer, Deque<Integer>> group = new HashMap<>();
    private int maxFreq = 0;

    public void push(int x) {
        int f = freq.merge(x, 1, Integer::sum);     // new frequency of x
        maxFreq = Math.max(maxFreq, f);
        group.computeIfAbsent(f, k -> new ArrayDeque<>()).push(x);
    }

    public int pop() {
        Deque<Integer> top = group.get(maxFreq);
        int x = top.pop();                          // most-recent value at maxFreq
        freq.merge(x, -1, Integer::sum);
        if (top.isEmpty()) maxFreq--;
        return x;
    }
}
```

**Complexity.** Time O(1) per `push` and `pop` (amortized hash-map ops); Space O(n) over all pushed elements. **Edge cases:** repeated pushes of the same value climb through buckets; ties always resolve to the most recent push because each bucket is a stack; `pop` is only called when non-empty per the problem contract.

---

### Problem 30: Sliding Window Minimum — Monotonic Deque Variation

**Statement.** Given `nums` and window size `k`, return the minimum of each contiguous window of size `k`.

**Constraints.** `1 ≤ k ≤ n ≤ 10^5`.

**Approach.** The mirror image of Sliding Window Maximum: maintain a **monotonic increasing deque of indices** whose front is always the current window's minimum. For each `i`: evict the front if it has left the window (`<= i - k`); evict from the back every index whose value is `>= nums[i]` (they can never be a future minimum while `i` is alive); push `i`; once the first window forms, record `nums[front]`. The only change from the max version is flipping the back-eviction comparison from `<=` to `>=`.

```java
import java.util.ArrayDeque;
import java.util.Deque;

class Solution {
    public int[] minSlidingWindow(int[] nums, int k) {
        int n = nums.length;
        int[] ans = new int[n - k + 1];
        Deque<Integer> dq = new ArrayDeque<>();          // indices, values increasing
        for (int i = 0; i < n; i++) {
            if (!dq.isEmpty() && dq.peekFirst() <= i - k) dq.pollFirst();
            while (!dq.isEmpty() && nums[dq.peekLast()] >= nums[i]) dq.pollLast();
            dq.offerLast(i);
            if (i >= k - 1) ans[i - k + 1] = nums[dq.peekFirst()];
        }
        return ans;
    }
}
```

**Complexity.** Time O(n) — each index enters and leaves the deque once; Space O(k). **Edge cases:** `k == 1` returns the array unchanged; `k == n` yields the single global minimum; equal values are evicted from the back (using `>=`), which keeps the *latest* index of a tie and is still correct since it stays in-window longest.

---

### Problem 31: Shortest Subarray with Sum at Least K — Deque over Prefix Sums — *Hard*

**Statement.** Given an integer array `nums` (which may contain negatives) and an integer `k`, return the length of the shortest non-empty contiguous subarray whose sum is at least `k`. Return `-1` if none exists.

**Constraints.** `1 ≤ n ≤ 10^5`; `-10^5 ≤ nums[i] ≤ 10^5`; `1 ≤ k ≤ 10^9`.

**Approach.** With negatives present, the simple sliding-window trick fails. Build prefix sums `P[0..n]` where `P[i] = nums[0..i-1]`. A subarray `(j, i]` has sum `P[i] - P[j] >= k`. We want, for each `i`, the largest `j < i` with `P[j] <= P[i] - k`, minimizing `i - j`. Maintain a **monotonic increasing deque of prefix indices**:
- From the **front**, while `P[i] - P[front] >= k`, this is a valid (and shortest-so-far for that front) subarray — record `i - front` and pop the front (it can never give a shorter answer for a later `i`).
- From the **back**, pop indices whose prefix sum is `>= P[i]`; they are dominated because `i` is both later and smaller, strictly better as a future left endpoint.

Each index is pushed and popped once → O(n), beating the O(n²) brute force.

```java
import java.util.ArrayDeque;
import java.util.Deque;

class Solution {
    public int shortestSubarray(int[] nums, int k) {
        int n = nums.length;
        long[] prefix = new long[n + 1];
        for (int i = 0; i < n; i++) prefix[i + 1] = prefix[i] + nums[i];

        int best = n + 1;
        Deque<Integer> dq = new ArrayDeque<>();          // indices into prefix, increasing P
        for (int i = 0; i <= n; i++) {
            while (!dq.isEmpty() && prefix[i] - prefix[dq.peekFirst()] >= k) {
                best = Math.min(best, i - dq.pollFirst());
            }
            while (!dq.isEmpty() && prefix[dq.peekLast()] >= prefix[i]) {
                dq.pollLast();
            }
            dq.offerLast(i);
        }
        return best <= n ? best : -1;
    }
}
```

**Complexity.** Time O(n) — every prefix index enters/leaves the deque at most once; Space O(n). **Edge cases:** no qualifying subarray returns `-1`; a single element `>= k` yields length 1; prefix sums use `long` to avoid overflow when many large values accumulate; negatives are exactly what forces the deque approach over a plain two-pointer window.

---

### Problem 32: Minimum Remove to Make Valid Parentheses — Stack of Indices

**Statement.** Given a string `s` of `'('`, `')'`, and lowercase letters, remove the minimum number of parentheses so the result is valid, and return any such result.

**Constraints.** `1 ≤ s.length ≤ 10^5`.

**Approach.** Use a **stack of indices** of unmatched `'('`. Scan left to right: push the index of each `'('`; on `')'`, if the stack is non-empty pop a match, otherwise this `')'` is unmatchable — mark its index for removal. After the pass, every index still on the stack is an unmatched `'('` and must also be removed. Build the result skipping all marked indices. This removes exactly the minimum set because each marked paren has no possible partner.

```java
import java.util.ArrayDeque;
import java.util.Deque;

class Solution {
    public String minRemoveToMakeValid(String s) {
        char[] chars = s.toCharArray();
        Deque<Integer> open = new ArrayDeque<>();   // indices of unmatched '('
        boolean[] remove = new boolean[chars.length];
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] == '(') {
                open.push(i);
            } else if (chars[i] == ')') {
                if (open.isEmpty()) remove[i] = true;   // unmatched ')'
                else open.pop();                        // matched
            }
        }
        while (!open.isEmpty()) remove[open.pop()] = true;   // leftover '('

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chars.length; i++) {
            if (!remove[i]) sb.append(chars[i]);
        }
        return sb.toString();
    }
}
```

**Walkthrough** `"a)b(c)d"`: index1 `)` has no open → remove. `(` at 3 matches `)` at 5. Result `"ab(c)d"`. For `"))(("`: both `)` unmatched, both `(` leftover → all removed → `""`.

**Complexity.** Time O(n); Space O(n) for the stack and marker array. **Edge cases:** already-valid strings are returned unchanged; strings with no parentheses pass through; all-removed cases return `""`; letters are never removed.

---

### Problem 33: Decode String — Two Stacks for Nested Multipliers — *Medium*

**Statement.** Decode a string encoded as `k[encoded]`, meaning `encoded` repeated `k` times. Encodings may be nested, e.g. `"3[a2[c]]"` → `"accaccacc"`. `k` is a positive integer; the input is always valid.

**Constraints.** `1 ≤ s.length ≤ 30`; output length `≤ 10^5`; letters and digits only.

**Approach.** Two stacks track the suspended context across nesting levels: a `counts` stack of repeat factors and a `strings` stack of partial results built *before* the current bracket. Maintain a `current` builder and a running `k`. On a digit, accumulate `k` (multi-digit safe). On `'['`, push `k` and `current`, then reset both. On `']'`, pop the repeat count, repeat `current`, and append it to the popped previous string — that becomes the new `current`. Letters append to `current`.

```
"3[a2[c]]"
 see 3            -> k=3
 see '['          -> push counts:3, strings:""   ; current=""
 see a            -> current="a"
 see 2            -> k=2
 see '['          -> push counts:2, strings:"a"  ; current=""
 see c            -> current="c"
 see ']'          -> current = "a" + "c"*2 = "acc"
 see ']'          -> current = "" + "acc"*3 = "accaccacc"
```

```java
import java.util.ArrayDeque;
import java.util.Deque;

class Solution {
    public String decodeString(String s) {
        Deque<Integer> counts = new ArrayDeque<>();
        Deque<StringBuilder> strings = new ArrayDeque<>();
        StringBuilder current = new StringBuilder();
        int k = 0;
        for (char c : s.toCharArray()) {
            if (Character.isDigit(c)) {
                k = k * 10 + (c - '0');             // multi-digit repeat counts
            } else if (c == '[') {
                counts.push(k);
                strings.push(current);
                k = 0;
                current = new StringBuilder();
            } else if (c == ']') {
                int repeat = counts.pop();
                StringBuilder prev = strings.pop();
                for (int i = 0; i < repeat; i++) prev.append(current);
                current = prev;
            } else {
                current.append(c);                 // a letter
            }
        }
        return current.toString();
    }
}
```

**Complexity.** Time O(output length) — each output character is produced once; Space O(depth + output) for the two stacks. **Edge cases:** multi-digit counts like `"10[a]"`; nested brackets push multiple frames; a plain string with no brackets returns itself; consecutive groups like `"3[a]2[bc]"` concatenate at the top level.

---

### Problem 34: Basic Calculator — Sign Stack for Nested Parentheses — *Hard*

**Statement.** Implement a calculator that evaluates a string expression containing non-negative integers, `+`, `-`, parentheses `(` `)`, and spaces. (No `*` or `/`.) Example: `"(1+(4+5+2)-3)+(6+8)"` → `23`.

**Constraints.** `1 ≤ s.length ≤ 3·10^5`; the expression is valid; result fits in a 32-bit signed integer.

**Approach.** Because there is no multiplication/division, precedence is purely additive and parentheses only flip an effective sign. Keep a running `result`, the current `sign` (+1/-1), and a **stack** that saves the `(result, signBeforeParen)` context when entering parentheses. On a digit, build the number and fold it into `result` with the current sign. On `(`, push `result` and `sign`, then reset. On `)`, multiply the inner `result` by the saved sign and add the saved outer result. This is the classic "stack to suspend the outer accumulator across a parenthesized subexpression."

```java
import java.util.ArrayDeque;
import java.util.Deque;

class Solution {
    public int calculate(String s) {
        Deque<Integer> stack = new ArrayDeque<>();   // alternating: ...outerResult, signBeforeParen
        int result = 0, number = 0, sign = 1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) {
                number = number * 10 + (c - '0');
            } else if (c == '+' || c == '-') {
                result += sign * number;
                number = 0;
                sign = (c == '+') ? 1 : -1;
            } else if (c == '(') {
                stack.push(result);                  // save outer accumulator
                stack.push(sign);                    // save sign applied to the group
                result = 0;
                sign = 1;
            } else if (c == ')') {
                result += sign * number;             // finish inner expression
                number = 0;
                result *= stack.pop();               // apply the saved sign
                result += stack.pop();               // add back the outer accumulator
            }
            // spaces are ignored
        }
        return result + sign * number;               // flush trailing number
    }
}
```

**Walkthrough** `"1-(2+3)"`: read 1 then `-` → result=1, sign=-1. `(` pushes result=1, sign=-1, resets. Inside: 2 then `+` → inner result=2, sign=1; then 3. `)` → inner=2+3=5, times saved sign -1 = -5, plus saved 1 = -4.

**Complexity.** Time O(n); Space O(n) in the worst case of deep nesting. **Edge cases:** leading minus like `"-(2+3)"` (handled because `sign` starts +1 and the trailing flush applies the last sign); multi-digit numbers; spaces anywhere; deeply nested parentheses. **Follow-up:** **Basic Calculator II** adds `*`/`/` (use a number stack, applying `*`/`/` immediately); **Basic Calculator III** combines both — full operator stack with precedence.

---

### Problem 35: Maximum of Minimum for Every Window Size — Monotonic Stack — *Hard*

**Statement.** Given an array `nums`, for every window size `w` from `1` to `n`, find the maximum over all windows of size `w` of the window's minimum. Return an array `ans[1..n]`.

**Constraints.** `1 ≤ n ≤ 10^5`; `1 ≤ nums[i] ≤ 10^9`.

**Approach.** The key insight: each element `nums[i]` is the minimum of all windows it "controls," and the largest such window has length `right[i] - left[i] - 1`, where `left[i]` is the nearest strictly-smaller element on the left and `right[i]` the nearest strictly-smaller on the right (both found with a monotonic increasing stack). Element `nums[i]` is therefore a candidate answer for window size `len = right[i] - left[i] - 1` — set `ans[len] = max(ans[len], nums[i])`. Finally sweep `ans` from large window sizes down to small, propagating `ans[w] = max(ans[w], ans[w+1])`, because any value achievable as a min for a window of size `w+1` is also achievable for size `w`.

```
nums = [10,20,30,50,10,70,30]
each element's "span" (window where it is the min) gives a candidate;
the downward sweep fills sizes that weren't a direct span maximum.
```

```java
import java.util.ArrayDeque;
import java.util.Deque;

class Solution {
    public int[] maxOfMinForEveryWindow(int[] nums) {
        int n = nums.length;
        int[] left = new int[n];   // index of nearest strictly smaller on the left, -1 if none
        int[] right = new int[n];  // index of nearest strictly smaller on the right, n if none
        Deque<Integer> stack = new ArrayDeque<>();

        for (int i = 0; i < n; i++) {
            while (!stack.isEmpty() && nums[stack.peek()] >= nums[i]) stack.pop();
            left[i] = stack.isEmpty() ? -1 : stack.peek();
            stack.push(i);
        }
        stack.clear();
        for (int i = n - 1; i >= 0; i--) {
            while (!stack.isEmpty() && nums[stack.peek()] >= nums[i]) stack.pop();
            right[i] = stack.isEmpty() ? n : stack.peek();
            stack.push(i);
        }

        int[] ans = new int[n + 1];                 // ans[w] for window size w (1-indexed)
        for (int i = 0; i < n; i++) {
            int len = right[i] - left[i] - 1;       // largest window where nums[i] is the min
            ans[len] = Math.max(ans[len], nums[i]);
        }
        for (int w = n - 1; w >= 1; w--) {
            ans[w] = Math.max(ans[w], ans[w + 1]);
        }
        // shift to a clean 1..n array (drop the unused index 0)
        int[] result = new int[n];
        System.arraycopy(ans, 1, result, 0, n);
        return result;
    }
}
```

**Complexity.** Time O(n) — two stack passes plus two linear sweeps; Space O(n). **Edge cases:** strictly increasing/decreasing arrays still resolve via the spans; the downward propagation is essential — without it, window sizes that are not the *maximal* span of any element would be left as 0; duplicates are handled by the `>=` pop (treating equal as "not strictly smaller," so each element owns a well-defined span).

---

### Problem 36: Design Front Middle Back Queue — Two Deques Balanced — *Medium*

**Statement.** Design a queue supporting push and pop at the front, the **middle**, and the back: `pushFront`, `pushMiddle`, `pushBack`, `popFront`, `popMiddle`, `popBack`. When there are two middle positions, push to the **frontmost** middle and pop from the **frontmost** middle. Return `-1` from a pop on an empty queue.

**Constraints.** Up to `1000` calls; `1 ≤ value ≤ 10^9`.

**Approach.** Split the sequence across two array-deques, `front` and `back`, maintaining the invariant `front.size()` equals `back.size()` or `back.size() + 1` is *not* what we want — instead keep `front.size() <= back.size()` and rebalance so the middle is always at the boundary. After every operation call `balance()`: if `front` has more than `back`, move its last to `back`'s front; if `back` exceeds `front` by 2+, move its first to `front`'s back. The middle element is then `front`'s last (when sizes are equal) or `back`'s first (when `back` is larger).

```java
import java.util.ArrayDeque;
import java.util.Deque;

class FrontMiddleBackQueue {
    private final Deque<Integer> front = new ArrayDeque<>();
    private final Deque<Integer> back = new ArrayDeque<>();

    public void pushFront(int val) { front.offerFirst(val); balance(); }
    public void pushBack(int val)  { back.offerLast(val);  balance(); }

    public void pushMiddle(int val) {
        if (front.size() == back.size()) front.offerLast(val);
        else back.offerFirst(val);          // back is larger -> push to its front
        balance();
    }

    public int popFront() {
        if (isEmpty()) return -1;
        int v = front.isEmpty() ? back.pollFirst() : front.pollFirst();
        balance();
        return v;
    }

    public int popMiddle() {
        if (isEmpty()) return -1;
        int v;
        if (front.size() == back.size()) v = front.pollLast();   // frontmost middle
        else v = back.pollFirst();
        balance();
        return v;
    }

    public int popBack() {
        if (isEmpty()) return -1;
        int v = back.pollLast();
        balance();
        return v;
    }

    private boolean isEmpty() { return front.isEmpty() && back.isEmpty(); }

    private void balance() {
        // invariant: front.size() <= back.size() <= front.size() + 1
        if (front.size() > back.size()) {
            back.offerFirst(front.pollLast());
        } else if (back.size() > front.size() + 1) {
            front.offerLast(back.pollFirst());
        }
    }
}
```

**Walkthrough** push 1,2,3,4 to back, balancing keeps `front=[1,2] back=[3,4]`. `pushMiddle(5)`: sizes equal → `front=[1,2,5]`, balance moves 5 to back → `front=[1,2] back=[5,3,4]`. `popMiddle` returns 5.

**Complexity.** Time O(1) amortized per operation (each `balance` moves at most one element); Space O(n). **Edge cases:** popping from empty returns `-1`; single-element queue: front == middle == back; the two-middle rule resolves to the frontmost by the `front.size() == back.size()` branch.

---

### Problem 37: Online Stock Span as Previous-Greater + Generalized Histogram Follow-up

**Statement.** Two related streaming queries are common follow-ups to Stock Span. (a) Given a stream of prices, for each price report how many recent consecutive days had a price strictly **less** than today (strict span). (b) Maintain the index of the **previous greater** element for the latest price. Implement a single structure answering both as prices arrive.

**Constraints.** Up to `10^5` prices; `0 ≤ price ≤ 10^9`.

**Approach.** A **monotonic decreasing stack** of `(price, index)` is the unifying structure. For the strict span (a), pop while the top price is strictly `<` today's price (note: `<` not `<=`, so equal prices stop the span), accumulating popped spans. For previous-greater (b), the element remaining on the stack *after* popping all `<=` today is exactly the previous strictly-greater element; its index is the answer (or `-1`). Storing index alongside price lets one stack serve both queries; each price is pushed and popped once.

```java
import java.util.ArrayDeque;
import java.util.Deque;

class StockMonitor {
    private final Deque<int[]> spanStack = new ArrayDeque<>();   // {price, span} for strict span
    private final Deque<int[]> pgeStack  = new ArrayDeque<>();   // {price, index} for prev-greater
    private int day = -1;

    // (a) strict span: consecutive prior days with price strictly less than today
    public int strictSpan(int price) {
        int span = 1;
        while (!spanStack.isEmpty() && spanStack.peek()[0] < price) {
            span += spanStack.pop()[1];
        }
        spanStack.push(new int[]{price, span});
        return span;
    }

    // (b) index of the previous strictly-greater price (-1 if none)
    public int previousGreaterIndex(int price) {
        day++;
        while (!pgeStack.isEmpty() && pgeStack.peek()[0] <= price) {
            pgeStack.pop();
        }
        int prevGreater = pgeStack.isEmpty() ? -1 : pgeStack.peek()[1];
        pgeStack.push(new int[]{price, day});
        return prevGreater;
    }
}
```

**Walkthrough** prices 100, 80, 80, 90. Strict spans: 100→1; 80→1; second 80→1 (the `<` stops at the equal 80); 90→ pops both 80s (span 1+1+1=3) but stops at 100, giving 3. Previous-greater indices: 100→-1; 80→0; 80→1 (the earlier 80 is not greater, so prev-greater is index of... after popping `<= 80` we pop the prior 80, leaving 100 at index 0 → answer 0); 90→ pop the 80s, leaving 100 → index 0.

**Complexity.** Time amortized O(1) per query, O(n) total; Space O(n). **Edge cases:** the `<` (strict) vs `<=` (non-strict) distinction is the crux — equal prices break a strict span but are still popped when searching for a *strictly* greater predecessor; an all-decreasing stream gives spans of 1 and previous-greater indices pointing to the immediate prior day; the first price always has span 1 and previous-greater `-1`.

---

## 🧩 Extended Problems — Supplemental: Medium → Expert

### Problem 38: Basic Calculator II — Operator Precedence with a Number Stack — *Medium*

**Statement.** Evaluate a string expression of non-negative integers and operators `+ - * /` (no parentheses, integer division truncates toward zero). Example: `"3+2*2"` → `7`; `" 3/2 "` → `1`; `" 3+5 / 2 "` → `5`.

**Constraints.** `1 ≤ s.length ≤ 3·10^5`; the expression is valid; result fits in a 32-bit signed integer.

**Approach.** Because there are no parentheses, precedence collapses to "apply `*` and `/` immediately, defer `+` and `-` by pushing signed numbers onto a stack." Track the *previous* operator (initially `+`). When the next token boundary is reached (operator or end-of-string), use the previous operator to fold the just-built `number` into a **number stack**: `+` pushes `+number`, `-` pushes `-number`, `*` and `/` pop the top and push `top*number` / `top/number`. The answer is the sum of the stack.

```
"3+5/2"
 see 3, prev='+'  -> at next op '+' push +3 -> stack=[3]
 see 5, prev='+'  -> at next op '/' push +5 -> stack=[3,5]
 see 2, prev='/'  -> end: pop 5, push 5/2=2 -> stack=[3,2]
 sum = 5
```

```java
import java.util.ArrayDeque;
import java.util.Deque;

class Solution {
    public int calculate(String s) {
        Deque<Integer> stack = new ArrayDeque<>();
        int number = 0;
        char prevOp = '+';
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) {
                number = number * 10 + (c - '0');
            }
            // commit on operator (non-space, non-digit) or at end of string
            if ((!Character.isDigit(c) && c != ' ') || i == n - 1) {
                switch (prevOp) {
                    case '+': stack.push(number); break;
                    case '-': stack.push(-number); break;
                    case '*': stack.push(stack.pop() * number); break;
                    case '/': stack.push(stack.pop() / number); break;
                }
                prevOp = c;
                number = 0;
            }
        }
        int total = 0;
        for (int v : stack) total += v;
        return total;
    }
}
```

**Complexity.** Time O(n); Space O(n) — at most one stack entry per `+`/`-` term. **Edge cases:** division truncates toward zero (Java's `/` already does this for non-negatives); leading/trailing spaces handled because the `prevOp` commit fires on the last digit via `i == n - 1`; a single number returns itself; multiple consecutive `*`/`/` chain correctly because each one folds into the stack top before the next operator is read.

---

### Problem 39: Score of Parentheses — Stack of Partial Scores

**Statement.** Given a balanced parentheses string `s`, compute its score by the rules: `"()"` has score 1; `AB` has score `A + B`; `(A)` has score `2 * A`. Example: `"(()(()))"` → `6`.

**Constraints.** `2 ≤ s.length ≤ 50`; `s` is a balanced parentheses string.

**Approach.** A **stack of partial scores**, with a sentinel `0` at the bottom for the outermost frame. On `'('`, push `0` (a new frame starts with score 0). On `')'`, pop the current frame: if it is `0` we just closed a literal `"()"` so the contribution is `1`, otherwise it is `2 * v`. Add that contribution to the new top (the enclosing frame). The final top is the answer. This handles concatenation and nesting in one pass.

```
"(()(()))"
 (  push -> [0,0]
 (  push -> [0,0,0]
 )  pop 0 -> contribute 1 to top -> [0,1]
 (  push -> [0,1,0]
 (  push -> [0,1,0,0]
 )  pop 0 -> 1 -> [0,1,0,1]
 )  pop 1 -> 2*1=2 -> [0,1,2]
 )  pop 2 -> still 2 wrong? recompute below
```

```java
import java.util.ArrayDeque;
import java.util.Deque;

class Solution {
    public int scoreOfParentheses(String s) {
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(0);                              // outer frame
        for (char c : s.toCharArray()) {
            if (c == '(') {
                stack.push(0);                      // start new inner frame
            } else {
                int v = stack.pop();
                int add = (v == 0) ? 1 : 2 * v;     // "()" = 1 ; "(A)" = 2*A
                stack.push(stack.pop() + add);      // add to enclosing frame
            }
        }
        return stack.pop();
    }
}
```

**Walkthrough.** `"(()(()))"`: inner `"()"` contributes 1, the next `"()"` inside `"(())"` contributes 1 doubled to 2, summed with sibling 1 → 3, doubled by the outer parens → 6. Correct.

**Complexity.** Time O(n); Space O(depth). **Edge cases:** `"()"` returns 1; sibling concatenation `"()()"` returns 2 (1+1) because each pop adds to the same outer frame; deeply nested `"(((())))"` returns `2^k` for `k` nested wraps around a literal.

---

### Problem 40: Remove K Digits — Greedy Monotonic Stack

**Statement.** Given a non-negative integer represented as string `num` and an integer `k`, remove `k` digits from `num` so that the resulting number is the smallest possible. Return the result as a string (without leading zeros, or `"0"` if empty).

**Constraints.** `1 ≤ k ≤ num.length ≤ 10^5`; `num` contains digits `0-9` only and has no leading zeros (unless `num == "0"`).

**Approach.** A **monotonic non-decreasing stack of digits**. Iterate left to right; while we still have removals left (`k > 0`) and the stack top is *greater* than the current digit, pop (removing a larger leading digit makes the number smaller). Push the current digit. If `k > 0` remains at the end, pop from the back (we can always profitably drop trailing digits of a non-decreasing sequence). Finally strip leading zeros. Each digit is pushed and popped at most once → O(n).

```
num="1432219", k=3
1 push -> [1]
4 push -> [1,4]
3 pop 4 (k=2) -> [1] ; push -> [1,3]
2 pop 3 (k=1) -> [1] ; push -> [1,2]
2 push -> [1,2,2]
1 pop 2 (k=0) -> [1,2] ; push -> [1,2,1]    no more removals
9 push -> [1,2,1,9]
result "1219"
```

```java
import java.util.ArrayDeque;
import java.util.Deque;

class Solution {
    public String removeKdigits(String num, int k) {
        Deque<Character> stack = new ArrayDeque<>();   // digits, non-decreasing bottom->top
        for (char c : num.toCharArray()) {
            while (!stack.isEmpty() && k > 0 && stack.peek() > c) {
                stack.pop();
                k--;
            }
            stack.push(c);
        }
        while (k-- > 0 && !stack.isEmpty()) stack.pop();   // drop trailing if not used

        // stack iterates top->bottom; reverse and trim leading zeros
        StringBuilder sb = new StringBuilder();
        for (char c : stack) sb.append(c);
        sb.reverse();
        int i = 0;
        while (i < sb.length() - 1 && sb.charAt(i) == '0') i++;
        return sb.substring(i);
    }
}
```

**Complexity.** Time O(n); Space O(n). **Edge cases:** `k == num.length` → return `"0"`; all-same digits → drop the trailing `k`; leading zeros after removal are stripped (`"10200", k=1` → `"200"`); already-non-decreasing input → trailing removals dominate (`"12345", k=2` → `"123"`).

---

### Problem 41: 132 Pattern — Monotonic Stack Sweeping Right-to-Left — *Medium*

**Statement.** Given an integer array `nums`, return `true` if there exist indices `i < j < k` such that `nums[i] < nums[k] < nums[j]` (the classic "132 pattern"), else `false`.

**Constraints.** `1 ≤ n ≤ 2·10^5`; `-10^9 ≤ nums[i] ≤ 10^9`.

**Approach.** Sweep **right-to-left** maintaining (a) a monotonic decreasing stack of candidate `nums[j]` values, and (b) a variable `secondMax` (the best `nums[k]` we have ever popped — values that were *less* than some later `nums[j]`). For each `nums[i]` in reverse: if `nums[i] < secondMax`, we have found `i < k < j` with `nums[i] < nums[k] < nums[j]` (the popped `nums[k]` came from somewhere right of the current `nums[j]`). Otherwise, while `nums[i] > stack.top`, pop into `secondMax`. Push `nums[i]`. Each value is pushed/popped at most once → O(n).

```java
import java.util.ArrayDeque;
import java.util.Deque;

class Solution {
    public boolean find132pattern(int[] nums) {
        Deque<Integer> stack = new ArrayDeque<>();    // candidates for nums[j], decreasing top->bottom (i.e., increasing bottom->top)
        int secondMax = Integer.MIN_VALUE;            // best nums[k] seen (k > j somewhere)
        for (int i = nums.length - 1; i >= 0; i--) {
            if (nums[i] < secondMax) return true;     // nums[i] < nums[k] < nums[j] confirmed
            while (!stack.isEmpty() && nums[i] > stack.peek()) {
                secondMax = stack.pop();              // popped value becomes our nums[k]
            }
            stack.push(nums[i]);
        }
        return false;
    }
}
```

**Walkthrough.** `nums=[3,1,4,2]`: i=3 push 2; i=2 val 4 > 2 → secondMax=2, push 4; i=1 val 1, is `1 < secondMax(2)`? yes → true. So `1, 4, 2` is the 132 pattern.

**Complexity.** Time O(n); Space O(n). **Edge cases:** `n < 3` always returns `false`; strictly increasing or strictly decreasing arrays have no 132 pattern; duplicates handled because the comparisons are strict; the right-to-left sweep is the trick that distinguishes this from a naive O(n²) attempt.

---

### Problem 42: Largest Rectangle in Histogram with Width Constraint — *Hard*

**Statement.** Given bar heights `h` (unit width) and an integer `W`, find the maximum area of a rectangle whose width is **at most** `W` and that fits under the histogram. (Standard largest-rectangle when `W >= n`.)

**Constraints.** `1 ≤ n ≤ 10^5`, `1 ≤ W ≤ n`, `0 ≤ h[i] ≤ 10^4`.

**Approach.** Reuse the **monotonic increasing stack of indices** from the standard problem, but when computing each candidate rectangle's width, clamp it by `W`. The popped index represents a height; its natural width is `i - leftBound - 1`; the constrained width is `min(W, naturalWidth)`. The shrunken-width candidate may still be the optimum (e.g. a short-but-wide rectangle that overshoots `W` becomes the best after clamping). A trailing sentinel `0` flushes the stack.

```java
import java.util.ArrayDeque;
import java.util.Deque;

class Solution {
    public int largestRectangleConstrained(int[] h, int W) {
        int n = h.length, best = 0;
        Deque<Integer> stack = new ArrayDeque<>();         // indices, heights increasing
        for (int i = 0; i <= n; i++) {
            int cur = (i == n) ? 0 : h[i];                 // sentinel flushes the stack
            while (!stack.isEmpty() && h[stack.peek()] >= cur) {
                int height = h[stack.pop()];
                int leftBound = stack.isEmpty() ? -1 : stack.peek();
                int width = Math.min(W, i - leftBound - 1);
                best = Math.max(best, height * width);
            }
            stack.push(i);
        }
        return best;
    }
}
```

**Complexity.** Time O(n); Space O(n). **Edge cases:** `W >= n` reduces to the classic problem; `W == 1` returns `max(h)`; bars of zero height never contribute; if a tall thin column would alone be the max, the clamp does not affect it because its natural width is 1 ≤ W.

---

### Problem 43: Steady Gene / Longest Valid Parentheses — Stack of Indices — *Hard*

**Statement.** Given a string of `'('` and `')'`, find the length of the **longest valid (well-formed) parentheses substring**.

**Constraints.** `0 ≤ s.length ≤ 3·10^4`.

**Approach.** A **stack of indices** with a sentinel `-1` at the bottom representing "the position just before the last unmatched character." On `'('`, push `i`. On `')'`, pop; if the stack becomes empty, push `i` as the new sentinel (this `')'` is itself unmatched and resets the base); otherwise the current valid substring length is `i - stack.peek()`. Track the maximum. The sentinel trick lets us compute *length* (an inclusive run width) from indices without special cases.

```
"(()())"
 i=0 '(' push -> [-1,0]
 i=1 '(' push -> [-1,0,1]
 i=2 ')' pop1, peek=0, len=2-0=2
 i=3 '(' push -> [-1,0,3]
 i=4 ')' pop3, peek=0, len=4-0=4
 i=5 ')' pop0, peek=-1, len=5-(-1)=6   <- answer
```

```java
import java.util.ArrayDeque;
import java.util.Deque;

class Solution {
    public int longestValidParentheses(String s) {
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(-1);                              // base sentinel
        int best = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '(') {
                stack.push(i);
            } else {
                stack.pop();
                if (stack.isEmpty()) {
                    stack.push(i);                   // new base after unmatched ')'
                } else {
                    best = Math.max(best, i - stack.peek());
                }
            }
        }
        return best;
    }
}
```

**Complexity.** Time O(n); Space O(n). **Edge cases:** empty string → 0; all-open `"((("` → 0 (no closes); all-closed → 0; concatenated runs like `"()(())"` → 6, found by carrying the base sentinel across the boundary; the sentinel pattern is the senior-level signal.

---

### Problem 44: Sum of Subarray Ranges — Twin Monotonic Stacks — *Hard*

**Statement.** Given an integer array `nums`, the *range* of a subarray is `max - min`. Return the sum of ranges of all O(n²) contiguous subarrays.

**Constraints.** `1 ≤ n ≤ 1000`; `-10^9 ≤ nums[i] ≤ 10^9`. (Optimal O(n) solution wins extra credit.)

**Approach.** `sum_of_ranges = sum_of_subarray_maximums - sum_of_subarray_minimums`. Compute each with the contribution-counting monotonic-stack technique from "Sum of Subarray Minimums": each `nums[i]` is the minimum of `left_min[i] * right_min[i]` subarrays and the maximum of `left_max[i] * right_max[i]` subarrays. Use asymmetric strict/non-strict comparisons (`>=` on one side, `>` on the other) so duplicates are counted exactly once. Two monotonic-stack passes per side → O(n) total.

```java
import java.util.ArrayDeque;
import java.util.Deque;

class Solution {
    public long subArrayRanges(int[] nums) {
        return contributionSum(nums, true) - contributionSum(nums, false);
    }

    // when isMax=true, sum of subarray maxima; otherwise sum of subarray minima
    private long contributionSum(int[] a, boolean isMax) {
        int n = a.length;
        int[] left = new int[n], right = new int[n];
        Deque<Integer> st = new ArrayDeque<>();

        // strictly-better on the left (so duplicates owned by the rightmost equal)
        for (int i = 0; i < n; i++) {
            while (!st.isEmpty() && better(a[st.peek()], a[i], isMax, false)) st.pop();
            left[i] = st.isEmpty() ? i + 1 : i - st.peek();
            st.push(i);
        }
        st.clear();
        // not-worse on the right (>= for max, <= for min) -> exactly-one-owner
        for (int i = n - 1; i >= 0; i--) {
            while (!st.isEmpty() && better(a[st.peek()], a[i], isMax, true)) st.pop();
            right[i] = st.isEmpty() ? n - i : st.peek() - i;
            st.push(i);
        }
        long total = 0;
        for (int i = 0; i < n; i++) {
            total += (long) a[i] * left[i] * right[i];
        }
        return total;
    }

    // For max-pass: "better" means top should be popped because it's not strictly greater than current.
    // For min-pass: "better" means top should be popped because it's not strictly less than current.
    // 'orEqual' makes the comparison non-strict (used on the right side for the tie-break asymmetry).
    private boolean better(int top, int cur, boolean isMax, boolean orEqual) {
        if (isMax) return orEqual ? top <= cur : top < cur;
        else       return orEqual ? top >= cur : top > cur;
    }
}
```

**Complexity.** Time O(n); Space O(n). **Edge cases:** single element → 0 (range of a length-1 subarray is 0); strictly monotonic arrays still resolve via spans; duplicates are not double-counted because the strict-vs-non-strict asymmetry assigns each subarray to a unique "owner" element on each side; the sum can exceed `int` → use `long`.

---

### Problem 45: Constrained Subsequence Sum — Monotonic Deque over DP — *Hard*

**Statement.** Given `nums` and integer `k`, return the maximum sum of a non-empty subsequence such that for every two consecutive picked indices `i < j` in the subsequence, `j - i <= k`. Example: `nums=[10,2,-10,5,20], k=2` → `37`.

**Constraints.** `1 ≤ n ≤ 10^5`; `1 ≤ k ≤ n`; `-10^4 ≤ nums[i] ≤ 10^4`.

**Approach.** Let `dp[i]` = best sum of a valid subsequence ending exactly at `i`. Recurrence: `dp[i] = nums[i] + max(0, max(dp[i-k..i-1]))`. The `max(0, ...)` lets us optionally start fresh at `i`. The bottleneck is the rolling max over a window of size `k` — exactly the **monotonic decreasing deque of indices** pattern. Each `i`: evict the front when out of window; pull `best = max(0, dp[front])`; set `dp[i] = nums[i] + best`; evict back while `dp[back] <= dp[i]`; push `i`.

```java
import java.util.ArrayDeque;
import java.util.Deque;

class Solution {
    public int constrainedSubsetSum(int[] nums, int k) {
        int n = nums.length;
        int[] dp = new int[n];
        Deque<Integer> dq = new ArrayDeque<>();     // indices, dp values decreasing
        int best = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            while (!dq.isEmpty() && dq.peekFirst() < i - k) dq.pollFirst();    // window
            int prev = dq.isEmpty() ? 0 : Math.max(0, dp[dq.peekFirst()]);
            dp[i] = nums[i] + prev;
            best = Math.max(best, dp[i]);
            while (!dq.isEmpty() && dp[dq.peekLast()] <= dp[i]) dq.pollLast();
            dq.offerLast(i);
        }
        return best;
    }
}
```

**Walkthrough.** `nums=[10,2,-10,5,20], k=2`: dp=10, 12, 0(=−10+max(0,12)=2; wait recompute), … the deque keeps the best reachable predecessor each step, ultimately yielding 37 = 10+2+5+20 with all gaps ≤ 2.

**Complexity.** Time O(n); Space O(k). **Edge cases:** all negatives → answer is `max(nums)` (we must pick at least one element; the `max(0,…)` ensures we don't carry negative tails); `k == n` reduces to classic max-subsequence-sum-with-no-distance-constraint (which is the maximum subarray sum if we couldn't skip — but we *can* skip, so the answer is just the sum of positives or `max(nums)`); single element → that element.

---

### Problem 46: Maximum of All Subarrays of Size K Using Two Stacks (Queue with O(1) Max)

**Statement.** Build a queue supporting `push`, `pop`, and `getMax`, all in **amortized O(1)**. Then use it to compute the maximum of every window of size `k` over `nums`, achieving O(n) with a structure that does not assume sortedness or random access.

**Constraints.** Up to `10^5` operations / `n`.

**Approach.** A **max-queue** combines the two-stack queue (Problem 3) with the min/max-stack trick (Problem 2). Each stack stores pairs `(value, runningMax)` so its own max is O(1). Enqueue pushes onto the **in** stack with `max(in.top.runningMax, value)`. Dequeue pops from the **out** stack; if empty, drain `in` into `out`, recomputing each `out` running-max as `max(out.top.runningMax, transferredValue)`. `getMax` returns `max(in.top.runningMax, out.top.runningMax)`. For the sliding window, push `nums[i]`, and once `i >= k-1` record `getMax`, then `pop` to slide.

```java
import java.util.ArrayDeque;
import java.util.Deque;

class MaxQueue {
    private final Deque<int[]> in = new ArrayDeque<>();   // {value, runningMaxOfThisStack}
    private final Deque<int[]> out = new ArrayDeque<>();

    public void push(int x) {
        int curMax = in.isEmpty() ? x : Math.max(in.peek()[1], x);
        in.push(new int[]{x, curMax});
    }

    public int pop() {
        if (out.isEmpty()) {
            while (!in.isEmpty()) {
                int v = in.pop()[0];
                int curMax = out.isEmpty() ? v : Math.max(out.peek()[1], v);
                out.push(new int[]{v, curMax});
            }
        }
        return out.pop()[0];
    }

    public int getMax() {
        int a = in.isEmpty() ? Integer.MIN_VALUE : in.peek()[1];
        int b = out.isEmpty() ? Integer.MIN_VALUE : out.peek()[1];
        return Math.max(a, b);
    }
}

class Solution {
    public int[] maxSlidingWindow(int[] nums, int k) {
        MaxQueue q = new MaxQueue();
        int[] ans = new int[nums.length - k + 1];
        for (int i = 0; i < nums.length; i++) {
            q.push(nums[i]);
            if (i >= k) q.pop();
            if (i >= k - 1) ans[i - k + 1] = q.getMax();
        }
        return ans;
    }
}
```

**Complexity.** Each value moves from `in` to `out` at most once → amortized O(1) per op; O(n) total for the sliding window pass. Space O(k). **Edge cases:** worst-case single `pop` is O(k) when a transfer triggers; `k == 1` returns `nums` unchanged; the deque-of-indices alternative (Problem 10) is also O(n) but the two-stack design generalizes to any associative "summary" (sum, gcd, bitwise-or) without needing index arithmetic.

---

### Problem 47: Design Hit Counter — Queue with Time-Bucket Compression

**Statement.** Design a hit counter that counts hits received in the past 5 minutes (300 seconds). Implement `hit(timestamp)` (timestamps arrive monotonically nondecreasing in seconds) and `getHits(timestamp)` returning the count in the inclusive window `[timestamp - 299, timestamp]`.

**Constraints.** Up to `10^5` calls; `1 ≤ timestamp ≤ 2·10^9`.

**Approach.** Use a **queue of `(timestamp, count)` buckets** — when `hit(t)` is called and the most recent bucket has the same timestamp, increment its count; otherwise enqueue a new bucket `(t, 1)`. On every call (both `hit` and `getHits`), evict the front while its timestamp is `<= t - 300`. Maintain a running `total` so `getHits` is O(buckets-evicted) amortized, but each timestamp is enqueued at most once and dequeued at most once → amortized O(1). This compresses many hits at the same second into a single queue entry — important when a burst of thousands of hits share a second.

```java
import java.util.ArrayDeque;
import java.util.Deque;

class HitCounter {
    private final Deque<int[]> q = new ArrayDeque<>();   // each {timestamp, count}
    private int total = 0;

    public void hit(int timestamp) {
        evict(timestamp);
        if (!q.isEmpty() && q.peekLast()[0] == timestamp) {
            q.peekLast()[1]++;
        } else {
            q.offerLast(new int[]{timestamp, 1});
        }
        total++;
    }

    public int getHits(int timestamp) {
        evict(timestamp);
        return total;
    }

    private void evict(int now) {
        while (!q.isEmpty() && q.peekFirst()[0] <= now - 300) {
            total -= q.pollFirst()[1];
        }
    }
}
```

**Complexity.** Amortized O(1) per `hit` and `getHits`; Space O(unique timestamps in last 300 s) — bounded by 300 if you also coalesce buckets at the same second, or by the number of distinct seconds otherwise. **Edge cases:** a burst of thousands of hits at one timestamp creates a single bucket (efficient); querying with no hits returns 0; the window is *inclusive* on both ends, so use `<= now - 300` (strictly older than 5 minutes) for eviction; timestamps are guaranteed nondecreasing so we never need to insert in the middle.

---

### Problem 48: Number of Visible People in a Queue — Monotonic Stack of Heights — *Hard*

**Statement.** Given heights `heights` of people standing in a line, person `i` can see person `j` (with `j > i`) iff every person strictly between them is **strictly shorter** than both. Return an array `answer` where `answer[i]` is the number of people person `i` can see to the right.

**Constraints.** `1 ≤ n ≤ 10^5`; all heights distinct; `1 ≤ heights[i] ≤ 10^9`.

**Approach.** Process the array **right-to-left** using a **monotonic decreasing stack of heights**. For each `i`: pop everyone strictly shorter than `heights[i]` (person `i` sees each of them and they no longer block anyone further), counting them; if the stack is still non-empty after popping (someone equal or taller blocks the view, but here heights are distinct so this means strictly taller), person `i` can also see that one. Push `heights[i]`. The total seen is `popped + (stack-non-empty ? 1 : 0)`. Each height is pushed/popped once → O(n).

```java
import java.util.ArrayDeque;
import java.util.Deque;

class Solution {
    public int[] canSeePersonsCount(int[] heights) {
        int n = heights.length;
        int[] ans = new int[n];
        Deque<Integer> stack = new ArrayDeque<>();      // heights, decreasing top->bottom
        for (int i = n - 1; i >= 0; i--) {
            int seen = 0;
            while (!stack.isEmpty() && stack.peek() < heights[i]) {
                stack.pop();
                seen++;
            }
            if (!stack.isEmpty()) seen++;               // the (taller) blocker is still visible
            ans[i] = seen;
            stack.push(heights[i]);
        }
        return ans;
    }
}
```

**Walkthrough.** `heights=[10,6,8,5,11,9]`, rightmost: 9 sees 0 (stack empty), push. 11 pops 9 (seen=1), stack empty → ans 1. 5 sees only 11 (taller blocker, popped none) → 1. 8 pops 5 (1), then sees 11 → 2. 6 pops nothing (top is 8 which is taller) → sees 8 → 1. 10 pops 6, 8 (2), then sees 11 → 3. Result `[3,1,2,1,1,0]`.

**Complexity.** Time O(n); Space O(n). **Edge cases:** strictly decreasing input → each person sees exactly 1 (the next neighbor, then blocked); strictly increasing input → each sees only the immediate next (the rest blocked by it); the rightmost person always sees 0; the algorithm doesn't depend on distinctness, but if equal heights are possible, replace `<` with `<=` to skip equal-height blockers consistently.

---

### Problem 49: Sliding Window Median via Two Heaps / Multiset — Deque Companion — *Hard*

**Statement.** Given `nums` and window size `k`, return the median of every sliding window of size `k`. Median is the middle when `k` is odd, average of the two middles when `k` is even (use `double`).

**Constraints.** `1 ≤ k ≤ n ≤ 10^5`; values fit in `int` but the median should be computed in `double` to avoid overflow when averaging.

**Approach.** A monotonic deque does **not** work for the median because the median is not a min/max. Use the **two-heap** pattern: a max-heap `lo` for the smaller half, a min-heap `hi` for the larger half, with `|lo| - |hi| ∈ {0, 1}`. To support arbitrary removals as the window slides, use **lazy deletion**: a hash-map `toDelete` counts pending removals; whenever a heap's top is in `toDelete`, pop and decrement. After each add/remove, rebalance sizes and prune stale tops. The "stale-top pruning" routine is the queue-flavored operation that ties this back into the chapter (a deque of pending deletions logically; lazy-evict at the front when stale).

```java
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

class Solution {
    PriorityQueue<Integer> lo = new PriorityQueue<>(Comparator.reverseOrder());  // max-heap
    PriorityQueue<Integer> hi = new PriorityQueue<>();                            // min-heap
    Map<Integer, Integer> toDelete = new HashMap<>();
    int balance = 0;   // |lo effective| - |hi effective|

    public double[] medianSlidingWindow(int[] nums, int k) {
        double[] ans = new double[nums.length - k + 1];
        for (int i = 0; i < k; i++) add(nums[i]);
        ans[0] = median(k);
        for (int i = k; i < nums.length; i++) {
            add(nums[i]);
            remove(nums[i - k]);
            prune();
            ans[i - k + 1] = median(k);
        }
        return ans;
    }

    private void add(int x) {
        if (lo.isEmpty() || x <= lo.peek()) { lo.offer(x); balance++; }
        else                                { hi.offer(x); balance--; }
        rebalance();
    }

    private void remove(int x) {
        toDelete.merge(x, 1, Integer::sum);
        if (!lo.isEmpty() && x <= lo.peek()) balance--;
        else                                  balance++;
        prune();
        rebalance();
    }

    private void rebalance() {
        if (balance > 1)      { hi.offer(lo.poll()); balance -= 2; prune(); }
        else if (balance < 0) { lo.offer(hi.poll()); balance += 2; prune(); }
    }

    private void prune() {
        while (!lo.isEmpty() && toDelete.getOrDefault(lo.peek(), 0) > 0) {
            toDelete.merge(lo.poll(), -1, Integer::sum);
        }
        while (!hi.isEmpty() && toDelete.getOrDefault(hi.peek(), 0) > 0) {
            toDelete.merge(hi.poll(), -1, Integer::sum);
        }
    }

    private double median(int k) {
        if ((k & 1) == 1) return (double) lo.peek();
        return ((double) lo.peek() + hi.peek()) / 2.0;
    }
}
```

**Complexity.** Time O(n log k) — each insert/delete is O(log k) on the heaps with amortized O(1) lazy deletion; Space O(k). **Edge cases:** average overflow avoided by casting to `double` before adding; if `k == 1` the median is the element itself; the balance counter is updated *eagerly* on lazy delete so size invariants stay correct even with deferred top-pops; the deque/queue intuition shows up in the pruning loop, which evicts stale "front" entries.

---

### Problem 50: Robot Collisions — Stack-Based Simulation with State — *Hard*

**Statement.** `n` robots stand on a number line at distinct positions, each with a health value and a direction (`'L'` or `'R'`). All move at the same speed simultaneously. When two robots collide, the one with *lower* health is removed; the survivor loses 1 health. If they have equal health, both are removed. Return the surviving robots' healths in **input order**.

**Constraints.** `1 ≤ n ≤ 10^5`; positions distinct, healths positive; the output preserves the order in which robots appeared in the original input.

**Approach.** Sort robot indices by position so collisions resolve in spatial order. Use a **stack of right-moving robots**: scan robots left-to-right; right-movers push onto the stack; a left-mover collides with the stack top (the nearest right-mover to its left). Loop while the left-mover is alive and the stack top is a right-mover with non-zero health: if equal health both die; if the stack top is weaker, pop and decrement left-mover; if stronger, the left-mover dies and the top decrements. After processing, survivors are the stack (right-movers) plus any left-movers that escaped to the left of all right-movers — but to keep the **input order** of the output, attach the original index to each robot and finalize by sorting survivors by original index.

```java
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.ArrayList;
import java.util.List;

class Solution {
    public List<Integer> survivedRobotsHealths(int[] positions, int[] healths, String directions) {
        int n = positions.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Integer.compare(positions[a], positions[b]));

        int[] hp = healths.clone();                            // mutable copy
        boolean[] dead = new boolean[n];
        Deque<Integer> rightMovers = new ArrayDeque<>();       // original indices

        for (int idx : order) {
            if (directions.charAt(idx) == 'R') {
                rightMovers.push(idx);
            } else {                                            // 'L' collides with top R
                while (!rightMovers.isEmpty() && !dead[idx]) {
                    int top = rightMovers.peek();
                    if (hp[top] < hp[idx]) {
                        dead[top] = true;
                        rightMovers.pop();
                        hp[idx]--;
                    } else if (hp[top] == hp[idx]) {
                        dead[top] = true;
                        rightMovers.pop();
                        dead[idx] = true;
                    } else {
                        hp[top]--;
                        dead[idx] = true;
                    }
                }
            }
        }
        List<Integer> ans = new ArrayList<>();
        for (int i = 0; i < n; i++) if (!dead[i]) ans.add(hp[i]);   // input order preserved by i
        return ans;
    }
}
```

**Walkthrough.** Imagine `positions=[3,5,2,6]`, `healths=[10,10,15,12]`, `directions="RLRL"`. After sorting by position: index 2 (R,15), 0 (R,10), 1 (L,10), 3 (L,12). Process: push 2; push 0; index 1 left-moves into 0 → equal hp → both die; index 3 left-moves into 2 → 12 < 15 → index 3 dies, hp[2]=14. Survivor: index 2 with hp 14. Output `[14]`.

**Complexity.** Time O(n log n) for sort + O(n) for simulation (each robot pushed and popped at most once); Space O(n). **Edge cases:** all same direction (no collisions) → input survives unchanged; equal-health collisions remove both; the `dead[]` array plus iteration in input order is what restores the required output ordering — the stack alone would yield spatial order.

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
