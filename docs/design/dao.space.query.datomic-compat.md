# dao.space.query.datomic — Datomic Client Query Compatibility

Status: proposed specification; adapter direction accepted by the owner
(2026-10-24). Phase 0 contract freeze authored; engineering fixtures and
oracle execution pending. No adapter implementation or qualified
compatibility release is claimed.

Target: `com.datomic/client-api` **1.0.69**, public
`datomic.client.api/q`. This is a versioned, explicitly bounded
compatibility profile, not a replacement for the Datomic database system.

Lifecycle:

- **Proposed:** specification and intended domain recorded.
- **Implementing:** identified portions implemented, with per-case evidence.
- **Qualified:** every supported contract point has the required oracle,
  DaoSpace, portability and regression evidence; intentional differences
  and exclusions remain explicit.
- A version change or supported-domain expansion requires reviewed corpus
  changes and renewed qualification.

Related documents:

- [Architectural foundations](./datom.world.md)
- [Query core](./dao.space.query.md)
- [Published indexes](./dao.space.index.md)
- [dao.star](./dao.star.md), Mode 1
- [DaoStream remote](./dao.stream.remote.md)
- [Canonical CBOR](./dao.jing.cbor.md)
- [Build and test gates](../agents/build-n-test.md)
- [Engineering roles and reviewer independence](../agents/team.md)

## Purpose and consumer

The owner's purpose for this parallel track is:

> this is a parallel track to dao.star but i want this to be ready to build web based apps using dao.star with dao.space as a datomic like database backend

The primary consumer is a web application's backend participating in
`dao.star` Mode 1. The JVM is its primary execution host. Query semantics
remain portable across CLJ, CLJS and CLJD; portability does not move query
execution into the Mode 1 browser.

The browser submits ordinary HTTP requests. The HTTP boundary deposits
uninterpreted request events. The router classifies and forwards them;
the session driver observes them and explicitly steps the continuation.
Application code queries caller-selected DaoSpace values, derives view
data, and projects HTML or signal patches onto the egress stream. The
boundary sends the already constructed HTTP/SSE payload.

The browser does not call `q` over a socket, receive a database handle, or
choose backend source capabilities. Neither the HTTP boundary nor the
query adapter routes sessions or interprets Datastar signals.

### Session facts, signals and observation order

Mode 1 session drivers append occurrence and successor facts to the
session-facts medium. A transition fact must receive `:dao.stream/ok`
before UI naming its occurrence is projected. The router reads ingress,
then drains its directly held session-facts and lineage-lease cursors to
`blocked`, then classifies requests. This ordering is essential: published
facts and a router's observation of them are different events.

The query adapter must not replace that classification procedure with a
query against a potentially stale published manifest. Session routing,
lineage custody, leases, subscriptions and their observation order remain
owned by the `dao.star` composition.

Signals are immutable, unverified client claims decoded by an explicit
interpreter. They become application facts only through application
interpretation. Passing a signal as a query parameter does not make it
authority to select a source, invoke a function, or access another session.

For application reads, the backend chooses an immutable current-view
source for the operation and passes session or application scope explicitly
through `:in`. `q` neither discovers a session nor subscribes to live data.
An application requiring read-after-write visibility must supply a source
known to contain the accepted write; an older published manifest does not
become fresh because the query uses `current`.

Re-projection of a stale input or subscription does not resume the
continuation. Queries used by application projection must preserve that
property: they are reads, and source selection remains explicit.

### Representative workloads and priorities

The initial workload corpus must include:

1. **Entity views:** finite pull projections for a detail panel, edit form,
   or related-entity view, including missing attributes and multivalued
   attributes.
2. **List surfaces:** filtered current-fact queries using `:offset` and
   `:limit`, including duplicates introduced by joins and large offsets.
3. **Session-scoped reads:** a current-view query with explicit session or
   owner parameters, including isolation between two logical sessions.
4. **View summaries:** deterministic counts and grouped aggregates.
5. **Bounded failure:** a query that exhausts its deadline or work budget
   without returning partial view data or changing session facts.

These needs put adapter ergonomics, finite pull behavior, pagination and
bounded execution ahead of advanced recursive-rule coverage.

Offset/limit is an unordered query slice, not stable ordered pagination.
Applications needing a stable business order must establish an explicit
ordering/paging policy outside this profile. Sorting only an arbitrary
already-truncated page does not establish a global order.

