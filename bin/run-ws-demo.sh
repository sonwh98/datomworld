#!/bin/bash

# Demo: CLJS client calling CLJ functions via WebSocket and dao.stream.apply
#
# Usage:
#   ./bin/run-ws-demo.sh          # Start server, run client in parallel
#   ./bin/run-ws-demo.sh server   # Start server only
#   ./bin/run-ws-demo.sh client   # Run client only (assumes server is running)

set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"

MODE="${1:-both}"

case "$MODE" in
  server)
    echo "Starting WebSocket server on port 8080..."
    clj -M:clj -m datomworld.ws-demo-server 8080
    ;;

  client)
    echo "Running CLJS client (connecting to ws://localhost:8080)..."
    node target/ws-client-demo.js
    ;;

  both)
    echo "Building client..."
    npx shadow-cljs compile ws-client-demo > /dev/null 2>&1

    echo "Starting WebSocket server on port 8080..."
    clj -M:clj -m datomworld.ws-demo-server 8080 &
    SERVER_PID=$!

    echo "Waiting for server to start..."
    sleep 1

    echo "Running CLJS client..."
    node target/ws-client-demo.js

    echo "Stopping server..."
    kill $SERVER_PID 2>/dev/null || true
    wait $SERVER_PID 2>/dev/null || true
    ;;

  *)
    echo "Usage: $0 [server|client|both]"
    echo ""
    echo "  server  - Start the WebSocket server only"
    echo "  client  - Run the CLJS client only (assumes server is running)"
    echo "  both    - Start server and run client (default)"
    exit 1
    ;;
esac
