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
import re
import glob
import html as htmlmod
import subprocess
from datetime import datetime, timezone
from urllib.parse import quote
from xml.etree import ElementTree as ET


def escape(s):
    return htmlmod.escape(s, quote=True)


def load_favicon():
    path = os.path.join(os.path.dirname(__file__), "..", "..", "installer",
                        "resources", "themes", "console", "images", "plus.svg")
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
        css = re.sub(r'/\*.*?\*/', '', css, flags=re.DOTALL)
        css = re.sub(r'\s+', ' ', css)
        css = re.sub(r'\s*([{}:;,])\s*', r'\1', css)
        return css.strip()
    except FileNotFoundError:
        return ""


def load_js():
    path = os.path.join(os.path.dirname(__file__), "test-report.js")
    try:
        with open(path) as f:
            js = f.read()
        js = re.sub(r'//.*?\n', '\n', js)
        js = re.sub(r'\s+', ' ', js)
        return js.strip()
    except FileNotFoundError:
        return ""


def git_info():
    info = {}
    try:
        branch = subprocess.check_output(
            ["git", "rev-parse", "--abbrev-ref", "HEAD"],
            stderr=subprocess.DEVNULL).decode().strip()
        info["branch"] = branch
    except Exception:
        pass
    try:
        commit = subprocess.check_output(
            ["git", "rev-parse", "--short", "HEAD"],
            stderr=subprocess.DEVNULL).decode().strip()
        info["commit"] = commit
    except Exception:
        pass
    try:
        dirty = subprocess.check_output(
            ["git", "status", "--porcelain"],
            stderr=subprocess.DEVNULL).decode().strip()
        info["dirty"] = bool(dirty)
    except Exception:
        pass
    return info


def system_info():
    import platform
    info = {}
    info["python"] = platform.python_version()
    info["arch"] = platform.machine()
    try:
        java_version = subprocess.check_output(
            ["java", "-version"], stderr=subprocess.STDOUT).decode()
        first_line = java_version.split("\n")[0]
        m = re.search(r'"([^"]+)"', first_line)
        if m:
            ver = m.group(1)
            ver = re.sub(r'-ea$', '', ver)
            info["java"] = ver
    except Exception:
        pass
    return info


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

    all_cases = []
    fail_cases = []
    for tc in root.findall(".//testcase"):
        failure = tc.find("failure")
        error = tc.find("error")
        skipped_elem = tc.find("skipped")
        case_name = tc.get("name", "?")
        case_class = tc.get("classname", "")
        case_time = float(tc.get("time", 0))
        status = "pass"
        if failure is not None:
            status = "fail"
        elif error is not None:
            status = "error"
        elif skipped_elem is not None:
            status = "skip"
        all_cases.append({
            "method": case_name,
            "classname": case_class,
            "time": case_time,
            "status": status,
        })
        if status in ("fail", "error"):
            elem = failure if failure is not None else error
            fail_cases.append({
                "method": case_name,
                "classname": case_class,
                "type": elem.get("type", status),
                "message": (elem.get("message", "") or "").strip()[:500],
                "trace": (elem.text or "").strip()[:2000],
            })

    return {
        "name": name,
        "tests": tests,
        "failures": failures,
        "errors": errors,
        "skipped": skipped,
        "time": time_s,
        "fail_cases": fail_cases,
        "all_cases": all_cases,
    }


def pass_rate(passed, total):
    if total == 0:
        return 100.0
    return (passed / total) * 100.0


def rate_color(rate):
    if rate >= 100:
        return "#2ecc71"
    if rate >= 90:
        return "#27ae60"
    if rate >= 75:
        return "#f39c12"
    if rate >= 50:
        return "#e67e22"
    return "#e74c3c"


def progress_bar(passed, failed, errors, skipped, total):
    if total == 0:
        return '<div class="progress-bar"><div class="pb-empty"></div></div>'
    p_pct = (passed / total) * 100
    f_pct = (failed / total) * 100
    e_pct = (errors / total) * 100
    s_pct = (skipped / total) * 100
    parts = []
    if p_pct > 0:
        parts.append(f'<div class="pb-pass" style="width:{p_pct:.1f}%"></div>')
    if f_pct > 0:
        parts.append(f'<div class="pb-fail" style="width:{f_pct:.1f}%"></div>')
    if e_pct > 0:
        parts.append(f'<div class="pb-error" style="width:{e_pct:.1f}%"></div>')
    if s_pct > 0:
        parts.append(f'<div class="pb-skip" style="width:{s_pct:.1f}%"></div>')
    return '<div class="progress-bar">' + "".join(parts) + '</div>'


