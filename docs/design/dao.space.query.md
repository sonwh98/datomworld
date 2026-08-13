# dao.space.query — The Reader-Side Index Consumer

Status: implemented. The read coordinate is the explicit published source
(`published-source`), interpretation enters only through explicit immutable
views (`current`, `history`) or raw sources, the index realization is owned
by `dao.space.index`, and `match` / `q` / `pull` run over one evaluator on
every platform. This
document records the read model, the source model, the index-realization
decision, the query surface, and the open items. The executable contract is
`test/dao/space/query_test.cljc`.

**Related documents:**
- `docs/design/dao.space.index.md` — the transactor-side realization this
  library consumes
- `docs/design/dao.space.md` — the tuple space; *The Query Library* and
  *Source Polymorphism*
- `docs/design/dao.jing.md` — the storage boundary; *Physical intake versus
  semantic composition*
- `docs/design/dao.jing.dht.md` — the distributed backend a published source
  may be read through
- `docs/design/adr/0001-dao-space-as-storage-boundary.md` — the storage
  boundary decision and the monoid-homomorphism proof below
- `docs/datomic.md` — the Datomic architecture the Peer model maps to

## Architecture

The query library reads a content store as an embeddable Peer: it resolves an
explicit published source to its manifest address, restores or folds the
covered B-trees, and runs matching / Datalog / pull above them. It is pure
and stateless — it owns no durable state, never writes, and enforces no
schema; any schema policy belongs on the write side.

```
dao.space.query/q …/match …/pull         ← the TUPLE SPACE reads
     │  lazily restores B-trees, or folds datoms
     ▼
dao.jing content store (content handles) ← STORAGE boundary
     ▲
dao.space.index/publish-index!  (write side, a separate library)
```

## The read coordinate: published source

`(query/published-source content-store manifest-address)` names one published
manifest by its explicit store + address coordinate:

```clojure
(query/published-source store :segment/sha256-<manifest-hash>)
;; => {::content-store store, ::manifest-address :segment/sha256-…}
```

A query may receive several descriptors as separate `:in` database values.
Source identity is this explicit coordinate alone. It is never
inferred from a DaoJing intake stream, and **a bare content-store handle
carries no source**: it must be wrapped in `published-source` before
`q`/`match`/`pull` will read it, and passing one unwrapped throws.

Two kinds of pools must not be confused:

- **DaoJing intake pools** are physical ingestion topology — which streams an
  observer materializes into content storage.
- **Query db-values** are semantic composition — which published content a
  reader names independently in `:in`.

They never need to coincide, and DaoJing never learns the latter. A manifest
address is a content address like any other; it is a snapshot-at-read of an
immutable value, not a mutable "root" reference, and the query layer treats it
as such.

## Source polymorphism

`q` and `match` read immutable **db-values**, not narrowly content handles.
Each database input is one logical relation:

- **A single published source** — `(query/published-source store addr)`;
  `fold` reads exactly that one manifest.
- **A raw relation** — a collection of vectors of arbitrary and mixed arities.
  It is passed through unchanged. Arity never selects an interpretation.
- **An explicit datom view** — `(current source)` resolves a canonical d5
  history and projects it to d3; `(history source)` exposes exact d5. These
  views accept a local d5 collection or one published source. Independent
  physical sources stay separate database inputs.
- **A raw Clojure vector of entity maps** — `[{:db/id e, :work/status :todo,
  ...} ...]`. Normalized to datoms first: each `k v` pair becomes an
  `[e k v]` fact. Identity is explicit:
  the read side is pure and never mints an entity id, so every entity map must
  carry `:db/id`, and a map without one (top-level, or nested inside a mixed
  or nested collection) throws an informative `ex-info` rather than inventing
  a tempid.

Several relations compose through `:in $a $b ...`; source-qualified clauses
express union with `or` or deliberate joins with shared variables. A bare
collection of physical sources is rejected, because flattening stream-local
entity ids would fabricate identities.

Source polymorphism is an ergonomic property of the query *function*, not a
second medium — a raw in-memory vector is by definition not shared, and
coordination between agents still runs through shared content storage.

## Reading a manifest

`fold` chooses the read strategy from the source shape:

