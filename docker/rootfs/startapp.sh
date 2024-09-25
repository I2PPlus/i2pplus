#!/bin/sh
set -e

if [ -z $JVM_XMX ]; then
    echo " *** Using the default 512MB JVM heap limit..."
    echo " *** To configure a different maximum value, change the JVM_XMX"
    echo " *** variable value in startapp.sh (for example JVM_XMX=256m)"
    JVM_XMX=512m
fi

# Manually configure an ip address to use here by uncommenting the IP_ADDR
# line below and editing as required
#IP_ADDR=192.168.1.10

# Explicitly define HOME otherwise it might not have been set
export HOME=/i2p

export I2P=${HOME}

echo " [startapp] Starting I2P+..."

cd $HOME
export CLASSPATH=.

for jar in `ls lib/*.jar`; do
    CLASSPATH=${CLASSPATH}:${jar}
done

if [ -f /.dockerenv ] || [ -f /run/.containerenv ]; then
    echo " [startapp] Running I2P+ in container"
    if [ -z "$IP_ADDR" ]; then
        export IP_ADDR=$(hostname -i)
        echo " [startapp] Running I2P+ in docker network"
        echo ""
        echo "  Note: To access I2P+ from other computers on your lan, set IP_ADDR to this host's lan ip,"
        echo "  or 0.0.0.0 for access from anywhere - make sure your firewall permissions prevent"
        echo "  access from the public internet if using 0.0.0.0"
    fi
    echo " [startapp] Setting reachable IP to container IP $IP_ADDR"
    find . -name '*.config' -exec sed -i "s/127.0.0.1/$IP_ADDR/g" {} \;
    find . -name '*.config' -exec sed -i "s/localhost/$IP_ADDR/g" {} \;
fi

# Required Java options
JAVAOPTS="-Djava.net.preferIPv4Stack=false -Djava.library.path=${I2P}:${I2P}/lib -Di2p.dir.base=${I2P} -Di2p.dir.config=${HOME}/.i2p -DloggerFilenameOverride=logs/log-router-@.txt -Xmx$JVM_XMX"

java -cp "${CLASSPATH}" ${JAVA_OPTS} net.i2p.router.RouterLaunch
