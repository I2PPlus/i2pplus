#!/usr/bin/env python3
"""
Fix indentation and trailing whitespace violations found by Checkstyle.

Usage: fix-style.py -p <directory> [-x report.xml] [--dry-run] [--trailing-only] [--no-exclude]

Runs Checkstyle on the given path (or uses existing XML report),
extracts violations, and rewrites files.

Fixes:
  - Unused imports (google-java-format --fix-imports-only)
  - Leading tabs (tabs → 4 spaces)
  - Keyword and comma whitespace (if( → if (, x,y → x, y)
  - Brace and paren whitespace ({ x } → {x}, ( x ) → (x))
  - No whitespace before (; ; → ;, , → ,)
  - Upper ell (100l → 100L)
  - Empty statements (;; → ;)
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
CHECKSTYLE_CFG = os.path.join(SCRIPT_DIR, "checkstyle.xml")
GOOGLE_FORMAT_JAR = os.path.join(SCRIPT_DIR, "..", "google-java-format.jar")


def load_exclusions(cfg_path=None):
    """Load exclusion patterns from checkstyle.xml BeforeExecutionExclusionFileFilter."""
    if cfg_path is None:
        cfg_path = CHECKSTYLE_CFG
    try:
        with open(cfg_path) as f:
            content = f.read()
        root = ET.fromstring(content)
    except (OSError, ET.ParseError):
        return []
    patterns = []
    for mod in root.findall(".//module[@name='BeforeExecutionExclusionFileFilter']"):
        prop = mod.find("property[@name='fileNamePattern']")
        if prop is not None:
            patterns.append(re.compile(prop.attrib["value"]))
    return patterns


EXCLUDE_PATTERNS = load_exclusions()


def is_excluded(filepath):
    """Check if a file path matches any exclusion pattern from checkstyle.xml."""
    for pattern in EXCLUDE_PATTERNS:
        if pattern.search(filepath):
            return True
    return False

INDENT_ONLY_CFG = """<?xml version="1.0"?>
<!DOCTYPE module PUBLIC "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
  "https://checkstyle.org/dtds/configuration_1_3.dtd">
