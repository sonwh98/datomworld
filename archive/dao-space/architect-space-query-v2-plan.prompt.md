Created-GMT: 2026-09-08 10:41:14 GMT
Created-Local: 2026-09-08 17:41:14 +07 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: e425d8bd-ad4c-44f7-aaed-54cb3196fd0f
# Task: migration plan — dao.space.query onto dao.stream
Role: Lead System Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-08 17:41:14 +07 | Status: active | Rationale: Architect primary per team.md; this session authored the dao.jing plan and `dao.stream.observe`, so it holds the v2 contract and the invariants-list method in context

Produce an **implementation plan** for migrating `dao.space.query` from v1
`dao.stream` to `dao.stream`. Emit the complete plan as the body of your
final response; do not edit files. The orchestrator writes it to
`docs/design/dao.space.query.implementation-plan.md` after review.

Note: the seat changed hands. An AGY orchestrator ran out of credits; a Claude
seat took over and re-derived state. Since your last turn, P2 and P3 of the
dao.jing plan landed, both completed plans were deleted as transient, and the
`dao.stream.implementation-plan.md` end condition was restored into
`docs/design/dao.stream.md` under "The v2 namespace is transient".

## Why this, now

The project's primary goal is moving every consumer off v1 `dao.stream`, after
which **`dao.stream` is renamed to `dao.stream`**. 63 `src/` files still
require v1. `dao.space.query` is the gating one: `yin.vm.semantic` cannot be
ported to v2 while it requires `query`, because `query`'s own ns form pulls v1
in regardless of which functions are called. Query first, then semantic.

## Read first

- `docs/design/dao.stream.md` — the v2 contract, especially *Explicitly
  Absent*, which is what makes this a redesign rather than a rename
- `docs/design/dao.space.query.md` — query's own governing design (23KB;
  *The read coordinate*, *Source polymorphism*, *The `read-datoms` contract*,
  *Decisions*, *Open items*)
- `src/cljc/dao/space/query.cljc` (1503 lines)
- `test/dao/space/query_test.cljc` (951 lines)
- `src/cljc/dao/space/index.cljc` and `transactor.cljc` — already dual, for
  the house style of a partial migration

## Measured state — verified, do not re-derive

**Still on v1**, by call site:

| what | where | v2 status |
|---|---|---|
| `ds/IDaoStreamReader` + `ds/IDaoStreamBound` implemented by `ViewStream` and `QueryResultStream` | 44-80 | v2 has a different reader surface; `closed?` does not exist |
| `ds/strict-vec` | 291, 310, 386, 399, 403, 1484 — six sites | *"throwing conveniences — operational outcomes are data"* |
| `ds/open!` | 394 | *"an ambient registry"* |
| `ds/closed?` | 116 | *"a predicate answer is stale the moment it returns"* |
| `ds/exact-bound?` | 138 | v1 bound helper |
| `ds/close!` | 87 | v2 `close!` answers `{ok}` |
| `dsr/relation-descriptor` | 265 — one site | **no v2 relation transport exists** |

**Already on v2:** `stream/bound`, `stream/type`.

**Consumers** — 4 in `src/`: `yin.vm.semantic`, `yin.vm.space`,
`dao.space.transact`, `dao.space.schema`, plus `compilation_pipeline.cljs`
(the v1 demo). 8 test namespaces including `positional_query_test` and
`stigmergy_test`.

**Public surface, 12 fns:** `current-state-seq`, `relation`,
`entity-map-relation`, `current`, `history`, `datoms`, `match`,
`parse-pattern`, `pull`, `pull-many`, `q`, `collect`, `entity-attrs`.
`current-state-seq` is pure over a datom sequence and touches no stream.

## The architectural questions the plan must settle

1. **`q`'s input contract.** Today `q` takes "either an exact-bound
   serializable descriptor or an already-opened realization", and it *opens*
   descriptors itself via `ds/open!`. v2 has no registry: the host owns
   dispatch. Does `q` stop accepting descriptors and require handles, or does
   it take a caller-supplied resolver? This changes the API for every
   consumer, so it is the plan's central decision.
2. **`ViewStream` and `QueryResultStream`.** Both are bounded, already-realized
   row vectors dressed as streams: `next` over a row vector, `close!`
   returning `{:woke []}` (a waiter artifact), `closed?` always true. Are they
   v2 readers at all, or should a bounded realization stop pretending to be a
   stream — the same question Decision 2 of the dao.jing plan answered for the
   durable log? Answer it on the merits either way.
3. **`strict-vec` × 6.** In v2 this is an explicit drain: mint at
   `:dao.stream/oldest`, loop `next`, accumulate `ok`, stop on `end`, and
   *decide* about `blocked`, `gap` and the three defects rather than throwing.
   One shared helper, or six local decisions? What does a `gap` mean to a
   query — and note `dao.stream.observe/step` exists and may or may not fit.
4. **`dsr/relation-descriptor`, one call site, no v2 transport.** Build a v2
   relation transport, replace it with something already in v2
   (`ringbuffer`?), or eliminate the need. Say which and why.
5. **`closed?` and `exact-bound?`.** Both are v1 predicates; `closed?` is
   explicitly absent from v2. What replaces the validation they perform?
6. **Migration shape.** `index` and `transactor` are *dual* — they require
   both v1 and v2 and moved only what they needed. Is dual the right shape for
   query too, or does its stream surface have to move as one piece? What does
   `dao.space.query.md` have to say afterward, and which of its *Open items*
   does this close or change?

## Constraints

- **`public/demo.html` must keep working.** It loads `main.js` from the
  `:demo` shadow build, whose `:init-fn` is `datomworld.demo`, which requires
  `compilation_pipeline.cljs` (v1), which calls `query` through
  `semantic/find-by-type`. Nothing in this plan may break that page.
- `yin.vm.space` is a v1 VM slated for deletion under
  `yin.vm-consumers.implementation-plan.md`; do not plan work for it.
- Do not plan `dao.space.schema`, `index` or `transactor`; each is its own
  unit. Name what this plan leaves them owing.
- Nothing is in production, so there is no compatibility constraint and the
  tests are re-writable where an invariant is preserved — the framing that
  reshaped the dao.jing plan.

## Shape

Use the method that worked for dao.jing: an explicit **invariants list** as
the contract, each item marked with its source — stated in
`dao.space.query.md`, pinned only by a test, pinned by a test and worth
promoting into the design, or judged an implementation accident and dropped
with its reason named. Then decisions, then phases ordered by what is
buildable and checkable, each with what proves it on clj, cljs (Node) and
cljd. State the end condition and what remains v1 afterward.

Be concrete about size: 1503 lines of source and 951 of test is the material.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: e425d8bd-ad4c-44f7-aaed-54cb3196fd0f
