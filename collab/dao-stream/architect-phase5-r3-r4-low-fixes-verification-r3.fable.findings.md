Completed-GMT: 2026-09-04 05:49:30 GMT
Completed-Local: 2026-09-04 12:49:30 Asia/Ho_Chi_Minh
Coding-Agent: claude
Session-ID: fde8b517-9c3f-48c6-a4d9-c455489fbebc

# Phase 5 R3/R4 low-finding corrections verification round 3

Fable resumed the same architect session and granted sign-off on all four
requested LOW corrections.

## Disposition

1. `/transport-error` routing: closed. It is no longer queueable, so ordinary
   input returns to local evaluation and no notice promises delivery through a
   fresh boundary.
2. Fresh-connection RPC cleanup: closed. Old `:unsent` work is abandoned and
   its completion crosses RPC → adapter → driver before the adapter is replaced.
3. Terminal display consistency: closed. The first terminal conclusion wins;
   later non-diagnostic lifecycle events cannot overwrite it.
4. Bind/release failure shutdown: closed. Nil resources never reach `unbind!`,
   synchronous release failures resolve locally to `:stopped`, the resolution
   is removed, and the structured reason is published without inventing a host
   close completion.

## Additional non-blocking findings

- LOW: fresh connection publishes the old adapter outbox only when `:unsent`
  exists. A same-tick request-undeliverable completion can therefore be dropped,
  although the operator already saw the immediate request-not-sent notice.
- LOW: after `/detached`, choosing a different URL replaces the adapter and
  clears queued lines without reporting how many were discarded.

The complete reasoning is preserved in the paired `.stdout.log`.

SIGN-OFF: GRANTED