<module name="Checker">
  <property name="fileExtensions" value="java"/>
  <module name="BeforeExecutionExclusionFileFilter">
    <property name="fileNamePattern" value=".*/.*_jsp\\.java$"/>
  </module>
  <module name="BeforeExecutionExclusionFileFilter">
    <property name="fileNamePattern" value=".*[/\\\\]WEB-INF[/\\\\].*"/>
  </module>
  <module name="BeforeExecutionExclusionFileFilter">
    <property name="fileNamePattern" value=".*[/\\\\]jetty[/\\\\].*"/>
  </module>
  <module name="BeforeExecutionExclusionFileFilter">
    <property name="fileNamePattern" value=".*[/\\\\]com[/\\\\]maxmind[/\\\\].*"/>
  </module>
  <module name="BeforeExecutionExclusionFileFilter">
    <property name="fileNamePattern" value=".*[/\\\\]com[/\\\\]freenetproject[/\\\\].*"/>
  </module>
  <module name="BeforeExecutionExclusionFileFilter">
    <property name="fileNamePattern" value=".*[/\\\\]org[/\\\\]freenetproject[/\\\\].*"/>
  </module>
  <module name="BeforeExecutionExclusionFileFilter">
    <property name="fileNamePattern" value=".*[/\\\\]UPnP\\.java$"/>
  </module>
  <module name="BeforeExecutionExclusionFileFilter">
    <property name="fileNamePattern" value=".*[/\\\\]pack200[/\\\\].*"/>
  </module>
  <module name="BeforeExecutionExclusionFileFilter">
    <property name="fileNamePattern" value=".*[/\\\\]jrobin[/\\\\].*"/>
  </module>
  <module name="BeforeExecutionExclusionFileFilter">
    <property name="fileNamePattern" value=".*[/\\\\]com[/\\\\]southernstorm[/\\\\].*"/>
  </module>
  <module name="BeforeExecutionExclusionFileFilter">
    <property name="fileNamePattern" value=".*[/\\\\]wrapper[/\\\\].*"/>
  </module>
  <module name="BeforeExecutionExclusionFileFilter">
    <property name="fileNamePattern" value=".*[/\\\\]json[/\\\\]simple[/\\\\].*"/>
  </module>
  <module name="BeforeExecutionExclusionFileFilter">
    <property name="fileNamePattern" value=".*[/\\\\]thetransactioncompany[/\\\\].*"/>
  </module>
  <module name="BeforeExecutionExclusionFileFilter">
    <property name="fileNamePattern" value=".*[/\\\\]build[/\\\\].*"/>
  </module>
  <module name="BeforeExecutionExclusionFileFilter">
    <property name="fileNamePattern" value=".*[/\\\\]org[/\\\\]apache[/\\\\].*"/>
  </module>
  <module name="BeforeExecutionExclusionFileFilter">
    <property name="fileNamePattern" value=".*[/\\\\]org[/\\\\]minidns[/\\\\].*"/>
  </module>
  <module name="BeforeExecutionExclusionFileFilter">
    <property name="fileNamePattern" value=".*[/\\\\]org[/\\\\]bouncycastle[/\\\\].*"/>
  </module>
  <module name="BeforeExecutionExclusionFileFilter">
    <property name="fileNamePattern" value=".*[/\\\\]org[/\\\\]cybergarage[/\\\\].*"/>
  </module>
  <module name="BeforeExecutionExclusionFileFilter">
    <property name="fileNamePattern" value=".*[/\\\\]com[/\\\\]mpatric[/\\\\].*"/>
  </module>
  <module name="BeforeExecutionExclusionFileFilter">
    <property name="fileNamePattern" value=".*[/\\\\]metanotion[/\\\\].*"/>
  </module>
  <module name="BeforeExecutionExclusionFileFilter">
    <property name="fileNamePattern" value=".*[/\\\\]vuze[/\\\\].*"/>
  </module>
  <module name="BeforeExecutionExclusionFileFilter">
    <property name="fileNamePattern" value=".*[/\\\\]ndt[/\\\\].*"/>
  </module>
  <module name="BeforeExecutionExclusionFileFilter">
    <property name="fileNamePattern" value=".*[/\\\\]gnu[/\\\\].*"/>
  </module>
  <module name="BeforeExecutionExclusionFileFilter">
    <property name="fileNamePattern" value=".*[/\\\\]com[/\\\\]google[/\\\\].*"/>
  </module>
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
    try:
        with open(xml_file, "r") as f:
            content = f.read()
        # Truncate to last complete </error> or </file> tag, then close
        last_close = max(content.rfind("</error>"), content.rfind("</file>"))
        if last_close > 0:
            content = content[:last_close + content[last_close:].find(">") + 1]
        if not content.rstrip().endswith("</checkstyle>"):
            content = content.rstrip() + "\n</checkstyle>\n"
        root = ET.fromstring(content)
    except (ET.ParseError, OSError, IOError) as e:
        print(f"  Warning: could not parse XML ({e}), skipping", file=sys.stderr)
        return {}

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


def find_java_files(scan_path, apply_exclusions=True):
    """Find all .java files under scan_path, optionally filtering exclusions."""
    files = []
    if os.path.isfile(scan_path) and scan_path.endswith(".java"):
        if not apply_exclusions or not is_excluded(scan_path):
            files.append(scan_path)
    elif os.path.isdir(scan_path):
        for root, dirs, filenames in os.walk(scan_path):
            for f in filenames:
                if f.endswith(".java"):
                    fp = os.path.join(root, f)
                    if not apply_exclusions or not is_excluded(fp):
                        files.append(fp)
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


# Keywords that need whitespace after them
_WS_KEYWORDS = re.compile(r'\b(if|for|while|catch|switch|synchronized|try|return|throw|assert)\(')


