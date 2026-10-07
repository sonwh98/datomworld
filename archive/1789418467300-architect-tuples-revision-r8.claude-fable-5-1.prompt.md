# Architect revision round 8 — owner ruling: flat rows canonical, map AST is the semantic layer

You are revising `docs/design/yin.vm.code-as-tuples.md` under a new owner ruling
issued 2026-09-15 through orchestrator dialogue, after your r7 reached
APPROVE-WITH-FINDINGS. This is not a defect round: the owner has ruled on the
tree level, and the ruling supersedes parts of r7's ontology. Your r7 structure,
analysis, and verified claims are the base; the revision redirects the design.

## The ruling (owner statements, verbatim, 2026-09-15)

1. "i dont want to build a LISP with vectors instead of lists. i want to build a
   LISP using maps and vectors is a projection of that map to make a fast
   bytecode VM"
2. "the map ast doesn't get content-hashed and stored. the projection of the ast
   as flat tuples is what gets content-hashed and stored"
3. "the universal AST as a map is the semantic layer. the tuple representation
   of the AST is the linearalization or projection of it for the semantic vm"
4. "my model does not have a tuple tree"
5. "the flat tuple form of the map AST has all the information to reconstruct
   the map AST"

This clarifies owner ruling 1 of 2026-09-14 ("its representation as flat
tuples"): *flat* was meant literally. r7's nested Erlang-shaped tuple tree was
an interpretation of the ruling, and it is superseded. The orchestrator has
verified the ruling's model is coherent and lossless before chartering this
round (round-trip law below).

## The ruling as design constraints

1. **The map AST is the language's own representation — the semantic layer.**
   Tag + named fields, the walker's current form. It is never content-hashed,
   never stored, never shipped. It exists on both sides of storage:
   frontend-side before projection, machine-side after reconstruction. It is
   ephemeral and per-consumer, like an image.
2. **The canonical artifact is the flat per-node row projection**
   `[id tag & slots]`: child slots hold child ids, `nodes` slots hold ordered
   vectors of child ids, data slots hold their data unchanged. Addressing is
   merkle per row: each row's id is `(dao.jing/segment-key row)` computed with
   children's ids inline, and a tree's address is its root row's id. These rows
   are what `dao.jing` stores, streams carry, addresses name, and `q` queries.
   **There is no nested tuple tree anywhere in the design.** Whether rows are
   stored as individual values (DAG, git-style) or packed per tree is an
   implementation note, not ontology; state it as open.
3. **The §2.3 grammar table becomes the projection/reconstruction dictionary.**
   Slot names return as a column (they are load-bearing again: they name the
   map fields at reconstruction). State the arity ↔ key-set correspondence
   explicitly: a tag's slot list in order IS the map's key set, so the table
   defines both directions of the round trip.
4. **The instruction vector (§5, UCF §7.3.2) is unchanged and was already this
   shape** — flat positional tuples, canonical, refs resolved to pcs. The two
   levels are now uniform; say so where the document contrasts them.
5. **The walker stays on map ASTs exactly as built.** No sweep of walker arms
   or tests; §2.6's frame re-scheme is withdrawn as a design requirement
   (runtime `assoc` into map nodes is legal — the node is the machine's
   ephemeral image, not canonical form; a short walker-local note may keep the
   reasoning). Load = validate rows, reconstruct maps. Reconstruction
   precedent: `datoms->ast` (`src/cljc/yin/vm.cljc:494-559`) already
   rebuilds map ASTs from flat rows through entity ids; the loader is its
   successor with content addresses instead of allocated ids.
6. **Frontends keep emitting map ASTs**; a permanent pinned projection step at
   the boundary (strip the §2.5 exclusions, saturate per §2.4, positionalize,
   merkle) produces the canonical rows. The r7 §9.1 adapter stops being a
   migration device and becomes the standing contract.
7. **The round-trip law is a first-class conformance obligation:** for every
   corpus program, `map → rows → map` is the identity on the canonical map
   AST, and `rows → map → rows` is the identity on the rows. This restates
   §7.2's both-paths obligation under this ontology; fold it in there.

## Section delta map

- **Header**: revision 8; the rulings list grows the clarification (eight
  rulings, ruling 1 clarified).
- **§1**: the Content example becomes a row (r7's §6.1 example — `[A :lambda
  [x] B]` etc. — is now *the* content example, no longer a derived projection).
  Add one sentence naming the semantic layer's home: the map AST is ephemeral,
  exists at both ends, and is never persisted — a presentation, not a fourth
  layer. The Query layer keeps its members (segment rows, datom projection,
  the occurrence relation); the flat per-node rows move OUT of Query into
  Content — they are the stored form, not a view of something else.
- **§2.1**: invert the ontology. The map AST is the code's own representation;
  the flat rows are its canonical projection — total and lossless by the
  round-trip law — and the rows, not the map, are what persists, travels, and
  is named. The Erlang nested-shape citation is withdrawn (or kept only as
  historical motivation for positional slots, if a sentence needs it). No
  sentence may remain that calls the nested tree the AST.
- **§2.2/§2.3**: grammar survives as the dictionary; slot-name column added;
  the Tuple column becomes rows `[id tag & slots]` (or the row form is shown
  beside the map form per tag).
- **§2.4, §2.5**: discipline unchanged, restated as rules of the projection
  boundary (saturation and exclusions happen map→rows, never the reverse).
- **§2.6**: withdrawn as a requirement; reduce to a walker-local note.
- **§3**: unchanged. §3.2's `:stream/close` walker arm is a small correctness
  fix independent of representation; keep it.
- **§4**: mechanism inverts — per-row merkle ids, tree address = root row id.
  §4.1's payoff table survives verbatim in meaning (a root id is still a
  whole-tree address; derived-from, the macro ledger, manifests, lowering all
  name it identically). §4.3's per-lambda follow-up DISSOLVES (every node is
  addressed by construction; say so). §4.4 sharing becomes structural in the
  stored DAG. §4.2's dao.jing encoder blocker stands verbatim — rows carry
  data slots, the collision table is unchanged, every identity use stays
  blocked.
- **§6.1**: direction inverts. Rows are storage/canonical; `rows → map` is
  load-time reconstruction; `map → rows` is the frontend boundary projection;
  `q` over the rows is unchanged (ruling 3 was verified live over exactly this
  shape). The occurrence relation stays.
- **§7.1**: walker row becomes — validate rows (§7.4), reconstruct the map
  AST, set `:program`/`:control`; no conversion to any tuple node form.
  Semantic rows unchanged.
- **§7.4**: validator keeps tag/arity/slot-kind/saturation and gains reference
  rules — child ids resolve within the loaded set, the graph is acyclic,
  every row is reachable from the root — mirroring §7.5's existing segment
  reference rules (`:target-bounds`, dense pcs). Name the defect kinds.
- **§9.1**: shrinks. Walker arms and tests untouched; the linearizer reads the
  row relation (same get-attr-over-an-index shape as today); the codec becomes
  the rows⇄datoms projection pair; frontends emit maps plus the permanent
  projection step; the §2.6-related sweep rows go.
- **§10**: blockers stand; add the round-trip law where §7.2 lands it.

## What must not change

- §5 segments and UCF §7.3.2 (already flat-canonical).
- §8 ledger, refs, and the datoms-as-reference-layer rulings (6 and 7).
- §6.3 q mechanics; §6.5 datom projection.
- §7.5–§7.7 (vector validation, hot loop, dependency/effect machinery).
- §4.2 verbatim.
- House table format: ASCII box tables, 170 columns in characters,
  `docs/agents/file-format.md`. Re-verify width after every table edit.

## Acceptance

astra re-reviews against the ruling. Every executed claim stays line-anchored;
re-verify line numbers of any code you cite newly. Keep section numbering and
the protocol voice. The doc keeps its name.
