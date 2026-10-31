Completed-GMT: 2026-09-28 12:08:21 GMT
Completed-Local: 2026-09-28 19:08:21 +07
Coding-Agent: claude
Session-ID: 2fd38cae-ba0d-4d01-9d12-b9ee9af137e2

# Slice 3a fix round 2: close! / channel ownership (owner decision (a), dedicated channel)

## Changed files
- `src/cljc/yin/vm/ffi/remote_serve.cljc`
- `test/yin/vm/ffi/remote_serve_test.cljc`

These are edits on top of the orchestrator's cljstyle formatting and the round-1 fixes; both are kept. Nothing is staged or committed, and no other file was touched.

## Implementation
- **New required option `::channel-exclusive?`.** This is the composition's explicit declaration of the ownership contract: the channel end is dedicated to this binding, and no other reflection or mirror reads or writes it. A handle cannot prove exclusivity, so it is a declaration.
  - It must be exactly `true`.
  - If absent, it refuses as `{::option ::channel-exclusive?, ::reason ::missing}`.
  - Any other value refuses as `{::option ::channel-exclusive?, ::reason ::not-exclusive}`, so the refusal names the problem rather than a generic `::malformed`.
- **Refusal reasons.** Entries in the `required` table may now carry their own refusal reason. Every other entry still defaults to `::malformed`.
- **Docstrings.** The `open!` docstring documents the contract in its options list. The namespace docstring states that the binding owns its channel end outright.
- **`close!`** still closes the writer, unchanged in code. Its docstring now states that closing the writer is the binding's to do only because of the contract `open!` required, so no other reflection or mirror loses its channel with it.

## Tests
- **`open-refuses-an-incomplete-assembly`** gains two checks:
  - `::channel-exclusive?` is included in the missing-option sweep, and refuses as `::missing`.
  - `false`, `nil`, `:yes` and `1` each refuse as `::not-exclusive`.
- **`an-unresolved-remote-append-ends-as-append-unknown`** is extended rather than duplicated:
  - It asserts the binding was opened with the exclusivity declaration.
  - After `close!`, appending to the binding's channel writer answers `:dao.stream/closed`, which proves `close!` closed it.
  - The existing assertion still holds: exactly one `append-unknown` event for the unanswered remote append.
- **Fixture.** The `opts` fixture now declares `::rs/channel-exclusive? true`.

## Verification (exact)
- `clj -M:kondo --lint src/cljc/yin/vm/ffi/remote_serve.cljc test/yin/vm/ffi/remote_serve_test.cljc` gave `errors: 0, warnings: 0`.
- `clj -M:test -n yin.vm.ffi.remote-serve-test -n yin.vm.ucf.remote-test -n dao.stream.remote-test` gave `Ran 54 tests containing 470 assertions. 0 failures, 0 errors.` (new ns alone: 10 tests, 94 assertions, 0 failures).
- cljstyle was skipped per the brief; the orchestrator runs it. CLJD and Node were not run this round. No new host-sensitive constructs were added: `true?` and plain maps only.

## Open items
None from this round.
