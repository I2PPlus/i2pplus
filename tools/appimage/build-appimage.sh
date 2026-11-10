#!/bin/bash
# Build I2P+ AppImage
# Usage: ./build-appimage.sh [version] [appimagetool-update] [pkg-temp-dir] [app-dir] [java-dir]

set -e

# Get the project root directory
BASEDIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$BASEDIR"

VERSION="${1:-}"
APPIMAGETOOL_UPDATE="${2:-never}"
PKG_TEMP_DIR="${3:-}"
APP_DIR="${4:-}"
JAVA_DIR="${5:-}"

# The payload is staged under the build root, which both builds keep out of the
# tree and both can be told to move (ant -Dbuild.root=, gradle ext.pkgTempDir).
# ant and gradle pass the path as an argument when they run this script; run by
# hand, ask either build what it is instead of guessing a location. Both answer
# with a pkgTempDir=<path> line, which is what the sed picks out of the log.
if [ -z "$PKG_TEMP_DIR" ] && command -v ant >/dev/null 2>&1; then
    PKG_TEMP_DIR=$(ant -q pkgTempDir 2>/dev/null | sed -n 's/.*pkgTempDir=\([^[:space:]]*\).*/\1/p' | tail -n 1)
fi
if [ -z "$PKG_TEMP_DIR" ] && [ -x "${BASEDIR}/gradlew" ]; then
    PKG_TEMP_DIR=$("${BASEDIR}/gradlew" -q pkgTempDir 2>/dev/null | sed -n 's/.*pkgTempDir=\([^[:space:]]*\).*/\1/p' | tail -n 1)
fi

if [ ! -d "${PKG_TEMP_DIR:-}" ]; then
    echo "No payload at ${PKG_TEMP_DIR:-the package staging directory} -> run 'ant preppkg-linux' first, or pass the payload directory as the third argument" >&2
    exit 1
fi

# appimagetool is not in the repository (tools/ is gitignored), so a fresh
# checkout has nothing to pack with. ant fetches it in appimage-setup; a hand
# run gets the same report from the same script. Asked for before any staging,
# so a missing tool is not discovered after the payload has been copied.
if [ ! -x "${BASEDIR}/tools/appimage/appimagetool" ]; then
    bash "${BASEDIR}/tools/appimage/download-appimagetool.sh" "${APPIMAGETOOL_UPDATE}"
fi

# Get version from CoreVersion.java if not provided
if [ -z "$VERSION" ]; then
    if [ -f "${BASEDIR}/core/java/src/net/i2p/CoreVersion.java" ]; then
        VERSION=$(grep 'public static final String VERSION = "' "${BASEDIR}/core/java/src/net/i2p/CoreVersion.java" | sed -n 's/.*VERSION = "\(.*\)".*/\1/p')
    else
        VERSION=$(date +%Y%m%d)
    fi
fi

# The AppDir is staging, so it lives with the rest of the staging - under the
# build root, never in the workspace. ant passes it as the fourth argument; run
# by hand, take the build root from the payload directory resolved above, which
# is the same place both builds stage.
BUILD_ROOT="${PKG_TEMP_DIR%/}"
BUILD_ROOT="${BUILD_ROOT%/*}"
APPDIR="${APP_DIR:-${BUILD_ROOT}/appimage}"
DISTDIR="${BASEDIR}/dist"

# Create dist dir
mkdir -p "$DISTDIR"

# Linux package is built via ant buildAppImage dependency (preppkg-linux)

# Create AppImage directory structure
rm -rf "$APPDIR"
mkdir -p "$APPDIR"
mkdir -p "$APPDIR/usr/lib/jvm"
mkdir -p "$APPDIR/i2p"

# The Java runtime to ship. The router jars are Java 8 bytecode, so any runtime
# from 8 up runs them; 17 is what we ship because the console's Jetty 9 is only
# exercised up to there. ant names it (-Dappimage.jdk=); run by hand, the default
# is the Debian/Ubuntu OpenJDK path the CI job installs. A missing runtime is a
# hard stop, not a warning: the AppImage would ship without a JVM and AppRun
# would have nothing to run.
JAVA_DIR="${JAVA_DIR:-/usr/lib/jvm/java-17-openjdk-amd64}"
if [ ! -x "${JAVA_DIR}/bin/java" ]; then
    echo "No Java runtime at ${JAVA_DIR} -> pass the JDK to bundle as the fifth argument, or -Dappimage.jdk=" >&2
    exit 1
fi
# -L copies what the JDK's symlinks point at rather than the links. Debian and
# Ubuntu keep conf/security/java.security, the jurisdiction policy files and the
# cacerts trust store outside the JDK tree, under /etc/java-<feature>-openjdk and
# /etc/ssl/certs/java, and reach them through absolute symlinks. A link copied
# into the image is resolved on the machine that runs it, which may have no such
# directory, and the JVM then stops with "Error loading java.security file"
# before the router has started.
cp -aL "$JAVA_DIR" "$APPDIR/usr/lib/jvm/"
JVM_DIR="$APPDIR/usr/lib/jvm/$(basename "$JAVA_DIR")"

