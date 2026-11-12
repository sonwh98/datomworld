Created-GMT: 2026-11-10 20:00:00 GMT
Created-Local: 2026-11-11 03:00:00 +07 (+0700)
Coding-Agent: codex
Session-ID: pending (provider-generated — capture thread.started.thread_id)
# Task: independent review — yang.clojure R3 (protocol-condition retention through the public escape)
Role: Reviewer (independent; author chain was gemini + qwen-max + the orchestrator seat, so any family is independent — you are codex)
Implementers:
- Model: gpt-6-astra (codex) | Assigned: 2026-11-11 03:00 +0700 | Status: active | Rationale: read-only review of a boundary-contract diff; codex is the handoff's natural reviewer choice.

## The work under review

Branch `exception-r3-impl` @ `778747ce` (pushed; NOT merged). Diff of record:
`git diff 8cd9fc92..778747ce` — exactly two commits:
- `98519957` fix(yang.clojure): protocol conditions retain and forward unchanged through the public escape (R3)
- `778747ce` fix(yang.clojure): token-authenticate retained-condition provenance; guard strip-top-outcome for non-outcome units (R3)

Files: `src/cljc/yang/clojure.cljc` (+438/−257 across both commits) and
`test/yang/clojure_test.clj` (+156). Nothing else.

## What the round claims

Per ruling v2 R3 (`collab/exception-track/1794960000000-owner-ruling-exception-uniformity-v2.md` —
read §R3 and §R5): the public escape previously reclassified every retained condition through an
unconditional `:yang.call/thrown` (clojure.cljc:1088/:1306 on master); the fix makes protocol
conditions (`:yang.call/not-callable`, `:yang.call/invalid-invocation`,
`:yang.call/unsupported-binding`) retain and forward UNCHANGED — qualified reason, every evidence
field, payload identity — so an unchanged rethrow forwards the original condition without a new
wrapper. Per argon's adjudication 1794320926000: `:dsg__condition` is load-bearing provenance —
stamped with the compiler-held `dsg__call_token` at every writer site, read by token EQUALITY not
truthiness. Per codex ruling 1794332020000: the library value path (`invoke-value :else`) throws
an UNMARKED condition map; the §8.10.7 wrap at the boundary is the wire contract; never `(f)`.
Per argon #3 (1794336505000): `strip-top-outcome` needs the `data/vector?` + count-2 guard.

## Known-verified state (do not re-run suites; spend your budget on static analysis)

- Laptop (outgoing seat): `yang.clojure-test` 97F/937/0/0 → claimed green; kit contract test
  141 assertions 69F/21E (six `:clojure/protocol-*` + `:clojure/saved-protocol-*` rows green on
  all three backends — those six rows ARE the ledger deltas); kondo 0/0.
- Shiva confirmation session `xtrack-r3-final` was in flight at dispatch time (cmd1/cmd2 rc=0;
  the orchestrator will reconcile any contradiction with the laptop numbers before merge).

## Review questions

1. **R3 contract**: does every protocol-condition path (fresh not-callable/invalid-invocation/
   unsupported-binding, saved-protocol rethrow, higher-order re-entry) forward the ORIGINAL
   condition unchanged — reason, evidence fields, payload identity — with exactly one transfer
   and an unreachable after-call? Hunt for paths that still reclassify, double-wrap, or drop
   evidence fields.
2. **Provenance**: every `:dsg__condition` writer stamps `dsg__call_token`; every reader
   authenticates by token equality. Any truthiness reader left? Any source-forgeable path to a
   condition-looking map being accepted (R5: shape is never evidence)?
3. **`strip-top-outcome` guard**: is the `data/vector?` + count-2 guard correct and complete —
   can it now meet a legitimate 2-vector guest value, or a descriptor map it shouldn't touch?
4. **Behavior change to the ordinary (non-condition) paths**: the diff is large (+438/−257).
   What ELSE moved? Check the five value-path test expectation updates in
   `test/yang/clojure_test.clj` — each must be justified by the R3 contract, not weakened to
   pass (do-not-weaken rule).
5. **Tail semantics**: argon's adjudication required `remark-tails` in compile-program — is it
   present, and does anything now skip the tail remark that shouldn't (constant-depth property)?
6. **Scope**: only the two files above may change. Anything else in the diff is out of scope.

## Deliverable

Findings list: each with severity (P0 blocker / P1 must-fix / P2 should-fix / P3 note), file:line
evidence, and a concrete disposition proposal. State explicitly which of the six review questions
are clean. Do NOT edit anything; you are read-only. End with one line: APPROVE / APPROVE WITH
FINDINGS / REQUEST CHANGES.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
