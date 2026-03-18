#!/bin/sh
#
# JBIGI Build Script
# 
# Usage:
#   ./build.sh              - Show this help
#   ./build.sh local        - Build for current machine only
#   ./build.sh all         - Build all architectures for current OS

# Help output
if [ -z "$1" ] || [ "$1" = "help" ] || [ "$1" = "--help" ] || [ "$1" = "-h" ]; then
    echo "JBIGI Build Script"
    echo ""
    echo "Usage:"
    echo "  ./build.sh              Show this help"
    echo "  ./build.sh local        Build for current machine only"
    echo "  ./build.sh local dyn    Build dynamically (requires system GMP)"
    echo "  ./build.sh all         Build all architectures for current OS"
    echo "  ./build.sh all --dry-run  Preview what would be built"
    echo ""
    echo "Environment variables:"
    echo "  BITS=32|64     - Force 32 or 64 bit build"
    echo "  TARGET=os      - Target OS (linux, windows, freebsd, etc)"
    echo "  CC=gcc         - C compiler"
    echo "  JAVA_HOME      - Path to JDK with JNI"
    echo ""
    echo "Examples:"
    echo "  ./build.sh local                    # Build for current machine"
    echo "  ./build.sh local dyn                # Build dynamically"
    echo "  ./build.sh all --dry-run            # Preview all builds"
    echo "  BITS=32 ./build.sh local            # Build 32-bit"
    echo "  TARGET=mingw64 CC=x86_64-w64-mingw32-gcc BITS=64 ./build.sh all  # Cross-compile Windows"
    exit 0
fi

# Source gmp version variables and download gmp.
. ./download_gmp.sh

# Check for m4
if [ ! $(which m4) ]; then
    echo "ERROR: m4 not found. Install m4 and re-run." >&2
    exit 1
fi

# Determine JAVA_HOME if not set.
if [ -z "$JAVA_HOME" ]; then
    if [ -f "../find-java-home" ]; then
        . ../find-java-home
    fi
fi

# Check for jni.h presence.
if [ ! -f "$JAVA_HOME/include/jni.h" ]; then
    echo "ERROR: Cannot find jni.h! Looked in '$JAVA_HOME/include/jni.h'" >&2
    echo "Please set JAVA_HOME to a java home that has the JNI" >&2
    exit 1
fi

# Check for compiler
if [ -z "$CC" ]; then
    CC="gcc"
fi

if [ ! $(which ${CC}) ]; then
    echo "ERROR: Compiler '$CC' not found." >&2
    exit 1
fi

ACTION="$1"

case "$ACTION" in
    local)
        # Build for current machine only
        rm -rf bin/local
        mkdir -p lib bin/local
        cd bin/local

        if [ "$2" = "dyn" ]; then
            sh ../../build_jbigi.sh dynamic
        else
            ../../gmp-"${GMP_VER}"/configure --with-pic
            make
            make check
            sh ../../build_jbigi.sh static
        fi

        cp -- *jbigi???* ../../lib/
        echo 'Library copied to lib/'
        cd ../..
        ;;

    all)
        # Build all architectures
        if [ "$2" = "--dry-run" ] || [ "$2" = "-n" ]; then
            echo "Would build for:"
            . ./download_gmp.sh
            TARGET=$(uname -s |tr "[A-Z]" "[a-z]")
            BITS=${BITS:-64}
            UNAME="$(uname -m)"
            
            X86_64_PLATFORMS="zen3 zen2 zen silvermont goldmont skylake coreisbr coreihwl coreibwl bobcat jaguar bulldozer piledriver steamroller excavator atom athlon64 core2 corei nano pentium4 k10 x86_64"
            X86_PLATFORMS="pentium pentiummmx pentium2 pentium3 pentiumm k6 k62 k63 athlon geode viac3 viac32 i386"
            ARM_PLATFORMS="armv5 armv6 armv7a armcortex8 armcortex9 armcortex15"
            
            case "$TARGET" in
                linux*)
                    case "${UNAME}" in
                        x86_64|amd64|i*86)
                            if [ "$BITS" -eq 32 ]; then
                                echo "  32-bit x86: $X86_PLATFORMS"
                            else
                                echo "  64-bit x86: $X86_64_PLATFORMS"
                            fi
                            ;;
                        arm*)
                            echo "  ARM: $ARM_PLATFORMS"
                            ;;
                        aarch64)
                            echo "  ARM64: aarch64"
                            ;;
                    esac
                    ;;
                mingw*|windows*)
                    if [ "$BITS" -eq 32 ]; then
                        echo "  Windows 32-bit: $X86_PLATFORMS"
                    else
                        echo "  Windows 64-bit: $X86_64_PLATFORMS"
                    fi
                    ;;
                darwin*|osx)
                    echo "  macOS: core2 corei coreisbr coreihwl coreibwl"
                    ;;
                freebsd*)
                    echo "  FreeBSD: $X86_64_PLATFORMS"
                    ;;
                *)
                    echo "  Unknown platform (TARGET=$TARGET, UNAME=$UNAME)"
                    ;;
            esac
            echo ""
            echo "Run without --dry-run to actually build."
        else
            # Delegate to build-all.sh for full multi-arch build
            . ./build-all.sh
        fi
        ;;

    *)
        echo "Unknown command: $ACTION"
        echo "Run './build.sh help' for usage"
        exit 1
        ;;
esac
