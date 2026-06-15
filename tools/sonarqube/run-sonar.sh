#!/bin/bash
#
# Run SonarQube analysis on I2P+.
# Auto-downloads server + scanner, starts server, runs analysis, stops server.
#
# Usage: run-sonar.sh [--no-stop|--skip-scan] [--local] [sonar-project.properties]
#
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
PROPERTIES_FILE="${SCRIPT_DIR}/sonar-project.properties"
NO_STOP=false
SKIP_SCAN=false
LOCAL=false
SCAN_EXIT=0
# --no-stop: leave server running after scan
# --skip-scan: start server, generate report from cached data, stop
# --local: generate report with file:// links for editor integration
parse_args() {
    local args=("$@")
    PROPERTIES_FILE="${SCRIPT_DIR}/sonar-project.properties"
    for arg in "${args[@]}"; do
        case "$arg" in
            --no-stop) NO_STOP=true ;;
            --skip-scan) SKIP_SCAN=true ;;
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
SONAR_ISSUES_CACHE="/tmp/i2p-sonarqube/.issues-cache.json"

SERVER_DIR="$(find_latest "sonarqube-*")"
for f in "${SCRIPT_DIR}"/sonarqube-*/logs/*.log; do
    : > "$f" 2>/dev/null || true
done
SONAR_USER="${SONAR_USER:-admin}"
SONAR_PASSWORD="${SONAR_PASSWORD:-SonarQube2026!}"
SONAR_TOKEN_FILE="${SCRIPT_DIR}/.token"

# ---- Helpers ----
ensure_downloaded() {
    local WHAT="$1"
    if ! bash "${SCRIPT_DIR}/download-sonar.sh" "$WHAT" 2>&1 | grep -qv "up to date"; then
        : # already installed
    fi
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

# ---- Cache data in /tmp for fast restarts ----
SONAR_CACHE="/tmp/i2p-sonarqube/data"
if [ -e "${SERVER_DIR}/data" ] && [ ! -L "${SERVER_DIR}/data" ]; then
    mkdir -p "$SONAR_CACHE"
    if ls "${SERVER_DIR}/data"/ >/dev/null 2>&1; then
        cp -a "${SERVER_DIR}/data"/* "$SONAR_CACHE"/
    fi
    rm -rf "${SERVER_DIR}/data"
fi
mkdir -p "$SONAR_CACHE"
ln -sf "$SONAR_CACHE" "${SERVER_DIR}/data"
# Clean up any stray recursive symlink inside the cache from prior bad runs
if [ -L "${SONAR_CACHE}/data" ]; then
    rm -f "${SONAR_CACHE}/data"
fi

# ---- Skip server startup if local cache available (for --skip-scan) ----
if [ "$SKIP_SCAN" = true ] && [ -f "$SONAR_ISSUES_CACHE" ]; then
    TOTAL=$(python3 -c "import json; print(len(json.load(open('${SONAR_ISSUES_CACHE}'))) )" 2>/dev/null)
    if [ "$TOTAL" -gt 0 ] 2>/dev/null; then
        CE_TASK_ID="cached"
    else
        rm -f "$SONAR_ISSUES_CACHE"
    fi
fi

# ---- Server startup (skipped when local cache available) ----
if [ -z "$CE_TASK_ID" ]; then
    echo "Starting SonarQube server (no local issues cache)..."

# ---- Kill any existing instance to ensure known password ----
STARTED_BY_US=false
if curl -m 2 -s -o /dev/null -w "%{http_code}" "${SONAR_HOST}/api/system/health" 2>/dev/null | grep -qE '200|403|401'; then
    echo "Stopping existing SonarQube (restarting with known password)..."
    LD_PRELOAD="" "$SONAR_SH" stop 2>/dev/null || true
    sleep 3
fi

# Clean up stale PID regardless
if [ -f "${SERVER_DIR}/bin/linux-x86-64/SonarQube.pid" ]; then
    echo "Removing leftover PID file..."
    LD_PRELOAD="" "$SONAR_SH" force-stop 2>/dev/null || true
    sleep 2
    # Unclean shutdown — ES cluster state may be corrupt, force fresh
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
    # Set 8GB heap for web server
    sed -i '/^sonar.web.javaOpts=/d' "$SONAR_PROPERTIES"
    echo "sonar.web.javaOpts=-Xms512m -Xmx1g -XX:+HeapDumpOnOutOfMemoryError" >> "$SONAR_PROPERTIES"
    # Set 2GB heap for compute engine
    sed -i '/^sonar.ce.javaOpts=/d' "$SONAR_PROPERTIES"
    echo "sonar.ce.javaOpts=-Xms512m -Xmx1g -XX:+HeapDumpOnOutOfMemoryError" >> "$SONAR_PROPERTIES"
    # Set 2GB for Elasticsearch
    sed -i '/^sonar.search.javaOpts=/d' "$SONAR_PROPERTIES"
    echo "sonar.search.javaOpts=-Xms1g -Xmx2g" >> "$SONAR_PROPERTIES"

    echo "Starting SonarQube Server on port ${SONAR_PORT}..."
    export ES_DISCOVERY_TYPE=single-node
    LD_PRELOAD="" "$SONAR_SH" start
    STARTED_BY_US=true

    echo "Waiting for server to be ready..."
    for i in $(seq 1 180); do
if curl -m 2 -s -o /dev/null -w "%{http_code}" "${SONAR_HOST}/api/system/health" 2>/dev/null | grep -qE '200|403|401'; then
            echo "Server ready after ${i}s"
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
    for attempt in 1 2 3; do
        TOKEN_JSON=$(_get_token)
        TOKEN=$(echo "$TOKEN_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin).get('token',''))" 2>/dev/null)
        if [ -n "$TOKEN" ]; then
            break
        fi
        echo "  Token generation attempt $attempt failed, retrying in 5s..."
        sleep 5
    done
    if [ -z "$TOKEN" ]; then
        echo "Error: Failed to generate token after 3 attempts"
        echo "Last response: $TOKEN_JSON"
        exit 1
    fi
    echo "$TOKEN" > "$SONAR_TOKEN_FILE"
    SONAR_TOKEN_ARG="-Dsonar.token=${TOKEN}"
fi
fi  # end server startup guard

# ---- Extract token value ----
SONAR_TOKEN_VALUE=$(echo "${SONAR_TOKEN_ARG}" | sed 's/-Dsonar.token=//')

# ---- Skip scan if --skip-scan and project has cached results ----
if [ -z "$CE_TASK_ID" ] && [ "$SKIP_SCAN" = true ]; then
    # Prefer local cache file (avoids needing the server at all)
    if [ -f "$SONAR_ISSUES_CACHE" ]; then
        TOTAL=$(python3 -c "import json; print(len(json.load(open('${SONAR_ISSUES_CACHE}'))) )" 2>/dev/null)
        if [ "$TOTAL" -gt 0 ] 2>/dev/null; then
            echo "Found $TOTAL issues in local cache ($SONAR_ISSUES_CACHE) — skipping scan."
            CE_TASK_ID="cached"
        else
            echo "Local cache empty, removing..."
            rm -f "$SONAR_ISSUES_CACHE"
        fi
    fi
    # Fall back to server API check
    if [ -z "$CE_TASK_ID" ]; then
        echo "No local cache — checking server for cached analysis results..."
        EXISTING=$(LD_PRELOAD="" curl -s \
            -H "Authorization: Bearer ${SONAR_TOKEN_VALUE}" \
            "${SONAR_HOST}/api/issues/search?componentKeys=net.i2p.router:i2pplus&ps=1" 2>/dev/null)
        TOTAL=$(echo "$EXISTING" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('total',0))" 2>/dev/null)
        if [ "$TOTAL" -gt 0 ] 2>/dev/null; then
            echo "Found $TOTAL cached issues on server — skipping scan."
            CE_TASK_ID="cached"
        else
            echo "No cached results found. Run without --skip-scan to perform a full analysis."
            exit 0
        fi
    fi
fi

# ---- Run analysis (skip if --skip-scan with cached results) ----
if [ "$SKIP_SCAN" = false ]; then
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
        -Dsonar.working.directory="/tmp/opencode/sonar-scanner-work" \
        -Dsonar.scanner.socketTimeout=1800 2>&1)
    SCAN_EXIT=$?
    echo "$SCAN_OUTPUT"

    # Extract CE task ID from scanner output
    CE_TASK_ID=$(echo "$SCAN_OUTPUT" | grep -oP 'id=[a-f0-9-]+' | head -1 | cut -d= -f2)

    # Wait for Compute Engine to process
    if [ "$SCAN_EXIT" = 0 ] && [ -n "$CE_TASK_ID" ] && [ "$CE_TASK_ID" != "cached" ]; then
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
fi

# ---- Generate HTML report (skips scan for cached or successful scan) ----
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
    LD_PRELOAD="" "$SONAR_SH" stop
fi

exit $SCAN_EXIT
