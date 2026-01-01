#!/bin/bash

if [ -z "$1" ]; then
    echo "Usage: $0 <folder_path>"
    exit 1
fi

folder="$1"

if [ ! -d "$folder" ]; then
    echo "Error: '$folder' is not a directory"
    exit 1
fi

po_files=$(find "$folder" -maxdepth 1 -name "*_en.po" && find "$folder" -maxdepth 1 -name "*.po" | grep -v "_en.po" | sort)

if [ -z "$po_files" ]; then
    echo "No .po files found in '$folder'"
    exit 0
fi

echo "Translation status for .po files in '$folder':"
echo "================================================================="

for po_file in $po_files; do
    filename=$(basename "$po_file")
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