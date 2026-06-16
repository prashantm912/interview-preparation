# How to Use This Guide & Study Plan

[← Back to master index](../README.md)

> **TL;DR** — This is a long-haul, self-paced software-engineering interview-prep guide. Pick a target role, pick a timeline (12-week sprint or 6-month marathon), and follow the weekly template. Rotate four skill tracks every week — **Theory (CS fundamentals)**, **DSA (coding)**, **System Design**, and **Behavioral** — and reinforce with **spaced repetition** plus a steady **mock-interview cadence**. Don't grind one track to exhaustion; interleave them.

---

## 1. Executive Summary

Most candidates fail technical interviews not because they lack intelligence, but because they prepare *unevenly* — they grind 400 LeetCode problems and then freeze on a system-design prompt, or they can design a distributed cache but can't articulate why they left their last job. Modern interview loops (FAANG, well-funded startups, and serious mid-size companies in 2026) test **four orthogonal dimensions**, and your offer probability is roughly the product, not the sum, of your scores across them.

This guide is structured so you can build all four dimensions in parallel over **6–12 months**, or compress them into a focused **12-week** push if you have a deadline. It is deliberately *role-aware*: a backend engineer, a full-stack engineer, a senior/staff candidate, and an architect are graded on different weightings of the same four tracks. The plans below tell you what to emphasize.

The guide is **reference material plus a schedule** — not a course you watch passively. You read a topic, immediately practice it, then revisit it on a spaced cadence so it survives until interview day.

### The Four Tracks

| Track | What it tests | Primary signal interviewers look for |
|-------|---------------|--------------------------------------|
| **Theory** | CS fundamentals: data structures, complexity, OS, networking, concurrency, databases | Do you understand *why*, not just *what*? |
| **DSA** | Coding: arrays, trees, graphs, DP, problem-solving under time pressure | Can you turn a vague problem into correct, efficient code while talking? |
| **System Design** | Architecting scalable, reliable systems | Can you reason about tradeoffs at scale and communicate them? |
| **Behavioral** | Leadership, collaboration, judgment, impact | Are you someone the team wants to work with, and can you tell a coherent story? |

---

## 2. Folder Structure & How to Navigate

The guide is organized as numbered top-level folders so they sort naturally in any file explorer or Git host. Read them roughly in order on a first pass, then treat them as a reference library.

```
interview-preparation/
├── README.md                      ← master index (start here)
├── 00-getting-started/            ← THIS folder: orientation + study plans
│   └── how-to-use.md              ← the file you're reading
├── 01-cs-fundamentals/            ← Theory track
├── 02-data-structures-algorithms/ ← DSA track (patterns + problem sets)
├── 03-system-design/              ← System Design track
├── 04-behavioral/                 ← Behavioral track (STAR stories)
├── 05-languages-and-frameworks/   ← language-specific deep dives
├── 06-mock-interviews/            ← question banks + self-eval rubrics
└── 99-resources/                  ← books, courses, platforms, cheat sheets
```

**Navigation conventions used throughout the guide:**

- Every document opens with a `[← Back to master index]` link so you can always jump home.
- Topics are tagged with an **experience level** badge (see §3) so you can skim past or zoom into content that matches where you are.
- Code examples are language-agnostic in the core tracks; language-specific notes live in `05-languages-and-frameworks/`.
- Each DSA pattern page lists **representative problems** with difficulty so you can build a problem set without guessing.

> **Concrete example:** Preparing for a backend role and weak on databases? Open `01-cs-fundamentals/databases.md` for theory, then `03-system-design/data-storage.md` for applied tradeoffs (SQL vs. NoSQL, sharding, replication), then drill the relevant problems in `02-data-structures-algorithms/`. That single thread crosses three folders — the cross-links connect them.

---

## 3. How the 4 Experience Levels Work

Content is tagged with one of four levels. They are **cumulative** — higher levels assume you've absorbed the lower ones. Use them to filter: don't waste a junior's time on staff-level org-design content, and don't let a staff candidate skip the behavioral depth that now dominates their loop.

| Level | Badge | Typical title | Years | Emphasis shift |
|-------|-------|---------------|-------|----------------|
| **L1 — Foundational** | 🟢 | Intern / New Grad / Junior (SWE I) | 0–2 | Heavy DSA + theory; behavioral is "tell me about a project" |
| **L2 — Intermediate** | 🔵 | Mid-level (SWE II) | 2–5 | DSA still core; system design enters; behavioral about ownership |
| **L3 — Advanced** | 🟠 | Senior (SWE III) | 5–9 | System design co-equal with DSA; behavioral about influence & mentorship |
| **L4 — Expert** | 🔴 | Staff / Principal / Architect | 9+ | Design + behavioral dominate; DSA is a sanity check, not the bar |

