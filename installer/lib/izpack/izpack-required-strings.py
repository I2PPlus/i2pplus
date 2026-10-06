#!/usr/bin/env python3
"""List the IzPack strings our installers can actually display.

IzPack ships one langpack per language covering every panel it implements - 307
strings for IzPack 5, 223 for IzPack 4. Our descriptors use eight panels in
IzPack 5 and five in IzPack 4, so more than half of those strings belong to
panels we never instantiate (JDKPathPanel, CompilePanel, TreePacksPanel, the
licence panels, the printer panel, ...) or to features we do not enable, such as
FinishPanel's "generate an automatic installation script". They cannot appear on
screen and translating them is wasted effort.

This resolves the reachable set from the descriptors themselves rather than from
a hand-kept list, so it cannot drift when a panel is added or removed:

  1. read the <panel classname=...> entries out of the install descriptor,
  2. resolve each panel's superclass chain inside the IzPack distribution, since
     a panel looks up resources as <SimpleClassName>.<suffix> and so inherits
     its ancestors' keys,
  3. emit the ids whose prefix is a reachable panel, plus the framework-level
     prefixes the runtime uses regardless of which panels are present.

Usage:
  izpack-required-strings.py                      # summary for both versions
  izpack-required-strings.py --version 5          # ids only, one per line
  izpack-required-strings.py --version 5 --text   # id + English source
  izpack-required-strings.py --version 5 --template --lang ar
                                               # translation template for a
                                               # language the distributions
                                               # do not ship
  izpack-required-strings.py --skeleton-dir DIR   # write every skeleton

Panels are resolved against the panel jars, which IzPack 4 keeps individually
under bin/panels/ and IzPack 5 bundles into izpack-core.
"""

import argparse
import os
import re
import subprocess
import sys
import tempfile
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

IZ4_JAR = "installer/lib/izpack/4/standalone-compiler.jar"
IZ5_CORE = "installer/lib/izpack/5/izpack-5.2.7/lib/izpack-core-5.2.7.jar"
INSTALL4 = "installer/lib/izpack/4/install.xml"
INSTALL5 = "installer/lib/izpack/5/install5.xml"

# Resource-key prefixes the runtime uses whatever panels are configured: the
# frame's own buttons and dialogs, the uninstaller, the log window and the
# diagnostics that surface on failure.
FRAMEWORK = frozenset(
    ("installer", "uninstaller", "log", "debug", "data", "functionFailed", "LockFile")
)

# Swing/AWT ancestors contribute no resource keys; walking into them only makes
# the chain output noisier.
NOT_A_PANEL = frozenset(
    ("IzPanel", "JPanel", "JComponent", "Container", "Component", "JFrame", "Object")
)

# The console offers these languages (ConfigUIHelper.langs) and neither IzPack
# distribution ships a langpack for them, so we supply one. The codes are what
# Locale.getISO3Language() returns for each, which is how IzPack matches a
# declared <langpack iso3=...> against the JVM's locales - using any other
# spelling would list the language and then never select it.
PENDING = (
    "ara", "aze", "ben", "bod", "est", "heb", "hin",
    "pus", "slv", "swa", "tha", "tgl", "urd", "vie",
)


def path(*parts):
    return os.path.join(REPO, *parts)



def classpath(version):
    """The classpath javap needs in order to see the panel classes.

    IzPack 5 bundles everything into izpack-core. IzPack 4 keeps each panel in
    its own jar under bin/panels/ inside the standalone compiler, and a jar
    inside a jar is not on any classpath, so those have to be unpacked before
    javap can walk a superclass chain.
    """
    if version == 5:
        libdir = path("installer/lib/izpack/5/izpack-5.2.7/lib")
        return os.pathsep.join(
            sorted(os.path.join(libdir, f) for f in os.listdir(libdir) if f.endswith(".jar"))
        )
    with zipfile.ZipFile(path(IZ4_JAR)) as zf:
        nested = [n for n in zf.namelist() if n.endswith(".jar") and n.startswith("bin/panels/")]
    out = [path(IZ4_JAR)]
    if nested:
        tmp = tempfile.mkdtemp(prefix="izpack4-panels-")
        with zipfile.ZipFile(path(IZ4_JAR)) as zf:
            for n in nested:
                with open(os.path.join(tmp, os.path.basename(n)), "wb") as fh:
                    fh.write(zf.read(n))
        out.extend(os.path.join(tmp, os.path.basename(n)) for n in nested)
    return os.pathsep.join(out)


