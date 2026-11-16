#!/bin/bash
# Update Tanuki Wrapper from Delta Pack
# Downloads the delta pack from SourceForge and extracts Linux/FreeBSD/macOS binaries
#
# Usage: update-wrapper.sh [--version X.X.X]
# Requirements: curl, unzip

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_DIR="${SCRIPT_DIR}"
CACHE_DIR="${WRAPPER_DIR}/cache"
RELEASES="${WRAPPER_DIR}/wrapper-release.sh"

# The pinned release, what version.txt names.
pinned_version() {
    if [ -f "${WRAPPER_DIR}/version.txt" ]; then
        grep "^WRAPPER_VERSION=" "${WRAPPER_DIR}/version.txt" | cut -d= -f2
    fi
}

VERSION=$(pinned_version)
if [ -z "${VERSION}" ]; then
    VERSION="3.6.4"
fi

LATEST=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --version) VERSION="$2"; shift 2 ;;
        --latest) LATEST=1; shift ;;
        *) shift ;;
    esac
done

# --latest takes the newest release on SourceForge, so a bump needs no edit to
# version.txt first; the file follows the build in sync_bookkeeping, and the
# caller rolls it back if the build fails.
if [ "${LATEST}" -eq 1 ]; then
    newest=$("${RELEASES}" latest) || {
        echo "update-wrapper.sh: cannot reach the SourceForge feed, staying at ${VERSION}" >&2
        newest=""
    }
    if [ -n "${newest}" ] && [ "${newest}" != "${VERSION}" ]; then
        echo "Target release ${newest} (version.txt pins ${VERSION})"
        VERSION="${newest}"
    fi
fi

BASE_URL="https://sourceforge.net/projects/wrapper/files/wrapper"
DELTA_PACK="wrapper-delta-pack-${VERSION}.zip"

# The release folder carries the upload date in its name, and that date is part
# of the download URL. Looked up rather than remembered: a hand-kept table of
# dates went stale the day a new release landed, and the download asked the
# previous release's folder for the new one - a 404.
SRC_DATE=$("${RELEASES}" --version "${VERSION}" date) || {
    echo "update-wrapper.sh: no release folder for ${VERSION} on SourceForge" >&2
    exit 1
}

mkdir -p "${CACHE_DIR}"

DELTA_CACHE="${CACHE_DIR}/${DELTA_PACK}"
DELTA_DIR="${CACHE_DIR}/deltapack_${VERSION}"

echo "Checking wrapper ${VERSION}..."

check_up_to_date() {
    [ -f "${WRAPPER_DIR}/all/wrapper.jar" ] || return 1
    [ -f "${WRAPPER_DIR}/linux64/libwrapper.so" ] || return 1
    [ -f "${WRAPPER_DIR}/linux64/i2psvc" ] || return 1
    [ -f "${WRAPPER_DIR}/win64/wrapper.dll" ] || return 1
    [ -f "${WRAPPER_DIR}/win64/I2Psvc.exe" ] || return 1
    [ -f "${WRAPPER_DIR}/macosx/libwrapper-macosx-universal-64.jnilib" ] || return 1

    [ "$(jar_version "${WRAPPER_DIR}/all/wrapper.jar")" = "${VERSION}" ] || {
        local jar_ver=$(jar_version "${WRAPPER_DIR}/all/wrapper.jar")
        [ -n "${jar_ver}" ] && echo "Installed wrapper.jar is ${jar_ver}, target is ${VERSION}"
        return 1
    }

    return 0
}

# The version a jar was built as, from its manifest.
jar_version() {
    unzip -p "$1" META-INF/MANIFEST.MF 2>/dev/null |
        grep "Implementation-Version" | cut -d' ' -f2 | tr -d '\r'
}

# A delta pack is only cached if it unpacked: an earlier run that downloaded a
# 404 page and tried to unzip it left an empty deltapack_<version>/ behind, and
# the next run trusted the directory, copied nothing, and still called itself
# up to date.
delta_pack_cached() {
    [ -f "${DELTA_DIR}/wrapper-delta-pack-${VERSION}/lib/wrapper.jar" ]
}

# version.txt and README.md name the version, whatever the update did.
sync_bookkeeping() {
    "${WRAPPER_DIR}/sync-wrapper-version.sh" --version "${VERSION}" ||
        echo "WARNING: version.txt / README.md were not updated - fix them by hand" >&2
}

