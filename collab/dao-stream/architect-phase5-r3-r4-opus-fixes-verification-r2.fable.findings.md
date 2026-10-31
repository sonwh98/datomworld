Completed-GMT: 2026-09-04 05:36:30 GMT
Completed-Local: 2026-09-04 12:36:30 Asia/Ho_Chi_Minh
Coding-Agent: claude
Session-ID: fde8b517-9c3f-48c6-a4d9-c455489fbebc

# Phase 5 R3/R4 Opus blocker fixes verification round 2

Fable resumed the architect session that previously withheld sign-off and
reviewed the real current unstaged delta with read-only `git diff` access.

## Prior blocker disposition

1. HIGH — JVM `(quit)` did not exit: closed. The poll-loop step owner invokes
   the injected/default exit after draining the endpoint; the reader is not
   awaited. The regression test asserts one exit invocation.
2. MEDIUM — operator-disconnect reconnect trap: closed. The temporary guard is
   limited to the pre-terminal window; terminal status is preserved and later
   connect composes a new boundary or reattaches as appropriate.
3. MEDIUM — stale RPC `:unsent`: closed. Pure portable RPC transitions report
   abandonment as a completion, clear retained work, preserve monotonic IDs,
   and keep retry ownership in one driver path. Tests cover ordering,
   disconnect, reattach behavior, and the RPC contract.
4. MEDIUM — never-bound endpoint shutdown: closed for the named cases. An
   endpoint with no serving driver stops immediately without inventing a host
   completion; the JVM join budget now covers the entire bounded drain.

Related LOW fixes also passed: non-reattachable terminals return input locally,
the clockless `accept!` arity is gone, incomplete input uses a serve-owned code,
and the Node `verifyClient` arity/deprecation constraints are documented.

## Remaining non-blocking findings

- LOW — `/transport-error` queues lines while a fresh `open-connection` later
  clears that queue. Either route locally or define queue carry-over semantics.
- LOW — a bind that throws may leave `:serving` present without resources and
  spend the full stop budget waiting for a host completion that cannot arrive.
- LOW — `open-connection` should defensively abandon RPC `:unsent`, matching
  the reattach path, even though real hosts complete it through the closed
  writer first.
- LOW — post-terminal connection observation can display `:detached` while the
  RPC terminal governing behavior remains `/transport-error`.

Passed properties include one resend path, portable completion and request-ID
invariants, ordered queued lines, step-owner exits on all hosts, honest
host-sourced `:stopped`, and no new hidden state or implicit control flow.

Standing deferred risks remain: CLJD is compile/analyze verified but not test
executed; pending pre-accept sockets are not closed by `serving/stop!`; two
shells remain under D4; the Node host seam is unwired; canonicalizers are
aligned but duplicated; and interactive JVM/Dart Ctrl-C shutdown with `--port`
is not yet wired.

SIGN-OFF: GRANTED
