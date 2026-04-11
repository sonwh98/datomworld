#!/bin/bash
set -e

echo "========================================"
echo "Running Clojure (JVM) Tests..."
echo "========================================"
clj -M:test

echo ""
echo "========================================"
echo "Running ClojureScript (Node.js) Tests..."
echo "========================================"
./bin/run-cljs-tests.sh

echo ""
echo "========================================"
echo "Running ClojureDart (Dart VM) Tests..."
echo "========================================"
./bin/run-cljd-tests.sh

echo ""
echo "========================================"
echo "ALL TESTS PASSED!"
echo "========================================"
