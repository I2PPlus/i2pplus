#!/bin/sh
#
# Helper for parallel jbigi builds.
# Called by build-linux64.sh, build-win64.sh, build-arm64.sh via xargs.
#
# Env vars: SCRIPT_DIR, GMP_VER, JBIGI_DIR, OUTPUT_DIR, CC, HOST, SUFFIX,
#           BUILD_ROOT, INCLUDE_DIR, LIB_EXT, SONAME_FLAG, STRIP_CMD, OUTPUT_PREFIX
# Args: $1 = ENTRY (cpu_name|family|march)

ENTRY="$1"
CPU_NAME="${ENTRY%%|*}"
ENTRY="${ENTRY#*|}"
MARCH="${ENTRY##*|}"

CPU_GMP="$BUILD_ROOT/gmp-$CPU_NAME"
CPU_JBIGI="$BUILD_ROOT/jbigi-$CPU_NAME"
cp -a "$SCRIPT_DIR/gmp-${GMP_VER}" "$CPU_GMP"
cp -a "$JBIGI_DIR" "$CPU_JBIGI"

# Build GMP
if [ -n "$MARCH" ]; then
    export CFLAGS="-march=$MARCH -fPIC"
else
    export CFLAGS="-fPIC"
fi

cd "$CPU_GMP"
if [ -n "$HOST" ]; then
    CONFIGURE_HOST="--host=$HOST"
else
    CONFIGURE_HOST=""
fi

if ! ./configure $CONFIGURE_HOST --with-pic > /dev/null 2>&1 || ! make -j$(nproc) > /dev/null 2>&1; then
    echo "FAILED"
    exit 0
fi

# Build jbigi
cd "$CPU_JBIGI"
rm -f "libjbigi${LIB_EXT}" jbigi.o

if [ -n "$MARCH" ]; then
    MARCH_FLAG="-march=$MARCH"
else
    MARCH_FLAG=""
fi

# Convert colon-separated INCLUDE_DIR to -I flags
INCLUDE_FLAGS=$(echo "$INCLUDE_DIR" | sed 's/:/ -I/g; s/^/-I/')

if ! $CC -c -fPIC -Wall $MARCH_FLAG -I. -I./include $INCLUDE_FLAGS -I"$CPU_GMP" -o jbigi.o src/jbigi.c 2>/dev/null; then
    echo "FAILED"
    exit 0
fi

if ! $CC -shared -fPIC -Wall $MARCH_FLAG -I. -I./include $INCLUDE_FLAGS -I"$CPU_GMP" -o "libjbigi${LIB_EXT}" jbigi.o "$CPU_GMP/.libs/libgmp.a" $SONAME_FLAG 2>/dev/null; then
    echo "FAILED"
    exit 0
fi

cp "libjbigi${LIB_EXT}" "$OUTPUT_DIR/${OUTPUT_PREFIX}-${CPU_NAME}${SUFFIX}${LIB_EXT}"

if [ -n "$STRIP_CMD" ] && command -v "$STRIP_CMD" >/dev/null 2>&1; then
    "$STRIP_CMD" "$OUTPUT_DIR/${OUTPUT_PREFIX}-${CPU_NAME}${SUFFIX}${LIB_EXT}" 2>/dev/null || true
fi
