#!/usr/bin/env python3
"""build-devdocs.py — Consolidate all markdown (README.md, docs/*.md) and
package.html files into a self-contained, javadoc-style HTML documentation
site. Translated README-*.md copies under docs/ are included; their links
are resolved root-relative, since they translate the root README.

The output tree mirrors the source tree:
    *.md           -> *.html   (markdown rendered, heading anchors added)
    package.html   -> package.html  (body reused as-is)

Everything is first generated in STAGE_DIR (default /tmp/build-i2p/devdocs/) and
only then published to OUT_DIR (default dist/devdocs/), so an interrupted or
failed run never leaves a half-written site under ./dist.

Features:
  - Pure-python pipeline: markdown_it for md -> html, stdlib regex elsewhere
  - No external fetches: CSS/JS are inlined in every page; images referenced by
    local docs are copied alongside the mirrored tree
- Relative links rewritten (.md -> .html); hash anchors kept
- Sidebar navigation tree with collapsible modules, an h3 heading per
  subsystem (apps/ modules, java package families under router/core),
  breadcrumbs, and an index page with client-side search (works from file://,
  no server needed)
- package.html pages classified first-party (net.i2p.*) vs third-party/vendored
- Javadoc {@link ...} tags in package.html resolved to links into the site

Usage:
    python3 tools/scripts/build-devdocs.py              # stage + publish
    python3 tools/scripts/build-devdocs.py --stage-only # stage only
    ant devdocs                                         # or ant devdocs-zip
"""

import argparse
import html as htmlmod
import posixpath
import re
import shutil
import sys
from collections import OrderedDict
from pathlib import Path
from urllib.parse import quote

try:
    from markdown_it import MarkdownIt
except ImportError:
    sys.stderr.write("ERROR: 'markdown-it-py' is required: pip install markdown-it-py\n")
    sys.exit(1)

EXCLUDED_PREFIXES = ("tools/codeql/", "tools/gradle/",
                     "dist/", "build/", ".github/", ".opencode/", ".recon/",
                     "tools/sonarqube/sonar-scanner-")
EXCLUDED_SEGMENTS = ("node_modules", ".git", "wip", "dist")
EXCLUDED_NAMES = ("AGENTS.md", "CLAUDE.md")
ASSET_EXTS = (".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp", ".ico", ".txt",
              ".pdf", ".gz")
ASSET_MAX = 4 * 1024 * 1024
DEFAULT_STAGE = "/tmp/build-i2p/devdocs"
DEFAULT_OUT = "dist/devdocs"


def slugify(text):
    """Approximate GitHub heading slug: lowercase, punctuation removed,
    underscores kept, every space becomes a hyphen (no collapsing)."""
    s = re.sub(r"[^a-z0-9\-_ ]", "", text.lower())
    return s.strip().replace(" ", "-")


def add_heading_ids(html):
    """Give each heading an id so #anchor links work."""
    counts = {}

    def repl(m):
        tag, inner = m.group(1), m.group(2)
        slug = slugify(" ".join(re.sub(r"<[^>]+>", " ", inner).split()))
        if not slug:
            return m.group(0)
        n = counts.get(slug, 0)
        counts[slug] = n + 1
        slug = slug if not n else "%s-%d" % (slug, n)
        return '<%s id="%s">%s</%s>' % (tag, slug, inner, tag)

    return re.sub(r"<(h[1-6])>(.*?)</\1>", repl, html, flags=re.S)


def extract_package(path):
    """(title, body) of a javadoc package.html — the existing HTML is reused."""
    raw = path.read_text(encoding="utf-8", errors="replace")
    raw = re.sub(r"<!DOCTYPE[^>]*>", "", raw, flags=re.I | re.S)
    title = re.search(r"<title>(.*?)</title>", raw, flags=re.S | re.I)
    title = title.group(1).strip() if title else path.parent.name
    body = re.search(r"<body[^>]*>(.*?)</body>", raw, flags=re.S | re.I)
    if not body:
        body = re.search(r"<html[^>]*>(.*?)</html>", raw, flags=re.S | re.I)
    return title, (body.group(1) if body else raw).strip()


