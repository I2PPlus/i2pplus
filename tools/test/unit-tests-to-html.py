#!/usr/bin/env python3
"""
Parse JUnit XML test results and generate an HTML report.

Usage: unit-tests-to-html.py [reports_dir] [output.html]

Defaults:
  reports_dir = reports/
  output.html = dist/test-report.html
"""

import sys
import os
import glob
import html as htmlmod
from datetime import datetime, timezone
from urllib.parse import quote
from xml.etree import ElementTree as ET


def escape(s):
    return htmlmod.escape(s, quote=True)


def load_favicon():
    path = os.path.join(os.path.dirname(__file__), "..", "..", "installer", "resources", "themes", "console", "images", "plus.svg")
    try:
        with open(path) as f:
            return f"data:image/svg+xml,{quote(f.read())}"
    except FileNotFoundError:
        return None


def load_css():
    path = os.path.join(os.path.dirname(__file__), "test-report.css")
    try:
        with open(path) as f:
            css = f.read()
        # Basic minification
        import re
        css = re.sub(r'/\*.*?\*/', '', css, flags=re.DOTALL)
        css = re.sub(r'\s+', ' ', css)
        css = re.sub(r'\s*([{}:;,])\s*', r'\1', css)
        return css.strip()
    except FileNotFoundError:
        return ""


def parse_junit_xml(path):
    try:
        tree = ET.parse(path)
    except (ET.ParseError, OSError):
        return None
    root = tree.getroot()
    attrs = root.attrib
    tests = int(attrs.get("tests", 0))
    failures = int(attrs.get("failures", 0))
    errors = int(attrs.get("errors", 0))
    skipped = int(attrs.get("skipped", 0))
    time_s = float(attrs.get("time", 0))
    name = attrs.get("name", os.path.splitext(os.path.basename(path))[0])
    if name.startswith("TEST-"):
        name = name[5:]
    fail_cases = []
    for tc in root.findall(".//testcase"):
        failure = tc.find("failure")
        error = tc.find("error")
        if failure is not None or error is not None:
            elem = failure if failure is not None else error
            fail_cases.append({
                "method": tc.get("name", "?"),
                "type": elem.get("type", "failure"),
                "message": (elem.get("message", "") or "").strip()[:200],
                "trace": (elem.text or "").strip()[:800],
            })
    return {
        "name": name,
        "tests": tests,
        "failures": failures,
        "errors": errors,
        "skipped": skipped,
        "time": time_s,
        "fail_cases": fail_cases,
    }


def main():
    reports_dir = sys.argv[1] if len(sys.argv) > 1 else "reports"
    output = sys.argv[2] if len(sys.argv) > 2 else "dist/test-report.html"

    modules = {}
    for xml_path in sorted(glob.glob(os.path.join(reports_dir, "**", "TEST-*.xml"), recursive=True)):
        rel = os.path.relpath(xml_path, reports_dir)
        parts = rel.split(os.sep)
        module = parts[0] if len(parts) > 1 else "unknown"
        result = parse_junit_xml(xml_path)
        if result:
            modules.setdefault(module, []).append(result)

    total_tests = sum(r["tests"] for rs in modules.values() for r in rs)
    total_fail = sum(r["failures"] for rs in modules.values() for r in rs)
    total_err = sum(r["errors"] for rs in modules.values() for r in rs)
    total_skip = sum(r["skipped"] for rs in modules.values() for r in rs)
    total_passed = total_tests - total_fail - total_err - total_skip
    total_time = sum(r["time"] for rs in modules.values() for r in rs)
    timestamp = datetime.now(timezone.utc).strftime("%B %-d %Y, %H:%M UTC")

    os.makedirs(os.path.dirname(output), exist_ok=True)

    out = []
    o = out.append

    o('<!DOCTYPE html>')
    o('<html lang="en"><head>')
    o('<meta charset="UTF-8">')
    o('<meta name="viewport" content="width=device-width, initial-scale=1.0">')
    favicon = load_favicon()
    if favicon:
        o(f'<link rel="icon" type="image/svg+xml" href="{favicon}">')
    o('<title>I2P+ Test Report</title>')
    o('<style>')
    o(load_css())
    o('</style></head><body>')

    o(f'<h1>I2P+ Test Report</h1>')
    o(f'<p class="meta">{escape(timestamp)} &middot; Total test time: {total_time:.1f}s</p>')

    o('<div class="summary">')
    o(f'<div class="card total"><div class="num">{total_tests}</div><div class="label">Total</div></div>')
    o(f'<div class="card pass"><div class="num">{total_passed}</div><div class="label">Passed</div></div>')
    if total_fail:
        o(f'<div class="card fail"><div class="num">{total_fail}</div><div class="label">Failed</div></div>')
    o('</div>')

    for module, results in sorted(modules.items()):
        m_tests = sum(r["tests"] for r in results)
        m_fail = sum(r["failures"] for r in results)
        m_err = sum(r["errors"] for r in results)
        m_skip = sum(r["skipped"] for r in results)
        m_pass = m_tests - m_fail - m_err - m_skip
        m_time = sum(r["time"] for r in results)

        o(f'<h2>{escape(module)} &middot; {m_tests} tests &middot; ')
        if m_fail or m_err:
            o(f'<span style="color:var(--fail)">{m_fail} failed</span> &middot; ')
        o(f'<span class="pass">{m_pass} passed</span>')
        o(f' &middot; All tests completed in: <span class="time">{m_time:.1f}s</span></h2>')

        o('<table><tr><th>Test</th><th>Passed</th><th>Failed</th><th>Errors</th><th>Time</th></tr>')
        for r in sorted(results, key=lambda x: x["name"]):
            cls = ' class="fail-name"' if r["failures"] or r["errors"] else ""
            passed = r["tests"] - r["failures"] - r["errors"] - r["skipped"]
            o(f'<tr><td{cls}>{escape(r["name"])}</td>')
            o(f'<td>{passed}</td><td>{r["failures"]}</td><td>{r["errors"]}</td>')
            o(f'<td class="time">{r["time"]:.3f}s</td></tr>')
        o('</table>')

        fails = [(r, fc) for r in results for fc in r["fail_cases"]]
        if fails:
            for r, fc in fails:
                o(f'<pre class="trace"><b>{escape(r["name"])}.{escape(fc["method"])}</b>')
                if fc["message"]:
                    o(f'\n{escape(fc["message"])}')
                if fc["trace"]:
                    o(f'\n{escape(fc["trace"])}')
                o('</pre>')

    o('</body></html>')

    with open(output, "w") as f:
        f.write("".join(out))

    print(f"Test report: {output} ({total_tests} tests, {total_fail} failed, {total_err} errors)")


if __name__ == "__main__":
    main()
