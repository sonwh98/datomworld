Completed-GMT: 2026-10-11 18:09:26 GMT
Completed-Local: 2026-10-12 01:09:26 Asia/Bangkok

(The worktree host's clock reads earlier than the brief's Created stamp of
2026-10-12 08:00:00 GMT; the stamps above are the host clock as read at
completion.)

# Report: rename "cell" to "cursor" in the design documents (documents only)

Branch `rename-cursor-docs`, worktree `/Users/sto/workspace/datomworld-rename-docs`.
Nine files under `docs/design/` changed, nothing staged, nothing committed,
no source or test touched. `collab/`, `docs/orchestrator-log.md` and every
other document untouched.

```
 docs/design/yin.vm.linker.dht.md                              |  52
 docs/design/yin.vm.linker.md                                  |  48
 docs/design/yin.vm.semantic-register-vm.continuation.md       |  40
 docs/design/yin.vm.semantic-register-vm.evaluator.md          |   4
 docs/design/yin.vm.semantic-register-vm.md                    |   6
 docs/design/yin.vm.ucf-revisions.md                           |   8
 docs/design/yin.vm.ucf-transport-tuples.md                    | 166
 docs/design/yin.vm.universal-continuation-format.md           | 143
 docs/design/yin.vm.universal-continuation-format.v2-amendment.md | 20
 9 files changed, 248 insertions(+), 239 deletions(-)
```

## Method

One mechanical pass (a regex script run once, then deleted) applied the
spelling table to the nine documents: the keyword and tag tokens first
(`:noncanonical-cells`, `:extra-cell`, `:yin.k/cells`, `:yin.k/response-cell`,
`:yin.k/cell`, `:yin.k/c-<n>` → `:yin.k/cur-<n>`, `:cell-body`, `:cells`,
`cells-cref`, `cell-C`, `response-cell-C`, `[:cell C]`), then the phrases
("cursor cell(s)" → "cursor(s)", "cursor-cell alias" → "cursor alias",
"cursor CELL" → "CURSOR"), then the bare words cell/cells/Cell/Cells, with
`yin.vm.cell`, `cell/new`-style and `:cell/…` spellings excluded by
pattern. Every changed line was then read; the sites below that the pass
touched wrongly were reverted by hand, the "cursor … kept cursor"
doublings were reworded, and the terminology notes were added.

Terminology note added (three places, where the carrier first uses the
word): `yin.vm.ucf-transport-tuples.md` §3 "Identity rows",
`yin.vm.universal-continuation-format.md` §7.5.3 "Cursors",
`yin.vm.semantic-register-vm.continuation.md` §2 ruling 1.

Scope decision: the UCF document's body-version 0/1/2 grammar (§7.2.1,
§7.4.3 examples, §7.5.3 table) is marked historical by its own 2026-10-11
scope note but is live text in a live document, not a review record, so it
was renamed too (`:yin.k/cells` → `:yin.k/cursors`, `:yin.k/cell` →
`:yin.k/cursor`, `:yin.k/c-n` → `:yin.k/cur-n`). The same holds for the
reconciliation tables of `yin.vm.linker.md` §13 and the companion-design
cross-references in `yin.vm.ucf-revisions.md` and the v2 amendment. If the
owner wants the historical grammar frozen in the old spelling, those
hunks revert cleanly; they are listed per document below.

## Per document

### 1. `docs/design/yin.vm.ucf-transport-tuples.md` (79 mentions before)

- Changed: all 79 (every mention in this document is the carrier's
  cursor). Tokens: `:yin.k/c-<n>` → `:yin.k/cur-<n>` (§3), `:cells` →
  `:cursors` and `:cell-body` → `:cursor-body` (§3 census, §5 row-kind
  table, §5.4 root layout `cursors-cref`), `[:cell C]` → `[:cursor C]`
  (§4 value union, §6 steps 2 and 5, §8, §10), `cell-C` /
  `response-cell-C` → `cursor-C` / `response-cursor-C` (§5.3 pending
  table, §6), `:noncanonical-cells` → `:noncanonical-cursors` (§6
  validation, §8, §9, §10.10). Prose: "cell census" → "cursor census",
  "§6 Canonical cell numbering" → "Canonical cursor numbering",
  "source-cell-to-number bijection" → "source-cursor-to-number",
  "a cell iref in a value" → "a cursor iref in a value" (iref = identity
  reference, carrier).