def is_third_party(rel):
    """True when a package.html sits under a non-I2P package root."""
    m = re.search(r"java/src/(.*)/package\.html$", rel)
    if not m:
        return False
    pkg = m.group(1)
    return not pkg.startswith(("net/i2p/", "i2p/", "org/klomp/snark/"))


def detect_title(path):
    raw = path.read_text(encoding="utf-8", errors="replace")
    m = re.search(r"^#\s+(.+)$", raw, flags=re.M)
    return m.group(1).strip() if m else path.parent.name.replace("_", " ").title()


_TITLE_SUFFIX = re.compile(r"\s*[\(（]\s*`?\s*([\w.-]+)/\s*`?\s*[\)）]\s*$")


def load_favicon(root):
    """Inline SVG favicon as a data URI (borrowed from
    tools/test/unit-tests-to-html.py:306); empty when the icon is missing."""
    path = root / "installer/resources/console/themes/console/images/plus.svg"
    try:
        data = path.read_text(encoding="utf-8")
    except OSError:
        return ""
    return '<link rel="icon" type="image/svg+xml" href="data:image/svg+xml,%s">' % quote(data)


def load_brand(root):
    """URL-escaped data URI of the I2P+ wordmark (tools/template/cannon.svg)
    for the --ident CSS variable; '' when the asset is missing."""
    try:
        data = (root / "tools/template/cannon.svg").read_text(encoding="utf-8")
    except OSError:
        return ""
    return quote(data)


def clean_title(title, rel):
    """Display label for a page: strip markdown backticks, and drop a
    trailing parenthetical like (`pack200/`) when it names the page's own
    directory — the tree and .pth path already show it."""
    title = title.strip().replace("`", "")
    m = _TITLE_SUFFIX.search(title)
    if m:
        dirname = posixpath.basename(posixpath.dirname(rel))
        if m.group(1).lower() == dirname.lower():
            title = title[:m.start()].rstrip()
    return title


_IMG_TAG = re.compile(r"<[^>]+>")
_BADGE_ROW = re.compile(r'<p>.*?</p>', flags=re.S)


def strip_badge_rows(html):
    """Drop paragraphs that are nothing but linked images pointing at local
    badges or language flags (e.g. the badge strip and translation row of the
    root README) — noise for the docs site."""
    out = []
    pos = 0
    for m in _BADGE_ROW.finditer(html):
        out.append(html[pos:m.start()])
        seg = m.group(0)
        if _IMG_TAG.sub("", seg).strip() or not re.search(
                r'src="[^"]*(?:badges/|flags_svg/)', seg):
            out.append(seg)
        pos = m.end()
    out.append(html[pos:])
    return "".join(out)


_LANGS = {
    "ar": "Arabic", "bn": "Bengali", "bo": "Tibetan", "cs": "Czech",
    "de": "German", "el": "Greek", "es": "Spanish", "fa": "Persian",
    "fr": "French", "he": "Hebrew", "hi": "Hindi", "hu": "Hungarian",
    "id": "Indonesian", "it": "Italian", "ja": "Japanese", "ko": "Korean",
    "nl": "Dutch", "pl": "Polish", "pt": "Portuguese", "ro": "Romanian",
    "ru": "Russian", "th": "Thai", "tr": "Turkish", "uk": "Ukrainian",
    "ur": "Urdu", "vi": "Vietnamese", "zh": "Chinese",
}


def collect_pages(root):
    """All .md / package.html pages as dicts (rel, outrel, kind, title,
    third_party, body). Agent/config files are skipped; translated
    README-*.md pages under docs/ are kept."""
    pages = []
    for path in sorted(root.rglob("*.md")) + sorted(root.rglob("package.html")):
        rel = posixpath.join(*[str(x) for x in path.relative_to(root).parts])
        if rel.startswith(EXCLUDED_PREFIXES):
            continue
        if any(seg in EXCLUDED_SEGMENTS for seg in rel.split("/")):
            continue
        if path.name == "package.html":
            title, body = extract_package(path)
            pages.append({"rel": rel, "outrel": rel, "kind": "package",
                          "title": title, "label": clean_title(title, rel),
                          "third_party": is_third_party(rel), "body": body})
            continue
        if path.name in EXCLUDED_NAMES:
            continue  # agent config files
        if path.name.startswith("README-") and not rel.startswith("docs/"):
            continue  # translations outside docs/ are out of scope
        title = detect_title(path)
        page = {"rel": rel, "outrel": rel[:-3] + ".html", "kind": "md",
                "title": title, "label": clean_title(title, rel),
                "third_party": False, "body": None}
        if path.name.startswith("README-"):
            m = re.match(r"README-(.+)\.md$", path.name)
            lang = _LANGS.get(m.group(1), m.group(1))
            page["label"] += " (%s)" % lang
            page["root_rel"] = True  # links written root-relative
        pages.append(page)
    return pages


