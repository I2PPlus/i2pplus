#!/bin/sh

# I2P+ Installer - removes an I2P+ installation.
#
# uninstall-i2p.sh
# This code is public domain.
#
# A thin launcher for the uninstaller the installer writes into Uninstaller/.
# That jar does not ship in the payload - the IzPack compiler generates it at install
# time, together with the .installationinformation record of what it unpacked - so
# there is nothing to call before an install has happened.
#
# Takes the install root as $1 and passes every other argument through to the
# uninstaller, so the usual IzPack switches work:
#
#   ./uninstall-i2p.sh            GUI, if there is an X display
#   ./uninstall-i2p.sh -c         text mode
#   ./uninstall-i2p.sh -c -q      text mode, no confirmation prompt
#
# What it does NOT remove is a systemd unit. The installer never creates one: this
# postinstall step only copies the wrapper's i2psvc control script into the install
# root, and a unit appears only if a user runs ./i2psvc install themselves. The
# uninstaller deletes what it recorded in .installationinformation, and a unit
# created after the fact is not in that record - so it is left alone, which is also
# the safer outcome, since a unit may point at a different I2P installation that this
# one knows nothing about. Remove it yourself only if it is this installation's:
#
#   grep ExecStart /etc/systemd/system/i2prouter.service   # check which install it uses
#   sudo rm /etc/systemd/system/i2prouter.service && sudo systemctl daemon-reload
#   rm ~/.config/systemd/user/i2prouter.service          # if installed per-user

if [ "$1" != "" ]; then
    cd "$1" || exit 1
    shift
fi

UNINSTALLER=Uninstaller/uninstaller.jar

if [ ! -f "$UNINSTALLER" ]; then
    echo "No I2P+ uninstaller found at $(pwd)/$UNINSTALLER."
    echo "If this directory was already uninstalled, there is nothing left to do."
    exit 1
fi

# Prefer the JRE the router itself uses, then JAVA_HOME, then whatever is on PATH.
# The uninstaller needs java 1.8 or later, same as the router.
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
elif [ -x ./jre/bin/java ]; then
    JAVA=./jre/bin/java
else
    JAVA=java
fi

if ! command -v "$JAVA" >/dev/null 2>&1 && [ ! -x "$JAVA" ]; then
    echo "No Java runtime found. Set JAVA_HOME or put java on PATH, then try again."
    exit 1
fi

# With no arguments and no display, the GUI uninstaller cannot open a window and the
# run dies with an AWTError rather than anything readable. Text mode is the useful
# default there; an explicit switch always wins.
if [ "$#" -eq 0 ] && [ -z "$DISPLAY" ]; then
    set -- -c
fi

# A system-wide install was done as root and its files are root-owned, so the
# uninstaller cannot remove them as an ordinary user. Say so up front: the failure
# otherwise surfaces partway through, after some files are already gone.
if [ "$(id -u)" != "0" ] && [ ! -w "$(pwd)" ]; then
    echo "This install is owned by another user, so its files cannot be removed."
    echo "Re-run as the owning user, or as root, to uninstall it completely."
    exit 1
fi

exec "$JAVA" -jar "$UNINSTALLER" "$@"
