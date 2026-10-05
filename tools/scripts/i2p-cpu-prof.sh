#!/bin/bash
#
# i2p-cpu-prof.sh - attribute router CPU to threads and Java frames.
#
# A CPU-focused companion to i2p-diag-dump.sh. That script answers "what is the
# router doing"; this one answers "which thread is burning it, and on what code".
# The two differ in where the evidence comes from: /proc gives ground truth on
# which OS threads consumed CPU in a known wall-clock window, while a JFR
# recording samples those same threads to say which Java frame was on the
# stack. Neither alone is conclusive - /proc cannot see frames, JFR cannot be
# trusted for absolute totals - so the per-thread accounting is the ranking
# authority and JFR supplies the "why".
#
# Everything runs concurrently in one window so the numbers agree with each
# other. Ranking threads from two snapshots taken at either end of the window
# avoids the sampling error that makes a single short dump misleading: a
# thread that was merely runnable looks identical to one that is spinning.
#
# It is strictly read-only. It attaches to read CPU, thread, and GC state, and
# starts a JFR recording on a private filename. It never sends a signal to the
# JVM, never sets a breakpoint, and never suspends a thread for longer than the
# attach handshake. Do not attach a debugger to the router and leave it
# suspended: the wrapper watchdog reads suspended threads as a hang and
# force-restarts the router.
#
# Usage:
#   ./i2p-cpu-prof.sh [options]
#
#   --pid N            router JVM pid (default: auto-detected from /proc)
#   --out DIR          output root (default: /tmp/i2plogs)
#   --seconds N        per-thread CPU accounting window (default: 20)
#   --jfr SECS         JFR recording length (default: 60, 0 = skip)
#   --dumps N          thread dumps inside the window (default: 3)
#   --top N            hot threads to pull stacks for (default: 15)
#   --no-jfr           same as --jfr 0
#   --router-dir DIR   router base dir, to archive the log tail
#   -h, --help         this text
#
# Output: <out>/cpuprof-<UTC timestamp>/ holding summary.txt, index.txt, the raw
# per-thread accounting, the thread dumps, and the JFR recording.
#
# Privilege: jcmd/jstack may only attach to a JVM running as the same account,
# or as root. Run as root (or as the router's own account) or the JDK tools
# will be refused; the /proc accounting still works regardless, so a partial
# run is still worth reading. Nothing about the owning account is written to
# the output.
#
# Requires: bash, awk, and at least one of jcmd / jstack from the JDK that runs
# the router. Optional: jstat, jfr, top, getconf. Missing tools are reported,
# not fatal.

set -uo pipefail

OUTROOT="/tmp/i2plogs"
PID=""
WINDOW=20
JFRSECS=60
DUMPS=3
TOPN=15
ROUTERDIR=""

# ---------------------------------------------------------------- helpers

say()  { printf '%s\n' "$*"; }
note() { printf '  %s\n' "$*"; }
have() { command -v "$1" >/dev/null 2>&1; }

# Capture into a file, tolerating a non-zero exit. A refused attach must not
# abort the run, because the remaining sections are often the ones that
# identify the fault.
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

usage() { sed -n '2,38p' "$0" | sed 's/^# \?//'; exit 0; }

while [ $# -gt 0 ]; do
    case "$1" in
        --pid)       PID="${2:-}"; shift 2;;
        --out)       OUTROOT="${2:-}"; shift 2;;
        --seconds)   WINDOW="${2:-}"; shift 2;;
        --jfr)       JFRSECS="${2:-}"; shift 2;;
        --no-jfr)    JFRSECS=0; shift;;
        --dumps)     DUMPS="${2:-}"; shift 2;;
        --top)       TOPN="${2:-}"; shift 2;;
        --router-dir) ROUTERDIR="${2:-}"; shift 2;;
        -h|--help)   usage;;
        *) say "unknown option: $1"; usage;;
    esac
done

