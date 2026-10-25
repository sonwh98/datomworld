# dao.space.query.datomic — Datomic Client Query Compatibility

Status: implementing. Phases 0, 1 and 4a are landed. Phase 4b slices
4b-1 (the pure stepped cursor), 4b-2 (the single-node reader and the
open-time descriptor) and 4b-3 (kernel integration) are implemented and
this qualification slice (4b-4) records their evidence: bounded published-
index execution is admitted for exactly the profile in *Phase 4b: checked
published-index traversal* below (a `current` view with no `as-of`
directly over a published value carrying a checked-read descriptor), and
every other published path stays refused. The Client oracle
qualification of this storage-execution work is Phase 5. No full
compatibility qualification is claimed. Individual oracle cases retain
their recorded provenance and verification status.

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
| `:timeout` | Documented difference; staged implementation | Expiry is observed time greater than or equal to deadline. Phase 4a qualifies only admitted materialized-source paths after integration tests. Published-index paths remain refused until Phase 4b. |
| `:dao.space.query.datomic/max-steps` | DaoSpace extension; staged implementation | Positive explicit work allowance, threaded through the whole invocation. The same Phase 4a source restrictions apply to step-only and timed calls. No ambient default. |
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
remaining work allowance, deadline data, phase and any explicit execution
frames. The evaluator uses a common state-passing kernel whose internal
operations return `[state result]`. Nested evaluation returns its updated
state to the caller; subsequent work receives that returned state.

The existing `::active-rules` mechanism is a precedent for explicit
lexical/path context, not for accumulated progress: it passes scope
downward but does not return consumed work. Rule-path scope must be
restored on return while invocation progress is retained. Branch-local
bindings, active-rule membership and execution allowances must not be
conflated.

Core `q` uses this same kernel with an explicit unbounded policy. The
adapter uses it with its supplied bounds. A separate legacy and bounded
Datalog evaluator is not permitted.

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

Deadline expiry is defined as `observed-now >= deadline`. The final
observation uses the same rule, so completion exactly at the deadline is
timeout.

For an integrated work operation, observe first, then charge, then
perform the operation only if the state remains live. Perform the
required post-operation observation before interpreting or delivering its
result. An existing terminal state is absorbing: subsequent operations
return that same state, perform no work and take no clock observations.

The last available work unit may execute. Completion with no remaining
units succeeds only when no further work is required and the final
deadline observation is live. A value returned by a read whose post-read
observation expires must not be interpreted or delivered.

**Checked-path calibration.** Checked published-index traversal adds
`:tree-step` work and, for each actual fetch, `:node-read` work to the
existing scan/candidate/pull accounting. Yielding and interpreting a
candidate therefore costs more than its query-layer candidate unit
alone. Seek descent, node validation (chunked, at most 32 keys per
transition), ascent and advancement can add further transitions. There
is no universal "two steps per datom" and no fixed conversion from Phase
4a allowances: applications must recalibrate `:max-steps` using
representative checked-path workloads. Each new scan starts without
reuse of the legacy restored-tree cache, so joins, pull projections and
special forms that rescan an index may repeat seek-path reads and their
work charges. Report these costs separately from result cardinality and
backend cache latency.

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

The Phase 4a implementation is authorized to rewrite the evaluator into
strict state-passing operations. Strict state passing does not require
exhaustive materialization of every intermediate relation. The common
kernel must preserve first-witness termination for existential evaluation
such as `not` and `not-join`, using explicit demand, checked folds or
execution frames.

Query-controlled loops must return progress, including loops that reject
every candidate or produce no binding. Lazy evaluator chains that capture
stale execution state are not permitted. Adapter preprocessing and final
output work participate in the same invocation state.

Existing selective index access and planning-never-forces behavior remain
regression requirements for unbounded calls. This rewrite does not
authorize an eager source drain or a second query semantics
implementation.

Phase 4a must make query-controlled, potentially unbounded evaluation
chains stack-safe through a trampoline or explicit execution frames. This
includes recursive rule expansion and continuation chains whose depth
grows with processed bindings or nested evaluation. Increasing the work
allowance must not replace structured exhaustion with host stack
overflow. A fixed rule-depth guard is not a substitute for this
requirement. Phase 3 tabling addresses recursive completeness and must
preserve this execution property. Opaque caller functions retain the
separately documented indivisible-operation limitation.

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

### Staged source qualification: Phase 4a and Phase 4b

The one-in-flight-storage-read guarantee is unchanged. Phase 4a does not
claim to establish it for published indexes.

