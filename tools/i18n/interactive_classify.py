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
            col_start = start - line_start
            occurrences.append(Occurrence(path, line_no, col_start, col_start + len(raw), raw, context_line))
    return occurrences


def _source_without_literals_or_comments(src: str) -> str:
    masked = list(src)
    for start, end, _ in _lib._iter_literals(src):
        for index in range(start, end):
            if masked[index] != "\n":
                masked[index] = " "
    masked_src = "".join(masked)

    def blank(match: re.Match[str]) -> str:
        return "".join("\n" if char == "\n" else " " for char in match.group(0))

    return re.sub(r"//[^\n]*|/\*.*?\*/", blank, masked_src, flags=re.DOTALL)


_NON_COMPOSABLE_LAMBDA_CALLS = frozenset({
    "remember",
    "rememberSaveable",
    "LaunchedEffect",
    "DisposableEffect",
    "SideEffect",
    "produceState",
    "derivedStateOf",
    "snapshotFlow",
    "runCatching",
    "runBlocking",
    "withContext",
    "launch",
    "async",
    "let",
    "also",
    "apply",
    "run",
    "use",
    "map",
    "mapNotNull",
    "filter",
    "filterNot",
    "fold",
    "forEach",
    "onEach",
    "repeat",
    "withTimeout",
    "withTimeoutOrNull",
    "onPreviewKeyEvent",
    "onFocusChanged",
})


def _brace_pairs(masked: str) -> dict[int, int]:
    stack: list[int] = []
    matching: dict[int, int] = {}
    for index, char in enumerate(masked):
        if char == "{":
            stack.append(index)
        elif char == "}" and stack:
            matching[stack.pop()] = index
    return matching


def _composable_ranges(src: str) -> list[tuple[int, int, bool]]:
    masked = _source_without_literals_or_comments(src)
    matching = _brace_pairs(masked)
    ranges: list[tuple[int, int, bool]] = []
    function_pattern = re.compile(r"\bfun\s+[A-Za-z_][\w`]*(?:\s*<[^{}]*>)?[^{}]*\{")
    for match in function_pattern.finditer(masked):
        opening = match.end() - 1
        closing = matching.get(opening)
        if closing is None:
            continue
        boundary = max(
            masked.rfind("}", 0, match.start()),
            masked.rfind("{", 0, match.start()),
            masked.rfind(";", 0, match.start()),
            masked.rfind("\n\n", 0, match.start()),
        )
        annotation = masked[boundary + 1:match.start()]
        is_composable = bool(re.search(r"@\s*(?:[\w.]+\.)?Composable\b", annotation))
        ranges.append((opening + 1, closing, is_composable))
    return ranges


def _lambda_call_name(prefix: str) -> str | None:
    prefix = prefix.rstrip()
    if prefix.endswith(")"):
        depth = 0
        opening = None
        for index in range(len(prefix) - 1, -1, -1):
            if prefix[index] == ")":
                depth += 1
            elif prefix[index] == "(":
                depth -= 1
                if depth == 0:
                    opening = index
                    break
        if opening is None:
            return None
        prefix = prefix[:opening].rstrip()
    match = re.search(r"([A-Za-z_][\w.]*)\s*$", prefix)
    return match.group(1).rsplit(".", 1)[-1] if match else None


def _non_composable_lambda_ranges(src: str) -> list[tuple[int, int]]:
    masked = _source_without_literals_or_comments(src)
    matching = _brace_pairs(masked)
    function_openings = {
        match.end() - 1
        for match in re.finditer(r"\bfun\s+[A-Za-z_][\w`]*(?:\s*<[^{}]*>)?[^{}]*\{", masked)
    }
    ranges: list[tuple[int, int]] = []
    for opening, closing in matching.items():
        if opening in function_openings:
            continue
        prefix = masked[max(0, opening - 512):opening].rstrip()
        if re.search(r"@\s*(?:[\w.]+\.)?Composable\s*$", prefix):
            continue
        if re.search(r"(?:^|[\s;])(?:if|for|while|when|catch|synchronized)\s*(?:\([^{}]*\))?\s*$", prefix):
            continue
        if re.search(r"(?:^|[\s;])(?:else|try|finally|do)\s*$", prefix):
            continue
        if re.search(r"\b(?:class|interface|object|enum)\b[^{}]*$", prefix):
            continue
        if re.search(r"(?:[A-Za-z_][\w.]*)\s*=\s*$", prefix):
            ranges.append((opening + 1, closing))
            continue
        if _lambda_call_name(prefix) in _NON_COMPOSABLE_LAMBDA_CALLS:
            ranges.append((opening + 1, closing))
    return ranges


