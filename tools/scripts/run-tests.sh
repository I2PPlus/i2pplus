#!/usr/bin/env bash
#
# Wrapper for `ant test`. Ensures test dependencies are present,
# then runs ant with the correct classpath properties.
#
# Usage:
#   tools/scripts/run-tests.sh             Run all tests (via ant test)
#   tools/scripts/run-tests.sh core        Run core tests only
#   tools/scripts/run-tests.sh router      Run router tests only

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

HAMCREST="${REPO_ROOT}/tools/test/hamcrest"
JUNIT="${REPO_ROOT}/tools/test/junit"
MOCKITO="${REPO_ROOT}/tools/test/mockito"
SCALA="${REPO_ROOT}/tools/test/scala"

# Download deps if missing
if [ ! -f "${HAMCREST}/hamcrest-core.jar" ] || [ ! -f "${JUNIT}/junit4.jar" ]; then
  "${SCRIPT_DIR}/ensure-testdeps.sh" --quiet
fi

ANT_PROPS="-Dhamcrest.home=${HAMCREST} -Djunit.home=${JUNIT} -Dmockito.home=${MOCKITO} -Dscalatest.libs=${SCALA}"

BOLD='\033[1m'
RESET='\033[0m'
RED='\033[0;31m'
GREEN='\033[0;32m'

# XML results go here, separate from build-i2p/ and from repo tree
RESULTS_DIR="/tmp/test-i2p"

total_tests=0
total_failures=0
total_errors=0

get_report_name() {
  case "$1" in
    core/java) echo "core" ;;
    apps/ministreaming/java) echo "ministreaming" ;;
    apps/streaming/java) echo "streaming" ;;
    *) echo "$(basename "$(dirname "$1")")" ;;
  esac
}

get_build_props() {
  local dir="$1"
  local build_root="${TMPDIR:-/tmp}/build-i2p"
  local props="-Dbuild.dir=${build_root}/${dir}/build"
  case "$dir" in
    apps/ministreaming/java)
      props="$props -Dcore.java.build.dir=${build_root}/core/java/build"
      ;;
    apps/streaming/java|apps/i2ptunnel/java)
      props="$props -Dcore.java.build.dir=${build_root}/core/java/build -Dministreaming.java.build.dir=${build_root}/apps/ministreaming/java/build"
      ;;
    router/java)
      props="$props -Dcore.java.build.dir=${build_root}/core/java/build -Dapps.i2ptunnel.java.build.dir=${build_root}/apps/i2ptunnel/java/build"
      ;;
  esac
  echo "$props"
}

run_build() {
  local dir="$1" target="${2:-jar}"
  local logfile=$(mktemp /tmp/i2p_build_XXXXX.log)
  (cd "${REPO_ROOT}/${dir}" && ant $ANT_PROPS $(get_build_props "$dir") "$target" -logfile "$logfile" > /dev/null 2>&1)
  rm -f "$logfile"
}

# Run ant test for a module in the background.
# Writes "tests failures errors" to result_file on completion.
run_test_bg() {
  local dir="$1"
  local result_file="$2"
  local logfile=$(mktemp /tmp/i2p_test_XXXXX.log)

  (
    if [ ! -d "${REPO_ROOT}/${dir}/test" ]; then
      echo "0 0 0" > "$result_file"
      rm -f "$logfile"
      exit 0
    fi

    cd "${REPO_ROOT}/${dir}" && ant $ANT_PROPS $(get_build_props "$dir") test -logfile "$logfile" > /dev/null 2>&1 || true

    local report_name
    report_name=$(get_report_name "$dir")

    local test_dir="${RESULTS_DIR}/${report_name}/junit"
    local tests=0 failures=0 errors=0
    if [ -d "$test_dir" ]; then
      for xml in "${test_dir}"/TEST-*.xml; do
        [ -f "$xml" ] || continue
        tests=$((tests + $(grep -oP 'tests="\K[0-9]+' "$xml" 2>/dev/null || echo 0)))
        failures=$((failures + $(grep -oP 'failures="\K[0-9]+' "$xml" 2>/dev/null || echo 0)))
        errors=$((errors + $(grep -oP 'errors="\K[0-9]+' "$xml" 2>/dev/null || echo 0)))
      done
    fi
    echo "$tests $failures $errors" > "$result_file"
    rm -f "$logfile"
  ) &
}