def fix_whitespace_after(filepath, dry_run=False):
    """Add space after keywords before '('. Returns changes."""
    try:
        with open(filepath, "r") as f:
            content = f.read()
    except (OSError, IOError):
        return 0

    def replacer(m):
        return m.group(1) + " ("

    new_content = _WS_KEYWORDS.sub(replacer, content)
    changes = 1 if new_content != content else 0

    if changes > 0 and not dry_run:
        with open(filepath, "w") as f:
            f.write(new_content)

    return changes


_WS_INSIDE_PAREN = re.compile(r'\( (\S)')  # ( x → (x
_WS_BEFORE_CLOSE_PAREN = re.compile(r'(\S) \)')  # x ) → x)


def fix_paren_whitespace(filepath, dry_run=False):
    """Remove whitespace inside parens: ( x ) → (x). Returns changes."""
    try:
        with open(filepath, "r") as f:
            content = f.read()
    except (OSError, IOError):
        return 0

    prev = None
    while prev != content:
        prev = content
        content = _WS_INSIDE_PAREN.sub(r'(\1', content)
        content = _WS_BEFORE_CLOSE_PAREN.sub(r'\1)', content)
    changes = 1 if prev != content else 0

    if changes > 0 and not dry_run:
        with open(filepath, "w") as f:
            f.write(content)

    return changes


def parse_xml_violations(xml_file, check_suffix, filter_path=None):
    """Parse Checkstyle XML for a specific check, return {filepath: [line_numbers]}."""
    try:
        with open(xml_file, "r") as f:
            content = f.read()
        if not content.rstrip().endswith("</checkstyle>"):
            last_tag = -1
            for tag in ("</error>", "</file>"):
                pos = content.rfind(tag)
                if pos > last_tag:
                    last_tag = pos + len(tag)
            if last_tag > 0:
                content = content[:last_tag]
            content = content.rstrip() + "\n</checkstyle>\n"
        root = ET.fromstring(content)
    except (ET.ParseError, OSError, IOError):
        return {}
    fixes = {}
    for fnode in root.findall("file"):
        fname = fnode.attrib["name"]
        if filter_path and not fname.startswith(filter_path):
            continue
        for enode in fnode.findall("error"):
            source = enode.attrib.get("source", "")
            if check_suffix not in source:
                continue
            line_no = int(enode.attrib.get("line", "0"))
            fixes.setdefault(fname, []).append(line_no)
    return fixes


def parse_unused_imports(xml_file, filter_path=None):
    """Parse Checkstyle XML for UnusedImports, return {filepath: [line_numbers]}."""
    return parse_xml_violations(xml_file, "UnusedImportsCheck", filter_path)

    return fixes


def fix_unused_imports(filepath, lines_to_remove, dry_run=False):
    """Remove import lines at specified line numbers. Returns changes."""
    try:
        with open(filepath, "r") as f:
            lines = f.readlines()
    except (OSError, IOError):
        return 0

    remove_set = set(lines_to_remove)
    changes = 0
    new_lines = []
    for i, line in enumerate(lines):
        line_no = i + 1
        if line_no in remove_set:
            changes += 1
            continue
        new_lines.append(line)

    if changes > 0 and not dry_run:
        with open(filepath, "w") as f:
            f.writelines(new_lines)

    return changes


# Single-line control structures without braces
# Matches: if/else if/for/while (...) statement;  and  else statement;
# Also: do statement; while (...);
_NL_IF = re.compile(r'^(\s*(?:else\s+)?if\s*\(.*?\))\s+(?!\{)(.+;\s*)$', re.MULTILINE)
_NL_ELSE = re.compile(r'^(\s*else)\s+(?!\{|if)(.+;\s*)$', re.MULTILINE)
_NL_FOR = re.compile(r'^(\s*for\s*\(.*?\))\s+(?!\{)(.+;\s*)$', re.MULTILINE)
_NL_WHILE = re.compile(r'^(\s*while\s*\(.*?\))\s+(?!\{)(.+;\s*)$', re.MULTILINE)
_NL_DO = re.compile(r'^(\s*do)\s+(?!\{)(.+?;)\s*(while\s*\(.*?\);\s*)$', re.MULTILINE)


