#!/bin/bash
# install-i2psnark-browser-handler.sh
# Register I2PSnark as the handler for magnet links and .torrent files for the
# user running this script (the logged-in desktop user, who may differ from
# the account running the i2p router). Idempotent, no admin rights needed.
#
# Works on Linux, Windows (git-bash/msys), and macOS.
#
# Linux:   .desktop file + mimeapps.list + Firefox-family handlers.json seeding
# Windows: HKCU registry entries + Firefox-family handlers.json seeding
# macOS:   Firefox-family handlers.json seeding (LaunchServices has no CLI
#          scheme registration)
#
# Usage:   install-i2psnark-browser-handler.sh [--url BASE] [--jar PATH]
#          --url defaults to http://127.0.0.1:7657/i2psnark

set -euo pipefail

BASE_URL="http://127.0.0.1:7657/i2psnark"
JAR_PATH=""
while [ $# -gt 0 ]; do
    case "$1" in
        --url) BASE_URL="${2:?--url needs a value}"; shift 2 ;;
        --jar) JAR_PATH="${2:?--jar needs a value}"; shift 2 ;;
        *) echo "unknown option: $1" >&2; exit 1 ;;
    esac
done

log() { printf '\033[1;34m[i2psnark-handler]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[i2psnark-handler]\033[0m WARNING: %s\n' "$*"; }
die() { printf '\033[1;31m[i2psnark-handler]\033[0m ERROR: %s\n' "$*" >&2; exit 1; }

OS="$(uname -s 2>/dev/null || echo Windows)"
case "$OS" in
    MINGW*|MSYS*|CYGWIN*) PLATFORM=windows ;;
    Darwin*) PLATFORM=macos ;;
    *) PLATFORM=linux ;;
esac

if [ "$PLATFORM" = "windows" ]; then
    APPDATA_DIR="${APPDATA:-$HOME/AppData/Roaming}"
    LOCALAPPDATA_DIR="${LOCALAPPDATA:-$HOME/AppData/Local}"
    WRAPPER_DIR="$LOCALAPPDATA_DIR/i2psnark"
    WRAPPER="$WRAPPER_DIR/i2psnark-open.cmd"
    JAVA_BIN="java"
else
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        JAVA_BIN="$JAVA_HOME/bin/java"
    else
        JAVA_BIN="$(command -v java || echo java)"
    fi
    WRAPPER_DIR="${HOME}/.local/bin"
    WRAPPER="$WRAPPER_DIR/i2psnark-open"
fi

detect_jar() {
    [ -n "$JAR_PATH" ] && [ -r "$JAR_PATH" ] && { echo "$JAR_PATH"; return; }
    [ -n "${I2PSNARK_JAR:-}" ] && [ -r "$I2PSNARK_JAR" ] && { echo "$I2PSNARK_JAR"; return; }
    local d
    [ -n "${I2P:-}" ] && [ -r "$I2P/lib/i2psnark.jar" ] && { echo "$I2P/lib/i2psnark.jar"; return; }
    for d in "$HOME/i2p" "$HOME/.i2p" /home/i2p/i2p /usr/share/i2p /opt/i2p /var/lib/i2p; do
        if [ -r "$d/lib/i2psnark.jar" ]; then
            echo "$d/lib/i2psnark.jar"
            return
        fi
    done
    local f
    for f in "$HOME/i2p/lib/i2psnark.jar" /usr/share/i2p/lib/i2psnark.jar; do
        if [ -r "$f" ]; then
            echo "$f"
            return
        fi
    done
}

if [ -z "$JAR_PATH" ]; then
    JAR_PATH=$(detect_jar || true)
fi
if [ -z "$JAR_PATH" ] || [ ! -r "$JAR_PATH" ]; then
    die "cannot locate i2psnark.jar; pass --jar PATH"
fi
log "using jar: $JAR_PATH"
log "using url: $BASE_URL"

mkdir -p "$WRAPPER_DIR"

if [ "$PLATFORM" = "windows" ]; then
    cat > "$WRAPPER" <<EOF
@echo off
"$JAVA_BIN" -cp "$JAR_PATH" org.klomp.snark.MagnetHandler --url "$BASE_URL" %*
EOF
else
    cat > "$WRAPPER" <<EOF
#!/bin/sh
exec "$JAVA_BIN" -cp "$JAR_PATH" org.klomp.snark.MagnetHandler --url "$BASE_URL" "\$@"
EOF
    chmod 755 "$WRAPPER"
fi
log "wrapper: $WRAPPER"

PYTHON=$(command -v python3 || command -v python || true)
[ -z "$PYTHON" ] && warn "python3/python not found; skipping handlers.json seeding"

