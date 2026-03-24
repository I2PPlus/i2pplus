#!/usr/bin/env python3
"""
Convert PMD XML output to HTML with I2P+ dark theme.
Uses shared template from tools/template/common.py.

Usage: pmd-to-html.py pmd.xml output.html [language] [--local]
"""

import json
import sys
import os
from datetime import datetime, timezone
from xml.etree import ElementTree as ET

# Add project root to path for template import
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))

from tools.template.common import (
    escape, load_css, load_favicon, load_js, minify_html, get_code_snippet,
    snippet_to_text, severity_class,
    html_header, html_meta_line, html_footer, write_report,
)

NS = {"pmd": "http://pmd.sourceforge.net/report/2.0.0"}


def get_subsystem(pkg, fname):
    """Derive I2P+ sub-system name from package or file path."""
    if pkg:
        parts = pkg.split(".")
        if parts[0] == "net" and len(parts) > 1 and parts[1] == "i2p":
            if len(parts) == 2:
                return "Core"
            sub = parts[2]
            return {
                "router": "Router", "data": "Data", "streaming": "Streaming",
                "client": "Client", "crypto": "Crypto", "util": "Utilities",
                "internal": "Core", "i2psnark": "I2PSnark",
                "addressbook": "Addressbook", "router.news": "News",
                "router.time": "Time", "router.startup": "Startup",
                "router.transport": "Transport", "router.tunnel": "Tunnels",
                "router.networkdb": "NetDB", "router.peermanager": "Peer Manager",
                "router.deny": "Sybil", "router.update": "Update",
                "router.web": "Router Console", "client.naming": "Naming",
                "client.streaming": "Client Streaming", "client.impl": "Client Impl",
                "app": "Apps", "desktop": "Desktop", "update": "Updater",
            }.get(sub, sub.capitalize())
        path = fname.replace("\\", "/")
        if "apps/" in path:
            app = path.split("apps/")[1].split("/")[0]
            return _app_subsystem(app)
        return "Third-party"
    if fname:
        path = fname.replace("\\", "/")
        if "apps/" in path:
            app = path.split("apps/")[1].split("/")[0]
            return _app_subsystem(app)
        if "/core/" in path:
            return "Core"
        if "/router/" in path:
            return "Router"
    return "Other"


def _app_subsystem(app):
    return {
        "routerconsole": "Router Console", "i2ptunnel": "I2PTunnel",
        "i2psnark": "I2PSnark", "susidns": "SusiDNS", "susimail": "SusiMail",
        "addressbook": "Addressbook", "jetty": "Jetty", "wrapper": "Wrapper",
    }.get(app, app.replace("-", " ").title())