- Remaining (2, both intended): lines 136-137, the new terminology note
  quoting the old word and naming `yin.vm.cell`.
- Ambiguous sites: none. "cell iref" (§3 acyclicity) was checked: it is
  the identity reference of §4's union, not a heap box; renamed.
- `:extra-cell` did not occur in this document (the census rule says
  "none missing, none extra" in prose).
- Pinned probes: none. The document quotes no concrete root id, checksum
  or golden; every address is schematic (`:segment/blake3-…`).

### 2. `docs/design/yin.vm.universal-continuation-format.md` (70 mentions before)

- Changed: 66. Classification of every site:
  - Carrier cursor, renamed (envelope key `:yin.k/cells` → `:yin.k/cursors`
    §7.2 and §7.2.1; pending keys `:yin.k/cell` → `:yin.k/cursor` and
    `:yin.k/response-cell` → `:yin.k/response-cursor` in §7.4.3 examples
    and the required-keys list; ids `:yin.k/c-17/23/31` → `:yin.k/cur-…`;
    §7.5.1 table row `:yin.k/cursor-ref` body `:yin.k/cell cell-id` →
    `:yin.k/cursor cursor-id`; §7.5.3 heading "Sharing, cells, and cycles"
    → "Sharing, cursors, and cycles", "Cursor cells" → "Cursors",
    "logical cursor cells" → "logical cursors", "Cells lower into the
    private resource table" → "Cursors lower…"; §7.6.2 "Resource cells
    live beside the store" → "Resource cursors…", "cursor cells per
    §7.5.3" → "cursors per §7.5.3"; §7.7 gate holds "an entry or cell
    carrying :yin.k/held, a cursor cell carrying :yin.k/unminted" → "an
    entry or cursor carrying…, a cursor carrying…"; §7.7 export recovery
    "handoff value/cell codec" → "value/cursor codec" and "distinct
    cells" → "distinct cursors"; §7.8 resume step 7; §7.10 invariants
    table "explicit cell graph" → "explicit cursor graph"; test plan
    items on aliasing).
  - Doublings reworded after the pass: §7.4.3 "The cell carries its kept
    cursor" → "The cursor carries its kept position"; §7.10 reflection
    test "the cell carries the kept cursor" → "the cursor carries the
    kept position"; §7.4.3 example comment "the cursor CELL id" → "the
    CURSOR id".
- Remaining (4): line 1163 "a big integer inside a heap cell stays under
  the cell-lift refusal" is the mutable heap box, kept; lines 1355-1357
  are the new terminology note (3 mentions of the old word and
  `yin.vm.cell`).
- Ambiguous sites and decisions:
  - §7.7 "an entry or cell carrying `:yin.k/held`": the hold list is
    about wait entries and the carrier's cursor table, not heap boxes;
    classified carrier, renamed.
  - §7.7 "using the handoff value/cell codec": the codec is the §7.5
    value grammar plus the cursor table; classified carrier, renamed.
  - §7.5.1 "a big integer inside a heap cell … cell-lift refusal": heap
    box (the existing refusal for mutable heap payloads); kept.
- Pinned probes: none quoted (schematic addresses only).

### 3. `docs/design/yin.vm.semantic-register-vm.continuation.md` (18 mentions before)

