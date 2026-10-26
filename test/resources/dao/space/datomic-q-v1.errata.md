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

**RESOLVED in Phase 2.** The core now refuses statically, before any
evaluation, a `:find`, `:with` or aggregate-argument variable that neither
`:in` nor any `:where` clause can bind (a variable merely tested inside a
`not` does not count). Case `find.unbound-var` graduated from `pending` to an
executable refusal (message contains "not bound"); `find.unbound-var-in-aggregate`
and `find.in-bound-var` pin the aggregate and the `:in` sides. The check is
conservative: a variable that appears only as a function argument is not
rejected statically and still fails at run time as an unbound-variable error.

## E4 — A trailing map argument is accepted as options without validation

The core treats one extra trailing argument as an options map and ignores
unknown keys (it even accepts a database value there). Unknown options must
not be silently ignored. Case `opts.unknown-option-refused` is `pending`.

## E5 — Aggregate parameters are dropped

**RESOLVED in Phase 2.** The aggregate parser preserves parameters and
validates arity; `distinct`, `median`, `variance`, `stddev` and parameterized
`(min n ?x)` / `(max n ?x)` are implemented and their cases are executable
DaoSpace-side pins (oracle comparison still pending, as for every case). The
conventions they freeze, and what they do not claim, are E15.

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
(`dao.jing.cbor/float64`). Phase 0 verified only the JVM. Phase 2 adds the
corpus comparison rule `numeric-value` (numerics agree when they compare equal
in exact numeric order, so an integral double equals the integer on a host
that cannot tell them apart) and `agg.avg` now lists every host with it.
Cases listing `cljs` and `cljd` declare host applicability that the
three-host gates exercise.

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
- A call supplying either bound is admitted over explicit materialized
  relation/fact-relation sources and views over them (Phase 4a) and,
  since Phase 4b, over exactly a `current` view with no `as-of` directly
  over a published value carrying a structurally valid checked-read
  descriptor, read through the checked cursor. Every other published shape
  (a bare published value, history, `as-of`, nested views, descriptor-less
  values), deferred rows and unrecognized sources are refused
  `:unsupported` / `:bounded-source-unqualified` before any read.
- Pinned in `dao.space.query.datomic-execution-test`,
  `dao.space.query.execution-test` and (the published profile)
  `dao.space.query.published-bounded-test`, not executed by this harness. Case
  `bounds.published-source-refused` records the admission rule as `pending`
  (there is no Client counterpart to measure). Full timeout oracle cases
  remain Phase 5.

## E13 — Core accepts a relation slot that is neither tuples nor a delay of tuples

`realize-st` (and the earlier `realize-db-value!`) checks only that a source
is a map carrying one of the three source keys. A value such as
`{:dao.space.query/relation <delay of a non-sequence>}` yields an empty
result instead of a refusal. The condition predates Phase 4a and bounded
calls are unaffected (admission requires a vector). **RESOLVED in Phase 2.**
A relation slot must be nil, a sequential collection, a set, or a delay of
one: anything else (including a delay of a non-sequence, checked when it is
forced) is refused with "relation source must be a sequence of tuples"
instead of reading as empty. Pinned in `dao.space.query-aggregates-test`; no
corpus case, because the harness builds sources only through the fixture
DSL.

## E16 — Phase 3 rule semantics: tabling, required-bound heads, negation

Frozen DaoSpace-side behavior (every case is `oracle` pending; Client rule
behavior is unmeasured):

- **Least fixed point.** Positive recursion is evaluated by an immutable
  naive fixed point per call key: a repeated call (same rule, same argument
  content) reads the head tuples tabled so far and records the hit, and its
  leader re-runs the pass until a pass finds nothing new. Left, right,
  both-sided and mutual recursion, cycles, several definitions and a fully
  unbound entry call are complete. The earlier active-call guard returned
  nothing for a repeated key and lost answers under left recursion (it was
  not a completeness proof). A rule that never re-enters itself still costs
  one pass. Kind-distinct numerics are distinct call keys (content identity).
- **Required-bound heads** `(r [?x ?y] ?z)`: one leading vector of symbols
  names the variables a call must already have bound; the call is flat
  `(r 2 3 ?z)`. A required position that is unbound at the call (a variable
  with no binding, or `_`) throws "Rule required-bound variable is not
  bound at the call" naming the variable, evaluated with the bindings at the
  call (a later clause does not rescue it). Through the adapter this surfaces
  as the generic `:evaluation-failed` path, deliberately: the error is
  binding-dependent and not statically decidable. It joins the Phase 5 oracle
  measurement list. A vector inside a call is
  refused ("rule call arguments are flat"); a vector anywhere but the head's
  leading position is a malformed head. Datomic's own diagnostics are
  unmeasured.
