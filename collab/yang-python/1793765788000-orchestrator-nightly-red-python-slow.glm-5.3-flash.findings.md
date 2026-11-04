Created-GMT: 2026-11-04 04:16:28 GMT
Created-Local: 2026-11-04 11:16:28 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: interactive (zcode, glm-5.3-flash)
Session-ID: not-applicable (interactive seat)
# Task: nightly red handoff — yang.python slow-tier failures
Role: orchestrator
To: the yang.python track seat (relay by owner)

## Source run

Jupiter standalone JVM nightly, run `20261103-1342` at master `2ec867d2`.
Results dir: `~/ci/nightly-results/20261103-1342/` on jupiter. Two red
namespaces in the slow tier, both previously green in run 20261102-1651.

## Failure 1 — `yang.python.antlr.safepoint-test`: JVM died mid-test

`slow-yang.python.antlr.safepoint-test.log` is 6 lines, ending at
"Testing yang.python.antlr.safepoint-test" — no results summary, no
exception, no assertion output. The JVM exited (or was killed) during the
namespace run. Prior run (20261102-1651) had this namespace producing
normal measured-case output. Host shows no OOM evidence (62 GB total,
load was nominal). Class: infra/transient suspect, same shape as the
yang.php include-test death in the same wave — possibly a 12-way
slow-pool concurrency artifact rather than a code defect. Isolated
reproduction is the next step per the failure-reproduction rule:
`clojure -M:test -i :slow -n yang.python.antlr.safepoint-test` (single
JVM). If it passes solo, no yang.python code change is needed.

## Failure 2 — `yang.python.antlr.e2e-test`: fresh-REPL 30 s timeout

`filtered-range-comprehension-repl-test` (e2e_test.clj:341):

    expected: [:primed "" "[4, 16, 36, 64, 100]\n"]
    actual:   :yang.python.antlr.e2e-test/timeout

The fresh-REPL round (index the bundled prelude, evaluate a filtered
range comprehension, keep the value) did not terminate within its 30 s
deref timeout. This ran inside the 12-way slow pool; the same test's
budget may be load-sensitive. Isolated reproduction:
`clojure -M:test -i :slow -n yang.python.antlr.e2e-test`. If it passes
solo, options are a raised timeout or pool-size awareness — the seat's
call; do NOT raise the budget reflexively without a solo measurement
first (build-n-test.md's measure-then-pin rule).

## Failure 3 — Node lane: recursion-limit emulation leaks the JS stack
overflow (shiva run, pin `ab3839b5`)

Shiva's completed Node lane (run `node-shards-20261102-1650`, 14 lanes,
wall 36,904 s) had exactly one red: `yang.python`, 6 failures —
`linked-recursion-error-test` (linked_safepoint_test.cljc:372, on
:semantic and :register) and `recursion-error-test` /
`generator-rebase-test` (safepoint_test.cljc:156). Signature: the guest
RecursionError emulation at the 1000-frame default limit does not fire on
Node before the JS engine's own stack exhausts —

    expected: [true false {:py/out ["0" "rec" "1000"], :py/exception nil}]
    actual:   [:thrown "Maximum call stack size exceeded"]

i.e. f(1000) must raise a catchable guest RecursionError; instead the
host stack overflow escapes as an uncaught JS RangeError. This is the
SAME safepoint territory as Failure 1's `safepoint-test` red on the JVM
lane — treat as one cross-host investigation of the recursion-limit
safepoint, not two unrelated flakes. Reproduce per lane with only the
failing namespaces: Node `node target/node-tests.js
--test=yang.python.antlr.safepoint-test,yang.python.antlr.linked-safepoint-test`;
JVM `clojure -M:test -i :slow -n yang.python.antlr.safepoint-test`.
Logs: shiva `~/ci/datomworld-ts3/target-ci/node-shards-20261102-1650/yang.python.log`
(failures at lines 81-284).

## Context (not yang.python's)

The same runs' other reds belong elsewhere and are handed off separately:
yang.php golden-pin drift after the P5 merge + the include-test hang
(collab/yang-php note 1793765688000) and the known hardening3-h3-1 latte
kernel gate. Shiva's Dart lane completed fully green (4,508 assertions,
wall 25,806 s, done rc=0).
