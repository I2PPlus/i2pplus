#!/bin/bash
#
# Run SonarQube analysis on I2P+.
# Auto-downloads server + scanner, starts server, runs analysis, stops server.
#
# Usage: run-sonar.sh [--no-stop] [--local] [sonar-project.properties]
#
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
PROPERTIES_FILE="${SCRIPT_DIR}/sonar-project.properties"
NO_STOP=false
LOCAL=false
SCAN_EXIT=0
# --no-stop: leave server running after scan
# --local: generate report with file:// links for editor integration
parse_args() {
    local args=("$@")
    PROPERTIES_FILE="${SCRIPT_DIR}/sonar-project.properties"
    for arg in "${args[@]}"; do
        case "$arg" in
            --no-stop) NO_STOP=true ;;
            --local) LOCAL=true ;;
            *) PROPERTIES_FILE="$arg" ;;
        esac
    done
}
parse_args "$@"

# ---- Helper to find latest server/scanner dir ----
find_latest() {
    local PATTERN="$1"
    find "$SCRIPT_DIR" -maxdepth 1 -type d -name "$PATTERN" | sort | tail -1
}

# ---- Config ----
SONAR_PORT="${SONAR_PORT:-11199}"
SONAR_HOST="http://localhost:${SONAR_PORT}"
SONAR_TMP="/tmp/build-i2p/sonarqube"
SONAR_ISSUES_CACHE="${SONAR_TMP}/.issues-cache.json"

SERVER_DIR="$(find_latest "sonarqube-*")"
SONAR_USER="${SONAR_USER:-admin}"
SONAR_PASSWORD="${SONAR_PASSWORD:-SonarQube2026!}"
SONAR_TOKEN_FILE="${SCRIPT_DIR}/.token"

# SonarQube 26.x requires Java 17-21; default system JDK (27 EA) breaks ES.
# Use Java 21 if available, otherwise fall back to the system default.
if [ -z "$SONAR_JAVA_PATH" ]; then
    for jv in "/usr/lib/jvm/java-21-openjdk-amd64" "/usr/lib/jvm/java-17-openjdk-amd64"; do
        if [ -x "$jv/bin/java" ]; then
            export SONAR_JAVA_PATH="$jv/bin/java"
            export JAVA_HOME="$jv"
            break
        fi
    done
fi

# ---- Helpers ----
ensure_downloaded() {
    local WHAT="$1"
    bash "${SCRIPT_DIR}/download-sonar.sh" "$WHAT" 2>&1
}

# ---- Ensure scanner installed ----
ensure_downloaded scanner
SCANNER_DIR=$(find_latest "sonar-scanner-*")
SCANNER_BIN="${SCANNER_DIR}/bin/sonar-scanner"
if [ ! -x "$SCANNER_BIN" ]; then
    echo "Error: SonarScanner not found at $SCANNER_BIN"
    exit 1
fi

# ---- Ensure server installed ----
ensure_downloaded server
SERVER_DIR=$(find_latest "sonarqube-*")
if [ -z "$SERVER_DIR" ]; then
    echo "Error: SonarQube Server directory not found"
    exit 1
fi
SONAR_SH="${SERVER_DIR}/bin/linux-x86-64/sonar.sh"
if [ ! -x "$SONAR_SH" ]; then
    echo "Error: sonar.sh not found at $SONAR_SH"
    exit 1
fi

