Created-GMT: 2026-10-30 04:00:00 GMT
Created-Local: 2026-10-30 11:00:00 +07
Coding-Agent: codex
Session-ID: pending (provider-generated)
# Task: clojure-common-call-adoption-s1
Role: Compiler & AST Engineer

Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-10-30 11:00:00 +07 | Status: active | Rationale: codex engineering lane per the common-call ownership brief.

Work in /Users/sto/workspace/datomworld-yang-common-call (branch yang-common-call, rebased onto master@64f2885a — the shared yang.call runtime has LANDED: src/cljc/yang/core/call.cljc emits the yang.call module guest-side, bundled into preludes; test/yang/core/call_test.cljc is its suite).

GOVERNING:
- docs/design/yang.antlr.md section 8.10 (committed eb8030e8; in your base)
- The architect rulings A1-A5 (collab/1793292667828-architect-yang-call-kimi-r3-clojure.kimi-k3.1-pro.findings.md, also in this worktree's collab/): A1 every admitted Clojure source function publishes an immutable descriptor {:yang.call/entry <new unary record-closure> :clj/type :function} — never the body closure, never deferred to export; A2 EVERY source application routes through the common projection (no descriptor-check fast path as conformance; internal compiler-generated calls stay private); A3 :yang.call/named [] always; A4 outcome<->raise conversion at every generated entry/caller bridge; A5 dsg__ temps, guest-library privates, and the kernel context are NOT descriptors.
- The ownership map: collab/1793297800000-orchestrator-clojure-ownership-map.md (main collab/).

SCOPE (S1 for Clojure, bounded):
1. Descriptor publication: defn/fn/def sites construct the descriptor (entry = new unary
   record-closure capturing the private argument-vector body; :clj/type :function) under
   the outcome model (the entry is a guest function too: ok/error outcomes per slice 5).
2. Source application: operator resolution routes through the common projection —
   descriptor callables invoke via the entry/invocation record; plain non-descriptor
   closures (internal, private, pre-adopted program values) apply directly as today.
   Per A2 there is no conformance fast path, but internal compiler-generated calls
   (dsg__ temps, lowered library internals) remain private direct applications.
3. The entry adapter performs the A4 conversion: callee body outcome ok -> value;
   error -> invoke :yang.call/raise with {:yang.call/reason :yang.call/thrown
   :yang.call/value <original-thrown-value>} per 8.10.7 (note: the guest outcome's
   data is the slice-5 error payload; the mapping preserves it inside the condition).
   Caller-side: a received condition transfers back into outcome propagation — never
   returned as a successful value.
4. :yang.call/named [] always; nonempty named -> guest binding error (fixture).
5. Cross-language demonstration fixture: a TS-or-PH P-constructed callable value is
   NOT yet available on this branch (TS/PHP prelude wiring is their track) — instead,
   fixture a HAND-CONSTRUCTED conforming descriptor (as the spec's own illustration
   shows) applied from Clojure, proving the projection/invocation path end to end.
   Cross-frontend fixtures defer to the coordinating owner's suite.

FILES: src/cljc/yang/clojure.cljc, test/yang/clojure_test.clj, test/yang/latte_oracle_test.clj (only if oracle regressions must acknowledge the new convention), test/yang/core/call_test.cljc (only additions proving Clojure-side integration of the shared runtime). Nothing under src/cljc/yin/. The shared runtime src/cljc/yang/core/call.cljc is READ-ONLY to you (coordinating owner's file).

Verify: clj -M:test -n yang.clojure-test -n yang.core.call-test -n yin.vm.macro-test -n yang.clojure.stream-eval-test -> 0 failures; the full accumulated oracle corpus stays green (the convention is additive: plain non-descriptor closures behave exactly as today); kondo 0 errors, no new warnings. Do not commit — the orchestrator reviews and commits.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
