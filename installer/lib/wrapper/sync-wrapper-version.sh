#!/bin/bash
# Keep version.txt and README.md in step with the wrapper version in use.
#
# Usage: sync-wrapper-version.sh [--version X.X.X] [--check]
#        Without --version the version comes from all/wrapper.jar's manifest,
#        falling back to version.txt when no jar is present.
#
# --check reports the drift and changes nothing: exit 0 when both files already
# name the version, exit 1 when either one is stale.
#
# update-wrapper.sh and build-wrapper-win64.sh call this after installing
# binaries, and also when they find nothing to do, so a version bump cannot
# leave version.txt or the README pointing at an older release.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VERSION_FILE="${SCRIPT_DIR}/version.txt"
README="${SCRIPT_DIR}/README.md"
JAR="${SCRIPT_DIR}/all/wrapper.jar"

VERSION=""
if [ -f "${VERSION_FILE}" ]; then
    VERSION=$(grep "^WRAPPER_VERSION=" "${VERSION_FILE}" | cut -d= -f2)
fi

# The jar the tree ships is the ground truth: its manifest carries the exact
# build. Without --version, version.txt and README.md are checked against it,
# so both files being stale together is still reported.
EXPLICIT=0
JAR_VER=""
if [ -f "${JAR}" ]; then
    JAR_VER=$(unzip -p "${JAR}" META-INF/MANIFEST.MF 2>/dev/null | grep "Implementation-Version" | cut -d' ' -f2 | tr -d '\r')
fi

CHECK=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --version) VERSION="$2"; EXPLICIT=1; shift 2 ;;
        --check) CHECK=1; shift ;;
        *) shift ;;
    esac
done

if [ "${EXPLICIT}" -eq 0 ] && [ -n "${JAR_VER}" ]; then
    VERSION="${JAR_VER}"
fi

if [ -z "${VERSION}" ]; then
    echo "sync-wrapper-version.sh: no version given and version.txt is empty" >&2
    exit 1
fi

if [ ! -f "${README}" ]; then
    echo "sync-wrapper-version.sh: no README.md beside version.txt" >&2
    exit 1
fi

# The marker the README carries: "(currently **X.Y.Z**)".
MARKER='currently \*\*[0-9][0-9.]*\*\*'
if ! grep -q "${MARKER}" "${README}"; then
    echo "sync-wrapper-version.sh: README.md has no \"currently **X.Y.Z**\" marker to update" >&2
    exit 1
fi

DRIFT=0

FILED_VER=""
if [ -f "${VERSION_FILE}" ]; then
    FILED_VER=$(grep "^WRAPPER_VERSION=" "${VERSION_FILE}" | cut -d= -f2)
fi
if [ "${FILED_VER}" != "${VERSION}" ]; then
    DRIFT=1
    if [ "${CHECK}" -eq 1 ]; then
        echo "version.txt is stale: ${FILED_VER:-<missing>}, should be ${VERSION}"
    else
        printf 'WRAPPER_VERSION=%s\n' "${VERSION}" > "${VERSION_FILE}"
        echo "version.txt set to ${VERSION}"
    fi
fi

if ! grep -q "currently \*\*${VERSION}\*\*" "${README}"; then
    DRIFT=1
    if [ "${CHECK}" -eq 1 ]; then
        echo "README.md is stale: $(grep -o 'currently \*\*[0-9][0-9.]*\*\*' "${README}"), should be currently **${VERSION}**"
    else
        sed -i "s/${MARKER}/currently **${VERSION}**/" "${README}"
        echo "README.md version reference set to ${VERSION}"
    fi
fi

if [ "${DRIFT}" -eq 0 ]; then
    echo "version.txt and README.md both name wrapper ${VERSION}"
    exit 0
fi
# --check reports drift as a failure; a normal run has just fixed it, so the
# files are correct now and the caller has nothing to warn about.
if [ "${CHECK}" -eq 1 ]; then
    exit 1
fi
exit 0
