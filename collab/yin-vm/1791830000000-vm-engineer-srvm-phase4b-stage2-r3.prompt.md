Created-GMT: 2026-10-11 03:50:00 GMT
Created-Local: 2026-10-11 10:50:00 Asia/Bangkok

# Task: 4b Stage 2, round 3 -- close the reviewer's two gaps (continuing your thread)

Worktree `/Users/sto/workspace/datomworld-srvm-p3`, branch `srvm-phase4b`, HEAD bdf48cf4. Do NOT `git add`/commit. TEST-ONLY: `src/cljc/yin/vm/semantic_register.cljc` must stay byte-identical (check `git diff --quiet HEAD -- src/`). Read the review: `collab/1791790000000-reviewer-srvm-phase4b-stage2.claude-fable-5-1.findings.md` (19 mutants; no source defect).

1. Mutant R17 (`engine/pin-refs` dropped from the `:ffi-call` arm, `semantic_register.cljc:237`) survives. Add to `effects_test.cljc`: pass a cell ref over FFI (`(ffi :op/echo (cell/new :keep))` with the cell module registered); after the call blocks, assert the cell's id is in `(get-in blocked [:gc :pinned])` and that `(engine/collect (assoc blocked :window {} :env {}))` keeps the cell. Template: `heap_reclamation_test.cljc` `a-ref-put-on-a-stream-stays-authentic-test` (about lines 391-403). Apply R17, confirm a named test FAILS, restore byte for byte.
2. Mutant R12b (`spawn-module` gives the child the parent's `:window`) survives. In `link_test.cljc` `image-and-store-context`, assert `(= {} (:window child))`. Apply R12b, confirm it FAILS, restore.
Optional (take only if it costs a few lines): align `lower-closure`'s binding-mismatch ex-data with the template (`:binding :format :expected-format`, `semantic.cljc:964-970`) and assert it in `marker-checks`; if you do this it is a SOURCE change, so report it explicitly; otherwise skip it.

Verify, foreground, literal `-n` flags: JVM for the 17 namespaces of your round-2 report; Node as in round 2; kondo and cljstyle on changed files. Dart: orchestrator runs it.

Report to `collab/1791830000000-vm-engineer-srvm-phase4b-stage2-r3.report.md`, beginning exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
Per item: change, mutant killed by which test, exact lane counts. Do not claim runs that did not occur.
