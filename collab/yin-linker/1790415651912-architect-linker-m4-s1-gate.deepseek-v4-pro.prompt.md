Created-GMT: 2026-09-26 09:40:51 GMT
Created-Local: 2026-09-26 16:40:51 +0700
Coding-Agent: deepseek
Session-ID: 5ddc5a4f-d312-4e99-ae06-04971babf2ca

# Task: gate for M4 slice S1, kernel attach-image in the four backends (Claude-authored)

Role: Adversarial Code Reviewer and Security Auditor + Lead System Architect (combined commit gate)

Implementers:
- Model: deepseek-v4-pro | Assigned: 2026-09-26 16:40:51 +0700 | Status: active | Rationale: owner directive "deepseek for both, then commit and merge"; the code was authored by claude-opus-5-5, so a non-Claude reviewer is required

You are a HEADLESS plan-mode reviewer. Produce the COMPLETE review in your final response now. Do not wait for approval, do not ask questions, do not end with a plan or a promise of a verdict. Read-only: do not edit files. Cite file:line. Do not rerun the test suites.

## Owner statements (verbatim quotes)

"use opus for S1 and glm for S2"
"deepseek for both, then commit and merge"

## What is under review
Uncommitted work in /Users/sto/workspace/datomworld-m4-s1 (branch m4-s1, from master b3d0b5a1, which
holds M1, Rule R, M2, M3 and the authority policy). Modified: src/cljc/yin/repl.cljc,
src/cljc/yin/vm/ast_walker.cljc, debruijn/register.cljc, debruijn/stack.cljc,
debruijn_register_effects.cljc, semantic.cljc, test/yin/repl_test.cljc; NEW (untracked, read it
directly) test/yin/vm/attach_image_test.cljc. Read the change with git -C
/Users/sto/workspace/datomworld-m4-s1 diff HEAD and the files directly. The specification is
docs/design/yin.vm.linker.md: section 7.3 (installing a module is a scheduler child task; the
attach-image passages near lines 1160 to 1240, 1338 and 1523), section 9 (the M4 paragraph),
section 10 (the file box) and section 11 criterion 17. The implementer's report (untrusted):
/Users/sto/workspace/datomworld/collab/1790403451133-vm-engineer-linker-m4-s1-attach-image.claude-opus-5-5.report.md
A scoping report framed the slice (untrusted, and its claims may be wrong):
/Users/sto/workspace/datomworld/collab/1790355000000-architect-linker-m3-m5-scoping.glm-5.3.report.md
Rules in force: Rule R (yin/def is syntax, never a name; only yin/def reserved); no contract stamp is
ever assigned to external input; kernels must stay behavior-neutral for everything that does not
call attach-image (no existing test may change). S1 must not change engine.cljc, module.cljc or any
UCF doc (those are slices S3, S2 and S5).

## Orchestrator evidence (independently run in the worktree, solo; do not rerun)
JVM 2,136 tests / 181,862 assertions / 0 failures; Node 2,049 / 48,681 / 0; Dart 2,011 passed (all
tests passed, though the lane took about 34 minutes against about 3 on earlier runs, most likely
machine load, not yet proven); kondo 0 errors and 1 warning that exists at the base; cljstyle clean;
git diff --check clean. Baseline at master b3d0b5a1: JVM 2,118 / 181,736, Node 2,031 / 48,573, Dart
1,993. All match the implementer's own lane figures.

## Orchestrator framing (my reading; challenge it)
Claimed: stack and register kernels get an offset table (:images) whose base row load-image writes,
and attach-image relocates, appends, adds one [identity offset length] row and recomputes :hash; entries
record :image (the table row their pc falls in); restore checks format plus table membership, never
uses :hash as a key and never writes :segment from an entry; the register call frame drops :segment
and :hash so returns are grow-only; the semantic kernel mints a fresh local id and writes the alias
column; the walker annotates each :lambda node with its row id in metadata (so node equality is
unchanged), keeps :rows and a [node params] index, and closure-row refuses :unrooted-body; the REPL
append functions call attach-image and then set :pc themselves. Decisions the implementer flags:
(1) the REPL also resets frames, stack, continuation, halted and blocked flags, value and the register
file because attach-image touches none of them; (2) the REPL takes :pc from the image's table row, not
the held length, since an already-held image attaches as a no-op; (3) register reset keeps the offset
table; (4) entries still carry :segment and :hash (existing tests read them); (5) UCF lift and lower
are not implemented and the tests compose them by hand. It also edited
debruijn_register_effects.cljc, OUTSIDE the expected list, because its frame check demanded each
frame carry :segment and :hash, contradicting the grow-only rule. I have not read the diff.

## What to produce
1. SPEC CONFORMANCE for each kernel and the REPL: does attach-image do what section 7.3 and
   criterion 17 require? List any divergence or unimplemented requirement. Is S1's scope exactly S1's?
2. CORRECTNESS: relocation arithmetic, the offset table and its invariants, hash recomputation,
   restore by table membership, grow-only returns and a register call in flight over a nested attach,
   the semantic alias column, the walker row index and metadata annotation. Hunt for off-by-one,
   stale-pc, aliasing, double-attach of an already-held image, and a state that can be left
   inconsistent.
3. BEHAVIOR NEUTRALITY: does anything change for code paths that never call attach-image? Compare
   removed and changed assertions and existing tests in git diff HEAD; was any weakened or edited to
   pass? Is the debruijn_register_effects.cljc edit justified and safe?
4. THE FIVE DECISIONS: right, defensible, or a defect; which belong in the spec.
5. CROSS-HOST (JVM, Node, Dart): metadata on the walker's map nodes (vary-meta), the decoder's letfn
   and atom, reader-conditional traps, private var access, keyword identity.
6. TESTS: do the 17 plus 1 tests cover every S1 item and criterion 17, and can each fail?
7. Rule R and security: any path where an attached image bypasses reserved-name validation, contract
   stamping or the admission checks the loaders enforce?
8. Anything new. Distinguish defects from deferred work. Your overall verdict on whether the diff is
   ready to commit as M4 slice S1.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report actionable findings as:
P0-P3 | file:line | evidence | concrete fix

End with exactly two lines:
Verdict: READY
Sign-off: GRANTED
or
Verdict: REQUEST CHANGES
Sign-off: DENIED
(meaning: whether the diff is ready to commit.)
