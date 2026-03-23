#!/usr/bin/env python3
"""
Convert PMD XML output to HTML with I2P+ dark theme.

Usage: pmd-to-html.py pmd.xml output.html
"""

import sys
import os
import re
import subprocess
from datetime import datetime, timezone
from xml.etree import ElementTree as ET
from urllib.parse import quote

def minify_css(css):
    try:
        r = subprocess.run(["cleancss"], input=css.encode(), capture_output=True, timeout=5)
        if r.returncode == 0:
            return r.stdout.decode()
    except (FileNotFoundError, subprocess.TimeoutExpired):
        pass
    # Fallback: basic minification
    css = re.sub(r'/\*.*?\*/', '', css, flags=re.DOTALL)
    css = re.sub(r'\s+', ' ', css)
    css = re.sub(r'\s*([{}:;,])\s*', r'\1', css)
    return css.strip()

def minify_js(js):
    try:
        r = subprocess.run(["terser"], input=js.encode(), capture_output=True, timeout=5)
        if r.returncode == 0:
            return r.stdout.decode()
    except (FileNotFoundError, subprocess.TimeoutExpired):
        pass
    # Fallback: basic minification
    js = re.sub(r'\s+', ' ', js)
    return js.strip()

def minify_html(html):
    html = re.sub(r'<!--.*?-->', '', html, flags=re.DOTALL)
    # Minify CSS blocks
    def _min_css(m):
        return '<style>' + minify_css(m.group(1)) + '</style>'
    html = re.sub(r'<style>(.*?)</style>', _min_css, html, flags=re.DOTALL)
    # Minify JS blocks
    def _min_js(m):
        return '<script>' + minify_js(m.group(1)) + '</script>'
    html = re.sub(r'<script>(.*?)</script>', _min_js, html, flags=re.DOTALL)
    # Collapse whitespace between tags
    html = re.sub(r'>\s+<', '><', html)
    html = re.sub(r'\n{2,}', '\n', html)
    return html.strip()

NS = {"pmd": "http://pmd.sourceforge.net/report/2.0.0"}

