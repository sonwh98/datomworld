#!/bin/bash
set -euo pipefail

echo "Compiling ClojureDart tests..."

# Test helper namespaces under test/ compile into lib/cljd-out and are imported
# by generated test entrypoints. Keep them in the same compile pass so the
# generated APIs stay in sync with the tests.
clj -M:cljd compile \
    dao.test-utils \
    yin.vm.test-utils \
    datomworld.demo.continuation-handoff \
    agent.tzu-test \
    dao.stream.http-test \
    dao.stream-test \
    dao.db-test \
    dao.db.in-memory-test \
    yin.repl-test \
    dao.stream.apply-test \
    dao.stream.transit-test \
    dao.gui.compiler-cljd-test \
    dao.postgraphics.flutter-cljd-test \
    dao.postgraphics.v3-compliance-repro-test \
    yin.vm.ast-walker-test \
    yin.vm.engine-test \
    yin.vm.macro-test \
    yin.vm.parity-test \
    yin.vm.register-test \
    yin.vm.semantic-test \
    yin.vm.stack-test \
    datomworld.demo.continuation-handoff-test

echo "Running Dart tests..."
flutter test test/dart/runner.dart
