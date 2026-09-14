#!/bin/bash
# Build Tanuki Wrapper for Windows 64-bit
# Downloads source from SourceForge and cross-compiles using mingw-w64.
# The Windows exe needs MSVC-style SEH (__try/__except/__finally), which only
# clang supports on the win-gnu target, so clang is a build requirement too.
#
# Usage: build-wrapper-win64.sh [--version X.X.X]
# Requirements: mingw-w64 (gcc + windres), clang (for the SEH source file)

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
        --force) FORCE=1; shift ;;
        *) shift ;;
    esac
done

SRC_DATE="20260904"
case "$VERSION" in
    3.7.3) SRC_DATE="20260904" ;;
    3.7.0) SRC_DATE="20260716" ;;
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

if [ "${FORCE:-0}" -eq 1 ]; then
    true
elif [ -f "${WRAPPER_DIR}/win64/I2Psvc.exe" ] && [ -f "${WRAPPER_DIR}/win64/wrapper.dll" ] && [ -f "${WRAPPER_DIR}/all/wrapper.jar" ]; then
    jar_ver=$(unzip -p "${WRAPPER_DIR}/all/wrapper.jar" META-INF/MANIFEST.MF 2>/dev/null | grep "Implementation-Version" | cut -d' ' -f2 | tr -d '\r')
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
command -v ${HOST}-windres >/dev/null || { echo "Need ${HOST}-windres"; exit 1; }
command -v clang >/dev/null || { echo "Need clang (compiles the SEH source wrapper_win.c)"; exit 1; }

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
# Fix case-sensitive include paths for Linux cross-compilation
sed -i 's/<Ws2tcpip\.h>/<ws2tcpip.h>/g' wrapper.c
sed -i 's/<Sddl\.h>/<sddl.h>/g' wrapper.c wrapper_win.c wrapperjni_win.c
sed -i 's/<Fcntl\.h>/<fcntl.h>/g' logger.c wrapper_win.c
sed -i 's/<Softpub\.h>/<softpub.h>/g' wrapper_win.c
sed -i 's/<DbgHelp\.h>/<dbghelp.h>/g' wrapper_win.c
sed -i 's/"Winternl\.h"/"winternl.h"/g' wrapper.c
# Workaround for InterlockedOrAcquire not in mingw-w64
sed -i '48i// Workaround for missing InterlockedOrAcquire in mingw-w64\n#if !defined(InterlockedOrAcquire)\n#define InterlockedOrAcquire InterlockedOr\n#endif\n' wrapper_win.c
sed -i 's/ChainPara.dwUrlRetrievalTimeout = timeout;/\/\/ ChainPara.dwUrlRetrievalTimeout = timeout;/' wrapper_win.c
sed -i 's/ChainPara.RequestedIssuancePolicy = CertUsage;/\/\/ ChainPara.RequestedIssuancePolicy = CertUsage;/' wrapper_win.c
# 3.7.3+: cannot take address of cast rvalue in GetExitCodeProcess call
# Fix: &((DWORD)x) -> (LPDWORD)&x  (take address of the struct member, not the cast)
sed -i 's/&((DWORD)queryInfo->exitCode)/(LPDWORD)\&queryInfo->exitCode/g' wrapper_win.c
# Upstream quirk: wrapperjni.h declares JNU_SetByteArrayRegion extern, the
# .c defines it static; gcc rejects the mismatch (MSVC tolerates it)
sed -i 's/^static void JNU_SetByteArrayRegion(/void JNU_SetByteArrayRegion(/' wrapperjni_exception.c
cp wrapperinfo.c.in wrapperinfo.c
# Substitute the version tokens Tanuki's Ant build would normally fill in
# (without this, wrapperinfo reports literal "@version@" strings)
# Tanuki default.properties: version.base = version.root = "3.7.3" (full version)
# NOTE: version.root is compared against wrapper.jar's wrapper.version property;
# using just the middle field (e.g. "7" from "3.7.3") causes a FATAL mismatch.
VERSION_SPEC=$(echo "${VERSION}" | cut -d. -f1-2)
sed -i "s/@version.base@/${VERSION}/g; s/@version.root@/${VERSION}/g; s/@version@/${VERSION}/g; s/@version.spec@/${VERSION_SPEC}/g; s/@bits@/64/g; s/@dist.arch@/x86_64/g; s/@dist.os@/win32/g; s/@build.date@/$(date +%Y%m%d)/g; s/@build.time@/$(date +%H%M)/g; s/@javac.target.version@/1.8/g" wrapperinfo.c

