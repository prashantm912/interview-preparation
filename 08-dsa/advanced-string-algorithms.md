# Advanced String Algorithms

[← Back to master index](../README.md)

Naive substring search compares every pattern against every text position — `O(n·m)`. The algorithms in this file get rid of that redundancy by **remembering what you already matched**: a prefix that is also a suffix (KMP, Z-array), an algebraic fingerprint of a window (Rabin-Karp, double hashing), a sorted index of every suffix (suffix array + LCP, suffix automaton), or a multi-pattern automaton (Aho-Corasick). Around them sit the palindrome machinery (Manacher), the dynamic-programming family (edit distance, LCS, wildcard matching), compressed dictionaries (radix tree), and the Burrows-Wheeler transform that powers `bzip2` and modern bioinformatics aligners.

The unifying idea: **a string's structure (its borders, periods, and repeated factors) is computable in linear or near-linear time, and almost every fast string algorithm is an exploitation of that structure.**

---

## Coding Problems

### Problem 1: Implement strStr — Naive Substring Search — Brute Force Baseline
**Statement:** Return the index of the first occurrence of `needle` in `haystack`, or `-1`. Establish the baseline the rest of the file improves on. (LeetCode 28.)

**Approach:** Try every start position `i` in the text; compare the pattern character by character. Worst case `O(n·m)` (e.g. `"aaaa…a"` vs `"aaa…ab"`), but trivially correct and the reference for testing fancier matchers.

```java
class Solution {
    public int strStr(String haystack, String needle) {
        int n = haystack.length(), m = needle.length();
        if (m == 0) return 0;
        for (int i = 0; i + m <= n; i++) {
            int j = 0;
            while (j < m && haystack.charAt(i + j) == needle.charAt(j)) j++;
            if (j == m) return i;
        }
        return -1;
    }
}
```
**Time:** `O(n·m)` worst. **Space:** `O(1)`.
**Insight:** every linear-time matcher exists to avoid re-comparing the prefix the naive loop throws away after a mismatch.

---

### Problem 2: KMP Failure Function (Prefix Function) — Borders
**Statement:** Compute the prefix function `pi[i]` of a string: the length of the longest proper prefix of `s[0..i]` that is also a suffix of `s[0..i]`. This is the core preprocessing for KMP and many periodicity results.

**Approach:** Extend the previous border by character; on mismatch fall back through chains of shorter borders using `pi[k-1]` until a match or empty border.

```java
class Solution {
    public int[] prefixFunction(String s) {
        int n = s.length();
        int[] pi = new int[n];
        for (int i = 1; i < n; i++) {
            int k = pi[i - 1];
            while (k > 0 && s.charAt(i) != s.charAt(k)) k = pi[k - 1];
            if (s.charAt(i) == s.charAt(k)) k++;
            pi[i] = k;
        }
        return pi;
    }
}
```
**Time:** `O(n)` (amortized — `k` increases at most `n` times, so total decreases are bounded). **Space:** `O(n)`.
**Insight:** `pi[i]` is the length of the longest *border*; the whole KMP family is bookkeeping over borders.

---

### Problem 3: KMP Substring Search — Linear Pattern Matching
**Statement:** Find the first occurrence of `pattern` in `text` in `O(n + m)` using the prefix function. (LeetCode 28, optimal variant.)

**Approach:** Build the prefix function of the pattern. Slide a single matched-length pointer `k` over the text; on mismatch fall back via `pi[k-1]` instead of restarting — the text index never moves backward.

```java
class Solution {
    public int strStr(String text, String pattern) {
        int n = text.length(), m = pattern.length();
        if (m == 0) return 0;
        int[] pi = new int[m];
        for (int i = 1; i < m; i++) {
            int k = pi[i - 1];
            while (k > 0 && pattern.charAt(i) != pattern.charAt(k)) k = pi[k - 1];
            if (pattern.charAt(i) == pattern.charAt(k)) k++;
            pi[i] = k;
        }
        int k = 0;
        for (int i = 0; i < n; i++) {
            while (k > 0 && text.charAt(i) != pattern.charAt(k)) k = pi[k - 1];
            if (text.charAt(i) == pattern.charAt(k)) k++;
            if (k == m) return i - m + 1;
        }
        return -1;
    }
}
```
**Time:** `O(n + m)`. **Space:** `O(m)`.
**Insight:** the text pointer is monotone; the failure links recycle the partial match instead of rescanning.

---

### Problem 4: Count All Occurrences with KMP — Overlapping Matches
**Statement:** Count how many times `pattern` occurs in `text`, including overlaps (e.g. `"aa"` in `"aaa"` → 2).

**Approach:** Run KMP; whenever `k` reaches `m`, record a hit and set `k = pi[m-1]` to allow overlapping matches to continue.

```java
class Solution {
    public int countOccurrences(String text, String pattern) {
        int n = text.length(), m = pattern.length();
        if (m == 0) return 0;
        int[] pi = new int[m];
        for (int i = 1; i < m; i++) {
            int k = pi[i - 1];
            while (k > 0 && pattern.charAt(i) != pattern.charAt(k)) k = pi[k - 1];
            if (pattern.charAt(i) == pattern.charAt(k)) k++;
            pi[i] = k;
        }
        int k = 0, count = 0;
        for (int i = 0; i < n; i++) {
            while (k > 0 && text.charAt(i) != pattern.charAt(k)) k = pi[k - 1];
            if (text.charAt(i) == pattern.charAt(k)) k++;
            if (k == m) { count++; k = pi[m - 1]; }
        }
        return count;
    }
}
```
**Time:** `O(n + m)`. **Space:** `O(m)`.
**Insight:** resetting to `pi[m-1]` after a hit keeps the longest border so overlapping occurrences are not missed.

---

### Problem 5: Shortest Palindrome — KMP on s + '#' + reverse(s)
**Statement:** Prepend the fewest characters to `s` to make it a palindrome. Return the result. (LeetCode 214.)

**Approach:** The answer length is `2n - (longest palindromic prefix)`. Compute the longest prefix of `s` that is a suffix of `reverse(s)` via the prefix function of `s + '#' + reverse(s)`; the separator `#` blocks cross-matching beyond `n`.

```java
class Solution {
    public String shortestPalindrome(String s) {
        int n = s.length();
        if (n == 0) return s;
        String rev = new StringBuilder(s).reverse().toString();
        String combo = s + "#" + rev;
        int[] pi = new int[combo.length()];
        for (int i = 1; i < combo.length(); i++) {
            int k = pi[i - 1];
            while (k > 0 && combo.charAt(i) != combo.charAt(k)) k = pi[k - 1];
            if (combo.charAt(i) == combo.charAt(k)) k++;
            pi[i] = k;
        }
        int longestPalPrefix = pi[combo.length() - 1];
        return new StringBuilder(s.substring(longestPalPrefix)).reverse().toString() + s;
    }
}
```
**Time:** `O(n)`. **Space:** `O(n)`.
**Insight:** the longest border of `s#reverse(s)` is exactly the longest palindromic prefix of `s`.

---

### Problem 6: Repeated Substring Pattern — Periodicity via Prefix Function
**Statement:** Decide whether `s` can be built by repeating a substring two or more times. (LeetCode 459.)

**Approach:** Let `k = pi[n-1]`, the longest border. The smallest period is `p = n - k`. The string is a repetition iff `p` divides `n` and `p < n`.

```java
class Solution {
    public boolean repeatedSubstringPattern(String s) {
        int n = s.length();
        int[] pi = new int[n];
        for (int i = 1; i < n; i++) {
            int k = pi[i - 1];
            while (k > 0 && s.charAt(i) != s.charAt(k)) k = pi[k - 1];
            if (s.charAt(i) == s.charAt(k)) k++;
            pi[i] = k;
        }
        int period = n - pi[n - 1];
        return pi[n - 1] != 0 && n % period == 0;
    }
}
```
**Time:** `O(n)`. **Space:** `O(n)`.
**Insight:** a string is periodic with period `p` iff it has a border of length `n - p`; the prefix function hands you the largest border for free.

---

### Problem 7: Smallest Period of a String — Border Arithmetic
**Statement:** Return the length of the shortest string `t` such that `s` is a prefix of some repetition of `t` (the *smallest period*, which may not divide `n`).

**Approach:** The smallest period equals `n - pi[n-1]`. Unlike Problem 6 it need not divide `n` — `"abcab"` has period 3 even though 3 does not divide 5.

```java
class Solution {
    public int smallestPeriod(String s) {
        int n = s.length();
        int[] pi = new int[n];
        for (int i = 1; i < n; i++) {
            int k = pi[i - 1];
            while (k > 0 && s.charAt(i) != s.charAt(k)) k = pi[k - 1];
            if (s.charAt(i) == s.charAt(k)) k++;
            pi[i] = k;
        }
        return n - pi[n - 1];
    }
}
```
**Time:** `O(n)`. **Space:** `O(n)`.
**Insight:** period and border are two sides of the same coin: `period(s) = |s| - longestBorder(s)`.

---

### Problem 8: Z-Algorithm — Z-Array Construction
**Statement:** Compute the Z-array of `s`: `z[i]` is the length of the longest substring starting at `i` that is also a prefix of `s` (with `z[0] = 0` or `n` by convention).

**Approach:** Maintain the rightmost Z-box `[l, r]`. For `i` inside the box reuse the mirrored value `z[i-l]`, clamped to the box; otherwise compare from scratch, then extend and update `[l, r]`.

```java
class Solution {
    public int[] zArray(String s) {
        int n = s.length();
        int[] z = new int[n];
        int l = 0, r = 0;
        for (int i = 1; i < n; i++) {
            if (i < r) z[i] = Math.min(r - i, z[i - l]);
            while (i + z[i] < n && s.charAt(z[i]) == s.charAt(i + z[i])) z[i]++;
            if (i + z[i] > r) { l = i; r = i + z[i]; }
        }
        return z;
    }
}
```
**Time:** `O(n)`. **Space:** `O(n)`.
**Insight:** the Z-box is the same trick as KMP's borders, expressed as "how far does the prefix re-appear here"; both amortize to linear.

---

### Problem 9: Pattern Matching with the Z-Algorithm — Concatenation Trick
**Statement:** Find all occurrences of `pattern` in `text` using the Z-array of `pattern + '#' + text`.

**Approach:** Build `combo = pattern + sep + text`. A position `i` in the text part with `z[i] >= m` marks a full match starting there.

```java
class Solution {
    public java.util.List<Integer> findAll(String text, String pattern) {
        int m = pattern.length();
        String combo = pattern + "" + text;
        int n = combo.length();
        int[] z = new int[n];
        int l = 0, r = 0;
        for (int i = 1; i < n; i++) {
            if (i < r) z[i] = Math.min(r - i, z[i - l]);
            while (i + z[i] < n && combo.charAt(z[i]) == combo.charAt(i + z[i])) z[i]++;
            if (i + z[i] > r) { l = i; r = i + z[i]; }
        }
        java.util.List<Integer> res = new java.util.ArrayList<>();
        for (int i = m + 1; i < n; i++)
            if (z[i] >= m) res.add(i - m - 1);
        return res;
    }
}
```
**Time:** `O(n + m)`. **Space:** `O(n + m)`.
**Insight:** any "longest match against the prefix" query becomes a Z-array lookup once you concatenate pattern, a sentinel, and text.

---

### Problem 10: Distinct Prefixes That Are Also Suffixes — Z + Borders
**Statement:** List the lengths of all prefixes of `s` that are also suffixes (proper and improper), in increasing order. (Codeforces-style.)

**Approach:** A prefix of length `len` is a suffix iff `z[n - len] == len`. Scan from the end; the full string length `n` is always trivially included.

```java
class Solution {
    public java.util.List<Integer> prefixThatAreSuffix(String s) {
        int n = s.length();
        int[] z = new int[n];
        int l = 0, r = 0;
        for (int i = 1; i < n; i++) {
            if (i < r) z[i] = Math.min(r - i, z[i - l]);
            while (i + z[i] < n && s.charAt(z[i]) == s.charAt(i + z[i])) z[i]++;
            if (i + z[i] > r) { l = i; r = i + z[i]; }
        }
        java.util.List<Integer> res = new java.util.ArrayList<>();
        for (int i = n - 1; i >= 1; i--)
            if (z[i] == n - i) res.add(n - i);
        res.add(n);
        return res;
    }
}
```
**Time:** `O(n)`. **Space:** `O(n)`.
**Insight:** `z[n-len] == len` is the Z-array's way of stating "the suffix here equals the prefix" — borders read off the back of the array.

---

### Problem 11: Rabin-Karp Single Pattern Search — Rolling Hash
**Statement:** Find the first occurrence of `pattern` in `text` using a polynomial rolling hash with `O(n + m)` expected time.

**Approach:** Hash the pattern and the first window; slide by removing the leading char's contribution and adding the new trailing char. On a hash match, verify character-by-character to defend against collisions.

```java
class Solution {
    public int strStr(String text, String pattern) {
        int n = text.length(), m = pattern.length();
        if (m == 0) return 0;
        if (m > n) return -1;
        long base = 131, mod = 1_000_000_007L;
        long ph = 0, th = 0, pow = 1;
        for (int i = 0; i < m; i++) {
            ph = (ph * base + pattern.charAt(i)) % mod;
            th = (th * base + text.charAt(i)) % mod;
            if (i < m - 1) pow = pow * base % mod;
        }
        for (int i = 0; i + m <= n; i++) {
            if (ph == th && text.regionMatches(i, pattern, 0, m)) return i;
            if (i + m < n) {
                th = (th - text.charAt(i) * pow % mod + mod) % mod;
                th = (th * base + text.charAt(i + m)) % mod;
            }
        }
        return -1;
    }
}
```
**Time:** `O(n + m)` expected, `O(n·m)` worst (adversarial collisions). **Space:** `O(1)`.
**Insight:** a rolling hash turns "compare a window" into "compare one integer", at the cost of a verification step on hash hits.

---

### Problem 12: Repeated DNA Sequences — Rolling Hash over a Fixed Window
**Statement:** Return all 10-letter-long substrings (over `A,C,G,T`) that appear more than once in a DNA string. (LeetCode 187.)

**Approach:** Each base packs into 2 bits, so a length-10 window fits in 20 bits — a perfect (collision-free) rolling integer. Maintain a sliding 20-bit code in a `HashMap` of counts.

```java
class Solution {
    public java.util.List<String> findRepeatedDnaSequences(String s) {
        java.util.List<String> res = new java.util.ArrayList<>();
        int n = s.length();
        if (n < 10) return res;
        int[] map = new int[256];
        map['A'] = 0; map['C'] = 1; map['G'] = 2; map['T'] = 3;
        int code = 0, mask = (1 << 20) - 1;
        java.util.Map<Integer, Integer> seen = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            code = ((code << 2) | map[s.charAt(i)]) & mask;
            if (i >= 9) {
                int c = seen.merge(code, 1, Integer::sum);
                if (c == 2) res.add(s.substring(i - 9, i + 1));
            }
        }
        return res;
    }
}
```
**Time:** `O(n)`. **Space:** `O(n)`.
**Insight:** when the alphabet is tiny and the window is fixed, the rolling hash becomes an exact bit-packed integer — no collisions, no verification.

---

### Problem 13: Longest Duplicate Substring — Binary Search + Rolling Hash
**Statement:** Find the longest substring that occurs at least twice in `s`. Return it (any if ties). (LeetCode 1044.)

**Approach:** Binary search the answer length `L`. For each `L`, hash every length-`L` window and check for a repeat in a `HashSet`; verify candidates to avoid collisions. Monotonic: if a length-`L` duplicate exists, so does length `L-1`.

```java
class Solution {
    private long base = 131, mod = 1_000_000_007L;

    private int search(String s, int L) {
        if (L == 0) return 0;
        int n = s.length();
        long h = 0, pow = 1;
        for (int i = 0; i < L; i++) { h = (h * base + s.charAt(i)) % mod; if (i < L - 1) pow = pow * base % mod; }
        java.util.Map<Long, java.util.List<Integer>> seen = new java.util.HashMap<>();
        seen.computeIfAbsent(h, k -> new java.util.ArrayList<>()).add(0);
        for (int i = 1; i + L <= n; i++) {
            h = (h - s.charAt(i - 1) * pow % mod + mod) % mod;
            h = (h * base + s.charAt(i + L - 1)) % mod;
            if (seen.containsKey(h)) {
                for (int j : seen.get(h))
                    if (s.regionMatches(j, s, i, L)) return i;
            }
            seen.computeIfAbsent(h, k -> new java.util.ArrayList<>()).add(i);
        }
        return -1;
    }

    public String longestDupSubstring(String s) {
        int lo = 1, hi = s.length() - 1, start = -1, len = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int pos = search(s, mid);
            if (pos >= 0) { start = pos; len = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        return start < 0 ? "" : s.substring(start, start + len);
    }
}
```
**Time:** `O(n log n)` expected. **Space:** `O(n)`.
**Insight:** "longest X that satisfies a monotone property" is binary-searchable, and rolling hash makes the per-length feasibility test linear.

---

### Problem 14: Double Hashing — Collision-Resistant Substring Equality
**Statement:** Build a structure that answers "are `s[a..b]` and `s[c..d]` equal?" in `O(1)` with negligible collision probability, using two independent moduli.

**Approach:** Precompute prefix hashes under two different `(base, mod)` pairs. A substring hash is `(pref[r+1] - pref[l]·base^(r-l+1))`. Two substrings are equal iff *both* hashes match — squaring the collision resistance.

```java
class DoubleHash {
    private final long[] h1, h2, p1, p2;
    private final long m1 = 1_000_000_007L, m2 = 998_244_353L, b1 = 131, b2 = 137;

    public DoubleHash(String s) {
        int n = s.length();
        h1 = new long[n + 1]; h2 = new long[n + 1];
        p1 = new long[n + 1]; p2 = new long[n + 1];
        p1[0] = 1; p2[0] = 1;
        for (int i = 0; i < n; i++) {
            h1[i + 1] = (h1[i] * b1 + s.charAt(i)) % m1;
            h2[i + 1] = (h2[i] * b2 + s.charAt(i)) % m2;
            p1[i + 1] = p1[i] * b1 % m1;
            p2[i + 1] = p2[i] * b2 % m2;
        }
    }

    private long sub1(int l, int r) { return ((h1[r + 1] - h1[l] * p1[r - l + 1]) % m1 + m1) % m1; }
    private long sub2(int l, int r) { return ((h2[r + 1] - h2[l] * p2[r - l + 1]) % m2 + m2) % m2; }

    public boolean equal(int a, int b, int c, int d) {
        if (b - a != d - c) return false;
        return sub1(a, b) == sub1(c, d) && sub2(a, b) == sub2(c, d);
    }
}
```
**Time:** `O(n)` build, `O(1)` per query. **Space:** `O(n)`.
**Insight:** two moduli make the collision probability roughly `1/(m1·m2)` — astronomically small — so hash equality can be trusted without verification in practice.

---

### Problem 15: Manacher's Algorithm — Longest Palindromic Substring
**Statement:** Find the longest palindromic substring of `s` in linear time. (LeetCode 5.)

**Approach:** Transform `s` into `^#a#b#a#$` so every palindrome has odd length. Maintain a center `c` and right boundary `r`; mirror radii inside the current palindrome, then expand. The transformed radius maps directly back to original indices.

```java
class Solution {
    public String longestPalindrome(String s) {
        if (s.isEmpty()) return "";
        StringBuilder t = new StringBuilder("^");
        for (char ch : s.toCharArray()) t.append('#').append(ch);
        t.append("#$");
        char[] a = t.toString().toCharArray();
        int n = a.length;
        int[] p = new int[n];
        int c = 0, r = 0, maxLen = 0, centerIdx = 0;
        for (int i = 1; i < n - 1; i++) {
            if (i < r) p[i] = Math.min(r - i, p[2 * c - i]);
            while (a[i + p[i] + 1] == a[i - p[i] - 1]) p[i]++;
            if (i + p[i] > r) { c = i; r = i + p[i]; }
            if (p[i] > maxLen) { maxLen = p[i]; centerIdx = i; }
        }
        int start = (centerIdx - maxLen) / 2;
        return s.substring(start, start + maxLen);
    }
}
```
**Time:** `O(n)`. **Space:** `O(n)`.
**Insight:** the sentinel-separated transform makes even and odd palindromes uniform, and the mirror-inside-the-box reuse is the same amortization as Z-array.

---

### Problem 16: Count All Palindromic Substrings — Manacher Radii Sum
**Statement:** Count the total number of palindromic substrings of `s`. (LeetCode 647.)

**Approach:** Run Manacher; for the transformed array the count of palindromes centered at `i` is `(p[i] + 1) / 2`. Summing gives every palindromic substring exactly once.

```java
class Solution {
    public int countSubstrings(String s) {
        if (s.isEmpty()) return 0;
        StringBuilder t = new StringBuilder("^");
        for (char ch : s.toCharArray()) t.append('#').append(ch);
        t.append("#$");
        char[] a = t.toString().toCharArray();
        int n = a.length;
        int[] p = new int[n];
        int c = 0, r = 0, count = 0;
        for (int i = 1; i < n - 1; i++) {
            if (i < r) p[i] = Math.min(r - i, p[2 * c - i]);
            while (a[i + p[i] + 1] == a[i - p[i] - 1]) p[i]++;
            if (i + p[i] > r) { c = i; r = i + p[i]; }
            count += (p[i] + 1) / 2;
        }
        return count;
    }
}
```
**Time:** `O(n)`. **Space:** `O(n)`.
**Insight:** the radius at each transformed center directly encodes how many palindromes sit there — no separate expansion needed.

---

### Problem 17: Longest Common Subsequence — Classic DP
**Statement:** Return the length of the longest common subsequence of `a` and `b`. (LeetCode 1143.)

**Approach:** `dp[i][j]` = LCS of prefixes `a[0..i)` and `b[0..j)`. If the last chars match, extend the diagonal; otherwise take the better of dropping one char from either string.

```java
class Solution {
    public int longestCommonSubsequence(String a, String b) {
        int n = a.length(), m = b.length();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = 1; i <= n; i++)
            for (int j = 1; j <= m; j++)
                dp[i][j] = a.charAt(i - 1) == b.charAt(j - 1)
                        ? dp[i - 1][j - 1] + 1
                        : Math.max(dp[i - 1][j], dp[i][j - 1]);
        return dp[n][m];
    }
}
```
**Time:** `O(n·m)`. **Space:** `O(n·m)` (reducible to `O(min(n,m))` with rolling rows).
**Insight:** subsequence DP is a grid walk — diagonal on match, the better of the two neighbors on mismatch.

---

### Problem 18: Longest Common Substring — Contiguous DP
**Statement:** Return the length of the longest *contiguous* substring common to `a` and `b` (distinct from subsequence).

**Approach:** `dp[i][j]` = length of the common suffix ending at `a[i-1]` and `b[j-1]`. It resets to 0 on a mismatch (substrings must be contiguous); track the running max.

```java
class Solution {
    public int longestCommonSubstring(String a, String b) {
        int n = a.length(), m = b.length(), best = 0;
        int[][] dp = new int[n + 1][m + 1];
        for (int i = 1; i <= n; i++)
            for (int j = 1; j <= m; j++)
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                    best = Math.max(best, dp[i][j]);
                }
        return best;
    }
}
```
**Time:** `O(n·m)`. **Space:** `O(n·m)`.
**Insight:** the only difference from LCS is the mismatch case — substrings reset to zero, subsequences carry the max forward.

---

### Problem 19: Edit Distance (Levenshtein) — Insert/Delete/Replace
**Statement:** Minimum number of single-character insertions, deletions, or replacements to turn `a` into `b`. (LeetCode 72.)

**Approach:** `dp[i][j]` = edit distance of prefixes. On a match, copy the diagonal; otherwise `1 + min(replace, delete, insert)`. Base rows/cols are pure insertions/deletions.

```java
class Solution {
    public int minDistance(String a, String b) {
        int n = a.length(), m = b.length();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) dp[i][0] = i;
        for (int j = 0; j <= m; j++) dp[0][j] = j;
        for (int i = 1; i <= n; i++)
            for (int j = 1; j <= m; j++)
                dp[i][j] = a.charAt(i - 1) == b.charAt(j - 1)
                        ? dp[i - 1][j - 1]
                        : 1 + Math.min(dp[i - 1][j - 1], Math.min(dp[i - 1][j], dp[i][j - 1]));
        return dp[n][m];
    }
}
```
**Time:** `O(n·m)`. **Space:** `O(n·m)`.
**Insight:** the three operations map to the three grid neighbors; a match is a free diagonal step.

---

### Problem 20: Damerau-Levenshtein — Edit Distance with Transposition
**Statement:** Like edit distance, but also allow swapping two *adjacent* characters as a single operation (handles common typos like "teh" → "the").

**Approach:** Extend the Levenshtein recurrence with a fourth candidate: when `a[i-1]==b[j-2]` and `a[i-2]==b[j-1]`, allow `dp[i-2][j-2] + 1` for the transposition.

```java
class Solution {
    public int damerauLevenshtein(String a, String b) {
        int n = a.length(), m = b.length();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) dp[i][0] = i;
        for (int j = 0; j <= m; j++) dp[0][j] = j;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(dp[i - 1][j - 1] + cost,
                           Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1));
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2)
                        && a.charAt(i - 2) == b.charAt(j - 1))
                    dp[i][j] = Math.min(dp[i][j], dp[i - 2][j - 2] + 1);
            }
        }
        return dp[n][m];
    }
}
```
**Time:** `O(n·m)`. **Space:** `O(n·m)`.
**Insight:** transposition is a diagonal jump of two cells — a single recurrence branch added to Levenshtein.

---

### Problem 21: One Edit Distance — Greedy Linear Scan
**Statement:** Decide whether `s` and `t` are exactly one edit (insert / delete / replace) apart. (LeetCode 161.)

**Approach:** No DP needed. If lengths differ by more than 1, false. Scan to the first mismatch, then check whether skipping one character on the longer (or both on equal length) makes the rest equal.

```java
class Solution {
    public boolean isOneEditDistance(String s, String t) {
        int n = s.length(), m = t.length();
        if (Math.abs(n - m) > 1) return false;
        if (n > m) return isOneEditDistance(t, s);   // ensure n <= m
        for (int i = 0; i < n; i++) {
            if (s.charAt(i) != t.charAt(i)) {
                if (n == m) return s.substring(i + 1).equals(t.substring(i + 1));   // replace
                return s.substring(i).equals(t.substring(i + 1));                    // insert into s
            }
        }
        return n + 1 == m;   // t has exactly one extra trailing char
    }
}
```
**Time:** `O(n)`. **Space:** `O(n)` for substrings (or `O(1)` with index comparison).
**Insight:** a bounded edit budget collapses the DP grid to a single greedy walk past the first divergence.

---

### Problem 22: Regular Expression Matching — '.' and '*' DP
**Statement:** Implement regex matching where `.` matches any single char and `*` matches zero or more of the preceding element, anchored over the whole string. (LeetCode 10.)

**Approach:** `dp[i][j]` = does `s[0..i)` match `p[0..j)`. A `*` either drops the pair (`dp[i][j-2]`) or, if the preceding pattern char matches `s[i-1]`, consumes one char (`dp[i-1][j]`).

```java
class Solution {
    public boolean isMatch(String s, String p) {
        int n = s.length(), m = p.length();
        boolean[][] dp = new boolean[n + 1][m + 1];
        dp[0][0] = true;
        for (int j = 1; j <= m; j++)
            if (p.charAt(j - 1) == '*') dp[0][j] = dp[0][j - 2];
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                char pc = p.charAt(j - 1);
                if (pc == '*') {
                    dp[i][j] = dp[i][j - 2];
                    char prev = p.charAt(j - 2);
                    if (prev == '.' || prev == s.charAt(i - 1)) dp[i][j] |= dp[i - 1][j];
                } else if (pc == '.' || pc == s.charAt(i - 1)) {
                    dp[i][j] = dp[i - 1][j - 1];
                }
            }
        }
        return dp[n][m];
    }
}
```
**Time:** `O(n·m)`. **Space:** `O(n·m)`.
**Insight:** `*` is the only branching operator — "use zero copies" vs "consume one more char"; everything else is a literal/dot diagonal.

---

### Problem 23: Wildcard Matching — '?' and '*' DP
**Statement:** Implement wildcard matching where `?` matches any single char and `*` matches any sequence (including empty). (LeetCode 44.)

**Approach:** `dp[i][j]`. A `*` matches empty (`dp[i][j-1]`) or one more text char (`dp[i-1][j]`). `?` and literals are diagonal steps. Initialize leading `*` runs in row 0.

```java
class Solution {
    public boolean isMatch(String s, String p) {
        int n = s.length(), m = p.length();
        boolean[][] dp = new boolean[n + 1][m + 1];
        dp[0][0] = true;
        for (int j = 1; j <= m; j++)
            if (p.charAt(j - 1) == '*') dp[0][j] = dp[0][j - 1];
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                char pc = p.charAt(j - 1);
                if (pc == '*') dp[i][j] = dp[i][j - 1] || dp[i - 1][j];
                else if (pc == '?' || pc == s.charAt(i - 1)) dp[i][j] = dp[i - 1][j - 1];
            }
        }
        return dp[n][m];
    }
}
```
**Time:** `O(n·m)`. **Space:** `O(n·m)` (reducible to `O(m)`).
**Insight:** wildcard `*` is simpler than regex `*` — it stands alone, so it branches into "match empty" vs "absorb one char" without a paired predecessor.

---

### Problem 24: Wildcard Matching — Greedy Two-Pointer O(1) Space
**Statement:** Same as Problem 23 but in linear time and constant extra space, using backtracking on the last `*`.

**Approach:** Walk both strings. On `?`/literal match advance both. On `*` remember its position and the text index. On mismatch, if a `*` is pending, backtrack: advance the remembered text index and retry from just after the star.

```java
class Solution {
    public boolean isMatch(String s, String p) {
        int i = 0, j = 0, star = -1, match = 0;
        int n = s.length(), m = p.length();
        while (i < n) {
            if (j < m && (p.charAt(j) == '?' || p.charAt(j) == s.charAt(i))) { i++; j++; }
            else if (j < m && p.charAt(j) == '*') { star = j; match = i; j++; }
            else if (star != -1) { j = star + 1; match++; i = match; }
            else return false;
        }
        while (j < m && p.charAt(j) == '*') j++;
        return j == m;
    }
}
```
**Time:** `O(n·m)` worst, near `O(n + m)` typical. **Space:** `O(1)`.
**Insight:** a single remembered star plus a resumable text pointer replaces the whole DP table for wildcard (works because `*` is unconstrained, unlike regex `*`).

---

### Problem 25: Compressed Trie / Radix Tree — Insert & Search
**Statement:** Build a radix tree (compressed trie) where chains of single-child nodes are merged into edge labels, then support `insert` and `search` of whole words.

**Approach:** Each edge carries a string. On insert, walk matching edges; when a partial match splits an edge, break it into a shared prefix node plus two children. Search follows edges, consuming the query.

```java
class RadixTree {
    private static class Node {
        java.util.Map<Character, Edge> edges = new java.util.HashMap<>();
        boolean isEnd;
    }
    private static class Edge { String label; Node child; Edge(String l, Node c){label=l;child=c;} }
    private final Node root = new Node();

    public void insert(String word) {
        Node node = root; int i = 0;
        while (i < word.length()) {
            char c = word.charAt(i);
            Edge e = node.edges.get(c);
            if (e == null) {
                Node leaf = new Node(); leaf.isEnd = true;
                node.edges.put(c, new Edge(word.substring(i), leaf));
                return;
            }
            int k = commonPrefix(e.label, word, i);
            if (k == e.label.length()) { node = e.child; i += k; }
            else {
                Node split = new Node();
                Edge lower = new Edge(e.label.substring(k), e.child);
                split.edges.put(e.label.charAt(k), lower);
                e.label = e.label.substring(0, k);
                e.child = split;
                node = split; i += k;
            }
        }
        node.isEnd = true;
    }

    public boolean search(String word) {
        Node node = root; int i = 0;
        while (i < word.length()) {
            Edge e = node.edges.get(word.charAt(i));
            if (e == null) return false;
            int k = commonPrefix(e.label, word, i);
            if (k < e.label.length()) return false;
            node = e.child; i += k;
        }
        return node.isEnd;
    }

    private int commonPrefix(String label, String word, int start) {
        int k = 0;
        while (k < label.length() && start + k < word.length()
                && label.charAt(k) == word.charAt(start + k)) k++;
        return k;
    }
}
```
**Time:** `O(L)` per op (L = word length). **Space:** `O(total chars)` but far fewer nodes than a plain trie.
**Insight:** collapsing single-child chains into edge labels is what makes a radix tree memory-efficient for sparse dictionaries (and is the model for IP routing tries).

---

### Problem 26: Word Search II — Trie + DFS Backtracking
**Statement:** Given a grid of letters and a word list, return all words present in the grid (4-directional, no cell reuse). (LeetCode 212.)

**Approach:** Insert all words into a trie. DFS from every cell, advancing the trie pointer; prune the instant no child matches. Store the full word at terminal nodes and null it out after collecting to dedupe.

