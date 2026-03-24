#!/usr/bin/env python3
"""
Convert CodeQL SARIF output to HTML report for I2P+.
Uses the same dark theme and layout as PMD reports.
Usage: codeql-to-html.py <input.sarif> <output.html>
"""
import json
import sys
import html
import re
import os
from collections import defaultdict
from datetime import datetime, timezone
from urllib.parse import quote


# Paths to exclude (matching PMD ruleset-java.xml)
EXCLUDE_PATTERNS = [
    'org/cybergarage/', 'org/apache/', 'com/mpatric/', 'org/klomp/snark/',
    'org/rrd4j/', 'org/bouncycastle/', 'io/pack200/', 'com/maxmind/',
    'org/minidns/', 'com/southernstorm/noise/', 'org/json/simple/',
    'net/metanotion/', 'apps/imagegen/identicon/', 'apps/imagegen/zxing/',
    'apps/i2psnark/java/src/com/mpatric/', 'apps/i2psnark/java/src/org/klomp/snark/',
    'apps/pack200/java/src/io/pack200/', 'apps/jrobin/java/src/org/rrd4j/',
    'org/freenetproject/', 'freenet/', 'com/southernstorm/',
    'com/tomgibara/', 'com/google/zxing/', 'com/docuverse/',
    'com/thetransactioncompany/', 'edu/internet2/', 'com/vuze/',
    '/test/', '/demo/', '/tmp/', '_jsp.java',
]


def is_excluded(uri):
    return any(p in uri for p in EXCLUDE_PATTERNS)


def get_doc_url(rule_id):
    """Generate CodeQL documentation URL from rule ID."""
    # Rule IDs are like: java/http-response-splitting
    # Doc URLs are like: https://codeql.github.com/codeql-query-help/java/http-response-splitting/
    return f"https://codeql.github.com/codeql-query-help/{rule_id}/"


def escape(s):
    return html.escape(str(s)) if s else ""


def load_favicon():
    try:
        path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "installer", "resources", "themes", "console", "images", "plus.svg")
        with open(path) as f:
            return f"data:image/svg+xml,{quote(f.read())}"
    except Exception:
        return ""


def load_css():
    try:
        with open("tools/pmd/report.css") as f:
            return f.read()
    except Exception:
        return ""


def get_code_snippet(file_path, line_num, context=3):
    """Read a few lines of source code around the violation."""
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
            is_target = n == ln
            if is_target:
                snippet_lines.append(f'<span class="offense"><span class="ln">{n}</span> <span class="codeline">{escape(code)}</span></span>')
            else:
                snippet_lines.append(f'<span class="code-line"><span class="ln">{n}</span> {escape(code)}</span>')
        return "\n".join(snippet_lines)
    except Exception:
        return ""


