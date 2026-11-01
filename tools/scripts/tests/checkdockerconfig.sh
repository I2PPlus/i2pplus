#!/bin/sh
#
# Check the config-override helpers in docker/rootfs/startapp.sh.
#
# The container's overrides (EXTERNAL_PORT, IP_ADDR) have to land in the config
# directory the router reads, not only in the base-directory template the router
# copies on the first start of an empty volume. That distinction is invisible in
# a single run, so it is pinned here: the helpers are extracted from the script,
# run against a fake install in a temp directory, and checked for first-start
# seeding, later-start overriding, key appending, and leaving a file that does
# not exist alone.
#
# Returns nonzero on failure.
#
# public domain
#

# this script lives in tools/scripts/tests, so the repo root is three levels up
cd "$(dirname "$0")/../../.." || exit 1
set -u

SCRIPT=docker/rootfs/startapp.sh
TOTAL=0
FAIL=0

if [ ! -f "$SCRIPT" ]; then
  echo "! ${SCRIPT} not found." >&2
  exit 1
fi

# Take the helper block out of the script and define the functions here. The
# markers are in the script and name this test, so moving the helpers is a
# deliberate edit that fails loudly here rather than a silent loss of coverage.
helpers=$(sed -n '/^# --- config helpers /,/^# --- end config helpers ---/p' "$SCRIPT")
if [ -z "$helpers" ]; then
  echo "! No helper block found in ${SCRIPT} (the markers were removed?)." >&2
  exit 1
fi
# shellcheck disable=SC3037
eval "$helpers"

# A fake install: a base dir holding the packaged template, and a config dir
# that is empty (first start) or holds the router's own copy (later start). The
# helpers read I2P and HOME, so they are pointed at the fake install.
work=$(mktemp -d "${TMPDIR:-/tmp}/startapp-check.XXXXXX") || exit 1
trap 'rm -rf "$work"' EXIT HUP INT TERM

base="$work/i2p"
conf="$work/i2p/.i2p"
mkdir -p "$base" "$conf"
# The script sets HOME=/i2p, I2P=$HOME, and the config dir to $HOME/.i2p, so the
# fake install has to use the same shape for the helpers to resolve it.
# shellcheck disable=SC2034  # I2P and HOME are read by the extracted helpers
HOME="$base"
I2P="$base"

make_template() {
  cat >"$base/router.config" <<'EOF'
router.updateDisabled=true
i2np.ntcp.port=0
i2np.udp.port=0
i2np.udp.internalPort=0
i2p.dir.base=/i2p
i2p.dir.config=/i2p/.i2p
EOF
  cat >"$base/clients.config" <<'EOF'
# the console, non-SSL and SSL
clientApp.0.args=7657 127.0.0.1 -s 7667 127.0.0.1 ./webapps/
clientApp.12.args=sam.keys 127.0.0.1 7656
clientApp.3.name=Subscription service
EOF
}

# The router's own copy, as it stands after a first start on a random port that
# peer feedback has since moved, which is the state a reported fixed port was
# found in.
make_active() {
  cat >"$conf/router.config" <<'EOF'
router.updateDisabled=true
i2np.ntcp.port=0
i2np.udp.port=32644
i2np.udp.internalPort=13617
i2p.dir.base=/i2p
i2p.dir.config=/i2p/.i2p
EOF
  cat >"$conf/clients.config" <<'EOF'
# Modified by I2P User dir migration script
clientApp.0.args=7657 127.0.0.1 -s 7667 127.0.0.1 ./webapps/
clientApp.12.args=sam.keys 127.0.0.1 7656
EOF
}

ok() {
  TOTAL=$((TOTAL + 1))
  echo "> ok: $1"
}

fail() {
  TOTAL=$((TOTAL + 1))
  echo "! FAIL: $1"
  FAIL=$((FAIL + 1))
}

value_of() {
  # value_of <file> <key> - the last occurrence, as Java Properties reads it
  sed -n "s|^$2=||p" "$1" | tail -1
}

apply_port() {
  for file in $(override_targets); do
    if set_config "$file" i2np.udp.internalPort 20191 &&
       set_config "$file" i2np.udp.port 20191 &&
       set_config "$file" i2np.ntcp.port 20191 &&
       set_config "$file" i2np.udp.fixedPort true; then
      :
    fi
  done
}

apply_ip() {
  for file in $(console_targets); do
    set_console_ip "$file" 0.0.0.0
  done
}

make_template