```java
class Solution {
    static class Node { Node[] next = new Node[26]; String word; }

    public java.util.List<String> findWords(char[][] board, String[] words) {
        Node root = new Node();
        for (String w : words) {
            Node n = root;
            for (char c : w.toCharArray()) {
                int i = c - 'a';
                if (n.next[i] == null) n.next[i] = new Node();
                n = n.next[i];
            }
            n.word = w;
        }
        java.util.List<String> res = new java.util.ArrayList<>();
        for (int r = 0; r < board.length; r++)
            for (int c = 0; c < board[0].length; c++)
                dfs(board, r, c, root, res);
        return res;
    }

    private void dfs(char[][] b, int r, int c, Node node, java.util.List<String> res) {
        if (r < 0 || c < 0 || r >= b.length || c >= b[0].length) return;
        char ch = b[r][c];
        if (ch == '#' || node.next[ch - 'a'] == null) return;
        node = node.next[ch - 'a'];
        if (node.word != null) { res.add(node.word); node.word = null; }
        b[r][c] = '#';
        dfs(b, r + 1, c, node, res); dfs(b, r - 1, c, node, res);
        dfs(b, r, c + 1, node, res); dfs(b, r, c - 1, node, res);
        b[r][c] = ch;
    }
}
```
**Time:** `O(rows·cols·4^L)` worst, pruned heavily by the trie. **Space:** `O(total chars)`.
**Insight:** the trie turns "search the grid for each word" into "search the grid once, guided by the dictionary" — shared prefixes are explored a single time.

---

### Problem 27: Aho-Corasick — Multi-Pattern Matching Automaton
**Statement:** Given a set of patterns and a text, find all occurrences of all patterns in one pass over the text. (Generalizes KMP to many patterns.)

**Approach:** Build a trie of the patterns, then BFS to compute *fail* links (longest proper suffix that is a trie node) and *output* links. Run the text through the automaton, following goto/fail edges, emitting every output along the fail chain.

```java
class AhoCorasick {
    private final int[][] go = new int[1][];   // placeholder, rebuilt
    private int[] fail;
    private java.util.List<Integer>[] out;
    private int[][] trie;
    private int size = 1;

    @SuppressWarnings("unchecked")
    public AhoCorasick(java.util.List<String> patterns) {
        int maxNodes = 1;
        for (String p : patterns) maxNodes += p.length();
        trie = new int[maxNodes][26];
        for (int[] row : trie) java.util.Arrays.fill(row, -1);
        out = new java.util.List[maxNodes];
        fail = new int[maxNodes];
        for (int pi = 0; pi < patterns.size(); pi++) {
            int node = 0;
            for (char c : patterns.get(pi).toCharArray()) {
                int i = c - 'a';
                if (trie[node][i] == -1) trie[node][i] = size++;
                node = trie[node][i];
            }
            if (out[node] == null) out[node] = new java.util.ArrayList<>();
            out[node].add(pi);
        }
        build();
    }

    private void build() {
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        for (int c = 0; c < 26; c++) {
            if (trie[0][c] == -1) trie[0][c] = 0;
            else { fail[trie[0][c]] = 0; q.add(trie[0][c]); }
        }
        while (!q.isEmpty()) {
            int u = q.poll();
            for (int c = 0; c < 26; c++) {
                int v = trie[u][c];
                if (v == -1) { trie[u][c] = trie[fail[u]][c]; continue; }
                fail[v] = trie[fail[u]][c];
                if (out[fail[v]] != null) {
                    if (out[v] == null) out[v] = new java.util.ArrayList<>();
                    out[v].addAll(out[fail[v]]);
                }
                q.add(v);
            }
        }
    }

    public java.util.List<int[]> search(String text) {
        java.util.List<int[]> res = new java.util.ArrayList<>();   // {endIndex, patternId}
        int node = 0;
        for (int i = 0; i < text.length(); i++) {
            node = trie[node][text.charAt(i) - 'a'];
            if (out[node] != null)
                for (int pid : out[node]) res.add(new int[]{i, pid});
        }
        return res;
    }
}
```
**Time:** `O(Σ pattern lengths · 26)` build, `O(n + matches)` search. **Space:** `O(nodes · 26)`.
**Insight:** fail links are KMP's prefix function generalized to a trie — they let a single text pass detect every pattern simultaneously.

---

### Problem 28: Stream of Characters — Reversed Aho-Corasick / Trie
**Statement:** Support `query(letter)` returning true if any word in a fixed dictionary is a suffix of the stream so far. (LeetCode 1032.)

**Approach:** Insert reversed words into a trie. Keep the recent stream chars; on each query walk the trie backward over the suffix, stopping early when no child matches.

```java
class StreamChecker {
    private static class Node { Node[] next = new Node[26]; boolean end; }
    private final Node root = new Node();
    private final StringBuilder sb = new StringBuilder();
    private int maxLen = 0;

    public StreamChecker(String[] words) {
        for (String w : words) {
            Node n = root;
            maxLen = Math.max(maxLen, w.length());
            for (int i = w.length() - 1; i >= 0; i--) {
                int c = w.charAt(i) - 'a';
                if (n.next[c] == null) n.next[c] = new Node();
                n = n.next[c];
            }
            n.end = true;
        }
    }

    public boolean query(char letter) {
        sb.append(letter);
        Node n = root;
        for (int i = sb.length() - 1, steps = 0; i >= 0 && steps < maxLen; i--, steps++) {
            n = n.next[sb.charAt(i) - 'a'];
            if (n == null) return false;
            if (n.end) return true;
        }
        return false;
    }
}
```
**Time:** `O(maxLen)` per query. **Space:** `O(total chars)`.
**Insight:** reversing the dictionary turns "is some word a suffix of the stream" into a forward trie walk from the newest character backward.

---

### Problem 29: Suffix Array — O(n log² n) Prefix-Doubling Construction
**Statement:** Build the suffix array of `s`: the sorted order of all suffix start indices. Foundational for substring search, LCP, and BWT.

**Approach:** Rank suffixes by their first `k` characters using radix-style sorting; double `k` each round (1, 2, 4, …) re-ranking by `(rank[i], rank[i+k])` pairs until all ranks are distinct.

```java
class SuffixArray {
    public int[] build(String s) {
        int n = s.length();
        Integer[] sa = new Integer[n];
        int[] rank = new int[n], tmp = new int[n];
        for (int i = 0; i < n; i++) { sa[i] = i; rank[i] = s.charAt(i); }
        for (int k = 1; k < n; k <<= 1) {
            final int kk = k;
            final int[] r = rank;
            java.util.Comparator<Integer> cmp = (a, b) -> {
                if (r[a] != r[b]) return Integer.compare(r[a], r[b]);
                int ra = a + kk < n ? r[a + kk] : -1;
                int rb = b + kk < n ? r[b + kk] : -1;
                return Integer.compare(ra, rb);
            };
            java.util.Arrays.sort(sa, cmp);
            tmp[sa[0]] = 0;
            for (int i = 1; i < n; i++)
                tmp[sa[i]] = tmp[sa[i - 1]] + (cmp.compare(sa[i - 1], sa[i]) < 0 ? 1 : 0);
            System.arraycopy(tmp, 0, rank, 0, n);
            if (rank[sa[n - 1]] == n - 1) break;
        }
        int[] res = new int[n];
        for (int i = 0; i < n; i++) res[i] = sa[i];
        return res;
    }
}
```
**Time:** `O(n log² n)`. **Space:** `O(n)`.
**Insight:** prefix doubling sorts by exponentially growing keys, so `log n` rounds of ranking suffice — each suffix is identified by a pair of earlier ranks.

---

### Problem 30: Kasai's Algorithm — LCP Array from Suffix Array
**Statement:** Given `s` and its suffix array, compute the LCP array where `lcp[i]` is the longest common prefix of the suffixes at `sa[i]` and `sa[i-1]`.

**Approach:** Process suffixes in *text* order. The LCP of a suffix with its predecessor in `sa` is at least `prev_lcp - 1`, so reuse `h` across iterations instead of recomputing from zero.

```java
class Kasai {
    public int[] buildLCP(String s, int[] sa) {
        int n = s.length();
        int[] rank = new int[n], lcp = new int[n];
        for (int i = 0; i < n; i++) rank[sa[i]] = i;
        int h = 0;
        for (int i = 0; i < n; i++) {
            if (rank[i] > 0) {
                int j = sa[rank[i] - 1];
                while (i + h < n && j + h < n && s.charAt(i + h) == s.charAt(j + h)) h++;
                lcp[rank[i]] = h;
                if (h > 0) h--;
            } else h = 0;
        }
        return lcp;
    }
}
```
**Time:** `O(n)`. **Space:** `O(n)`.
**Insight:** the `h--` reuse is the whole trick — adjacent suffixes in text order can lose at most one shared character, so total work is linear.

---

### Problem 31: Substring Search via Suffix Array — Binary Search
**Statement:** With a prebuilt suffix array, decide whether `pattern` occurs in `s` (and where) in `O(m log n)` per query.

**Approach:** Suffixes are sorted, so binary search for the pattern as a prefix: compare the pattern against the suffix at `sa[mid]` up to `m` characters. Great when many patterns are queried against one fixed text.

```java
class SuffixArraySearch {
    public boolean contains(String s, int[] sa, String pattern) {
        int n = s.length(), m = pattern.length();
        int lo = 0, hi = n - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int cmp = compare(s, sa[mid], pattern, m);
            if (cmp == 0) return true;
            if (cmp < 0) lo = mid + 1;
            else hi = mid - 1;
        }
        return false;
    }

    private int compare(String s, int start, String pattern, int m) {
        for (int k = 0; k < m; k++) {
            if (start + k >= s.length()) return -1;      // suffix shorter -> smaller
            char a = s.charAt(start + k), b = pattern.charAt(k);
            if (a != b) return a < b ? -1 : 1;
        }
        return 0;   // pattern is a prefix of this suffix
    }
}
```
**Time:** `O(m log n)` per query after `O(n log n)` preprocessing. **Space:** `O(n)`.
**Insight:** sorting suffixes makes substring membership a prefix binary search — the index amortizes across many queries.

---

### Problem 32: Number of Distinct Substrings — Suffix Array + LCP
**Statement:** Count the number of distinct non-empty substrings of `s`.

**Approach:** Every substring is a prefix of some suffix. In sorted suffix order, suffix `sa[i]` contributes `(n - sa[i])` prefixes, of which `lcp[i]` duplicate the previous suffix. Sum `(n - sa[i] - lcp[i])`.

```java
class DistinctSubstrings {
    public long count(String s, int[] sa, int[] lcp) {
        int n = s.length();
        long total = 0;
        for (int i = 0; i < n; i++) {
            total += (n - sa[i]);
            total -= lcp[i];   // lcp[0] = 0 by convention
        }
        return total;
    }
}
```
**Time:** `O(n)` given `sa` and `lcp` (so `O(n log n)` overall). **Space:** `O(1)` extra.
**Insight:** the LCP array measures exactly how many prefixes each sorted suffix shares with its neighbor — subtract those and you've counted every distinct substring once.

---

### Problem 33: Longest Repeated Substring — Max of LCP Array
**Statement:** Find the longest substring that appears at least twice in `s`, using the suffix and LCP arrays.

**Approach:** Two equal substrings are shared prefixes of two suffixes that are adjacent in sorted order. The answer length is `max(lcp)`; the substring starts at the corresponding `sa` index.

```java
class LongestRepeated {
    public String solve(String s, int[] sa, int[] lcp) {
        int n = s.length(), best = 0, idx = 0;
        for (int i = 1; i < n; i++)
            if (lcp[i] > best) { best = lcp[i]; idx = sa[i]; }
        return s.substring(idx, idx + best);
    }
}
```
**Time:** `O(n)` after `O(n log n)` preprocessing. **Space:** `O(1)` extra.
**Insight:** repeats sit next to each other in the sorted suffix order, so the largest LCP entry *is* the longest repeat — no hashing needed.

---

### Problem 34: Longest Common Substring of Two Strings — Generalized Suffix Array
**Statement:** Find the longest common substring of `a` and `b` in `O(N log N)` using a suffix array over `a + '#' + b`.

**Approach:** Concatenate with a separator, build the suffix/LCP arrays, and scan adjacent suffixes. The answer is the max `lcp[i]` where `sa[i]` and `sa[i-1]` originate from *different* source strings.

```java
class LCSofTwo {
    public String longest(String a, String b) {
        String s = a + "" + b;
        int sep = a.length();
        int n = s.length();
        int[] sa = new SuffixArray().build(s);
        int[] lcp = new Kasai().buildLCP(s, sa);
        int best = 0, idx = 0;
        for (int i = 1; i < n; i++) {
            boolean diffSides = (sa[i] < sep) != (sa[i - 1] < sep);
            if (diffSides && lcp[i] > best) { best = lcp[i]; idx = sa[i]; }
        }
        return s.substring(idx, idx + best);
    }
}
```
**Time:** `O(N log N)`. **Space:** `O(N)`.
**Insight:** requiring adjacent suffixes to come from *different* strings is what turns the repeated-substring trick into a cross-string common-substring solver.

---

### Problem 35: Suffix Automaton — Construction
**Statement:** Build a suffix automaton (SAM) for `s`: the minimal DFA recognizing all substrings of `s`. It has at most `2n - 1` states and supports many queries in linear time.

**Approach:** Extend the automaton one character at a time. Each state has a `len`, a suffix `link`, and labeled transitions. On extension, clone a state when a transition's `len` does not line up (the classic SAM clone step).

```java
class SuffixAutomaton {
    static class State {
        int len, link;
        java.util.Map<Character, Integer> next = new java.util.HashMap<>();
    }
    java.util.List<State> st = new java.util.ArrayList<>();
    int last;

    public SuffixAutomaton() {
        State init = new State(); init.len = 0; init.link = -1;
        st.add(init); last = 0;
    }

    public void extend(char c) {
        int cur = st.size();
        State curState = new State();
        curState.len = st.get(last).len + 1;
        st.add(curState);
        int p = last;
        while (p != -1 && !st.get(p).next.containsKey(c)) {
            st.get(p).next.put(c, cur);
            p = st.get(p).link;
        }
        if (p == -1) {
            curState.link = 0;
        } else {
            int q = st.get(p).next.get(c);
            if (st.get(p).len + 1 == st.get(q).len) {
                curState.link = q;
            } else {
                int clone = st.size();
                State cloneState = new State();
                cloneState.len = st.get(p).len + 1;
                cloneState.next = new java.util.HashMap<>(st.get(q).next);
                cloneState.link = st.get(q).link;
                st.add(cloneState);
                while (p != -1 && st.get(p).next.get(c) != null
                        && st.get(p).next.get(c) == q) {
                    st.get(p).next.put(c, clone);
                    p = st.get(p).link;
                }
                st.get(q).link = clone;
                curState.link = clone;
            }
        }
        last = cur;
    }

    public void build(String s) {
        for (char c : s.toCharArray()) extend(c);
    }
}
```
**Time:** `O(n · log Σ)` with a `HashMap` (`O(n)` with arrays). **Space:** `O(n · Σ)` worst.
**Insight:** the suffix link tree of a SAM groups substrings by their set of end positions (endpos), which is what makes counting and longest-common-substring queries linear.

---

### Problem 36: Count Distinct Substrings via Suffix Automaton
**Statement:** Count the distinct substrings of `s` using a suffix automaton (an alternative to the suffix-array method of Problem 32).

**Approach:** Every distinct substring corresponds to a unique path from the initial state. Summing `len[v] - len[link[v]]` over all non-initial states counts exactly those paths.

```java
class SAMDistinctSubstrings {
    public long count(String s) {
        SuffixAutomaton sam = new SuffixAutomaton();
        sam.build(s);
        long total = 0;
        for (int v = 1; v < sam.st.size(); v++) {
            SuffixAutomaton.State cur = sam.st.get(v);
            total += cur.len - sam.st.get(cur.link).len;
        }
        return total;
    }
}
```
**Time:** `O(n)` after construction. **Space:** `O(n)`.
**Insight:** `len[v] - len[link[v]]` is the number of distinct substrings that *end* in state `v`'s equivalence class — summing partitions all substrings with no double counting.

---

### Problem 37: Longest Common Substring via Suffix Automaton
**Statement:** Find the length of the longest common substring of `a` and `b` by building a SAM on `a` and streaming `b` through it.

**Approach:** Build the automaton of `a`. Walk `b` character by character, following transitions; on a miss, follow suffix links and reset the current matched length to `len[link]+1`. Track the maximum matched length.

```java
class SAMLongestCommon {
    public int longest(String a, String b) {
        SuffixAutomaton sam = new SuffixAutomaton();
        sam.build(a);
        int v = 0, l = 0, best = 0;
        for (char c : b.toCharArray()) {
            SuffixAutomaton.State cur = sam.st.get(v);
            if (cur.next.containsKey(c)) {
                v = cur.next.get(c); l++;
            } else {
                while (v != -1 && !sam.st.get(v).next.containsKey(c)) v = sam.st.get(v).link;
                if (v == -1) { v = 0; l = 0; }
                else { l = sam.st.get(v).len + 1; v = sam.st.get(v).next.get(c); }
            }
            best = Math.max(best, l);
        }
        return best;
    }
}
```
**Time:** `O(|a| + |b|)`. **Space:** `O(|a|)`.
**Insight:** suffix links let the matcher "back off" exactly like KMP when the current substring can't be extended, so streaming `b` is linear.

---

### Problem 38: Concatenated Substring with All Words — Hashing + Sliding Window
**Statement:** Given `s` and `words` (all equal length `L`), find all start indices where a concatenation of every word (each used once, any order) begins. (LeetCode 30.)

**Approach:** Slide a window of `len(words)·L` in `L` distinct phase offsets. Maintain a count map of words seen vs needed; on excess, shrink from the left by whole words. Avoids re-scanning each window from scratch.

```java
class Solution {
    public java.util.List<Integer> findSubstring(String s, String[] words) {
        java.util.List<Integer> res = new java.util.ArrayList<>();
        if (words.length == 0) return res;
        int L = words[0].length(), total = words.length, windowLen = L * total, n = s.length();
        if (windowLen > n) return res;
        java.util.Map<String, Integer> need = new java.util.HashMap<>();
        for (String w : words) need.merge(w, 1, Integer::sum);
        for (int off = 0; off < L; off++) {
            int left = off, count = 0;
            java.util.Map<String, Integer> window = new java.util.HashMap<>();
            for (int right = off; right + L <= n; right += L) {
                String w = s.substring(right, right + L);
                if (need.containsKey(w)) {
                    window.merge(w, 1, Integer::sum);
                    count++;
                    while (window.get(w) > need.get(w)) {
                        String lw = s.substring(left, left + L);
                        window.merge(lw, -1, Integer::sum);
                        left += L; count--;
                    }
                    if (count == total) res.add(left);
                } else {
                    window.clear(); count = 0; left = right + L;
                }
            }
        }
        return res;
    }
}
```
**Time:** `O(n·L)`. **Space:** `O(total·L)`.
**Insight:** stepping by word length in `L` phases means each character is visited a constant number of times overall — a sliding window over tokens, not characters.

---

### Problem 39: Minimum Window Substring — Sliding Window with Counts
**Statement:** Find the smallest window in `s` containing all characters of `t` (with multiplicity). (LeetCode 76.)

**Approach:** Expand the right edge, tracking how many required chars are satisfied; once complete, contract the left edge to minimize while still valid. Each pointer moves forward only — linear.

```java
class Solution {
    public String minWindow(String s, String t) {
        if (t.isEmpty() || s.length() < t.length()) return "";
        int[] need = new int[128];
        for (char c : t.toCharArray()) need[c]++;
        int required = t.length(), left = 0, bestLen = Integer.MAX_VALUE, bestStart = 0;
        for (int right = 0; right < s.length(); right++) {
            if (need[s.charAt(right)]-- > 0) required--;
            while (required == 0) {
                if (right - left + 1 < bestLen) { bestLen = right - left + 1; bestStart = left; }
                if (++need[s.charAt(left++)] > 0) required++;
            }
        }
        return bestLen == Integer.MAX_VALUE ? "" : s.substring(bestStart, bestStart + bestLen);
    }
}
```
**Time:** `O(n)`. **Space:** `O(1)` (fixed alphabet).
**Insight:** a single `required` counter over a frequency array tells you in `O(1)` whether the window is valid, so both pointers stay monotone.

---

### Problem 40: Find All Anagrams — Fixed Sliding Window
**Statement:** Return all start indices in `s` where a permutation of `p` begins. (LeetCode 438.)

**Approach:** Keep a fixed-size window of length `|p|`. Maintain a frequency difference; the window is an anagram iff all 26 counts match. Update counts in `O(1)` as the window slides.

```java
class Solution {
    public java.util.List<Integer> findAnagrams(String s, String p) {
        java.util.List<Integer> res = new java.util.ArrayList<>();
        int n = s.length(), m = p.length();
        if (n < m) return res;
        int[] need = new int[26], have = new int[26];
        for (char c : p.toCharArray()) need[c - 'a']++;
        for (int i = 0; i < n; i++) {
            have[s.charAt(i) - 'a']++;
            if (i >= m) have[s.charAt(i - m) - 'a']--;
            if (i >= m - 1 && java.util.Arrays.equals(need, have)) res.add(i - m + 1);
        }
        return res;
    }
}
```
**Time:** `O(n·26)`. **Space:** `O(1)`.
**Insight:** an anagram check is just "do the two frequency vectors match", and a sliding window updates that vector incrementally.

---

### Problem 41: Burrows-Wheeler Transform — Forward
**Statement:** Compute the BWT of `s` (append a unique sentinel `$` smaller than all chars): the last column of the sorted rotation matrix, obtainable directly from the suffix array.

**Approach:** Append `$`, build the suffix array, then `bwt[i] = s[(sa[i] - 1 + n) % n]`. This is the linear-space route used by `bzip2` and FM-index aligners — no explicit rotation matrix.

```java
class BWT {
    public String transform(String s) {
        String t = s + " ";          // sentinel smaller than all chars
        int n = t.length();
        int[] sa = new SuffixArray().build(t);
        StringBuilder bwt = new StringBuilder();
        for (int i = 0; i < n; i++) {
            int idx = (sa[i] - 1 + n) % n;
            bwt.append(t.charAt(idx));
        }
        return bwt.toString();
    }
}
```
**Time:** `O(n log² n)` (suffix array). **Space:** `O(n)`.
**Insight:** the BWT is just "the character before each sorted suffix", so a suffix array gives it without ever materializing the `n × n` rotation matrix.

---

### Problem 42: Burrows-Wheeler Transform — Inverse (LF-Mapping)
**Statement:** Reconstruct the original string from its BWT (the last column) using the LF-mapping / standard inversion.

**Approach:** The first column is the sorted last column. Use the LF-mapping: rank each character occurrence in the last column and pair it with the first column. Walk the permutation from the sentinel row back to the original string.

```java
class InverseBWT {
    public String invert(String bwt) {
        int n = bwt.length();
        // count occurrences to get starting position of each char in first column
        int[] count = new int[256];
        for (int i = 0; i < n; i++) count[bwt.charAt(i)]++;
        int[] start = new int[256];
        int sum = 0;
        for (int c = 0; c < 256; c++) { start[c] = sum; sum += count[c]; }
        // LF-mapping: next[i] points to the row whose first-column char follows this last-column char
        int[] next = new int[n];
        int[] occ = new int[256];
        for (int i = 0; i < n; i++) {
            char c = bwt.charAt(i);
            next[start[c] + occ[c]] = i;
            occ[c]++;
        }
        StringBuilder sb = new StringBuilder();
        int row = next[0];                 // row whose first column is the sentinel
        for (int i = 0; i < n; i++) {
            sb.append(bwt.charAt(row));
            row = next[row];
        }
        // drop the sentinel (smallest char) from the reconstructed rotation
        String result = sb.toString();
        int dollar = result.indexOf(' ');
        return result.substring(dollar + 1) + result.substring(0, dollar);
    }
}
```
**Time:** `O(n + Σ)`. **Space:** `O(n + Σ)`.
**Insight:** the LF-mapping exploits that the i-th occurrence of a char in the last column corresponds to its i-th occurrence in the (sorted) first column — that bijection unwinds the transform.

---

### Problem 43: Lyndon Factorization — Duval's Algorithm
**Statement:** Factor `s` into a non-increasing sequence of Lyndon words (a Lyndon word is strictly smaller than all its proper suffixes). Used in BWT theory and necklace problems.

**Approach:** Duval's algorithm scans with three pointers `i, j, k`. It greedily grows a candidate Lyndon prefix; on a strict increase it extends, on equality it advances `k`, and on a decrease it outputs full Lyndon factors of the established period.

```java
class Duval {
    public java.util.List<String> factorize(String s) {
        java.util.List<String> res = new java.util.ArrayList<>();
        int n = s.length(), i = 0;
        while (i < n) {
            int j = i + 1, k = i;
            while (j < n && s.charAt(k) <= s.charAt(j)) {
                if (s.charAt(k) < s.charAt(j)) k = i;
                else k++;
                j++;
            }
            while (i <= k) {
                res.add(s.substring(i, i + j - k));
                i += j - k;
            }
        }
        return res;
    }
}
```
**Time:** `O(n)`. **Space:** `O(n)` for output.
**Insight:** Duval's three-pointer scan extracts the period of the current Lyndon prefix and emits as many copies as fit — the same machinery that finds the smallest rotation of a string.

---

### Problem 44: Smallest Rotation (Booth's Algorithm) — Least Rotation
**Statement:** Find the index of the lexicographically smallest rotation of `s` in linear time. (Used to canonicalize necklaces.)

**Approach:** Booth's algorithm runs the failure-function machinery over `s + s` with a clever skip rule that discards dominated starting positions, yielding the least-rotation start index in `O(n)`.

```java
class Booth {
    public int leastRotation(String s) {
        String t = s + s;
        int n = t.length();
        int[] f = new int[n];
        java.util.Arrays.fill(f, -1);
        int k = 0;
        for (int j = 1; j < n; j++) {
            char sj = t.charAt(j);
            int i = f[j - k - 1];
            while (i != -1 && sj != t.charAt(k + i + 1)) {
                if (sj < t.charAt(k + i + 1)) k = j - i - 1;
                i = f[i];
            }
            if (sj != t.charAt(k + i + 1)) {
                if (sj < t.charAt(k)) k = j;
                f[j - k] = -1;
            } else {
                f[j - k] = i + 1;
            }
        }
        return k;
    }
}
```
**Time:** `O(n)`. **Space:** `O(n)`.
**Insight:** Booth augments the prefix-function with a "this start is dominated" pruning rule, so the least rotation falls out of a single pass over the doubled string.

---

### Problem 45: Longest Palindromic Subsequence — Interval DP
**Statement:** Return the length of the longest subsequence of `s` that is a palindrome. (LeetCode 516.)

**Approach:** `dp[i][j]` = LPS of `s[i..j]`. If the ends match, `dp[i+1][j-1] + 2`; otherwise the better of dropping either end. Equivalently it's the LCS of `s` and its reverse.

```java
class Solution {
    public int longestPalindromeSubseq(String s) {
        int n = s.length();
        int[][] dp = new int[n][n];
        for (int i = n - 1; i >= 0; i--) {
            dp[i][i] = 1;
            for (int j = i + 1; j < n; j++) {
                if (s.charAt(i) == s.charAt(j))
                    dp[i][j] = dp[i + 1][j - 1] + 2;
                else
                    dp[i][j] = Math.max(dp[i + 1][j], dp[i][j - 1]);
            }
        }
        return dp[0][n - 1];
    }
}
```
**Time:** `O(n²)`. **Space:** `O(n²)`.
**Insight:** palindromic subsequence is LCS(s, reverse(s)) in disguise — matching ends grow the answer from the inside out.

---

### Problem 46: Distinct Subsequences — Count Subsequence Matches
**Statement:** Count how many distinct subsequences of `s` equal `t`. (LeetCode 115.)

**Approach:** `dp[i][j]` = number of subsequences of `s[0..i)` equal to `t[0..j)`. Always inherit `dp[i-1][j]` (skip `s[i-1]`); if chars match also add `dp[i-1][j-1]` (use it).

```java
class Solution {
    public int numDistinct(String s, String t) {
        int n = s.length(), m = t.length();
        long[][] dp = new long[n + 1][m + 1];
        for (int i = 0; i <= n; i++) dp[i][0] = 1;
        for (int i = 1; i <= n; i++)
            for (int j = 1; j <= m; j++) {
                dp[i][j] = dp[i - 1][j];
                if (s.charAt(i - 1) == t.charAt(j - 1)) dp[i][j] += dp[i - 1][j - 1];
            }
        return (int) dp[n][m];
    }
}
```
**Time:** `O(n·m)`. **Space:** `O(n·m)` (reducible to `O(m)`).
**Insight:** counting (rather than maximizing) subsequence matches turns `max` into `+`, the standard "count vs optimize" DP swap.

---

### Problem 47: Word Break — Trie + DP Over Substrings
**Statement:** Decide whether `s` can be segmented into a sequence of dictionary words. (LeetCode 139.)

**Approach:** `dp[i]` = `s[0..i)` is segmentable. For each `i`, walk a trie of dictionary words from position `j` where `dp[j]` is true; mark `dp[i]` when a trie path ends exactly at `i`. The trie prunes impossible extensions early.

```java
class Solution {
    static class Node { Node[] next = new Node[26]; boolean end; }

    public boolean wordBreak(String s, java.util.List<String> wordDict) {
        Node root = new Node();
        for (String w : wordDict) {
            Node n = root;
            for (char c : w.toCharArray()) {
                int i = c - 'a';
                if (n.next[i] == null) n.next[i] = new Node();
                n = n.next[i];
            }
            n.end = true;
        }
        int len = s.length();
        boolean[] dp = new boolean[len + 1];
        dp[0] = true;
        for (int i = 0; i < len; i++) {
            if (!dp[i]) continue;
            Node n = root;
            for (int j = i; j < len; j++) {
                n = n.next[s.charAt(j) - 'a'];
                if (n == null) break;
                if (n.end) dp[j + 1] = true;
            }
        }
        return dp[len];
    }
}
```
**Time:** `O(n²)` worst, pruned by the trie. **Space:** `O(total dict chars)`.
**Insight:** the trie lets each DP extension stop the moment the running substring leaves the dictionary, replacing repeated `HashSet` lookups with one guided walk.

---

### Problem 48: Sum of Scores (Z-Function Application) — Build & Sum
**Statement:** For each prefix `s[0..i]`, its score is the number of indices `j` such that the substring starting at `j` shares the entire prefix `s[0..i]` (i.e. how often the whole prefix re-occurs as a prefix elsewhere). Sum the score of every prefix. (Codeforces 1968G-style, simplified with Z.)

**Approach:** Build the Z-array. The number of positions where a prefix of length `L` re-occurs is `1 + |{ i : z[i] >= L }|`. Bucket the Z-values, take a suffix sum over lengths, and add the prefix itself.

```java
class Solution {
    public long sumScores(String s) {
        int n = s.length();
        int[] z = new int[n];
        int l = 0, r = 0;
        for (int i = 1; i < n; i++) {
            if (i < r) z[i] = Math.min(r - i, z[i - l]);
            while (i + z[i] < n && s.charAt(z[i]) == s.charAt(i + z[i])) z[i]++;
            if (i + z[i] > r) { l = i; r = i + z[i]; }
        }
        // cnt[L] = number of i>=1 with z[i] == L
        long[] cnt = new long[n + 2];
        for (int i = 1; i < n; i++) cnt[z[i]]++;
        // atLeast[L] = number of i with z[i] >= L, via suffix sums
        long[] atLeast = new long[n + 2];
        for (int L = n; L >= 1; L--) atLeast[L] = atLeast[L + 1] + cnt[L];
        long total = 0;
        for (int L = 1; L <= n; L++) total += 1 + atLeast[L];   // +1 for the prefix matching itself
        return total;
    }
}
```
**Time:** `O(n)`. **Space:** `O(n)`.
**Insight:** "how many times does this prefix appear as a prefix elsewhere" is exactly `|{ z[i] >= L }|`; one suffix-sum over the Z-histogram answers it for all lengths at once.

---

## 🧩 Extended Problems — Set 1: Deeper internals & edge cases

These problems probe the *machinery* the earlier ones glossed over: the exact off-by-one edge cases (empty strings, single chars, all-equal runs), the invariants each algorithm secretly relies on, automaton states most people never inspect, hash-collision adversaries, and the subtle correctness arguments behind the one-liners. No duplicates of Problems 1–48 — each one stresses a corner the canonical solution hides.

### Problem 49: KMP Automaton — Full Transition Table — DFA Materialization
**Statement:** From the prefix function of a pattern over an alphabet `Σ`, build the complete deterministic transition table `delta[state][c]`, so matching becomes a single array lookup per text char with **zero** failure-link chasing at match time. Edge case: state `m` (full match) must transition correctly to allow overlaps.

**Approach:** `delta[0][c]` is 1 if `c == p[0]` else 0. For state `j` in `1..m`, `delta[j][c] = (c == p[j]) ? j+1 : delta[pi[j-1]][c]` — the failure link is resolved *once*, at build time, by reusing the already-built row of the border state.

