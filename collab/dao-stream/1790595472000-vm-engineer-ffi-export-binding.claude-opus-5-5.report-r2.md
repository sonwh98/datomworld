Completed-GMT: 2026-09-28 11:55:08 GMT
Completed-Local: 2026-09-28 18:55:08 +07
Coding-Agent: claude
Session-ID: 2fd38cae-ba0d-4d01-9d12-b9ee9af137e2

# Slice 3a fix round 1: P1 (bounded step) and P2 (cursor portability)

## Changed files
- `src/cljc/yin/vm/ffi/remote_serve.cljc`
- `test/yin/vm/ffi/remote_serve_test.cljc` (edited on top of the orchestrator's cljstyle fix, which is kept)

I did not edit `dao.stream.remote` or `yin.vm.ucf.remote`. `close!` is unchanged, as instructed. Nothing is staged or committed.

## P1: bounded step
- **Option.** `::step-budget` is a new required option: a positive integer, validated in `open!`. Missing or malformed values are refused.
- **Reader view.** `step` now passes `remote/mirror-step` a `budgeted-reader` view over the channel reader instead of the raw reader:
  - It passes at most `budget` `next` calls through to the real reader and returns each outcome unchanged.
  - Once the budget is spent it answers `blocked` without reading.
  - `descriptor` and `cursor` go straight to the real reader, so the mirror's channel context (the channel identity) is unchanged.
- **Why the mirror's cursor semantics hold exactly.** The view never builds or rewrites a cursor. On `blocked`, `mirror-step` returns the cursor it holds, which is the successor of the last value it read. The binding stores that cursor and the next `step` resumes from it.
- **What the budget counts.** It counts channel reads per pass, not well-formed requests. Checking well-formedness would need `remote`'s private predicate. Counting reads bounds the work either way, and malformed values count against the budget. `step`'s docstring now also states that it belongs to the single, serialized drive owner, answering the gate's Q1.
- **Test `step-is-a-bounded-pass`.** Five requests are on the channel with a budget of 2. The answered ids are `[0 1]`, then `[0 1 2 3]`, then `[0 1 2 3 4]`. A fourth step returns `::advanced? false`, and the ids are still `[0 1 2 3 4]`: nothing is skipped or answered twice.

## P2: cursor portability
- **Check.** `serve!` now refuses a handle with a `:reader` surface unless the cursor it mints at both `:oldest` and `:newest` answers ok and survives a round trip through the binding's codec (encode, decode, equal).
- **Local implementation.** `ucf.remote/portable-cursor` is private, so I wrote the same rule locally as `round-trips?`. It uses the same portable catch, `#?(:cljd Object :clj Throwable :cljs :default)`, with `:cljd` first.
- **Result.** A refused handle gets nil from `serve!` and nothing is entered in the table.
- **Codec option.** `::codec` is a new optional option, validated as `{:encode fn :decode fn}` when given. It defaults to `ucf.remote/cursor-codec`, the documented UCF default. `rs/lift-frame`'s default codec is now the binding's codec, so serving and lifting judge with the same codec.
- **Writer-only handles.** A handle served with only `:writer` names no cursors, so it is not checked.
- **Test `a-reader-whose-cursors-are-not-portable-is-never-entered`** covers three cases:
  - A reader whose cursor holds a host object (a fn): `serve!` returns nil and the table and entries stay empty.
  - A writer-only handle is still served.
  - A binding configured with a codec that refuses everything refuses an ordinary ring-buffer reader, which shows the binding's own codec does the judging.
- **`open!` coverage.** `open-refuses-an-incomplete-assembly` now also covers a missing `::step-budget`, a nil `::step-budget`, and a malformed `::codec`.

## Mutation check
I bypassed each new guard: forced the portability check to true, and passed the raw reader to `mirror-step` instead of the view. The new namespace then reported **7 failures** across the two new tests. Both mutations were reverted with edits, a grep confirms no mutation text remains, and the reruns below are green.

## Verification (exact)
- `clj -M:kondo --lint src/cljc/yin/vm/ffi/remote_serve.cljc test/yin/vm/ffi/remote_serve_test.cljc` gave `errors: 0, warnings: 0`.
- `clj -M:test -n yin.vm.ffi.remote-serve-test -n yin.vm.ucf.remote-test -n dao.stream.remote-test` gave `Ran 54 tests containing 461 assertions. 0 failures, 0 errors.` (new ns: 10 tests, 85 assertions)
- `bb test:cljs` (I ran it myself; not required) gave `Testing yin.vm.ffi.remote-serve-test` present, `Ran 2204 tests containing 50049 assertions. 0 failures, 0 errors.`
- **cljstyle check: NOT RUN.** `cljstyle check src/cljc/yin/vm/ffi/remote_serve.cljc test/yin/vm/ffi/remote_serve_test.cljc` was refused again by the session permission gate ("This command requires approval"). The orchestrator needs to run it, and possibly `cljstyle fix`: the new `reify` blocks in both files may need formatting.
- **CLJD: NOT RUN.** The only new host-sensitive construct is the reader-conditional catch, written `:cljd`-first. `volatile!` and `reify` are core on all three hosts.

## Open items
- The close!/channel-ownership finding (gate P2 #3 / Q3) is untouched and awaits the owner decision.
- The budget counts reads rather than well-formed requests (see P1). If the Architect wants a request-only budget, that needs either a public well-formedness predicate in `dao.stream.remote` (authorization needed) or a local copy of it.
