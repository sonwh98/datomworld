Created-GMT: 2026-10-24 12:41:41 GMT
Created-Local: 2026-10-24 19:41:41 +07
Coding-Agent: glm
Session-ID: 09098796-22e1-41cf-b24b-f98b489eef39

# Task: independent adversarial review — Phase 0 engineer deliverables of the datomic-q-v1 conformance track

Role: QA, TDD and Verification Engineer (read-only adversarial review)

Implementers:
- Model: glm-5.3 | Assigned: 2026-10-24 19:41:41 +07 | Status: active | Rationale: family-independent review of a claude-opus-5-5 implementation slice (different provider family); glm proven healthy today on this track's plan review; argon (the plan's assumed review lane) has unverified credit status after its 2026-10-24 reset.

## Under review

The Phase 0 engineer slice delivered by claude-opus-5-5 (2026-10-24, session
6e84b906-f5ab-4551-bd47-73efc3d4a7c4). Files (all new except deps.edn):

- test/resources/dao/space/datomic-q-v1.json (80 cases, 28 contract points, 9 fixtures)
- test/resources/dao/space/datomic-q-v1.README.md
- test/resources/dao/space/datomic-q-v1.errata.md (E1–E8)
- test/resources/dao/space/datomic-q-v1.provenance.edn
- test/dao/space/datomic_query_fixtures.cljc (202 lines)
- test/dao/space/datomic_query_conformance_test.cljc (405 lines)
- src/dev/dao/space/datomic_query_oracle.clj (249 lines)
- deps.edn (a single :oracle alias, 2 lines + comment)

The implementer's claims (untrusted — verify): G0 conformance run 16 tests /
39 assertions / 0 failures (orchestrator reproduced this locally: CONFIRMED);
kondo clean; node-discovery OK; oracle smoke with --backend unselected
reported 77 pending / 3 not-eligible and refused to write into the frozen
corpus; corpus digest BLAKE3 e129fd6a…f9d43 pinned in test and provenance;
executable expectations are core-observed regression pins, labelled
DaoSpace-side only; errata records core defects E2–E7 instead of fixing them
(correct per plan — Phase 0 does not change the core).

## Contract to review against

- docs/design/dao.space.query.datomic-compat.md — sections "Conformance
  corpus and oracle" and the deliverable table in
  collab/1792844152000-architect-dao-space-query-client-api-compat-r2.gpt-6-astra.findings.md
  (after the ---- delimiter). Phase 0 acceptance: every contract point
  classified; no fabricated oracle evidence; fixtures load portably;
  disputed behavior has explicit oracle cases; pending cases reported
  honestly.
- Precedent: test/resources/dao/jing/cbor-v1.* + test/dao/jing/cbor_conformance_test.cljc.
- docs/design/datom.world.md invariants apply to the test code (no hidden
  state, no ambient anything in the loader).

## Review checklist (report per item)

1. Corpus integrity: JSON schema consistency, typed-value tags sufficient to
   reconstruct kinds portably (decimal/float64-bits/bigint/ratio), stable
   IDs, digest actually recomputable and pinned in BOTH the test and
   provenance.edn; does the test fail if the corpus is edited without
   re-digesting?
2. Honesty of classification: sample executable/difference/refusal/pending
   cases — is any pending case effectively asserting compatibility? Is any
   "executable" expectation actually a fabricated Datomic answer rather than
   a labelled DaoSpace-side pin?
3. Coverage vs the contract: do the 28 contract points cover the supported
   domain table (and the five web-app workload classes: entity views, list
   surfaces with :offset/:limit, session-scoped reads, view summaries,
   bounded failure)? Name any contract point from the doc's table with NO
   corpus case.
4. Portability: fixtures ns free of JVM-only requires (node-discovery + G1
   readiness); typed reconstruction correct on all three hosts; any
   host-conditional logic that silently reduces the qualified domain.
5. Oracle runner safety: prove from the code that it cannot overwrite frozen
   corpus files; check the backend=unselected path reports pending honestly;
   assess the acknowledged untested paths (:ref-attrs heuristic, entity-id
   mapping heuristic) — are the risks recorded in provenance/errata?
6. Errata accuracy: spot-check E2–E7 against actual core behavior
   (src/cljc/dao/space/query.cljc at f6856bbc) — e.g. does a string query
   really return #{[]}? does (min 2 ?a) really drop its parameter? Flag any
   erratum that misstates the core.
7. deps.edn alias: :oracle is dev-scope only; :deps untouched; no production
   leakage.

Read-only: do not edit files. Run checks when practical (you may run
`clj -M:test -n dao.space.datomic-query-conformance-test` and kondo; do NOT
run the oracle runner with any real backend, and do not modify the corpus).

## Output

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: 09098796-22e1-41cf-b24b-f98b489eef39

List actionable findings only as:
P0-P3 | file:line | evidence | concrete fix
State "No actionable findings" where appropriate. List commands actually run
with outcomes. End with one overall verdict line: ENDORSE / ENDORSE WITH
CHANGES / REJECT for the Phase 0 slice, plus one sentence. The orchestrator
will promote this response to
collab/1792844888000-qa-engineer-datomic-q-phase0-review.glm-5.3.findings.md.