RUNNING=""
if [ "$PLATFORM" = "linux" ] || [ "$PLATFORM" = "macos" ]; then
    for p in librewolf firefox firefox-trunk firefox-nightly; do
        pgrep -x "$p" >/dev/null 2>&1 && RUNNING="$RUNNING $p"
    done
fi
[ -n "$RUNNING" ] && warn "running browsers (profiles skipped):$RUNNING"

if [ -n "$PYTHON" ]; then
    "$PYTHON" - "$HOME" "$WRAPPER" "$RUNNING" "$PLATFORM" <<'PYEOF'
import json
import os
import sys

home, wrapper, running, platform = sys.argv[1], sys.argv[2], sys.argv[3].split(), sys.argv[4]
handler = {"name": "I2PSnark Browser API", "path": wrapper}
TORRENT = "application/x-bittorrent"

def profile_roots():
    roots = []
    if platform in ("linux", "macos"):
        librewolf = os.path.join(home, ".librewolf")
        if platform == "macos":
            librewolf = os.path.join(home, "Library", "Application Support", "LibreWolf")
        firefox = os.path.join(home, ".mozilla", "firefox")
        if platform == "macos":
            firefox = os.path.join(home, "Library", "Application Support", "Firefox")
        roots = [(librewolf, "librewolf"), (firefox, "firefox")]
    elif platform == "windows":
        appdata = os.environ.get("APPDATA", os.path.join(home, "AppData", "Roaming"))
        roots = [(os.path.join(appdata, "LibreWolf", "Profiles"), "librewolf"),
                 (os.path.join(appdata, "Mozilla", "Firefox", "Profiles"), "firefox")]
    return roots

def profile_dirs(root):
    found = []
    ini = os.path.join(root, "profiles.ini")
    if not os.path.exists(ini):
        return found
    cur = None
    for line in open(ini, encoding="utf-8", errors="replace"):
        line = line.strip()
        if line.startswith("[") and line.endswith("]"):
            cur = line[1:-1]
        elif line.startswith("Path=") and cur:
            p = line.split("=", 1)[1].strip()
            if not os.path.isabs(p):
                p = os.path.join(root, p)
            found.append(p)
    return found

def seed_handlers_json(path):
    data = {}
    if os.path.exists(path):
        try:
            data = json.load(open(path, encoding="utf-8"))
        except (ValueError, OSError):
            data = {}
    mime = data.setdefault("mimeTypes", {})
    schemes = data.setdefault("schemes", {})
    changed = False
    if "x-scheme-handler/magnet" in schemes and "magnet" not in schemes:
        schemes["magnet"] = schemes.pop("x-scheme-handler/magnet")
        changed = True
    for typ, store in ((TORRENT, mime), ("magnet", schemes)):
        entry = store.get(typ, {})
        existing = entry.get("handlers", []) or []
        cleaned = []
        seen = set()
        for h in existing:
            if not isinstance(h, dict):
                cleaned.append(h)
                continue
            key = (h.get("path"), h.get("command"), h.get("uriTemplate"))
            if key in seen:
                continue
            seen.add(key)
            p = h.get("path")
            if p is not None and not os.path.exists(p):
                continue
            cleaned.append(h)
        if cleaned != existing:
            changed = True
        if cleaned and cleaned[0].get("path") == wrapper:
            if changed:
                entry["handlers"] = cleaned
                store[typ] = entry
            continue
        entry["handlers"] = [handler] + cleaned
        entry["action"] = 2
        entry.pop("ask", None)
        if typ == TORRENT:
            exts = entry.setdefault("extensions", [])
            if "torrent" not in exts:
                exts.append("torrent")
        store[typ] = entry
        changed = True
    if changed:
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)
            f.write("\n")
        os.replace(tmp, path)
        print("seeded:", os.path.relpath(path, home))

for root, browser in profile_roots():
    if not os.path.isdir(root):
        continue
    for pdir in profile_dirs(root):
        hp = os.path.join(pdir, "handlers.json")
        if not os.path.exists(hp) and not os.path.exists(os.path.join(pdir, "prefs.js")):
            continue
        if browser in running:
            print("SKIP (browser running):", hp)
            continue
        seed_handlers_json(hp)
PYEOF
fi

if [ "$PLATFORM" = "linux" ]; then
    APPS_ID="i2psnark-browserapi.desktop"
    APPS_DIR="$HOME/.local/share/applications"
    mkdir -p "$APPS_DIR"
    cat > "$APPS_DIR/$APPS_ID" <<EOF