for v in WINDOW JFRSECS DUMPS TOPN; do
    case "${!v}" in
        ''|*[!0-9]*) say "$v must be a non-negative integer"; exit 1;;
    esac
done
[ "$WINDOW" -lt 1 ] && WINDOW=1
[ "$DUMPS" -gt 20 ] && DUMPS=20
[ "$TOPN" -lt 1 ] && TOPN=1

# ---------------------------------------------------------------- locate pid

if [ -z "$PID" ]; then
    PID=$(pgrep -f 'net\.i2p\.router\.Router' 2>/dev/null | head -1)
    [ -z "$PID" ] && PID=$(pgrep -f 'i2p\.router' 2>/dev/null | head -1)
    if [ -z "$PID" ]; then
        say "No I2P router PID found."
        say "Usage: $(basename "$0") --pid N"
        exit 1
    fi
fi
case "$PID" in ''|*[!0-9]*) say "--pid must be numeric"; exit 1;; esac

# /proc is the liveness test, not kill -0: signal permission is denied for
# another account's process even when it is perfectly alive, and that is the
# normal case here, so treating EPERM as "not running" is simply wrong.
if [ ! -d "/proc/$PID/task" ]; then
    say "pid $PID has no /proc task directory: not running, or not Linux."
    exit 1
fi
kill -0 "$PID" 2>/dev/null || note "pid $PID belongs to another account; attach will need root"

STAMP=$(date -u +%Y%m%d-%H%M%S)
DEST="$OUTROOT/cpuprof-$STAMP"
mkdir -p "$DEST" || { say "cannot write to $DEST"; exit 1; }
START_UTC=$(date -u +%Y-%m-%dT%H:%M:%SZ)
say "i2p-cpu-prof: pid $PID -> $DEST"
note "window ${WINDOW}s, $DUMPS thread dump(s), jfr ${JFRSECS}s"

CMDLINE=$(tr '\0' ' ' <"/proc/$PID/cmdline" 2>/dev/null)
[ -z "$ROUTERDIR" ] && ROUTERDIR=$(printf '%s' "$CMDLINE" \
    | tr ' ' '\n' | sed -n 's/^-Di2p.dir.base=//p' | head -1)
[ -z "$ROUTERDIR" ] && ROUTERDIR=$(printf '%s' "$CMDLINE" \
    | tr ' ' '\n' | sed -n 's/^-Di2p.dir=//p' | head -1)

CLK=$(getconf CLK_TCK 2>/dev/null)
case "$CLK" in ''|*[!0-9]*) CLK=100;; esac
NCPU=$(nproc 2>/dev/null || echo 1)

# ---------------------------------------------------------- privilege check

# Prefer the JDK that actually runs the router. Attaching with a jcmd from a
# different JDK is the usual reason a root attach gets refused, and the target
# JDK is discoverable from the running process rather than from PATH.
TARGET_JDK=""
TARGET_EXE=$(readlink -f "/proc/$PID/exe" 2>/dev/null)
if [ -n "$TARGET_EXE" ] && [ -x "$(dirname "$TARGET_EXE")/jcmd" ]; then
    TARGET_JDK=$(dirname "$TARGET_EXE")
    note "using target JDK: $TARGET_JDK"
fi

pick() {
    if [ -n "$TARGET_JDK" ] && [ -x "$TARGET_JDK/$1" ]; then
        printf '%s\n' "$TARGET_JDK/$1"
    elif have "$1"; then
        command -v "$1"
    fi
}
JCMD=$(pick jcmd)
JSTAT=$(pick jstat)

# Only the JDK tools are gated on privilege; the /proc accounting is not, so
# note the shortfall once and carry on rather than aborting a partial capture.
ATTACH_OK=1
if [ -n "$JCMD" ] && "$JCMD" "$PID" VM.version >/dev/null 2>&1; then
    :
elif [ "$(id -u)" = "0" ]; then
    note "jcmd attach refused even as root; is $JCMD the wrong JDK for this JVM?"
    ATTACH_OK=0
