I’ll continue against current HEAD, check which earlier leads the revisions resolve, and finish the report. I’ll keep the review read-only.
The revised `:strong` construction resolves the B-tree refault lead, including resumed sessions. The query-value and retry-suffix leads remain open. I also found two provenance gaps: resolution omits metadata references in `m`, and the supplied event schema would resolve a source-medium tempid as a log-local reference. A JVM probe confirmed that incremental insertion and bulk building produce equal rows but different root blobs, so Phase 0′ cannot require identical manifests.
Completed-GMT: 2026-09-13 09:15:45 GMT
Completed-Local: 2026-09-13 16:15:45 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a099f1-87d6-7811-8a69-b3336b956fac
Role: Lead System Architect (review) | Model: gpt-6-astra

Reviewed current HEAD `e101932`, including the three follow-up commits and Appendix A. No repository files changed.

The earlier refault lead is **resolved** by the fresh/resumed tree construction in §4.1. The query-value and publication-suffix leads remain open.

**[P1 — blocking] 1. Resolution omits the metadata-reference slot `m`.**

Location: `docs/design/dao.space.index.as-observer.md:184–191,306–311`.

The resolution rule enumerates entity ids and declared-ref values, but omits metadata references. This breaks the Phase 2 composition: macro log output carries `m = ev`, where `ev` is a negative allocated event id (`docs/design/yin.vm.macro.md:691–697`). Resolving the event’s `e` while retaining the output row’s negative `m` disconnects the provenance edge.

This is part of the d5 identity protocol, not knowledge of macro semantics. The existing transactor collects and resolves `m` tempids explicitly (`src/cljc/dao/space/transact.cljc:133–150,168–181`). `local-datom?` merely checks that `m` is an integer; post-resolution validation therefore does not catch the broken reference.

**Correction:** Resolve negative metadata references through the same batch-local table, including tempids appearing only in `m`. Preserve reserved markers. Define mode rules for positive metadata references alongside ref-valued `v`. Test that a resolved event joins to every output row whose original `m` named that event.

Classification: architectural identity defect.

**[P1 — blocking] 2. The cross-medium provenance example conflicts with the supplied ref schema.**

Location: `docs/design/dao.space.index.as-observer.md:289–311`.

The example expects an event’s `:yin/source-call -16` to identify tempid `-16` in a source batch. However, the prescribed `event-schema` declares `:yin/source-call` a ref (`docs/design/yin.vm.macro.md:699–702`). The indexer will resolve `-16` against the **log batch’s** table, allocating a log-local entity if necessary. It will not retain the illustrated raw value or resolve it to the source entity.

A shared allocator prevents allocation collisions; it does not establish reference correspondence. Moreover, `:yin/source-call` denotes an external source entity for an initial expansion and a log-local entity for a nested expansion. An attribute-level ref declaration alone cannot distinguish those scopes.

**Correction:** Specify an explicit composition-level correspondence protocol. Preserve external coordinates as descriptive data, or supply qualified mappings that distinguish external coordinates from batch-local refs. Keep this outside generic index interpretation. Exercise initial and nested expansions, including identical negative numbers on different media and `v`-only tempids.

Classification: architectural provenance defect. The allocator/topology choice may remain deferred; the claimed query chain cannot.

**[P1 — blocking] 3. A checkpoint lacks a publication-completion and coverage contract.**

Location: `docs/design/dao.space.index.as-observer.md:395–418`.

Successful intake appends do not establish durable availability. A checkpoint can survive on a durable source while the manifest or some referenced blobs remain only in an in-memory intake. A process failure then leaves a checkpoint whose promised restart fails. This is precisely the loss window documented in `docs/design/dao.space.md:441–449`.

The four fields also need to describe **one captured boundary**. If publication covers batches through B, then the observer folds C before the composition records its current cursor/allocator beside B’s manifest, restart skips C permanently.

Finally, the checkpoint omits known incompleteness. Restoring a partial index while resetting `:ingress-gaps` to zero can make §3.2’s provenance-offset check appear safe again.

**Correction:** Specify:

- Capture manifest coverage, successor cursor, allocator and ordinal together.
- Distinguish enqueued publication from a recoverable checkpoint with its entire reachable graph available.
- Define recovery from an incomplete latest publication.
- Preserve completeness/provenance status, and bind resumption to the same mode, schema and allocation domain through the checkpoint or validated construction inputs.

Checkpoint *placement* may remain open. These validity conditions cannot.

Classification: architectural recovery defect.

**[P2 — must address] 4. Publication retries need explicit per-payload progress, and the observer fix must be a prerequisite.**

Location: `docs/design/dao.space.index.as-observer.md:151–153,386–389,460–472`.

“`full` retains the exact list” does not implement the borrowed discipline for a publication containing multiple appends:

1. Blob A returns `ok`.
2. Blob B returns `full`.
3. Retrying the original list appends A again.

Manifest-last makes that duplicate prefix content-addressably idempotent downstream; it does not provide exactly-once blob appends. The macro design clears each independently accepted payload. This note needs the equivalent within its list.

The known `run-on-stream` defect also bites this indexer. A round may finish a previously staged publication, clear its recording handle, then throw on a later read or fold. Current code returns no updated session (`src/cljc/dao/stream/observer.cljc:183–208`); retrying the caller’s old session repeats the already accepted publication.

**Correction:** Retain an immutable publication plus its next-unaccepted position, or retain only its unaccepted suffix. Advance after each `ok`; preserve progress on every terminal failure. Specify `closed`, `invalid-value`, malformed outcomes and `transport-error`, not only `full`. Gate Phase 0′ on the partial-session exception fix, including read failures and failures after an initial staged retry.

Test `ok/full/full/ok`, refusal at the manifest, terminal failure after accepted blobs, successful retry followed by a read failure, and a subsequent publication after additional batches. Promise at-most-once acceptance per staged occurrence under ordinary retries; content-address deduplication is a separate guarantee.

Classification: missing protocol specification plus an acknowledged implementation prerequisite.

**[P2 — must address] 5. The proposed index state is not a query value, and “live” lacks an observation boundary.**

Location: `docs/design/dao.space.index.as-observer.md:207–208,437–439,507–509`.

`query/value?` accepts relations, views and opened published values. `db-source` and `realize-db-value!` reject the proposed index-state map (`src/cljc/dao/space/query.cljc:142–150,185–195,371–399`). Merely carrying `:indexes` does not activate the covered-index path.

Wrapping EAVT rows in `query/relation` would make queries possible, but materializes rows and does not expose the existing covered trees through that path.

The acceptance statement also says the query sees a batch “after each appended batch.” Appending does not drive either observer.

**Correction:** Specify the public conversion from an immutable index state to a query db-value. If queries must consume the existing covered trees, name the necessary query realization extension while preserving dependency direction.

Define “live” as: after a successful observer round, the returned index represents every admitted, successfully loaded batch up to that session’s cursor, excluding whole rejected batches and reporting gaps. It promises no synchronization with appends or evaluator progress. Tests must explicitly drive the observer before querying, and verify that older db-values remain unchanged.

Classification: integration gap and an overstated acceptance contract.

**[P2 — must address] 6. The two-mode rule does not close all identity-bearing positions.**

Location: `docs/design/dao.space.index.as-observer.md:163–191,244–260`.

A positive declared-ref `v` on an unresolved medium passes admission and is never addressed by the mode rule. For example, with `next-eid = 16`:

```clojure
[-16 :example/ref 16 0 1]
```

can become a self-reference when `-16` is allocated as `16`, although the writer supplied the positive reference independently.

Conversely, `[16 :example/ref -16 0 1]` passes `local-datom?` in resolved mode. The promised rejection of “a tempid on a resolved medium” therefore requires an additional schema-aware check. Entity id zero is also admitted but neither allocated nor rejected by the stated “positive `e`” rule.

**Correction:** Provide a mode-validation matrix for `e`, declared-ref `v`, and metadata-ref `m`, including zero and reserved ids. Under the stated tempids-only model, reject unexplained positive user references in unresolved mode. Supporting already-resolved references instead requires an explicit allocation-domain contract. Leave undeclared negative values untouched.

