Coding-Agent: claude (claude-opus-5-5)

# Engineer: Slice S3a-1 completion report

Branch `stream-crossmachine-s3a` in worktree `datomworld-stream-s3a`, based on `01138591`. Nothing committed; the working tree holds the change for review.

## What landed

**`src/cljc/dao/stream/ws.cljc`**: `endpoint-stop!` (§4.1). For each `:pending` slot it sets the phase to `:closed`, calls `invoke-close!` with 1001 `"dao.stream/endpoint-stopped"`, deposits `terminal!` `:ws/closed` on the control medium, then calls `release-slot!`. It is idempotent and answers the endpoint.

**`src/cljc/dao/stream/ws_project.cljc`** (§4.2):
- `stop!` sets `:stopping? true`. After that, `adopt!` rejects every offer the way the session cap does: the handle is closed, no ack is written and no media are composed.
- `close-sessions!` runs `close-session-resources!` on every session and marks each `:closed?`. The next `accept-step!` reaps them.
- **One addition the spec does not list:** when a stepped link reports channel-gone, `dial-step!` now records `:gone? true` on the dial's channel atom, and the `channel` docstring names the new field. The reason: when the `:ws/closed` event has been evicted (traffic-medium gap), the ws handle is already closed, so the projection never closes. Without this flag the remote-channel dial would stay `:attached` forever, even though the link expired. `remote-channel/dial-step` treats `:attached` as `:lost` when the projection is closed or `:gone?` is set. This row of §5.2 needs it.

**`src/cljc/dao/stream/remote_channel.cljc`** (new): `production-bounds`, `descriptor-of`, `loopback-literal?`, `serve`, `serve-step`, `stop!`, `sessions`, `dial`, `dial-step`, `handle`, `close!`, following §1, §2.1, §4.3, §4.4 and §5.1.
- `:bounds` is merged over `production-bounds`, so tests can override single keys.
- Invalid bounds are rethrown by the layer that validates them (`make-endpoint`, `make-acceptor`, `remote/links`) before anything listens. The link policy is checked up front by calling `remote/links`.
- Refusals are returned as data: `::no-transport`, `::no-port`, `::invalid-table`, `::bind-failed`, `::lifecycle-lost`.
- Stop outcomes are `:confirmed`, `::unconfirmed`, `::unbind-failed` and `::host-stopped`.

**Tests:**
- `test/dao/stream/loopback_net.cljc` (new) holds the moved fixtures plus `blackhole!`, `flood!` and `unbind-on`. `test/yin/vm/linker/head_ws_test.cljc` now uses these fixtures instead of its own copies.
- `test/dao/stream/remote_channel_test.cljc` (new) has the 15 cases of §7.
- The `ws_test` case `endpoint-stop-closes-pending-connections-and-frees-slots` and the two `ws_project_test` cases from §7 are added.

**Docs:**
- `dao.stream.remote.md` §3.0 gains the bullets "Explicit stop" and "Lifecycle observation"; §3.1 gains a sentence naming `dao.stream.remote-channel`.
- `dao.stream.ws.md`: the Serving ownership bullet now names `endpoint-stop!` and the 1001 close. The Deferred Liveness bullet is rewritten: the idle probe is the consumer's own periodic read plus its deadline, and the open question is closed for remote channels.

## Deviations and judgment calls (for the reviewer)

1. **The production profile has 20 keys, not 17.** The brief says "exact 17 keys", but the §1 table has 20 rows (the 17 layer bounds plus `:slot-count`, `:capacity` and `:stop-grace-ms`). I implemented all 20 table rows, and `production-bounds-reach-every-layer` asserts the count is 20.
2. **`::invalid-table` is decided by a structural check before composing anything:** the table must be a map, each entry a map with a `:handle`, and names must be nil or a map. It is not done by catching a throw from `make-acceptor`, because that same throw also signals invalid bounds, which §2.1 says must be rethrown.
3. **A pending connection present at `stop!` is closed by `adopt!`'s rejection, not by `endpoint-stop!`.** The §4.3 order runs `accept-step!` first, and that step consumes the offer, rejects it (1000 `detached`) and releases the slot. So `stop-is-explicit-and-driver-paced` asserts that the pending connection was closed, that its `:ws/closed` reached the control medium, and that all slots are free; it does not assert a 1001 close. `endpoint-stop!` covers connections that arrive in the race between the acceptor tick and the endpoint stop. It is exercised with 1001 directly in the `ws_test` case and in `a-lifecycle-gap-while-starting-is-terminal`.
4. **The `serve-step` ticks after the first stopping tick do slightly more than §4.3 lists.** Besides draining the lifecycle medium and checking the grace, each one runs `accept-step!` (rejecting late offers and reaping closed sessions) and `endpoint-stop!` (closing pending connections that arrived after the first pass). Without this, closed sessions would stay in `sessions` forever and late pending connections would stay open until the host closes them.
5. **A host-stopped stop, or an end on the lifecycle medium, also calls `ws-project/stop!`** before closing sessions and pending connections, so nothing is adopted afterwards. A `:stopped` fact while `:starting` is also treated as `host-stopped`; the §5.1 table leaves that cell unspecified.
6. **A newcomer during stop:** §7 says its resolution is `:ws/transport-error`. Over the net, though, the client socket has already opened, so the client deposits `:ws/closed`. The dial ends `:lost` with `:dao.stream/transport-error` (reason channel-gone), and that is what the test asserts.
7. **Liveness tests poll twice** (`ask!`). Reading the value prefetches an installed `blocked` for the next cursor, so the first poll is answered locally and no request goes out.
8. **`loopback-net/unlisten!` now also deposits `:stopped`** through the listener's `deposit!`, as the §7 host-stopped case needs. `head_ws_test`'s restart case still passes, since its server just becomes `:stopped` before it is replaced.
9. **I left `yin.vm.linker.head.ws` unchanged**, so it still has its own `loopback?`. S3a-2 deletes the namespace.

## Verification

- `clojure -M:test -n dao.stream.remote-channel-test`: 15 tests, 118 assertions, 0 failures.
- `clojure -M:test -n dao.stream.ws-test -n dao.stream.ws-project-test`: 51 tests, 229 assertions, 0 failures (both run after the additions).
- `clojure -M:test -n yin.vm.linker.head-ws-test` with the moved fixture: green (59 tests across the three namespaces in that run).
- `clj -M:kondo --lint` over the three source files, the new and changed test files, and `head_ws_test.cljc`: 0 errors, 0 warnings.
- `bb test:clj`: **Ran 3699 tests containing 237960 assertions. 0 failures, 0 errors.**
- **Not run:** the Node and Dart lanes (`bb test` across all three hosts). The brief asked for the JVM lane only, and this worktree has no `node_modules` (`npm ci` would be needed first). §8 asks for a green three-lane `bb test` before commit, so the full three-host run is still owed at landing. The new code avoids the known CLJD traps (multi-key `assoc` on nil, `for` over chunked seqs, `case` on vector constants, `#?(:clj)` ordering), but that has not been checked on Dart.
