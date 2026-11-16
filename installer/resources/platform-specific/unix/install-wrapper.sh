#!/bin/sh
#
# Installs the service wrapper's own files into an I2P+ install.
#
# The wrapper cannot update itself. i2psvc is the process the router runs under,
# libwrapper.so is dlopen()d by that process, and on Windows the JVM holds
# lib/wrapper.jar open. A restart from the console or `i2prouter restart` keeps
# the native wrapper process alive and re-launches only the JVM, so all three
# stay mapped for the whole update - an updater payload stages them and stops
# there. This script is the part that stops the service, replaces the files, and
# starts the service again.
#
# Nothing is written until every file it is about to replace has been copied
# aside, and a failed install puts them back and starts the service again.

I2P=""
SOURCE=""
DRY=""
NO_RESTART=""
START=""

usage() {
    cat << 'EOF'
Usage: install-wrapper.sh [options]

Stops the wrapper service, replaces the wrapper's own files (the launcher, its
native library and lib/wrapper.jar) with the release this script was built or
updated with, and starts the service again. Run it after the rest of an update
is in place.

Options:
  --install-dir DIR  I2P install to update. Default: the directory holding this
                     script, when that is an install root.
  --source DIR       Directory holding the new wrapper files, laid out as
                     <platform>/ plus all/wrapper.jar. Default, in order: the
                     build tree this script sits in, then lib/wrapper inside the
                     install, which is where an updater payload stages them.
  -n, --dry-run      Report what would be done; write nothing.
  --no-restart       Stop, install, and leave the service stopped.
  --start            Start the service afterwards even if it was not running.
                     Otherwise it is started back only if it was running, and
                     by the same mechanism that was running it - the service
                     unit if the unit is what holds the router, i2prouter if
                     the router was started from the command line.
  -h, --help         This text.
EOF
}

die() {
    echo "install-wrapper.sh: $*" >&2
    exit 1
}

say() {
    echo "$*"
}

run() {
    if [ -n "$DRY" ]; then
        echo "DRY: $*"
        return 0
    fi
    "$@"
}

# First file under $1 matching any of the remaining arguments as a name pattern.
# find is used rather than a shell glob so a pattern like libwrapper*.jnilib
# survives a path containing spaces.
first_match() {
    dir=$1
    shift
    [ -d "$dir" ] || return 1
    for pat in "$@"; do
        found=$(find "$dir" -maxdepth 1 -type f -name "$pat" 2>/dev/null | head -1)
        if [ -n "$found" ]; then
            echo "$found"
            return 0
        fi
    done
    return 1
}

mode_of() {
    stat -c '%a' "$1" 2>/dev/null || stat -f '%Lp' "$1" 2>/dev/null || echo 644
}

# Any digit with its low bit set is an execute bit: 1, 3, 5, 7.
has_x() {
    case "$1" in
        *[1357]*) return 0 ;;
    esac
    return 1
}

while [ $# -gt 0 ]; do
    case "$1" in
        --install-dir) [ $# -ge 2 ] || die "--install-dir needs a directory"; I2P="$2"; shift 2 ;;
        --source)      [ $# -ge 2 ] || die "--source needs a directory";      SOURCE="$2"; shift 2 ;;
        --dry-run|-n)  DRY="true"; shift ;;
        --no-restart)  NO_RESTART="true"; shift ;;
        --start)       START="true"; shift ;;
        --help|-h)     usage; exit 0 ;;
        *)             usage >&2; die "unknown argument: $1" ;;
    esac
done

# ---------------------------------------------------------------- platform ---

