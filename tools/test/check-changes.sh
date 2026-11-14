#!/bin/bash
# check-changes.sh [-m module] <file...> — run every validation gate for a set of changed files.
#
# One pass over the gates this tree enforces in review: doclint at -package, the javadoc
# formatting and description checkers, the log-message checker, whitespace and line-length
# against HEAD, a clean-room compile of the sources touched, and the module's JUnit suite.
# It prints measurements, not a verdict — read the numbers.
#
# Usage: tools/test/check-changes.sh apps/i2psnark/java/src/org/klomp/snark/Message.java \
#                            apps/i2psnark/java/test/junit/org/klomp/snark/MessageWireTest.java
#
# -m module   which module's test suite to run (default: i2psnark). The javadoc, log,
#             whitespace and compile gates always cover exactly the files given.
#
# Scratch output goes under $TMPDIR_BASE (default /tmp/opencode), never the repo tree.
# Generalize the compile classpath per module as needed; the snark one is what is wired up.

set -u

TMPDIR_BASE="${TMPDIR_BASE:-/tmp/opencode}"
MODULE="i2psnark"

while [ "${1:-}" = "-m" ]; do
    MODULE="$2"
    shift 2
done

if [ $# -eq 0 ]; then
    sed -n '2,16p' "$0"
    exit 1
fi

cd "$(git rev-parse --show-toplevel)" || exit 1

FILES=("$@")
SRC=()
for f in "${FILES[@]}"; do
    case "$f" in */src/*) SRC+=("$f") ;; esac
done

# module-specific wiring
case "$MODULE" in
    i2psnark)
        BUILD="apps/i2psnark/java/build.xml"
        REPORTS="/tmp/build-i2p/reports/i2psnark/junit"
        SOURCEPATH="apps/i2psnark/java/src:core/java/src:router/java/src:apps/streaming/java/src:apps/ministreaming/java/src:apps/jetty/java/src"
        CP="$(find apps/jetty -name '*.jar' | tr '\n' ':')"
        LOGSCOPE="apps/i2psnark/java/src"
        ;;
    core)
        BUILD="core/java/build.xml"
        REPORTS="/tmp/build-i2p/reports/core/junit"
        SOURCEPATH="core/java/src:core/java/test/junit:router/java/src:apps/streaming/java/src"
        CP="$(find apps/jetty -name '*.jar' | tr '\n' ':')"
        LOGSCOPE="core/java/src"
        ;;
    *)
        echo "check-changes.sh: no wiring for module '$MODULE' — add it above" >&2
        exit 1
        ;;
esac

echo "=== javadoc (doclint -package)"
bash .opencode/skills/javadoc-audit/jdcheck.sh "${FILES[@]}" 2>&1 | tail -4

echo "=== pad / boilerplate / fmt"
python3 tools/javadoc/scripts/javadoc-pad.py "${FILES[@]}" 2>&1 | tail -1
python3 tools/javadoc/scripts/javadoc-boilerplate.py "${FILES[@]}" 2>&1 | tail -2
python3 tools/javadoc/scripts/javadoc-fmt.py "${FILES[@]}" 2>&1 | tail -2

echo "=== logcheck"
python3 tools/scripts/logging/logcheck.py all "$LOGSCOPE" 2>&1 | tail -1

echo "=== whitespace / max line / final newline"
for f in "${FILES[@]}"; do
    printf '%s max=' "$(basename "$f")"
    awk '{if (length > m) m = length} END {print m+0}' "$f"
    printf '%s HEAD max=' "$(basename "$f")"
    git show "HEAD:$f" 2>/dev/null | awk '{if (length > m) m = length} END {print m+0}'
    rg -q ' +$|\t' "$f" && echo "  WS/TAB FINDINGS in $f"
    [ -n "$(tail -c1 "$f")" ] && echo "  NO FINAL NEWLINE in $f"
done

echo "=== javac (clean room)"
if [ ${#SRC[@]} -gt 0 ]; then
    OUT="$TMPDIR_BASE/gates-out"
    rm -rf "$OUT"
    javac -nowarn -d "$OUT" -sourcepath "$SOURCEPATH" -cp "$CP" "${SRC[@]}" 2>&1 |
        grep -v -i deprecat
    echo "javac done"
fi

echo "=== $MODULE suite"
ant -q -f "$BUILD" junit.test 2>&1 | rg -n 'error:|BUILD' | head -12
REPORTS="$REPORTS" python3 - <<'EOF'
import glob, os, xml.etree.ElementTree as ET
t = f = e = 0
bad = []
for p in glob.glob(os.path.join(os.environ['REPORTS'], 'TEST-*.xml')):
    r = ET.parse(p).getroot()
    t += int(r.get('tests', 0))
    f += int(r.get('failures', 0))
    e += int(r.get('errors', 0))
    if int(r.get('failures', 0)) + int(r.get('errors', 0)):
        bad.append(r.get('name'))
print('tests', t, 'failures', f, 'errors', e, bad)
EOF
