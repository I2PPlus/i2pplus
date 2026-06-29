#!/bin/sh
#
# Fetch the latest GeoIP and ASN databases from db-ip.com
# Run this from installer/resources/geoip/
#
# Note: Set HTTP_PROXY if using a proxy, or remove the proxy settings
# to download directly.

VER=`date +%Y-%m`
DL=dbip-country-lite-${VER}.mmdb.gz
FILE=GeoLite2-Country.mmdb.gz
ASN_DL=dbip-asn-lite-${VER}.mmdb.gz
ASN_FILE=db-ip-asn.mmdb.gz
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

if [ -x "$(command -v curl)" ]; then
    echo " > Attempting to download https://download.db-ip.com/free/$DL using curl..."
    curl -s -x $HTTP_PROXY https://download.db-ip.com/free/$DL -o $FILE
else
    echo " ! Error: Please install curl before running this command."
    exit 1
fi

# Check if downloaded file is smaller than 2MB
if [ -f "$FILE" ]; then
    downloaded_file_size=$(stat -c%s "$FILE")
    if [ $downloaded_file_size -lt 2097152 ]; then # 2MB = 2097152 bytes
        echo " ! Error: File size is too small (less than 2MB), restoring original file."
        if [ -f "$FILE.old" ]; then
            mv -v "${FILE}.old" "$FILE" >/dev/null 2>&1
        fi
        exit 1
    fi
fi

# Exit on download error
if [ ! -f "$FILE" ]; then
    echo " ! Error: Failed to download the file using the proxy. Please check your proxy configuration and try again."
    if [ -f "$FILE.old" ]; then
        echo " > The file ${FILE}.old has been restored"
        mv -v "${FILE}.old" "$FILE" >/dev/null 2>&1
    fi
    exit 1
fi

# Check if downloaded file exists
if [ ! -f $FILE ]; then
    echo " ! Error: Downloaded file $FILE not found. Please check your internet connection and try again."
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
echo " > Old hash: $OLD_HASH"
echo " > New hash: $NEW_HASH"

if [ -n "$OLD_HASH" ] && [ -n "$NEW_HASH" ] && [ "$OLD_HASH" != "$NEW_HASH" ]; then
    echo " > Update successful (hashes do not match) -> deleting $FILE.old"
    rm -f "${FILE}.old" >/dev/null 2>&1
fi

# --- ASN Database Download ---

echo ""
echo " > ASN database retrieval from db-ip.com"

# Check for the existence of "$ASN_FILE.old"
if [ -f "${ASN_FILE}.old" ]; then
    echo " > Deleting the previous backup file ${ASN_FILE}.old"
    rm -f "${ASN_FILE}.old" >/dev/null 2>&1
fi

# If $ASN_FILE exists, rename it before proceeding
if [ -f "$ASN_FILE" ]; then
    echo " > The previous file $ASN_FILE has been renamed to ${ASN_FILE}.old"
    mv -v "$ASN_FILE" "${ASN_FILE}.old" >/dev/null 2>&1
fi

if [ -x "$(command -v curl)" ]; then
    echo " > Attempting to download https://download.db-ip.com/free/$ASN_DL using curl..."
    curl -s -x $HTTP_PROXY https://download.db-ip.com/free/$ASN_DL -o $ASN_FILE
else
    echo " ! Error: Please install curl before running this command."
    exit 1
fi

# Check if downloaded file is smaller than 2MB
if [ -f "$ASN_FILE" ]; then
    downloaded_file_size=$(stat -c%s "$ASN_FILE")
    if [ $downloaded_file_size -lt 2097152 ]; then # 2MB = 2097152 bytes
        echo " ! Error: ASN file size is too small (less than 2MB), restoring original file."
        if [ -f "${ASN_FILE}.old" ]; then
            mv -v "${ASN_FILE}.old" "$ASN_FILE" >/dev/null 2>&1
        fi
        exit 1
    fi
fi

# Exit on download error
if [ ! -f "$ASN_FILE" ]; then
    echo " ! Error: Failed to download the ASN file using the proxy. Please check your proxy configuration and try again."
    if [ -f "${ASN_FILE}.old" ]; then
        echo " > The file ${ASN_FILE}.old has been restored"
        mv -v "${ASN_FILE}.old" "$ASN_FILE" >/dev/null 2>&1
    fi
    exit 1
fi

# Check if downloaded file exists
if [ ! -f "$ASN_FILE" ]; then
    echo " ! Error: Downloaded ASN file $ASN_FILE not found. Please check your internet connection and try again."
    if [ -f "${ASN_FILE}.old" ]; then
        echo " > The file ${ASN_FILE}.old has been restored"
        mv -v "${ASN_FILE}.old" "$ASN_FILE" >/dev/null 2>&1
    fi
    exit 1
fi

# Check file permissions and hash for ASN
if [ -f "$ASN_MMDB" ]; then
    echo " > Checking ASN file permissions"
    ls -l "$ASN_MMDB"
    OLD_HASH=$(sha256sum "./${ASN_MMDB}.old" 2>/dev/null | cut -f 1 -d ' ')
    NEW_HASH=$(sha256sum "./$ASN_MMDB" | cut -f 1 -d ' ')
    echo " > Old hash: $OLD_HASH"
    echo " > New hash: $NEW_HASH"

    if [ -n "$OLD_HASH" ] && [ -n "$NEW_HASH" ] && [ "$OLD_HASH" != "$NEW_HASH" ]; then
        echo " > ASN update successful (hashes do not match) -> deleting ${ASN_MMDB}.old"
        rm -f "${ASN_MMDB}.old" >/dev/null 2>&1
    fi
fi

echo ""
echo " > Done."