OS=$(uname -s)
ARCH=$(uname -m)
case "$OS" in
    Linux)
        case "$ARCH" in
            aarch64 | armv8*) PLATFORM="linux64-armv8" ;;
            *)                PLATFORM="linux64" ;;
        esac
        LIB_PATTERNS="libwrapper.so"
        ;;
    FreeBSD)
        case "$ARCH" in
            aarch64) PLATFORM="freebsd-arm64" ;;
            *)       PLATFORM="freebsd64" ;;
        esac
        LIB_PATTERNS="libwrapper.so"
        ;;
    Darwin)
        case "$ARCH" in
            arm64) PLATFORM="macosx-arm64" ;;
            *)     PLATFORM="macosx" ;;
        esac
        # macOS ships Tanuki's versioned names, and the .jnilib extension is what
        # the JVM's dlopen expects.
        LIB_PATTERNS="libwrapper*.jnilib libwrapper*.dylib"
        ;;
    *)
        die "no wrapper binaries for $OS - start the router with runplain.sh, which needs no wrapper"
        ;;
esac

# ------------------------------------------------------------------ layout ---

here=$(cd "$(dirname "$0")" && pwd)

if [ -z "$I2P" ]; then
    if [ -f "$here/i2prouter" ] || [ -f "$here/wrapper.config" ]; then
        I2P="$here"
    else
        die "no i2prouter or wrapper.config in $here - name the install with --install-dir"
    fi
fi
[ -d "$I2P" ] || die "no such install directory: $I2P"
I2P=$(cd "$I2P" && pwd)

if [ -z "$SOURCE" ]; then
    # From the build tree this script sits in
    # (installer/resources/platform-specific/unix), the artifacts are three
    # levels up under installer/lib/wrapper.
    for cand in "$here/../../../lib/wrapper" "$I2P/lib/wrapper"; do
        if [ -d "$cand/$PLATFORM" ]; then
            SOURCE=$(cd "$cand" && pwd)
            break
        fi
    done
fi
[ -n "$SOURCE" ] || die "no wrapper files to install - name them with --source DIR"
[ -d "$SOURCE/$PLATFORM" ] || die "$SOURCE has no $PLATFORM - this release carries no wrapper for $OS $ARCH"

SRC_JAR="$SOURCE/all/wrapper.jar"
[ -f "$SRC_JAR" ] || die "missing $SRC_JAR"

src_launcher=$(first_match "$SOURCE/$PLATFORM" i2psvc 'i2psvc-*')
[ -n "$src_launcher" ] || die "no launcher in $SOURCE/$PLATFORM"
src_lib=$(first_match "$SOURCE/$PLATFORM" $LIB_PATTERNS)
[ -n "$src_lib" ] || die "no native library in $SOURCE/$PLATFORM"

# The launcher and the native library keep whatever names the install already
# uses, so an install holding i2psvc-macosx-universal-64 is not renamed by an
# update, and an install with no wrapper yet gets the names from the release.
dst_launcher=$(first_match "$I2P" i2psvc 'i2psvc-*')
[ -n "$dst_launcher" ] || dst_launcher="$I2P/$(basename "$src_launcher")"
dst_lib=$(first_match "$I2P/lib" $LIB_PATTERNS)
[ -n "$dst_lib" ] || dst_lib="$I2P/lib/$(basename "$src_lib")"
dst_jar="$I2P/lib/wrapper.jar"

# Which release is being installed, from whichever marker is present.
version=""
for m in "$SOURCE/version.txt" "$SOURCE/VERSION" "$SOURCE/win64/BUILT-FROM"; do
    if [ -f "$m" ]; then
        version=$(sed -n 's/^WRAPPER_VERSION=//p;s/^[0-9][0-9.]*$//p' "$m" 2>/dev/null | head -1)
        [ -n "$version" ] && break
    fi
done

say "=== Wrapper update ==="
say "Release:  ${version:-unknown}"
say "Platform: $PLATFORM"
say "Install:  $I2P"
say "Source:   $SOURCE"
say ""
say "  $(basename "$src_launcher") -> $dst_launcher"
say "  $(basename "$src_lib") -> $dst_lib"
say "  wrapper.jar -> $dst_jar"
say ""

# ----------------------------------------------------------------- service ---

