#!/bin/sh
#
# Run 'msgfmt -c' on all .po files
# Returns nonzero on failure
#
# zzz 2011-02
# public domain
#

cd "$(dirname "$0")/../.."

DIRS="
  core/locale \
  router/locale \
  apps/routerconsole/locale \
  apps/routerconsole/locale-news \
  apps/routerconsole/locale-countries \
  apps/i2ptunnel/locale \
  apps/i2ptunnel/locale-proxy \
  apps/i2psnark/locale \
  apps/ministreaming/locale \
  apps/susidns/locale \
  apps/susimail/locale \
  apps/desktopgui/locale \
  installer/resources/locale/po \
  installer/resources/locale-man \
  debian/po"

FILES="installer/resources/locale-man/man.pot"

PASS=0
FAIL=0
TOTAL=0

for dir in $DIRS; do
  echo "> Checking directory: $dir"
  for i in $(find "$dir" -maxdepth 1 -type f -name '*.po'); do
    TOTAL=$((TOTAL + 1))
    msgfmt --check-format "$i" -o /dev/null
    if [ $? -eq 0 ]; then
      PASS=$((PASS + 1))
    else
      FAIL=$((FAIL + 1))
      echo "! WARN: $i failed the .po format check."
    fi
  done
  echo # Add a newline separator after checking each directory
done

# Check additional files not located in directories
for i in $FILES; do
  TOTAL=$((TOTAL + 1))
  msgfmt --check-format "$i" -o /dev/null
  if [ $? -eq 0 ]; then
    PASS=$((PASS + 1))
  else
    FAIL=$((FAIL + 1))
    echo "! WARN: $i failed the .po format check."
  fi
done

echo "> ${PASS}/${TOTAL} files passed the .po format check."

if [ "$FAIL" -gt 0 ]; then
  echo "! ${FAIL} files failed the .po format check."
  exit 1
else
  echo "> All files passed the .po format check."
  exit 0
fi