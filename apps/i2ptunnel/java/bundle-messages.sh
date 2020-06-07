#!/bin/sh

# Update messages_xx.po and messages_xx.class files,
# from both java and jsp sources.
# Requires installed programs xgettext, msgfmt, msgmerge, and find.
#
# usage:
#    bundle-messages.sh (generates the resource bundle from the .po file)
#    bundle-messages.sh -p (updates the .po file from the source tags, then generates the resource bundle)
#
# zzz - public domain
#
CLASS=net.i2p.i2ptunnel.web.messages
TMPFILE=build/javafiles.txt
export TZ=UTC
RC=0
if ! $(which javac > /dev/null 2>&1); then
    export JAVAC="${JAVA_HOME}/../bin/javac"
fi

if [ "$1" = "-p" ]
then
	POUPDATE=1
fi

# on windows, one must specify the path of commnad find
# since windows has its own version of find.
if which find|grep -q -i windows ; then
	export PATH=.:/bin:/usr/local/bin:$PATH
fi
# Fast mode - update ondemond
# set LG2 to the language you need in environment variables to enable this

# add ../java/ so the refs will work in the po file
JPATHS="../java/src/net/i2p/i2ptunnel/web ../jsp/WEB-INF"
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
	if [ -s ../jsp/WEB-INF/classes/net/i2p/i2ptunnel/web/messages_$LG.class -a \
	     ../jsp/WEB-INF/classes/net/i2p/i2ptunnel/web/messages_$LG.class -nt $i -a \
	     ! -s $TMPFILE ]
	then
		continue
	fi

	if [ "$POUPDATE" = "1" ]
	then
	 	echo "Updating the $i file from the tags..."
		# extract strings from java and jsp files, and update messages.po files
		# translate calls must be one of the forms:
		# _t("foo")
		# _x("foo")
		# intl._t("foo")
		# intl.title("foo")
		# In a jsp, you must use a helper or handler that has the context set.
		# To start a new translation, copy the header from an old translation to the new .po file,
		# then ant distclean updater.
		find $JPATHS -name *.java > $TMPFILE
		xgettext -f $TMPFILE -F -L java --from-code=UTF-8 --add-comments\
	                 --keyword=_t --keyword=_x --keyword=intl._ --keyword=intl.title --keyword=unlessAdvanced \
		         -o ${i}t
		if [ $? -ne 0 ]
		then
			echo "ERROR - xgettext failed on ${i}, not updating translations"
			rm -f ${i}t
			RC=1
			break
		fi
		msgmerge -U --backup=none $i ${i}t
		if [ $? -ne 0 ]
		then
			echo "ERROR - msgmerge failed on ${i}, not updating translations"
			rm -f ${i}t
			RC=1
			break
		fi
		rm -f ${i}t
		# so we don't do this again
		touch $i
	fi

    if [ "$LG" != "en" ]
    then
        # only generate for non-source language
        echo "Generating ${CLASS}_$LG ResourceBundle..."

        msgfmt -V | grep -q -E ' 0\.((19)|[2-9])'
        if [ $? -ne 0 ]
        then
            # slow way
            # convert to class files in jsp/WEB-INF/classes
            msgfmt --java2 --statistics -r $CLASS -l $LG -d ../jsp/WEB-INF/classes $i
            if [ $? -ne 0 ]
            then
                echo "ERROR - msgfmt failed on ${i}, not updating translations"
                # msgfmt leaves the class file there so the build would work the next time
                find build -name messages_${LG}.class -exec rm -f {} \;
                RC=1
                break
            fi
        else
            # fast way
            # convert to java files in build/messages-src
            TD=build/messages-src-tmp
            TDX=$TD/net/i2p/i2ptunnel/web
            TD2=build/messages-src
            TDY=$TD2/net/i2p/i2ptunnel/web
            rm -rf $TD
            mkdir -p $TD $TDY
            msgfmt --java2 --statistics --source -r $CLASS -l $LG -d $TD $i
            if [ $? -ne 0 ]
            then
                echo "ERROR - msgfmt failed on ${i}, not updating translations"
                # msgfmt leaves the class file there so the build would work the next time
                find src/WEB-INF/classes -name messages_${LG}.class -exec rm -f {} \;
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