def module_id(name):
    return re.sub(r'[^a-zA-Z0-9_-]', '-', name)


def get_package(name):
    """Extract package prefix from a test class name."""
    if '.' in name:
        parts = name.split('.')
        # Class name is last part, package is everything before it
        return '.'.join(parts[:-1])
    return ''


def group_by_package(results):
    """Group test results by package prefix. Returns OrderedDict of package -> [results]."""
    groups = {}
    for r in sorted(results, key=lambda x: x["name"]):
        pkg = get_package(r["name"])
        groups.setdefault(pkg, []).append(r)
    return groups


_row_counter = [0]


def emit_test_row(o, r):
    """Emit a test class row plus its collapsible detail row."""
    _row_counter[0] += 1
    row_id = f"det-{_row_counter[0]}"

    cls = ' class="test-row'
    if r["failures"] or r["errors"]:
        cls += ' fail-name'
    elif r["skipped"]:
        cls += ' skip-name'
    cls += '"'
    passed = r["tests"] - r["failures"] - r["errors"] - r["skipped"]

    has_cases = r.get("all_cases")
    if has_cases:
        o(f'<tr{cls} data-toggle="{row_id}" style="cursor:pointer">')
    else:
        o(f'<tr{cls}>')
    o(f'<td>{escape(r["name"])}</td>')
    o(f'<td>{passed}</td><td>{r["failures"]}</td><td>{r["errors"]}</td>')
    o(f'<td class="time">{r["time"]:.3f}s</td></tr>')

    # Detail row with individual test cases
    if has_cases:
        o(f'<tr class="detail-row" id="{row_id}" style="display:none">')
        o('<td colspan="5"><div class="detail-inner">')
        o('<table class="detail-table">')
        o('<thead><tr><th>Test</th><th></th><th>Time</th></tr></thead><tbody>')
        for c in sorted(has_cases, key=lambda x: x["method"]):
            status_cls = "pass" if c["status"] == "pass" else "fail"
            o(f'<tr><td>{escape(c["method"])}</td>')
            o(f'<td class="{status_cls}"></td>')
            o(f'<td class="time">{c["time"]:.3f}s</td></tr>')
        o('</tbody></table></div></td></tr>')


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
    total_rate = pass_rate(total_passed, total_tests)
    timestamp = datetime.now(timezone.utc).strftime("%B %-d %Y, %H:%M UTC")
    git = git_info()
    sysinfo = system_info()

    # Collect all test cases for slowest analysis
    all_cases = []
    for rs in modules.values():
        for r in rs:
            for c in r.get("all_cases", []):
                all_cases.append(c)
    slowest = sorted(all_cases, key=lambda c: c["time"], reverse=True)[:10]

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
    o('</style>')
    o('<script>')
    o(load_js())
    o('</script>')
    o('</head><body>')

    # Header
    o('<header>')
    o('<h1>I2P+ Test Report</h1>')
    o(f'<p class="meta">{escape(timestamp)} &middot; Total test time: {total_time:.1f}s</p>')

    # Info badges
    info_bits = []
    if git.get("branch"):
        info_bits.append(f'<span class="badge badge-git">{escape(git["branch"])}</span>')
    if git.get("commit"):
        dirty = " *" if git.get("dirty") else ""
        info_bits.append(f'<span class="badge badge-git">{escape(git["commit"])}{dirty}</span>')
    if sysinfo.get("java"):
        info_bits.append(f'<span class="badge badge-sys">Java {escape(sysinfo["java"])}</span>')
    if sysinfo.get("python"):
        info_bits.append(f'<span class="badge badge-sys">Python {escape(sysinfo["python"])}</span>')
    if sysinfo.get("arch"):
        info_bits.append(f'<span class="badge badge-sys">{escape(sysinfo["arch"])}</span>')
    if info_bits:
        o('<div class="info-badges">' + " ".join(info_bits) + '</div>')
    o('</header>')

    # Search
    o('<div class="search-bar"><input type="text" placeholder="Filter tests by name..." class="search-input"></div>')

    # Summary cards
    o('<div class="summary">')
    o(f'<div class="card total"><div class="num">{total_tests}</div><div class="label">Total</div></div>')
    o(f'<div class="card pass"><div class="num">{total_passed}</div><div class="label">Passed</div></div>')
    if total_fail:
        o(f'<div class="card fail"><div class="num">{total_fail}</div><div class="label">Failed</div></div>')
    if total_err:
        o(f'<div class="card error"><div class="num">{total_err}</div><div class="label">Errors</div></div>')
    if total_skip:
        o(f'<div class="card skip"><div class="num">{total_skip}</div><div class="label">Skipped</div></div>')
    o(f'<div class="card rate"><div class="num" style="color:{rate_color(total_rate)}">{total_rate:.1f}%</div><div class="label">Pass Rate</div></div>')
    o('</div>')

    # Overall progress bar
    o(progress_bar(total_passed, total_fail, total_err, total_skip, total_tests))

    # Module TOC
    o('<nav class="module-toc"><strong>Modules:</strong> ')
    for module in sorted(modules.keys()):
        mid = module_id(module)
        m_tests = sum(r["tests"] for r in modules[module])
        m_fail = sum(r["failures"] for r in modules[module])
        m_err = sum(r["errors"] for r in modules[module])
        m_skip = sum(r["skipped"] for r in modules[module])
        m_pass = m_tests - m_fail - m_err - m_skip
        m_rate = pass_rate(m_pass, m_tests)
        dot = "dot-pass" if m_rate >= 90 else "dot-fail" if m_rate < 75 else "dot-warn"
        o(f'<a href="#{mid}" class="toc-link"><span class="dot {dot}"></span>{escape(module)} <span class="toc-rate">{m_rate:.0f}%</span></a>')
    o('</nav>')

    # Module sections
    for module in sorted(modules.keys()):
        results = modules[module]
        mid = module_id(module)
        m_tests = sum(r["tests"] for r in results)
        m_fail = sum(r["failures"] for r in results)
        m_err = sum(r["errors"] for r in results)
        m_skip = sum(r["skipped"] for r in results)
        m_pass = m_tests - m_fail - m_err - m_skip
        m_time = sum(r["time"] for r in results)
        m_rate = pass_rate(m_pass, m_tests)

        o(f'<section id="{mid}" class="module-section">')
        o(f'<h2>{escape(module)} &middot; {m_tests} tests &middot; ')
        if m_fail or m_err:
            o(f'<span class="fail-name">{m_fail} failed</span> &middot; ')
        if m_err and m_err != m_fail:
            o(f'<span class="error-name">{m_err} errors</span> &middot; ')
        o(f'<span class="pass">{m_pass} passed</span>')
        o(f' &middot; <span class="time">{m_time:.1f}s</span>')
        o(f' &middot; <span style="color:{rate_color(m_rate)}" class="pass-rate-badge">{m_rate:.1f}%</span>')
        o('</h2>')

        # Module progress bar
        o(progress_bar(m_pass, m_fail, m_err, m_skip, m_tests))

        # Failure traces at top for easy access
        fails = [(r, fc) for r in results for fc in r.get("fail_cases", [])]
        if fails:
            o(f'<details><summary><div class="tabletitle">{len(fails)} failure/error trace(s)</div></summary>')
            o('<div class="trace-container">')
            for r, fc in fails:
                o(f'<pre class="trace"><b>{escape(r["name"])}.{escape(fc["method"])}</b>')
                if fc.get("classname"):
                    o(f' <span class="trace-class">({escape(fc["classname"])})</span>')
                o(f' <span class="trace-type">[{escape(fc["type"])}]</span>')
                if fc["message"]:
                    o(f'\n{escape(fc["message"])}')
                if fc["trace"]:
                    o(f'\n{escape(fc["trace"])}')
                o('</pre>')
            o('</div></details>')

        # Group by package for sub-sections
        pkg_groups = group_by_package(results)

        # Column header sort buttons
        col_headers = (
            f'<th data-sort-table="{mid}" data-sort-col="0" class="sortable">Test</th>'
            f'<th data-sort-table="{mid}" data-sort-col="1" class="sortable">Passed</th>'
            f'<th data-sort-table="{mid}" data-sort-col="2" class="sortable">Failed</th>'
            f'<th data-sort-table="{mid}" data-sort-col="3" class="sortable">Errors</th>'
            f'<th data-sort-table="{mid}" data-sort-col="4" class="sortable">Time</th>'
        )

        if len(pkg_groups) <= 1:
            # Single group — wrap in details
            pkg = list(pkg_groups.keys())[0]
            pkg_results = pkg_groups[pkg]
            pkg_label = pkg if pkg else module
            pkg_tests = sum(r["tests"] for r in pkg_results)
            pkg_fail = sum(r["failures"] for r in pkg_results)
            pkg_err = sum(r["errors"] for r in pkg_results)
            pkg_skip = sum(r["skipped"] for r in pkg_results)
            pkg_pass = pkg_tests - pkg_fail - pkg_err - pkg_skip
            pkg_rate = pass_rate(pkg_pass, pkg_tests)

            label = f"{escape(pkg_label)} &middot; {pkg_tests} tests"
            if pkg_fail or pkg_err:
                label += f' &middot; <span class="fail-name">{pkg_fail} failed</span>'
            label += f' &middot; <span class="pass">{pkg_pass} passed</span>'
            label += f' &middot; <span style="color:{rate_color(pkg_rate)}">{pkg_rate:.0f}%</span>'

            o(f'<details><summary><div class="tabletitle">{label}</div></summary>')
            o('<table>')
            o(f'<thead><tr>{col_headers}</tr></thead>')
            o('<tbody>')
            for r in sorted(pkg_results, key=lambda x: x["name"]):
                emit_test_row(o, r)
            o('</tbody></table>')
            o('</details>')
        else:
            # Multiple groups — sub-sections
            for pkg, pkg_results in pkg_groups.items():
                pkg_label = pkg if pkg else "(default)"
                pkg_tests = sum(r["tests"] for r in pkg_results)
                pkg_fail = sum(r["failures"] for r in pkg_results)
                pkg_err = sum(r["errors"] for r in pkg_results)
                pkg_skip = sum(r["skipped"] for r in pkg_results)
                pkg_pass = pkg_tests - pkg_fail - pkg_err - pkg_skip
                pkg_rate = pass_rate(pkg_pass, pkg_tests)

                label = f"{escape(pkg_label)} &middot; {pkg_tests} tests"
                if pkg_fail or pkg_err:
                    label += f' &middot; <span class="fail-name">{pkg_fail} failed</span>'
                label += f' &middot; <span class="pass">{pkg_pass} passed</span>'
                label += f' &middot; <span style="color:{rate_color(pkg_rate)}">{pkg_rate:.0f}%</span>'

                o(f'<details><summary><div class="tabletitle">{label}</div></summary>')
                o(progress_bar(pkg_pass, pkg_fail, pkg_err, pkg_skip, pkg_tests))
                o('<table>')
                o(f'<thead><tr>{col_headers}</tr></thead>')
                o('<tbody>')
                for r in sorted(pkg_results, key=lambda x: x["name"]):
                    emit_test_row(o, r)
                o('</tbody></table>')
                o('</details>')

        o('</section>')

    # Slowest tests panel
    if slowest:
        o('<section id="slowest-tests" class="module-section">')
        o('<h2>Slowest Tests (Top 10)</h2>')
        o('<table><thead><tr><th>Test</th><th>Status</th><th>Time</th></tr></thead><tbody>')
        for c in slowest:
            status_cls = "pass" if c["status"] == "pass" else "fail"
            o(f'<tr class="test-row"><td>{escape(c["method"])}</td>')
            o(f'<td><span class="{status_cls}"></span></td>')
            o(f'<td class="time">{c["time"]:.3f}s</td></tr>')
        o('</tbody></table>')
        o('</section>')

    o('</body></html>')

    with open(output, "w") as f:
        f.write("".join(out))

    print(f"Test report: {output} ({total_tests} tests, {total_fail} failed, {total_err} errors, {total_skip} skipped, {total_rate:.1f}% pass rate)")


if __name__ == "__main__":
    main()
