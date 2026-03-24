#!/usr/bin/env python3
"""
Convert Checkstyle XML output to HTML with I2P+ dark theme.
Shares CSS with PMD report for consistent look.

Usage: checkstyle-to-html.py checkstyle.xml output.html [--local]
"""

import sys
import os
import re
import subprocess
from datetime import datetime, timezone
from xml.etree import ElementTree as ET


def minify_css(css):
    try:
        r = subprocess.run(["cleancss"], input=css.encode(), capture_output=True, timeout=5)
        if r.returncode == 0:
            return r.stdout.decode()
    except (FileNotFoundError, subprocess.TimeoutExpired):
        pass
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
    js = re.sub(r'\s+', ' ', js)
    return js.strip()


def minify_html(html):
    html = re.sub(r'<!--.*?-->', '', html, flags=re.DOTALL)
    def _min_css(m):
        return '<style>' + minify_css(m.group(1)) + '</style>'
    html = re.sub(r'<style>(.*?)</style>', _min_css, html, flags=re.DOTALL)
    def _min_js(m):
        return '<script>' + minify_js(m.group(1)) + '</script>'
    html = re.sub(r'<script>(.*?)</script>', _min_js, html, flags=re.DOTALL)
    html = re.sub(r'>\s+<', '><', html)
    html = re.sub(r'\n{2,}', '\n', html)
    return html.strip()


