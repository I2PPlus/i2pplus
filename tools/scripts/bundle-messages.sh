#!/bin/sh
#
# Consolidated bundle-messages script.
# Generates ResourceBundle .class files from .po translations.
# Supports Java ResourceBundles and gettext .mo files.
#
# Usage:
#   bundle-messages.sh --dir <cfgdir> [--cfg <name>] [--build-dir <dir>] [-p|--poupdate] [--no-coverage]
#
#   --dir <cfgdir>    Directory containing the config file (required)
#   --cfg <name>      Config filename (default: bundle-messages.cfg)
#   --build-dir <dir> Build output directory (default: "build")
#   -p, --poupdate    Update .po files from source tags, then generate bundles
#   --no-coverage     Suppress translation coverage summary
#
# Each module directory must have a bundle-messages.cfg file with the
# per-module settings (class name, source paths, keywords, etc.)
# Secondary bundles (news, countries, proxy) use --cfg with their variant name.
#
# Requires: xgettext, msgfmt (gettext >= 0.19 for fast mode), msgmerge, find
#
# Original by zzz (public domain), consolidated and extended by dr|z3d

usage() {
    echo "Usage: $0 --dir <cfgdir> [--cfg <name>] [--build-dir <dir>] [-p|--poupdate]"
    exit 1
}

DIR=""
CFG="bundle-messages.cfg"
BD=""
POUPDATE=0
NOCOVERAGE=0

while [ $# -gt 0 ]; do
    case "$1" in
        --dir)
            DIR="$2"; shift 2 ;;
        --cfg)
            CFG="$2"; shift 2 ;;
        --build-dir)
            BD="$2"; shift 2 ;;
        -p|--poupdate)
            POUPDATE=1; shift ;;
        --no-coverage)
            NOCOVERAGE=1; shift ;;
        *)
            usage ;;
    esac
done

test -n "$DIR" || usage
test -d "$DIR" || { echo "ERROR: directory not found: $DIR"; exit 1; }

cd "$DIR" || { echo "ERROR: can't cd to $DIR"; exit 1; }
BD="${BD:-build}"

test -f "$CFG" || { echo "ERROR: $CFG not found in $DIR"; exit 1; }
. "./$CFG"

# --- Common defaults ---
TYPE="${TYPE:-java}"
TMPFILE="${TMPFILE:-$BD/javafiles${SUFFIX:+-}${SUFFIX}.txt}"
RC=0
export TZ=UTC

if which find | grep -q -i windows; then
    export PATH=.:/bin:/usr/local/bin:$PATH
fi

# --- TYPE=java specific defaults & validation ---
if [ "$TYPE" = "java" ]; then
    : "${CLASS?ERROR: CLASS not set in bundle-messages.cfg}"
    : "${PO_GLOB?ERROR: PO_GLOB not set in bundle-messages.cfg}"
    : "${JPATHS?ERROR: JPATHS not set in bundle-messages.cfg}"
    : "${XGETTEXT_KEYWORDS?ERROR: XGETTEXT_KEYWORDS not set in bundle-messages.cfg}"

    XGETTEXT_LANG="${XGETTEXT_LANG:-java}"
    XGETTEXT_EXTRA_FLAGS="${XGETTEXT_EXTRA_FLAGS:-}"
    XGETTEXT_SKIP_DEFAULTS="${XGETTEXT_SKIP_DEFAULTS:-false}"
    STRIP_PRINTF="${STRIP_PRINTF:-false}"
    SUFFIX="${SUFFIX:-}"
    CLASS_OUTPUT_DIR="${CLASS_OUTPUT_DIR:-$BD/obj}"

    if [ -z "$PACKAGE_PATH" ]; then
        PACKAGE_PATH=$(echo "$CLASS" | sed 's/\./\//g' | sed 's,/messages$,,')
    fi

    if ! which javac > /dev/null 2>&1; then
        export JAVAC="${JAVA_HOME}/../bin/javac"
    fi

    msgfmt -V 2>/dev/null | grep -q -E ' 0\.((19)|[2-9])'
    FAST=$?

# --- TYPE=mo specific defaults & validation ---
elif [ "$TYPE" = "mo" ]; then
    : "${PO_GLOB?ERROR: PO_GLOB not set in bundle-messages.cfg}"
    : "${MO_SOURCE_PATHS?ERROR: MO_SOURCE_PATHS not set in bundle-messages.cfg}"
    : "${MO_FILE_PATTERN?ERROR: MO_FILE_PATTERN not set in bundle-messages.cfg}"

    XGETTEXT_LANG="${XGETTEXT_LANG:-Shell}"
    XGETTEXT_EXTRA_FLAGS="${XGETTEXT_EXTRA_FLAGS:-}"
    MO_SOURCE_FILTER="${MO_SOURCE_FILTER:-}"
