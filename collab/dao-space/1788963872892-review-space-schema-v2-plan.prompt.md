Created-GMT: 2026-09-09 14:24:32 GMT
Created-Local: 2026-09-09 21:24:32 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: pending (provider-generated)
# Task: routine review of the dao.space.schema v2 migration plan
Role: Routine Review
Implementers:
- Model: gpt-6-astra | Assigned: 2026-09-09 21:24:32 +0700 | Status: active | Rationale: owner routed this review to gpt-6-astra in place of the gpt-5.6-sol primary; independent of the Claude family that authored the plan

**Read-only. Print your review to stdout; write no file.** Repository
`/Users/sto/workspace/datomworld`, branch `dao.stream-redesign-v2`, clean at
`4b9f0e7`. An adversarial review runs in parallel; do not coordinate with it.

## What you are reviewing

`collab/1788962937302-architect-space-schema-v2-plan.claude-fable-5-1.findings.md` — a migration plan, not a diff. Nothing has been implemented. Your
job is to find what would make the implementation wrong, unbuildable, or
unreviewable *before* an implementer is briefed from it.

## Context

The project is moving every consumer off v1 `dao.stream` onto
`dao.stream`, after which v2 is renamed to `dao.stream`
(`docs/design/dao.stream.md`, *The v2 namespace is transient*).
`dao.space.query`, `dao.space.transactor` and `dao.space.index` are done;
`dao.space.schema` is the last `dao.space.*` namespace on v1.

Read as needed:
- `docs/design/dao.space.schema.md` — the design, especially §3.1 and the D10 rule
- `docs/design/dao.stream.md` — the v2 contract, especially *Explicitly Absent*
- `docs/design/dao.space.transactor.md` — T20 and its open item, both of which this plan retires
- `docs/design/dao.space.query.md` and `git show 96ec78f` — the precedent the plan follows or departs from
- `src/cljc/dao/space/schema.cljc`, `test/dao/space/schema_test.cljc`, `test/dao/space/schema_fixtures.cljc`

## Verified by me — do not spend budget re-measuring these

`grep -c 'ds/'`: 16 in `schema.cljc`, 68 in `schema_test.cljc`. Of 63
`ds/close!` sites, 61 target wrappers (57 `w`, plus `reopened`,
`strict-wrapper`, `wa`, `wb`) and 2 target fixtures (`:307`, `:621`) —
the plan's §0.3 split is exact. `:615` is prose inside a comment.
`query/value?` (`query.cljc:142-150`) does answer true for
`:dao.space.query/published`, and `schema/current` tests it at `:337`/`:345`
— the plan's §0 observation holds. `query_test`'s
`close-published-closes-once-and-is-idempotent` (`:716`) and
`failed-open-closes-the-store-it-opened` (`:1076`) exist. `tx/publish!`
(`transactor.cljc:271`) has no closed check. No file under `src/` requires
`dao.space.schema` except `query.cljc:175`'s docstring.

Spend your budget on judgment, not on re-running greps.

## What to judge

1. **The two departures.** D4 has schema **throw** on a `:gap`/`:defect`
   snapshot where query lets the caller decide, arguing schema has a basis
   query lacks (schema rows live at the history's origin; a lost prefix
   silently degrades the view to pass-through with card-one collapse
   disabled). D7 **deletes** the `publish!` closed guard that the transactor
   plan's D8 had just placed under the wrapper lock, arguing the guard is
   what created the race. Are both reasoned or merely asserted? Is either a
   behavior change smuggled in under a migration?
2. **The D10 collapse.** `transact!` stops re-wrapping and returns the inner
   receipt. Does any caller-visible property depend on the `{:result :ok …}`
   shape beyond the 24 assertion lines the plan edits by rule? Is the
   ok/refused/closed/throws boundary after D1 coherent — in particular the
   claim that closedness is data while every argument defect still throws,
   and the pinned ordering between the two?
3. **Deletion accounting.** Seven deftests are deleted and five added. For
   each deletion, does the plan name a surviving pin for the property, and
   is that pin real — or does the property leave the suite? This is where
   migrations lose invariants.
4. **The phase split.** Phase 1 (write side) then Phase 2 (read side), with
   Phase 2's residue grep as the end condition. Does Phase 1 leave the tree
   green and coherent on its own? Four deftests are edited in both phases —
   is anything else double-touched or left in an intermediate state that
   does not compile?
5. **The proof lists.** Phase 1 predicts `ds/` counts of 16→11 and 68→6.
   Recompute those from the plan's own build/delete lists and say whether
   they follow. A proof list whose arithmetic does not close is a plan an
   implementer cannot self-check against.
6. **Whether anything is owed and unnamed.** §7 claims `dao.space.*` is
   entirely on v2 afterward and §8 claims nothing is owed to a later plan.
   Check both against the tree.

## Report

Print to stdout. Order findings by severity, each with the plan section it
attacks, the concrete failure it would cause, and what you would change.
Distinguish blocking defects from improvements. If a decision is sound, say
so plainly rather than manufacturing a finding; a short review that names one
real defect beats a long one that names none.