A query timeout is also not a session or HTTP-exchange lease. Applications
must size query bounds within their response and renewal arrangements.
Synchronous `q` does not secretly yield to the session driver or renew a
lease.

## Evidence and provenance

The design baseline is `f6856bbc`. Historical line references below refer
to that baseline and identify evidence, not permanent source coordinates.

Evidence classes are separate from compatibility classifications:

| Evidence | Meaning |
|---|---|
| `local-client-source` | Inspected source in the locally installed Client API 1.0.69 jar. |
| `local-dao-source` | Inspected DaoSpace implementation or existing tests. |
| `oracle-executed` | Recorded execution through the actual public Client API against an identified supported backend. |
| `unverified` | Behavior not yet established by the necessary evidence. |

The first two classes are available. Client oracle execution is pending.
Agreement among DaoSpace implementations alone cannot establish Datomic
compatibility.

Inspected Client source establishes:

- Public `q` has argument-map and query-plus-arguments arities.
- `:query` accepts map, sequential/list and string representations.
- `:args` supplies query inputs.
- `:offset`, `:limit` and `:timeout` are documented options.
- Query limit defaults to `-1`, meaning unlimited.
- The documented result is a collection of tuples.
- `:ret` is an output key in statistics envelopes, not a documented
  `:coll`/`:mapping` input selector.
- Db-first `q` belongs to the internal `Queryable` protocol, not this
  public entry point.
- Errors use `ex-info` with Cognitect anomaly data.

The artifact is locally installed but is not a declared project
dependency. `deps.edn` currently declares `com.datomic/peer` 1.0.7364.
Phase 0 engineering must add Client API 1.0.69 at development/oracle scope.

Source inspection does not establish all option validation, find-shape
restrictions, aggregate edge cases, return-map behavior or error details.
Those remain explicitly unverified until the oracle cases run.

## Architecture and ownership

Add `dao.space.query.datomic/q` in:

`src/cljc/dao/space/query/datomic.cljc`

Preserve `dao.space.query/q`, its existing signature, result-value shape,
and `collect`.

The adapter owns:

- Public call-shape normalization.
- Safe string reading and supported-profile validation.
- Translation of Datomic database-pattern syntax.
- Client pagination and output representation.
- Translation of generic execution exhaustion into query exceptions.
- The host driver and explicit clock capability used for timed calls.

The core owns reusable query semantics, generic execution state, rule
evaluation and aggregate implementation. Client option names, HTTP
concepts, sessions, connection protocols and statistics envelopes must
not enter core evaluation.

Storage remains an opaque content substrate. Generic checked traversal
may expose individual read operations to the execution driver, but
storage must not interpret query clauses or implement Client pagination.

Both entry points borrow sources:

- No opening, closing, publishing, subscribing or durable writes.
- No global database registry or connection cache.
- Caller ownership survives success, failure and timeout.
- Successful output contains projected values, not source handles.
- Separate sources remain separate input values, never added datom slots.

The ordinary execution path is:

1. Validate and normalize.
2. Evaluate the supplied immutable sources.
3. Complete projection, grouping, aggregation and deduplication.
4. Apply offset and limit to result rows.
5. Return fully realized tuples or supported return maps.

The result set is already built inside core `q`; lazy published-index
access must not be confused with lazy result production. `collect`
applies the conventional find/return-map shapes.

No `qseq`, streaming-result API or automatic caller migration is included.

## Supported domain

“Exact” below means the intended qualified behavior within the declared
domain. It does not mean oracle verification has already occurred.

