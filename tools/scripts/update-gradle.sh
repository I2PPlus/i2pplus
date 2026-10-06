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

# The wrapper task writes the stock layout: gradle/wrapper/ plus scripts pointing at
# $APP_HOME/gradle/wrapper. This repo keeps them in tools/gradle/wrapper and patches
# both scripts to look there (cd1c91f352), so as generated the build would silently
# split across two wrapper directories. Move the jar back and re-apply the one-line
# redirect instead of keeping what the task wrote.
if [ -d "${PROJECT_ROOT}/gradle/wrapper" ]; then
    cp "${PROJECT_ROOT}/gradle/wrapper/gradle-wrapper.jar" "${WRAPPER_DIR}/gradle-wrapper.jar"
    rm -rf "${PROJECT_ROOT}/gradle/wrapper"
    echo "Relocated gradle-wrapper.jar into tools/gradle/wrapper"

    for script in "$GRADLEW" "${PROJECT_ROOT}/gradlew.bat"; do
        [ -f "$script" ] || continue
        # POSIX sed sees the .bat backslashes as ordinary characters; in BRE a
        # backslash is not special, so no escaping is needed on either side.
        sed -i \
            -e 's|\$APP_HOME/gradle/wrapper/gradle-wrapper.jar|$APP_HOME/tools/gradle/wrapper/gradle-wrapper.jar|g' \
            -e 's|%APP_HOME%\\gradle\\wrapper\\gradle-wrapper.jar|%APP_HOME%\\tools\\gradle\\wrapper\\gradle-wrapper.jar|g' \
            "$script"
    done

    if grep -q 'APP_HOME/gradle/wrapper' "$GRADLEW"; then
        echo "Error: gradlew still points at the stock wrapper path; revert and retry." >&2
        exit 1
    fi
    chmod +x "$GRADLEW"
    echo "Re-applied the tools/gradle wrapper redirect to gradlew and gradlew.bat"
fi

# The `wrapper` task above rewrites both start scripts from scratch, so any local edit is
# gone by this point and has to be reapplied. Two matter: the tools/gradle wrapper redirect
# above, and the relocated project cache below. Both exist to keep a build from writing into
# the source tree, so silently losing either would push that output back into the workspace.
# Done with python rather than sed because the block spans many lines and the .bat needs its
# CRLF endings preserved.
if ! grep -q 'build-i2p-cache' "$GRADLEW"; then
    python3 - "$GRADLEW" "${PROJECT_ROOT}/gradlew.bat" <<'PYEOF'
import sys

posix_anchor = '# For Cygwin or MSYS, switch paths to Windows format before running java\n'
posix_block = (
    '# Keep Gradle\'s project cache (task history, configuration cache, file hashes) out of\n'
    '# the source tree. Everything else this build produces already goes to $TMPDIR; see\n'
    '# settings.gradle for buildDir. A sibling of build-i2p rather than a child of it,\n'
    '# because `ant clean` and `ant distclean` both delete all of build.root - putting the\n'
    '# cache in there would discard it on any ant clean, and Gradle\'s own `clean` task\n'
    '# deliberately keeps its project cache. Skipped when the caller passes their own\n'
    '# --project-cache-dir, since Gradle rejects a duplicate instead of taking the last one.\n'
    'GRADLE_PROJECT_CACHE_DIR="${TMPDIR:-/tmp}/build-i2p-cache"\n'
    'for arg in "$@"; do\n'
    '    case $arg in\n'
    '        --project-cache-dir|--project-cache-dir=*) GRADLE_PROJECT_CACHE_DIR="" ;;\n'
    '    esac\n'
    'done\n'
    'if [ -n "$GRADLE_PROJECT_CACHE_DIR" ]; then\n'
    '    set -- "--project-cache-dir=$GRADLE_PROJECT_CACHE_DIR" "$@"\n'
    'fi\n'
    '\n'
)
posix_anchor_line = posix_anchor + 'if "$cygwin" || "$msys" ; then\n'

# Matched against the line as it stands after the tools/gradle redirect above, which is
# why the tools path is written here rather than the stock gradle/wrapper one.
bat_anchor = 'endlocal & "%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -jar "%APP_HOME%\\tools\\gradle\\wrapper\\gradle-wrapper.jar" %* & call :exitWithErrorLevel & goto exitWithErrorLevel'
# The block below defines the arg, so the exec line also has to pass it: anchor on the
# stock form and replace it with the rewritten one.
bat_new_line = bat_anchor.replace('%*', '%GRADLE_PROJECT_CACHE_ARG% %*', 1)
bat_block = (
    "@rem Keep Gradle's project cache (task history, configuration cache, file hashes) out\r\n"
    "@rem of the source tree; see gradlew for why this is a sibling of build-i2p rather than a\r\n"
    "@rem child of it. Skipped when the caller passes their own --project-cache-dir, since\r\n"
    "@rem Gradle rejects a duplicate instead of taking the last one.\r\n"
    "set \"GRADLE_PROJECT_CACHE_DIR=%TEMP%\\build-i2p-cache\"\r\n"
    "echo %* | findstr /b /c:\"--project-cache-dir\" >nul && set \"GRADLE_PROJECT_CACHE_DIR=\"\r\n"
    "if defined GRADLE_PROJECT_CACHE_DIR set \"GRADLE_PROJECT_CACHE_ARG=--project-cache-dir=%GRADLE_PROJECT_CACHE_DIR%\"\r\n"
    "\r\n"
    "@rem Execute gradlew\r\n"
)


def patch(path, anchor, replacement):
    """Insert replacement in place of anchor, once. A no-op if already applied."""
    if not path:
        return
    try:
        raw = open(path, 'rb').read()
    except FileNotFoundError:
        return
    text = raw.decode('utf-8', errors='surrogateescape')
    if 'build-i2p-cache' in text:
        return
    if text.count(anchor) != 1:
        sys.exit(f'Error: {path}: expected one anchor, found {text.count(anchor)}')
    open(path, 'wb').write(text.replace(anchor, replacement, 1).encode(
        'utf-8', errors='surrogateescape'))


gradlew, gradlew_bat = (sys.argv[1] if len(sys.argv) > 1 else '',
                        sys.argv[2] if len(sys.argv) > 2 else '')
patch(gradlew, posix_anchor_line, posix_block + posix_anchor_line)
patch(gradlew_bat, bat_anchor, bat_block + bat_new_line)
print('  reapplied the relocated project cache to gradlew and gradlew.bat')
PYEOF
fi

for script in "$GRADLEW" "${PROJECT_ROOT}/gradlew.bat"; do
    [ -f "$script" ] || continue
    if ! grep -q 'build-i2p-cache' "$script"; then
        echo "Error: $(basename "$script") lost its relocated project cache; reapply and retry." >&2
        exit 1
    fi
done
chmod +x "$GRADLEW" 2>/dev/null || true
echo "Verified: no build state is written into the source tree"

# Update version.txt
echo "${LATEST}" > "$VERSION_FILE"

echo ""
echo "=== Done ==="
echo "Gradle wrapper updated to ${LATEST}."
echo "Review changes with: git diff"
