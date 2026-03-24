#!/bin/bash
#
# Download or update SpotBugs for I2P+ analysis.
# Checks current version against latest release and downloads if needed.
# Strips unnecessary files for a minimal installation.
#
# Usage: download-spotbugs.sh [--force]
#
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VERSION_FILE="${SCRIPT_DIR}/version.txt"
DOWNLOAD_URL="https://github.com/spotbugs/spotbugs/releases"
FORCE=false

[ "$1" = "--force" ] && FORCE=true

# SpotBugs 4.9.x has a regression (CheckReturnAnnotationDatabase crash on Java 21)
# Pin to 4.8.x stable until fixed: https://github.com/spotbugs/spotbugs/issues/3501
SPOTBUGS_PIN="4.8.6"

get_latest_version() {
    echo "$SPOTBUGS_PIN"
}

# Get currently installed version
get_installed_version() {
    if [ -f "$VERSION_FILE" ]; then
        cat "$VERSION_FILE"
    else
        echo "none"
    fi
}

echo "Checking SpotBugs version..."

INSTALLED=$(get_installed_version)

if [ "$FORCE" = false ] && [ "$INSTALLED" != "none" ]; then
    LATEST=$(get_latest_version)
    if [ -z "$LATEST" ]; then
        echo "WARNING: Could not determine latest version. Using installed: $INSTALLED"
        exit 0
    fi
    if [ "$INSTALLED" = "$LATEST" ]; then
        echo "SpotBugs $INSTALLED is up to date."
        exit 0
    fi
    echo "Update available: $INSTALLED -> $LATEST"
else
    LATEST=$(get_latest_version)
    if [ -z "$LATEST" ]; then
        echo "ERROR: Could not determine latest SpotBugs version."
        exit 1
    fi
fi

DIST_URL="${DOWNLOAD_URL}/download/${LATEST}/spotbugs-${LATEST}.tgz"
TMPDIR=$(mktemp -d /tmp/spotbugs-XXXXXX)
TGZ_FILE="${TMPDIR}/spotbugs-dist.tgz"

cleanup() { rm -rf "$TMPDIR"; }
trap cleanup EXIT

echo "Downloading SpotBugs ${LATEST}..."
LD_PRELOAD= curl -L -o "$TGZ_FILE" "$DIST_URL"

echo "Extracting..."
tar xzf "$TGZ_FILE" -C "$TMPDIR"

# Find the extracted directory (exclude TMPDIR itself which also matches spotbugs-*)
EXTRACTED=$(find "$TMPDIR" -maxdepth 1 -type d -name 'spotbugs-*' ! -path "$TMPDIR" | head -1)
if [ -z "$EXTRACTED" ]; then
    echo "ERROR: Could not find extracted SpotBugs directory."
    exit 1
fi

echo "Cleaning up unnecessary files..."
# Remove deprecated and experimental scripts
rm -rf "${EXTRACTED}/bin/deprecated" "${EXTRACTED}/bin/experimental"
# Remove Windows-only files
rm -f "${EXTRACTED}/bin/spotbugs.bat" "${EXTRACTED}/bin/spotbugs.ico"
# Remove bundled license files (we keep our own in docs/licenses/LICENSE-spotbugs.txt)
rm -f "${EXTRACTED}"/LICENSE-*.txt "${EXTRACTED}/LICENSE.txt" "${EXTRACTED}/README.txt"
# Remove bundled XSL stylesheets (we use our own in config/)
rm -rf "${EXTRACTED}/src"
# Remove plugin placeholder
rm -rf "${EXTRACTED}/plugin"

# Strip unnecessary jars (not needed for Java analysis)
rm -f "${EXTRACTED}"/lib/Saxon-HE-*.jar       # 5.6MB - XSLT, only for HTML report gen
rm -f "${EXTRACTED}"/lib/commons-io-*.jar      # not referenced by SpotBugs core
rm -f "${EXTRACTED}"/lib/error_prone-*.jar     # annotation-only, not needed at analysis time
rm -f "${EXTRACTED}"/lib/gson-*.jar            # JSON, not needed for XML output
rm -f "${EXTRACTED}"/lib/jcip-annotations-*.jar # annotation-only
rm -f "${EXTRACTED}"/lib/xmlresolver-*.jar     # XML resolver, not needed
# NOTE: jsr305, dom4j, jaxen, slf4j, log4j, commons-lang3, commons-text are REQUIRED

# Copy our log4j2.xml config (disables logging noise)
mkdir -p "${EXTRACTED}/lib/config"
cp "${SCRIPT_DIR}/config/log4j2.xml" "${EXTRACTED}/lib/config/log4j2.xml"

# Remove old version directories, then install
rm -rf "${SCRIPT_DIR}"/spotbugs-*
mv "$EXTRACTED" "$SCRIPT_DIR/"

# Record version
echo -n "$LATEST" > "$VERSION_FILE"

# Find installed dir for reporting
SPOTBUGS_DIR=$(find "$SCRIPT_DIR" -maxdepth 1 -type d -name 'spotbugs-*' | head -1)
echo "Installed $(ls "${SPOTBUGS_DIR}/lib"/*.jar | wc -l) jars ($(du -sh "${SPOTBUGS_DIR}/lib" | cut -f1))"
echo "SpotBugs ${LATEST} installed to ${SPOTBUGS_DIR}"
echo "Binary: ${SPOTBUGS_DIR}/bin/spotbugs"