elif have jstack; then
    note "jcmd attach refused; re-run as root or as the router's account"
    ATTACH_OK=0
else
    note "no jcmd or jstack found; only the /proc accounting will run"
    ATTACH_OK=0
fi

# ------------------------------------------------------------- 1. environment

{
    echo "start_utc      $START_UTC"
    echo "pid            $PID"
    echo "jvm_argc       $(tr '\0' '\n' <"/proc/$PID/cmdline" 2>/dev/null | wc -l)"
    echo "clock_ticks    $CLK"
    echo "host_cpus      $NCPU"
    echo "window_secs    $WINDOW"
    echo "jfr_secs       $JFRSECS"
    echo "dumps          $DUMPS"
    echo "attach_ok      $ATTACH_OK"
    echo "router_dir     ${ROUTERDIR:-unknown}"
    echo "loadavg        $(cut -d' ' -f1-3 /proc/loadavg 2>/dev/null)"
} > "$DEST/00-env.txt"

if [ "$ATTACH_OK" = "1" ]; then
    try "$DEST/01-vm-version.txt"  "$JCMD" "$PID" VM.version
    try "$DEST/02-vm-flags.txt"    "$JCMD" "$PID" VM.flags
    try "$DEST/03-vm-uptime.txt"   "$JCMD" "$PID" VM.uptime
fi

# ---------------------------------------------- 2. per-thread CPU accounting

# Ground truth for "which OS thread burned CPU", read from the kernel rather
# than the JVM, as the delta of utime+stime across the sampling window.
# One awk for every thread rather than a fork per thread: the router runs well
# over a thousand of them, and adding two thousand processes to a capture of an
# already-saturated router would distort what is being measured. Short-lived
# threads come and go constantly, so a task that exits between the glob and the
# read makes awk complain on stderr; that is discarded, not fatal.
snapshot() {
    awk -v OFS='\t' '
        # The comm field is parenthesised and may contain spaces, so plain
        # $14/$15 would drift for any thread with a space in its name. Strip
        # the pid, then take the comm as everything up to the last ")" that is
        # followed by a single-letter thread state - the only place one can
        # appear, since every field after comm is numeric.
        {
            s = $0
            pid = s
            sub(/ .*/, "", pid)
            sub(/^[0-9]+ +/, "", s)
            p1 = index(s, "(")
            if (p1 == 0) next
            rest = substr(s, p1 + 1)
            name = ""
            state = ""
            while (match(rest, /\) [RSDZTWIP]/)) {
                name  = substr(rest, 1, RSTART - 1)
                state = substr(rest, RSTART + 2, 1)
                rest  = substr(rest, RSTART + 3)
            }
            # Rebuild the tail as a clean "state ppid ..." so the field count
            # is stable: whether awk keeps or drops a leading empty field must
            # not decide whether utime lands on f[12] or f[13].
            sub(/^ +/, "", rest)
            n = split(state " " rest, f, " ")
            if (n < 13) next
            # f[12] is utime and f[13] is stime, counting state as f[1] after
            # removing the pid and comm that preceded it.
            print pid, f[12], f[13], name
        }
    ' /proc/"$PID"/task/*/stat 2>/dev/null
}

say "sampling per-thread CPU for ${WINDOW}s..."
snapshot > "$DEST/.snap-a"

# The thread dumps and the JFR window share the accounting window so that the
# frames in 40-hot-stacks.txt belong to the threads ranked in the summary.
[ "$ATTACH_OK" = "1" ] && "$JCMD" "$PID" Thread.print > "$DEST/20-threads-pre.txt" 2>/dev/null \
    || note "Thread.print unavailable"

