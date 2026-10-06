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
        print("all %d languages have a registered welcome page" % len(langs))
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