Created-GMT: 2026-09-17 09:53:20 GMT
Created-Local: 2026-09-17 16:53:20 +0700 (Asia/Ho_Chi_Minh)
Coding-Agent: glm
Session-ID: 23e4c42e-7822-49da-9d68-5c8a231d1d0f
Role: Stream & Network Implementer

# Task: dao.stream v1-retirement, U4 — dao.gui.event on v2 (D5)

Read `docs/design/dao.stream.v1-retirement.implementation-plan.md` in full
first, especially D5 and the "U4" section, and `docs/design/dao.stream.md`
in full for the v2 contract (particularly the outcome-map vocabulary,
cursor rules, and why an in-process ring buffer cannot produce `full`).
Also read `docs/design/dao.gui.event.md` in full — it's larger than the
code change: the *code* is already close to v2 discipline, but its
*specification*'s backpressure section is written against a transport v2
refuses to build.

A separate, independent implementer is concurrently working on U3
(`dao.postgraphics.terminal` on v2) in the same working tree. Your files
and theirs overlap in exactly two places — `src/cljs/datomworld/demo/artifact.cljs`
and `src/cljd/datomworld/demo/artifact.cljd` — but on disjoint regions:
**you own the input, output, and signal streams** in those two files; U3
owns only the frame stream. Do not touch the frame stream or anything
that feeds the terminal binding in those two files. If in doubt about a
line, leave it and note it in your report rather than guessing at the
boundary.

## Why this is smaller in code than it looks

`dao.gui.event` is 839 lines; the binding driver was already written to
the v2 discipline ("creates no ambient singleton, host thread, callback,
or waiter registration"; `advance` reads at most one input per call and
retains the cursor as a value). Its v1 dependence is confined to four
places: `flush-pending`'s `{:result :full}` check, `close-outputs`,
`advance`'s read (`{:position 0}` initial cursor, bare-keyword/
`:daostream/gap` dispatch), and `bind`'s initial cursor. Re-check current
line numbers yourself — the plan's are from the 2026-09-17 sweep.

## Why it's larger in specification than it looks

`dao.gui.event.md`'s *Transport, Coalescing, And Backpressure* section
says "a conforming canonical runtime-input DaoStream uses reject/
backpressure rather than `:evict-oldest`" and specifies a parked interval
on `{:result :full}`. The v2 ring buffer is evict-oldest and never
returns `full` — this is settled elsewhere (the runtime plan's Divergence
register, row 6) as not a deferred variant: a reject-mode buffer with no
destructive take would be full forever. `bind_test.cljc` frees slots with
`drain-one!` to exercise parking today, which no v2 transport offers —
this needs a scripted-handle fixture instead (see below).

## Disposition (D5, full detail in the plan — read it before starting)

- `advance` dispatches on `:dao.stream/outcome`: `ok` as today; `blocked`,
  `end` as today; `gap` returns `{:status :input-gap :recovery-cursor c}`
  **with the cursor the outcome carried**, still without advancing the
  binding's own cursor (the spec's rule that a bare gap cannot construct
  the input-loss envelope is unchanged — the caller now has a real cursor
  instead of fabricating one from `rb/tail-position`). Terminal outcomes
  (`cursor-mismatch`, `invalid-cursor`, `transport-error`) return a new
  `:status :transport-error` rather than falling into `:blocked` — a
  binding that retries a mismatched cursor forever is exactly the spin
  the runtime plan's classifier forbids.
- `flush-pending` parks on `:dao.stream/full` exactly as it parks on
  `{:result :full}` today, and treats `closed`, `invalid-value`, and
  `transport-error` on an output as the *output* being gone: drop the
  value from pending with one diagnostic, do not retry. Keep the park
  mechanism — the contract permits transports that return `full` (a real
  WebSocket outbound path may). What changes is only the claim that an
  in-process ring buffer is such a transport.
- `bind` accepts `:cursor` and otherwise mints `:dao.stream/oldest` on the
  first `advance` (the origin-cursor rule: a binding created before the
  input handle has any value still observes from its origin).
  `recover-input-gap` takes the recovery cursor or any host-minted cursor;
  it never receives a `{:position n}` map.
- `bind_test.cljc`: switch to `ringbuffer/create!`; replace the
  `drain-one!`-based parked-interval cases with a scripted handle that
  answers `full` for `n` appends then `ok` (the runtime plan's fixture
  rule) — this makes the tests *stronger*, since they stop depending on a
  drain the spec never promised. Add: a `gap` on the input returns
  `:input-gap` with a `:recovery-cursor` that, passed to
  `recover-input-gap` with an input-loss envelope, resumes at the next
  retained input; a `transport-error` on the input returns
  `:transport-error` and does not re-read.
- `dao.gui.event.md`: rewrite the *Binding Contract* paragraph to restate
  outcomes as maps; rewrite *Transport, Coalescing, And Backpressure* to
  say a conforming input stream **either refuses (`full`) and the binding
  parks, or evicts and reports `gap`, with `:input-gap` plus
  `recover-input-gap`'s `:dao.terminal/input-loss` envelope as the
  recovery path** — this is exactly what `artifact.cljs` already does in
  production; drop the sentence preferring reject over evict-oldest.

## Demo streams to move (your scope only — input/output/signal, not frame)

`src/cljs/datomworld/demo/artifact.cljs` and
`src/cljd/datomworld/demo/artifact.cljd`: runtime-input (capacity 1024),
output (64), and signal (32, currently `:reject`) streams move to
`ringbuffer/create!` — the two `:reject` buffers become evict-oldest
(the only v2 ring buffer; nothing reads the 32-slot signal lane in a
tight loop, so eviction over refusal changes nothing observable). The
fabricated `{:position (rb/tail-position s)}` cursors become the
recovery cursor D5's `advance` now returns, or a freshly minted
`:newest`. Rewrite the gap branch onto the recovery cursor.

## Acceptance criteria

- All eleven `test/dao/gui/event/*_test.cljc` files green on all three
  hosts (clear `test/cljd-out` first) with deftest counts unchanged
  except `bind_test.cljc` (whose parked-interval cases and gap/
  transport-error cases change as described above).
- `clj -M:test`, `bb test:cljs`, `bb test:cljd` all pass in full.
- Flag manual verification as NOT something you can do (no browser/
  Flutter runtime available to you) — the orchestrator will check
  `#artifact` (drag rotates, keyboard navigates, console shows the
  "dao.stream output gap; resuming" recovery message when spammed past
  capacity) and the Flutter *Artifact* entry separately.

Do not touch `src/cljc/dao/postgraphics/terminal.cljc`,
`test/dao/postgraphics/*`, `src/cljd/dao/postgraphics/flutter.cljd`,
`src/cljs/dao/postgraphics/web{,/gpu}.cljs`, or the frame stream in the
two `artifact.*` files — that is U3's scope. Leave your working tree
changes unstaged for the orchestrator to review and commit. Report back
exactly what you changed (with line numbers), what you could not verify
(manual UI checks), and your own test output.
