Completed-GMT: 2026-09-29 08:06:06 GMT
Completed-Local: 2026-09-29 15:06:06 +07
Coding-Agent: claude
Session-ID: f9fe29ab-85ec-4a4d-8667-ca972a237a16

# Report: yin.repl.main-test cross-process flake

## Root cause (demonstrated)

A **missed event, not a slow one**. The client sent its RPC request before its
response cursor was minted. The server then resolved that cursor past the answer,
so the answer was never read.

The path, in order:

1. `connect/open` builds the RPC client with the response cursor set to the
   `:dao.stream/newest` anchor. Over a `dao.stream.remote` reflection, that
   anchor is resolved **by the server (process A)** when the cursor request
   reaches it (`refl-cursor` → `send-or-count!`).
2. `Connected to …` is published at once (the attach is deferred). The WebSocket
   may still be `:connecting`, and while it is not `:open`, `WsHandle.append!`
   answers `:dao.stream/full` (`dao/stream/ws.cljc:207`).
3. On `:full` the two requests are handled differently:
   - The **cursor request** is dropped. It is not registered, and the next ask
     sends it again (`remote/send-request!`).
   - The **eval request** is kept by RPC as unsent. The driver sets `:retrying`,
     and `retry-unsent` resends it at the **start** of the next `repl-step`,
     *before* `poll-remote` asks for the cursor again.
4. Once the socket opens, the eval request therefore goes on the wire ahead of
   the cursor(newest) request. If A reads the request in step N (`serve/step`
   runs the mirror, then `advance-requests` evaluates and appends the answer)
   and reads the cursor request in step N+1, `newest` resolves one past the
   answer. The answer is skipped for good, and `remote-value` or `await-event`
   times out with nil.

The race only opens when the first eval line arrives before the socket
handshake completes. That timing dependence explains why the failures are bursty.

### Evidence

I added temporary instrumentation:
- In the peer's ticker: a per-tick log of the WS handle phase, `:retrying`,
  `:outstanding`, and the RPC `:cursor`.
- On `await-event` timeout in `main_test`: the parent pump's errors and replies,
  the peer's trace and probe, and A's answers medium dumped with cursors.

**Baseline run, 4 failures.** All four had the same shape:
- The first `remote-value` after attach returned nil.
- The peer's history held only `Attaching…` and `Connected…`.
- The request was still `:outstanding`, the ticker was alive (~2260 ticks, no
  error), the connection was `:established`, and the parent's `:replies` and
  `:errors` held only JVM boot noise.

So the pipe, the stdout pump and the budget are all ruled out: the peer's
driver never received the response.

**Instrumented failing run** (`reattaching-…`, `(def x 10)`). Peer log, by tick:
```
[:cursor 5 :connecting ":dao.stream/newest"]
[:retrying 7 :connecting "(def x 10)"]           ; append answered :full
[:outstanding 10 :open 5010736382359540 ":dao.stream/newest"]  ; request crossed, cursor unminted
[:cursor 19 :open "{…ringbuffer/position 5}"]    ; cursor resolved afterwards
```
A's answers medium at the timeout:
```
[… position 4] id 5010736382359540 → "10"
[:end … position 5]
```
The answer to exactly that request sits at position 4. The client's cursor was
minted at position 5, so the answer can never be read.

## Fix

In `src/cljc/yin/repl/driver.cljc` only: **no remote request is sent while the
RPC response cursor is still an unminted anchor.**

- New `response-cursor-unminted?`: the RPC client's `:cursor` is still in
  `stream/standard-anchors`.
- `handle-line`: while the cursor is unminted, the line is queued, the same way
  it is behind an outstanding, retrying or unsent request.
- `release-queue`: releases nothing while the cursor is unminted. The line goes
  out on the first tick after `poll-remote` mints the cursor. Since that mint was
  answered before any request of ours crossed, the resolved cursor is at or
  before the answer.
- `pending-write?`: now also true for a queued line waiting on the mint, so a
  host's cadence stays at the base interval instead of backing off while the
  line is held.

