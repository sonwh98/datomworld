# Note to the owner: C4 freeze window + two 8f/8g rulings needed before slice 8e starts (DRAFT)

Created-Local: 2026-10-15 00:15:00 +07
From: semantic-register-vm orchestrator (muse seat, phase-8 track)
To: owner (please relay the freeze to the yang.python orchestrator)

## 1. Freeze request (needed before 8e starts, lead time ~1 week)

Phase-8 plan risk 1
(`collab/1792560000000-architect-phase8-plan.report.md:221`): slices 8e and
8f mechanically edit 10 shared C4 files (37 test files total in 8e; the 56-file
rename in 8f), with `linked_harness.cljc` as the shared hinge. Request: the
yang.python seat pauses commits to those files for the 8e+8f duration, while
this track edits them only as mechanical API substitution with no behavioural
change. The C4 suite over newly published images (`bb test:sub yang.python
--slow`) is the proof. Proposed trigger: the day 8e dispatches (8e needs 8c;
8c needs the 8ab merge now in its fix round).

## 2. Owner ruling Q1: who edits the 10 C4 files, and when

Plan recommendation is (a): this track edits them in 8f under the frozen
state, because the change is mechanical (harness's three backend lambdas +
loader) and a two-party edit of one file is the worst conflict shape. Please
confirm (a), or direct (b) (they port against a published 8c/8e API on a C4
branch, 8f merges both). Also: are the C4 goldens ours to move (derivations
§8.1: publish twice and diff; the review must show the diff)?

## 3. Owner ruling Q6 (8g): fate of `docs/design/yin.vm.semantic.md`

It describes the v3 machine being deleted. Plan recommendation is (c): rename
`yin.vm.semantic-register-vm.md` to `yin.vm.semantic.md`, delete the old,
plus a one-paragraph history block. Please confirm (c), (a), or (b) any time
before 8g — early ruling lets 8g land in one pass.

## What this seat will NOT do

No edits to `test/yang/`, C4 worktrees, or the other seat's uncommitted docs
from this track. 8e/8f briefs will carry the freeze dates once the owner sets
them.
