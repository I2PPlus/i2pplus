#!/bin/bash
# SpotBugs runner for I2P+ with Java 21

cd /home/rogue/i2pplus

# Set Java 21 explicitly
JAVA_CMD="java"
SPOTBUGS_JAR="build/spotbugs-4.9.8/lib/spotbugs.jar"
SPOTBUGS_HOME="build/spotbugs-4.9.8"

# Default options
OUTPUT="dist/spotbugs-report.xml"
FORMAT="xml:withMessages"
EFFORT="default"
LEVEL="medium"

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -output)
            OUTPUT="$2"
            shift 2
            ;;
        -format)
            FORMAT="$2"
            shift 2
            ;;
        -effort)
            EFFORT="$2"
            shift 2
            ;;
        -level)
            LEVEL="$2"
            shift 2
            ;;
        -html)
            FORMAT="html:spotbugs.xsl"
            OUTPUT="${OUTPUT%.xml}.html"
            shift
            ;;
        *)
            FILES="$FILES $1"
            shift
            ;;
    esac
done

# Run SpotBugs
echo "Running SpotBugs with Java 21..."
echo "Output: $OUTPUT"
echo "Format: $FORMAT"
echo "Effort: $EFFORT"
echo "Level: $LEVEL"
echo "Files: $FILES"

$JAVA_CMD -jar "$SPOTBUGS_JAR" \
    -textui \
    -format:"$FORMAT" \
    -output "$OUTPUT" \
    -effort:"$EFFORT" \
    -$LEVEL \
    $FILES

echo "SpotBugs analysis complete: $OUTPUT"