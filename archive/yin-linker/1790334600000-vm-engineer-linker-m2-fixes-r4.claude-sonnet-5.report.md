Created-GMT: 2026-09-25 11:10:00 GMT
Created-Local: 2026-09-25 18:10:00 +0700
Coding-Agent: claude (in-process subagent, Sonnet 5)
Session-ID: pending (provider-generated)

# M2 gate fix round 4 report

Fix (linker.cljc, tree-definition-occurrences): yin/def-derived
definitions are now dropped (obligations retained) when the module
has (a) any definition keyed yin/def, direct :vm/store-put included,
or (b) any yin/def application whose key is not statically a constant
(anything but two operands, first a literal), via new
computed-yin-def-write? and tree-yin-def-application-query. Rule is
per module; no cross-module widening. Docstring updated.

Tests (linker_test.cljc): a-direct-store-put-of-yin-def-discharges-
nothing-from-it and a-computed-key-yin-def-write-discharges-nothing-
from-it, each free of any literal yin/def rebinding. Both verified to
fail (2 failures) with the old guard restored, then pass.

Verification (sequential, mise): JVM 2,059 tests / 180,916 assertions /
0 failures (baseline 2,057/180,912); Node 1,975 tests / 47,941
assertions / 0 failures, "Testing yin.vm.linker-test" present; Dart
1,937 passed, All tests passed (cljd-out removed first). cljstyle
check clean. clj-kondo is not installed under mise, so not run.
ASCII, <=80 columns on added lines. No git state changed.
