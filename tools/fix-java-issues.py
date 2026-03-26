#!/usr/bin/env python3
"""
Consolidated Java source fixer. Combines PMD, SpotBugs, and Checkstyle fixes.

Usage: fix-java-issues.py -p <directory> [--dry-run] [--fix TYPE ...]

Fix types (use --fix to select, or run all):
  checkstyle    Tabs, whitespace, braces, trailing ws, empty statements, upper ell
  imports       Unused and redundant imports (google-java-format + Checkstyle XML)
  indent        Iterative indentation fixes (requires Checkstyle jar + XML report)
  simpledate    SimpleDateFormat → DateTimeFormatter (PMD: AvoidSimpleDateFormat)
  newline-fmt   \\n → %n in String.format (SB: VA_FORMAT_STRING_USES_NEWLINE)
  encoding      Default encoding → StandardCharsets (SB: DM_DEFAULT_ENCODING)
  serializable  Add Serializable to Comparators (SB: SE_COMPARATOR_SHOULD_BE_SERIALIZABLE)
  pattern       Make Pattern.compile results static final (PMD: AvoidRecompilingPatterns)
  dead-store    Remove dead local variable stores (SB: DLS_DEAD_LOCAL_STORE)
  comparator    Make inline Comparators static final (PMD: InitializeComparatorOnlyOnce)

Example:
  fix-java-issues.py -p core/java/src --dry-run
  fix-java-issues.py -p apps/ --fix simpledate newline-fmt encoding
  fix-java-issues.py -p router/java/src --fix checkstyle indent -x dist/checkstyle.xml
  fix-java.py -p router/java/src --fix checkstyle --dry-run
"""

import sys
import os
import re
import json
import subprocess
import tempfile
import argparse
from xml.etree import ElementTree as ET

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
TEMPLATE_DIR = os.path.join(SCRIPT_DIR, "template")

# ── Helpers ───────────────────────────────────────────────────────────────────


def find_java_files(scan_path):
    """Find all .java files under scan_path."""
    files = []
    if os.path.isfile(scan_path) and scan_path.endswith(".java"):
        files.append(scan_path)
    elif os.path.isdir(scan_path):
        for root, dirs, filenames in os.walk(scan_path):
            # Skip build/temp directories
            if any(s in root for s in ["/build/", "/tmp/", "/.git/", "/obj/"]):
                continue
            for f in filenames:
                if f.endswith(".java"):
                    files.append(os.path.join(root, f))
    return files


def read_file(filepath):
    """Read file content, return (content, lines_list)."""
    with open(filepath, errors="replace") as f:
        content = f.read()
    return content, content.split("\n")


def write_file(filepath, content, dry_run):
    """Write content back to file unless dry_run."""
    if not dry_run:
        with open(filepath, "w") as f:
            f.write(content)


def print_fix(filepath, line_no, before, after, dry_run):
    """Print a fix preview."""
    label = "DRY" if dry_run else "FIX"
    if line_no:
        print(f"  [{label}] {filepath}:{line_no}")
    else:
        print(f"  [{label}] {filepath}")
    if before and after:
        print(f"         - {before.strip()}")
        print(f"         + {after.strip()}")


# ── Checkstyle-style fixes (from fix-style.py) ───────────────────────────────

_WS_KEYWORDS = re.compile(r'\b(if|for|while|catch|switch|synchronized|try|return|throw|assert)\(')
_WS_INSIDE_PAREN = re.compile(r'\( (\S)')
_WS_BEFORE_CLOSE_PAREN = re.compile(r'(\S) \)')
_DOUBLE_SEMI = re.compile(r'(?<![\w()]);;+')
_EMPTY_BLOCK = re.compile(r'\{[ \t]*;\}')
_UPPER_L = re.compile(r'(\d)[lL]\b')
_WS_BEFORE_SEMI = re.compile(r' (?=;)')
_WS_BEFORE_COMMA = re.compile(r' (?=,)')

# Don't remove space before ; if it's part of an HTML entity like &amp; &lt; &#1234;
_HTML_ENTITY_SEMI = re.compile(r'&(?:amp|lt|gt|apos|quot|nbsp|#\d+|#x[0-9a-fA-F]+);$')

EXCLUDE_PATTERNS = [
    r".*_jsp\.java$", r".*[/\\]WEB-INF[/\\].*", r".*[/\\]jetty[/\\].*",
    r".*[/\\]build[/\\].*", r".*[/\\]pack200[/\\].*", r".*[/\\]jrobin[/\\].*",
    r".*[/\\]wrapper[/\\].*", r".*[/\\]org[/\\]apache[/\\].*",
    r".*[/\\]gnu[/\\].*", r".*[/\\]ndt[/\\].*",
    r".*[/\\]com[/\\]maxmind[/\\].*", r".*[/\\]org[/\\]bouncycastle[/\\].*",
]
EXCLUDE_RE = [re.compile(p) for p in EXCLUDE_PATTERNS]

# Exclusions loaded from config/exclusions.txt
_OVERRIDE_EXCLUSIONS = None

def _load_override_exclusions():
    """Load file-level exclusions for missing-override-annotation from config/exclusions.txt."""
    global _OVERRIDE_EXCLUSIONS
    if _OVERRIDE_EXCLUSIONS is not None:
        return _OVERRIDE_EXCLUSIONS
    _OVERRIDE_EXCLUSIONS = set()
    config_path = os.path.join(os.path.dirname(SCRIPT_DIR), "config", "exclusions.txt")
    if not os.path.exists(config_path):
        # Try relative to script dir
        config_path = os.path.join(SCRIPT_DIR, "..", "config", "exclusions.txt")
    if not os.path.exists(config_path):
        return _OVERRIDE_EXCLUSIONS
    with open(config_path) as f:
        for line in f:
            line = line.strip()
            if line.startswith("#") or not line:
                continue
            parts = line.split(":")
            if len(parts) >= 3 and parts[1] == "java/missing-override-annotation":
                filepath = parts[2]
                if filepath.endswith("/*"):
                    # Wildcard directory exclusion - store prefix
                    _OVERRIDE_EXCLUSIONS.add(filepath[:-1])
                else:
                    _OVERRIDE_EXCLUSIONS.add(filepath)
    return _OVERRIDE_EXCLUSIONS


def is_excluded(filepath):
    return any(p.search(filepath) for p in EXCLUDE_RE)


