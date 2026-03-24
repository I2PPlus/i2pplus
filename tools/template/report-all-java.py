#!/usr/bin/env python3
"""
Combine PMD, Checkstyle, CodeQL, and SpotBugs results into a single HTML report.
Uses shared template from tools/template/common.py.

Usage: report-all-java.py [--pmd dist/pmd-java.xml] [--checkstyle dist/checkstyle.xml]
                          [--codeql dist/codeql-java.sarif] [--spotbugs dist/spotbugs.xml]
                          -o dist/report-all-java.html
"""

import json
import sys
import os
import argparse
from xml.etree import ElementTree as ET
from collections import defaultdict

# Add project root to path for template import
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))

from tools.template.common import (
    escape, load_css, load_favicon, get_code_snippet,
    severity_class, minify_html,
    html_header, html_meta_line, html_footer,
    write_report, EXCLUDE_PATTERNS, is_excluded,
    build_source_index, resolve_source_path,
)


def get_subsystem(file_path):
    """Derive subsystem from source file path."""
    if not file_path:
        return "Other"
    path = file_path.replace("\\", "/")
    if "apps/" in path:
        app = path.split("apps/")[1].split("/")[0]
        return {
            "routerconsole": "Router Console", "i2ptunnel": "I2PTunnel",
            "i2psnark": "I2PSnark", "susidns": "SusiDNS",
            "susimail": "SusiMail", "addressbook": "Addressbook",
            "jetty": "Jetty", "wrapper": "Wrapper",
            "i2pcontrol": "I2PControl", "imagegen": "ImageGen",
            "sam": "SAM", "streaming": "Streaming",
        }.get(app, app.replace("-", " ").title())
    if "/core/" in path:
        return "Core"
    if "/router/" in path:
        return "Router"
    # Extract package from path: find src/ directory
    for marker in ["/src/", "/java/src/"]:
        idx = path.rfind(marker)
        if idx > 0:
            pkg_path = path[idx + len(marker):]
            parts = pkg_path.replace("/", ".").rsplit(".", 1)[0].split(".")
            if len(parts) >= 3 and parts[0] == "net" and parts[1] == "i2p":
                sub = parts[2]
                return {
                    "router": "Router", "data": "Data", "streaming": "Streaming",
                    "client": "Client", "crypto": "Crypto", "util": "Utilities",
                    "internal": "Core",
                }.get(sub, sub.capitalize())
            break
    return "Third-party"


# ── Parsers: each returns list of {file, line, rule, message, severity, source, url, category} ──

def parse_pmd(xml_file):
    """Parse PMD XML output."""
    ns = {"pmd": "http://pmd.sourceforge.net/report/2.0.0"}
    with open(xml_file) as f:
        content = f.read()
    if not content.rstrip().endswith("</pmd>"):
        content = content.rstrip() + "\n</pmd>\n"
    root = ET.fromstring(content)
    results = []
    for fnode in root.findall("pmd:file", ns):
        fname = fnode.attrib["name"]
        if is_excluded(fname):
            continue
        for vnode in fnode.findall("pmd:violation", ns):
            v = vnode.attrib
            msg = (vnode.text or "").strip()
            results.append({
                "file": fname,
                "line": int(v.get("beginline", 0) or 0),
                "rule": v.get("rule", "?"),
                "message": msg,
                "severity": f'p{v.get("priority", "3")}',
                "source": "PMD",
                "url": v.get("externalInfoUrl", ""),
                "category": v.get("ruleset", ""),
            })
    return results


def parse_checkstyle(xml_file):
    """Parse Checkstyle XML output."""
    with open(xml_file) as f:
        content = f.read()
    if not content.rstrip().endswith("</checkstyle>"):
        last_tag = -1
        for tag in ("</error>", "</file>"):
            pos = content.rfind(tag)
            if pos > last_tag:
                last_tag = pos + len(tag)
        if last_tag > 0:
            content = content[:last_tag]
        if "</file>" not in content[content.rfind("<file") if "<file" in content else 0:]:
            content += "\n</file>"
        content += "\n</checkstyle>\n"
    try:
        root = ET.fromstring(content)
    except ET.ParseError:
        import re
        files = re.findall(r'<file\b[^>]*>.*?</file>', content, re.DOTALL)
        content = '<?xml version="1.0" encoding="UTF-8"?>\n<checkstyle>\n' + "\n".join(files) + "\n</checkstyle>\n"
        root = ET.fromstring(content)

    results = []
    for fnode in root.findall("file"):
        fname = fnode.attrib["name"]
        if is_excluded(fname):
            continue
        for enode in fnode.findall("error"):
            a = enode.attrib
            source = a.get("source", "")
            check = source.split(".")[-1]
            if check.endswith("Check"):
                check = check[:-5]
            sev = a.get("severity", "warning")
            sev_cls = "p1" if sev == "error" else "p2"
            # Extract category from package path
            parts = source.split(".")
            category = ""
            for i, p in enumerate(parts):
                if p == "checks" and i + 1 < len(parts):
                    category = parts[i + 1]
                    break
            url = f"https://checkstyle.org/checks/{category}/{check.lower()}.html" if category else ""
            results.append({
                "file": fname,
                "line": int(a.get("line", 0) or 0),
                "rule": check,
                "message": a.get("message", ""),
                "severity": sev_cls,
                "source": "Checkstyle",
                "url": url,
                "category": category,
            })
    return results