Classification: architectural admission/identity gap.

**[P2 — must address] 7. The checkpoint proposal does not yet satisfy the transactor’s watermark requirement.**

Location: `docs/design/dao.space.index.as-observer.md:391–418,521–527`.

A checkpoint covering `t ≤ 10` cannot initialize a writer safely if retained transactions through `t = 12` exist after its cursor. Using the checkpoint alone reuses transaction times. `create!` currently derives `0` for empty history, otherwise **one plus** the maximum (`src/cljc/dao/space/transactor.cljc:117–131`).

The corrected O(rows) statement is honest, but still does not remove the asymptotic watermark cost. Also, saying checkpoints are worthwhile only for process-surviving cursors excludes the same-process task-restart optimization explicitly requested by `dao.space.md:434–456`.

**Correction:** Separate:

- Same-logical-stream reopening: restore a checkpoint and fold its retained suffix before enabling writes.
- Process restart with a durable external medium.
- Process restart with a new memory-log identity.

Record a derived writer-time summary at the covered boundary if eliminating O(history) is required; keep it causally bound to that checkpoint. Do not derive writer time from observer-ordinal resolution facts. Reconcile external checkpoint placement with the transactor’s “one truth” preference rather than treating the existing preference as already satisfied.

Classification: architectural recovery/performance gap.

**[P2 — must address] 8. Specify the outer batch grammar and rejected-batch state transition.**

Location: `docs/design/dao.space.index.as-observer.md:142–143,159–200`.

`run-on-stream` passes one exact stream value to `load`. A transactor appends a transaction-record map directly (`src/cljc/dao/space/transactor.cljc:144–145`); a program medium carries a collection of rows. The note specifies each *element’s* grammar but not how the top-level value becomes elements. Iterating the transaction map as a batch would inspect map entries rather than its transaction record.

“Whole batch skipped” also needs an explicit state transition: otherwise an implementation may return before advancing `:batch`, invalidating cross-session offsets after malformed input even without a gap.

**Correction:** Define normalization for a raw d5 value, a transaction-record value, a batch collection, and malformed outer values. Validate the complete normalized batch before committing allocation or tree changes. A rejected consumed batch must preserve all four trees and `:ids`, append its defect, and increment `:batch` exactly once.

Test malformed outer values, malformed nested records, a valid prefix followed by a defect, mixed-mode rows, and valid–invalid–valid batches. Require deterministic tempid allocation order across hosts.

Classification: implementation contract gap; the intended atomic, non-throwing policy itself is sound.

**[P2 — must address] 9. Manifest equality is not a valid incremental-build acceptance test.**

Location: `docs/design/dao.space.index.as-observer.md:474–480`.

Existing `publish-index!` bulk-builds with `bt/from-sequential` (`src/cljc/dao/space/index.cljc:561–567`). The proposed observer inserts with persistent `conj`. These can produce different tree partitions for identical row sets, hence different root blobs and manifest addresses.

A JVM probe with branching factor 4 and values `0..15` confirmed:

```text
equal-rows true
equal-root-blobs false
unchanged-store-writes 0
one-insert-store-writes 4
```

**Correction:** Require identical logical rows, counts, query results and valid restored indexes—not identical manifests. Include enough rows and insertion orders to cause splits. Canonical tree layout would be an additional design requirement, not a property of the existing B-tree.

Classification: acceptance-test defect.

**[P3 — suggestion/alignment] 10. Clarify clock semantics and the scope of the new retraction explanation.**

Location: `docs/design/dao.space.index.as-observer.md:210–227,272–282`.

With the namespace reservation honored, resolution facts cannot shadow observed rows under greatest-`t` selection: their `[e a v]` keys are disjoint. That correction passes.

However, `history` does not promise transaction-time ordering; its implementation preserves source row order (`src/cljc/dao/space/query.cljc:113–115`). Over EAVT, that is EAVT order. `as-of` also filters both observer-clock facts and writer-clock facts numerically, despite their different meanings.

