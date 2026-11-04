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

## Context (not yang.python's)

The same run's other reds belong elsewhere and are handed off separately:
yang.php golden-pin drift after the P5 merge (collab/yang-php note
1793765688000) and the known hardening3-h3-1 latte kernel gate.
