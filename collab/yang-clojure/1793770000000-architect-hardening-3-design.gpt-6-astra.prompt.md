Created-GMT: 2026-11-02 10:35:00 GMT
Created-Local: 2026-11-02 17:35:00 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: not-applicable (orchestrator-dispatched; you may reference thread 01a199e5 contextually but this is a new design thread)
# Task: design the hardening-3 slice plan — close the kernel differential gate

Role: Architect (gpt-6-astra) — the approving architect for the yang.clojure track and the §8.10 convention
Requested-by: the ZCode glm orchestrator seat

## Governing instructions

Read `docs/design/yin.cic.spec.md` (the frozen Gate S v1 spec), `test/yang/latte_oracle/slice9_gate.md` (the gate record), `test/yang/latte_oracle/slice9_evidence.edn` + `slice9_unexercised.edn` (the refreshed evidence), and `docs/agents/known-gaps.md` rows tagged yang.clojure. The master is at `d5d56ca3` — read the tree in the main checkout `/Users/sto/workspace/datomworld` (read-only).

## The state you are designing from (measured 2026-11-02, shiva, at master)

The kernel differential gate runs 106 fixtures: **100 passing agreements, 2 mismatches, 2 blocked fixtures, 2 declared upstream failures; gate status :blocked**. Admissions: 155 bindings (146 admitted, 9 excluded), 175 candidate function/arities (165 admitted, 10 excluded). Executed coverage: **107 credited (39 direct + 68 named integrations), 58 admitted arities unexercised** — the full list is in the refreshed `slice9_unexercised.edn`. The common-call reconciliation un-credited 38 named-integration trace paths the pre-reconciliation evidence had recorded; the 20 previously-recorded unexercised arities are all still unexercised; the pass count did not move.

The 58 cluster into families (your design should decide order and strategy per family):

- **proof elaboration (the largest cluster)**: `elab-have 8`, `elab-have-impl 8`, `elab-declare 7`, `elab-discharge 6`, `elab-discharge-impl 6`, `abstract-local-def 4`, `abstract-local-calls 4`, `gen-local-calls 3`, `update-def-uses 3`, `update-var-deps 3`, `ref-uses-in-term 1`
- **presyntax parsing**: `parse/1`, `parse-arrow-term 3`, `parse-defined-term 4`, `parse-product-term 3`
- **normalization**: `beta-reduction 1`, `beta-step 1/2`, `beta-step-args 2`, `delta-step 4`, `delta-step-args 5`, `instantiate-def 3` (both norm and nbe), `redex? 1`, `nbe/evaluation 1`
- **syntax utilities**: `alpha-eq? 2`, `alpha-norm 1`, `alpha-norm- 3`, `assumptions 1`, `binderify 3`, `free-vars 1`, `mk-fresh 2/3`, `prod? 1`, `readable-term 1`, `readable-term- 2/3`, `rebinder 3`, `subst- 4`, `term-reduce 3`, `top-assumption 1`, `vars 1`
- **typing**: `type-of-app 4`, `type-of-args 3`, `type-of-refdef 5`, `generalize-params 2`, `prepare-argument-subst 6`
- **environment/unparser/utils**: `axiom? 1`, `definition? 1`, `qualify-def 2`, `registered-definition? 1/2/3`, `unregister-unparser! 1`, `map-with 3`, `vconcat 2`, `vcons 2`, `zip 1`

Two known bookkeeping defects to design around (both introduced since the gate round's evidence was written):

1. The evidence writer regenerates `:recorded-portability-boundaries` as `[]` and no fixture row carries `:status :recorded-deviation`, so the gate test's deviations assertion fails even though the two declared departures (`elab-after-qed`, `check-after-qed` — upstream embeds `clojure.core/meta` in error data; boundary `:nonportable-host-value`, full records in git history at `ea18c8e4^:test/yang/latte_oracle/slice9_evidence.edn`) are still true. Declared dispositions must be carried forward by the writer, not regenerated empty.
2. The two BLOCKED fixtures are `check-accepted` (only the c-walker lane blocks; c-semantic completes) and `check-assume-have-discharge` (both C lanes block; A and B complete). Blocked fixtures are not accepted negatives — they need diagnosis.

Standing constraints: the executed-arity rule (both C-lane traces prove the exact arity) may not be weakened; the four declared exception departures and the two recorded upstream failures (`typing-ascription`, `compile-malformed`) stay declared; no shared-runtime (yang.call / yin.vm) changes without an explicit ruling; instruction budgets are finite (see the evidence's instruction-budget-note).

## What we ask for

The hardening-3 slice plan: slice breakdown with per-slice scope, acceptance criteria, engineering routing, and verification requirements — deciding at minimum:

1. Fixture-closure strategy for the 58 (direct fixtures vs named integrations per family; whether a proof-elaboration end-to-end fixture family can cover the elab-* cluster at once rather than arities one by one; corpus-derived vs hand-built fixtures).
2. The evidence-writer fix (carrying declared dispositions) and whether it lands before or with the first fixture slice.
3. The diagnosis approach for the two blocked fixtures (walker-lane blocking on `check-accepted` smells like a lane-specific instruction/budget or continuation issue — what evidence do you want first?).
4. Slice ordering and what the honest intermediate states are (the gate stays :blocked until the last arity is credited; intermediate evidence refreshes are expected).
5. What would justify RE-OPENING admission counts (the 10 excluded candidates, the 9 excluded bindings) vs leaving them excluded.

First return: the plan. The seat routes engineering rounds from it and keeps your thread for plan reviews.
