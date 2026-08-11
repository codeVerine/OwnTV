#!/usr/bin/env python3
"""Interactive classifier for new Kotlin string literals.

Walks every UNCLASSIFIED literal (neither in the shrinking baseline nor in safe_literals.txt),
shows each occurrence with file/line/context, and lets you classify it:

  [r]egex  [s]ql  [j]son  [p]rotocol  [t]echnical  [u]ser-facing  [i]gnore  [q]uit

User-facing strings are added to strings.xml and replaced with stringResource() in source.
Technical strings are written to safe_literals.txt.  "Ignore" writes to safe_literals.txt as
"technical" so it survives baseline regeneration.

After every classification the baseline is regenerated so you can stop and resume at any time.

Usage:
    python3 tools/i18n/interactive_classify.py
    python3 tools/i18n/interactive_classify.py --dry-run
"""
from __future__ import annotations

import argparse
import re
import sys
from collections import Counter
from pathlib import Path
from typing import NamedTuple

# ---------------------------------------------------------------------------
# Import the existing tool's internals without running its main()
# ---------------------------------------------------------------------------
TOOLS_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS_DIR))

import check_hardcoded_strings as _lib  # noqa: E402

ROOT = _lib.ROOT
SRC = _lib.SRC
BASELINE = _lib.BASELINE
SAFE_MANIFEST = _lib.SAFE_MANIFEST
STRINGS_XML = ROOT / "app" / "src" / "main" / "res" / "values" / "strings.xml"
SAFE_CATEGORIES = _lib.SAFE_CATEGORIES

# User-facing aliases in the prompt (single keypress → full category name)
_SHORT_CATEGORIES: dict[str, str] = {
    "r": "regex",
    "s": "sql",
    "j": "json",
    "p": "protocol",
    "t": "technical",
    "u": "user-facing",
    "i": "technical",  # "ignore" → stored as technical in safe_literals.txt
}


# ---------------------------------------------------------------------------
# Find unclassified literals
# ---------------------------------------------------------------------------

def _unclassified() -> dict[tuple[str, str], int]:
    """Return (path, text) → count for every literal NOT in the baseline and NOT safe."""
    current = _lib._inventory()
    safe, _, _ = _lib._safe_entries()
    classified = _lib._add_counts(
        safe,
        {} if not BASELINE.is_file() else _lib._parse(BASELINE.read_text("utf-8")),
    )
    return _lib._subtract_counts(current, classified)


# ---------------------------------------------------------------------------
# Source context helpers
# ---------------------------------------------------------------------------

class Occurrence(NamedTuple):
    path: str
    line_no: int
    col_start: int
    col_end: int
    raw: str          # the raw Kotlin literal including quotes
    context: str      # the surrounding source line


def _find_all_occurrences(path: str, normalized_text: str) -> list[Occurrence]:
    """Return every position where *normalized_text* appears in *path*."""
    full_path = ROOT / path
    src = full_path.read_text("utf-8")
    occurrences: list[Occurrence] = []
    for start, end, raw in _lib._iter_literals(src):
        if _lib._normalize(_lib._decode(raw)) == normalized_text:
            line_no = src[:start].count("\n") + 1
            line_start = src.rfind("\n", 0, start) + 1
            line_end = src.find("\n", start)
            if line_end == -1:
                line_end = len(src)
            context_line = src[line_start:line_end].rstrip()
            occurrences.append(Occurrence(path, line_no, 0, 0, raw, context_line))
    return occurrences


# ---------------------------------------------------------------------------
# Key suggestion
# ---------------------------------------------------------------------------

_KEY_SLUG_RE = re.compile(r"[^a-z0-9]+")


def _suggest_key(path: str, text: str) -> str:
    """Suggest an R.string.* key based on existing patterns in the same file."""
    full_path = ROOT / path
    src = full_path.read_text("utf-8")
    existing = set(re.findall(r"R\.string\.(\w+)", src))
    slug = _KEY_SLUG_RE.sub("_", text.lower().strip(" _")).strip("_")
    if not slug:
        slug = "label"

    # Try matching an existing prefix from the file
    prefixes: Counter = Counter()
    for key in existing:
        if "_" in key:
            prefixes[key.rsplit("_", 1)[0]] += 1
    if prefixes:
        best_prefix = prefixes.most_common(1)[0][0]
        candidate = f"{best_prefix}_{slug}"
        if candidate not in existing:
            return candidate

    # Try single-word prefix from any existing key
    for key in existing:
        prefix = key.split("_", 1)[0]
        candidate = f"{prefix}_{slug}"
        if candidate not in existing:
            return candidate

    # Fallback
    return slug if slug not in existing else f"{slug}_2"


