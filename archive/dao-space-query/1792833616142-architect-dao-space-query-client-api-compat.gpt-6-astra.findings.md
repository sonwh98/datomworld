Completed-GMT: 2026-10-24 09:25:36 GMT
Completed-Local: 2026-10-24 16:25:36 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a16d38-d4fc-7253-8098-51e80bd692b9

**Recommendation:** add `dao.space.query.datomic/q` as a versioned compatibility adapter. Preserve `dao.space.query/q` and its result-value contract. Target a documented subset of Datomic Client query semantics, with explicit refusals outside that subset.

Estimated implementation effort: **44–76 agent-hours**, including independent review and remediation. An initial adapter milestone is **7–12 implementation agent-hours**, but must not be advertised as complete query-language compatibility.

This was a read-only investigation at `f6856bbc`. I made no file edits or network calls and did not rerun tests. The supplied 545-test result remains orchestrator evidence, not independently reproduced evidence.

**Corrections to the dispatch**

The local artifact `com.datomic/client-api:1.0.69` supplies inspectable source in `datomic/client/api.clj` and `datomic/client/api/impl.clj`. It changes the target substantially:

| Dispatch claim | Verified finding |
|---|---|
| Client `q` is `(q db arg-map)` | Public `q` supports `([arg-map])` and `([query & args])`. The db-first shape exists in the internal `Queryable` protocol. |
| `:ret :coll/:mapping` selects output | Not documented by this local public API. `:ret` is an **output key** when statistics are requested. Do not invent a mapping contract under the compatibility label. |
| Existing `q` returns a lazy query result | It returns a tagged result containing an already constructed set. `relation-result` uses `into #{}`. Index access is lazy; final result construction is eager. |
| `get-else` and `missing?` are missing | Both exist as index-aware special forms at [query.cljc:1273](/Users/sto/workspace/datomworld/src/cljc/dao/space/query.cljc:1273), with lazy/eager parity tests. |
| Bare symbols being refused is a Datomic incompatibility | Not established. The repository explicitly treats `?`-prefixed symbols as variables, consistent with its documented Datomic term model. Retain this rule. |
| Numeric example `1.0M` versus `1.0M` | The meaningful distinction is `1.0M` versus `1.00M`, alongside signed zero and numeric kind. |
| All distinct results retain content identity | Intermediate deduplication is content-aware, but the final host set can collapse content-distinct values. The source documents this limitation at [query.cljc:1597](/Users/sto/workspace/datomworld/src/cljc/dao/space/query.cljc:1597). |

Other supplied findings are substantially supported: map/sequential query normalization, four find shapes, return maps, `:with`, six aggregates, explicit function maps, multiple sources, input binding forms, negation/disjunction, and recursive rules exist. Required-bound rule heads explicitly throw; pull recursion is deferred.

However, feature presence is not proof of Datomic equivalence. Two additional gaps matter:

- Core patterns match **exact tuple arity**; Datomic database patterns permit omitted trailing positions.
- Pull is schema-free: cardinality, reference interpretation, wildcard behavior, and automatic `:db/id` inclusion are not generally Datomic semantics.

The recursive evaluator also needs differential testing. Its active-call cycle guard is not sufficient evidence of complete least-fixed-point behavior for every recursive rule shape.

**Decision 1 — adapter, with eager returns**

Use:

```clojure
(require '[dao.space.query.datomic :as d])

(d/q {:query '[:find ?e ?name
               :where [?e :person/name ?name]]
      :args [(query/current opened-index)]
      :offset 0
      :limit 100})

;; Also support the locally verified public convenience arity:
(d/q '[:find ?e :where [?e :person/name]] db-value)
```

The adapter owns public argument normalization, supported-language validation, Datomic-pattern translation, pagination, output representation, and error translation.

The core owns generic query interpretation and reusable language features. It must not learn Client option names, statistics envelopes, connection protocols, or database discovery.

Execution remains:

1. Validate and normalize the complete request.
2. Evaluate against caller-supplied immutable sources.
3. Complete grouping, aggregation and result deduplication.
4. Apply offset and limit to result rows.
5. Produce fully realized tuples or return maps.

This preserves selective published-index reads without promising that `:limit` bounds evaluation, memory, or segment fetches. No early termination is safe merely because enough intermediate bindings exist: aggregation, duplicates, and joins can change the final answer.

