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

RESULTS_DIR="/tmp/test-i2p"
ANT_PROPS="-Dhamcrest.home=${HAMCREST} -Djunit.home=${JUNIT} -Dmockito.home=${MOCKITO} -Dscalatest.libs=${SCALA} -Djunit.reports.dir=${RESULTS_DIR}"

BOLD='\033[1m'
RESET='\033[0m'
RED='\033[0;31m'
GREEN='\033[0;32m'

total_tests=0
total_failures=0
total_errors=0

get_report_name() {
  case "$1" in
    core/java) echo "core" ;;
    apps/ministreaming/java) echo "ministreaming" ;;
    apps/streaming/java) echo "streaming" ;;
    apps/addressbook) echo "addressbook" ;;
    apps/i2ptunnel/java) echo "i2ptunnel" ;;
    apps/routerconsole/java) echo "routerconsole" ;;
    apps/i2psnark/java) echo "i2psnark" ;;
    apps/susimail) echo "susimail" ;;
    apps/sam/java) echo "sam" ;;
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
    apps/addressbook)
      props="$props -Dcore.java.build.dir=${build_root}/core/java/build -Drouter.java.build.dir=${build_root}/router/java/build -Dministreaming.java.build.dir=${build_root}/apps/ministreaming/java/build -Dstreaming.java.build.dir=${build_root}/apps/streaming/java/build"
      ;;
    apps/routerconsole/java)
      props="$props -Dcore.java.build.dir=${build_root}/core/java/build -Drouter.java.build.dir=${build_root}/router/java/build -Dministreaming.java.build.dir=${build_root}/apps/ministreaming/java/build -Dstreaming.java.build.dir=${build_root}/apps/streaming/java/build -Di2ptunnel.java.build.dir=${build_root}/apps/i2ptunnel/java/build -Djetty.pkg.dir=${build_root}/apps/jetty/build -Djrobin.java.build.dir=${build_root}/apps/jrobin/java/build -Dsystray.java.build.dir=${build_root}/apps/systray/java/build -Ddesktopgui.dist.dir=${build_root}/apps/desktopgui/dist -Ddesktopgui.build.dir=${build_root}/apps/desktopgui/build -Dwrapper.dir=${REPO_ROOT}/installer/lib/wrapper/all"
      ;;
    apps/i2psnark/java)
      props="$props -Dcore.java.build.dir=${build_root}/core/java/build -Dministreaming.java.build.dir=${build_root}/apps/ministreaming/java/build -Dstreaming.java.build.dir=${build_root}/apps/streaming/java/build -Drouter.java.build.dir=${build_root}/router/java/build -Djetty.pkg.dir=${build_root}/apps/jetty/build -Dsystray.java.build.dir=${build_root}/apps/systray/java/build -Di2ptunnel.java.build.dir=${build_root}/apps/i2ptunnel/java/build -Dservlet.jar=${REPO_ROOT}/apps/jetty/jettylib/javax.servlet.jar"
      ;;
    apps/susimail)
      props="$props -Dcore.java.build.dir=${build_root}/core/java/build -Djetty.pkg.dir=${build_root}/apps/jetty/build"
      ;;
    apps/sam/java)
      props="$props -Dcore.java.build.dir=${build_root}/core/java/build -Dministreaming.java.build.dir=${build_root}/apps/ministreaming/java/build -Dstreaming.java.build.dir=${build_root}/apps/streaming/java/build"
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

