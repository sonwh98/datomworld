Created-GMT: 2026-10-24 12:15:52 GMT
Created-Local: 2026-10-24 19:15:52 +07
Coding-Agent: codex
Session-ID: 01a16d38-d4fc-7253-8098-51e80bd692b9 (resume of the 2026-10-24 design thread)

# Task: r2 — Phase 0 contract freeze: author docs/design/dao.space.query.datomic-compat.md, folding in the reconciliation and the owner's consumer story

Role: Lead System Architect (design authorship; per this seat's routing)

Implementers:
- Model: gpt-6-astra | Assigned: 2026-10-24 19:15:52 +07 | Status: active | Rationale: continuation of the same design on the same seat's thread; codex reserved for architecture per team.md.

## What happened since your plan

1. The owner accepted the plan's direction and gave its purpose — quoted verbatim per orchestrator protocol:

> "this is a parallel track to dao.star but i want this to be ready to build web based apps using dao.star with dao.space as a datomic like database backend"

The orchestrator's paraphrase, kept visibly separate: this track exists so that
web applications built on dao.star (Mode 1, ratified f6856bbc —
docs/design/dao.star.md) can use dao.space as their Datomic-like database
backend, with the compatibility adapter as the query surface those apps'
developers write against. The owner also directed a stream-engineer review
of your plan (below), then instructed the orchestrator seat to continue this
track.

2. Independent stream-engineer review (glm-5.3, 2026-10-24, session
37072475-0422-4f8a-9fad-d7faa9b3b395) returned ENDORSE WITH CHANGES. Read the
findings verbatim:
   collab/1792840587000-stream-engineer-dao-space-query-datomic-review.glm-5.3.findings.md
The orchestrator verified the review's load-bearing factual claims locally
(dht.cljc:1498 apply-query/q call site; relation-result docstring at 1605-1611;
::active-rules ctx threading at 1413/1416; dao.stream.remote.md:486-491
step-budget precedent) and ACCEPTED ALL TEN FINDINGS as plan amendments.
They are now binding requirements on the contract freeze:

- F1 (P2): Phase 4 spec must pin the step unit (recommend: one candidate
  binding produced during the forced pass; observations at each phase
  boundary), the forcing discipline against the lazy evaluator, and the
  overshoot guarantee (recommend: "within one storage read of the deadline",
  checkpointing around each range scan at query.cljc:1042-1044, following the
  remote_channel.cljc:63-69 documented-overshoot pattern).
- F2 (P2): execution state threads through nested evaluation via the explicit
  ctx (the ::active-rules precedent, query.cljc:1413-1416); one
  invocation-scoped state; no dynamic-var or atom escape hatch.
- F3 (P2): dao.space.dht joins the Phase 5 downstream-caller gates (it runs
  (apply query/q ...) over a lazy published index at dht.cljc:1498);
  include dao.space.transact's realize-path uses.
- F4 (P2): timeout semantics tests inject a deterministic fake driver
  (expiry after N observations); wall-clock appears only in one
  generously-margined smoke test per host; no sub-second real timeouts in
  test lanes.
- F5 (P3): pin the clock seam — zero-arg now-fn or an absolute deadline
  minted at entry and compared as data (remote.cljc:379-397 pattern); forbid
  a namespace-level default clock var in execution.cljc; prefer monotonic
  sources where the host offers one.
- F6 (P3): timeout/eval error categories carry minimal projected context
  (clause, var, step count); never ::dbs or full binding maps.
- F7 (P3): compat doc carries sizing guidance for the eager-before-pagination
  difference (the unselective 3-fixed clause full-walk fallback at
  query.cljc:526); the segment-read counter builds on IStorage/-accessed
  (storage.cljc:68) or an explicit wrapper store so all three hosts count.
- F8 (P3): the final-set-collapse limitation is documented at
  relation-result's docstring, query.cljc:1605-1611 (your 1597 citation was
  off) — use the corrected citation.