fi

# --- Pre-generation command (if any) ---
if [ -n "$PRE_COMMAND" ]; then
    eval "$PRE_COMMAND" || { echo "ERROR: PRE_COMMAND failed"; exit 1; }
fi

# =========================================================================
# TYPE=java -- Java ResourceBundle .class files
# =========================================================================
if [ "$TYPE" = "java" ]; then

    if [ "$XGETTEXT_SKIP_DEFAULTS" != "true" ]; then
        XGS="-F --width=0 --no-wrap --add-comments"
    else
        XGS=""
    fi

    ALL_UPTODATE=1
    for i in $PO_GLOB; do
        LG=$(basename "$i" .po)
        LG="${LG#messages_}"
        [ "$LG" = "en" ] && continue
        # Search for the class file under the build tree.
        # Modules output to various subdirs: $BD/obj, $BD/classes, $BD,
        # or to a sibling under the build root (e.g. i2ptunnel/jsp).
        MATCH=$(find "$BD" "$BD/.." "$BD/../.." "$BD/../../.." \
          -path "*/$PACKAGE_PATH/messages_$LG.class" 2>/dev/null | head -1)
        if [ -z "$MATCH" ] || [ "$i" -nt "$MATCH" ]; then
            ALL_UPTODATE=0; break
        fi
    done
    if [ "$ALL_UPTODATE" = "1" ] && [ "$POUPDATE" != "1" ]; then
        rm -f "$TMPFILE"; echo "INFO: Using cached translation bundles"; exit 0
    fi

    for i in $PO_GLOB; do
        LG=$(basename "$i" .po)
        LG="${LG#messages_}"

        if [ -n "$LG2" ]; then
            [ "$LG" != "$LG2" ] && continue || echo "INFO: Language update is set to [$LG2] only."
        fi

        if [ "$POUPDATE" = "1" ]; then
            find $JPATHS -name '*.java' -newer "$i" > "$TMPFILE" 2>/dev/null
        fi

        CLASSFILE="$CLASS_OUTPUT_DIR/$PACKAGE_PATH/messages_$LG.class"
        if [ -s "$CLASSFILE" ] && [ "$CLASSFILE" -nt "$i" ] && [ ! -s "$TMPFILE" ]; then
            continue
        fi

        if [ "$POUPDATE" = "1" ]; then
            echo "Updating $i from source tags..."
            find $JPATHS -name '*.java' > "$TMPFILE" 2>/dev/null

            xgettext -f "$TMPFILE" -L "$XGETTEXT_LANG" --from-code=UTF-8 \
                $XGS $XGETTEXT_EXTRA_FLAGS $XGETTEXT_KEYWORDS -o "${i}t"
            if [ $? -ne 0 ]; then
                echo "ERROR - xgettext failed on $i, not updating translations"
                rm -f "${i}t"; RC=1; break
            fi

            msgmerge -q -U -N --backup=none "$i" "${i}t"
            if [ $? -ne 0 ]; then
                echo "ERROR - msgmerge failed on $i, not updating translations"
                rm -f "${i}t"; RC=1; break
            fi
            rm -f "${i}t"

            if [ "$STRIP_PRINTF" = "true" ]; then
                grep -v java-printf-format "$i" > "${i}t" && mv "${i}t" "$i"
            fi
            touch "$i"
        fi

        [ "$LG" = "en" ] && continue

        if [ $FAST -eq 0 ]; then
            TD="$BD/messages${SUFFIX:+-}${SUFFIX}-src-tmp"
            TDX="$TD/$PACKAGE_PATH"
            TD2="$BD/messages${SUFFIX:+-}${SUFFIX}-src"
            TDY="$TD2/$PACKAGE_PATH"
            rm -rf "$TD"
            mkdir -p "$TD" "$TDY"
            msgfmt --java2 --source -r "$CLASS" -l "$LG" -d "$TD" "$i"
            if [ $? -ne 0 ]; then
                echo "ERROR - msgfmt (fast) failed on $i"; rm -rf "$TD"
                find "$CLASS_OUTPUT_DIR" -name "messages_${LG}.class" -exec rm -f {} \;
                RC=1; break
            fi
            mv "$TDX/messages_$LG.java" "$TDY"
            rm -rf "$TD"
        else
            msgfmt --java2 -r "$CLASS" -l "$LG" -d "$CLASS_OUTPUT_DIR" "$i"
            if [ $? -ne 0 ]; then
                echo "ERROR - msgfmt failed on $i"
                find "$CLASS_OUTPUT_DIR" -name "messages_${LG}.class" -exec rm -f {} \;
                RC=1; break
            fi
        fi
    done

