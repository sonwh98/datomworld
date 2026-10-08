Created-GMT: 2026-09-16 04:24:15 GMT
Created-Local: 2026-09-16 11:24:15 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: 01a0a6df-fb45-7122-aab4-06049faa93ba
# Task: Review the :global retirement (docs) — a design ruling reversal
Role: Routine Review
Implementers:
- Model: gpt-6-astra | Assigned: 2026-09-16 11:24:15 +07 | Status: active | Rationale: same reviewer, resumed session, deep prior context on this exact file

## Context

The owner ruled tonight to retire the `:global` AST tag entirely, after a
live design discussion (not written up as a separate collab artifact —
the reasoning is now captured directly in the doc). Read the new §4.5
section in full (`docs/design/yin.vm.code-as-tuples.md`, "Free variables
are queried, not tagged") for the complete rationale before reviewing
anything else.

This is a large, mostly-mechanical revert (21 reference sites) plus two
new substantive additions: §4.5 itself, and a correction to §4.2/§10
item 1 (the `dao.jing` encoder was described as blocking identity uses;
it's fixed now, per tonight's earlier committed `0cafb2d`/`33fdded` — the
block is lifted for the three structural conformance pairs, residuals
remain open per `dao.jing.md`'s Open Items).

Also read `docs/design/datom.world.md`'s diff — a new "Derive, don't
persist" principle was added to its Design Principles section, pointing
back at this case study.

## Task

1. **Completeness of the revert.** `git diff -- docs/design/yin.vm.code-as-tuples.md`
   and a fresh `grep -n ":global"` over the current file — confirm every
   remaining hit is inside §4.5 describing the retired concept
   historically, and nothing outside §4.5 still assumes `:global` exists
   as a current or future tag. Sweep the WHOLE file, not just the diff
   hunks, the way your prior reviews tonight did for the terminology
   cleanup — a stale reference outside the diff is exactly the kind of
   thing that survived your first two passes on that earlier cleanup.
2. **§4.5's honesty.** This section went through several corrections
   during the live discussion before landing (the author twice caught
   themselves overclaiming/misclaiming things — once wrongly saying
   `:global` wouldn't have helped with a row-sharing edge case when it
   actually would have, then wrongly calling that edge case an
   unrecoverable cost when a fuller query resolves it, before landing on
   the current text). Read it with real scrutiny for a third mistake:
   does the current text's soundness argument actually hold up? Are the
   two cited tests (`test/dao/space/query_test.cljc`'s free-name and
   occurrence-aware demonstrations) accurately described?
3. **The §4.2/§10 correction.** Does the rewritten text accurately
   reflect what `src/cljc/dao/jing.cljc` (current committed state,
   `0cafb2d`) actually does? Check the specific conformance-pair claims
   against the code, not just against the prose.
4. **The `datom.world.md` principle.** Is it stated at the right level of
   generality — general enough to apply beyond this one AST, precise
   enough to be actionable?

## Boundaries

Read-only. Do not edit anything.

## Deliverable

Report: pass/fail per the four checks, exact citations for anything
flagged, explicit verdict — safe to commit as-is. Produce the complete
deliverable now.
