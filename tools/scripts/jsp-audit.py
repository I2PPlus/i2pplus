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
  * House hygiene the tree has already settled on: pageEncoding on every page, no
    trailing whitespace, no tabs.

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
"""
import argparse
import glob
import json
import os
import re
import sys
import tempfile

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


def _line_off(raw, i):
    return len(raw.split(b'\n')[:i]) and sum(len(x) + 1 for x in raw.split(b'\n')[:i])


class Findings:
    def __init__(self):
        self.items = []

    def add(self, rule, path, text, offset, key):
        line = text[:offset].count('\n') + 1 if offset > 0 else 1
        self.items.append({'rule': rule, 'file': repo_rel(path), 'line': line,
                           'key': key})

    def keys(self):
        return set('%s\t%s\t%s' % (i['rule'], i['file'], i['key']) for i in self.items)

    def by_rule(self):
        """rule -> count, in rule order."""
        counts = {}
        for i in self.items:
            counts[i['rule']] = counts.get(i['rule'], 0) + 1
        return counts


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


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('--verbose', action='store_true', help='list every finding')
    ap.add_argument('--json', action='store_true', help='machine-readable output')
    ap.add_argument('--rule', help='only run this rule')
    ap.add_argument('--app', help='only check this app')
    args = ap.parse_args()

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
        # '* ' summary line per app, and a second line pointing at the command
        # that shows the detail. Everything else is for a direct --verbose run.
        for app in apps:
            name = app['name']
            mine = [i for i in items if i.get('app') == name]
            print(summary_line('JSP validation', name, len(mine)))
            if mine:
                print(report_cmd('tools/scripts/jsp-audit.py', name))
                if args.verbose:
                    for i in sorted(mine, key=lambda x: (x['file'], x['line'])):
                        print('*   %s:%d %s %s'
                              % (i['file'], i['line'], i['rule'], i['key'][:90]))
                    by_rule = {}
                    for i in mine:
                        by_rule.setdefault(i['rule'], []).append(i)
                    for rule in sorted(by_rule):
                        print('*   %s: %d' % (rule, len(by_rule[rule])))
        if blind and args.verbose:
            print('* JSP unchecked expressions: %d (source not visible from the page)'
                  % len(blind))
            for b in sorted(blind):
                print('*   ' + b)

    # a fresh pass every run: no baseline, so any finding at all is a failure
    return 1 if items else 0


if __name__ == '__main__':
    sys.exit(main())
