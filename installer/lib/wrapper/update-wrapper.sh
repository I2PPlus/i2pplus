#!/bin/bash
# Update Tanuki Wrapper from Delta Pack
# Downloads the delta pack from SourceForge and extracts Linux/FreeBSD/macOS binaries
#
# Usage: update-wrapper.sh [--version X.X.X]
# Requirements: curl, unzip

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_DIR="${SCRIPT_DIR}"
CACHE_DIR="${WRAPPER_DIR}/cache"

if [ -f "${WRAPPER_DIR}/version.txt" ]; then
    VERSION=$(grep "^WRAPPER_VERSION=" "${WRAPPER_DIR}/version.txt" | cut -d= -f2)
else
    VERSION="3.6.4"
fi

while [[ $# -gt 0 ]]; do
    case "$1" in
        --version) VERSION="$2"; shift 2 ;;
        *) shift ;;
    esac
done

SRC_DATE="20251218"
case "$VERSION" in
    3.6.5) SRC_DATE="20260317" ;;
    3.6.4) SRC_DATE="20251218" ;;
    3.6.3) SRC_DATE="20250910" ;;
esac

BASE_URL="https://sourceforge.net/projects/wrapper/files/wrapper"
DELTA_PACK="wrapper-delta-pack-${VERSION}.zip"

mkdir -p "${CACHE_DIR}"

DELTA_CACHE="${CACHE_DIR}/${DELTA_PACK}"
DELTA_DIR="${CACHE_DIR}/deltapack_${VERSION}"

echo "Checking wrapper ${VERSION}..."

check_up_to_date() {
    [ -f "${WRAPPER_DIR}/all/wrapper.jar" ] || return 1
    [ -f "${WRAPPER_DIR}/linux64/libwrapper.so" ] || return 1
    [ -f "${WRAPPER_DIR}/linux64/i2psvc" ] || return 1
    [ -f "${WRAPPER_DIR}/win64/wrapper.dll" ] || return 1
    [ -f "${WRAPPER_DIR}/win32/I2Psvc.exe" ] || return 1
    [ -f "${WRAPPER_DIR}/macosx/libwrapper-macosx-universal-64.jnilib" ] || return 1
    [ -f "${WRAPPER_DIR}/win32/wrapper.dll" ] || return 1

    local jar_ver=$(unzip -p "${WRAPPER_DIR}/all/wrapper.jar" META-INF/MANIFEST.MF 2>/dev/null | grep "Implementation-Version" | cut -d' ' -f2 | tr -d '\r')
    [ "${jar_ver}" = "${VERSION}" ] || return 1

    return 0
}

if check_up_to_date; then
    echo "Nothing to do, all wrapper files are up to date."
    exit 0
fi

for f in "${CACHE_DIR}"/deltapack_*; do
    [ -d "$f" ] || continue
    if [ "$f" != "${DELTA_DIR}" ]; then
        echo "Removing old delta pack: $f"
        rm -rf "$f"
    fi
done