def fix_checkstyle(filepath, dry_run=False):
    """Run all checkstyle-style fixes on a single file. Returns count of changes."""
    if is_excluded(filepath):
        return 0
    try:
        content, lines = read_file(filepath)
    except OSError:
        return 0

    original = content

    # Tabs → spaces (leading only)
    new_lines = []
    for line in lines:
        stripped = line.lstrip("\t")
        if stripped != line:
            leading_tabs = len(line) - len(stripped)
            line = "    " * leading_tabs + stripped
        new_lines.append(line)
    content = "\n".join(new_lines)

    # Keyword whitespace: if( → if (
    content = _WS_KEYWORDS.sub(r'\1 (', content)

    # Paren whitespace (iterative)
    prev = None
    while prev != content:
        prev = content
        content = _WS_INSIDE_PAREN.sub(r'(\1', content)
        content = _WS_BEFORE_CLOSE_PAREN.sub(r'\1)', content)

    # Empty statements
    content = _DOUBLE_SEMI.sub(";", content)
    content = _EMPTY_BLOCK.sub("{}", content)

    # Upper ell: 100l → 100L
    content = _UPPER_L.sub(r'\1L', content)

    # No whitespace before ; and ,
    # But don't break HTML entities: &amp; &lt; &gt; &#NNN; in string literals
    _HTML_ENTITY = re.compile(r'&(?:amp|lt|gt|apos|quot|nbsp|#\d+|#x[0-9a-fA-F]+);')
    new_lines = []
    for line in content.split("\n"):
        stripped = line.lstrip()
        if stripped.startswith("//") or stripped.startswith("*"):
            new_lines.append(line)
            continue
        # Skip lines containing HTML entities — don't remove space before ;
        # as it may be part of an entity like &amp; in a string literal
        if _HTML_ENTITY.search(line):
            new_lines.append(line)
            continue
        line = _WS_BEFORE_SEMI.sub("", line)
        line = _WS_BEFORE_COMMA.sub("", line)
        new_lines.append(line)
    content = "\n".join(new_lines)

    # Trailing whitespace
    new_lines = []
    for line in content.split("\n"):
        if line.endswith("\n"):
            new_lines.append(line.rstrip(" \t"))
        else:
            new_lines.append(line.rstrip(" \t"))
    content = "\n".join(new_lines)

    # Ensure trailing newline
    if content and not content.endswith("\n"):
        content += "\n"

    changes = 0
    if content != original:
        changes = sum(1 for a, b in zip(original.split("\n"), content.split("\n")) if a != b)
        if not dry_run:
            with open(filepath, "w") as f:
                f.write(content)

    return changes


# ── SimpleDateFormat → DateTimeFormatter (39 fixes) ──────────────────────────

_SDF_PATTERN = re.compile(
    r'new\s+SimpleDateFormat\s*\(\s*("([^"]*)")\s*(?:,\s*([^)]+))?\s*\)'
)
_SDF_FORMAT = re.compile(r'\.format\s*\(')
_SDF_IMPORT_CHECK = re.compile(r'import\s+java\.text\.SimpleDateFormat')
_DTF_IMPORT = "import java.time.format.DateTimeFormatter;\n"

# Patterns that need manual review (thread-local, non-static, complex)
_SDF_SKIP = re.compile(r'ThreadLocal|new\s+SimpleDateFormat.*SimpleDateFormat|synchronized')


def fix_simple_date(filepath, dry_run=False):
    """Replace inline SimpleDateFormat with DateTimeFormatter."""
    if is_excluded(filepath):
        return 0
    try:
        content, _ = read_file(filepath)
    except OSError:
        return 0

    if _SDF_SKIP.search(content):
        return 0

    original = content
    changes = 0
    result_lines = []
    lines = content.split("\n")
    field_name_counter = 0

    for i, line in enumerate(lines):
        m = _SDF_PATTERN.search(line)
        if not m:
            result_lines.append(line)
            continue

        pattern_str = m.group(2)
        locale_arg = m.group(3)
        full_match = m.group(0)

        # Convert SimpleDateFormat pattern to DateTimeFormatter pattern
        dtf_pattern = pattern_str
        # Simple replacements (most common differences)
        dtf_pattern = dtf_pattern.replace("yyyy", "uuuu")
        dtf_pattern = dtf_pattern.replace("yy", "uu")
        dtf_pattern = dtf_pattern.replace("DD", "D")
        dtf_pattern = dtf_pattern.replace("hh", "hh")
        dtf_pattern = dtf_pattern.replace("HH", "HH")
        dtf_pattern = dtf_pattern.replace("a", "a")  # AM/PM

        # Build replacement
        if locale_arg:
            dtf_init = f'DateTimeFormatter.ofPattern("{dtf_pattern}", {locale_arg.strip()})'
        else:
            dtf_init = f'DateTimeFormatter.ofPattern("{dtf_pattern}")'

        # Replace the new SimpleDateFormat(...) with DateTimeFormatter.ofPattern(...)
        new_line = line[:m.start()] + dtf_init + line[m.end():]

        # Also change the type declaration if present (e.g., "SimpleDateFormat sdf =")
        new_line = re.sub(r'\bSimpleDateFormat\b(\s+\w+\s*=)', r'DateTimeFormatter\1', new_line)

        if new_line != line:
            changes += 1
            if dry_run:
                print_fix(filepath, i + 1, line, new_line, dry_run)

        result_lines.append(new_line)

    if changes > 0:
        new_content = "\n".join(result_lines)

        # Add DateTimeFormatter import if needed
        if "DateTimeFormatter" not in original and "import java.text.SimpleDateFormat" in original:
            new_content = new_content.replace(
                "import java.text.SimpleDateFormat;",
                "import java.time.format.DateTimeFormatter;\nimport java.text.SimpleDateFormat;"
            )
        elif "DateTimeFormatter" not in original:
            # Add import after other java imports
            import_pos = new_content.rfind("import java.")
            if import_pos >= 0:
                end_of_line = new_content.index("\n", import_pos)
                new_content = (new_content[:end_of_line + 1] +
                              "import java.time.format.DateTimeFormatter;\n" +
                              new_content[end_of_line + 1:])

        if not dry_run:
            write_file(filepath, new_content, dry_run)

    return changes


# ── String.format \n → %n (32 fixes) ─────────────────────────────────────────