def _is_composable_occurrence(src: str, occurrence: Occurrence) -> bool:
    lines = src.splitlines(keepends=True)
    if not 1 <= occurrence.line_no <= len(lines):
        return False
    offset = sum(len(line) for line in lines[:occurrence.line_no - 1]) + occurrence.col_start
    containing = [item for item in _composable_ranges(src) if item[0] <= offset < item[1]]
    if not containing:
        return False
    if not min(containing, key=lambda item: item[1] - item[0])[2]:
        return False
    return not any(start <= offset < end for start, end in _non_composable_lambda_ranges(src))


# ---------------------------------------------------------------------------
# Key suggestion
# ---------------------------------------------------------------------------

_KEY_SLUG_RE = re.compile(r"[^a-z0-9]+")
_RESOURCE_KEY_RE = re.compile(r"[a-z][a-z0-9_]*")


def _is_valid_resource_key(key: str) -> bool:
    return _RESOURCE_KEY_RE.fullmatch(key) is not None


def _suggest_key(path: str, text: str) -> str:
    """Suggest an R.string.* key based on existing patterns in the same file."""
    full_path = ROOT / path
    src = full_path.read_text("utf-8")
    existing = set(re.findall(r"R\.string\.(\w+)", src))
    slug = _KEY_SLUG_RE.sub("_", text.lower().strip(" _")).strip("_")
    if not slug:
        slug = "label"
    elif not slug[0].isalpha():
        slug = f"label_{slug}"

    # Try matching an existing prefix from the file
    prefixes: Counter = Counter()
    for key in existing:
        if "_" in key:
            prefixes[key.rsplit("_", 1)[0]] += 1
    if prefixes:
        best_prefix = prefixes.most_common(1)[0][0]
        candidate = f"{best_prefix}_{slug}"
        if _is_valid_resource_key(candidate) and candidate not in existing:
            return candidate

    # Try single-word prefix from any existing key
    for key in existing:
        prefix = key.split("_", 1)[0]
        candidate = f"{prefix}_{slug}"
        if _is_valid_resource_key(candidate) and candidate not in existing:
            return candidate

    # Fallback
    return slug if slug not in existing else f"{slug}_2"


def _string_resource_matches(xml_content: str, key: str) -> list[re.Match[str]]:
    pattern = re.compile(
        rf"<string(?=\s|>)(?=[^>]*\bname\s*=\s*[\"']{re.escape(key)}[\"'])[^>]*(?:/>|>.*?</string\s*>)",
        re.DOTALL,
    )
    return list(pattern.finditer(xml_content))


def _existing_resource_entries(key: str) -> list[tuple[Path, re.Match[str]]]:
    entries: list[tuple[Path, re.Match[str]]] = []
    for resource_file in sorted(STRINGS_XML.parent.glob("strings*.xml")):
        content = resource_file.read_text("utf-8")
        entries.extend((resource_file, match) for match in _string_resource_matches(content, key))
    return entries


def _key_exists(key: str) -> bool:
    return bool(_existing_resource_entries(key))


# ---------------------------------------------------------------------------
# strings.xml editing
# ---------------------------------------------------------------------------

