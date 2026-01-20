#!/bin/sh
echo "Compiling benchmark for Node.js..."
npx shadow-cljs compile test

echo "Running benchmark on Node.js..."
node target/node-tests.js
