Created-GMT: 2026-11-16 18:35:22 GMT
Created-Local: 2026-11-17 01:35:22 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: 01a1e592-2d0b-7fe2-9438-fae05bf926d6 (resumed: step-7 review thread)
# Task: yang-compilers-dedup-malleability-review
Role: Lead System Architect

Completed-GMT: 2026-11-16 18:35:22 GMT
Completed-Local: 2026-11-17 01:35:22 Asia/Ho_Chi_Minh

The refactoring improves evolutionary fitness. It centralizes existing shared contracts while preserving target-owned policy. No blocking architectural defect identified. Three nonblocking concerns remain:

| Severity | File:line | Invariant / evidence | Recommended correction |
|---|---|---|---|
| P2 — architectural debt amplified | [full.patch.txt:31](/Users/sto/workspace/datomworld/collab/exception-track/1794852821344-dry-r2-full.patch.txt:31), [full.patch.txt:11](/Users/sto/workspace/datomworld/collab/exception-track/1794852821344-dry-r2-full.patch.txt:11) | `core.prelude` grows **14→16** public functions; `core.lower` grows **10→12**. Both exceed the 2–5 API target; lower reaches the approximately 12-function extraction trigger. Low dependency counts do not establish bounded interface complexity. | Before further growth, audit public/private boundaries and evaluate cohesive splits: prelude emission versus admission, and lowering diagnostics versus packet access/UAST construction. Keep that work separate from this mechanical extraction. |
| P2 — implementation documentation gap | [full.patch.txt:31](/Users/sto/workspace/datomworld/collab/exception-track/1794852821344-dry-r2-full.patch.txt:31) | The shared balanced emitter loses the originals’ explanation of stack-depth pressure. Its distinction from ordinary sequencing affects canonical tree shape. | Document why both emitters exist, including empty-input behavior and the requirement to preserve tree shape. Future callers should choose deliberately. |
| P2 — future governance risk | [equivalence-plan.md:69](/Users/sto/workspace/datomworld/collab/exception-track/1794796441580-dry-orchestrator-fixture-inventory-and-equivalence-plan.md:69) | “Never” restoring target-local implementations can become forced coupling when contracts genuinely diverge. The existing STOP clause appropriately prevents an automatic resolution. | When that clause triggers, explicitly permit a target adapter or separate implementation after architectural review. Preserve shared semantics where they remain shared; avoid accumulating flags merely to retain deduplication. |

**Bounded complexity — PASSED for the new namespaces.** Store exposes two operations; publication exposes three. Every added public function has external callers. Consumer `load-tree`, `overlay`, definition emitters, and lowering helpers remain private. Existing public publication wrappers retain external callers in tests and harnesses and preserve an established interface; making them private would introduce unnecessary compatibility churn. Bounded complexity remains qualified for the enlarged prelude/lower namespaces, as recorded above.

**Loose coupling — PASSED.** Each new namespace depends on one foundational namespace and has two target consumers. PHP/Python sitesets each gain two outgoing edges; their projected out-degrees become 10/9 from the supplied baseline. This is additional stable architectural coupling, with shared maintenance replacing duplicated maintenance. The added edges introduce no cycles or multi-node SCCs. These are patch-derived conclusions using the supplied pre-diff measurements, not a newly generated analyzer report.

**High cohesion — PASSED.** Store operations share the canonical tree/byte-handle contract. Publication operations share the signed site-publication schema and indexing representation. Discovery, admission, hook selection, and target-specific initialization remain separately owned. The overlay’s mutable buffer remains local to each invocation; extraction adds no shared global state.

**Structural clustering — PASSED.** Matching bodies, data shapes, dependencies, and test surfaces establish an existing cluster. This satisfies the document’s extraction signal. A possible next cluster is common safepoint module-emission machinery, but substantial machinery is already shared. PHP budgets/ticks and Python/TypeScript signal initialization provide concrete reasons to retain target-owned composition. This round should explicitly leave those differences outside its boundary.

**Evolutionary fitness — PASSED within the shared contracts.** Ruby can reuse these operations without copying their implementations, while still implementing its own discovery and composition. A different byte-store protocol can use an adapter where semantics match. An incompatible protocol or envelope requires separate design rather than additional policy switches. Targets without sitesets acquire no dependency on these namespaces.

The parked items 2/4/5 use appropriate parameterization: `vconj` is a target symbol represented as data; reader/absence arguments preserve existing target boundaries. Both reflect observed variation. Separate balanced and ordinary emitters also preserve an observed distinction. Their surface-clear and equivalence gates remain intentionally deferred work, not evidence of an architectural defect.

**Naming — PASSED.** `siteset-store` and `siteset-publication` identify domain responsibilities and constrain their scope. Neither invites an unrelated collection of utilities.

MALLEABILITY VERDICT: IMPROVED — shared contracts gain focused ownership and cheaper adoption, with nonblocking API-growth and future-divergence governance concerns.
