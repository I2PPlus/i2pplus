#!/usr/bin/env python3
"""
jsp-security-audit.py - static security audit of the JSP trees.

Separate from jsp-audit.py on purpose: that one asks "will this page run", this
one asks "what reaches the browser unescaped, and is every state change
authenticated". Neither is a compiler question. Every run is a fresh pass over
the current tree: no baseline file, no history, and any finding is a non-zero
exit. What needs judgement is reported as a blind spot, not silently accepted.

Rules
  unencoded-request-param   ${param.x} / ${header.x} / ${cookie.x} written to the
                            response. The console's own filter (ConsoleAuthFilter,
                            RouterFilter) and the surrounding attribute decide
                            whether a hit is reachable, so each hit is shown with
                            its context when the checker is run with --verbose.
  csrf-check-fails-open     an Origin/Host same-origin test that starts allowed and
                            is also set to allowed by its own catch, so a malformed
                            request or a lookup failure authorizes the POST.
  form-post-without-nonce   a <form method=POST> in a page with no CSRF nonce field
                            and no Origin/Host test anywhere in it.
  script-output-unescaped   <%= x %> between <script> and </script> where x is not
                            run through an escaper and can be traced back to the
                            request - HTML escaping is the wrong context there and
                            a quote breaks out of the JS literal. A value assigned
                            from a router property, a translation catalog or a bean
                            getter is server-derived and passes; a value the page
                            never assigns (a pageContext attribute set in Java) is
                            a blind spot and is reported as such, not as a finding.

Usage: jsp-security-audit.py [path ...] [--verbose] [--json] [--rule RULE]
       [--app APP]
"""

import argparse
import importlib.util
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location('jsp_audit', os.path.join(HERE, 'jsp-audit.py'))
ja = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ja)

ROOT = ja.ROOT
APPS = ja.APPS

# EL roots that come straight from the request
REQUEST_ROOTS = ('param', 'paramValues', 'header', 'headerValues', 'cookie', 'initParam')

# something(...escape...) / EscapeUtil / StringEscapeUtils style helpers
ESCAPER = re.compile(r'\b(esc|escape|escapeXml|escapeHtml4|filter|sanitize|'
                     r'HTMLFilter|XSSFilter|net\.i2p\.util\.EscapeUtil)\w*\s*\(', re.I)

FORM = re.compile(r'<form\b(?P<attrs>[^>]*)>', re.I)
SCRIPT = re.compile(r'<script\b[^>]*>(?P<body>.*?)</script\s*>', re.I | re.S)
ORIGIN_TEST = re.compile(r'getHeader\(\s*"(?:Origin|Host)"', re.I)
ALLOW_INIT = re.compile(r'\bboolean\s+(?P<name>\w*[Aa]llowed\w*)\s*=\s*true\b')
ALLOW_CATCH = re.compile(r'catch\s*\([^)]*\)\s*\{[^}]*?(?P<name>\w*[Aa]llowed\w*)\s*=\s*true', re.S)
NONCE = re.compile(r'name[= ]*"?nonce\b|getNonce\(|CSSHelper', re.I)

# Where a value in a page can come from the request at all. A scriptlet variable
# is only as safe as what was assigned to it, so the search follows assignments.
REQUEST_SOURCE = re.compile(r'\brequest\s*\.\s*(?:getParameter|getParameterValues|'
                            r'getParameterNames|getQueryString|getHeader|getCookies|'
                            r'getRequestURI|getRequestURL|getPathInfo)\b|'
                            r'\b(?:param|paramValues|header|headerValues|cookie)\s*\.\s*\w|'
                            r'\brequest(?:URI|URL|QueryString|PathInfo)\b')
ASSIGN = re.compile(r'\b(?P<name>[A-Za-z_$][\w$]*)\s*(?<![=!<>])=(?!=)\s*'
                    r'(?P<rhs>[^;\n]{1,200})')

# A translation catalog string is never request data.
TRANSLATION = re.compile(r'\b(?:_t|_x|ngettext)\s*\(|\b(?:intl|messages)\s*\.\s*\w+')

# Written in the page, so safe by construction.
LITERAL = re.compile(r'''(?x) ^ (?: "[^"]*" | '[^']*' | -?\d+(?:\.\d+)?[lLfFdD]? |
                                  true | false | null ) $''')

