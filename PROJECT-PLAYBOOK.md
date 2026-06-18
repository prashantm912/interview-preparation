# Project Playbook — How to Work Any of the 110 Projects Yourself
**Your "teach me to fish" guide.** This is the *method* you run for every project in `LEARNING-PROJECTS.md`, so you can derive the full plan from a 3-line description on your own. Pair it with `PROJECT-BRIEFS.md` (the answer key) — work a project from this playbook first, then check yourself against the brief.

---

## The core truth

A project's 3 lines (`Teaches / Build / Aha`) are a **pointer, not a spec.** You don't deduce the whole plan from them by staring — you deduce it from the 3 lines **+ ~1 hour of reconnaissance** on the real system you're cloning. The detail comes from research + a pattern that compounds as you finish more projects.

> **You are not expected to deduce everything cold on day one.** For your first ~10 projects, lean on the briefs/explainers. By ~project 25 the milestones will appear in your head, because the same patterns (logs, indexes, state machines, queues) recur everywhere. Deduction is the *output* of the curriculum, not the entry fee.

---

## The reconnaissance recipe (do this before EVERY project, time-boxed to ~1 hr)

1. **One sentence — what real thing am I cloning?** (e.g., "P69 → a single-node Kafka.")
2. **Open the real thing's docs** — its "Concepts"/"Architecture" page, or the JavaDoc of the real class. Don't read it all; **harvest the nouns and verbs.**
   - Nouns = the things you must model (Topic, Partition, Offset…).
   - Verbs = the API you must expose (produce, fetch, commit…).
   - **Those nouns and verbs ARE your spec.** You copy the real vocabulary; you don't invent it.
3. **Find ONE explainer** — the References appendix in `LEARNING-PROJECTS.md`, or google `"build your own <X>"` / `"<X> explained"`. Skim for the *core idea*.
4. **Write the Aha in your own words.** If you can't, read 30 more minutes — you're not ready to build yet.
5. **List every noun/verb you don't understand** → that's your **prerequisite list** (next section).
6. **Stop researching. Build the naive version.** Recon is capped at ~1 hr; the rest you learn by building.

When you do this, the nouns basically *hand you the milestones* — "one milestone per noun."

---

## How to know what to learn first (prerequisites)

Two kinds, found two ways:

- **Between-project prerequisites → already solved by the Master Sequence** in `LEARNING-PROJECTS.md`. The order *is* the prereq map (P3 before P69; P52→P53 before P54). Just follow it.
- **Within-project prerequisites → discovered in recon step 5, learned just-in-time.** Hit a noun you don't get ("what's an fsync? a CRC?")? Learn *just enough to proceed* — 15 min, not a course — then continue. **Just-in-time, never just-in-case.**

**"Am I in over my head?" signal:** if recon leaves you not understanding **more than half** the nouns, the project is too far ahead — drop back to an easier one in that track first.

---

## The 9-step build loop (run for every project)

```
1. READ      → extract the learning target (the Aha)
2. SPEC      → derive "what to build" from the real thing's nouns/verbs (recon)
3. ACCEPT    → write 3–5 done-criteria + the 4-point gate, BEFORE coding
4. NAIVE     → build the dumbest version that works for the trivial case
5. PAIN      → exercise it until it breaks the way the Aha predicts
6. ITERATE   → fix one failure at a time; each fix IS a concept
7. STRESS    → test + break on purpose
8. COMPARE   → read the real implementation/paper, diff your design
9. GATE      → write the 4-bullet summary, commit, move on
```

**Golden rules:**
- **Naive-first, always.** Never start from the "correct" design. Build broken, feel why, then fix. Theory-first is how you forget.
- **The Aha is your scope-cutter.** Unsure whether to build a feature? Ask: *does it serve the Aha?* If not, skip it.
- **Slice a 🔴 into 5–6 testable milestones.** Never face a 1–2 week project as one blob; each evening should end with something that runs.
- **Time-box.** At 2× the effort estimate, you're gold-plating or missing a prereq — cut scope or go learn the prereq; don't grind.
- **Stuck > 30 min?** *Then* open the paper/explainer. Pain-first makes reading stick.

---

## The project brief template (fill this in yourself per project)