for f in "${CACHE_DIR}"/*delta*.zip; do
    [ -f "$f" ] || continue
    if [ "$f" != "${DELTA_CACHE}" ]; then
        echo "Removing old zip: $f"
        rm -f "$f"
    fi
done

if [ -d "${DELTA_DIR}" ]; then
    echo "Using cached delta pack: ${DELTA_DIR}"
else
    echo "=== Downloading Delta Pack ${VERSION} ==="
    echo "URL: ${BASE_URL}/Wrapper_${VERSION}_${SRC_DATE}/${DELTA_PACK}"

    curl -fSL "${BASE_URL}/Wrapper_${VERSION}_${SRC_DATE}/${DELTA_PACK}" -o "${DELTA_CACHE}"

    echo "Extracting..."
    mkdir -p "${DELTA_DIR}"
    unzip -qo "${DELTA_CACHE}" -d "${DELTA_DIR}"
fi

LIB="${DELTA_DIR}/wrapper-delta-pack-${VERSION}/lib"
BIN="${DELTA_DIR}/wrapper-delta-pack-${VERSION}/bin"

echo "Updating wrapper.jar..."
cp "${LIB}/wrapper.jar" "${WRAPPER_DIR}/all/wrapper.jar"
cp "${LIB}/wrapper.jar" "${WRAPPER_DIR}/win-all/wrapper.jar"

echo "Updating platform binaries..."

declare -A MAPPINGS=(
    ["libwrapper-linux-x86-32.so"]="linux:wrapper-linux-x86-32"
    ["libwrapper-linux-x86-64.so"]="linux64:wrapper-linux-x86-64"
    ["libwrapper-linux-arm-64.so"]="linux64-armv8:wrapper-linux-arm-64"
    ["libwrapper-linux-armel-32.so"]="linux-armv5:wrapper-linux-armel-32"
    ["libwrapper-linux-armhf-32.so"]="linux-armv7:wrapper-linux-armhf-32"
    ["libwrapper-freebsd-x86-32.so"]="freebsd:wrapper-freebsd-x86-32"
    ["libwrapper-freebsd-x86-64.so"]="freebsd64:wrapper-freebsd-x86-64"
    ["libwrapper-freebsd-arm-64.so"]="freebsd-arm64:wrapper-freebsd-arm-64"
)

for lib in "${!MAPPINGS[@]}"; do
    target_dir="${MAPPINGS[$lib]%:*}"
    bin_name="${MAPPINGS[$lib]#*:}"
    if [ -f "${LIB}/${lib}" ]; then
        cp "${LIB}/${lib}" "${WRAPPER_DIR}/${target_dir}/libwrapper.so"
        if [ -f "${BIN}/${bin_name}" ]; then
            cp "${BIN}/${bin_name}" "${WRAPPER_DIR}/${target_dir}/i2psvc"
            chmod -x "${WRAPPER_DIR}/${target_dir}/i2psvc"
            strip "${WRAPPER_DIR}/${target_dir}/i2psvc" 2>/dev/null || true
        fi
        chmod -x "${WRAPPER_DIR}/${target_dir}/libwrapper.so"
        strip "${WRAPPER_DIR}/${target_dir}/libwrapper.so" 2>/dev/null || true
    fi
done

echo "Updating macOS binaries..."
cp "${LIB}/libwrapper-macosx-universal-64.jnilib" "${WRAPPER_DIR}/macosx/" 2>/dev/null || true
cp "${LIB}/libwrapper-macosx-universal-32.jnilib" "${WRAPPER_DIR}/macosx/" 2>/dev/null || true
cp "${LIB}/libwrapper-macosx-arm-64.dylib" "${WRAPPER_DIR}/macosx-arm64/" 2>/dev/null || true
if [ -f "${BIN}/wrapper-macosx-universal-64" ]; then
    cp "${BIN}/wrapper-macosx-universal-64" "${WRAPPER_DIR}/macosx/i2psvc-macosx-universal-64"
    chmod +x "${WRAPPER_DIR}/macosx/i2psvc-macosx-universal-64"
    strip "${WRAPPER_DIR}/macosx/i2psvc-macosx-universal-64" 2>/dev/null || true
fi
if [ -f "${BIN}/wrapper-macosx-universal-32" ]; then
    cp "${BIN}/wrapper-macosx-universal-32" "${WRAPPER_DIR}/macosx/i2psvc-macosx-universal-32"
    chmod +x "${WRAPPER_DIR}/macosx/i2psvc-macosx-universal-32"
    strip "${WRAPPER_DIR}/macosx/i2psvc-macosx-universal-32" 2>/dev/null || true
fi
if [ -f "${BIN}/wrapper-macosx-arm-64" ]; then
    cp "${BIN}/wrapper-macosx-arm-64" "${WRAPPER_DIR}/macosx-arm64/i2psvc-macosx-arm-64"
    chmod +x "${WRAPPER_DIR}/macosx-arm64/i2psvc-macosx-arm-64"
    strip "${WRAPPER_DIR}/macosx-arm64/i2psvc-macosx-arm-64" 2>/dev/null || true
fi

echo "Updating Windows 32-bit binaries..."
if [ -f "${BIN}/wrapper-windows-x86-32.exe" ]; then
    cp "${BIN}/wrapper-windows-x86-32.exe" "${WRAPPER_DIR}/win32/I2Psvc.exe"
fi
if [ -f "${LIB}/wrapper-windows-x86-32.dll" ]; then
    cp "${LIB}/wrapper-windows-x86-32.dll" "${WRAPPER_DIR}/win32/wrapper.dll"
fi

echo "Removing platforms not in deltapack..."
for dir in linux-ppc solaris; do
    if [ -d "${WRAPPER_DIR}/${dir}" ]; then
        echo "  Removing ${dir}/ (not in deltapack)"
        rm -rf "${WRAPPER_DIR}/${dir}"
    fi
done

echo ""
echo "=== Updated ==="
ls -la "${WRAPPER_DIR}/all/"
ls -la "${WRAPPER_DIR}/linux64/"