| Surface | Classification | Contract and current evidence |
|---|---|---|
| `(q arg-map)` and `(q query & args)` | Exact target | Public Client source verified. Runtime cases pending. |
| Db-first public `(q db arg-map)` | Refused | Not the verified public API. |
| Ordered `:args`; default `:in $` | Exact target | Wrapper/documentation verified; binding cases require oracle evidence. |
| Map, vector/list and string queries | Exact target | Safe EDN only; no evaluation or ambient tagged readers. Reader edge cases pending. |
| Relation find, realized tuple collection | Exact target | Client documentation verified; adapter returns ordinary realized data. |
| Scalar, tuple and collection finds | Unverified | Core supports them. Client acceptance/rejection must be measured; do not infer it from Peer behavior. |
| `:keys`, `:syms`, `:strs` | Exact target for verified forms | Core support verified; Client restrictions and combinations require oracle execution. |
| `:offset`, `:limit` | Exact logical slicing target | Limit default `-1` verified. Detailed validation and interactions pending. Enumeration order is unspecified. |
| `:ret :coll` / `:ret :mapping` | Refused | No such documented input contract in the pinned source. |
| `:io-context`, `:query-stats` | Refused/deferred | Real Client options, but their statistics are outside this profile. |
| DaoSpace source values | Documented difference | Explicit current views or logical fact relations; no native Datomic db objects. |
| Raw maps/vectors as database sources | Refused in v1 | Wrap sources explicitly. Ordinary scalar/tuple/collection/relation `:in` values remain ordinary data. |
| One-/two-/three-position database patterns | Exact target over current d3 facts | Adapter pads omitted positions with blanks to three slots. Core exact-arity semantics remain unchanged. |
| Four-/five-position Datomic history patterns | Refused/deferred | DaoSpace `[e a v t m]` is not automatically Datomic `[e a v tx added]`. |
| Multiple sources and input binding forms | Exact target | Core presence verified; differential fixtures required. |
| Negation/disjunction | Exact target for declared supported forms | Binding, scope and error behavior require conformance. |
| Required-bound rule heads | Exact target, implementation pending | Core currently refuses them. |
| Finite positive recursive rules | Exact target after conformance | Current active-call guard is not a completeness proof. Work budget provides an execution backstop. |
| Numeric identity/arithmetic | Documented difference | Preserve the ratified CBOR identity policy and explicit unsupported-carrier refusals. |
| `get-else`, `missing?` | Exact target on supported fact sources | Already index-aware special forms; edge semantics pending oracle cases. |
| Deterministic aggregates | Exact target within declared numeric domain | Existing six plus additions specified below; edge cases pending. |
| Finite pull | Documented-difference profile | Useful for views, but schema-free cardinality/reference/wildcard behavior is not general Datomic pull parity. |
| `:timeout` | Documented difference | Cooperative exception contract below; refused until execution machinery lands. |
| Eager evaluation before pagination | Documented difference | Limit does not bound total query work, memory or storage reads. |
| Ambient function resolution, fulltext, randomness | Refused/deferred | Boundaries specified below. |

### Input and output rules

Source positions are determined by `:in`, not by inspecting whether an
argument happens to be a map or vector. Never wrap arbitrary application
arguments as databases.

For current facts, abbreviated patterns become three fixed positions.
Do not translate them into rest patterns that leave the selective
three-position index path.

The adapter must reject malformed or unsupported constructs before
evaluation whenever they can be recognized statically. Unknown options
must not be silently ignored.

The adapter policy is offset zero when absent, limit `-1` when absent,
nonnegative integer offsets, and limits that are either nonnegative
integers or `-1`. Timeout, when supplied, is a positive integer number of
milliseconds. Until oracle verification establishes matching validation,
these are declared adapter policies, not claims of exact Client error
behavior.

Pagination applies after final row formation. `:limit 0` returns no rows
but is not promised to skip validation or evaluation. No stable ordering
or cross-call page membership is promised.

### Finite pull for application views

Finite pull is a priority workload, not an expansion to general
schema-aware Datomic pull.

The admitted profile uses explicit finite patterns and current fact
sources. Its contract includes the existing DaoSpace distinctions:

- Forward attributes with one value produce a scalar; multiple values
  produce a collection.
- Nested patterns explicitly assert navigation through entity identities.
- Missing attributes are omitted unless an admitted default is specified.
- DaoSpace includes `:db/id` in projected entity maps.
- Recursive markers and unsupported option combinations are refused.

The fixture inventory must separately identify flat attributes,
wildcards, defaults, reverse attributes, finite nesting, aliases and
limits. Only validated combinations enter the admitted grammar.
Source-qualified pull, dynamic patterns and other unverified forms must
not slip through permissive parsing.

Applications that require schema-dependent cardinality or reference
behavior need a later explicit semantic profile. The adapter does not
infer schema or turn numeric values into references automatically.

### Numeric limitation

The `relation-result` docstring at
`src/cljc/dao/space/query.cljc:1605–1611` documents a current limitation:
intermediate deduplication uses content identity, but the final host set
can collapse content-distinct rows that compare and hash equally on a
host. Examples include JVM decimal-scale distinctions and signed zeros
on affected hosts.

The adapter must disclose this behavior and must not claim lossless
content-distinct results for those cases. Wrapping the already collapsed
set in a vector cannot recover them. Fixtures must distinguish this
limitation from intended numeric identity.

