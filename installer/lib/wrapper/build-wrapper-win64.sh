#!/bin/bash
# Build Tanuki Wrapper for Windows 64-bit
# Downloads source from SourceForge and cross-compiles using mingw-w64
#
# Usage: build-wrapper-win64.sh [--version X.X.X]
# Requirements: mingw-w64

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_DIR="${SCRIPT_DIR}"
CACHE_DIR="${WRAPPER_DIR}/cache"
INSTALL_DIR="${WRAPPER_DIR}"
WORK_DIR="${WRAPPER_DIR}/build-temp"

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

BASE_URL="https://sourceforge.net/projects/wrapper/files/wrapper_src"
TGZ="wrapper_${VERSION}_src.tar.gz"

mkdir -p "${CACHE_DIR}"
mkdir -p "${WORK_DIR}"

SRC_CACHE_DIR="${CACHE_DIR}/wrapper_${VERSION}_src"
SRC_DIR="${WORK_DIR}/wrapper_${VERSION}_src"

echo "Checking wrapper ${VERSION} win64..."

if [ -f "${WRAPPER_DIR}/win64/I2Psvc.exe" ] && [ -f "${WRAPPER_DIR}/win-all/wrapper.jar" ]; then
    local jar_ver=$(unzip -p "${WRAPPER_DIR}/win-all/wrapper.jar" META-INF/MANIFEST.MF 2>/dev/null | grep "Implementation-Version" | cut -d' ' -f2 | tr -d '\r')
    if [ "${jar_ver}" = "${VERSION}" ]; then
        echo "Nothing to do, all win64 wrapper files are up to date."
        exit 0
    fi
fi

for f in "${CACHE_DIR}"/wrapper_*_src; do
    [ -d "$f" ] || continue
    if [ "$f" != "${SRC_CACHE_DIR}" ]; then
        echo "Cleaning old source: $f"
        rm -rf "$f"
    fi
done

for f in "${CACHE_DIR}"/*_src.tar.gz; do
    [ -f "$f" ] || continue
    if [ "$f" != "${CACHE_DIR}/${TGZ}" ]; then
        echo "Cleaning old tarball: $f"
        rm -f "$f"
    fi
done

if [ ! -d "${SRC_CACHE_DIR}" ]; then
    echo "=== Downloading Wrapper ${VERSION} Source ==="
    curl -L -o "${CACHE_DIR}/${TGZ}" "${BASE_URL}/Wrapper_${VERSION}_${SRC_DATE}/${TGZ}"
    tar -xzf "${CACHE_DIR}/${TGZ}" -C "${CACHE_DIR}"
    echo "Cached at: ${SRC_CACHE_DIR}"
else
    echo "Using cached source: ${SRC_CACHE_DIR}"
fi

rm -rf "${SRC_DIR}"
cp -r "${SRC_CACHE_DIR}" "${SRC_DIR}"

echo "=== Building Wrapper ${VERSION} for Windows x64 ==="
cd "${SRC_DIR}/src/c"

HOST="x86_64-w64-mingw32"
WIN_DIR="win64"

command -v ${HOST}-gcc >/dev/null || { echo "Need ${HOST}-gcc"; exit 1; }

cp wrapper_win.c wrapper_win.c.orig 2>/dev/null || true
cp wrapper.c wrapper.c.orig 2>/dev/null || true

ICON_SRC="${WRAPPER_DIR}/../../resources/platform-specific/windows/console.ico"
if [ -f "${ICON_SRC}" ]; then
    cp "${ICON_SRC}" wrapper.ico
fi

sed -i 's/#include <errno.h>/#include <errno.h>\ntypedef int socklen_t;/' wrapper.c wrapper_win.c
sed -i 's/const char\* inet_ntop(/const char* wrapper_inet_ntop(/g' wrapper.c
sed -i 's/const char\* inet_pton(/const char* wrapper_inet_pton(/g' wrapper.c
sed -i 's/Iphlpapi\.h/iphlpapi.h/g' wrapperjni_win.c
sed -i '1s/^/#include <ws2tcpip.h>\n/' wrapper.c wrapper_win.c
# Fix CERT_CHAIN_PARA members not in newer mingw-w64
sed -i 's/ChainPara.dwUrlRetrievalTimeout = timeout;/\/\/ ChainPara.dwUrlRetrievalTimeout = timeout;/' wrapper_win.c
sed -i 's/ChainPara.RequestedIssuancePolicy = CertUsage;/\/\/ ChainPara.RequestedIssuancePolicy = CertUsage;/' wrapper_win.c
[ ! -f wrapperinfo.c ] && [ -f wrapperinfo.c.in ] && cp wrapperinfo.c.in wrapperinfo.c

python3 - << 'PYEND'
import re

with open('wrapper_win.c', 'r') as f:
    content = f.read()

content = re.sub(r'__try\s*\{', '{', content)
content = re.sub(r'__finally\s*\{', '{', content)
content = re.sub(r'__leave;', '/* leave */;', content)

