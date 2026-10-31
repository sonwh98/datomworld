Created-GMT: 2026-09-01 15:51:46 GMT
Created-Local: 2026-09-01 22:51:46 Asia/Ho_Chi_Minh

# Task: evaluate leases as the general mechanism for attachment lifetime

Role: Lead System Architect

## The proposal

The user observes that four separate problems in the current design share one
shape — *how do I stop caring about something that may never answer* — and
proposes the Jini lease as the single mechanism, rather than four ad-hoc ones:

1. **Pause requests** (plan 4d) are already a lease: hold-for-*n*, renewed
   while still needed, expiring on its own if the reader dies.
2. **Liveness** (open finding 6) is currently ping/pong plus an arbitrary "drop
   after two unanswered", and the three of you split three ways on where the
   mechanism and its parameters belong.
3. **A dead or hostile client** holding server-side resources — currently
   answered by arguing that nothing accumulates, since eviction is
   unconditional.
4. **A connection cancelled before it resolves** (open finding 3), where a
   waiter can wait forever for an answer that will never come.

**The proposal**: an attachment is a lease. A client asks for it for a bounded
duration, renews while it wants it, and stops renewing when it does not — or
when it dies. The serving side reclaims on expiry, having detected nothing.
Renewal *is* the liveness signal, so ping/pong and the drop threshold cease to
exist as separate mechanisms.

## The question put to you

Answer these directly. Take a position; do not survey.

**1. Is the lease the right general mechanism here, or is the resemblance
between those four problems superficial?** If superficial, say which of the
four it genuinely fits and which it does not.

**2. Does it violate the authority?** Jini leases require the granting side to
hold a table of who holds what and when it expires. `datom.world.md` denies a
"registry of who is listening", and `dao.stream.md` makes a stream hold no
reader positions. The orchestrator's reading is that this distinguishes two
layers: at the **connection** layer per-client state already exists (a socket,
an identity, a forwarder), so an expiry adds nothing hidden; at the **stream**
layer a lease table would be exactly the forbidden registry, which is why
eviction is unconditional and a lagging reader gets a `gap` rather than a
negotiation. Is that line correct, or is it a rationalization?

**3. What does it actually subsume?** Be specific about which open findings
collapse, which change shape, and which are untouched. In particular: does it
resolve finding 6 by deleting the mechanism under dispute, and does it change
finding 5 (where the pause vocabulary belongs) if pause is one instance of a
general lease rather than a special case?

**4. Does it fix finding 3?** The orchestrator's view is that it does not: a
lease governs how long you *hold* something, while finding 3 is about
*delivering an answer once* — so a lease would make waiting bounded without
making the missing answer unnecessary, and the cancelled case still needs a
name. Agree or refute.

**5. What does it cost?** Renewal traffic, clock assumptions across hosts,
what happens when a renewal is in flight as the lease expires, whether the
grantor may refuse or shorten a requested duration, and what a client observes
when its lease lapses under it. Name what would have to be specified that is
not specified now.

**6. Where would it live** — contract, ws spec, plan, or a new document — and
why. Note that the contract's operations take no lease parameter today, and
that adding one would change the public surface.

## Scope

Read: `docs/design/dao.stream.md`, `docs/design/dao.stream.ws.md`,
`docs/design/dao.stream.implementation-plan.md`,
`docs/design/datom.world.md`. **No source code.** No ADRs, no `dao.space`
documents.

Propose only. **Make no edits.**

Begin your final response with:

```
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Ho_Chi_Minh>
```