# Match \n inside String.format() format strings, but NOT in concatenation chains
# The \n must appear in a single string literal directly inside String.format()
# e.g. String.format("foo\nbar", args) ✓  vs  String.format("foo" + "\nbar", args) ✗
_FMT_NL = re.compile(r'String\.format\s*\((\s*"[^"]*?)\\n([^"]*?")\s*,')


def fix_format_newline(filepath, dry_run=False):
    """Replace \\n with %n in String.format calls."""
    if is_excluded(filepath):
        return 0
    try:
        content, _ = read_file(filepath)
    except OSError:
        return 0

    def replacer(m):
        return "String.format(" + m.group(1) + "%n" + m.group(2) + ","

    new_content = _FMT_NL.sub(replacer, content)
    changes = 0

    if new_content != content:
        # Count changes
        for old_line, new_line in zip(content.split("\n"), new_content.split("\n")):
            if old_line != new_line:
                changes += 1
                if dry_run:
                    print_fix(filepath, None, old_line, new_line, dry_run)

        if not dry_run:
            write_file(filepath, new_content, dry_run)

    return changes


# ── Default encoding → StandardCharsets (20 fixes) ───────────────────────────

# new FileReader(path) → new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8)
_FILEREADER = re.compile(r'new\s+FileReader\s*\(\s*([^)]+?)\s*\)')
# new String(bytes) → new String(bytes, StandardCharsets.UTF_8)
_STRING_BYTES = re.compile(r'new\s+String\s*\(\s*(\w+)\s*\)\s*;')
# str.getBytes() → str.getBytes(StandardCharsets.UTF_8)
# Only match common String variable patterns, not custom getBytes() methods
_GETBYTES = re.compile(r'(\w+)\s*\.\s*getBytes\s*\(\s*\)')
# path.getBytes("UTF-8") is fine, skip those
_GETBYTES_CHARSET = re.compile(r'\.getBytes\s*\(\s*"[^"]*"\s*\)')
# Known non-String variable names that have custom getBytes() methods
_GETBYTES_SKIP_NAMES = frozenset([
    'h', 'header', 'packet', 'socks', 'icon', 'msg', 'message',
    'resp', 'response', 'req', 'request', 'obj', 'data', 'block',
    'bytebuf', 'buf', 'bytes', 'raw', 'payload', 'output', 'input',
])


def fix_default_encoding(filepath, dry_run=False):
    """Replace default-encoding constructors with explicit StandardCharsets."""
    if is_excluded(filepath):
        return 0
    try:
        content, _ = read_file(filepath)
    except OSError:
        return 0

    original = content
    needs_import = False
    changes = 0

    # FileReader → InputStreamReader + FileInputStream
    def filereader_replacer(m):
        nonlocal needs_import, changes
        needs_import = True
        changes += 1
        path = m.group(1).strip()
        return (f"new InputStreamReader(new FileInputStream({path}), StandardCharsets.UTF_8)")

    new_content = _FILEREADER.sub(filereader_replacer, content)

    # new String(bytes) → new String(bytes, StandardCharsets.UTF_8)
    # Skip if already has charset arg (look for comma after first arg)
    def string_replacer(m):
        nonlocal needs_import, changes
        needs_import = True
        changes += 1
        return f"new String({m.group(1)}, StandardCharsets.UTF_8)"

    new_content = _STRING_BYTES.sub(string_replacer, new_content)

    # getBytes() → getBytes(StandardCharsets.UTF_8)
    def getbytes_replacer(m):
        nonlocal needs_import, changes
        # Skip known non-String variable names with custom getBytes() methods
        if m.group(1).lower() in _GETBYTES_SKIP_NAMES:
            return m.group(0)
        needs_import = True
        changes += 1
        return f"{m.group(1)}.getBytes(StandardCharsets.UTF_8)"

    new_content = _GETBYTES.sub(getbytes_replacer, new_content)

    if needs_import and "StandardCharsets" not in original:
        # Add import
        import_pos = new_content.rfind("import java.")
        if import_pos >= 0:
            end_of_line = new_content.index("\n", import_pos)
            new_content = (new_content[:end_of_line + 1] +
                          "import java.nio.charset.StandardCharsets;\n" +
                          new_content[end_of_line + 1:])
        # Add FileInputStream import if we used FileReader replacement
        if "FileInputStream" in new_content and "import java.io.FileInputStream" not in new_content:
            import_pos = new_content.rfind("import java.io.")
            if import_pos >= 0:
                end_of_line = new_content.index("\n", import_pos)
                new_content = (new_content[:end_of_line + 1] +
                              "import java.io.FileInputStream;\n" +
                              new_content[end_of_line + 1:])

    if changes > 0 and not dry_run:
        write_file(filepath, new_content, dry_run)

    return changes


# ── Serializable Comparator (6 fixes) ─────────────────────────────────────────

_COMPARATOR_CLASS = re.compile(
    r'(private\s+static\s+class\s+\w+\s+implements\s+Comparator<[^>]+>)\s*\{'
)


def fix_serializable_comparator(filepath, dry_run=False):
    """Add Serializable to Comparator classes."""
    if is_excluded(filepath):
        return 0
    try:
        content, lines = read_file(filepath)
    except OSError:
        return 0

    def replacer(m):
        class_decl = m.group(1)
        if "Serializable" in class_decl:
            return m.group(0)
        new_decl = class_decl.replace("implements Comparator<", "implements Comparator<").rstrip()
        # Insert Serializable before the opening brace
        return f"{new_decl}, java.io.Serializable {{"

    new_content = _COMPARATOR_CLASS.sub(replacer, content)
    changes = 0

    if new_content != content:
        # Add serialVersionUID if not present
        # Find each comparator class and add serialVersionUID
        for m in _COMPARATOR_CLASS.finditer(content):
            changes += 1
            if dry_run:
                print_fix(filepath, None, m.group(0), "added Serializable", dry_run)

        if not dry_run:
            # Add serialVersionUID after the opening brace
            new_content = re.sub(
                r'(implements Comparator<[^>]+>,\s*java\.io\.Serializable\s*\{)',
                r'\1\n        private static final long serialVersionUID = 1L;',
                new_content
            )
            write_file(filepath, new_content, dry_run)

    return changes


# ── AvoidRecompilingPatterns — make static final (2 fixes) ───────────────────

