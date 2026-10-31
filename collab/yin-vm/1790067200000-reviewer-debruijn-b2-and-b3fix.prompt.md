Created-GMT: 2026-09-22 09:33:20 GMT
Created-Local: 2026-09-22 16:33:20 +07 (Indochina Time)
Coding-Agent: cmd
Session-ID: pending (new session, caller-generated)
# Task: reviewer-debruijn-b2-and-b3fix — independent review of B2 and the B3 :push fix
Role: Independent Reviewer
Implementers:
- Model: qwen3.8-max | Assigned: 2026-09-22 16:33:20 +07 | Status: active | Rationale: different model family from both the B2 implementer (claude-sonnet-5) and the B3 :push fix author (the orchestrator itself); read-only review, no edits authorized

Work in /Users/sto/workspace/worktree-debruijn-b2 (your launch directory;
branch debruijn-b2). This is a READ-ONLY, STATIC REVIEW: read the files
named below in full and reason about them directly. Do not edit any file,
do not run `git add`/`commit`/`push`, and do not attempt to run the test
suite or a build (`clojure -M:test`, `clojure -M:kondo`, etc.) -- those
require write access this session does not grant, and the orchestrator has
already run and will re-run them independently. Find defects by reading the
code the way a human reviewer would: trace the logic, check edge cases
against the spec, compare shapes and invariants across files.

## What changed, in order

1. **B2** (new, uncommitted): `src/cljc/yin/vm/debruijn_linearize.cljc` and
   `test/yin/vm/debruijn_linearize_test.cljc` -- the named-datom lowerer
   adapter. Read `docs/design/yin.vm.debruijn.stack.md` sections 1-3 and the
   "B2: named-datom lowerer adapter" phase box first, in full, for the spec
   this must satisfy. This file has not been reviewed by anyone yet.

2. **B3 `:push` fix** (already applied to `src/cljc/yin/vm/debruijn/stack.cljc`,
   the already-merged, already-architect-signed-off VM kernel -- formerly
   `yin.vm.debruijn-vm`, renamed this session, see item 3). B2's own test
   coverage discovered that `step1` had no `:push` case: any program whose
   root lowers an `:application` (which wraps every call operand in a
   `:push` under the named lowering's calling convention) threw stepping
   past its first `:push`. The fix adds a `:push` case that only advances
   `pc`. Read the comment directly above it in the file for the reasoning:
   the named VM (`src/cljc/yin/vm/semantic.cljc`, search `:push` there,
   opcode 22) keeps a separate `val` accumulator distinct from its operand
   stack `St`, so `:push` there commits `val` onto `St`
   (`St <- St ++ [val]`, semantic.cljc around line 275). This stack-native
   machine (`debruijn/stack.cljc`) has no such split: `:const`,
   `:load-bound`, `:load-free`, and `:closure` already `conj` their result
   straight onto `:stack` (see those cases a few lines above the `:push`
   case). Verify this reasoning is actually correct by reading both files
   yourself -- do not take the comment's word for it. Also verify the fix
   does not silently mask a case where B2 or a future caller emits a
   `:push` for a genuinely different purpose than "commit an already-
   pushed value" (i.e., confirm no code path conjures a value onto some
   OTHER register that `:push` would need to move, the way the named VM's
   `val` works).

3. **A rename** (mechanical, done by the orchestrator, not by either
   implementer): `yin.vm.debruijn-vm` -> `yin.vm.debruijn.stack` (file
   `src/cljc/yin/vm/debruijn_vm.cljc` -> `src/cljc/yin/vm/debruijn/stack.cljc`,
   test file similarly moved and renamed), because it is a STACK vm, to
   make room for a planned sibling `yin.vm.debruijn.register` (a register
   VM design, not yet implemented, see `docs/design/yin.vm.debruijn.register.md`
   for context only -- it is out of scope for this review). The design doc
   `docs/design/yin.vm.debruijn-vm.md` was renamed to
   `docs/design/yin.vm.debruijn.stack.md` to match. B1's `yin.vm.debruijn-code`
   and B0's `yin.vm.debruijn-vm-contract-test` were deliberately NOT renamed
   (they are not the VM kernel itself). Verify: every reference to the old
   name is gone by reading each require/ns form and docstring in the files
   this review covers (do not rely on running a search tool if it is
   unavailable to you -- reading the files in full is sufficient), and
   that this worktree's copy of the rename is internally consistent: no
   broken requires, no stale docstring references, no leftover old name
   anywhere in the files you read.

## What to read

Read every file named above, in full, not just a diff: `src/cljc/yin/vm/debruijn_linearize.cljc`,
`test/yin/vm/debruijn_linearize_test.cljc`, `src/cljc/yin/vm/debruijn/stack.cljc`,
`test/yin/vm/debruijn/stack_test.cljc`, `src/cljc/yin/vm/semantic.cljc` (just
the `:push` case, opcode 22), and `docs/design/yin.vm.debruijn.stack.md`
sections 1-3 and the B2 phase box. Local test/lint execution is out of
scope for this review (the orchestrator handles that); find defects purely
by reading.

## Verdict

READY / READY WITH CHANGES / NOT READY, findings as P1 (must fix before
commit) / P2 (should fix) / P3 (nice to have). Be specific: file, line,
concrete failure scenario for every P1/P2.

## Final report

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: cmd
Session-ID: <your session id>
Then the verdict and findings. Facts only; you ran what you ran, say
exactly what you ran and its output counts.