- **Recursion through negation** (a rule that, directly or through other
  rules, calls inside `not` / `not-join` a rule from which it is reachable,
  itself included) is refused before any evaluation: core ex-info with
  reason `:recursion-through-negation`, adapter `:unsupported`. Negating a
  recursive rule from outside its cycle is stratified and allowed.
- **Divergence.** Fresh-value recursion has no fixed point; the Phase 4 work
  budget is the backstop and a bounded call exhausts structurally (adapter:
  `:step-budget-exhausted`). An unbounded call on a divergent rule does not
  terminate, as before.
- **Not claimed.** Cost: the naive iteration re-runs whole passes and can be
  far from optimal on deep recursion (no semi-naive delta). First-witness
  demand is preserved: negation over a recursive rule stops at its first
  tuple.

## E15 — Phase 2 aggregate conventions and their limits

Frozen DaoSpace-side conventions (Client behavior is unmeasured; every case
is `oracle` pending):

- Aggregates fold the content-distinct rows projected to the `:find`,
  `:with` and aggregate-argument variables, so duplicates vanish unless
  `:with` distinguishes them; `distinct` returns a set.
- `median`: odd count -> the middle element itself (its kind kept); even
  count -> the double mean of the two middle elements. `variance`:
  POPULATION variance (divide by n); a single value gives 0.0; `stddev` is its
  square root. `(min n ?x)` -> the n smallest ascending, `(max n ?x)` -> the n
  largest descending, duplicates kept, n above the count returns everything.
- Order statistics sort by exact numeric order across kinds with the owner's
  tie rule (an exact operand before a float64, then the shorter canonical
  encoding), so tied kinds order identically on every host; non-numeric
  operands are refused ("requires numeric operands"), never coerced.
- Float results are asserted for EXACT equality with no tolerance: values are
  folded as doubles in the fixed sorted order, and `+ - * /` and `sqrt` are
  correctly rounded IEEE-754 operations on every host. This is claimed only
  for the integer inputs the cases use, not for arbitrary floats.
- The kind of an INTEGRAL double result (7.0) is not host-portable: a
  JavaScript number cannot be told from the integer 7 without a carrier, so
  the corpus compares such results by value (`numeric-value`); the JVM kind
  is pinned in `dao.space.query-aggregates-test`. The tie ORDER of kinds is
  likewise pinned there, with `cbor/content=`, not in this harness.
- A `distinct` set collapses elements the host treats as `=`: on the JVM
  `1.0M` and `1.00M` are one member (the E1 limitation applied to a set).
- Numeric carriers (decimals, ratios, float64 carriers) have no host
  arithmetic on JavaScript and Dart: `variance`, `stddev` and an even-count
  `median` refuse explicitly ("does not accept a numeric carrier",
  `agg.variance-carrier-refused`, hosts `cljs` and `cljd`). The JVM evaluates
  BigDecimal as a host number. Order statistics (odd `median`, `min`/`max`
  with n) never do arithmetic and work with carriers everywhere. A float64
  is a carrier on JavaScript only; a Dart double and a JVM Double are native
  numbers. Decimals are carriers on both JavaScript and Dart. So an
  even-count `median` over tied kinds is evaluated on `clj` and `cljd`
  (`agg.median-tied-kinds`), refused explicitly on `cljs`
  (`agg.median-tied-kinds-carrier-refused`), and the odd-count
  `agg.median-tied-kinds-odd` (no arithmetic) runs on every host.
- `rand` and `sample` remain excluded, at both layers: the core throws
  "Unknown aggregate in :find", and the Datomic adapter refuses them first as
  `:unsupported` ("randomized aggregates are excluded from the profile").
- `(max n ?x)` tie order: among values that tie numerically the order is the
  reverse of the ascending order, so a float64 precedes the exact operand
  (`[2.0 1.0 1]`, where `(min n ?x)` gives `[1 1.0 2.0]`).
- An aggregate argument must be a query variable: `(count [1 2])` and other
  non-variable arguments are refused ("aggregate argument must be a query
  variable"). Earlier phases parsed them and projected nil at grouping; the
  stricter refusal was chosen over an explicit laxness note.

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
