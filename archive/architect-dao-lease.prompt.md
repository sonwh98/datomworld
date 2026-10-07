Created-GMT: 2026-09-01 16:20:01 GMT
Created-Local: 2026-09-01 23:20:01 Asia/Ho_Chi_Minh

# Task: leases as datoms — evaluate, then propose `dao.lease.md`

Role: Lead System Architect

## Background

Four problems in the current DaoStream design share one shape — *how do I stop
caring about something that may never answer*: pause requests, connection
liveness, a dead client holding server-side resources, and a connection
cancelled before it resolves. The team has already agreed a lease fits the
first three and not the fourth.

The open question is bigger than DaoStream. Java Jini solved this class of
problem with the **lease**: a grant that lapses unless renewed, so a dead
holder's resources are reclaimed without anyone detecting death, and without
consensus.

Jini's *concept* looks right. Jini's *design* may not fit this project:

- `net.jini.core.lease.Lease` is an **object with methods** — `renew(duration)`,
  `cancel()`, `getExpiration()` — held by the grantee. `renew` is a synchronous
  remote call that blocks and fails by exception.
- `LeaseRenewalManager` renews on the holder's behalf from a background thread.
- `getExpiration()` returns **absolute** time in the holder's milliseconds.
- A lease is a live object: it cannot be appended to a stream, stored,
  replayed, or interpreted from another perspective.

## The proposal to evaluate

Take Jini's insight and express it in datom.world's own terms:

- A lease is **facts, accreted**. The grant is a datom; a renewal is another
  datom. No lease object exists; the record of the lease is the trace on the
  medium.
- **Expiry is an interpretation, not an event.** Nothing fires when a lease
  lapses. An interpreter reads the most recent renewal fact, compares it to its
  own clock, and concludes lapse. Per Axiom 2, "expired" is a perspective taken
  on facts, and two interpreters with different tolerances may legitimately
  disagree.
- The mechanism therefore needs **no new mechanism** — only a vocabulary. Grant
  by appending, renew by appending, lapse by reading and judging.
- This lands on the project's coordination model: a holder leaves a trace
  saying *I still want this, as of t*; the grantor encounters the absence of a
  recent trace and reclaims. Nobody registers, nobody is notified.

## Your task

**1. Is this a good idea?** Answer plainly. If it is wrong, say so and say
why — a well-argued rejection is more useful than a reluctant yes. Consider at
minimum:

- whether "expiry is interpretation" survives contact with a grantor that must
  actually reclaim a socket at a particular moment;
- whether leases-as-datoms conflicts with any of the four axioms or six
  non-negotiable invariants;
- whether it is genuinely more general than a DaoStream connection mechanism,
  or whether that generality is speculative;
- clock discipline with no absolute time on the wire;
- what happens when renewal facts are evicted by bounded retention;
- whether a hostile holder gains anything by renewing forever.

**2. If it is a good idea, propose the specification.** Produce the content of
a new `docs/design/dao.lease.md` — a datom.world-level document, not a
DaoStream one. It must be derived from and subordinate to
`docs/design/datom.world.md`, and must state which axioms and invariants it
rests on. Cover at least: what a lease is as data; the grant, renewal, release
and lapse vocabulary; whose clock decides and why no absolute time crosses a
boundary; what an interpreter must do to judge lapse; what a lease may never
do; and what is explicitly out of scope.

**3. Say what it does *not* replace.** Be specific about which of the four
problems above it solves, which it only bounds, and which it leaves untouched.

**4. Name the seam to DaoStream.** `dao.stream.ws.md` would own only carriage —
which frames carry a renewal on a WebSocket, and the close code when an
endpoint reclaims. Confirm or correct that split.

Write the proposed `dao.lease.md` content in full, in the voice of the
existing design documents: declarative, reasons stated alongside rules, prose
over bullet lists where a sentence does the work.

## Scope

Read: `docs/design/datom.world.md` (the authority), `docs/design/dao.stream.md`,
`docs/design/dao.stream.ws.md`,
`docs/design/dao.stream.implementation-plan.md`. **No source code.**

Propose only. **Make no edits** — do not create the file.

Begin your final response with:

```
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Ho_Chi_Minh>
```
