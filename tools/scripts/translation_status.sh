#!/bin/bash

MODE="diff"
case "$1" in
    -h|--help)
        echo "Usage: $0 [--stats] <folder_path>"
        echo "  Recursively scans <folder_path> for .po files and reports translation status."
        echo "  Each directory containing .po files is shown with its own header."
        echo "  Default mode compares each translation's msgid set against messages_en.po"
        echo "  (matched / missing / extra) so you can verify every file matches the template."
        echo "  Use --stats for the legacy msgfmt --statistics percentage view."
        exit 1
        ;;
    --stats)
        MODE="stats"
        shift
        ;;
esac

if [ -z "$1" ]; then
    echo "Usage: $0 [--stats] <folder_path>"
    exit 1
fi

folder="$1"

if [ ! -d "$folder" ]; then
    echo "Error: '$folder' is not a directory"
    exit 1
fi

# Collect every .po file under the path. Grouping is done in python below, so
# the order here only has to be stable.
# Use a temp file list (POSIX-safe; avoids bash process-substitution which fails under dash).
po_list=$(mktemp /tmp/translation_status.XXXXXX.list)
find "$folder" -type f -name "*.po" | sort > "$po_list"

if [ ! -s "$po_list" ]; then
    rm -f "$po_list"
    echo "No .po files found in '$folder'"
    exit 0
fi

po_files=""
while IFS= read -r f; do
    po_files="$po_files $f"
done < "$po_list"
rm -f "$po_list"

if [ "$MODE" != "stats" ]; then
    tmp_py=$(mktemp /tmp/msgid_diff.XXXXXX.py)
    cat > "$tmp_py" <<'PYEOF'
import polib, sys, os
from collections import OrderedDict

def msgids(path):
    # Obsolete (#~) entries are msgids that once existed and no longer do, so
    # msgmerge leaves them in the translations forever. Counting them as
    # "extra" reports every long-lived catalog as mismatched, so skip them.
    return set(e.msgid for e in polib.pofile(path) if e.msgid and not e.obsolete)

# Group by directory here rather than relying on the order of the file list,
# so each directory prints exactly one header with its template first.
groups = OrderedDict()
for po_file in sys.argv[1:]:
    groups.setdefault(os.path.dirname(po_file), []).append(po_file)

for po_dir, files in groups.items():
    names = [os.path.basename(f) for f in files]
    if "messages_en.po" not in names:
        # Nothing to diff against. Comparing against an empty set would mark
        # every file 100% extra; the distro/ and man/ catalogs ship without a
        # template, so just say they were not diffed.
        print()
        print("msgid diff in '%s':" % po_dir)
        print("=================================================================")
        print("  no messages_en.po template - %d file(s) not diffed" % len(names))
        continue
    print()
    print("msgid diff vs messages_en.po in '%s':" % po_dir)
    print("=================================================================")
    en = msgids(os.path.join(po_dir, "messages_en.po"))
    print("%-40s [ %d msgids (template) ]" % ("messages_en.po", len(en)))
    for po_file in sorted(files):
        filename = os.path.basename(po_file)
        if filename == "messages_en.po":
            continue
        ids = msgids(po_file)
        missing = len(en - ids)
        extra = len(ids - en)
        matched = len(en & ids)
        if missing == 0 and extra == 0:
            flag = "OK"
        else:
            flag = "MISMATCH"
        print("%-40s matched=%-5d missing=%-5d extra=%-5d %s" % (filename, matched, missing, extra, flag))
PYEOF
    python3 "$tmp_py" $po_files
    rm -f "$tmp_py"
    exit 0
fi

last_dir=""
for po_file in $po_files; do
    po_dir=$(dirname "$po_file")
    filename=$(basename "$po_file")

    if [ "$po_dir" != "$last_dir" ]; then
        echo
        echo "Translation status for .po files in '$po_dir':"
        echo "================================================================="
        last_dir="$po_dir"
    fi

    stats=$(msgfmt --statistics "$po_file" 2>&1)

    translated=$(echo "$stats" | grep -oP '\d+(?= translated message)')
    fuzzy=$(echo "$stats" | grep -oP '\d+(?= fuzzy translation)')
    untranslated=$(echo "$stats" | grep -oP '\d+(?= untranslated message)')

    translated=${translated:-0}
    fuzzy=${fuzzy:-0}
    untranslated=${untranslated:-0}

    total=$((translated + fuzzy + untranslated))
    if [[ "$filename" == *"_en.po" ]]; then
        indicator="[ $total messages ]"
    else
        if [ $total -gt 0 ]; then
            percentage=$((translated * 100 / total))
        else
            percentage=0
        fi
        indicator=$(printf "[%3d%% complete ]" "$percentage")
    fi

    if [[ "$filename" == *"_en.po" ]]; then
        printf "%-40s \e[100G%s\n" "$filename" "$indicator"
    else
        printf "%-40s %s\e[100G%s\n" "$filename" "$(echo "$stats" | tr '\n' ' ')" "$indicator"
    fi
done