# ---- Redirect runtime dirs to /tmp so workspace stays clean ----
SONAR_CACHE="${SONAR_TMP}/data"
mkdir -p "$SONAR_TMP" "$SONAR_CACHE"
# data — migrate existing data on first run, then symlink
if [ -e "${SERVER_DIR}/data" ] && [ ! -L "${SERVER_DIR}/data" ]; then
    cp -a "${SERVER_DIR}/data"/* "$SONAR_CACHE"/ 2>/dev/null || true
    rm -rf "${SERVER_DIR}/data"
fi
ln -snf "$SONAR_CACHE" "${SERVER_DIR}/data"
# logs — replace dir with symlink so nohup redirect and sonar.properties agree
if [ -e "${SERVER_DIR}/logs" ] && [ ! -L "${SERVER_DIR}/logs" ]; then
    rm -rf "${SERVER_DIR}/logs"
fi
mkdir -p "${SONAR_TMP}/logs"
ln -snf "${SONAR_TMP}/logs" "${SERVER_DIR}/logs"
# temp — replace dir with symlink for PID file etc
if [ -e "${SERVER_DIR}/temp" ] && [ ! -L "${SERVER_DIR}/temp" ]; then
    rm -rf "${SERVER_DIR}/temp"
fi
mkdir -p "${SONAR_TMP}/temp"
ln -snf "${SONAR_TMP}/temp" "${SERVER_DIR}/temp"
# Clean up any stray recursive symlink inside the cache from prior bad runs
rm -f "${SONAR_CACHE}/data" "${SONAR_TMP}/logs/data" "${SONAR_TMP}/temp/data"

# ---- Server startup ----
echo "Starting SonarQube server..."

# ---- Kill any existing instance to ensure known password ----
STARTED_BY_US=false
if curl -m 2 -s -o /dev/null -w "%{http_code}" "${SONAR_HOST}/api/system/health" 2>/dev/null | grep -qE '200|403|401'; then
    echo "Stopping existing SonarQube (restarting with known password)..."
    timeout 5 LD_PRELOAD="" "$SONAR_SH" stop 2>/dev/null || true
    sleep 3
fi

# Clean up stale PID regardless
if [ -f "${SERVER_DIR}/bin/linux-x86-64/SonarQube.pid" ]; then
    echo "Removing leftover PID file..."
    if ! timeout 5 LD_PRELOAD="" "$SONAR_SH" force-stop 2>/dev/null; then
        echo "Force-stop timed out, killing remaining SonarQube processes..."
        pkill -f "sonar-application" 2>/dev/null || true
        pkill -f "org.elasticsearch" 2>/dev/null || true
        sleep 2
    fi
    rm -rf "${SERVER_DIR}/data/es8"
    rm -f "${SERVER_DIR}/bin/linux-x86-64/SonarQube.pid"
fi

# Configure port and memory in sonar.properties
SONAR_PROPERTIES="${SERVER_DIR}/conf/sonar.properties"
if grep -q "^sonar.web.port=" "$SONAR_PROPERTIES" 2>/dev/null; then
    sed -i "s/^sonar.web.port=.*/sonar.web.port=${SONAR_PORT}/" "$SONAR_PROPERTIES"
else
    echo "sonar.web.port=${SONAR_PORT}" >> "$SONAR_PROPERTIES"
fi
sed -i '/^sonar.web.javaOpts=/d' "$SONAR_PROPERTIES"
echo "sonar.web.javaOpts=-Xms512m -Xmx1g -XX:+HeapDumpOnOutOfMemoryError" >> "$SONAR_PROPERTIES"
sed -i '/^sonar.ce.javaOpts=/d' "$SONAR_PROPERTIES"
echo "sonar.ce.javaOpts=-Xms512m -Xmx1g -XX:+HeapDumpOnOutOfMemoryError" >> "$SONAR_PROPERTIES"
sed -i '/^sonar.search.javaOpts=/d' "$SONAR_PROPERTIES"
echo "sonar.search.javaOpts=-Xms1g -Xmx2g" >> "$SONAR_PROPERTIES"

sed -i '/^sonar.path.logs=/d' "$SONAR_PROPERTIES"
echo "sonar.path.logs=${SONAR_TMP}/logs" >> "$SONAR_PROPERTIES"
sed -i '/^sonar.path.temp=/d' "$SONAR_PROPERTIES"
echo "sonar.path.temp=${SONAR_TMP}/temp" >> "$SONAR_PROPERTIES"

echo "Starting SonarQube Server on port ${SONAR_PORT}..."
export ES_DISCOVERY_TYPE=single-node
LD_PRELOAD="" "$SONAR_SH" start
STARTED_BY_US=true

