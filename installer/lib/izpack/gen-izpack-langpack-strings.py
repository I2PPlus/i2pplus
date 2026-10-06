#!/usr/bin/env python3
"""Fill the untranslated IzPack langpack skeletons from our catalogues, then MT.

Order matters. A string is taken from the project's own gettext catalogues when one of them
already carries that English text, because those are human translations we have shipped and
reviewed. Only what no catalogue covers is machine translated.

Coverage is thin - around 9% for the languages that share any wording at all, and nothing for
most - so the machine-translated part is the bulk. Every string this tool writes is therefore a
DRAFT: it is a complete first pass for a native reviewer to correct, not a finished
translation. The per-file marker says so, and --verify exists to catch the mechanical damage
machine translation does (see below).

Machine translation mangles three things we have to defend against:

  * MessageFormat placeholders. IzPack runs every string through MessageFormat, so a lost or
    reordered {0} is a runtime crash, not a typo. They are swapped for opaque markers before
    the text goes out and put back afterwards.
  * XML entities such as &gt;, which appear inside otherwise translatable strings.
  * The scraped Google endpoint the translate library defaults to, which prefixes its reply
    with a list index ("13. ") and appends a space. Both are stripped, and a translation that
    still looks like a list index after stripping is rejected rather than written.

Two sources, and the table always wins:

  --table  a TSV of "lang<TAB>id<TAB>text", written by hand. Authoritative: these are
           reviewed translations and they replace whatever is currently in the pack, so a
           language can be filled in over several sittings without redoing earlier work.
  --langs  machine translation for anything the table does not cover. A convenience for
           producing a draft to review, never a substitute for the table.

Usage:
  gen-izpack-langpack-strings.py --table tsv      apply reviewed translations
  gen-izpack-langpack-strings.py --langs ara,aze  machine translate the remainder
  gen-izpack-langpack-strings.py --verify          report empty or damaged strings
  gen-izpack-langpack-strings.py --source          print the ids and English to translate
"""

import argparse
import os
import re
import subprocess
import sys
import unicodedata

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

TREES = {
    4: "installer/lib/izpack/4/patches/bin/langpacks/installer",
    5: "installer/lib/izpack/5/patches/resources/installer",
}
# Both versions are written as UTF-8. IzPack 4's langpacks upstream declare iso-8859-1,
# which cannot represent Arabic, Hebrew, Devanagari, Thai, Tibetan or Bengali at all, so
# the declaration is rewritten along with the bytes. That is safe because IzPack parses a
# langpack through XMLParser, which builds its InputSource from the raw stream without
# calling setEncoding, so the declaration in the document is what governs - verified by
# loading a generated pack through izpack's own LocaleDatabase.
WRITE_ENCODING = {4: "utf-8", 5: "utf-8"}

# Languages the distributions do not ship, so the skeletons are ours.
PENDING = ("ara", "aze", "ben", "bod", "est", "heb", "hin",
           "pus", "slv", "swa", "tha", "fil", "urd", "vie")

# IzPack builds a few resource ids at run time, by concatenating a key with a locale
# token, so a scan of the panel sources for string literals never sees them. Without
# these a language gets an English start-menu folder name and the default date format.
# izpack-required-strings.py cannot find them by design; they are listed here instead.
RUNTIME = {
    # The KDE start menu directory. Every upstream pack leaves this as the literal
    # "K-Menu" because it names a real directory, not something translatable.
    "ShortcutPanel.regular.StartMenu:K-Menu": ("K-Menu", True),
    # The freedesktop/GNOME start menu directory, which packs do localize.
    "ShortcutPanel.regular.StartMenu:Start-Menu": ("Start-Menu", False),
    # SimpleDateFormat pattern for the install log header.
    "log.timeStamp": ("dd.MM.yyyy HH:mm:ss", False),
    # Section separator in the shortcut text file. Blank upstream, blank here.
    "ShortcutPanel.textFile.header": ("", True),
}

