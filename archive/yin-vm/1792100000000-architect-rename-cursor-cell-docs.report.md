Completed-GMT: 2026-10-11 18:53:34 GMT
Completed-Local: 2026-10-12 01:53:34 Asia/Bangkok

# Report: second rename, the carrier's box is a "cursor cell" (documents only)

Worktree `/Users/sto/workspace/datomworld-rename-docs2`, branch `rename-cursor-cell-docs`.
Nine `docs/design/*.md` files modified in the working tree, nothing staged, nothing committed. No source, no test, no `collab/` record, no `docs/orchestrator-log.md` touched. `dao.stream.md` checked and left alone.

## Method

Commit 10876025 had no later changes on top of it in any of the nine files (`git diff --stat 10876025 HEAD -- docs/design/` is empty), so instead of inverting the first rename hunk by hunk I restored each file to `10876025^` and re-applied the owner's vocabulary to the ORIGINAL text. The original already said "cursor cell" in most of the places the first rename had flattened to "cursor", so this base loses nothing. Every "cell" occurrence in the original was then classified by hand:

- box sense (the carrier's identity-bearing resource) -> "cursor cell" as a noun, "cursor-cell" as a modifier (ids, census, numbering, bodies, table, graph, mapping, identity, stream, references, iref);
- heap-box sense (`yin.vm.cell`, `:cell/new`, `:cell-ref`, "heap cell", "cell-lift refusal", "heap-cell payloads", "a cell ref held only in a register", the linker's "scheduler-owned cell") -> untouched;
- table-cell sense ("replace the State cell" in the evaluator doc) -> untouched.

Wire tokens were then handled per carrier version:

- version 3 docs (`yin.vm.ucf-transport-tuples.md`, `yin.vm.semantic-register-vm.continuation.md`): `:yin.k/c-<n>` -> `:yin.k/cc-<n>`, `:cells` -> `:cursor-cells`, `:cell-body` -> `:cursor-cell-body`, `[:cell C]` -> `[:cursor-cell C]`, `cell-C` -> `cursor-cell-C`, `response-cell-C` -> `response-cursor-cell-C`, `cells-cref` -> `cursor-cells-cref`, `:noncanonical-cells` -> `:noncanonical-cursor-cells`, test name `...crosses-as-one-cell` -> `...crosses-as-one-cursor-cell`;
- version 0/1/2 docs (UCF main 7.2.1, 7.4.3, 7.5.1 table row, 7.5.3; `v2-amendment`; `ucf-revisions`; `linker.md`; `linker.dht.md`): wire keys stay exactly as `ucf/handoff.cljc` and `ucf/remote.cljc` spell them, confirmed by grep of the v2 code: `:yin.k/cells`, `:yin.k/cell`, `:yin.k/response-cell`, `:yin.k/c-<n>`, `:extra-cell`. One-line notes say version 3 spells them differently.

Verification greps over the nine files: no `:yin.k/cur-`, `:cursors`, `:cursor-body`, `[:cursor `, `{:yin.k/cursor `, `:noncanonical-cursors`, `cursors-cref`, bare `cursor-C` remain. The only matches for the pattern `:yin.k/response-cursor` are the new `:yin.k/response-cursor-cell` tokens inside the version-3 notes.

## Per document

### yin.vm.ucf-transport-tuples.md (version 3 carrier)
- Sites changed: every box-sense "cell" (about 95 occurrences across 80 lines) -> "cursor cell"/"cursor-cell"; all tags, ids, slot names and refusal kinds per the spelling table (§3 census rows, §4 value union, §5.3 pending slots, §5.4 row kinds and root layout, §6 title and procedure, §7, §8 steps 3/6/8/9/12 and the diagnostics list, §9, §10 items 2/9/10). `§6` is now "Canonical cursor-cell numbering".
- Reverted to v2 spelling: none (this document has no v2 grammar).
- Remaining occurrences: the word "cursor" alone appears only in the `dao.stream` value sense ("portable cursor domain", "cursor profile", "kept position"). No "cell" remains without "cursor" in front of it.
- Ambiguous: `[:cell <class>]` in §10 item 9 (the class-erased skeleton) denotes the box tag -> `[:cursor-cell <class>]`. `cells-cref` is a slot name in the root layout -> `cursor-cells-cref`.
- Terminology note (§3, replacing the first rename's note) now defines cursor / cursor cell / cursor-ref / cell and records the pre-2026-10-12 spellings and that the v2 carrier keeps them.
- Pinned probes: none in this document.

### yin.vm.semantic-register-vm.continuation.md (version 3 carrier)
- Sites changed: §0 overview list, §2 "canonical cursor-cell numbering", §2.2 identity rows `:yin.k/cc-<n>` and "cursor-cell census", §2.4 root layout `cursor-cells-cref ; :cursor-cells census`, §3.x first-defect order `:noncanonical-cursor-cells`, §5.5 renaming-invariance paragraph (fresh cursor cells, "cursor cells by body", cursor-cell numbering, cursor-cell bodies), §6 lift step "number cursor cells", test list items 7, 8 and 10.
- Reverted to v2 spelling: none.
- Remaining: "cursors" in the test-row prose "holds two cursors named by a module closure" and the fixture name `parked-module-store-cursors` are the fixture's own words and name; left.
- Ambiguous: row 18's result column "the cursors' cells" -> "the two cursor cells". `a-response-cursor-shared-by-two-waiters-crosses-as-one-cell` is a test name; renamed in the doc to `...-crosses-as-one-cursor-cell` because the doc describes the port target, the code owner decides the real name.
- Terminology note in §2.2 rewritten to the four-word vocabulary.
- Pinned probes: §10.x "Pinned row sets" (line 1674 area) refers to pinned row sets in TEST code, not in the doc; those pins hash `:cells`/`:cell-body`/`:yin.k/c-<n>` and change again. Flagged for the code owner; nothing to recompute in the document.

### yin.vm.universal-continuation-format.md (body versions 0/1/2 grammar plus general prose)
- Sites changed (prose, box sense -> cursor cell): 7.2.1 shape rules ("the cursor cells the body carries"), 7.4.3 three-rules paragraphs (response cursor cell travels at its kept position, waiters sharing a cursor cell, exporter enters the cursor cell's handle, "The cursor cell carries its kept cursor", fresh local key, attach to cursor cells, a `:next` wait polls its cursor cell), 7.4.3 linker-variants paragraph ("the cursor-cell id stands for the cursor and its kept position rides in the cursor-cell table"), per-cursor-cell round-order, install child contents, required-keys list ("the cursor cell's own stream marker"), 7.5.1 decode column, 7.5.2 resource lowering paragraph, 7.5.3 title "Sharing, cursor cells, and cycles" and body, 7.6.2 (resource cursor cells beside the store, remapped cursor cells), exporting-mode custody holds, export-recovery codec and rehydrate paragraph, resume step 7, the invariants table ("explicit cursor-cell graph"), acceptance tests (reflection acceptance, sharing scenarios).
- Reverted to the v2 ORIGINAL spelling (exactly as `ucf/handoff.cljc`/`ucf/remote.cljc`): envelope key `:yin.k/cells` (7.2 envelope and the four-questions list), 7.2.1 body `:yin.k/cells`, 7.4.3 examples `:yin.k/cell :yin.k/c-17`, `:yin.k/cell :yin.k/c-23` (twice), `:yin.k/cell :yin.k/c-31` (twice), "A wait entry's `:cursor` is UCF's `:yin.k/cell`", required-keys list `:yin.k/cell` (x3) and `:yin.k/response-cell`, 7.5.1 table `:yin.k/cell cell-id`, 7.5.3 table `:yin.k/cells {:yin.k/c-17 ...}` and `{:yin.k/tag :yin.k/cursor-ref :yin.k/cell :yin.k/c-17}`.
- Version-3 notes added (one line each): after the 7.2.1 body grammar, after the 7.4.3 pending examples, after the 7.5.3 table.
- Terminology note: the 7.5.3 "**Cursor cells.**" paragraph now opens with the four-word vocabulary (cursor / cursor cell / cursor-ref / cell) and the pre-date naming.
- Remaining "cursor" occurrences, all the immutable-value sense: "kept cursor", "a cursor is plain data in the `:dao.stream.remote/v1` profile", "cursor profile(s)", "cursor identity, position, and `gap` outcomes" (7.4.3 reflection rule, about the stream cursor), "shared cursor ref" (7.5.3, a ref), "cursor minting", "successor cursor", "cursor namespace", "store cursor entry" (the engine's resource entry, pre-existing phrase describing what a cursor cell lowers into).
- Remaining "cell" occurrences without "cursor": 7.5.2 "big integer inside a heap cell stays under the cell-lift refusal" (heap box, correct).
- Ambiguous sites and decisions:
  - 7.2.1 grammar metavariable `{cell {:yin.k/stream ...}}` -> `{cursor-cell {...}}` (a doc metavariable, not a wire key, so it follows the new vocabulary).
  - "The cell carries its kept cursor" (7.4.3; the first rename wrote "The cursor carries its kept position") -> "The cursor cell carries its kept cursor": box carries value.
  - "the cell id stands for the cursor" -> "the cursor-cell id stands for the cursor": the wait entry's `:cursor` is the kept cursor value; kept "cursor" there.
  - Exporting-mode holds "an entry or cell carrying `:yin.k/held`, a cursor cell carrying `:yin.k/unminted`": `holder/reader.cljc` sets both marks on the `:resources` cursor cell, so both are "cursor cell".
  - Reflection acceptance test: "the cell descriptor is a remote descriptor and the cell carries the kept cursor" -> cursor cell / kept cursor (the first rename had changed "kept cursor" to "kept position"; restored).
  - "handoff value/cell codec" -> "value/cursor-cell codec".
- Pinned probes: none in this document (the only long digit string is `2^52-1`).

### yin.vm.universal-continuation-format.v2-amendment.md (version 2)
- Sites changed (prose): "`C` is a task-local cursor-cell identifier", "stream/cursor-cell references", "Cursor cells are task-local ... distinct source cursor cells", "every declared cursor cell ... every reached cursor cell", "unminted cursor cell", test matrix items 4, 10, 11 ("distinct cursor cells", "cursor-cell IDs", "registers/cursor cells").
- Reverted to v2 spelling: body grammar `:yin.k/cells {C ...}`; a trailing comment on that line says version 3 spells it `:yin.k/cursor-cells`, ids `:yin.k/cc-<n>`.
- Remaining: "heap-cell payloads remain non-portable" (heap sense, untouched).
- Ambiguous: "unminted cell" -> "unminted cursor cell" (the `:yin.k/unminted` mark lives on the `:resources` cursor cell, `holder/reader.cljc`).
- Pinned probes: none.

### yin.vm.ucf-revisions.md (version 2 stage log)
- Sites changed: "the one cursor-cell table", item 5 "Cursor-cell identity discipline: cursor-cell ids are lift-local ... per cursor cell", item 6 "a body carrying cursor cells".
- Reverted to v2 spelling: this prose carried no wire keys; a one-line note after item 5 records the v2 spellings and the v3 spellings.
- Remaining: none of concern.
- Pinned probes: none ("pinned" appears only about stamps and AST identities).

### yin.vm.linker.md (reconciliation tables describe the v2 UCF)
- Sites changed: 7.2 step 4 "the kept cursor cell", step 7 "per-cursor-cell round-order", r8/r9/sealed-references rules ("engine-filed resource cursor cells", "migration slice still carries resource cursor cells", "lowers each logical cursor cell", "two refs to one cursor cell", "attachment or cursor cell" x4, "an FFI cursor-cell id" x3), 11.x acceptance text, the edited-files list ("the stream and cursor cells out of :store", original wording kept), and the r9, r8, r7 and r2 reconciliation tables (re-wrapped to keep the column widths).
- Reverted to v2 spelling: the tables carry no wire keys; a one-line note after the r8 paragraph records v2 vs v3 spellings.
- Remaining: "scheduler-owned cell" (a shared mutable module instance, heap sense; untouched). "cursor" alone: "the linker interpreter's own cursor", "router cursor", "kept cursor", "store cursor entry", "a stream or cursor reference", "`cursor-ref`" (value or ref sense).
- Ambiguous: "FFI cell id" (the sealed reference the engine issues for the FFI pair's response box) -> "FFI cursor-cell id". "stays a UCF cell and migrates" -> "stays a UCF cursor cell".
- Pinned probes: none.

### yin.vm.linker.dht.md (14.x handoff acceptance, describes the v2 wire)
- Sites changed: 14.1.x prose and table ("logical cursor-cell identity", "maps to one cursor cell", "distinct cursor cells even when ... equal", shared/independent cursor cell, "sorted cursor-cell ids", "reachable cursor cells keep their positions", "remapped cursor cell", "kept response cursor cell" x2, "original response cursor cell", "response cursor cells retained before the append"), the lift/lower steps ("Cursor-cell ids", "cursor-cell mapping", "source-resource-to-cursor-cell", "retains its occurrence and cursor cells", "each logical cursor cell", "equal-content distinct cursor cells"), the test setups ("shared cursor cell", "independent cursor cells", "malformed cursor-cell references").
- Reverted to v2 spelling: "UCF 7.5.3 already carries `:yin.k/cells`" kept as the v2 key, with a parenthetical that version 3 says `:yin.k/cursor-cells` and `:yin.k/cc-<n>`.
- Remaining: "the cursor-cell alias relation" was already correct in the original. Line 95 "fresh link state and fresh cursors" is the linker interpreter's own stream cursors (value sense), untouched and outside the first rename's hunks.
- Pinned probes: none.

### yin.vm.semantic-register-vm.md (frozen design)
- Sites changed: the `:stream-next` blocked transition "pending: cursor cell c", the §4.1 safepoint table "pending cursor cell `c`" and "(call id, response cursor cell)".
- Reverted: nothing to revert.
- Ambiguous: `c` is the instruction's cursor-ref operand; the pending wait holds the cursor cell it names, so "cursor cell" (not "cursor", not "cursor-ref").
- Pinned probes: none.

### yin.vm.semantic-register-vm.evaluator.md
- Sites changed: the two quoted §4.1 rows "(call id, response cursor cell)".
- Remaining: "replace the State cell" (a table cell), "`:cell/new`", "`:cell/*`", "a cell ref held only in a register" (heap `:cell-ref`), all untouched.
- Pinned probes: none.

### dao.stream.md
- Checked only. Contains no occurrence of "cell"; "cursor" there is the immutable value. Needs nothing.

## Pinned ids and goldens

No pinned root id, golden body address or probe value appears in any of the nine documents, so nothing was recomputed and nothing is stale inside `docs/design/`. For the code owner: every version-3 test pin that hashes `:cells`, `:cell-body`, `[:cell C]` or `:yin.k/c-<n>` (the "pinned row sets" the continuation doc §10 mentions, the row-carrier goldens, any pinned root ids in `test/`) changes when the code adopts `:cursor-cells`, `:cursor-cell-body`, `[:cursor-cell C]`, `:yin.k/cc-<n>`; the v2 handoff pins (`:yin.k/cells`, `:yin.k/c-<n>`) do not change.

## Not done, by design

- No source or test edited; v2 production code spellings untouched and the docs now agree with them.
- `collab/` records, `docs/orchestrator-log.md`, review notes untouched. A temporary rename script was created under `collab/` during the work and deleted again.
- Nothing staged or committed.
