Created-GMT: 2026-09-09 14:25:02 GMT
Created-Local: 2026-09-09 21:25:02 +0700 (Asia/Bangkok)
Coding-Agent: deepseek
Session-ID: 7bf6a403-74d3-4a94-91a7-4b7eb55a7e72
# Task: adversarial review of the dao.space.schema v2 migration plan
Role: Adversarial Review
Implementers:
- Model: deepseek-v4-pro | Assigned: 2026-09-09 21:25:02 +0700 | Status: active | Rationale: Adversarial primary per team.md; independent of the Claude family that authored the plan; found the eleventh read path and the vacuous-assertion bug in the index/transactor sweep

**Read-only. Print to stdout; write nothing.** A routine review by
`gpt-6-astra` runs in parallel; do not coordinate with it.

You reviewed the transactor and index phases of this same sweep. This is the
**last namespace** — `dao.space.schema` — and unlike those, it is a *plan*,
not a diff. Nothing is implemented. Attack it now, while it costs one review
instead of a phase.

Plan: `collab/1788962937302-architect-space-schema-v2-plan.claude-fable-5-1.findings.md`
Design: `docs/design/dao.space.schema.md` (§3.1, the D10 rule),
`docs/design/dao.stream.md` (*Explicitly Absent*),
`docs/design/dao.space.transactor.md` (T20, and D8 which this plan reverses).
Code: `src/cljc/dao/space/schema.cljc`, `test/dao/space/schema_test.cljc`,
`test/dao/space/schema_fixtures.cljc`.

## Verified by me — attack past these

`ds/` counts 16 (`schema.cljc`) and 68 (`schema_test.cljc`); the 61/2 split
of `ds/close!` sites is exact; `:615` is prose; `query/value?` answers true
for `:dao.space.query/published` and `schema/current` already tests it;
`tx/publish!` has no closed check; no `src/` file requires
`dao.space.schema`. The plan's five corrections to my brief are all correct.

## Hunt these shapes

1. **A guard deleted on a plausible argument.** D7 removes `publish!`'s
   closed guard *and* its lock — the guard the previous plan's D8 had just
   moved under that lock to close a race. The argument is that the guard
   created the race. Is that true, or does deleting it expose a different
   window: a `publish!` racing a `close!` that is closing the *inner*
   transactor while `index/publish-index!` reads the local stream and
   enqueues into the intake pool? Who owns the pool after close? Construct
   the interleaving if one exists.
2. **A throw introduced under a migration.** D4 makes `schema/current`
   throw on a `:gap`/`:defect` snapshot where query returns data. The
   reasoning is that a lost prefix silently disables card-one collapse. Is
   the throw the right instrument, is `:blocked` genuinely safe to accept,
   and can a caller construct a source that passes D4's check yet still
   lacks the vocabulary?
3. **A property deleted rather than moved.** Seven deftests die. The plan
   names a surviving pin for each. You caught exactly this shape in Phase 3
   by asking what nobody had written down. Which of the seven properties
   leaves the suite entirely? Look hardest at
   `publish-serializes-against-close` (D7) and both borrowed-path tests (D6).
4. **A test that would pass vacuously.** Five tests are specified but not
   written. For each, would it fail if the property it names were violated —
   in particular D1's `closed-check-precedes-argument-validation` and D7's
   `publish-after-close-reads-the-callers-stream`?
5. **State that survives its owner.** The wrapper becomes a plain map holding
   an atom, losing the `deftype`'s encapsulation. `transact!` plans the next
   state before one inner append and installs it only on `ok` (T19, the
   invariant most at risk from the rewrite). Can the map form let a caller —
   or a concurrent transaction — observe or corrupt a half-installed state
   the `deftype` prevented?
6. **The unstated cost of D5.** `PublishedSchemaRows` closed its store before
   returning; query's `open-published!` hands the store to the caller. Every
   `schema/current` over a published index now leaves a store open until the
   caller closes it. Does the plan's V14 actually pin that, and is there a
   path where the store leaks?

## Report

Print to stdout, ordered by severity, each finding with the interleaving,
input, or omission that makes it real. Say plainly where the plan is right —
you cleared Phase 2's code outright once and that was the correct call.
