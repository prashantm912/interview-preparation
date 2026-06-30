# Interview Preparation Guide — Claude Context

Content-only repo. No build, no tests. 140 markdown docs across 15 numbered sections (00–14).

## Layout
- `00-getting-started/` … `13-ai-ml/` — topic sections (`00-getting-started/` also holds `study-schedule.md`; the two `rag-mini-project*.md` builds live in `13-ai-ml/`; `bicep.md` in `07-devops-cloud/`)
- `08-dsa/` — DSA problems (Java solutions)
- `09-system-design/` + `09-system-design/design-problems/` — case studies
- `14-hands-on-projects/` — build curriculum (hands-on-projects, learning-projects, project-playbook, project-briefs)
- `README.md` — generated TOC with per-file counts; don't hand-edit count cells

## Three doc formats
1. **Tagged Q&A** — tier sections (🟢 Junior / 🟡 Mid / 🟠 Senior / 🔴 Staff+) with `### Qn. [Tag] question` and closing `## ✅ Key Takeaways` / `## ⚠️ Common Pitfalls` / `## 📚 Further Reading`.
2. **DSA** — `## Coding Problems` + `### Problem N: Title — Technique` with Java code blocks.
3. **System design case study** — sections `## 1. Requirements` … `## 8. Trade-offs` + `## Interview Q&A by Level` (tagged).

## Tag vocabulary
`[Theory]` `[Practical]` `[Coding]` `[Behavioral]` — these four only. Never invent `[Design]` or other tags.

## Appending new questions
- Insert BEFORE `## ✅ Key Takeaways` (Edit anchor).
- Wrap in `## 🧩 Extended Questions — Set k: <lens>` with tier subheads `### 🟢 — extended` etc.
- Continue Q-numbers from current max. Never renumber existing.
- Never touch existing content.

## Operational rules
- Do NOT commit unless user explicitly asks.
- README counts come from `tools/count_q.py` — run `python tools/count_q.py` from repo root for per-file T/P/C/B + DSA problem counts, then update the README tables + At a Glance from its output. Don't hand-edit count cells by guessing.
- Memory index: `C:\Users\prash\.claude\projects\C--Learning-interview-preparation\memory\MEMORY.md`.
