#!/bin/sh

# Check scripts in the I2P source for validity by running with "sh -n
# $SCRIPTNAME". Optionally checks for bashisms if "checkbashisms" is installed.

# Exits 0 if no errors, non-zero otherwise

cd "$(dirname "$0")/../.."

# Only Bourne-compatible scripts should be in this list.
SCRIPTFILES="
  ./apps/desktopgui/bundle-messages.sh \
  ./apps/i2psnark/java/bundle-messages.sh \
  ./apps/i2psnark/launch-i2psnark \
  ./apps/i2ptunnel/java/bundle-messages*.sh \
  ./apps/ministreaming/java/bundle-messages.sh \
  ./apps/routerconsole/java/bundle-messages*.sh \
  ./apps/susidns/src/bundle-messages.sh \
  ./apps/susimail/bundle-messages.sh \
  ./core/c/*.sh \
  ./core/c/jbigi/*.sh \
  ./debian/*.config \
  ./debian/*.init \
  ./debian/*.preinst \
  ./debian/*.postinst \
  ./debian/*.postrm \
  ./Docker.entrypoint.sh \
  ./installer/resources/*.sh \
  ./installer/resources/eepget \
  ./installer/resources/i2prouter \
  ./installer/resources/install_i2p_service_osx.command \
  ./installer/resources/install_i2p_service_unix \
  ./installer/resources/locale/bundle-messages.sh \
  ./installer/resources/uninstall_i2p_service_osx.command \
  ./installer/resources/uninstall_i2p_service_unix \
  ./Slackware/i2p/i2p.SlackBuild \
  ./Slackware/i2p/doinst.sh \
  ./Slackware/i2p/rc.i2p \
  ./tests/scripts/*.sh \
"

TOTAL=0

echo "> Checking scripts for bashisms ..."

for script in $SCRIPTFILES; do
  TOTAL=$((TOTAL + 1))

  if sh -n "$script"; then
    if command -v checkbashisms > /dev/null 2>&1; then
      echo "> Checking for bashisms in $script"
      checkbashisms_output=$(checkbashisms "$script")
      if [ -n "$checkbashisms_output" ]; then
        echo "! WARN: $script contains possible bashisms:"
        echo "$checkbashisms_output"
      fi
    fi
  else
    echo "! WARN: $script failed the syntax check."
  fi
done

echo "> ${TOTAL} scripts checked, review the output above for possible issues."