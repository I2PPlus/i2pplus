#!/bin/bash
#
# Download SonarQube Server (Community Edition) and SonarScanner CLI for I2P+.
#
# Usage:
#   download-sonar.sh server       # download SonarQube Server (~885MB)
#   download-sonar.sh scanner      # download SonarScanner CLI (~59MB)
#   download-sonar.sh all          # download both
#   download-sonar.sh [--force]    # backwards compat: download scanner (default)
#
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FORCE=false

# Parse mode
MODE="scanner"
case "$1" in
    server|scanner|all) MODE="$1"; shift ;;
    --force) FORCE=true; shift ;;
    --help|-h)
        echo "Usage: download-sonar.sh [mode] [--force]"
        echo ""
        echo "Modes:"
        echo "  scanner     Download SonarScanner CLI (~59MB) [default]"
        echo "  server      Download SonarQube Server Community Edition (~885MB)"
        echo "  all         Download both"
        echo ""
        echo "Options:"
        echo "  --force     Re-download even if already installed"
        echo "  --help,-h   Show this help"
        exit 0
        ;;
esac
[ "$1" = "--force" ] && FORCE=true

download_scanner() {
    local VERSION_FILE="${SCRIPT_DIR}/version.txt"
    local VERSION="8.1.0.6389"

    local INSTALLED="none"
    if [ -f "$VERSION_FILE" ]; then
        INSTALLED=$(cat "$VERSION_FILE")
    fi

    if [ "$FORCE" = false ] && [ "$INSTALLED" = "$VERSION" ]; then
        echo "SonarScanner $INSTALLED is up to date."
        return 0
    fi

    local DOWNLOAD_URL="https://binaries.sonarsource.com/Distribution/sonar-scanner-cli/sonar-scanner-cli-${VERSION}-linux-x64.zip"

    echo "Downloading SonarScanner ${VERSION}..."
    local ZIP_FILE
    ZIP_FILE=$(mktemp)
    curl -L -o "$ZIP_FILE" "$DOWNLOAD_URL"

    echo "Extracting..."
    unzip -q -o -d "$SCRIPT_DIR" "$ZIP_FILE"
    rm -f "$ZIP_FILE"

    local EXTRACTED_DIR
    EXTRACTED_DIR=$(find "$SCRIPT_DIR" -maxdepth 1 -type d -name "sonar-scanner-*" | head -1)
    if [ -z "$EXTRACTED_DIR" ]; then
        echo "Error: SonarScanner extracted directory not found"
        return 1
    fi

    echo -n "$VERSION" > "$VERSION_FILE"
    echo "SonarScanner ${VERSION} installed"
    echo "Binary: ${EXTRACTED_DIR}/bin/sonar-scanner"
}

download_server() {
    local VERSION_FILE="${SCRIPT_DIR}/server-version.txt"
    local VERSION="26.6.0.123539"

    local INSTALLED="none"
    if [ -f "$VERSION_FILE" ]; then
        INSTALLED=$(cat "$VERSION_FILE")
    fi

    if [ "$FORCE" = false ] && [ "$INSTALLED" = "$VERSION" ]; then
        echo "SonarQube Server $INSTALLED is up to date."
        return 0
    fi

    local DOWNLOAD_URL="https://binaries.sonarsource.com/Distribution/sonarqube/sonarqube-${VERSION}.zip"

    echo "Downloading SonarQube Server ${VERSION} (~885MB)..."
    local ZIP_FILE
    ZIP_FILE=$(mktemp)
    curl -L -o "$ZIP_FILE" "$DOWNLOAD_URL"

    echo "Extracting to ${SCRIPT_DIR}..."
    unzip -q -o -d "$SCRIPT_DIR" "$ZIP_FILE"
    rm -f "$ZIP_FILE"

    local EXTRACTED_DIR
    EXTRACTED_DIR=$(find "$SCRIPT_DIR" -maxdepth 1 -type d -name "sonarqube-*" | head -1)
    if [ -z "$EXTRACTED_DIR" ]; then
        echo "Error: SonarQube extracted directory not found"
        return 1
    fi

    echo -n "$VERSION" > "$VERSION_FILE"
    echo "SonarQube Server ${VERSION} installed"
    echo "Directory: ${EXTRACTED_DIR}"
    echo ""
    echo "To start:  ${EXTRACTED_DIR}/bin/linux-x86-64/sonar.sh console"
    echo "To stop:   ${EXTRACTED_DIR}/bin/linux-x86-64/sonar.sh stop"
    echo "Web UI:    http://localhost:9000  (admin / admin)"
}

case "$MODE" in
    scanner) download_scanner ;;
    server)  download_server ;;
    all)     download_scanner; download_server ;;
esac
