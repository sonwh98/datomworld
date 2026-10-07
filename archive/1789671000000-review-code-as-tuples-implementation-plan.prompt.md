Created-GMT: 2026-09-17 15:56:40 GMT
Created-Local: 2026-09-17 22:56:40 +0700 (Asia/Ho_Chi_Minh)
Coding-Agent: agy
Session-ID: pending (provider-generated)
Role: Adversarial Review

Implementers:
- Model: gemini-3.1-pro-high | Assigned: 2026-09-17 22:56:40 +07 | Status: active | Rationale: massive cross-document analysis strength, reviewing a Claude-family drafter

# Task: Review docs/design/yin.vm.code-as-tuples.implementation-plan.md

This is a brand-new implementation plan (696 lines, uncommitted, working
tree), drafted by the Architect (claude-fable-5-1) against
`docs/design/yin.vm.code-as-tuples.md` (2094 lines). Read the plan in
full. For grounding, read `docs/design/yin.vm.code-as-tuples.md` in full
(especially §2, §5.2.1, §6.5, §7, §8.4-8.5, §9, §10), and skim
`docs/design/yin.vm.semantic.md` (748 lines), `docs/design/yin.vm.macro.md`
(1321 lines), and `docs/design/yin.vm.universal-continuation-format.md`
(1331 lines, currently untracked in git — confirm this yourself with `git
status`).

## Context

The orchestrator's original brief to the Architect claimed the map↔rows
codec this whole design depends on was entirely unbuilt. The Architect's
draft corrected this: the codec (`ast->semantic-bytecode`/
`semantic-bytecode->ast`) exists at `src/cljc/yin/vm.cljc:590-826`,
committed as `84f8eef` on 2026-09-16, with the round-trip law already a
passing test (`test/yin/vm_test.cljc:266`). The orchestrator
independently verified this correction: both commits exist as described,
the `:global` tag removal (`c5cea20`) is confirmed, and
`yin.vm.universal-continuation-format.md` is indeed untracked (`??` in
`git status`).

The plan classifies the source design's 14 §10 acceptance-blocker items,
finds a 15th open question the design doesn't list (D3, row storage
grain) and a topology ambiguity between §7.1 and §6.1/§9.1 (D2), and
proposes 16 units across a Phase 0 (doc corrections) and three phases:
Phase 1 (7 units, "buildable now, no decision pending," ~3 weeks), Phase
2 (4 units, "mechanical once a decision lands," ~1 month once 4 decisions
are made), Phase 3 (5 units, "genuinely speculative architecture," 2-3
months against two other documents themselves marked Proposed/unbuilt).
It names six owner decisions (D1-D6), four with stated defaults.

## Task

This plan makes strong, specific claims about exactly what exists and
what doesn't across a very large codebase and four large design
documents. Focus your review on:

1. **Verify the "Built" census table is accurate** — for each row, check
   the cited file/line range actually contains what's claimed and the
   cited tests actually pass and test what they're claimed to test. Pay
   particular attention to the claim that `engine/bind-params`
   (`engine.cljc:46-51`) already implements §7.7.2's nil-fill binding, and
   that `dao.jing/segment-key` (`jing.cljc:271-310`) already closes the
   three §4.2 conformance pairs.
2. **Verify the "Missing — implementable now" table (U1-U7) really has no
   hidden dependency on a Phase 2/3 decision.** The plan claims Phase 1
   needs no decision — check this is actually true, not just asserted,
   especially for U4 (lowering) and U7 (occurrence relation), which touch
   territory (`origin`, provenance) the plan says elsewhere is gated by
   D4.
3. **Judge D1-D6's stated defaults** — are they genuinely low-risk
   defaults consistent with what's already built and decided elsewhere in
   the codebase, or does any of them quietly foreclose an option the
   owner would actually want? Pay particular attention to D2 (which
   topology the walker attaches to) since the plan itself says the three
   options differ in real, non-cosmetic ways (whether an evaluator can
   run an unprojected map, what §7.2's tests compare).
4. **Check the two "traps" the plan calls out** (U4's `:call`/`tailcall`
   folding asymmetry, U2's `data`/`key`-in-metadata check) against the
   actual current code at the cited locations — are they real risks or
   overstated?
5. **Judge the overall estimate calibration** — three weeks for Phase 1,
   a month for Phase 2, two to three months for Phase 3. Does the
   evidence in the plan support that relative sizing, or does anything
   look understated (especially U4, which the plan itself calls "the
   largest Phase 1 unit and the one with a real chance of an ordering
   bug")?
6. **Confirm the Boundary table and Phase-3 "owed elsewhere" framing is
   honest** — does anything in Phase 1 or Phase 2 secretly depend on one
   of the Phase-3 items despite the plan's dependency graph saying
   otherwise?

## Deliverable

A findings list, most severe first, and an explicit verdict: ready for
Architect/owner sign-off, or not (with what must change first). Do not
edit any file.
