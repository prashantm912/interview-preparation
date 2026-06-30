#!/usr/bin/env python3
"""Regenerate the question/problem counts that fill README.md.

Run from the repo root:  python tools/count_q.py

For every markdown file under the numbered sections (00-.. .. 14-..) it counts:
  - tagged Q&A headings:  `#+ ... [Theory|Practical|Coding|Behavioral] ...`
  - DSA problem headings: `#+ Problem N`   (08-dsa)

Prints per-file rows, per-section subtotals, and grand totals. Fill the README
TOC tables + the "At a Glance" summary from this output rather than guessing.
Stdlib only, no dependencies.
"""
import os
import re

TAGS = ['Theory', 'Practical', 'Coding', 'Behavioral']
tag_re = {t: re.compile(r'^#+ .*\[' + t + r'\]') for t in TAGS}
prob_re = re.compile(r'^#+ Problem \d+')
sec_re = re.compile(r'^\d\d-')


def count_file(path):
    counts = {t: 0 for t in TAGS}
    probs = 0
    with open(path, encoding='utf-8') as fh:
        for ln in fh:
            for t, rx in tag_re.items():
                if rx.match(ln):
                    counts[t] += 1
            if prob_re.match(ln):
                probs += 1
    return counts, probs


def main():
    sections = {}
    grand = {t: 0 for t in TAGS}
    grand['problems'] = 0
    grand['files'] = 0
    rows = []
    for dirpath, _dirs, files in os.walk('.'):
        top = next((p for p in dirpath.replace('\\', '/').split('/') if sec_re.match(p)), None)
        if top is None:
            continue
        for fn in files:
            if not fn.endswith('.md'):
                continue
            path = os.path.join(dirpath, fn).replace('\\', '/')
            counts, probs = count_file(path)
            rows.append((path, counts, sum(counts.values()), probs))
            s = sections.setdefault(top, {**{t: 0 for t in TAGS}, 'problems': 0, 'files': 0})
            for t in TAGS:
                s[t] += counts[t]
                grand[t] += counts[t]
            s['problems'] += probs
            s['files'] += 1
            grand['problems'] += probs
            grand['files'] += 1

    for path, counts, total, probs in sorted(rows):
        c = ' '.join(f'{t[0]}{counts[t]}' for t in TAGS)
        print(f'{path:58} {c}  total={total:<4} problems={probs}')
    print('\n=== per section ===')
    for sec in sorted(sections):
        d = sections[sec]
        tq = sum(d[t] for t in TAGS)
        print(f'{sec}: files={d["files"]} taggedQ={tq} '
              f'(T{d["Theory"]}/P{d["Practical"]}/C{d["Coding"]}/B{d["Behavioral"]}) problems={d["problems"]}')
    qtot = sum(grand[t] for t in TAGS)
    print('\n=== grand totals ===')
    print(f'files={grand["files"]}  taggedQ&A={qtot}  DSA problems={grand["problems"]}')


if __name__ == '__main__':
    main()
