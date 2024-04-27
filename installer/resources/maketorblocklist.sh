#!/bin/bash

# Download https://check.torproject.org/torbulkexitlist
# and then run this script in the same folder to generate
# a consolidated, sorted list

# Specify input and output file variables
input_file="torbulkexitlist"
output_file="blocklist_tor.txt"

# Variables for comments
url="# https://check.torproject.org/torbulkexitlist"
today="# $(date '+%d %B %Y')"

# Check if the input file exists
if [ ! -f $input_file ]; then
  echo "Input file does not exist or is not a regular file."
  echo "Please ensure $input_file exists and is in the directory this script is being run from"
  exit 1
fi

# Count the number of IPs in the file
ips_count=$(wc -l < $input_file)
echo "Number of IPs in $input_file: $ips_count"
echo "Sorting IPs and consolidating ranges, please stand by..."

# Sort the list of IPs naturally
sorted_ips=$(cat $input_file | sort -V)

# Initialize variables
start_ip=""
end_ip=""
output=""

# Function to print the IP range
print_range() {
  if [ "$start_ip" = "$end_ip" ]; then
    output+="$start_ip\n"
  else
    output+="$start_ip-$end_ip\n"
  fi
}

# Loop through the sorted IPs to find consecutive IPs
for ip in $sorted_ips; do
  if [ "$start_ip" = "" ]; then
    start_ip=$ip
    end_ip=$ip
  elif [ $(expr $(cut -d\. -f4 <<< $ip) - $(cut -d\. -f4 <<< $end_ip)) -eq 1 ]; then
    end_ip=$ip
  else
    print_range
    start_ip=$ip
    end_ip=$ip
  fi
done

# Print the last range
print_range

# Print URL and date to the output file
output="$url\n$today\n$output"

# Remove trailing newlines from the output
output=$(echo -e "$output" | sed -e '$ { /^$/d }')

# Output the consolidated IPs to file
echo "Consolidated IPs saved to: $output_file"
echo -e "$output" > $output_file