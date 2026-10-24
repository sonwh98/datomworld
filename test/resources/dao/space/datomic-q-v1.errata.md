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