# Comma-separated version fields for the RC FILEVERSION/PRODUCTVERSION
RC_VERSION_BASE=$(echo "${VERSION}" | cut -d. -f1)
RC_VERSION_ROOT=$(echo "${VERSION}" | cut -d. -f2)
RC_VERSION_PATCH=$(echo "${VERSION}" | cut -d. -f3)
[ -n "${RC_VERSION_PATCH}" ] || RC_VERSION_PATCH="0"

# Keep the MSVC SEH (__try/__except/__finally) intact in wrapper_win.c — it is
# the abnormal-termination handler for the SCM control paths. mingw gcc cannot
# parse SEH, so that single file is compiled with clang (SEH-aware on the
# win-gnu target) by the makefile below instead of being stripped.

# Entry shim: the wrapper's entry point is wmain() (wide-char; the source
# spells it _tmain which is #defined to wmain under -D_UNICODE). The mingw-w64
# GCC runtime (win32-threads) has no wmainCRTStartup, and "-nostartfiles -e
# wmain" entered _tmain with garbage argc/argv from the loader (intermittent
# 0xC0000005). Link the stock mainCRTStartup and marshal the real command line
# here with CommandLineToArgvW instead.
cat > wrapper-entry-shim.c << 'SHIM'
#include <windows.h>

extern void wmain(int argc, wchar_t **argv);

int main(void) {
    int argc;
    wchar_t **argv;
    static wchar_t *fallback[] = { L"", NULL };
    argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    if (argv == NULL) {
        argc = 1;
        argv = fallback;
    }
    wmain(argc, argv);
    if (argv != fallback)
        LocalFree(argv);
    return 0;
}
SHIM

# Version/icon resource (wine/Windows "File version" dialog, WER crash
# attribution). The upstream Wrapper.rc needs mc.exe for its message table
# (MSG00001.bin), so a focused equivalent is generated here instead.
# (Unquoted RC delimiter so the ${VERSION} tokens are expanded.)
cat > wrapper-version.rc << RC
#include <windows.h>

IDI_WRAPPER             ICON                    "wrapper.ico"

VS_VERSION_INFO VERSIONINFO
 FILEVERSION     ${RC_VERSION_BASE},${RC_VERSION_ROOT},${RC_VERSION_PATCH},0
 PRODUCTVERSION  ${RC_VERSION_BASE},${RC_VERSION_ROOT},${RC_VERSION_PATCH},0
 FILEFLAGSMASK   0x3fL
 FILEFLAGS       0x0L
 FILEOS          0x40004L
 FILETYPE        0x1L
 FILESUBTYPE     0x0L
 BEGIN
     BLOCK "StringFileInfo"
     BEGIN
         BLOCK "040904b0"
         BEGIN
             VALUE "CompanyName", "Tanuki Software, Ltd."
             VALUE "FileDescription", "Java Service Wrapper daemon"
             VALUE "FileVersion", "${VERSION}"
             VALUE "InternalName", "I2Psvc"
             VALUE "OriginalFilename", "I2Psvc.exe"
             VALUE "ProductName", "I2P"
             VALUE "ProductVersion", "${VERSION}"
         END
     END
     BLOCK "VarFileInfo"
     BEGIN
         VALUE "Translation", 0x409, 1200
     END
 END
RC

cat > Makefile-windows-x64.mingw << 'MAKEFILE'
CC = x86_64-w64-mingw32-gcc
CC_SEH = clang --target=x86_64-w64-windows-gnu -fms-extensions
RC = x86_64-w64-mingw32-windres
WIN_FLAGS = -O2 -DWIN32 -DWIN64 -DNDEBUG -D_UNICODE -DUNICODE -D_WIN32_WINNT=0x0601 -DHAVE_EADDRINUSE
LDFLAGS = -lws2_32 -lshlwapi -ladvapi32 -luser32 -lcrypt32 -lwintrust -lpdh -lpsapi -lole32 -loleaut32 -lactiveds -ladsiid -lmpr -lshell32 -lnetapi32 -lbcrypt -lntdll -lwtsapi32 -lwevtapi -ldbghelp -liphlpapi

