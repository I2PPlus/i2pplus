#!/usr/bin/env python3
"""
align-md-tables.py — Align markdown tables and trim trailing whitespace.

Reads one or more markdown files, finds all pipe-delimited tables,
and rewrites them with vertically aligned column separators. A column's
alignment marker (``:---``, ``---:``, ``:---:``) is read from the separator row
and written back into it, so reformatting never changes how the table renders.
Also trims trailing whitespace, preserving intentional markdown line breaks
(exactly 2 trailing spaces). Accounts for CJK double-width characters.

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


def rendered_width(s):
    """Display width of a cell as it actually renders.

    A literal pipe inside a table cell is written ``\\|`` and renders as one
    character, so counting the backslash leaves that column a space too wide and
    the separators do not line up.
    """
    return display_width(s.replace('\\|', '|'))


def pad_to(s, target_width):
    """Pad string to target display width."""
    return s + ' ' * (target_width - rendered_width(s))


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


# Split a row on cell separators only. A pipe written ``\|`` is data, not a
# separator, so splitting on every pipe turns one cell into several and
# silently corrupts the row. The ``(?:\\\\)*`` lets a cell end in a literal
# backslash immediately before the separator.
CELL_SPLIT = re.compile(r'(?<!\\)(?:\\\\)*\|')


def parse_row(line):
    """Split a table row into cells, stripping whitespace.

    Escaped pipes stay inside their cell.
    """
    stripped = line.strip()
    if stripped.startswith('|'):
        stripped = stripped[1:]
    if stripped.endswith('|'):
        stripped = stripped[:-1]
    return [cell.strip() for cell in CELL_SPLIT.split(stripped)]


def separator_index(table_lines):
    """Which line of a table is its separator row, or None.

    A GFM table's separator is the second line, right under the header. That is
    where to look first: a body cell that is only dashes (a divider someone drew
    by hand) reads as a separator too, and the wrong one decides the alignment of
    every column.
    """
    if len(table_lines) > 1 and is_separator_row(table_lines[1]):
        return 1
    for i, line in enumerate(table_lines):
        if is_separator_row(line):
            return i
    return None


def column_alignments(sep_row):
    """Per-column alignment read off the separator row.

    ``:---`` left, ``---:`` right, ``:---:`` centre, ``---`` leave it to the
    renderer. The colons are not decoration: they are what the renderer aligns
    the column by, so rewriting the row as plain dashes changes how the table
    displays, not only how the source looks.
    """
    out = []
    for cell in sep_row:
        left, right = cell.startswith(':'), cell.endswith(':')
        out.append('center' if left and right else
                   'left' if left else 'right' if right else '')
    return out


def separator_cell(align, width):
    """A separator cell ``width`` wide, keeping its alignment marker.

    The colons count towards the width, so the pipes still line up with the rows
    above and below; a column never gets narrower than the marker it needs.
    """
    left = ':' if align in ('left', 'center') else ''
    right = ':' if align in ('right', 'center') else ''
    return left + '-' * max(width - len(left) - len(right), 1) + right


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
            widths[i] = max(widths[i], rendered_width(cell))

    # Read the alignment before the row that carries it is rewritten, and make
    # room for it: a right-aligned column of one-character cells still needs the
    # colon that says so.
    sep_at = separator_index(table_lines)
    aligns = column_alignments(parsed[sep_at]) if sep_at is not None else []
    for i, align in enumerate(aligns):
        if align:
            widths[i] = max(widths[i], 3 if align == 'center' else 2)

    # Rebuild lines
    result = []
    for i, row in enumerate(parsed):
        if i == sep_at:
            # Separator row: dashes matching column widths, marker kept
            cells = [separator_cell(aligns[j] if j < len(aligns) else '', widths[j])
                     for j in range(ncols)]
        else:
            # Data/header row: pad cells
            cells = [pad_to(row[j], widths[j]) for j in range(ncols)]
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
