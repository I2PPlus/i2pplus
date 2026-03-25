#!/bin/bash
# CodeQL Java build script - compiles all source files for extraction
# Excludes: build/, tmp/, jrobin/, pack200/, _jsp files, test dirs, third-party
find core router apps -name "*.java" \
  -not -path "*/build/*" \
  -not -path "*/tmp/*" \
  -not -path "*/jrobin/*" \
  -not -path "*/pack200/*" \
  -not -path "*_jsp*" \
  -not -path "*/test/*" \
  -not -path "*/com/maxmind/*" \
  -not -path "*/org/bouncycastle/*" \
  -not -path "*/org/cybergarage/*" \
  -not -path "*/com/mpatric/*" \
  -not -path "*/org/rrd4j/*" \
  -not -path "*/com/southernstorm/*" \
  -not -path "*/org/json/simple/*" \
  -not -path "*/net/metanotion/*" \
  -not -path "*/freenet/*" \
  -not -path "*/org/freenetproject/*" \
  -not -path "*/edu/internet2/*" \
  -not -path "*/io/pack200/*" \
  -not -path "*/edu/umd/*" \
  -not -path "*/com/tomgibara/*" \
  -not -path "*/com/google/zxing/*" \
  -not -path "*/com/vuze/*" \
  -not -path "*/com/docuverse/*" \
  -not -path "*/com/thetransactioncompany/*" \
  -not -path "*/org/minidns/*" \
  -not -path "*/com/mindrot/*" \
  > /tmp/codeql/sources.txt 2>/dev/null

echo "Found $(wc -l < /tmp/codeql/sources.txt) source files"

# Compile - errors are expected for files with missing deps
mkdir -p /tmp/codeql/classes
javac -d /tmp/codeql/classes -source 8 -target 8 -proc:none @/tmp/codeql/sources.txt 2>/dev/null || true