Reattachment is unaffected: `rpc/rebind` keeps the already-resolved cursor.

No timeouts or sleeps were changed.

## Before / after

| | Runs | Failed |
|---|---|---|
| Before (master + temporary instrumentation only, clean driver) | 20 | **4** (20%) |
| After (fix, instrumentation removed) | 26 + 10 = 36 | **0** |

The "after" count comes from two loops. The first 30-run loop reached 26/26
passes before the orchestrating session died; a second 10-run loop passed 10/10.

A partial second instrumented run (5 passes, then the failure traced above) was
used for diagnosis only, not counted.

At the baseline 20% rate, 0 failures in 36 runs has probability 0.8^36 ≈ 0.03%.

Both loops ran in one JVM: `clj -Sdeps '{:aliases {:loop {:extra-paths
["test"]}}}' -M:loop target/flake-loop.clj`, calling
`clojure.test/run-tests 'yin.repl.main-test` N times. Each run spawns fresh
peer JVMs, and the peers load `driver.cljc` from source. Logs are in
`target/flake-run{1,3,4}.log`.

## Changed files

- `src/cljc/yin/repl/driver.cljc` (+19/−4)
- `test/yin/repl/main_test.cljc` and `test/yin/repl/slice_peer.cljc` were
  instrumented temporarily and restored by hand. `git diff` shows no change to
  either file, and `grep -rn FLAKE src test/yin` finds nothing.
- The loop script `target/flake-loop.clj` is a scratch file under `target/`.

## Verification

- `clj -M:kondo --lint src/cljc/yin/repl/driver.cljc`: errors 0, warnings 0.
  The two test files are byte-identical to HEAD.
- `cljstyle check src/cljc/yin/repl/driver.cljc`: **blocked**. The command
  needed permission approval in this session and was not run.
- `clj -M:test -n yin.repl.main-test -n yin.repl.driver-test`: 30 tests,
  173 assertions, 0 failures, 0 errors.
- Mid-task, after the fix: `clj -M:test` on the yin.repl namespaces adapter,
  connect, driver, embed, serve-connect-wire, serve and `yin.repl-test`:
  84 tests, 390 assertions, 0 failures, 0 errors.
- `clj -M:test` (full): 2343 tests, 184065 assertions, 0 failures, 0 errors.
  One pre-existing skip: `a-dart-dialer-reads-a-jvm-server`, because
  `build/ws-project-peer` is absent.
- `bb test:cljs`: 2249 tests, 50589 assertions, 0 failures, 0 errors.
  `yin.repl.main-test` and `yin.repl.driver-test` both ran.
- `bb test:cljd`: not run, as instructed.

## Cross-host / unresolved

- **The Dart peer is affected.** It runs the same `yin.repl.driver`, so the race
  exists on every host. `build/yin-repl-peer` (built 11:46, before this change)
  still has the old driver. The JVM test `a-dart-client-attaches-to-this-jvm-server`
  and the Node pair ran against that stale exe and passed, but they can still
  flake the same way until the peer is rebuilt with
  `bb build:yin-repl-peer`. I did not rebuild it.
- **Follow-up in `dao.stream.rpc` (outside my authorized files, not changed).**
  The latent bug is general: any `rpc/client-state` caller that passes
  `stream/anchor-newest` over a remote reflection can call `request!` before
  `poll!` has minted the cursor and lose the answer. Suggested change:
  `rpc/request!` answers a non-allocating outcome (for example
  `:dao.stream.rpc/cursor-pending`) while `(:cursor state)` is in
  `stream/standard-anchors`. That makes the driver guard redundant. It needs an
  Architect/owner decision.
- **No deterministic regression test was added.** `driver_test.cljc` is outside
  the allowed files. Suggested test: build a driver state whose RPC client
  cursor is `:dao.stream/newest` with a reader that answers the retryable mint,
  submit a remote line, and assert that nothing is appended to the writer and
  the line is `:queued`. Then let the mint succeed and assert the line is sent.
