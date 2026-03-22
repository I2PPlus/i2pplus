#!/bin/bash
#
# Download or update PMD for I2P+ analysis.
# Checks current version against latest release and downloads if needed.
# Strips unnecessary language modules, keeping only Java + JS support.
#
# Usage: download-pmd.sh [--force]
#
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VERSION_FILE="${SCRIPT_DIR}/version.txt"
DOWNLOAD_URL="https://github.com/pmd/pmd/releases"
FORCE=false

[ "$1" = "--force" ] && FORCE=true

# Jars needed for Java + JS analysis
KEEP_JARS=(
  "Saxon-HE-*.jar"
  "pmd-core-*.jar"
  "pmd-java-*.jar"
  "pmd-javascript-*.jar"
  "pmd-cli-*.jar"
  "asm-*.jar"
  "rhino-*.jar"
  "jsoup-*.jar"
  "picocli-*.jar"
  "jline-*.jar"
  "progressbar-*.jar"
  "commons-lang3-*.jar"
  "pcollections-*.jar"
  "gson-*.jar"
  "xmlresolver-*.jar"
  "nice-xml-messages-*.jar"
  "slf4j-api-*.jar"
  "jul-to-slf4j-*.jar"
)

# Get latest stable version from GitHub API
get_latest_version() {
    curl -sL https://api.github.com/repos/pmd/pmd/releases/latest \
        | grep '"tag_name"' | head -1 | sed 's/.*"pmd_releases\/\(.*\)".*/\1/'
}

# Get currently installed version
get_installed_version() {
    if [ -f "$VERSION_FILE" ]; then
        cat "$VERSION_FILE"
    else
        echo "none"
    fi
}

echo "Checking PMD version..."

INSTALLED=$(get_installed_version)

if [ "$FORCE" = false ] && [ "$INSTALLED" != "none" ]; then
    LATEST=$(get_latest_version)
    if [ -z "$LATEST" ]; then
        echo "WARNING: Could not determine latest version. Using installed: $INSTALLED"
        exit 0
    fi
    if [ "$INSTALLED" = "$LATEST" ]; then
        echo "PMD $INSTALLED is up to date."
        exit 0
    fi
    echo "Update available: $INSTALLED -> $LATEST"
else
    LATEST=$(get_latest_version)
    if [ -z "$LATEST" ]; then
        echo "ERROR: Could not determine latest PMD version."
        exit 1
    fi
fi

DIST_URL="${DOWNLOAD_URL}/download/pmd_releases%2F${LATEST}/pmd-dist-${LATEST}-bin.zip"
ZIP_FILE="${SCRIPT_DIR}/.pmd-dist.zip"

echo "Downloading PMD ${LATEST}..."
curl -L -o "$ZIP_FILE" "$DIST_URL"

echo "Extracting..."
rm -rf "${SCRIPT_DIR}/bin" "${SCRIPT_DIR}/lib" "${SCRIPT_DIR}/conf" \
       "${SCRIPT_DIR}/LICENSE" "${SCRIPT_DIR}/sbom"

TMPDIR="${SCRIPT_DIR}/.pmd-tmp"
rm -rf "$TMPDIR"
mkdir -p "$TMPDIR"
unzip -q -o "$ZIP_FILE" -d "$TMPDIR"
shopt -s dotglob
mv "$TMPDIR"/pmd-bin-*/* "$SCRIPT_DIR"/
rmdir "$TMPDIR"/pmd-bin-*
shopt -u dotglob
rm -rf "$TMPDIR"

# Record version
echo -n "$LATEST" > "$VERSION_FILE"

# Strip to only needed jars
echo "Stripping to essential jars..."
TEMP_LIB="${SCRIPT_DIR}/.lib-tmp"
mkdir -p "$TEMP_LIB"
for pattern in "${KEEP_JARS[@]}"; do
  cp "$SCRIPT_DIR"/lib/$pattern "$TEMP_LIB"/ 2>/dev/null
done
rm -rf "$SCRIPT_DIR/lib"
mv "$TEMP_LIB" "$SCRIPT_DIR/lib"

# Remove unnecessary files
rm -rf "${SCRIPT_DIR}/conf" "${SCRIPT_DIR}/LICENSE" "${SCRIPT_DIR}/sbom"
rm -f "${SCRIPT_DIR}/bin/pmd.bat"

echo "Installed $(ls "$SCRIPT_DIR/lib"/*.jar | wc -l) jars ($(du -sh "$SCRIPT_DIR/lib" | cut -f1))"

# Clean up
rm -f "$ZIP_FILE"

echo "PMD ${LATEST} installed to ${SCRIPT_DIR}"
echo "Binary: ${SCRIPT_DIR}/bin/pmd"
