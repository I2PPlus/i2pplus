#!/bin/sh
#
# validate-po.sh — Validate all .po files for structural integrity.
#
# Checks:
#   1. msgfmt --check (syntax, format strings, unterminated strings)
#   2. Duplicate msgid detection (fatal for msgmerge/msgfmt)
#   3. Orphan bare-quote lines (leftover from broken multi-line collapse)
#
# Usage:
#   validate-po.sh [--quiet] [file ...]
#
#   --quiet         Suppress per-file output; only print summary
#   file ...        Specific .po files to validate (default: all in repo)
#
# Exit code: 0 = all clean, 1 = errors found
#
# Requires: msgfmt (gettext)

QUIET=0
FILES=""

while [ $# -gt 0 ]; do
    case "$1" in
        --quiet|-q) QUIET=1; shift ;;
        *)          FILES="$FILES $1"; shift ;;
    esac
done

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

if [ -z "$FILES" ]; then
    FILES=$(find "$REPO_ROOT" -name '*.po' -not -path '*/__pycache__/*' | sort)
fi

ERRORS=0
WARNINGS=0
FILES_OK=0
FILES_BAD=0

for f in $FILES; do
    FILE_ERRS=0

    # --- Check 1: orphan bare-quote lines ---
    if grep -qxE '"' "$f" 2>/dev/null; then
        if [ "$QUIET" = "0" ]; then
            echo "ORPHAN_QUOTE: $f"
        fi
        ERRORS=$((ERRORS + 1))
        FILE_ERRS=$((FILE_ERRS + 1))
    fi

    # --- Check 2: msgfmt --check ---
    MSGFMT_OUT=$(msgfmt --check -o /dev/null "$f" 2>&1)
    MSGFMT_ERRS=$(echo "$MSGFMT_OUT" | grep -c "^.*: error:")
    if [ "$MSGFMT_ERRS" -gt 0 ]; then
        if [ "$QUIET" = "0" ]; then
            echo "MSGFMT_ERROR: $f"
            echo "$MSGFMT_OUT" | grep "error:" | sed 's/^/  /'
        fi
        ERRORS=$((ERRORS + MSGFMT_ERRS))
        FILE_ERRS=$((FILE_ERRS + MSGFMT_ERRS))
    fi

    # --- Check 3: duplicate msgids (via msgmerge self-test) ---
    if [ "$FILE_ERRS" -eq 0 ]; then
        cp "$f" /tmp/_validate_po_input_$$.po 2>/dev/null
        MSGMERGE_OUT=$(msgmerge -q -U -N --backup=none "$f" /tmp/_validate_po_input_$$.po 2>&1)
        MSGMERGE_RC=$?
        rm -f /tmp/_validate_po_input_$$.po
        if [ "$MSGMERGE_RC" -ne 0 ]; then
            DUPES=$(echo "$MSGMERGE_OUT" | grep -c "duplicate message definition")
            if [ "$DUPES" -gt 0 ]; then
                if [ "$QUIET" = "0" ]; then
                    echo "DUPLICATE_MSGID: $f"
                    echo "$MSGMERGE_OUT" | grep -E "(duplicate|first definition)" | sed 's/^/  /'
                fi
                ERRORS=$((ERRORS + DUPES))
                FILE_ERRS=$((FILE_ERRS + DUPES))
            fi
        fi
    fi

    if [ "$FILE_ERRS" -eq 0 ]; then
        FILES_OK=$((FILES_OK + 1))
    else
        FILES_BAD=$((FILES_BAD + 1))
    fi
done

TOTAL=$((FILES_OK + FILES_BAD))
echo "---"
echo "PO validation: $FILES_OK/$TOTAL files clean, $FILES_BAD with errors ($ERRORS total errors)"

if [ "$ERRORS" -gt 0 ]; then
    exit 1
fi
exit 0
