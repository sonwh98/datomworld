Created-GMT: 2026-09-08 12:03:02 GMT
Created-Local: 2026-09-08 19:03:02 +0700 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 98b2c597-f191-4430-aa68-56a7536f500d
# Task: remove the two v1 bridges from dao.space.query (P1 fix round)
Role: Storage & Indexing Engineer
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-08 19:03:02 +0700 | Status: active | Rationale: same session that implemented P1; resumed for the review fixes

Your P1 landed and I verified it independently: clj 1423/165253, cljs
1334/34839 (1 pre-existing wasm error), cljd 1287 all passed, `:demo` build
0 warnings. `gpt-5.6-sol` then reviewed it and returned **request changes**
with two P1 findings, both on the schema-compatibility scaffolding, not on
the migration itself. Its full report is
`collab/review-space-query-v2.gpt-5.6-sol.findings.md` — read it first.

**The owner has since ruled on this directly: "what is legacy-v1-realization?
there should be no legacy."** That settles finding 1; it is not open for
counter-argument. Both bridges come out.

## Fix 1 — delete the legacy v1 realization path

`query.cljc:190-198,224-249`. `legacy-v1-realization?` is a negative type
test, not a capability check, so any unrecognized host object (a Date, an
atom, a delay, a host array, an exception) is classified as a v1 reader and
drained through `index/snapshot-datoms` instead of receiving I1's rejection.
It also makes query behaviorally dependent on v1 traversal hidden behind
`dao.space.index`, weakens Decision 3 (a live stream enters query only
through `snapshot`), accepts open v1 streams the old `validate-borrowed!`
rejected, and behaves differently per host.

- Delete `legacy-v1-realization?`.
- Delete the realization branches in `current` and `history`; both route
  every source through `db-source`.
- Fix the schema tests that relied on it —
  `test/dao/space/schema_test.cljc:523-531` and `599-610` — by draining
  schema-side: `(query/relation (ds/strict-vec realization))`, which is
  where `dao.stream` already lives. Keep what each test actually pins;
  `borrowed-path-does-not-close-again` must still prove schema never closes
  a borrowed source, so do not make it vacuous — if draining it schema-side
  would make the assertion prove nothing, say so in your report rather than
  landing a test that asserts nothing.

## Fix 2 — take v1 transport typing off query values

`query.cljc:153-163,214-221` and `schema.cljc:228-239,327-348`. The
vestigial `:dao.stream/type` on relation and view values re-imports exactly
the transport typing Decisions 2 and 4 remove, and makes unrelated v1
descriptor code classify non-openable values as transports.

- Remove `:dao.stream/type` from `relation` and `view-value`.
- In `schema/current`, accept either `(query/value? d)` or a legacy
  descriptor carrying a keyword `:dao.stream/type`.
- Make `validate-not-nested-view!` inspect `:dao.space.query/view` for query
  values, keeping its `:dao.space.schema/current` check for legacy schema
  descriptors.
- `test/dao/space/schema_test.cljc:538-541`: the reviewer found its
  bound-inheritance assertion passes only because both source and result have
  a missing/nil bound. Revise it to assert something real, or delete it and
  say why.

## Cleared, do not touch

The reviewer verified and cleared: the three-region schema deviation and its
`:as-of`/`:schema-as-of` behavior (the require cycle is real — `schema`
already requires `query`), `snapshot` against `observe/step` on every
status, `open-published!`/`close-published!` ownership and the failure path,
every dropped `[T✗]` invariant, the `dao.stream.relation` deletion, the
evaluator and lazy published path, and the cross-host reader conditionals.
Leave all of it alone.

## Ownership and verification

Same file ownership as your P1 brief, plus `test/dao/space/schema_test.cljc`
for the three test sites named above. Nothing else.

Re-run in full and report counts: `bb test:clj`, `bb test:cljs`,
`bb test:cljd`, `clj -M:cljs -m shadow.cljs.devtools.cli compile demo`, and
kondo on the files you touched. The `:demo` build must stay clean —
`public/demo.html` working is the owner's hard constraint. Expect the one
pre-existing cljs error (`wasm-eval-emits-telemetry-test`); it is not yours.

Write your report to
`collab/storage-space-query-v2-r2.glm-5.3.findings.md` with the
Completed-GMT/Local, Coding-Agent, Session-ID header. Do not stage or commit.
