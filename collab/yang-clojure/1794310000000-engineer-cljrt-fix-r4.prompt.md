Created-GMT: 2026-11-04 00:15:00 GMT
Created-Local: 2026-11-04 07:15:00 Asia/Ho_Chi_Minh
Coding-Agent: glm (glm-5.3); owner instruction after two muse-wonder-1.0 fix rounds failed this defect
Session-ID: not-applicable (orchestrator-dispatched, fresh session)
# Task: cljrt fix round 4 — authenticity-failure fallback for cell-ref maps + the D2 rebase reconciliation

Role: compiler engineer (glm). NOTE: a fresh codex gate reviews before landing — write for review. You have shell; use it.

## Context

Worktree /Users/sto/workspace/datomworld-cljrt, branch yang-clojure-runtime-module, head d3a35830. Read in order:
1. The final gate's rejection (your spec): collab/yang-clojure/1794300000000-reviewer-cljrt-r3.codex.stdout.log — the agent messages. Key references it gives: src/cljc/yang/clojure.cljc:1224 (the shape test), :1343 (the routing), :2184 (cell/get in admission); src/cljc/yin/vm/engine.cljc:316 (the VM ISSUES genuine references as maps with exactly {:type :cell-ref :id :seal}); test/yang/cross_lang_test.clj:161 (the weak tests).
2. The branch history: e267bcf3 (publish + run-main + globals + profile + legs) -> 0ecddf87 (globals lifecycle, structural cell-ref admission, kind-of) -> d3a35830 (shape-based fallback — INSUFFICIENT, you are fixing its class).
3. The landed D2 on master (8fb525c4): read master's src/cljc/yang/clojure.cljc around call-entry/admission — the token-packet lane with dsg__call_token authentication, the arity guard, the witness admission. YOUR BRANCH predates it (merge-base 7eb7fc9e).

## The defect (why shape checks can never work)

Genuine cell references ARE maps at the VM level (engine.cljc:316), shape {:type :cell-ref :id X :seal S}; authenticity is the heap+seal check at deref time, not any shape. So a legitimate three-entry DATA map with a 64-char string under :seal routes to admission and dies at cell/get instead of returning its stored value via ordinary map invocation. The r2/r3 fixes keyed routing on shape presence/matching — that direction cannot converge.

## The fix (specified by the gate; implement exactly this direction)

Route shape-matching maps to admission (genuine references require this), and have admission FALL BACK to ordinary map invocation WHEN THE AUTHENTICITY CHECK FAILS specifically — invalid seal or nonexistent cell means it was never genuine: it is data; invoke it as a map (get on the key, normal map-call semantics). The fallback must NOT trigger for genuine-but-dead references (a real cell that exists with a valid seal — keep the current refusal/behavior exactly). The D2 witness bar is unchanged: a forged source map must never gain cell capability — the fallback grants only what the value already is (plain data), never a deref.

## The second half of the unit: the D2 rebase

Rebase the branch onto CURRENT master (origin/master, 8fb525c4+): both lanes changed src/cljc/yang/clojure.cljc — reconcile the token-packet lane (entry authentication, arity guard, witness admission) with the runtime-module lane (run-main, globals, projection, your fallback). Preserve BOTH lanes' behavior; the merged compiler is the truth. Then re-verify EVERYTHING (see below) — pre-rebase results do not establish the merge result.

## Required tests (strengthened, addressing the gate's weaknesses finding)

- A FULL-SHAPE three-entry data map {:type :cell-ref :id :some-key :seal "<valid-format 64-char hex>"} invoked as data must return its stored value (both evaluators where the lane supports map invocation).
- A runtime-BUILT map (constructed with assoc/conj at runtime, not a bound literal) carrying the full shape must also invoke as data.
- Genuine projected-cell legs (the cross-language donor calls) must still work through admission.
- Genuine-but-dead reference behavior unchanged (the existing corpus/refusal tests stay green).
- The r3 tests you keep (four-entry and short-seal shapes) must stay green — now as the trivially-data cases.

## Verification

- Individual focused tests may run on this laptop (the forgery/leg tests, the new regressions): report real outputs.
- SUITE-SCALE runs (full matrix namespace, bb test:sub:clj yang.base) belong on the remote hosts per the owner ruling — if your sandbox cannot ssh, DO NOT run them here; list the exact commands for the seat to run on jupiter.
- clj-kondo on touched files: 0 errors.
- The D2 authenticity regression (test-common-call-token-packet-forgery-refuses) and the 4M kernel gate must be re-established on the REBASED tree (commands for the seat).

## Boundaries

- src/cljc/yang/clojure.cljc + test/yang/** as needed. NO src/cljc/yin/vm/** changes, NO src/cljc/yang/core/call.cljc, no evidence/narrative files. §8.10 conformance binding (no call-node surgery, no foreign :code extraction, no wrappers; admission not weakened).
- Commit on the branch; push (gh HTTPS if ssh unavailable; never change origin config); do NOT merge.

## Report

The diff summary (post-rebase), the conflict resolution narrative (what D2 hunks vs runtime hunks met and how both survived), every test you ran with real output, and the exact remote commands for the seat. A fresh codex gate reviews before landing; write for review.