def superclass(fqcn, cp):
    """The superclass of a class, or None. Absent class -> None."""
    try:
        out = subprocess.run(
            ["javap", "-cp", cp, fqcn], capture_output=True, text=True, timeout=60
        ).stdout
    except (OSError, subprocess.SubprocessError):
        return None
    for line in out.split("\n"):
        if not re.match(r"(?:public |abstract |final )*(?:class|interface)\s", line):
            continue
        m = re.search(r"extends\s+([\w.$]+)", line)
        if not m:
            return None
        s = m.group(1)
        return s if "." in s else fqcn.rsplit(".", 1)[0] + "." + s
    return None


def declared_panels(version):
    """Panel class names from the descriptor, comments stripped.

    IzPack 5's descriptor spells panels out in full; IzPack 4's uses the bare
    simple name, so those are mapped back to a class name before use.
    """
    xml = path(INSTALL5 if version == 5 else INSTALL4)
    text = re.sub(r"<!--.*?-->", "", open(xml, encoding="iso-8859-1").read(), flags=re.S)
    names = re.findall(r'<panel\s+classname="([^"]+)"', text)
    if version == 5:
        return names
    index = class_index(version)
    resolved = []
    for n in names:
        if "." in n:
            resolved.append(n)
        elif n in index:
            resolved.append(index[n])
        else:
            raise SystemExit(
                "izpack-required-strings: descriptor names panel '%s', which is in "
                "neither the compiler jar nor its bin/panels jars" % n
            )
    return resolved


def class_index(version):
    """Simple class name -> fully qualified name, over the jars on the classpath."""
    index = {}
    for entry in all_class_entries(version):
        fqcn = entry[: -len(".class")].replace("/", ".")
        index.setdefault(fqcn.rsplit(".", 1)[-1], fqcn)
    return index


def all_class_entries(version):
    names = []
    for jar in classpath(version).split(os.pathsep):
        if not os.path.isfile(jar):
            continue
        with zipfile.ZipFile(jar) as zf:
            names.extend(n for n in zf.namelist() if n.endswith(".class"))
    return names


def panel_prefixes(version, cp):
    """Simple class names whose resource keys our configuration can reach."""
    prefixes = set()
    for panel in declared_panels(version):
        cls = panel
        seen = set()
        while cls and cls not in seen:
            seen.add(cls)
            simple = cls.rsplit(".", 1)[-1]
            if simple not in NOT_A_PANEL:
                prefixes.add(simple)
            cls = superclass(cls, cp)
    return prefixes


def langpack_strings(version):
    jar = IZ5_CORE if version == 5 else IZ4_JAR
    entry_dir = (
        "com/izforge/izpack/bin/langpacks/installer/" if version == 5 else "bin/langpacks/installer/"
    )
    with zipfile.ZipFile(path(jar)) as zf:
        raw = zf.read(entry_dir + "eng.xml").decode("iso-8859-1")
    return re.findall(r'<str id="([^"]+)" txt="([^"]*)"', raw)


def required(version):
    """The (id, english) pairs a langpack must cover for this configuration."""
    cp = classpath(version)
    panels = panel_prefixes(version, cp)
    out = []
    for sid, text in langpack_strings(version):
        if sid.split(".")[0] in panels or sid.split(".")[0] in FRAMEWORK:
            out.append((sid, text))
    return out, panels


def comment_safe(text):
    """Make a string safe to drop inside an XML comment.

    XML forbids "--" anywhere in a comment body, and the English sources contain
    runs like "---  Installation Messages  ---". Spacing every hyphen out is
    lossless for a human reading the file and keeps the comment parseable; a
    single-pass replace of "--" does not, because it leaves "--" behind in a run
    of odd length.
    """
    return re.sub(r"-{2,}", lambda m: "- " * (len(m.group(0)) - 1) + "-", text)


