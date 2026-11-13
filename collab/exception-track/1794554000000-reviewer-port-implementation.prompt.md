Created-GMT: 2026-11-12 21:58:00 GMT
Created-Local: 2026-11-13 04:58:00 +07 (+0700)
Coding-Agent: qwen
Session-ID: 43797562-f3e2-489c-b310-a1bcbe453f1b
# Task: reviewer — the continuation port, implementation-level (non-codex family, per routing)
Role: Reviewer (implementer families: deepseek-v4-pro Phases 1-3r + codex-terra 3r-b/4 — you are qwen, independent of both)
Implementers:
- Model: qwen3.8-max | Assigned: 2026-11-13 04:58 +0700 | Status: active | Rationale: codex-implemented rounds get non-codex reviewers; sol stayed reserved; you probe-verified available.

## The work under review

Branch `exception-r3-impl` @ **`35d09aaa`** (pushed), cut from post-cc3 master.
Diff of record: `git diff origin/master...35d09aaa` (merge-base form; verify the stat first).
Read-only review. **Do NOT run `yang.call.exception-contract-test` or any suite-scale test**
(shiva-only). Focused `clojure -M:test -n yang.clojure-test` is allowed (laptop-legal; the
implementer reports 103/993 0F/0E — confirm counts, do not trust them).

## Authority chain (what "correct" means — read the rulings, not the code first)

- FINAL DIRECTIVE: `collab/exception-track/1794372000000-owner-decision-r3-vm-retained-seam.md`
  (converge onto continuations; the VM seam stays declined)
- ruling v2 R1-R5 + §8.10.7-§8.10.9 (docs/design/yang.antlr.md)
- the (A) two-class design + mob record + the depth thresholds (CLEARED: 155/155, max 1.20×)

## Review checklist (findings with severity + file:line)

1. **VM purity**: `git diff origin/master...35d09aaa -- src/cljc/yin/vm/` MUST be EMPTY. Any VM
   change is a P0 (the declined seam's territory).
2. **Encoding shape**: does `try/throw/finally` lower to the `%capture` family (clj/try,
   clj/throw, clj/call-ec, handler-stack cell), mirroring py/ts/php — or does any outcome-channel
   residue survive (wrapper forms, outcome tags, routers)?
3. **§8.10.7 boundary**: public entry installs bridge BEFORE argument binding; escaping throw +
   binding failures → raise exit exactly once; §8.10.8 cleanup first; after-call unreachable.
4. **R5**: a guest-thrown condition-shaped map is DATA (fresh `:yang.call/thrown`), never an
   authenticated condition; nothing routes on shape.
5. **finally semantics**: portable try accepts finally; runs on every exit including unwind
   (unwind-to); no ordering violation vs catch/else.
6. **Tail lane**: intra-unit direct calls remain tail (the preserved private lane); bridge frames
   only at published/cross-unit entries — check `mark-tails`/`remark-tails` interaction with the
   new lowering.
7. **Kit integrity**: `test/yang/call/exception_contract.clj` + fixtures UNCHANGED on this branch
   vs master (the acceptance instrument is not the port's to edit).
8. **Test integrity**: terra's Phase 4 coverage restorations (985→993) — any REMAINING deleted or
   weakened expectation across the whole port span? The deleted-coverage pattern was caught twice
   this arc; hunt it.
9. **Source surface**: the one-catch/no-finally restriction lifted — is the widened surface
   documented where the dialect is specified (docs or comments)?

## Deliverable

Per-item verdicts with evidence; findings table (P0-P3); a final line:
**APPROVE** (port ready for shiva + merge request) / **APPROVE WITH FINDINGS** (list, severities)
/ **REQUEST CHANGES** (blocking defects). Begin with Completed-GMT/Completed-Local.
