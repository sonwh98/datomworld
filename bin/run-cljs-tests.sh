#!/bin/bash
set -e

echo "Compiling ClojureScript tests..."
# Use clj -M:cljs to run shadow-cljs as configured in deps.edn
clj -M:cljs -m shadow.cljs.devtools.cli compile test

echo "Running Node.js tests..."
node target/node-tests.js