- Changed: 18. `:yin.k/c-<n>` → `:yin.k/cur-<n>` and "cell census" →
  "cursor census" (§2 ruling 1), `cells-cref ; :cells census` →
  `cursors-cref ; :cursors census` (§2 root layout), "canonical cell
  numbering" (§2, §5.5, §8), `:noncanonical-cells` → `:noncanonical-cursors`
  (§5.2, §8.2 item 7), "fresh cells on relift", "cells by body",
  "renaming of the cells", "cell bodies" (§5.5), "number cells" (§7),
  "permuted cell numbering" (§8.2 item 10).
- Test name in §8.2 item 8:
  `a-response-cursor-shared-by-two-waiters-crosses-as-one-cell` →
  `…-crosses-as-one-cursor`. This is a code identifier (the ported test
  var); the document now names the post-rename spelling, so the code
  owner must rename the test var to match.
- Reworded: §5.3 scenario table row 18 "the cursors' cells" (the
  identity rows of two module-store cursors) → "the cursors' identity
  rows", since "the cursors' cursors" was unreadable.
- Remaining (2): lines 227-228, the new terminology note.
- Ambiguous sites: row 18 above, decided as carrier (it names which
  rows the scenario covers).
- Pinned probes: none quoted.

### 4. `docs/design/yin.vm.semantic-register-vm.md` (3 mentions before)

- Changed: 3. §4.2 transition rule "pending: cell c" → "pending: cursor
  c"; §4.1 safepoint table "pending cell `c`" → "pending cursor `c`" and
  "(call id, response cell)" → "(call id, response cursor)". In all three
  `c` is the cursor operand of `:stream-next`, the carrier's cursor.
- Remaining: 0.
- Pinned probes: none.

### 5. `docs/design/yin.vm.semantic-register-vm.evaluator.md` (6 mentions before)

- Changed: 2. §12 amendment text that quotes and replaces the
  semantic-register-vm §4.1 table row: "(call id, response cell)" →
  "(call id, response cursor)" in both the "replace" and "with" quotes,
  so the amendment stays consistent with document 4.
- Remaining (4), all kept: line 280 `:cell/new` effect and line 512
  `:cell/*` effects (heap box ops); line 1257 "a cell ref held only in a
  register" (GC roots, heap box); line 1450 "replace the State cell"
  (the State column cell of a markdown table, not a carrier term).
- Ambiguous sites: line 1450 "State cell", decided table cell, kept.
- Pinned probes: none.

### 6. `docs/design/yin.vm.linker.md` (24 mentions before)

- Changed: 23. §7.2 "the kept cell", "per-cell round-order" (link
  response flow); §8 r8/r9/r10/r11 amendments: "resource cells", "cursor
  cells", "logical cell", "two refs to one cell", "attachment or cell",
  "a cursor cell", "an FFI cell id" → "an FFI cursor id" (three places,
  §8, §11, §12); §11 file list "cursor cells out of :store"; §13
  reconciliation tables (r8/r9 rows, P1 correlation row "per-entry cells",
  "stays a UCF cell"). Table padding in §13 re-aligned by hand after the
  width change.
- Remaining (1): line 2512 "needs a scheduler-owned cell and an effect
  discipline for writing it" is a shared mutable box, kept.
- Ambiguous sites: "an FFI cell id" (r10 sealed references, and the two
  acceptance restatements). The FFI pair's resource ids are the call-out
  stream handle and the response cursor; the "cell id" there is the
  response cursor's id, so renamed to "FFI cursor id". Line 2512 decided
  heap/mutable box.
- Pinned probes: none quoted.

### 7. `docs/design/yin.vm.linker.dht.md` (46 raw grep hits before; 26 are "cancelled"/"cancellation")

- Changed: 20, all in §14.1 (kept cursors and aliasing across hosts):
  "cursor-cell alias relation" → "cursor alias relation", "logical cell
  identity", "maps to one cell", "distinct cells", "shared cell",
  "independent cell", "sorted cell ids", the safepoint-evidence table
  ("remapped cell", "kept response cell", "response cell"), "`:yin.k/cells`,
  each cell's stream marker" → "`:yin.k/cursors`, each cursor's…", "Cell
  ids are lift-local", "A cell mapping", "source-resource-to-cell map" →
  "-to-cursor", "fresh private cursor resource for each logical cell" →
  "…each logical cursor", test setups "one shared cell", "independent
  cells", "malformed cell references".