run_test() {
  local dir="$1"
  local label="$2"

  if [ ! -d "${REPO_ROOT}/${dir}/test" ]; then
    echo -e "${BOLD}${label}${RESET}"
    echo "  (no tests)"
    return 0
  fi

  local logfile=$(mktemp /tmp/i2p_test_XXXXX.log)
  echo -e "${BOLD}${label}${RESET}"
  (cd "${REPO_ROOT}/${dir}" && ant $ANT_PROPS $(get_build_props "$dir") test -logfile "$logfile" > /dev/null 2>&1) || true
  rm -f "$logfile"

  local report_name
  report_name=$(get_report_name "$dir")

  local test_dir="${RESULTS_DIR}/${report_name}/junit"
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
    for xml in "${test_dir}"/TEST-*.xml; do
      [ -f "$xml" ] || continue
      if grep -q 'failures="[^0]"' "$xml" 2>/dev/null || grep -q 'errors="[^0]"' "$xml" 2>/dev/null; then
        local name
        name=$(basename "$xml" .xml | sed 's/^TEST-//')
        echo -e "    ${RED}${name}${RESET}"
      fi
    done
  else
    echo "  Results: ${passed} passed [Total: ${tests}]"
  fi
  echo ""
}

print_summary() {
  local total_tests=$1 total_failures=$2 total_errors=$3
  echo "------------------------"
  local passed=$((total_tests - total_failures - total_errors))
  if [ "$total_failures" -gt 0 ] || [ "$total_errors" -gt 0 ]; then
    echo -e "${BOLD}Results: ${passed} passed, ${RED}${total_failures} failed${RESET}${BOLD}, ${total_errors} errors [Total: ${total_tests}]${RESET}"
  else
    echo -e "${GREEN}${BOLD}Results: ${total_tests} passed [Total: ${total_tests}]${RESET}"
  fi
}

read_result() {
  local f="$1"
  if [ -f "$f" ]; then
    cat "$f"
  else
    echo "0 0 0"
  fi
}

# --- Main ---

# Symlink REPO_ROOT/reports → RESULTS_DIR so ant writes XML directly to /tmp
# and nothing is left in the workspace.
rm -rf "${RESULTS_DIR}"
mkdir -p "${RESULTS_DIR}"
rm -rf "${REPO_ROOT}/reports"
ln -s "${RESULTS_DIR}" "${REPO_ROOT}/reports"

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
    run_build core/java jarTest
    run_build apps/ministreaming/java
    run_build apps/i2ptunnel/java
    run_build router/java
    run_test router/java "Router"
    ;;
  all|*)
    # Build all modules first (jar dependencies prevent parallelism)
    run_build core/java
    run_build core/java jarTest
    run_build apps/ministreaming/java
    run_build apps/streaming/java
    run_build apps/i2ptunnel/java
    run_build router/java

    # Run all test suites in parallel (safe now that clean is removed from junit.test)
    core_r=$(mktemp)
    mini_r=$(mktemp)
    stream_r=$(mktemp)
    router_r=$(mktemp)
    trap 'rm -f "$core_r" "$mini_r" "$stream_r" "$router_r"' EXIT

    run_test_bg core/java "$core_r"
    run_test_bg apps/ministreaming/java "$mini_r"
    run_test_bg apps/streaming/java "$stream_r"
    run_test_bg router/java "$router_r"

    echo -e "${BOLD}Running test suites in parallel...${RESET}"
    wait

    # Print per-suite results and aggregate
    total_t=0; total_f=0; total_e=0
    for pair in "Core:$core_r" "MiniStreaming:$mini_r" "Streaming:$stream_r" "Router:$router_r"; do
      label="${pair%%:*}"
      rf="${pair#*:}"
      read -r t f e <<< "$(read_result "$rf")"
      p=$((t - f - e))
      total_t=$((total_t + t)); total_f=$((total_f + f)); total_e=$((total_e + e))
      if [ "$f" -gt 0 ] || [ "$e" -gt 0 ]; then
        echo -e "  ${BOLD}${label}${RESET}: ${p} passed, ${f} failed, ${e} errors [Total: ${t}]"
      elif [ "$t" -gt 0 ]; then
        echo -e "  ${BOLD}${label}${RESET}: ${p} passed [Total: ${t}]"
      else
        echo -e "  ${BOLD}${label}${RESET}: (no tests)"
      fi
    done
    print_summary "$total_t" "$total_f" "$total_e"
    ;;
esac

# Generate consolidated HTML report only for full suite runs
if [ "${1:-all}" = "all" ] && [ -d "${RESULTS_DIR}" ]; then
  python3 "${REPO_ROOT}/tools/test/unit-tests-to-html.py" "${RESULTS_DIR}" "${REPO_ROOT}/dist/test-report.html"
fi
