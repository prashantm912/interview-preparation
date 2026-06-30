# Math & Number Theory for Coding

[← Back to master index](../README.md)

Math and number-theory problems are a staple of coding interviews and competitive programming. They reward a small toolbox of reusable building blocks — Euclid's GCD, the sieve of Eratosthenes, modular arithmetic with a prime modulus (`10^9 + 7` everywhere), fast/binary exponentiation, Fermat's little theorem for modular inverse, and the matrix-exponentiation trick that collapses a linear recurrence to `O(log n)`. Once you internalize these, problems that look intimidating (compute `nCr mod p` for huge `n`, the `10^18`-th Fibonacci number, or "count integers ≤ N with property P") become mechanical.

The two cross-cutting hazards are **integer overflow** (a single `int` multiply silently wraps at `2^31`; promote to `long`, or take the modulus *inside* the loop) and **negative modulo** (Java's `%` follows the sign of the dividend, so `-1 % m` is `-1`, not `m-1`; normalize with `((x % m) + m) % m`). This file collects 50 problems ramping from "compute a GCD" to digit DP, the Chinese Remainder Theorem, and matrix exponentiation.

---

## Primer — the identities you must hold

- **GCD (Euclid):** `gcd(a, b) = gcd(b, a mod b)`, base case `gcd(a, 0) = a`. Runs in `O(log min(a,b))`.
- **LCM:** `lcm(a, b) = a / gcd(a, b) * b` — divide *before* multiplying to avoid overflow.
- **Modular arithmetic:** `(a ± b) mod m`, `(a · b) mod m` distribute over the operation; division does **not** — you need a modular inverse.
- **Fermat's little theorem:** if `p` is prime and `gcd(a, p) = 1`, then `a^(p-1) ≡ 1 (mod p)`, so `a^(-1) ≡ a^(p-2) (mod p)`.
- **Binary exponentiation:** compute `a^n` in `O(log n)` multiplies by squaring and consuming the bits of `n`.
- **Sieve of Eratosthenes:** mark composites in `O(n log log n)`; the smallest-prime-factor variant gives `O(log x)` factorization afterward.
- **`MOD = 1_000_000_007`** is prime; every intermediate product fits in a `long` because `(10^9)^2 < 9.2·10^18 = Long.MAX_VALUE`.

Throughout, `MOD` denotes `1_000_000_007L` unless stated otherwise.

---

## Coding Problems

### Problem 1: Greatest Common Divisor — Euclid's algorithm

**Statement.** Compute `gcd(a, b)` for non-negative integers.

**Approach.** Repeatedly replace `(a, b)` with `(b, a mod b)` until `b` is 0; the remaining `a` is the GCD.

```java
public class Gcd {
    // Iterative Euclid — no recursion-depth risk.
    public static long gcd(long a, long b) {
        while (b != 0) {
            long t = a % b;
            a = b;
            b = t;
        }
        return a;
    }
}
```

**Time:** O(log min(a,b)) · **Space:** O(1)

**Insight.** Each step at least halves the larger argument within two iterations (Lamé's theorem), giving logarithmic time.

---

### Problem 2: Least Common Multiple — divide before multiply

**Statement.** Compute `lcm(a, b)` without overflowing for inputs up to `10^9`.

**Approach.** `lcm = a / gcd(a, b) * b`. Dividing first keeps the intermediate small; the result still fits in a `long`.

```java
public class Lcm {
    public static long lcm(long a, long b) {
        if (a == 0 || b == 0) return 0;
        return a / Gcd.gcd(a, b) * b; // divide first: a/g is exact
    }
}
```

**Time:** O(log min(a,b)) · **Space:** O(1)

**Insight.** `a / gcd * b` not `a * b / gcd` — the latter overflows even though the final answer fits.

---

### Problem 3: GCD of an Array — fold with Euclid

**Statement.** Given an array, return the GCD of all elements.

**Approach.** Fold `gcd` left to right. Once the running GCD hits 1 you can stop early.

```java
public class GcdArray {
    public static long gcdAll(long[] a) {
        long g = 0; // gcd(0, x) = x, so this seeds correctly
        for (long x : a) {
            g = Gcd.gcd(g, x);
            if (g == 1) break; // cannot get smaller
        }
        return g;
    }
}
```

**Time:** O(n log max) · **Space:** O(1)

**Insight.** Seeding with 0 works because `gcd(0, x) = x`; the early exit at 1 is a common micro-optimization.

---

### Problem 4: Extended Euclidean Algorithm — Bézout coefficients

**Statement.** Find integers `x, y` with `a·x + b·y = gcd(a, b)`.

**Approach.** Recurse on `gcd(b, a mod b)`; when it returns `(g, x1, y1)` for the sub-call, back-substitute `x = y1`, `y = x1 - (a/b)·y1`.

```java
public class ExtGcd {
    // Returns {g, x, y} with a*x + b*y = g.
    public static long[] extgcd(long a, long b) {
        if (b == 0) return new long[]{a, 1, 0};
        long[] r = extgcd(b, a % b);
        long g = r[0], x1 = r[1], y1 = r[2];
        return new long[]{g, y1, x1 - (a / b) * y1};
    }
}
```

**Time:** O(log min(a,b)) · **Space:** O(log min(a,b)) recursion

**Insight.** Extended GCD is the general way to get a modular inverse — it works for *any* modulus, not just primes.

---

### Problem 5: Modular Addition and Subtraction — safe normalization

**Statement.** Compute `(a + b) mod m` and `(a - b) mod m` so the result is always in `[0, m)`, even for negative inputs.

**Approach.** Add then take modulus; for subtraction, add `m` before the final modulus to repair Java's signed `%`.

```java
public class ModAddSub {
    public static long add(long a, long b, long m) {
        return ((a % m) + (b % m)) % m;
    }
    public static long sub(long a, long b, long m) {
        return ((a % m - b % m) % m + m) % m; // +m fixes negatives
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** `-1 % m` is `-1` in Java; the `+ m` before the last `% m` is the universal fix.

---

### Problem 6: Modular Multiplication — promote to long

**Statement.** Compute `(a · b) mod m` for `a, b < 10^9` without overflow.

**Approach.** Cast each operand to `long` before multiplying — `(10^9)^2` fits in a `long` (`< 9.2·10^18`).

```java
public class ModMul {
    public static long mul(long a, long b, long m) {
        return (a % m) * (b % m) % m;
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** If `m` can exceed `~3·10^9`, the product overflows even a `long`; then you need `Math.multiplyHigh`/`BigInteger` or `__int128`-style mulmod.

---

### Problem 7: Binary (Fast) Exponentiation — a^n in O(log n)

**Statement.** Compute `a^n mod m` for `n` up to `10^18`.

**Approach.** Square `a` repeatedly; whenever the current bit of `n` is set, fold the square into the result.

```java
public class FastPow {
    public static long power(long a, long n, long m) {
        long result = 1 % m;
        a %= m;
        while (n > 0) {
            if ((n & 1) == 1) result = result * a % m;
            a = a * a % m;
            n >>= 1;
        }
        return result;
    }
}
```

**Time:** O(log n) · **Space:** O(1)

**Insight.** `result = 1 % m` (not `1`) handles the edge case `m == 1`, where everything is `0`.

---

### Problem 8: Modular Inverse via Fermat's Little Theorem

**Statement.** For prime `p`, compute `a^(-1) mod p`.

**Approach.** By Fermat, `a^(p-1) ≡ 1`, so `a^(-1) ≡ a^(p-2)`. Use fast exponentiation.

```java
public class ModInverseFermat {
    static final long MOD = 1_000_000_007L;
    public static long inverse(long a) {
        return FastPow.power(a, MOD - 2, MOD);
    }
}
```

**Time:** O(log p) · **Space:** O(1)

**Insight.** Only valid when the modulus is prime and `gcd(a, p) = 1`; otherwise use extended-Euclid inverse.

---

### Problem 9: Modular Inverse via Extended Euclid — any coprime modulus

**Statement.** Compute `a^(-1) mod m` when `m` is not necessarily prime, assuming `gcd(a, m) = 1`.

**Approach.** Extended GCD gives `a·x + m·y = 1`, so `x mod m` is the inverse. Normalize to `[0, m)`.

```java
public class ModInverseExt {
    public static long inverse(long a, long m) {
        long[] r = ExtGcd.extgcd(((a % m) + m) % m, m);
        if (r[0] != 1) throw new ArithmeticException("no inverse: gcd != 1");
        return ((r[1] % m) + m) % m;
    }
}
```

**Time:** O(log m) · **Space:** O(log m)

**Insight.** This is strictly more general than Fermat — Fermat is just the special-case shortcut for prime moduli.

---

### Problem 10: Modular Division — multiply by the inverse

**Statement.** Compute `(a / b) mod p` for prime `p`, where the division is exact in `ℤ/pℤ`.

**Approach.** Division is multiplication by the modular inverse: `a · b^(-1) mod p`.

```java
public class ModDiv {
    static final long MOD = 1_000_000_007L;
    public static long divide(long a, long b) {
        return a % MOD * ModInverseFermat.inverse(b) % MOD;
    }
}
```

**Time:** O(log p) · **Space:** O(1)

**Insight.** There is no "integer division" in modular arithmetic — only multiplication by an inverse.

---

### Problem 11: Iterative Modular Inverses 1..n — linear precompute

**Statement.** Compute `inv[i] = i^(-1) mod p` for all `i` in `[1, n]` in `O(n)`.

**Approach.** Use the recurrence `inv[i] = -(p/i) · inv[p mod i] mod p`, seeded with `inv[1] = 1`.

```java
public class InverseTable {
    public static long[] inverses(int n, long p) {
        long[] inv = new long[n + 1];
        inv[1] = 1;
        for (int i = 2; i <= n; i++) {
            inv[i] = (p - (p / i) * inv[(int) (p % i)] % p) % p;
        }
        return inv;
    }
}
```

**Time:** O(n) · **Space:** O(n)

**Insight.** Writing `p = (p/i)·i + (p mod i)` modulo `p` and rearranging yields this elegant linear recurrence — far cheaper than `n` separate `log p` exponentiations.

---

### Problem 12: Sieve of Eratosthenes — all primes ≤ n

**Statement.** Return a boolean table where `isPrime[i]` marks primality for `i ≤ n`.

**Approach.** Start all true, clear 0 and 1, then for each prime `i` clear its multiples starting at `i*i`.

```java
public class Sieve {
    public static boolean[] sieve(int n) {
        boolean[] isPrime = new boolean[n + 1];
        java.util.Arrays.fill(isPrime, true);
        if (n >= 0) isPrime[0] = false;
        if (n >= 1) isPrime[1] = false;
        for (long i = 2; i * i <= n; i++) {
            if (isPrime[(int) i]) {
                for (long j = i * i; j <= n; j += i) isPrime[(int) j] = false;
            }
        }
        return isPrime;
    }
}
```

**Time:** O(n log log n) · **Space:** O(n)

**Insight.** Starting the inner loop at `i*i` (not `2*i`) skips multiples already cleared by smaller primes; `i*i` must be a `long` to avoid overflow near `n ≈ 2^31`.

---

### Problem 13: Linear Sieve — smallest prime factor in O(n)

**Statement.** Build a table `spf[x]` = smallest prime factor of `x`, plus the prime list, in true linear time.

**Approach.** Each composite is marked exactly once by its smallest prime factor; break when `i % p == 0` to preserve that invariant.

```java
import java.util.*;

public class LinearSieve {
    public static int[] smallestPrimeFactors(int n) {
        int[] spf = new int[n + 1];
        List<Integer> primes = new ArrayList<>();
        for (int i = 2; i <= n; i++) {
            if (spf[i] == 0) { spf[i] = i; primes.add(i); }
            for (int p : primes) {
                if (p > spf[i] || (long) p * i > n) break;
                spf[p * i] = p;
            }
        }
        return spf;
    }
}
```

**Time:** O(n) · **Space:** O(n)

**Insight.** The `break` when `p > spf[i]` is what guarantees each number is touched once — the difference between linear and `n log log n`.

---

### Problem 14: Prime Factorization by Trial Division — up to √n

**Statement.** Factor a single integer `n` (up to `10^12`) into prime powers.

**Approach.** Divide out each candidate `d` from 2 upward while `d*d ≤ n`; any leftover `n > 1` is a prime factor.

```java
import java.util.*;

public class TrialFactor {
    // Returns prime -> exponent.
    public static Map<Long, Integer> factorize(long n) {
        Map<Long, Integer> f = new LinkedHashMap<>();
        for (long d = 2; d * d <= n; d++) {
            while (n % d == 0) { f.merge(d, 1, Integer::sum); n /= d; }
        }
        if (n > 1) f.merge(n, 1, Integer::sum);
        return f;
    }
}
```

**Time:** O(√n) · **Space:** O(log n) factors

**Insight.** Only one prime factor can exceed `√n`, which is why the leftover `n > 1` is captured separately.

---

### Problem 15: Fast Factorization with a Sieve — O(log x) per query

**Statement.** Given the `spf` table from Problem 13, factor any `x ≤ n` in `O(log x)`.

**Approach.** Repeatedly divide `x` by `spf[x]`, accumulating exponents, until `x` becomes 1.

```java
import java.util.*;

public class SieveFactor {
    public static Map<Integer, Integer> factor(int x, int[] spf) {
        Map<Integer, Integer> f = new LinkedHashMap<>();
        while (x > 1) {
            int p = spf[x];
            while (x % p == 0) { f.merge(p, 1, Integer::sum); x /= p; }
        }
        return f;
    }
}
```

**Time:** O(log x) per query · **Space:** O(log x)

**Insight.** Each division at least halves `x`, bounding the factor count by `log₂ x`.

---

### Problem 16: Count Divisors — from the prime factorization

**Statement.** Count the number of positive divisors of `n`.

**Approach.** If `n = ∏ pᵢ^eᵢ`, the divisor count is `∏ (eᵢ + 1)` — each exponent can independently range from 0 to `eᵢ`.

```java
public class CountDivisors {
    public static long countDivisors(long n) {
        long count = 1;
        for (long d = 2; d * d <= n; d++) {
            int e = 0;
            while (n % d == 0) { n /= d; e++; }
            count *= (e + 1);
        }
        if (n > 1) count *= 2; // a leftover prime contributes exponent 1
        return count;
    }
}
```

**Time:** O(√n) · **Space:** O(1)

**Insight.** The multiplicative `∏(eᵢ+1)` formula is why divisor count is fast once you have the factorization.

---

### Problem 17: Sum of Divisors — geometric series per prime

**Statement.** Compute the sum of all positive divisors of `n`.

**Approach.** Sigma is multiplicative: for each prime power `p^e`, the contribution is `1 + p + p² + … + p^e = (p^(e+1) - 1)/(p - 1)`; multiply across primes.

```java
public class SumDivisors {
    public static long sigma(long n) {
        long total = 1;
        for (long d = 2; d * d <= n; d++) {
            if (n % d == 0) {
                long term = 1, pk = 1;
                while (n % d == 0) { n /= d; pk *= d; term += pk; }
                total *= term;
            }
        }
        if (n > 1) total *= (1 + n);
        return total;
    }
}
```

**Time:** O(√n) · **Space:** O(1)

**Insight.** Accumulating `1 + p + … + p^e` iteratively avoids a division and any modular-inverse subtlety.

---

### Problem 18: Euler's Totient — count of coprimes ≤ n

**Statement.** Compute `φ(n)`, the number of integers in `[1, n]` coprime to `n`.

**Approach.** `φ(n) = n · ∏ (1 - 1/p)` over distinct primes `p | n`. Compute by starting at `n` and applying `result -= result/p` per prime.

```java
public class Totient {
    public static long phi(long n) {
        long result = n;
        for (long p = 2; p * p <= n; p++) {
            if (n % p == 0) {
                while (n % p == 0) n /= p;
                result -= result / p;
            }
        }
        if (n > 1) result -= result / n; // remaining prime factor
        return result;
    }
}
```

**Time:** O(√n) · **Space:** O(1)

**Insight.** `result -= result/p` applies the factor `(1 - 1/p)` using only integer arithmetic — order matters, so reduce `result` before moving to the next prime.

---

### Problem 19: Totient Sieve — φ(i) for all i ≤ n

**Statement.** Precompute `φ(i)` for every `i` in `[1, n]`.

**Approach.** Sieve-style: initialize `phi[i] = i`, then for each prime `p` subtract `phi[i]/p` from every multiple of `p`.

```java
public class TotientSieve {
    public static int[] phiTable(int n) {
        int[] phi = new int[n + 1];
        for (int i = 0; i <= n; i++) phi[i] = i;
        for (int p = 2; p <= n; p++) {
            if (phi[p] == p) { // p is prime
                for (int j = p; j <= n; j += p) phi[j] -= phi[j] / p;
            }
        }
        return phi;
    }
}
```

**Time:** O(n log log n) · **Space:** O(n)

**Insight.** `phi[p] == p` still holding means `p` was never reduced, hence prime — the sieve doubles as a primality test.

---

### Problem 20: Primality Test — trial division to √n

**Statement.** Decide whether a single `n` (up to `10^12`) is prime.

**Approach.** Handle 2 and 3, then test only candidates of the form `6k ± 1` up to `√n`.

```java
public class IsPrime {
    public static boolean isPrime(long n) {
        if (n < 2) return false;
        if (n < 4) return true;           // 2, 3
        if (n % 2 == 0 || n % 3 == 0) return false;
        for (long i = 5; i * i <= n; i += 6) {
            if (n % i == 0 || n % (i + 2) == 0) return false;
        }
        return true;
    }
}
```

**Time:** O(√n) · **Space:** O(1)

**Insight.** Every prime > 3 is `6k ± 1`, so the `i += 6` stride skips two-thirds of candidates.

---

### Problem 21: Miller–Rabin — deterministic primality for 64-bit

**Statement.** Test primality of any `n < 2^63` correctly and fast.

**Approach.** Write `n - 1 = d·2^s`; for a fixed set of witnesses that is deterministic below `3.3·10^24`, check `a^d` and its repeated squares. Use `mulmod` via 128-bit (`Math.multiplyHigh`) or `BigInteger` to avoid overflow.

```java
import java.math.BigInteger;

public class MillerRabin {
    public static boolean isPrime(long n) {
        if (n < 2) return false;
        for (long p : new long[]{2,3,5,7,11,13,17,19,23,29,31,37}) {
            if (n % p == 0) return n == p;
        }
        long d = n - 1; int s = 0;
        while ((d & 1) == 0) { d >>= 1; s++; }
        for (long a : new long[]{2,3,5,7,11,13,17,19,23,29,31,37}) {
            if (!check(a, d, n, s)) return false;
        }
        return true;
    }
    private static boolean check(long a, long d, long n, int s) {
        long x = BigInteger.valueOf(a)
                .modPow(BigInteger.valueOf(d), BigInteger.valueOf(n)).longValue();
        if (x == 1 || x == n - 1) return true;
        for (int i = 1; i < s; i++) {
            x = BigInteger.valueOf(x).pow(2).mod(BigInteger.valueOf(n)).longValue();
            if (x == n - 1) return true;
        }
        return false;
    }
}
```

**Time:** O(k log³ n) with k witnesses · **Space:** O(1)

**Insight.** The 12-base witness set is *proven* deterministic for all 64-bit integers — no probabilistic risk for interview-sized inputs.

---

### Problem 22: Factorial mod p — with overflow control

**Statement.** Compute `n! mod p` for `n` up to `10^7`.

**Approach.** Multiply iteratively, taking the modulus every step so the running product never exceeds `p·(p-1) < Long.MAX_VALUE`.

```java
public class FactorialMod {
    static final long MOD = 1_000_000_007L;
    public static long factorial(int n) {
        long result = 1;
        for (int i = 2; i <= n; i++) result = result * i % MOD;
        return result;
    }
}
```

**Time:** O(n) · **Space:** O(1)

**Insight.** Taking `% MOD` *inside* the loop is the entire trick — defer it and you overflow at `n = 21`.

---

### Problem 23: nCr mod p — precomputed factorials and inverses

**Statement.** Answer many `C(n, r) mod p` queries with `n` up to `10^6`.

**Approach.** Precompute `fact[]` and `invFact[]` once (`invFact[n] = fact[n]^(p-2)`, then fill downward), so each query is three multiplications.

```java
public class Binomial {
    static final long MOD = 1_000_000_007L;
    static long[] fact, invFact;

    public static void init(int n) {
        fact = new long[n + 1];
        invFact = new long[n + 1];
        fact[0] = 1;
        for (int i = 1; i <= n; i++) fact[i] = fact[i - 1] * i % MOD;
        invFact[n] = FastPow.power(fact[n], MOD - 2, MOD);
        for (int i = n; i > 0; i--) invFact[i - 1] = invFact[i] * i % MOD;
    }

    public static long nCr(int n, int r) {
        if (r < 0 || r > n) return 0;
        return fact[n] * invFact[r] % MOD * invFact[n - r] % MOD;
    }
}
```

**Time:** O(n) precompute, O(1) per query · **Space:** O(n)

**Insight.** Filling `invFact` downward with `invFact[i-1] = invFact[i]·i` costs one inverse total instead of `n` — a classic precompute pattern.

---

### Problem 24: nPr mod p — permutations from factorials

**Statement.** Compute `P(n, r) = n! / (n-r)! mod p`.

**Approach.** With the same precomputed tables, `nPr = fact[n] · invFact[n-r] mod p`.

```java
public class Permutations {
    static final long MOD = 1_000_000_007L;
    public static long nPr(int n, int r) {
        if (r < 0 || r > n) return 0;
        return Binomial.fact[n] * Binomial.invFact[n - r] % MOD;
    }
}
```

**Time:** O(1) per query · **Space:** O(1) extra

**Insight.** Permutations drop the `1/r!` ordering factor that distinguishes them from combinations.

---

### Problem 25: Pascal's Triangle — additive binomial table

**Statement.** Build the first `n` rows of Pascal's triangle (binomial coefficients), modulo `p`.

**Approach.** `C(i, j) = C(i-1, j-1) + C(i-1, j)`; no division or inverse needed for small `n`.

```java
public class PascalTriangle {
    static final long MOD = 1_000_000_007L;
    public static long[][] build(int n) {
        long[][] c = new long[n + 1][];
        for (int i = 0; i <= n; i++) {
            c[i] = new long[i + 1];
            c[i][0] = c[i][i] = 1;
            for (int j = 1; j < i; j++) {
                c[i][j] = (c[i - 1][j - 1] + c[i - 1][j]) % MOD;
            }
        }
        return c;
    }
}
```

**Time:** O(n²) · **Space:** O(n²)

**Insight.** Pascal's recurrence avoids modular inverses entirely — ideal when `n ≤ a few thousand` and the modulus is composite.

---

### Problem 26: Lucas' Theorem — nCr mod small prime for huge n

**Statement.** Compute `C(n, r) mod p` where `n, r` can be up to `10^18` but `p` is a small prime (`≤ 10^5`).

**Approach.** Lucas: `C(n, r) ≡ ∏ C(nᵢ, rᵢ) (mod p)` over the base-`p` digits of `n` and `r`. Each small `C` uses precomputed factorials mod `p`.

```java
public class Lucas {
    static long[] fact, invFact;
    static long P;

    public static void init(long p) {
        P = p;
        fact = new long[(int) p];
        invFact = new long[(int) p];
        fact[0] = 1;
        for (int i = 1; i < p; i++) fact[i] = fact[i - 1] * i % p;
        invFact[(int) p - 1] = FastPow.power(fact[(int) p - 1], p - 2, p);
        for (int i = (int) p - 1; i > 0; i--) invFact[i - 1] = invFact[i] * i % p;
    }
    private static long small(long n, long r) {
        if (r < 0 || r > n) return 0;
        return fact[(int) n] * invFact[(int) r] % P * invFact[(int) (n - r)] % P;
    }
    public static long nCr(long n, long r) {
        long result = 1;
        while (n > 0 || r > 0) {
            result = result * small(n % P, r % P) % P;
            n /= P; r /= P;
        }
        return result;
    }
}
```

**Time:** O(p + log_p(n)) · **Space:** O(p)

**Insight.** Lucas reduces an astronomically large binomial to a product of digit-sized ones — the only practical route when `n` dwarfs the modulus.

---

### Problem 27: Catalan Numbers — DP recurrence

**Statement.** Compute the `n`-th Catalan number `Cₙ` modulo `p`.

**Approach.** Use `Cₙ = Σ_{i=0}^{n-1} Cᵢ · C_{n-1-i}` with `C₀ = 1`. (Counts balanced parentheses, BSTs, triangulations, …)

```java
public class CatalanDP {
    static final long MOD = 1_000_000_007L;
    public static long catalan(int n) {
        long[] c = new long[n + 1];
        c[0] = 1;
        for (int i = 1; i <= n; i++) {
            for (int j = 0; j < i; j++) {
                c[i] = (c[i] + c[j] * c[i - 1 - j]) % MOD;
            }
        }
        return c[n];
    }
}
```

**Time:** O(n²) · **Space:** O(n)

**Insight.** The convolution recurrence mirrors "split at the root" — every Catalan interpretation reduces to choosing a split point.

---

### Problem 28: Catalan Number in Closed Form — binomial route

**Statement.** Compute `Cₙ = C(2n, n) / (n + 1) mod p` in `O(log p)` per query.

**Approach.** Reuse precomputed factorials: `Cₙ = C(2n, n) · (n+1)^(-1) mod p`.

```java
public class CatalanClosed {
    static final long MOD = 1_000_000_007L;
    public static long catalan(int n) {
        long c2n = Binomial.nCr(2 * n, n);
        long inv = FastPow.power(n + 1, MOD - 2, MOD);
        return c2n * inv % MOD;
    }
}
```

**Time:** O(log p) per query · **Space:** O(1) beyond the factorial table

**Insight.** Equivalent forms `C(2n,n) - C(2n,n+1)` and `C(2n,n)/(n+1)` both work; the inverse form is simplest with a factorial table on hand.

---

### Problem 29: Stars and Bars — non-negative integer solutions

**Statement.** Count the solutions to `x₁ + x₂ + … + x_k = n` in non-negative integers, modulo `p`.

**Approach.** The count is `C(n + k - 1, k - 1)` — distribute `n` stars among `k` bins using `k-1` bars.

```java
public class StarsAndBars {
    public static long count(int n, int k) {
        return Binomial.nCr(n + k - 1, k - 1);
    }
}
```

**Time:** O(1) per query · **Space:** O(1)

**Insight.** Stars-and-bars converts a constrained-sum count into a single binomial — recognize the pattern and the formula is immediate.

---

### Problem 30: Chinese Remainder Theorem — two congruences

**Statement.** Find `x` with `x ≡ a₁ (mod m₁)` and `x ≡ a₂ (mod m₂)`, where `m₁, m₂` are coprime.

**Approach.** Extended GCD gives `m₁·p + m₂·q = 1`; then `x = a₁·m₂·q + a₂·m₁·p (mod m₁m₂)`. Normalize to `[0, m₁m₂)`.

```java
public class CrtPair {
    // Solve x ≡ a1 (mod m1), x ≡ a2 (mod m2), gcd(m1, m2) = 1.
    public static long crt(long a1, long m1, long a2, long m2) {
        long[] g = ExtGcd.extgcd(m1, m2); // g[0] must be 1
        long mod = m1 * m2;
        long p = g[1], q = g[2];
        // x = a1 + m1 * (a2 - a1) * p mod mod
        long diff = ((a2 - a1) % m2 + m2) % m2;
        long t = diff % m2 * (p % m2) % m2;
        t = ((t % m2) + m2) % m2;
        long x = (a1 + m1 % mod * t % mod) % mod;
        return ((x % mod) + mod) % mod;
    }
}
```

**Time:** O(log min(m1,m2)) · **Space:** O(1)

**Insight.** The construction works because `m₂·q ≡ 1 (mod m₁)` and `≡ 0 (mod m₂)` — it isolates each residue.

---

### Problem 31: General CRT — fold a system of congruences

**Statement.** Solve a system `x ≡ aᵢ (mod mᵢ)` for arbitrary (possibly non-coprime) moduli, or report no solution.

**Approach.** Fold pairwise: merge the running `(a, m)` with the next `(aᵢ, mᵢ)` using extended GCD; a solution exists only if `(aᵢ - a)` is divisible by `gcd(m, mᵢ)`.

```java
public class CrtGeneral {
    // Returns {x, lcm} or null if inconsistent.
    public static long[] solve(long[] a, long[] m) {
        long x = 0, mod = 1;
        for (int i = 0; i < a.length; i++) {
            long[] g = ExtGcd.extgcd(mod, m[i]);
            long gcd = g[0];
            long diff = a[i] - x;
            if (diff % gcd != 0) return null; // inconsistent
            long lcm = mod / gcd * m[i];
            long step = (diff / gcd) % (m[i] / gcd) * (g[1] % (m[i] / gcd)) % (m[i] / gcd);
            x = ((x + mod * step) % lcm + lcm) % lcm;
            mod = lcm;
        }
        return new long[]{x, mod};
    }
}
```

**Time:** O(k log M) · **Space:** O(1)

**Insight.** The divisibility check `diff % gcd != 0` is the consistency test — non-coprime CRT can genuinely have no solution.

---

### Problem 32: Fibonacci by Matrix Exponentiation — O(log n)

**Statement.** Compute `F(n) mod p` for `n` up to `10^18`.

**Approach.** `[[1,1],[1,0]]^n = [[F(n+1), F(n)], [F(n), F(n-1)]]`. Raise the matrix by binary exponentiation.

```java
public class FibMatrix {
    static final long MOD = 1_000_000_007L;

    static long[][] mul(long[][] a, long[][] b) {
        long[][] c = new long[2][2];
        for (int i = 0; i < 2; i++)
            for (int j = 0; j < 2; j++)
                for (int k = 0; k < 2; k++)
                    c[i][j] = (c[i][j] + a[i][k] * b[k][j]) % MOD;
        return c;
    }

    public static long fib(long n) {
        long[][] result = {{1, 0}, {0, 1}}; // identity
        long[][] base = {{1, 1}, {1, 0}};
        while (n > 0) {
            if ((n & 1) == 1) result = mul(result, base);
            base = mul(base, base);
            n >>= 1;
        }
        return result[0][1]; // F(n)
    }
}
```

**Time:** O(log n) · **Space:** O(1)

**Insight.** Any linear recurrence with constant coefficients becomes matrix exponentiation — Fibonacci is the textbook case.

---

### Problem 33: Generic Linear Recurrence — matrix power for order-k

**Statement.** Given `f(n) = c₁f(n-1) + … + c_k f(n-k)` and seeds, compute `f(n) mod p` for huge `n`.

**Approach.** Build the `k×k` companion matrix (coefficients on row 0, identity sub-diagonal) and raise it to the `(n - k + 1)`-th power.

```java
public class LinearRecurrence {
    static final long MOD = 1_000_000_007L;

    static long[][] mul(long[][] a, long[][] b, int k) {
        long[][] c = new long[k][k];
        for (int i = 0; i < k; i++)
            for (int t = 0; t < k; t++) {
                if (a[i][t] == 0) continue;
                for (int j = 0; j < k; j++)
                    c[i][j] = (c[i][j] + a[i][t] * b[t][j]) % MOD;
            }
        return c;
    }
    static long[][] pow(long[][] m, long e, int k) {
        long[][] r = new long[k][k];
        for (int i = 0; i < k; i++) r[i][i] = 1;
        while (e > 0) {
            if ((e & 1) == 1) r = mul(r, m, k);
            m = mul(m, m, k);
            e >>= 1;
        }
        return r;
    }
    // c[] are the k coefficients, seed[] are f(0..k-1).
    public static long compute(long[] c, long[] seed, long n) {
        int k = c.length;
        if (n < k) return ((seed[(int) n] % MOD) + MOD) % MOD;
        long[][] m = new long[k][k];
        for (int j = 0; j < k; j++) m[0][j] = ((c[j] % MOD) + MOD) % MOD;
        for (int i = 1; i < k; i++) m[i][i - 1] = 1;
        long[][] mp = pow(m, n - (k - 1), k);
        long res = 0;
        for (int j = 0; j < k; j++)
            res = (res + mp[0][j] * (((seed[k - 1 - j] % MOD) + MOD) % MOD)) % MOD;
        return res;
    }
}
```

**Time:** O(k³ log n) · **Space:** O(k²)

**Insight.** The companion matrix turns *any* fixed-order recurrence into a single `O(log n)` matrix power — the universal generalization of the Fibonacci trick.

---

### Problem 34: Sum of Geometric Series mod p — log-time recursion

**Statement.** Compute `1 + r + r² + … + r^(n-1) mod p` for huge `n`.

**Approach.** Recurse: for even `n`, `S(n) = S(n/2)·(1 + r^(n/2))`; for odd `n`, peel one term. Avoids the `(r-1)^(-1)` division which fails when `r ≡ 1`.

```java
public class GeoSeries {
    static final long MOD = 1_000_000_007L;
    // 1 + r + ... + r^(n-1)
    public static long sum(long r, long n) {
        if (n == 0) return 0;
        if (n == 1) return 1 % MOD;
        long half = sum(r, n / 2);
        long rHalf = FastPow.power(r, n / 2, MOD);
        long s = half * (1 + rHalf) % MOD;
        if ((n & 1) == 1) s = (s + FastPow.power(r, n - 1, MOD)) % MOD;
        return s;
    }
}
```

**Time:** O(log² n) · **Space:** O(log n)

**Insight.** The split-and-double recursion sidesteps the singular `r = 1` case that the closed-form `(r^n - 1)/(r - 1)` cannot handle modularly.

---

### Problem 35: Base Conversion — arbitrary radix to/from decimal

**Statement.** Convert a non-negative integer to a string in base `b` (2 ≤ b ≤ 36) and back.

**Approach.** Repeatedly take `n % b` for digits (least significant first, then reverse); parse by Horner's method `value = value·b + digit`.

```java
public class BaseConvert {
    static final String D = "0123456789abcdefghijklmnopqrstuvwxyz";

    public static String toBase(long n, int b) {
        if (n == 0) return "0";
        StringBuilder sb = new StringBuilder();
        while (n > 0) { sb.append(D.charAt((int) (n % b))); n /= b; }
        return sb.reverse().toString();
    }
    public static long fromBase(String s, int b) {
        long v = 0;
        for (char c : s.toCharArray()) v = v * b + D.indexOf(Character.toLowerCase(c));
        return v;
    }
}
```

**Time:** O(log_b n) · **Space:** O(log_b n)

**Insight.** Division extracts least-significant digits; Horner's method reverses the process in a single pass without `pow`.

---

### Problem 36: Convert Between Two Arbitrary Bases — via long

**Statement.** Given a number as a string in base `from`, output it as a string in base `to`.

**Approach.** Decode to a `long`, then re-encode — reusing Problem 35. (For values beyond 64 bits, use repeated long-division on the digit array instead.)

```java
public class BaseToBase {
    public static String convert(String num, int from, int to) {
        long value = BaseConvert.fromBase(num, from);
        return BaseConvert.toBase(value, to);
    }
}
```

**Time:** O(len) · **Space:** O(len)

**Insight.** Decimal is just a pivot; the only trap is overflow, which forces digit-array division for very long inputs.

---

### Problem 37: Digit Sum and Digital Root — repeated digit sum

**Statement.** Compute the iterated digit sum (digital root) of `n`.

**Approach.** The digital root has a closed form: `0` if `n == 0`, else `1 + (n - 1) % 9` — a consequence of `n ≡ digitSum(n) (mod 9)`.

```java
public class DigitalRoot {
    public static int digitalRoot(long n) {
        if (n == 0) return 0;
        return (int) (1 + (n - 1) % 9);
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** Casting out nines: every number is congruent to its digit sum mod 9, collapsing the iteration to one formula.

---

### Problem 38: Count Trailing Zeros of n! — Legendre's formula

**Statement.** How many trailing zeros does `n!` have in decimal?

**Approach.** Trailing zeros come from factors of 5 (5 is rarer than 2): `Σ ⌊n/5^k⌋` over `k ≥ 1`.

```java
public class FactorialTrailingZeros {
    public static long trailingZeros(long n) {
        long count = 0;
        for (long p = 5; p <= n; p *= 5) count += n / p;
        return count;
    }
}
```

**Time:** O(log₅ n) · **Space:** O(1)

**Insight.** Legendre's formula counts the exponent of a prime in `n!`; for trailing zeros only the limiting prime 5 matters.

---

### Problem 39: Exponent of a Prime in n! — Legendre generalized

**Statement.** Compute the largest `e` with `p^e | n!`, for prime `p`.

**Approach.** Same `Σ ⌊n/p^k⌋` sum, now for an arbitrary prime.

```java
public class PrimeExponentInFactorial {
    public static long exponent(long n, long p) {
        long count = 0;
        for (long pk = p; pk <= n; pk *= p) count += n / pk;
        return count;
    }
}
```

**Time:** O(log_p n) · **Space:** O(1)

**Insight.** Watch for `pk *= p` overflow when `p` is large; once `pk > n` the loop terminates, so guard with the `<= n` test (a `long` `pk` is enough for `n ≤ 10^18`).

---

### Problem 40: Integer Square Root — overflow-safe binary search

**Statement.** Compute `⌊√n⌋` exactly for `n` up to `10^18`, without floating-point rounding errors.

**Approach.** Binary search the answer in `[0, 2·10^9]`; test `mid·mid ≤ n` using `long` (or guard `mid > n/mid` to avoid overflow).

```java
public class IntSqrt {
    public static long isqrt(long n) {
        long lo = 0, hi = 2_000_000_000L, ans = 0;
        while (lo <= hi) {
            long mid = lo + (hi - lo) / 2;
            if (mid <= n / mid) { ans = mid; lo = mid + 1; } // mid*mid <= n, no overflow
            else hi = mid - 1;
        }
        return ans;
    }
}
```

**Time:** O(log n) · **Space:** O(1)

**Insight.** `mid <= n/mid` replaces the overflow-prone `mid*mid <= n`; floating `Math.sqrt` can be off by one near perfect squares, so binary search is the safe interview answer.

---

### Problem 41: Fast Power without Modulus — overflow detection

**Statement.** Compute `base^exp` as an exact `long`, returning a sentinel (or throwing) on overflow.

**Approach.** Binary exponentiation, but multiply with `Math.multiplyExact` so overflow raises `ArithmeticException`.

```java
public class SafePow {
    public static long power(long base, long exp) {
        long result = 1;
        while (exp > 0) {
            if ((exp & 1) == 1) result = Math.multiplyExact(result, base);
            exp >>= 1;
            if (exp > 0) base = Math.multiplyExact(base, base);
        }
        return result;
    }
}
```

**Time:** O(log exp) · **Space:** O(1)

**Insight.** `Math.multiplyExact` is the clean way to make silent two's-complement wraparound an explicit, catchable error — guard the final `base` square so it isn't computed when unused.

---

### Problem 42: Add Two Numbers Without Overflow — saturating add

**Statement.** Add two `int`s, clamping to `Integer.MAX_VALUE`/`MIN_VALUE` instead of wrapping.

**Approach.** Compute in `long`, then clamp to the `int` range.

```java
public class SaturatingAdd {
    public static int add(int a, int b) {
        long sum = (long) a + b;
        if (sum > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (sum < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) sum;
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** Widening to `long` before the add is the simplest overflow-safe pattern; `Math.addExact` is the throwing alternative when wrapping must be a hard error.

---

### Problem 43: Reverse Integer with Overflow Guard

**Statement.** Reverse the digits of a 32-bit signed integer; return 0 if the reversed value overflows `int`.

**Approach.** Pop digits one at a time; before each `rev = rev*10 + digit`, check the multiplication/addition against the `int` bounds.

```java
public class ReverseInteger {
    public static int reverse(int x) {
        int rev = 0;
        while (x != 0) {
            int digit = x % 10;
            x /= 10;
            // Pre-check to avoid overflowing rev*10 + digit.
            if (rev > Integer.MAX_VALUE / 10 || (rev == Integer.MAX_VALUE / 10 && digit > 7)) return 0;
            if (rev < Integer.MIN_VALUE / 10 || (rev == Integer.MIN_VALUE / 10 && digit < -8)) return 0;
            rev = rev * 10 + digit;
        }
        return rev;
    }
}
```

**Time:** O(log x) · **Space:** O(1)

**Insight.** The digit bounds 7 and -8 come from `Integer.MAX_VALUE % 10 == 7` and `MIN_VALUE % 10 == -8`; checking *before* multiplying is the only way to detect overflow without a wider type.

---

### Problem 44: Multiply Two Large Numbers as Strings — grade-school

**Statement.** Multiply two non-negative integers given as decimal strings (arbitrary length), returning the product string.

**Approach.** Grade-school multiplication into a `int[m+n]` buffer; `num1[i]·num2[j]` lands at positions `i+j` and `i+j+1`. Carry afterward.

```java
public class MultiplyStrings {
    public static String multiply(String a, String b) {
        if (a.equals("0") || b.equals("0")) return "0";
        int m = a.length(), n = b.length();
        int[] prod = new int[m + n];
        for (int i = m - 1; i >= 0; i--) {
            for (int j = n - 1; j >= 0; j--) {
                int mul = (a.charAt(i) - '0') * (b.charAt(j) - '0');
                int p1 = i + j, p2 = i + j + 1;
                int sum = mul + prod[p2];
                prod[p2] = sum % 10;
                prod[p1] += sum / 10;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int d : prod) if (!(sb.length() == 0 && d == 0)) sb.append(d);
        return sb.length() == 0 ? "0" : sb.toString();
    }
}
```

**Time:** O(m·n) · **Space:** O(m + n)

**Insight.** The position mapping `i+j, i+j+1` is the crux — it places each partial product before carrying, avoiding `BigInteger` while staying exact.

---

### Problem 45: Probability of Two People Sharing a Birthday — complement

**Statement.** Given `k` people and `d` equally likely birthdays, compute the probability that at least two share a birthday.

**Approach.** Use the complement: `P(all distinct) = ∏_{i=0}^{k-1} (d - i)/d`; the answer is `1 - that`.

```java
public class BirthdayParadox {
    public static double sharedProbability(int k, int d) {
        if (k > d) return 1.0; // pigeonhole guarantees a collision
        double pAllDistinct = 1.0;
        for (int i = 0; i < k; i++) {
            pAllDistinct *= (double) (d - i) / d;
        }
        return 1.0 - pAllDistinct;
    }
}
```

**Time:** O(k) · **Space:** O(1)

**Insight.** Computing the complement (all distinct) sidesteps inclusion–exclusion; with `d = 365`, just 23 people cross 50%.

---

### Problem 46: Expected Dice Rolls to Reach a Sum — DP on probability

**Statement.** A fair `m`-sided die is rolled repeatedly; compute the probability of *exactly* hitting target sum `n` at some point.

**Approach.** `p[s] = (1/m)·Σ_{f=1}^{m} p[s - f]`, with `p[0] = 1`. Each sum `s` is reached from the `m` prior sums.

```java
public class DiceSumProbability {
    public static double probability(int n, int m) {
        double[] p = new double[n + 1];
        p[0] = 1.0;
        for (int s = 1; s <= n; s++) {
            double sum = 0;
            for (int f = 1; f <= m && f <= s; f++) sum += p[s - f];
            p[s] = sum / m;
        }
        return p[n];
    }
}
```

**Time:** O(n·m) · **Space:** O(n)

**Insight.** This is a probability DP — each state aggregates the `m` ways to arrive, weighted by `1/m`; it's the discrete analog of a renewal equation.

---

### Problem 47: Random Index by Reservoir Sampling — uniform with one pass

**Statement.** Pick one element uniformly at random from a stream of unknown length, using `O(1)` memory.

**Approach.** Keep the current pick; for the `i`-th element (1-indexed), replace the pick with probability `1/i`.

```java
import java.util.Random;

public class ReservoirSampling {
    private final Random rng = new Random();
    private int count = 0;
    private int chosen = 0;

    public int next(int value) {
        count++;
        if (rng.nextInt(count) == 0) chosen = value; // prob 1/count
        return chosen;
    }
}
```

**Time:** O(1) per element · **Space:** O(1)

**Insight.** Induction shows each seen element ends up chosen with probability exactly `1/count` — the foundation of streaming uniform sampling.

---

### Problem 48: Count Numbers ≤ N with a Digit Constraint — digit DP

**Statement.** Count integers in `[0, N]` that do **not** contain the digit 4 (a classic digit-DP template).

**Approach.** DP over digit positions with state `(pos, tight)`: `tight` marks whether the prefix equals `N`'s prefix (bounding the next digit). At each position try every allowed digit.

```java
public class DigitDpNoFour {
    static int[] digits;
    static Long[][] memo; // memo[pos][tight]

    public static long count(long n) {
        String s = Long.toString(n);
        digits = new int[s.length()];
        for (int i = 0; i < s.length(); i++) digits[i] = s.charAt(i) - '0';
        memo = new Long[s.length()][2];
        return dp(0, 1);
    }
    private static long dp(int pos, int tight) {
        if (pos == digits.length) return 1; // one valid number formed
        if (memo[pos][tight] != null) return memo[pos][tight];
        int limit = (tight == 1) ? digits[pos] : 9;
        long total = 0;
        for (int d = 0; d <= limit; d++) {
            if (d == 4) continue; // forbidden digit
            total += dp(pos + 1, (tight == 1 && d == limit) ? 1 : 0);
        }
        return memo[pos][tight] = total;
    }
}
```

**Time:** O(len · 10 · 2) · **Space:** O(len · 2)

**Insight.** The `tight` flag is the heart of digit DP: it decides whether the next digit is capped by `N` or free to range 0–9.

---

### Problem 49: Sum of Digits of All Numbers from 1 to N — digit DP with carry

**Statement.** Compute `Σ_{i=1}^{N} digitSum(i)`, the total of all digit sums.

**Approach.** Digit DP carrying two aggregates: `count` of completions and `sum` of digit-sums over completions. At each position add `d · count(of suffixes)` to the running sum.

```java
public class SumOfDigitSums {
    static int[] digits;
    static long[][] memoSum, memoCnt;
    static boolean[][] seen;

    public static long total(long n) {
        if (n < 0) return 0;
        String s = Long.toString(n);
        digits = new int[s.length()];
        for (int i = 0; i < s.length(); i++) digits[i] = s.charAt(i) - '0';
        int len = s.length();
        memoSum = new long[len][2];
        memoCnt = new long[len][2];
        seen = new boolean[len][2];
        return dp(0, 1)[0];
    }
    // Returns {sumOfDigitSums, count} for completions from this state.
    private static long[] dp(int pos, int tight) {
        if (pos == digits.length) return new long[]{0, 1};
        if (seen[pos][tight]) return new long[]{memoSum[pos][tight], memoCnt[pos][tight]};
        int limit = (tight == 1) ? digits[pos] : 9;
        long sum = 0, cnt = 0;
        for (int d = 0; d <= limit; d++) {
            long[] sub = dp(pos + 1, (tight == 1 && d == limit) ? 1 : 0);
            sum += sub[0] + (long) d * sub[1]; // this digit contributes d per completion
            cnt += sub[1];
        }
        seen[pos][tight] = true;
        memoSum[pos][tight] = sum;
        memoCnt[pos][tight] = cnt;
        return new long[]{sum, cnt};
    }
}
```

**Time:** O(len · 10 · 2) · **Space:** O(len · 2)

**Insight.** Carrying both `count` and `sum` lets each digit contribute `d × (number of valid suffixes)` — the standard way digit DP accumulates an additive quantity rather than just counting.

---

### Problem 50: Number of Coprime Pairs ≤ N — Mobius / inclusion–exclusion

**Statement.** Count ordered pairs `(i, j)` with `1 ≤ i, j ≤ N` and `gcd(i, j) = 1`.

**Approach.** By Möbius inversion, `Σ_{d=1}^{N} μ(d) · ⌊N/d⌋²`. Compute the Möbius function with a sieve, then sum.

```java
public class CoprimePairs {
    public static long countCoprimePairs(int n) {
        int[] mu = new int[n + 1];
        boolean[] composite = new boolean[n + 1];
        java.util.List<Integer> primes = new java.util.ArrayList<>();
        mu[1] = 1;
        for (int i = 2; i <= n; i++) {
            if (!composite[i]) { primes.add(i); mu[i] = -1; }
            for (int p : primes) {
                if ((long) i * p > n) break;
                composite[i * p] = true;
                if (i % p == 0) { mu[i * p] = 0; break; } // squared factor
                else mu[i * p] = -mu[i];
            }
        }
        long ans = 0;
        for (int d = 1; d <= n; d++) {
            long q = n / d;
            ans += (long) mu[d] * q * q;
        }
        return ans;
    }
}
```

**Time:** O(n) · **Space:** O(n)

**Insight.** `μ(d)` weights inclusion–exclusion over shared prime divisors; `⌊N/d⌋²` counts pairs both divisible by `d`, and the alternating sum cancels everything but coprime pairs.

---

## ✅ Key Takeaways

- **Always take the modulus inside the loop** — deferring it overflows. With `MOD = 10^9 + 7`, every product of two reduced values fits in a `long` because `(10^9)^2 < Long.MAX_VALUE`.
- **Modular inverse has two routes:** Fermat (`a^(p-2)`) for *prime* moduli, extended Euclid for *any* coprime modulus. Precompute factorial inverses downward (`invFact[i-1] = invFact[i]·i`) to pay only one inverse for a whole table.
- **`O(log n)` exponentiation generalizes:** to `a^n`, to matrices (linear recurrences, Fibonacci in `log n`), and to geometric-series sums. Recognize the squaring structure.
- **Sieves are the gateway** to fast primality, factorization, totient, and Möbius — build the smallest-prime-factor table once and factor in `O(log x)`.
- **Combinatorics modulo p** reduces to precomputed `fact`/`invFact`; Lucas' theorem handles `n` larger than the modulus, stars-and-bars and Catalan numbers reduce to single binomials.
- **Digit DP** counts integers `≤ N` with a property via the `(pos, tight)` state; carry extra aggregates (count, sum) to accumulate, not just count.

## ⚠️ Common Pitfalls

- **Negative modulo:** Java's `%` follows the dividend's sign, so `-1 % m == -1`. Normalize every subtraction with `((x % m) + m) % m`.
- **Overflow in `lcm` and `i*i`:** use `a/gcd*b` (divide first) and make sieve loop variables `long` so `i*i` doesn't wrap near `2^31`.
- **`1 % m` vs `1`:** fast exponentiation must seed `result = 1 % m` to stay correct when `m == 1`.
- **Modular division ≠ integer division:** you must multiply by an inverse; there is no `/` in `ℤ/pℤ`.
- **Fermat needs a prime modulus and `gcd(a,p)=1`** — using it on a composite modulus silently gives wrong answers; fall back to extended Euclid.
- **`mid*mid` overflow** in integer-sqrt binary search: compare `mid <= n/mid` instead, and never trust `Math.sqrt` near perfect squares.
- **CRT can be inconsistent** for non-coprime moduli; the `(aᵢ - a) % gcd != 0` check is mandatory before merging.

## 📚 Further Reading

- *Competitive Programming* (Halim & Halim), ch. 5 "Mathematics" — sieves, modular arithmetic, combinatorics, and the number-theory problem catalog.
- *Concrete Mathematics* (Graham, Knuth, Patashnik) — binomial coefficients, generating functions, and the identities behind Catalan and Möbius.
- cp-algorithms.com — reference implementations for extended Euclid, CRT, Lucas, Miller–Rabin, Möbius, and matrix exponentiation.
- *An Introduction to the Theory of Numbers* (Hardy & Wright) — the classical grounding for Fermat, Euler's totient, and the prime-counting results.
- LeetCode tags "Math" and "Number Theory" — problems 7, 50, 172, 204, 372, 1175, 1808, 2447 reinforce reverse-integer, fast-power, sieve, and coprime-counting patterns above.

---

## 🧩 Extended Problems — Set 1: Deeper internals & edge cases

The base set assumed friendly inputs: a prime modulus, operands under `10^9`, and no adversarial corner cases. Real interviews probe exactly the places those assumptions break. This set drills the internals — `mulmod` when the modulus exceeds `3·10^9`, modular inverse when `gcd(a, m) ≠ 1`, Lucas generalized to prime *powers* (Andrew Granville / Lucas–Andrew), Pollard's rho when trial division is hopeless, and the precise overflow/sign edge cases that separate code that "works on the sample" from code that survives `Long.MIN_VALUE`.

### Problem 51: 128-bit `mulmod` — multiply under a modulus near 2^63 — Math.multiplyHigh

**Statement.** Compute `(a · b) mod m` for `a, b, m` up to `~9.2·10^18`, where `a·b` overflows a 64-bit `long`.

**Approach.** Java 9+ exposes `Math.multiplyHigh(a, b)` (the high 64 bits of the full 128-bit product). Combine high and low halves and reduce by the modulus using `Math.floorMod` on the 128-bit value emulated through `Long.remainderUnsigned` against a long-division of the two halves.

```java
public class MulMod128 {
    // (a*b) mod m for 0 <= a,b < m, m up to ~9.2e18. Requires Java 9+.
    public static long mulmod(long a, long b, long m) {
        long high = Math.multiplyHigh(a, b);     // high 64 bits (signed; a,b >= 0 so fine)
        long low = a * b;                         // low 64 bits (two's-complement wrap)
        // Reduce the 128-bit value [high:low] modulo m by binary long division.
        long rem = 0;
        for (int bit = 127; bit >= 0; bit--) {
            rem = (rem << 1) | ((bit >= 64 ? (high >>> (bit - 64)) : (low >>> bit)) & 1L);
            // rem may have lost its top bit on shift; compare unsigned against m.
            if (Long.compareUnsigned(rem, m) >= 0) rem -= m;
        }
        return rem;
    }
}
```

**Time:** O(1) (128 fixed iterations) · **Space:** O(1)

**Insight.** Once `m > ~3·10^9` the schoolbook `a%m * b%m % m` overflows even a `long`; the bitwise long-division of the 128-bit product is the portable fallback when you cannot reach for `BigInteger` or `__int128`.

---

### Problem 52: Russian-Peasant `mulmod` — addition-only overflow avoidance — binary doubling

**Statement.** Compute `(a · b) mod m` without `Math.multiplyHigh`, using only additions and a modulus that may be up to `~4.6·10^18` (so that `rem + rem` stays within `long`).

**Approach.** Mirror binary exponentiation but with addition: accumulate `a` into the result whenever a bit of `b` is set, doubling `a` each step — every intermediate stays `< 2m`.

```java
public class MulModPeasant {
    public static long mulmod(long a, long b, long m) {
        long result = 0;
        a %= m;
        while (b > 0) {
            if ((b & 1) == 1) result = (result + a) % m;
            a = (a + a) % m;   // doubling; safe while m < Long.MAX_VALUE/2
            b >>= 1;
        }
        return result;
    }
}
```

**Time:** O(log b) · **Space:** O(1)

**Insight.** The constraint is `2m < Long.MAX_VALUE` (about `4.6·10^18`) so that `a + a` and `result + a` never wrap — beyond that you must drop to the 128-bit method of Problem 51.

---

### Problem 53: Modular Inverse when gcd(a, m) ≠ 1 — detect non-invertibility — extended Euclid guard

**Statement.** Given any `a` and `m`, return `a^(-1) mod m` if it exists, or signal that it does not (because `gcd(a, m) > 1`).

**Approach.** Run extended Euclid; the inverse exists *iff* the returned gcd is exactly 1. Normalize the Bézout coefficient into `[0, m)`. This is the safe wrapper that callers forget, then divide by a non-unit and get garbage.

```java
import java.util.OptionalLong;

public class SafeModInverse {
    public static OptionalLong inverse(long a, long m) {
        long a0 = ((a % m) + m) % m;
        long[] r = ExtGcd.extgcd(a0, m);   // {g, x, y}
        if (r[0] != 1) return OptionalLong.empty(); // not coprime → no inverse
        return OptionalLong.of(((r[1] % m) + m) % m);
    }
}
```

**Time:** O(log m) · **Space:** O(log m)

**Insight.** Many "wrong answer" bugs are a silent inverse of a non-unit: `2` has no inverse mod `4`. Returning an `Optional` forces the caller to handle the `gcd > 1` case instead of trusting Fermat blindly.

---

### Problem 54: Division mod a Composite — split off the common factor — generalized modular division

**Statement.** Compute `(a / b) mod m` where the division is exact over the integers (`b | a` as integers), but `b` may share factors with `m` so `b^(-1) mod m` does not exist.

**Approach.** Let `g = gcd(b, m)`. Since `b | a`, also `g | a`. Reduce to `(a/g) · (b/g)^(-1) mod (m/g)` — now `b/g` is coprime to `m/g`, so its inverse exists. Lift back if the full residue mod `m` is needed.

```java
public class DivideModComposite {
    // Assumes b divides a over the integers. Returns (a/b) mod m.
    public static long divide(long a, long b, long m) {
        long g = Gcd.gcd(b, m);
        long mm = m / g;
        long bb = (b / g) % mm;
        long aa = (a / b) % mm;          // a/b is an exact integer by hypothesis
        long inv = SafeModInverse.inverse(bb, mm)
                     .orElseThrow(() -> new ArithmeticException("unexpected non-unit"));
        return ((aa % mm) * (inv % mm) % mm + mm) % mm;
    }
}
```

**Time:** O(log m) · **Space:** O(1)

**Insight.** The trick is that exact integer divisibility lets you cancel `g` from `a`, `b`, and `m` simultaneously — without it, `(a/b) mod m` is genuinely undefined when `gcd(b, m) ∤ a`.

---

### Problem 55: Euler's Theorem Inverse — inverse mod composite without extended Euclid — a^(φ(m)−1)

**Statement.** Compute `a^(-1) mod m` for composite `m` using Euler's theorem instead of extended Euclid, given `gcd(a, m) = 1`.

**Approach.** Euler generalizes Fermat: `a^φ(m) ≡ 1 (mod m)`, so `a^(-1) ≡ a^(φ(m)-1) (mod m)`. Compute `φ(m)` by factorization, then one fast exponentiation.

```java
public class EulerInverse {
    public static long inverse(long a, long m) {
        if (Gcd.gcd(a, m) != 1) throw new ArithmeticException("no inverse");
        long phi = Totient.phi(m);
        return FastPow.power(((a % m) + m) % m, phi - 1, m);
    }
}
```

**Time:** O(√m + log m) · **Space:** O(1)

**Insight.** Fermat is the prime special case (`φ(p) = p - 1`). Euler works for any modulus but costs a factorization to find `φ(m)`; extended Euclid avoids that and is usually preferred.

---

### Problem 56: Tetration mod m — a^a^a … via Euler's theorem with the +φ lift — generalized Euler

**Statement.** Compute a power tower `a ↑↑ k = a^(a^(a^…))` (height `k`) modulo `m`, where the exponent is astronomically large.

**Approach.** Use the *generalized* Euler theorem: for any `a` (even when `gcd(a, m) ≠ 1`), `a^e ≡ a^((e mod φ(m)) + φ(m)) (mod m)` whenever `e ≥ log₂ m`. Recurse on the exponent modulo `φ(m)`, adding the `+φ` lift to stay in the valid branch.

```java
public class Tetration {
    // a^^k mod m
    public static long tetration(long a, long k, long m) {
        if (m == 1) return 0;
        if (k == 0) return 1 % m;
        long phi = Totient.phi(m);
        long e = tetration(a, k - 1, phi);     // exponent mod φ(m)
        // Lift: add φ(m) so we are on the periodic branch (valid since tower height ≥ 2).
        return powLift(a % m, e + phi, m);
    }
    private static long powLift(long base, long exp, long m) {
        long result = 1 % m;
        base %= m;
        while (exp > 0) {
            if ((exp & 1) == 1) result = result * base % m;
            base = base * base % m;
            exp >>= 1;
        }
        return result;
    }
}
```

**Time:** O(log m · log* of the tower) — `φ` reaches 1 in `O(log m)` nests · **Space:** O(log m)

**Insight.** The `+ φ(m)` lift is what makes the reduction valid even when `a` and `m` share factors — plain `e mod φ(m)` is only correct under coprimality. The `φ`-chain `m, φ(m), φ(φ(m)), …` collapses to 1 in logarithmically many steps, bounding recursion depth.

---

### Problem 57: Discrete Logarithm — Baby-Step Giant-Step — meet in the middle

**Statement.** Solve `a^x ≡ b (mod m)` for the smallest non-negative `x`, with `m` prime (or `a` coprime to `m`).

**Approach.** Write `x = i·n − j` with `n = ⌈√m⌉`. Precompute `a^j` for `j ∈ [0, n)` into a hash map (baby steps), then test `b · a^(i·n)`… equivalently search `(a^n)^i` against the table (giant steps).

```java
import java.util.*;

public class BabyStepGiantStep {
    // smallest x >= 0 with a^x ≡ b (mod m), or -1.
    public static long solve(long a, long b, long m) {
        a %= m; b %= m;
        long n = (long) Math.ceil(Math.sqrt(m));
        Map<Long, Long> table = new HashMap<>();
        long cur = b;
        for (long j = 0; j < n; j++) {            // baby steps: b · a^j
            table.putIfAbsent(cur, j);
            cur = cur * a % m;
        }
        long an = FastPow.power(a, n, m);         // giant stride a^n
        long giant = an;
        for (long i = 1; i <= n; i++) {
            Long j = table.get(giant);
            if (j != null) {
                long x = i * n - j;
                if (x >= 0) return x;
            }
            giant = giant * an % m;
        }
        return -1;
    }
}
```

**Time:** O(√m log m) · **Space:** O(√m)

**Insight.** BSGS is the canonical meet-in-the-middle: it trades `O(m)` brute force for `O(√m)` time and space — the same square-root decomposition that powers many "huge exponent" search problems.

---

### Problem 58: Modular Square Root — Tonelli–Shanks — quadratic residues mod p

**Statement.** Find `x` with `x² ≡ n (mod p)` for an odd prime `p`, or report that `n` is a non-residue.

**Approach.** First check the Legendre symbol `n^((p-1)/2)`; if it is `p-1`, no root exists. For `p ≡ 3 (mod 4)` the root is `n^((p+1)/4)`. Otherwise run Tonelli–Shanks, factoring `p-1 = q·2^s` and iteratively reducing the order.

```java
public class TonelliShanks {
    public static long sqrtMod(long n, long p) {
        n %= p;
        if (n == 0) return 0;
        if (FastPow.power(n, (p - 1) / 2, p) != 1) return -1; // non-residue
        if (p % 4 == 3) return FastPow.power(n, (p + 1) / 4, p);
        long q = p - 1; int s = 0;
        while ((q & 1) == 0) { q >>= 1; s++; }
        long z = 2;
        while (FastPow.power(z, (p - 1) / 2, p) != p - 1) z++; // a non-residue
        long c = FastPow.power(z, q, p);
        long r = FastPow.power(n, (q + 1) / 2, p);
        long t = FastPow.power(n, q, p);
        int mm = s;
        while (t != 1) {
            int i = 0; long tt = t;
            while (tt != 1) { tt = tt * tt % p; i++; }
            long b = FastPow.power(c, 1L << (mm - i - 1), p);
            r = r * b % p;
            c = b * b % p;
            t = t * c % p;
            mm = i;
        }
        return r;
    }
}
```

**Time:** O(log² p) expected · **Space:** O(1)

**Insight.** The Legendre symbol `n^((p-1)/2) ∈ {1, p-1}` is the gatekeeper: it costs one exponentiation and tells you whether a root exists before you spend effort finding it. The `p ≡ 3 (mod 4)` fast path covers a large share of real moduli.

---

### Problem 59: Pollard's Rho — factor a 64-bit semiprime — Floyd cycle + Brent

**Statement.** Factor a large composite `n` (up to `~10^18`) where trial division to `√n ≈ 10^9` is too slow.

**Approach.** Pollard's rho with Brent's improvement: iterate `x ← x² + c (mod n)` and watch `gcd(|x − y|, n)` for a non-trivial factor. Combine with Miller–Rabin to peel off primes, recursing on cofactors.

```java
import java.util.*;

public class PollardRho {
    public static long pollard(long n) {
        if (n % 2 == 0) return 2;
        long x = 2, y = 2, c = 1, d = 1;
        Random rng = new Random();
        while (true) {
            x = 2; y = 2; c = 1 + Math.floorMod(rng.nextLong(), n - 1); d = 1;
            while (d == 1) {
                x = f(x, c, n);
                y = f(f(y, c, n), c, n);
                d = Gcd.gcd(Math.abs(x - y), n);
            }
            if (d != n) return d;   // non-trivial factor
        }
    }
    private static long f(long x, long c, long n) {
        return (MulMod128.mulmod(x, x, n) + c) % n;
    }
    public static void factor(long n, Map<Long, Integer> out) {
        if (n == 1) return;
        if (MillerRabin.isPrime(n)) { out.merge(n, 1, Integer::sum); return; }
        long d = pollard(n);
        factor(d, out);
        factor(n / d, out);
    }
}
```

**Time:** O(n^(1/4)) expected per factor · **Space:** O(log n) recursion

**Insight.** Rho finds a factor in `O(n^(1/4))` expected time via the birthday paradox on the pseudo-random sequence — the only practical route for 18-19 digit numbers, and it leans on a correct 128-bit `mulmod` (Problem 51) since `x²` overflows when `n > 3·10^9`.

---

### Problem 60: Lucas for Prime Powers — nCr mod p^k — Granville / Andrew's theorem

**Statement.** Compute `C(n, r) mod p^k` for prime power moduli (where plain Lucas, which needs a prime modulus, fails).

**Approach.** Use the generalized Lucas (Andrew Granville): factor out powers of `p` from the factorials via Legendre's formula, then combine the *unit parts* of the factorials computed mod `p^k` using Wilson-quotient products. Below is the core for the common `k = 2` style via factorial-with-p-removed.

```java
public class LucasPrimePower {
    long p, pk;
    long[] fact;       // i! with factors of p removed, mod p^k, for i in [0, pk)

    public LucasPrimePower(long p, int k) {
        this.p = p;
        this.pk = 1; for (int i = 0; i < k; i++) pk *= p;
        fact = new long[(int) pk];
        fact[0] = 1 % pk;
        for (int i = 1; i < pk; i++)
            fact[i] = (i % p == 0) ? fact[i - 1] : fact[i - 1] * i % pk;
    }
    // exponent of p in n!
    private long legendre(long n) {
        long e = 0; for (long q = p; q <= n; q *= p) e += n / q; return e;
    }
    // product of (i! with p removed) across base-pk blocks — the unit part of n! mod p^k
    private long factUnit(long n) {
        long res = 1;
        while (n > 0) {
            res = res * FastPow.power(fact[(int) (pk - 1)], n / pk, pk) % pk;
            res = res * fact[(int) (n % pk)] % pk;
            n /= p;
        }
        return res;
    }
    public long nCr(long n, long r) {
        if (r < 0 || r > n) return 0;
        long e = legendre(n) - legendre(r) - legendre(n - r);
        if (e >= big()) return 0;                       // p^k | answer
        long num = factUnit(n);
        long den = factUnit(r) * factUnit(n - r) % pk;
        long inv = SafeModInverse.inverse(den, pk).orElseThrow();
        return FastPow.power(p, e, pk) * (num * inv % pk) % pk;
    }
    private int big() { int e = 0; long t = pk; while (t > 1) { t /= p; e++; } return e; }
}
```

**Time:** O(p^k + log n) · **Space:** O(p^k)

**Insight.** Plain Lucas needs a prime modulus; for `p^k` you must track the exponent of `p` separately (Legendre) and invert only the *unit* part, since `p` itself is not invertible mod `p^k`. This is the building block for `nCr mod m` with arbitrary `m` via CRT over its prime-power factors.

---

### Problem 61: nCr mod Arbitrary m — CRT over prime-power factors — composite-modulus binomial

**Statement.** Compute `C(n, r) mod m` for *any* `m` (not necessarily prime or prime power).

**Approach.** Factor `m = ∏ pᵢ^kᵢ`, compute `C(n, r) mod pᵢ^kᵢ` via Problem 60, then recombine with the Chinese Remainder Theorem.

```java
import java.util.*;

public class BinomialAnyMod {
    public static long nCr(long n, long r, long m) {
        Map<Long, Integer> f = TrialFactor.factorize(m); // p -> k
        long[] residues = new long[f.size()];
        long[] mods = new long[f.size()];
        int idx = 0;
        for (Map.Entry<Long, Integer> e : f.entrySet()) {
            LucasPrimePower lpp = new LucasPrimePower(e.getKey(), e.getValue());
            long pk = 1; for (int i = 0; i < e.getValue(); i++) pk *= e.getKey();
            residues[idx] = lpp.nCr(n, r);
            mods[idx] = pk;
            idx++;
        }
        long[] sol = CrtGeneral.solve(residues, mods); // coprime prime powers
        return sol[0];
    }
}
```

**Time:** O(√m + Σ pᵢ^kᵢ) · **Space:** O(max pᵢ^kᵢ)

**Insight.** The distinct prime powers of `m` are pairwise coprime, so CRT recombination is exact — this is the general recipe for "binomial mod a composite that is not `10^9 + 7`," a trap when an interviewer quietly hands you `m = 142857` or `m = 1000`.

---

### Problem 62: Floor-Sum — Σ ⌊(a·i + b)/m⌋ — Euclid-like recursion

**Statement.** Compute `Σ_{i=0}^{n-1} ⌊(a·i + b)/m⌋` in `O(log)` time (the AtCoder Library `floor_sum`), for `n` up to `10^9`.

**Approach.** Reduce `a` and `b` modulo `m`, peeling off their quotient contributions in closed form, then swap roles of the slope and modulus — a Stern–Brocot / continued-fraction style recursion mirroring Euclid's GCD.

```java
public class FloorSum {
    // sum_{i=0}^{n-1} floor((a*i + b) / m)
    public static long floorSum(long n, long m, long a, long b) {
        long ans = 0;
        if (a < 0) { long a2 = ((a % m) + m) % m; ans -= n * (n - 1) / 2 * ((a2 - a) / m); a = a2; }
        if (b < 0) { long b2 = ((b % m) + m) % m; ans -= n * ((b2 - b) / m); b = b2; }
        while (true) {
            if (a >= m) { ans += n * (n - 1) / 2 * (a / m); a %= m; }
            if (b >= m) { ans += n * (b / m); b %= m; }
            long yMax = a * n + b;
            if (yMax < m) break;
            n = yMax / m;
            b = yMax % m;
            long t = m; m = a; a = t;   // swap slope and modulus
        }
        return ans;
    }
}
```

**Time:** O(log max(a, m)) · **Space:** O(1)

**Insight.** Lattice-point counting under a line reduces, like GCD, by alternately taking `a mod m` and swapping — this single primitive answers a whole family of "sum of floors" and "count lattice points below a line" subproblems in logarithmic time.

---

### Problem 63: Stern–Brocot / Continued Fraction — best rational approximation — mediant search

**Statement.** Find the fraction `p/q` with smallest denominator `q ≤ Q` that best approximates a target real `x` (e.g. the continued-fraction convergents of `x`).

**Approach.** Walk the Stern–Brocot tree by repeatedly taking mediants of the bracketing fractions `lo` and `hi`; at each node go left or right depending on whether the mediant under- or over-shoots `x`, batching consecutive same-direction steps via the continued-fraction quotient.

```java
public class SternBrocot {
    // Returns {p, q}: best approximation to num/den with denominator <= Q.
    public static long[] best(long num, long den, long Q) {
        long lp = 0, lq = 1, rp = 1, rq = 0; // 0/1 and 1/0
        long bp = 0, bq = 1;
        while (true) {
            long mp = lp + rp, mq = lq + rq;
            if (mq > Q) break;
            // compare mp/mq with num/den
            long cmp = Long.compare(mp * den, num * mq);
            if (cmp == 0) return new long[]{mp, mq};
            if (cmp < 0) { lp = mp; lq = mq; } else { rp = mp; rq = mq; }
            bp = mp; bq = mq;
        }
        return new long[]{bp, bq};
    }
}
```

**Time:** O(log Q) amortized with quotient batching · **Space:** O(1)

**Insight.** The mediant `(a+c)/(b+d)` of two Farey-adjacent fractions is the unique fraction of least denominator strictly between them — the Stern–Brocot tree is the continued-fraction algorithm in geometric clothing.

---

### Problem 64: Extended Euclid Overflow — Bézout coefficients can overflow long — guarded extgcd

**Statement.** Run extended Euclid on inputs near `10^18` where the intermediate Bézout coefficients `x, y` can themselves overflow `long`, and detect/avoid the wrap.

**Approach.** The coefficients are bounded by `|x| ≤ b/(2g)` and `|y| ≤ a/(2g)`, so they fit in `long` for inputs up to `~9·10^18` *individually* — but the multiply `(a/b)·y1` in the back-substitution can overflow. Use `Math.multiplyExact` to surface it, or compute coefficients only modulo the modulus you ultimately reduce against.

```java
public class ExtGcdSafe {
    // Returns {g, x, y}; throws ArithmeticException on coefficient overflow.
    public static long[] extgcd(long a, long b) {
        long oldR = a, r = b;
        long oldS = 1, s = 0;
        long oldT = 0, t = 1;
        while (r != 0) {
            long q = oldR / r;
            long tmpR = oldR - q * r; oldR = r; r = tmpR;
            long tmpS = Math.subtractExact(oldS, Math.multiplyExact(q, s)); oldS = s; s = tmpS;
            long tmpT = Math.subtractExact(oldT, Math.multiplyExact(q, t)); oldT = t; t = tmpT;
        }
        return new long[]{oldR, oldS, oldT};
    }
}
```

**Time:** O(log min(a, b)) · **Space:** O(1)

**Insight.** Although the *final* Bézout coefficients are bounded, the running products `q·s` can transiently exceed `long` — `Math.multiplyExact` converts a silent wraparound into a catchable error, the correct posture when inputs approach the 64-bit ceiling.

---

### Problem 65: gcd of Long.MIN_VALUE — the unrepresentable absolute value — sign edge case

**Statement.** Compute `gcd(a, b)` correctly when either argument is `Long.MIN_VALUE`, whose absolute value `2^63` is *not* representable as a positive `long`.

**Approach.** `Math.abs(Long.MIN_VALUE)` returns `Long.MIN_VALUE` (still negative!) — the classic trap. Work with unsigned semantics or special-case `MIN_VALUE`: `gcd(MIN, MIN) = MIN`'s magnitude `2^63`, and `gcd(MIN, b)` reduces immediately to `gcd(b, MIN mod b)` where the remainder is safely in range.

```java
public class GcdMinValue {
    public static long gcd(long a, long b) {
        // Reduce using % first — MIN % b is in range for any b != 0, avoiding abs.
        while (b != 0) {
            long t = a % b;   // never overflows: |a % b| < |b| <= Long.MAX_VALUE
            a = b;
            b = t;
        }
        return Math.abs(a) == a ? a : -a; // a may be MIN_VALUE only if both inputs were MIN
    }
}
```

**Time:** O(log min) · **Space:** O(1)

**Insight.** Never call `Math.abs` on a value that might be `Long.MIN_VALUE` — it silently returns the same negative number. Euclid's `%` step sidesteps the issue because a remainder is always strictly smaller in magnitude than the divisor.

---

### Problem 66: Sieve Segmented — primes in [L, R] with R up to 10^12 — block sieve

**Statement.** List all primes in a range `[L, R]` where `R` can be up to `10^12` but `R − L ≤ 10^6`, so a full sieve to `R` is impossible.

**Approach.** Sieve base primes up to `√R` (about `10^6`), then mark composites within the window `[L, R]` by crossing off multiples of each base prime, offset to the first multiple `≥ L`.

```java
import java.util.*;

public class SegmentedSieve {
    public static List<Long> primesInRange(long L, long R) {
        int lim = (int) Math.sqrt(R) + 1;
        boolean[] small = Sieve.sieve(lim);
        boolean[] isComp = new boolean[(int) (R - L + 1)];
        for (int p = 2; p <= lim; p++) {
            if (!small[p]) continue;
            long start = Math.max((long) p * p, ((L + p - 1) / p) * p);
            for (long j = start; j <= R; j += p) isComp[(int) (j - L)] = true;
        }
        List<Long> primes = new ArrayList<>();
        for (long i = Math.max(L, 2); i <= R; i++)
            if (!isComp[(int) (i - L)]) primes.add(i);
        return primes;
    }
}
```

**Time:** O((R − L) log log R + √R) · **Space:** O(R − L + √R)

**Insight.** The window of size `R − L` is what fits in memory; the offset `((L + p − 1)/p)·p` snaps to the first multiple of `p` at or above `L`, and starting at `max(p², …)` avoids re-marking small primes as composite.

---

### Problem 67: Wilson's Theorem Check — (p−1)! ≡ −1 mod p — primality witness

**Statement.** Use Wilson's theorem `(p − 1)! ≡ −1 (mod p)` iff `p` is prime as an (impractically slow but instructive) primality test, and explain why it is not used in practice.

**Approach.** Multiply `1·2·…·(p−1) mod p` and compare with `p − 1`. The point is the `O(p)` cost versus `O(√p)` trial division or `O(log³ p)` Miller–Rabin.

```java
public class WilsonPrimality {
    public static boolean isPrime(long p) {
        if (p < 2) return false;
        long fact = 1;
        for (long i = 2; i < p; i++) fact = MulMod128.mulmod(fact, i, p);
        return fact == p - 1;   // (p-1)! ≡ -1 (mod p)
    }
}
```

**Time:** O(p) · **Space:** O(1)

**Insight.** Wilson's theorem is a perfect *characterization* of primes but a terrible *algorithm* — computing `(p−1)!` is linear in `p`, exponentially slower than the size of `p`'s representation, which is precisely why Miller–Rabin exists.

---

### Problem 68: Carmichael Function λ(n) — the true exponent of the unit group — vs totient

**Statement.** Compute the Carmichael function `λ(n)` — the smallest `e` with `a^e ≡ 1 (mod n)` for all `a` coprime to `n` — which can be strictly smaller than `φ(n)`.

**Approach.** `λ` is the lcm of `λ(pᵢ^kᵢ)` over prime powers, where `λ(p^k) = φ(p^k)` for odd `p` (and for `2, 4`), but `λ(2^k) = 2^(k−2)` for `k ≥ 3`.

```java
import java.util.*;

public class Carmichael {
    public static long lambda(long n) {
        Map<Long, Integer> f = TrialFactor.factorize(n);
        long result = 1;
        for (Map.Entry<Long, Integer> e : f.entrySet()) {
            long p = e.getKey(); int k = e.getValue();
            long lpk;
            if (p == 2 && k >= 3) lpk = 1L << (k - 2);       // 2^(k-2)
            else { long pk = 1; for (int i = 0; i < k; i++) pk *= p; lpk = pk / p * (p - 1); } // φ(p^k)
            result = result / Gcd.gcd(result, lpk) * lpk;     // lcm
        }
        return result;
    }
}
```

**Time:** O(√n) · **Space:** O(log n)

**Insight.** `λ(n) | φ(n)`, and for the special case `n = 2^k (k ≥ 3)` the unit group is *not* cyclic, so `λ` is half of `φ` — using `φ` where `λ` is needed (e.g. RSA key recovery, order computations) overestimates the period.

---

### Problem 69: Multiplicative Order — smallest k with a^k ≡ 1 — divisors of λ(n)

**Statement.** Find the multiplicative order of `a` modulo `n` (with `gcd(a, n) = 1`): the smallest `k > 0` such that `a^k ≡ 1 (mod n)`.

**Approach.** The order divides `λ(n)`. Factor `λ(n)`, then for each prime factor repeatedly divide it out of the candidate exponent while `a^(cand) ≡ 1` still holds — reducing `λ(n)` to the true order.

```java
import java.util.*;

public class MultiplicativeOrder {
    public static long order(long a, long n) {
        if (Gcd.gcd(a, n) != 1) throw new ArithmeticException("not a unit");
        long ord = Carmichael.lambda(n);
        Map<Long, Integer> f = TrialFactor.factorize(ord);
        for (Map.Entry<Long, Integer> e : f.entrySet()) {
            long p = e.getKey();
            for (int i = 0; i < e.getValue(); i++) {
                if (FastPow.power(a, ord / p, n) == 1) ord /= p;
                else break;
            }
        }
        return ord;
    }
}
```

**Time:** O(√λ + (#prime factors)·log) · **Space:** O(log λ)

**Insight.** Starting from `λ(n)` (not `φ(n)`) and stripping prime factors is far faster than incrementing `k`; the order is always a divisor of `λ(n)`, so this divisor-pruning finds it in a handful of exponentiations.

---

### Problem 70: Primitive Root mod p — a generator of the unit group — order = p−1

**Statement.** Find a primitive root modulo a prime `p` — an element whose multiplicative order is exactly `p − 1`.

**Approach.** Factor `p − 1`. A candidate `g` is a primitive root iff `g^((p−1)/q) ≠ 1 (mod p)` for every prime `q | (p − 1)`. Test `g = 2, 3, …` until one passes.

```java
import java.util.*;

public class PrimitiveRoot {
    public static long find(long p) {
        if (p == 2) return 1;
        Map<Long, Integer> f = TrialFactor.factorize(p - 1);
        for (long g = 2; g < p; g++) {
            boolean ok = true;
            for (long q : f.keySet()) {
                if (FastPow.power(g, (p - 1) / q, p) == 1) { ok = false; break; }
            }
            if (ok) return g;
        }
        return -1;
    }
}
```

**Time:** O(g·ω(p−1)·log p), `g` small on average · **Space:** O(log p)

**Insight.** Checking only the maximal proper divisors `(p−1)/q` (one per prime factor `q`) suffices — if `g` had order `< p−1`, that order would divide some `(p−1)/q`. The smallest primitive root is heuristically tiny, so the linear scan terminates fast.

---

### Problem 71: Sum of Two Squares — Fermat's theorem on sums — p ≡ 1 mod 4

**Statement.** Determine whether `n` can be written as `a² + b²`, and if so produce one such representation.

**Approach.** By Fermat's two-square theorem, `n` is a sum of two squares iff every prime `≡ 3 (mod 4)` in its factorization appears to an even power. To construct, find a square root of `−1` mod each `p ≡ 1 (mod 4)` (Tonelli–Shanks) and apply a Gaussian-integer gcd / Cornacchia descent.

```java
public class SumOfTwoSquares {
    // Cornacchia for a single prime p ≡ 1 (mod 4): returns {a, b} with a²+b² = p.
    public static long[] forPrime(long p) {
        if (p == 2) return new long[]{1, 1};
        long x = TonelliShanks.sqrtMod(p - 1, p); // sqrt(-1) mod p
        long a = p, b = x;
        long limit = (long) Math.sqrt(p);
        while (b > limit) { long r = a % b; a = b; b = r; } // Euclid descent
        long c = p - b * b;
        long s = (long) Math.sqrt(c);
        if (s * s == c) return new long[]{b, s};
        return null; // shouldn't happen for valid p
    }
}
```

**Time:** O(log² p) · **Space:** O(1)

**Insight.** The deep fact is that `ℤ[i]` is a unique-factorization domain: a prime `p ≡ 1 (mod 4)` splits as `(a + bi)(a − bi)`, and Cornacchia's algorithm is just a Euclidean descent in the Gaussian integers driven by the `sqrt(−1)` you obtain from Tonelli–Shanks.

---

### Problem 72: Möbius via Linear Sieve — μ(i) for all i ≤ n — multiplicative sieve

**Statement.** Compute the Möbius function `μ(i)` for every `i ≤ n` in linear time, with the smallest-prime-factor invariant kept correct.

**Approach.** Extend the linear sieve: `μ(1) = 1`; for a prime `p`, `μ(p) = −1`; when extending `i·p`, set `μ(i·p) = 0` if `p | i` (squared factor), else `μ(i·p) = −μ(i)`.

```java
import java.util.*;

public class MobiusSieve {
    public static int[] mobius(int n) {
        int[] mu = new int[n + 1];
        boolean[] comp = new boolean[n + 1];
        List<Integer> primes = new ArrayList<>();
        mu[1] = 1;
        for (int i = 2; i <= n; i++) {
            if (!comp[i]) { primes.add(i); mu[i] = -1; }
            for (int p : primes) {
                if ((long) i * p > n) break;
                comp[i * p] = true;
                if (i % p == 0) { mu[i * p] = 0; break; }
                mu[i * p] = -mu[i];
            }
        }
        return mu;
    }
}
```

**Time:** O(n) · **Space:** O(n)

**Insight.** The `break` after setting `μ(i·p) = 0` when `p | i` is the same linear-sieve invariant as the SPF sieve — it guarantees each integer is processed by its smallest prime factor exactly once, giving true `O(n)`.

---

### Problem 73: Dirichlet Convolution Prefix — Σ φ(i) up to N — hyperbola / Mertens-style

**Statement.** Compute `Φ(N) = Σ_{i=1}^{N} φ(i)` for `N` up to `~10^11`, too large to sieve.

**Approach.** Use the identity `Σ_{i≤N} φ(i) = ½(1 + Σ_{i≤N} μ?... )` — concretely, `Σ_{d=1}^{N} φ(d)·⌊N/d⌋`-style recursion: `Φ(N) = N(N+1)/2 − Σ_{d=2}^{N} Φ(⌊N/d⌋)`, evaluated with the `⌊N/d⌋` block trick and memoized on the `O(√N)` distinct quotients.

```java
import java.util.*;

public class TotientSummatory {
    static long N;
    static int[] smallPhiPrefix;   // prefix sums of φ for i ≤ threshold
    static Map<Long, Long> memo = new HashMap<>();
    static int threshold;

    public static long phiSum(long n) {
        if (n <= threshold) return smallPhiPrefix[(int) n];
        if (memo.containsKey(n)) return memo.get(n);
        long res = n % 2 == 0 ? (n / 2) % MOD * ((n + 1) % MOD) : n % MOD * (((n + 1) / 2) % MOD);
        for (long l = 2, r; l <= n; l = r + 1) {
            long q = n / l;
            r = n / q;
            res -= (r - l + 1) % MOD * phiSum(q) % MOD;
            res = ((res % MOD) + MOD) % MOD;
        }
        memo.put(n, res);
        return res;
    }
    static final long MOD = 1_000_000_007L;
}
```

**Time:** O(N^(2/3)) with the standard threshold `N^(2/3)` · **Space:** O(N^(2/3))

**Insight.** The recursion only ever recurses on values of the form `⌊N/k⌋`, of which there are `O(√N)`; precomputing `φ` prefixes up to `N^(2/3)` and memoizing the rest gives the sub-linear `O(N^(2/3))` that underlies "sum a multiplicative function to `10^11`" problems.

---

### Problem 74: Fast nCr by Direct Product — single query without precompute — running fraction

**Statement.** Compute one `C(n, r) mod p` when `n` is up to `10^9` but you cannot afford an `O(n)` factorial table (only a single query).

**Approach.** `C(n, r) = ∏_{i=1}^{r} (n − r + i) / i`, computed as a modular product: multiply by `(n − r + i)` and by `i^(-1)`. Take `r = min(r, n − r)` to bound the loop.

```java
public class BinomialDirect {
    static final long MOD = 1_000_000_007L;
    public static long nCr(long n, long r) {
        if (r < 0 || r > n) return 0;
        r = Math.min(r, n - r);
        long num = 1, den = 1;
        for (long i = 1; i <= r; i++) {
            num = num % MOD * (((n - r + i) % MOD)) % MOD;
            den = den * (i % MOD) % MOD;
        }
        return num * FastPow.power(den, MOD - 2, MOD) % MOD;
    }
}
```

**Time:** O(r + log p) · **Space:** O(1)

**Insight.** `r = min(r, n − r)` exploits the symmetry `C(n, r) = C(n, n − r)` to cap the loop at `n/2`; accumulating one numerator and one denominator means a *single* modular inverse rather than `r` of them.

---

### Problem 75: Modular Factorial of a Huge n mod p — Wilson reduction when n ≥ p — it's just 0

**Statement.** Compute `n! mod p` for prime `p` when `n` can be `≥ p`, the trap that breaks naive factorial-mod code.

**Approach.** If `n ≥ p`, then `p` is one of the factors `1·2·…·n`, so `n! ≡ 0 (mod p)`. Only when `n < p` do you actually multiply. (For the "remove all factors of p" variant you'd use Wilson's theorem on each block.)

```java
public class FactorialModHuge {
    public static long factorial(long n, long p) {
        if (n >= p) return 0;            // p divides n! once n reaches p
        long result = 1 % p;
        for (long i = 2; i <= n; i++) result = result * i % p;
        return result;
    }
}
```

**Time:** O(min(n, p)) · **Space:** O(1)

**Insight.** The single most common factorial-mod bug: forgetting that `n! ≡ 0 (mod p)` for all `n ≥ p`. Looping `i` up to a huge `n` is both wrong-spirited and pointless — the answer collapsed to 0 the moment `i` hit `p`.

---

### Problem 76: GCD of Fibonacci Numbers — gcd(F(m), F(n)) = F(gcd(m, n)) — divisibility identity

**Statement.** Compute `gcd(F(m), F(n)) mod p` for huge `m, n` using the divisibility structure of the Fibonacci sequence.

**Approach.** The Fibonacci sequence is a *strong divisibility sequence*: `gcd(F(m), F(n)) = F(gcd(m, n))`. Reduce the indices by Euclid first, then compute a single Fibonacci by matrix exponentiation.

```java
public class FibGcd {
    public static long fibGcd(long m, long n) {
        long g = Gcd.gcd(m, n);   // index gcd
        return FibMatrix.fib(g);  // F(gcd(m, n)) mod p
    }
}
```

**Time:** O(log(max(m, n))) · **Space:** O(1)

**Insight.** Recognizing the strong-divisibility identity converts a hopeless "gcd of two `10^18`-th Fibonacci numbers" into one Euclid on the *indices* plus one `O(log)` matrix power — the algebraic structure does all the work.

---

### Problem 77: Josephus Position — O(n) recurrence and O(k log n) jump — survivor index

**Statement.** Find the surviving position in the Josephus problem: `n` people in a circle, every `k`-th eliminated.

**Approach.** The linear recurrence `J(1) = 0`, `J(i) = (J(i − 1) + k) mod i` gives the 0-indexed survivor in `O(n)`. For small `k` and huge `n`, a faster `O(k log n)` recurrence skips whole rounds.

```java
public class Josephus {
    public static int survivor(int n, int k) {
        int res = 0;
        for (int i = 2; i <= n; i++) res = (res + k) % i;
        return res;   // 0-indexed
    }
    // O(k log n) for small k, huge n.
    public static long survivorFast(long n, long k) {
        if (n == 1) return 0;
        if (k == 1) return n - 1;
        if (k > n) return (survivorFast(n - 1, k) + k) % n;
        long res = survivorFast(n - n / k, k);
        res -= n % k;
        if (res < 0) res += n;          // wrap into the circle
        else res += res / (k - 1);      // re-expand skipped block
        return res;
    }
}
```

**Time:** O(n) or O(k log n) · **Space:** O(1) iterative / O(log n) recursive

**Insight.** The `+k mod i` recurrence reframes each elimination as a coordinate shift; the fast variant collapses a whole sweep of `⌊n/k⌋` eliminations into one step, the only way to handle `n = 10^18` with small `k`.

---

### Problem 78: Integer nth Root — ⌊n^(1/k)⌋ without floating point — binary search + safe pow

**Statement.** Compute `⌊n^(1/k)⌋` exactly for `n` up to `10^18` and `k ≥ 2`, avoiding `Math.pow` rounding errors.

**Approach.** Binary search the root `x` in `[0, …]`; test `x^k ≤ n` using overflow-safe exponentiation that short-circuits the moment the partial product exceeds `n`.

```java
public class IntNthRoot {
    public static long nthRoot(long n, int k) {
        if (n < 0) throw new IllegalArgumentException();
        long lo = 0, hi = (long) Math.pow(n, 1.0 / k) + 2, ans = 0;
        while (lo <= hi) {
            long mid = lo + (hi - lo) / 2;
            if (powLE(mid, k, n)) { ans = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        return ans;
    }
    // true iff mid^k <= n, computed without overflow.
    private static boolean powLE(long base, int k, long n) {
        long result = 1;
        for (int i = 0; i < k; i++) {
            if (base != 0 && result > n / base) return false; // would exceed n
            result *= base;
        }
        return result <= n;
    }
}
```

**Time:** O(log n · k) · **Space:** O(1)

**Insight.** Seeding `hi` from `Math.pow` then *correcting* by binary search gives both speed and exactness; the `result > n/base` early exit prevents the `result *= base` overflow that would otherwise corrupt the comparison.

---

### Problem 79: Negative Base Conversion — representing integers in base −2 — non-standard radix

**Statement.** Convert an integer (possibly negative) to its representation in a negative base `b` (e.g. base `−2`), where no sign or extra digit is needed.

**Approach.** Repeatedly take `n mod b`; if the remainder is negative, add `|b|` and increment the quotient to compensate. Digits are emitted least-significant first.

```java
public class NegativeBase {
    public static String toNegativeBase(long n, int b) { // b < 0
        if (n == 0) return "0";
        StringBuilder sb = new StringBuilder();
        while (n != 0) {
            long rem = n % b;
            n /= b;
            if (rem < 0) { rem -= b; n += 1; }  // b is negative, so -b > 0
            sb.append(rem);
        }
        return sb.reverse().toString();
    }
}
```

**Time:** O(log|n|) · **Space:** O(log|n|)

**Insight.** Negative bases encode sign in the digit *pattern* alone — every integer has a unique representation with digits in `[0, |b|)` and no leading minus, because the alternating sign of `b^k` covers both halves of the number line.

---

### Problem 80: Bitwise GCD (Binary GCD / Stein's algorithm) — no division — shifts and subtraction

**Statement.** Compute `gcd(a, b)` using only subtraction, comparison, and bit shifts (no `%` or `/`), which can be faster on hardware without cheap division.

**Approach.** Stein's algorithm: factor out common powers of 2, then repeatedly remove factors of 2 from the odd-even cases and subtract the smaller from the larger.

```java
public class BinaryGcd {
    public static long gcd(long a, long b) {
        if (a == 0) return b;
        if (b == 0) return a;
        int shift = Long.numberOfTrailingZeros(a | b); // common factors of 2
        a >>= Long.numberOfTrailingZeros(a);
        do {
            b >>= Long.numberOfTrailingZeros(b);
            if (a > b) { long t = a; a = b; b = t; }    // ensure a <= b
            b -= a;
        } while (b != 0);
        return a << shift;
    }
}
```

**Time:** O(log² max(a, b)) bit operations · **Space:** O(1)

**Insight.** Stein's algorithm replaces Euclid's division with shifts (`numberOfTrailingZeros`) and subtraction — the same asymptotic step count but using only operations that are single-cycle even on minimal hardware, and it never touches `Long.MIN_VALUE`'s abs trap.

---

### Problem 81: Count Set Bits 0..N — total popcount over a range — bit-position contribution

**Statement.** Compute `Σ_{i=0}^{N} popcount(i)`, the total number of set bits across all integers from 0 to `N`.

**Approach.** Sum each bit position's contribution independently. For bit `k` (value `2^k`), the pattern of that bit over `0..N` repeats every `2^(k+1)`: full cycles contribute `2^k` ones each, plus a partial remainder.

```java
public class CountBitsUpToN {
    public static long totalSetBits(long n) {
        long total = 0;
        for (int k = 0; k < 63; k++) {
            long block = 1L << (k + 1);
            long full = (n + 1) / block;        // complete cycles
            total += full * (1L << k);
            long rem = (n + 1) % block;          // leftover in the partial cycle
            total += Math.max(0, rem - (1L << k));
        }
        return total;
    }
}
```

**Time:** O(log N) · **Space:** O(1)

**Insight.** Decomposing by bit position turns an `O(N)` popcount loop into `O(log N)`: each bit toggles on a fixed period, so its total contribution is a closed-form count of full plus partial cycles — the same digit-DP-by-position idea applied to base 2.

---

### Problem 82: Modular Geometric Series with r ≡ 1 — the singular case — avoid 1/(r−1)

**Statement.** Compute `1 + r + r² + … + r^(n−1) mod p` robustly, including the case `r ≡ 1 (mod p)` where the closed form `(r^n − 1)/(r − 1)` divides by zero.

**Approach.** Detect `r ≡ 1` and return `n mod p` directly; otherwise use the modular inverse of `(r − 1)`. This is the closed-form companion to the divide-and-conquer recursion of Problem 34.

```java
public class GeoSeriesClosed {
    static final long MOD = 1_000_000_007L;
    public static long sum(long r, long n) {
        r %= MOD;
        if (r == 1) return n % MOD;                       // singular case: 1+1+...+1 = n
        long num = (FastPow.power(r, n, MOD) - 1 + MOD) % MOD;
        long invDen = FastPow.power((r - 1 + MOD) % MOD, MOD - 2, MOD);
        return num * invDen % MOD;
    }
}
```

**Time:** O(log n) · **Space:** O(1)

**Insight.** The closed form is `O(log n)` (one inverse) versus the `O(log² n)` divide-and-conquer of Problem 34 — but it is only valid for `r ≢ 1`. The explicit `r == 1` guard is the edge case that silently produces a division-by-zero (inverse of 0) otherwise.

---

## 🧩 Extended Problems — Set 2: Hard variations & follow-ups

Set 1 hardened the *primitives*; this set asks the harder *combinations* interviewers reach for once you've proven you can compute a modular inverse. The lens here is "you have the toolbox — now compose it under adversarial constraints": polynomial arithmetic that needs NTT, matrix recurrences with an inhomogeneous term, lattice-point and divisor-summatory tricks that beat the naive `O(N)`, continued-fraction machinery (Pell, modular continued fractions), and the gnarly sign/precision edge cases (`__int128`-free `mulmod`, exact rational comparison, `BigInteger` fallbacks). Each problem is a *variation* on or *follow-up* to something earlier — Lucas → factorial-prime-power, Fibonacci → Pisano period, geometric series → matrix geometric series — and several are the "and now do it for `10^18`" escalation that turns a warm-up into a screen-out.

### Problem 83: Pisano Period — Fibonacci mod m repeats — period of F(n) mod m

**Statement.** Find the Pisano period `π(m)`: the length of the cycle in which `F(n) mod m` repeats, so that `F(huge) mod m` reduces to `F(huge mod π(m)) mod m`.

**Approach.** The sequence of consecutive pairs `(F(n), F(n+1)) mod m` must repeat (only `m²` states); the period starts at `(0, 1)`. Detect the return to `(0, 1)` by iterating, or factor `m` and lcm the prime-power periods for huge `m`.

```java
public class PisanoPeriod {
    public static long period(long m) {
        long prev = 0, cur = 1;
        for (long i = 0; i < m * m * 6L; i++) {  // π(m) ≤ 6m, loose bound
            long next = (prev + cur) % m;
            prev = cur; cur = next;
            if (prev == 0 && cur == 1) return i + 1;
        }
        return -1; // unreachable for valid m
    }
    public static long fib(long n, long m) {
        long p = period(m);
        return FibMatrix.fib(n % p) % m; // reduce index by the period
    }
}
```

**Time:** O(π(m)) ≤ O(m) to find the period · **Space:** O(1)

**Insight.** `π(m) ≤ 6m` (with equality only for `m = 2·5^k`), so the pair `(F(n), F(n+1))` cycling is guaranteed; reducing the index modulo `π(m)` is what lets you answer `F(10^18) mod m` with a tiny modulus even faster than matrix exponentiation's `O(log n)`.

---

### Problem 84: Inhomogeneous Linear Recurrence — f(n)=A·f(n−1)+c — augmented matrix

**Statement.** Compute `f(n) mod p` for a recurrence with a constant term: `f(n) = a·f(n−1) + b·f(n−2) + c`, for huge `n`.

**Approach.** Fold the constant `c` into the state vector by augmenting the transition matrix with an extra row/column that carries a permanent `1`. The `(k+1)×(k+1)` matrix then handles the affine term inside ordinary matrix exponentiation.

```java
public class AffineRecurrence {
    static final long MOD = 1_000_000_007L;
    // f(n) = a*f(n-1) + b*f(n-2) + c, seeds f0, f1.
    public static long compute(long a, long b, long c, long f0, long f1, long n) {
        if (n == 0) return ((f0 % MOD) + MOD) % MOD;
        if (n == 1) return ((f1 % MOD) + MOD) % MOD;
        // State [f(n), f(n-1), 1]; last row keeps the constant alive.
        long[][] m = {
            {a % MOD, b % MOD, c % MOD},
            {1, 0, 0},
            {0, 0, 1}
        };
        long[][] mp = LinearRecurrence.pow(m, n - 1, 3);
        long r = (mp[0][0] * (f1 % MOD) + mp[0][1] * (f0 % MOD) + mp[0][2]) % MOD;
        return (r + MOD) % MOD;
    }
}
```

**Time:** O(k³ log n) · **Space:** O(k²)

**Insight.** The trick for any *affine* (not purely linear) recurrence is the permanent `1` slot: it turns the additive constant `c` into a matrix entry, so the whole machine stays a single matrix power instead of needing a separate particular-plus-homogeneous solution.

---

### Problem 85: Matrix Geometric Series — Σ Aⁱ — block-matrix doubling

**Statement.** Compute `I + A + A² + … + A^(n−1) mod p` for a `k×k` matrix `A` — the matrix analog of Problem 34.

**Approach.** Embed `A` and the identity in a `2k×2k` block matrix `[[A, I], [0, I]]`; its `n`-th power has the running sum `Σ Aⁱ` in the top-right block. One matrix exponentiation yields the series.

```java
public class MatrixGeoSeries {
    static final long MOD = 1_000_000_007L;
    // Returns S = I + A + ... + A^(n-1), A is k x k.
    public static long[][] series(long[][] A, int k, long n) {
        int K = 2 * k;
        long[][] big = new long[K][K];
        for (int i = 0; i < k; i++)
            for (int j = 0; j < k; j++) big[i][j] = A[i][j] % MOD;
        for (int i = 0; i < k; i++) { big[i][k + i] = 1; big[k + i][k + i] = 1; }
        long[][] p = LinearRecurrence.pow(big, n, K);
        long[][] S = new long[k][k];
        for (int i = 0; i < k; i++)
            for (int j = 0; j < k; j++) S[i][j] = p[i][k + j];
        return S;
    }
}
```

**Time:** O((2k)³ log n) · **Space:** O(k²)

**Insight.** The `[[A, I], [0, I]]` block raised to `n` gives `[[Aⁿ, Σ Aⁱ], [0, I]]` — the same telescoping that makes the scalar geometric-series doubling work, lifted to matrices so you never invert `(A − I)` (which may be singular).

---

### Problem 86: Kitamasa / Polynomial Mod for Linear Recurrence — O(k² log n) recurrence

**Statement.** Compute the `n`-th term of an order-`k` linear recurrence faster than the `O(k³ log n)` matrix power, using polynomial remainders.

**Approach.** Represent `x^n mod (characteristic polynomial)` by repeated squaring of polynomials, reducing modulo the degree-`k` characteristic polynomial after each multiply (Kitamasa's method). Combine the resulting coefficients with the seed values.

```java
public class Kitamasa {
    static final long MOD = 1_000_000_007L;
    // c[]: recurrence f(n) = sum c[i]*f(n-1-i); init[]: f(0..k-1).
    public static long kthTerm(long[] c, long[] init, long n) {
        int k = c.length;
        long[] result = {1};            // polynomial "1"
        long[] base = {0, 1};           // polynomial "x"
        while (n > 0) {
            if ((n & 1) == 1) result = reduce(mul(result, base), c);
            base = reduce(mul(base, base), c);
            n >>= 1;
        }
        long ans = 0;
        for (int i = 0; i < result.length && i < k; i++)
            ans = (ans + result[i] * (init[i] % MOD)) % MOD;
        return ans;
    }
    static long[] mul(long[] a, long[] b) {
        long[] r = new long[a.length + b.length - 1];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < b.length; j++)
                r[i + j] = (r[i + j] + a[i] * b[j]) % MOD;
        return r;
    }
    // reduce poly mod (x^k - c0 x^{k-1} - ... ), i.e. apply the recurrence to high terms.
    static long[] reduce(long[] p, long[] c) {
        int k = c.length;
        for (int i = p.length - 1; i >= k; i--) {
            long coef = p[i];
            if (coef == 0) continue;
            p[i] = 0;
            for (int j = 0; j < k; j++)
                p[i - 1 - j] = (p[i - 1 - j] + coef * c[j]) % MOD;
        }
        long[] res = new long[Math.min(k, p.length)];
        System.arraycopy(p, 0, res, 0, res.length);
        return res;
    }
}
```

**Time:** O(k² log n) (O(k log k log n) with NTT multiply) · **Space:** O(k)

**Insight.** Kitamasa reframes "advance a recurrence `n` steps" as "compute `xⁿ` in the quotient ring `F_p[x]/(charpoly)`": the degree-`k` reduction *is* the recurrence, so polynomial squaring replaces the `O(k³)` matrix multiply with an `O(k²)` (or NTT-`O(k log k)`) one.

---

### Problem 87: NTT — multiply polynomials mod 998244353 — number-theoretic transform

**Statement.** Multiply two polynomials of degree up to `10^5` modulo the NTT-friendly prime `998244353`, in `O(n log n)`.

**Approach.** `998244353 = 119·2²³ + 1` has a primitive `2²³`-th root of unity (3 is a generator), so the FFT runs entirely in modular integers — no floating-point error. Transform both polynomials, multiply pointwise, inverse-transform.

```java
public class NTT {
    static final long MOD = 998244353L, G = 3;
    public static void ntt(long[] a, boolean invert) {
        int n = a.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { long t = a[i]; a[i] = a[j]; a[j] = t; }
        }
        for (int len = 2; len <= n; len <<= 1) {
            long w = FastPow.power(G, (MOD - 1) / len, MOD);
            if (invert) w = FastPow.power(w, MOD - 2, MOD);
            for (int i = 0; i < n; i += len) {
                long wn = 1;
                for (int k = 0; k < len / 2; k++) {
                    long u = a[i + k], v = a[i + k + len / 2] * wn % MOD;
                    a[i + k] = (u + v) % MOD;
                    a[i + k + len / 2] = (u - v + MOD) % MOD;
                    wn = wn * w % MOD;
                }
            }
        }
        if (invert) {
            long inv = FastPow.power(n, MOD - 2, MOD);
            for (int i = 0; i < n; i++) a[i] = a[i] * inv % MOD;
        }
    }
    public static long[] multiply(long[] a, long[] b) {
        int sz = 1; while (sz < a.length + b.length) sz <<= 1;
        long[] fa = java.util.Arrays.copyOf(a, sz), fb = java.util.Arrays.copyOf(b, sz);
        ntt(fa, false); ntt(fb, false);
        for (int i = 0; i < sz; i++) fa[i] = fa[i] * fb[i] % MOD;
        ntt(fa, true);
        return fa;
    }
}
```

**Time:** O(n log n) · **Space:** O(n)

**Insight.** NTT is FFT with roots of unity drawn from `Z/pZ` instead of `ℂ`, so it is *exact* — the prime `998244353` is chosen precisely because `p − 1` has a large power-of-two factor, guaranteeing roots of unity for every transform size up to `2²³`.

---

### Problem 88: Polynomial Inverse mod xⁿ — Newton iteration — formal power series

**Statement.** Given a power series `A(x)` with `A(0) ≠ 0`, compute `B(x) ≡ A(x)^(−1) (mod xⁿ)` — the multiplicative inverse truncated to `n` terms.

**Approach.** Newton's iteration doubles the precision each step: if `B` is correct mod `x^k`, then `B' = B·(2 − A·B) mod x^(2k)` is correct mod `x^(2k)`. Each step is two NTT multiplies.

```java
import java.util.Arrays;

public class PolyInverse {
    static final long MOD = 998244353L;
    public static long[] inverse(long[] a, int n) {
        long[] b = { FastPow.power(a[0], MOD - 2, MOD) }; // B mod x^1
        int len = 1;
        while (len < n) {
            len <<= 1;
            long[] aTrunc = Arrays.copyOf(a, Math.min(a.length, len));
            long[] ab = NTT.multiply(aTrunc, b);          // A*B
            ab = Arrays.copyOf(ab, len);
            for (int i = 0; i < len; i++) ab[i] = (MOD - ab[i]) % MOD;
            ab[0] = (ab[0] + 2) % MOD;                     // 2 - A*B
            long[] nb = NTT.multiply(b, ab);
            b = Arrays.copyOf(nb, len);
        }
        return Arrays.copyOf(b, n);
    }
}
```

**Time:** O(n log n) (geometric sum of doubling NTTs) · **Space:** O(n)

**Insight.** Newton iteration on power series converges *quadratically* in the number of correct coefficients, so the total cost is dominated by the final `O(n log n)` multiply — the same doubling principle underlies series log, exp, sqrt, and division.

---

### Problem 89: Subset-Sum Count via Generating Functions — product of (1 + x^aᵢ) — polynomial DP

**Statement.** Count, modulo `p`, the number of subsets of a multiset that sum to each target `t ≤ S`.

**Approach.** Each item `aᵢ` contributes a factor `(1 + x^{aᵢ})`; the coefficient of `x^t` in the product is the number of subsets summing to `t`. Multiply the factors (knapsack-style DP, or NTT-batch identical weights).

```java
public class SubsetSumGF {
    static final long MOD = 1_000_000_007L;
    public static long[] counts(int[] items, int S) {
        long[] dp = new long[S + 1];
        dp[0] = 1;
        for (int a : items)
            for (int t = S; t >= a; t--)       // 0/1 knapsack order
                dp[t] = (dp[t] + dp[t - a]) % MOD;
        return dp; // dp[t] = #subsets summing to t
    }
}
```

**Time:** O(n·S) · **Space:** O(S)

**Insight.** The generating-function viewpoint `∏(1 + x^{aᵢ})` *is* the knapsack DP — reading the recurrence as polynomial multiplication explains why the reverse-iteration order enforces "use each item at most once," and licenses the NTT speed-up when many weights coincide.

---

### Problem 90: Partition Function p(n) — pentagonal number recurrence — Euler's pentagonal theorem

**Statement.** Compute `p(n)`, the number of integer partitions of `n`, modulo `p`, for `n` up to `~10^5`.

**Approach.** Euler's pentagonal number theorem gives the sparse recurrence `p(n) = Σ_k (−1)^(k−1) [p(n − g_k) + p(n − g_{k+}) ]` over generalized pentagonal numbers `g_k = k(3k−1)/2`, of which only `O(√n)` are `≤ n`.

```java
public class PartitionFunction {
    static final long MOD = 1_000_000_007L;
    public static long[] partitions(int n) {
        long[] p = new long[n + 1];
        p[0] = 1;
        for (int i = 1; i <= n; i++) {
            long sum = 0;
            for (int k = 1; ; k++) {
                int g1 = k * (3 * k - 1) / 2;  // pentagonal
                int g2 = k * (3 * k + 1) / 2;
                if (g1 > i && g2 > i) break;
                long sign = (k % 2 == 1) ? 1 : -1;
                if (g1 <= i) sum += sign * p[i - g1];
                if (g2 <= i) sum += sign * p[i - g2];
                sum %= MOD;
            }
            p[i] = ((sum % MOD) + MOD) % MOD;
        }
        return p;
    }
}
```

**Time:** O(n√n) · **Space:** O(n)

**Insight.** The pentagonal theorem turns a dense `O(n²)` partition DP into `O(n√n)` because the infinite product `∏ (1 − x^k)` has almost all coefficients zero — only the pentagonal exponents survive, a striking instance of generating-function sparsity.

---

### Problem 91: Divisor Summatory Function D(n) — Σ d(i) — hyperbola method

**Statement.** Compute `D(n) = Σ_{i=1}^{n} d(i)` (the total number of divisors up to `n`) for `n` up to `~10^12`, far beyond a per-`i` factorization.

**Approach.** `D(n) = Σ_{i=1}^{n} ⌊n/i⌋`, and by the hyperbola (Dirichlet) method this equals `2·Σ_{i=1}^{⌊√n⌋} ⌊n/i⌋ − ⌊√n⌋²`, exploiting the symmetry of the lattice region under `xy ≤ n`.

```java
public class DivisorSummatory {
    public static long D(long n) {
        long s = (long) Math.sqrt((double) n);
        while ((s + 1) * (s + 1) <= n) s++;     // exact floor sqrt
        while (s * s > n) s--;
        long sum = 0;
        for (long i = 1; i <= s; i++) sum += n / i;
        return 2 * sum - s * s;                  // hyperbola symmetry
    }
}
```

**Time:** O(√n) · **Space:** O(1)

**Insight.** Counting lattice points under the hyperbola `xy ≤ n` only requires iterating to `√n` because the region is symmetric across `y = x`; subtracting the over-counted `√n × √n` square corrects the double count — the canonical `O(√n)` divisor-sum trick.

---

### Problem 92: Mertens Function M(n) — Σ μ(i) — sub-linear sieve with memoization

**Statement.** Compute `M(n) = Σ_{i=1}^{n} μ(i)` for `n` up to `~10^11`, where a full Möbius sieve is impossible.

**Approach.** From `Σ_{d|n} μ(d) = [n = 1]`, derive `M(n) = 1 − Σ_{i=2}^{n} M(⌊n/i⌋)`, evaluated with the `⌊n/i⌋` block grouping and memoized over the `O(√n)` distinct quotients (precompute small `M` by linear sieve).

```java
import java.util.*;

public class Mertens {
    static int threshold;
    static long[] smallM;                 // prefix sums of μ for i ≤ threshold
    static Map<Long, Long> memo = new HashMap<>();

    public static long M(long n) {
        if (n <= threshold) return smallM[(int) n];
        Long cached = memo.get(n);
        if (cached != null) return cached;
        long res = 1;
        for (long l = 2, r; l <= n; l = r + 1) {
            long q = n / l;
            r = n / q;
            res -= (r - l + 1) * M(q);
        }
        memo.put(n, res);
        return res;
    }
}
```

**Time:** O(n^(2/3)) with threshold `n^(2/3)` · **Space:** O(n^(2/3))

**Insight.** The same Dirichlet-hyperbola + memoization scaffold that summed `φ` (Problem 73) sums *any* function whose Dirichlet inverse is simple; for `μ` the inverse is the constant `1`, giving the clean `M(n) = 1 − Σ M(⌊n/i⌋)` recursion.

---

### Problem 93: Sum of GCDs Σ gcd(i, n) — Euler-totient weighted divisors — multiplicative identity

**Statement.** Compute `Σ_{i=1}^{n} gcd(i, n)` for a single `n`, in `O(√n)` after factorization.

**Approach.** Group by `g = gcd(i, n)`: exactly `φ(n/g)` values of `i ≤ n` have `gcd(i, n) = g`. Hence `Σ = Σ_{d | n} d · φ(n/d)`. Enumerate divisors from the factorization.

```java
import java.util.*;

public class SumOfGcds {
    public static long sumGcd(long n) {
        Map<Long, Integer> f = TrialFactor.factorize(n);
        List<Long> divisors = new ArrayList<>();
        divisors.add(1L);
        for (Map.Entry<Long, Integer> e : f.entrySet()) {
            int sz = divisors.size();
            long pk = 1;
            for (int k = 1; k <= e.getValue(); k++) {
                pk *= e.getKey();
                for (int i = 0; i < sz; i++) divisors.add(divisors.get(i) * pk);
            }
        }
        long total = 0;
        for (long d : divisors) total += d * Totient.phi(n / d);
        return total;
    }
}
```

**Time:** O(√n + #divisors · √n) · **Space:** O(#divisors)

**Insight.** `Σ_{d|n} d·φ(n/d)` is a *Dirichlet convolution* `Id * φ`, which is multiplicative; recognizing that exactly `φ(n/d)` inputs hit each gcd value collapses a naive `O(n log n)` gcd loop into a divisor sum.

---

### Problem 94: Pell's Equation — x² − D·y² = 1 — fundamental solution via continued fractions

**Statement.** Find the smallest positive integer solution `(x, y)` to Pell's equation `x² − D·y² = 1` for a non-square `D`.

**Approach.** The fundamental solution comes from the continued-fraction expansion of `√D`: the convergent just before the period closes gives `(x, y)`. Iterate the standard `(m, d, a)` continued-fraction recurrence, tracking numerator/denominator convergents.

```java
import java.math.BigInteger;

public class Pell {
    // Returns {x, y}, smallest solution to x^2 - D y^2 = 1.
    public static BigInteger[] solve(long D) {
        long a0 = (long) Math.sqrt((double) D);
        long m = 0, d = 1, a = a0;
        BigInteger p0 = BigInteger.ONE, p1 = BigInteger.valueOf(a0);
        BigInteger q0 = BigInteger.ZERO, q1 = BigInteger.ONE;
        if (a0 * a0 == D) return null; // perfect square: no solution
        while (!p1.multiply(p1).subtract(BigInteger.valueOf(D).multiply(q1).multiply(q1))
                  .equals(BigInteger.ONE)) {
            m = d * a - m;
            d = (D - m * m) / d;
            a = (a0 + m) / d;
            BigInteger p2 = BigInteger.valueOf(a).multiply(p1).add(p0);
            BigInteger q2 = BigInteger.valueOf(a).multiply(q1).add(q0);
            p0 = p1; p1 = p2; q0 = q1; q1 = q2;
        }
        return new BigInteger[]{p1, q1};
    }
}
```

**Time:** O(period of √D) BigInteger ops · **Space:** O(1)

**Insight.** Pell solutions *are* continued-fraction convergents of `√D`: the period of the expansion bounds the work, and `BigInteger` is mandatory because the fundamental solution can explode (e.g. `D = 61` already needs a 10-digit `x`).

---

### Problem 95: Chicken McNugget / Frobenius Number — largest non-representable — two-coin closed form

**Statement.** Given two coprime denominations `a, b`, find the largest amount that *cannot* be formed as `a·x + b·y` with non-negative `x, y` (the Frobenius number), and count how many amounts are non-representable.

**Approach.** For two coprime coins the Frobenius number is `a·b − a − b`, and exactly `(a−1)(b−1)/2` non-negative integers are non-representable — both closed forms. (For three+ coins no closed form exists; you fall back to a Dijkstra over residues mod the smallest coin.)

```java
public class Frobenius {
    public static long frobeniusTwo(long a, long b) {
        if (Gcd.gcd(a, b) != 1) throw new IllegalArgumentException("need coprime");
        return a * b - a - b;
    }
    public static long countNonRepresentable(long a, long b) {
        return (a - 1) * (b - 1) / 2;
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** The two-coin Frobenius number `ab − a − b` is one of the few clean closed forms in additive number theory; the symmetry that exactly half of `[0, ab−a−b]` is non-representable comes from the bijection `t ↦ (ab − a − b) − t` swapping representable and non-representable values.

---

### Problem 96: Frobenius for k Coins — Dijkstra on residue graph — coin-problem shortest path

**Statement.** Find the Frobenius number for `k ≥ 3` coprime coins, where no closed form exists.

**Approach.** Build a graph on residues `0..a₀−1` (mod the smallest coin `a₀`); edge from `r` to `(r + cᵢ) mod a₀` with weight `cᵢ`. The shortest path from 0 to each residue `r` is the least reachable amount with that residue; the Frobenius number is `max_r dist[r] − a₀`.

```java
import java.util.*;

public class FrobeniusMultiCoin {
    public static long frobenius(int[] coins) {
        int a0 = Arrays.stream(coins).min().getAsInt();
        long[] dist = new long[a0];
        Arrays.fill(dist, Long.MAX_VALUE);
        dist[0] = 0;
        PriorityQueue<long[]> pq = new PriorityQueue<>((p, q) -> Long.compare(p[1], q[1]));
        pq.add(new long[]{0, 0});
        while (!pq.isEmpty()) {
            long[] top = pq.poll();
            int r = (int) top[0]; long d = top[1];
            if (d > dist[r]) continue;
            for (int c : coins) {
                int nr = (int) ((r + (long) c) % a0);
                long nd = d + c;
                if (nd < dist[nr]) { dist[nr] = nd; pq.add(new long[]{nr, nd}); }
            }
        }
        long max = 0;
        for (long v : dist) if (v != Long.MAX_VALUE) max = Math.max(max, v);
        return max - a0;   // largest non-representable
    }
}
```

**Time:** O(a₀·k·log a₀) · **Space:** O(a₀)

**Insight.** The residue graph reframes "what's the smallest reachable value with residue `r`?" as a shortest path; once every residue's minimum is known, anything `≥ dist[r]` of that residue is representable, so `max dist[r] − a₀` is the last gap — the standard route when the closed form vanishes for `k ≥ 3`.

---

### Problem 97: Modular nCr with n − r Small — falling factorial — avoid full factorial table

**Statement.** Compute `C(n, r) mod p` when `n` is up to `10^18` but `min(r, n−r)` is small (say `≤ 10^6`), with `p` prime and `n < p` not guaranteed.

**Approach.** Use the falling-factorial form `C(n, r) = [n·(n−1)···(n−r+1)] / r!`, taking each factor `(n − i) mod p`. This works for huge `n` as long as `r` is small and `p` is prime (handle the rare `p ≤ n` factor-of-`p` case by tracking the `p`-adic valuation, as in Lucas/Granville).

```java
public class BinomialSmallR {
    static final long MOD = 1_000_000_007L;
    public static long nCr(long n, long r) {
        if (r < 0) return 0;
        r = Math.min(r, n - r);
        if (r < 0) return 0;
        long num = 1, den = 1;
        for (long i = 0; i < r; i++) {
            num = num * (((n - i) % MOD + MOD) % MOD) % MOD;  // falling factorial mod p
            den = den * ((i + 1) % MOD) % MOD;
        }
        return num * FastPow.power(den, MOD - 2, MOD) % MOD;
    }
}
```

**Time:** O(r + log p) · **Space:** O(1)

**Insight.** When `r` is small, the falling factorial `n^(r̲)` has only `r` factors, so you never build an `O(n)` table — but each `(n − i) mod p` may individually hit a multiple of `p`, so for `n ≥ p` you must separately count factors of `p` (Legendre) or the answer is silently 0.

---

### Problem 98: Lucas Theorem Parity — C(n, r) mod 2 via bit AND — Kummer's corollary

**Statement.** Determine the parity of `C(n, r)` (whether it is odd) in `O(1)` bit operations.

**Approach.** By Lucas mod 2, `C(n, r)` is odd iff every binary digit of `r` is ≤ the corresponding digit of `n`, i.e. `(r & n) == r` (equivalently `(n & r) == r`). This is Kummer's theorem specialized to `p = 2`.

```java
public class BinomialParity {
    public static boolean isOdd(long n, long r) {
        return (n & r) == r;   // C(n, r) odd ⟺ r is a submask of n
    }
    // Number of odd entries in row n of Pascal's triangle = 2^(popcount(n)).
    public static long oddCountInRow(long n) {
        return 1L << Long.bitCount(n);
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** Lucas mod 2 collapses an arbitrary binomial parity to a single submask test `(n & r) == r`; the count of odd entries in row `n` being `2^popcount(n)` is the same fact summed over `r` — the Sierpiński-triangle structure of Pascal's triangle made arithmetic.

---

### Problem 99: Kummer's Theorem — exponent of p in C(n, r) — carries in base-p addition

**Statement.** Compute the exact power of a prime `p` dividing `C(n, r)` without computing the binomial itself.

**Approach.** Kummer's theorem: the exponent equals the number of *carries* when adding `r` and `n − r` in base `p`. Add the base-`p` digits with carry propagation and count the carries.

```java
public class KummerExponent {
    public static long exponent(long n, long r, long p) {
        long a = r, b = n - r, carries = 0, carry = 0;
        while (a > 0 || b > 0 || carry > 0) {
            long da = a % p, db = b % p;
            long s = da + db + carry;
            carry = s >= p ? 1 : 0;
            carries += carry;
            a /= p; b /= p;
        }
        return carries;
    }
}
```

**Time:** O(log_p n) · **Space:** O(1)

**Insight.** Kummer's carry count equals the Legendre-difference `v_p(n!) − v_p(r!) − v_p((n−r)!)` but computes it directly from base-`p` addition — an elegant combinatorial reason a binomial gains factors of `p` exactly when digit sums "spill over."

---

### Problem 100: Count Coprimes to n in [1, m] — inclusion–exclusion over prime factors — bounded totient

**Statement.** Count integers in `[1, m]` coprime to `n`, where `m` need not equal `n` (a generalization of Euler's totient, which is the `m = n` case).

**Approach.** Inclusion–exclusion over the *distinct prime factors* of `n`: subtract multiples of each prime, add back multiples of each pairwise product, and so on. With `ω(n)` distinct primes this is `2^ω(n)` terms (`ω ≤ 15` for `n ≤ 10^18`).

```java
import java.util.*;

public class CoprimeCountInRange {
    public static long count(long m, long n) {
        List<Long> primes = new ArrayList<>(TrialFactor.factorize(n).keySet());
        int k = primes.size();
        long total = 0;
        for (int mask = 0; mask < (1 << k); mask++) {
            long prod = 1; int bits = 0;
            for (int i = 0; i < k; i++)
                if ((mask & (1 << i)) != 0) { prod *= primes.get(i); bits++; }
            long term = m / prod;
            total += (bits % 2 == 0) ? term : -term;   // inclusion–exclusion sign
        }
        return total;
    }
}
```

**Time:** O(2^ω(n) + √n) · **Space:** O(ω(n))

**Insight.** Euler's `φ(n)` is the special case `m = n` of this inclusion–exclusion; decoupling the range bound `m` from the modulus `n` is exactly what "count fractions in lowest terms with denominator dividing `n`" and many Farey-sequence problems require.

---

### Problem 101: Sum of Floor(n / i) for All i — divisor-block grouping — √n distinct values

**Statement.** Compute `Σ_{i=1}^{n} ⌊n/i⌋` (equivalently `D(n)` of Problem 91) using the fact that `⌊n/i⌋` takes only `O(√n)` distinct values.

**Approach.** Group consecutive `i` that share the same quotient `q = ⌊n/i⌋`: for a left endpoint `l`, the largest `r` with `⌊n/r⌋ = q` is `⌊n / q⌋`. Add `q·(r − l + 1)` and jump to `r + 1`.

```java
public class FloorDivSum {
    public static long sum(long n) {
        long total = 0;
        for (long l = 1, r; l <= n; l = r + 1) {
            long q = n / l;
            r = n / q;                       // last index with the same quotient
            total += q * (r - l + 1);
        }
        return total;
    }
}
```

**Time:** O(√n) · **Space:** O(1)

**Insight.** The "divisor block" / "number-theoretic blocking" trick — iterating over the `O(√n)` distinct values of `⌊n/i⌋` rather than every `i` — is the workhorse behind Mertens, totient-summatory, and any "sum a function over `⌊n/i⌋`" problem.

---

### Problem 102: Exact Rational Comparison — compare a/b vs c/d without overflow — cross-multiply guard

**Statement.** Compare two fractions `a/b` and `c/d` (with `b, d > 0`) where `a, c` can be up to `10^18`, so the cross-products `a·d` and `c·b` overflow `long`.

**Approach.** Reduce each fraction by its gcd, then compare via the continued-fraction / Euclidean-style mediant comparison, or fall back to `Math.multiplyHigh` to compute the 128-bit cross-products and compare them as 128-bit signed values.

```java
public class RationalCompare {
    // Returns sign of a/b - c/d, with b, d > 0, overflow-safe.
    public static int compare(long a, long b, long c, long d) {
        // Compare a*d vs c*b using 128-bit products via multiplyHigh.
        long hi1 = Math.multiplyHigh(a, d), lo1 = a * d;
        long hi2 = Math.multiplyHigh(c, b), lo2 = c * b;
        if (hi1 != hi2) return Long.compare(hi1, hi2);
        return Long.compareUnsigned(lo1, lo2);  // same high word ⟹ compare low unsigned
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight.** Cross-multiplication is correct but overflows; lifting the comparison to the 128-bit product (high word first, then unsigned low word) preserves exactness without `BigInteger` — the standard pattern for ordering rationals near the 64-bit ceiling.

---

### Problem 103: Stern–Brocot kth Fraction — index into the Farey/SB tree — path encoding

**Statement.** Given a target position, walk to a specific node of the Stern–Brocot tree, or find where a given reduced fraction sits, encoding the path as a run-length continued fraction.

**Approach.** A reduced fraction `p/q`'s path is its continued-fraction expansion: the quotients `a₀, a₁, …` give run lengths of L/R moves. Reconstruct or descend by emitting `aᵢ` consecutive same-direction steps.

```java
import java.util.*;

public class SternBrocotPath {
    // Continued-fraction quotients = run-length-encoded L/R path to p/q.
    public static List<Long> pathTo(long p, long q) {
        List<Long> cf = new ArrayList<>();
        while (q != 0) {
            cf.add(p / q);
            long r = p % q; p = q; q = r;   // Euclid = descend the tree
        }
        return cf;   // e.g. [a0, a1, a2, ...] alternating R,L,R,... run lengths
    }
}
```

**Time:** O(log(p + q)) · **Space:** O(log(p + q))

**Insight.** The Stern–Brocot tree and the Euclidean algorithm are the same process: each continued-fraction quotient `aᵢ` is a *run* of identical L/R turns, so the entire path compresses to `O(log)` numbers even when the literal step count is huge.

---

### Problem 104: Modular Binomial Sum Σ C(n, i) for i in [0, k] — prefix of a row — no closed form, smart DP

**Statement.** Compute `Σ_{i=0}^{k} C(n, i) mod p` (a partial row sum) for many `(n, k)` queries efficiently.

**Approach.** There is no simple closed form, but Mo's algorithm on `(n, k)` uses the two adjacency relations `S(n, k+1) = S(n, k) + C(n, k+1)` and `S(n+1, k) = 2·S(n, k) − C(n, k)` to move between queries in `O(1)` amortized each.

```java
public class PartialRowSum {
    static final long MOD = 1_000_000_007L;
    // Single-query version: O(k). Mo's algorithm batches many queries.
    public static long partialSum(int n, int k) {
        long sum = 0, term = 1;   // term = C(n, i)
        for (int i = 0; i <= k && i <= n; i++) {
            sum = (sum + term) % MOD;
            // C(n, i+1) = C(n, i) * (n - i) / (i + 1)
            term = term * ((n - i) % MOD) % MOD
                        * FastPow.power(i + 1, MOD - 2, MOD) % MOD;
        }
        return sum;
    }
}
```

**Time:** O(k log p) single query, O((Q + maxN)√Q) with Mo's · **Space:** O(1)

**Insight.** The absence of a closed form for partial binomial sums is itself the lesson; the recurrences `S(n+1,k) = 2S(n,k) − C(n,k)` (one Pascal step per element shifts the row) and `S(n,k+1) = S(n,k) + C(n,k+1)` are what let Mo's algorithm answer offline queries in near-constant amortized time.

---

### Problem 105: Sum of Σ i·⌊n/i⌋ — weighted divisor-block sum — arithmetic-series per block

**Statement.** Compute `Σ_{i=1}^{n} i·⌊n/i⌋` (which equals `Σ_{k=1}^{n} σ(k)`, the summatory of the sum-of-divisors function) in `O(√n)`.

**Approach.** Within each divisor block where `⌊n/i⌋ = q` is constant, `Σ i·q = q · (arithmetic series of i from l to r)`. Use `Σ_{i=l}^{r} i = (l + r)(r − l + 1)/2`.

```java
public class WeightedFloorSum {
    static final long MOD = 1_000_000_007L;
    static final long INV2 = (MOD + 1) / 2;   // inverse of 2 mod p
    public static long sum(long n) {
        long total = 0;
        for (long l = 1, r; l <= n; l = r + 1) {
            long q = n / l;
            r = n / q;
            long cnt = (r - l + 1) % MOD;
            long seq = (l + r) % MOD * cnt % MOD * INV2 % MOD;  // Σ i over [l, r]
            total = (total + q % MOD * seq) % MOD;
        }
        return total;
    }
}
```

**Time:** O(√n) · **Space:** O(1)

**Insight.** Weighting the divisor-block trick by an arithmetic series gives `Σ σ(k)` for free — because `Σ_{i} i·⌊n/i⌋` counts each pair `(i, multiple)` with weight `i`, which is exactly the divisor sum summed over all `k ≤ n`.

---

### Problem 106: GCD Convolution / Σ_{i,j} gcd(i, j) — totient-weighted double sum — Dirichlet over gcd

**Statement.** Compute `Σ_{i=1}^{n} Σ_{j=1}^{n} gcd(i, j)` for `n` up to `~10^7`.

**Approach.** Reindex by the gcd value `g = gcd(i, j)`: the number of pairs with `gcd = g` is the number of coprime pairs in `[1, ⌊n/g⌋]²`, which is `Σ_{d} μ(d)·⌊n/(gd)⌋²`. Equivalently `Σ_g (Σ_{e|g} e·μ(g/e))·⌊n/g⌋²`; precompute the multiplicative weight `f(g) = Σ_{d|g} d·μ(g/d)` (which is `φ` here) with a sieve.

```java
public class GcdPairSum {
    static final long MOD = 1_000_000_007L;
    public static long sum(int n) {
        int[] phi = TotientSieve.phiTable(n);   // f(g) = φ(g) for Σgcd
        long total = 0;
        for (long g = 1; g <= n; g++) {
            long t = n / g;                       // ⌊n/g⌋
            long pairs = (t % MOD) * (t % MOD) % MOD;
            total = (total + (long) phi[(int) g] % MOD * pairs) % MOD;
        }
        return total;
    }
}
```

**Time:** O(n log log n) (sieve) + O(n) · **Space:** O(n)

**Insight.** `Σ gcd(i, j) = Σ_g φ(g)·⌊n/g⌋²` because `Σ_{d | g} d·μ(g/d) = φ(g)` (the identity `Id = φ * 1` Möbius-inverted); the double sum dissolves into a single weighted sum over `g`, the prototypical "gcd convolution via Möbius" manipulation.

---

### Problem 107: Modular Catalan via Prime Factorization — C(2n,n)/(n+1) mod composite — Kummer per prime

**Statement.** Compute the `n`-th Catalan number modulo a *composite* `m` (where the `(n+1)^(−1)` inverse may not exist mod `m`).

**Approach.** Catalan `Cₙ = C(2n, n) − C(2n, n+1)` avoids the division entirely; compute each binomial mod `m` via the composite-binomial machinery (Problem 61: Granville + CRT) and subtract.

```java
public class CatalanComposite {
    public static long catalan(long n, long m) {
        long a = BinomialAnyMod.nCr(2 * n, n, m);
        long b = BinomialAnyMod.nCr(2 * n, n + 1, m);
        return ((a - b) % m + m) % m;   // Cₙ = C(2n,n) − C(2n,n+1)
    }
}
```

**Time:** O(√m + Σ pᵢ^kᵢ) · **Space:** O(max pᵢ^kᵢ)

**Insight.** Using the *subtractive* identity `Cₙ = C(2n,n) − C(2n,n+1)` sidesteps the `(n+1)^(−1)` that fails when `gcd(n+1, m) > 1` — the same reason interviewers like Catalan-mod-composite: the obvious division route silently breaks.

---

### Problem 108: Modular Determinant mod p — Gaussian elimination with inverses — fraction-free option

**Statement.** Compute the determinant of an `n×n` integer matrix modulo a prime `p`.

**Approach.** Gaussian elimination in `Z/pZ`: pivot, multiply the determinant by the pivot, eliminate below using the pivot's modular inverse. Track a sign flip on each row swap.

```java
public class ModularDeterminant {
    public static long det(long[][] a, long p) {
        int n = a.length;
        long determinant = 1;
        for (int col = 0; col < n; col++) {
            int pivot = -1;
            for (int r = col; r < n; r++) if (a[r][col] % p != 0) { pivot = r; break; }
            if (pivot == -1) return 0;                  // singular
            if (pivot != col) { long[] t = a[pivot]; a[pivot] = a[col]; a[col] = t; determinant = p - determinant; }
            determinant = determinant * (a[col][col] % p) % p;
            long inv = FastPow.power(a[col][col], p - 2, p);
            for (int r = col + 1; r < n; r++) {
                long factor = a[r][col] * inv % p;
                for (int c = col; c < n; c++)
                    a[r][c] = ((a[r][c] - factor * a[col][c]) % p + p) % p;
            }
        }
        return (determinant % p + p) % p;
    }
}
```

**Time:** O(n³) · **Space:** O(1) extra

**Insight.** Modular Gaussian elimination needs the *prime* modulus so every nonzero pivot is invertible; the determinant accumulates the product of pivots times the swap sign — the foundation for counting spanning trees (Matrix-Tree theorem) and solving linear systems mod `p`.

---

### Problem 109: Linear System mod p — Gaussian elimination with back-substitution — rank and solution

**Statement.** Solve `A·x ≡ b (mod p)` for a prime `p`, returning a solution or detecting inconsistency / infinitely many solutions.

**Approach.** Row-reduce the augmented matrix `[A | b]` to reduced row echelon form using modular inverses for pivots; read off the solution, or detect a `0 = nonzero` row (inconsistent) or free variables (infinite solutions).

```java
public class ModularLinearSystem {
    // Returns one solution, or null if inconsistent. p prime.
    public static long[] solve(long[][] A, long[] b, long p) {
        int n = A.length, m = A[0].length;
        long[][] aug = new long[n][m + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(A[i], 0, aug[i], 0, m);
            aug[i][m] = ((b[i] % p) + p) % p;
        }
        int row = 0;
        int[] where = new int[m]; java.util.Arrays.fill(where, -1);
        for (int col = 0; col < m && row < n; col++) {
            int sel = -1;
            for (int r = row; r < n; r++) if (aug[r][col] % p != 0) { sel = r; break; }
            if (sel == -1) continue;
            long[] t = aug[sel]; aug[sel] = aug[row]; aug[row] = t;
            long inv = FastPow.power(aug[row][col], p - 2, p);
            for (int c = col; c <= m; c++) aug[row][c] = aug[row][c] * inv % p;
            for (int r = 0; r < n; r++) if (r != row && aug[r][col] % p != 0) {
                long f = aug[r][col];
                for (int c = col; c <= m; c++)
                    aug[r][c] = ((aug[r][c] - f * aug[row][c]) % p + p) % p;
            }
            where[col] = row++;
        }
        long[] x = new long[m];
        for (int c = 0; c < m; c++) if (where[c] != -1) x[c] = aug[where[c]][m];
        for (int r = row; r < n; r++) if (aug[r][m] % p != 0) return null; // inconsistent
        return x;
    }
}
```

**Time:** O(n·m·min(n,m)) · **Space:** O(n·m)

**Insight.** The `where[]` array recording each column's pivot row distinguishes the three outcomes — unique solution, no solution (a surviving `0 = c≠0` row), or free variables — which is exactly the information modular Gauss-Jordan needs to report beyond just "a solution."

---

### Problem 110: Number of Spanning Trees mod p — Matrix-Tree theorem — Laplacian cofactor

**Statement.** Count the spanning trees of a graph modulo a prime `p` using Kirchhoff's Matrix-Tree theorem.

**Approach.** Build the Laplacian `L = D − A` (degree minus adjacency), delete any one row and column, and take the determinant of the resulting `(n−1)×(n−1)` matrix modulo `p` (Problem 108).

```java
public class SpanningTreeCount {
    public static long count(int n, int[][] edges, long p) {
        long[][] L = new long[n][n];
        for (int[] e : edges) {
            int u = e[0], v = e[1];
            L[u][u] = (L[u][u] + 1) % p;
            L[v][v] = (L[v][v] + 1) % p;
            L[u][v] = (L[u][v] - 1 + p) % p;
            L[v][u] = (L[v][u] - 1 + p) % p;
        }
        // Delete last row & column → (n-1)x(n-1) minor.
        long[][] minor = new long[n - 1][n - 1];
        for (int i = 0; i < n - 1; i++)
            for (int j = 0; j < n - 1; j++) minor[i][j] = L[i][j];
        return ModularDeterminant.det(minor, p);
    }
}
```

**Time:** O(n³) · **Space:** O(n²)

**Insight.** Kirchhoff's theorem says *any* cofactor of the Laplacian counts spanning trees — the choice of deleted vertex is irrelevant — reducing a hard combinatorial count to a single modular determinant; for weighted graphs the same minor gives the *weighted* tree sum.

---

### Problem 111: Modular Fibonacci with Negative Index — F(−n) = (−1)^(n+1) F(n) — extend the sequence

**Statement.** Compute `F(n) mod p` for possibly *negative* `n`, where Fibonacci extends to negative indices by `F(−n) = (−1)^(n+1)·F(n)`.

**Approach.** Compute `F(|n|)` by matrix exponentiation, then apply the sign rule for negative `n`: positive for odd `|n|`, negated for even `|n|`.

```java
public class NegafibonacciMod {
    static final long MOD = 1_000_000_007L;
    public static long fib(long n) {
        if (n >= 0) return FibMatrix.fib(n);
        long f = FibMatrix.fib(-n);
        // F(-n) = (-1)^(n+1) F(n): negate when -n is even.
        boolean negate = ((-n) % 2 == 0);
        return negate ? (MOD - f) % MOD : f;
    }
}
```

**Time:** O(log|n|) · **Space:** O(1)

**Insight.** The "negafibonacci" extension `F(−n) = (−1)^(n+1)F(n)` keeps the recurrence `F(k) = F(k+2) − F(k+1)` valid for all integers; the only subtlety modularly is converting the sign into a `MOD − f` negation rather than a literal negative.

---

### Problem 112: Sum of First n Fibonacci Numbers mod p — telescoping identity — ΣF = F(n+2) − 1

**Statement.** Compute `Σ_{i=1}^{n} F(i) mod p` for huge `n` without summing term by term.

**Approach.** Use the closed identity `Σ_{i=1}^{n} F(i) = F(n+2) − 1`. Compute `F(n+2)` by matrix exponentiation and subtract 1 modularly.

```java
public class FibPrefixSum {
    static final long MOD = 1_000_000_007L;
    public static long sum(long n) {
        return (FibMatrix.fib(n + 2) - 1 + MOD) % MOD;  // ΣF(1..n) = F(n+2) − 1
    }
}
```

**Time:** O(log n) · **Space:** O(1)

**Insight.** The telescoping `F(i) = F(i+2) − F(i+1)` collapses the prefix sum to a single Fibonacci `F(n+2) − 1`; recognizing these Fibonacci identities (prefix sum, sum of squares `= F(n)F(n+1)`, sum of even-indexed) turns `O(n)` loops into `O(log n)` matrix powers.

---

### Problem 113: Modular Exponentiation of a Polynomial of a Matrix — Cayley–Hamilton reduction — degree-k remainder

**Statement.** Compute `Aⁿ` for a `k×k` matrix `A` and huge `n` faster than naive matrix-power by reducing through the characteristic polynomial (Cayley–Hamilton).

**Approach.** By Cayley–Hamilton, `Aⁿ` is a polynomial in `A` of degree `< k`. Compute `xⁿ mod charpoly(A)` (a degree-`k` polynomial, via Kitamasa-style squaring), then evaluate `Σ cᵢ Aⁱ` with only `k−1` matrix multiplies.

```java
public class MatrixPowCayleyHamilton {
    static final long MOD = 1_000_000_007L;
    // charPoly: coefficients c[] so that x^k ≡ c0 x^{k-1} + ... + c_{k-1}.
    public static long[][] pow(long[][] A, long[] charPoly, long n, int k) {
        long[] poly = Kitamasa.xPowModPoly(n, charPoly);  // x^n mod charpoly, degree < k
        long[][] result = new long[k][k];
        long[][] Apow = identity(k);
        for (int i = 0; i < poly.length; i++) {
            if (poly[i] != 0)
                for (int r = 0; r < k; r++)
                    for (int c = 0; c < k; c++)
                        result[r][c] = (result[r][c] + poly[i] * Apow[r][c]) % MOD;
            Apow = LinearRecurrence.mul(Apow, A, k);       // A^(i+1)
        }
        return result;
    }
    static long[][] identity(int k) {
        long[][] I = new long[k][k];
        for (int i = 0; i < k; i++) I[i][i] = 1;
        return I;
    }
}
```

**Time:** O(k² log n + k³) (polynomial squaring then k matrix products) · **Space:** O(k²)

**Insight.** Cayley–Hamilton guarantees `Aⁿ` lives in the degree-`< k` span `{I, A, …, A^(k−1)}`, so you spend the `log n` factor on cheap *polynomial* squaring and pay the `k³` matrix cost only `k` times total — strictly better than `O(k³ log n)` when `k` is large.

---

### Problem 114: Count Numbers ≤ N Divisible by Any of a Set — inclusion–exclusion over lcms — multiple-of-set count

**Statement.** Count integers in `[1, N]` divisible by at least one element of a set `{a₁, …, a_k}` (small `k`), modulo nothing — exact count up to `N = 10^18`.

**Approach.** Inclusion–exclusion over subsets: for each non-empty subset, add or subtract `⌊N / lcm(subset)⌋` by parity, guarding the lcm against overflow (if it exceeds `N`, the term is 0).

```java
public class CountDivisibleByAny {
    public static long count(long N, long[] a) {
        int k = a.length;
        long total = 0;
        for (int mask = 1; mask < (1 << k); mask++) {
            long l = 1; int bits = 0; boolean overflow = false;
            for (int i = 0; i < k; i++) if ((mask & (1 << i)) != 0) {
                bits++;
                long g = Gcd.gcd(l, a[i]);
                if (l / g > N / a[i]) { overflow = true; break; } // lcm > N ⟹ term 0
                l = l / g * a[i];
            }
            if (overflow) continue;
            long term = N / l;
            total += (bits % 2 == 1) ? term : -term;
        }
        return total;
    }
}
```

**Time:** O(2^k · k log) · **Space:** O(1)

**Insight.** The overflow guard `l/g > N/a[i]` is essential: once an lcm exceeds `N` its floor-division contributes 0, and computing it directly would overflow `long` — skipping such terms keeps the inclusion–exclusion both correct and overflow-free.

---

### Problem 115: Euler Totient of n! — φ(n!) via prime exponents — Legendre meets totient

**Statement.** Compute `φ(n!) mod p`, the totient of a factorial, for `n` up to `10^7`.

**Approach.** `n! = ∏_{q ≤ n prime} q^{e_q}` with `e_q` from Legendre's formula. Then `φ(n!) = n! · ∏_{q ≤ n} (1 − 1/q) = ∏_q q^{e_q − 1}·(q − 1)`. Sieve primes ≤ n, compute each exponent, accumulate modularly.

```java
public class TotientOfFactorial {
    static final long MOD = 1_000_000_007L;
    public static long phiFactorial(int n) {
        boolean[] comp = new boolean[n + 1];
        long result = 1;
        for (int q = 2; q <= n; q++) {
            if (comp[q]) continue;
            for (int j = 2 * q; j <= n; j += q) comp[j] = true;  // sieve
            long e = 0;                                          // Legendre exponent
            for (long pk = q; pk <= n; pk *= q) e += n / pk;
            result = result * FastPow.power(q, e - 1, MOD) % MOD;  // q^(e-1)
            result = result * ((q - 1) % MOD) % MOD;               // (q - 1)
        }
        return result;
    }
}
```

**Time:** O(n log log n) · **Space:** O(n)

**Insight.** `φ` is multiplicative over the prime-power factorization of `n!`, so `φ(n!) = ∏_q q^{e_q−1}(q−1)` — combining Legendre's exponent count with the totient formula avoids ever materializing the astronomically large `n!`.

---

### Problem 116: Sum of Primes ≤ N — Lucy_Hedgehog / Meissel sieve — sub-linear prime sum

**Statement.** Compute the sum of all primes `≤ N` for `N` up to `~10^11`, far beyond a linear sieve.

**Approach.** Lucy_Hedgehog's DP over the `O(√N)` distinct values of `⌊N/i⌋`: maintain `S(v)` = sum of integers in `[2, v]` not yet sieved; for each prime `p ≤ √N`, subtract `p·(S(v/p) − S(p−1))`. The final `S(N)` is the prime sum.

```java
import java.util.*;

public class SumOfPrimes {
    public static long sumPrimes(long N) {
        long sq = (long) Math.sqrt((double) N);
        while ((sq + 1) * (sq + 1) <= N) sq++;
        long[] small = new long[(int) sq + 1];   // S(i) for i ≤ √N
        long[] large = new long[(int) sq + 1];   // S(N/i) for i ≤ √N
        for (int i = 1; i <= sq; i++) {
            small[i] = (long) i * (i + 1) / 2 - 1;          // Σ 2..i
            large[i] = (N / i) % 2 == 0
                ? (N / i / 2) % 1 * 0 + (N / i / 2) * ((N / i) + 1) - 1
                : ((N / i + 1) / 2) * (N / i) - 1;
        }
        for (long p = 2; p <= sq; p++) {
            if (small[(int) p] == small[(int) (p - 1)]) continue; // p not prime
            long sp = small[(int) (p - 1)];                       // sum of primes < p
            long p2 = p * p;
            for (int i = 1; i <= sq && (long) i * i <= N; i++) {
                long d = (long) i * p;
                long val = (d <= sq) ? large[(int) d]
                         : small[(int) (N / d)];
                if ((N / i) < p2) break;
                large[i] -= p * (val - sp);
            }
            for (int i = (int) sq; i >= 1 && (long) i >= p2; i--)
                small[i] -= p * (small[(int) (i / p)] - sp);
        }
        return large[1];
    }
}
```

**Time:** O(N^(3/4) / log N) · **Space:** O(√N)

**Insight.** Lucy_Hedgehog's method computes prime-counting and prime-sum functions in `O(N^(3/4))` by only ever tracking the `O(√N)` distinct `⌊N/i⌋` partial sieves — the same blocking idea as Mertens, but maintaining a running "sum of survivors" sieved one prime at a time.

---

### Problem 117: Modular Square Root mod Prime Power — lift via Hensel — p-adic Newton step

**Statement.** Solve `x² ≡ a (mod p^k)` by lifting a root mod `p` to a root mod `p^k` (assuming `p` odd, `gcd(a, p) = 1`).

**Approach.** Find a root mod `p` (Tonelli–Shanks), then Hensel-lift: given `x` with `x² ≡ a (mod p^j)`, the lift mod `p^{2j}` is `x − (x² − a)·(2x)^{−1}`, a `p`-adic Newton step. Iterate to reach `p^k`.

```java
public class SqrtPrimePower {
    public static long sqrt(long a, long p, int k) {
        long pk = p;
        long x = TonelliShanks.sqrtMod(a % p, p);
        if (x < 0) return -1;
        int cur = 1;
        while (cur < k) {
            long nextPk = pk * p;                         // lift one power at a time
            long inv2x = FastPow.power((2 * x) % nextPk, totientOfPrimePower(p, cur + 1) - 1, nextPk);
            long fx = ((x * x - a) % nextPk + nextPk) % nextPk;
            x = ((x - fx * inv2x) % nextPk + nextPk) % nextPk;  // Newton/Hensel step
            pk = nextPk; cur++;
        }
        return x;
    }
    static long totientOfPrimePower(long p, int e) {
        long pe = 1; for (int i = 0; i < e; i++) pe *= p;
        return pe / p * (p - 1);
    }
}
```

**Time:** O(k·log p^k) · **Space:** O(1)

**Insight.** Hensel's lemma is Newton's method in the `p`-adic integers: a simple root mod `p` (where the derivative `2x ≢ 0`) lifts uniquely to each higher power, doubling correct digits per step — the algebraic foundation for solving polynomial congruences mod `p^k`.

---

### Problem 118: Generalized CRT with Square-Root Lift — combine x² ≡ a over coprime moduli — CRT of roots

**Statement.** Solve `x² ≡ a (mod n)` for composite `n = ∏ pᵢ^{kᵢ}` by combining the per-prime-power roots — yielding all `2^ω` roots.

**Approach.** Solve `x² ≡ a (mod pᵢ^{kᵢ})` for each prime power (Problem 117), each giving `±` a root; CRT-combine every sign choice across the factors to produce all square roots mod `n`.

```java
import java.util.*;

public class SqrtModComposite {
    public static List<Long> sqrt(long a, long n) {
        Map<Long, Integer> f = TrialFactor.factorize(n);
        List<long[]> rootsPerFactor = new ArrayList<>();  // {root, modulus}
        for (Map.Entry<Long, Integer> e : f.entrySet()) {
            long pk = 1; for (int i = 0; i < e.getValue(); i++) pk *= e.getKey();
            long r = SqrtPrimePower.sqrt(((a % pk) + pk) % pk, e.getKey(), e.getValue());
            if (r < 0) return Collections.emptyList();     // no root mod this factor
            rootsPerFactor.add(new long[]{r, pk});
        }
        // Combine all ± choices via CRT.
        List<Long> result = new ArrayList<>();
        int k = rootsPerFactor.size();
        for (int mask = 0; mask < (1 << k); mask++) {
            long[] residues = new long[k], mods = new long[k];
            for (int i = 0; i < k; i++) {
                long[] rf = rootsPerFactor.get(i);
                long sign = ((mask >> i) & 1) == 0 ? rf[0] : (rf[1] - rf[0]) % rf[1];
                residues[i] = sign; mods[i] = rf[1];
            }
            long[] sol = CrtGeneral.solve(residues, mods);
            if (sol != null) result.add(sol[0]);
        }
        return result;
    }
}
```

**Time:** O(√n + 2^ω·ω log n) · **Space:** O(2^ω)

**Insight.** Square roots mod a composite multiply: each prime power contributes `±` one root, so `n` with `ω` distinct odd primes has `2^ω` square roots of a residue — the structural fact underlying why factoring `n` is equivalent to extracting square roots mod `n` (the basis of Rabin cryptography).

---

### Problem 119: Modular Logarithm with Pohlig–Hellman — discrete log on smooth order — CRT over prime-power orders

**Statement.** Solve `g^x ≡ h (mod p)` when the order `n = p − 1` of `g` is *smooth* (factors into small primes), faster than `O(√p)` BSGS.

**Approach.** Pohlig–Hellman: for each prime power `qᵉ || n`, solve the discrete log modulo `qᵉ` (cheap because the subgroup is small, using BSGS within it), then CRT-combine the partial logs.

```java
import java.util.*;

public class PohligHellman {
    // g^x ≡ h (mod p), order of g is n (= p-1 for a primitive root).
    public static long discreteLog(long g, long h, long p, long n) {
        Map<Long, Integer> f = TrialFactor.factorize(n);
        long[] residues = new long[f.size()];
        long[] mods = new long[f.size()];
        int idx = 0;
        for (Map.Entry<Long, Integer> e : f.entrySet()) {
            long q = e.getKey(); int ex = e.getValue();
            long qe = 1; for (int i = 0; i < ex; i++) qe *= q;
            // Reduce to the subgroup of order qe.
            long gi = FastPow.power(g, n / qe, p);
            long hi = FastPow.power(h, n / qe, p);
            long xi = BabyStepGiantStep.solve(gi, hi, p);  // small subgroup BSGS
            residues[idx] = xi; mods[idx] = qe; idx++;
        }
        long[] sol = CrtGeneral.solve(residues, mods);
        return sol[0];
    }
}
```

**Time:** O(Σ eᵢ(√qᵢ + log p)) · **Space:** O(max √qᵢ)

**Insight.** Pohlig–Hellman is why discrete-log cryptography insists the group order have a *large prime factor*: if `p − 1` is smooth, the log splits across small subgroups via CRT and each piece is a tiny BSGS, breaking the problem in roughly `O(Σ √qᵢ)` instead of `O(√p)`.

---

### Problem 120: Digit DP with Multiple Constraints — count ≤ N with digit-sum ≡ r (mod m) — extended state

**Statement.** Count integers in `[0, N]` whose digit sum is `≡ r (mod m)`, a digit DP with an extra modular-residue state dimension.

**Approach.** Standard digit DP with state `(pos, tight, residue)` where `residue` accumulates `(running digit sum) mod m`. At the end, count completions whose final residue equals `r`.

```java
public class DigitDpModSum {
    static int[] digits;
    static int M, target;
    static Long[][][] memo;   // memo[pos][tight][residue]

    public static long count(long n, int m, int r) {
        M = m; target = r;
        String s = Long.toString(n);
        digits = new int[s.length()];
        for (int i = 0; i < s.length(); i++) digits[i] = s.charAt(i) - '0';
        memo = new Long[s.length()][2][m];
        return dp(0, 1, 0);
    }
    private static long dp(int pos, int tight, int res) {
        if (pos == digits.length) return res == target ? 1 : 0;
        if (memo[pos][tight][res] != null) return memo[pos][tight][res];
        int limit = (tight == 1) ? digits[pos] : 9;
        long total = 0;
        for (int d = 0; d <= limit; d++)
            total += dp(pos + 1, (tight == 1 && d == limit) ? 1 : 0, (res + d) % M);
        return memo[pos][tight][res] = total;
    }
}
```

**Time:** O(len · 2 · m · 10) · **Space:** O(len · 2 · m)

**Insight.** Every extra "running property mod something" becomes one more memo dimension; the digit-sum-residue state shows the general pattern for counting numbers `≤ N` satisfying a *modular* digit constraint — divisibility-by-`m`-of-digit-sum, alternating-sum tests, etc.

---

### Problem 121: Count Square-Free Numbers ≤ N — Möbius over squares — inclusion–exclusion sieve

**Statement.** Count the square-free integers in `[1, N]` for `N` up to `~10^12`.

**Approach.** By inclusion–exclusion over squares of primes, the count is `Σ_{d=1}^{√N} μ(d)·⌊N/d²⌋`. Sieve `μ` up to `√N`, then sum.

```java
public class SquareFreeCount {
    public static long count(long N) {
        int lim = (int) Math.sqrt((double) N) + 1;
        int[] mu = MobiusSieve.mobius(lim);
        long total = 0;
        for (long d = 1; (long) d * d <= N; d++)
            total += (long) mu[(int) d] * (N / (d * d));   // μ(d)·⌊N/d²⌋
        return total;
    }
}
```

**Time:** O(√N) · **Space:** O(√N)

**Insight.** `Σ μ(d)⌊N/d²⌋` counts each integer once with weight `Σ_{d²|n} μ(d) = [n square-free]` — the Möbius function over the *square* divisors filters out exactly the numbers with a repeated prime factor.

---

### Problem 122: Sum over Subsets / SOS DP — Σ over submasks for gcd/divisor lattices — zeta transform

**Statement.** Given `f` indexed by `1..n`, compute `g(m) = Σ_{d | m} f(d)` for all `m` (the divisor zeta transform), in `O(n log log n)`.

**Approach.** This is the multiplicative analog of subset-sum-over-subsets: for each prime `p ≤ n`, add `f`'s value at `m/p` into `m` for every multiple — a Dirichlet-prefix sweep. The inverse (Möbius) subtracts instead.

```java
public class DivisorZeta {
    // g(m) = Σ_{d | m} f(d), in place.
    public static void zeta(long[] f, int n) {
        boolean[] comp = new boolean[n + 1];
        for (int p = 2; p <= n; p++) {
            if (comp[p]) continue;
            for (int j = 2 * p; j <= n; j += p) comp[j] = true;     // mark composites
            for (int m = 1; m * p <= n; m++) f[m * p] += f[m];       // add f(m) into f(m*p)
        }
    }
    // Inverse: f(m) = Σ_{d | m} μ(m/d) g(d).
    public static void mobius(long[] g, int n) {
        boolean[] comp = new boolean[n + 1];
        for (int p = 2; p <= n; p++) {
            if (comp[p]) continue;
            for (int j = 2 * p; j <= n; j += p) comp[j] = true;
            for (int m = n / p; m >= 1; m--) g[m * p] -= g[m];       // reverse order
        }
    }
}
```

**Time:** O(n log log n) · **Space:** O(1) extra

**Insight.** The divisor zeta/Möbius transform is "sum-over-subsets" on the *divisor lattice* instead of the subset lattice: each prime is one dimension, and sweeping `f(m) → f(m·p)` is the same prefix-sum-then-invert pattern as classic SOS DP over bitmasks.

---

### Problem 123: Count Lattice Points Under a Line — Σ ⌊(a·i + b)/c⌋ generalized — floor-sum application

**Statement.** Count integer lattice points `(x, y)` with `1 ≤ x ≤ n`, `1 ≤ y ≤ ⌊(a·x + b)/c⌋` — lattice points strictly under a line — reusing the floor-sum primitive.

**Approach.** The count is exactly `Σ_{x=1}^{n} ⌊(a·x + b)/c⌋`, which the `O(log)` floor-sum of Problem 62 computes directly (shifting the index range from `0..n−1` to `1..n`).

```java
public class LatticePointsUnderLine {
    public static long count(long n, long a, long b, long c) {
        // Σ_{x=1}^{n} floor((a x + b)/c) = floorSum(n+1, c, a, b) − floor(b/c)
        long full = FloorSum.floorSum(n + 1, c, a, b);  // covers x = 0..n
        return full - Math.floorDiv(b, c);              // remove the x = 0 term
    }
}
```

**Time:** O(log max(a, c)) · **Space:** O(1)

**Insight.** Many geometry-flavored counting problems ("points under `y = (a/c)x`", "sum of `⌊kx⌋`") reduce to the single floor-sum primitive; the only care needed is the index offset, since `floorSum` sums over `0..n−1` while the geometric range often starts at 1.

---

### Problem 124: BigInteger Factorial Exactly — when modulus is forbidden — arbitrary precision

**Statement.** Compute the *exact* value of `n!` (not modular) for moderate `n` (say `n ≤ 5000`), returning the full big number.

**Approach.** Multiply iteratively with `BigInteger`; for large `n`, a divide-and-conquer product (split the range, multiply halves) is asymptotically faster because it keeps operand sizes balanced.

```java
import java.math.BigInteger;

public class ExactFactorial {
    public static BigInteger factorial(int n) {
        return product(1, n);
    }
    // Balanced divide-and-conquer keeps BigInteger multiplies near-square.
    private static BigInteger product(int lo, int hi) {
        if (lo > hi) return BigInteger.ONE;
        if (lo == hi) return BigInteger.valueOf(lo);
        int mid = (lo + hi) >>> 1;
        return product(lo, mid).multiply(product(mid + 1, hi));
    }
}
```

**Time:** O(M(n log n)) with fast multiplication · **Space:** O(n log n) bits

**Insight.** The naive left-fold `result *= i` makes one operand huge and the other tiny — unbalanced multiplies; splitting the range so each `BigInteger.multiply` has comparable-sized operands lets Karatsuba/Toom-Cook engage, a meaningful speed-up for `BigInteger` factorials and products.

---

### Problem 125: Modular Inverse of a Factorial Range — batch inverses via prefix products — Montgomery's trick

**Statement.** Given `a₁, …, a_k`, compute all `aᵢ^{−1} mod p` using a *single* modular inverse (Montgomery's batch-inversion trick).

**Approach.** Compute prefix products `P_i = a₁·…·aᵢ`, invert only the final product `P_k`, then walk backward: `aᵢ^{−1} = P_{i−1} · (inverse of P_i)`, updating the running inverse by multiplying back `aᵢ`.

```java
public class BatchInverse {
    static final long MOD = 1_000_000_007L;
    public static long[] inverses(long[] a) {
        int k = a.length;
        long[] prefix = new long[k + 1];
        prefix[0] = 1;
        for (int i = 0; i < k; i++) prefix[i + 1] = prefix[i] * (a[i] % MOD) % MOD;
        long inv = FastPow.power(prefix[k], MOD - 2, MOD);   // ONE inversion
        long[] res = new long[k];
        for (int i = k - 1; i >= 0; i--) {
            res[i] = prefix[i] * inv % MOD;                  // a_i^{-1} = P_{i-1} · inv(P_i)
            inv = inv * (a[i] % MOD) % MOD;                  // peel a_i off the running inverse
        }
        return res;
    }
}
```

**Time:** O(k + log p) · **Space:** O(k)

**Insight.** Montgomery's trick amortizes one `O(log p)` inverse across `k` elements: prefix products plus one inversion plus a backward pass costs `O(k + log p)` instead of `O(k log p)` — indispensable when inverting thousands of values inside a tight loop.

---

### Problem 126: Wilson-Based Factorial mod p Removing p — factorial without multiples of p — block-Wilson

**Statement.** Compute `n!` with all factors of `p` removed, taken mod `p` (the "factorial unit part," needed for Lucas-over-prime-powers and Wilson generalizations).

**Approach.** Wilson's theorem says each *complete* block of `p` consecutive factors (with the multiple of `p` removed) contributes `(p−1)! ≡ −1 (mod p)`. Count the blocks via `n/p`, apply the `±1` sign, and multiply the partial block `(n mod p)!`.

```java
public class FactorialRemovingP {
    // (n! with factors of p stripped) mod p, recursively over base-p blocks.
    public static long factMod(long n, long p) {
        long result = 1;
        while (n > 1) {
            long blocks = n / p;
            if (blocks % 2 == 1) result = (p - result) % p;   // each full block ≡ -1 (Wilson)
            long rem = n % p;
            for (long i = 2; i <= rem; i++) result = result * i % p;
            n /= p;                                            // recurse on the block count
        }
        return result;
    }
}
```

**Time:** O((n/p + p) ... ) ≈ O(p · log_p n) with precomputed block factorials · **Space:** O(1)

**Insight.** Wilson's `(p−1)! ≡ −1` makes each full block of `p` factors collapse to a sign, so the factorial-with-`p`-removed reduces to `(−1)^{blocks}` times a partial product — the engine inside generalized Lucas (Problem 60) for `nCr mod p^k`.

---

### Problem 127: Quadratic Residue Count and Legendre Sum — Σ Legendre symbols — character sum

**Statement.** Count quadratic residues among `1..p−1` (it is always `(p−1)/2`) and compute a Legendre character sum `Σ_{x} (x/p)·f(x)` for a simple `f`.

**Approach.** The Legendre symbol `(a/p) = a^{(p−1)/2} mod p` (mapping to `+1`, `−1`, or `0`). Exactly half the nonzero residues are QRs. For character sums, evaluate the symbol per term; Gauss sums give closed forms for special `f`.

```java
public class LegendreSum {
    public static int legendre(long a, long p) {
        long r = FastPow.power(((a % p) + p) % p, (p - 1) / 2, p);
        return r == p - 1 ? -1 : (int) r;   // a^((p-1)/2) ∈ {0, 1, p-1}
    }
    public static long countQuadraticResidues(long p) {
        return (p - 1) / 2;   // exactly half the units are QRs
    }
    public static long characterSum(long p) {
        long sum = 0;
        for (long x = 1; x < p; x++) sum += legendre(x, p);  // ≡ 0 over a full period
        return sum;                                          // demonstrates Σ(x/p) = 0
    }
}
```

**Time:** O(p log p) for the explicit sum, O(log p) per symbol · **Space:** O(1)

**Insight.** The Legendre symbol is a multiplicative *character*: exactly half of `1..p−1` are residues, and `Σ_{x=1}^{p−1} (x/p) = 0` because residues and non-residues balance — the starting point for Gauss sums, the analytic class-number formula, and many character-sum estimates.

---

### Problem 128: Modular Tower with Mixed Coprimality — a^b^c mod m general — generalized Euler twice

**Statement.** Compute `a^(b^c) mod m` for arbitrary `a, b, c, m` (no coprimality assumptions), the two-level version of the tetration reduction.

**Approach.** Reduce the inner exponent `b^c` modulo `φ(m)` using fast exponentiation, applying the `+φ(m)` lift when `b^c ≥ log₂ m` (generalized Euler), then raise `a` to that lifted exponent. Detect when `b^c` is genuinely small to skip the lift.

```java
public class PowerTowerGeneral {
    // a^(b^c) mod m
    public static long compute(long a, long b, long c, long m) {
        if (m == 1) return 0;
        long phi = Totient.phi(m);
        // Inner exponent e = b^c, reduced mod φ(m) with lift if e ≥ log2(m).
        long[] inner = powWithFlag(b, c, phi);   // {b^c mod φ, exceededFlag}
        long exp = inner[0];
        if (inner[1] == 1) exp += phi;            // lift onto the periodic branch
        return powLift(a % m, exp, m);
    }
    // Returns {base^exp mod mod, flag=1 if true value ≥ mod}.
    private static long[] powWithFlag(long base, long exp, long mod) {
        long result = 1 % mod; boolean exceeded = false;
        base %= mod;
        // crude: also track whether the true power reached mod via a parallel capped product
        long cap = 1;
        for (long e = exp; e > 0; e--) {
            cap = Math.min(mod, cap * Math.max(base, 1));
            if (cap >= mod) { exceeded = true; break; }
        }
        result = FastPow.power(base, exp, mod);
        return new long[]{result, exceeded ? 1 : 0};
    }
    private static long powLift(long base, long exp, long m) {
        long r = 1 % m; base %= m;
        while (exp > 0) {
            if ((exp & 1) == 1) r = r * base % m;
            base = base * base % m; exp >>= 1;
        }
        return r;
    }
}
```

**Time:** O(log c + log m) (with a proper capped exponent check) · **Space:** O(1)

**Insight.** The two-level tower needs the *generalized* Euler lift on the inner exponent because `a` and `m` may share factors; the subtle part is deciding whether `b^c ≥ φ(m)` to apply the `+φ` correction — getting that flag wrong is the classic off-by-a-cycle bug in `a^b^c mod m`.

---

### Problem 129: Count Integers ≤ N That Are Perfect Powers — inclusion–exclusion over exponents — Möbius on powers

**Statement.** Count integers in `[2, N]` that are perfect powers (`a^k` for some `k ≥ 2`) for `N` up to `10^18`, without double-counting numbers like `64 = 2^6 = 4^3 = 8^2`.

**Approach.** Inclusion–exclusion over the exponent: the count of perfect powers is `Σ_{k≥2} μ(k)·(−1)... ` — concretely `−Σ_{k=2}^{log₂N} μ(k)·(⌊N^{1/k}⌋ − 1)`, where the Möbius weight removes the over-counting from composite exponents.

```java
public class PerfectPowerCount {
    public static long count(long N) {
        int maxK = 63;                       // 2^63 > 10^18
        int[] mu = MobiusSieve.mobius(maxK);
        long total = 0;
        for (int k = 2; k <= maxK; k++) {
            if (mu[k] == 0) continue;
            long root = IntNthRoot.nthRoot(N, k);   // ⌊N^{1/k}⌋
            if (root < 2) break;                    // no k-th powers ≥ 2^k ≤ N
            total -= mu[k] * (root - 1);            // −μ(k)·(count of a^k in [2,N])
        }
        return total;
    }
}
```

**Time:** O(log N · log² N) (nth-root per exponent) · **Space:** O(log N)

**Insight.** A number is a perfect power iff it is a `k`-th power for some *prime* `k`, and Möbius inclusion–exclusion over exponents `−Σ μ(k)(⌊N^{1/k}⌋−1)` corrects for numbers counted under multiple exponents (like `64`) — the square-free-exponent weighting is exactly what prevents triple-counting.

---
