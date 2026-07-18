#!/usr/bin/env python3
"""
validate-po.py — Validate .po files for structural integrity using polib.

Checks:
  1. Parse/syntax validity (polib.load)
  2. Duplicate msgid detection
  3. Orphan bare-quote lines (broken multi-line collapse)
  4. Unbalanced format placeholders ({0}, {1})
  5. Plural form consistency (msgstr[0]/[1] present when msgid_plural exists)
  6. Empty translations (optional, for reporting coverage)

Usage:
  validate-po.py [--quiet] [--check-empty] [--repo-root DIR] [file ...]

  --quiet         Suppress per-file output; only print summary
  --check-empty   Report entries with empty msgstr (coverage info)
  --repo-root     Repository root (default: auto-detect)
  file ...        Specific .po files to validate (default: all in repo)

Exit code: 0 = all clean, 1 = errors found

Requires: polib (pip install polib), falls back to msgfmt if unavailable.
"""

import argparse
import os
import re
import subprocess
import sys

try:
    import polib
    HAS_POLIB = True
except ImportError:
    HAS_POLIB = False


def find_po_files(repo_root):
    """Find all .po files in the repository."""
    po_files = []
    for dirpath, dirnames, filenames in os.walk(repo_root):
        # Skip hidden dirs and __pycache__
        dirnames[:] = [d for d in dirnames if not d.startswith('.') and d != '__pycache__']
        for f in filenames:
            if f.endswith('.po'):
                po_files.append(os.path.join(dirpath, f))
    return sorted(po_files)


def check_orphan_quotes(filepath):
    """Check for orphan bare-quote lines (broken multi-line collapse)."""
    errors = []
    with open(filepath, 'r', encoding='utf-8', errors='replace') as f:
        for lineno, line in enumerate(f, 1):
            if line.strip() == '"':
                errors.append(f"  ORPHAN_QUOTE line {lineno}")
    return errors


def validate_with_polib(filepath, check_empty=False):
    """Validate a PO file using polib."""
    errors = []
    empty_count = 0

    try:
        po = polib.pofile(filepath)
    except Exception as e:
        return [f"  PARSE_ERROR: {e}"], 0

    # Check 1: duplicate msgids
    seen = {}
    for entry in po:
        if not entry.msgid:  # skip header
            continue
        key = entry.msgid
        if key in seen:
            errors.append(f"  DUPLICATE_MSGID: '{key[:60]}' (first at line {seen[key]})")
        else:
            seen[key] = entry.linenum or 0

    # Check 2: unbalanced format placeholders (compare unique sets)
    # Skip English templates — empty msgstr is expected in source files
    is_english = '_en.po' in filepath or filepath.endswith('/messages_en.po')
    if not is_english:
        for entry in po:
            if not entry.msgid:
                continue
            src_ph = set(re.findall(r'\{(\d+)\}', entry.msgid))
            if not src_ph:
                continue
            if entry.msgid_plural:
                for idx, form in entry.msgstr_plural.items():
                    tgt_ph = set(re.findall(r'\{(\d+)\}', form))
                    if tgt_ph != src_ph:
                        errors.append(
                            f"  FORMAT_MISMATCH line {entry.linenum} msgstr[{idx}]: "
                            f"msgid has {{{','.join(sorted(src_ph))}}}, "
                            f"msgstr has {{{','.join(sorted(tgt_ph)) if tgt_ph else 'none'}}}"
                        )
            elif entry.msgstr:
                tgt_ph = set(re.findall(r'\{(\d+)\}', entry.msgstr))
                if tgt_ph != src_ph:
                    errors.append(
                        f"  FORMAT_MISMATCH line {entry.linenum}: "
                        f"msgid has {{{','.join(sorted(src_ph))}}}, "
                        f"msgstr has {{{','.join(sorted(tgt_ph)) if tgt_ph else 'none'}}}"
                    )

    # Check 3: plural form consistency (skip English templates — empty msgstr is expected)
    is_english = '_en.po' in filepath or '/en.po' in filepath
    for entry in po:
        if not entry.msgid:
            continue
        if entry.msgid_plural:
            if not is_english:
                if not entry.msgstr_plural or not any(entry.msgstr_plural.values()):
                    errors.append(f"  EMPTY_PLURAL line {entry.linenum}: '{entry.msgid[:50]}'")

    # Check 4: orphan quotes (raw file check)
    errors.extend(check_orphan_quotes(filepath))

    # Check 5: empty translations (optional)
    if check_empty:
        for entry in po:
            if not entry.msgid:
                continue
            if not entry.msgstr and not entry.msgid_plural:
                empty_count += 1
            elif entry.msgid_plural:
                if not entry.msgstr_plural or not any(entry.msgstr_plural.values()):
                    empty_count += 1

    return errors, empty_count


def validate_with_msgfmt(filepath):
    """Fallback: validate using msgfmt --check."""
    errors = []
    try:
        result = subprocess.run(
            ['msgfmt', '--check', '-o', '/dev/null', filepath],
            capture_output=True, text=True, timeout=30
        )
        for line in result.stderr.splitlines():
            if 'error:' in line.lower():
                errors.append(f"  MSGFMT: {line.strip()}")
    except FileNotFoundError:
        errors.append("  MSGFMT_NOT_FOUND: msgfmt not available")
    except subprocess.TimeoutExpired:
        errors.append("  MSGFMT_TIMEOUT")
    return errors


def main():
    parser = argparse.ArgumentParser(description='Validate .po files')
    parser.add_argument('--quiet', '-q', action='store_true',
                        help='Suppress per-file output')
    parser.add_argument('--check-empty', action='store_true',
                        help='Report entries with empty msgstr')
    parser.add_argument('--repo-root', default=None,
                        help='Repository root directory')
    parser.add_argument('files', nargs='*',
                        help='Specific .po files to validate')
    args = parser.parse_args()

    # Find repo root
    if args.repo_root:
        repo_root = args.repo_root
    else:
        script_dir = os.path.dirname(os.path.abspath(__file__))
        repo_root = os.path.join(script_dir, '..', '..')

    # Collect files
    if args.files:
        po_files = args.files
    else:
        po_files = find_po_files(repo_root)

    total_errors = 0
    total_empty = 0
    files_ok = 0
    files_bad = 0

    for filepath in po_files:
        file_errors = []
        file_empty = 0

        if HAS_POLIB:
            file_errors, file_empty = validate_with_polib(filepath, args.check_empty)
        else:
            file_errors = validate_with_msgfmt(filepath)
            file_errors.extend(check_orphan_quotes(filepath))

        total_empty += file_empty

        if file_errors:
            files_bad += 1
            total_errors += len(file_errors)
            if not args.quiet:
                print(f"ERRORS: {filepath}")
                for err in file_errors:
                    print(err)
        else:
            files_ok += 1

    total = files_ok + files_bad
    backend = "polib" if HAS_POLIB else "msgfmt"
    print(f"---")
    print(f"PO validation ({backend}): {files_ok}/{total} files clean, "
          f"{files_bad} with errors ({total_errors} total errors)")
    if args.check_empty:
        print(f"Empty translations: {total_empty}")

    return 1 if total_errors > 0 else 0


if __name__ == '__main__':
    sys.exit(main())
