Completed-GMT: 2026-10-24 17:25:34 GMT
Completed-Local: 2026-10-25 00:25:34 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a16d38-d4fc-7253-8098-51e80bd692b9

**Ruling: Route A, one state-passing evaluator. Choose seam option (b): qualify bounded execution over materialized sources in Phase 4a; defer checked published-index traversal to a separately reviewed Phase 4b. Confirm expiry at `observation >= deadline`.**

The implementer correctly invoked STOP. The existing execution helpers establish useful local properties, but neither actual query cancellation nor the published-index read guarantee is implemented.

This is a read-only architecture decision. I inspected the report, implementation, tests, adapter, evaluator and B-tree traversal. I did not edit files or rerun tests.

## 1. State-threading decision

Authorize **Route A**, including the necessary evaluator rewrite. Do not retain two production evaluators.

The `::active-rules` precedent identifies an explicit context path, but it does not solve returned progress: it passes branch scope downward. My earlier specification understated that distinction. Execution state must return from nested work and feed subsequent work.

Route B would duplicate exactly the machinery still awaiting aggregate and recursive-rule changes. The corpus and parity tests are necessary regression evidence, but they cannot establish equivalence over every query, evaluation failure, numeric edge and recursive workload. Maintaining two interpreters would impose a permanent obligation without preserving a required public contract.

Checks only around existing lazy operations are also rejected. A `keep`, `mapcat`, recursive branch, or source scan can do arbitrarily much work before producing its next binding.

### Authorized implementation shape

Use one explicit state-passing evaluation kernel. Standardize its internal return convention as **`[state result]`**, consistent with the delivered execution helpers.

- Core `q` runs the kernel with `execution/unbounded`.
- The adapter runs that same kernel with an explicit execution state and driver.
- Keep the existing public core signature and result-value representation.
- Do not add Client option names to core evaluation.
- Treat exhaustion as terminal data inside the kernel; only the adapter translates it into a compatibility exception.
- A terminal return must not expose a usable partial result.

The rewrite covers query normalization/validation loops, input expansion, source realization, clause evaluation, nested evaluation, rule expansion, projection, deduplication, aggregation, pull and output construction wherever those operations consume accountable work.

The adapter must carry the same state through its own parsing, translation, pagination and final realization. A bounded core beneath unaccounted adapter preprocessing does not satisfy the entry-to-return contract.

### Strict state passing does not require exhaustive intermediate materialization

Do **not** mechanically replace every lazy operation with `mapv`.

In particular, `not` and `not-join` currently ask whether a nested evaluation has a witness. Exhaustively computing that nested relation can introduce unnecessary reads, exceptions or divergence after a witness was already found.

The common kernel must support explicit demand:

- Complete enumeration for relation production.
- First-witness termination for existential checks.

Implement this using checked loops/folds or explicit traversal frames. It remains one evaluator; a demand parameter is not a second interpretation of Datalog. Preserve existing content-aware deduplication and scoping.

Likewise, keep source traversal incremental where it is incremental today. “State-passing” does not authorize draining published sources before planning or replacing selective access with full scans.

### Scope versus progress

Separate these two lifetimes explicitly:

- **Lexical/path scope:** rule-local bindings, join-local variables and active-rule membership.
- **Invocation progress:** consumed steps, remaining allowance, deadline and terminal outcome.

When a nested rule returns, restore the caller’s lexical/path scope while retaining the returned progress. Do not leave an active-rule key installed in subsequent sibling branches, and do not restore an old budget along with the old scope.

Use loops or explicit frames where necessary so that increasing a configured work allowance does not merely replace budget exhaustion with host stack overflow.

No atom, volatile, dynamic variable or mutable closure may carry evaluator progress. Test-local fake-clock counters remain acceptable test instrumentation.

## 2. Checked-read seam decision

Choose **(b)**. Split Phase 4 into:

- **Phase 4a:** the common state-passing evaluator, adapter integration, and bounded-execution qualification for materialized sources.
- **Phase 4b:** checked published-index traversal, with a separate architecture-reviewed seam design.

Phase 4b remains required before qualification of bounded queries over published indexes. It is not silently absorbed into Phase 5 or removed from the web-backend objective.

### Why not authorize a wrapping `IStorage` now?

The inspected B-tree path confirms the problem:

- `slice*` calls `resident-root` and descends before returning its sequence.
- `descend-from`, `descend-leftmost` and `advance` can traverse multiple levels.
- `resident-root` and `node-child` restore through storage attached to the tree and its settings.
- Restored nodes are memoized in the existing tree machinery.