**How to use the badges:**

- **Find your floor.** Whatever you're interviewing for, you must be solid one level *below* it. A senior candidate who can't fluently do L2 DSA will get dinged.
- **Stretch one level up.** Reading L4 system-design content as an L3 candidate is the single best way to stand out — staff-level reasoning in a senior loop reads as "ready for promotion."
- **Behavioral scales hardest.** The same prompt ("tell me about a conflict") expects a one-team answer at L1 and a cross-org, multi-stakeholder answer at L4. The behavioral track gives level-specific story rubrics.

---

## 4. Using the Guide Over 6–12 Months

A long runway is a gift — it lets you build **durable** knowledge instead of crammed knowledge. The failure mode of long prep is drift: you study hard for three weeks, life intervenes, and you return having forgotten everything. Beat drift with three habits:

1. **Maintain a study log.** A single `study-log.md` (or a Notion/Obsidian page) with a one-line daily entry: what you covered, what felt shaky. This is your spaced-repetition backbone — anything "shaky" gets re-queued.
2. **Build a personal problem journal.** For every DSA problem you solve, log the *pattern*, the *insight that unlocked it*, and your *time-to-solve*. After ~150 problems you'll see your weak patterns statistically, not by gut feel.
3. **Run a monthly diagnostic.** Once a month, do a timed full mock (one coding + one design + a few behavioral). Score yourself with the rubrics in `06-mock-interviews/`. Your trend line matters more than any single score.

**Macro phases for a 6–12 month plan:**

| Phase | Months | Goal |
|-------|--------|------|
| **Foundation** | 1–3 | Close theory gaps; learn every core DSA *pattern* (not problem count); read system-design primitives |
| **Fluency** | 4–7 | Volume DSA practice (medium-heavy); design full systems end-to-end; draft all behavioral stories |
| **Sharpening** | 8–10 | Timed mocks; harder problems; refine stories from mock feedback; mock with real humans |
| **Peak & maintain** | 11–12 | Light daily reps to stay warm; target-company-specific prep; rest before loops |

---

## 5. Suggested Study Plans

### 5a. The 12-Week Intensive Plan (~15–20 hrs/week)

For candidates with a hard deadline and a reasonable baseline. Each week interleaves all four tracks.

| Week | Theory | DSA focus | System Design | Behavioral |
|------|--------|-----------|---------------|------------|
| 1 | Big-O, memory model | Arrays, Hashing, Two Pointers | Intro: scaling basics, latency/throughput | Draft career timeline |
| 2 | Recursion, stacks | Sliding Window, Stack | Load balancing, caching | 2 STAR stories (conflict, failure) |
| 3 | Trees, BST theory | Binary Search, Linked Lists | Databases: SQL vs NoSQL, indexing | 2 STAR stories (leadership, impact) |
| 4 | Hashing internals | Trees, BFS/DFS | Sharding, replication, consistency | "Why this company / why leaving" |
| 5 | Graph theory | Graphs (traversal, topo sort) | Message queues, async processing | Mock behavioral #1 |
| 6 | DP foundations | Dynamic Programming I | Design a URL shortener (end-to-end) | Refine stories from feedback |
| 7 | Concurrency, threads | DP II, Greedy | Design a news feed / timeline | Story for "ambiguity / no data" |
| 8 | Networking, HTTP, TLS | Heaps, Intervals | Design a rate limiter, design chat | Mock behavioral #2 |
| 9 | OS: scheduling, locks | Backtracking, Tries | Design a video/streaming service | Salary & negotiation prep |
| 10 | Distributed systems basics | Mixed hard set | Design a search / autocomplete | Mock behavioral #3 |
| 11 | Review weak theory | Timed mixed mocks | Mock design interviews (×2) | Story polish + delivery |
| 12 | Light review | Light daily warmups | One final full mock loop | Rest, logistics, mindset |

> **Rule for the 12-week plan:** never let any single day be 100% one track. A typical day = 1 DSA problem set (60–90 min) + one theory or design read (45 min) + one story rehearsal (15 min). Interleaving beats blocking — it's the single most evidence-backed study tactic here.

### 5b. The 6-Month Steady Plan (~8–10 hrs/week)

For employed candidates preparing while working. Same content, more breathing room, deeper retention.

| Month | Primary build | DSA target | Design target | Behavioral |
|-------|---------------|-----------|---------------|------------|
| **1** | Theory + DSA patterns | ~30 easy/med, learn all patterns | Read all primitives | Brain-dump every story candidate |
| **2** | DSA fluency | ~40 medium | 2 full designs | Write 6 polished STAR stories |
| **3** | DSA + design depth | ~40 medium, start hard | 3 full designs | Mock behavioral #1, revise |
| **4** | Design-heavy | ~30 mixed, weak patterns | 4 designs + deep dives | Mock behavioral #2 |
| **5** | Mocks begin | Timed mocks weekly | Mock design weekly | Negotiation + company research |
| **6** | Peak & maintain | Light daily reps | Final mocks | Final delivery polish, rest |

