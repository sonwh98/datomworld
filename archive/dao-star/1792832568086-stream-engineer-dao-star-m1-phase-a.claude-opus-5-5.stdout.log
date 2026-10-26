Completed-GMT: 2026-10-24 (clock not read; no precise timestamp available)
Completed-Local: 2026-10-24, +07 (clock not read)

Phase A is implemented and its tests pass. I couldn't write the findings report or run `bb test:sub:clj dao.stream`, because both were denied by permissions.

**Files** (all in the `dao-star-m1` worktree, nothing staged or committed):
- `src/cljc/dao/stream/http.cljc`: the boundary core.
- `src/clj/dao/stream/http/jvm.clj`: the http-kit listener.
- `test/dao/stream/http_test.clj`: 11 tests.

**Checks:**
- `clojure -M:test -n dao.stream.http-test`: 11 tests, 86 assertions, 0 failures, 0 errors.
- `clojure -M:test -n dao.stream.http-test -r 'dao\.stream\..*-test'` ran 504 tests with 3874 assertions and 0 failures. I used it as a stand-in for the `dao.stream` lane. Two ws-project tests were skipped because the Dart peer binary isn't built.
- `clj -M:kondo --lint` on the three files: 0 errors, 0 warnings.
- `bb test:sub:clj dao.stream` was not run: `bb` isn't on PATH, and calling the mise shim was refused.
- The findings report was not written to the main tree's `collab/`, because the write was denied. The contents are summarised below.
- I wrote the tests and the code in one pass and ran the tests afterwards, so this wasn't strict test-first.

**Decisions to confirm:**
- **Lifetime key:** The spec's "lifetime longer than the boundary" has no key in the ws admission check. I invented `:lifetime :outlives-boundary` and require it.
- **Incarnation:** `:http/incarnation` comes from config, or defaults to a fresh uuid string.
- **Stricter than the spec:** I treat a head with a non-integer status, a chunk body that isn't a string or bytes, and an `:http/end` before the head as out of order and close the exchange. The spec names only chunk-before-head and a second head.
- **Step behaviour:** `egress-step` drains until the egress medium is empty. A writer that keeps appending could keep it running.

**Not covered:**
- The 400 path (an exception inside `accept!`) is untested.
- Egress `transport-error` shares the branch with `end`, but only `end` is tested.
- A peer disconnecting mid-response is covered only with scripted sockets. I rely on http-kit's `send!` returning false on a closed channel.
- The race where an exchange closes while its request event is still being deposited has no test.
- I did not run the host-boundary gate (`gate_test`). The cljs and cljd listeners are out of scope.
- The spec's Deferred list is left unimplemented, as intended.

I can write the report once you grant write access to that `collab/` path.
