#!/bin/sh
#
# Test harness for installer/resources/platform-specific/unix/i2prouter.
#
# The script is the daemon control script, so a mistake in it stops the router
# from starting or misreports its state. These checks are deliberately cheap and
# hermetic: no router is started, no network is touched, and every artefact is
# written under a temporary directory rather than into the workspace.
#
# Usage:
#   tools/scripts/i2prouter-test.sh              # run everything
#   tools/scripts/i2prouter-test.sh PATH         # check a specific copy
#   tools/scripts/i2prouter-test.sh --shellcheck # add shellcheck if installed
#
# Exit status is 0 only when every check passes.

set -u

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
# Defaults to the source copy, but accepts a path so an installed, substituted
# build can be verified before it is put in place.
TARGET=${1:-"$REPO_ROOT/installer/resources/platform-specific/unix/i2prouter"}

PASSED=0
FAILED=0
TMPROOT=""

cleanup() {
    if [ -n "$TMPROOT" ] && [ -d "$TMPROOT" ]
    then
        rm -rf "$TMPROOT"
    fi
}
trap cleanup EXIT INT TERM

pass() {
    PASSED=`expr $PASSED + 1`
    echo "  ok   $1"
}

fail() {
    FAILED=`expr $FAILED + 1`
    echo "  FAIL $1"
    if [ -n "${2:-}" ]
    then
        echo "       $2"
    fi
}

# assert_eq EXPECTED ACTUAL DESCRIPTION
assert_eq() {
    if [ "$1" = "$2" ]
    then
        pass "$3"
    else
        fail "$3" "expected [$1] got [$2]"
    fi
}

# assert_contains HAYSTACK NEEDLE DESCRIPTION
assert_contains() {
    case "$1" in
        *"$2"*)
            pass "$3"
            ;;
        *)
            fail "$3" "[$2] not found in [$1]"
            ;;
    esac
}

# assert_absent HAYSTACK NEEDLE DESCRIPTION
assert_absent() {
    case "$1" in
        *"$2"*)
            fail "$3" "[$2] unexpectedly present"
            ;;
        *)
            pass "$3"
            ;;
    esac
}

newtmp() {
    TMPROOT=`mktemp -d "${TMPDIR:-/tmp}/i2prouter-test.XXXXXX"`
}

echo "== i2prouter test harness =="
if [ ! -f "$TARGET" ]
then
    echo "  FAIL cannot find $TARGET"
    exit 1
fi

#---------------------------------------------------------------------------
echo "-- syntax --"
#---------------------------------------------------------------------------
if sh -n "$TARGET" 2>/dev/null
then
    pass "sh -n parses the script"
else
    syntax_err=$(sh -n "$TARGET" 2>&1 | head -3)
    fail "sh -n parses the script" "$syntax_err"
fi

# POSIX shell portability: bash -n accepts more than sh -n does.
if command -v dash >/dev/null 2>&1
then
    if dash -n "$TARGET" >/dev/null 2>&1
    then
        pass "dash -n parses the script (POSIX portability)"
    else
        fail "dash -n parses the script (POSIX portability)"
    fi
fi

#---------------------------------------------------------------------------
echo "-- helper functions --"
#---------------------------------------------------------------------------
# Source only the helper block so the daemon paths are not executed.
load_helpers() {
    sed -n '/^format_uptime()/,/^}/p'                       "$TARGET"
    sed -n '/^format_memory()/,/^}/p'                      "$TARGET"
    sed -n '/^parse_max_heap()/,/^}/p'                     "$TARGET"
    sed -n '/^read_status_file()/,/^}/p'                   "$TARGET"
    sed -n '/^getjavapid()/,/^}/p'                         "$TARGET"
    sed -n '/^get_running_version()/,/^}/p'               "$TARGET"
    sed -n '/^openconsolebrowser()/,/^}/p'                "$TARGET"
}

eval "`load_helpers`"

