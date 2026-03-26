#!/usr/bin/env python3
"""
Java source formatter wrapping google-java-format with I2P style conventions.

Usage:
    java-formatter.py [options] <file|directory> ...

Options:
    --dry-run, -n       Show diff of changes without modifying files
    --replace, -r       Apply changes in-place
    --imports-only      Only fix imports (sort + remove unused)
    --skip-imports      Skip import fixing
    --skip-javadoc      Skip javadoc reformatting
    --lines RANGE       Format only specific line range (e.g. 1:50)
    --help, -h          Show this help

I2P style post-edits applied after google-java-format:
    - Preserves original line lengths (unwraps aggressively wrapped lines)
    - Keeps opening braces on same line (K&R style)
    - Uses 4-space indentation (--aosp)

Examples:
    java-formatter.py --dry-run core/java/src/net/i2p/data/Hash.java
    java-formatter.py --replace --imports-only apps/i2psnark/java/src/
    java-formatter.py -n router/java/src/net/i2p/router/JobQueue.java
"""

import os
import sys
import subprocess
import difflib
import tempfile
import argparse
import re
import concurrent.futures
from functools import partial


SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
FORMATTER_JAR = os.path.join(SCRIPT_DIR, "google-java-format.jar")

# ── google-java-format wrapper ────────────────────────────────────────────────


def find_java_files(paths):
    """Expand directories to .java file lists."""
    files = []
    for p in paths:
        if os.path.isfile(p) and p.endswith(".java"):
            files.append(os.path.abspath(p))
        elif os.path.isdir(p):
            for root, dirs, fnames in os.walk(p):
                if any(s in root for s in ["/build/", "/.git/", "/obj/", "/tmp/"]):
                    continue
                for f in fnames:
                    if f.endswith(".java"):
                        files.append(os.path.join(root, f))
        else:
            print(f"Warning: not found: {p}", file=sys.stderr)
    return sorted(set(files))


def run_formatter(filepath, imports_only=False, lines=None, column_limit=None):
    """Run google-java-format on a file, return formatted content as string.
    I2P defaults are baked into the JAR: AOSP style, no wrapping, no javadoc,
    no import sorting, no string reflowing."""
    cmd = ["java", "-jar", FORMATTER_JAR]

    if imports_only:
        cmd.append("--fix-imports-only")
    if column_limit is not None:
        cmd.extend(["--column-limit", str(column_limit)])
    if lines:
        for r in lines:
            cmd.extend(["--lines", r])

    cmd.append(filepath)

    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
        if result.returncode != 0:
            print(f"Warning: formatter error for {filepath}: {result.stderr.strip()}", file=sys.stderr)
            return None
        return result.stdout
    except subprocess.TimeoutExpired:
        print(f"Warning: formatter timeout for {filepath}", file=sys.stderr)
        return None
    except FileNotFoundError:
        print(f"Error: {FORMATTER_JAR} not found", file=sys.stderr)
        sys.exit(1)


# ── Post-edit fixes ───────────────────────────────────────────────────────────


def join_wrapped_args(lines):
    """Join argument lists that google-java-format split across multiple lines.

    Pattern:
        foo(
            arg1,
            arg2)
    Becomes:
        foo(arg1, arg2)

    Only joins when result fits within 120 chars.
    """
    result = []
    i = 0
    while i < len(lines):
        line = lines[i]
        s = line.rstrip()

        # Skip javadoc, comments, string literals
        if s.lstrip().startswith("*") or s.lstrip().startswith("//"):
            result.append(line)
            i += 1
            continue

        # Detect: line ends with '(' and next line is deeper indented
        if not s.endswith("(") or i + 1 >= len(lines):
            result.append(line)
            i += 1
            continue

        # Collect the argument block
        base_indent = len(line) - len(line.lstrip())
        block = [s[:s.rfind("(") + 1]]  # prefix up to and including '('
        j = i + 1
        closed = False
        while j < len(lines):
            cs = lines[j].strip()
            cj_indent = len(lines[j]) - len(lines[j].lstrip())
            # Stop if we hit a line at same or lesser indent that isn't an arg
            if cj_indent <= base_indent and not cs.startswith(",") and not cs == ")":
                break
            block.append(cs)
            if cs.endswith(")"):
                closed = True
                j += 1
                break
            if cs.endswith(");"):
                closed = True
                j += 1
                break
            j += 1

        if not closed:
            # Couldn't find closing paren, leave as-is
            result.append(line)
            i += 1
            continue

        # Try to join: "foo(arg1, arg2)"
        args = " ".join(b.rstrip(",").rstrip(")") for b in block[1:-1] if b not in (")", ");"))
        closing = block[-1][-1] if block[-1].endswith(")") else block[-1][-2:]
        joined = block[0] + args + ("" if block[0].endswith("(") else "") + closing

        # Clean up: ensure proper spacing
        joined = re.sub(r'\(\s+', '(', joined)
        joined = re.sub(r'\s+\)', ')', joined)
        joined = re.sub(r',(?!\s)', ', ', joined)

        if len(joined) <= 120:
            result.append(" " * base_indent + joined.lstrip())
            i = j
        else:
            result.append(line)
            i += 1

    return result


