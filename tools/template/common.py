#!/usr/bin/env python3
"""
Shared report resources for I2P+ analysis tools (CodeQL, PMD, Checkstyle).
Import this module to get common CSS, favicon, HTML helpers, and exclusion patterns.
"""
import html
import os
import re
from datetime import datetime, timezone
from urllib.parse import quote

TEMPLATE_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.join(TEMPLATE_DIR, "..", "..")

# ── Config ────────────────────────────────────────────────────────────────────

_DEFAULTS = {
    "favicon": "installer/resources/console/themes/console/images/plus.svg",
    "report_prefix": "I2P+",
}

def _load_config():
    cfg = dict(_DEFAULTS)
    cfg_file = os.path.join(TEMPLATE_DIR, "config.txt")
    try:
        with open(cfg_file) as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                if "=" in line:
                    key, _, val = line.partition("=")
                    cfg[key.strip()] = val.strip()
    except FileNotFoundError:
        pass
    return cfg

CONFIG = _load_config()

# ── Exclusion patterns (third-party/generated code) ──────────────────────────

# Rules to exclude from all reports (low value / high noise)
EXCLUDED_RULES = [
    # Done: Added manually, not a real issue
    "java/missing-override-annotation",
    # Complex analysis required, low value
    "java/useless-null-check",
    # Unused variables - common in I2P codebase patterns
    "java/unused-parameter",
    "java/local-variable-is-never-read",
    # Deprecated/old API usage - often intentional
    "java/deprecated-call",
    "java/ignored-error-status-of-call",
    "java/constant-comparison",
    # Documentation noise
    "java/unknown-javadoc-parameter",
    # Style issues, not bugs
    "java/call-to-object-tostring",
    "java/non-sync-override",
    "java/misleading-indentation",
    "java/missing-case-in-switch",
    "java/sleep-with-lock-held",
]

EXCLUDE_PATTERNS = [
    "org/cybergarage/",
    "org/apache/",
    "com/mpatric/",
    "org/rrd4j/",
    "org/bouncycastle/",
    "io/pack200/",
    "com/maxmind/",
    "org/minidns/",
    "com/southernstorm/noise/",
    "org/json/simple/",
    "net/metanotion/",
    "apps/imagegen/identicon/",
    "apps/imagegen/zxing/",
    "apps/i2psnark/java/src/com/mpatric/",
    "apps/pack200/java/src/io/pack200/",
    "apps/jrobin/java/src/org/rrd4j/",
    "org/freenetproject/",
    "freenet/",
    "com/southernstorm/",
    "com/tomgibara/",
    "com/google/zxing/",
    "com/docuverse/",
    "com/thetransactioncompany/",
    "edu/internet2/",
    "com/vuze/",
    "/test/",
    "/demo/",
    "/tmp/",
    "_jsp.java",
]


def is_excluded(uri, rule_id=None):
    """Check if a file URI matches any exclusion pattern, or rule is excluded."""
    if rule_id and rule_id in EXCLUDED_RULES:
        return True
    return any(p in uri for p in EXCLUDE_PATTERNS)


# ── Severity mapping ─────────────────────────────────────────────────────────

SEV_ORDER = {"error": 0, "warning": 1, "note": 2}

# Maps external tool severities to our p1-p5 CSS classes
SEV_TO_CLASS = {
    # CodeQL
    "error": "p1",
    "warning": "p2",
    "note": "p3",
    # PMD
    "1": "p1",
    "2": "p2",
    "3": "p3",
    "4": "p4",
    "5": "p5",
    # Checkstyle
    "info": "p4",
    "ignore": "p5",
}


def severity_class(sev):
    """Map a severity string to a CSS class (p1-p5)."""
    s = str(sev).lower()
    return SEV_TO_CLASS.get(s, "p3")


def severity_span(sev, label=None):
    """Generate a severity badge span."""
    cls = severity_class(sev)
    text = label or str(sev)
    return f'<span class="badge {cls}">{escape(text)}</span>'