# =========================================================================
# TYPE=mo -- gettext .mo files (e.g., shell script translations)
# =========================================================================
elif [ "$TYPE" = "mo" ]; then

    ALL_UPTODATE=1
    for i in $PO_GLOB; do
        LG=$(basename "$i" .po)
        LG="${LG#messages_}"
        [ "$LG" = "en" ] && continue
        MO_FILE="$BD/$(echo "$MO_FILE_PATTERN" | sed "s/\$LG/$LG/g")"
        if [ ! -s "$MO_FILE" ] || [ "$i" -nt "$MO_FILE" ]; then
            ALL_UPTODATE=0; break
        fi
    done
    if [ "$ALL_UPTODATE" = "1" ] && [ "$POUPDATE" != "1" ]; then
        rm -f "$TMPFILE"; exit 0
    fi

    for i in $PO_GLOB; do
        LG=$(basename "$i" .po)
        LG="${LG#messages_}"

        if [ -n "$LG2" ]; then
            [ "$LG" != "$LG2" ] && continue || echo "INFO: Language update is set to [$LG2] only."
        fi

        if [ "$POUPDATE" = "1" ]; then
            find $MO_SOURCE_PATHS $MO_SOURCE_FILTER -newer "$i" > "$TMPFILE" 2>/dev/null
        fi

        MO_FILE="$BD/$(echo "$MO_FILE_PATTERN" | sed "s/\$LG/$LG/g")"
        if [ -s "$MO_FILE" ] && [ "$MO_FILE" -nt "$i" ] && [ ! -s "$TMPFILE" ]; then
            continue
        fi

        if [ "$POUPDATE" = "1" ]; then
            echo "Updating $i from source tags..."
            find $MO_SOURCE_PATHS $MO_SOURCE_FILTER > "$TMPFILE" 2>/dev/null

            xgettext -f "$TMPFILE" -F -L "$XGETTEXT_LANG" --from-code=UTF-8 \
                $XGETTEXT_EXTRA_FLAGS -o "${i}t"
            if [ $? -ne 0 ]; then
                echo "ERROR - xgettext failed on $i"
                rm -f "${i}t"; RC=1; break
            fi

            msgmerge -q -U -N --backup=none "$i" "${i}t"
            if [ $? -ne 0 ]; then
                echo "ERROR - msgmerge failed on $i"
                rm -f "${i}t"; RC=1; break
            fi
            rm -f "${i}t"
            touch "$i"
        fi

        [ "$LG" = "en" ] && continue

        MO_DIR=$(dirname "$MO_FILE")
        mkdir -p "$MO_DIR"
        echo "Generating $LG ResourceBundle..."
        msgfmt -o "$MO_FILE" "$i"
        if [ $? -ne 0 ]; then
            echo "ERROR - msgfmt failed on $i"
            rm -rf "$MO_DIR"; RC=1; break
        fi
    done
fi

# =========================================================================
# Translation coverage summary
# =========================================================================
if [ "$NOCOVERAGE" = "0" ]; then
    MODULE=$(pwd | sed 's,.*/\([^/]*/[^/]*\)$,\1,')
    echo "=== Translation coverage ($MODULE) ==="

    TOTAL_ALL=0
    TRANS_ALL=0
    LINE=""
    N=0
    for i in $PO_GLOB; do
        LG=$(basename "$i" .po)
        LG="${LG#messages_}"
        [ "$LG" = "en" ] && continue

        stats=$(msgfmt --statistics "$i" 2>&1)
        eval "$(echo "$stats" | sed 's/, */;/g' | awk -F'; ' '
        {
            t = 0; f = 0; u = 0
            for (i = 1; i <= NF; i++) {
                if ($i ~ /translated/) t = $i + 0
                if ($i ~ /fuzzy/) f = $i + 0
                if ($i ~ /untranslated/) u = $i + 0
            }
            print "TRANS=" t "; FUZZY=" f "; UNTRANS=" u
        }')"

        TOTAL=$((TRANS + FUZZY + UNTRANS))
        TOTAL_ALL=$((TOTAL_ALL + TOTAL))
        TRANS_ALL=$((TRANS_ALL + TRANS))
        PCT=0
        [ "$TOTAL" -gt 0 ] && PCT=$((TRANS * 100 / TOTAL))

        LINE="$LINE	$LG $PCT%"
        N=$((N + 1))
        if [ $((N % 6)) -eq 0 ]; then
            echo " $LINE"
            LINE=""
        fi
    done
    [ -n "$LINE" ] && echo " $LINE"
    if [ "$TOTAL_ALL" -gt 0 ]; then
        PCT_ALL=$((TRANS_ALL * 100 / TOTAL_ALL))
        printf "  Total: %d/%d (%d%%)\n" "$TRANS_ALL" "$TOTAL_ALL" "$PCT_ALL"
    fi
fi

rm -f "$TMPFILE"
exit $RC