def _key_exists(key: str) -> bool:
    if not STRINGS_XML.is_file():
        return False
    return f'name="{key}"' in STRINGS_XML.read_text("utf-8")


# ---------------------------------------------------------------------------
# strings.xml editing
# ---------------------------------------------------------------------------

def _add_to_strings_xml(key: str, text: str):
    """Add a <string> entry to strings.xml before the closing </resources> tag."""
    xml_content = STRINGS_XML.read_text("utf-8")
    xml_lines = xml_content.splitlines(keepends=True)

    insert_at = None
    for i in range(len(xml_lines) - 1, -1, -1):
        if "</resources>" in xml_lines[i]:
            insert_at = i
            break
    if insert_at is None:
        print(f"  ✗ Could not find </resources> in {STRINGS_XML}")
        return

    indent = "    "
    entry = f'{indent}<!-- Translators: auto-classified user-facing text. -->\n{indent}<string name="{key}">{_xml_escape(text)}</string>\n'
    xml_lines.insert(insert_at, entry)
    STRINGS_XML.write_text("".join(xml_lines), "utf-8")
    print(f"  ✓ Added R.string.{key} to strings.xml")


def _xml_escape(text: str) -> str:
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


# ---------------------------------------------------------------------------
# Source replacement
# ---------------------------------------------------------------------------

def _replace_all_in_source(path: str, occurrences: list[Occurrence], key: str):
    """Replace ALL occurrences of a literal in a file with stringResource."""
    full_path = ROOT / path
    src = full_path.read_text("utf-8")
    text_to_find = _lib._normalize(_lib._decode(occurrences[0].raw))

    # Collect absolute positions via _iter_literals (descending so replacements don't shift)
    positions = []
    for start, end, raw in _lib._iter_literals(src):
        if _lib._normalize(_lib._decode(raw)) == text_to_find:
            positions.append((start, end))
    positions.sort(reverse=True)

    for start, end in positions:
        src = src[:start] + f'stringResource(R.string.{key})' + src[end:]

    # Ensure import exists
    if "import tv.own.owntv.R" not in src:
        first_import = src.find("\nimport ")
        if first_import != -1:
            src = src[:first_import] + "\nimport tv.own.owntv.R" + src[first_import:]
        else:
            pkg_end = src.find("\n", src.find("package "))
            if pkg_end == -1:
                pkg_end = 0
            src = src[:pkg_end + 1] + "import tv.own.owntv.R\n" + src[pkg_end + 1:]

    full_path.write_text(src, "utf-8")
    print(f"  ✓ Replaced {len(positions)} occurrence(s) in {path}")


# ---------------------------------------------------------------------------
# Add to safe_literals.txt
# ---------------------------------------------------------------------------

def _add_to_safe(path: str, text: str, count: int, category: str):
    safe, categories, errors = _lib._safe_entries()
    if errors:
        for error in errors:
            print(f"  ✗ {error}")
        return
    key = (path, _lib._normalize(text))
    safe[key] = count
    categories[key] = category
    entries = {item: (cnt, categories[item]) for item, cnt in safe.items()}
    SAFE_MANIFEST.write_text(_lib._serialize_safe(entries), "utf-8")


# ---------------------------------------------------------------------------
# Baseline regeneration
# ---------------------------------------------------------------------------

def _regenerate_baseline():
    current = _lib._inventory()
    safe, _, _ = _lib._safe_entries()
    baseline = _lib._subtract_counts(current, safe)
    BASELINE.write_text(_lib._serialize(baseline), "utf-8")


# ---------------------------------------------------------------------------
# Interactive loop
# ---------------------------------------------------------------------------

def _prompt_category(text: str, count: int) -> str | None:
    prompt = (
        f'\n─── Classify: "{text[:80]}{"…" if len(text) > 80 else ""}" ({count} occurrence(s)) ───\n'
        "  [r]egex  [s]ql  [j]son  [p]rotocol  [t]echnical  [u]ser-facing  [i]gnore  [q]uit\n"
        "  > "
    )
    try:
        choice = input(prompt).strip().lower()
    except (EOFError, KeyboardInterrupt):
        return "q"
    valid = {"r", "s", "j", "p", "t", "u", "i", "q"}
    while choice not in valid:
        print(f"  Unknown: {choice!r}. Choose r/s/j/p/t/u/i/q")
        try:
            choice = input("  > ").strip().lower()
        except (EOFError, KeyboardInterrupt):
            return "q"
    return choice


