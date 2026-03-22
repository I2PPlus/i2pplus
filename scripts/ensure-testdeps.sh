#!/usr/bin/env bash
#
# Download test dependencies (JUnit, Hamcrest, Mockito, Scala) for `ant test`.
# Downloads from Maven Central to tools/test/{hamcrest,junit,mockito,scala}.
#
# Usage:
#   ensure-testdeps.sh              Download if missing
#   ensure-testdeps.sh --force      Re-download even if present
#   ensure-testdeps.sh --check      Report status without downloading
#   ensure-testdeps.sh --quiet      Suppress per-file output

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
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

DEPS=(
  # Hamcrest
  "hamcrest|org/hamcrest:hamcrest-core:1.3:hamcrest-core.jar"
  "hamcrest|org/hamcrest:hamcrest-library:1.3:hamcrest-library.jar"
  "hamcrest|org/hamcrest:hamcrest-integration:1.3:hamcrest-integration.jar"
  "hamcrest|org/hamcrest:hamcrest-all:1.3:hamcrest-all.jar"
  # JUnit
  "junit|junit:junit:4.13.2:junit4.jar"
  # Mockito
  "mockito|org/mockito:mockito-core:2.28.2:mockito-core.jar"
  "mockito|net/bytebuddy:byte-buddy:1.9.7:byte-buddy.jar"
  "mockito|net/bytebuddy:byte-buddy-agent:1.9.7:byte-buddy-agent.jar"
  "mockito|org/objenesis:objenesis:3.1:objenesis.jar"
  # Scala
  "scala|org/scala-lang:scala-compiler:2.12.18:scala-compiler.jar"
  "scala|org/scala-lang:scala-library:2.12.18:scala-library.jar"
  "scala|org/scala-lang:scala-reflect:2.12.18:scala-reflect.jar"
  "scala|org/scala-lang/modules:scala-xml_2.12:1.0.6:scala-xml.jar"
  "scala|org/scalactic:scalactic_2.12:3.0.1:scalactic.jar"
  "scala|org/scalatest:scalatest_2.12:3.0.1:scalatest.jar"
)

download() {
  local group_path="$1" artifact="$2" version="$3" dest="$4"
  local url="${MAVEN_CENTRAL}/${group_path}/${artifact}/${version}/${artifact}-${version}.jar"
  curl -sfL -o "$dest" "$url"
}

missing=0
downloaded=0

for dep in "${DEPS[@]}"; do
  subdir="${dep%%|*}"
  rest="${dep#*|}"
  IFS=: read -r group_path artifact version dest_name <<< "$rest"
  dest_dir="${TEST_DIR}/${subdir}"
  dest="${dest_dir}/${dest_name}"

  if [ "$CHECK_ONLY" = true ]; then
    if [ ! -f "$dest" ]; then
      if [ "$QUIET" = false ]; then echo "  MISSING: ${subdir}/${dest_name}"; fi
      missing=$((missing + 1))
    fi
    continue
  fi

  if [ -f "$dest" ] && [ "$FORCE" = false ]; then
    continue
  fi

  mkdir -p "$dest_dir"

  if [ "$QUIET" = false ]; then echo "  ${artifact}-${version}.jar"; fi
  if download "$group_path" "$artifact" "$version" "$dest"; then
    downloaded=$((downloaded + 1))
  else
    echo "  FAIL: ${artifact}-${version}.jar"
    missing=$((missing + 1))
  fi
done

if [ "$CHECK_ONLY" = true ]; then
  if [ "$missing" -gt 0 ]; then
    echo "---"
    echo "FAIL: $missing missing dependency(ies). Run $0 to download."
    exit 1
  else
    if [ "$QUIET" = false ]; then echo "OK: all test dependencies present"; fi
    exit 0
  fi
fi

if [ "$missing" -gt 0 ]; then
  echo "---"
  echo "FAIL: $missing download failure(s)"
  exit 1
fi

if [ "$QUIET" = false ] && [ "$downloaded" -gt 0 ]; then
  echo "---"
  echo "Downloaded $downloaded jar(s) to tools/test/"
fi
