Created-GMT: 2026-10-12 12:00:00 GMT
Created-Local: 2026-10-12 19:00:00 Asia/Bangkok

# Task: independent architecture review of the discovery-order numbering note (authored by claude-fable-5-1)

Role: Lead System Architect (gpt-6.1-sol), fresh thread. READ-ONLY: edit nothing, no git add/commit. Worktree `/Users/sto/workspace/datomworld-srvm-numbering`, branch `numbering-note`, HEAD 7a6d7cc9. The note: `docs/design/yin.vm.semantic-register-vm.cursor-cell-numbering.md` (about 420 lines). The author's report: `collab/1792120000000-architect-cursor-cell-numbering.report.md`; the prototype: `collab/numbering-probe` (run `clojure -M -e "(require 'probe.discovery) (probe.discovery/-main)"` from that directory). The owner has approved the direction (adopt discovery-order numbering for cursor cells, replacing label refinement and search, if your review finds it sound).

Vocabulary (UCF section 7.5.3, mandatory): cursor = the immutable dao.stream value; cursor cell = the VM's mutable box `{:stream-id id :cursor c}` that the carrier serializes; cursor-ref = the sealed ref a program holds; cell = only the heap box. Owner rulings: sharing must survive transport; the root id depends only on structure (cross-engine law); no legacy, no version bump; BLAKE3.

Evaluate, independently:
1. Position inventory (the note's F1): is every non-guest position that can hold a cursor-ref or cursor-cell marker really ordered, including the environment, window, frames, wait entry, pending slots, parked table, installs, module stores, the root's slots? Check against the carrier draft (`docs/design/yin.vm.ucf-transport-tuples.md` sections 5 and 6) and `src/cljc/yin/vm/ucf/rows.cljc` of branch srvm-phase6a (read via `git show srvm-phase6a:src/cljc/yin/vm/ucf/rows.cljc`; the p6a worktree path may be blocked by your sandbox). Find any position the note missed.
2. The rule D1-D5: traversal order, tie groups, fixpoint, the orbit lemma (D4), the refusal (D5). Try to BREAK it: construct states where discovery order plus content-first sorting gives two different numberings for the same structure (renaming or permuting an unordered container), or where the orbit lemma admits a non-interchangeable choice. Try nested unordered containers, a cell appearing in two tie groups, a cell that appears both in an ordered slot and in a set, install children, cells referenced from a guest map's keys and values. Run or extend the prototype to test them; report counterexamples with exact inputs.
3. The proof (section 3): induction on renaming invariance, permutation invariance, termination (at most n fixpoint rounds): is each step actually justified?
4. The refusal: kind `:yin.k/non-portable` `:unresolved-cursor-cells` at lift and `:yin.k/undecodable` `:unresolved-cursor-cells` at decode instead of `:resource-limit`. Consistent with the carrier's refusal taxonomy and the validator order? Is the remaining refused class genuinely rare in real continuations (guest sets or map keys holding several cursor-refs with identical contents that are distinguishable only through tie groups)?
5. Impact on 6a (A1-A10, acceptance items 12-20): what is deleted, kept, added; are the pinned-id claims right (the frame root `c4a7c7e6...` and the two-cell fixture root `fab223...` unchanged; the new pins)? Recompute at least three pins. Does anything else in the repo read the current numbering (decode recompute-and-compare, the harness law, `canonical-under`)?
6. The rejection of portable ids (finding F6): sound?
7. Grounding: spot-check at least 12 file:line references; amendment texts exact and minimal; consistency with phases 5 and 6 designs; portability notes (nil-valued keys, large seqs, reader whitespace on Dart).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
Then findings (severity | file:line | evidence | correction), answers 1-7, and end with exactly one line:
VERDICT: READY_TO_MERGE | CHANGES_REQUESTED
