#!/bin/bash
# Update Tanuki Java Service Wrapper binaries
# Downloads the community edition delta pack and copies files to installer/lib/wrapper/
#
# Usage: scripts/update-wrapper.sh --version VERSION [--dry-run]
#        scripts/update-wrapper.sh --version VERSION --file /path/to/delta-pack.zip
#
# Windows binaries (win32/, win64/, win-all/) are NOT updated by this script
# as they require manual compilation. See installer/lib/wrapper/README.txt.

set -e

DRY_RUN=false
VERSION=""
FILE=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --version|-v)
            VERSION="$2"
            shift 2
            ;;
        --file|-f)
            FILE="$2"
            shift 2
            ;;
        --dry-run|-n)
            DRY_RUN=true
            shift
            ;;
        *)
            echo "Usage: $0 --version VERSION [--file ZIP] [--dry-run]" >&2
            exit 1
            ;;
    esac
done

if [ -z "$VERSION" ]; then
    echo "Error: --version is required" >&2
    echo "Usage: $0 --version VERSION [--file ZIP] [--dry-run]" >&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WRAPPER_DIR="${PROJECT_ROOT}/installer/lib/wrapper"
EDITION="st"

DOWNLOAD_URL="https://download.tanukisoftware.com/wrapper/${VERSION}/wrapper-delta-pack-${VERSION}-${EDITION}.zip"

echo "=== Tanuki Java Service Wrapper Updater ==="
echo "Version: ${VERSION} (${EDITION})"
echo "Download: ${DOWNLOAD_URL}"
echo ""

# File mappings: delta-pack-source -> target-dir
# Format: source-lib:source-bin:target-dir
# Empty bin means no binary (lib only)
MAPPINGS=(
    "libwrapper-linux-x86-32.so:wrapper-linux-x86-32:linux"
    "libwrapper-linux-x86-64.so:wrapper-linux-x86-64:linux64"
    "libwrapper-linux-arm-64.so:wrapper-linux-arm-64:linux64-armv8"
    "libwrapper-linux-armel-32.so:wrapper-linux-armel-32:linux-armv5"
    "libwrapper-linux-armhf-32.so:wrapper-linux-armhf-32:linux-armv7"
    "libwrapper-freebsd-x86-32.so:wrapper-freebsd-x86-32:freebsd"
    "libwrapper-freebsd-x86-64.so:wrapper-freebsd-x86-64:freebsd64"
    "libwrapper-freebsd-arm-64.so:wrapper-freebsd-arm-64:freebsd-arm64"
    "libwrapper-macosx-universal-64.jnilib:wrapper-macosx-universal-64:macosx"
    "libwrapper-macosx-arm-64.dylib::macosx-arm64"
)

# Wrapper jar
JAR_TARGET="${WRAPPER_DIR}/all/wrapper.jar"

if $DRY_RUN; then
    echo "DRY RUN - no files will be downloaded or modified."
    echo ""
    echo "Would update wrapper.jar:"
    echo "  wrapper.jar -> all/wrapper.jar"
    echo ""
    echo "Would update platform binaries:"

    for mapping in "${MAPPINGS[@]}"; do
        IFS=':' read -r src_lib src_bin target_dir <<< "$mapping"
        if [ "$target_dir" = "macosx" ]; then
            ext_lib="${src_lib##*.}"
            echo "  ${target_dir}/"
            echo "    ${src_lib} -> ${target_dir}/${src_lib}"
            echo "    ${src_bin} -> ${target_dir}/i2psvc-macosx-$(echo "$src_bin" | sed 's/wrapper-macosx-//')"
        else
            echo "  ${target_dir}/"
            echo "    ${src_lib} -> ${target_dir}/libwrapper.so"
            echo "    ${src_bin} -> ${target_dir}/i2psvc"
        fi
    done

    echo ""
    echo "NOT updated (manual builds required):"
    echo "  win32/       - Windows 32-bit"
    echo "  win64/       - Windows 64-bit"
    echo "  win-all/     - Fallback wrapper.jar for Windows"
    echo ""
    echo "Deprecated files (32-bit macOS, no longer in delta pack):"
    echo "  macosx/*-universal-32.*  - Consider removing"
    exit 0
fi

TMP_DIR=$(mktemp -d)
trap 'rm -rf "${TMP_DIR}"' EXIT

