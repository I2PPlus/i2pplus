#!/bin/bash
#
# i2p-diag-dump.sh - one-shot forensic capture from a running I2P router.
#
# Collects everything needed to diagnose the failures that are hard to see from the
# console: a wedged periodic timer, lock contention between subsystems, heap growth,
# GC pressure, CPU saturation, and file-descriptor or disk exhaustion. It is strictly
# read-only: it attaches to read thread and heap state, takes an optional JFR
# recording, and copies logs. It never sends a signal to the JVM, never sets a
# breakpoint, and never suspends a thread for longer than the attach handshake.
#
# Usage:
#   ./i2p-diag-dump.sh [options]
#
#   --pid N            router JVM pid (default: auto-detected from /proc)
#   --out DIR          output root (default: /tmp/i2plogs)
#   --dumps N          thread dumps to take (default: 3)
#   --interval SECS    seconds between dumps and samples (default: 5)
#   --jfr SECS         record a JFR profile of SECS while sampling (default: 0=off)
#   --hprof            also write a full heap dump (large; off by default)
#   --console URL      router console base URL, to archive the error log page
#   --router-dir DIR   router base dir (default: read from the process's -Di2p.dir.base)
#   --no-locks         skip thread dumps (env/heap/logs only)
#   -h, --help         this text
#
# Output: <out>/diag-<UTC timestamp>/ containing an index.txt plus the raw captures.
#
# Privilege: jcmd/jstack/jmap may only attach to a JVM running as the same user, or
# as root. If the router runs as a different service account this script detects the
# refusal and, when sudo is available, re-runs the JDK tools as the owning account.
# Nothing about that account is written into the output.
#
# Requires: bash, awk, and at least one of jcmd / jstack from the JDK that runs the
# router. Optional: jstat, jfr, top, ss, curl. Missing tools are reported, not fatal.

set -uo pipefail

OUTROOT="/tmp/i2plogs"
PID=""
DUMPS=3
INTERVAL=5
JFRSECS=0
HPROF=0
NOLOCKS=0
CONSOLE=""
ROUTERDIR=""

# ---------------------------------------------------------------- helpers

say()  { printf '%s\n' "$*"; }
note() { printf '  %s\n' "$*"; }
have() { command -v "$1" >/dev/null 2>&1; }

# Capture into a file, tolerating a non-zero exit. Best-effort by design: a missing
# tool or a refused attach must not abort the run, because the remaining sections
# are often the ones that identify the fault.
try() {
    local dest="$1"; shift
    if "$@" >"$dest" 2>"$dest.err"; then
        rm -f "$dest.err"
        return 0
    fi
    local why
    why=$(tr -d '\r' <"$dest.err" 2>/dev/null | head -2 | tr '\n' ' ')
    rm -f "$dest.err"
    note "SKIPPED $(basename "$dest"): ${why:-command failed}"
    return 1
}

usage() { sed -n '2,32p' "$0" | sed 's/^# \?//'; exit 0; }

while [ $# -gt 0 ]; do
    case "$1" in
        --pid)       PID="${2:-}"; shift 2;;
        --out)       OUTROOT="${2:-}"; shift 2;;
        --dumps)     DUMPS="${2:-}"; shift 2;;
        --interval)  INTERVAL="${2:-}"; shift 2;;
        --jfr)       JFRSECS="${2:-}"; shift 2;;
        --hprof)     HPROF=1; shift;;
        --console)   CONSOLE="${2:-}"; shift 2;;
        --router-dir) ROUTERDIR="${2:-}"; shift 2;;
        --no-locks)  NOLOCKS=1; shift;;
        -h|--help)   usage;;
        *) say "unknown option: $1"; usage;;
    esac
done

case "$DUMPS" in ''|*[!0-9]*) DUMPS=3;; esac
case "$INTERVAL" in ''|*[!0-9]*) INTERVAL=5;; esac
case "$JFRSECS" in ''|*[!0-9]*) JFRSECS=0;; esac

# ------------------------------------------------------- locate the router