def resolve_target(val, base, pages_by_out, root, assets, strip=False):
    """Output-tree target for a local href/src, relative to the page directory
    ('' for pages whose links are written root-relative, tried again relative
    to docs/ for the translated readmes, which mix both conventions). Returns
    the rewritten href, None to leave the reference alone, or False to drop
    the attribute. Directory references map to the directory's README.html /
    package.html / index.html landing page when one exists; local references
    that resolve to no built page and no asset (excluded subtrees, class
    pages the site doesn't host, dead paths) render as plain text."""
    if not val or val.startswith(("#", "http:", "https:", "mailto:", "data:", "javascript:", "//")):
        return None
    bases = [base, "docs"] if base == "" else [base]
    for b in bases:
        target = posixpath.normpath(posixpath.join(b, val))
        if target == "." or target.startswith("../"):
            continue
        target = target.lstrip("/")
        if target.endswith(".md"):
            target = target[:-3] + ".html"
        if target.endswith("package-summary.html"):
            target = target[:-len("package-summary.html")] + "package.html"
        if target in pages_by_out:
            return target
        src = root / target
        if src.is_file() and target.endswith(ASSET_EXTS) and src.stat().st_size <= ASSET_MAX:
            assets.add(target)
            return target
        if not target.endswith(ASSET_EXTS):
            for candidate in (target + "/README.html", target + "/package.html",
                              target + "/index.html"):
                if candidate in pages_by_out:
                    return candidate
    return False if strip else None


def fix_links(text, page, pages_by_out, root, assets):
    """Rewrite href=/src= attributes; record local assets referenced.
    Unresolvable local hrefs are stripped (dead links render as plain text);
    src= attributes are never touched."""
    base = "" if page.get("root_rel") else posixpath.dirname(page["outrel"])

    def sub(m):
        pre, name, q, val = m.group(1), m.group(2), m.group(3), m.group(4)
        new = resolve_target(val, base, pages_by_out, root, assets,
                             strip=name == "href")
        if new is None:
            return m.group(0)
        if new is False:
            return ""
        return "%s %s=%s%s%s" % (pre, name, q,
                                 posixpath.relpath(new, posixpath.dirname(page["outrel"])), q)

    return re.sub(r'(\s)(href|src)=("|\')([^"\'>]+)\3', sub, text)


def build_tree(pages):
    root = {"name": "I2P+", "pages": [], "children": OrderedDict()}
    for p in pages:
        node = root
        for seg in p["outrel"].split("/")[:-1]:
            node = node["children"].setdefault(seg, {"name": seg, "pages": [],
                                                     "children": OrderedDict()})
        node["pages"].append(p)
    return root


_SCAFFOLD_DIRS = frozenset(("java", "src", "main", "test", "jsp", "webapp",
                            "resources", "build", "dist"))
_PKG_ROOTS = frozenset(("net", "i2p", "org", "com", "eu", "gnu", "freenet"))


def page_section(outrel):
    """Sidebar h3 label for a page under a module group: for apps the module
    directory (addressbook, i2psnark ...), for java trees the package family
    under net.i2p (tunnel, transport, peermanager ...) or the vendored root
    (bouncycastle, google ...); pages directly under the module group get the
    group name itself."""
    parts = outrel.split("/")
    mod, rest = parts[0], parts[1:]
    if len(rest) == 1:
        return mod
    if mod == "apps":
        return rest[0]
    while rest and rest[0] in _SCAFFOLD_DIRS:
        rest = rest[1:]
    if rest and rest[0] in _PKG_ROOTS:
        rest = rest[1:]
        if rest and rest[0] == "i2p":
            rest = rest[1:]
    if not rest or rest[0].endswith(".html"):
        return mod
    if rest[0] == "router" and len(rest) > 1 and not rest[1].endswith(".html"):
        return rest[1]
    return rest[0]


