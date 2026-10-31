Completed-GMT: 2026-10-25 12:26:10 GMT
Completed-Local: 2026-10-25 19:26:10 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a16d38-d4fc-7253-8098-51e80bd692b9

**Decision: combine mechanisms (a) and (b): a pure stepped cursor over persisted B-tree node blobs, issuing explicit single-node read requests. Reject invocation-scoped `IStorage` wrapping as the correctness mechanism.**

The bounded path will use immutable cursor state and the existing invocation state. It will neither traverse nor alter the borrowed restored tree. Unbounded calls retain their current traversal and memoization.

This is a proposed design for independent review before implementation. No files were edited or tests run.

----

## Phase 4b — checked published-index traversal

### Status and scope

Status: proposed seam design, following landed Phase 4a. Independent architecture review is required before implementation dispatch.

Phase 4b initially admits **an explicit `current` view, with no `as-of` bound, directly over a published value carrying the new checked-read descriptor**. It covers all index-reading operations reachable from supported adapter queries over that source.

It does not admit every possible wrapper around a published value. History, `as-of`, nested semantic views and deferred-row fallback remain refused until separately implemented and qualified.

This narrows the initial implementation target without weakening the one-in-flight-read guarantee.

### 1. Mechanism and ownership

Add a generic namespace:

```text
src/cljc/dao/data/btree/cursor.cljc
```

It traverses the existing persisted node representation:

```clojure
;; Leaf
{:keys [...]}

;; Branch: keys are child maxima, not additional result elements.
{:level n
 :keys [...]
 :addresses [...]}
```

These shapes are already defined by `dao.data.btree/node->blob`. The cursor must not introduce another storage format.

The cursor knows:

- Root address and declared tree metadata.
- Range bounds and inclusivity.
- Caller-supplied comparator.
- Node layout and traversal position.

It does **not** know:

- Datoms, query clauses, source variables or Client options.
- Deadlines, execution allowances or clocks.
- DaoJing handles, network transports or database ownership.

The query layer interprets cursor outcomes. On a read request it uses the supplied content-store capability through `execution/read-step`, then returns the updated invocation state before resuming traversal.

This is one Datalog evaluator with two source-access implementations, not two query evaluators.

### 2. Open-time descriptor

Today, `published-db` reads and validates the manifest, constructs restored trees, and retains deferred EAVT rows. It does not retain a checked-read descriptor. `open-published!` also reconstructs the returned map, so it must explicitly preserve the new field.

Add this **local, nonpersistent** field to values constructed by both functions:

```clojure
:dao.space.query/published-read
{:version 1
 :store content-store-handle
 :manifest-address manifest-address
 :manifest validated-manifest}
```

The manifest has the existing shape:

```clojure
{:indexes {:eavt root-address
           :aevt root-address
           :avet root-address
           :vaet root-address}
 :count n
 :branching-factor b}
```

Requirements:

- Reuse the manifest already read by the constructor. No extra manifest read.
- Do not change the serializable published coordinate or persisted manifest.
- `published-db` continues to borrow its handle.
- `open-published!` retains its existing ownership and close behavior.
- The descriptor grants no new authority; it contains an existing local capability.
- Never place this descriptor in results or exception data.
- Admission validates the descriptor structurally without reading storage.
- Old or manually assembled published values lacking this descriptor remain usable unbounded, but are refused for bounded execution.
- Do not recover missing descriptor information by inspecting or forcing restored trees.

The descriptor is local source implementation data, not a portable query result or new storage protocol.

### 3. Exact cursor interface

Provide these functions:

```clojure
(start descriptor)
(step cursor comparator)
(supply cursor response)
```

Input to `start`:

```clojure
{:root address-or-nil
 :count nonnegative-integer
 :branching-factor positive-integer
 :lower nil-or-{:key lower-key :inclusive? boolean}
 :upper nil-or-{:key upper-key :inclusive? boolean}}
```

A `nil` bound means unbounded. A map containing `:key nil` can represent a genuine nil key bound if a generic caller requires one.

