#!/bin/bash
# Badge Generator Script
# Generates I2P+ badges using shields.io
#
# Only fetches what would change. The four badges with no version in them are
# generated once and left alone after that; the two that show the release version
# are refetched only when the version they show is not the current one. So a
# release run makes two requests, and a run with current badges makes none.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$(dirname "$SCRIPT_DIR")")"

# Colors
BLUE="0078d4"
GREEN="2ecc71"
ORANGE="e67e22"
PURPLE="8e44ad"
APPIMAGE="739FB9"
ACCENT="b44422"

# Common parameters
STYLE="flat"
HEIGHT="20"
SERVER="https://img.shields.io"

# Get version from CoreVersion.java
VERSION_FILE="${PROJECT_ROOT}/core/java/src/net/i2p/CoreVersion.java"
if [ -f "$VERSION_FILE" ]; then
    VERSION=$(grep 'public static final String VERSION =' "$VERSION_FILE" | sed 's/.*"\(.*\)".*/\1/')
else
    VERSION="unknown"
fi

# file name | shields.io query | aria-label the finished badge carries. The label
# is what tells us whether the file on disk is already the one we would fetch.
BADGES=(
    # Docker badge (docker logo)
    "docker-badge.svg|Docker-Guide-${BLUE}?logo=docker&style=${STYLE}&height=${HEIGHT}|Docker: Guide"
    # I2PSnark Download (github logo, accent color)
    "i2psnark-badge.svg|I2PSnark-Download-${ACCENT}?logo=github&style=${STYLE}&height=${HEIGHT}|I2PSnark: Download"
    # Javadocs Download (github logo, accent color)
    "javadocs-badge.svg|Javadocs-Download-${ACCENT}?logo=github&style=${STYLE}&height=${HEIGHT}|Javadocs: Download"
    # AppImage Guide (appimage logo white, blue)
    "appimage-badge.svg|AppImage-Guide-${BLUE}?logo=appimage&logoColor=white&style=${STYLE}&height=${HEIGHT}|AppImage: Guide"
    # Installer Download (github logo, accent color, current version)
    "installer-badge.svg|Installer-v${VERSION}-${ACCENT}?logo=github&style=${STYLE}&height=${HEIGHT}|Installer: v${VERSION}"
    # Update zip Download (github logo, accent color, current version)
    "update-badge.svg|Update%20zip-v${VERSION}-${ACCENT}?logo=github&style=${STYLE}&height=${HEIGHT}|Update zip: v${VERSION}"
)

# Split into what is already current and what has to be fetched, so the server is
# not contacted at all when nothing needs doing.
pending=()
for badge in "${BADGES[@]}"; do
    IFS='|' read -r name query label <<< "$badge"
    if [ -s "${SCRIPT_DIR}/${name}" ] && grep -q "aria-label=\"${label}\"" "${SCRIPT_DIR}/${name}"; then
        echo "Current: ${name} -> ${label}"
    else
        pending+=("$badge")
    fi
done

if [ ${#pending[@]} -eq 0 ]; then
    echo "Badges in ${SCRIPT_DIR} are all current -> nothing fetched"
    exit 0
fi

# A request that fails returns an empty body, and an empty file is not a badge,
# so reach the server before writing anything. Called from 'ant bumpMajorVersion',
# which reports a failure here but keeps the version bump it already made.
if ! command -v curl >/dev/null 2>&1; then
    echo "Badges not generated -> curl not found" >&2
    exit 1
fi
if ! curl -sf --max-time 15 -o /dev/null "${SERVER}/badge/probe-passed-green.svg"; then
    echo "Badges not generated -> cannot reach ${SERVER}" >&2
    exit 1
fi

echo "Generating badges for I2P+ v${VERSION}..."

# Fetched into a temp file, then moved into place, so one bad response part way
# through leaves the previous badge intact.
rc=0
for badge in "${pending[@]}"; do
    IFS='|' read -r name query label <<< "$badge"
    tmp="$(mktemp)"
    if curl -sfL --max-time 30 "${SERVER}/badge/${query}" > "$tmp" && grep -q '<svg' "$tmp"; then
        mv "$tmp" "${SCRIPT_DIR}/${name}"
        echo "Generated: ${name} -> ${label}"
    else
        echo "Badge not updated -> no SVG from ${SERVER} for ${name}" >&2
        rm -f "$tmp"
        rc=1
    fi
done

if [ "$rc" -ne 0 ]; then
    echo "Badges incomplete, see the errors above" >&2
    exit 1
fi
echo "Done! Badges saved to ${SCRIPT_DIR}"