This work does not change persisted numeric identity, canonical bytes,
index formats, or the existing core result representation. The exact
compatibility domain excludes numeric combinations whose behavior is
classified as a documented difference.

## Functions and aggregates

Retain `get-else` and `missing?` in the core's index-aware special-form
path. They are not new adapter functions and must not be duplicated
through `:fns`.

Verify absent values, false/nil-like values, multivalued attributes, source
shape, binding shape and arity. Preserve selective published-index access.

Retain:

- `count`
- `count-distinct`
- `sum`
- `min`
- `max`
- `avg`

Add:

- `distinct`
- `median`
- `variance`
- `stddev`
- Parameterized `(min n ?x)` and `(max n ?x)`

Aggregate parsing must preserve parameters and validate arity. These
additions belong to generic core semantics, not adapter-supplied function
closures.

Freeze empty-input results, grouping, duplicate elimination, `:with`
multiplicity, variance convention, median behavior, numeric result types
and ordering/tie behavior through fixtures. Arithmetic carriers that the
host cannot safely evaluate remain explicit refusals. Cross-host
floating-point tolerances must be justified per case; do not use a
blanket tolerance to conceal type or algorithm differences.

`rand`, `sample`, fulltext, database-function lookup and arbitrary custom
aggregate resolution are excluded. Ambient randomness and global symbol
resolution are not acceptable shortcuts. A future random aggregate
requires explicit entropy ownership and consumption semantics.

Explicit caller function capabilities remain a DaoSpace extension.
Nothing in this profile grants a browser the ability to supply executable
functions or resolve arbitrary symbols.

## Normative execution bounds

This section incorporates review findings F1, F2, F4, F5, F6, F9 and F10.

### State and driver

Use generic execution machinery in:

`src/cljc/dao/space/query/execution.cljc`

One invocation owns one immutable execution state containing progress,
remaining work allowance, deadline data, phase and continuation state.
Nested evaluation threads and returns that state through explicit `ctx`
plumbing, following the existing `::active-rules` precedent.

Passing the same initial state independently to multiple branches is not
threading: each branch must receive the state returned by prior work.
Negation, disjunction, rule calls, pull, grouping and source realization
must not reset allowances.

No dynamic variable, atom, namespace-level clock default, background
task, future or callback registration may carry invocation progress.
A lazy closure capturing an old `ctx` must not restart accounting when
forced.

The execution driver explicitly interprets progress/read requests and
supplies observations. A synchronous zero-argument `now-fn` may be a
driver-local capability: the driver calls it at specified checkpoints.
It is never registered for later invocation and never stored in global
state. Core evaluation consumes time readings/deadlines as data and does
not resolve a clock.

The adapter mints the absolute deadline at entry, before parsing and
source realization. Use a monotonic source where available, expressed in
a common duration unit. Wall-clock fallback must be documented as such;
clock adjustment cannot be silently represented as monotonic behavior.

Legacy `q` uses the same semantic machinery with an explicit unbounded,
never-expiring policy. No Client option name enters the core.

For web-app calls, the composition supplies a finite work allowance in
addition to a deadline. The adapter may expose this as the explicitly
non-Client extension `:dao.space.query.datomic/max-steps`, translated to
generic execution data. Its omission means no step cap; it does not
enable an ambient default.

### Step unit and nonproductive work

A **candidate step** is one attempted candidate-binding production during
the forced evaluation pass. It consumes one unit whether the candidate
is retained or rejected. Count produced bindings separately if useful;
successful output count is not a work budget.

Charge additional explicit work units for operations that can otherwise
continue without attempting a binding: advancing a scanned source tuple,
entering/expanding a rule, processing an aggregate input, advancing pull
traversal, and progressing incremental parsing or output construction.
The implementation must publish the operation-to-unit table in its tests.

This refines the candidate-binding recommendation so that empty-result
scans and fresh-value recursion cannot bypass the bound. Work units are
logical accounting, not a CPU instruction count or elapsed-time estimate.

Every potentially unbounded loop must either consume units or be
decomposed into bounded execution steps. Opaque host functions, parsing
primitives or storage calls must be identified as indivisible operations;
calling a long monolithic operation “one step” does not establish
preemption.

Observe at entry and exit of parsing, source realization, planning,
joins, nested evaluation, recursion, aggregation, pull and output
realization, as well as at the work checkpoints above. Before performing
a unit, refuse if no allowance remains. Exact-boundary completion succeeds
only if all required work is complete and the final deadline observation
has not expired.