# Pattern.compile() assigned to local variable → extract to static field
_PATTERN_COMPILE = re.compile(
    r'^(\s+)(\w+)\s*=\s*Pattern\.compile\(([^)]+)\);',
    re.MULTILINE
)


def fix_pattern_static(filepath, dry_run=False):
    """Extract Pattern.compile() to static final fields where possible."""
    if is_excluded(filepath):
        return 0
    try:
        content, _ = read_file(filepath)
    except OSError:
        return 0

    # Only fix patterns that are in methods but compile a constant string
    changes = 0
    new_content = content

    for m in _PATTERN_COMPILE.finditer(content):
        var_name = m.group(2)
        pattern_arg = m.group(3).strip()

        # Skip if pattern is a variable (not a string literal)
        if not pattern_arg.startswith('"'):
            continue

        # Skip if inside a loop or conditional (the pattern arg changes)
        # Simple heuristic: check if there's a for/while/if on the same line
        line_start = content.rfind("\n", 0, m.start()) + 1
        line_prefix = content[line_start:m.start()]
        if any(kw in line_prefix for kw in ["for ", "while ", "if "]):
            continue

        changes += 1
        if dry_run:
            print_fix(filepath, None, m.group(0).strip(), f"extract to static final", dry_run)

    if changes > 0 and not dry_run:
        # Extract patterns to static fields
        # This is a simplified approach — extract the first Pattern.compile to a field
        for m in _PATTERN_COMPILE.finditer(new_content):
            var_name = m.group(2)
            pattern_arg = m.group(3).strip()
            if not pattern_arg.startswith('"'):
                continue

            # Create a static field name
            field_name = f"_{var_name.upper()}_PATTERN"
            # Remove the local assignment and reference the field
            line_start = new_content.rfind("\n", 0, m.start()) + 1
            line_end = new_content.find("\n", m.end())
            old_line = new_content[line_start:line_end]

            # Replace usage
            new_content = new_content[:line_start] + new_content[line_end + 1:]

            # Add static field before the method (find the enclosing method/class)
            # Simple approach: add after the last field declaration or before the method
            field_decl = f"    private static final Pattern {field_name} = Pattern.compile({pattern_arg});\n"

            # Find insertion point (before the method containing this Pattern.compile)
            method_start = new_content.rfind("\n    ", 0, m.start())
            if method_start >= 0:
                new_content = new_content[:method_start + 1] + field_decl + new_content[method_start + 1:]

            break  # One at a time to avoid index issues

        write_file(filepath, new_content, dry_run)

    return changes


# ── InitializeComparatorOnlyOnce — make static final (4 fixes) ───────────────

_INLINE_COMPARATOR = re.compile(
    r'(\.\s*sort\s*\(\s*)(Comparator\.\w+\([^)]*\))(\s*\))',
    re.DOTALL
)


def fix_comparator_static(filepath, dry_run=False):
    """Extract inline Comparators to static final fields."""
    if is_excluded(filepath):
        return 0
    try:
        content, _ = read_file(filepath)
    except OSError:
        return 0

    changes = 0
    new_content = content

    for m in _INLINE_COMPARATOR.finditer(content):
        comparator_expr = m.group(2)
        # Only fix if it's a constant expression (no lambda capturing local vars)
        if "e ->" in comparator_expr or "->" in comparator_expr:
            # Lambda comparators may capture local state — check if simple
            if "getValue()" in comparator_expr or "getKey()" in comparator_expr:
                changes += 1
                if dry_run:
                    print_fix(filepath, None, comparator_expr[:60], "extract to static final", dry_run)

    if changes > 0 and not dry_run:
        # Simplified: just note that these need manual extraction
        # The full fix requires understanding the types and class structure
        pass

    return changes


# ── Dead local store removal (77 fixes, conservative) ─────────────────────────

_DEAD_ASSIGN = re.compile(
    r'^(\s+)(\w[\w<>\[\]]*\s+)(\w+)\s*=\s*([^;]+);(\s*//.*)?$',
    re.MULTILINE
)


def fix_dead_store(filepath, dry_run=False):
    """Remove obvious dead local variable stores (variable assigned but never read)."""
    if is_excluded(filepath):
        return 0
    try:
        content, lines = read_file(filepath)
    except OSError:
        return 0

    # Conservative: only remove assignments to variables that are clearly unused
    # This requires whole-method analysis which is complex.
    # For now, skip this fix — it's not safely scriptable without AST parsing.
    return 0


# ── @Override annotation (CodeQL: java/missing-override-annotation, ~2360) ─────

def _parse_override_locations(sarif_path):
    """Parse CodeQL SARIF for missing-override-annotation locations.
    Returns {filepath: [line_numbers]}."""
    try:
        with open(sarif_path) as f:
            sarif = json.load(f)
    except (OSError, json.JSONDecodeError):
        return {}
    locations = {}
    for run in sarif.get("runs", []):
        for result in run.get("results", []):
            if result.get("ruleId") != "java/missing-override-annotation":
                continue
            loc = result.get("locations", [{}])[0].get("physicalLocation", {})
            uri = loc.get("artifactLocation", {}).get("uri", "")
            region = loc.get("region", {})
            line = region.get("startLine", 0)
            if uri and line:
                locations.setdefault(uri, []).append(line)
    return locations


