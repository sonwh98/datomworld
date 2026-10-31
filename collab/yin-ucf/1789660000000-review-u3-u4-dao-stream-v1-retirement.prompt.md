Created-GMT: 2026-09-17 13:33:20 GMT
Created-Local: 2026-09-17 20:33:20 +0700 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: pending (provider-generated)
Role: Adversarial Review

Implementers:
- Model: gpt-6-astra | Assigned: 2026-09-17 20:33:20 +07 | Status: active | Rationale: fresh third family reviewing two different authors (claude-opus-5, glm-5.3) in one diff

# Task: Review U3 and U4 of dao.stream.v1-retirement.implementation-plan.md

Read `docs/design/dao.stream.v1-retirement.implementation-plan.md` in full,
especially D4/U3 and D5/U4, and `docs/design/dao.stream.md` for the v2
contract's invariants. Also read `docs/design/dao.postgraphics.md` (the
terminal protocol's signal vocabulary) and `docs/design/dao.gui.event.md`.

## What changed

`git status --short` shows 17 modified files plus one new file
(`test/dao/gui/event/scripted.cljc`). Two independent implementers worked
concurrently on disjoint regions of a shared working tree:

- **U3** (claude-opus-5): rewrote `src/cljc/dao/postgraphics/terminal.cljc`
  from waiter-registration to a step-driven binding (D4); host tickers in
  `src/cljd/dao/postgraphics/flutter.cljd` (`Timer.periodic`) and
  `src/cljs/dao/postgraphics/web.cljs` (`setInterval`); frame-stream
  `ds/open!` -> `ringbuffer/create!` conversions in six demo files plus the
  *frame* stream only in the two `artifact.*` files; rewrote
  `test/dao/postgraphics/{terminal,web}_test.cljc`; updated
  `docs/design/dao.postgraphics.terminal.md`.
- **U4** (glm-5.3): ported `src/cljc/dao/gui/event.cljc`'s `advance`,
  `flush-pending`, and `bind` to v2 outcome maps and cursors (D5); added
  `test/dao/gui/event/scripted.cljc` (a scripted-handle fixture replacing
  `drain-one!`); rewrote `test/dao/gui/event/bind_test.cljc`; moved the
  input/output/signal streams (not frame) in the two `artifact.*` files to
  `ringbuffer/create!`; rewrote `docs/design/dao.gui.event.md`'s
  backpressure section.

Verified independently by the orchestrator, not trusted from either
implementer's report: the two implementers' regions of the shared
`artifact.cljs`/`artifact.cljd` files are confirmed disjoint (U3 touched
only the frame-stream lines; U4 touched only input/output/signal). A grep
sweep for v1 mechanism names (`register-*-waiter!`, `drain-one!`,
`bind-stream!`, etc.) across every touched file returns only false
positives (the binding's own local `:closed?` state key, not a v1 stream
predicate). `clj -M:test` -> 1358 tests, 165697 assertions, 0 failures, 0
errors. `bb test:cljs` -> 1279 tests, 35253 assertions, 0 failures, 0
errors, 0 warnings. `bb test:cljd` (after `rm -rf test/cljd-out`) -> 1242
tests, all pass.

**Not verified by anyone**: manual Flutter and browser smoke checks (Solar
System/Earth-Moon/Voxel/dao.gui Prototype animating correctly, `#artifact`
drag/keyboard interaction, visual pause behavior). Neither implementer nor
the orchestrator has a running simulator/device or a headless browser in
this environment. `flutter analyze` was attempted as a substitute but
returned ~11,600 issues that are noise from generated `test/cljd-out`
scaffolding paths, not a meaningful signal — abandoned. Treat this as a
named, open gap, not something to paper over with static analysis.

## An unresolved spec question — the review's main job

U3's implementer set the terminal's `:error/kind` (in the
`:dao.terminal/protocol-error` signal) directly to the raw v2 stream
outcome keyword (e.g. `:dao.stream/end`) on any non-ok, non-gap outcome
from `step`. But `docs/design/dao.postgraphics.md` states explicitly: "For
v1, signal keyword vocabularies are constrained... Protocol Error
`:error/kind` MUST be one of `:future-frame-tap`, `:out-of-order-frame-events`,
or `:stale-generation-tap`" — three keywords describing tap/generation
*ordering* violations, none of them a transport failure. v1's terminal
never received a transport-level `:end`/`:error` from its ring buffer in
the same way v2's contract allows, so this is genuinely new territory the
spec doesn't cover, not a rule the implementer broke by carelessness — it
said so itself rather than quietly picking an answer.

## Task

1. **Judge the `:error/kind` question.** Is reusing the raw stream outcome
   keyword the right interim answer, given the spec's vocabulary doesn't
   have a slot for it? Or should this be blocked pending a documented
   vocabulary extension (a new `:error/kind` value, e.g.
   `:dao.terminal/transport-error`, with `dao.postgraphics.md` updated to
   name it) before this can be considered done? State a recommendation.
2. **Judge the two other decisions U3's implementer flagged**: (a)
   `:submission-id` changed from a stream-position-derived concept to a
   pure terminal-local ingress counter (since v2 cursors are opaque and
   don't expose positions) — confirm this doesn't break any consumer that
   inspects the id's value, not just its presence; (b) both host widgets
   keep the `:signal-stream` option name (unchanged) even though it's
   passed through as `:signal-handle` internally — confirm no caller in
   U4's or elsewhere's code was relying on the old internal name.
3. **Confirm D4's step-driven binding actually eliminates the v1 waiter
   mechanism** in `terminal.cljc`, `flutter.cljd`, and `web.cljs` — no
   callback registered anywhere, cadence owned solely by the host ticker.
4. **Confirm D5's `advance`/`flush-pending`/`bind` changes in `event.cljc`
   match the plan's disposition exactly** — outcome-map dispatch, the
   recovery-cursor-adoption rule on `gap`, the new `:transport-error`
   status on terminal outcomes, the origin-cursor rule in `bind`.
5. **Confirm the two `artifact.*` files' split is real** — re-derive the
   disjointness yourself from the diff, don't just trust the orchestrator's
   claim above.
6. **Spot-check the new `test/dao/gui/event/scripted.cljc` fixture** —
   does it actually exercise `full`-then-`ok` parking and a `transport-error`
   path as the plan's fixture rule requires, without depending on any v1
   destructive-take mechanism?
7. Independently re-run or spot-check the test-suite claims above if
   useful, or trust them and spend your budget on static review — your
   choice, state which you did.

## Deliverable

A findings list, most severe first, and an explicit verdict: ready for
Architect sign-off, or not (with what must resolve first — call out
explicitly whether the `:error/kind` question alone should block sign-off).
Do not edit any file.