Phase 3 tabling addresses recursive completeness. The Phase 4 work
budget is the termination backstop for accepted recursive execution,
including paths that keep generating fresh values rather than revisiting
an active call key. It cannot preempt an externally supplied function
that never returns.

### Forcing discipline

Constructing a lazy sequence performs no accountable evaluation by
itself. The driver must control the forced pass, including source
traversal, candidate generation, projection, deduplication, aggregation,
pull and final materialization.

Do not place the only checks around `q`, `collect`, a clause, or the
construction of a range sequence. Chunked realization must not perform an
unchecked batch of work.

Planning must continue to avoid forcing deferred source rows merely to
estimate clause cost.

A completed result must retain no deferred work capable of reading a
borrowed source after return. Intermediate lazy values must not escape
with reusable stale budget state.

### Storage checkpoints and overshoot

The required storage-work guarantee is:

**Once a deadline passes during a storage read, expiry is observed when
that read returns and before another query-controlled storage read starts.
At most the one in-flight storage read is allowed beyond the deadline.**

Checkpoint before and after each actual node fetch, including fetches
inside a lazy range traversal. Also checkpoint around range entry,
advancement and exit.

Checks only before and after `select-datoms` or a whole range scan cannot
establish this guarantee: one scan may fault many B-tree nodes. The
baseline range-selection seam at `query.cljc:1042–1044` therefore needs
checked traversal down to each actual read. Use generic explicit read
steps or an equivalent invocation-scoped traversal mechanism; do not
mutate a borrowed shared tree to install query state.

This is an operation-count bound, not a millisecond upper bound. A single
synchronous backend read may block beyond the deadline, and so may a
host function. The adapter cannot interrupt such a call. On return it
checks before further work. CPU-only work has the corresponding
next-checkpoint guarantee, subject to the documented indivisible
operation.

Qualification must demonstrate the one-read guarantee on the supported
path. If the existing lazy traversal cannot expose that granularity,
implement the generic seam or explicitly leave the timed path
unqualified. Do not replace the guarantee with checks at clause boundaries.

### Exhaustion and exceptions

The generic engine may represent exhaustion as a terminal data outcome.
The public compatibility adapter raises structured `ex-info`; it never
returns a partial successful relation.

This deliberately differs from repository mechanisms that report
exhaustion as `:dao.stream/blocked`, parked state, or `:lost`/`:deadline`.
It is not an invariant breach: the query API is an interpreter boundary
with an exception contract. Streams retain their own data-outcome
semantics, and the adapter neither rewrites nor consumes their history
destructively.

The design can compose with a future stepped remote driver, but this
profile adds no query RPC or remote-execution protocol.

### Deterministic testing

Semantic tests inject a fake driver that expires after a specified number
of observations. Test immediately-before, exact-boundary and
immediately-after outcomes, plus work exhaustion independently of time.

Required cases include:

- Empty-result and rejected-candidate scans.
- Multiple node fetches before a useful binding.
- Joins, negation, disjunction and nested rules.
- Fresh-value recursive expansion.
- Pull traversal and aggregate realization.
- Parsing/source-realization/output boundaries.
- Success and failure without opening or closing inputs.
- No read after observed expiry.

Only one generously margined real-clock smoke test per host is allowed
for the clock integration. No sub-second real-time timeout tests belong
in the lanes. Sleeps and scheduler timing must not decide semantic
correctness.

## Errors and context retention

Adapter exceptions carry a stable
`:dao.space.query.datomic/error` discriminator and a
`:cognitect.anomalies/category`. This is the adapter taxonomy; exact
Datomic messages and complete ex-data identity are not promised.

| Error | Anomaly category | Meaning |
|---|---|---|
| `:invalid-request` | `:cognitect.anomalies/incorrect` | Malformed query, arguments, binding or option. |
| `:unsupported` | `:cognitect.anomalies/unsupported` | Recognized feature outside this profile. |
| `:timeout` | `:cognitect.anomalies/interrupted` | Observed deadline expiry. |
| `:step-budget-exhausted` | `:cognitect.anomalies/interrupted` | Explicit invocation work allowance exhausted. |
| `:evaluation-failed` | `:cognitect.anomalies/fault` | Unexpected evaluator failure; preserve the cause. |
| `:source-failed` | Existing meaningful category, otherwise `:cognitect.anomalies/fault` | Storage/read failure; preserve the cause and its distinction from invalid input. |