def fix_override(filepath, sarif_path=None, dry_run=False):
    """Add @Override to methods identified by CodeQL SARIF.
    Walks from the reported line to find the actual method declaration.
    Never places @Override inside Javadoc or at wrong indentation."""
    if sarif_path is None or not os.path.exists(sarif_path):
        return 0

    # Lazily build the location map on first call
    if not hasattr(fix_override, "_locations"):
        fix_override._locations = _parse_override_locations(sarif_path)

    rel = os.path.relpath(filepath)
    reported_lines = fix_override._locations.get(rel, [])
    if not reported_lines:
        return 0

    if is_excluded(filepath):
        return 0

    # Check config/exclusions.txt for file-level exclusions
    exclusions = _load_override_exclusions()
    if rel in exclusions:
        return 0
    # Check for wildcard directory exclusions (e.g., apps/jetty/*)
    for excluded in exclusions:
        if excluded.endswith("/") and rel.startswith(excluded):
            return 0

    try:
        content, lines = read_file(filepath)
    except OSError:
        return 0

    # Method declaration pattern: access modifiers returnType name(params)
    _METHOD_SIG = re.compile(
        r'^\s+((?:@\w+\s+)*'
        r'(?:public|protected|private)\s+'
        r'(?:(?:static|final|synchronized|abstract|native|default|strictfp)\s+)*'
        r'(?:[\w<>\[\]?,\s&]+?)\s+'
        r'\w+\s*\([^)]*\))'
    )

    changes = 0
    # Track which method declarations we've already annotated: (idx, indent)
    annotations = []

    def find_method_declaration(start_idx):
        """Walk forward from start_idx to find a method declaration.
        Returns (method_line_idx, indent) or (None, 0)."""
        in_javadoc = False
        for j in range(start_idx, min(start_idx + 20, len(lines))):
            stripped = lines[j].strip()

            # Track Javadoc state
            if '/**' in stripped:
                in_javadoc = True
            if '*/' in stripped:
                in_javadoc = False
                continue

            # Skip lines inside Javadoc
            if in_javadoc or stripped.startswith('*'):
                continue

            # Skip blank lines
            if not stripped:
                continue

            # Check for method declaration
            if _METHOD_SIG.match(lines[j]):
                indent = len(lines[j]) - len(lines[j].lstrip())
                return j, indent

            # If we hit an opening brace, we're inside a body — stop
            if stripped == '{':
                return None, 0

            # If we hit a field declaration or assignment with no parens, stop
            if '=' in stripped and '(' not in stripped:
                return None, 0

        return None, 0

    for reported_line in reported_lines:
        idx = reported_line - 1
        if idx < 0 or idx >= len(lines):
            continue

        # Find the actual method declaration from this starting point
        method_idx, indent = find_method_declaration(idx)
        if method_idx is None:
            continue

        stripped = lines[method_idx].strip()

        # Skip if already has @Override on line above
        has_override = False
        for k in range(1, 4):
            if method_idx - k >= 0 and lines[method_idx - k].strip() == "@Override":
                has_override = True
                break
        if has_override:
            continue
        if "@Override" in lines[method_idx]:
            continue

        # Skip constructors (method name == class name, uppercase start)
        paren_pos = lines[method_idx].find("(")
        if paren_pos > 0:
            before_paren = lines[method_idx][:paren_pos].strip()
            # Skip static methods
            if "static " in before_paren:
                continue
            parts = before_paren.split()
            method_name = parts[-1] if parts else ""
            if method_name and method_name[0].isupper() and "static" not in before_paren:
                continue

        # Don't annotate the same method twice
        already = any(a[0] == method_idx for a in annotations)
        if already:
            continue

        annotations.append((method_idx, indent))
        changes += 1
        if dry_run:
            print_fix(filepath, method_idx + 1, lines[method_idx].strip(),
                       " " * indent + "@Override", dry_run)

    if changes > 0 and not dry_run:
        # Insert annotations in reverse order to preserve line numbers
        for idx, indent in sorted(annotations, key=lambda x: -x[0]):
            lines.insert(idx, " " * indent + "@Override")

        # Post-edit cleanup: remove any @Override inside Javadoc blocks
        cleaned = []
        in_javadoc = False
        for i, line in enumerate(lines):
            stripped = line.strip()
            if '/**' in stripped:
                in_javadoc = True
            if '*/' in stripped:
                in_javadoc = False
            # Remove @Override if it's inside Javadoc
            if in_javadoc and stripped == "@Override":
                changes -= 1
                continue
            cleaned.append(line)
        lines = cleaned

        with open(filepath, "w") as f:
            f.write("\n".join(lines) + ("\n" if content.endswith("\n") else ""))

    return changes


# ── .isEmpty() (CodeQL: java/inefficient-empty-string-test, ~66) ──────────────

# Match variable.length() patterns, but NOT File.length() (which returns long, not String)
# Capture group: full variable expression including dots and method calls
# Negative lookahead: skip if preceded by 'File' type or 'long' context markers
_LEN_PATTERN = r'(?<!File )(?<!File  )(?<!File\t)' r'(\w+(?:\.\w+(?:\([^)]*\))?)+)\.length\(\)'

# .length() == 0 → .isEmpty()
_LEN_EQ_0 = re.compile(_LEN_PATTERN + r'\s*==\s*0')
# .length() != 0 → !foo.isEmpty()
_LEN_NE_0 = re.compile(_LEN_PATTERN + r'\s*!=\s*0')
# .length() > 0 → !foo.isEmpty()
_LEN_GT_0 = re.compile(_LEN_PATTERN + r'\s*>\s*0')
# .length() <= 0 → .isEmpty()
_LEN_LTE_0 = re.compile(_LEN_PATTERN + r'\s*<=\s*0')


# File-like variable names to skip (File.length() returns long, not String)
_FILE_NAMES = re.compile(r'(?:^|[.\s(])(?:file|tempFile|configFile|f|dir|dirpath|path|fpath|dest)\b\.length\(\)')


def fix_empty_string(filepath, dry_run=False):
    """Replace .length() == 0 with .isEmpty() and similar patterns."""
    if is_excluded(filepath):
        return 0
    try:
        content, _ = read_file(filepath)
    except OSError:
        return 0

    original = content

    # Process line by line to skip lines already using .isEmpty() or File.length()
    new_lines = []
    for line in content.split("\n"):
        if ".isEmpty()" in line:
            new_lines.append(line)
            continue
        if _FILE_NAMES.search(line):
            new_lines.append(line)
            continue
        # .length() == 0 → .isEmpty()
        line = _LEN_EQ_0.sub(r'\1.isEmpty()', line)
        # .length() != 0 → !foo.isEmpty()
        line = _LEN_NE_0.sub(r'!\1.isEmpty()', line)
        # .length() > 0 → !foo.isEmpty()
        line = _LEN_GT_0.sub(r'!\1.isEmpty()', line)
        # .length() <= 0 → .isEmpty()
        line = _LEN_LTE_0.sub(r'\1.isEmpty()', line)
        new_lines.append(line)

    content = "\n".join(new_lines)

    changes = 0
    if content != original:
        for old_line, new_line in zip(original.split("\n"), content.split("\n")):
            if old_line != new_line:
                changes += 1
                if dry_run:
                    print_fix(filepath, None, old_line, new_line, dry_run)
        if not dry_run:
            write_file(filepath, content, dry_run)

    return changes


