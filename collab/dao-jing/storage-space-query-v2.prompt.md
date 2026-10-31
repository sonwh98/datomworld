Created-GMT: 2026-09-08 10:57:17 GMT
Created-Local: 2026-09-08 17:57:17 +0700 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 98b2c597-f191-4430-aa68-56a7536f500d
# Task: reimplement dao.space.query on dao.stream (P1)
Role: Storage & Indexing Engineer
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-08 17:57:17 +0700 | Status: active | Rationale: Storage & Indexing primary per team.md; implemented P1/P2 of the dao.jing plan and knows the v2 contract and `observe/step`

**Implementation task with write authority**, bounded to the files listed under
*Ownership*. Repository `/Users/sto/workspace/datomworld`, branch
`dao.stream-redesign-v2`, clean at `78b5262` apart from uncommitted
`docs/design/dao.stream.md` and the new plan named below.

## Read these first, in order

1. `docs/design/dao.space.query.implementation-plan.md` — **your specification.**
   Written by the Architect, verified by the orchestrator against the tree,
   and authorized by the owner. Its invariants list (I, V, E, L, R, O, S, C)
   is the contract; its Decisions 1-5 are settled and not yours to reopen;
   its P1 section is your scope. P2 (the design-doc rewrite) is not yours.
2. `docs/design/dao.space.query.md` — the design the reimplementation answers to.
3. `docs/design/dao.stream.md` — the v2 contract, in particular *Explicitly
   Absent* and *The v2 namespace is transient*.
4. `src/cljc/dao/stream/observe.cljc` — `step`, which `snapshot` is built on.
   Its docstring states the cursor law: an element's cursor advances exactly
   when its disposition has been durably recorded.
5. `src/cljc/dao/space/query.cljc` (1503 lines) and
   `test/dao/space/query_test.cljc` (951 lines) — what you are replacing.

## The goal this serves

The project's primary goal is moving every consumer off v1 `dao.stream` onto
`dao.stream`, after which v2 is renamed to `dao.stream`. `dao.space.query`
is the next consumer, and it blocks the rest: it is what keeps a v1 require
alive in `yin.vm.semantic` and it is reached by the `:demo` build.

## The license, and its limit

The owner authorized reimplementing `dao.space.query` **from scratch** against
its design doc rather than editing the v1 file. Use that license on the
stream-facing surface — inputs, ownership, views, results, snapshot — which is
what Decisions 1-3 rewrite. **Do not use it on the evaluator.** Roughly
`query.cljc` 416-1475 (`match` through `q`'s evaluation, the planner, rules,
aggregates, `pull`) touches no stream; carry it over. Rewriting it would risk
hundreds of pinned invariants and buy the migration nothing. The plan's
"Build / Delete / Consumer call sites / Prove" lists under P1 are exact.

## Hard constraint

`public/demo.html` must keep working exactly as it does today. The chain is
`main.js` -> `datomworld.demo` -> `compilation_pipeline.cljs` ->
`semantic/find-by-type` -> `query/collect`, `query/q`, `query/current`,
`query/relation`. Those four keep their call shapes for value inputs (C1).
Verify by compiling the `:demo` build, not by inspection.

## One correction the plan already carries, do not miss it

Decision 4 deletes `src/cljc/dao/stream/relation.cljc`. Its only test consumer
is `test/dao/stream_test.cljc`: the whole `relation-descriptor-contract-test`
deftest (lines ~511-552) and the `[dao.stream.relation :as relation]` require
at line 5. Both go with the transport they pin. Nothing else in
`dao/stream_test.cljc` moves — it is v1's own test file and v1 still exists.

## Ownership

Write only:
- `src/cljc/dao/space/query.cljc`
- `test/dao/space/query_test.cljc`
- `src/cljc/dao/space/schema.cljc` — **two sites only**: 255-256 and 294
- `test/dao/space/stigmergy_test.clj` — the six `published-index` sites; **line
  177 stays as it is** (it drains the v1 transactor, which is not yours)
- `test/dao/stream_test.cljc` — the deletion named above, nothing else
- delete `src/cljc/dao/stream/relation.cljc`

Touch nothing in `dao.space.index`, `dao.space.transactor`, `yin.vm.*`,
`transact.cljc`, or `compilation_pipeline.cljs`. `index.cljc`'s
`ds/defopen :dao.space.index/published` is left standing, dead, for index's
own plan. The plan's *Left owing* section says what each other namespace owes;
do not do their work.

## Verification you must run and report

- `bb test:clj`, `bb test:cljs`, `bb test:cljd` — full, unfiltered. Report
  assertion counts, not adjectives. Confirm `Testing dao.space.query-test`
  appears in the Node output (shadow `:node-test` auto-discovers; absence of
  the line means the namespace did not run).
- `clj -M:cljs -m shadow.cljs.devtools.cli compile demo` — the `:demo` build.
- The cljd lane regenerates `test/cljd-out/`. If you delete a namespace, its
  stale generated Dart under `test/cljd-out/` and `lib/cljd-out/` must be
  removed too or the lane will run orphaned tests. This bit a previous phase.
- `clj -M:kondo --lint src/cljc/dao/space/query.cljc`.

Reader-conditional trap, since you are writing new `.cljc`: `#?(:clj ...)`
alone does **not** exclude code from the cljd build. Use
`#?(:cljd nil :clj ...)` with `:cljd` first.

## Report

Write your report to
`collab/storage-space-query-v2.glm-5.3.findings.md`, starting with
Completed-GMT, Completed-Local, Coding-Agent: glm, Session-ID: 98b2c597-f191-4430-aa68-56a7536f500d.
State what you built, what you deleted, the exact commands you ran with their
outcomes and counts, any invariant from the plan you could not honor and why,
and anything you left owing. Do not stage or commit; the orchestrator commits.
If your budget runs out mid-phase, leave the tree readable and say exactly what
remains rather than leaving a half-applied edit.
