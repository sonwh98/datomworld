
# dao.space.query on DaoStream v2

Status: implementation plan, subordinate to [`dao.stream.md`](./dao.stream.md)
(the contract) and [`dao.space.query.md`](./dao.space.query.md) (the design).
It follows the method that reshaped the `dao.jing` plan: an explicit
invariants list is the contract, the existing implementation and tests have
no authority beyond the invariants they pin, and old code is deleted in the
phase that replaces it. Nothing is in production. This document is transient.

## Two corrections to the brief, before anything depends on them

- **`query.cljc` is not partly on v2.** It requires `dao.stream` and
  `dao.stream.relation` and nothing from `dao.stream.v2`; its
  `:dao.stream/type` and `:dao.stream/bound` are keyword literals in maps, not
  calls. The "already on v2" row belongs to `index.cljc`, which does call
  `stream/append!`. So there is no partial migration to extend; the whole
  stream surface moves at once, which turns out to be the right shape anyway
  (Decision 5).
- **`yin.vm.semantic` is scheduled for deletion, not porting.**
  `yin.vm.v2-consumers.implementation-plan.md` Phase 1 deletes
  `src/cljc/yin/vm/semantic.cljc` as an excluded experimental VM. The brief's
  "query first, then semantic" therefore does not describe a port that
  follows; it describes the ordering in which query stops being the thing
  that keeps a v1 require alive in `semantic` until `semantic` goes. Either
  way query must end v1-free, and this plan does not depend on which happens
  to `semantic`. `compilation_pipeline.cljs`, the `:demo` build's v1 path,
  calls query through `semantic/find-by-type` and must keep working; it uses
  only `relation`, `current`, `q`, `collect`, `entity-attrs` and
  `current-state-seq`, every one of which keeps its call shape below.

## The license

The owner has authorized reimplementing `dao.space.query` from scratch against
`dao.space.query.md` on `dao.stream.v2`, rather than editing the v1 file. That
license is used where the shape is stream-facing — inputs, ownership, views,
results, and the snapshot — which is exactly what Decisions 1-3 rewrite. It is
deliberately *not* used on the evaluator: `match` through `q`, the planner,
rules, aggregates and `pull` (roughly `query.cljc` 416-1475) touch no stream
and answer to `dao.space.query.md`'s *Datalog surface* and *Pull* sections,
whose executable contract is the existing `query_test`. Rewriting them from
scratch would put several hundred pinned invariants at risk and buy nothing
the migration needs. So: the stream surface is written new, the evaluator is
carried over, and the invariants list below is what the result is judged
against either way.

## What query is, in one paragraph

`dao.space.query` is an embeddable Peer: given finite relations of tuples, it
runs positional `match`, Datalog `q`, and `pull` above them, with `current`
and `history` as the two explicit interpreters of canonical d5 datoms. It is
pure and stateless. It owns no durable state, never writes, and enforces no
schema. Everything it reads is either a relation value it was handed or a
published covered index reached through a `dao.jing` content store. **It has
never read a live stream.** Every "stream" it touches today is a bounded,
already-realized row vector wearing the v1 reader protocol: the relation
transport carries its tuples inside the descriptor, `ViewStream` and
`QueryResultStream` are vectors with a position counter, and
`PublishedIndexStream` is a delayed vector over restored B-trees. That fact
decides most of this plan.

## The invariants

Marked by source: **[D]** stated in `dao.space.query.md`; **[T]** pinned by a
test; **[T→D]** pinned by a test, not stated, and worth adding to the design;
**[T✗]** pinned by a test, judged an accident of the stream dressing, dropped
with its reason. Grouped by the test namespace that will exercise each.

### I. Inputs — `query_test` (R1)

- I1 [D] Raw vectors and raw maps are not database inputs; they throw before
  any evaluation, with a message naming what is accepted. Callers wrap
  tuples with `relation` and entity maps with `entity-map-relation`.
- I2 [D] A relation is an arbitrary, mixed-dimensional tuple collection;
  arity never selects an interpretation.
- I3 [D] `entity-map-relation` normalizes each `{:db/id e k v …}` to `[e k v]`
  facts; a map without `:db/id` throws. The read side never mints an id.
- I4 [D] A published covered index is named by a **serializable coordinate**:
  a content-store coordinate plus a manifest address (`index/published-index`
  builds it). The coordinate is data and may travel through a stream. A bare
  content-store handle carries no source and is not an input.
