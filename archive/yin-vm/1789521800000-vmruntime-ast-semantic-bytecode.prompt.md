Created-GMT: 2026-09-15 21:23:20 GMT
Created-Local: 2026-09-16 04:23:20 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: claude
Session-ID: 206534e8-0432-4941-8631-0212c8f59132
# Task: Implement yin.vm/ast->semantic-bytecode (map AST -> flat content-addressed rows)
Role: VM Runtime
Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-16 04:23:20 +07 | Status: active | Rationale: deep context processing, AST/compiler expertise per team.md

## Context

Read first, in this order:
1. `docs/design/yin.vm.code-as-tuples.md` §1 (layering), §2 (the AST tuple
   grammar — the full tag table with body arities, especially the note
   under it: "the slot list of a tag, in order, is exactly the map's key
   set beyond `:type`"), §4.1-4.2 (addressing), §4.4 (sharing by content
   address), §6.5 (the codec boundary projection pair), and §7.2 (the
   round-trip law). These sections are authoritative; do not deviate from
   the tag table.
2. `src/cljc/dao/jing.cljc` — `segment-key`, `content-hash`,
   `materialize!`. This is the content-addressing primitive you'll use to
   mint each row's id. Note: another engineer is concurrently fixing this
   file's encoder for a metadata/set/record collision edge case — that
   work is NOT your concern; the encoder already handles the plain
   vectors of keywords/symbols/numbers/strings that AST rows are made of,
   which was never part of what's broken. Do not edit `dao.jing.cljc`.
3. `src/cljc/yin/vm.cljc` lines 280-560 (`ast->datoms-with-root`,
   `ast->datoms`, `datoms->ast`). This is the EXISTING codec — it already
   converts the map AST to/from a *datom-quad* representation (`[e a v t
   m]`, DaoDB-style refs with tempids) using named variables
   (`:yin/name`, `:yin/params` — that part is already correct and
   up-to-date). Your job is a DIFFERENT, NEW representation: flat rows
   `[id tag & slots]`, content-addressed, no tempids, no `t`/`m`. Use
   `ast->datoms-with-root`'s `case`-over-`:type` structure as your
   template for the node-type dispatch (same cases, same fields per node
   type), but the emission mechanics are entirely different — see Task
   below. Do not modify `ast->datoms-with-root`/`ast->datoms`/
   `datoms->ast` — they remain in place, this is additive.

## Task

Implement two functions in `src/cljc/yin/vm.cljc` (or a new file
`src/cljc/yin/vm/codec.cljc` if you judge that cleaner — your call, but
if you create a new file, require it properly from wherever it needs to be
used and say so in your findings):

### `ast->semantic-bytecode : map-ast -> {:root row-id, :rows {row-id row}}`

