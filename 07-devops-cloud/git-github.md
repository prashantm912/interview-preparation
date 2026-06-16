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
