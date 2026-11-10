#!/usr/bin/env python3
"""Audit the webapp JSPs for the defects the JSP compiler cannot see.

The build already translates every JSP with Tomcat Jasper (net.i2p.servlet.util.JspC,
failonerror="true"), so syntax errors, unknown taglibs, scriptlet Java that does not
parse and useBean classes that do not exist all fail the build. What Jasper does NOT
check is everything resolved by reflection at runtime:

  * <jsp:setProperty>/<jsp:getProperty> and EL ${bean.prop} are compiled into
    Introspector lookups, so a bean property renamed or deleted in Java translates
    cleanly and 500s in the browser. This is the class of bug this tool exists for
    (details.jsp referenced trClass after the bean dropped it).
  * <jsp:useBean id="x"> used before it is declared, or never declared in the page
    or the files it statically includes.
  * <%@ include file="..." %> targets, and href/action links to a .jsp page that
    does not exist in the webapp.
  * EL roots that nothing ever sets, so the expression silently evaluates to "".
  * _t()/_x()/ngettext() msgids that are not in the app's .po files, which render
    the untranslated key text in the console.
  * request.getParameter() written straight to the response with no encoding.
  * HTML markup that does not balance: an element left open at the end of a page
    (applytheme.jsp shipped an unclosed <form>), an end tag with nothing open to
    close (login.jsp's </a></a>), an element closed inside the wrong one, a start
    tag for a void element's end (</br>), and a tag whose start and end sit under
    different scriptlet or JSTL guards - the netdb.jsp form, where the <div> is
    opened under one condition and closed under a slightly different one, so one
    branch of the page is malformed even though the file balances overall.
  * House hygiene the tree has already settled on: pageEncoding on every page, no
    trailing whitespace, no tabs.

The markup balance check is deliberately not an HTML parser. It knows the void
elements, the elements HTML closes implicitly (<p>, <li>, <td>, <tr>, <option>
and friends are never reported for staying open), the raw-text elements whose
bodies are not markup (<script>, <style>), attribute quoting, a JSP expression
inside an attribute value, a JSTL action written inside a start tag, and JSP
scriptlets and comments. Balance is measured over the rendered page: the static
<%@ include file="..." %> fragments are walked in document order, so head.jsi
opening <head> and the page closing it is one element, not two findings, and a
finding in a .jsi names the fragment whose tag the page never closed.

A scriptlet branch is an alternative, not a step in a sequence: when a branch ends
the open-element stack is put back as it was when it began, so an if arm and an else
arm that each balance on their own are not reported, and only what a branch itself
opened stays marked as conditional. That is what separates editServer.jsi, whose two
arms each write their own </th><th>, from netdb.jsp, whose <div> is opened under one
condition and closed under a slightly different one.

A final newline is deliberately NOT required: these pages are output-sensitive and
several say so in their own first line ("No newline or spaces at end of file,
otherwise errors!"), so the last byte is usually a '>' by intent.

Bean accessors are read out of the Java sources, following the extends chain, so a
property inherited from a base class is not a false alarm. A bean class that cannot
be found on the source path is counted as a blind spot and its properties are not
judged - sight is claimed only over what was parsed.

Every run is a fresh pass over the current sources: there is no baseline, and any
finding at all is a failure. Findings are identified by rule + file + normalized
expression, never by line number, so a report does not churn when code above a
finding moves.

Usage:
    tools/scripts/jsp-audit.py                             # summary, exit 1 on any finding
    tools/scripts/jsp-audit.py --verbose                   # list every finding and blind spot
    tools/scripts/jsp-audit.py --json                      # machine-readable, with locations
    tools/scripts/jsp-audit.py --rule bean-prop-unknown    # one rule only
    tools/scripts/jsp-audit.py --selftest                  # markup-rule fixtures
    tools/scripts/jsp-audit.py --ant-report FILE           # counts, for the build
"""
import argparse
import glob
import json
import os
import re
import sys
import tempfile
import textwrap

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# The three webapps whose JSPs the build compiles. jsp is the webapp's page tree,
# java is where its own classes live, locale holds its .po files, and shared is the
# extra source roots needed to resolve the beans the pages declare.
APPS = [
    {
        'name': 'susidns',
        'jsp': 'apps/susidns/src/jsp',
        'java': ['apps/susidns/src/java/src', 'apps/addressbook/java/src'],
        'locale': 'apps/susidns/locale',
        'shared': ['core/java/src', 'router/java/src', 'apps/routerconsole/java/src',
                   'apps/jetty/java/src'],
    },
    {
        'name': 'routerconsole',
        'jsp': 'apps/routerconsole/jsp',
        'java': ['apps/routerconsole/java/src'],
        'locale': 'apps/routerconsole/locale',
        'shared': ['core/java/src', 'router/java/src', 'apps/streaming/java/src',
                   'apps/ministreaming/java/src', 'apps/jetty/java/src',
                   'apps/i2psnark/java/src'],
    },
    {
        'name': 'i2ptunnel',
        'jsp': 'apps/i2ptunnel/jsp',
        'java': ['apps/i2ptunnel/java/src'],
        'locale': 'apps/i2ptunnel/locale',
        'shared': ['core/java/src', 'router/java/src', 'apps/routerconsole/java/src',
                   'apps/streaming/java/src', 'apps/ministreaming/java/src',
                   'apps/jetty/java/src'],
    },
]

# Anchored to the repo root so the checker runs from any directory — the ant and
# gradle hooks invoke it from the webapp's own module directory.
for _app in APPS:
    _app['jsp'] = os.path.join(ROOT, _app['jsp'])
    _app['locale'] = os.path.join(ROOT, _app['locale'])
    for _key in ('java', 'shared'):
        _app[_key] = [os.path.join(ROOT, p) for p in _app[_key]]

# JSP implicit objects and JSTL-scoped names that are always legal EL roots.
EL_IMPLICIT = {
    'pageContext', 'pageScope', 'requestScope', 'sessionScope', 'applicationScope',
    'param', 'paramValues', 'header', 'headerValues', 'cookie', 'initParam',
    'page', 'out', 'config', 'exception', 'fn', 'c', 'fmt', 'ut',
}

DIRECTIVE = re.compile(r'<%@(?P<body>.*?)%>', re.S)
USEBEAN = re.compile(r'<jsp:useBean\b[^>]*?/?>', re.S)
PROP_USE = re.compile(r'<jsp:(?:get|set)Property\b[^>]*?/?>', re.S)
ATTR = re.compile(r'\b(?P<name>id|name|property|class|type|beanName|value|file|var)\s*=\s*"([^"]*)"')
INCLUDE = re.compile(r'<%@\s*include\b[^>]*?file\s*=\s*"(?P<file>[^"]*)"', re.S)
EL_USE = re.compile(r'[$#{]\{\s*(?P<expr>[^}]*)\}')
EL_ROOT = re.compile(r'^\s*(?P<root>[A-Za-z_$][\w$]*)')
EL_PATH = re.compile(r'^\s*(?P<root>[A-Za-z_$][\w$]*)((?:\s*\.\s*[A-Za-z_$][\w$]*)+)'
                     r'(?P<call>\s*\()?')
# One `.name[(]` step of a dotted EL chain, so a method call is told apart from
# a property read - ${book.getBook()} is EL 2.2 method invocation, not a property.
EL_STEP = re.compile(r'\s*\.\s*(?P<prop>[A-Za-z_$][\w$]*)(?P<call>\s*\()?')
LINK = re.compile(r'\b(?:href|action|src)\s*=\s*"(?P<url>[^"]*)"')
I18N = re.compile(r'\b(?:_t|_x|ngettext|gettext)\s*\((?P<args>[^)]*)\)')
STR_LIT = re.compile(r'"((?:[^"\\]|\\.)*)"')
SET_ATTR = re.compile(r'\bsetAttribute\s*\(\s*"([^"]+)"')
LINK_JSP = re.compile(r'\.jsp$')

# HTML markup balance. The tree writes HTML by hand in template text, so nothing
# but a check of our own ever sees whether it balances.
HTML_VOID = {'area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link',
             'meta', 'param', 'source', 'track', 'wbr'}
# Elements HTML closes by itself: an open one is closed by the next sibling of the
# same kind or by a parent's next child, so leaving one open is house style, not a
# defect. Reported only when an end tag for one lands in the wrong place.
HTML_IMPLICIT_CLOSE = {
    'p': {'p'},
    'li': {'li'},
    'dl': {'dt', 'dd'}, 'dt': {'dt', 'dd'}, 'dd': {'dt', 'dd'},
    'tr': {'td', 'th'},
    'td': {'td', 'th'}, 'th': {'td', 'th'},
    'thead': {'td', 'th', 'tr'}, 'tbody': {'td', 'th', 'tr'}, 'tfoot': {'td', 'th', 'tr'},
    'caption': {'td', 'th', 'tr', 'thead', 'tbody', 'tfoot'},
    'colgroup': {'colgroup'},
    'option': {'option'}, 'optgroup': {'option', 'optgroup'},
}
# Bodies that are not markup: a '<' inside them is JavaScript or CSS, not a tag.
HTML_RAWTEXT = {'script', 'style'}
# JSTL tags that open a branch, so a tag opened inside one and closed outside it is
# the same defect as doing it inside a Java scriptlet.
HTML_GUARD_TAGS = {'c:if', 'c:when', 'c:otherwise', 'c:choose', 'c:forEach',
                   'c:forTokens', 'c:catch'}
TAG_NAME = re.compile(r'[A-Za-z][\w:.\-]*')
RAWTEXT_END = {t: re.compile(r'</\s*%s\s*>' % t, re.I) for t in HTML_RAWTEXT}
# Every element name the checker knows. A start tag written inside a Java string
# literal is only recognised when it is one of these: Java is full of '<', and
# 'i<len' or 'a<b' has to read as a comparison, not as an element that never closes.
HTML_NAMES = frozenset(
    {'a', 'abbr', 'address', 'area', 'article', 'aside', 'audio', 'b', 'base',
     'bdi', 'bdo', 'blockquote', 'body', 'br', 'button', 'canvas', 'caption',
     'cite', 'code', 'col', 'colgroup', 'data', 'datalist', 'dd', 'del', 'details',
     'dfn', 'dialog', 'div', 'dl', 'dt', 'em', 'embed', 'fieldset', 'figcaption',
     'figure', 'footer', 'form', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'head',
     'header', 'hgroup', 'hr', 'html', 'i', 'iframe', 'img', 'input', 'ins', 'kbd',
     'label', 'legend', 'li', 'link', 'main', 'map', 'mark', 'menu', 'meta', 'nav',
     'noscript', 'object', 'ol', 'optgroup', 'option', 'output', 'p', 'param',
     'picture', 'pre', 'q', 'rp', 'rt', 's', 'samp', 'script', 'section', 'select',
     'slot', 'small', 'source', 'span', 'strong', 'style', 'sub', 'summary', 'sup',
     'table', 'tbody', 'td', 'template', 'textarea', 'tfoot', 'th', 'thead', 'time',
     'title', 'tr', 'track', 'u', 'ul', 'var', 'video', 'wbr',
     # the JSP and JSTL actions a page may build in Java rather than in the template
     'c:catch', 'c:choose', 'c:forTokens', 'c:forEach', 'c:if', 'c:otherwise',
     'c:when', 'fmt:message', 'jsp:attribute', 'jsp:body', 'jsp:element',
     'jsp:fallback', 'jsp:getProperty', 'jsp:include', 'jsp:param', 'jsp:params',
     'jsp:useBean', 'jsp:widget', 'jsp:parameter', 'jsp:content'})

