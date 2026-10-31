Completed-GMT: 2026-09-28 16:03:10 GMT
Completed-Local: 2026-09-28 23:03:10 +07
Coding-Agent: claude
Session-ID: 715a2230-3f6e-48b5-9260-4e2225debf68

# Report: Slice 3c (FFI apply responder, production lease holder and grant delivery, real-VM end-to-end remote FFI)

**Status: complete.** Parts A, B and C are done. Acceptance items 1 to 11 are covered, and the focused JVM lane and the CLJS lane pass. I did not stage or commit anything, and I edited no file outside the allowed scope. No authorization is needed.

## Changed files

- `src/cljc/yin/vm/ffi/remote_serve.cljc` (modified)
  - New required option `::lease-grants`: a reader of the grantor's stream. `open!` publishes it as the S6 `lease-grants` table entry with surface `#{:reader}`. The entry has no lease, because the grantor serves it by its own policy.
  - `open!` refuses `::lease-grants` as `::malformed` when the handle is not a reader or does not answer descriptor, or when its cursors do not round-trip the channel codec. The refusal happens before the judge is assembled or anything is minted.
  - New public `lease-grants`, which returns the served marker (nil once the binding is closed).
  - New public `served?`, which tells whether an identity is still live.
  - `close!` now also unpublishes the grants entry.
  - `round-trips?` and `portable-cursors?` moved above `open!`, unchanged.
  - Docstrings updated.
- `src/cljc/yin/vm/ffi/remote_serve/responder.cljc` (new, `yin.vm.ffi.remote-serve.responder`): the apply responder (part A).
- `src/cljc/yin/vm/ffi/remote_serve/holder.cljc` (new, `yin.vm.ffi.remote-serve.holder`): the production remote lease holder (part B).
- `test/yin/vm/ffi/remote_serve_test.cljc` (modified):
  - Added `::rs/lease-grants` to the fixtures.
  - Assertions that the table is empty now use `exports`, which is the table minus the standing grants entry.
  - Added lease-grants refusal cases, both missing/malformed and unportable cursors. The codec test now uses a codec that refuses one named ring, so it still proves the check made by `serve!`.
  - **The test-only holder, which copied the grant into an inbox, is replaced by the production holder.**
- `test/yin/vm/ffi/remote_serve/responder_test.cljc` (new, `yin.vm.ffi.remote-serve.responder-test`): the end-to-end acceptance suite.
- `collab/1790610416000-vm-engineer-ffi-responder-e2e.cljs.log`: the `bb test:cljs` output.

## Design choices where the design left latitude

1. **Where the code lives.** The responder and the holder are new namespaces under `yin/vm/ffi/remote_serve/`, not additions to the binding. They are peer observers of the same streams. The binding's mirror serves stream *operations* and interprets no value. The responder interprets apply *values* and knows nothing about the channel, table or leases. Neither one drives the other: the one drive owner calls `rs/step` and `resp/step`. This keeps the rule "one stream, many interpreters" and matches the ruling's split ("the remote mirror serves stream operations; the FFI interpreter reads apply requests").
2. **Handler authority.** `resp/open!` exports call-in and call-out **through the binding's own `serve!`**. The 3a `::rs/admit?` gate and the surface policy therefore decide whether this peer's handlers can be reached at all. There is no second gate that admits everything by default.
   - The handler map is required. Leaving it out refuses with `::missing`.
   - The served surfaces must include `:writer` on call-in and `:reader` on call-out, or the call refuses with `::surface`.
   - Any refusal retires every identity that `open!` itself served.
   - I did **not** add a per-request authorization gate. Whether every peer that learns the descriptor may invoke the handlers remains the owner policy question that the design flagged.
3. **Responder state.** The responder is a plain value that the drive owner threads through. `step` is exactly one `apply/serve-once!` over the caller-owned server state. It reads the request map whole and never rebuilds it; `rpc/request!` is not used anywhere. On top of `serve-once!` there are two rules:
   - **Gap on the call-in cursor means terminal `::request-lost`, and call-out is closed.** This mirrors `yin.vm.ffi/bridge-step`, which treats a gap as fatal locally. Every VM waiting on that pair reads end and raises through `call-result` instead of parking forever.
   - **Once the binding no longer serves the pair, the responder is terminal (`::retired`).** No handler runs for a retired or reclaimed tenure, even if a response is still pending.
