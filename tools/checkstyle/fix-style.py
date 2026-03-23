#!/usr/bin/env python3
"""
Fix indentation and trailing whitespace violations found by Checkstyle.

Usage: fix-style.py -p <directory> [-x report.xml] [--dry-run] [--trailing-only]

Runs Checkstyle on the given path (or uses existing XML report),
extracts violations, and rewrites files.

Fixes:
  - Indentation (iterative, from Checkstyle IndentationCheck)
  - Trailing whitespace (spaces/tabs before newline)
  - Missing newline at end of file
"""

import sys
import os
import re
import subprocess
import tempfile
import argparse
from xml.etree import ElementTree as ET

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CHECKSTYLE_JAR = os.path.join(SCRIPT_DIR, "checkstyle-all.jar")

INDENT_ONLY_CFG = """<?xml version="1.0"?>
<!DOCTYPE module PUBLIC "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
  "https://checkstyle.org/dtds/configuration_1_3.dtd">
<module name="Checker">
  <property name="fileExtensions" value="java"/>
  <module name="TreeWalker">
    <module name="Indentation">
      <property name="basicOffset" value="4"/>
      <property name="caseIndent" value="4"/>
      <property name="arrayInitIndent" value="4"/>
      <property name="lineWrappingIndentation" value="4"/>
      <property name="tabWidth" value="4"/>
      <property name="forceStrictCondition" value="false"/>
    </module>
  </module>
</module>"""


def run_checkstyle(scan_path, xml_out):
    """Run Checkstyle on scan_path, write XML to xml_out."""
    cfg = tempfile.NamedTemporaryFile(mode="w", suffix=".xml", delete=False)
    cfg.write(INDENT_ONLY_CFG)
    cfg.close()
    try:
        subprocess.run(
            ["java", "-jar", CHECKSTYLE_JAR,
             "-c", cfg.name, "-f", "xml", "-o", xml_out, scan_path],
            capture_output=True, timeout=600)
    finally:
        os.unlink(cfg.name)


def parse_violations(xml_file, filter_path=None):
    """Parse Checkstyle XML and return {filepath: [(line_no, [valid_indents])]}."""
    with open(xml_file, "r") as f:
        content = f.read()
    if not content.rstrip().endswith("</checkstyle>"):
        content = content.rstrip() + "\n</checkstyle>\n"
    root = ET.fromstring(content)

    fixes = {}
    for fnode in root.findall("file"):
        fname = fnode.attrib["name"]
        if filter_path and not fname.startswith(filter_path):
            continue
        for enode in fnode.findall("error"):
            source = enode.attrib.get("source", "")
            if "IndentationCheck" not in source:
                continue
            msg = enode.attrib.get("message", "")
            line_no = int(enode.attrib.get("line", "0"))

            # Single choice: "expected level should be 8."
            m = re.search(r"expected level should be (\d+)\.", msg)
            if m:
                fixes.setdefault(fname, []).append((line_no, [int(m.group(1))]))
                continue

            # Multi choice: "expected level should be one of the following: 8, 45, 47."
            m = re.search(r"one of the following:\s*([\d, ]+)", msg)
            if m:
                options = [int(x.strip()) for x in m.group(1).split(",")]
                fixes.setdefault(fname, []).append((line_no, options))

    return fixes


def fix_indentation(filepath, line_fixes, dry_run=False):
    """Fix indentation. line_fixes: [(line_no, [valid_indents])]. Returns changes."""
    try:
        with open(filepath, "r") as f:
            lines = f.readlines()
    except (OSError, IOError) as e:
        print(f"  SKIP {filepath}: {e}", file=sys.stderr)
        return 0

    # Group fixes by line: merge multiple violations for same line
    fix_map = {}
    for line_no, options in line_fixes:
        if line_no in fix_map:
            # Intersect: keep only common options
            existing = fix_map[line_no]
            fix_map[line_no] = [x for x in existing if x in options] or existing
        else:
            fix_map[line_no] = options

    changes = 0
    new_lines = []

    for i, line in enumerate(lines):
        line_no = i + 1
        if line_no in fix_map:
            options = fix_map[line_no]
            # Expand leading tabs to spaces for comparison
            stripped = line.lstrip(" \t")
            if not stripped:
                new_lines.append(line)
                continue
            leading = line[:len(line) - len(stripped)]
            actual_spaces = len(leading.expandtabs(4))

            # Pick the closest valid option to current indent
            target = min(options, key=lambda x: abs(x - actual_spaces))

            if actual_spaces != target:
                new_line = " " * target + stripped
                if new_line != line:
                    changes += 1
                    line = new_line
        new_lines.append(line)

    if changes > 0 and not dry_run:
        with open(filepath, "w") as f:
            f.writelines(new_lines)

    return changes


def find_java_files(scan_path):
    """Find all .java files under scan_path."""
    files = []
    if os.path.isfile(scan_path) and scan_path.endswith(".java"):
        files.append(scan_path)
    elif os.path.isdir(scan_path):
        for root, dirs, filenames in os.walk(scan_path):
            for f in filenames:
                if f.endswith(".java"):
                    files.append(os.path.join(root, f))
    return files


