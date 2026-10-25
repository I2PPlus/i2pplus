#!/usr/bin/env bash
#
# Wrapper for `ant test`. Ensures test dependencies are present,
# then runs ant with the correct classpath properties.
#
# Usage:
#   tools/scripts/run-tests.sh                  Run all tests (via ant test)
#   tools/scripts/run-tests.sh core             Run core tests only
#   tools/scripts/run-tests.sh router           Run router tests only
#   tools/scripts/run-tests.sh --logs all       Keep ant's console output and
#                                               leave every per-module logfile
#                                               behind (what CI should use)
#   tools/scripts/run-tests.sh --logs-dir DIR   Where those logfiles go
#                                               (default: /tmp/test-i2p/logs)
#
# A flag may appear anywhere; the first non-flag argument selects the suite.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

HAMCREST="${REPO_ROOT}/tools/test/hamcrest"
JUNIT="${REPO_ROOT}/tools/test/junit"
MOCKITO="${REPO_ROOT}/tools/test/mockito"
SCALA="${REPO_ROOT}/tools/test/scala"

# Always check the test libraries. A pin that moved to a newer version, or a
# cached jar that is not a readable archive, is refetched and replaces what is
# there; a current cache is a no-op that touches no network. Nothing already
# current is refetched.
"${SCRIPT_DIR}/ensure-testdeps.sh" --quiet || true

# Diagnostic only, and it stays that way: ant ignores a <pathelement> whose jar
# does not exist, so a missing junit4.jar means the junit task has no classes,
# every module reports zero tests, and the run still exits 0. Say so, loudly,
# without changing what the script goes on to do.
if ! deps_out="$("${SCRIPT_DIR}/ensure-testdeps.sh" --check 2>&1)"; then
  echo "WARNING: test dependencies are not all present:" >&2
  printf '%s\n' "$deps_out" >&2
  echo "Without them ant runs zero tests and still reports success." >&2
fi

# Ensure the python dep for HTML report generation is present. Kept here so
# the report works regardless of when the calling CI step runs relative to
# other steps that may install python packages.
if ! python3 -c 'import markdown_it' >/dev/null 2>&1; then
  echo "Installing markdown-it-py for test report generation..."
  if ! python3 -m pip install --quiet --user markdown-it-py 2>/dev/null \
     && ! python3 -m pip install --quiet --break-system-packages markdown-it-py 2>/dev/null; then
    echo "WARNING: could not install markdown-it-py; the HTML test report will not be generated" >&2
  fi
fi

RESULTS_DIR="/tmp/test-i2p"
ANT_PROPS="-Dhamcrest.home=${HAMCREST} -Djunit.home=${JUNIT} -Dmockito.home=${MOCKITO} -Dscalatest.libs=${SCALA} -Djunit.reports.dir=${RESULTS_DIR}"

# --- logging override -------------------------------------------------------
# Every ant call in this script is quiet by default: its console output goes to
# /dev/null and its -logfile is deleted afterwards. That is fine when a test
# fails, because the JUnit XML says so, and useless when a module fails to
# BUILD, which is indistinguishable from a module with no tests at all - the
# tally just reads 0 and nothing explains why. --logs keeps ant's own output on
# stdout and leaves each per-module logfile in ${LOG_DIR} for CI to show.
LOGS=false
LOG_DIR="${RESULTS_DIR}/logs"
SUITE="all"
while [ $# -gt 0 ]; do
  case "$1" in
    -l|--logs) LOGS=true; shift ;;
    --logs-dir) LOGS=true; LOG_DIR="${2:?--logs-dir needs a directory}"; shift 2 ;;
    -h|--help) sed -n '2,/^$/s/^# \?//p' "$0"; exit 0 ;;
    *) SUITE="$1"; shift ;;
  esac
