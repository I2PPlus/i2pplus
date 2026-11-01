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

# --- config helpers (exercised by tools/scripts/tests/checkdockerconfig.sh) ---

# Write key = value into a router config file, appending the key when the file
# does not have it. Java Properties takes the last occurrence of a key, so
# every occurrence is replaced. The file is rewritten through a temp file and
# moved over it, because the sed in busybox and the sed in GNU coreutils do not
# agree on -i, and a config file that is half written is worse than one that is
# not written at all.
#
# A file that is not there is not created, and the call reports that by
# returning non-zero. The router copies the base-dir template into the config
# directory on the first start, and a config file created here would suppress
# that copy and lose the rest of the template with it.
set_config() {
    _sc_file=$1
    _sc_key=$2
    _sc_value=$3
    [ -f "$_sc_file" ] || return 1
    _sc_tmp="${_sc_file}.startapp.$$"
    if grep -q "^${_sc_key}=" "$_sc_file"; then
        sed "s|^${_sc_key}=.*|${_sc_key}=${_sc_value}|" "$_sc_file" >"$_sc_tmp" || { rm -f "$_sc_tmp"; return 1; }
    else
        cat "$_sc_file" >"$_sc_tmp" || { rm -f "$_sc_tmp"; return 1; }
        printf '%s=%s\n' "$_sc_key" "$_sc_value" >>"$_sc_tmp" || { rm -f "$_sc_tmp"; return 1; }
    fi
    mv "$_sc_tmp" "$_sc_file" || { rm -f "$_sc_tmp"; return 1; }
    return 0
}

# Rewrite the console bind address in a clients.config file, in place, for
# every clientApp args line. The pattern is POSIX basic regular expression
# syntax, since the container's sed is busybox, and [0-9]\+ is not.
set_console_ip() {
    _si_file=$1
    _si_ip=$2
    [ -f "$_si_file" ] || return 1
    _si_tmp="${_si_file}.startapp.$$"
    sed "/^clientApp\.[0-9][0-9]*\.args=/s/127\.0\.0\.1/${_si_ip}/g" "$_si_file" >"$_si_tmp" || { rm -f "$_si_tmp"; return 1; }
    mv "$_si_tmp" "$_si_file" || { rm -f "$_si_tmp"; return 1; }
    return 0
}

# The config files an override has to reach. The router reads the ones in the
# config directory, and the base directory holds the template it copies into
# that directory on the first start of an empty volume, so an override written
# only to the template is a first-start seed and stops applying from the second
# start on, which is what made the port and console overrides look like they
# worked once and then never again.
override_targets() {
    for _ot_file in "$I2P/router.config" "$HOME/.i2p/router.config"; do
        echo "$_ot_file"
    done
}

console_targets() {
    for _ct_file in "$I2P/clients.config" "$HOME/.i2p/clients.config"; do
        echo "$_ct_file"
    done
}

# --- end config helpers ---

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

    # Bind the console to the configured IP in both the packaged template and
    # the active config, so the address holds on every start and not only the
    # first one (both non-SSL and SSL)
    _ip_written=
    for _ip_file in $(console_targets); do
        if set_console_ip "$_ip_file" "$IP_ADDR"; then
            _ip_written="${_ip_written} ${_ip_file}"
        fi
    done
    if [ -n "$_ip_written" ]; then
        echo "[startapp] Console bind address set to $IP_ADDR in:$_ip_written"
    else
        echo "[startapp] No clients.config found -> Console bind address left as packaged"
    fi

    # Override external port if EXTERNAL_PORT env var is set (runtime override)
    if [ -n "${EXTERNAL_PORT:-}" ] && [ "${EXTERNAL_PORT:-}" != "0" ]; then
        case "$EXTERNAL_PORT" in
            *[!0-9]*)
                echo "[startapp] EXTERNAL_PORT=$EXTERNAL_PORT is not a number -> Ignoring it"
                EXTERNAL_PORT=
                ;;
        esac
    fi
    if [ -n "${EXTERNAL_PORT:-}" ] && [ "${EXTERNAL_PORT:-}" != "0" ]; then
        if [ "$EXTERNAL_PORT" -lt 1 ] || [ "$EXTERNAL_PORT" -gt 65535 ]; then
            echo "[startapp] EXTERNAL_PORT=$EXTERNAL_PORT is outside 1-65535 -> Ignoring it"
            EXTERNAL_PORT=
        fi
    fi
    if [ -n "${EXTERNAL_PORT:-}" ] && [ "${EXTERNAL_PORT:-}" != "0" ]; then
        echo "[startapp] Setting external port to $EXTERNAL_PORT (from EXTERNAL_PORT env)"
        _port_written=
        _port_failed=
        for _port_file in $(override_targets); do
            if set_config "$_port_file" i2np.udp.internalPort "$EXTERNAL_PORT" &&
               set_config "$_port_file" i2np.udp.port "$EXTERNAL_PORT" &&
               set_config "$_port_file" i2np.ntcp.port "$EXTERNAL_PORT" &&
               set_config "$_port_file" i2np.udp.fixedPort true; then
                _port_written="${_port_written} ${_port_file}"
            else
                _port_failed="${_port_failed} ${_port_file}"
            fi
        done
        # The advertised port follows peer feedback unless it is pinned, and a
        # port the user forwarded is not useful once the router advertises a
        # different one, so fixedPort is set with the port.
        if [ -n "$_port_written" ]; then
            echo "[startapp] External port $EXTERNAL_PORT set in:$_port_written"
            echo "[startapp] The advertised port is pinned (i2np.udp.fixedPort=true) in those files"
        fi
        if [ -n "$_port_failed" ]; then
            echo "[startapp] External port not set in:$_port_failed"
            echo "[startapp] Those files do not exist yet; the router creates them from the template"
        fi
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
