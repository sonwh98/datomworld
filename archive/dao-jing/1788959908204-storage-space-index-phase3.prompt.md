Created-GMT: 2026-09-09 13:18:28 GMT
Created-Local: 2026-09-09 20:18:28 +0700 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 09a11e16-5b39-47cd-9534-318b8b20d0bd
# Task: Phase 3 — index closes
Role: Storage & Indexing Engineer
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-09 20:18:28 +0700 | Status: active | Rationale: Storage & Indexing primary; built memory-log and Phases 1 and 2

**Implementation task with write authority, bounded to Phase 3.** Repo
`/Users/sto/workspace/datomworld`, branch `dao.stream-redesign-v2`, clean at
`bafae86`. `bb` is permitted — run the real lanes.

## Read first

`collab/1788950282826-architect-space-transactor-v2-plan-r5.claude-fable-5-1.findings.md`
**§5 is your scope, and §5.4's table is the specification.** Note §4.5/§5.5
carry dated corrections from your own Phase 2 round: `stream-values` and the
`:777` carrier migrate **now**, with the adapter deftests that are their only
callers.

Adversarial review that validated this accounting:
`collab/1788956763164-adversarial-space-transactor-phase2.deepseek-v4-pro.findings.md`

## What this phase does

Deletes index's v1 published adapter and moves the one production consumer —
`:dao.space.schema/published` — onto `index/read-datoms`. At the end,
**`dao.space.index` is v1-free** and `dao.space.schema` is the only
`dao.space` namespace still on v1.

`PublishedSchemaRows` is a small private record in `schema.cljc`: a reader
over the forced row vector, `close!` → `{:woke []}` per D10, `closed?` →
true. It is *simpler* than what it replaces — schema forces the whole vector
at open anyway (`ds/strict-vec` at :1167), so nothing on that path ever
needed the lazy restored trees or a retained store handle.

## The part most easily got wrong

**§5.4's eleven read paths are individually accounted for, and three are
MOVES, not deletions.** A deleted test whose property is covered nowhere is a
defect, not a saving. Specifically:

- **#1** coordinate rejection (P1) — **move** to `query_test`. Nothing there
  covers `open-published!`'s coordinate validation today.
- **#5** open-time fetch count (P3) — **move**.
  `lazy-published-node-budget-is-strictly-bounded` bounds *total* gets at
  ≤ 4; it never pins "exactly 1 at open, zero nodes faulted". Add
  `open-published-fetches-only-the-manifest`.
- **#6** `covered-indexes` (P8) — **stays, split.** Its four structural cases
  remain verbatim and lose the `publish-into-file` fixture entirely; only the
  opened-value assertion moves.
- **#7, #8** (P4, P2) — **move and modernize**; #8 keeps the
  `pr-str`/`read-string` round-trip with a **v2** carrier and asserts the
  **complete exact coordinate map** in place of v1's `ds/exact-bound?`.
- **#9** `stigmergy_test:176-178` `sources` — a *use*, not a test. Every
  scenario in that file calls it.

**Plus P5, a test the adapter never had:** "a store opened during a failed
open is closed before the error propagates". No survivor covers it — the
others prove validation errors, not ownership cleanup. Add an
`open-published!` test with a close-counting store whose manifest read fails
**after** `jing-coordinate/open!` succeeds; assert the original error
propagates and the store closes **exactly once**.

## Do not touch (§5.3)

`ds/defopen :dao.space.schema/current` and its opener; `SchemaWrapper` beyond
Phase 2's edits; `ds/strict-vec` at :260 and `ds/realization?` at :259/:318;
`schema_test:304`'s v1 fixture; schema's closedness model. Schema keeps its own
v1 surface — it has its own plan.

## Closure criteria — greps, not lists

- `ds/` in `test/dao/space/index_test.cljc`: **35 → 0** (verified 35 now)
- `ds/` in `test/dao/space/stigmergy_test.clj`: **3 → 0** (verified 3 now)
- `index.cljc` requires **exactly** `dao.data.btree`,
  `dao.data.btree.storage`, `dao.datom`, `dao.jing`, `dao.stream`.
  `dao.jing.coordinate` goes with the deleted `defopen`;
  `dao.stream.observe` is **never added** (D5). Confirm both, do not assume.
- `#?(:cljs (:require-macros [dao.stream]))` gone from `index.cljc`.

## Verification — run in full, report counts

`bb test:clj` (**under `timeout 900`** — report the exit code; a hang is a
distinct failure mode from a red test), `bb test:cljs` (confirm
`Testing dao.space.index-test` and `…schema-test` in Node output),
`bb test:cljd`, `clj -M:cljs -m shadow.cljs.devtools.cli compile demo`, and
`clj -M:kondo --lint` on every file you touch.

**Clear `.clj-kondo/.cache` before linting** — you found last round that
stdin-linting `git show HEAD:file` poisons it with stale analysis under the
live namespace.

**Format before reporting**: `mise exec -- cljstyle fix <files>`.

Reader-conditional trap: `#?(:clj …)` alone does not exclude from cljd; use
`#?(:cljd nil :clj …)` with `:cljd` first.

## Report

`collab/1788959908204-storage-space-index-phase3.glm-5.3.findings.md` with
Completed-GMT/Local, Coding-Agent: glm, Session-ID: 09a11e16-5b39-47cd-9534-318b8b20d0bd. Exact commands and
counts, both residue greps, index's final require list, and **per §5.4 row,
which test now pins that property**. Name anything you could not honour.
Do not stage or commit.

If the host kills you, write partial findings first — it has twice.
