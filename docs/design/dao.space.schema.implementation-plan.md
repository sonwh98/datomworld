# dao.space.schema on DaoStream v2

Status: implementation plan, subordinate to [`dao.stream.md`](./dao.stream.md)
(the contract) and [`dao.space.schema.md`](./dao.space.schema.md) (the design).
Verified against `4b9f0e7`. Authored by the Architect, revised through three
rounds against a routine review (`gpt-6-astra`) and an adversarial review
(`deepseek-v4-pro`), both of which cleared r2 and neither of which shares a
family with the author. This document is transient: it is deleted when nothing
in it is still owed, under the rule in `dao.stream.md`.

Method as before: the **invariants list** (§2) is the contract, grouped by
the test that exercises each and marked `[D]` stated in the design, `[T]`
pinned only by a test, `[T→D]` test-pinned and promoted to the design, `[T✗]`
an accident of the v1 stream dressing dropped with its reason, and `[D✗]` a
stated clause retired because the value model discharges it. The current
implementation and tests have no authority beyond the invariants they pin.
Nothing is in production. This plan is deleted when nothing in it is owed.

**r2** takes six findings from two independent reviews (`gpt-6-astra`
routine, `deepseek-v4-pro` adversarial), all verified against the tree by
the owner before they reached me and re-verified here. Where a decision
changed, its text below states what it now rules; the numbering is stable.

| # | Finding | Where |
|---|---|---|
| 1 | D4 keyed completeness on a `:gap` that `query/snapshot` cannot emit for a prefix evicted before the call: it mints a fresh `:oldest`, which is the earliest *retained* position. V13(a) was unwritable and the silent degradation stayed open. | **D4** rewritten; V13 split into V13a/V13b; new V15 pins the limit |
| 2 | D1 said the closed check precedes argument validation "exactly as `tx/transact!` does". False: `tx/transact!` throws on empty `tx-data` before its lock and closed check (`transactor.cljc:241-243`). | **D1** aligned with the transactor; L10 qualified |
| 3 | V11's property ("`schema/current` never closes a borrowed source") left the suite; V14 could not observe an early close because `close-published!` is idempotent. The idempotent-close test likewise never observed `tx/close!`. | **V14** gains an ownership counter; the close test asserts on the inner value |
| 4 | The arithmetic did not close: Phase 1 removes two `ds/` lines from `schema.cljc` (16 → 14, not 11); both `:1062` and `:1071` change in T19's test; 22 receipt lines are rewritten and 2 die with the race test; five deftests are deleted, not seven. | §0.3–0.4, §4 Prove, §5 Prove recomputed from the edit lists |
| 5 | `grep "dao.stream\|dao.jing"` over `schema.cljc` can never be empty: the receipt keywords stay. | §5 Prove targets require forms |
| 6 | W38/W39/W41's `opened` binding was out of scope of the existing `finally`. | §5 Tests specifies the nesting |

Confirmed sound by both reviewers and not relitigated: D7, the D10 receipt
collapse, T19's survival of the `deftype`→map rewrite, and D5's surviving
pins — now cited by test name (§2.5 P4).

---

## 0. Corrections to the brief

The brief's measurements are right in every particular I could check, with
five refinements:

1. **The 8-API / 16-line table is exact**, line for line. Two v1 touch points
   sit outside it because they contain no `ds/`: the `[dao.stream :as ds]`
   require at `schema.cljc:19` and the `#?(:cljs (:require-macros
   [dao.stream]))` at `:20`. Both go in Phase 2. The four
   `#_{:clj-kondo/ignore …}` forms at `:244`, `:305`, `:355`, `:1202` exist
   only to quiet kondo about `ds/` vars behind reader conditionals; they go
   with the calls they cover.
2. **`ds/strict-vec` in `schema_test` is prose, not code.** Its one
   occurrence (`:615`) is inside a comment. Code-level v1 use in the test
   is: 63 `ds/close!`, 2 `ds/open!` (`:305`, `:1630`), 1 `ds/append!`
   (`:306`), 1 `ds/closed?` (`:708`).
3. **61 of the 63 `ds/close!` sites target the wrapper; 60 become
   `schema/close!` and one dies.** The 61st is `:1123` inside
   `publish-serializes-against-close`, deleted under D7. The other two
   (`:307`, `:621`) close v1 fixtures and die with them in Phase 2 (D6, D9).
4. **Receipt assertion sites: 24 lines**, not the 20–23 earlier plans
   counted: `:711 :712 :715 :780 :808 :843 :860 :940 :944 :976 :1006 :1062
   :1071 :1119 :1120 :1179 :1333 :1348 :1383 :1796 :1881 :1940 :1957 :1959`.
   Two (`:1119`, `:1120`) die with the race test; **22 are rewritten**,
   including both lines of T19's test (`:1062`, `:1071`). Plus one
   `{:woke []}` pin at `:1134`, also inside the deleted race test.
   (`:1531`'s `{:result r}` is the test observer's own ex-info data.)
5. **The demo's route to `query` is direct, not via `semantic`.**
   `src/cljs/datomworld/demo/compilation_pipeline.cljs:13` requires
   `dao.space.query` itself (and `dao.space.transact`, and v1 `dao.stream` +
   `dao.stream.ringbuffer` at `:15-16`). The conclusion holds: **no file
   under `src/cljs`, `src/cljd` or `src/clj` requires `dao.space.schema`**;
   the only in-tree references outside `schema.cljc`, `schema_test.cljc` and
   `schema_fixtures.cljc` are a docstring mention in `query.cljc:175` and
   prose in design docs. The demo is compiled each phase anyway (§7).

One observation the brief did not make and the plan depends on: **schema
already accepts an opened published index on its value path.** `current`
tests `(query/value? d)` (`:337`, `:345`), and `query/value?` answers true
for `{:dao.space.query/published …}`; `interpret-view` then calls
`query/rows` on a `history` view over it, which forces the `:rows` delay.
So `(schema/current (query/open-published! coord))` works at `4b9f0e7`
without a line changed — only the tests never exercise it, because W38–W41
reach the manifest through `schema/published` and the `defopen`. That is
why Decision 5 can delete the opener rather than replace it.

---

## 1. What schema is

`dao.space.schema` is two clients of the tuple space sharing one extraction
function. The **validating wrapper** (`schema/transactor`, `transact!`,
`publish!`, `close!`) owns a `dao.space.transactor` value and, per
transaction, plans the complete next state — translation, lookup-ref
resolution, supersession emission, both-modes structure checks, strict-mode
data checks — before exactly one inner append, installing the planned state
only when the inner receipt is `ok`. The **schema view** (`schema/current`)
is a pure function from a d5 source value to a d3 fact-relation: extract the
schema with `q` over the history view, current-state-resolve the data,
collapse the attributes the schema names card-one, pass everything else
through. Neither client reads a live stream: the wrapper reads its local
handle through `index/snapshot-datoms` (already v2, T18), and the view takes
values. Everything v1 about the namespace is dressing: a `deftype` wearing
`IDaoStreamBound` so `ds/close!`/`ds/closed?` dispatch to it, a
`defopen`-routed descriptor for the view, a borrowed-realization branch that
drains a v1 reader into a relation, and a `defopen` + reader record that
turns a manifest into a v1 stream so `schema/current` can drain it back into
a vector. Taking the dressing off leaves a namespace that requires
`dao.datom`, `dao.space.index`, `dao.space.query` and `dao.space.transactor`
— and **neither `dao.stream` nor `dao.stream.v2`** as a namespace. The
`:dao.stream/outcome`, `:dao.stream/ok` and `:dao.stream/closed` keywords
remain in its vocabulary, as they do in the transactor's.

