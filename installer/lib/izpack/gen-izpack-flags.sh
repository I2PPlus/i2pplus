#!/bin/bash
#
# Generate the language-picker flag GIFs for every language the console offers.
#
# Why this is needed at all: both IzPack compilers hardcode the flag path and
# extension and fail the build when the file is absent.
#
#   IzPack 4  bin/langpacks/flags/<iso3>.gif
#   IzPack 5  com/izforge/izpack/bin/langpacks/flags/<iso3>.gif
#
# Neither offers a PNG, WebP or SVG path, so a flag has to be a GIF however it is
# authored. The console keeps its flags as SVG, so every flag is rasterised from
# there. The size matches what the distributions ship (24x18) because that is
# what the language combo box is laid out for.
#
# The flags live once, in installer/lib/izpack/resources/flags, and each build
# stages them to wherever its compiler looks for them (build.xml: izpack-patches
# folds them into patches.jar for IzPack 4, izpack5-patches copies them beside
# the 5/patches/resources langpacks for IzPack 5). Two copies of the same GIFs
# could only ever disagree, and did once, when the languages were rebalanced.
#
# They are staged for both distributions even where a distribution would have
# supplied the flag itself, because the two ship different sets - IzPack 4 has
# ind and por, IzPack 5 has neither - and the point is that the two installers
# show the same flag for the same language instead of inheriting whatever that
# particular jar happened to carry.
#
# The language list and the flag for each language come from the console itself
# (ConfigUIHelper.langs), so this cannot drift from what /configui offers. The
# only knowledge kept here is the ISO 639-1 to 639-2 mapping, which is IzPack's
# side of the join: IzPack names langpacks with three-letter codes, and the two
# distributions disagree on the spelling for seven languages.
#
# Usage:
#   gen-izpack-flags.sh --ensure    write only the flags that are missing
#   gen-izpack-flags.sh --check     report coverage; write nothing (default)
#   gen-izpack-flags.sh --generate  rewrite every flag, ignoring what exists
#   gen-izpack-flags.sh --list      print "<console code> <iso3> <flag stem>" per line
#   gen-izpack-flags.sh --prune     delete flags no descriptor declares

set -u

rc=0
REPO=$(cd "$(dirname "$0")/../../.." && pwd)
SVG_DIR="$REPO/apps/routerconsole/resources/icons/flags_svg"
CONSOLE_LANGS="$REPO/apps/routerconsole/java/src/net/i2p/router/web/helpers/ConfigUIHelper.java"

IZPACK4_JAR="$REPO/installer/lib/izpack/4/standalone-compiler.jar"
IZPACK5_JAR="$REPO/installer/lib/izpack/5/izpack-5.2.7/lib/izpack-core-5.2.7.jar"

# The one canonical location. Shared by both IzPack versions, so this directory
# is the answer to "where do the flags live".
FLAG_DIR="$REPO/installer/lib/izpack/resources/flags"

need() {
    echo "gen-izpack-flags: $*" >&2
    exit 1
}

# ISO 639-1 -> the IzPack langpack codes that serve it.
#
# Only codes a JVM actually reports are listed. IzPack matches a declared
# <langpack iso3=...> against the ISO3 codes of the JVM's available locales, so a
# plausible-looking code that no locale reports can never be selected - the
# installer offers it and then silently falls back to English. Several of
# IzPack's own codes are affected: it ships Persian as "fa" where a JVM says
# "fas", the legacy Czech/Dutch/Romanian/Slovak/Chinese spellings, and Filipino
# as "tgl" where a JVM only ever says "fil". check-izpack-langs.py probes a JDK
# and fails if any declared code is unresolvable, so these cannot drift back.
ISO3="ar:ara
az:aze
bn:ben
bo:bod
ca:cat
cs:ces
da:dan
de:deu
el:ell
en:eng
es:spa
et:est
fa:fas
fi:fin
fr:fra
he:heb
hi:hin
hu:hun
in:ind
it:ita
ja:jpn
ko:kor
nb:nor
nl:nld
pl:pol
ps:pus
pt:por
ro:ron
ru:rus
sk:slk
sl:slv
sv:swe
sw:swa
th:tha
tl:fil
tr:tur
uk:ukr
ur:urd
vi:vie
zh:zho
zh_TW:zho"

# The console's own language list, as "<iso639-1> <flagcode>" per line. Read from
# the source rather than restated here, so a language added to /configui is picked
# up by --ensure with no edit to this script.
console_langs() {
    sed -n '/private static final String\[\]\[\] langs = {/,/^    };/p' "$CONSOLE_LANGS" \
        | sed 's://.*::' \
        | grep -oE '\{ "[a-zA-Z_]+", "[^"]+"' \
        | sed 's/{ "//; s/", "/ /; s/"$//'
}

