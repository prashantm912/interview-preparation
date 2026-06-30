# Regular Expressions

[← Back to master index](../README.md)

Regular expressions (regex) are a compact pattern language for matching, extracting, and transforming text. This guide covers regex from the ground up — syntax (anchors, character classes, quantifiers, groups, backreferences, lookaround), the engines that execute them (NFA backtracking vs DFA), the security trap of catastrophic backtracking / ReDoS, and the practical realities of Java's `Pattern`/`Matcher` API — with correct, runnable Java examples. All content is current to 2026, reflecting modern JDK behavior and Unicode 15.x semantics.

## 🟢 Basic (0–2 yrs)

### Q1. [Theory] What is a regular expression, and what problem does it solve?

A regular expression is a string that describes a **pattern** over a set of input strings. Instead of writing imperative code to scan characters one by one, you declare *what* a valid/interesting piece of text looks like and let a regex engine do the scanning. Typical uses:

- **Validation** — does this input look like an email, a phone number, a date?
- **Search** — find all occurrences of a pattern in a body of text.
- **Extraction** — pull out the captured pieces (e.g., year/month/day from a date).
- **Replacement** — find a pattern and substitute new text.

Formally, "regular" expressions describe the class of *regular languages* (recognizable by a finite automaton). In practice, modern "regex" flavors (Java, PCRE, .NET) add features like backreferences and lookaround that go *beyond* truly regular languages — so the name is partly historical.

### Q2. [Theory] What are anchors, and what is the difference between `^`, `$`, and `\b`?

Anchors match a **position** between characters, not a character itself ("zero-width" assertions):

- `^` — start of input (or start of a line in multiline mode).
- `$` — end of input (or end of a line in multiline mode; in Java it also matches before a final line terminator).
- `\b` — a **word boundary**: the position between a word character (`[A-Za-z0-9_]`) and a non-word character (or string edge).
- `\B` — a non-word-boundary (the negation of `\b`).
- `\A` / `\z` / `\Z` (Java) — absolute start of input / absolute end / end before a final terminator. Unlike `^`/`$`, these are **not** affected by multiline mode.

```
Text:   "the cat sat"
\bcat\b  ─┐   matches "cat" because it's bounded by spaces
          └─ positions:  the·│cat│·sat
```

A word boundary lets you match `cat` in "the cat" but not inside "category" (`\bcat\b` fails there because `cat` is followed by `e`, a word char).

### Q3. [Theory] What are character classes? Explain `[abc]`, `[^abc]`, and ranges.

A character class `[...]` matches **exactly one** character from the set inside the brackets.

- `[abc]` — matches a single `a`, `b`, or `c`.
- `[a-z]` — a **range**: any lowercase ASCII letter.
- `[a-zA-Z0-9]` — multiple ranges combined.
- `[^abc]` — a **negated** class: any character that is *not* `a`, `b`, or `c` (the `^` must be first to negate).

Inside a class, most metacharacters lose their special meaning: `[.+*]` matches a literal dot, plus, or asterisk. The characters you still need to be careful with inside a class are `]`, `\`, `^` (when first), and `-` (when between two chars). Put `-` first or last, or escape it, to match a literal hyphen: `[a-z-]` or `[-a-z]`.

### Q4. [Theory] What do the shorthand classes `\d`, `\w`, `\s` (and their uppercase forms) mean?

These are predefined shorthand character classes:

| Shorthand | Meaning | Negation |
|-----------|---------|----------|
| `\d` | a digit | `\D` (non-digit) |
| `\w` | a "word" char: `[A-Za-z0-9_]` | `\W` (non-word) |
| `\s` | whitespace (space, tab, newline, etc.) | `\S` (non-whitespace) |

By default in Java these are **ASCII-only**: `\d` is `[0-9]`, not Arabic-Indic or Devanagari digits. To make them Unicode-aware you pass the `UNICODE_CHARACTER_CLASS` flag (or `(?U)`), after which `\d` matches any Unicode decimal digit and `\w` follows Unicode word semantics. This trips up many engineers who assume `\d` is Unicode by default.

### Q5. [Theory] Explain the quantifiers `*`, `+`, `?`, and `{n,m}`.

Quantifiers specify **how many times** the preceding element may repeat:

- `*` — zero or more (`{0,}`).
- `+` — one or more (`{1,}`).
- `?` — zero or one / optional (`{0,1}`).
- `{n}` — exactly `n` times.
- `{n,}` — at least `n` times.
- `{n,m}` — between `n` and `m` times (inclusive).

```
a*      ""        "a"   "aaaa"     (matches all)
a+      "a"       "aaa"            (needs at least one)
a?      ""        "a"             (at most one)
a{2,3}  "aa"      "aaa"           (two or three)
```

A quantifier with no preceding atom (`*abc`) is a syntax error. Quantifiers bind to the single element immediately to their left — `ab+` means `a` followed by one-or-more `b`, **not** one-or-more `ab`. To repeat a sequence, group it: `(ab)+`.

### Q6. [Theory] What is the difference between greedy, lazy (reluctant), and possessive quantifiers?

All three describe *how much* a quantifier tries to consume:

- **Greedy** (default: `*`, `+`, `?`, `{n,m}`) — match as much as possible, then **backtrack** (give characters back) if the rest of the pattern fails.
- **Lazy / reluctant** (`*?`, `+?`, `??`, `{n,m}?`) — match as little as possible, then expand only if needed.
- **Possessive** (`*+`, `++`, `?+`, `{n,m}+`) — match as much as possible and **never backtrack**. Faster, and a key defense against catastrophic backtracking, but can cause a match to fail where greedy would have succeeded.

```
Input:  <a><b>
<.+>   greedy   → matches the WHOLE "<a><b>"  (grabs all, backtracks to last >)
<.+?>  lazy     → matches "<a>"               (stops at first >)
<.++>  possessive → FAILS to match here; .++ eats "a><b>" and won't give back the closing >, so the trailing > has nothing to match. A working possessive analogue is <[^>]++> → matches "<a>".
```

Lazy is the usual fix for "match the smallest HTML tag." Possessive is the usual fix for performance/ReDoS when you know backtracking is unnecessary.

### Q7. [Coding] Write a Java regex that matches a string of only digits, and show how to test it.

```java
import java.util.regex.Pattern;

public class DigitsOnly {
    // ^\d+$  anchored so the ENTIRE string must be digits
    private static final Pattern DIGITS = Pattern.compile("^\\d+$");

    public static boolean isAllDigits(String s) {
        return DIGITS.matcher(s).matches();
    }