# Which mechanism controls this router is not the same question as which one is
# installed. A unit file can sit in place while the router was started by hand
# with ./i2prouter start, and then `systemctl stop` cheerfully reports that a
# service which was never started has stopped while the wrapper keeps running.
# So the choice is made from what is actually running, and whichever mechanism
# stopped the router is the one that starts it again.
unit=""
if command -v systemctl >/dev/null 2>&1; then
    for u in i2p i2prouter; do
        systemctl list-unit-files --no-legend --no-pager "$u.service" 2>/dev/null |
            grep -q "^$u\.service" || continue
        # A unit belongs to this install only if it starts this install's
        # i2prouter. Two installs on one machine is common, and the unit that
        # happens to exist may be the other one's; so may the running process.
        if systemctl cat "$u.service" 2>/dev/null | grep -q "ExecStart=$I2P/i2prouter"; then
            unit="$u"
            break
        fi
    done
fi

# systemctl needs root to stop a system unit; i2prouter goes through runuser and
# reports its own needs.
SUDO=""
if [ -n "$unit" ] && [ "$(id -u)" -ne 0 ] && command -v sudo >/dev/null 2>&1; then
    SUDO="sudo"
fi

unit_running() {
    [ -n "$unit" ] || return 1
    $SUDO systemctl is-active --quiet "$unit"
}

# True if the process named by $1 is this install's launcher. Matching the path
# rather than just the pid keeps a recycled pid, or an unrelated process that
# happens to mention the install directory, from being read as the router.
is_our_launcher() {
    ps -p "$1" -o args= 2>/dev/null | grep -q "^$I2P/$(basename "$dst_launcher")"
}

# The launcher process is what holds the three files being replaced.
script_running() {
    for pid in $(pgrep -x "$(basename "$dst_launcher")" 2>/dev/null); do
        is_our_launcher "$pid" && return 0
    done
    pid=$(sed -n 's/^wrapper\.pidfile=//p' "$I2P/wrapper.config" 2>/dev/null | head -1)
    if [ -n "$pid" ] && [ -f "$pid" ]; then
        is_our_launcher "$(cat "$pid" 2>/dev/null)" && return 0
    fi
    return 1
}

# systemd, i2prouter, or empty for not running.
how_running() {
    if unit_running; then
        echo systemd
    elif script_running; then
        echo i2prouter
    else
        echo ""
    fi
}

STOPPED_BY=""

stop_service() {
    case "$STOPPED_BY" in
        systemd)
            say "Stopping $unit.service..."
            run $SUDO systemctl stop "$unit"
            ;;
        i2prouter)
            say "Stopping the router through i2prouter..."
            run "$I2P/i2prouter" stop
            ;;
    esac
    [ -n "$DRY" ] && return 0
    # The three files are mapped by the process that was just asked to die.
    # Copying while it is still on its way out trades a clean stop for ETXTBSY
    # or a half written binary, so wait for it to be gone, not for a delay.
    waited=0
    while [ -n "$(how_running)" ]; do
        sleep 1
        waited=$((waited + 1))
        if [ "$waited" -ge 60 ]; then
            die "the wrapper is still running 60s after the stop request - refusing to copy over it"
        fi
    done
    say "Stopped."
}

start_service() {
    if [ -n "$NO_RESTART" ]; then
        say "--no-restart: leaving the service stopped."
        return 0
    fi
    if [ -z "$STOPPED_BY" ] && [ -z "$START" ]; then
        say "The wrapper was not running, so nothing was started. Start it as you normally do, or pass --start."
        return 0
    fi
    # Start it back the way it was running. A forced start with nothing to go on
    # prefers the unit when one is installed.
    how="$STOPPED_BY"
    if [ -z "$how" ]; then
        if [ -n "$unit" ]; then how=systemd; else how=i2prouter; fi
    fi
    case "$how" in
        systemd)
            say "Starting $unit.service..."
            run $SUDO systemctl start "$unit"
            ;;
        *)
            say "Starting the router through i2prouter..."
            run "$I2P/i2prouter" start
            ;;
    esac
}

# ------------------------------------------------------------------ install ---

STOPPED_BY=$(how_running)
case "$STOPPED_BY" in
    "")
        say "The wrapper is not running."
        ;;
    i2prouter)
        if [ ! -x "$I2P/i2prouter" ]; then
            die "the wrapper is running but $I2P/i2prouter is missing - stop it by hand, then re-run with --no-restart"
        fi
        say "The wrapper is running, started through i2prouter."
        stop_service
        ;;
    *)
        say "The wrapper is running, started through $unit.service."
        stop_service
        ;;