# 1. an existing key is replaced, not appended to, and no duplicate is left
set_config "$base/router.config" i2np.udp.port 20191 >/dev/null 2>&1
if [ "$(value_of "$base/router.config" i2np.udp.port)" = "20191" ]; then
  ok "set_config replaces an existing key"
else
  fail "set_config did not replace an existing key"
fi
if [ "$(grep -c '^i2np.udp.port=' "$base/router.config")" = "1" ]; then
  ok "the replacement leaves one key line"
else
  fail "the replacement left duplicate key lines"
fi

# 2. a key the file does not have is appended
set_config "$base/router.config" i2np.udp.fixedPort true >/dev/null 2>&1
if [ "$(value_of "$base/router.config" i2np.udp.fixedPort)" = "true" ]; then
  ok "set_config appends a key the file lacks"
else
  fail "set_config did not append a key the file lacks"
fi

# 3. a file that is not there is not created, and the call says so, because a
#    config file created here would suppress the router's first-start copy of
#    the template and lose the rest of it
set_config "$conf/router.config" i2np.udp.port 20191 >/dev/null 2>&1
rc=$?
if [ "$rc" -ne 0 ]; then
  ok "set_config reports a missing file"
else
  fail "set_config claimed success on a missing file"
fi
if [ ! -f "$conf/router.config" ]; then
  ok "set_config does not create a missing file"
else
  fail "set_config created a config file the router should have copied"
fi

# 4. first start: the config dir is empty, so the template carries the port and
#    the config dir is left for the router to copy into
make_template
rm -f "$conf/router.config"
apply_port
if [ "$(value_of "$base/router.config" i2np.udp.internalPort)" = "20191" ]; then
  ok "first start seeds the template with the port"
else
  fail "first start did not seed the template with the port"
fi
if [ ! -f "$conf/router.config" ]; then
  ok "first start leaves the config dir to the router"
else
  fail "first start wrote into the config dir"
fi

# 5. later start: the active file holds the router's own random ports, and the
#    override has to reach it. This is the reported bug.
make_template
make_active
apply_port
if [ "$(value_of "$conf/router.config" i2np.udp.internalPort)" = "20191" ]; then
  ok "later start overrides the active internal port"
else
  fail "later start left the active internal port alone"
fi
if [ "$(value_of "$conf/router.config" i2np.udp.port)" = "20191" ]; then
  ok "later start overrides the active advertised port"
else
  fail "later start left the active advertised port alone"
fi
if [ "$(value_of "$conf/router.config" i2np.udp.fixedPort)" = "true" ]; then
  ok "later start pins the advertised port"
else
  fail "later start did not pin the advertised port"
fi
if [ "$(value_of "$base/router.config" i2np.ntcp.port)" = "20191" ]; then
  ok "later start keeps the template current too"
else
  fail "later start left the template stale"
fi

# 6. the console bind address: every clientApp args line, both addresses, and a
#    two-digit app number, since the pattern is POSIX basic regular expressions
make_template
rm -f "$conf/clients.config"
apply_ip
if grep -q '^clientApp\.0\.args=7657 0\.0\.0\.0 -s 7667 0\.0\.0\.0' "$base/clients.config"; then
  ok "first start binds the console in the template, both addresses"
else
  fail "first start did not bind the console in the template"
fi
if grep -q '^clientApp\.12\.args=sam\.keys 0\.0\.0\.0' "$base/clients.config"; then
  ok "the console rewrite covers a two-digit app number"
else
  fail "the console rewrite missed a two-digit app number"
fi
if grep -q '^clientApp\.3\.name=Subscription service' "$base/clients.config"; then
  ok "the console rewrite leaves a non-args line alone"
else
  fail "the console rewrite touched a non-args line"
fi

make_active
apply_ip
if grep -q '^clientApp\.0\.args=7657 0\.0\.0\.0' "$conf/clients.config"; then
  ok "later start binds the console in the active config"
else
  fail "later start left the active console bind address alone"
fi

# 7. no temp file is left behind in the install
if [ -z "$(find "$work" -name '*.startapp.*' -print | head -n 1)" ]; then
  ok "no temp file is left behind"
else
  fail "a temp file was left behind in the install"
fi

# 8. the script is Bourne-compatible, since the container runs busybox ash
if sh -n "$SCRIPT"; then
  ok "${SCRIPT} parses as POSIX shell"
else
  fail "${SCRIPT} is not Bourne-compatible"
fi

if [ "$FAIL" -ne 0 ]; then
  echo "******** ${FAIL} of ${TOTAL} checks failed ********"
else
  echo "> All ${TOTAL} checks passed"
fi
exit "$FAIL"