def _add_to_strings_xml(key: str, text: str, source_path: str, translator_note: str | None = None) -> bool:
    """Add or update one <string> entry in strings.xml."""
    if not _is_valid_resource_key(key):
        print(f"  ✗ Invalid Android resource name: {key!r}")
        return False

    existing = _existing_resource_entries(key)
    if existing:
        if len(existing) != 1:
            print(f"  ✗ Cannot overwrite R.string.{key}: found {len(existing)} entries")
            return False
        resource_file, match = existing[0]
        xml_content = resource_file.read_text("utf-8")
        element = match.group(0)
        opening_end = element.find(">")
        opening = element[:opening_end].rstrip()
        if opening.endswith("/"):
            opening = opening[:-1].rstrip()
        replacement = f'{opening}>{_xml_escape(text)}</string>'
        resource_file.write_text(xml_content[:match.start()] + replacement + xml_content[match.end():], "utf-8")
        print(f"  ✓ Updated R.string.{key} in {resource_file.name}")
        return True

    xml_content = STRINGS_XML.read_text("utf-8")
    closing = xml_content.rfind("</resources>")
    if closing == -1:
        print(f"  ✗ Could not find </resources> in {STRINGS_XML}")
        return False

    indent = "    "
    if translator_note:
        comment = f"{indent}<!-- Translators: {translator_note} -->\n"
    else:
        section = source_path.replace("app/src/main/java/tv/own/owntv/", "").split("/")[0]
        comment = f"{indent}<!-- Translators: used in {section} ({source_path}) -->\n"
    entry = f'{comment}{indent}<string name="{key}">{_xml_escape(text)}</string>\n'
    line_start = xml_content.rfind("\n", 0, closing) + 1
    if xml_content[line_start:closing].strip():
        entry = "\n" + entry
        insertion = closing
    else:
        insertion = line_start
    STRINGS_XML.write_text(xml_content[:insertion] + entry + xml_content[insertion:], "utf-8")
    print(f"  ✓ Added R.string.{key} to strings.xml")
    return True


def _xml_escape(text: str) -> str:
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


# ---------------------------------------------------------------------------
# Source replacement
# ---------------------------------------------------------------------------

def _replace_all_in_source(path: str, occurrences: list[Occurrence], key: str):
    """Replace only the supplied occurrences in a source file."""
    if not occurrences:
        return
    full_path = ROOT / path
    src = full_path.read_text("utf-8")
    lines = src.splitlines(keepends=True)
    positions: list[tuple[int, int]] = []
    for occurrence in occurrences:
        if not 1 <= occurrence.line_no <= len(lines):
            continue
        start = sum(len(line) for line in lines[:occurrence.line_no - 1]) + occurrence.col_start
        end = start + len(occurrence.raw)
        if src[start:end] == occurrence.raw:
            positions.append((start, end))
    positions.sort(reverse=True)

    for start, end in positions:
        src = src[:start] + f'stringResource(R.string.{key})' + src[end:]

    if not positions:
        return
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
    safe[key] = safe.get(key, 0) + count
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
            while not _is_valid_resource_key(key):
                print("  Key must match [a-z][a-z0-9_]*")
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

    composable_occurrences = [
        occurrence for occurrence in occurrences
        if _is_composable_occurrence((ROOT / path).read_text("utf-8"), occurrence)
    ]
    if not composable_occurrences:
        print("  → No @Composable occurrence selected; literal left unchanged")
        return choice

    approved_occurrences = composable_occurrences
    if len(composable_occurrences) > 1:
        valid_numbers = {occurrences.index(occurrence) + 1 for occurrence in composable_occurrences}
        while True:
            selection = input("  Replace occurrences [a]ll [numbers] [s]kip > ").strip().lower()
            if selection in ("", "a", "all"):
                break
            if selection in ("s", "skip"):
                print("  → Skipped, literal left unchanged")
                return choice
            try:
                selected_numbers = {int(value) for value in re.split(r"[ ,]+", selection) if value}
            except ValueError:
                selected_numbers = set()
            if selected_numbers and selected_numbers <= valid_numbers:
                approved_occurrences = [
                    occurrence for occurrence in composable_occurrences
                    if occurrences.index(occurrence) + 1 in selected_numbers
                ]
                break
            print(f"  Choose occurrence numbers from: {', '.join(map(str, sorted(valid_numbers)))}")

    resource_text = _lib._decode(approved_occurrences[0].raw)
    approved_occurrences = [
        occurrence for occurrence in approved_occurrences
        if _lib._decode(occurrence.raw) == resource_text
    ]

    # Optional translator context
    print(f'\n  Translator context (optional — explain where/how this string is used):')
    note = input("  > ").strip()
    if not _add_to_strings_xml(key, resource_text, path, translator_note=note if note else None):
        return choice

    _replace_all_in_source(path, approved_occurrences, key)
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
