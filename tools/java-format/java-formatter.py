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
import fnmatch
import concurrent.futures
from functools import partial


SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
FORMATTER_JAR = os.path.join(SCRIPT_DIR, "google-java-format.jar")
DEFAULT_EXCLUDE = os.path.join(SCRIPT_DIR, "exclude.txt")


def load_excludes(path):
    """Load exclude patterns from file. Returns list of fnmatch patterns."""
    patterns = []
    if not os.path.exists(path):
        return patterns
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            patterns.append(line)
    return patterns


def is_excluded(filepath, patterns):
    """Check if a file matches any exclude pattern."""
    abs_path = filepath.replace("\\", "/")
    try:
        rel_path = os.path.relpath(abs_path).replace("\\", "/")
    except ValueError:
        rel_path = abs_path
    for pat in patterns:
        for path in (abs_path, rel_path):
            if fnmatch.fnmatch(path, pat) or fnmatch.fnmatch(os.path.basename(path), pat):
                return True
            if pat.endswith("/") and (path.startswith(pat.rstrip("/") + "/") or "/" + pat.lstrip("/") in path):
                return True
    return False


def find_java_files(paths, excludes=None):
    """Expand directories to .java file lists."""
    if excludes is None:
        excludes = []
    files = []
    for p in paths:
        if os.path.isfile(p) and p.endswith(".java"):
            if not is_excluded(os.path.abspath(p), excludes):
                files.append(os.path.abspath(p))
        elif os.path.isdir(p):
            for root, dirs, fnames in os.walk(p):
                if is_excluded(root + "/", excludes):
                    dirs.clear()
                    continue
                for f in fnames:
                    if f.endswith(".java"):
                        filepath = os.path.join(root, f)
                        if not is_excluded(filepath, excludes):
                            files.append(os.path.abspath(filepath))
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

        # Don't join if current line contains a // comment — joining would
        # turn subsequent code into a comment
        if "//" in line and not line.strip().startswith("//"):
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


def normalize_indentation(content):
    """Normalize indentation to 4-space multiples, preserving comment blocks."""
    lines_out = []
    in_block_comment = False
    for line in content.split("\n"):
        stripped = line.lstrip(" \t")
        if in_block_comment:
            lines_out.append(line)
            if "*/" in line:
                in_block_comment = False
            continue
        if stripped.startswith("/*") or stripped.startswith("*"):
            lines_out.append(line)
            if "*/" not in stripped:
                in_block_comment = True
            continue
        if not stripped:
            lines_out.append(line)
            continue
        leading = line[:len(line) - len(stripped)]
        spaces = leading.replace("\t", "    ")
        indent = len(spaces)
        normalized = indent // 4 * 4
        lines_out.append(" " * normalized + stripped)
    return "\n".join(lines_out)


def post_edit(content):
    """Apply I2P-style post-edits: join continuations and normalize indentation."""
    lines = content.split("\n")
    lines = join_continuation_lines(lines)
    lines = join_continuation_lines(lines)
    return normalize_indentation("\n".join(lines))

# ── Main ──────────────────────────────────────────────────────────────────────


def format_file(filepath, args):
    """Format a single file. Returns (original, formatted) or (None, None) on error."""
    try:
        with open(filepath, encoding="utf-8") as f:
            original = f.read()
    except (OSError, UnicodeDecodeError) as e:
        print(f"Warning: cannot read {filepath}: {e}", file=sys.stderr)
        return None, None

    cleaned = run_formatter(filepath)
    if cleaned is None:
        cleaned = original

    formatted = post_edit(cleaned)
    return original, formatted


def process_one(filepath, args, progress, verbose):
    """Format a single file. Returns (filepath, original, formatted) or None."""
    original, formatted = format_file(filepath, args)
    n = progress[0] = progress[0] + 1
    rel = os.path.relpath(filepath)
    if verbose:
        status = "changed" if (original and formatted and original != formatted) else "ok"
        if original is None:
            status = "skip"
        print(f"  [{status}] {rel}", file=sys.stderr)
    else:
        sys.stderr.write(f"\r  {n}/{progress[1]} files  ")
        sys.stderr.flush()
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
               "  java-formatter.py core/java/src/net/i2p/data/\n"
               "  java-formatter.py -r apps/i2psnark/java/src/\n"
               "  java-formatter.py --imports-only core/java/src/\n"
               "  java-formatter.py --column-limit 100 Foo.java\n"
               "  java-formatter.py --threads 4 apps/\n"
               "  java-formatter.py --exclude my-excludes.txt ./\n"
    )
    parser.add_argument("paths", nargs="+", help="Files or directories to format")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("-n", "--dry-run", action="store_true",
                      help="Show diff without modifying files (default)")
    mode.add_argument("-r", "--replace", action="store_true",
                      help="Apply changes in-place")
    parser.add_argument("--imports-only", action="store_true",
                        help="Only fix imports (sort + remove unused)")
    parser.add_argument("--column-limit", type=int, default=None,
                        help="Max column width (enables wrapping; default: no wrapping)")
    parser.add_argument("--threads", type=int, default=0,
                        help="Number of parallel threads (default: all cores)")
    parser.add_argument("--exclude", default=None, metavar="FILE",
                        help="Exclude patterns file (default: exclude.txt)")
    parser.add_argument("--lines", nargs="+", metavar="RANGE",
                        help="Line ranges to format (e.g. 1:50 100:200)")

    args = parser.parse_args()

    if not args.dry_run and not args.replace:
        args.dry_run = True  # default to dry-run

    exclude_path = args.exclude or DEFAULT_EXCLUDE
    excludes = load_excludes(exclude_path)
    if excludes:
        print(f"Loaded {len(excludes)} exclude patterns from {os.path.relpath(exclude_path)}", file=sys.stderr)

    files = find_java_files(args.paths, excludes)
    if not files:
        print("No .java files found", file=sys.stderr)
        sys.exit(1)

    threads = args.threads or os.cpu_count() or 4

    # Process files in parallel
    import time
    start = time.time()
    mode = "dry-run" if args.dry_run else "replace"
    verbose = len(files) <= 30
    progress = [0, len(files)]
    print(f"Formatting {len(files)} file(s) with {threads} threads ({mode})", file=sys.stderr)
    results = []
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=threads) as pool:
            futures = {pool.submit(process_one, f, args, progress, verbose): f for f in files}
            for future in concurrent.futures.as_completed(futures):
                result = future.result()
                if result is not None:
                    results.append(result)
    except KeyboardInterrupt:
        for f in futures:
            f.cancel()
        print(f"\nCancelled ({len(results)} files processed)", file=sys.stderr)
        sys.exit(130)

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

    elapsed = time.time() - start
    errors = [r for r in results if isinstance(r, tuple) and len(r) == 3 and r[1] is None]
    err_count = len(errors)

    # Write error log
    if err_count > 0:
        err_log = os.path.join(SCRIPT_DIR, "java-format-errors.txt")
        with open(err_log, "w") as f:
            for filepath, msg in sorted(errors):
                f.write(f"{os.path.relpath(filepath)}: {msg}\n")
        print(f"{err_count} error(s) logged to {os.path.relpath(err_log)}", file=sys.stderr)

    if args.dry_run:
        print(f"\n{changed} file(s) would be modified ({elapsed:.1f}s)", file=sys.stderr)
    else:
        print(f"\n{changed} file(s) modified ({elapsed:.1f}s)", file=sys.stderr)


if __name__ == "__main__":
    main()
