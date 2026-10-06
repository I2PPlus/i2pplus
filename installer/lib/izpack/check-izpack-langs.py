#!/usr/bin/env python3
"""Check that every language the console offers is fully installable.

/configui drives the router console's language from ConfigUIHelper.langs. The
installer has to be able to offer the same set, which means five things per
language, and it is easy for them to drift apart because they live in different
places:

  1. a translation bundle, so the console has something to show;
  2. a <langpack iso3=...> entry in the IzPack 4 descriptor;
  3. the same in the IzPack 5 descriptor;
  4. a langpack XML reachable on that version's compiler classpath - either
     shipped by the distribution or supplied in our patches tree;
  5. a flag GIF reachable on that version's classpath, for the same reason.
     Both compilers hardcode the .gif extension and fail the build when the file
     is missing, so this is not optional.

The inverse also matters: a language in an installer descriptor that /configui
cannot select is a language no user can reach coherently, so those are reported
as orphans rather than left alone.

The iso3-to-flag mapping is read from gen-izpack-flags.sh --list rather than
re-derived, so there is exactly one place that knows how a console language maps
onto IzPack's three-letter codes.

Exits non-zero if any language is incomplete or any orphan is present.
"""

import os
import re
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))

CONSOLE_LANGS = "apps/routerconsole/java/src/net/i2p/router/web/helpers/ConfigUIHelper.java"
IZ4_JAR = "installer/lib/izpack/4/standalone-compiler.jar"
IZ5_JAR = "installer/lib/izpack/5/izpack-5.2.7/lib/izpack-core-5.2.7.jar"
IZ4_XML = "installer/lib/izpack/4/install.xml"
IZ5_XML = "installer/lib/izpack/5/install5.xml"
IZ4_LANGPACKS = "installer/lib/izpack/4/patches/bin/langpacks/installer/%s.xml"
IZ5_LANGPACKS = "installer/lib/izpack/5/patches/resources/installer/%s.xml"
# One flag per language, shared by both IzPack versions. Each build stages this
# directory to the path its own compiler hardcodes; see build.xml izpack-patches
# and izpack5-patches.
FLAGS = "installer/lib/izpack/resources/flags/%s.gif"


# English is the template every other catalog is translated from, so its msgstr
# entries are empty by design and it has no filled bundle to find.
NO_BUNDLE_EXPECTED = ("en",)


def console_languages():
    """(iso639_1, flag code) for everything /configui offers, in source order."""
    src = open(os.path.join(REPO, CONSOLE_LANGS), encoding="utf-8").read()
    block = re.search(r"private static final String\[\]\[\] langs = \{(.*?)\n    \};",
                      src, re.S).group(1)
    block = re.sub(r"//.*", "", block)          # drop the commented-out entries
    out = []
    for m in re.finditer(r'\{\s*"([a-zA-Z_]+)"\s*,\s*"([^"]+)"', block):
        if m.group(1) != "xx":                  # xx is "untagged strings", not a language
            out.append((m.group(1), m.group(2)))
    return out


def flag_mapping():
    """console code -> {iso3: flag stem}, from the flag generator.

    Keyed by the console code rather than by flag stem, because a stem is
    ambiguous: "in" is the flag for both Hindi and Indonesian.
    """
    out = subprocess.run([os.path.join(HERE, "gen-izpack-flags.sh"), "--list"],
                         capture_output=True, text=True, check=True).stdout
    mapping = {}
    for line in out.split("\n"):
        parts = line.split()
        if len(parts) == 3:
            mapping.setdefault(parts[0], {})[parts[1]] = parts[2]
    return mapping


def declared(path):
    """iso3 codes from a descriptor, comments stripped."""
    text = re.sub(r"<!--.*?-->", "", open(os.path.join(REPO, path),
                                         encoding="iso-8859-1").read(), flags=re.S)
    return {m.group(1) for m in re.finditer(r'<langpack iso3="([a-z]+)"', text)}


def jar_langpacks(jar, prefix):
    with zipfile.ZipFile(os.path.join(REPO, jar)) as zf:
        return {n[len(prefix):-len(".xml")] for n in zf.namelist()
                if n.startswith(prefix) and n.endswith(".xml")}


