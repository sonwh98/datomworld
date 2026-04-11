#!/bin/bash
set -e

echo "Compiling ClojureDart tests..."
clj -M:cljd compile dao.cljd-runner

echo "Running Dart tests..."
dart test/dart/runner.dart