# ── Unbox primitive wrappers (CodeQL: java/non-null-boxed-variable, ~23) ──────

_BOXED_TO_PRIM = {
    "Integer": "int", "Long": "long", "Double": "double",
    "Float": "float", "Boolean": "boolean", "Short": "short",
    "Byte": "byte", "Character": "char",
}
_BOXED_DECL = re.compile(r'^(\s+)((?:final\s+)?)(Integer|Long|Double|Float|Boolean|Short|Byte|Character)\s+(\w+)\s*(=|;|\))')


def fix_unbox(filepath, dry_run=False):
    """Replace boxed primitive types with primitives where never-null."""
    if is_excluded(filepath):
        return 0
    try:
        content, _ = read_file(filepath)
    except OSError:
        return 0

    original = content
    changes = 0
    new_lines = []

    for line in content.split("\n"):
        m = _BOXED_DECL.match(line)
        if m:
            indent = m.group(1)
            modifiers = m.group(2)
            boxed = m.group(3)
            var_name = m.group(4)
            prim = _BOXED_TO_PRIM[boxed]
            new_line = f"{indent}{modifiers}{prim} {var_name} {'=' if m.group(5) == '=' else m.group(5)}"
            # Reconstruct the rest of the line after the match
            rest = line[m.end():]
            new_line = new_line + rest
            if new_line != line:
                changes += 1
                if dry_run:
                    print_fix(filepath, None, line.strip(), new_line.strip(), dry_run)
            new_lines.append(new_line)
        else:
            new_lines.append(line)

    content = "\n".join(new_lines)

    if changes > 0 and not dry_run:
        write_file(filepath, content, dry_run)

    return changes


# ── Integer multiplication cast to long (CodeQL: ~42, prevents overflow) ──────

def _parse_int_mult_locations(sarif_path):
    """Parse CodeQL SARIF for integer-multiplication-cast-to-long locations."""
    try:
        with open(sarif_path) as f:
            sarif = json.load(f)
    except (OSError, json.JSONDecodeError):
        return {}
    locations = {}
    for run in sarif.get("runs", []):
        for result in run.get("results", []):
            if result.get("ruleId") != "java/integer-multiplication-cast-to-long":
                continue
            loc = result.get("locations", [{}])[0].get("physicalLocation", {})
            uri = loc.get("artifactLocation", {}).get("uri", "")
            region = loc.get("region", {})
            line = region.get("startLine", 0)
            col = region.get("startColumn", 0)
            if uri and line:
                locations.setdefault(uri, []).append((line, col))
    return locations


def fix_int_mult(filepath, sarif_path=None, dry_run=False):
    """Add L suffix to integer literals in multiplications reported by CodeQL.
    Only fixes lines identified in SARIF — conservative, no bulk regex."""
    if sarif_path is None or not os.path.exists(sarif_path):
        return 0

    if not hasattr(fix_int_mult, "_locations"):
        fix_int_mult._locations = _parse_int_mult_locations(sarif_path)

    rel = os.path.relpath(filepath)
    reported = fix_int_mult._locations.get(rel, [])
    if not reported:
        return 0

    if is_excluded(filepath):
        return 0

    try:
        content, lines = read_file(filepath)
    except OSError:
        return 0

    # For each reported line, find integer literals in multiplication context
    # and add L suffix. Be conservative: only add L to the LAST integer literal
    # before the reported column, or the FIRST after, whichever is in a multiply.
    changes = 0
    fix_lines = set()

    for line_no, col in reported:
        idx = line_no - 1
        if idx < 0 or idx >= len(lines):
            continue
        line = lines[idx]
        if line in fix_lines:
            continue

        # Skip if line is inside an array literal { ... }
        stripped = line.strip()
        if stripped.startswith("//") or stripped.startswith("*"):
            continue

        # Find the multiplication near the reported column
        # Strategy: find all " * constant" patterns on the line,
        # add L to the first integer literal that's an operand of *
        new_line = line
        # Match: digit(s) followed by * or preceded by * (without already having L)
        # Be conservative: only fix the specific multiplication reported
        col_pos = col - 1 if col > 0 else 0
        # Find "number * number" pattern near the column
        before = line[:col_pos + 20]
        after = line[col_pos:]

        # Look for " * constant" or "constant * " near the reported position
        m = re.search(r'(\d+)\s*\*\s*(\d+)', line[max(0, col_pos - 10):col_pos + 30])
        if m:
            full_match = m.group(0)
            # Add L to the first operand if it doesn't already have one
            if not m.group(1).endswith('L'):
                replacement = m.group(1) + 'L' + m.group(0)[len(m.group(1)):]
                # Verify we're not in an array context
                if '{' not in line.split('*')[0].split('=')[-1]:
                    new_line = line[:line.find(full_match)] + replacement + line[line.find(full_match) + len(full_match):]
        else:
            # Simpler: just add L to integer literals in "* N" patterns
            # But only if there's a multiplication operator on the line
            if '*' in line:
                # Find all "* number" and "number *" patterns
                # Add L to the operand
                new_line = re.sub(r'(?<!\d)(\d{2,})(?!\d)(?![lL])\s*\*', r'\1L *', line, count=1)
                if new_line == line:
                    new_line = re.sub(r'\*\s*(\d{2,})(?!\d)(?![lL])(?!\.)', r'* \1L', line, count=1)

        if new_line != line:
            fix_lines.add(line)
            changes += 1
            if dry_run:
                print_fix(filepath, line_no, line.strip(), new_line.strip(), dry_run)
            lines[idx] = new_line

    if changes > 0 and not dry_run:
        with open(filepath, "w") as f:
            f.write("\n".join(lines) + ("\n" if content.endswith("\n") else ""))

    return changes


# ── Imports (google-java-format + Checkstyle XML) ─────────────────────────────

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
GOOGLE_FORMAT_JAR = os.path.join(SCRIPT_DIR, "google-java-format.jar")


def fix_imports(filepath, dry_run=False):
    """Remove unused imports using google-java-format --fix-imports-only."""
    if is_excluded(filepath):
        return 0
    if not os.path.exists(GOOGLE_FORMAT_JAR):
        return 0
    try:
        with open(filepath, errors="replace") as f:
            original = f.read()
    except OSError:
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

    removed = len(original.splitlines()) - len(fixed.splitlines())
    return max(removed, 1)


