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

VERIFY_GMP=true

download_sig()
{
  if [ $(which wget) ]; then
    wget -N --progress=dot "${GMP_TAR_MIRROR}${GMP_TAR}.sig"
  elif [ $(which curl) ]; then
    curl -LO "${GMP_TAR_MIRROR}${GMP_TAR}.sig"
  fi
}

verify_tar()
{
  if [ "$VERIFY_GMP" != "true" ]; then
    echo "Skipping GMP verification (VERIFY_GMP=false)"
    return 0
  fi
  if ! command -v gpg >/dev/null 2>&1; then
    echo "WARNING: gpg not found, cannot verify tarball signature" >&2
    return 0
  fi
  if [ ! -f "${GMP_TAR}.sig" ]; then
    echo "WARNING: Signature file not found, downloading..." >&2
    download_sig
  fi
  if [ -f "${GMP_TAR}.sig" ]; then
    echo "Verifying GMP tarball signature..."
    # Import GNU keyring if not already done
    gpg --keyserver keyserver.ubuntu.com --recv-keys 0x<KEY> 2>/dev/null || true
    if gpg --verify "${GMP_TAR}.sig" "${GMP_TAR}" 2>/dev/null; then
      echo "GMP tarball signature verified successfully"
      return 0
    else
      echo "WARNING: Signature verification failed, checksums may not match!" >&2
      return 1
    fi
  else
    echo "WARNING: Could not download signature file, skipping verification" >&2
    return 0
  fi
}

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
    for p in ../patches/*.diff; do
      # Use proper quoting and avoid cat piping
      [ -f "$p" ] || continue
      echo "Applying patch: $p"
      if ! patch -p1 --dry-run -s < "$p" 2>/dev/null; then
        if ! patch -p1 < "$p"; then
          echo "WARNING: Failed to apply patch $p, attempting with -R" >&2
          if ! patch -R -p1 < "$p"; then
            echo "ERROR: Failed to apply patch $p" >&2
            cd ..
            exit 1
          fi
        fi
      else
        echo "Patch $p appears to already be applied, skipping"
      fi
    done
    cd ..
  fi
}

if [ ! -d "$GMP_DIR" -a ! -e "$GMP_TAR" ]; then
  download_tar
  if ! verify_tar; then
    echo "ERROR: GMP verification failed, aborting" >&2
    rm -f "${GMP_TAR}" "${GMP_TAR}.sig"
    exit 1
  fi
fi

if [ ! -d "$GMP_DIR" ]; then
  extract_tar
fi
