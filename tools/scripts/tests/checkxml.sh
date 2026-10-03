#!/bin/sh
#
# Validate the hand-maintained XML and HTML files with xmllint.
# Prints only failures plus a one line summary, and exits nonzero if any fail.
#
# A few files carry an .xml extension but hold HTML (news.xml,
# initialNews*.xml), so those are parsed in HTML mode.
#
# zzz 2011-03
# public domain
#

# this script lives in tools/scripts/tests, so the repo root is three levels up
cd "$(dirname "$0")/../../.." || exit 1

if ! command -v xmllint >/dev/null 2>&1; then
  echo "xmllint not found: install libxml2-utils (Debian/Ubuntu) or libxml2 (Fedora)." >&2
  exit 1
fi

total=0
failed=0

# check <xmllint-mode> <file>...
#   An empty mode parses as XML, --html parses as HTML. A listed file that no
#   longer exists counts as a failure, so a moved or deleted file cannot quietly
#   drop out of the list unnoticed.
check() {
  mode="$1"
  shift
  for file in "$@"; do
    total=$((total + 1))
    if [ ! -f "$file" ]; then
      echo "MISSING  $file"
      failed=$((failed + 1))
      continue
    fi
    # HTML mode is a lenient parser that practically never sets a nonzero exit
    # status, so treat any output at all as a failure. It catches little, but it
    # costs nothing next to the XML pass.
    if output=$(xmllint $mode --noout "$file" 2>&1) && [ -z "$output" ]; then
      continue
    fi
    echo "FAILED   $file"
    if [ -n "$output" ]; then
      echo "$output" | sed 's/^/         /'
    fi
    failed=$((failed + 1))
  done
}

echo 'Checking XML...'
check "" \
  ./apps/addressbook/build.xml \
  ./apps/desktopgui/build.xml \
  ./apps/i2psnark/java/build.xml \
  ./apps/i2psnark/jetty-i2psnark.xml \
  ./apps/i2psnark/web.xml \
  ./apps/i2ptunnel/java/build.xml \
  ./apps/i2ptunnel/jsp/web.xml \
  ./apps/jetty/build.xml \
  ./apps/ministreaming/java/build.xml \
  ./apps/routerconsole/java/build.xml \
  ./apps/routerconsole/jsp/web.xml \
  ./apps/sam/java/build.xml \
  ./apps/streaming/java/build.xml \
  ./apps/susidns/src/build.xml \
  ./apps/susidns/src/WEB-INF/web-template.xml \
  ./apps/susimail/build.xml \
  ./apps/susimail/src/WEB-INF/web.xml \
  ./apps/systray/java/build.xml \
  ./build.xml \
  ./core/java/build.xml \
  ./installer/i2pinstaller.xml \
  ./installer/i2pstandalone.xml \
  ./installer/lib/izpack/4/install.xml \
  ./installer/lib/izpack/5/install5.xml \
  ./installer/lib/izpack/5/customicons.xml \
  ./installer/lib/izpack/5/i2pinstaller.xml \
  ./installer/lib/izpack/resources/CustomLangPack.xml_eng \
  ./installer/lib/izpack/resources/shortcutSpec.xml \
  ./installer/lib/izpack/5/patches/resources/installer/ind.xml \
  ./installer/lib/izpack/5/patches/resources/installer/por.xml \
  ./installer/lib/izpack/5/patches/resources/installer/zho.xml \
  ./installer/resources/eepsite/jetty-ssl.xml \
  ./installer/resources/eepsite/contexts/cgi-context.xml \
  ./installer/resources/eepsite/contexts/base-context.xml \
  ./installer/resources/eepsite/jetty-rewrite.xml \
  ./installer/resources/eepsite/etc/webdefault.xml \
  ./installer/resources/eepsite/jetty-jmx.xml \
  ./installer/resources/eepsite/jetty.xml \
  ./router/java/build.xml

echo 'Checking HTML...'
check --html \
  ./apps/desktopgui/src/net/i2p/desktopgui/package.html \
  ./apps/ministreaming/java/src/net/i2p/client/streaming/package.html \
  ./apps/susimail/src/index.html \
  ./core/java/src/net/i2p/client/datagram/package.html \
  ./core/java/src/net/i2p/client/naming/package.html \
  ./core/java/src/net/i2p/client/package.html \
  ./core/java/src/net/i2p/crypto/package.html \
  ./core/java/src/net/i2p/data/i2cp/package.html \
  ./core/java/src/net/i2p/data/package.html \
  ./core/java/src/net/i2p/internal/package.html \
  ./core/java/src/net/i2p/package.html \
  ./core/java/src/net/i2p/stat/package.html \
  ./core/java/src/net/i2p/time/package.html \
  ./core/java/src/net/i2p/util/package.html \
  ./installer/lib/izpack/resources/welcome.html \
  ./installer/resources/eepsite/docroot/help/index*.html \
  ./installer/resources/eepsite/docroot/help/pagetemplate.html \
  ./installer/resources/eepsite/docroot/index.html \
  ./installer/resources/platform-specific/windows/startconsole.html \
  ./installer/resources/small/toolbar.html \
  ./router/java/src/net/i2p/data/i2np/package.html \
  ./router/java/src/net/i2p/router/package.html \
  ./router/java/src/net/i2p/router/peermanager/package.html \
  ./router/java/src/net/i2p/router/startup/package.html \
  ./router/java/src/net/i2p/router/transport/ntcp/package.html \
  ./router/java/src/net/i2p/router/transport/package.html \
  ./router/java/src/net/i2p/router/transport/udp/package.html \
  ./router/java/src/net/i2p/router/util/package.html

if [ "$failed" -ne 0 ]; then
  echo "******** $failed of $total checks failed ********"
else
  echo "All $total files passed"
fi
exit "$failed"
