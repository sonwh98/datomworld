#!/bin/bash
set -e

echo "Compiling ClojureDart tests..."
# Compile all namespaces in the test directory that are relevant for CLJD
# Since we have a lot of .cljc files in test/, we let cljd find them.
# We explicitly compile the ones needed by test/dart/runner.dart
clj -M:cljd compile \
    dao.stream-test \
    yin.vm.parity-test \
    dao.db-test \
    dao.db.in-memory-test \
    dao.repl-test \
    dao.stream.apply-test \
    dao.stream.transit-test \
    yin.vm.ast-walker-test \
    yin.vm.engine-test \
    yin.vm.macro-test \
    yin.vm.register-test \
    yin.vm.semantic-test \
    yin.vm.stack-test

echo "Running Dart tests..."
dart test/dart/runner.dart
