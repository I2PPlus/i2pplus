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
# Installs the appropriate set of Java Service Wrapper support files for the
# user's OS

if [ ! "X$1" = "X" ]; then
    cd $1
fi

chmod 755 ./i2prouter
chmod 755 ./osid
chmod 755 ./runplain.sh

ERROR_MSG="Cannot determine operating system type. From the subdirectory in lib/wrapper matching your operating system, please move i2psvc to your base I2P directory, and move the remaining two files to the lib directory."
LOGFILE=./postinstall.log

HOST_OS=`./osid`

if [ "X$HOST_OS" = "X" -o "X$HOST_OS" = "Xunknown" ]; then
    echo "$ERROR_MSG"
    echo "Host OS is $HOST_OS" >> $LOGFILE
    echo "Host architecture is $OS_ARCH" >> $LOGFILE
    echo "$ERROR_MSG" >> $LOGFILE
    exit 1
fi

OS_ARCH=`uname -m`
X86_64=`echo "${OS_ARCH}" | grep x86_64`

case $HOST_OS in
    debian | fedora | gentoo | linux | mandrake | redhat | suse )
        # Tanuki-built arm wrapper works on armv5 and armv7 but not on Raspberry Pi armv6.
        # Wrapper we built for Raspberry Pi does not work on Trimslice armv7.
        if [ `echo $OS_ARCH |grep armv8` ]; then
            wrapperpath="./lib/wrapper/linux64-armv8"
        elif [ `echo $OS_ARCH |grep aarch64` ]; then
            wrapperpath="./lib/wrapper/linux64-armv8"
        elif [ `echo $OS_ARCH |grep armv7` ]; then
            wrapperpath="./lib/wrapper/linux-armv7"
        elif [ `echo $OS_ARCH |grep armv6` ]; then
            wrapperpath="./lib/wrapper/linux-armv6"
        elif [ `echo $OS_ARCH |grep arm` ]; then
            wrapperpath="./lib/wrapper/linux-armv5"
        elif [ `echo $OS_ARCH |grep ppc` ]; then
            wrapperpath="./lib/wrapper/linux-ppc"
        elif [ "X$X86_64" = "X" ]; then
            wrapperpath="./lib/wrapper/linux"
        else
            wrapperpath="./lib/wrapper/linux64"
            # the 32bit libwrapper.so will be needed if a 32 bit jvm is used
            cp ./lib/wrapper/linux/libwrapper.so ./lib/libwrapper-linux-x86-32.so
        fi
        cp ${wrapperpath}/libwrapper.so ./lib/
        ;;
    freebsd )
        if [ ! `echo $OS_ARCH | grep amd64` ]; then
            wrapperpath="./lib/wrapper/freebsd"
        else
            wrapperpath="./lib/wrapper/freebsd64"
            # the 32bit libwrapper.so will be needed if a 32 bit jvm is used
            cp ./lib/wrapper/freebsd/libwrapper.so ./lib/libwrapper-freebsd-x86-32.so
        fi
        cp ${wrapperpath}/libwrapper.so ./lib/
        ;;
    osx )
        wrapperpath="./lib/wrapper/macosx"
        cp ${wrapperpath}/libwrapper*.jnilib ./lib/
        chmod 755 ./Start\ I2P\ Router.app/Contents/MacOS/i2prouter
        chmod 755 ./install_i2p_service_osx.command
        chmod 755 ./uninstall_i2p_service_osx.command
        ;;
    solaris )
        wrapperpath="./lib/wrapper/solaris"
        cp ${wrapperpath}/libwrapper.so ./lib/
        ;;
    netbsd|openbsd|kfreebsd)
        # FIXME
        # This isn't displayed when installing, but if we fall back to the "*)"
        # choice, no cleanup happens and users are advised to copy the wrapper
        # in place...but there is no wrapper. Figuring out how to display this,
        # such as when doing a headless installation would be good.
        echo "The java wrapper is not supported on this platform."
        echo "Please use `pwd`/runplain.sh to start I2P."
        # But at least the cleanup below will happen.
        ;;
    * )
        echo "${ERROR_MSG}"
        echo "Host OS is $HOST_OS" >> $LOGFILE
        echo "Host architecture is $OS_ARCH" >> $LOGFILE
        echo "$ERROR_MSG" >> $LOGFILE
        exit 1
        ;;
esac

if [ ! "X$wrapperpath" = "x" ]; then
    cp $wrapperpath/i2psvc* .
    chmod 755 ./i2psvc*
fi

chmod 755 ./eepget
chmod 755 ./eephead
chmod 755 ./i2ping
chmod 755 ./graceful_helper
chmod 755 ./ssleepget

rm -rf ./icons ./lib/wrapper
rm -f ./lib/*.dll ./*.bat ./*.cmd ./*.exe ./utility.jar
rm -f ./scripts/fixperms2.bat

if [ ! `echo $HOST_OS  |grep osx` ]; then
    rm -rf ./Start\ I2P\ Router.app
    rm -f *i2p_service_osx.command
    rm -f net.i2p.router.plist.template
    #rm -f I2P\ Router\ Console.webloc
fi

rm -f ./osid
rm -f ./postinstall.sh
exit 0