Do not add streaming or `qseq` in this project. Existing `collect` remains unchanged.

Existing callers needing regression protection include `yin.vm`, `yin.vm.ledger`, linker publication and semantic scanning, `yin.repl.query`, and `dao.space.schema`. The ledger call sites are now around lines 231–270, not the dispatched line 149. No caller migration is required.

**Decision 2 — accepted compatibility boundary**

Pin the initial contract to **Client API 1.0.69**, the locally verified version. A newer-version claim requires separate evidence.

| Classification | Accepted behavior |
|---|---|
| Exact API behavior within the supported subset | Argument-map and query-plus-inputs forms; ordered `:args`; map, vector/list, and EDN-string queries; relation results; supported return maps; offset; limit, including default `-1` and explicit `-1`. |
| Exact language behavior after conformance | Scalar/tuple/collection/relation input bindings; source-qualified patterns; supported predicates/functions; `:with`; deterministic aggregates; supported negation/disjunction and finite positive rules. |
| Documented differences | DaoSpace source values; numeric identity and arithmetic domain; unordered result enumeration; cooperative timeout; eager complete evaluation before pagination; schema-free finite pull where explicitly enabled. |
| Refused or excluded | Unverified `:ret` input modes; db-first public signature; Datomic connection/database objects; statistics options until separately implemented; arbitrary symbol resolution; unsupported pull and schema features. |

Important details:

- **Find shapes:** the core supports scalar, tuple and collection finds, but the inspected Client doc promises a collection of tuples. Phase 0 must establish the target’s acceptance/rejection behavior for these forms. Do not automatically expose Peer conveniences as Client compatibility.
- **Raw inputs:** ordinary `:in` data remains ordinary data. If the adapter accepts raw tuple collections as source inputs, wrap only positions declared as sources. Never reinterpret every vector/map in `:args` as a database.
- **Database patterns:** initially support logical current facts `[e a v]`. Normalize one- and two-position database patterns by appending blanks to three positions, preserving the indexed path. Do not translate them to rest patterns that unnecessarily force eager fallback.
- **History:** do not equate canonical `[e a v t m]` with Datomic `[e a v tx added]`. Transaction identity and metadata/boolean conversion need an explicit semantic view before four/five-position compatibility can be claimed.
- **Pagination:** no ordering guarantee and no promise that separate calls reproduce the same page membership. Test cardinality and membership, not one arbitrary Datomic traversal order.
- **Errors:** provide structured `ex-info` categories for malformed requests and unsupported features. Preserve causes; do not disguise storage corruption or closed-input errors as invalid queries.

**Timeout call:** implement cooperative cancellation, not an after-the-fact elapsed-time check. Until that phase lands, explicitly reject `:timeout`; never silently ignore it.

A pure interpreter cannot observe elapsed time itself. Introduce explicit, bounded execution steps and a host driver that supplies deadline observations. Parsing, source realization, joins, recursion, aggregation and output realization all need cancellation checkpoints. A synchronous external function or storage read can still block between checkpoints; that limitation must be documented. Hard preemption requires host isolation and is outside this plan.

**Decision 3 — functions and aggregates**

Keep `get-else` and `missing?` in the core’s index-aware special-form path. Do not duplicate them through adapter `:fns`. Add conformance cases for absent attributes, nil-like values, invalid arity, wrong source shape and multivalued attributes; existing presence does not establish all edge semantics.

Add these deterministic aggregate capabilities to the core:

- `distinct`
- `median`
- `variance`
- `stddev`
- Parameterized `(min n ?x)` and `(max n ?x)`

These are not `min-n`/`max-n` function names. Generalize aggregate parsing to retain parameters and validate arity; the current parser destructures only an aggregate symbol and one argument.

Keep existing `count`, `count-distinct`, `sum`, `min`, `max`, and `avg`. Verify empty inputs, grouping, repeated arguments, duplicate elimination, and `:with` multiplicity for all aggregates.

Numeric policy:

- Preserve CBOR content identity and existing numeric ordering.
- Do not normalize decimal scale, signed zero or numeric kind to imitate Datomic.
- Preserve explicit refusal of arithmetic on unsupported numeric carriers.
- Establish a cross-host supported arithmetic domain with fixtures, including overflow and non-finite inputs.
- Freeze variance convention, median behavior, result types and permitted floating-point tolerance from oracle evidence.
- Do not claim bit-identical floating arithmetic across hosts without proving it.