echo "Waiting for server to be ready..."
for i in $(seq 1 180); do
    STATUS=$(curl -m 2 -s "${SONAR_HOST}/api/system/status" 2>/dev/null | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    print(d.get('status', ''))
except Exception:
    print('')
" 2>/dev/null)
    if [ "$STATUS" = "UP" ]; then
        echo "Server ready after ${i}s"
        sleep 3
        break
    fi
    # Detect early process death (ES/CE/web crashed)
    if ! grep -q "SonarQube is stopped" "${SERVER_DIR}/logs/sonar.log" 2>/dev/null; then
        if ! pgrep -f "sonar-application" >/dev/null 2>&1; then
            echo "Error: SonarQube process died unexpectedly (check logs)"
            tail -30 "${SERVER_DIR}/logs/sonar.log" 2>/dev/null
            exit 1
        fi
    fi
    if [ "$i" -eq 180 ]; then
        echo "Error: Server failed to start within 180s"
        exit 1
    fi
    sleep 1
done

# ---- Force admin password to known value ----
# Prevents Web UI password-change prompt and keeps CLI token generation working
# across fresh and cached-data restarts. Retries until the API is ready.
for attempt in 1 2 3 4 5; do
    if curl -s -u "admin:admin" -X POST "${SONAR_HOST}/api/users/change_password" \
        --data-urlencode "login=admin" \
        --data-urlencode "previousPassword=admin" \
        --data-urlencode "password=${SONAR_PASSWORD}" -o /dev/null -w "%{http_code}" 2>/dev/null | grep -q 200; then
        break
    fi
    if curl -s -u "admin:${SONAR_PASSWORD}" -X POST "${SONAR_HOST}/api/users/change_password" \
        --data-urlencode "login=admin" \
        --data-urlencode "previousPassword=${SONAR_PASSWORD}" \
        --data-urlencode "password=${SONAR_PASSWORD}" -o /dev/null -w "%{http_code}" 2>/dev/null | grep -q 200; then
        break
    fi
    sleep 2
done

# ---- Get or generate token ----
# ---- Persist password for Web UI ----
SONAR_PASSWORD_FILE="${SCRIPT_DIR}/.password"
echo "${SONAR_PASSWORD}" > "$SONAR_PASSWORD_FILE"

_get_token() {
    LD_PRELOAD="" curl -s -u "${SONAR_USER}:${SONAR_PASSWORD}" \
        -X POST "${SONAR_HOST}/api/user_tokens/generate" \
        --data-urlencode "name=scan-$(date +%s)"
}

if [ -n "${SONAR_TOKEN}" ]; then
    SONAR_TOKEN_ARG="-Dsonar.token=${SONAR_TOKEN}"
elif [ -f "${SONAR_TOKEN_FILE}" ]; then
    TOKEN=$(cat "${SONAR_TOKEN_FILE}")
    # Verify token still works
    TOKEN_OK=$(LD_PRELOAD="" curl -s -o /dev/null -w "%{http_code}" \
        "${SONAR_HOST}/api/plugins/installed" \
        -H "Authorization: Bearer ${TOKEN}" 2>/dev/null)
    if [ "$TOKEN_OK" = "200" ]; then
        SONAR_TOKEN_ARG="-Dsonar.token=${TOKEN}"
    else
        echo "Cached token expired, generating new one..."
        rm -f "${SONAR_TOKEN_FILE}"
    fi
fi

if [ -z "${SONAR_TOKEN_ARG}" ]; then
    echo "Generating scanner token..."
    for attempt in 1 2 3 4 5; do
        TOKEN_JSON=$(_get_token 2>/dev/null) || true
        TOKEN=$(echo "$TOKEN_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin).get('token',''))" 2>/dev/null) || true
        if [ -n "$TOKEN" ]; then
            break
        fi
        echo "  Token generation attempt $attempt failed, retrying in 10s..."
        sleep 10
    done
    if [ -z "$TOKEN" ]; then
        echo "Error: Failed to generate token after 5 attempts"
        echo "Last response: $TOKEN_JSON"
        exit 1
    fi
    echo "$TOKEN" > "$SONAR_TOKEN_FILE"
    SONAR_TOKEN_ARG="-Dsonar.token=${TOKEN}"
fi

# ---- Extract token value ----
SONAR_TOKEN_VALUE=$(echo "${SONAR_TOKEN_ARG}" | sed 's/-Dsonar.token=//')

# ---- Run analysis ----
echo "Running SonarQube analysis..."
echo "Server: ${SONAR_HOST}"
echo "Config: ${PROPERTIES_FILE}"
echo ""

BUILD_ROOT="${TMPDIR:-/tmp}/build-i2p"
SCAN_OUTPUT=$(LD_PRELOAD="" SONAR_SCANNER_OPTS="-Xmx8g" \
    "$SCANNER_BIN" \
    ${SONAR_TOKEN_ARG} \
    -Dsonar.projectBaseDir="$PROJECT_DIR" \
    -Dproject.settings="$PROPERTIES_FILE" \
    -Dsonar.java.binaries="${BUILD_ROOT}/build" \
    -Dsonar.java.libraries="${BUILD_ROOT}/build/*.jar" \
    -Dsonar.working.directory="${SONAR_TMP}/scanner-work" \
    -Dsonar.userHome="${SONAR_TMP}/scanner-home" \
    -Dsonar.scanner.socketTimeout=1800 2>&1)
SCAN_EXIT=$?
echo "$SCAN_OUTPUT"

# Extract CE task ID from scanner output
CE_TASK_ID=$(echo "$SCAN_OUTPUT" | grep -oP 'id=[a-f0-9-]+' | head -1 | cut -d= -f2)

# Wait for Compute Engine to process
if [ -n "$CE_TASK_ID" ]; then
    echo "Waiting for Compute Engine (task $CE_TASK_ID)..."
    for i in $(seq 1 150); do
        TASK_STATUS=$(LD_PRELOAD="" curl -s \
            -H "Authorization: Bearer ${SONAR_TOKEN_VALUE}" \
            "${SONAR_HOST}/api/ce/task?id=${CE_TASK_ID}" 2>/dev/null)
        CURRENT_STATUS=$(echo "$TASK_STATUS" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    t = d.get('task', {})
    print(t.get('status', 'UNKNOWN'))
except Exception:
    print('ERROR')
" 2>/dev/null)
        if [ "$CURRENT_STATUS" = "SUCCESS" ]; then
            echo "CE processing finished after ${i}s"
            break
        fi
        if [ "$CURRENT_STATUS" = "FAILED" ] || [ "$CURRENT_STATUS" = "CANCELED" ]; then
            echo "CE task ${CURRENT_STATUS} after ${i}s (continuing anyway)"
            echo "  Error: $(echo "$TASK_STATUS" | python3 -c 'import sys,json; print(json.load(sys.stdin).get(\"task\",{}).get(\"errorMessage\",\"\"))' 2>/dev/null)"
            break
        fi
        if [ "$i" -eq 150 ]; then
            echo "CE not finished after 300s (continuing anyway)"
        fi
        sleep 2
    done
fi

# ---- Generate HTML report ----
if [ -n "$CE_TASK_ID" ]; then
    CACHE_ARG=""
    if [ -f "$SONAR_ISSUES_CACHE" ]; then
        CACHE_ARG="--cached-issues ${SONAR_ISSUES_CACHE}"
    else
        CACHE_ARG="--save-cache ${SONAR_ISSUES_CACHE}"
    fi
    SONAR_RULES_URL="${SONAR_RULES_URL:-https://cloud-ci.sgs.com/sonar}"
    run_python() {
        for attempt in 1 2 3; do
            if python3 "${SCRIPT_DIR}/sonarqube-to-html.py" \
                ${CACHE_ARG} \
                --token "${SONAR_TOKEN_VALUE}" \
                --url "${SONAR_HOST}" \
                --project "net.i2p.router:i2pplus" \
                "$@" \
                --sonar-url "${SONAR_RULES_URL}"; then
                break
            else
                echo "  Report generation attempt $attempt failed, retrying in 10s..."
                sleep 10
            fi
        done
    }
    if [ "$LOCAL" = "true" ]; then
        run_python --output "dist/sonarqube-local.html"
    else
        run_python --output "dist/sonarqube.html" --output "dist/sonarqube-local.html"
    fi
fi

# ---- Print memory usage summary if server was started ----
if [ "$STARTED_BY_US" = true ]; then
    echo ""
    echo "=== System memory ==="
    free -h
    echo ""
    echo "=== SonarQube process RSS ==="
    for pid in $(pgrep -f "sonar-application|org.sonar.server|org.elasticsearch" 2>/dev/null); do
        NAME=$(ps -p $pid -o comm= 2>/dev/null || echo "java")
        RSS_KB=$(ps -p $pid -o rss= 2>/dev/null || echo 0)
        RSS_MB=$((RSS_KB / 1024))
        echo "  PID $pid ($NAME): ${RSS_MB}MB RSS"
    done
    echo ""
fi

# ---- Stop server if we started it ----
if [ "$STARTED_BY_US" = true ] && [ "$NO_STOP" = false ]; then
    echo "Stopping SonarQube Server..."
    if ! timeout 5 LD_PRELOAD="" "$SONAR_SH" stop 2>/dev/null; then
        echo "Stop timed out after 30s, force-stopping..."
        LD_PRELOAD="" "$SONAR_SH" force-stop 2>/dev/null || true
        sleep 2
        rm -rf "${SERVER_DIR}/data/es8"
        rm -f "${SERVER_DIR}/bin/linux-x86-64/SonarQube.pid"
    fi
fi

exit $SCAN_EXIT
