#!/bin/bash
# Cross-compile Tanuki Java Service Wrapper for Windows using mingw-w64
#
# Usage:
#   bash /path/to/build-wrapper-windows.sh /path/to/src [--clean] [--arch x64|win32] [--install-dir PATH]
#
# Requirements:
#   sudo apt install mingw-w64 binutils-mingw-w64-x86-64 gcc-mingw-w64-x86-64 git
#   For win32: also i686-w64-mingw32-gcc (install gcc-mingw-w64-i686)

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_SRC=""
CLEAN=false
ARCH="x64"
INSTALL_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)/installer"
JNI_HEADERS_DIR="/tmp/mingw-jni-headers"
BUILD_DLL=true

while [[ $# -gt 0 ]]; do
    case "$1" in
        --clean) CLEAN=true; shift ;;
        --no-dll) BUILD_DLL=false; shift ;;
        --arch) ARCH="$2"; shift 2 ;;
        --install-dir) INSTALL_DIR="$2"; shift 2 ;;
        --help|-h)
            echo "Usage: $0 /path/to/src [--clean] [--arch x64|win32] [--install-dir PATH] [--no-dll]"
            echo ""
            echo "Options:"
            echo "  --arch           Target architecture: x64 or win32 (default: x64)"
            echo "  --install-dir    Path to i2pplus installer directory (default: ../installer from script)"
            echo "  --no-dll         Skip building the JNI DLL (default: build both)"
            echo "  --clean          Clean build artifacts and patches"
            exit 0 ;;
        --*) echo "Unknown: $1"; exit 1 ;;
        *) WRAPPER_SRC="$1"; shift ;;
    esac
done

[ -z "$WRAPPER_SRC" ] && { echo "Usage: $0 /path/to/src [--clean] [--arch x64|win32] [--install-dir PATH] [--no-dll]"; exit 1; }

case "$ARCH" in
    x64)
        HOST="x86_64-w64-mingw32"
        WIN_DIR="win64"
        ;;
    win32)
        HOST="i686-w64-mingw32"
        WIN_DIR="win32"
        ;;
    *) echo "Unknown arch: $ARCH (use x64 or win32)"; exit 1 ;;
esac

SRC_DIR="$(cd "$WRAPPER_SRC" && pwd)"
C_SRC_DIR="${SRC_DIR}/src/c"

echo "=== Tanuki Wrapper Windows ${ARCH} Build ==="
command -v ${HOST}-gcc >/dev/null || { echo "Need ${HOST}-gcc"; exit 1; }
echo "Source: ${C_SRC_DIR}"
echo "Install: ${INSTALL_DIR}"

if $CLEAN; then
    cd "${C_SRC_DIR}"
    rm -f *.o ../src/bin/wrapper.exe ../src/bin/wrapper.dll
    rm -f Makefile-windows-${ARCH}.mingw Makefile-dll-windows-${ARCH}.mingw
    [ -f wrapper_win.c.orig ] && mv wrapper_win.c.orig wrapper_win.c
    [ -f wrapper.c.orig ] && mv wrapper.c.orig wrapper.c
    rm -rf "${JNI_HEADERS_DIR}"
    echo "Cleaned"
    exit 0
fi