---

## 2. The invariants

Numbering continues the design's §9 matrix loosely; test names are the
current `schema_test` deftests. A `[T✗]`/`[D✗]` entry names its reason in
§3.

### 2.1 Representation and extraction — untouched by this plan

`axioms-shape`, `bootstrap-expands-to-axioms`, `extraction-*`,
`retracted-property-absent`, `reassert-after-retract`, `same-t-supersession-
resolves-new-value`, `full-tie-collapses-to-least-v`, `unknown-db-type-
throws`, `unknown-db-star-name-ignored`, `meta-properties-resolve-from-
axioms`, `conflicting-property-history-throws`, `no-ident-property-rows-
ignored`, `ident-tie-break-resolves-to-least-v`, `reified-metadata-reference-
survives-schema-extraction` (15 deftests, `:46-:288`).

| # | Invariant | |
|---|---|---|
| E1 | The five `:db/*` axioms, their fixed property maps, genesis ids 16–20, and `bootstrap`'s tx-data shape | `[D]` §2 |
| E2 | Extraction runs through the public `q` surface over the history view; the private fold applies retraction, greatest `t`, least-`v` tie; `m` never orders | `[D]` §4 |
| E3 | Unknown `:db.type` throws; unknown `:db/*` name is ignored by the view; meta-properties resolve from axioms | `[D]` §2, §3.1 |

**No test in this group touches v1 and none changes.**

### 2.2 The read view — `schema/current`