- I5 [D] `q` accepts several independent database inputs under `:in $ $2 …`
  and never merges them; source identity is interpreter context, never a
  tuple slot.
- I6 [T✗] A database input must carry `:dao.stream/type` and an *exact*
  `:dao.stream/bound`; symbolic bounds (`:open`, `:closed`, booleans) are
  rejected; a borrowed input must satisfy `IDaoStreamBound` and answer
  `closed?` true. All of this existed to make a finite value *look like* a
  stream that had finished. Under Decision 1 inputs are values, finite by
  construction, so there is no bound to check and no closedness to ask.
  Dropped. What survives of it is I1: the input must be a value query
  constructed or a published index query opened, not something loose.

### V. Views — `query_test` (R2), `positional_query_test`

- V1 [D] `(current src)` resolves the greatest `(t,m)` per `[e a v]`, drops
  retractions, projects to d3; `(history src)` exposes exact d5.
- V2 [D] `as-of` bounds visible datoms to `t <= as-of` and is accepted only
  with a datom view. A bare relation has no temporal semantics even when
  every row has five slots.
- V3 [D] Same `[e a v t]` with differing `m` is conflicting history and
  throws; `current-state-seq` is the pure function that does this over any
  d5 sequence.
- V4 [D] Transaction envelopes `{:dao.space/transaction {:t n :datoms […]}}`
  flatten into their datoms; a malformed envelope or mismatched `t` throws;
  any other non-d5 element throws.
- V5 [T] A view is a value that carries its source and its `as-of`; the same
  `current`/`history` call shape works over a relation value, a snapshot, or
  an opened published index.
- V6 [T✗] A view over a "realization" is itself a closed `IDaoStreamReader`
  with `{:woke []}` on close and `closed?` true, drainable by cursor. Dropped
  with the stream dressing; the view is a value, and `rows` (Decision 2)
  gives its resolved rows to a caller that wants them.

### E. The evaluator — `query_test` (:in bindings, negation, aggregation, …), `positional_query_test`

Unchanged code and unchanged contract. Listed so the implementer knows what
must not move: the roughly one thousand lines from `match` through `q`'s
evaluation are untouched by this plan.

- E1 [D] Positional matching with exact arity and the `& _` / `& ?tail` rest
  syntax, in `match` and in pattern clauses alike.
- E2 [D] `not`, `not-join`, `or`, `or-join`, `and`, with the binding rules
  the design states (unbound var in `not` throws; `or` branches bind the
  same set; `or-join` branches bind every join var).
- E3 [D] Aggregates with `:with`, Datomic's project/dedupe/group pipeline.
- E4 [D] Function and predicate clauses resolve only through the caller's
  `:fns` and the default `builtins`, disableable with `{:builtins false}`;
  no symbol resolution, no hidden registry. `get-else` and `missing?` probe
  the fact index and require a current fact view.
- E5 [D] Rules via `%`, rule-local scope, cycle termination, arity checks.
- E6 [D] Find specs — relation, scalar, tuple, collection — and the return-map
  forms, arity-checked.
- E7 [D] `pull` / `pull-many` / `entity-attrs` per the Pull rulings; `pull`
  requires a current fact view; unknown entities echo `{:db/id e}`.
- E8 [D] The planner reorders only contiguous runs of pattern clauses and
  never forces a deferred relation (the multi-clause budget test).
- E9 [T] Results are distinct.

### L. The lazy published path — `query_test` (Step 3)

- L1 [D] A `current` view with no `as-of` over a published index routes
  3-fixed clauses through the restored covered sets; only the manifest plus
  the seek path and matching range are fetched (the point-clause and
  multi-clause budget tests: ≤ 4 and ≤ 15 gets).
- L2 [D] `history`, any `as-of`, rest patterns and 4+-slot patterns fall back
  to the eager drain of the deferred rows and give the same answers.
- L3 [D] Eager and lazy agree on every `[e a v]` projection; `t`/`m`
  provenance differs by design and `datoms` exposes it.
- L4 [T→D] Planning never forces the deferred relation. The design says it in
  a bullet under *The `read-datoms` contract*; it belongs under *Index
  realization* as a stated property, since it is what makes the lazy route
  exist for multi-clause queries at all.

### R. Results — `query_test` (R3)

- R1 [D] `q` returns a local result value carrying the find spec; `collect`
  materializes it into the relation / scalar / tuple / collection /
  return-map shape. The split stays because every consumer, including the
  demo, is written against it.