def bundle_counts():
    """iso639_1 -> number of translated msgids across every catalog."""
    import glob
    import collections
    counts = collections.defaultdict(int)
    for path in glob.glob(os.path.join(REPO, "**/messages_*.po"), recursive=True):
        name = os.path.basename(path)[:-len(".po")]
        if not name.startswith("messages_"):
            continue
        code = name[len("messages_"):]
        text = open(path, encoding="utf-8", errors="replace").read()
        counts[code] += len(re.findall(r'^msgstr\s+"[^"]+\S', text, re.M))
    return counts


# IzPack selects a language by matching the declared <langpack iso3=...> against the ISO3
# codes of the JVM's available locales, and warns "No locale for: xx" when nothing matches.
# Several plausible-looking codes never match on a modern JVM - Filipino is exposed only as
# "fil", never "tgl"; Persian as "fas", never "fa"; Java collapses zh_TW to "zho", so "twn"
# is unreachable. A declared code that cannot resolve is a language the installer offers but
# can never actually select, so it is checked here rather than discovered at runtime.
_PROBE = """
import java.util.*;
public class IzpackLocaleProbe {
    public static void main(String[] a) {
        Set<String> s = new TreeSet<>();
        for (Locale l : Locale.getAvailableLocales())
            try { String i = l.getISO3Language(); if (i != null && !i.isEmpty()) s.add(i); }
            catch (Exception e) { }
        for (String c : a) System.out.println(c + "\t" + s.contains(c));
    }
}
"""


def resolvable_iso3(codes):
    """Subset of `codes` the running JVM could actually match, per a JDK probe.

    Returns None when no JDK is available, so the check degrades to a warning rather
    than a false failure on a machine without javac.
    """
    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        src = os.path.join(tmp, "IzpackLocaleProbe.java")
        with open(src, "w") as fh:
            fh.write(_PROBE)
        r = subprocess.run(["java", src] + list(codes), capture_output=True, text=True)
        if r.returncode != 0:
            return None
        out = {}
        for line in r.stdout.split("\n"):
            if "\t" in line:
                c, ok = line.split("\t", 1)
                out[c] = ok.strip() == "true"
        return out


