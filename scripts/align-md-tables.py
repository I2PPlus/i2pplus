#!/usr/bin/env python3
"""
align-md-tables.py — Align markdown tables and trim trailing whitespace.

Reads one or more markdown files, finds all pipe-delimited tables,
and rewrites them with vertically aligned column separators. Also trims
trailing whitespace, preserving intentional markdown line breaks (exactly
2 trailing spaces). Accounts for CJK double-width characters.

Usage:
    align-md-tables.py FILE [FILE ...]
    align-md-tables.py --dry-run FILE
    align-md-tables.py --check FILE     (exit 1 if changes needed)
    align-md-tables.py -r PATH          (recursively find .md files)
    align-md-tables.py -r --check PATH  (CI mode)
    echo '|a|b|' | align-md-tables.py -
"""

import sys
import os
import re
import unicodedata
import argparse


def display_width(s):
    """Calculate display width: CJK/fullwidth chars count as 2."""
    w = 0
    for ch in s:
        if unicodedata.east_asian_width(ch) in ('W', 'F'):
            w += 2
        else:
            w += 1
    return w


def pad_to(s, target_width):
    """Pad string to target display width."""
    dw = display_width(s)
    return s + ' ' * (target_width - dw)


def is_table_row(line):
    """Return True if line is a pipe-delimited table row."""
    stripped = line.strip()
    return stripped.startswith('|') and stripped.endswith('|')


def is_separator_row(line):
    """Return True if line is a table separator (|---|---|)."""
    stripped = line.strip()
    if not (stripped.startswith('|') and stripped.endswith('|')):
        return False
    inner = stripped.strip('|').strip()
    return all(c in '-:| ' for c in inner)


def parse_row(line):
    """Split a table row into cells, stripping whitespace."""
    stripped = line.strip().strip('|')
    return [cell.strip() for cell in stripped.split('|')]


def align_table(table_lines):
    """Align a list of table row strings. Returns reformatted lines."""
    if len(table_lines) < 2:
        return table_lines

    # Parse all rows
    parsed = [parse_row(line) for line in table_lines]
    ncols = max(len(row) for row in parsed)

    # Normalize row lengths
    for row in parsed:
        while len(row) < ncols:
            row.append('')

    # Calculate max display width per column
    widths = [0] * ncols
    for row in parsed:
        for i, cell in enumerate(row):
            widths[i] = max(widths[i], display_width(cell))

    # Rebuild lines
    result = []
    for row in parsed:
        if is_separator_row(table_lines[len(result)]):
            # Separator row: dashes matching column widths
            cells = ['-' * widths[i] for i in range(ncols)]
        else:
            # Data/header row: pad cells
            cells = [pad_to(row[i], widths[i]) for i in range(ncols)]
        result.append('| ' + ' | '.join(cells) + ' |')

    return result


def trim_trailing_spaces(lines):
    """Trim trailing whitespace, preserving intentional markdown line breaks.

    Markdown uses two trailing spaces for a <br>, so:
    - Exactly 2 trailing spaces: preserve (intentional line break)
    - 1 or 3+ trailing spaces: trim (accidental)
    - Lines inside fenced code blocks: preserve as-is
    """
    result = []
    in_code_block = False

    for line in lines:
        if line.lstrip().startswith('```'):
            in_code_block = not in_code_block
            result.append(line.rstrip())
            continue

        if in_code_block:
            result.append(line)
            continue

        # Count trailing spaces
        stripped = line.rstrip()
        trailing = len(line) - len(stripped)

        if trailing == 2:
            result.append(line)  # Preserve intentional <br>
        else:
            result.append(stripped)

    return result


def align_tables_in_content(content):
    """Find all tables in markdown content and align them."""
    lines = content.splitlines()
    lines = trim_trailing_spaces(lines)
    output = []
    i = 0

    while i < len(lines):
        if is_table_row(lines[i]):
            # Collect contiguous table rows
            table_start = i
            while i < len(lines) and is_table_row(lines[i]):
                i += 1
            table_lines = lines[table_start:i]
            aligned = align_table(table_lines)
            output.extend(aligned)
        else:
            output.append(lines[i])
            i += 1

    result = '\n'.join(output)
    # Preserve trailing newline
    if content.endswith('\n') or content.endswith('\r\n'):
        result += '\n'
    return result


def process_file(filepath, dry_run=False, check=False):
    """Align tables in a markdown file. Returns True if changes were made."""
    if filepath == '-':
        content = sys.stdin.read()
        aligned = align_tables_in_content(content)
        if check:
            return content != aligned + '\n'
        sys.stdout.write(aligned + '\n')
        return content != aligned + '\n'

    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    # Normalize line endings for processing
    had_crlf = '\r\n' in content
    normalized = content.replace('\r\n', '\n')

    aligned = align_tables_in_content(normalized)

    # Restore line endings if original used CRLF
    if had_crlf:
        aligned = aligned.replace('\n', '\r\n')

    changed = content != aligned

    if check:
        if changed:
            print(f"{filepath}: tables not aligned")
        return changed

    if changed:
        if dry_run:
            print(f"{filepath}: would reformat")
        else:
            with open(filepath, 'w', encoding='utf-8', newline='') as f:
                f.write(aligned)
            print(f"{filepath}: reformatted")

    return changed


def collect_files(paths, recursive=False):
    """Expand paths: directories become lists of .md files (optionally recursive)."""
    result = []
    for path in paths:
        if path == '-':
            result.append('-')
        elif os.path.isdir(path):
            if recursive:
                for dirpath, _dirnames, filenames in os.walk(path):
                    for f in sorted(filenames):
                        if f.endswith('.md') or f.endswith('.txt'):
                            result.append(os.path.join(dirpath, f))
            else:
                for f in sorted(os.listdir(path)):
                    if f.endswith('.md') or f.endswith('.txt'):
                        result.append(os.path.join(path, f))
        elif os.path.isfile(path):
            result.append(path)
        else:
            print(f"{path}: not found", file=sys.stderr)
    return result


def main():
    parser = argparse.ArgumentParser(
        description='Align markdown table column separators.',
    )
    parser.add_argument(
        'paths', nargs='+', metavar='PATH',
        help='Files or directories to process (use - for stdin)',
    )
    parser.add_argument(
        '-r', '--recursive', action='store_true',
        help='Recursively find .md/.txt files in directories',
    )
    parser.add_argument(
        '--dry-run', action='store_true',
        help='Show which files would be changed without modifying them',
    )
    parser.add_argument(
        '--check', action='store_true',
        help='Exit 1 if any file needs reformatting (for CI)',
    )
    args = parser.parse_args()

    files = collect_files(args.paths, recursive=args.recursive)
    if not files:
        print("No files found", file=sys.stderr)
        sys.exit(1)

    any_changed = False
    for filepath in files:
        try:
            changed = process_file(filepath, dry_run=args.dry_run, check=args.check)
            any_changed = any_changed or changed
        except Exception as e:
            print(f"{filepath}: error: {e}", file=sys.stderr)
            any_changed = True

    if args.check and any_changed:
        sys.exit(1)


if __name__ == '__main__':
    main()
