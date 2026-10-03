#!/bin/sh
#
# Check for UTF-8 problems in the files most likely to hold translated text,
# and in every Java/Scala source file. Returns nonzero on failure.
#
# zzz 2010-12
# public domain
#

# this script lives in tools/scripts/tests, so the repo root is three levels up
cd "$(dirname "$0")/../../.." || exit 1

if ! command -v iconv >/dev/null 2>&1; then
  echo "iconv not found: install libc-bin (Debian/Ubuntu) or glibc-common (Fedora)." >&2
  exit 1
fi

FAIL=0
TOTAL=0

# check <from> <to> <file>...
#   iconv fails on anything it cannot round trip in the given encoding, which is
#   how a mis-encoded file is spotted without caring what the text says.
check() {
  from="$1"
  to="$2"
  shift 2
  for file in "$@"; do
    TOTAL=$((TOTAL + 1))
    if ! iconv -f "$from" -t "$to" "$file" >/dev/null 2>&1; then
      echo "********* FAILED CHECK FOR $file *************"
      FAIL=$((FAIL + 1))
    fi
  done
}

# Translations and packaged end-user text. apps/routerconsole/jsp should only
# carry UTF-8 in help_xx.jsp.
DIRS="
  apps/routerconsole/locale
  apps/routerconsole/locale-news
  apps/routerconsole/locale-countries
  apps/routerconsole/jsp
  apps/i2ptunnel/locale
  apps/i2ptunnel/locale-proxy
  apps/i2ptunnel/jsp
  apps/i2psnark/locale
  apps/ministreaming/locale
  apps/susidns/locale
  apps/susidns/src/jsp
  apps/susimail/locale
  apps/desktopgui/locale
  distro/debian/po
  installer/resources/console/proxy
  installer/resources/console/readme
  installer/resources/eepsite/docroot/help
  installer/resources/initialNews
"

# A listed directory that has moved is reported rather than skipped, so the list
# cannot quietly stop covering a tree.
for dir in $DIRS; do
  if [ ! -d "$dir" ]; then
    echo "MISSING  $dir"
    FAIL=$((FAIL + 1))
    continue
  fi
  # No path in this repository contains whitespace, so plain word splitting is
  # safe here and keeps the loop in this shell rather than a subshell.
  # shellcheck disable=SC2086
  check UTF-8 UTF-8 $(find "$dir" -maxdepth 1 -type f)
done

echo "> Checking all Java and Scala files ..."
# shellcheck disable=SC2046
for file in $(find . \( -name '*.java' -o -name '*.scala' \) -type f); do
  check UTF-8 UTF-8 "$file"
done

# Java properties files (where they are not read through DataHelper) must be
# ISO-8859-1. https://docs.oracle.com/javase/6/docs/api/java/util/Properties.html
echo "> Checking getopt properties files ..."
# shellcheck disable=SC2046
for file in $(find core/java/src/gnu/getopt -name '*.properties' -type f); do
  check ISO-8859-1 ISO-8859-1 "$file"
done

if [ "$FAIL" -ne 0 ]; then
  echo "******** ${FAIL} of ${TOTAL} checks failed ********"
else
  echo "> All ${TOTAL} files passed"
fi
exit "$FAIL"
