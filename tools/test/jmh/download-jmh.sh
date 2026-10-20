#!/bin/bash
#
# Download or update the JMH libraries used by the bench targets (ant bench).
# Caches the four jars next to this script under the exact names that
# core/java/build.xml's jmhLibCheck expects, and records the version in
# version.txt, so repeat runs are a no-op and the jars stay gitignored.
#
# Usage: download-jmh.sh [--force] [--version X.Y.Z]
#
#   JMH_VERSION   same as --version; pins the JMH release instead of the
#                 built-in one
#
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VERSION_FILE="${SCRIPT_DIR}/version.txt"
MAVEN_BASE="https://repo1.maven.org/maven2"
FORCE=false
PIN=""

while [ $# -gt 0 ]; do
    case "$1" in
        --force) FORCE=true; shift ;;
        --version) PIN="$2"; shift 2 ;;
        --version=*) PIN="${1#*=}"; shift ;;
        -h|--help)
            echo "Usage: download-jmh.sh [--force] [--version X.Y.Z]"
            exit 0
            ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done
[ -n "${JMH_VERSION:-}" ] && [ -z "$PIN" ] && PIN="$JMH_VERSION"

# JMH 1.37 is the latest release and still runs on the Java 8 bench build.
# jopt-simple and commons-math3 are jmh-core's own POM dependencies, pinned
# to the versions that resolve against it.
JMH_PIN="1.37"
JOPT_VERSION="5.0.4"
MATH3_VERSION="3.6.1"

# fetch name:mavenPath:version; "JMH" means "use the selected JMH release"
ARTIFACTS=(
    "jmh-core.jar:org/openjdk/jmh/jmh-core:JMH"
    "jmh-generator-annprocess.jar:org/openjdk/jmh/jmh-generator-annprocess:JMH"
    "jopt-simple.jar:net/sf/jopt-simple/jopt-simple:${JOPT_VERSION}"
    "commons-math3.jar:org/apache/commons/commons-math3:${MATH3_VERSION}"
)

# Maven Central intermittently answers with a 403 "open proxy" page instead of
# the artifact for clients it misclassifies, so every request retries and then
# verifies the payload is really a jar before it is cached.
fetch() {
    local url="$1" out="$2" attempt
    for attempt in 1 2 3 4 5; do
        # Bypass any SOCKS wrapper for the plain-HTTPS Maven Central fetches,
        # the same way tools/spotbugs/download-spotbugs.sh does.
        if LD_PRELOAD= curl -sSL --fail --max-time 300 -o "$out" "$url" \
           && [ "$(head -c 2 "$out")" = "PK" ]; then
            return 0
        fi
        echo "  attempt ${attempt} failed, retrying..." >&2
        sleep $((attempt * 3))
    done
    return 1
}

TARGET="${PIN:-$JMH_PIN}"
INSTALLED="$(cat "$VERSION_FILE" 2>/dev/null || echo none)"

up_to_date() {
    [ "$INSTALLED" = "$TARGET" ] || return 1
    local entry name path ver
    for entry in "${ARTIFACTS[@]}"; do
        IFS=: read -r name path ver <<<"$entry"
        [ -f "${SCRIPT_DIR}/${name}" ] || return 1
    done
    return 0
}

if [ "$FORCE" = false ] && up_to_date; then
    echo "JMH $INSTALLED libraries are up to date."
    exit 0
fi

echo "Fetching JMH $TARGET libraries into ${SCRIPT_DIR}..."
for entry in "${ARTIFACTS[@]}"; do
    IFS=: read -r name path ver <<<"$entry"
    [ "$ver" = "JMH" ] && ver="$TARGET"
    artifact="$(basename "$path")-${ver}.jar"
    if ! fetch "${MAVEN_BASE}/${path}/${ver}/${artifact}" "${SCRIPT_DIR}/${name}"; then
        echo "FAILED to download ${path}/${ver}/${artifact}" >&2
        rm -f "${SCRIPT_DIR}/${name}"
        exit 1
    fi
    echo "  ${name} (${ver})"
done
echo "$TARGET" > "$VERSION_FILE"
echo "JMH libraries cached in ${SCRIPT_DIR} (gitignored)."
