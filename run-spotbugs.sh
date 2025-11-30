#!/bin/bash
# SpotBugs runner for I2P+ with Java 21

cd /home/rogue/i2pplus

# Set Java 21 explicitly
JAVA_CMD="java"
SPOTBUGS_VERSION="4.9.8"
SPOTBUGS_DIR="spotbugs/spotbugs-${SPOTBUGS_VERSION}"
SPOTBUGS_JAR="${SPOTBUGS_DIR}/lib/spotbugs.jar"
SPOTBUGS_HOME="${SPOTBUGS_DIR}"
SPOTBUGS_SCRIPT="${SPOTBUGS_DIR}/bin/spotbugs"
SPOTBUGS_TGZ="spotbugs/spotbugs-${SPOTBUGS_VERSION}.tgz"

# Default options (matching ant task)
OUTPUT="dist/spotbugs.xml"
FORMAT="xml:withMessages"
EFFORT="default"
LEVEL="medium"

# Parse command line arguments
# Handle help first
if [[ "$1" == "-h" || "$1" == "--help" ]]; then
    echo "Usage: $0 [OPTIONS] [FILES]"
    echo "Options:"
    echo "  -o, --output FILE     Output file (default: $OUTPUT)"
    echo "  -f, --format FORMAT   Output format (default: $FORMAT)"
    echo "  -e, --effort EFFORT   Analysis effort (default: $EFFORT)"
    echo "  -l, --level LEVEL     Minimum bug priority (default: $LEVEL)"
    echo "  -h, --help            Show this help message"
    echo ""
    echo "Files: JAR/WAR files to analyze (default: all build artifacts)"
    exit 0
fi

while [[ $# -gt 0 ]]; do
    case $1 in
        -o|--output)
            OUTPUT="$2"
            shift 2
            ;;
        -f|--format)
            FORMAT="$2"
            shift 2
            ;;
        -e|--effort)
            EFFORT="$2"
            shift 2
            ;;
        -l|--level)
            LEVEL="$2"
            shift 2
            ;;
        -*)
            echo "Unknown option: $1" >&2
            exit 1
            ;;
        *)
            FILES="$FILES $1"
            shift
            ;;
    esac
done

# Check if SpotBugs is available, download if needed
if [ ! -f "$SPOTBUGS_SCRIPT" ]; then
    if [ ! -f "$SPOTBUGS_TGZ" ]; then
        echo "Downloading SpotBugs ${SPOTBUGS_VERSION}..."
        mkdir -p spotbugs
        wget -O "$SPOTBUGS_TGZ" "https://github.com/spotbugs/spotbugs/releases/download/${SPOTBUGS_VERSION}/spotbugs-${SPOTBUGS_VERSION}.tgz"
    else
        echo "SpotBugs ${SPOTBUGS_VERSION} archive already found at ${SPOTBUGS_TGZ}"
    fi
    
    echo "Extracting SpotBugs ${SPOTBUGS_VERSION}..."
    mkdir -p "$SPOTBUGS_DIR"
    tar -xzf "$SPOTBUGS_TGZ" -C spotbugs/
    echo "SpotBugs ${SPOTBUGS_VERSION} extracted to ${SPOTBUGS_DIR}"
else
    echo "SpotBugs ${SPOTBUGS_VERSION} already found at ${SPOTBUGS_DIR}"
fi

# Build classpath matching ant task
AUX_CLASSPATH="build/commons-el.jar:build/commons-logging.jar:build/jasper-compiler.jar:build/jasper-runtime.jar:build/jetty-continuation.jar:build/jetty-deploy.jar:build/jetty-http.jar:build/jetty-i2p.jar:build/jetty-io.jar:build/jetty-java5-threadpool.jar:build/jetty-rewrite-handler.jar:build/jetty-security.jar:build/jetty-servlet.jar:build/jetty-servlets.jar:build/jetty-sslengine.jar:build/jetty-start.jar:build/jetty-util.jar:build/jetty-webapp.jar:build/jetty-xml.jar:build/javax.servlet.jar:build/jo.jsonrpc.war:build/jrobin.jar:build/org.mortbay.jetty.jar:build/org.mortbay.jmx.jar:build/pack200.jar:build/systray.jar:build/tools.jar"

# Default files to analyze (matching ant task)
if [ -z "$FILES" ]; then
    FILES="build/addressbook.jar build/addressbook.war build/desktopgui.jar build/i2p.jar build/i2psnark.jar build/i2psnark.war build/i2ptunnel.jar build/i2ptunnel.war build/imagegen.war build/mstreaming.jar build/router.jar build/routerconsole.jar build/routerconsole.war build/sam.jar build/susidns.war build/susimail.war build/streaming.jar build/utility.jar"
fi

# Check if build artifacts exist
MISSING_FILES=""
for file in $FILES; do
    if [ ! -f "$file" ]; then
        MISSING_FILES="$MISSING_FILES $file"
    fi
done

if [ -n "$MISSING_FILES" ]; then
    echo "Error: Build artifacts not found. Please run the build first:"
    echo "Missing files:$MISSING_FILES"
    echo ""
    echo "Run: ant jar build2 buildUtilityJar"
    exit 1
fi

# Run SpotBugs
echo "Running SpotBugs with Java 21..."
echo "Output: $OUTPUT"
echo "Format: $FORMAT"
echo "Effort: $EFFORT"
echo "Level: $LEVEL"
echo "Files: $FILES"

# Use SpotBugs script if available, otherwise try jar
if [ -f "$SPOTBUGS_SCRIPT" ]; then
    "$SPOTBUGS_SCRIPT" \
        -textui \
        -progress \
        -xml:withMessages \
        -output "$OUTPUT" \
        -effort:"$EFFORT" \
        -$LEVEL \
        -auxclasspath "$AUX_CLASSPATH" \
        -sourcepath "./apps:./router:./core" \
        $FILES
elif [ -f "$SPOTBUGS_JAR" ]; then
    $JAVA_CMD -cp "$SPOTBUGS_JAR" \
        edu.umd.cs.findbugs.FindBugs2 \
        -progress \
        -xml:withMessages \
        -output "$OUTPUT" \
        -effort:"$EFFORT" \
        -$LEVEL \
        -auxclasspath "$AUX_CLASSPATH" \
        -sourcepath "./apps:./router:./core" \
        $FILES
else
    echo "Error: Neither SpotBugs script nor jar found at $SPOTBUGS_SCRIPT or $SPOTBUGS_JAR"
    exit 1
fi

echo "SpotBugs analysis complete: $OUTPUT"

# Ensure dist directory exists
mkdir -p dist

# Transform to HTML if spotbugs.xsl exists AND the XML report was created
if [ -f "spotbugs.xsl" ] && [ -f "$OUTPUT" ]; then
    echo "Transforming SpotBugs XML to HTML using custom stylesheet..."
    xsltproc -o "dist/spotbugs.html" "spotbugs.xsl" "$OUTPUT"
    echo "SpotBugs HTML report written to dist/spotbugs.html"
elif [ -f "spotbugs.xsl" ]; then
    echo "Warning: spotbugs.xsl found but SpotBugs XML report ($OUTPUT) was not created"
fi