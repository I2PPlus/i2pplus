#!/bin/sh
#
# Check the Bourne-compatible scripts in the I2P source for validity by running
# "sh -n" on each. Optionally checks for bashisms if "checkbashisms" is installed.
#
# Exits 0 if no errors, non-zero otherwise.
#
# zzz 2011-02
# public domain
#

# this script lives in tools/scripts/tests, so the repo root is three levels up
cd "$(dirname "$0")/../../.." || exit 1

# Only Bourne-compatible scripts belong here. Anything with a bash shebang, or
# with bash-only syntax, has to be left out or the check is meaningless.
SCRIPTFILES="
  ./apps/i2psnark/launch-i2psnark \
  ./core/c/*.sh \
  ./core/c/jbigi/*.sh \
  ./distro/debian/*.config \
  ./distro/debian/*.init \
  ./distro/debian/*.preinst \
  ./distro/debian/*.postinst \
  ./distro/debian/*.postrm \
  ./distro/Slackware/i2p/i2p.SlackBuild \
  ./distro/Slackware/i2p/doinst.sh \
  ./distro/Slackware/i2p/rc.i2p \
  ./installer/resources/platform-specific/unix/eepget \
  ./installer/resources/platform-specific/unix/eephead \
  ./installer/resources/platform-specific/unix/graceful_update \
  ./installer/resources/platform-specific/unix/hostping \
  ./installer/resources/platform-specific/unix/i2ping \
  ./installer/resources/platform-specific/unix/i2prouter \
  ./installer/resources/platform-specific/unix/osid \
  ./installer/resources/platform-specific/unix/postinstall.sh \
  ./installer/resources/platform-specific/unix/runplain.sh \
  ./installer/resources/platform-specific/macos/install_i2p_service_osx.command \
  ./installer/resources/platform-specific/macos/uninstall_i2p_service_osx.command \
"

TOTAL=0
FAILED=0

echo "> Checking scripts for bashisms ..."

for pattern in $SCRIPTFILES; do
  # Expand one pattern into the positional parameters so a glob matching several
  # scripts is handled by the inner loop. Word splitting is intended here.
  # shellcheck disable=SC2086
  set -- $pattern
  if [ "$#" -eq 1 ] && [ ! -e "$1" ]; then
    # A pattern matching nothing almost always means the file was moved or
    # deleted. Report it, otherwise the list quietly stops covering anything.
    echo "! WARN: $pattern matched no file."
    continue
  fi

  for script in "$@"; do
    TOTAL=$((TOTAL + 1))
    if ! sh -n "$script"; then
      echo "! WARN: $script failed the syntax check."
      FAILED=$((FAILED + 1))
      continue
    fi
    command -v checkbashisms >/dev/null 2>&1 || continue
    echo "> Checking for bashisms in $script"
    if bashisms=$(checkbashisms "$script" 2>&1) && [ -n "$bashisms" ]; then
      echo "! WARN: $script contains possible bashisms:"
      echo "$bashisms"
    fi
  done
done

if [ "$FAILED" -ne 0 ]; then
  echo "! ${FAILED} of ${TOTAL} scripts failed the syntax check."
  exit 1
fi
echo "> ${TOTAL} scripts checked, all valid."
exit 0
