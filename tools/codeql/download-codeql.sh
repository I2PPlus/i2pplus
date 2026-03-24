#!/bin/bash
#
# Download or update CodeQL CLI for I2P+ analysis.
# Checks current version against latest release and downloads if needed.
# CodeQL is ~509MB so only downloads when explicitly requested.
#
# Usage: download-codeql.sh [--force]
#
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VERSION_FILE="${SCRIPT_DIR}/version.txt"
DOWNLOAD_URL="https://github.com/github/codeql-cli-binaries/releases"
QUERY_REPO="https://github.com/github/codeql.git"
FORCE=false

[ "$1" = "--force" ] && FORCE=true

get_latest_version() {
    curl -sL https://api.github.com/repos/github/codeql-cli-binaries/releases/latest \
        | grep '"tag_name"' | head -1 | sed 's/.*"tag_name": *"\([^"]*\)".*/\1/'
}

get_installed_version() {
    if [ -f "$VERSION_FILE" ]; then
        cat "$VERSION_FILE"
    else
        echo "none"
    fi
}

echo "Checking CodeQL version..."

INSTALLED=$(get_installed_version)

if [ "$FORCE" = false ] && [ "$INSTALLED" != "none" ]; then
    LATEST=$(get_latest_version)
    if [ -z "$LATEST" ]; then
        echo "WARNING: Could not determine latest version. Using installed: $INSTALLED"
        exit 0
    fi
    if [ "$INSTALLED" = "$LATEST" ]; then
        echo "CodeQL $INSTALLED is up to date."
        exit 0
    fi
    echo "Update available: $INSTALLED -> $LATEST"
else
    LATEST=$(get_latest_version)
    if [ -z "$LATEST" ]; then
        echo "ERROR: Could not determine latest CodeQL version."
        exit 1
    fi
fi

DIST_URL="${DOWNLOAD_URL}/download/${LATEST}/codeql-linux64.zip"
ZIP_FILE="${SCRIPT_DIR}/.codeql-dist.zip"

echo "Downloading CodeQL ${LATEST} (~509MB)..."
curl -L --progress-bar -o "$ZIP_FILE" "$DIST_URL"

echo "Extracting..."
rm -rf "${SCRIPT_DIR}/codeql"
mkdir -p "${SCRIPT_DIR}/codeql"
unzip -q -o "$ZIP_FILE" -d "${SCRIPT_DIR}/codeql"
mv "${SCRIPT_DIR}/codeql/codeql/codeql" "${SCRIPT_DIR}/codeql/codeql-cli" 2>/dev/null || true
shopt -s dotglob
mv "${SCRIPT_DIR}/codeql/codeql"/* "${SCRIPT_DIR}/codeql/"
rmdir "${SCRIPT_DIR}/codeql/codeql" 2>/dev/null || true
shopt -u dotglob

# Record version
echo -n "$LATEST" > "$VERSION_FILE"

# Clean up
rm -f "$ZIP_FILE"

echo "CodeQL ${LATEST} installed to ${SCRIPT_DIR}/codeql/"
echo "Binary: ${SCRIPT_DIR}/codeql/codeql"
echo ""
echo "To create a database:"
echo "  cd /path/to/i2pplus"
echo "  ${SCRIPT_DIR}/codeql/codeql database create codeql-db --language=java --command='ant updaterCompact'"
echo ""
echo "To analyze:"
echo "  ${SCRIPT_DIR}/codeql/codeql database analyze codeql-db --format=sarif-latest --output=dist/codeql.sarif"