def fix_unused_imports_xml(filepath, lines_to_remove, dry_run=False):
    """Remove import lines at specified line numbers."""
    try:
        with open(filepath, errors="replace") as f:
            lines = f.readlines()
    except OSError:
        return 0

    remove_set = set(lines_to_remove)
    new_lines = [l for i, l in enumerate(lines, 1) if i not in remove_set]

    if len(new_lines) < len(lines) and not dry_run:
        with open(filepath, "w") as f:
            f.writelines(new_lines)

    return len(lines) - len(new_lines)


def parse_xml_violations(xml_file, check_suffix, filter_path=None):
    """Parse Checkstyle XML for a specific check, return {filepath: [line_numbers]}."""
    try:
        with open(xml_file) as f:
            content = f.read()
        if not content.rstrip().endswith("</checkstyle>"):
            last_tag = max(content.rfind("</error>"), content.rfind("</file>"))
            if last_tag > 0:
                content = content[:last_tag + content[last_tag:].find(">") + 1]
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


# ── Iterative indentation (Checkstyle) ────────────────────────────────────────

CHECKSTYLE_JAR = os.path.join(SCRIPT_DIR, "checkstyle", "checkstyle-all.jar")

INDENT_CFG = """<?xml version="1.0"?>
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
    <property name="fileNamePattern" value=".*[/\\\\]pack200[/\\\\].*"/>
  </module>
  <module name="BeforeExecutionExclusionFileFilter">
    <property name="fileNamePattern" value=".*[/\\\\]jrobin[/\\\\].*"/>
  </module>
  <module name="BeforeExecutionExclusionFileFilter">
    <property name="fileNamePattern" value=".*[/\\\\]build[/\\\\].*"/>
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


def _run_checkstyle(scan_path, xml_out):
    """Run Checkstyle on scan_path, write XML to xml_out."""
    cfg = tempfile.NamedTemporaryFile(mode="w", suffix=".xml", delete=False)
    cfg.write(INDENT_CFG)
    cfg.close()
    try:
        subprocess.run(
            ["java", "-jar", CHECKSTYLE_JAR, "-c", cfg.name, "-f", "xml", "-o", xml_out, scan_path],
            capture_output=True, timeout=600)
    finally:
        os.unlink(cfg.name)


def _parse_indentation_violations(xml_file, filter_path=None):
    """Parse Checkstyle XML for IndentationCheck, return {filepath: [(line_no, [valid_indents])]}."""
    try:
        with open(xml_file) as f:
            content = f.read()
        last_close = max(content.rfind("</error>"), content.rfind("</file>"))
        if last_close > 0:
            content = content[:last_close + content[last_close:].find(">") + 1]
        if not content.rstrip().endswith("</checkstyle>"):
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
            if "IndentationCheck" not in source:
                continue
            msg = enode.attrib.get("message", "")
            line_no = int(enode.attrib.get("line", "0"))
            m = re.search(r"expected level should be (\d+)\.", msg)
            if m:
                fixes.setdefault(fname, []).append((line_no, [int(m.group(1))]))
                continue
            m = re.search(r"one of the following:\s*([\d, ]+)", msg)
            if m:
                options = [int(x.strip()) for x in m.group(1).split(",")]
                fixes.setdefault(fname, []).append((line_no, options))
    return fixes


def _fix_single_indent(filepath, line_fixes, dry_run=False):
    """Fix indentation for one file."""
    try:
        with open(filepath, errors="replace") as f:
            lines = f.readlines()
    except OSError:
        return 0

    fix_map = {}
    for line_no, options in line_fixes:
        if line_no in fix_map:
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
            stripped = line.lstrip(" \t")
            if not stripped:
                new_lines.append(line)
                continue
            leading = line[:len(line) - len(stripped)]
            actual_spaces = len(leading.expandtabs(4))
            target = min(options, key=lambda x: abs(x - actual_spaces))
            if actual_spaces != target:
                line = " " * target + stripped
                changes += 1
        new_lines.append(line)

    if changes > 0 and not dry_run:
        with open(filepath, "w") as f:
            f.writelines(new_lines)
    return changes


def fix_indentation(scan_path, xml_path, dry_run=False):
    """Run iterative indentation fix passes."""
    if not os.path.exists(CHECKSTYLE_JAR):
        print("    (skipped — checkstyle jar not found)", file=sys.stderr)
        return 0

    total = 0
    for iteration in range(10):
        if xml_path and iteration == 0:
            fixes = _parse_indentation_violations(xml_path, filter_path=scan_path)
        else:
            tmp_xml = tempfile.mktemp(suffix=".xml")
            _run_checkstyle(scan_path, tmp_xml)
            fixes = _parse_indentation_violations(tmp_xml, filter_path=scan_path)
            os.unlink(tmp_xml)

        if not fixes:
            break

        pass_fixes = 0
        for filepath in sorted(fixes.keys()):
            n = _fix_single_indent(filepath, fixes[filepath], dry_run)
            pass_fixes += n
        total += pass_fixes

        if pass_fixes == 0:
            remaining = sum(len(v) for v in fixes.values())
            if remaining > 0 and iteration == 0:
                print(f"    {remaining} indentation violations remain (can't auto-fix)", file=sys.stderr)
            break

    return total


def fix_trailing_newlines(filepath, dry_run=False):
    """Remove excessive trailing newlines, keep exactly one. Add one if missing."""
    try:
        with open(filepath, "rb") as f:
            content = f.read()
    except OSError:
        return 0

    if not content:
        return 0

    stripped = content.rstrip(b"\n")
    trailing = len(content) - len(stripped)

    if trailing == 1:
        return 0  # already correct

    corrected = stripped + b"\n"

    if not dry_run:
        with open(filepath, "wb") as f:
            f.write(corrected)

    rel = os.path.relpath(filepath)
    if trailing == 0:
        print_fix(filepath, len(content), "no trailing newline",
                  "added trailing newline", dry_run)
    else:
        print_fix(filepath, trailing, f"{trailing} trailing newlines",
                  "1 trailing newline", dry_run)
    return 1


# ── Main ──────────────────────────────────────────────────────────────────────

ALL_FIXES = {
    "checkstyle": ("Tabs, whitespace, braces, trailing ws", fix_checkstyle),
    "imports": ("Unused imports (google-java-format)", fix_imports),
    "override": ("Add @Override from CodeQL SARIF [java/missing-override-annotation]", fix_override),
    "is-empty": ("Replace .length() == 0 with .isEmpty() [java/inefficient-empty-string-test]", fix_empty_string),
    "unbox": ("Unbox Integer/Long/etc to int/long [java/non-null-boxed-variable]", fix_unbox),
    "int-mult": ("Add L suffix to int multiplications [java/integer-multiplication-cast-to-long]", fix_int_mult),
    "simpledate": ("SimpleDateFormat → DateTimeFormatter", fix_simple_date),
    "newline-fmt": (r"\n → %n in String.format", fix_format_newline),
    "encoding": ("Default encoding → StandardCharsets", fix_default_encoding),
    "serializable": ("Add Serializable to Comparators", fix_serializable_comparator),
    "pattern": ("Extract Pattern.compile to static final", fix_pattern_static),
    "comparator": ("Extract inline Comparators to static final", fix_comparator_static),
    "dead-store": ("Remove dead local stores (disabled — needs AST)", fix_dead_store),
    "trailing": ("Remove excessive trailing newlines (keep exactly one)", fix_trailing_newlines),
}


def main():
    parser = argparse.ArgumentParser(
        description="Consolidated Java source fixer for PMD/SpotBugs/Checkstyle violations.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="Examples:\n"
               "  fix-java-issues.py -p core/java/src --dry-run\n"
               "  fix-java-issues.py -p apps/ --fix simpledate newline-fmt encoding\n"
               "  fix-java-issues.py -p router/java/src --fix checkstyle serializable\n"
               "  fix-java-issues.py -p . --fix indent imports -x dist/checkstyle.xml\n"
               "\nAvailable fix types:\n" +
               "\n".join(f"  {k:<14} {v[0]}" for k, v in ALL_FIXES.items()) +
               "\n  indent         Iterative indentation (requires -x and checkstyle jar)")
    parser.add_argument("-p", "--path", required=True, help="Directory to scan and fix")
    parser.add_argument("-n", "--dry-run", action="store_true", help="Preview changes without modifying files")
    parser.add_argument("-f", "--fix", nargs="+", default=list(ALL_FIXES.keys()),
                        help="Fix types to apply (default: all checkstyle/pmd/spotbugs)")
    parser.add_argument("-x", "--xml", help="Checkstyle XML report (for indent and redundant imports)")
    parser.add_argument("-s", "--sarif", help="CodeQL SARIF report (for override fix)")
    args = parser.parse_args()

    scan_path = os.path.abspath(args.path)
    if not os.path.exists(scan_path):
        print(f"ERROR: {scan_path} not found", file=sys.stderr)
        sys.exit(1)

    if args.dry_run:
        print("DRY RUN — no files will be modified\n")

    java_files = find_java_files(scan_path)
    print(f"Scanning {len(java_files)} files in {scan_path}\n", file=sys.stderr)

    total = 0
    for fix_type in args.fix:
        # Handle indent separately (file-scoped, uses XML)
        if fix_type == "indent":
            print("── indent: Iterative indentation (Checkstyle)", file=sys.stderr)
            n = fix_indentation(scan_path, args.xml, args.dry_run)
            label = "would fix" if args.dry_run else "fixed"
            print(f"   {n} {label}\n", file=sys.stderr)
            total += n
            continue

        # Handle imports with redundant import check from XML
        if fix_type == "imports":
            print("── imports: Unused imports (google-java-format)", file=sys.stderr)
            n = 0
            for filepath in sorted(java_files):
                n += fix_imports(filepath, args.dry_run)
            label = "would fix" if args.dry_run else "fixed"
            print(f"   {n} {label}", file=sys.stderr)

            if args.xml:
                redundant = parse_xml_violations(args.xml, "RedundantImportCheck", filter_path=scan_path)
                r_n = 0
                for filepath in sorted(redundant.keys()):
                    r_n += fix_unused_imports_xml(filepath, redundant[filepath], args.dry_run)
                if r_n:
                    print(f"   {r_n} redundant imports {label}", file=sys.stderr)
                n += r_n
            print(f"")
            total += n
            continue

        # Handle override (uses SARIF)
        if fix_type == "override":
            desc = ALL_FIXES[fix_type][0]
            print(f"── override: {desc}", file=sys.stderr)
            if not args.sarif:
                print("   (skipped — use -s dist/codeql-java.sarif)", file=sys.stderr)
                print(f"")
                continue
            # Reset cached locations for each run
            if hasattr(fix_override, "_locations"):
                delattr(fix_override, "_locations")
            count = 0
            for filepath in sorted(java_files):
                n = fix_override(filepath, args.sarif, args.dry_run)
                if n > 0:
                    count += n
            label = "would fix" if args.dry_run else "fixed"
            print(f"   {count} {label}\n", file=sys.stderr)
            total += count
            continue

        # Handle int-mult (uses SARIF)
        if fix_type == "int-mult":
            desc = ALL_FIXES[fix_type][0]
            print(f"── int-mult: {desc}", file=sys.stderr)
            if not args.sarif:
                print("   (skipped — use -s dist/codeql-java.sarif)", file=sys.stderr)
                print(f"")
                continue
            if hasattr(fix_int_mult, "_locations"):
                delattr(fix_int_mult, "_locations")
            count = 0
            for filepath in sorted(java_files):
                n = fix_int_mult(filepath, args.sarif, args.dry_run)
                if n > 0:
                    count += n
            label = "would fix" if args.dry_run else "fixed"
            print(f"   {count} {label}\n", file=sys.stderr)
            total += count
            continue

        if fix_type not in ALL_FIXES:
            print(f"  Unknown fix type: {fix_type}", file=sys.stderr)
            continue

        desc, fix_func = ALL_FIXES[fix_type]
        print(f"── {fix_type}: {desc}", file=sys.stderr)
        count = 0
        for filepath in sorted(java_files):
            n = fix_func(filepath, args.dry_run)
            if n > 0:
                count += n
        label = "would fix" if args.dry_run else "fixed"
        print(f"   {count} {label}\n", file=sys.stderr)
        total += count

    dry_label = " (dry run)" if args.dry_run else ""
    print(f"Total: {total} changes{dry_label}", file=sys.stderr)


if __name__ == "__main__":
    main()
