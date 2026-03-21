#
# copy the files out of the unzipped delta pack
#
V=3.6.4
D=wrapper-delta-pack-$V
B=$D/bin
L=$D/lib

cp $L/wrapper.jar all

# libraries
cp $L/libwrapper-freebsd-x86-32.so freebsd/libwrapper.so
cp $L/libwrapper-freebsd-x86-64.so freebsd64/libwrapper.so
cp $L/libwrapper-freebsd-arm-64.so freebsd-arm64/libwrapper.so
cp $L/libwrapper-linux-x86-32.so linux/libwrapper.so
cp $L/libwrapper-linux-x86-64.so linux64/libwrapper.so
cp $L/libwrapper-linux-arm-64.so linux64-armv8/libwrapper.so
cp $L/libwrapper-linux-armel-32.so linux-armv5/libwrapper.so
cp $L/libwrapper-linux-armhf-32.so linux-armv7/libwrapper.so
cp $L/libwrapper-macosx-universal-64.jnilib macosx/libwrapper-macosx-universal-64.jnilib
cp $L/libwrapper-macosx-arm-64.dylib macosx-arm64/libwrapper-macosx-arm-64.dylib

# executables
cp $B/wrapper-freebsd-x86-32 freebsd/i2psvc
cp $B/wrapper-freebsd-x86-64 freebsd64/i2psvc
cp $B/wrapper-freebsd-arm-64 freebsd-arm64/i2psvc
cp $B/wrapper-linux-x86-32 linux/i2psvc
cp $B/wrapper-linux-x86-64 linux64/i2psvc
cp $B/wrapper-linux-arm-64 linux64-armv8/i2psvc
cp $B/wrapper-linux-armel-32 linux-armv5/i2psvc
cp $B/wrapper-linux-armhf-32 linux-armv7/i2psvc
cp $B/wrapper-macosx-universal-64 macosx/i2psvc-macosx-universal-64

for i in freebsd freebsd64 linux linux64
do
	strip $i/i2psvc $i/libwrapper.so
	chmod -x $i/i2psvc $i/libwrapper.so
done

for i in linux-armv5 linux-armv7 linux64-armv8
do
	chmod -x $i/i2psvc $i/libwrapper.so
done

echo 'Windows binaries not copied, see README.txt'
echo 'Now compile the armv6 binaries on a Raspberry Pi, see linux-armv6/README.txt'
