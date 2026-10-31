Created-GMT: 2026-10-24 09:20:16 GMT
Created-Local: 2026-10-24 16:20:16 +07
Coding-Agent: codex
Session-ID: pending (provider-generated — orchestrator captures thread.started.thread_id from stdout JSONL)

# Task: implementation plan — make dao.space.query/q compatible with datomic.client.api/q

Role: Lead System Architect (design authorship; per this seat's routing)

Implementers:
- Model: gpt-6-astra | Assigned: 2026-10-24 16:20:16 +07 | Status: active | Rationale: hardest cross-subsystem design (query language + API contract reconciliation on a lazy-published-index substrate); codex/OpenAI seats are reserved for architecture per team.md, and this is squarely that.

## Owner instruction (quote, kept verbatim per orchestrator protocol)

> "you're the @orchestrator.md . ask astra as the [architect.md](./docs/agents/roles/architect.md)  to design an implementation plan to make dao.space.query/q compatible with datomic.client.api/q . also ask for ETA to implement"

The orchestrator's paraphrase, kept visibly separate: the owner wants (1) an
implementation plan for compatibility between `dao.space.query/q` and
`datomic.client.api/q`, authored by this seat, and (2) an ETA to implement it.
The owner has NOT authorized any code changes — this dispatch is read-only
design.

## Context and evidence base

Earlier today (2026-10-24) the owner asked whether dao.space and dao.jing.dht
are ready for use as a Datomic-like DB, then asked where `q` is tested, then
asked whether `dao.space.query/q` is compatible with `datomic.client.api/q`.
The orchestrator verified implementation state locally: JVM suites
dao.space.* + dao.jing.* ran 545 tests / ~48,500 assertions / 0 failures, 0
errors (incl. dao.space.stigmergy-test, 5 tests). Tree at dispatch:
master@f6856bbc.

## Orchestrator-established compatibility findings — verify, do not trust

The orchestrator read the source and produced the gap analysis below. Re-verify
each claim against the code before building on it; correct anything wrong in
your findings.

Calling convention (src/cljc/dao/space/query.cljc):
- `q` at query.cljc:1700 — `(q query & inputs)`, `$` binds to first input,
  optional trailing opts map `{:fns {...} :builtins bool}`. Returns a result
  VALUE `{:dao.space.query/result relation :spec ... :return-map-key ...}`;
  `collect` at :1761 materializes into find shapes. Deliberate lazy design:
  relations over published indexes fault only seek paths + ranges.
- `datomic.client.api/q` contract (upstream): `(q db arg-map)` with
  `{:query ... :args [db ...] :offset n :limit n :timeout ms :ret :coll|:mapping}`,
  returns realized data (coll of tuples, or mapping var→values with :ret
  :mapping).

Compatible today (verified by orchestrator):
- EDN vector AND map query forms (`normalize-query` :1512); not string queries.
- Find specs: default relation, scalar `.`, tuple `[?a ?b]`, coll `[?a ...]`
  (`apply-spec` :1639). Return maps `:keys`/`:syms`/`:strs`. `:with`.
  `(pull ?e [...])` as a find element. Distinct set semantics. Multi-source
  `$`/`$2`. `:in` scalar/tuple/collection/relation bindings.
  `not`/`not-join`, `or`/`or-join`, rules via `%` with recursion.

Gaps (the design surface):
1. Call shape: no arg-map form `{:query ... :args [...]}`; no db-first `(q db arg-map)`.
2. Return shape: lazy result value + explicit `collect` vs client.api's realized return.
3. No `:offset`, `:limit`, `:timeout`; no `:ret :mapping`.
4. Aggregates: only count/count-distinct/sum/min/max/avg (:1532). Datomic has
   ~20 (median, variance, std-dev, sample, rand, min/max-n, distinct, ...).
5. Builtins (~30 at :926) lack get-else, missing?, fulltext; predicates/fns
   resolve via `{:fns {...}}` opts, not peer-registered database-function symbols.
6. Inputs are dao.space query values (relation values, `current`/`history`
   views, `open-published!` indexes); raw vectors/maps rejected. No entity API.
7. Stricter than Datomic: bare symbols in :in/:find refused; kind-strict
   numerics (JVM BigInt/Long, 1.0M vs 1.0M, 0.0/-0.0 content-distinctness —
   see test/dao/space/query_numeric_test.cljc); required-bound rule head vars
   throw "not implemented" (:1424); pull recursion deferred.

Tests today: test/dao/space/query_test.cljc (74 deftests), query_numeric_test.cljc,
positional_query_test.cljc. Design docs: docs/design/dao.space.query.md,
docs/design/dao.space.index.md.

## Read first

- docs/design/datom.world.md (foundational axioms, non-negotiable invariants)
- docs/design/dao.space.query.md (the query engine's contract + "Index
  Realization" laziness rationale)
- docs/design/dao.space.index.md (published index / why q returns a result value)
- src/cljc/dao/space/query.cljc (the implementation under design)
- test/dao/space/query_test.cljc (current contract, incl. result-value tests
  q-returns-a-result-value / collect-materializes-*)
- docs/design/dao.jing.cbor.md + test/dao/jing/cbor_conformance_test.cljc
  (the repo's precedent for conformance-suite-driven compatibility work)
- docs/agents/roles/architect.md (this seat's charter)

## Required decision points — state your call and reasons on each

1. **Adapter vs mutation.** A new namespace (e.g. dao.space.query.client-api
   or dao.space.datomic) implementing the client.api/q contract on top of the
   existing lazy core, vs changing q's signature in place. Existing callers
   must not break: grep the tree (yin/vm/ledger.cljc:149, yin.repl lanes,
   dao.space.schema/dht use q/match/pull). The result-value design is
   deliberate — decide how realized returns reconcile with lazy published
   relations (eager materialization per call? bounded fetch honoring :limit?
   streaming?). Preserve the invariant that q owns no durable state and
   never opens/closes inputs.
2. **Scope of compatibility.** Partition datomic.client.api/q's contract into:
   (a) implementable exactly (arg-map form, :args, :ret :coll/:mapping,
   :offset/:limit, :keys/:syms/:strs passthrough, string+map+vector query forms?),
   (b) implementable with documented semantic differences (:timeout on a pure
   synchronous interpreter; :limit semantics vs Datomic's streaming), and
   (c) declined/inapplicable (entity-API db values, tx-queue inputs). Give
   the owner an explicit accepted boundary.
3. **Builtins and aggregates.** Whether get-else/missing? and the missing
   aggregates enter the core builtins/aggregate tables (portability + canonical
   CBOR + kind-strict numeric constraints apply) or are adapter-supplied via
   :fns. Name the exact set you would add and any numeric-portability hazards.
4. **Conformance strategy.** How compatibility is proven: a Datomic client.api
   conformance suite (like the CBOR corpus precedent) with which fixtures; a
   decision test per contract point; where the fixtures live.
5. **Docs/ADR impact.** Status-header updates to dao.space.query.md; whether a
   new design doc (e.g. dao.space.query.datomic-compat.md) or an ADR is
   warranted; naming of the adapter namespace that best fits the vocabulary.

## Deliverables

1. **Implementation plan** — phased, each phase with testable acceptance
   criteria and file-level ownership (namespaces to add/change, tests to add),
   ordered to keep master green throughout. Include an invariants analysis
   (no hidden global state, no implicit control flow, no layer collapsing)
   and CLJ/CLJS/CLJD portability notes (jolt lane exclusions exist for
   socket/fs suites — the query core itself is expected to stay portable).
2. **ETA to implement** — per-phase and total, with the sizing assumption
   stated (which implementation lane: e.g. gpt-6.1-sol or claude-opus on this
   repo's delegated-engineering pattern, review by fable/argon per routing).
   Calendar-time is welcome but agent-effort estimates are the required unit.
3. **Explicit non-compatibility list** — what will never match client.api/q
   and why, so the owner can accept the boundary knowingly.

Constraints:
- Read-only: do not edit files.
- The 6 non-negotiable invariants bind; no layer collapsing (client.api shape
  must not leak into the lazy core's contract).
- Existing callers stay green; the plan keeps master testable at every phase
  (bb test:clj / test:cljs / test:cljd lanes exist).
- Private repo — no external calls; work from the local tree only.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: <thread id>

Then report the implementation plan, the ETA, the non-compatibility boundary,
and the decision-point calls. The orchestrator will promote this response to
collab/<timestamp>-architect-dao-space-query-client-api-compat.gpt-6-astra.findings.md.