JFRNAME="i2pcpu$$"
JFRACTIVE=""
if [ "$JFRSECS" -gt 0 ] && [ "$ATTACH_OK" = "1" ]; then
    if try "$DEST/.jfr-start.txt" "$JCMD" "$PID" JFR.start \
            name="$JFRNAME" settings=profile duration="${JFRSECS}s" \
            filename="$DEST/30-profile.jfr"; then
        JFRACTIVE=1
        note "JFR recording for ${JFRSECS}s"
    else
        note "JFR.start failed; continuing without it"
    fi
fi

DUMPSPAUSE=$(( WINDOW / (DUMPS + 1) ))
[ "$DUMPSPAUSE" -lt 1 ] && DUMPSPAUSE=1
ELAPSED=0
i=1
while [ "$i" -le "$DUMPS" ]; do
    sleep "$DUMPSPAUSE"
    ELAPSED=$(( ELAPSED + DUMPSPAUSE ))
    if [ "$ATTACH_OK" = "1" ]; then
        n=$(printf '%02d' $(( 20 + i )))
        "$JCMD" "$PID" Thread.print > "$DEST/$n-threads.txt" 2>/dev/null \
            || note "Thread.print $i unavailable"
    fi
    i=$(( i + 1 ))
done

# The dumps are spaced to fit inside the window, not the other way round. Sleep
# out whatever is left so the two snapshots always span the full WINDOW: the
# percentages are only meaningful against the interval they claim, and with
# --dumps 0 there is no other sleep at all.
REMAIN=$(( WINDOW - ELAPSED ))
[ "$REMAIN" -gt 0 ] && sleep "$REMAIN"

snapshot > "$DEST/.snap-b"

if [ -n "$JFRACTIVE" ]; then
    "$JCMD" "$PID" JFR.check > "$DEST/31-jfr-check.txt" 2>/dev/null
    "$JCMD" "$PID" JFR.stop name="$JFRNAME" > "$DEST/32-jfr-stop.txt" 2>/dev/null
    # stop should flush the recording to the filename given at start, but it
    # does not always; dump is the belt-and-braces way to force the bytes out
    # before giving up, and it costs nothing if stop already worked.
    if [ ! -s "$DEST/30-profile.jfr" ]; then
        "$JCMD" "$PID" JFR.dump name="$JFRNAME" filename="$DEST/30-profile.jfr" \
            > "$DEST/33-jfr-dump.txt" 2>/dev/null
    fi
    if [ -s "$DEST/30-profile.jfr" ]; then
        note "JFR: $(du -h "$DEST/30-profile.jfr" | cut -f1)"
    else
        note "JFR recording produced no file; see 31/32-jfr-*.txt"
    fi
fi
rm -f "$DEST/.jfr-start.txt"

# Percent-of-one-core per thread, then rolled up by thread name. The aggregate
# matters more than the per-thread number: a pool of eight dispatchers at 28%
# each is one subsystem costing 220%, and that is the shape of the problem.
awk -v hz="$CLK" -v win="$WINDOW" -v FS='\t' '
    NR == FNR { u[$1] = $2; s[$1] = $3; seen[$1] = 1; next }
    ($1 in seen) {
        d = ($2 + $3) - (u[$1] + s[$1])
        if (d < 0) d = 0
        pct = (d / (hz * win)) * 100
        if (pct <= 0) next
        byname[$4] += pct
        count[$4]++
        total += pct
        printf "%s\t%.2f\t%s\n", $1, pct, $4 > DETAIL
    }
    END {
        for (n in byname) printf "%s\t%.2f\t%d\n", n, byname[n], count[n] > AGG
        printf "%.2f\n", total > TOTAL
    }
    ' DETAIL="$DEST/.detail" AGG="$DEST/.agg" TOTAL="$DEST/.total" \
    "$DEST/.snap-a" "$DEST/.snap-b"

TOTAL=$(cat "$DEST/.total" 2>/dev/null || echo 0)

# Thread names contain spaces, so every ranking stays tab-delimited rather than
# being re-split by whitespace. .detail and .agg are the machine-readable form;
# the numbered files are the same data aligned for a human reader.
TAB=$(printf '\t')
sort -t"$TAB" -k2 -rn "$DEST/.detail" > "$DEST/.detail-sorted"

