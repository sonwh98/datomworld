#!/bin/sh
# Vendor the pinned Datastar bundle into public/js/ (run once, with network).
# The pinned version is dao.star-cart.view/datastar-version.
set -eu
VERSION=1.0.0-RC.7
DEST="$(dirname "$0")/public/js"
mkdir -p "$DEST"
curl -fsSL "https://cdn.jsdelivr.net/gh/starfederation/datastar@${VERSION}/bundles/datastar.js" -o "$DEST/datastar.js"
echo "vendored datastar ${VERSION}: $(wc -c < "$DEST/datastar.js") bytes, sha256 $(shasum -a 256 "$DEST/datastar.js" | cut -d' ' -f1)"