def skeleton(version, pairs, iso3):
    """An untranslated langpack in the format this IzPack version expects.

    IzPack 4 langpacks are latin-1 with a bare <langpack> root and no namespace;
    IzPack 5 ones are UTF-8 with a namespaced root and a schema location. The
    two formats are not interchangeable, so each version gets its own file.

    Every entry is emitted with an empty txt rather than the English source. An
    empty value makes the runtime fall back to the base English pack for that
    one key, whereas copying the English text in would ship it labelled as a
    translation - and would silently rot the moment the English wording changes.
    """
    out = []
    if version == 5:
        out.append('<?xml version="1.0" encoding="UTF-8"?>')
        out.append('<izpack:langpack version="5.0"')
        out.append('                 xmlns:izpack="http://izpack.org/schema/langpack"')
        out.append('                 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"')
        out.append('                 xsi:schemaLocation="http://izpack.org/schema/langpack'
                   ' http://izpack.org/schema/5.0/izpack-langpack-5.0.xsd">')
    else:
        out.append('<?xml version="1.0" encoding="iso-8859-1" standalone="yes" ?>')
        out.append('')
        out.append('<!-- Untranslated skeleton for %s. See'
                   ' installer/lib/izpack/izpack-required-strings.py. -->' % iso3)
        out.append('')
        out.append('<langpack>')
    out.append('')
    # Keep the distribution's panel ordering, which is what a translator reads
    # top to bottom, and makes a diff against the shipped packs meaningful.
    section = None
    for sid, english in pairs:
        head = sid.split(".")[0]
        if head != section:
            section = head
            out.append('    <!-- %s -->' % head)
        out.append("    <!-- %s -->" % comment_safe(english) if english.strip()
                   else "    <!-- TODO -->")
        out.append('    <str id="%s" txt=""/>' % sid)
    out.append('</izpack:langpack>' if version == 5 else '</langpack>')
    out.append('')
    return "\n".join(out)


def write_skeletons(root):
    """Write a skeleton per language per version under the patches trees."""
    written = []
    for version in (4, 5):
        pairs, _ = required(version)
        base = (os.path.join(root, "5") if version == 5 else os.path.join(root, "4"))
        os.makedirs(base, exist_ok=True)
        for iso3 in PENDING:
            path = os.path.join(base, iso3 + ".xml")
            text = skeleton(version, pairs, iso3)
            with open(path, "w", encoding="utf-8" if version == 5 else "iso-8859-1",
                      newline="\n") as fh:
                fh.write(text)
            # Parse it back: a generator that can emit XML it cannot read is worse
            # than no generator, and the compiler would only find out at build time.
            import xml.etree.ElementTree as ET
            try:
                ET.parse(path)
            except ET.ParseError as exc:
                raise SystemExit("%s: generated invalid XML: %s" % (path, exc))
            written.append(path)
    return written


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--version", type=int, choices=(4, 5))
    ap.add_argument("--text", action="store_true", help="include the English source")
    ap.add_argument("--template", action="store_true",
                    help="emit a translation template (implies --text)")
    ap.add_argument("--lang", help="iso3 code for the template's target language")
    ap.add_argument("--skeleton-dir",
                    help="write a skeleton per pending language, under <dir>/4 and <dir>/5")
    args = ap.parse_args()

    if args.skeleton_dir:
        written = write_skeletons(args.skeleton_dir)
        for p in written:
            print(p)
        print("%d skeletons (%d languages x 2 versions)" % (len(written), len(PENDING)))
        return 0

    versions = [args.version] if args.version else [4, 5]
    for version in versions:
        wanted, panels = required(version)
        total = len(langpack_strings(version))
        if args.template:
            if not args.lang:
                ap.error("--template needs --lang")
            print("# izpack %d translation template for '%s'" % (version, args.lang))
            print("# %d strings; untranslated entries must be left empty, not" % len(wanted))
            print("# copied from English, so the installer falls back to English.")
            for sid, text in wanted:
                print('<str id="%s" txt=""/>  <!-- %s -->' % (sid, comment_safe(text)))
            continue
        if args.version and (args.text or not args.template):
            for sid, text in wanted:
                print(("%s\t%s" % (sid, text)) if args.text else sid)
            continue
        dead = total - len(wanted)
        print("izpack %d: %d strings in the distribution, %d reachable, %d dead (%.0f%%)"
              % (version, total, len(wanted), dead, 100.0 * dead / total))
        print("  panels: %s" % ", ".join(sorted(panels)))
    return 0


if __name__ == "__main__":
    sys.exit(main())