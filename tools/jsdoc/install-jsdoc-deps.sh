#!/bin/bash
# Install JSDoc dependencies
# Run this once before using 'ant jsdoc' or after pulling changes

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TEMPLATE_DIR="$SCRIPT_DIR/docdash-template"

echo "Installing JSDoc dependencies..."

if [ -d "$TEMPLATE_DIR" ]; then
    cd "$TEMPLATE_DIR"
    npm install --prefer-offline
    echo "Dependencies installed in $TEMPLATE_DIR"
else
    echo "Error: docdash-template not found at $TEMPLATE_DIR"
    exit 1
fi

echo "Done."