def escape(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


def load_favicon():
    # Use the I2P+ plus icon from the console theme
    path = os.path.join(os.path.dirname(__file__), "..", "..", "installer", "resources", "themes", "console", "images", "plus.svg")
    try:
        with open(path) as f:
            return f"data:image/svg+xml,{quote(f.read())}"
    except FileNotFoundError:
        return None


def load_css():
    path = os.path.join(os.path.dirname(__file__), "report.css")
    try:
        with open(path) as f:
            return f.read()
    except FileNotFoundError:
        return ""

CONTEXT = 2


def get_code_snippet(filepath, line_no, context=CONTEXT):
    try:
        line_no = int(line_no)
    except (ValueError, TypeError):
        return ""
    try:
        with open(filepath, errors="replace") as f:
            src = f.readlines()
    except (OSError, IOError):
        return ""
    start = max(0, line_no - 1 - context)
    end = min(len(src), line_no + context)
    pad = len(str(end))
    out = []
    for i in range(start, end):
        ln = i + 1
        ln_str = f'<span class="ln">{ln:>{pad}}</span>'
        raw = src[i].rstrip()
        code_text = escape(raw) if raw else ""
        cls = "code-line offense" if ln == line_no else "code-line"
        code_line = f'{ln_str} <span class="{cls}">{code_text}</span>'
        out.append(code_line)
    return "\n".join(out)
    return f"data:image/svg+xml,{quote(svg, safe='')}"

def main():
    if len(sys.argv) < 3 or len(sys.argv) > 4:
        print(f"Usage: {sys.argv[0]} pmd.xml output.html [language]", file=sys.stderr)
        sys.exit(1)

    xml_file, html_file = sys.argv[1], sys.argv[2]
    lang = sys.argv[3] if len(sys.argv) == 4 else ""
    # Handle truncated XML (PMD sometimes drops the closing tag)
    with open(xml_file, "r") as f:
        content = f.read()
    if not content.rstrip().endswith("</pmd>"):
        content = content.rstrip() + "\n</pmd>\n"
    root = ET.fromstring(content)

    version = root.attrib.get("version", "?")
    raw_ts = root.attrib.get("timestamp", "?")
    try:
        timestamp = datetime.fromisoformat(raw_ts).astimezone(timezone.utc).strftime("%B %-d %Y, %H:%M UTC")
    except ValueError:
        timestamp = raw_ts

    # Collect violations grouped by file and by rule
    files = []
    rule_counts = {}
    rule_violations = {}
    subsystem_counts = {}
    total = 0

    def get_subsystem(pkg, fname):
        # Java: derive from package name
        if pkg:
            parts = pkg.split(".")
            if parts[0] == "net" and len(parts) > 1 and parts[1] == "i2p" and len(parts) > 2:
                sub = parts[2]
                return {
                    "router": "Router",
                    "data": "Data",
                    "streaming": "Streaming",
                    "client": "Client",
                    "crypto": "Crypto",
                    "util": "Utilities",
                    "i2psnark": "I2PSnark",
                    "addressbook": "Addressbook",
                    "router.news": "News",
                    "router.time": "Time",
                    "router.startup": "Startup",
                    "router.transport": "Transport",
                    "router.tunnel": "Tunnels",
                    "router.networkdb": "NetDB",
                    "router.peermanager": "Peer Manager",
                    "router.deny": "Sybil",
                    "router.update": "Update",
                    "router.web": "Router Console",
                    "client.naming": "Naming",
                    "client.streaming": "Client Streaming",
                    "client.impl": "Client Impl",
                    "app": "Apps",
                    "desktop": "Desktop",
                    "update": "Updater",
                }.get(sub, sub.capitalize())
            # Non-net.i2p package — check file path for apps/
            path = fname.replace("\\", "/")
            if "apps/" in path:
                app = path.split("apps/")[1].split("/")[0]
                return {
                    "routerconsole": "Router Console",
                    "i2ptunnel": "I2PTunnel",
                    "i2psnark": "I2PSnark",
                    "susidns": "SusiDNS",
                    "susimail": "SusiMail",
                    "addressbook": "Addressbook",
                    "jetty": "Jetty",
                    "wrapper": "Wrapper",
                }.get(app, app.replace("-", " ").title())
            return "Third-party"
        # No package — derive from file path
        if fname:
            path = fname.replace("\\", "/")
            if "apps/" in path:
                app = path.split("apps/")[1].split("/")[0]
                return {
                    "routerconsole": "Router Console",
                    "i2ptunnel": "I2PTunnel",
                    "i2psnark": "I2PSnark",
                    "susidns": "SusiDNS",
                    "susimail": "SusiMail",
                    "addressbook": "Addressbook",
                    "jetty": "Jetty",
                    "wrapper": "Wrapper",
                }.get(app, app.replace("-", " ").title())
            # Extract from core/ or router/
            if "/core/" in path:
                return "Core"
            if "/router/" in path:
                return "Router"
        return "Other"

    for fnode in root.findall("pmd:file", NS):
        fname = fnode.attrib["name"]
        violations = []
        for vnode in fnode.findall("pmd:violation", NS):
            v = vnode.attrib
            msg = (vnode.text or "").strip()
            rule = v.get("rule", "?")
            pkg = v.get("package", "")
            sub = get_subsystem(pkg, fname)
            rule_counts[rule] = rule_counts.get(rule, 0) + 1
            subsystem_counts[sub] = subsystem_counts.get(sub, 0) + 1
            total += 1
            vdict = {
                "begin": v.get("beginline", "?"),
                "end": v.get("endline", "?"),
                "rule": rule,
                "ruleset": v.get("ruleset", ""),
                "priority": v.get("priority", "3"),
                "class": v.get("class", ""),
                "method": v.get("method", ""),
                "url": v.get("externalInfoUrl", ""),
                "msg": msg,
                "subsystem": sub,
                "file": fname,
            }
            violations.append(vdict)
            rule_violations.setdefault(rule, []).append((fname, vdict))
        if violations:
            files.append((fname, violations))

    files.sort(key=lambda x: x[0])

    # Build summary
    sorted_rules = sorted(rule_counts.items(), key=lambda x: -x[1])
    sorted_subs = sorted(subsystem_counts.items(), key=lambda x: -x[1])

    # Build per-rule subsystem list with counts
    rule_subs = {}
    rule_sub_counts = {}
    rule_files = {}
    for rule, vlist in rule_violations.items():
        sub_count = {}
        fset = set()
        for fname, v in vlist:
            sub_count[v["subsystem"]] = sub_count.get(v["subsystem"], 0) + 1
            fset.add(fname)
        # Sort by count descending
        rule_subs[rule] = sorted(sub_count.keys(), key=lambda s: -sub_count[s])
        rule_sub_counts[rule] = sub_count
        rule_files[rule] = len(fset)

    # Generate HTML
    lines = []
    w = lines.append

    w('<!DOCTYPE html>')
    w('<html lang="en">')
    w('<head>')
    w('<meta charset="UTF-8">')
    w('<meta name="viewport" content="width=device-width, initial-scale=1.0">')
    favicon = load_favicon()
    if favicon:
        w(f'<link rel="icon" type="image/svg+xml" href="{favicon}">')
    suffix = f" ({lang})" if lang else ""
    w(f'<title>I2P+ | PMD Report{suffix}</title>')
    w('<style>')
    w(load_css())
    w('</style>')
    w('<script>')
    # Build rule data as JSON for filtering
    import json
    rule_data_json = json.dumps({rule: [(f, {"begin": v["begin"], "end": v["end"], "priority": v["priority"],
        "msg": v["msg"], "class": v["class"], "method": v["method"], "url": v["url"],
        "ruleset": v["ruleset"], "subsystem": v["subsystem"]}) for f, v in vlist]
        for rule, vlist in rule_violations.items()})
    w(f'var RULE_DATA={rule_data_json};')
    w('''document.addEventListener("click",function(e){
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
  var a=e.target.closest("a[data-sub]");
  if(a){e.preventDefault();var tgt=document.getElementById(a.dataset.sub);
    if(tgt){document.querySelectorAll("details[open]").forEach(function(o){o.removeAttribute("open")});
      tgt.setAttribute("open","");setTimeout(function(){tgt.scrollIntoView({behavior:"smooth",block:"start"})},50)}return}
  var rl=e.target.closest("[data-rule]");
  if(rl){e.preventDefault();showRule(rl.dataset.rule);return}
  var bk=e.target.closest("#rule-back");
  if(bk){e.preventDefault();hideRule();return}
});
function esc(s){return s.replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;")}
function showRule(rule){
  var d= RULE_DATA[rule]; if(!d)return;
  var c=document.getElementById("rule-filter");
  var v=document.getElementById("violations");
  v.style.display="none";
  c.style.display="block";
  var h=\'<div class="tabletitle"><a id="rule-back" href="#">\u25C0 Back</a> &middot; \'+esc(rule)+\' <span class="badge">\'+d.length+\'</span></div>\';
  var curFile="";
  for(var i=0;i<d.length;i++){
    var f=d[i][0], x=d[i][1];
    if(f!==curFile){curFile=f;
      h+=\'<h3><span class="path">\'+esc(f)+\'</span></h3>\';
      h+=\'<table class="warningtable"><tr><th class="line">Line</th><th>Message</th><th class="rule-category">Category</th><th class="rule-doc">Doc</th></tr>\';}
    var rng= x.begin===x.end? x.begin : x.begin+\'-\'+x.end;
    var dl=\'\';
    if(x.url)dl=\'<a href="\'+esc(x.url)+\'" target="_blank" class="rule-doc-link"><span class="rule-doc-icon" title="Rule documentation"></span></a>\';
    h+=\'<tr class="tablerow\'+(i%2)+\'"><td class="line priority-cell p\'+x.priority+\'">\'+esc(rng)+\'</td>\';
    h+=\'<td>\'+esc(x.msg)+\'</td>\';
    h+=\'<td class="rule-category">\'+esc(x.ruleset)+\'</td>\';
    h+=\'<td class="rule-doc">\'+dl+\'</td></tr>\';
    if(i+1>=d.length||d[i+1][0]!==f) h+=\'</table>\';
  }
  c.innerHTML=h;
  c.scrollIntoView({behavior:"smooth",block:"start"});
}
function hideRule(){
  document.getElementById("rule-filter").style.display="none";
  document.getElementById("violations").style.display="block";
}''')
    w('</script>')
    w('</head>')
    w('<body>')

    w(f'<h1>I2P+ PMD Report{suffix}</h1>')
    w(f'<p class="meta">PMD {escape(version)} &middot; {total} violations across {len(files)} files &middot; {escape(timestamp)}</p>')

    # Navbar
    w('<div id="navbar">')
    for sub, count in sorted_subs:
        w(f'<span><a href="#sub-{escape(sub)}" data-sub="sub-{escape(sub)}">{escape(sub)} <span class="badge">{count}</span></a></span>')
    w('</div>')

    # Group files by sub-system
    sub_files = {}
    for fname, violations in files:
        sub = violations[0]["subsystem"]
        sub_files.setdefault(sub, []).append((fname, violations))

    # Combined summary: by rule with sub-system tags
    w('<div class="tabletitle"><a name="rules">By Rule</a></div>')
    w('<table id="summary">')
    w('<tr><th>Rule</th><th>Sub-systems</th><th class="summary-count">Violations</th><th class="summary-count">Files</th></tr>')
    for i, (rule, count) in enumerate(sorted_rules):
        row = "tablerow" + str(i % 2)
        fc = rule_files.get(rule, 0)
        sub_counts = rule_sub_counts.get(rule, {})
        subs_str = ", ".join(f'<a href="#sub-{escape(s)}" data-sub="sub-{escape(s)}">{escape(s)}</a> ({sub_counts[s]})' for s in rule_subs.get(rule, []))
        w(f'<tr class="{row}"><td><a href="#" data-rule="{escape(rule)}">{escape(rule)}</a></td><td class="subs">{subs_str}</td><td class="summary-count">{count}</td><td class="summary-count">{fc}</td></tr>')
    w(f'<tr class="tablerow0"><td><b>Total</b></td><td></td><td class="summary-count"><b>{total}</b></td><td class="summary-count"><b>{len(files)}</b></td></tr>')
    w('</table>')

    # Rule filter container (hidden by default, shown when a rule is clicked)
    w('<div id="rule-filter" style="display:none"></div>')

    # Violations grouped by sub-system (collapsible)
    w('<div id="violations">')
    w('<div class="tabletitle"><a name="violations">Violations</a></div>')

    for sub, count in sorted_subs:
        if sub not in sub_files:
            continue
        nfiles = len(sub_files[sub])
        w(f'<details id="sub-{escape(sub)}">')
        w(f'<summary><div class="tabletitle"><a name="sub-{escape(sub)}">{escape(sub)}</a>&#32;<span class="badge">{count} / {nfiles}</span></div></summary>')
        for fname, violations in sub_files[sub]:
            w(f'<h3><span class="path">{escape(fname)}</span> <span class="badge">{len(violations)}</span></h3>')
            w('<table class="warningtable">')
            w('<tr><th class="line">Line</th><th>Rule</th><th>Message</th><th class="rule-category">Category</th><th class="rule-doc">Doc</th></tr>')
            for i, v in enumerate(sorted(violations, key=lambda x: int(x["begin"]))):
                row = "tablerow" + str(i % 2)
                vid = f"v{abs(hash(fname + v['begin'] + v['rule']))}"
                rng = v["begin"] if v["begin"] == v["end"] else f'{v["begin"]}-{v["end"]}'
                doc_link = ''
                if v["url"]:
                    doc_link = f'<a href="{escape(v["url"])}" target="_blank" class="rule-doc-link"><span class="rule-doc-icon" title="Rule documentation: {escape(v["rule"])}"></span></a>'
                detail_parts = []
                if v["class"]:
                    detail_parts.append(f'<b>Class:</b> {escape(v["class"])}')
                if v["method"]:
                    detail_parts.append(f'<b>Method:</b> {escape(v["method"])}')
                snippet = get_code_snippet(fname, v["begin"])
                has_detail = bool(detail_parts) or bool(snippet)
                if has_detail:
                    w(f'<tr class="{row}" data-detail="{vid}">')
                else:
                    w(f'<tr class="{row}">')
                w(f'<td class="line priority-cell p{v["priority"]}">{escape(rng)}</td>')
                w(f'<td>{escape(v["rule"])}</td>')
                w(f'<td>{escape(v["msg"])}</td>')
                w(f'<td class="rule-category">{escape(v["ruleset"])}</td>')
                w(f'<td class="rule-doc">{doc_link}</td>')
                w('</tr>')
                if has_detail:
                    w(f'<tr class="detailrow{i % 2} hidden"><td colspan="5">')
                    w(f'<div id="{vid}" style="display:none" class="detail-content">')
                    if detail_parts:
                        w(' &middot; '.join(detail_parts))
                    if snippet:
                        w(f'<pre class="snippet"><code>{snippet}</code></pre>')
                    w('</div>')
                    w('</td></tr>')
            w('</table>')
        w('</details>')

    w('</div>')  # close violations div
    w('</body>')
    w('</html>')

    html_output = "\n".join(lines)
    html_output = minify_html(html_output)
    with open(html_file, "w") as f:
        f.write(html_output)

    print(f"Wrote {html_file}: {total} violations across {len(files)} files", file=sys.stderr)

if __name__ == "__main__":
    main()
