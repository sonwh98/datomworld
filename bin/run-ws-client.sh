#!/bin/bash
# Run the WebSocket client (CLJS in Node.js)
#
# Assumes the server is already running on localhost:8080
# Usage:
#   ./bin/run-ws-client.sh              # Connect to ws://localhost:8080
#   ./bin/run-ws-client.sh ws://host:port  # Connect to custom URL

set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"

URL="${1:-ws://localhost:8080}"

echo "Building client..."
npx shadow-cljs compile ws-client-demo > /dev/null 2>&1

echo "Running CLJS client..."
echo "Connecting to $URL"
echo ""

node target/ws-client-demo.js "$URL"