# format_uptime
assert_eq "00:00:00" "`format_uptime 0`"          "format_uptime 0"
assert_eq "00:00:59" "`format_uptime 59`"         "format_uptime 59"
assert_eq "01:00:00" "`format_uptime 3600`"       "format_uptime 1h"
assert_eq "02:38:22" "`format_uptime 9502`"       "format_uptime 2h38m22s"
assert_eq "1d 00:00:00" "`format_uptime 86400`"    "format_uptime exactly 1 day"
assert_eq "1d 01:01:01" "`format_uptime 90061`"    "format_uptime 1d1h1m1s"
format_uptime abc  >/dev/null 2>&1 && fail "format_uptime rejects non-numeric" || pass "format_uptime rejects non-numeric"
format_uptime ""   >/dev/null 2>&1 && fail "format_uptime rejects empty"      || pass "format_uptime rejects empty"

# format_memory
assert_eq "0M"     "`format_memory 0`"        "format_memory 0"
assert_eq "512M"   "`format_memory 524288`"   "format_memory 512 MiB"
assert_eq "1023M"  "`format_memory 1048575`"  "format_memory just under 1 GiB truncates"
assert_eq "1.0G"   "`format_memory 1048576`"  "format_memory exactly 1 GiB"
assert_eq "2.6G"   "`format_memory 2810096`"  "format_memory 2.6 GiB"
format_memory xyz >/dev/null 2>&1 && fail "format_memory rejects non-numeric"  || pass "format_memory rejects non-numeric"

# parse_max_heap
assert_eq "8192"  "`parse_max_heap 'java -Xms1024m -Xmx8192m -Dx=1'`" "parse_max_heap -Xmx8192m"
assert_eq "512"   "`parse_max_heap 'java -Xmx512m'`"                  "parse_max_heap -Xmx512m"
assert_eq "2048"  "`parse_max_heap 'java -Xmx2g'`"                    "parse_max_heap -Xmx2g"
assert_eq "2048"  "`parse_max_heap 'java -Xmx2048M'`"                 "parse_max_heap -Xmx2048M is case-insensitive"
assert_eq "1048576" "`parse_max_heap 'java -Xmx1048576'`"             "parse_max_heap bare byte count"
parse_max_heap 'java -Dx=1' >/dev/null 2>&1 && fail "parse_max_heap returns 1 when unset" || pass "parse_max_heap returns 1 when unset"
parse_max_heap 'java -XmxZZ'  >/dev/null 2>&1 && fail "parse_max_heap returns 1 on garbage" || pass "parse_max_heap returns 1 on garbage"
# A bare -Xmxs (sizing flag) must not be mistaken for the maximum.
parse_max_heap 'java -Xmxs512m' >/dev/null 2>&1 && fail "parse_max_heap ignores -Xmxs" || pass "parse_max_heap ignores -Xmxs"

#---------------------------------------------------------------------------
echo "-- read_status_file distinguishes its three outcomes --"
#---------------------------------------------------------------------------
newtmp
printf 'STARTED' > "$TMPROOT/present"
: > "$TMPROOT/empty"
printf 'STARTED' > "$TMPROOT/unreadable"
chmod 000 "$TMPROOT/unreadable"

out=`read_status_file "$TMPROOT/present"`; rc=$?
assert_eq "0" "$rc"                 "read_status_file: present returns 0"
assert_eq "STARTED" "$out"         "read_status_file: present echoes contents"

out=`read_status_file "$TMPROOT/absent"`; rc=$?
assert_eq "1" "$rc"                 "read_status_file: absent returns 1"

if [ "`id -u`" = "0" ]
then
    echo "  skip read_status_file unreadable case (running as root, chmod 000 is still readable)"
else
    out=`read_status_file "$TMPROOT/unreadable"`; rc=$?
    assert_eq "2" "$rc"             "read_status_file: unreadable returns 2"
fi

out=`read_status_file "$TMPROOT/empty"`; rc=$?
assert_eq "0" "$rc"                 "read_status_file: empty file is a successful read"
assert_eq "" "$out"                 "read_status_file: empty file echoes nothing"

#---------------------------------------------------------------------------
echo "-- getjavapid never returns a non-JVM --"
#---------------------------------------------------------------------------
newtmp
# A stub ps that reports a single process whose command line mentions the
# install path but is NOT a java launcher. A loose matcher returns this pid,
# which would make status report a shell's memory as the JVM's.
cat > "$TMPROOT/ps" <<'STUB'
#!/bin/sh
# Minimal ps stub for getjavapid. Reports the PID/PPID listing when asked for
# ppid, and the PID/args listing otherwise.
want_ppid=0
for arg in "$@"
do
    case "$arg" in
        ppid*) want_ppid=1 ;;
    esac