if [ -n "$FILE" ]; then
    echo "Using local delta pack: ${FILE}"
    if [ ! -f "$FILE" ]; then
        echo "Error: file not found: ${FILE}" >&2
        exit 1
    fi
    cp "$FILE" "${TMP_DIR}/delta-pack.zip"
else
    echo "Downloading delta pack..."
    if command -v curl > /dev/null; then
        curl -fSL "${DOWNLOAD_URL}" -o "${TMP_DIR}/delta-pack.zip"
    elif command -v wget > /dev/null; then
        wget -q -O "${TMP_DIR}/delta-pack.zip" "${DOWNLOAD_URL}"
    else
        echo "Error: neither curl nor wget found" >&2
        exit 1
    fi
fi

echo "Extracting..."
unzip -qo "${TMP_DIR}/delta-pack.zip" -d "${TMP_DIR}"

DELTA_DIR=$(find "${TMP_DIR}" -maxdepth 1 -type d -name "wrapper-*" | head -1)
if [ -z "${DELTA_DIR}" ]; then
    echo "Error: could not find extracted delta pack directory" >&2
    exit 1
fi

LIB="${DELTA_DIR}/lib"
BIN="${DELTA_DIR}/bin"

echo ""
echo "Updating wrapper.jar..."
cp "${LIB}/wrapper.jar" "${JAR_TARGET}"

echo ""
echo "Updating platform binaries:"

for mapping in "${MAPPINGS[@]}"; do
    IFS=':' read -r src_lib src_bin target_dir <<< "$mapping"

    if [ ! -d "${WRAPPER_DIR}/${target_dir}" ]; then
        echo "  ${target_dir}/ - creating directory"
        mkdir -p "${WRAPPER_DIR}/${target_dir}"
    fi

    # Check if source lib exists in delta pack
    if [ ! -f "${LIB}/${src_lib}" ]; then
        echo "  ${target_dir}/ - ${src_lib} not in delta pack, skipping"
        continue
    fi

    echo "  ${target_dir}/"

    # Copy library (handle different extensions)
    lib_ext="${src_lib##*.}"
    if [ "$target_dir" = "macosx" ] || [ "$target_dir" = "macosx32" ] || [ "$target_dir" = "macosx-arm64" ]; then
        cp "${LIB}/${src_lib}" "${WRAPPER_DIR}/${target_dir}/${src_lib}"
        # Copy binary if it exists (empty src_bin means lib only)
        if [ -n "$src_bin" ] && [ -f "${BIN}/${src_bin}" ]; then
            macos_arch=$(echo "$src_bin" | sed 's/wrapper-macosx-//')
            cp "${BIN}/${src_bin}" "${WRAPPER_DIR}/${target_dir}/i2psvc-macosx-${macos_arch}"
        fi
    else
        cp "${LIB}/${src_lib}" "${WRAPPER_DIR}/${target_dir}/libwrapper.so"
        if [ -n "$src_bin" ] && [ -f "${BIN}/${src_bin}" ]; then
            cp "${BIN}/${src_bin}" "${WRAPPER_DIR}/${target_dir}/i2psvc"
        fi
    fi
done

echo ""
echo "Stripping binaries and disabling execute bit..."
for dir in freebsd freebsd64 linux linux64 linux-armv5 linux-armv7 linux64-armv8; do
    if [ -d "${WRAPPER_DIR}/${dir}" ]; then
        for f in "${WRAPPER_DIR}/${dir}"/*; do
            chmod -x "$f" 2>/dev/null || true
            if command -v strip > /dev/null && file "$f" | grep -q "ELF"; then
                strip "$f" 2>/dev/null || true
            fi
        done
    fi
done

for dir in macosx macosx32 macosx-arm64; do
    if [ -d "${WRAPPER_DIR}/${dir}" ]; then
        for f in "${WRAPPER_DIR}/${dir}"/*; do
            [ -f "$f" ] || continue
            chmod -x "$f" 2>/dev/null || true
        done
    fi
done

echo ""
echo "=== Done ==="
echo "Updated wrapper ${VERSION} (${EDITION} edition)."
echo ""
echo "NOT updated (manual builds required):"
echo "  win32/       - Windows 32-bit"
echo "  win64/       - Windows 64-bit"
echo "  win-all/     - Fallback wrapper.jar for Windows"
echo ""
echo "Review changes and commit when ready."
