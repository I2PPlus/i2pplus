#!/bin/sh
#
# Build JBigI native library for Linux 64-bit (x86_64)
# Detects supported CPU targets and builds optimized .so files.
#
# Usage:
#   cd core/c/jbigi
#   ./build-linux64.sh          # Show help
#   ./build-linux64.sh -a       # Build all supported CPU targets
#   ./build-linux64.sh -a -j 4  # Build with 4 parallel jobs
#   ./build-linux64.sh -g       # Generic build only
#

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
GMP_VER=6.3.0
GMP_DIR="$SCRIPT_DIR/gmp-${GMP_VER}"
JBIGI_DIR="$SCRIPT_DIR/jbigi"
OUTPUT_DIR="$SCRIPT_DIR/lib/net/i2p/util"
CC="${CC:-gcc}"
SUFFIX="_64"
STRIP="strip"
MODE=""

# All CPU targets: name|family|march flag
ALL_TARGETS="
zen3|amd|znver3
zen2|amd|znver2
zen|amd|znver1
skylake|intel|skylake
coreisbr|intel|corei7-avx
coreihwl|intel|core-avx2
coreibwl|intel|broadwell
core2|intel|core2
k10|amd|amdfam10
bulldozer|amd|bdver1
piledriver|amd|bdver2
steamroller|amd|bdver3
excavator|amd|bdver4
bobcat|amd|btver1
jaguar|amd|btver2
silvermont|intel|slm
goldmont|intel|goldmont
atom|intel|atom
athlon64|amd|k8-sse3
none|generic|
"

show_help() {
    cat <<EOF
JBigI Linux 64-bit Build

Compiles optimized libjbigi.so for Linux x86_64.

Usage:
  ./build-linux64.sh [OPTIONS]

Options:
  -a, --all         Build for all supported CPU targets
  -a -j N           Build with N parallel jobs (default: nproc)
  -g, --generic     Build generic .so only (faster)
  -h, --help        Show this help message

Examples:
  ./build-linux64.sh          # Show help
  ./build-linux64.sh -a       # Build all CPU targets
  ./build-linux64.sh -a -j 4  # Build with 4 parallel jobs
  ./build-linux64.sh --generic # Generic build only

Supported CPU targets:
  AMD:  zen3, zen2, zen, k10, bulldozer, piledriver, steamroller,
        excavator, bobcat, jaguar, athlon64
  Intel: skylake, coreisbr, coreihwl, coreibwl, core2,
         silvermont, goldmont, atom

Prerequisites:
  Debian/Ubuntu:  sudo apt-get install build-essential m4 openjdk-8-jdk
  Fedora/RHEL:    sudo dnf install gcc make m4 java-1.8.0-openjdk-devel
  Arch:           sudo pacman -S base-devel m4 jdk8-openjdk

Output:
  lib/net/i2p/util/libjbigi-linux-{cpu}_64.so
EOF
}

# Parse arguments
JOBS=""
while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help|"")
            show_help
            exit 0
            ;;
        -a|--all)
            MODE="all"
            ;;
        -g|--generic)
            MODE="generic"
            ;;
        -j)
            JOBS="$2"
            shift
            ;;
        *)
            echo "Unknown option: $1"
            echo "Run './build-linux64.sh --help' for usage."
            exit 1
            ;;
    esac
    shift
done

if [ -z "$MODE" ]; then
    show_help
    exit 0
fi

check_deps() {
    MISSING=""

    for TOOL in "$CC" "m4" "make"; do
        if ! command -v "$TOOL" >/dev/null 2>&1; then
            MISSING="$MISSING    $TOOL\n"
        fi
    done

    if [ -z "$MISSING" ]; then
        echo "All dependencies found."
        return 0
    fi

    echo ""
    echo "WARNING: Missing dependencies:"
    printf "$MISSING"
    echo ""

    if [ -f /etc/os-release ]; then
        . /etc/os-release
        case "$ID" in
            debian|ubuntu|linuxmint|pop)
                echo "Detected: Debian/Ubuntu"
                echo "  sudo apt-get install build-essential m4 openjdk-8-jdk"
                ;;
            fedora|rhel|centos|almalinux|rocky)
                echo "Detected: Fedora/RHEL"
                echo "  sudo dnf install gcc make m4 java-1.8.0-openjdk-devel"
                ;;
            arch|manjaro|endeavouros)
                echo "Detected: Arch"
                echo "  sudo pacman -S base-devel m4 jdk8-openjdk"
                ;;
            opensuse*|sles)
                echo "Detected: openSUSE"
                echo "  sudo zypper install gcc make m4 java-1_8_0-openjdk-devel"
                ;;
            gentoo)
                echo "Detected: Gentoo"
                echo "  sudo emerge --ask sys-devel/gcc m4 virtual/jdk:1.8"
                ;;
            *)
                echo "Install: gcc, make, m4, JDK 8 with JNI headers"
                ;;
        esac
    fi
    echo ""

    return 1
}

check_cpu_target() {
    MARCH="$1"

    if [ -z "$MARCH" ]; then
        return 0
    fi

    echo "int main(){return 0;}" > /tmp/cpu_test.c
    if $CC -march=$MARCH -c /tmp/cpu_test.c -o /tmp/cpu_test.o 2>/dev/null; then
        rm -f /tmp/cpu_test.c /tmp/cpu_test.o
        return 0
    else
        rm -f /tmp/cpu_test.c /tmp/cpu_test.o
        return 1
    fi
}