done
if [ "$want_ppid" = "1" ]
then
    echo "   111    1"
else
    echo " 222 /bin/bash -c i2prouter --status /home/i2p/i2p"
    echo " 333 /usr/lib/jvm/java/bin/java -Di2p.dir.base=/home/i2p/i2p router.jar"
fi
STUB
chmod +x "$TMPROOT/ps"

# Each case runs a generated runner: sourcing the function into the harness and
# exercising it there would leave our own $pid and $PSEXE in scope.
run_javapid_case() {
    # $1 = wrapper pid to set, $2 = description
    cat > "$TMPROOT/runner.sh" <<RUNNER
#!/bin/sh
$(sed -n '/^getjavapid()/,/^}/p' "$TARGET")
PSEXE="$TMPROOT/ps"
pid="$1"
getjavapid
printf '%s' "\$javapid"
RUNNER
    sh "$TMPROOT/runner.sh"
}

assert_eq "333" "`run_javapid_case 111`" \
    "getjavapid rejects a shell that merely mentions the path"
assert_eq "333" "`run_javapid_case 999`" \
    "getjavapid falls back to the JVM when it is not a direct child"
assert_eq ""    "`run_javapid_case ''`"  \
    "getjavapid returns nothing when there is no wrapper pid"

#---------------------------------------------------------------------------
echo "-- testpid ends the stop wait loop --"
#---------------------------------------------------------------------------
# stopit() holds $pid from one getpid() call and relies on testpid to clear it
# once the wrapper is gone. When testpid cannot re-check the process the loop
# never exits and "Waiting for $APP_LONG_NAME to exit" repeats forever, long
# after the router has stopped.
newtmp
cat > "$TMPROOT/ps" <<'STUB'
#!/bin/sh
# Minimal ps stub for wrapper_running/testpid. Reports a process only for the
# pid in WRAPPER_STUB_PID, and only when WRAPPER_STUB_ALIVE is 1. With
# WRAPPER_STUB_OTHER set it reports a live process that is not the wrapper,
# which is what a recycled pid looks like.
target=""
prev=""
for arg in "$@"
do
    case "$prev" in
        -p) target="$arg" ;;
    esac
    prev="$arg"
done
[ -n "$target" ] || exit 0
[ "${WRAPPER_STUB_ALIVE:-1}" = "1" ] || exit 0
[ "$target" = "${WRAPPER_STUB_PID:-}" ] || exit 0
echo "COMMAND"
if [ -n "${WRAPPER_STUB_OTHER:-}" ]
then
    echo "$target /usr/bin/some-other-daemon --worker"
else
    echo "$target /bin/sh $WRAPPER_CMD start"
fi
STUB
chmod +x "$TMPROOT/ps"

# $1 = wrapper pid to set, $2 = description. Prints $pid after testpid, then
# whether the pid file survived. Each case runs in its own shell so our own
# $pid and $PSEXE stay out of scope.
run_testpid_case() {
    cat > "$TMPROOT/runner.sh" <<RUNNER
#!/bin/sh
`sed -n '/^wrapper_running()/,/^}/p' "$TARGET"`
`sed -n '/^testpid()/,/^}/p' "$TARGET"`
PSEXE="$TMPROOT/ps"
DIST_OS=linux
WRAPPER_CMD="\$WRAPPER_CMD"
WRAPPER_STUB_PID="\$WRAPPER_STUB_PID"
WRAPPER_STUB_ALIVE="\$WRAPPER_STUB_ALIVE"
WRAPPER_STUB_OTHER="\$WRAPPER_STUB_OTHER"
export WRAPPER_CMD WRAPPER_STUB_PID WRAPPER_STUB_ALIVE WRAPPER_STUB_OTHER
pid="$1"
testpid
if [ -f "\$PIDFILE" ]
then
    kept=pidfile-kept
else
    kept=pidfile-removed
fi
printf '%s|%s' "\${pid:-GONE}" "\$kept"
RUNNER
    PIDFILE="$TMPROOT/i2p.pid" WRAPPER_CMD="$TMPROOT/i2psvc" \
    WRAPPER_STUB_PID="$2" WRAPPER_STUB_ALIVE="$3" WRAPPER_STUB_OTHER="$4" \
        sh "$TMPROOT/runner.sh"
}