# Java parsing: enough to see what a class extends and which accessors it declares.
JAVA_CLASS = re.compile(r'\b(?:public\s+|final\s+|abstract\s+)*(?:class|interface)\s+(?P<name>\w+)'
                        r'(?:\s*<[^>]*>)?(?:[^{]*?\bextends\s+(?P<ext>[\w.]+))?')
JAVA_IMPORT = re.compile(r'^\s*import\s+(?:static\s+)?(?P<fqn>[\w.]+)(?:\.\*)?\s*;', re.M)
JAVA_PKG = re.compile(r'^\s*package\s+([\w.]+)\s*;', re.M)
ACCESSOR = re.compile(r'\b(?P<kind>get|is|set)(?P<prop>[A-Za-z]\w*)\s*\(')
PUBLIC_FIELD = re.compile(r'^\s*public\s+(?:static\s+|final\s+|transient\s+|volatile\s+)*'
                          r'[\w.<>,\[\]?]+\s+(?P<name>\w+)\s*[;=]', re.M)
RETURN_TYPE = re.compile(r'[\w.<>,\[\]?\s]*?\b(?P<kind>get|is)(?P<prop>[A-Za-z]\w*)\s*\(\s*\)\s*'
                         r'(?:throws [\w.,\s]+)?\s*(?:[{;]|$)')
# A visible method declaration. Deliberately loose: matching a call site instead
# only makes the EL method check more forgiving, which is the safe direction.
JAVA_METHOD = re.compile(r'\b(?:public|protected)\s+(?:static\s+|final\s+|synchronized\s+|'
                         r'abstract\s+|default\s+)*[\w.<>,\[\]?\s]*?\s(?P<name>\w+)\s*\(')
MODIFIERS = re.compile(r'\b(private|static|abstract|native)\b')


def _blank(text):
    """
    The text with Java comments and string literals replaced by spaces, at the same
    offsets.

    _clean() removes what it strips, which is what the Java index wants but leaves
    the offsets useless; a scanner that reports a line number or names the statement
    it found has to work on text that still lines up with the file.
    """
    out = list(text)
    n = len(text)

    def blank(start, stop):
        for k in range(start, stop):
            if text[k] != '\n':
                out[k] = ' '

    i = 0
    while i < n:
        c = text[i]
        if c == '/' and text[i + 1:i + 2] == '/':
            j = text.find('\n', i)
            blank(i, n if j < 0 else j)
            i = n if j < 0 else j
        elif c == '/' and text[i + 1:i + 2] == '*':
            j = text.find('*/', i + 2)
            blank(i, n if j < 0 else j + 2)
            i = n if j < 0 else j + 2
        elif c in '"\'':
            j = i + 1
            while j < n and text[j] != c:
                j += 2 if text[j] == '\\' else 1
            blank(i, min(j + 1, n))
            i = j + 1
        else:
            i += 1
    return ''.join(out)


def _clean(text):
    """Strip comments and string literals so Java scanning cannot match inside them."""
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == '/' and i + 1 < n and text[i + 1] == '/':
            j = text.find('\n', i)
            i = n if j < 0 else j
        elif c == '/' and i + 1 < n and text[i + 1] == '*':
            j = text.find('*/', i + 2)
            i = n if j < 0 else j + 2
        elif c in '"\'':
            q = c
            i += 1
            while i < n and text[i] != q:
                i += 2 if text[i] == '\\' else 1
            i += 1
            out.append(' ')
        else:
            out.append(c)
            i += 1
    return ''.join(out)


def strip_jsp_comments(text):
    """Blank out <%-- --%> comment bodies, keeping every other offset intact.

    A commented-out block is not live markup: it declares no beans, sets no
    properties and needs no translation. Scanning the masked text instead of the
    raw text is what keeps dead code from being reported — config.jsp's
    commented-out burst-factor boxes and ssl.jsp's commented-out table do
    exactly that.
    """
    out = list(text)

    def blank(start, stop):
        for k in range(start, stop):
            if text[k] != '\n':
                out[k] = ' '

    i = 0
    while True:
        i = text.find('<%--', i)
        if i < 0:
            break
        end = text.find('--%>', i + 4)
        stop = len(text) if end < 0 else end + 4
        blank(i, stop)
        i = stop

    # A Java comment inside a scriptlet is not live code either. formhandler.jsi
    # documents its use with a literal `<jsp:useBean class="...xxxHandler" .../>`
    # inside a /* */ block, and without this mask that prose reads as a bean
    # declaration of a class that does not exist.
    i = 0
    while True:
        i = text.find('<%', i)
        if i < 0:
            break
        if text[i:i + 4] == '<%--' or text[i:i + 2] in ('<%@',):
            i += 2
            continue
        end = text.find('%>', i + 2)
        stop = len(text) if end < 0 else end + 2
        k = i + 2
        while k < stop - 2:
            c = text[k]
            if c == '/' and text[k + 1] == '/':
                nl = text.find('\n', k, stop)
                blank(k, stop - 2 if nl < 0 else nl)
                k = stop - 2 if nl < 0 else nl
            elif c == '/' and text[k + 1] == '*':
                close = text.find('*/', k + 2, stop)
                blank(k, stop - 2 if close < 0 else close + 2)
                k = stop - 2 if close < 0 else close + 2
            elif c in '"\'':
                q = c
                k += 1
                while k < stop - 2 and text[k] != q:
                    k += 2 if text[k] == '\\' else 1
                k += 1
            else:
                k += 1
        i = stop
    return ''.join(out)


def java_string_unescape(s):
    """Decode the escapes that are legal inside a Java string literal.

    configban.jsp writes _x("Ban routers with > 5 introducers\'s", ...); the
    \' has to become a plain apostrophe before the msgid is compared with the
    .po file, or every escaped string is a false positive.
    """
    def one(m):
        e = m.group(1)
        if e[0] == 'u':
            return chr(int(e[1:], 16))
        return {'n': '\n', 't': '\t', 'r': '\r', 'b': '\b', 'f': '\f'}.get(e[0], e)
    return re.sub(r'\\(u[0-9a-fA-F]{4}|.)', one, s)


def java_string_concatenations(text):
    """Every `"a" + "b"` Java string concatenation in the file, as one string.

    A long msgid is wrapped across source lines in exactly this form, so the
    .po lookup has to see the assembled value, not its first fragment.
    """
    out = []
    for m in re.finditer(r'"((?:[^"\\\n]|\\.)*)"\s*\+\s*(?:"((?:[^"\\\n]|\\.)*)"|[A-Za-z_$][\w.]*\s*\()',
                         text):
        left = java_string_unescape(m.group(1))
        right = m.group(2)
        if right is None:
            continue
        out.append(left + java_string_unescape(right))
    # and the tail of a longer chain: "x" + "y" + "z" — reassemble runs
    for m in re.finditer(r'((?:"(?:[^"\\\n]|\\.)*"\s*\+\s*)+"(?:[^"\\\n]|\\.)*")', text):
        parts = re.findall(r'"((?:[^"\\\n]|\\.)*)"', m.group(1))
        if len(parts) > 1:
            out.append(''.join(java_string_unescape(p) for p in parts))
    return out


def resolve_include(base, path, rel):
    """Filesystem path of a static include target.

    A leading / is context-relative, i.e. the application's jsp root; anything
    else is relative to the including page.
    """
    if rel.startswith('/'):
        return os.path.normpath(os.path.join(base, rel[1:]))
    return os.path.normpath(os.path.join(os.path.dirname(path), rel))


def decapitalize(name):
    """JavaBeans property name for an accessor suffix (Introspector's rule).

    getURL -> URL, two leading capitals left alone; getfileName -> fileName.
    Following the spec rather than the common case is what stops a false
    positive on the tree's lower-case-first accessors.
    """
    if not name:
        return name
    if len(name) > 1 and name[0].isupper() and name[1].isupper():
        return name
    return name[0].lower() + name[1:]


class JavaIndex:
    """Resolves a class name to its source, and its accessors, across source roots."""

    def __init__(self, roots):
        self.roots = [r for r in roots if os.path.isdir(r)]
        self._by_simple = {}
        for root in self.roots:
            for dirpath, _dirs, files in os.walk(root):
                pkg = os.path.relpath(dirpath, root).replace(os.sep, '.')
                if pkg == '.':
                    pkg = ''
                for fn in files:
                    if fn.endswith('.java'):
                        self._by_simple.setdefault(fn[:-5], set()).add(
                            (pkg + '.' + fn[:-5]) if pkg else fn[:-5])
        self._cache = {}

    def resolve(self, fqn, imports=None, local_pkg=''):
        """Return the source path for a class name, or None if it is not in the tree."""
        if '<' in fqn or fqn.endswith('[]'):
            return None
        cands = []
        if '.' in fqn:
            cands.append(fqn)
        else:
            for imp in imports or ():
                if imp.endswith('.' + fqn):
                    cands.append(imp)
            if local_pkg:
                cands.append(local_pkg + '.' + fqn)
            cands.extend(sorted(self._by_simple.get(fqn, ())))
        for cand in cands:
            for root in self.roots:
                path = os.path.join(root, cand.replace('.', os.sep) + '.java')
                if os.path.isfile(path):
                    return path
        return None

    def members(self, path):
        """Return (readable props, writable props, {prop: return type}, method names,
        parents, imports, pkg).

        Property names, not accessor names, and always through decapitalize(),
        so a getter written getfileName() is found as the property fileName the
        JSP actually asks for.
        """
        if path in self._cache:
            return self._cache[path]
        try:
            with open(path, encoding='utf-8', errors='replace') as f:
                text = _clean(f.read())
        except OSError:
            return set(), set(), {}, set(), [], [], ''
        pkg = JAVA_PKG.search(text)
        pkg = pkg.group(1) if pkg else ''
        imports = [m.group('fqn') for m in JAVA_IMPORT.finditer(text)]
        parents = []
        for m in JAVA_CLASS.finditer(text):
            if m.group('name') == os.path.basename(path)[:-5] and m.group('ext'):
                parents.append(m.group('ext'))
        gets = set()
        sets = set()
        returns = {}
        methods = set()
        for m in ACCESSOR.finditer(text):
            prop = decapitalize(m.group('prop'))
            methods.add(m.group('kind') + m.group('prop'))
            if m.group('kind') == 'set':
                sets.add(prop)
            else:
                gets.add(prop)
        for m in JAVA_METHOD.finditer(text):
            methods.add(m.group('name'))
        for m in RETURN_TYPE.finditer(text):
            prop = decapitalize(m.group('prop'))
            head = text[max(0, m.start() - 120):m.start()]
            decl = RETURN_TYPE_HEAD.search(head) if RETURN_TYPE_HEAD else None
            if decl:
                returns[prop] = decl.group(1).strip()
        # a public field is a JavaBeans property too, and JSPs read them
        for m in PUBLIC_FIELD.finditer(text):
            if '(' not in text[m.end():m.end() + 3]:
                gets.add(m.group('name'))
        self._cache[path] = (gets, sets, returns, methods, parents, imports, pkg)
        return self._cache[path]

    def has_method(self, path, name, depth=0, seen=None):
        """Is a method of that name declared here or in a supertype we can read?

        EL 2.2 lets a JSP call a plain method (`${book.getBook()}`), which is not
        a JavaBeans property at all, so a name that is not an accessor is still
        valid usage.
        """
        if path is None or depth > 8:
            return True
        seen = seen or set()
        if path in seen:
            return True
        seen.add(path)
        _gets, _sets, _returns, methods, parents, imports, pkg = self.members(path)
        if name in methods:
            return True
        for parent in parents:
            ppath = self.resolve(parent, imports, pkg)
            if ppath is None:
                return True  # supertype outside the tree: no alarm
            if self.has_method(ppath, name, depth + 1, seen):
                return True
        return False