def main():
    if len(sys.argv) < 3:
        print(f"Usage: {sys.argv[0]} pmd.xml output.html [language] [--local]", file=sys.stderr)
        sys.exit(1)

    xml_file, html_file = sys.argv[1], sys.argv[2]
    lang = ""
    local = "--local" in sys.argv
    for arg in sys.argv[3:]:
        if arg == "--local":
            local = True
        elif not arg.startswith("-"):
            lang = arg

    # Handle truncated XML
    with open(xml_file) as f:
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

    # Collect violations
    files = []
    rule_counts = {}
    rule_violations = {}
    subsystem_counts = {}
    total = 0

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
                "begin": v.get("beginline", "?"), "end": v.get("endline", "?"),
                "rule": rule, "ruleset": v.get("ruleset", ""),
                "priority": v.get("priority", "3"), "class": v.get("class", ""),
                "method": v.get("method", ""), "url": v.get("externalInfoUrl", ""),
                "msg": msg, "subsystem": sub, "file": fname,
            }
            violations.append(vdict)
            rule_violations.setdefault(rule, []).append((fname, vdict))
        if violations:
            files.append((fname, violations))

    files.sort(key=lambda x: x[0])
    sorted_rules = sorted(rule_counts.items(), key=lambda x: -x[1])
    sorted_subs = sorted(subsystem_counts.items(), key=lambda x: -x[1])

    # Per-rule subsystem data
    rule_subs = {}
    rule_sub_counts = {}
    rule_files = {}
    for rule, vlist in rule_violations.items():
        sc = {}
        fset = set()
        for fname, v in vlist:
            sc[v["subsystem"]] = sc.get(v["subsystem"], 0) + 1
            fset.add(fname)
        rule_subs[rule] = sorted(sc.keys(), key=lambda s: -sc[s])
        rule_sub_counts[rule] = sc
        rule_files[rule] = len(fset)

    # Generate HTML
    lines = []
    w = lines.append

    suffix = f" ({lang})" if lang else ""
    title = f"PMD Report{suffix}"
    body_id = f"pmd-{lang.lower().replace('script', 's')}" if lang else "pmd"
    w(html_header(title, f"PMD{suffix}", body_id))
    w(html_meta_line(total, len(files), f"PMD {escape(version)}"))

    if total > 0:
        # Navbar by sub-system
        w('<div id="navbar">')
        for sub, count in sorted_subs:
            w(f'<span><a href="#sub-{escape(sub)}" data-sub="sub-{escape(sub)}">{escape(sub)} <span class="badge">{count}</span></a></span>')
        w('</div>')

        # Group files by sub-system
        sub_files = {}
        for fname, violations in files:
            sub = violations[0]["subsystem"]
            sub_files.setdefault(sub, []).append((fname, violations))

        # Build rule data JSON for interactive filtering
        def norm_path(f):
            return os.path.abspath(f) if local else f
        rule_data_json = json.dumps({rule: [(norm_path(f), {
            "begin": v["begin"], "end": v["end"], "priority": v["priority"],
            "msg": v["msg"], "class": v["class"], "method": v["method"],
            "url": v["url"], "ruleset": v["ruleset"], "subsystem": v["subsystem"],
            "snippet": snippet_to_text(get_code_snippet(f, v["begin"])),
        }) for f, v in vlist] for rule, vlist in rule_violations.items()})

        # Inject PMD-specific data + JS files before closing script tag
        pmd_data = f'var LOCAL_MODE={"true" if local else "false"};\nvar RULE_DATA={rule_data_json};\n'
        pmd_js = pmd_data + load_js("shared.js", "rule-detail.js")
        # Insert PMD JS before </script> in the header
        html_so_far = "\n".join(lines)
        html_so_far = html_so_far.replace("</script>", pmd_js + "</script>")
        lines = [html_so_far]
        w = lines.append

        # Summary by rule
        w('<div class="tabletitle"><a name="rules">By Rule</a></div>')
        w('<table id="summary">')
        w('<tr><th>Rule</th><th>Sub-systems</th><th class="summary-count">Violations</th><th class="summary-count">Files</th></tr>')
        for i, (rule, count) in enumerate(sorted_rules):
            row = "tablerow" + str(i % 2)
            fc = rule_files.get(rule, 0)
            sc = rule_sub_counts.get(rule, {})
            subs_str = ", ".join(f'<a href="#sub-{escape(s)}" data-sub="sub-{escape(s)}">{escape(s)}</a> ({sc[s]})' for s in rule_subs.get(rule, []))
            w(f'<tr class="{row}"><td><a href="#" data-rule="{escape(rule)}">{escape(rule)}</a></td><td class="subs">{subs_str}</td><td class="summary-count">{count}</td><td class="summary-count">{fc}</td></tr>')
        w(f'<tr class="tablerow0"><td><b>Total</b></td><td></td><td class="summary-count"><b>{total}</b></td><td class="summary-count"><b>{len(files)}</b></td></tr>')
        w('</table>')

        # Rule filter container
        w('<div id="rule-filter" style="display:none"></div>')

        # Violations by sub-system
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
                w('<tr><th class="line">Line</th><th>Rule</th><th>Message</th><th class="rule-category">Category</th><th class="rule-doc">Doc</th></tr>')
                for i, v in enumerate(sorted(violations, key=lambda x: int(x["begin"]))):
                    row = "tablerow" + str(i % 2)
                    vid = f"v{abs(hash(fname + v['begin'] + v['rule']))}"
                    rng = v["begin"] if v["begin"] == v["end"] else f'{v["begin"]}-{v["end"]}'
                    doc_link = f'<a href="{escape(v["url"])}" target="_blank" class="rule-doc-link"><span class="rule-doc-icon" title="Rule documentation: {escape(v["rule"])}"></span></a>' if v["url"] else ""
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

        w('</div>')  # violations
    w(html_footer())

    html_output = "\n".join(lines)
    write_report(html_file, html_output)
    print(f"Wrote {html_file}: {total} violations across {len(files)} files", file=sys.stderr)


if __name__ == "__main__":
    main()