# ── Shared resources ─────────────────────────────────────────────────────────

def _strip_comments(text, comment_start="/*", comment_end="*/"):
    """Strip block comments from CSS/JS while preserving URLs containing comment-like chars."""
    result = []
    i = 0
    while i < len(text):
        if text[i:i+len(comment_start)] == comment_start:
            end = text.find(comment_end, i + len(comment_start))
            if end != -1:
                i = end + len(comment_end)
                continue
        result.append(text[i])
        i += 1
    return "".join(result)


def load_css():
    """Load the shared report CSS, stripping comments and minifying if possible."""
    try:
        with open(os.path.join(TEMPLATE_DIR, "report.css")) as f:
            raw = _strip_comments(f.read())
    except Exception:
        return ""
    # Try minification via cleancss
    try:
        import subprocess
        r = subprocess.run(["cleancss"], input=raw.encode(), capture_output=True, timeout=5)
        if r.returncode == 0:
            return r.stdout.decode()
    except (FileNotFoundError, subprocess.TimeoutExpired):
        pass
    return raw


def load_favicon():
    """Load the SVG favicon as a data URI, from config or default."""
    try:
        path = os.path.join(PROJECT_ROOT, CONFIG["favicon"])
        with open(path) as f:
            return f"data:image/svg+xml,{quote(f.read())}"
    except Exception:
        return ""


def load_js(*filenames):
    """Load one or more JS files from tools/template/js/, concatenate, strip comments, minify."""
    parts = []
    for name in filenames:
        path = os.path.join(TEMPLATE_DIR, "js", name)
        try:
            with open(path) as f:
                parts.append(_strip_comments(f.read()))
        except FileNotFoundError:
            pass
    raw = "\n".join(parts)
    # Try minification via terser or uglifyjs
    try:
        import subprocess
        r = subprocess.run(["terser"], input=raw.encode(), capture_output=True, timeout=5)
        if r.returncode == 0:
            return r.stdout.decode()
    except (FileNotFoundError, subprocess.TimeoutExpired):
        pass
    try:
        import subprocess
        r = subprocess.run(["uglifyjs"], input=raw.encode(), capture_output=True, timeout=5)
        if r.returncode == 0:
            return r.stdout.decode()
    except (FileNotFoundError, subprocess.TimeoutExpired):
        pass
    return raw


# ── HTML helpers ──────────────────────────────────────────────────────────────

def escape(s):
    """HTML-escape a string."""
    return html.escape(str(s)) if s else ""


def minify_html(html_str):
    """Minify HTML by collapsing whitespace."""
    html_str = re.sub(r"<!--.*?-->", "", html_str, flags=re.DOTALL)
    html_str = re.sub(r"\s+", " ", html_str)
    html_str = re.sub(r">\s+<", "><", html_str)
    return html_str.strip()


def get_code_snippet(file_path, line_num, context=3):
    """Read source code around a violation line."""
    try:
        if not os.path.exists(file_path):
            return ""
        with open(file_path) as f:
            lines = f.readlines()
        ln = int(line_num)
        start = max(0, ln - context - 1)
        end = min(len(lines), ln + context)
        snippet_lines = []
        for i in range(start, end):
            n = i + 1
            code = lines[i].rstrip()
            if n == ln:
                snippet_lines.append(
                    f'<span class="offense"><span class="ln">{n}</span> '
                    f'<span class="codeline">{escape(code)}</span></span>'
                )
            else:
                snippet_lines.append(
                    f'<span class="code-line"><span class="ln">{n}</span> '
                    f"{escape(code)}</span>"
                )
        return "\n".join(snippet_lines)
    except Exception:
        return ""


def snippet_to_text(snippet):
    """Strip HTML from a snippet for safe JSON embedding. Regenerates HTML in JS."""
    import re
    text = re.sub(r'<[^>]+>', '', snippet)
    text = html.unescape(text)
    return text.strip()