RETURN_TYPE_HEAD = re.compile(r'([\w.<>,\[\]?]*\s+)\b(?:public|protected|private)?\s*[\w.<>,\[\]?\s]*$')


def bean_property_ok(java, path, prop, want_set, depth=0, seen=None):
    """True when the class at path, or anything it extends, can carry this property.

    Property names are compared after decapitalize(), which is how Jasper's
    Introspector-derived helper resolves get/setProperty and EL paths.
    """
    if path is None or depth > 6:
        return True
    seen = seen or set()
    if path in seen:
        return True
    seen.add(path)
    gets, sets, _returns, _methods, parents, imports, pkg = java.members(path)
    if want_set:
        if prop in sets:
            return True
    elif prop in gets:
        return True
    for parent in parents:
        ppath = java.resolve(parent, imports, pkg)
        if bean_property_ok(java, ppath, prop, want_set, depth + 1, seen):
            return True
    # inherited from a class outside the tree (Object, a servlet class): give up, no alarm
    if parents and any(java.resolve(p, imports, pkg) is None for p in parents):
        return True
    return False


def property_type(java, path, prop):
    """Best-effort return type of the getter for prop, as a simple or fqn name."""
    _gets, _sets, returns, _methods, _parents, _imports, _pkg = java.members(path)
    return returns.get(prop)


def read(path):
    try:
        with open(path, encoding='utf-8', errors='replace') as f:
            return f.read()
    except OSError:
        return None


def jsp_files(app):
    out = []
    base = app['jsp']
    for dirpath, _dirs, files in os.walk(base):
        for fn in sorted(files):
            if fn.endswith(('.jsp', '.jsi')):
                out.append(os.path.join(dirpath, fn))
    return sorted(out)


def build_dirs():
    """
    Compiled output that may already hold the classes the pages reference: the
    install tree's jars, plus the ant build root's jars and class directories
    when the tree has been built. Entries that are not on disk are skipped.
    """
    antroot = os.path.join(tempfile.gettempdir(), 'build-i2p')
    out = sorted(glob.glob(os.path.join(ROOT, 'dist', 'lib', '*.jar')))
    out.extend(sorted(glob.glob(os.path.join(antroot, 'build', '*.jar'))))
    out.extend(sorted(glob.glob(os.path.join(antroot, 'apps', '*', '*', 'build', 'obj'))))
    out.extend(sorted(glob.glob(os.path.join(antroot, 'apps', '*', 'src', 'build', 'classes'))))
    return [p for p in out if os.path.exists(p)]


def java_sources(app):
    """
    Java source roots javac may need in order to resolve the beans the pages
    declare: the app's own sources plus the shared trees it reads from.
    """
    return app['java'] + app['shared']


def repo_rel(path):
    """Path as seen from the repo root, so a finding never carries the
    machine's absolute location."""
    ap = os.path.abspath(path)
    return os.path.relpath(ap, ROOT) if ap.startswith(ROOT + os.sep) else path


STATIC_INCLUDE = re.compile(r'<%@\s*include\s+file\s*=\s*"([^"]+)"\s*%>')


def expand_includes(path, text, depth=0):
    """
    The page with its static <%@ include file="..." %> fragments inlined, so a
    bean id, a message id or a CSRF nonce declared in a shared fragment counts
    for every page that pulls it in. Cycles and a missing target are left as-is.
    """
    if depth > 6:
        return text

    base = path if os.path.isabs(path) else os.path.join(ROOT, path)

    def repl(m):
        rel = m.group(1)
        if rel.startswith(('/', 'http')):
            return m.group(0)
        frag = os.path.normpath(os.path.join(os.path.dirname(base), rel))
        if not os.path.isfile(frag):
            return m.group(0)
        body = read(frag)
        if body is None:
            return m.group(0)
        return expand_includes(frag, body, depth + 1)

    return STATIC_INCLUDE.sub(repl, text)


PO_ENTRY = re.compile(
    r'^(?:msgctxt|msgid)\s+(?P<strings>"(?:[^"\\]|\\.)*"(?:\s+"(?:[^"\\]|\\.)*")*)',
    re.M)


def po_msgids(locale_dir):
    """
    Every msgid and msgctxt in an app's .po files, unescaped and with wrapped
    entries reassembled. A msgid is split across source lines by gettext for
    long strings, and a JSP may split it the same way, so both sides have to be
    flattened before they can be compared.
    """
    keys = set()
    if not os.path.isdir(locale_dir):
        return keys
    for fn in sorted(os.listdir(locale_dir)):
        if fn.endswith('.po'):
            text = read(os.path.join(locale_dir, fn)) or ''
            for m in PO_ENTRY.finditer(text):
                parts = [java_string_unescape(s) for s in re.findall(r'"((?:[^"\\]|\\.)*)"',
                                                                     m.group('strings'))]
                keys.add(''.join(parts))
    return keys


JSTL_SCOPED = re.compile(
    r'<c:(?:forEach|forTokens|set|param|when|if|catch|out)\b[^>]*?'
    r'\b(?:var|varStatus|target|name)\s*=\s*["\'](?P<name>[\w$]+)["\']',
    re.S)


def jstl_vars(text):
    """
    Names a JSTL tag puts into EL scope for the rest of the page: c:forEach and
    c:forTokens loop variables and their varStatus, c:set and c:param targets, and
    the target of c:when / c:if / c:catch / c:out. A page that iterates
    <c:forEach var="addr" ...> then writes ${addr.host} without any bean behind
    it, so these names are legitimate EL roots.
    """
    return set(m.group('name') for m in JSTL_SCOPED.finditer(text))


def directive_attrs(text):
    """Every directive's attributes, as (kind, {attr: value}) with the match span."""
    out = []
    for m in DIRECTIVE.finditer(text):
        body = m.group('body')
        kind = body.strip().split(None, 1)[0].strip().lower()
        attrs = {}
        for a in ATTR.finditer(body):
            attrs.setdefault(a.group('name'), a.group(2))
        out.append((kind, attrs, m.start()))
    return out


def tag_attrs(tag_text):
    attrs = {}
    for a in ATTR.finditer(tag_text):
        attrs.setdefault(a.group('name'), a.group(2))
    return attrs


# The elements HTML is allowed to leave open, so an end tag for something else
# while one of them is open is normal nesting and not a mis-nesting.
HTML_OPTIONAL_CLOSE = {k for k, v in HTML_IMPLICIT_CLOSE.items() if k in v or k in
                       {'p', 'li', 'td', 'th', 'tr', 'thead', 'tbody', 'tfoot',
                        'caption', 'colgroup', 'option', 'optgroup', 'dt', 'dd'}}
# The elements whose end tag HTML may omit, so leaving one open is not a defect:
# the ones closed by the next sibling, plus the document elements a page may leave
# open at its end.
HTML_OMITTABLE = HTML_OPTIONAL_CLOSE | {'html', 'head', 'body'}
# Elements an HTML parser creates whether or not the page writes them, so an end
# tag for one with no matching start is untidy rather than broken.
HTML_SYNTHESIZED = {'html', 'head', 'body'}


# The Java keywords whose block a page's markup can sit inside.
JAVA_BRANCH = re.compile(r'\b(if|else|for|while|do|switch|try|catch|finally|'
                         r'synchronized)\b')


def _branch_key(body, brace):
    """
    Name the branch that the '{' at brace opens: the control statement that owns it.

    A scriptlet is a run of Java, so the text between the previous brace and this
    one is usually several unrelated statements - ssl.jsp's header scriptlet puts
    six declarations between `} catch (NumberFormatException nfe) {}` and the next
    `if`. The owner is the last control keyword before the brace, and an `else if`
    is named from the `else` so the arm is recognizable as an alternative.
    """
    ms = list(JAVA_BRANCH.finditer(body[:brace]))
    if not ms:
        return ''
    m = ms[-1]
    if (m.group(1) == 'if' and len(ms) > 1 and ms[-2].group(1) == 'else'
            and not body[ms[-2].end():m.start()].strip()):
        m = ms[-2]
    return re.sub(r'\s+', ' ', body[m.start():brace]).strip()


def _scriptlet_guards(body, off):
    """
    The markup and the control flow one scriptlet or expression contributes.

    Yields push/pop markers for the Java control flow the scriptlet opens or closes,
    and start/end markers for the markup written inside its string literals, all in
    document order.

    The house form is `<% if (x) { %>` ... `<% } %>`, but a scriptlet may hold
    several statements (`<% } else if (x) { %>`, or a loop whose body is Java rather
    than markup), so the braces are counted in order across the whole body. They are
    counted in the string- and comment-stripped text, so a brace inside a string
    literal cannot move the stack, while the branch is named from the raw source -
    `if (!"new".equals(tunnelType))`, not the blanked `if (! .equals(...))` - because
    that is the text a reader recognizes and greps for.

    The literals are read for markup because what they write reaches the response:
    index.jsp closes its <span class=tunnelDestinationLabel> inside
    out.write(":</b></span> ..."), and help/ui.jsp opens the <b> of '<b>Note' inside
    a translation string. A page whose tags balance only because a write was ignored
    is not a clean page, and one reported broken because a write was ignored is not
    a finding.
    """
    code = _blank(body)
    events = []
    for k, ch in enumerate(code):
        if ch == '}':
            events.append((k, ('pop', '', off + k, None)))
        elif ch == '{':
            events.append((k, ('push', _branch_key(body, k), off + k, None)))
    regions = _output_regions(body, code)
    for start, stop in _java_strings(body):
        if not any(a <= start < b for a, b in regions):
            # a literal that is only assigned to a variable or passed to a bean is
            # not output here, and its tags belong to wherever the value is written
            continue
        for tok in _literal_tags(body[start:stop], off + start):
            events.append((tok[2] - off, tok))
    for _, tok in sorted(events, key=lambda pair: pair[0]):
        yield tok


# What writes to the response from inside a scriptlet.
OUTPUT_CALL = re.compile(r'\b(?:out|writer)\s*\.\s*(?:print|write)\s*\(')


def _output_regions(body, code):
    """
    The parts of a scriptlet whose text reaches the response, as (start, end) spans.

    A `<%= expression %>` writes its value, so everything in it is output. In a
    scriptlet, only the arguments of out.write() and out.print() are - a string
    assigned to a variable is markup somewhere else and sometime later, which is why
    ssl.jsp building `String url = "..." + "</a>"` at line 84 is not an end tag at
    line 84. The calls are found in the blanked text, so a commented-out write does
    not count, and the offsets still line up with the file.
    """
    if body.lstrip().startswith('='):
        return [(0, len(body))]
    spans = []
    for m in OUTPUT_CALL.finditer(code):
        spans.append((m.end(), _close_paren(code, m.end() - 1)))
    return spans


