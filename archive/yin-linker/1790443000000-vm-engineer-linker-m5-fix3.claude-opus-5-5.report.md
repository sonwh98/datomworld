# Report: yin.vm.linker M5 fix3 -- retained-line replay stops at a new pending require

Implementer: claude-opus-5-5 | Worktree /Users/sto/workspace/datomworld-m5
(branch m5) | Uncommitted; nothing committed, merged, or pushed.

## P1 -- confirmed and fixed

I checked codex's finding against the code and agree with it. `fold-queued`
reduced over every retained line with no check, so a retained
`(require 'other)` that parked set a new `:pending-run` (with empty
`:pending-lines`) and the later lines then ran against that parked
shell. `eval-parsed` also ran the triggering line after the fold without
checking whether the fold had parked the shell again.

Fix (src/cljc/yin/repl.cljc only, three hunks):

- `fold-queued` (repl.cljc:1164) is now a `loop`. Once the state carries
  a `:pending-run`, it stops and appends the unreplayed lines, in order,
  to that run's `:pending-lines`.
- `resume-pending` (repl.cljc:1184) queues the triggering `input` itself:
  - On completion it folds `(conj pending-lines input)`, so the triggering
    line is just the last queued line. If an earlier line parks, the
    triggering line stays retained behind it.
  - On a refusal it folds `[input]` against the rolled-back base. This
    keeps the old behavior: the retained lines are dropped and reported,
    and the line just typed still runs.
- `eval-parsed` (repl.cljc:1235) now just calls `resume-pending`. Its
  second, unchecked `eval-parsed*` is gone.

Net: +8 lines in production code, no new state keys, and no change to
the `:pending-lines` shape.

Each queued line still runs through `eval-parsed*`, which catches every
error itself. So the fold now sitting inside `resume-pending`'s `try`
cannot turn a line's own error into a link refusal.

## Regression test

`a-retained-require-that-parks-stops-the-replay-test`
(test/yin/repl/require_test.cljc):

- **Setup:** `mod` is pending over a silent wire. Three lines are
  retained: `(+ 1 2)`, `(require 'other)` and `(+ 3 4)`. The source is
  then swapped to one that serves `mod` but never hears the ask for
  `other`'s manifest. The triggering line is `(+ 5 6)`.
- **Asserts:**
  - `mod` completes, and `(+ 1 2)` runs exactly once: the value history
    is `[3 mod nil]`.
  - `7` and `11` are not evaluated.
  - The second pending state is kept: `repl-state` reports `other` and
    the VM is blocked.
  - `(+ 3 4)` and `(+ 5 6)` stay retained, in that order.
  - `(abandon)` raises `abandoned` and reports "dropped 2 lines".
  - The shell returns to the base the second require started from
    (`:last-value` 3).
- **Checked against the old code:** with my repl.cljc hunks temporarily
  reverted, the test fails. The retained lines come back as `[]`, and
  `(abandon)` reports no dropped lines, because the old code lost them.

Test rig: `withholding` (about 30 lines, portable `reify` of the four
dao.stream protocols, the same pattern as
test/dao/stream/waitset_test.cljc). It wraps a composition's content
request medium so the request for one address is never delivered. The
far end holds the content but never hears the ask, so that link uses up
its budget and stays `:pending` while every other link over the same
source completes. I found no simpler way to get this: every answer a
`:local` source gives is final, so a missing manifest is refused as
`:absent`, not left pending. `cljstyle fix` added the blank lines inside
the `reify`.

## Non-blocking item -- done: real late child response

I rewrote `an-install-wait-abandons-explicitly-test` without the
synthetic `:install` wait. `publish-module` gains an optional manifest
`overlay`, which the other tests leave out.

- **Setup:** `foo`'s body does `(require 'bar)`, and its manifest
  declares `bar` under `:yin.module/requires` and `require` under
  `:yin.module/primitives`. The `withholding` source hides `bar`'s
  manifest. So `(require 'foo)` links `foo`, and the install child parks
  on its own `bar` link while the root waits on `[:install]`.
- **What the test does:** runs `(abandon)`, then gives the shell a source
  that answers everything and requires `foo` again.
- **Asserts:**
  - The abandonment is raised, and the child origin carries onto the
    base.
  - The pair's requests are `foo bar foo bar`, and all four ids are
    distinct.
  - The late answer to the abandoned child's request is skipped:
    `{:kind :unknown, :id <child's id>, :entry <next id>}`.
  - The new `foo` links through a child of its own.
- **Mutation check:** with the `:origins` carry in
  `carry-link-identity` disabled, the test fails. The second child
  reuses the abandoned child's id, so the ids come out as 4 requests
  with 3 distinct.

## Verification

- `yin.repl.require-test` (JVM): 8 tests, 80 assertions, 0 failures.
- Full JVM suite (`clojure -M:test`): 2202 tests, 182790 assertions,
  0 failures, 0 errors.
- kondo (`clojure -M:kondo`) on both changed files: 0 errors,
  0 warnings.
- cljstyle clean. ASCII only, 80 columns.
- ClojureDart: no reader conditionals added, no private var-quote, and
  the `reify` has the same shape as the waitset test's. I did not run a
  cljd compile. The Node and Dart lanes are the orchestrator's.
