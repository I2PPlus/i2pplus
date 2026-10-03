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

# The English template is an input to every translation, so a translation older
# than the template is stale even when its compiled bundle and its sources look
# current. This happens after a template-only run (poupdate-source), which
# rewrites messages_en.po without touching the other languages: their .po files
# are then older than the template while the mtime-only check below would call
# them up to date, leaving them permanently missing new msgids.
EN_PO_NEWEST=""
for f in $PO_GLOB; do
    case "$(basename "$f")" in
        *_en.po|en.po)
            if [ -z "$EN_PO_NEWEST" ] || [ "$f" -nt "$EN_PO_NEWEST" ]; then
                EN_PO_NEWEST="$f"
            fi
            ;;
    esac
done

# True when $1 (a translation) is out of date with respect to the template.
template_stale() {
    [ -n "$EN_PO_NEWEST" ] && [ "$EN_PO_NEWEST" -nt "$1" ]
}

# Advisory diagnostics dropped from the build log. Neither affects the
# generated .po/.mo/.class output. Set either to "none" in a
# bundle-messages.cfg to see the raw tool output.
#
# WARN_FILTER_FORLOOP: xgettext's Java scanner mis-counts the parentheses of a
# classic C-style for-loop header (for (init; cond; step)) and emits one
# "')' found where '}' was expected" warning per loop. The scan only locates
# comments and string literals, so extraction and the emitted line references
# stay correct.
#
# WARN_FILTER_EMBEDDED_URL: gettext flags URLs left inline in translatable
# strings. The routerconsole help pages link to around 45 external sites that
# way. This is a real i18n finding, but the alternative is editing ~45 msgids
# and invalidating their existing translations in every language, so it stays
# filtered until the help text is reworked. Unfixed, not ignored.
#
# The defaults are assigned in separate steps on purpose: a '}' inside the
# default of ${VAR:-...} terminates the expansion in dash, which breaks the
# pattern.
if [ -z "$WARN_FILTER_FORLOOP" ]; then
    WARN_FILTER_FORLOOP="')' found where '}' was expected"
fi
if [ -z "$WARN_FILTER_EMBEDDED_URL" ]; then
    WARN_FILTER_EMBEDDED_URL="Message contains an embedded URL"
fi

# filter_out <file> <fixed-string>: drop matching lines from <file> in place.
filter_out() {
    [ -n "$2" ] && [ "$2" != "none" ] || return 0
    _tmp="$1.f"
    grep -v -F -e "$2" "$1" > "$_tmp" 2>/dev/null
    mv -f "$_tmp" "$1"
}

run_xgettext() {
    _err="$TMPFILE.err"
    xgettext "$@" 2> "$_err"
    _rc=$?
    filter_out "$_err" "$WARN_FILTER_FORLOOP"
    filter_out "$_err" "$WARN_FILTER_EMBEDDED_URL"
    [ -s "$_err" ] && cat "$_err" >&2
    rm -f "$_err"
    return $_rc
}

run_msgmerge() {
    _err="$TMPFILE.err"
    msgmerge "$@" 2> "$_err"
    _rc=$?
    filter_out "$_err" "$WARN_FILTER_EMBEDDED_URL"
    [ -s "$_err" ] && cat "$_err" >&2
    rm -f "$_err"
    return $_rc
}

