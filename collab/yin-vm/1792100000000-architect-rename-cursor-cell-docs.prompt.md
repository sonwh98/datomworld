Created-GMT: 2026-10-12 09:00:00 GMT
Created-Local: 2026-10-12 16:00:00 Asia/Bangkok

# Task: second rename in the design documents: the carrier's box is a "cursor cell" (documents only)

Role: Architect delegate (claude-fable-5-1). Worktree `/Users/sto/workspace/datomworld-rename-docs2`, branch `rename-cursor-cell-docs` (from master 6e05a98f, which already contains the first rename). DOCUMENTS ONLY under `docs/design/`: no source, no test. No git add/commit. Leave `collab/` records, `docs/orchestrator-log.md` and companion review notes untouched.

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

Documents to cover: every `docs/design/*.md` touched by commit 10876025 (`git show --stat 10876025`), plus `docs/design/dao.stream.md` ONLY to check it needs nothing (it should not). Update the terminology note the first rename added (three places) to the new vocabulary (cursor / cursor cell / cursor-ref / cell). Verify: grep each document for the bare word "cursor" used as the BOX and for old spellings (`:yin.k/cur-`, `:cursors`, `:cursor-body`, `[:cursor `, `{:yin.k/cursor `), and justify every remaining occurrence; flag every pinned root id/probe in the docs that contains the old tags (the code owner regenerates; do not recompute).

Report to `collab/1792100000000-architect-rename-cursor-cell-docs.report.md`, beginning exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
Per document: sites changed, sites reverted to the v2 original spelling, remaining occurrences with justification, ambiguous sites and decisions, pinned probes flagged.