def fix_need_braces(filepath, dry_run=False):
    """Add braces to single-line if/else/for/while/do bodies. Returns changes."""
    try:
        with open(filepath, "r") as f:
            content = f.read()
    except (OSError, IOError):
        return 0

    def wrap(m):
        return f"{m.group(1)} {{ {m.group(2).strip()} }}"

    def wrap_do(m):
        return f"{m.group(1)} {{ {m.group(2).strip()} }} {m.group(3)}"

    new_content = content
    new_content = _NL_IF.sub(wrap, new_content)
    new_content = _NL_ELSE.sub(wrap, new_content)
    new_content = _NL_FOR.sub(wrap, new_content)
    new_content = _NL_WHILE.sub(wrap, new_content)
    new_content = _NL_DO.sub(wrap_do, new_content)

    changes = 1 if new_content != content else 0

    if changes > 0 and not dry_run:
        with open(filepath, "w") as f:
            f.write(new_content)

    return changes


_BRACE_PAREN_WS = re.compile(r'(\{ )|( \})|(\( )|( \))')


def fix_brace_paren_whitespace(filepath, dry_run=False):
    """Remove whitespace around braces and parens: { x } → {x}, ( x ) → (x). Returns changes."""
    try:
        with open(filepath, "r") as f:
            content = f.read()
    except (OSError, IOError):
        return 0

    def replacer(m):
        if m.group(1): return "{"
        if m.group(2): return "}"
        if m.group(3): return "("
        if m.group(4): return ")"
        return m.group(0)

    prev = None
    while prev != content:
        prev = content
        content = _BRACE_PAREN_WS.sub(replacer, content)
    changes = 1 if prev != content else 0

    if changes > 0 and not dry_run:
        with open(filepath, "w") as f:
            f.write(content)

    return changes


_DOUBLE_SEMI = re.compile(r'(?<![\w()]);;+')
_EMPTY_BLOCK = re.compile(r'\{[ \t]*;\}')


def fix_empty_statements(filepath, dry_run=False):
    """Replace ;; with ; and {; } with {}. Returns changes."""
    try:
        with open(filepath, "r") as f:
            content = f.read()
    except (OSError, IOError):
        return 0

    new_content = _DOUBLE_SEMI.sub(";", content)
    new_content = _EMPTY_BLOCK.sub("{}", new_content)
    changes = 1 if new_content != content else 0

    if changes > 0 and not dry_run:
        with open(filepath, "w") as f:
            f.write(new_content)

    return changes


_SEMI_NO_SPACE = re.compile(r';(?![;})\s\047])(?=\S)')


def fix_semicolon_whitespace(filepath, dry_run=False):
    """Add space after ; when followed by non-whitespace: x;y → x; y. Returns changes."""
    try:
        with open(filepath, "r") as f:
            content = f.read()
    except (OSError, IOError):
        return 0

    lines = content.split('\n')
    fixed_lines = []
    for line in lines:
        stripped = line.lstrip()
        if stripped.startswith('//') or stripped.startswith('*'):
            fixed_lines.append(line)
            continue
        line = _SEMI_NO_SPACE.sub('; ', line)
        fixed_lines.append(line)
    new_content = '\n'.join(fixed_lines)

    changes = 1 if new_content != content else 0

    if changes > 0 and not dry_run:
        with open(filepath, "w") as f:
            f.write(new_content)

    return changes


_UPPER_L = re.compile(r'(\d)[lL]\b')


def fix_upper_ell(filepath, dry_run=False):
    """Replace long literal suffix l with L: 100l → 100L. Returns changes."""
    try:
        with open(filepath, "r") as f:
            content = f.read()
    except (OSError, IOError):
        return 0

    new_content = _UPPER_L.sub(r'\1L', content)
    changes = 1 if new_content != content else 0

    if changes > 0 and not dry_run:
        with open(filepath, "w") as f:
            f.write(new_content)

    return changes


