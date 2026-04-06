#!/bin/bash
# Deploy MesenCore.dylib to agent tools and app bundle

set -e

# Configuration
SOURCE_LIB="InteropDLL/obj.osx-arm64/MesenCore.dylib"
LEGACY_CORE_DEST="${MESEN2_CORE_DEPLOY_PATH:-}"
DEST_APP_SYSTEM="/Applications/Mesen2 OOS.app/Contents/MacOS/MesenCore.dylib"
DEST_APP_USER="$HOME/Applications/Mesen2 OOS.app/Contents/MacOS/MesenCore.dylib"

# Check if source exists
if [ ! -f "$SOURCE_LIB" ]; then
    echo "Error: Source library not found at $SOURCE_LIB"
    echo "Run 'make' first."
    exit 1
fi

echo "Deploying MesenCore.dylib..."

deploy_to() {
    local dest="$1"
    if [ -d "$(dirname "$dest")" ]; then
        echo "-> Copying to $(dirname "$dest")..."
        if cp "$SOURCE_LIB" "$dest"; then
            echo "   Success."
        else
            echo "   Failed (permission denied?). Try: sudo cp \"$SOURCE_LIB\" \"$dest\""
        fi
    else
        echo "-> Skipping $(dirname "$dest") (not found)"
    fi
}

if [ -n "$LEGACY_CORE_DEST" ]; then
    deploy_to "$LEGACY_CORE_DEST"
else
    echo "-> Skipping legacy core deploy (set MESEN2_CORE_DEPLOY_PATH to enable)"
fi
deploy_to "$DEST_APP_SYSTEM"
deploy_to "$DEST_APP_USER"

echo "Deployment complete."
