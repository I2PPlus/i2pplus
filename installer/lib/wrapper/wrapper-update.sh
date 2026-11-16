#!/bin/bash
# Update the Tanuki Wrapper end to end: binaries from the delta pack, then the
# win64 cross-compile, then the bookkeeping files.
#
# Usage: wrapper-update.sh [--version X.Y.Z] [--pinned] [--no-commit]
#        no arguments  take the newest release on SourceForge
#        --version     take that release instead
#        --pinned      take what version.txt names, the old behaviour
#        --no-commit   build and install, but leave the result unstaged
#
# version.txt and README.md follow the build, not the other way round: the run
# discovers which release SourceForge actually publishes, builds it, and only
# then names it. When either stage fails, both files are put back at the release
# the tree shipped, so a failed pass cannot leave version.txt pointing at a
# wrapper the tree does not hold.
#
# A pass that lands a new release commits it, the way the tomcat update script
# does: the binaries, version.txt and the README move together, and a half
# committed set (new jar, old win64 pair) is exactly the drift the checks above
# exist to prevent.
#
# The two stages used to be separate ant <exec> calls with failonerror, which is
# how a 404 on the delta pack left version.txt naming 3.7.4 while all/wrapper.jar
# was still 3.7.3.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_DIR="${SCRIPT_DIR}"
RELEASES="${WRAPPER_DIR}/wrapper-release.sh"

pinned_version() {
    if [ -f "${WRAPPER_DIR}/version.txt" ]; then
        grep "^WRAPPER_VERSION=" "${WRAPPER_DIR}/version.txt" | cut -d= -f2
    fi
}

# The release the tree actually holds, read off the shipped jar. version.txt can
# be ahead of it when an earlier pass failed part way through, and rolling back
# to the file rather than the binaries would leave the two disagreeing.
installed_version() {
    if [ -f "${WRAPPER_DIR}/all/wrapper.jar" ]; then
        unzip -p "${WRAPPER_DIR}/all/wrapper.jar" META-INF/MANIFEST.MF 2>/dev/null |
            grep "Implementation-Version" | cut -d' ' -f2 | tr -d '\r'
    fi
}

PINNED=$(pinned_version)
INSTALLED=$(installed_version)
MODE=latest
VERSION=""
COMMIT=1
while [[ $# -gt 0 ]]; do
    case "$1" in
        --version) VERSION="$2"; MODE=explicit; shift 2 ;;
        --pinned) MODE=pinned; shift ;;
        --no-commit) COMMIT=0; shift ;;
        *) shift ;;
    esac
done

if [ "${MODE}" = latest ]; then
    TARGET=$("${RELEASES}" latest) || {
        echo "wrapper-update.sh: cannot reach the SourceForge feed" >&2
        exit 1
    }
elif [ "${MODE}" = pinned ]; then
    TARGET="${PINNED}"
    if [ -z "${TARGET}" ]; then
        echo "wrapper-update.sh: version.txt names no release and --pinned was asked for" >&2
        exit 1
    fi
else
    TARGET="${VERSION}"
fi

# Roll the bookkeeping files back to the release the binaries came from. When
# the tree already names the target there is nothing to undo, so whatever the
# stages do to those files is already correct.
ROLLBACK="${INSTALLED:-${PINNED}}"
if [ "${ROLLBACK}" = "${TARGET}" ]; then
    ROLLBACK=""
fi

restore_bookkeeping() {
    if [ -n "${ROLLBACK}" ]; then
        "${WRAPPER_DIR}/sync-wrapper-version.sh" --version "${ROLLBACK}" >/dev/null 2>&1 ||
            echo "wrapper-update.sh: version.txt and README.md need a hand - target was ${TARGET}, rolling back to ${ROLLBACK}" >&2
        echo "wrapper-update.sh: build failed, version.txt and README.md rolled back to ${ROLLBACK}" >&2
    fi
}