_WS_BEFORE_SEMI = re.compile(r' (?=;)')  # ' ;' → ';'
_WS_BEFORE_COMMA = re.compile(r' (?=,)')  # ' ,' → ','


def fix_no_whitespace_before(filepath, dry_run=False):
    """Remove space before ; and ,. Returns changes."""
    try:
        with open(filepath, "r") as f:
            content = f.read()
    except (OSError, IOError):
        return 0

    lines = content.split('\n')
    fixed_lines = []
    for line in lines:
        stripped = line.lstrip()
        if stripped.startswith('//') or stripped.startswith('*'):
            fixed_lines.append(line)
            continue
        line = _WS_BEFORE_SEMI.sub('', line)
        line = _WS_BEFORE_COMMA.sub('', line)
        fixed_lines.append(line)
    new_content = '\n'.join(fixed_lines)

    changes = 1 if new_content != content else 0

    if changes > 0 and not dry_run:
        with open(filepath, "w") as f:
            f.write(new_content)

    return changes


def fix_imports(filepath, dry_run=False):
    """Remove unused imports using google-java-format --fix-imports-only. Returns changes."""
    if not os.path.exists(GOOGLE_FORMAT_JAR):
        return 0
    try:
        with open(filepath, "r") as f:
            original = f.read()
    except (OSError, IOError):
        return 0

    result = subprocess.run(
        ["java", "-jar", GOOGLE_FORMAT_JAR, "--fix-imports-only", "--skip-sorting-imports", filepath],
        capture_output=True, text=True, timeout=30)

    if result.returncode != 0:
        return 0

    fixed = result.stdout
    if fixed == original:
        return 0

    if not dry_run:
        with open(filepath, "w") as f:
            f.write(fixed)

    # Count removed lines
    removed = len(original.splitlines()) - len(fixed.splitlines())
    return max(removed, 1)


