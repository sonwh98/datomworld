# dao.space.query — The Reader-Side Index Consumer

Status: implemented. The read coordinate is an explicit bounded DaoStream descriptor, interpretation enters only through explicit immutable
views (`current`, `history`) or explicit stream wrappers (`query/relation`, `query/entity-map-relation`), the index realization is owned
by `dao.space.index`, and `match` / `q` / `pull` run over one evaluator on
every platform, returning local closed bounded result DaoStreams. This
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

The query library is an embeddable Peer over bounded DaoStreams. It opens each
explicit descriptor, consumes its logical tuples, and runs matching / Datalog /
pull above them. A published-index stream resolves its content-store coordinate
and manifest address below this boundary. The query library is pure
and stateless — it owns no durable state, never writes, and enforces no
schema; any schema policy belongs on the write side.

```
dao.space.query/q …/match …/pull         ← the TUPLE SPACE reads
     │  consumes exact-bounded logical tuple streams
     ▼
dao.jing content store (content handles) ← STORAGE boundary
     ▲
dao.space.index/publish-index!  (write side, a separate library)
```

## The read coordinate: bounded DaoStream descriptor

A published covered index must be exposed as a bounded DaoStream descriptor/realization whose logical elements are canonical d5, with physical B-tree segments hidden below the adapter. The descriptor contains a resolvable content-store coordinate plus manifest address, not necessarily a live handle; its portability is conditional on that coordinate being resolvable by the receiving runtime.

```clojure
(index/published-index
  {:dao.jing/type :dao.jing/file
   :path "/srv/datom-world/content.log"}
  :segment/sha256-<manifest-hash>)

;; The resulting descriptor has this exact data shape:
{:dao.stream/type :dao.space.index/published
 :dao.stream/bound {:manifest-address :segment/sha256-<manifest-hash>}
 :dao.stream/comparator :dao.space.index/eavt
 :content-store {:dao.jing/type :dao.jing/file
                 :path "/srv/datom-world/content.log"}
 :manifest-address :segment/sha256-<manifest-hash>}
```

A query may receive several descriptors as separate `:in` database values. A DaoStream descriptor is itself data and may be transported through another DaoStream.
Source identity is interpreter context and never a tuple slot. It is never
inferred from a DaoJing intake stream, and **a bare content-store handle
carries no source**: it must be wrapped in a valid DaoStream descriptor before
`q`/`match`/`pull` will read it, and passing one unwrapped throws.

Two kinds of pools must not be confused:

- **DaoJing intake pools** are physical ingestion topology — which streams an
  observer materializes into content storage.
- **Query db-values** are semantic composition — which bounded streams a
  reader names independently in `:in`.

They never need to coincide, and DaoJing never learns the latter. A manifest
address is a content address like any other; it is a snapshot-at-read of an
immutable value, not a mutable "root" reference, and the query layer treats it
as such.

## Source polymorphism

`dao.space.query/q` accepts database values only as:
1. A serializable exact-bounded DaoStream descriptor carrying `:dao.stream/type` and `:dao.stream/bound`
2. An already-opened closed fully-retained realization satisfying Reader+Bound.

Raw maps/vectors are not `q` database inputs; callers explicitly wrap arbitrary mixed-dimensional tuples with `query/relation` and entity maps with `query/entity-map-relation`.

Each database input is one logical relation:

- **A published index** — a bounded DaoStream descriptor. `q` opens the
  descriptor and owns the realization for that query. A realization supplied
  directly by the caller is borrowed and is never closed by `q`.
- **An explicit datom view** — `current` and `history` are semantic view
  values interpreted by `q`/`match`/`pull`; their nested source remains the
  bounded DaoStream descriptor or realization. `(current source)` resolves a canonical d5 history and
  projects it to d3; `(history source)` preserves exact d5. Independent
  physical sources stay separate database inputs.
- **A wrapped relation** — `(query/relation [[...] ...])` wrapping arbitrary and mixed arities. Arity never selects an interpretation.
- **A wrapped entity-map collection** — `(query/entity-map-relation [{:db/id e, :work/status :todo, ...} ...])`. Normalized to datoms first: each `k v` pair becomes an `[e k v]` fact. Identity is explicit: the read side is pure and never mints an entity id, so every entity map must carry `:db/id`, and a map without one throws an informative `ex-info` rather than inventing a tempid.

