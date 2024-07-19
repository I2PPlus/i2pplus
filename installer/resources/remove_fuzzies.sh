#!/bin/sh

# Recursively remove fuzzy translations from .po files with an option to prevent line wrapping
# Usage: remove_fuzzy_translations.sh <directory> [--no-wrap]

# Initialize counters
num_files=0
num_fuzzy=0
no_wrap=0

# Check and parse arguments
if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
    echo " ! Error: Incorrect number of arguments."
    echo " > Usage: $0 <directory> [--no-wrap]"
    echo " > This script removes fuzzy translations from .po files."
    echo " > Optional '--no-wrap' flag removes line wrapping in .po files."
    exit 1
fi

directory="$1"

# Check for --no-wrap argument
if [ "$#" -eq 2 ] && [ "$2" = "--no-wrap" ]; then
    no_wrap=1
fi

# Create a temporary file to hold the names of the .po files
tmpfile=$(mktemp)
trap "rm -f $tmpfile" EXIT

# Write the names of the .po files to the temporary file, replacing '\0' with '\n'
find "$directory" -type f -name "*.po" -print0 | tr '\0' '\n' > "$tmpfile"

# Iterate over all .po files in the given directory and subdirectories
while IFS= read -r po_file; do
  # Count the number of fuzzy entries before removal
  num_fuzzy_entries=$(grep -c '^#, fuzzy' "$po_file")
  num_fuzzy=$((num_fuzzy + num_fuzzy_entries))

  # Define the msgattrib command with or without --no-wrap
  if [ $no_wrap -eq 1 ]; then
    msgattrib_cmd="msgattrib --no-fuzzy --no-wrap --output-file='${po_file}' '${po_file}'"
  else
    msgattrib_cmd="msgattrib --no-fuzzy --output-file='${po_file}' '${po_file}'"
  fi

  # Execute the msgattrib command
  eval $msgattrib_cmd

  # Remove the specified header lines using sed
  sed -i '/^"Report-Msgid-Bugs-To:.*$/d; /^"POT-Creation-Date:.*$/d; /^"Project-Id-Version:.*$/d' "$po_file"

  # Increment file counter
  num_files=$((num_files + 1))
done < "$tmpfile"

# Report results
echo " > Processed $num_files .po files."
echo " > Removed $num_fuzzy fuzzy translations."
if [ $no_wrap -eq 1 ]; then
  echo " > Any line wrapping found in .po files has been removed."
fi