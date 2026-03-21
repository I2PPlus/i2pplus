#!/bin/sh
#
# Post-process tui jsdoc output: copy fonts and update font families.
#

JSDOC_DIR="$1"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

if [ -z "$JSDOC_DIR" ] || [ ! -d "$JSDOC_DIR" ]; then
  echo "Usage: $0 <jsdoc-dir>"
  exit 1
fi

# Copy Open Sans fonts
mkdir -p "$JSDOC_DIR/fonts/OpenSans"
cp installer/resources/themes/fonts/OpenSans/OpenSans.woff2 "$JSDOC_DIR/fonts/OpenSans/" 2>/dev/null
cp installer/resources/themes/fonts/OpenSans/OpenSans-Italic.woff2 "$JSDOC_DIR/fonts/OpenSans/" 2>/dev/null

# Copy Fira Code font
cp installer/resources/themes/fonts/FiraCode.woff2 "$JSDOC_DIR/fonts/" 2>/dev/null

# Copy font CSS
cp "$SCRIPT_DIR/jsdoc-fonts.css" "$JSDOC_DIR/styles/fonts.css" 2>/dev/null

# Update tui CSS to use Open Sans
for css in "$JSDOC_DIR"/styles/*.css; do
  [ -f "$css" ] || continue
  sed -i "s/font-family: Helvetica Neue, Helvetica, Arial/font-family: 'Open Sans', sans-serif/g" "$css"
  sed -i "s/font-family: Verdana, sans-serif/font-family: 'Open Sans', sans-serif/g" "$css"
done

echo "Fonts patched in $JSDOC_DIR"