def _show_occurrences(path: str, normalized_text: str) -> list[Occurrence]:
    """Print all occurrences and return them."""
    occurrences = _find_all_occurrences(path, normalized_text)
    current = _lib._inventory()
    all_files = [p for (p, t), c in current.items() if t == normalized_text]

    seen = set()
    for i, occ in enumerate(occurrences):
        indent = "    "
        marker = "→" if i == 0 else " "
        print(f"\n  [{i+1}] {marker} {occ.path}:{occ.line_no}")
        print(f"{indent}{occ.context.strip()[:120]}")
        seen.add(occ.path)

    other_files = [f for f in all_files if f != path and f not in seen]
    if other_files:
        shown = other_files[:5]
        for f in shown:
            print(f"  [·] also in {f}")
        if len(other_files) > 5:
            print(f"  … and {len(other_files) - 5} more files")

    return occurrences


def _classify_one(path: str, text: str, count: int) -> str | None:
    """Classify one unique string. Returns short key or None to quit."""
    occurrences = _show_occurrences(path, text)
    choice = _prompt_category(text, count)
    if choice in ("q", None):
        return "q"

    category = _SHORT_CATEGORIES[choice]
    label = "ignored (technical)" if choice == "i" else category

    if choice != "u":
        _add_to_safe(path, text, count, category)
        _regenerate_baseline()
        print(f"  → Classified as {label}")
        return choice

    # --- user-facing ---
    key = _suggest_key(path, text)
    while True:
        print(f"\n  Suggested key: R.string.{key}")
        confirm = input("  [y]es  [e]dit  [s]kip > ").strip().lower()
        if confirm in ("y", "yes", ""):
            break
        elif confirm in ("e", "edit"):
            key = input("  Enter key name (without prefix): ").strip()
            while not key or not _KEY_SLUG_RE.sub("_", key):
                print("  Key must be snake_case (a-z, 0-9, _)")
                key = input("  Enter key name: ").strip()
        elif confirm in ("s", "skip"):
            print("  → Skipped, literal left unchanged")
            return choice
        else:
            print(f"  Unknown: {confirm!r}")

    if _key_exists(key):
        print(f"  ⚠ R.string.{key} already exists in strings.xml")
        confirm = input("  Overwrite? [y]es [n]o > ").strip().lower()
        if confirm not in ("y", "yes"):
            print("  → Skipped")
            return choice

    _add_to_strings_xml(key, text)

    # Replace in all files that contain this literal
    current_inv = _lib._inventory()
    files_with_text = sorted(set(p for (p, t), c in current_inv.items() if t == text))
    for file_path in files_with_text:
        occs = _find_all_occurrences(file_path, text)
        if occs:
            _replace_all_in_source(file_path, occs, key)

    _regenerate_baseline()
    return choice


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true", help="list unclassified but don't modify files")
    args = parser.parse_args()

    unclassified = _unclassified()
    if not unclassified:
        print("✓ No unclassified literals found.")
        return 0

    total_unique = len(unclassified)
    total_occ = sum(unclassified.values())
    print(f"Unclassified literals: {total_unique} unique strings ({total_occ} total occurrences)\n")

    if args.dry_run:
        for (path, text), count in sorted(unclassified.items(), key=lambda x: (-x[1], x[0][0], x[0][1])):
            print(f"  [{count:3d}] {path}: {text[:100]}")
        print(f"\n{total_unique} unclassified, {total_occ} occurrences")
        return 0

    items = sorted(unclassified.items(), key=lambda x: (-x[1], x[0][0], x[0][1]))
    classified_count = 0
    for i, ((path, text), count) in enumerate(items):
        # Skip if already classified by a previous iteration (same text in another file)
        if i > 0:
            if (path, text) not in _unclassified():
                continue

        print(f"\n{'─' * 60}")
        print(f"[{i+1}/{total_unique}]")
        choice = _classify_one(path, text, count)
        if choice == "q":
            print(f"\nQuit after {classified_count} classified. Run again to continue.")
            return 0
        classified_count += 1

    _regenerate_baseline()
    print(f"\n✓ All {classified_count} literals classified.")
    print("  Remember to commit the changed files:")
    print("    strings.xml, safe_literals.txt, hardcoded_baseline.txt, *.kt sources")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