{
    echo "# threads by total CPU over ${WINDOW}s, as % of one core"
    echo "# total for pid $PID: ${TOTAL}% of one core across $NCPU host cpus"
    echo "# names are as the kernel reports them and cut off at 15 chars;"
    echo "# 40-hot-stacks.txt carries the JVM's full names for the same tids"
    printf "%8s  %5s  %s\n" "PCT" "N" "THREAD NAME"
    while IFS="$TAB" read -r name pct cnt; do
        printf "%7.2f%%  %5d  %s\n" "$pct" "$cnt" "$name"
    done < <(sort -t"$TAB" -k2 -rn "$DEST/.agg" 2>/dev/null)
} > "$DEST/10-perthread-cpu.txt"

{
    echo "# individual OS threads, % of one core over ${WINDOW}s (tab separated)"
    cat "$DEST/.detail-sorted"
} > "$DEST/11-perthread-cpu-detail.txt"

# ------------------------------------------- 3. stacks for the hottest threads

# With a thousand-odd router threads a full dump says nothing at a glance, so
# pull only the blocks belonging to the threads the accounting flagged. This is
# the join that turns "I2CPDisp.0" into the frame that is actually spinning.
head -n "$TOPN" "$DEST/.detail-sorted" | cut -f1 | sort -u > "$DEST/.hottids"

