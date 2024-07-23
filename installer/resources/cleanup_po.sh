#!/bin/sh

# Initialize counters
num_files=0
num_fuzzy=0
no_wrap=0
no_location=0
remove_comments=0

# Check if the directory argument is provided first
if [ "$#" -lt 1 ]; then
    echo " ! Error: A directory is required."
    echo " > Usage: $0 <directory> [--no-wrap] [--no-location] [--no-comments]"
    exit 1
fi

directory="$1"

# Shift the directory argument to start parsing options
shift

# Parse optional arguments
while [ $# -gt 0 ]; do
    case "$1" in
        --no-wrap)
            no_wrap=1
            ;;
        --no-location)
            no_location=1
            ;;
        --no-comments)
            remove_comments=1
            ;;
        *)
            echo " ! Error: Unrecognized option '$1'."
            echo " > Usage: $0 <directory> [--no-wrap] [--no-location] [--no-comments]"
            exit 1
            ;;
    esac
    shift
done

# Temporary file to store .po file paths
tmpfile=$(mktemp)
trap "rm -f $tmpfile" EXIT

# Find and list .po files
find "$directory" -type f -name "*.po" -print0 | tr '\0' '\n' > "$tmpfile"

# Process each .po file
while IFS= read -r po_file; do
    num_fuzzy_entries=$(grep -c '^#, fuzzy' "$po_file")
    num_fuzzy=$((num_fuzzy + num_fuzzy_entries))

    msgattrib_cmd="msgattrib --no-fuzzy"
    if [ $no_wrap -eq 1 ]; then
        msgattrib_cmd="$msgattrib_cmd --no-wrap"
    fi
    if [ $no_location -eq 1 ]; then
        msgattrib_cmd="$msgattrib_cmd --no-location"
    fi
    msgattrib_cmd="$msgattrib_cmd --output-file=\"$po_file\" \"$po_file\""

    eval "$msgattrib_cmd"

    # Prepare sed commands based on flags
    sed_commands="
        /^\"Report-Msgid-Bugs-To:.*\"$/d;
        /^\"POT-Creation-Date:.*\"$/d;
        /^\"Project-Id-Version:.*\"$/d;
        /^\"Last-Translator:.*\"$/d;
        /^\"Language-Team:.*\"$/d;
        /^\"PO-Revision-Date:.*\"$/d;
        /^\"X-Generator:.*\"$/d
    "

    if [ $remove_comments -eq 1 ]; then
        sed_commands="$sed_commands; /^#\\./d"
    fi

    # Apply sed commands to the file
    sed -i "$sed_commands" "$po_file"

    num_files=$((num_files + 1))
done < "$tmpfile"

echo " > Processed $num_files .po files."
echo " > Removed $num_fuzzy fuzzy translations."

if [ $no_wrap -eq 1 ]; then
    echo " > Line wrapping has been removed from .po files."
fi
if [ $no_location -eq 1 ]; then
    echo " > Code location comments have been removed from .po files."
fi
if [ $remove_comments -eq 1 ]; then
    echo " > Lines starting with '#.' have been removed from .po files."
fi