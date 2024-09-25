#!/bin/sh
set -e

# Default JVM heap size
if [ -z $JVM_XMX ]; then
    echo "[startapp] Using the default 512MB JVM heap limit for I2P+..."
    echo "[startapp] To configure a different maximum value, change the JVM_XMX"
    echo "[startapp] variable value in startapp.sh (for example JVM_XMX=256m)"
    echo ""
    JVM_XMX=512m
fi

# Manually configure an ip address to use by uncommenting the
# IP_ADDR line below and editing as required
#IP_ADDR=192.168.1.10

# Set environment variables
export HOME=/i2p
export I2P=${HOME}

# Build classpath dynamically
cd $HOME
export CLASSPATH=.
if [ -d "lib" ]; then
    CLASSPATH="."
    for jar in $(find lib -name "*.jar"); do
        CLASSPATH="${CLASSPATH}:${jar}"
    done
else
    echo "[startapp] Error: lib directory not found. Cannot build classpath!"
    exit 1
fi

# Set classpath
export CLASSPATH

# Configure IP address based on container environment
if [ -f /.dockerenv ] || [ -f /run/.containerenv ]; then
    echo "[startapp] Running I2P+ in container"
    if [ -z "$IP_ADDR" ]; then
        export IP_ADDR=$(hostname -i)
        echo "[startapp] Running I2P+ in docker network"
        echo ""
        echo "[startapp] Note: To access I2P+ from other computers on your lan, set IP_ADDR to this host's lan ip,"
        echo "[startapp] or 0.0.0.0 for access from anywhere - make sure your firewall permissions prevent"
        echo "[startapp] access from the public internet if using 0.0.0.0"
        echo ""
    fi
    echo "[startapp] Setting IP address for I2P+ console and service access to $IP_ADDR"
    echo "[startapp] When I2P+ is running, you can reach the web console at https://$IP_ADDR:7667/"
    echo "[startapp] Note that your browser may warn you about the site certificate which is self-signed."
    echo ""

    # Update configuration files with the new IP
    for config_file in $(find . -name '*.config'); do
        if [ -f "$config_file" ]; then
            sed -i "s/127.0.0.1/$IP_ADDR/g" "$config_file"
            sed -i "s/localhost/$IP_ADDR/g" "$config_file"
        fi
    done
fi

# Set required Java options
JAVA_OPTS="-Djava.net.preferIPv4Stack=false -Djava.library.path=${I2P}:${I2P}/lib -Di2p.dir.base=${I2P} -Di2p.dir.config=${HOME}/.i2p -DloggerFilenameOverride=logs/log-router-@.txt -Xmx$JVM_XMX"

# Launch I2P+
echo "[startapp] Launching I2P+ ... please standy by ..."
echo ""
java -cp "${CLASSPATH}" ${JAVA_OPTS} net.i2p.router.RouterLaunch