printf '4242' > "$TMPROOT/i2p.pid"
assert_eq "4242|pidfile-kept" "`run_testpid_case 4242 4242 1 ''`" \
    "testpid keeps a live wrapper"

printf '4242' > "$TMPROOT/i2p.pid"
assert_eq "GONE|pidfile-removed" "`run_testpid_case 4242 4242 0 ''`" \
    "testpid clears the pid and removes the pid file once the wrapper exits"

printf '4242' > "$TMPROOT/i2p.pid"
assert_eq "GONE|pidfile-removed" "`run_testpid_case 4242 4242 1 other`" \
    "testpid clears a pid that no longer belongs to the wrapper"

printf '4242' > "$TMPROOT/i2p.pid"
assert_eq "GONE|pidfile-removed" "`run_testpid_case '' 4242 1 ''`" \
    "testpid with no pid is a no-op that still tidies the pid file"

# The wait loop terminates on liveness alone; it must not depend on the pid file
# surviving, because the wrapper removes that itself on the way out.
loop_body=`sed -n '/^stopit()/,/^}/p' "$TARGET" | sed -n '/while \[ "X\$pid" != "X" \]/,/done/p'`
if [ -n "$loop_body" ] && printf '%s\n' "$loop_body" | grep -q 'testpid'
then
    pass "stopit() wait loop re-checks the pid through testpid"
else
    fail "stopit() wait loop re-checks the pid through testpid"
fi

#---------------------------------------------------------------------------
echo "-- obsolete platform code is gone --"
#---------------------------------------------------------------------------
for pattern in ia64 pa_risc parisc sparc isainfo KERNEL_BIT ip27 i386 i686 armel cpu64bit_capable hp-ux unix_sv "os/390" kfreebsd solaris sunos aix zos; do
    if grep -q "$pattern" "$TARGET"
    then
        fail "no references to $pattern"
    else
        pass "no references to $pattern"
    fi
done

# The daemon install/remove chains must keep exactly one head. Removing a
# branch whose terminator was an elif has to promote the next branch to if, or
# the chain is left with no head - and sh -n still accepts that.
for fn in installdaemon removedaemon; do
    chain=$(sed -n "/^$fn() {/,/^}/p" "$TARGET")
    heads=$(printf '%s\n' "$chain" | grep -c '^ *if \[ "\$DIST_OS"')
    elifs=$(printf '%s\n' "$chain" | grep -c '^ *elif \[ "\$DIST_OS"')
    if [ "$heads" = "1" ]
    then
        pass "$fn() DIST_OS chain has one head ($elifs elif)"
    else
        fail "$fn() DIST_OS chain has one head" "found $heads heads, $elifs elif"
    fi
done

# The 32-bit fallback path must be gone from the architecture block.
if sed -n '/^# Resolve the architecture/,/^# Through Java 6/p' "$TARGET" | grep -q 'DIST_BITS="32"'
then
    fail "architecture resolution is 64-bit only"
else
    pass "architecture resolution is 64-bit only"
fi

#---------------------------------------------------------------------------
echo "-- dead code --"
#---------------------------------------------------------------------------
# pause() and resume() were no-op stubs that only echoed. Either they are gone
# or they now do something; an echo-only stub must not come back.
for fn in pause resume; do
    body=`sed -n "/^$fn()/,/^}/p" "$TARGET"`
    if [ -z "$body" ]
    then
        pass "$fn() removed"
    else
        echoes=`printf '%s' "$body" | grep -c 'echo'`
        lines=`printf '%s' "$body" | grep -c '[^[:space:]]'`
        if [ "$echoes" -ge "$lines" ]
        then
            fail "$fn() is an echo-only stub again"
        else
            pass "$fn() does real work"
        fi
    fi
done

#---------------------------------------------------------------------------
echo "-- formatting --"
#---------------------------------------------------------------------------
if grep -nP '[ \t]+$' "$TARGET" >/dev/null 2>&1
then
    fail "no trailing whitespace" "`grep -nP '[ \t]+$' "$TARGET" | head -3`"