Phase 4a may qualify bounded queries over explicitly materialized
relation and fact-relation values, and supported views whose complete
source chain terminates in such a materialized value. Their
query-controlled realization, current-state interpretation, index
construction and evaluation must participate in work accounting and
deadline observations.

Calls supplying either a timeout or a work cap must refuse a source
chain reaching an opened published index, deferred source rows or another
unqualified source implementation. Admission inspects descriptors without
forcing source contents and checks every declared source before source
realization. Cache residency does not change eligibility.

Refusal uses the adapter's `:unsupported` category, a minimal
`:bounded-source-unqualified` reason and the source variable. It must
perform zero source reads and must not open or close anything. The
adapter must not silently materialize a rejected source or fall back to
unchecked execution.

Unbounded calls retain existing source support through the common
evaluator. Explicit materialization performed by the caller before
invoking `q` is outside that query's bounds and must be described as
such.

Phase 4b owns the checked published-index traversal seam, specified in
the next subsection. Phase 5 cannot qualify bounded published-index
queries beyond the profile below.

| Source path | Unbounded calls | Bounded calls (Phase 4a) | Bounded calls (Phase 4b, implemented) |
|---|---|---|---|
| Materialized relation/fact-relation and supported materialized views | Existing supported semantics | Admitted | Unchanged |
| Exactly `current`, no `as-of`, directly over a published value carrying a structurally valid checked-read descriptor | Existing supported semantics | Refused before source reads | **Admitted**: checked cursor |
| Bare published value or bare published d5 relation | Existing supported semantics | Refused | Refused |
| History, `as-of`, or nested semantic views over published data | Existing supported semantics | Refused | Refused |
| Published value lacking the descriptor, or with an invalid one | Existing supported semantics | Refused | Refused |
| Arbitrary delayed rows, unrecognized source implementation, live stream | No new support granted | Refused | Refused |

"Admitted" does not mean the Client oracle has verified anything: it
records that the bounded path exists, is tested on all three hosts, and
holds the one-in-flight guarantee below.

### Phase 4b: checked published-index traversal

**Mechanism.** A pure, stepped, ascending cursor
(`dao.data.btree.cursor`) traverses the persisted B-tree node blobs and
issues explicit single-node read requests; the query layer interprets
its outcomes. Invocation-scoped `IStorage` wrapping is not the
correctness mechanism, and the borrowed restored tree is neither
traversed nor altered. Unbounded calls keep their existing traversal and
memoization.

- `(start descriptor comparator)` pins the comparator for the cursor's
  life; `(step cursor)` performs one local transition and returns
  `:continue`, `:need-node`, `:item` or `:done`; `(supply cursor
  response)` hands over the requested node. There is at most one pending
  request, a waiting cursor re-steps to the same request, and `supply`
  rejects a missing, mismatched or already-satisfied response. `start`
  performs no I/O and the cursor holds no storage handle, execution state
  or clock.
- Every transition does bounded work: validation advances at most 32 keys
  per step and a seek is a binary search. Malformed nodes are structured
  failures, never a silent empty or an endless traversal. Validation is
  limited to leaf/branch shape, equal key/address lengths with present
  addresses, keys strictly ordered under the pinned comparator, strictly
  decreasing levels (leaves are 0), and child-maximum equality with the
  parent separator under the comparator (comparison equality, never
  content identity). Occupancy, fill, balance and branching-factor key
  counts are builder policy and are **not** validated: roots,
  single-child spines and partial final chunks read fine.
- A single-node reader, `dao.data.btree.storage/read-node-blob`, performs
  exactly one hash-verifying `dao.jing/get`; `KVStorage/-restore` is never
  on the checked path.
- A local, nonpersistent descriptor
  `:dao.space.query/published-read {:version 1 :store :manifest-address
  :manifest}` is built by `published-db` and preserved by
  `open-published!` from the manifest already read at construction. It
  never appears in results or error data, and admission validates it
  structurally (never by identity) without reading storage.
- The query-side interpreter threads `[execution-state cursor
  accumulator]` in a constant-stack loop. Each transition observes and
  charges one `:tree-step`; each `:need-node` observes and charges one
  `:node-read`, then performs exactly one `read-node-blob` inside
  `execution/read-step`. A terminal state starts no read, and a node
  returned after the deadline passed is **discarded and never supplied**.
  A cursor or read failure is a `:source-failed` adapter error with the
  original as its cause.