detect_targets() {
    SUPPORTED=""
    UNSUPPORTED=""
    SUPPORTED_COUNT=0

    for ENTRY in $ALL_TARGETS; do
        CPU_NAME="${ENTRY%%|*}"
        ENTRY="${ENTRY#*|}"
        FAMILY="${ENTRY%%|*}"
        MARCH="${ENTRY##*|}"

        if check_cpu_target "$MARCH"; then
            SUPPORTED="$SUPPORTED ${CPU_NAME}|${FAMILY}|${MARCH}"
            SUPPORTED_COUNT=$((SUPPORTED_COUNT + 1))
        else
            UNSUPPORTED="$UNSUPPORTED $CPU_NAME"
        fi
    done
}

build_gmp() {
    MARCH="$1"
    GMP_BUILD="${2:-$GMP_DIR}"
    if [ -n "$MARCH" ]; then
        export CFLAGS="-march=$MARCH -fPIC"
    else
        export CFLAGS="-fPIC"
    fi
    cd "$GMP_BUILD"
    ./configure --with-pic > /dev/null 2>&1
    make -j$(nproc) > /dev/null 2>&1
}

build_jbigi() {
    CPU_NAME="$1"
    MARCH="$2"
    GMP_BUILD="${3:-$GMP_DIR}"

    cd "$JBIGI_DIR"
    rm -f libjbigi.so jbigi.o

    if [ -n "$MARCH" ]; then
        MARCH_FLAG="-march=$MARCH"
    else
        MARCH_FLAG=""
    fi

    $CC -c -fPIC -Wall $MARCH_FLAG -I. -I./include -I./include/linux-x86_64 -I"$GMP_BUILD" -o jbigi.o src/jbigi.c
    $CC -shared -fPIC -Wall $MARCH_FLAG -I. -I./include -I./include/linux-x86_64 -I"$GMP_BUILD" -o libjbigi.so jbigi.o "$GMP_BUILD/.libs/libgmp.a" -Wl,-soname,libjbigi.so

    cp libjbigi.so "$OUTPUT_DIR/libjbigi-linux-${CPU_NAME}${SUFFIX}.so"

    if command -v $STRIP >/dev/null 2>&1; then
        $STRIP "$OUTPUT_DIR/libjbigi-linux-${CPU_NAME}${SUFFIX}.so"
    fi
}

echo "=== JBigI Linux 64-bit Build ==="
echo ""

# Check dependencies
if ! check_deps; then
    echo "Aborting. Install missing dependencies and re-run."
    exit 1
fi

set -e

# Download and extract GMP if needed
if [ ! -d "$GMP_DIR" ]; then
    . "$SCRIPT_DIR/download_gmp.sh"
fi

# Detect supported CPU targets
echo "Detecting supported CPU targets..."
detect_targets

echo ""
echo "Supported targets ($SUPPORTED_COUNT):"
for ENTRY in $SUPPORTED; do
    CPU_NAME="${ENTRY%%|*}"
    ENTRY="${ENTRY#*|}"
    FAMILY="${ENTRY%%|*}"
    MARCH="${ENTRY##*|}"
    if [ -n "$MARCH" ]; then
        printf "  %-15s %s (-march=%s)\n" "$CPU_NAME" "$FAMILY" "$MARCH"
    else
        printf "  %-15s %s (generic)\n" "$CPU_NAME" "$FAMILY"
    fi
done

if [ -n "$UNSUPPORTED" ]; then
    echo ""
    echo "Unsupported (GCC doesn't recognize -march):"
    for CPU_NAME in $UNSUPPORTED; do
        echo "  $CPU_NAME"
    done
fi

echo ""
mkdir -p "$OUTPUT_DIR"

if [ "$MODE" = "generic" ]; then
    rm -f "$OUTPUT_DIR"/*linux*"$SUFFIX"*.so
    echo "Building generic libjbigi-linux-none${SUFFIX}.so..."
    build_gmp ""
    build_jbigi "none" ""
    echo ""
    echo "Done: $OUTPUT_DIR/libjbigi-linux-none${SUFFIX}.so"
    file "$OUTPUT_DIR/libjbigi-linux-none${SUFFIX}.so"
    exit 0
fi

# Build for all supported CPU targets
BUILD_ROOT="/tmp/jbigi-build-$$"
MAX_JOBS="${JOBS:-$(nproc)}"
if [ "$MAX_JOBS" -gt 1 ]; then MAX_JOBS=$((MAX_JOBS - 1)); fi

rm -f "$OUTPUT_DIR"/*linux*"$SUFFIX"*.so

cleanup() { rm -rf "$BUILD_ROOT"; }
trap cleanup EXIT

mkdir -p "$BUILD_ROOT" "$OUTPUT_DIR"

echo "Building ${SUFFIX#_}-bit Linux .so files (jobs: $MAX_JOBS)..."
echo ""

export SCRIPT_DIR GMP_VER JBIGI_DIR OUTPUT_DIR CC SUFFIX BUILD_ROOT
export INCLUDE_DIR="./include/linux-x86_64" LIB_EXT=".so"
export SONAME_FLAG="-Wl,-soname,libjbigi.so" OUTPUT_PREFIX="libjbigi-linux"
export STRIP_CMD="$STRIP" HOST=""

echo "$SUPPORTED" | tr ' ' '\n' | grep -v '^$' | \
    xargs -P "$MAX_JOBS" -I {} "$SCRIPT_DIR/build_one.sh" {}

"$SCRIPT_DIR/list-jbigi.sh"