def render_tree(tree, current, up=""):
    """Sidebar: one collapsible group per top-level directory, holding a
    hierarchy of h3 headings for the subsystem slide on the pages under a
    module (see page_section). The group containing the current page is open,
    as is everything on the index. All links are relative to the page
    directory via the UP prefix."""
    top = current.split("/")[0] if current else ""

    def all_pages(node):
        ps = list(node["pages"])
        for child in node["children"].values():
            ps.extend(all_pages(child))
        return ps

    def page_link(p):
        cls = "here" if p["outrel"] == current else ("third" if p["third_party"] else "")
        return ('<li><a class="%s" href="%s%s" title="%s">%s</a></li>' %
                (cls, up, p["outrel"], htmlmod.escape(p["outrel"]),
                 htmlmod.escape(p["label"])))

    out = ['<details open><summary>I2P+</summary><ul>']
    for name, child in tree["children"].items():
        sections = OrderedDict()
        for p in all_pages(child):
            sections.setdefault(page_section(p["outrel"]), []).append(p)
        heads = [name] if name in sections else []
        heads += sorted(s for s in sections if s != name)
        out.append('<li><details%s><summary>%s</summary><ul>' % (
            " open" if (not current or name == top) else "", htmlmod.escape(name)))
        for s in heads:
            out.append('<li><h3>%s</h3><ul class="sub">' % htmlmod.escape(s))
            for p in sorted(sections[s], key=lambda x: (x["outrel"], x["title"].lower())):
                out.append(page_link(p))
            out.append("</ul></li>")
        out.append("</ul></details></li>")
    for p in sorted(tree["pages"], key=lambda x: x["title"].lower()):
        out.append(page_link(p))
    out.append("</ul></details>")
    return "".join(out)


def related_html(p, pages, up=""):
    d = "/".join(p["outrel"].split("/")[:-1])
    sibs = [q for q in pages
            if "/".join(q["outrel"].split("/")[:-1]) == d and q["outrel"] != p["outrel"]]
    if not sibs:
        return ""
    rows = "".join('<li><a href="%s%s">%s</a></li>' % (up, q["outrel"], htmlmod.escape(q["label"]))
                   for q in sorted(sibs, key=lambda x: x["title"].lower()))
    return '<div class="related"><h3>Related (same directory)</h3><ul>%s</ul></div>' % rows