else
    pass "no trailing whitespace"
fi

if [ -z "$(tail -c1 "$TARGET")" ]
then
    pass "file ends with a newline"
else
    fail "file ends with a newline"
fi

# Indentation must be a multiple of four spaces.
# Scoped to the helper and status blocks: the rest of the file predates this
# convention and reindenting it would bury the real change in noise.
_region=$(sed -n '/^# Status reporting helpers/,/^outputFile/p' "$TARGET")
_region=$(sed -n '/^status()/,/^}/p' "$TARGET")
# The [^ ] anchor matters: without it "one to three spaces" also matches a
# line indented four spaces, since the pattern need not span the whole indent.
odd=$(printf '%s\n' "$_region" | grep -n '^ \{1,3\}[^ ]\|^ \{5,7\}[^ ]\|^ \{9,11\}[^ ]' | head -3)
if [ -n "$odd" ]
then
    fail "indentation is 4-space aligned" "$odd"
else
    pass "indentation is 4-space aligned"
fi

#---------------------------------------------------------------------------
echo "-- shellcheck (optional) --"
#---------------------------------------------------------------------------
if command -v shellcheck >/dev/null 2>&1
then
    # Advisory, not a gate. This is a legacy file carrying many pre-existing
    # findings; pinning them down means either an exclusion list that grows
    # every time an unrelated old line is touched, or blanket silence. The
    # functional assertions above are the regression gate; shellcheck is run so
    # a new finding still shows up in the output.
    out=$(shellcheck -s sh -f gcc "$TARGET" 2>&1)
    if [ -z "$out" ]
    then
        echo "  ok   shellcheck reports nothing"
    else
        echo "  note shellcheck findings (pre-existing, advisory):"
        printf '%s\n' "$out" | grep -oE "SC[0-9]+" | sort | uniq -c | sed 's/^/       /'
    fi
else
    echo "  skip shellcheck (not installed)"
fi

#---------------------------------------------------------------------------
echo "-- end-to-end: status through the real entry point --"

# The unit assertions above source individual functions. They cannot see the
# script's entry point, and that is where both real defects lived: a misordered
# runuser re-exec that only fires for root, and a dispatch placed after an
# early exit. This runs the file as a program instead.
e2e_dir=$(mktemp -d "${TMPDIR:-/tmp}/i2prouter-e2e.XXXXXX")
mkdir -p "$e2e_dir/cfg"
: > "$e2e_dir/wrapper.config"
# status never launches the wrapper, but the script insists the binary exists
# and runs ldd over it. A shell script fails that check as "not a dynamic
# executable", so use a real dynamically linked binary.
cp /bin/true "$e2e_dir/i2psvc"
chmod +x "$e2e_dir/i2psvc"
# PSEXE is a hardcoded absolute path, so PATH redirection cannot fake ps. Point
# it at a stub instead: getpid validates the pid file by matching WRAPPER_CMD in
# the process args, and deletes the pid file as stale when it does not match.
cat > "$e2e_dir/ps" <<PSSTUB
#!/bin/sh
for _a in "\$@"; do
    case "\$_a" in
        args)    echo "$e2e_dir/i2psvc $e2e_dir/wrapper.config"; exit 0 ;;
        etimes=) echo 3600; exit 0 ;;
    esac
done
exit 0
PSSTUB
chmod +x "$e2e_dir/ps"

e2e_pid=4242
printf '%s\n' "$e2e_pid" > "$e2e_dir/cfg/i2p.pid"
printf 'STARTED' > "$e2e_dir/cfg/i2p.status"
printf 'STARTED' > "$e2e_dir/cfg/i2p.java.status"

# Replace the absolute runuser path with a stub so the root re-exec path can be
# exercised without root, and so the arguments it builds can be inspected.
cat > "$e2e_dir/runuser" <<STUB
#!/bin/sh
printf '%s\n' "\$@" > "$e2e_dir/runuser.argv"
shift 3
exec "\$@"
STUB
chmod +x "$e2e_dir/runuser"