Phase 4b needs ascending traversal only. Do not add reverse traversal or change `slice`/`rslice` in this slice.

`start` performs no I/O and returns immutable cursor state. `step` performs one local traversal transition and returns exactly one of:

```clojure
{:status :continue
 :cursor next-cursor}

{:status :need-node
 :address address
 :cursor waiting-cursor}

{:status :item
 :value stored-key
 :cursor next-cursor}

{:status :done
 :cursor terminal-cursor}
```

Supply a fetched node with:

```clojure
(supply waiting-cursor
        {:address requested-address
         :node decoded-node-blob})
```

`supply` returns a cursor ready for validation and further stepping. It must reject:

- A response when no node is pending.
- A response for a different address.
- A second response to an already satisfied request.

There is at most one pending request per cursor. Repeatedly stepping a waiting cursor returns the same request without progressing.

A cursor contains explicit local state equivalent to:

```clojure
{:phase :root-or-seek-or-validate-or-yield-or-advance-or-done
 :path [...]
 :leaf nil-or-leaf-frame
 :pending nil-or-request
 :bounds ...
 :tree-metadata ...}
```

Internal field names may be namespace-qualified. The outcome and response shapes above are the public seam.

Path frames contain the immutable branch blob, its address, the selected child position, and the position needed for subsequent advancement. They contain no storage handle, execution state or clock.

### 4. Traversal algorithm

Implement the existing B-tree ordering semantics:

1. **Root:** request the root blob unless the validated tree is empty.
2. **Seek:** choose the first child whose maximum can satisfy the lower bound.
3. **Descent:** request one selected child at a time.
4. **Leaf:** locate the first qualifying key and yield keys individually.
5. **Advance:** ascend explicit frames, select the next child, and descend its leftmost path through individual read requests.
6. **Upper bound:** stop before emitting a key outside the bound.
7. **Full walk:** absent bounds traverse all leaves in order using the same mechanism.

Do not call `resident-root`, `node-child`, `slice`, `seq` on a `BTSet`, or any function that can restore implicitly.

No transition may recursively descend through multiple fetched nodes. Long ascents and node validation must also progress through explicit transitions rather than hiding an unbounded loop inside `step`.

Validate supplied node structure before using it:

- Leaf versus branch shape.
- Vector fields and matching branch key/address lengths.
- Node occupancy against the existing branching-factor rules.
- Positive branch level and decreasing child levels.
- Ordered keys under the supplied comparator.
- Parent child-maximum consistency once the child is available.
- Required child addresses.

Validation must not fetch sibling nodes merely to validate a selected path. Whole-tree correctness is not established by a selective read.

Malformed nodes produce structured failures, not silent empty results or endless traversal. The cursor remains storage-address-type agnostic; DaoJing address validation belongs below it.

Property tests against existing B-tree traversal are mandatory because branch keys represent child maxima.

### 5. Fetch interpreter and execution accounting

Add a single-node blob reader in `dao.data.btree.storage`, for example:

```clojure
(read-node-blob content-store address)
```

It performs exactly **one** `jing/get`, returning the decoded value or throwing a missing-segment error. It must not traverse children, hydrate descendants, open a handle or retry through a separate traversal.

`jing/get` already hash-verifies stored bytes and decodes the value. Do not bypass that path or substitute the stale optional-verification description in the storage namespace docstring.

The query-side cursor interpreter threads:

```clojure
[execution-state cursor accumulator]
```

For each transition:

1. Observe and charge one generic traversal unit.
2. If terminal, stop without advancing the cursor.
3. Call the pure cursor transition.
4. Observe before interpreting its outcome.
5. Process the outcome through the existing kernel’s explicit control flow.

For `:need-node`:

1. Observe and charge one generic node-read unit.
2. If live, call `execution/read-step` around exactly one `read-node-blob`.
3. If its post-read observation expires, discard the returned value.
4. Only while live, call `supply` and continue through the driver.

Add these unit names to the published execution table:

```clojure
:tree-step 1
:node-read 1
```

They are generic work units, not Client options. Candidate, scan and pull units remain separate: fetching a node does not pay for all datoms it contains.