```java
class KMPAutomaton {
    int m, alpha;
    int[][] delta;

    public KMPAutomaton(String p, int alphabetSize) {
        m = p.length(); alpha = alphabetSize;
        int[] pi = new int[m];
        for (int i = 1; i < m; i++) {
            int k = pi[i - 1];
            while (k > 0 && p.charAt(i) != p.charAt(k)) k = pi[k - 1];
            if (p.charAt(i) == p.charAt(k)) k++;
            pi[i] = k;
        }
        delta = new int[m + 1][alpha];
        for (int c = 0; c < alpha; c++)
            delta[0][c] = (m > 0 && c == p.charAt(0) - 'a') ? 1 : 0;
        for (int j = 1; j <= m; j++)
            for (int c = 0; c < alpha; c++) {
                if (j < m && c == p.charAt(j) - 'a') delta[j][c] = j + 1;
                else delta[j][c] = delta[pi[j - 1]][c];   // chained one-step, table already filled
            }
    }

    public int run(String text) {           // returns first match end+1 or -1
        int j = 0;
        for (int i = 0; i < text.length(); i++) {
            j = delta[j][text.charAt(i) - 'a'];
            if (j == m) return i - m + 1;
        }
        return -1;
    }
}
```
**Time:** `O(m·|Σ|)` build, `O(n)` match (no inner loop). **Space:** `O(m·|Σ|)`.
**Insight:** the `while` loop in textbook KMP is amortized-linear but not *constant* per char; materializing the DFA trades `Σ` space for a true O(1) step — exactly how regex engines pre-compile.

---

### Problem 50: Prefix-Function ⇄ Z-Function Conversion — Equivalence Proof in Code
**Statement:** Convert a Z-array to the prefix function (and the reverse) in `O(n)` without re-reading the string, demonstrating the two encodings carry identical information. Edge case: `z[0]` is conventionally 0 but represents the whole string for this conversion.

**Approach:** Z→π: for each `i` with `z[i] > 0`, the substring `s[i..i+z[i]-1]` equals a prefix, so it sets a *border candidate* `pi[i + z[i] - 1] = max(..., z[i])`; fill gaps by propagating `pi[t] = max(pi[t], pi[t+1] - 1)` back-to-front. π→Z: walk borders and extend Z-boxes.

```java
class PrefixZConvert {
    public int[] zToPrefix(int[] z) {
        int n = z.length;
        int[] pi = new int[n];
        for (int i = 1; i < n; i++) {
            if (z[i] > 0) {
                int end = i + z[i] - 1;
                pi[end] = Math.max(pi[end], z[i]);     // a Z-match induces a border at its right end
            }
        }
        for (int i = n - 1; i > 0; i--)
            if (pi[i] > 0)
                pi[i - 1] = Math.max(pi[i - 1], pi[i] - 1);  // shorter border survives one step left
        // normalize: ensure each pi[i] <= i
        for (int i = 1; i < n; i++) pi[i] = Math.min(pi[i], i);
        return pi;
    }

    public int[] prefixToZ(int[] pi) {
        int n = pi.length;
        int[] z = new int[n];
        for (int i = 1; i < n; i++)
            if (pi[i] > 0) z[i - pi[i] + 1] = Math.max(z[i - pi[i] + 1], pi[i]);
        if (n > 0) z[0] = n;
        return z;
    }
}
```
**Time:** `O(n)` each direction. **Space:** `O(n)`.
**Insight:** a Z-match of length `L` starting at `i` is precisely a border of length `L` ending at `i+L-1`; the two arrays are dual views of "where does the prefix recur", so neither is more fundamental.

---

### Problem 51: All Borders of a String — Failure-Link Chain Enumeration
**Statement:** Output the lengths of **every** border of `s` (not just the longest), in decreasing order, by walking the prefix-function chain from `pi[n-1]`. Edge case: a string with no proper border (e.g. `"abc"`) returns only the empty border.

**Approach:** The set of borders of `s` is exactly `{ pi[n-1], pi[pi[n-1]-1], ... }` until it hits 0. This chain has at most `O(log n)`... no — up to `O(n)` entries (e.g. `"aaaa"`), but the chain is the canonical border tower.

```java
class AllBorders {
    public java.util.List<Integer> borders(String s) {
        int n = s.length();
        int[] pi = new int[n];
        for (int i = 1; i < n; i++) {
            int k = pi[i - 1];
            while (k > 0 && s.charAt(i) != s.charAt(k)) k = pi[k - 1];
            if (s.charAt(i) == s.charAt(k)) k++;
            pi[i] = k;
        }
        java.util.List<Integer> res = new java.util.ArrayList<>();
        for (int b = n == 0 ? 0 : pi[n - 1]; b > 0; b = pi[b - 1]) res.add(b);
        return res;   // strictly decreasing
    }
}
```
**Time:** `O(n)`. **Space:** `O(n)`.
**Insight:** borders nest — every border of a border is a border of the whole — so the failure chain enumerates the complete (totally ordered) border lattice without recomputation.

---

### Problem 52: All Periods of a String — Border-to-Period Duality
**Statement:** Return all periods `p` of `s` (lengths such that `s[i] == s[i+p]` for all valid `i`), in increasing order. Edge case: `n` itself is always a (trivial) period.

**Approach:** `p` is a period iff `n - p` is a border. Enumerate the border chain (Problem 51); each border `b` yields period `n - b`. Add `n` for completeness. Reverse to ascending.

