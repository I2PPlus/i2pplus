#!/usr/bin/env python3
"""Emit the per-language welcome pages from a translation table.

The welcome page is the one screen whose content IzPack cannot localize by itself: an HTML
panel's body is fetched with Resources.getURL(), never through the langpack, so there is one
welcome.html for every language. installer/lib/izpack/5/patches/java has
LocalizedHTMLInfoPanel, which resolves welcome_<iso3>.html and falls back to welcome.html,
and this writes those files.

Only eleven translatable fragments are replaced. Every tag, attribute, URL, the onion address,
the e-mail address, the product names and the #saltR channel are copied verbatim from the
English page, because a translator mangling a tag or a hostname breaks the page rather than
merely reading badly. The fragments are located in the English source at run time rather than
the page being restated, so the two cannot drift: a change to welcome.html that moves one of
them makes the tool fail loudly instead of silently skipping it.

Usage:
  gen-izpack-welcome.py --table TSV          write the pages
  gen-izpack-welcome.py --check              report missing or unregistered pages
"""

import argparse
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
ENGLISH = "installer/lib/izpack/resources/welcome.html"
OUT_DIR = "installer/lib/izpack/resources"
INSTALL5 = "installer/lib/izpack/5/install5.xml"

# The translatable fragments, in document order. Everything NOT listed here - every tag,
# every attribute, every URL, the .onion address, the e-mail address, the "I2P" and "I2P+"
# product names, and the #saltR channel - is copied from the English page byte for byte. A
# translator cannot therefore break the markup or a hostname, which is the failure mode that
# matters here; a wrong word is a cosmetic defect by comparison.
FRAGMENTS = (
    "Welcome to the I2P+ Installer",
    "I2P+ is a soft fork of the ",
    " anonymity software, developed by dr|z3d,\nwith an emphasis on presentation, performance, and usability.",
    "More information about I2P+:",
    "Web: ",
    "Clearnet Mirror: ",
    "Tor .onion Site: ",
    "Contact: ",
    "IRC: ",
    " on I2P's IRC Network",
    "Enjoy responsibly!",
)


# The sentence about the fork is split around the <a>I2P</a> anchor, so the space that
# separated the two halves lives on the fragments themselves. A translation that omits it
# would render as "mit Schwerpunkt aufI2P" once the tags are stripped, which is not the same
# word, so the space is enforced here rather than trusted to the table.
SPLIT_SENTENCE = (1, 2)


def normalise(index, value):
    if index not in SPLIT_SENTENCE:
        return value
    if index == 1 and value and not value.endswith((" ", "\n")):
        return value + " "
    if index == 2 and value and not value.startswith((" ", "\n")):
        return " " + value
    return value


def build_page(template, translated):
    """Substitute the translatable fragments into the English page's structure."""
    translated = [normalise(i, v) for i, v in enumerate(translated)]
    out = template
    for src, dst in zip(FRAGMENTS, translated):
        if src not in out:
            raise SystemExit("welcome page no longer contains %r - the English source "
                             "changed and this tool's fragment list needs updating" % src)
        out = out.replace(src, "\x00\x00%d\x00\x00" % FRAGMENTS.index(src), 1)
    for n, dst in enumerate(translated):
        out = out.replace("\x00\x00%d\x00\x00" % n, dst, 1)
    return out


def panel_prefix():
    """The resource prefix that must carry the welcome body.

    IzPack derives a panel's resource names from the class it instantiated, which here is
    our subclass, so the raw name it asks with is not the one the descriptor should use:
    LocalizedHTMLInfoPanel rebases it onto the stock class before looking anything up, so
    the body has to be registered under that rebased id.
    """
    text = open(os.path.join(REPO, "installer/lib/izpack/5/install5.xml"),
                encoding="iso-8859-1").read()
    text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
    for m in re.finditer(r'<panel classname="([A-Za-z0-9_.]*[A-Za-z0-9])"', text):
        simple = m.group(1).rsplit(".", 1)[-1]
        if "HTML" in simple and "Panel" in simple:
            return body_resource_id()
    raise SystemExit("gen-izpack-welcome: no HTML info panel declared in install5.xml")