Attach only minimal projected context: phase, clause position or bounded
clause fragment, variable, consumed step count and relevant option name.
Do not attach `::dbs`, complete bindings, input collections, restored trees,
continuations or store handles.

Fix owned error producers that currently embed bindings, including the
baseline `eval-not-join` and `resolve-special-arg` paths. Wrapping such an
exception while retaining it as a cause does not remove the retained
binding; the producer must be corrected.

Preserve genuine underlying causes rather than replacing storage
corruption or backend failures with generic invalid-query errors.
Third-party causes are not under this library's control and may contain
their own data. The adapter must not copy that data into its context, and
application HTTP error projection must not serialize cause chains or raw
exception maps to clients.

## Sizing and read observability

Pagination is an output bound only. A query with `:limit 20` can still
visit every relevant source tuple, construct a large join, build all
groups and evaluate pull for every matching entity before returning
twenty rows.

The unselective three-fixed-position path falls back to
`(seq (:eavt idx))` at baseline `query.cljc:526`. This walks the restored
tree and resolves current state; it is not an indexed bounded page fetch.

Capacity planning must account for:

- Source tuples visited, including historical entries resolved by a
  current view.
- Join intermediates and deduplication keys.
- Aggregate/group state.
- Total projected result bytes, including nested pull output.
- Concurrent invocation count.

Peak memory can exceed final result size substantially. Neither
`:timeout` nor a step cap is a heap quota. Do not publish an unsupported
universal row-count safety threshold.

Prefer selective entity/attribute/value patterns, explicit session scope,
finite pull patterns and appropriately sized source snapshots. Measure
representative cold and warm workloads before choosing application
concurrency and response bounds.

Portable read-budget tests must use an explicitly supplied wrapper store
or an instrumented `IStorage` seam. The existing no-op
`IStorage/-accessed` at
`src/cljc/dao/data/btree/storage.cljc:68` is a possible access-observation
seam, but an access event must not be mislabeled as an actual backend
fetch. Count actual restores/gets separately where needed.

The harness must distinguish manifest reads, node fetches and cache
accesses, and run on JVM, Node and Dart. Do not depend on `with-redefs` or
skip Dart counting. Instrumentation belongs to the caller/test composition;
it must not add global counters or durable query state.

## Conformance corpus and oracle

Use one frozen corpus named `datomic-q-v1`.

Proposed files:

- `test/resources/dao/space/datomic-q-v1.json`
- `test/resources/dao/space/datomic-q-v1.README.md`
- `test/resources/dao/space/datomic-q-v1.errata.md`
- `test/resources/dao/space/datomic-q-v1.provenance.edn`
- `test/dao/space/datomic_query_fixtures.cljc`
- `test/dao/space/datomic_query_conformance_test.cljc`
- `src/dev/dao/space/datomic_query_oracle.clj`

The JSON corpus uses an explicit data representation for symbols,
keywords, lists, numeric kinds and queries. Do not pass rich numbers
through ordinary JSON numeric decoding and lose their identity.

Each case records:

- Stable ID and contract point.
- Logical fixture, query, input bindings and options.
- Expected result/error, or an explicit pending expectation.
- Compatibility classification.
- Evidence class, artifact/backend version and oracle provenance.
- Comparison rule: unordered rows, identity normalization, or justified
  numeric tolerance.
- Host applicability and any reviewed limitation.

An unverified expectation is not a passing conformance assertion.
Invented expected answers must not be attributed to Datomic.

### Dual loaders

One logical fixture has independent Datomic and DaoSpace loaders.
Datomic schema and transaction setup belong to its loader. DaoSpace
explicit fact/current-view construction belongs to its loader.

Normalize allocated entity IDs through fixture identity mappings,
including IDs embedded in pull results and references. Do not normalize
away semantic differences such as cardinality or missing values.
Transaction IDs require separate explicit semantics and are outside the
initial accepted history domain.

### Oracle

Execute public `datomic.client.api/q` against an identified real Client
backend. Client API availability alone is not a backend.

Record API version, backend kind/version, fixture identity, query/options,
normalization procedure and observed result/error. Use an explicit
disposable fixture database. A Peer-only runner may supply additional
language evidence but cannot certify Client behavior.

The oracle runner emits candidate observations to a separate output.
It must never rewrite frozen expected values as part of a test run.
Corpus promotion requires review.

If the backend cannot run, preserve the unverified status and report the
missing evidence. Do not qualify the release using wrapper source,
mock responses or DaoSpace-generated answers.