# Entries that are empty in every catalogue on purpose, so a run over a fully
# translated set reports nothing rather than 5 expected empties per language.
#   installer.madewith          blanked to drop the "Made with IzPack" branding
#   InstallPanel.begin          a single space in the English source; IzPack appends
#                               the product name straight after it
#   ShortcutPanel.textFile.header  blank upstream, a section separator
#   log.message_, log.warning_ blank upstream, prefixes used only when a report
#                               actually contains messages or warnings
EXPECTED_EMPTY = {"installer.madewith", "InstallPanel.begin",
                  "ShortcutPanel.textFile.header", "log.message_", "log.warning_"}

# The console's code for each, which is also the catalogue suffix.
CONSOLE_CODE = {"ara": "ar", "aze": "az", "ben": "bn", "bod": "bo", "est": "et",
                "heb": "he", "hin": "hi", "pus": "ps", "slv": "sl", "swa": "sw",
                "tha": "th", "fil": "tl", "urd": "ur", "vie": "vi"}

MARK = "__PH%d__"
PLACEHOLDER = re.compile(r"\{\d+\}|&[a-zA-Z]+;|\\[a-z]|\$\{?[A-Za-z_][A-Za-z0-9_]*\}?")

# Strings that must never be translated: date and number formats, layout rules, and the
# literal names of desktop-environment menu locations. Machine translation reliably mangles
# them - "MM-dd-yyyy [HH:mm:ss] zzzz" comes back as "MM-DDYY [HH:mm: ss] zzzz", which would
# print wrong dates in the install report rather than merely look odd.
PROTECTED = frozenset((
    "log.timeStamp",
    "ShortcutPanel.regular.StartMenu:K-Menu",
    "ShortcutPanel.regular.StartMenu:Start-Menu",
    "ShortcutPanel.textFile.header",
))

# The endpoint caps a query at 500 characters and there is no chunking that would preserve
# message structure, so a longer string is left for a human rather than mangled.
MAX_QUERY = 460

# English text for every id, from the distribution the skeletons stand in for.
ENG_XML = {
    4: ("installer/lib/izpack/4/standalone-compiler.jar",
        "bin/langpacks/installer/eng.xml"),
    5: ("installer/lib/izpack/5/izpack-5.2.7/lib/izpack-core-5.2.7.jar",
        "com/izforge/izpack/bin/langpacks/installer/eng.xml"),
}


def english_for(version):
    import zipfile
    jar, entry = ENG_XML[version]
    with zipfile.ZipFile(os.path.join(REPO, jar)) as zf:
        raw = zf.read(entry).decode("iso-8859-1")
    return dict(re.findall(r'<str id="([^"]+)" txt="([^"]*)"', raw))

DRAFT_MARKER = (
    "<!-- Machine-drafted on first fill: taken from our own catalogues where one had the\n"
    "         string, machine translated otherwise. Needs a native reviewer's pass before\n"
    "         it should be treated as a finished translation. -->\n"
)


def norm(text):
    text = unicodedata.normalize("NFC", text or "").strip()
    text = re.sub(r"\s+", " ", text)
    return text.rstrip(":.").strip().lower()


def load_catalogues():
    """console code -> {normalised English msgid: translation}, from every .po in the tree."""
    import glob
    import collections
    index = collections.defaultdict(dict)
    for path in glob.glob(os.path.join(REPO, "**/messages_*.po"), recursive=True):
        name = os.path.basename(path)[:-len(".po")]
        if not name.startswith("messages_"):
            continue
        code = name[len("messages_"):]
        text = open(path, encoding="utf-8", errors="replace").read()
        for m in re.finditer(r'^msgid\s+"((?:[^"\\]|\\.)*)"\s*\n^msgstr\s+"((?:[^"\\]|\\.)*)"',
                             text, re.M):
            mid, mstr = m.group(1), m.group(2)
            if mid and mstr.strip():
                index[code].setdefault(norm(mid), mstr)
    return index


