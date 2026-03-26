#!/bin/bash
# Format codebase and fix compilation errors iteratively
set -e
cd "$(dirname "$0")/../.."
MAX_ITERATIONS=10
EXCLUDE="tools/java-format/exclude.txt"

echo "=== Starting format + build cycle ==="

for i in $(seq 1 $MAX_ITERATIONS); do
    echo ""
    echo "--- Iteration $i ---"

    # Revert all source changes
    git checkout -- core/ router/ apps/ 2>/dev/null || true

    # Run formatter
    echo "Formatting..."
    python3 tools/java-format/java-formatter.py -r ./ 2>&1 | tail -1

    # Try to build
    echo "Building..."
    ant updater 2>/dev/null | grep 'error:' | sed 's/\[javac\] //;s/:.*$//' | sort -u > /tmp/format-errors.txt

    if [ ! -s /tmp/format-errors.txt ]; then
        echo "=== BUILD SUCCESSFUL ==="
        ant updater 2>&1 | tail -3
        exit 0
    fi

    # Add broken files to exclude list and revert them
    echo "Excluding and reverting:"
    while IFS= read -r file; do
        file=$(echo "$file" | xargs)  # trim whitespace
        if [ -n "$file" ] && ! grep -qF "$file" "$EXCLUDE" 2>/dev/null; then
            echo "  $file"
            echo "$file" >> "$EXCLUDE"
        fi
        git checkout -- "$file" 2>/dev/null || true
    done < /tmp/format-errors.txt
done

echo "=== MAX ITERATIONS REACHED ==="
ant updater 2>&1 | grep 'error:' | head -10
exit 1
