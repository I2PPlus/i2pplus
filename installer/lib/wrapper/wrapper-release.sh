#!/bin/bash
# Ask SourceForge which Wrapper releases exist, so the version date in a download
# URL is looked up rather than remembered.
#
# Usage: wrapper-release.sh [--version X.Y.Z] <what>
#        latest     print the newest release version, newest first
#        newest     print the newest release folder name (Wrapper_X.Y.Z_YYYYMMDD)
#        date       print the YYYYMMDD folder suffix for the release
#        has-src    exit 0 when a source tarball is published for the release
#
# Each release lives in a folder named Wrapper_<version>_<date>, and the date is
# part of every download URL. It used to be hardcoded per version in
# update-wrapper.sh and build-wrapper-win64.sh, so bumping version.txt to a new
# release downloaded the previous release's folder and SourceForge answered 404.
# The files-area RSS feed lists every release folder, so the date is derivable
# at run time and the per-version table is only a fallback for when the feed is
# unreachable.
#
# The binary area (/wrapper) and the source area (/wrapper_src) are published
# separately, so has-src answers whether the win64 cross-compile can run at all
# before a script has downloaded 4 MB of binaries and started a make.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FEED_BASE="https://sourceforge.net/projects/wrapper/rss?path="
FEED_TIMEOUT=30

# Folder dates for releases whose feed entry may outlive the feed window.
# Kept short on purpose: a date found on the feed always wins, and a release
# nobody has downloaded yet is not in this table.
declare -A KNOWN_DATES=(
    [3.7.3]="20260904"
    [3.7.0]="20260716"
    [3.6.5]="20260317"
    [3.6.4]="20251218"
    [3.6.3]="20250910"
)

VERSION=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --version) VERSION="$2"; shift 2 ;;
        *) WHAT="$1"; shift ;;
    esac
done

# The feed is one <item> per file, so a release folder appears many times.
feed() {
    curl -fsSL --max-time "${FEED_TIMEOUT}" "${FEED_BASE}$1" 2>/dev/null |
        grep -o "$2" | sort -u
}

# Newest folder last: the YYYYMMDD prefix sorts chronologically on its own.
bin_folders() { feed "/wrapper" 'Wrapper_[0-9][0-9.]*_[0-9]\{8\}'; }
src_folders() { feed "/wrapper_src" 'Wrapper_[0-9][0-9.]*_[0-9]\{8\}'; }

case "${WHAT}" in
    latest)
        newest=$(bin_folders | tail -1)
        if [ -z "${newest}" ]; then
            echo "wrapper-release.sh: no releases found on the SourceForge feed" >&2
            exit 1
        fi
        echo "${newest#Wrapper_}" | cut -d_ -f1
        ;;
    newest)
        newest=$(bin_folders | tail -1)
        if [ -z "${newest}" ]; then
            echo "wrapper-release.sh: no releases found on the SourceForge feed" >&2
            exit 1
        fi
        echo "${newest}"
        ;;
    date)
        if [ -z "${VERSION}" ]; then
            echo "wrapper-release.sh: date needs --version" >&2
            exit 1
        fi
        folder=$(bin_folders | grep "^Wrapper_${VERSION}_" | tail -1)
        if [ -n "${folder}" ]; then
            echo "${folder##*_}"
        elif [ -n "${KNOWN_DATES[${VERSION}]:-}" ]; then
            echo "${KNOWN_DATES[${VERSION}]}"
        else
            echo "wrapper-release.sh: no release folder for ${VERSION} on SourceForge" >&2
            exit 1
        fi
        ;;
    has-src)
        if [ -z "${VERSION}" ]; then
            echo "wrapper-release.sh: has-src needs --version" >&2
            exit 1
        fi
        if src_folders | grep -q "^Wrapper_${VERSION}_"; then
            exit 0
        fi
        echo "wrapper-release.sh: no source published for ${VERSION} yet" >&2
        exit 1
        ;;
    *)
        echo "Usage: wrapper-release.sh [--version X.Y.Z] {latest|newest|date|has-src}" >&2
        exit 1
        ;;
esac