def join_continuation_lines(lines):
    """Join lines that google-java-format split for wrapping.

    A continuation line starts with deeper indentation than the previous
    code line and isn't a standalone statement. Join it back to the
    previous line. Skip javadoc, string literals, and comments.
    """
    result = []
    i = 0
    while i < len(lines):
        line = lines[i]
        s = line.strip()

        # Skip empty lines, javadoc, comments
        if not s or s.startswith("*") or s.startswith("//") or s.startswith("/*"):
            result.append(line)
            i += 1
            continue

        # Check if next line is a continuation (deeper indent, not a new statement)
        if i + 1 >= len(lines):
            result.append(line)
            i += 1
            continue

        next_line = lines[i + 1]
        next_s = next_line.strip()

        # Next line is empty or comment — not a continuation
        if not next_s or next_s.startswith("//") or next_s.startswith("*"):
            result.append(line)
            i += 1
            continue

        cur_indent = len(line) - len(line.lstrip())
        next_indent = len(next_line) - len(next_line.lstrip())

        # Next line must be indented more than current
        if next_indent <= cur_indent:
            result.append(line)
            i += 1
            continue

        # Don't join if current line opens a block (ends with {)
        if s.endswith("{"):
            result.append(line)
            i += 1
            continue

        # Don't join if current line is a standalone statement (ends with ; or })
        if s.endswith(";") or s.endswith("}") or s.endswith("};"):
            result.append(line)
            i += 1
            continue

        # Don't join if next line starts a block
        if next_s.startswith("{"):
            result.append(line)
            i += 1
            continue

        # Join: current line + space + next line content (strip leading indent)
        joined = line.rstrip() + " " + next_s
        result.append(" " * cur_indent + joined.lstrip())
        i += 2  # skip the continuation line we just joined

    return result


def post_edit(content):
    """Apply I2P-style post-edits to google-java-format output."""
    lines = content.split("\n")
    # Run twice to handle multi-line continuations (e.g. 3+ line arg lists)
    lines = join_continuation_lines(lines)
    lines = join_continuation_lines(lines)
    return "\n".join(lines)


# ── Main ──────────────────────────────────────────────────────────────────────


def format_file(filepath, args):
    """Format a single file. Returns (original, formatted) or (None, None) on error."""
    try:
        with open(filepath, encoding="utf-8") as f:
            original = f.read()
    except (OSError, UnicodeDecodeError) as e:
        print(f"Warning: cannot read {filepath}: {e}", file=sys.stderr)
        return None, None

    formatted = run_formatter(
        filepath,
        imports_only=args.imports_only,
        lines=args.lines,
        column_limit=getattr(args, 'column_limit', None),
    )

    if formatted is None:
        return None, None

    # Apply post-edits unless imports-only mode
    if not args.imports_only:
        formatted = post_edit(formatted)

    return original, formatted


def process_one(filepath, args):
    """Format a single file. Returns (filepath, original, formatted) or None."""
    original, formatted = format_file(filepath, args)
    if original is None or original == formatted:
        return None
    return (filepath, original, formatted)


def main():
    parser = argparse.ArgumentParser(
        description="Java formatter with I2P style conventions (AOSP indent, no wrapping)",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="I2P defaults: AOSP style, no line wrapping, no javadoc changes,\n"
               "no import sorting, no string reflowing. All baked into the JAR.\n\n"
               "Examples:\n"
               "  java-formatter.py -n core/java/src/net/i2p/data/\n"
               "  java-formatter.py -r apps/i2psnark/java/src/\n"
               "  java-formatter.py -n --imports-only core/java/src/\n"
               "  java-formatter.py -n --column-limit 100 Foo.java\n"
    )
    parser.add_argument("paths", nargs="+", help="Files or directories to format")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("-n", "--dry-run", action="store_true",
                      help="Show diff without modifying files")
    mode.add_argument("-r", "--replace", action="store_true",
                      help="Apply changes in-place")
    parser.add_argument("--imports-only", action="store_true",
                        help="Only fix imports (sort + remove unused)")
    parser.add_argument("--column-limit", type=int, default=None,
                        help="Max column width (enables wrapping; default: no wrapping)")
    parser.add_argument("--lines", nargs="+", metavar="RANGE",
                        help="Line ranges to format (e.g. 1:50 100:200)")
    parser.add_argument("--threads", type=int, default=0,
                        help="Number of threads (default: all cores)")

    args = parser.parse_args()

    if not args.dry_run and not args.replace:
        args.dry_run = True  # default to dry-run

    files = find_java_files(args.paths)
    if not files:
        print("No .java files found", file=sys.stderr)
        sys.exit(1)

    threads = args.threads or os.cpu_count() or 4
    print(f"Formatting {len(files)} file(s) with {threads} threads", file=sys.stderr)

    # Process files in parallel
    results = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=threads) as pool:
        futures = {pool.submit(process_one, f, args): f for f in files}
        for future in concurrent.futures.as_completed(futures):
            result = future.result()
            if result is not None:
                results.append(result)

    # Sort results by filepath for deterministic output
    results.sort(key=lambda r: r[0])

    changed = 0
    for filepath, original, formatted in results:
        changed += 1

        if args.dry_run:
            rel = os.path.relpath(filepath)
            diff = difflib.unified_diff(
                original.splitlines(keepends=True),
                formatted.splitlines(keepends=True),
                fromfile=f"a/{rel}",
                tofile=f"b/{rel}",
            )
            sys.stdout.writelines(diff)
        elif args.replace:
            with open(filepath, "w", encoding="utf-8") as f:
                f.write(formatted)
            print(f"  {os.path.relpath(filepath)}", file=sys.stderr)

    if args.dry_run:
        print(f"\n{changed} file(s) would be modified", file=sys.stderr)
    else:
        print(f"\n{changed} file(s) modified", file=sys.stderr)


if __name__ == "__main__":
    main()
