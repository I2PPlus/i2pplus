#!/bin/bash
# SpotBugs runner with specific Java version

JAVA_VERSION=${1:-21}  # Default to Java 21
shift

case $JAVA_VERSION in
    21)
        JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
        ;;
    24)
        JAVA_HOME="/usr/lib/jvm/java-24-openjdk-amd64"
        ;;
    25)
        JAVA_HOME="/usr/lib/jvm/java-25-openjdk-amd64"
        ;;
    *)
        echo "Usage: $0 [21|24|25] [ant-options]"
        echo "Example: $0 21 spotbugs"
        exit 1
        ;;
esac

echo "Using Java $JAVA_VERSION from $JAVA_HOME"
JAVA_HOME="$JAVA_HOME" ant "$@"