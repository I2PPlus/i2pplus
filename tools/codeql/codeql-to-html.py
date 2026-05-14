#!/usr/bin/env python3
"""
Convert CodeQL SARIF output to HTML report for I2P+.
Usage: codeql-to-html.py [--no-exclude] [--local] <input.sarif> <output.html>
"""
import json
import sys
import os
from collections import defaultdict

# Add project root to path for template import
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))

from tools.template.common import (
    is_excluded, escape, load_css, load_favicon, get_code_snippet,
    severity_class, severity_span, minify_html,
    html_header, html_meta_line, html_navbar, html_footer,
    write_report, SEV_ORDER, CONFIG,
)


def get_doc_url(rule_id):
    """Generate CodeQL documentation URL from rule ID."""
    return f"https://codeql.github.com/codeql-query-help/{rule_id}/"


def main():
    args = sys.argv[1:]
    no_exclude = "--no-exclude" in args
    local = "--local" in args
    args = [a for a in args if not a.startswith("--")]

    if len(args) != 2:
        print(f"Usage: {sys.argv[0]} [--no-exclude] [--local] <input.sarif> <output.html>", file=sys.stderr)
        sys.exit(1)

    sarif_file = args[0]
    html_file = args[1]
    editor = CONFIG.get("editor", "") if local else ""

    with open(sarif_file) as f:
        sarif = json.load(f)

    runs = sarif.get("runs", [])
    if not runs:
        header = html_header("CodeQL Report", "CodeQL", "codeql")
        with open(html_file, "w") as f:
            f.write(header)
            f.write(html_meta_line(0, 0))
            f.write(html_footer())
        print(f"Wrote {html_file}: 0 issues across 0 files", file=sys.stderr)
        return

    # Collect results
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

            if not no_exclude and is_excluded(artifact, rule_id):
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

    sorted_rules = sorted(results_by_rule.items(),
                          key=lambda x: (min(SEV_ORDER.get(r["severity"], 3) for r in x[1]), -len(x[1])))

    # Build HTML
    lines = []
    w = lines.append

    w(html_header("CodeQL Report", "CodeQL", "codeql"))
    w(html_meta_line(total, len(results_by_file), "CodeQL 2.25.0"))

    # Navbar
    w(html_navbar(dict(severity_counts)))

    if total > 0:
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
            sev_class = severity_class(sev)
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
                path_html = f'<span class="path">{escape(file_path)}</span>'
                if local:
                    abs_path = os.path.abspath(file_path)
                    first_line = 0
                    for r in results:
                        if str(r["line"]).isdigit() and int(r["line"]) > 0:
                            first_line = int(r["line"])
                            break
                    loc = f":{first_line}" if first_line else ""
                    link = f"{editor}://file/{abs_path.lstrip('/')}{loc}" if editor else f"file://{abs_path}{loc}"
                    path_html = f'<a href="{escape(link)}" class="file-link">{path_html}</a>'
                w(f'<h3>{path_html} <span class="badge">{len(results)}</span></h3>')
                w('<table class="warningtable">')
                w('<tr><th class="line">Line</th><th>Rule</th><th>Message</th><th class="rule-category">Category</th><th class="rule-doc">Doc</th></tr>')
                for i, r in enumerate(results):
                    row = "tablerow" + str(i % 2)
                    sev_cls = severity_class(r["severity"])
                    rng = str(r["line"]) if r["line"] == r["endLine"] else f'{r["line"]}-{r["endLine"]}'
                    if local and str(r["line"]).isdigit():
                        la = os.path.abspath(file_path)
                        ll = f"{editor}://file/{la.lstrip('/')}" if editor else f"file://{la}"
                        ll += f":{r['line']}"
                        line_link = f'<a href="{escape(ll)}" class="line-link">{escape(rng)}</a>'
                    else:
                        line_link = escape(rng)
                    doc_link = f'<a href="{escape(r["helpUrl"])}" target="_blank" class="rule-doc-link"><span class="rule-doc-icon" title="Rule documentation: {escape(r["ruleId"])}"></span></a>' if r["helpUrl"] else ""
                    snippet = get_code_snippet(r["file"], r["line"])
                    has_detail = bool(snippet)
                    vid = f"v{abs(hash(file_path + str(r['line']) + r['ruleId']))}" if has_detail else ""
                    if has_detail:
                        w(f'<tr class="{row}" data-detail="{vid}">')
                    else:
                        w(f'<tr class="{row}">')
                    w(f'<td class="line priority-cell {sev_cls}">{line_link}</td>')
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

    w(html_footer())
    html_output = "\n".join(lines)
    write_report(html_file, html_output)
    print(f"Wrote {html_file}: {total} issues across {len(results_by_file)} files", file=sys.stderr)


if __name__ == "__main__":
    main()
