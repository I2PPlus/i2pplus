#!/bin/sh
#
# List built jbigi binaries.
# Checks core/c/jbigi/lib/net/i2p/util/ (direct builds)
# and installer/lib/jbigi/ (ant builds).
#

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$SCRIPT_DIR/lib/net/i2p/util"
DIR="$SRC_DIR"

if ! ls "$DIR"/*.so >/dev/null 2>&1 && ! ls "$DIR"/*.dll >/dev/null 2>&1; then
    echo "No jbigi binaries found. Run 'ant buildJbigi' or ./build-linux64.sh -a first."
    exit 1
fi

WIN64=$(ls "$DIR"/*windows*_64.dll 2>/dev/null)
LINUX64=$(ls "$DIR"/*linux*_64.so 2>/dev/null | grep -v armv8 | grep -v cortex)
ARM64=$(ls "$DIR"/*linux-armv8* "$DIR"/*linux-cortex* 2>/dev/null)

WIN64_COUNT=$(echo "$WIN64" | wc -w)
LINUX64_COUNT=$(echo "$LINUX64" | wc -w)
ARM64_COUNT=$(echo "$ARM64" | wc -w)
TOTAL=$((WIN64_COUNT + LINUX64_COUNT + ARM64_COUNT))

if [ "$TOTAL" -eq 0 ]; then
    echo "No jbigi binaries found."
    exit 0
fi

echo ""
echo "  jBigI Build Summary"
echo "  ===================="
echo ""

print_platform() {
    LABEL="$1"
    FILES="$2"
    COUNT="$3"
    printf "  %-18s %d files\n" "$LABEL" "$COUNT"
    for f in $FILES; do
        printf "    %s  %s\n" "$(du -h "$f" | cut -f1)" "$(basename "$f")"
    done
    echo ""
}

[ "$WIN64_COUNT" -gt 0 ] && print_platform "Windows 64-bit" "$WIN64" "$WIN64_COUNT"
[ "$LINUX64_COUNT" -gt 0 ] && print_platform "Linux 64-bit" "$LINUX64" "$LINUX64_COUNT"
[ "$ARM64_COUNT" -gt 0 ] && print_platform "Linux ARM64" "$ARM64" "$ARM64_COUNT"

printf "  Total: %d binaries in %s\n" "$TOTAL" "$DIR"
echo ""
