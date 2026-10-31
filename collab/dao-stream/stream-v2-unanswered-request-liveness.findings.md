Completed-GMT: 2026-09-07 07:30:39 GMT
Completed-Local: 2026-09-07 14:30:39 +07 (Asia/Bangkok)
Coding-Agent: interactive
Session-ID: not-applicable (interactive seat)

# Finding: an unanswered RPC request is bounded by nothing

Raised by the user while reading the `allocator-error` defect report, and
verified against the tree by this seat. **This is not that defect.** It is a
separate, larger gap, and it is an *unowned deferral* rather than a code bug:
no line of code is wrong, and no layer has claimed the problem.

## The gap

A request is sent to a healthy peer over a healthy socket. The peer never
answers — its handler hangs, crashes without closing, drops the request, or is
simply slow beyond any useful bound. No socket `close` or `error` fires, so
the host adapter deposits nothing, so `poll!` returns `blocked` forever, so
`lose-outstanding` never runs and no completion is ever published.

The id stays in `:outstanding` permanently. The caller's own record for it —
the REPL's outstanding request, or the planned `dao.jing.v2.remote`
materialization record — never resolves. Nothing in the system will ever say
otherwise.

This is distinct from the two failure modes that *are* handled:

| case | who detects it | completion published? |
|------|----------------|-----------------------|
| socket closes or errors | host adapter's `.on "close"` / `"error"` deposits a lifecycle fact (`node.cljs:136-142`), read by `poll!` | yes, via `lose-outstanding` |
| read fails (gap, end, cursor-mismatch, transport-error) | `poll-read`'s own outcome (`rpc.cljc:386-420`) | yes, via `lose-outstanding` |
| **peer answers nothing, socket healthy** | **nobody** | **no** |

## Verified: nothing bounds it, at any layer

- `docs/design/dao.stream.md` — **zero** occurrences of "timeout" or
  "deadline". The contract has no notion of elapsed time. This is deliberate:
  "no operation waits", an operation is "a question about now", and that is
  what lets one API mean the same thing on JVM, Node and Dart.
- `src/cljc/dao/stream/rpc.cljc` — nothing bounds how long an id may sit in
  `:outstanding`.
- `src/cljc/yin/repl/driver.cljc:528-531` — `repl-step` takes `now`, "the
  host's clock reading, **recorded but not waited on: this layer has no
  deadline of its own**." The driver holds a clock and deliberately declines
  the responsibility.
- `docs/design/dao.jing.v2.implementation-plan.md:354` — "Timeouts,
  reattachment … are the driver's."

So the contract assigns it to the driver, and the driver disclaims it. Nobody
owns it.

## Affected

- **`yin.repl`** — shipped. A remote evaluation whose server never answers
  never completes. **Mitigation: manual only.** An operator disconnect
  (`driver.cljc:253-261`) calls `connect/close!`, which closes the socket and
  therefore produces the `/detached` deposit that finally loses the
  outstanding requests. A human can recover; nothing automatic does.
- **`dao.jing.v2.remote`** — planned. Decision 3 hands timeouts to the driver,
  so J3's client inherits the same gap by construction.

## Why a plain timeout is the wrong repair

It is the "switch with a stuck state" the stream plan already rejected for
flow control. A fixed deadline chosen by the waiter alone discards the work of
a slow-but-alive peer, or holds the driver for the full interval against a
dead one, and the peer never agreed to the bound. It also reintroduces a clock
into a contract that deliberately has none.

## The designated home, unclaimed

`dao.lease.md` is the shape that fits: parties agree a duration in advance and
silence past it means lapsed — no timer, no callback, no absolute time. Its
rationale names this territory (`dao.lease.rationale.md:507-512`):

> `dao.stream.ws.md` defers liveness probing, and its Deferred list awaits a
> durable home for a pause vocabulary. Both are lease-shaped, and this design
> is the plausible home for their semantics; neither is claimed until the
> transport's wire-contract gate settles what crosses the wire.

So it is **claimed as lease-shaped but not claimed as lease semantics**, and
is parked behind the ws wire-contract gate. Note also
`dao.lease.rationale.md:515` — the vocabulary's own scope discipline warns
against making "every timeout, cancellation, or lock a lease"; the three uses
it does justify include "the lifetime of a served connection", which is this
one's neighbour.

## What a resolution needs

Not proposed here, only bounded: the wire-contract gate in `dao.stream.ws.md`
settling what crosses the wire; `dao.lease` claiming liveness semantics its
rationale currently only calls plausible; and a decision about whether the
bound is per-request or per-connection — the lease vocabulary's justified use
is a connection's lifetime, whereas the symptom here is a single unanswered
request.

## Related documentation drift

`docs/design/dao.stream.implementation-plan.md` cites dao.lease's
*"Neighbouring deferrals"* section as the place declining pause and liveness.
That section is in `dao.lease.rationale.md`, not `dao.lease.md`, and the
rationale "binds nothing" by its own first paragraph. The flow-control
precondition therefore points at non-binding prose.

## Provenance

Found by the user in conversation, 2026-09-07, while reading
`collab/stream-v2-rpc-allocator-defect.findings.md`. Every citation above was
verified against the tree by this seat. No delegate was involved.