Copy this, fill it during recon + acceptance (steps 2–3). This is exactly the shape of every entry in `PROJECT-BRIEFS.md`.

```
### Pn · <Title>   [effort]
Clone of:            <real system or JDK class>
Aha (finish line):   <restate the insight you must end up able to demonstrate>
Recon — read first:  <the real API page / paper / explainer to skim>
Prerequisites:       <earlier projects + within-project micro-prereqs>
Spec (nouns/verbs):  <the data you model + the API methods you expose>
Milestones:          1) ...  2) ...  3) ...   (each independently testable)
Acceptance criteria: [ ] ...   [ ] ...   [ ] DEMONSTRATE THE AHA: ...
How to break it:     <the stress/fault test that proves it works>
Compare to real:     <what the real system does that you can diff against>
Gate (must answer):  trade-off · 10× failure · real system · what broke
```

---

## Verification — "did I build exactly what the project required?"

This is the question that matters most, and the honest answer is: **the description is not what you verify against — your acceptance criteria are.** Verify in three ascending levels. Level 2 is the one most people skip and it's the most important.

### Level 1 — Functional: does it do what `Build:` says?
Run your **acceptance-criteria checklist** (the one you wrote in step 3). Each criterion is a concrete, runnable check, not a vibe. Methods by project type:
- **Data structures / algorithms** → unit tests with known inputs/outputs + a complexity check (does lookup stay O(log n) as N grows 10×?).
- **Concurrency** → a stress test asserting an invariant under many threads (e.g., "exactly-once execution, no lost/double work").
- **Storage / DB** → a "kill mid-write, restart, recover" test.
- **Distributed** → a "kill a node, cluster still correct" test.
- **Performance** → a JMH benchmark showing the before/after number you predicted.

### Level 2 — The Aha: can you reproduce the insight on demand? ⭐
This is the real test of understanding. **The Aha names a specific failure or insight — you must be able to trigger it deliberately.** Examples:
- P3 (thread pool): *unbounded queue → OOM.* Verify by actually flooding an unbounded queue and watching the heap climb, then swapping a bounded queue + rejection policy and watching it push back.
- P69 (Kafka-lite): *replay reconstructs state.* Verify by wiping consumer state, replaying from offset 0, and asserting the rebuilt projection == live state.
- P35 (LSM): *read amplification.* Verify by counting how many SSTables a read touches before vs after adding Bloom filters.

**If you can't reproduce the Aha, you built the mechanics but missed the point — you're not done.**

### Level 3 — Parity: diff against the real thing
Read the real implementation's source or the paper (References appendix) and check your design makes the **same essential choices** (and understand where you deliberately differ / simplified). You're not matching line-for-line — you're confirming you reinvented the *core idea*, not a lookalike.

### The final gate (your pass/fail)
You're done when you can answer all four **without notes**:
1. **Trade-off** — the central one this design makes, in one sentence.
2. **10× failure** — what breaks first at 10× load/scale, and the fix.
3. **Real system** — what production system uses this and why.
4. **What broke** — the specific failure you hit while building and how you diagnosed it.

If all four are crisp → commit, write them into a `NOTES.md`, move on. If any is fuzzy → that's exactly the part you don't understand yet; go back to it. **Don't gold-plate** — you're learning the idea, not shipping the product.

### Cross-checking against the answer key
After your own pass, open the matching entry in `PROJECT-BRIEFS.md` and compare: Did you miss a milestone? A failure mode? A trade-off? Differences are learning, not failure — note what you'd do better and move on. **Read the brief *after* your attempt, not before** — pre-reading robs you of the deduction practice that is the whole point.

---

## Suggested per-project workflow on disk

```
mini-<project>/
├── src/...                 # your build
├── Demo.java               # proves each acceptance criterion when run
├── <Project>Test.java      # the stress/fault test (Level 1 + 2)
└── NOTES.md                # recon notes + the 4-bullet gate answers
```

`NOTES.md` is what you reread before interviews — it's the durable output, more than the code.

---

**Method over memorization. Run the recon recipe + 9-step loop on each project, verify at all three levels, then check yourself against `PROJECT-BRIEFS.md`. Do that ~40 times and you won't just pass interviews — you'll have rebuilt the internals you spent 15 years above.**
