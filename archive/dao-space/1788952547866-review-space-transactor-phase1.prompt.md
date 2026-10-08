Created-GMT: 2026-09-09 11:15:47 GMT
Created-Local: 2026-09-09 18:15:47 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a080de-1a15-7d23-9a35-4106b127e4f0
# Task: review Phase 1 of the transactor+index migration
Role: Routine Review

**Read-only. Print to stdout; write nothing.**

Phase 1 is implemented by `glm-5.3`, uncommitted:
`git diff` on `src/cljc/dao/space/index.cljc`,
`test/dao/space/index_test.cljc`, `docs/design/dao.space.index.md`.

Plan: `collab/1788950282826-architect-space-transactor-v2-plan-r5.claude-fable-5-1.findings.md` §4
Implementer's report: `collab/1788950986527-storage-space-transactor-phase1.glm-5.3.findings.md`

## The acceptance criterion

Phase 1 is **behaviour-neutral**. An earlier draft claimed neutrality while
restructuring `snapshot-datoms` into drain-then-flatten — which changes which
failure wins when both a malformed element and a later malformed signal exist,
and reads past where the old code stopped. You caught that; the plan now keeps
the incremental loop and keeps invariant S8 rather than dropping it.

## Structural checks I ran

- `index_test.cljc`: **63 insertions, 0 deletions** — purely additive, so no
  existing test was modified.
- `index.cljc`: 13 insertions, 2 deletions. The only change inside
  `snapshot-datoms` is the callee rename
  `stream-payload-datoms` → `element-datoms`.

## Lanes I ran on this exact tree

- `bb test:clj` — 1432 tests, 165341 assertions, 0 failures, 0 errors
- `bb test:cljs` — 1342 tests, 34913 assertions, 0 failures, 1 pre-existing
  wasm error; `Testing dao.space.index-test` present
- `compile demo` — 212 files, 0 warnings
- `bb test:cljd` — running; I will not report readiness without it

Do not rerun them.

## Judge

1. **Is it genuinely behaviour-neutral?** The diff is small enough to settle
   this by reading. `into` with `mapcat` is eager, but confirm the throw
   ordering inside `snapshot-datoms` is bit-for-bit what it was.
2. **`datoms-from-elements`** — is the public function correct, and does its
   docstring claim exactly what it does? It has no `src` caller by design.
3. **The new test** — does it pin S2, S3, S6, S7 as the plan says, or merely
   exercise them? Its equivalence check against `snapshot-datoms` is the part
   I would attack.
4. Anything that only compiles because Phase 2 will land later. That would be
   a defect: each phase must stand alone.

State plainly whether Phase 1 is ready to commit.