**Exclude `rand` and `sample` initially.** Ambient randomness would violate the state boundary. Supporting them later requires explicit entropy and its consumption semantics. Deterministic aggregate work should not quietly introduce a global RNG.

Also exclude fulltext, arbitrary custom aggregate resolution, and database-function lookup. `:fns` remains an explicit DaoSpace capability, not an automatic bridge to ambient JVM namespaces.

**Implementation phases and ownership**

All paths below are proposed future changes.

| Phase | Files and responsibility | Acceptance criteria | Agent-hours |
|---|---|---|---:|
| **0. Freeze the contract** | Architect: `docs/design/dao.space.query.datomic-compat.md`. Engineer: `test/resources/dao/space/datomic-q-v1.json`, companion README/errata, `test/dao/space/datomic_query_fixtures.cljc`; isolated oracle runner under `src/dev/`. | Version, provenance, supported domain, exclusions and every disputed contract point recorded. Unknown behavior remains marked unverified. Oracle regeneration is separate from tests. | 3–5 |
| **1. Adapter boundary** | Engineer: `src/cljc/dao/space/query/datomic.cljc`, `test/dao/space/query/datomic_test.cljc`; docs examples. | Both verified public arities; argument validation; safe EDN strings; tuple/return-map realization; pagination defaults and boundaries; loud unsupported-option errors. Existing core result-value tests unchanged. | 4–7 |
| **2. Deterministic language coverage** | Engineer: `src/cljc/dao/space/query.cljc`; new aggregate tests and adapter tests; extend numeric tests. | Parameterized aggregates; six additions above; empty/grouped/multiplicity cases; abbreviated current-fact patterns; special-form edge cases; unsupported constructs rejected before evaluation. Three-host agreement over the accepted numeric domain. | 6–10 |
| **3. Rules and semantic conformance** | Engineer: core rule parsing/evaluation and `test/dao/space/query_rules_test.cljc`; adapter validation; corpus expansion. | Required-bound heads supported. Finite positive recursion tested for left/right/mutual recursion, cycles, multiple definitions and unbound entry calls. Fix incompleteness using per-call immutable tabling/fixed-point evaluation where demonstrated. Unsupported recursion through negation refused. Existing VM rule workloads remain green. | 8–14 |
| **4. Cooperative timeout** | Engineer: generic execution machinery in `src/cljc/dao/space/query/execution.cljc`; core integration; adapter host driver; `test/dao/space/query_execution_test.cljc`. | Explicit execution state, bounded steps and deadline observations; no background task, global clock state, callback registration or partial success. Timeout during scans, empty-result searches, joins, rules and aggregation. Legacy `q` drives the same machinery to completion without changing its API. | 12–22 |
| **5. Release qualification** | Engineer: conformance runner and published-index tests; test-lane registration where necessary. Architect: status headers and final compatibility matrix. | All declared supported cases verified; exclusions tested; segment-read regressions absent; input ownership preserved on success/failure/timeout; downstream callers pass; documentation matches executable coverage. | 5–8 |

**Implementation subtotal: 38–66 agent-hours.** Independent review and remediation: **6–10 agent-hours**. **Total: 44–76 agent-hours.**

Sizing assumes one `claude-opus-5.5` implementation lane following the repository’s delegated-engineering workflow, with `gemini-4-argon` independent review and GPT architecture sign-off for boundary changes. Fable can review this GPT-authored design, but would not be an independent-family reviewer of an Opus implementation. Codex remains in architecture under the current roster.

Allow roughly **6–10 working days** with an eight-hour active implementation window, plus scheduling delays. These are engineering estimates, not measured model-throughput guarantees. Oracle availability, recursive-rule defects and execution-step refactoring dominate uncertainty.

The initial adapter milestone is phases 0–1. It can land earlier with explicit exclusions; full delivery requires all phases.

**Conformance strategy**

Follow the CBOR precedent in [cbor_conformance_test.cljc](/Users/sto/workspace/datomworld/test/dao/jing/cbor_conformance_test.cljc): frozen cases, independent evidence, provenance, immutable expectations, and explicit host exceptions.

Each fixture records:

- Stable case ID and contract point.
- Logical input data, query and arguments.
- Expected result or error category.
- Oracle version and evidence provenance.
- Classification: exact, intentional difference, unsupported, or unverified.
- Comparison rule, including unordered rows and numeric tolerances where appropriate.

Use small fixtures covering people/attributes, duplicate-producing joins, missing values, multivalued attributes, multiple sources, cyclic edges, rule alternatives, empty inputs, grouped aggregates, and numeric boundaries. Include negative cases for malformed strings/options, unsupported pull, wrong arity, and unresolved functions.

A shared logical fixture must have separate Datomic and DaoSpace loaders. Datomic entity IDs must be normalized through explicit fixture identities; never compare incidental allocated IDs or fabricate equivalent transaction IDs.

Use the actual Client API for Client behavior. A Peer-only oracle can inform query-language cases but cannot certify Client call shapes, paging, timeouts, or return restrictions. Local source inspection establishes the wrapper contract; it does not replace execution against a supported local Client backend. If that backend is unavailable, report the affected oracle cases as unverified and withhold the compatibility release claim.

Run each applicable corpus case against:

1. In-memory DaoSpace facts.
2. Explicit current views.
3. Opened published indexes, with segment-read counters.
4. JVM, Node and Dart.

Tests must not regenerate their expected answers. Pin corpus contents and audit changes separately.

Follow the repository gate ladder: focused JVM iteration, three-host subsystem checkpoint, changed/dependent tests before landing, and required full/slow gates at phase boundaries. Include `bb test:clj`, `bb test:cljs`, and `bb test:cljd` in release evidence. Pure query cases belong in the applicable jolt lane; socket/filesystem exclusions are not reasons to skip evaluator semantics.

**Invariant and portability analysis**

The charter names six invariants; the foundation currently also lists information preservation. Preserve all seven:

- No global registry, connection cache, mutable query cache, or ambient RNG.
- Execution progress, rule tables and deadlines belong to one invocation.
- No callback-based cancellation; execution steps and observations are explicit data.
- Client normalization, query interpretation, and storage execution remain separate.
- Sources remain separate values; no source identity appended to datoms.
- Pull traverses explicitly requested relations; no graph or reference schema inferred.
- No source writes or destructive transformations; pagination is an output projection.

Both entry points borrow inputs. Neither opens, closes, republishes, subscribes to streams, or creates durable state.

Use `.cljc` for semantic machinery. Safe EDN readers need CLJ/CLJS/CLJD branches and equivalent refusal tests; do not use evaluating readers or ambient tagged-literal handlers. Keep wall-clock access in the explicit host driver. Fixture numeric constructors must preserve canonical kinds rather than relying on lossy JSON numbers.

**Documentation decision**

Create `docs/design/dao.space.query.datomic-compat.md` as the authoritative versioned compatibility specification. A separate ADR would duplicate it at this stage: preserving the existing boundary does not reverse an architectural decision.

Update the status header of [dao.space.query.md](/Users/sto/workspace/datomworld/docs/design/dao.space.query.md) to distinguish:

- Existing core implementation.
- Proposed/in-progress/qualified compatibility adapter.
- Lazy index realization versus eager result construction.

Update `dao.space.index.md` only to clarify adapter borrowing, pagination costs and unchanged publication semantics. Do not imply an index-format migration.

**Explicit non-compatibility boundary**

These remain architectural exclusions under this plan:

- Native Datomic database/connection/entity objects, transaction services and transaction queues.
- Automatic namespace resolution, database-installed executable functions, hidden registries and ambient randomness.
- Datomic numeric identity where it conflicts with the ratified CBOR identity rules.
- Automatic equivalence between DaoSpace metadata/transaction coordinates and Datomic `tx`/`added`.
- Datomic ordering, transport chunking, physical execution plans, exact I/O statistics or hard cancellation of arbitrary blocking host work.

These are deferred features, not claims of impossibility:

- Fulltext, lookup refs and schema-dependent query/pull behavior.
- Recursive pull, complete pull options and general Datomic pull parity.
- `rand`, `sample`, custom aggregates, nested query functions and broader builtin coverage.
- `qseq`, query statistics and I/O-statistics envelopes.
- Unrestricted recursive Datalog beyond the tested supported class.

Finally, **db-first `q` and `:ret :mapping` are not compatibility gaps in the locally verified public API**. They should not consume implementation effort unless the owner identifies a different target contract.