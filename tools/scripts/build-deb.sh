#!/bin/bash
# Build I2P+ Debian package without system dependencies
# Usage: ./build-deb.sh [version]

set -e

# Detect project root - works both via ant (pwd is basedir) and direct invocation
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BASEDIR="${PROJECT_ROOT}"
cd "$BASEDIR"

VERSION="${1:-}"

if [ -z "$VERSION" ]; then
    if [ -f "${BASEDIR}/core/java/src/net/i2p/CoreVersion.java" ]; then
        VERSION=$(grep 'public static final String VERSION = "' "${BASEDIR}/core/java/src/net/i2p/CoreVersion.java" | sed -n 's/.*VERSION = "\(.*\)".*/\1/p')
    else
        VERSION=$(date +%Y%m%d)
    fi
fi

DISTDIR="dist"
BUILD_DIR="..debian-pkg"
TMP_DEB="deb-tmp"

echo "Building I2P+ Debian package v${VERSION}..."

mkdir -p "$DISTDIR"

echo "Step 1: Checking build artifacts..."
if [ ! -d "pkg-temp" ] || [ ! -f "build.xml" ]; then
    echo "Error: Must be run from project root directory."
    echo "  Expected: pkg-temp/ and build.xml present"
    exit 1
fi

echo "Step 2: Setting up debian build directory..."
rm -rf "$BUILD_DIR" "$TMP_DEB" deb-tmp
mkdir -p "$BUILD_DIR/debian"
mkdir -p "$BUILD_DIR/I2P"
mkdir -p "$TMP_DEB"

