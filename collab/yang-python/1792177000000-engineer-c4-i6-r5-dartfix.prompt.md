Created-GMT: 2026-10-16 18:15:00 GMT
Created-Local: 2026-10-17 01:15:00 +07 (+0700)
Coding-Agent: glm
Session-ID: 0c99e083-bffb-49d9-9038-d079daff5148

# Task: C4 I6 fix round — the Dart import-test reds (5 tests) on c4-i6-r5

Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: glm-5.3 | Assigned: 2026-10-17 01:15:00 +07 (+0700) | Status: active | Rationale: you verified the pins round on this exact tree; continuation

WORK TREE: /Users/sto/workspace/datomworld-c4-i6 (branch c4-i6, tip aa8dad89 = I6 + the regenerated pins + the
re-minted pysp golden; landed I5 is beneath). Do not touch other worktrees. Do not stage or commit.

STATE: the shiva Dart slow lane on aa8dad89 finished +791 -3 (log:
shiva.stigmergy.systems:~/ci/datomworld-c4-i6-dart/target-ci/i6r5-dart-slow.log — read it over ssh if needed).
The failures are in test/yang/python/antlr/import_test.cljc, portable parity tests on Dart:
- mutually-importing-modules-test
- aliased-and-from-forms-test-test
- an-import-free-program-tree-is-unchanged-test
- cached-import-skips-delivery-test-test
- catchable-delivery-refusals-test-test
(scan-test/verify-outcomes-agree also failed — that is the KNOWN deferred golden group; leave it.)
The same namespaces are GREEN on the JVM. There is no clean I5-era Dart baseline (the post-rebase I5 Dart
rerun never ran), so determine per test whether I5, I6, or the pins regen introduced the Dart behavior.

ROOT-CAUSE then FIX, per test: candidates include Dart BigInt carriers in prelude/module rows (the srvm 8d
class), host `=`/hash differences on the import thunks' cell contents, cljd reader quirks in the new test
packets, and address divergence on Dart in the I6 reload path. Behavior reds get FIXED; pin/address reds get
regenerated via the checked-in mechanisms only (never hand-edit). Do not weaken tests. Keep the I6/I5 reviewed
content intact except your fixes.

VERIFICATION (foreground, one command at a time, waiting; no background runs, no watchers, complete the turn):
1. Focused Dart: DATOM_SLOW_TESTS=1 mise exec -- bb test:slow:cljd is the full lane (~70 min) — run it LAST.
   First: the focused import namespaces on Dart via bb test:sub:cljd or the direct namespace invocation if
   available (check bb.edn for the task shape).
2. JVM non-regression: DATOM_SLOW_TESTS=1 mise exec -- clojure -M:test -i :slow -n
   yang.python.antlr.import-test,yang.python.antlr.dht-import-test.
3. kondo + cljstyle on touched files.
Report: root cause per test, the fix, exact counts, anything behavioral vs environmental.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700>
