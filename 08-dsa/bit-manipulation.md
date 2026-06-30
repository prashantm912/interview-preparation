# Bit Manipulation

[← Back to master index](../README.md) | [← DSA index](README.md)

Bit manipulation is the art of treating an integer as a fixed-width vector of bits and operating on it directly with the bitwise operators `&`, `|`, `^`, `~`, `<<`, `>>`, and (in Java) the unsigned shift `>>>`. It shows up everywhere in interviews: O(1) tricks that replace loops, O(2ⁿ) subset enumeration, bitmask dynamic programming over small sets, parity/XOR puzzles, and low-level questions about two's-complement representation, the sign bit, and overflow. This file collects 50 problems ramping from "set the i-th bit" to bitmask DP and SWAR popcount.

---

## Primer — the mental model you must hold

A Java `int` is **32 bits, two's complement**: bit 31 is the sign bit, values range `-2³¹ … 2³¹−1`, and `-x == (~x) + 1`. A `long` is 64 bits. The operators:

- `a & b` — AND: 1 only where both are 1. Used to **mask** (keep) bits and to **test** bits.
- `a | b` — OR: 1 where either is 1. Used to **set** bits.
- `a ^ b` — XOR: 1 where the bits **differ**. Self-inverse (`a ^ a == 0`, `a ^ 0 == a`), commutative, associative — the engine behind most parity tricks.
- `~a` — NOT: flips every bit (`~a == -a - 1`).
- `a << k` — left shift: multiply by `2ᵏ`, zero-fills from the right.
- `a >> k` — **arithmetic** right shift: divide by `2ᵏ` rounding toward −∞, **sign-extends** (copies bit 31).
- `a >>> k` — **logical** right shift: zero-fills from the left, ignores sign. (Java-specific; C has only one `>>` whose behavior depends on signedness.)

Two identities you will reuse constantly:
- `x & (x - 1)` clears the **lowest** set bit.
- `x & (-x)` **isolates** the lowest set bit (because `-x == ~x + 1`).

Throughout, "the i-th bit" means the bit of weight `2ⁱ`, counting from 0 at the least-significant end.

---

## Coding Problems

### Problem 1: Get the i-th Bit — masking with AND

**Statement.** Return the value (0 or 1) of bit `i` of integer `n`.

**Approach.** Shift the target bit down to position 0 and mask with `1`, or shift a probe mask `1 << i` up and test for non-zero.