# Copy pkg-temp contents
echo "Copying pkg-temp to build directory..."
cp -a pkg-temp/* "$BUILD_DIR/I2P/" 2>/dev/null

# Copy jbigi native libs for multi-arch Linux support
if [ -d "installer/lib/jbigi" ]; then
    mkdir -p "$BUILD_DIR/I2P/lib/jbigi"
    cp installer/lib/jbigi/libjbigi-linux-*.so "$BUILD_DIR/I2P/lib/jbigi/" 2>/dev/null || true
fi

# Copy history.txt
cp docs/history.txt "$BUILD_DIR/" 2>/dev/null || true

echo "Step 3: Creating debian package files..."

# Patch code for bundled libs (remove system Tomcat dependencies)
echo "Patching code for bundled libs..."
if grep -q "SimpleInstanceManager" apps/routerconsole/java/src/net/i2p/router/web/WebAppConfiguration.java 2>/dev/null; then
    sed -i 's/import org.apache.tomcat.SimpleInstanceManager;//' apps/routerconsole/java/src/net/i2p/router/web/WebAppConfiguration.java
    sed -i 's/context.getServletContext().setAttribute("org.apache.tomcat.InstanceManager", new SimpleInstanceManager());/\/\/ Removed for bundled libs/' apps/routerconsole/java/src/net/i2p/router/web/WebAppConfiguration.java
fi
if grep -q "realm.update\|realm.putUser" apps/routerconsole/java/src/net/i2p/router/web/RouterConsoleRunner.java 2>/dev/null; then
    sed -i 's/realm\.putUser/realm.update/g' apps/routerconsole/java/src/net/i2p/router/web/RouterConsoleRunner.java
fi

# debian/control
cat > "$BUILD_DIR/debian/control" << EOF
Source: i2p
Section: net
Priority: optional
Maintainer: z3d@i2pmail.org
Standards-Version: 4.5.0

Package: i2p
Architecture: all
Depends: default-jre-headless (>= 1.8)
Recommends: libjbigi-jni
Description: I2P Router and Suite
 I2P is an anonymizing network.
 It protects you from traffic analysis, provides pseudonymous connections,
 and lets you use most Internet services privately and safely.
 .
 This package contains the I2P router and the I2P suite of tools including:
  - I2PSnark (bittorrent client)
  - I2P email
  - address book
  - router console

Package: libjbigi-jni
Architecture: any
Description: I2P Big Integer Library
 Contains the JNI library for fast Big Integer math used by I2P.
EOF

# debian/changelog
cat > "$BUILD_DIR/debian/changelog" << EOF
i2p (${VERSION}) focal; urgency=medium

  * New upstream version ${VERSION}

 -- z3d@i2pmail.org  $(date -R)

EOF

# debian/postinst
cat > "$BUILD_DIR/debian/postinst" << 'EOF'
#!/bin/sh
set -e
if [ -f "$I2P/lib/wrapper/javaservice-wrapper" ]; then
    chmod 755 "$I2P/lib/wrapper/javaservice-wrapper" 2>/dev/null || true
fi
if [ -f "$I2P/i2prouter" ]; then
    chmod 755 "$I2P/i2prouter" 2>/dev/null || true
fi
if command -v ldconfig >/dev/null 2>&1; then
    ldconfig 2>/dev/null || true
fi
exit 0
EOF
chmod 755 "$BUILD_DIR/debian/postinst"

# debian/preinst
cat > "$BUILD_DIR/debian/preinst" << 'EOF'
#!/bin/sh
set -e
if ! getent passwd i2psvc > /dev/null 2>&1; then
    adduser --system --home /var/lib/i2p --disabled-login --group i2psvc 2>/dev/null || true
fi
mkdir -p /var/lib/i2p
mkdir -p /var/log/i2p
chown -R i2psvc:i2psvc /var/lib/i2p /var/log/i2p 2>/dev/null || true
exit 0
EOF
chmod 755 "$BUILD_DIR/debian/preinst"

# debian/postrm
cat > "$BUILD_DIR/debian/postrm" << 'EOF'
#!/bin/sh
set -e
if [ "$1" = "purge" ]; then
    rm -rf /var/lib/i2p /var/log/i2p 2>/dev/null || true
    deluser i2psvc 2>/dev/null || true
fi
exit 0
EOF
chmod 755 "$BUILD_DIR/debian/postrm"

# debian/prerm
cat > "$BUILD_DIR/debian/prerm" << 'EOF'
#!/bin/sh
set -e
if [ -f /usr/bin/i2prouter ]; then
    /usr/bin/i2prouter stop 2>/dev/null || true
fi
exit 0
EOF
chmod 755 "$BUILD_DIR/debian/prerm"

# Create systemd service
mkdir -p "$BUILD_DIR/debian/i2p"
cat > "$BUILD_DIR/debian/i2p/i2p.service" << 'EOF'
[Unit]
Description=I2P Router
Documentation=https://geti2p.net/en/docs
After=network.target

[Service]
Type=forking
ExecStart=/usr/bin/i2prouter console
User=i2psvc
Group=i2psvc
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

# Create startup wrapper
cat > "$BUILD_DIR/I2P/i2prouter" << 'EOF'
#!/bin/sh
# I2P Router startup script
if [ -z "$I2P" ]; then
    I2P=/usr/share/i2p
fi
cd "$I2P"
exec ./i2prouter-nowrapper "$@"
EOF
chmod 755 "$BUILD_DIR/I2P/i2prouter"

echo "Step 4: Building .deb packages..."

# Manual dpkg-deb build for i2p package
echo "Building i2p package..."
rm -rf "$TMP_DEB"
mkdir -p "$TMP_DEB/DEBIAN"
mkdir -p "$TMP_DEB/usr/share/i2p"
mkdir -p "$TMP_DEB/usr/bin"
mkdir -p "$TMP_DEB/var/lib/i2p"
mkdir -p "$TMP_DEB/var/log/i2p"
mkdir -p "$TMP_DEB/lib/systemd/system"

# Copy I2P files
cp -a "$BUILD_DIR/I2P/"* "$TMP_DEB/usr/share/i2p/"

# Create symlinks
ln -sf /var/lib/i2p "$TMP_DEB/usr/share/i2p/config"
ln -sf /var/log/i2p "$TMP_DEB/usr/share/i2p/logs"

# Install wrapper script
cp "$BUILD_DIR/I2P/i2prouter" "$TMP_DEB/usr/bin/i2prouter"

# Install systemd service
cp "$BUILD_DIR/debian/i2p/i2p.service" "$TMP_DEB/lib/systemd/system/i2p.service"

# Create control file
printf 'Package: i2p\nVersion: %s\nSection: net\nPriority: optional\nArchitecture: all\nDepends: default-jre-headless (>= 1.8)\nMaintainer: z3d@i2pmail.org\nDescription: I2P Router and Suite\n I2P is an anonymizing network.\n It protects you from traffic analysis, provides pseudonymous connections,\n and lets you use most Internet services privately and safely.\n' "$VERSION" > "$TMP_DEB/DEBIAN/control"

# Build package
fakeroot dpkg-deb --build "$TMP_DEB" "$BASEDIR/$DISTDIR/i2p_${VERSION}_all.deb"

echo "Packages in $DISTDIR:"
ls -la "$BASEDIR/$DISTDIR/"*.deb 2>/dev/null || echo "No .deb files found"

# Cleanup
cd "$BASEDIR"
rm -rf "$BUILD_DIR" "$TMP_DEB"
rm -rf debian .pc history.txt
rm -rf core/c/jbigi/*.o core/c/jbigi/*.so core/c/jcpuid/*.o core/c/jcpuid/*.so
rm -rf distro/debian/.debhelper distro/debian/files distro/debian/libjbigi-jni

# Restore original source
echo "Restoring original source..."
git checkout -- apps/routerconsole/java/src/net/i2p/router/web/WebAppConfiguration.java apps/routerconsole/java/src/net/i2p/router/web/RouterConsoleRunner.java 2>/dev/null || true

echo "Done! .deb files in dist/"