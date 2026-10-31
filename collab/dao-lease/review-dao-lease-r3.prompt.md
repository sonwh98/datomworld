Created-GMT: 2026-09-02 07:11:07 GMT
Created-Local: 2026-09-02 14:11:07 Asia/Ho_Chi_Minh

# Task: review `docs/design/dao.lease.md` — third round

Role: Lead Systems Architecture Reviewer

## What changed since round 2

Two things. First, a **reframe**: the user observed that a lease manager is
essentially a garbage collector for resources other than memory. That is
lineage rather than analogy — Jini's lease descends from distributed GC — and
this repository already carries the other half: `dao.jing.md` defers content
reclamation, `dao.data.btree.md` ships `walk-addresses` and calls it the mark
hook for a segment collector that does not exist, `dao.jing.dht.md` records
unbounded caching with no reclamation policy.

So the Precedent section now carries **two** instances — `yin.vm.jit.md` for
the shape, garbage collection for the problem — and a new subsection states the
spine: a tracing collector computes reachability over a closed heap and has
ground truth; a lease has none, so **a duration is a timeout standing in for
reachability.**

Second, **every round-2 finding was applied**: the holder field, the invariant
enumeration, ticks-supply-time-not-wakeup, the drain-then-judge ordering,
reclaim-before-regrant, released semantics, the grant-seeded holder bound, the
first-window gap, judge state (tenure start, pending-reclaim set, seeding from
the grantor's own act), the transit-delay direction, duration schema, durable
resource gating, and assembly-refusable medium properties.

## Scope

Read:

- docs/design/dao.lease.md          — under review
- docs/design/datom.world.md        — governing authority
- docs/design/yin.vm.jit.md         — the shape precedent
- docs/design/dao.stream.md         — the contract it claims not to touch
- docs/design/dao.stream.ws.md      — where carriage is said to live

Optional, for the GC claims only: `dao.jing.md` §Open items,
`dao.data.btree.md` (`walk-addresses`), `dao.jing.dht.md` §Operational limits.

**No source code.** No `collab/` logs.

## What to test

1. **Does the GC framing do work, or decorate?** It claims to justify
   conservatism, to legitimize the judge's state as a root set rather than the
   registry the invariants forbid, to explain mark-before-sweep ordering, and to
   confirm the delegation denial via the reference-cycle parallel. Check each.
   If any is ornament, say so — a framing that explains nothing is worse than
   none, because it invites expectations the design cannot meet.
2. **Is the fence adequate?** The document states three places the kinship
   breaks. Is that enough to stop a reader expecting completeness, precision,
   or cycle collection from a lease?
3. **Did any round-2 fix relocate its defect again?** Two rounds have now
   produced fixes that moved problems rather than closing them. Assume it
   happened again and look for it.
4. **Internal coherence after a 35% growth.** Section against section.
5. **What would an implementer still have to invent?**
6. **What is overstated?** In either direction — too cautious to be useful, or
   claiming more than the design delivers.

## Output

Findings ranked most severe first: severity | section or line | the claim | the
exact correction. Then two short sections: **whether the GC reframe was worth
making**, answered plainly yes or no; and **the thing most likely wrong that you
cannot prove is wrong.**

State plainly if you find nothing at a given severity. Read-only; make no edits.

Begin your final response with:

```
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Ho_Chi_Minh>
```
