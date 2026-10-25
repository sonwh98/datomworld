# datomic-q-v1 errata

Known limitations of the corpus and of the DaoSpace core it exercises,
recorded explicitly. Nothing is excluded silently: every case that depends
on an entry names it in its `limitation` field, and the conformance test
fails if a cited entry is missing here.

Baseline revision: `f6856bbc`. Entries describe observed behavior of
`dao.space.query` at that baseline. They are not claims about Datomic.

## E1 — Final result set collapses content-distinct numeric rows

`src/cljc/dao/space/query.cljc` (`relation-result` docstring) documents that
rows are deduplicated by content identity, but the returned host set still
compares members with host `=`. On the JVM `1.0M` and `1.00M` (decimals of
different scale) and `0.0` and `-0.0` are host `=` and hash alike, so a
result holding both holds one member. Dart collapses `0.0`/`-0.0`. Wrapping
the already collapsed set in a vector cannot recover the lost row.

Cases: `numeric.scale-collapse`, `numeric.signed-zero-collapse`. Both assert
the collapsed row count (1) on `clj` only and are classified
`documented-difference`; the intended content identity is two rows. The
corpus does not assert behavior on `cljs` or `cljd` for these cases. This
does not change persisted numeric identity or canonical bytes.

## E2 — The core silently misreads a string query

`(q "[:find ...]" db)` returns `#{[]}` on the JVM instead of refusing or
reading the string. Safe EDN reading belongs to the adapter, which does not
exist yet. Case `api.string-query` is `pending`; its recorded core
observation is a regression pin, not an expectation.

## E3 — A find variable never bound by `:where` yields a nil row

`[:find ?z :where [?e :person/name "Ada"]]` returns `#{[nil]}`. The adapter
must refuse this statically. Case `find.unbound-var` is `pending`.

Routing: the Phase 1 adapter does not perform this check
(`(q '[:find ?z ...] db)` still returns `[[nil]]`). The static bound-find-var
check is owned by Phase 2's "special-form edges" acceptance, and the case
stays `pending` until that lands.

## E4 — A trailing map argument is accepted as options without validation

The core treats one extra trailing argument as an options map and ignores
unknown keys (it even accepts a database value there). Unknown options must
not be silently ignored. Case `opts.unknown-option-refused` is `pending`.

## E5 — Aggregate parameters are dropped

`(min 2 ?a)` parses but its parameter is not preserved; the core returns
`#{[nil]}`. Parameterized `min`/`max` are an Amended Phase 0 addition and are
`pending` (`agg.parameterized-min`). `distinct`, `median`, `variance` and
`stddev` are refused today as `Unknown aggregate` and are `pending`.

## E6 — Recursive pull markers fail with a host exception

`{:person/friend ...}` and `{:person/friend 2}` in a pull pattern fail with
`Don't know how to create ISeq` instead of a typed refusal. The message is
host specific, so the case has no message assertion and is `pending`
(`pull.recursive-marker-refused`). The adapter must refuse recursion
explicitly.

## E7 — Aggregates over empty input return no row

`[:find (count ?e) :where [?e :person/nothing _]]` returns `#{}`. Whether
Datomic returns an empty result, a zero row, or something else is
unmeasured. Case `agg.empty-input` is `unverified` and `pending`; the core
result is recorded only as a regression pin.

## E8 — Cross-host float equality is unverified; hosts other than `clj` are unrun in phase 0

Float64 results (for example `avg`) depend on carrier equality
(`dao.jing.cbor/float64`) that this phase verified only on the JVM
(`agg.avg` is `clj` only). Cases listing `cljs` and `cljd` declare intended
host applicability; phase 0 qualification ran the JVM focused gate and a
Node discovery check. The three-host G1 run is the next gate.

## E9 — Some pull results depend on JVM shape or enumeration order

The core returns reverse-reference values (`:person/_friend`) and
`[:person/email :limit 1]` results as seqs (lists), and which element a
`:limit` keeps is the core's enumeration order, not a contract. Datomic's
shape and choice are unmeasured, and the other hosts' shapes are unrun.
Cases `pull.reverse-attribute` and `pull.limit-option` therefore list `clj`
only, are `pending`, and pin the JVM observation as a regression pin.

## E10 — Superseded pins: the first pull-option cases recorded shape refusal only

