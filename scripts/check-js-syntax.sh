#!/usr/bin/env bash
# Check JavaScript files for syntax errors using node --check
#
# Usage:
#   check-js-syntax.sh                             Check all .js files below CWD
#   check-js-syntax.sh -p <path|file>              Check a path or single file
#   check-js-syntax.sh -q                          Quiet: suppress per-file output
#   check-js-syntax.sh -h                          Show this help
#
# Examples (run from scripts/ directory):
#   ./check-js-syntax.sh -p ../apps/
#   ./check-js-syntax.sh -p ../apps/routerconsole/jsp/js/jobs.js
#   ./check-js-syntax.sh -q -p ../apps/
#
# Paths are resolved relative to the working directory. If the target
# falls outside the repo root, a warning is printed.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
QUIET=false
TARGET=""

while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help)
      sed -n '2,/^$/s/^# \?//p' "$0"
      exit 0
      ;;
    -q|--quiet)
      QUIET=true
      shift
      ;;
    -p|--path)
      if [ -z "${2:-}" ]; then
        echo "Error: $1 requires a path argument" >&2
        exit 1
      fi
      TARGET="$2"
      shift 2
      ;;
    *)
      echo "Error: unknown option '$1'. Use -h for help." >&2
      exit 1
      ;;
  esac
done

TARGET="${TARGET:-.}"
ERRORS=0
CHECKED=0

# Resolve to absolute path for comparison
if [[ "$TARGET" = /* ]]; then
  ABS_TARGET="$TARGET"
else
  ABS_TARGET="$(cd "$(dirname "$TARGET")" 2>/dev/null && pwd)/$(basename "$TARGET")"
fi

if [[ "$ABS_TARGET" != "$REPO_ROOT"* ]]; then
  echo "Warning: target '$TARGET' is outside repo root ($REPO_ROOT)"
  echo
fi

check_file() {
  local file="$1"
  local output
  local rc
  CHECKED=$((CHECKED + 1))
  output=$(node --check "$file" 2>&1) || rc=$?
  if [ "${rc:-0}" -eq 0 ]; then
    if [ "$QUIET" = false ]; then
      echo "OK: $file"
    fi
  else
    ERRORS=$((ERRORS + 1))
    echo "FAIL: $file"
    echo "$output" | sed 's/^/  /'
    echo
  fi
}

if [ -f "$TARGET" ]; then
  check_file "$TARGET"
elif [ -d "$TARGET" ]; then
  while IFS= read -r -d '' file; do
    check_file "$file"
  done < <(find "$TARGET" -name '*.js' -type f -print0 2>/dev/null | sort -z)
else
  echo "Error: '$TARGET' is not a file or directory"
  exit 1
fi

echo "---"
if [ "$ERRORS" -gt 0 ]; then
  echo "FAIL: $ERRORS syntax error(s) in $CHECKED file(s)"
  exit 1
else
  echo "OK: $CHECKED file(s) checked, no syntax errors"
  exit 0
fi
