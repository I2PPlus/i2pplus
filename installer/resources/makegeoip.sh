#!/bin/sh
#
# Fetch the latest file from db-ip.com
# Run this from installer/resources/

VER=`date +%Y-%m`
DL=dbip-country-lite-${VER}.mmdb.gz
FILE=GeoLite2-Country.mmdb.gz
UPDATE_SCRIPT=makegeoip.sh
export HTTP_PROXY=http://127.0.0.1:4444

# Check for the presence of the update script in the current directory
if [ ! -f "$UPDATE_SCRIPT" ]; then
    echo " ! Error: This script must be executed from the same folder where makegeoip.sh is located."
    exit 1
fi

# Use curl to perform a test request and extract the IP address
IP=$(curl -sS --proxy $HTTP_PROXY https://icanhazip.com/)
echo " > GeoIP database retrieval from db.ip.com"
echo " > The IP address of the configured http proxy server is: $IP"

# Prompt the user to continue
read -p " > Press ENTER to continue or CTRL+C to cancel" dummy

# Check for the existence of "$FILE.old"
if [ -f "${FILE}.old" ]; then
    echo " > Deleting the previous backup file ${FILE}.old"
    rm -f "${FILE}.old" >/dev/null 2>&1
fi

# If $FILE exists, rename it before proceeding
if [ -f "$FILE" ]; then
    echo " > The previous file $FILE has been renamed to ${FILE}.old"
    mv -v "$FILE" "${FILE}.old" >/dev/null 2>&1
fi

if command -v curl &> /dev/null; then
    curl -x $HTTP_PROXY https://download.db-ip.com/free/$DL -o $FILE || {
    echo " > Attempting to download https://download.db-ip.com/free/$DL using curl..."
        if command -v wget &> /dev/null; then
            wget $HTTP_PROXY https://download.db-ip.com/free/$DL -O $FILE || exit 1
        else
            echo " ! Warning: curl is not detected, falling back to wget for download."
            echo " > Attempting to download https://download.db-ip.com/free/$DL using wget..."
            wget -e use_proxy=yes -e http_proxy=$HTTP_PROXY https://download.db-ip.com/free/$DL -O $FILE || exit 1
        fi
    }
else
    if command -v wget &> /dev/null; then
        wget -e use_proxy=yes -e http_proxy=$HTTP_PROXY https://download.db-ip.com/free/$DL -O $FILE || exit 1
    else
        echo "! Error: Neither curl nor wget is installed. Please install curl or wget before running this command."
        exit 1
    fi
fi

# Exit on download error
if [ $? -ne 0 ]; then
    echo " ! Error: Failed to download the file using the proxy. Please check your proxy configuration and try again."
    if [ -f "$FILE.old" ]; then
        echo " > The file ${FILE}.old has been restored"
        mv -v "${FILE}.old" "$FILE" >/dev/null 2>&1
    fi
    exit 1
fi

# Check if downloaded file exists
if [ ! -f $FILE ]; then
    echo " ! Error: Downloaded file $FILE is not found. Please check your internet connection and try again."
    if [ -f "$FILE.old" ]; then
        echo " > The file ${FILE}.old has been restored"
        mv -v "${FILE}.old" "$FILE" >/dev/null 2>&1
    fi
    exit 1
fi

# Delete old file and rename new file
# rm -f "${FILE}.old" >/dev/null 2>&1
mv -v "$DL" "$FILE" >/dev/null 2>&1
echo " > The file $DL has been renamed to $FILE"

# Check file permissions and hash
echo " > Checking file permissions and hash"
ls -l "$FILE"
OLD_HASH=$(sha256sum "./$FILE.old" | cut -f 1 -d ' ')
NEW_HASH=$(sha256sum "./$FILE" | cut -f 1 -d ' ')
echo "Old hash: $OLD_HASH"
echo "New hash: $NEW_HASH"

if [ -n "$OLD_HASH" ] && [ -n "$NEW_HASH" ] && [ "$OLD_HASH" != "$NEW_HASH" ]; then
    echo " > Update successful (hashes do not match) -> deleting $FILE.old"
    #cp -v "$FILE" "./installer/resources/$FILE"
    rm -f "${FILE}.old" >/dev/null 2>&1
fi