The existing execution namespace continues to own only generic state, accounting and read bracketing. It must not require DaoJing, B-tree comparators or query syntax.

The query-side interpreter must compose with `bounce`/`bind` and `run-comp`, or use a constant-stack checked local loop whose returned state is threaded back into that kernel. It must not invoke a second recursive evaluator or capture stale state in a lazy sequence.

### 6. Query integration

Factor index access into two operations:

- A pure **range plan**: choose EAVT/AEVT/AVET/VAET, seek key and stopping predicate.
- A source-specific **candidate producer**.

Keep existing unbounded production through restored trees. The bounded published producer uses the cursor.

Preserve the current selection priorities and comparator behavior:

- Bound entity → EAVT.
- Bound attribute and value → AVET.
- Bound value → VAET.
- Bound attribute → AEVT.
- No selective bound → full EAVT walk.

A range stop based on numeric ordering must retain the existing kind-loose comparison behavior. Apply content-strict filtering afterward; do not stop at a content-distinct numeric value that still compares equal in index order.

Some query ranges use a prefix stopping predicate rather than a complete generic upper key. In that case the cursor yields raw keys until the query layer sees the first key outside the prefix and stops requesting further work. Boundary-leaf reads remain legitimate.

Route **every** published-source read reachable from bounded `q` through this producer:

- Pattern clauses and joins.
- The unselective three-position fallback.
- Finite pull, including nested projections and wildcard reads admitted by the adapter.
- `get-else` and `missing?`.
- Reads inside negation, disjunction and rules.
- Repeated scans during aggregate-producing queries.

Do not change current-state interpretation: retractions, transaction ordering, conflicting d5 rows and content identity retain their existing semantics.

The bounded realization must never force the published value’s `:rows` delay. It should carry an explicit checked-index source representation instead of a delayed row relation that ordinary fallback code might force.

If a clause requires an unimplemented relation fallback, refuse it. Do not fall through to `read-datoms`, `force-relation`, or a legacy tree sequence.

Planning must recognize the checked source without traversing it.

### 7. Memoization and warm-cache behavior

For the first Phase 4b implementation, **retain only the cursor’s active path and current leaf**. Do not add an invocation-wide node cache or reuse the borrowed tree’s memoized nodes.

Consequences:

- Advancing within a resident cursor leaf is a local step, not a storage read.
- Ascending through retained cursor frames requires no storage read.
- A new scan may fetch the same node again.
- A previously warmed legacy `BTSet` does not reduce this path’s fetch count.
- Backend-internal caching may make `jing/get` faster, but does not remove the query-visible read operation.
- Admission does not depend on cache residency.
- Cursor memory is proportional to the active path and retained node sizes, excluding query result/intermediate state.

This intentionally avoids a new cache policy, mutable lookup structure, source-identity problem or unbounded invocation cache. Cross-scan memoization can be designed later with separate ownership and sizing evidence.

Unbounded calls continue to benefit from existing B-tree memoization. Their traversal and performance are not changed.

### 8. Meaning of the overshoot guarantee

An **actual query-controlled fetch** is one invocation of the single-node reader, containing one `jing/get`.

The guarantee covers these calls, not individual network packets, backend retries or physical disk operations internal to a content-store implementation.

If the deadline is reached during that call:

- The call may finish.
- The post-read observation must mark timeout.
- The returned blob must not be supplied to the cursor.
- No later query-controlled fetch may begin.
- No partial successful query result may escape.

A backend cache hit still counts as a fetch because the query invoked `jing/get`. Reusing an active cursor frame does not count as a fetch, but still consumes traversal work and observes the deadline.

As before, this is an operation-count guarantee, not a wall-time upper bound. One synchronous read, including verification/decoding, can exceed the deadline.

When a read throws, preserve its failure as a source failure. Do not swallow corruption or missing-segment errors to manufacture a timeout result.

### 9. Admission changes

Use one source-classification policy shared by adapter admission and bounded core realization; do not maintain contradictory recognition logic.

The following become eligible after their tests pass:

| Source/path | Phase 4b bounded behavior |
|---|---|
| Existing materialized relation/fact relation and supported materialized views | Unchanged |
| Exactly `current`, no `as-of`, directly over a published value with checked descriptor | Admitted |
| EAVT/AEVT/AVET/VAET selective scans of that current source | Checked cursor |
| Unselective three-position scan of that current source | Checked full EAVT cursor |
| Supported finite pull and special forms over that source | Same checked cursor |
| Multiple declared sources, each independently eligible | Admitted; one invocation allowance |
| Bare published d5 relation | Remains refused in the bounded adapter profile |
| History, `as-of`, or nested semantic views over published data | Remain refused |
| Published value lacking the checked descriptor | Remains refused |
| Arbitrary delayed rows, unrecognized source implementation or live stream | Remains refused |
| Clause requiring unchecked eager/deferred-row fallback | Refused before evaluation |

All declared sources must pass admission before any query-controlled fetch, including unused sources. A mixed eligible/ineligible query must not read the eligible source first.

The published value’s legacy row delay may exist; its presence does not disqualify the recognized checked source. It must remain unused. Arbitrary delayed relation values remain ineligible.

Open-time manifest reading occurs before `q` and remains the caller’s operation. The query deadline is not retroactively applied to opening. No constructor is called from bounded query evaluation.

### 10. Instrumentation contract

Use an explicit test content-store wrapper passed into the normal published-value constructor. Wrap its `:get-bytes-fn` to record:

```clojure
{:event :fetch-start :ordinal n :address address}
{:event :fetch-return :ordinal n :address address}
;; or
{:event :fetch-error :ordinal n :address address}
```

The test driver independently records observation order and advances its fake clock during a selected fetch.

Requirements:

- Test-local counters are allowed; production invocation progress remains immutable.
- Record constructor manifest reads separately from query node reads.
- Do not use `with-redefs` or skip Dart counting.
- Distinguish cursor local transitions from backend reads.
- Instrument close/write calls to prove their absence during queries.
- Instrument returned execution state to prove allowance consumption across fetches and scans.

`IStorage/-accessed` is a cache-reuse notification, not a reliable actual-fetch counter. It is not used as the correctness seam or the primary Phase 4b proof counter.

### 11. Required tests

**Pure cursor, all three hosts**

- Empty and single-leaf trees.
- Multi-level trees with small branching factors.
- Seek before the minimum, at a key, between keys and beyond the maximum.
- Inclusive/exclusive bounds, empty ranges and full walks.
- Leaf transitions, sibling subtrees and ascent across several levels.
- Exact ordered equivalence with existing `bt/slice` over persisted fixtures.
- Response-address mismatch, unexpected supply and repeated waiting steps.
- Missing/corrupt/malformed nodes and inconsistent levels.
- No implicit node reads from `start`, `step` or `supply`.

**Integrated query, all three hosts**

For each access path, select fetch ordinals covering root restoration, initial descent and later range advancement:

- Expiry before first read: zero fetches.
- Expiry during fetch N: no fetch N+1.
- Expiry exactly at the post-read observation: returned blob discarded.
- Exhaustion before a node-read unit: zero corresponding fetch.
- Repeated scans and nested evaluation share the same allowance.
- Cached cursor-local advancement still observes expiry.
- Previously warmed legacy trees do not bypass the checked path.

Run these against:

- Every covered index order.
- Full EAVT fallback.
- Two-clause joins.
- Pull, nested finite pull and wildcard projection.
- `get-else`/`missing?`, including absence.
- Negation/rules that read the source.
- Multiple published sources.

Add a post-expiry test whose returned node would fail validation if supplied; timeout must occur without interpreting that node.

**Semantics and lifecycle**

- Bounded/unbounded/materialized answer parity for the admitted domain.
- Retractions, conflicting d5 rows and kind-strict numeric matches.
- Zero-read refusal for every excluded source/path and mixed-source query.
- No forcing of the legacy row delay.
- No opens, closes, writes or publication during success, failure or timeout.
- Missing/corrupt segment causes preserved.
- A caller can continue using the borrowed source after query timeout.
- Bounds and first-witness outcomes stop future cursor work.