4. **Grant delivery (part B).** The binding serves the grantor's stream as `lease-grants`. The holder attaches a reflection to it and takes the first `:accepted` whose subject is its served identity. That grant names the renewal medium, and so the holder's own identity (`:dao.lease/holder`).
   - Only then is `dao.lease/make-holder` assembled. Its fact medium is that same reflection, attributed to the grantor by per-author media. Its outbound medium is a reflection of the renewal medium.
   - The grants-medium declaration, the renewal-medium declaration and the renewal interval are required options. `open!` validates them with a trial `make-holder`, so assembly at grant time cannot throw.
   - Renewals settle only on the source's outcome as seen on the link's event writer. A reflection's ok means only that the append was accepted outbound.
   - An `append-unknown` event carries no identity, so it settles every pending renewal as unknown and no bound advances. This errs toward stopping early, never late.
   - A not-found answer marks the lease reclaimed.
   - `release!` is `dao.lease/stop`. `step` re-appends the release until the reflection accepts it.
5. **Extra request keys.** The real VM builds its request with only id, op and args (`semantic.cljc:467`), so acceptance 1 cannot get an extra key from a plain VM call. It is covered two ways:
   - A non-VM producer appends a request carrying `:producer/nonce` over the call-in reflection. B holds the identical map and answers under the identical id.
   - The retained UCF call (item 8) carries `:producer/nonce` through the lift, the lower and the retry.

## Test outcomes (exact)

- `clj -M:kondo --lint` on all five changed `.cljc` files: `errors: 0, warnings: 0`.
- `cljstyle check`: **blocked**. The command needs approval in this session, so it did not run.
- Focused JVM run: `clj -M:test -n yin.vm.ffi.remote-serve-test -n yin.vm.ffi.remote-serve.responder-test -n yin.vm.ffi-test -n yin.vm.ucf.remote-test -n dao.stream.apply-test -n dao.stream.remote-test -n dao.lease-composition-test` gave **Ran 106 tests containing 948 assertions. 0 failures, 0 errors.**
  - `yin.vm.ffi.remote-serve-test`: 18 tests, 242 assertions.
  - `yin.vm.ffi.remote-serve.responder-test`: 12 tests, 101 assertions.
- `bb test:cljs` gave **Ran 2236 tests containing 50422 assertions. 0 failures, 0 errors.** The log shows "Testing yin.vm.ffi.remote-serve-test" and "Testing yin.vm.ffi.remote-serve.responder-test".
- `bb test:cljd` was not run, as instructed.
- I did not run the full `bb test:clj`, so the known `yin.repl.main-test` flake was not exercised.

## Acceptance coverage

All tests are in `responder_test` unless noted.

| # | Covered by |
|---|---|
| 1 | `a-real-vm-call-is-answered-remotely`: a real VM on peer A runs with no bridge; its call pair is reflections of B's served endpoint. The value the VM appended equals B's call-in value and `apply/request`; the response carries the same id; the VM halts with 3. `nil-is-a-value-and-errors-raise`: a nil value; handler-error and unknown-operation are raised through `call-result`. `a-request-carries-its-extra-keys-in-transit`: the extra key crosses and the id is identical. |
| 2 | `a-refused-request-append-is-retained-and-retried`: A's channel writer is gated full. The VM retains the exact `:datom` and id. Nothing reaches B until capacity returns; then there is one append, one handler run and one response. |
| 3 | `a-refused-response-is-retained-without-a-second-run`: call-out is gated full. The step answers `pending-response` and retains the response, id and successor across repeated steps with one handler run. After the gate opens there is one append and the VM resumes. |
| 4 | `a-refused-remote-answer-leaves-the-append-unknown`: B's channel writer is full for the pass that answers the append!. The request is applied and the VM resumes from its own apply response with one handler run. No source outcome is filed and nothing is re-sent. Closing the reflection emits exactly one `append-unknown`. |
| 5 | `a-call-in-gap-is-reported-as-loss`: the VM's request is evicted. The responder reports `::request-lost` and closes call-out; the parked VM raises instead of waiting forever; no handler runs. `a-pair-channel-gap-ends-the-link`: a VM over a `dao.stream.remote-pair` channel; B's answers outrun the pair's `in`; the VM raises "Stream read failed" and the link answers `channel-gone`. |
| 6 | `detach-and-rebind-under-the-same-identity`: the channel ends and the binding reports detached. Both identities stay served and leased. After `reattach!`, the same VM rebinds fresh reflections of the same identities, and the next call returns 30 under the same endpoint. |
| 7 | `a-reclaimed-pair-answers-not-found`: expiry reclaims both leases; the identities are unserved and out of the table. The responder answers `::retired`, then terminal, and invokes nothing. A fresh VM call raises on not-found. A fresh reflection answers not-found. Re-serving mints a new identity. The ordering "removal precedes acknowledgement" stays pinned by 3b's `expiry-reclaims-through-retire` and `release-by-the-holder-reclaims` in `remote_serve_test`. |
| 8 | `a-retained-call-lifts-at-b-and-resumes-at-a`: a real emitter VM at B with a full call-in. `rs/lift-frame` carries the envelope verbatim with the extra key; the request marker is the responder's call-in identity; the kept response position is carried. It is lowered at A into a fresh engine; the sweep retries over reflections; the map lands once, verbatim; one handler run; the VM resumes with 15. |
| 9 | `lift-frame-serves-one-handle-once-across-both-call-sites` (`remote_serve_test`, unchanged, passing). |
| 10 | `an-unservable-endpoint-refuses-the-responder`: call-out refused, call-in retired; the missing handler map is refused; a wrong surface is refused and leaves no entry. Also in `remote_serve_test`: `a-refused-frame-leaves-no-provisional-export` and `a-reader-whose-cursors-are-not-portable-is-never-entered`, which includes the lease-grants codec refusal. |
| 11 | `live-renewal-keeps-the-export-served` and `release-by-the-holder-reclaims` (`remote_serve_test`) now use the production holder. It observes its grant, which equals the grant on the grantor's stream, through the `lease-grants` reflection, renews over the renewal reflection with more than 10 renewals and no lapse, and its `release!` reclaims through 3b's path. |