# A bare "Quit" comes back as "[translation of the term: Quit]" rather than a translation:
# the endpoint treats a single word as a glossary lookup. Prefixing a carrier that it leaves
# alone forces a real translation, and the carrier is stripped again afterwards.
CARRIER = "0000 "
GLOSS = re.compile(r"^\[[^\]]*\]$")


class Translator:
    """Machine translation with placeholders protected and endpoint noise removed."""

    def __init__(self, cache_path=None):
        self._by_lang = {}
        self.cache = {}
        self.cache_path = cache_path
        if cache_path and os.path.exists(cache_path):
            try:
                import json
                with open(cache_path, encoding="utf-8") as fh:
                    raw = json.load(fh)
                self.cache = {(e[0], e[1]): v for e, v in raw}
            except Exception:  # noqa: BLE001 - a damaged cache is not worth failing over
                self.cache = {}

    def _save(self):
        if not self.cache_path:
            return
        import json
        tmp = self.cache_path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump([[k[0], k[1], v] for k, v in self.cache.items()], fh)
        os.replace(tmp, self.cache_path)

    def _backend(self, code):
        if code not in self._by_lang:
            from translate import Translator as Backend
            self._by_lang[code] = Backend(to_lang=code, from_lang="en", backend="google")
        return self._by_lang[code]

    def __call__(self, text, code):
        if not text or not text.strip():
            return text
        key = (text, code)
        if key in self.cache:
            return self.cache[key]

        marks = []

        def protect(m):
            marks.append(m.group(0))
            return MARK % (len(marks) - 1)

        protected = PLACEHOLDER.sub(protect, text)
        try:
            out = self._backend(code).translate(protected)
            if GLOSS.match(out.strip()) and not protected.startswith(CARRIER):
                # Retry inside a carrier, then drop it.
                out = self._backend(code).translate(CARRIER + protected)
                if out.startswith(CARRIER):
                    out = out[len(CARRIER):]
                else:
                    marker = out.split(None, 1)
                    out = marker[1] if len(marker) == 2 else out
        except Exception as exc:  # noqa: BLE001 - a backend failure must not abort the run
            # Returning the English source here would be the worst outcome: it would be
            # written into the pack looking like a translation. None means "leave it for
            # a human", and --verify will report it.
            sys.stderr.write("  MT failed for %s: %s\n" % (code, str(exc)[:90]))
            return None
        out = clean(out)
        for n, original in enumerate(marks):
            # The endpoint sometimes pads a marker with spaces, so match loosely.
            out = re.sub(r"__\s*PH\s*%d\s*__" % n, lambda _m, o=original: o, out)
        self.cache[key] = out
        self._save()
        return out


