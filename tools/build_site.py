"""Build the static site for this guide (MkDocs Material).

The repo is the source of truth: markdown stays where it is. This script
assembles a build tree, derives nav + titles from the content, writes the
MkDocs config, and runs the build.

  python tools/build_site.py          # build into site/
  python tools/build_site.py --serve  # build, then serve locally on :8000

Requires: pip install mkdocs-material
"""

from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / ".site_build"
DOCS = BUILD / "docs"
OUT = ROOT / "site"
CONFIG = BUILD / "mkdocs.yml"

# Files that document the repo for agents/tooling, not readers.
EXCLUDE_FILES = {"AGENTS.md", "CLAUDE.md"}
EXCLUDE_DIRS = {".git", ".idea", ".claude", ".dctest", "tools", ".site_build", "site"}

# Acronyms and stylings the generic title-caser gets wrong.
TITLE_FIXES = {
    "Dsa": "DSA",
    "Ai Ml": "AI / ML",
    "Apis Auth": "APIs & Auth",
    "Devops Cloud": "DevOps & Cloud",
    "Cs Fundamentals": "CS Fundamentals",
    "Backend Java": "Backend (Java)",
}

SECTION_PREFIX = re.compile(r"^\d{2}-")
H1 = re.compile(r"^#\s+(.+?)\s*$", re.MULTILINE)
ANCHOR_LINK = re.compile(r"\]\(#[^)]*\)")
README_LINK = re.compile(r"\]\((?:\.{1,2}/)*README\.md(#[^)]*)?\)")

# The roadmap's companion HTML edition is published from a separate repo.
HTML_EDITION_URL = "https://prashantm912.github.io/frontend-easy-tutorial/interview-prep-guide.html"


def retarget_readme_links(text: str, depth: int) -> str:
    """Point every back-link to the root README at the site's home page.

    The repo's README becomes index.md here, and some source links use a
    relative depth that never resolved on GitHub either, so all of them are
    rewritten to the correct path for the file's own depth.
    """
    target = "../" * depth + "index.md"
    return README_LINK.sub(lambda m: f"]({target}{m.group(1) or ''})", text)


def pretty(name: str) -> str:
    """'07-devops-cloud' -> 'DevOps & Cloud'."""
    base = SECTION_PREFIX.sub("", name).replace("-", " ").title()
    return TITLE_FIXES.get(base, base)


def page_title(path: Path) -> str:
    """First H1 of a markdown file, minus decorative emoji, else the filename."""
    match = H1.search(path.read_text(encoding="utf-8", errors="replace")[:4000])
    if not match:
        return pretty(path.stem)
    title = re.sub(r"[\U0001F300-\U0001FAFF←-⇿☀-➿]", "", match.group(1))
    return title.strip(" -–—:") or pretty(path.stem)


def copy_markdown() -> list[Path]:
    """Mirror the markdown tree into the build dir; README becomes the home page."""
    copied: list[Path] = []
    for src in sorted(ROOT.rglob("*.md")):
        rel = src.relative_to(ROOT)
        if set(rel.parts) & EXCLUDE_DIRS or rel.name in EXCLUDE_FILES:
            continue
        dst = DOCS / ("index.md" if rel.name == "README.md" and len(rel.parts) == 1 else rel)
        dst.parent.mkdir(parents=True, exist_ok=True)
        text = src.read_text(encoding="utf-8")
        text = retarget_readme_links(text, depth=len(rel.parts) - 1)
        text = text.replace("](interview-prep-guide.html)", f"]({HTML_EDITION_URL})")
        # GitHub keeps U+FE0F in heading anchors; the slugifier below drops it.
        text = ANCHOR_LINK.sub(lambda m: m.group(0).replace("️", ""), text)
        dst.write_text(text, encoding="utf-8")
        copied.append(dst)
    return copied


def nav_entry(path: Path) -> str:
    """One 'Title: relative/path.md' line, YAML-quoted."""
    rel = path.relative_to(DOCS).as_posix()
    title = page_title(path).replace('"', "'")
    return f'"{title}": {rel}'


def build_nav() -> str:
    lines = ["nav:", "  - Home: index.md"]
    for section in sorted(p for p in DOCS.iterdir() if p.is_dir()):
        lines.append(f'  - "{pretty(section.name)}":')
        for md in sorted(section.glob("*.md")):
            lines.append(f"      - {nav_entry(md)}")
        for sub in sorted(p for p in section.iterdir() if p.is_dir()):
            lines.append(f'      - "{pretty(sub.name)}":')
            for md in sorted(sub.glob("*.md")):
                lines.append(f"          - {nav_entry(md)}")
    return "\n".join(lines)


CONFIG_TEMPLATE = """site_name: Interview Preparation Guide
site_description: >-
  Multi-technology software engineering interview preparation, answered at four
  experience levels with theory, scenarios, and coding solutions.
site_url: https://prashantm912.github.io/interview-preparation/
repo_url: https://github.com/prashantm912/interview-preparation
repo_name: interview-preparation
docs_dir: docs
site_dir: __SITE_DIR__
use_directory_urls: false

theme:
  name: material
  palette:
    - media: "(prefers-color-scheme: light)"
      scheme: default
      primary: indigo
      accent: indigo
      toggle:
        icon: material/brightness-7
        name: Dark mode
    - media: "(prefers-color-scheme: dark)"
      scheme: slate
      primary: indigo
      accent: indigo
      toggle:
        icon: material/brightness-4
        name: Light mode
  features:
    - navigation.tabs
    - navigation.sections
    - navigation.top
    - navigation.indexes
    - navigation.footer
    - toc.follow
    - search.suggest
    - search.highlight
    - content.code.copy
    - content.tooltips

markdown_extensions:
  - admonition
  - attr_list
  - md_in_html
  - tables
  - footnotes
  - pymdownx.details
  - pymdownx.superfences
  - pymdownx.tabbed:
      alternate_style: true
  - pymdownx.highlight:
      anchor_linenums: true
  - pymdownx.inlinehilite
  - toc:
      permalink: true
      toc_depth: 3
      # GitHub-compatible heading ids, so the in-page links written for
      # GitHub's renderer (e.g. "#-basic-02-yrs") keep working here.
      slugify: !!python/object/apply:pymdownx.slugs.slugify {kwds: {case: lower}}

plugins:
  - search

__NAV__
"""


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--serve", action="store_true", help="serve the built site afterwards")
    args = parser.parse_args()

    if BUILD.exists():
        shutil.rmtree(BUILD)
    DOCS.mkdir(parents=True)

    pages = copy_markdown()
    config = CONFIG_TEMPLATE.replace("__SITE_DIR__", OUT.as_posix()).replace("__NAV__", build_nav())
    CONFIG.write_text(config, encoding="utf-8")

    subprocess.run([sys.executable, "-m", "mkdocs", "build", "-f", str(CONFIG)], check=True)
    print(f"[build] {len(pages)} pages -> {OUT}")

    if args.serve:
        subprocess.run([sys.executable, "-m", "http.server", "8000", "-d", str(OUT)])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