- **A single published source with no `as-of` bound** restores its manifest's
  B-trees lazily — `index/restored-indexes` over `index/read-manifest`,
  `dao.data.btree/restore-tree` on every platform. Nothing is fetched until a
  index consumer traverses; a `subseq-from` slice then loads only the descent
  path plus the matching range.
- **Every other d5 adapter shape reads eagerly** into a fresh in-memory index:
  an `as-of` bound, or a local canonical d5 source.

`index/read-manifest` validates before reading: the value at the manifest
address must be exactly `{:indexes {:eavt … :aevt … :avet … :vaet …} :count n
:branching-factor n}` and must hash to the requested address. A query therefore
never interprets a foreign value as an index.

## Index realization

`dao.jing` exposes only the substrate an index is built from — immutable,
content-addressed segments of opaque payloads — and knows nothing of indexes.
The *index*
itself, a covered B-Tree (EAVT/AEVT/AVET/VAET) a query can traverse and answer
from, is the interpretation this library projects onto those payloads. How a
reader builds and maintains it is a pure performance choice made *above* the
storage boundary; all strategies answer identically, the index being the same
set of datoms (ADR 0001's monoid homomorphism):

- **Rebuild per query** — each read folds the source's datoms into a fresh
  index and discards it. Simple; O(total datoms) per read. The permanent
  fallback for small sources and `as-of` reads.
- **Incremental index** — a long-lived reader keeps a cursor per source
  stream and folds only new frames as they arrive (Datomic-peer style). More
  machinery; amortized reads. Still no transactor and no global clock — each
  stream advances its own cursor.
- **Owner-built, peers compose (implemented)** — each stream's owner indexes
  its own stream (`dao.space.index/publish-index!`) and persists the segments;
  readers consume explicitly supplied manifests as independent db-values. A
  sole manifest can be traversed lazily through `fold`; query composition
  stays in the interpreter. Index-once, reuse-by-many, and available
  when the author is offline — the decentralized analog of Datomic's
  transactor-built index. Implemented on JVM,
  ClojureScript, and ClojureDart on `dao.data.btree` (see
  `dao.space.index.md`); laziness is cross-platform because the tree and its
  storage adapter are shared `.cljc`.

## The `read-datoms` contract

The eager read path is `index/read-datoms`, the function that turns what
`jing/get` returns into the `[e a v t m]` vectors the index is built from:

```clojure
(defn read-datoms [content-store manifest-address]
  (let [manifest (read-manifest content-store manifest-address)]
    (vec (walk-index-datoms content-store (:eavt (:indexes manifest))))))
```

It is a pure data-structure walk over whatever `jing/get` returns — it never
touches bytes or calls `edn/read-string` itself. `jing/get`'s contract is
"returns the stored opaque value at a strict segment address, already
decoded." Whether that value arrived via an EDN round-trip (`dao.jing.file`), a
plain in-memory reference (`dao.jing.mem`), an RPC (`dao.jing.remote`), or a
DHT fetch
(`dao.jing.dht`) is backend-internal and invisible here. This is exactly the
seam Datomic's Peer occupies internally when it decodes storage's index blobs
before building indexes from them — `dao.jing` itself never decodes meaning.

**Current contract:**

- **Input.** A single `jing/get` of the manifest address, returning an
  already-decoded, validated manifest.
- **Extract.** `walk-index-datoms` reads the `:eavt` node graph eagerly, one
  `jing/get` per node (plain EDN leaf/branch blobs), and returns the flat
  datoms seq in index order. A nil address (an empty index) walks to ().
- **Canonical d5 only.** Local streams and covered indexes store exactly
  `[e a v t m]`. `read-datoms` preserves those vectors. Source scope is held
  by the query interpreter and never stamped into a tuple.
- **Tuple preservation.** `read-datoms` returns stored vectors unchanged.
  Entity-map source normalization is a separate query-boundary convenience
  that supplies `t = 0` and `m = dao.datom/default-op` (*Source
  polymorphism*).

Where the *lazy* path lives: not in `read-datoms` (which is by definition the
eager collect-everything read) but in `fold` — a single published source with
no `as-of` bound bypasses `read-datoms` entirely and restores the persisted
indexes lazily, fetching nodes only as traversal reaches them. `read-datoms`
remains the correctness path every pool, raw source, and `as-of` read shares.

## Current-state resolution

The log is append-only, so a retraction is another d5 datom. Interpretation
is explicit:

- `(current source)` bounds by `as-of`, resolves the greatest `(t,m)` for
  each local `[e a v]`, removes retractions, and projects to d3.
- `(history source)` exposes the exact d5 relation, optionally bounded by
  `as-of`.
- A bare vector relation has no temporal semantics, even when every row has
  five positions.

`as-of` is accepted only with an explicit datom view. There is no implicit
cardinality-one schema or automatic supersession.

## Datalog surface

`q` implements Datalog over one or more immutable relations:

- **Positive-conjunction pattern clauses** — arbitrary mixed-dimensional
  tuples. A plain clause matches exact arity. `[fixed ... & _]` explicitly
  ignores the remaining positions; `[fixed ... & ?tail]` binds them as a
  vector. `match` uses the same positional contract.
- **Negation** — `(not clause ...)` and `(not-join [?join-var ...] clause
  ...)`. Stratified, evaluated as a failed sub-query per candidate binding
  over the already current-state-resolved index, so retractions and
  supersessions are honored. Plain `not` unifies every var it contains with
  the outer scope, and every one of them must be bound there (informative
  throw otherwise — an unbound var would act as a wildcard and silently negate
  everything), except vars scoped to a nested `not-join`, which contributes
  only its join vars to the requirement; `not-join`'s vector names the *only*
  vars that unify — any other inner var is fresh even under a colliding name —
  and those join vars must be bound. Negation forms nest (note `(not
  (not-join ...))` is double negation: "exists").
- **Disjunction** — `or` (every branch must bind the same free-var set) and
  `or-join` (only the declared join vars unify with the outer scope; the
  branches run from a seed of join vars plus the sources, and branch-local
  vars are stripped before merging; each branch must statically bind every
  declared join var). `(and ...)` groups clauses inside a branch.
- **Aggregates** — `count`, `count-distinct`, `sum`, `min`, `max`, `avg` in
  `:find`, with `:with` for multiplicity. Pipeline is Datomic's: project each
  result to the find ∪ `:with` ∪ aggregate-arg vars, dedupe those tuples as a
  set, group by the find vars only (`:with` vars keep intended duplicates
  within a group, never split groups), aggregate per group.
- **Predicates & function clauses** — `[(f ?a ...)]` filters; `[(f ?a ...)
  ?out]` (or `[?o1 ?o2]` for tuple destructuring) binds. `f` resolves from a
  caller-supplied `{:fns {sym fn}}` entry — no symbol `resolve`, no hidden
  global registry, so the surface stays pure and clj/cljs/cljd-portable. A
  default `builtins` registry of pure, data-first functions (`=`, `<`,
  arithmetic, `str`, `count`, `get`, `tuple`/`untuple`, `ground`, …) resolves
  before caller fns and can be disabled with `{:builtins false}` for
  governed/confined callers. Unknown fn, unbound argument, more than one
  binding form, tuple-arity mismatch, and the collection/relation binding
  forms (`[?y ...]`, `[[?a ?b]]` — not implemented) all throw. Predicates keep
  a binding on any *truthy* return — broader than Datomic's boolean contract,
  so a predicate returning `0` keeps the row where Datomic would be a type
  error.
- **Special forms** — `(get-else $ ?e ?a default)` and `(missing? $ ?e ?a)`
  probe the index directly as fn clauses.
- **Rules (recursion)** — Datomic syntax: a rule set bound to `%` via
  `:in $ %`, invoked as `(rule-name arg ...)` clauses. Multiple definitions of
  one head are a disjunction. A rule body runs in a fresh scope seeded from
  the caller's resolved args, so body vars are rule-local like not-join
  locals; head vars must be bound by the body (throw otherwise). Recursion
  terminates on cyclic data by failing any in-progress call with identical
  resolved args — sound for Datalog, where every fact derivable through a
  cycle also has a finite acyclic derivation. Required-bound head vars
  (`[(rule [?a] ?b) ...]`) are not implemented (throw).
- **Find specs** — relation (default), scalar (`.`), tuple (`[?x]`), and
  collection (`[?x ...]`) results, plus the return-map forms `:keys`,
  `:syms`, `:strs` (relation-only, arity-checked).

The clause planner (`plan-where`) reorders only contiguous runs of pattern
clauses by selectivity; negation, rule, and fn clauses are order barriers that
stay in source position, so their vars are bound by the clauses the query
author placed before them. Multi-source `:in $ $2 …` folds each source
independently.

## Pull

`pull` is the third read verb alongside `match` (template → datoms) and `q`
(Datalog → relations): declarative entity projection, entity → tree. Its
schema-free design rulings:

- No schema: every attr is potentially multi-valued; forward attrs follow the
  entity-attrs convention (one datom → scalar, more → vector), reverse attrs
  (`:_attr`) always return a vector.
- Ref-ness is asserted by the pattern, not guessed: a nested map spec
  navigates values as entity ids.
- Missing attrs are omitted, not nil-valued, unless the pattern gives
  `:default`; `:db/id` is included in every result map.
- Recursion markers (`'...` / depth limits) are deferred: finite patterns
  bound the walk by construction.
- `(pull ?e pattern)` also works as a `:find` element, resolved against the
  already-folded index. `pull-many` shares one fold; `entity-attrs` is the
  flat wildcard convenience.

## Freshness

Calling the persisted indexes materialized views forces an explicit answer:

- A fold of a raw source always reads canonical truth (the datoms directly).
- A served manifest can lag the current tail of its stream — it is a
  precomputed arrangement over a snapshot the owner published.

In an append-only world this is benign: indexes only *grow* as datoms are
added (retractions are semantic, above storage), so lag is monotone and
bounded, and a served view is always a faithful projection of some prefix of
the log. Because any reader can reproduce a served view by folding the
canonical datoms, a served view stays "acceleration of the one medium," not a
second coordinating substrate. A caller wanting fresher data has the owner
republish and folds the new manifest; there is no promise beyond that.

## Decisions

The following calls were settled in the 2026-07-09 discussion and remain in
force:

- **Expose how: no new storage protocol.** Everything the index realization
  needs is built on `dao.jing/materialize!` and `dao.jing/get` over strict
  segment addresses (see `dao.space.index.md`). Immutable B-tree nodes are
  just more content-addressed blobs; lazy traversal is a **reader-side**
  property — the tree pulls a node only when traversal reaches it, and storage
  never scans or seeks, it just answers `get`. Any "storage-side
  materialization" would mean a backend *computing* something, which collides
  head-on with storage-never-interprets. A backend-private network shortcut
  (e.g. `dao.jing.dht` batching) stays possible but lives entirely inside that
  backend's own transport.
- **Coordinate semantics: reference at naming, snapshot at read.** A
  `published-source` names a manifest by content address; each read resolves
  the immutable value at that address. This is Datomic's `d/db` pattern
  exactly (a db value is immutable; calling `d/db` again gets a fresher one) —
  no new mechanism, just "immutable segments + explicit address."
- **Owner-built, peers-merge is the target (implemented).** Incremental
  indexing is the degenerate case where owner and reader coincide.
  Rebuild-per-query stays the permanent fallback for small sources or when no
  persisted manifest exists yet — never removed, just no longer the only
  option.
- **Freshness: explicit, monotone lag.** A reader's merged view reflects
  whatever manifest each source names *at fold time* — never live, never
  blocking.
- **`dao.stream`/`dao.jing` unification: ruled out.** The source stays a
  convention layered over the dumb content store. The DHT division of labor,
  the observer design, and the tuple space all depend on `dao.stream` staying
  upstream plumbing and `dao.jing` staying the dumb boundary; unifying them
  would undo that.

## Open items

- **K-way merge of lazy indexes** — several explicitly scoped manifests
  could be exposed as an explicit derived relation without flattening source
  identity, then answered by merging N restored B-trees in index order.
- **Generic relation arrangements** — arbitrary tuples currently use a
  relation scan. Current d3 facts retain the covered datom indexes; future
  interpreters may supply positional arrangements for other dimensions.
- **Segment GC and incremental indexing** — see `dao.space.index.md`.