def clean(text):
    """Strip the scraped endpoint's list index and the padding it adds."""
    text = unicodedata.normalize("NFC", text or "").strip()
    text = re.sub(r"^\d{1,3}\.\s*", "", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text


def looks_damaged(text, original):
    """Reject output that is empty, still a gloss, untranslated, or structurally damaged.

    Each clause is a failure mode actually observed from the endpoint rather than a
    hypothetical: a list index prefix, a bracketed glossary gloss, a marker it padded,
    a placeholder it dropped, or the English coming back untouched.
    """
    if not text or not text.strip():
        return True
    if re.match(r"^\d{1,3}\.\s", text):
        return True
    if GLOSS.match(text.strip()):
        return True
    if re.search(r"__\s*PH\s*\d+\s*__", text):
        return True
    if set(PLACEHOLDER.findall(original)) != set(PLACEHOLDER.findall(text)):
        return True
    if normalise_ws(text) == normalise_ws(original):
        return True          # came back untranslated; an empty cell is more honest
    return False


def normalise_ws(text):
    return re.sub(r"\s+", " ", text or "").strip()


def unescape(text):
    import html as _html
    return _html.unescape(text or "")


def looks_like_prose(text):
    """False for format patterns, separators and other non-translatable furniture."""
    letters = sum(c.isalpha() for c in text)
    return letters >= 2 and letters / max(len(text), 1) > 0.4


def esc(text):
    """Escape for an XML attribute value, exactly once.

    The source we read out of eng.xml is already escaped - it contains "&gt;" - so escaping
    it again is what produced "&amp;gt;", which renders as a literal "&gt;" in the installer.
    Unescape first, translate the real text, then escape once on the way out.
    """
    import html as _html
    return (_html.escape(text, quote=True)
            .replace("'", "&apos;"))


def fill(path, version, console_code, catalogue, translate, english):
    raw = open(path, "rb").read()
    text = raw.decode(WRITE_ENCODING[version])
    stats = {"harvested": 0, "machine": 0, "left": 0, "damaged": 0, "protected": 0}

    def replace(m):
        prefix, sid = m.group(1), m.group(2)
        source = english.get(sid, "")
        if sid in PROTECTED or len(source) > MAX_QUERY or not looks_like_prose(source):
            stats["protected"] += 1
            return '%s<str id="%s" txt="%s"/>' % (prefix, sid, source)
        source = unescape(source)
        found = catalogue.get(norm(source))
        if found:
            stats["harvested"] += 1
            return '%s<str id="%s" txt="%s"/>' % (prefix, sid, esc(found))
        out = translate(source, console_code)
        if out is None or looks_damaged(out, source):
            stats["damaged"] += 1
            return '%s<str id="%s" txt=""/>' % (prefix, sid)
        if out.strip():
            stats["machine"] += 1
        else:
            stats["left"] += 1
            return '%s<str id="%s" txt=""/>' % (prefix, sid)
        return '%s<str id="%s" txt="%s"/>' % (prefix, sid, esc(out))

    # Only empty values are filled, so re-running never re-translates settled strings.
    text = re.sub(r'( *)<str id="([^"]+)" txt="([^"]*)"/>',
                  lambda m: (m.group(0) if m.group(3).strip()
                             else replace(m)),
                  text)
    text = re.sub(r'(<\?xml[^>]*encoding=")[^"]*(")',
                  lambda m: m.group(1) + "UTF-8" + m.group(2), text, count=1)
    if DRAFT_MARKER.split("\n")[0] not in text:
        text = text.replace("<izpack:langpack", DRAFT_MARKER + "<izpack:langpack", 1)
        text = text.replace("<langpack>", DRAFT_MARKER + "<langpack>", 1)
    # Encode before opening: opening for write truncates, so an encoding error part way
    # through would leave a zero-byte langpack and every later run would silently see an
    # empty file and report nothing to do.
    payload = text.encode(WRITE_ENCODING[version])
    open(path, "wb").write(payload)
    return stats


def apply_table(path):
    """Write reviewed translations into the packs, replacing whatever is there.

    Keyed by id rather than by position so a language can be filled in across several
    sittings, and so a stale translation for a string that no longer exists is reported
    rather than silently written somewhere harmless.
    """
    wanted = {}
    with open(path, encoding="utf-8") as fh:
        for lineno, line in enumerate(fh, 1):
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) != 3:
                raise SystemExit("%s:%d: expected lang<TAB>id<TAB>text, got %d field(s)"
                                 % (path, lineno, len(parts)))
            val = (parts[2].replace("\\\\", "\\x00").replace("\\n", "\n")
                   .replace("\\t", "\t").replace("\\r", "\r").replace("\\x00", "\\"))
            wanted.setdefault(parts[0], {})[parts[1]] = val

    for lang, entries in wanted.items():
        for sid, (val, fixed) in RUNTIME.items():
            if sid in entries and fixed and entries[sid] != val:
                raise SystemExit("%s: %s is a literal, not translatable: %r"
                                 % (lang, sid, entries[sid]))
    unknown = sorted(set(wanted) - set(PENDING))
    if unknown:
        raise SystemExit("table has languages with no skeleton: %s" % ", ".join(unknown))

    total = 0
    for lang, entries in sorted(wanted.items()):
        placed = 0
        for version, tree in TREES.items():
            target = os.path.join(REPO, tree, lang + ".xml")
            if not os.path.exists(target):
                continue
            raw = open(target, "rb").read()
            text = raw.decode(WRITE_ENCODING[version])
            ids = set(re.findall(r'<str id="([^"]+)"', text))

            def swap(m):
                nonlocal placed
                if m.group(2) not in entries:
                    return m.group(0)
                placed += 1
                return '%s<str id="%s" txt="%s"/>' % (
                    m.group(1), m.group(2), esc(entries[m.group(2)]))

            text = re.sub(r'( *)<str\s+id="([^"]+)"\s+txt="[^"]*"\s*/>', swap, text)
            text = re.sub(r'(<\?xml[^>]*encoding=")[^"]*(")',
                          lambda m: m.group(1) + "UTF-8" + m.group(2), text, count=1)
            payload = text.encode(WRITE_ENCODING[version])
            open(target, "wb").write(payload)
            missing = sorted(set(entries) - ids)
            if missing:
                sys.stderr.write("  %s izpack%d: ids not in pack: %s\n"
                                 % (lang, version, ", ".join(missing)))
        print("%-4s applied %d translation(s)" % (lang, placed))
        total += placed
    print("total %d" % total, file=sys.stderr)
    return 0


