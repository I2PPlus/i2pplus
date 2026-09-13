#!/bin/sh

cd "$(dirname "$0")" || exit 1
rm -rf lib
mkdir -p lib/freenet/support/CPUInformation

[ -z "$CC_PREFIX" ] && CC_PREFIX=""
[ -z "$TARGET" ] && TARGET="$(uname -s)"
[ -z "$HOST" ] && HOST="$(uname -s | tr '[:upper:]' '[:lower:]')"

case "$TARGET" in
    MINGW*|CYGWIN*|windows*)
        echo "Building windows .dlls";;
    SunOS*)
        echo "Building solaris .sos";;
    Darwin*)
        echo "Building Darwin jnilibs";;
    Linux*|NetBSD*|OpenBSD*|*FreeBSD*)
        echo "Building $(uname -s |tr [A-Z] [a-z]) .sos";;
    *)
        echo "Unsupported build environment"
        exit 1;;
esac


if [ -z "$BITS" ]; then
  UNAME="$(uname -m)"
  if test "${UNAME#*x86_64}" != "$UNAME"; then
    BITS=64
  elif test "${UNAME#*aarch64}" != "$UNAME"; then
    BITS=64
  else

    echo "Unable to detect default setting for BITS variable (only 64-bit hosts are supported)"
    exit 1
  fi

  printf '..%s..' "BITS variable not set, $BITS bit system detected\n" >&2
fi


if [ -z "$CC" ]; then
  export CC="gcc"
  printf '..%s..' "CC variable not set, defaulting to $CC\n" >&2
fi


# Debian builds are presumed to be native, we don't need the -mxx flag unless cross-compile
if [ -z "$DEBIANVERSION" ] ; then
    export ABI=64
    export CFLAGS="-m64 -mtune=generic"
    export LDFLAGS="-m64"
fi

[ -z "$ARCH" ] && case $(uname -m) in
    x86_64*|amd64)
        ARCH="x86_64";;
    # Solaris x86
    i86pc)
        ARCH="x86_64";;
    *)
        echo "Unsupported build environment. jcpuid is only built for x86_64 systems."
        exit 0;;
esac


case "$TARGET" in
    MINGW*|CYGWIN*|windows*)
        [ -z "$JAVA_HOME" ] && JAVA_HOME="/c/software/j2sdk1.4.2_05"
        CFLAGS="${CFLAGS} -Wall"
        INCLUDES="-I. -Iinclude"
        WINDOWS_INCLUDES=-I"${JAVA_HOME}/include/"
        WINDOWS_INCLUDES_HOST=-I"${JAVA_HOME}/include/$HOST/"
        LDFLAGS="${LDFLAGS} -shared -static -static-libgcc -Wl,--kill-at"
        LIBFILE="lib/freenet/support/CPUInformation/jcpuid-${ARCH}-windows.dll";;
    Darwin*)
        JAVA_HOME=$(/usr/libexec/java_home)
        CFLAGS="${CFLAGS} -fPIC -Wall -arch x86_64"
        INCLUDES="-I. -Iinclude -I${JAVA_HOME}/include/ -I${JAVA_HOME}/include/darwin/"
        LDFLAGS="${LDFLAGS} -dynamiclib -framework JavaVM"
        LIBFILE="lib/freenet/support/CPUInformation/libjcpuid-x86_64-osx.jnilib";;
    Linux*|OpenBSD*|NetBSD*|*FreeBSD*|SunOS*)
        KFREEBSD=0
        UNIXTYPE="$(uname -s | tr [A-Z] [a-z])"
        if [ "${UNIXTYPE}" = "sunos" ]; then
            UNIXTYPE="solaris"
        elif [ "${UNIXTYPE}" = "gnu/kfreebsd" ]; then
            UNIXTYPE="linux"
            KFREEBSD=1
        fi
        # If JAVA_HOME isn't set, try to figure it out on our own
        [ -z "$JAVA_HOME" ] && . ../find-java-home
        # JAVA_HOME being set doesn't guarantee that it's usable
        if [ ! -f "$JAVA_HOME/include/jni.h" ]; then
            echo "Please ensure you have a Java SDK installed" >&2
            echo "and/or set JAVA_HOME then re-run this script." >&2
            exit 1
        fi

        # rename jcpuid, formerly 0003-rename-jcpuid.patch
        if [ "$DEBIANVERSION" ] ; then
            LDFLAGS="${LDFLAGS} -shared -Wl,-soname,libjcpuid.so"
            LIBFILE="../jbigi/libjcpuid.so"
        else
            LDFLAGS="${LDFLAGS} -shared -Wl,-soname,libjcpuid-${ARCH}-${UNIXTYPE}.so"
            if [ $KFREEBSD -eq 1 ]; then
                LIBFILE="lib/freenet/support/CPUInformation/libjcpuid-${ARCH}-kfreebsd.so"
            else
                LIBFILE="lib/freenet/support/CPUInformation/libjcpuid-${ARCH}-${UNIXTYPE}.so"
            fi
        fi
        CFLAGS="${CFLAGS} -fPIC -Wall"
        INCLUDES="-I. -Iinclude -I${JAVA_HOME}/include -I${JAVA_HOME}/include/${UNIXTYPE}";;
esac

echo "CC_PREFIX:$CC_PREFIX"
echo "TARGET:$TARGET"
echo "HOST:$HOST"
echo "ARCH:$ARCH"
echo "CFLAGS:$CFLAGS"
echo "LDFLAGS:$LDFLAGS"
echo "INCLUDES:$INCLUDES"

echo "Compiling C code..."
rm -f ${LIBFILE}
case "$TARGET" in
    MINGW*|CYGWIN*|windows*)
        "${CC_PREFIX}""${CC}" ${CFLAGS} ${LDFLAGS} ${INCLUDES} "${WINDOWS_INCLUDES}" "${WINDOWS_INCLUDES_HOST}" src/*.c -o "${LIBFILE}" || (echo "Failed to compile ${LIBFILE}"; exit 1)
        ;;
    *)
        "${CC_PREFIX}""${CC}" ${CFLAGS} ${LDFLAGS} ${INCLUDES} src/*.c -o "${LIBFILE}" || (echo "Failed to compile ${LIBFILE}"; exit 1)
        ;;
esac
"${CC_PREFIX}"strip "${LIBFILE}" || (echo "Failed to strip ${LIBFILE}" ; exit 1)
echo Built "$(dirname "$0")"/"${LIBFILE}"
