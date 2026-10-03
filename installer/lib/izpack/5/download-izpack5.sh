#!/bin/bash
#
# Download or update the IzPack 5 distribution used by the installer5 targets.
# Compares the cached version against the latest upstream release and replaces
# the cached copy when they differ, so the distribution is self-updating and
# never has to be installed by hand.
#
# Installs to installer/lib/izpack/5/izpack-<version>/ with an
# installer/lib/izpack/5/izpack symlink pointing at it (the layout
# build.xml's ensureIzpack5 target and the Gradle mirrors read as
# izpack5.home). The versioned directory is required: IzPack's own installer
# wipes its target folder before unpacking, which would destroy the tracked
# install5.xml and patches/ that sit next to it.
#
# Usage: download-izpack5.sh [--force] [--version X.Y.Z]
#
#   IZPACK5_VERSION   same as --version; pins the release instead of
#                     tracking the latest one
#
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VERSION_FILE="${SCRIPT_DIR}/version.txt"
CONFIG_FILE="${SCRIPT_DIR}/izpack5.properties"
LINK_DIR="${SCRIPT_DIR}/izpack"
MAVEN_BASE="https://repo1.maven.org/maven2/org/codehaus/izpack/izpack-dist"
FORCE=false
PIN=""

while [ $# -gt 0 ]; do
    case "$1" in
        --force) FORCE=true; shift ;;
        --version) PIN="$2"; shift 2 ;;
        --version=*) PIN="${1#*=}"; shift ;;
        -h|--help)
            echo "Usage: download-izpack5.sh [--force] [--version X.Y.Z]"
            exit 0
            ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done
[ -n "${IZPACK5_VERSION:-}" ] && [ -z "$PIN" ] && PIN="$IZPACK5_VERSION"

# Maven Central intermittently answers with a 403 "open proxy" page instead of
# the artifact for clients it misclassifies, so every request retries and then
# verifies the payload is really a zip before anything is unpacked.
fetch() {
    local url="$1" out="$2" attempt
    for attempt in 1 2 3 4 5; do
        # Bypass any SOCKS wrapper for the plain-HTTPS Maven Central fetches,
        # the same way tools/spotbugs/download-spotbugs.sh does.
        if LD_PRELOAD= curl -sSL --fail --max-time 600 -o "$out" "$url" \
           && [ "$(head -c 2 "$out")" = "PK" ]; then
            return 0
        fi
        echo "  attempt ${attempt} failed, retrying..." >&2
        sleep $((attempt * 3))
    done
    return 1
}

get_latest_version() {
    # GitHub carries the release list; the tag is izpack-<version>
    local tag
    tag=$(LD_PRELOAD= curl -sSL --fail --max-time 60 \
        https://api.github.com/repos/izpack/izpack/releases/latest 2>/dev/null \
        | grep '"tag_name"' | head -1 | sed 's/.*"tag_name": *"\([^"]*\)".*/\1/')
    echo "${tag#izpack-}"
}

get_maven_version() {
    LD_PRELOAD= curl -sSL --fail --max-time 60 "${MAVEN_BASE}/maven-metadata.xml" 2>/dev/null \
        | sed -n 's:.*<release>\([^<]*\)</release>.*:\1:p' | head -1
}

# The release list and the published artifact can disagree while a release is
# being rolled out, so only accept a version whose installer jar is fetchable.
pick_version() {
    local candidate
    for candidate in "$@"; do
        [ -z "$candidate" ] && continue
        if LD_PRELOAD= curl -sIL --fail --max-time 60 -o /dev/null \
            "${MAVEN_BASE}/${candidate}/izpack-dist-${candidate}-installer.jar"; then
            echo "$candidate"
            return 0
        fi
        echo "  izpack ${candidate}: no installer jar on Maven Central, skipping" >&2
    done
    return 1
}

get_installed_version() {
    if [ -f "$VERSION_FILE" ]; then
        cat "$VERSION_FILE"
    else
        echo "none"
    fi
}

has_cached_install() {
    [ -d "$LINK_DIR/lib" ]
}

# An offline build must not break a tree that already has a usable copy, so
# every failure path falls back to the cache instead of failing the build.
offline_ok() {
    echo "WARNING: $1" >&2
    if has_cached_install; then
        echo "WARNING: Using the cached IzPack $(get_installed_version) in ${LINK_DIR}." >&2
        exit 0
    fi
    echo "ERROR: No cached IzPack 5 found in ${LINK_DIR}." >&2
    exit 1
}

# Machine-local settings, gitignored and read by build.xml ahead of
# override.properties so per-host paths never have to be committed. Written only
# when absent, so local edits survive every update, and called before any of the
# up-to-date exits so it exists even when nothing is downloaded. It deliberately
# carries no version pin: pinning here would be the way to stop tracking the
# latest release.
write_config() {
    [ -f "$CONFIG_FILE" ] && return 0
    cat > "$CONFIG_FILE" <<EOF
# Machine-local IzPack 5 settings, read by build.xml. Gitignored: keep per-host
# paths here rather than in override.properties, which is tracked in git.
# Uncomment and edit as needed; delete the file to get the built-in defaults.
#
# izpack5.home=/path/to/IzPack
#   Use an existing distribution instead of the downloaded copy. When set it is
#   used as-is and never replaced or updated, so it also stops auto-update.
#   Leave it unset to keep tracking the latest IzPack 5 release.
#
# izpack5.python=/path/to/python3
#   Interpreter for IzPack's izpack2exe.py (installer5exe). The script is Python 3
#   but ships a "#!/usr/bin/env python" shebang that does not resolve where only
#   python3 is on PATH.
#
# To pin a specific IzPack release instead, use --version or IZPACK5_VERSION.
EOF
    echo "Wrote ${CONFIG_FILE}"
}

