Completed-GMT: 2026-10-06 20:25:00 GMT
Completed-Local: 2026-10-07 03:25:00 +0700
Coding-Agent: claude (sonnet-5-5)

# UCF M-next D9, fix round 3: the halted-result census defect

## Fix
`src/cljc/yin/vm/ucf/handoff.cljc` (`export-task`): the halted
`:yin.k/result` is now encoded right after the task store and BEFORE
`snapshot-module-stores`, `export-installs`, the cells/profiles read,
the segments read and the code fetch. The body reuses that encoded
value (`result`). Version 0 and version 1 share the path, so both are
fixed. Nothing else in src moved.

## Tests (test-first)
`test/yin/vm/ucf/lift_support.cljc`: `halted-with-cursors`,
`halted-result-closure` (a halted machine whose result is a module
closure naming `host.mod`, whose module store holds two cursor
references only the result reaches).
`test/yin/vm/ucf/lift_v1_test.cljc`, five new rows, each over version 0
and version 1: result-only cursor (one declared cell), repeated aliases
(one cell), distinct cursors (two cells), result-only module closure
(module store and its two cells travel, segments equal code keys),
repeatable bytes. Each checks complete cells and profile declarations,
a successful lift, and validation/restoration (v1: inspector plus
`validate-body` over the jing bytes; v0: `validate-body` over the
stream-codec bytes).

## Red and green
- Red (fix absent, new rows present): `lift-v1-test` 35 failures, 6
  errors; the lift refused `:yin.k/undecodable` (`:cell :yin.k/c-1`,
  path `[:yin.k/cells]`) for the cursor rows and `:non-portable`
  `:unaddressed-segment` for the closure row until the fixture used the
  closure payload's segment.
- Green: `yin.vm.ucf.lift-v1-test` 26 tests, 221 assertions, 0 failures,
  0 errors.
- Surrounding suites (lift-v1, handoff, handoff-v1, checkpoint,
  holder.export, authority.front, authority.inherited, ledger-fixture,
  custody, remote): 169 tests, 1499 assertions, 0 failures.
- All `yin.vm.ucf.*-test` namespaces on the JVM: 324 tests, 3138
  assertions, 0 failures, 0 errors.
- Fixture anchors: checkpoint-test + ledger-fixture-test run in two
  further separate JVM processes, each 21 tests / 298 assertions, 0
  failures. No pin moved, no fixture file or D7/D8 byte-for-byte pin
  changed (none needed to).
- kondo on handoff.cljc and both test files: 0 errors, 0 warnings.
  cljstyle not run.

## Concerns / incomplete
- The "fresh cross-process digest check" is the pin tests in fresh
  processes. A direct digest print of the three anchors was blocked by
  the sandbox (`clojure -Spath`/java -cp needed approval), so I did not
  recompute `d94ab55d…`/`1b969525…`/`7ee3fcfb…` by hand.
- JVM only; Node and Dart lanes not run. The new rows use no
  host-specific constructs.
- No git writes performed.

# Round 4 (architect r3): the restoration regression

Source untouched this round. Tests only.

- `lift_v1_test.cljc`: `accepted-for!` now also calls `restored!`, which
  lowers the emitted bytes through `handoff/resume-task` into a fresh
  `s/new-machine` receiver with an attach seam answering one stream per
  distinct descriptor, supplying `{:address ...}` for version 1 only
  (a version-0 resume is a fork). It asserts `:status :ok` and returns
  the resumed machine. The four rows (cursor, repeated aliases,
  distinct cursors, module closure) now assert on that machine, for
  both versions: restored result cursor refs authentic under the
  receiver; aliases restore to one cursor id, distinct cursors to two;
  the closure row asserts the result is a closure and its restored
  `host.mod` module store has two entries whose cursor refs are
  authentic and distinct.
- `lift_support.cljc`: `halted-result-closure` now copies the real
  closure payload (changing only `:env`); the earlier hand-built payload
  restored with `:marker-mismatch`, a fixture defect the new restoration
  step exposed (the lift side was unaffected).
- Results: lift-v1-test 26 tests, 251 assertions, 0 failures; all
  `yin.vm.ucf.*-test` 324 tests, 3168 assertions, 0 failures, 0 errors;
  kondo on the two test files 0/0. JVM only.