def parse_codeql(sarif_file):
    """Parse CodeQL SARIF output."""
    with open(sarif_file) as f:
        sarif = json.load(f)

    results = []
    sev_map = {"error": "p1", "warning": "p2", "note": "p3"}
    for run in sarif.get("runs", []):
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
            line = region.get("startLine", 0)

            if is_excluded(artifact):
                continue

            short_msg = rule.get("shortDescription", {}).get("text", message[:120])
            category = rule_id.split("/")[0] if "/" in rule_id else ""
            url = f"https://codeql.github.com/codeql-query-help/{rule_id}/"

            results.append({
                "file": artifact,
                "line": int(line) if line else 0,
                "rule": rule_id,
                "message": short_msg,
                "severity": sev_map.get(severity, "p3"),
                "source": "CodeQL",
                "url": url,
                "category": category,
            })
    return results


def parse_spotbugs(xml_file):
    """Parse SpotBugs XML output."""
    # SpotBugs 4.8.x doesn't emit messages in XML — fall back to type descriptions
    _sb_messages = {
        "EI_EXPOSE_REP": "Exposes internal representation by returning mutable object reference",
        "EI_EXPOSE_REP2": "Stores mutable object reference from parameter directly",
        "CT_CONSTRUCTOR_THROW": "Constructor throws exception leaving object partially initialized",
        "DE_MIGHT_IGNORE": "Exception might be ignored in catch block without logging",
        "RV_RETURN_VALUE_IGNORED_BAD_PRACTICE": "Return value of method is ignored",
        "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE": "Redundant nullcheck of known non-null value",
        "DLS_DEAD_LOCAL_STORE": "Dead store to local variable that is never read afterward",
        "SE_BAD_FIELD": "Non-transient non-serializable field in Serializable class",
        "IS2_INCONSISTENT_SYNC": "Inconsistent synchronization of field",
        "VA_FORMAT_STRING_USES_NEWLINE": "Format string should use %n instead of \\n",
        "REC_CATCH_EXCEPTION": "Exception caught and Exception class is also caught",
        "SF_SWITCH_NO_DEFAULT": "Switch statement found without default case",
        "DM_DEFAULT_ENCODING": "Reliance on default encoding",
        "NP_NULL_ON_SOME_PATH": "Possible null pointer dereference on known path",
        "MS_MUTABLE_ARRAY": "Field is array which can be mutated",
        "EQ_DOESNT_OVERRIDE_EQUALS": "Class doesn't override equals() in superclass",
        "SWL_SLEEP_WITH_LOCK_HELD": "Sleep while holding lock causes performance issues",
        "SIC_INNER_SHOULD_BE_STATIC": "Should be a static inner class",
        "SE_COMPARATOR_SHOULD_BE_SERIALIZABLE": "Comparator doesn't implement Serializable",
        "SE_NO_SERIALVERSIONUID": "Class is Serializable but doesn't define serialVersionUID",
        "OS_OPEN_STREAM": "Method may fail to close stream",
        "OS_OPEN_STREAM_EXCEPTION_PATH": "Method may fail to close stream on exception path",
        "HE_EQUALS_USE_HASHCODE": "Class defines equals() but not hashCode()",
        "HE_HASHCODE_NO_EQUALS": "Class defines hashCode() but not equals()",
        "URF_UNREAD_FIELD": "Unread field",
        "UUF_UNUSED_FIELD": "Unused field",
        "MS_SHOULD_BE_FINAL": "Field isn't final but should be",
        "MS_PKGPROTECT": "Field should be package-protected",
        "DM_NUMBER_CTOR": "Method invokes inefficient Number constructor",
        "DM_STRING_CTOR": "Method invokes inefficient new String(String) constructor",
        "RV_RETURN_VALUE_IGNORED": "Return value of method is ignored",
    }
    with open(xml_file) as f:
        content = f.read()
    root = ET.fromstring(content)

    # Build priority → severity class map
    pri_map = {"1": "p1", "2": "p2", "3": "p3"}

    results = []
    for bug in root.findall("BugInstance"):
        pri = bug.attrib.get("priority", "3")
        type_name = bug.attrib.get("type", "?")
        category = bug.attrib.get("category", "")
        message_elem = bug.find("LongMessage")
        if message_elem is None:
            message_elem = bug.find("ShortMessage")
        msg = (message_elem.text or "").strip() if message_elem is not None else _sb_messages.get(type_name, type_name.replace("_", " ").title())

        # Find source location
        src = bug.find("SourceLine")
        if src is not None:
            sourcepath = src.attrib.get("sourcepath", "?")
            if is_excluded(sourcepath):
                continue
            fname = resolve_source_path(sourcepath)
            start = int(src.attrib.get("start", 0) or 0)
        else:
            fname = "?"
            start = 0

        url = f"https://spotbugs.readthedocs.io/en/latest/bugDescriptions.html#{type_name.lower()}"

        results.append({
            "file": fname,
            "line": start,
            "rule": type_name,
            "message": msg[:150],
            "severity": pri_map.get(pri, "p3"),
            "source": "SpotBugs",
            "url": url,
            "category": category,
        })
    return results