def _close_paren(code, i):
    """Index of the ')' that closes the '(' at i of blanked Java text, or len(code)."""
    depth = 0
    for k in range(i, len(code)):
        if code[k] == '(':
            depth += 1
        elif code[k] == ')':
            depth -= 1
            if depth == 0:
                return k
    return len(code)


def _java_strings(body):
    """
    The contents of the Java string and character literals of a scriptlet, as
    (start, end) offsets into the body.

    The same walk _blank() uses, so these are exactly the regions whose braces were
    ignored: comments skipped, escapes honoured.
    """
    spans = []
    n = len(body)
    i = 0
    while i < n:
        c = body[i]
        if c == '/' and body[i + 1:i + 2] == '/':
            j = body.find('\n', i)
            i = n if j < 0 else j
        elif c == '/' and body[i + 1:i + 2] == '*':
            j = body.find('*/', i + 2)
            i = n if j < 0 else j + 2
        elif c in '"\'':
            j = i + 1
            while j < n and body[j] != c:
                j += 2 if body[j] == '\\' else 1
            spans.append((i + 1, min(j, n)))
            i = j + 1
        else:
            i += 1
    return spans


LITERAL_TAG = re.compile(r'</?([A-Za-z][\w:.\-]*)')


def _literal_tags(text, base):
    """
    The markup tokens written inside one Java string literal, at file offsets.

    Only the tag name is read: a page built by concatenation splits a start tag
    across literals (`out.write("<a href=\"")` then the value then `out.write("\">")`),
    so an attribute region that stops at the end of the literal means nothing. A name
    HTML does not have is ignored, because Java text is full of `<` - `i<len` is a
    comparison, not a <len> element.
    """
    for m in LITERAL_TAG.finditer(text):
        name = m.group(1).lower()
        if name in HTML_NAMES:
            yield ('end' if text[m.start() + 1] == '/' else 'start', name,
                   base + m.start(), None)


def _skip_quoted(text, k):
    """
    Index just past the quoted value that starts at text[k], or len(text) if the
    file ends before the value does.

    A <% ... %> inside the value is opaque: Jasper copies the template text and
    splices the expression's result, so the quotes that delimit the Java string
    never reach the response. Without that, configreseed.jsp's
    title="<%=intl._t("Create reseed file")%>" ends its attribute at the quote
    before 'Create', and the apostrophe of "I2P's" then swallows the rest of the
    line as a second quoted value.
    """
    quote = text[k]
    i, n = k + 1, len(text)
    while i < n:
        ch = text[i]
        if ch == quote:
            return i + 1
        if ch == '<' and text[i + 1:i + 2] == '%':
            end = text.find('%>', i + 2)
            if end < 0:
                return n
            i = end + 2
            continue
        i += 1
    return n


def _tag_end(text, k):
    """
    Index of the '>' that ends the tag whose attribute region starts at k, and
    whether the tag is written self-closing. len(text) means the file ends first.

    Quoted values are read whole, so a '>' inside one cannot end the tag; a
    <% ... %> inside the tag is opaque for the same reason; and a JSP action
    element written inside the start tag is skipped by _skip_nested, so the '>'
    that ends the inner tag does not end the outer one.
    """
    n = len(text)
    selfclosed = False
    while k < n:
        ch = text[k]
        if ch in '"\'':
            k = _skip_quoted(text, k)
            continue
        if ch == '<':
            nxt = text[k + 1:k + 2]
            if nxt == '%':
                end = text.find('%>', k + 2)
                k = n if end < 0 else end + 2
            elif nxt.isalpha() or nxt == '/':
                k = _skip_nested(text, k)
            else:
                k += 1
            continue
        if ch == '>':
            return k, selfclosed
        if ch == '/' and text[k + 1:k + 2] == '>':
            return k, True
        k += 1
    return n, selfclosed


def _skip_nested(text, k):
    """
    Index just past the element that starts at text[k] == '<', including its body
    and its matching end tag.

    The tree writes a JSTL action inside a start tag to make an attribute
    conditional - addressbook.jsp's <html<c:if test="${book.isEmpty}">
    class=emptybook</c:if>> and its <input ... <c:if test="${book.isEmpty}">
    disabled</c:if>>. The '>' of the inner tag is not the end of the outer one, so
    the whole inner element is skipped, counting same-name nesting so an inner
    </c:if> cannot close an outer one early.
    """
    j = k + 1
    closing = text[j:j + 1] == '/'
    if closing:
        j += 1
    m = TAG_NAME.match(text, j)
    if not m:
        return k + 1
    name = m.group(0).lower()
    end, selfclosed = _tag_end(text, m.end())
    if end >= len(text) or closing or selfclosed:
        return len(text) if end >= len(text) else end + 1
    depth, i = 1, end + 1
    while i < len(text) and depth:
        lt = text.find('<', i)
        if lt < 0:
            return len(text)
        inner_closing = text[lt + 1:lt + 2] == '/'
        nm = TAG_NAME.match(text, lt + 2 if inner_closing else lt + 1)
        if not nm:
            i = lt + 1
            continue
        if nm.group(0).lower() != name:
            # some other element inside it: skip that one whole, so its end tag
            # is never mistaken for the one we are looking for
            i = _skip_nested(text, lt)
            continue
        e2, sc = _tag_end(text, nm.end())
        if e2 >= len(text):
            return len(text)
        if inner_closing:
            depth -= 1
        elif not sc:
            depth += 1
        i = e2 + 1
    return i


def html_tokens(text):
    """
    Yield the live markup of a page, always as a 4-tuple: ('start', name, offset,
    selfclosed), ('end', name, offset, None), ('push'|'pop', key, offset, None) for
    a Java or JSTL branch, and ('unterminated', name, offset, None) for a tag the
    file never ends.

    <%-- --%> bodies are already blanked by the caller. Scriptlets are read for
    their control flow and skipped as markup; HTML comments, CDATA and doctypes are
    skipped whole, as are <script> and <style> bodies, where a '<' is JavaScript or
    CSS rather than a tag. The tag's attribute region is read by _tag_end, which
    knows quoted values, <% ... %> expressions and a JSTL action written inside
    the start tag.
    """
    i, n = 0, len(text)
    while i < n:
        lt = text.find('<', i)
        if lt < 0:
            break
        nxt = text[lt + 1:lt + 2]
        if nxt == '%':
            end = text.find('%>', lt + 2)
            if end < 0:
                yield ('unterminated', '%', lt, None)
                return
            for g in _scriptlet_guards(text[lt + 2:end], lt + 2):
                yield g
            i = end + 2
            continue
        if nxt == '!':
            if text.startswith('<!--', lt):
                end = text.find('-->', lt + 4)
                i = n if end < 0 else end + 3
            else:
                end = text.find('>', lt)
                i = n if end < 0 else end + 1
            continue
        if not (nxt.isalpha() or nxt == '/'):
            i = lt + 1
            continue
        j = lt + 1
        closing = text[j:j + 1] == '/'
        if closing:
            j += 1
        m = TAG_NAME.match(text, j)
        if not m:
            i = lt + 1
            continue
        name = m.group(0).lower()
        k, selfclosed = _tag_end(text, m.end())
        if k >= n:
            yield ('unterminated', name, lt, None)
            return
        yield ('end' if closing else 'start', name, lt, selfclosed)
        if not closing and not selfclosed and name in HTML_RAWTEXT:
            mend = RAWTEXT_END[name].search(text, k)
            i = mend.start() if mend else n
        else:
            i = k + 1


def html_segments(app, path, text, depth=0, seen=()):
    """
    The page split at every static <%@ include file="..." %> and reassembled in
    document order, as [(path, file text, start, end), ...] - each piece of the
    rendered page, with where it sits in the file it came from.

    Markup balance is a property of the rendered page, not of one file: head.jsi
    opens <head> and the page closes it, and a .jsi panel may open a <div> the
    including page closes. Checking each file on its own reports those as stray end
    tags and unclosed elements, so the fragments are walked in place instead. A
    missing target or a cycle is left as the directive, which is not markup.
    """
    out = []
    pos = 0
    for m in INCLUDE.finditer(text):
        out.append((path, text, pos, m.start()))
        pos = m.end()
        if depth > 6:
            continue
        rel = m.group('file')
        target = resolve_include(app['jsp'], path, rel)
        if not os.path.isfile(target) or os.path.abspath(target) in seen:
            continue
        itext = strip_jsp_comments(read(target) or '')
        out.extend(html_segments(app, target, itext, depth + 1,
                                 seen + (os.path.abspath(path),)))
    out.append((path, text, pos, len(text)))
    return out


def _guard_phrase(opened, closed):
    """
    How two guard chains differ, in the words a reader wants.

    Everything the chains share is the same surrounding code, so only the branches
    past their common prefix are worth printing: `opened in if (a), closed in if
    (b)`, or `opened in if (a), closed outside it` when one side simply left the
    branch the other is still inside.
    """
    a = [g for g in opened if g]
    b = [g for g in closed if g]
    p = 0
    while p < len(a) and p < len(b) and a[p] == b[p]:
        p += 1
    ta, tb = a[p:], b[p:]
    if ta and tb:
        return 'opened in %s, closed in %s' % (ta[-1], tb[-1])
    if ta:
        return 'opened in %s, closed outside it' % ta[-1]
    if tb:
        return 'closed in %s, opened outside it' % tb[-1]
    return 'opened and closed under different branches'


def _token_line(tok):
    """The line a token sits on in the file it came from."""
    return tok[1][:tok[4]].count('\n') + 1 if tok[4] > 0 else 1


def _find_open(stack, name):
    """Index of the innermost open element called name, or None if none is open."""
    for i in range(len(stack) - 1, -1, -1):
        if stack[i][0] == name:
            return i
    return None


def _guard_inherit(guards, key):
    """
    The construct id that a JSTL arm shares with the construct it belongs to.

    c:when and c:otherwise are alternatives of one c:choose, so they take the id of
    the open c:choose rather than a new one: an element every arm opens is not
    conditional on which arm ran.
    """
    for g in reversed(guards):
        if g['key'] == key:
            return g['cid']
    return None


def _branch_survivors(stack, entry, cid):
    """
    The elements a finished branch leaves open, marked with the branch that opened them.

    Anything the branch closed had been open before it, so a sibling arm may close it
    too and it goes back on the stack unchanged. Anything the branch opened stays,
    marked with the branch's id: that element is in the output only when this branch
    runs, so an end tag for it outside the branch is the defect. An element every arm
    of the construct opens is not conditional at all, so its mark is cleared.
    """
    keep = []
    for e in stack:
        if any(e is x for x in entry):
            continue
        if e[2] is None:
            e[2] = cid
        dup = next((x for x in entry + keep
                    if x[0] == e[0] and x[2] == e[2]), None)
        if dup is not None:
            # this arm opens what an earlier arm opened, so the element is not
            # conditional on which arm ran
            dup[2] = None
            continue
        keep.append(e)
    return keep