CSS = """<style>
:root{--bg:#1f2630;--fg:#d5dbe4;--navbg:#171c24;--navfg:var(--link);--link:#6cb4e8;--hl:#343d4a;--code:#262f3b;--third:#e0b341;--ident:url("data:image/svg+xml,__IDENT_SVG__")}
*{box-sizing:border-box}
a{color:var(--link);text-decoration:none}
a:hover{text-decoration:underline}
blockquote{border-left:4px solid var(--hl);margin:8px 0;padding:2px 14px;color:#aab4c0}
body{margin:0;font:15px/1.6 Open Sans,-apple-system,'Segoe UI',Roboto,'Helvetica Neue',sans-serif;
code{background:var(--code);border:1px solid #343d4a;border-radius:3px;padding:1px 4px;font-size:13px}
color:var(--fg);background:var(--bg)}
footer{margin-top:18px;font-size:12px;color:#7a8490}
html{scrollbar-color:#6cb4e8 #0000}
h1{font-size:26px;margin:0 0 12px}
h2,.section{border-bottom:1px solid var(--hl);padding-bottom:4px;margin-top:1.6em}
hr{margin:30px 0 20px;border:0;border-bottom:1px solid #343d4a}
img{max-width:100%}
mark{background:#e4c981;color:#161b22;border-radius:2px;padding:0 2px}
pre code{background:none;border:none;padding:0}
pre{background:var(--code);border:1px solid var(--hl);border-radius:6px;padding:10px 12px;overflow-x:auto;font-family:Fira Sans,ui-monospace,Menlo,Consolas,monospace;font-size:13px}
table{border-collapse:collapse;margin:12px 0;width:100%}
th,td{border:1px solid var(--hl);padding:6px 10px;text-align:left}
th{background:#2a3340}
.badge{display:inline-block;background:#3d3314;color:#e4c981;border:1px solid #8a6d3b;border-radius:9px;font-size:11px;padding:1px 8px;vertical-align:middle}
.content{flex:1;margin:0 8%;padding:26px 0 60px;min-width:0}
.crumbs{font-size:13px;color:#9aa4b0;margin-bottom:16px}
.hide{display:none}
.mod a{margin:0 2px;padding:1px 6px;white-space:nowrap;background:#55f1;border-radius:4px;font-size:90%;font-weight:600}
.related h3{margin:0 0 6px;font-size:14px}
.related ul{margin:0;padding-left:18px}
.related{background:#232c38;border:1px solid var(--hl);border-radius:6px;padding:10px 16px;margin-top:24px;font-size:14px}
.sidebar{width:320px;min-width:320px;background:var(--navbg);color:var(--navfg);padding:14px 10px;overflow-y:auto;max-height:100vh;position:sticky;top:0;font-size:14px;border-right:4px solid #0004}
.sidebar .ident:hover{color:#7cc0ff;text-decoration:none}
.sidebar .ident{color:#fff;font-weight:700;font-size:20px;padding:0 0 12px}
.sidebar a{color:var(--navfg);display:block;line-height:1.35;margin:1px 0}
.sidebar details{margin-left:2px}
.sidebar form{padding:0 0 8px}
.sidebar h3{font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:.05em;color:#7c8ea4;margin:12px 0 2px;padding-left:4px}
.sidebar input{width:100%;padding:6px 8px;border:1px solid #3a4452;border-radius:4px;background:#10151b;color:#fff}
.sidebar li.collapsed{color:#8892a0;padding:1px 4px;font-size:13px}
.sidebar li.here > a{color:#7cc0ff;font-weight:700}
.sidebar li.third a{opacity:.7}
.sidebar summary{cursor:pointer;font-weight:600;padding:2px 0;color:#e6e9ee;user-select:none}
.sidebar ul.sub{margin:0 0 6px;padding-left:8px}
.sidebar ul{list-style:none;margin:2px 0 4px;padding-left:10px}
.sidebar ul a{font-size:90%}
.sub li a{margin-bottom:3px;padding-bottom:5px;border-bottom:1px dotted #fff1}
.wrap{display:flex;min-height:100vh;position:relative;contain:paint}
.wrap::after{width:500px;height:500px;display:inline-block;position:absolute;right:0;bottom:0;background:var(--ident) no-repeat right 10px bottom/500px;content:'';pointer-events:none}
#list .pth,#list-tp .pth{color:#7a8490;font-size:12px}
#list li,#list-tp li{padding:2px 0}
#list,#list-tp{list-style:none;padding:0}
#nores b{color:#e4c981}
#nores{margin:18px 0 0;color:#c8cfd9;background:#232c38;border:1px solid var(--hl);border-radius:6px;padding:10px 14px}
#q{width:100%;max-width:420px;padding:8px 12px;border:1px solid var(--hl);border-radius:5px;font-size:15px;background:#171c24;color:var(--fg)}
</style>"""


def output_page(p, pages, tree, favicon, ident):
    title = htmlmod.escape(p["label"])
    up = "../" * p["outrel"].count("/")
    index = up + "index.html"
    crumbs = ['<a href="%s">Index</a>' % index] + [htmlmod.escape(s)
                                                   for s in p["outrel"].split("/")[:-1]]
    badge = ' <span class="badge">third-party</span>' if p["third_party"] else ""
    body = p["body"]
    m = re.search(r"<h1[^>]*>", body)
    if m:
        if badge:
            body = body[:m.end()] + badge + body[m.end():]
        heading = ""
    else:
        heading = "<h1>" + title + badge + "</h1>\n"
    return ("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n"
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n"
            "<title>" + title + "</title>\n" + favicon + "\n"
            + CSS.replace("__IDENT_SVG__", ident) + "</head>\n<body><div class=\"wrap\">\n"
            "<aside class=\"sidebar\">\n<a class=\"ident\" href=\"" + index + "\">I2P+ Docs</a>\n"
            "<form action=\"" + index + "\" method=\"get\">"
            "<input type=\"search\" name=\"q\" placeholder=\"Search...\"></form>\n"
            + render_tree(tree, p["outrel"], up) +
            "</aside>\n<main class=\"content\">\n"
            "<div class=\"crumbs\">" + " / ".join(crumbs) + "</div>\n"
            "<article>\n" + heading + body + "\n</article>\n"
            + related_html(p, pages, up) +
            "<footer>Source: <code>" + htmlmod.escape(p["rel"]) + "</code></footer>\n"
            "</main>\n</div>\n</body>\n</html>\n")


