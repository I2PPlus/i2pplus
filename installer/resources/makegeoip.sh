#!/bin/sh
#
# Fetch the latest file from db-ip.com
#

# rm -rf GeoLite2-Country_20*
# mv GeoLite2-Country.tar.gz GeoIPCountry2-Country.tar.gz.bak
# mv GeoLite2-Country.mmdb.gz GeoIPCountry2-Country.mmdb.gz.bak
# wget http://geolite.maxmind.com/download/geoip/database/GeoLite2-Country.tar.gz || exit 1
# tar xzf GeoLite2-Country.tar.gz || exit 1
# mv GeoLite2-Country_20*/GeoLite2-Country.mmdb . || exit 1
# gzip GeoLite2-Country.mmdb || exit 1
# rm -rf GeoLite2-Country_20*
# ls -l GeoLite2-Country.mmdb.gz

rm -f dbip-country-lite-*
VER=`date +%Y-%m`
DL=dbip-country-lite-${VER}.mmdb.gz
FILE=GeoLite2-Country.mmdb.gz
wget https://download.db-ip.com/free/$DL || exit 1
mv $FILE ${FILE}.bak
mv $DL $FILE
ls -l $FILE
