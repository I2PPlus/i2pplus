#!/bin/bash
#
# Download or update appimagetool for the I2P+ AppImage build.
# Checks the installed version against the latest release and downloads if the
# installed copy is older. The tool is not in the repository (tools/ is
# gitignored), so a fresh checkout has nothing to run until this has run.
#
# Usage: download-appimagetool.sh [version|never]
#   version  pin that release tag (e.g. 1.9.1)
#   never    keep what is installed; fetch only when nothing is there
#   (empty)  use the latest release, updating an older installed copy
#
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VERSION_FILE="${SCRIPT_DIR}/version.txt"
BINARY="${SCRIPT_DIR}/appimagetool"
DOWNLOAD_URL="https://github.com/AppImage/appimagetool/releases"
API_URL="https://api.github.com/repos/AppImage/appimagetool/releases?per_page=100"
REQUEST="${1:-}"

# The floor. 1.9.0 is the first release of the rewritten appimagetool: the oldest
# tag this script can fetch from this repository (the v13-era builds live in
# AppImageKit and are not tagged here), the first that ships a static binary and
# fetches the type 2 runtime itself, and the line where the
# "SOURCE [DESTINATION]" form build-appimage.sh calls it with settled. Anything
# older is not a tool we know how to drive.
MIN_VERSION="1.9.0"

# The newest release, as its tag. The rolling "continuous" build is skipped: it
# is not marked as a pre-release, so the API's own "latest" resolves to it, and
# it carries no version number to compare against. Only numeric tags are
# releases, and the highest of those is the newest.
get_latest_version() {
    curl -sL --max-time 30 "$API_URL" |
        grep -o '"tag_name": *"[^"]*"' | sed 's/.*"\([^"]*\)"$/\1/' |
        grep -E '^[0-9]+(\.[0-9]+)*$' | sort -V | tail -1
}

# The version we installed, as recorded in version.txt at install time. The
# tool's own --version output cannot be used for this: every appimagetool
# release asset is built by the rolling continuous pipeline and prints
# "continuous build (git version ...)", with no release number in it.
get_installed_version() {
    if [ -f "$VERSION_FILE" ]; then
        cat "$VERSION_FILE"
    else
        echo "none"
    fi
}

# True when $1 is at least $2, compared as version numbers rather than strings.
at_least() {
    [ "$(printf '%s\n%s\n' "$2" "$1" | sort -V | head -1)" = "$2" ]
}

install_version() {
    local version="$1"
    local tmp
    tmp=$(mktemp)
    local url="${DOWNLOAD_URL}/download/${version}/appimagetool-x86_64.AppImage"

    echo "Downloading appimagetool ${version} (~15MB)..."
    # -f: a tag that does not exist answers 404 with an HTML body, and without it
    # curl reports success and we would install that page as the binary.
    if ! curl -fsSL --max-time 300 -o "$tmp" "$url"; then
        echo "ERROR: Could not download appimagetool ${version}."
        rm -f "$tmp"
        exit 1
    fi
    chmod 755 "$tmp"
    mv "$tmp" "$BINARY"
    echo -n "$version" > "$VERSION_FILE"
    echo "appimagetool ${version} installed to ${SCRIPT_DIR}"
    echo "Binary: ${BINARY}"
}

echo "Checking appimagetool version..."

INSTALLED=$(get_installed_version)

if [ "$REQUEST" = "never" ]; then
    if [ -x "$BINARY" ]; then
        echo "appimagetool kept (update disabled)."
        exit 0
    fi
    LATEST=$(get_latest_version)
    if [ -z "$LATEST" ]; then
        echo "ERROR: Could not determine latest appimagetool version and nothing is installed."
        exit 1
    fi
    install_version "$LATEST"
    exit 0
fi

if [ -n "$REQUEST" ]; then
    LATEST="$REQUEST"
else
    LATEST=$(get_latest_version)
fi

if [ -z "$LATEST" ]; then
    if [ -x "$BINARY" ]; then
        echo "WARNING: Could not determine latest version. Using installed: $INSTALLED"
        exit 0
    fi
    echo "ERROR: Could not determine latest appimagetool version."
    exit 1
fi

if ! at_least "$LATEST" "$MIN_VERSION"; then
    echo "ERROR: appimagetool $LATEST is older than the minimum $MIN_VERSION."
    exit 1
fi

# Up to date means exactly the pinned tag when one is asked for, and the newest
# release or newer for the default.
up_to_date=false
if [ -x "$BINARY" ] && [ "$INSTALLED" != "none" ]; then
    if [ -n "$REQUEST" ]; then
        if [ "$INSTALLED" = "$LATEST" ]; then
            up_to_date=true
        fi
    elif at_least "$INSTALLED" "$LATEST"; then
        up_to_date=true
    fi
fi

if $up_to_date; then
    echo "appimagetool $INSTALLED is up to date."
    exit 0
fi

if [ ! -x "$BINARY" ]; then
    echo "appimagetool not found -> installing $LATEST"
elif [ "$INSTALLED" = "none" ]; then
    # The binary is there but nothing recorded what it is, so it cannot be
    # vouched for; the newest release replaces it.
    echo "Installed copy has no recorded version -> replacing with $LATEST"
elif [ -n "$REQUEST" ]; then
    echo "Pinned version requested: $INSTALLED -> $LATEST"
else
    echo "Update available: $INSTALLED -> $LATEST"
fi

install_version "$LATEST"