def _crossed_finding(nm, name, idx, ends, allowed, closed_in_branch, start_line,
                     end_line):
    """
    What an element still open when an outer element closes has done, or None.

    Elements HTML closes by itself are forgiven, as is one some branch has already
    closed - this path may be the one that did. If the element's own end tag comes
    later in the file the two elements cross over; if it never comes, it is unclosed.
    The key names both places, because identity is rule + file + key and two elements
    of the same kind left open at two different lines are two defects, not one.
    """
    if nm in allowed or closed_in_branch:
        return None
    if any(p > idx for p in ends.get(nm, ())):
        return ('html-tag-mismatch',
                '</%s> at line %d closes <%s> early' % (name, end_line, nm))
    return ('html-tag-unclosed',
            '<%s> at line %d not closed before </%s>' % (nm, start_line, name))


def _open_is_forgiven(e):
    """
    True when an element still open at the end of a page is not a defect.

    HTML omits its end tag; it was already reported as conditional; more than one
    branch closed it, so every path through the page closes it once; or the one
    branch that closed it runs under exactly the conditions its start sits under.
    """
    return (e[0] in HTML_OMITTABLE or e[4] or len(e[5]) >= 2 or e[3] in e[5])


def _open_element(tok, idx, stack, guards, open_branch):
    """
    Handle a start tag: close what HTML closes implicitly, open a JSTL branch, or
    put the element on the stack. idx is the token's position in the page's token
    list, which is where an unclosed element is later reported from.
    """
    name, selfclosed = tok[3], tok[5]
    for inner in HTML_IMPLICIT_CLOSE.get(name, ()):
        while stack and stack[-1][0] == inner:
            stack.pop()
    if name in HTML_GUARD_TAGS:
        if not selfclosed:
            open_branch('tag:' + name,
                        _guard_inherit(guards, 'tag:c:choose')
                        if name in ('c:when', 'c:otherwise') else None)
        return
    if selfclosed or name in HTML_VOID:
        return
    stack.append([name, idx, None, tuple(g['key'] for g in guards), False, [], None])


def _close_element(tok, idx, toks, stack, guards, ends, note, close_branch,
                   cond_note, annotate):
    """
    Handle an end tag: close the element it matches, and report what that close says
    about the elements that were still open above it.
    """
    spath, stext, name, off = tok[0], tok[1], tok[3], tok[4]
    # the key names the line, so two stray end tags of the same kind on two lines are
    # two findings; the report shows the tag itself, the line is already in front of it
    site = '</%s> at line %d' % (name, _token_line(tok))
    shown = '</%s>' % name
    if name in HTML_VOID:
        note('html-void-end-tag', spath, stext, off, site, shown, name=name)
        return
    if name in HTML_GUARD_TAGS:
        # the end tag closes the JSTL branch itself, not an element: the
        # action tag was never put on the element stack
        at_g = None
        for gi in range(len(guards) - 1, -1, -1):
            if guards[gi]['key'] == 'tag:' + name:
                at_g = gi
                break
        if at_g is None:
            note('html-tag-stray-end', spath, stext, off, site, shown, name=name)
            return
        while len(guards) > at_g:
            close_branch()
        return
    at = _find_open(stack, name)
    if at is None:
        note('html-tag-stray-end', spath, stext, off, site, shown, name=name)
        return
    # What is still open above the match is forgiven only where HTML closes
    # that kind of element by itself - a </table> with a <td> open is normal.
    allowed = HTML_IMPLICIT_CLOSE.get(name, set()) | HTML_OMITTABLE
    for inner in range(len(stack) - 1, at, -1):
        nm, ioff = stack[inner][0], stack[inner][1]
        found = _crossed_finding(nm, name, idx, ends, allowed, bool(stack[inner][5]),
                                 _token_line(toks[ioff]), _token_line(tok))
        if found is None:
            continue
        rule, key = found
        if stack[inner][6] is not None:
            # An earlier conditional close already took this element's end tag, so
            # the finding that caused it names this site instead of a second report.
            annotate(stack[inner][6][0], stack[inner][6][1], stack[inner][6][2],
                     '<%s> at line %d unbalanced by the same mismatch'
                     % (nm, _token_line(toks[ioff])))
            break
        if rule == 'html-tag-unclosed':
            # reported against the start tag, which is where the fix goes
            note(rule, spath, stext, toks[ioff][4], key, name=nm)
        else:
            note(rule, spath, stext, off, key, name=nm)
        break
    entry = stack[at]
    chain, now = entry[3], tuple(g['key'] for g in guards)
    # The start is inside a condition the end is not under, so the element
    # is in the output only when that branch runs and the end tag is then
    # stray. Comparing the guard text, not just which branch it was, keeps
    # details.jsp quiet: it opens its <form> in `if (showNotes)`, writes the
    # rows in a second `if (showNotes)` and closes it in a third, and every
    # one of those runs together. An element opened outside every branch is
    # unconditional and never earns this, which is also what keeps head.jsi's
    # conditional <html>, <head> and <body> - the form 63 console pages use
    # on purpose - quiet.
    if (entry[2] is not None and chain != now
            and name not in HTML_OMITTABLE):
        entry[4] = True
        root = cond_note(spath, stext, off, name, entry, chain, now)
        # Everything this element sits inside is now one end tag short, and that is
        # this mismatch's doing, not a second defect: mark those ancestors so they
        # join this finding instead of each becoming its own report.
        if root is not None:
            for e in stack[:at]:
                if e[0] == name and e[6] is None:
                    e[6] = root
    # remember the conditions this close happened under: the element goes
    # back on the stack when the branch ends, and a later arm may be the one
    # that really closed it
    entry[5].append(now)
    del stack[at:]


def check_html(app, path, text, findings):
    """
    Report HTML markup that does not balance, and an element whose start tag and end
    tag sit under different branches.

    A page is one HTML document, so an element left open at the end of it, an end
    tag with nothing open to close, or an element closed inside the wrong one is
    broken markup in every branch. Elements whose end tag HTML omits - <p>, <li>,
    <td>, <tr>, <option>, and <html>, <head>, <body> at the end of a page - are not
    reported for staying open, because the tree omits them deliberately.

    A branch is an alternative, not a step in a sequence. editServer.jsi opens a
    <th>, then writes </th><th></th> in the if arm and </th><th>...</th> in the else
    arm: each arm is balanced on its own, and a linear read of the file sees the
    second arm closing a <th> the first arm already closed. So when a branch ends,
    the open-element stack is put back as it was when the branch began, and only
    what the branch itself opened stays on it - marked conditional, because it is in
    the output only when that branch runs. That is also what finds the real defect
    in netdb.jsp: its <div> is opened under one condition and closed under a
    slightly different one, so one arm of the page is malformed.
    """
    toks = []
    for spath, stext, start, end in html_segments(app, path, text):
        for t in html_tokens(stext[start:end]):
            # offsets are absolute in the file the piece came from, so a finding
            # names the right line of the right file
            toks.append((spath, stext, t[0], t[1], start + t[2], t[3]))
    # Token index of every end tag by name, so an element still open when an outer
    # element closes can be told apart from one that is simply closed too early.
    ends = {}
    for idx, t in enumerate(toks):
        if t[2] == 'end':
            ends.setdefault(t[3], []).append(idx)
    # open elements: [name, token index, id of the branch its start sits in,
    #                guard chain at its start, already reported as conditional,
    #                ids of the branches that closed it, the finding that already
    #                explains why its end tag never arrived]
    stack = []
    # open scriptlet and JSTL branches: key text, construct id, stack on entry
    guards = []
    seen = set()
    truncated = [False]        # the file stopped in the middle of a tag
    state = {'next_cid': 0, 'last_cid': None}
    # (file, element, line opened) -> (rule, key) of the conditional finding made
    # for it, so a second arm and the ancestors it left short join that one report
    cond_roots = {}

    def note(rule, spath, stext, off, key, msg=None, name=None):
        # html, head and body are synthesized by the parser, so an end tag for one
        # that the page never opened - deleteHosts.jsp writes <head> with no <html> -
        # is untidy rather than broken
        if rule == 'html-tag-stray-end' and name in HTML_SYNTHESIZED:
            return
        if (rule, spath, key) in seen:
            return
        seen.add((rule, spath, key))
        findings.add(rule, spath, stext, off, key, msg)

    def open_branch(key, inherit=None):
        """Start a branch, or an alternative arm of the construct 'inherit' names."""
        cid = state['next_cid'] if inherit is None else inherit
        state['next_cid'] += 1
        guards.append({'key': key, 'cid': cid, 'entry': stack[:]})
        state['last_cid'] = None

    def close_branch():
        """End a branch: put the open-element stack back as it was when it began."""
        if not guards:
            return
        frame = guards.pop()
        entry = frame['entry']
        stack[:] = entry + _branch_survivors(stack, entry, frame['cid'])
        state['last_cid'] = frame['cid']

    def cond_note(spath, stext, off, name, entry, chain, now):
        """
        Report an element whose end tag sits under a different condition than its
        start, and return what identifies that report.

        One element mis-bracketed against a condition is one defect even when the
        page closes it under two of them: netdb.jsp opens its <div> under one test
        and writes two closes under different tests, and the second close is the
        same mistake repeated, not a second one. So a later close of the same start
        tag joins the report that already explains it.
        """
        start = _token_line(toks[entry[1]])
        ident = ('html-conditional-tag', spath,
                 '<%s> at line %d %s -> %s'
                 % (name, start, '|'.join(chain), '|'.join(now)))
        root = cond_roots.get((spath, name, start))
        if root is not None:
            annotate(root[0], root[1], root[2],
                     '</%s> at line %d closes the same start tag again'
                     % (name, _token_line(tok)))
            return root
        note('html-conditional-tag', spath, stext, off, ident[2],
             '<%s> %s' % (name, _guard_phrase(chain, now)), name=name)
        cond_roots[(spath, name, start)] = ident
        return ident

    def annotate(rule, spath, key, text):
        """Add a site to a finding already made rather than reporting it again."""
        return findings.annotate(spath, rule, key, text)

    for idx, tok in enumerate(toks):
        kind, name = tok[2], tok[3]
        if kind == 'push':
            # `} else {` and `} else if {` are arms of the branch that just closed
            open_branch(name, state['last_cid'] if name.startswith('else') else None)
        elif kind == 'pop':
            close_branch()
        elif kind == 'unterminated':
            # The file stops in the middle of a tag, so nothing after it can be read
            # as markup: report where it broke and leave the balance alone. dns.jsp's
            # href="src="<%=url%>" is this - the quote inside the value opens a value
            # that never closes, and the <div> and <iframe> before it only look
            # unclosed because the scan ran on past the anchor.
            note('html-tag-unterminated', tok[0], tok[1], tok[4],
                 '<%s> at line %d' % (name, _token_line(tok)),
                 '<%s> never closes - check its attribute quoting' % name,
                 name=name)
            truncated[0] = True
            break
        elif kind == 'start':
            _open_element(tok, idx, stack, guards, open_branch)
        elif kind == 'end':
            _close_element(tok, idx, toks, stack, guards, ends, note, close_branch,
                           cond_note, annotate)
    if path.endswith('.jsi') or truncated[0]:
        # a fragment is only ever rendered inside a page, and the page is where an
        # element it leaves open gets closed - or fails to be, which is reported
        # there, against the page that includes it. A file that stopped mid-tag has
        # already said what it has to say.
        return
    for e in stack:
        if _open_is_forgiven(e):
            continue
        t = toks[e[1]]
        if e[6] is not None:
            # An element whose end tag was taken by a conditional close somewhere
            # else on the page is not a second unbalanced element: it is what the
            # first report already says.
            annotate(e[6][0], e[6][1], e[6][2],
                     '<%s> at line %d is unbalanced by the same mismatch'
                     % (e[0], _token_line(t)))
            continue
        if e[5]:
            # exactly one branch closes it, so every path where that branch does not
            # run leaves the element open
            msg = '<%s> closed only inside %s' % (e[0], e[5][0][-1] if e[5][0] else '?')
        elif e[2] is not None:
            msg = '<%s> opened in %s, never closed' % (e[0], e[3][-1] if e[3] else '?')
        else:
            msg = '<%s>' % e[0]
        note('html-tag-unclosed', t[0], t[1], t[4],
             '<%s> at line %d' % (e[0], _token_line(t)), msg, name=e[0])