done
# Resolve to an absolute path: each ant call runs with the module directory as its
# working directory, and ant resolves -logfile against that, so a relative
# --logs-dir would be written beside the module instead of where it was asked for.
case "$LOG_DIR" in
  /*) ;;
  *) LOG_DIR="${REPO_ROOT}/${LOG_DIR}" ;;
esac

BOLD='\033[1m'
RESET='\033[0m'
RED='\033[0;31m'
GREEN='\033[0;32m'

# One ant invocation, from the module's directory, with the shared properties.
# Quiet unless --logs. Either way a failed run keeps its logfile and echoes its
# tail, so a build failure is never silent. ANT_STREAM=false (set by the
# background test runner) keeps the console copy out of the terminal so parallel
# modules do not interleave; -logfile holds it either way.
ant_run() {
  local dir="$1" log="$2"; shift 2
  local rc=0
  # Created here, not at startup: the suite clears RESULTS_DIR part-way through.
  mkdir -p "$(dirname "$log")"
  if [ "$LOGS" = true ] && [ "${ANT_STREAM:-true}" = true ]; then
    (cd "${REPO_ROOT}/${dir}" && ant "$@" -logfile "$log") || rc=$?
  else
    (cd "${REPO_ROOT}/${dir}" && ant "$@" -logfile "$log" > /dev/null 2>&1) || rc=$?
  fi
  if [ "$rc" -ne 0 ]; then
    echo -e "  ${RED}ant failed -> exit ${rc}${RESET} in ${dir}" >&2
    if [ -s "$log" ]; then
      echo "  Last lines of ${log}:" >&2
      tail -n 20 "$log" | sed 's/^/  | /' >&2
    fi
    echo "  Full log: ${log}" >&2
  elif [ "$LOGS" = false ]; then
    rm -f "$log"
  fi
  return $rc
}

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
    apps/pack200/java) echo "pack200" ;;
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
      props="$props -Dcore.java.build.dir=${build_root}/core/java/build -Drouter.java.build.dir=${build_root}/router/java/build -Dministreaming.java.build.dir=${build_root}/apps/ministreaming/java/build -Dstreaming.java.build.dir=${build_root}/apps/streaming/java/build -Di2ptunnel.java.build.dir=${build_root}/apps/i2ptunnel/java/build -Djetty.pkg.dir=${build_root}/apps/jetty/build -Djrobin.java.build.dir=${build_root}/apps/jrobin/java/build -Dsystray.java.build.dir=${build_root}/apps/systray/java/build -Dsam.java.build.dir=${build_root}/apps/sam/java/build -Ddesktopgui.dist.dir=${build_root}/apps/desktopgui/dist -Ddesktopgui.build.dir=${build_root}/apps/desktopgui/build -Dwrapper.dir=${REPO_ROOT}/installer/lib/wrapper/all"
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

get_test_target() {
  # i2ptunnel has no junit.test target; its `test` target is the junit suite
  if [ "$1" = "apps/i2ptunnel/java" ]; then
    echo "test"
  else
    echo "junit.test"
  fi
}

run_build() {
  local dir="$1" target="${2:-jar}"
  # The target is in the log name because some modules are built twice with
  # different targets (core/java jar jarTest); one name would lose the first.
  ant_run "$dir" "${LOG_DIR}/${dir//\//_}-${target}-build.log" $ANT_PROPS $(get_build_props "$dir") "$target"
}

# Build jetty-i2p.jar without invoking jetty's ant build (which tries to download distro).
# Compiles java/src against the jars in jettylib/.
build_jetty() {
  local build_root="${TMPDIR:-/tmp}/build-i2p"
  local out="${build_root}/apps/jetty/build"
  local jettylib="${REPO_ROOT}/apps/jetty/jettylib"
  local cp="${jettylib}/javax.servlet.jar:${jettylib}/jetty-util.jar:${jettylib}/jetty-http.jar:${jettylib}/jetty-io.jar:${jettylib}/jetty-security.jar:${jettylib}/jetty-servlet.jar:${jettylib}/jetty-webapp.jar:${jettylib}/jetty-xml.jar:${jettylib}/org.mortbay.jetty.jar:${jettylib}/commons-logging.jar:${jettylib}/jasper-runtime.jar:${jettylib}/tomcat-api.jar:${jettylib}/jsp-api.jar:${jettylib}/jetty-deploy.jar:${build_root}/core/java/build/i2p.jar"
  mkdir -p "$out"
  # jettylib/ is not checked in - it is generated from the checked-in
  # jetty-distribution and apache-tomcat trees - so a fresh clone has none of it
  # and the javac below would compile against classpath entries that do not
  # exist. Assemble it the canonical way first; that target only copies and
  # renames from the trees in the repo, so it needs no network.
  if [ ! -f "${jettylib}/javax.servlet.jar" ] || [ ! -f "${jettylib}/jetty-http.jar" ]; then
    echo "  jettylib/ absent -> assembling it from the checked-in distributions"
    ant_run "" "${LOG_DIR}/root-buildJetty.log" -Dbuild.root="${build_root}/" buildJetty || return 1
  fi
  # jetty-util.jar is not shipped in jettylib (the ant build assembles it into
  # the pkg dir); materialize the checked-in distribution jar so the classpath
  # and the copy loop below resolve it like the other jetty libs.
  local distro="${REPO_ROOT}/apps/jetty/jetty-distribution-9.3.30.v20211001"
  if [ ! -f "${jettylib}/jetty-util.jar" ] && [ -f "${distro}/lib/jetty-util-9.3.30.v20211001.jar" ]; then
    cp "${distro}/lib/jetty-util-9.3.30.v20211001.jar" "${jettylib}/jetty-util.jar"
  fi
  # Pick a javac release flag this compiler actually understands. `--release`
  # (JDK 9+) pins the bootclasspath to the target platform; on JDK 8 only
  # -source/-target are available. Probing beats assuming a JDK version, and a
  # compiler that understands neither must fail here rather than at javac.
  # Probe with -help, not --help: JDK 8's javac rejects --help outright ("invalid
  # flag"), which leaves nothing to grep and reads as a compiler with no flags;
  # -help is accepted by every javac from 8 to 25.
  local javac_help
  javac_help=$(javac -help 2>&1 || true)
  if printf '%s\n' "$javac_help" | grep -q -- '--release'; then
    JAVAC_RELEASE="--release 8"
  elif printf '%s\n' "$javac_help" | grep -q -- '-source'; then
    JAVAC_RELEASE="-source 8 -target 8"
  else
    JAVAC_RELEASE=""
  fi
  if [ -z "${JAVAC_RELEASE:-}" ]; then
    echo "ERROR: no compatible javac release flag found" >&2
    return 1
  fi
  javac $JAVAC_RELEASE -Xlint:-options -cp "$cp" -d "$out" "${REPO_ROOT}/apps/jetty/java/src/net/i2p/servlet/util/ServletUtil.java" "${REPO_ROOT}/apps/jetty/java/src/net/i2p/servlet/util/WriterOutputStream.java" "${REPO_ROOT}/apps/jetty/java/src/net/i2p/servlet/util/JspC.java" "${REPO_ROOT}/apps/jetty/java/src/net/i2p/servlet/I2PDefaultServlet.java" "${REPO_ROOT}/apps/jetty/java/src/net/i2p/servlet/WebAppProviderConfiguration.java" "${REPO_ROOT}/apps/jetty/java/src/net/i2p/servlet/RequestWrapper.java" "${REPO_ROOT}/apps/jetty/java/src/net/i2p/servlet/ErrorServlet.java" "${REPO_ROOT}/apps/jetty/java/src/net/i2p/servlet/filters/XSSRequestWrapper.java" "${REPO_ROOT}/apps/jetty/java/src/net/i2p/servlet/filters/XI2PLocationFilter.java" "${REPO_ROOT}/apps/jetty/java/src/net/i2p/servlet/filters/XSSFilter.java" "${REPO_ROOT}/apps/jetty/java/src/net/i2p/jetty/JettyXmlConfigurationParser.java" "${REPO_ROOT}/apps/jetty/java/src/net/i2p/jetty/I2PRequestLog.java" "${REPO_ROOT}/apps/jetty/java/src/net/i2p/jetty/I2PLogger.java" "${REPO_ROOT}/apps/jetty/java/src/net/i2p/jetty/JettyStart.java" 2>&1
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
  ant_run apps/desktopgui "${LOG_DIR}/apps_desktopgui-build.log" \
    -Dbuild.dir="$out" -Ddist="$dist" \
    -Dcore.java.build.dir="${build_root}/core/java/build" \
    -Drouter.java.build.dir="${build_root}/router/java/build" \
    -Dsystray.java.build.dir="${build_root}/apps/systray/java/build" \
    -Dwrapper.dir="${REPO_ROOT}/installer/lib/wrapper/all" \
    -Dhamcrest.home="${HAMCREST}" -Djunit.home="${JUNIT}" \
    -Dmockito.home="${MOCKITO}" -Dscalatest.libs="${SCALA}" \
    -Djunit.reports.dir="${RESULTS_DIR}" jar
}

# Run ant test for a module in the background.
# Writes "tests failures errors" to result_file on completion.
run_test_bg() {
  local dir="$1"
  local result_file="$2"
  local logfile="${LOG_DIR}/${dir//\//_}-test.log"

  (
    local test_dir="${dir}/test"
    if [ "$dir" = "apps/addressbook" ]; then
      test_dir="${dir}/java/test"
    elif [ "$dir" = "apps/susimail" ]; then
      test_dir="${dir}/src/test"
    fi
    if [ ! -d "${REPO_ROOT}/${test_dir}" ]; then
      echo "0 0 0" > "$result_file"
      exit 0
    fi

    # Parallel modules must not interleave on the console; -logfile has the
    # whole run either way.
    ANT_STREAM=false
    ant_run "$dir" "$logfile" $ANT_PROPS $(get_build_props "$dir") $(get_test_target "$dir") || true

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
  ) &
}

run_test() {
  local dir="$1"
  local label="$2"

  local test_dir="${dir}/test"
  if [ "$dir" = "apps/addressbook" ]; then
    test_dir="${dir}/java/test"
  elif [ "$dir" = "apps/susimail" ]; then
    test_dir="${dir}/src/test"
  fi
  if [ ! -d "${REPO_ROOT}/${test_dir}" ]; then
    echo -e "${BOLD}${label}${RESET}"
    echo "  (no tests)"
    return 0
  fi

  echo -e "${BOLD}${label}${RESET}"
  ant_run "$dir" "${LOG_DIR}/${dir//\//_}-test.log" $ANT_PROPS $(get_build_props "$dir") $(get_test_target "$dir") || true

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

# Fail the run if a test wrote router runtime state into the source tree.
# Every junit target passes i2p.dir.base at the build root, so one of these
# names appearing under the workspace means something built an I2PAppContext
# that ignored them - and a stray netDb/ or autotune.config is then one
# `git add -A` away from being committed. The prunes are not test output: the
# installer's sample data is tracked, and a logs/ under a vendored tool
# distribution belongs to that tool. ant's distclean removes them all
# (cleanTestArtifacts); keep the two name sets in step.
check_no_runtime_artifacts() {
  local found
  found=$(find "$REPO_ROOT" -maxdepth 5 \
            \( -name .git -o -name dist -o -name node_modules -o -name sonarqube \
               -o -name 'jetty-distribution-*' -o -path "${REPO_ROOT}/installer/resources" \) -prune -o \
            \( -name autotune.config -o -name sessionbans -o -name peerProfiles \
               -o -name netDb -o -name logs -o -name dht.dat -o -name '*.config.d' \
               -o -name test-i2psnark-lookup \) -print 2>/dev/null |
            sed "s|^${REPO_ROOT}/||" || true)
  if [ -n "$found" ]; then
    echo "ERROR: a test left runtime state in the source tree:" >&2
    while IFS= read -r artifact; do
      echo "  ${artifact}" >&2
    done <<< "$found"
    echo "Something built an I2PAppContext without i2p.dir.* set - point it at" >&2
    echo "the build root, as RouterTestHelper.dataDir() does." >&2
    exit 1
  fi
}

# --- Main ---

# Ant writes JUnit XML directly to RESULTS_DIR via junit.reports.dir property.
rm -rf "${RESULTS_DIR}"
mkdir -p "${RESULTS_DIR}"

case "$SUITE" in
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
    run_build apps/streaming/java
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
  pack200)
    run_build apps/pack200/java
    run_test apps/pack200/java "Pack200"
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
    run_build apps/sam/java
    run_build apps/addressbook
    run_build apps/routerconsole/java
    run_build apps/i2psnark/java
    run_build apps/susimail

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
    p200_r=$(mktemp)
    jrob_r=$(mktemp)
    trap 'rm -f "$core_r" "$mini_r" "$stream_r" "$router_r" "$addr_r" "$i2pt_r" "$rcons_r" "$snark_r" "$susi_r" "$sam_r" "$p200_r" "$jrob_r"; rm -rf "${REPO_ROOT}/reports"' EXIT

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
    run_test_bg apps/pack200/java "$p200_r"
    run_test_bg apps/jrobin/java "$jrob_r"

    echo -e "${BOLD}Running test suites in parallel...${RESET}"
    wait

    # Print per-suite results and aggregate
    total_t=0; total_f=0; total_e=0
    for pair in "Core:$core_r" "MiniStreaming:$mini_r" "Streaming:$stream_r" "Router:$router_r" "Addressbook:$addr_r" "I2PTunnel:$i2pt_r" "RouterConsole:$rcons_r" "I2PSnark:$snark_r" "Susimail:$susi_r" "SAM:$sam_r" "Pack200:$p200_r" "JRobin:$jrob_r"; do
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

# Nothing a test writes may stay in the source tree - check before the report
# step, so a red suite still reports where it leaked.
check_no_runtime_artifacts

# Generate consolidated HTML report only for full suite runs
if [ "$SUITE" = "all" ] && [ -d "${RESULTS_DIR}" ]; then
  if ! python3 "${REPO_ROOT}/tools/test/unit-tests-to-html.py" "${RESULTS_DIR}" "${REPO_ROOT}/dist/test-report.html"; then
    echo "ERROR: report generation failed -> test results are still in ${RESULTS_DIR}" >&2
    exit 1
  fi
fi

if [ "$LOGS" = true ]; then
  echo "Ant logs kept in ${LOG_DIR}"
fi

# Tidy up any leftover workspace artifacts
rm -rf "${REPO_ROOT}/reports"
