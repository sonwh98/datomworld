Created-GMT: 2026-10-24 17:18:14 GMT
Created-Local: 2026-10-25 00:18:14 +07
Coding-Agent: claude
Session-ID: 6e84b906-f5ab-4551-bd47-73efc3d4a7c4 (resume — Phase 4 of the same track)

# Task: implement Phase 4 — bounded execution (cooperative timeout + work budget) for the dao.space query core and adapter

Role: QA, TDD and Verification Engineer (implementation slice; owner-authorized "go")

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-25 00:18:14 +07 | Status: active | Rationale: Phase 1 lane continuity (same session holds adapter + corpus context); the ratified plan sizes this slice on this lane.

## Binding contract — read first

1. docs/design/dao.space.query.datomic-compat.md, section "Normative execution
   bounds" — every subsection is normative: State and driver; Step unit and
   nonproductive work; Forcing discipline; Storage checkpoints and overshoot;
   Exhaustion and exceptions; Deterministic testing. Also "Errors and context
   retention" (timeout categories) and "Sizing and read observability".
2. The glm stream-engineer review findings F1/F2/F4/F5/F6/F9/F10 (already
   folded into the doc — the doc is the binding text; the findings are at
   collab/1792840587000-stream-engineer-dao-space-query-datomic-review.glm-5.3.findings.md
   for rationale).
3. Precedents the design composes (glm-verified, read them):
   - yin/vm/ffi/remote_serve.cljc:700-728 budgeted-reader (exhaustion = data
     outcome); dao/stream/remote.cljc:257-316 mirror-step bounds.
   - dao/stream/remote_route.cljc:468 deadline-as-data; remote.cljc:1157
     "now is supplied"; lease.cljc:1552 time-as-data.
   - ::active-rules ctx threading (query.cljc:1413-1416) — the F2 precedent
     for threading invocation state through nested eval-where.
4. Your own Phase 1 adapter (committed 1d29cdcf) and its errata routing.

## Deliverables (file authority)

NEW:
- src/cljc/dao/space/query/execution.cljc — the generic machinery: immutable
  invocation execution-state (progress, remaining work allowance, deadline
  data, phase), the step-unit accounting table as data, the checkpoint fns,
  the explicit driver protocol (synchronous zero-arg now-fn called only at
  specified checkpoints; never registered, never global), exhaustion as a
  terminal data outcome the ADAPTER translates to exceptions.
- test/dao/space/query/execution_test.cljc — deterministic fake-driver tests
  (expiry after N observations): immediately-before / exact-boundary /
  immediately-after; work exhaustion independent of time; the required case
  list from the doc's Deterministic testing subsection (empty-result scans,
  rejected candidates, multi-node-fetch before binding, joins, negation,
  disjunction, nested rules, fresh-value recursion, pull traversal, aggregate
  realization, parse/realize/output boundaries, no-read-after-expiry,
  ownership preserved on success/failure/timeout). NO wall-clock sleeps
  except at most ONE generously-margined real-clock smoke per host, JVM-only
  in this slice, ≥5s margins.
- test/dao/space/query/datomic_execution_test.cljc (or fold into datomic_test)
  — adapter-level :timeout and :dao.space.query.datomic/max-steps wiring:
  validation (positive int ms / positive int steps), :timeout no longer
  refused, exhaustion -> :timeout / :step-budget-exhausted with
  :cognitect.anomalies/interrupted and minimal context (phase, step count,
  option name — never ::dbs/bindings); success paths unchanged.
- Consumer examples (deferred from Phase 1 per orchestrator ruling):
  src/dev/dao/space/query/datomic_examples.cljc — small doc-stringed
  examples of the five web-app workload classes (entity view pull, list
  surface with offset/limit + timeout + max-steps, session-scoped read,
  view summary aggregates, bounded-failure handling) over an in-memory
  current view. Examples only — no new public API.

MODIFIED (bounded):
- src/cljc/dao/space/query.cljc — ONLY the integration seams: thread the
  execution state through eval-where/nested evaluation via ctx (F2: no
  branch gets a fresh state; no dynamic vars/atoms); checkpoints at the
  doc's phase boundaries and around candidate binding production and each
  actual storage read (the checked-read seam — see below); legacy q passes
  an explicit unbounded never-expiring policy with IDENTICAL public
  behavior (all 72 existing tests must pass unchanged). Preserve
  planning-never-forces (:1472-1485).
- src/cljc/dao/space/query/datomic.cljc — wire :timeout/:max-steps to the
  machinery; mint the absolute deadline at entry (before parsing/
  realization); monotonic-source preference with wall-clock fallback
  documented; publish the operation-to-unit accounting table in the tests
  as the doc requires.
- The checked-read seam: the doc requires checkpointing down to each ACTUAL
  node fetch inside lazy range traversal, "via generic explicit read steps
  or an equivalent invocation-scoped traversal mechanism; do not mutate a
  borrowed shared tree". If the existing lazy traversal cannot expose that
  granularity without a traversal refactor, STOP and report rather than
  widening scope — the contract says that seam "requires architecture
  review before landing" if it goes beyond minimal generic machinery.
- Corpus (datomic-q-v1.*) — ONLY if you add executable timeout cases to
  pin adapter behavior (re-digest per procedure; update both pins; errata
  note). Optional this phase; say what you did.

DO NOT TOUCH: the ble files, Flutter/pubspec files, deps.edn (no new deps —
BLAKE3 etc. all in-tree), docs/* (the contract doc is the architect's; if
implementation reveals a contract defect, STOP and report it for the
architect rather than editing the doc).

## Acceptance criteria (from the contract's phase table)

One threaded invocation state; normative unit accounting with the
operation-to-unit table published in tests; checked forced pass (chunked
realization cannot bypass); the per-read one-in-flight overshoot guarantee
demonstrated on the supported path; deterministic expiry tests; one clock
smoke; minimal error context; no input ownership change on any path; legacy
q API and behavior unchanged.

## Verification (run and report)

- clj -M:test -n dao.space.query.execution-test (new)
- clj -M:test -n dao.space.query.datomic-test (extended — may grow)
- clj -M:test -n dao.space.query-test — MUST remain 72 tests / 282
  assertions / 0 failures, byte-identical behavior
- clj -M:test -n dao.space.datomic-query-conformance-test (17/41/0 or
  updated with re-digest)
- clj -M:test -n dao.space.index-test + dao.space.transactor-test (core
  integration cannot regress the index/transactor path)
- clj -M:kondo --lint on every changed file (0/0)
- mise exec -- bb check:node-discovery
- git status scoped audit

No staging, no committing. Same final-response header format. The
orchestrator promotes your response to
collab/1792862294000-qa-engineer-datomic-q-phase4.claude-opus-5-5.findings.md.
