#!/usr/bin/env python3
"""
Convert SpotBugs XML output to HTML with I2P+ dark theme.
Uses shared template from tools/template/common.py.

Usage: spotbugs-to-html.py spotbugs.xml output.html [--local]
"""

import sys
import os
import json
from xml.etree import ElementTree as ET
from collections import defaultdict

# Add tools/template to path for shared resources
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "template"))
from common import (
    escape, load_css, load_favicon, load_js, minify_html, get_code_snippet,
    snippet_to_text, is_excluded, resolve_source_path,
)

CATEGORY_MAP = {
    "CORRECTNESS": "Correctness",
    "BAD_PRACTICE": "Bad Practice",
    "PERFORMANCE": "Performance",
    "MALICIOUS_CODE": "Security",
    "MT_CORRECTNESS": "Concurrency",
    "I18N": "I18N",
    "EXPERIMENTAL": "Experimental",
    "SECURITY": "Security",
    "STYLE": "Style",
    "NOISE": "Noise",
}

BUG_MESSAGES = {
    "EI_EXPOSE_REP": "Exposes internal representation by returning mutable object reference",
    "EI_EXPOSE_REP2": "Stores mutable object reference from parameter directly",
    "CT_CONSTRUCTOR_THROW": "Constructor throws exception leaving object partially initialized",
    "DE_MIGHT_IGNORE": "Exception might be ignored in catch block without logging",
    "RV_RETURN_VALUE_IGNORED_BAD_PRACTICE": "Return value of method is ignored",
    "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE": "Redundant nullcheck of known non-null value",
    "DLS_DEAD_LOCAL_STORE": "Dead store to local variable that is never read afterward",
    "SE_BAD_FIELD": "Non-transient, non-serializable field in Serializable class",
    "IS2_INCONSISTENT_SYNC": "Inconsistent synchronization of field",
    "VA_FORMAT_STRING_USES_NEWLINE": "Format string should use %n instead of \\n",
    "REC_CATCH_EXCEPTION": "Exception caught and Exception class is also caught",
    "PA_PUBLIC_PRIMITIVE_ATTRIBUTE": "Public primitive attribute",
    "SF_SWITCH_NO_DEFAULT": "Switch statement found without default case",
    "DM_DEFAULT_ENCODING": "Reliance on default encoding",
    "RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE": "Redundant nullcheck of value known to be non-null",
    "EQ_GETCLASS_AND_CLASS_CONSTANT": "Equals method compares class names rather than getClass()",
    "NP_NULL_ON_SOME_PATH": "Possible null pointer dereference on known path",
    "MS_MUTABLE_ARRAY": "Field is array which can be mutated",
    "EQ_DOESNT_OVERRIDE_EQUALS": "Class doesn't override equals() in superclass",
    "SWL_SLEEP_WITH_LOCK_HELD": "Sleep while holding lock causes performance issues",
    "MS_PKGPROTECT": "Field should be package-protected",
    "MS_SHOULD_BE_FINAL": "Field isn't final but should be",
    "MS_CANNOT_BE_FINAL": "Field can't be final",
    "NM_SAME_SIMPLE_NAME_AS_SUPERCLASS": "Class name shadows simple name of superclass",
    "NM_SAME_SIMPLE_NAME_AS_INTERFACE": "Class name shadows simple name of implemented interface",
    "SE_COMPARATOR_SHOULD_BE_SERIALIZABLE": "Comparator doesn't implement Serializable",
    "SIC_INNER_SHOULD_BE_STATIC": "Should be a static inner class",
    "UWF_NULL_FIELD": "Field written to but may remain null",
    "NP_LOAD_OF_KNOWN_NULL_VALUE": "Load of known null value",
    "RV_RETURN_VALUE_IGNORED": "Return value of method is ignored",
    "DM_NUMBER_CTOR": "Method invokes inefficient Number constructor",
    "DM_STRING_CTOR": "Method invokes inefficient new String(String) constructor",
    "SBSC_USE_STRINGBUFFER_CONCATENATION": "Method concatenates strings using + in a loop",
    "WMI_WRONG_MAP_ITERATOR": "Inefficient use of keySet iterator then get on Map",
    "BC_UNCONFIRMED_CAST": "Unchecked/unconfirmed cast",
    "BC_VACUOUS_INSTANCEOF": "Instanceof will always return true",
    "ICAST_IDIV_CAST_TO_DOUBLE": "Integral division result cast to double",
    "BX_BOXING_IMMEDIATELY_UNBOXED": "Primitive value is boxed then immediately unboxed",
    "HE_EQUALS_USE_HASHCODE": "Class defines equals() but not hashCode()",
    "HE_HASHCODE_NO_EQUALS": "Class defines hashCode() but not equals()",
    "SE_NO_SERIALVERSIONUID": "Class is Serializable but doesn't define serialVersionUID",
    "SE_READ_RESOLVE_MUST_RETURN_OBJECT": "readResolve method must return Object",
    "CN_IDIOM": "Class implements Cloneable but does not define clone()",
    "CN_IDIOM_NO_SUPER_CALL": "clone() method doesn't call super.clone()",
    "FI_PUBLIC_SHOULD_BE_PROTECTED": "Finalizer should be protected not public",
    "FI_EMPTY": "Finalizer does nothing except call super.finalize()",
    "FI_EXPLICIT_INVOCATION": "Finalizer explicitly invoked",
    "RV_DONT_JUST_NULL_CHECK_READLINE": "Method discards return value of readLine()",
    "OS_OPEN_STREAM": "Method may fail to close stream",
    "OS_OPEN_STREAM_EXCEPTION_PATH": "Method may fail to close stream on exception path",
    "SR_NOT_CHECKED": "Method ignores return value of read()",
    "DMI_RANDOM_NEXTINT_VIA_NEXTDOUBLE": "Use ofnextInt() via nextDouble() is inefficient",
    "SS_SHOULD_BE_STATIC": "Unread field: should this field be static?",
    "URF_UNREAD_FIELD": "Unread field",
    "UUF_UNUSED_FIELD": "Unused field",
    "UWF_UNWRITTEN_FIELD": "Unwritten field",
    "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE": "Non-constant string passed to execute",
}


