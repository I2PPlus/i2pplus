#!/bin/bash
#
# Download or update CodeQL CLI for I2P+ analysis.
# Checks current version against latest release and downloads if needed.
# CodeQL is ~509MB so only downloads when explicitly requested.
# Installs to tools/codeql/codeql-<version>/ to avoid codeql/codeql nesting.
#
# Usage: download-codeql.sh [--force]
#
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VERSION_FILE="${SCRIPT_DIR}/version.txt"
DOWNLOAD_URL="https://github.com/github/codeql-cli-binaries/releases"
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
    # Remove old version directory
    rm -rf "${SCRIPT_DIR}/codeql-${INSTALLED}"
else
    LATEST=$(get_latest_version)
    if [ -z "$LATEST" ]; then
        echo "ERROR: Could not determine latest CodeQL version."
        exit 1
    fi
fi

DIST_URL="${DOWNLOAD_URL}/download/${LATEST}/codeql-linux64.zip"
ZIP_FILE="${SCRIPT_DIR}/.codeql-dist.zip"
INSTALL_DIR="${SCRIPT_DIR}/codeql-${LATEST}"

echo "Downloading CodeQL ${LATEST} (~509MB)..."
curl -L --progress-bar -o "$ZIP_FILE" "$DIST_URL"

echo "Extracting to codeql-${LATEST}/..."
rm -rf "$INSTALL_DIR"
mkdir -p "$INSTALL_DIR"

# Zip contains codeql/ directory with the binary and query packs
TMPDIR="${SCRIPT_DIR}/.codeql-extract"
rm -rf "$TMPDIR"
mkdir -p "$TMPDIR"
unzip -q -o "$ZIP_FILE" -d "$TMPDIR"

# Move contents from codeql/ subdirectory to our versioned directory
shopt -s dotglob
mv "$TMPDIR"/codeql/* "$INSTALL_DIR"/
shopt -u dotglob
rm -rf "$TMPDIR"

# Symlink for convenience: tools/codeql/codeql -> tools/codeql/codeql-vX.Y.Z
rm -f "${SCRIPT_DIR}/codeql"
ln -sf "codeql-${LATEST}" "${SCRIPT_DIR}/codeql"

# Download query packs (stored in ~/.codeql by default)
echo "Downloading Java query packs..."
"${INSTALL_DIR}/codeql" pack download codeql/java-all 2>/dev/null || echo "WARNING: Could not download java-all"
"${INSTALL_DIR}/codeql" pack download codeql/java-queries 2>/dev/null || echo "WARNING: Could not download java-queries"

# Record version
echo -n "$LATEST" > "$VERSION_FILE"

# Clean up
rm -f "$ZIP_FILE"

echo "CodeQL ${LATEST} installed to ${INSTALL_DIR}/"
echo "Binary: ${INSTALL_DIR}/codeql"
echo "Symlink: ${SCRIPT_DIR}/codeql -> codeql-${LATEST}"
echo ""
echo "To create a database:"
echo "  cd /path/to/i2pplus"
echo "  ${INSTALL_DIR}/codeql database create codeql-db --language=java --command='ant updaterCompact'"
echo ""
echo "To analyze:"
echo "  ${INSTALL_DIR}/codeql database analyze codeql-db --format=sarif-latest --output=dist/codeql.sarif"