- R2 [D] A result is not a descriptor: it is not serializable, not
  reopenable, and carries no external bound claim. To transport one,
  `collect` it and wrap the tuples with `relation`.
- R3 [T✗] The result satisfies `IDaoStreamReader`, answers `closed?` true,
  and can be drained by `{:position n}` cursors. Dropped; R1 and R2 are what
  those tests were protecting.

### O. Ownership — `query_test` (R4)

- O1 [D] `q`, `match` and `pull` never close an input they were handed.
- O2 [T✗] `q` opens descriptors itself, owns what it opens, closes it in a
  `finally`, and closes already-opened inputs when a later one fails. This
  existed only because `q` opened things. Under Decision 1 `q` opens nothing:
  the caller opens a published index, holds the store, and closes it. O1
  becomes the whole rule, and the exception-safety machinery goes with O2.
  The pinned "exactly one store close per query" moves to the caller and is
  no longer query's to prove.

### S. Snapshotting a stream — new, `query_test` (R5)

The one place query touches `dao.stream.v2`, replacing the six `strict-vec`
sites and the borrowed-realization path with one explicit interpreter.

- S1 [T→D] A live stream becomes a query input only by an explicit snapshot:
  mint at `:dao.stream/oldest`, read to `blocked` or `end`, and return the
  values as a relation value together with the cursor reached and the
  outcome that stopped the read. `q` never reads a handle.
- S2 [T→D] Every stopping outcome is data, not an exception: `:ended`,
  `:blocked` (an open stream, caught up — a snapshot at call time, which is
  what v1 `->seq` promised), `:gap` with the recovery cursor and the values
  read before it, `:defect` with the raw answer. The caller decides whether
  a partial snapshot is a usable relation; query has no basis to decide for
  it. This replaces "blocked and gap throw during traversal" (`query_test`
  R5), which was a stream-shaped rule about a value-shaped library.