# ── Page generation ──────────────────────────────────────────────────────────

def html_header(title, tool_name, body_id=None, js_files=None):
    """Generate the common HTML header (doctype through <body>).

    js_files: list of JS filenames from tools/template/js/ to include.
              Defaults to ["shared.js"] if not specified.
    """
    lines = []
    w = lines.append
    favicon = load_favicon()
    bid = body_id or tool_name.lower().replace("+", "").replace(" ", "-")
    prefix = CONFIG["report_prefix"]

    if js_files is None:
        js_files = ["shared.js"]

    w("<!DOCTYPE html>")
    w('<html lang="en"><head>')
    w('<meta charset="UTF-8">')
    w('<meta name="viewport" content="width=device-width, initial-scale=1.0">')
    if favicon:
        w(f'<link rel="icon" type="image/svg+xml" href="{favicon}">')
    w(f"<title>{escape(prefix)} | {escape(title)}</title>")
    w("<style>")
    w(load_css())
    w("</style>")
    w("<script>")
    w(load_js(*js_files))
    w("</script>")
    w(f'</head><body id="{bid}">')
    w(f"<h1>{escape(prefix)} {escape(title)}</h1>")
    return "\n".join(lines)


def html_meta_line(total, file_count, tool_info=""):
    """Generate the summary meta line."""
    now = datetime.now(timezone.utc).strftime("%B %d, %Y, %H:%M UTC")
    parts = []
    if tool_info:
        parts.append(escape(tool_info))
    parts.append(f"{total} issues across {file_count} files")
    parts.append(escape(now))
    return f'<p class="meta">{" &middot; ".join(parts)}</p>'


def html_navbar(severity_counts, nav_id="navbar"):
    """Generate the sticky navbar with severity links."""
    lines = []
    w = lines.append
    has_items = any(v > 0 for v in severity_counts.values())
    if not has_items:
        return ""
    w(f'<div id="{nav_id}">')
    for sev, count in severity_counts.items():
        if count > 0:
            w(
                f'<span><a href="#severity-{sev}" data-severity="{sev}">'
                f'{sev.capitalize()} <span class="badge">{count}</span></a></span>'
            )
    w("</div>")
    return "\n".join(lines)


def html_summary_table_header(columns):
    """Generate a summary table header row. columns is a list of (label, css_class)."""
    cells = "".join(
        f'<th{" class=" + cls + '"' if cls else ""}>{label}</th>'
        for label, cls in columns
    )
    return f"<tr>{cells}</tr>"


def html_summary_row(values, row_class):
    """Generate a summary table row. values is a list of (content, css_class)."""
    cells = "".join(
        f'<td{" class=" + cls + '"' if cls else ""}>{content}</td>'
        for content, cls in values
    )
    return f'<tr class="{row_class}">{cells}</tr>'


def html_severity_span(label):
    """Generate a colored severity indicator span for the navbar or table."""
    sev = label.lower()
    return f'<span class="severity-{sev}"></span>'


def html_footer():
    """Generate the common HTML footer."""
    return "</body></html>"


def write_report(html_file, html_content):
    """Write a minified HTML report to disk."""
    output = minify_html(html_content)
    with open(html_file, "w") as f:
        f.write(output)


def _shared_js():
    """Shared JavaScript for expand/collapse and navigation."""
    return """document.addEventListener("click",function(e){
  if(e.target.closest(".line-link"))return;
  var s=e.target.closest("summary");
  if(s){var d=s.parentElement;
    if(d&&d.tagName==="DETAILS"&&!d.hasAttribute("open")){
      document.querySelectorAll("details[open]").forEach(function(o){if(o!==d)o.removeAttribute("open")});
    }return}
  var tr=e.target.closest("tr[data-detail]");
  if(tr){var el=document.getElementById(tr.dataset.detail);
    if(el){var showing=el.style.display==="none";
      el.style.display=showing?"block":"none";
      el.closest("tr").classList.toggle("hidden",!showing)}return}
  var a=e.target.closest("a[data-severity]");
  if(a){e.preventDefault();var tgt=document.getElementById("severity-"+a.dataset.severity);
    if(tgt){document.querySelectorAll("details[open]").forEach(function(o){o.removeAttribute("open")});
      tgt.setAttribute("open","");setTimeout(function(){tgt.scrollIntoView({behavior:"smooth",block:"start"})},50)}
  }
});"""


