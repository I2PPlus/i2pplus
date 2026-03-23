#!/bin/bash
#
# Download Checkstyle for I2P+ analysis.
#
# Usage: download-checkstyle.sh [--force]
#
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VERSION_FILE="${SCRIPT_DIR}/version.txt"
VERSION="13.3.0"
FORCE=false

[ "$1" = "--force" ] && FORCE=true

get_installed_version() {
    if [ -f "$VERSION_FILE" ]; then
        cat "$VERSION_FILE"
    else
        echo "none"
    fi
}

INSTALLED=$(get_installed_version)

if [ "$FORCE" = false ] && [ "$INSTALLED" = "$VERSION" ]; then
    echo "Checkstyle $INSTALLED is up to date."
    exit 0
fi

JAR_FILE="${SCRIPT_DIR}/checkstyle-all.jar"
DOWNLOAD_URL="https://github.com/checkstyle/checkstyle/releases/download/checkstyle-${VERSION}/checkstyle-${VERSION}-all.jar"

echo "Downloading Checkstyle ${VERSION}..."
curl -L -o "$JAR_FILE" "$DOWNLOAD_URL"

echo -n "$VERSION" > "$VERSION_FILE"
echo "Checkstyle ${VERSION} installed to ${SCRIPT_DIR}"
echo "Binary: ${JAR_FILE}"
