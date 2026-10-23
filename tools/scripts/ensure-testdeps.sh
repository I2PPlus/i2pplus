#!/usr/bin/env bash
#
# Download or update the test libraries used by `ant test` (JUnit, Hamcrest,
# Mockito, Scala). Fetches from Maven Central into tools/test/{hamcrest,junit,
# mockito,scala} under the exact names the build files put on the classpath, and
# records the version in version.txt per group, so repeat runs are a no-op and
# the jars stay gitignored.
#
# The versions pinned below are the source of truth. Nothing already current is
# ever refetched: a group is fetched only when its pinned version is newer than
# the one recorded in version.txt, or when a cached jar is not a readable
# archive, and the new jar then replaces the old one in place. `ant test` runs
# the check every time, which costs a version comparison when the cache is good,
# so a fresh clone works and a stale or partial cache cannot survive into a build.
#
# Usage: ensure-testdeps.sh [--force] [--check] [--quiet]
#
#   --force    refetch every group even when the cache looks good
#   --check    report status without downloading
#   --quiet    suppress per-file output

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# This script lives in tools/scripts, so the repo root is two levels up. Going up
# only once makes TEST_DIR tools/tools/test, which silently downloads the jars
# where nothing looks for them: `ant test` then runs with -Djunit.home pointing at
# an empty tools/test/junit and every module reports zero tests.
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
FORCE=false
CHECK_ONLY=false
QUIET=false

case "${1:-}" in
  -h|--help)
    sed -n '2,/^$/s/^# \?//p' "$0"
    exit 0
    ;;
  --force) FORCE=true ;;
  --check) CHECK_ONLY=true ;;
  --quiet) QUIET=true ;;
esac

MAVEN_CENTRAL="https://repo1.maven.org/maven2"
TEST_DIR="${REPO_ROOT}/tools/test"

# Version pins per group
HAMCREST_VERSION="1.3"
JUNIT_VERSION="4.13.2"
MOCKITO_VERSION="2.28.2"
SCALA_VERSION="2.12.18"

# Only the artifacts the test classpaths name. Hamcrest is core plus library:
# junit needs core at runtime (org.hamcrest.Matcher, Description), and the tests
# that use org.hamcrest.Matchers need library. -integration is not imported
# anywhere, and -all is a superset of the two we already fetch.
DEPS=(
  # Hamcrest
  "hamcrest|${HAMCREST_VERSION}|org/hamcrest:hamcrest-core:${HAMCREST_VERSION}:hamcrest-core.jar"
  "hamcrest|${HAMCREST_VERSION}|org/hamcrest:hamcrest-library:${HAMCREST_VERSION}:hamcrest-library.jar"
  # JUnit
  "junit|${JUNIT_VERSION}|junit:junit:${JUNIT_VERSION}:junit4.jar"
  # Mockito
  "mockito|${MOCKITO_VERSION}|org/mockito:mockito-core:${MOCKITO_VERSION}:mockito-core.jar"
  "mockito|${MOCKITO_VERSION}|net/bytebuddy:byte-buddy:1.9.7:byte-buddy.jar"
  "mockito|${MOCKITO_VERSION}|net/bytebuddy:byte-buddy-agent:1.9.7:byte-buddy-agent.jar"
  "mockito|${MOCKITO_VERSION}|org/objenesis:objenesis:3.1:objenesis.jar"
  # Scala
  "scala|${SCALA_VERSION}|org/scala-lang:scala-compiler:${SCALA_VERSION}:scala-compiler.jar"
  "scala|${SCALA_VERSION}|org/scala-lang:scala-library:${SCALA_VERSION}:scala-library.jar"
  "scala|${SCALA_VERSION}|org/scala-lang:scala-reflect:${SCALA_VERSION}:scala-reflect.jar"
  "scala|${SCALA_VERSION}|org/scala-lang/modules:scala-xml_2.12:1.0.6:scala-xml.jar"
  "scala|${SCALA_VERSION}|org/scalactic:scalactic_2.12:3.0.1:scalactic.jar"
  "scala|${SCALA_VERSION}|org/scalatest:scalatest_2.12:3.0.1:scalatest.jar"
)

# A jar counts as present only if it is a readable archive. curl -o leaves
# whatever bytes it managed to write when a transfer dies, and ant silently
# ignores a classpath entry it cannot read, so a truncated jar reads as a green
# run with zero tests. Testing the archive rather than the filename makes the
# gitignored cache self-healing: a bad entry is simply fetched again.
jar_ok() {
  local f="$1"
  [ -s "$f" ] || return 1
  if command -v unzip > /dev/null 2>&1; then
    unzip -tqq "$f" > /dev/null 2>&1
  else
    # No unzip: fall back to the zip local-file-header magic.
    [ "$(head -c 2 "$f" 2>/dev/null | od -An -tx1 | tr -d ' \n')" = "504b" ]
  fi
}

