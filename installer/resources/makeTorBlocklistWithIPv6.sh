#!/bin/sh

# Downloads https://check.torproject.org/torbulkexitlist and
# https://www.dan.me.uk/torlist/?exit using specified http proxy
# and then creates a consolidated list of IPv4 addresses, concatenates
# a sorted list of IPv6 addresses, and removes offer to remove the
# downloaded source files

# Specify input, output, and proxy variables
input_file="torbulkexitlist"
second_list_input_file="torlist_exit"
output_file="blocklist_tor.txt"
http_proxy=${http_proxy:-"http://127.0.0.1:7777"}

# Variables
url="https://check.torproject.org/$input_file"
second_list_url="https://www.dan.me.uk/torlist/?exit"
today="$(date '+%d %B %Y')"

# Check if curl is installed
if [ -z "$(which curl)" ]; then
  echo " > curl is not installed. Please install curl to use this script."
  exit 1
fi

# Check if input files already exist
if [ -f $input_file ]; then
  read -p "The file $input_file already exists. Do you want to delete it? [y/N] " choice
  case "$choice" in
    y|Y )
      rm $input_file
      echo " > Deleted existing local copy of $input_file"
      ;;
    * )
      echo " > Keeping existing local copy of $input_file"
      # Skip the download process for this file
      download_torbulkexitlist=false
      ;;
  esac
else
  # Set default value for download
  download_torbulkexitlist=true
fi

if [ -f $second_list_input_file ]; then
  read -p "The file $second_list_input_file already exists. Do you want to delete it? [y/N] " choice
  case "$choice" in
    y|Y )
      rm $second_list_input_file
      echo " > Deleted existing local copy of $second_list_input_file"
      ;;
    * )
      echo " > Keeping existing local copy of $second_list_input_file"
      # Skip the download process for this file
      download_second_list=false
      ;;
  esac
else
  # Set default value for download
  download_second_list=true
fi

# Download the latest list from Tor Project if needed
if $download_torbulkexitlist; then
  echo " > Downloading the latest list from Tor Project via specified proxy $http_proxy..."
  curl -s -o $input_file -x $http_proxy $url
  if [ $? -ne 0 ]; then
    echo " > Failed to download the latest list using proxy ${http_proxy}. Please check your proxy settings and try again."
    exit 1
  fi
fi

# Download the second list from Dan.me.uk if needed
if $download_second_list; then
  echo " > Downloading the second list from Dan.me.uk via specified proxy $http_proxy..."
  curl -s -o $second_list_input_file -x $http_proxy $second_list_url
  if [ $? -ne 0 ]; then
    echo " > Failed to download the second list using proxy ${http_proxy}. Please check your proxy settings and try again."
    exit 1
  fi
fi

# Concatenate the two lists, filter for IPv4 addresses, sort, and remove duplicates
concatenated_ips=$(cat $input_file $second_list_input_file | grep -P '^([0-9]{1,3}\.){3}[0-9]{1,3}$' | sort -u)

# Count the number of IPs in the concatenated list
ips_count=$(echo "$concatenated_ips" | wc -l)
echo " > Number of IPv4 addresses in the concatenated list: $ips_count"
echo " > Sorting IPv4 addresses and consolidating ranges, please stand by..."

# Initialize variables
start_ip=""
end_ip=""

cp $output_file ${output_file}.bak

# Print URL and date to the output file
echo "# $url + second_list_url" > $output_file
echo "# $today" >> $output_file

# Function to print the IP range with prefix
print_range() {
  if [ "$start_ip" = "$end_ip" ]; then
    echo "Tor Exit:$start_ip" >> $output_file
  else
    echo "Tor Exit:$start_ip-$end_ip" >> $output_file
  fi
}

# Loop through the sorted IPv4 addresses to find consecutive IPs
start_time=$(date +%s)
for ip in $concatenated_ips; do
  if [ "$start_ip" = "" ]; then
    start_ip=$ip
  elif [ "$ip" != $(echo -n "$end_ip" | awk -F. '{ printf "%d.%d.%d.%d", $1, $2, $3, $4 + 1}') ]; then
    print_range
    start_ip=$ip
  fi
  end_ip=$ip
done
end_time=$(date +%s)
echo " > Time taken to process IPv4 addresses and identify ranges: $(($end_time - $start_time)) seconds"

# Print the last IPv4 range
print_range

# Append and sort IPv6 addresses from the second list
echo "# IPv6" >> $output_file

# Count the number of IPv6 addresses before sorting
ipv6_count=$(grep -vP '^([0-9]{1,3}\.){3}[0-9]{1,3}$' $second_list_input_file | wc -l)
echo " > Number of IPv6 addresses in the second list: $ipv6_count"
echo " > Sorting IPv6 addresses, please stand by..."

# Create a temporary file to hold the sorted IPv6 addresses
tmpfile=$(mktemp)

# Filter for IPv6 addresses, sort them, and save to the temporary file
grep -vP '^([0-9]{1,3}\.){3}[0-9]{1,3}$' $second_list_input_file | sort > "$tmpfile"

# Read from the temporary file, transform the addresses, and write to the output file
while IFS= read -r ip; do
  ip_transformed=$(echo "$ip" | tr ':' ';')
  echo "Tor Exit:$ip_transformed" >> $output_file
done < "$tmpfile"

# After sorting IPv6 addresses, log the completion of the sorting process
echo " > $ipv6_count IPv6 addresses sorted"

# Clean up the temporary file
rm "$tmpfile"

# Count total entries in the output file
output_count=$(wc -l < "$output_file")
ips_count=$((output_count - 2))
echo " > $ips_count consolidated IP ranges and transformed IPv6 addresses saved to: $output_file"

# Clean up: Offer to remove downloaded input files
if [ -f $input_file ] || [ -f $second_list_input_file ]; then
  read -p "Do you want to delete the downloaded source files ($input_file and $second_list_input_file)? [y/N] " choice
  case "$choice" in
    y|Y )
      rm $input_file $second_list_input_file
      echo " > Deleted $input_file and $second_list_input_file"
      ;;
    * )
      echo " > Keeping $input_file and $second_list_input_file"
      ;;
  esac
fi