def verify():
    bad = 0
    for version, tree in TREES.items():
        for iso3 in PENDING:
            path = os.path.join(REPO, tree, iso3 + ".xml")
            if not os.path.exists(path):
                continue
            text = open(path, "rb").read().decode(WRITE_ENCODING[version])
            empty = [i for i in re.findall(
                r'<str\s+id="([^"]+)"\s+txt="\s*"\s*/>', text)
                if i not in EXPECTED_EMPTY]
            bad += len(empty)
            print("izpack%d %-4s %s" % (version, iso3,
                                        "ok" if not empty
                                        else "%d EMPTY: %s" % (len(empty),
                                                               " ".join(empty))))

    # Counting empty values is not enough. apply_table rewrites ids in place, so a
    # pattern that fails to match leaves the English source sitting in the pack -
    # which is not empty, so the count above calls it fine and a translated language
    # ships in English. Compare against the reviewed table instead, and say which id
    # is untranslated rather than only how many.
    want = load_expected()
    reach = reachable_per_version()
    for lang, entries in sorted(want.items()):
        for version, tree in TREES.items():
            path = os.path.join(REPO, tree, lang + ".xml")
            if not os.path.exists(path):
                continue
            have = str_values(open(path, "rb").read().decode(WRITE_ENCODING[version]))
            # Two different failures, reported apart. A pack legitimately lacks the
            # ids of panels that version does not have - IzPack 4 has no reboot
            # prompt and no alternate shortcut path - so a missing id is only a
            # problem when that version actually reaches for it. And an id that IS
            # present but holds something other than the reviewed text means the
            # rewrite silently missed it, which is invisible to the empty count.
            absent = sorted(k for k in entries
                            if k not in have and k in reach.get(version, ()))
            wrong = sorted(k for k, v in entries.items()
                           if k in have and have[k] != esc(v))
            if absent:
                bad += len(absent)
                print("izpack%d %-4s %d REACHABLE BUT MISSING: %s"
                      % (version, lang, len(absent), ", ".join(absent[:6])
                         + (" ..." if len(absent) > 6 else "")))
            if wrong:
                bad += len(wrong)
                print("izpack%d %-4s %d NOT APPLIED: %s"
                      % (version, lang, len(wrong), ", ".join(wrong[:6])
                         + (" ..." if len(wrong) > 6 else "")))
    if not bad:
        print("every reviewed translation is present in the packs that use it")
    return 1 if bad else 0


def reachable_per_version():
    """{version: set of ids} from izpack-required-strings.py, or {} if unavailable."""
    out = {}
    for v in ("4", "5"):
        try:
            r = subprocess.run(
                [os.path.join(REPO, "installer/lib/izpack/izpack-required-strings.py"),
                 "--version", v], capture_output=True, text=True, check=True)
        except (OSError, subprocess.CalledProcessError):
            return {}
        out[v] = set(r.stdout.split())
    return out