- Index access is factored into a pure **range plan** (order selection,
  seek key, prefix test) and a source-specific producer; the in-memory and
  restored-tree producers are unchanged. Every published-source read
  reachable from bounded `q` goes through the checked producer: pattern
  clauses and joins, the unselective full EAVT fallback, finite pull
  (including `(pull ?e pattern)` in `:find`, nested and wildcard),
  `get-else` and `missing?`, reads inside negation, disjunction and rules,
  and repeated aggregate scans. A bounded realization never forces the
  published value's `:rows` delay.
- The checked path retains only the cursor's active path and current
  leaf: no invocation-wide node cache and no reuse of the borrowed tree's
  memoized nodes. A warmed legacy tree does not reduce its fetch count.
  The unbounded path's restored trees memoize through host-mutable
  references owned by the source implementation, not by query execution
  state; leaving that machinery unchanged while the checked path does not
  consult it introduces neither shared mutable query-control state nor a
  requirement to remove it. Installing clocks, allowances or progress in
  those references remains forbidden.

**Sentinels and the stopping rule.** DaoSpace range planning uses
canonical datom slot positions `[e a v t m]`, never physically reordered
to match a comparator's field priority. Under the nil-first comparison of
sentinel fields the lower seek keys are `[e nil nil nil nil]` (EAVT,
entity), `[nil a nil nil nil]` (AEVT, attribute), `[nil a v nil nil]`
(AVET, attribute and value) and `[nil nil v nil nil]` (VAET, value); a
full EAVT walk has no lower bound. Slot scans stop by the query layer's
comparator-based prefix test (`same-slot?`), continue across values that
compare equal but differ in content identity (`1`/`1.0`, `1.0M`/`1.00M`,
`0.0`/`-0.0`), and apply content-strict filtering after candidate
production. A generic upper key is never manufactured by replacing
unspecified fields with nil. The cursor namespace carries the same table
as documentation only and depends on no DaoSpace comparator or datom.

**One-in-flight guarantee (unchanged).** An actual query-controlled fetch
is one invocation of `read-node-blob`, containing one `dao.jing/get`. If
the deadline is reached during that call the call may finish, the
post-read observation marks timeout, the returned node is discarded, no
later fetch begins and no partial relation escapes. A backend cache hit
still counts as a fetch. This is an operation-count guarantee, not a
wall-time upper bound, and it does not cover packets, retries or physical
operations inside a content-store implementation.

**Adapter-profile boundary.** Refusing a clause that would need an eager
fallback (a relation scan of the published value) *before any read* is a
property of the **adapter profile**: the adapter pads database patterns
to three fixed positions and refuses rest and history patterns, so no
admitted adapter query can reach the fallback. The core guarantees only
that it **never falls through** to `read-datoms`, `force-relation` or a
legacy tree sequence: a runtime guard refuses the fallback with
`:checked-source-fallback`. A direct caller of `q-with-state` may
therefore see fetches made by earlier clauses before that guard trips.

**Phase of death.** A deadline that passes during a node fetch is
reported with phase `:read`, the phase recorded by the `read-step`
bracket; `:read` is part of the published phase set.

**Evidence recorded for this profile.** The pure cursor has three-host
tests against `bt/slice` and an explicit sorted-filter oracle, including
kind-tie fixtures (1/1.0, 1.0M/1.00M, 0.0/-0.0) at the seek and
prefix-stop boundaries for all four orders and occupancy-parity fixtures.
The integrated matrix (instrumented content stores, fake clocks, no
`with-redefs`) covers expiry before the first read, during fetch N, and at
the post-read observation, work exhaustion before a node read, shared
allowance across joins, negation, rules and several sources, parity with
unbounded and materialized answers, zero-read refusal of every excluded
path, and malformed-node, missing-segment and post-expiry cases. Bounded
cold point and two-clause queries read at most 8 and 15 nodes at
branching factor 4, beside the unbounded 4 and 15 regressions. Oracle
verification against a Datomic Client backend is Phase 5.

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

**0 → 1 → 4a → 4b → 2 → 3 → 5**

Phase 4a may land independently with its explicit source restrictions.
Phase 4b remains a required slice of the published-index backend
objective; it is not deferred into an unspecified Phase 5 task. The
remaining phases keep their existing acceptance criteria.

Phase 1 prioritizes representative finite pull and list-surface cases.
Phase 4 moves ahead of aggregate/rule expansion because backend query
bounds are essential to the web-app consumer. This changes scheduling,
not the accepted feature boundary.