def escape(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


def load_favicon():
    path = os.path.join(os.path.dirname(__file__), "..", "..", "installer", "resources", "themes", "console", "images", "plus.svg")
    try:
        with open(path) as f:
            from urllib.parse import quote
            return f"data:image/svg+xml,{quote(f.read())}"
    except FileNotFoundError:
        return None


def load_css():
    path = os.path.join(os.path.dirname(__file__), "..", "pmd", "report.css")
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


def main():
    if len(sys.argv) < 3:
        print(f"Usage: {sys.argv[0]} checkstyle.xml output.html [--local]", file=sys.stderr)
        sys.exit(1)

    xml_file, html_file = sys.argv[1], sys.argv[2]
    local = "--local" in sys.argv

    with open(xml_file, "r") as f:
        content = f.read()
    # Handle truncated XML: close incomplete tags
    if not content.rstrip().endswith("</checkstyle>"):
        # Find last complete </error> or </file> tag
        last_tag = -1
        for tag in ("</error>", "</file>"):
            pos = content.rfind(tag)
            if pos > last_tag:
                last_tag = pos + len(tag)
        if last_tag > 0:
            content = content[:last_tag]
        # Ensure we're inside a <file> that needs closing
        if "</file>" not in content[content.rfind("<file") if "<file" in content else 0:]:
            content += "\n</file>"
        content += "\n</checkstyle>\n"
    try:
        root = ET.fromstring(content)
    except ET.ParseError as e:
        print(f"Warning: XML parse error ({e}), attempting repair", file=sys.stderr)
        # Last resort: extract only complete <file>...</file> blocks
        import re
        files = re.findall(r'<file\b[^>]*>.*?</file>', content, re.DOTALL)
        content = '<?xml version="1.0" encoding="UTF-8"?>\n<checkstyle>\n' + "\n".join(files) + "\n</checkstyle>\n"
        root = ET.fromstring(content)

    version = root.attrib.get("version", "?")

    # Collect violations
    files = []
    check_counts = {}
    severity_counts = {}
    subsystem_counts = {}
    check_violations = {}
    total = 0

    def get_subsystem(fname):
        path = fname.replace("\\", "/")
        if "/core/" in path:
            return "Core"
        if "/router/" in path:
            return "Router"
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
        return "Other"

    def extract_check_info(source):
        """Extract check name, category, and doc URL from source."""
        # com.puppycrawl.tools.checkstyle.checks.naming.StaticVariableNameCheck
        if source:
            parts = source.split(".")
            name = parts[-1]
            if name.endswith("Check"):
                name = name[:-5]
            # Category is the package component after 'checks.'
            category = ""
            for i, p in enumerate(parts):
                if p == "checks" and i + 1 < len(parts):
                    category = parts[i + 1]
                    break
            url = f"https://checkstyle.org/checks/{category}/{name.lower()}.html" if category else ""
            return name, url
        return "?", ""

    for fnode in root.findall("file"):
        fname = fnode.attrib["name"]
        violations = []
        for enode in fnode.findall("error"):
            a = enode.attrib
            msg = a.get("message", "")
            check, url = extract_check_info(a.get("source", ""))
            severity = a.get("severity", "warning")
            line = a.get("line", "?")
            column = a.get("column", "?")
            sub = get_subsystem(fname)

            check_counts[check] = check_counts.get(check, 0) + 1
            severity_counts[severity] = severity_counts.get(severity, 0) + 1
            subsystem_counts[sub] = subsystem_counts.get(sub, 0) + 1
            total += 1

            vdict = {
                "line": line,
                "column": column,
                "check": check,
                "severity": severity,
                "message": msg,
                "subsystem": sub,
                "file": fname,
                "url": url,
            }
            violations.append(vdict)
            check_violations.setdefault(check, []).append((fname, vdict))
        if violations:
            files.append((fname, violations))

    files.sort(key=lambda x: x[0])
    sorted_checks = sorted(check_counts.items(), key=lambda x: -x[1])
    sorted_sevs = sorted(severity_counts.items(), key=lambda x: -x[1])
    sorted_subs = sorted(subsystem_counts.items(), key=lambda x: -x[1])

    # Per-check subsystem data
    check_subs = {}
    check_sub_counts = {}
    check_files = {}
    for check, vlist in check_violations.items():
        sub_count = {}
        fset = set()
        for fname, v in vlist:
            sub_count[v["subsystem"]] = sub_count.get(v["subsystem"], 0) + 1
            fset.add(fname)
        check_subs[check] = sorted(sub_count.keys(), key=lambda s: -sub_count[s])
        check_sub_counts[check] = sub_count
        check_files[check] = len(fset)

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
    w('<title>I2P+ | Checkstyle Report</title>')
    w('<style>')
    w(load_css())
    w('''
#summary a[data-check]{cursor:pointer}
#check-filter .warningtable{border-top:1px solid #2a2a30}
''')
    w('</style>')
    w('<script>')
    w(f'var LOCAL_MODE={"true" if local else "false"};')

    NOISY_THRESHOLD = 1000
    def noisy(check):
        return check_counts.get(check, 0) >= NOISY_THRESHOLD

    import json
    def norm_path(f):
        return os.path.abspath(f) if local else f
    def maybe_snippet(check, f, line):
        return "" if noisy(check) else get_code_snippet(f, line)
    check_data_json = json.dumps({check: [(norm_path(f), {"line": v["line"], "column": v["column"],
        "severity": v["severity"], "message": v["message"], "subsystem": v["subsystem"],
        "url": v.get("url", ""),
        "snippet": maybe_snippet(check, f, v["line"])}) for f, v in vlist]
        for check, vlist in check_violations.items()})

    w(f'var CHECK_DATA={check_data_json};')
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
  if(a){e.preventDefault();hideCheck();var tgt=document.getElementById(a.dataset.sub);
    if(tgt){document.querySelectorAll("details[open]").forEach(function(o){o.removeAttribute("open")});
      tgt.setAttribute("open","");setTimeout(function(){tgt.scrollIntoView({behavior:"smooth",block:"start"})},50)}return}
  var rl=e.target.closest("[data-check]");
  if(rl){e.preventDefault();showCheck(rl.dataset.check);return}
});
function esc(s){return s.replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;")}
function showCheck(check){
  var d=CHECK_DATA[check]; if(!d)return;
  var c=document.getElementById("check-filter");
  var v=document.getElementById("violations");
  v.style.display="none";
  c.style.display="block";
  var h='<div class="tabletitle">'+esc(check)+' <span class="badge">'+d.length+'</span></div>';
  var curFile="";
  for(var i=0;i<d.length;i++){
    var f=d[i][0], x=d[i][1];
    if(f!==curFile){curFile=f;
      var pathHtml='<span class="path">'+esc(f)+'</span>';
      if(LOCAL_MODE) pathHtml='<a href="file://'+f+'" class="file-link">'+pathHtml+'</a>';
      h+='<h3>'+pathHtml+'</h3>';
      h+='<table class="warningtable"><tr><th class="line">Line</th><th>Message</th><th class="rule-category">Severity</th><th class="rule-doc">Doc</th></tr>';}
    var rng=x.line;
    var sevCls=x.severity==="error"?"p1":"p2";
    var dl='';
    if(x.url)dl='<a href="'+esc(x.url)+'" target="_blank" class="rule-doc-link"><span class="rule-doc-icon" title="Rule documentation"></span></a>';
    var details=['<b>Column:</b> '+esc(x.column)];
    var hasDetail=details.length>0||(x.snippet&&x.snippet.length>0);
    var vid='cv'+Math.abs(hashCode(f+x.line+check));
    if(hasDetail) h+='<tr class="tablerow'+(i%2)+'" data-detail="'+vid+'">';
    else h+='<tr class="tablerow'+(i%2)+'">';
    h+='<td class="line priority-cell '+sevCls+'">'+esc(rng)+'</td>';
    h+='<td>'+esc(x.message)+'</td>';
    h+='<td class="rule-category"><span class="severity-'+esc(x.severity)+'">'+esc(x.severity)+'</span></td>';
    h+='<td class="rule-doc">'+dl+'</td></tr>';
    if(hasDetail){
      h+='<tr class="detailrow'+(i%2)+' hidden"><td colspan="4">';
      h+='<div id="'+vid+'" style="display:none" class="detail-content">';
      if(details.length) h+=details.join(' &middot; ');
      if(x.snippet&&x.snippet.length) h+='<pre class="snippet"><code>'+x.snippet+'</code></pre>';
      h+='</div></td></tr>';
    }
    if(i+1>=d.length||d[i+1][0]!==f) h+='</table>';
  }
  c.innerHTML=h;
  c.scrollIntoView({behavior:"smooth",block:"start"});
}
function hashCode(s){var h=0;for(var i=0;i<s.length;i++){h=((h<<5)-h)+s.charCodeAt(i);h|=0}return h;}
function hideCheck(){
  document.getElementById("check-filter").style.display="none";
  document.getElementById("violations").style.display="block";
}''')
    w('</script>')
    w('</head>')
    w('<body id="checkstyle">')

    w('<h1>I2P+ Checkstyle Report</h1>')
    now = datetime.now(timezone.utc).strftime("%B %-d %Y, %H:%M UTC")
    w(f'<p class="meta">Checkstyle {escape(version)} &middot; {total} violations across {len(files)} files &middot; {escape(now)}</p>')

    if total > 0:
        # Navbar
        w('<div id="navbar">')
        for sub, count in sorted_subs:
            w(f'<span><a href="#sub-{escape(sub)}" data-sub="sub-{escape(sub)}">{escape(sub)} <span class="badge">{count}</span></a></span>')
        w('</div>')

        # Group files by subsystem
        sub_files = {}
        for fname, violations in files:
            sub = violations[0]["subsystem"]
            sub_files.setdefault(sub, []).append((fname, violations))

        # By check summary
        w('<div class="tabletitle"><a name="checks">By Check</a></div>')
        w('<table id="summary">')
        w('<tr><th>Check</th><th>Sub-systems</th><th class="summary-count">Violations</th><th class="summary-count">Files</th></tr>')
        for i, (check, count) in enumerate(sorted_checks):
            row = "tablerow" + str(i % 2)
            fc = check_files.get(check, 0)
            sc = check_sub_counts.get(check, {})
            subs_str = ", ".join(f'<a href="#sub-{escape(s)}" data-sub="sub-{escape(s)}">{escape(s)}</a> ({sc[s]})' for s in check_subs.get(check, []))
            w(f'<tr class="{row}"><td><a href="#" data-check="{escape(check)}">{escape(check)}</a></td><td class="subs">{subs_str}</td><td class="summary-count">{count}</td><td class="summary-count">{fc}</td></tr>')
        w(f'<tr class="tablerow0"><td><b>Total</b></td><td></td><td class="summary-count"><b>{total}</b></td><td class="summary-count"><b>{len(files)}</b></td></tr>')
        w('</table>')

        # Check filter container
        w('<div id="check-filter" style="display:none"></div>')

        # Violations grouped by subsystem
        w('<div id="violations">')
        w('<div class="tabletitle"><a name="violations">Violations</a></div>')

        for sub, count in sorted_subs:
            if sub not in sub_files:
                continue
            nfiles = len(sub_files[sub])
            w(f'<details id="sub-{escape(sub)}">')
            w(f'<summary><div class="tabletitle"><a name="sub-{escape(sub)}">{escape(sub)}</a>&#32;<span class="badge">{count} / {nfiles}</span></div></summary>')
            for fname, violations in sub_files[sub]:
                path_html = f'<span class="path">{escape(fname)}</span>'
                if local:
                    abs_path = os.path.abspath(fname)
                    path_html = f'<a href="file://{escape(abs_path)}" class="file-link">{path_html}</a>'
                w(f'<h3>{path_html} <span class="badge">{len(violations)}</span></h3>')
                w('<table class="warningtable">')
                w('<tr><th class="line">Line</th><th>Check</th><th>Message</th><th class="rule-category">Severity</th><th class="rule-doc">Doc</th></tr>')
                for i, v in enumerate(sorted(violations, key=lambda x: int(x["line"]))):
                    row = "tablerow" + str(i % 2)
                    vid = f"cv{abs(hash(fname + v['line'] + v['check']))}"
                    sev_cls = "p1" if v["severity"] == "error" else "p2"
                    snippet = maybe_snippet(v["check"], fname, v["line"])
                    has_detail = bool(snippet)
                    if has_detail:
                        w(f'<tr class="{row}" data-detail="{vid}">')
                    else:
                        w(f'<tr class="{row}">')
                    w(f'<td class="line priority-cell {sev_cls}">{escape(v["line"])}</td>')
                    w(f'<td>{escape(v["check"])}</td>')
                    w(f'<td>{escape(v["message"])}</td>')
                    w(f'<td class="rule-category"><span class="severity-{escape(v["severity"])}">{escape(v["severity"])}</span></td>')
                    v_url = v.get("url", "")
                    dl = f'<a href="{escape(v_url)}" target="_blank" class="rule-doc-link"><span class="rule-doc-icon" title="Rule documentation"></span></a>' if v_url else ""
                    w(f'<td class="rule-doc">{dl}</td>')
                    w('</tr>')
                    if has_detail:
                        w(f'<tr class="detailrow{i % 2} hidden"><td colspan="5">')
                        w(f'<div id="{vid}" style="display:none" class="detail-content">')
                        w(f'<pre class="snippet"><code>{snippet}</code></pre>')
                        w('</div>')
                        w('</td></tr>')
                w('</table>')
            w('</details>')

        w('</div>')
    w('</body>')
    w('</html>')

    html_output = "\n".join(lines)
    html_output = minify_html(html_output)
    with open(html_file, "w") as f:
        f.write(html_output)

    print(f"Wrote {html_file}: {total} violations across {len(files)} files", file=sys.stderr)


if __name__ == "__main__":
    main()
