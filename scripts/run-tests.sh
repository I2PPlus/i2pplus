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

run_test() {
  local dir="$1"
  cd "${REPO_ROOT}/${dir}" && ant $ANT_PROPS test
}

run_build() {
  local dir="$1"
  cd "${REPO_ROOT}/${dir}" && ant $ANT_PROPS build
}

case "${1:-all}" in
  core)
    run_build core/java
    run_test core/java
    ;;
  ministreaming)
    run_build core/java
    run_test apps/ministreaming/java
    ;;
  streaming)
    run_build core/java
    run_build apps/ministreaming/java
    run_test apps/streaming/java
    ;;
  all|*)
    # Build all, then test (each test target runs clean internally)
    run_build core/java
    run_build apps/ministreaming/java
    run_build apps/streaming/java
    run_test core/java
    # Rebuild core (clean wiped it)
    run_build core/java
    run_build apps/ministreaming/java
    run_build apps/streaming/java
    run_test apps/ministreaming/java
    run_test apps/streaming/java
    ;;
esac