| Phase | Ownership and files | Acceptance criteria | Implementation agent-hours |
|---|---|---|---:|
| 0 — Contract and fixtures | Architect: this document. Engineer: corpus, README/errata/provenance, fixture namespace, conformance harness, oracle runner and dev-only dependency alias. | Every contract point classified; no fabricated oracle evidence; fixtures load portably; disputed behavior has explicit oracle cases; pending cases reported honestly. | 3–5 |
| 1 — Adapter boundary and view workloads | Engineer: `src/cljc/dao/space/query/datomic.cljc`, `test/dao/space/query/datomic_test.cljc`, consumer fixtures and examples. | Both verified public arities; safe strings; argument/option validation; realized tuple/return-map output; pagination defaults/boundaries; finite pull profile validated; unsupported features refused; existing core result tests unchanged. | 4–7 |
| 4a — Common bounded evaluator | Engineer: `query.cljc`, `query/execution.cljc`, adapter integration, affected pull/realization helpers and tests. | One state-passing evaluator for bounded and unbounded calls; checked materialized-source execution; preserved existential demand and rule scope; adapter entry-to-return accounting; fail-closed source admission; deterministic tests on all three hosts; unchanged public core contract and downstream behavior. | 20–32 total, including 4–6 already spent; 16–26 remaining |
| 4b — Checked published-index traversal | Reviewed seam (`dao.data.btree.cursor`, `read-node-blob`, the published-read descriptor, the checked producer in `query.cljc`), then engineer implementation and portable read tests. **Status: 4b-1 through 4b-3 implemented; 4b-4 qualification recorded here, pending independent confirmation and architect sign-off.** | Per-fetch progress through root seek and subsequent traversal; one-in-flight guarantee on actual published indexes; no query state installed in borrowed trees; pull/special-form/fallback coverage; three-host counter evidence and lifecycle tests. Admitted profile: `current`, no `as-of`, directly over a descriptored published value; all else refused. | 20–32 implementation, plus 5–8 review |
| 2 — Deterministic language coverage | Engineer: `query.cljc`, aggregate/numeric tests and adapter tests. | Six specified aggregate capabilities added; arities and parameters preserved; empty/grouped/`:with` cases; abbreviated current-fact patterns; special-form edges; three-host numeric-domain agreement. | 6–10 |
| 3 — Rules and semantic conformance | Engineer: core rules and `test/dao/space/query_rules_test.cljc`; corpus expansion. | Required-bound heads; left/right/mutual finite positive recursion; cycles, alternatives and unbound entry calls; immutable tabling/fixed-point correction where necessary; unsupported recursion through negation refused; fresh-value work bounded by Phase 4; existing VM rules pass. | 8–14 |
| 5 — Qualification and integration | Engineer: complete conformance, counters, ownership and downstream tests. Architect: final matrix and status headers. | Supported cases verified; differences/refusals executable; read budgets hold; downstream callers pass; no remaining unverified claim inside the qualified exact domain; docs match implementation. Additionally requires evidence that Phase 4a restrictions are removed only for paths qualified by Phase 4b. | 7–11 |

Each phase must leave master testable. Land tests with their implementing
slice; proposed/pending cases must not make unrelated lanes fail or appear
as passing compatibility cases.

Phase 4a authorizes the common evaluator/state-passing rewrite but no
B-tree checked-read refactor. Phase 4b owns that separately reviewed
seam. Neither slice authorizes a persisted storage-format change,
query-aware backend, second evaluator, or weakened one-in-flight
guarantee.

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

The original 12–22-hour Phase 4 estimate is superseded by source
inspection. Phase 4a is estimated at 20–32 implementation agent-hours,
including 4–6 already spent and 16–26 remaining. Phase 4b adds a
provisional 12–20 hours, subject to its seam-design review. Combined
Phase 4 implementation is therefore 32–52 hours.

Phases 2 and 3 remain 6–10 and 8–14 implementation agent-hours
respectively; no new aggregate or recursive-completeness work is folded
into Phase 4a. Phase 5 increases to 7–11 hours for source-qualification,
traversal and regression evidence.

Remaining implementation from the Phase 4 STOP is 49–81 agent-hours.
Allow a further 8–14 agent-hours for independent review and remediation
across the remaining slices: 57–95 agent-hours remaining overall.
Already completed Phase 0/1 work and the 4–6 hours already spent on
Phase 4 are excluded from that remaining estimate.

These are revised engineering estimates, not measured model-throughput
guarantees. The Phase 4b range remains provisional until its
checked-traversal design is reviewed. Existing role-routing and
reviewer-independence requirements remain unchanged.

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
