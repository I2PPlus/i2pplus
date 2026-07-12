#!/usr/bin/env python3
"""Fetch SonarQube Java rules and generate self-contained static HTML site.

Usage:
  fetch-rules.py [--url <sonar_url>] [--token <token>] [--output <dir>] [--language java]

Reads sonar.host.url from sonar-project.properties if --url not given.
Reads token from .token file if --token not given.
"""

import html
import json
import os
import re
import sys
import urllib.parse
import urllib.request
import urllib.error

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(SCRIPT_DIR, "..", "template"))
from common import load_favicon

PROJECT_DIR = os.path.abspath(os.path.join(SCRIPT_DIR, "..", ".."))
DEFAULT_OUTPUT = os.path.join(SCRIPT_DIR, "rules")
PROPERTIES_FILE = os.path.join(SCRIPT_DIR, "sonar-project.properties")
TOKEN_FILE = os.path.join(SCRIPT_DIR, ".token")

SQ_TYPE_LABEL = {"BUG": "Bug", "VULNERABILITY": "Vulnerability", "CODE_SMELL": "Code Smell", "SECURITY_HOTSPOT": "Security Hotspot"}
SQ_TYPE_CLASS = {"BUG": "sq-bug", "VULNERABILITY": "sq-vuln", "CODE_SMELL": "sq-smell", "SECURITY_HOTSPOT": "sq-hotspot"}
SQ_SEV_CLASS = {"BLOCKER": "p1", "CRITICAL": "p2", "MAJOR": "p3", "MINOR": "p4", "INFO": "p5"}

CSS_FILE = os.path.join(SCRIPT_DIR, "assets", "style.css")
JS_FILE = os.path.join(SCRIPT_DIR, "assets", "filter.js")

def load_css():
    try:
        with open(CSS_FILE, encoding="utf-8") as f:
            return f.read()
    except (FileNotFoundError, OSError):
        print("warning: assets/style.css not found", file=sys.stderr)
        return ""

def load_js():
    try:
        with open(JS_FILE, encoding="utf-8") as f:
            return f.read()
    except (FileNotFoundError, OSError):
        print("warning: assets/filter.js not found", file=sys.stderr)
        return ""

def load_properties(path):
    props = {}
    if not os.path.isfile(path):
        return props
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if "=" not in line:
                continue
            k, _, v = line.partition("=")
            props[k.strip()] = v.strip()
    return props


def read_token(path):
    if os.path.isfile(path):
        with open(path, encoding="utf-8") as f:
            return f.read().strip()
    return None


def sq_api(url, token):
    req = urllib.request.Request(url)
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        print(f"sq_api HTTP {e.code} for URL: {url}")
        try:
            body = e.read().decode("utf-8", errors="replace")[:1000]
            if body:
                print(f"  Response: {body}")
        except Exception:
            pass
        raise


