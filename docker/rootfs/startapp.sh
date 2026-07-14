#!/bin/sh
set -e
set -u

# Default JVM heap size
if [ -z "${JVM_XMX:-}" ]; then
    echo "[startapp] Using the default 1024MB JVM heap limit for I2P+..."
    echo "[startapp] To configure a different maximum value, change the JVM_XMX"
    echo "[startapp] variable value in startapp.sh (for example JVM_XMX=2048m)"
    echo ""
    JVM_XMX=1024m
fi

# Manually configure an ip address to use for I2P+ service access
# by uncommenting the IP_ADDR line below and editing as required
#IP_ADDR=192.168.1.10

# Set environment variables
export HOME=/i2p
export I2P=${HOME}

# Ensure config directory exists (may not on first run with empty volume)
mkdir -p "$HOME/.i2p"

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
        export IP_ADDR=0.0.0.0
        echo ""
        echo "[startapp] Binding web console to 0.0.0.0 (all interfaces)."
        echo "[startapp] To restrict to a specific IP, set IP_ADDR environment variable."
        echo ""
    fi
    echo "[startapp] Setting IP address for I2P+ console access to $IP_ADDR"
    echo "[startapp] When I2P+ is running, you can reach the web console at http://$IP_ADDR:7657/"
    echo ""

    # Rewrite clients.config to bind console to the configured IP (both non-SSL and SSL)
    if [ -f "clients.config" ]; then
        sed -i "/^clientApp\.[0-9]\+\.args=/s/127\.0\.0\.1/$IP_ADDR/g" clients.config
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