def check_page(app, path, text, java, known_roots, msgids, findings, blind,
               app_bean_ids=()):
    base = app['jsp']
    # a <%-- --%> body is not live markup: it declares no beans and needs no translation
    text = strip_jsp_comments(text)
    # beans declared in this page, id -> (class fqn, offset)
    beans = {}
    for m in USEBEAN.finditer(text):
        attrs = tag_attrs(m.group(0))
        ident = attrs.get('id')
        cls = attrs.get('class') or attrs.get('type')
        if ident and cls:
            beans.setdefault(ident, []).append((cls, m.start()))
    # static includes are part of the same page for scoping purposes
    included_ids = set()
    for m in INCLUDE.finditer(text):
        rel = m.group('file')
        target = resolve_include(base, path, rel)
        if not os.path.isfile(target):
            findings.add('include-missing', path, text, m.start(), rel)
        else:
            itext = strip_jsp_comments(read(target) or '')
            for um in USEBEAN.finditer(itext):
                ua = tag_attrs(um.group(0))
                if ua.get('id'):
                    included_ids.add(ua['id'])
            included_ids.update(jstl_vars(itext))
    # JSTL loop and set variables are in scope for the rest of the page
    included_ids.update(jstl_vars(text))
    for m in SET_ATTR.finditer(text):
        included_ids.add(m.group(1))
    # A .jsi is only ever rendered inside a page, and that page — not the fragment —
    # is where its beans are declared. formhandler.jsi reads formhandler.allMessages
    # while each config page declares its own handler bean, so an id declared
    # anywhere in the app is in scope in a fragment.
    if path.endswith('.jsi'):
        included_ids.update(app_bean_ids)

    # pageEncoding
    if 'pageEncoding' not in text and path.endswith('.jsp'):
        findings.add('page-encoding-missing', path, text, 0, os.path.basename(path))

    # duplicate useBean ids
    for ident, decls in beans.items():
        if len(decls) > 1:
            findings.add('usebean-dup-id', path, text, decls[1][1], ident)

    # jsp:getProperty / jsp:setProperty
    for m in PROP_USE.finditer(text):
        attrs = tag_attrs(m.group(0))
        ident, prop = attrs.get('name'), attrs.get('property')
        if not ident or not prop or prop == '*':
            continue
        decl = beans.get(ident)
        if decl is None:
            if ident not in included_ids:
                findings.add('bean-id-undeclared', path, text, m.start(),
                             '%s.%s' % (ident, prop))
            continue
        cls, decl_off = decl[0]
        if decl_off > m.start():
            findings.add('bean-id-order', path, text, m.start(),
                         '%s.%s' % (ident, prop))
        want_set = '<jsp:setProperty' in m.group(0)
        bpath = java.resolve(cls)
        if bpath is None:
            blind.add(cls)
            continue
        if not bean_property_ok(java, bpath, prop, want_set):
            findings.add('bean-prop-unknown', path, text, m.start(),
                         '%s (%s).%s' % (ident, cls, prop))

    # EL: ${bean.prop} and unknown roots
    for m in EL_USE.finditer(text):
        expr = m.group('expr')
        root_m = EL_ROOT.match(expr)
        if not root_m:
            continue
        root = root_m.group('root')
        if root in EL_IMPLICIT:
            continue
        if root not in beans and root not in included_ids and root not in known_roots:
            findings.add('el-unknown-root', path, text, m.start(), root)
        path_m = EL_PATH.match(expr)
        if path_m and root in beans:
            cls = beans[root][0][0]
            bpath = java.resolve(cls)
            if bpath is None:
                blind.add(cls)
                continue
            chain = path_m.group(2) + (path_m.group('call') or '')
            cur_path = bpath
            walked = []
            for st in EL_STEP.finditer(chain):
                prop = st.group('prop')
                walked.append(prop + ('()' if st.group('call') else ''))
                if cur_path is None:
                    break
                if st.group('call'):
                    if not java.has_method(cur_path, prop):
                        findings.add('bean-prop-unknown', path, text, m.start(),
                                     '%s (%s).%s' % (root, cls, '.'.join(walked)))
                        break
                    cur_path = None  # a method call's return type is not tracked
                elif not bean_property_ok(java, cur_path, prop, False):
                    findings.add('bean-prop-unknown', path, text, m.start(),
                                 '%s (%s).%s' % (root, cls, '.'.join(walked)))
                    break
                else:
                    ptype = property_type(java, cur_path, prop)
                    cur_path = java.resolve(ptype) if ptype else None
                    if ptype and cur_path is None:
                        blind.add(ptype)

    # links to .jsp pages
    urls = set()
    for m in LINK.finditer(text):
        url = m.group('url')
        if '${' in url or '<%' in url or url.startswith(('#', 'http', 'https', '//')):
            continue
        if not LINK_JSP.search(url.split('?')[0]):
            continue
        urls.add(url.split('?')[0])
    for url in sorted(urls):
        if url.startswith('/'):
            target = os.path.join(base, url[1:])
        else:
            target = os.path.normpath(os.path.join(os.path.dirname(path), url))
        if not os.path.isfile(target):
            findings.add('jsp-link-missing', path, text, text.find(url), url)

    # i18n msgids
    for m in I18N.finditer(text):
        lits = java_string_concatenations(m.group('args'))
        if not lits:
            continue
        if not any(lit in msgids for lit in lits):
            findings.add('msg-key-missing', path, text, m.start(), lits[0])

    # hygiene
    raw = open(path, 'rb').read()
    lines = raw.split(b'\n')
    for i, line in enumerate(lines):
        stripped = line.rstrip(b'\r')
        if stripped != stripped.rstrip():
            findings.add('trailing-whitespace', path, raw.decode('utf-8', 'replace'),
                         _line_off(raw, i), 'line %d' % (i + 1))
        if b'\t' in stripped:
            findings.add('tab-indent', path, raw.decode('utf-8', 'replace'),
                         _line_off(raw, i), 'line %d' % (i + 1))

    # markup balance, over the comment-blanked text so a commented-out block
    # cannot supply a tag that the page never emits
    check_html(app, path, text, findings)


def _line_off(raw, i):
    return len(raw.split(b'\n')[:i]) and sum(len(x) + 1 for x in raw.split(b'\n')[:i])


class Findings:
    def __init__(self):
        self.items = []
        self._seen = set()

    def add(self, rule, path, text, offset, key, msg=None):
        # Identity is rule + file + normalized expression, never the line, so a
        # fragment included by three pages is reported once, not three times, and a
        # report does not churn when code above a finding moves. 'msg' is what the
        # report shows; 'key' is what identifies the finding, and for the markup
        # rules it carries the full guard chains so two sites never collide.
        ident = (rule, repo_rel(path), key)
        if ident in self._seen:
            return
        self._seen.add(ident)
        line = text[:offset].count('\n') + 1 if offset > 0 else 1
        self.items.append({'rule': rule, 'file': repo_rel(path), 'line': line,
                           'key': key, 'msg': msg if msg is not None else key})

    def annotate(self, path, rule, key, text):
        """
        Add a dependent site to a finding already made, instead of reporting it again.

        One unbalanced element has consequences: an element whose close sits under the
        wrong condition also leaves its own ancestors open, and an element closed in
        two arms has two closes to name. Those are the same defect, so they join the
        finding that explains them rather than becoming separate reports a reader has
        to work out are one.
        """
        want = (rule, repo_rel(path), key)
        for i in self.items:
            if (i['rule'], i['file'], i['key']) == want:
                if text not in i['msg']:
                    i['msg'] += '; ' + text
                return True
        return False

    def keys(self):
        return set('%s\t%s\t%s' % (i['rule'], i['file'], i['key']) for i in self.items)

    def by_rule(self):
        """rule -> count, in rule order."""
        counts = {}
        for i in self.items:
            counts[i['rule']] = counts.get(i['rule'], 0) + 1
        return counts