    public static void main(String[] args) {
        System.out.println(isAllDigits("12345")); // true
        System.out.println(isAllDigits("12a45")); // false
        System.out.println(isAllDigits(""));       // false (+ requires at least one)
    }
}
```

Note the **double backslash**: in Java source, `"\\d"` is the two-character regex `\d` because `\` is also Java's string escape character. Forgetting this (`"\d"`) is a compile error.

### Q8. [Theory] In Java, what is the difference between `matches()`, `find()`, and `lookingAt()`?

These three `Matcher` methods differ in *how much* of the input they require the pattern to cover:

- **`matches()`** — the pattern must match the **entire** input string (implicitly anchored start-to-end).
- **`find()`** — searches for the **next** substring anywhere in the input that matches; can be called repeatedly to iterate over all matches.
- **`lookingAt()`** — the pattern must match at the **beginning** of the input, but need not consume all of it.

```
Pattern "\\d+"  on input  "abc123def456"
  matches()    → false   (whole string isn't digits)
  lookingAt()  → false   (doesn't start with digits)
  find()       → true    ("123"), then find() again → "456"
```

A frequent bug: using `matches()` and wondering why a clearly-present substring "doesn't match" — `matches()` is whole-string, so you usually want `find()` for searching.

### Q9. [Coding] How do you find and print all matches of a pattern in Java?

Loop with `find()`, and read each match with `group()`:

```java
import java.util.regex.*;

public class FindAll {
    public static void main(String[] args) {
        String text = "Order #123, ref #4567, code #89";
        Matcher m = Pattern.compile("#(\\d+)").matcher(text);
        while (m.find()) {
            System.out.println("full=" + m.group(0) +
                               "  number=" + m.group(1) +
                               "  at=" + m.start() + ".." + m.end());
        }
    }
}
// full=#123  number=123  at=6..10
// full=#4567 number=4567 at=18..23
// full=#89   number=89   at=30..33
```

`group(0)` is the whole match; `group(1)` is the first capturing group; `start()`/`end()` give offsets. On JDK 9+ you can also use `m.results()` to get a `Stream<MatchResult>`.

### Q10. [Theory] What is the difference between a capturing group `(...)` and a non-capturing group `(?:...)`?

Both group a sub-pattern so a quantifier or alternation applies to the whole group. The difference is whether the matched text is **stored** for later retrieval:

- `(...)` — **capturing**: the substring it matched is saved and numbered (group 1, 2, ...), retrievable via `group(n)` or a backreference `\1`.
- `(?:...)` — **non-capturing**: groups for structure only; nothing is stored and it does not consume a group number.

```java
Matcher m = Pattern.compile("(?:abc)+(\\d+)").matcher("abcabc42");
m.matches();
m.group(1); // "42"  — the (?:abc)+ doesn't take group number 1
```

Use non-capturing groups when you only need grouping (for performance and to keep group numbers meaningful). Capturing groups carry overhead and clutter the numbering if you don't need their contents.

### Q11. [Theory] How does alternation (`|`) work, and what is its precedence?

The `|` operator means **"or"** — try the left alternative, and if it fails at that position, try the right. Alternation has the **lowest precedence** of all regex operators, so it spans as far as it can unless bounded by a group.

```
abc|def      matches "abc" OR "def"
^abc|def$    matches "abc" at START  OR  "def" at END  (likely NOT intended!)
^(abc|def)$  matches a whole string that is exactly "abc" or "def"
```

The second example is a classic mistake: because `|` is lowest precedence, the anchors bind only to their adjacent alternative. Always wrap alternatives in a group when anchoring: `^(?:abc|def)$`.

### Q12. [Coding] Write a Java regex to validate a simple 24-hour time like `13:45` or `09:00`.

```java
import java.util.regex.Pattern;

public class TimeValidator {
    // Hours 00-23, minutes 00-59
    private static final Pattern TIME =
        Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    public static boolean isValid(String s) {
        return TIME.matcher(s).matches();
    }

    public static void main(String[] args) {
        System.out.println(isValid("13:45")); // true
        System.out.println(isValid("09:00")); // true
        System.out.println(isValid("24:00")); // false (hour out of range)
        System.out.println(isValid("9:00"));  // false (needs leading zero)
    }
}
```

The hour part `([01]\d|2[0-3])` allows `00`–`19` (via `[01]\d`) and `20`–`23` (via `2[0-3]`), correctly rejecting `24`+ . This shows how range constraints are expressed structurally rather than numerically — regex has no concept of numeric `<= 23`.

### Q13. [Theory] How do you match a literal special character like `.`, `*`, or `(`?

Escape it with a backslash. The dot `.` is the most common: unescaped it matches *any* character (except line terminators by default), so to match a literal period you write `\.`.

```
\.   literal dot
\*   literal asterisk
\(   literal open paren
\\   literal backslash
\$   literal dollar sign
```

In Java source these need a second backslash: `"\\."`. Alternatively, escape a whole string with `Pattern.quote(s)`, which wraps it in `\Q...\E` so every character is treated literally — invaluable when the pattern is user-supplied and might contain metacharacters.

### Q14. [Theory] What does the `.` (dot) match, and what does it NOT match by default?

The dot matches **any single character except a line terminator** (`\n`, and depending on flags `\r`, ` `, etc.) by default. It does **not** match:

- newline characters (unless `DOTALL`/`(?s)` is enabled),
- and it always matches exactly **one** character (never zero).

```
"a.c"  matches  "abc", "a c", "a@c"   but NOT "ac" (no char between) or "a\nc"
```

To make the dot also match newlines, enable **DOTALL** mode (`Pattern.DOTALL` or inline `(?s)`). This is a frequent source of bugs when parsing multi-line text where you expect `.*` to span lines but it stops at the first newline.

### Q15. [Coding] Write Java code to replace all whitespace runs in a string with a single space.

```java
import java.util.regex.Pattern;

public class CollapseSpaces {
    private static final Pattern WS = Pattern.compile("\\s+");

    public static String collapse(String s) {
        return WS.matcher(s).replaceAll(" ").trim();
    }

    public static void main(String[] args) {
        System.out.println("[" + collapse("  hello   \t world\n\n") + "]");
        // [hello world]
    }
}
```

`\s+` matches one or more whitespace characters (so a run of spaces/tabs/newlines becomes one space). `replaceAll` does a `find()`-style global replace. Compiling the `Pattern` once as a constant avoids recompiling on every call — `String.replaceAll(...)` recompiles the regex each time and is wasteful in hot paths.

### Q16. [Theory] What is the difference between `String.matches()` and `Pattern.compile().matcher().find()`?

- `String.matches(regex)` compiles the regex **on every call** and requires the **whole string** to match (anchored). It's convenient for one-off checks but inefficient in loops.
- `Pattern.compile(regex)` compiles **once**; the resulting `Pattern` is immutable and thread-safe and can be reused. `matcher(input).find()` searches for a substring anywhere.

```java
// Bad in a hot loop — recompiles regex every iteration:
if (s.matches("\\d+")) { ... }

// Good — compile once, reuse:
static final Pattern DIGITS = Pattern.compile("\\d+");
if (DIGITS.matcher(s).matches()) { ... }
```

Rule of thumb: hoist any regex used more than once into a `static final Pattern`.

### Q17. [Theory] How do you use the case-insensitive flag in Java, both as an API flag and inline?

Two equivalent ways:

```java
// 1. As a Pattern flag:
Pattern p = Pattern.compile("hello", Pattern.CASE_INSENSITIVE);

// 2. Inline modifier at the start of the pattern:
Pattern p2 = Pattern.compile("(?i)hello");

// 3. Scoped inline modifier — only part of the pattern:
Pattern p3 = Pattern.compile("foo(?i:bar)"); // 'foo' case-sensitive, 'bar' not
```

`CASE_INSENSITIVE` only folds **ASCII** case by default. For full Unicode case folding (e.g., matching `Σ`/`σ`, or accented letters) you must also add `Pattern.UNICODE_CASE` (or `(?u)`). Forgetting `UNICODE_CASE` is a subtle internationalization bug.

### Q18. [Coding] Write a Java regex to extract the file extension from a filename.

```java
import java.util.regex.*;

public class FileExtension {
    // Capture chars after the LAST dot, where there's a non-empty name before it
    private static final Pattern EXT = Pattern.compile("\\.([^.]+)$");

    public static String extension(String filename) {
        Matcher m = EXT.matcher(filename);
        return m.find() ? m.group(1) : "";
    }

    public static void main(String[] args) {
        System.out.println(extension("report.final.pdf")); // pdf
        System.out.println(extension("archive.tar.gz"));    // gz
        System.out.println(extension("README"));            // "" (no extension)
        System.out.println(extension(".gitignore"));        // gitignore (dotfile!)
    }
}
```

`\.([^.]+)$` anchors to the end and captures everything after the last dot. Note the dotfile edge case (`.gitignore` → `gitignore`) — a reminder that "extension" is ambiguous and regex captures whatever you specify, edge cases included.

### Q19. [Theory] What is the difference between `[0-9]` and `\d`, and when do they diverge?

In **ASCII** mode they are identical: both match a single character `0`–`9`. They **diverge under Unicode**:

- `[0-9]` always matches only the ASCII digits.
- `\d` matches ASCII digits by default, but with `Pattern.UNICODE_CHARACTER_CLASS` (or `(?U)`) it matches **any Unicode decimal digit**, including Arabic-Indic (٠-٩), Devanagari (०-९), fullwidth digits, etc.

So if you want "ASCII digits only" be explicit with `[0-9]`; if you want "any digit a human might type" use `\d` with the Unicode flag. Silently accepting non-ASCII digits via `\d` can break downstream `Integer.parseInt` calls that expect ASCII.

### Q20. [Theory] Why do you need to escape backslashes in Java regex string literals?

Because the backslash is an escape character in **two** layers:

1. The **Java compiler** processes string escapes first: `"\\d"` becomes the two-character string `\d`.
2. The **regex engine** then interprets `\d` as "a digit."

So the regex metacharacter `\d` must be written `"\\d"` in a Java string literal, and a literal backslash in the target text is `"\\\\"` (four backslashes → two characters `\\` → regex for one literal backslash).

```
Want to match:   \d   (literal backslash, then d)
Regex:           \\d
Java string:     "\\\\d"
```

Java's text blocks (`"""..."""`) do not change this — they still process backslash escapes. This double-escaping is one of the most error-prone parts of Java regex.

### Q21. [Practical] How would you split a CSV line on commas using regex, and what's the catch?

The naive split is trivial but wrong for real CSV:

```java
String[] parts = line.split(",");
```

The catch: real CSV allows **quoted fields containing commas** (`"Smith, John",42`). A pure regex split can't robustly parse quoted-and-escaped CSV because CSV is context-sensitive (quotes toggle a mode). A pattern that splits on commas *outside* quotes exists but is fragile:

```java
// Split on commas NOT inside double quotes (no escaped-quote support):
String[] parts = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
```

This lookahead counts quotes ahead to ensure an even number, but it breaks on escaped quotes (`""`), embedded newlines, etc. **The right answer in an interview:** use a real CSV library (OpenCSV, Apache Commons CSV, Jackson CSV) rather than a regex — this signals you know regex's limits.

## 🟡 Intermediate (3–7 yrs)

### Q22. [Theory] Explain backreferences. How do `\1`, `\2` work and what can they express?

A **backreference** matches the **same text** that a previous capturing group matched (not the same pattern — the same literal characters). `\1` refers to group 1, `\2` to group 2, and so on.

```
(\w+)\s+\1     matches a repeated word: "hello hello", "the the"
                but NOT "hello world"
```

```java
Pattern dup = Pattern.compile("\\b(\\w+)\\s+\\1\\b");
// finds doubled words like "the the"
```

Backreferences are what push regex **beyond regular languages** — `(.+)\1` matches the **copy language** `{ww | w ∈ Σ*}` (any string followed by an exact copy of itself), which is not regular (in fact not even context-free), and is why backreference matching can't be done by a pure DFA. (Note this needs an alphabet of ≥ 2 symbols: the unary `(a+)\1` collapses to `(aa)+`, which *is* regular.) They're powerful for finding duplicates or matched delimiters but contribute to backtracking cost.

### Q23. [Theory] What are named capturing groups, and how do you use them in Java?

Named groups give a capturing group a readable name instead of (or in addition to) a number, improving maintainability.

```java
Pattern p = Pattern.compile("(?<year>\\d{4})-(?<month>\\d{2})-(?<day>\\d{2})");
Matcher m = p.matcher("2026-07-01");
if (m.matches()) {
    System.out.println(m.group("year"));  // 2026
    System.out.println(m.group("month")); // 07
    System.out.println(m.group("day"));   // 01
}
```

- Syntax: `(?<name>...)` to define, `\k<name>` for a named backreference, `${name}` in replacement strings.
- Names must be alphanumeric (start with a letter) and unique.
- Named groups still occupy numbered positions, so `group(1)` also works. They make complex patterns far more readable in code review.

### Q24. [Theory] Explain lookahead. What is the difference between positive `(?=...)` and negative `(?!...)`?

Lookahead is a **zero-width assertion**: it checks whether the upcoming text does (or doesn't) match a sub-pattern, **without consuming** any characters.

- **Positive lookahead** `(?=X)` — succeeds if `X` matches starting here.
- **Negative lookahead** `(?!X)` — succeeds if `X` does **not** match starting here.

```
\d+(?= dollars)    matches the digits in "100 dollars" (but not " dollars")
\d+(?! dollars)    matches digits NOT followed by " dollars"
foo(?!bar)         "foo" not immediately followed by "bar"
```

A classic use is password policy: `(?=.*[A-Z])(?=.*\d)` asserts "contains an uppercase letter AND a digit" without dictating order, because each lookahead scans from the same position without consuming.

### Q25. [Theory] Explain lookbehind. What are its constraints in Java?

Lookbehind asserts what **precedes** the current position, again zero-width:

- **Positive lookbehind** `(?<=X)` — preceded by `X`.
- **Negative lookbehind** `(?<!X)` — not preceded by `X`.

```
(?<=\$)\d+      digits preceded by a literal $  → "100" in "$100"
(?<!\d)\d{3}    three digits NOT preceded by another digit
```

Java's lookbehind historically required **bounded length** (no unbounded `*`/`+`), though it permits bounded alternation and `{n,m}` ranges. Unlike some other engines (e.g., .NET supports arbitrary-length lookbehind), Java's lookbehind cannot look behind an indeterminate amount. If you need variable-length lookbehind, restructure with capturing groups or `\K`-style tricks (which Java lacks — PCRE's `\K` is not supported).

### Q26. [Coding] Write a Java password-strength regex requiring 8+ chars, one uppercase, one digit, and one special char.

```java
import java.util.regex.Pattern;

public class PasswordPolicy {
    // Lookaheads assert each requirement independently; .{8,} enforces length
    private static final Pattern STRONG = Pattern.compile(
        "^(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%^&*])(?=\\S+$).{8,}$");

    public static boolean isStrong(String pw) {
        return STRONG.matcher(pw).matches();
    }

    public static void main(String[] args) {
        System.out.println(isStrong("Passw0rd!")); // true
        System.out.println(isStrong("password"));  // false (no upper/digit/special)
        System.out.println(isStrong("Pa0!"));      // false (too short)
    }
}
```

Each `(?=...)` is a lookahead that scans from the start without consuming, so order of requirements doesn't matter. `(?=\S+$)` forbids whitespace. `.{8,}` then does the actual consuming. Note: for real systems, length + a few rules and a breached-password check beats overly clever regex.

### Q27. [Practical] How do you do capture-group-referencing replacement in Java?

In the replacement string, `$1`, `$2`, ... insert the text matched by the corresponding capturing group; `${name}` inserts a named group.

```java
import java.util.regex.Pattern;

public class DateReformat {
    public static void main(String[] args) {
        String in = "2026-07-01";
        // yyyy-MM-dd  ->  MM/dd/yyyy
        String out = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})")
                            .matcher(in)
                            .replaceAll("$2/$3/$1");
        System.out.println(out); // 07/01/2026
    }
}
```

Gotchas: a literal `$` or `\` in the replacement must be escaped as `\\$` and `\\\\`, or you can use `Matcher.quoteReplacement(s)` to make a replacement string literal. On JDK 9+, `replaceAll(Function<MatchResult,String>)` lets you compute replacements in code instead of with `$` references.

### Q28. [Theory] What does the MULTILINE flag change, and how does it interact with `^`/`$`?

By default, `^` and `$` match only at the **very start and end of the entire input**. With **MULTILINE** (`Pattern.MULTILINE` or `(?m)`), they additionally match at the **start and end of each line** (around line terminators).

```
Input (two lines):  "foo\nbar"
Pattern  ^bar  without MULTILINE → no match
Pattern  ^bar  with    MULTILINE → matches "bar" on line 2
```

MULTILINE only affects `^` and `$` — it does **not** make `.` match newlines (that's DOTALL). The two are independent and often confused. Also note `\A` and `\z` ignore MULTILINE entirely — use them when you truly mean the absolute edges of the input regardless of mode.

### Q29. [Theory] What does the DOTALL (single-line) flag do, and why is the name confusing?

DOTALL (`Pattern.DOTALL` or `(?s)`) makes the `.` metacharacter match **any character including line terminators**. Without it, `.` stops at newlines.

```
Input:  "a\nb"
"a.b"          without DOTALL → no match (. won't cross \n)
"(?s)a.b"      with    DOTALL → matches "a\nb"
```

The confusion: DOTALL is also called **"single-line mode"** (the `s` flag) — yet MULTILINE is the `m` flag. They sound related but are orthogonal: `s` is about whether `.` crosses newlines; `m` is about whether `^`/`$` see line boundaries. You can enable both at once (`(?sm)`).

### Q30. [Coding] Extract all named groups from a date and reformat using named backreferences.

```java
import java.util.regex.*;

public class NamedGroupDemo {
    private static final Pattern P = Pattern.compile(
        "(?<y>\\d{4})-(?<m>\\d{2})-(?<d>\\d{2})");

    public static void main(String[] args) {
        String log = "start=2026-07-01 end=2026-12-31";
        Matcher m = P.matcher(log);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            // Reformat to d/m/y using named-group replacement
            m.appendReplacement(sb, "${d}/${m}/${y}");
        }
        m.appendTail(sb);
        System.out.println(sb); // start=01/07/2026 end=31/12/2026
    }
}
```

This shows `appendReplacement`/`appendTail` for streaming replacement with per-match logic, plus `${name}` named-group references in the replacement string.

### Q31. [Theory] What is catastrophic backtracking and how does it arise?

Catastrophic backtracking is an explosion in the number of paths a backtracking engine explores when a pattern can match a given substring in **exponentially many ways** and the overall match ultimately fails. It typically arises from **nested or overlapping quantifiers** over the same characters:

```
(a+)+$        on input "aaaaaaaaaaaaaaaaX"
```

Here every `a` can be distributed among the inner `a+` and the outer `+` in 2^n ways. Since the trailing `X`/`$` never matches, the engine tries *all* of them before giving up — O(2^n) time. The classic dangerous shapes are `(a+)+`, `(a*)*`, `(a|a)*`, and `(a|ab)+` followed by something that forces failure.

```
n chars:   10      20       30          40
attempts:  ~1K     ~1M      ~1B         ~1T   (each +10 chars ≈ ×1000)
```

A single bad pattern on attacker-controlled input can hang a thread for seconds to minutes.

### Q32. [Theory] What is ReDoS, and how do you defend against it?

**ReDoS** (Regular-expression Denial of Service) is an attack that exploits catastrophic backtracking: an attacker sends an input crafted to trigger exponential matching time, tying up CPU and threads and starving the service. Defenses, roughly in order of preference:

1. **Avoid vulnerable patterns** — eliminate nested quantifiers and ambiguous alternation. Rewrite `(a+)+` as `a+`.
2. **Use possessive quantifiers or atomic groups** (`(?>...)`) to forbid backtracking where it isn't needed.
3. **Anchor and make patterns unambiguous** so there's only one way to match.
4. **Bound input length** before matching untrusted data.
5. **Run untrusted regex with a timeout** on a separate thread you can interrupt, or use a non-backtracking engine (RE2/`re2j` in Java) which guarantees linear time.
6. **Never compile attacker-supplied regex** against your own engine without RE2-style guarantees.

In Java, the standard `java.util.regex` engine is backtracking and has **no built-in timeout**, so for untrusted patterns the `com.google.re2j` library (linear-time, no backtracking) is the pragmatic choice.

### Q33. [Coding] Rewrite a ReDoS-prone email-ish pattern to be safe.

A naively written validator with nested quantifiers is dangerous:

```java
// VULNERABLE: nested quantifier over overlapping char sets
Pattern bad = Pattern.compile("^([a-zA-Z0-9]+)*@example\\.com$");
// Input "aaaaaaaaaaaaaaaaaaaa!" triggers catastrophic backtracking
```

Fixes — remove the nesting and/or make it possessive/atomic:

```java
// 1. Remove the redundant outer group: a single quantifier is enough
Pattern fixed1 = Pattern.compile("^[a-zA-Z0-9]+@example\\.com$");

// 2. If you must group, make it possessive so it never backtracks:
Pattern fixed2 = Pattern.compile("^(?:[a-zA-Z0-9]++)@example\\.com$");

// 3. Or use an atomic group:
Pattern fixed3 = Pattern.compile("^(?>[a-zA-Z0-9]+)@example\\.com$");
```

The key insight: `([a-zA-Z0-9]+)*` and `[a-zA-Z0-9]+` match the *same language*, but the first has exponential ambiguity. Possessive `++` or atomic `(?>...)` collapse the redundant decision points so failures are detected in linear time.

### Q34. [Theory] What is an atomic group `(?>...)` and how does it relate to possessive quantifiers?

An **atomic group** `(?>...)` matches its contents and then **discards all backtracking positions** inside it — once the group has matched, the engine will never re-enter it to try a different way. It's a "commit" point.

```
(?>a+)b   on "aaab" → matches; on "aaa" → fails fast (no re-try of a+)
```

Possessive quantifiers are syntactic sugar for atomic groups around a single quantifier:

```
a++      ≡  (?>a+)
a*+      ≡  (?>a*)
(?:...)++  ≡  (?>(?:...)+)
```

Both are primary tools against catastrophic backtracking: they remove backtracking decisions you don't actually need, so the engine can fail in linear time instead of exploring an exponential search tree.

### Q35. [Practical] Why are most email-validation regexes wrong, and what should you do instead?

The full grammar for a valid email address (RFC 5321/5322) is extraordinarily complex — it allows quoted local parts, comments, IP-literal domains, and more — so any "complete" regex is enormous, unreadable, and still imperfect. Short regexes like `^\S+@\S+\.\S+$` either reject valid addresses or accept invalid ones.

Practical guidance for an interview:

1. **Do a cheap sanity check**, not full validation — e.g., "has exactly one `@`, with non-empty parts on each side":
   ```java
   Pattern SANE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
   ```
2. **Prefer a library** — Java's `jakarta.mail.internet.InternetAddress` (or Apache Commons Validator's `EmailValidator`) handles the grammar far better than hand-rolled regex.
3. **The only true test of an email is sending it** a confirmation link. Treat regex as a typo filter, not proof of existence.

This answer signals maturity: knowing *not* to over-engineer validation is as important as writing the pattern.

### Q36. [Practical] Validate an IPv4 address with regex, and explain the constraint regex can't easily express.

```java
import java.util.regex.Pattern;

public class IPv4 {
    // Each octet: 25[0-5] | 2[0-4]\d | 1\d\d | [1-9]?\d   (0-255, no leading zeros >1 digit)
    private static final String OCTET = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
    private static final Pattern IPV4 =
        Pattern.compile("^" + OCTET + "(\\." + OCTET + "){3}$");

    public static boolean isValid(String s) {
        return IPV4.matcher(s).matches();
    }

    public static void main(String[] args) {
        System.out.println(isValid("192.168.0.1")); // true
        System.out.println(isValid("256.1.1.1"));   // false (256 > 255)
        System.out.println(isValid("1.2.3"));        // false (only 3 octets)
    }
}
```

The constraint regex handles awkwardly is the **numeric range 0–255** — you must enumerate it structurally (`25[0-5]`, `2[0-4]\d`, ...) because regex can't say "≤ 255." This is a recurring theme: for numeric ranges, parsing the number and comparing in code is often clearer than an intricate alternation.

### Q37. [Theory] What is the difference between `\b` (word boundary) and `\B`, with examples?

`\b` matches at a position where one side is a word character (`\w`) and the other is not (or a string edge). `\B` matches everywhere `\b` does **not** — i.e., positions where both sides are word chars, or both are non-word chars.

```
Input: "foo.bar baz"
\bfoo\b   → matches "foo"  (boundaries before f and after o)
\Bar      → matches the "ar" in "bar" (B is inside a word, before 'a')
\bbaz\b   → matches "baz"
```

Subtlety: `\b` depends on the definition of `\w`. In ASCII mode, an accented letter like `é` is *not* a word char, so `\bé\b` behaves unexpectedly. Add `UNICODE_CHARACTER_CLASS` so `\w`/`\b` use Unicode word semantics, or use `\b{g}`-style grapheme handling where supported.

### Q38. [Coding] Write a Java method that masks all but the last 4 digits of any credit-card-like number in text.

```java
import java.util.regex.*;

public class CardMasker {
    // 13-19 digits, optionally separated by spaces or dashes
    private static final Pattern CARD =
        Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b");

    public static String mask(String text) {
        Matcher m = CARD.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String digits = m.group().replaceAll("[ -]", "");
            String last4 = digits.substring(digits.length() - 4);
            m.appendReplacement(sb, Matcher.quoteReplacement(
                "*".repeat(digits.length() - 4) + last4));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static void main(String[] args) {
        System.out.println(mask("Card 4111 1111 1111 1111 on file"));
        // Card ************1111 on file
    }
}
```

Note `Matcher.quoteReplacement` so any `$`/`\` in the computed replacement is treated literally. This is a realistic data-redaction task; in production you'd also Luhn-check to reduce false positives.

### Q39. [Theory] What are inline flag modifiers and scoped modifier groups?

Inline modifiers embed flags **inside** the pattern instead of passing them to `Pattern.compile`:

- `(?i)` at the start turns on a flag (CASE_INSENSITIVE) for the rest of the pattern.
- `(?-i)` turns it off.
- `(?i:...)` — a **scoped** (non-capturing) group that applies the flag only within the group.
- `(?i-x:...)` — turn `i` on and `x` off, scoped.

```
(?i)hello            → "hello", "HELLO", "HeLLo"
foo(?i:bar)baz       → "foo" and "baz" case-sensitive, only "bar" insensitive
```

Common flag letters: `i` (case-insensitive), `m` (multiline), `s` (dotall), `x` (comments/whitespace mode), `u` (unicode-case), `U` (unicode character classes). Inline flags keep the flag co-located with the pattern, which is handy when the pattern is stored as a plain string (e.g., in config).

### Q40. [Practical] What is the `x` / COMMENTS flag and when is it useful?

The COMMENTS flag (`Pattern.COMMENTS` or `(?x)`, also called "extended" or "verbose" mode) makes the engine **ignore unescaped whitespace** in the pattern and treat `#` as a line comment. This lets you format and document a complex regex.

```java
Pattern phone = Pattern.compile(
    "(?x)            # enable comments mode      \n" +
    "  \\(?          # optional opening paren     \n" +
    "  (\\d{3})      # area code                  \n" +
    "  \\)?[\\s-]?   # optional close + separator \n" +
    "  (\\d{3})      # prefix                     \n" +
    "  [\\s-]?       # separator                  \n" +
    "  (\\d{4})      # line number                \n");
```

It dramatically improves readability and maintainability of nontrivial patterns. Caveat: since whitespace is ignored, a literal space must be written `\ ` or `[ ]` or `\s`. Java text blocks pair nicely with `(?x)` for multi-line documented patterns.

### Q41. [Theory] How do you match a literal Unicode property, like "any letter" or "any currency symbol"?

Java supports **Unicode property escapes** with `\p{...}` (and the negation `\P{...}`):

- `\p{L}` — any kind of letter from any language.
- `\p{Lu}` / `\p{Ll}` — uppercase / lowercase letters.
- `\p{N}` / `\p{Nd}` — any number / decimal digit number.
- `\p{Sc}` — currency symbols (`$ € ¥ ₹ ...`).
- `\p{Punct}`, `\p{IsAlphabetic}`, `\p{Block=Cyrillic}`, `\p{script=Greek}` etc.

```java
Pattern letters = Pattern.compile("\\p{L}+");          // runs of letters
Pattern money   = Pattern.compile("\\p{Sc}\\s?\\d+");  // "€ 100", "$5"
```

These are far more robust than ASCII ranges for international text — `\p{L}` matches `café`, `naïve`, `日本語` correctly, whereas `[a-zA-Z]` does not. This is the right tool when "letter" or "digit" should mean it in the full Unicode sense.

## 🟠 Advanced (8–12 yrs)

### Q42. [Theory] Explain the difference between NFA and DFA regex engines.

Both model regex matching as a finite automaton, but differ in execution strategy:

- **DFA** (Deterministic Finite Automaton) — at each input character there's exactly one state to move to. Matching is **O(n)** in input length, **no backtracking**, and immune to catastrophic backtracking. The trade-off: a DFA can't support backreferences or capturing in the usual way, and building the DFA can cost memory. Used by `grep` (often), RE2, `re2j`, `awk`.
- **NFA** (Nondeterministic Finite Automaton, backtracking implementation) — explores alternatives by trying one and backtracking on failure. Supports rich features (backreferences, lookaround, capturing groups, possessive/atomic) but can be **exponential** in the worst case. Used by Perl, PCRE, Java, .NET, Python.

```
DFA:  linear time, limited features, ReDoS-proof
NFA:  rich features, capturing/backrefs, but ReDoS-prone
```

Java's `java.util.regex` is a **backtracking NFA**. Knowing which engine you're on tells you whether catastrophic backtracking is even possible.

### Q43. [Theory] Java's regex engine is backtracking. What are the performance implications and mitigations?

Because Java uses a backtracking NFA, the implications are:

- **Rich features available**: backreferences, lookaround, atomic/possessive quantifiers, named groups.
- **Worst-case exponential time** on ambiguous patterns over adversarial input (ReDoS).
- **No timeout** — a runaway match blocks the thread until it finishes; `Matcher` doesn't honor interrupts mid-match without help.

Mitigations:

1. Write **unambiguous** patterns; prefer possessive/atomic over plain greedy where backtracking is pointless.
2. **Bound input length** for untrusted data.
3. For untrusted patterns or inputs, run on a **dedicated, interruptible thread** with a watchdog, or use **`com.google.re2j`** (RE2 port) for linear-time guarantees (at the cost of backreferences/lookaround).
4. **Profile** with realistic worst cases, not just happy-path inputs.

```
Strategy table:
  trusted pattern + trusted input   → java.util.regex, just be sensible
  trusted pattern + untrusted input → bound length, prefer possessive
  untrusted pattern                 → re2j (linear) or strong sandbox + timeout
```

### Q44. [Theory] How does Java's `Matcher` behave with regard to thread safety and reuse?

The two classes have different concurrency stories:

- **`Pattern` is immutable and thread-safe.** Compile once, share freely across threads. This is why hoisting patterns to `static final` is safe and recommended.
- **`Matcher` is mutable and NOT thread-safe.** It holds per-match state (current region, group positions). A single `Matcher` must not be shared across threads.

Reuse within one thread is fine and efficient via `reset()`:

```java
static final Pattern P = Pattern.compile("\\d+"); // shared, thread-safe

void perThread() {
    Matcher m = P.matcher("");      // cheap, create per use/thread
    m.reset("abc123").find();       // reuse the same Matcher object
    m.reset("xyz789").find();
}
```

For high-throughput code, a common pattern is a `ThreadLocal<Matcher>` or simply creating a fresh `Matcher` per call (it's lightweight) while sharing the `Pattern`.

### Q45. [Practical] When should you NOT use a regex? Give concrete examples.

Regex is the wrong tool when the structure isn't regular or readability/correctness suffer:

- **Nested / recursive structures** — HTML, XML, JSON, balanced parentheses. These are context-free; regex (without recursion extensions Java lacks) cannot match arbitrarily nested brackets. Use a parser (Jackson, JSoup, an XML parser).
- **Context-sensitive formats** — CSV with quoted fields, INI with sections, full email/URL grammars. Use purpose-built parsers/validators.
- **Simple fixed-string work** — `contains`, `startsWith`, `split` on a literal — use `String` methods; they're faster and clearer.
- **Numeric range checks** — parse the number and compare; don't enumerate ranges in regex.
- **Anything attacker-controlled where a parser exists** — parsers fail predictably; ambiguous regex can ReDoS.

```
"Can a balanced-bracket language be matched by regex?" → No (it's not regular).
The famous SO answer about parsing HTML with regex captures this: use a parser.
```

The senior signal is recognizing the boundary and reaching for the right tool.

### Q46. [Coding] Implement a regex-based tokenizer that returns typed tokens in one pass.

```java
import java.util.*;
import java.util.regex.*;

public class Tokenizer {
    enum Type { NUMBER, IDENT, OP, WS }
    record Token(Type type, String text, int pos) {}

    // Alternation of named groups; order matters (longest/most-specific first)
    private static final Pattern LEXER = Pattern.compile(
        "(?<NUMBER>\\d+(?:\\.\\d+)?)" +
        "|(?<IDENT>[A-Za-z_]\\w*)"     +
        "|(?<OP>[+\\-*/=()])"          +
        "|(?<WS>\\s+)");

    public static List<Token> tokenize(String src) {
        List<Token> out = new ArrayList<>();
        Matcher m = LEXER.matcher(src);
        int expected = 0;
        while (m.find()) {
            if (m.start() != expected)        // gap = unrecognized char
                throw new IllegalArgumentException("Bad char at " + expected);
            expected = m.end();
            for (Type t : Type.values()) {
                if (m.group(t.name()) != null) {
                    if (t != Type.WS) out.add(new Token(t, m.group(), m.start()));
                    break;
                }
            }
        }
        return out;
    }

    public static void main(String[] args) {
        System.out.println(tokenize("x = 3.14 + y"));
        // [Token[NUMBER... no — IDENT x], OP =, NUMBER 3.14, OP +, IDENT y]
    }
}
```

This is the standard "single big alternation of named groups" lexing trick. The gap check (`m.start() != expected`) detects illegal characters. It's a legitimate, performant use of regex for simple grammars — but for anything with real nesting you graduate to a proper parser.

### Q47. [Theory] What is possessive-quantifier behavior and when can it cause a correct match to fail?

A possessive quantifier (`X*+`, `X++`, `X?+`, `X{n,m}+`) consumes greedily and **refuses to give anything back**. This is great for performance, but if a later part of the pattern *needs* some of those characters, the match fails where greedy would have succeeded.

```
".*+a"   on input "xxxa"
  .*+   consumes "xxxa" (everything)
  then  'a' has nothing left to match → MATCH FAILS
".*a"    greedy would backtrack one char and succeed.
```

So possessive quantifiers are safe precisely when the possessed pattern and what follows it are **disjoint** — there's no character that could legitimately belong to either. `\d++\.` is safe (a digit can't be a dot). `.*+something` is usually a bug. The discipline: use possessive to kill *useless* backtracking, never to remove backtracking the match actually depends on.

### Q48. [Behavioral] Tell me about a time a regex caused a production incident. How did you handle it?

A strong answer follows a structure (use your own real example):

- **Situation** — "A log-parsing service used a hand-written pattern with `(\w+\s*)+` to extract fields. A new log line format produced inputs that triggered catastrophic backtracking."
- **Impact** — "Worker threads pinned at 100% CPU; request latency spiked; the pool exhausted and the service started dropping traffic. It looked like a traffic surge but throughput was actually low."
- **Diagnosis** — "Thread dumps showed many threads stuck inside `java.util.regex.Pattern$...match`. That fingerprint (threads parked in regex matching) pointed straight at ReDoS."
- **Fix** — "Short term: bounded input length and added a watchdog timeout on an interruptible thread. Medium term: rewrote the pattern to remove the nested quantifier and made the inner group possessive. Long term: moved untrusted-input matching to `re2j` for linear-time guarantees, and added a CI check (lint) that flags nested quantifiers."
- **Lesson** — "Test regex against adversarial inputs, not just samples; treat any pattern touching untrusted data as a potential DoS vector."

The interviewer is checking for incident discipline (diagnose → mitigate → prevent) and that you understand ReDoS deeply, not just syntactically.

### Q49. [Theory] How does Unicode complicate regex, including grapheme clusters and normalization?

Unicode breaks several naive assumptions:

- **A "character" isn't a code point.** `é` may be one code point (U+00E9) or two (`e` + combining accent U+0301). The regex `.` matches **one code unit/point**, not a user-perceived character (grapheme). So `.` against decomposed `é` matches only the base `e`.
- **Grapheme clusters** — to match a whole user-perceived character (including emoji with modifiers, e.g., 👨‍👩‍👧 which is several code points joined by ZWJ), use `\X` where supported, or `\b{g}` boundaries. Java added grapheme support via `\b{g}` and `Pattern`/`BreakIterator` interplay.
- **Normalization** — equal-looking strings can differ in code points. Normalize (NFC/NFD via `java.text.Normalizer`) **before** matching so patterns are consistent.
- **Case folding** — full Unicode case folding (`UNICODE_CASE`) handles `İ`/`i`, `ß`/`SS`, Greek sigma, etc.; ASCII-only folding does not.

```java
String s = Normalizer.normalize(input, Normalizer.Form.NFC);
Pattern p = Pattern.compile("\\p{L}+",
    Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNICODE_CASE);
```

The takeaway: for international text, normalize first, use `\p{...}` and Unicode flags, and reason in graphemes when "one character" matters.

### Q50. [Practical] How would you validate or extract a URL, and why is a "perfect" URL regex impractical?

A truly RFC-3986-complete URL regex is gigantic and still doesn't validate that a host resolves or that a scheme is meaningful. Practical strategy:

1. **Prefer parsing over validating.** In Java, use `java.net.URI` (and on modern JDKs the more lenient parsing utilities) to parse, then inspect components:
   ```java
   try {
       URI u = new URI(candidate);
       if (u.getScheme() == null || u.getHost() == null) { /* reject */ }
   } catch (URISyntaxException e) { /* reject */ }
   ```
2. **Use regex only for cheap extraction/scanning**, e.g., finding URL-ish tokens in free text, then hand each to `URI` for real validation:
   ```java
   Pattern URLISH = Pattern.compile(
       "https?://[^\\s/$.?#].[^\\s]*", Pattern.CASE_INSENSITIVE);
   ```
3. **Know the pitfalls** — schemes other than http(s), internationalized domain names (punycode), userinfo, ports, percent-encoding, and security concerns (an over-permissive regex used for SSRF/redirect allow-listing is dangerous; validate the *parsed* host, never the raw string).

The senior point: regex finds *candidates*; a real parser validates them. For security decisions (allow-lists, redirect targets), never trust a regex over a parsed-and-canonicalized URL.

## 🔴 Expert (15+ yrs)

### Q51. [Theory] Compare backtracking engines (PCRE/Java) with automata engines (RE2). What are the deep trade-offs?

The split is fundamental and affects architecture:

| Dimension | Backtracking NFA (Java, PCRE, .NET) | Automata (RE2 / re2j) |
|-----------|-------------------------------------|------------------------|
| Worst-case time | Exponential (ReDoS possible) | Linear `O(n·m)`, guaranteed |
| Backreferences | Supported | **Not** supported (not regular) |
| Lookaround | Supported | Limited / none |
| Capturing | Full | Supported (Thompson + submatch tracking) |
| Memory | Low pattern compile cost | Can build large DFA lazily |
| Use case | Rich patterns, trusted input | Untrusted input/patterns at scale |

RE2 (Russ Cox's design, used at Google for processing untrusted user regex) deliberately **omits** features that require backtracking, guaranteeing linear time — the right call when you accept regex from users (search, log filters). Backtracking engines keep the rich features but put the burden of ReDoS-avoidance on the author. An expert chooses the engine per threat model: **trusted authors → backtracking; untrusted authors/inputs at scale → RE2**.

### Q52. [Theory] Explain how submatch extraction works in a Thompson-NFA / Pike VM, since "NFAs can't capture."

The folklore "DFA/NFA engines can't capture groups" is imprecise. A **Pike VM** (Thompson NFA executed as a virtual machine, due to Pike/Cox) tracks capturing by attaching **a set of saved positions (tags)** to each thread of execution:

- Compile the regex to bytecode (`char`, `split`, `jump`, `save n`).
- Run **all** active threads in lockstep over the input (the simulated NFA), so time stays linear (each input char processed once, threads deduplicated by program counter).
- A `save` instruction records the current input offset into that thread's capture slots.
- On a successful accept, the winning thread's saved slots give the submatch boundaries.

```
Program for (a+)(b+):
  0 save 2        ; start group 1
  1 char a
  2 split 1, 3    ; loop a+
  3 save 3        ; end group 1
  4 save 4        ; start group 2
  ...
```

This is how RE2/re2j provide capturing **without backtracking** and without exponential blowup. The cost is tracking O(threads × tags) state, but time stays linear. Understanding this dissolves the myth and explains why RE2 can capture yet drop backreferences (backrefs need the *matched text*, which can't be simulated in one linear pass).

### Q53. [Practical] You must run user-supplied regex at scale. Architect a safe design.

Treat user regex as untrusted code. A layered design:

1. **Engine choice** — use a **non-backtracking engine** (`re2j` in JVM) so worst-case time is linear regardless of pattern. This eliminates the ReDoS class entirely rather than chasing individual bad patterns.
2. **Feature restriction** — if you must use the backtracking engine, **reject** dangerous constructs at submit time (nested quantifiers, large `{n,m}` bounds, backreferences) via static analysis of the pattern AST.
3. **Resource bounds** — cap pattern length, compiled program size, input length, and number of matches. Reject huge `{0,1000000}`-style bounds.
4. **Isolation + timeout** — run matching on a bounded pool with per-call deadlines; for the backtracking engine, run on interruptible threads with a watchdog (since `Matcher` won't time out on its own).
5. **Caching** — cache compiled `Pattern`s keyed by pattern string with an LRU + size cap (compilation itself can be abused).
6. **Observability** — emit per-pattern match-time metrics; alert on outliers; keep a kill-switch to disable a pathological pattern.

```
Request → validate pattern (size/features) → compile (cached, re2j)
        → match with input cap + deadline → metrics/kill-switch
```

The expert framing: **defense in depth** — prefer eliminating the vulnerability class (RE2) over mitigating instances (timeouts), but layer both.

### Q54. [Behavioral] How do you set team standards and review practices for regex in a large codebase?

A mature answer covers people, process, and tooling:

- **Guidelines, not bans.** Document when regex is appropriate (simple tokenizing, validation-as-typo-filter) and when to reach for parsers/libraries (HTML/JSON/CSV/email/URL). Make the "use a library" decision the default for known-hard formats.
- **Readability rules.** Require `(?x)` verbose mode with comments for any nontrivial pattern; mandate named groups over numbered ones; hoist patterns to `static final` constants with a unit test documenting intent and edge cases.
- **Security gate.** Add CI linting (e.g., a ReDoS detector / static analyzer) that flags nested quantifiers and unbounded backtracking risk; require that any regex touching untrusted input either uses `re2j` or carries a justification and a fuzz test.
- **Testing.** Insist on tests including **adversarial inputs**, empty strings, Unicode, and boundary cases — not just happy paths. Property/fuzz testing for parsers replacing regex.
- **Review checklist.** During code review, ask: is the input trusted? could this backtrack? is there a library that does this better? is it anchored correctly (`matches` vs `find`)? are flags (Unicode/multiline) correct?
- **Knowledge sharing.** Run a brown-bag on ReDoS after the first incident; keep a short internal "regex pitfalls" page.

The signal: you scale *judgment*, not just your own skill — turning hard-won lessons into guardrails (lint, defaults, library wrappers) so the whole team avoids the same traps.

## 🧩 Extended Questions — Set 1: Deeper theory & internals

### 🟢 — extended

#### Q55. [Theory] What is a "regular language" formally, and which closure properties does it have?

A **regular language** is a set of strings recognized by some finite automaton (DFA or NFA) — equivalently, describable by a *regular expression* in the formal (Kleene) sense, or generated by a regular (Type-3) grammar. The three equivalent characterizations (FA, regex, regular grammar) are guaranteed identical by **Kleene's theorem**.

Regular languages are **closed** under a rich set of operations — if `A` and `B` are regular, so are:

- **Union** `A ∪ B`, **concatenation** `A·B`, **Kleene star** `A*` (the operations regex syntax exposes directly).
- **Intersection** `A ∩ B` and **complement** `Ā` (via the product/subset construction on DFAs — flipping accept states gives the complement).
- **Difference**, **reversal**, **homomorphism** and inverse homomorphism.

The practical upshot: a problem is regex-expressible *iff* the language you're matching is regular. Counting unboundedly (e.g., "equal numbers of `(` and `)`") or matching nested structure is **not** regular, which is exactly why you can't validate balanced brackets, HTML, or JSON with a pure (formal) regex. The closure under intersection/complement is also why some tools support `&` (intersection) and negation operators even though POSIX/PCRE syntax omits them.

#### Q56. [Theory] What is Kleene's theorem and why does it matter to regex practitioners?

**Kleene's theorem** states that the three formalisms — finite automata, regular expressions, and regular grammars — describe **exactly the same class of languages**. Anything one can express, the others can too.

Why it matters in practice:

- It is the theoretical license for **compiling** a regex into an automaton (Thompson's construction turns a regex into an NFA; subset construction turns that NFA into a DFA). Every regex engine relies on this equivalence.
- It tells you the **ceiling** of pure regex: if a language isn't FA-recognizable, no amount of clever regex (in the formal sense) will match it. This is the formal grounding for "use a parser for nested structures."
- It clarifies that the *backtracking* "regexes" of Java/PCRE — with backreferences and lookaround — have stepped *outside* Kleene's class. They match some non-regular languages, which is precisely why they trade the linear-time guarantee for exponential worst cases.

#### Q57. [Theory] How does Thompson's construction turn a regex into an NFA?

**Thompson's construction** builds an NFA from a regex *compositionally*, one operator at a time, using ε-transitions (empty moves) to glue fragments together. Each sub-expression becomes a small NFA fragment with exactly one start and one accept state:

- **Literal `a`** — two states with a single `a`-transition between them.
- **Concatenation `RS`** — connect `R`'s accept to `S`'s start with an ε-transition.
- **Alternation `R|S`** — a new start state ε-branches into both `R` and `S`; both their accepts ε-merge into a new accept.
- **Kleene star `R*`** — a new start/accept pair with ε-edges that allow skipping `R` entirely and looping back through it.

The result has **O(m)** states and edges for a pattern of length `m` (each operator adds a constant number of states), and crucially **no backtracking is required** to simulate it. Running all ε-closures in lockstep over the input (Thompson's simulation) gives linear-time matching. This construction is the foundation of RE2/`re2j` and `grep`'s DFA path.

#### Q58. [Theory] What does it mean that anchors and lookaround are "zero-width"?

"Zero-width" means the construct **tests a condition at a position** in the input but **consumes no characters** — after it succeeds (or fails), the engine's cursor is exactly where it started. They assert *about* the text rather than matching text.

Zero-width constructs include:

- **Anchors** — `^`, `$`, `\A`, `\z`, `\Z`, `\b`, `\B`.
- **Lookahead** — `(?=...)`, `(?!...)`.
- **Lookbehind** — `(?<=...)`, `(?<!...)`.

Consequences that trip people up:

- Two zero-width assertions can sit back-to-back and both must hold at the *same* position: `(?=.*\d)(?=.*[A-Z])` (the password idiom).
- A quantifier applied to a purely zero-width match (e.g., `(?=x)*`) is meaningless or an infinite-loop risk; engines guard against empty-match loops.
- `\b` between two assertions changes *whether* a position qualifies, not *where* the cursor is. Understanding zero-width is the key to reading complex validation patterns.

#### Q59. [Theory] What is an ε-transition (empty transition) and why do NFAs use them?

An **ε-transition** is an edge an automaton may follow **without consuming an input character** — a "free" move between states. They exist only in NFAs (a DFA by definition has no ε-moves and exactly one transition per symbol).

They matter because:

- **Thompson's construction** uses ε-transitions as the universal glue to compose fragments (skip a starred sub-expression, branch into alternatives), keeping the construction simple and linear-size.
- The **ε-closure** of a state — the set of all states reachable via ε-moves — is the core operation when simulating an NFA or converting it to a DFA via subset construction. At each input symbol you take the ε-closure of where you could be.
- They let an NFA represent "optionality" and "choice" structurally without duplicating sub-automata.

When converting NFA→DFA, ε-transitions are eliminated by folding ε-reachable states into combined DFA states, which is why a DFA can match in strict O(n) with no per-character branching.

#### Q60. [Practical] Why does a regex engine "compile" a pattern, and what is the cost model?

Compiling translates the **pattern string** into an internal representation the matcher executes — for Java's `java.util.regex`, a tree/graph of `Node` objects; for RE2, NFA/DFA program bytecode. This separates the **one-time parse+build cost** from the **per-input match cost**.

Cost model:

- **Compile is O(m)**-ish in pattern length (plus more for large `{n,m}` expansions, which can inflate the program). It allocates objects and validates syntax — relatively expensive.
- **Match is per-input**; for a backtracking engine it ranges from O(n) on well-behaved patterns to O(2^n) on pathological ones.

This is exactly why the universal advice is to **hoist `Pattern.compile(...)` into a `static final` constant** and reuse it. `String.matches`, `String.replaceAll`, `String.split` all recompile on every call — fine once, wasteful in a loop. The `Pattern` is immutable and thread-safe, so a single compiled instance can serve all threads, amortizing the compile cost to effectively zero.

#### Q61. [Theory] What is the difference between a "match" and a "search," and how do engines implement search?

A **match** (anchored) asks "does the pattern match *starting here* / *covering all of this*?" A **search** asks "is there *any* position where the pattern matches?"

Engines implement search as a match attempted at each successive starting offset: try at index 0; on failure, advance to index 1 and retry; continue until a match or the end. Conceptually this is "`.*?` then the pattern," and indeed many engines internally treat an unanchored search as an implicit leading "skip-ahead."

Practical reflections in Java:

- `matches()` = anchored both ends (start-to-end, full coverage).
- `lookingAt()` = anchored at start only.
- `find()` = search; it remembers where it left off so repeated calls scan forward.

The per-offset retry is why an **anchored** pattern (`^...`) can be dramatically faster — the engine doesn't have to re-attempt at every position. It's also why a poorly anchored pattern over long input pays an O(n) outer loop on top of per-attempt cost.

#### Q62. [Theory] What is the empty match, and how do engines avoid infinite loops on it?

An **empty (zero-length) match** is a successful match that consumes no characters — e.g., `a*` matches the empty string at any position, and `(?=x)` is always zero-length. They're legitimate but dangerous for iteration: if `find()` returned an empty match and the cursor didn't advance, the loop would spin forever at the same spot.

Engines apply a standard rule: **after an empty match, force the next search to start one position later** (advance the cursor by one, stepping over a full code point/grapheme where applicable). Java's `Matcher.find()` does exactly this — if a match is empty, it bumps the region start so successive `find()` calls make progress.

This also explains subtle behavior in `split` and `replaceAll`: e.g., `"abc".split("")` yields `["a","b","c"]` because empty matches between characters are found at each boundary, with the engine stepping forward each time. Knowing the empty-match advance rule explains otherwise-baffling off-by-one and "extra empty token" results.

### 🟡 — extended

#### Q63. [Theory] How does subset construction convert an NFA to a DFA, and what is the state-explosion risk?

**Subset (powerset) construction** builds a DFA whose states are *sets* of NFA states. Start from the ε-closure of the NFA start state; for each DFA state (a set `S`) and each input symbol `c`, the next DFA state is the ε-closure of all NFA states reachable from any state in `S` on `c`. A DFA state is accepting if its set contains any NFA accept state. The DFA is deterministic by construction — exactly one transition per symbol, no ε-moves — so it matches in strict O(n).

The risk: an NFA with `k` states can in the worst case yield a DFA with up to **2^k** states (every subset). Patterns like `.*a.{n}` (a character `n` positions from the end) are classic blow-up cases. This is why production automata engines (RE2) build the DFA **lazily** (on-the-fly, caching only states actually visited) and cap the cache size, falling back to the NFA simulation if the DFA would grow too large. The lazy approach keeps memory bounded while retaining linear time.

#### Q64. [Theory] Why can't a DFA implement backreferences, and what complexity class does that imply?

A DFA has a **fixed, finite** number of states and no memory beyond "which state am I in." A backreference like `(.+)\1` requires the engine to **remember the actual text** the group captured (potentially arbitrarily long) and compare it later — that's unbounded memory, which a finite automaton categorically lacks.

Formally, the language `{ww | w ∈ Σ*}` (a string followed by a copy of itself) is **not regular** and not even context-free; backreferences let "regexes" describe such non-regular, even context-sensitive languages. That extra power has a price: deciding whether a string matches a regex *with backreferences* is **NP-hard** in general. This is the deep reason RE2/`re2j` simply **omit** backreferences — they're incompatible with the linear-time, automaton-based guarantee. Backtracking engines support them precisely because backtracking can carry the captured text along, but that's also where exponential blow-ups live.

#### Q65. [Theory] How does a backtracking engine represent and explore its search tree?

A backtracking NFA treats matching as **depth-first search** over a tree of choices. Each point of nondeterminism — a quantifier (`a*`: match more or stop?), an alternation (`a|b`: try left or right?) — is a branch. The engine:

1. Picks one branch (greedy: "match more / try left first"; lazy: the opposite order).
2. Descends, consuming input, recording a **backtrack point** (saved cursor position + which alternative to try next) on a stack.
3. On failure, **pops** the most recent backtrack point and tries the next alternative from there.

The matched-so-far state lives on the call/explicit stack. Catastrophic backtracking is simply this tree being **exponentially large**: nested quantifiers like `(a+)+` create overlapping ways to partition the same characters, so the DFS explores 2^n leaves before concluding failure. Atomic groups `(?>...)` and possessive quantifiers work by **pruning** the tree — they discard the saved backtrack points for that sub-expression, so the DFS can never re-enter it.

#### Q66. [Theory] What is the pumping lemma for regular languages, and how do you use it to prove a pattern can't be done with regex?

The **pumping lemma** is a necessary property of every regular language: there exists a "pumping length" `p` such that any string `s` in the language with `|s| ≥ p` can be split `s = xyz` where `|xy| ≤ p`, `|y| ≥ 1`, and **every** pumped string `xyⁱz` (i ≥ 0) is *also* in the language. Intuitively, a DFA with `p` states must repeat a state within the first `p` characters, and the loop between repeats can be traversed any number of times.

You use it to **prove a language is *not* regular** (by contradiction): assume it's regular, take the adversary's `p`, choose a clever `s`, and show that *some* valid split is forced to pump out of the language.

Classic example — `{aⁿbⁿ | n ≥ 0}` (equal `a`s then `b`s, the essence of balanced brackets): pick `s = aᵖbᵖ`. Since `|xy| ≤ p`, `y` is all `a`s; pumping `y` adds `a`s without `b`s, breaking the count. Contradiction → not regular. This is the rigorous version of "regex can't count matched pairs," and it directly justifies refusing to validate balanced parentheses, nesting depth, or `<tag>...</tag>` pairing with formal regex.

#### Q67. [Theory] Java's regex supports recursion-like features? Distinguish what Java can and cannot do versus PCRE.

Java's `java.util.regex` is a **backtracking NFA but deliberately limited** relative to PCRE/Perl. Concretely:

- **No recursion / subroutine calls.** PCRE has `(?R)`, `(?1)`, `\g<name>` recursion that can match *balanced* nested structures (a context-free trick bolted onto regex). **Java has none of these**, so you genuinely cannot match arbitrarily nested brackets in Java regex — you must use a parser.
- **No `\K`** (keep-out / reset match start) that PCRE offers.
- **No possessive-only constructs missing**, but **no variable-length lookbehind** — Java's lookbehind must be bounded length (PCRE/.NET differ; .NET allows arbitrary-length lookbehind).
- **No conditionals** `(?(1)yes|no)` that PCRE/.NET support.
- Java **does** support: atomic groups `(?>...)`, possessive quantifiers, named groups `(?<n>...)`, lookahead, *bounded* lookbehind, Unicode properties `\p{...}`, and inline/scoped flags.

The senior point: knowing these gaps prevents you from copy-pasting a PCRE "match nested parens" recipe into Java and being mystified when it doesn't compile or doesn't work. For nesting in Java, reach for a real parser.

#### Q68. [Practical] How does Java compile and store a Pattern internally, and what does that mean for `{n,m}` bounds?

Java compiles a pattern into a **linked graph of `Node` subclasses** (an in-memory program), each node implementing a `match` step that calls the next node — matching is recursive descent over this graph with backtracking encoded in the call structure. There's no separate DFA; it's a tree-walking backtracker.

Key consequence for **bounded quantifiers** `{n,m}`: large bounds can **expand** into many nodes or deep recursion. A pattern like `(?:...){0,100000}` creates substantial internal structure and can both bloat compile time/memory and, at match time, drive deep recursion that risks `StackOverflowError` on long inputs (Java's regex recursion has historically caused stack overflows on very long repetitive input). Practical guidance:

- Keep `{n,m}` bounds modest; reject huge user-supplied bounds.
- Be aware that *matching* long input against certain patterns can overflow the stack (a known Java footgun), distinct from *exponential* backtracking.
- For very large repetition counts, restructure or validate length separately rather than encoding it in the quantifier.

#### Q69. [Theory] What is the longest-match / leftmost-longest (POSIX) semantics versus Perl/Java leftmost-first semantics?

There are two competing rules for *which* match wins when several are possible:

- **Leftmost-first (Perl/Java/PCRE)** — alternation tries branches **in written order** and takes the **first** that lets the *overall* pattern succeed, not necessarily the longest. `(a|ab)` against `"ab"` matches just `"a"` (first alternative wins), even though `"ab"` is longer.
- **Leftmost-longest (POSIX ERE)** — at each point the engine prefers the **longest** possible match overall, regardless of alternative order. POSIX `(a|ab)` against `"ab"` matches `"ab"`.

Both agree on the leftmost *starting position*; they differ on length/branch preference. RE2 can emulate POSIX longest-match mode; Java/Perl are leftmost-first. This explains real surprises: an alternation order that "shouldn't matter" absolutely does in Java — put the **longer/more-specific alternative first** (e.g., `(\d{4}|\d{2})`) so it isn't preempted. The tokenizer "longest match first" discipline is a direct application of understanding leftmost-first semantics.

### 🟠 — extended

#### Q70. [Theory] Explain DFA minimization (Hopcroft/Myhill-Nerode) and why it matters for engine memory.

Two DFAs can recognize the same language with different state counts; **minimization** produces the unique smallest DFA. The theoretical backbone is the **Myhill-Nerode theorem**: the minimal DFA's states correspond exactly to the equivalence classes of input prefixes that lead to indistinguishable futures (two prefixes are equivalent if, for every possible suffix, both either accept or both reject). The number of such classes is the minimal state count — and the theorem doubles as another non-regularity proof (infinitely many classes ⇒ not regular).

**Hopcroft's algorithm** computes this minimization in **O(n log n)** by iteratively *refining* a partition of states: start by splitting accept vs non-accept, then repeatedly split any group whose members transition into *different* groups on some symbol, until stable.

Why it matters: subset construction can produce DFAs with redundant states; minimizing shrinks the table, reducing memory and improving cache behavior in DFA-based engines (grep-style, lex/flex-generated scanners). RE2's lazy DFA effectively gets some of this benefit by only materializing reachable, distinct states on demand rather than minimizing eagerly.

#### Q71. [Theory] How does the Pike VM track capture groups in linear time, and what is its per-thread cost?

The **Pike VM** (Thompson's NFA executed as a bytecode VM, extended by Pike/Cox for submatch capture) runs **all live threads in lockstep** over the input, one input character per step, so total time is **O(n · m)** — linear in input. Each "thread" is a program counter plus a vector of **capture slots** (tagged positions). A `save k` instruction writes the current input offset into slot `k`; group `i` uses slots `2i` (start) and `2i+1` (end).

Crucial detail keeping it linear: at each step the VM **deduplicates threads by program counter** — if two threads reach the same instruction, only one survives (priority order resolves which capture set wins, preserving leftmost-first/POSIX semantics). Without dedup you'd be back to exponential.

Per-thread cost is **O(number of capture slots)** because forking a thread copies its capture vector. So the VM's space/time is roughly **O(m × number_of_groups)** per input position in the worst case. This is exactly how RE2/`re2j` deliver capturing groups **without** backtracking — and why they still can't do backreferences (those need the captured *text* for comparison, not just positions, which can't be resolved in a single linear pass).

#### Q72. [Practical] You profiled a hot regex path and it's CPU-bound on matching. Walk through optimization without changing the language matched.

Optimize the *engine workload*, not the accepted set:

1. **Anchor it.** If matches only occur at the start/whole string, add `^`/use `matches()`/`lookingAt()` so the engine doesn't retry at every offset — turns an O(n) outer scan into one attempt.
2. **Kill needless backtracking.** Replace greedy with **possessive/atomic** where the possessed run and its follower are disjoint (`\d++`, `(?>...)`). Same language, no backtrack stack churn.
3. **Front-load a cheap discriminator.** Order alternation with the **most-likely/cheapest** branch first (leftmost-first means earlier branches are tried first), and start patterns with a literal so the engine's first-character optimization (Boyer-Moore-style "find the required literal") can skip ahead.
4. **Reduce capturing.** Convert `(...)` to `(?:...)` where you don't read the group — fewer slots to maintain.
5. **Hoist + reuse.** `static final Pattern`; reuse `Matcher` via `reset()`; avoid `String.matches`/`split` recompiling.
6. **Bound the work.** Use `Matcher.region(...)` to limit scanning to the relevant span instead of the whole buffer.
7. **Measure** with JMH on representative *and* adversarial inputs; confirm the language is unchanged with a golden test set.

The discipline: each step removes engine *work* (offsets retried, backtrack frames, capture copies) while leaving the matched language identical — verified by tests.

#### Q73. [Theory] What are the security implications of `UNICODE_CASE` and case-folding in security-sensitive matching (e.g., allow-lists)?

Case-insensitive matching over Unicode is a **security minefield** for allow/deny decisions:

- **Many-to-one and one-to-many folds.** German `ß` case-folds toward `ss`; Turkish dotless `ı`/dotted `İ` break the naive `i↔I` assumption (the "Turkish-i" problem). An allow-list comparing case-insensitively can match strings an attacker didn't expect, or fail to match ones it should.
- **Locale dependence.** `String.toLowerCase()` (no locale) vs `toLowerCase(Locale.ROOT)` can differ; security comparisons must use a **fixed locale** (`Locale.ROOT`) to avoid environment-dependent results.
- **Confusables / homoglyphs.** Even with correct folding, visually identical characters from different scripts (Cyrillic `а` vs Latin `a`) bypass naive matching — case folding doesn't address this; you need **confusable-skeleton** normalization (UTS #39).
- **Normalize first.** Compose to NFC/NFKC *before* matching so combining sequences and compatibility variants don't sneak past.

Hardening rule: for security-relevant matching, **canonicalize** (normalize + casefold with `Locale.ROOT` + optionally confusable-fold) into a normal form, then compare/allow-list against that normal form — never against the raw input with a case-insensitive flag bolted on. And validate the *parsed/canonical* artifact (host, path), not the raw string.

#### Q74. [Theory] How does atomic grouping change the formal search, and why is `(?>a*)a` guaranteed to fail to match `"aaa"`?

An **atomic group** `(?>...)` matches its body greedily and then **throws away every backtrack point created inside it**. Formally, it prunes that entire subtree from the backtracking DFS: once the group commits to a match, the engine may never return to try a shorter (or different) match for the group, even if doing so is the only way the *overall* pattern could succeed.

Trace `(?>a*)a` on `"aaa"`:

- `a*` greedily consumes **all three** `a`s. The atomic group commits to that.
- The trailing `a` now needs a fourth `a`, but input is exhausted.
- Normally `a*` would **backtrack**, give back one `a`, and let the trailing `a` match the third character. But the atomic group **forbids** re-entering `a*` — those backtrack points were discarded.
- No alternative remains → **overall failure**.

Contrast plain `a*a`, which *does* match `"aaa"` (greedy backtracks one). This is the same mechanism as possessive `a*+a`. The lesson: atomic/possessive constructs are correct **only** when the body and what follows it are disjoint (can't legitimately claim the same characters). Used otherwise, they convert a should-succeed into a guaranteed failure — which is exactly why they're a precise tool against *useless* backtracking but a bug when the match depends on giving characters back.

#### Q75. [Practical] How do `Matcher.region`, `useAnchoringBounds`, and `useTransparentBounds` interact, and when do they matter?

`Matcher.region(start, end)` restricts matching to a **sub-span** of the input without copying a substring — efficient for scanning one field of a larger buffer. Two flags control how the **region boundaries** behave with respect to anchors and lookaround:

- **`useAnchoringBounds(true)`** (the default) — the region's `start`/`end` act as `^`/`$` and `\b` see them as input edges. Turn it **off** when the region is a window into larger text and you *don't* want anchors to fire at the artificial cut.
- **`useTransparentBounds(true)`** (default is **false**, opaque) — lets **lookaround** and `\b` **peek past** the region boundary into the surrounding text. With opaque (default) bounds, a lookbehind at the region start sees nothing before it, which can produce wrong boundary decisions.

When they matter: **incremental/streaming scanning**, tokenizers that process one region at a time, or re-running a sub-pattern on a slice while still needing correct word-boundary/lookaround semantics relative to the *full* text. Misconfigured bounds cause subtle bugs — anchors matching at a chunk edge that isn't a real line edge, or lookbehind failing because it can't see the preceding chunk. The expert move: set transparent + non-anchoring bounds when a region is a *view* into larger input, and keep the defaults when the region *is* the logical input.

### 🔴 — extended

#### Q76. [Theory] Map the Chomsky hierarchy onto regex tooling: what each level can match and where real-world "regex" sits.

The **Chomsky hierarchy** ranks language classes by the automaton needed to recognize them:

| Type | Class | Automaton | Regex relevance |
|------|-------|-----------|-----------------|
| 3 | Regular | Finite automaton | *Formal* regex; RE2/`re2j`; grep DFA path |
| 2 | Context-free | Pushdown automaton | Nesting/balanced brackets, JSON, arithmetic — needs a parser |
| 1 | Context-sensitive | Linear-bounded automaton | `{aⁿbⁿcⁿ}`, some backreference languages |
| 0 | Recursively enumerable | Turing machine | Unrestricted |

Where real tools sit:

- **Pure (formal) regex = Type 3.** It cannot count or nest unboundedly.
- **PCRE/Perl with recursion `(?R)`** climbs into **Type 2 territory** — recursion gives a stack, so it can match balanced structures (Java *can't*, lacking recursion).
- **Backreferences** push into **context-sensitive (Type 1)** and make matching NP-hard; the language `{ww}` is the canonical example.

The expert framing: "regex" colloquially spans three Chomsky levels depending on which extensions are present, with wildly different complexity guarantees. Choosing a tool means choosing a level: Type-3 engines (RE2) for safety and linear time; a Type-2 **parser** for genuine nesting; never expect a Type-3 engine to do Type-2 work (the pumping lemma forbids it).

#### Q77. [Theory] Explain how lazy DFA construction (RE2-style) bounds memory while keeping linear time, and its failure modes.

RE2 keeps the **linear-time** guarantee of a DFA without paying the worst-case **2^k** state-explosion up front by building the DFA **lazily (on the fly)**:

- It starts from the NFA and, as input is consumed, **computes each DFA state (a set of NFA states) only when first reached**, then **caches** it keyed by the NFA-state-set.
- Subsequent transitions hit the cache (amortized O(1) per character), so steady-state matching is the speed of a precomputed DFA.
- A **bounded cache** caps memory. If the cache fills (a pathological pattern would need too many distinct DFA states), RE2 **flushes** it and continues, or **falls back to the NFA (Pike VM) simulation** — slower per character but still **linear** and bounded in memory.

Failure modes / trade-offs:

- **Cache thrashing**: patterns that touch enormously many distinct states (e.g., `.*a.{n}` with large `n`) cause repeated flushes, degrading to NFA-simulation speed — still linear, just with a larger constant.
- **No backrefs/lookaround**: the lazy DFA can't represent these, so RE2 omits them entirely.
- **Submatch tracking** still routes through the Pike VM (the DFA quickly decides *whether* it matches and *where* it ends; capture extraction is a separate, bounded pass).

The architectural lesson: lazy DFA + bounded cache + NFA fallback is *defense in depth for performance* — you get DFA speed on the common case and a hard linear/memory ceiling on the adversarial case, which is precisely the property you want when matching **untrusted** patterns at scale.

#### Q78. [Practical] Design a static analyzer that flags ReDoS-prone patterns at CI time. What does it look for?

A CI-time ReDoS linter analyzes the **pattern AST** (not the input) for structures that admit super-linear backtracking:

1. **Parse the regex** into an AST (quantifiers, groups, alternations, char classes).
2. **Detect the canonical danger shapes:**
   - **Nested quantifiers** over overlapping content: `(a+)+`, `(a*)*`, `(a+)*`, `(\w+)+`.
   - **Quantified groups whose alternatives overlap**: `(a|a)*`, `(a|ab)+` — ambiguity means multiple ways to match the same span.
   - **Adjacent quantifiers on the same char class**: `\d+\d+`, `.*.*`.
   - **Large or unbounded `{n,m}`** bounds, especially nested.
3. **Reachability of failure**: super-linear blow-up needs a *failing* tail (something after the ambiguous part that can reject), so flag ambiguous repetition followed by a mandatory literal/anchor that can fail.
4. **Formal core**: the rigorous version checks whether the NFA built from the pattern is **ambiguous** — i.e., some input string has *two distinct* accepting paths through a starred sub-expression (the "star height / ambiguous NFA" test). Tools like `safe-regex`, `recheck`, and RXXR2 approximate or compute this.
5. **Policy actions**: fail the build, or require a waiver with a **fuzz test** (pump the suspected attack string and assert a time bound) and/or a switch to `re2j`.

The expert nuance: pure pattern-shape heuristics produce false positives/negatives; the gold standard is **ambiguity analysis of the NFA** plus an **empirical pump test** (feed `a^n` and assert match time grows linearly, not quadratically/exponentially). Layer heuristic lint (fast, in editor) with deeper ambiguity analysis (in CI) and runtime defense (`re2j`/timeouts) — no single layer is sufficient.

#### Q79. [Theory] What is "catastrophic backtracking" formally in terms of NFA ambiguity, and how does it relate to the star height / IDA condition?

Formally, catastrophic backtracking happens when the **NFA built from the pattern is *ambiguous*** in a way the backtracking simulator explores exhaustively. Precisely:

- A backtracking engine enumerates **all accepting paths** of the NFA for a given input before reporting failure (it can't know it'll fail until it's tried them). If the number of distinct paths through some sub-expression grows **polynomially or exponentially** in input length, matching time does too.
- **Exponential** blow-up corresponds to the **IDA (Infinite Degree of Ambiguity)** condition: there's a state `q` and a non-empty string `w` such that the NFA has **two distinct paths** from `q` back to `q` spelling `w` (a "doubly-traversable loop"). `(a+)+` has exactly this — for the same run of `a`s there are two ways to loop. Each repeated `w` multiplies the path count → 2^n.
- **Polynomial** (e.g., quadratic) blow-up corresponds to **finite but >1 degree of ambiguity** — overlapping but not self-nested loops, like `\d+\d+` or `.*x.*y` over inputs lacking the tail.

This connects to **star height** and the theory of ambiguous regular expressions: a regex whose NFA is *unambiguous* (at most one accepting path per input) is **immune** to catastrophic backtracking — it backtracks O(1) per position. Tools (`recheck`, RXXR2) detect ReDoS by searching for IDA/EDA witnesses. The deep takeaway: ReDoS is not a quirk of "bad patterns" but a **measurable property — NFA ambiguity** — and the fix is to make the pattern unambiguous (possessive/atomic to prune duplicate paths, or anchored/disjoint alternatives) or to run on an engine (RE2) that *never* enumerates paths.

#### Q80. [Behavioral] As an architect, you must choose a regex strategy for a multi-team platform handling untrusted user-supplied patterns. Defend your decision and how you'd roll it out.

A strong answer frames it as **risk management plus organizational change**, not just a library pick.

- **Decision & rationale.** "For untrusted user-supplied patterns, I'd standardize on a **non-backtracking engine (`re2j` on the JVM)** behind a shared internal library. Rationale: it eliminates the *entire ReDoS vulnerability class* by construction (linear-time guarantee) rather than playing whack-a-mole with timeouts and per-pattern fixes. The cost — losing backreferences and lookaround — is acceptable because *user* patterns for search/filter rarely need them, and I'd document the few cases that do."
- **Defense in depth.** "Even with `re2j`, layer **input-length caps**, **pattern-length/complexity caps**, **per-call deadlines**, a **compiled-pattern LRU cache** (compilation itself is a DoS vector), and **per-pattern match-time metrics with a kill-switch**. Prefer eliminating the class, but never rely on a single control."
- **Where backtracking stays.** "Trusted, in-repo patterns can keep `java.util.regex` for its richer features — but gated by a **CI ReDoS linter** (ambiguity analysis + pump tests) and a review checklist (trusted input? anchored? possessive where safe?)."
- **Rollout.** "Ship the safe engine as a **wrapper API** so teams adopt it by default, not by discipline; provide a migration guide and codemod; add the lint to the shared CI template; run a **brown-bag on ReDoS** seeded by a real incident; track adoption with a dashboard and a deprecation timeline for raw `java.util.regex` on untrusted paths."
- **Trade-off honesty.** "I'd be explicit that this trades some expressiveness and adds a dependency, and I'd revisit if a major use case genuinely needs backreferences — handling that case with a sandboxed, timed, length-bounded backtracking path rather than weakening the default."

The interviewer is checking for **threat-model-driven decision-making**, willingness to trade features for safety, and the ability to turn a technical choice into **guardrails and defaults** that scale judgment across many teams — plus the maturity to state costs and revisit conditions explicitly.

#### Q81. [Theory] How does a backtracking engine apply a "first-character" / required-literal optimization, and why does it matter for an unanchored search?

Before exploring the backtracking search tree at every offset, mature engines extract **necessary preconditions** from the compiled pattern to skip impossible starting positions cheaply:

- **First-character set / required prefix.** If every match must begin with a specific character or a small set (e.g., the pattern starts with a literal or `[abc]`), the engine scans the input with a fast primitive (often a `memchr`/`indexOf`/Boyer-Moore-Horspool style search) to jump straight to candidate offsets, instead of launching the full matcher at every index.
- **Required literal anywhere.** If the pattern *must* contain a literal substring (e.g., `@example\.com`), the engine can search for that literal first and only attempt the full match near hits.
- **Minimum length.** The engine knows the shortest possible match length and won't even try in the final `minLen-1` positions.

Why it matters: an unanchored search is conceptually an O(n) outer loop of match attempts. These optimizations turn the *outer* loop from "attempt the full automaton at every position" into "skip to the few positions that could conceivably match," often making a poorly-anchored pattern behave almost as well as an anchored one. Java's engine performs `BnM` (Boyer-Moore) and first-character optimizations for suitable patterns; you help it by **starting patterns with a discriminating literal** rather than `.*` or a broad class. It does **not** rescue you from *intra-attempt* catastrophic backtracking — that's a separate axis.

#### Q82. [Theory] Distinguish the *degree of ambiguity* classes — unambiguous, polynomially ambiguous, exponentially ambiguous — and the matching cost each implies.

The worst-case cost of a backtracking match is governed by the **ambiguity of the underlying NFA** — how many distinct accepting paths a single input can have through a sub-expression:

- **Unambiguous (finite degree 1).** Every input has at most **one** path. Backtracking is effectively linear, O(n) — no path enumeration. Anchored, disjoint patterns like `\d{3}-\d{4}` are here.
- **Polynomially ambiguous (IDA-free but degree > 1).** The number of paths grows like a polynomial in input length — typically because two *separate* loops can each consume the same characters (e.g., `.*x.*` or `\d+\d+` on inputs missing the tail). Cost is **O(nᵏ)** for some fixed `k` (often quadratic). The formal witness is **finite ambiguity** in the NFA without a self-nested loop.
- **Exponentially ambiguous (EDA / IDA condition).** A single state has **two distinct loops** spelling the same string (`(a+)+`, `(a|a)*`). Path count is **O(2ⁿ)** → catastrophic. The witness is the EDA (Exponential Degree of Ambiguity) structure.

Why an expert cares: this taxonomy is *the* precise vocabulary for ReDoS. "Is this pattern dangerous?" becomes "what is its ambiguity degree?" — answerable by static NFA analysis (tools like `recheck` report exactly polynomial vs exponential vulnerability). It also explains why a pattern can be *non-catastrophic* yet still **quadratic** (a real, exploitable slowdown that the crude "look for nested quantifiers" heuristic misses), and why the durable fix is to make the NFA **unambiguous** (anchoring, disjoint alternatives, possessive/atomic pruning) or to switch to a path-non-enumerating engine (RE2).

#### Q83. [Practical] How do `Pattern.split`, `Pattern.splitAsStream`, and the `limit` parameter behave at the internals level, including trailing-empty handling?

`Pattern.split(input, limit)` repeatedly applies `find()` to locate delimiters and emits the spans *between* matches; understanding the edge rules avoids real bugs:

- **`limit > 0`** — the pattern is applied at most `limit-1` times; the final element holds the **unsplit remainder**. Useful to cap splitting (`split(s, 2)` = "first field, rest").
- **`limit == 0`** (the common default via the one-arg form) — applied as many times as possible, but **trailing empty strings are discarded**. So `"a,b,,".split(",")` → `["a","b"]`, not `["a","b","",""]`. This silent trimming surprises people parsing fixed-width or trailing-empty data.
- **`limit < 0`** — applied as many times as possible and **trailing empties are kept**. Use a negative limit when empty trailing fields are significant.
- **Leading empty / zero-width matches** — a zero-width delimiter match at position 0 historically produced a leading empty string; modern JDKs special-case a zero-width match at the very start so `"abc".split("")` yields `["a","b","c"]` without a leading `""`. Zero-width delimiters still trigger the empty-match advance rule (step one code point forward each time).
- **`splitAsStream`** (JDK 8+) — the lazy, `Stream<String>` equivalent; it evaluates spans on demand, which can avoid materializing a large array and lets you short-circuit with `limit()`/`findFirst()`.

The internals lesson: `split` is `find()` plus span bookkeeping plus the trailing-empty policy keyed off `limit`'s **sign**. For lossless, predictable parsing of delimited data with possible empties, pass a **negative limit** (or a real parser for context-sensitive formats like CSV).

#### Q84. [Theory] What is the relationship between regex, lexers (lex/flex), and the maximal-munch rule used in compiler front-ends?

Lexical analyzers are the **canonical industrial application** of regular languages, and they reveal design choices regex APIs usually hide:

- **Each token class is a regex**; a lexer is the **union** of all token regexes compiled into a single combined automaton. Tools like `flex` build a **DFA** (via Thompson + subset construction + minimization) so lexing is strict **O(n)** with no backtracking — exactly the RE2-style guarantee, chosen because compilers must be fast and predictable.
- **Maximal munch (longest match).** At each position the lexer takes the **longest** string matching *any* token rule — `<=` lexes as one `LE` token, not `<` then `=`. This is **leftmost-longest** semantics (POSIX-style), deliberately different from Perl/Java's leftmost-*first*. The DFA implements it by running until no further transition is possible, remembering the **last accepting state** seen, then emitting that token and resetting.
- **Rule-order tie-breaking.** When two rules match the *same* longest span (e.g., a keyword vs an identifier both matching `if`), the lexer picks the **earlier-listed** rule — which is why keyword rules precede the identifier rule in a `flex` file.
- **Why not a backtracking engine?** Compilers can't risk ReDoS or super-linear lexing on adversarial source files, and they need deterministic throughput, so the DFA/automaton model wins. The single-pass "big alternation of named groups" tokenizer (shown earlier in Java) is a *manual* approximation of what flex automates — but Java's backtracking engine gives **leftmost-first**, so you must hand-order alternatives longest-first to emulate maximal munch.

The expert synthesis: lexing is where "regular language theory" stops being academic — maximal munch, DFA construction, minimization, and rule-priority tie-breaking are all *direct* consequences of the automata theory underlying regex, and they explain why production lexers deliberately avoid the very backtracking model that general-purpose regex libraries default to.

#### Q85. [Behavioral] You discover two senior engineers disagree — one insists all validation move to RE2/`re2j`, the other defends `java.util.regex` for its lookaround features. How do you drive this to a decision?

A mature answer shows **technical arbitration grounded in the threat model**, not picking a side by seniority or preference:

- **Reframe from "which engine" to "which inputs."** "The right axis isn't engine-vs-engine globally — it's **trust boundary**. I'd split validation into *trusted-author, trusted-input* paths and *untrusted* paths and decide each independently, so neither engineer has to be wholesale 'wrong.'"
- **Make the trade-off explicit and shared.** "I'd put the actual trade-offs on the table: `re2j` gives linear-time/ReDoS-immunity but drops backreferences and lookaround; `java.util.regex` keeps lookaround/atomic groups but carries ReDoS risk and no timeout. Then I'd ask the lookaround advocate *which concrete validations genuinely need lookaround* — often the password-policy lookahead idiom has a non-lookaround or code-side equivalent."
- **Let data settle it.** "For the contested patterns I'd run **ambiguity analysis + pump tests**; if a pattern is provably unambiguous and only on trusted input, `java.util.regex` is fine and the ReDoS objection doesn't apply. If any touches untrusted input, the safety argument wins by policy."
- **Decide and document.** "Outcome: `re2j` (behind a shared wrapper) is the **default for untrusted input**; `java.util.regex` is **permitted for trusted input**, gated by a CI ReDoS linter and a review checklist. I'd capture this as an ADR with the rationale and revisit triggers, so the decision outlives the argument and new engineers inherit the *why*."
- **Tend the relationship.** "I'd make sure both engineers feel heard — the safety advocate's concern becomes the default policy; the features advocate keeps a sanctioned, gated path — so it lands as a shared standard, not a winner/loser verdict."

The interviewer is probing **conflict resolution among senior peers**: can you convert a binary argument into a **policy partitioned by threat model**, use **objective evidence** (ambiguity analysis, benchmarks) instead of authority, produce a **durable artifact** (ADR + lint + wrapper), and preserve team cohesion while still making a firm call.

## 🧩 Extended Questions — Set 2: Practical scenarios, troubleshooting & coding

### 🟢 — extended

#### Q86. [Practical] Your `String.matches("\\d+")` returns false for `"  123 "` and you don't know why. How do you debug it?

The symptom is the classic `matches()` whole-string trap compounded by stray whitespace. `matches()` requires the **entire** string to satisfy the pattern, and `"  123 "` has leading/trailing spaces that `\d+` doesn't cover, so it fails.

Debugging discipline:

1. **Print the exact input** with delimiters so invisible characters show: `System.out.println("[" + s + "]");` → `[  123 ]` reveals the spaces.
2. **Decide search vs match.** If you want "contains a number," use `find()`; if you want "is exactly a number," `trim()` first or widen the pattern.

```java
String s = "  123 ";
System.out.println(s.matches("\\d+"));            // false (spaces not matched)
System.out.println(s.trim().matches("\\d+"));     // true  (trimmed first)
System.out.println(java.util.regex.Pattern.compile("\\d+")
        .matcher(s).find());                      // true  (search finds "123")
```

The general lesson: when a "should-match" fails, first confirm the *actual* bytes of the input (whitespace, BOM, non-breaking space ` `), then confirm you picked `matches`/`find`/`lookingAt` deliberately.

#### Q87. [Coding] Write a Java method that trims and normalizes internal whitespace, then validates the result is a single word of letters.

```java
import java.util.regex.Pattern;

public class WordNormalizer {
    private static final Pattern WS = Pattern.compile("\\s+");
    private static final Pattern LETTERS = Pattern.compile("\\p{L}+");

    public static String normalizeWord(String raw) {
        String cleaned = WS.matcher(raw.strip()).replaceAll(" ");
        if (!LETTERS.matcher(cleaned).matches()) {
            throw new IllegalArgumentException("Not a single letter-word: [" + cleaned + "]");
        }
        return cleaned;
    }

    public static void main(String[] args) {
        System.out.println(normalizeWord("  café  "));   // café (\p{L} accepts é)
        // normalizeWord("two words")  -> throws (space remains, not \p{L}+)
        // normalizeWord("ab12")       -> throws (digits not letters)
    }
}
```

`strip()` (JDK 11+, Unicode-aware) removes leading/trailing whitespace; `\p{L}+` with `matches()` enforces "all letters, any script." Using `\p{L}` rather than `[A-Za-z]` is what makes `café` and `日本語` pass — a common real-world requirement for international input.

#### Q88. [Practical] A teammate's pattern `"C:\new\test"` behaves oddly in Java. What's wrong and how do you fix it?

Two distinct escaping problems collide:

1. **Java string escapes fire first.** In the source literal `"C:\new\test"`, `\n` becomes a newline and `\t` becomes a tab — the string isn't even what they think before regex sees it.
2. **Backslash is also a regex metacharacter**, so even a correctly-escaped backslash needs doubling for the engine.

To match the literal text `C:\new\test`, you need four backslashes per backslash in a normal literal, or better, avoid the problem entirely:

```java
// Wrong: \n and \t are control chars; \ is a regex escape
// Pattern.compile("C:\new\test");   // matches "C:<newline>ew<tab>est" region-ish — broken

// Correct, but ugly:
Pattern p1 = Pattern.compile("C:\\\\new\\\\test");

// Better: Pattern.quote escapes the whole literal for you
Pattern p2 = Pattern.compile(Pattern.quote("C:\\new\\test"));

// Cleanest for literal search: don't use regex at all
boolean found = "path C:\\new\\test here".contains("C:\\new\\test");
```

The fix to teach: for matching **literal** text (especially Windows paths, regex-metacharacter-laden user input), use `Pattern.quote(...)` or plain `String.contains/indexOf` — hand-escaping backslashes is error-prone.

#### Q89. [Coding] Write code that extracts all hashtags from a social-media post.

```java
import java.util.*;
import java.util.regex.*;

public class HashtagExtractor {
    // # followed by a word char run; \w is Unicode-aware here for international tags
    private static final Pattern TAG =
        Pattern.compile("(?U)#(\\w+)");

    public static List<String> hashtags(String post) {
        List<String> out = new ArrayList<>();
        Matcher m = TAG.matcher(post);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    public static void main(String[] args) {
        System.out.println(hashtags("Loving #Java and #RegEx2026 — also #café"));
        // [Java, RegEx2026, café]
    }
}
```

The `(?U)` (UNICODE_CHARACTER_CLASS) flag makes `\w` accept letters from any script so `#café` is captured fully; without it, ASCII `\w` would stop at the `é`. This is a small change that prevents a real internationalization bug in user-generated content.

#### Q90. [Practical] Your regex works on regex101.com but fails in Java. What are the usual culprits?

Online testers default to other flavors (PCRE/JavaScript) and don't require Java's double-escaping, so behavior diverges. The common culprits, in order of frequency:

1. **Escaping** — the tester shows the bare pattern `\d{3}`; in Java source you must write `"\\d{3}"`. Copy-pasting without doubling backslashes is the #1 cause.
2. **Flavor-specific syntax** — `\K`, recursion `(?R)`, possessive variations, conditionals `(?(1)..)`, and arbitrary-length lookbehind exist in PCRE but **not** Java. They compile elsewhere, throw `PatternSyntaxException` here.
3. **Default flags** — JavaScript's `g`/`m` semantics or a tester's "global" toggle differ from Java's `find()` loop and `Pattern.MULTILINE`.
4. **Unicode defaults** — JS `\d` vs Java's ASCII-by-default `\d`; `\w`/`\b` Unicode behavior differs without `(?U)`.
5. **Anchoring assumptions** — a tester highlighting a substring corresponds to `find()`, not `matches()`.

Debug move: set the online tester to **Java flavor** if available, then transcribe carefully, doubling backslashes, and reproduce the exact `Pattern.compile(flags)` and `matches`/`find` call you use in code.

#### Q91. [Coding] Write a method to validate a hex color code like `#1a2B3c` or `#abc`.

```java
import java.util.regex.Pattern;

public class HexColor {
    // 3 or 6 hex digits after #; case-insensitive for a-f
    private static final Pattern HEX =
        Pattern.compile("^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$");

    public static boolean isValid(String s) {
        return HEX.matcher(s).matches();
    }

    public static void main(String[] args) {
        System.out.println(isValid("#1a2B3c")); // true
        System.out.println(isValid("#abc"));     // true (shorthand)
        System.out.println(isValid("#12"));      // false (not 3 or 6)
        System.out.println(isValid("123456"));   // false (missing #)
    }
}
```

Note the alternation order doesn't matter here because the 3- and 6-digit branches are anchored by `^...$` and mutually exclusive by length. Listing `{6}` first vs `{3}` is irrelevant with full anchoring — but in an *unanchored* search you'd put the longer alternative first (leftmost-first semantics) to avoid a short match preempting the long one.

#### Q92. [Practical] How do you make a quick reusable "tester" snippet to troubleshoot a pattern interactively?

A tiny harness that prints groups and offsets turns guesswork into evidence:

```java
import java.util.regex.*;

public class RegexProbe {
    static void probe(String regex, String input, int flags) {
        Pattern p = Pattern.compile(regex, flags);
        Matcher m = p.matcher(input);
        System.out.printf("pattern=%s  input=[%s]%n", regex, input);
        System.out.println("  matches()   = " + m.matches());
        m.reset();
        System.out.println("  lookingAt() = " + m.lookingAt());
        m.reset();
        while (m.find()) {
            System.out.printf("  find  -> [%s] at %d..%d  groups=%d%n",
                m.group(), m.start(), m.end(), m.groupCount());
            for (int i = 1; i <= m.groupCount(); i++)
                System.out.printf("      g%d=%s%n", i, m.group(i));
        }
    }
    public static void main(String[] args) {
        probe("(\\d{4})-(\\d{2})", "2026-07 and 2030-01", 0);
    }
}
```

This makes the three match modes, every match's offsets, and each group visible at once — far faster than reasoning in your head. Keeping such a probe in a scratch class is a practical habit for debugging patterns at work.

### 🟡 — extended

#### Q93. [Practical] A log line is `2026-07-01T14:23:05.123Z ERROR [svc-api] request failed id=abc123`. Extract timestamp, level, service, and id in one pass.

Use a single anchored pattern with named groups so the structure is self-documenting and you parse the whole line once:

```java
import java.util.regex.*;

public class LogParser {
    private static final Pattern LINE = Pattern.compile(
        "^(?<ts>\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z)\\s+" +
        "(?<level>[A-Z]+)\\s+" +
        "\\[(?<svc>[^\\]]+)\\]\\s+" +
        ".*?\\bid=(?<id>\\w+)\\s*$");

    public static void main(String[] args) {
        String line = "2026-07-01T14:23:05.123Z ERROR [svc-api] request failed id=abc123";
        Matcher m = LINE.matcher(line);
        if (m.matches()) {
            System.out.println("ts="    + m.group("ts"));    // 2026-07-01T14:23:05.123Z
            System.out.println("level=" + m.group("level")); // ERROR
            System.out.println("svc="   + m.group("svc"));   // svc-api
            System.out.println("id="    + m.group("id"));    // abc123
        }
    }
}
```

`[^\]]+` captures the service name without greedily eating past the `]`. The lazy `.*?` skips the free-text message up to the `id=` token. Anchoring with `^...$` plus `matches()` validates the whole line shape, so a malformed line fails fast instead of partially parsing.

#### Q94. [Coding] Write a method that converts `snake_case` to `camelCase` using regex replacement with a lambda.

```java
import java.util.regex.*;

public class SnakeToCamel {
    private static final Pattern US_LETTER = Pattern.compile("_([a-z])");

    public static String toCamel(String snake) {
        return US_LETTER.matcher(snake)
            .replaceAll(mr -> mr.group(1).toUpperCase()); // JDK 9+ functional replaceAll
    }

    public static void main(String[] args) {
        System.out.println(toCamel("user_first_name")); // userFirstName
        System.out.println(toCamel("http_status_code")); // httpStatusCode
    }
}
```

The `replaceAll(Function<MatchResult,String>)` overload (JDK 9+) lets you compute each replacement in code — uppercasing the captured letter — which is far cleaner than building a `$`-reference string for case transformation (regex replacement strings can't change case in Java, unlike some sed dialects with `\U`). For the reverse (camel→snake) you'd insert `_` before each uppercase letter and lowercase it.

#### Q95. [Practical] Your pattern `"(.*),(.*)"` against `"a,b,c"` puts `"a,b"` in group 1, not `"a"`. Explain and fix.

This is **greedy quantifier** behavior. The first `.*` is greedy, so it consumes as much as possible — grabbing `a,b` — and the engine backtracks only enough to let the single literal `,` and the second `.*` succeed, leaving `c` in group 2.

```java
Matcher m = Pattern.compile("(.*),(.*)").matcher("a,b,c");
m.matches();
m.group(1); // "a,b"   (greedy first .* took as much as it could)
m.group(2); // "c"
```

Fixes depend on intent:

```java
// Want first field only (split on FIRST comma): make group 1 lazy
Pattern.compile("(.*?),(.*)");      // g1="a", g2="b,c"

// Or exclude commas from the field explicitly (clearest):
Pattern.compile("([^,]*),(.*)");    // g1="a", g2="b,c"

// Or just use split with a limit:
"a,b,c".split(",", 2);              // ["a", "b,c"]
```

The teaching point: greedy-then-backtrack is *correct* behavior, not a bug. When you want the *smallest* capture, use a lazy quantifier or a negated character class (`[^,]*`), the latter usually being clearer and faster (no backtracking).

#### Q96. [Coding] Write a method that removes all HTML tags from a string (with the caveat that this is not a real HTML parser).

```java
import java.util.regex.Pattern;

public class TagStripper {
    // Match <...> spans; [^>]* avoids crossing into the next tag
    private static final Pattern TAG = Pattern.compile("<[^>]*>");

    public static String strip(String html) {
        return TAG.matcher(html).replaceAll("");
    }

    public static void main(String[] args) {
        System.out.println(strip("<p>Hello <b>world</b></p>"));
        // Hello world
    }
}
```

This works for *simple, well-formed, trusted* fragments. The interview caveat is essential: it breaks on `<` inside attributes/text (`<a title="a > b">`), comments, CDATA, scripts, and malformed markup — and using it to **sanitize untrusted HTML is a security hole** (XSS). For real stripping/sanitizing use Jsoup (`Jsoup.clean(html, Safelist...)`) or an HTML parser. Stating this limitation is what separates a correct answer from a dangerous one.

#### Q97. [Practical] You see `PatternSyntaxException: Unclosed group near index 12`. How do you systematically diagnose regex compile errors?

`PatternSyntaxException` is thrown at **compile** time (not match time) and carries three useful fields. Read them, don't guess:

```java
try {
    Pattern.compile("(\\d{3}(\\d{2})");   // missing close paren
} catch (PatternSyntaxException e) {
    System.out.println(e.getDescription()); // "Unclosed group"
    System.out.println(e.getIndex());        // approximate offset
    System.out.println(e.getMessage());      // includes a ^ caret pointer
}
```

Systematic approach:

1. **Read `getMessage()`** — it prints the pattern with a `^` under the offending position; the index is approximate but close.
2. **Common causes**: unbalanced `(`/`)` or `[`/`]`, a dangling quantifier (`*abc`), an unfinished `{n,m}`, a bad inline flag `(?z)`, an invalid `\p{...}` property name, or a lone backslash at end.
3. **Bisect the pattern** — compile progressively larger prefixes until it throws, isolating the construct.
4. **Watch double-escaping** — `"\d"` is a Java *compile* error (invalid escape) while `"\\d"` is fine; a stray `"\("` in source is also a Java escape error before regex even sees it.

The key distinction for juniors: a *syntax* error (`PatternSyntaxException`/compile) is different from a *logic* error (compiles fine, matches the wrong thing) — fix them with different tools (read the caret vs. use a probe harness).

#### Q98. [Coding] Write a method that finds duplicate consecutive words in text (case-insensitive) and reports their positions.

```java
import java.util.*;
import java.util.regex.*;

public class DuplicateWords {
    // (?i) case-insensitive; \1 backreference matches the same word again
    private static final Pattern DUP =
        Pattern.compile("(?i)\\b(\\w+)\\s+\\1\\b");

    public static List<String> findDuplicates(String text) {
        List<String> hits = new ArrayList<>();
        Matcher m = DUP.matcher(text);
        while (m.find()) {
            hits.add("'" + m.group(1) + "' at " + m.start());
        }
        return hits;
    }

    public static void main(String[] args) {
        System.out.println(findDuplicates("The the cat sat sat on the mat"));
        // ['The' at 0, 'sat' at 12]
    }
}
```

The backreference `\1` matches the *same text* group 1 captured, and `(?i)` makes `The the` count as a duplicate. This is a real proofreading use case. Caveat: overlapping triples (`a a a`) report only non-overlapping pairs because `find()` resumes after each match — acceptable for most editing tools.

#### Q99. [Practical] A regex-based replacement corrupts strings containing `$` or `\`. What happened and how do you fix it?

In `replaceAll`/`replaceFirst`/`appendReplacement`, the **replacement string** is itself interpreted: `$1`, `$2`, `${name}` are group references and `\` is an escape. So a replacement containing a literal `$` or `\` (e.g., a price `$5` or a Windows path) is mis-parsed — `$5` tries to reference group 5 and throws `IndexOutOfBoundsException` or inserts the wrong text.

```java
// Broken: "$" in replacement is treated as a group reference
// "price".replaceAll("price", "$5");   // throws: no group 5

// Fix 1: escape the replacement yourself ($ -> \$, \ -> \\)
String r1 = "X".replaceAll("X", "\\$5");           // literal "$5"

// Fix 2 (preferred): let the library quote it
String r2 = "X".replaceAll("X", Matcher.quoteReplacement("$5\\path")); // literal "$5\path"
```

Rule: whenever the replacement text is **computed or user-supplied**, wrap it in `Matcher.quoteReplacement(...)` so `$` and `\` are treated literally. This is the replacement-side analogue of `Pattern.quote(...)` on the pattern side, and forgetting it is a frequent production bug in templating/redaction code.

#### Q100. [Coding] Write a method that splits a camelCase or PascalCase identifier into its component words.

```java
import java.util.regex.Pattern;

public class CamelSplitter {
    // Split at boundaries: lower→Upper, and Upper→Upper-lower (acronym handling)
    private static final Pattern BOUNDARY = Pattern.compile(
        "(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

    public static String[] split(String id) {
        return BOUNDARY.split(id);
    }

    public static void main(String[] args) {
        System.out.println(String.join(" ", split("getHTTPResponseCode")));
        // get HTTP Response Code
        System.out.println(String.join(" ", split("parseXMLToJSON")));
        // parse XML To JSON
    }
}
```

This splits on **zero-width** boundaries (lookbehind + lookahead), so no characters are consumed or lost. The first alternative `(?<=[a-z0-9])(?=[A-Z])` handles normal `wordWord` transitions; the second `(?<=[A-Z])(?=[A-Z][a-z])` handles the acronym case so `HTTPResponse` becomes `HTTP` + `Response` rather than `H T T P Response`. Java's lookbehind here is bounded-length (fixed one char), so it compiles fine.

### 🟠 — extended

#### Q101. [Practical] A production service intermittently pins a CPU core to 100% and thread dumps show threads inside `Pattern$Curly.match`. Walk through diagnosis and the fix.

This fingerprint — multiple threads stuck deep inside `java.util.regex.Pattern$...match` (e.g., `Curly`, `GroupCurly`, `Branch`) with no progress — is the textbook signature of **catastrophic backtracking / ReDoS**.

Diagnosis:

1. **Confirm the fingerprint** — repeated thread dumps show the *same* stack frames inside the regex matcher; CPU is high but throughput is *low* (work isn't completing). That rules out a genuine traffic surge.
2. **Identify the pattern and input** — find which regex those threads are running and capture the triggering input (log it, or correlate with the request). Look for **nested/ambiguous quantifiers** (`(\w+\s*)+`, `(a+)+`, `(.*)*`).
3. **Reproduce offline** — feed the suspect pattern an adversarial input (`"a".repeat(40) + "!"`) and confirm time explodes super-linearly.

Fix, layered:

```java
// Vulnerable:
Pattern.compile("^(\\w+\\s*)+$");
// Fixes:
Pattern.compile("^\\w[\\w\\s]*$");          // 1. remove nesting (same language)
Pattern.compile("^(?:\\w+\\s*)++$");        // 2. possessive: no backtracking
// 3. for untrusted input, run on re2j (linear) or a watchdog-timed thread
```

Then **prevent recurrence**: bound input length, add a CI ReDoS linter, and move untrusted-input matching to `re2j`. The senior signal is the diagnose→mitigate→prevent arc, not just spotting the bad pattern.

#### Q102. [Coding] Implement a `matchWithTimeout` that aborts a backtracking match that runs too long.

Java's `Matcher` has no timeout, but you can make the input `CharSequence` throw when interrupted, so a hung match aborts cooperatively:

```java
import java.util.regex.*;

public class RegexTimeout {
    // A CharSequence that checks the deadline on each charAt the matcher reads
    static final class TimedCharSequence implements CharSequence {
        private final CharSequence s; private final long deadlineNanos;
        TimedCharSequence(CharSequence s, long deadlineNanos) { this.s = s; this.deadlineNanos = deadlineNanos; }
        public char charAt(int i) {
            if (System.nanoTime() > deadlineNanos)
                throw new RuntimeException("regex timeout");
            return s.charAt(i);
        }
        public int length() { return s.length(); }
        public CharSequence subSequence(int a, int b) {
            return new TimedCharSequence(s.subSequence(a, b), deadlineNanos);
        }
        public String toString() { return s.toString(); }
    }

    public static boolean matchWithTimeout(Pattern p, String input, long millis) {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        return p.matcher(new TimedCharSequence(input, deadline)).find();
    }

    public static void main(String[] args) {
        Pattern evil = Pattern.compile("^(a+)+$");
        try {
            matchWithTimeout(evil, "a".repeat(40) + "!", 200);
        } catch (RuntimeException e) {
            System.out.println("aborted: " + e.getMessage()); // aborted: regex timeout
        }
    }
}
```

The trick: the backtracking engine repeatedly reads characters via `charAt`, so a `charAt` that checks a deadline lets a runaway match self-abort. It's a pragmatic guard for the standard engine; the cleaner long-term answer remains `re2j` (linear time, no timeout needed). Caveat: a match phase that doesn't re-read characters won't be interrupted, so pair this with input-length caps.

#### Q103. [Practical] You must redact PII (emails, phone numbers, SSNs) from large log files streamed line by line. Design the regex strategy.

Treat it as a multi-pattern, streaming, performance-and-correctness problem:

1. **One compiled `Pattern` per PII type**, hoisted to `static final`, applied per line — don't recompile, and process line-by-line so memory stays bounded for large files.
2. **Anchor/limit work** — use word boundaries and reasonably specific patterns to cut false positives; redact with `appendReplacement` so you can compute the mask (e.g., keep last 4 of a card).
3. **Order and overlap** — apply the most specific patterns first; be aware patterns can overlap (an SSN-looking substring inside a longer number) and decide precedence explicitly.
4. **Performance** — these patterns must be **unambiguous** (no nested quantifiers) since logs can contain adversarial content; consider `re2j` if logs are untrusted at scale.

```java
import java.util.regex.Pattern;

public class PiiRedactor {
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
    private static final Pattern SSN   = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern PHONE = Pattern.compile("\\b\\d{3}[ .-]?\\d{3}[ .-]?\\d{4}\\b");

    public static String redactLine(String line) {
        String r = EMAIL.matcher(line).replaceAll("[EMAIL]");
        r = SSN.matcher(r).replaceAll("[SSN]");
        r = PHONE.matcher(r).replaceAll("[PHONE]");
        return r;
    }
}
```

The senior nuances: redaction must be **deterministic and idempotent** (re-running yields the same output), should err toward over-redaction for sensitive types, and regex is a *detector* — for high-stakes compliance you'd combine it with context (field names) and validation (Luhn for cards) to reduce false negatives/positives.

#### Q104. [Coding] Write a pattern and code to validate that a string is balanced w.r.t. one level of brackets — and explain why arbitrary nesting can't be done.

```java
import java.util.regex.Pattern;

public class OneLevelBrackets {
    // Matches strings with NO nested brackets: [^()]* outside, then optional (no-paren) groups
    private static final Pattern FLAT =
        Pattern.compile("^[^()]*(?:\\([^()]*\\)[^()]*)*$");

    public static boolean isFlatBalanced(String s) {
        return FLAT.matcher(s).matches();
    }

    public static void main(String[] args) {
        System.out.println(isFlatBalanced("a(b)c(d)e")); // true
        System.out.println(isFlatBalanced("a(b)c)"));     // false (extra close)
        System.out.println(isFlatBalanced("a(b(c)d)"));   // false (nested — beyond one level)
    }
}
```

This validates *flat* (non-nested) balanced parens. **Arbitrary nesting is impossible** with Java regex because balanced-bracket languages are **context-free, not regular** — the pumping lemma proves no finite automaton can count unbounded nesting depth. PCRE works around this with recursion `(?R)`, which Java lacks. For real nesting, use a counter/stack:

```java
static boolean balanced(String s) {
    int depth = 0;
    for (char c : s.toCharArray()) {
        if (c == '(') depth++;
        else if (c == ')' && --depth < 0) return false;
    }
    return depth == 0;
}
```

The interview point: recognizing the regular/context-free boundary and reaching for a stack is the correct senior answer.

#### Q105. [Practical] Your `\b` word-boundary pattern fails to match around accented or non-Latin characters. Diagnose and fix.

`\b` is defined relative to `\w`, and **`\w` is ASCII-only by default** in Java (`[A-Za-z0-9_]`). So `é`, `ü`, `日`, etc. count as *non-word* characters, putting spurious boundaries inside words like `café` (a boundary appears between `caf` and `é`).

```java
String s = "café society";
// ASCII \w: boundary falls between 'caf' and 'é'
System.out.println(Pattern.compile("\\bcafé\\b").matcher(s).find());        // false-ish/brittle
// Unicode-aware: \w/\b use Unicode word semantics
System.out.println(Pattern.compile("(?U)\\bcafé\\b").matcher(s).find());    // true
```

The fix is `Pattern.UNICODE_CHARACTER_CLASS` (or inline `(?U)`), which makes `\w`, `\d`, `\s`, and therefore `\b` follow Unicode definitions so accented and non-Latin letters are word characters. For grapheme-aware boundaries (emoji with modifiers, combining marks) you additionally normalize (NFC) first and may use `\b{g}` grapheme boundaries where the requirement is "user-perceived character." The general rule: any `\b`/`\w` over international text needs the Unicode flag.

#### Q106. [Coding] Write code that extracts key=value pairs from a query string, handling URL-encoded values, and explain the regex's role vs. decoding.

```java
import java.util.*;
import java.util.regex.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public class QueryParser {
    // Split into pairs on & ; each pair is key=value (value may be empty)
    private static final Pattern PAIR = Pattern.compile("([^&=]+)=([^&]*)");

    public static Map<String,String> parse(String query) {
        Map<String,String> map = new LinkedHashMap<>();
        Matcher m = PAIR.matcher(query);
        while (m.find()) {
            String k = URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
            String v = URLDecoder.decode(m.group(2), StandardCharsets.UTF_8);
            map.put(k, v);
        }
        return map;
    }

    public static void main(String[] args) {
        System.out.println(parse("name=John%20Doe&city=S%C3%A3o%20Paulo&debug="));
        // {name=John Doe, city=São Paulo, debug=}
    }
}
```

The regex's job is purely **tokenizing** the structure (`key=value` separated by `&`); it deliberately does **not** decode percent-escapes — that's `URLDecoder`'s job. Mixing the two (trying to handle `%20` inside the regex) is a classic mistake: percent-decoding is a transformation, not a matching concern. This separation (split structurally, then decode each token) is the clean, correct pattern for query strings, headers, and similar encoded formats.

#### Q107. [Practical] A pattern that worked for years suddenly mismatches after a JDK upgrade or input-source change. How do you investigate a regression?

Regex regressions are usually about a changed *assumption*, not the engine. Investigate methodically:

1. **Diff the inputs, not just the code.** Capture a now-failing input and a previously-passing one; print them with delimiters and hex/codepoint dumps to catch invisible changes (a new BOM `﻿`, non-breaking spaces ` `, CRLF vs LF, full-width digits, or NFD vs NFC normalization from a new data source).
2. **Check JDK behavior changes.** Across JDK versions, regex behavior has shifted in documented ways — e.g., `split("")` no longer emits a leading empty string, Unicode data updates change `\p{...}`/`\w` membership as the bundled Unicode version advances, and some `\b{g}`/grapheme handling was added/refined. Read the release notes for `java.util.regex` and Unicode version bumps.
3. **Pin and bisect.** Reproduce on the old and new JDK with the *same* input to isolate engine vs input. A unit test with the captured input makes the regression concrete.
4. **Normalize defensively.** If the input source changed encoding/normalization, normalize to NFC and a known charset *before* matching so the pattern sees consistent code points.

The discipline: regex is deterministic, so a regression means *something the pattern depended on changed* — usually the bytes of the input or the Unicode/JDK semantics — and the fix is to make that dependency explicit (normalize, pin Unicode expectations, add the captured input as a regression test).

#### Q108. [Coding] Write a method that converts a glob pattern (`*.txt`, `data?.csv`) into a safe Java regex.

```java
import java.util.regex.*;

public class GlobToRegex {
    public static Pattern glob(String glob) {
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> sb.append("[^/]*");   // * = any run except path separator
                case '?' -> sb.append("[^/]");    // ? = exactly one non-separator
                case '.' , '\\', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|'
                         -> sb.append('\\').append(c); // escape regex metachars
                default  -> sb.append(c);
            }
        }
        return Pattern.compile(sb.append('$').toString());
    }

    public static void main(String[] args) {
        System.out.println(glob("*.txt").matcher("notes.txt").matches());  // true
        System.out.println(glob("data?.csv").matcher("data1.csv").matches()); // true
        System.out.println(glob("*.txt").matcher("a/b.txt").matches());    // false (no cross /)
    }
}
```

The critical safety detail is **escaping every regex metacharacter** in the literal parts (especially `.`), so a glob like `*.txt` doesn't let the `.` match any character. Translating `*`→`[^/]*` rather than `.*` keeps wildcards from crossing path separators, matching typical filesystem-glob semantics. Building the regex character-by-character (rather than string-replacing) avoids order-of-replacement bugs where an inserted regex piece gets re-escaped.

### 🔴 — extended

#### Q109. [Practical] You must accept user-supplied search patterns in a SaaS product. Design the end-to-end safe-regex pipeline and justify each control.

Treat user regex as **untrusted code executing in your process**. A defense-in-depth pipeline:

1. **Engine: `re2j` (linear-time).** This eliminates the *entire ReDoS class* by construction rather than mitigating instances — the single most important control. Document the lost features (backreferences, lookaround); user search patterns rarely need them.
2. **Submit-time validation.** Cap **pattern length**, reject pathological `{n,m}` bounds, and (if you must allow the backtracking engine for some tier) statically reject nested quantifiers / ambiguous alternation via AST analysis.
3. **Compile cache with bounds.** LRU-cache compiled patterns keyed by string with a size cap — compilation itself is a DoS vector (someone submitting millions of distinct huge patterns).
4. **Per-request resource limits.** Cap **input length**, number of matches, and wall-clock via a deadline; run on a bounded executor so one tenant can't starve others.
5. **Isolation & fairness.** Per-tenant rate limits and quotas; a kill-switch to disable a specific pathological pattern hot.
6. **Observability.** Emit per-pattern match-time and per-tenant CPU metrics; alert on outliers; sample slow patterns for offline ambiguity analysis.

```
submit → validate(size/features) → compile(re2j, LRU-cached)
       → match(input cap + deadline, bounded pool, per-tenant quota)
       → metrics + kill-switch
```

Justification framing: **prefer eliminating the vulnerability class (RE2) over mitigating instances (timeouts)**, but layer both — plus tenancy fairness, because at SaaS scale the threat is not just a single hang but **noisy-neighbor CPU exhaustion**.

#### Q110. [Theory] Demonstrate, with a worked trace, why `(a|ab)(c|bcd)(d*)` exhibits leftmost-first surprises in Java, and how you'd make it robust.

Java is **leftmost-first**: each alternation takes the **first** branch that allows the *overall* match to eventually succeed, not the longest. Trace `(a|ab)(c|bcd)(d*)` on input `"abcd"`:

- Group1 tries `a` first → matches `"a"`, cursor at index 1 (`"bcd"` remains).
- Group2 tries `c` → fails (next char is `b`); tries `bcd` → matches `"bcd"`, cursor at end.
- Group3 `d*` matches empty.
- Overall: **match**, with g1=`a`, g2=`bcd`, g3=``.

But a human often *expects* g1=`ab`, g2=`c`, g3=`d`. Both are valid parses of `"abcd"`; Java returns the **first** one found by trying `a` before `ab`. A POSIX leftmost-longest engine could prefer a different (longest-leading) decomposition. The surprise is that **alternative order changes which capture you get**, even when the overall match status is the same.

Making it robust:

- **Order alternatives most-specific/longest first** when you care which branch wins: `(ab|a)` instead of `(a|ab)`.
- **Make alternatives disjoint** so only one can match at a position (no overlap to be ambiguous about).
- **Anchor and avoid overlapping branches** so there's a single parse.
- If you genuinely need longest-match semantics, use an engine with a POSIX mode (RE2 supports it) rather than fighting leftmost-first.

The expert point: ambiguous grammars + leftmost-first = non-obvious captures; the fix is to remove the ambiguity (ordering/disjointness/anchoring), not to add more quantifiers.

#### Q111. [Practical] Architect a regex-rule engine where non-engineers author hundreds of patterns (e.g., content moderation). What are the hard problems and your controls?

The hard problems are **safety at scale**, **authoring quality**, and **maintainability of a large rule corpus** — not writing any single pattern.

Controls, grouped:

- **Safety (the dominant concern).** Run all author-supplied rules on **`re2j`** so no rule can ReDoS the moderation pipeline (which often sits on the hot path of every message). Bound input length and total rules evaluated per item. Pre-compile all rules into a **combined automaton** where possible so evaluating N rules is closer to one linear pass than N separate scans.
- **Authoring quality.** Provide a **constrained authoring UI** (test box with sample inputs, live match preview, a linter that warns on overly broad patterns like `.*` and on rules with no anchors). Require each rule to ship with **positive and negative test cases**; reject rules that match the empty string or an obviously huge fraction of traffic.
- **Governance & lifecycle.** Version rules, require review/approval, support **staged rollout** (shadow mode that logs would-be matches without acting) and instant rollback/kill-switch per rule. Track each rule's **hit rate and false-positive feedback** so dead or harmful rules are pruned.
- **Performance observability.** Per-rule timing and match-count metrics; alert on a rule that suddenly matches far more (corpus drift or an attack); cap total per-item evaluation time.
- **Internationalization correctness.** Normalize input (NFC) and configure Unicode-aware classes centrally so authors don't each have to remember `(?U)`; consider confusable-folding for evasion-resistant moderation.

The architect synthesis: you're building a **safe, observable, governed platform around an unsafe-by-default primitive** — the engine choice (`re2j`) removes the catastrophic risk, and the surrounding lint/test/rollout/metrics machinery turns hundreds of non-expert authors into a maintainable, auditable rule set rather than a liability.

#### Q112. [Theory] Explain how you'd build a quantitative ReDoS test (a "pump test") into CI, including what to measure and the statistical pitfalls.

A **pump test** empirically detects super-linear matching by feeding a pattern increasingly long attack strings and observing how match time grows — complementary to static ambiguity analysis (which can have false positives/negatives).

Construction:

1. **Synthesize attack inputs.** For each candidate pattern, derive a repeating "pump" prefix from the ambiguous sub-expression (e.g., for `(a+)+$`, pump `a` and append a failing suffix `!`). Generate inputs of size `n = 10, 20, 40, 80, ...`.
2. **Measure match time** at each `n` (use a monotonic clock; `System.nanoTime`).
3. **Fit the growth.** Linear/O(n) is safe; **quadratic** (time roughly ×4 when `n` doubles) signals polynomial ambiguity; **exponential** (time explodes — ×1000 per +10 chars) signals catastrophic backtracking. Assert an upper bound (e.g., "match of `n=200` must complete < X ms").

Statistical pitfalls to control for:

- **JIT warm-up.** The first runs are interpreted/compiling; discard warm-up iterations or use JMH so you measure steady-state, not classloading/JIT artifacts.
- **GC and OS noise.** Run multiple trials, report **median/percentiles**, not a single sample; pin to avoid scheduler jitter where possible.
- **Coarse clocks / tiny inputs.** Sub-microsecond matches are dominated by timer resolution — ensure inputs are large enough that the signal exceeds noise, but **cap total time** so a truly exponential pattern doesn't hang the build (run the match itself under a timeout and treat timeout-at-small-`n` as a failure).
- **False confidence from one input.** A pattern can be linear on your pump string but quadratic on another shape; combine pump tests with **static ambiguity analysis** (`recheck`/RXXR2) which searches for the worst-case witness rather than guessing it.

The expert framing: CI should layer **fast heuristic lint** (every commit), **static ambiguity analysis** (per PR), and **bounded empirical pump tests** (per PR, time-capped) — and treat any pattern touching untrusted input that isn't provably linear as requiring `re2j` rather than relying on the pump test passing.

#### Q113. [Behavioral] A regex you approved in code review later caused a customer-facing outage. How do you handle the aftermath technically and with the team?

A mature answer separates **immediate response**, **systemic prevention**, and **culture/ownership** — and explicitly takes shared responsibility as the approver.

- **Own it without blame-shifting.** "As the approving reviewer I share ownership — I'd say so plainly. The goal of the postmortem is a *blameless* root-cause analysis, not finding a person to fault; the engineer who wrote it and I both operated within a process that let the risk through."
- **Stabilize first.** "Roll back or hotfix the pattern (possessive/atomic or `re2j`), bound input length, and confirm recovery with metrics before anything else. Communicate status to support/customers."
- **Blameless postmortem with a systemic lens.** "Root cause isn't 'a bad regex' — it's 'our review and CI didn't catch a ReDoS-prone pattern on an untrusted path.' Fix the *system*: add a CI ReDoS linter + pump test, a review checklist item ('is input trusted? could this backtrack? should this be `re2j`?'), and default untrusted-input matching to the safe engine via a shared wrapper."
- **Close the loop on detection.** "Add the triggering input as a regression test, add alerting on the thread-stuck-in-matcher fingerprint, and a per-pattern match-time metric so the *next* one is caught in minutes, not after an outage."
- **Team and trust.** "Run a short brown-bag on ReDoS seeded by this incident so the lesson scales beyond the two of us, and frame the new guardrails as making the *whole team* faster/safer, not as punishment."

The interviewer is checking for **accountability without scapegoating**, the instinct to fix the **process and tooling** (not just the instance), and the leadership maturity to turn an incident into durable guardrails and a stronger, blame-free engineering culture.

#### Q114. [Practical] You need to match across a multi-gigabyte file or a stream without loading it into memory. What are the regex-specific challenges and approaches?

`java.util.regex` operates on an in-memory `CharSequence`, so naively you'd need the whole input in RAM — impossible for multi-GB. The regex-specific challenges and mitigations:

1. **Matches spanning chunk boundaries.** If you read fixed-size chunks, a match could straddle two chunks and be missed. Mitigation: read in **overlapping windows** sized to the **maximum possible match length** (you must be able to bound it — patterns with unbounded `.*` make this hard), carrying a tail of the previous chunk forward. For line-oriented data, **split on line boundaries** (`BufferedReader.lines()`) and match per line, which sidesteps spanning entirely when matches can't cross newlines.
2. **`Matcher.region` for windows.** Match within a region of a buffer and use `useTransparentBounds(true)` + `useAnchoringBounds(false)` so lookaround/`\b` near the window edge see the carried-over context rather than firing at an artificial cut.
3. **Avoid backtracking blowups on huge input.** Over gigabytes, even *polynomial* (quadratic) ambiguity is fatal; insist on **unambiguous, anchored** patterns or `re2j`, whose **streaming DFA** model is designed for linear single-pass scanning and is the right tool for true stream matching.
4. **Encoding/normalization at scale.** Decode with an explicit charset as you stream; if normalization matters, normalize per logical unit (line/record), not the whole file.

The senior synthesis: for stream/large-file matching, prefer **record/line-oriented processing** (bound the match window naturally) or a **streaming linear-time engine (`re2j`)**; reserve `java.util.regex` for in-memory, bounded inputs, and never assume a pattern with unbounded quantifiers can be safely windowed.

#### Q115. [Theory] Compare strategies for case-insensitive *and* accent-insensitive matching for a global search feature, and the correctness/performance trade-offs.

"Find `cafe` and match `Café`, `CAFÉ`, `cafe`" requires folding **case** *and* **diacritics** — two separate normalizations with distinct correctness traps.

Strategies:

1. **Regex flags only (`CASE_INSENSITIVE | UNICODE_CASE`).** Handles case (including Unicode case like `ß`/`SS` with `UNICODE_CASE`) but **not** accents — `café` ≠ `cafe`. Insufficient alone for accent-insensitivity.
2. **Normalize + strip diacritics, then match.** Decompose to **NFD** (`Normalizer.Form.NFD`) so accents become separate combining marks, remove the marks (`\p{M}`), and compare the folded forms. Combined with case folding this gives accent- and case-insensitive matching:

```java
static String fold(String s) {
    String d = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD);
    return d.replaceAll("\\p{M}+", "")
            .toLowerCase(java.util.Locale.ROOT);   // fixed locale!
}
// fold("Café") == fold("cafe") -> "cafe"
```

   Then match on `fold(text)` against `fold(query)`.

3. **Collator / ICU.** `java.text.Collator` with a low **strength** (`Collator.PRIMARY` ignores case and accents) does locale-correct insensitive *comparison*; ICU4J offers transliteration and robust folding. More correct for sorting/equality than regex stripping, but it's a comparison API, not a substring-search engine.

Trade-offs:

- **Correctness:** naive diacritic-stripping breaks languages where an accented letter is a **distinct letter** (e.g., Swedish `å`/`ä`/`ö`, Turkish dotted/dotless i) — `Locale.ROOT` lowercasing and blanket mark-removal can be *wrong* there; locale-aware collation is safer. Also normalize the **query and the corpus the same way**.
- **Performance:** folding allocates new strings; for a search index you **fold once at index time**, store the folded form, and fold only the (small) query at search time — never fold the whole corpus per query. Regex flags are cheap but incomplete; per-query full-corpus normalization is the performance trap to avoid.

The expert framing: case-insensitivity is a flag; **accent-insensitivity is a normalization pipeline** (NFD → strip `\p{M}` → locale-aware casefold), best done **once at index time**, and locale-correct collation beats blanket stripping when linguistic correctness matters.

#### Q116. [Coding] Implement a streaming, bounded-memory grep-like matcher over a `Reader` that reports line numbers, and discuss its limits.

```java
import java.io.*;
import java.util.regex.*;

public class StreamGrep {
    // Reports "lineNumber: line" for each line containing a match. Bounded memory: one line at a time.
    public static void grep(Reader in, Pattern p, PrintStream out) throws IOException {
        try (BufferedReader br = new BufferedReader(in)) {
            String line; int n = 0;
            Matcher m = p.matcher("");          // reuse one Matcher
            while ((line = br.readLine()) != null) {
                n++;
                m.reset(line);                  // rebind to the current line
                if (m.find()) out.println(n + ": " + line);
            }
        }
    }

    public static void main(String[] args) throws IOException {
        String data = "alpha\nbeta error 1\ngamma\ndelta error 2\n";
        grep(new StringReader(data), Pattern.compile("error \\d+"), System.out);
        // 2: beta error 1
        // 4: delta error 2
    }
}
```

This is genuinely bounded-memory: it holds **one line at a time**, reuses a single `Matcher` via `reset()`, and shares the compiled `Pattern`. Limits to call out: it only finds matches that **fit within a single line** — a pattern intended to span newlines (multi-line records) would be missed because the line boundary breaks the match window; for that you'd need overlapping multi-line buffering or a record-aware reader. It also assumes the chosen charset is correct for the stream, and relies on the pattern being **unambiguous** so no single pathological line can ReDoS the scan (use `re2j` if the input or pattern is untrusted).

#### Q117. [Behavioral] You're mentoring a junior who reaches for regex on every string problem, including parsing JSON. How do you coach them?

The coaching goal is **judgment about tool fit**, not discouraging regex — delivered constructively so they keep their enthusiasm.

- **Affirm the strength, redirect the application.** "Regex is a great instinct for *regular* problems — tokenizing, validation-as-typo-filter, find/replace. I'd praise that they reach for a declarative tool, then introduce the boundary: some structures are beyond what regex can do *by definition*."
- **Teach the concept, not just the rule.** "Rather than 'don't parse JSON with regex,' I'd explain *why*: JSON/HTML/XML are **nested (context-free)**, and regex matches **regular** languages — the pumping lemma proves a finite automaton can't track unbounded nesting. Once they see the categorical limit, the rule becomes obvious instead of arbitrary."
- **Make the right path easy.** "Show the better tool in *their* code: Jackson for JSON, Jsoup for HTML, `URI` for URLs, a CSV library for CSV. Pair on converting one of their regex attempts to a parser so they feel the payoff (correctness on edge cases, readability)."
- **Surface the hidden risks.** "Walk through a ReDoS example so they learn that an *over*-clever regex on untrusted input is a security/perf liability, and that 'use a library' is often the senior move, not a cop-out."
- **Give a heuristic they can self-apply.** "A simple checklist: *Is the structure nested or context-sensitive? Is the input untrusted? Is there a well-known parser?* If any 'yes,' prefer the parser. Regex for the regular, parsers for the nested."
- **Encourage, don't shame.** "Frame it as leveling up — 'you've mastered the hammer; here's when to pick a different tool' — and invite them to teach it back, which cements the judgment."

The interviewer is assessing **mentorship and the ability to teach judgment**: turning a habit into a principled decision framework, grounding it in real theory (regular vs context-free) and real risk (ReDoS), making the better path easy, and doing it in a way that builds the junior's confidence rather than deflating it.

## ✅ Key Takeaways

- **Regex describes patterns; choose `matches` (whole-string) vs `find` (search) vs `replace` deliberately** — most "it doesn't match" bugs are `matches()` used where `find()` was meant.
- **Greedy backtracks, lazy minimizes, possessive never gives back.** Use lazy for smallest matches; use possessive/atomic to kill useless backtracking and prevent ReDoS.
- **Java uses a backtracking NFA with no built-in timeout** — nested quantifiers over untrusted input are a denial-of-service vector. For untrusted patterns/inputs, prefer `re2j` (linear time) or sandbox with a watchdog.
- **`Pattern` is immutable/thread-safe; `Matcher` is not.** Compile patterns once as `static final`; create/reset matchers per thread.
- **Mind the double escaping** (`"\\d"`), the dot's newline behavior (`DOTALL`), `^/$` vs MULTILINE, and ASCII vs Unicode (`\d`, `\w`, `CASE_INSENSITIVE` need Unicode flags for international text).
- **Use named groups, `(?x)` comments, and `\p{...}` properties** for readable, internationalization-correct patterns.
- **Don't use regex for nested/context-sensitive formats** (HTML, JSON, CSV, full email/URL grammars) or numeric range checks — reach for a parser/library, and treat regex validation as a typo filter, not proof.

## ⚠️ Common Pitfalls

- Using `matches()` to "search" — it requires the entire string to match.
- Forgetting to escape `\` twice in Java string literals (`"\d"` won't compile; `"\\d"` is correct).
- Assuming `.` crosses newlines (needs DOTALL) or that `^/$` match per line (needs MULTILINE).
- Anchoring an alternation without grouping: `^a|b$` ≠ `^(?:a|b)$`.
- Nested quantifiers like `(a+)+` / `(.*)*` over untrusted input — catastrophic backtracking / ReDoS.
- Possessive quantifiers swallowing characters a later part of the pattern needs (`.*+a` always fails).
- Treating `\d`/`\w`/`CASE_INSENSITIVE` as Unicode-aware by default (they're ASCII unless you add the Unicode flags).
- Sharing one `Matcher` across threads (it's mutable, not thread-safe).
- Recompiling a regex on every call via `String.matches`/`replaceAll` in hot paths instead of a cached `Pattern`.
- Trusting a hand-rolled email/URL/IP regex for security decisions instead of a parser plus canonicalization.

## 📚 Further Reading

- Jeffrey Friedl, *Mastering Regular Expressions* (3rd ed.) — the definitive book on engines and optimization.
- Russ Cox, "Regular Expression Matching Can Be Simple And Fast" (swtch.com) — the RE2/Thompson-NFA series.
- Java `java.util.regex.Pattern` Javadoc — the authoritative reference for Java's supported syntax and flags.
- OWASP — "Regular expression Denial of Service (ReDoS)" and the ReDoS cheat sheet.
- `com.google.re2j` — linear-time RE2 port for the JVM (safe for untrusted patterns).
- Unicode Technical Standard #18 (Unicode Regular Expressions) and `java.text.Normalizer` docs.
- regex101.com / regexr.com — interactive testers with match-debugging and step-through backtracking visualization.
