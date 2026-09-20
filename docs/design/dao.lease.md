# DaoLease

Status: proposed design target, derived from and subordinate to
[`datom.world.md`](./datom.world.md). This document is the operative contract:
every sentence below is a rule. Why the design is this shape is in
[`dao.lease.rationale.md`](./dao.lease.rationale.md), which binds nothing.

A lease is a grant that lapses unless renewed. It adds no operation to any
contract and no key to any DaoStream result map, leaving
[`dao.stream.md`](./dao.stream.md) unchanged by its existence.

## Vocabulary

Facts are plain data on ordinary streams, classified by two dispatch keys:
`:dao.lease/status` for the negotiation, `:dao.lease/event` for evidence. A
reader switches on both. A fact carrying neither is not a lease fact and is
ignored; a fact carrying both is defective.

+----------+-----------------------+---------+---------------------------------------------+---------------------------------------------------------------------------+
| Fact     | `:dao.lease/status`   | Author  | Identity carried                            | Also required                                                             |
+==========+=======================+=========+=============================================+===========================================================================+
| Proposal | `:dao.lease/proposed` | holder  | `:dao.lease/proposal`                       | `:dao.lease/subject`; `:dao.lease/duration` optional, an ask              |
+----------+-----------------------+---------+---------------------------------------------+---------------------------------------------------------------------------+
| Grant    | `:dao.lease/accepted` | grantor | `:dao.lease/lease`, plus                    | `:dao.lease/subject`, `:dao.lease/holder`, `:dao.lease/duration`;         |
|          |                       |         | `:dao.lease/proposal` if answering one      | `:dao.lease/max` optional                                                 |
+----------+-----------------------+---------+---------------------------------------------+---------------------------------------------------------------------------+
| Refusal  | `:dao.lease/rejected` | grantor | `:dao.lease/proposal`                       | —                                                                         |
+----------+-----------------------+---------+---------------------------------------------+---------------------------------------------------------------------------+
| Release  | `:dao.lease/released` | holder  | `:dao.lease/lease`                          | —                                                                         |
+----------+-----------------------+---------+---------------------------------------------+---------------------------------------------------------------------------+
| Reclaim  | `:dao.lease/lapsed`   | grantor | `:dao.lease/lease`                          | `:dao.lease/cause` — `:silence`, `:release`, `:cap`, or `:policy`         |
+----------+-----------------------+---------+---------------------------------------------+---------------------------------------------------------------------------+

+------------+----------------------+---------+----------------------+----------------------+
| Evidence   | `:dao.lease/event`   | Author  | Identity carried     | Also required        |
+============+======================+=========+======================+======================+
| Renewal    | `:dao.lease/renewal` | holder  | `:dao.lease/lease`   | —                    |
+------------+----------------------+---------+----------------------+----------------------+
| Tick       | `:dao.lease/tick`    | adapter | —                    | `:dao.lease/reading` |
+------------+----------------------+---------+----------------------+----------------------+

**Keys.**

- `:dao.lease/lease` — the lease's identity, minted by the grantor, carried by
  every fact about it, never reused within that grantor.
- `:dao.lease/proposal` — a proposal's identity, minted by the holder, echoed
  by the grant or refusal answering it.
- `:dao.lease/subject` — what is leased, in whatever plain data the domain uses.
- `:dao.lease/holder` — the party whose renewals count, in whatever form the
  medium's attribution takes.
- `:dao.lease/duration` — a single-entry map from a unit keyword to a positive
  integer magnitude. `:dao.lease/max`, `:dao.lease/reading` and a judge's
  tolerance use the same representation; tolerance may be zero.
- `:dao.lease/cause` — why a reclaim happened.

**Units.** The unit keywords and their ratios are a composition's
interoperability decision, fixed once and shared by its grantor, holders and
judge. Comparison normalizes to the finer of two units, and is strict: an
interval exactly equal to its bound has not yet passed it.

**Validity.** A fact is defective, and establishes nothing, when it omits a key
its status or event requires; carries a status or event outside the tables
above; carries a duration, cap or reading that is not a single-entry map of a
fixed unit to a positive integer; carries a lease id on a proposal; answers one
proposal with both `:accepted` and `:rejected`, or repeats `:accepted`,
`:released` or `:lapsed` for one lease; or carries a reading older than one
already observed. A judge reading a defective fact has read no lease fact.

## Authority

- **Only grantor-authored facts establish terms.** A holder cannot grant itself
  tenure. A holder fact establishes no term; a delayed holder renewal is still
  evidence and counts as such.