def write_index(out_dir, pages, tree, favicon, ident):
    first = [p for p in pages if not p["third_party"]]
    third = [p for p in pages if p["third_party"]]
    mods = OrderedDict()
    for p in first:
        if p["kind"] == "md":
            top = "(root)" if "/" not in p["outrel"] else p["outrel"].split("/")[0]
            mods.setdefault(top, []).append(p)
    mods_html = ""
    for top, ps in sorted(mods.items()):
        links = " ".join('<a href="%s">%s</a>' % (p["outrel"], htmlmod.escape(p["label"]))
                         for p in sorted(ps, key=lambda x: x["title"].lower()))
        mods_html += '<div class="mod"><h3>%s</h3><p>%s</p></div>' % (htmlmod.escape(top), links)

    def rows(ps):
        return "".join('<li><a href="%s">%s</a> <span class="pth">%s</span></li>' %
                       (p["outrel"], htmlmod.escape(p["label"]), htmlmod.escape(p["rel"]))
                       for p in sorted(ps, key=lambda x: x["title"].lower()))

    third_html = ""
    if third:
        third_html = ('<h2 class="section">Third-party / vendored packages (%d)</h2>'
                      '<ul id="list-tp">%s</ul>' % (len(third), rows(third)))

    parts = [
        "<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n",
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n",
        "<title>I2P+ Developer Documentation</title>\n", favicon, "\n",
        CSS.replace("__IDENT_SVG__", ident),
        "<style>.mods{display:flex;flex-wrap:wrap;gap:12px}\n",
        ".mod{border:1px solid var(--hl);border-radius:6px;padding:10px 14px;",
        "background:var(--code);flex:1 1 300px}\n",
        ".mod p{margin:6px 0 0;line-height:1.9}</style>\n</head>\n",
        "<body><div class=\"wrap\">\n<aside class=\"sidebar\">\n",
        "<div class=\"ident\">I2P+ Docs</div>\n",
        render_tree(tree, ""),
        "</aside>\n<main class=\"content\">\n<h1>Documentation</h1>\n",
        "<input id=\"q\" type=\"search\" placeholder=\"Filter documents...\">\n",
        "<div id=\"nores\" class=\"hide\" role=\"status\">No documents match \"<b id=\"nores-q\"></b>\"",
        " - the search covers titles and source paths; adjust the query or empty the box.</div>\n",
        "<h2 class=\"section\">Modules (%d)</h2>\n" % len(mods),
        "<div class=\"mods\">%s</div>\n" % mods_html,
        "<h2 class=\"section\">Documentation (%d)</h2>\n" % len(first),
        "<ul id=\"list\">%s</ul>\n" % rows(first),
        third_html,
        "</main>\n</div>\n<script>\n(function(){\n",
        "var q=document.getElementById('q');\n",
        "var lists=['list','list-tp'];\n",
        "var items=[];\n",
        "lists.forEach(function(id){var el=document.getElementById(id);",
        "if(!el)return;",
        "Array.prototype.forEach.call(el.querySelectorAll('li'),function(li){items.push(li);});});\n",
        "[].forEach.call(document.querySelectorAll('.mod'),function(m){items.push(m);});\n",
        "var nores=document.getElementById('nores');\n",
        "var noq=document.getElementById('nores-q');\n",
        "var params=new URLSearchParams(location.search);\n",
        "function unmark(){\n",
        "[].forEach.call(document.querySelectorAll('mark'),function(m){\n",
        "var t=document.createTextNode(m.textContent);m.parentNode.replaceChild(t,m);});\n",
        "}\n",
        "function markNode(n,term){\n",
        "var low=n.nodeValue.toLowerCase();var idx=low.indexOf(term);if(idx===-1)return;\n",
        "var p=n.parentNode;\n",
        "var mark=document.createElement('mark');\n",
        "mark.appendChild(document.createTextNode(n.nodeValue.substring(idx,idx+term.length)));\n",
        "var tail=n.nodeValue.slice(idx+term.length);\n",
        "if(idx) p.insertBefore(document.createTextNode(n.nodeValue.slice(0,idx)),n);\n",
        "p.insertBefore(mark,n);\n",
        "p.removeChild(n);\n",
        "if(tail) p.insertBefore(document.createTextNode(tail),mark.nextSibling);\n",
        "}\n",
        "function refresh(){\n",
        "var t=q.value.toLowerCase().trim();\n",
        "unmark();\n",
        "var any=false;\n",
        "items.forEach(function(li){var h=t!=='' && (li.textContent||'').toLowerCase().indexOf(t)===-1;",
        "li.classList.toggle('hide',h);if(!h)any=true;});\n",
        "nores.classList.toggle('hide',t===''||any);\n",
        "if(t)noq.textContent=q.value.trim();",
        "if(!t)return;\n",
        "items.forEach(function(li){\n",
        "if(li.classList.contains('hide'))return;\n",
        "var nodes=[];var w=document.createTreeWalker(li,NodeFilter.SHOW_TEXT);\n",
        "while(w.nextNode()){if(w.currentNode.parentNode.tagName!=='MARK')nodes.push(w.currentNode);}\n",
        "nodes.forEach(function(n){markNode(n,t);});\n",
        "});\n",
        "}\n",
        "q.addEventListener('input',refresh);\n",
        "if(params.get('q')){q.value=params.get('q');}\n",
        "refresh();\n",
        "})();\n</script>\n</body>\n</html>\n"]
    (out_dir / "index.html").write_text("".join(parts), encoding="utf-8")