# The source copy carries build-time placeholders; resolve them into the sandbox
# exactly as an installed copy would be, so the entry point sees a real layout.
# Three overrides are not placeholder substitutions:
#   I2P/I2PTEMP - an already-substituted target has no %INSTALL_PATH left to
#     replace, and without this the sandbox reads the real router's pid/status
#     files. Assignments may be indented, so match leading whitespace, not ^.
#   RUN_AS_USER - the runuser stub cannot become another user, so a mismatch
#     would re-exec forever; the invocation form is asserted statically below.
sed -e "s#/sbin/runuser#$e2e_dir/runuser#" \
    -e "s#%INSTALL_PATH#$e2e_dir#g" \
    -e "s#%USER_HOME#$e2e_dir#g" \
    -e "s#%SYSTEM_java_io_tmpdir#$e2e_dir#g" \
    -e "s#PSEXE=\"[^\"]*\"#PSEXE=\"$e2e_dir/ps\"#g" \
    -e "s#^[[:space:]]*I2P_CONFIG_DIR=.*#I2P_CONFIG_DIR=\"$e2e_dir/cfg\"#" \
    -e "s#^[[:space:]]*I2P=.*#I2P=\"$e2e_dir\"#" \
    -e "s#^[[:space:]]*I2PTEMP=.*#I2PTEMP=\"$e2e_dir\"#" \
    -e "s#^RUN_AS_USER=.*#RUN_AS_USER=\$(id -un)#" \
    "$TARGET" > "$e2e_dir/i2prouter"
chmod +x "$e2e_dir/i2prouter"

# Bound the entry point: a re-exec mistake must fail fast, not hang the suite.
e2e_out=$(cd "$e2e_dir" && timeout 60 sh ./i2prouter status 2>&1)
e2e_rc=$?

if [ "$e2e_rc" -eq 0 ]
then
    pass "status exits 0 through the real entry point"
else
    fail "status exits 0 through the real entry point" "rc=$e2e_rc: $(printf '%s' "$e2e_out" | head -2)"
fi
assert_contains "$e2e_out" "is running: PID:" "status reports the wrapper pid"
assert_contains "$e2e_out" "Uptime:" "status reports uptime"
assert_contains "$e2e_out" "JVM:" "status reports JVM state"

# The two wrapper-reported statuses belong together, Java reported second.
assert_contains "$e2e_out" "Wrapper:      STARTED
  Java status:  STARTED" "wrapper and java status are adjacent, java second"

# The re-exec form is what actually regressed: runuser's -c/-f/-l/-s are mutually
# exclusive with -u, so the command has to be positional. When -c landed after
# the user it became an argument instead and the shell died with
# "Syntax error: word unexpected". The re-exec only fires when the invoking user
# differs from RUN_AS_USER and the sandbox cannot become another user, so assert
# the invocation form statically and check the recorded argv when it is available.
_script_text=`cat "$TARGET"`
assert_contains "$_script_text" 'runuser -u "$RUN_AS_USER" -- "$REALPATH"' \
    "re-exec passes the command positionally after --"
case "$_script_text" in
    *'runuser -u "$RUN_AS_USER" -c'*|*'runuser -u "$RUN_AS_USER" -s'*)
        fail "re-exec never combines -u with -c/-s" "found the broken form"
        ;;
    *)
        pass "re-exec never combines -u with -c/-s"
        ;;
esac
if [ -f "$e2e_dir/runuser.argv" ]
then
    _argv=`tr '\n' ' ' < "$e2e_dir/runuser.argv"`
    assert_contains "$_argv" "status" "re-exec passes the command through"
    assert_absent "$_argv" " -c " "recorded re-exec argv carries no -c"
fi
rm -rf "$e2e_dir"

#---------------------------------------------------------------------------
echo
# --- running-version resolution -------------------------------------------
# router.info keeps the version of whichever build created the keys, so status
# must ask the live router and only fall back to the file.
mk_version_fixture() {
    _V="$1"; _P="$2"
    mkdir -p "$_V/bin" "$_V/router"
    cat > "$_V/bin/curl" <<EOF
#!/bin/sh
cat <<'HTMLEOF'
<tr title="The version of the I2P software we are running"><td><a href=/configupdate><b>Version</b></a></td><td class=digits><span>$_P</span></td></tr>
<tr title="other"><td><span>9.9.9-9+</span></td></tr>
HTMLEOF
EOF
    chmod +x "$_V/bin/curl"
    printf 'hash=abc1234\nversion=0.0.1-??\n' > "$_V/router/router.info"
}