The first frozen revision of `pull.default-option`, `pull.alias-option` and
`pull.limit-option` encoded the attribute options as flat lists
(`(:person/age :default 0)`). The core refuses any non-vector pattern element
by shape alone, so the pinned `malformed pull pattern` refusals and the
reasons "not admitted until specified" described list-shape refusal, not
option support. The honored form is the nested vector
(`[:person/age :default 0]`), which the core accepts and applies. Review
found this; the cases were re-encoded as nested vectors and re-pinned to the
core's actual behavior (`:default` supplies the value, `:as` renames the key,
`:limit` truncates). The earlier digest `e129fd6a…9d43` is superseded. The
cases stay `pending` and `unverified`: Client pull acceptance is unmeasured.

## E11 — Adapter-executed refusals are pinned outside this harness

The conformance harness executes core `dao.space.query/q` only. Case
`pull.list-form-option` records that Datomic documents the list form of
pull attribute options (`(:person/age :default 0)`), that the core honors
only the vector form, and that the adapter refuses the list form with
`:unsupported`. The adapter refusal is pinned by
`dao.space.query.datomic-test`, not by the corpus harness. The case is
`pending` and `unverified`: whether the Client accepts the list form, the
vector form, or both is for the oracle to adjudicate. The corpus has no
adapter-executed status; this note is the minimal encoding.

## E12 — Phase 4a bounded-execution surface (`:timeout`, `max-steps`) is DaoSpace-specific

Phase 4a implements `:timeout` and the extension
`:dao.space.query.datomic/max-steps` in the adapter, so the earlier "refused
until the execution-bounds machinery lands" reasons are superseded. The
surface is a documented difference, not Client parity:

- `:timeout` is a cooperative deadline in milliseconds (a positive integer),
  minted at entry; expiry is observed time >= deadline; exhaustion raises
  `:timeout` or `:step-budget-exhausted` (`:cognitect.anomalies/interrupted`)
  and never returns a partial relation. Client timeout behavior is
  unmeasured.
- A call supplying either bound is admitted only over explicit materialized
  relation/fact-relation sources and views over them. Opened published
  indexes, deferred rows and unrecognized sources are refused
  `:unsupported` / `:bounded-source-unqualified` before any read. Checked
  published-index traversal is Phase 4b.
- Pinned in `dao.space.query.datomic-execution-test` and
  `dao.space.query.execution-test`, not executed by this harness. Case
  `bounds.published-source-refused` records the admission rule as `pending`
  (there is no Client counterpart to measure). Full timeout oracle cases
  remain Phase 5.

## E13 — Core accepts a relation slot that is neither tuples nor a delay of tuples

`realize-st` (and the earlier `realize-db-value!`) checks only that a source
is a map carrying one of the three source keys. A value such as
`{:dao.space.query/relation <delay of a non-sequence>}` yields an empty
result instead of a refusal. The condition predates Phase 4a and bounded
calls are unaffected (admission requires a vector). It is routed to the
Phase 2 core slice; no code changed here and no corpus case pins it.

## E14 — Syntax depth is bounded by a documented constant

Recursive validation, translation and pull parsing walk query syntax by host
recursion. The adapter therefore refuses native query syntax (the query map or
vector, its pull patterns, and rules passed at `%`) that nests collections
deeper than `dao.space.query.datomic/max-syntax-depth` (64), `:unsupported` /
`:syntax-depth-limit`, measured iteratively before anything recurses. String
queries are bounded by the same constant in the scanner. Source descriptor
chains are not syntax: they are walked iteratively with one accounted unit per
hop (admission and realization), so any depth is a structured exhaustion or
refusal. Not covered, and why that is acceptable here: (a) data inside `:args`
is opaque and not measured (content hashing of user data recurses with the
data and is the caller's input); (b) callers of core `dao.space.query/q` that
build deeply nested syntax natively are not depth-checked (the host stack
applies; the core makes no claim); (c) public `dao.space.query/rows` still
recurses over view chains, outside the query kernel.

## Not represented by the corpus

- JVM `Float` (32-bit), instants, UUIDs, byte arrays, URIs: no tag exists in
  the corpus DSL; add one only with a matching oracle loader.
- Non-ASCII text: the corpus file is pure ASCII so its text is its bytes.
- Transaction ids and history patterns (four/five positions): outside the
  initial accepted domain (`pattern.five-position-history-refused`).
- Datomic schema: DaoSpace is schema-free and the corpus records none. The
  oracle runner derives value type and cardinality from the fixture's
  attribute usage and takes reference attributes from its `:ref-attrs`
  configuration. That configuration, and the heuristic mapping of allocated
  entity ids back to logical ids, are unreviewed until a backend is selected.