- S3 [T→D] Snapshotting never advances a cursor past a value it did not
  retain (the step's effect-before-commit), and never closes the handle.

### C. Consumers — `schema_test`, `stigmergy_test`, `transact` tests, the `:demo` build

- C1 [T] `relation`, `entity-map-relation`, `current`, `history`, `q`,
  `collect`, `match`, `pull`, `pull-many`, `entity-attrs`, `current-state-seq`
  keep their names and call shapes for value inputs. `transact.cljc`,
  `semantic.cljc` and `compilation_pipeline.cljs` need no edit.
- C2 [T] `schema.cljc` reaches into query's records at two sites
  (`ds/strict-vec (query/history …)` at 255-256; `query/->ViewStream` at
  294). Those become `query/rows` and `query/fact-relation` (Decision 2) — a
  mechanical two-site edit that keeps schema's own v1 surface untouched.

## Decisions

### Decision 1 — `q` takes values; it opens nothing

`q`, `match` and `pull` accept exactly: a relation value; a datom view over a
relation value, a snapshot, or an opened published index; an opened published
index directly (where a d5 relation is meant); and plain data for scalar,
tuple, collection and relation `:in` bindings. **Descriptors are no longer
inputs.** `q` neither opens nor closes anything.

Why not a caller-supplied resolver. The contract retired the registry
(`dao.stream.md`, *Explicitly Absent*) and made dispatch a host-owned map.
A resolver parameter on `q` would be that map's last hiding place: every
caller would build one to reach two cases — the relation transport, whose
"opening" is nothing because the tuples are already in the descriptor, and
the published index, whose opening is `dao.jing.coordinate/open!` plus a
manifest read, which is not a stream operation at all. Neither needs
dispatch on `:dao.stream/type`; both need a function call the caller can
make. So the caller makes it:

```clojure
(query/relation tuples)                       ; => relation value
(query/entity-map-relation maps)              ; => relation value of [e k v] facts
(query/open-published! coordinate)            ; => published-index value; caller owns the store
(query/close-published! opened)               ; closes the store it opened
(query/snapshot handle)                       ; => {:relation v :status … :cursor c …}  (Decision 3)
(query/current src as-of?) (query/history src as-of?)
(query/q form & inputs) (query/collect result) (query/match src pattern) (query/pull src e pattern)
```

`open-published!` is query's because the read coordinate is query's design
(*The read coordinate*); it calls `jing-coordinate/open!`,
`index/read-manifest` and `index/restored-indexes`, all public, and returns
`{:dao.space.query/published coord :indexes {…} :rows (delay …) :store h}`.
The coordinate stays exactly the serializable map `index/published-index`
builds — that portability is I4 and is untouched — and `index.cljc`'s
`ds/defopen :dao.space.index/published` is left standing, dead, for index's
own plan to delete.

What this costs: `open-db-inputs!`, `close-owned!`, `quiet-close!`,
`validate-borrowed!`, `validate-descriptor!`, the `::owned` plumbing through
`realize-db-value!` and `realize-datom-view!`, and the `finally` blocks in
`q`/`match`/`pull`/`pull-many` — about 120 lines — are deleted. What it buys:
`q` is a pure function of its arguments, which is what the design already
claimed it was.

### Decision 2 — Bounded realizations stop pretending to be streams

`ViewStream`, `QueryResultStream`, and query's use of `RelationStream` are
deleted. The contract's reader surface promises "one positioned, append-only,
retained sequence that cursors can observe and re-observe"; it exists so that
independent cursors can watch a sequence *over time*, so that a descriptor
can name it across a serialization boundary, and so that eviction is
reported as `gap`. A row vector computed in full has none of those needs: no
second observer, no future appends, no eviction, no reachability. Making
these v2 readers would owe each a manifest, an exclusion reason for every
outcome it cannot produce, and the conformance harness — the same "transport
with one consumer owes the conformance suite for nobody" that retired
`dao.jing.file`'s log stream — and would buy a `next` loop over `nth`.

They become values:

- a relation value `{:dao.space.query/relation tuples}` (`relation`,
  `entity-map-relation`, and `fact-relation` — the explicit current-fact
  variant that `schema` builds directly, marked `:fact? true` so the fact
  index is built for it);
- a view value `{:dao.space.query/view :current|:history :source src :as-of t}`,
  the shape the descriptor path already returned;
- a result value `{:dao.space.query/result rows :spec s :return-map …}`;
- `rows`: view or relation value → the resolved row vector, for callers that
  used to `strict-vec` a view.

Structural dispatch inside `realize-db-value!` becomes a `case` on the one
tag each value carries. Raw vectors and maps still throw (I1), with the same
message class the tests match on.

### Decision 3 — One snapshot interpreter, built on `observe/step`

The six `strict-vec` sites do not become six decisions; five of them vanish
with Decisions 1 and 2 (two view-over-realization sites, the borrowed-input
site, two opened-descriptor sites, and `collect`'s drain of a result that is
now a set). What remains is one genuine need — turning a v2 reader handle
into a relation — and it is written once:

```clojure
(query/snapshot handle)
;; => {:relation {:dao.space.query/relation [v …]}
;;     :status   :ended | :blocked | :gap | :defect
;;     :cursor   c            ; the cursor reached; on :gap the last retained position
;;     :recovery c'           ; :gap only
;;     :read     raw}         ; :defect only
```

It mints at `:dao.stream/oldest` and loops `observe/step` with a total effect
(`conj` onto the accumulator, answering `ok`) until the step returns
`:retry`, `:ended`, `:gap` or `:defect`. `step` fits exactly: the effect is
local and total so every read advances (glm's `snapshot-datoms` remark), the
successor-only cursor discipline is the seam's, and the four stopping
outcomes are already classified as data. No policy lives in `snapshot`: a
`gap` means "these values, then a hole" and the caller — a test composition,
a future `dao.space` reader — decides whether that is a relation it can
query. This is S1–S3.

`snapshot` is the only function in `dao.space.query` that requires
`dao.stream.v2` or `dao.stream.v2.observe`. `index/snapshot-datoms`,
`schema`'s remaining `strict-vec` sites and the transactor's local-stream
walk will want the same function when their plans run; it stays in query
until a second consumer arrives, at which point it moves to
`dao.stream.v2.observe` as `drain` — the "unify on the third copy" rule,
recorded so the second copier knows where to put it.

### Decision 4 — No relation transport, in v2 or anywhere

`dsr/relation-descriptor` is eliminated, not replaced. The v1 relation
transport is a descriptor that *contains* its tuples — twice, once under
`:tuples` and once inside the bound — and "opens" into a record that reads
them back by index. The contract says a descriptor "only names one — never
executable code"; a descriptor that is the data is not reachability data,
it is a value with a `:dao.stream/type` on it. Under Decision 2 `relation`
returns the value directly. Nothing is lost: a relation value is plain data
and travels through any stream that can carry it, which is what the design
meant by "a DaoStream descriptor is itself data and may be transported."
`dao.stream.relation` has no other consumer in `src/`; it is deleted with
its `defopen`, and `dao.stream.md`'s list of remaining v1 consumers shrinks
by "relation". It has one consumer in `test/`:
`dao/stream_test.cljc`'s `relation-descriptor-contract-test` (the whole
deftest, using `relation/relation-bound` at 514 and 551 and
`relation/relation-descriptor` at 536) is the v1 transport's own contract and
is deleted with it — the deftest entire, and the `dao.stream.relation`
require at line 5.

### Decision 5 — In place, one piece, not dual

`index` and `transactor` are dual because their v1 uses are *call sites* —
an `append!` here, a `satisfies?` there — that can move one at a time while
the namespace keeps compiling. Query's v1 uses are *structural*: two
`defrecord`s implementing v1 protocols, a `defopen` transport, and a
validation layer whose only subject is v1 stream shape. There is no call
site to move first that leaves the records meaningful, and there is nothing
in query that should stay on v1 afterward. So: one change to `query.cljc`,
one to `query_test.cljc`, the two-site edit in `schema.cljc` (C2), and the
call-site edits in `stigmergy_test.clj` — landed together, green on three
hosts. `dao.space.query.md` afterward: *Source polymorphism* is rewritten
from "descriptor or realization" to "value or opened index, and snapshots
of streams"; *The read coordinate* keeps its coordinate shape and loses "q
opens the descriptor"; the "After close, loudness is the backend's" bullet
becomes the caller's, since the caller closes. Of the *Open items*, none
closes; the second — generic positional indexes — is unchanged, and the
first — K-way merge over several manifests — becomes easier to state, since
several opened-index values in one `:in` is exactly what `q` already takes.

## What is built, in two phases

Each phase leaves the suite green on clj, cljs (Node) and cljd and deletes
what it replaces.

### P1 — Query over values

**Build**, in `query.cljc` (1503 lines; roughly 130 deleted, 70 added, the
evaluator from line 416 to 1475 byte-for-byte untouched):

- the value constructors and tag predicates of Decision 2; `rows`;
  `fact-relation`;
- `open-published!` / `close-published!` of Decision 1, over
  `jing-coordinate/open!`, `index/read-manifest`, `index/restored-indexes`;
  `covered-indexes` keeps working on the opened value because it is a
  structural check on `:indexes`;
- `realize-db-value!` as a `case` over the value tags, returning
  `{::relation ::fact-index ::indexes}` with no `::owned`;
- `snapshot` of Decision 3, the namespace's only v2 require;
- `q` returning a result value; `collect` materializing it; `match`, `pull`,
  `pull-many`, `entity-attrs` unchanged in body once `realize-db-value!`
  returns no owned list.

**Delete**: `ViewStream`, `QueryResultStream`, `make-query-result-stream`,
`bounded-next`, `quiet-close!`, `close-owned!`, `validate-borrowed!`,
`validate-descriptor!`, `open-db-inputs!`, the `dao.stream`,
`dao.stream.relation` and `:require-macros` entries; `src/cljc/dao/stream/relation.cljc`
and its `defopen`.

**Consumer call sites, same change**: `schema.cljc:255-256` → `query/rows`;
`schema.cljc:294` → `query/fact-relation`; `stigmergy_test.clj` — the six
`(query/current (index/published-index …))` sites become
`(query/current (query/open-published! (index/published-index …)))` inside a
`try`/`finally` that closes, and line 177's `(query/current-state-seq
(ds/strict-vec stream))` over the v1 transactor stays as it is (transactor's,
not query's). `transact.cljc`, `semantic.cljc`, `positional_query_test.cljc`
and `compilation_pipeline.cljs` need no edit; confirm by compiling, not by
inspection.

**Prove** with `query_test.cljc` (951 lines) rewritten where it pinned the
stream dressing and kept where it pins the evaluator:

- R1 from I1–I4: raw vector and raw map throw; a loose map with
  `:dao.stream/type` throws (it is neither a value query built nor an opened
  index); `relation`, `entity-map-relation` (with the `:db/id` throw), and
  an opened published index are accepted; a bare content-store handle
  throws.
- R2 from V1–V5, unchanged in substance, minus the `IDaoStreamReader` /
  `closed?` / `drain` assertions; plus `rows` over a view.
- R3 from R1–R2: `q` returns a tagged result value; `collect` shapes; a
  result is not a coordinate (no `:dao.space.query/published`, no
  `:dao.stream/type`).
- R4 from O1: an opened published index passed to `q` is still open after
  `q` returns and after `collect`; `close-published!` closes the store once;
  a second close is a no-op. The `OwnedSource`/`BlockedOwnedSource` types
  and their `defopen`s are deleted.
- R5 from S1–S3, over a v2 ring buffer (`ringbuffer/create!`): snapshot of
  an open buffer with three values is `:blocked` with three values and the
  cursor after them; of a closed one `:ended`; of a capacity-1 buffer with
  two appends `:gap` with the values read before it and a `:recovery`
  cursor; of a scripted reader answering `cursor-mismatch`, `:defect` with
  the raw answer; the handle is never closed by `snapshot`; and the
  snapshot's relation feeds `current` and `q` like any relation.
- R6, R7, the `:in` bindings, negation/aggregation, and every Step 3 lazy
  test unchanged in assertion. `publish-into-file` returns an opened value
  and its `finally` closes it through `close-published!`; the
  `with-redefs` counting harness redefines `jing-coordinate/open!` exactly
  as it does now, since `open-published!` goes through it. The
  `lazy-published-current-closes-owned-store-once` test is deleted: the
  property it pinned (O2) moved to the caller and is covered by R4.

Green: `query_test`, `positional_query_test`, `schema_test`,
`stigmergy_test` (clj), `transact`'s tests, `semantic_test`, and the
`:demo` shadow build compiling with `public/demo.html` loading and the
compilation-pipeline demo running its queries — on clj, cljs (Node) and
cljd; the cljd lane regenerates `test/cljd-out/`. Confirm `Testing
dao.space.query-test` in the Node output.

### P2 — `dao.space.query.md`

Rewrite *Source polymorphism* to Decision 1's input list; rewrite *The read
coordinate*'s last paragraphs so the coordinate is opened by the caller and
borrowed by `q`; state L4 under *Index realization*; add S1–S3 as a short
*Snapshots* section naming `snapshot` as the one stream interpreter and
`dao.stream.v2.observe/step` as what it is built on; move the after-close
loudness bullet to the caller; update the status line and the executable
contract pointer. Add to *Decisions*: "bounded realizations are values, not
streams" with Decision 2's reason, and "the relation transport is
eliminated" with Decision 4's. *Open items* unchanged except the K-way
merge note.

## Host matrix

| phase | clj | cljs (Node) | cljd | notes |
| --- | --- | --- | --- | --- |
| P1 | ✓ | ✓ | ✓ | pure `.cljc`; the two `with-redefs` budget tests stay clj/cljs-only as today; `:demo` build compiled and the page smoke-loaded |
| P2 | — | — | — | prose |

## Boundary

**Built or deleted here:** `dao.space.query` (rewritten in place),
`dao.stream.relation` (deleted), `query_test` (rewritten),
`dao/stream_test.cljc`'s `relation-descriptor-contract-test` (deleted with the
transport it pins), two call sites in `dao.space.schema`, the published-index
call sites in `stigmergy_test`, `dao.space.query.md`.

**Left owing, by namespace, each to its own plan:**

- `dao.space.index` — `ds/defopen :dao.space.index/published` and
  `PublishedIndexStream` are now dead code; `snapshot-datoms` walks the
  agent-local stream with v1 `ds/next` and `{:position 0}`; the `dao.stream`
  require stays until those go. `open-published!` may move here if index's
  plan prefers the opener beside the coordinate builder.
- `dao.space.schema` — its own `dao.stream` require, its transactor over v1,
  and the `strict-vec` sites other than the two edited here.
- `dao.space.transactor` — the v1 local stream, its protocols, and
  `ds/closed?`.
- `yin.vm.semantic` — deleted under the v2-consumers plan; until then it
  compiles against query unchanged.
- `stigmergy_test.clj:177` — `ds/strict-vec` over the v1 transactor's stream,
  the transactor's to change.
- `snapshot` → `dao.stream.v2.observe/drain` — when a second consumer
  appears.

## End condition

On clj, cljs (Node) and cljd: `dao.space.query` requires no `dao.stream` and
no `dao.stream.relation`; `dao.stream.relation` is gone; every invariant
I–S has a test that exercises it and the [T→D] ones are in
`dao.space.query.md`; the evaluator's tests pass unchanged; `schema_test`,
`stigmergy_test`, `positional_query_test` and `transact`'s tests are green;
`public/demo.html` loads and its compilation-pipeline queries run; and the
only things under `dao.space*` still on v1 are `index`, `schema` and
`transactor`, named above with what each owes.