if [ -s "$DEST/.hottids" ]; then
    for f in "$DEST"/2*-threads.txt "$DEST"/20-threads-pre.txt; do
        [ -r "$f" ] || continue
        awk -v HOT="$DEST/.hottids" '
            BEGIN { while ((getline l < HOT) > 0) hot[l + 0] = 1 }
            /^"/ {
                if (keep) print ""
                n = $0
                nid = ""
                if (match(n, /nid=[0-9]+/))            nid = substr(n, RSTART + 4, RLENGTH - 4)
                else if (match(n, /\[tid=[0-9]+\]/))  nid = substr(n, RSTART + 5, RLENGTH - 6)
                else if (match(n, /#([0-9]+) /))     { m = substr(n, RSTART + 1, RLENGTH - 3); if (m in hot) nid = m }
                keep = (nid in hot)
                # The header carries the thread name plus the cpu= and
                # elapsed= counters from the JVM, so it has to survive the
                # filter or the surviving frames would be anonymous.
                if (keep) print
                next
            }
            { if (keep) print }
        ' "$f"
    done > "$DEST/40-hot-stacks.txt"
    [ -s "$DEST/40-hot-stacks.txt" ] \
        || note "no stacks matched the hot tids; thread dumps may predate them"
fi

# -------------------------------------------------- 4. GC and heap behaviour

# A CPU spike that is really GC pressure shows up here rather than in any
# thread's Java frames, so keep GC cadence next to the thread ranking.
if [ "$ATTACH_OK" = "1" ]; then
    if [ -n "$JSTAT" ]; then
        try "$DEST/50-jstat-gcutil.txt" "$JSTAT" -gcutil "$PID" 2000 3
    else
        note "jstat unavailable; skipping GC sampling"
    fi
    try "$DEST/51-gc-heap.txt" "$JCMD" "$PID" GC.heap_info
    # Compiler threads burn real CPU during a rebuild-heavy period; if the
    # ranking below is dominated by them the fix is build flags, not router code.
    try "$DEST/52-compilation.txt" "$JCMD" "$PID" Compiler.queue
fi

# ------------------------------------------------------- 5. router log tail

if [ -n "$ROUTERDIR" ] && [ -d "$ROUTERDIR/logs" ]; then
    LOGDIR="$ROUTERDIR/logs"
elif [ -d "$ROUTERDIR" ]; then
    LOGDIR="$ROUTERDIR"
else
    LOGDIR="$OUTROOT"
fi
# Only the newest log, tail-only: a busy router's logs churn fast and this is a
# CPU capture, not an incident archive.
NEWEST=$(ls -t "$LOGDIR"/log-*.txt 2>/dev/null | head -1)
if [ -n "$NEWEST" ]; then
    tail -n 300 "$NEWEST" > "$DEST/60-log-tail.txt" 2>/dev/null \
        && note "log tail: $(basename "$NEWEST")"
fi

# Scratch files are hidden and start with a dot so they never look like part of
# the capture; drop them now that the numbered files hold the same data.
rm -f "$DEST/.snap-a" "$DEST/.snap-b" "$DEST/.agg" "$DEST/.total" \
      "$DEST/.detail" "$DEST/.detail-sorted" "$DEST/.hottids"

# ------------------------------------------------------------------ summary

{
    echo "i2p-cpu-prof summary"
    echo "====================="
    echo "captured : $START_UTC"
    echo "pid      : $PID"
    echo "window   : ${WINDOW}s   jfr: ${JFRSECS}s   dumps: $DUMPS"
    echo "host     : $NCPU cpus, loadavg $(cut -d' ' -f1-3 /proc/loadavg 2>/dev/null)"
    echo "total    : ${TOTAL}% of one core"
    echo
    echo "Top CPU consumers by thread name"
    echo "---------------------------------"
    # Filter the comment lines rather than counting them, so adding a note to
    # the data file cannot silently shift the ranking out of the summary.
    grep -v '^#' "$DEST/10-perthread-cpu.txt" 2>/dev/null | tail -n +2 | head -20
    echo
    echo "Where the top threads were running"
    echo "----------------------------------"
    if [ -s "$DEST/40-hot-stacks.txt" ]; then
        echo "see 40-hot-stacks.txt (top $TOPN tids, frames only)"
    else
        echo "no stacks captured; rerun as root or as the router's account"
    fi
    echo
    echo "How to read the JFR recording"
    echo "-----------------------------"
    if [ -s "$DEST/30-profile.jfr" ]; then
        echo "  jfr summary $DEST/30-profile.jfr"
        echo "  jfr print --events jdk.ExecutionSample $DEST/30-profile.jfr | less"
        echo "  Rank by cumulative time to see the hot path; by sample count to see"
        echo "  which threads dominate. A thread pinned in the same frame across"
        echo "  every sample is spinning, not merely runnable."
    else
        echo "  (no recording: attach was refused or --no-jfr was given)"
    fi
} > "$DEST/summary.txt"

{
    echo "i2p-cpu-prof capture - $START_UTC"
    echo "pid $PID, window ${WINDOW}s, jfr ${JFRSECS}s, top $TOPN threads"
    echo
    echo "  summary.txt                   ranked CPU consumers and how to read them"
    echo "  00-env.txt                    capture parameters and host context"
    echo "  01..03-vm-*.txt               JVM version, flags, uptime"
    echo "  10-perthread-cpu.txt          CPU by thread name, % of one core"
    echo "  11-perthread-cpu-detail.txt   the same per OS tid"
    echo "  20-threads*.txt               full thread dumps from inside the window"
    echo "  30-profile.jfr               JFR recording, if it was started"
    echo "  40-hot-stacks.txt            frames for the top $TOPN threads only"
    echo "  50..52-gc-*.txt              GC cadence, heap, compiler queue"
    echo "  60-log-tail.txt              tail of the newest router log, if readable"
    echo
    echo "This capture is read-only and attached no debugger; the router was never"
    echo "suspended beyond the attach handshake."
} > "$DEST/index.txt"

# A capture taken with sudo lands root-owned and unreadable, which defeats the
# point of asking for it. Read-only to everyone is enough to analyse and to
# attach to a bug report; nothing here needs write access for anyone else.
chmod -R a+rX "$DEST" 2>/dev/null

say "done: $DEST"
note "summary: $DEST/summary.txt"
