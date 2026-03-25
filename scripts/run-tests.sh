#!/usr/bin/env bash
#
# Wrapper for `ant test`. Ensures test dependencies are present,
# then runs ant with the correct classpath properties.
#
# Usage:
#   scripts/run-tests.sh             Run all tests
#   scripts/run-tests.sh core        Run core tests only
#   scripts/run-tests.sh router      Run router tests only

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

HAMCREST="${REPO_ROOT}/tools/test/hamcrest"
JUNIT="${REPO_ROOT}/tools/test/junit"
MOCKITO="${REPO_ROOT}/tools/test/mockito"
SCALA="${REPO_ROOT}/tools/test/scala"

# Download deps if missing
if [ ! -f "${HAMCREST}/hamcrest-core.jar" ] || [ ! -f "${JUNIT}/junit4.jar" ]; then
  "${SCRIPT_DIR}/ensure-testdeps.sh" --quiet
fi

ANT_PROPS="-Dhamcrest.home=${HAMCREST} -Djunit.home=${JUNIT} -Dmockito.home=${MOCKITO} -Dscalatest.libs=${SCALA}"
LOG_FILE=$(mktemp /tmp/i2p_test_XXXXX.log)
trap 'rm -f "$LOG_FILE"' EXIT

BOLD='\033[1m'
RESET='\033[0m'
RED='\033[0;31m'
GREEN='\033[0;32m'

total_tests=0
total_failures=0
total_errors=0

# Map source dir to report dir name
get_report_name() {
  case "$1" in
    core/java) echo "core" ;;
    apps/ministreaming/java) echo "ministreaming" ;;
    apps/streaming/java) echo "streaming" ;;
    *) echo "$(basename "$(dirname "$1")")" ;;
  esac
}

run_test() {
  local dir="$1"
  local label="$2"
  echo -e "${BOLD}${label}${RESET}"
  cd "${REPO_ROOT}/${dir}" && ant $ANT_PROPS test -logfile "$LOG_FILE" > /dev/null 2>&1

  local report_name
  report_name=$(get_report_name "$dir")
  local test_dir="${REPO_ROOT}/reports/${report_name}/junit"

  local tests=0 failures=0 errors=0
  if [ -d "$test_dir" ]; then
    for xml in "${test_dir}"/TEST-*.xml; do
      [ -f "$xml" ] || continue
      tests=$((tests + $(grep -oP 'tests="\K[0-9]+' "$xml" 2>/dev/null || echo 0)))
      failures=$((failures + $(grep -oP 'failures="\K[0-9]+' "$xml" 2>/dev/null || echo 0)))
      errors=$((errors + $(grep -oP 'errors="\K[0-9]+' "$xml" 2>/dev/null || echo 0)))
    done
  fi

  total_tests=$((total_tests + tests))
  total_failures=$((total_failures + failures))
  total_errors=$((total_errors + errors))

  local passed=$((tests - failures - errors))
  if [ "$failures" -gt 0 ] || [ "$errors" -gt 0 ]; then
    echo "  Results: ${passed} passed, ${failures} failed, ${errors} errors [Total: ${tests}]"
    if [ -d "$test_dir" ]; then
      for xml in "${test_dir}"/TEST-*.xml; do
        [ -f "$xml" ] || continue
        if grep -q 'failures="[^0]"' "$xml" 2>/dev/null || grep -q 'errors="[^0]"' "$xml" 2>/dev/null; then
          local name
          name=$(basename "$xml" .xml | sed 's/^TEST-//')
          echo -e "    ${RED}${name}${RESET}"
        fi
      done
    fi
  else
    echo "  Results: ${passed} passed [Total: ${tests}]"
  fi
  echo ""
}

run_build() {
  local dir="$1"
  cd "${REPO_ROOT}/${dir}" && ant $ANT_PROPS build -logfile "$LOG_FILE" > /dev/null 2>&1
}

rm -rf "${REPO_ROOT}/reports" 2>/dev/null

case "${1:-all}" in
  core)
    run_build core/java
    run_test core/java "Core"
    ;;
  ministreaming)
    run_build core/java
    run_test apps/ministreaming/java "MiniStreaming"
    ;;
  streaming)
    run_build core/java
    run_build apps/ministreaming/java
    run_test apps/streaming/java "Streaming"
    ;;
  router)
    run_build core/java
    run_build router/java
    run_test router/java "Router"
    ;;
  all|*)
    run_build core/java
    run_build apps/ministreaming/java
    run_build apps/streaming/java
    run_build router/java
    run_test core/java "Core"
    run_test apps/ministreaming/java "MiniStreaming"
    run_test apps/streaming/java "Streaming"
    run_test router/java "Router"
    ;;
esac

echo "------------------------"
passed=$((total_tests - total_failures - total_errors))
if [ "$total_failures" -gt 0 ] || [ "$total_errors" -gt 0 ]; then
  echo -e "${BOLD}Results: ${passed} passed, ${RED}${total_failures} failed${RESET}${BOLD}, ${total_errors} errors [Total: ${total_tests}]${RESET}"
else
  echo -e "${GREEN}${BOLD}Results: ${total_tests} passed [Total: ${total_tests}]${RESET}"
fi

# Generate HTML report
if [ -d "${REPO_ROOT}/reports" ]; then
  python3 "${REPO_ROOT}/tools/test/unit-tests-to-html.py" "${REPO_ROOT}/reports" "${REPO_ROOT}/dist/test-report.html"
fi
