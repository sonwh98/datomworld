Created-GMT: 2026-10-24 12:28:08 GMT
Created-Local: 2026-10-24 19:28:08 +07
Coding-Agent: claude
Session-ID: 6e84b906-f5ab-4551-bd47-73efc3d4a7c4

# Task: implement Phase 0 engineer deliverables — the datomic-q-v1 conformance corpus, fixture loader, conformance harness, and oracle runner

Role: QA, TDD and Verification Engineer (implementation slice; this brief defines your exact file authority)

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-24 19:28:08 +07 | Status: active | Rationale: the ratified plan sizes Phase 0 on a claude-opus-5.5 engineering lane; claude flat plan is open per routing-status (2026-10-23).

## Governing contract — read first, in this order

1. docs/design/dao.space.query.datomic-compat.md — the Phase 0 contract freeze
   (authored by gpt-6-astra 2026-10-24, transcribed verbatim by the
   orchestrator). YOUR SPEC IS SECTIONS "Conformance corpus and oracle" and the
   closing "Amended Phase 0 engineer-deliverable spec" (in the findings file
   collab/1792844152000-architect-dao-space-query-client-api-compat-r2.gpt-6-astra.findings.md
   — the doc in the tree ends at the Documentation completion section; the
   findings file carries the deliverable table after the ---- delimiter).
2. docs/design/datom.world.md — axioms and non-negotiable invariants.
3. docs/agents/build-n-test.md — gate ladder (G0 focused JVM first) and lane rules.
4. Precedent for corpus discipline: test/resources/dao/jing/cbor-v1.json +
   test/dao/jing/cbor_conformance_test.cljc + test/resources/dao/jing/cbor-v1.README.md
   (frozen cases, provenance, explicit host exceptions, no self-regenerating
   expectations). Follow this precedent's structure and spirit.
5. src/cljc/dao/space/query.cljc — the core whose contract points the corpus
   pins (q :1700, collect :1761, special forms :1273, aggregate parser :1549,
   rule-head refusal :1421-1425, final-set-collapse docstring :1605-1611).

## Deliverables (your entire file authority — write NOTHING outside this list)

1. `test/resources/dao/space/datomic-q-v1.json` — the frozen corpus. Explicit
   data representation for symbols, keywords, lists, and numeric kinds
   (BigDecimal/BigInt/float/double markers — NEVER rely on plain JSON number
   decoding to preserve identity). Cases for every contract point in the
   doc's supported-domain table, including the five web-app workload classes
   (entity views, list surfaces with :offset/:limit, session-scoped reads,
   view summaries/aggregates, bounded failure). Initial cases are
   DaoSpace-side executable, refusal, documented-difference, or
   explicitly `unverified`-pending-oracle — honest classification per case.
   Do NOT fabricate expected Datomic answers: unverified stays unverified.
2. `test/resources/dao/space/datomic-q-v1.README.md` — fixture DSL, identity
   normalization, evidence classes (local-client-source / local-dao-source /
   oracle-executed / unverified), comparison rules, corpus
   regeneration/promotion procedure, supported-domain inventory.
3. `test/resources/dao/space/datomic-q-v1.errata.md` — known limitations
   recorded explicitly (start with the final-set-collapse numeric limitation
   from query.cljc:1605-1611); no silent exclusions.
4. `test/resources/dao/space/datomic-q-v1.provenance.edn` — corpus digest,
   inspected artifact coordinates (com.datomic/client-api 1.0.69), baseline
   revision f6856bbc, oracle/backend provenance (pending), unresolved
   observations.
5. `test/dao/space/datomic_query_fixtures.cljc` — portable (CLJ/CLJS/CLJD)
   immutable fixture loading and typed-value reconstruction from the JSON
   corpus. Pure data handling; no resource writes; no JVM-only requires.
6. `test/dao/space/datomic_query_conformance_test.cljc` — corpus integrity
   checks (schema conformance, digest stability, classification validity)
   plus the executable DaoSpace-side conformance cases (run core
   dao.space.query/q + collect against the fixture data), with the honest
   distinction: pending/refusal/difference/executable. Unverified cases must
   NOT assert compatibility — they assert their own pending status.
7. `src/dev/dao/space/datomic_query_oracle.clj` — JVM-only oracle runner:
   loads the corpus, executes datomic.client.api/q for oracle-eligible cases,
   writes candidate observations to a SEPARATE output file, never rewrites
   frozen expectations. The Datomic backend is NOT yet selected: deliver the
   runner with backend selection as an explicit parameter/config and record
   the limitation in provenance; do NOT substitute Peer execution or mocks
   for the oracle.
8. `deps.edn` — ONE change only: add a development/oracle alias containing
   com.datomic/client-api {:mvn/version "1.0.69"} (the artifact is in
   ~/.m2). No addition to :deps or any production path. Leave every other
   line byte-identical.

## Constraints

- TDD where practical (corpus integrity checks red→green), but the corpus is
  data-first — author the schema and integrity checks together.
- The new test namespaces must pass `bb check:node-discovery` (no JVM-only
  code reachable from Node-loaded .cljc test namespaces; guard oracle/JVM
  paths behind .clj files or reader conditionals with a printed SKIP like
  dao.test-slow/guard precedent).
- Preserve unrelated changes; do not reformat deps.edn.
- No staging, no committing, no worktree creation. You work in the main tree;
  the dao.star track runs in its own worktree — do not touch .claude/ or any
  file outside the list.
- bb needs mise on this machine: invoke as `mise exec -- bb <task>` if a bare
  `bb` is not on PATH (owner relay).

## Verification (run and report exact outcomes)

1. `clj -M:test -n dao.space.datomic-query-conformance-test` (G0 focused JVM)
   — must pass; report test/assertion counts.
2. `clj -M:test -n dao.space.datomic-query-fixtures-test` if you add fixture
   unit tests, else fold integrity checks into the conformance ns and say so.
3. `clj -M:kondo --lint` on each new file — 0 errors 0 warnings target.
4. `bb check:node-discovery` (via mise) — pass.
5. `git status --porcelain` — confirm only the 8 artifacts (7 new + deps.edn
   modified) changed.
6. Oracle runner smoke: run it with backend=unselected and confirm it
   reports pending observations honestly without writing frozen files.

## Final response

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 6e84b906-f5ab-4551-bd47-73efc3d4a7c4

Report changed files, exact commands with outcomes and assertion counts,
unresolved concerns, and any incomplete work. Do not claim edits or tests
that did not occur. The orchestrator will promote this response to
collab/1792844888000-qa-engineer-datomic-q-phase0.claude-opus-5-5.findings.md.