def fix_trailing_whitespace(filepath, dry_run=False):
    """Remove trailing spaces and tabs from lines. Returns number of changes."""
    try:
        with open(filepath, "r") as f:
            lines = f.readlines()
    except (OSError, IOError):
        return 0

    changes = 0
    new_lines = []
    for line in lines:
        if line.endswith("\n"):
            content = line[:-1]
            new_content = content.rstrip(" \t")
            new_line = new_content + "\n"
        else:
            new_line = line.rstrip(" \t")

        if new_line != line:
            changes += 1
        new_lines.append(new_line)

    if changes > 0 and not dry_run:
        with open(filepath, "w") as f:
            f.writelines(new_lines)

    return changes


def fix_missing_newline(filepath, dry_run=False):
    """Ensure file ends with a newline. Returns 1 if changed, 0 otherwise."""
    try:
        with open(filepath, "rb") as f:
            content = f.read()
    except (OSError, IOError):
        return 0

    if not content:
        return 0

    if content.endswith(b"\n"):
        return 0

    if not dry_run:
        with open(filepath, "ab") as f:
            f.write(b"\n")

    return 1


def fix_indentation_iterative(scan_path, filter_path, dry_run=False, verbose=True):
    """Run iterative fix-indentation passes until no more violations."""
    total_fixes = 0
    for iteration in range(10):
        xml_out = tempfile.mktemp(suffix=".xml")
        try:
            run_checkstyle(scan_path, xml_out)
            fixes = parse_violations(xml_out, filter_path=filter_path)
        finally:
            os.unlink(xml_out)

        if not fixes:
            if verbose and iteration > 0:
                print(f"  Converged after {iteration + 1} passes.", file=sys.stderr)
            break

        if verbose and iteration == 0:
            total_violations = sum(len(v) for v in fixes.values())
            print(f"  {total_violations} violations in {len(fixes)} files", file=sys.stderr)

        pass_fixes = 0
        pass_files = 0
        for filepath in sorted(fixes.keys()):
            n = fix_indentation(filepath, fixes[filepath], dry_run)
            if n > 0:
                pass_fixes += n
                pass_files += 1

        total_fixes += pass_fixes

        if pass_fixes == 0:
            # No changes made but violations remain — can't auto-fix
            if verbose:
                remaining = sum(len(v) for v in fixes.values())
                print(f"  {remaining} violations remain (can't auto-fix).", file=sys.stderr)
            break

        if verbose:
            action = "would fix" if dry_run else "fixed"
            print(f"  Pass {iteration + 1}: {pass_fixes} lines {action} in {pass_files} files", file=sys.stderr)

    return total_fixes


def main():
    parser = argparse.ArgumentParser(
        description="Fix indentation and trailing whitespace violations.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="Example:\n"
               "  ant checkstyle\n"
               "  fix-style.py -p core/java/src/net/i2p -x dist/checkstyle.xml --dry-run\n"
               "  fix-style.py -p router/java/src -x dist/checkstyle.xml\n"
               "  fix-style.py -p apps/i2psnark/java --trailing-only")
    parser.add_argument("-p", "--path", required=True,
                        help="Directory to scan and fix")
    parser.add_argument("-x", "--xml",
                        help="Existing Checkstyle XML report (if omitted, runs checkstyle on -p)")
    parser.add_argument("-n", "--dry-run", action="store_true",
                        help="Preview changes without modifying files")
    parser.add_argument("-t", "--trailing-only", action="store_true",
                        help="Only fix trailing whitespace and newlines, skip indentation")
    args = parser.parse_args()

    scan_path = os.path.abspath(args.path)
    if not os.path.exists(scan_path):
        print(f"ERROR: {scan_path} not found", file=sys.stderr)
        sys.exit(1)

    if args.dry_run:
        print("DRY RUN - no files will be modified\n")

    total_fixes = 0

    # Pass 1: Indentation (iterative)
    if not args.trailing_only:
        print("Fixing indentation...", file=sys.stderr)
        total_fixes += fix_indentation_iterative(scan_path, args.xml, args.dry_run)
    else:
        print("Skipping indentation (--trailing-only)", file=sys.stderr)

    # Pass 2: Trailing whitespace
    print("Fixing trailing whitespace...", file=sys.stderr)
    java_files = find_java_files(scan_path)
    trailing_files = 0
    trailing_fixes = 0
    for filepath in sorted(java_files):
        n = fix_trailing_whitespace(filepath, args.dry_run)
        if n > 0:
            trailing_fixes += n
            trailing_files += 1
            action = "would fix" if args.dry_run else "fixed"
            print(f"  {filepath}: {n} trailing lines {action}")

    total_fixes += trailing_fixes

    if trailing_fixes == 0:
        print("No trailing whitespace found.", file=sys.stderr)

    # Pass 3: Missing newline at end of file
    print("Fixing missing newlines...", file=sys.stderr)
    newline_files = 0
    for filepath in sorted(java_files):
        n = fix_missing_newline(filepath, args.dry_run)
        if n > 0:
            newline_files += 1
            total_fixes += 1
            action = "would fix" if args.dry_run else "fixed"
            print(f"  {filepath}: missing newline {action}")

    if newline_files == 0:
        print("No missing newlines found.", file=sys.stderr)

    total_files = trailing_files + newline_files
    print(f"\nTotal: {total_fixes} lines fixed", file=sys.stderr)


if __name__ == "__main__":
    main()
