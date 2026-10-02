#!/bin/sh
# Pack200-compress (or repack) the update payload staged in pkg-temp.
#
# One script for every POSIX platform, so the ant pack200/repack200 targets no
# longer carry four near-identical copies of the byte-accounting logic. Uses
# the in-tree io.pack200 fork rather than the JDK's pack200 tool, which was
# removed in Java 14: the fork runs on any modern JDK, and the very same jar is
# what ships as lib/pack200.jar so routers can unpack what we pack here.
#
# Configure with environment variables (ant sets these via <exec><env/>):
#   MODE    compress (default) or repack
#   JAVA    java launcher            (default: java on PATH)
#   P200JAR path to pack200.jar      (required)
#   PKGDIR  staging dir with lib/ and webapps/   (required)
#   MARKER  file to record the pre-pack byte count, for zipit200's size report
#   SERIAL  set to 1 to force serial even when GNU parallel is installed
#
# GNU parallel is used when present because --effort=5 is slow, but it is no
# longer required: without it we fall back to a serial loop.
#
# "script --one <file>" is the worker entry point used by the parallel path: it
# handles exactly one file and nothing else, so the parent never has to export
# shell functions into a subshell.

set -e

MODE="${MODE:-compress}"
JAVA="${JAVA:-java}"
P200JAR="${P200JAR:-}"
PKGDIR="${PKGDIR:-}"
MARKER="${MARKER:-}"
SELF=$(cd "$(dirname "$0")" && pwd)/$(basename "$0")

# The fork's CLI entry point is io.pack200.Driver; io.pack200.Pack200 has no
# main(). -g/--no-gzip emits a plain .pack stream, which is what FileUtil
# expects to find as .jar.pack / .war.pack inside the update zip.
pack() {
    "$JAVA" -cp "$P200JAR" io.pack200.Driver "$@"
}

# compress_one <file> - rename to .jar, pack to <name>.pack, drop the source.
# pack200 refuses to write its output over its input, hence the rename.
compress_one() {
    local f="$1"
    mv "$f" "$f.jar"
    pack --effort=5 --modification-time=latest --segment-limit=-1 -g "$f.pack" "$f.jar"
    rm -f "$f.jar"
}

# repack_one <file> - normalise a jar in place for jarsigner (pack200 -r).
# No rename dance: Driver's repack mode takes the jar itself as the target and
# writes back over it via a temp file, and rejects a first argument ending in
# .pack/.pac/.gz. Verified byte-identical with and without the rename.
repack_one() {
    local f="$1"
    pack -r "$f"
}

# ---- worker mode: one file, no aggregation ----
if [ "$1" = "--one" ]; then
    [ -n "$P200JAR" ] || { echo "ERROR: P200JAR not set" >&2; exit 1; }
    [ -f "$P200JAR" ] || { echo "ERROR: pack200.jar not found at $P200JAR" >&2; exit 1; }
    f="$2"
    [ -n "$f" ] && [ -f "$f" ] || { echo "ERROR: no such file: '$f'" >&2; exit 1; }
    if [ "$MODE" = repack ]; then
        repack_one "$f"
    else
        compress_one "$f"
    fi
    exit 0
fi

usage() {
    echo "Usage: MODE=compress|repack JAVA=... P200JAR=... PKGDIR=... MARKER=... $0" >&2
    exit 1
}

[ -n "$P200JAR" ] || { echo "ERROR: P200JAR not set" >&2; usage; }
[ -n "$PKGDIR" ] || { echo "ERROR: PKGDIR not set" >&2; usage; }
[ -f "$P200JAR" ] || { echo "ERROR: pack200.jar not found at $P200JAR (run 'ant buildPack200' first)" >&2; exit 1; }

case "$MODE" in
    compress) ;;
    repack) ;;
    *) echo "ERROR: MODE must be 'compress' or 'repack', not '$MODE'" >&2; usage ;;
esac

# total_bytes <file...> - sum the sizes of the files that actually exist.
total_bytes() {
    local total=0 f size
    for f in "$@"; do
        [ -f "$f" ] || continue
        size=$(wc -c < "$f")
        total=$((total + size))
    done
    echo "$total"
}

# mb <bytes> - render a byte count as MB with two decimals, sign-aware.
# The raw .pack total can legitimately exceed the raw .jar total (-g leaves the
# stream uncompressed, and resource-heavy jars have little bytecode for pack200
# to work with), so a negative delta must not print as "0.-35".
mb() {
    if [ "$1" -lt 0 ]; then
        printf -- '-%d.%02d' $((-$1 / 1000000)) $(((-$1 % 1000000) / 10000))
    else
        printf '%d.%02d' $(($1 / 1000000)) $((($1 % 1000000) / 10000))
    fi
}

jars="$PKGDIR/lib/*.jar"
wars="$PKGDIR/webapps/*war"

before=$(total_bytes $jars $wars)
count=0
for f in $jars $wars; do
    [ -f "$f" ] && count=$((count + 1))
done
if [ "$count" -eq 0 ]; then
    echo "ERROR: no jars or wars under $PKGDIR/lib and $PKGDIR/webapps" >&2
    exit 1
fi

if [ "$MODE" = compress ]; then
    verb="Compressing"
else
    verb="Repacking"
fi
before_mb=$(mb "$before")
echo "Pack200: $verb $count files (${before_mb}MB)..."
if [ -n "$MARKER" ]; then
    echo "$before" > "$MARKER"
fi

use_parallel=no
if [ "$MODE" = compress ] && [ -z "$SERIAL" ] && command -v parallel > /dev/null 2>&1; then
    use_parallel=yes
fi

if [ "$use_parallel" = yes ]; then
    # -k preserves input order so the log stays readable. Each worker is a
    # separate JVM; a non-zero exit anywhere aborts the target.
    for f in $jars $wars; do
        [ -f "$f" ] && printf '%s\n' "$f"
    done | parallel -k "$SELF --one {}"
else
    for f in $jars $wars; do
        [ -f "$f" ] || continue
        if [ "$MODE" = compress ]; then
            compress_one "$f"
        else
            repack_one "$f"
        fi
    done
fi

if [ "$MODE" = compress ]; then
    after=$(total_bytes "$PKGDIR"/lib/*.pack "$PKGDIR"/webapps/*.pack)
else
    after=$(total_bytes $jars $wars)
fi
saved=$((before - after))
after_mb=$(mb "$after")
saved_mb=$(mb "$saved")
echo "Pack200 done: $before_mb -> ${after_mb}MB (saved ${saved_mb}MB)"