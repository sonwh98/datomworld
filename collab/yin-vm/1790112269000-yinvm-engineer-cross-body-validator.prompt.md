Created-GMT: 2026-09-22 19:44:29 GMT
Created-Local: 2026-09-23 02:44:29 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: pending (new session, caller-generated)
# Task: register-cross-body-validator — close a real validator gap plus two minor findings
Role: yin.vm / Interpreter Engineer
Implementers:
- Model: claude-sonnet-5 | Assigned: 2026-09-23 02:44:29 +07 | Status: active | Rationale: fresh session; fixing findings against already-committed R1 code from an independent review

Work in /Users/sto/workspace/worktree-register-r0 (your launch directory;
branch register-r0, HEAD includes R0, R1, and live-set tracking,
committed -- confirm with `git log --oneline`). Do NOT stage, commit,
merge or push.

## Context: an independent review found two real findings

Full review at
collab/1790111732000-reviewer-r1-liveset.deepseek-v4-pro.stdout.log --
read it in full. Its first P2 (a stale design doc missing section 4.5 in
this worktree) is already fixed in a prior commit on this branch; ignore
it, it is not your job. Your job is the SECOND P2 and the two P3s.

## P2: the validator does not reject a cross-body jump/branch target

`body-scope-rule` (src/cljc/yin/vm/debruijn_register_code.cljc, search
for it) checks body shape, contiguity, coverage, and closure targets --
but never checks that a `:jump` or `:branch-false` target lies inside
ITS OWN body's `[start end]` range. `target-bounds-rule` only bounds a
target against the whole instruction vector's length, not against the
target's own enclosing body. A hand-built image with a `:jump` from
body 0 into body 1's interior therefore passes every current rule, and
`body-liveness` then silently computes wrong liveness for it (each
body's dataflow pass only updates its own pcs, so a cross-body successor
reads an empty `live-in`).

Read B1's direct precedent for this exact class of check: `scope-defect`
in `src/cljc/yin/vm/debruijn_code.cljc` (and `all-body-chains` if that is
where the actual chain-reachability logic lives) -- it rejects a pc
reached under two different scope chains for the stack format. The
register validator already has body ranges available (via whatever
`owner-of`-equivalent helper the code uses to look up which body a pc
belongs to); add a rule that every `:jump` target and `:branch-false`
target is within the SAME body as the instruction itself (compare each
target pc's owning body to the instruction's own owning body, refuse if
they differ). Wire it into the validator's rule sequence in the same
position family as `target-bounds-rule`/`body-scope-rule` (before the
live-set rules, since a cross-body jump makes `body-liveness` itself
produce meaningless results the live-set rules should never be asked to
check).

Test with a hand-built image containing a `:jump` (or `:branch-false`)
whose target is a valid instruction-vector index but belongs to a
different body than the instruction itself -- confirm the new rule
refuses it with a named diagnostic, and confirm every existing valid
corpus image still passes (the rule must not reject legitimate
within-body backward jumps, if any exist in the corpus, or legitimate
`:closure` targets, which are a different operand already checked
elsewhere).

## P3: a docstring overclaims test coverage

`debruijn_register_code.cljc` (search for text near "`:move` is also
declared but never emitted") claims both `:move`'s absence and some
other fact are "asserted by this phase's own tests, not left as a silent
absence." No test actually asserts `:move`/`:store-get`/`:store-put`
absence from any emitted image today (the review confirmed: the only
`store-get`/`store-put` hits in the test file are the deferred-node-type
refusal tests, which test something else). Either add a real test
(iterate every emitted instruction across the full register-lowerable
corpus and assert none is `:move`/`:store-get`/`:store-put`) or correct
the docstring to state plainly that this is true by construction of the
lowerer's own logic (no code path ever emits them) rather than claiming
test coverage that does not exist. Prefer adding the test if it is cheap
(it should be -- a single `doseq` over the existing corpus).

## P3: a fixture comment has off-by-one register numbers

`test/yin/vm/debruijn_register_compile_test.cljc` around lines 500-507
(search for the comment naming register numbers for `f`/`x` in a lambda
body): the comment says `f` lands in reg1 and `x` in reg2, but the
lowerer actually reserves temp 0 for the body's own return register
(so with `locals 1`, `f` is reg2 and `x` is reg3). The test's actual
assertions are correct (it locates the call by its tail-flag, not by a
hardcoded register number), so this is comment-only. Fix the comment to
state the correct register numbers, so a future reader hand-deriving
from it is not misled.

## Completion criteria

- The cross-body validator rule exists, is wired into the rule sequence,
  and is tested with a hand-built cross-body-jump fixture that it
  correctly refuses.
- Every existing test in `debruijn_register_contract_test.cljc` and
  `debruijn_register_compile_test.cljc` still passes -- the new rule
  must not be a false-positive against any legitimate image the corpus
  already produces.
- The `:move`/`:store-get`/`:store-put` absence claim is either backed by
  a real test or the docstring is corrected to not claim one.
- The off-by-one comment is fixed.
- Full JVM suite still green; kondo/cljstyle clean.

## Never

Do not touch the design documents (already fixed and synced in a prior
commit on this branch). Do not touch `yin.vm.linearize`, `yin.vm.code`,
`yin.vm.completion`, `yin.vm.ast_walker`, `yin.vm.debruijn`,
`yin.vm.debruijn-resolve`, `yin.vm.debruijn-linearize`,
`yin.vm.debruijn-code`, or `yin.vm.debruijn.stack` -- only READ from
them. Do not implement anything beyond these three findings. Keep files
pure ASCII, no em dashes, cljstyle-style Clojure.

## Environment

Default PATH gives Java 21 and the mise clojure and bb. This worktree is
already `mise trust`ed. Focused JVM run: `clojure -M:test -n
yin.vm.debruijn-register-contract-test -n
yin.vm.debruijn-register-compile-test`.

## Final report

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: <your session id>
Then: the new validator rule's exact logic and its test; how the
:move/:store-get/:store-put finding was closed (test added, or docstring
corrected -- name which); the comment fix; exact test counts before/
after; what you ran; every deviation. Facts only.