# Build jetty-i2p.jar without invoking jetty's ant build (which tries to download distro).
# Compiles java/src against pre-checked-in jars in jettylib/.
build_jetty() {
  local build_root="${TMPDIR:-/tmp}/build-i2p"
  local out="${build_root}/apps/jetty/build"
  local jettylib="${REPO_ROOT}/apps/jetty/jettylib"
  local cp="${jettylib}/javax.servlet.jar:${jettylib}/jetty-util.jar:${jettylib}/jetty-http.jar:${jettylib}/jetty-io.jar:${jettylib}/jetty-security.jar:${jettylib}/jetty-servlet.jar:${jettylib}/jetty-webapp.jar:${jettylib}/jetty-xml.jar:${jettylib}/org.mortbay.jetty.jar:${jettylib}/commons-logging.jar:${jettylib}/jasper-runtime.jar:${jettylib}/tomcat-api.jar:${jettylib}/jsp-api.jar:${jettylib}/jetty-deploy.jar:${build_root}/core/java/build/i2p.jar"
  mkdir -p "$out"
  javac --release 8 -Xlint:-options -cp "$cp" -d "$out" "${REPO_ROOT}/apps/jetty/java/src/net/i2p/servlet/util/ServletUtil.java" "${REPO_ROOT}/apps/jetty/java/src/net/i2p/servlet/util/WriterOutputStream.java" "${REPO_ROOT}/apps/jetty/java/src/net/i2p/servlet/util/JspC.java" "${REPO_ROOT}/apps/jetty/java/src/net/i2p/servlet/I2PDefaultServlet.java" "${REPO_ROOT}/apps/jetty/java/src/net/i2p/servlet/WebAppProviderConfiguration.java" "${REPO_ROOT}/apps/jetty/java/src/net/i2p/servlet/RequestWrapper.java" "${REPO_ROOT}/apps/jetty/java/src/net/i2p/servlet/ErrorServlet.java" "${REPO_ROOT}/apps/jetty/java/src/net/i2p/servlet/filters/XSSRequestWrapper.java" "${REPO_ROOT}/apps/jetty/java/src/net/i2p/servlet/filters/XI2PLocationFilter.java" "${REPO_ROOT}/apps/jetty/java/src/net/i2p/servlet/filters/XSSFilter.java" "${REPO_ROOT}/apps/jetty/java/src/net/i2p/jetty/JettyXmlConfigurationParser.java" "${REPO_ROOT}/apps/jetty/java/src/net/i2p/jetty/I2PRequestLog.java" "${REPO_ROOT}/apps/jetty/java/src/net/i2p/jetty/I2PLogger.java" "${REPO_ROOT}/apps/jetty/java/src/net/i2p/jetty/JettyStart.java" 2>&1
  local src_d="$out"
  (cd "$src_d" && jar cf jetty-i2p.jar net/)
  # Copy needed jetty lib jars to build dir for other modules
  for j in jetty-util jetty-http jetty-io jetty-security jetty-servlet jetty-servlets jetty-webapp jetty-xml jetty-deploy org.mortbay.jetty; do
    cp "${jettylib}/${j}.jar" "$out/" 2>/dev/null || true
  done
}

# Build desktopgui.jar (needs wrapper.jar from installer libs)
build_desktopgui() {
  local build_root="${TMPDIR:-/tmp}/build-i2p"
  local out="${build_root}/apps/desktopgui/build"
  local dist="${build_root}/apps/desktopgui/dist"
  mkdir -p "$out" "$dist"
  cd "${REPO_ROOT}/apps/desktopgui" && ant -Dbuild.dir="$out" -Ddist="$dist" -Dcore.java.build.dir="${build_root}/core/java/build" -Drouter.java.build.dir="${build_root}/router/java/build" -Dsystray.java.build.dir="${build_root}/apps/systray/java/build" -Dwrapper.dir="${REPO_ROOT}/installer/lib/wrapper/all" -Dhamcrest.home="${HAMCREST}" -Djunit.home="${JUNIT}" -Dmockito.home="${MOCKITO}" -Dscalatest.libs="${SCALA}" -Djunit.reports.dir="${RESULTS_DIR}" jar -logfile /tmp/i2p_build_desktopgui.log > /dev/null 2>&1
  rm -f /tmp/i2p_build_desktopgui.log
}