**Performance regression**

Keep the existing unbounded `≤4` and `≤15` read-budget tests unchanged.

Add portable bounded equivalents using explicit wrappers, with constructor manifest reads distinguished. The existing cold point and two-clause fixtures should remain within those budgets; cursor code must not fetch the whole tree to seek.

Do not claim warm-cache equivalence between bounded and unbounded calls. Report repeated-scan costs explicitly.

### 12. File ownership and implementation sequence

| Slice | Files | Acceptance | Agent-hours |
|---|---|---|---:|
| 4b-0: independent design review | Proposed seam and contract amendment | Review state/read protocol, admission fence, cursor ordering and lifetime proof | 2–3 review |
| 4b-1: pure cursor | `src/cljc/dao/data/btree/cursor.cljc`; `test/dao/data/btree/cursor_test.cljc` | Three-host cursor/property tests; no I/O dependencies or changes to legacy traversal | 6–9 |
| 4b-2: descriptor and single-node reader | `query.cljc`; `dao/data/btree/storage.cljc`; constructor/storage tests | Existing constructor reads unchanged; descriptor retained; exactly one verified get per reader call | 2–4 |
| 4b-3: kernel integration | `query.cljc`; `query/execution.cljc`; `query/datomic.cljc` | Checked candidate producer and every admitted read path; centralized admission; shared progress; no fallback escape | 6–9 |
| 4b-4: qualification and docs | Portable published-execution tests; conformance notes; compatibility/index/B-tree docs | Fault-injection proof, lifecycle checks, parity and read budgets; downstream gates | 6–10 |

**Implementation: 20–32 agent-hours.** Independent design and implementation review/remediation: **5–8 agent-hours**. **Total: 25–40 agent-hours.**

This supersedes the provisional 12–20-hour implementation estimate. The additional work is the reusable pure cursor, explicit admission fencing and three-host proof coverage.

Recommend **GLM-5.3 in the stream-engineer role** for independent design review: it holds the F-findings context and is independent of this GPT-authored design. Use an independent-family reviewer for the subsequent Opus implementation, with architecture sign-off on the completed seam.

Do not dispatch implementation before the design review resolves any blocking findings.

Land each implementation slice with its own tests while retaining refusal until integration qualifies the corresponding paths. Finish with the repository gate ladder, including B-tree/index/query suites, CLJ/CLJS/CLJD and downstream DHT, transact, schema, VM and REPL gates.

### 13. Contract amendment instructions

In `dao.space.query.datomic-compat.md`:

1. Retain the existing **Storage checkpoints and overshoot** guarantee unchanged.
2. Replace the blanket Phase 4a published-source refusal with the admission table and rules in section 9 above.
3. Add sections 1–11 above as the Phase 4b seam specification, or link them from a dedicated design document if the orchestrator chooses that organization.
4. Replace Phase 4b’s provisional estimate with **20–32 implementation agent-hours**, plus its review allocation.
5. Keep bounded history, `as-of`, nested published views and deferred-row fallback explicitly unqualified.
6. Keep final Client oracle qualification separate from this storage-execution qualification.

### 14. Invariant analysis

- **No hidden state:** cursor and execution progress are explicit returned values.
- **No implicit control flow:** the query driver chooses when to step and when to fetch.
- **No callbacks:** request/response values express traversal suspension; no handler is registered with a backend.
- **No shared mutable query state:** borrowed trees are untouched; cursor frames belong to one invocation.
- **No layer collapse:** generic cursor interprets node layout, DaoJing reads content, query interprets datoms, adapter interprets Client call shape.
- **No assumed application graph:** the persisted B-tree topology is explicit in child addresses; entity navigation remains explicit in query/pull.
- **Information preservation:** no source writes, destructive reads, schema changes or persisted-format changes.

The bounded path does not reuse the legacy tree’s lazy restoration machinery. That separation is what makes each fetch visible and accountable without introducing a mutable execution cell.

----