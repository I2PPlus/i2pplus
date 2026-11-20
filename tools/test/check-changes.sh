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
# --test-roots  print the directories the suite-recompile touch pass walks, each marked
#             ok or MISSING, and stop. A module wired without its test root shows up
#             as MISSING there, or prints nothing at all.
#
# Scratch output goes under $TMPDIR_BASE (default /tmp/opencode), never the repo tree.
# Generalize the compile classpath per module as needed; the snark one is what is wired up.

set -u

TMPDIR_BASE="${TMPDIR_BASE:-/tmp/opencode}"
MODULE="i2psnark"
# extra -D properties a module's ant build needs (jetty's version pins);
# empty for every module that takes none
ANTPROPS=""
# test roots the module keeps outside SOURCEPATH, colon-separated; empty when
# SOURCEPATH already carries them. Only the suite-recompile touch pass needs
# them - the compile gate deliberately sees main sources only.
TESTPATH=""
# set by --test-roots: print the touch pass's directories and stop
TESTROOTS=""

while [ $# -gt 0 ]; do
    case "$1" in
        -m)
            MODULE="$2"
            shift 2
            ;;
        --test-roots)
            TESTROOTS=1
            shift
            ;;
        *) break ;;
    esac
done

if [ $# -eq 0 ] && [ -z "$TESTROOTS" ]; then
    sed -n '2,18p' "$0"
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
        TESTPATH="apps/i2psnark/java/test/junit"
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
    router)
        BUILD="router/java/build.xml"
        REPORTS="/tmp/build-i2p/reports/router/junit"
        SOURCEPATH="router/java/src:router/java/test/junit:core/java/src:apps/streaming/java/src:apps/ministreaming/java/src:apps/i2ptunnel/java/src"
        CP="$(find tools/test -name '*.jar' | tr '\n' ':')"
        LOGSCOPE="router/java/src"
        ;;
    streaming)
        BUILD="apps/streaming/java/build.xml"
        REPORTS="/tmp/build-i2p/reports/streaming/junit"
        SOURCEPATH="apps/streaming/java/src:apps/streaming/java/test/junit:core/java/src:router/java/src:apps/ministreaming/java/src"
        CP="$(find tools/test -name '*.jar' | tr '\n' ':')"
        LOGSCOPE="apps/streaming/java/src"
        ;;
    pack200)
        BUILD="apps/pack200/java/build.xml"
        REPORTS="/tmp/build-i2p/reports/pack200/junit"
        SOURCEPATH="apps/pack200/java/src:apps/pack200/java/test/junit"
        CP="$(find tools/test -name '*.jar' | tr '\n' ':')"
        LOGSCOPE="apps/pack200/java/src"
        ;;
    sam)
        BUILD="apps/sam/java/build.xml"
        REPORTS="/tmp/build-i2p/reports/sam/junit"
        SOURCEPATH="apps/sam/java/src:apps/sam/java/test:core/java/src:router/java/src:apps/streaming/java/src:apps/ministreaming/java/src"
        CP="$(find tools/test -name '*.jar' | tr '\n' ':')"
        LOGSCOPE="apps/sam/java/src"
        ;;
    routerconsole)
        BUILD="apps/routerconsole/java/build.xml"
        REPORTS="/tmp/build-i2p/reports/routerconsole/junit"
        SOURCEPATH="apps/routerconsole/java/src:apps/routerconsole/java/test/junit:core/java/src:router/java/src:apps/streaming/java/src:apps/ministreaming/java/src:apps/jetty/java/src:apps/jrobin/java/src:apps/i2ptunnel/java/src:apps/desktopgui/src"
        CP="$(find apps/jetty installer/lib/wrapper -name '*.jar' | tr '\n' ':')$(find /tmp/build-i2p/pkg-temp/lib -name '*.jar' ! -name 'routerconsole.jar' 2>/dev/null | tr '\n' ':')"
        LOGSCOPE="apps/routerconsole/java/src"
        ;;
    susidns)
        BUILD="apps/susidns/src/build.xml"
        REPORTS="/tmp/build-i2p/reports/susidns/junit"
        SOURCEPATH="apps/susidns/src/java/src:apps/susidns/src/java/test/junit:core/java/src:apps/jetty/java/src:apps/addressbook/java/src"
        CP="$(find tools/test apps/jetty apps/susidns/src/lib installer/lib/wrapper -name '*.jar' | tr '\n' ':')$(find /tmp/build-i2p/pkg-temp/lib -name '*.jar' 2>/dev/null | tr '\n' ':')"
        LOGSCOPE="apps/susidns/src/java/src"
        ;;
    susimail)
        BUILD="apps/susimail/build.xml"
        REPORTS="/tmp/build-i2p/reports/susimail/junit"
        SOURCEPATH="apps/susimail/src/src:apps/susimail/src/test/junit:core/java/src:router/java/src:apps/streaming/java/src:apps/ministreaming/java/src:apps/jetty/java/src"
        CP="$(find tools/test apps/jetty/jettylib -name '*.jar' | tr '\n' ':')"
        LOGSCOPE="apps/susimail/src/src"
        # WebMail imports net.i2p.servlet, which lives in jetty-i2p.jar. The module's
        # own build looks for that jar in apps/jetty/jettylib/, and a standalone run
        # of this module never puts it there - point it at the jar the jetty module
        # builds (needs `ant -q buildJetty` once).
        ANTPROPS="-Djetty.pkg.dir=/tmp/build-i2p/apps/jetty/build"
        ;;
    i2ptunnel)
        BUILD="apps/i2ptunnel/java/build.xml"
        # this module's suite target is plain `test`, not `junit.test`
        TARGET="test"
        REPORTS="/tmp/build-i2p/reports/i2ptunnel/junit"
        SOURCEPATH="apps/i2ptunnel/java/src:apps/i2ptunnel/java/test/junit:core/java/src:router/java/src:apps/streaming/java/src:apps/ministreaming/java/src:apps/jetty/java/src"
        CP="$(find tools/test apps/jetty/jettylib -name '*.jar' | tr '\n' ':')"
        LOGSCOPE="apps/i2ptunnel/java/src"
        ;;
    i2pcontrol)
        BUILD="apps/i2pcontrol/build.xml"
        REPORTS="/tmp/build-i2p/reports/i2pcontrol/junit"
        # the jsonrpc2 and jbcrypt sources are vendored under this module's java dir
        SOURCEPATH="apps/i2pcontrol/java:core/java/src:router/java/src:apps/streaming/java/src:apps/ministreaming/java/src"
        TESTPATH="apps/i2pcontrol/java/test/junit"
        CP="$(find tools/test apps/jetty/jettylib installer/lib/wrapper -name '*.jar' | tr '\n' ':')"
        LOGSCOPE="apps/i2pcontrol/java/net"
        ;;
    addressbook)
        BUILD="apps/addressbook/build.xml"
        REPORTS="/tmp/build-i2p/reports/addressbook/junit"
        SOURCEPATH="apps/addressbook/java/src:apps/addressbook/java/test/junit:core/java/src:router/java/src:apps/streaming/java/src:apps/ministreaming/java/src:apps/i2ptunnel/java/src"
        CP="$(find tools/test -name '*.jar' | tr '\n' ':')"
        # the module also vendors the metanotion block store, whose log findings
        # are upstream's and out of this repo's scope
        LOGSCOPE="apps/addressbook/java/src/net/i2p/addressbook"
        ;;
    jetty)
        BUILD="apps/jetty/build.xml"
        REPORTS="/tmp/build-i2p/reports/jetty/junit"
        SOURCEPATH="apps/jetty/java/src:apps/jetty/java/test/junit:core/java/src"
        CP="$(find tools/test apps/jetty/jettylib -name '*.jar' | tr '\n' ':')"
        LOGSCOPE="apps/jetty/java/src"
        # this build refuses to run without the version pins the top-level
        # build.xml normally supplies, and jettylib/ is generated from them
        ANTPROPS="-Djetty.ver=9.3.30.v20211001 -Dtomcat.ver=9.0.124"
        ;;
    *)
        echo "check-changes.sh: no wiring for module '$MODULE' — add it above" >&2
        exit 1
        ;;