OBJS = wrapper.o wrapperinfo.o wrappereventloop.o wrapper_jvm_launch.o \
       property.o logger.o logger_file.o wrapper_file.o wrapper_i18n.o wrapper_hashmap.o \
       wrapper_ulimit.o wrapper_encoding.o wrapper_jvminfo.o wrapper_secure_file.o \
       wrapper_cipher.o wrapper_cipher_base.o wrapper_sysinfo.o wrapper_backend_base.o \
       wrapper_integration.o wrapper-entry-shim.o wrapper_win.o

.PHONY: all clean

all: ../../src/bin/wrapper.exe

../../src/bin/wrapper.exe: $(OBJS) Wrapper.res
	@mkdir -p ../../src/bin
	$(CC) -o $@ $(OBJS) Wrapper.res $(WIN_FLAGS) $(LDFLAGS)

# wrapper_win.c uses MSVC SEH (__try/__except/__finally); only clang supports
# SEH on the win-gnu target, so this object is built with clang while the rest
# of the exe uses gcc.
wrapper_win.o: wrapper_win.c
	$(CC_SEH) $(WIN_FLAGS) -c $< -o $@

Wrapper.res: wrapper-version.rc wrapper.ico
	$(RC) --include-dir=. wrapper-version.rc -O coff -o $@

%.o: %.c
	$(CC) -c $< -o $@ $(WIN_FLAGS)

clean:
	rm -f $(OBJS) wrapper-entry-shim.c wrapper-version.rc Wrapper.res ../../src/bin/wrapper.exe

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

echo "=== Building Wrapper ${VERSION} JNI DLL for Windows x64 ==="
# The JNI DLL (wrapper.dll) is loaded by the JVM via WrapperManager
# (System.loadLibrary("wrapper")) for service integration (router console
# service page, i2pcontrol). Tanuki ships no 64-bit Windows binaries, so
# this is cross-compiled from the same source as the exe above.
# jni.h is platform-independent, but jni_md.h is tightly target-specific:
# the Linux one defines JNICALL as empty while Windows must declare it
# __stdcall, and typedefs differ (long is 32-bit on Win64). A canonical MSVC
# jni_md.h is vendored in jni-win64/ and used instead.
JNI_WIN_INC="${WRAPPER_DIR}/jni-win64"
if [ ! -f "${JNI_WIN_INC}/jni_md.h" ]; then
    echo "Missing vendored Windows jni_md.h: ${JNI_WIN_INC}/jni_md.h"
    exit 1
fi
JNI_INC=""
if [ -n "${JAVA_HOME}" ] && [ -f "${JAVA_HOME}/include/jni.h" ]; then
    JNI_INC="${JAVA_HOME}/include"
elif command -v java >/dev/null 2>&1; then
    JAVA_REAL="$(readlink -f "$(command -v java)")"
    JNI_INC="$(dirname "$(dirname "${JAVA_REAL}")")/include"
fi
if [ -z "${JNI_INC}" ] || [ ! -f "${JNI_INC}/jni.h" ]; then
    echo "Need JDK headers (jni.h) to build wrapper.dll; set JAVA_HOME"
    exit 1
fi

# Object list mirrors Makefile-windows-x86-32.nmake (DLL_OBJS) from the 3.7.0 source
#
# CRITICAL: The DLL is loaded into javaw.exe (Microsoft OpenJDK 25, which uses
# ucrtbase.dll).  The default mingw-w64 link uses dllcrt2.o + msvcrt.dll
# (legacy CRT).  When loaded into a ucrtbase.dll process, two incompatible
# CRTs coexist — _initterm / _lock/_unlock / __iob_func all resolve to the
# wrong CRT, causing NULL-function-pointer crashes (0xC0000005 at offset 0x0).
#
# Fix: Link entirely against ucrtbase.dll via -lucrt + -lucrtbase.
# -lucrt provides the CRT runtime symbols (__acrt_iob_func, strlen, malloc,
# etc.) that back onto ucrtbase.dll through the api-ms-win-crt-* shims.
# -nostartfiles skips dllcrt2.o (no _initterm, no _DllMainCRTStartup).
# We provide a minimal DllMainCRTStartup in wrapper-dllmain.c.
# The atexit() stub satisfies libmingwex.a's dtoa_lock cleanup reference.

