@echo off

REM This launches I2PSnark and Jetty in a separate JVM.
REM The file jetty-i2psnark.xml must be present in the current directory.
REM I2PSnark will be accessible at: http://127.0.0.1:8002/

set I2P="."
java -jar "%I2P%/i2psnark.jar"
REM Uncomment the following if I2PSnark doesn't automatically open in a browser on launch
REM start http://127.0.0.1:8002