# Run ant test for a module in the background.
# Writes "tests failures errors" to result_file on completion.
run_test_bg() {
  local dir="$1"
  local result_file="$2"
  local logfile=$(mktemp /tmp/i2p_test_XXXXX.log)

  (
    local test_dir="${dir}/test"
    if [ "$dir" = "apps/addressbook" ]; then
      test_dir="${dir}/java/test"
    fi
    if [ ! -d "${REPO_ROOT}/${test_dir}" ]; then
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

  local test_dir="${dir}/test"
  if [ "$dir" = "apps/addressbook" ]; then
    test_dir="${dir}/java/test"
  fi
  if [ ! -d "${REPO_ROOT}/${test_dir}" ]; then
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

# Ant writes JUnit XML directly to RESULTS_DIR via junit.reports.dir property.
rm -rf "${RESULTS_DIR}"
mkdir -p "${RESULTS_DIR}"

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
  i2ptunnel)
    run_build core/java
    run_build core/java jarTest
    run_build apps/ministreaming/java
    run_build apps/i2ptunnel/java
    run_test apps/i2ptunnel/java "I2PTunnel"
    ;;
  addressbook)
    run_build core/java
    run_build core/java jarTest
    run_build apps/ministreaming/java
    run_build apps/streaming/java
    run_build apps/i2ptunnel/java
    run_build router/java
    run_build apps/addressbook
    run_test apps/addressbook "Addressbook"
    ;;
  routerconsole)
    run_build core/java
    run_build core/java jarTest
    run_build apps/ministreaming/java
    run_build apps/streaming/java
    run_build apps/i2ptunnel/java
    run_build apps/jrobin/java
    build_jetty
    run_build apps/systray/java
    run_build router/java
    build_desktopgui
    run_build apps/routerconsole/java
    run_test apps/routerconsole/java "RouterConsole"
    ;;
  i2psnark)
    run_build core/java
    run_build core/java jarTest
    run_build apps/ministreaming/java
    run_build apps/streaming/java
    run_build apps/jrobin/java
    build_jetty
    run_build apps/systray/java
    run_build router/java
    build_desktopgui
    run_build apps/routerconsole/java
    run_build apps/i2psnark/java
    run_test apps/i2psnark/java "I2PSnark"
    ;;
  susimail)
    run_build core/java
    run_build core/java jarTest
    build_jetty
    run_build apps/susimail
    run_test apps/susimail "Susimail"
    ;;
  sam)
    run_build core/java
    run_build core/java jarTest
    run_build apps/ministreaming/java
    run_build apps/streaming/java
    run_build apps/sam/java
    run_test apps/sam/java "SAM"
    ;;
  all|*)
    # Build all modules first (jar dependencies prevent parallelism)
    run_build core/java
    run_build core/java jarTest
    run_build apps/ministreaming/java
    run_build apps/streaming/java
    run_build apps/i2ptunnel/java
    run_build apps/jrobin/java
    build_jetty
    run_build apps/systray/java
    run_build router/java
    run_build router/java jarTest
    build_desktopgui
    run_build apps/addressbook
    run_build apps/routerconsole/java
    run_build apps/i2psnark/java
    run_build apps/susimail
    run_build apps/sam/java

    # Run all test suites in parallel (safe now that clean is removed from junit.test)
    core_r=$(mktemp)
    mini_r=$(mktemp)
    stream_r=$(mktemp)
    router_r=$(mktemp)
    addr_r=$(mktemp)
    i2pt_r=$(mktemp)
    rcons_r=$(mktemp)
    snark_r=$(mktemp)
    susi_r=$(mktemp)
    sam_r=$(mktemp)
    trap 'rm -f "$core_r" "$mini_r" "$stream_r" "$router_r" "$addr_r" "$i2pt_r" "$rcons_r" "$snark_r" "$susi_r" "$sam_r"; rm -rf "${REPO_ROOT}/reports"' EXIT

    run_test_bg core/java "$core_r"
    run_test_bg apps/ministreaming/java "$mini_r"
    run_test_bg apps/streaming/java "$stream_r"
    run_test_bg router/java "$router_r"
    run_test_bg apps/addressbook "$addr_r"
    run_test_bg apps/i2ptunnel/java "$i2pt_r"
    run_test_bg apps/routerconsole/java "$rcons_r"
    run_test_bg apps/i2psnark/java "$snark_r"
    run_test_bg apps/susimail "$susi_r"
    run_test_bg apps/sam/java "$sam_r"

    echo -e "${BOLD}Running test suites in parallel...${RESET}"
    wait

    # Print per-suite results and aggregate
    total_t=0; total_f=0; total_e=0
    for pair in "Core:$core_r" "MiniStreaming:$mini_r" "Streaming:$stream_r" "Router:$router_r" "Addressbook:$addr_r" "I2PTunnel:$i2pt_r" "RouterConsole:$rcons_r" "I2PSnark:$snark_r" "Susimail:$susi_r" "SAM:$sam_r"; do
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

# Tidy up any leftover workspace artifacts
rm -rf "${REPO_ROOT}/reports"
