# Git & GitHub

A deep-but-compact interview guide covering Git's internals, day-to-day workflows, branching strategies, code review, and the recovery scenarios that separate engineers who *use* Git from engineers who *understand* it. Knowledge current through 2026 (Git 2.43+, with notes on `git switch`/`restore`, SHA-256 repos, and modern GitHub features).

[← Back to master index](../README.md)

## Table of Contents

- [🟢 Basic (0–2 yrs)](#-basic-02-yrs)
- [🟡 Intermediate (3–7 yrs)](#-intermediate-37-yrs)
- [🟠 Advanced (8–12 yrs)](#-advanced-812-yrs)
- [🔴 Expert (15+ yrs)](#-expert-15-yrs)
- [✅ Key Takeaways](#-key-takeaways)
- [⚠️ Common Pitfalls](#-common-pitfalls)
- [📚 Further Reading](#-further-reading)

---

## 🟢 Basic (0–2 yrs)

### Q1. [Theory] What is the difference between Git and GitHub?

Git is a **distributed version control system (DVCS)** — a command-line tool that records snapshots of your files and lives entirely on your machine. Every clone is a full repository with complete history, so you can commit, branch, diff, and view logs with no network connection. GitHub (like GitLab, Bitbucket, Gitea) is a **hosting platform** built around Git that adds collaboration features Git itself does not have: pull requests, issues, code review UI, access control, CI/CD (GitHub Actions), and a web interface. The key insight for interviews: Git is the protocol/engine; GitHub is one of many remotes you can `push` to. You can use Git with zero remotes, and you can host Git on a bare repo over SSH without any platform at all.

### Q2. [Theory] Explain the three areas/states in Git: working directory, staging area (index), and repository.

Git moves changes through three zones. The **working directory** is your actual files on disk. The **staging area** (also called the *index*) is a holding area where you assemble exactly what the next commit will contain. The **repository** (the `.git` directory) is where committed snapshots live permanently as objects. The flow is `working dir → (git add) → index → (git commit) → repository`.

```
  edit files        git add          git commit
 ┌───────────┐     ┌──────────┐     ┌────────────┐
 │  Working  │ ──▶ │  Staging │ ──▶ │ Repository │
 │ Directory │     │  (index) │     │  (.git)    │
 └───────────┘     └──────────┘     └────────────┘
   modified           staged          committed
        ▲                                  │
        └────────── git checkout ──────────┘
```

The index is what makes partial commits possible (`git add -p`) — you can stage some hunks of a file while leaving others for a later commit.

### Q3. [Practical] You made changes but want to discard them. What commands undo work at each stage?

It depends on *where* the change lives:

```bash
# Discard unstaged changes in the working directory (DESTRUCTIVE)
git restore <file>            # modern; replaces `git checkout -- <file>`
git restore .                 # all files

# Unstage a file (keep the edits in working dir)
git restore --staged <file>   # modern; replaces `git reset HEAD <file>`

# Undo the last commit but KEEP changes staged
git reset --soft HEAD~1

# Undo the last commit and UNSTAGE changes (keep in working dir)
git reset --mixed HEAD~1      # --mixed is the default

# Nuke the last commit AND all its changes (DESTRUCTIVE)
git reset --hard HEAD~1

# Remove untracked files/dirs (DESTRUCTIVE) — preview first!
git clean -nd                 # dry run
git clean -fd                 # actually delete
```

Trade-off: `--soft`/`--mixed` are recoverable (changes stay on disk); `--hard` and `clean -fd` destroy uncommitted work permanently. In production I always run the dry-run form (`-n`) before `clean`, and I prefer `restore`/`switch` over the overloaded `checkout`/`reset` because their intent is unambiguous.

### Q4. [Theory] What is `.gitignore` and what should never go in a repo?

`.gitignore` lists glob patterns for files Git should not track (build artifacts, `node_modules/`, `.env`, IDE folders, compiled binaries). It keeps the repo small and avoids merge noise. **Critically, `.gitignore` only affects *untracked* files** — if a file is already tracked, adding it to `.gitignore` does nothing; you must `git rm --cached <file>` first.

Things that must never be committed: secrets (API keys, passwords, private keys, `.env`), large binaries (use Git LFS), generated/derived files, and anything personal/machine-specific. Security note: once a secret is committed it lives in history forever even after deletion — you must rewrite history (`git filter-repo`) **and** rotate the secret, because anyone who cloned it already has it.

```gitignore
node_modules/
dist/
*.log
.env
.env.*
!.env.example      # negation: DO track the template
.DS_Store
.idea/
```

---

## 🟡 Intermediate (3–7 yrs)

### Q5. [Theory] Explain Git's internal object model: blobs, trees, commits, and tags.

Git is a **content-addressable filesystem**. Everything is stored in `.git/objects/` as one of four object types, each addressed by the SHA-1 (or SHA-256 in newer repos) hash of its content:

- **Blob** — the raw contents of a file. No filename, no metadata, just bytes. Identical content = identical blob (automatic deduplication).
- **Tree** — a directory listing. It maps names → blob hashes (files) and names → tree hashes (subdirectories), plus file modes.
- **Commit** — a snapshot pointer. It references one root tree, zero-or-more parent commits, author/committer info, timestamp, and a message.
- **Tag** (annotated) — a named, signed pointer to a commit with its own message and author.

```
   commit ──parent──▶ commit ──parent──▶ commit
     │                  │                  │
     ▼                  ▼                  ▼
   tree               tree               tree
   ├─ blob (README)   ├─ blob            ├─ blob
   └─ tree (src/)     └─ tree            └─ tree
        └─ blob            └─ blob            └─ blob
```

The "why": because objects are immutable and content-addressed, history is tamper-evident (changing any byte changes every downstream hash), branches are cheap (just a 40-char pointer), and identical files across commits are stored once.

```bash
git cat-file -t <hash>    # show type (blob/tree/commit/tag)
git cat-file -p <hash>    # pretty-print contents
git rev-parse HEAD        # resolve a ref to its commit hash
```

### Q6. [Theory] What are refs and HEAD? What does "detached HEAD" mean?

A **ref** is a human-friendly name pointing to a commit hash, stored as a plain file under `.git/refs/` (e.g. `refs/heads/main` for branches, `refs/tags/v1.0` for tags, `refs/remotes/origin/main` for remote-tracking). A **branch is just a movable ref** that advances when you commit on it.

**HEAD** is a symbolic ref that normally points to your current branch (`.git/HEAD` contains `ref: refs/heads/main`). When you check out a commit directly (`git checkout <hash>` or a tag), HEAD points straight at a commit instead of a branch — this is a **detached HEAD**. Commits you make there belong to no branch; if you switch away without creating a branch, they become unreachable and are eventually garbage-collected. The fix is to capture them: `git switch -c new-branch` before leaving, or recover via `git reflog` afterward.

### Q7. [Theory] Merge vs. Rebase — what is the real difference and when do you use each?

Both integrate changes from one branch into another, but they produce different histories. **Merge** creates a new *merge commit* with two parents, preserving the exact branch topology — nothing is rewritten. **Rebase** *replays* your commits one-by-one on top of the target branch, creating brand-new commits with new hashes, producing a linear history.

```
 MERGE                          REBASE
 A─B─C  (main)                  A─B─C  (main)
      \                              \
       D─E (feature)                  D'─E' (feature, replayed onto C)
        \ /
         M  ← merge commit
```

Use **rebase** for local, unpushed cleanup — keeping your feature branch current and history linear before opening a PR. Use **merge** to integrate completed features into shared branches, because the merge commit documents *when* and *what* was integrated. The **Golden Rule of Rebasing**: never rebase commits that others have already pulled — rewriting shared history forces everyone into painful conflict resolution. Trade-off: rebase = clean linear bisectable history but loses true topology and is dangerous on shared branches; merge = honest history but noisy graph.

### Q8. [Practical] Walk me through resolving a merge conflict.

A conflict occurs when two branches change the same lines (or one edits a file the other deletes) and Git cannot auto-merge.

```bash
git merge feature
# Auto-merging app.js
# CONFLICT (content): Merge conflict in app.js

git status                 # lists "Unmerged paths"
# edit app.js — Git inserts conflict markers:
```

```
<<<<<<< HEAD
const timeout = 30;       // your current branch
=======
const timeout = 60;       // incoming branch
>>>>>>> feature
```

You edit the file to the correct final state, **remove all markers**, then:

```bash
git add app.js            # marks conflict resolved
git merge --continue      # or `git commit`
# escape hatch:
git merge --abort         # bail out, restore pre-merge state
```

Production approach: I use a 3-way merge tool (`git mergetool`, or VS Code) to see *base*, *ours*, and *theirs* — the base shows the common ancestor, which is essential for understanding *why* both sides changed. I enable `git config rerere.enabled true` (**reuse recorded resolution**) so Git remembers how I resolved a conflict and auto-applies it if the same conflict recurs (huge during long rebases). For "take their/our whole side" cases: `git checkout --theirs <file>` / `--ours <file>`.

### Q9. [Practical] Compare GitFlow, GitHub Flow, and trunk-based development.

These are branching strategies trading off release ceremony against integration speed.

```
GITHUB FLOW (simplest)        TRUNK-BASED (fastest)
 main ───●───────●──── ...     main ──●─●─●─●─●── (tiny commits, flags)
          \     /                      \ /
   feature ●─●─● PR→merge       short-lived branch < 1 day

GITFLOW (most structured)
 main ──────●─────────●──  (production, tagged releases)
             \       /
 release      ●─────●     (stabilize)
  develop ──●──●──●──●──── (integration)
            \  /  \  /
 feature     ●─●   ●─●
```

- **GitFlow**: `main`, `develop`, plus `feature/`, `release/`, `hotfix/` branches. Heavyweight; suits versioned software with scheduled releases (desktop apps, libraries). Often overkill today and discouraged for web apps.
- **GitHub Flow**: one long-lived `main` + short-lived feature branches merged via PR; deploy from `main`. Simple, ideal for continuous deployment web apps.
- **Trunk-based development**: everyone commits to `main` (or branches living <24h), behind **feature flags**. Requires strong CI and test coverage; this is what high-velocity orgs like Google and modern SaaS teams use because it minimizes merge debt and maximizes integration frequency.

What I actually do: GitHub Flow for most teams, trunk-based + feature flags when CI maturity and team discipline are high, GitFlow only when there are real parallel maintained release lines.

### Q10. [Coding] Write the commands to do an interactive rebase that squashes the last 4 commits into one and rewords the message.

**Problem:** A feature branch has 4 messy WIP commits; you want a single clean commit before opening a PR.

```bash
# Open the interactive rebase editor for the last 4 commits
git rebase -i HEAD~4
```

Git opens an editor listing commits oldest-first. Change the verbs:

```
pick   a1b2c3d  Add login endpoint
squash 2c3d4e5  fix typo
squash 3d4e5f6  oops forgot import
squash 4e5f6a7  WIP tests
```

- `pick` (`p`) — keep the commit as is
- `squash` (`s`) — fold into the previous commit, **keeping** its message in the combined editor
- `fixup` (`f`) — like squash but **discards** that commit's message
- `reword` (`r`) — keep the commit but edit its message
- `edit` (`e`) — pause to amend content; `drop` (`d`) — delete the commit

Save and Git presents a combined message editor where you write the final clean message. If your branch was already pushed, you must force-push **safely**:

```bash
git push --force-with-lease    # NOT --force: aborts if remote moved since your last fetch
```

- **Time complexity:** O(n) in the number of commits replayed.
- **Edge cases:** the **first** line cannot be `squash`/`fixup` (nothing to fold into — that's an error). Conflicts during replay pause the rebase; resolve, `git add`, `git rebase --continue`, or `git rebase --abort`. Pro tip: `git commit --fixup=<hash>` plus `git rebase -i --autosquash` automates marking fixups.

### Q11. [Practical] What is cherry-pick and when would you reach for it instead of merge/rebase?

`git cherry-pick <hash>` copies the *changes* introduced by a specific commit and applies them as a new commit on your current branch (new hash, same diff). You reach for it when you want **one specific commit, not a whole branch**.

Real scenario: a critical bug is fixed on `main` but you need it on a `release/2.3` branch that has diverged and you can't merge all of `main`. You cherry-pick just the fix:

```bash
git switch release/2.3
git cherry-pick a1b2c3d            # single commit
git cherry-pick a1b2c3d^..f6e5d4c  # a contiguous range
git cherry-pick -x a1b2c3d         # -x appends "(cherry picked from ...)" for traceability
```

Trade-offs: it duplicates the commit on two branches, which can confuse later merges and history analysis. Overuse is a smell — if you're cherry-picking many commits regularly, you probably want a proper branch strategy or backport workflow. For hotfix backports across release lines, judicious cherry-picking is the standard tool.

### Q12. [Theory] Lightweight vs. annotated tags, and how do you cut a release?

A **lightweight tag** is just a named pointer to a commit (a ref, no extra object) — fine for private bookmarks. An **annotated tag** is a full Git object storing the tagger, date, message, and an optional GPG/SSH signature. **Always use annotated tags for releases** because they are verifiable, carry metadata, and `git describe` relies on them.

```bash
git tag -a v2.1.0 -m "Release 2.1.0"        # annotated
git tag -s v2.1.0 -m "Signed release"       # GPG-signed (supply-chain integrity)
git push origin v2.1.0                       # tags are NOT pushed by default
git push --tags                              # push all tags
git describe --tags                          # e.g. v2.1.0-3-gabc123 (3 commits past v2.1.0)
```

On GitHub a **Release** wraps a tag with notes and downloadable assets. Combined with semantic versioning (`MAJOR.MINOR.PATCH`) and signed tags, this gives consumers a trustworthy, auditable release artifact.

---

## 🟠 Advanced (8–12 yrs)

### Q13. [Practical] A teammate force-pushed and "lost" three commits. How do you recover them?

The reflog is the safety net. `git reflog` records every move of `HEAD` (and per-branch reflogs record branch tip movements) for ~90 days by default — **even commits that are no longer reachable from any branch** still exist as objects until garbage collection.

```bash
git reflog                          # find the pre-force-push HEAD
# ab12cd3 HEAD@{4}: commit: the work that "vanished"
git switch -c rescue ab12cd3        # recreate a branch at that commit
# or, to move an existing branch back:
git reset --hard ab12cd3            # if on that branch
```

If even the reflog is gone (e.g. on the remote), `git fsck --lost-found --unreachable` surfaces dangling commits/blobs directly from the object store, which you can then inspect with `git cat-file -p` and recover. Production approach: recover into a *new* branch (never reset shared branches blindly), verify with `git log`/`diff`, then communicate before re-pushing. The lesson I'd also raise: enable branch protection so force-pushes to `main` are blocked in the first place.

### Q14. [Coding] Use `git bisect` to find the commit that introduced a bug. Show the manual and automated forms.

**Problem:** A test passes on an old commit and fails on `HEAD`. Among hundreds of commits, find the exact one that broke it.

Bisect does a **binary search** over history. You mark one bad and one good commit; Git checks out the midpoint, you test, mark it good/bad, and it halves the range each step.

```bash
# Manual
git bisect start
git bisect bad                 # current HEAD is broken
git bisect good v2.0.0         # known-good commit/tag
# Git checks out the midpoint. Test it, then:
git bisect good                # ...or `git bisect bad`
# repeat until Git prints "<hash> is the first bad commit"
git bisect reset               # return to your original HEAD
```

```bash
# Automated — let a script decide (exit 0 = good, non-zero = bad; 125 = skip)
git bisect start HEAD v2.0.0   # bad good in one line
git bisect run ./run-tests.sh
git bisect reset
```

- **Time complexity:** O(log n) tests for n commits — 1000 commits need only ~10 tests.
- **Edge cases:** untestable/broken-to-build commits → `git bisect skip`. Non-deterministic ("flaky") failures break the monotonic good→bad assumption and produce wrong answers. The whole technique relies on commits being **small and individually buildable** — another argument for atomic commits.

### Q15. [Theory] Submodules vs. subtrees vs. monorepo — trade-offs for managing shared code across repos.

Three ways to compose code from multiple sources:

- **Submodules**: a repo embeds a pointer (a specific commit) to another repo. The parent tracks *which commit* of the child it depends on. Pros: clean separation, child has its own history, pinned versions. Cons: notoriously sharp edges — clones need `--recursive`, updates are a two-step dance (`git submodule update --init --remote`), and contributors forget to commit pointer updates. Good for vendoring a third-party dependency you occasionally bump.
- **Subtrees** (`git subtree`): the child repo's files are merged *into* the parent's tree as real files, with optional history. No special clone steps; everyone just sees files. Cons: bidirectional sync is awkward, history can bloat the parent.
- **Monorepo**: all projects in one repo. Pros: atomic cross-project commits, single source of truth, trivial refactors across boundaries, unified CI. Cons: scale pain — needs tooling (Bazel, Nx, Turborepo, sparse-checkout, partial clone) and the repo can become huge. This is the Google/Meta model.

```bash
git submodule add https://github.com/org/lib vendor/lib
git clone --recurse-submodules <url>
git submodule update --init --recursive --remote
```

What I'd choose: monorepo when teams share a lot of code and refactor across boundaries frequently; submodules only for stable, independently-versioned external dependencies. Submodules are the most-asked-about and most-disliked — be ready to explain *why* they're painful (the detached-HEAD-in-child problem and the easy-to-forget pointer commit).

### Q16. [Practical] Design a code-review / pull-request process for a 30-engineer team. What gates do you enforce?

The PR is the unit of review and the audit record. A solid pipeline:

```
 feature branch ─push─▶ open PR ─▶ CI (build, unit, lint, SAST, secret scan)
                                     │ all green?
                          required reviewers (CODEOWNERS) approve
                                     │
                          branch protection: up-to-date, no force-push, signed?
                                     │
                       squash-merge ─▶ main ─▶ deploy / release
```

Gates I'd enforce via **branch protection rules** + **CODEOWNERS**: required passing status checks (CI must be green), ≥1–2 approvals with code-owner approval for sensitive paths, no direct pushes to `main`, linear history (require rebase or squash), dismiss stale approvals on new pushes, and required secret-scanning / dependency review. Cultural rules: small PRs (<400 LOC — review quality drops sharply past that), descriptive PR templates linking the issue, and a turnaround SLA so reviews don't stall delivery. Merge strategy: **squash-and-merge** keeps `main` history clean and atomic, which makes `revert` and `bisect` reliable. Trade-off to articulate: more gates = higher quality and auditability but slower throughput; I tune the strictness to the blast radius of each path (stricter for auth/payments, lighter for docs).

### Q17. [Practical] Git hooks — what are they, where do they live, and how do you enforce them across a team?

Hooks are scripts Git runs at lifecycle events. **Client-side** hooks live in `.git/hooks/` (e.g. `pre-commit`, `commit-msg`, `pre-push`) and run on the developer's machine. **Server-side** hooks (`pre-receive`, `update`, `post-receive`) run on the remote and are the only ones you can truly *enforce*, because `.git/hooks/` is local, not cloned, and trivially bypassed with `--no-verify`.

```bash
#!/bin/sh
# .git/hooks/pre-commit — block commits that contain "TODO-FIXME" or secrets
if git diff --cached | grep -nE 'AKIA[0-9A-Z]{16}'; then
  echo "❌ Possible AWS key in staged changes — aborting commit"; exit 1
fi
```

To distribute and version client hooks across a team, you can't rely on `.git/hooks` (not tracked). The standard solutions: set `git config core.hooksPath .githooks` to point at a tracked directory, or use a manager like **pre-commit** (`.pre-commit-config.yaml`) or **Husky** (JS ecosystem). For hard enforcement (secret scanning, signed commits, lint gates) put them in **CI** and **branch protection**, because anything client-side is advisory. Security angle: a malicious `pre-commit` hook in a cloned repo could execute arbitrary code — hooks are not run from a fresh clone by default for exactly this reason; review hook configs before trusting a repo.

---

## 🔴 Expert (15+ yrs)

### Q18. [Theory] How does Git's packing and garbage collection work, and why does it matter at scale?

Loose objects (one file per object, zlib-compressed) are simple but waste space and inodes. `git gc` consolidates them into **packfiles** that store objects as **deltas** against similar objects, then builds an index (`.idx`) for O(log n) lookup. Deltas mean storing only the *difference* between similar blobs, which is why a repo with thousands of versions of a file stays compact. Unreachable objects older than the grace period (`gc.pruneExpire`, default 2 weeks) are pruned; reflog entries expire (90 days reachable, 30 unreachable) — this is the clock behind "recovery is possible *for a while*."

At scale this matters enormously: large binaries don't delta well and bloat packs forever (hence **Git LFS**, which stores pointers in Git and blobs in a separate store). Operations that touch every object — `gc`, `clone`, `fsck` — become the bottleneck on huge repos. Modern mitigations Git ships today: **partial clone** (`--filter=blob:none` fetches commits/trees now, blobs on demand), **sparse-checkout** (materialize only part of the tree), **commit-graph** files (cache generation numbers for fast traversal/merge-base), and **reachability bitmaps** to speed up `git push`/clone negotiation. These are how Microsoft runs the Windows monorepo (~300GB, the origin of VFS for Git / Scalar) on Git.

### Q19. [Practical] You must permanently remove a leaked secret (and a 2GB binary) from the entire history of a shared repo. Walk through it and the fallout.

This requires **rewriting history**, which changes every commit hash from the offending commit forward. The fallout: everyone's clones diverge and must re-clone or hard-reset; open PRs break; tags must be re-pushed.

```bash
# Preferred modern tool (faster, safer than filter-branch):
pip install git-filter-repo
git filter-repo --invert-paths --path secrets.env          # purge a file from all history
git filter-repo --strip-blobs-bigger-than 50M              # purge large blobs
# replace literal secret text everywhere:
git filter-repo --replace-text <(echo 'OLD_KEY==>***REMOVED***')
```

Steps in practice: (1) **rotate the secret immediately** — assume it's compromised the moment it was pushed; rewriting history does not un-leak it from clones, forks, caches, or CI logs. (2) Coordinate a freeze, run `filter-repo` on a fresh clone, force-push all branches and tags. (3) Have collaborators re-clone (or `fetch` + `reset --hard`). (4) On GitHub, contact support to purge cached views/forks and invalidate the old SHAs; enable **push protection** and **secret scanning** going forward. (5) Run `gc --prune=now` to drop the now-unreachable objects. The hard truth I always state in interviews: **history rewriting is damage control, not a cure** — prevention (pre-commit secret scanning + push protection + short-lived credentials) is the real fix.

### Q20. [Theory] Explain commit signing and the supply-chain security story around Git (SHA-1 → SHA-256, signed commits, attestations).

Git's integrity guarantee (content-addressing) protects against accidental corruption but **not against a malicious committer**, because anyone can set `user.name`/`user.email` to anything. **Signed commits/tags** (GPG, or SSH/X.509 since Git 2.34) cryptographically bind a commit to a key the author controls; GitHub shows a "Verified" badge when the signature matches a registered key. This anchors *who* actually authored code — important after supply-chain attacks (SolarWinds, the 2024 `xz` backdoor) raised the bar on provenance.

On the hash: Git historically used **SHA-1**, which is cryptographically broken (the 2017 SHAttered collision). Git added **collision detection** (`sha1dc`, rejects known-attack inputs) and now supports a **SHA-256 object format** for new repos, though interop with the SHA-1 ecosystem is still maturing. Beyond Git itself, modern supply-chain practice layers **signed tags for releases**, **SLSA provenance / attestations**, **Sigstore (cosign / gitsign)** for keyless signing, and branch protection requiring signed commits. Expert framing: Git gives you *integrity* (tamper-evidence) for free, but *authenticity* and *provenance* require signing + policy you bolt on top.

### Q21. [Behavioral] Tell me about a time a Git decision caused a production incident or major team friction, and what you changed.

Strong answers use STAR and show systemic thinking, not blame. Example: *"On a 40-person team we allowed force-push to `main` and used a long-lived `develop` branch that drifted weeks behind `main`. An engineer rebased and force-pushed `main` to 'clean up history,' silently dropping two merged hotfixes; the next deploy regressed a payment bug we'd already fixed. **Task:** restore correctness and prevent recurrence. **Action:** I recovered the lost commits via `reflog` on a colleague's up-to-date clone, re-applied them, and then drove a process change — enabled branch protection (no force-push, required green CI, required reviews), migrated from GitFlow to trunk-based with feature flags to kill branch drift, and added pre-commit + CI secret/lint gates. **Result:** zero history-related incidents afterward and merge conflicts dropped sharply because branches lived hours, not weeks."* The meta-point I'd emphasize: the fix was rarely "be more careful" — it was making the dangerous action *impossible* through protection rules and shortening branch lifetimes so integration pain never accumulates.

### Q22. [Practical] "Oh no" recovery cheat-sheet — be ready to fix any of these on the spot.

The interviewer may rapid-fire these; crisp, correct answers signal seniority:

```bash
# Committed to the wrong branch (last commit) → move it
git switch correct-branch
git cherry-pick wrong-branch          # bring the commit over
git switch wrong-branch && git reset --hard HEAD~1   # remove from wrong branch

# Committed but forgot a file / typo in message (NOT yet pushed)
git add forgotten.txt && git commit --amend --no-edit
git commit --amend -m "Better message"

# Accidentally `git reset --hard` and lost uncommitted work
# → recover only if it was ever staged/committed:
git fsck --lost-found        # dangling blobs land in .git/lost-found

# Deleted a branch that wasn't merged
git reflog                   # find its last tip hash
git switch -c recovered <hash>

# Pulled and got a messy merge you didn't want
git reset --hard ORIG_HEAD   # ORIG_HEAD = pre-pull state

# Need to undo a PUSHED commit safely (shared branch) → revert, don't reset
git revert <hash>            # new commit that inverts changes; history-safe

# Stash work to switch context, then bring it back
git stash push -m "wip"; git stash list; git stash pop
```

The single most important distinction: on **shared/pushed** branches use `revert` (adds a commit, safe for everyone); on **local/unpushed** work use `reset`/`rebase` (rewrites history). Conflating the two is the root of most Git disasters.

---

## 🧩 Extended Questions — Set 1: Deeper Theory & Practical Operations

### 🟢 Basic — extended

#### Q23. [Theory] What is the difference between `git fetch`, `git pull`, and `git clone`?

These three commands all bring data from a remote, but they differ in *how much* they sync and whether they touch your working files. **`git clone`** is the one-time bootstrap: it copies the entire object database, creates remote-tracking branches (`origin/*`), checks out the default branch, and sets `origin` as the remote. **`git fetch`** updates your remote-tracking refs (`origin/main`, etc.) with new objects from the remote but **does not touch your local branches or working directory** — it is a read-only, always-safe operation. **`git pull`** is the convenience combo: `fetch` + an integration step (`merge` by default, or `rebase` if configured) that *moves your current branch*.

```
git fetch                git pull (= fetch + merge)
 ───────────              ───────────────────────────
 origin/main ✱ updated    origin/main ✱ updated
 main         unchanged   main         ✱ moved/merged
 working dir  unchanged   working dir  ✱ may change/conflict
```

The practical lesson: `fetch` first, *then* inspect (`git log HEAD..origin/main`, `git diff`) before integrating. A blind `git pull` on a dirty working tree can produce surprise merge commits or conflicts. I configure `git config --global pull.rebase true` (or `pull.ff only`) on most repos so a plain `pull` either rebases cleanly or refuses rather than silently creating noisy merge bubbles.

```bash
git pull --ff-only        # refuse to merge; only fast-forward (safest default)
git pull --rebase         # replay local commits on top of fetched work
git fetch --prune         # also delete remote-tracking refs whose remote branch is gone
```

#### Q24. [Practical] How do you configure Git identity, and what is the precedence of config levels?

Git resolves configuration from three (sometimes four) layered scopes, with the **most specific winning**. From narrowest to broadest: `--local` (`.git/config`, per-repo) → `--global` (`~/.gitconfig`, per-user) → `--system` (`/etc/gitconfig`, per-machine). A repo-local setting overrides your global one, which overrides the system default. This is why your work laptop can use a corporate email globally while one client's repo uses a different identity locally.

```bash
git config --global user.name  "Jane Dev"
git config --global user.email "jane@personal.dev"
git config --local  user.email "jane@client-corp.com"   # only this repo
git config --list --show-origin    # see every value AND which file set it
git config --get user.email        # resolved effective value
```

A common real-world problem: commits land with the wrong author email (personal email on company commits, breaking the GitHub "Verified"/contribution linkage). The fix going forward is a local override; for *existing* commits you must rewrite history (`git rebase` with `--exec`, or `git filter-repo --mailmap`). To avoid the whole class of mistakes, I use **conditional includes** so identity is auto-selected by directory:

```ini
# ~/.gitconfig
[includeIf "gitdir:~/work/"]
    path = ~/.gitconfig-work
[includeIf "gitdir:~/personal/"]
    path = ~/.gitconfig-personal
```

#### Q25. [Theory] What is a "fast-forward" merge and when can't Git do one?

A **fast-forward (FF) merge** happens when the target branch has no commits the source branch lacks — the branches haven't diverged. Git simply slides the branch pointer forward to the tip of the source; no merge commit is created because none is needed. It can only happen when your branch's tip is a direct *ancestor* of the branch you're merging in.

```
FAST-FORWARD possible            FF impossible (diverged → real merge)
 main ──A──B   (tip)              main ──A──B──C
              \                              \
   feature      C──D               feature    D──E
 merge: main just moves to D       merge: needs a merge commit M
```

The interview nuance is the policy choice. `--ff-only` makes Git *refuse* to merge if a real merge would be required (great for enforcing linear history on `main`). `--no-ff` forces a merge commit *even when* a fast-forward is possible — many teams use this so every feature integration is marked by an explicit commit, making it trivial to see and `revert` a whole feature as a unit.

```bash
git merge --ff-only feature    # only if no merge commit needed, else abort
git merge --no-ff   feature    # always create a merge commit (feature boundary visible)
git config merge.ff false      # make --no-ff the repo default
```

#### Q26. [Practical] How do you rename a branch locally and on the remote, including the default branch?

Renaming a local branch is trivial; the friction is propagating it to the remote and to every collaborator, because the remote can't "rename" — you push the new name and delete the old. The sequence matters to avoid leaving a dangling upstream.

```bash
git branch -m old-name new-name          # rename current/other local branch
git push origin -u new-name              # push new name and set upstream tracking
git push origin --delete old-name        # remove the stale remote branch
```

Renaming the **default branch** (the classic `master` → `main` migration) is the higher-stakes version because it touches PRs, CI configs, branch protection, and everyone's local clone. On GitHub do it via Settings → Branches (which auto-retargets open PRs), then each collaborator runs:

```bash
git branch -m master main
git fetch origin
git branch -u origin/main main
git remote set-head origin -a            # update origin/HEAD pointer
```

The thing I always flag: **search your CI/CD and config for hard-coded branch names** (`.github/workflows`, deploy scripts, badge URLs, webhooks). The rename itself is one command; the breakage is always in the automation that assumed the old name.

### 🟡 Intermediate — extended

#### Q27. [Theory] Explain `git reset --soft` vs `--mixed` vs `--hard` in terms of the three trees.

`git reset` moves the current branch pointer to a target commit and then *optionally* updates the index and working directory. Thinking in Git's "three trees" — HEAD (the commit/branch pointer), the index (staging), and the working directory — makes the three modes obvious: each mode just resets *how far down* the chain it propagates.

| Mode      | Moves HEAD | Resets index | Resets working dir | Result |
|-----------|:----------:|:------------:|:------------------:|--------|
| `--soft`  | ✅ | ❌ | ❌ | changes from undone commits sit **staged** |
| `--mixed` (default) | ✅ | ✅ | ❌ | changes sit **unstaged** in working dir |
| `--hard`  | ✅ | ✅ | ✅ | changes **gone** from working dir (destructive) |

```
        HEAD/branch   index    working dir
--soft      moved      kept        kept       → "uncommit, keep staged"
--mixed     moved      moved       kept       → "uncommit + unstage"
--hard      moved      moved       moved      → "uncommit + discard everything"
```

The mental model I give juniors: `--soft` "undoes the commit," `--mixed` "undoes the commit and the `git add`," `--hard` "undoes the commit, the `git add`, and your edits." Only `--hard` loses work — and even then, the *commits* you reset away are still recoverable via reflog for weeks; it's the **uncommitted** working-directory changes that `--hard` destroys irreversibly.

#### Q28. [Practical] Your `git pull` created an unwanted merge commit. How do you prevent and fix this?

The unwanted "Merge branch 'main' of origin/main" commits appear when your local branch and the remote both have commits (divergence) and `pull` defaults to merge. Each such pull bubbles up a noisy merge commit that pollutes history and breaks linear-history policies. To **fix the one you just made** (assuming it's unpushed), reset to the state before the pull:

```bash
git reset --hard ORIG_HEAD        # ORIG_HEAD = where you were before the pull
git pull --rebase                 # re-do it as a rebase instead
```

To **prevent it permanently**, change the default pull behavior. The choice is between rebase (linear, replays your local commits) and ff-only (refuse and make you decide):

```bash
git config --global pull.rebase true   # always rebase local commits on pull
# or
git config --global pull.ff only       # only fast-forward; error if diverged
```

The trade-off worth stating: `pull.rebase true` gives clean linear history but rewrites your *local* unpushed commits (fine) — never let it touch commits others have based work on. `pull.ff only` is the safest for shared branches because it never silently does anything; it forces an explicit decision when histories diverge. I default teams to `ff only` plus an explicit `git pull --rebase` habit, which kills the merge-bubble problem entirely.

#### Q29. [Coding] Write a `git log` command that produces a readable graph of branch topology, and explain the key flags.

**Problem:** You need to understand how branches diverged and merged at a glance, without a GUI.

```bash
git log --oneline --graph --decorate --all
```

```
* a1b2c3d (HEAD -> main, origin/main) Merge feature/auth
|\
| * 9f8e7d6 (feature/auth) Add token refresh
| * 7c6b5a4 Add login endpoint
|/
* 5d4c3b2 Update README
```

Flag-by-flag: `--oneline` condenses each commit to `<short-hash> <subject>`; `--graph` draws the ASCII commit DAG showing merges/branches; `--decorate` annotates which refs (branches, tags, HEAD) point at each commit; `--all` includes every branch, not just the current one. For deeper investigation I layer on:

```bash
git log --graph --all --pretty=format:'%C(auto)%h %d %s %C(dim)(%cr) <%an>'
git log --oneline --graph --simplify-by-decoration   # show only "interesting" ref points
git log main..feature       # commits on feature NOT on main (what a PR would add)
git log --first-parent      # follow only mainline, hiding merged-in branch detail
```

- **Edge cases:** `A..B` (two dots) is the asymmetric set difference (in B, not A); `A...B` (three dots) is the symmetric difference plus shows which side each commit is on with `--left-right`. Confusing the two is a classic mistake when scoping "what changed."

#### Q30. [Theory] What is `git stash` really doing under the hood, and what are its pitfalls?

`git stash` is not magic storage — it creates **real commits** that aren't on any branch. A stash entry is actually (at least) two commits: one capturing the index state and one the working-tree state, joined under a special ref `refs/stash` that behaves like a stack (`stash@{0}` is newest). Because they're real commits in the object DB, a "lost" stash is recoverable via `git fsck` even after `stash drop`.

```bash
git stash push -m "wip: refactor auth"   # save tracked changes, clean working tree
git stash push -u                        # -u also stashes UNTRACKED files
git stash list                           # stash@{0}, stash@{1}, ...
git stash apply stash@{1}                # restore but KEEP the stash entry
git stash pop                            # restore AND delete the entry
git stash branch fix-branch stash@{0}    # pop onto a fresh branch (avoids conflicts)
```

Pitfalls that bite people: (1) plain `stash` ignores **untracked** files unless you pass `-u`, so new files silently get left behind. (2) `pop` deletes the entry even if it conflicts, leaving you mid-conflict with no clean stash to retry from — `apply` is safer when unsure. (3) Stashes are easy to forget and accumulate; they aren't pushed and don't survive a fresh clone. My guidance: prefer a throwaway WIP commit on a branch over long-lived stashes for anything you care about — `git commit -m "wip"` is more visible, named, and pushable than a stash buried three deep.

#### Q31. [Practical] How do `.gitattributes` and line-ending normalization (`core.autocrlf`) prevent the "whole file changed" diff problem?

The classic cross-platform headache: a Windows dev commits and suddenly every line of a file shows as changed, because Windows uses CRLF line endings and the repo (or a Mac/Linux teammate) uses LF. The robust fix is **`.gitattributes`** committed to the repo, which enforces normalization consistently for *everyone* regardless of their local `core.autocrlf` setting.

```gitattributes
# .gitattributes — normalize text to LF in the repo, auto-convert on checkout
*           text=auto
*.sh        text eol=lf
*.bat       text eol=crlf
*.png       binary
```

`text=auto` tells Git to store text files with LF internally and convert to the platform's native ending on checkout. `binary` (shorthand for `-text -diff`) marks files Git must never touch or try to diff. The older per-machine knob `core.autocrlf` (`true` on Windows, `input` on mac/Linux) works but is fragile because it relies on every developer configuring it identically — `.gitattributes` is authoritative and version-controlled.

When introducing this to an existing repo, the one-time renormalization is important so the switch itself doesn't create a giant noisy diff for everyone:

```bash
git add --renormalize .
git commit -m "Normalize line endings via .gitattributes"
```

`.gitattributes` does more than line endings — it also drives `merge` strategies per path, custom diff drivers (e.g. treat `.ipynb` semantically), `export-ignore` for archives, and `filter` for Git LFS and clean/smudge filters.

#### Q32. [Theory] Compare HTTPS vs SSH vs GitHub CLI/token authentication for remotes. What are the trade-offs?

There are three common ways to authenticate to GitHub, each with different security and ergonomics trade-offs. **SSH** (`git@github.com:org/repo.git`) uses a key pair you register once; no per-push credential prompt, works great for personal machines, but keys must be managed/rotated and SSH egress can be blocked on locked-down corporate networks. **HTTPS** (`https://github.com/org/repo.git`) works everywhere (port 443, proxy-friendly) but requires a credential helper — and since 2021 GitHub **forbids account passwords for Git**, so you must use a **Personal Access Token (PAT)** or OAuth via a credential manager.

| Method | Credential | Best for | Watch-outs |
|--------|-----------|----------|------------|
| SSH | key pair (`~/.ssh`) | dev workstations | key rotation; port 22 blocked on some networks |
| HTTPS + PAT | token via cred helper | CI, restricted networks | token scope/expiry; never hard-code in URLs |
| `gh` / OAuth | browser-based token | interactive setup | needs `gh` installed |

```bash
git remote set-url origin git@github.com:org/repo.git           # switch to SSH
git remote set-url origin https://github.com/org/repo.git       # switch to HTTPS
gh auth login                                                   # OAuth via GitHub CLI
git config --global credential.helper manager                   # cache HTTPS creds securely
```

For automation/CI the right answer is increasingly **short-lived, narrowly-scoped tokens** — fine-grained PATs, GitHub App installation tokens, or **OIDC** (so a CI job exchanges its identity for a momentary token, no stored secret at all). The anti-pattern to call out: embedding a PAT directly in the remote URL (`https://user:token@github.com/...`) — it leaks into `.git/config`, process listings, and shell history.

### 🟠 Advanced — extended

#### Q33. [Theory] How does the three-way merge algorithm actually work, and what is the role of the merge base?

A naive two-way diff between two file versions can't tell *addition* from *deletion* — if a line exists in "ours" but not "theirs," did you add it or did they remove it? Git resolves this with a **three-way merge** that uses the **merge base**: the best common ancestor commit of the two branch tips (found via `git merge-base`). By comparing each side *against the base*, Git can classify every change as added/removed/modified on a specific side and combine non-overlapping changes automatically.

```
        o---o---o  feature (theirs)
       /
 ...--B------------ ← B = merge base (common ancestor)
       \
        o---o---o  main (ours)

For each region:  base→ours  vs  base→theirs
  changed on one side only  → take that side  (auto)
  changed on both, same way → take it          (auto)
  changed on both, differently → CONFLICT
```

This is why understanding the *base* is the key to resolving conflicts intelligently — `git mergetool` shows base/ours/theirs precisely so you can see what each side intended relative to the shared starting point. Complications: with multiple merge bases (criss-cross merges), Git's default `ort` strategy (since 2.34, replacing `recursive`) computes a *virtual* merged base recursively. **`rerere`** caches your resolution of a given base/ours/theirs triple so identical conflicts auto-resolve later. And `git merge -X ours`/`-X theirs` biases *only the conflicting hunks* toward one side — distinct from the `ours` *strategy* (`-s ours`), which discards the other branch's content entirely while still recording the merge.

#### Q34. [Practical] You need to migrate a 12-year-old SVN repo (or large legacy repo) to Git while preserving history and authors. How do you approach it?

History-preserving migration is mostly about **author mapping** and **cleanup**, not the mechanical conversion. For SVN, `git svn clone` (or the more robust `svn2git` / `reposurgeon` for complex layouts) reconstructs Git history, but SVN only stores usernames — you must supply an **authors file** mapping each SVN user to a real `Name <email>` so Git history and GitHub attribution are correct.

```bash
# authors.txt:  svnuser = Real Name <real@email.com>
git svn clone --stdlayout --authors-file=authors.txt \
    https://svn.example.com/repo /tmp/repo-git
# convert SVN tag/branch dirs into real Git tags/branches, then:
git filter-repo --strip-blobs-bigger-than 50M    # drop accumulated cruft
```

The phased plan I'd run: (1) **trial conversion** to discover all authors and oversized blobs; build the authors map and an LFS migration list. (2) **Clean during migration** — this is the one cheap moment to purge secrets, giant binaries (`git lfs migrate import --include="*.psd"`), and dead branches before they're enshrined in everyone's clone. (3) **Freeze** the old system, do the final conversion, push to GitHub, set up branch protection/CI. (4) Keep the old repo **read-only** for a deprecation window as a reference. The hard parts in practice are non-fast-forward SVN branch semantics, `svn:externals` (which map to submodules or vendored code), and binary bloat — budget time for those, not the `clone` command itself.

#### Q35. [Theory] What are reachability bitmaps, the commit-graph file, and how do they make operations on huge repos fast?

On a large repo, the expensive part of `clone`, `fetch`, and `push` is **object negotiation** — figuring out which objects the other side is missing, which requires walking the commit DAG and enumerating reachable objects. **Reachability bitmaps** (`.bitmap` files alongside packfiles) precompute, for selected commits, a bitmap of every object reachable from that commit. Set operations (union/difference) on bitmaps replace expensive graph walks, turning "what do you need" from O(history) traversal into fast bitwise math — this is why a `git push` to GitHub is quick even on a massive repo.

The **commit-graph** file (`.git/objects/info/commit-graph`) is a parallel optimization for *history traversal*. It caches commit parent pointers, tree OIDs, commit dates, and crucially **generation numbers** (topological levels). Operations like `git merge-base`, `git log --graph`, and ancestry checks normally parse and decompress many commit objects; the commit-graph lets Git answer "is X an ancestor of Y?" using generation numbers without touching the object store.

```bash
git config --global fetch.writeCommitGraph true   # maintain commit-graph automatically
git commit-graph write --reachable                 # build/refresh it manually
git config repack.writeBitmaps true                # write bitmaps on repack
git maintenance start                              # background gc/commit-graph/prefetch
```

The expert framing: Git's data model is content-addressed and elegant but *traversal-heavy*. These structures are caches that trade disk and write-time for read-time speed, and they're foundational to how GitHub serves millions of fetches and how monorepos stay usable. They pair with **partial clone** and **sparse-checkout** (download/materialize less) to make repos that wouldn't otherwise fit on a laptop workable.

#### Q36. [Practical] Design a release/hotfix branching and tagging strategy for software with multiple supported major versions in production.

When you support several live versions (say v2.x for legacy enterprise customers and v3.x for current SaaS), you can't use a single-trunk model alone — you need **long-lived release branches** that receive only backported fixes, plus a disciplined tagging and cherry-pick flow. The shape:

```
 main ───●───●───●───●───●──────────●──   (active development, v3.x)
          \           \              \
 release/3.x ●──●──tag v3.4.2     fix lands on main first…
                  \
 release/2.x ●──●──tag v2.9.7   ← cherry-pick the same fix back (-x)
```

The governing rule is **fix forward, then backport**: a bug is fixed on `main` (or the newest release branch) first, reviewed and tested, then cherry-picked (`git cherry-pick -x <sha>`) into each older supported `release/N.x` branch, each getting its own patch tag. This prevents the common failure where a fix exists in 2.9.7 but was never forward-ported and silently regresses in 3.0. Annotated, signed tags (`v3.4.2`) mark every shipped artifact; `git describe --tags` gives build provenance.

```bash
git switch release/2.x
git cherry-pick -x 9f8e7d6        # -x records the source SHA for traceability
git tag -s v2.9.7 -m "Backport: fix auth token leak"
git push origin release/2.x v2.9.7
```

Operational guardrails I'd add: a documented **support matrix** (which versions get fixes, until when), CI that runs each release branch's test suite, branch protection on every `release/*`, and automation (Mergify, or GitHub's backport bots / labels like `backport-2.x`) so backports aren't manual and forgotten. The trade-off: more maintained lines = more cherry-pick toil and divergence risk, so I push customers onto fewer supported versions and time-box support windows aggressively.

#### Q37. [Theory] Explain how `git blame`, `git log -L`, and `--follow` track history, and their limitations.

`git blame <file>` annotates each line with the commit, author, and time that last *modified* it — invaluable for "why is this line here / who do I ask." Under the hood it walks history backward, attributing each line to the most recent commit that changed it, following the file across renames when possible. Its core limitation: blame shows the *last touch*, which is often a trivial reformat or rename, not the commit that introduced the actual logic. That's where its options matter:

```bash
git blame -L 40,60 app.js          # only lines 40–60
git blame -w app.js                # ignore whitespace-only changes
git blame -C -C app.js             # detect lines copied/moved from other files
git blame --ignore-rev <sha>       # skip a known bulk-reformat commit
git log -L 40,60:app.js            # FULL evolution of those lines over time
git log --follow -p app.js         # history across renames, with diffs
```

`git log -L` is the more powerful cousin: instead of one snapshot of blame, it shows the *entire history* of a line range — every commit that ever touched those lines, in order — which is what you actually want for archaeology. `--follow` makes `git log` continue past renames (Git doesn't store renames; it *infers* them by content similarity at display time, controlled by `-M`/`-C` rename-detection thresholds).

Limitations to state in an interview: rename/copy detection is **heuristic** (content similarity), so a rename combined with heavy edits can break the chain. To stop reformatting commits from polluting blame permanently, commit a `.git-blame-ignore-revs` file and set `git config blame.ignoreRevsFile .git-blame-ignore-revs` — GitHub honors it too, so "Reformat with Prettier" stops masking the real authorship of every line.

### 🔴 Expert — extended

#### Q38. [Theory] Walk through Git's wire protocol (v0/v1 vs v2) and what happens during `git fetch`/`push` negotiation.

A fetch is a conversation between client and server over a transport (HTTPS smart protocol or SSH). In the **legacy protocol (v0/v1)** the server *unconditionally advertises every ref* the instant you connect — on a repo with hundreds of thousands of refs (think a busy fork or a repo with per-PR refs) that advertisement alone can be megabytes before you've asked for anything. The client then runs **want/have negotiation**: it sends `want <oid>` for refs it wants and `have <oid>` for commits it already has; the server computes the difference and streams back a **packfile** of just the missing objects (using bitmaps/thin packs to minimize size).

**Protocol v2** (default since Git 2.26, negotiated via `version=2`) fixed the ref-advertisement scaling problem: it is **command-based and supports server-side ref filtering**, so the client can request only the refs matching a prefix (`ref-prefix refs/heads/main`) instead of receiving the entire ref namespace. This dramatically speeds up fetches on huge repos and is why modern Git feels faster against GitHub even when nothing local changed.

```bash
GIT_TRACE_PACKET=1 git fetch          # watch the actual want/have exchange
git config --global protocol.version 2
git -c protocol.version=2 ls-remote origin 'refs/heads/main'   # filtered ref query
```

For **push**, the negotiation runs the other direction (the client knows what the server has from ref advertisement), the client sends a packfile, and the server runs `pre-receive`/`update` hooks before atomically updating refs — which is the only place enforcement can truly happen (clients are untrusted). Push also enforces **non-fast-forward rejection** unless forced, which is the mechanism behind "updates were rejected because the remote contains work you do not have." Expert detail: `--force-with-lease` works because the client sends the *expected* old OID of the ref, and the server rejects the push if the ref moved — a CAS (compare-and-swap) on the ref that plain `--force` skips.

#### Q39. [Practical] Your CI is slow because every job does a full clone of a 5GB repo. How do you optimize checkout time?

Full clones of a large repo in CI are pure waste — most jobs need one commit's worth of files, not the entire history of every branch. The optimizations stack, and the right combination depends on whether the job needs history (e.g. `git describe`, changelog generation) or just the current tree.

```bash
# Shallow: only the tip commit, no history (fastest; common in CI)
git clone --depth 1 https://github.com/org/repo.git

# Single branch only (skip fetching all the other refs)
git clone --depth 1 --single-branch --branch main <url>

# Partial clone: full history but blobs fetched on demand (history-safe)
git clone --filter=blob:none <url>          # treeless: --filter=tree:0

# Sparse checkout: materialize only the paths this job builds
git clone --filter=blob:none --sparse <url>
git sparse-checkout set services/payments libs/common
```

The trade-offs matter: **shallow clone** (`--depth 1`) is the cheapest but breaks anything that walks history (`git log`, `git describe`, `merge-base`-based diffing for "changed files since main") and can cause "shallow update not allowed" errors if a job tries to push. **Partial clone** (`--filter=blob:none`) keeps full commit/tree history so history operations work, fetching file contents lazily only when checked out — better when you need `git diff origin/main...HEAD`. **Sparse-checkout** cuts the working-tree size for monorepos so a job only writes the directories it builds.

Beyond clone flags: most CI providers offer a **repo cache / persistent checkout** between runs (fetch instead of clone), and `actions/checkout` defaults to `fetch-depth: 1` for exactly this reason. The pragmatic recipe I use: `--depth 1 --single-branch` for build/test jobs that don't need history; `--filter=blob:none` (full ref history, lazy blobs) for jobs that diff against base; warm caches where the runner supports them. I also run scheduled `git gc`/repack and bitmap generation on self-hosted Git so server-side negotiation stays fast.

#### Q40. [Theory] What is `git replace`, what are grafts/replace-refs used for, and how do shallow/partial clones change repo invariants?

`git replace <original> <replacement>` lets you transparently substitute one object for another at read time *without rewriting history* — Git stores a `refs/replace/<oid>` ref and, when traversal hits the original OID, silently serves the replacement. Its classic use is **history stitching**: you converted a repo and want the new history's root commit to appear to have the old (archived) history as its parent, without merging the giant old object DB into the live repo. `git replace --graft <commit> <new-parents>` rewrites *just the parent pointers* for display, which is the modern, safe successor to the old `.git/info/grafts` file (grafts were unversioned, non-replicated, and silently lied about history — replace-refs at least can be fetched and are explicit).

```bash
git replace --graft <old-root-commit> <tip-of-archived-history>
git replace -l                     # list active replacements
git --no-replace-objects log       # see TRUE history, ignoring replacements
git push origin 'refs/replace/*'   # replace-refs aren't pushed by default
```

This connects to a deeper expert point about **invariants**. Normal Git guarantees that if you have a commit, you have *every* object reachable from it (the "have it all" property). **Shallow clones** deliberately break this — they cut history at a depth and record the boundary in `.git/shallow`, so the repo is internally inconsistent on purpose and many operations (`git log` past the boundary, some merges) silently can't see the truncated past. **Partial clones** break a different invariant: you have all *commits/trees* but blobs may be absent, so the repo carries a **promisor remote** reference and Git transparently lazy-fetches missing blobs on demand (which means an operation can suddenly hit the network or fail offline).

The unifying expert insight: replace-refs, grafts, shallow, and partial clone are all ways the *apparent* object graph diverges from the *materially present* objects. Each is powerful but violates an assumption other tooling may rely on — so you reach for them deliberately (CI speed, monorepo scale, history archival) and document them, never as silent defaults that surprise the next engineer.

#### Q41. [Practical] How do you debug "works on my machine but the clone is corrupt / objects missing" and other repository-integrity problems?

When a repo behaves strangely — `git log` errors, checkout fails with "object not found," or a push is rejected with corruption messages — the first tool is **`git fsck`**, which verifies the connectivity and validity of every object in the database. It reports missing objects (referenced but not present), dangling objects (present but unreachable — usually harmless reflog/stash debris), and broken links.

```bash
git fsck --full --strict           # full integrity check
# error: object <oid>: missing/corrupt
git cat-file -p <oid>              # try to read a suspect object
git count-objects -vH             # repo size, loose vs packed object counts
git verify-pack -v .git/objects/pack/pack-*.idx   # validate a packfile
```

The diagnostic flow I follow: (1) Confirm whether it's **local corruption** (bad disk, interrupted `gc`, killed clone) or **a genuinely missing object** (shallow/partial clone hitting a boundary, or a remote that lost objects). Local corruption is often *recoverable from the remote*: `git fetch origin` (or a fresh `git clone`) re-downloads good copies of the corrupt objects, since every clone is a full replica. (2) For a partial clone hitting a missing blob, the promisor remote should refetch it — failures there usually mean the remote or network is the problem, not your repo. (3) For loose-object corruption with no remote, `git fsck --lost-found` plus `git unpack-objects` from a colleague's packfile can rescue data.

Root causes worth naming: filesystem issues (especially network drives, OneDrive/Dropbox syncing `.git`, or case-insensitive filesystems causing phantom changes), an interrupted `git gc`/repack, antivirus locking pack files on Windows, or running out of disk mid-operation. Prevention: never put `.git` on a sync service, set `git config core.fsmonitor true` carefully, run `git maintenance` rather than ad-hoc `gc`, and on critical servers keep mirror backups (`git clone --mirror`) so the canonical object store is recoverable. The reassuring truth I anchor on: because Git is distributed and every clone is complete, "the repo is corrupt" is almost never fatal — some healthy clone somewhere has the missing objects.

#### Q42. [Theory] Compare Git's data model and performance characteristics with Mercurial and with newer systems (Jujutsu/Sapling), and where Git's design shows its age.

Git, Mercurial (hg), and the newer crop (Jujutsu/`jj`, Meta's Sapling) all do distributed version control with content-addressed history, but their models differ in instructive ways. **Git** stores **snapshots** (each commit references a full tree; deltas are a packfile storage detail, not the model) and exposes a famously large, low-level "porcelain over plumbing" surface with mutable refs and an explicit staging index. **Mercurial** also snapshots but historically used a revlog (delta-chain) storage model, presents a *simpler, more consistent* command set, and treats history as more immutable by default (extensions like `evolve` add safe rewriting) — many find it gentler, but Git won the ecosystem (GitHub) and network effects.

| Aspect | Git | Mercurial | Jujutsu / Sapling |
|--------|-----|-----------|-------------------|
| Model | snapshots + refs | snapshots + revlog | Git-compatible objects, different UX |
| Staging index | explicit | none (simpler) | no index; working copy *is* a commit |
| History rewrite | reflog safety net | immutable-ish + evolve | first-class, with **operation log** undo |
| Conflicts | block the operation | block the operation | **recorded in commits**, resolve anytime |
| Backend | own object store | own | jj works *on top of a Git repo* |

The newer systems are the interesting part for an expert. **Jujutsu (`jj`)** uses Git as a storage backend (so it interoperates) but rethinks the UX: there is **no staging area** (the working copy is automatically a commit), history rewriting is the *normal* path with an **operation log** that can undo *any* operation (not just ref moves like reflog), and **conflicts are first-class objects stored in commits** — you can commit a conflicted state and resolve it later, which makes large rebases far less painful than Git's "stop the world on every conflict." **Sapling** (Meta) similarly drops the index and is built for monorepo scale with lazy fetching.

Where Git's design shows its age: SHA-1's cryptographic weakness (mitigated, SHA-256 migration still maturing and not seamlessly interoperable); the staging area and overloaded commands (`checkout` doing five things, since split into `switch`/`restore`) confuse newcomers; rename detection is heuristic rather than recorded; and conflicts halt operations rather than being representable data. The honest expert take: Git *won* on ecosystem and is good enough that its data model is now a de-facto standard — which is exactly why `jj` and Sapling chose to build *on Git's object store* rather than replace it, improving the interface while keeping the proven storage layer.

#### Q43. [Practical] What is the difference between `git revert` and `git reset`, and when is each the only correct choice?

Both "undo" commits, but they do it in opposite ways with opposite safety profiles. **`git revert <sha>`** creates a *new* commit that applies the inverse diff of the target — history grows forward, nothing is rewritten, and every collaborator's clone stays consistent. **`git reset <sha>`** *moves the branch pointer backward*, rewriting history so the undone commits are no longer on the branch. The single most important operational rule in Git follows directly: **on a shared/pushed branch use `revert`; on local/unpushed work use `reset`.**

```
revert                            reset --hard
 A-B-C  (bad commit = B)           A-B-C  (HEAD)
        \                                v
         A-B-C-B'  <- B' undoes B   A      (B,C discarded from branch)
 history preserved, safe to push   history rewritten, never on shared
```

```bash
git revert <sha>                  # safe everywhere; inverse commit
git revert -m 1 <merge-sha>       # revert a MERGE: -m picks the mainline parent
git revert --no-commit a..b       # stage the inverse of a range, one commit
git reset --hard <sha>            # local rewind only (destructive to later commits)
```

The subtle expert point is **reverting a merge commit**: a merge has two parents, so Git can't know which side to keep unless you tell it with `-m 1` (mainline) or `-m 2`. And reverting a merge *doesn't* let you cleanly re-merge that branch later — Git thinks those changes are already present — so you typically must revert the revert. This is exactly why I prefer squash-and-merge on the integration branch: it makes "undo this whole feature" a single clean `revert` of one commit rather than a multi-parent headache.

#### Q44. [Practical] How do you keep a long-running fork or feature branch in sync with a fast-moving upstream without merge hell?

A branch that lives for weeks against an active `main` accumulates **integration debt**: the longer it diverges, the more (and worse) the eventual conflicts. The structural answer is to *integrate continuously* rather than in one big bang at the end. For a fork, you add the original repo as a second remote (`upstream`) and regularly pull its changes in:

```bash
git remote add upstream https://github.com/original/repo.git
git fetch upstream
git switch main
git merge --ff-only upstream/main      # keep your fork's main = upstream
git push origin main
# then rebase your feature work onto the refreshed main:
git switch feature
git rebase main                        # replay your commits on latest main
git push --force-with-lease            # your unpushed-to-others feature branch
```

The cadence and method matter. I rebase a *personal, unshared* feature branch frequently (daily) so conflicts surface in small, comprehensible chunks and `rerere` (`git config rerere.enabled true`) remembers resolutions across repeated rebases. For a *shared* feature branch that several people commit to, I **merge** `main` into it instead of rebasing — rebasing would rewrite commits others have based work on, violating the golden rule.

The real fix, though, is usually structural rather than mechanical: keep branches short-lived (hours/days, not weeks), break large features into independently-mergeable PRs behind feature flags, and integrate to trunk continuously. When I see a branch that's "300 commits behind main," the lesson isn't "rebase harder" — it's that the branching strategy let debt accumulate, and trunk-based development with flags would have prevented it entirely.

#### Q45. [Theory] What are `git worktree`s, and what problem do they solve that branching and stashing don't?

`git worktree` lets a single repository have **multiple working directories checked out simultaneously**, each on a different branch, all sharing one `.git` object store. The problem it solves: you're mid-task on `feature` with a dirty tree, and an urgent hotfix lands. The old options were ugly — `stash` your work and switch (context loss, risky), or `clone` the repo again (wastes disk, duplicates objects, separate config). A worktree gives you a second clean directory on `hotfix` *without disturbing* your `feature` directory at all.

```bash
git worktree add ../repo-hotfix -b hotfix origin/main   # new dir on a new branch
git worktree add ../repo-review pr-1234                  # check out a PR to review
git worktree list                                        # show all worktrees + paths
git worktree remove ../repo-hotfix                       # clean up when done
git worktree prune                                       # drop stale admin records
```

Because all worktrees share the object database, this is far cheaper than multiple clones (objects, hooks, and config are shared) and there's no fetch duplication. Real uses: running a long build/test on one branch while coding on another, reviewing a PR in a real checkout without losing your place, or bisecting in a separate directory. The one rule Git enforces: **the same branch can't be checked out in two worktrees** (it would create ambiguity about where commits go), so each worktree owns its branch. Edge cases to know: submodules and worktrees interact awkwardly, and removing a worktree directory by hand without `git worktree remove`/`prune` leaves stale metadata you must clean up.

#### Q46. [Practical] How do you split one messy commit into several clean, atomic commits during a rebase?

You have a single commit that does three unrelated things (a bug fix, a refactor, and a typo) and you want three reviewable commits. The tool is **interactive rebase with `edit`**, which pauses *at* that commit so you can deconstruct it. You reset the commit's changes back into the working tree, then re-commit them in logical pieces.

```bash
git rebase -i HEAD~3
# mark the messy commit:  edit  a1b2c3d  Did three things at once
# rebase stops at that commit. Now undo just that commit but keep the changes:
git reset HEAD~                 # commit's changes are now unstaged in working dir
git add -p                      # interactively stage ONLY the bug-fix hunks
git commit -m "Fix null deref in auth handler"
git add src/utils.js
git commit -m "Refactor: extract token validator"
git add -A && git commit -m "Fix typo in error message"
git rebase --continue           # resume; later commits replay on top
```

The key tools are `git reset HEAD~` (which "uncommits" into the working directory while staying paused in the rebase) and `git add -p` / `git add -i` (which let you stage individual hunks, even partial lines via the `e`dit option, so each commit is genuinely atomic). The whole point is **atomic commits**: each one builds, passes tests, and does exactly one thing — which is what makes `bisect`, `revert`, `cherry-pick`, and code review actually work. The only hard rule: never do this surgery on commits others have already pulled, since it rewrites hashes. If conflicts arise as later commits replay, resolve, `git add`, `git rebase --continue` — or `--abort` to bail back to the original state.

#### Q47. [Theory] Explain GitHub-specific ref namespaces (`refs/pull/*`, `refs/notes/*`) and how PR merge strategies (merge/squash/rebase) shape history.

GitHub layers extra refs on top of plain Git. Every pull request is exposed under **`refs/pull/<n>/head`** (the PR branch tip) and **`refs/pull/<n>/merge`** (a test-merge GitHub computes against the base) — these aren't fetched by default, but you can grab them, which is how `gh pr checkout` and CI fetch PR code even from forks:

```bash
git fetch origin 'refs/pull/1234/head:pr-1234'   # check out PR #1234 locally
git fetch origin '+refs/pull/*/head:refs/remotes/origin/pr/*'   # all PRs
git notes add -m "deploy verified"               # refs/notes/* - metadata, not in tree
```

The three **merge-button strategies** produce materially different `main` histories. **Create a merge commit** preserves every PR commit plus a merge node — honest topology but a noisy graph with all the "fix typo" WIP commits. **Squash and merge** collapses the whole PR into one commit on `main` — clean, atomic, one-commit-per-PR history that makes `revert`/`bisect` trivial, at the cost of losing intra-PR commit granularity (and it rewrites authorship into a single commit). **Rebase and merge** replays each PR commit onto `main` with no merge node — linear history that keeps individual commits, but loses the PR boundary and can confuse attribution.

```
merge commit          squash               rebase
 main -*---*           main -*-#            main -*-#-#-#
        \  /                 (1 commit)            (N commits, linear)
   PR    #-#
```

My default for most teams is **squash-and-merge**: it enforces one logical change per `main` commit, keeps history bisectable, and means contributors don't have to curate a clean commit series inside the PR. I use rebase-merge when the PR's individual commits are themselves meaningful and curated, and plain merge commits when I deliberately want feature boundaries visible for whole-feature reverts. The policy should be enforced repo-wide (allow only one strategy) so history stays consistent.

#### Q48. [Practical] Production incident: a bad commit reached `main` and auto-deployed. Walk through your immediate Git response and the follow-up hardening.

The first principle under incident pressure is **stop the bleeding with the safest reversible action** — which on a shared, deployed branch means `git revert`, never `git reset`/force-push (rewriting `main` mid-incident would break every clone and CI run and possibly trigger more bad deploys). Revert creates a forward commit that restores the known-good state and flows through the same deploy pipeline:

```bash
git switch main
git revert <bad-sha>              # or `-m 1 <merge-sha>` if the bad change was a merged PR
git push origin main             # redeploys the reverted (good) state
# if it was a squash-merged PR, that's ONE sha to revert - clean and fast
```

The triage sequence I run: (1) **Confirm and identify** the offending commit fast — `git log --oneline`, correlate the deploy timestamp, or `git bisect run` against a reproducing check if it's not obvious which commit. (2) **Revert and redeploy** to restore service; resist the urge to "fix forward" under pressure unless the revert itself is risky (e.g. a DB migration that can't be cleanly undone — those need a forward-fix and a runbook). (3) **Communicate** in the incident channel which sha was reverted and why. (4) **Fix properly afterward** on a branch, re-review, and re-merge — and remember that if you revert a *merge* commit you'll later need to "revert the revert" to re-introduce the corrected feature.

The follow-up hardening is where seniority shows: this commit *should not have been able to auto-deploy*. I'd add **branch protection** (required green CI, required reviews, no direct pushes), gate deploys behind the same checks, introduce a **canary/staged rollout** with automated rollback on health-check failure so a bad deploy self-heals, and ensure the deploy is **idempotent and instantly revertible**. The systemic lesson, as always: the durable fix isn't "review more carefully," it's making the bad outcome structurally hard — green-CI gates, progressive delivery, and one-commit-per-PR (squash) so reverts are atomic.

#### Q49. [Theory] How do Git LFS and clean/smudge filters work, and what are their failure modes?

Git is terrible at large binaries: they don't delta-compress, every version bloats the packfile *permanently* (history can't shrink without a rewrite), and clone/fetch times balloon. **Git LFS (Large File Storage)** solves this with **clean/smudge filters** configured via `.gitattributes`. On commit (the *clean* filter) LFS replaces the binary with a tiny text **pointer file** (an OID + size) that gets stored in Git; the actual bytes go to a separate LFS server. On checkout (the *smudge* filter) LFS swaps the pointer back for the real file by downloading it.

```gitattributes
*.psd  filter=lfs diff=lfs merge=lfs -text
*.mp4  filter=lfs diff=lfs merge=lfs -text
```

```bash
git lfs install                      # register the filters in git config
git lfs track "*.psd"                # adds the .gitattributes rule
git lfs migrate import --include="*.psd" --everywhere   # convert EXISTING history
git lfs ls-files                     # what's stored in LFS
```

The mechanism generalizes: **clean/smudge filters** are a hook point for any content transformation between the repo and working tree (e.g. encrypting secrets at rest, keyword expansion). LFS just ships a polished implementation. Failure modes to know: (1) **a fresh clone without `git-lfs` installed** checks out *pointer files instead of real content* — the classic "why is my image a 130-byte text file" bug. (2) LFS objects live on a separate server with its own **storage/bandwidth quotas and auth**; if that server is down or the quota is hit, checkouts fail even though Git is fine. (3) Removing a file from LFS doesn't reclaim space in the LFS store without a separate prune, and converting an existing repo to LFS **rewrites history** (every collaborator re-clones). (4) Forks and CI need LFS access configured separately. The strategic call: LFS helps, but the better long-term answer for truly large or generated artifacts is often to keep them *out of version control entirely* (artifact registry, object storage) and reference them by version.

#### Q50. [Practical] How do you safely test, audit, and pin third-party GitHub Actions to avoid supply-chain compromise?

A GitHub Actions workflow that references `uses: some-org/some-action@v3` is executing *someone else's code* with access to your repository, secrets, and (often) the `GITHUB_TOKEN`. The 2025-era attacks (e.g. the `tj-actions/changed-files` compromise, where a popular action's tags were repointed to malicious code) proved that a **mutable tag is a supply-chain liability**: the attacker moved the tag to point at code that exfiltrated secrets, and everyone pinned to that tag was instantly affected.

```yaml
# BAD - mutable tag; a moved tag silently changes what runs
- uses: tj-actions/changed-files@v44
# GOOD - pinned to an immutable commit SHA (can't be repointed)
- uses: tj-actions/changed-files@a1b2c3d4e5f6...   # full 40-char SHA
```

The defensive practices, in order of impact: (1) **Pin actions to a full commit SHA**, not a tag — a SHA is content-addressed and immutable, so an attacker can't swap the code under you (tools like Dependabot and `pin-github-action` keep the SHA comment-annotated with its version for readability). (2) **Minimize token scope** with `permissions:` at the workflow/job level (default to `contents: read`) so a compromised action can't push or open PRs. (3) **Avoid `pull_request_target`** and untrusted-input interpolation (the `${{ github.event.* }}` script-injection class). (4) **Vendor or fork critical actions** into your org so you control updates and can audit diffs before bumping.

```yaml
permissions:
  contents: read        # least privilege; widen only per-job as needed
```

The broader framing I'd give: your CI is part of your **attack surface and provenance chain**. Beyond pinning, mature orgs add allow-lists of permitted actions (Settings -> Actions), require signed commits, use **OIDC** for cloud auth so no long-lived cloud secrets sit in the repo, and generate **SLSA provenance/attestations** for build artifacts. The mindset shift is treating every `uses:` like a dependency in your lockfile — pinned, reviewed, and updated deliberately, never floating on a tag you don't control.

#### Q51. [Theory] What guarantees does Git's content-addressing provide, and what does it explicitly NOT protect against?

Git's object IDs are cryptographic hashes of object content, which gives **integrity** for free: change a single byte of any file, tree, or commit and its hash changes, which changes the hash of every object that references it, cascading all the way to the branch tip. This makes history **tamper-evident** — you cannot silently alter an old commit without every descendant hash changing, so two parties comparing tip hashes know instantly whether their histories are identical. It also gives automatic **deduplication** (identical content = same OID = stored once) and cheap verification (`git fsck` recomputes hashes to detect bit-rot or corruption).

What content-addressing does **not** provide is the crucial nuance: **integrity is not authenticity**. The hash proves the content hasn't changed *since it was hashed*; it says nothing about *who* authored it. The `author`/`committer` fields are arbitrary strings anyone can set — `git config user.email ceo@company.com` will happily forge commits attributed to anyone. Tamper-evidence also only helps if you have a *trusted reference* hash to compare against; an attacker who rewrites history and force-pushes produces a perfectly valid (internally consistent) but malicious history.

```bash
git config user.name  "Linus Torvalds"     # nothing stops this - author is not identity
git commit -m "totally legit"               # forged attribution, valid hash
git verify-commit <sha>                     # the REAL check: cryptographic signature
git log --show-signature                    # show signature verification status
```

The layers you bolt on to get the missing guarantees: **signed commits/tags** (GPG or SSH/X.509) bind a commit to a key the author controls, giving *authenticity*; **branch protection requiring signatures** and **push protection** give *policy enforcement*; **SHA-256 repos** harden the *integrity* primitive against SHA-1's known collision weakness (mitigated meanwhile by `sha1dc` collision detection). The expert one-liner: Git guarantees *what* the content is and that it hasn't changed; it guarantees nothing about *who* made it or *whether you should trust it* — that requires signing plus policy on top.

#### Q52. [Practical] A junior engineer asks why their `git push` is rejected with "fetch first" / non-fast-forward. Diagnose and explain the fix without causing data loss.

This rejection means the remote branch has commits your local branch doesn't — someone pushed since you last fetched, so your push would *not* be a fast-forward and accepting it would either lose their work or fork history. Git refuses by default precisely to protect the other person's commits. The teaching moment is that the rejection is Git *doing its job*, and the fix is to **integrate their work first**, then push.

```bash
git push
# ! [rejected]  main -> main (fetch first)
# error: failed to push some refs ... remote contains work you do not have locally

git fetch origin                    # see what's actually on the remote
git log --oneline HEAD..origin/main # the commits you're missing
git pull --rebase                   # replay YOUR commits on top of theirs (linear)
#   ...resolve any conflicts, then:
git push                            # now a clean fast-forward
```

The critical thing to *not* teach a junior is `git push --force`, which "fixes" the error by **overwriting the remote**, discarding the colleague's commits entirely — the single most common way teams lose work. If a force is genuinely warranted (e.g. they intentionally rewrote their *own* unshared feature branch via rebase), the correct tool is **`git push --force-with-lease`**, which only succeeds if the remote is still where they last saw it — a compare-and-swap that aborts if someone else pushed in the meantime, preventing the silent clobber.

```bash
git push --force-with-lease         # safe rewrite: fails if remote moved unexpectedly
git push --force                    # DANGEROUS: clobbers whatever is there
```

The mental model I'd give them: think of the remote branch as a shared document where Git enforces "no overwriting changes you haven't seen." A non-fast-forward rejection is that rule firing. The default workflow is *always* fetch/pull-rebase-then-push; force is reserved for branches only you work on, and even then `--force-with-lease`. Pair this with **branch protection** on `main` (which blocks force-push outright) so the dangerous option isn't even available where it matters most.

## 🧩 Extended Questions — Set 2: Coding, Design & Expert Scenarios

### 🟢 Basic — extended

#### Q53. [Coding] Write the exact command sequence to initialize a brand-new project, make your first commit, and connect it to an empty GitHub repo.

**Problem:** You have a local folder with code and an empty repo created on GitHub. Wire them together correctly the first time, with the right default branch name and a tracked first commit.

```bash
cd my-project
git init                                  # creates .git/, default branch from init.defaultBranch
git branch -m main                        # ensure the branch is named 'main' (older Git defaults to master)
git add .                                 # stage everything (respecting .gitignore)
git commit -m "Initial commit"            # first snapshot
git remote add origin git@github.com:me/my-project.git
git push -u origin main                   # -u sets upstream so future `git push`/`pull` need no args
```

The two flags people forget are the ones that matter most. **`git branch -m main`** guards against the `master`/`main` mismatch — if your local default differs from GitHub's, you can end up pushing a `master` branch into a repo whose default is `main`, leaving the repo looking empty in the UI. **`-u` (`--set-upstream`)** on the first push binds `main` to `origin/main`, which is what lets bare `git push` and `git pull` work afterward; without it Git nags you with "no upstream branch" every time.

```bash
git config --global init.defaultBranch main   # set the default ONCE, machine-wide
git remote -v                                  # verify the remote URL/direction
git branch -vv                                 # confirm upstream tracking is set
```

A subtle gotcha: if the GitHub repo was created *with* a README/license (not truly empty), the first `push` is rejected as non-fast-forward because the remote already has a commit your local doesn't. The fix is `git pull --rebase origin main` first to bring that initial commit in, then push — never `--force`, which would discard GitHub's auto-generated commit.

#### Q54. [Coding] Write a useful set of Git aliases and explain what each one buys you.

**Problem:** Long Git commands are typed dozens of times a day. Build a small alias set that encodes good habits (safe defaults, readable logs) so the right behavior is the easy default.

```bash
git config --global alias.st  "status -sb"
git config --global alias.co  "checkout"
git config --global alias.cm  "commit -m"
git config --global alias.last "log -1 HEAD --stat"
git config --global alias.unstage "restore --staged"
git config --global alias.lg "log --oneline --graph --decorate --all"
git config --global alias.pushf "push --force-with-lease"   # NEVER alias plain --force
# shell-out alias (prefix with !) to run arbitrary commands:
git config --global alias.cleanup '!git branch --merged main | grep -v "\\*\\|main" | xargs -r git branch -d'
```

Aliases live in `~/.gitconfig` under `[alias]`. There are two kinds: **internal** aliases (`st = status -sb`) that just expand to a Git subcommand, and **shell aliases** (prefixed with `!`) that run an arbitrary command — the latter unlocks pipelines like the `cleanup` alias above, which deletes every local branch already merged into `main`. The reason to alias `pushf` to `--force-with-lease` rather than `--force` is behavioral design: you want the *safe* force to be the one your muscle memory reaches for, so an accidental clobber of a teammate's work is impossible by default.

```ini
# Equivalent ~/.gitconfig section (editing the file directly also works)
[alias]
    st = status -sb
    lg = log --oneline --graph --decorate --all
    pushf = push --force-with-lease
```

One caveat to mention: shell aliases run via `sh -c`, so quoting on Windows (`cmd`/PowerShell) versus a POSIX shell differs — test them, and prefer storing complex logic in a versioned script the alias calls rather than cramming multi-line shell into the config.

#### Q55. [Practical] What is the difference between `git switch`/`git restore` and the old `git checkout`, and why were they split out?

`git checkout` was historically **overloaded** — the same command switched branches, created branches, restored files from the index, restored files from another commit, and detached HEAD, all depending on the arguments and flags. That ambiguity caused real accidents: `git checkout <file>` silently *discards* your uncommitted changes to that file (no confirmation), while `git checkout <branch>` switches branches — one typo away from very different, sometimes destructive, outcomes. Git 2.23 split the two intents into purpose-built commands.

```bash
# Switching / creating branches  → git switch
git switch main                    # change branch (was: checkout main)
git switch -c feature              # create + switch (was: checkout -b)
git switch -                       # toggle to previous branch
git switch --detach <sha>          # explicit, intentional detached HEAD

# Restoring file contents          → git restore
git restore file.txt               # discard working-tree changes (was: checkout -- file)
git restore --staged file.txt      # unstage (was: reset HEAD file)
git restore --source=HEAD~2 app.js # pull a file's content from an older commit
```

The "why" is **intent clarity and safety**. `git switch` only deals with branches and refuses to silently throw away work; `git restore` only deals with file contents and is explicit about its source and target (working tree vs index). `checkout` still exists for backward compatibility and isn't deprecated, but for new habits and especially for teaching juniors, `switch`/`restore` make the dangerous "discard my changes" operation visible and deliberate instead of a side effect of a branch command.

### 🟡 Intermediate — extended

#### Q56. [Coding] Write a `commit-msg` hook that enforces Conventional Commits, and explain how to distribute it to the team.

**Problem:** You want every commit message to follow Conventional Commits (`feat:`, `fix:`, `chore:`...) so you can auto-generate changelogs and drive semantic-release. Enforce the format at commit time.

```bash
#!/bin/sh
# .githooks/commit-msg  — receives the path to the commit message file as $1
pattern='^(feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert)(\([a-z0-9-]+\))?!?: .{1,72}'
if ! grep -qE "$pattern" "$1"; then
  echo "✖ Commit message must follow Conventional Commits:"
  echo "  <type>(<optional-scope>): <subject>   e.g. 'feat(auth): add token refresh'"
  exit 1                        # non-zero exit aborts the commit
fi
```

The mechanics: `commit-msg` runs *after* you write the message but *before* the commit is finalized, and Git passes it the path to a temp file holding the message. Returning non-zero aborts the commit. The regex enforces a known type, an optional `(scope)`, an optional `!` (breaking-change marker), and a subject of 1–72 chars. The crucial distribution problem is that `.git/hooks/` is **not** tracked or cloned, so a hook you write locally protects only you. Point Git at a versioned directory instead:

```bash
git config core.hooksPath .githooks    # repo-tracked hooks dir (run once per clone, or scripted in setup)
chmod +x .githooks/commit-msg          # must be executable on POSIX
```

The honest caveat to state in an interview: any client-side hook is **advisory** — `git commit --no-verify` bypasses it, and a fresh clone won't have `core.hooksPath` set until someone runs your bootstrap script. So for real enforcement you mirror this check in **CI** (a job that lints the PR's commit messages or the PR title) and in **branch protection** (require that status check). The client hook gives fast local feedback; CI gives the guarantee. Tools like **Husky** + **commitlint** package exactly this pattern for JS repos.

#### Q57. [Coding] Write a GitHub Actions workflow that runs tests on every PR and blocks merge if they fail.

**Problem:** Set up CI so that opening or updating a pull request runs the test suite, and a red build prevents merging via branch protection.

```yaml
# .github/workflows/ci.yml
name: CI
on:
  pull_request:
    branches: [ main ]
  push:
    branches: [ main ]
permissions:
  contents: read              # least privilege — this job only reads the repo
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4    # pin to SHA in production; tag shown for brevity
        with:
          fetch-depth: 0             # full history if you diff against base; 1 is faster otherwise
      - uses: actions/setup-node@v4
        with:
          node-version: 20
          cache: npm
      - run: npm ci                  # reproducible install from lockfile (not `npm install`)
      - run: npm test
```

The workflow triggers on `pull_request` (so forks/branches get checked) and on `push` to `main` (so the post-merge state is verified too). The key design choices: `npm ci` (not `npm install`) installs *exactly* the lockfile for reproducibility; `permissions: contents: read` follows least privilege so a compromised dependency can't push; and `cache: npm` plus `setup-node`'s built-in caching keeps runs fast. But the workflow **alone doesn't block anything** — a failing check is only advisory until you make it *required*.

The enforcement half lives in **GitHub branch protection** (Settings → Branches → add rule for `main`): enable "Require status checks to pass before merging" and select the `test` job as required, plus "Require branches to be up to date before merging" so a PR can't merge against a stale base that would have failed. With that, the red X on the PR genuinely prevents the merge button from going green. The interview point is the **two-part design**: the workflow *produces* a signal; branch protection *acts on* it — people often build the first half and wonder why merges still slip through.

#### Q58. [Coding] Using only the `gh` CLI, script the full flow: create a branch, open a PR, wait for checks, and merge it.

**Problem:** Automate a routine change end-to-end from the terminal without touching the web UI — useful for scripted/repeatable changes and for understanding what the buttons actually do.

```bash
git switch -c chore/bump-deps
# ... make changes ...
git commit -am "chore: bump dependencies"
git push -u origin chore/bump-deps

# Open a PR (title/body inline; --fill uses commit messages)
gh pr create --base main --head chore/bump-deps \
  --title "chore: bump dependencies" \
  --body "Routine dependency bump. CI must pass."

gh pr checks --watch          # block until all CI checks finish, stream status
gh pr merge --squash --auto --delete-branch   # auto-merge once checks+reviews pass, then delete branch
```

The `gh` CLI talks to the GitHub REST/GraphQL API using your stored auth, so each command maps to a UI action: `gh pr create` opens the PR, `gh pr checks --watch` polls the check runs and blocks your script until they settle (exiting non-zero if any fail — handy for `&&` chaining), and `gh pr merge --auto` is the powerful one: it sets **auto-merge**, meaning GitHub itself merges the PR the moment all required checks pass and required reviews are in, even if that's an hour later. `--delete-branch` cleans up the head branch post-merge.

```bash
gh pr status                  # see PRs relevant to you
gh pr view --web              # open the current branch's PR in a browser
gh pr checkout 1234           # check out someone else's PR locally to review/test
gh run watch                  # follow the latest workflow run live
```

Why script it this way: `--auto` plus required checks means you don't sit and babysit — you fire the merge intent and walk away, and branch protection still guarantees nothing merges red. The scripting angle also matters for **bots and chatops**: this exact sequence is what a "merge when green" automation or a dependency-update bot runs. One caveat: `--auto` requires that the repo has auto-merge enabled in settings and that a merge method is allowed; otherwise `gh` errors, which is the correct fail-fast behavior.

#### Q59. [Coding] Write a script that finds and deletes all local branches whose remote tracking branch has been deleted (merged & gone on GitHub).

**Problem:** After PRs merge and GitHub deletes the head branches, your local repo accumulates dozens of stale branches whose `origin/*` counterpart no longer exists. Prune them safely.

```bash
# Step 1: update remote-tracking refs and DROP refs for deleted remote branches
git fetch --prune                      # marks gone branches as "[gone]" locally

# Step 2: list local branches whose upstream is gone, then delete them
git branch -vv \
  | grep ': gone]' \
  | awk '{print $1}' \
  | grep -v '^\*' \
  | xargs -r git branch -d             # -d = safe (refuses if unmerged)
```

The mechanism hinges on `git fetch --prune`: a remote branch that was deleted on GitHub still has a local `origin/<branch>` ref until you prune, after which `git branch -vv` annotates the orphaned local branch with `: gone]`. The pipeline parses those lines, strips the current-branch marker (`*`), and feeds them to `git branch -d`. Using **`-d` not `-D`** is the safety choice: `-d` refuses to delete a branch whose commits aren't merged into its upstream or HEAD, so a branch with unpushed work is protected, whereas `-D` would force-delete and could lose commits.

```bash
# Configure fetch to always prune, so you never see ghost branches:
git config --global fetch.prune true

# Preview-only version (dry run) before trusting the deletion:
git branch -vv | grep ': gone]' | awk '{print $1}'
```

Edge cases worth flagging: the script can't tell "merged via squash" from "abandoned" perfectly, because a squash-merge creates a *new* commit on `main` and the branch's original commits are technically unmerged — so `-d` may refuse to delete a squash-merged branch even though it's done. For squash-heavy workflows you either verify the PR state via `gh pr list --state merged` or accept using `-D` only after confirming the PR merged. Never blindly `-D` in a loop; the whole point is to keep the *safe* form as the default.

#### Q60. [Practical] Explain refspecs in depth — what does `+refs/heads/*:refs/remotes/origin/*` mean, and how do you fetch/push non-default refs?

A **refspec** tells Git how to map refs between a remote and your local ref namespace, in the form `[+]<src>:<dst>`. The line in `.git/config` under your remote — `fetch = +refs/heads/*:refs/remotes/origin/*` — is read as: take every branch on the remote (`refs/heads/*`), and store each under your remote-tracking namespace (`refs/remotes/origin/*`). The leading **`+`** means "force update even if it's not a fast-forward," which is correct for remote-tracking refs (they should always mirror the remote, even after the remote rewrote a branch).

```bash
# Fetch a specific branch into a custom local ref
git fetch origin refs/heads/release:refs/heads/local-release

# Fetch all GitHub PR heads (a non-default ref namespace) into a tidy local space
git fetch origin '+refs/pull/*/head:refs/remotes/origin/pr/*'

# Push local 'main' to a DIFFERENTLY named remote branch
git push origin main:production           # src:dst — deploy by pushing to a branch
git push origin :stale-branch             # empty src = DELETE the remote branch
git push origin HEAD                       # push current branch to its same-named remote branch
```

The directionality is the part that trips people up. On **fetch**, `src` is the remote side and `dst` is your local side. On **push**, it reverses: `src` is your local ref and `dst` is the remote ref. This is why `git push origin main:production` pushes your local `main` *onto* the remote's `production` branch — a common lightweight deploy trick. And `git push origin :branch` (empty source) is the canonical "delete the remote branch" form, because you're pushing "nothing" into that remote ref.

Understanding refspecs demystifies a lot of "magic": why `git push` knows where to go (the `push` refspec or `push.default` config), how CI fetches PR refs that aren't normal branches, and how mirror clones (`+refs/*:refs/*`) replicate *every* ref including tags and notes. When someone asks "how do I fetch just the tags" or "why did my fetch not get that branch," the answer is always: look at the configured refspec.

#### Q61. [Practical] Design a `.gitignore` strategy for a polyglot monorepo, and explain the precedence rules that make patterns surprising.

In a monorepo mixing, say, a Node frontend, a Python service, and Terraform infra, a single root `.gitignore` becomes a sprawling mess and over-ignores across language boundaries. The better design is **layered, scoped ignore files**: a small root file for truly global junk, plus per-directory `.gitignore` files owned by each subproject. Git applies ignore rules **hierarchically** — a `.gitignore` affects its directory and everything below it, and a deeper file's patterns combine with (and can override via negation) shallower ones.

```gitignore
# /.gitignore  — only universal cruft
.DS_Store
*.log
.env
.idea/
.vscode/

# /frontend/.gitignore
node_modules/
dist/
.next/

# /services/api/.gitignore
__pycache__/
*.pyc
.venv/

# /infra/.gitignore
.terraform/
*.tfstate
*.tfstate.*
!*.tfstate.example     # negation: KEEP the example state
```

The precedence rules that surprise people: (1) **later patterns override earlier ones**, so a negation (`!pattern`) only works if the file wasn't already excluded by a parent-directory pattern that ignores the whole folder — you cannot re-include a file if its *parent directory* is ignored (Git never descends into it). (2) A **trailing slash** (`build/`) matches only directories; without it (`build`) it matches files and dirs. (3) A **leading slash** (`/dist`) anchors to that `.gitignore`'s directory, while an unanchored pattern (`dist`) matches at any depth below.

```bash
git check-ignore -v path/to/file       # tells you WHICH rule (file + line) ignores a path
git status --ignored                    # show ignored files explicitly
```

The debugging tool every senior reaches for is **`git check-ignore -v`**, which prints the exact ignore file and line responsible for a path — indispensable when "why is this file being ignored / not ignored" wastes an hour. The strategic point: keep ignore rules close to the code they describe (subproject ownership), reserve the root file for genuinely global patterns, and remember that ignore rules are powerless over already-tracked files (`git rm --cached` first).

### 🟠 Advanced — extended

#### Q62. [Coding] Write a pre-receive (server-side) hook that rejects pushes containing large files or to a protected branch.

**Problem:** You run a self-hosted Git server and need *unbypassable* enforcement (clients can `--no-verify` their own hooks, but they cannot bypass server-side ones). Reject any push that adds a file over 25 MB or attempts to push directly to `main`.

```bash
#!/bin/sh
# hooks/pre-receive — reads "<old-sha> <new-sha> <ref>" lines on stdin, one per pushed ref
max_bytes=$((25 * 1024 * 1024))
while read old new ref; do
  # 1) Block direct pushes to main
  if [ "$ref" = "refs/heads/main" ]; then
    echo "✖ Direct pushes to main are forbidden — open a PR."; exit 1
  fi
  # 2) Reject oversized blobs introduced by this push
  for sha in $(git rev-list "$old".."$new" 2>/dev/null || git rev-list "$new"); do
    git diff-tree -r --no-commit-id --name-only "$sha" | while read f; do
      blob=$(git rev-parse "$sha:$f" 2>/dev/null) || continue
      size=$(git cat-file -s "$blob" 2>/dev/null || echo 0)
      if [ "$size" -gt "$max_bytes" ]; then
        echo "✖ $f is ${size} bytes (> 25MB) in commit $sha — use Git LFS."; exit 1
      fi
    done
  done
done
exit 0
```

The crucial design facts: `pre-receive` runs **once per push** (not per ref) on the *server*, receiving the list of ref updates on stdin as `old new ref` triples, and the push is **atomic** — if the hook exits non-zero, *no* refs are updated, so it's all-or-nothing. The `$old".."$new` range enumerates exactly the new commits being introduced; for a brand-new branch `$old` is all-zeros, which is why the fallback to `git rev-list "$new"` handles the "first push of a branch" edge case.

```
client push ──▶ server receives pack ──▶ pre-receive hook (stdin: old new ref)
                                              │ exit 0 → refs updated atomically
                                              │ exit ≠0 → ENTIRE push rejected
                                          update (per-ref) ──▶ post-receive (notify/deploy)
```

Why server-side is the only real enforcement: client hooks live in `.git/hooks`, aren't cloned, and are trivially skipped with `--no-verify`; the server hook runs in an environment the pusher doesn't control. On **GitHub specifically** you can't install raw server hooks — the equivalent is **branch protection rules**, **push protection** (secret scanning blocks pushes containing secrets), **file-size limits** (100 MB hard cap), and **rulesets**. The portable lesson: enforcement belongs where the untrusted client can't reach it.

#### Q63. [Coding] Write a `git filter-repo` invocation (and a callback) to rewrite author emails across all history, and explain the fallout management.

**Problem:** A team migrated repos and half the history has commits authored under personal Gmail addresses instead of corporate ones, breaking GitHub's "Verified" linkage and contribution graphs. Rewrite the author/committer emails across *all* history.

```bash
pip install git-filter-repo

# Option A — declarative mailmap (preferred for simple name/email remaps):
cat > mailmap.txt <<'EOF'
Corp Name <jane@corp.com> <jane@gmail.com>
Corp Name <jane@corp.com> Jane J <jane.j@old.com>
EOF
git filter-repo --mailmap mailmap.txt

# Option B — programmatic callback for conditional logic:
git filter-repo --commit-callback '
    if commit.author_email == b"jane@gmail.com":
        commit.author_email = b"jane@corp.com"
        commit.author_name  = b"Jane Corp"
    if commit.committer_email == b"jane@gmail.com":
        commit.committer_email = b"jane@corp.com"
'
```

Two facts make `filter-repo` the right tool over the deprecated `filter-branch`: it's **orders of magnitude faster** (single pass, written in Python over a fast plumbing stream) and it **rewrites both author and committer** plus updates tags and refs consistently. The `--mailmap` form is declarative and ideal for pure identity remaps (it's the same `.mailmap` format Git's `log`/`shortlog` already understand); the **callback** form handles conditional logic (rewrite only commits in a date range, only certain paths, etc.) by mutating the `commit` object in Python.

The fallout is the hard part, and it's identical to any history rewrite: **every commit hash from the earliest rewritten commit onward changes**, so all existing clones diverge, open PRs break, and tags move. The runbook: (1) coordinate a freeze, (2) run `filter-repo` on a fresh `--mirror` clone, (3) force-push all branches and tags (`git push --force --mirror`), (4) have everyone **re-clone** (re-basing old clones onto the rewritten history is error-prone), (5) re-protect branches and re-open/retarget PRs. `filter-repo` deliberately **refuses to run on a non-fresh clone** by default (it wants `--force` or a fresh mirror) precisely to stop you from half-rewriting a repo people are actively using. The senior framing: a history-wide email rewrite is a coordinated migration event, not a casual command — schedule it, communicate it, and do it once.

#### Q64. [Practical] Design a multi-repo release automation using tags, GitHub Releases, and Actions triggered on tag push.

**Problem:** Several services should each build, test, and publish a versioned artifact + GitHub Release whenever a maintainer pushes a semantic-version tag, with the version derived from the tag and provenance attached.

```yaml
# .github/workflows/release.yml
name: Release
on:
  push:
    tags: [ 'v*.*.*' ]          # fires only on semver tags like v2.3.1
permissions:
  contents: write               # needed to create a Release
  id-token: write               # OIDC for keyless signing / cloud auth
jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }            # full history so changelog/describe works
      - name: Derive version from tag
        run: echo "VERSION=${GITHUB_REF_NAME#v}" >> "$GITHUB_ENV"
      - run: make build VERSION=$VERSION
      - run: make test
      - name: Create GitHub Release with notes
        run: gh release create "$GITHUB_REF_NAME" \
               --generate-notes dist/*.tar.gz
        env: { GH_TOKEN: ${{ github.token }} }
      - uses: actions/attest-build-provenance@v1   # SLSA provenance attestation
        with: { subject-path: 'dist/*.tar.gz' }
```

The architecture's backbone is the **tag-as-trigger**: pushing an annotated, signed tag (`git tag -s v2.3.1 && git push origin v2.3.1`) is the single human action that kicks off everything. `on: push: tags: ['v*.*.*']` constrains the trigger to semver tags so arbitrary tags don't deploy. The version flows from the tag name (`GITHUB_REF_NAME`) into the build, guaranteeing the artifact's version *equals* the Git tag — no drift between "what's tagged" and "what's built." `gh release create --generate-notes` auto-builds release notes from merged PRs since the previous tag.

```
maintainer: git tag -s v2.3.1 ; git push --tags
        │
        ▼  (on: push tags v*.*.*)
   build → test → gh release create → upload assets → attest provenance
        │
   per-repo workflow; org-level reusable workflow keeps them DRY
```

For the **multi-repo** dimension, the design choice is a **reusable workflow** (`workflow_call`) hosted in a central `.github` or `org-workflows` repo, which each service references — so the release logic is defined once and versioned, not copy-pasted into N repos that drift. Security and provenance hardening: signed tags prove *who* cut the release, `id-token: write` + `attest-build-provenance` produces **SLSA attestations** binding the artifact to the workflow that built it, and `permissions` is scoped to the minimum. The trade-off to articulate: centralizing the workflow improves consistency and auditability but couples all repos to its versioning — so you pin the reusable workflow to a SHA/tag per repo and bump deliberately.

#### Q65. [Coding] Use `git rev-list`, `git cat-file`, and `git verify-pack` to find the largest objects bloating a repo. Write the one-liner.

**Problem:** A repo's `.git` directory is huge and clones are slow. Identify the biggest blobs in history (often a long-deleted binary) so you know what to purge with `filter-repo`/LFS.

```bash
# Find the top 10 largest objects in the packfiles, resolved to their path
git rev-list --objects --all \
  | git cat-file --batch-check='%(objecttype) %(objectname) %(objectsize) %(rest)' \
  | awk '/^blob/ {print $3, $4}' \
  | sort -rn \
  | head -10 \
  | numfmt --field=1 --to=iec       # human-readable sizes (e.g. 210M)
```

The pipeline is a small masterclass in plumbing. `git rev-list --objects --all` walks **every** reachable object across all refs and prints `<oid> <path>` pairs. Piping into `git cat-file --batch-check` (a fast batch mode that reads OIDs on stdin and prints metadata) yields the type, oid, *size*, and the path (`%(rest)` carries through the path from rev-list). `awk` keeps only blobs and emits `size path`; `sort -rn | head` gives the worst offenders; `numfmt` makes the bytes readable. This finds objects bloating history **even if the file was deleted long ago**, because deleted files still live in old commits.

```bash
# Cross-check what's in the packs directly, sorted by size:
git verify-pack -v .git/objects/pack/pack-*.idx \
  | sort -k3 -rn | head -10
git count-objects -vH                 # total repo size, loose vs packed
```

`git verify-pack -v` dumps every object in a pack with its **packed (delta-compressed) size** in column 3 — useful because a blob that looks huge uncompressed might delta well, and vice versa. The reason this matters: large binaries don't delta-compress, so each version is stored nearly in full and bloats the pack *permanently* — deleting the file in a new commit does nothing for repo size. Once identified, the fix is history surgery (`git filter-repo --strip-blobs-bigger-than 25M` or `--path`) plus migrating ongoing large assets to **Git LFS**, followed by the usual force-push-and-re-clone coordination.

#### Q66. [Practical] Explain `git rerere`, the commit-graph, `git maintenance`, and `fsmonitor` — the "quality of life at scale" features and when to enable each.

These are four features that don't change *what* Git does but make it dramatically faster or less painful on real-world repos. **`rerere`** ("reuse recorded resolution") records how you resolved a given conflict (keyed by the base/ours/theirs hunks) and **auto-applies that resolution** if the identical conflict recurs — a lifesaver during long rebases or when repeatedly merging a long-lived branch, where you'd otherwise resolve the same conflict over and over.

```bash
git config --global rerere.enabled true        # remember conflict resolutions
git config --global maintenance.auto false     # disable ad-hoc auto-gc...
git maintenance start                          # ...and run scheduled background maintenance instead
git config core.fsmonitor true                 # use a filesystem monitor for instant `git status`
git config feature.manyFiles true              # bundles index v4 + untracked cache for huge worktrees
```

**`git maintenance`** is the modern successor to ad-hoc `git gc`: it registers background jobs (cron/launchd/Task Scheduler) that incrementally repack, write the **commit-graph**, prefetch from remotes, and expire reflogs — on a schedule, so you never get a surprise multi-minute `gc` blocking a commit. The **commit-graph** itself (a cache of parents, generation numbers, and tree OIDs) accelerates history traversal — `git log --graph`, `git merge-base`, ancestry checks — by avoiding parsing thousands of commit objects, which is decisive on repos with deep history.

**`fsmonitor`** addresses the single most common "Git feels slow" complaint: `git status` on a huge working tree has to `lstat` every file to detect changes. With `core.fsmonitor true`, Git talks to a filesystem-watcher daemon (built-in since 2.37, or Watchman) that *tells* it which files changed since last time, turning an O(files) scan into O(changed files). The decision guide: enable **`rerere`** always (pure upside); enable **`maintenance`** on any repo you work in daily and on servers; enable **`fsmonitor`** + `feature.manyFiles` on large monorepos where `status`/`add` lag. The unifying theme: Git's data model is traversal- and stat-heavy, and these features are caches/daemons that trade a little disk and a background process for large interactive speedups.

#### Q67. [Coding] Write a sparse-checkout + partial-clone setup so a developer only materializes one service from a 200-directory monorepo.

**Problem:** A monorepo has 200 top-level service directories and a full checkout is 30 GB. A developer working on `services/payments` should clone fast and see only the files they need, while still being able to build (they need a couple of shared libs too).

```bash
# 1) Partial clone (no blobs yet) + cone-mode sparse (fast pattern matching), no initial checkout
git clone --filter=blob:none --sparse https://github.com/org/monorepo.git
cd monorepo

# 2) Declare the subset of the tree to materialize (cone mode = directory-based, fast)
git sparse-checkout init --cone
git sparse-checkout set services/payments libs/common libs/proto

# 3) Now the working tree contains ONLY those dirs (plus top-level files); blobs fetched on demand
git checkout main
git sparse-checkout list                 # show active patterns
git sparse-checkout add services/billing # widen the cone later when you touch another service
```

Two orthogonal optimizations combine here. **Partial clone** (`--filter=blob:none`) downloads all commits and trees but **no file contents** up front; blobs are lazily fetched from the *promisor remote* only when a command actually needs them (checkout, diff). **Sparse-checkout** (`--sparse` + `set`) limits which paths get *written to the working directory* — in **cone mode** (`--cone`) the patterns are whole directories, which Git can match with fast prefix logic instead of evaluating gitignore-style globs against every path (the older non-cone mode is flexible but slow on big trees).

```
full repo (200 dirs, 30GB)        sparse + partial clone
 ┌──────────────────────┐          ┌──────────────────────┐
 │ services/* (200)     │   ──▶    │ services/payments ✓   │  ← in working tree
 │ libs/* (40)          │          │ libs/common ✓         │
 │ ...                  │          │ libs/proto ✓          │
 └──────────────────────┘          │ (everything else: in  │
   all blobs downloaded             │  history, not on disk)│
                                    └──────────────────────┘
   blobs fetched lazily on demand from the promisor remote
```

The trade-offs to state: the developer's clone is small and fast, but operations that touch absent paths (`git grep` across everything, a checkout of an old commit with different files) trigger **on-demand network fetches** and fail offline. Builds work *if* you sparse-set the transitive dependencies (hence including `libs/common`/`libs/proto`) — getting that set right is the practical friction, which is why monorepo tooling (Bazel, Nx) often generates the sparse set from the dependency graph. This is exactly the model behind Microsoft's Scalar/VFS-for-Git and how huge monorepos stay workable on a laptop.

#### Q68. [Practical] An interviewer hands you a corrupted/odd history scenario: two branches share no common ancestor ("unrelated histories"). How did it happen and how do you reconcile them?

The error `fatal: refusing to merge unrelated histories` means the two branches have **no common ancestor commit** — their root commits are different, so the three-way merge algorithm has no merge base to diff against. This usually happens from one of a few origins: someone ran `git init` and committed in a project that was then connected to a remote that already had its own initial commit; a repo was re-initialized losing its history; two independently-started repos are being combined; or a `git clone` + fresh `git init` mix-up.

```bash
# Diagnose: do they actually share an ancestor?
git merge-base main other-root        # prints nothing / errors if no common ancestor
git log --oneline main | tail -1      # compare the ROOT commit hashes of each
git log --oneline other-root | tail -1

# If joining them is intended, explicitly allow it:
git merge other-root --allow-unrelated-histories
# ...likely many conflicts since the trees evolved separately; resolve them.
```

The `--allow-unrelated-histories` flag (required since Git 2.9, which made refusing the default to catch the *accidental* version of this) tells Git to merge anyway, treating the empty tree as the implicit base — which means nearly every file that exists in both sides with different content conflicts, because there's no shared origin to auto-merge from. So the flag is correct only when joining the histories is *genuinely intended* (e.g. merging a docs repo into a code repo as a subdirectory).

The senior move is to **diagnose root cause before reconciling**. If this appeared accidentally — a developer's local repo diverged from origin because they re-`init`'d — the right fix is usually *not* to merge unrelated histories but to figure out which history is canonical and re-clone/re-apply work onto it (cherry-picking the few real commits across), avoiding a Frankenstein history with a spurious merge of two unrelated roots. When the join *is* intentional (combining repos), prefer `git subtree add` or `git read-tree` into a subdirectory over a flat `--allow-unrelated-histories` merge, so the incoming project lands cleanly under its own path with traceable history.

### 🔴 Expert — extended

#### Q69. [Theory] Explain the on-disk anatomy of `.git`: loose objects, packfiles, the index format, `packed-refs`, and the emerging reftable backend.

The `.git` directory is a small, well-defined database, and knowing its layout turns "Git magic" into "files I can inspect." Objects live in `objects/`: **loose objects** are individual zlib-compressed files at `objects/ab/cdef...` (sharded by the first two hex chars of the OID), created by normal commits; **packfiles** (`objects/pack/pack-*.pack` + `.idx`) consolidate many objects with delta compression after `gc`/repack. The **index** (`.git/index`) is a *binary* file — not a ref — listing every tracked path with its blob OID, stat data (mtime, size, inode), and stage number; it *is* the staging area, and its v4 format plus the untracked-cache are what `feature.manyFiles` tunes.

```
.git/
├── HEAD                 → "ref: refs/heads/main"  (symbolic ref)
├── index                → binary staging area (tracked paths + stat cache)
├── config               → repo-local config
├── objects/
│   ├── ab/cdef…         → loose object (one file per object)
│   ├── pack/pack-*.pack → delta-compressed object database
│   ├── pack/pack-*.idx  → offset index into the pack
│   ├── pack/*.bitmap    → reachability bitmaps (negotiation speedup)
│   └── info/commit-graph→ cached parents/generation numbers
├── refs/heads/main      → loose ref: a 40/64-char OID
├── packed-refs          → many refs in ONE file (perf for thousands of refs)
└── logs/                → reflogs (HEAD + per-branch)
```

Refs have an interesting performance story. A ref is normally a one-line file under `refs/` containing an OID — fine for a handful, but a repo with **hundreds of thousands of refs** (tags, PR refs, CI refs) would create hundreds of thousands of tiny files, murdering filesystem performance. The classic mitigation is **`packed-refs`**: `git pack-refs` collapses many loose refs into a single sorted file, with loose files taking precedence when both exist. The modern successor (Git 2.45+, experimental) is the **reftable** backend — a binary, block-based, indexed format (borrowed from JGit/Gerrit) that handles millions of refs and atomic transactional updates far better than the loose+`packed-refs` scheme, which is why it's the planned default for huge-ref repos.

The expert payoff of knowing this: when diagnosing weirdness you can read these files directly (`cat .git/HEAD`, `git cat-file -p`, `git ls-files --stage`, inspect `packed-refs`), you understand *why* certain operations are slow (loose-object explosion, ref-count blowup) and which feature fixes each (`gc`, `pack-refs`, reftable, commit-graph), and you can reason about corruption (a truncated `index`, a half-written pack) and recovery (refetch from any complete clone).

#### Q70. [Theory] How does `git push --signed` / signed pushes work, and how does it differ from signed commits and signed tags?

There are three distinct signing surfaces in Git, and conflating them is a common gap. **Signed commits** (`git commit -S`) and **signed tags** (`git tag -s`) attach a GPG/SSH/X.509 signature *to the object*, binding its content to a key — this is durable and travels with the object forever, proving *who created this commit/tag*. **Signed pushes** (`git push --signed`) are different in kind: they sign the **push certificate** — a transient document stating "principal X is updating ref R from old-OID to new-OID at this time on this server" — which the *server* can verify and log, proving *who performed this specific ref update*, not who wrote the commits.

```bash
git commit -S -m "msg"              # sign the COMMIT object (who authored it)
git tag -s v1.0 -m "release"        # sign the TAG object (who cut the release)
git push --signed origin main       # sign the PUSH (who moved the ref, when)
git config gpg.format ssh           # use SSH keys instead of GPG (2.34+)
git verify-commit <sha>             # verify a commit signature
git log --show-signature            # show per-commit verification status
```

The reason signed pushes exist separately is **non-repudiation of the action**, not the content. A signed commit doesn't prove who *pushed* it — anyone with write access could push commits authored (and signed) by someone else, or replay an old signed commit. The push certificate, captured server-side (e.g. by a `post-receive` hook reading `GIT_PUSH_CERT`), gives an auditable trail: "at 14:03, key belonging to Jane advanced `refs/heads/main` from A to B." High-security and compliance setups (and Gerrit) use this so the *ref-update events* are attributable and tamper-evident, independent of who authored the underlying commits.

The full picture for an authenticity story layers all three: signed commits/tags for content authorship and release integrity, signed pushes for action auditing, plus **policy** (branch protection requiring signed commits, allow-listed signer keys via `gpg.ssh.allowedSignersFile`) that turns "signatures exist" into "unsigned work is rejected." Modern keyless variants — **gitsign/Sigstore** — replace long-lived GPG keys with short-lived certificates tied to an OIDC identity, which scales signing across an org without key-management toil. The expert one-liner: commit/tag signatures answer *who made this object*; push signatures answer *who changed this ref*; you need both plus enforcement policy for a complete provenance chain.

#### Q71. [Coding] Implement a script that produces a "what changed between two releases" report (commits, authors, files) suitable for release notes.

**Problem:** For every release you need an auto-generated report between two tags: the commit list (excluding merges), contributing authors, files changed, and the diffstat — formatted for release notes.

```bash
#!/bin/sh
# release-report.sh PREV_TAG NEW_TAG
prev="$1"; new="$2"; range="$prev..$new"

echo "## Changes $prev → $new"
echo
echo "### Commits"
git log "$range" --no-merges --pretty='- %s (%h) — %an'

echo
echo "### Contributors"
git shortlog -sne "$range"          # -s summary, -n by count, -e with email

echo
echo "### Files changed"
git diff --stat "$prev" "$new" | tail -1     # the summary line: N files, +X -Y

echo
echo "### Full diffstat"
git diff --stat "$prev" "$new"
```

The backbone is the **two-dot range** `prev..new`, which means "commits reachable from `new` but not from `prev`" — exactly the set of work added since the last release. `--no-merges` strips merge commits so the list shows actual changes, not integration noise (especially important with merge-commit workflows). `git shortlog -sne` is the underused gem: it groups commits by author with counts, which is precisely the "thanks to these contributors" section release notes want.

```bash
# Compare two versions of the SAME branch (e.g. before/after a force-push or rebase):
git range-diff @{u}...HEAD          # shows how commits were reworded/reordered/dropped
# GitHub-native equivalent:
gh release create v2.0.0 --generate-notes --notes-start-tag v1.9.0
```

Two expert add-ons worth mentioning. **`git range-diff`** answers a different question than `diff`: given two *versions of a branch* (e.g. before and after a rebase), it pairs up corresponding commits and shows how each changed — invaluable for reviewing a force-pushed PR ("what did they actually alter in v2 of this branch?"). And in practice you rarely hand-roll the whole thing: `gh release --generate-notes` and tools like `git-cliff`/`semantic-release` build this from **Conventional Commit** prefixes, which is the payoff for enforcing commit-message format (Q56) — structured commits make automated, categorized changelogs (`feat`/`fix`/`breaking`) essentially free.

#### Q72. [Practical] Walk through using `git bundle` for air-gapped or offline repo transfer, and how it differs from a clone or a patch.

A **bundle** is a single file containing a slice of a repo's object database plus refs — effectively a portable, offline packfile you can move by USB stick, email, or scp across an air-gap where there's no network path between two Git hosts. It's the right tool when you must move history between machines that can't reach each other directly, and it preserves full commit history and integrity (unlike a flat archive of the working tree).

```bash
# Create a bundle of EVERYTHING (full repo, transferable as one file):
git bundle create repo.bundle --all

# Create an INCREMENTAL bundle (only commits since a known tag the other side already has):
git bundle create update.bundle v2.0.0..main

# On the other (air-gapped) side — verify, then clone or fetch FROM the file:
git bundle verify repo.bundle           # checks prerequisites & integrity
git clone repo.bundle local-repo        # treat the bundle like a remote
git fetch ../update.bundle main:main    # apply an incremental update
```

The mechanics: a full bundle (`--all`) is self-contained, so the receiver can `git clone` it as if it were a remote URL. An **incremental** bundle (`v2.0.0..main`) only contains commits *after* a boundary, making it tiny — but it lists the boundary commit as a **prerequisite**, and `git bundle verify` *refuses* to apply it unless the receiving repo already has that prerequisite object. This is the elegant part: incremental bundles let you ship only deltas across the air-gap on each sync, exactly like a `fetch` would, without a live connection.

How it differs from the alternatives: a **clone/fetch** needs a live network path between the two repos — impossible across an air-gap. A **`git format-patch`/`git am`** patch series carries *diffs* (and can lose merge topology, binary files, and exact hashes — it reconstructs commits, changing them), whereas a bundle carries the *actual objects*, so hashes and history are preserved bit-for-bit. The decision rule: use `format-patch` for emailing a few reviewable changes (the kernel mailing-list workflow); use **`git bundle`** when you need to faithfully move *history* (full or incremental) across a boundary Git can't traverse online — air-gapped networks, backups, or seeding a new mirror.

#### Q73. [Theory] Explain octopus merges, the `ort` merge strategy, and merge strategy options (`-X`). When is each appropriate?

Git can merge **more than two branches in a single commit** — an **octopus merge**, where the merge commit has three or more parents. `git merge a b c` creates one commit joining all of them, and Git's default for >2 heads is literally the `octopus` strategy. It's intentionally limited: octopus merges **refuse to proceed if any branch requires conflict resolution** — they only succeed when all the merges are trivially combinable. That constraint is by design, because resolving a conflict across N parents simultaneously is incomprehensible. The legitimate use is bundling several already-independent, non-conflicting topic branches into an integration branch at once (some CI/integration bots do this); for normal work, merging branches one at a time is clearer.

```bash
git merge topic-a topic-b topic-c     # octopus: one commit, multiple parents (no conflicts allowed)
git merge -s ort feature              # the default 2-branch strategy since 2.34
git merge -X ours feature             # on CONFLICTING hunks, prefer our side (not -s ours!)
git merge -X theirs feature           # on conflicting hunks, prefer their side
git merge -X ignore-all-space feature # ignore whitespace differences when merging
git merge -s ours obsolete-branch     # record a merge but KEEP ONLY our tree (discard theirs)
```

The **`ort`** strategy ("Ostensibly Recursive's Twin") replaced `recursive` as the default two-branch merge engine in Git 2.34. It produces the same merge results but is a clean rewrite that's faster (especially with renames and large trees), uses less memory, computes merges *in-memory* without touching the working tree (enabling fast server-side merge previews like GitHub's), and handles directory renames and criss-cross/multiple-merge-base cases more correctly. You rarely select it explicitly — it's the default — but knowing the name and *why* it matters (server-side merges, rename handling) is the expert signal.

The most-confused distinction is **`-s ours` (strategy) versus `-X ours` (option)**. `-X ours` is an *option to the `ort` strategy* that only affects **conflicting hunks** — non-conflicting changes from the other branch are still merged in normally; it just auto-picks your side where both changed the same lines. `-s ours` is a *whole strategy* that records a merge commit but **completely discards the other branch's content**, keeping your tree verbatim — used to mark a branch as "merged/superseded" without actually taking its changes (e.g. retiring an obsolete branch while keeping the history link). Reaching for `-s ours` when you meant `-X ours` silently throws away the other branch's work — a genuinely dangerous mix-up worth calling out.

#### Q74. [Coding] Write a `git subtree split` + push workflow to extract a subdirectory of a monorepo into its own standalone repository, preserving its history.

**Problem:** A library lives at `libs/auth/` inside a monorepo and now needs to become its own repository with **only the history of that subdirectory** — extracted cleanly so the new repo's log shows the library's real evolution, not the monorepo's.

```bash
# 1) Produce a new history containing ONLY libs/auth, rewritten as if it were the repo root:
git subtree split --prefix=libs/auth -b auth-only
#   creates branch 'auth-only' whose commits are just the libs/auth changes

# 2) Push that branch to a fresh standalone repo as its main:
git remote add auth-repo git@github.com:org/auth-lib.git
git push auth-repo auth-only:main

# 3) (Optional) keep consuming it back in the monorepo as a subtree:
git subtree pull --prefix=libs/auth auth-repo main --squash
git subtree push --prefix=libs/auth auth-repo main
```

`git subtree split --prefix=<dir>` walks history and synthesizes a **new commit chain** in which `libs/auth` is the top level — each original commit that touched that directory becomes a commit whose tree is just the subdirectory's contents, and commits that never touched it are dropped. The result is a faithful, standalone history you can push as a new repo's `main`. For very large/old monorepos, **`git filter-repo --path libs/auth --path-rename libs/auth/:`** does the same extraction faster and is the recommended modern tool; `subtree split` is convenient and built-in but slow on huge histories.

```
monorepo history                 split --prefix=libs/auth
 c1  (touch app + libs/auth)       c1' (libs/auth content as root)
 c2  (touch app only)        ──▶   (dropped — no libs/auth change)
 c3  (touch libs/auth)             c3' (libs/auth content as root)
```

The reason to prefer `subtree`/`filter-repo` over a naive "copy the folder and `git init`" is **history preservation**: blame, `git log --follow`, and authorship all survive, which matters for a library people will maintain. The trade-offs and gotchas: the extraction can be slow and should be run on a fresh clone; if files *moved into* `libs/auth` from elsewhere, their pre-move history won't be captured (it lived under a different path); and once split out, you must decide the ongoing relationship — a true split (independent repo, consumed via package manager or submodule) versus an ongoing `subtree push/pull` mirror (heavier, but lets changes flow both ways). For a clean break, split + publish as a package; for gradual decoupling, keep the subtree link temporarily.

#### Q75. [Practical] Diagnose: a developer reports `git status` shows files as modified that they never touched, and it persists across `checkout`. Walk through the causes.

This "phantom modifications" symptom has a handful of classic root causes, and the diagnostic is to figure out *what transformation* Git thinks is happening between the committed blob and the working file. The two most common culprits are **line-ending normalization** and **file-mode (permission) changes**, both of which alter how Git compares the stored content to disk even when the visible text is identical.

```bash
git diff                      # LOOK at the diff — is the whole file changed? (line endings)
git diff --stat               # many files, all "changed" → systemic (eol or filemode)
git config core.autocrlf      # CRLF/LF auto-conversion mismatch
git config core.fileMode      # if true, executable-bit changes show as modified
git diff --summary            # shows "mode change 100644 => 100755" for permission flips
git ls-files --eol            # show the eol attributes Git computed per file
```

**Line endings** are the #1 cause: a Windows checkout with `core.autocrlf=true` (or a missing `.gitattributes`) converts LF↔CRLF, so a file stored with LF appears "modified" on disk as CRLF — and `git diff` shows the *entire file* changed. The fix is a committed **`.gitattributes`** with `* text=auto` plus a one-time `git add --renormalize .` (see Q31), which makes normalization authoritative and machine-independent instead of relying on each dev's `autocrlf`. **File mode** is the #2 cause: on filesystems where the executable bit differs (or when files came from a zip, or via WSL/Windows interop), Git sees `100644 → 100755` mode changes; `git config core.fileMode false` tells Git to ignore permission bits if your platform can't represent them reliably.

```bash
# Renormalize line endings once, repo-wide:
git add --renormalize . && git commit -m "Normalize EOL via .gitattributes"
# Ignore filemode if the platform is the problem:
git config core.fileMode false
```

The subtler causes worth knowing for senior credibility: (1) a **smudge/clean filter or LFS** that isn't installed or behaves non-deterministically can make checked-out content differ from the stored blob (the "image is a 130-byte pointer" or a filter that round-trips imperfectly). (2) **Stale index stat-cache** after a filesystem change or clock skew — `git status` thinks files are dirty until it re-reads them; `git update-index --refresh` or just running `git diff` (which forces a content comparison) resolves the false positives. (3) `.git` on a **sync service or case-insensitive filesystem** causing spurious change detection. The methodical answer always starts with "show me `git diff` and `git diff --summary`" — whole-file diffs point to EOL, mode-only lines point to permissions, and content-but-not-text differences point to filters/LFS.

#### Q76. [Theory] Explain `git notes` and the multi-pack-index (MIDX) — two lesser-known features — and a real use case for each.

**`git notes`** attaches metadata to a commit **without altering the commit** (and thus without changing its hash). Because a commit is immutable and content-addressed, you can't add information to it after the fact — but notes solve this by storing the annotation in a *separate* ref namespace (`refs/notes/commits` by default), keyed by the commit's OID. `git log` then displays the note alongside the commit. The killer property is that you can annotate commits *after they're created and even after they're public*, since the commit objects are untouched.

```bash
git notes add -m "Reviewed-by: Jane; deployed in release 2.3" <sha>
git notes show <sha>
git log --show-notes                 # display notes inline with the log
git push origin 'refs/notes/*'       # notes are NOT pushed by default
git fetch origin 'refs/notes/*:refs/notes/*'
git notes --ref=ci-status add -m "build:pass" <sha>   # a separate notes namespace
```

Real use cases for notes: recording **CI/build status, deploy markers, or review metadata** against commits without polluting commit messages; **Gerrit** uses notes-like refs for review data; and migration tools stash the original SVN revision or pre-rewrite SHA in notes for traceability. The big caveat (and interview gotcha): notes live in their own ref namespace and are **not fetched/pushed by default**, and concurrent edits to notes can conflict and require a notes-merge — which is why teams that want commit metadata often prefer trailers in the message or external systems. Notes shine specifically when you must annotate *immutable, already-published* commits.

The **multi-pack-index (MIDX)** addresses a different scaling problem. As a repo accumulates many packfiles (frequent incremental repacks, or `git maintenance` doing incremental repacking instead of one giant repack), looking up an object means checking *each* pack's `.idx` in turn — O(number of packs) per lookup. The MIDX (`objects/pack/multi-pack-index`) is a **single index spanning all packfiles**, mapping each OID directly to "(which pack, what offset)" so object lookup stays fast regardless of how many packs exist. It also enables **bitmaps that span multiple packs**, so you get fast reachability negotiation without forcing everything into one monolithic pack.

```bash
git multi-pack-index write              # build/refresh the MIDX
git config core.multiPackIndex true     # use it for lookups
git maintenance start                   # incremental repack + MIDX maintenance, scheduled
```

The connection between the two: both are about Git staying fast as a repo grows in a dimension the naive design handles poorly — notes let you scale *metadata* without rewriting immutable history, and the MIDX lets you scale *many packfiles* (the natural result of incremental maintenance) without O(packs) lookups. Knowing them signals you've operated Git at scale, not just used it.

#### Q77. [Behavioral] (STAR) Tell me about a time you led a significant change to your organization's Git/branching workflow or tooling. What resistance did you face, and what was the outcome?

**Situation:** At a ~60-engineer company across six teams, every team used a different Git workflow — some GitFlow, some ad-hoc long-lived branches — and `main` for the flagship service routinely had branches 200+ commits behind. Merge conflicts consumed roughly a day per engineer per week, release cuts were error-prone (fixes existed in `develop` but never reached the tagged release), and a botched force-push had recently dropped a hotfix and caused a customer-facing regression. Leadership asked me, as staff engineer, to standardize and de-risk the workflow.

**Task:** Drive a migration to **trunk-based development with feature flags and short-lived branches**, plus enforce safety via branch protection and CI — across teams that were attached to their existing habits and skeptical that "commit to main" could be safe. The constraint: do it without freezing delivery or imposing a top-down mandate that teams would quietly ignore.

**Action:** I deliberately avoided a big-bang mandate. First I **ran a pilot** on one willing team for a month, instrumenting the metrics (PR cycle time, conflict frequency, revert count) so I'd have data, not opinions. I paired the workflow change with the *enabling* tooling — feature-flag SDK, required green CI, branch protection blocking force-push and direct pushes to `main`, squash-merge for atomic revertable history — so "do the right thing" was the path of least resistance rather than a discipline ask. The biggest resistance was the "feature flags add complexity / I want my long feature branch" camp; I addressed it by showing the pilot's conflict-time drop and by writing a short playbook plus a `pre-commit`/CI guardrail set, then **let the pilot team evangelize to peers** rather than mandating from above. I also explicitly handled the legitimate objection — the one team with a genuinely versioned, parallel-release product kept a release-branch + backport model, because forcing pure trunk on them would've been wrong; tailoring the strategy to each product's reality bought credibility.

**Result:** Over a quarter, five of six teams moved to trunk-based with flags; average PR cycle time dropped from ~3 days to under a day, conflict-resolution time fell sharply because branches now lived hours not weeks, and we had **zero force-push/history incidents** afterward because the dangerous actions were structurally blocked, not merely discouraged. The release-correctness bugs disappeared since "fix forward on trunk" eliminated the develop-never-reached-release gap. The durable lesson I carry from it: workflow change succeeds when you (1) make the safe action the easy default via tooling rather than relying on discipline, (2) prove it with a measured pilot before scaling, and (3) respect that one canonical workflow doesn't fit every product — the version-shipping team's exception was a feature of the plan, not a failure of it.

#### Q78. [Coding] Write the commands to recover a commit that exists only in a dropped stash, a deleted branch, AND an amended-away state — using `reflog` and `fsck`.

**Problem:** Three flavors of "I lost a commit" in one drill. Show the recovery path for each, demonstrating that Git almost never truly loses committed/staged work within the gc window.

```bash
# (1) Dropped stash — `git stash drop` removed the entry, but the objects survive
git fsck --no-reflogs --unreachable | grep commit     # find dangling commits
git stash list                                         # (empty now)
git log -g stash@{0} 2>/dev/null                       # if reflog still has it
git cat-file -p <dangling-sha>                         # confirm it's the stash content
git stash apply <dangling-sha>                         # re-apply the recovered stash commit

# (2) Deleted branch — `git branch -D feature` removed the ref, not the commits
git reflog                                             # HEAD reflog still shows feature's tip
# or specifically:
git reflog show feature 2>/dev/null
git switch -c feature-recovered <tip-sha>              # recreate the branch at that commit

# (3) Amended-away commit — `git commit --amend` replaced it; the ORIGINAL is orphaned
git reflog                                             # find "commit (amend)" -> the pre-amend HEAD
# e.g.  abc1234 HEAD@{1}: commit: original message before amend
git switch -c pre-amend abc1234                        # recover the original commit
```

The unifying principle is that **a commit, once created, is an immutable object in `.git/objects` that survives until garbage collection** — and `reset --hard`, `branch -D`, `stash drop`, and `commit --amend` all merely move or delete *refs*, leaving the objects orphaned ("dangling") but intact. The two recovery tools target this: **`git reflog`** records every movement of `HEAD` (and per-branch tips), so anything `HEAD` ever pointed at — pre-amend states, deleted-branch tips, pre-reset positions — is listed with a `HEAD@{n}` coordinate you can recover from. **`git fsck --unreachable`** goes deeper, enumerating dangling objects directly from the object store even when *no reflog entry* references them (the typical case for a dropped stash, whose ref was deleted).

```bash
# The clock you're racing — orphans are pruned after these windows:
git config gc.reflogExpire            # default 90 days (reachable reflog entries)
git config gc.reflogExpireUnreachable # default 30 days (unreachable)
git config gc.pruneExpire             # default 2 weeks (loose unreachable objects)
```

The expert caveats: recovery works **only within the gc grace windows** above — an aggressive `git gc --prune=now` or `git reflog expire --expire=now --all` deliberately destroys orphans immediately, which is exactly what you run *after* a secret-removal rewrite to ensure the bad objects are gone. Recovery also can't help with **never-committed, never-staged** working-tree changes destroyed by `reset --hard` or `clean -fd` — those were never objects, so there's nothing to recover (a hard argument for committing WIP frequently). The discipline I always state: recover into a *new* branch, verify with `git log`/`git diff`, *then* integrate — never reset a shared branch blindly during a recovery.

#### Q79. [Theory] How would you architect Git hosting and scale for a 5,000-engineer monorepo? Discuss server topology, caching, and the protocol/storage features you'd lean on.

At this scale a vanilla single Git server falls over, and the architecture becomes a distributed-systems problem layered on Git's primitives. The core constraints: tens of thousands of fetches/day, a working tree no one can fully check out, a ref namespace with potentially millions of entries (per-PR/CI refs), and an object DB measured in hundreds of GB. The design leans on the features built precisely for this — **partial clone**, **sparse-checkout**, **reachability bitmaps**, **commit-graph**, **MIDX**, and **reftable** — combined with a tiered serving topology.

```
            ┌──────────── developers (sparse + partial clones) ────────────┐
            ▼                         ▼                          ▼
       read replica            read replica              read replica   (geo-distributed, cache fetches)
            └───────────────── replication ─────────────────────┘
                                  ▲
                          primary / write node
                                  │  (atomic ref updates: reftable)
                          object storage (packs + bitmaps + MIDX + commit-graph)
                                  │
                          CI/build farm ──▶ uses bundle-URI / managed cache for cold clones
```

**Server topology:** a primary that accepts writes (pushes), fronted by **read replicas** (geographically distributed) that serve the overwhelmingly read-heavy fetch/clone traffic — this is essentially how GitHub/GitLab scale. Replicas are kept current by replication of the object store and refs. To absorb CI's brutal clone load you add **bundle-URI** (Git 2.38+: the server hands clients a CDN-hosted bundle to bootstrap most objects, so the expensive part of a clone comes from cheap static hosting, not the Git server) and persistent **CI checkout caches** so build jobs `fetch` deltas rather than clone cold.

**Storage & protocol features:** **reachability bitmaps** + **MIDX** keep push/fetch *negotiation* fast despite the object count; the **commit-graph** keeps history traversal (`merge-base`, ancestry, log) fast despite deep history; **reftable** replaces the loose-ref/`packed-refs` scheme so millions of refs and atomic transactional ref updates don't melt the filesystem; and **protocol v2** with server-side ref filtering means clients fetch `refs/heads/main` without downloading a million PR refs. On the client side, **partial clone** (`--filter=blob:none`) and **cone-mode sparse-checkout** are mandatory — no engineer materializes the whole tree; tooling (Bazel/Buck/Nx) computes the sparse set from the build graph. **fsmonitor** + `feature.manyFiles` keep local `status`/`add` interactive on the giant working tree.

The expert framing and trade-offs: this is the **Microsoft (Scalar/VFS-for-Git on the Windows repo) and Meta (Sapling/EdenFS) playbook** — at the extreme, Meta replaced the working-tree model entirely with a **virtual filesystem (EdenFS)** that lazily materializes files on access, because even sparse-checkout writes too much. The cost of all this is operational complexity (replication consistency, cache invalidation, monorepo build tooling) and a hard coupling between VCS and build system. The honest staff-level answer also questions the premise: a 5,000-engineer monorepo is a deliberate org choice (atomic cross-cuts, one source of truth) bought at enormous tooling cost — viable only if you invest in the infra above, and many orgs that can't should use polyrepo with good dependency management instead.

#### Q80. [Practical] You discover a feature branch was based on the wrong parent (branched off `develop` instead of `main`) after 8 commits. How do you re-base it onto the correct base without dragging in develop's commits?

The problem precisely: your 8 feature commits sit on top of `develop`, but they should sit on top of `main`. A naive `git rebase main` would try to replay *everything* between the branches' merge base and your branch tip — which **includes develop's commits**, dragging them along. The surgical tool is **`git rebase --onto`**, which lets you specify three things independently: the new base, the *old* base to cut from, and the branch to move.

```bash
# Syntax:  git rebase --onto <new-base> <old-base> <branch>
git switch feature
git rebase --onto main develop feature
#          └ replay onto main
#                └ cut everything up to and including develop's tip
#                       └ move these commits (feature)
```

`--onto main develop feature` reads as: "take the commits on `feature` that come *after* `develop`, and replay them onto `main`." The `<old-base>` argument (`develop`) is the key — it tells rebase where your *own* commits begin, so only your 8 commits get replayed and develop's commits are excluded. Without `--onto`, `git rebase main` uses the merge-base of `feature` and `main` as the cut point, which sits *before* develop diverged, sweeping develop's work into the replay.

```
BEFORE                              AFTER  git rebase --onto main develop feature
 main    A───B                       main    A───B
              \                                    \
 develop  C───D                       develop  C───D   (untouched)
                \                      feature   F'──G'──…  (your 8 commits, now on B)
 feature  D──E──F──G (8 commits)
   (E..G are yours; C,D are develop's)
```

The verification and gotchas: after the rebase, confirm with `git log --oneline main..feature` that *only your 8 commits* appear and none of develop's. If commits conflict during replay (because they assumed develop's context), resolve, `git add`, `git rebase --continue`. Because this rewrites your branch's hashes, **only do it if the feature branch isn't shared** (or coordinate + `--force-with-lease`). The broader signal this question tests is whether you understand that `rebase` is really "replay the commit *range* `<old-base>..<branch>` onto `<new-base>`," and that the default base-selection is just a convenience — `--onto` exposes the full power and is the canonical fix for "branched off the wrong thing," transplanting commits between any two points in history.

#### Q81. [Coding] Write a CODEOWNERS file and explain exactly how matching, ordering, and required-review enforcement interact.

**Problem:** A repo needs path-based review routing: security-sensitive code requires the security team, frontend changes route to the web team, and everything else has a default owner — with the right rules winning when paths overlap.

```bash
# .github/CODEOWNERS  (also valid at repo root or in docs/)
# Syntax: <path-pattern>  <owner1> <owner2> ...   (owners are @user or @org/team)

*                       @org/platform-team        # default owner for everything
*.js                    @org/web-team             # all JS files
/frontend/              @org/web-team
/infra/                 @org/sre-team
/services/payments/     @org/payments @org/security-team
/.github/               @org/platform-leads       # who guards CI config
SECURITY.md             @org/security-team
# Negation/precedence note: LAST matching pattern wins (unlike .gitignore layering)
```

The single most important and counter-intuitive rule: **the last matching pattern in the file takes precedence** for a given path. This is the *opposite* of mental models people import from `.gitignore` (where later patterns refine earlier ones cumulatively). In CODEOWNERS, if `/services/payments/api.js` matches both `*.js` (web-team) and `/services/payments/` (payments + security), the **later line wins outright** — so ordering is load-bearing: you put broad defaults at the top and increasingly specific overrides below. A file matched by *no* pattern has **no required owner**.

```
PR touches: /services/payments/api.js
  matches:  *               → @platform-team   (line 1)
            *.js            → @web-team         (line 2)
            /services/payments/ → @payments @security-team  (later line — WINS)
  required reviewers = @payments + @security-team
```

CODEOWNERS only *routes* reviews by default — it auto-requests those owners as reviewers. It becomes **enforcement** only when you enable **"Require review from Code Owners"** in branch protection: then the PR cannot merge until an actual member of every owning team for every changed path approves. The interplay to articulate: the set of required approvers is the *union* of the winning owners across *all* changed files, so a PR spanning `frontend/` and `services/payments/` needs approval from web-team **and** payments **and** security. Gotchas worth knowing: a syntactically invalid CODEOWNERS line is silently ignored (validate it — GitHub shows errors in the repo's CODEOWNERS UI); owners must have *write* access or they can't be required reviewers; and teams must be members of the repo. The design lesson: CODEOWNERS encodes "who must vouch for this code," precedence makes specificity win, and pairing it with branch protection turns advisory routing into a hard merge gate scaled to each path's blast radius.

#### Q82. [Theory] Explain how Git would migrate from SHA-1 to SHA-256, why it's hard, and the current state of interoperability.

Git's entire model is built on object IDs being hashes of content, and historically that hash was **SHA-1** — which is cryptographically broken (the 2017 SHAttered attack produced a real SHA-1 collision, meaning an attacker could in principle craft two different objects with the same OID, undermining the tamper-evidence guarantee). Git's immediate mitigation was **`sha1dc`** (collision-detecting SHA-1, on by default since 2.13), which detects the known attack patterns and aborts rather than silently accepting a collision — a stopgap, not a fix. The real fix is moving the object format to **SHA-256**, which Git supports today as an experimental repository format.

```bash
git init --object-format=sha256 myrepo     # create a SHA-256 repo
cd myrepo && git rev-parse HEAD            # OIDs are now 64 hex chars, not 40
git config extensions.objectFormat         # → sha256
```

Why the migration is genuinely hard: the hash is **woven through everything** — every object references its children by hash, so a SHA-256 repo's commits, trees, tags, and signatures are all in terms of 256-bit OIDs, making the two formats *fundamentally incompatible object databases*. You can't just "switch a flag" on an existing repo; converting means rewriting every object (new hashes cascade exactly like any history rewrite). Worse, the **entire ecosystem assumes 40-char SHA-1**: hosting platforms (GitHub does not yet support SHA-256 repos), CI systems, hooks, scripts that parse OIDs, submodule pointers, signed-commit verification, and tooling all hard-code SHA-1 assumptions. A flag day where the world switches at once is impossible.

The designed answer is **interoperability via a translation table**: the long-term plan is for a repository to store objects in *both* formats (or maintain a SHA-1↔SHA-256 mapping) so a SHA-256 repo can still speak SHA-1 on the wire to SHA-1 clients/servers, translating OIDs at the boundary. This "interop" layer is **specified but not yet fully implemented**, which is the crux of the current state: SHA-256 repos *work* in isolation, but you can't meaningfully push one to GitHub or interoperate with the SHA-1 world yet, so adoption is essentially limited to greenfield/experimental use. The honest expert summary: SHA-1's weakness is *mitigated* (sha1dc) rather than urgent for most threat models, the SHA-256 format is *ready* but the *interop bridge and ecosystem support are the long pole*, and a real-world migration is a multi-year ecosystem effort — not a command you run. It's a textbook example of how hard it is to change a foundational primitive once it's embedded in a global ecosystem.

## ✅ Key Takeaways

- Git is a content-addressable store of immutable objects (blob → tree → commit); branches and HEAD are just movable refs (pointers). Understanding this demystifies almost everything else.
- The index (staging area) is a deliberate layer — it lets you craft precise, atomic commits (`git add -p`), which in turn make `bisect`, `revert`, and review reliable.
- **Rebase** rewrites history (linear, clean, *local only*); **merge** preserves it (honest, *shared branches*). Never rebase published commits.
- Almost nothing is truly lost for ~weeks: `reflog` and `fsck` recover orphaned commits. Recover into a new branch, verify, then act.
- Use `revert` on pushed/shared branches and `reset`/`rebase` only on local work — this one rule prevents most team-wide Git pain.
- Choose branching strategy by velocity and release model: GitHub Flow (default), trunk-based + flags (high velocity), GitFlow (parallel release lines).
- Enforce quality where it can't be bypassed: server-side hooks, CI, and branch protection — client hooks are advisory and `--no-verify`-able.
- Security is layered on top of Git: secret scanning + push protection prevent leaks; signed commits/tags provide authenticity; SHA-256 hardens integrity.

## ⚠️ Common Pitfalls

- Adding an already-tracked file to `.gitignore` and expecting it to stop being tracked — you must `git rm --cached` it first.
- `git push --force` instead of `--force-with-lease`, clobbering teammates' pushed work with no warning.
- Committing secrets/`.env`; deleting them in a later commit and assuming they're gone — they live in history (and every clone) until rewritten *and* rotated.
- Rebasing or force-pushing a shared branch, forcing everyone into avoidable conflict hell.
- Treating `git reset --hard` and `git clean -fd` casually — both destroy uncommitted work irreversibly; always dry-run `clean -nd` first.
- Forgetting that tags and submodule pointer updates aren't pushed/cloned by default (`git push --tags`, `clone --recurse-submodules`).
- Giant PRs and giant commits — they wreck review quality and make `bisect`/`revert` useless.
- Trusting Git's author field as identity — without signing, it's freely spoofable.

## 📚 Further Reading

- **Pro Git** by Scott Chacon & Ben Straub — free at [git-scm.com/book](https://git-scm.com/book); Chapter 10 ("Git Internals") is essential for the object-model questions.
- **Official Git Reference** — [git-scm.com/docs](https://git-scm.com/docs); authoritative man pages, including `git-rebase`, `git-bisect`, `git-filter-repo` notes.
- **GitHub Docs: Branch protection & repository security** — [docs.github.com](https://docs.github.com) (CODEOWNERS, required status checks, secret scanning, push protection).
- **"Trunk-Based Development"** — [trunkbaseddevelopment.com](https://trunkbaseddevelopment.com) by Paul Hammant; the definitive reference on the strategy.
- **git-filter-repo** — [github.com/newren/git-filter-repo](https://github.com/newren/git-filter-repo); the modern, recommended history-rewriting tool (replaces `filter-branch`).
- **Atlassian Git Tutorials** — [atlassian.com/git/tutorials](https://www.atlassian.com/git/tutorials); excellent visual explanations of merge/rebase, GitFlow, and workflows.