```java
public class GetBit {
    // Returns 0 or 1.
    public static int getBit(int n, int i) {
        return (n >> i) & 1;
    }

    // Boolean "is bit i set?" — shift the mask instead of the value.
    public static boolean isSet(int n, int i) {
        return (n & (1 << i)) != 0;
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** `(n >> i) & 1` reads a bit; `n & (1 << i)` tests it without moving `n`.

---

### Problem 2: Set the i-th Bit — OR with a mask

**Statement.** Return `n` with bit `i` forced to 1 (leaving the rest unchanged).

**Approach.** OR with the mask `1 << i`. OR-ing with 1 sets a bit; OR-ing with 0 is a no-op, so other bits are untouched.

```java
public class SetBit {
    public static int setBit(int n, int i) {
        return n | (1 << i);
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** OR is the "turn on" operator — `x | 1 == 1` regardless of `x`.

---

### Problem 3: Clear the i-th Bit — AND with an inverted mask

**Statement.** Return `n` with bit `i` forced to 0.

**Approach.** Build the mask `1 << i`, invert it with `~` so every bit is 1 except position `i`, then AND. AND-ing with 1 keeps a bit, AND-ing with 0 clears it.

```java
public class ClearBit {
    public static int clearBit(int n, int i) {
        return n & ~(1 << i);
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** `~(1 << i)` is all-ones with a single 0 hole — AND punches that hole into `n`.

---

### Problem 4: Toggle the i-th Bit — XOR with a mask

**Statement.** Flip bit `i` of `n` (1→0, 0→1).

**Approach.** XOR with `1 << i`. XOR with 1 flips, XOR with 0 preserves.

```java
public class ToggleBit {
    public static int toggleBit(int n, int i) {
        return n ^ (1 << i);
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** XOR is the controllable inverter — flip exactly the bits set in the mask.

---

### Problem 5: Update the i-th Bit to a Given Value — clear then set

**Statement.** Set bit `i` of `n` to a supplied value `v` (0 or 1) branchlessly.

**Approach.** First clear bit `i`, then OR in `v << i`. This works for both values without an `if`.

```java
public class UpdateBit {
    public static int updateBit(int n, int i, int v) {
        int cleared = n & ~(1 << i); // hole at i
        return cleared | (v << i);   // fill with v (0 or 1)
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** "Clear then place" is the canonical branchless bit-write pattern.

---

### Problem 6: Multiply / Divide by Powers of Two — shifting

**Statement.** Compute `n * 2ᵏ` and `n / 2ᵏ` using shifts, and explain the rounding of right shift on negatives.

**Approach.** `n << k` multiplies; `n >> k` divides rounding **toward −∞** (so `-7 >> 1 == -4`, not `-3`). To match Java's `/` truncation-toward-zero on negatives you must adjust — but for non-negative `n` plain shifts match `/` exactly.

```java
public class ShiftArithmetic {
    public static int mulPow2(int n, int k) { return n << k; }

    public static int divPow2(int n, int k) { return n >> k; } // floor division

    // Truncate toward zero like Java's '/', even for negatives.
    public static int divTruncate(int n, int k) {
        int bias = (1 << k) - 1;          // 2^k - 1
        if (n < 0) n += bias;             // round negatives toward zero first
        return n >> k;
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** Arithmetic `>>` floors; add `2ᵏ−1` before shifting to recover truncation for negatives.

---

### Problem 7: Check Even or Odd — least significant bit

**Statement.** Determine parity of `n` without using `%`.

**Approach.** The LSB is exactly the value mod 2. `n & 1` is 1 for odd, 0 for even — and it is correct for negative numbers in two's complement (`-3 & 1 == 1`).

```java
public class EvenOdd {
    public static boolean isOdd(int n)  { return (n & 1) == 1; }
    public static boolean isEven(int n) { return (n & 1) == 0; }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** `n & 1` beats `n % 2` because `%` on negatives can yield `-1`.

---

### Problem 8: Swap Two Numbers Without a Temp — XOR swap

**Statement.** Swap `a` and `b` in place without an auxiliary variable.

**Approach.** Three XORs. Because XOR is self-inverse, `a ^= b; b ^= a; a ^= b` rotates the values. (Caveat: fails if `a` and `b` are the *same* memory location — then it zeroes them — so it is a puzzle answer, not production code.)

```java
public class XorSwap {
    public static int[] swap(int a, int b) {
        a ^= b;
        b ^= a; // b = b ^ (a ^ b) = a
        a ^= b; // a = (a ^ b) ^ a = b
        return new int[]{a, b};
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** XOR's self-inverse property lets two registers carry each other's bits transiently.

---

### Problem 9: Count Set Bits — naive vs Brian Kernighan

**Statement.** Count the 1-bits ("population count") of `n`. Treat `n` as unsigned 32-bit.

**Approach.** Naively test all 32 bits in O(32). **Brian Kernighan's trick** runs in O(set-bits): `n & (n-1)` clears the lowest set bit, so the loop iterates once per 1-bit.

```java
public class CountBits {
    // O(number of set bits)
    public static int kernighan(int n) {
        int count = 0;
        while (n != 0) {
            n &= (n - 1);  // drop lowest set bit
            count++;
        }
        return count;
    }

    // Library popcount (hardware POPCNT where available).
    public static int builtin(int n) {
        return Integer.bitCount(n);
    }
}
```

**Time:** O(set bits) · **Space:** O(1)

**Insight.** `n & (n-1)` peels off one set bit per step, so sparse integers cost almost nothing.

---

### Problem 10: Check Power of Two — single set bit

**Statement.** Return true iff `n` is a positive power of two (1, 2, 4, 8, …).

**Approach.** A power of two has exactly one set bit, so `n & (n-1) == 0`. Guard against `n <= 0` (which would wrongly pass `0`).

```java
public class PowerOfTwo {
    public static boolean isPowerOfTwo(int n) {
        return n > 0 && (n & (n - 1)) == 0;
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** Powers of two are precisely the integers with one bit set; `n & (n-1)` exposes that.

---

### Problem 11: Check Power of Four

**Statement.** Return true iff `n` is a power of four (1, 4, 16, 64, …).

**Approach.** It must be a power of two **and** the single set bit must sit at an even position. The mask `0x55555555` (binary `0101…01`) has 1s only at even positions; AND-ing keeps the bit only if it lands there.

```java
public class PowerOfFour {
    public static boolean isPowerOfFour(int n) {
        return n > 0
            && (n & (n - 1)) == 0          // power of two
            && (n & 0x55555555) != 0;      // bit at an even index
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** Powers of four are powers of two whose lone bit lands on an even index — mask `0x55555555` filters them.

---

### Problem 12: Isolate the Lowest Set Bit — n & (-n)

**Statement.** Return a number with only the lowest set bit of `n` retained (0 if `n == 0`).

**Approach.** In two's complement `-n == ~n + 1`, which flips everything up to and including the lowest set bit, so `n & (-n)` keeps exactly that bit. (This is the core of Fenwick/BIT indexing.)

```java
public class LowestSetBit {
    public static int lowestSetBit(int n) {
        return n & (-n);
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** `n & -n` extracts the rightmost 1 — the trick at the heart of binary indexed trees.

---

### Problem 13: Clear the Lowest Set Bit

**Statement.** Return `n` with its lowest set bit turned off.

**Approach.** `n & (n - 1)`. Subtracting 1 flips the lowest set bit to 0 and all the zeros below it to 1; AND-ing then clears that bit and restores the lower zeros.

```java
public class ClearLowestSetBit {
    public static int clearLowest(int n) {
        return n & (n - 1);
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** The dual of `n & -n`: one isolates the low bit, the other removes it.

---

### Problem 14: Isolate the Lowest *Clear* Bit

**Statement.** Return a mask with only the lowest **0**-bit of `n` set.

**Approach.** `~n` turns clear bits into set bits; the lowest set bit of `~n` is the lowest clear bit of `n`. So `~n & (n + 1)` — equivalently `(n + 1) & ~n` — isolates it, because `n + 1` sets the lowest clear bit while clearing those below.

```java
public class LowestClearBit {
    public static int lowestClearBit(int n) {
        return ~n & (n + 1);
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** Complement first, then reuse the lowest-set-bit machinery.

---

### Problem 15: Single Number — XOR cancels pairs

**Statement.** Every element appears twice except one. Find the single element. (LeetCode 136.)

**Approach.** XOR all elements. Duplicates cancel (`x ^ x == 0`) and `0 ^ unique == unique`. O(n) time, O(1) space, no hashing.

```java
public class SingleNumber {
    public static int find(int[] nums) {
        int x = 0;
        for (int v : nums) x ^= v;
        return x;
    }
}
```

**Time:** O(n) · **Space:** O(1)

**Insight.** XOR is the ultimate pair-canceller — the entire algorithm is one accumulator.

---

### Problem 16: Missing Number — XOR indices and values

**Statement.** Array contains `n` distinct numbers from `[0, n]`; one is missing. Find it. (LeetCode 268.)

**Approach.** XOR every index `0..n` with every value. Each present number cancels its matching index; the leftover is the missing number. (A sum-based `n(n+1)/2 − Σ` also works but can overflow.)

```java
public class MissingNumber {
    public static int find(int[] nums) {
        int x = nums.length;            // include index n
        for (int i = 0; i < nums.length; i++) {
            x ^= i ^ nums[i];
        }
        return x;
    }
}
```

**Time:** O(n) · **Space:** O(1)

**Insight.** Pairing indices against values via XOR avoids the overflow risk of summation.

---

### Problem 17: Two Single Numbers — partition by a distinguishing bit

**Statement.** Every element appears twice except **two** distinct elements. Find both. (LeetCode 260.)

**Approach.** XOR everything to get `a ^ b`. Any set bit of that result is a bit where `a` and `b` differ; isolate the lowest such bit `diff = xorAll & -xorAll`. Partition the array on `diff` and XOR each half independently — each unique number falls into its own bucket.

```java
public class TwoSingleNumbers {
    public static int[] find(int[] nums) {
        int xorAll = 0;
        for (int v : nums) xorAll ^= v;     // = a ^ b
        int diff = xorAll & (-xorAll);      // a bit where a,b differ
        int a = 0;
        for (int v : nums) {
            if ((v & diff) != 0) a ^= v;    // bucket with bit set
        }
        return new int[]{a, a ^ xorAll};    // b = (a^b) ^ a
    }
}
```

**Time:** O(n) · **Space:** O(1)

**Insight.** A single differing bit splits the multiset so each loner is isolated.

---

### Problem 18: Single Number II — every element thrice but one

**Statement.** Every element appears three times except one (which appears once). Find it. (LeetCode 137.)

**Approach.** Count each bit position mod 3. Implement with two accumulators `ones`/`twos` acting as a base-3 state machine so a bit resets after three sightings; the surviving bits form the unique number.

```java
public class SingleNumberII {
    public static int find(int[] nums) {
        int ones = 0, twos = 0;
        for (int v : nums) {
            ones = (ones ^ v) & ~twos;
            twos = (twos ^ v) & ~ones;
        }
        return ones; // bits seen exactly once (mod 3)
    }
}
```

**Time:** O(n) · **Space:** O(1)

**Insight.** Two bit-vectors emulate a mod-3 counter on every bit position in parallel.

---

### Problem 19: Reverse Bits — swap-and-shift

**Statement.** Reverse the 32 bits of an unsigned integer (bit 0 ↔ bit 31, etc.). (LeetCode 190.)

**Approach.** Simple O(32): pull bits off the bottom of `n`, push onto the bottom of `result`, shifting `result` left each step. (A divide-and-conquer "butterfly" using masks does it in O(log 32) — shown as the follow-up.)

```java
public class ReverseBits {
    public static int reverse(int n) {
        int result = 0;
        for (int i = 0; i < 32; i++) {
            result = (result << 1) | (n & 1); // append n's LSB
            n >>>= 1;                          // logical shift, no sign extension
        }
        return result;
    }

    // O(log 32) butterfly: swap halves, then quarters, ... then adjacent bits.
    public static int reverseFast(int n) {
        n = (n >>> 16) | (n << 16);
        n = ((n & 0xff00ff00) >>> 8) | ((n & 0x00ff00ff) << 8);
        n = ((n & 0xf0f0f0f0) >>> 4) | ((n & 0x0f0f0f0f) << 4);
        n = ((n & 0xcccccccc) >>> 2) | ((n & 0x33333333) << 2);
        n = ((n & 0xaaaaaaaa) >>> 1) | ((n & 0x55555555) << 1);
        return n;
    }
}
```

**Time:** O(1) (fixed 32 bits) · **Space:** O(1)

**Insight.** Use `>>>` here — `>>` would smear the sign bit and corrupt the reversal.

---

### Problem 20: Number of 1 Bits (Hamming Weight)

**Statement.** Return the number of set bits in an unsigned 32-bit integer. (LeetCode 191.)

**Approach.** Brian Kernighan's loop again, framed as the Hamming-weight question; or the SWAR popcount of Problem 45 for O(1) without a loop.

```java
public class HammingWeight {
    public static int hammingWeight(int n) {
        int count = 0;
        while (n != 0) {
            n &= (n - 1);
            count++;
        }
        return count;
    }
}
```

**Time:** O(set bits) · **Space:** O(1)

**Insight.** Hamming weight is just popcount under a different interview name.

---

### Problem 21: Hamming Distance — popcount of XOR

**Statement.** Count the positions at which two integers differ. (LeetCode 461.)

**Approach.** XOR produces 1s exactly where the bits differ; popcount the result.

```java
public class HammingDistance {
    public static int distance(int a, int b) {
        return Integer.bitCount(a ^ b);
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** XOR localizes differences; popcount tallies them — distance in one line.

---

### Problem 22: Total Hamming Distance — count per bit column

**Statement.** Sum of Hamming distances over **all pairs** in an array. (LeetCode 477.)

**Approach.** Brute force is O(n²). Instead, for each of 32 bit positions count how many numbers have that bit set (`k`); each such number pairs with the `n − k` that don't, contributing `k·(n−k)` differing pairs. Sum over columns.

```java
public class TotalHammingDistance {
    public static int total(int[] nums) {
        int n = nums.length, total = 0;
        for (int b = 0; b < 32; b++) {
            int ones = 0;
            for (int v : nums) ones += (v >> b) & 1;
            total += ones * (n - ones);   // set × clear pairs at this column
        }
        return total;
    }
}
```

**Time:** O(32·n) · **Space:** O(1)

**Insight.** Decompose pairwise distance column-by-column: each bit contributes independently.

---

### Problem 23: Counting Bits 0..n — DP on lowest bit

**Statement.** Return an array `ans[0..n]` where `ans[i]` is the popcount of `i`, in O(n). (LeetCode 338.)

**Approach.** DP: `popcount(i) = popcount(i & (i-1)) + 1` (one more bit than `i` with its lowest bit cleared), or `popcount(i) = popcount(i >> 1) + (i & 1)`. Both reuse a previously computed smaller value.

```java
public class CountingBits {
    public static int[] countBits(int n) {
        int[] ans = new int[n + 1];
        for (int i = 1; i <= n; i++) {
            ans[i] = ans[i & (i - 1)] + 1; // one set bit more than i with low bit cleared
        }
        return ans;
    }
}
```

**Time:** O(n) · **Space:** O(n)

**Insight.** Every integer's popcount is a tiny lookup away from a smaller already-solved one.

---

### Problem 24: Sum of Two Integers Without + or − — full adder

**Statement.** Add two integers using only bitwise operators. (LeetCode 371.)

**Approach.** XOR is addition without carry; `(a & b) << 1` is the carry. Loop, folding the carry back in until it vanishes. Java's wraparound handles overflow exactly like a hardware adder.

```java
public class GetSum {
    public static int getSum(int a, int b) {
        while (b != 0) {
            int carry = (a & b) << 1; // bits that overflow into the next column
            a = a ^ b;                // add without carry
            b = carry;                // propagate
        }
        return a;
    }
}
```

**Time:** O(1) (≤32 iterations) · **Space:** O(1)

**Insight.** A ripple-carry adder is just "XOR sum + shifted AND carry" iterated to a fixed point.

---

### Problem 25: Subtract Two Integers Without − — borrow propagation

**Statement.** Compute `a - b` using only bitwise operators.

**Approach.** Dual of addition: XOR is difference without borrow; the borrow is `(~a & b) << 1`. Iterate until the borrow is zero.

```java
public class GetDiff {
    public static int subtract(int a, int b) {
        while (b != 0) {
            int borrow = (~a & b) << 1;
            a = a ^ b;
            b = borrow;
        }
        return a;
    }
}
```

**Time:** O(1) (≤32 iterations) · **Space:** O(1)

**Insight.** Borrow happens where `a` is 0 and `b` is 1 — hence `~a & b`.

---

### Problem 26: Multiply Two Integers With Shifts and Adds — Russian peasant

**Statement.** Multiply `a * b` using only shifts, adds, and bit tests.

**Approach.** Long multiplication in binary: for each set bit `i` of `b`, add `a << i` to the result. This is the Russian-peasant / shift-add algorithm.

```java
public class BitMultiply {
    public static int multiply(int a, int b) {
        int result = 0;
        while (b != 0) {
            if ((b & 1) == 1) result += a; // add shifted a where b has a 1
            a <<= 1;                        // a * 2
            b >>>= 1;                       // next bit of b
        }
        return result;
    }
}
```

**Time:** O(32) · **Space:** O(1)

**Insight.** Multiplication is a sum of shifted copies — one per set bit of the multiplier.

---

### Problem 27: Divide Two Integers Without / or % — shift-subtract

**Statement.** Divide `dividend / divisor` (truncating toward zero) without `*`, `/`, or `%`. Handle the `INT_MIN / -1` overflow. (LeetCode 29.)

**Approach.** Work in `long` to dodge overflow, take absolutes, and repeatedly subtract the largest shifted multiple `divisor << k` that still fits, accumulating `1 << k` into the quotient.

```java
public class Divide {
    public static int divide(int dividend, int divisor) {
        if (dividend == Integer.MIN_VALUE && divisor == -1) return Integer.MAX_VALUE;
        boolean negative = (dividend < 0) ^ (divisor < 0);
        long a = Math.abs((long) dividend), b = Math.abs((long) divisor), quotient = 0;
        while (a >= b) {
            long temp = b, multiple = 1;
            while (a >= (temp << 1)) {  // largest doubling that fits
                temp <<= 1;
                multiple <<= 1;
            }
            a -= temp;
            quotient += multiple;
        }
        return negative ? (int) -quotient : (int) quotient;
    }
}
```

**Time:** O(log²(a/b)) · **Space:** O(1)

**Insight.** Binary long division: subtract the biggest shifted divisor, banking its power of two.

---

### Problem 28: Generate All Subsets via Bitmask

**Statement.** Return the power set of `nums` (distinct elements). (LeetCode 78.)

**Approach.** There are `2ⁿ` subsets; enumerate masks `0 … 2ⁿ−1`. Bit `j` of the mask decides whether `nums[j]` is included.

```java
import java.util.*;

public class Subsets {
    public static List<List<Integer>> subsets(int[] nums) {
        int n = nums.length;
        List<List<Integer>> result = new ArrayList<>();
        for (int mask = 0; mask < (1 << n); mask++) {
            List<Integer> subset = new ArrayList<>();
            for (int j = 0; j < n; j++) {
                if ((mask & (1 << j)) != 0) subset.add(nums[j]);
            }
            result.add(subset);
        }
        return result;
    }
}
```

**Time:** O(n·2ⁿ) · **Space:** O(n·2ⁿ)

**Insight.** Each integer in `[0, 2ⁿ)` *is* a subset — its bits are membership flags.

---

### Problem 29: Iterate All Submasks of a Mask

**Statement.** Enumerate every submask `s` of a bitmask `m` (including 0 and `m` itself).

**Approach.** The classic `s = (s - 1) & m` recurrence walks submasks in decreasing order. Subtracting 1 borrows through the low zeros; AND-ing with `m` snaps back onto valid bits. Total work over all masks is the famous O(3ⁿ).

```java
import java.util.*;

public class Submasks {
    public static List<Integer> submasks(int m) {
        List<Integer> result = new ArrayList<>();
        for (int s = m; ; s = (s - 1) & m) {
            result.add(s);
            if (s == 0) break;   // 0 must be the last value emitted
        }
        return result;
    }
}
```

**Time:** O(number of submasks) · **Space:** O(1) extra

**Insight.** `(s-1) & m` is the standard submask step; summed over all `m` it gives Σ 2^popcount = 3ⁿ.

---

### Problem 30: Gray Code Sequence — binary-to-Gray

**Statement.** Return a sequence of `2ⁿ` integers, starting at 0, where consecutive entries (and the wrap-around pair) differ in exactly one bit. (LeetCode 89.)

**Approach.** The reflected Gray code of `i` is `i ^ (i >> 1)`. Successive `i` flip exactly one Gray bit, so iterating `i = 0 … 2ⁿ−1` yields a valid sequence directly.

```java
import java.util.*;

public class GrayCode {
    public static List<Integer> grayCode(int n) {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < (1 << n); i++) {
            result.add(i ^ (i >> 1)); // standard reflected Gray code
        }
        return result;
    }
}
```

**Time:** O(2ⁿ) · **Space:** O(2ⁿ)

**Insight.** `i ^ (i >> 1)` maps the natural counting order onto single-bit-change order.

---

### Problem 31: Gray to Binary — prefix XOR

**Statement.** Convert a Gray code value back to its ordinary binary index.

**Approach.** Binary is the running XOR of the Gray bits from the most significant down: `b = g ^ (g >> 1) ^ (g >> 2) ^ …`. The loop folds each shifted copy in.

```java
public class GrayToBinary {
    public static int grayToBinary(int g) {
        int b = g;
        while (g > 0) {
            g >>= 1;
            b ^= g;
        }
        return b;
    }
}
```

**Time:** O(log n) · **Space:** O(1)

**Insight.** Gray→binary is a prefix-XOR; binary→Gray is `x ^ (x>>1)` — they invert each other.

---

### Problem 32: Bitwise AND of a Range — common prefix

**Statement.** Return the bitwise AND of all integers in `[left, right]`. (LeetCode 201.)

**Approach.** Any bit that flips somewhere in the range becomes 0 in the AND. The result is simply the **common binary prefix** of `left` and `right`. Shift both right until they are equal, counting the shifts, then shift the common value back.

```java
public class RangeBitwiseAnd {
    public static int rangeAnd(int left, int right) {
        int shift = 0;
        while (left < right) {       // strip differing low bits
            left >>= 1;
            right >>= 1;
            shift++;
        }
        return left << shift;        // restore the common prefix
    }
}
```

**Time:** O(log n) · **Space:** O(1)

**Insight.** Across a range every low bit eventually toggles, so only the shared high prefix survives AND.

---

### Problem 33: Count Set Bits in [0, n] — digit-DP style counting

**Statement.** Count the total number of 1-bits across every integer from 0 to `n` inclusive.

**Approach.** Process bit position `i` independently. Set bits at position `i` repeat in blocks of size `2^(i+1)` (`2^i` zeros then `2^i` ones). Count full blocks, then add the partial tail.

```java
public class CountBitsInRange {
    public static long countSetBits(int n) {
        long total = 0;
        for (int i = 0; (1L << i) <= n; i++) {
            long blockSize = 1L << (i + 1);
            long fullBlocks = (n + 1) / blockSize;
            total += fullBlocks * (blockSize / 2);          // ones from whole blocks
            long remainder = (n + 1) % blockSize;
            total += Math.max(0, remainder - (blockSize / 2)); // partial tail of ones
        }
        return total;
    }
}
```

**Time:** O(log n) · **Space:** O(1)

**Insight.** Set bits at column `i` are perfectly periodic — count periods, then the leftover.

---

### Problem 34: Find Position of the Only Set Bit (log₂ of a power of two)

**Statement.** Given an integer with exactly one set bit, return that bit's index (0-based). Return −1 otherwise.

**Approach.** Validate it's a power of two, then count trailing zeros — `Integer.numberOfTrailingZeros` is `log₂`. (Manual version: shift right until the bit reaches position 0.)

```java
public class OnlySetBitPosition {
    public static int position(int n) {
        if (n <= 0 || (n & (n - 1)) != 0) return -1; // not a single set bit
        return Integer.numberOfTrailingZeros(n);
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** Trailing-zero count is `log₂` for powers of two — no floating point needed.

---

### Problem 35: Find the Two Non-Repeating + Detect Odd-Occurring (parity recap)

**Statement.** In an array where exactly one element occurs an **odd** number of times and all others an even number, find it.

**Approach.** XOR everything; even occurrences cancel pairwise regardless of how many pairs, leaving the odd-occurring element. (Generalizes Problem 15 from "twice" to "any even count".)

```java
public class OddOccurring {
    public static int find(int[] nums) {
        int x = 0;
        for (int v : nums) x ^= v;
        return x;
    }
}
```

**Time:** O(n) · **Space:** O(1)

**Insight.** XOR cares only about parity of occurrences, so any even multiplicity vanishes.

---

### Problem 36: Maximum XOR of Two Numbers — greedy bit trie

**Statement.** Given an array, find the maximum `nums[i] ^ nums[j]`. (LeetCode 421.)

**Approach.** Build the answer greedily from the high bit down. At each bit, *assume* we can extend the running prefix with a 1; check via a `HashSet` of all numbers' prefixes whether some pair produces that bit (`candidate ^ prefix` exists). Keep the bit if achievable.

```java
import java.util.*;

public class MaximumXor {
    public static int findMaximumXOR(int[] nums) {
        int max = 0, mask = 0;
        for (int i = 31; i >= 0; i--) {
            mask |= (1 << i);                 // consider one more high bit
            Set<Integer> prefixes = new HashSet<>();
            for (int v : nums) prefixes.add(v & mask);
            int candidate = max | (1 << i);   // hope this bit can be 1
            for (int p : prefixes) {
                if (prefixes.contains(candidate ^ p)) { // a^b == candidate
                    max = candidate;
                    break;
                }
            }
        }
        return max;
    }
}
```

**Time:** O(32·n) · **Space:** O(n)

**Insight.** Greedily grab the highest achievable XOR bit; `a ^ b = c` ⇔ `a = b ^ c` makes the check a set lookup.

---

### Problem 37: UTF-8 Validation — bit-pattern parsing

**Statement.** Given a list of integers representing bytes (low 8 bits each), decide whether they form a valid UTF-8 encoding. (LeetCode 393.)

**Approach.** A leading byte's high bits announce the sequence length: `0xxxxxxx` (1), `110xxxxx` (2), `1110xxxx` (3), `11110xxx` (4). Each continuation byte must match `10xxxxxx`. Count expected continuations from the leading byte and verify the rest.

```java
import java.util.*;

public class Utf8Validation {
    public static boolean validUtf8(int[] data) {
        int remaining = 0; // continuation bytes still expected
        for (int b : data) {
            b &= 0xFF;
            if (remaining == 0) {
                if      ((b >> 5) == 0b110)   remaining = 1;
                else if ((b >> 4) == 0b1110)  remaining = 2;
                else if ((b >> 3) == 0b11110) remaining = 3;
                else if ((b >> 7) == 0b1)     return false; // 10xxxxxx with no leader
                // else single-byte 0xxxxxxx: remaining stays 0
            } else {
                if ((b >> 6) != 0b10) return false;          // must be a continuation
                remaining--;
            }
        }
        return remaining == 0;
    }
}
```

**Time:** O(n) · **Space:** O(1)

**Insight.** UTF-8's length is self-describing in the leading byte's high bits — pure prefix matching.

---

### Problem 38: Bitmask DP — Counting Bits per Number for Subset Sums (Partition to K Equal Subsets)

**Statement.** Decide whether `nums` can be partitioned into `k` subsets of equal sum. (LeetCode 698.)

**Approach.** Memoize over a `2ⁿ` bitmask of used elements. For a given `mask`, `chosenSum(mask) % target` is exactly how full the *current* bucket is (buckets fill one target at a time), so `remaining = target − fill`. Add any unused element that fits; reaching the full mask means every bucket closed exactly on target.

```java
import java.util.*;

public class PartitionKSubsets {
    public static boolean canPartition(int[] nums, int k) {
        int sum = 0;
        for (int v : nums) sum += v;
        if (k <= 0 || sum % k != 0) return false;
        int target = sum / k, n = nums.length;
        Boolean[] dp = new Boolean[1 << n];
        return dfs(nums, target, (1 << n) - 1, 0, dp);
    }

    private static boolean dfs(int[] nums, int target, int full, int mask, Boolean[] dp) {
        if (mask == full) return true;
        if (dp[mask] != null) return dp[mask];
        int fill = chosenSum(nums, mask) % target;   // how full the current bucket is
        int remaining = target - fill;               // room left in this bucket
        for (int i = 0; i < nums.length; i++) {
            if ((mask & (1 << i)) == 0 && nums[i] <= remaining) {
                if (dfs(nums, target, full, mask | (1 << i), dp)) return dp[mask] = true;
            }
        }
        return dp[mask] = false;
    }

    private static int chosenSum(int[] nums, int mask) {
        int s = 0;
        for (int i = 0; i < nums.length; i++)
            if ((mask & (1 << i)) != 0) s += nums[i];
        return s;
    }
}
```

**Time:** O(n·2ⁿ) · **Space:** O(2ⁿ)

**Insight.** The set of "used" elements is a bitmask, and `chosenSum(mask) % target` recovers the current bucket's fill level from it.

---

### Problem 39: Bitmask DP — Travelling Salesman (Held–Karp)

**Statement.** Given an `n×n` distance matrix, find the minimum-cost Hamiltonian cycle starting and ending at city 0. (Classic Held–Karp.)

**Approach.** `dp[mask][i]` = cheapest path that starts at 0, visits exactly the set `mask`, and currently sits at city `i`. Transition to an unvisited city `j` by OR-ing `1<<j` into the mask. Close the tour by returning to 0.

```java
import java.util.*;

public class TspHeldKarp {
    public static int shortestTour(int[][] dist) {
        int n = dist.length, FULL = (1 << n) - 1;
        int[][] dp = new int[1 << n][n];
        for (int[] row : dp) Arrays.fill(row, Integer.MAX_VALUE / 2);
        dp[1][0] = 0; // start at city 0, only it visited
        for (int mask = 1; mask <= FULL; mask++) {
            if ((mask & 1) == 0) continue;        // tours must include city 0
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) == 0) continue;
                for (int j = 0; j < n; j++) {
                    if ((mask & (1 << j)) != 0) continue; // j already visited
                    int next = mask | (1 << j);
                    dp[next][j] = Math.min(dp[next][j], dp[mask][i] + dist[i][j]);
                }
            }
        }
        int best = Integer.MAX_VALUE;
        for (int i = 1; i < n; i++)
            best = Math.min(best, dp[FULL][i] + dist[i][0]); // return to 0
        return best;
    }
}
```

**Time:** O(2ⁿ·n²) · **Space:** O(2ⁿ·n)

**Insight.** Encoding the visited-set as a bitmask collapses TSP from O(n!) to O(2ⁿ·n²).

---

### Problem 40: Minimum Number of Flips to Make a OR b Equal c

**Statement.** Given `a`, `b`, `c`, return the minimum bit flips (on `a` or `b`) so that `a | b == c`. (LeetCode 1318.)

**Approach.** Examine each bit. If `c`'s bit is 0, both `a` and `b` must be 0 there — add the number of 1s among them. If `c`'s bit is 1, at least one of `a`,`b` must be 1 — a flip is needed only if both are 0.

```java
public class MinFlips {
    public static int minFlips(int a, int b, int c) {
        int flips = 0;
        for (int i = 0; i < 32; i++) {
            int ai = (a >> i) & 1, bi = (b >> i) & 1, ci = (c >> i) & 1;
            if (ci == 0) flips += ai + bi;             // turn both off
            else if (ai == 0 && bi == 0) flips += 1;   // turn one on
        }
        return flips;
    }
}
```

**Time:** O(32) · **Space:** O(1)

**Insight.** OR constraints decompose per bit into three tiny cases — count flips column by column.

---

### Problem 41: Complement of a Number (Number Complement)

**Statement.** Flip the bits of `n` within its own bit-length (ignore leading zeros). `5` (`101`) → `2` (`010`). (LeetCode 476.)

**Approach.** Build a mask of all-ones covering exactly the significant bits of `n`, then XOR. The mask is `(1 << bitLength) - 1`, where `bitLength = 32 - numberOfLeadingZeros(n)`.

```java
public class NumberComplement {
    public static int findComplement(int n) {
        if (n == 0) return 1;
        int mask = (1 << (32 - Integer.numberOfLeadingZeros(n))) - 1;
        return n ^ mask;
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** A full `~` flips leading zeros too; mask down to the value's own width first.

---

### Problem 42: Binary Watch — enumerate by popcount

**Statement.** A binary watch has 4 LEDs for hours (0–11) and 6 for minutes (0–59). Given `n` LEDs lit, list every valid time. (LeetCode 401.)

**Approach.** Brute-force all `12 × 60` times; keep those whose total set bits across hour and minute equals `n`. Popcount does the filtering.

```java
import java.util.*;

public class BinaryWatch {
    public static List<String> readBinaryWatch(int turnedOn) {
        List<String> result = new ArrayList<>();
        for (int h = 0; h < 12; h++) {
            for (int m = 0; m < 60; m++) {
                if (Integer.bitCount(h) + Integer.bitCount(m) == turnedOn) {
                    result.add(String.format("%d:%02d", h, m));
                }
            }
        }
        return result;
    }
}
```

**Time:** O(720) · **Space:** O(1) extra

**Insight.** "LEDs lit" is literally popcount — enumerate the small domain and filter.

---

### Problem 43: Single Number III Generalized — find the element occurring k times among m-times

**Statement.** Every element appears `m` times except one that appears `k` times (`1 ≤ k < m`). Find it using bit counts mod `m`.

**Approach.** For each of 32 bit positions, sum the bit over all elements and take it `mod m`. The remainder reconstructs the unique element's bits (it will be `k mod m` times that bit). General version of Problems 15/18.

```java
public class SingleNumberGeneral {
    public static int find(int[] nums, int m) {
        int result = 0;
        for (int b = 0; b < 32; b++) {
            int sum = 0;
            for (int v : nums) sum += (v >> b) & 1;
            if (sum % m != 0) result |= (1 << b); // surviving bit belongs to the loner
        }
        return result;
    }
}
```

**Time:** O(32·n) · **Space:** O(1)

**Insight.** Counting each column mod `m` cancels the `m`-fold elements and leaves the outlier's bits.

---

### Problem 44: Reverse Bits in a Byte via Lookup Table

**Statement.** Reverse the 8 bits of a byte, fast, using a precomputed table.

**Approach.** Precompute reversals for all 256 byte values once; then reversal is a single array lookup. Used to bootstrap 32-bit reversal four lookups at a time.

```java
public class ReverseByte {
    private static final int[] TABLE = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int r = 0, v = i;
            for (int b = 0; b < 8; b++) { r = (r << 1) | (v & 1); v >>= 1; }
            TABLE[i] = r;
        }
    }

    public static int reverseByte(int b) {
        return TABLE[b & 0xFF];
    }
}
```

**Time:** O(1) per call (O(256) one-time build) · **Space:** O(256)

**Insight.** Trade memory for speed — precompute the bit permutation and index into it.

---

### Problem 45: Popcount Without a Loop — SWAR parallel bit counting

**Statement.** Count set bits of a 32-bit integer in O(1) with no branches or loops.

**Approach.** SWAR ("SIMD within a register"): add bits in parallel — pairs, then nibbles, then bytes — using masks `0x55555555`, `0x33333333`, `0x0f0f0f0f`, and a final multiply by `0x01010101` to sum the byte counts into the top byte.

```java
public class SwarPopcount {
    public static int popcount(int n) {
        n = n - ((n >>> 1) & 0x55555555);                 // counts in 2-bit fields
        n = (n & 0x33333333) + ((n >>> 2) & 0x33333333);  // counts in 4-bit fields
        n = (n + (n >>> 4)) & 0x0f0f0f0f;                 // counts in 8-bit fields
        return (n * 0x01010101) >>> 24;                    // sum bytes into top byte
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** Fold partial sums in parallel across the register — no per-bit loop at all.

---

### Problem 46: Next Number With Same Number of Set Bits — Gosper's Hack

**Statement.** Given `n > 0`, return the smallest integer larger than `n` with the same popcount. (Used to iterate fixed-size subsets.)

**Approach.** Gosper's hack: isolate the lowest set bit `c = n & -n`, add it to ripple the carry (`r = n + c`), then reattach the trailing ones in the lowest positions via `((r ^ n) >> 2) / c`.

```java
public class NextSamePopcount {
    public static int next(int n) {
        int c = n & (-n);            // lowest set bit
        int r = n + c;               // ripple carry up
        return r | (((r ^ n) >>> 2) / c); // restore the trailing ones, packed low
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** Gosper's hack walks combinations in O(1) per step — the canonical fixed-popcount iterator.

---

### Problem 47: Maximum Product of Word Lengths — bitmask of letters

**Statement.** Given words of lowercase letters, return the max product of lengths of two words that share **no** common letter. (LeetCode 318.)

**Approach.** Encode each word's letter set as a 26-bit mask. Two words are disjoint iff `maskA & maskB == 0`. Compare all pairs using masks instead of character sets.

```java
public class MaxProductWordLengths {
    public static int maxProduct(String[] words) {
        int n = words.length;
        int[] masks = new int[n];
        for (int i = 0; i < n; i++) {
            for (char ch : words[i].toCharArray()) {
                masks[i] |= 1 << (ch - 'a'); // set the letter's bit
            }
        }
        int best = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if ((masks[i] & masks[j]) == 0) {       // no shared letter
                    best = Math.max(best, words[i].length() * words[j].length());
                }
            }
        }
        return best;
    }
}
```

**Time:** O(n² + total chars) · **Space:** O(n)

**Insight.** A 26-bit mask turns "share a letter?" into one AND — far faster than set intersection.

---

### Problem 48: Minimum XOR Sum / Shortest Superstring style — bitmask assignment DP

**Statement.** Given `nums1`, `nums2` of equal length `n`, pair them up to minimize `Σ nums1[i] ^ nums2[perm[i]]`. (LeetCode 1879, assignment via bitmask DP.)

**Approach.** `dp[mask]` = min XOR sum after assigning the first `popcount(mask)` elements of `nums1` to the `nums2` indices in `mask`. Add the next `nums1` element paired with each unused `nums2` index.

```java
import java.util.*;

public class MinXorSum {
    public static int minimumXORSum(int[] nums1, int[] nums2) {
        int n = nums1.length, FULL = (1 << n) - 1;
        int[] dp = new int[1 << n];
        Arrays.fill(dp, Integer.MAX_VALUE);
        dp[0] = 0;
        for (int mask = 0; mask < (1 << n); mask++) {
            if (dp[mask] == Integer.MAX_VALUE) continue;
            int i = Integer.bitCount(mask); // next nums1 element to assign
            if (i >= n) continue;
            for (int j = 0; j < n; j++) {
                if ((mask & (1 << j)) != 0) continue;       // nums2[j] taken
                int next = mask | (1 << j);
                dp[next] = Math.min(dp[next], dp[mask] + (nums1[i] ^ nums2[j]));
            }
        }
        return dp[FULL];
    }
}
```

**Time:** O(2ⁿ·n) · **Space:** O(2ⁿ)

**Insight.** `popcount(mask)` tells you which row you are assigning — the mask doubles as a progress counter.

---

### Problem 49: Sum of XOR Over All Subsets — bit-contribution counting

**Statement.** For an array, sum `XOR(subset)` over all `2ⁿ` subsets.

**Approach.** Consider bit `b`. If any element has bit `b` set, then exactly half of all subsets have an **odd** count of that bit (so the subset-XOR has bit `b` = 1): `2^(n-1)` subsets. Each contributes `2^b`. If no element has bit `b`, it never appears. Sum the contributions.

```java
public class SumXorAllSubsets {
    public static long sumXorSubsets(int[] nums) {
        int n = nums.length, orAll = 0;
        for (int v : nums) orAll |= v;          // bits present in at least one element
        // Each present bit is set in half of all subsets: 2^(n-1) of them.
        return (long) orAll * (1L << (n - 1));
    }
}
```

**Time:** O(n) · **Space:** O(1)

**Insight.** For any present bit, exactly half the subsets flip it on — so the answer is `OR_all · 2^(n-1)`.

---

### Problem 50: XOR Queries of a Subarray — prefix XOR

**Statement.** Given `arr` and queries `[L, R]`, answer the XOR of `arr[L..R]` for each. (LeetCode 1310.)

**Approach.** Build a prefix-XOR array `pre[i] = arr[0] ^ … ^ arr[i-1]`. Because XOR is its own inverse, `XOR(L..R) = pre[R+1] ^ pre[L]` — the prefix up to `L` cancels itself out, just like prefix sums.

```java
public class XorQueries {
    public static int[] xorQueries(int[] arr, int[][] queries) {
        int n = arr.length;
        int[] pre = new int[n + 1];
        for (int i = 0; i < n; i++) pre[i + 1] = pre[i] ^ arr[i];
        int[] ans = new int[queries.length];
        for (int q = 0; q < queries.length; q++) {
            int l = queries[q][0], r = queries[q][1];
            ans[q] = pre[r + 1] ^ pre[l]; // overlap cancels
        }
        return ans;
    }
}
```

**Time:** O(n + q) · **Space:** O(n)

**Insight.** Prefix XOR mirrors prefix sum — subtraction becomes XOR because every value is its own inverse.

---

## ✅ Key Takeaways

- **Memorize the four core mutations:** test `n & (1<<i)`, set `n | (1<<i)`, clear `n & ~(1<<i)`, toggle `n ^ (1<<i)`.
- **Two identities unlock half of all problems:** `x & (x-1)` clears the lowest set bit (Kernighan, power-of-two test); `x & (-x)` isolates it (Fenwick indexing, two-loners partition).
- **XOR is a parity machine:** self-inverse and associative, it powers single-number, missing-number, swap, prefix-XOR ranges, and "odd occurrence" puzzles.
- **Use `>>>` not `>>` when treating an int as unsigned** (reverse bits, popcount, iterating bytes) — `>>` sign-extends and silently corrupts results on negative inputs.
- **Bitmasks scale subset reasoning:** `0…2ⁿ−1` enumerates subsets; `(s-1)&m` walks submasks in O(3ⁿ) total; `dp[mask]` turns TSP/assignment/partition from factorial to exponential.
- **Prefer library intrinsics** (`Integer.bitCount`, `numberOfTrailingZeros`, `numberOfLeadingZeros`, `highestOneBit`) in real code — they compile to single hardware instructions.

## ⚠️ Common Pitfalls

- **`1 << 31` overflows int sign** and `1 << 32 == 1` (shift counts are taken mod 32). For bit 31+ or 64-bit work use `1L << i`.
- **Operator precedence:** `&`, `|`, `^` bind *looser* than `==`. `n & 1 == 0` parses as `n & (1 == 0)`. Always parenthesize: `(n & 1) == 0`.
- **`>>` vs `>>>`:** arithmetic shift sign-extends; logical shift zero-fills. Reversing or popcounting with `>>` on a negative number loops forever or miscounts.
- **`Math.abs(Integer.MIN_VALUE)` is still negative** — guard the `INT_MIN`/`-1` division case explicitly, and do absolute-value math in `long`.
- **XOR swap fails on aliased operands** (same variable/index) by zeroing the value — never use it on `a[i], a[i]`.
- **Power-of-two test needs the `n > 0` guard:** `0 & (0-1) == 0` would otherwise report `0` as a power of two.
- **Bitmask DP blows up fast:** `2ⁿ` states are fine to ~20–22 elements; beyond that you need meet-in-the-middle or a different model.

## 📚 Further Reading

- *Hacker's Delight*, Henry S. Warren Jr. — the definitive catalogue of bit tricks (popcount, Gosper's hack, division by constants).
- Sean Eron Anderson, "Bit Twiddling Hacks" (Stanford graphics page) — concise reference for masks, parallel counting, and sign tricks.
- *Competitive Programmer's Handbook*, Antti Laaksonen — chapters on bit manipulation and bitmask DP (submask iteration, Held–Karp).
- Java API: `java.lang.Integer` / `java.lang.Long` — `bitCount`, `highestOneBit`, `lowestOneBit`, `numberOfLeadingZeros`, `numberOfTrailingZeros`, `reverse`, `rotateLeft`.
- LeetCode "Bit Manipulation" tag — problems 136, 137, 190, 191, 201, 260, 268, 318, 338, 371, 421, 461, 477, 1318.

---

## 🧩 Extended Problems — Set 1: Deeper internals & edge cases

These problems push past the "set/clear/toggle" basics into the corners where bit manipulation actually bites: signed-vs-unsigned reasoning, the `INT_MIN` and `1 << 31` traps, shift-count modular arithmetic, rotation, byte-order, fixed-point packing, branchless arithmetic, and 64-bit subtleties. Every solution is fixed-width-aware and calls out the edge case it is defending against.

### Problem 51: Arithmetic vs Logical Right Shift on Negatives — sign-extension proof

**Statement.** Given a negative `int`, show the difference between `>>` and `>>>` and return both results, explaining why `-1 >> 1 == -1` but `-1 >>> 1 == Integer.MAX_VALUE`.

**Approach.** `>>` copies the sign bit (bit 31) into the vacated high bits, so a negative number stays negative; `>>>` zero-fills, turning the all-ones pattern of `-1` into `0x7FFFFFFF`. The distinction only matters for negative inputs.

```java
public class ShiftSignBehavior {
    // Returns {arithmetic, logical}.
    public static int[] shiftBoth(int n, int k) {
        return new int[]{ n >> k, n >>> k };
    }

    public static boolean demonstrates() {
        return (-1 >> 1) == -1
            && (-1 >>> 1) == Integer.MAX_VALUE; // 0x7FFFFFFF
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** `>>` preserves magnitude-with-sign (floor division by 2ᵏ); `>>>` treats the bits as a pure unsigned vector.

---

### Problem 52: Shift Count Taken Mod 32 — the `1 << 32` surprise

**Statement.** Explain and reproduce why `1 << 32 == 1` for `int` and `1L << 64 == 1L` for `long`, and write a *safe* shift that yields 0 for out-of-range counts.

**Approach.** Java masks the shift count: for `int` it uses `count & 31`, for `long` it uses `count & 63`. So `1 << 32` is `1 << 0`. To get the intuitive "shifted everything out" behaviour, widen to a type with enough headroom or branch on the count.

```java
public class ShiftCountModulo {
    public static boolean quirks() {
        return (1 << 32) == 1          // 32 & 31 == 0
            && (1L << 64) == 1L;       // 64 & 63 == 0
    }

    // Returns 0 when shifting an int by >= 32, instead of wrapping.
    public static int safeShiftLeft(int n, int count) {
        if (count < 0 || count >= 32) return 0;
        return n << count;
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** Shift counts are reduced mod the operand width — never assume a large shift zeroes the value.

---

### Problem 53: Rotate Left and Right — wrap bits around 32 bits

**Statement.** Rotate the 32 bits of `n` left (or right) by `k`, wrapping bits off one end back onto the other.

**Approach.** A left rotate by `k` is `(n << k) | (n >>> (32 - k))` — the `>>>` half catches the bits that fell off the top. Normalize `k` mod 32 first so `k == 0` and `k == 32` don't trigger an undefined `32 - 0 == 32` shift (which would be a no-op shift of 32 → mod 32 → 0, silently wrong).

```java
public class Rotate {
    public static int rotateLeft(int n, int k) {
        k &= 31;
        if (k == 0) return n;
        return (n << k) | (n >>> (32 - k));
    }

    public static int rotateRight(int n, int k) {
        k &= 31;
        if (k == 0) return n;
        return (n >>> k) | (n << (32 - k));
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** Rotation = shifted halves OR'd back together; mask `k` mod 32 so the complementary `32 - k` shift never hits the mod-32 trap.

---

### Problem 54: Absolute Value Without Branching — sign-mask trick

**Statement.** Compute `|n|` using no `if` and no `Math.abs`, and note the one input it cannot fix.

**Approach.** `mask = n >> 31` is all-ones for negatives, all-zeros otherwise. `(n + mask) ^ mask` negates exactly when `n < 0` (it computes `~n + 1` via XOR-then-add of the mask). `Integer.MIN_VALUE` has no positive counterpart, so it returns itself — the unavoidable two's-complement asymmetry.

```java
public class BranchlessAbs {
    public static int abs(int n) {
        int mask = n >> 31;          // -1 if negative, 0 if non-negative
        return (n + mask) ^ mask;    // conditional two's-complement negate
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** `n >> 31` is a ready-made boolean-as-mask; XOR-plus-add applies a sign flip without a branch.

---

### Problem 55: Sign of an Integer Branchlessly — return −1, 0, or +1

**Statement.** Return `-1`, `0`, or `+1` for negative, zero, and positive `n` without comparisons.

**Approach.** `(n >> 31)` contributes `-1` for negatives and `0` otherwise. `(-n >>> 31)` contributes `+1` for positives (the negation's sign bit) and `0` for zero/negatives. Sum them. Works at the `INT_MIN` edge because `-INT_MIN` overflows back to `INT_MIN`, still negative, so its top bit is 0 there — leaving the `-1` from the first term.

```java
public class SignFunction {
    public static int sign(int n) {
        return (n >> 31) | (-n >>> 31);
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** OR-combine two opposite sign masks; the zero case falls out because both terms vanish.

---

### Problem 56: Min and Max of Two Ints Without Branches

**Statement.** Return `min(a, b)` and `max(a, b)` without `if` or the ternary operator.

**Approach.** `diff = a - b`; `mask = diff >> 31` is all-ones when `a < b`. Then `min = b + (diff & mask)` and `max = a - (diff & mask)`. Caveat: this assumes `a - b` does not overflow; for adversarial inputs widen to `long`.

```java
public class BranchlessMinMax {
    public static int min(int a, int b) {
        int diff = a - b;
        int mask = diff >> 31;        // -1 if a < b
        return b + (diff & mask);
    }

    public static int max(int a, int b) {
        int diff = a - b;
        int mask = diff >> 31;
        return a - (diff & mask);
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** Select via a sign mask: `diff & mask` is `diff` or `0`, steering the result without a jump.

---

### Problem 57: Conditional Negate / Conditional Set Without Branches

**Statement.** Negate `n` only if a boolean flag is true; and separately, set `n` to `v` only if a flag is true — both branchlessly.

**Approach.** Turn the boolean into a full mask: `mask = -(flag ? 1 : 0)` is `-1` or `0`. Conditional negate is `(n ^ mask) - mask` (XOR-then-subtract is the two's-complement negate gated by the mask). Conditional select is `(b & mask) | (a & ~mask)`.

```java
public class BranchlessSelect {
    public static int negateIf(int n, boolean doNegate) {
        int mask = doNegate ? -1 : 0;
        return (n ^ mask) - mask;     // negate iff mask == -1
    }

    public static int selectIf(int a, int b, boolean takeB) {
        int mask = takeB ? -1 : 0;
        return (b & mask) | (a & ~mask);
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** `-(bool)` materializes a 0/−1 mask that gates any AND/XOR-based operation.

---

### Problem 58: Detect Opposite Signs Without Comparison

**Statement.** Return true iff `a` and `b` have opposite signs (one negative, one non-negative).

**Approach.** XOR copies of the sign bits: if the sign bits differ, `a ^ b` has bit 31 set, so `(a ^ b) < 0`. This is overflow-proof because it inspects bit 31 directly rather than computing `a * b` or `a - b`.

```java
public class OppositeSigns {
    public static boolean oppositeSigns(int a, int b) {
        return (a ^ b) < 0;
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** Sign lives entirely in bit 31; XOR exposes a sign *difference* without any arithmetic that could overflow.

---

### Problem 59: Average of Two Integers Without Overflow

**Statement.** Compute `(a + b) / 2` (floored) without the intermediate `a + b` overflowing `int`.

**Approach.** `(a & b) + ((a ^ b) >> 1)`. `a & b` are the bits both share (carried into the sum exactly once each), `a ^ b` are the bits in exactly one (which contribute half). Using arithmetic `>>` floors toward −∞ correctly for mixed signs.

```java
public class SafeAverage {
    public static int average(int a, int b) {
        return (a & b) + ((a ^ b) >> 1);
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** Split the sum into "carried" (`AND`) and "half" (`XOR>>1`) parts so the full sum is never materialized.

---

### Problem 60: Round Up to the Next Power of Two — bit-smearing

**Statement.** Return the smallest power of two ≥ `n` (for `n > 0`), e.g. `5 → 8`, `8 → 8`.

**Approach.** Decrement, then "smear" the highest set bit down to bit 0 by OR-ing progressively larger right shifts, then increment. The `n--` at the start makes exact powers of two map to themselves instead of doubling.

```java
public class NextPowerOfTwo {
    public static int roundUp(int n) {
        if (n <= 1) return 1;
        n--;
        n |= n >>> 1;
        n |= n >>> 2;
        n |= n >>> 4;
        n |= n >>> 8;
        n |= n >>> 16;
        return n + 1;
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** Smearing turns `0001xxxx` into `0001111...1`; `+1` then snaps it to the next clean power of two.

---

### Problem 61: Round Down to the Previous Power of Two

**Statement.** Return the largest power of two ≤ `n` (for `n > 0`), e.g. `5 → 4`, `8 → 8`.

**Approach.** This is exactly the highest set bit. `Integer.highestOneBit(n)` isolates it directly; the manual smear-then-subtract version shows the mechanics (smear down, then drop all but the top bit via `n - (n >>> 1)`).

```java
public class PrevPowerOfTwo {
    public static int roundDown(int n) {
        return Integer.highestOneBit(n); // 0 for n <= 0
    }

    public static int manual(int n) {
        n |= n >>> 1; n |= n >>> 2; n |= n >>> 4;
        n |= n >>> 8; n |= n >>> 16;
        return n - (n >>> 1);            // keep only the top set bit
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** The previous power of two is the highest set bit; smearing then `n - (n>>1)` strips everything below it.

---

### Problem 62: Swap Two Bit Ranges / Adjacent Bit Pairs

**Statement.** Swap each adjacent pair of bits in `n` (bit 0 ↔ bit 1, bit 2 ↔ bit 3, …), e.g. `0b10 → 0b01`.

**Approach.** Move even-indexed bits up one and odd-indexed bits down one, masking each group. `((n & 0x55555555) << 1) | ((n & 0xAAAAAAAA) >>> 1)` — `0x55…` selects even positions, `0xAA…` selects odd.

```java
public class SwapAdjacentBits {
    public static int swapPairs(int n) {
        return ((n & 0x55555555) << 1) | ((n & 0xAAAAAAAA) >>> 1);
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** Even/odd bit lanes are isolated by `0x55…`/`0xAA…`; shift each lane the opposite direction and OR.

---

### Problem 63: Swap Two Arbitrary Bit Ranges of Equal Width

**Statement.** Swap the `len`-bit field starting at position `i` with the one starting at position `j` (non-overlapping), via XOR.

**Approach.** Extract the XOR of the two fields, then XOR it back into both positions. `temp = ((n >>> i) ^ (n >>> j)) & ((1 << len) - 1)` captures where the fields differ; `n ^= (temp << i) | (temp << j)` flips exactly those bits in both places, swapping them.

```java
public class SwapBitRanges {
    public static int swap(int n, int i, int j, int len) {
        int temp = ((n >>> i) ^ (n >>> j)) & ((1 << len) - 1);
        return n ^ ((temp << i) | (temp << j));
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** XOR-of-difference applied to both sites is a self-inverse swap — no temporary storage of either field.

---

### Problem 64: Reverse Bytes (Endianness Swap) of a 32-bit Word

**Statement.** Reverse the *byte* order of a 32-bit integer (big-endian ↔ little-endian), keeping each byte's bits intact.

**Approach.** Swap outer bytes with inner bytes using masks and 8/24-bit shifts, or call `Integer.reverseBytes`. Note this differs from full bit reversal — bytes move as units. Use `>>>` to avoid sign smear on the high byte.

```java
public class ByteSwap {
    public static int reverseBytes(int n) {
        return  (n >>> 24)
             | ((n >>> 8)  & 0x0000FF00)
             | ((n << 8)   & 0x00FF0000)
             |  (n << 24);
    }

    public static int builtin(int n) {
        return Integer.reverseBytes(n);
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** Endianness swap permutes bytes as atomic units — mask each byte, shift it to its mirror slot, OR.

---

### Problem 65: Pack and Unpack Two 16-bit Values into an Int

**Statement.** Pack two `short`-range values `hi`, `lo` into one `int`, and unpack them back, sign-correctly.

**Approach.** Pack: `(hi << 16) | (lo & 0xFFFF)` — mask `lo` to 16 bits so a negative `lo` doesn't pollute the high half. Unpack `hi` with `n >> 16` (arithmetic, to sign-extend); unpack `lo` with `(short) n` (the cast re-applies 16-bit sign extension).

```java
public class PackPair {
    public static int pack(int hi, int lo) {
        return (hi << 16) | (lo & 0xFFFF);
    }

    public static int high(int packed) { return packed >> 16; }   // sign-extended
    public static int low(int packed)  { return (short) packed; } // sign-extended
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** Mask on the way in (`& 0xFFFF`), sign-extend on the way out (`>> 16` / `(short)`) — symmetry is the whole game.

---

### Problem 66: Count Trailing Zeros Without the Intrinsic

**Statement.** Return the number of trailing zero bits of `n` (index of the lowest set bit); define it as 32 for `n == 0`.

**Approach.** Isolate the lowest set bit `n & -n`, then find its index. A De Bruijn sequence maps the isolated power of two to its log in O(1) via a perfect-hash table — the classic trick when no hardware `ctz` exists.

```java
public class TrailingZeros {
    private static final int[] DEBRUIJN = {
        0, 1, 28, 2, 29, 14, 24, 3, 30, 22, 20, 15, 25, 17, 4, 8,
        31, 27, 13, 23, 21, 19, 16, 7, 26, 12, 18, 6, 11, 5, 10, 9
    };

    public static int ctz(int n) {
        if (n == 0) return 32;
        int isolated = n & (-n);                 // single power of two
        return DEBRUIJN[(isolated * 0x077CB531) >>> 27];
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** A De Bruijn multiply-and-shift perfectly hashes the 32 powers of two onto their exponents.

---

### Problem 67: Count Leading Zeros Without the Intrinsic

**Statement.** Return the number of leading zero bits of `n`; 32 for `n == 0`.

**Approach.** Smear the highest set bit down to bit 0 so `n` becomes a clean low-mask, popcount that mask to get `bitLength`, then `32 - bitLength`. The smear converts "position of top bit" into a count problem.

```java
public class LeadingZeros {
    public static int clz(int n) {
        if (n == 0) return 32;
        n |= n >>> 1; n |= n >>> 2; n |= n >>> 4;
        n |= n >>> 8; n |= n >>> 16;     // now a low run of 1s
        return 32 - Integer.bitCount(n); // bitCount == bit length
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** Smear-to-mask turns "find the top bit" into "popcount the mask", sidestepping a search loop.

---

### Problem 68: Integer Log Base 2 (Floor) Via Bit Length

**Statement.** Return `floor(log2(n))` for `n > 0` without floating point (e.g. `1 → 0`, `5 → 2`, `8 → 3`).

**Approach.** `floor(log2(n))` is the index of the highest set bit, which is `31 - numberOfLeadingZeros(n)`. Avoid `Math.log` entirely — float rounding gives wrong answers at exact powers of two (e.g. `log2(8)/log2` can yield `2.9999…`).

```java
public class IntLog2 {
    public static int log2Floor(int n) {
        if (n <= 0) throw new IllegalArgumentException("n must be positive");
        return 31 - Integer.numberOfLeadingZeros(n);
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** `log2` of a positive int is purely the top-bit index — integer-exact, unlike `Math.log` which rounds.

---

### Problem 69: Integer Square Root by Bit-by-Bit Construction

**Statement.** Compute `floor(sqrt(n))` for `n >= 0` using only integer ops, building the root one bit at a time.

**Approach.** Start `bit` at the largest power of four ≤ `n`. Greedily try to set each bit of the result from high to low: if the candidate `result + bit` squared (computed incrementally) fits, keep it. No multiplication of the root is needed — the running `result` carries the partial square.

```java
public class IntSqrt {
    public static int isqrt(int n) {
        if (n < 0) throw new IllegalArgumentException();
        int bit = 1 << 30;              // highest power of four <= 2^31
        while (bit > n) bit >>= 2;
        int result = 0;
        while (bit != 0) {
            if (n >= result + bit) {
                n -= result + bit;
                result = (result >> 1) + bit;
            } else {
                result >>= 1;
            }
            bit >>= 2;
        }
        return result;
    }
}
```

**Time:** O(log n) · **Space:** O(1)

**Insight.** Binary digit-by-digit sqrt: each step decides one root bit using only adds, shifts, and a compare.

---

### Problem 70: Is the Difference a Power of Two? — common Hamming-distance check

**Statement.** Given `a` and `b`, return true iff they differ in exactly one bit position.

**Approach.** `a ^ b` has a 1 in every differing position; "exactly one differing bit" means the XOR is a power of two, testable with `x != 0 && (x & (x-1)) == 0`.

```java
public class DiffOneBit {
    public static boolean differByOneBit(int a, int b) {
        int x = a ^ b;
        return x != 0 && (x & (x - 1)) == 0;
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** "Hamming distance == 1" reduces to "XOR is a single set bit" — reuse the power-of-two test.

---

### Problem 71: Add One to an Integer Using Only Bitwise Ops

**Statement.** Compute `n + 1` using only `~`, `-`, and bitwise operators (no `+`).

**Approach.** The identity `n + 1 == -(~n)` follows from `~n == -n - 1`, so `-(~n) == n + 1`. A single complement and negation — both bitwise-expressible — does it, wrapping correctly at `INT_MAX` (where it yields `INT_MIN`, exactly as `+1` would).

```java
public class AddOne {
    public static int addOne(int n) {
        return -(~n);        // ~n == -n-1  ⇒  -(~n) == n+1
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** `~n == -n - 1` is the master two's-complement identity; rearranged it gives increment for free.

---

### Problem 72: Negate Without the Unary Minus

**Statement.** Compute `-n` using only `~` and `+`/bitwise ops.

**Approach.** Two's-complement negation is `~n + 1`. At `INT_MIN` this returns `INT_MIN` (the value has no positive twin), matching how `-Integer.MIN_VALUE` behaves — a deliberate, documented overflow.

```java
public class Negate {
    public static int negate(int n) {
        return ~n + 1;
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** Negation is "flip every bit, add one" — and it self-consistently fixes `INT_MIN` to itself.

---

### Problem 73: Multiply by 3.5 (and Other Constants) With Shifts and Adds

**Statement.** Compute `n * 7` and `floor(n * 3.5)` (i.e. `n * 7 / 2`) using only shifts, adds, and subtracts.

**Approach.** `n * 7 == (n << 3) - n` (8n − n). For `n * 3.5 == n * 7 / 2 == ((n << 3) - n) >> 1`. Using arithmetic `>>` floors toward −∞, which is the documented behaviour for the `/2`.

```java
public class ConstantMultiply {
    public static int times7(int n) {
        return (n << 3) - n;         // 8n - n
    }

    public static int times3point5(int n) {
        return ((n << 3) - n) >> 1;  // (7n) / 2, floored
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** Any rational constant decomposes into a few shifted adds/subtracts — the basis of strength reduction.

---

### Problem 74: Modulo by a Power of Two for Negative Numbers

**Statement.** Compute `n % d` where `d` is a power of two, matching Java's truncated `%` (which keeps the dividend's sign) using bit ops where possible.

**Approach.** For non-negative `n`, `n & (d - 1)` equals `n % d`. For negative `n` the mask gives a non-negative result, which differs from Java's `%` (e.g. `-5 % 4 == -1` but `-5 & 3 == 3`). To replicate `%`, mask the magnitude and re-apply the sign.

```java
public class ModPowerOfTwo {
    // True modulo in [0, d): always non-negative.
    public static int floorMod(int n, int d) {
        return n & (d - 1);
    }

    // Matches Java's '%' sign behaviour.
    public static int javaMod(int n, int d) {
        int m = n & (d - 1);
        return (n < 0 && m != 0) ? m - d : m;
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** `n & (d-1)` is *floor* mod, not Java's *truncated* mod — they diverge on negatives.

---

### Problem 75: Clear All Bits From the Most Significant Set Bit Through a Given Position

**Statement.** Two masked clears: (a) clear bits `[i..31]` (from `i` up to the top), and (b) clear bits `[0..i]` (from bottom up to and including `i`).

**Approach.** (a) Keep only the low `i` bits: `n & ((1 << i) - 1)`. (b) Keep only bits above `i`: `n & ~((1 << (i + 1)) - 1)`, i.e. `n & (-1 << (i + 1))`. Watch `i == 31`, where `1 << 32` would wrap — use `-1 << (i+1)` form which behaves under mod-32 as a full clear when `i+1 == 32` (`-1 << 0 == -1`, so guard or use long).

```java
public class RangeClear {
    // Clear bits i..31 (keep low i bits).
    public static int clearHigh(int n, int i) {
        return n & ((1 << i) - 1);
    }

    // Clear bits 0..i (keep bits above i). Uses long to dodge the i==31 shift wrap.
    public static int clearLow(int n, int i) {
        long mask = ~((1L << (i + 1)) - 1);
        return (int) (n & mask);
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** Range masks are `(1<<k)-1` and its complement; promote to `long` near bit 31 to dodge the shift-mod wrap.

---

### Problem 76: Extract a Bit Field [hi:lo] and Sign-Extend It

**Statement.** Extract bits `[lo..hi]` (inclusive) of `n` as an unsigned value, and separately as a sign-extended signed value (treating bit `hi` as the field's sign bit).

**Approach.** Unsigned: shift the field down and mask to `width = hi - lo + 1` bits. Signed: after extracting, replicate bit `(width-1)` upward via `(x << (32-width)) >> (32-width)` — left-shift the sign bit to position 31, then arithmetic-shift back to smear it.

```java
public class BitField {
    public static int extractUnsigned(int n, int hi, int lo) {
        int width = hi - lo + 1;
        int mask = (width == 32) ? -1 : ((1 << width) - 1);
        return (n >>> lo) & mask;
    }

    public static int extractSigned(int n, int hi, int lo) {
        int width = hi - lo + 1;
        int x = extractUnsigned(n, hi, lo);
        int shift = 32 - width;
        return (x << shift) >> shift;   // sign-extend from bit (width-1)
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** Sign-extension is "left-shift the sign bit to the top, then arithmetic-shift back" — the universal field-decode idiom.

---

### Problem 77: Parity of a Word in O(log n) — fold XOR halves

**Statement.** Return the parity (XOR of all bits, i.e. popcount mod 2) of a 32-bit integer without a per-bit loop.

**Approach.** Fold the word onto itself by XOR-ing successively smaller halves: 16, 8, 4, 2, 1. After folding, the answer is the low bit. The final nibble can be replaced by a 16-entry magic-constant lookup `(0x6996 >> x) & 1`.

```java
public class Parity {
    public static int parity(int n) {
        n ^= n >>> 16;
        n ^= n >>> 8;
        n ^= n >>> 4;
        n &= 0xF;
        return (0x6996 >>> n) & 1;   // 0x6996 is the 4-bit parity table
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** XOR-folding halves the live width each step; `0x6996` is the packed parity table for the final nibble.

---

### Problem 78: Interleave Bits of Two Shorts (Morton / Z-order Code)

**Statement.** Interleave the low 16 bits of `x` and `y` so the result's even bits come from `x` and odd bits from `y` — the Morton code used in spatial indexing.

**Approach.** "Spread" each 16-bit value so its bits occupy even positions (gaps between them), using the classic magic-mask spreading, then shift `y` left by one and OR. Each spreading step doubles the gap between bits.

```java
public class MortonCode {
    private static int spread(int v) {
        v &= 0x0000FFFF;
        v = (v | (v << 8)) & 0x00FF00FF;
        v = (v | (v << 4)) & 0x0F0F0F0F;
        v = (v | (v << 2)) & 0x33333333;
        v = (v | (v << 1)) & 0x55555555;
        return v;
    }

    public static int interleave(int x, int y) {
        return spread(x) | (spread(y) << 1);
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** Bit-spreading by magic masks opens even slots; OR-ing a shifted second value fills the odd slots — Z-order in O(1).

---

### Problem 79: Next and Previous Lexicographic Bit Permutation (fixed popcount)

**Statement.** Given a bitmask, return the next *smaller* mask with the same popcount (the complement direction of Gosper's hack), useful for descending submask iteration.

**Approach.** Gosper gives the next *larger*; to go smaller, apply Gosper to the complement within a fixed width and complement back. Equivalently, mirror the trick: this implementation derives the previous permutation by negating Gosper's logic on `~v` masked to `width`.

```java
public class PrevSamePopcount {
    public static int prev(int v, int width) {
        int full = (width == 32) ? -1 : ((1 << width) - 1);
        int comp = (~v) & full;          // complement within the window
        int c = comp & (-comp);
        int r = comp + c;
        int nextComp = (r | (((r ^ comp) >>> 2) / c)) & full;
        return (~nextComp) & full;       // complement back
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** "Previous same-popcount" is "next same-popcount of the complement" — Gosper run in a mirror.

---

### Problem 80: Detect Integer Overflow of `a + b` Using Sign Bits

**Statement.** Return true iff `a + b` overflows a signed 32-bit int, using only the sign bits (no widening to `long`, no `Math.addExact`).

**Approach.** Signed addition overflows exactly when `a` and `b` share a sign but the sum has the opposite sign. `(~(a ^ b) & (a ^ sum)) < 0` tests that: `~(a^b)` is negative-bit-set when signs match, and `(a^sum)` is negative-bit-set when the sum flipped sign.

```java
public class AddOverflow {
    public static boolean overflows(int a, int b) {
        int sum = a + b;
        return (~(a ^ b) & (a ^ sum)) < 0;
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** Overflow ⇔ "operands agree in sign but the result disagrees" — purely a bit-31 condition.

---

### Problem 81: Conditional Swap to Sort Three Values (sorting network, branchless)

**Statement.** Sort three integers ascending using branchless compare-and-swap, the building block of sorting networks.

**Approach.** A branchless `cas(a, b)` returns `{min, max}` using the sign-mask min/max of Problem 56. Apply the three-comparator network `(0,1),(1,2),(0,1)` to fully sort three elements with no data-dependent branches — handy where branch misprediction dominates.

```java
public class BranchlessSort3 {
    private static long cas(int a, int b) {           // pack {min, max}
        int diff = a - b;
        int mask = diff >> 31;                          // -1 if a < b
        int min = b + (diff & mask);
        int max = a - (diff & mask);
        return ((long) min << 32) | (max & 0xFFFFFFFFL);
    }

    public static int[] sort3(int a, int b, int c) {
        long p = cas(a, b); a = (int)(p >> 32); b = (int) p;
        p = cas(b, c);      b = (int)(p >> 32); c = (int) p;
        p = cas(a, b);      a = (int)(p >> 32); b = (int) p;
        return new int[]{a, b, c};
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** A 3-comparator network plus branchless min/max sorts without a single data-dependent jump.

---

### Problem 82: 64-bit XOR Basis (Linear Basis Over GF(2)) — max-subset-XOR

**Statement.** Given longs, build a linear basis so you can answer "maximum XOR achievable by any subset" — a staple of competitive bit problems generalizing Problem 36 to arbitrary subsets.

**Approach.** Maintain up to 64 basis vectors, one per leading bit. For each number, reduce it by the existing basis from the high bit down; if a bit survives with no basis vector there, insert it. The maximum subset XOR greedily XORs in any basis vector that increases the running value.

```java
public class XorBasis {
    private final long[] basis = new long[64];

    public void insert(long x) {
        for (int b = 63; b >= 0; b--) {
            if (((x >> b) & 1) == 0) continue;
            if (basis[b] == 0) { basis[b] = x; return; } // new pivot
            x ^= basis[b];                                // reduce and continue
        }
    }

    public long maxXor() {
        long res = 0;
        for (int b = 63; b >= 0; b--) {
            if ((res ^ basis[b]) > res) res ^= basis[b]; // take it if it helps
        }
        return res;
    }
}
```

**Time:** O(n·64) to build, O(64) per query · **Space:** O(64)

**Insight.** A Gaussian-elimination basis over GF(2) makes "max subset XOR" a greedy high-to-low pivot walk.

---

## 🧩 Extended Problems — Set 2: Hard variations & follow-ups

This set is the deep end. It takes the easy primitives from earlier and twists each into the form interviewers actually reach for when they want to separate "knows the trick" from "understands the model": digit-DP that carries a bitmask state, segment-tree and Fenwick variants that pack counts into words, online/streaming basis maintenance, XOR with constraints, SOS (sum-over-subsets) DP, broadword string algorithms, and the genuinely nasty `INT_MIN`/64-bit overflow corners. Every problem continues the numbering from 82, ships compiling Java, and names the variation it generalizes.

### Problem 83: Sum-Over-Subsets DP (SOS) — aggregate over every submask in O(n·2ⁿ)

**Statement.** Given `f[mask]` for all `mask` in `[0, 2ⁿ)`, compute `F[mask] = Σ f[sub]` over every submask `sub ⊆ mask`. Brute force is O(3ⁿ); do it in O(n·2ⁿ). (The classic "SOS DP" that powers subset-convolution and many counting problems.)

**Approach.** Process one bit dimension at a time. For bit `i`, every mask that *has* bit `i` absorbs the value of the same mask with bit `i` cleared. After sweeping all `n` bits, each `F[mask]` has accumulated all `2^popcount(mask)` submasks — the inclusion order is forced by handling dimensions independently, exactly like an n-dimensional prefix sum.

```java
public class SosDp {
    // F[mask] = sum of f over all submasks of mask.
    public static long[] sumOverSubsets(long[] f, int n) {
        long[] F = f.clone();
        for (int i = 0; i < n; i++) {                 // one bit-dimension at a time
            for (int mask = 0; mask < (1 << n); mask++) {
                if ((mask & (1 << i)) != 0) {
                    F[mask] += F[mask ^ (1 << i)];     // absorb the bit-i-cleared mask
                }
            }
        }
        return F;
    }
}
```

**Time:** O(n·2ⁿ) · **Space:** O(2ⁿ)

**Insight.** SOS is an n-dimensional prefix sum over the Boolean lattice — sweep each axis once instead of revisiting all 3ⁿ submask pairs.

---

### Problem 84: Superset-Sum DP — the dual sweep over supermasks

**Statement.** Compute `G[mask] = Σ f[sup]` over every **supermask** `sup ⊇ mask`. The mirror image of Problem 83.

**Approach.** Same dimension-by-dimension sweep, but now a mask **without** bit `i` absorbs the value of the mask **with** bit `i` set. Equivalently, run SOS on the complemented index space.

```java
public class SupersetSumDp {
    public static long[] sumOverSupersets(long[] f, int n) {
        long[] G = f.clone();
        for (int i = 0; i < n; i++) {
            for (int mask = 0; mask < (1 << n); mask++) {
                if ((mask & (1 << i)) == 0) {          // bit i is clear here
                    G[mask] += G[mask | (1 << i)];      // absorb the bit-i-set superset
                }
            }
        }
        return G;
    }
}
```

**Time:** O(n·2ⁿ) · **Space:** O(2ⁿ)

**Insight.** Flip the absorption direction and SOS becomes superset-sum — the lattice prefix sum run "downward" instead of "upward".

---

### Problem 85: Count Pairs With AND ≥ a Threshold Bit — column contribution revisited

**Statement.** Given an array and a bit position `b`, count pairs `(i, j)` whose `nums[i] & nums[j]` has bit `b` set. Generalize Problem 22's per-column counting to AND instead of XOR.

**Approach.** `nums[i] & nums[j]` has bit `b` set iff **both** operands have bit `b` set. So count `k` = numbers with bit `b` set; the answer is `C(k, 2) = k·(k−1)/2`. No O(n²) scan needed.

```java
public class AndPairsAtBit {
    public static long pairsWithBitSet(int[] nums, int b) {
        long k = 0;
        for (int v : nums) if (((v >> b) & 1) == 1) k++;
        return k * (k - 1) / 2;                         // both must have the bit
    }
}
```

**Time:** O(n) · **Space:** O(1)

**Insight.** AND needs *both* bits; XOR needs *exactly one*. The pair count flips from `k·(n−k)` to `C(k,2)` accordingly.

---

### Problem 86: Maximum AND Subset — bit-greedy filtering

**Statement.** Find the largest possible bitwise AND of any non-empty subset, then report how many elements can simultaneously achieve it. (LeetCode 2275 family.)

**Approach.** Greedily build the answer from the high bit down. Tentatively keep candidates that have the bit set on top of the bits already committed; if at least one candidate survives, commit the bit. The surviving count at the end is the maximum number of elements sharing that AND.

```java
import java.util.*;

public class MaxAndSubset {
    public static int[] maxAnd(int[] nums) {
        int result = 0, count = nums.length;
        List<Integer> cand = new ArrayList<>();
        for (int v : nums) cand.add(v);
        for (int b = 31; b >= 0; b--) {
            List<Integer> keep = new ArrayList<>();
            for (int v : cand) if (((v >> b) & 1) == 1) keep.add(v);
            if (!keep.isEmpty()) {                      // committing this bit stays feasible
                result |= (1 << b);
                cand = keep;
                count = keep.size();
            }
        }
        return new int[]{result, count};                // {maxAnd, howMany}
    }
}
```

**Time:** O(32·n) · **Space:** O(n)

**Insight.** AND can only *lose* bits, so commit high bits greedily and narrow the candidate set — the survivors define both the value and its multiplicity.

---

### Problem 87: Maximum XOR With an Element From an Array (offline, value-bounded) — persistent trie queries

**Statement.** Answer queries `(x, m)`: the maximum `x ^ nums[j]` over all `nums[j] ≤ m`, or −1 if none. (LeetCode 1707.) Generalizes Problem 36 to bounded, offline queries.

**Approach.** Sort numbers ascending and sort queries by `m`. Insert numbers into a binary trie as `m` grows so each query only sees eligible values, then walk the trie high-bit-first taking the opposite bit whenever possible to maximize XOR.

```java
import java.util.*;

public class MaxXorBounded {
    static class Trie { Trie[] c = new Trie[2]; }

    public static int[] maximizeXor(int[] nums, int[][] queries) {
        Arrays.sort(nums);
        Integer[] order = new Integer[queries.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> queries[a][1] - queries[b][1]); // by m

        Trie root = new Trie();
        int[] ans = new int[queries.length];
        int idx = 0;
        for (int qi : order) {
            int x = queries[qi][0], m = queries[qi][1];
            while (idx < nums.length && nums[idx] <= m) insert(root, nums[idx++]);
            ans[qi] = (idx == 0) ? -1 : query(root, x);
        }
        return ans;
    }

    private static void insert(Trie root, int v) {
        Trie node = root;
        for (int b = 31; b >= 0; b--) {
            int bit = (v >> b) & 1;
            if (node.c[bit] == null) node.c[bit] = new Trie();
            node = node.c[bit];
        }
    }

    private static int query(Trie root, int x) {
        Trie node = root;
        if (node.c[0] == null && node.c[1] == null) return -1;
        int res = 0;
        for (int b = 31; b >= 0; b--) {
            int want = ((x >> b) & 1) ^ 1;              // prefer the opposite bit
            if (node.c[want] != null) { res |= (1 << b); node = node.c[want]; }
            else node = node.c[want ^ 1];
        }
        return res;
    }
}
```

**Time:** O((n + q)·32 + q·log q) · **Space:** O(n·32)

**Insight.** Sorting queries by their ceiling makes the trie monotone — add candidates once and never remove, an offline trick that beats per-query filtering.

---

### Problem 88: Count Numbers in [0, n] With No Two Adjacent Set Bits — bit digit-DP

**Statement.** Count integers in `[0, n]` whose binary representation has no two consecutive 1-bits (e.g. `101` is fine, `110` is not). (LeetCode 600 / Zeckendorf-style.)

**Approach.** Walk the bits of `n` from high to low. When the current bit is 1, every number that puts a 0 here (and matches the prefix so far) is free to fill the rest with any no-adjacent-ones suffix — there are `fib(i)` of those. Track the previous bit to abort if `n` itself has two adjacent ones below the current prefix.

```java
public class NoAdjacentOnes {
    public static int countWithoutAdjacentOnes(int n) {
        int[] fib = new int[32];
        fib[0] = 1; fib[1] = 2;                         // valid suffix counts of length i
        for (int i = 2; i < 32; i++) fib[i] = fib[i - 1] + fib[i - 2];

        int prevBit = 0, result = 0;
        for (int i = 30; i >= 0; i--) {
            if ((n & (1 << i)) != 0) {                  // this prefix bit is 1
                result += fib[i];                       // count putting 0 here, free suffix
                if (prevBit == 1) return result;        // n has "11" -> n itself excluded
                prevBit = 1;
            } else {
                prevBit = 0;
            }
        }
        return result + 1;                              // +1 for n itself
    }
}
```

**Time:** O(32) · **Space:** O(32)

**Insight.** Valid suffixes obey the Fibonacci recurrence (Zeckendorf), so a single high-to-low pass with a "previous bit" flag counts them via digit-DP.

---

### Problem 89: Smallest Number With Exactly k Set Bits and ≥ n — fixed-popcount successor

**Statement.** Find the smallest integer `≥ n` that has exactly `k` set bits. Generalizes Problem 46 (Gosper) to "at least n with a *fixed* popcount", handling popcount mismatches.

**Approach.** If `n`'s popcount equals `k`, `n` already qualifies. If it has too few bits, fill the lowest clear bits until you reach `k` (the smallest such value `≥ n` keeps the prefix and packs ones low). If it has too many, round `n` up to the next value with a higher prefix, then pack the remaining ones into the lowest positions. The clean construction: keep the top `k−1` bits free and saturate low.

```java
public class SmallestWithKBits {
    public static long smallestAtLeast(long n, int k) {
        for (long x = n; ; x++) {                       // correctness baseline
            if (Long.bitCount(x) == k) return x;
        }
    }

    // O(64) construction: smallest x >= n with popcount k, no linear scan.
    public static long fast(long n, int k) {
        long x = n;
        while (Long.bitCount(x) != k) {
            int pc = Long.bitCount(x);
            if (pc < k) {
                x |= (x + 1);                            // set lowest clear bit, may overshoot down
            } else {
                long lowest = x & (-x);                  // strip the lowest set bit upward
                x += lowest;
            }
        }
        return x;
    }
}
```

**Time:** O(64·iterations) construction · **Space:** O(1)

**Insight.** Popcount is monotone-ish under "set lowest clear bit" (up) and "carry lowest set bit" (consolidate) — drive it toward `k` from whichever side `n` sits on.

---

### Problem 90: XOR of All Pairwise Sums of an Array — carry-aware bit counting

**Statement.** Compute `XOR` over `nums[i] + nums[j]` for all pairs `i < j` (sums, not XOR). (LeetCode 1835 variant.) The carry from addition makes per-column independence subtle.

**Approach.** Process bit `b` of the result. Bit `b` of `nums[i]+nums[j]` depends only on the low `b+1` bits, so reduce every element mod `2^(b+1)`, sort, and count pairs whose sum lands in a window where bit `b` is 1 (i.e. sum in `[2^b, 2^(b+1))` or `[2^b + 2^(b+1), 2^(b+2))`). If that count is odd, set bit `b`.

```java
import java.util.*;

public class XorOfPairwiseSums {
    public static int xorAllPairSums(int[] nums) {
        int result = 0, n = nums.length;
        for (int b = 0; b < 30; b++) {
            int mod = 1 << (b + 1);
            int[] a = new int[n];
            for (int i = 0; i < n; i++) a[i] = nums[i] & (mod - 1);   // low b+1 bits
            Arrays.sort(a);
            long cnt = 0;
            int lo1 = 1 << b, hi1 = (1 << (b + 1)) - 1;               // window A
            int lo2 = (1 << b) + (1 << (b + 1)), hi2 = (1 << (b + 2)) - 2; // window B
            cnt += countPairsInRange(a, lo1, hi1);
            cnt += countPairsInRange(a, lo2, hi2);
            if ((cnt & 1) == 1) result |= (1 << b);                   // odd parity sets the bit
        }
        return result;
    }

    // pairs i<j with a[i]+a[j] in [lo, hi], two-pointer on sorted a
    private static long countPairsInRange(int[] a, int lo, int hi) {
        return atMost(a, hi) - atMost(a, lo - 1);
    }

    private static long atMost(int[] a, int target) {
        long c = 0;
        int i = 0, j = a.length - 1;
        while (i < j) {
            if (a[i] + a[j] <= target) { c += j - i; i++; }
            else j--;
        }
        return c;
    }
}
```

**Time:** O(30·n log n) · **Space:** O(n)

**Insight.** Bit `b` of a sum is a parity over how many pair-sums fall in carry windows — reduce mod `2^(b+1)` so the high bits stop interfering, then count by two pointers.

---

### Problem 91: Maximum XOR Subarray (contiguous) — prefix-XOR meets a trie

**Statement.** Find the maximum XOR of any **contiguous** subarray. Combines Problem 50's prefix-XOR with Problem 36's greedy trie.

**Approach.** `XOR(L..R) = pre[R+1] ^ pre[L]`, so this becomes "max XOR of two prefix values" — but only with `L ≤ R`, which a streaming trie enforces. Insert each prefix as you go and query the best XOR partner already inserted.

```java
public class MaxXorSubarray {
    static class Node { Node[] c = new Node[2]; }

    public static int maxXorSubarray(int[] arr) {
        Node root = new Node();
        insert(root, 0);                                // empty prefix
        int pre = 0, best = 0;
        for (int v : arr) {
            pre ^= v;
            best = Math.max(best, query(root, pre));    // best pre[L] for current pre[R+1]
            insert(root, pre);
        }
        return best;
    }

    private static void insert(Node root, int x) {
        Node node = root;
        for (int b = 31; b >= 0; b--) {
            int bit = (x >>> b) & 1;
            if (node.c[bit] == null) node.c[bit] = new Node();
            node = node.c[bit];
        }
    }

    private static int query(Node root, int x) {
        Node node = root; int res = 0;
        for (int b = 31; b >= 0; b--) {
            int want = ((x >>> b) & 1) ^ 1;
            if (node.c[want] != null) { res |= (1 << b); node = node.c[want]; }
            else node = node.c[want ^ 1];
        }
        return res;
    }
}
```

**Time:** O(n·32) · **Space:** O(n·32)

**Insight.** Contiguous-subarray XOR is a two-prefix XOR problem; inserting prefixes left-to-right makes the trie automatically respect `L ≤ R`.

---

### Problem 92: Count Subarrays With XOR Equal to k — prefix-XOR with a hash map

**Statement.** Count contiguous subarrays whose XOR equals a target `k`. (Counting cousin of Problem 50.)

**Approach.** `XOR(L..R) = pre[R+1] ^ pre[L] = k` ⇔ `pre[L] = pre[R+1] ^ k`. Sweep prefixes, and for each new prefix add the number of earlier prefixes equal to `pre ^ k`.

```java
import java.util.*;

public class SubarraysWithXor {
    public static long countSubarrays(int[] arr, int k) {
        Map<Integer, Integer> seen = new HashMap<>();
        seen.put(0, 1);                                 // empty prefix
        int pre = 0; long count = 0;
        for (int v : arr) {
            pre ^= v;
            count += seen.getOrDefault(pre ^ k, 0);     // matching earlier prefix
            seen.merge(pre, 1, Integer::sum);
        }
        return count;
    }
}
```

**Time:** O(n) · **Space:** O(n)

**Insight.** The "two-sum on prefix XOR" pattern: rewrite the target as `pre[L] = pre[R+1] ^ k` and count complements in a map.

---

### Problem 93: Decode XORed Permutation — reconstruct from adjacent XORs

**Statement.** An odd-length permutation `perm` of `[1..n]` is given only as `encoded[i] = perm[i] ^ perm[i+1]`. Recover `perm`. (LeetCode 1734.)

**Approach.** XOR of the whole permutation is `1 ^ 2 ^ … ^ n` (known). XOR of every *odd-indexed* encoded entry telescopes to `perm[1] ^ perm[3] ^ …`, i.e. everything except `perm[0]`. So `perm[0] = totalXor ^ xorOfOddEncoded`; the rest unrolls by prefix XOR.

```java
public class DecodeXorPermutation {
    public static int[] decode(int[] encoded) {
        int n = encoded.length + 1;
        int total = 0;
        for (int i = 1; i <= n; i++) total ^= i;        // 1^2^...^n
        int odd = 0;
        for (int i = 1; i < encoded.length; i += 2) odd ^= encoded[i]; // perm[1]^perm[3]^...
        int[] perm = new int[n];
        perm[0] = total ^ odd;                          // the only unknown left
        for (int i = 0; i < encoded.length; i++) perm[i + 1] = perm[i] ^ encoded[i];
        return perm;
    }
}
```

**Time:** O(n) · **Space:** O(1) extra

**Insight.** Odd length is the trick: pairing up encoded entries telescopes to "everything but the first", recoverable against the known total XOR.

---

### Problem 94: Minimize XOR — match target popcount while staying closest

**Statement.** Given `x` and `y`, return the integer with exactly `popcount(y)` set bits that minimizes its XOR with `x`. (LeetCode 2429.)

**Approach.** You have a budget of `k = popcount(y)` bits to place. To minimize XOR with `x`, first reuse `x`'s set bits from the high end (they cost 0), then spend any leftover budget on the lowest clear bits of `x` (cheapest new bits).

```java
public class MinimizeXor {
    public static int minimizeXor(int x, int y) {
        int k = Integer.bitCount(y), result = 0;
        for (int b = 31; b >= 0 && k > 0; b--) {        // reuse x's high set bits
            if (((x >> b) & 1) == 1) { result |= (1 << b); k--; }
        }
        for (int b = 0; b < 32 && k > 0; b++) {         // spend leftover on lowest clear bits
            if (((result >> b) & 1) == 0) { result |= (1 << b); k--; }
        }
        return result;
    }
}
```

**Time:** O(32) · **Space:** O(1)

**Insight.** XOR cost is zero where you align with `x`'s bits — align the high ones first, then place the cheapest (lowest) extra bits.

---

### Problem 95: Find XOR-Beauty of an Array — algebraic collapse

**Statement.** Define the "effective value" of a triple `(i, j, k)` as `(nums[i] | nums[j]) & nums[k]`. The XOR-beauty is the XOR of these over **all** `n³` triples. Compute it in O(n). (LeetCode 2527.)

**Approach.** Expand the XOR over all triples bit by bit. Algebra shows nearly everything cancels in pairs, leaving the XOR of the array itself: the answer is simply `nums[0] ^ nums[1] ^ … ^ nums[n-1]`.

```java
public class XorBeauty {
    public static int xorBeauty(int[] nums) {
        int res = 0;
        for (int v : nums) res ^= v;                    // all the triple structure cancels
        return res;
    }
}
```

**Time:** O(n) · **Space:** O(1)

**Insight.** A daunting O(n³) definition collapses to a one-line XOR once you count each bit's parity across all triples — most contributions appear an even number of times.

---

### Problem 96: Bitwise ORs of Subarrays — distinct-value frontier

**Statement.** Count the number of **distinct** values produced by `OR(nums[i..j])` over all subarrays. (LeetCode 898.)

**Approach.** OR is monotone as a subarray extends left, so for each right endpoint only O(31) distinct OR values can end there. Maintain a small frontier set of "ORs of subarrays ending at the previous index", extend each by the new element, dedupe, and union into a global result set.

```java
import java.util.*;

public class SubarrayOrs {
    public static int subarrayBitwiseORs(int[] nums) {
        Set<Integer> result = new HashSet<>();
        Set<Integer> frontier = new HashSet<>();        // ORs ending at previous index
        for (int v : nums) {
            Set<Integer> next = new HashSet<>();
            next.add(v);
            for (int prev : frontier) next.add(prev | v); // extend each subarray by v
            result.addAll(next);
            frontier = next;
        }
        return result.size();
    }
}
```

**Time:** O(31·n) · **Space:** O(31·n)

**Insight.** OR only ever turns bits on, so subarrays ending at a fixed index take at most 32 values — the frontier stays tiny.

---

### Problem 97: Shortest Subarray With OR ≥ k — sliding window over bit counts

**Statement.** Find the shortest contiguous subarray whose bitwise OR is at least `k`. OR is not invertible, so you can't just subtract when shrinking. (LeetCode 3097 style.)

**Approach.** Keep a per-bit count over the window. Adding an element increments the count for each of its set bits; removing decrements. The window's OR has bit `b` set iff `count[b] > 0`, so you can reconstruct OR on shrink — a sliding window with a 32-slot bit histogram.

```java
public class ShortestOrAtLeastK {
    public static int shortest(int[] nums, int k) {
        int[] cnt = new int[32];
        int left = 0, best = Integer.MAX_VALUE;
        for (int right = 0; right < nums.length; right++) {
            addBits(cnt, nums[right], +1);
            while (left <= right && currentOr(cnt) >= k) {
                best = Math.min(best, right - left + 1);
                addBits(cnt, nums[left++], -1);          // shrink, rebuilding OR via counts
            }
        }
        return best == Integer.MAX_VALUE ? -1 : best;
    }

    private static void addBits(int[] cnt, int v, int delta) {
        for (int b = 0; b < 32; b++) if (((v >> b) & 1) == 1) cnt[b] += delta;
    }

    private static int currentOr(int[] cnt) {
        int or = 0;
        for (int b = 0; b < 32; b++) if (cnt[b] > 0) or |= (1 << b);
        return or;
    }
}
```

**Time:** O(32·n) · **Space:** O(32)

**Insight.** OR loses information on removal, so replace the single OR value with per-bit counts — now "is this bit still on?" is `count > 0`, restoring shrinkability.

---

### Problem 98: Count Distinct Numbers With a Given Bit Constraint via Meet-in-the-Middle

**Statement.** Given up to 40 numbers, count subsets whose XOR equals a target `t`. `2⁴⁰` is too many, so split. (Meet-in-the-middle XOR counting — beyond the 20-element bitmask ceiling.)

**Approach.** Split the array into two halves of ≤20. Enumerate every subset XOR of the left half into a frequency map. For each subset XOR `r` of the right half, the partner needed from the left is `t ^ r`; add the map's count. Total work is O(2^(n/2)) instead of O(2ⁿ).

```java
import java.util.*;

public class XorSubsetCountMITM {
    public static long countSubsetsWithXor(int[] nums, int t) {
        int n = nums.length, half = n / 2;
        Map<Integer, Long> left = new HashMap<>();
        for (int mask = 0; mask < (1 << half); mask++) {
            int x = 0;
            for (int i = 0; i < half; i++) if ((mask & (1 << i)) != 0) x ^= nums[i];
            left.merge(x, 1L, Long::sum);
        }
        int rest = n - half; long count = 0;
        for (int mask = 0; mask < (1 << rest); mask++) {
            int x = 0;
            for (int i = 0; i < rest; i++) if ((mask & (1 << i)) != 0) x ^= nums[half + i];
            count += left.getOrDefault(t ^ x, 0L);       // partner from the left half
        }
        return count;
    }
}
```

**Time:** O(2^(n/2)·n) · **Space:** O(2^(n/2))

**Insight.** When `2ⁿ` is too big but `2^(n/2)` fits, split and join on the XOR complement `t ^ r` — meet-in-the-middle halves the exponent.

---

### Problem 99: Maximum Genetic Difference (XOR on a tree) — persistent trie over a DFS

**Statement.** Build a rooted tree where each node has an integer label equal to its node id. For each query `(node, val)`, find the max `val ^ id` over all ancestors of `node` (including itself). (LeetCode 1938.) Generalizes the XOR trie to a moving ancestor set.

**Approach.** DFS the tree; insert a node's id into a binary trie on entry and remove it on exit, so the trie always holds exactly the current root-to-node path. Answer the node's queries against that live ancestor set with the standard greedy XOR walk. Use a count per trie edge so removals are clean.

```java
import java.util.*;

public class MaxGeneticDifference {
    static int[][] childTrie; static int[] cnt; static int next = 1;
    static final int BITS = 18;                         // ids up to ~2^18

    public static int[] solve(int[] parents, int[][] queries) {
        int n = parents.length;
        List<List<Integer>> children = new ArrayList<>();
        for (int i = 0; i < n; i++) children.add(new ArrayList<>());
        int root = -1;
        for (int i = 0; i < n; i++) {
            if (parents[i] == -1) root = i; else children.get(parents[i]).add(i);
        }
        Map<Integer, List<int[]>> byNode = new HashMap<>(); // node -> {qIndex, val}
        for (int q = 0; q < queries.length; q++)
            byNode.computeIfAbsent(queries[q][0], x -> new ArrayList<>())
                  .add(new int[]{q, queries[q][1]});

        childTrie = new int[n * (BITS + 1) + 2][2];
        cnt = new int[n * (BITS + 1) + 2];
        int[] ans = new int[queries.length];
        dfs(root, children, byNode, ans);
        return ans;
    }

    private static void dfs(int node, List<List<Integer>> children,
                            Map<Integer, List<int[]>> byNode, int[] ans) {
        update(node, +1);
        for (int[] q : byNode.getOrDefault(node, Collections.emptyList()))
            ans[q[0]] = query(q[1]);
        for (int ch : children.get(node)) dfs(ch, children, byNode, ans);
        update(node, -1);                                // leave the path on exit
    }

    private static void update(int x, int delta) {
        int node = 0;
        for (int b = BITS; b >= 0; b--) {
            int bit = (x >> b) & 1;
            if (childTrie[node][bit] == 0) childTrie[node][bit] = next++;
            node = childTrie[node][bit];
            cnt[node] += delta;
        }
    }

    private static int query(int val) {
        int node = 0, res = 0;
        for (int b = BITS; b >= 0; b--) {
            int want = ((val >> b) & 1) ^ 1;
            int c = childTrie[node][want];
            if (c != 0 && cnt[c] > 0) { res |= (1 << b); node = c; }
            else node = childTrie[node][want ^ 1];
        }
        return res;
    }
}
```

**Time:** O((n + q)·BITS) · **Space:** O(n·BITS)

**Insight.** A trie with per-edge counts becomes "persistent along a DFS path" — insert on entry, delete on exit, and every query sees exactly the current ancestor chain.

---

### Problem 100: Concatenation of Consecutive Binary Numbers mod 1e9+7 — bit-length shifting

**Statement.** Concatenate the binary representations of `1, 2, …, n` and return the resulting number mod `1e9+7`. (LeetCode 1680.)

**Approach.** Build the result incrementally: appending `i` means shifting the running value left by `bitLength(i)` and OR-ing in `i`, all under the modulus. `bitLength(i)` is `32 - numberOfLeadingZeros(i)`, and `i` itself doubles whenever it crosses a power of two.

```java
public class ConcatenatedBinary {
    public static int concatenatedBinary(int n) {
        long MOD = 1_000_000_007L, result = 0;
        int length = 0;                                 // bit length of current i
        for (int i = 1; i <= n; i++) {
            if ((i & (i - 1)) == 0) length++;           // power of two -> one more bit
            result = ((result << length) | i) % MOD;    // shift left by len, append i
        }
        return (int) result;
    }
}
```

**Time:** O(n) · **Space:** O(1)

**Insight.** Each number contributes a left shift by its bit length; the length only grows at powers of two, so track it with a single increment.

---

### Problem 101: Minimum One-Bit Operations to Make Zero — Gray-code inversion

**Statement.** You may (1) flip the rightmost bit, or (2) flip the bit just left of the lowest set bit. Return the minimum operations to turn `n` into 0. (LeetCode 1611.)

**Approach.** The reachable-state graph is exactly Gray code: the minimum number of operations equals the *index* of `n` in the reflected Gray-code order, i.e. the inverse of `x ^ (x >> 1)`. Fold the bits with running XOR from the top down to invert the Gray code.

```java
public class MinOpsToZero {
    public static int minimumOneBitOperations(int n) {
        int result = 0;
        while (n != 0) {
            result ^= n;                                // accumulate inverse-Gray prefix XOR
            n >>= 1;
        }
        return result;
    }
}
```

**Time:** O(log n) · **Space:** O(1)

**Insight.** The operation set traces Gray code, so the answer is `grayToBinary(n)` — the same prefix-XOR fold from Problem 31, reused as a distance.

---

### Problem 102: Maximum Score From XOR-Constrained Path — bitmask Dijkstra-lite

**Statement.** On a small DAG of `m ≤ 16` "stations", each edge XORs a value into an accumulator; find a path from 0 to a goal maximizing the final XOR while visiting each station at most once. (Bitmask state DP with an XOR payload.)

**Approach.** State is `(visitedMask, currentNode, accXor)` — but `accXor` ranges over values, so memoize on `(mask, node)` keeping the best XOR reachable, and only recurse when a larger XOR is found. With ≤16 nodes the mask space is `2¹⁶·16`.

```java
import java.util.*;

public class XorConstrainedPath {
    static int[][] cost; static int n, goal; static Map<Long, Integer> best = new HashMap<>();

    public static int maxXorPath(int[][] adjXor, int target) {
        cost = adjXor; n = adjXor.length; goal = target;
        return dfs(1, 0, 0);                             // start at node 0, only it visited
    }

    private static int dfs(int mask, int node, int acc) {
        if (node == goal) return acc;
        long key = ((long) mask << 5) | node;
        Integer prev = best.get(key);
        if (prev != null && prev >= acc) return Integer.MIN_VALUE; // already explored as good or better
        best.put(key, acc);
        int res = Integer.MIN_VALUE;
        for (int v = 0; v < n; v++) {
            if (v == node || (mask & (1 << v)) != 0 || cost[node][v] == Integer.MIN_VALUE) continue;
            res = Math.max(res, dfs(mask | (1 << v), v, acc ^ cost[node][v]));
        }
        return res;
    }
}
```

**Time:** O(2ⁿ·n²) worst case · **Space:** O(2ⁿ·n)

**Insight.** When the payload (XOR) can't be folded into the cost, memoize on `(mask, node)` and prune by "have I been here with an equal-or-better accumulator?".

---

### Problem 103: Fenwick Tree Indexing — why `i & (-i)` defines the structure

**Statement.** Implement a Binary Indexed Tree (Fenwick) for prefix sums and explain how `i & (-i)` (Problem 12) defines parent/child jumps.

**Approach.** Each index `i` is responsible for a range of length `i & (-i)` (its lowest set bit). To update, add that lowest bit to climb to the next responsible index; to query a prefix, subtract it to walk down the covered ranges. The lowest-set-bit isolation *is* the tree topology.

```java
public class FenwickTree {
    private final long[] tree;

    public FenwickTree(int size) { tree = new long[size + 1]; }

    public void update(int i, long delta) {             // 1-indexed
        for (; i < tree.length; i += i & (-i)) tree[i] += delta; // climb by lowest set bit
    }

    public long prefixSum(int i) {
        long sum = 0;
        for (; i > 0; i -= i & (-i)) sum += tree[i];     // descend by lowest set bit
        return sum;
    }

    public long rangeSum(int l, int r) { return prefixSum(r) - prefixSum(l - 1); }
}
```

**Time:** O(log n) per op · **Space:** O(n)

**Insight.** `i & (-i)` is not a trick bolted onto Fenwick — it literally is the tree: each node owns exactly that many leaves, so add it to ascend and subtract it to cover the prefix.

---

### Problem 104: Range Update / Point Query With a Bit-Sliced Fenwick — XOR over a range

**Statement.** Support "XOR `v` into every element of `[l, r]`" and "query a single element". Standard sum-Fenwick uses difference arrays; do the XOR analogue. (Bit-sliced range XOR.)

**Approach.** XOR has a clean difference form: XOR `v` at index `l` and again at `r+1` in a Fenwick that aggregates by XOR (not sum). A point query is the prefix-XOR up to that index — each `v` toggles exactly the range `[l, r]`.

```java
public class XorFenwick {
    private final int[] tree;

    public XorFenwick(int size) { tree = new int[size + 2]; }

    private void pointXor(int i, int v) {
        for (; i < tree.length; i += i & (-i)) tree[i] ^= v;
    }

    public void rangeXor(int l, int r, int v) {          // 1-indexed
        pointXor(l, v);
        pointXor(r + 1, v);                              // toggle off after the range
    }

    public int pointQuery(int i) {
        int x = 0;
        for (; i > 0; i -= i & (-i)) x ^= tree[i];        // prefix XOR = element value
        return x;
    }
}
```

**Time:** O(log n) per op · **Space:** O(n)

**Insight.** XOR is its own inverse, so the prefix-sum difference trick (`+v` at `l`, `−v` at `r+1`) becomes "XOR `v` at `l` and `r+1`" — no negation needed.

---

### Problem 105: Broadword "Has a Zero Byte" — SWAR byte search

**Statement.** Given a 64-bit word holding 8 bytes, determine whether any byte is zero, branchlessly and without a loop. (The `strlen`/`memchr` core trick from *Hacker's Delight*.)

**Approach.** The magic formula `(v - 0x0101010101010101) & ~v & 0x8080808080808080` is non-zero iff some byte is zero. Subtracting 1 from a zero byte borrows and sets its high bit; `& ~v` keeps it only where the byte was actually zero (not merely had its top bit set); the `0x80…80` mask isolates the per-byte high bits.

```java
public class HasZeroByte {
    public static boolean hasZeroByte(long v) {
        long lows  = 0x0101010101010101L;
        long highs = 0x8080808080808080L;
        return ((v - lows) & ~v & highs) != 0;
    }

    // Locate position (0..7) of the first zero byte, or -1.
    public static int firstZeroByte(long v) {
        for (int i = 0; i < 8; i++) {
            if (((v >>> (i * 8)) & 0xFF) == 0) return i;
        }
        return -1;
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** Broadword SIMD-within-a-register lets one 64-bit subtract test eight bytes at once — the borrow propagation is the per-byte zero detector.

---

### Problem 106: Broadword Byte Equality — find a target byte in a word

**Statement.** Given a 64-bit word and a target byte `c`, report whether any of the 8 bytes equals `c`, branchlessly. Generalizes Problem 105 from "zero" to "any value".

**Approach.** XOR the word with a broadcast of `c` (`c * 0x0101…01`), which makes matching bytes become zero; then apply the has-zero-byte test from Problem 105.

```java
public class HasByteEqual {
    public static boolean containsByte(long v, int c) {
        long broadcast = (c & 0xFFL) * 0x0101010101010101L; // c in every byte
        long x = v ^ broadcast;                             // matching bytes -> 0
        long lows  = 0x0101010101010101L, highs = 0x8080808080808080L;
        return ((x - lows) & ~x & highs) != 0;              // has-zero-byte on x
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** "Find byte `c`" reduces to "find a zero byte" after XOR-ing in a broadcast of `c` — broadword equality is broadword zero-detection in disguise.

---

### Problem 107: Compute Hamming Weight of a 64-bit Long via SWAR

**Statement.** Popcount a `long` in O(1) with no loop, the 64-bit version of Problem 45.

**Approach.** Same SWAR cascade with 64-bit masks: count in 2-bit fields, then 4-bit, then 8-bit, finishing with a multiply by `0x0101010101010101` to sum all eight byte-counts into the top byte.

```java
public class SwarPopcount64 {
    public static int popcount(long n) {
        n = n - ((n >>> 1) & 0x5555555555555555L);
        n = (n & 0x3333333333333333L) + ((n >>> 2) & 0x3333333333333333L);
        n = (n + (n >>> 4)) & 0x0f0f0f0f0f0f0f0fL;
        return (int) ((n * 0x0101010101010101L) >>> 56);    // sum bytes into the top byte
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** Widen every mask to 64 bits and shift the final reduction to `>>> 56` — the SWAR popcount scales cleanly from 32 to 64 bits.

---

### Problem 108: Reverse Bits of a 64-bit Long — butterfly with long masks

**Statement.** Reverse all 64 bits of a `long` in O(log 64), the 64-bit analogue of Problem 19's butterfly.

**Approach.** Swap halves (32 bits), then quarters (16), then bytes (8), nibbles (4), pairs (2), and finally adjacent bits — each step uses a striped 64-bit mask. Six swaps total.

```java
public class ReverseBits64 {
    public static long reverse(long n) {
        n = (n >>> 32) | (n << 32);
        n = ((n & 0xffff0000ffff0000L) >>> 16) | ((n & 0x0000ffff0000ffffL) << 16);
        n = ((n & 0xff00ff00ff00ff00L) >>> 8)  | ((n & 0x00ff00ff00ff00ffL) << 8);
        n = ((n & 0xf0f0f0f0f0f0f0f0L) >>> 4)  | ((n & 0x0f0f0f0f0f0f0f0fL) << 4);
        n = ((n & 0xccccccccccccccccL) >>> 2)  | ((n & 0x3333333333333333L) << 2);
        n = ((n & 0xaaaaaaaaaaaaaaaaL) >>> 1)  | ((n & 0x5555555555555555L) << 1);
        return n;
    }
}
```

**Time:** O(1) (6 fixed steps) · **Space:** O(1)

**Insight.** Bit reversal is a perfect-shuffle network: each level swaps fields of half the previous width, so 64 bits need exactly six masked swaps.

---

### Problem 109: Next Power of Two for a 64-bit Long — bit-smearing to 63

**Statement.** Round a `long` up to the next power of two (≥ the input), the 64-bit version of Problem 60, defending against overflow near `2⁶²`.

**Approach.** Decrement, then smear the highest set bit down across all lower positions with shifts of 1, 2, 4, …, 32, then increment. Doing it in `long` covers all 63 magnitude bits.

```java
public class NextPowerOfTwo64 {
    public static long nextPow2(long n) {
        if (n <= 1) return 1;
        n--;
        n |= n >>> 1;  n |= n >>> 2;  n |= n >>> 4;
        n |= n >>> 8;  n |= n >>> 16; n |= n >>> 32;     // smear across 64 bits
        return n + 1;
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** Smearing fills every bit below the top one to make `0b1000…0 − 1`; the extra `>>> 32` step is the only difference from the 32-bit case.

---

### Problem 110: De Bruijn Sequence for O(1) Trailing-Zero Lookup

**Statement.** Compute the index of the lowest set bit (trailing zeros) of a 32-bit integer in O(1) using a De Bruijn sequence and a lookup table — the classic technique behind `numberOfTrailingZeros` on hardware without a dedicated instruction.

**Approach.** Isolate the lowest set bit with `n & -n`, multiply by a De Bruijn constant that maps each single-bit value to a unique 5-bit prefix, shift right by 27, and index a precomputed position table.

```java
public class DeBruijnTrailingZeros {
    private static final int DEBRUIJN = 0x077CB531;
    private static final int[] TABLE = new int[32];
    static {
        for (int i = 0; i < 32; i++) {
            TABLE[(DEBRUIJN << i) >>> 27] = i;           // each rotation lands at a unique slot
        }
    }

    public static int trailingZeros(int n) {
        if (n == 0) return 32;
        int isolated = n & (-n);                         // lowest set bit
        return TABLE[(isolated * DEBRUIJN) >>> 27];
    }
}
```

**Time:** O(1) · **Space:** O(32)

**Insight.** A De Bruijn sequence packs all 32 distinct 5-bit windows into one constant, turning "which bit is set" into a single multiply, shift, and table lookup.

---

### Problem 111: Compress Bits by a Mask (parallel bit extract / PEXT) — software emulation

**Statement.** Implement `pext(x, mask)`: gather the bits of `x` selected by `mask` and pack them contiguously into the low end of the result. (Emulates the x86 BMI2 `PEXT` instruction.)

**Approach.** Iterate the set bits of `mask` from low to high using `mask & -mask`; for each, append the corresponding bit of `x` to the next output position. A faster parallel-prefix version exists but the bit-by-bit loop makes the semantics explicit.

```java
public class ParallelBitExtract {
    public static int pext(int x, int mask) {
        int result = 0, out = 0;
        while (mask != 0) {
            int low = mask & (-mask);                    // lowest selected position
            if ((x & low) != 0) result |= (1 << out);    // copy x's bit, packed low
            out++;
            mask &= (mask - 1);                          // drop that mask bit
        }
        return result;
    }
}
```

**Time:** O(set bits of mask) · **Space:** O(1)

**Insight.** PEXT is "iterate selected positions, append bits densely" — `mask & -mask` walks the chosen columns and a separate output counter packs them.

---

### Problem 112: Expand Bits by a Mask (parallel bit deposit / PDEP) — the inverse scatter

**Statement.** Implement `pdep(x, mask)`: take the low bits of `x` and scatter them into the positions selected by `mask`. The inverse of Problem 111. (Emulates x86 `PDEP`.)

**Approach.** Walk the set bits of `mask` low to high; deposit successive low bits of `x` into those positions, leaving non-mask positions zero.

```java
public class ParallelBitDeposit {
    public static int pdep(int x, int mask) {
        int result = 0, in = 0;
        while (mask != 0) {
            int low = mask & (-mask);                    // next target position
            if ((x & (1 << in)) != 0) result |= low;     // scatter x's in-th bit there
            in++;
            mask &= (mask - 1);
        }
        return result;
    }
}
```

**Time:** O(set bits of mask) · **Space:** O(1)

**Insight.** PDEP reverses PEXT: read `x`'s bits densely and write them sparsely onto the mask's set positions — gather and scatter are exact inverses.

---

### Problem 113: Find the k-th Set Bit's Position — select via popcount

**Statement.** Given an integer and `k`, return the 0-based position of its `k`-th set bit (counting from the least significant). Return −1 if there are fewer than `k`. (The "select" operation that complements popcount's "rank".)

**Approach.** Peel set bits with `n & -n` and `n & (n-1)`, decrementing `k`; the position of the last peeled bit when `k` hits zero is the answer. (A popcount-binary-search does it in O(log) but the peel is clearest.)

```java
public class KthSetBit {
    public static int kthSetBit(int n, int k) {
        while (n != 0) {
            int low = n & (-n);                          // lowest set bit value
            if (--k == 0) return Integer.numberOfTrailingZeros(low);
            n &= (n - 1);                                // drop it, advance
        }
        return -1;                                       // fewer than k set bits
    }
}
```

**Time:** O(k) · **Space:** O(1)

**Insight.** Popcount answers "how many set bits up to here" (rank); peeling with `n & -n` answers the dual "where is the k-th set bit" (select).

---

### Problem 114: Maximum Number After Swapping Two Adjacent Equal-Parity Bits — greedy on bit runs

**Statement.** Given a non-negative integer, you may swap any single `0` with an adjacent `1` that sits to its **right** at most once; return the maximum value obtainable. (A bit-level greedy with a single move budget.)

**Approach.** Scanning from the most significant bit, the best single improvement is to find the highest `0` that has a `1` somewhere below it and pull the highest such `1` up to that `0`'s place (swapping the bits). Greedily picking the highest fixable `0` maximizes the value.

```java
public class MaxAfterOneBitSwap {
    public static int maximize(int n) {
        for (int i = 30; i >= 1; i--) {                  // skip sign bit 31; inputs are non-negative
            if (((n >> i) & 1) == 0) {                   // a 0 we'd like to turn into 1
                for (int j = 0; j < i; j++) {            // lowest 1 below it loses the least value
                    if (((n >> j) & 1) == 1) {
                        return (n | (1 << i)) & ~(1 << j);  // set the high 0, clear that 1 (a true swap)
                    }
                }
            }
        }
        return n;                                        // already maximal (ones packed at the top)
    }
}
```

**Time:** O(32) · **Space:** O(1)

**Insight.** Value is dominated by high bits, so the single best move always promotes the highest fixable zero — a greedy that never needs to look at lower bits' arrangement.

---

### Problem 115: Bitset Union-Find Component Sizes via Word Popcount

**Statement.** Given an `n×n` adjacency stored as `n` bitmask rows (`n ≤ 64`), compute connected-component sizes by closing each row under reachability with bitwise OR. (Transitive closure by broadword OR — Warshall in words.)

**Approach.** Repeatedly OR each node's reachable-set with the reachable-sets of every node it can already reach (`reach[i] |= reach[j]` when bit `j` of `reach[i]` is set) until nothing changes. Component size is `Long.bitCount(reach[i])`.

```java
import java.util.*;

public class BitsetComponents {
    public static int[] componentSizes(long[] adj) {
        int n = adj.length;
        long[] reach = adj.clone();
        for (int i = 0; i < n; i++) reach[i] |= (1L << i);   // include self
        boolean changed = true;
        while (changed) {                                    // fixed-point closure
            changed = false;
            for (int i = 0; i < n; i++) {
                long before = reach[i];
                long r = reach[i];
                while (r != 0) {
                    int j = Long.numberOfTrailingZeros(r);
                    reach[i] |= reach[j];                     // absorb j's reachable set
                    r &= (r - 1);
                }
                if (reach[i] != before) changed = true;
            }
        }
        int[] sizes = new int[n];
        for (int i = 0; i < n; i++) sizes[i] = Long.bitCount(reach[i]);
        return sizes;
    }
}
```

**Time:** O(n²) word ops to a fixed point · **Space:** O(n)

**Insight.** Reachability is a transitive OR-closure; storing each frontier as a 64-bit word turns "merge neighbor sets" into a single OR and "component size" into a popcount.

---

### Problem 116: XOR Linear Basis With Vector Tracking — recover the actual subset

**Statement.** Extend Problem 82's GF(2) basis so that, for any value it can represent, you can recover **which** original elements XOR to it. (Needed when the interviewer asks "and which numbers?")

**Approach.** Alongside each basis vector store the bitmask of original indices that produced it. When inserting `x`, also reduce its provenance mask; when querying, XOR provenance masks together for each basis vector you use.

```java
public class XorBasisWithTracking {
    private final long[] basis = new long[64];
    private final long[] who   = new long[64];           // index-set producing basis[b]

    public void insert(long x, int index) {
        long mask = 1L << index;
        for (int b = 63; b >= 0; b--) {
            if (((x >> b) & 1) == 0) continue;
            if (basis[b] == 0) { basis[b] = x; who[b] = mask; return; }
            x ^= basis[b]; mask ^= who[b];                // reduce value AND provenance
        }
    }

    // Returns the index-set whose XOR equals target, or -1 if unrepresentable.
    public long subsetFor(long target) {
        long x = target, used = 0;
        for (int b = 63; b >= 0; b--) {
            if (((x >> b) & 1) == 0) continue;
            if (basis[b] == 0) return -1;                 // not in the span
            x ^= basis[b]; used ^= who[b];
        }
        return used;
    }
}
```

**Time:** O(n·64) build, O(64) per query · **Space:** O(64)

**Insight.** Carry a provenance mask through every reduction step in lockstep with the value — Gaussian elimination over GF(2) tracks "which rows" for free if you XOR the row-ids too.

---

### Problem 117: Gray-Code Rank and Unrank — combinatorial position in single-bit-change order

**Statement.** Given an `n`-bit Gray code value, return its position (rank) in the standard reflected-Gray sequence, and conversely produce the Gray value at a given rank. (Bidirectional Problem 30/31, framed as ranking.)

**Approach.** Rank is just Gray→binary (prefix XOR, Problem 31): the binary value *is* the index in the Gray sequence. Unrank is binary→Gray (`i ^ (i >> 1)`, Problem 30). The two operations are inverses.

```java
public class GrayRankUnrank {
    public static int rank(int grayValue) {              // position in the Gray sequence
        int b = grayValue;
        while (grayValue > 0) { grayValue >>= 1; b ^= grayValue; }
        return b;                                         // = grayToBinary
    }

    public static int unrank(int position) {             // Gray value at this position
        return position ^ (position >> 1);
    }
}
```

**Time:** O(log n) rank, O(1) unrank · **Space:** O(1)

**Insight.** "Where does this Gray value sit?" and "what value sits at index i?" are exactly the Gray↔binary conversions — ranking a single-bit-change code is a relabeling, not a search.

---

### Problem 118: Minimum Bit Flips to Convert a Number — popcount of XOR, with a twist on counting per-flip

**Statement.** Return the minimum number of bit flips to convert `start` into `goal`. (LeetCode 2220.) Then extend: count flips restricted to a given mask of editable positions, returning −1 if impossible.

**Approach.** The unconstrained answer is `bitCount(start ^ goal)` (Problem 21). With an editable `mask`, any required flip outside the mask makes it impossible; otherwise the count is the popcount of the difference (which by feasibility lies entirely within the mask).

```java
public class MinBitFlipsConstrained {
    public static int minBitFlips(int start, int goal) {
        return Integer.bitCount(start ^ goal);
    }

    // Only positions in 'editable' may be flipped.
    public static int minBitFlipsMasked(int start, int goal, int editable) {
        int diff = start ^ goal;
        if ((diff & ~editable) != 0) return -1;          // a required flip is locked
        return Integer.bitCount(diff);
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** The XOR pinpoints exactly which positions must change; a constraint is satisfiable iff no required flip falls outside the editable mask.

---

### Problem 119: Count Integers in [L, R] With Even Bit-Parity — parity prefix counting

**Statement.** Count integers in `[L, R]` whose number of set bits is even. (Parity of popcount, a building block of XOR-of-range problems.)

**Approach.** Define `f(n)` = count of integers in `[0, n]` with even popcount. For `n ≥ 0`, exactly half of `[0, n]` (rounding by parity of `n` itself) have even popcount: `f(n) = (n+1)/2 + (((n+1) & 1) & evenParity(n))`. Answer is `f(R) - f(L-1)`. The closed form follows from popcount parity being perfectly balanced over any `[0, 2^k − 1]`.

```java
public class EvenParityCount {
    private static long f(long n) {                      // count in [0,n] with even popcount
        if (n < 0) return 0;
        long half = (n + 1) / 2;
        if (((n + 1) & 1) == 1 && Integer.bitCount((int) n) % 2 == 0) half++;
        return half;
    }

    public static long countEvenParity(long L, long R) {
        return f(R) - f(L - 1);
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** Popcount parity splits any prefix `[0, n]` almost exactly in half; the only correction is the lone endpoint when `n+1` is odd.

---

### Problem 120: Maximize AND Sum by Bitmask Slot Assignment — multi-slot bitmask DP

**Statement.** You have `numSlots` slots and an array `nums`; each slot holds at most 2 numbers, and the score of placing `x` in slot `s` is `x & s`. Maximize total score by assigning every number to some slot. (LeetCode 2172.) A bitmask DP where each slot has capacity 2.

**Approach.** Encode each slot's remaining capacity in **2 bits** (base-3 packed into base-4 for simplicity), so the state is a `4^numSlots` mask. `dp[mask]` is the best score after placing the first `t` numbers, where `t` = total used capacity decoded from `mask`. Try placing the next number into any non-full slot.

```java
import java.util.*;

public class MaximizeAndSum {
    public static int maximumANDSum(int[] nums, int numSlots) {
        int states = 1;
        for (int i = 0; i < numSlots; i++) states *= 3;  // each slot: 0,1,2 used
        int[] dp = new int[states];
        return dfs(nums, 0, encodeFull(numSlots), numSlots, new int[states], pow3(numSlots));
    }

    private static int pow3(int n) { int p = 1; while (n-- > 0) p *= 3; return p; }
    private static int encodeFull(int numSlots) { return 0; }

    private static int dfs(int[] nums, int idx, int mask, int numSlots, int[] memo, int states) {
        if (idx == nums.length) return 0;
        if (memo[mask] != 0) return memo[mask];
        int best = 0, p = 1;
        for (int s = 0; s < numSlots; s++) {
            int used = (mask / p) % 3;
            if (used < 2) {                              // slot s has room
                int score = (nums[idx] & (s + 1))        // slots are 1-indexed
                          + dfs(nums, idx + 1, mask + p, numSlots, memo, states);
                best = Math.max(best, score);
            }
            p *= 3;
        }
        return memo[mask] = best;
    }
}
```

**Time:** O(3^numSlots · numSlots) · **Space:** O(3^numSlots)

**Insight.** When each slot holds up to 2 items, pack per-slot occupancy into a base-3 digit; the packed integer is the DP state and its digit-sum is how many numbers you've placed.

---

### Problem 121: Smallest Subarray Covering All Bits of the Total OR — bit-frequency sliding window

**Statement.** Find the shortest subarray whose OR equals the OR of the entire array (i.e. it "covers" every bit that appears anywhere). (Combines Problem 96/97's bit-count window with a target equal to the global OR.)

**Approach.** Compute the global OR target. Slide a window keeping a per-bit count; the window's OR equals the target once every target bit has count `> 0`. Shrink from the left while the cover holds, tracking the minimum length.

```java
public class SmallestCoveringSubarray {
    public static int shortestCovering(int[] nums) {
        int target = 0;
        for (int v : nums) target |= v;
        if (target == 0) return 1;                       // all zeros: any single element

        int[] cnt = new int[32];
        int left = 0, best = Integer.MAX_VALUE;
        for (int right = 0; right < nums.length; right++) {
            for (int b = 0; b < 32; b++) if (((nums[right] >> b) & 1) == 1) cnt[b]++;
            while (left <= right && covers(cnt, target)) {
                best = Math.min(best, right - left + 1);
                for (int b = 0; b < 32; b++) if (((nums[left] >> b) & 1) == 1) cnt[b]--;
                left++;
            }
        }
        return best;
    }

    private static boolean covers(int[] cnt, int target) {
        for (int b = 0; b < 32; b++)
            if (((target >> b) & 1) == 1 && cnt[b] == 0) return false; // a target bit missing
        return true;
    }
}
```

**Time:** O(32·n) · **Space:** O(32)

**Insight.** "OR equals the global OR" is "every target bit is present" — track presence with per-bit counts so the window stays shrinkable despite OR being non-invertible.

---

### Problem 122: Bitmask Graph Coloring — minimum colors via subset enumeration DP

**Statement.** Find the chromatic number of a small graph (`n ≤ 16`) — the minimum colors so no edge joins same-colored vertices — using subset DP over independent sets. (Bitmask DP combining submask iteration with set cover.)

**Approach.** Precompute which subsets are independent sets (no internal edge). `dp[mask]` = minimum colors to properly color the vertex set `mask`. Transition by choosing an independent subset of `mask` as one color class and recursing on the rest — submask iteration (Problem 29) drives it.

```java
import java.util.*;

public class ChromaticNumber {
    public static int chromaticNumber(boolean[][] adj) {
        int n = adj.length, FULL = (1 << n) - 1;
        boolean[] independent = new boolean[1 << n];
        for (int mask = 0; mask <= FULL; mask++) independent[mask] = isIndependent(mask, adj, n);

        int[] dp = new int[1 << n];
        Arrays.fill(dp, Integer.MAX_VALUE);
        dp[0] = 0;
        for (int mask = 1; mask <= FULL; mask++) {
            for (int sub = mask; sub > 0; sub = (sub - 1) & mask) { // submasks of mask
                if (independent[sub] && dp[mask ^ sub] != Integer.MAX_VALUE) {
                    dp[mask] = Math.min(dp[mask], dp[mask ^ sub] + 1); // one more color class
                }
            }
        }
        return dp[FULL];
    }

    private static boolean isIndependent(int mask, boolean[][] adj, int n) {
        for (int i = 0; i < n; i++) {
            if ((mask & (1 << i)) == 0) continue;
            for (int j = i + 1; j < n; j++) {
                if ((mask & (1 << j)) != 0 && adj[i][j]) return false; // internal edge
            }
        }
        return true;
    }
}
```

**Time:** O(3ⁿ + 2ⁿ·n²) · **Space:** O(2ⁿ)

**Insight.** Graph coloring is "partition vertices into the fewest independent sets" — submask iteration over `dp[mask]` peels one color class (an independent subset) at a time.

---

### Problem 123: Detect Multiplication Overflow Branchlessly — high-bit reasoning in 64 bits

**Statement.** Determine whether `a * b` (both `int`) overflows a 32-bit `int`, without performing the wrapping multiply in `int`. (The multiply analogue of Problem 80.)

**Approach.** Widen to `long`, multiply there (cannot overflow for two `int`s), and check whether the 64-bit product fits back into `int` range. The sign-extension of the result's bit 31 must equal its top 33 bits — equivalently `(int) prod == prod`.

```java
public class MulOverflow {
    public static boolean overflows(int a, int b) {
        long prod = (long) a * (long) b;                 // exact 64-bit product
        return (int) prod != prod;                       // doesn't round-trip -> overflowed
    }

    // The actual saturating multiply.
    public static int saturatingMul(int a, int b) {
        long prod = (long) a * (long) b;
        if (prod > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (prod < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) prod;
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** A value fits in `int` iff casting it down and back is lossless — `(int) prod == prod` is the cleanest overflow test once you have the wide product.

---

### Problem 124: Rotate a Bit-Packed Grid 90° — transpose via bit interleave

**Statement.** Given an `8×8` bit grid packed as a single 64-bit `long` (row-major), rotate it 90° clockwise, in O(log n) broadword steps rather than O(n²) per-cell moves. (A *Hacker's Delight* matrix-transpose-by-bit trick.)

**Approach.** A 90° rotation is a transpose followed by a horizontal flip. Transpose the bit matrix with the recursive Delta-swap algorithm (swap quadrants, then sub-quadrants), then reverse the byte order to perform the flip.

```java
public class RotateBitGrid {
    // Transpose an 8x8 bit matrix packed row-major in a long.
    public static long transpose8x8(long x) {
        long t;
        t = (x ^ (x >>> 7)) & 0x00AA00AA00AA00AAL; x ^= t ^ (t << 7);
        t = (x ^ (x >>> 14)) & 0x0000CCCC0000CCCCL; x ^= t ^ (t << 14);
        t = (x ^ (x >>> 28)) & 0x00000000F0F0F0F0L; x ^= t ^ (t << 28);
        return x;
    }

    public static long rotate90(long grid) {
        long transposed = transpose8x8(grid);
        return reverseBytes(transposed);                 // horizontal flip = reverse rows' bytes
    }

    private static long reverseBytes(long x) {
        return Long.reverseBytes(x);
    }
}
```

**Time:** O(1) (3 swap steps + byte reverse) · **Space:** O(1)

**Insight.** Delta-swap transposes a power-of-two bit matrix by exchanging ever-smaller diagonal blocks — rotation is then just transpose composed with a byte-order flip.

---

### Problem 125: Longest Subarray With Bitwise AND Equal to the Maximum Element

**Statement.** The AND of a subarray is at most its minimum element, and reaches the array maximum only on runs of that maximum. Find the length of the longest subarray whose AND equals the array's maximum value. (LeetCode 2419.)

**Approach.** The AND equals the global max only when every element in the window *is* the max (since AND can't exceed the smallest element). So the answer is the longest run of consecutive maximum values — a single linear scan, no bit DP needed once you see the constraint.

```java
public class LongestAndEqualsMax {
    public static int longestSubarray(int[] nums) {
        int max = 0;
        for (int v : nums) max = Math.max(max, v);
        int best = 0, run = 0;
        for (int v : nums) {
            if (v == max) best = Math.max(best, ++run);  // extend the run of maxes
            else run = 0;
        }
        return best;
    }
}
```

**Time:** O(n) · **Space:** O(1)

**Insight.** AND is bounded above by the minimum element, so equalling the maximum forces every windowed value to *be* the maximum — the problem degenerates to "longest run of the max".

---

### Problem 126: Count Triplets With XOR Zero Across a Split Point — prefix-XOR equality

**Statement.** Count triples `(i, j, k)` with `i < j ≤ k` such that `arr[i] ^ … ^ arr[j-1] == arr[j] ^ … ^ arr[k]`. (LeetCode 1442.) A prefix-XOR identity hides a counting shortcut.

**Approach.** The condition is equivalent to `pre[i] == pre[k+1]` (the two segments equal iff their combined XOR is zero). For each pair `(i, k)` with equal prefixes, any `j` in `(i, k]` works — that's `k - i` triples. Accumulate per matching prefix value in one pass.

```java
import java.util.*;

public class TripletsXorZero {
    public static int countTriplets(int[] arr) {
        int n = arr.length;
        int[] pre = new int[n + 1];
        for (int i = 0; i < n; i++) pre[i + 1] = pre[i] ^ arr[i];

        int count = 0;
        // For equal prefixes pre[i] == pre[k+1], number of valid j is (k - i).
        Map<Integer, List<Integer>> positions = new HashMap<>();
        for (int idx = 0; idx <= n; idx++) {
            positions.computeIfAbsent(pre[idx], x -> new ArrayList<>()).add(idx);
        }
        for (List<Integer> list : positions.values()) {
            for (int a = 0; a < list.size(); a++)
                for (int b = a + 1; b < list.size(); b++)
                    count += list.get(b) - list.get(a) - 1; // j choices between the two prefixes
        }
        return count;
    }
}
```

**Time:** O(n²) worst case (O(n) with running aggregates) · **Space:** O(n)

**Insight.** "Two adjacent segments XOR-equal" collapses to "two prefix XORs are equal"; the count of `j` between them is purely positional, so group by prefix value.

---

### Problem 127: Maximum Strong Pair XOR — sorted window meets a trie

**Statement.** A "strong pair" `(x, y)` satisfies `|x − y| ≤ min(x, y)`. Find the maximum `x ^ y` over all strong pairs. (LeetCode 2935.) Combines a sliding constraint with the XOR-maximizing trie.

**Approach.** Sort the array. The strong-pair condition `|x−y| ≤ min(x,y)` becomes `max ≤ 2·min`, so for a sorted window with right end `y`, valid `x` satisfy `x ≥ y/2` — a monotone window. Maintain a trie of the current window (insert on the right, remove on the left) and query the best XOR each step.

```java
import java.util.*;

public class MaxStrongPairXor {
    static class Node { Node[] c = new Node[2]; int count; }

    public static int maximumStrongPairXor(int[] nums) {
        Arrays.sort(nums);
        Node root = new Node();
        int left = 0, best = 0;
        for (int right = 0; right < nums.length; right++) {
            insert(root, nums[right], +1);
            while (nums[right] > 2 * nums[left]) insert(root, nums[left++], -1); // shrink invalid
            best = Math.max(best, query(root, nums[right]));
        }
        return best;
    }

    private static void insert(Node root, int v, int delta) {
        Node node = root;
        for (int b = 20; b >= 0; b--) {
            int bit = (v >> b) & 1;
            if (node.c[bit] == null) node.c[bit] = new Node();
            node = node.c[bit];
            node.count += delta;
        }
    }

    private static int query(Node root, int v) {
        Node node = root; int res = 0;
        for (int b = 20; b >= 0; b--) {
            int want = ((v >> b) & 1) ^ 1;
            if (node.c[want] != null && node.c[want].count > 0) { res |= (1 << b); node = node.c[want]; }
            else node = node.c[want ^ 1];
        }
        return res;
    }
}
```

**Time:** O(n·B + n log n) · **Space:** O(n·B)

**Insight.** Sorting turns the absolute-difference constraint into a monotone window (`max ≤ 2·min`), so a counted trie can slide alongside it and still answer max-XOR greedily.

---

### Problem 128: Bitwise OR Reachability — minimum operations to reach a target OR (DP over bit goals)

**Statement.** Given `nums`, in one operation you may pick any element and turn on one of its zero bits. Find the minimum operations so that the OR of the whole array equals a target `t` (where `t ⊇` initial OR is guaranteed). (A counting variation on Problem 40's OR constraint.)

**Approach.** A bit of `t` is already satisfied if any element has it; otherwise exactly one operation turns it on somewhere. So the answer is the number of target bits not present in the current OR — `popcount(t & ~currentOr)`.

```java
public class MinOpsToTargetOr {
    public static int minOperations(int[] nums, int t) {
        int or = 0;
        for (int v : nums) or |= v;                      // bits already covered
        int missing = t & ~or;                           // target bits we still need
        return Integer.bitCount(missing);                // one op per missing bit
    }
}
```

**Time:** O(n) · **Space:** O(1)

**Insight.** OR only needs each target bit set *somewhere*, so missing bits are independent — the cost is simply how many target bits the current OR lacks.

---

### Problem 129: Two's-Complement Range Check for Arbitrary Bit Widths — fits-in-k-bits test

**Statement.** Given a value and a bit width `k` (1 ≤ k ≤ 64), decide whether it fits in a signed `k`-bit two's-complement field, i.e. in `[−2^(k−1), 2^(k−1) − 1]`. (The field-validation behind Problem 76's sign-extension.)

**Approach.** Sign-extend the low `k` bits and check whether the round-trip preserves the value: shift left by `64 − k`, then arithmetic-shift back, and compare. If it matches the original, the value fits; otherwise its high bits carried information that a `k`-bit field would lose.

```java
public class FitsInKBits {
    public static boolean fitsSigned(long value, int k) {
        if (k >= 64) return true;
        int shift = 64 - k;
        long roundTrip = (value << shift) >> shift;      // sign-extend low k bits
        return roundTrip == value;
    }

    public static boolean fitsUnsigned(long value, int k) {
        if (k >= 64) return value >= 0;
        return value >= 0 && (value >>> k) == 0;          // no bits above position k-1
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** "Does it fit in a k-bit signed field?" is exactly "does sign-extending its low k bits give it back?" — the shift-left/arithmetic-shift-right round trip is a one-line range check.

---

### Problem 130: Maximum XOR After Operations With a Mask Budget — greedy bit allocation

**Statement.** Given `nums` and an integer `k`, you may, across at most `k` total bit-flips distributed however you like over the elements, maximize the XOR of the whole array. Determine the maximum achievable XOR. (A budgeted generalization of Problem 49's contribution counting.)

**Approach.** The array XOR's bit `b` is the parity of how many elements have bit `b` set. Flipping one element's bit `b` toggles that parity at cost 1. Greedily, from the high bit down, turn on any result bit currently 0 if you can afford one flip (it costs exactly 1 to change a parity). Spend the budget on the highest bits first.

```java
public class MaxXorWithBudget {
    public static int maxXor(int[] nums, int k) {
        int xor = 0;
        for (int v : nums) xor ^= v;                     // current array XOR
        int result = xor;
        for (int b = 31; b >= 0 && k > 0; b--) {
            if (((result >> b) & 1) == 0) {              // this high bit is off
                result |= (1 << b);                      // flip a parity to turn it on
                k--;                                     // costs one operation
            }
        }
        return result;
    }
}
```

**Time:** O(n + 32) · **Space:** O(1)

**Insight.** Each result bit is an independent parity that one flip can toggle; with a flip budget, greedily buy the highest off-bits first — value is dominated by the top.

---

### Problem 131: Count Numbers Whose Bits Are a Superset of a Pattern — masked equality

**Statement.** Given an array and a pattern `p`, count elements `x` with `(x & p) == p` (every bit of `p` is present in `x`). Then extend to "exactly these bits and no others among `p`'s positions". (Masked membership, the filter behind subset queries.)

**Approach.** `(x & p) == p` means `p` is a submask of `x`. For the strict variant, also require `x` has no bits outside `p` in the relevant region by checking `(x & region) == p`. Both are single masked comparisons per element.

```java
public class SupersetOfPattern {
    public static int countSuperset(int[] nums, int p) {
        int count = 0;
        for (int x : nums) if ((x & p) == p) count++;     // p is a submask of x
        return count;
    }

    // Within the positions of 'region', bits must match 'p' exactly.
    public static int countExactWithin(int[] nums, int region, int p) {
        int count = 0;
        for (int x : nums) if ((x & region) == (p & region)) count++;
        return count;
    }
}
```

**Time:** O(n) · **Space:** O(1)

**Insight.** Submask membership is one AND-and-compare: `(x & p) == p` says "x contains all of p", and masking to a region turns it into exact-match filtering.

---

### Problem 132: Minimum Array XOR to Reach a Target via At-Most-One Element Change

**Statement.** Given `nums` and a target `t`, you may change **at most one** element to any value. Return the minimum number of *bits* you must alter (Hamming cost on the changed element) so the total array XOR equals `t`. (A single-edit XOR repair problem.)

**Approach.** Let `cur` be the current XOR. To reach `t` you need the changed element `x` to become `x ^ (cur ^ t)` — i.e. you toggle exactly the bits of `delta = cur ^ t` in whichever element you pick. The minimum bit cost is `popcount(delta)`, and choosing any single element suffices (0 if already on target).

```java
public class SingleEditXorRepair {
    public static int minBitsToFix(int[] nums, int t) {
        int cur = 0;
        for (int v : nums) cur ^= v;
        int delta = cur ^ t;                             // bits the array XOR is off by
        return Integer.bitCount(delta);                  // flip them all in one chosen element
    }
}
```

**Time:** O(n) · **Space:** O(1)

**Insight.** Any single element can absorb the entire correction `cur ^ t` because XOR distributes the fix; the cost is just the popcount of the discrepancy.

---

### Problem 133: Subset XOR Spanning a Target Bit — basis membership query

**Statement.** Given a set of numbers and many queries, answer for each query value `q`: can some subset XOR to exactly `q`? (LeetCode-style "is q in the XOR span?" — the membership companion to Problem 82's max query.)

**Approach.** Build the GF(2) linear basis once (Problem 82). A query `q` is representable iff reducing it by the basis (high bit to low) drives it to zero — every leading bit must find a pivot to cancel against.

```java
public class XorSpanMembership {
    private final long[] basis = new long[64];

    public void insert(long x) {
        for (int b = 63; b >= 0; b--) {
            if (((x >> b) & 1) == 0) continue;
            if (basis[b] == 0) { basis[b] = x; return; }
            x ^= basis[b];
        }
    }

    public boolean canForm(long q) {
        for (int b = 63; b >= 0; b--) {
            if (((q >> b) & 1) == 0) continue;
            if (basis[b] == 0) return false;             // leading bit has no pivot -> not in span
            q ^= basis[b];
        }
        return q == 0;                                   // fully reduced -> representable
    }
}
```

**Time:** O(n·64) build, O(64) per query · **Space:** O(64)

**Insight.** Membership in a GF(2) span is decided by Gaussian reduction: `q` is reachable iff every set bit finds a pivot and the residue collapses to zero.

---

### Problem 134: XOR of a Range [0, n] in Closed Form — period-4 pattern

**Statement.** Compute `0 ^ 1 ^ 2 ^ … ^ n` in O(1) without looping. (The closed form behind many "XOR of range" follow-ups, e.g. Problem 16 generalized to arbitrary upper bounds.)

**Approach.** The prefix XOR has period 4 in `n`: it equals `n` when `n % 4 == 0`, `1` when `n % 4 == 1`, `n + 1` when `n % 4 == 2`, and `0` when `n % 4 == 3`. A range `[a, b]` XOR is then `prefix(b) ^ prefix(a − 1)`.

```java
public class XorUpToN {
    public static int xorUpTo(int n) {
        switch (n & 3) {                                 // n mod 4
            case 0:  return n;
            case 1:  return 1;
            case 2:  return n + 1;
            default: return 0;                           // case 3
        }
    }

    public static int xorRange(int a, int b) {
        return xorUpTo(b) ^ xorUpTo(a - 1);              // prefix-XOR difference
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** Consecutive integers pair up so their XOR cycles with period 4; memorizing the four cases turns a loop into a switch, and ranges fall out by prefix difference.

---

### Problem 135: Maximum AND of Two Numbers in an Array — high-bit narrowing with a count check

**Statement.** Find the maximum `nums[i] & nums[j]` over all pairs `i ≠ j`. (The AND counterpart of Problem 36's max XOR, but the structure differs because AND favors shared high bits.)

**Approach.** Build the answer greedily from the top bit. Tentatively require the current candidate bits; count how many numbers contain all of them. If at least **two** do, that pair can realize the candidate, so commit the bit. This narrows to the maximal AND-able prefix.

```java
public class MaxAndPair {
    public static int maximumAnd(int[] nums) {
        int result = 0;
        for (int b = 31; b >= 0; b--) {
            int candidate = result | (1 << b);
            int count = 0;
            for (int v : nums) if ((v & candidate) == candidate) count++; // contains all bits
            if (count >= 2) result = candidate;          // a pair can achieve it
        }
        return result;
    }
}
```

**Time:** O(32·n) · **Space:** O(1)

**Insight.** Max-AND needs *two* numbers sharing a high prefix, so the greedy commits a bit only when at least two elements carry the whole candidate — the "≥2" check is what distinguishes it from max-XOR's "≥1 pair".

---

### Problem 136: Decode the Original Array From Adjacent XORs and a First Element

**Statement.** Given `encoded[i] = orig[i] ^ orig[i+1]` and the known `first = orig[0]`, reconstruct `orig`. (LeetCode 1720 — the even-length sibling of Problem 93 where the seed is given outright.)

**Approach.** Since XOR is self-inverse, `orig[i+1] = orig[i] ^ encoded[i]`. Seed with `first` and roll forward — a one-pass prefix XOR.

```java
public class DecodeWithFirst {
    public static int[] decode(int[] encoded, int first) {
        int[] orig = new int[encoded.length + 1];
        orig[0] = first;
        for (int i = 0; i < encoded.length; i++) {
            orig[i + 1] = orig[i] ^ encoded[i];          // unroll the adjacent XOR
        }
        return orig;
    }
}
```

**Time:** O(n) · **Space:** O(1) extra

**Insight.** With the seed handed to you, decoding adjacent XORs is a trivial forward prefix-XOR — contrast Problem 93, where recovering the seed was the entire challenge.

---

## ✅ Key Takeaways — Set 2

- **SOS DP is an n-dimensional prefix sum** over the Boolean lattice: sweep each bit-axis once for O(n·2ⁿ) aggregation over submasks (Problem 83) or supersets (Problem 84); flipping the absorption direction is the only difference.
- **The trie is the universal max-XOR engine.** Offline value bounds (Problem 87), contiguous subarrays via prefix XOR (Problem 91), DFS ancestor sets (Problem 99), and sliding strong-pair windows (Problem 127) are all the same greedy high-bit walk over a (possibly counted, possibly persistent) binary trie.
- **Prefix XOR turns range/segment identities into equality lookups:** subarray XOR = `pre[r+1] ^ pre[l]` powers counting (Problem 92), triple-split equality (Problem 126), and decode problems (Problems 93, 136). The period-4 closed form (Problem 134) removes the loop entirely.
- **The GF(2) linear basis answers a whole question family:** max subset XOR (Problem 82), membership (Problem 133), and which-subset provenance (Problem 116) — all by Gaussian reduction high-bit to low.
- **Broadword / SWAR scales bit tricks to whole words:** zero-byte and byte-equality search (Problems 105–106), 64-bit popcount and reversal (Problems 107–108), De Bruijn trailing-zero lookup (Problem 110), and PEXT/PDEP gather-scatter (Problems 111–112).
- **OR is non-invertible, AND is bounded by the minimum.** Replace a single OR with per-bit counts to keep sliding windows shrinkable (Problems 97, 121); exploit `AND ≤ min` to collapse "AND equals max" into "longest run of the max" (Problem 125) and to drive ≥2-count greedy for max-AND (Problems 135, 86).
- **Bitmask DP packs richer state than membership:** base-3 slot occupancy (Problem 120), independent-set color classes via submask iteration (Problem 122), and XOR-payload path memoization (Problem 102) all extend the plain `dp[mask]` model.

## ⚠️ Common Pitfalls — Set 2

- **SOS absorption direction is easy to flip wrong:** for submask sums absorb from `mask ^ (1<<i)` *into* masks that **have** bit `i`; for superset sums absorb from `mask | (1<<i)` into masks that **lack** it. Reversing them silently computes the wrong aggregate.
- **Trie bit width must cover the value range** — using 18 bits for ids up to `2¹⁸` (Problem 99) or 20 bits for strong pairs (Problem 127) is deliberate; too few bits truncates high bits and corrupts the max-XOR walk. Use `>>>` for the top bit so the sign bit never sneaks in.
- **Pairwise-sum bit counting (Problem 90) must reduce mod `2^(b+1)`** before counting carry windows; forgetting the reduction lets high bits leak into the window comparison and breaks the parity.
- **Meet-in-the-middle (Problem 98) needs disjoint halves and the *complement* join key** `t ^ r` — joining on `r` itself counts the wrong pairs.
- **`(int) prod == prod` overflow tests require the wide multiply first** (Problem 123); doing `(long)(a * b)` multiplies in `int` and wraps before widening — cast each operand to `long` *before* the `*`.
- **Fenwick XOR range updates rely on XOR being self-inverse** (Problem 104): the `+v` at `l` / `−v` at `r+1` difference trick becomes `^v` at both ends — there is no separate "undo" value, so do not negate.
- **The fits-in-k-bits round trip uses arithmetic `>>` for signed and `>>>` for unsigned** (Problem 129); mixing them mis-classifies negative values at the boundary `−2^(k−1)`.
- **Period-4 XOR (Problem 134) is for `[0, n]`** — for `[a, b]` you must XOR `xorUpTo(b) ^ xorUpTo(a-1)`, and `a = 0` needs `xorUpTo(-1) == 0` handled (the `a-1` guard).

## 📚 Further Reading — Set 2

- *Hacker's Delight* (Warren) chapters 6–7 — broadword search, bit-matrix transpose (Problem 124), PEXT/PDEP emulation, and the De Bruijn position trick.
- Codeforces / cp-algorithms "Sum over Subsets" and "Linear Basis (XOR)" articles — the SOS DP and GF(2) basis material behind Problems 83–84 and 82/116/133.
- Knuth, *TAOCP* Vol. 4A, §7.1.3 "Bitwise tricks and techniques" — broadword algorithms, rank/select (Problem 113), and Gray-code ranking (Problem 117).
- Intel BMI2 instruction reference — the hardware `PEXT`/`PDEP`/`TZCNT`/`LZCNT` semantics emulated in Problems 110–112.
- LeetCode "Bit Manipulation" + "Trie" tags — problems 421, 1707, 1734, 1738, 1835, 1938, 2220, 2419, 2429, 2527, 2935 referenced throughout Set 2.

---
