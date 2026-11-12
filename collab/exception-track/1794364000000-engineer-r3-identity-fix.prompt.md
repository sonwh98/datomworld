Created-GMT: 2026-11-10 22:05:00 GMT
Created-Local: 2026-11-11 05:05:00 +07 (+0700)
Coding-Agent: agy
Session-ID: pending (provider-generated)
# Task: engineer — R3 identity regression fix (argon CHANGES STILL REQUIRED on db2434dd)
Role: Engineer
Implementers:
- Status-Event: 2026-11-11 05:22 +0700 | Model: gemini-4-argon-high | Status: failed | Rationale: AGY quota exhausted (429, resets 128h) after 1.1ks.
- Model: muse-wonder-1.0 | Assigned: 2026-11-11 05:37 +0700 | Status: active | Rationale: argon quota-exhausted; deepseek-flash already failed this unit once (identity regression its own tests could not see); muse-wonder is team.md's long-horizon multi-file tier.

## State

Worktree `/Users/sto/workspace/datomworld-r3-orch`, branch `exception-r3-impl` @ `db2434dd`.
Read argon's confirmation first: `collab/exception-track/1794363200000-reviewer-r3-fix-confirm.gemini-4-argon-high.stdout.log`.
Files you may change: `src/cljc/yang/clojure.cljc`, `test/yang/clojure_test.clj`. Nothing else.
Commit on that branch only; the shared main checkout is not yours.

## What is confirmed good (do not disturb)

- P1-1 (token transplant) — CLOSED: the closure-witness mechanism authenticates complete contents.
- P1-3 — CLOSED: original condition round-trips byte-identical.
- P2-4 — CLOSED: assertions pin the outer contract.
Keep the wrapper-as-cell design; keep the witness; keep `dsg__condition_retained`.

## What must be fixed (P1)

1. **Payload identity is broken across dynamic calls.** `try` binds `e` to the wrapper `:cell-ref`
   (`clojure.cljc:1220`), so `(= e original)` is false and `[:clojure :python :round-trip]`
   regresses green→red. EVERY guest operation on a caught value must observe `dsg__visible`, not
   the wrapper: `=`, `get`, keyword lookup, predicates, `ex-message`, `.getMessage`, `ex-data`.
2. **Nested wrappers.** `dsg__condition_wrapper` (`:931-935`) must not nest a wrapper as `visible`;
   normalize through argon's `dsg__visible_of` helper so `ex-message` on a rethrown caught wrapper
   answers the original message, not nil.
3. **P3 shape-guard:** `condition-retained-form` (`:940-941`) should check the candidate's cell
   contents are a 3-vector BEFORE `cell/authentic?` (mirror `dsg__admitted?`'s discipline).

Argon's "Minimal Disposition" section gives concrete code — follow it, but you own the design
judgment on where the unwrap belongs (its option (a) normalization at wrap time vs (b) routing
`=`/`get`/keywords through `dsg__visible_of`; do BOTH if both are needed for identity).

## Tests you must add (the ones the previous round could not see)

- `(= e orig)` true across a DYNAMIC call where `orig` is a location-independent value, and
  specifically `(= e original)` with `original` a `cell/new` value (mirrors exception_legs.clj:44).
- `(:yin.k/cause e)`, `(:yin.k/message e)`, `(get e :yin.k/data)`, a custom key, a predicate on `e`.
- `(:yang.call/reason e)` on a caught protocol condition.
- Nested: `capture` → rethrow of the caught value across a dynamic call → `(ex-message e2)`.
- **And a cross-language regression guard**: a clojure catch receiving a `:yang.call/thrown`
  condition whose `:yang.call/value` is a `cell/new` value must see identity preserved.

## Verification

- `clojure -M:test -n yang.clojure-test` — green.
- `clj -M:kondo --lint src/cljc/yang/clojure.cljc test/yang/clojure_test.clj` — 0/0.
- **DO NOT run `yang.call.exception-contract-test` locally** — suite-scale, shiva-only (owner ruling
  2026-11-04). The orchestrator runs it on shiva.
- Commit and push `exception-r3-impl`. FOREGROUND work only, no background server tasks.

Begin the final response exactly with Completed-GMT/Completed-Local, then: what you changed
(file:line), the identity semantics now guaranteed, test names, command counts, and the commit SHA.