| # | Invariant | Test | |
|---|---|---|---|
| V1 | Card-one collapse: greatest `t`, least-`v` tie, never throws; out-of-order retraction resolves `v2`; same-`t` assert+retract resolves `v_new` | `view-collapses-card-one`, `out-of-order-…`, `same-t-assert-retract-…`, `reader-tie-never-throws` | `[D]` §4 |
| V2 | Unschematized attributes pass through; undeclared cardinality is card-many; `:db/*` rows collapse by axiom with or without bootstrap rows | `unschematized-…`, `undeclared-cardinality-…`, `schema-rows-themselves-collapse` | `[D]` §3, §4 |
| V3 | `:as-of` bounds data and schema; `:schema-as-of` re-reads the schema at another point of the same `t` axis | `as-of-bounds-data-and-schema`, `schema-as-of-rereads-schema` | `[D]` §4 |
| V4 | The result is a query fact-relation value (`query/value?`, `:fact? true`) that `q` accepts directly | `schema-current-returns-a-fact-relation-value` | `[T→D]` — §4 still says "returns a self-contained closed `ViewStream`" |
| V5 | A nested `query/current` or `query/history` view is rejected as a source | `nested-view-descriptor-rejected` (cases 1–2) | `[D]` §4, §8 |
| V6 | A schema view's own output is rejected as a source | `nested-view-descriptor-rejected` (case 3) | `[T]`, **kept, restated on the value** (D3) |
| V7 | Unknown `:db.type` surfaces when the view is built | `unknown-db-type-throws-at-realization` | `[D]` §3.1; only the comment is stale |
| V8 | The descriptor path and the borrowed-realization path answer identically | `borrowed-and-descriptor-paths-agree` | **`[T✗]`** — D3/D6 |
| V9 | A borrowed source must satisfy `IDaoStreamBound` and answer `closed?` true | `current` `:320-:327` (untested negative) | **`[T✗]`** — D6 |
| V10 | The `defopen` route opens `:source`, closes it exactly once, interprets | `inner-stream-closed-after-descriptor-path` | **`[T✗]`** — D3: nothing is opened |
| V11 | `schema/current` never closes a source it is handed | `borrowed-path-does-not-close-again` | **kept, re-pinned** on the one closable source that survives — the opened published index — by V14's ownership counter (D6). r1 marked this `[T✗]`; the reviews showed the property would have left the suite |
| V12 | `:dao.stream/bound` inherited from `:source` | (already gone; W11's comment records it) | `[D✗]` — §4's descriptor block is deleted (§5) |
| V13a | **New.** A `query/snapshot` result whose status is `:gap` or `:defect` is rejected — an **observed read failure** during the snapshot | new: `snapshot-read-failures-are-rejected` | **`[D]`, new** — D4 |
| V13b | **New.** A snapshot result with status `:ended` or `:blocked` is accepted and interprets identically to the same rows as a relation value | new: `complete-snapshots-are-accepted` | **`[D]`, new** — D4 |
| V14 | **New.** `schema/current` over an opened published index forces the rows before returning and **does not close the store**; the result stays valid after the owner's `close-published!` | new: `schema-view-is-self-contained-and-never-closes-the-store` | **`[T→D]`, new** — the value-model form of §9's "no inner-stream leak after `close!` (the ownership trap)" plus V11 |
| V15 | **New, a documented limit.** A prefix evicted *before* the snapshot is not detectable: `query/snapshot` mints a fresh `:oldest`, so an overflowed ring buffer answers `:blocked` over its suffix and `schema/current` interprets that suffix. Completeness is the caller's declaration, never schema's check | new: `evicted-prefix-is-not-detectable-through-snapshot` | **`[D]`, new** — D4; pinned the way transactor T6 pins its documented hazard |

### 2.3 The write wrapper — lifecycle and results

| # | Invariant | Test | |
|---|---|---|---|
| L1 | `schema/transactor` creates and owns the inner transactor value; closing the wrapper closes it | `wrapper-opens-and-transacts`; new `close-is-idempotent-and-closes-the-inner-value` | `[D]` §3.1 |
| L2 | Closing the wrapper neither closes nor erases the caller's local stream | `closed-wrapper-answers-closed` (second assertion) | `[D]` §3.1 + transactor T7 |
| L3 | `close!` returns `{:woke []}` | `publish-serializes-against-close` `:1134` | **`[T✗]`** — D1 |
| L4 | `closed?` is a public predicate | `wrapper-opens-and-transacts` `:708` | **`[T✗]`** — D2 |
| L5 | `transact!` on a closed wrapper throws | `closed-wrapper-throws` | **`[T✗]`** → L5′: answers `{:dao.stream/outcome :dao.stream/closed}` as data — D1 |
| L6 | The ok receipt is `{:result :ok :t t :datoms ds}` | the 22 surviving lines of §0.4 | **`[T✗]`** → L6′: the inner v2 receipt passes through unchanged — D1 |
| L7 | A conforming non-ok inner outcome is returned unchanged; the wrapper's state does not advance; the same `t` is retried | `failed-inner-append-leaves-wrapper-state-unchanged` | `[D]` §3.1 (transactor T19) — kept; its two receipt lines `:1062`, `:1071` change shape |
| L8 | `publish!` delegates to the inner `publish!` and returns `{:manifest-address … :manifest …}` | W38–W41 | `[D]` §5, transactor T11 |
| L9 | `publish!` on a closed wrapper throws; the guard is serialized with the publication under the wrapper lock | `publish-serializes-against-close` | **`[T✗]`** — D7 |
| L10 | Argument defects throw with nothing appended: every rejection listed in §3 and §3.1, **when the wrapper is open**. On a closed wrapper the precedence is the transactor's: empty `tx-data` throws; every other argument answers `closed` before it is examined | every `thrown-with-msg?` in the wrapper section; new `closed-precedence-matches-the-transactor` | `[D]` §3 — semantics unchanged; the closed-wrapper qualifier is new (D1) |

### 2.4 The write wrapper — semantics, untouched by this plan

`map-form-supersession`, `add-collision-…`, `valueType-…`, `ref-existence`,
`lookup-ref-in-db-id`, `lookup-ref-in-ref-value`, `no-dedup`, `card-many-
expansion`, `retract-attribute-wide`, `schema-evolves-through-wrapper`,
`bootstrap-reads-back`, `bootstrap-verbatim-retransact-allowed`, `unknown-
db-star-name-rejected-both-modes`, `illegal-schema-values-…`, `duplicate-
ident-…`, `axiom-protection`, `user-schema-evolution-allowed`, `unique-
requires-card-one-…`, `lookup-ref-after-same-record-…`, `re-extract-gated`,
`audit-via-fns`, `schema-in-source`, `unique-duplicate-…`, `lax-unique-
ambiguity-…`, `retract-frees-unique-value`, `map-form-repairs-…`,
`concurrent-strict-unique-writes-serialize`, `non-emitting-map-…`,
`opposite-operations-…`, `schema-retractions-update-…`, `reified-metadata-
reference-seeds-wrapper-state`, `unique-is-per-stream-not-per-world`.

| # | Invariant | |
|---|---|---|
| S1 | Every §3 / §3.1 / §3.2 / §3.3 ruling: mode split, supersession shape, lookup-ref resolution, axiom protection, no-dedup, atomicity, serialization, epoch-on-next-transaction, reopen parity, the per-stream guarantee boundary | `[D]` |

Their **only** edits are mechanical: `(ds/close! w)` → `(schema/close! w)`
and `(:result r)`/`(:t r)`/`(:datoms r)` → the v2 keys.

### 2.5 The publisher — `publish!`, the published coordinate, parity

| # | Invariant | Test | |
|---|---|---|---|
| P1 | `q` answers identically before and after publish, through the schema view | `publish-then-schema-view-parity` | `[D]` §9 |
| P2 | The published source answers the same raw query as the drained relation | `published-descriptor-as-raw-db-input` | `[D]` §9 |
| P3 | The published source is accepted as `schema/current`'s `:source` | `published-accepted-as-current-source` | `[D]` §7 — kept, on `query/open-published!`'s value |
| P4 | `schema/published` validates its coordinate arguments; the opener rejects a coordinate with extra keys | `published-descriptor-validation` | **`[T✗]`** — D5. The three argument checks are pinned on the surviving constructor by `published-index-constructor-validates-its-arguments` (`index_test:592`); extra-key rejection on the surviving opener by `open-published-rejects-unresolvable-and-malformed-coordinates` (`query_test:936`) |
| P5 | A `:dao.space.schema/published` coordinate is its own `:dao.stream/type`, realized by a registered opener over `PublishedSchemaRows` | design §5 | **`[D✗]`** — D5 |
| P6 | A content store opened by the published opener is closed before the value is returned, on success and on failure (the uncovered `finally`, brief Q8) | none | **`[D✗]`** — D5/D8: the code that owned the store is deleted; the property is pinned on the surviving opener by `failed-open-closes-the-store-it-opened` (`query_test:1076`) and `close-published-closes-once-and-is-idempotent` (`:716`) |

### 2.6 Fixtures

| # | Invariant | |
|---|---|---|
| F1 | A `defopen` registration cannot live in a ClojureDart test namespace, so it lives in `schema_fixtures` | **`[T✗]`** — D9: there is no registration |

---

## 3. Decisions

### D1 — The wrapper is a value with named operations; results are the inner transactor's, unwrapped; precedence is the inner transactor's too. (Brief Q1, Q2; r2 finding 2)

`SchemaWrapper` implements one v1 protocol to expose two operations, and
both exist to make `ds/close!` and `ds/closed?` dispatch. In the value model
the transactor already made (`dao.space.transactor.md`, *A transactor is a
value, not a stream*), the wrapper becomes the same kind of thing: a plain
map, not a `deftype`:

```clojure
(schema/transactor local intake {:strict true})
;; => {:dao.space.schema/transactor true
;;     :inner        <transactor value>      ; owned
;;     :local-stream local                   ; borrowed, for proposed-schema's snapshot
;;     :strict?      true
;;     :state        (atom {…, :closed false})}

(schema/transact! w tx-data)
;; ok      => {:dao.stream/outcome :dao.stream/ok :dao.space/t t :dao.space/datoms ds}
;; refused => the inner outcome map, unchanged (full | closed | invalid-value | transport-error)
;; closed  => {:dao.stream/outcome :dao.stream/closed}
;; defect  => throws, nothing appended
(schema/publish! w opts)   ; => {:manifest-address … :manifest …}   (unchanged)
(schema/close! w)          ; => {:dao.stream/outcome :dao.stream/ok}, idempotent
```

**The D10 collapse is a deletion, not a translation.** `transact!`'s
success branch currently rebuilds `{:result :ok :t … :datoms …}` from the
inner receipt; it now returns the inner receipt. The two-shaped outcome
space the D10 rule created as a named transitional artifact collapses to one
shape, and the 22 surviving assertion lines are edited by rule: `(:result
r)` → `(:dao.stream/outcome r)` against `:dao.stream/ok`; `(:t r)` →
`(:dao.space/t r)`; `(:datoms r)` → `(:dao.space/datoms r)`; the literal at
`:1071` becomes the v2 literal.

**Why schema adds nothing to the receipt.** `:dao.space/datoms` already
carries the emitted datoms including repairs
(`map-form-repairs-all-card-one-values` reads them), and an epoch marker is
a feature with no consumer. Open maps permit adding keys later without
breaking anyone; nothing is reserved.

**`close!` returns the v2 close outcome.** `{:woke []}` was v1's waiter
vocabulary and v2 has no waiters. It is idempotent because the inner
`tx/close!` is and the flag is a `swap!` to `true`.

**Closed → data, defects → throw, in the transactor's order.** r1 claimed
the closed check runs "before any argument is looked at, exactly as
`tx/transact!` does." That was false: `tx/transact!` (`transactor.cljc:
241-243`) throws on empty `tx-data` **before** taking its lock and before
its closed check, and only then answers `closed` for everything else. The
wrapper adopts that exact precedence rather than inventing its own:

1. `(when (empty? tx-data) (throw …))` — outside the lock, unconditional.
   An empty transaction is malformed regardless of any state; the
   transactor drew the line there and there is no reason for the wrapper to
   move it.
2. Under the lock: `(if (:closed @state) {:dao.stream/outcome
   :dao.stream/closed} …)`.
3. Then translation and every §3 validation, which throw.

So the qualifier the reviews asked for is: **every argument defect throws
when the wrapper is open; on a closed wrapper only the empty-`tx-data`
defect throws, and every other argument answers `closed` before it is
examined** — which is precisely what a closed inner transactor does with the
same arguments. No transactor change, no new precedence, no added scope.
Today's `transact!` already checks emptiness after the closed check
(`:1079-:1083`); moving the empty check above the lock is the one-line
reordering that aligns it. L10 is pinned by `closed-precedence-matches-the-
transactor`: on a closed wrapper, `(schema/transact! w [])` throws
"requires at least one item" and `(schema/transact! w [[:db/add 30 :db/foo
:bar]])` answers `closed` (not the "unknown :db/*" throw); the same two calls
on `(:inner w)` give the same two answers, which is the alignment stated as
an assertion.

### D2 — `closed?` does not survive. (Brief Q2)

The brief asks whether the wrapper's flag being real per-wrapper state
earns `closed?` a stay. It does not:

- **The staleness argument is about the caller, not the flag.** A caller
  that reads `closed?` and then calls `transact!` has a window between two
  operations that the wrapper's lock does not cover. "Operation results are
  authoritative" closes that window, and `transact!` now answers as data
  (L5′).
- **The flag stays; the predicate goes.** The wrapper keeps `:closed` in its
  state atom because `transact!` consults it. What is deleted is the public
  read. The `wrapper-state` test helper still reads the atom for T19's
  state-identity assertion — a white-box seam, not a predicate.

`wrapper-opens-and-transacts:708` loses `(is (not (ds/closed? w)))`; the
successful `transact!` two lines later proves the wrapper is open.
`closed-wrapper-throws` becomes `closed-wrapper-answers-closed`, and its
second assertion — the local stream still `blocked`s at its tail — stays
verbatim (L2).

### D3 — The `:dao.space.schema/current` descriptor route dies; callers pass values. (Brief Q3)

`current` is dual today: a query value interprets immediately; a map with a
keyword `:dao.stream/type` is wrapped in a `:dao.space.schema/current`
descriptor and pushed through `ds/open!`, whose `defopen` body opens
`:source`, closes it, and interprets. The registry is *Explicitly Absent*,
and query's Decision 1 (inputs are values) is the precedent. Follow it.

**What is lost, honestly.** Three things the route could do that the value
path cannot:

1. **Accept a source it did not know how to read.** Any `open!`-dispatchable
   descriptor went through. In v2 the transports schema might meet are
   reached by `attach!` on a handle the *caller* holds, and reading them is
   a `query/snapshot` (D4). Nothing schema-side needs to know a transport.
2. **Own the inner stream's lifetime.** The `defopen` body closed what it
   opened. In the value model there is nothing to open; an opened published
   index is closed by whoever opened it (D5, V14).
3. **Be named across a serialization boundary.** A `:dao.space.schema/
   current` descriptor was, on paper, data that could travel. It could not:
   its `:source` was a v1 descriptor carrying live state or its own data,
   and `:as-of` is interpretation, not reachability. A view is a lens a
   reader applies, not a place a reader attaches to (Axiom 2). What travels
   is the coordinate under it (`index/published-index`).

So `current`'s contract becomes: `(current source)` / `(current source
{:as-of n :schema-as-of n})` where `source` is a relation value, an opened
published index, or a `query/snapshot` result (D4). A `query/current` /
`query/history` view is rejected (V5). **A `:fact?` relation is rejected**
(V6, restated): `nested-view-descriptor-rejected`'s third case passes
`(schema/current rel)`'s own result back in, replacing the hand-built v1
descriptor. Any other value falls through to `query/history`'s own
rejection, which is the informative error query's I1 already guarantees.
`validate-not-nested-view!` loses its `(= t :dao.space.schema/current)` arm
and gains the `:fact?` arm.

### D4 — The v1 drain vanishes. A live stream enters only through `query/snapshot`. Schema rejects observed read failures and **states, as a limit, that it cannot detect a prefix lost before the snapshot**. (Brief Q4; r2 finding 1)

`interpret-view:260-261` converts a v1 realization to a relation because
query takes values. With D3 and D6 no realization can reach it, so the
branch is deleted and `interpret-view` takes a query value.

**Is a live stream a legitimate schema source?** Yes, the same way it is
for query: a caller with a v2 reader handle calls `query/snapshot` and hands
schema the result. `schema/current` does not take a handle and does not
call `snapshot` itself; schema opens nothing and reads nothing.

**What r1 got wrong, and why it matters.** r1 ruled that schema rejects a
snapshot with status `:gap` because "a snapshot that lost its prefix has,
with high probability, lost the vocabulary," and specified a ring-buffer
test that overflows and then snapshots, expecting `:gap`. That test cannot
be written: `query/snapshot` (`query.cljc:293`) mints a *fresh*
`:dao.stream/oldest`, which on a ring buffer is `(:first s)` — the earliest
**retained** position (`ringbuffer.cljc:61-64`). A buffer that evicted
before the call answers `:blocked` or `:ended` over its surviving suffix and
never `:gap`; `gap` is reported only to a cursor that *spans* an eviction
(`:88-98`). `dao.stream.md:496-503` says this in so many words: "Retained
history is not complete history, and no anchor can make it so. A consumer
that requires complete history gets it from the transport's declared
retention, never from an anchor." `query_test`'s `snapshot-of-a-gap-is-data`
(`:359`) carries a comment explaining exactly why it uses a scripted reader
and not a ring buffer. So r1's check keyed on a signal the case it was built
for cannot produce, and the silent degradation — a suffix that lost the
bootstrap, interpreted as raw pass-through with card-one collapse disabled —
stayed open behind a guard that looked closed.

**What `schema/current` now guarantees, and what it demands.** The contract
offers exactly two instruments for completeness, and `query/snapshot`
provides neither: a **transport declaring complete retention** at creation
(`dao.stream.v2.memory-log`, whose `:oldest` is pinned to position 0 for the
stream's life) and a **kept origin cursor** minted before the first append.
Both belong to the composition that created the stream. Schema is handed a
result after the fact and has no way to interrogate either — the same
position T18 puts the transactor in, resolved the same way:

- **Guarantee.** `schema/current` interprets the rows it is given
  correctly (§4). Over a relation value, an opened published index, or a
  snapshot result whose status is `:ended` or `:blocked`, its answer is the
  §4 answer over exactly those rows.
- **Rejection.** A snapshot result whose status is `:gap` or `:defect` is
  rejected with an informative throw naming the status. This detects an
  **observed read failure** — a hole opened while the snapshot was reading,
  or a transport that answered outside its contract — and nothing more. It
  is right to reject because the schema rows may be in the hole and the
  read is known-incomplete; it is not a completeness check.
- **Demand, declared not interrogated.** A caller that hands
  `schema/current` a snapshot is declaring that the snapshot is the complete
  history of its source: either the handle was on a complete-retention
  transport, or the caller **read through an origin cursor** minted before
  the first append and observed no `gap` — merely holding an origin cursor
  is not evidence, and today's `query/snapshot` takes no caller-supplied
  cursor, so it cannot make that observation on the caller's behalf.
  `query/snapshot` on a handle that evicted before the call produces a
  suffix that `schema/current` **cannot distinguish from complete
  history**; wiring one is a host-assembly defect of the same kind as
  T18's, **knowable at wiring time** by the composition that created the
  stream and caught at read time by nothing — no status field, no assert,
  no type check — and never by schema. **One softening versus T18 is real
  and named:** T18's declaration point is a single local wiring, whereas a
  snapshot value strips provenance — a `:blocked` result carries `:status`
  and nothing about retention — so a caller at a distance from the stream's
  creation cannot make the declaration knowledgeably. That is the bounded
  gap the deferral below covers. This is written into
  `dao.space.schema.md` §4 in those words, beside the pointer to
  `dao.stream.md`'s *Complete history*.

**Why not fix it in `query/snapshot`.** An origin cursor can be minted only
by whoever holds the handle before the first append. `snapshot` takes any
handle at any time, so closing the gap means a `snapshot-from` variant that
takes a caller-kept origin cursor and reads from it — ordinary protocol use,
since observing a `gap` through a supplied cursor is exactly what a kept
cursor is for, not an interrogation of configuration. It is deferred because
it is **query's API to add**, under query's own plan, not because it would
violate the contract. If it lands, schema accepts its result on the same
terms as today: a status to check, nothing else. Not designed here.

**Why still accept snapshot results rather than only bare relations.**
`query/current` accepts them (`db-source`), so parity says schema should;
and the `:gap`/`:defect` rejection is cheap and catches the one failure
schema *can* see. The alternative — schema takes only relations and the
caller unpacks `(:relation snap)` after checking status — moves the check
to every caller and pins nothing.

**Pins.** V13a: a scripted reader modelled on `snapshot-of-a-gap-is-data`
(reads one value, then answers `gap` with a recovery cursor) and a second
answering `cursor-mismatch` (→ `:defect`); `schema/current` throws naming
`:gap` and `:defect` respectively. V13b: an open memory-log carrying
`schema-rows` plus data answers `:blocked`, a closed one `:ended`; both
interpret identically to `(query/relation rows)`. V15: a v2 ring buffer of
capacity 4 receives `schema-rows` plus two card-one values for one `[e a]`;
`query/snapshot` answers `:blocked`; `schema/current` accepts it and both
values are visible, because the vocabulary was evicted. V15 is a pin on a
**documented limit**, exactly as `single-writer-wrappers-are-not-
coordinated` pins transactor T6: its comment says so, and it exists so that
a future change which makes the limit detectable has to change a test and
therefore the design. **For the implementer: V15 is a cross-layer pin.**
Part of its teeth is the `(is (= :blocked (:status snap)))` assertion on
`query/snapshot`'s own status, so a query-side refactor that changes that
status while leaving schema's limit intact also trips it. Both reviewers
judged that acceptable — it forces the re-examination the pin wants — but a
V15 failure is not by itself evidence of a schema defect; read the failing
assertion first.

### D5 — `:dao.space.schema/published`, `schema/published`, `PublishedSchemaRows` and the opener are deleted; nothing replaces them. (Brief Q5, Q8)

The brief asks what replaces the `defopen` and where the opener belongs.
**The opener already exists and is query's.** Per §0, `(schema/current
(query/open-published! (index/published-index store manifest-address)))`
works today. What the schema-typed coordinate bought was a
`:dao.stream/type` for `ds/open!` to dispatch on. Remove the dispatch and
the type has no job. Three further reasons, in decreasing weight:

1. **It contradicts §1.** "A raw dao.space agent and a schema'd agent share
   one medium and read the same log differently." A coordinate stamped
   `:dao.space.schema/published` bakes the reader's lens into the name of
   the data. The lens is applied by calling `schema/current`; the coordinate
   is the same `index/published-index` value for both readers. §5 already
   half-says this: "The manifest is schema-independent."
2. **It is not reachability data.** `schema/published` produced the same
   five keys as `index/published-index` with a different type keyword — a
   value with a transport type stamped on it, the shape query's Decision 2
   retired.
3. **A second opener is a second store-ownership model.** `PublishedSchemaRows`
   wanted eager rows and a store closed before return; query's opener
   defers rows and hands the store to the caller. Two openers over one
   manifest format is the "undecided coexistence" the v2 doc calls a
   defect. Query's model wins because it has consumers and because
   eager-versus-lazy is already the caller's choice: `schema/current`
   forces the rows, so the caller closes the store right after it returns
   (V14).

**No `schema/open-published!` convenience either.** It would hide a
`close-published!` the caller must still make, and it would put an opener
in schema — the question this decision answers "nowhere new."

**The uncovered `finally` (Q8) is discharged by deletion.** The debt the
transactor Phase 3 record named was owed on code this plan removes. There is
no schema-side resource ownership left: `schema/current` over an opened
index acquires nothing and — per V14 — releases nothing. The property (a
store opened during a failed open is closed before the error propagates; a
successful open's store is closed exactly once by its owner) is pinned on
the surviving opener by `failed-open-closes-the-store-it-opened`
(`query_test:1076`) and `close-published-closes-once-and-is-idempotent`
(`:716`).

Consequently `schema.cljc` drops its `dao.jing` and `dao.jing.coordinate`
requires: `jing/segment-address?` served only `schema/published`, and
`jing-coordinate/open!` only the opener.

### D6 — The borrowed-realization contract is an accident of the dressing; the one property under it that is real is re-pinned, not dropped. (Brief Q6; r2 finding 3)

`current:319-327` demands a v1 realization satisfy `IDaoStreamBound` and
answer `closed?` true, then drains it. Query dropped its equivalent because
"a row vector computed in full has none of those needs." Schema's is not
different: the borrowed input was always a *finished* reader — the test
helper appends and closes a ring buffer; `PublishedSchemaRows` was closed
from construction. `query/relation` is the honest spelling of it. V8, V9
and V10 go.

**V11 does not go.** r1 marked "schema/current never closes a borrowed
source" as vacuous in the value model. Both reviews showed why that was
wrong: one source schema is handed *is* closable — the opened published
index carries a store — and r1's V14 could not have observed an early close,
because `close-published!` is idempotent (`query.cljc:265-266`) and the
returned fact-relation is store-independent. A future `schema/current` that
closed the store it was handed would have passed the whole suite. So the
property is re-pinned where it is real: V14 asserts, **before** the owner
closes, that schema closed nothing.

**The seam, host-neutral.** `open-published!` returns `{… :store store
:close-guard (atom false)}` and `jing/close!` delegates to the store's
`:close-fn` (`jing.cljc:318-325`). The test wraps the store it was handed:

```clojure
(let [closes  (atom 0)
      base    (:close-fn (:store opened))
      opened' (assoc-in opened [:store :close-fn]
                        (fn [] (swap! closes inc) (when base (base))))
      view    (schema/current opened')]
  (is (zero? @closes) "schema/current closed nothing")
  (query/close-published! opened')
  (is (= 1 @closes) "the owner's close reached the store exactly once")
  (is (= expected (qq form view)) "the view is self-contained after close")
  (is (= expected (qq form view)) "and stays so"))
```

No `with-redefs`, so it runs on ClojureDart — an improvement on the
`jing/close!` spy `query_test` uses and has to skip on cljd. The
`RecordingStream` close-count atom, `borrowed-path-does-not-close-again`,
`open-closed` and the fixtures namespace still go (D9); the property they
carried moves here.

### D7 — `publish!` loses its closed guard and its lock. (Confirmed by both reviews.)

The transactor plan's D8 moved schema's `publish!` guard under the wrapper
lock and deferred to this plan the alternative of "redefining
publication-after-close as permitted." Rule for the alternative:

- **The inner value permits it.** `tx/publish!` (`transactor.cljc:271`) has
  no closed check; T7 says close "rejects further writes" and says nothing
  about publication, which is a *read* of the caller's still-open local
  stream plus an enqueue into the caller-owned pool. `tx/close!` sets only
  its own flag; there is no use-after-close.
- **The guard is what created the race.** Delete the guard and the lock
  extension has nothing to protect; `publish!` becomes thin delegation and
  the lock is no longer held across an index build.
- **Datomic's shape.** Releasing a connection does not invalidate a `db`
  you hold.

`publish-serializes-against-close` is `[T✗]`: all four assertions are about
the guard. The one property in it worth keeping — an in-flight publication
completes with the full history — is `index/publish-index!`'s over a
complete-retention transport, pinned by `publish-enqueues-indexes-into-the-
pool`. The design gains one sentence: publication after close is permitted
and reads the caller's still-open local stream.

### D8 — The wrapper's white-box seams. (Consequence of D1)

`wrapper-state` (`schema_test:676-682`) reaches `(.-state w)` with a
per-host type hint — the ClojureDart private-field trap. With the wrapper a
map it becomes `@(:state w)` with no reader conditional. `transact!`'s
`^SchemaWrapper` hints and `.-inner`/`.-local-stream`/`.-strict?` become
destructuring. The `with-write-lock` helper is unchanged. The `:inner` key
is a second white-box seam the new close test uses (§4): it is the same
exposure `transactor/create!` already ships with `:next-t` and `:state`.

### D9 — `schema_fixtures` is deleted, and its ClojureDart constraint no longer binds. (Brief Q7)

The namespace's docstring states its reason precisely: a `defopen`
registration is a *multimethod contribution* into `dao.stream/open!`, and
on ClojureDart a contribution emitted from a test namespace points at a path
the renamed test library does not occupy. The constraint is about
registration into an ambient table, not about `deftype`/`reify` in test
namespaces — `schema_test` already `reify`s v2 protocols inline at `:1041`
and `:1093` on all three hosts, and V13a's scripted readers do the same.
With no `defopen` anywhere in schema (D3, D5) there is nothing to register.
Both consumers of the fixture are `[T✗]` under D3/D6. The file goes.

### D10 — Two phases, by seam; write side first. (Brief Q9)

Schema has no in-tree consumer to keep compiling, so nothing *forces* a
split. Split anyway, for review rather than compilation: the write side is
~85 mechanical test edits over a `deftype`-to-map rewrite whose semantics
(§2.4) must not move, while the read side deletes two routes, a namespace
and a coordinate type and rewrites or adds ten deftests. One commit mixing
"nothing semantic changed" with "these tests are gone" cannot be checked by
rule; two commits can each be checked by their residue grep.

Write side first, because after it the test file's remaining `ds/` count is
exactly the read-side inventory (§5) and Phase 2's closure grep is then the
end condition. W38, W39, W41 are touched in both phases (their `ds/close! w`
in Phase 1, their `schema/published` in Phase 2); W40 is touched in Phase 1
and deleted in Phase 2. Four deftests edited twice is the whole cost of the
split. Both design documents also span the phases, each phase carrying the
paragraphs its code makes stale.

---

## 4. Phase 1 — the write side

### Build

`src/cljc/dao/space/schema.cljc`:

- `transactor` returns the D1 map. Docstring: owns the inner value; the
  wrapper's flag is its own; no `closed?`.
- `transact!`: empty-`tx-data` throw moved **above** the lock (D1 step 1);
  under the lock, `(if (:closed @state) {:dao.stream/outcome
  :dao.stream/closed} …)` first; the success branch returns `result` (the
  inner receipt) unchanged; the non-ok branch unchanged. Docstring rewritten
  on the v2 receipt and the closed-precedence rule.
- `close!`: new public fn — under the lock, `swap!` `:closed true`,
  `tx/close!` on `:inner`, return `{:dao.stream/outcome :dao.stream/ok}`.
- `publish!`: `(tx/publish! (:inner w) opts)`. No guard, no lock (D7).
- Delete `SchemaWrapper` and its `ds/IDaoStreamBound` implementation. The
  `[dao.stream :as ds]` require and the `:require-macros` **stay this
  phase** — `current` and the openers still use them.

`docs/design/dao.space.schema.md`, in-phase:

- §3.1 ownership paragraph (`:383-:392`): `close!` returns the v2 close
  outcome; there is no `closed?`; `transact!` on a closed wrapper answers
  `closed` as data, with the D1 precedence rule (empty `tx-data` throws
  regardless; everything else answers `closed` first) stated as the
  transactor's own rule adopted.
- §3.1 D10 paragraph (`:394-:403`) **deleted**, replaced by one sentence:
  `transact!` returns the inner transactor's receipt unchanged — ok,
  refused, or closed — and throws only for defects in the caller's argument.
- §3.1 gains D7's sentence on publication after close.
- §7's usage block: receipt comments show the v2 shape; add
  `(schema/close! log)`.
- `docs/design/dao.space.transactor.md`: T20 becomes "retired 2026-09-09
  under schema's plan; schema returns the transactor's receipt unchanged and
  adopts its closed-precedence rule"; the *Open items* bullet on schema's
  mixed return is deleted.

### Delete

`SchemaWrapper`; the `{:result :ok …}` re-wrap; `publish!`'s guard and
lock; `transact!`'s closed throw.

### Tests

`test/dao/space/schema_test.cljc`:

- Add `[dao.space.transactor :as tx]` to the requires (for the inner-value
  assertions below).
- **60** `(ds/close! …)` on wrappers → `(schema/close! …)`; the 61st
  (`:1123`) dies with the race test; `:307` and `:621` are Phase 2's.
- **22** receipt lines per D1's rule, including `:1062` and `:1071`; `:1119`
  and `:1120` die with the race test.
- `wrapper-state` per D8; `:708` deleted.
- `closed-wrapper-throws` → `closed-wrapper-answers-closed`: assert the
  `closed` outcome map; keep the local-stream `blocked` assertion.
- `publish-serializes-against-close` **deleted** (D7, L9), with its
  `slow-local` reify and the `{:woke []}` pin.
- **New** `close-is-idempotent-and-closes-the-inner-value`: two `close!`s
  both answer `ok`; `(tx/transact! (:inner w) [[7 :x 1]])` answers
  `closed` — the direct pin that `tx/close!` was called; `(schema/transact!
  w [[7 :x 1]])` answers `closed`; the local stream still answers `blocked`
  at `newest` (L1, L2).
- **New** `closed-precedence-matches-the-transactor` (L10, D1): after
  `close!`, `(schema/transact! w [])` throws `#"at least one"`;
  `(schema/transact! w [[:db/add 30 :db/foo :bar]])` answers `closed`; the
  same two calls on `(:inner w)` throw and answer `closed` respectively.
- **New** `publish-after-close-reads-the-callers-stream` (D7): transact,
  close, publish; the manifest's `:count` equals `(count (datoms-of local))`.

### Prove

- Every §2.4 deftest passes with only the two mechanical edit classes
  applied. A diff of the test file filtered to lines containing `close!`,
  `:result`, `(:t `, `:datoms`, `:dao.stream/`, `:dao.space/` accounts for
  every changed line outside the deftests named above — the reviewer's
  check that §2.4 moved nothing.
- `failed-inner-append-leaves-wrapper-state-unchanged` passes with only
  `:1062` and `:1071` changed — T19's pin.
- Deftest count: 70 → **72** — one deleted (`publish-serializes-against-
  close`), three added; the rename is not a count change.
- `grep -c "ds/" src/cljc/dao/space/schema.cljc`: 16 → **14** — the
  protocol declaration at `:946` and `ds/closed?` at `:1079` go; the
  `deftype`'s method names are unqualified and were never counted.
- `grep -c "ds/" test/dao/space/schema_test.cljc`: 68 → **6** — 60
  rewritten, 1 deleted with the race test, `:708` deleted; the six that
  remain are `:305`, `:306`, `:307`, `:615` (prose), `:621`, `:1630` — the
  read-side inventory. The require and require-macros lines contain no
  `ds/` and are not count contributors.
- `clojure -M:test`; `bb test:cljs` (confirm `Testing dao.space.schema-test`
  in the node output); `bb test:cljd`; `clj -M:cljs -m
  shadow.cljs.devtools.cli compile demo`.

## 5. Phase 2 — the read side

### Build

`src/cljc/dao/space/schema.cljc`:

- `interpret-view` takes a query value; the `ds/realization?` branch is
  gone. Body otherwise unchanged.
- `current`, the D3/D4 contract:

```clojure
(defn- snapshot-result? [x]
  (and (map? x) (contains? x :relation) (contains? x :status)))

(defn current
  ([source] (current source nil))
  ([source opts]
   (let [as-of        (when (map? opts) (:as-of opts))
         schema-as-of (when (map? opts) (:schema-as-of opts))
         source (if (snapshot-result? source)
                  (do (when-not (#{:ended :blocked} (:status source))
                        (throw (ex-info (str "schema/current rejects a snapshot that reported "
                                             (name (:status source))
                                             ": the read was incomplete or defective")
                                        {:status (:status source)})))
                      (:relation source))
                  source)]
     (validate-not-nested-view! source)             ; query views, :fact? relations
     (interpret-view source as-of schema-as-of))))  ; query/history rejects the rest
```

  `snapshot-result?` mirrors query's private `snapshot-value?`; schema
  spells its own two-line predicate rather than widening query's surface.
  The docstring carries D4's limit verbatim: rejection detects an observed
  read failure; a prefix evicted before the snapshot is indistinguishable
  from complete history and is the caller's declaration, not schema's check.
- `validate-not-nested-view!`: drop the `:dao.stream/type` arm, add the
  `:fact?` arm; message "schema/current source must be a d5 source value,
  not a nested view".
- Delete the `[dao.stream :as ds]`, `[dao.jing :as jing]` and
  `[dao.jing.coordinate :as jing-coordinate]` requires and the
  `#?(:cljs (:require-macros [dao.stream]))`. Final require set:
  `dao.datom`, `dao.space.index`, `dao.space.query`, `dao.space.transactor`.
- Delete the four kondo-ignore forms.

`docs/design/dao.space.schema.md`, in-phase:

- §4's descriptor block (`:494-:507`) and *Realization* paragraph
  (`:509-:531`): replaced by the value contract — `schema/current` is a
  function from a d5 source value (a relation value, an opened published
  index, or a `query/snapshot` result) to a fact-relation value; it opens
  and closes nothing and never closes a source it is handed (V11/V14);
  nested views and its own output are rejected; **D4 in full**: `:gap`/
  `:defect` rejected as observed read failure; completeness is the caller's
  declaration, with the pointer to `dao.stream.md` *Complete history* and
  the sentence that a suffix from an already-evicted transport is
  indistinguishable from complete history and wiring one is a
  host-assembly defect (the T18 form).
- §5's published-descriptor paragraph and block (`:568-:584`): deleted.
  Replaced by: the published coordinate is `index/published-index`, opened
  by `query/open-published!` and closed by its caller; `schema/current`
  forces the rows before returning and does not close the store, so the
  caller may close immediately (V14); no schema-typed coordinate because
  the lens is the reader's (D5).
- §7: "Both openers register at namespace load … fails closed otherwise"
  (`:625-:627`) deleted; the read example becomes `open-published!` →
  `schema/current` → `close-published!`.
- §8 *Implementation verification*: the `defopen`/`ViewStream`/`::owned`
  sentence and "`:source` is an `open!`-dispatchable d5 descriptor" replaced
  by "the view is a value; V13a/V13b/V14/V15". Add a ruling bullet for the
  V15 limit so it is findable as a ruling, not only as a test.
- §9: the borrowed-path and ownership-trap items → V13a/V13b/V14/V15's
  statements; "publisher parity … through its registered opener" → "through
  `query/open-published!`".
- Status paragraph: one sentence recording this migration and the date.
- `docs/design/dao.stream.md` consumer list (`:803-:805`): `dao.space`
  removed.
- `docs/design/dao.space.transactor.md` *Related documents*: the schema
  line loses "its D10 rule governs the shapes it re-wraps".

### Delete

`ds/defopen :dao.space.schema/current` and its body; the `ds/open!` call in
`current`; `published`; `PublishedSchemaRows`; `ds/defopen
:dao.space.schema/published`; **`test/dao/space/schema_fixtures.cljc`**
(D9).

### Tests

`test/dao/space/schema_test.cljc`:

- Requires: drop `dao.stream`, `dao.space.schema-fixtures`, the
  `:require-macros`. `dao.stream.v2`, `memory-log`, `ringbuffer` stay.
- `open-closed` helper deleted.
- **Deleted:** `borrowed-and-descriptor-paths-agree`, `inner-stream-closed-
  after-descriptor-path`, `borrowed-path-does-not-close-again` (V8, V10;
  V11 moves to V14), `published-descriptor-validation` (P4).
- `nested-view-descriptor-rejected` case 3: `(schema/current (schema/current
  rel))` throws with the new message (V6).
- `unknown-db-type-throws-at-realization`: comment rewritten; assertion
  unchanged (V7).
- W38, W39, W41: nested `try`/`finally`, so `opened` is in scope of the
  close that releases it:

```clojure
(let [{:keys [manifest-address]} (schema/publish! w)
      path (temp-content-path "parity")]
  (try
    (materialize-to-file-store intake path)
    (let [opened (query/open-published!
                   (index/published-index {:dao.jing/type :dao.jing/file :path path}
                                          manifest-address))]
      (try
        (is (= live-result (qq q-form (schema/current opened))) …)
        (finally (query/close-published! opened))))
    (finally (cleanup-file path))))
```

  Assertions unchanged (P1–P3). `ds/open!` at `:1630` goes with W40.
- **New** `snapshot-read-failures-are-rejected` (V13a): two `reify`d v2
  readers modelled on `query_test:359` and `:385` — one reads a value then
  answers `gap` with a recovery cursor; one answers `cursor-mismatch` —
  `query/snapshot` each, `schema/current` throws naming `:gap` and
  `:defect` respectively.
- **New** `complete-snapshots-are-accepted` (V13b): a memory-log receives
  `schema-rows` plus two card-one values at different `t`; open →
  `:blocked` accepted, answer equals `(schema/current (query/relation
  rows))`; `stream/close!` the log → `:ended` accepted, same answer.
- **New** `evicted-prefix-is-not-detectable-through-snapshot` (V15): a v2
  ring buffer, `:dao.stream.ringbuffer/capacity 4`, receives `schema-rows`
  then `[7 :person/name "old" 1 1]` `[7 :person/name "new" 2 1]`;
  `query/snapshot` → `(is (= :blocked (:status snap)))`; `schema/current`
  accepts and `(qq '[:find ?v :where [7 :person/name ?v]] view)` is
  `#{["old"] ["new"]}` — the vocabulary was evicted and collapse did not
  fire. The deftest's leading comment states this is the documented D4
  limit, not a guarantee, and names the two instruments that would have
  prevented it.
- **New** `schema-view-is-self-contained-and-never-closes-the-store` (V14):
  publish, materialize, `open-published!`, then the D6 seam verbatim —
  wrap the store's `:close-fn` with a counter, `schema/current`, assert
  zero closes, `close-published!`, assert one, then `q` twice over the view
  after the close. `jing-file` as W38.

### Prove

- `grep -c "ds/" src/cljc/dao/space/schema.cljc` → **0** (14 → 0).
- `grep -n "\[dao.stream\|\[dao.jing\|require-macros"
  src/cljc/dao/space/schema.cljc` → **nothing**. (The receipt keywords
  `:dao.stream/outcome`, `:dao.stream/ok`, `:dao.stream/closed` remain by
  design; a grep for the bare string `dao.stream` is not a closure check.)
- `grep -c "ds/" test/dao/space/schema_test.cljc` → **0** (6 → 0).
- `test/dao/space/schema_fixtures.cljc` does not exist; `grep -rn
  "schema-fixtures\|schema_fixtures\|schema/published\|dao.space.schema/published\|dao.space.schema/current\|PublishedSchemaRows\|SchemaWrapper" src test docs/design` → **nothing** (the
  orchestrator log is history and is not edited).
- Deftest count: 72 → **72** (four deleted, four added).
- `grep -rln "\[dao.stream :as" src/cljc/dao/space` → **nothing**: every
  `dao.space.*` namespace is off v1.
- Three lanes and the demo compile, as Phase 1.

---

## 6. Host matrix

| Concern | clj | cljs (Node) | cljd |
|---|---|---|---|
| `with-write-lock` | `locking` on the state atom | `(f)` | `(f)` — unchanged, no new reader conditional |
| Wrapper as a map (D8) | removes `^dao.space.schema.SchemaWrapper` hint | same | removes the `^dao.space.schema/SchemaWrapper` hint and the private-field trap; `@(:state w)` and `(:inner w)` are host-neutral |
| Exception types in new tests | `Exception` | `js/Error` | `Object` — the existing idiom, `:cljd` before `:default` |
| `concurrent-strict-unique-writes-serialize` | runs | `(is true)` | `(is true)` — unchanged |
| `publish-serializes-against-close` | deleted | was `(is true)` | was `(is true)` |
| V13a scripted readers | `reify stream/IDaoStreamReader` | same | same — the pattern `schema_test:1041` already uses on all hosts |
| V14 ownership counter | `assoc-in [:store :close-fn]` on the opened value | same | same — plain data, no `with-redefs`, so unlike `query_test`'s spy it is **not** skipped on cljd |
| V15's ring buffer | `dao.stream.v2.ringbuffer`, capacity 4 | same | same |
| V13b/V14 file store | `jing-file/create-content-file` | same (`fs`) | same (`dart:io`) — `materialize-to-file-store` and `cleanup-file` already carry the conditionals |
| Test discovery | `cognitect.test-runner` over `test/` | shadow `:node-test` auto-discovers `*-test`; confirm `Testing dao.space.schema-test` | `clojure -M:cljd test` over `test/`; deleting `schema_fixtures` removes a non-test namespace and needs no allowlist edit |
| Reader conditionals added by this plan | **none** | **none** | **none** |

---

## 7. The boundary

### Built here

- `dao.space.schema` on the value model: `transactor` → map, `transact!`
  returns the inner receipt with the transactor's closed precedence,
  `close!`, `publish!` unguarded, `current` over values with D4's rejection
  and D4's stated limit.
- Deleted: `SchemaWrapper`, both `defopen`s, `PublishedSchemaRows`,
  `published`, the borrowed-realization branch, `schema_fixtures`, every
  `dao.stream`/`dao.jing`/`dao.jing.coordinate` require in `schema.cljc`.
- `dao.space.schema.md` rewritten where §3.1, §4, §5, §7, §8, §9 stand;
  `transactor.md` T20 retired; `dao.stream.md`'s consumer list loses
  `dao.space`.
- Seven new tests (Phase 1: idempotent close with the inner-value pin,
  closed precedence, publish-after-close; Phase 2: V13a, V13b, V14, V15);
  five deftests deleted with named reasons (V8, V10, V11→V14, P4, L9), one
  renamed, one assertion removed.

### Confirmed, not assumed

- **`public/demo.html`**: schema is not on its path (§0.5). The demo *is*
  on v1 `dao.stream` through `compilation_pipeline.cljs:15-16` and
  `artifact.cljs:5-6`; that is the "demo and server surfaces" row of
  `dao.stream.md`'s consumer list and is outside this plan.

### Left owing, by namespace — after this plan, `dao.space.*` is entirely v2

At the end of Phase 2, `grep -rln "\[dao.stream :as" src/cljc/dao/space`
returns nothing; `dao.space.query`, `dao.space.index` and
`dao.space.transactor` require `dao.stream.v2`, while `dao.space.schema`
and `dao.space.transact` require no stream namespace. **The brief's Q10
answer is yes.** What the rest of the repo still holds on v1 (`grep -rln
"\[dao.stream :as\|dao.stream\]" src`, measured at `4b9f0e7`), none of it
planned here:

| Group | Namespaces on v1 |
|---|---|
| **The v1 transports** (deleted with v1, not migrated) | `dao.stream.{apply, file, file-input-stream, file-output-stream, http, link, ringbuffer, udp, ws}`, `dao.stream.rpc.{client, server, udp, ws}`, `src/cljd/dao/stream/{udp,ws}.cljd` |
| **`yin.vm.*` lineage** (deleted under `yin.vm.v2-consumers.implementation-plan.md`) | `yin.vm`, `yin.vm.{ast-walker, engine, ffi, register, semantic, space, stack, stream-driver, telemetry}`, `yin.repl`, `src/clj/yin/vm/{bytecode_bench, telemetry_server/jvm}`, `src/cljd/yin/register_bench_cljd.cljd` |
| **`yin.io`** with its two consumers | `yin.io.{file, file-input-stream, file-output-stream}`, `dao.gui.event`, `dao.postgraphics.terminal` |
| **`dao.runtime`** (gated on the v1 VM's deletion) | `dao.runtime`, `dao.await` |
| **`dao.jing`'s remote adapter and DHT node** | `dao.jing.remote` (via `dao.stream.rpc.client`/`rpc.ws`, clj only), `dao.jing.dht.node` (via `dao.stream.transit`) |
| **Agent tooling** | `agent.tools`; `agent.tzu` is dead code and excluded |
| **Demo and server surfaces** | `datomworld.continuation-transport`, `datomworld.demo.{earth-moon-runner, voxel-runner}`, `src/cljs/datomworld/demo/{artifact, compilation_pipeline, continuation_stream, equation_plotter, solar_system, yin_repl}`, `src/cljs/datomworld/ws_client_demo`, `src/cljs/yin/vm/telemetry_viewer`, `src/clj/datomworld/ws_demo_server`, `src/cljd/datomworld/demo/{artifact, dao_gui, postgraphics, solar_system}` |

Plus 45 test namespaces under `test/` requiring `[dao.stream :as ds]`, of
which the two schema files are the only `dao.space` ones. `dao.stream.v2`
cannot take the name until the table above is empty; this plan empties one
row of `dao.stream.md`'s list and adds nothing to any other.

### Not owed, stated so it is not mistaken for a gap

An origin-cursor or provenance-carrying variant of `query/snapshot` (D4).
Schema's contract is complete without it: the limit is stated, pinned
(V15), and placed where a composition wiring a snapshot source will read
it. If query's own plan ever adds such a variant, schema accepts its result
on today's terms and V15 is the test that must change.

---

## 8. End condition

- `dao.space.schema` requires exactly `dao.datom`, `dao.space.index`,
  `dao.space.query`, `dao.space.transactor` — no stream namespace of either
  generation, no `dao.jing`. The `:dao.stream/*` receipt keywords remain,
  as in the transactor.
- `schema/transact!` has one result shape, the transactor's, and the
  transactor's closed precedence; `schema/close!` returns the v2 close
  outcome; there is no `closed?`, no `published`, no registered opener, no
  `SchemaWrapper`, no `PublishedSchemaRows`, no `schema_fixtures`.
- `schema/current` rejects observed read failures and **documents, in the
  design and in a test, that it cannot detect a prefix evicted before the
  snapshot** — completeness is the caller's declaration.
- `docs/design/dao.stream.md`'s remaining-consumer list no longer names
  `dao.space`; `docs/design/dao.space.transactor.md` T20 is retired and its
  schema open item deleted; `docs/design/dao.space.schema.md` §3.1, §4, §5,
  §7, §8, §9 describe the value model, D4's limit, and nothing else.
- The transactor Phase 3 record's "schema's `finally`" note is closed by
  D5's pointer to `query_test:1076` and `:716`.
- Every `dao.space.*` namespace is on v2. Nothing is owed to a later plan by
  this namespace; what remains before the rename is §7's table, each row
  under its own plan.
