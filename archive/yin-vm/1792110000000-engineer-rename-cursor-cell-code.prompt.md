Created-GMT: 2026-10-12 09:05:00 GMT
Created-Local: 2026-10-12 16:05:00 Asia/Bangkok

# Task: second rename in slice 6a's code and tests: the carrier's box is a "cursor cell"

Role: engineer (claude-opus-5-5). Worktree `/Users/sto/workspace/datomworld-srvm-p6a`, branch `srvm-phase6a`, HEAD 83fd51d8 (which contains the first rename). Do NOT `git add`/commit. Write each `-n ns` literally; foreground only. Take a verified byte backup of each file you edit into `collab/` first.

Files: `src/cljc/yin/vm/ucf/rows.cljc`, `test/yin/vm/ucf/rows_test.cljc`, `test/yin/vm/semantic_register/frame_test.cljc` (and `src/cljc/yin/vm/semantic_register/frame.cljc` only if it now has a site). Do not touch any other file; in particular NOT the version-2 production code (`engine.cljc`, `ucf/handoff.cljc`, `ucf/remote.cljc`).

## The second rename (owner decision 2026-10-12): the box is a "cursor cell"

Vocabulary, one meaning per word:
- **cursor**: the IMMUTABLE value of `dao.stream` (logical-stream identity + position; `dao.stream.md`: "A cursor is an immutable value"). Keep the word "cursor" for this.
- **cursor cell**: the VM's mutable box `{:stream-id id :cursor c}` in `:resources` that `stream/next!` updates (`engine.cljc` `cursor-cell?`). It has identity. THIS is what the row carrier serializes: the census, the numbering, aliasing.
- **cursor-ref**: the sealed `:cursor-ref` a guest program holds; it names a cursor cell. Unchanged.
- **cell**: ONLY the general heap box (`yin.vm.cell`, `cell/new`, `:cell-ref`). Unchanged.

The previous rename (docs commit 10876025, code commit 83fd51d8) turned the carrier's box from "cell" into "cursor". That was wrong for sites that mean the BOX. Invert it for every site that denotes the box, and keep "cursor" where it means the immutable value (for example "a cell holds a cursor", a stream position, `:dao.stream/cursor`, the `:cursor` slot of a box body). Read each changed hunk of that earlier commit (`git show <commit>`) and decide per hunk; list every ambiguous site and your decision in the report.

Spelling table (current -> new), applied to the carrier's box only:
- prose: "cursor" (box sense) -> "cursor cell"; "cursor census" -> "cursor-cell census"; "canonical cursor numbering" -> "canonical cursor-cell numbering"; "cursors" (plural, box sense) -> "cursor cells".
- ids: `:yin.k/cur-<n>` -> `:yin.k/cc-<n>` (same numeric order and no-leading-zero rule).
- tuple tags: `:cursors` -> `:cursor-cells` (census); `:cursor-body` -> `:cursor-cell-body`.
- value marker `{:yin.k/cursor id}` -> `{:yin.k/cursor-cell id}`; union `[:lit|:row|:cursor]` -> `[:lit|:row|:cursor-cell]`; wire `[:cursor id]` -> `[:cursor-cell id]`.
- UCF envelope key `:yin.k/cursors` -> `:yin.k/cursor-cells`.
- refusal kinds: `:extra-cursor` -> `:extra-cursor-cell`; `:missing-cursor` -> `:missing-cursor-cell`; `:noncanonical-cursors` -> `:noncanonical-cursor-cells`.
- pending-row keys the v3 code reads: `:yin.k/cursor` -> `:yin.k/cursor-cell`; `:yin.k/response-cursor` -> `:yin.k/response-cursor-cell`.
- code identifiers: locals/args/helpers denoting the box: `cursors` -> `cursor-cells`, `cursor-body` -> `cursor-cell-body`, budget key `:cursors` -> `:cursor-cells`.
The tags and ids are hashed into tuple bodies: every pinned root id and golden containing them changes again; recompute them mechanically and list old -> new.

IMPORTANT, the version-2 handoff carrier: the shipped v2 production code (`engine.cljc`, `ucf/handoff.cljc`, `ucf/remote.cljc`) still uses the ORIGINAL spellings `:yin.k/c-<n>`, `:yin.k/cell`, `:yin.k/response-cell`, `:yin.k/cells`, `:extra-cell`, and it is retired at phase 8, so it is NOT renamed. Documents that describe the v2 grammar (UCF section 7.2.1, 7.4.3 examples and the 7.5.3 table for body versions 0/1/2, `yin.vm.universal-continuation-format.v2-amendment.md`, the linker docs' v2 reconciliation tables, `yin.vm.ucf-revisions.md`) must go back to the ORIGINAL spellings exactly as the v2 code uses them (use `git show 10876025^:<file>` to recover the original text of those hunks), with a one-line note that v3 spells these differently. Never rename anything in v2 production code.

Procedure: use `git show 83fd51d8 -- src/cljc/yin/vm/ucf/rows.cljc test/yin/vm/ucf/rows_test.cljc test/yin/vm/semantic_register/frame_test.cljc` as the map of every site the first rename touched; for each hunk decide box-sense (rename to cursor cell per the table) or value-sense (keep "cursor"), and list ambiguous sites. Then regenerate every pinned root id/checksum/golden (the two that changed in the first rename and any other) by recomputing with the public hash function, and prove the rename changed only spelling with the same method as before: your equivalence probe `collab/1792090000000-rename-equivalence.clj` and the backup codec `collab/1792090000000-backup-rows.cljc.orig` (the codec before ANY rename: ids `c-N`, tags `:cells`/`:cell-body`) if still present; the numbering, ordering and refusal behaviour must be unchanged and no test weakened or deleted. Also confirm 5 earlier mutants are still KILLED by a named test (drop the guest/explicit marker distinction; remove one byte-bound preflight; collapse equality tags; skip numbering before the frame check; census order reversed).

Verify (foreground): `clojure -M:test -n yin.vm.semantic-register.frame-test -n yin.vm.ucf.rows-test` (expect 31 tests / 553 assertions unless you add tests), the rows test under `clojure -J-Xmx256m -M:test -n yin.vm.ucf.rows-test`, the semantic-register namespaces, Node for the two test namespaces, kondo and cljstyle. `grep -n -i "cursor"` over the files and justify any bare "cursor" that denotes the box. Dart: the orchestrator.

Report to `collab/1792110000000-engineer-rename-cursor-cell-code.report.md`, beginning exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
Old -> new for every pinned id, every ambiguous site and decision, mutants re-checked, exact lane counts.
