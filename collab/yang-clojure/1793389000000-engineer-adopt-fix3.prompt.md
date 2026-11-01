Created-GMT: 2026-10-30 19:25:00 GMT
Created-Local: 2026-10-31 02:25:00 +07
Coding-Agent: kimi
Session-ID: pending (provider-generated)
# Task: adopt-fix3-n1-n2
Role: Compiler & AST Engineer

Implementers:
- Model: kimi-code/k3.1-pro | Assigned: 2026-10-31 02:25:00 +07 | Status: active | Rationale: bounded mechanical fix; codex pool state unknown, glm just finished its window.

Work in /Users/sto/workspace/datomworld-yang-common-call (branch yang-common-call @abbfb998, rebased onto stable master 63674368). Files you may edit:
- src/cljc/yang/clojure.cljc
- test/yang/clojure_test.clj
- the three closure manifests (whatever the repo's generator writes) + any count the gate test checks.
Do NOT edit src/cljc/yang/core/call.cljc (another owner's shared runtime) or src/cljc/yin/.

Two required fixes from the architect's N1/N2 ruling (collab/1793388000000-architect-yang-call-kimi-r5-n1.kimi-k3.1-pro.findings.md), following glm's confirmation review (collab/1793386000000-...findings.md):

1. N1 — WITNESS-GATE THE INVOCATION OPERATORS. `yang.call/invoke` and `yang.call/invoke-with`
   are namespaced (native-name?) operators, so source calls to them currently compile through the
   direct native branch and bypass the admission witness. Probes that must flip from success to
   qualified refusal:
     (let [h (assoc {} :yang.call/entry (get f :yang.call/entry))] (yang.call/invoke h [21] [] raise)) => must be :yang.call/not-callable (currently [:yin.k/ok 42])
     same via invoke-with; and forged guest-fn/scalar entries must ALSO refuse with the qualified
     condition, never a host "Cannot apply non-function" defect.
   Fix: extend the special compile branch at src/cljc/yang/clojure.cljc:1286 (the one that handles
   yang.call/eligible?) to cover both operators and check dsg__admitted? on their callee before
   invoking the shared runtime. Do not change call.cljc.

2. N2 — REGENERATE THE CLOSURE MANIFESTS so they include src/cljc/yang/core/call.cljc (the adoption
   made it reachable), updating any counts the gate test asserts.

Fixtures: refusal for both operators (copied entry, scalar entry, guest-fn entry); positive controls
for genuinely admitted descriptors including the dynamic-eligibility behavior (eligible? true/false
still works); a non-symbol-operator form if reachable.

Verify: clj -M:test -n yang.clojure-test -n yang.core.call-test -n yin.vm.macro-test
-n yang.clojure.stream-eval-test AND the closure/gate test the N2 failures came from
(dao.host.gate-test) -> 0 failures; the accumulated oracle corpus (yang.latte-oracle-test) unchanged
(its one intentional gate failure is expected); kondo 0 errors, no new warnings. Commit on
yang-common-call (format.md). Do not push.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
