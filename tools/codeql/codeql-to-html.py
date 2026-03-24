#!/usr/bin/env python3
"""
Convert CodeQL SARIF output to HTML report for I2P+.
Usage: codeql-to-html.py <input.sarif> <output.html>
"""
import json
import sys
import html
import re
from collections import defaultdict
from datetime import datetime, timezone


def escape(s):
    return html.escape(str(s)) if s else ""


def load_css():
    return """
:root { color-scheme: dark; }
body { font-family: 'JetBrains Mono', 'Fira Code', monospace; font-size: 14px;
       background: #1e1e1e; color: #d4d4d4; margin: 0; padding: 1rem; max-width: 1400px; }
h1 { color: #569cd6; margin: 0 0 .5rem; font-size: 1.4em; }
.meta { color: #808080; margin-bottom: 1rem; }
.tabletitle { background: #264f78; color: #fff; padding: .3rem .5rem; margin: 1rem 0 .2rem; font-size: 1.1em; }
table { border-collapse: collapse; width: 100%; margin-bottom: 1rem; }
th { background: #252526; color: #9cdcfe; text-align: left; padding: .3rem .5rem;
     border: 1px solid #3c3c3c; font-weight: 600; }
td { padding: .3rem .5rem; border: 1px solid #3c3c3c; vertical-align: top; }
.tablerow0 { background: #1e1e1e; } .tablerow1 { background: #252526; }
tr:hover { background: #2a2d2e; }
.badge { background: #264f78; color: #fff; padding: .1rem .4rem; border-radius: 3px; font-size: .85em; }
a { color: #4ec9b0; text-decoration: none; } a:hover { text-decoration: underline; }
.path { color: #ce9178; font-size: .9em; }
.severity-high { color: #f44747; font-weight: bold; }
.severity-medium { color: #dcdcaa; font-weight: bold; }
.severity-low { color: #608b4e; }
pre.snippet { background: #2d2d2d; padding: .5rem; overflow-x: auto; font-size: .85em; border-radius: 3px; }
details { margin: .5rem 0; }
summary { cursor: pointer; color: #569cd6; }
"""


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
        print(f"No runs found in {sarif_file}", file=sys.stderr)
        # Write empty report
        with open(html_file, "w") as f:
            f.write("<!DOCTYPE html><html><head><style>" + load_css() + "</style><title>CodeQL Report</title></head>")
            f.write("<body><h1>I2P+ CodeQL Report</h1><p class='meta'>No issues found.</p></body></html>")
        return

    # Collect all results
    results_by_rule = defaultdict(list)
    results_by_file = defaultdict(list)
    severity_counts = defaultdict(int)
    total = 0

    for run in runs:
        # Build rule lookup
        rules = {}
        for rule in run.get("tool", {}).get("driver", {}).get("rules", []):
            rules[rule["id"]] = rule

        for result in run.get("results", []):
            rule_id = result.get("ruleId", "unknown")
            rule = rules.get(rule_id, {})
            severity = result.get("level", rule.get("defaultConfiguration", {}).get("level", "warning"))
            message = result.get("message", {}).get("text", "")

            # Get location
            loc = result.get("locations", [{}])[0].get("physicalLocation", {})
            artifact = loc.get("artifactLocation", {}).get("uri", "unknown")
            region = loc.get("region", {})
            line = region.get("startLine", "?")
            col = region.get("startColumn", "?")
            end_line = region.get("endLine", line)

            # Get help URL
            help_url = rule.get("helpUri", "")

            # Short message
            short_msg = rule.get("shortDescription", {}).get("text", message[:120])

            result_data = {
                "ruleId": rule_id,
                "severity": severity,
                "message": message,
                "shortMessage": short_msg,
                "file": artifact,
                "line": line,
                "endLine": end_line,
                "col": col,
                "helpUrl": help_url,
                "rule": rule,
            }

            results_by_rule[rule_id].append(result_data)
            results_by_file[artifact].append(result_data)
            severity_counts[severity] += 1
            total += 1

    # Sort rules by severity then count
    sev_order = {"error": 0, "warning": 1, "note": 2}
    sorted_rules = sorted(results_by_rule.items(),
                          key=lambda x: (min(sev_order.get(r["severity"], 3) for r in x[1]), -len(x[1])))

    # Build HTML
    lines = []
    w = lines.append
    now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")

    w("<!DOCTYPE html>")
    w("<html lang='en'><head>")
    w("<meta charset='UTF-8'>")
    w("<meta name='viewport' content='width=device-width, initial-scale=1.0'>")
    w("<title>I2P+ CodeQL Report</title>")
    w("<style>" + load_css() + "</style>")
    w("</head><body>")

    w(f"<h1>I2P+ CodeQL Report</h1>")
    w(f"<p class='meta'>CodeQL &middot; {total} issues &middot; {len(results_by_file)} files &middot; {now}</p>")

    # Severity summary
    w("<table><tr><th>Severity</th><th class='summary-count'>Count</th></tr>")
    for sev in ["error", "warning", "note"]:
        if severity_counts.get(sev, 0) > 0:
            sev_class = f"severity-{sev}" if sev != "note" else "severity-low"
            sev_label = sev.capitalize()
            w(f"<tr class='tablerow0'><td class='{sev_class}'>{sev_label}</td><td class='summary-count'>{severity_counts[sev]}</td></tr>")
    w(f"<tr class='tablerow1'><td><b>Total</b></td><td class='summary-count'><b>{total}</b></td></tr>")
    w("</table>")

    # By rule
    w("<div class='tabletitle'>By Rule</div>")
    w("<table>")
    w("<tr><th>Rule</th><th>Severity</th><th class='summary-count'>Count</th><th>Files</th><th>Doc</th></tr>")
    for i, (rule_id, results) in enumerate(sorted_rules):
        row = "tablerow" + str(i % 2)
        sev = results[0]["severity"]
        sev_class = f"severity-{sev}" if sev != "note" else "severity-low"
        files = len(set(r["file"] for r in results))
        help_url = results[0].get("helpUrl", "")
        doc_link = f'<a href="{escape(help_url)}" target="_blank">doc</a>' if help_url else ""
        w(f"<tr class='{row}'>")
        w(f"<td>{escape(rule_id)}</td>")
        w(f"<td class='{sev_class}'>{escape(sev)}</td>")
        w(f"<td class='summary-count'>{len(results)}</td>")
        w(f"<td class='summary-count'>{files}</td>")
        w(f"<td>{doc_link}</td>")
        w("</tr>")
    w("</table>")

    # By file
    w("<div class='tabletitle'>By File</div>")
    for file_path in sorted(results_by_file.keys()):
        results = sorted(results_by_file[file_path], key=lambda r: int(r["line"]))
        w(f"<details><summary><span class='path'>{escape(file_path)}</span> <span class='badge'>{len(results)}</span></summary>")
        w("<table>")
        w("<tr><th>Line</th><th>Severity</th><th>Rule</th><th>Message</th></tr>")
        for i, r in enumerate(results):
            row = "tablerow" + str(i % 2)
            sev_class = f"severity-{r['severity']}" if r['severity'] != "note" else "severity-low"
            rng = str(r["line"]) if r["line"] == r["endLine"] else f'{r["line"]}-{r["endLine"]}'
            w(f"<tr class='{row}'>")
            w(f"<td>{escape(rng)}</td>")
            w(f"<td class='{sev_class}'>{escape(r['severity'])}</td>")
            w(f"<td>{escape(r['ruleId'])}</td>")
            w(f"<td>{escape(r['shortMessage'])}</td>")
            w("</tr>")
        w("</table></details>")

    w("</body></html>")

    html_output = "\n".join(lines)
    with open(html_file, "w") as f:
        f.write(html_output)

    print(f"Wrote {html_file}: {total} issues across {len(results_by_file)} files", file=sys.stderr)


if __name__ == "__main__":
    main()