VF=$(mktemp -d)
mk_version_fixture() {
    _V="$1"; _P="$2"
    mkdir -p "$_V/bin" "$_V/router"
    cat > "$_V/bin/curl" <<EOF
#!/bin/sh
cat <<'HTMLEOF'
<tr title="The version of the I2P software we are running"><td><a href=/configupdate><b>Version</b></a></td><td class=digits><span>$_P</span></td></tr>
<tr title="unrelated row"><td><span>9.9.9-9+</span></td></tr>
HTMLEOF
EOF
    chmod +x "$_V/bin/curl"
    printf 'hash=abc1234\nversion=0.0.1-??\n' > "$_V/router/router.info"
}

# live console wins over the stale router.info
mk_version_fixture "$VF" "2.13.0-22+"
assert_eq "2.13.0-22+" "`( export PATH="$VF/bin:$PATH" I2P="$VF/router"
    CONSOLE_URL="http://127.0.0.1:7657"; get_running_version )`" \
    "get_running_version prefers the live router over stale router.info"

# console down -> fall back to router.info
mk_version_fixture "$VF" "2.13.0-22+"
printf '#!/bin/sh\nexit 7\n' > "$VF/bin/curl"; chmod +x "$VF/bin/curl"
assert_eq "0.0.1-??" "`( export PATH="$VF/bin:$PATH" I2P="$VF/router"
    CONSOLE_URL="http://127.0.0.1:7657"; get_running_version )`" \
    "get_running_version falls back to router.info when the console is down"

# a release with no build suffix still parses
mk_version_fixture "$VF" "2.13.0"
assert_eq "2.13.0" "`( export PATH="$VF/bin:$PATH" I2P="$VF/router"
    CONSOLE_URL="http://127.0.0.1:7657"; get_running_version )`" \
    "get_running_version accepts a version with no build suffix"

# the row scope must not leak a version from a neighbouring row
mk_version_fixture "$VF" "2.13.0-22+"
assert_eq "2.13.0-22+" "`( export PATH="$VF/bin:$PATH" I2P="$VF/router"
    CONSOLE_URL="http://127.0.0.1:7657"; get_running_version )`" \
    "get_running_version is scoped to the version row"

# console browser opener selection
printf '#!/bin/sh\nexit 0\n' > "$VF/bin/xdg-open"; chmod +x "$VF/bin/xdg-open"
printf '#!/bin/sh\nexit 0\n' > "$VF/bin/open";    chmod +x "$VF/bin/open"
assert_eq "0" "`( export PATH="$VF/bin:$PATH" DIST_OS=linux
    CONSOLE_URL="http://127.0.0.1:7657"; openconsolebrowser >/dev/null 2>&1; echo $? )`" \
    "openconsolebrowser uses xdg-open on linux"
assert_eq "0" "`( export PATH="$VF/bin:$PATH" DIST_OS=macosx
    CONSOLE_URL="http://127.0.0.1:7657"; openconsolebrowser >/dev/null 2>&1; echo $? )`" \
    "openconsolebrowser prefers open on macos"
mkdir -p "$VF/empty"
assert_eq "1" "`( export PATH="$VF/empty" DIST_OS=linux
    CONSOLE_URL="http://127.0.0.1:7657"; openconsolebrowser >/dev/null 2>&1; echo $? )`" \
    "openconsolebrowser fails cleanly with no opener available"

# status must surface a version line, and console must reach the browser
assert_contains "`cat "$TARGET"`" "Version:" "status reports the router version"
assert_contains "`cat "$TARGET"`" "openconsolebrowser" "console uses the browser opener"
assert_absent  "`cat "$TARGET"`" \
    "grep ^RUN_AS_USER \$0" "no source-text grep of \$0 remains"
assert_absent  "`cat "$TARGET"`" \
    "_status_heap M max heap" "heap size has no space before the unit"

echo "passed: $PASSED   failed: $FAILED"
if [ "$FAILED" -gt 0 ]
then
    exit 1
fi
exit 0