def main():
    parser = argparse.ArgumentParser(description="Combine analysis results into a single HTML report")
    parser.add_argument("--pmd", help="PMD XML file")
    parser.add_argument("--checkstyle", help="Checkstyle XML file")
    parser.add_argument("--codeql", help="CodeQL SARIF file")
    parser.add_argument("--spotbugs", help="SpotBugs XML file")
    parser.add_argument("-o", "--output", required=True, help="Output HTML file")
    args = parser.parse_args()

    all_results = []

    if args.pmd and os.path.exists(args.pmd):
        all_results.extend(parse_pmd(args.pmd))
        print(f"  PMD: {len(all_results)} issues", file=sys.stderr)

    pre = len(all_results)
    if args.checkstyle and os.path.exists(args.checkstyle):
        all_results.extend(parse_checkstyle(args.checkstyle))
        print(f"  Checkstyle: {len(all_results) - pre} issues", file=sys.stderr)

    pre = len(all_results)
    if args.codeql and os.path.exists(args.codeql):
        all_results.extend(parse_codeql(args.codeql))
        print(f"  CodeQL: {len(all_results) - pre} issues", file=sys.stderr)

    pre = len(all_results)
    if args.spotbugs and os.path.exists(args.spotbugs):
        all_results.extend(parse_spotbugs(args.spotbugs))
        print(f"  SpotBugs: {len(all_results) - pre} issues", file=sys.stderr)

    # Sort by file then line
    all_results.sort(key=lambda r: (r["file"], r["line"]))

    # Derive sub-system for each result
    for r in all_results:
        r["subsystem"] = get_subsystem(r["file"])

    # Count by source
    source_counts = defaultdict(int)
    for r in all_results:
        source_counts[r["source"]] += 1
    source_order = ["PMD", "Checkstyle", "CodeQL", "SpotBugs"]

    # Count by sub-system
    subsystem_counts = defaultdict(int)
    for r in all_results:
        subsystem_counts[r["subsystem"]] += 1
    subsystem_order = sorted(subsystem_counts.keys(), key=lambda s: -subsystem_counts[s])

    # Count by file
    file_counts = defaultdict(int)
    for r in all_results:
        file_counts[r["file"]] += 1

    total = len(all_results)

    # Generate HTML
    lines = []
    w = lines.append

    w(html_header("Java Analysis Report", "Combined", "report-all", js_files=["shared.js", "source-filter.js"]))
    w(html_meta_line(total, len(file_counts),
                     " · ".join(f"{s}: {source_counts[s]}" for s in source_order if s in source_counts)))

    if total > 0:
        # Navbar by sub-system
        w('<div id="navbar">')
        for sub in subsystem_order:
            if sub not in subsystem_counts:
                continue
            w(f'<span><a href="#sub-{escape(sub)}" data-sub="sub-{escape(sub)}">{escape(sub)} <span class="badge">{subsystem_counts[sub]}</span></a></span>')
        w('</div>')

        # Summary by source (clickable to filter)
        w('<div class="tabletitle"><a name="summary">By Source</a></div>')
        w('<table id="summary">')
        w('<tr><th>Source</th><th class="summary-count">Issues</th><th class="summary-count">Files</th></tr>')
        for src in source_order:
            count = source_counts.get(src, 0)
            if count == 0:
                continue
            src_files = len(set(r["file"] for r in all_results if r["source"] == src))
            w(f'<tr class="tablerow0 src-filter-row" data-source="{src.lower()}" title="Click to filter by {src}"><td><span class="src-badge {src.lower()}">{src}</span></td><td class="summary-count">{count}</td><td class="summary-count">{src_files}</td></tr>')
        w(f'<tr class="tablerow1"><td><b>Total</b></td><td class="summary-count"><b>{total}</b></td><td class="summary-count"><b>{len(file_counts)}</b></td></tr>')
        w('</table>')

        # Issues grouped by sub-system
        w('<div id="violations">')
        w('<div class="tabletitle"><a name="violations">All Issues</a></div>')

        for sub in subsystem_order:
            sub_results = [r for r in all_results if r["subsystem"] == sub]
            if not sub_results:
                continue
            nfiles = len(set(r["file"] for r in sub_results))
            w(f'<details id="sub-{escape(sub)}">')
            w(f'<summary><div class="tabletitle"><a name="sub-{escape(sub)}">{escape(sub)}</a>&#32;<span class="badge">{len(sub_results)} / {nfiles}</span></div></summary>')

            current_file = None
            file_results = []
            row_idx = 0

            for r in sub_results + [None]:
                if r is None or r["file"] != current_file:
                    if current_file and file_results:
                        w(f'<h3><span class="path">{escape(current_file)}</span> <span class="badge">{len(file_results)}</span></h3>')
                        w('<table class="warningtable">')
                        w('<tr><th class="line">Line</th><th>Source</th><th>Rule</th><th>Message</th><th class="rule-category">Category</th><th class="rule-doc">Doc</th></tr>')
                        for fr in file_results:
                            row = "tablerow" + str(row_idx % 2)
                            rng = str(fr["line"]) if fr["line"] else ""
                            doc_link = f'<a href="{escape(fr["url"])}" target="_blank" class="rule-doc-link"><span class="rule-doc-icon" title="Rule documentation: {escape(fr["rule"])}"></span></a>' if fr["url"] else ""
                            snippet = get_code_snippet(fr["file"], fr["line"]) if fr["line"] else ""
                            has_detail = bool(snippet)
                            vid = f"v{abs(hash(fr['file'] + str(fr['line']) + fr['rule'] + fr['source']))}" if has_detail else ""
                            if has_detail:
                                w(f'<tr class="{row}" data-detail="{vid}" data-source="{fr["source"].lower()}">')
                            else:
                                w(f'<tr class="{row}" data-source="{fr["source"].lower()}">')
                            w(f'<td class="line priority-cell {fr["severity"]}">{escape(rng)}</td>')
                            w(f'<td><span class="src-badge {fr["source"].lower()}">{escape(fr["source"])}</span></td>')
                            w(f'<td>{escape(fr["rule"])}</td>')
                            w(f'<td>{escape(fr["message"])}</td>')
                            w(f'<td class="rule-category">{escape(fr["category"])}</td>')
                            w(f'<td class="rule-doc">{doc_link}</td>')
                            w('</tr>')
                            if has_detail:
                                w(f'<tr class="detailrow{row_idx % 2} hidden" data-source="{fr["source"].lower()}"><td colspan="6">')
                                w(f'<div id="{vid}" style="display:none" class="detail-content">')
                                w(f'<pre class="snippet"><code>{snippet}</code></pre>')
                                w('</div>')
                                w('</td></tr>')
                            row_idx += 1
                        w('</table>')
                    if r is None:
                        break
                    current_file = r["file"]
                    file_results = []
                file_results.append(r)

            w('</details>')

        w('</div>')

    # Inject source badge CSS
    source_css = """
.src-badge,.pmd,.checkstyle,.codeql,.spotbugs{display:inline-block;padding:1px 8px;font-size:8pt;font-weight:700;border-radius:3px;color:#fff;text-shadow:1px 1px 1px #000}
.src-badge.pmd,.pmd{background:#6040a0}.src-badge.checkstyle,.checkstyle{background:#206080}.src-badge.codeql,.codeql{background:#a04020}.src-badge.spotbugs,.spotbugs{background:#208040}
.src-filter-row{cursor:pointer}.src-filter-row:hover{background:#2a2a40!important}
.src-filter-row.active td:first-child{box-shadow:inset 3px 0 0 #e88830}
tr.source-hidden{display:none}
"""

    html_so_far = "\n".join(lines)
    html_so_far = html_so_far.replace("</style>", source_css + "</style>")
    lines = [html_so_far]
    w = lines.append
    w(html_footer())

    html_output = "\n".join(lines)
    write_report(args.output, html_output)
    print(f"Wrote {args.output}: {total} issues across {len(file_counts)} files", file=sys.stderr)


if __name__ == "__main__":
    main()