def load_expected():
    """{lang: {id: translation}} from the reviewed tables under /tmp.

    Best effort: without them --verify still reports the empty-value counts, which is
    what it did before. The tables are a scratch artifact, not something to commit, so
    their absence must not turn into a hard failure.
    """
    out = {}
    d = "/tmp/opencode/welcome"
    if not os.path.isdir(d):
        return out
    for name in os.listdir(d):
        if not name.endswith(".tsv"):
            continue
        with open(os.path.join(d, name), encoding="utf-8") as fh:
            for line in fh:
                parts = line.rstrip("\n").split("\t")
                if len(parts) != 3 or parts[0] not in PENDING:
                    continue
                val = (parts[2].replace("\\\\", "\\x00").replace("\\n", "\n")
                       .replace("\\t", "\t").replace("\\r", "\r")
                       .replace("\\x00", "\\"))
                out.setdefault(parts[0], {})[parts[1]] = val
    return out


def str_values(text):
    """{id: txt} for every entry, tolerating the optional space before the slash."""
    return {m.group(1): m.group(2)
            for m in re.finditer(r'<str\s+id="([^"]+)"\s+txt="([^"]*)"\s*/>', text)}


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--langs", help="comma separated ISO3 codes; default all pending")
    ap.add_argument("--verify", action="store_true")
    ap.add_argument("--cache", help="JSON cache of MT results (default /tmp/opencode/...)")
    ap.add_argument("--table", help="TSV: lang, id, reviewed translation")
    ap.add_argument("--source", action="store_true",
                    help="print 'id<TAB>english' for every translatable string")
    args = ap.parse_args()
    if args.verify:
        return verify()

    if args.source:
        # IzPack 4 and 5 ship different panel sets, so each version has ids the other
        # lacks. Listing one version's ids and translating them would silently leave the
        # other version's extras in English, so ask for the union and translate both.
        eng = english_for(5)
        eng.update(english_for(4))
        reach = set()
        for v in ("4", "5"):
            reach.update(subprocess.run(
                [os.path.join(REPO, "installer/lib/izpack/izpack-required-strings.py"),
                 "--version", v], capture_output=True, text=True, check=True).stdout.split())
        reach = sorted(reach)
        for sid, (val, _fixed) in sorted(RUNTIME.items()):
            val = val.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
            print("%s\t%s" % (sid, val))
        for sid in reach:
            if sid in PROTECTED:
                continue
            # Escape so one record is always one line: LockFile.exists.prompt carries
            # embedded newlines, and a raw multi-line value silently breaks the TSV.
            val = unescape(eng.get(sid, "")).replace("\\", "\\\\")
            val = val.replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n")
            print("%s\t%s" % (sid, val))
        return 0

    if args.table:
        return apply_table(args.table)

    langs = args.langs.split(",") if args.langs else list(PENDING)
    unknown = [c for c in langs if c not in CONSOLE_CODE]
    if unknown:
        sys.exit("no console mapping for: %s" % ", ".join(unknown))

    catalogues = load_catalogues()
    translate = Translator(args.cache or "/tmp/opencode/izpack-mt-cache.json")
    for iso3 in langs:
        code = CONSOLE_CODE[iso3]
        catalogue = catalogues.get(code, {})
        for version, tree in TREES.items():
            path = os.path.join(REPO, tree, iso3 + ".xml")
            if not os.path.exists(path):
                sys.stderr.write("no skeleton: %s\n" % path)
                continue
            stats = fill(path, version, code, catalogue, translate,
                        english_for(version))
            print("izpack%d %-4s harvested=%-3d machine=%-3d protected=%-3d damaged=%-3d empty=%d"
                  % (version, iso3, stats["harvested"], stats["machine"],
                     stats["protected"], stats["damaged"], stats["left"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())