`q` eagerly consumes every input before returning. It interprets datom views,
opens and closes descriptor-owned source realizations during evaluation, and
directly opens ordinary stream descriptors. Already-opened realizations
are borrowed and never closed. It performs arbitrary-dimensional exact
positional matching/unification (with explicit `&` tail syntax) and returns a
local closed bounded result DaoStream. `query/collect` materializes conventional
Datalog shapes.

The local result realization and derived realizations returned when a view is
applied to an already-open source are intentionally not DaoStream descriptors:
they are not serializable or reopenable transport identities. To transport a
result, first materialize it with `query/collect`, then wrap the retained tuples
explicitly with `query/relation`.

Source polymorphism is an ergonomic property of the query *function*, not a
second medium — a local realization is by definition not shared, and
coordination between agents still runs through shared content storage.

## Reading a manifest

A bounded DaoStream descriptor is opened into a realization.

- **The implemented published-index DaoStream adapter** opens the declared
  DaoJing coordinate, validates the manifest address, walks EAVT with
  `index/read-datoms`, and retains the resulting canonical d5 vector. This is
  eager and gives `q` one uniform Reader+Bound surface.
- **The lower-level index library also exposes lazy restoration** through
  `index/restored-indexes`. Query does not currently exploit that path; a
  future positional-index interpreter may do so without changing `q`.

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
  readers consume explicitly supplied descriptors as independent db-values. A
  published index descriptor is opened as a logical d5 stream; query composition
  stays in the interpreter. Index-once, reuse-by-many, and available
  when the author is offline — the decentralized analog of Datomic's
  transactor-built index. Implemented on JVM,
  ClojureScript, and ClojureDart on `dao.data.btree` (see
  `dao.space.index.md`). The current stream adapter walks EAVT eagerly;
  `restored-indexes` remains an explicit lower-level lazy API.

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

The published-index DaoStream adapter currently uses this eager path. Lazy
`restored-indexes` remains available to a future positional-index interpreter,
but it is not hidden inside `q` and is not part of the current descriptor-open
contract.

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
  precomputed positional index naming an indexed snapshot the owner published.

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
  just more content-addressed blobs; lazy traversal, when an interpreter uses
  `restored-indexes`, is a **reader-side** property — the tree pulls a node only when traversal reaches it, and storage
  never scans or seeks, it just answers `get`. Any "storage-side
  materialization" would mean a backend *computing* something, which collides
  head-on with storage-never-interprets. A backend-private network shortcut
  (e.g. `dao.jing.dht` batching) stays possible but lives entirely inside that
  backend's own transport.
- **Coordinate semantics: reference at naming, snapshot at read.** A
  published index descriptor names a manifest by content address; each read resolves
  the immutable value at that address. This is Datomic's `d/db` pattern
  exactly (a db value is immutable; calling `d/db` again gets a fresher one) —
  no new mechanism, just "immutable segments + explicit address."
- **Owner-built, peers-merge is the target (implemented).** Incremental
  indexing is the degenerate case where owner and reader coincide.
  Rebuild-per-query stays the permanent fallback for small sources or when no
  persisted manifest exists yet.
- **Freshness: explicit, monotone lag.** A reader's merged view reflects
  whatever manifest each source names *at fold time* — never live, never
  blocking.
- **`dao.stream`/`dao.jing` unification: ruled out.** The source stays a
  convention layered over the dumb content store. The DHT division of labor,
  the observer design, and the tuple space all depend on `dao.stream` staying
  upstream plumbing and `dao.jing` staying the dumb boundary; unifying them
  would undo that.

## Open items

- **Published indexed snapshots and K-way merge** — several explicitly scoped manifests
  could be exposed as an explicit derived relation without flattening source
  identity, then answered by merging N restored B-trees in index order.
- **Generic relation positional indexes** — a positional index orders a
  relation by explicitly selected tuple positions without assigning meaning to
  those positions. The bounded tuples, their exact bound, and their explicitly
  requested positional indexes form an **indexed snapshot**. Arbitrary tuples
  currently use a relation scan. An explicit
  datom interpreter automatically supplies the covered indexes for canonical
  d5 facts; arity alone never selects that interpretation. Every other n-tuple
  relation must explicitly request its positional indexes from the caller or
  publisher. The planner may select only from those supplied indexes and falls
  back to a relation scan when none fits; it never constructs every positional
  permutation implicitly. This generic path remains future work.
- **Segment GC and incremental indexing** — see `dao.space.index.md`.