esac

# Copied aside first, in the install rather than in /tmp, so an interrupted
# update can still be undone by hand.
backup="$I2P/lib/wrapper-backup"
if [ -z "$DRY" ]; then
    rm -rf "$backup"
    mkdir -p "$backup" || die "cannot create $backup"
fi
saved=""
for f in "$dst_launcher" "$dst_lib" "$dst_jar"; do
    if [ -f "$f" ]; then
        # The mode is recorded, not assumed: the launcher is executable and the
        # native library and jar are not, and what an install carries is what it
        # should get back.
        if [ -n "$DRY" ]; then
            echo "DRY: copy $f aside (mode $(mode_of "$f"))"
        else
            printf '%s %s\n' "$(basename "$f")" "$(mode_of "$f")" >> "$backup/modes" ||
                die "cannot write $backup/modes"
            cp -p "$f" "$backup/$(basename "$f")" || die "cannot copy $f aside"
        fi
        saved="$saved $f"
    fi
done

restore_backup() {
    for f in $saved; do
        name=$(basename "$f")
        if [ -f "$backup/$name" ]; then
            rm -f "$f"
            cp "$backup/$name" "$f"
            chmod "$(sed -n "s/^$name //p" "$backup/modes" 2>/dev/null)" "$f" 2>/dev/null
        fi
    done
    say "Rolled back to the previous wrapper."
}

# Unlink before writing: overwriting a mapped executable in place fails with
# ETXTBSY on linux and FreeBSD, and a cp that dies part-way leaves a half
# written file on the inode the next start will load. The mode of the file being
# replaced is kept rather than the release's, since an install's modes come from
# its own umask and the user it runs as.
install_one() {
    src=$1
    dst=$2
    if [ -f "$dst" ]; then
        mode=$(mode_of "$dst")
    else
        mode=$(mode_of "$src")
    fi
    # git carries no exec bit for a binary it has never run, so the launcher in
    # the tree is 644 and an install with no launcher yet would get an
    # unlaunchable one. Same for an install whose launcher lost the bit.
    if [ "$3" = x ] && ! has_x "$mode"; then
        mode=755
    fi
    run rm -f "$dst"
    run cp "$src" "$dst" || return 1
    run chmod "$mode" "$dst"
    if [ -z "$DRY" ] && ! cmp -s "$src" "$dst"; then
        return 1
    fi
    return 0
}

failed=""
install_one "$src_launcher" "$dst_launcher" x || failed="$failed $(basename "$dst_launcher")"
install_one "$src_lib" "$dst_lib" || failed="$failed $(basename "$dst_lib")"
install_one "$SRC_JAR" "$dst_jar" || failed="$failed wrapper.jar"

if [ -n "$failed" ]; then
    say "Failed to install:$failed" >&2
    if [ -z "$DRY" ]; then
        restore_backup
        start_service
    fi
    exit 1
fi

# The staged copy has done its job, and leaving it behind means the next run
# cannot tell an installed release from one still waiting to be installed.
if [ "$SOURCE" = "$I2P/lib/wrapper" ]; then
    run rm -rf "$I2P/lib/wrapper"
fi
run rm -rf "$backup"

start_service

if [ -z "$DRY" ] && [ -z "$NO_RESTART" ]; then
    # The wrapper names its own version on every start, so this reports what is
    # running rather than what was meant to be installed.
    log=$(sed -n 's/^wrapper\.logfile=//p' "$I2P/wrapper.config" 2>/dev/null | head -1)
    if [ -n "$log" ] && [ -f "$log" ]; then
        live=$(grep -o 'Java Service Wrapper[^,]*[0-9]\+\.[0-9]\+\.[0-9]\+' "$log" 2>/dev/null | tail -1)
        [ -n "$live" ] && say "Running: ${live#Java Service Wrapper }"
    fi
fi

say ""
say "Wrapper updated${version:+ to $version}."
