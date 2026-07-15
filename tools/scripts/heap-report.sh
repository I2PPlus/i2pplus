#!/bin/bash
# Generate heap/thread dumps for analysis.
# Usage:
#   ./heap-report.sh              # auto-detect I2P router PID
#   ./heap-report.sh <pid>        # target specific PID
#   ./heap-report.sh <file.hprof> # analyze existing dump
#
# Output: /tmp/dump-i2p/<timestamp>/
# Run as the same user as the router (or root) for jcmd/jstack/jmap access.

set -uo pipefail

OUTDIR="/tmp/dump-i2p/$(date +%Y%m%d-%H%M%S)"
HPROF_SRC=""
PID=""
NEED_ROOT=""

if [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ]; then
    echo "Usage: $(basename "$0") [pid|path.hprof]"
    echo ""
    echo "  <pid>         Attach to a running JVM and collect thread dump,"
    echo "                class histogram, heap dump, and GC info."
    echo "  <file.hprof>  Analyze an existing heap dump (dominator tree)."
    echo "  (no args)     Auto-detect I2P router PID."
    echo ""
    echo "Output: /tmp/dump-i2p/<timestamp>/"
    echo "Run as the same user as the router (or root) for full access."
    exit 0
fi

# --- Determine source ---
if [ $# -eq 1 ] && [[ "$1" == *.hprof ]]; then
    HPROF_SRC="$1"
elif [ $# -eq 1 ] && [[ "$1" =~ ^[0-9]+$ ]]; then
    PID="$1"
elif [ $# -eq 0 ]; then
    PID=$(pgrep -f 'net\.i2p\.router\.Router' 2>/dev/null | head -1)
    [ -z "$PID" ] && PID=$(pgrep -f 'i2p\.router' 2>/dev/null | head -1)
    if [ -z "$PID" ]; then
        echo "No I2P router PID found."
        echo "Usage: $(basename "$0") <pid>          # attach to running JVM"
        echo "       $(basename "$0") <file.hprof>   # analyze existing dump"
        exit 1
    fi
else
    BN=$(basename "$0")
    echo "Usage: $BN [pid|path.hprof]"; exit 1
fi

# Check access to the target JVM
if [ -n "$PID" ]; then
    if ! kill -0 "$PID" 2>/dev/null; then
        echo "PID $PID not running."; exit 1
    fi
    # Check if jcmd can talk to this PID
    JCMD_OK=0
    if command -v jcmd &>/dev/null; then
        if jcmd "$PID" VM.version &>/dev/null; then
            JCMD_OK=1
        fi
    fi
    # If jcmd fails, check jstack
    if [ "$JCMD_OK" -eq 0 ]; then
        if command -v jstack &>/dev/null; then
            if jstack "$PID" &>/dev/null; then
                JCMD_OK=1  # jstack works so we can use jmap too
            fi
        fi
    fi
    if [ "$JCMD_OK" -eq 0 ]; then
        ROUTER_USER=$(ps -o user= -p "$PID" 2>/dev/null | tr -d ' ')
        echo "Cannot attach to PID $PID (running as $ROUTER_USER)."
        echo "Re-run as:"
        echo "  sudo -u $ROUTER_USER $(basename "$0") $PID"
        exit 1
    fi
fi

echo "PID: $PID"
mkdir -p "$OUTDIR"

# --- helpers ---
heading() { echo "" >> "$OUTDIR/report.txt"; echo "=== $* ===" | tee -a "$OUTDIR/report.txt"; }

# --- 1. System info ---
{
    echo "Generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "Host: $(uname -a)"
    java -version 2>&1 | tr '\n' ' '
    echo
} > "$OUTDIR/report.txt"
heading "Memory" && free -h >> "$OUTDIR/report.txt" 2>/dev/null || true

if [ -n "$PID" ]; then
    # --- 2. Thread dump ---
    heading "Thread dump"
    if jcmd "$PID" Thread.print > "$OUTDIR/threads.txt" 2>&1 ||
       jstack "$PID" > "$OUTDIR/threads.txt" 2>&1; then
        head -5 "$OUTDIR/threads.txt" >> "$OUTDIR/report.txt"
        echo "  ... (full in threads.txt)" >> "$OUTDIR/report.txt"
    else
        echo "  unavailable" >> "$OUTDIR/report.txt"
    fi

    # --- 3. Class histogram (live) ---
    heading "Class histogram (live, top by count)"
    if jcmd "$PID" GC.class_histogram > "$OUTDIR/histogram.txt" 2>&1 ||
       jmap -histo:live "$PID" > "$OUTDIR/histogram.txt" 2>&1; then
        grep -E '^\s*[0-9]+:' "$OUTDIR/histogram.txt" | sort -k2 -rn | head -60 \
            >> "$OUTDIR/report.txt"
        # CSV for diffing
        awk '/^\s*[0-9]+:/ {
            rank=$1; sub(/:$/, "", rank); name=$4; gsub(/\(.*$/, "", name)
            print rank "," $2 "," $3 "," name
        }' "$OUTDIR/histogram.txt" > "$OUTDIR/histogram.csv"
    else
        echo "  unavailable" >> "$OUTDIR/report.txt"
    fi

    # --- 4. Heap dump ---
    heading "Heap dump"
    HPROF_SRC="$OUTDIR/heap.hprof"
    # jcmd/jmap tell the JVM to write the file as the router's user.
    # Write to /tmp/ first (world-writable), then move to output dir.
    HPROF_TMP="/tmp/heap-i2p-${PID}.hprof"
    JVM_OK=0
    if jcmd "$PID" GC.heap_dump "$HPROF_TMP" 2>&1 ||
       jmap -dump:live,file="$HPROF_TMP" "$PID" 2>&1; then
        if mv "$HPROF_TMP" "$HPROF_SRC"; then
            JVM_OK=1
        fi
    fi
    if [ "$JVM_OK" -eq 1 ] && [ -f "$HPROF_SRC" ]; then
        echo "  saved: heap.hprof ($(du -h "$HPROF_SRC" | cut -f1))" >> "$OUTDIR/report.txt"
    else
        rm -f "$HPROF_TMP" "$HPROF_SRC" 2>/dev/null
        HPROF_SRC=""
        echo "  unavailable" >> "$OUTDIR/report.txt"
    fi

    # --- 5. GC info ---
    heading "GC info"
    jcmd "$PID" GC.heap_info >> "$OUTDIR/report.txt" 2>&1 || true
    if jcmd "$PID" VM.native_memory summary > "$OUTDIR/native-memory.txt" 2>&1; then
        echo "  native summary in native-memory.txt" >> "$OUTDIR/report.txt"
    else
        rm -f "$OUTDIR/native-memory.txt" 2>/dev/null
    fi

    # --- 6. Finalizer queue ---
    heading "Finalizer queue"
    jcmd "$PID" GC.run_finalization 2>/dev/null; sleep 1
    FINALIZERS=$(jcmd "$PID" GC.class_histogram 2>/dev/null | awk '/java\.lang\.ref\.Finalizer/{print $2}')
    if [ -n "$FINALIZERS" ]; then
        echo "  ${FINALIZERS} objects pending finalization" >> "$OUTDIR/report.txt"
    fi
fi

# --- 7. Top-objects CSV ---
if [ -s "$OUTDIR/histogram.txt" ]; then
    echo "Rank,Instances,Bytes,ClassName" > "$OUTDIR/top-objects.csv"
    awk '/^\s*[0-9]+:/ && $2+0 > 1000 {
        rank=$1; sub(/:$/, "", rank); name=$4; gsub(/\(.*$/, "", name)
        print rank "," $2 "," $3 "," name
    }' "$OUTDIR/histogram.txt" | sort -t, -k2 -rn >> "$OUTDIR/top-objects.csv"
fi

# --- 8. Suspicious types ---
if [ -f "$OUTDIR/top-objects.csv" ]; then
    heading "Suspicious (iterators, crypto temps, stats)"
    awk -F, 'NR>1 && /Itr|Iterator|FieldElement|GroupElement|RateStat|StringBuilder/{
        print $0
    }' "$OUTDIR/top-objects.csv" >> "$OUTDIR/report.txt"
fi

# --- 9. Dominator tree (via jhat if available) ---
if [ -n "$HPROF_SRC" ] && [ -f "$HPROF_SRC" ] && [ -s "$HPROF_SRC" ]; then
    JHAT=""
    for c in "$JAVA_HOME/bin/jhat" /usr/bin/jhat /usr/lib/jvm/*/bin/jhat; do
        [ -x "$c" ] && { JHAT="$c"; break; }
    done

    if [ -n "$JHAT" ] && command -v curl &>/dev/null; then
        heading "Dominator tree (via jhat)"
        $JHAT -port 7400 "$HPROF_SRC" &>/dev/null &
        JHAT_PID=$!
        sleep 3

        if kill -0 "$JHAT_PID" 2>/dev/null; then
            curl -s "http://127.0.0.1:7400/dominatorTree/200/" \
                > "$OUTDIR/dominator-tree.html" 2>/dev/null || true

            if [ -s "$OUTDIR/dominator-tree.html" ]; then
                python3 -c "
from html.parser import HTMLParser
import csv

class P(HTMLParser):
    def __init__(self):
        super().__init__()
        self.rows = []; self._r = []; self._in = False
    def handle_starttag(self, tag, attrs):
        if tag == 'tr': self._in = True; self._r = []
    def handle_endtag(self, tag):
        if tag == 'tr' and self._r:
            self.rows.append(self._r); self._in = False
    def handle_data(self, data):
        if self._in: self._r.append(data.strip())

with open('$OUTDIR/dominator-tree.html') as f:
    p = P(); p.feed(f.read())
with open('$OUTDIR/dominator-tree.csv', 'w', newline='') as f:
    w = csv.writer(f)
    w.writerow(['Class','Objects','ShallowHeap','RetainedHeap'])
    for r in p.rows:
        if len(r) >= 4 and any(c.strip() for c in r):
            w.writerow(r[:4])
print('  saved: dominator-tree.csv')
" 2>/dev/null || true
            fi

            # Ref chains for iterator suspects
            for cls in "java.util.ArrayList%24Itr" "java.util.concurrent.PriorityBlockingQueue%24Itr"; do
                curl -s "http://127.0.0.1:7400/refsByType/?q=$cls" 2>/dev/null \
                    | python3 -c "
from html.parser import HTMLParser
import sys
class P(HTMLParser):
    def __init__(self):
        super().__init__()
        self._c = False; self._d = []
    def handle_starttag(self, tag, attrs):
        if tag == 'pre': self._c = True
    def handle_endtag(self, tag):
        if tag == 'pre': self._c = False
    def handle_data(self, data):
        if self._c: self._d.append(data)
raw = sys.stdin.buffer.read().decode('utf-8', errors='replace')
p = P(); p.feed(raw)
sys.stdout.write(''.join(p._d[:80]))
" 2>/dev/null > "$OUTDIR/itr-refs.txt" || true
            done
            kill "$JHAT_PID" 2>/dev/null || true
            echo "  saved: itr-refs.txt" >> "$OUTDIR/report.txt"
        fi
    fi
fi

# --- Summary ---
echo ""
echo "=============================================="
echo "  Output: $OUTDIR/"
echo "=============================================="
echo ""
ls -lh "$OUTDIR/" 2>/dev/null | awk '{if(NR>1) print "  " $NF " (" $5 ")"}'
echo ""
if [ -f "$OUTDIR/heap.hprof" ]; then
    echo "For dominator tree + leak suspect report:"
    echo "  Download Eclipse MAT from https://eclipse.org/mat"
    echo "  Then: ParseHeapDump.sh $OUTDIR/heap.hprof"
fi
echo ""