def write_site(dirpath, pages, tree, root, assets, favicon, ident):
    dirpath.mkdir(parents=True, exist_ok=True)
    for p in pages:
        dst = dirpath / p["outrel"]
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_text(output_page(p, pages, tree, favicon, ident), encoding="utf-8")
    for target in sorted(assets):
        src = root / target
        if not src.is_file():
            continue
        dst = dirpath / target
        dst.parent.mkdir(parents=True, exist_ok=True)
        if not dst.exists():
            shutil.copyfile(src, dst)
    write_index(dirpath, pages, tree, favicon, ident)


_A_TAG = re.compile(r"<a\s+[^>]*?>")


def external_blank(html):
    """Open external links (http/https/mailto) in a new tab, with
    rel=noopener noreferrer. Marks already carrying a target are left alone."""
    def repl(m):
        tag = m.group(0)
        if "target=" in tag or not re.search(r'href="(?:https?|mailto):', tag):
            return tag
        return tag[:-1] + ' target="_blank" rel="noopener noreferrer">'
    return _A_TAG.sub(repl, html)


_URL_RE = re.compile(r"(?<![\w])(https?://[^\s<>\"']+)")
_TAG_SPLIT = re.compile(r"(<[^>]*>)")
_JAVADOC_LINK = re.compile(r"\{@link(?:plain)?\s+([^\s}]+)(?:\s+([^}]*?))?\}", flags=re.I)


def _pkg_dotted(outrel):
    """Dotted package name for a package page output path (the path segment
    under the module's java/ dir), e.g. 'apps/foo/java/net/i2p/x/package.html'
    -> 'net.i2p.x'; None when the page isn't under a java/ tree."""
    m = re.search(r"/java/(?:src/)?([^/]+(?:/[^/]+)*)/package\.html$", outrel)
    return m.group(1).replace("/", ".") if m else None


def _pkg_index(pages):
    """dotted package name -> {top-level dir: outrel} for every package page,
    so javadoc references can be resolved to a concrete page in the site."""
    index = {}
    for p in pages:
        if p["kind"] != "package":
            continue
        dotted = _pkg_dotted(p["outrel"])
        if not dotted:
            continue
        top = p["outrel"].split("/", 1)[0]
        index.setdefault(dotted, {})[top] = p["outrel"]
    return index


def _package_page(ref, index, page):
    """Output path of the package.html for a javadoc reference (package or
    class name); None when no package page exists in the site. Class
    references fall back to the enclosing package. On ambiguity between
    modules, the current page's own module wins."""
    ref = ref.split("#", 1)[0]
    parts = ref.split(".")
    top = page["outrel"].split("/", 1)[0]
    for n in range(len(parts), 0, -1):
        entry = index.get(".".join(parts[:n]))
        if entry:
            return entry.get(top) or sorted(entry.items())[0][1]
    return None


