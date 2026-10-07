Slice 8 -- UCF facade through dao.stream.remote descriptors

## What was built

NEW module `src/cljc/yin/vm/ucf/remote.cljc` (home chosen: yin.vm-side,
next to `yin.vm.ucf`'s existing S7.3/S7.4 static half, since this
bridges parked-state pending waits into UCF data -- it does not belong
under `dao.stream`, which knows nothing of yin.vm). It is
parameterized, not namespace-coupled: `lift-pending`/`lower-pending`
take plain local handles plus two composition callbacks (`serve!`,
`attach!`) rather than requiring `yin.vm.engine` or
`dao.stream.remote` internals. `dao.stream` is required only for
`stream/descriptor` (naming a refusal's stream identity).

Implements S7.4.3's full pending-wait variant set, lift and lower,
every stream/cursor reference through a remote descriptor under
`:dao.stream.remote/v1` (`:yin.k/cursor-profiles`):
  - `:next` (blocked read) -- a cursor cell (S7.5.3), never a bare
    position
  - `:put` (blocked write) -- a stream marker plus the retained value
  - `:ffi` (sent) and `:ffi-request` (retained) -- request marker,
    response marker, and the response's kept cursor cell
  - the linker's M4 variants `:link-request`, `:link-response` --
    same shape as ffi, plus the envelope
  - `:install` -- passes through unchanged, no stream

NEW test `test/yin/vm/ucf/remote_test.cljc`, 11 tests / 54 assertions:
  - the acceptance row itself: a string-backed (ring-buffer) stream
    migrates with a kept cursor through a toy two-ring-buffer channel
    and `dao.stream.remote/attacher`/`mirror-step` (the same toy
    pattern as `dao.stream.remote-test`), and resumes with the
    source's own outcome at the kept position
  - forced eviction (a 2-slot ring) yields `:dao.stream/gap` with the
    source's own successor cursor, no replay, no silent skip
  - `serve!` refusing a handle is `:yin.k/unsatisfied` before lift
    mints a value
  - `attach!` answering gone is `:yin.k/unsatisfied` naming the stream
  - round-trips for `:put`, `:ffi`, `:ffi-request`, `:link-request`,
    `:link-response`, `:install`, and an unrecognized reason as
    `:yin.k/undecodable`

## Design notes / genuine ambiguities (minimal reading)

- Scope: one `lift-pending`/`lower-pending` call handles one pending
  entry with its own self-contained `:yin.k/cells` table (fixed id
  `:yin.k/c-0`). S7.5.3's cross-entry cell aliasing (two waiters
  sharing one cell) is a concern of whatever assembles a whole frame's
  cell table from several pending entries -- out of this slice's
  acceptance row, which is about one wait migrating. Noted in the
  namespace docstring rather than guessed at.
- `attach!`'s contract is "resolves synchronously to ok+handle or a
  definitive refusal," not the raw `dao.stream.remote/attacher` (which
  always answers `ok` at once per S2.4's deferred-confirmation rule,
  with `gone` only surfacing on a later poll). A caller wiring this
  facade into a real resume path is expected to poll/drain to
  resolution before calling `lower-pending`, or to pass an `attach!`
  that already does so. The gone-reflection test exercises this
  directly with a stub that already knows the outcome, since driving
  an async gone-detection through the toy channel is a transport
  proof, not a facade one, and is already covered by
  `dao.stream.remote-test`'s `not-found-marks-gone`.
- No design conflict with the UCF doc was found; no BLOCKED condition
  triggered.

## Verification

- Baseline measured at start (this session, before any edit):
  matches the constraint's stated ballpark; concurrent slices 5/6 were
  already mid-edit (rpc.cljc, ws_project.cljc, yin.repl/* modified;
  serving.cljc, rpc/ws.cljc, several slice_test.* and
  dao.stream.slice-peer deleted).
- JVM (`clj -M:test`), run twice for attribution: 2250 tests /
  182,985 assertions, 22 failures + 2 errors both times, all in
  `yin.repl.embed-test` / `yin.repl.main-test` (owned by slices 5/6).
  `yin.vm.ucf.remote-test` alone: 11 tests / 54 assertions, 0
  failures, 0 errors.
- Node (`mise exec -- clj -M:cljs -m shadow.cljs.devtools.cli compile
  test`; the `:slice-peer` build in `bb test:cljs` is broken by slices
  5/6's deletion of `dao.stream.slice-peer` -- attributed, not mine to
  fix under this slice's NEW-files-only constraint): 2157 tests /
  49,623 assertions, 15 failures, 0 errors, all in
  `dao/stream/ws/node_test.cljs` and `yin/repl/*` (owned by slices
  5/6). `yin.vm.ucf.remote-test` included and green.
- Dart (`rm -rf test/cljd-out` first per the stale-compile caveat,
  then `mise exec -- clojure -M:cljd test`; `bb test:cljd`'s
  `build:slice-peer`/`build:yin-repl-peer` deps are broken by the same
  slice-peer deletion): 2115 tests, 3 failures, all in
  `yin.repl.embed-test` / `yin.repl.main-test` (owned by slices 5/6).
  `yin.vm.ucf.remote-test`'s 11 tests all green.
- `clj -M:kondo --lint` on both new files: 0 errors, 0 warnings.
- `cljstyle` binary is not installed in this environment (not on
  PATH, no jar found) -- not run; formatting was done by hand
  following the surrounding codebase's existing style (the same
  disclosed limitation prior reviewers in this repo have hit).
- Pure ASCII, <= 80 columns verified on every line of both new files.
- No commit/stage/checkout/reset/stash performed; no leftover
  diagnostic files (scratch logs written under `target/` during
  verification were removed).

Status: COMPLETE