# ── Cached source file index ─────────────────────────────────────────────────

INDEX_FILE = os.path.join(TEMPLATE_DIR, ".source-index.json")


def build_source_index():
    """Build a cached index mapping SpotBugs package paths to actual file paths.

    Scans core/, router/, and apps/ for .java files, skipping build/temp dirs.
    Returns dict: "net/i2p/data/DataHelper.java" -> "/abs/path/to/file.java"
    """
    import json

    # Rebuild if cache is older than any source dir
    if os.path.exists(INDEX_FILE):
        cache_mtime = os.path.getmtime(INDEX_FILE)
        stale = False
        for check in ["core/java/src", "router/java/src", "apps"]:
            if os.path.isdir(check) and os.path.getmtime(check) > cache_mtime:
                stale = True
                break
        if not stale:
            try:
                with open(INDEX_FILE) as f:
                    data = json.load(f)
                if data:
                    return data
            except (json.JSONDecodeError, OSError):
                pass

    # Build index
    idx = {}
    search_roots = []
    skip_dirs = {"/tmp/", "/build/", "/obj/", "/jsp/", "/.git/"}
    pkg_keywords = ("net/", "i2p/", "org/", "com/", "edu/", "freenet/")

    # core, router, and installer are direct roots
    for r in ["core/java/src", "router/java/src", "installer/java/src", "installer/tools/java/src"]:
        if os.path.isdir(r):
            search_roots.append(r)

    # apps: find package roots
    if os.path.isdir("apps"):
        for app in os.listdir("apps"):
            app_dir = os.path.join("apps", app)
            if not os.path.isdir(app_dir):
                continue
            seen = set()
            for dirpath, dirnames, filenames in os.walk(app_dir):
                if any(s in dirpath for s in skip_dirs):
                    dirnames.clear()
                    continue
                if any(f.endswith(".java") for f in filenames):
                    rel = os.path.relpath(dirpath, app_dir)
                    for kw in pkg_keywords:
                        idx_pos = rel.find(kw)
                        if idx_pos > 0:
                            root = os.path.join(app_dir, rel[:idx_pos].rstrip("/"))
                            if root not in seen:
                                seen.add(root)
                                search_roots.append(root)
                            break
                if dirpath.count(os.sep) - app_dir.count(os.sep) > 5:
                    dirnames.clear()

    for root_dir in search_roots:
        if not os.path.isdir(root_dir):
            continue
        for dirpath, _, filenames in os.walk(root_dir):
            for f in filenames:
                if f.endswith(".java"):
                    full = os.path.abspath(os.path.join(dirpath, f))
                    pkg_path = os.path.relpath(full, os.path.abspath(root_dir))
                    idx[pkg_path] = full

    # Cache to disk
    try:
        with open(INDEX_FILE, "w") as f:
            json.dump(idx, f)
    except OSError:
        pass

    return idx


def resolve_source_path(sourcepath, project_root=None):
    """Resolve a SpotBugs/package sourcepath to an actual file path using cached index."""
    if not sourcepath:
        return ""
    idx = build_source_index()
    resolved = idx.get(sourcepath)
    if resolved and os.path.exists(resolved):
        return resolved
    # Fallback: try direct path
    if project_root:
        full = os.path.join(project_root, sourcepath)
        if os.path.exists(full):
            return full
    return sourcepath