esac

# The directories whose .java files must be recompiled when a main source
# changes: the test roots carried in SOURCEPATH, plus TESTPATH. One per line.
test_roots() {
    local d
    IFS=':' read -ra _sp <<< "$SOURCEPATH"
    for d in "${_sp[@]}"; do
        case "$d" in *test*) printf '%s\n' "$d" ;; esac
    done
    IFS=':' read -ra _tp <<< "$TESTPATH"
    for d in "${_tp[@]}"; do
        [ -n "$d" ] && printf '%s\n' "$d"
    done
}

if [ -n "$TESTROOTS" ]; then
    while IFS= read -r d; do
        printf '%-8s %s\n' "$([ -d "$d" ] && echo ok || echo MISSING)" "$d"
    done < <(test_roots)
    exit 0
fi

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
# A changed `static final` constant is inlined into every class that reads it, and ant's
# staleness check only compares a .java against its own .class — so a test class compiled
# against the old value keeps the old number and fails for a reason that is not in the
# test. Touch the module's test sources whenever a main source was touched, so the suite
# compiles against the constants it asserts on. Test roots outside SOURCEPATH go in
# TESTPATH — i2psnark and i2pcontrol keep them there, so walking SOURCEPATH alone leaves
# those two modules' suites stale.
if [ ${#SRC[@]} -gt 0 ]; then
    while IFS= read -r d; do
        [ -d "$d" ] && find "$d" -name '*.java' -exec touch {} +
    done < <(test_roots)
fi
ant -q -f "$BUILD" $ANTPROPS "${TARGET:-junit.test}" 2>&1 | rg -n 'error:|BUILD' | head -12
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
line = 'tests: %d * failures: %d * errors: %d' % (t, f, e)
if bad:
    line += ' * Failing: ' + ', '.join(bad)
print(line)
EOF
