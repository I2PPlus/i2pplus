#!/bin/sh
#
# Runs a test using each libjbigi-linux-*.so file
# Returns nonzero on failure, but it will always
# pass because NBI doesnt return an error code (yet).
# But when it does, it will fail all the time since
# your hardware probably can't run all versions.
#
# zzz 2011-05
# public domain
#

cd $(dirname $0)/../../installer/lib/jbigi

TMP=/tmp/testjbigi$$
mkdir $TMP

echo "> Testing 32 bit libcpuid ..."
ln -s $PWD/libjcpuid-x86-linux.so $TMP/libjcpuid.so
java -cp ../../../build/i2p.jar -Djava.library.path=$TMP freenet.support.CPUInformation.CPUID
rm -f $TMP/libjcpuid.so
echo

echo "> Testing 64 bit libcpuid ..."
ln -s $PWD/libjcpuid-x86_64-linux.so $TMP/libjcpuid.so
java -cp ../../../build/i2p.jar -Djava.library.path=$TMP freenet.support.CPUInformation.CPUID
rm -f $TMP/libjcpuid.so
echo

for i in libjbigi-linux-*.so; do
  echo "> Testing $i ..."
  ln -s $PWD/$i $TMP/libjbigi.so
  java -cp ../../../build/i2p.jar -Djava.library.path=$TMP net.i2p.util.NativeBigInteger |
    egrep 'java|native|However'
  if [ $? -ne 0 ]; then
    echo "! FAILED CHECK FOR $i"
    FAIL=1
  fi
  rm -f $TMP/libjbigi.so
  echo
done

if [ "$FAIL" != "" ]; then
  echo "! At least one file failed jbigi check."
else
  echo "> All files passed jbigi check."
fi
rm -rf $TMP
exit $FAIL
