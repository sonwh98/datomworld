Created-GMT: 2026-09-02 07:33:00 GMT
Created-Local: 2026-09-02 14:33:00 Asia/Ho_Chi_Minh

# Task: review `docs/design/dao.lease.md` — fourth round

Role: Lead Systems Architecture Reviewer

## What changed since round 3

Every round-3 finding was applied, and one section was rewritten on a new
argument from the user.

**The GC framing was corrected, not merely trimmed.** Round 3 established that
the lineage runs Gray & Cheriton (1989, cache consistency) → Network Objects →
RMI DGC → Jini, and that **this branch is reference counting with decay, not
tracing**. The repository's three open reclamation problems are the tracing
branch. Consequences applied: the "root set" claim is deleted (the anti-registry
argument stands without it); "mark before sweep" is replaced by the ordering
rule stated plainly, since nothing is traced or marked; "conservatively" became
"erring toward retention, without being able to guarantee it"; the "invented by
DGC" error is corrected; and a new fence rail states that a lease traverses
nothing and collects no cycles.

**The judgment pass** now drains the tick cursor first (nothing advanced time
before), stamps facts with that pass's reading, judges on interval *or* release
*or* cap, reclaims, then records `:lapsed` with a cause in every case. The judge
is stated to be composed **inside the boundary that possesses the resource**.

**Also applied**: the ledger's exit rule; `:dao.lease/proposal` split from
`:dao.lease/lease`; `:accepted` carried to the holder; renewals now oblige the
grantor to honour granted time; re-grant's hidden inventory admitted; the
shared-medium gap leak added as a third limit; attribution refusal scoped to a
composition-supplied resolver; duration pinned to a single-entry unit map.

**And a generalization from the user, which replaced the "cancellation is out of
scope" paragraph**: cancellation *is* a lapse. To cancel something ongoing is to
stop wanting it continued, and non-renewal says exactly that — stronger than a
cancellation flag, because a flag needs its setter alive while a lease cancels
when the canceller dies. The stated boundary is that a lease ends continuation,
not consequence: it cancels an activity's future and never its past.

## Scope

Read:

- docs/design/dao.lease.md          — under review
- docs/design/datom.world.md        — governing authority
- docs/design/yin.vm.jit.md         — the shape precedent
- docs/design/dao.stream.md         — the contract it claims not to touch
- docs/design/dao.stream.ws.md      — where carriage is said to live

**No source code.** No `collab/` logs.

## What to test

1. **The cancellation generalization.** It is new and unreviewed. Is "a lease
   that is not renewed is a cancellation" sound, or does it overreach? Is
   "ends continuation, not consequence" the right boundary, and is it drawn
   where the document says? Does it change what else in the document must be
   true — for instance, does a holder that is itself an interpreter now need a
   rule the vocabulary does not give it?
2. **Did a round-3 fix relocate its defect?** Three rounds have each produced
   fixes that moved problems rather than closing them. Assume it happened
   again.
3. **Is the refcounting-with-decay framing now correct**, and is the tracing
   vocabulary fully purged? A residue of the wrong branch is worse than the
   original error, because the correction advertises accuracy.
4. **The five-step judgment pass** is the most-rewritten passage in the
   document. Read it as an implementer: is it executable as written?
5. **Internal coherence.** The document is now 815 lines, grown 60% across two
   rounds. Section against section.
6. **What is overstated, in either direction?**

## Output

Findings ranked most severe first: severity | section or line | the claim | the
exact correction. Then: **is this document finishable, or is it accreting
faster than it converges?** Answer plainly — three rounds of fixes have each
found the previous round's fixes wanting, and if that is a trend rather than a
tail, say so and say what would stop it.

State plainly if you find nothing at a given severity. Read-only; make no edits.

Begin your final response with:

```
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Ho_Chi_Minh>
```