# A call on a class named in the expression (Integer.toHexString(..),
# net.i2p.router.web.CSSHelper.getNonce()) is server state, not request data.
# A call on a bean declared by the page stays a blind spot: a bean property can
# be filled in from the request and the checker cannot see the setter.
SERVER_CALL = re.compile(r'\b(?:[a-z][\w.]*\.)?[A-Z][\w$]*\s*\(')

IDENT = re.compile(r'[A-Za-z_$][\w$]*')


def in_attribute(text, pos):
    """True if the EL at pos sits inside an attribute value.

    Scans backwards for the nearest opening quote of an attribute and for the
    nearest tag boundary, and takes whichever is closer. An unquoted attribute
    value ends at the first space, so a space in the expression means the
    expression is not a value.
    """
    before = text[:pos]
    q = max(before.rfind('"'), before.rfind("'"))
    tag = max(before.rfind('>'), before.rfind('<'))
    if q < 0:
        return False
    if q < tag:
        return False
    if q > tag and ' ' in before[q + 1:pos]:
        return False
    return True


def assignments(text):
    """Every `name = rhs` written in the page, keyed by name.

    Deliberately loose: an HTML attribute that happens to look like an
    assignment only adds a safe right-hand side, and provenance() reports
    'request' if any assignment to the name reads from the request.
    """
    out = {}
    for m in ASSIGN.finditer(text):
        out.setdefault(m.group('name'), []).append(m.group('rhs'))
    return out


def provenance(expr, assigns, depth=0):
    """Where the value of a JSP expression can come from.

    'request' means it is reachable from a parameter, header or cookie;
    'server' means it is a literal, a catalog string or something assigned
    from server state; 'unknown' means the checker cannot see the source,
    which is a blind spot rather than a finding. Assignments are followed a
    few levels so `String h = request.getParameter(..); ... <%= h %>` is a
    finding and `String t = bean.getTheme(); ... <%= t %>` is not.
    """
    expr = expr.strip()
    if REQUEST_SOURCE.search(expr):
        return 'request'
    if LITERAL.match(expr):
        return 'server'
    if TRANSLATION.search(expr) or SERVER_CALL.search(expr):
        return 'server'
    if depth >= 4:
        return 'unknown'
    m = IDENT.match(expr)
    if not m:
        return 'unknown'
    rhs = assigns.get(m.group(0))
    if not rhs:
        return 'unknown'
    state = 'server'
    for r in rhs:
        p = provenance(r, assigns, depth + 1)
        if p == 'request':
            return 'request'
        if p == 'unknown':
            state = 'unknown'
    return state


