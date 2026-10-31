Created-GMT: 2026-09-16 15:07:59 GMT
Created-Local: 2026-09-16 22:07:59 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: agy
Session-ID: pending (provider-generated)

# Task: Review U6 — the yin.vm v1 deletion set (one atomic commit)

Role: Adversarial Review

Implementers:
- Model: gemini-3.1-pro-high | Assigned: 2026-09-16 22:07:59 +07 | Status: active | Rationale: cross-family review of the final, largest, most consequential unit in this plan

## Context

`docs/design/yin.vm.v1-retirement.implementation-plan.md`'s "### U6" and
"### D6" sections — read both in full first. This is the plan's final
unit: it deletes v1 `yin.repl`/`yin.vm`/telemetry servers now that U1, U2,
U4, U5 have all landed with v2 replacements. D6 explains why this must be
exactly one commit, never split.

The current uncommitted diff: **53 files changed, 90 insertions, 7275
deletions** (26 file deletions — the plan's named 25 plus one, explained
below — plus edits to `deps.edn`, `shadow-cljs.edn`, several test files,
and roughly 15 documentation files).

Verified independently by the orchestrator, not trusted from the
implementer's report: `clj -M:kondo --lint` on every edited code/config
file — clean. `clj -M:test` (full suite) → 1389 tests, 0 failures. `bb
test:cljs` (full suite) → 1289 tests, 0 failures, `demo` build clean. `bb
test:cljd` (full suite, `test/cljd-out` cleared first) → 1245 tests, all
pass. The R4 gate condition (`dao.runtime.implementation-plan.md`) —
independently confirmed: `engine.cljc` no longer exists, and nothing in
`src/` requires plain v1 `dao.runtime` except the three existing drivers.

**A real, pre-existing bug, confirmed independently by the orchestrator,
not introduced by this unit**: `bin/yin_repl_main.dart` calls
`repl.run_main(args)`, but `src/cljc/yin/repl.cljc:419` defines
`run-main` with `^{:dart/name main}` — meaning the actual generated Dart
function is named `main`, not `run_main`. The launcher calls the wrong
name. Confirmed via `git log --all --oneline -- bin/yin_repl_main.dart`
that this file was introduced before tonight's session (commit `5370bd1`)
and untouched by any of tonight's work. This blocks one of U6's own
stated completion criteria (`clj -M:cljd-yin-repl` starting) — the
implementer correctly did not fix it, since it's outside a deletion
unit's scope, but flagged it clearly.

## Task

This is a large, high-stakes review — the last chance to catch a problem
before an irreversible-in-spirit atomic commit (per D6, this unit is not
meant to be amended piecemeal afterward). Take real time on it.

1. **Verify the 25-file delete list matches the plan exactly** — cross-
   check `git status --short | grep '^ D'` against the plan's named list,
   confirm nothing is missing and nothing extra beyond the one disclosed
   departure (below).
2. **Scrutinize the one undisclosed-by-plan deletion**:
   `test/datomworld/demo/yin_repl_test.cljs`. The implementer's reasoning:
   once `yin_repl.cljs` (the file it tests) was deleted, the Node test
   build failed to compile (`"datomworld.demo.yin-repl" is not
   available`), and the file exclusively tested v1-only functions
   (`location->repl-url`, `queue-request`/`resolve-request`/
   `fail-active-request` — the v1 request queue). Confirm this
   independently: read the actual deleted test file's content (`git show
   HEAD:test/datomworld/demo/yin_repl_test.cljs` — it's gone from the
   working tree, use git to see it), confirm every test in it really only
   exercises deleted v1-only code, and confirm the v2 client
   (`yin_repl.cljs`, committed in U4) genuinely has no equivalent test
   coverage gap opened by this deletion.
3. **Scrutinize the three "departures from the plan"** the implementer
   disclosed:
   - Rewording two test assertions in `v2_core_test.cljc`/`v2_test.cljc`
     that checked the old telemetry-text wording (a direct, necessary
     consequence of the plan's own required text change — confirm it's
     not scope creep, just fixing an internal inconsistency the plan's
     own brief didn't anticipate).
   - Two comment fixes in files the plan explicitly said to leave alone
     (`rpc/client.cljc:9`, `v2/runner.clj`'s docstring) — both because
     they pointed to or described something now false after the
     deletion. Confirm each is genuinely a stale-reference fix, not an
     unrelated change.
   - Leaving `test/dao/test_utils.cljc`'s now-fully-orphaned
     `stream-values`/`fact?` helpers untouched, since the plan named only
     one helper (`make-waitable-retry-stream`) to remove. Is strict
     adherence to the plan's literal scope the right call here, or should
     genuinely dead code discovered mid-unit be cleaned up in the same
     commit? Give your own judgment.
4. **Verify the doc-drift handling is sound**, spot-checking at least
   3-4 of the ~15 touched documentation files against what they actually
   say now versus what the plan (plus the orchestrator's additional
   Phase-0-sweep findings folded into the brief) required.
5. **Judge the pre-existing CLJD REPL launcher bug's disposition.** Is it
   correct to let U6 land with this known, disclosed, pre-existing defect
   unfixed (recommend it as a tracked follow-up), or does a deletion this
   consequential need every stated completion criterion actually met
   before it's safe to commit? Weigh against the two other already-
   accepted gaps from U2/U4 (manual smoke tests neither could run here) —
   is this the same category of "accepted gap," or is a REPL that
   provably cannot start a more serious defect that should block?
6. **Confirm nothing was silently broken** beyond what's disclosed — pick
   at least 2-3 of the deleted files yourself and independently confirm
   via grep that nothing remaining in the tree still references them.

Do not edit any file.

## Deliverable

A findings list, most severe first, and an explicit verdict: ready for
Architect sign-off, or does something need fixing first. Given the
stakes, if you find anything that should block, say so plainly and
explain exactly what needs to change before this commit is safe.
