Created-GMT: 2026-10-25 12:23:23 GMT
Created-Local: 2026-10-25 19:23:23 +07
Coding-Agent: codex
Session-ID: 01a16d38-d4fc-7253-8098-51e80bd692b9 (resume — the datomic-compat design thread)

# Task: r7 — author the Phase 4b seam design: checked published-index traversal

Role: Lead System Architect (design authorship; the contract routes the 4b seam through you before implementation)

Implementers:
- Model: gpt-6-astra | Assigned: 2026-10-25 19:23:23 +07 | Status: active | Rationale: your r3 ruling created 4b and reserved its design review; owner has authorized the next unit ("do it").

## Where the track stands

Phases 0, 1, 4a committed (3d77ed52, 1d29cdcf, df75d855 — 4a signed off by you at r6). The one-in-flight-storage-read guarantee currently holds only over materialized sources; bounded calls refuse published/deferred chains (:bounded-source-unqualified, zero reads). Phase 4b is the seam that extends the guarantee to opened published indexes.

## The binding requirements (your own r3 text, now contract)

From "Staged source qualification" and "Storage checkpoints and overshoot":
- Coverage: root restoration, initial seek descent, range advancement, full-walk fallback, and reads made by pull or special forms.
- Per ACTUAL fetch: return invocation progress or explicit read requests — no mutation of a borrowed tree to install query state, no storage interpreting query semantics, no query-aware backend.
- A wrapping IStorage alone is insufficient (your r5-era analysis: slice*/descend-from/descend-leftmost/advance restore through storage baked into the restored tree; -restore has no channel to return updated immutable execution state; a wrapper risks hiding the forbidden mutable progress cell).
- B-tree memoization is not permission to add query-control state to the tree.
- Phase 4a's kernel is trampolined with explicit heap frames and incremental accounted producers — the seam must compose with that machinery (run-comp, bounce/bind), not bypass it.

## Design questions to resolve

1. **Mechanism.** Candidate shapes you may choose among or combine (or reject with a better one):
   (a) explicit read requests: the traversal yields a request value; the driver performs the fetch through its own storage capability and feeds bytes back — the tree never restores lazily during a bounded query;
   (b) stepped traversal in dao.data.btree: a public iterative walk API (seek/advance returning explicit per-step outcomes) that the kernel drives;
   (c) invocation-scoped restore: rebuild the tree/settings for the invocation with an accounting storage seam that RETURNS progress per restore (must satisfy your own insufficiency ruling — show why it is not the rejected wrapper).
   Rule on: which mechanism, its exact data shapes, who owns memoization during a bounded query, and how unbounded calls are affected (they must keep today's behavior and performance).
2. **Boundary discipline.** dao.data.btree is shared infrastructure (index restore, DHT loading, linker publication). Specify what changes inside dao.data.btree (if anything), what lives in dao.space.query/execution.cljc, and what lives in the adapter — no layer collapse, and the btree must not learn query semantics (deadline/steps are invocation data, not tree data).
3. **The guarantee, made testable.** Define the counter/instrumentation contract (the IStorage/-accessed seam or a wrapper store — your r3 text allows an explicit wrapper store for observability even though it cannot be the correctness mechanism). Specify the three-host tests that PROVE: at most one in-flight read past the deadline on the supported paths; zero reads on refusal; progress returned per fetch; pull/special-form/fallback coverage.
4. **Warm-cache and memoization interaction.** A previously-restored tree has memoized nodes: is a cache hit a "read"? Define what the guarantee means when the bytes are already resident (your r3: cache residency does not change ADMISSION; now define it for OVERSHOOT accounting).
5. **Scope fencing.** What parts of the 4a admission rule flip from refused to admitted when 4b lands (the contract's matrix row 3), and what stays refused (deferred rows? unrecognized sources?). Phase 5's requirement: restrictions removed only for paths qualified by 4b — enumerate the paths precisely.
6. **Deliverable plan.** Phase the implementation (files, acceptance criteria, agent-hours against your provisional 12-20h), including the design-review step the contract requires before landing (recommend the reviewer seat: glm-5.3 stream-engineer holds the F-findings context, or another family).

## Constraints

Read-only dispatch as always; produce the complete design as your final response (the orchestrator transcribes any contract amendments verbatim as before); the 6 invariants + information preservation bind; no second evaluator; unbounded behavior/performance unchanged (the selective-read regressions in query_test get/≤4 and ≤15 pins are the baseline).

Read first: docs/design/dao.space.query.datomic-compat.md (Staged source qualification; Storage checkpoints), src/cljc/dao/data/btree/ (the traversal path your r3 analysis named: slice*, descend-from, descend-leftmost, advance, resident-root, node-child, storage.cljc), src/cljc/dao/space/query.cljc (run-comp/bounce/bind; datoms-st; select-by-index at the former :1042-1044 seam — line numbers have shifted post-rewrite), src/cljc/dao/space/query/execution.cljc (read-step/run-reads), and the existing selective-read regression tests in test/dao/space/query_test.cljc.

Begin the final response with the usual header. The orchestrator promotes it to
collab/1792931003000-architect-datomic-q-phase4b-seam-design.gpt-6-astra.findings.md
and routes the design to independent review before any implementation dispatch.
