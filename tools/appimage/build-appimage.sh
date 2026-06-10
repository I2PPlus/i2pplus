#!/bin/bash
# Build I2P+ AppImage
# Usage: ./build-appimage.sh [version]

set -e

# Get the project root directory
BASEDIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$BASEDIR"

VERSION="${1:-}"
APPIMAGETOOL_UPDATE="${2:-never}"
PKG_TEMP_DIR="${3:-pkg-temp}"

# Get version from CoreVersion.java if not provided
if [ -z "$VERSION" ]; then
    if [ -f "${BASEDIR}/core/java/src/net/i2p/CoreVersion.java" ]; then
        VERSION=$(grep 'public static final String VERSION = "' "${BASEDIR}/core/java/src/net/i2p/CoreVersion.java" | sed -n 's/.*VERSION = "\(.*\)".*/\1/p')
    else
        VERSION=$(date +%Y%m%d)
    fi
fi

APPDIR="AppImage"
DISTDIR="${BASEDIR}/dist"

# Create dist dir
mkdir -p "$DISTDIR"

# Linux package is built via ant buildAppImage dependency (preppkg-linux)

# Create AppImage directory structure
rm -rf "$APPDIR"
mkdir -p "$APPDIR"
mkdir -p "$APPDIR/usr/lib/jvm"
mkdir -p "$APPDIR/i2p"

# Copy Java runtime
if [ -d "/usr/lib/jvm/java-17-openjdk-amd64" ]; then
    cp -r /usr/lib/jvm/java-17-openjdk-amd64 "$APPDIR/usr/lib/jvm/"
else
    echo "Warning: Java 17 not found"
fi

# Copy I2P+ files
cp -r "$PKG_TEMP_DIR"/* "$APPDIR/i2p/"

# Copy AppRun
cp tools/appimage/AppRun "$APPDIR/"
chmod +x "$APPDIR/AppRun"

# Create wrapper script
cat > "$APPDIR/i2prouter-wrapper" << 'EOF'
#!/bin/bash
APPDIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -d "$APPDIR/usr/lib/jvm" ]; then
    export JAVA_HOME="$APPDIR/usr/lib/jvm/java-17-openjdk-amd64"
    export PATH="$JAVA_HOME/bin:$PATH"
fi
exec "$APPDIR/i2p/i2prouter" "$@"
EOF
chmod +x "$APPDIR/i2prouter-wrapper"

# Create desktop file
mkdir -p "$APPDIR/applications"
mkdir -p "$APPDIR/usr/share/applications"
cat > "$APPDIR/applications/i2pplus.desktop" << EOF
[Desktop Entry]
Name=I2P+ Router
Comment=I2P+ Anonymous Network Router
Exec=i2prouter-wrapper
Icon=i2p
Terminal=false
Type=Application
Categories=Network;
StartupNotify=true
X-AppImage-Name=I2P+
X-AppImage-Version=${VERSION}
X-AppImage-Arch=x86_64
EOF
cp "$APPDIR/applications/i2pplus.desktop" "$APPDIR/usr/share/applications/"
ln -sf applications/i2pplus.desktop "$APPDIR/i2pplus.desktop"

# Copy icon
if [ -f "tools/appimage/plus.png" ]; then
    mkdir -p "$APPDIR/usr/share/icons/hicolor/256x256/apps"
    cp tools/appimage/plus.png "$APPDIR/i2p.png"
    cp tools/appimage/plus.png "$APPDIR/usr/share/icons/hicolor/256x256/apps/i2p.png"
    ln -sf i2p.png "$APPDIR/.DirIcon"
fi

echo "Building I2P+ AppImage v${VERSION}..."

APPIMAGE_FILE="I2P+_${VERSION}.appimage"

echo "Creating AppImage..."
if APPIMAGELAUNCHER_DISABLE=1 "${BASEDIR}/tools/appimage/appimagetool" "$APPDIR" "$APPIMAGE_FILE" 2>&1; then
    echo "Moving AppImage to dist..."
    mv "$APPIMAGE_FILE" "$DISTDIR/"
    rm -rf "$APPDIR"
    echo "AppImage created: $DISTDIR/$APPIMAGE_FILE"
else
    echo "Error: appimagetool failed"
    exit 1
fi