def body_resource_id():
    """The <res> id that must carry the welcome body, taken from the panel itself.

    LocalizedHTMLInfoPanel rewrites the resource prefix IzPack derives from the declared
    panel class back onto the stock class, because the descriptor's <res> ids are written
    against the stock class. Rather than reimplement that here - which is how the two drift -
    the panel and its test are asked directly. The test is the authority: it exercises the
    real getURL against a Resources that throws on a miss, exactly as IzPack's does, and it
    fails if the prefix, the descriptor and the rebase stop agreeing.

    Getting this wrong is silent at build time and fatal at runtime: with the stock
    HTMLHelloPanel id the panel died on its second screen with "Cannot find named resource".
    """
    src = os.path.join(REPO, "installer/lib/izpack/5/patches/java/test/net/i2p/installer",
                       "LocalizedResourcesTest.java")
    if not os.path.exists(src):
        raise SystemExit("gen-izpack-welcome: %s is missing" % src)
    java = open(src, encoding="utf-8").read()
    # Read the expectation explicitly marked <body-resource-id>, not merely the first one:
    # the test also covers the historical HTMLHelloPanel name, and taking whichever came
    # first would silently pin that instead of the live contract.
    m = re.search(r'expect\(\s*"([A-Za-z0-9_]+)\.info"[^)]*<body-resource-id>', java)
    if not m:
        raise SystemExit("gen-izpack-welcome: %s has no expectation marked "
                         "<body-resource-id>; it cannot be told which id install5.xml must "
                         "provide" % src)
    return m.group(1)


def declared():
    text = re.sub(r"<!--.*?-->", "", open(os.path.join(REPO, INSTALL5),
                                         encoding="iso-8859-1").read(), flags=re.S)
    return [m.group(1) for m in re.finditer(r'<langpack iso3="([a-z]+)"', text)]


def read_table(path):
    table = {}
    with open(path, encoding="utf-8") as fh:
        for lineno, line in enumerate(fh, 1):
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) != len(FRAGMENTS) + 1:
                raise SystemExit("%s:%d: expected %d tab-separated fields, got %d"
                                 % (path, lineno, len(FRAGMENTS) + 1, len(parts)))
            table[parts[0]] = parts[1:]
    return table


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--table", help="TSV: iso3 then the %d translated fragments" % len(FRAGMENTS))
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()

    langs = [c for c in declared() if c != "eng"]
    template = open(os.path.join(REPO, ENGLISH), encoding="utf-8").read()

    if args.check:
        missing = [c for c in langs
                   if not os.path.exists(os.path.join(REPO, OUT_DIR, "welcome_%s.html" % c))]
        unregistered = [c for c in langs
                        if ('id="welcome_%s.html"' % c) not in
                        open(os.path.join(REPO, INSTALL5), encoding="iso-8859-1").read()]
        for c in missing:
            print("missing page: welcome_%s.html" % c)
        for c in unregistered:
            print("page not registered as a resource: welcome_%s.html" % c)
        if missing or unregistered:
            return 1
        # The panel prefix and the body id must agree, or the panel cannot load its own body.
        want = "%s.info" % panel_prefix()
        text = re.sub(r"<!--.*?-->", "",
                      open(os.path.join(REPO, INSTALL5), encoding="iso-8859-1").read(),
                      flags=re.S)
        if ('<res id="%s"' % want) not in text:
            print("install5.xml declares the welcome panel as %s but has no "
                  '<res id="%s"> for its body; the panel would fail to load'
                  % (panel_prefix(), want))
            return 1
        print("all %d languages have a registered welcome page, body id %s"
              % (len(langs), want))
        return 0

    if not args.table:
        ap.error("--table is required unless --check is given")

    table = read_table(args.table)
    unknown = sorted(set(table) - set(langs))
    if unknown:
        raise SystemExit("table has languages the descriptor does not declare: %s"
                         % ", ".join(unknown))
    for iso3, values in sorted(table.items()):
        for n, v in zip(FRAGMENTS, values):
            if re.search(r"<[a-zA-Z/]|&[a-z]+;", v):
                raise SystemExit("%s: translation of %r looks like markup: %r"
                                 % (iso3, n, v))
        page = build_page(template, values)
        with open(os.path.join(REPO, OUT_DIR, "welcome_%s.html" % iso3), "w",
                  encoding="utf-8", newline="\n") as fh:
            fh.write(page)
        print("wrote welcome_%s.html" % iso3)
    absent = [c for c in langs if c not in table]
    if absent:
        print("still English for %d language(s): %s"
              % (len(absent), " ".join(absent)), file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())