# Each fixture is (what it pins, path, page text, the rules that must fire, and
# optionally what the report must say). The rules are a set, or a dict of rule ->
# how many times it fires when the fixture pins the count as well - that is how
# folding one defect's consequences into its report is held in place, because the
# same rules firing twice would otherwise still pass. Every one of them is a shape
# the tree really contains, and each fails without the rule it names - a checker
# that reads clean because it never looked is worse than none.
HTML_FIXTURES = [
    ('form left open (applytheme.jsp)', 'p.jsp',
     '<%@page contentType="text/html"%>\n<body>\n<form method=POST>\n'
     '<input type=hidden name=nonce value=x>\n</body>',
     {'html-tag-unclosed'}),
    ('doubled end tag (login.jsp)', 'p.jsp',
     '<body><a href=x><img src=y></a></a></body>',
     {'html-tag-stray-end'}),
    # The crossing is the finding; the late </i> is then an end tag with nothing
    # open, which is the same defect seen from the other side.
    ('closed inside the wrong element', 'p.jsp',
     '<div><b><i>x</b></i></div>',
     {'html-tag-mismatch', 'html-tag-stray-end'}),
    ('div opened and closed under different conditions (netdb.jsp)', 'p.jsp',
     '<% if ((r == null && ls != null) || l != null || ls != null) { %>'
     '<div class=leasesets_container><% } %>\n'
     '<% if ((r == null && ls != null) || l != null) { %></div><% } %>',
     {'html-conditional-tag'}),
    # One element mis-bracketed against a condition is one defect, so the second
    # close and the outer element whose end tag that close displaced are named inside
    # the report that explains them. Three lines here would read as three problems.
    ('one element closed under two conditions is one report (netdb.jsp)', 'p.jsp',
     '<div class=main id=netdb>\n'
     '<% if ((r == null && ls != null) || l != null || ls != null) { %>'
     '<div class=leasesets_container><% } %>\ncontent\n'
     '<% if ((r == null && ls != null) || l != null) { %></div><% } %>\n</div>\n'
     '</body>\n</html>\n',
     {'html-conditional-tag': 1},
     ('</div> at line 5 closes the same start tag again',
      '<div> at line 1 is unbalanced by the same mismatch')),
    ('a close outside the branch that opened the element (configplugins.jsp)', 'p.jsp',
     '<div class=main id=plugins>\n<% if (clientshelper.showPlugins()) { %>\n'
     '<div class=plugins>\n<% } %>\ncontent\n</div>\n</body>\n</html>\n',
     {'html-conditional-tag': 1},
     ('<div> at line 1 is unbalanced by the same mismatch',)),
    # an independent element left open on its own must still be its own report
    ('an independent element left open is still its own report', 'p.jsp',
     '<div class=main>\n<div class=box>\n<% if (a) { %><p>x<% } %>\n</div>\n'
     '</body>\n</html>\n',
     {'html-tag-unclosed': 1}),
    # ssl.jsp writes the same stray </td> in each of three arms. They are three
    # sites, so they are three reports: one line naming one of them leaves the
    # other two unfixed.
    ('the same stray end tag on different lines is a report each (ssl.jsp)', 'p.jsp',
     '<table><tr><td>a</td>\n<% if (x) { %></td><td></td></tr>\n<% } else { %>'
     '</td><td></td></tr>\n<% } %></table>',
     {'html-tag-stray-end': 2}),
    # help/ui.jsp: the <b> of the message text '<b>Note' is in the response, so the
    # </b> that follows the expression closes it and the page is balanced
    ('markup in a message key is output like any other (help/ui.jsp)', 'p.jsp',
     '<body><p><%=intl._t("<b>Note")%>:</b> text</p></body>',
     set()),
    # advancedsettings.jsp: the message opens a link and never closes it, and the
    # browser ends the anchor at the cell - a link that swallows the rest of the row
    ('a message key that opens a tag without closing it', 'p.jsp',
     '<body><table><tr><td><%=intl._t("See <a href=/configsidebar>. [Default false]")%>'
     '</td></tr></table></body>',
     {'html-tag-unclosed': 1}),
    # index.jsp closes the span it opened in the template from inside out.write():
    # the close is only in the Java, so a checker that skips writes reports the span
    # as left open
    ('markup written by out.write() counts as output', 'p.jsp',
     '<body><div><span class=label>x<% out.write(" and more</span>"); %></div></body>',
     set()),
    # ssl.jsp builds a link in a String and writes it later: the tags inside the
    # assignment are not output where they are written, so they are not balanced
    # there - read as output, the </a> of the first statement is stray and the <a>
    # of the second is never closed
    ('markup assigned to a variable is not output at the assignment (ssl.jsp)', 'p.jsp',
     '<body><div>x<% String url = ">" + tgt + "</a>";\n'
     '   String link = "<a href=\\"x\\">" + url; %>y</div></body>',
     set()),
    # only known element names are tags: a Java comparison inside written text must
    # not be read as an element the page never closes
    ('a java comparison in written text is not a tag', 'p.jsp',
     '<body><div><% out.write("i<len"); %></div></body>',
     set()),
    ('void elements and a > inside a quoted attribute', 'p.jsp',
     '<body><div><br><img src="a>b.png"><input type=text value="x>y"></div></body>',
     set()),
    ('p and li closed by HTML itself', 'p.jsp',
     '<body><p>one<p>two<ul><li>a<li>b</ul></body>',
     set()),
    ('end tag for a void element', 'p.jsp',
     '<body>hi</br>there</body>',
     {'html-void-end-tag'}),
    ('a < inside <script> is JavaScript, not markup', 'p.jsp',
     '<body><script>if (a > b) { document.write("<div>"); }</script></body>',
     set()),
    ('rows opened and closed inside one loop', 'p.jsp',
     '<table><% for (int i = 0; i < n; i++) { %><tr><td>x</td></tr><% } %></table>',
     set()),
    ('td closed by the next td', 'p.jsp',
     '<table><tr><td>a<td>b</tr></table>',
     set()),
    ('if/else, each branch balanced', 'p.jsp',
     '<% if (a) { %><div>x</div><% } else { %><div>y</div><% } %>',
     set()),
    # editServer.jsi: one <th> opened before the condition, and each arm writes its
    # own </th><th> pair. Read straight through, the else arm appears to close a
    # <th> the if arm already closed; as alternatives, both arms are balanced.
    ('if and else arms each close and reopen the same cell', 'p.jsp',
     '<tr><th>\n<% if ("streamrserver".equals(t)) { %>\nAccess Point\n</th>\n<th></th>\n'
     '<% } else { %>\nTarget\n</th>\n<th>Use SSL</th>\n<% } %>\n</tr>',
     set()),
    # an element closed only inside one arm is unclosed when that arm does not run
    ('closed only inside a branch', 'p.jsp',
     '<div>\n<% if (a) { %>\ncontent\n</div>\n<% } %>',
     {'html-tag-unclosed'}),
    ('every arm opens the element, closed after the branch', 'p.jsp',
     '<% if (a) { %>\n<div>\n<% } else { %>\n<div>\n<% } %>\ncontent\n</div>',
     set()),
    # details.jsp opens its <form> in one `if (showNotes)`, writes the rows in a
    # second one and closes it in a third: the same condition tested again, so the
    # three blocks always run together
    ('the same condition tested twice opens and closes the element', 'p.jsp',
     '<% if (showNotes) { %>\n<form action=x>\n<input type=submit>\n<% } %>\n'
     '<% if (showNotes) { %>\n<table><tr><td>x</td></tr></table>\n<% } %>\n'
     '<% if (showNotes) { %>\n</form>\n<% } %>',
     set()),
    # only the truncated <div is a finding: </body> may be omitted, so an open
    # <body> at the end of a page is not one
    ('a tag the file never ends', 'p.jsp',
     '<body><div class=foo',
     {'html-tag-unterminated'}),
    ('markup inside a <%-- --%> block is not live', 'p.jsp',
     '<%-- <div> --%>\n<body>ok</body>',
     set()),
    ('a fragment may leave an element open', 'f.jsi',
     '<div class=panel>\n<p>content\n',
     set()),
    ('JSTL branch balanced', 'p.jsp',
     '<c:if test="${a}"><div>x</div></c:if>',
     set()),
    # configplugins.jsp closes its branches as `} // showPlugins`, and one scriptlet
    # may close two of them at once
    ('a closing brace with a comment after it', 'p.jsp',
     '<% if (showPlugins()) { %>\n<form action=x>\n<input type=submit>\n</form>\n'
     '<% } // showPlugins %>',
     set()),
    ('one scriptlet closing two branches', 'p.jsp',
     '<% if (a) { %>\n<% if (b) { %>\n<div>x</div>\n<% } // b\n} // a %>',
     set()),
    # configreseed.jsp: the quotes that delimit the Java string are not the
    # attribute's, and the apostrophe of "I2P's" is not a quote at all
    ('a _t() call inside a quoted attribute', 'p.jsp',
     '<body><label title="<%=intl._t("This uses I2P\'s HTTP outproxy.")%>">'
     '<input type=submit></label></body>',
     set()),
    # addressbook.jsp: a JSTL action makes an attribute conditional, so the '>' of
    # the inner tag does not end the outer one
    ('a c:if inside a start tag', 'p.jsp',
     '<html<c:if test="${book.isEmpty}"> class=emptybook</c:if>>\n<body>x</body>',
     set()),
    ('a c:if inside an <input> start tag', 'p.jsp',
     '<body><form action=x><input form=exportlist type=submit '
     '<c:if test="${book.isEmpty}">disabled</c:if>></form></body>',
     set()),
    ('two c:ifs nested inside one start tag', 'p.jsp',
     '<body><div<c:if test="${a}"> class=x<c:if test="${b}"> y</c:if></c:if>>ok</div>',
     set()),
]


# Balance is a property of the rendered page, so the static includes have to be
# walked: head.jsi opens <head> and the page closes it, and a .jsi panel may open a
# <div> the including page closes. Each entry is (what it pins, the files on disk,
# the page to check, the rules that must fire, the file the finding must name).
INCLUDE_FIXTURES = [
    ('a page closes what its fragment opened',
     {'frag.jsi': '<div class=panel>\n'},
     'p.jsp', '<%@ include file="frag.jsi"%>\ncontent\n</div>\n', set(), None),
    # the console's own head.jsi form: the fragment opens <head>, the page ends it
    ('a fragment opens an element the page closes',
     {'head.jsi': '<head>\n<title>t</title>\n'},
     'p.jsp', '<html>\n<%@include file="head.jsi"%>\n<body>x</body>\n</head>\n',
     set(), None),
    # a fragment that includes a fragment, as configpage.jsi does
    ('an include of an include supplies the tag',
     {'outer.jsi': '<%@ include file="inner.jsi"%>\n<hr>\n',
      'inner.jsi': '<div class=panel>\n'},
     'p.jsp', '<%@ include file="outer.jsi"%>\ncontent\n</div>\n', set(), None),
    # the fragment is not where the defect is: the page that includes it never
    # closes the element, and that is the file the report has to name
    ('no page closes what the fragment opened',
     {'frag.jsi': '<div class=panel>\n'},
     'p.jsp', '<%@ include file="frag.jsi"%>\ncontent\n',
     {'html-tag-unclosed'}, 'frag.jsi'),
    # a fragment that balances itself is not a finding in either file
    ('a fragment that balances itself',
     {'sidebar.jsi': '<div class=sidebar><a href=x>y</a></div>\n'},
     'p.jsp', '<body>\n<%@ include file="sidebar.jsi"%>\n<p>content\n</body>\n',
     set(), None),
    # a missing target is the include-missing rule's business, not markup's
    ('a missing include is not a markup defect',
     {},
     'p.jsp', '<div>\n<%@ include file="gone.jsi"%>\ncontent\n</div>\n', set(), None),
]


def _fixture_tally(findings):
    """rule -> how many times it fired, for comparing against a fixture."""
    counts = {}
    for i in findings.items:
        counts[i['rule']] = counts.get(i['rule'], 0) + 1
    return counts


def _fixture_form(want):
    """
    A fixture expectation as text, for the line that says it did not hold.

    A fixture may name the rules that must fire (a set) or pin how many times each
    fires (a dict), which is how the deduplication of one defect's consequences is
    held in place: the same rules firing twice is a different result there.
    """
    if isinstance(want, dict):
        return ','.join('%s x%d' % (r, n) for r, n in sorted(want.items())) or 'nothing'
    return ','.join(sorted(want)) or 'nothing'


def _fixture_holds(want, counts):
    """Whether a run's rule tally satisfies a fixture's expectation."""
    if isinstance(want, dict):
        return all(counts.get(r, 0) == n for r, n in want.items()) and \
            set(counts) == set(want)
    return set(counts) == set(want)


