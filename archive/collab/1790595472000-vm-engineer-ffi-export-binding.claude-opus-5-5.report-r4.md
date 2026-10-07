Completed-GMT: 2026-09-28 12:19:44 GMT
Completed-Local: 2026-09-28 19:19:44 +07
Coding-Agent: claude
Session-ID: 2fd38cae-ba0d-4d01-9d12-b9ee9af137e2

# Slice 3a fix round 3: gate P3 test pin (a writer-only export skips the cursor check)

## Changed files
- `test/yin/vm/ffi/remote_serve_test.cljc` only.

`remote_serve.cljc` was edited temporarily for the mutation check and then reverted to its prior content (see below). Nothing is staged or committed.

## Change
- **Fixture.** The `host-cursor-reader` fixture now also implements `IDaoStreamWriter`: `append!` answers ok. Its reader cursors still hold a host object (a fn), and its docstring now says why it is also a writer. The existing reader-surface refusal case is unaffected, because a `#{:reader}` policy is still refused on portability.
- **Test.** The "a writer-only surface names no cursors" case in `a-reader-whose-cursors-are-not-portable-is-never-entered` now serves **that fixture**, instead of a portable ring, under a `#{:writer}` surface policy. It asserts two things:
  - The fixture is admitted (`serve!` is non-nil).
  - Its table entry is exactly `{:handle h, :surface #{:writer}}`.
- **Source.** The new test passed against the unchanged source, so there is no source bug to report.

## Mutation check (the pin bites)
- I temporarily added `(do :MUTATION-P3 (portable-cursors? (::codec policy) h))` as an unconditional clause in `servable-surface`. That applies the cursor check to every surface, including writer-only.
- `clj -M:test -n yin.vm.ffi.remote-serve-test` then reported `Ran 10 tests containing 95 assertions. 2 failures, 0 errors.` Both failures were in `a-reader-whose-cursors-are-not-portable-is-never-entered`, at lines 381 and 384: the admission check and the table-entry check.
- I reverted the mutation with an edit. `grep -c "MUTATION"` on both files gave `0` and `0`.

## Verification (exact, after the revert)
- `clj -M:kondo --lint test/yin/vm/ffi/remote_serve_test.cljc` gave `errors: 0, warnings: 0`.
- `clj -M:test -n yin.vm.ffi.remote-serve-test -n yin.vm.ucf.remote-test -n dao.stream.remote-test` gave `Ran 54 tests containing 471 assertions. 0 failures, 0 errors.`
- cljstyle, Node and CLJD were not run this round (not requested). The fixture change adds one `reify` protocol block, which may need the orchestrator's cljstyle pass.

## Open items
None.