echo "Checking IzPack 5 version..."

write_config

INSTALLED=$(get_installed_version)

if [ -n "$PIN" ]; then
    LATEST="$PIN"
elif [ "$FORCE" = false ] && [ "$INSTALLED" != "none" ] && has_cached_install; then
    LATEST=$(get_latest_version)
    if [ -z "$LATEST" ]; then
        offline_ok "Could not determine the latest IzPack release."
    fi
else
    LATEST=$(get_latest_version)
    if [ -z "$LATEST" ]; then
        offline_ok "Could not determine the latest IzPack release."
    fi
fi

if [ -z "$PIN" ] && [ "$FORCE" = false ] && [ "$INSTALLED" = "$LATEST" ]; then
    echo "IzPack 5 $LATEST is up to date."
    exit 0
fi

if [ -n "$PIN" ] && [ "$FORCE" = false ] && [ "$INSTALLED" = "$PIN" ]; then
    echo "IzPack 5 $PIN is up to date (pinned)."
    exit 0
fi

[ -n "$PIN" ] || echo "Update available: $INSTALLED -> $LATEST"

# The release tag can be published before the matching artifact lands, so fall
# back to the version Maven Central itself reports as current.
if ! RESOLVED=$(pick_version "$LATEST" "$(get_maven_version)"); then
    if has_cached_install && [ "$FORCE" = false ]; then
        offline_ok "No downloadable IzPack 5 distribution found."
    fi
    echo "ERROR: Could not find a downloadable IzPack 5 distribution." >&2
    exit 1
fi
if [ "$RESOLVED" != "$LATEST" ]; then
    echo "Falling back to IzPack $RESOLVED (latest tag $LATEST is not published yet)."
fi

INSTALL_DIR="${SCRIPT_DIR}/izpack-${RESOLVED}"
TMPDIR=$(mktemp -d /tmp/izpack5-XXXXXX)
JAR_FILE="${TMPDIR}/izpack-dist-${RESOLVED}-installer.jar"

cleanup() { rm -rf "$TMPDIR"; }
trap cleanup EXIT

echo "Downloading IzPack 5 $RESOLVED (~95MB)..."
fetch "${MAVEN_BASE}/${RESOLVED}/izpack-dist-${RESOLVED}-installer.jar" "$JAR_FILE" \
    || offline_ok "Download of izpack-dist-${RESOLVED}-installer.jar failed."

# IzPack ships its own installer, which is how the distribution is meant to be
# laid out. Driving it with a generated options file gives a non-interactive
# install; the "Required files" and "Experimental tools" packs are the whole
# distribution, so the default selection is what we want. stdin is closed
# because console mode prompts for a language pack before unpacking.
echo "Installing to ${INSTALL_DIR}..."
echo "INSTALL_PATH=${INSTALL_DIR}" > "${TMPDIR}/options.xml"
# -Djava.awt.headless=true: console mode still touches AWT headless on some JVMs
( LD_PRELOAD= java -Djava.awt.headless=true -jar "$JAR_FILE" \
      -options "${TMPDIR}/options.xml" < /dev/null ) > "${TMPDIR}/install.log" 2>&1 \
    || { cat "${TMPDIR}/install.log" >&2; offline_ok "IzPack 5 $RESOLVED install failed."; }

if [ ! -d "${INSTALL_DIR}/lib" ]; then
    cat "${TMPDIR}/install.log" >&2
    offline_ok "IzPack 5 $RESOLVED did not produce ${INSTALL_DIR}/lib."
fi

# Remove the uninstaller is generated by the install and only serves the installed
# copy; nothing in the build reads it.
rm -rf "${INSTALL_DIR}/Uninstaller"

# Machine-local settings, gitignored and loaded by build.xml ahead of
# override.properties so per-host paths never have to be committed. A no-op once
# the file exists, so local edits survive every update.
write_config

# Drop versions superseded before the last update, or left behind by an aborted
# run. version.txt only records the current install, so it cannot detect those.
# Matched on izpack-<digits> rather than a bare izpack-* glob so the sweep can
# never widen onto izpack5.properties or the izpack symlink.
for old in "${SCRIPT_DIR}"/izpack-[0-9]*; do
    if [ -d "$old" ] && [ "$old" != "$INSTALL_DIR" ]; then
        echo "Removing stale IzPack ${old##*/}..."
        rm -rf "$old"
    fi
done

ln -sfn "izpack-${RESOLVED}" "$LINK_DIR"

echo -n "$RESOLVED" > "$VERSION_FILE"

echo "IzPack 5 ${RESOLVED} installed to ${INSTALL_DIR}"
echo "Symlink: ${LINK_DIR} -> izpack-${RESOLVED}"
echo "Jars: $(ls "${INSTALL_DIR}/lib"/*.jar | wc -l) ($(du -sh "${INSTALL_DIR}/lib" | cut -f1))"
