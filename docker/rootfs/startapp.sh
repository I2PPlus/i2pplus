#!/bin/sh
set -e
set -u

# Default JVM heap size
if [ -z "${JVM_XMX:-}" ]; then
    echo "[startapp] Using the default 512MB JVM heap limit for I2P+..."
    echo "[startapp] To configure a different maximum value, change the JVM_XMX"
    echo "[startapp] variable value in startapp.sh (for example JVM_XMX=1024m)"
    echo ""
    JVM_XMX=512m
fi

# Manually configure an ip address to use for I2P+ service access
# by uncommenting the IP_ADDR line below and editing as required
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
    echo "[startapp] Error: i2p/lib directory not found. Cannot build classpath!"
    exit 1
fi

# Set classpath
export CLASSPATH

# Configure IP address based on container environment
if [ -f /.dockerenv ] || [ -f /run/.containerenv ]; then
    echo "[startapp] Running I2P+ in docker container"
    if [ -z "${IP_ADDR:-}" ]; then
        export IP_ADDR=$(hostname -I | awk '{print $1}')
        echo ""
        echo "[startapp] Note: To access I2P+ from other computers on your lan, set IP_ADDR to this host's lan ip,"
        echo "[startapp] or 0.0.0.0 for access from anywhere - make sure your firewall permissions prevent access"
        echo "[startapp] from the public internet if using 0.0.0.0"
        echo ""
    fi
    echo "[startapp] Setting IP address for I2P+ console and service access to $IP_ADDR"
    echo "[startapp] When I2P+ is running, you can reach the web console at https://$IP_ADDR:7667/"
    echo "[startapp] Note that your browser may warn you about the site certificate which is self-signed."
    echo ""

    # Update configuration files with the new IP (only for console, not listeners)
    # Keep listeners on 127.0.0.1 for security - only update console hostname
    if [ -f "i2pconsole.config" ]; then
        sed -i "s/localhost/$IP_ADDR/g" "i2pconsole.config"
    fi

    # Override external port if EXTERNAL_PORT env var is set (runtime override)
    if [ -n "${EXTERNAL_PORT:-}" ] && [ "${EXTERNAL_PORT:-}" != "0" ]; then
        echo "[startapp] Setting external port to $EXTERNAL_PORT (from EXTERNAL_PORT env)"
        sed -i "s/i2np.udp.port=.*/i2np.udp.port=${EXTERNAL_PORT}/" router.config
        sed -i "s/i2np.ntcp.port=.*/i2np.ntcp.port=${EXTERNAL_PORT}/" router.config
        sed -i "s/i2np.udp.internalPort=.*/i2np.udp.internalPort=${EXTERNAL_PORT}/" router.config
    fi
fi

# Set required Java options
JAVA_OPTS="-Djava.net.preferIPv4Stack=false -Djava.library.path=${I2P}:${I2P}/lib -Di2p.dir.base=${I2P} -Di2p.dir.config=${HOME}/.i2p -DloggerFilenameOverride=logs/log-router-@.txt -Xmx${JVM_XMX}"

# Append JAVA17OPTS if defined
if [ -n "${JAVA17OPTS:-}" ]; then
    JAVA_OPTS="${JAVA_OPTS} ${JAVA17OPTS}"
fi

# Launch I2P+
echo "[startapp] Launching I2P+ ... please stand by ..."
echo ""
echo "[startapp] When I2P+ has started, review the existing UDP port on http://127.0.0.1:7667/info and ensure this port"
echo "[startapp] is permitted for both TCP and UDP in your firewall, and port-forwarded from your network router or modem"
echo "[startapp] in order to run the router at full capacity. Also be sure to configure your bandwidth settings."
echo ""
exec java -cp "${CLASSPATH}" ${JAVA_OPTS} net.i2p.router.RouterLaunch