Project a map AST into the flat row form per the §2 tag table. For each
node:
- Compute its **body** `[tag & slots]` exactly per the tag table (e.g.
  `:variable` → `[:variable name]`, `:lambda` → `[:lambda params body-id]`
  where `body-id` is the *already-computed* id of the converted body node
  — children are converted bottom-up so their ids are known before the
  parent's body is built).
- Mint the row's id via `(dao.jing/segment-key body)` — this is what makes
  the id a `:segment/sha256-...` content address.
- The full row is `(into [id] body)` — i.e. `[id tag & slots]`.
- **Structural sharing**: two nodes with an identical body hash to the
  same id and must be stored as ONE row (§4.4) — do not emit duplicate
  rows for structurally-identical subtrees (this includes exact duplicates
  encountered via shared references in the input map, if any, and
  independently-constructed but structurally-identical subtrees).
- Handle every tag in the §2 table, including `:global` (`[:global
  name]`, same slot kind as `:variable` — `:global` is a new tag added by
  this design, not present in `ast->datoms-with-root`; treat any node
  with `{:type :global :name sym}` as a first-class case even though
  nothing in the current tree constructs one yet).
- **Exclusions (§2.5)**: strip anything not in the tag table's slot list
  before hashing — source positions, frontend metadata (`:yang/*` keys,
  reader metadata), `:eid`. These never enter a row's body. If the input
  map AST carries them, drop them silently (they are not this function's
  concern to preserve — that's what side tables are for, and side-table
  emission is explicitly OUT OF SCOPE for this unit, see Boundaries).
- Saturate defaults and mark `:tail?` per §2.3/§2.4 exactly as the
  existing walker/codec already does it for `:application` — check
  `ast_walker.cljc` and `v2.cljc:412` (`(when tail? (emit! e :yin/tail?
  true))`) for the existing tail-marking convention, and replicate the
  same rule (tail marks on `:application` only, per §5.2.1's reference
  profile row "normalization applied before emission").

### `semantic-bytecode->ast : {:root row-id, :rows {row-id row}} -> map-ast`

The inverse: reconstruct the canonical map AST from a root id and row
set. This is `rows → map` (§6.1/§7.1 load-time reconstruction). Validate
as you reconstruct:
- Every row's `id == (dao.jing/segment-key body)` (content integrity) —
  throw a clear `ex-info` if not (a corrupted or foreign row set).
- Every referenced child id (in a `node`/`nodes`-kind slot) resolves to a
  row in the set — throw if not (`:id-resolves` per §7.4's table).
- Reconstruct field names from the tag table's Slots column (the inverse
  of the projection — position `i` of a row body holds the field the
  Slots column names at `i`).

## Contract: the round-trip law (§7.2)

This is the acceptance criterion, not optional polish. Add tests
(`test/yin/vm_test.cljc` is the natural home, alongside the existing
`ast->datoms`/`datoms->ast` round-trip tests if any exist there — check
first) asserting, for a corpus of representative programs covering EVERY
tag in the §2 table at least once (literals, variables, globals, lambdas
with 0/1/N params, applications with 0/1/N operands and both tail?
true/false, if, dao.stream.apply/call, vm/gensym, vm/store-get,
vm/store-put, vm/current-continuation, vm/park, vm/resume, stream/make,
stream/put, stream/cursor, stream/next, stream/close):

- `map → rows → map` is the identity on the *canonical* map AST (i.e. on
  a map AST that's already free of `:yang/*` keys, stray `tail?` marks
  not on `:application`, and other §2.5 exclusions — construct your test
  fixtures already canonical, don't test the stripping behavior itself as
  part of round-trip identity, just construct clean input).
- `rows → map → rows` is the identity on the rows.
- Structural sharing: construct at least one test case with two
  independently-built, structurally-identical subtrees (e.g. two
  `{:type :literal :value 1}` nodes reachable from different parents) and
  assert they collapse to the same row id and there's exactly one row for
  them in the result's `:rows` map.

Run these tests on the JVM host (`:clj`) and, if you can, `:cljs` too.
**Do NOT run `bb test:cljd`** — the ClojureDart host's shared generated
output is currently owned by another concurrent process; leave cljd
verification for later, explicitly say in your findings that you did not
run it and why.

## Boundaries

- Only create/edit: `src/cljc/yin/vm.cljc` (or a new
  `src/cljc/yin/vm/codec.cljc` if you choose that path) and
  `test/yin/vm_test.cljc` (or a new test file alongside it, your
  call).
- Do NOT edit `src/cljc/dao/jing.cljc`, `test/dao/jing_test.cljc`, or
  `docs/design/yin.vm.code-as-tuples.md` — all three are owned by
  concurrent work.
- Do NOT run `bb test:cljd` (CLJD lane is owned by concurrent work).
- Do NOT implement the "Combined Atomic Output" S1/S2 side-table datom
  bundling, the dedicated AST indexer, or any side-table emission
  (source positions, frontend metadata, variable-name side tables --
  none of that exists as a side-table concern for this design since names
  are inline, but do not invent new side-table machinery either way).
  This unit is scoped strictly to the map<->rows projection pair and its
  round-trip law. That larger envelope question is architecturally
  unresolved (see `collab/1789502626000-claude-review.findings.md`, Fix
  2) and explicitly out of scope here.
- Do NOT touch `ast_walker.cljc`, `linearize.cljc`, `semantic.cljc`, or
  `code.cljc` — those are later, separate units.
- Do not stage or commit. Leave your work as an uncommitted working-tree
  diff.
- Do not invoke further subagents or delegate this work onward.

## Deliverable

Produce the complete deliverable without waiting for further input. Report:
- exact functions/files added or changed, with line ranges
- how you handled structural sharing and content addressing
- the round-trip corpus test coverage (which tags exercised, pass/fail,
  exact command and output)
- any tag from the §2 table you could not cleanly implement and why
- confirmation you did not touch any of the boundary-excluded files or
  run bb test:cljd

Write your findings to
`collab/1789521800000-vmruntime-ast-semantic-bytecode.claude-opus-5.findings.md`
with the same header block as this prompt.
