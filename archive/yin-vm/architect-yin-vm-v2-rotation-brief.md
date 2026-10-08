Created-GMT: 2026-09-02 18:39:21 GMT
Created-Local: 2026-09-03 02:39:21 Asia/Shanghai

# Architect seat rotation — Fable → Sol

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-03 01:53:55 Asia/Shanghai | Status: rotated out at ~90% of a 5-hour quota | Rationale: held the seat for sign-off rounds 1-7; granted at r3 and r5, withheld at r1, r2, r4, r6
- Model: gpt-5.6-sol | Assigned: 2026-09-03 02:39:21 Asia/Shanghai | Status: active | Rationale: first fallback per architect.md; cross-family; independent standing from review-v2-plans-r2

## What the seat has settled, rounds 1-7

Sol reviewed `review-v2-plans-r2` and has not seen rounds 3-7. Those rounds
changed the plan substantially. Carried state:

**Withheld at r1, and why it mattered.** The plan requested a reject-mode ring
buffer from the sibling plan to preserve the VM's park-on-full backpressure.
Fable showed that builds a deadlock: v1 frees capacity *only* through the
destructive drain (`ringbuffer.cljc:161`), `next-outcome` never advances
`:head` (109-119), and the contract forbids eviction that waits on a reader. The
request was withdrawn; the divergence is recorded with that reason.

**Withheld at r2** on two stale sentences — the census said `take!` removed
while Phase V2 still said "redefined". Fixes that relocate their problems are
this document's recurring failure mode.

**Granted at r3.**

**Withheld at r4** after the user asked to defer telemetry. The stub as written
would have silently accepted `{:telemetry {:stream s}}` and never written to it,
because `create-vm` and `empty-state` still plumb the opt into state. A non-nil
`:telemetry` opt is now a construction error.

**Granted at r5**, with the census independently reproduced: `:position` 34 in
scope across 8 files, `:woke` 11, `IDaoStreamWaitable` 6, `drain-one!` 5,
`closed?` 1.

**Withheld at r6** after the user's design change — the host now supplies a
stream constructor (`:make-stream`) exactly as it supplies `+`, so
`dao.stream.ringbuffer` left the closure entirely. Fable found four holes,
all applied in r7, the deepest being that **the VM can no longer assume
evict-oldest**: totality over `append!`'s five outcomes is the VM's obligation,
and what a composition observes follows from the transport it supplied.

**r7 is in flight with Fable** and may grant. If it withholds, the remaining
items come to this seat.

## Standing instruction for the seat

The document has been through seven rounds. The bar is not perfection but
whether a competent engineer could execute V1-V6 without inventing an
architectural decision the plan should have made. Say GRANTED or WITHHELD
explicitly, and if withheld give the shortest list that would earn it.
