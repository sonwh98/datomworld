Created-GMT: 2026-11-03 14:45:00 GMT
Created-Local: 2026-11-03 21:45:00 Asia/Ho_Chi_Minh
Coding-Agent: muse (muse-wonder-1.0, laptop)
Session-ID: not-applicable (orchestrator-dispatched, fresh session)
# Task: H3-2 fixture-family PREPARATION — design and A-verify the proof-elaboration fixture family

Role: QA/fixture engineer (muse)
Reviewer/landing: the ZCode glm orchestrator seat; kimi reviews the design before any harness integration.

## Context

The architect's approved hardening-3 plan (read `collab/1793770000000-architect-hardening-3-design.gpt-6-astra.prompt.md`, slice H3-2) authorizes closing the proof-elaboration cluster (11 unexercised arities: elab-have 8, elab-have-impl 8, elab-declare 7, elab-discharge 6, elab-discharge-impl 6, abstract-local-def 4, abstract-local-calls 4, gen-local-calls 3, update-def-uses 3, update-var-deps 3, ref-uses-in-term 1) through a SMALL NAMED INTEGRATION FAMILY of end-to-end proof fixtures — NOT eleven isolated helper calls. Per the plan, independent fixture PREPARATION may overlap other work; this round is preparation only.

Work in `/Users/sto/workspace/datomworld-h31`, branch `hardening3-h3-1` (the D2 arc's branch; you are ADDING a design document, not touching the compiler). Note: master is the correct base for the pinned upstream — the A oracle runs original upstream Clojure, so the design can be A-verified against the pinned latte-kernel (deps.edn :test alias has latte-kernel at sha 86fca0d1).

## Deliverable

A design document `test/yang/latte_oracle/h3_2_fixture_family.md` (new file; commit it) containing:

1. **The family**: the smallest complementary set of end-to-end proof fixtures covering all 11 arities, following the pinned upstream proof tests' structure (read the pinned latte-kernel source: `~/.gitlibs/libs/latte-kernel/...86fca0d1.../src/latte_kernel/proof.cljc` — the proof tests show the script shapes). Required coverage per the plan:
   - named `have` with explicit and inferred types, plus anonymous `have`;
   - a local definition referenced by another local definition;
   - discharge with nonempty dependency sets (forcing definition abstraction and local-call rewriting);
   - completion through `qed` and `check-proof`.
2. **Per fixture**: the exact script (proof steps), the expected A outcome (verified by RUNNING it against the pinned upstream via the A path — the harness's `kernel-a`-equivalent: original upstream Clojure evaluation; you may run upstream directly with a clojure REPL over the pinned latte-kernel), and WHICH of the 11 arities each fixture exercises (predicted from the upstream source's call graph).
3. **Refusal companions**: source-backed refusal fixtures for meaningful boundaries — duplicate local definitions, invalid declared types, incorrect discharge — each with the expected A rejection.
4. **Conservative-upstream note**: preserve pinned behavior including upstream's conservative `update-var-deps`; the slice does not correct LaTTe.
5. **Credit prediction table**: fixture → arities → direct/named-integration → which H3-2 acceptance line it satisfies.

## Headless constraints (binding — measured failure modes)

- You run HEADLESS: shell command approvals are unavailable, so DO NOT run shell commands — but you MAY run the A-verification via a different mechanism: write a standalone verification script `test/yang/latte_oracle/h3_2_a_verify.clj` as a FILE (the seat runs it), and mark the expected outcomes in the design doc as "A-verified pending seat run" where you could not execute.
- File edits only. No git operations. The seat commits.
- No harness changes (`latte_oracle_test.clj` is off-limits this round); no evidence regeneration; no compiler changes.

## Report

The design doc + the verification script, committed on the branch (no push; the seat lands after kimi's design review). State which predictions are machine-verified vs source-derived.
