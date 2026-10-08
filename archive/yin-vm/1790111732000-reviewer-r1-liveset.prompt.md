Created-GMT: 2026-09-22 19:35:32 GMT
Created-Local: 2026-09-23 02:35:32 +07 (Indochina Time)
Coding-Agent: deepseek
Session-ID: pending (new session, caller-generated)
# Task: reviewer-r1-liveset — independent review of the committed R1 + live-set register lowerer
Role: Independent Reviewer
Implementers:
- Model: deepseek-v4-pro | Assigned: 2026-09-23 02:35:32 +07 | Status: active | Rationale: independent family from the implementer (claude-sonnet-5); per the project's new commit-then-review workflow (docs/agents/roles/orchestrator.md), this reviews the committed diff directly

Work in /Users/sto/workspace/worktree-register-r0 (your launch directory;
branch register-r0). This is a READ-ONLY, STATIC REVIEW. Do not edit any
file, do not run `git add`/`commit`/`push`, and do not attempt to run the
test suite or a build -- the orchestrator has already run and verified
this independently on all three hosts (JVM 1834 tests / 175723
assertions, CLJS 1751 / 45521 with 5 pre-existing unrelated failures,
CLJD 1713, all clean). Find defects by reading the code.

## Context

This is the register VM's R1 phase (the lowerer, allocator, descriptor,
and validator) plus an in-session extension: per-call-site live-register-
set tracking (contract version 1 -> 2), added because a saved
continuation should carry only registers still needed, never dead ones.
It is committed as `git show HEAD` (the tip of this branch,
"Add R1: the register dimension and lowerer, with live-register-set
tracking"). This has not been reviewed before; review the whole commit,
not a diff against a prior review.

## Read first, in full

- docs/design/yin.vm.debruijn.register.md, in full. Sections 1-4.5 are
  your spec: the objective, the descriptor (section 3), the lowering
  algorithm and register banks (4.1-4.3), the instruction mapping (4.4),
  and live-set tracking (4.5, the newest section -- read it slowly, it
  is the hardest part of this phase). Section 6's R0 and R1 boxes are
  your exact completion criteria.
- test/yin/vm/debruijn_register_contract_test.cljc (R0, this commit's
  sibling, frozen): the operand-mapping table, the two-way address law,
  the frozen `register-contract-version` (still 1, deliberately, per
  section 4.5's own deferral of that re-pin to a later phase).
- src/cljc/yin/vm/debruijn_register_code.cljc, in full: the descriptor,
  opcode table (note `:call`'s sixth operand, `live`), `register-hash`,
  and the validator -- especially `register-bounds-rule` and
  `body-scope-rule` (no B1 equivalent) and the four new live-set rules
  (`:live-shape`, `:live-bounds`, `:live-tail`, `:live-exact`).
- src/cljc/yin/vm/debruijn_register_compile.cljc, in full:
  `lower-register`, the allocator, `body-liveness`, and the lift.
- test/yin/vm/debruijn_register_compile_test.cljc, in full: the
  completion-criteria tests, including the hand-derived live-set
  fixtures (search for "fixture-" -- there are at least five, matching
  design section 4.5's required list: all-dead, live-across-a-branch,
  live-in-one-arm-not-the-other, nested-non-tail-calls, tail-call-always-
  empty).
- src/cljc/yin/vm/debruijn_code.cljc (B1, merged): the precedent this
  phase's descriptor/validator/hash structure follows.
- src/cljc/yin/vm/debruijn_linearize.cljc (B2, merged): the precedent
  this phase's lowerer/lift structure follows (`lower-stack`, `lift`).

## What to check specifically

1. **`body-liveness`'s correctness.** Design section 4.5 specifies a
   standard backward liveness dataflow: use/def per mnemonic, a
   successor rule, `live-in(p) = use(p) + (live-out(p) - def(p))`,
   `live-out(p) = union of live-in(s) over successors`, iterated to a
   fixpoint. Trace the actual implementation against this specification
   line by line. Is the use/def table exactly what section 4.5 lists?
   Does the successor rule handle `:jump`, `:branch-false`, `:return`,
   `:halt`, and tail `:call` correctly? Is the fixpoint iteration
   actually correct for a body containing a loop-shaped control flow (a
   `:jump` backward to an earlier pc) -- construct one by hand if the
   test corpus does not already cover it, and check whether the
   implementation would compute the right answer.
2. **The four validator rules.** `:live-shape` (ascending, duplicate-
   free), `:live-bounds` (within the body's register count),
   `:live-tail` (empty on tail calls), `:live-exact` (matches
   `body-liveness`'s own recomputation) -- verify each is actually wired
   into the validator's rule sequence and actually tested with a hand-
   built malformed fixture, not just exercised incidentally by valid
   images.
3. **The hand-derived fixtures' correctness.** For each fixture testing
   live-set values, work out by hand (independently, not by trusting the
   test's own comment) what the correct live set should be at each
   non-tail call, and confirm the test's asserted expected value is
   actually right -- this is exactly the kind of self-consistent-but-
   wrong bug a single implementer session can introduce and not notice.
4. **The allocator's determinism**, per design section 4.3: virtual-
   value definition order is evaluation order, register selection is
   lowest-available, tie breaks are lowest virtual-id, no host map/set
   iteration order affects output. Confirm the actual data structures
   used (sorted sets, vectors) genuinely guarantee this, not just
   incidentally produce deterministic output on the current test corpus.
5. **The lift law and its trust rule.** Confirm the lift follows B2's
   exact trust rule (synthesized names by default; a supplied side table
   trusted only after arity match, capture-freedom, and byte-for-byte
   round-trip verification) with no weakening.
6. **The ClojureDart portability fix**, mentioned in the commit message:
   three R0 helpers (`resolved-addresses-of`, `stack-addresses-of`,
   `address-law-extra-fixtures`) were made public because `#'ns/private-
   var` cross-namespace reflection fails at runtime on CLJD. Confirm this
   fix is complete (no remaining `#'` usage anywhere in either test file)
   and that making these three specific things public did not
   inadvertently expose anything R0's own file box should keep private.
7. Anything else a fresh pair of eyes finds: register-bounds/body-scope
   validator gaps, an off-by-one in the allocator, a case in the
   instruction mapping (4.4) that silently diverges from what the
   descriptor's opcode table declares.

## Verdict

READY / READY WITH CHANGES / NOT READY, findings as P1 (must fix) / P2
(should fix) / P3 (nice to have), each with a concrete failure scenario
or file:line, not a vague concern.

## Final report

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: deepseek
Session-ID: <your session id>
Then the verdict and findings. Facts only, each claim checked against
the actual code, not assumed.