The assertion/retraction conflict explanation applies only when both rows resolve to the same entity. Reusing a negative tempid in a later unresolved batch creates a different entity and produces no such conflict.

**Correction:** Describe `history` as exact rows, require explicit ordering where needed, and document mixed-clock `as-of` behavior. Qualify the retraction example by resolved identity.

Classification: semantic documentation alignment.

**[P3 — suggestion/alignment] 11. Tighten the allocator rationale and invariant table.**

Location: `docs/design/dao.space.index.as-observer.md:244–260,449–454,533–538`.

The collision argument is sufficient for **unrestricted, independent positive allocators**. It is not a proof that integer partitions are impossible: an explicit even/odd convention can avoid runtime coordination. That alternative still changes the writer’s allocation contract and cannot safely accommodate an arbitrary existing writer. Two modes remain the simpler default here.

“Never equate `?e`” is advisory: query unification compares values and carries no allocation-domain check. The same caution applies to independently allocated unresolved sessions and independent resolved sources, including identity-bearing `v` and `m`.

The invariant table also says the recording handle is created per publication, contradicting §4.1’s persistent handle/storage discipline, and names throwing `datoms-from-elements` as the batch validator.

**Correction:** State these as explicit source-domain contracts. Describe any shared allocator as a composition-owned value threaded serially, not an atom shared among sessions. Correct the two stale invariant-table entries. No mode partition or query guard is required merely to preserve the chosen simpler design.

Classification: rationale and invariant documentation alignment.

**Properties that passed**

- **Symmetric ignorance:** §§2–4 need no evaluator state, AST interpretation or knowledge of sibling observers. Supplied ref schemas are data. The remaining provenance work can stay in composition without teaching the indexer macro semantics.
- **Core boundaries:** No new global registry, scheduler or application callback is required. Folding, publication and materialization remain explicit; storage remains opaque. Graph edges are selected by declared reference information.
- **Incremental storage:** `conj` preserves unchanged child addresses and clears changed paths; `node-store` skips addressed child slots; an already-addressed root returns immediately. The dirty-subgraph claim holds against the actual implementation.
- **Refault correction:** The revised fresh/resumed construction closes the earlier draining hazard. Resumed strong trees retain new nodes; untouched, initially unfaulted children retain their durable storage route. Draining the recorder therefore need not destroy a live tree’s readable graph.
- **Serialization correction:** Round-tripping checkpoint data rather than a live session is appropriate, subject to the transport’s cursor-serialization contract.
- **Memory-log caveat:** A process restart creates a new logical stream identity. The old cursor cannot resume it.
- **Gap-offset caveat:** An observed gap invalidates the constant ordinal correspondence. Gap count counts gap observations, not the number of missing batches; it cannot calculate a replacement offset.
- **Namespace reservation:** When honored by the source, it separates resolution facts from writer facts without inspecting domain semantics.

The loop’s relevant behavior is:

| Case | Actual behavior |
|---|---|
| Defective batch returned as data | `load` returns; cursor advances. Consumer consistency depends on atomic rejection and ordinal advancement. |
| Staged publication remains `full` | `run` executes first; not-ready state returns without reading input. |
| Staged publication finishes next round | Consumer becomes ready; the same call resumes observation and may load further batches. |
| `blocked` | Returns the session, retaining the cursor; publication remains an explicit later composition step. |
| `end` | Also returns the session with the cursor retained; current return shape does not distinguish it from `blocked`. |
| `gap` | Adopts recovery cursor, increments gap accounting, and continues within the same call. |
| Later throw | Current implementation loses the round’s successor session; partial-progress exception support remains necessary. |

Phase 0′ → 1 → 2 → 3 is a reasonable broad sequence, but Phase 0′ must depend on the observer progress fix and an agreed publication state machine. Phase 2 needs the identity/provenance corrections; Phase 3 needs checkpoint validity and suffix recovery. The existing acceptance list does not cover those failure paths.

Topology choice, checkpoint placement, the exact representation of published completeness, and retirement of `snapshot` may remain deferred. Their required correctness properties must be stated before the dependent phases implement them.

**Verdict: REQUEST CHANGES.**
