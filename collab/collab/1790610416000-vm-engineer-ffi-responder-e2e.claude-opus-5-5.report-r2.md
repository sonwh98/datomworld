Completed-GMT: 2026-09-28 16:15:04 GMT
Completed-Local: 2026-09-28 23:15:04 +07
Coding-Agent: claude
Session-ID: 715a2230-3f6e-48b5-9260-4e2225debf68

# Slice 3c fix round 1 (partial): the holder's silent lease-grants gap

**Status: fixed.** A gap on the holder's `lease-grants` cursor used to leave the holder unchanged, so it retried the same gapped cursor forever. It is now an explicit, named terminal outcome, and a new test shows the holder stops. I did not work on the other gate findings (P1 call correlation, the call-out readiness P2, Q3 loss error), since they are with the Architect. Nothing is staged or committed.

## Changed files

- `src/cljc/yin/vm/ffi/remote_serve/holder.cljc`
  - **Gap is terminal loss.** `seek-grant` now handles a `:dao.stream/gap` from the `lease-grants` reflection by setting `::lost :dao.stream/gap`. The grant may have been evicted before it was read, and which facts were lost cannot be known, so the holder never re-reads that cursor.
  - **`step` stops for a lost holder.** A holder that is lost, like one that is reclaimed, reads and appends nothing more.
  - **`holding?`** now answers false once the holder is lost.
  - **New public `lost` accessor.** It returns why the holder can never observe its grant (`:dao.stream/gap`), or nil. The loss is recorded in the holder's state under `::lost`, which `open!` initializes to nil.
  - Docstrings updated.
- `test/yin/vm/ffi/remote_serve_test.cljc`: new test `an-evicted-grant-is-terminal-loss-for-the-holder`.
  - The grantor's stream is a 2-slot ring.
  - The holder's first pass asks for its grants cursor, and the binding answers it at the position of the unread grant.
  - Two more facts are then appended to the grantor's stream, which evicts the grant.
  - The holder's later passes read the gap. The test asserts:
    - `(h/lost @hd)` is `:dao.stream/gap`;
    - no grant was observed;
    - `holding?` is false;
    - five further holder passes send no request on the channel, so there is no infinite retry.

## Verification

- **Lint:** `clj -M:kondo --lint` on `holder.cljc` and `remote_serve_test.cljc` gave `errors: 0, warnings: 0`.
- **Focused JVM lane:** `clj -M:test -n yin.vm.ffi.remote-serve-test -n yin.vm.ffi.remote-serve.responder-test -n yin.vm.ffi-test -n yin.vm.ucf.remote-test -n dao.stream.apply-test -n dao.stream.remote-test -n dao.lease-composition-test` gave **Ran 107 tests containing 954 assertions. 0 failures, 0 errors.**
- **Mutation proof:** I temporarily changed the gap branch's condition so it never matches, which restores the old fall-through. The new test failed twice:
  - `lost` was nil;
  - the channel request count went from 4 to 7, meaning the gapped read kept being re-sent.

  I then reverted, and grepping for `MUTATED` under `src/cljc/yin/vm/ffi/` and `test/yin/vm/ffi/` finds nothing.
- **Not run:** cljstyle (the orchestrator runs it) and the CLJS and CLJD lanes (not requested this round).

## Notes

- The loss applies only while the holder is still looking for its grant. Once the grant is observed, the holder no longer reads `lease-grants`.
- What a lost holder should do next is the composition's decision; this namespace only reports the loss. Possible responses include proposing again or giving up the resource.
