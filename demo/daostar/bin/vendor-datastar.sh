#!/bin/sh
# Vendor the pinned Datastar bundle for the dao.star cart demo into
# resources/dao_star_cart/public/js/ (run once, with network, from anywhere;
# the js dir is resolved relative to this script).  The pinned version is
# dao.star-cart.view/datastar-version.
set -eu
VERSION=1.0.0-RC.7
DEST="$(cd "$(dirname "$0")/.." && pwd)/resources/dao_star_cart/public/js"
mkdir -p "$DEST"
curl -fsSL "https://cdn.jsdelivr.net/gh/starfederation/datastar@${VERSION}/bundles/datastar.js" -o "$DEST/datastar.js"
echo "vendored datastar ${VERSION}: $(wc -c < "$DEST/datastar.js") bytes, sha256 $(shasum -a 256 "$DEST/datastar.js" | cut -d' ' -f1)"
