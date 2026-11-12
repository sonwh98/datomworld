Created-GMT: 2026-11-11 18:00:00 GMT
Created-Local: 2026-11-12 01:00:00 +07 (+0700)
Coding-Agent: deepseek
Session-ID: b91624ce-042f-4720-9173-923be62d570d
# Task: engineer — fix round 2: the admission over-rejects legitimate retained syntax
Role: Engineer (fix round 2 on finding 2; findings 1 and 3 are CLOSED and must not regress)
Implementers:
- Model: deepseek-v4-pro | Assigned: 2026-11-12 01:00 +0700 | Status: active | Rationale: continuity on the same machinery; the remaining defect is precise.

Read first: `collab/exception-track/1794462000000-reviewer-vmprov-fix-confirm-r2.gpt-6-astra.stdout.log`
(the re-verification that found this) and your own fix report. Work in
`/Users/sto/workspace/datomworld-vmprov` on `exception-vm-provenance` @ `566c5450`.

## What is CLOSED (do not regress)

Findings 1 (REPL pre-pass) and 3 (nil mint refusal) are verified fixed. The security closure of
finding 2's repro classes (quoted door packets, assembled yin/def, indirect application) is
verified working — guest-forged door references MUST stay rejected.

## What is OPEN

**The admission blanket-rejects legitimate retained syntax.** Repro (all fail now, all `:ok` on
the base): `(id (let [[x] [42]] x))`, `(id (fn [[x]] x))`, `(id (loop [x 0] x))` with identity
macro `(fn [x] x)` — compiler-generated `dsg__0`/`dsg__loop0` symbols legitimately appear in
macro output when a macro passes through its (already-compiled-eligible) input. Your fix report
claimed "legitimate compiler-generated occurrences live in retained source syntax, never in a
macro's output" — the reviewer disproved that claim with these probes. Also: the diff DELETED
`macro-cannot-construct-the-mint-door-test` (the reviewer re-ran it manually — it still passes).
Restore it alongside the new tests.

## The fix: consult trusted construction provenance, not the prefix

sol's amendment: "Preserve legitimate compiler-generated occurrences through trusted construction
provenance; quoted names alone must confer no authority." The compiler KNOWS which `dsg__` names
it minted: the symbol supply (`::gen` env component, `compiler-sym`/`gen-sym` at clojure.cljc
~903) records every minted name at creation. The admission should validate a `dsg__` symbol
against the SUPPLY REGISTRY (minted → trusted, pass; never minted → forged, reject) rather than
rejecting the prefix outright. Two subtleties to resolve and document:
1. **Macro-output identity**: a symbol minted during the macro's EXPANSION (compiler gensym for
   the macro's own lowering) vs one passing through from the macro's INPUT (retained syntax).
   Both originate from the supply — is the supply alone sufficient, or do you need to distinguish?
   Rule on it with evidence.
2. **Scope**: the supply is per-compile — confirm macro expansion happens within the same compile
   (same env) so the registry is populated before admission.

If the supply registry is insufficient (e.g. admitted programs can collide), say so and propose
the alternative — but the goal is: guest-forged reserved names rejected, compiler-generated
retained syntax preserved, zero blanket rejection.

## Also required

- **Restore `macro-cannot-construct-the-mint-door-test`** (deleted in 566c5450; the reviewer
  verified it still passes) alongside the strengthened tests.
- Add astra's three repro probes as permanent tests: identity-macro passthrough of destructuring
  `let`, destructuring `fn` params, and `loop` — each must remain `:ok`.
- Regression guarantee: findings 1 and 3's tests must stay green; the forged-door rejection tests
  must stay green.

## Verification

`clojure -M:test -n yin.vm.provenance-test -n yin.vm.macro-test` focused + kondo on changed files.
NEVER run the contract test locally. Commit + push `exception-vm-provenance`.

End with Completed-GMT/Completed-Local and: **ADMISSION CORRECTED** or **STILL OPEN: <what>**.