def main():
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} <input.sarif> <output.html>", file=sys.stderr)
        sys.exit(1)

    sarif_file = sys.argv[1]
    html_file = sys.argv[2]

    with open(sarif_file) as f:
        sarif = json.load(f)

    runs = sarif.get("runs", [])
    if not runs:
        with open(html_file, "w") as f:
            f.write("<!DOCTYPE html><html><head><meta charset='UTF-8'><style>" + load_css() + "</style>")
            favicon = load_favicon()
            if favicon:
                f.write(f'<link rel="icon" type="image/svg+xml" href="{favicon}">')
            f.write("<title>I2P+ | CodeQL Report</title></head><body id='codeql'>")
            f.write("<h1>I2P+ CodeQL Report</h1><p class='meta'>0 issues across 0 files</p></body></html>")
        print(f"Wrote {html_file}: 0 issues across 0 files", file=sys.stderr)
        return

    # Collect results, excluding third-party/test
    results_by_rule = defaultdict(list)
    results_by_file = defaultdict(list)
    severity_counts = defaultdict(int)
    total = 0

    for run in runs:
        rules = {}
        for rule in run.get("tool", {}).get("driver", {}).get("rules", []):
            rules[rule["id"]] = rule

        for result in run.get("results", []):
            rule_id = result.get("ruleId", "unknown")
            rule = rules.get(rule_id, {})
            severity = result.get("level", rule.get("defaultConfiguration", {}).get("level", "warning"))
            message = result.get("message", {}).get("text", "")

            loc = result.get("locations", [{}])[0].get("physicalLocation", {})
            artifact = loc.get("artifactLocation", {}).get("uri", "unknown")
            region = loc.get("region", {})
            line = region.get("startLine", "?")
            end_line = region.get("endLine", line)

            # Skip excluded paths
            if is_excluded(artifact):
                continue

            help_url = get_doc_url(rule_id)
            short_msg = rule.get("shortDescription", {}).get("text", message[:120])
            category = rule_id.split("/")[0] if "/" in rule_id else ""

            result_data = {
                "ruleId": rule_id,
                "severity": severity,
                "message": message,
                "shortMessage": short_msg,
                "file": artifact,
                "line": line,
                "endLine": end_line,
                "helpUrl": help_url,
                "category": category,
                "rule": rule,
            }

            results_by_rule[rule_id].append(result_data)
            results_by_file[artifact].append(result_data)
            severity_counts[severity] += 1
            total += 1

    sev_order = {"error": 0, "warning": 1, "note": 2}
    sorted_rules = sorted(results_by_rule.items(),
                          key=lambda x: (min(sev_order.get(r["severity"], 3) for r in x[1]), -len(x[1])))

    # Build HTML
    lines = []
    w = lines.append
    now = datetime.now(timezone.utc).strftime("%B %d, %Y, %H:%M UTC")

    w('<!DOCTYPE html>')
    w('<html lang="en"><head>')
    w('<meta charset="UTF-8">')
    w('<meta name="viewport" content="width=device-width, initial-scale=1.0">')
    favicon = load_favicon()
    if favicon:
        w(f'<link rel="icon" type="image/svg+xml" href="{favicon}">')
    w('<title>I2P+ | CodeQL Report</title>')
    w('<style>')
    w(load_css())
    w('</style>')
    w('<script>')
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
  var a=e.target.closest("a[data-severity]");
  if(a){e.preventDefault();var tgt=document.getElementById("severity-"+a.dataset.severity);
    if(tgt){document.querySelectorAll("details[open]").forEach(function(o){o.removeAttribute("open")});
      tgt.setAttribute("open","");setTimeout(function(){tgt.scrollIntoView({behavior:"smooth",block:"start"})},50)}
  }
});''')
    w('</script>')
    w('</head><body id="codeql">')

    w(f'<h1>I2P+ CodeQL Report</h1>')
    w(f'<p class="meta">CodeQL 2.25.0 &middot; {total} issues across {len(results_by_file)} files &middot; {escape(now)}</p>')

    # Navbar
    if total > 0:
        w('<div id="navbar">')
        for sev in ["error", "warning", "note"]:
            if severity_counts.get(sev, 0) > 0:
                w(f'<span><a href="#severity-{sev}" data-severity="{sev}">{sev.capitalize()} <span class="badge">{severity_counts[sev]}</span></a></span>')
        w('</div>')

        # Summary by rule
        w('<div class="tabletitle"><a name="rules">By Rule</a></div>')
        w('<table id="summary">')
        w('<tr><th>Rule</th><th class="severity">Severity</th><th class="summary-count">Issues</th><th class="summary-count">Files</th><th class="rule-doc">Doc</th></tr>')
        for i, (rule_id, results) in enumerate(sorted_rules):
            row = "tablerow" + str(i % 2)
            sev = results[0]["severity"]
            files = len(set(r["file"] for r in results))
            help_url = results[0].get("helpUrl", "")
            doc_link = f'<a href="{escape(help_url)}" target="_blank" class="rule-doc-link"><span class="rule-doc-icon" title="Rule documentation: {escape(rule_id)}"></span></a>' if help_url else ""
            sev_class = f'p{sev_order.get(sev, 2)+1}' if sev in sev_order else 'p3'
            w(f'<tr class="{row}">')
            w(f'<td>{escape(rule_id)}</td>')
            w(f'<td class="priority-cell {sev_class}">{escape(sev)}</td>')
            w(f'<td class="summary-count">{len(results)}</td>')
            w(f'<td class="summary-count">{files}</td>')
            w(f'<td class="rule-doc">{doc_link}</td>')
            w('</tr>')
        w(f'<tr class="tablerow0"><td><b>Total</b></td><td></td><td class="summary-count"><b>{total}</b></td><td class="summary-count"><b>{len(results_by_file)}</b></td><td></td></tr>')
        w('</table>')

        # Issues by severity
        w('<div class="tabletitle"><a name="violations">Issues</a></div>')
        for sev in ["error", "warning", "note"]:
            sev_files = {f: r for f, r in results_by_file.items()
                         if any(x["severity"] == sev for x in r)}
            if not sev_files:
                continue
            sev_count = sum(len(r) for r in sev_files.values())
            w(f'<details id="severity-{sev}" {"open" if sev == "error" else ""}>')
            w(f'<summary><div class="tabletitle"><a name="severity-{sev}">{sev.capitalize()}</a>&#32;<span class="badge">{sev_count} / {len(sev_files)}</span></div></summary>')
            for file_path in sorted(sev_files.keys()):
                results = sorted(sev_files[file_path], key=lambda r: int(r["line"]) if str(r["line"]).isdigit() else 0)
                w(f'<h3><span class="path">{escape(file_path)}</span> <span class="badge">{len(results)}</span></h3>')
                w('<table class="warningtable">')
                w('<tr><th class="line">Line</th><th>Rule</th><th>Message</th><th class="rule-category">Category</th><th class="rule-doc">Doc</th></tr>')
                for i, r in enumerate(results):
                    row = "tablerow" + str(i % 2)
                    sev_class = f'p{sev_order.get(r["severity"], 2)+1}'
                    rng = str(r["line"]) if r["line"] == r["endLine"] else f'{r["line"]}-{r["endLine"]}'
                    doc_link = f'<a href="{escape(r["helpUrl"])}" target="_blank" class="rule-doc-link"><span class="rule-doc-icon" title="Rule documentation: {escape(r["ruleId"])}"></span></a>' if r["helpUrl"] else ""
                    snippet = get_code_snippet(r["file"], r["line"])
                    has_detail = bool(snippet)
                    vid = f"v{abs(hash(file_path + str(r['line']) + r['ruleId']))}" if has_detail else ""
                    if has_detail:
                        w(f'<tr class="{row}" data-detail="{vid}">')
                    else:
                        w(f'<tr class="{row}">')
                    w(f'<td class="line priority-cell {sev_class}">{escape(rng)}</td>')
                    w(f'<td>{escape(r["ruleId"])}</td>')
                    w(f'<td>{escape(r["shortMessage"])}</td>')
                    w(f'<td class="rule-category">{escape(r["category"])}</td>')
                    w(f'<td class="rule-doc">{doc_link}</td>')
                    w('</tr>')
                    if has_detail:
                        w(f'<tr class="detailrow{i % 2} hidden"><td colspan="5">')
                        w(f'<div id="{vid}" style="display:none" class="detail-content">')
                        w(f'<pre class="snippet"><code>{snippet}</code></pre>')
                        w('</div>')
                        w('</td></tr>')
                w('</table>')
            w('</details>')

    w('</body></html>')

    html_output = "\n".join(lines)
    html_output = minify_html(html_output)
    with open(html_file, "w") as f:
        f.write(html_output)

    print(f"Wrote {html_file}: {total} issues across {len(results_by_file)} files", file=sys.stderr)


def minify_html(html_str):
    html_str = re.sub(r'<!--.*?-->', '', html_str, flags=re.DOTALL)
    html_str = re.sub(r'\s+', ' ', html_str)
    html_str = re.sub(r'>\s+<', '><', html_str)
    return html_str.strip()


if __name__ == "__main__":
    main()