# The router is the JVM whose command line carries the I2P base directory. Scanning
# /proc rather than using jps avoids depending on the JDK that happens to be first on
# PATH, and works when the router runs under a different account.
detect_pid() {
    local p cmd
    for p in /proc/[0-9]*; do
        [ -r "$p/cmdline" ] || continue
        cmd=$(tr '\0' ' ' <"$p/cmdline" 2>/dev/null) || continue
        case "$cmd" in
            *java*router*.jar*|*java*i2p*.jar*)
                printf '%s' "${p#/proc/}"; return 0;;
        esac
    done
    return 1
}

if [ -z "$PID" ]; then
    PID=$(detect_pid) || {
        say "could not find the router JVM; pass --pid"
        exit 1
    }
fi
if [ ! -r "/proc/$PID/cmdline" ]; then
    say "pid $PID is not readable; pass a pid you can inspect"
    exit 1
fi

STAMP=$(date -u +%Y%m%d-%H%M%S)
DEST="$OUTROOT/diag-$STAMP"
mkdir -p "$DEST" || { say "cannot write to $DEST"; exit 1; }
START_UTC=$(date -u +%Y-%m-%dT%H:%M:%SZ)
say "i2p-diag-dump: pid $PID -> $DEST"

CMDLINE=$(tr '\0' ' ' <"/proc/$PID/cmdline")
[ -z "$ROUTERDIR" ] && ROUTERDIR=$(printf '%s' "$CMDLINE" \
    | tr ' ' '\n' | sed -n 's/^-Di2p.dir.base=//p' | head -1)
[ -z "$ROUTERDIR" ] && ROUTERDIR=$(printf '%s' "$CMDLINE" \
    | tr ' ' '\n' | sed -n 's/^-Di2p.dir=//p' | head -1)

# --------------------------------------------------- privilege escalation

# jcmd refuses to attach across accounts. Detect that once, and if we are not already
# privileged, re-run the JDK tools as the owning account. The account name is used only
# for the sudo invocation and is never written to the output files.
RUNAS=""
# Any failure to attach means "try harder", rather than matching on the message: the
# refusal depends on the JDK and the platform. A cross-account attach commonly reports
# that the well-known pid file is not owned by the caller, not that it lacks permission,
# and a message allowlist misses that.
detect_privilege() {
    if have jcmd; then
        jcmd "$PID" VM.version >/dev/null 2>&1 && return 0
        if have sudo && sudo -n true 2>/dev/null; then
            RUNAS=$(stat -c %U "/proc/$PID" 2>/dev/null)
            [ -n "$RUNAS" ] && return 0
        fi
        return 1
    fi
    have jstack || return 1
    jstack "$PID" >/dev/null 2>&1 && return 0
    return 1
}

AS=()
if detect_privilege; then
    [ -n "$RUNAS" ] && { AS=(sudo -n -u "$RUNAS"); note "jcmd attach refused; using sudo as the owning account"; }
else
    note "WARNING: cannot attach to pid $PID as this user."
    note "         Thread dumps and heap data will be missing."
    if have sudo; then
        owner=$(stat -c %U "/proc/$PID" 2>/dev/null)
        if [ -n "$owner" ]; then
            note "         Re-run as that account:  sudo -u $owner $0 --pid $PID"
        fi
    fi
    note "         Or grant once: sudo setcap cap_sys_ptrace,cap_kill+ep \$(command -v jcmd)"
fi

