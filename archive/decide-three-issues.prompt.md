Created-GMT: 2026-08-31 06:10:00 GMT
Created-Local: 2026-08-31 13:10:00 +07

# Role: Design authority, deciding three open questions

You are one of four models being asked the same three questions independently.
Your answers will be compared and a consensus sought. Answer decisively: for
each question pick ONE option and defend it. Do not hedge, do not propose a
fourth option unless every listed option is unsound — and if you do, say
explicitly that you are rejecting the framing and why.

## Read first

- docs/design/datom.world.md                      (authority: 4 axioms, 6 invariants)
- docs/design/dao.stream.md                       (the contract)
- docs/design/dao.stream.ws.md                    (subordinate transport spec)
- docs/design/dao.stream.implementation-plan.md (subordinate, transient)
- docs/design/adr/0003-dao-space-is-the-event-medium.md

Precedence: datom.world.md > dao.stream.md > {ws spec, plan}.

## Background

DaoStream is a non-blocking polling IO contract. Its ninth invariant is
"No operation waits. Every operation returns what is true at the moment it is
called; an answer not yet knowable arrives later as data." Seven operations,
each returning an outcome map with a closed (now: exhaustive) outcome set.

A WebSocket handle is writer+closable only. Inbound socket events are deposited
by a host-composed boundary adapter onto a stream the host wired; interpreters
read that stream with cursors. This is how callback IO gets un-inverted.

Three reviews have converged on three questions that cannot be settled inside
the subordinate documents. Decide each.

---

## Question 1 — Is `ok` from a connecting `attach!` a false success?

On a host with no blocking IO, `attach!` cannot know whether the endpoint
serves the named stream before it returns. Current design: `attach!` returns
`:dao.stream/ok` with a handle on an attachment still being established, and
how the connection resolved arrives later as a deposited event
(`not-found` vs `transport-error`), correlated by attachment identity.

One reviewer objects: the contract defines `ok` for `attach!` as "attached to
the existing logical stream", and the ninth invariant says a result states what
is true NOW. A later deposited `not-found` proves the earlier `ok` was false.
Displacement licenses postponing an answer; it does not license substituting a
false success.

**Options:**

- **1A. Keep `ok`.** Argue that `ok` truthfully reports what is locally true —
  an attachment was begun — and that the contract's `ok` wording should be
  rescoped accordingly.
- **1B. Add `:dao.stream/pending`** to `attach!`'s exhaustive outcome set,
  carrying the provisional handle and attachment identity. `ok` is reserved for
  transports that can attach synchronously (e.g. an in-memory ring buffer).
- **1C. Something else**, only if both are unsound.

Consider: 1B widens the public surface and forces every `attach!` caller to
handle two success-ish outcomes. 1A leaves a result that can later be shown
false. Which cost is right, and what exactly should the contract's `attach!`
`ok` row say afterward?

---

## Question 2 — May the WebSocket slice deposit into a ring buffer?

ADR 0003 decides: "There is no system event bus. `dao.space` is the medium."
The ws spec repeats it: "Per ADR 0003 there is no separate event bus:
dao.space is the medium." The implementation plan nonetheless makes an
in-memory ring buffer the deposit destination for the WebSocket slice, on the
grounds that the ring buffer is the slice's only implemented reader-surface
transport and dao.space is not yet built against this contract.

**Options:**

- **2A. Use a conforming `dao.space` writer** as the deposit destination in the
  slice. Ring buffers stay as isolated transport/forwarding fixtures.
- **2B. Keep the ring buffer, and amend the ws spec and ADR 0003** to grant an
  explicit, time-boxed slice-only exception naming what replaces it and when.
- **2C. Keep the ring buffer with no amendment**, arguing the ADR governs
  production composition and not a migration slice's test scaffolding.

Consider what "the medium" means: is it a claim about a *named subsystem*, or
about there being no *second* stigmergic medium? Does a ring buffer used as one
boundary's deposit destination constitute a second medium, or is it an
implementation of the same one?

---

## Question 3 — Does the deposited-event envelope violate the authority?

`datom.world.md` says: "Host events are ordinary values on an ordinary stream,
so they need no bespoke envelope, version field, or fact taxonomy to be read."

The ws spec now mandates:

```clojure
{:dao.stream/attachment <id>
 :ws/event              :ws/payload  ; or :ws/opened :ws/closed :ws/error
                                     ;    :ws/not-found :ws/transport-error
 :ws/value              <decoded>}   ; payload events only
```

This exists because one deposit medium multiplexes many attachments, so events
must be attributable; and because payload and lifecycle events must be
distinguishable. `:ws/value` is never inspected by the adapter.

**Options:**

- **3A. Amend `datom.world.md`** to permit minimal transport-owned event maps
  where correlation or lifecycle classification requires them.
- **3B. Drop the wrapper**; express correlation as fields on ordinary
  transformed event values, with no mandated envelope or event taxonomy.
- **3C. Keep the envelope and add a reconciling clause** to the ws spec: this
  carries attribution and the event classes the socket itself distinguishes,
  not a taxonomy of what values mean.

Consider: is the authority's sentence forbidding a *wrapper*, or forbidding a
*semantic vocabulary*? Does 3B actually differ from 3A/3C in what gets built,
or only in what it is called? Would a per-attachment deposit medium (one per
connection instead of one per boundary) remove the need for attribution — and
if so, is that the real answer?

---

## Output

For each question: **the option you pick**, then your reasoning in at most six
sentences, then the exact text you would put in the governing document. Then a
final section: which of the three you are least confident about, and what
evidence would change your mind.

Read-only. Do not edit files.