def main():
    langs = console_languages()
    mapping = flag_mapping()
    bundles = bundle_counts()

    d4, d5 = declared(IZ4_XML), declared(IZ5_XML)
    z4 = jar_langpacks(IZ4_JAR, "bin/langpacks/installer/")
    z5 = jar_langpacks(IZ5_JAR, "com/izforge/izpack/bin/langpacks/installer/")

    def patch(pattern, code):
        return os.path.exists(os.path.join(REPO, pattern % code))

    problems = []
    covered = {"iz4": set(), "iz5": set()}
    all_codes = sorted({c for m in mapping.values() for c in m})
    resolvable = resolvable_iso3(all_codes)
    if resolvable is None:
        print("note: no JDK available, skipping the JVM locale resolvability check\n")

    print("%-6s %-9s %-8s %-6s %-6s %-6s" %
          ("LANG", "FLAG", "BUNDLE", "IZ4", "IZ5", "NOTE"))
    for code, flag in langs:
        # Every IzPack code this console language can be reached by, as spelled by
        # either distribution.
        codes = sorted(mapping.get(code, {}))
        if not codes:
            problems.append("%s: offered by /configui but gen-izpack-flags.sh maps no "
                            "IzPack code to it" % code)
            print("%-6s %-9s %-8s %-6s %-6s %-6s" %
                  (code, flag, "-", "-", "-", "NO IZPACK CODE"))
            continue

        nb = bundles.get(code, 0)
        has_bundle = nb > 50 or code in NO_BUNDLE_EXPECTED

        declared4 = [c for c in codes if c in d4]
        declared5 = [c for c in codes if c in d5]
        # Resolved per code, not per language. zh and zh_TW both map to zho, so a
        # per-language test is satisfied by one code and hides the other having no
        # pack at all - which is exactly what happened to zho on IzPack 4, whose
        # distribution only ships the legacy chn and twn spellings.
        # Deliberately no legacy-spelling fallback here. gen-izpack-langpacks.py
        # stages a copy of the distribution's chn/cze/ned/rom/svk/fa packs under the
        # modern name the descriptor declares, and the compiler only ever looks for
        # that modern name - so a missing staged copy is a real gap, not a licence
        # to fall back to the spelling IzPack 4 happens to ship.
        use4 = [c for c in codes if c in z4 or patch(IZ4_LANGPACKS, c)]
        use5 = [c for c in codes
                if c in z5 or patch(IZ5_LANGPACKS, c)]
        # Our shared flag satisfies both. Otherwise fall back to whichever flags the
        # distribution itself ships, which still differ: IzPack 4 carries ind and
        # por, IzPack 5 carries neither, so a language can be covered for one
        # version and not the other and it is worth reporting separately.
        gif4 = [c for c in codes if patch(FLAGS, c) or c in jar_flags(
            IZ4_JAR, "bin/langpacks/flags/")]
        gif5 = [c for c in codes if patch(FLAGS, c) or c in jar_flags(
            IZ5_JAR, "com/izforge/izpack/bin/langpacks/flags/")]
        covered["iz4"].update(declared4)
        covered["iz5"].update(declared5)

        notes = []
        # A language is fine as long as one of its declared codes resolves. Some
        # languages have several spellings and only one is reachable - zh_TW is
        # served by zho because Java collapses it - so the test is per language,
        # not per code. It is the "no code at all resolves" case that means the
        # installer offers a language it can never select.
        if resolvable is not None:
            live = [c for c in declared4 + declared5 if resolvable.get(c, True)]
            if declared4 + declared5 and not live:
                notes.append("no JVM locale resolves %s"
                             % "/".join(sorted(set(declared4 + declared5))))
        if not has_bundle:
            notes.append("no translation bundle")
        if not declared4:
            notes.append("not declared in izpack 4")
        if not declared5:
            notes.append("not declared in izpack 5")
        for label, declared_codes, resolvable_codes in (
                ("izpack 4", declared4, use4), ("izpack 5", declared5, use5)):
            orphan = sorted(set(declared_codes) - set(resolvable_codes))
            if orphan:
                notes.append("declared with no langpack on %s: %s"
                             % (label, "/".join(orphan)))
        if not gif4:
            notes.append("no izpack 4 flag")
        if not gif5:
            notes.append("no izpack 5 flag")
        if notes:
            problems.append("%s: %s" % (code, "; ".join(notes)))

        print("%-6s %-9s %-8s %-6s %-6s %-6s" % (
            code, flag,
            "%d" % nb if code not in NO_BUNDLE_EXPECTED else "template",
            "/".join(declared4) or "-", "/".join(declared5) or "-",
            "; ".join(notes) or "ok"))

    # Orphans: declared by an installer but not selectable on the console.
    served = set()
    for code, flag in langs:
        served.update(mapping.get(code, {}))
    for label, declared_codes, served_codes in (("izpack 4", d4, served),
                                                ("izpack 5", d5, served)):
        orphans = sorted(declared_codes - served_codes)
        if orphans:
            problems.append("%s declares languages /configui cannot select: %s"
                            % (label, " ".join(orphans)))

    # Two invariants that only a second tool can see: every declared code resolves to
    # a pack the compiler can actually reach, and no reachable pack renders "Made with
    # IzPack". Neither is checkable from here - the first needs the classpath shadowing
    # IzPack 4 requires, the second needs to read the packs rather than the console.
    branding = subprocess.run(
        [sys.executable, os.path.join(REPO, "installer/lib/izpack/gen-izpack-langpacks.py"),
         "--check"], capture_output=True, text=True)
    if branding.returncode != 0:
        problems.append("langpack reachability / branding:\n%s"
                        % branding.stdout.rstrip())

    print()
    print("console languages: %d | izpack 4 declared: %d | izpack 5 declared: %d"
          % (len(langs), len(d4), len(d5)))
    if problems:
        print("\n%d problem(s):" % len(problems))
        for p in problems:
            print("  %s" % p)
        return 1
    print("every console language is selectable, translated and flagged on both IzPack versions")
    return 0


if __name__ == "__main__":
    sys.exit(main())