### Coverage and gates

The corpus covers API forms, safe string reading, options, find shapes,
return maps, input bindings, source patterns, joins, special forms,
negation/disjunction, aggregates, rules, finite pull, numeric differences
and explicit refusals. Include the web-app workloads described above.

Run supported cases against in-memory fact sources, explicit current
views and opened published indexes, on CLJ, CLJS and CLJD. Pair semantic
checks with portable segment-read budgets and ownership tests.

Follow the repository gate ladder:

1. G0: focused JVM iteration.
2. G1: three-host subsystem checkpoint.
3. G2: changed/dependent tests before landing.
4. G3: mandatory master/wide-change gate.
5. G4: required full/slow validation at phase boundaries and release.

Release evidence includes `bb test:clj`, `bb test:cljs`,
`bb test:cljd`, applicable changed/dependent gates, and required full/slow
runs. Applicable pure query cases also run in the jolt lane. Socket or
filesystem exclusions do not excuse skipping pure evaluator behavior.

Tests must not regenerate their own oracle or corpus digest. Maintain a
reviewed corpus digest and explicit errata, following the CBOR conformance
precedent.

## Implementation sequence and ownership

Retain phase IDs from the initial plan for traceability. Execute in this
order:

**0 → 1 → 4 → 2 → 3 → 5**

Phase 1 prioritizes representative finite pull and list-surface cases.
Phase 4 moves ahead of aggregate/rule expansion because backend query
bounds are essential to the web-app consumer. This changes scheduling,
not the accepted feature boundary.

| Phase | Ownership and files | Acceptance criteria | Implementation agent-hours |
|---|---|---|---:|
| 0 — Contract and fixtures | Architect: this document. Engineer: corpus, README/errata/provenance, fixture namespace, conformance harness, oracle runner and dev-only dependency alias. | Every contract point classified; no fabricated oracle evidence; fixtures load portably; disputed behavior has explicit oracle cases; pending cases reported honestly. | 3–5 |
| 1 — Adapter boundary and view workloads | Engineer: `src/cljc/dao/space/query/datomic.cljc`, `test/dao/space/query/datomic_test.cljc`, consumer fixtures and examples. | Both verified public arities; safe strings; argument/option validation; realized tuple/return-map output; pagination defaults/boundaries; finite pull profile validated; unsupported features refused; existing core result tests unchanged. | 4–7 |
| 4 — Bounded execution | Engineer: `src/cljc/dao/space/query/execution.cljc`, core integration, adapter driver, execution tests, and the minimal generic traversal seam needed for read checkpoints. | One threaded invocation state; normative unit accounting; checked forced pass; per-read guarantee; deterministic expiry tests; one clock smoke per host; minimal error context; no input ownership change; legacy API unchanged. | 12–22 |
| 2 — Deterministic language coverage | Engineer: `query.cljc`, aggregate/numeric tests and adapter tests. | Six specified aggregate capabilities added; arities and parameters preserved; empty/grouped/`:with` cases; abbreviated current-fact patterns; special-form edges; three-host numeric-domain agreement. | 6–10 |
| 3 — Rules and semantic conformance | Engineer: core rules and `test/dao/space/query_rules_test.cljc`; corpus expansion. | Required-bound heads; left/right/mutual finite positive recursion; cycles, alternatives and unbound entry calls; immutable tabling/fixed-point correction where necessary; unsupported recursion through negation refused; fresh-value work bounded by Phase 4; existing VM rules pass. | 8–14 |
| 5 — Qualification and integration | Engineer: complete conformance, counters, ownership and downstream tests. Architect: final matrix and status headers. | Supported cases verified; differences/refusals executable; read budgets hold; downstream callers pass; no remaining unverified claim inside the qualified exact domain; docs match implementation. | 5–8 |

Each phase must leave master testable. Land tests with their implementing
slice; proposed/pending cases must not make unrelated lanes fail or appear
as passing compatibility cases.

Phase 4 may change only generic traversal machinery needed for the stated
guarantee. It does not authorize a storage-format redesign or query-aware
backend. That seam requires architecture review before landing.

### Downstream regression gates

Protect:

- `yin.vm` query-based extraction and rule workloads.
- `yin.vm.ledger`.
- Linker publication and semantic scanning.
- `yin.repl.query` and its bounded-answer behavior.
- `dao.space.schema`.
- `dao.space.dht`, whose baseline `dht.cljc:1498` applies core `q` over a
  current view of a loaded published index.