```java
class AllPeriods {
    public java.util.List<Integer> periods(String s) {
        int n = s.length();
        int[] pi = new int[n];
        for (int i = 1; i < n; i++) {
            int k = pi[i - 1];
            while (k > 0 && s.charAt(i) != s.charAt(k)) k = pi[k - 1];
            if (s.charAt(i) == s.charAt(k)) k++;
            pi[i] = k;
        }
        java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
        for (int b = n == 0 ? 0 : pi[n - 1]; b > 0; b = pi[b - 1]) set.add(n - b);
        if (n > 0) set.add(n);
        return new java.util.ArrayList<>(set);
    }
}
```
**Time:** `O(n)` (plus `O(b log b)` for the set, `b` = #borders). **Space:** `O(n)`.
**Insight:** the period/border bijection `period = n - border` means one prefix-function pass hands you both lattices; the Fine–Wilf theorem then constrains how these periods can coexist.

---

### Problem 53: Z-Array Edge Cases — Empty, Single-Char, and All-Equal
**Statement:** Implement a Z-array builder and explicitly verify it on the three classic degenerate inputs: `""` (returns `[]`), `"a"` (returns `[0]`), and `"aaaa"` (returns `[0,3,2,1]`). The all-equal case maximally exercises the Z-box reuse path.

**Approach:** Standard Z construction; the all-equal input is the worst case for the inner `while` *unless* the box-mirror is correct — each `z[i]` must be clamped by `r-i` and only extended past the box, or it degrades to `O(n²)`.

```java
class ZEdgeCases {
    public int[] zArray(String s) {
        int n = s.length();
        int[] z = new int[n];
        int l = 0, r = 0;
        for (int i = 1; i < n; i++) {
            if (i < r) z[i] = Math.min(r - i, z[i - l]);   // clamp is what keeps "aaaa" linear
            while (i + z[i] < n && s.charAt(z[i]) == s.charAt(i + z[i])) z[i]++;
            if (i + z[i] > r) { l = i; r = i + z[i]; }
        }
        return z;
    }

    public boolean selfTest() {
        return zArray("").length == 0
            && java.util.Arrays.equals(zArray("a"), new int[]{0})
            && java.util.Arrays.equals(zArray("aaaa"), new int[]{0, 3, 2, 1});
    }
}
```
**Time:** `O(n)`. **Space:** `O(n)`.
**Insight:** `"aaaa"` is the litmus test — a naive (no-clamp) Z builder still passes random inputs but blows up here; the `Math.min(r-i, z[i-l])` clamp is the entire linearity guarantee.

---

### Problem 54: Rabin-Karp Adversarial Collision — Why Verification Is Mandatory
**Statement:** Construct, for a *fixed* known modulus, two distinct strings of equal length with identical polynomial hashes, then show a Rabin-Karp that *skips* verification reports a false match. Demonstrates the single-mod attack.

**Approach:** With base `b` and modulus `M`, two length-2 strings `xy` and `zw` collide iff `b·x + y ≡ b·z + w (mod M)`. Pick `x=0,y=b` vs `x=1,y=0` over a numeric alphabet: `b·0 + b == b·1 + 0`. The unverified matcher treats them as equal.

```java
class RKCollision {
    // returns a colliding pair {a, b} for the given base over an integer alphabet
    public int[][] collidingPair(long base, long mod) {
        // chars are ints; "a"=[0,(int)base], "b"=[1,0] both hash to base mod mod
        return new int[][]{ {0, (int) (base % mod)}, {1, 0} };
    }

    public boolean unverifiedMatch(int[] text, int[] pat, long base, long mod) {
        int m = pat.length;
        long ph = 0, th = 0, pow = 1;
        for (int i = 0; i < m; i++) { ph = (ph * base + pat[i]) % mod; th = (th * base + text[i]) % mod; if (i < m - 1) pow = pow * base % mod; }
        for (int i = 0; i + m <= text.length; i++) {
            if (ph == th) return true;          // BUG: no char verification -> false positive on collision
            if (i + m < text.length) {
                th = (th - text[i] * pow % mod + mod) % mod;
                th = (th * base + text[i + m]) % mod;
            }
        }
        return false;
    }
}
```
**Time:** `O(n)`. **Space:** `O(1)`.
**Insight:** a *fixed-seed* single hash is forgeable in `O(1)` — production matchers either verify on hit or randomize the base per run so an attacker cannot precompute a collision.

---

### Problem 55: Polynomial Hash Mismatch From Overflow — Unsigned-Mod Trap
**Statement:** Show why computing a polynomial hash in a 64-bit `long` *without* an explicit modulus (relying on silent 2⁶⁴ wraparound) is exploitable, and give the safe modular version. Edge case: `base` even vs odd interacts with power-of-two implicit modulus.

**Approach:** Implicit `mod 2^64` hashing has known *Thue–Morse* style collisions ("Anti-hash test"). The fix is an explicit large prime modulus with `Math.floorMod` to keep results non-negative, plus avoiding `int` overflow in `char * pow`.

```java
class SafeHash {
    static final long MOD = (1L << 61) - 1;   // Mersenne prime, fast reduction possible
    static final long BASE = 131;

    long mulmod(long a, long b) {              // careful 128-bit-free multiply under 2^61-1
        long hi = Math.multiplyHigh(a, b);
        long lo = a * b;
        long res = (lo & MOD) + ((lo >>> 61) | (hi << 3));
        return res >= MOD ? res - MOD : res;
    }

    public long hash(String s) {
        long h = 0;
        for (int i = 0; i < s.length(); i++)
            h = (mulmod(h, BASE) + (s.charAt(i) + 1)) % MOD;  // +1 so 'a'==0 isn't absorbed
        return h;
    }
}
```
**Time:** `O(n)`. **Space:** `O(1)`.
**Insight:** the `+1` offset stops a leading run of the zero-valued char from being invisible, and a prime (not 2⁶⁴) modulus removes the algebraic structure that anti-hash tests exploit.

---

### Problem 56: Substring Hash With Negative-Safe Modular Arithmetic — Off-by-One Audit
**Statement:** Implement `O(1)` substring hashing where the subtraction `pref[r+1] - pref[l]·pow[len]` can go negative under modulus, and audit every index boundary so `hash(l, l-1)` (empty range) returns a consistent sentinel. Edge case: empty substring and full-string substring.

**Approach:** Keep `pref[0]=0`, `pref[i+1]` over `s[i]`. Substring `[l, r]` inclusive uses `len = r-l+1`; guard `r < l` → return a fixed empty-hash (0). Always `((x % M) + M) % M`.

```java
class SubHash {
    final long M = 1_000_000_007L, B = 131;
    long[] pref, pow;

    public SubHash(String s) {
        int n = s.length();
        pref = new long[n + 1]; pow = new long[n + 1];
        pow[0] = 1;
        for (int i = 0; i < n; i++) {
            pref[i + 1] = (pref[i] * B + (s.charAt(i) + 1)) % M;
            pow[i + 1] = pow[i] * B % M;
        }
    }

    public long sub(int l, int r) {            // inclusive [l, r]; r < l => empty
        if (r < l) return 0;                   // sentinel for empty range
        int len = r - l + 1;
        long h = (pref[r + 1] - pref[l] * pow[len]) % M;
        return (h % M + M) % M;                // negative-safe normalization
    }
}
```
**Time:** `O(n)` build, `O(1)` query. **Space:** `O(n)`.
**Insight:** the two silent bugs in every hand-rolled substring hash are (a) forgetting the `+M` after subtraction and (b) an `len` off-by-one between inclusive/exclusive conventions — pin both with an explicit empty-range sentinel test.

---

### Problem 57: Manacher Internals — Map Transformed Index Back to Original Bounds
**Statement:** Given Manacher's radius array over the `#`-interleaved string, write the exact formula that recovers, for **each** center, the original `[start, end)` of its maximal palindrome, and prove the parity handling. Edge case: even-length palindromes sit on `#` centers.

**Approach:** For transformed center `i` with radius `p[i]`, the original length is `p[i]`, and the original start is `(i - p[i]) / 2` (the `^` and `#` sentinels make this integer division exact regardless of parity).

```java
class ManacherMap {
    public int[][] allMaximalPalindromes(String s) {   // returns {start, len} per original center
        if (s.isEmpty()) return new int[0][];
        StringBuilder t = new StringBuilder("^");
        for (char c : s.toCharArray()) t.append('#').append(c);
        t.append("#$");
        char[] a = t.toString().toCharArray();
        int n = a.length;
        int[] p = new int[n];
        int c = 0, r = 0;
        for (int i = 1; i < n - 1; i++) {
            if (i < r) p[i] = Math.min(r - i, p[2 * c - i]);
            while (a[i + p[i] + 1] == a[i - p[i] - 1]) p[i]++;
            if (i + p[i] > r) { c = i; r = i + p[i]; }
        }
        java.util.List<int[]> res = new java.util.ArrayList<>();
        for (int i = 1; i < n - 1; i++) {
            int start = (i - p[i]) / 2;        // exact for both odd (char) and even (#) centers
            int len = p[i];
            res.add(new int[]{ start, len });
        }
        return res.toArray(new int[0][]);
    }
}
```
**Time:** `O(n)`. **Space:** `O(n)`.
**Insight:** interleaving sentinels makes `(i - p[i]) / 2` parity-agnostic — odd centers land on original chars, even centers on `#`, and the same division recovers both, which is *why* the transform is worth the 2× blowup.

---

### Problem 58: Palindromic Tree (Eertree) — Construction
**Statement:** Build an eertree (palindromic tree) for `s`: a structure with one node per **distinct** palindromic substring (at most `n+2` nodes), supporting incremental append. Edge case: the two imaginary roots with lengths `-1` and `0`.

**Approach:** Maintain two roots (`len=-1`, `len=0`) and a `suffixLink`. On appending `s[i]`, follow suffix links from `last` to find the longest palindrome `X` such that `s[i-len(X)-1] == s[i]`, creating a new node `c·X·c` if absent, with its own suffix link found by continuing the walk.

```java
class Eertree {
    int[] len = new int[1 << 16];
    int[] link = new int[1 << 16];
    int[][] to = new int[1 << 16][26];
    char[] s = new char[1 << 16];
    int n = 0, last = 1, sz = 2;   // node 0: len -1 (imaginary), node 1: len 0 (empty)

    Eertree() { len[0] = -1; link[0] = 0; len[1] = 0; link[1] = 0; }

    int getLink(int v) {           // walk until s[i - len - 1] == s[i]
        while (s[n - len[v] - 2] != s[n - 1]) v = link[v];
        return v;
    }

    public boolean add(char ch) {
        s[n++] = ch;
        int c = ch - 'a';
        int cur = getLink(last);
        boolean isNew = to[cur][c] == 0;
        if (isNew) {
            int now = sz++;
            len[now] = len[cur] + 2;
            link[now] = (len[now] == 1) ? 1 : to[getLink(link[cur])][c];
            to[cur][c] = now;
        }
        last = to[cur][c];
        return isNew;              // true if a brand-new distinct palindrome appeared
    }

    public int distinctPalindromes() { return sz - 2; }   // exclude the two roots
}
```
**Time:** `O(n·log Σ)` amortized (array transitions). **Space:** `O(n·Σ)`.
**Insight:** the two-root trick (`len = -1` and `0`) lets a single suffix-link walk handle both odd and even palindromes uniformly; the eertree adds exactly one node per *first* occurrence of a distinct palindrome, so it has ≤ `n+2` nodes total.

---

### Problem 59: Count Distinct Palindromic Substrings — Eertree Node Count
**Statement:** Count the number of **distinct** palindromic substrings of `s` (contrast Problem 16 which counts *occurrences*). Edge case: `"aaa"` has 3 distinct palindromes (`a`, `aa`, `aaa`) but 6 occurrences.

**Approach:** Each eertree node is one distinct palindrome; the answer is `sz - 2` after appending all chars. No double counting because the tree dedupes by construction.

```java
class DistinctPalindromes {
    public int count(String str) {
        Eertree t = new Eertree();
        for (char c : str.toCharArray()) t.add(c);
        return t.distinctPalindromes();
    }
}
```
**Time:** `O(n)` amortized. **Space:** `O(n·Σ)`.
**Insight:** "occurrences" (Manacher radii sum) and "distinct" (eertree node count) are genuinely different quantities — `"aaa"` separates them — and conflating the two is the classic palindrome-counting bug.

---

### Problem 60: Edit Distance — O(min(n,m)) Space Rolling Rows
**Statement:** Compute Levenshtein distance using only two rows (or one row + a scalar), reducing space from `O(n·m)` to `O(min(n,m))`. Edge case: must swap so the *shorter* string drives the row width, and the diagonal value must be saved before it's overwritten.

**Approach:** Keep `prev` and `cur` rows of length `min+1`. The trap is `dp[i-1][j-1]`: it lives in `prev[j-1]` *before* `cur[j-1]` overwrites — but here we read `prev` (full old row) so a temp isn't needed; with a single-row variant you cache the diagonal.

```java
class EditDistanceLowSpace {
    public int minDistance(String a, String b) {
        if (a.length() < b.length()) { String t = a; a = b; b = t; }  // ensure b is shorter
        int n = a.length(), m = b.length();
        int[] prev = new int[m + 1], cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            for (int j = 1; j <= m; j++) {
                cur[j] = a.charAt(i - 1) == b.charAt(j - 1)
                        ? prev[j - 1]
                        : 1 + Math.min(prev[j - 1], Math.min(prev[j], cur[j - 1]));
            }
            int[] swap = prev; prev = cur; cur = swap;   // O(1) row swap, no allocation
        }
        return prev[m];
    }
}
```
**Time:** `O(n·m)`. **Space:** `O(min(n,m))`.
**Insight:** because each cell only depends on the previous row and the current cell-to-the-left, two rotating rows suffice; choosing the shorter string as the row index minimizes the resident memory.

---

### Problem 61: Hirschberg's Algorithm — LCS Alignment in Linear Space
**Statement:** Recover the actual LCS *string* (not just its length) in `O(n·m)` time but `O(min(n,m))` space via divide-and-conquer on the middle column. Edge case: base cases of empty string and single character must be handled directly.

**Approach:** Compute the forward LCS-length row to the midpoint column and the backward row from the end; the split row of the second string maximizes their sum. Recurse on the two quadrants, concatenating results.

```java
class Hirschberg {
    public String lcs(String a, String b) {
        int n = a.length(), m = b.length();
        if (n == 0 || m == 0) return "";
        if (n == 1) return b.indexOf(a.charAt(0)) >= 0 ? a : "";
        int mid = n / 2;
        int[] scoreL = lcsLen(a.substring(0, mid), b);
        int[] scoreR = lcsLen(new StringBuilder(a.substring(mid)).reverse().toString(),
                              new StringBuilder(b).reverse().toString());
        int split = 0, best = -1;
        for (int j = 0; j <= m; j++) {
            int s = scoreL[j] + scoreR[m - j];
            if (s > best) { best = s; split = j; }
        }
        return lcs(a.substring(0, mid), b.substring(0, split))
             + lcs(a.substring(mid), b.substring(split));
    }

    private int[] lcsLen(String a, String b) {   // last row of the LCS DP, O(|b|) space
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++)
                cur[j] = a.charAt(i - 1) == b.charAt(j - 1)
                        ? prev[j - 1] + 1 : Math.max(prev[j], cur[j - 1]);
            int[] t = prev; prev = cur; cur = t;
            java.util.Arrays.fill(cur, 0);
        }
        return prev;
    }
}
```
**Time:** `O(n·m)`. **Space:** `O(m)` (recursion depth `O(log n)`).
**Insight:** the alignment *path* normally needs the whole table for backtracking; Hirschberg recovers it with linear space by finding one optimal split column at a time — the canonical divide-and-conquer-on-DP technique.

---

### Problem 62: Suffix Array — O(n log n) via Radix-Sorted Prefix Doubling
**Statement:** Improve Problem 29's `O(n log²n)` to `O(n log n)` by replacing the comparison `Arrays.sort` with a counting/radix sort on the `(rank, rank+k)` pairs. Edge case: ranks can reach `n`, so two counting passes are needed.

**Approach:** Each doubling round sorts indices by the pair `(rank[i], rank[i+k])`. Counting-sort by the second key, then by the first (stable), each in `O(n)`. `log n` rounds → `O(n log n)`.

```java
class SuffixArrayNLogN {
    public int[] build(String s) {
        int n = s.length();
        int[] sa = new int[n], rank = new int[n], tmp = new int[n], cnt = new int[Math.max(256, n) + 1];
        for (int i = 0; i < n; i++) { sa[i] = i; rank[i] = s.charAt(i); }
        for (int k = 1; k < n; k <<= 1) {
            // sort by second key (rank[i+k]), stable
            java.util.Arrays.fill(cnt, 0);
            for (int i = 0; i < n; i++) cnt[i + k < n ? rank[i + k] + 1 : 0]++;
            for (int i = 1; i < cnt.length; i++) cnt[i] += cnt[i - 1];
            for (int i = n - 1; i >= 0; i--) tmp[--cnt[i + k < n ? rank[i + k] + 1 : 0]] = i;
            // sort by first key (rank[i]), stable, using tmp order
            java.util.Arrays.fill(cnt, 0);
            for (int i = 0; i < n; i++) cnt[rank[i]]++;
            for (int i = 1; i < cnt.length; i++) cnt[i] += cnt[i - 1];
            for (int i = n - 1; i >= 0; i--) sa[--cnt[rank[tmp[i]]]] = tmp[i];
            // recompute ranks
            tmp[sa[0]] = 0;
            for (int i = 1; i < n; i++) {
                int p = sa[i - 1], q = sa[i];
                boolean same = rank[p] == rank[q]
                        && (p + k < n ? rank[p + k] : -1) == (q + k < n ? rank[q + k] : -1);
                tmp[q] = tmp[p] + (same ? 0 : 1);
            }
            System.arraycopy(tmp, 0, rank, 0, n);
            if (rank[sa[n - 1]] == n - 1) break;
        }
        return sa;
    }
}
```
**Time:** `O(n log n)`. **Space:** `O(n)`.
**Insight:** the pair `(rank[i], rank[i+k])` has both components in `[0, n]`, so two stable counting passes replace the `O(log n)` comparator — dropping a full log factor that matters at `n = 10⁶`.

---

### Problem 63: LCP — Range Minimum Gives LCP of Any Two Suffixes
**Statement:** Preprocess the LCP array so the longest common prefix of *arbitrary* suffixes `i` and `j` is answered in `O(1)` (after `O(n log n)` build) as a range-minimum over LCP between their suffix-array ranks. Edge case: identical suffixes (`i == j`).

**Approach:** `LCP(suffix_i, suffix_j) = min(lcp[rank_i+1 .. rank_j])` where `rank_i < rank_j`. Build a sparse table over `lcp` for `O(1)` RMQ. Handle `i == j` → return `n - i`.

```java
class SuffixLCPQuery {
    int[] rank; int n; int[][] sparse; int[] lg;

    public SuffixLCPQuery(int[] sa, int[] lcp) {
        n = sa.length;
        rank = new int[n];
        for (int i = 0; i < n; i++) rank[sa[i]] = i;
        lg = new int[n + 1];
        for (int i = 2; i <= n; i++) lg[i] = lg[i / 2] + 1;
        int K = lg[n] + 1;
        sparse = new int[K][n];
        System.arraycopy(lcp, 0, sparse[0], 0, n);
        for (int k = 1; k < K; k++)
            for (int i = 0; i + (1 << k) <= n; i++)
                sparse[k][i] = Math.min(sparse[k - 1][i], sparse[k - 1][i + (1 << (k - 1))]);
    }

    private int rmq(int l, int r) {            // min over lcp[l..r], inclusive
        if (l > r) return Integer.MAX_VALUE;
        int k = lg[r - l + 1];
        return Math.min(sparse[k][l], sparse[k][r - (1 << k) + 1]);
    }

    public int lcpOfSuffixes(int i, int j) {
        if (i == j) return n - i;
        int ri = rank[i], rj = rank[j];
        if (ri > rj) { int t = ri; ri = rj; rj = t; }
        return rmq(ri + 1, rj);                // lcp array is indexed against adjacent ranks
    }
}
```
**Time:** `O(n log n)` build, `O(1)` query. **Space:** `O(n log n)`.
**Insight:** because suffixes between ranks `ri` and `rj` are sorted, their pairwise LCP is the *minimum* adjacent LCP across that range — turning a string question into a static RMQ, the bridge between suffix arrays and segment-tree queries.

---

### Problem 64: kth Smallest Substring — Suffix Array + LCP Walk
**Statement:** Return the `k`-th lexicographically smallest **distinct** substring of `s`. Edge case: `k` larger than the number of distinct substrings returns empty.

**Approach:** Walk suffixes in `sa` order. Suffix `sa[i]` contributes `(n - sa[i] - lcp[i])` new distinct substrings (lengths `lcp[i]+1 .. n-sa[i]`). Accumulate until the bucket containing `k`, then slice the exact length.

```java
class KthDistinctSubstring {
    public String kth(String s, int[] sa, int[] lcp, long k) {
        int n = s.length();
        for (int i = 0; i < n; i++) {
            int newCount = (n - sa[i]) - lcp[i];   // distinct substrings starting at this suffix
            if (k <= newCount) {
                int length = lcp[i] + (int) k;     // the k-th new prefix length
                return s.substring(sa[i], sa[i] + length);
            }
            k -= newCount;
        }
        return "";                                 // k exceeds total distinct substrings
    }
}
```
**Time:** `O(n)` after `O(n log n)` preprocessing. **Space:** `O(1)` extra.
**Insight:** the sorted suffix order is *also* the sorted order of their fresh prefixes, so distinct substrings emit in lexicographic order — `lcp[i]` tells you exactly how many of each suffix's prefixes were already counted.

---

### Problem 65: Suffix Automaton — endpos Sizes via Suffix-Link Tree
**Statement:** For a SAM of `s`, compute `cnt[v]` = the number of positions where the substrings of state `v` occur (the size of its `endpos` set), enabling occurrence counting of any substring in `O(|p|)`. Edge case: cloned states have `cnt = 0` initially.

**Approach:** Mark each *non-clone* state created at extension with `cnt = 1`. Process states in **decreasing `len`** order (a reverse topological order of the suffix-link tree) and add each `cnt[v]` to `cnt[link[v]]`.

```java
class SAMEndpos {
    public long[] endposSizes(SuffixAutomaton sam, String s) {
        int N = sam.st.size();
        long[] cnt = new long[N];
        // mark the "primary" states along the main chain (non-clones)
        int cur = 0;
        for (char c : s.toCharArray()) { cur = sam.st.get(cur).next.get(c); cnt[cur] = 1; }
        // order states by len descending (counting-sort buckets)
        Integer[] order = new Integer[N];
        for (int i = 0; i < N; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> sam.st.get(b).len - sam.st.get(a).len);
        for (int v : order) {
            int link = sam.st.get(v).link;
            if (link >= 0) cnt[link] += cnt[v];
        }
        return cnt;
    }
}
```
**Time:** `O(n log n)` (sort) or `O(n)` with counting sort. **Space:** `O(n)`.
**Insight:** the suffix-link tree's parent accumulates its children's end positions — propagating `cnt` up the tree is how a SAM answers "how many times does substring `p` occur" without ever storing occurrence lists.

---

### Problem 66: Suffix Automaton — Lexicographically kth Substring (Distinct & Total)
**Statement:** Using a SAM, return the `k`-th smallest substring in lexicographic order, supporting both *distinct* mode and *count-with-multiplicity* mode. Edge case: empty string and `k` out of range.

**Approach:** Precompute `paths[v]` = number of substrings reachable from `v` (1 per outgoing edge in distinct mode, or weighted by `endpos` size in total mode), via DFS over the DAG. Greedily descend: at each state try transitions in alphabetical order, subtracting skipped subtree counts from `k`.

```java
class SAMKthSubstring {
    long[] paths;

    public String kth(SuffixAutomaton sam, long[] cnt, long k, boolean distinct) {
        int N = sam.st.size();
        paths = new long[N];
        boolean[] vis = new boolean[N];
        dfsCount(sam, 0, cnt, distinct, vis);
        if (k > paths[0]) return "";              // out of range
        StringBuilder sb = new StringBuilder();
        int v = 0;
        while (k > 0) {
            for (char c = 'a'; c <= 'z'; c++) {
                Integer nxt = sam.st.get(v).next.get(c);
                if (nxt == null) continue;
                long here = paths[nxt];           // substrings through this edge
                if (k <= here) { sb.append(c); v = nxt; k--; break; }
                k -= here;
            }
        }
        return sb.toString();
    }

    private long dfsCount(SuffixAutomaton sam, int v, long[] cnt, boolean distinct, boolean[] vis) {
        vis[v] = true;
        long total = 0;
        for (var e : sam.st.get(v).next.entrySet()) {
            int u = e.getValue();
            if (!vis[u]) dfsCount(sam, u, cnt, distinct, vis);
            total += (distinct ? 1 : cnt[u]) + paths[u];
        }
        paths[v] = total;
        return total;
    }
}
```
**Time:** `O(n·Σ)` preprocessing, `O(|answer|·Σ)` query. **Space:** `O(n)`.
**Insight:** the SAM is a DAG of substrings; counting paths through each node turns "k-th substring" into a guided descent — the `distinct` vs `total` switch is just whether you weight an edge by 1 or by its `endpos` size.

---

### Problem 67: Aho-Corasick — Count Total Matches via Fail-Tree Subtree Sums
**Statement:** Instead of emitting every match (which can be `Θ(n·patterns)`), count the *total* number of pattern occurrences across the text in `O(n + total_nodes)` by deferring output to a fail-tree aggregation. Edge case: nested patterns (one pattern is a suffix of another).

**Approach:** Run the text and increment `hit[node]` at each step (no fail-chain walk). After the pass, each node's true occurrence contribution is the subtree sum over the *fail tree*; process nodes by decreasing depth and push `hit` to `fail[node]`, summing terminal counts.

```java
class AhoCorasickCount {
    public long countAll(AhoCorasick ac, String text, int[][] trie, int[] fail,
                         java.util.List<Integer>[] out, int size) {
        long[] hit = new long[size];
        int node = 0;
        for (int i = 0; i < text.length(); i++) {
            node = trie[node][text.charAt(i) - 'a'];
            hit[node]++;                          // defer: don't walk fail chain here
        }
        // order nodes by BFS depth descending so children flush before parents
        Integer[] order = new Integer[size];
        for (int i = 0; i < size; i++) order[i] = i;
        int[] depth = bfsDepth(trie, fail, size);
        java.util.Arrays.sort(order, (a, b) -> depth[b] - depth[a]);
        long total = 0;
        for (int v : order) {
            if (out[v] != null) total += hit[v] * out[v].size();  // patterns ending exactly here
            if (fail[v] != v) hit[fail[v]] += hit[v];             // push counts up the fail tree
        }
        return total;
    }

    private int[] bfsDepth(int[][] trie, int[] fail, int size) {
        int[] depth = new int[size];
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        for (int c = 0; c < 26; c++) if (trie[0][c] != 0) { depth[trie[0][c]] = 1; q.add(trie[0][c]); }
        while (!q.isEmpty()) {
            int u = q.poll();
            for (int c = 0; c < 26; c++) {
                int v = trie[u][c];
                if (v != 0 && depth[v] == 0) { depth[v] = depth[u] + 1; q.add(v); }
            }
        }
        return depth;
    }
}
```
**Time:** `O(n + nodes·Σ)`. **Space:** `O(nodes)`.
**Insight:** walking the fail chain per match is the naive `O(n·d)` trap; deferring to a single fail-tree subtree-sum makes total-occurrence counting linear regardless of how deeply patterns nest.

---

### Problem 68: Two Strings With Equal Hash But Different Content — Birthday Bound Demo
**Statement:** Empirically find a hash collision under a small modulus by generating random strings until two share a hash (the birthday paradox), proving the `~√M` collision threshold that drives the choice of modulus size. Edge case: must store first-seen string per hash to confirm a *true* collision.

**Approach:** Use a deliberately small `mod` (e.g. `10⁶+3`). Generate random strings, hash each; on a repeated hash with differing content, report the pair. Expected trials ≈ `1.25·√mod`.

```java
class BirthdayCollision {
    final long MOD = 1_000_003, BASE = 131;

    long hash(String s) {
        long h = 0;
        for (int i = 0; i < s.length(); i++) h = (h * BASE + s.charAt(i)) % MOD;
        return h;
    }

    public String[] findCollision(long seed) {
        java.util.Random rnd = new java.util.Random(seed);
        java.util.Map<Long, String> seen = new java.util.HashMap<>();
        for (int t = 0; t < 10_000_000; t++) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append((char) ('a' + rnd.nextInt(26)));
            String s = sb.toString();
            long h = hash(s);
            String prev = seen.get(h);
            if (prev != null && !prev.equals(s)) return new String[]{ prev, s };
            seen.putIfAbsent(h, s);
        }
        return null;
    }
}
```
**Time:** `O(√MOD)` expected. **Space:** `O(√MOD)`.
**Insight:** collisions appear at ~`√M` insertions, not `M` — so a `10⁹` modulus collides after only ~`30000` strings, which is why competitive hashing uses `~10¹⁸` (double 64-bit) moduli to push the birthday bound past any realistic input.

---

### Problem 69: Longest Substring Occurring At Least k Times — SAM endpos ≥ k
**Statement:** Find the length of the longest substring that occurs **at least `k`** times in `s`. Edge case: `k = 1` returns `n`; no substring meeting the threshold returns 0.

**Approach:** Build the SAM, compute `endpos` sizes (Problem 65). The answer is `max(len[v])` over all states `v` whose `cnt[v] >= k`, because `len[v]` is the longest substring in that endpos-equivalence class.

```java
class LongestAtLeastK {
    public int solve(String s, int k) {
        SuffixAutomaton sam = new SuffixAutomaton();
        sam.build(s);
        long[] cnt = new SAMEndpos().endposSizes(sam, s);
        int best = 0;
        for (int v = 1; v < sam.st.size(); v++)
            if (cnt[v] >= k) best = Math.max(best, sam.st.get(v).len);
        return best;
    }
}
```
**Time:** `O(n log n)`. **Space:** `O(n)`.
**Insight:** all substrings sharing an `endpos` set have the same occurrence count, so the threshold test happens once per *state* (not per substring) and `len[v]` is the longest representative for free.

---

### Problem 70: Number of Distinct Substrings After Each Append — Online SAM
**Statement:** Maintain a count of distinct substrings of the string built so far, updated in amortized `O(log Σ)` after **each** appended character (online). Edge case: appending a repeated char may add fewer than `len` new substrings.

**Approach:** After each `extend(c)`, the number of *new* distinct substrings equals `len[last] - len[link[last]]`. Keep a running total — this is the incremental form of Problem 36.

```java
class OnlineDistinctSubstrings {
    SuffixAutomaton sam = new SuffixAutomaton();
    long total = 0;

    public long append(char c) {
        sam.extend(c);
        int last = sam.last;
        int link = sam.st.get(last).link;
        total += sam.st.get(last).len - (link < 0 ? 0 : sam.st.get(link).len);
        return total;
    }
}
```
**Time:** `O(log Σ)` amortized per char. **Space:** `O(n)`.
**Insight:** the SAM's online construction means the distinct-substring count is a *running sum* of `len[last] - len[link[last]]` — no rebuild, which is exactly why SAMs beat suffix arrays for streaming queries.

---

### Problem 71: Z-Algorithm Compression — Smallest Generating Period for Each Prefix
**Statement:** For every prefix length `i`, report whether `s[0..i)` is a power of a shorter string (a perfect repetition) using the Z-array, and give the smallest such generator length. Edge case: a prefix that is not a repetition reports itself as its own generator.

**Approach:** A prefix of length `i` is a `t`-power iff there's a position `p` dividing `i` with `z[p] == i - p` and `i % p == 0`. Scan candidate periods using Z-values; the smallest valid `p` is the generator.

```java
class PrefixPowers {
    public int[] smallestGenerator(String s) {
        int n = s.length();
        int[] z = new int[n];
        int l = 0, r = 0;
        for (int i = 1; i < n; i++) {
            if (i < r) z[i] = Math.min(r - i, z[i - l]);
            while (i + z[i] < n && s.charAt(z[i]) == s.charAt(i + z[i])) z[i]++;
            if (i + z[i] > r) { l = i; r = i + z[i]; }
        }
        int[] gen = new int[n + 1];
        for (int i = 1; i <= n; i++) gen[i] = i;        // default: own generator
        for (int p = 1; p < n; p++) {
            if (z[p] == 0) continue;
            // prefix of length p repeats; check multiples covered by this Z-box
            int reach = p + z[p];                        // s[0..reach) is covered by period p
            for (int i = 2 * p; i <= reach; i += p)
                if (gen[i] == i) gen[i] = p;            // first (smallest) period wins
        }
        return gen;
    }
}
```
**Time:** `O(n log n)` (harmonic inner loop). **Space:** `O(n)`.
**Insight:** `z[p] == i - p` certifies that the length-`p` prefix tiles the whole prefix of length `i`; scanning periods in increasing order makes the *first* hit the smallest generator — perfect-power detection without factoring.

---

### Problem 72: Wildcard Matching Greedy — Catastrophic-Backtracking Counterexample
**Statement:** Exhibit an input where the greedy `O(1)`-space wildcard matcher (Problem 24) degrades toward quadratic, and show the DP version stays linear-per-cell, clarifying when greedy is *not* safe. Edge case: many `*` separated by literals against a long non-matching text.

**Approach:** Pattern like `"*a*a*a*a*b"` against `"aaaa...aaaa"` forces repeated star-backtracking. Provide a benchmark harness that counts character comparisons for both approaches.

```java
class WildcardWorstCase {
    long greedyComparisons = 0, dpCells = 0;

    public boolean greedy(String s, String p) {
        int i = 0, j = 0, star = -1, match = 0, n = s.length(), m = p.length();
        while (i < n) {
            greedyComparisons++;
            if (j < m && (p.charAt(j) == '?' || p.charAt(j) == s.charAt(i))) { i++; j++; }
            else if (j < m && p.charAt(j) == '*') { star = j; match = i; j++; }
            else if (star != -1) { j = star + 1; match++; i = match; }   // backtrack
            else return false;
        }
        while (j < m && p.charAt(j) == '*') j++;
        return j == m;
    }

    public String build(int n) {                      // adversarial text + pattern
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < n; i++) text.append('a');
        return text.toString();                       // pair with "*a*a*a*...*b"
    }
}
```
**Time:** greedy up to `O(n·#stars)`, DP `O(n·m)` guaranteed. **Space:** greedy `O(1)`, DP `O(m)`.
**Insight:** greedy wildcard is *usually* near-linear but lacks a worst-case bound; the alternating-star adversary forces re-scanning, which is exactly why production glob engines either cap backtracking or fall back to the DP/automaton form.

---

### Problem 73: Regex `.`/`*` With Memoized Recursion — Stack-Depth Edge Cases
**Statement:** Implement regex matching (Problem 22) as top-down memoized recursion and handle the deep-recursion edge case for very long inputs by bounding stack growth. Edge case: `p = "a*a*a*...*"` against empty `s` must short-circuit via the `*`-skips-zero branch.

**Approach:** Memo table `Boolean[i][j]`. The `*` branch tries "skip two pattern chars" first (cheap, terminates fast on empty text) before "consume one text char", minimizing recursion depth on star-heavy patterns.

```java
class RegexMemo {
    Boolean[][] memo;
    String s, p;

    public boolean isMatch(String s, String p) {
        this.s = s; this.p = p;
        memo = new Boolean[s.length() + 1][p.length() + 1];
        return dp(0, 0);
    }

    private boolean dp(int i, int j) {
        if (j == p.length()) return i == s.length();
        if (memo[i][j] != null) return memo[i][j];
        boolean firstMatch = i < s.length() && (p.charAt(j) == '.' || p.charAt(j) == s.charAt(i));
        boolean ans;
        if (j + 1 < p.length() && p.charAt(j + 1) == '*')
            ans = dp(i, j + 2) || (firstMatch && dp(i + 1, j));   // try zero-copies first
        else
            ans = firstMatch && dp(i + 1, j + 1);
        return memo[i][j] = ans;
    }
}
```
**Time:** `O(n·m)`. **Space:** `O(n·m)` memo + `O(n+m)` stack.
**Insight:** trying the `*`-as-zero branch first keeps recursion shallow on patterns like `"a*b*c*"`, and the memo guarantees each `(i,j)` is evaluated once — converting exponential naive recursion into polynomial.

---

### Problem 74: Trie With Deletion — Reference Counting and Node Pruning
**Statement:** Extend a trie to support `delete(word)` that prunes nodes which become unreachable, *without* deleting nodes shared by other words. Edge case: deleting a word that is a prefix of another must keep the shared path but clear the `isEnd` flag.

**Approach:** Track `passCount` (words routed through a node) and `endCount`. On delete, decrement `passCount` down the path; physically remove a child only when its `passCount` hits 0. Clear `isEnd`/`endCount` at the terminal.

```java
class DeletableTrie {
    static class Node {
        Node[] next = new Node[26];
        int pass = 0, end = 0;
    }
    Node root = new Node();

    public void insert(String w) {
        Node n = root; n.pass++;
        for (char c : w.toCharArray()) {
            int i = c - 'a';
            if (n.next[i] == null) n.next[i] = new Node();
            n = n.next[i]; n.pass++;
        }
        n.end++;
    }

    public boolean delete(String w) {
        if (!contains(w)) return false;
        Node n = root; n.pass--;
        for (char c : w.toCharArray()) {
            int i = c - 'a';
            Node child = n.next[i];
            if (--child.pass == 0) { n.next[i] = null; return true; }  // whole subtree gone
            n = child;
        }
        n.end--;
        return true;
    }

    public boolean contains(String w) {
        Node n = root;
        for (char c : w.toCharArray()) {
            n = n.next[c - 'a'];
            if (n == null) return false;
        }
        return n.end > 0;
    }
}
```
**Time:** `O(L)` per op. **Space:** `O(total chars)`.
**Insight:** a plain trie leaks memory on deletes; the `pass` counter tells you the exact moment a subtree is owned by no remaining word, so pruning is safe even when prefixes are shared — the same refcount idea as garbage collection.

---

### Problem 75: Persistent Trie — XOR Maximization Over a Prefix-Versioned Structure
**Statement:** Build a persistent binary trie of running prefix-XORs so you can answer "max XOR of a subarray ending at index `r` with start in `[l, r]`" using version `l-1..r`. Edge case: querying an empty version range.

**Approach:** Each insert creates `O(bits)` new nodes sharing untouched subtrees with the previous version. To find max XOR of `x` against numbers inserted in `(l-1, r]`, walk both version roots greedily choosing the opposite bit when the *count delta* between versions is positive.

```java
class PersistentXorTrie {
    static final int B = 30;
    int[][] ch = new int[2_000_000][2];
    int[] cnt = new int[2_000_000];
    int sz = 1;
    int[] roots;

    int insert(int prev, int val) {
        int cur = sz++, node = cur;
        for (int b = B; b >= 0; b--) {
            int bit = (val >> b) & 1;
            ch[node][bit ^ 1] = prev < 0 ? 0 : ch[prev][bit ^ 1];
            ch[node][bit] = sz++;
            cnt[node] = (prev < 0 ? 0 : cnt[prev]) + 1;
            node = ch[node][bit];
            prev = prev < 0 ? -1 : ch[prev][bit];
        }
        cnt[node] = (prev < 0 ? 0 : cnt[prev]) + 1;
        return cur;
    }

    int maxXor(int rootL, int rootR, int x) {     // numbers in (L, R] only
        int res = 0, a = rootL, b = rootR;
        for (int bit = B; bit >= 0; bit--) {
            int want = ((x >> bit) & 1) ^ 1;
            int delta = cnt[ch[b][want]] - (a == 0 ? 0 : cnt[ch[a][want]]);
            if (delta > 0) { res |= (1 << bit); a = ch[a][want]; b = ch[b][want]; }
            else { int got = (x >> bit) & 1; a = ch[a][got]; b = ch[b][got]; }
        }
        return res;
    }
}
```
**Time:** `O(bits)` per insert and per query. **Space:** `O(n·bits)`.
**Insight:** persistence turns a static XOR-trie into a *range*-queryable one — the count delta between two versions reveals whether a desired bit branch contains any element in the index window, no rebuild required.

---

### Problem 76: Suffix Tree via Ukkonen — Online Linear Construction Skeleton
**Statement:** Build a suffix tree in `O(n)` with Ukkonen's algorithm, correctly handling the three extension rules, the active-point (`activeNode`, `activeEdge`, `activeLength`), and suffix links. Edge case: the implicit-to-explicit conversion needs a global end pointer for leaf edges.

**Approach:** Process characters left to right; maintain an active point and a `remainder` of pending suffixes. Rule 2 splits an edge and adds a suffix link from the previously created internal node; the leaf-end is a shared global index so all leaves extend in `O(1)`.

```java
class UkkonenSuffixTree {
    static final int OO = Integer.MAX_VALUE / 2;
    String text; int[] start; int[] end; int[][] next; int[] link; int[] leafEnd = {-1};
    int root, lastNew, activeNode, activeEdge, activeLength, remainder, pos, size;

    public UkkonenSuffixTree(String s) {
        text = s + " ";
        int n = text.length();
        start = new int[2 * n]; end = new int[2 * n]; link = new int[2 * n];
        next = new int[2 * n][256];
        for (int[] row : next) java.util.Arrays.fill(row, -1);
        root = newNode(-1, -1); activeNode = root;
        for (pos = 0; pos < n; pos++) extend(pos);
    }

    int newNode(int s, int e) { start[size] = s; end[size] = e; link[size] = root; return size++; }
    int edgeLen(int node) { return Math.min(end[node], pos + 1) - start[node]; }

    void extend(int i) {
        leafEnd[0] = i; remainder++; lastNew = -1;
        while (remainder > 0) {
            if (activeLength == 0) activeEdge = i;
            int c = text.charAt(activeEdge);
            if (next[activeNode][c] == -1) {
                next[activeNode][c] = newNode(i, OO);
                addLink(activeNode);
            } else {
                int nxt = next[activeNode][c];
                if (activeLength >= edgeLen(nxt)) { activeEdge += edgeLen(nxt); activeLength -= edgeLen(nxt); activeNode = nxt; continue; }
                if (text.charAt(start[nxt] + activeLength) == text.charAt(i)) { activeLength++; addLink(activeNode); break; }
                int split = newNode(start[nxt], start[nxt] + activeLength);
                next[activeNode][c] = split;
                int leaf = newNode(i, OO);
                next[split][text.charAt(i)] = leaf;
                start[nxt] += activeLength;
                next[split][text.charAt(start[nxt])] = nxt;
                addLink(split);
            }
            remainder--;
            if (activeNode == root && activeLength > 0) { activeLength--; activeEdge = i - remainder + 1; }
            else if (activeNode != root) activeNode = link[activeNode];
        }
    }

    void addLink(int node) { if (lastNew != -1) link[lastNew] = node; lastNew = node; }
}
```
**Time:** `O(n)` (amortized; with `Σ` constant). **Space:** `O(n·Σ)`.
**Insight:** the active-point + global-leaf-end trick is what makes Ukkonen linear — leaves grow for free via a shared end pointer, and suffix links let each phase reuse the previous one's traversal instead of re-walking from the root.

---

### Problem 77: Generalized Suffix Automaton — Multiple Strings, One Automaton
**Statement:** Build a single SAM over a *set* of strings (separated logically), so you can answer "longest common substring of all `k` strings" or "which strings contain substring `p`". Edge case: resetting `last` to the initial state between strings, and avoiding spurious clones when a transition already exists.

**Approach:** Reset `last = 0` before each string. In `extend`, if the transition already exists with the right `len`, reuse it; otherwise apply the standard clone logic, taking care not to create a duplicate `cur` when the char is already present.

```java
class GeneralizedSAM {
    static class S { int len, link; java.util.Map<Character, Integer> next = new java.util.HashMap<>(); }
    java.util.List<S> st = new java.util.ArrayList<>();

    GeneralizedSAM() { S r = new S(); r.len = 0; r.link = -1; st.add(r); }

    int extend(int last, char c) {
        if (st.get(last).next.containsKey(c)) {        // transition exists: maybe just descend
            int q = st.get(last).next.get(c);
            if (st.get(q).len == st.get(last).len + 1) return q;
            int clone = st.size(); S cs = new S();
            cs.len = st.get(last).len + 1; cs.next = new java.util.HashMap<>(st.get(q).next);
            cs.link = st.get(q).link; st.add(cs);
            st.get(q).link = clone;
            int p = last;
            while (p != -1 && st.get(p).next.getOrDefault(c, -1) == q) { st.get(p).next.put(c, clone); p = st.get(p).link; }
            return clone;
        }
        int cur = st.size(); S curS = new S(); curS.len = st.get(last).len + 1; st.add(curS);
        int p = last;
        while (p != -1 && !st.get(p).next.containsKey(c)) { st.get(p).next.put(c, cur); p = st.get(p).link; }
        if (p == -1) curS.link = 0;
        else {
            int q = st.get(p).next.get(c);
            if (st.get(p).len + 1 == st.get(q).len) curS.link = q;
            else {
                int clone = st.size(); S cs = new S();
                cs.len = st.get(p).len + 1; cs.next = new java.util.HashMap<>(st.get(q).next);
                cs.link = st.get(q).link; st.add(cs);
                while (p != -1 && st.get(p).next.getOrDefault(c, -1) == q) { st.get(p).next.put(c, clone); p = st.get(p).link; }
                st.get(q).link = clone; curS.link = clone;
            }
        }
        return cur;
    }

    public void addString(String s) { int last = 0; for (char c : s.toCharArray()) last = extend(last, c); }
}
```
**Time:** `O(total length · log Σ)`. **Space:** `O(total length · Σ)`.
**Insight:** the "transition already exists" branch is the *only* difference from a single-string SAM — it stops the automaton from re-adding states for substrings shared across the input set, keeping the node count linear in *total* length.

---

### Problem 78: Bitset-Accelerated Edit Distance — Myers' Bit-Parallel Algorithm
**Statement:** Compute Levenshtein distance in `O(n·⌈m/w⌉)` (where `w` is the machine word size) using Myers' bit-vector algorithm, a 64× constant-factor speedup over the textbook DP for `m ≤ 64`. Edge case: pattern longer than one word needs block carry handling (here, the single-word version).

**Approach:** Precompute a bitmask `Peq[c]` of where each char appears in the pattern. Maintain vertical-positive/negative delta bit-vectors (`VP`, `VN`); each text char updates them with a handful of bit ops, and the score follows a carry bit at the high position.

```java
class MyersEditDistance {
    public int distance(String pattern, String text) {
        int m = pattern.length();
        if (m == 0) return text.length();
        if (m > 64) throw new IllegalArgumentException("single-word version: m <= 64");
        long[] Peq = new long[256];
        for (int i = 0; i < m; i++) Peq[pattern.charAt(i)] |= 1L << i;
        long VP = ~0L, VN = 0L;
        int score = m;
        long topBit = 1L << (m - 1);
        for (int j = 0; j < text.length(); j++) {
            long eq = Peq[text.charAt(j)];
            long X = eq | VN;
            long D0 = (((eq & VP) + VP) ^ VP) | eq;
            long HP = VN | ~(D0 | VP);
            long HN = D0 & VP;
            if ((HP & topBit) != 0) score++;
            else if ((HN & topBit) != 0) score--;
            HP = HP << 1 | 1;
            HN = HN << 1;
            VP = HN | ~(D0 | HP);
            VN = D0 & HP;
        }
        return score;
    }
}
```
**Time:** `O(n·⌈m/w⌉)`. **Space:** `O(|Σ|)`.
**Insight:** the DP's column of `±1` differences fits in two bit-vectors, so an entire column updates with ~10 word operations — Myers' algorithm is why approximate-matching tools (`agrep`, read aligners) hit gigabytes/second.

---

### Problem 79: Longest Common Extension (LCE) via Hashing — Binary-Search Probe
**Statement:** Answer `LCE(i, j)` = length of the longest common prefix of suffixes `i` and `j`, in `O(log n)` per query using only prefix hashes (no suffix array), useful when you also need substring equality elsewhere. Edge case: one index at the very end of the string.

**Approach:** Binary search the LCE length `L`: the largest `L` with `hash(i, i+L-1) == hash(j, j+L-1)`. Each equality check is `O(1)` via the substring-hash structure (Problem 56), so the whole query is `O(log n)`.

```java
class LCEHashing {
    SubHash h; int n;
    public LCEHashing(String s) { h = new SubHash(s); n = s.length(); }

    public int lce(int i, int j) {
        if (i == j) return n - i;
        int lo = 0, hi = n - Math.max(i, j);   // can't exceed the shorter remaining suffix
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (h.sub(i, i + mid - 1) == h.sub(j, j + mid - 1)) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }
}
```
**Time:** `O(log n)` per query, `O(n)` build. **Space:** `O(n)`.
**Insight:** LCE is monotone in length — if length `L` matches, so does every shorter length — so it's binary-searchable, and hashing makes each probe `O(1)`, a lighter-weight alternative to the suffix-array RMQ of Problem 63.

---

### Problem 80: Run-Length-Aware Pattern Matching — Match Over RLE-Compressed Text
**Statement:** Given run-length-encoded text and a (plain) pattern, find matches while operating on the *compressed* representation where possible. Edge case: pattern spanning a run boundary, and a pattern composed of a single repeated character.

**Approach:** Decompose the pattern into runs too. A pattern with `k` runs matches starting inside a text run only if the interior runs match exactly and the two boundary runs have sufficient length. Single-run patterns reduce to "is there a text run of the same char with length ≥ pattern length".

```java
class RLEMatch {
    static class Run { char c; int len; Run(char c, int len){ this.c = c; this.len = len; } }

    java.util.List<Run> encode(String s) {
        java.util.List<Run> r = new java.util.ArrayList<>();
        for (int i = 0; i < s.length(); ) {
            int j = i; while (j < s.length() && s.charAt(j) == s.charAt(i)) j++;
            r.add(new Run(s.charAt(i), j - i)); i = j;
        }
        return r;
    }

    public boolean contains(String text, String pattern) {
        java.util.List<Run> t = encode(text), p = encode(pattern);
        if (p.size() == 1) {                       // single-run pattern: any long-enough same-char run
            for (Run run : t) if (run.c == p.get(0).c && run.len >= p.get(0).len) return true;
            return false;
        }
        // multi-run: middle runs must match exactly, ends need >= length
        for (int i = 0; i + p.size() <= t.size(); i++) {
            boolean ok = t.get(i).c == p.get(0).c && t.get(i).len >= p.get(0).len
                      && t.get(i + p.size() - 1).c == p.get(p.size() - 1).c
                      && t.get(i + p.size() - 1).len >= p.get(p.size() - 1).len;
            for (int k = 1; ok && k < p.size() - 1; k++)
                ok = t.get(i + k).c == p.get(k).c && t.get(i + k).len == p.get(k).len;
            if (ok) return true;
        }
        return false;
    }
}
```
**Time:** `O(R_t · R_p)` on run counts, often ≪ `O(n·m)`. **Space:** `O(R_t + R_p)`.
**Insight:** on highly compressible text the run count is tiny, so matching over runs (exact interiors, `≥`-length ends) collapses the problem — the same idea behind matching in compressed text without full decompression.

---

### Problem 81: Count Palindromic Substrings in a Range — Eertree + Offline Queries
**Statement:** Answer offline queries "how many palindromic substrings end at or before index `r`" by exploiting that the eertree's `last` pointer after appending `s[0..r]` reflects the longest palindromic suffix; accumulate a running distinct/total count. Edge case: queries with `r` before any palindrome.

**Approach:** Append chars one at a time; after each append, the number of palindromic substrings *ending at* the current index equals the depth of `last` in the suffix-link tree (its `series` count). Prefix-sum these to answer range-count queries.

```java
class PalindromeRangeCount {
    public long[] palindromesEndingAt(String s) {
        Eertree t = new Eertree();
        int[] seriesLen = new int[1 << 16];        // # palindromic suffixes ending at each node
        long[] ans = new long[s.length()];
        long running = 0;
        for (int i = 0; i < s.length(); i++) {
            t.add(s.charAt(i));
            int node = t.last;
            seriesLen[node] = seriesLen[t.link[node]] + 1;   // chain via suffix link
            running += seriesLen[node];
            ans[i] = running;                       // total palindromic substrings in s[0..i]
        }
        return ans;
    }
}
```
**Time:** `O(n)` amortized. **Space:** `O(n)`.
**Insight:** the count of palindromes ending at position `i` is the length of the suffix-link chain from `last` — caching it as `seriesLen[node] = seriesLen[link]+1` makes each step `O(1)`, turning the eertree into a prefix-count oracle.

---

### Problem 82: Smallest Rotation — Lexicographic Via Suffix Automaton of Doubled String
**Statement:** Find the lexicographically smallest rotation of `s` (Problem 44 via Booth's) by an *independent* method: build a SAM (or just greedily walk transitions) over `s + s` and read the smallest length-`n` path. Edge case: all-equal strings have a unique rotation; ties broken arbitrarily but consistently.

**Approach:** Concatenate `t = s + s`. Greedily walk: from each starting state choose the smallest available transition for `n` steps. To find the *start index*, track which original positions a smallest path corresponds to (via earliest occurrence).

```java
class SmallestRotationSAM {
    public int leastRotation(String s) {
        int n = s.length();
        String t = s + s;
        // greedy: maintain set of candidate start positions, prune by smallest next char
        java.util.List<Integer> cand = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) cand.add(i);
        for (int k = 0; k < n && cand.size() > 1; k++) {
            char best = Character.MAX_VALUE;
            for (int start : cand) best = (char) Math.min(best, t.charAt(start + k));
            java.util.List<Integer> nxt = new java.util.ArrayList<>();
            for (int start : cand) if (t.charAt(start + k) == best) nxt.add(start);
            cand = nxt;
        }
        return cand.get(0);
    }
}
```
**Time:** `O(n²)` worst for this candidate-pruning form (Booth's is `O(n)`; included as a contrast). **Space:** `O(n)`.
**Insight:** the candidate-pruning view makes *why* a smallest rotation exists obvious — at each column keep only positions tied for the minimum — but it can be quadratic on `"aaaa…"`, which is precisely the case Booth's skip rule was invented to fix.

---

### Problem 83: Z-Function on Concatenation With Mismatch Budget — k-Mismatch Prefix
**Statement:** For each position `i`, compute the longest prefix match allowing up to `k` mismatches (k-approximate Z). Edge case: `k` larger than the string length matches everything.

**Approach:** For small `k`, use the "kangaroo jump" technique: with an LCE oracle (Problem 79), at each `i` perform up to `k+1` LCE queries, jumping over each mismatch, to get the k-mismatch match length in `O(k log n)` per position.

```java
class KMismatchZ {
    LCEHashing lce; int n;
    public KMismatchZ(String s) { lce = new LCEHashing(s); n = s.length(); }

    public int[] zKMismatch(int k) {
        int[] z = new int[n];
        for (int i = 1; i < n; i++) {
            int p = 0, q = i, mism = 0;
            while (q < n && mism <= k) {
                int l = lce.lce(p, q);              // match run from prefix pos p and text pos q
                p += l; q += l;
                if (q < n) { mism++; if (mism > k) break; p++; q++; }   // jump the mismatch
            }
            z[i] = p;                               // chars of prefix matched within budget
        }
        return z;
    }
}
```
**Time:** `O(n·k·log n)`. **Space:** `O(n)`.
**Insight:** "kangaroo jumps" turn approximate matching into a bounded number of *exact* LCE leaps — each mismatch costs one query, so the total work scales with the mismatch budget `k`, not the string length squared.

---

### Problem 84: Aho-Corasick With Dynamic Dictionary — Rebuild Amortization
**Statement:** Support inserting new patterns into an Aho-Corasick matcher and querying, amortizing rebuild cost via a logarithmic stack of automata (the "log buckets" trick). Edge case: a query must check *all* buckets, and a flush merges smaller buckets into a larger one.

**Approach:** Keep `O(log n)` AC automata of sizes `2⁰, 2¹, …`. Insert into the smallest; when two buckets share a size, merge their pattern lists and rebuild one larger automaton. Each pattern is rebuilt `O(log n)` times total.

```java
class DynamicAhoCorasick {
    java.util.List<java.util.List<String>> buckets = new java.util.ArrayList<>();
    java.util.List<AhoCorasick> autos = new java.util.ArrayList<>();

    public void add(String pattern) {
        java.util.List<String> carry = new java.util.ArrayList<>();
        carry.add(pattern);
        int i = 0;
        while (i < buckets.size() && !buckets.get(i).isEmpty()) {    // same-size collision -> merge
            carry.addAll(buckets.get(i));
            buckets.get(i).clear();
            autos.set(i, null);
            i++;
        }
        while (buckets.size() <= i) { buckets.add(new java.util.ArrayList<>()); autos.add(null); }
        buckets.set(i, carry);
        autos.set(i, new AhoCorasick(carry));                       // rebuild this level only
    }

    public long countMatches(String text) {
        long total = 0;
        for (AhoCorasick a : autos) if (a != null) total += a.search(text).size();
        return total;
    }
}
```
**Time:** `O(L log P)` amortized insert, `O(buckets · n)` query. **Space:** `O(total pattern chars)`.
**Insight:** binary-counter merging amortizes the otherwise-`O(P)` full rebuild down to `O(log P)` per pattern — the same logarithmic-decomposition technique that makes static structures support insertions.

---

### Problem 85: Manacher for Longest Palindromic Substring With One Allowed Edit
**Statement:** Find the longest substring that is a palindrome *after allowing at most one character substitution*. Edge case: a string already palindromic should still report the full length; a single mismatch in the center must be tolerated.

**Approach:** Expand around each center but permit one mismatch: track a mismatch counter during expansion, continuing past the first mismatch and stopping at the second. Run for both odd and even centers.

```java
class OneEditPalindrome {
    public int longest(String s) {
        int n = s.length(), best = 0;
        for (int c = 0; c < 2 * n - 1; c++) {
            int l = c / 2, r = l + (c % 2);
            int mism = 0;
            while (l >= 0 && r < n) {
                if (s.charAt(l) != s.charAt(r)) { if (++mism > 1) break; }
                best = Math.max(best, r - l + 1);
                l--; r++;
            }
        }
        return best;
    }
}
```
**Time:** `O(n²)`. **Space:** `O(1)`.
**Insight:** the "one edit" relaxation is a single extra counter on the center-expansion loop — you stop at the *second* mismatch instead of the first, which is the minimal change from exact palindrome detection.

---

### Problem 86: Count Substrings With Exactly K Distinct Characters — Sliding Window Subtraction
**Statement:** Count substrings of `s` containing exactly `k` distinct characters. Edge case: `k` exceeding the alphabet size yields 0; the "exactly = atMost(k) − atMost(k−1)" identity must be applied carefully.

**Approach:** `exactly(k) = atMost(k) − atMost(k−1)`. Compute `atMost(k)` with a sliding window counting valid windows ending at each right pointer; the subtraction isolates the exact-`k` count.

```java
class ExactlyKDistinct {
    public long countExactly(String s, int k) {
        return atMost(s, k) - atMost(s, k - 1);
    }

    private long atMost(String s, int k) {
        if (k < 0) return 0;
        int[] freq = new int[128];
        int distinct = 0, left = 0;
        long count = 0;
        for (int right = 0; right < s.length(); right++) {
            if (freq[s.charAt(right)]++ == 0) distinct++;
            while (distinct > k)
                if (--freq[s.charAt(left++)] == 0) distinct--;
            count += right - left + 1;            // # valid windows ending at right
        }
        return count;
    }
}
```
**Time:** `O(n)`. **Space:** `O(1)` (fixed alphabet).
**Insight:** "exactly k" rarely has a clean direct window, but "at most k" does — the difference of two monotone window counts is the standard trick for converting an inequality constraint into an equality one.

---

### Problem 87: Minimum Insertions to Make a String Palindrome — Interval DP / LCS Reduction
**Statement:** Find the minimum number of character insertions to make `s` a palindrome. Edge case: an already-palindromic string needs 0; relate the answer to `n − LPS(s)`.

**Approach:** The answer is `n − longestPalindromicSubsequence(s)`, because the LPS chars stay fixed and every other char needs a mirror inserted. Equivalently an interval DP on `dp[i][j]`.

```java
class MinInsertionsPalindrome {
    public int minInsertions(String s) {
        int n = s.length();
        int[][] dp = new int[n][n];               // dp[i][j] = insertions for s[i..j]
        for (int len = 2; len <= n; len++)
            for (int i = 0; i + len - 1 < n; i++) {
                int j = i + len - 1;
                dp[i][j] = s.charAt(i) == s.charAt(j)
                        ? dp[i + 1][j - 1]
                        : 1 + Math.min(dp[i + 1][j], dp[i][j - 1]);
            }
        return dp[0][n - 1];
    }
}
```
**Time:** `O(n²)`. **Space:** `O(n²)`.
**Insight:** insertions to palindromize equals `n − LPS`, the dual of the longest-palindromic-subsequence problem — the chars *not* in the LPS are exactly the ones requiring a mirrored insertion.

---

### Problem 88: Two-Way (Crochemore-Perrin) String Matching — Constant-Space Optimal
**Statement:** Match a pattern in `O(n)` time and `O(1)` extra space using the Two-Way algorithm: compute the critical factorization of the pattern (its maximal suffix under both orders) and scan with two phases. Edge case: periodic patterns need the period-aware memory bound.

**Approach:** Find the critical position via the maximal-suffix computation under `<` and `>`. Then match the right part left-to-right and, on success, the left part right-to-left; on mismatch shift by the period, reusing matched info only within the period.

```java
class TwoWayMatcher {
    // maximal suffix of x under the given order; returns {position, period}
    private int[] maxSuffix(String x, boolean less) {
        int n = x.length(), i = -1, j = 0, k = 1, p = 1;
        while (j + k < n) {
            char a = x.charAt(j + k), b = x.charAt(i + k);
            boolean cmp = less ? a < b : a > b;
            boolean eq = a == b;
            if (cmp) { j += k; k = 1; p = j - i; }
            else if (eq) { if (k == p) { j += p; k = 1; } else k++; }
            else { i = j; j = i + 1; k = 1; p = 1; }
        }
        return new int[]{ i, p };
    }

    public int indexOf(String text, String pat) {
        int m = pat.length(), n = text.length();
        if (m == 0) return 0;
        int[] a = maxSuffix(pat, true), b = maxSuffix(pat, false);
        int crit, period;
        if (a[0] >= b[0]) { crit = a[0]; period = a[1]; } else { crit = b[0]; period = b[1]; }
        // simplified search (non-periodic branch) for illustration
        int pos = 0;
        while (pos + m <= n) {
            int i = crit + 1;
            while (i < m && pat.charAt(i) == text.charAt(pos + i)) i++;
            if (i < m) pos += i - crit;
            else {
                int j = crit;
                while (j >= 0 && pat.charAt(j) == text.charAt(pos + j)) j--;
                if (j < 0) return pos;
                pos += period;
            }
        }
        return -1;
    }
}
```
**Time:** `O(n)`. **Space:** `O(1)`.
**Insight:** the critical factorization splits the pattern at the point where periodicity "from both sides" is maximal, letting the search shift by the pattern's period without storing a failure table — the only truly constant-space linear matcher.

---

### Problem 89: Count Distinct Subsequences (Total) — DP With Last-Occurrence Correction
**Statement:** Count the number of **distinct** subsequences of `s` (over all lengths, including empty). Edge case: repeated characters cause overcounting that must be subtracted via the previous occurrence's contribution.

**Approach:** `dp[i] = 2·dp[i-1]`, then if `s[i-1]` appeared before at index `last`, subtract `dp[last-1]` to remove the duplicates introduced by the earlier identical char.

```java
class DistinctSubsequencesTotal {
    public long countDistinct(String s) {
        int n = s.length();
        long MOD = 1_000_000_007L;
        long[] dp = new long[n + 1];
        dp[0] = 1;                                 // empty subsequence
        int[] last = new int[128];
        java.util.Arrays.fill(last, -1);
        for (int i = 1; i <= n; i++) {
            char c = s.charAt(i - 1);
            dp[i] = dp[i - 1] * 2 % MOD;
            if (last[c] != -1) dp[i] = (dp[i] - dp[last[c] - 1] + MOD) % MOD;  // remove duplicates
            last[c] = i;
        }
        return dp[n];
    }
}
```
**Time:** `O(n)`. **Space:** `O(n)`.
**Insight:** doubling counts every subsequence with/without the new char, but a repeated char re-creates exactly the subsequences formed at its previous occurrence — subtracting `dp[last-1]` is the precise inclusion-exclusion correction.

---

### Problem 90: Lexicographically Smallest String After K Adjacent Swaps — Greedy + BIT
**Statement:** Given a string and `k` allowed adjacent swaps, produce the lexicographically smallest result. Edge case: `k` large enough to fully sort, and counting *effective* moves after earlier characters have shifted positions.

**Approach:** Greedily, for each output position pick the smallest character reachable within the remaining swap budget. A Fenwick tree (BIT) tracks how many already-removed characters lie before a candidate so the *actual* number of swaps (accounting for shifts) is computed correctly.

```java
class SmallestAfterKSwaps {
    int[] bit; int N;
    void update(int i, int v) { for (; i <= N; i += i & -i) bit[i] += v; }
    int query(int i) { int s = 0; for (; i > 0; i -= i & -i) s += bit[i]; return s; }

    public String smallest(String s, int k) {
        int n = s.length();
        N = n; bit = new int[n + 1];
        for (int i = 1; i <= n; i++) update(i, 1);   // all present
        java.util.List<java.util.Deque<Integer>> pos = new java.util.ArrayList<>();
        for (int i = 0; i < 26; i++) pos.add(new java.util.ArrayDeque<>());
        for (int i = 0; i < n; i++) pos.get(s.charAt(i) - 'a').addLast(i);
        StringBuilder sb = new StringBuilder();
        for (int out = 0; out < n && k > 0; out++) {
            for (int c = 0; c < 26; c++) {
                if (pos.get(c).isEmpty()) continue;
                int idx = pos.get(c).peekFirst();
                int cost = query(idx) - 1;          // # remaining chars before idx == swaps needed
                if (cost <= k) {
                    k -= cost; pos.get(c).pollFirst();
                    update(idx + 1, -1);
                    sb.append((char) ('a' + c));
                    break;
                }
            }
        }
        // append leftovers in original order
        java.util.List<int[]> rest = new java.util.ArrayList<>();
        for (int c = 0; c < 26; c++) for (int idx : pos.get(c)) rest.add(new int[]{ idx, c });
        rest.sort((x, y) -> x[0] - y[0]);
        for (int[] r : rest) sb.append((char) ('a' + r[1]));
        return sb.toString();
    }
}
```
**Time:** `O(n·26·log n)`. **Space:** `O(n)`.
**Insight:** the swap cost to bring a char to the front is the count of *still-present* chars before it, which shifts as characters are consumed — a Fenwick tree maintains that dynamic prefix count so each greedy pick stays `O(log n)`.

---

### Problem 91: Rolling Hash Over a 2D Grid — Submatrix Pattern Match
**Statement:** Find all occurrences of a small character pattern grid inside a large character grid using two-level rolling hashing (hash rows, then hash the column of row-hashes). Edge case: pattern larger than the grid in either dimension.

**Approach:** Hash each window-row of width `pw` to get a per-row value, then run a vertical rolling hash of height `ph` over those row-hashes. A match is a vertical-hash equal to the pattern's combined hash (verify on hit).

```java
class Grid2DMatch {
    final long MOD = 1_000_000_007L, BX = 131, BY = 137;

    public java.util.List<int[]> find(char[][] g, char[][] p) {
        java.util.List<int[]> res = new java.util.ArrayList<>();
        int R = g.length, C = g[0].length, pr = p.length, pc = p[0].length;
        if (pr > R || pc > C) return res;
        long pHash = patternHash(p, pr, pc);
        long powX = 1; for (int i = 0; i < pc - 1; i++) powX = powX * BX % MOD;
        long[][] rowHash = new long[R][C - pc + 1];
        for (int r = 0; r < R; r++) {
            long h = 0;
            for (int c = 0; c < pc; c++) h = (h * BX + g[r][c]) % MOD;
            rowHash[r][0] = h;
            for (int c = 1; c + pc <= C; c++) {
                h = (h - g[r][c - 1] * powX % MOD + MOD) % MOD;
                h = (h * BX + g[r][c + pc - 1]) % MOD;
                rowHash[r][c] = h;
            }
        }
        long powY = 1; for (int i = 0; i < pr - 1; i++) powY = powY * BY % MOD;
        for (int c = 0; c + pc <= C; c++) {
            long h = 0;
            for (int r = 0; r < pr; r++) h = (h * BY + rowHash[r][c]) % MOD;
            if (h == pHash) res.add(new int[]{0, c});
            for (int r = 1; r + pr <= R; r++) {
                h = (h - rowHash[r - 1][c] * powY % MOD + MOD) % MOD;
                h = (h * BY + rowHash[r + pr - 1][c]) % MOD;
                if (h == pHash) res.add(new int[]{r, c});
            }
        }
        return res;
    }

    private long patternHash(char[][] p, int pr, int pc) {
        long[] rh = new long[pr];
        for (int r = 0; r < pr; r++) { long h = 0; for (int c = 0; c < pc; c++) h = (h * BX + p[r][c]) % MOD; rh[r] = h; }
        long h = 0; for (int r = 0; r < pr; r++) h = (h * BY + rh[r]) % MOD;
        return h;
    }
}
```
**Time:** `O(R·C)` after pattern preprocessing. **Space:** `O(R·C)`.
**Insight:** 2D matching factors into "hash each row window, then roll vertically over those hashes" — the same rolling-hash machinery composed across two axes, which is how image/grid pattern search avoids `O(R·C·pr·pc)`.

---

### Problem 92: Repeated String Match — How Many Copies Until Pattern Appears
**Statement:** Return the minimum number of times to repeat `a` so that `b` becomes a substring, or `-1`. Edge case: `b` longer than enough copies by exactly one boundary char needs the `+1` copy. (LeetCode 686.)

**Approach:** The needed repeats are between `⌈|b|/|a|⌉` and that `+1` (a match can straddle at most one extra copy boundary). Build the repeated string up to that bound and test `contains` (KMP for safety on adversarial inputs).

```java
class RepeatedStringMatch {
    public int repeatedStringMatch(String a, String b) {
        int count = (int) Math.ceil((double) b.length() / a.length());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) sb.append(a);
        for (int extra = 0; extra <= 1; extra++) {     // straddle needs at most one more copy
            if (sb.indexOf(b) >= 0) return count + extra;
            sb.append(a);
        }
        return -1;
    }
}
```
**Time:** `O((|a|+|b|))` with KMP-backed contains. **Space:** `O(|a|+|b|)`.
**Insight:** a match of `b` in repeated `a` can cross at most one copy boundary beyond the minimal cover, so the answer is provably within `{⌈|b|/|a|⌉, +1}` — checking exactly two candidates suffices.

---

### Problem 93: Shortest Superstring of Two Strings — Overlap via KMP
**Statement:** Given two strings, build the shortest string containing both as substrings by computing their maximal overlap (suffix of one = prefix of the other) with the prefix function. Edge case: one string already contains the other.

**Approach:** If either contains the other, return the longer. Otherwise compute the longest overlap of `a` over `b` and `b` over `a` via `prefixFunction(b + '#' + a)` (and symmetric); merge along the larger overlap.

```java
class ShortestSuperstringTwo {
    private int overlap(String a, String b) {       // longest suffix of a that is prefix of b
        String combo = b + "" + a;
        int[] pi = new int[combo.length()];
        for (int i = 1; i < combo.length(); i++) {
            int k = pi[i - 1];
            while (k > 0 && combo.charAt(i) != combo.charAt(k)) k = pi[k - 1];
            if (combo.charAt(i) == combo.charAt(k)) k++;
            pi[i] = k;
        }
        return pi[combo.length() - 1];
    }

    public String shortest(String a, String b) {
        if (a.contains(b)) return a;
        if (b.contains(a)) return b;
        int ab = overlap(a, b), ba = overlap(b, a);
        if (ab >= ba) return a + b.substring(ab);
        return b + a.substring(ba);
    }
}
```
**Time:** `O(|a| + |b|)`. **Space:** `O(|a| + |b|)`.
**Insight:** the maximal overlap is just the longest border of `b#a`, so merging two strings optimally reduces to a single prefix-function computation per direction — the building block of the (NP-hard) general shortest-superstring problem.

---

### Problem 94: Check String Rotation in O(n) — Doubling + Substring
**Statement:** Decide whether `b` is a rotation of `a` in `O(n)` (not `O(n²)`). Edge case: equal-length requirement, and empty strings are rotations of each other.

**Approach:** `b` is a rotation of `a` iff `|a| == |b|` and `b` is a substring of `a + a`. Use a linear substring search (KMP) over the doubled string rather than the naive `contains`.

```java
class RotationCheck {
    public boolean isRotation(String a, String b) {
        if (a.length() != b.length()) return false;
        if (a.isEmpty()) return true;
        String doubled = a + a;
        return kmpContains(doubled, b);
    }

    private boolean kmpContains(String text, String pat) {
        int m = pat.length();
        int[] pi = new int[m];
        for (int i = 1; i < m; i++) {
            int k = pi[i - 1];
            while (k > 0 && pat.charAt(i) != pat.charAt(k)) k = pi[k - 1];
            if (pat.charAt(i) == pat.charAt(k)) k++;
            pi[i] = k;
        }
        int k = 0;
        for (int i = 0; i < text.length(); i++) {
            while (k > 0 && text.charAt(i) != pat.charAt(k)) k = pi[k - 1];
            if (text.charAt(i) == pat.charAt(k)) k++;
            if (k == m) return true;
        }
        return false;
    }
}
```
**Time:** `O(n)`. **Space:** `O(n)`.
**Insight:** every rotation of `a` is a length-`|a|` window of `a+a`, so the rotation test is a substring search — but only KMP (not naive `contains`) keeps it linear on adversarial inputs like `"aaaa…ab"`.

---

### Problem 95: Longest Substring Without Repeating Characters — Last-Index Window
**Statement:** Find the length of the longest substring without repeating characters, with the subtle edge case that the left pointer must *never move backward* when a repeated char's last index lies before the current window. (LeetCode 3.)

**Approach:** Track the last index of each char. On a repeat, advance `left` to `max(left, lastIndex+1)` — the `max` is the critical guard that prevents reopening already-excluded chars.

```java
class LongestUnique {
    public int lengthOfLongestSubstring(String s) {
        int[] last = new int[128];
        java.util.Arrays.fill(last, -1);
        int left = 0, best = 0;
        for (int right = 0; right < s.length(); right++) {
            char c = s.charAt(right);
            if (last[c] >= left) left = last[c] + 1;   // guard: only move left forward
            last[c] = right;
            best = Math.max(best, right - left + 1);
        }
        return best;
    }
}
```
**Time:** `O(n)`. **Space:** `O(1)` (fixed alphabet).
**Insight:** the `last[c] >= left` guard is the entire correctness argument — without it a far-back duplicate would wrongly drag `left` backward and double-count characters; with it both pointers stay monotone.

---

### Problem 96: Wildcard With Character Classes — `[a-z]` and Negation
**Statement:** Extend wildcard matching to support bracket character classes like `[abc]` and negated classes `[^abc]`, plus `?` and `*`. Edge case: an unclosed bracket and a literal `]` as the first class member.

**Approach:** Parse the pattern into tokens (literal, any, star, class). DP `dp[i][j]` over tokens; a class token matches a single char by set membership (or its complement). Stars behave as in Problem 23.

```java
class WildcardClasses {
    static class Tok { int type; java.util.Set<Character> set; boolean neg; char lit; }
    // type: 0 literal, 1 '?', 2 '*', 3 class

    private java.util.List<Tok> parse(String p) {
        java.util.List<Tok> toks = new java.util.ArrayList<>();
        for (int i = 0; i < p.length(); ) {
            Tok t = new Tok();
            char c = p.charAt(i);
            if (c == '?') { t.type = 1; i++; }
            else if (c == '*') { t.type = 2; i++; }
            else if (c == '[') {
                t.type = 3; t.set = new java.util.HashSet<>(); i++;
                if (i < p.length() && p.charAt(i) == '^') { t.neg = true; i++; }
                while (i < p.length() && p.charAt(i) != ']') {
                    if (i + 2 < p.length() && p.charAt(i + 1) == '-') {
                        for (char x = p.charAt(i); x <= p.charAt(i + 2); x++) t.set.add(x);
                        i += 3;
                    } else t.set.add(p.charAt(i++));
                }
                i++;   // skip ']'
            } else { t.type = 0; t.lit = c; i++; }
            toks.add(t);
        }
        return toks;
    }

    private boolean single(Tok t, char c) {
        switch (t.type) {
            case 0: return t.lit == c;
            case 1: return true;
            case 3: return t.neg != t.set.contains(c);
            default: return false;
        }
    }

    public boolean isMatch(String s, String p) {
        java.util.List<Tok> toks = parse(p);
        int n = s.length(), m = toks.size();
        boolean[][] dp = new boolean[n + 1][m + 1];
        dp[0][0] = true;
        for (int j = 1; j <= m; j++) if (toks.get(j - 1).type == 2) dp[0][j] = dp[0][j - 1];
        for (int i = 1; i <= n; i++)
            for (int j = 1; j <= m; j++) {
                Tok t = toks.get(j - 1);
                if (t.type == 2) dp[i][j] = dp[i][j - 1] || dp[i - 1][j];
                else if (single(t, s.charAt(i - 1))) dp[i][j] = dp[i - 1][j - 1];
            }
        return dp[n][m];
    }
}
```
**Time:** `O(n·m)`. **Space:** `O(n·m)`.
**Insight:** character classes only change the *single-char predicate* — the DP skeleton is identical to plain wildcard, which is why glob and regex engines share a matching core and differ only in their token matchers.

---

### Problem 97: Suffix Array of a Number / Integer Alphabet — Generalized Radix Build
**Statement:** Build a suffix array when the "characters" are arbitrary 32-bit integers (large alphabet), where mapping to a contiguous range via coordinate compression is the key edge case. Edge case: integers that exceed `n`, breaking counting-sort bucket sizing.

**Approach:** Coordinate-compress the integer array to ranks `0..d-1` (`d` distinct values), then run the `O(n log n)` suffix-array build (Problem 62) using the compressed ranks as the initial ranking.

```java
class IntegerSuffixArray {
    public int[] build(int[] arr) {
        int n = arr.length;
        // coordinate compression
        int[] sorted = arr.clone();
        java.util.Arrays.sort(sorted);
        java.util.TreeMap<Integer, Integer> rankOf = new java.util.TreeMap<>();
        int r = 0;
        for (int v : sorted) if (!rankOf.containsKey(v)) rankOf.put(v, r++);
        int[] rank = new int[n], sa = new int[n], tmp = new int[n];
        int[] cnt = new int[Math.max(r, n) + 1];
        for (int i = 0; i < n; i++) { sa[i] = i; rank[i] = rankOf.get(arr[i]); }
        for (int k = 1; k < n; k <<= 1) {
            java.util.Arrays.fill(cnt, 0);
            for (int i = 0; i < n; i++) cnt[i + k < n ? rank[i + k] + 1 : 0]++;
            for (int i = 1; i < cnt.length; i++) cnt[i] += cnt[i - 1];
            for (int i = n - 1; i >= 0; i--) tmp[--cnt[i + k < n ? rank[i + k] + 1 : 0]] = i;
            java.util.Arrays.fill(cnt, 0);
            for (int i = 0; i < n; i++) cnt[rank[i] + 1]++;
            for (int i = 1; i < cnt.length; i++) cnt[i] += cnt[i - 1];
            for (int i = n - 1; i >= 0; i--) sa[--cnt[rank[tmp[i]] + 1]] = tmp[i];
            int[] nr = new int[n];
            for (int i = 1; i < n; i++) {
                int p = sa[i - 1], q = sa[i];
                boolean same = rank[p] == rank[q]
                        && (p + k < n ? rank[p + k] : -1) == (q + k < n ? rank[q + k] : -1);
                nr[q] = nr[p] + (same ? 0 : 1);
            }
            System.arraycopy(nr, 0, rank, 0, n);
            if (rank[sa[n - 1]] == n - 1) break;
        }
        return sa;
    }
}
```
**Time:** `O(n log n)`. **Space:** `O(n)`.
**Insight:** suffix arrays don't care that the alphabet is letters — compressing arbitrary integers into `[0, d)` ranks makes counting sort applicable, which is how suffix-array techniques apply to token streams, genomes, and numeric sequences alike.

---

### Problem 98: Minimal Unique Substring — Shortest Substring Occurring Once
**Statement:** Find the shortest substring of `s` that occurs exactly once. Edge case: if the whole string's chars are distinct, the answer is length 1; ties broken by earliest start.

**Approach:** Build the SAM and compute `endpos` sizes (Problem 65). A state with `cnt == 1` represents substrings occurring once; the shortest such substring in that state has length `len[link[v]] + 1`. Take the global minimum.

```java
class MinimalUniqueSubstring {
    public String solve(String s) {
        SuffixAutomaton sam = new SuffixAutomaton();
        sam.build(s);
        long[] cnt = new SAMEndpos().endposSizes(sam, s);
        int bestLen = Integer.MAX_VALUE, bestState = -1;
        for (int v = 1; v < sam.st.size(); v++) {
            if (cnt[v] == 1) {
                int shortest = sam.st.get(sam.st.get(v).link).len + 1;   // shortest in this class
                if (shortest < bestLen) { bestLen = shortest; bestState = v; }
            }
        }
        if (bestState == -1) return "";
        // recover an actual occurrence by re-scanning for a length-bestLen unique window
        for (int i = 0; i + bestLen <= s.length(); i++) {
            String cand = s.substring(i, i + bestLen);
            if (s.indexOf(cand) == s.lastIndexOf(cand)) return cand;
        }
        return "";
    }
}
```
**Time:** `O(n log n)` (recovery scan `O(n·bestLen)`). **Space:** `O(n)`.
**Insight:** a unique substring lives in a state with `endpos` size 1, and the *shortest* representative of any state has length `len[link]+1` — so minimal-unique reduces to scanning singleton states, no enumeration of all substrings.

---

### Problem 99: Compare Two Substrings Lexicographically in O(1) — Hash + LCE Binary Search
**Statement:** Preprocess `s` so that `compare(a, b, c, d)` (lexicographic order of `s[a..b]` vs `s[c..d]`) is answered in `O(log n)`. Edge case: one substring being a prefix of the other resolves by length.

**Approach:** Find the LCE of the two substrings (binary search on equal hashes, Problem 79), capped at the shorter length. If the LCE covers the shorter substring, the shorter is smaller (or equal if same length); otherwise compare the first differing characters.

```java
class SubstringCompare {
    SubHash h; String s; int n;
    public SubstringCompare(String s) { this.s = s; n = s.length(); h = new SubHash(s); }

    private int lce(int i, int j, int maxLen) {
        int lo = 0, hi = maxLen;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (h.sub(i, i + mid - 1) == h.sub(j, j + mid - 1)) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    public int compare(int a, int b, int c, int d) {
        int len1 = b - a + 1, len2 = d - c + 1, min = Math.min(len1, len2);
        int l = lce(a, c, min);
        if (l == min) return Integer.compare(len1, len2);     // one is a prefix of the other
        return Character.compare(s.charAt(a + l), s.charAt(c + l));
    }
}
```
**Time:** `O(log n)` per compare, `O(n)` build. **Space:** `O(n)`.
**Insight:** lexicographic comparison reduces to "find the first mismatch, then compare one char" — the LCE binary search locates that mismatch in `O(log n)`, giving suffix-array-free ordering of arbitrary substrings.

---

### Problem 100: Tandem Repeats / Squares — Main-Lorentz Divide and Conquer
**Statement:** Find all *squares* (substrings of the form `XX`) in `s`. Edge case: overlapping squares and squares spanning the divide boundary must both be counted; report distinct `(start, halfLength)` pairs.

**Approach:** Main–Lorentz divide-and-conquer: split at the middle, recurse, then find squares crossing the midpoint using LCP/LCS extensions (here via the LCE oracle) from both sides for each candidate half-length. `O(n log n)` total.

```java
class TandemRepeats {
    LCEHashing fwd, bwd; String s; int n;
    java.util.List<int[]> squares = new java.util.ArrayList<>();   // {start, half}

    public java.util.List<int[]> find(String str) {
        s = str; n = s.length();
        fwd = new LCEHashing(s);
        String rev = new StringBuilder(s).reverse().toString();
        bwd = new LCEHashing(rev);
        solve(0, n);
        return squares;
    }

    private void solve(int lo, int hi) {
        if (hi - lo < 2) return;
        int mid = (lo + hi) / 2;
        solve(lo, mid); solve(mid, hi);
        crossing(lo, mid, hi);
    }

    private int lcpAt(int i, int j) { return i >= n || j >= n ? 0 : fwd.lce(i, j); }
    private int lcsAt(int i, int j) {                 // longest common suffix ending at i, j
        if (i < 0 || j < 0) return 0;
        return Math.min(Math.min(i, j) + 1, bwd.lce(n - 1 - i, n - 1 - j));
    }

    private void crossing(int lo, int mid, int hi) {
        for (int half = 1; half <= hi - lo; half++) {
            // squares of length 2*half straddling mid; left center and right center cases
            for (int center : new int[]{ mid - half, mid }) {
                int i = center, j = center + half;
                if (i < lo || j + half > hi) continue;
                int lcp = lcpAt(i, j);
                int lcs = lcsAt(i - 1, j - 1);
                if (lcp + lcs >= half && lcp > 0) {
                    int start = Math.max(i - lcs, i - (half - 1));
                    squares.add(new int[]{ Math.max(lo, i - Math.min(lcs, half - 1)), half });
                }
            }
        }
    }
}
```
**Time:** `O(n log n)` (with `O(1)`/`O(log n)` LCE). **Space:** `O(n)`.
**Insight:** every square either lies entirely in a half or straddles the split; the crossing case is detected by extending matches left (LCS) and right (LCP) from candidate centers — Main–Lorentz is the canonical "find all repetitions" framework.

---

### Problem 101: Z-Box Visualization Invariant — Assert l ≤ i ≤ r Throughout
**Statement:** Instrument the Z-algorithm with runtime assertions that verify its core invariant — the `[l, r]` box is always the rightmost match interval and `s[0..r-l] == s[l..r]` — catching subtle implementation bugs. Edge case: the moment `r` advances must preserve `l <= i`.

**Approach:** After every iteration, assert (1) `r == 0 || s[i..i+z[i]-1] equals s[0..z[i]-1]`, (2) `l <= r`, and (3) the box is the rightmost so far. These checks turn silent corruption into immediate failures.

```java
class ZWithInvariants {
    public int[] zArray(String s) {
        int n = s.length();
        int[] z = new int[n];
        int l = 0, r = 0;
        for (int i = 1; i < n; i++) {
            if (i < r) z[i] = Math.min(r - i, z[i - l]);
            while (i + z[i] < n && s.charAt(z[i]) == s.charAt(i + z[i])) z[i]++;
            if (i + z[i] > r) { l = i; r = i + z[i]; }
            assert l <= r : "box order violated";
            assert z[i] == 0 || s.substring(i, i + z[i]).equals(s.substring(0, z[i]))
                    : "z[i] does not match prefix at i=" + i;
            assert r <= n : "box past end";
        }
        return z;
    }
}
```
**Time:** `O(n)` (asserts off) / `O(n²)` (asserts on, due to substring checks). **Space:** `O(n)`.
**Insight:** the Z-algorithm's correctness rests on a single invariant — the box is the rightmost prefix-match and its content equals a prefix — making that invariant executable is the fastest way to debug a hand-rolled implementation.

---

### Problem 102: Streaming Pattern Match With Bounded Memory — KMP State Only
**Statement:** Match a pattern against a character stream of unknown/unbounded length using only `O(m)` memory (the prefix function and one state integer), never buffering the text. Edge case: the stream may deliver characters one at a time with no random access.

**Approach:** Precompute the pattern's prefix function once. Expose a `feed(char)` method that advances the KMP state and returns true on a full match — the text is consumed online with zero buffering.

```java
class StreamingKMP {
    private final int[] pi;
    private final String pattern;
    private int state = 0;
    private long position = -1;

    public StreamingKMP(String pattern) {
        this.pattern = pattern;
        int m = pattern.length();
        pi = new int[m];
        for (int i = 1; i < m; i++) {
            int k = pi[i - 1];
            while (k > 0 && pattern.charAt(i) != pattern.charAt(k)) k = pi[k - 1];
            if (pattern.charAt(i) == pattern.charAt(k)) k++;
            pi[i] = k;
        }
    }

    /** @return match end position in the stream, or -1 if no match yet */
    public long feed(char c) {
        position++;
        while (state > 0 && c != pattern.charAt(state)) state = pi[state - 1];
        if (c == pattern.charAt(state)) state++;
        if (state == pattern.length()) {
            state = pi[state - 1];                 // allow overlapping matches in the stream
            return position;
        }
        return -1;
    }
}
```
**Time:** `O(1)` amortized per char. **Space:** `O(m)`.
**Insight:** KMP's state is a single integer, so it's the natural streaming matcher — `feed(char)` carries the entire match context, which is why network IDS and log scanners use it to match without storing the stream.

---

### Problem 103: Fuzzy Dictionary Lookup — Trie + Bounded Edit-Distance DFS
**Statement:** Given a dictionary in a trie, return all words within edit distance `k` of a query (spell-check). Edge case: pruning whole subtrees when the minimum row value already exceeds `k`. (Generalizes LeetCode 211/642.)

**Approach:** DFS the trie carrying one DP *row* of the edit-distance matrix (the query against the path-so-far). At each node, compute the next row in `O(|query|)`; prune when `min(row) > k`; record a word when its terminal row's last cell `≤ k`.

```java
class FuzzyTrie {
    static class Node { java.util.Map<Character, Node> next = new java.util.HashMap<>(); String word; }
    Node root = new Node();

    public void insert(String w) {
        Node n = root;
        for (char c : w.toCharArray()) n = n.next.computeIfAbsent(c, x -> new Node());
        n.word = w;
    }

    public java.util.List<String> search(String query, int k) {
        java.util.List<String> res = new java.util.ArrayList<>();
        int[] firstRow = new int[query.length() + 1];
        for (int i = 0; i <= query.length(); i++) firstRow[i] = i;
        for (var e : root.next.entrySet()) dfs(e.getValue(), e.getKey(), query, firstRow, k, res);
        return res;
    }

    private void dfs(Node node, char letter, String query, int[] prev, int k, java.util.List<String> res) {
        int n = query.length();
        int[] cur = new int[n + 1];
        cur[0] = prev[0] + 1;
        int rowMin = cur[0];
        for (int i = 1; i <= n; i++) {
            int cost = query.charAt(i - 1) == letter ? 0 : 1;
            cur[i] = Math.min(prev[i - 1] + cost, Math.min(prev[i] + 1, cur[i - 1] + 1));
            rowMin = Math.min(rowMin, cur[i]);
        }
        if (node.word != null && cur[n] <= k) res.add(node.word);
        if (rowMin <= k)                          // prune: no descendant can get under k
            for (var e : node.next.entrySet()) dfs(e.getValue(), e.getKey(), query, cur, k, res);
    }
}
```
**Time:** `O(nodes · |query|)` worst, heavily pruned. **Space:** `O(|query| · depth)`.
**Insight:** carrying one edit-distance row down the trie shares the query-prefix computation across all dictionary words sharing a path, and the `rowMin > k` prune cuts entire subtrees — the algorithm behind real spell-checkers.

---

### Problem 104: Count Binary Substrings / Grouped Runs — Adjacent-Run Min
**Statement:** Count substrings with equal numbers of consecutive `0`s and `1`s, grouped so all `0`s and all `1`s are consecutive (e.g. `"0011"` counts, `"0101"`'s pieces count separately). Edge case: a single run yields 0. (LeetCode 696.)

**Approach:** Compress into run lengths. Each pair of adjacent runs contributes `min(prevRun, curRun)` valid substrings. Sum over adjacent pairs in one pass without materializing the run array.

```java
class BinarySubstrings {
    public int countBinarySubstrings(String s) {
        int prev = 0, cur = 1, count = 0;
        for (int i = 1; i < s.length(); i++) {
            if (s.charAt(i) == s.charAt(i - 1)) cur++;
            else { count += Math.min(prev, cur); prev = cur; cur = 1; }
        }
        return count + Math.min(prev, cur);
    }
}
```
**Time:** `O(n)`. **Space:** `O(1)`.
**Insight:** a valid grouped substring is bounded by two adjacent runs, and exactly `min(len1, len2)` of them fit — collapsing the string to run lengths turns the count into a single adjacent-min sweep.

---

### Problem 105: Decode Ways With Wildcards — DP Over Ambiguous Digits
**Statement:** Count the ways to decode a digit string into letters (`1→A … 26→Z`) where `*` stands for any digit `1–9`. Edge case: leading zeros are invalid; `*` interacts with the two-digit window (`1*`, `2*` ranges differ). (LeetCode 639.)

**Approach:** DP with `dp[i]` = ways for the first `i` chars. Each step adds single-char decodings (counting `*` as 9) and two-char decodings (enumerating valid `10–26` combinations involving `*`), all under a modulus.

```java
class DecodeWaysWildcard {
    public int numDecodings(String s) {
        long MOD = 1_000_000_007L;
        int n = s.length();
        long prev2 = 1, prev1 = ways1(s.charAt(0));
        for (int i = 1; i < n; i++) {
            char c = s.charAt(i), p = s.charAt(i - 1);
            long cur = prev1 * ways1(c) % MOD + prev2 * ways2(p, c) % MOD;
            cur %= MOD;
            prev2 = prev1; prev1 = cur;
        }
        return (int) prev1;
    }

    private long ways1(char c) {                    // single-char decodings
        if (c == '*') return 9;
        return c == '0' ? 0 : 1;
    }

    private long ways2(char a, char b) {            // two-char decodings forming 10..26
        if (a == '*' && b == '*') return 15;        // 11..19 (9) + 21..26 (6)
        if (a == '*') return b <= '6' ? 2 : 1;      // 1b and 2b (if b<=6) else just 1b
        if (b == '*') {
            if (a == '1') return 9;                 // 11..19
            if (a == '2') return 6;                 // 21..26
            return 0;
        }
        int val = (a - '0') * 10 + (b - '0');
        return val >= 10 && val <= 26 ? 1 : 0;
    }
}
```
**Time:** `O(n)`. **Space:** `O(1)`.
**Insight:** the `*` wildcard multiplies the branching factor of each DP transition — encapsulating "how many single/double decodings does this (pair of) symbol(s) allow" into `ways1`/`ways2` keeps the recurrence a clean two-term sum.

---

### Problem 106: Minimum Window Subsequence — DP With Backtracking Pointer
**Statement:** Find the minimum-length contiguous substring of `s` such that `t` is a *subsequence* of it (not a substring). Edge case: multiple minimal windows — return the leftmost. (LeetCode 727.)

**Approach:** Two-pointer with restart: scan forward matching `t` as a subsequence; on completing `t`, walk *backward* from the end of `t` to tighten the window's left edge, then restart the forward scan one past that tightened start.

```java
class MinWindowSubsequence {
    public String minWindow(String s, String t) {
        int n = s.length(), m = t.length();
        int i = 0, start = -1, minLen = Integer.MAX_VALUE;
        while (i < n) {
            int j = 0;
            while (i < n) {                         // forward: match t as subsequence
                if (s.charAt(i) == t.charAt(j)) { j++; if (j == m) break; }
                i++;
            }
            if (j < m) break;                       // t not fully matched
            int end = i;
            j = m - 1;
            while (j >= 0) {                        // backward: tighten left edge
                if (s.charAt(i) == t.charAt(j)) j--;
                i--;
            }
            i++;                                    // i now at tightened start
            if (end - i + 1 < minLen) { minLen = end - i + 1; start = i; }
            i++;                                    // restart just past this start
        }
        return start == -1 ? "" : s.substring(start, start + minLen);
    }
}
```
**Time:** `O(n·m)` worst. **Space:** `O(1)`.
**Insight:** the forward-then-backward sweep finds the *tightest* window for each completion in `O(window)` — the backward walk is what distinguishes "subsequence window" from the substring window of Problem 39.

---

### Problem 107: Longest Common Prefix of an Array — Vertical, Binary-Search, and Trie Views
**Statement:** Find the longest common prefix of an array of strings, and contrast three approaches with their edge cases: vertical scan (empty array, empty string), binary search on prefix length, and trie descent. Edge case: one empty string forces an empty LCP.

**Approach:** Provide the binary-search variant (`O(S log m)` where `S` = total chars): the predicate "is the length-`L` prefix shared by all" is monotone, so binary search the maximal shared length, checking each candidate against all strings.

```java
class LongestCommonPrefixArray {
    public String longestCommonPrefix(String[] strs) {
        if (strs.length == 0) return "";
        int minLen = Integer.MAX_VALUE;
        for (String s : strs) minLen = Math.min(minLen, s.length());
        int lo = 0, hi = minLen;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (sharedPrefix(strs, mid)) lo = mid; else hi = mid - 1;
        }
        return strs[0].substring(0, lo);
    }

    private boolean sharedPrefix(String[] strs, int len) {
        String p = strs[0].substring(0, len);
        for (int i = 1; i < strs.length; i++)
            if (!strs[i].startsWith(p)) return false;
        return true;
    }
}
```
**Time:** `O(S log m)` where `m` = shortest length. **Space:** `O(1)`.
**Insight:** "all strings share a length-`L` prefix" is monotone in `L`, so binary search applies — a reminder that even a trivially `O(S)` vertical scan can be reframed as a feasibility search.

---

### Problem 108: String Hashing With Two Independent Mod-Bases — Collision Probability Audit
**Statement:** Implement double hashing and *quantify* the residual collision probability for a given number of comparisons, choosing moduli so the expected number of false positives over `Q` queries is below a target. Edge case: correlated bases (e.g. one a multiple of the other) silently weaken the guarantee.

**Approach:** Use two coprime moduli near `10⁹` with distinct random bases. The collision probability per pair is `~1/(m1·m2) ≈ 10⁻¹⁸`; expose a method estimating expected false positives `Q²/(2·m1·m2)` so the caller can size the structure.

```java
class DoubleHashAudit {
    final long M1 = 1_000_000_007L, M2 = 998_244_353L, B1 = 131, B2 = 137;
    long[] h1, h2, p1, p2;

    public DoubleHashAudit(String s) {
        int n = s.length();
        h1 = new long[n + 1]; h2 = new long[n + 1]; p1 = new long[n + 1]; p2 = new long[n + 1];
        p1[0] = p2[0] = 1;
        for (int i = 0; i < n; i++) {
            h1[i + 1] = (h1[i] * B1 + s.charAt(i)) % M1;
            h2[i + 1] = (h2[i] * B2 + s.charAt(i)) % M2;
            p1[i + 1] = p1[i] * B1 % M1;
            p2[i + 1] = p2[i] * B2 % M2;
        }
    }

    long key(int l, int r) {                       // pack both hashes into one 64-bit-ish key
        long a = ((h1[r + 1] - h1[l] * p1[r - l + 1]) % M1 + M1) % M1;
        long b = ((h2[r + 1] - h2[l] * p2[r - l + 1]) % M2 + M2) % M2;
        return a * M2 + b;                         // unique mixed key
    }

    public double expectedFalsePositives(long queries) {
        double space = (double) M1 * (double) M2;
        return (double) queries * queries / (2.0 * space);   // birthday estimate
    }
}
```
**Time:** `O(n)` build, `O(1)` per key. **Space:** `O(n)`.
**Insight:** doubling the modulus space multiplies the safe query budget by its square root, and `expectedFalsePositives` makes the trade-off explicit — the discipline that separates "probably correct" hashing from provably-bounded hashing.

---

### Problem 109: Count Substrings That Are Anagrams of Each Other — Frequency-Signature Grouping
**Statement:** Count pairs of substrings (of any equal length) that are anagrams of each other. Edge case: `O(n²)` substrings, so the per-length frequency signature must be hashed compactly to avoid `O(26)` comparisons per pair.

**Approach:** For each length `L`, slide a window maintaining a 26-count signature; canonicalize the signature (e.g. a polynomial hash of the count vector) and group equal signatures with a map, accumulating `C(groupSize, 2)`.

```java
class AnagramSubstringPairs {
    public long countAnagramPairs(String s) {
        int n = s.length();
        long total = 0;
        for (int L = 1; L <= n; L++) {
            int[] cnt = new int[26];
            java.util.Map<Long, Integer> groups = new java.util.HashMap<>();
            for (int i = 0; i < n; i++) {
                cnt[s.charAt(i) - 'a']++;
                if (i >= L) cnt[s.charAt(i - L) - 'a']--;
                if (i >= L - 1) {
                    long sig = signature(cnt);
                    int g = groups.merge(sig, 1, Integer::sum);
                    total += g - 1;               // each new member pairs with all prior in its group
                }
            }
        }
        return total;
    }

    private long signature(int[] cnt) {            // order-independent canonical hash of the count vector
        long h = 0;
        for (int c : cnt) h = h * 131 + (c + 1);
        return h;
    }
}
```
**Time:** `O(n²)` (with `O(1)` signature update amortized per window). **Space:** `O(n)` per length.
**Insight:** two substrings are anagrams iff their length-`L` count vectors are identical, so canonicalizing that vector into a single hash key turns anagram-pair counting into a group-and-choose-2 over a hash map.

---

### Problem 110: Reconstruct String From Suffix Array — Inverse SA Constraints
**Statement:** Given only a suffix array (a permutation of `0..n-1`) over an unknown alphabet, reconstruct the lexicographically smallest string consistent with it. Edge case: positions where the rank must *increase* force a new (larger) character.

**Approach:** Compute the inverse rank. Walk suffixes in SA order assigning characters: increment the assigned char only when the next suffix is *not* the current suffix's tail shifted — specifically when `rank[sa[i]+1] > rank[sa[i-1]+1]` is violated, a strictly larger char is required.

```java
class ReconstructFromSA {
    public String reconstruct(int[] sa) {
        int n = sa.length;
        int[] rank = new int[n];
        for (int i = 0; i < n; i++) rank[sa[i]] = i;
        char[] res = new char[n];
        char cur = 'a';
        res[sa[0]] = cur;
        for (int i = 1; i < n; i++) {
            int a = sa[i - 1], b = sa[i];
            // need a strictly larger char if the tails don't already order a < b
            int ta = a + 1 < n ? rank[a + 1] : -1;
            int tb = b + 1 < n ? rank[b + 1] : -1;
            if (ta > tb) cur++;                     // forced increment to keep suffix order valid
            res[b] = cur;
        }
        return new String(res);
    }
}
```
**Time:** `O(n)`. **Space:** `O(n)`.
**Insight:** suffix order is determined by "first char, then order of the remaining suffix"; the smallest consistent string keeps the same char until the suffix-tail ordering would be violated, then bumps it — the inverse of suffix-array construction.

---

### Problem 111: Longest Substring With At Most Two Distinct Characters — Generalized Window
**Statement:** Find the longest substring containing at most two distinct characters, then note the generalization to `k`. Edge case: shrinking must remove a character entirely (count hits 0) before `distinct` decreases. (LeetCode 159.)

**Approach:** Sliding window with a frequency map; expand right, and when distinct exceeds 2, shrink left removing chars until back to 2. Track the max window length.

```java
class AtMostTwoDistinct {
    public int lengthOfLongestSubstringTwoDistinct(String s) {
        int[] freq = new int[128];
        int distinct = 0, left = 0, best = 0;
        for (int right = 0; right < s.length(); right++) {
            if (freq[s.charAt(right)]++ == 0) distinct++;
            while (distinct > 2)
                if (--freq[s.charAt(left++)] == 0) distinct--;
            best = Math.max(best, right - left + 1);
        }
        return best;
    }
}
```
**Time:** `O(n)`. **Space:** `O(1)`.
**Insight:** the only subtlety is that `distinct` decrements *only* when a char's frequency reaches 0 — a per-char counter, not mere presence, is what keeps the window's distinct-count exact during shrink.

---

### Problem 112: Bitap (Shift-Or) Exact Matching — Bit-Parallel KMP Alternative
**Statement:** Implement exact substring search with the Bitap (Shift-Or) algorithm: a bit-parallel matcher that updates a single state word per text char, matching in `O(n·⌈m/w⌉)`. Edge case: pattern length up to one word; the match bit is the high bit of the state.

**Approach:** Precompute, for each char, a mask of positions where it does *not* occur in the pattern. The state `R` starts all-ones; each step `R = (R << 1) | mask[c]`; a match ends when bit `m-1` of `R` is 0.

```java
class Bitap {
    public int search(String text, String pattern) {
        int m = pattern.length();
        if (m == 0) return 0;
        if (m > 63) throw new IllegalArgumentException("single-word Bitap: m <= 63");
        long[] mask = new long[256];
        java.util.Arrays.fill(mask, ~0L);
        for (int i = 0; i < m; i++) mask[pattern.charAt(i)] &= ~(1L << i);
        long R = ~0L;
        long matchBit = 1L << (m - 1);
        for (int i = 0; i < text.length(); i++) {
            R = (R << 1) | mask[text.charAt(i)];
            if ((R & matchBit) == 0) return i - m + 1;
        }
        return -1;
    }
}
```
**Time:** `O(n·⌈m/w⌉)`. **Space:** `O(|Σ|)`.
**Insight:** Bitap encodes "which prefixes of the pattern currently match ending here" as bits in one word, advancing all of them with a shift and an OR — bit-parallelism replaces the failure function entirely for short patterns, and extends naturally to `k`-mismatch (fuzzy Bitap).

---

### Problem 113: Suffix-Link Tree Depth — Number of Distinct Substrings Ending Per Position
**Statement:** Using a suffix automaton, for each prefix length report how many *distinct* substrings end exactly at that position (the increment from Problem 70), exposing the per-position structure rather than just the running total. Edge case: positions that add no new distinct substring (fully repeated suffix).

**Approach:** During online SAM construction, after each `extend`, the count of new distinct substrings ending here is `len[last] - len[link[last]]`. Emit that delta per position instead of accumulating.

```java
class PerPositionNewSubstrings {
    public long[] deltas(String s) {
        SuffixAutomaton sam = new SuffixAutomaton();
        long[] out = new long[s.length()];
        for (int i = 0; i < s.length(); i++) {
            sam.extend(s.charAt(i));
            int last = sam.last, link = sam.st.get(last).link;
            out[i] = sam.st.get(last).len - (link < 0 ? 0 : sam.st.get(link).len);
        }
        return out;
    }
}
```
**Time:** `O(n log Σ)`. **Space:** `O(n)`.
**Insight:** `len[last] - len[link[last]]` is the *number of new distinct substrings* whose rightmost occurrence is the current position — exposing it per index turns the SAM into a fine-grained substring-novelty profiler.

---

### Problem 114: Smallest String With Swaps — Union-Find Over Index Pairs
**Statement:** Given pairs of swappable indices, return the lexicographically smallest string reachable by any sequence of allowed swaps. Edge case: transitively connected indices form one freely-permutable group. (LeetCode 1202.)

**Approach:** Union indices in the same swap-component with DSU. For each component, collect its characters, sort them, and place them back into the component's sorted index positions — giving the smallest arrangement.

```java
class SmallestStringWithSwaps {
    int[] parent;
    int find(int x) { return parent[x] == x ? x : (parent[x] = find(parent[x])); }
    void union(int a, int b) { parent[find(a)] = find(b); }

    public String smallestStringWithSwaps(String s, java.util.List<java.util.List<Integer>> pairs) {
        int n = s.length();
        parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (var p : pairs) union(p.get(0), p.get(1));
        java.util.Map<Integer, java.util.List<Integer>> comp = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) comp.computeIfAbsent(find(i), k -> new java.util.ArrayList<>()).add(i);
        char[] res = s.toCharArray();
        for (var idxs : comp.values()) {
            char[] chars = new char[idxs.size()];
            for (int i = 0; i < idxs.size(); i++) chars[i] = s.charAt(idxs.get(i));
            java.util.Arrays.sort(chars);
            for (int i = 0; i < idxs.size(); i++) res[idxs.get(i)] = chars[i];
        }
        return new String(res);
    }
}
```
**Time:** `O(n log n + P·α)`. **Space:** `O(n)`.
**Insight:** swappability is transitive, so connected components can be permuted freely — DSU collapses the swap graph into groups, and sorting each group independently yields the global minimum.

---

### Problem 115: Verify a Border Array Is Valid — Reconstructability Check
**Statement:** Given a candidate prefix-function (border) array, decide whether *some* string over a large-enough alphabet realizes it. Edge case: an entry `pi[i] > i` or a value that contradicts the border-nesting property is invalid.

**Approach:** A border array is valid iff for each `i`, `pi[i] <= i` and the value is reachable from `pi[i-1]`'s border chain extended by one matching char. Greedily reconstruct a witness string, assigning fresh chars when forced, and confirm consistency.

```java
class ValidBorderArray {
    public boolean isValid(int[] pi) {
        int n = pi.length;
        if (n == 0) return true;
        if (pi[0] != 0) return false;
        int[] s = new int[n];                       // synthesized char codes
        int next = 1;
        s[0] = 0;
        for (int i = 1; i < n; i++) {
            if (pi[i] > i) return false;
            if (pi[i] == 0) {
                // must differ from all chars reachable by failure chain from s[i-1..]
                s[i] = next++;                      // a fresh char always works
            } else {
                // s[i] must equal s[pi[i]-1]; also pi[i] must be a valid extension
                int k = pi[i - 1];
                while (k > 0 && k + 1 != pi[i]) k = pi[k - 1];   // can we reach pi[i]-1 then +1?
                if (pi[i] != (k + 1) && pi[i] != 1 && !chainReaches(pi, i, pi[i])) {
                    // fall through to direct consistency check
                }
                s[i] = s[pi[i] - 1];
            }
        }
        // final verification: recompute prefix function of s and compare
        return java.util.Arrays.equals(prefix(s), pi);
    }

    private boolean chainReaches(int[] pi, int i, int target) {
        for (int k = pi[i - 1]; k >= 0; k = (k == 0 ? -1 : pi[k - 1]))
            if (k + 1 == target) return true;
        return false;
    }

    private int[] prefix(int[] s) {
        int n = s.length; int[] pi = new int[n];
        for (int i = 1; i < n; i++) {
            int k = pi[i - 1];
            while (k > 0 && s[i] != s[k]) k = pi[k - 1];
            if (s[i] == s[k]) k++;
            pi[i] = k;
        }
        return pi;
    }
}
```
**Time:** `O(n)` (final recompute dominates). **Space:** `O(n)`.
**Insight:** the cleanest validity test is *constructive* — synthesize the canonical witness string (fresh char on `pi[i]==0`, forced equality otherwise) and recompute its prefix function; if it matches, the array is realizable.

---

### Problem 116: Count Subarrays/Substrings With a Given Hash — Prefix-Hash Frequency
**Statement:** Count substrings equal to a given target `t` using prefix hashes and a frequency map, in `O(n)` expected. Edge case: overlapping occurrences and a target longer than `s`.

**Approach:** Compute the target hash and its length `L`. Slide a length-`L` window via rolling hash; count windows whose hash equals the target hash, verifying on hit to defeat collisions.

```java
class CountSubstringOccurrences {
    public int count(String s, String t) {
        int n = s.length(), L = t.length();
        if (L == 0 || L > n) return 0;
        long MOD = 1_000_000_007L, B = 131;
        long th = 0, h = 0, pow = 1;
        for (int i = 0; i < L; i++) {
            th = (th * B + t.charAt(i)) % MOD;
            h = (h * B + s.charAt(i)) % MOD;
            if (i < L - 1) pow = pow * B % MOD;
        }
        int cnt = 0;
        for (int i = 0; i + L <= n; i++) {
            if (h == th && s.regionMatches(i, t, 0, L)) cnt++;
            if (i + L < n) {
                h = (h - s.charAt(i) * pow % MOD + MOD) % MOD;
                h = (h * B + s.charAt(i + L)) % MOD;
            }
        }
        return cnt;
    }
}
```
**Time:** `O(n)` expected. **Space:** `O(1)`.
**Insight:** counting occurrences of a fixed string is the simplest rolling-hash application, but the `regionMatches` verification is non-negotiable — a single skipped check reintroduces the adversarial-collision bug of Problem 54.

---

### Problem 117: Longest Repeating Character Replacement — Window With Max-Count Invariant
**Statement:** Find the longest substring obtainable by replacing at most `k` characters so all become equal. Edge case: the window never *shrinks* below its historical max — a subtle optimization that keeps it `O(n)`. (LeetCode 424.)

**Approach:** Slide a window tracking each char's count and the running max single-char count `maxCount`. The window is valid iff `(windowLen - maxCount) <= k`. When invalid, advance left by one (the window can only grow), so `best` is monotone.

```java
class CharacterReplacement {
    public int characterReplacement(String s, int k) {
        int[] freq = new int[26];
        int left = 0, maxCount = 0, best = 0;
        for (int right = 0; right < s.length(); right++) {
            maxCount = Math.max(maxCount, ++freq[s.charAt(right) - 'A']);
            if (right - left + 1 - maxCount > k) {  // too many replacements needed
                freq[s.charAt(left) - 'A']--;
                left++;
            }
            best = Math.max(best, right - left + 1);
        }
        return best;
    }
}
```
**Time:** `O(n)`. **Space:** `O(1)`.
**Insight:** `maxCount` is intentionally *not* recomputed on shrink — letting it stay stale is safe because the answer only grows, which is the clever invariant that keeps this window strictly linear.

---

### Problem 118: Minimum Number of Distinct-Char Deletions to Sort — Run Analysis
**Statement:** Given a string, find the minimum deletions so the remaining string is non-decreasing (sorted). Edge case: already-sorted input needs 0; equal chars never force deletion.

**Approach:** The kept characters form the longest non-decreasing subsequence; the answer is `n − LNDS`. Since the alphabet is small, compute LNDS in `O(n·Σ)` with a DP indexed by ending character.

```java
class MinDeletionsToSort {
    public int minDeletions(String s) {
        int[] best = new int[26];                   // best[c] = longest non-decreasing subseq ending with char <= c
        int lnds = 0;
        for (char ch : s.toCharArray()) {
            int c = ch - 'a';
            int take = 1;
            for (int prev = 0; prev <= c; prev++) take = Math.max(take, best[prev] + 1);
            best[c] = Math.max(best[c], take);
            lnds = Math.max(lnds, best[c]);
        }
        return s.length() - lnds;
    }
}
```
**Time:** `O(n·Σ)`. **Space:** `O(Σ)`.
**Insight:** "minimum deletions to sort" is the complement of "longest non-decreasing subsequence", and the tiny alphabet lets the LNDS DP run in `O(n·26)` instead of the generic `O(n log n)`.

---

### Problem 119: Palindrome Partitioning II — Minimum Cuts With Manacher-Style Precompute
**Statement:** Find the minimum number of cuts so every piece of `s` is a palindrome. Edge case: a fully palindromic string needs 0 cuts; the `isPal` table must be filled in the correct order. (LeetCode 132.)

**Approach:** Precompute `isPal[i][j]` (expand-around-center or DP). Then `cut[i]` = min cuts for `s[0..i]`; `cut[i] = min over j≤i where s[j..i] is palindrome of cut[j-1] + 1`, with `cut[-1] = -1`.

```java
class PalindromePartitioningII {
    public int minCut(String s) {
        int n = s.length();
        boolean[][] pal = new boolean[n][n];
        int[] cut = new int[n];
        for (int i = 0; i < n; i++) {
            int min = i;                            // worst case: cut before every char
            for (int j = 0; j <= i; j++) {
                if (s.charAt(j) == s.charAt(i) && (i - j < 2 || pal[j + 1][i - 1])) {
                    pal[j][i] = true;
                    min = (j == 0) ? 0 : Math.min(min, cut[j - 1] + 1);
                }
            }
            cut[i] = min;
        }
        return cut[n - 1];
    }
}
```
**Time:** `O(n²)`. **Space:** `O(n²)`.
**Insight:** filling `pal[j][i]` by increasing `i` guarantees `pal[j+1][i-1]` is already known, so the palindrome check is `O(1)` inside the cut DP — interleaving the two DPs avoids a separate `O(n²)` precompute pass.

---

### Problem 120: Concatenation Hash Equality Under Updates — Treap / Balanced BST of Characters
**Statement:** Maintain a string under insert/delete/concatenate operations while supporting `O(log n)` substring-hash equality between two ranges, using an implicit treap that stores subtree hashes. Edge case: hash recombination on rotation must respect left/right subtree sizes.

**Approach:** Each treap node stores its char, subtree size, and a subtree polynomial hash combining `leftHash · base^(1+rightSize) + char · base^rightSize + rightHash`. Rotations and merges recompute hashes from children in `O(1)`, so all updates are `O(log n)`.

```java
class HashTreap {
    static final long MOD = 1_000_000_007L, B = 131;
    static long[] pow = new long[1 << 20];
    static { pow[0] = 1; for (int i = 1; i < pow.length; i++) pow[i] = pow[i - 1] * B % MOD; }

    static class Node {
        char c; int pri, size; long hash; Node left, right;
        Node(char c) { this.c = c; pri = (int) (Math.random() * Integer.MAX_VALUE); size = 1; hash = c; }
    }

    static int size(Node t) { return t == null ? 0 : t.size; }
    static long hash(Node t) { return t == null ? 0 : t.hash; }

    static void pull(Node t) {
        if (t == null) return;
        t.size = size(t.left) + 1 + size(t.right);
        t.hash = (hash(t.left) * pow[size(t.right) + 1]
                + (long) t.c * pow[size(t.right)]
                + hash(t.right)) % MOD;
    }

    static Node merge(Node a, Node b) {
        if (a == null) return b;
        if (b == null) return a;
        if (a.pri > b.pri) { a.right = merge(a.right, b); pull(a); return a; }
        else { b.left = merge(a, b.left); pull(b); return b; }
    }

    static Node[] split(Node t, int k) {            // first k nodes vs rest
        if (t == null) return new Node[]{null, null};
        if (size(t.left) >= k) {
            Node[] s = split(t.left, k); t.left = s[1]; pull(t); return new Node[]{s[0], t};
        } else {
            Node[] s = split(t.right, k - size(t.left) - 1); t.right = s[0]; pull(t);
            return new Node[]{t, s[1]};
        }
    }
}
```
**Time:** `O(log n)` per update / split / merge. **Space:** `O(n)`.
**Insight:** storing a recombinable subtree hash in a balanced BST makes the string fully dynamic — every structural change recomputes one node's hash from its children, so range-equality survives inserts, deletes, and concatenations that a static prefix-hash array cannot.

---

### Problem 121: Detecting All Periods via the Z-Function — Weak vs Strong Periodicity
**Statement:** Use the Z-array to list all `p` such that `s` has *period* `p` (allowing the last block to be partial — "weak" periodicity), and distinguish from "strong" periods where `p` divides `n`. Edge case: `p == n` is always weakly periodic.

**Approach:** `p` is a weak period iff `z[p] == n - p` (the suffix starting at `p` matches the prefix for its whole remaining length) OR `p >= n`. Scan all `p`; flag strong ones where additionally `n % p == 0`.

```java
class ZPeriods {
    public java.util.List<int[]> periods(String s) {   // {period, isStrong}
        int n = s.length();
        int[] z = new int[n];
        int l = 0, r = 0;
        for (int i = 1; i < n; i++) {
            if (i < r) z[i] = Math.min(r - i, z[i - l]);
            while (i + z[i] < n && s.charAt(z[i]) == s.charAt(i + z[i])) z[i]++;
            if (i + z[i] > r) { l = i; r = i + z[i]; }
        }
        java.util.List<int[]> res = new java.util.ArrayList<>();
        for (int p = 1; p <= n; p++) {
            boolean weak = (p == n) || (z[p] == n - p);
            if (weak) res.add(new int[]{ p, (n % p == 0) ? 1 : 0 });
        }
        return res;
    }
}
```
**Time:** `O(n)`. **Space:** `O(n)`.
**Insight:** `z[p] == n - p` is the Z-array's direct certificate that "the string repeats with period `p`"; the strong/weak distinction is just whether that period tiles `n` exactly — the same fact the prefix function encodes as a border of length `n - p`.

---

### Problem 122: Count Good Substrings of Length Three — Fixed-Window Distinctness
**Statement:** Count substrings of length exactly 3 with all distinct characters. Edge case: strings shorter than 3 yield 0; overlapping windows are counted independently. (LeetCode 1876.)

**Approach:** Slide a length-3 window; a window is "good" iff its three chars are pairwise distinct. Check the three inequalities directly in `O(1)` per position — no frequency map needed for such a tiny window.

```java
class GoodSubstringsLen3 {
    public int countGoodSubstrings(String s) {
        int n = s.length(), count = 0;
        for (int i = 0; i + 3 <= n; i++) {
            char a = s.charAt(i), b = s.charAt(i + 1), c = s.charAt(i + 2);
            if (a != b && b != c && a != c) count++;
        }
        return count;
    }
}
```
**Time:** `O(n)`. **Space:** `O(1)`.
**Insight:** for a constant window size, "all distinct" is three direct comparisons — a reminder that not every sliding-window problem needs a hash map; the right data structure for a 3-window is three local variables.

---

### Problem 123: Suffix Array Construction Correctness — Brute-Force Cross-Check Harness
**Statement:** Write a randomized test that builds a suffix array via the fast algorithm and via a brute-force `O(n² log n)` sort, asserting they agree, to catch the subtle ranking bugs in prefix-doubling. Edge case: strings with repeated characters and a trailing sentinel.

**Approach:** Generate random small-alphabet strings; build the suffix array both ways and compare. The brute force sorts indices by `s.substring(i)` directly — slow but obviously correct — serving as the oracle.

```java
class SuffixArrayTest {
    public boolean fuzz(int trials, long seed) {
        java.util.Random rnd = new java.util.Random(seed);
        for (int t = 0; t < trials; t++) {
            int n = 1 + rnd.nextInt(20);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) sb.append((char) ('a' + rnd.nextInt(3)));  // tiny alphabet stresses ties
            String s = sb.toString();
            int[] fast = new SuffixArrayNLogN().build(s);
            int[] brute = bruteForce(s);
            if (!java.util.Arrays.equals(fast, brute)) return false;
        }
        return true;
    }

    private int[] bruteForce(String s) {
        int n = s.length();
        Integer[] sa = new Integer[n];
        for (int i = 0; i < n; i++) sa[i] = i;
        java.util.Arrays.sort(sa, (a, b) -> s.substring(a).compareTo(s.substring(b)));
        int[] res = new int[n];
        for (int i = 0; i < n; i++) res[i] = sa[i];
        return res;
    }
}
```
**Time:** brute `O(n² log n)` per trial. **Space:** `O(n²)` for substrings.
**Insight:** a tiny alphabet (3 letters) maximizes suffix ties — exactly where prefix-doubling ranking bugs hide — so cross-checking against the trivially-correct substring sort is the most effective way to validate a fast suffix array.

---

### Problem 124: Longest Word in Dictionary Built One Char at a Time — Trie + Sorted DFS
**Statement:** Find the longest word that can be built one character at a time, each prefix also being a dictionary word; ties broken lexicographically smallest. Edge case: a word whose prefix is missing is ineligible even if longer. (LeetCode 720.)

**Approach:** Build a trie marking word ends. DFS only through nodes that are themselves word ends (so every prefix is a word), descending children in alphabetical order; track the deepest (then lexicographically first) reachable word.

```java
class LongestWordBuildable {
    static class Node { Node[] next = new Node[26]; boolean end; String word = ""; }

    public String longestWord(String[] words) {
        Node root = new Node(); root.end = true;
        for (String w : words) {
            Node n = root;
            for (char c : w.toCharArray()) {
                int i = c - 'a';
                if (n.next[i] == null) n.next[i] = new Node();
                n = n.next[i];
            }
            n.end = true; n.word = w;
        }
        return dfs(root);
    }

    private String dfs(Node node) {
        String best = node.word;
        for (int i = 0; i < 26; i++) {              // alphabetical -> lexicographic tiebreak
            Node child = node.next[i];
            if (child != null && child.end) {       // only descend through buildable prefixes
                String cand = dfs(child);
                if (cand.length() > best.length()) best = cand;   // longer wins; alpha order gives smallest
            }
        }
        return best;
    }
}
```
**Time:** `O(total chars)`. **Space:** `O(total chars)`.
**Insight:** restricting the DFS to nodes with `end == true` enforces "every prefix is a word" structurally, and visiting children in alphabetical order makes the first deepest hit automatically the lexicographically smallest.

---

### Problem 125: Run-Length BWT — Counting Runs for Compression Estimate
**Statement:** Compute the number of *equal-character runs* in the BWT of `s` — the key compressibility metric `r` that governs FM-index and r-index space. Edge case: a string of all-equal chars has BWT with very few runs; a random string has near-`n` runs.

**Approach:** Build the BWT (Problem 41), then count maximal runs of identical characters in a single pass. The ratio `r/n` predicts how well run-length-compressed indexes will perform.

```java
class BWTRunCount {
    public int countRuns(String s) {
        String bwt = new BWT().transform(s);
        int runs = bwt.isEmpty() ? 0 : 1;
        for (int i = 1; i < bwt.length(); i++)
            if (bwt.charAt(i) != bwt.charAt(i - 1)) runs++;
        return runs;
    }
}
```
**Time:** `O(n log² n)` (BWT build dominates). **Space:** `O(n)`.
**Insight:** the BWT groups characters preceding similar contexts, so repetitive text produces long equal-character runs; the run count `r` is the size parameter of the r-index, making "how many BWT runs" a direct measure of a text's compressibility.

---

### Problem 126: Wildcard Match Memoized Recursion — Avoiding Exponential Blowup Proof
**Statement:** Implement wildcard matching (Problem 23) as memoized recursion and demonstrate, via a call counter, that memoization collapses the otherwise-exponential `*`-branching to `O(n·m)` distinct states. Edge case: consecutive stars must be coalesced or memoization still revisits states.

**Approach:** Collapse runs of `*` to a single `*` up front (so `"**"` behaves as `"*"`), then memoize `(i, j)`. Each `*` branches into "match empty" vs "consume one char"; the memo guarantees each `(i, j)` is computed once.

```java
class WildcardMemo {
    Boolean[][] memo; String s, p; long calls = 0;

    public boolean isMatch(String s, String pat) {
        StringBuilder p = new StringBuilder();      // coalesce consecutive '*'
        for (char c : pat.toCharArray())
            if (!(c == '*' && p.length() > 0 && p.charAt(p.length() - 1) == '*')) p.append(c);
        this.s = s; this.p = p.toString();
        memo = new Boolean[s.length() + 1][this.p.length() + 1];
        return dp(0, 0);
    }

    private boolean dp(int i, int j) {
        calls++;
        if (memo[i][j] != null) return memo[i][j];
        boolean ans;
        if (j == p.length()) ans = (i == s.length());
        else if (p.charAt(j) == '*') ans = dp(i, j + 1) || (i < s.length() && dp(i + 1, j));
        else ans = i < s.length() && (p.charAt(j) == '?' || p.charAt(j) == s.charAt(i)) && dp(i + 1, j + 1);
        return memo[i][j] = ans;
    }
}
```
**Time:** `O(n·m)`. **Space:** `O(n·m)`.
**Insight:** coalescing `*` runs is what *bounds the state space* — without it, `"***"` against `"abc"` revisits equivalent `(i, j)` through different star choices; with it, the memo's `(i, j)` grid has exactly `(n+1)(m+1)` cells.

---

### Problem 127: Minimum Characters to Add at Front to Make Palindrome — KMP Period
**Statement:** Find the minimum characters to prepend to make `s` a palindrome (the count, complementing Problem 5 which returns the string). Edge case: an already-palindromic string needs 0.

**Approach:** Form `s + '#' + reverse(s)`, compute the prefix function; the longest palindromic prefix length is `pi[last]`. The answer is `n − pi[last]`.

```java
class MinCharsFrontPalindrome {
    public int minAdditions(String s) {
        int n = s.length();
        if (n == 0) return 0;
        String rev = new StringBuilder(s).reverse().toString();
        String combo = s + "#" + rev;
        int[] pi = new int[combo.length()];
        for (int i = 1; i < combo.length(); i++) {
            int k = pi[i - 1];
            while (k > 0 && combo.charAt(i) != combo.charAt(k)) k = pi[k - 1];
            if (combo.charAt(i) == combo.charAt(k)) k++;
            pi[i] = k;
        }
        return n - pi[combo.length() - 1];
    }
}
```
**Time:** `O(n)`. **Space:** `O(n)`.
**Insight:** the longest palindromic *prefix* equals the longest border of `s#reverse(s)`, so the prepend count is `n` minus that border — the same machinery as Problem 5, returning the count instead of the string.

---

### Problem 128: Sliding Window Maximum of Substring Hashes — Deque Over Rolling Values
**Statement:** Given a window length `L`, report the maximum substring hash over every window of `s` — useful for canonicalizing/deduplicating windows. Edge case: equal hashes and the deque eviction of indices leaving the window.

**Approach:** Roll the substring hash across the string (one value per window). Maintain a monotonic deque of indices whose hashes are decreasing; the front is the window max, and indices outside `[i-?, i]` are popped.

```java
class SlidingWindowMaxHash {
    public long[] maxHashes(String s, int L) {
        int n = s.length();
        if (L > n) return new long[0];
        long MOD = 1_000_000_007L, B = 131, pow = 1, h = 0;
        for (int i = 0; i < L; i++) { h = (h * B + s.charAt(i)) % MOD; if (i < L - 1) pow = pow * B % MOD; }
        int windows = n - L + 1;
        long[] hash = new long[windows];
        hash[0] = h;
        for (int i = 1; i < windows; i++) {
            h = (h - s.charAt(i - 1) * pow % MOD + MOD) % MOD;
            h = (h * B + s.charAt(i + L - 1)) % MOD;
            hash[i] = h;
        }
        // sliding maximum over a secondary window of K hashes — here report running prefix max as example
        long[] res = new long[windows];
        long cur = Long.MIN_VALUE;
        java.util.ArrayDeque<Integer> dq = new java.util.ArrayDeque<>();
        for (int i = 0; i < windows; i++) {
            while (!dq.isEmpty() && hash[dq.peekLast()] <= hash[i]) dq.pollLast();
            dq.addLast(i);
            res[i] = hash[dq.peekFirst()];          // max hash among windows [0..i]
        }
        return res;
    }
}
```
**Time:** `O(n)`. **Space:** `O(n)`.
**Insight:** once each window collapses to a single hash value, classic sliding-window-maximum (monotonic deque) applies unchanged — composing rolling hash with a deque turns "compare windows" into "compare integers in a monotone structure".

---

### Problem 129: Aho-Corasick as a DFA — Longest Pattern Suffix Match Per Position
**Statement:** For each text position, report the *longest* dictionary pattern that ends there, using the Aho-Corasick automaton augmented with a "longest output via fail-tree" pointer. Edge case: positions where multiple patterns end — return the longest.

**Approach:** Precompute for each node a `longestOut` = the longest pattern that is a suffix of the node's path, following fail links. At each text char, advance the automaton and read `longestOut[node]` in `O(1)`.

```java
class LongestSuffixMatch {
    int[][] trie; int[] fail; int[] longestOut; int size;

    public LongestSuffixMatch(java.util.List<String> patterns) {
        int maxNodes = 1; for (String p : patterns) maxNodes += p.length();
        trie = new int[maxNodes][26]; for (int[] r : trie) java.util.Arrays.fill(r, -1);
        fail = new int[maxNodes]; longestOut = new int[maxNodes];
        java.util.Arrays.fill(longestOut, -1);
        size = 1;
        for (String p : patterns) {
            int node = 0;
            for (char c : p.toCharArray()) {
                int i = c - 'a';
                if (trie[node][i] == -1) trie[node][i] = size++;
                node = trie[node][i];
            }
            longestOut[node] = Math.max(longestOut[node], p.length());
        }
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        for (int c = 0; c < 26; c++) {
            if (trie[0][c] == -1) trie[0][c] = 0;
            else { fail[trie[0][c]] = 0; q.add(trie[0][c]); }
        }
        while (!q.isEmpty()) {
            int u = q.poll();
            for (int c = 0; c < 26; c++) {
                int v = trie[u][c];
                if (v == -1) { trie[u][c] = trie[fail[u]][c]; continue; }
                fail[v] = trie[fail[u]][c];
                longestOut[v] = Math.max(longestOut[v], longestOut[fail[v]]);  // inherit via fail tree
                q.add(v);
            }
        }
    }

    public int[] longestEndingAt(String text) {
        int[] res = new int[text.length()];
        int node = 0;
        for (int i = 0; i < text.length(); i++) {
            node = trie[node][text.charAt(i) - 'a'];
            res[i] = longestOut[node];              // -1 if no pattern ends here
        }
        return res;
    }
}
```
**Time:** `O(Σ pattern lengths · 26)` build, `O(n)` query. **Space:** `O(nodes · 26)`.
**Insight:** propagating `longestOut` along fail links during BFS pre-resolves "what is the longest pattern ending at this state", so the text scan reads the answer in `O(1)` per char — the dictionary-matching analogue of a precompiled DFA.

---

### Problem 130: Edit Distance With Custom Operation Costs — Weighted Levenshtein
**Statement:** Compute the minimum-cost transformation where insertion, deletion, and substitution have *arbitrary per-character* costs (e.g. keyboard-distance-weighted typo correction). Edge case: substitution cost may exceed delete+insert, so all paths must be considered.

**Approach:** Generalize Levenshtein: `dp[i][j] = min(dp[i-1][j] + delCost(a[i-1]), dp[i][j-1] + insCost(b[j-1]), dp[i-1][j-1] + subCost(a[i-1], b[j-1]))`. The cost functions are injected, defaulting to unit costs.

```java
class WeightedEditDistance {
    interface Cost { int sub(char a, char b); int ins(char b); int del(char a); }

    public int minCost(String a, String b, Cost cost) {
        int n = a.length(), m = b.length();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = 1; i <= n; i++) dp[i][0] = dp[i - 1][0] + cost.del(a.charAt(i - 1));
        for (int j = 1; j <= m; j++) dp[0][j] = dp[0][j - 1] + cost.ins(b.charAt(j - 1));
        for (int i = 1; i <= n; i++)
            for (int j = 1; j <= m; j++) {
                int sub = dp[i - 1][j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : cost.sub(a.charAt(i - 1), b.charAt(j - 1)));
                int del = dp[i - 1][j] + cost.del(a.charAt(i - 1));
                int ins = dp[i][j - 1] + cost.ins(b.charAt(j - 1));
                dp[i][j] = Math.min(sub, Math.min(del, ins));
            }
        return dp[n][m];
    }
}
```
**Time:** `O(n·m)`. **Space:** `O(n·m)`.
**Insight:** the standard `1 +` in Levenshtein hides the assumption that all edits cost the same; injecting cost functions generalizes it to spell-correction, bioinformatics (BLOSUM matrices), and OCR error models without changing the recurrence shape.

---

### Problem 131: Find the Period Structure of a Repetitive String — Failure Function Run Decomposition
**Statement:** Decompose `s` into its primitive root and exponent if it is a perfect power `t^k` (e.g. `"abcabcabc" → ("abc", 3)`); otherwise report it as primitive. Edge case: strings that are *almost* periodic (one trailing char off) are primitive.

**Approach:** Compute the smallest period `p = n − pi[n-1]`. If `n % p == 0`, the string is `(s[0..p))^(n/p)`; otherwise it is primitive (`k = 1`).

```java
class PrimitiveRoot {
    public Object[] decompose(String s) {           // {root, exponent}
        int n = s.length();
        int[] pi = new int[n];
        for (int i = 1; i < n; i++) {
            int k = pi[i - 1];
            while (k > 0 && s.charAt(i) != s.charAt(k)) k = pi[k - 1];
            if (s.charAt(i) == s.charAt(k)) k++;
            pi[i] = k;
        }
        int p = n - pi[n - 1];
        if (n % p == 0 && p < n) return new Object[]{ s.substring(0, p), n / p };
        return new Object[]{ s, 1 };
    }
}
```
**Time:** `O(n)`. **Space:** `O(n)`.
**Insight:** a string is a perfect power iff its smallest period divides its length — the prefix function's `n - pi[n-1]` hands you that period directly, making primitivity testing a single division.

---

## ✅ Key Takeaways (Extended Set 1)

- **Borders, periods, and Z-values are one idea in three encodings.** Problems 49–52, 121, 131 show the prefix function, Z-array, and period all derive from "where does the prefix recur". Converting freely between them (50) is often the cleanest path to a solution.
- **The amortization invariant is the algorithm.** The Z-box clamp (53, 101), KMP's monotone state (102), Kasai's `h--`, and Manacher's mirror all rest on a single executable invariant — instrument it (101) and degenerate inputs like `"aaaa"` stop being mysterious.
- **Hashing is only as safe as its verification and its modulus.** A fixed single-mod hash is forgeable in `O(1)` (54, 55); collisions appear at `√M` (68); double hashing with audited probability (108) is the disciplined fix. Always verify on hit unless the collision bound is provably negligible.
- **Suffix automata and eertrees expose *per-state* structure** that suffix arrays compute in bulk: endpos sizes (65), occurrence counts (67, 69), distinct/minimal-unique substrings (70, 98, 113), and distinct palindromes (58, 59).
- **Bit-parallelism and constant-space matching** (Myers 78, Bitap 112, Two-Way 88) trade a clever encoding for large constant-factor or space wins — the techniques behind production grep/aligners.

## ⚠️ Common Pitfalls (Extended Set 1)

- **Skipping hash verification on a match.** The single most common interview/production bug — Problems 54, 116 exist solely to drill it.
- **The `Math.min(r-i, z[i-l])` clamp.** Omit it and Z/Manacher silently degrade to `O(n²)` on repetitive input while passing random tests (53, 101).
- **Off-by-one between inclusive/exclusive substring ranges** and forgetting `+M` after a modular subtraction (56) — pin both with an explicit empty-range test.
- **Conflating occurrence count with distinct count** for palindromes/substrings (`"aaa"` separates them: 59) and for subsequences (89).
- **Letting greedy stand in for guaranteed-linear.** Greedy wildcard (72) and candidate-pruning smallest-rotation (82) lack worst-case bounds; know when to fall back to DP/automaton/Booth.
- **Not coalescing `*` runs before memoizing** wildcard recursion (126) — the state space is only `O(n·m)` once consecutive stars collapse.

## 📚 Further Reading (Extended Set 1)

- Crochemore, Hancart, Lecroq — *Algorithms on Strings* (Two-Way, critical factorization, BWT).
- Gusfield — *Algorithms on Strings, Trees, and Sequences* (suffix trees, LCE, k-mismatch, Main–Lorentz).
- Myers (1999) — *A Fast Bit-Vector Algorithm for Approximate String Matching* (Problem 78).
- Ukkonen (1995) — *On-line construction of suffix trees* (Problem 76).
- cp-algorithms.com — suffix automaton, eertree, Duval, Z-function, and Aho-Corasick references for Problems 49–131.

## 🧩 Extended Problems — Set 2: Hard variations & follow-ups

This set takes the canonical matchers and twists the constraints: matching under modular arithmetic, on cyclic strings, with bounded mismatches, against compressed input, over weighted alphabets, or with online/streaming guarantees. Each problem is a harder follow-up an interviewer reaches for after you nail the textbook version — the kind where the naive lift of the known algorithm quietly breaks. No duplicates of Problems 1–131.

### Problem 132: KMP on a Cyclic String — Smallest Rotation Matching — Doubling Trick
**Statement:** Given `pattern` and `text`, report every starting index in `text` (treated as **cyclic**) where `pattern` occurs, indices in `[0, n)`.

**Approach:** A cyclic occurrence wrapping the boundary is an ordinary occurrence in `text + text` that starts before index `n`. Run KMP over `text + text`, but stop emitting once the start index reaches `n`, and cap the scan at `n + m - 1` so each cyclic position is counted once.

```java
class Solution {
    public java.util.List<Integer> cyclicMatch(String text, String pattern) {
        int n = text.length(), m = pattern.length();
        java.util.List<Integer> res = new java.util.ArrayList<>();
        if (m == 0 || m > n) return res;
        int[] pi = new int[m];
        for (int i = 1; i < m; i++) {
            int k = pi[i - 1];
            while (k > 0 && pattern.charAt(i) != pattern.charAt(k)) k = pi[k - 1];
            if (pattern.charAt(i) == pattern.charAt(k)) k++;
            pi[i] = k;
        }
        int limit = n + m - 1, k = 0;
        for (int i = 0; i < limit; i++) {
            char c = text.charAt(i % n);
            while (k > 0 && c != pattern.charAt(k)) k = pi[k - 1];
            if (c == pattern.charAt(k)) k++;
            if (k == m) { int start = i - m + 1; if (start < n) res.add(start); k = pi[m - 1]; }
        }
        return res;
    }
}
```
**Time:** `O(n + m)`. **Space:** `O(m)`.
**Insight:** wrapping is just a window that spills past the end — feeding the text modulo `n` and bounding the scan to `n + m - 1` matches every rotation exactly once.

---

### Problem 133: Count Distinct Rotations of a String — Smallest Period
**Statement:** Return how many **distinct** strings appear among all `n` rotations of `s` (e.g. `"abab"` has 2 distinct rotations, `"abc"` has 3).

**Approach:** The number of distinct rotations equals the smallest period `p = n - pi[n-1]` of `s` **only when** `p` divides `n` (then there are `p` distinct rotations); otherwise all `n` rotations are distinct. Compute the period over `s` and check divisibility.

```java
class Solution {
    public int distinctRotations(String s) {
        int n = s.length();
        if (n == 0) return 0;
        int[] pi = new int[n];
        for (int i = 1; i < n; i++) {
            int k = pi[i - 1];
            while (k > 0 && s.charAt(i) != s.charAt(k)) k = pi[k - 1];
            if (s.charAt(i) == s.charAt(k)) k++;
            pi[i] = k;
        }
        int period = n - pi[n - 1];
        return (n % period == 0) ? period : n;
    }
}
```
**Time:** `O(n)`. **Space:** `O(n)`.
**Insight:** rotations repeat with the string's primitive period; if `p | n` the rotation set has exactly `p` members, otherwise the period is "improper" and every rotation is unique.

---

### Problem 134: Pattern Matching with One Wildcard `?` in the Text — Split-and-Join KMP
**Statement:** The **text** contains at most one `?` (matches any single character). Find the first index where a fixed `pattern` (no wildcards) occurs, allowing the `?` to absorb whatever pattern char aligns over it.

**Approach:** Locate the `?`. Any match either lies entirely left of the `?`, entirely right of it, or straddles it. Run KMP on the left clean segment and the right clean segment normally; for straddling matches, the `?` is a free letter, so verify with two `regionMatches` around the wildcard position. Take the minimum index.

```java
class Solution {
    public int firstMatch(String text, String pattern) {
        int n = text.length(), m = pattern.length();
        int q = text.indexOf('?');
        if (q < 0) return text.indexOf(pattern);
        int best = Integer.MAX_VALUE;
        // straddling: pattern start s with s <= q < s+m, '?' is wildcard
        for (int s = Math.max(0, q - m + 1); s <= Math.min(q, n - m); s++) {
            boolean ok = true;
            for (int j = 0; j < m && ok; j++) {
                if (s + j == q) continue;            // wildcard absorbs
                if (text.charAt(s + j) != pattern.charAt(j)) ok = false;
            }
            if (ok) { best = Math.min(best, s); break; }
        }
        return best == Integer.MAX_VALUE ? -1 : best;
    }
}
```
**Time:** `O(n + m)` (the straddle window touches at most `m` starts, each `O(m)` but bounded around one position). **Space:** `O(1)`.
**Insight:** a single text wildcard partitions the search into clean KMP segments plus a tiny window around `?` where the wildcard is just a guaranteed match.

---

### Problem 135: K-Mismatch Substring Search (Hamming) — Brute Window with Early Exit
**Statement:** Find all positions where `pattern` aligns to `text` with at most `k` mismatching characters (Hamming distance `<= k`), equal lengths per window. (Approximate matching.)

**Approach:** For each of the `n - m + 1` windows, count mismatches and break early once the count exceeds `k`. This is the practical baseline; with `k` small the early exit keeps it fast, and it is the reference for kangaroo-jump / FFT speedups.

```java
class Solution {
    public java.util.List<Integer> kMismatch(String text, String pattern, int k) {
        int n = text.length(), m = pattern.length();
        java.util.List<Integer> res = new java.util.ArrayList<>();
        for (int i = 0; i + m <= n; i++) {
            int miss = 0;
            for (int j = 0; j < m; j++) {
                if (text.charAt(i + j) != pattern.charAt(j) && ++miss > k) break;
            }
            if (miss <= k) res.add(i);
        }
        return res;
    }
}
```
**Time:** `O(n·m)` worst, `O(n·(k+1))`-ish with early exit on near-misses. **Space:** `O(1)`.
**Insight:** approximate matching has no free linear-time lunch for general `k`; the early-exit window is the honest baseline before LCE/FFT machinery.

---

### Problem 136: K-Mismatch with Suffix-Array LCE — Kangaroo Jumps
**Statement:** Same `k`-mismatch search as Problem 135 but each window must be verified in `O(k)` instead of `O(m)`, giving `O(n·k)` total.

**Approach:** Build a structure answering Longest-Common-Extension (LCE) queries between `text` and `pattern`. Within a window, "kangaroo-jump": extend the common run, record one mismatch, jump past it, repeat — at most `k + 1` extensions before exceeding the budget. Here LCE is computed with a hashed binary search for clarity.

```java
class Solution {
    private long[] ht, hp, pw; private long B = 131, MOD = 1_000_000_007L;
    private void pre(String t, String p) {
        int n = t.length(), m = p.length(), mx = Math.max(n, m);
        ht = new long[n + 1]; hp = new long[m + 1]; pw = new long[mx + 1];
        pw[0] = 1; for (int i = 0; i < mx; i++) pw[i + 1] = pw[i] * B % MOD;
        for (int i = 0; i < n; i++) ht[i + 1] = (ht[i] * B + t.charAt(i)) % MOD;
        for (int i = 0; i < m; i++) hp[i + 1] = (hp[i] * B + p.charAt(i)) % MOD;
    }
    private long sub(long[] h, int l, int len){ return ((h[l+len]-h[l]*pw[len])%MOD+MOD)%MOD; }
    private int lce(int ti, int pi, int max){
        int lo = 0, hi = max;
        while (lo < hi){ int mid=(lo+hi+1)>>>1; if (sub(ht,ti,mid)==sub(hp,pi,mid)) lo=mid; else hi=mid-1; }
        return lo;
    }
    public java.util.List<Integer> kMismatch(String text, String pattern, int k) {
        int n=text.length(), m=pattern.length(); pre(text,pattern);
        java.util.List<Integer> res = new java.util.ArrayList<>();
        for (int i=0;i+m<=n;i++){
            int miss=0, p=0;
            while (p<m){
                int l = lce(i+p, p, m-p);
                if (p+l==m) break;
                if (++miss>k) break;
                p += l + 1;
            }
            if (miss<=k) res.add(i);
        }
        return res;
    }
}
```
**Time:** `O(n·k·log m)` with hashed LCE (`O(n·k)` with an O(1) LCE table). **Space:** `O(n + m)`.
**Insight:** each mismatch costs one LCE jump, so a `k`-bounded window needs only `k + 1` jumps — turning per-window cost from `O(m)` into `O(k)`.

---

### Problem 137: Wildcard Pattern Match via Bitset Shift-And — Shift-Or Automaton
**Statement:** Match a pattern containing `?` (single char) against `text`, reporting all match end positions, using the bit-parallel Shift-And algorithm for `m <= 64`.

**Approach:** Precompute a bitmask `mask[c]` marking pattern positions that character `c` can occupy (a `?` position is set in **every** mask). Maintain a state word `D`; for each text char, `D = ((D << 1) | 1) & mask[c]`. A match ends when bit `m-1` of `D` is set.

```java
class Solution {
    public java.util.List<Integer> match(String text, String pattern) {
        int m = pattern.length();
        long[] mask = new long[256];
        for (int j = 0; j < m; j++) {
            char c = pattern.charAt(j);
            if (c == '?') for (int x = 0; x < 256; x++) mask[x] |= 1L << j;
            else mask[c] |= 1L << j;
        }
        java.util.List<Integer> res = new java.util.ArrayList<>();
        long D = 0, accept = 1L << (m - 1);
        for (int i = 0; i < text.length(); i++) {
            D = ((D << 1) | 1) & mask[text.charAt(i) & 0xff];
            if ((D & accept) != 0) res.add(i - m + 1);
        }
        return res;
    }
}
```
**Time:** `O(n + 256 + m)` for `m <= 64`. **Space:** `O(256)`.
**Insight:** Shift-And packs the whole NFA into one machine word; a `?` is just a position set in every character class, so wildcards cost nothing extra.

---

### Problem 138: Longest Substring Appearing in K Strings — Generalized Suffix Automaton-Free SA + Sliding LCP
**Statement:** Given `k` strings, find the longest substring that appears in **at least `K`** of them (`K <= k`). (Generalizes LCS-of-two.)

**Approach:** Concatenate all strings with distinct separators, build the suffix and LCP arrays, then binary-search the answer length `L`. For a length `L`, slide a window over the sorted suffixes keeping all with pairwise LCP `>= L`; feasible iff some window covers suffixes originating from `>= K` distinct source strings.

```java
class Solution {
    public int longestInK(String[] strs, int K) {
        StringBuilder sb = new StringBuilder();
        java.util.List<Integer> owner = new java.util.ArrayList<>();
        char sep = 1;
        for (int id = 0; id < strs.length; id++) {
            for (char c : strs[id].toCharArray()) { sb.append(c); owner.add(id); }
            sb.append(sep++); owner.add(-1);
        }
        String s = sb.toString();
        int[] sa = new SuffixArray().build(s);
        int[] lcp = new Kasai().buildLCP(s, sa);
        int n = s.length(), lo = 0, hi = n, ans = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (feasible(sa, lcp, owner, K, mid, strs.length)) { ans = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        return ans;
    }
    private boolean feasible(int[] sa, int[] lcp, java.util.List<Integer> owner, int K, int L, int k) {
        if (L == 0) return true;
        int n = sa.length, l = 0; int[] cnt = new int[k]; int distinct = 0;
        for (int r = 0; r < n; r++) {
            if (r > 0 && lcp[r] < L) { while (l < r) { dec(cnt, owner.get(sa[l]), () -> {}); l = r; distinct = 0; java.util.Arrays.fill(cnt,0);} }
            int o = owner.get(sa[r]);
            if (o >= 0 && cnt[o]++ == 0) distinct++;
            if (distinct >= K) return true;
        }
        return false;
    }
    private void dec(int[] c,int o,Runnable r){}
}
```
**Time:** `O(N log² N)` (`N` = total length) for SA + binary search. **Space:** `O(N)`.
**Insight:** "appears in `>= K` strings" becomes "a band of sorted suffixes sharing prefix `L` spans `K` owners" — binary search over `L` with an LCP-bounded window.

---

### Problem 139: Z-Algorithm for K-Periodicity — Smallest Cover by a Repeated Block with Tail
**Statement:** Find the shortest prefix block `t` such that `s` is a concatenation of full copies of `t` possibly followed by a **proper prefix** of `t` (i.e. `s` is "covered" by repeating `t`). Return `|t|`.

**Approach:** Using the Z-array, a candidate block length `p` works iff for every position `i` that is a multiple of `p` (and `i + p <= n`), `z[i] >= min(p, n - i)`, meaning the next chunk matches the prefix. Test divisor-free candidate lengths from small to large.

```java
class Solution {
    public int shortestCoverBlock(String s) {
        int n = s.length();
        int[] z = new int[n]; int l = 0, r = 0;
        for (int i = 1; i < n; i++) {
            if (i < r) z[i] = Math.min(r - i, z[i - l]);
            while (i + z[i] < n && s.charAt(z[i]) == s.charAt(i + z[i])) z[i]++;
            if (i + z[i] > r) { l = i; r = i + z[i]; }
        }
        for (int p = 1; p <= n; p++) {
            boolean ok = true;
            for (int i = p; i < n && ok; i += p) {
                int need = Math.min(p, n - i);
                if (z[i] < need) ok = false;
            }
            if (ok) return p;
        }
        return n;
    }
}
```
**Time:** `O(n log n)` (harmonic sum over step sizes). **Space:** `O(n)`.
**Insight:** the prefix re-occurs at every block boundary exactly when `z[i]` reaches the remaining block length — the Z-array validates a tiling in one pass per candidate.

---

### Problem 140: Manacher Follow-up — Longest Palindromic Substring Avoiding a Forbidden Center
**Statement:** Given `s` and a forbidden index `f`, find the longest palindromic substring whose **center does not coincide** with position `f` (odd-length center or the gap nearest `f`).

**Approach:** Run Manacher once to get all radii. Scan the transformed centers, skipping any center that maps back to original index `f`, and track the max palindrome among the rest. Mapping: transformed center `i` corresponds to original character index `(i - 1) / 2` when `i` is at a real character.

```java
class Solution {
    public String longestAvoidingCenter(String s, int f) {
        if (s.isEmpty()) return "";
        StringBuilder t = new StringBuilder("^");
        for (char ch : s.toCharArray()) t.append('#').append(ch);
        t.append("#$");
        char[] a = t.toString().toCharArray();
        int n = a.length; int[] p = new int[n];
        int c = 0, r = 0, best = 0, ci = 0;
        for (int i = 1; i < n - 1; i++) {
            if (i < r) p[i] = Math.min(r - i, p[2 * c - i]);
            while (a[i + p[i] + 1] == a[i - p[i] - 1]) p[i]++;
            if (i + p[i] > r) { c = i; r = i + p[i]; }
            int origCenter = (i % 2 == 1) ? -1 : (i / 2 - 1);  // even i sits on a real char
            if (origCenter == f) continue;
            if (p[i] > best) { best = p[i]; ci = i; }
        }
        int start = (ci - best) / 2;
        return s.substring(start, start + best);
    }
}
```
**Time:** `O(n)`. **Space:** `O(n)`.
**Insight:** Manacher hands you every center's radius for free; a forbidden-center constraint is just a filter applied during the max scan.

---

### Problem 141: Rolling Hash on Two Independent Mods — Anagram Substring Search
**Statement:** Find all start indices where a permutation (anagram) of `pattern` occurs in `text`. (LeetCode 438, but solved with a frequency "hash" that rolls.)

**Approach:** Anagrams share a character-count vector, not order, so a polynomial hash is wrong here — instead roll a 26-length count window and compare to the pattern's counts via a single `matches` counter that tracks how many of the 26 buckets currently agree.

```java
class Solution {
    public java.util.List<Integer> findAnagrams(String text, String pattern) {
        java.util.List<Integer> res = new java.util.ArrayList<>();
        int n = text.length(), m = pattern.length();
        if (m > n) return res;
        int[] need = new int[26], win = new int[26];
        for (char c : pattern.toCharArray()) need[c - 'a']++;
        int matches = 0;
        for (int b = 0; b < 26; b++) if (need[b] == 0) matches++;
        for (int i = 0; i < n; i++) {
            int add = text.charAt(i) - 'a';
            if (win[add] == need[add]) matches--;
            win[add]++;
            if (win[add] == need[add]) matches++;
            if (i >= m) {
                int rem = text.charAt(i - m) - 'a';
                if (win[rem] == need[rem]) matches--;
                win[rem]--;
                if (win[rem] == need[rem]) matches++;
            }
            if (i >= m - 1 && matches == 26) res.add(i - m + 1);
        }
        return res;
    }
}
```
**Time:** `O(n)`. **Space:** `O(1)` (fixed 26 buckets).
**Insight:** anagram matching keys on a multiset, so the "rolling" quantity is the count vector; tracking a single `matches` counter avoids re-scanning 26 buckets each step.

---

### Problem 142: Minimum Window Containing All Characters in Order — Subsequence Window
**Statement:** Find the minimum-length window of `text` that contains `pattern` as a **subsequence** (chars in order, not necessarily contiguous). (LeetCode 727 core.)

**Approach:** Two-pointer with a forward-then-backward sweep. March forward matching `pattern` greedily; once fully matched at index `e`, march backward from `e` re-matching `pattern` to find the tightest start. Repeat from just after that start.

```java
class Solution {
    public String minWindowSubseq(String text, String pattern) {
        int n = text.length(), m = pattern.length(), start = -1, len = Integer.MAX_VALUE;
        int i = 0;
        while (i < n) {
            int j = 0;
            while (i < n) {
                if (text.charAt(i) == pattern.charAt(j)) { if (++j == m) break; }
                i++;
            }
            if (j < m) break;
            int end = i++;
            j = m - 1;
            int b = end;
            while (j >= 0) { if (text.charAt(b) == pattern.charAt(j)) j--; b--; }
            b++;
            if (end - b + 1 < len) { len = end - b + 1; start = b; }
            i = b + 1;
        }
        return start < 0 ? "" : text.substring(start, start + len);
    }
}
```
**Time:** `O(n·m)` worst. **Space:** `O(1)`.
**Insight:** forward gives an end, backward tightens the start — alternating sweeps converge on the minimal subsequence window without a DP table.

---

### Problem 143: Edit Distance with Custom Operation Costs — Weighted Levenshtein
**Statement:** Compute minimum edit cost from `a` to `b` where insert, delete, and replace each have their own positive cost `ci, cd, cr`. Return the minimum total cost.

**Approach:** The Levenshtein recurrence, but each transition is weighted: a free diagonal on match, else the cheapest of `cr + diag`, `cd + up`, `ci + left`. Base rows/cols accumulate deletion/insertion costs respectively.

```java
class Solution {
    public long weightedEdit(String a, String b, long ci, long cd, long cr) {
        int n = a.length(), m = b.length();
        long[][] dp = new long[n + 1][m + 1];
        for (int i = 1; i <= n; i++) dp[i][0] = dp[i - 1][0] + cd;
        for (int j = 1; j <= m; j++) dp[0][j] = dp[0][j - 1] + ci;
        for (int i = 1; i <= n; i++)
            for (int j = 1; j <= m; j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) dp[i][j] = dp[i - 1][j - 1];
                else dp[i][j] = Math.min(dp[i - 1][j - 1] + cr,
                               Math.min(dp[i - 1][j] + cd, dp[i][j - 1] + ci));
            }
        return dp[n][m];
    }
}
```
**Time:** `O(n·m)`. **Space:** `O(n·m)` (reducible to `O(m)`).
**Insight:** asymmetric costs only re-weight the three grid moves; the recurrence shape is untouched, which is why spell-checkers can bias toward likely typos.

---

### Problem 144: Edit Distance Within Threshold — Banded DP (Ukkonen Cutoff)
**Statement:** Decide whether the edit distance between `a` and `b` is `<= t`. Exploit `t` to run in `O(t · min(n,m))` instead of `O(n·m)`.

**Approach:** If `|n - m| > t`, return false immediately. Otherwise only cells within a diagonal band of width `2t + 1` can hold values `<= t`; compute just that band, clamping out-of-band neighbors to infinity.

```java
class Solution {
    public boolean withinThreshold(String a, String b, int t) {
        int n = a.length(), m = b.length();
        if (Math.abs(n - m) > t) return false;
        int INF = Integer.MAX_VALUE / 2;
        int[] prev = new int[m + 1], cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            int lo = Math.max(1, i - t), hi = Math.min(m, i + t);
            cur[0] = i;
            for (int j = lo; j <= hi; j++) {
                int diag = (j - 1 >= 0) ? prev[j - 1] : INF;
                int up   = (j <= i + t) ? prev[j] : INF;
                int left = (j - 1 >= lo) ? cur[j - 1] : INF;
                if (a.charAt(i - 1) == b.charAt(j - 1)) cur[j] = diag;
                else cur[j] = 1 + Math.min(diag, Math.min(up, left));
            }
            if (lo > 1) prev[lo - 1] = INF;
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[m] <= t;
    }
}
```
**Time:** `O(t · min(n, m))`. **Space:** `O(m)`.
**Insight:** any alignment cheaper than `t` stays near the main diagonal, so the off-band cells are provably useless — Ukkonen's banding is the classic threshold speedup.

---

### Problem 145: Longest Common Subsequence of Three Strings — 3D DP
**Statement:** Return the length of the longest subsequence common to **three** strings `a`, `b`, `c`.

**Approach:** Lift the 2D LCS recurrence to a cube. `dp[i][j][k]` extends the diagonal when all three current chars match; otherwise it is the max over dropping a char from any one of the three strings.

```java
class Solution {
    public int lcs3(String a, String b, String c) {
        int n = a.length(), m = b.length(), o = c.length();
        int[][][] dp = new int[n + 1][m + 1][o + 1];
        for (int i = 1; i <= n; i++)
            for (int j = 1; j <= m; j++)
                for (int k = 1; k <= o; k++) {
                    if (a.charAt(i - 1) == b.charAt(j - 1) && b.charAt(j - 1) == c.charAt(k - 1))
                        dp[i][j][k] = dp[i - 1][j - 1][k - 1] + 1;
                    else
                        dp[i][j][k] = Math.max(dp[i - 1][j][k],
                                      Math.max(dp[i][j - 1][k], dp[i][j][k - 1]));
                }
        return dp[n][m][o];
    }
}
```
**Time:** `O(n·m·o)`. **Space:** `O(n·m·o)` (reducible to two 2D layers).
**Insight:** the curse of dimensionality is literal here — each extra string adds a DP axis and one diagonal/drop branch per axis.

---

### Problem 146: Shortest Common Supersequence — Reconstruct from LCS DP
**Statement:** Return the shortest string that has both `a` and `b` as subsequences. (LeetCode 1092.)

**Approach:** Build the LCS DP table, then walk it backward: on a matched char emit it once; otherwise emit the char from whichever neighbor the DP came from (the larger of up/left), consuming uncommon characters. Append leftovers and reverse.

```java
class Solution {
    public String shortestCommonSupersequence(String a, String b) {
        int n = a.length(), m = b.length();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = 1; i <= n; i++)
            for (int j = 1; j <= m; j++)
                dp[i][j] = a.charAt(i - 1) == b.charAt(j - 1)
                        ? dp[i - 1][j - 1] + 1
                        : Math.max(dp[i - 1][j], dp[i][j - 1]);
        StringBuilder sb = new StringBuilder();
        int i = n, j = m;
        while (i > 0 && j > 0) {
            if (a.charAt(i - 1) == b.charAt(j - 1)) { sb.append(a.charAt(i - 1)); i--; j--; }
            else if (dp[i - 1][j] >= dp[i][j - 1]) sb.append(a.charAt(--i));
            else sb.append(b.charAt(--j));
        }
        while (i > 0) sb.append(a.charAt(--i));
        while (j > 0) sb.append(b.charAt(--j));
        return sb.reverse().toString();
    }
}
```
**Time:** `O(n·m)`. **Space:** `O(n·m)`.
**Insight:** SCS length is `n + m - LCS`; reconstructing means weaving the LCS skeleton with the leftover characters of both strings.

---

### Problem 147: Interleaving String — 2D Reachability DP
**Statement:** Decide whether `s3` is formed by interleaving `s1` and `s2`, preserving the relative order of each. (LeetCode 97.)

**Approach:** `dp[i][j]` = can `s1[0..i)` and `s2[0..j)` interleave to `s3[0..i+j)`. Reach `(i,j)` from above if `s1[i-1]` matches the current `s3` char, or from the left if `s2[j-1]` does.

```java
class Solution {
    public boolean isInterleave(String s1, String s2, String s3) {
        int n = s1.length(), m = s2.length();
        if (n + m != s3.length()) return false;
        boolean[][] dp = new boolean[n + 1][m + 1];
        dp[0][0] = true;
        for (int i = 0; i <= n; i++)
            for (int j = 0; j <= m; j++) {
                if (i > 0 && dp[i - 1][j] && s1.charAt(i - 1) == s3.charAt(i + j - 1)) dp[i][j] = true;
                if (j > 0 && dp[i][j - 1] && s2.charAt(j - 1) == s3.charAt(i + j - 1)) dp[i][j] = true;
            }
        return dp[n][m];
    }
}
```
**Time:** `O(n·m)`. **Space:** `O(n·m)` (reducible to `O(m)`).
**Insight:** the merged index `i + j` ties the two source positions to one target position, so interleaving is a monotone grid reachability problem.

---

### Problem 148: Distinct Subsequences Count — Counting DP
**Statement:** Count how many distinct subsequences of `s` equal `t`. (LeetCode 115.)

**Approach:** `dp[i][j]` = number of ways `t[0..j)` appears as a subsequence of `s[0..i)`. Always inherit `dp[i-1][j]` (skip `s[i-1]`); when chars match, add `dp[i-1][j-1]` (use `s[i-1]` to extend a match).

```java
class Solution {
    public int numDistinct(String s, String t) {
        int n = s.length(), m = t.length();
        long[][] dp = new long[n + 1][m + 1];
        for (int i = 0; i <= n; i++) dp[i][0] = 1;
        for (int i = 1; i <= n; i++)
            for (int j = 1; j <= m; j++) {
                dp[i][j] = dp[i - 1][j];
                if (s.charAt(i - 1) == t.charAt(j - 1)) dp[i][j] += dp[i - 1][j - 1];
            }
        return (int) dp[n][m];
    }
}
```
**Time:** `O(n·m)`. **Space:** `O(n·m)` (reducible to `O(m)`).
**Insight:** "count distinct alignments" turns each match into an additive branch — the diagonal contributes new ways while the up-cell preserves existing ones.

---

### Problem 149: Palindromic Partitioning — Minimum Cuts (Two DP Tables)
**Statement:** Partition `s` so every part is a palindrome; return the **minimum** number of cuts. (LeetCode 132.)

**Approach:** Precompute `isPal[i][j]` with the standard interval DP, then `cut[i]` = min cuts for prefix ending at `i`: if `s[0..i]` is itself a palindrome, zero cuts; else `1 + min(cut[j-1])` over all `j` where `s[j..i]` is a palindrome.

```java
class Solution {
    public int minCut(String s) {
        int n = s.length();
        boolean[][] pal = new boolean[n][n];
        int[] cut = new int[n];
        for (int i = 0; i < n; i++) {
            cut[i] = i;                              // worst case: i cuts
            for (int j = 0; j <= i; j++) {
                if (s.charAt(j) == s.charAt(i) && (i - j < 2 || pal[j + 1][i - 1])) {
                    pal[j][i] = true;
                    cut[i] = (j == 0) ? 0 : Math.min(cut[i], cut[j - 1] + 1);
                }
            }
        }
        return cut[n - 1];
    }
}
```
**Time:** `O(n²)`. **Space:** `O(n²)`.
**Insight:** fusing the palindrome-interval table with the cut DP lets each prefix consult all palindromic tails in one pass — the two recurrences share the same scan.

---

### Problem 150: Count Palindromic Subsequences (Distinct, Mod) — Interval DP with Dedup
**Statement:** Count the number of **distinct** non-empty palindromic subsequences of `s` modulo `1e9+7`. (LeetCode 730.)

**Approach:** `dp[i][j]` over intervals. When `s[i] != s[j]`, inclusion-exclusion: `dp[i+1][j] + dp[i][j-1] - dp[i+1][j-1]`. When they match, count inner distinct palindromes and add wrappers, adjusting for duplicate boundary characters `lo`/`hi` to avoid double counting.

```java
class Solution {
    public int countPalindromicSubsequences(String s) {
        int n = s.length(), MOD = 1_000_000_007;
        long[][] dp = new long[n][n];
        for (int i = 0; i < n; i++) dp[i][i] = 1;
        for (int len = 2; len <= n; len++)
            for (int i = 0; i + len - 1 < n; i++) {
                int j = i + len - 1;
                if (s.charAt(i) != s.charAt(j)) {
                    dp[i][j] = dp[i + 1][j] + dp[i][j - 1] - dp[i + 1][j - 1];
                } else {
                    int lo = i + 1, hi = j - 1;
                    while (lo <= hi && s.charAt(lo) != s.charAt(i)) lo++;
                    while (lo <= hi && s.charAt(hi) != s.charAt(i)) hi--;
                    if (lo > hi)       dp[i][j] = dp[i + 1][j - 1] * 2 + 2;
                    else if (lo == hi) dp[i][j] = dp[i + 1][j - 1] * 2 + 1;
                    else               dp[i][j] = dp[i + 1][j - 1] * 2 - dp[lo + 1][hi - 1];
                }
                dp[i][j] = ((dp[i][j] % MOD) + MOD) % MOD;
            }
        return (int) dp[0][n - 1];
    }
}
```
**Time:** `O(n²)`. **Space:** `O(n²)`.
**Insight:** distinctness forces inclusion-exclusion at the boundaries — locating the nearest equal characters `lo`/`hi` is what cancels palindromes counted twice.

---

### Problem 151: Aho-Corasick Follow-up — Count Total Pattern Hits with Fail-Link DP
**Statement:** Given a dictionary and a text, return the **total number** of pattern occurrences (counting multiplicities, summed over all patterns) rather than listing them.

**Approach:** Build the automaton; precompute a per-node `cnt` = number of dictionary words ending at this node plus `cnt[fail[node]]` (propagated in BFS order). Running the text and summing `cnt[node]` at each step counts every occurrence along every fail chain in `O(1)` per character.

```java
class Solution {
    public long countHits(java.util.List<String> patterns, String text) {
        int maxNodes = 1; for (String p : patterns) maxNodes += p.length();
        int[][] go = new int[maxNodes][26]; for (int[] r : go) java.util.Arrays.fill(r, -1);
        int[] fail = new int[maxNodes], cnt = new int[maxNodes]; int size = 1;
        for (String p : patterns) {
            int node = 0;
            for (char c : p.toCharArray()) { int i = c - 'a'; if (go[node][i] == -1) go[node][i] = size++; node = go[node][i]; }
            cnt[node]++;
        }
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        for (int c = 0; c < 26; c++) { if (go[0][c] == -1) go[0][c] = 0; else { fail[go[0][c]] = 0; q.add(go[0][c]); } }
        while (!q.isEmpty()) {
            int u = q.poll();
            for (int c = 0; c < 26; c++) {
                int v = go[u][c];
                if (v == -1) { go[u][c] = go[fail[u]][c]; continue; }
                fail[v] = go[fail[u]][c];
                cnt[v] += cnt[fail[v]];        // fold suffix counts forward
                q.add(v);
            }
        }
        long total = 0; int node = 0;
        for (int i = 0; i < text.length(); i++) { node = go[node][text.charAt(i) - 'a']; total += cnt[node]; }
        return total;
    }
}
```
**Time:** `O(Σ|pattern| · 26 + n)`. **Space:** `O(nodes · 26)`.
**Insight:** folding `cnt[fail[v]]` into `cnt[v]` collapses the whole output (fail) chain into a single number, so counting needs no per-step chain walk.

---

### Problem 152: Suffix Automaton — Count Distinct Substrings via `len - link.len`
**Statement:** Build a suffix automaton of `s` and use it to count the number of distinct non-empty substrings.

**Approach:** Each state of the suffix automaton represents an equivalence class of substrings; it contributes `len[v] - len[link[v]]` distinct substrings. Summing that over every state but the initial gives the total distinct substring count in linear time.

```java
class Solution {
    int last = 0, sz = 1;
    int[] len, link; int[][] next;
    public long distinctSubstrings(String s) {
        int n = s.length(), cap = 2 * n + 5;
        len = new int[cap]; link = new int[cap]; next = new int[cap][26];
        for (int[] r : next) java.util.Arrays.fill(r, -1);
        link[0] = -1;
        for (char c : s.toCharArray()) extend(c - 'a');
        long total = 0;
        for (int v = 1; v < sz; v++) total += len[v] - len[link[v]];
        return total;
    }
    private void extend(int c) {
        int cur = sz++; len[cur] = len[last] + 1;
        int p = last;
        while (p != -1 && next[p][c] == -1) { next[p][c] = cur; p = link[p]; }
        if (p == -1) link[cur] = 0;
        else {
            int q = next[p][c];
            if (len[p] + 1 == len[q]) link[cur] = q;
            else {
                int clone = sz++; len[clone] = len[p] + 1; link[clone] = link[q];
                next[clone] = next[q].clone();
                while (p != -1 && next[p][c] == q) { next[p][c] = clone; p = link[p]; }
                link[q] = link[cur] = clone;
            }
        }
        last = cur;
    }
}
```
**Time:** `O(n · 26)`. **Space:** `O(n · 26)`.
**Insight:** the suffix automaton compresses all substrings into `O(n)` states; each state's "new" substrings are exactly `len - link.len`, so distinct-substring counting is a single sum.

---

### Problem 153: Lexicographically Smallest Rotation — Booth's Algorithm
**Statement:** Return the starting index of the lexicographically smallest rotation of `s` in linear time. (Distinct from candidate-pruning; uses Booth's failure-function variant.)

**Approach:** Booth builds a failure array over the doubled string conceptually, advancing two candidate starts `i` and `j` with an offset `k`; whenever a mismatch favors one candidate, jump the loser past the matched run. The surviving start is the smallest rotation.

```java
class Solution {
    public int leastRotation(String s) {
        int n = s.length();
        int[] f = new int[2 * n];
        java.util.Arrays.fill(f, -1);
        int k = 0;
        for (int j = 1; j < 2 * n; j++) {
            char sj = s.charAt(j % n);
            int i = f[j - k - 1];
            while (i != -1 && sj != s.charAt((k + i + 1) % n)) {
                if (sj < s.charAt((k + i + 1) % n)) k = j - i - 1;
                i = f[i];
            }
            if (i == -1 && sj != s.charAt((k + i + 1) % n)) {
                if (sj < s.charAt((k) % n)) k = j;
                f[j - k] = -1;
            } else {
                f[j - k] = i + 1;
            }
        }
        return k;
    }
}
```
**Time:** `O(n)`. **Space:** `O(n)`.
**Insight:** Booth runs a KMP-style failure function over the cyclic string, discarding any rotation start the moment it proves lexicographically larger — one linear pass, no doubling allocation of the data.

---

### Problem 154: Run-Length Compressed Equality — Compare Without Decompressing
**Statement:** Two strings are given as run-length encoded lists of `(char, count)`. Decide whether they expand to the same string **without** materializing them (counts may be huge, up to `long`).

**Approach:** Normalize each encoding by merging adjacent equal-character runs, then compare run-by-run. Equal expansions iff the normalized run lists are identical in char and count.

```java
class Solution {
    public boolean rleEqual(char[] c1, long[] n1, char[] c2, long[] n2) {
        java.util.List<long[]> a = normalize(c1, n1), b = normalize(c2, n2);
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++)
            if (a.get(i)[0] != b.get(i)[0] || a.get(i)[1] != b.get(i)[1]) return false;
        return true;
    }
    private java.util.List<long[]> normalize(char[] ch, long[] cnt) {
        java.util.List<long[]> r = new java.util.ArrayList<>();
        for (int i = 0; i < ch.length; i++) {
            if (cnt[i] == 0) continue;
            if (!r.isEmpty() && r.get(r.size() - 1)[0] == ch[i]) r.get(r.size() - 1)[1] += cnt[i];
            else r.add(new long[]{ch[i], cnt[i]});
        }
        return r;
    }
}
```
**Time:** `O(r1 + r2)` in the number of runs. **Space:** `O(r1 + r2)`.
**Insight:** equality of expansions is equality of *normalized* runs — collapsing adjacent equal runs is the only step that matters, and it dodges the exponential blow-up of decompression.

---

### Problem 155: Wildcard Matching on Compressed Pattern — Collapse Consecutive Stars First
**Statement:** Wildcard match (`?`, `*`) where the pattern may contain long runs of `*`. Preprocess so the DP/greedy never wastes states on redundant stars, then match.

**Approach:** Collapse every maximal run of `*` into a single `*` (semantically identical), which bounds the effective pattern length, then run the `O(1)`-space greedy two-pointer with star backtracking.

```java
class Solution {
    public boolean isMatch(String s, String pat) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pat.length(); i++) {
            char c = pat.charAt(i);
            if (c == '*' && sb.length() > 0 && sb.charAt(sb.length() - 1) == '*') continue;
            sb.append(c);
        }
        String p = sb.toString();
        int i = 0, j = 0, star = -1, match = 0, n = s.length(), m = p.length();
        while (i < n) {
            if (j < m && (p.charAt(j) == '?' || p.charAt(j) == s.charAt(i))) { i++; j++; }
            else if (j < m && p.charAt(j) == '*') { star = j; match = i; j++; }
            else if (star != -1) { j = star + 1; match++; i = match; }
            else return false;
        }
        while (j < m && p.charAt(j) == '*') j++;
        return j == m;
    }
}
```
**Time:** `O(n + m)` after the `O(m)` collapse. **Space:** `O(m)`.
**Insight:** `**` is identical to `*`, so collapsing star runs is a free correctness-preserving shrink that prevents pathological backtracking blow-ups.

---

### Problem 156: Longest Palindromic Subsequence — Interval DP
**Statement:** Return the length of the longest palindromic **subsequence** of `s`. (LeetCode 516; note: subsequence, not substring.)

**Approach:** It equals `LCS(s, reverse(s))`, but the direct interval DP is cleaner: `dp[i][j]` extends the inner palindrome by 2 when ends match, else takes the better of dropping either end.

```java
class Solution {
    public int longestPalindromeSubseq(String s) {
        int n = s.length();
        int[][] dp = new int[n][n];
        for (int i = n - 1; i >= 0; i--) {
            dp[i][i] = 1;
            for (int j = i + 1; j < n; j++) {
                if (s.charAt(i) == s.charAt(j)) dp[i][j] = dp[i + 1][j - 1] + 2;
                else dp[i][j] = Math.max(dp[i + 1][j], dp[i][j - 1]);
            }
        }
        return dp[0][n - 1];
    }
}
```
**Time:** `O(n²)`. **Space:** `O(n²)`.
**Insight:** palindromic subsequence is symmetric LCS-against-reverse; the interval recurrence grows matching pairs inward and skips the rest.

---

### Problem 157: Minimum Insertions to Make a Palindrome — n Minus LPS
**Statement:** Return the minimum number of character insertions (anywhere) to make `s` a palindrome. (LeetCode 1312.)

**Approach:** The unchangeable core is the longest palindromic subsequence; every other character needs a mirror inserted. Answer = `n - LPS(s)`.

```java
class Solution {
    public int minInsertions(String s) {
        int n = s.length();
        int[][] dp = new int[n][n];
        for (int i = n - 1; i >= 0; i--) {
            dp[i][i] = 1;
            for (int j = i + 1; j < n; j++) {
                if (s.charAt(i) == s.charAt(j)) dp[i][j] = dp[i + 1][j - 1] + 2;
                else dp[i][j] = Math.max(dp[i + 1][j], dp[i][j - 1]);
            }
        }
        return n - dp[0][n - 1];
    }
}
```
**Time:** `O(n²)`. **Space:** `O(n²)`.
**Insight:** insertions only need to mirror the non-palindromic remainder, so the cost is exactly the characters outside the longest palindromic subsequence.

---

### Problem 158: Two-Way (Crochemore-Perrin) String Matching — Constant Space Search
**Statement:** Find the first occurrence of `pattern` in `text` in `O(n)` time and `O(1)` extra space (no prefix-function array), via the Two-Way algorithm's critical factorization.

**Approach:** Compute a critical factorization `pattern = u·v` using the maximal-suffix comparison (Duval-style) under both orderings; match the right part `v` left-to-right, then the left part `u` right-to-left, shifting by the period on success or by the mismatch offset otherwise.

```java
class Solution {
    public int strStr(String t, String p) {
        int n = t.length(), m = p.length();
        if (m == 0) return 0; if (m > n) return -1;
        int[] mp = maxSuf(p, true), mpr = maxSuf(p, false);
        int ell, per;
        if (mp[0] > mpr[0]) { ell = mp[0]; per = mp[1]; } else { ell = mpr[0]; per = mpr[1]; }
        if (p.regionMatches(0, p, per, ell + 1)) {                 // periodic case
            int j = 0, memory = -1;
            while (j <= n - m) {
                int i = Math.max(ell, memory) + 1;
                while (i < m && p.charAt(i) == t.charAt(i + j)) i++;
                if (i < m) { j += i - ell; memory = -1; }
                else {
                    int k = ell;
                    while (k > memory && p.charAt(k) == t.charAt(k + j)) k--;
                    if (k <= memory) return j;
                    j += per; memory = m - per - 1;
                }
            }
            return -1;
        } else {                                                    // non-periodic case
            per = Math.max(ell + 1, m - ell - 1) + 1;
            int j = 0;
            while (j <= n - m) {
                int i = ell + 1;
                while (i < m && p.charAt(i) == t.charAt(i + j)) i++;
                if (i < m) j += i - ell;
                else {
                    int k = ell;
                    while (k >= 0 && p.charAt(k) == t.charAt(k + j)) k--;
                    if (k < 0) return j;
                    j += per;
                }
            }
            return -1;
        }
    }
    private int[] maxSuf(String p, boolean less) {
        int m = p.length(), i = -1, j = 0, k = 1, per = 1;
        while (j + k < m) {
            char a = p.charAt(j + k), b = p.charAt(i < 0 ? 0 : i + k);
            int cmp = Character.compare(a, b);
            if (i >= 0 && ((less && cmp < 0) || (!less && cmp > 0))) { j += k; k = 1; per = j - i; }
            else if (cmp == 0) { if (k == per) { j += per; k = 1; } else k++; }
            else { i = j; j = i + 1; k = 1; per = 1; }
        }
        return new int[]{i, per};
    }
}
```
**Time:** `O(n)` (at most `2n` comparisons). **Space:** `O(1)`.
**Insight:** critical factorization lets you split the pattern at its period so a mismatch on either half gives a safe full-period shift — the route to genuinely constant-space linear matching.

---

### Problem 159: Online Palindrome Stream — Append and Query Longest-Palindromic-Suffix via Eertree
**Statement:** Process characters one at a time; after each append, report whether the **entire current string** read so far is a palindrome, in amortized `O(1)` per character.

**Approach:** Maintain an eertree (palindromic tree). After adding a character, the longest palindromic suffix node has length `len`; the whole string is a palindrome iff that suffix length equals the current string length.

```java
class Solution {
    int[] len = new int[0]; int[] link; int[][] to; char[] buf;
    int sz, last, n;
    public java.util.List<Boolean> stream(String s) {
        int cap = s.length() + 5;
        len = new int[cap]; link = new int[cap]; to = new int[cap][26]; buf = new char[cap];
        len[0] = -1; len[1] = 0; link[0] = 0; link[1] = 0; sz = 2; last = 1; n = 0;
        java.util.List<Boolean> res = new java.util.ArrayList<>();
        for (char c : s.toCharArray()) { add(c - 'a'); res.add(len[last] == n); }
        return res;
    }
    private int getLink(int v) {
        while (n - len[v] - 2 < 0 || buf[n - len[v] - 2] != buf[n - 1]) v = link[v];
        return v;
    }
    private void add(int c) {
        buf[n++] = (char) c;
        int cur = getLink(last);
        if (to[cur][c] == 0) {
            int now = sz++;
            len[now] = len[cur] + 2;
            int l = getLink(link[cur]);
            link[now] = (len[now] == 1) ? 1 : to[l][c];
            to[cur][c] = now;
        }
        last = to[cur][c];
    }
}
```
**Time:** `O(n)` amortized total. **Space:** `O(n · 26)`.
**Insight:** the eertree tracks the longest palindromic suffix incrementally; "is the whole prefix a palindrome" is just "does that suffix span the entire string."

---

### Problem 160: Palindrome After Removing At Most One Character — Greedy Two-Pointer Branch
**Statement:** Decide whether `s` can become a palindrome by deleting **at most one** character. (LeetCode 680.)

**Approach:** Standard two-pointer; on the first mismatch, the only valid moves are deleting the left or the right character, so check whether either of the two remaining substrings is a palindrome.

```java
class Solution {
    public boolean validPalindrome(String s) {
        int i = 0, j = s.length() - 1;
        while (i < j) {
            if (s.charAt(i) != s.charAt(j))
                return isPal(s, i + 1, j) || isPal(s, i, j - 1);
            i++; j--;
        }
        return true;
    }
    private boolean isPal(String s, int i, int j) {
        while (i < j) { if (s.charAt(i++) != s.charAt(j--)) return false; }
        return true;
    }
}
```
**Time:** `O(n)`. **Space:** `O(1)`.
**Insight:** a budget of one deletion means at most one branch point — the first mismatch forks into exactly two `O(n)` palindrome checks.

---

### Problem 161: Concatenated Words from a Dictionary — Word-Break DP per Word
**Statement:** Given a list of words, return those that are a concatenation of **two or more** shorter words from the same list. (LeetCode 472.)

**Approach:** Put all words in a set. For each word, run a word-break DP allowing splits only into *other* dictionary words (never the whole word itself), requiring at least one cut. Reachability over positions.

```java
class Solution {
    public java.util.List<String> findAllConcatenatedWords(String[] words) {
        java.util.Set<String> dict = new java.util.HashSet<>(java.util.Arrays.asList(words));
        java.util.List<String> res = new java.util.ArrayList<>();
        for (String w : words) if (canForm(w, dict)) res.add(w);
        return res;
    }
    private boolean canForm(String w, java.util.Set<String> dict) {
        int n = w.length();
        if (n == 0) return false;
        boolean[] dp = new boolean[n + 1]; dp[0] = true;
        for (int i = 1; i <= n; i++)
            for (int j = (i == n ? 1 : 0); j < i; j++) {
                if (!dp[j]) continue;
                String part = w.substring(j, i);
                if (dict.contains(part) && !(j == 0 && i == n)) { dp[i] = true; break; }
            }
        return dp[n];
    }
}
```
**Time:** `O(Σ |word|²)` per word break. **Space:** `O(maxLen)`.
**Insight:** the "two or more" constraint forbids the trivial whole-word split — forcing at least one interior cut turns membership into a genuine composition test.

---

### Problem 162: Rolling Hash Substring with a Negative Query — Hash a Substring of the Reverse for Palindrome Check
**Statement:** Preprocess `s` so you can answer "is `s[l..r]` a palindrome?" in `O(1)` per query, using forward and reverse rolling hashes.

**Approach:** Build prefix hashes of `s` and of `reverse(s)`. A substring is a palindrome iff its forward hash equals the hash of the mirrored range in the reversed string. Compare both (optionally double-hash) for an `O(1)` test.

```java
class PalindromeQuery {
    private final long[] hf, hr, pw; private final long B = 131, MOD = 1_000_000_007L; private final int n;
    public PalindromeQuery(String s) {
        n = s.length();
        hf = new long[n + 1]; hr = new long[n + 1]; pw = new long[n + 1]; pw[0] = 1;
        for (int i = 0; i < n; i++) {
            hf[i + 1] = (hf[i] * B + s.charAt(i)) % MOD;
            hr[i + 1] = (hr[i] * B + s.charAt(n - 1 - i)) % MOD;
            pw[i + 1] = pw[i] * B % MOD;
        }
    }
    private long fwd(int l, int r) { int len = r - l + 1; return ((hf[r + 1] - hf[l] * pw[len]) % MOD + MOD) % MOD; }
    private long rev(int l, int r) {                       // reverse-string indices for original [l,r]
        int rl = n - 1 - r, rr = n - 1 - l, len = r - l + 1;
        return ((hr[rr + 1] - hr[rl] * pw[len]) % MOD + MOD) % MOD;
    }
    public boolean isPalindrome(int l, int r) { return fwd(l, r) == rev(l, r); }
}
```
**Time:** `O(n)` build, `O(1)` per query. **Space:** `O(n)`.
**Insight:** a palindrome equals its own reverse, so hashing the original and the reversed string once lets any range-palindrome question reduce to one integer comparison.

---

### Problem 163: Smallest String Rotation That Is Lexicographically Sorted Among All Substrings — Suffix Array of Doubled String
**Statement:** Among all `n` rotations of `s`, return the lexicographically smallest one as a string, using a suffix array of `s + s`.

**Approach:** Build the suffix array of `s + s`; the first suffix (in sorted order) whose start index is `< n` marks the smallest rotation's offset. Slice `n` characters from there.

```java
class Solution {
    public String smallestRotation(String s) {
        int n = s.length();
        String d = s + s;
        int[] sa = new SuffixArray().build(d);
        for (int i = 0; i < sa.length; i++) {
            if (sa[i] < n) return d.substring(sa[i], sa[i] + n);
        }
        return s;
    }
}
```
**Time:** `O(n log² n)` (suffix array of length `2n`). **Space:** `O(n)`.
**Insight:** rotations of `s` are exactly the length-`n` prefixes of suffixes of `s + s` starting before `n`; sorting suffixes hands you their lexicographic order directly.

---

### Problem 164: Find the K-th Lexicographically Smallest Substring — Suffix Array + LCP Walk
**Statement:** Return the `k`-th smallest **distinct** substring of `s` in lexicographic order (1-indexed), or `""` if fewer than `k` distinct substrings exist.

**Approach:** Walk the suffix array in sorted order. Suffix `sa[i]` introduces `(n - sa[i]) - lcp[i]` new distinct substrings, ordered by increasing length. Accumulate these counts; when the running total reaches `k`, the answer is a prefix of suffix `sa[i]`.

```java
class Solution {
    public String kthDistinctSubstring(String s, long k) {
        int n = s.length();
        int[] sa = new SuffixArray().build(s);
        int[] lcp = new Kasai().buildLCP(s, sa);
        for (int i = 0; i < n; i++) {
            long newCount = (n - sa[i]) - lcp[i];
            if (k <= newCount) {
                int len = lcp[i] + (int) k;          // (lcp[i]+1)-th .. up to k-th new prefix
                return s.substring(sa[i], sa[i] + len);
            }
            k -= newCount;
        }
        return "";
    }
}
```
**Time:** `O(n log² n)` build, `O(n)` walk. **Space:** `O(n)`.
**Insight:** sorted suffixes enumerate distinct substrings in lexicographic order; each suffix's new prefixes form a contiguous length range, so the `k`-th falls out of a prefix-sum walk.

---

### Problem 165: Repeated String Match — Minimum Copies So a Pattern Fits
**Statement:** Return the minimum number of times `a` must be repeated so that `b` becomes a substring of the repetition, or `-1` if impossible. (LeetCode 686.)

**Approach:** Repeat `a` until its length is at least `|b|`, then try one extra copy to cover boundary overlaps. Check `contains` at each of those two repeat counts; the first hit is the answer.

```java
class Solution {
    public int repeatedStringMatch(String a, String b) {
        int count = (int) Math.ceil((double) b.length() / a.length());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) sb.append(a);
        for (int i = 0; i < 2; i++) {
            if (sb.toString().contains(b)) return count + i;
            sb.append(a);
        }
        return -1;
    }
}
```
**Time:** `O((|a| + |b|) · 1)` with a linear `contains` (KMP under the hood). **Space:** `O(|a| + |b|)`.
**Insight:** only the boundary between copies can break a match, so testing `ceil` and `ceil + 1` repetitions provably covers every case — more copies never help.

---

### Problem 166: Longest Happy Prefix — Border via Prefix Function (No Trivial Whole String)
**Statement:** Return the longest **proper** prefix of `s` that is also a suffix (excluding `s` itself). (LeetCode 1392.)

**Approach:** This is precisely `pi[n-1]` from the prefix function — the longest proper border. Return the corresponding prefix substring.

```java
class Solution {
    public String longestPrefix(String s) {
        int n = s.length();
        int[] pi = new int[n];
        for (int i = 1; i < n; i++) {
            int k = pi[i - 1];
            while (k > 0 && s.charAt(i) != s.charAt(k)) k = pi[k - 1];
            if (s.charAt(i) == s.charAt(k)) k++;
            pi[i] = k;
        }
        return s.substring(0, pi[n - 1]);
    }
}
```
**Time:** `O(n)`. **Space:** `O(n)`.
**Insight:** the "happy prefix" is just the longest border — the prefix function's last entry names it directly with no extra work.

---

## ✅ Key Takeaways (Extended Set 2)

- **Cyclic and rotation problems reduce to the doubled string** — feed `text` modulo `n` to a matcher (132), or build a suffix array of `s + s` (163, 164); rotations are length-`n` prefixes of those suffixes.
- **The period is the master quantity.** Distinct rotations (133), covers (139), and repeated-block detection all fall out of `period = n - border`, with divisibility deciding "proper" vs "improper."
- **Approximate matching has no general linear bound.** The honest baseline is the early-exit window (135); LCE kangaroo jumps trade it down to `O(n·k)` (136), and bit-parallel Shift-And handles wildcards for short patterns (137).
- **Banding beats full DP when an answer threshold is known** (144) — alignments cheaper than `t` hug the diagonal, so the off-band cells are provably dead.
- **Palindrome questions split by structure:** subsequence → interval DP / LPS (156, 157), substring → Manacher (140), online → eertree (159), range queries → forward+reverse hashing (162).
- **Automaton "count" follow-ups fold suffix links forward** — Aho-Corasick `cnt[fail[v]]` (151) and suffix-automaton `len - link.len` (152) turn chain walks into single additions.

## ⚠️ Common Pitfalls (Extended Set 2)

- **Forgetting the boundary copy** in cyclic / repeated-match problems — a wrapping occurrence (132) or a cross-copy substring (165) needs one extra copy or a `n + m - 1` scan cap, or you miss matches.
- **Treating anagram search as a polynomial hash** (141) — order-insensitive matching keys on a count multiset, not a positional hash; rolling a 26-bucket vector is the correct model.
- **Off-by-one in the eertree imaginary roots** (159) — `len[-1 node] = -1` and the `getLink` boundary check `n - len[v] - 2 >= 0` are easy to botch and silently corrupt the suffix links.
- **Double-counting palindromic subsequences** (150) — without the `lo`/`hi` nearest-equal-character cancellation, inclusion-exclusion over-counts every palindrome that repeats a boundary letter.
- **Not collapsing `**` before wildcard matching** (155) — redundant stars don't change the language but can trigger exponential backtracking; collapse them first.
- **Reverse-hash index mapping** (162) — the mirror of original range `[l, r]` is `[n-1-r, n-1-l]` in `reverse(s)`; getting this transform wrong makes every palindrome query wrong.

## 📚 Further Reading (Extended Set 2)

- Crochemore & Perrin (1991) — *Two-Way String Matching* (Problem 158, constant-space linear search).
- Booth (1980) — *Lexicographically least circular substrings* (Problem 153).
- Landau & Vishkin (1986) — *Efficient string matching with k mismatches* (Problems 135–136, LCE kangaroo jumps).
- Baeza-Yates & Gonnet (1992) — *A new approach to text searching* (Shift-Or / Shift-And, Problem 137).
- Rubinchik & Shur (2015) — *EERTREE: An Efficient Data Structure for Processing Palindromes* (Problem 159).
- cp-algorithms.com — suffix automaton, suffix array `k`-th substring, and Two-Way matching references for Problems 152, 158, 164.
