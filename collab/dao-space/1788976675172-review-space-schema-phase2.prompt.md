Created-GMT: 2026-09-09 17:57:55 GMT
Created-Local: 2026-09-10 00:57:55 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a0868e-e9f2-7242-92e8-58d63e7f9574 (resumed)
# Task: review dao.space.schema Phase 2 — the read side
Role: Routine Review

**Read-only. Print to stdout; write no file.** You reviewed this plan through
r1/r2 and cleared Phase 1 as ready to commit. Phase 1 is committed (`bdbe6f9`).
**Phase 2 is now implemented** — review `git diff`.

Implementer: `glm-5.3` (resumed from its Phase 1 session), independent of you.
Plan: `docs/design/dao.space.schema.implementation-plan.md` **§5**
Report: `collab/1788975087538-storage-space-schema-phase2.glm-5.3.findings.md`

This phase ends `dao.space.*` on v1.

## Verified by me — do not re-run the suite

Every §5 closure grep, my measurements: `ds/` **0** in `schema.cljc` and **0**
in `schema_test.cljc`; the require/require-macros grep **empty** (final
requires: `dao.datom`, `dao.space.index`, `dao.space.query`,
`dao.space.transactor`); the deleted-name sweep across `src test docs/design`
**clean** apart from the plan file itself; `grep -rln "\[dao.stream :as"
src/cljc/dao/space` → **nothing**; deftests **72**; no orphaned
`schema_fixtures` output under `cljd-out`.

Lanes: clj **1436/165349 0 failures 0 errors**; cljd **+1299 all passed**;
demo **212 files 0 warnings**; cljs **1346/34922**.

**One thing you should know about the cljs lane.** Two of my five cljs runs on
this tree failed, each on a *different* test —
`schedule-work-does-not-leave-stale-poll-timer-after-poll-break-test`
(`dao/runtime/driver_test.cljs:162`) and
`unknown-paths-are-authoritatively-disclaimed`
(`dao/stream/ws/node_test.cljs:580`). I stashed Phase 2 and ran the
baseline three times (green), restored it and ran three more (green). Three
consecutive greens on the identical tree that had failed twice means these are
load-sensitive flakes, not a consequence of this diff — same family as the
three races `bafae86` removed from `yin.repl-test`. Neither namespace
depends on schema. I am recording it as separate work, not as Phase 2's.
**If you see a mechanism by which deleting `schema_fixtures` from the
`:node-test` bundle could actually perturb those two tests, say so** — that
is the one hypothesis I could not rule out by measurement alone.

Separately I deleted a dead deftest `wasm-eval-emits-telemetry-test` in
`test/yin/vm/telemetry_test.cljc` — `78b5262` removed its require and left the
body calling `wasm/create-vm`, so the lane carried a permanent error. It is
**not part of Phase 2** and will be its own commit; the diff you review
includes it, so judge it separately (cljs is now 1345/34921, **0 failures 0
errors**, for the first time).

## What to judge

1. **D4 implemented as a limit, not a guarantee.** `current` rejects
   `:gap`/`:defect`, accepts `:blocked`/`:ended`. **V15 asserts that an
   evicted prefix is accepted and both card-one values stay visible.** That is
   the contract, settled over three rounds — confirm the code and the test say
   what the design says, and that the docstring carries the limit.
2. **V14 pins ownership.** The counter on the opened store's `:close-fn`:
   zero closes after `current` returns, one after `close-published!`. Would
   it still fail if `current` closed the store early?
3. **V13a/V13b** — the scripted gap/defect readers and the accepted
   `:blocked`/`:ended` snapshots. Do they pin what they claim?
4. **The deletions** — both `defopen`s, `published`, `PublishedSchemaRows`,
   `schema_fixtures`, four deftests (V8, V10, V11→V14, P4). Does every
   deleted property have a surviving pin, or did one leave the suite? This is
   where you and the adversarial reviewer both found real defects before.
5. **The design edits** — `dao.space.schema.md` §4/§5/§7/§8/§9 and the status
   paragraph, `dao.stream.md`'s consumer list losing `dao.space`,
   `dao.space.transactor.md:16` losing the D10 clause. Do they describe the
   code as it now is?
6. **The three deviations the implementer disclosed** (W12's regex
   simplification, §8's "d5 descriptor" → "d5 source value", a status-sentence
   rewording). Justified, or does one change meaning?

## Report

Ordered by severity, each finding with the concrete failure it would cause.
Distinguish blocking from improvement. If it is clean, say so plainly and say
it is ready to commit.
