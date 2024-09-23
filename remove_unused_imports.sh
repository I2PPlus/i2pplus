#!/bin/sh

# Set the custom Java executable path or use the system default if unset
CUSTOM_JAVA_PATH="/usr/lib/jvm/java-22-openjdk-amd64/bin/java"

# Check if the custom Java path is set, otherwise use the system default
if [ -n "$CUSTOM_JAVA_PATH" ]; then
  JAVA_EXECUTABLE="$CUSTOM_JAVA_PATH"
else
  JAVA_EXECUTABLE="/usr/bin/java"
fi

# Check if google-java-format.jar exists in the script directory
if [ ! -f "./google-java-format.jar" ]; then
  echo " ! Error: google-java-format.jar not found in the script directory."
  echo " > Download the latest version with all dependencies from: https://github.com/google/google-java-format/releases/"
  exit 1
fi

if [ $# -ne 1 ]; then
  echo "remove_unused_imports.sh"
  echo "------------------------"
  echo "  Remove unused imports from .java class files"
  echo "  Usage: $0 {path}"
  echo ""
  exit 1
fi

directory=$1
removed_imports=0

find "$directory" -type f \( ! -name "*.class" \) \( ! -name "Strings.java" \) -name "*.java" -print0 | xargs -0 "$JAVA_EXECUTABLE" -jar ./google-java-format.jar --replace --fix-imports-only --skip-sorting-imports | grep -c "^import"

echo " > Number of removed imports: $removed_imports"