def selftest():
    """
    Run the markup rules over HTML_FIXTURES, the include fixtures and the
    branch-naming probes.

    Compact by design: a passing fixture says nothing the reader needs, so only a
    drift prints a line, and the one summary line says how many checks ran.
    """
    failed = 0
    checks = 0
    for fixture in HTML_FIXTURES:
        what, name, text, want = fixture[:4]
        # an optional fifth field pins that a consequence is still named somewhere,
        # so folding duplicate reports cannot quietly drop one
        also = fixture[4] if len(fixture) > 4 else ()
        checks += 1
        findings = Findings()
        check_html({'jsp': '.'}, name, strip_jsp_comments(text), findings)
        counts = _fixture_tally(findings)
        missing = [s for s in also
                   if not any(s in i['msg'] for i in findings.items)]
        if _fixture_holds(want, counts) and not missing:
            continue
        failed += 1
        print('* FAIL %s: expected %s%s, got %s%s'
              % (what, _fixture_form(want),
                 ' naming %s' % ' and '.join(also) if also else '',
                 _fixture_form(counts),
                 ' (missing %s)' % ', '.join(missing) if missing else ''))
    # the branch a '{' opens is named by the control statement that owns it, not by
    # the declarations that happen to precede it in the same scriptlet
    for src, want in (
        ('if (x) {', 'if (x)'),
        ('} else {', 'else'),
        ('} else if (y) {', 'else if (y)'),
        ('} catch (NumberFormatException nfe) {} String s = "x{"; if (b) {',
         'if (b)'),
        ('} catch (IOException ioe) { ioe.printStackTrace(); } for (String s : a) {',
         'for (String s : a)'),
    ):
        checks += 1
        got = _branch_key(src, src.rindex('{'))
        if got == want:
            continue
        failed += 1
        print('* FAIL branch name %r: expected %r, got %r' % (src, want, got))
    # balance is a property of the rendered page, so these need real files on disk:
    # the fragments are read through the same resolver the pages use
    for what, files, page, body, want, where in INCLUDE_FIXTURES:
        checks += 1
        with tempfile.TemporaryDirectory() as td:
            for name, text in list(files.items()) + [(page, body)]:
                with open(os.path.join(td, name), 'w') as f:
                    f.write(text)
            findings = Findings()
            text = read(os.path.join(td, page)) or ''
            check_html({'jsp': td}, os.path.join(td, page),
                       strip_jsp_comments(text), findings)
            got = set(i['rule'] for i in findings.items)
            bad = [i for i in findings.items
                   if where and os.path.basename(i['file']) != where]
            if got != want or bad:
                failed += 1
                print('* FAIL %s: expected %s%s, got %s%s'
                      % (what, ','.join(sorted(want)) or 'nothing',
                         ' in ' + where if where else '',
                         ','.join(sorted(got)) or 'nothing',
                         ' (wrong file: %s)' % ', '.join(i['file'] for i in bad)
                         if bad else ''))
    print('* markup selftest: %d check(s), %d failure(s)' % (checks, failed))
    return 1 if failed else 0


def summary_line(label, app, count):
    """
    The one line a check says about one app, in the house '* <info>' form used
    by the other build checkers.  'label' names what was checked: 'JSP
    validation' or 'JSP security check'.  No page count - it is the same number
    on both lines and says nothing about the result.
    """
    if count == 0:
        return '* %s passed for: %s' % (label, app)
    return '* %s identified %d issue%s for: %s' % (
        label, count, '' if count == 1 else 's', app)


def report_cmd(script, app):
    """Where to go for the detail, as a copy-pasteable command."""
    return '* Run %s --app %s --verbose to view the full report.' % (script, app)


def _rule_counts(items):
    """rule -> how many findings, for the tally a failing run prints."""
    counts = {}
    for i in items:
        counts[i['rule']] = counts.get(i['rule'], 0) + 1
    return counts


def _report_segments(text):
    """
    The pieces of a report message, each with the separator that joins it to the last.

    The breaks are the message's own: between clauses at '; ', and between the halves
    of a clause at a comma that is not inside a condition. A guard like
    `if ((a == null) || (b != null)), closed outside it` breaks after the condition,
    never inside it.
    """
    out = []
    cur = ''
    sep = ''
    depth = 0
    i = 0
    while i < len(text):
        ch = text[i]
        if ch in '([':
            depth += 1
        elif ch in ')]':
            depth -= 1
        elif depth == 0 and text.startswith('; ', i):
            out.append((sep, cur))
            cur, sep, i = '', '; ', i + 2
            continue
        elif depth == 0 and text.startswith(', ', i):
            out.append((sep, cur))
            cur, sep, i = '', ', ', i + 2
            continue
        cur += ch
        i += 1
    out.append((sep, cur))
    return out


def item_lines(text, prefix='  - ', width=92):
    """
    One report item as one or more lines, broken at the message's own separators.

    A conditional-tag finding names every site the same mismatch reaches, so it runs
    past the line limit. Segments are packed to the width and a line that ends early
    keeps the separator that was joining it to the next one, so the reader sees the
    sentence break where the sentence breaks; a segment that is too long by itself,
    a guard condition, runs over rather than being cut.
    """
    indent = ' ' * len(prefix)
    budget = width - len(prefix)
    lines = []
    cur = None
    for sep, seg in _report_segments(text):
        if cur is None:
            cur = seg
        elif len(cur) + len(sep) + len(seg) <= budget:
            cur += sep + seg
        else:
            lines.append(cur + sep.rstrip())
            cur = seg
    if cur is not None:
        lines.append(cur)
    return [(prefix if n == 0 else indent) + ln for n, ln in enumerate(lines)]


def finding_lines(finding):
    """One finding as report lines: file, line, the rule, and what it found."""
    return item_lines('%s:%d %s %s'
                      % (finding['file'], finding['line'], finding['rule'],
                         finding.get('msg') or finding['key']))


def _tally_sites(items, rule, base):
    """
    Where one rule fired, as 'file:line' values, for the tally's continuation lines.

    Paths are cut to the app's own jsp directory: the app is named on the line above,
    and repeating 'apps/routerconsole/jsp/' on every line of a 114-page app's report
    is padding that pushes the part a reader acts on off the end of the line.
    """
    cut = repo_rel(base).rstrip('/') + '/'
    sites = ['%s:%d' % (i['file'][len(cut):] if i['file'].startswith(cut)
                        else i['file'], i['line'])
             for i in sorted(items, key=lambda x: (x['file'], x['line']))
             if i['rule'] == rule]
    return ['      ' + ln for ln in textwrap.wrap(', '.join(sites), 86)]


# What each rule means, in the words a reader of the build log wants. A line that
# says 'html-tag-stray-end: 2' names the check, not the defect, and tells the
# reader nothing about the pages; the rule name stays in the --verbose listing,
# where it is next to the file and line and is what you grep for.
RULE_LABELS = {
    'bean-id-order': 'Bean used before it is declared',
    'bean-id-undeclared': 'Bean id no page or include declares',
    'bean-prop-unknown': 'Property the bean does not have',
    'csrf-check-fails-open': 'CSRF check that passes when it cannot run',
    'el-unknown-root': 'EL root nothing ever puts in scope',
    'form-post-without-nonce': 'POST form with no CSRF nonce',
    'html-conditional-tag': 'Start tag and end tag under different conditions',
    'html-tag-mismatch': 'Element closed inside the wrong element',
    'html-tag-stray-end': 'End tag with nothing open to close',
    'html-tag-unclosed': 'Element left open',
    'html-tag-unterminated': 'Start tag the file never ends',
    'html-void-end-tag': 'End tag for a void element',
    'include-missing': 'Static include target missing',
    'jsp-link-missing': 'Link to a page that does not exist',
    'msg-key-missing': 'Message key missing from the .po files',
    'page-encoding-missing': 'Page with no pageEncoding',
    'script-output-unescaped': 'Request data written inside a script tag',
    'tab-indent': 'Tab in the indentation',
    'trailing-whitespace': 'Trailing whitespace',
    'unencoded-request-param': 'Request data written without encoding',
    'usebean-dup-id': 'Duplicate jsp:useBean id',
}


def rule_label(rule):
    """The defect a rule reports, for a report line."""
    return RULE_LABELS.get(rule, rule)


def ant_report(path, label, items):
    """
    Append the finding counts to 'path' as ant properties.

    The build runs the checker with <exec>, and an exec failure reaches ant only as
    'exec returned: 1', so the build log blames the ant line rather than the pages.
    The checkjsps targets load this file and fail with a message that says what was
    found, so the build stops on the report itself.
    """
    counts = _rule_counts(items)
    tally = ', '.join('%s %d' % (rule_label(r), counts[r]) for r in sorted(counts))
    try:
        with open(path, 'a', encoding='utf-8') as f:
            f.write('jspcheck.%s.count=%d\n' % (label, len(items)))
            f.write('jspcheck.%s.rules=%s\n' % (label, tally or 'none'))
    except OSError as e:
        print('* JSP %s report not written: %s' % (label, e))


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('--verbose', action='store_true', help='list every finding')
    ap.add_argument('--json', action='store_true', help='machine-readable output')
    ap.add_argument('--rule', help='only run this rule')
    ap.add_argument('--app', help='only check this app')
    ap.add_argument('--selftest', action='store_true',
                    help='run the markup-balance fixtures and exit')
    ap.add_argument('--ant-report', metavar='FILE',
                    help='append the finding counts to FILE as ant properties')
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    apps = [a for a in APPS if not args.app or a['name'] == args.app]
    findings = Findings()
    blind = set()
    counts = {}
    for app in apps:
        java = JavaIndex(app['java'] + app['shared'])
        msgids = po_msgids(app['locale'])
        # names any page or helper in this app ever puts into a scope
        known_roots = set()
        app_bean_ids = set()
        for path in jsp_files(app):
            text = read(path)
            if text is None:
                continue
            for m in SET_ATTR.finditer(text):
                known_roots.add(m.group(1))
            for m in USEBEAN.finditer(strip_jsp_comments(text)):
                ident = tag_attrs(m.group(0)).get('id')
                if ident:
                    app_bean_ids.add(ident)
        for rel in app['java']:
            for dirpath, _dirs, files in os.walk(rel):
                for fn in files:
                    if fn.endswith('.java'):
                        text = read(os.path.join(dirpath, fn)) or ''
                        for m in SET_ATTR.finditer(text):
                            known_roots.add(m.group(1))
        first = len(findings.items)
        for path in jsp_files(app):
            text = read(path)
            if text is None:
                counts.setdefault('unreadable', []).append(path)
                continue
            counts[app['name']] = counts.get(app['name'], 0) + 1
            check_page(app, path, text, java, known_roots, msgids, findings, blind,
                       app_bean_ids)
        for item in findings.items[first:]:
            item['app'] = app['name']

    items = findings.items
    if args.rule:
        items = [i for i in items if i['rule'] == args.rule]

    if args.json:
        print(json.dumps({'pages': sum(counts.get(a['name'], 0) for a in apps),
                          'findings': items, 'blind': sorted(blind)}, indent=2))
    else:
        # house style for a build-time checker (see bundle-messages.sh): one
        # '* ' summary line per app, then the tally of what fired with the pages it
        # fired on, then the command that shows the detail. The findings themselves,
        # with the rule id and the prose, are for a --verbose run.
        for app in apps:
            name = app['name']
            mine = [i for i in items if i.get('app') == name]
            print(summary_line('JSP validation', name, len(mine)))
            if not mine:
                continue
            if args.verbose:
                for i in sorted(mine, key=lambda x: (x['file'], x['line'])):
                    for ln in finding_lines(i):
                        print(ln)
            else:
                # The tally has to stand on its own, so each kind of defect carries
                # the pages it fired on: the build log then says what to fix without
                # a second run. No header above it - a count beside a defect name,
                # with file:line values under it, says what it is.
                for rule, cnt in sorted(_rule_counts(mine).items(),
                                        key=lambda kv: (-kv[1], rule_label(kv[0]))):
                    print('  - %s: %d' % (rule_label(rule), cnt))
                    for ln in _tally_sites(mine, rule, app['jsp']):
                        print(ln)
                print('  - Total: %d' % len(mine))
            if not args.verbose:
                print(report_cmd('tools/scripts/jsp-audit.py', name))
        if blind and args.verbose:
            print('* JSP unchecked expressions: %d (source not visible from the page)'
                  % len(blind))
            for b in sorted(blind):
                print('  - ' + b)

    if args.ant_report:
        for app in apps:
            ant_report(args.ant_report, 'validation',
                       [i for i in items if i.get('app') == app['name']])

    # a fresh pass every run: no baseline, so any finding at all is a failure
    return 1 if items else 0


if __name__ == '__main__':
    sys.exit(main())