download() {
  local group_path="$1" artifact="$2" version="$3" dest="$4"
  local url="${MAVEN_CENTRAL}/${group_path}/${artifact}/${version}/${artifact}-${version}.jar"
  local tmp="${dest}.part"
  # Fetch aside and only then move it into place, so replacing a prior version
  # can never leave a half-written jar where a usable one was.
  if curl -sfL -o "$tmp" "$url" && jar_ok "$tmp"; then
    mv -f "$tmp" "$dest"
    return 0
  fi
  rm -f "$tmp"
  return 1
}

get_installed_version() {
  local version_file="$1"
  if [ -f "$version_file" ]; then
    cat "$version_file"
  else
    echo "none"
  fi
}

missing=0
downloaded=0
stale_groups=()

# Check version files first
declare -A group_versions
for dep in "${DEPS[@]}"; do
  subdir="${dep%%|*}"
  rest="${dep#*|}"
  version="${rest%%|*}"
  group_versions["$subdir"]="$version"
done

for group in "${!group_versions[@]}"; do
  version_file="${TEST_DIR}/${group}/version.txt"
  expected="${group_versions[$group]}"
  installed=$(get_installed_version "$version_file")

  if [ "$CHECK_ONLY" = true ]; then
    if [ "$installed" != "$expected" ]; then
      if [ "$QUIET" = false ]; then echo "  STALE: ${group} (${installed} -> ${expected})"; fi
      missing=$((missing + 1))
    fi
    continue
  fi

  if [ "$installed" != "$expected" ] || [ "$FORCE" = true ]; then
    stale_groups+=("$group")
  fi
done

if [ "$CHECK_ONLY" = true ]; then
  # Also check individual jars exist
  for dep in "${DEPS[@]}"; do
    subdir="${dep%%|*}"
    rest="${dep#*|}"
    rest="${rest#*|}"
    IFS=: read -r group_path artifact version dest_name <<< "$rest"
    dest="${TEST_DIR}/${subdir}/${dest_name}"
    if ! jar_ok "$dest"; then
      if [ -f "$dest" ]; then kind=CORRUPT; else kind=MISSING; fi
      if [ "$QUIET" = false ]; then echo "  ${kind}: ${subdir}/${dest_name}"; fi
      missing=$((missing + 1))
    fi
  done

  if [ "$missing" -gt 0 ]; then
    echo "---"
    echo "FAIL: $missing issue(s). Run $0 to download."
    exit 1
  else
    if [ "$QUIET" = false ]; then echo "OK: all test dependencies present and up to date"; fi
    exit 0
  fi
fi

# Determine which groups need downloading
declare -A needs_download
for group in "${stale_groups[@]}"; do
  needs_download["$group"]=true
done

# Mark any group holding an unusable jar for download, so a truncated or empty
# entry in the cache is refetched rather than trusted.
for dep in "${DEPS[@]}"; do
  subdir="${dep%%|*}"
  rest="${dep#*|}"
  rest="${rest#*|}"
  IFS=: read -r group_path artifact version dest_name <<< "$rest"
  dest="${TEST_DIR}/${subdir}/${dest_name}"
  if ! jar_ok "$dest"; then
    needs_download["$subdir"]=true
  fi
done

# Download stale/missing groups
for dep in "${DEPS[@]}"; do
  subdir="${dep%%|*}"
  rest="${dep#*|}"
  group_version="${rest%%|*}"
  rest="${rest#*|}"
  IFS=: read -r group_path artifact version dest_name <<< "$rest"
  dest_dir="${TEST_DIR}/${subdir}"
  dest="${dest_dir}/${dest_name}"

  [ -z "${needs_download[$subdir]+x}" ] && continue

  mkdir -p "$dest_dir"

  if [ "$QUIET" = false ]; then echo "  ${artifact}-${version}.jar"; fi
  if download "$group_path" "$artifact" "$version" "$dest"; then
    downloaded=$((downloaded + 1))
  else
    echo "  FAIL: ${artifact}-${version}.jar"
    missing=$((missing + 1))
  fi
done

# Update version.txt for downloaded groups
for group in "${!needs_download[@]}"; do
  version="${group_versions[$group]}"
  echo -n "$version" > "${TEST_DIR}/${group}/version.txt"
done

if [ "$missing" -gt 0 ]; then
  echo "---"
  echo "FAIL: $missing download failure(s)"
  exit 1
fi

if [ "$QUIET" = false ] && [ "$downloaded" -gt 0 ]; then
  echo "---"
  echo "Downloaded $downloaded jar(s) to tools/test/"
fi
