#!/bin/sh
#
# Update messages_xx.po and messages_xx.class files,
# from java sources.
# Requires installed programs xgettext, msgfmt, msgmerge -q, and find.
#
# usage:
#    bundle-messages.sh (generates the resource bundle from the .po file)
#    bundle-messages.sh -p (updates the .po file from the source tags, then generates the resource bundle)
#
cd `dirname $0`
CLASS=net.i2p.addressbook.messages
TMPFILE=build/javafiles.txt
export TZ=UTC
RC=0

if ! $(which javac > /dev/null 2>&1); then
    export JAVAC=${JAVA_HOME}/../bin/javac
fi

if [ "$1" = "-p" ]
then
  POUPDATE=1
fi

# on windows, one must specify the path of command find
# since windows has its own version of find.
if which find|grep -q -i windows ; then
  export PATH=.:/bin:/usr/local/bin:$PATH
fi
# Fast mode - update ondemond
# set LG2 to the language you need in environment variables to enable this


# add ../java/ so the refs will work in the po file
# do not scan 3rd-party code in java/src/com or java/src/edu
JPATHS="src"
for i in ../locale/messages_*.po
do
  # get language
  LG=${i#../locale/messages_}
  LG=${LG%.po}

  # skip, if specified
  if [ $LG2 ]; then
    [ $LG != $LG2 ] && continue || echo INFO: Language update is set to [$LG2] only.
  fi

  if [ "$POUPDATE" = "1" ]
  then
    # make list of java files newer than the .po file
    find $JPATHS -name *.java -newer $i > $TMPFILE
  fi

  if [ -s build/obj/net/i2p/addressbook/messages_$LG.class -a \
       build/obj/net/i2p/addressbook/messages_$LG.class -nt $i -a \
       ! -s $TMPFILE ]
  then
    continue
  fi

  if [ "$POUPDATE" = "1" ]
  then
     echo "Updating the $i file from the tags..."
    # extract strings from java files, and update messages.po files
    # translate calls must be one of the forms:
    # _t("foo")
    # _x("foo")
    # intl._t("foo")
    # intl.title("foo")
    # handler._t("foo")
    # formhandler._t("foo")
    # net.i2p.addressbook.Messages.getString("foo")
    # To start a new translation, copy the header from an old translation to the new .po file,
    # then ant distclean updater.
    find $JPATHS -name *.java > $TMPFILE
    xgettext -f $TMPFILE -F -L java --from-code=UTF-8 --width=0 --no-wrap --add-comments \
                   --keyword=_t --keyword=_x --keyword=intl._ --keyword=intl.title \
                   --keyword=handler._ --keyword=formhandler._ \
                   --keyword=net.i2p.addressbook.Messages.getString \
              -o ${i}t
    if [ $? -ne 0 ]
    then
      echo "ERROR - xgettext failed on ${i}, not updating translations"
      rm -f ${i}t
      RC=1
      break
    fi
    msgmerge -q -U -N --backup=none $i ${i}t
    if [ $? -ne 0 ]
    then
      echo "ERROR - msgmerge -q failed on ${i}, not updating translations"
      rm -f ${i}t
      RC=1
      break
    fi
    # string with '75%' causes it to add a java-printf-format directive,
    # and then testscript fails if the translated
    # string doesn't have a '%' in it; strip out the directive
    grep -v java-printf-format $i > ${i}t
    mv ${i}t ${i}
  fi

    if [ "$LG" != "en" ]
    then
        # only generate for non-source language
        # echo "Generating ${CLASS}_$LG ResourceBundle..."

        msgfmt -V | grep -q -E ' 0\.((19)|[2-9])'
        if [ $? -ne 0 ]
        then
            # slow way
            # convert to class files in build/obj
            msgfmt --java2 -r $CLASS -l $LG -d build/obj $i
            if [ $? -ne 0 ]
            then
                echo "ERROR - msgfmt failed on ${i}, not updating translations"
                # msgfmt leaves the class file there so the build would work next time
                find build -name messages_${LG}.class -exec rm -f {} \;
                RC=1
                break
            fi
        else
            # fast way
            # convert to java files in build/messages-src
            TD=build/messages-src-tmp
            TDX=$TD/net/i2p/addressbook
            TD2=build/messages-src
            TDY=$TD2/net/i2p/addressbook
            rm -rf $TD
            mkdir -p $TD $TDY
            msgfmt --java2 --source -r $CLASS -l $LG -d $TD $i
            if [ $? -ne 0 ]
            then
                echo "ERROR - msgfmt failed on ${i}, not updating translations"
                # msgfmt leaves the class file there so the build would work next time
                find build -name messages_${LG}.class -exec rm -f {} \;
                RC=1
                break
            fi
            mv $TDX/messages_$LG.java $TDY
            rm -rf $TD
        fi
    fi
done
rm -f $TMPFILE
exit $RC