- `dao.space.transact` realization paths using `match` and `current`,
  including baseline lines 60, 80, 282, 304, 334 and 365.

The new adapter must not be required by legacy callers. Shared execution
or realization changes must still pass those callers' existing tests.

A Mode 1 consumer test should verify explicit session parameters,
current-source selection and query failure without session advancement.
The established dao.star integration gates remain authoritative for
fact-before-UI ordering; query tests must not substitute a mock proof for
the actual router/driver protocol.

### Effort and routing

Implementation subtotal remains **38–66 agent-hours**.
Independent review and remediation add **6–10 agent-hours**.
Total remains **44–76 agent-hours**.

Scheduling alone does not change the estimate. The detailed checked-read
seam and recursive completeness work are the largest risks and should be
resolved early within their phase ranges. Re-estimate if investigation
shows a broader traversal refactor is necessary.

Assume one `claude-opus-5.5` engineering lane, independent
`gemini-4-argon` implementation review, and GPT architecture sign-off for
boundary changes. Fable may independently review this GPT-authored
design, but is not a different-family reviewer of Opus implementation.
Codex remains in architecture under the current roster.

At eight active implementation hours per working day, allow roughly
6–10 working days plus scheduling/backend-availability delays. These are
engineering estimates, not measured model throughput guarantees.

Phases 0–1 are a 7–12-hour initial milestone with explicit exclusions.
They are not web-app backend qualification while execution bounds or
oracle evidence remain absent.

## Invariants and portability

This design preserves the six charter invariants plus information
preservation:

- No hidden global state: capabilities, source choice, budgets and clocks
  are explicit.
- No implicit control flow: the driver advances execution; data arrivals
  invoke nothing.
- No callbacks: clock/read capabilities are synchronously called at
  explicit steps, not registered for control inversion.
- No shared mutable invocation state: progress is threaded and owned by
  one call.
- No layer collapse: Client syntax belongs to the adapter; generic query
  semantics to the core; storage execution below it.
- No assumed graph: navigation is explicitly requested by query or pull
  patterns.
- No destruction of source information: pagination and projection derive
  output; no datoms, stream history or canonical source values are
  rewritten.

The JVM is the primary application host, not the sole semantic reference.
Use `.cljc` for portable semantics and isolate host clock and EDN-reader
differences behind explicit driver/reader seams.

No evaluating reader, ambient tag registry, global clock default or
host-only numeric shortcut may enter the portable path. Host exceptions
must be documented per corpus case and may not silently reduce the
qualified domain.

## Exclusions and deferrals

Architectural exclusions under this profile:

- Native Datomic connection, database and entity objects.
- Datomic transaction services and transaction queues.
- Ambient namespace resolution or database-installed executable functions.
- Hidden registries and ambient randomness.
- Datomic numeric identity where it conflicts with ratified CBOR identity.
- Automatic equivalence between DaoSpace metadata/transaction coordinates
  and Datomic `tx`/`added`.
- Datomic physical plans, transport chunking, traversal ordering or exact
  I/O statistics.
- Hard preemption of arbitrary synchronous blocking work.

Deferred features:

- Fulltext and lookup refs.
- Schema-dependent query/pull semantics and full Datomic pull parity.
- Recursive pull and unsupported finite-pull options/combinations.
- `rand`, `sample`, arbitrary custom aggregates and nested query functions.
- Broader builtin coverage.
- `qseq` and streaming results.
- Query-statistics and I/O-statistics envelopes.
- Recursive Datalog outside the tested accepted class.

The consumer story changes priorities but does not remove these
boundaries. Finite pull is promoted as a documented-difference application
profile; it is not relabeled as general Datomic equivalence.

Db-first public `q` and `:ret :mapping` are not missing features of the
locally verified target. A different target contract requires new
evidence and a revised profile.

## Documentation completion

Update `dao.space.query.md` to distinguish the implemented core from this
adapter's lifecycle and to state clearly that index realization is lazy
while result construction is eager.

Update `dao.space.index.md` only for borrowing, sizing and read-checkpoint
clarifications. Publication semantics and persisted formats remain
unchanged.

This document is the versioned compatibility decision and specification.
No separate ADR is required unless implementation proposes reversing an
existing architectural decision.

Qualification requires an evidence matrix, not only a green test count.
The final status must identify the exact supported profile, unresolved
differences, oracle/backend versions and host gates.