# Strip what a runtime never reads: jmods (jlink inputs), the JNI headers and man
# pages (build-time), ct.sym (javac's cross-compilation symbol table), src.zip
# (sources for javadoc) and the CDS archives, which are tied to the install path
# of the JDK they were built in and so never map inside the image. That is 108 MB
# of the 262 MB a full OpenJDK 17 adds, and 81 MB of the packed image.
for cruft in jmods include man docs lib/ct.sym lib/src.zip \
             lib/server/classes.jsa lib/server/classes_nocoops.jsa; do
    rm -rf "${JVM_DIR:?}/${cruft}"
done

# The same fix as -L above, for a JDK whose security configuration is not in its
# own tree at all: without java.security the JVM throws InternalError at the
# first crypto call, and without a trust store every HTTPS connection - eepget,
# the plugin updates, console HTTPS - fails the handshake.
JAVA_FEATURE=$(sed -n 's/^JAVA_VERSION="\([0-9]*\).*/\1/p' "${JVM_DIR}/release")
if [ ! -f "${JVM_DIR}/conf/security/java.security" ] &&
   [ -d "/etc/java-${JAVA_FEATURE}-openjdk/security" ]; then
    cp -Lr "/etc/java-${JAVA_FEATURE}-openjdk/security/." "${JVM_DIR}/conf/security/"
fi
if [ ! -f "${JVM_DIR}/lib/security/cacerts" ]; then
    for store in /etc/ssl/certs/java/cacerts "${JAVA_DIR}/lib/security/cacerts"; do
        if [ -f "$store" ]; then
            mkdir -p "${JVM_DIR}/lib/security"
            cp -L "$store" "${JVM_DIR}/lib/security/cacerts"
            break
        fi
    done
fi
if [ ! -f "${JVM_DIR}/conf/security/java.security" ] ||
   [ ! -f "${JVM_DIR}/lib/security/cacerts" ]; then
    echo "Bundled runtime has no java.security or no cacerts -> the JVM cannot do TLS; pass a self-contained JDK as the fifth argument, or -Dappimage.jdk=" >&2
    exit 1
fi
# The copy is the thing that has to run, not the one that was copied from.
if ! "${JVM_DIR}/bin/java" -version >/dev/null 2>&1; then
    echo "Bundled runtime does not run from ${JVM_DIR} -> the copy is incomplete" >&2
    exit 1
fi
echo "Bundling Java runtime: $(basename "$JAVA_DIR") ($("${JAVA_DIR}/bin/java" -version 2>&1 | head -1)), $(du -sh "$JVM_DIR" | cut -f1) after pruning"

