Created-GMT: 2026-10-12 08:05:00 GMT
Created-Local: 2026-10-12 15:05:00 Asia/Bangkok

# Task: rename "cell" to "cursor" in slice 6a's code and tests

Role: engineer (claude-opus-5-5). Worktree `/Users/sto/workspace/datomworld-srvm-p6a`, branch `srvm-phase6a`, HEAD af2b29c4. Do NOT `git add`/commit. Write each `-n ns` literally; foreground only. Take a verified byte backup of each file you edit into `collab/` first.

Files: `src/cljc/yin/vm/ucf/rows.cljc`, `src/cljc/yin/vm/semantic_register/frame.cljc`, `test/yin/vm/ucf/rows_test.cljc`, `test/yin/vm/semantic_register/frame_test.cljc`. Do not touch any other file (other slices and the engine use "cell" for the heap box). Other places in the repo that read the carrier's `:cells`/`:cell-body` tags or `:yin.k/c-<n>` ids: grep `src/` and `test/` for them and LIST them in the report; if any production reader other than 6a's namespaces uses them, STOP on that item and report.

## The rename (owner decision 2026-10-12: "call the carrier's thing a cursor, keep cell for the heap box")

In the row carrier, the identity-bearing, task-local reference that stands for a position in a stream (UCF section 7.5.3 "cursor cells"; the carrier's `:cell-body` = stream marker + kept position) is renamed **cursor**. The word **cell** stays ONLY for the mutable heap box (`yin.vm.cell`, `cell/new`, heap cell refs, `:heap` in GC, "cell iref" only if it means a heap box).

Spelling table (old -> new):
- prose: "cell" -> "cursor"; "cell census" -> "cursor census"; "canonical cell numbering" -> "canonical cursor numbering"; "cursor cell" -> "cursor"; "identity rows are cells" -> "identity rows are cursors".
- task-local ids: `:yin.k/c-<n>` -> `:yin.k/cur-<n>` (the ids' numeric order and sort rule unchanged).
- census tuple tag `:cells` -> `:cursors`; body tuple tag `:cell-body` -> `:cursor-body`.
- value marker: `{:yin.k/cell id}` -> `{:yin.k/cursor id}`; value union `[:lit|:row|:cell]` -> `[:lit|:row|:cursor]`; wire `[:cell id]` -> `[:cursor id]`.
- UCF envelope key `:yin.k/cells` -> `:yin.k/cursors`.
- refusal kinds: `:extra-cell` -> `:extra-cursor`; `:noncanonical-cells` -> `:noncanonical-cursors`; `:cell-*`/`*-cell` kinds -> the cursor spelling.
- code identifiers: `cell-body` helper -> `cursor-body`; any fn/var/arg named `cell(s)` that denotes the carrier's cursor -> `cursor(s)`.
DO NOT rename: `yin.vm.cell`, `cell/new`, `cell/get`, heap `:cell-0`-style ids of heap boxes, GC heap code, and any use that means the mutable heap box (e.g. "(ffi :op/echo (cell/new :keep))" in the evaluator tests is a HEAP cell: unchanged). When a use is ambiguous, classify it by reading its context; list every ambiguous site in the report with your decision.
The tags and ids are hashed into tuple bodies, so every pinned root id, checksum and golden that contains them changes: regenerate them mechanically (recompute with the public hash function) and list old -> new for each.

Procedure: do the rename; then regenerate every pinned root id, checksum and golden in the two test files (the three pinned ids: `(fixture :s1 :s2 :x :y)`, `(frame->rows ft/worked-frame)`, the three-cursor loop-plus-2-cycle fixture, and any other literal containing the old tags or ids) by recomputing them with the existing test helper and the public hash function, and confirm each changed value is explained only by the tag and id renames (a test that proves the structure is unchanged: for example compute the root id of the SAME fixture before and after under the old/new spelling, or show the numbering order is identical). The numbering order, ordering rules and all refusal behaviours must be unchanged: no test may be weakened or deleted. Re-run the mutants you used in rounds 3 and 4 only if the rename touches their sites (at least confirm 5 of the previous mutants are still KILLED by a named test: drop the guest/explicit marker distinction; remove one byte-bound preflight; collapse equality tags; skip numbering before frame check; census order reversed).

Verify (foreground): `clojure -M:test -n yin.vm.semantic-register.frame-test -n yin.vm.ucf.rows-test` (expect 31 tests, 552 assertions unless you add tests); the rows test under `clojure -J-Xmx256m -M:test -n yin.vm.ucf.rows-test`; the semantic-register namespaces; Node for the two test namespaces; kondo and cljstyle on changed files; `grep -n -i cell` over the four files and justify every remaining occurrence (heap box, or quoting history). Dart: the orchestrator.

Report to `collab/1792090000000-engineer-rename-cursor-code.report.md`, beginning exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
Old -> new for every pinned id, every ambiguous site and the decision, the list of other readers of the old spellings, the mutants re-checked, exact lane counts.