def check_page(path, text, expanded, findings, blind):
    body = ja.strip_jsp_comments(text)
    # a nonce or an Origin test declared in a statically included fragment
    # protects the page that pulls it in, so the page-level decision reads the
    # expanded text while every finding is reported at its real file and line
    page = ja.strip_jsp_comments(expanded)

    # unencoded-request-param
    for m in ja.EL_PATH.finditer(body):
        if m.group('root') not in REQUEST_ROOTS:
            continue
        if in_attribute(body, m.start()):
            continue
        expr = m.group(0)
        lo = max(0, m.start() - 160)
        if ESCAPER.search(body[lo:m.start()]):
            continue
        findings.add('unencoded-request-param', path, body, m.start(), expr)

    # csrf-check-fails-open
    if ORIGIN_TEST.search(body):
        for m in ALLOW_INIT.finditer(body):
            name = m.group('name')
            tail = body[m.end():m.end() + 2000]
            cm = ALLOW_CATCH.search(tail)
            if cm and cm.group('name') == name:
                findings.add('csrf-check-fails-open', path, body, m.start(), name)

    # form-post-without-nonce
    has_nonce = bool(NONCE.search(page))
    for m in FORM.finditer(body):
        if not re.search(r'method\s*=\s*["\']?\s*post', m.group('attrs'), re.I):
            continue
        if has_nonce or ORIGIN_TEST.search(page):
            continue
        findings.add('form-post-without-nonce', path, body, m.start(),
                     os.path.basename(path))

    # script-output-unescaped
    assigns = assignments(page)
    for sm in SCRIPT.finditer(body):
        inner = sm.group('body')
        base = sm.start('body')
        for em in re.finditer(r'<%=\s*(?P<expr>.*?)%\>', inner, re.S):
            expr = em.group('expr').strip()
            if ESCAPER.search(expr):
                continue
            state = provenance(expr, assigns)
            if state == 'unknown':
                blind.add(expr)
                continue
            if state == 'request':
                findings.add('script-output-unescaped', path, body,
                             base + em.start(), expr)


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('paths', nargs='*', help='files or directories (default: the JSP trees)')
    ap.add_argument('--verbose', action='store_true', help='list every finding')
    ap.add_argument('--json', action='store_true', help='machine-readable findings')
    ap.add_argument('--rule', action='append', help='only these rules')
    ap.add_argument('--app', action='append', help='only these apps')
    ap.add_argument('--ant-report', metavar='FILE',
                    help='append the finding counts to FILE as ant properties')
    args = ap.parse_args()

    wanted_apps = set(args.app) if args.app else set(a['name'] for a in APPS)
    wanted_rules = set(args.rule) if args.rule else None

    targets = []
    if args.paths:
        for p in args.paths:
            p = p if os.path.isabs(p) else os.path.join(ROOT, p)
            if os.path.isdir(p):
                targets.extend(('given paths', os.path.join(dp, f))
                               for dp, _, fs in os.walk(p)
                               for f in fs if f.endswith(('.jsp', '.jsi')))
            else:
                targets.append(('given paths', p))
    else:
        for app in APPS:
            if app['name'] in wanted_apps:
                targets.extend((app['name'], p) for p in ja.jsp_files(app))

    findings = ja.Findings()
    blind = set()
    pages = {}
    for app_name, path in targets:
        text = ja.read(path)
        if text is None:
            sys.stderr.write('unreadable: %s\n' % path)
            continue
        pages[app_name] = pages.get(app_name, 0) + 1
        first = len(findings.items)
        # a nonce or an Origin test declared in a statically included fragment
        # protects the page that pulls it in
        check_page(ja.repo_rel(path), text,
                   ja.expand_includes(path, text), findings, blind)
        for item in findings.items[first:]:
            item['app'] = app_name

    if wanted_rules:
        findings.items = [i for i in findings.items if i['rule'] in wanted_rules]

    if args.json:
        print(json.dumps({'pages': sum(pages.values()), 'findings': findings.items,
                          'blind': sorted(blind)}, indent=1))
    else:
        # house style for a build-time checker (see bundle-messages.sh): one
        # '* ' summary line per app, then the tally of what fired with the pages it
        # fired on, then the command that shows the detail. The findings themselves,
        # with the rule id and the prose, are for a --verbose run.
        jspdirs = {a['name']: a['jsp'] for a in ja.APPS}
        for name in sorted(pages):
            mine = [i for i in findings.items if i.get('app') == name]
            print(ja.summary_line('JSP security check', name, len(mine)))
            if not mine:
                continue
            if args.verbose:
                for i in sorted(mine, key=lambda x: (x['file'], x['line'])):
                    for ln in ja.finding_lines(i):
                        print(ln)
            else:
                for rule, cnt in sorted(ja._rule_counts(mine).items(),
                                        key=lambda kv: (-kv[1], ja.rule_label(kv[0]))):
                    print('  - %s: %d' % (ja.rule_label(rule), cnt))
                    for ln in ja._tally_sites(mine, rule, jspdirs.get(name, '')):
                        print(ln)
                print('  - Total: %d' % len(mine))
            if not args.verbose:
                print(ja.report_cmd('tools/scripts/jsp-security-audit.py', name))
        if blind and args.verbose:
            print('* JSP security unchecked expressions: %d (source not visible '
                  'from the page)' % len(blind))
            for b in sorted(blind):
                for ln in ja.item_lines(b):
                    print(ln)

    if args.ant_report:
        for name in sorted(pages):
            ja.ant_report(args.ant_report, 'security',
                          [i for i in findings.items if i.get('app') == name])

    # a fresh pass every run: no baseline, so any finding at all is a failure
    return 1 if findings.items else 0


if __name__ == '__main__':
    sys.exit(main())
