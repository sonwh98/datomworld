Created-GMT: 2026-11-19 18:20:00 GMT (session clock)
Created-Local: 2026-11-21 04:20:00 +07 (Asia/Ho_Chi_Minh)
# Task: yang-preludes-p0prime-step7-confirmation
Role: Reviewer (step-7 — fresh session; the prior sol thread's rollout was pruned by codex 0.170, so this brief is self-contained)

Confirm the three step-7 P1 fixes on slice **P0′** of the signed-off `yang.preludes.md` r8
design (read it in your cwd — it is the landed master's state). The 19-file patch (950+ lines,
4 NEW files) is piped after this prompt. Read-only; your final response is the deliverable.

## The three P1 fixes to confirm

1. **Profile-collision bypass (was reproduced):** `validate-profile` collected duplicate
   definitions only from `(:operands ast)` — a linear `u/then`-sequenced tree hid the second
   `(u/def! 'x …)` inside the sequencing lambda. Fixed: module-level definitions are now
   collected across sequenced AND balanced shapes (or unsupported shapes rejected), preserving
   literal and nested-function boundaries. A collision-bypass regression was added to
   `test/yang/core/prelude_admission_test.cljc`.
2. **`integer/compare-float-boxed`** added to `yin.vm.integer` (boxed inputs; the float
   unwrapped only inside the host comparison; the integer operand exact; legacy
   `to-float`/`true-div` ABI unchanged). Required by the r8 design §7.2 and the
   VM-engineer amendment.
3. **The float and admission test files** (`test/yin/vm/float_test.cljc`,
   `test/yang/core/prelude_admission_test.cljc`) are now IN the patch (they previously
   existed untracked and were omitted from the reviewed export — an export artifact, since
   corrected).

## Context

- The P0′ slice implements the signed-off r8 design §9 P0′: `:pure` host core additions
  (`data/equiv`, `admit-key?`, `canonical-compare`/`canonical-entries` with Jing
  encoded-byte normative order, boxed NEW `yin.vm.float`), `yang.core.defquoted`
  consolidation, recipe/profile schemas + `validate-profile`/`admit-profile` in
  `yang.core.prelude-admission` (5 publics; `core.prelude` pure at 11), migration recipe
  records, integer boxed exports.
- **Disclosure — one pre-existing master red:** `yang.python.antlr.linked-prelude-test`
  fails on master itself (count assertion 71 vs 72: `py.b/AssertionError` was added by
  commit `60cda9b6` without updating `linked_prelude_test.cljc:191`). It predates and is
  unrelated to this diff (the P0′ changes do not alter the python runtime-key tables —
  verified); relayed to the owning seat. Treat it as out of scope.
- Gate evidence: JVM admission+data/integer/float 62/1,039; Node 62/1,041; frozen net
  excluding the pre-existing red: green; analyzer self-loops 3→0; diff --check clean.

## Your task

Verify the three fixes in the patched content: the collision collection covers the
sequenced shape (the bypass case), the boxed comparison export preserves the exact integer
operand and unwraps only inside the host op, the regression test actually exercises the
bypass, and the test files are complete. Scope discipline: nothing beyond C4/C5-equivalent
P0′ scope. Begin exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

End with exactly one line:
APPROVED | CHANGES STILL REQUIRED — <why> | PROOF FAILS — <why>
