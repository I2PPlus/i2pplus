#!/bin/sh

# Strip out binaries from the official upstream I2P source tarballs that are
# not required to build the Debian packages.

# Script mostly borrowed from Raphael Geissert's dfsg-repack.sh


set -e

if [ ! -f "$3" ] && [ ! -f "$1" ]; then
    echo "ERROR: This script must be run via uscan or by manually specifying the input tarball." >&2
    exit 1
fi

tarball=

[ -f "$3" ] && tarball="$3"
[ -z "$tarball" -a -f "$1" ] && tarball="$1"

fname=$(basename "$tarball" .bz2)
tarball=$(readlink -f "$tarball")

tdir=$(mktemp -d)
trap '[ ! -d "$tdir" ] || rm -r "$tdir"' EXIT

cp -f ${tarball} "$tarball.bkp"
echo "Filtering tarball contents..."
# The jetty and tomcat patterns are wildcarded on purpose: both trees are checked
# in under a directory that carries the version (apache-tomcat-9.0.107,
# jetty-distribution-9.3.30...), so an unversioned pattern silently stops matching
# after a bump and the vendored jars end up back in the orig tarball.
bzcat "$tarball" | tar --wildcards --delete '*/installer/lib/*' \
                        --delete '*/Slackware/*' \
                        --delete '*/debian-alt/*' \
                        --delete '*/installer/resources/geoip.txt' \
                        --delete '*/installer/resources/geoipv6.dat.gz' \
                        --delete '*/apps/jetty/apache-tomcat*/*' \
                        --delete '*/apps/jetty/jetty-distribution-*/*' \
                        --delete '*/apps/susidns/src/WEB-INF/lib/jstl.jar' \
                        --delete '*/apps/susidns/src/WEB-INF/lib/standard.jar' \
                        --delete '*/debian/*' > "$tdir/${fname}"

echo "Compressing filtered tarball..."
bzip2 -9 "$tdir/${fname}"

repackedtarball=$(echo $tarball|sed -e 's/i2psource/i2p/' -e 's/\.orig\.tar\.bz2/+repack.orig.tar.bz2/')
mv "$tdir/${fname}.bz2" "$repackedtarball"
echo "Repacked tarball saved to $repackedtarball."
