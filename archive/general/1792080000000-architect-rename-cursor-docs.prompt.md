Created-GMT: 2026-10-12 08:00:00 GMT
Created-Local: 2026-10-12 15:00:00 Asia/Bangkok

# Task: rename "cell" to "cursor" in the design documents (documents only)

Role: Architect delegate (claude-fable-5-1). Worktree `/Users/sto/workspace/datomworld-rename-docs`, branch `rename-cursor-docs` (from master 03587c54). DOCUMENTS ONLY: no source, no test. No git add/commit.

Apply the rename below in: `docs/design/yin.vm.ucf-transport-tuples.md` (about 79 mentions), `docs/design/yin.vm.universal-continuation-format.md` (about 70; many may mean other things: classify each), `docs/design/yin.vm.semantic-register-vm.continuation.md` (about 18), `docs/design/yin.vm.semantic-register-vm.md` (about 3), and any other `docs/design/*.md` that talks about the carrier's cells (grep for "cell"). Leave historical records (the two companion designs' review notes, `collab/`, `docs/orchestrator-log.md`) untouched. Add a short terminology note where the carrier first uses "cursor": it was called "cell" before 2026-10-12 and the word "cell" now means only the mutable heap box.

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

Verify: after the edit, grep each document for the remaining word "cell" (case-insensitive) and justify every remaining occurrence in the report (heap box, quoted history, or a mistake to fix). Also check that any hash/root-id probe quoted in the documents containing the old tags is flagged (do not recompute here: list them; the code owner regenerates).

Report to `collab/1792080000000-architect-rename-cursor-docs.report.md` (inside the worktree's own `collab/`), beginning exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
Per document: occurrences changed, remaining occurrences with justification, ambiguous sites and decisions, pinned probes flagged.
