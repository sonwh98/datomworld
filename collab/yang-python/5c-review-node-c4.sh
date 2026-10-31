#!/bin/sh
# Reviewer's Node slow C4 scanner row (phase 5c review). Foreground; no backgrounding inside.
cd /Users/sto/workspace/datomworld-srvm-p5c || exit 1
export DATOM_SLOW_TESTS=1
date
node target/5c-review-node-scanners.js --test=yin.vm.semantic-register.scan-test/scanners-agree-on-the-c4-modules
echo "node exit $?"
date
