#!/bin/sh
#
#  This script downloads gmp-6.x.x.tar.bz2 to this directory
#  (if a different version, change the GMP_VER= line below)
#
#  If you do not want any patches applied comment out the PATCH_GMP line.
#

PATCH_GMP=true

export GMP_VER=6.3.0
export GMP_TARVER=${GMP_VER}
export GMP_DIR="gmp-$GMP_VER"
export GMP_TAR="gmp-$GMP_TARVER.tar.bz2"
export GMP_TAR_MIRROR="https://ftp.gnu.org/gnu/gmp/" # This mirror works
#export GMP_TAR_MIRROR="https://gmplib.org/download/gmp/" #This is the upstream, but they have an expired TLS certificate.

download_tar()
{
  GMP_TAR_URL="${GMP_TAR_MIRROR}${GMP_TAR}"
  if [ $(which wget) ]; then
    echo "Downloading $GMP_TAR_URL"
    wget -N --progress=dot $GMP_TAR_URL
  else
    echo "ERROR: Cannot find wget." >&2
    echo >&2
    echo "Please download $GMP_TAR_URL" >&2
    echo "manually and rerun this script." >&2
    exit 1
  fi
}

extract_tar()
{
  tar -xjf ${GMP_TAR} > /dev/null 2>&1 || (rm -f ${GMP_TAR} && download_tar && extract_tar || exit 1)
  if [ ! -z $PATCH_GMP ]; then
    cd ${GMP_DIR}
    for p in $(ls ../patches/*.diff); do
      echo "applying $p"
      cat $p | patch -p1
    done
    cd ..
  fi
}

if [ ! -d "$GMP_DIR" -a ! -e "$GMP_TAR" ]; then
  download_tar
fi

if [ ! -d "$GMP_DIR" ]; then
  extract_tar
fi
