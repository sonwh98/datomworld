Created-GMT: 2026-09-22 20:03:20 GMT
Created-Local: 2026-09-23 03:03:20 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: pending (new session, caller-generated)
# Task: reviewer-r1-liveset-confirm — confirm the fix closes deepseek-v4-pro's prior findings
Role: Independent Reviewer
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-23 03:03:20 +07 | Status: active | Rationale: deepseek-v4-pro AND glm-5.3 both hard-failed model routing (broader non-Anthropic gateway outage, confirmed by a live fable sanity check succeeding); claude-fable-5-1 substituted to avoid blocking further on a routine confirm-fix review; weaker family diversity from the claude-sonnet-5 implementer is accepted here

Work in /Users/sto/workspace/worktree-register-r0 (your launch directory;
branch register-r0). READ-ONLY. Do not edit any file, do not run `git
add`/`commit`/`push`. Do not run the test suite -- the orchestrator has
already run it directly (1836 tests, 175904 assertions, 0 failures/
errors, JVM host).

## Context

deepseek-v4-pro reviewed commit 483bbffc earlier
(collab/1790111732000-reviewer-r1-liveset.deepseek-v4-pro.stdout.log has
its full prior report -- read it in full) and found no P1s, two P2s, and
two P3s. The first P2 (stale worktree design doc) was already fixed in
ed50ca0c before that review ran. The second P2 (no cross-body
jump/branch-false validator check) and both P3s (unbacked
:move/:store-get/:store-put coverage claim; an off-by-one
register-numbering comment) are addressed in the new commit
`git show 0e497a44`. Read that diff in full. You were not the original
reviewer -- deepseek-v4-pro is currently unavailable, so you are
confirming its findings as a substitute; treat its report as the spec
for what must be closed, and use your own independent judgment on
whether the fix actually closes it.

## What to check

1. Does the new `jump-scope-rule` (in
   src/cljc/yin/vm/debruijn_register_code.cljc) actually close your P2?
   Specifically: does it reject a `:jump` or `:branch-false` whose target
   lies in a different body than the instruction itself, using each pc's
   owning body (via `owner-of` or equivalent)? Is it wired into the
   validator's rule sequence before the live-set rules, as your review
   requested? Is the hand-built cross-body-jump test fixture actually a
   valid regression test for this (does it construct a genuinely
   cross-body target, not something already caught by an existing rule
   like `target-bounds-rule`)?
2. Does the new test in
   test/yin/vm/debruijn_register_compile_test.cljc (search for
   "move-and-store-mnemonics-are-never-emitted" or similar) actually
   iterate every emitted instruction across the full register-lowerable
   corpus and assert none is `:move`/`:store-get`/`:store-put`, closing
   your first P3? Or is it a narrower/weaker test than what you asked
   for?
3. Is the off-by-one comment (near where `f`/`x` register numbers are
   named in a lambda-body fixture) actually corrected to reg2/reg3 (not
   reg1/reg2), closing your second P3?
4. Any new defect introduced by this fix itself (e.g. `jump-scope-rule`
   rejecting a legitimate within-body backward jump, or being positioned
   wrong relative to other rules such that it never actually runs).

## Verdict

READY / READY WITH CHANGES / NOT READY. For each of deepseek-v4-pro's
four original findings (1 P2 + 2 P3, since the doc-sync P2 is out of
scope here), state explicitly: CLOSED, PARTIALLY CLOSED, or NOT CLOSED,
with why. Flag any new defect the fix itself introduces.

## Final report

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: <your session id>
Then the verdict and per-finding disposition. Facts only, each claim
checked against the actual code, not assumed.
