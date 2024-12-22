#!/bin/sh

# Source gmp version variables and download gmp.
. ./download_gmp.sh

# Determine JAVA_HOME if not set.
if [ -z "$JAVA_HOME" ]; then
    . ../find-java-home
fi

# Check for jni.h presence.
if [ ! -f "$JAVA_HOME/include/jni.h" ]; then
    echo "ERROR: Cannot find jni.h! Looked in \"$JAVA_HOME/include/jni.h\"" >&2
    echo "Please set JAVA_HOME to a java home that has the JNI" >&2
    exit 1
fi

# Set up directories.
rm -rf bin/local
mkdir -p lib bin/local

# Set error handling.
set -e

# Change to the build directory.
cd bin/local

# Build the library based on the argument.
case "$1" in
    dynamic)
        shift
        sh ../../build_jbigi.sh dynamic
        ;;
    *)
        case $(uname -sr) in
            Darwin*)
                # --with-pic is required for static linking
                ../../gmp-"${GMP_VER}"/configure --with-pic
                ;;
            *)
                # and it's required for ASLR
                ../../gmp-"${GMP_VER}"/configure --with-pic
                ;;
        esac
        make
        make check
        sh ../../build_jbigi.sh static
        ;;
esac

# Copy the library to the target directory.
cp -- *jbigi???* ../../lib/
echo 'Library copied to lib/'

# Return to the original directory.
cd ../..

# Run tests if requested.
if [ "$1" != "notest" ]; then
    if [ -z "$I2P" ]; then
        if [ -r "$HOME/i2p/lib/i2p.jar" ]; then
            I2P="$HOME/i2p"
        elif [ -r /usr/share/i2p/lib/i2p.jar ]; then
            I2P="/usr/share/i2p"
        else
            echo "Please set the environment variable \$I2P to run tests." >&2
            exit 1
        fi
    fi

    if [ ! -f "$I2P/lib/i2p.jar" ]; then
        echo "I2P installation not found" >&2
        echo "We looked in $I2P" >&2
        echo "Not running tests against I2P installation without knowing where it is." >&2
        echo >&2
        echo "Please set the environment variable I2P to the location of your" >&2
        echo "I2P installation (so that \$I2P/lib/i2p.jar works)." >&2
        echo "If you do so, this script will run two tests to compare your" >&2
        echo "installed jbigi with the one here you just compiled to see if" >&2
        echo "there is a marked improvement." >&2
        exit 1
    fi

    echo 'Running test with standard I2P installation...'
    java -cp "$I2P/lib/i2p.jar:$I2P/lib/jbigi.jar" net.i2p.util.NativeBigInteger
    echo
    echo 'Running test with new libjbigi...'
    java -Djava.library.path=lib/ -cp "$I2P/lib/i2p.jar:$I2P/lib/jbigi.jar" net.i2p.util.NativeBigInteger
    echo 'If the second run shows better performance, please use the jbigi that you have compiled so that I2P will work better!'
    echo "(You can do that just by copying lib/libjbigi.so over the existing libjbigi.so file in \$I2P)"
fi