A per-call wrapper may isolate a storage capability, but **wrapping alone does not return updated immutable execution state through `IStorage/-restore`**. It risks moving the forbidden mutable progress cell into the wrapper instead.

Therefore, neither wrapping storage nor adding checks around `bt/slice` is approved as a shortcut. Phase 4b must separately show how individual restores return progress or yield explicit read requests, including initial root descent and later advancement.

No mutation of the borrowed tree to install query clocks, budgets or storage wrappers is allowed. Existing B-tree memoization is not permission to add query-control state to it.

### Phase 4a admission rule

For a call supplying either `:timeout` or `:dao.space.query.datomic/max-steps`:

1. Admit explicit, fully materialized relation/fact-relation sources and supported views whose complete source chain terminates in such a relation.
2. Reject any source chain reaching an opened published index, deferred source rows, or another unqualified source implementation.
3. Inspect source descriptors without forcing them.
4. Apply this rule to every declared source, including a source unused by the eventual plan.
5. Refuse before any source realization or storage access.

A current view over a published source is **not** a materialized source. A warmed cache is **not** a qualification certificate. Step-only calls receive the same restriction: otherwise unchecked source traversal can escape work accounting.

Use the existing adapter `:unsupported` category with a minimal reason such as `:bounded-source-unqualified` and the source variable. Include neither the source value nor its bindings.

Unbounded calls preserve their existing source support through the common evaluator.

A caller may separately materialize a source and then pass a new relation value. That is explicit caller work outside the query deadline; the adapter must not perform this conversion automatically or advertise the combined operation as bounded.

## 3. Exact-boundary rule and machinery corrections

Confirm:

```clojure
(>= observed-now deadline)
;; => expired
```

Completion at the deadline is timeout, including expiry at the final observation after output construction. There is no successful partial result.

Work allowance has a different boundary: the last permitted unit may execute. Completion with zero remaining units succeeds if no more work is needed and the final deadline observation is live.

For integrated work, use a consistent sequence:

1. Observe the deadline.
2. If live, charge before performing the unit.
3. If still live, perform the unit.
4. Observe again at the required post-operation boundary.
5. Stop immediately on a terminal outcome.

This makes deadline-versus-budget precedence reproducible when both would otherwise fail at the next operation. An already recorded terminal outcome wins permanently.

Two small corrections are required in the delivered machinery:

- **Terminal-state absorption:** `checkpoint` currently associates `::phase` before checking whether the state is terminal. Its outcome stays stable, but the returned state changes despite the documented “unchanged” contract. Check terminality first and test equality of the entire terminal state after subsequent checkpoints.
- **Post-read expiry:** `read-step` currently returns the read value alongside a terminal state; `run-reads` correctly discards it. Every future consumer must discard such a value before interpretation. Prefer an explicit discarded/not-delivered result, or enforce this structurally in the runner. Test the primitive and its consumer, not only the aggregate read-sequence helper.

The existing synthetic read-sequence test proves that helper’s behavior. It does not certify a B-tree traversal.

## 4. Exact contract amendments

Apply the following to `docs/design/dao.space.query.datomic-compat.md`. These are transcription instructions; no edits were made here.

### A. Update the status header

Replace the opening status paragraph with:

> Status: implementing. Phases 0 and 1 are landed. Phase 4 execution helpers exist, but real-query integration and bounded-execution qualification remain pending. Phase 4 is split into 4a, the common state-passing evaluator over materialized sources, and 4b, checked published-index traversal. No full compatibility qualification is claimed. Individual oracle cases retain their recorded provenance and verification status.

### B. Under “Normative execution bounds → State and driver”

Replace the paragraph beginning “One invocation owns one immutable execution state” with:

> One invocation owns one immutable execution state containing progress, remaining work allowance, deadline data, phase and any explicit execution frames. The evaluator uses a common state-passing kernel whose internal operations return `[state result]`. Nested evaluation returns its updated state to the caller; subsequent work receives that returned state.
>
> The existing `::active-rules` mechanism is a precedent for explicit lexical/path context, not for accumulated progress: it passes scope downward but does not return consumed work. Rule-path scope must be restored on return while invocation progress is retained. Branch-local bindings, active-rule membership and execution allowances must not be conflated.
>
> Core `q` uses this same kernel with an explicit unbounded policy. The adapter uses it with its supplied bounds. A separate legacy and bounded Datalog evaluator is not permitted.

### C. Append under “Forcing discipline”

