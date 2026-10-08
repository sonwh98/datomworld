Completed-GMT: 2026-09-04 05:25:00 GMT (estimated by reviewer; no clock or shell)
Completed-Local: 2026-09-04 12:25:00 Asia/Ho_Chi_Minh (estimated)
Coding-Agent: claude
Session-ID: none

# Phase 5 R3/R4 Opus fixes verification

Fable performed a read-only static review of the unstaged Opus delta. The full
report is preserved in the paired `.stdout.log`.

## Blocking findings

- HIGH — `src/cljc/yin/repl.cljc`: the JVM poller returns after draining the
  server, but process halt remains on the main thread behind a blocking
  `read-line`. A typed `(quit)` stops the endpoint but does not exit until EOF.
- MEDIUM — `src/cljc/yin/repl/driver.cljc` and `connect.cljc`: the new
  "Still disconnecting" guard can trap every future `(connect ...)` after a
  non-reattachable terminal has already arrived. Gate only while no terminal
  has landed, preserve terminal status, and test a fresh connection after a
  transport error and operator disconnect.
- MEDIUM — `src/cljc/yin/repl/driver.cljc` and the RPC client: clearing the
  driver's `:retrying` value does not clear the RPC client's `:unsent` request.
  It can resurface after reattach and silently replace the operator's next
  line. Abandon/report unsent work explicitly and make retry ownership
  consistent.
- MEDIUM — `src/cljc/yin/repl/serve.cljc` and host shutdown loops: `stop!`
  changes a failed, never-bound endpoint into `:stopping`, but no host can ever
  deposit `:stopped`; every host waits the full budget and prints a misleading
  timeout. Treat an endpoint without `:serving` as already stopped/no-op.

## Lower findings

- JVM shutdown budget has an off-by-one step/join mismatch.
- Non-reattachable remote terminals still route ordinary input into a queue.
- The 2-arity `serve/accept!` can omit the clock and recreate the expiry bug.
- Incomplete source uses the envelope-owned `malformed-request` code rather
  than a serve-owned application error.
- Node `verifyClient` depends on the generated callback having arity two.
- Interactive JVM and Dart signal shutdown remain incomplete.
- Client and Node path canonicalizers agree in traced cases but remain copies.

## Prior finding disposition

1. Operator disconnect: partially closed; local routing and driver queues are
   fixed, but RPC `:unsent` and terminal reconnect paths remain defective.
2. Cross-attachment incomplete input: closed; error vocabulary remains a LOW.
3. Node admission clock: closed.
4. Quit/EOF/headless stop: partially closed; host stepping is explicit and
   bounded, but typed JVM quit does not exit and never-bound endpoints timeout.

Passed properties include same-tick event ordering, explicit admission expiry,
pre-upgrade subprotocol refusal, query/dot-segment canonicalization, honest
host-sourced `:stopped`, explicit port-zero refusal, host-seam documentation,
and reader-conditional portability hygiene.

SIGN-OFF: WITHHELD