- **A renewal counts only when its attributed author is the lease's holder.**
  A renewal from any other author is a fact about that author, and silence is
  measured as though it had not arrived.
- **At most one of `:accepted` or `:rejected` answers a proposal.**
  Renegotiating is a new proposal with a new proposal identity.
- **A proposal creates no state.** Nothing exists until `:accepted`. A grantor
  owes no answer, and a holder's wait on an unanswered proposal is bounded by
  nothing in this vocabulary.
- **A grantor may grant unsolicited**, with no proposal, in which case the
  holder is the party the grant is delivered to.
- **A `:policy` cause may not consist of declining to count evidence
  received.** Eligible renewals are counted whether or not another condition
  ends the lease.

## Prohibitions

- A lease may not extend retention, defer eviction, or gate history, and no
  DaoStream operation may consult a lease.
- No timer holds a callback and no notification marks a lapse.
- No absolute time appears in any fact.
- A reclaim frees the resource, never the record.
- A holder renews from its own control flow. No machinery renews on its behalf.

## Time

Time reaches lease code only as data; neither judge nor holder reads a host
clock. Both read a tick stream their composition wires. Readings on one stream
never decrease, and a composition persisting a ledger across a restart keeps
its readings comparable across it.

A tick-stream gap makes a pass late, not wrong; a lease-fact gap is governed
under *Judging with incomplete evidence*.

## The judge

The judge is composed **inside the boundary that possesses the resource** and
reclaims by acting on what it itself holds; one that does not possess the
resource is outside this contract. It concludes and reclaims in one
interpreter, which is not the forbidden collapse: it is the possessing host
interpreter performing its own act.

**Ledger.** The judge keeps, privately, for each live lease:

- its **terms** — subject, holder, duration, cap;
- its **tenure start**, the reading the grant was seeded at, which no renewal
  changes;
- its **last relevant observation**, the reading of the grant or newest
  eligible renewal, whichever is later;
- its **evidence state** — `known`, or `unknown` with its resumed reading;
- its **reclaim state** — `live`, or `pending` carrying the classified cause
  and whether the reclaim succeeded.

A lease **enters** the ledger when the grantor grants it, seeded from that act,
and **leaves** once its reclaim has succeeded and its `:lapsed` record has been
appended with `:dao.stream/ok`. Until both, it remains.

The ledger is not rebuildable from any stream: a composition either persists it,
as a stream its owner writes and reads, or recovers by the rule under *Restart*.

### The pass

A pass classifies nothing until a first tick has been observed. At each pass,
in this order:

1. **Drain every wired tick cursor** to `:dao.stream/blocked`. The newest
   reading drained, or the newest previously observed if none were drained, is
   **now** for the whole pass.
2. **Drain every wired lease-fact cursor** to `:dao.stream/blocked`, stamping
   each fact with that same *now*, and apply it: an eligible renewal advances
   the lease's last relevant observation to *now* and returns it to `known`; a
   valid `:released` from the holder marks it released; a `:dao.stream/gap`
   marks every lease on that medium `unknown` with *now* as its resumed
   reading.
3. **Answer and grant.** Proposals drained this pass may be answered, and
   unsolicited grants authored, both seeded and stamped with this pass's *now*.
   A grant authored between passes is seeded with the next pass's *now*.
4. **Classify.** A lease is due for reclaim when any of these holds, and the
   first that holds in this order is its cause: it is already `pending`, and
   keeps the cause it carries; a valid `:released` from its holder has been
   observed, `:release`; its tenure start is further back than
   `:dao.lease/max`, `:cap`; the grantor's own policy ends it, `:policy`; its
   interval since the last relevant observation exceeds duration plus
   tolerance, `:silence`. A lease whose evidence state is `unknown` is due for
   `:silence` only once, in addition, a full duration has passed since its
   resumed reading.
5. **Reclaim** each due lease by its composition-supplied procedure, marking it
   `pending` with its cause first. The procedure is idempotent and reports
   whether it succeeded; a reclaim that fails or does not report leaves the
   lease `pending` and unrecorded.
6. **Record** `:lapsed`, carrying the cause the ledger holds, for each lease
   whose reclaim succeeded. On `:dao.stream/ok` the lease leaves the ledger;
   otherwise it stays `pending` and the next pass reaches it again through the
   first clause of step 4.

The record follows the act and never precedes it. **The absence of a `:lapsed`
fact is never evidence of tenure.**

