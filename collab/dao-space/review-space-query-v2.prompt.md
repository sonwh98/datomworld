Created-GMT: 2026-09-08 11:51:58 GMT
Created-Local: 2026-09-08 18:51:58 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a080de-1a15-7d23-9a35-4106b127e4f0 (captured from thread.started)
# Task: review the dao.space.query v2 reimplementation (P1)
Role: Routine Review
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-08 18:51:58 +0700 | Status: active | Rationale: Routine Review primary per team.md; independent of the GLM implementer and the Claude architect

**Read-only review.** Repository `/Users/sto/workspace/datomworld`, branch
`dao.stream-redesign-v2`, base commit `78b5262`. The change is uncommitted:
`git diff` plus the deletion of `src/cljc/dao/stream/relation.cljc`.

## Context

The project is migrating every consumer off v1 `dao.stream` onto
`dao.stream`, after which v2 is renamed to `dao.stream`.
`dao.space.query` was reimplemented on v2 by `glm-5.3` against
`docs/design/dao.space.query.implementation-plan.md` (the specification —
read it first; its invariant list I/V/E/L/R/O/S/C and Decisions 1-5 are the
contract) and `docs/design/dao.space.query.md` (the design it answers to).
The implementer's own report is
`collab/storage-space-query-v2.glm-5.3.findings.md`; read it, and treat its
claims as claims.

## The suites already pass; spend your budget on static analysis

The orchestrator ran these independently on the final tree, in full:

- `bb test:clj` — 1423 tests, 165253 assertions, 0 failures, 0 errors
- `bb test:cljs` — 1334 tests, 34839 assertions, 0 failures, 1 error
  (`wasm-eval-emits-telemetry-test`, pre-existing since `78b5262` left a
  cljs-only test behind when it removed the wasm backend; unrelated to this diff)
- `clj -M:cljs -m shadow.cljs.devtools.cli compile demo` — 212 files, 0 warnings
- `clj -M:kondo --lint` on query/schema/query_test — 0 errors; schema's one
  warning is pre-existing, verified against `HEAD`
- `bb test:cljd` — All tests passed, 1287 tests; `dao.space.query-test` ran on Dart

Do not rerun them. Review the code.

## Three things I want your judgment on specifically

1. **`legacy-v1-realization?` (query.cljc:190).** Query is v1-require-free,
   but `current`/`history` still accept an opaque v1 reader by draining it
   through `index/snapshot-datoms`. The predicate is a *catch-all negative
   type test* — "not nil, boolean, number, string, keyword, symbol, seq, set,
   map, vector or fn" — so any unrecognized object (a Date, an atom, a delay,
   a host array) routes into a v1 drain instead of the informative rejection
   invariant I1 requires. Its only caller is `schema_test`'s
   `borrowed-path-does-not-close-again` (schema_test.cljc:599), which hands a
   `deftype` `RecordingStream` to `query/history`. Is this bridge worth its
   blast radius, or should the test drain schema-side and the bridge be
   deleted? Say which, and why.
2. **The vestigial `:dao.stream/type` key on query values** (query.cljc:162,
   216). It exists so `schema.cljc`'s unchanged descriptor validation and
   nested-view check classify query values. Is that a sound temporary bridge
   with schema still on v1, or does it re-import the transport-typing that
   Decisions 2 and 4 exist to remove?
3. **The schema deviation.** The plan said two schema sites; three regions
   changed. The implementer argues the third is forced: `q` now opens
   nothing, so `schema/current`'s map branch must interpret eagerly, and the
   schema collapse cannot move into query without a require cycle. Verify
   that argument — is the cycle real, and is the eager dispatch correct for
   every path `schema_test` exercises, including `:as-of`/`:schema-as-of`?

## Also check, in the ordinary way

- `snapshot` (query.cljc:~305) against `dao.stream.observe/step`'s
  contract: cursor handling on every status, the effect's totality, that no
  cursor advances past an unretained value, and that the handle is never closed.
- `open-published!` / `close-published!`: ownership, the failure path that
  closes the store before rethrowing, and idempotent close.
- Invariants the plan marks `[T✗]` (dropped) — confirm each was dropped for
  the stated reason and not merely lost.
- Whether anything in the deleted `dao.stream.relation` had a consumer that
  now silently changed behavior.
- Portability across clj/cljs/cljd, including the reader-conditional trap:
  `#?(:clj ...)` alone does not exclude code from the cljd build.

## Report

Write to `collab/review-space-query-v2.gpt-5.6-sol.findings.md` with the
Completed-GMT/Local, Coding-Agent, Session-ID header. Rank findings by
severity, name file and line, and say plainly for each whether it blocks the
commit. If you find nothing blocking, say so.