> **Weekly rhythm for the 6-month plan:** 3 DSA sessions, 1 design session, 1 theory session, plus 1 short behavioral rehearsal. One weekend "long session" (2–3 hrs) for a full system design or a timed mock.

### 5c. Topic-Priority Order by Target Role

Same four tracks, different weighting. Spend your *marginal* hour on the highest-priority track for your role.

| Priority | Backend | Full-Stack | Senior / Staff | Architect |
|----------|---------|-----------|----------------|-----------|
| 1 | DSA + data modeling | DSA (broad, not deep) | System Design | System Design (deep) |
| 2 | System Design (data/infra) | Frontend + API design | Behavioral (influence) | Behavioral (org-level) |
| 3 | Concurrency & databases | System Design (full-stack) | DSA (medium fluency) | Tradeoff articulation / RFCs |
| 4 | API & service design | Behavioral | Theory (distributed systems) | Cross-team & cost tradeoffs |
| 5 | Behavioral | Theory (web fundamentals) | Mentorship stories | DSA (sanity check only) |

**Role notes:**

- **Backend:** databases, queues, caching, and consistency dominate design rounds. Know B-trees vs. LSM-trees, isolation levels, and idempotency cold.
- **Full-stack:** breadth wins. You'll see lighter DSA but a frontend round (DOM, rendering, state, accessibility) plus API design. Don't neglect either end.
- **Senior/Staff:** the bar shifts from "can you code" to "can you scope ambiguity and drive others." Design and behavioral are co-dominant; DSA is pass/fail, not differentiating.
- **Architect:** you're selling *judgment*. Practice whiteboarding a system *and defending every tradeoff* — cost, reliability, team capability, migration path. Behavioral is about organizational impact, not individual heroics.

---

## 6. Combining Theory + DSA + System Design + Behavioral

These tracks reinforce each other; studied together they compound.

- **Theory → DSA.** You can't recognize a graph problem without graph theory. Read the theory page for a pattern *before* drilling its problems. Example: read tree-balancing theory, then solve BST validation and LCA problems the same day.
- **Theory → System Design.** Indexing theory makes "why is this query slow at scale?" obvious. Concurrency theory makes "how do you prevent double-charging?" answerable. Pull the relevant `01-cs-fundamentals/` page when a design topic stumps you.
- **DSA → System Design.** Designing a rate limiter *is* a heap/queue problem dressed up. Designing autocomplete *is* a trie. The same primitives recur at a larger scale.
- **Everything → Behavioral.** Your strongest STAR stories should come from real technical work. "Tell me about a hard technical decision" is a behavioral question that you answer with system-design vocabulary. Mine your own projects for these.

**A worked example of combining tracks in one study thread — "Rate Limiter":**

1. *Theory:* read token-bucket vs. leaky-bucket, and atomicity (`01-cs-fundamentals/concurrency.md`).
2. *DSA:* implement a sliding-window counter and a token bucket in code (`02-data-structures-algorithms/`).
3. *System Design:* design a *distributed* rate limiter — where does state live, how do you handle clock skew, what's the failure mode? (`03-system-design/`).
4. *Behavioral:* prepare a story about a time you protected a system under load. The technical depth makes the story credible.

One topic, four tracks, one afternoon — and it sticks because each angle reinforces the others.

---

## 7. Spaced Repetition & Mock-Interview Cadence

### Spaced repetition

Cramming produces knowledge that evaporates by interview day. Spaced repetition produces knowledge that lasts. Use an expanding-interval schedule for anything you want to *retain* (theory facts, DSA patterns, your own story beats):

| Touch | When | What you do |
|-------|------|-------------|
| 1st | Day 0 | Learn it; solve it once |
| 2nd | Day 1 | Re-derive from memory (no notes) |
| 3rd | Day 3 | Re-solve / re-explain aloud |
| 4th | Day 7 | Re-solve a *variant* |
| 5th | Day 21 | Final retention check |

Tools: **Anki** (with a CS-fundamentals or "Grind 75 patterns" deck), or a simple spaced-review column in your study log. The rule: **if you couldn't re-derive it from scratch, it isn't learned yet** — re-queue it. Track patterns, not individual problems; recognizing "this is a monotonic-stack problem" is the transferable skill.

### Mock-interview cadence