# Download JNI headers for cross-compilation
download_jni_headers() {
    if [ -d "${JNI_HEADERS_DIR}/include/jni.h" ]; then
        echo "JNI headers already present"
        return 0
    fi
    
    echo "Downloading JNI headers..."
    mkdir -p "${JNI_HEADERS_DIR}"
    
    if command -v git >/dev/null 2>&1; then
        git clone --depth=1 https://github.com/Casterlabs/jni-headers.git "${JNI_HEADERS_DIR}/src" 2>/dev/null || {
            echo "Git clone failed, trying wget..."
            cd "${JNI_HEADERS_DIR}"
            wget -q -O jni-headers.zip https://github.com/Casterlabs/jni-headers/archive/refs/heads/main.zip 2>/dev/null || \
            curl -sL https://github.com/Casterlabs/jni-headers/archive/refs/heads/main.zip -o jni-headers.zip
            unzip -q jni-headers.zip
            mv jni-headers-main/* .
            rm -rf jni-headers.zip jni-headers-main
        }
    else
        echo "Git not found, downloading via curl..."
        cd "${JNI_HEADERS_DIR}"
        curl -sL https://github.com/Casterlabs/jni-headers/archive/refs/heads/main.zip -o jni-headers.zip
        unzip -q jni-headers.zip
        mv jni-headers-main/* .
        rm -rf jni-headers.zip jni-headers-main
    fi
    
    # Use Java 21 headers (most compatible)
    if [ -d "${JNI_HEADERS_DIR}/src/versions/21" ]; then
        cp -r "${JNI_HEADERS_DIR}/src/versions/21/include/"* "${JNI_HEADERS_DIR}/"
    elif [ -d "${JNI_HEADERS_DIR}/src/versions/17" ]; then
        cp -r "${JNI_HEADERS_DIR}/src/versions/17/include/"* "${JNI_HEADERS_DIR}/"
    elif [ -d "${JNI_HEADERS_DIR}/src/versions/8" ]; then
        cp -r "${JNI_HEADERS_DIR}/src/versions/8/include/"* "${JNI_HEADERS_DIR}/"
    fi
    
    # For Windows cross-compilation, copy win32 headers to root (jni_md.h location)
    if [ -d "${JNI_HEADERS_DIR}/win32" ]; then
        cp "${JNI_HEADERS_DIR}/win32/"*.h "${JNI_HEADERS_DIR}/" 2>/dev/null || true
    fi
    
    rm -rf "${JNI_HEADERS_DIR}/src"
    echo "JNI headers ready at ${JNI_HEADERS_DIR}"
}

echo "Applying patches..."
cd "${C_SRC_DIR}"

cp wrapper_win.c wrapper_win.c.orig 2>/dev/null || true
cp wrapper.c wrapper.c.orig 2>/dev/null || true

sed -i 's/#include <errno.h>/#include <errno.h>\ntypedef int socklen_t;/' wrapper.c wrapper_win.c
sed -i 's/const char\* inet_ntop(/const char* wrapper_inet_ntop(/g' wrapper.c
sed -i 's/const char\* inet_pton(/const char* wrapper_inet_pton(/g' wrapper.c
sed -i 's/Iphlpapi\.h/iphlpapi.h/g' wrapperjni_win.c
[ ! -f wrapperinfo.c ] && [ -f wrapperinfo.c.in ] && cp wrapperinfo.c.in wrapperinfo.c

# SEH patching
python3 - << 'PYEND'
import sys

try:
    with open('wrapper_win.c', 'r') as f:
        content = f.read()
except FileNotFoundError:
    print("wrapper_win.c not found")
    sys.exit(1)

lines = content.split('\n')
output = []
state = 'NORMAL'
except_depth = 0
finally_depth = 0

for line in lines:
    stripped = line.strip()
    
    if '__try {' in stripped:
        output.append(line.replace('__try {', '{'))
        continue
    
    if '} __except' in stripped:
        idx = stripped.index('}')
        output.append(line[:idx+1] + ' /* except removed */')
        state = 'IN_EXCEPT_BLOCK'
        rest = stripped[idx+1:]
        except_depth = rest.count('{') - rest.count('}')
        continue
    
    if '__except' in stripped and '} __except' not in stripped:
        output.append('/* except removed */')
        state = 'IN_EXCEPT_BLOCK'
        except_depth = stripped.count('{') - stripped.count('}')
        continue
    
    if '} __finally' in stripped:
        idx = stripped.index('}')
        output.append(line[:idx+1] + ' /* finally removed */')
        state = 'IN_FINALLY_BLOCK'
        rest = stripped[idx+1:]
        finally_depth = rest.count('{') - rest.count('}')
        continue
    
    if '__finally {' in stripped:
        output.append(line.replace('__finally {', '{'))
        state = 'IN_FINALLY_BLOCK'
        finally_depth = stripped.count('{') - stripped.count('}')
        continue
    
    line = line.replace('__leave;', '/* __leave */;')
    
    if state == 'IN_EXCEPT_BLOCK':
        except_depth += stripped.count('{') - stripped.count('}')
        if except_depth <= 0:
            state = 'NORMAL'
        continue
    
    if state == 'IN_FINALLY_BLOCK':
        finally_depth += stripped.count('{') - stripped.count('}')
        if finally_depth <= 0:
            state = 'NORMAL'
        continue
    
    output.append(line)

with open('wrapper_win.c', 'w') as f:
    f.write('\n'.join(output))
print('SEH patched')
PYEND

# Makefile for wrapper.exe
cat > Makefile-windows-${ARCH}.mingw << 'MAKEFILE_END'
CC = CC_PLACEHOLDER
STRIP = STRIP_PLACEHOLDER
WIN_FLAGS = -DWIN32 -D_UNICODE -DUNICODE -DHAVE_EADDRINUSE
LDFLAGS = -L/usr/HOST_PLACEHOLDER/lib -lws2_32 -lshlwapi -ladvapi32 -luser32 -lcrypt32 -lwintrust -lpdh -lpsapi -lole32 -loleaut32 -lactiveds -ladsiid -lmpr -lshell32 -lnetapi32

WRAPPER_SRCS = wrapper.c wrapperinfo.c wrappereventloop.c wrapper_jvm_launch.c wrapper_win.c property.c logger.c logger_file.c wrapper_file.c wrapper_i18n.c wrapper_hashmap.c wrapper_ulimit.c wrapper_encoding.c wrapper_jvminfo.c wrapper_secure_file.c wrapper_cipher.c wrapper_cipher_base.c wrapper_sysinfo.c
WRAPPER_OBJ = $(WRAPPER_SRCS:.c=.o)

.PHONY: all wrapper strip

all: init wrapper

init:
	@mkdir -p ../src/bin

wrapper: $(WRAPPER_OBJ)
	$(CC) -o ../src/bin/wrapper.exe $(WRAPPER_OBJ) $(WIN_FLAGS) $(LDFLAGS) -nostartfiles -e wmain

strip:
	$(STRIP) -s ../src/bin/wrapper.exe

install: ../src/bin/wrapper.exe
	cp ../src/bin/wrapper.exe INSTALLDIR/lib/wrapper/WINDIR/I2Psvc.exe
	$(STRIP) -s INSTALLDIR/lib/wrapper/WINDIR/I2Psvc.exe

%.o: %.c wrapper.h
	$(CC) $(WIN_FLAGS) -Wno-incompatible-pointer-types -Wno-unused-but-set-variable -Wno-pointer-to-int-cast -c $< -o $@

clean:
	rm -f $(WRAPPER_OBJ) ../src/bin/wrapper.exe
MAKEFILE_END

sed -i "s|CC_PLACEHOLDER|${HOST}-gcc|g; s|STRIP_PLACEHOLDER|${HOST}-strip|g; s|HOST_PLACEHOLDER|${HOST}|g; s|INSTALLDIR|${INSTALL_DIR}|g; s|WINDIR|${WIN_DIR}|g" Makefile-windows-${ARCH}.mingw

echo "Building wrapper.exe..."
make -f Makefile-windows-${ARCH}.mingw

if [ ! -f "../src/bin/wrapper.exe" ]; then
    echo "Build failed - wrapper.exe not created"
    exit 1
fi
echo "wrapper.exe built successfully"

# Build DLL if requested
if $BUILD_DLL; then
    echo "Building wrapper.dll..."
    
    # Download JNI headers if needed
    download_jni_headers
    
    # Makefile for wrapper.dll (JNI)
    cat > Makefile-dll-windows-${ARCH}.mingw << 'DLL_MAKEFILE'
CC = CC_PLACEHOLDER
STRIP = STRIP_PLACEHOLDER
WIN_FLAGS = -DWIN32 -D_UNICODE -DUNICODE -DHAVE_EADDRINUSE -DJNIFONTCACHE
JNI_INCLUDE = -IJNIHEADERS
LDFLAGS = -L/usr/HOST_PLACEHOLDER/lib -lws2_32 -lshlwapi -ladvapi32 -luser32 -liphlpapi -lkernel32 -lnetapi32 -lshell32 -lole32 -loleaut32

DLL_SRCS = wrapperjni.c wrapperjni_win.c wrapperinfo.c loggerjni.c wrapper_i18n.c
DLL_OBJ = $(DLL_SRCS:.c=.o)

.PHONY: all dll strip

all: dll

dll: $(DLL_OBJ)
	$(CC) -shared -o ../src/bin/wrapper.dll $(DLL_OBJ) $(WIN_FLAGS) $(JNI_INCLUDE) $(LDFLAGS)

strip:
	$(STRIP) -s ../src/bin/wrapper.dll

install: ../src/bin/wrapper.dll
	cp ../src/bin/wrapper.dll INSTALLDIR/lib/wrapper/WINDIR/wrapper.dll
	$(STRIP) -s INSTALLDIR/lib/wrapper/WINDIR/wrapper.dll

%.o: %.c wrapperjni.h wrapperinfo.h loggerjni.h
	$(CC) $(WIN_FLAGS) $(JNI_INCLUDE) -Wno-incompatible-pointer-types -Wno-unused-but-set-variable -Wno-pointer-to-int-cast -Wno-int-conversion -c $< -o $@

clean:
	rm -f $(DLL_OBJ) ../src/bin/wrapper.dll
DLL_MAKEFILE

    sed -i "s|CC_PLACEHOLDER|${HOST}-gcc|g; s|STRIP_PLACEHOLDER|${HOST}-strip|g; s|HOST_PLACEHOLDER|${HOST}|g; s|INSTALLDIR|${INSTALL_DIR}|g; s|WINDIR|${WIN_DIR}|g; s|JNIHEADERS|${JNI_HEADERS_DIR}|g" Makefile-dll-windows-${ARCH}.mingw
    
    make -f Makefile-dll-windows-${ARCH}.mingw
    
    if [ ! -f "../src/bin/wrapper.dll" ]; then
        echo "DLL build failed - wrapper.dll not created"
        echo "This is expected if JNI headers are incomplete"
        BUILD_DLL=false
    else
        echo "wrapper.dll built successfully"
    fi
fi

echo "Installing to ${INSTALL_DIR}/lib/wrapper/${WIN_DIR}/..."
mkdir -p "${INSTALL_DIR}/lib/wrapper/${WIN_DIR}"

make -f Makefile-windows-${ARCH}.mingw install INSTALL_DIR="${INSTALL_DIR}" 2>/dev/null || true

if $BUILD_DLL && [ -f "../src/bin/wrapper.dll" ]; then
    make -f Makefile-dll-windows-${ARCH}.mingw install INSTALL_DIR="${INSTALL_DIR}" 2>/dev/null || \
    cp ../src/bin/wrapper.dll "${INSTALL_DIR}/lib/wrapper/${WIN_DIR}/wrapper.dll"
fi

echo ""
echo "=== Build Complete ==="
ls -lh "${INSTALL_DIR}/lib/wrapper/${WIN_DIR}/"