- Remaining: 0 carrier mentions; the 20 grep hits left are the substring
  in "cancelled"/"cancellation" (`:dao.space.dht/cancelled`), untouched.
- Ambiguous sites: none; the whole section is the carrier's aliasing
  contract.
- Pinned probes: none quoted.

### 8. `docs/design/yin.vm.ucf-revisions.md` (4 mentions before)

- Changed: 4. §14 stage list: "the one cell table" → "the one cursor
  table"; item 5 "Cell identity discipline: cell ids are lift-local …
  fresh resource keys per cell" → cursor spelling; item 6 "a body
  carrying cells must claim" → "carrying cursors".
- Remaining: 0. Pinned probes: none.

### 9. `docs/design/yin.vm.universal-continuation-format.v2-amendment.md` (11 mentions before)

- Changed: 10. §3 "`C` is a task-local cell identifier" → "cursor
  identifier"; §4 grammar `:yin.k/cells {C …}` → `:yin.k/cursors`;
  "stream/cell references" → "stream/cursor references"; §9 canonical
  census paragraph "Cells are task-local … distinct source cells …
  Every declared cell must be reached and every reached cell declared";
  §10 "an unminted cell" → "an unminted cursor"; acceptance items 4, 10
  and 11 ("distinct cells", "cell IDs", "registers/cells").
- Remaining (1): line 539 "unsupported mutable heap-cell payloads remain
  non-portable" is the heap box, kept.
- Ambiguous sites: line 539 only, decided heap box.
- Pinned probes: item 11 "Assert legacy version-0/1 and ledger pins
  unchanged from the post-D14 baseline" refers to pinned goldens held in
  the test suite, not quoted in the document; see "Probes for the code
  owner" below.

## Documents grepped and left alone

`dao.gui.event.md`, `dao.gui.md` (GUI grid cells), `yang.antlr.md`
(grammar/table cells), `yin.vm-portability.md` (datom cells and
`:cell/ref` attributes, heap), `yin.vm.debruijn.targets.md` (Ribbit's
3-field cells), `yin.vm.semantic.md` (benchmark table cell),
`yin.vm.streams-all-the-way-down.md` ("store cells", "bind/cell logs":
heap-box log), `dao.*` documents (unrelated senses). None mentions the
carrier's cursor.

## Probes for the code owner (not recomputed here)

No design document quotes a concrete root id, checksum or golden
containing the old tags, so there is nothing to regenerate inside the
documents and the old → new list is empty. The tags and ids that are
hashed into tuple bodies, and which therefore change every pinned value
that contains them in the source tree, are:

- census tag `:cells` → `:cursors`, body tag `:cell-body` → `:cursor-body`
- identity ids `:yin.k/c-<n>` → `:yin.k/cur-<n>`
- value tag `[:cell C]` → `[:cursor C]`
- envelope key `:yin.k/cells` → `:yin.k/cursors`, pending keys
  `:yin.k/cell` → `:yin.k/cursor`, `:yin.k/response-cell` →
  `:yin.k/response-cursor` (nested carrier, versions 0-2, if any golden
  still pins them)
- refusal kind `:noncanonical-cells` → `:noncanonical-cursors`

Pinned values the documents point at but do not quote: the v2 amendment's
item 11 "legacy version-0/1 and ledger pins … post-D14 baseline" and the
continuation design's §8.2 `rows-test` root-id comparisons. Both live in
the test suite; the code owner regenerates them with the public hash
function after the source rename.

Code identifiers named in the documents that the source must now match:
`cursors-cref` (root layout slot name, if it is a var), `cursor-body`
helper (`cell-body` before), and the test var
`a-response-cursor-shared-by-two-waiters-crosses-as-one-cursor`.