# "<console code> <iso3> <svg stem>" for every language the console offers. The
# console code is carried through because the flag stem alone is ambiguous: "in"
# is both the flag for Hindi and for Indonesian, so a consumer cannot infer which
# console language an iso3 code belongs to from the flag.
mapping() {
    local code flag iso3 stem
    while read -r code flag; do
        [ -n "$code" ] || continue
        iso3=$(grep "^$code:" <<< "$ISO3" | cut -d: -f2)
        [ -n "$iso3" ] || continue
        for stem in ${iso3//,/ }; do
            echo "$code $stem $flag"
        done
    done < <(console_langs)
}

in_jar() {
    [ -f "$1" ] || return 1
    unzip -l "$1" 2>/dev/null | grep -q " $2\$"
}

declared() {
    [ -f "$1" ] || return 0
    # A commented-out <langpack> is not declared.
    sed 's/<!--.*-->//' "$1" | grep -o 'iso3="[a-z]*"' | cut -d'"' -f2
}

all_declared() {
    printf '%s\n%s\n' \
        "$(declared "$REPO/installer/lib/izpack/4/install.xml")" \
        "$(declared "$REPO/installer/lib/izpack/5/install5.xml")" | sort -u
}

# The flag stem for an iso3 code, taken from the console row it came from.
flag_for() {
    awk -v iso="$1" '$2 == iso { print $3; exit }' <<< "$(mapping)"
}

in_tree() {
    [ -f "$FLAG_DIR/$1.gif" ]
}

render() {
    # $1 svg stem, $2 iso3
    local src="$SVG_DIR/$1.svg" tmp
    [ -f "$src" ] || need "no such console flag for $2: $src"
    tmp=$(mktemp -d)
    rsvg-convert -w 24 -h 18 --background-color=white "$src" -o "$tmp/flag.png" \
        || need "rsvg-convert failed on $src"
    # 32 colours is ample at this size and keeps the GIF small; the
    # distributions' own flags use up to 104.
    convert "$tmp/flag.png" -colors 32 -dither FloydSteinberg -layers Optimize "$tmp/f.gif" \
        || need "convert failed for $src"
    mkdir -p "$FLAG_DIR"
    cp "$tmp/f.gif" "$FLAG_DIR/$2.gif"
    rm -rf "$tmp"
}

require_rasterisers() {
    command -v rsvg-convert >/dev/null 2>&1 || need "rsvg-convert not found (librsvg2-bin)"
    command -v convert >/dev/null 2>&1 || need "ImageMagick 'convert' not found"
}

# ---- orphan pruning ----
# Nothing references a flag for a code no descriptor declares, so a leftover is
# dead weight that can only drift. They appear whenever a code is respelled - the
# IzPack 4 distribution's chn/cze/ned/rom/svk and the fa/twn Chinese spellings all
# became zho/ces/nld/ron/slk/fas once the codes had to be ones a JVM reports.
prune() {
    local want iso3 f
    want=$(all_declared | sort -u)
    for f in "$FLAG_DIR"/*.gif; do
        [ -e "$f" ] || continue
        iso3=$(basename "$f" .gif)
        if ! grep -qx "$iso3" <<< "$want"; then
            rm -f "$f"
            echo "gen-izpack-flags: pruned unused $iso3.gif" >&2
        fi
    done
}

case "${1:---check}" in
--list)
    mapping
    exit 0
    ;;
--generate)
    require_rasterisers
    while read -r code iso3 stem; do
        [ -n "$iso3" ] || continue
        render "$stem" "$iso3"
        echo "gen-izpack-flags: generated $iso3.gif from $stem.svg"
    done < <(mapping)
    exit 0
    ;;
--ensure)
    # The build path. Writes nothing, and needs no rasteriser, unless a declared
    # langpack has no flag in the shared directory.
    missing=""
    for iso3 in $(all_declared); do
        in_tree "$iso3" || missing="$missing $iso3"
    done
    [ -n "$missing" ] || exit 0
    require_rasterisers
    for iso3 in $missing; do
        stem=$(flag_for "$iso3")
        [ -n "$stem" ] || need "declared langpack '$iso3' has no console flag.
Add it to ConfigUIHelper.langs, or add the ISO 639-1 to three-letter mapping
to the ISO3 table in $0."
        render "$stem" "$iso3"
        echo "gen-izpack-flags: generated $iso3.gif from $stem.svg"
    done
    exit 0
    ;;
--prune)
    prune
    exit 0
    ;;
--check)
    orphans=""
    for f in "$FLAG_DIR"/*.gif; do
        [ -e "$f" ] || continue
        iso3=$(basename "$f" .gif)
        all_declared | grep -qx "$iso3" || orphans="$orphans $iso3"
    done
    if [ -n "$orphans" ]; then
        echo "gen-izpack-flags: flags for codes no descriptor declares:$orphans" >&2
        echo "gen-izpack-flags: run with --prune to remove them" >&2
        rc=1
    fi
    ;;
*) need "unknown option '$1' (try --ensure, --check, --generate, --list)" ;;
esac

# ---- coverage report ----
printf '%-6s %-10s %-10s %-10s %s\n' ISO3 IZPACK4 IZPACK5 FLAGS NOTE
for iso3 in $(all_declared); do
    in4=no; in5=no
    in_jar "$IZPACK4_JAR" "bin/langpacks/flags/$iso3.gif" && in4=yes
    in_jar "$IZPACK5_JAR" "com/izforge/izpack/bin/langpacks/flags/$iso3.gif" && in5=yes
    note=""
    if in_tree "$iso3"; then
        note="shared, from $(flag_for "$iso3").svg"
    else
        note="MISSING FLAG in resources/flags - the build will fail"
        rc=1
    fi
    printf '%-6s %-10s %-10s %-10s %s\n' "$iso3" "$in4" "$in5" "$( [ -n "$note" ] && echo yes || echo no )" "$note"
done
exit $rc