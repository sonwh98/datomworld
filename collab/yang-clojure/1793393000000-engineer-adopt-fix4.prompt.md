Created-GMT: 2026-10-30 20:15:00 GMT
Created-Local: 2026-10-31 03:15:00 +07
Coding-Agent: kimi
Session-ID: pending (provider-generated)
# Task: adopt-fix4-operand-order
Role: Compiler & AST Engineer

Implementers:
- Model: kimi-code/k3.1-pro | Assigned: 2026-10-31 03:15:00 +07 | Status: active | Rationale: bounded fix; codex astra authored the finding.

Work in /Users/sto/workspace/datomworld-yang-common-call (branch yang-common-call @12feb7d1). Files: src/cljc/yang/clojure.cljc and test/yang/clojure_test.clj only.

Architect (astra) finding on the N1 fix — P2: THE INVOCATION ADMISSION GATE SKIPS ARGUMENT EVALUATION ON REFUSAL.
At src/cljc/yang/clojure.cljc:1305 the positional, named and options operands are evaluated only in the ADMITTED branch of the new yang.call/invoke + invoke-with admission gate. §8.10.4 requires source operands to evaluate exactly once, in source order, before the callee is entered (and refusals must not suppress those effects).

Probe (astra's, using the existing profile-raise harness):
  (yang.call/invoke 5 (probe/note [21]) (probe/note []) profile-raise)
  (yang.call/invoke-with 5 (probe/note [21]) (probe/note []) profile-raise (probe/note {}))
At parent abbfb998 these record [21], [] and {} BEFORE the refusal. At 12feb7d1 none of the argument effects occur — only the refusal. Confirmed on both walker and semantic lanes.

FIX: evaluate and bind ALL operands exactly once, left to right, BEFORE the witness check, and use those bindings in BOTH the admitted and refused branches. Do not duplicate evaluation (no re-evaluating operands in the admitted arm). Same discipline as the compiler already applies elsewhere (see how other multi-operand forms bind temps once).

Regressions: refused callees through BOTH operators must still run their argument effects, in order, exactly once — including the options operand for invoke-with. Add positive-path controls that admitted calls still evaluate once in order. Cover both lanes.

Verify: clj -M:test -n yang.clojure-test -n yang.core.call-test -n yin.vm.macro-test -n yang.clojure.stream-eval-test -n dao.host.gate-test -> 0 failures; oracle corpus (yang.latte-oracle-test) unchanged at 21/971 (its one intentional gate failure expected); kondo 0 errors, no new warnings. Commit on yang-common-call (format.md). Do not push.