def clean_html(html_text):
    if not html_text:
        return ""
    text = re.sub(r"<br\s*/?>", "\n", html_text)
    text = re.sub(r"</p>", "\n\n", text)
    text = re.sub(r"<li>", "- ", text)
    text = re.sub(r"</li>", "", text)
    text = re.sub(r"<[^>]+>", "", text)
    text = html.escape(text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    text = text.strip()
    return "<pre>" + text + "</pre>" if "\n" in text else "<p>" + text + "</p>"


def fetch_all_rules(base_url, token, language="java", page_size=500):
    rules = []
    total = None
    page = 1
    fields = "name,severity,htmlDesc,mdDesc,descriptionSections,params,tags,debtRemFn,defaultDebtRemFn,defaultRemFn,gapDescription,htmlNote,sysTags"
    while total is None or len(rules) < total:
        url = f"{base_url}/api/rules/search?languages={language}&ps={page_size}&p={page}&f={fields}"
        data = sq_api(url, token)
        if total is None:
            total = data.get("total", 0)
        page_rules = data.get("rules", [])
        rules.extend(page_rules)
        if len(rules) >= total:
            if page_rules:
                print(f"  Rule keys available: {list(page_rules[0].keys())}")
            break
        page += 1
    return rules


def rule_sort_key(r):
    sev = {"BLOCKER": 0, "CRITICAL": 1, "MAJOR": 2, "MINOR": 3, "INFO": 4}
    typ = {"BUG": 0, "VULNERABILITY": 1, "SECURITY_HOTSPOT": 2, "CODE_SMELL": 3}
    return (typ.get(r.get("type", ""), 9), sev.get(r.get("severity", ""), 9), r.get("key", ""))


def render_type_badge(rtype):
    label = SQ_TYPE_LABEL.get(rtype, rtype)
    cls = SQ_TYPE_CLASS.get(rtype, "sq-smell")
    return f'<span class="sq-badge {cls}">{label}</span>'


def render_sev_badge(sev):
    cls = SQ_SEV_CLASS.get(sev, "p5")
    return f'<span class="sq-badge {cls}">{sev}</span>'


def render_tags(tags):
    if not tags:
        return ""
    return " ".join(f'<span class="tag">{html.escape(t)}</span>' for t in tags)


def write_assets(output_dir):
    """Write external CSS and JS to assets/ subdirectory."""
    assets_dir = os.path.join(output_dir, "assets")
    os.makedirs(assets_dir, exist_ok=True)
    css = load_css()
    css_path = os.path.join(assets_dir, "style.css")
    with open(css_path, "w", encoding="utf-8") as f:
        f.write(css)
    js = load_js()
    js_path = os.path.join(assets_dir, "filter.js")
    with open(js_path, "w", encoding="utf-8") as f:
        f.write(js)
    return css_path, js_path


_PRE_PLACEHOLDER = "__PRE_BLOCK_%d__"

def minify_html(html_text):
    """Minify HTML, preserving <pre> content exactly."""
    import html as html_mod

    pres = []
    def save_pre(m):
        idx = len(pres)
        pres.append(m.group(0))
        return _PRE_PLACEHOLDER % idx

    # Save <pre> blocks
    text = re.sub(r'<pre[^>]*>.*?</pre>', save_pre, html_text, flags=re.DOTALL)

    # Collapse whitespace outside <pre>
    text = re.sub(r'\s+', ' ', text)
    text = re.sub(r'>\s+<', '><', text)
    text = re.sub(r'\s+/>', '/>', text)

    # Restore <pre> blocks
    for idx, p in enumerate(pres):
        text = text.replace(_PRE_PLACEHOLDER % idx, p)
    return text.strip()


def _strip_empty_headings(html_text):
    """Remove <h2>/<h3> tags with no content before the next heading or end of text."""
    return re.sub(r'<h[23][^>]*>.*?</h[23]>\s*(?=<h[23]|$)', '', html_text, flags=re.DOTALL | re.IGNORECASE)


def generate_index(rules, output_dir, minify=False, favicon_tag=""):
    rows = []
    for r in rules:
        key = r.get("key", "")
        name = html.escape(r.get("name", ""))
        rtype = r.get("type", "")
        sev = r.get("severity", "")
        tags_list = r.get("tags", []) or []
        sys_tags_list = r.get("sysTags", []) or []
        all_tags_list = list(dict.fromkeys(tags_list + sys_tags_list)) if sys_tags_list else tags_list
        key_short = key.split(":", 1)[-1] if ":" in key else key
        rows.append(f"""<tr data-key="{html.escape(key)}" data-name="{html.escape(r.get("name", "").lower())}" data-type="{rtype}" data-sev="{sev}">
<td class="key"><a href="{key_short}.html">{html.escape(key)}</a></td>
<td>{render_type_badge(rtype)}</td>
<td>{render_sev_badge(sev)}</td>
<td><a href="{key_short}.html">{name}</a></td>
<td>{render_tags(all_tags_list)}</td>
</tr>""")

    html_content = f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>SonarQube Java Rules</title>
{favicon_tag}
<link rel="stylesheet" href="assets/style.css">
</head>
<body>
<div class="header">
<h1>SonarQube Java Rules</h1>
<p>{len(rules)} rules &middot; <span class="topnav"><a href="../report.html">SonarQube Report</a></span></p>
</div>
<div class="wrap">
<div class="toolbar">
<input type="text" id="filter" placeholder="Filter by key, name, or tag..." oninput="applyFilter()">
<label><input type="checkbox" id="hideCodeSmells" onchange="applyFilter()"> Hide Code Smells</label>
<label><input type="checkbox" id="hideInfo" onchange="applyFilter()"> Hide Info/Minor</label>
</div>
<table>
<thead><tr><th>Rule</th><th>Type</th><th>Severity</th><th>Description</th><th>Tags</th></tr></thead>
<tbody id="rules-body">
{chr(10).join(rows)}
</tbody>
</table>
</div>
<script src="assets/filter.js"></script>
</body>
</html>"""
    if minify:
        html_content = minify_html(html_content)
    index_path = os.path.join(output_dir, "index.html")
    with open(index_path, "w", encoding="utf-8") as f:
        f.write(html_content)
    return index_path


def generate_rule_page(rule, output_dir, minify=False, favicon_tag=""):
    key = rule.get("key", "")
    key_short = key.split(":", 1)[-1] if ":" in key else key
    name = rule.get("name", "")
    rtype = rule.get("type", "")
    sev = rule.get("severity", "")
    tags = rule.get("tags", []) or []
    sys_tags = rule.get("sysTags", []) or []
    all_tags = list(dict.fromkeys(tags + sys_tags)) if sys_tags else tags
    html_desc = rule.get("htmlDesc", "") or ""
    html_note = rule.get("htmlNote", "") or ""
    desc_sections = rule.get("descriptionSections") or []

    desc_body = ""
    if desc_sections:
        parts = []
        for sec in desc_sections:
            content = (sec.get("content") or "").strip()
            if content:
                sec_key = sec.get("key", "")
                if sec_key and sec_key != "default":
                    label = sec_key.replace("_", " ").capitalize()
                    parts.append(f"<h3>{html.escape(label)}</h3>\n{content}")
                else:
                    parts.append(content)
        desc_body = "\n".join(parts) if parts else ""
    if not desc_body and html_desc.strip():
        desc_body = html_desc
    if not desc_body:
        md_desc = rule.get("mdDesc", "") or ""
        if md_desc.strip():
            desc_body = f"<pre>{html.escape(md_desc)}</pre>"
    if not desc_body:
        desc_body = "<p>No description available.</p>"
    else:
        desc_body = _strip_empty_headings(desc_body)
    html_note = _strip_empty_headings(html_note)
    note_body = f"<h2>Note</h2>{html_note}" if html_note.strip() else ""

    params = rule.get("params", [])
    params_html = ""
    if params:
        rows = []
        for p in params:
            p_name = html.escape(p.get("key", ""))
            p_desc = html.escape(p.get("description", "") or "")
            p_default = html.escape(p.get("defaultValue", "") or "")
            rows.append(f"<tr><td><code>{p_name}</code></td><td>{p_desc}</td><td>{p_default}</td></tr>")
        params_html = f"""<h2>Parameters</h2>
<table><thead><tr><th>Key</th><th>Description</th><th>Default</th></tr></thead>
<tbody>{chr(10).join(rows)}</tbody></table>"""

    debt_raw = rule.get("debtRemFn") or rule.get("defaultDebtRemFn") or rule.get("defaultRemFn") or rule.get("remFn") or ""
    default_debt = ""
    if debt_raw:
        if isinstance(debt_raw, dict):
            debt_type = html.escape(debt_raw.get("type", "") or "")
            debt_coeff = debt_raw.get("coefficient") or debt_raw.get("coefficient") or ""
            if debt_type:
                txt = debt_type
                if debt_coeff:
                    txt += f" ({debt_coeff})"
                default_debt = f'<div class="meta-row"><strong>Remediation:</strong> {txt}</div>'
        elif isinstance(debt_raw, str) and debt_raw.strip():
            default_debt = f'<div class="meta-row"><strong>Remediation:</strong> {html.escape(debt_raw.strip())}</div>'

    body = f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html.escape(name)} - {html.escape(key)}</title>
{favicon_tag}
<link rel="stylesheet" href="assets/style.css">
</head>
<body class="rule-page">
<div class="header">
<span class="topnav"><a class="back" href="index.html">&larr; Back to all rules</a> &middot; <a href="../report.html">Report</a></span>
<h1>{html.escape(key)}: {html.escape(name)}</h1>
</div>
<div class="wrap">
<div class="rule-body">
<div class="rule-meta">
{render_type_badge(rtype)}
{render_sev_badge(sev)}
</div>
{default_debt}
{desc_body}
{note_body}
{params_html}
<div class="rule-tags">
<h2>Tags</h2>
<p>{render_tags(all_tags) if all_tags else "None"}</p>
</div>
</div>
</div>
</body>
</html>"""
    if minify:
        body = minify_html(body)

    os.makedirs(output_dir, exist_ok=True)
    rule_path = os.path.join(output_dir, f"{key_short}.html")
    with open(rule_path, "w", encoding="utf-8") as f:
        f.write(body)
    return rule_path


def main():
    import argparse
    parser = argparse.ArgumentParser(description="Fetch SonarQube rules and generate static HTML site")
    parser.add_argument("--url", help="SonarQube base URL")
    parser.add_argument("--token", help="Auth token")
    parser.add_argument("--output", default=DEFAULT_OUTPUT, help=f"Output directory (default: {DEFAULT_OUTPUT})")
    parser.add_argument("--language", default="java", help="Language filter (default: java)")
    parser.add_argument("--skip-if-exists", action="store_true",
                        help="Skip generation if index.html already exists in output directory")
    parser.add_argument("--minify", action="store_true",
                        help="Minify HTML output (preserves <pre> content)")
    args = parser.parse_args()

    output_dir = os.path.abspath(args.output)

    if args.skip_if_exists:
        index_check = os.path.join(output_dir, "index.html")
        assets_check = os.path.join(output_dir, "assets", "style.css")
        if os.path.isfile(index_check) and os.path.isfile(assets_check):
            print(f"Rules docs already exist at {output_dir} (--skip-if-exists), skipping fetch.")
            return 0

    base_url = args.url
    if not base_url:
        props = load_properties(PROPERTIES_FILE)
        base_url = props.get("sonar.host.url", "http://localhost:11199")

    token = args.token
    if not token:
        token = read_token(TOKEN_FILE)

    os.makedirs(output_dir, exist_ok=True)

    print(f"Fetching {args.language} rules from {base_url}...")
    try:
        rules = fetch_all_rules(base_url, token, args.language)
    except urllib.error.HTTPError as e:
        print(f"Error: HTTP {e.code} from {base_url}")
        print(f"  URL: {e.url}")
        print(f"  Reason: {e.msg}")
        try:
            body = e.read().decode("utf-8", errors="replace")[:1000]
            if body:
                print(f"  Response: {body}")
        except Exception:
            pass
        sys.exit(1)
    except urllib.error.URLError as e:
        reason = e.reason if hasattr(e, 'reason') else str(e)
        print(f"Error: cannot connect to SonarQube at {base_url}: {reason}")
        print("Is the SonarQube server running? Try: ant sonarqube-report")
        sys.exit(1)
    print(f"Fetched {len(rules)} rules.")

    rules.sort(key=rule_sort_key)

    favicon = load_favicon()
    favicon_tag = f'<link rel="icon" type="image/svg+xml" href="{favicon}">' if favicon else ""

    css_path, js_path = write_assets(output_dir)
    print(f"Assets: {css_path}, {js_path}")

    index_path = generate_index(rules, output_dir, minify=args.minify, favicon_tag=favicon_tag)
    print(f"Index: {index_path}")

    for r in rules:
        generate_rule_page(r, output_dir, minify=args.minify, favicon_tag=favicon_tag)

    tally = f"{len(rules)} rule pages + index + assets/style.css + assets/filter.js"
    if args.minify:
        tally += " (minified)"
    print(f"Done. Open {index_path} in a browser.")
    print(f"Total files: {tally}")


if __name__ == "__main__":
    main()