> The Phase 4a implementation is authorized to rewrite the evaluator into strict state-passing operations. Strict state passing does not require exhaustive materialization of every intermediate relation. The common kernel must preserve first-witness termination for existential evaluation such as `not` and `not-join`, using explicit demand, checked folds or execution frames.
>
> Query-controlled loops must return progress, including loops that reject every candidate or produce no binding. Lazy evaluator chains that capture stale execution state are not permitted. Adapter preprocessing and final output work participate in the same invocation state.
>
> Existing selective index access and planning-never-forces behavior remain regression requirements for unbounded calls. This rewrite does not authorize an eager source drain or a second query semantics implementation.

### D. Insert after “Storage checkpoints and overshoot”

> ### Staged source qualification: Phase 4a and Phase 4b
>
> The one-in-flight-storage-read guarantee is unchanged. Phase 4a does not claim to establish it for published indexes.
>
> Phase 4a may qualify bounded queries over explicitly materialized relation and fact-relation values, and supported views whose complete source chain terminates in such a materialized value. Their query-controlled realization, current-state interpretation, index construction and evaluation must participate in work accounting and deadline observations.
>
> Calls supplying either a timeout or a work cap must refuse a source chain reaching an opened published index, deferred source rows or another unqualified source implementation. Admission inspects descriptors without forcing source contents and checks every declared source before source realization. Cache residency does not change eligibility.
>
> Refusal uses the adapter's `:unsupported` category, a minimal `:bounded-source-unqualified` reason and the source variable. It must perform zero source reads and must not open or close anything. The adapter must not silently materialize a rejected source or fall back to unchecked execution.
>
> Unbounded calls retain existing source support through the common evaluator. Explicit materialization performed by the caller before invoking `q` is outside that query's bounds and must be described as such.
>
> Phase 4b owns the checked published-index traversal seam. Its design must cover root restoration, initial seek descent, range advancement, full-walk fallback and reads made by pull or special forms. It must return invocation progress or explicit read requests at each actual fetch, without mutating a borrowed tree to install query state and without making storage interpret query semantics.
>
> A wrapping `IStorage` alone is not sufficient evidence: its design must explain how immutable progress returns from each restore. The Phase 4b seam requires architecture review before implementation/landing. Phase 5 cannot qualify bounded published-index queries until Phase 4b passes.
>
> | Source path | Unbounded calls | Bounded calls after Phase 4a | Bounded calls after Phase 4b |
> |---|---|---|---|
> | Materialized relation/fact-relation | Existing supported semantics | Eligible for qualification after integrated tests | Same |
> | Supported view over materialized relation | Existing supported semantics | Eligible after checked realization and evaluation tests | Same |
> | Published index, directly or under a view | Existing supported semantics | Refused before source reads | Eligible only for paths proven by checked-traversal tests |
> | Deferred or unrecognized source implementation | No new support granted | Refused | Remains refused unless separately qualified |
>
> “Eligible” does not mean tests or Client oracle verification have already passed.

### E. Under “Step unit and nonproductive work”

Append:

> Deadline expiry is defined as `observed-now >= deadline`. The final observation uses the same rule, so completion exactly at the deadline is timeout.
>
> For an integrated work operation, observe first, then charge, then perform the operation only if the state remains live. Perform the required post-operation observation before interpreting or delivering its result. An existing terminal state is absorbing: subsequent operations return that same state, perform no work and take no clock observations.
>
> The last available work unit may execute. Completion with no remaining units succeeds only when no further work is required and the final deadline observation is live. A value returned by a read whose post-read observation expires must not be interpreted or delivered.

### F. Update “Supported domain”

Replace the timeout row and add the source-bound row:

| Surface | Classification | Contract and current evidence |
|---|---|---|
| `:timeout` | Documented difference; staged implementation | Expiry is observed time greater than or equal to deadline. Phase 4a qualifies only admitted materialized-source paths after integration tests. Published-index paths remain refused until Phase 4b. |
| `:dao.space.query.datomic/max-steps` | DaoSpace extension; staged implementation | Positive explicit work allowance, threaded through the whole invocation. The same Phase 4a source restrictions apply to step-only and timed calls. No ambient default. |

### G. Update “Implementation sequence and ownership”

Replace the sequence with:

> **0 → 1 → 4a → 4b → 2 → 3 → 5**
>
> Phase 4a may land independently with its explicit source restrictions. Phase 4b remains a required slice of the published-index backend objective; it is not deferred into an unspecified Phase 5 task. The remaining phases keep their existing acceptance criteria.

Replace the Phase 4 row with:

| Phase | Ownership and files | Acceptance criteria | Implementation agent-hours |
|---|---|---|---:|
| 4a — Common bounded evaluator | Engineer: `query.cljc`, `query/execution.cljc`, adapter integration, affected pull/realization helpers and tests. | One state-passing evaluator for bounded and unbounded calls; checked materialized-source execution; preserved existential demand and rule scope; adapter entry-to-return accounting; fail-closed source admission; deterministic tests on all three hosts; unchanged public core contract and downstream behavior. | 20–32 total, including 4–6 already spent; 16–26 remaining |
| 4b — Checked published-index traversal | Separate architecture-reviewed seam, then engineer implementation and portable read tests. | Per-fetch progress through root seek and subsequent traversal; one-in-flight guarantee on actual published indexes; no query state installed in borrowed trees; pull/special-form/fallback coverage; three-host counter evidence and lifecycle tests. | 12–20 provisional |

Replace the Phase 5 estimate with **7–11 agent-hours**. Its acceptance criteria remain intact and additionally require evidence that Phase 4a restrictions are removed only for paths qualified by Phase 4b.

Replace the paragraph beginning “Phase 4 may change only generic traversal machinery” with:

> Phase 4a authorizes the common evaluator/state-passing rewrite but no B-tree checked-read refactor. Phase 4b owns that separately reviewed seam. Neither slice authorizes a persisted storage-format change, query-aware backend, second evaluator, or weakened one-in-flight guarantee.

### H. Replace “Effort and routing” estimate paragraphs

> The original 12–22-hour Phase 4 estimate is superseded by source inspection. Phase 4a is estimated at 20–32 implementation agent-hours, including 4–6 already spent and 16–26 remaining. Phase 4b adds a provisional 12–20 hours, subject to its seam-design review. Combined Phase 4 implementation is therefore 32–52 hours.
>
> Phases 2 and 3 remain 6–10 and 8–14 implementation agent-hours respectively; no new aggregate or recursive-completeness work is folded into Phase 4a. Phase 5 increases to 7–11 hours for source-qualification, traversal and regression evidence.
>
> Remaining implementation from the Phase 4 STOP is 49–81 agent-hours. Allow a further 8–14 agent-hours for independent review and remediation across the remaining slices: 57–95 agent-hours remaining overall. Already completed Phase 0/1 work and the 4–6 hours already spent on Phase 4 are excluded from that remaining estimate.
>
> These are revised engineering estimates, not measured model-throughput guarantees. The Phase 4b range remains provisional until its checked-traversal design is reviewed. Existing role-routing and reviewer-independence requirements remain unchanged.

## 5. Resume acceptance criteria

Before Phase 4a can land as qualified for materialized sources, require:

- Actual bounded `q` tests, rather than helper-only tests.
- State accumulation across sibling branches and nested negation/rules.
- Active-rule scope restoration without progress rollback.
- First-witness tests with a later candidate that would fail if unnecessarily evaluated.
- Rejected-candidate scans and fresh-value recursive work exhausting the cap.
- Checked current-view realization, input expansion, pull, aggregation and adapter output.
- Source admission refusing direct, wrapped, cached and mixed-source published inputs with **zero source reads**.
- Terminal-state absorption and no use of results produced after observed expiry.
- Existing core, adapter, numeric, positional and downstream gates, including `dao.space.dht` and `dao.space.transact`.
- CLJ/CLJS/CLJD deterministic execution tests and host clock smoke coverage.
- Published-index selective-read regressions for **unbounded** calls: the evaluator rewrite must not turn them into eager drains.

The 81-case corpus and existing 72 core tests remain valuable baselines, but the rewrite needs these additional behavioral tests. Keep Phase 2 aggregate expansion and Phase 3 completeness changes separate so failures remain attributable.

## Implementer resume paragraph

Proceed with Route A: rewrite the existing evaluator into one explicit state-passing kernel using `[state result]`, with legacy `q` running it unbounded and the adapter carrying the same bounded state from parsing through final output. Preserve first-witness evaluation for negation, rule-local scope restoration, content-aware semantics, selective reads and planning-never-forces; do not create a second evaluator or hide progress in mutable state. This slice is Phase 4a: admit bounded calls only over explicit materialized relations/fact-relations and supported views over them; reject every published/deferred source chain before realization or reads whenever either timeout or max-steps is supplied. Leave unbounded source support intact. Confirm expiry at `now >= deadline`, make terminal states fully absorbing, and discard results after post-operation expiry. Add integrated three-host query tests and the stated downstream gates. Do not modify B-tree traversal or install storage wrappers in this slice: checked published-index execution is separately reviewed Phase 4b. Report Phase 4a’s limited qualification honestly; the overall Phase 4 and published-index backend objective remain incomplete.