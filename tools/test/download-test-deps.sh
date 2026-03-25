#!/bin/bash
#
# Download test dependencies (Hamcrest, JUnit, Mockito) for I2P+ unit tests.
# Checks current versions and downloads if missing or outdated.
#
# Usage: download-test-deps.sh [--force]
#
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FORCE=false

[ "$1" = "--force" ] && FORCE=true

# Versions
HAMCREST_VERSION="1.3"
JUNIT_VERSION="4.13.2"
MOCKITO_VERSION="4.11.0"

# Maven Central base URL
MAVEN_URL="https://repo1.maven.org/maven2"

download_if_needed() {
    local name="$1"
    local version="$2"
    local dir="${SCRIPT_DIR}/${name}"
    local version_file="${dir}/version.txt"
    local jars=("${@:3}")

    mkdir -p "$dir"

    local installed="none"
    if [ -f "$version_file" ]; then
        installed=$(cat "$version_file")
    fi

    if [ "$FORCE" = false ] && [ "$installed" = "$version" ]; then
        echo "${name} ${installed} is up to date."
        return 0
    fi

    if [ "$installed" != "none" ]; then
        echo "Updating ${name}: ${installed} -> ${version}"
    else
        echo "Downloading ${name} ${version}..."
    fi

    local jar
    for jar in "${jars[@]}"; do
        local url="${MAVEN_URL}/${jar}"
        local dest="${dir}/$(basename "$jar")"
        echo "  $(basename "$jar")"
        curl -sL -o "$dest" "$url"
    done

    echo -n "$version" > "$version_file"
    echo "${name} ${version} installed to ${dir}"
}

# Hamcrest
HAMCREST_JARS=(
    "org/hamcrest/hamcrest-core/${HAMCREST_VERSION}/hamcrest-core-${HAMCREST_VERSION}.jar"
    "org/hamcrest/hamcrest-library/${HAMCREST_VERSION}/hamcrest-library-${HAMCREST_VERSION}.jar"
    "org/hamcrest/hamcrest-integration/${HAMCREST_VERSION}/hamcrest-integration-${HAMCREST_VERSION}.jar"
    "org/hamcrest/hamcrest-all/${HAMCREST_VERSION}/hamcrest-all-${HAMCREST_VERSION}.jar"
)

# JUnit
JUNIT_JARS=(
    "junit/junit/${JUNIT_VERSION}/junit-${JUNIT_VERSION}.jar"
)

# Mockito
MOCKITO_JARS=(
    "org/mockito/mockito-core/${MOCKITO_VERSION}/mockito-core-${MOCKITO_VERSION}.jar"
    "net/bytebuddy/byte-buddy/1.14.18/byte-buddy-1.14.18.jar"
    "net/bytebuddy/byte-buddy-agent/1.14.18/byte-buddy-agent-1.14.18.jar"
    "org/objenesis/objenesis/3.3/objenesis-3.3.jar"
)

download_if_needed "hamcrest" "$HAMCREST_VERSION" "${HAMCREST_JARS[@]}"
download_if_needed "junit" "$JUNIT_VERSION" "${JUNIT_JARS[@]}"
download_if_needed "mockito" "$MOCKITO_VERSION" "${MOCKITO_JARS[@]}"

# Clean up version markers in jar names (hamcrest-1.3.jar -> hamcrest-core.jar)
# Rename downloaded jars to simple names expected by build.xml
rename_jars() {
    local dir="$1"
    shift
    local src dest
    for mapping in "$@"; do
        src="${dir}/${mapping%%=*}"
        dest="${dir}/${mapping##*=}"
        if [ -f "$src" ] && [ "$src" != "$dest" ]; then
            mv "$src" "$dest"
        fi
    done
}

rename_jars "${SCRIPT_DIR}/hamcrest" \
    "hamcrest-core-${HAMCREST_VERSION}.jar=hamcrest-core.jar" \
    "hamcrest-library-${HAMCREST_VERSION}.jar=hamcrest-library.jar" \
    "hamcrest-integration-${HAMCREST_VERSION}.jar=hamcrest-integration.jar" \
    "hamcrest-all-${HAMCREST_VERSION}.jar=hamcrest-all.jar"

rename_jars "${SCRIPT_DIR}/junit" \
    "junit-${JUNIT_VERSION}.jar=junit4.jar"

rename_jars "${SCRIPT_DIR}/mockito" \
    "mockito-core-${MOCKITO_VERSION}.jar=mockito-core.jar" \
    "byte-buddy-1.14.18.jar=byte-buddy.jar" \
    "byte-buddy-agent-1.14.18.jar=byte-buddy-agent.jar" \
    "objenesis-3.3.jar=objenesis.jar"

echo ""
echo "All test dependencies ready."