# Copy I2P+ files
cp -r "$PKG_TEMP_DIR"/* "$APPDIR/i2p/"

# Copy AppRun
cp tools/appimage/AppRun "$APPDIR/"
chmod +x "$APPDIR/AppRun"

# Create wrapper script
cat > "$APPDIR/i2prouter-wrapper" << 'EOF'
#!/bin/bash
# The desktop entry's Exec= target. AppRun is the one implementation of "start
# the router from inside the image"; this exists only because a desktop file
# names a file next to the icon rather than the AppRun itself.
exec "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/AppRun" "$@"
EOF
chmod +x "$APPDIR/i2prouter-wrapper"

# The payload's own launchers still hold the placeholders the izpack installer
# substitutes, and nothing substitutes them here, so they fail with "Unable to
# find %INSTALL_PATH/wrapper.config". Point them at the directory the script
# itself sits in, which is the install directory whatever the image is mounted
# at. They stay second choice: i2prouter runs the Tanuki wrapper, and the wrapper
# writes wrapper.log into the install directory, which inside a mounted image is
# read-only. AppRun is what the image runs.
sed -i \
    -e 's|I2P="%INSTALL_PATH"|I2P="$(cd "$(dirname "$0")" \&\& pwd)"|' \
    -e 's|I2P_CONFIG_DIR="%USER_HOME/.i2p"|I2P_CONFIG_DIR="${I2P_CONFIG_DIR:-${HOME}/.i2p}"|' \
    -e 's|I2P_CONFIG_DIR="%USER_HOME/Library/Application Support/i2p"|I2P_CONFIG_DIR="${HOME}/Library/Application Support/i2p"|' \
    -e 's|I2PTEMP="%SYSTEM_java_io_tmpdir"|I2PTEMP="${TMPDIR:-/tmp}"|' \
    -e 's|which "%JAVA_HOME"/bin/java|which "${JAVA_HOME:-/nonexistent}"/bin/java|' \
    "$APPDIR/i2p/i2prouter" "$APPDIR/i2p/runplain.sh"

# Create desktop file. The file is named after the AppStream component id, because
# that is the name appimagetool derives the metainfo file name from when it looks
# for the metadata, and the name appstreamcli matches the component's
# <launchable> against - with any other pairing the packer either says the
# metadata is missing or fails the validation. The display name is the project
# name; GenericName= says what it is, in the same words as the metadata summary.
ARCH=$(uname -m)
case "$ARCH" in
    armv7l|armhf) ARCH=armhf ;;
    aarch64|arm64) ARCH=aarch64 ;;
esac
DESKTOP_ID="io.github.i2pplus.i2pplus"
mkdir -p "$APPDIR/applications"
mkdir -p "$APPDIR/usr/share/applications"
cat > "$APPDIR/applications/${DESKTOP_ID}.desktop" << EOF
[Desktop Entry]
Name=I2P+
GenericName=Anonymous Network Router
Comment=Router for the I2P anonymous network
Exec=i2prouter-wrapper
Icon=i2p
Terminal=false
Type=Application
Categories=Network;
StartupNotify=true
X-AppImage-Name=I2P+
X-AppImage-Version=${VERSION}
X-AppImage-Arch=${ARCH}
EOF
cp "$APPDIR/applications/${DESKTOP_ID}.desktop" "$APPDIR/usr/share/applications/"
ln -sf "applications/${DESKTOP_ID}.desktop" "$APPDIR/${DESKTOP_ID}.desktop"

# AppStream metadata. appimagetool looks for the file as
# usr/share/metainfo/<desktop file name>.appdata.xml - which is why the desktop
# file is named after the component id - and then validates the whole AppDir with
# "appstreamcli validate-tree". That validator wants the file named after the
# component id and the <launchable> naming a desktop file that is really in the
# AppDir; get either pairing wrong and the packer calls the metadata missing or
# fails the build. Without the file the software centres that catalogue
# applications show nothing about this one: no description, no upstream links, no
# version. The text is in the repository and only the release line is stamped
# here, the same way the desktop file's version keys are.
APPDATA="io.github.i2pplus.i2pplus.appdata.xml"
mkdir -p "$APPDIR/usr/share/metainfo"
sed -e "s|@VERSION@|${VERSION}|g" -e "s|@DATE@|$(date +%Y-%m-%d)|g" \
    "tools/appimage/${APPDATA}" > "$APPDIR/usr/share/metainfo/${APPDATA}"

# Copy icon. The desktop file's Icon= key names i2p, so the icon has to be in the
# image under that name. The scalable one is the console's own mark - the same
# plus the login page and the favicon use - and the 256x256 PNG is there for the
# launchers that only look at fixed sizes. .DirIcon stays a PNG, which is what
# the file managers that render it expect.
ICON_SVG="installer/resources/console/themes/console/images/plus.svg"
if [ -f "$ICON_SVG" ]; then
    mkdir -p "$APPDIR/usr/share/icons/hicolor/scalable/apps"
    cp "$ICON_SVG" "$APPDIR/usr/share/icons/hicolor/scalable/apps/i2p.svg"
else
    echo "No console icon at ${ICON_SVG} -> the desktop entry gets no scalable icon" >&2
fi
if [ -f "tools/appimage/plus.png" ]; then
    mkdir -p "$APPDIR/usr/share/icons/hicolor/256x256/apps"
    cp tools/appimage/plus.png "$APPDIR/i2p.png"
    cp tools/appimage/plus.png "$APPDIR/usr/share/icons/hicolor/256x256/apps/i2p.png"
    ln -sf i2p.png "$APPDIR/.DirIcon"
fi

echo "Building I2P+ AppImage v${VERSION}..."

APPIMAGE_FILE="I2P+_${VERSION}.appimage"

echo "Creating AppImage..."
# appimagetool takes the destination as its second argument, so the packed file
# is written straight to dist and never lands in the workspace on the way.
# zstd at level 19 over 1M blocks instead of the packer's level 15 over 128K:
# measured on the staged tree (209 MB of pruned runtime and payload), 84 MB packs
# to 77 MB rather than 84, and costs two seconds. Level 22 buys nothing, and at
# 128K blocks level 19 buys nothing either - the two settings only pay together.
# ARCH is passed because the packer cannot always guess it from the AppDir, and
# without it it stops.
if APPIMAGELAUNCHER_DISABLE=1 ARCH="$ARCH" "${BASEDIR}/tools/appimage/appimagetool" \
        --comp zstd \
        --mksquashfs-opt=-b --mksquashfs-opt=1M \
        --mksquashfs-opt=-Xcompression-level --mksquashfs-opt=19 \
        "$APPDIR" "$DISTDIR/$APPIMAGE_FILE" 2>&1; then
    rm -rf "$APPDIR"
    echo "AppImage created: $DISTDIR/$APPIMAGE_FILE"
else
    echo "Error: appimagetool failed"
    exit 1
fi
