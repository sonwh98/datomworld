Created-GMT: 2026-09-09 16:46:10 GMT
Created-Local: 2026-09-09 23:46:10 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a0868e-e9f2-7242-92e8-58d63e7f9574 (resumed)
# Task: confirm round — dao.space.schema v2 migration plan, r2
Role: Routine Review

**Read-only. Print to stdout; write no file.** This resumes your review of the
r1 plan. The Architect revised it in place; the file you reviewed is now r2:
`collab/1788962937302-architect-space-schema-v2-plan.claude-fable-5-1.findings.md` (948 lines, header dated 21:46).

You requested changes on four counts. How each was settled, in the plan's own
terms — verify, do not take my word:

1. **D4 (your P1).** Rewritten. Schema still rejects `:gap`/`:defect` but now
   states in those words that this detects an **observed read failure**, not
   prior prefix loss. Completeness becomes the caller's declaration — a
   complete-retention transport or a kept origin cursor — the same
   "declared, never interrogated" position as the transactor's T18. V13 is
   split into V13a/V13b over scripted readers; a **new V15** pins the
   undetectability itself as a documented limit.
2. **D1 ordering (your P2).** Aligned with `tx/transact!` rather than made a
   schema-specific rule: empty tx-data throws above the lock, everything else
   answers `closed` first. L10 carries the "when the wrapper is open"
   qualifier.
3. **Arithmetic (your P2).** Recomputed: source 16 → 14 → 0, tests 68 → 6 → 0,
   22 receipt lines rewritten including both `:1062` and `:1071`, five
   deftests deleted, deftest count 70 → 72. Closure grep targets require forms.
4. **Vacuous tests (your P2).** The close test now asserts `closed` on the
   inner value directly. V14 wraps a counter onto the opened store's
   `:close-fn` and asserts zero closes before the owner's `close-published!`.

Also fixed: the Phase 2 `finally` scoping you flagged, and P4's pins are now
cited by test name including `published-index-constructor-validates-its-arguments`.

## What I verified myself, so you need not

`jing/close!` delegates to the handle's `:close-fn` (`jing.cljc:318-325`) and
`open-published!` returns `{… :store … :close-guard …}`, so V14's seam is real
and host-neutral. Ringbuffer `:oldest` is `(:first s)` and `gap` fires only
when `pos < (:first s)`. `memory-log`'s `:oldest` is pinned to 0 for the
stream's life. `schema_test` has 70 deftests today.

## Your question

Does r2 discharge each of your four findings, or does any survive in a new
form? Is D4's limit **honestly stated** — a reader of `dao.space.schema.md`
alone would understand what schema does and does not guarantee — and is V15 a
real pin rather than a comment? Are the recomputed numbers right this time?
Check them from the plan's own build/delete lists.

If it is clean, say so plainly and say it is implementable. Do not manufacture
a finding to justify a second round.