def resolve_javadoc_links(text, page, index):
    """Convert {@link pkg.Class} tags to hrefs. The site has no class pages,
    so class references link to the enclosing package page; bare packages link
    to their own page. Unresolvable tags render as plain text."""
    pdir = posixpath.dirname(page["outrel"])

    def replace(m):
        ref, label = m.group(1), (m.group(2) or "").strip()
        if not label:
            label = ref[1:] if ref.startswith("#") else ref
        cand = _package_page(ref, index, page)
        if cand:
            href = posixpath.relpath(cand, pdir)
            return '<a href="%s">%s</a>' % (htmlmod.escape(href), htmlmod.escape(label))
        return htmlmod.escape(label)

    return _JAVADOC_LINK.sub(replace, text)


def linkify_text(html):
    """Link bare http(s) URLs in text with target=_blank; text inside <a>,
    <pre>, <code> and <kbd> is left untouched."""
    def rep(m):
        u = m.group(1).rstrip(".,;:!?)]}")
        return ('<a href="%s" target="_blank" rel="noopener noreferrer">%s</a>'
                % (htmlmod.escape(u), htmlmod.escape(u)))

    out = []
    protect = 0
    for seg in _TAG_SPLIT.split(html):
        if not seg:
            continue
        if seg.startswith("<"):
            if seg.startswith("</"):
                if seg[2:].split()[0].rstrip(">").lower() in ("a", "pre", "code", "kbd"):
                    protect -= 1
            elif not seg.endswith("/>"):
                n = seg[1:].split()[0].lower()
                if n in ("a", "pre", "code", "kbd"):
                    protect += 1
            out.append(seg)
        else:
            out.append(seg if protect else _URL_RE.sub(rep, seg))
    return "".join(out)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", "-r", default=".", help="Repo root (default: cwd)")
    ap.add_argument("--stage", "-s", default=DEFAULT_STAGE,
                    help="Intermediate build dir (default: %s)" % DEFAULT_STAGE)
    ap.add_argument("--out", "-o", default=DEFAULT_OUT,
                    help="Final output dir (default: %s)" % DEFAULT_OUT)
    ap.add_argument("--stage-only", action="store_true",
                    help="Generate into the stage dir only; do not publish")
    args = ap.parse_args()

    root = Path(args.root)
    if not root.is_dir():
        sys.stderr.write("ERROR: not a directory: %s\n" % root)
        sys.exit(1)

    pages = collect_pages(root)
    if not pages:
        sys.stderr.write("ERROR: no README.md / package.html files found under %s\n" % root)
        sys.exit(1)

    md = MarkdownIt("default", {"html": True})
    assets = set()

    def render_page_html(p):
        if p["kind"] == "md":
            raw = (root / p["rel"]).read_text(encoding="utf-8", errors="replace")
            return external_blank(linkify_text(add_heading_ids(strip_badge_rows(md.render(raw)))))
        return external_blank(linkify_text(p["body"]))

    tree = build_tree(pages)
    pages_by_out = {p["outrel"]: p for p in pages}
    pkg_index = _pkg_index(pages)

    for p in pages:
        html = fix_links(render_page_html(p), p, pages_by_out, root, assets)
        p["body"] = resolve_javadoc_links(html, p, pkg_index)

    stage = Path(args.stage)
    out = Path(args.out)
    if stage.exists():
        shutil.rmtree(stage)
    write_site(stage, pages, tree, root, assets, load_favicon(root),
               load_brand(root))

    if not args.stage_only:
        if out.exists():
            shutil.rmtree(out)
        shutil.copytree(stage, out)
    dest = stage if args.stage_only else out

    n_md = sum(1 for p in pages if p["kind"] == "md")
    n_pkg = len(pages) - n_md
    n_third = sum(1 for p in pages if p["third_party"])
    print("devdocs: %d pages (%d markdown, %d package, %d third-party), %d assets -> %s" %
          (len(pages), n_md, n_pkg, n_third, len(assets), dest))


if __name__ == "__main__":
    main()