if check_up_to_date; then
    echo "Nothing to do, all wrapper files are up to date."
    sync_bookkeeping
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

if delta_pack_cached; then
    echo "Using cached delta pack: ${DELTA_DIR}"
else
    [ -d "${DELTA_DIR}" ] && {
        echo "Discarding incomplete delta pack cache: ${DELTA_DIR}"
        rm -rf "${DELTA_DIR}"
    }
    echo "=== Downloading Delta Pack ${VERSION} ==="
    echo "URL: ${BASE_URL}/Wrapper_${VERSION}_${SRC_DATE}/${DELTA_PACK}"

    curl -fSL "${BASE_URL}/Wrapper_${VERSION}_${SRC_DATE}/${DELTA_PACK}" -o "${DELTA_CACHE}" || {
        echo "update-wrapper.sh: the delta pack download failed" >&2
        rm -f "${DELTA_CACHE}"
        exit 1
    }

    echo "Extracting..."
    mkdir -p "${DELTA_DIR}"
    unzip -qo "${DELTA_CACHE}" -d "${DELTA_DIR}" || {
        echo "update-wrapper.sh: the downloaded delta pack is not a zip" >&2
        rm -rf "${DELTA_DIR}" "${DELTA_CACHE}"
        exit 1
    }
    delta_pack_cached || {
        echo "update-wrapper.sh: the delta pack unpacked without a wrapper.jar in it" >&2
        rm -rf "${DELTA_DIR}" "${DELTA_CACHE}"
        exit 1
    }
fi

LIB="${DELTA_DIR}/wrapper-delta-pack-${VERSION}/lib"
BIN="${DELTA_DIR}/wrapper-delta-pack-${VERSION}/bin"

echo "Updating wrapper.jar..."
cp "${LIB}/wrapper.jar" "${WRAPPER_DIR}/all/wrapper.jar" || {
    echo "update-wrapper.sh: no wrapper.jar in the delta pack" >&2
    exit 1
}

echo "Updating platform binaries..."

declare -A MAPPINGS=(
    ["libwrapper-linux-x86-64.so"]="linux64:wrapper-linux-x86-64"
    ["libwrapper-linux-arm-64.so"]="linux64-armv8:wrapper-linux-arm-64"
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
cp "${LIB}/libwrapper-macosx-arm-64.dylib" "${WRAPPER_DIR}/macosx-arm64/" 2>/dev/null || true
if [ -f "${BIN}/wrapper-macosx-universal-64" ]; then
    cp "${BIN}/wrapper-macosx-universal-64" "${WRAPPER_DIR}/macosx/i2psvc-macosx-universal-64"
    chmod +x "${WRAPPER_DIR}/macosx/i2psvc-macosx-universal-64"
    strip "${WRAPPER_DIR}/macosx/i2psvc-macosx-universal-64" 2>/dev/null || true
fi
if [ -f "${BIN}/wrapper-macosx-arm-64" ]; then
    cp "${BIN}/wrapper-macosx-arm-64" "${WRAPPER_DIR}/macosx-arm64/i2psvc-macosx-arm-64"
    chmod +x "${WRAPPER_DIR}/macosx-arm64/i2psvc-macosx-arm-64"
    strip "${WRAPPER_DIR}/macosx-arm64/i2psvc-macosx-arm-64" 2>/dev/null || true
fi

echo "Removing 32-bit and unsupported platforms..."
for dir in linux linux-armv5 linux-armv7 freebsd linux-ppc solaris; do
    if [ -d "${WRAPPER_DIR}/${dir}" ]; then
        echo "  Removing ${dir}/ (32-bit or not supported)"
        rm -rf "${WRAPPER_DIR}/${dir}"
    fi
done
for f in "${WRAPPER_DIR}"/macosx/*-32 "${WRAPPER_DIR}"/macosx/*universal-32*; do
    [ -f "$f" ] || continue
    echo "  Removing $(basename "$f") (32-bit)"
    rm -f "$f"
done

sync_bookkeeping

echo ""
echo "=== Updated ==="
ls -la "${WRAPPER_DIR}/all/"
ls -la "${WRAPPER_DIR}/linux64/"