cat > wrapper-dllmain.c << 'SHIM'
#include <windows.h>

/*
 * Minimal DLL entry point.  Bypasses the mingw-w64 CRT startup (dllcrt2.o)
 * entirely: no _initterm, no _lock/_unlock, no __iob_func — none of the
 * msvcrt.dll symbols that crash when javaw.exe (ucrtbase.dll) loads us.
 *
 * Standard C functions (malloc, printf, etc.) resolve via -lucrt to
 * ucrtbase.dll through the API-set shims (api-ms-win-crt-*), matching
 * javaw.exe's CRT.
 *
 * The atexit() stub below satisfies libmingwex.a references (dtoa_lock
 * cleanup).  In a DLL that never registers C++ static destructors, this
 * is a safe no-op.
 */

int atexit(void (*func)(void)) { (void)func; return 0; }

BOOL WINAPI DllMainCRTStartup(HINSTANCE hinstDLL, DWORD fdwReason, LPVOID lpvReserved) {
    (void)hinstDLL; (void)lpvReserved;
    if (fdwReason == DLL_PROCESS_DETACH) return TRUE;
    return TRUE;
}
SHIM

cat > Makefile-windows-x64-dll.mingw << 'MAKEFILE'
CC = x86_64-w64-mingw32-gcc
DLL_FLAGS = -O2 -DWIN32 -DWIN64 -DNDEBUG -D_UNICODE -DUNICODE -D_WINDOWS -D_USRDLL -DDECODERJNI_VC8_EXPORTS -D_WINDLL -D_WIN32_WINNT=0x0601
# Link order matters: --start-group/--end-group resolves circular deps between
# libucrt.a, libucrtbase.a, and libmingwex.a.  libucrt.a provides CRT symbols
# backed by ucrtbase.dll; libucrtbase.a adds ucrt-specific I/O; libmingwex.a
# provides POSIX/Win32 glue.  All three are needed, and libmingwex.a has
# references to libucrt.a symbols (and vice versa) that won't resolve without
# the group.
DLL_LIBS = -nostartfiles -Wl,--start-group -lucrt -lucrtbase -lmingw32 -lmingwex -lgcc -lgcc_s -Wl,--end-group -lws2_32 -lwsock32 -lshlwapi -ladvapi32 -luser32 -lshell32 -liphlpapi -lcrypt32 -lwintrust -lpsapi -lole32 -loleaut32 -lmpr -lnetapi32 -lbcrypt -lntdll -ldbghelp

OBJS = wrapper_i18n.o wrapperjni_win.o wrapperjni_debug.o wrapperjni_exception.o \
       wrapperjni_utils.o wrapperinfo.o wrapperjni.o loggerjni.o wrapper_backend_base.o \
       wrapper-dllmain.o

.PHONY: all clean

all: wrapper.dll

wrapper.dll: $(OBJS)
	$(CC) -shared -o $@ $(OBJS) $(DLL_FLAGS) -I"$(JNI_INC)" -I"$(JNI_WIN_INC)" $(DLL_LIBS)

%.o: %.c
	$(CC) -c $< -o $@ $(DLL_FLAGS) -I"$(JNI_INC)" -I"$(JNI_WIN_INC)"

clean:
	rm -f $(OBJS) wrapper-dllmain.c wrapper.dll
MAKEFILE

make -f Makefile-windows-x64-dll.mingw JNI_INC="${JNI_INC}" JNI_WIN_INC="${JNI_WIN_INC}"

if [ ! -f wrapper.dll ]; then
    echo "DLL build failed"
    exit 1
fi
cp wrapper.dll "${INSTALL_DIR}/${WIN_DIR}/wrapper.dll"
${HOST}-strip -s "${INSTALL_DIR}/${WIN_DIR}/wrapper.dll"

echo ""
echo "=== Built ==="
ls -lh "${INSTALL_DIR}/${WIN_DIR}/"