lines = content.split('\n')
output = []
i = 0
n = len(lines)

while i < n:
    line = lines[i]

    if '} __except' in line:
        brace_count = 1
        j = i + 1
        while j < n and brace_count > 0:
            brace_count += lines[j].count('{') - lines[j].count('}')
            j += 1
        if j < n and '} __finally' in lines[j]:
            brace_count2 = 1
            k = j + 1
            while k < n and brace_count2 > 0:
                brace_count2 += lines[k].count('{') - lines[k].count('}')
                k += 1
            output.append(line.split('}')[0] + '}')
            i = k
            continue
        output.append(line.split('}')[0] + '}')
        i = j
        continue

    if '} __finally' in line:
        brace_count = 1
        j = i + 1
        while j < n and brace_count > 0:
            brace_count += lines[j].count('{') - lines[j].count('}')
            j += 1
        output.append(line.split('}')[0] + '}')
        i = j
        continue

    output.append(line)
    i += 1

with open('wrapper_win.c', 'w') as f:
    f.write('\n'.join(output))

print('SEH patched')
PYEND

cat > Makefile-windows-x64.mingw << 'MAKEFILE'
CC = x86_64-w64-mingw32-gcc
WIN_FLAGS = -DWIN32 -D_UNICODE -DUNICODE -DHAVE_EADDRINUSE
LDFLAGS = -L/usr/x86_64-w64-mingw32/lib -lws2_32 -lshlwapi -ladvapi32 -luser32 -lcrypt32 -lwintrust -lpdh -lpsapi -lole32 -loleaut32 -lactiveds -ladsiid -lmpr -lshell32 -lnetapi32

OBJS = wrapper.o wrapperinfo.o wrappereventloop.o wrapper_jvm_launch.o wrapper_win.o property.o logger.o logger_file.o wrapper_file.o wrapper_i18n.o wrapper_hashmap.o wrapper_ulimit.o wrapper_encoding.o wrapper_jvminfo.o wrapper_secure_file.o wrapper_cipher.o wrapper_cipher_base.o wrapper_sysinfo.o

.PHONY: all clean

all: ../../src/bin/wrapper.exe

../../src/bin/wrapper.exe: $(OBJS)
	@mkdir -p ../../src/bin
	$(CC) -o $@ $(OBJS) $(WIN_FLAGS) $(LDFLAGS) -nostartfiles -e wmain

Wrapper.res: Wrapper.rc wrapper.ico
	$(CC) -DRC_INVOKED -o $@ -c $< $(WIN_FLAGS)

%.o: %.c
	$(CC) -c $< -o $@ $(WIN_FLAGS)

clean:
	rm -f $(OBJS) ../../src/bin/wrapper.exe

MAKEFILE

rm -f *.o ../../src/bin/wrapper.exe
make -f Makefile-windows-x64.mingw

if [ ! -f "${SRC_DIR}/src/bin/wrapper.exe" ]; then
    echo "Build failed"
    exit 1
fi

mkdir -p "${INSTALL_DIR}/${WIN_DIR}"
cp "${SRC_DIR}/src/bin/wrapper.exe" "${INSTALL_DIR}/${WIN_DIR}/I2Psvc.exe"
${HOST}-strip -s "${INSTALL_DIR}/${WIN_DIR}/I2Psvc.exe"

echo "Syncing wrapper.jar to win-all..."
if [ -f "${INSTALL_DIR}/all/wrapper.jar" ]; then
    cp "${INSTALL_DIR}/all/wrapper.jar" "${INSTALL_DIR}/win-all/wrapper.jar"
fi

echo ""
echo "=== Built ==="
ls -lh "${INSTALL_DIR}/${WIN_DIR}/"