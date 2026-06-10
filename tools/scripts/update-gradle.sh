#!/bin/bash
# Update Gradle wrapper to the latest stable release
# Downloads new wrapper jar and regenerates gradlew/gradlew.bat
#
# Usage: tools/scripts/update-gradle.sh [--dry-run] [--yes]
#
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
GRADLE_DIR="${PROJECT_ROOT}/tools/gradle"
WRAPPER_DIR="${GRADLE_DIR}/wrapper"
VERSION_FILE="${GRADLE_DIR}/version.txt"
PROPS="${WRAPPER_DIR}/gradle-wrapper.properties"
GRADLEW="${PROJECT_ROOT}/gradlew"

DRY_RUN=false
YES=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run|-n) DRY_RUN=true; shift ;;
        --yes|-y) YES=true; shift ;;
        --help|-h) echo "Usage: $0 [--dry-run] [--yes]"; exit 0 ;;
        *) echo "Usage: $0 [--dry-run] [--yes]"; exit 1 ;;
    esac
done

# Get current version from version.txt
if [ -f "$VERSION_FILE" ]; then
    CURRENT=$(head -1 "$VERSION_FILE" | tr -d '[:space:]')
else
    echo "Error: $VERSION_FILE not found"
    exit 1
fi

# Fetch latest stable version from Gradle API
echo "Checking latest Gradle version..."
LATEST_JSON=$(curl -sSL --max-time 10 "https://services.gradle.org/versions/current" 2>/dev/null || true)
LATEST=$(echo "$LATEST_JSON" | grep -oP '"version"\s*:\s*"\K[^"]+' 2>/dev/null || echo "")

if [ -z "$LATEST" ]; then
    echo "Warning: could not fetch latest version from services.gradle.org"
    echo "Gradle wrapper remains at ${CURRENT}."
    exit 1
fi

echo "  Current: ${CURRENT}"
echo "  Latest:  ${LATEST}"

if [ "$CURRENT" = "$LATEST" ]; then
    echo "Already up to date."
    exit 0
fi

echo "New Gradle version available: ${LATEST}"

if $DRY_RUN; then
    echo "DRY RUN - no changes made."
    echo "Would update:"
    echo "  ${VERSION_FILE}"
    echo "  ${PROPS}"
    echo "  ${WRAPPER_DIR}/gradle-wrapper.jar"
    echo "  ${GRADLEW}"
    echo "  ${PROJECT_ROOT}/gradlew.bat"
    exit 0
fi

# Prompt user unless --yes
if ! $YES; then
    read -r -p "Update Gradle wrapper from ${CURRENT} to ${LATEST}? [y/N] " REPLY
    case "$REPLY" in
        [yY]|[yY][eE][sS]) ;;
        *) echo "Skipped."; exit 0 ;;
    esac
fi

# Update distribution URL in properties
DIST_URL="https\\\\://services.gradle.org/distributions/gradle-${LATEST}-bin.zip"
if grep -q 'distributionUrl=' "$PROPS"; then
    sed -i "s|distributionUrl=.*|distributionUrl=${DIST_URL}|" "$PROPS"
else
    echo "distributionUrl=${DIST_URL}" >> "$PROPS"
fi
echo "Updated gradle-wrapper.properties to ${LATEST}"

# Check for java
JAVA_CMD=""
if command -v java &>/dev/null; then
    JAVA_CMD="java"
elif [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_CMD="$JAVA_HOME/bin/java"
fi

if [ -z "$JAVA_CMD" ]; then
    echo "Warning: Java not found. Run './gradlew wrapper --gradle-version ${LATEST}' manually"
    echo "after installing Java to regenerate the wrapper jar and scripts."
    echo "${LATEST}" > "$VERSION_FILE"
    exit 0
fi

if [ ! -f "$GRADLEW" ]; then
    echo "Warning: gradlew not found at ${GRADLEW}. Run './gradlew wrapper --gradle-version ${LATEST}'"
    echo "manually after installing the wrapper."
    echo "${LATEST}" > "$VERSION_FILE"
    exit 0
fi

# Regenerate wrapper jar and scripts
echo "Regenerating wrapper for Gradle ${LATEST}..."
chmod +x "$GRADLEW"
if ! "$GRADLEW" wrapper --gradle-version "${LATEST}" --no-daemon 2>&1; then
    echo "Warning: wrapper regeneration failed. The properties file has been updated."
    echo "Run '${GRADLEW} wrapper --gradle-version ${LATEST}' manually to retry."
    exit 1
fi

# Update version.txt
echo "${LATEST}" > "$VERSION_FILE"

echo ""
echo "=== Done ==="
echo "Gradle wrapper updated to ${LATEST}."
echo "Review changes with: git diff"