# The artifacts an update produces: the binaries the delta pack installs, the
# win64 pair this script cross-compiles, and the two bookkeeping files naming
# the release. Scripts are deliberately absent - they are source, edited by
# hand, and they commit on their own.
#
# Scoping is by path pattern, not by what this pass happened to dirty: a stage
# that failed part-way (the delta pack in, the win64 pair not) leaves the
# binaries already modified when the update is retried, and the retry has to
# commit the whole release, not the half it redid.
ARTIFACT_RE='^installer/lib/wrapper/(all/wrapper\.jar|(linux64|linux64-armv8|freebsd64|freebsd-arm64|macosx|macosx-arm64|win64)/[^/]+$|version\.txt$|README\.md$)'

commit_release() {
    local gitroot
    gitroot=$(git -C "${WRAPPER_DIR}" rev-parse --show-toplevel 2>/dev/null) || {
        echo "wrapper-update.sh: not a git checkout, leaving ${TARGET} uncommitted" >&2
        return 0
    }
    local jar_ver
    jar_ver=$(unzip -p "${WRAPPER_DIR}/all/wrapper.jar" META-INF/MANIFEST.MF 2>/dev/null |
        grep "Implementation-Version" | cut -d' ' -f2 | tr -d '\r')
    if [ "${jar_ver}" != "${TARGET}" ]; then
        echo "wrapper-update.sh: all/wrapper.jar reports ${jar_ver:-nothing}, not ${TARGET} - not committing" >&2
        return 1
    fi

    local paths staged=""
    # -u --no-renames: deletions (the platforms a new delta pack drops) and
    # added binaries both have to show, and a rename would hide the old path.
    paths=$(git -C "${gitroot}" status --porcelain -u --no-renames -- "${WRAPPER_REL}" |
        sed -e 's/^...//' -e 's/ -> .*$//' |
        grep -E "${ARTIFACT_RE}" || true)
    if [ -z "${paths}" ]; then
        echo "wrapper-update.sh: no release artifacts changed against HEAD, nothing to commit"
        return 0
    fi
    # shellcheck disable=SC2086
    git -C "${gitroot}" add -- ${paths} || {
        echo "wrapper-update.sh: staging failed, leaving the update uncommitted" >&2
        return 1
    }
    git -C "${gitroot}" commit -q -m "installer: update wrapper to ${TARGET}" -- ${paths} || {
        echo "wrapper-update.sh: the commit failed, the files stay staged" >&2
        return 1
    }
    echo "Committed $(git -C "${gitroot}" rev-parse --short HEAD): installer: update wrapper to ${TARGET}"
    echo "  $(printf '%s\n' ${paths} | grep -c .) files"
}

echo "=== Wrapper update: target ${TARGET} (version.txt pins ${PINNED:-nothing}, installed jar is ${INSTALLED:-nothing}) ==="

REPO_ROOT=$(git -C "${WRAPPER_DIR}" rev-parse --show-toplevel 2>/dev/null)
if [ -n "${REPO_ROOT}" ]; then
    WRAPPER_REL=$(git -C "${WRAPPER_DIR}" rev-parse --show-prefix 2>/dev/null | sed 's:/*$::')
else
    WRAPPER_REL="installer/lib/wrapper"
fi

if ! "${WRAPPER_DIR}/update-wrapper.sh" --version "${TARGET}"; then
    echo "wrapper-update.sh: the delta pack stage failed" >&2
    restore_bookkeeping
    exit 1
fi

if ! "${WRAPPER_DIR}/build-wrapper-win64.sh" --version "${TARGET}"; then
    echo "wrapper-update.sh: the win64 build failed" >&2
    restore_bookkeeping
    exit 1
fi

if [ "${COMMIT}" -eq 1 ]; then
    if ! commit_release; then
        echo "wrapper-update.sh: the release was installed but not committed" >&2
        exit 1
    fi
else
    echo "wrapper-update.sh: --no-commit, leaving ${TARGET} for a hand commit"
fi

echo ""
echo "=== Wrapper ${TARGET} updated ==="