def fix_tabs(filepath, dry_run=False):
    """Replace leading tabs with spaces. Returns changes."""
    try:
        with open(filepath, "r") as f:
            lines = f.readlines()
    except (OSError, IOError):
        return 0

    changes = 0
    new_lines = []

    for line in lines:
        stripped = line.lstrip("\t")
        if stripped != line:
            leading_tabs = len(line) - len(stripped)
            new_line = "    " * leading_tabs + stripped
            changes += 1
            line = new_line
        new_lines.append(line)

    if changes > 0 and not dry_run:
        with open(filepath, "w") as f:
            f.writelines(new_lines)

    return changes


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
            if verbose:
                remaining = sum(len(v) for v in fixes.values())
                print(f"  {remaining} violations remain (can't auto-fix).", file=sys.stderr)
            break

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
    parser.add_argument("--no-exclude", action="store_true",
                        help="Don't apply exclusion patterns from checkstyle.xml")
    args = parser.parse_args()

    scan_path = os.path.abspath(args.path)
    if not os.path.exists(scan_path):
        print(f"ERROR: {scan_path} not found", file=sys.stderr)
        sys.exit(1)

    if args.dry_run:
        print("DRY RUN - no files will be modified\n")

    total_fixes = 0
    apply_exclusions = not args.no_exclude
    java_files = find_java_files(scan_path, apply_exclusions=apply_exclusions)
    dry_label = " (dry run)" if args.dry_run else ""

    def run_pass(label, fix_func, *args_list, **kwargs):
        """Run a fix pass, return total fixes."""
        count = 0
        for filepath in sorted(java_files):
            n = fix_func(filepath, *args_list, **kwargs)
            if n > 0:
                count += n
        return count

    def report(label, count):
        """Print pass result."""
        if count > 0:
            action = f"would fix{dry_label}" if args.dry_run else f"fixed{dry_label}"
            print(f"  {label}: {count} {action}", file=sys.stderr)
        else:
            print(f"  {label}: 0 (clean)", file=sys.stderr)

    # Pass 1: Unused imports (google-java-format --fix-imports-only)
    if not args.trailing_only:
        print("Checking unused imports...", file=sys.stderr)
        n = run_pass("imports", fix_imports, args.dry_run)
        report("Unused imports", n)
        total_fixes += n

    # Pass 1b: Redundant imports (from Checkstyle XML)
    if not args.trailing_only and args.xml:
        print("Checking redundant imports...", file=sys.stderr)
        redundant_fixes = parse_xml_violations(args.xml, "RedundantImportCheck", filter_path=scan_path)
        r_count = 0
        for filepath in sorted(redundant_fixes.keys()):
            n = fix_unused_imports(filepath, redundant_fixes[filepath], args.dry_run)
            if n > 0:
                r_count += n
        report("Redundant imports", r_count)
        total_fixes += r_count

    # Pass 2: Leading tabs → spaces
    if not args.trailing_only:
        print("Checking leading tabs...", file=sys.stderr)
        n = run_pass("tabs", fix_tabs, args.dry_run)
        report("Leading tabs", n)
        total_fixes += n

    # Pass 2b: Keyword and comma whitespace (if( → if (, x,y → x, y)
    if not args.trailing_only:
        print("Checking keyword/comma whitespace...", file=sys.stderr)
        n = run_pass("keywords", fix_whitespace_after, args.dry_run)
        report("Keyword/comma whitespace", n)
        total_fixes += n

    # Pass 2c: Semicolon whitespace (x;y → x; y)
    if not args.trailing_only:
        print("Checking semicolon whitespace...", file=sys.stderr)
        n = run_pass("semicolons", fix_semicolon_whitespace, args.dry_run)
        report("Semicolon whitespace", n)
        total_fixes += n

    # Pass 3: Brace and paren whitespace ({ x } → {x}, ( x ) → (x))
    if not args.trailing_only:
        print("Checking brace/paren whitespace...", file=sys.stderr)
        n = run_pass("braces", fix_brace_paren_whitespace, args.dry_run)
        report("Brace/paren whitespace", n)
        total_fixes += n

    # Pass 4: Empty statements (;; → ;)
    if not args.trailing_only:
        print("Checking empty statements...", file=sys.stderr)
        n = run_pass("empty", fix_empty_statements, args.dry_run)
        report("Empty statements", n)
        total_fixes += n

    # Pass 4b: Upper ell (100l → 100L)
    if not args.trailing_only:
        print("Checking upper ell...", file=sys.stderr)
        n = run_pass("ell", fix_upper_ell, args.dry_run)
        report("Upper ell", n)
        total_fixes += n

    # Pass 4c: No whitespace before (; ; → ;, , → ,)
    if not args.trailing_only:
        print("Checking whitespace before ; and ,...", file=sys.stderr)
        n = run_pass("before_semi", fix_no_whitespace_before, args.dry_run)
        report("No whitespace before", n)
        total_fixes += n

    # Pass 5: Indentation (iterative)
    if not args.trailing_only:
        print("Checking indentation...", file=sys.stderr)
        n = fix_indentation_iterative(scan_path, args.xml, args.dry_run)
        if n > 0:
            action = f"would fix{dry_label}" if args.dry_run else f"fixed{dry_label}"
            print(f"  Indentation: {n} {action}", file=sys.stderr)
        else:
            print(f"  Indentation: 0 (clean)", file=sys.stderr)
        total_fixes += n

    # Pass 6: Trailing whitespace
    print("Checking trailing whitespace...", file=sys.stderr)
    n = run_pass("trailing", fix_trailing_whitespace, args.dry_run)
    report("Trailing whitespace", n)
    total_fixes += n

    # Pass 7: Missing newline at end of file
    print("Checking missing newlines...", file=sys.stderr)
    newline_count = 0
    for filepath in sorted(java_files):
        if fix_missing_newline(filepath, args.dry_run):
            newline_count += 1
            total_fixes += 1
    report("Missing newlines", newline_count)

    print(f"\nTotal: {total_fixes} lines fixed{dry_label}", file=sys.stderr)


if __name__ == "__main__":
    main()
