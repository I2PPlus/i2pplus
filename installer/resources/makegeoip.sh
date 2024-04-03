#!/bin/sh
#
# Fetch the latest file from db-ip.com
# Run this from installer/resources/

VER=`date +%Y-%m`
DL=dbip-country-lite-${VER}.mmdb.gz
FILE=GeoLite2-Country.mmdb.gz
UPDATE_SCRIPT=makegeoip.sh
export HTTP_PROXY=http://127.0.0.1:4444

# Check for the presence of DL or FILE variables
if [ ! -f "$UPDATE_SCRIPT" ]; then
    echo " ! Error: This script must be executed from the same folder where makegeoip.sh is located."
    exit 1
fi

# Use curl to perform a test request and extract the IP address
IP=$(curl -sS --proxy $HTTP_PROXY https://icanhazip.com/)
echo " > GeoIP database retrieval from db.ip.com"
echo " > The IP address of the configured http proxy server is: $IP"

# Prompt the user to continue
read -p "> Press enter to continue or CTRL+C to cancel" dummy

# Delete old file (if it exists)
if [ -f "$FILE" ]; then
    echo " > The previous file $FILE has been renamed to ${FILE}.old"
    mv -v "$FILE" "${FILE}.old" >/dev/null 2>&1
fi

# Invoke wget using proxy and download file
echo " > Downloading from https://download.db-ip.com/free/$DL ..."
wget -e use_proxy=yes -e http_proxy=$HTTP_PROXY https://download.db-ip.com/free/$DL || exit 1

# Exit on download error
if [ $? -ne 0 ]; then
    echo " > Error: Failed to download the file using the proxy. Please check your proxy configuration and try again."
    if [ -f "$FILE.old" ]; then
        echo " > The file ${FILE}.old has been restored"
        mv -v "${FILE}.old" "$FILE" >/dev/null 2>&1
    fi
    exit 1
fi

# Check if downloaded file exists
if [ ! -f $FILE ]; then
    echo " > Error: Downloaded file $FILE is not found. Please check your internet connection and try again."
    if [ -f "$FILE.old" ]; then
        echo " > The file ${FILE}.old has been restored"
        mv -v "${FILE}.old" "$FILE" >/dev/null 2>&1
    fi
    exit 1
fi

# Delete old file and rename new file
rm -f "${FILE}.old" >/dev/null 2>&1
mv -v "$DL" "$FILE" >/dev/null 2>&1
echo " > The file $DL has been renamed to $FILE"

# Check file permissions and hash
echo " > Checking file permissions and hash"
ls -l $FILE
OLD_HASH=$(sha256sum ./installer/resources/$FILE | cut -f 1 -d ' ')
NEW_HASH=$(sha256sum ./$FILE | cut -f 1 -d ' ')
echo "Old hash: $OLD_HASH"
echo "New hash: $NEW_HASH"
if [ $OLD_HASH != $NEW_HASH ]; then
    echo " > The new file is different from the previously installed file. Updating installer/resources/$FILE"
    cp -v "$FILE" "./installer/resources/$FILE"
fi