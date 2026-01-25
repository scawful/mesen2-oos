#!/bin/bash
# Deploy MesenCore.dylib to agent tools and app bundle

set -e

# Configuration
SOURCE_LIB="InteropDLL/obj.osx-arm64/MesenCore.dylib"
DEST_MCP="$HOME/src/tools/mesen2-mcp/MesenCore.dylib"
DEST_APP="/Applications/Mesen2 OOS.app/Contents/MacOS/MesenCore.dylib"

# Check if source exists
if [ ! -f "$SOURCE_LIB" ]; then
    echo "Error: Source library not found at $SOURCE_LIB"
    echo "Run 'make' first."
    exit 1
fi

echo "Deploying MesenCore.dylib..."

# 1. Deploy to mesen2-mcp (Headless/Agent use)
if [ -d "$(dirname "$DEST_MCP")" ]; then
    echo "-> Copying to mesen2-mcp..."
    cp "$SOURCE_LIB" "$DEST_MCP"
    echo "   Success."
else
    echo "-> Skipping mesen2-mcp (directory not found)"
fi

# 2. Deploy to Mesen2 OOS.app (GUI use)
if [ -d "$(dirname "$DEST_APP")" ]; then
    echo "-> Copying to Mesen2 OOS.app..."
    # Try copying, might need sudo if user doesn't own /Applications
    if cp "$SOURCE_LIB" "$DEST_APP"; then
        echo "   Success."
    else
        echo "   Failed to copy to App bundle (permission denied?)"
        echo "   Try: sudo cp \"$SOURCE_LIB\" \"$DEST_APP\""
    fi
else
    echo "-> Skipping Mesen2 OOS.app (app not found)"
fi

echo "Deployment complete."