**Mutation proofs.** Each mutation was applied temporarily, the tests run, and then reverted. Afterwards I grepped for `MUTATED`, `and false` and `:table {}` and found none.

| Mutation | Result |
|---|---|
| M1: gap-to-loss rule disabled | 4 FAIL in `a-call-in-gap-is-reported-as-loss` |
| M2: retired check disabled | 2 FAIL in `a-reclaimed-pair-answers-not-found` |
| M3: retained response cleared each step | 2 FAIL in `a-refused-response-is-retained-without-a-second-run` (a second handler run) |
| M4: `lease-grants` entry not published | 14 FAIL and 2 ERROR in the live-renewal and release tests |
| M5: responder refusal cleanup disabled | 3 FAIL in `an-unservable-endpoint-refuses-the-responder` |

## Unresolved concerns (not fixed; outside the allowed files or owner policy)

1. **VM call ids are not unique across VMs.** Each VM counts its own ids, so two VMs can mint the same id. `call-result` checks correlation only by id. A fresh VM that attaches an already-used call-out and mints its cursor at `:oldest` (`vm.cljc:2046`) can therefore accept an earlier VM's response as its own. I hit this while writing test 6: a second fresh VM returned the first VM's result, 3. The test now rebinds the same VM, keeping its call-out cursor. The practical rule is one caller per call pair, or a caller that mints at `:newest` when rebinding. A real fix needs either globally unique VM call ids or a newest-anchored mint for supplied pairs. Both touch `yin.vm` or `yin.vm.semantic`, so they are **not proposed as a change in this slice**; it is flagged for the Architect.
2. **Building a VM over a remote call-out needs a pre-poll.** `create-vm` mints the call-out cursor synchronously, and a reflection's first cursor call answers `transport-error` with `retry?`. The composition must therefore ask for the cursor and drive B until the answer is filed before it constructs the VM, as the test's `remote-vm` does. This works because filed cursor answers are kept. It is a composition duty, not a defect, but a retry-aware mint in `yin.vm` would remove it (out of scope).
3. **After a call-in gap, the VM's error message is generic.** The responder closes call-out, so the VM reads end, gets a nil value, and `call-result` raises "FFI response envelope is malformed". It is loud and terminal, but not specific. A dedicated loss message would need a change to `yin.vm.ffi` or the engine.
4. **Owner policy is still open:**
   - whether to add a per-request handler gate beyond the export gate;
   - who may renew, since the renewal medium stays reachable by anyone who learns its descriptor (S6's ShiBi seam);
   - production capacities and cadences.
5. The `lease-proposals` entry of S6 is not served. This slice needed only unsolicited grants; serving proposals is a separate feature.

## Incomplete work

None against the brief. cljstyle and CLJD were not run: cljstyle was blocked by the approval gate, and CLJD was excluded by instruction.