Mocks are where preparation becomes performance. Talking while coding, managing a 45-minute clock, and recovering from a wrong turn are *separate skills* from solving problems alone.

| Phase | Mock frequency | Format |
|-------|----------------|--------|
| Foundation (early) | Optional / 1× total | Self-recorded; just get comfortable talking aloud |
| Fluency (mid) | 1 every 2 weeks | Peer or platform; mixed coding + design |
| Sharpening (late) | 1–2 per week | Real humans; simulate the full pressure |
| Peak (final 2 wks) | 2–3 per week | Full loops; target-company style |

**Where to find mock partners:** [Pramp](https://www.pramp.com) / Exponent (free peer mocks), [interviewing.io](https://interviewing.io) (anonymous mocks with real FAANG engineers), a study buddy, or trading mocks within an interview-prep community. After every mock, write three lines in your log: *what went well, what broke, one thing to fix next time.* That feedback loop is where the gains live.

---

## 8. Weekly Schedule Template

A concrete, copy-this template for a working candidate (~10 hrs/week). Scale time up for the 12-week intensive. Adjust the day labels to your life — the *structure* (interleave tracks, protect a long weekend session, never skip the review) is what matters.

| Day | Focus | Time | Activity |
|-----|-------|------|----------|
| **Mon** | DSA | 75 min | 2 problems on this week's pattern + log insights |
| **Tue** | Theory | 60 min | Read 1 fundamentals topic + add Anki cards |
| **Wed** | DSA | 75 min | 2 problems (1 new pattern, 1 spaced-review variant) |
| **Thu** | System Design | 75 min | Read 1 design topic OR sketch half a full design |
| **Fri** | Behavioral | 45 min | Draft/rehearse 1 STAR story aloud, record it |
| **Sat** | Long session | 150 min | Full system design end-to-end **or** a timed mock |
| **Sun** | Review + plan | 45 min | Clear spaced-repetition queue; plan next week; update study log |

**Daily micro-habit (any day, 10 min):** one flashcard pass over your shakiest cards. Ten minutes a day of retrieval beats a two-hour weekend cram for long-term retention.

---

## 9. Recommended Resources

Real, well-regarded resources current as of 2026. You don't need all of them — pick one per track and go deep.

**Books**

- *Cracking the Coding Interview* — Gayle Laakmann McDowell (the classic DSA + interview-process primer)
- *Elements of Programming Interviews* (EPI) — Aziz, Lee, Prakash (harder, language-specific editions)
- *Designing Data-Intensive Applications* — Martin Kleppmann (the bible for backend/system-design depth)
- *System Design Interview, Vol. 1 & 2* — Alex Xu (the standard design-interview prep)
- *Grokking Algorithms* — Aditya Bhargava (gentle, visual intro for L1/L2)
- *The Algorithm Design Manual* — Steven Skiena (deeper algorithmic theory)
- *Staff Engineer* — Will Larson (essential for L4 behavioral & scope)
- *The Manager's Path* — Camille Fournier (leadership context for senior/staff behavioral)

**Courses & structured curricula**

- **Grind 75 / NeetCode 150** — curated, ordered DSA problem sets (the modern successor to "Blind 75")
- **NeetCode.io** — pattern-based video explanations for the above
- **Educative.io** — *Grokking the Coding Interview* and *Grokking the System Design Interview* (pattern-first)
- **AlgoExpert / SystemsExpert** — structured paid tracks with video solutions
- **MIT 6.006 / 6.046 (OpenCourseWare)** — for genuine algorithmic theory depth

**Practice platforms**

- [LeetCode](https://leetcode.com) — the default problem bank; use company tags and timed mode
- [HackerRank](https://www.hackerrank.com) — common for take-home / OA-style screens
- [Codeforces](https://codeforces.com) — for competitive-programming sharpness (optional, L1–L2)
- [Pramp](https://www.pramp.com) / [interviewing.io](https://interviewing.io) — live mock interviews
- [excalidraw.com](https://excalidraw.com) — whiteboarding tool for practicing system-design diagrams aloud

---

## 10. Common Pitfalls (Read This Twice)

- **Grinding problem count over patterns.** 300 problems with no pattern journal < 120 problems you can categorize and re-derive.
- **Skipping system design until the end.** It has the slowest learning curve; start light in week 1.
- **Treating behavioral as an afterthought.** At L3+ it can sink an otherwise strong loop. Write stories early, rehearse aloud, and get feedback.
- **Studying silently.** Interviews are spoken. Practice narrating your thought process from day one — solving in your head transfers poorly.
- **No spaced review.** Knowledge you don't revisit is knowledge you're paying to forget.
- **Burning out before the loop.** Build a rest week before your interviews. A rested brain outperforms a crammed one.

---

[← Back to master index](../README.md)
