#!/bin/sh
#
# Post-process jsdoc output: copy fonts and theme files.
#

JSDOC_DIR="$1"

if [ -z "$JSDOC_DIR" ] || [ ! -d "$JSDOC_DIR" ]; then
  echo "Usage: $0 <jsdoc-dir>"
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Copy fonts
mkdir -p "$JSDOC_DIR/fonts/OpenSans"
cp installer/resources/themes/fonts/OpenSans/OpenSans.woff2 "$JSDOC_DIR/fonts/OpenSans/" 2>/dev/null
cp installer/resources/themes/fonts/OpenSans/OpenSans-Italic.woff2 "$JSDOC_DIR/fonts/OpenSans/" 2>/dev/null
cp installer/resources/themes/fonts/FiraCode.woff2 "$JSDOC_DIR/fonts/" 2>/dev/null

# Copy font CSS and dark theme CSS (template injects these into HTML)
cp "$SCRIPT_DIR/jsdoc-fonts.css" "$JSDOC_DIR/styles/fonts.css" 2>/dev/null
cp "$SCRIPT_DIR/jsdoc-dark.css" "$JSDOC_DIR/styles/jsdoc-dark.css" 2>/dev/null

echo "Fonts copied to $JSDOC_DIR"
