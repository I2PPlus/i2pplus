#!/bin/bash
# Download and extract PMD if not already present

PMD_VERSION="${PMD_VERSION:-7.7.0}"
PMD_DIR="pmd-bin-${PMD_VERSION}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TOOLS_DIR="${PROJECT_ROOT}/tools"
PMD_BIN="${TOOLS_DIR}/${PMD_DIR}/bin/pmd"

if [ -f "$PMD_BIN" ]; then
    exit 0
fi

PMD_ZIP="${TOOLS_DIR}/pmd-dist-${PMD_VERSION}-bin.zip"
PMD_URL="https://github.com/pmd/pmd/releases/download/pmd_releases%2F${PMD_VERSION}/pmd-dist-${PMD_VERSION}-bin.zip"

echo "Downloading PMD ${PMD_VERSION}..."
if command -v curl > /dev/null; then
    curl -fSL "$PMD_URL" -o "$PMD_ZIP"
elif command -v wget > /dev/null; then
    wget -q -O "$PMD_ZIP" "$PMD_URL"
else
    echo "Error: neither curl nor wget found" >&2
    exit 1
fi

echo "Extracting..."
mkdir -p "$TOOLS_DIR"
unzip -qo "$PMD_ZIP" -d "$TOOLS_DIR"
rm -f "$PMD_ZIP"
chmod +x "$PMD_BIN"
echo "PMD ${PMD_VERSION} ready at ${PMD_DIR}/bin/pmd"