run_msgfmt() {
    _err="$TMPFILE.err"
    msgfmt "$@" 2> "$_err"
    _rc=$?
    filter_out "$_err" "$WARN_FILTER_EMBEDDED_URL"
    [ -s "$_err" ] && cat "$_err" >&2
    rm -f "$_err"
    return $_rc
}

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

    msgfmt -V 2>/dev/null | grep -q -E ' 0\.((19)|[2-9])| [1-9]\.'
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
        # Only this module's own generated classes count as up to date;
        # scanning the build root can mistake another module's (or the
        # gradle build's) output for ours and skip regeneration.
        CLASSFILE="$CLASS_OUTPUT_DIR/$PACKAGE_PATH/messages_$LG.class"
        if [ ! -s "$CLASSFILE" ] || [ "$i" -nt "$CLASSFILE" ]; then
            ALL_UPTODATE=0; break
        fi
    done
    if [ "$ALL_UPTODATE" = "1" ] && [ "$POUPDATE" != "1" ]; then
        # Leading '*' is deliberate: ConciseLogger only surfaces <exec> output
        # whose line starts with '*' (see tools/build ConciseLogger.messageLogged),
        # so an "INFO:" prefixed line is silently dropped from the build log.
        rm -f "$TMPFILE"; echo "* translations: already compiled, reusing cached bundles"; exit 0
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
        if ! template_stale "$i" && [ -s "$CLASSFILE" ] && [ "$CLASSFILE" -nt "$i" ] && [ ! -s "$TMPFILE" ]; then
            continue
        fi

        if [ "$POUPDATE" = "1" ]; then
            echo "Updating $i from source tags..."
            find $JPATHS -name '*.java' > "$TMPFILE" 2>/dev/null

            run_xgettext -f "$TMPFILE" -L "$XGETTEXT_LANG" --from-code=UTF-8 \
                $XGS $XGETTEXT_EXTRA_FLAGS $XGETTEXT_KEYWORDS -o "${i}t"
            if [ $? -ne 0 ]; then
                echo "ERROR - xgettext failed on $i, not updating translations"
                rm -f "${i}t"; RC=1; break
            fi

            run_msgmerge -q -U -N --backup=none "$i" "${i}t"
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
            run_msgfmt --java2 --source -r "$CLASS" -l "$LG" -d "$TD" "$i"
            if [ $? -ne 0 ]; then
                echo "ERROR - msgfmt (fast) failed on $i"; rm -rf "$TD"
                find "$CLASS_OUTPUT_DIR" -name "messages_${LG}.class" -exec rm -f {} \;
                RC=1; break
            fi
            mv "$TDX/messages_$LG.java" "$TDY"
            rm -rf "$TD"
        else
            run_msgfmt --java2 -r "$CLASS" -l "$LG" -d "$CLASS_OUTPUT_DIR" "$i"
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
        # Leading '*' is deliberate: ConciseLogger only surfaces <exec> output
        # whose line starts with '*' (see tools/build ConciseLogger.messageLogged).
        rm -f "$TMPFILE"; echo "* translations: already compiled, reusing cached bundles"; exit 0
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
        if ! template_stale "$i" && [ -s "$MO_FILE" ] && [ "$MO_FILE" -nt "$i" ] && [ ! -s "$TMPFILE" ]; then
            continue
        fi

        if [ "$POUPDATE" = "1" ]; then
            echo "Updating $i from source tags..."
            find $MO_SOURCE_PATHS $MO_SOURCE_FILTER > "$TMPFILE" 2>/dev/null

            run_xgettext -f "$TMPFILE" -F -L "$XGETTEXT_LANG" --from-code=UTF-8 \
                $XGETTEXT_EXTRA_FLAGS -o "${i}t"
            if [ $? -ne 0 ]; then
                echo "ERROR - xgettext failed on $i"
                rm -f "${i}t"; RC=1; break
            fi

            run_msgmerge -q -U -N --backup=none "$i" "${i}t"
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
        run_msgfmt -o "$MO_FILE" "$i"
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
    MASTER_COUNT=0
    for i in $PO_GLOB; do
        LG=$(basename "$i" .po)
        LG="${LG#messages_}"
        if [ "$LG" = "en" ]; then
            MASTER_COUNT=$(grep -c '^msgid ' "$i" 2>/dev/null)
            MASTER_COUNT=${MASTER_COUNT:-0}
            [ "$MASTER_COUNT" -gt 0 ] && MASTER_COUNT=$((MASTER_COUNT - 1))
            break
        fi
    done
    echo "* Translation coverage for: $MODULE ($MASTER_COUNT strings)"

    TOTAL_ALL=0
    TRANS_ALL=0
    LINE=""
    N=0
    for i in $PO_GLOB; do
        LG=$(basename "$i" .po)
        LG="${LG#messages_}"
        [ "$LG" = "en" ] && continue

        # -o /dev/null is required, not cosmetic: msgfmt with no -o/-d writes its
        # default output to ./messages.mo even when only --statistics is asked
        # for. Run from the module directory (--dir ".") that dropped a compiled
        # catalog into the source tree for every language in the glob.
        stats=$(msgfmt --statistics -o /dev/null "$i" 2>&1)
        eval "$(echo "$stats" | sed 's/, */; /g' | awk -F'; ' '
        {
            t = 0; f = 0; u = 0
            for (i = 1; i <= NF; i++) {
                if ($i ~ /^[0-9]+ translated /) t = $i + 0
                if ($i ~ /^[0-9]+ fuzzy /) f = $i + 0
                if ($i ~ /^[0-9]+ untranslated /) u = $i + 0
            }
            print "TRANS=" t "; FUZZY=" f "; UNTRANS=" u
        }')"

        TOTAL=$((TRANS + FUZZY + UNTRANS))
        TOTAL_ALL=$((TOTAL_ALL + TOTAL))
        TRANS_ALL=$((TRANS_ALL + TRANS))
        PCT=0
        [ "$TOTAL" -gt 0 ] && PCT=$((TRANS * 100 / TOTAL))

        ENTRY=$(printf "%-8s" "$LG $PCT%")
        if [ -z "$LINE" ]; then
            LINE="$ENTRY"
        else
            LINE="$LINE $ENTRY"
        fi
        N=$((N + 1))
        if [ $((N % 6)) -eq 0 ]; then
            echo "*   $LINE"
            LINE=""
        fi
    done
    [ -n "$LINE" ] && echo "*   $LINE"
    if [ "$TOTAL_ALL" -gt 0 ]; then
        PCT_ALL=$((TRANS_ALL * 100 / TOTAL_ALL))
        printf "*   Total: %d / %d strings (%d%%)\n" "$TRANS_ALL" "$TOTAL_ALL" "$PCT_ALL"
    fi
fi

rm -f "$TMPFILE"
exit $RC
