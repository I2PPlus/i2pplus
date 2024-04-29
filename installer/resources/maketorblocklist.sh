#!/bin/bash

# Download https://check.torproject.org/torbulkexitlist
# and then run this script in the same folder to generate
# a consolidated, sorted list

# Specify input, output, and proxy variables
input_file="torbulkexitlist"
output_file="blocklist_tor.txt"
http_proxy="http://127.0.0.1:4444"

# Variables for comments
url="# https://check.torproject.org/torbulkexitlist"
today="# $(date '+%d %B %Y')"

# Check if curl is installed
if [ -z "$(which curl)" ]; then
  echo "> curl is not installed. Please install curl to use this script."
  exit 1
fi

# Remove existing input file if it exists
if [ -f $input_file ]; then
  rm $input_file
  echo " > Deleted existing local copy of $input_file"
fi

# Download the latest list from Tor Project
echo " > Downloading the latest list from Tor Project via specified proxy $http_proxy..."
curl -o $input_file -x $http_proxy https://check.torproject.org/torbulkexitlist
if [ $? -ne 0 ]; then
  echo " > Failed to download the latest list. Please check your proxy settings and try again."
  exit 1
fi

# Count the number of IPs in the file
ips_count=$(wc -l < $input_file)
echo " > Number of IPs in $input_file: $ips_count"
echo " > Sorting IPs and consolidating ranges, please stand by..."

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
echo -e "$output" > $output_file
echo " > Consolidated IPs saved to: $output_file"

# Clean up: Remove downloaded input file
rm $input_file
echo " > Deleted $input_file"
