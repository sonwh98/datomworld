Created-GMT: 2026-10-05 15:57:21 GMT
Created-Local: 2026-10-05 22:57:21 +07
Coding-Agent: claude
Session-ID: 2e396723-5628-413b-b406-88d743d2aa93

# Task: head-h1

Role: DaoSpace and DaoJing Storage Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-05 22:57:21 +07 | Status: active | Rationale: complex agentic coding per team.md; Claude-family author, so a different-family reviewer (gpt-6.1-sol) gates it before commit

Implement slice **H1 (the follower, over any reader)** of the published head trace
in /Users/sto/workspace/datomworld/.claude/worktrees/head-h1 (a git worktree on top
of H0: `src/cljc/yin/vm/linker/head.cljc` with `trace`, `verify`, `seq-of`, `judge`
already exists and is reviewed; do not weaken it).

## Read first

- docs/design/yin.vm.linker.dht.head.md: sections 5.1 to 5.9 (above all 5.5
  reader behaviour, 5.6 refresh, 5.7 persistence), section 6 (the plain API
  table), 7 (limits), 9/10 (failure modes), 11 slice H1 (**your acceptance
  criteria: implement EVERY bullet under H1**), and 12 (what is deferred: do not
  build it).
- src/cljc/yin/vm/linker/head.cljc (H0), src/cljc/dao/space/dht.cljc (`load`,
  `load-index`, `forget`, `loaded-indexes`, `advance-loads`, `step`, `announce!`,
  the ledger ring), src/cljc/dao/jing/content/step.cljc (`abandon` line ~736 and
  `retire` ~708 already exist), src/cljc/yin/vm/linker/dht.cljc (`snapshots`),
  src/cljc/yin/repl/query.cljc (`dht-answer` ~636, `publication-call`),
  src/cljc/yin/repl/dht.cljc, and the existing tests
  `test/dao/space/dht_test.cljc`, `test/yin/repl/dht_test.cljc`,
  `test/yin/vm/linker/dht_test.cljc`, `test/yin/vm/linker/head_test.cljc`.

## What to build (files from the design)

- `src/cljc/yin/vm/linker/head.cljc`: add `board`, `deposit!`, `follow`, `attach`,
  `step`, `install`, `heads`, `records`, `moved` (section 6), keeping H0's four
  functions unchanged.
- `src/cljc/dao/space/dht.cljc`: `abandon` (remove a `:loading` record, emit no
  event, refused for a record that is not loading), the kind-conflict refusal
  (`:dao.space.dht/kind-conflict`, replacing today's silent no-op when an address is
  already recorded under another kind), and the wider client step (`advance-loads`
  steps the content client whenever it holds anything, not only while a load is
  active), using `content.step/abandon` and `content.step/retire`. `forget` is
  UNTOUCHED: `forget-clears-a-terminal-record-and-is-refused-while-loading` must
  pass as written.
- `src/cljc/yin/vm/linker/dht.cljc`: `snapshots` adds installed heads; candidates are
  never in a snapshot set.
- `src/cljc/yin/repl/query.cljc`: both host load operations (`load-index`,
  `load-module` in `dht-answer`) answer a `:dao.space.dht/refused` of ANY code as the
  call's error value, so the interpreter never throws; tested through the host
  module.
- Tests: `test/dao/space/dht_test.cljc`, `test/yin/repl/dht_test.cljc`, new
  `test/yin/vm/linker/head_follow_test.cljc`. Migrate (and name in your report) any
  existing test that loads one address under two kinds and relies on the old no-op.
- The amendments the design lists for H1 (`docs/design/yin.vm.linker.dht.md`
  sections 1, 7.2, 9, 10 and 13: D2, `abandon`, the refusal and its failure-vocabulary
  row; and a pointer in `docs/design/dao.jing.dht.md` section 1). Match the style of
  those documents and keep ASCII, 80 columns.

No transport in this slice: in every test the reader is handed the publisher's board
ring DIRECTLY as its reader handle, and blobs travel over the existing mesh seam of
`test/dao/space/dht_test.cljc`.

## If you cannot finish everything

Land a coherent prefix, in this order, and report exactly what remains (do not
claim more than you did): (1) `dao.space.dht` `abandon`, the kind-conflict refusal and
the wider client step, with their tests; (2) the `query.cljc` refusal translation with
its host-module test; (3) `snapshots` and the installed-head listing; (4) the follower
in `head.cljc` with `head_follow_test.cljc`; (5) the document amendments.

## Scope and process rules

- Work only in the files named above; if a dependency needs another file, stop and
  ask. Preserve unrelated changes; do not weaken any existing test.
- **No git commands.** No formatter or cljstyle (the orchestrator formats before
  commit). Verify in the **foreground** with focused JVM runs, for example
  `clj -M:test -n dao.space.dht-test -n yin.repl.dht-test -n yin.vm.linker.head-test
  -n yin.vm.linker.head-follow-test -n yin.vm.linker.dht-test`, and
  `clj -M:kondo --lint <files>`. No background processes. **No Node or Dart runs**;
  the orchestrator runs them at landing and reports any host failure back.
- Portable `.cljc` only. ClojureDart traps: `#?(:cljd nil :clj ...)` with `:cljd`
  FIRST for JVM-only code; no `0.0` literals in portable tests (the integer 0 on
  JS); EDN with no whitespace before a closer (`heads.edn`); `(- x)` is `0 - x` on
  Dart; no duplicate `_` protocol parameters; no `#'ns/private` across namespaces.
- `judge` and `verify` must stay total (never throw). Your follower must not throw on
  any trace a reader handle yields.
- If the design contradicts the code or itself, STOP that item and say so with
  file:line; do not silently diverge.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 2e396723-5628-413b-b406-88d743d2aa93

Then report: changed files; the exact commands and outcomes with assertion counts;
every H1 criterion with done / partly / not done and the test that pins it; every
return shape you chose; existing tests you migrated; unresolved concerns; and any
incomplete work. Do not claim edits or tests that did not occur.
