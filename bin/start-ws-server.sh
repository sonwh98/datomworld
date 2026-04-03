#!/bin/bash
# Start the WebSocket server on port 8080
#
# The server handles dao.stream.apply requests for:
#   :op/add [a b] → returns a + b
#   :op/multiply [a b] → returns a * b
#   :op/uppercase [s] → returns uppercased string

set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"

PORT="${1:-8080}"

echo "Starting WebSocket server on port $PORT..."
echo "Listening for requests from CLJS clients"
echo ""

clj -M:clj -m datomworld.ws-demo-server $PORT