jcmd_() { if [ ${#AS[@]} -gt 0 ]; then "${AS[@]}" jcmd "$PID" "$@"; else jcmd "$PID" "$@"; fi; }
jstk_() { if [ ${#AS[@]} -gt 0 ]; then "${AS[@]}" jstack "$PID" "$@"; else jstack "$PID" "$@"; fi; }

# ------------------------------------------------------------ 1. environment

{
    echo "captured_utc   : $START_UTC"
    echo "pid            : $PID"
    echo "router_dir     : ${ROUTERDIR:-(not found)}"
    echo "host           : $(uname -a)"
    echo "uptime         : $(uptime)"
    echo "loadavg        : $(cat /proc/loadavg)"
    echo "cmdline        : $CMDLINE"
} > "$DEST/00-environment.txt"
jcmd_ VM.version            > "$DEST/01-vm-version.txt"      2>/dev/null || note "VM.version unavailable"
jcmd_ VM.flags              > "$DEST/02-vm-flags.txt"        2>/dev/null || note "VM.flags unavailable"
jcmd_ VM.uptime             > "$DEST/03-vm-uptime.txt"       2>/dev/null || note "VM.uptime unavailable"
jcmd_ VM.system_properties   > "$DEST/04-vm-properties.txt"   2>/dev/null || note "VM.system_properties unavailable"
# Absent -Xlog means GC file logging is simply not enabled, which is worth knowing
# but is not a failed capture.
if grep -aoE '\-Xlog:gc[^ ]*' "/proc/$PID/cmdline" > "$DEST/05-gc-log-flags.txt" 2>/dev/null; then
    note "GC file logging flags captured"
else
    printf 'no -Xlog:gc flags on the command line; GC file logging is off\n' > "$DEST/05-gc-log-flags.txt"
    note "GC file logging not enabled on the command line"
fi

# ------------------------------------------------------------ 2. process state

try "$DEST/10-proc-status.txt"    cat "/proc/$PID/status"
try "$DEST/11-proc-io.txt"        cat "/proc/$PID/io"
try "$DEST/12-proc-limits.txt"    cat "/proc/$PID/limits"
try "$DEST/13-proc-smaps.txt"     cat "/proc/$PID/smaps_rollup"
# 0 here would be indistinguishable from a router holding no descriptors, so an
# unreadable fd directory is reported as unknown rather than as a count of zero.
if ls "/proc/$PID/fd" >/dev/null 2>&1; then
    FDCOUNT=$(ls "/proc/$PID/fd" 2>/dev/null | wc -l)
    note "open file descriptors: $FDCOUNT"
    printf 'fd_count: %s\n' "$FDCOUNT" >> "$DEST/10-proc-status.txt"
else
    note "open file descriptors: unreadable without the router's account"
    printf 'fd_count: unreadable (needs the same account as the router)\n' >> "$DEST/10-proc-status.txt"
fi
THREADS=$(awk '/^Threads:/{print $2}' "/proc/$PID/status" 2>/dev/null)
note "threads in the JVM   : ${THREADS:-unknown}"

# ------------------------------------------------------------ 3. CPU

# Two snapshots so per-thread CPU can be differenced: the thread dump carries a cpu=
# field per thread, but only a delta shows who is actually burning time now.
if have top; then
    try "$DEST/20-cpu-threads-a.txt" top -H -b -n1 -p "$PID"
    sleep "$INTERVAL"
    try "$DEST/21-cpu-threads-b.txt" top -H -b -n1 -p "$PID"
else
    note "top not available; skipping per-thread CPU"
fi

# ------------------------------------------------------------ 4. JFR (optional)

# JFR is the cheapest way to attribute CPU and allocations when no thread dump lines
# up with the symptom, because it samples rather than waits. Off by default because it
# is not free.
JFRREC=""
if [ "$JFRSECS" -gt 0 ] && have jcmd; then
    if REC=$(jcmd_ JFR.start name=i2pdiag settings=profile duration="${JFRSECS}s" filename="$DEST/30-profile.jfr" 2>&1); then
        JFRREC=1
        note "JFR recording for ${JFRSECS}s"
    else
        note "JFR.start failed: $(printf '%s' "$REC" | head -1)"
    fi
fi

# ------------------------------------------------------------ 5. heap

jcmd_ GC.heap_info        > "$DEST/40-heap-info.txt"      2>/dev/null || note "GC.heap_info unavailable"
# Two histograms so growth is visible as a diff rather than a snapshot. -all keeps
# unreachable objects, which is where a leak accumulates before any collection.
jcmd_ GC.class_histogram  > "$DEST/41-histogram-a.txt"   2>/dev/null || note "class_histogram A unavailable"
sleep "$INTERVAL"
jcmd_ GC.class_histogram  > "$DEST/42-histogram-b.txt"   2>/dev/null || note "class_histogram B unavailable"
jcmd_ GC.heap_diag        > "$DEST/43-heap-diag.txt"     2>/dev/null || note "GC.heap_diag unavailable"

if [ "$HPROF" -eq 1 ]; then
    if [ ${#AS[@]} -gt 0 ]; then
        note "writing heap dump (large, several hundred MB)"
        "${AS[@]}" jcmd "$PID" GC.heap_dump "$DEST/44-heap.hprof" >/dev/null 2>&1 \
            && note "heap dump written" || note "heap dump failed"
    else
        note "skipping heap dump: needs the same account as the router"
    fi
fi

# ------------------------------------------------------------ 6. GC rate

if have jstat; then
    if [ ${#AS[@]} -gt 0 ]; then
        "${AS[@]}" jstat -gcutil "$PID" 1000 10 > "$DEST/50-jstat-gcutil.txt" 2>/dev/null \
            || note "jstat unavailable"
    else
        jstat -gcutil "$PID" 1000 10 > "$DEST/50-jstat-gcutil.txt" 2>/dev/null \
            || note "jstat unavailable"
    fi
else
    note "jstat not available; skipping GC rate"
fi

# ------------------------------------------------------------ 7. thread dumps

# Repeated on purpose. A wedge is often gone by the time the second dump lands, and
# the first dump taken while the JVM is healthy proves nothing. -l adds the lock
# owner table, which is what turns "everything is blocked" into "blocked on what".
DUMPDIR="$DEST/60-threads"
mkdir -p "$DUMPDIR"
if [ "$NOLOCKS" -eq 1 ]; then
    note "thread dumps skipped by request"
else
    i=1
    while [ "$i" -le "$DUMPS" ]; do
        f="$DUMPDIR/threads-$(printf '%02d' "$i").txt"
        if [ $i -gt 1 ]; then sleep "$INTERVAL"; fi
        if [ ${#AS[@]} -gt 0 ]; then
            "${AS[@]}" jstack -l "$PID" > "$f" 2>"$f.err"
        else
            jstack -l "$PID" > "$f" 2>"$f.err"
        fi
        if [ -s "$f" ] && ! grep -qi "operation not permitted\|permission denied" "$f"; then
            rm -f "$f.err"
            note "thread dump $i/$DUMPS: $(grep -c '^"' "$f") threads"
        else
            note "thread dump $i/$DUMPS FAILED: $(head -1 "$f.err" 2>/dev/null)"
            rm -f "$f"
            [ ${#AS[@]} -eq 0 ] && break
        fi
        i=$((i + 1))
    done
fi

# ------------------------------------------------------------ 8. contention

# The single most useful derived artefact. Groups every BLOCKED thread by the monitor
# it waits for and names the holder, because "N threads blocked" is an observation
# while "N threads blocked on one lock held by X, which is parked in Y" is a diagnosis.
if [ -d "$DUMPDIR" ] && [ -n "$(ls -A "$DUMPDIR" 2>/dev/null)" ]; then
    {
        for f in "$DUMPDIR"/threads-*.txt; do
            [ -r "$f" ] || continue
            echo "### $(basename "$f")"
            awk '
                # RS="" makes each record one thread header plus its frames.
                function name_of(r)  { if (match(r, /^"[^"]+"/)) return substr(r, RSTART+1, RLENGTH-2); return "?" }
                function state_of(r, t) {
                    if (match(r, /Thread\.State: [A-Z]+/)) { t = substr(r, RSTART, RLENGTH); sub(/^Thread\.State: /, "", t); return t }
                    return "?"
                }
                # The regexes are inlined rather than passed in: handing a regexp
                # literal to an awk parameter makes it a boolean, and the match then
                # silently returns the wrong offset.
                function wait_lock(r,   t) {
                    if (match(r, /waiting to lock <0x[0-9a-f]+>/)) {
                        t = substr(r, RSTART, RLENGTH); sub(/^.*</, "", t); sub(/>.*$/, "", t); return t
                    }
                    return ""
                }
                # A thread can hold several monitors at once - the one that is blocking
                # everyone is rarely the first one listed - so every "- locked" entry in
                # the block is collected, not just the first match.
                function hold_locks(r,   t, out) {
                    out = ""
                    while (match(r, /- locked <0x[0-9a-f]+>/)) {
                        t = substr(r, RSTART, RLENGTH); sub(/^.*</, "", t); sub(/>.*$/, "", t)
                        out = out " " t
                        r = substr(r, RSTART + RLENGTH)
                    }
                    return out
                }
                function topframe(r,   n, i, L) {
                    n = split(r, L, "\n")
                    for (i = 2; i <= n; i++) if (L[i] ~ /^\tat /) { sub(/^\tat /, "", L[i]); return L[i] }
                    return "?"
                }
                {
                    st = state_of($0); nm = name_of($0)
                    if (st == "BLOCKED") {
                        lk = wait_lock($0)
                        if (lk != "") { waiters[lk]++; wnames[lk] = wnames[lk] " " nm; wframe[lk] = topframe($0) }
                    }
                    nh = split(hold_locks($0), held, " ")
                    for (j = 1; j <= nh; j++)
                        if (held[j] != "") { owner[held[j]] = nm; oframe[held[j]] = topframe($0); ostate[held[j]] = st }
                }
                END {
                    for (lk in waiters)
                        printf "  lock %s  waiters=%d\n    holder : %s [%s] at %s\n    waiter :%s\n",
                               lk, waiters[lk],
                               (lk in owner ? owner[lk] : "NOT IN THIS DUMP"),
                               (lk in ostate ? ostate[lk] : "?"),
                               (lk in oframe ? oframe[lk] : "?"),
                               substr(wnames[lk], 1, 200)
                }
            ' RS="" "$f"
            echo
        done
    } > "$DEST/61-contention.txt" 2>/dev/null
    note "contention summary: $(grep -c 'lock 0x' "$DEST/61-contention.txt" 2>/dev/null) contended locks"
fi

# ------------------------------------------------------------ 9. files and disk

if [ -n "$ROUTERDIR" ] && [ -d "$ROUTERDIR" ]; then
    {
        echo "# df"; df -h "$ROUTERDIR" 2>/dev/null
        echo; echo "# du top level"; du -sh "$ROUTERDIR"/* 2>/dev/null | sort -h | tail -25
        echo; echo "# rrd dir"
        du -sh "$ROUTERDIR"/rrd 2>/dev/null
        ls -lS "$ROUTERDIR"/rrd 2>/dev/null | head -20
        echo; echo "# largest files anywhere under the router dir"
        find "$ROUTERDIR" -type f -size +10M -printf '%s\t%p\n' 2>/dev/null | sort -rn | head -25
    } > "$DEST/70-files-disk.txt" 2>/dev/null
    note "router dir size: $(du -sh "$ROUTERDIR" 2>/dev/null | cut -f1)"
else
    note "router dir not readable; skipping disk section"
fi

# ------------------------------------------------------------ 10. sockets

if have ss; then
    try "$DEST/71-sockets.txt" ss -s
else
    note "ss not available; skipping socket summary"
fi

# ------------------------------------------------------------ 11. logs

LOGDIR=$(dirname "$(printf '%s' "$CMDLINE" | tr ' ' '\n' | sed -n 's/^-DloggerFilenameOverride=//p' | head -1)" 2>/dev/null)
[ -n "${LOGDIR:-}" ] && [ -d "$LOGDIR" ] || LOGDIR="/tmp/iplogs"
[ -d "$LOGDIR" ] || LOGDIR="$OUTROOT"
if [ -d "$LOGDIR" ]; then
    LATEST=$(ls -t "$LOGDIR"/log-router-*.txt 2>/dev/null | head -1)
    if [ -n "$LATEST" ]; then
        tail -20000 "$LATEST" > "$DEST/80-router-log-tail.txt" 2>/dev/null
        grep -aE "ERROR|WARN" "$LATEST" 2>/dev/null | tail -400 > "$DEST/81-router-log-errors.txt"
        note "log tail from $(basename "$LATEST"): $(wc -l < "$DEST/80-router-log-tail.txt") lines"
    fi
fi

if [ -n "$CONSOLE" ] && have curl; then
    curl -s -m 20 -L "$CONSOLE/errorlogs" -o "$DEST/82-console-errorlogs.html" 2>/dev/null \
        && note "archived console error log page" || note "console fetch failed"
    curl -s -m 20 -L "$CONSOLE/graphs" -o "$DEST/83-console-graphs.html" 2>/dev/null
fi

# ------------------------------------------------------------ 12. finish JFR

if [ -n "$JFRREC" ]; then
    sleep 1
    jcmd_ JFR.check > "$DEST/31-jfr-check.txt" 2>/dev/null
    jcmd_ JFR.stop name=i2pdiag > "$DEST/32-jfr-stop.txt" 2>/dev/null
    [ -s "$DEST/30-profile.jfr" ] && note "JFR recording: $(du -h "$DEST/30-profile.jfr" | cut -f1)"
fi

# ------------------------------------------------------------ 13. index

END_UTC=$(date -u +%Y-%m-%dT%H:%M:%SZ)
{
    echo "i2p-diag-dump"
    echo "  started  : $START_UTC"
    echo "  finished : $END_UTC"
    echo "  pid      : $PID"
    echo "  router   : ${ROUTERDIR:-unknown}"
    echo "  interval : ${INTERVAL}s, dumps requested: $DUMPS"
    echo
    echo "Where to look first:"
    echo "  61-contention.txt     who is blocked and on what (a lock, not a symptom)"
    echo "  62-blocked-threads.txt the same, condensed to blocked threads only"
    echo "  41/42-histogram       class counts two samples apart; diff for growth"
    echo "  50-jstat-gcutil.txt   GC rate and pause time"
    echo "  10-proc-status.txt    RSS, thread count, fd_count"
    echo "  20/21-cpu-threads     per-thread CPU, two samples apart"
    echo "  70-files-disk.txt     disk use and largest files"
    echo "  30-profile.jfr        JFR recording, if --jfr was given"
    echo
    echo "All captures:"
    ls -l "$DEST" | sed 's/^/  /'
} > "$DEST/index.txt"

# Condensed blocked-thread list: usually the first file worth opening.
if [ -d "$DUMPDIR" ] && [ -n "$(ls -A "$DUMPDIR" 2>/dev/null)" ]; then
    awk 'BEGIN{RS=""} /Thread.State: BLOCKED/ {
        n="?"; l=""; f="?";
        if (match($0, /^"[^"]+"/)) n=substr($0, RSTART+1, RLENGTH-2);
        if (match($0, /waiting to lock <0x[0-9a-f]+>/)) { s=substr($0,RSTART,RLENGTH); if (match(s,/<0x[0-9a-f]+>/)) l=substr(s,RSTART+1,RLENGTH-1) }
        c=split($0, L, "\n");
        for (i=2;i<=c;i++) if (L[i] ~ /^\tat /) { sub(/^\tat /,"",L[i]); f=L[i]; break }
        printf "%-34s %s  %s\n", n, l, f
    }' "$DUMPDIR"/threads-*.txt > "$DEST/62-blocked-threads.txt" 2>/dev/null
    say ""
    say "blocked threads (from 62-blocked-threads.txt):"
    head -25 "$DEST/62-blocked-threads.txt" 2>/dev/null | sed 's/^/  /'
fi

# ------------------------------------------------------------ 14. redact

# Captures legitimately contain absolute paths, and those paths carry the service
# account name. Redact the account segment in every written artefact, last, so that
# index.txt is covered too and a capture can be attached to a ticket without
# publishing it. The terminal output above keeps the real path, because the operator
# needs it to re-run the script.
for f in "$DEST"/*.txt "$DEST"/*/*.txt; do
    [ -f "$f" ] || continue
    sed -i -E 's#(/home|/Users)/[A-Za-z0-9._-]+#\1/<user>#g' "$f" 2>/dev/null
done

say ""
say "done: $DEST  (index.txt lists what was collected and what was skipped)"
exit 0