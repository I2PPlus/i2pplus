#!/bin/sh

# I2P Installer - Installs and pre-configures I2P.
#
# postinstall
# 2004 The I2P Project
# https://geti2p.net
# This code is public domain.
#
# author: hypercubus
#
# Prunes the single all-platforms payload down to the machine being installed on.
#
# The installer ships one tar of every platform's files - the Windows service wrapper
# and DLLs, the .bat launchers, the macOS .app bundle and .command scripts, and a
# lib/wrapper tree holding the Tanuki native library for all four unix targets at
# once. This script runs once at the end of a unix install to:
#
#   1. put the matching libwrapper native library in place, since wrapper.jar dlopen()s
#      it out of ./lib and cannot work until it is there
#   2. copy the wrapper's i2psvc service-control script to the install root
#   3. delete everything that belongs to a different platform
#
# It does not install a systemd unit or any other service. i2psvc is only copied;
# a user who wants a service runs ./i2psvc install themselves, and the router starts
# with ./i2prouter or ./runplain.sh without one.
#
# Takes the install root as $1, which is what both install.xml and install5.xml pass.

if [ "$1" != "" ]; then
    cd "$1" || exit 1
fi

LOGFILE=./postinstall.log
ERROR_MSG="Cannot determine operating system type. From the subdirectory in lib/wrapper matching your operating system, please move i2psvc to your base I2P directory, and move the remaining two files to the lib directory."

# Say something to the user.
#
# IzPack runs this script with stdout and stderr attached to pipes it drains and throws
# away (FileExecutor.executeCommand reads Process.getInputStream/getErrorStream into
# nowhere), so a plain echo here is never seen. /dev/tty is the controlling terminal
# and is not redirected, so writing to it puts the message in front of the user in both
# console and GUI installs. Falling back to stdout keeps the script usable when run by
# hand with no terminal at all, and when run under a pty-less harness.
say() {
    if [ -w /dev/tty ] ; then
        printf '%s\n' "$*" > /dev/tty
    else
        printf '%s\n' "$*"
    fi
}

fail() {
    say "$ERROR_MSG"
    {
        echo "Host OS is $HOST_OS"
        echo "Host architecture is $OS_ARCH"
        echo "$ERROR_MSG"
    } >> $LOGFILE
    exit 1
}

# Run osid through the shell rather than executing it, so this script does not depend
# on its own chmod having run first. preppkg-unix marks it +x anyway.
HOST_OS=$(sh ./osid)
OS_ARCH=$(uname -m)

# "[ p -o q ]" is not defined by POSIX, so it is two tests rather than one. Quoting
# every expansion is what makes dropping the X-prefix safe.
if [ "$HOST_OS" = "" ] || [ "$HOST_OS" = "unknown" ]; then
    fail
fi

# Report a systemd unit that already exists, and deliberately do nothing about it.
#
# This script installs, replaces and removes no systemd unit. i2psvc - which Tanuki
# makes install one on its first `start` - is only copied into the install root above,
# so a unit can already be present because somebody set the router up as a service by
# hand, possibly against a different I2P installation entirely. Detecting it here is
# purely so the user is not left wondering whether the installer clobbered it.
#
# Nothing below this function writes to, moves or removes any of these paths.
report_existing_unit() {
    unit_home="${XDG_CONFIG_HOME:-$HOME/.config}"
    for unit in /etc/systemd/system/i2prouter.service \
                /usr/lib/systemd/system/i2prouter.service \
                /lib/systemd/system/i2prouter.service \
                "$unit_home/systemd/user/i2prouter.service" ; do
        [ -e "$unit" ] || continue
        say "An I2P+ systemd unit is already installed: $unit"
        say "Leaving it untouched. This installer does not create, replace or remove"
        say "systemd units, and will not touch any other I2P installation on this system."
    done
}

report_existing_unit

case $HOST_OS in
    debian | fedora | gentoo | linux | mandrake | redhat | suse )
        case $OS_ARCH in
            armv8* | aarch64 ) wrapperpath="./lib/wrapper/linux64-armv8" ;;
            *                ) wrapperpath="./lib/wrapper/linux64" ;;
        esac
        ;;
    freebsd )
        wrapperpath="./lib/wrapper/freebsd64"
        ;;
    osx )
        wrapperpath="./lib/wrapper/macosx"
        ;;
    solaris )
        wrapperpath="./lib/wrapper/solaris"
        ;;
    kfreebsd | netbsd | openbsd )
        # No prebuilt wrapper for these. Say so rather than falling through to the
        # default branch, which would try to report an OS problem that isn't one.
        say "The java wrapper is not supported on this platform."
        # Was written as "Please use `pwd`/runplain.sh", which sh expanded to this
        # directory rather than printing the word - harmless, but it printed a
        # different thing to the user than the message said.
        say "Please use $(pwd)/runplain.sh to start I2P."
        # The cleanup below still has to happen.
        wrapperpath=""
        ;;
    * )
        fail
        ;;
esac

if [ -n "$wrapperpath" ]; then
    case $HOST_OS in
        osx ) cp ${wrapperpath}/libwrapper*.jnilib ./lib/ ;;
        *    ) cp ${wrapperpath}/libwrapper.so ./lib/ ;;
    esac
    cp ${wrapperpath}/i2psvc* .
fi

# chmod only what is actually present.
#
# chmod prints to stderr for every missing operand, and this script used to name two
# that I2P+ removed upstream years ago (graceful_helper, ssleepget), so every install
# ended in two "cannot access" lines the user could do nothing about. Guarding here
# means a script dropped from the payload later costs nothing instead of printing.
make_exec() {
    for f in "$@" ; do
        [ -f "$f" ] && chmod 755 "$f"
    done
}

# Launchers and tools. hostping and graceful_update were missing from this list even
# though preppkg-unix stages them; so were i2prouter, osid and runplain.sh. The
# uninstaller launcher belongs here too: it is not an IzPack <parsable> target, and
# that appears to be what carries the executable bit through the pack.
make_exec ./i2prouter ./runplain.sh ./eepget ./eephead ./i2ping \
          ./hostping ./graceful_update ./uninstall-i2p.sh ./osid

case $HOST_OS in
    osx )
        make_exec "./Start I2P Router.app/Contents/MacOS/i2prouter"
        make_exec ./install_i2p_service_osx.command
        make_exec ./uninstall_i2p_service_osx.command
        ;;
esac

# Prune. lib/wrapper goes on every platform once the right native library has been
# copied out of it. The Windows artefacts are removed on macOS too - it is a unix
# install as far as this script is concerned. service.ps1 was previously missed, which
# left a PowerShell service script in the install of every unix user; *.ps1 and *.psm1
# are removed alongside it so a new one cannot repeat the omission.
#
# eepsite/docroot/favicon.ico is deliberately NOT removed: the console help pages
# reference it, and a favicon is not a Windows artefact.
rm -rf ./lib/wrapper
rm -f ./*.bat ./*.cmd ./*.exe ./*.ps1 ./*.psm1 ./lib/*.dll ./utility.jar

if [ "$HOST_OS" != "osx" ]; then
    rm -rf "./Start I2P Router.app"
    rm -f ./install_i2p_service_osx.command ./uninstall_i2p_service_osx.command
    rm -f ./net.i2p.router.plist.template
fi

rm -f ./osid ./postinstall.sh
exit 0