A drain that ends in `:dao.stream/end` retires that cursor for subsequent
passes. A drain that ends in `:dao.stream/transport-error` ends the pass before
step 4.

### Judging with incomplete evidence

Absence is evidence only over a window the judge observed. After a
`:dao.stream/gap`, a lease with no renewal in the retained tail is `unknown`,
and a surviving older renewal is not inferred to be the newest. A lease
recovered from a persisted ledger is `unknown`. Conditions other than
`:silence` apply to an `unknown` lease unchanged.

### Restart

A grantor that has lost its ledger **reclaims and re-grants**: for each
resource it still possesses it performs the reclaim first, then grants afresh.
The reclaim ends the prior tenure; a new grant alone does not.

This requires an inventory of possessed resources and a recoverable holder for
each, both of which belong to the resource rather than to this vocabulary.
A grantor with neither persists its ledger instead.

## The holder

- **Observe the grant before acting**, and establish that the grantor authored
  it by the same attribution its composition supplies to the judge. A holder
  that has not observed its grant holds nothing.
- **Renew at less than half the duration**, measured against ticks on its own
  tick stream. A renewal is an append returning `:dao.stream/ok`; any other
  outcome does not advance the holder's bound.
- **Stop acting at the bound** — the earlier of the duration since the later of
  its last renewal and its observed grant, and the cap the grant carries —
  whether or not anything has been heard.
- **Release when done.** The grantor still performs the reclaim and records it.

For cap purposes, the holder measures the cap interval from the reading at
which it observed the grant; that reading may be later than the grantor
judge's tenure start. The holder's observation-flight interval does not
extend, delay, or alter the judge's cap or reclaim decision; a resource
requiring exclusion must enforce it through fencing.

The bound bounds attention, not access; a holder needing exclusion obtains it
from the resource.

## Composition duties

A composition that grants leases owes all of the following.

- **A judging interpreter, and a runtime driving it at a declared cadence** —
  the maximum interval between *completed* passes.
- **A tick stream** for the judge, and one for every holder.
- **A tolerance** supplied to the judge, possibly zero.
- **An attribution resolver** for the judge and for every holder observing a
  grant — its own answer to who authored a fact, by per-author media, an
  envelope key, or a transport's attachment identity, since DaoStream supplies
  none. One handed no resolver, or one incompatible with its medium, is refused
  at assembly.
- **A reclaim procedure per subject**, idempotent, reporting success.
- **A stream the grantor writes grants and `:lapsed` to**, and the medium each
  recipient reads its carried facts from.
- **Media that either retain without eviction or declare evict-oldest**,
  refused at assembly otherwise.
- **For durable resources, three things this vocabulary does not supply**: a
  judge whose durability matches the resource, a rule for which incarnation may
  reclaim, and fencing from the resource. A composition that has not settled
  all three keeps its leases process-scoped.

## Sizing

Stated as relations; the values belong to compositions.

- The granted duration exceeds twice the holder's renewal interval.
- The judge's tolerance covers expected flight time and the rate skew between
  its tick stream and the holder's.
- A medium's retention window exceeds the judge's lag — cadence plus drain time.
- A cap below the duration is permitted, and tenure then ends at the cap.

## Limits

- **A false lapse is possible.** Delay errs against the holder while a renewal
  is unobserved and for it once observed, so a late-observed renewal from a
  dead holder buys a fresh duration, and continuity is lost when none is
  observed before a completed pass finds the interval past duration plus
  tolerance. Partition length alone decides neither.
- **`:dao.lease/max` binds within one ledger lifetime.**
- **A medium that gaps more often than one duration** never completes an
  observation window and leaks continuously while every component reports
  healthy; per-attachment media are the isolation.
- **Reclamation is bounded by** duration plus tolerance plus cadence plus the
  reclaim's own cost, given an uninterrupted observation window.

## Carriage

Proposals, grants, refusals, releases and renewals cross a boundary as ordinary
payload, carried by the composition to the medium the recipient reads. Carriage
is delivery, not a second authoring.

`:lapsed` does not cross; it is the grantor's record on the grantor's stream. A
remote holder learns of a reclaim by observing it — for a served connection, an
ordinary `:ws/closed`. No close code is assigned here; distinguishing reclaim on
the wire belongs to `dao.stream.ws.md`'s deferred close-code design.

## Out of scope

- **Delegated renewal.** A renewal is evidence about its author.
- **Transfer of a lease between holders.**
- **Who may be granted anything at all**, and **what is leasable** — this
  contract gates nothing, and the domain decides.
