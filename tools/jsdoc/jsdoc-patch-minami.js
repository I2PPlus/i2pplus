#!/bin/sh
#
# Post-process minami jsdoc output: copy local fonts and CSS.
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

# Copy ionicons font
mkdir -p "$JSDOC_DIR/fonts/ionicons"
cp "$SCRIPT_DIR/minami-local/fonts/ionicons.woff" "$JSDOC_DIR/fonts/ionicons/" 2>/dev/null

# Copy local CSS files
cp "$SCRIPT_DIR/jsdoc-fonts.css" "$JSDOC_DIR/styles/fonts.css" 2>/dev/null
cp "$SCRIPT_DIR/minami-local/ionicons.css" "$JSDOC_DIR/styles/ionicons.css" 2>/dev/null
cp "$SCRIPT_DIR/jsdoc-minami-dark.css" "$JSDOC_DIR/styles/jsdoc-minami-dark.css" 2>/dev/null

# Update minami CSS to use local fonts and remove Google Fonts import
for css in "$JSDOC_DIR"/styles/jsdoc-default.css; do
  [ -f "$css" ] || continue
  sed -i '/@import.*fonts.googleapis/d' "$css"
  sed -i "s/font-family: 'Source Sans Pro',/font-family: 'Open Sans',/g" "$css"
  sed -i "s/font-family: 'Roboto',/font-family: 'Open Sans',/g" "$css"
done

# Update HTML to use local CSS and add dark theme
for html in "$JSDOC_DIR"/*.html "$JSDOC_DIR"/module*/*.html; do
  [ -f "$html" ] || continue
  sed -i 's|https://code.ionicframework.com/ionicons/2.0.1/css/ionicons.min.css|styles/ionicons.css|g' "$html"
  # Add dark theme CSS before </head>
  sed -i 's|</head>|<link rel="stylesheet" href="styles/jsdoc-minami-dark.css">\n</head>|' "$html"
done

echo "Fonts and CSS patched in $JSDOC_DIR"