- F9 (P3): name Phase 4's step budget as the termination backstop for the
  accepted recursion class (Phase 3 tabling addresses completeness, not
  termination).
- F10 (P3): name in Phase 0 that the query timeout raises a structured
  exception where other repo mechanisms answer exhaustion as data
  (:dao.stream/blocked, :lost/:deadline) — a deliberate divergence, not an
  invariant breach.
- Also noted: com.datomic/client-api 1.0.69 is in ~/.m2 but NOT declared in
  deps.edn (only com.datomic/peer 1.0.7364) — the engineer leg must add it
  at dev scope for the oracle runner.

## Deliverable for this round

Author the COMPLETE markdown text of docs/design/dao.space.query.datomic-compat.md
— the versioned compatibility specification, Phase 0's architect deliverable —
plus the amended engineer-deliverable spec. This dispatch remains read-only:
do not attempt to edit files. Produce the full document text as your final
response; the orchestrator transcribes it into the tree verbatim.

The document must contain, at minimum:

1. Status header (proposed/qualified lifecycle matching the repo's doc
   conventions), version pin (client-api 1.0.69), provenance (how each
   contract point was established: local jar source inspection, oracle
   execution pending, unverified).
2. Consumer story: dao.star web applications using dao.space as their
   Datomic-like backend. Ground it in docs/design/dao.star.md Mode 1 (read
   it; the facts/signals/router/driver model, session facts, fact-before-UI
   ordering). Spell out what follows for the adapter: representative
   web-app query workloads (pull-heavy entity projection for views,
   :offset/:limit pagination for list surfaces, current-view queries per
   session), where q runs (the app backend — JVM lane primary; the browser
   reaches dao.space only through the dao.star HTTP boundary/driver, never
   q over a socket directly), and which plan priorities this consumer story
   re-ranks. You MAY re-order phases if the consumer story warrants (e.g.
   pagination+timeout ahead of rules conformance) — state the rationale if
   you do, and keep every phase's acceptance criteria.
3. The supported-domain table from your plan (exact / documented-difference /
   refused / unverified), updated with the review's verified corrections and
   the F8-corrected citation.
4. The timeout design as a normative section: step unit, forcing discipline,
   overshoot guarantee, nested-state threading (F1/F2), clock seam (F5),
   error-context policy (F6), the exception-vs-data divergence note (F10),
   and the deterministic-testing requirement (F4).
5. Sizing guidance (F7) and the portable segment-read counter seam.
6. Error taxonomy (structured ex-info categories, causes preserved).
7. Conformance strategy: frozen fixture corpus (datomic-q-v1), provenance
   per case, dual loaders with entity-id normalization, oracle execution
   against a real client backend (unverified-marked until that backend
   runs), three-host runner coverage, gate ladder.
8. Implementation phases amended per F1-F10 with the caller gates extended
   (F3), the engineer deliverables for Phase 0 (fixture file layout under
   test/resources/dao/space/, fixtures ns, src/dev oracle runner,
   deps.edn dev-scope client-api), and the ETA table from r1 (re-estimate
   only if you re-rank phases).
9. The exclusions/deferrals boundary from r1, unchanged unless the consumer
   story forces a change (state any).

Constraints: read-only dispatch; the 6 invariants plus information
preservation bind; keep the r1 adapter/core split intact (no Client option
names in core evaluation); CLJ/CLJS/CLJD portability preserved with the JVM
lane named as the primary consumer host.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a16d38-d4fc-7253-8098-51e80bd692b9

Then deliver: (1) the complete doc text, delimited clearly (e.g. a line of
---- before and after), (2) any phase re-ranking with rationale, (3) the
amended Phase 0 engineer-deliverable spec. The orchestrator will transcribe
the doc verbatim and promote this response to
collab/1792844152000-architect-dao-space-query-client-api-compat-r2.gpt-6-astra.findings.md.