def get_subsystem(class_name, source_file):
    """Derive subsystem from class package or source file path."""
    if class_name:
        pkg = class_name.rsplit(".", 1)[0] if "." in class_name else ""
        parts = pkg.split(".")
        if parts[0] == "net" and len(parts) > 1 and parts[1] == "i2p":
            if len(parts) == 2:
                return "Core"
            sub = parts[2]
            return {
                "router": "Router", "data": "Data", "streaming": "Streaming",
                "client": "Client", "crypto": "Crypto", "util": "Utilities",
                "internal": "Core", "addressbook": "Addressbook",
                "router.news": "News", "router.time": "Time",
                "router.startup": "Startup", "router.transport": "Transport",
                "router.tunnel": "Tunnels", "router.networkdb": "NetDB",
                "router.peermanager": "Peer Manager", "router.deny": "Sybil",
                "router.update": "Update", "router.web": "Router Console",
                "client.naming": "Naming", "client.streaming": "Client Streaming",
                "client.impl": "Client Impl",
            }.get(sub, sub.capitalize())

    if source_file:
        path = source_file.replace("\\", "/")
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
    return "Other"


def main():
    if len(sys.argv) < 3:
        print(f"Usage: {sys.argv[0]} spotbugs.xml output.html [--local]", file=sys.stderr)
        sys.exit(1)

    xml_file, html_file = sys.argv[1], sys.argv[2]
    local = "--local" in sys.argv
    project_root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

    try:
        tree = ET.parse(xml_file)
        root = tree.getroot()
    except (ET.ParseError, FileNotFoundError) as e:
        # SpotBugs may produce empty/corrupt XML if it crashes mid-analysis
        print(f"Warning: Could not parse {xml_file}: {e}", file=sys.stderr)
        with open(html_file, "w", encoding="utf-8") as f:
            f.write("<html><body><h1>SpotBugs: no output (analysis failed)</h1></body></html>")
        sys.exit(0)

    version = root.attrib.get("version", "?")
    ts_ms = root.attrib.get("timestamp", "")
    try:
        from datetime import datetime, timezone
        timestamp = datetime.fromtimestamp(int(ts_ms) / 1000, tz=timezone.utc).strftime("%B %-d %Y, %H:%M UTC")
    except (ValueError, TypeError, OSError):
        timestamp = ts_ms

    summary = root.find("FindBugsSummary")
    total = int(summary.attrib.get("total_bugs", "0")) if summary is not None else 0

    # Collect bugs
    files = []
    rule_counts = {}
    rule_violations = {}
    subsystem_counts = {}
    file_map = defaultdict(list)

    for bug in root.findall("BugInstance"):
        bug_type = bug.attrib.get("type", "?")
        priority = bug.attrib.get("priority", "2")
        category = bug.attrib.get("category", "CORRECTNESS")

        # Message - SpotBugs 4.8.x doesn't emit messages in XML, generate from bug type
        msg_elem = bug.find("LongMessage") or bug.find("ShortMessage")
        message_text = (msg_elem.text or "").strip() if msg_elem is not None else BUG_MESSAGES.get(bug_type, bug_type.replace("_", " ").title())

        # Source location - SpotBugs uses 'start'/'end' attributes
        src_line = bug.find("SourceLine")
        source_file = ""
        start_line = "?"
        end_line = "?"
        if src_line is not None:
            sourcepath = src_line.attrib.get("sourcepath", "") or src_line.attrib.get("sourcefile", "")
            source_file = resolve_source_path(sourcepath)
            start_line = src_line.attrib.get("startLine") or src_line.attrib.get("start", "?")
            end_line = src_line.attrib.get("endLine") or src_line.attrib.get("end", start_line)

        # Class and method
        class_elem = bug.find("Class")
        class_name = class_elem.attrib.get("classname", "") if class_elem is not None else ""
        method_elem = bug.find("Method")
        method_name = method_elem.attrib.get("name", "") if method_elem is not None else ""

        url = f"https://spotbugs.readthedocs.io/en/latest/bugDescriptions.html#{bug_type.lower().replace('_', '-')}"

        sub = get_subsystem(class_name, source_file)
        cat_display = CATEGORY_MAP.get(category, category.replace("_", " ").title())

        rule_counts[bug_type] = rule_counts.get(bug_type, 0) + 1
        subsystem_counts[sub] = subsystem_counts.get(sub, 0) + 1

        vdict = {
            "begin": start_line, "end": end_line, "rule": bug_type,
            "category": cat_display, "priority": priority,
            "class": class_name, "method": method_name,
            "url": url, "msg": message_text, "subsystem": sub,
            "file": source_file,
        }

        rule_violations.setdefault(bug_type, []).append((source_file, vdict))
        file_map[source_file].append(vdict)

    files = sorted(file_map.items())

    # Summaries
    sorted_rules = sorted(rule_counts.items(), key=lambda x: -x[1])
    sorted_subs = sorted(subsystem_counts.items(), key=lambda x: -x[1])

    # Per-rule subsystem breakdown
    rule_subs = {}
    rule_sub_counts = {}
    rule_files = {}
    for rule, vlist in rule_violations.items():
        sub_count = {}
        fset = set()
        for fname, v in vlist:
            sub_count[v["subsystem"]] = sub_count.get(v["subsystem"], 0) + 1
            fset.add(fname)
        rule_subs[rule] = sorted(sub_count.keys(), key=lambda s: -sub_count[s])
        rule_sub_counts[rule] = sub_count
        rule_files[rule] = len(fset)

    # Generate HTML using shared template
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
    w('<title>I2P+ | SpotBugs Report</title>')
    w('<style>')
    w(load_css())
    w('</style>')
    w('<script>')

    def norm_path(f):
        return os.path.abspath(f) if local else f

    w(f'var LOCAL_MODE={"true" if local else "false"};')
    rule_data_json = json.dumps({rule: [(norm_path(f), {"begin": v["begin"], "end": v["end"], "priority": v["priority"],
        "msg": v["msg"], "class": v["class"], "method": v["method"], "url": v["url"],
        "category": v["category"], "subsystem": v["subsystem"],
        "snippet": snippet_to_text(get_code_snippet(f, v["begin"]))}) for f, v in vlist]
        for rule, vlist in rule_violations.items()})
    w(f'var RULE_DATA={rule_data_json};')
    w(load_js("shared.js", "rule-detail.js"))
    w('</script>')
    w('</head>')
    w('<body id="spotbugs">')

    w(f'<h1>I2P+ SpotBugs Report</h1>')
    w(f'<p class="meta">SpotBugs {escape(version)} &middot; {total} bugs across {len(files)} files &middot; {escape(timestamp)}</p>')

    if total > 0:
        # Navbar
        w('<div id="navbar">')
        for sub, count in sorted_subs:
            w(f'<span><a href="#sub-{escape(sub)}" data-sub="sub-{escape(sub)}">{escape(sub)} <span class="badge">{count}</span></a></span>')
        w('</div>')

        sub_files = {}
        for fname, violations in files:
            sub = violations[0]["subsystem"]
            sub_files.setdefault(sub, []).append((fname, violations))

        # By Bug Type
        w('<div class="tabletitle"><a name="rules">By Bug Type</a></div>')
        w('<table id="summary">')
        w('<tr><th>Bug Type</th><th>Sub-systems</th><th class="summary-count">Bugs</th><th class="summary-count">Files</th></tr>')
        for i, (rule, count) in enumerate(sorted_rules):
            row = "tablerow" + str(i % 2)
            fc = rule_files.get(rule, 0)
            sub_counts = rule_sub_counts.get(rule, {})
            subs_str = ", ".join(f'<a href="#sub-{escape(s)}" data-sub="sub-{escape(s)}">{escape(s)}</a> ({sub_counts[s]})' for s in rule_subs.get(rule, []))
            w(f'<tr class="{row}"><td><a href="#" data-rule="{escape(rule)}">{escape(rule)}</a></td><td class="subs">{subs_str}</td><td class="summary-count">{count}</td><td class="summary-count">{fc}</td></tr>')
        w(f'<tr class="tablerow0"><td><b>Total</b></td><td></td><td class="summary-count"><b>{total}</b></td><td class="summary-count"><b>{len(files)}</b></td></tr>')
        w('</table>')

        w('<div id="rule-filter" style="display:none"></div>')

        # Bugs by subsystem
        w('<div id="violations">')
        w('<div class="tabletitle"><a name="violations">Bugs</a></div>')

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
                w('<tr><th class="line">Line</th><th>Bug Type</th><th>Message</th><th class="rule-category">Category</th><th class="rule-doc">Doc</th></tr>')
                for i, v in enumerate(sorted(violations, key=lambda x: int(x.get("begin", "0") or "0") if x.get("begin", "0").isdigit() else 0)):
                    row = "tablerow" + str(i % 2)
                    vid = f"v{abs(hash(fname + str(v['begin']) + v['rule']))}"
                    rng = v["begin"] if v["begin"] == v["end"] else f'{v["begin"]}-{v["end"]}'
                    doc_link = ''
                    if v["url"]:
                        doc_link = f'<a href="{escape(v["url"])}" target="_blank" class="rule-doc-link"><span class="rule-doc-icon" title="Bug documentation: {escape(v["rule"])}"></span></a>'
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
                    w(f'<td class="rule-category">{escape(v["category"])}</td>')
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
        w('</div>')

    w('</body></html>')

    html_output = minify_html("\n".join(lines))
    with open(html_file, "w") as f:
        f.write(html_output)

    print(f"Wrote {html_file}: {total} bugs across {len(files)} files", file=sys.stderr)


if __name__ == "__main__":
    main()
