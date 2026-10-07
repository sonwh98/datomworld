Created-GMT: 2026-09-17 09:53:20 GMT
Created-Local: 2026-09-17 16:53:20 +0700 (Asia/Ho_Chi_Minh)
Coding-Agent: claude
Session-ID: 9829afe4-c770-4cbf-af7b-418e6a80eab9
Role: Frontend & Graphics Implementer

# Task: dao.stream v1-retirement, U3 — dao.postgraphics.terminal on v2 (D4)

Read `docs/design/dao.stream.v1-retirement.implementation-plan.md` in full
first, especially D4 and the "U3" section, and `docs/design/dao.stream.md`
in full for the v2 contract's invariants (the "Explicitly Absent" list in
particular — no waiter registration, no callback invocation from inside an
operation, no fabricated cursor positions; "cadence belongs to the runtime
driving the interpreters"). Also skim `docs/design/dao.postgraphics.terminal.md`.

This is real design work, not a rename: `terminal.cljc` is being rewritten
from a wake-on-append/waiter-registration design into a step-driven
binding the host polls, matching the precedent already set by
`yin.repl.flutter`'s `Timer.periodic` owning the only calls to
`embed/step`.

A separate, independent implementer is concurrently working on U4
(`dao.gui.event` on v2) in the same working tree. Your files and theirs
overlap in exactly two places — `src/cljs/datomworld/demo/artifact.cljs`
and `src/cljd/datomworld/demo/artifact.cljd` — but on disjoint regions:
**you own only the *frame* stream** in those two files (the one handed to
the terminal binding); U4 owns the input/output/signal streams. Do not
touch anything in those two files except the frame-stream `ds/open!` ->
`ringbuffer/create!` conversion and whatever wiring feeds the terminal
binding. If in doubt about a line, leave it and note it in your report
rather than guessing at the boundary.

## Disposition (D4, full detail in the plan — read it before starting)

Rewrite `dao.postgraphics.terminal` in place. Keep the namespace name and
its signal vocabulary (`reset-signal`, `rejection-signal`,
`frame-skipped-signal`, `protocol-error-signal`, `:dao.terminal/*` kinds —
all data, all survive). New surface:

- `(put-frame! frame-handle frame)` -> `(stream/append! frame-handle frame)`,
  returning the outcome map, waking nothing. Every producer in the tree
  already ignores or `=`-checks the return, so this is a return-shape
  change only.
- `(bind frame-handle {:validate-frame! :present-frame! :signal-handle
  :generation-id :generation-id-fn :on-error})` -> a binding **value**
  `{:frame-handle h :cursor c :generation-id g :closed? false}`, cursor
  minted at `:dao.stream/newest`, with the reset signal appended to the
  signal handle. No callback registered anywhere.
- `(step binding)` -> `{:binding binding' :status s}`, `s` one of
  `:presented`, `:rejected`, `:blocked`, `:end`, `:gap`, `:error`,
  `:closed`. One `next` per call: on `ok` validate-and-present (rejection
  appends a `rejection-signal`, calls `on-error`); on `gap` append
  `frame-skipped-signal` and **adopt the recovery cursor the outcome
  carries** (no `inc` fabrication); on `end`/a terminal outcome append
  `protocol-error-signal` and stop. Provide a `step-until-blocked` helper
  with a bound (same one-step-owner shape as `dao.gui.event/advance`).
- `(close binding)` marks closed; nothing to unregister.
- Signal handle stays optional, any v2 writer; its `append!` outcome is
  not inspected (a full/closed signal lane silently drops the signal).

Do NOT adopt `dao.stream.observer/run-on-stream` — that's for
VM-shaped consumers with attach/descriptor/batch semantics; the terminal
just holds a handle and reads one value at a time.

## Hosts

- `src/cljd/dao/postgraphics/flutter.cljd` (`bind-stream!` call around
  `:361`, `put-frame!` alias around `:313` — re-check current line numbers,
  the plan's are from the 2026-09-17 sweep and the tree has moved since):
  own one `Timer.periodic` per widget at the frame interval the demos
  already tick (16ms), calling `step` until `:blocked`, cancelled on
  dispose.
- `src/cljs/dao/postgraphics/web.cljs` (`bind-stream!` call around `:82`,
  alias around `:15`, docstring around `:102`): same shape with
  `requestAnimationFrame` or `setInterval`, cancelled on unmount.
- `src/cljs/dao/postgraphics/web/gpu.cljs` (around `:545`): alias-only
  update to match the renamed surface.

## Demo frame streams to move (your scope only — the *frame* stream in each)

`src/cljc/datomworld/demo/earth_moon_runner.cljc`,
`src/cljc/datomworld/demo/voxel_runner.cljc`,
`src/cljs/datomworld/demo/solar_system.cljs`,
`src/cljd/datomworld/demo/solar_system.cljd`,
`src/cljd/datomworld/demo/dao_gui.cljd`,
`src/cljd/datomworld/demo/postgraphics.cljd`,
and the frame stream only (not input/output/signal) in
`src/cljs/datomworld/demo/artifact.cljs` and
`src/cljd/datomworld/demo/artifact.cljd`. Each: `ds/open!` (v1 ring buffer)
-> `ringbuffer/create!` (v2), capacity per the plan's table (4 or 8 —
check each file's current capacity and preserve it via `capacity-key`).

## Tests and docs

- `test/dao/postgraphics/terminal_test.cljc`: rewrite over a v2 ring
  buffer and a scripted handle. Required cases (from the plan's
  criteria): `bind` appends exactly one reset signal and mints at
  `:newest`; a frame appended after bind is presented on the next `step`,
  one appended before is not; a rejected frame appends a rejection signal
  and calls `on-error`; a capacity-1 buffer with two appends yields `:gap`
  then `:presented` with a `frame-skipped` signal between; `end` yields
  `:end` once then `:closed`; a scripted `transport-error` yields `:error`
  with a `protocol-error` signal.
- `test/dao/postgraphics/web_test.cljs`: same assertions through the
  canvas widget's binding.
- `docs/design/dao.postgraphics.terminal.md`: update the "takes a
  `dao.stream` cursor" language to the D4 binding/step shape; add a
  *Cadence* paragraph describing the host ticker ownership.

## Acceptance criteria

- `terminal_test.cljc` passes on clj, cljs, and cljd (clear
  `test/cljd-out` first — this repo's known stale-artifact trap) with
  `Testing dao.postgraphics.terminal-test` in the Node output.
- `web_test.cljs` passes.
- `clj -M:test`, `bb test:cljs`, `bb test:cljd` all pass in full.
- Flag manual verification as NOT something you can do (no Flutter/browser
  runtime available to you) — the orchestrator will run the Flutter smoke
  test (Solar System, Earth/Moon, Voxel, dao.gui Prototype picker entries)
  and browser check (`#solar-system`, `#earth-moon`, `#voxel`) separately.

Do not touch `src/cljc/dao/gui/event.cljc`, `test/dao/gui/event/*`, or the
input/output/signal streams in the two `artifact.*` files — that is U4's
scope. Leave your working tree changes unstaged for the orchestrator to
review and commit. Report back exactly what you changed (with line
numbers), what you could not verify (manual UI checks), and your own test
output.