[Desktop Entry]
Version=1.0
Type=Application
Name=I2PSnark Browser API
Comment=Send magnet links and torrent files to I2PSnark
Exec="$WRAPPER" %u
Terminal=false
MimeType=x-scheme-handler/magnet;application/x-bittorrent;
Categories=Network;
EOF
    update-desktop-database "$APPS_DIR" >/dev/null 2>&1 || true
    MIMEAPPS="$HOME/.config/mimeapps.list"
    mkdir -p "$(dirname "$MIMEAPPS")"
    touch "$MIMEAPPS"
    if [ -n "$PYTHON" ]; then
        "$PYTHON" - "$MIMEAPPS" "$APPS_ID" <<'PYEOF'
import os
import sys

path, apps_id = sys.argv[1], sys.argv[2]
mimes = ("x-scheme-handler/magnet", "application/x-bittorrent")
lines = open(path, encoding="utf-8").read().splitlines()
sections = []
cur = None
for raw in lines:
    s = raw.strip()
    if s.startswith("[") and s.endswith("]"):
        cur = s[1:-1].strip()
        sections.append((cur, []))
    elif cur is not None:
        sections[-1][1].append(raw)
    else:
        sections.append((None, [raw]))

def ensure(section, key, value):
    for name, ls in sections:
        if name != section:
            continue
        for i, line in enumerate(ls):
            if line.split("=", 1)[0].strip() == key:
                if line.split("=", 1)[1].strip() != value:
                    ls[i] = key + "=" + value
                    return True
                return False
        ls.append(key + "=" + value)
        return True
    sections.append((section, [key + "=" + value]))
    return True

changed = False
for name, ls in sections:
    if name not in ("Default Applications", "Added Associations", "Removed Associations"):
        continue
    for i, line in enumerate(list(ls)):
        if "=" not in line:
            continue
        key, _, value = line.partition("=")
        key = key.strip()
        if name == "Removed Associations" and "i2psnark" in value.lower():
            ls[i] = None
            changed = True
        elif name == "Added Associations" and key in mimes:
            apps = [a for a in value.split(";") if a and a.strip() != apps_id
                    and not a.startswith("userapp-i2psnark")]
            new = ";".join([apps_id] + apps) + ";"
            if new != value:
                ls[i] = key + "=" + new
                changed = True
        elif name == "Default Applications" and key in mimes:
            if "userapp-i2psnark" in value or value.strip() != apps_id:
                ls[i] = key + "=" + apps_id
                changed = True
for name, ls in sections:
    if name is None:
        continue
    ls[:] = [l for l in ls if l is not None]
for section, key, value in (("Added Associations", "x-scheme-handler/magnet", apps_id + ";"),
                            ("Added Associations", "application/x-bittorrent", apps_id + ";"),
                            ("Default Applications", "x-scheme-handler/magnet", apps_id),
                            ("Default Applications", "application/x-bittorrent", apps_id)):
    if ensure(section, key, value):
        changed = True
if changed:
    with open(path, "w", encoding="utf-8") as f:
        for name, ls in sections:
            if name is not None:
                f.write("[" + name + "]\n")
            for l in ls:
                f.write(l + "\n")
            if ls:
                f.write("\n")
    print("mimeapps.list updated")
PYEOF
    fi
    for mt in x-scheme-handler/magnet application/x-bittorrent; do
        out=$(gio mime "$mt" 2>/dev/null || true)
        if printf '%s' "$out" | grep -q "$APPS_ID"; then
            log "OK  $mt -> $APPS_ID"
        else
            warn "$mt not (yet) mapped to $APPS_ID"
        fi
    done
fi

if [ "$PLATFORM" = "windows" ]; then
    TORRENT_ID="I2PSnarkTorrent"
    reg add "HKCU\\Software\\Classes\\magnet\\shell\\open\\command" //ve //d "\"$WRAPPER\" \"%1\"" //f >/dev/null 2>&1 \
        || reg add "HKCU\\Software\\Classes\\magnet\\shell\\open\\command" /ve /d "\"$WRAPPER\" \"%1\"" /f >/dev/null 2>&1 \
        || warn "reg add failed for magnet scheme"
    reg add "HKCU\\Software\\Classes\\.torrent" //ve //d "$TORRENT_ID" //f >/dev/null 2>&1 \
        || reg add "HKCU\\Software\\Classes\\.torrent" /ve /d "$TORRENT_ID" /f >/dev/null 2>&1
    reg add "HKCU\\Software\\Classes\\$TORRENT_ID\\shell\\open\\command" //ve //d "\"$WRAPPER\" \"%1\"" //f >/dev/null 2>&1 \
        || reg add "HKCU\\Software\\Classes\\$TORRENT_ID\\shell\\open\\command" /ve /d "\"$WRAPPER\" \"%1\"" /f >/dev/null 2>&1
    log "registry entries written (HKCU)"
fi

log "done. Re-run after closing any running LibreWolf/Firefox to seed their profiles."