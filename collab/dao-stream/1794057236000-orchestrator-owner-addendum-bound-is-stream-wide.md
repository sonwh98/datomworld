Created-GMT: 2026-11-07 12:24:18 GMT
Created-Local: 2026-11-07 19:24:18 +07
Coding-Agent: interactive (ZCode / glm-5.3-flash, master-green orchestrator seat)
Session-ID: not-applicable (interactive seat)

# OWNER ADDENDUM: the message-bound problem is GENERAL to every dao.stream implementation

## The owner's words (2026-11-07, during the byte-cut design review, verbatim)

> "but this problem is a general problem that all dao.stream implementation faces"

## What this changes

The first architect verdict (collab/dao-space/1794057586000-architect-byte-cut-
design-review.muse-wonder-1.0.stdout.log, APPROVED-WITH-CHANGES R1-R7) scoped
byte-sized cutting to the INDEX BUILDER (dao.space.index) as the consumer that
happens to carry large datoms. The owner's generalization relocates the design
question: EVERY dao.stream implementation composes messages that must respect
the transport bound (dao.jing.dht ::max-message-bytes 65536, refused
fail-closed at send), and each one currently re-derives that discipline
privately (or discovers the bound by refusal, as the nightly did).

Required consequence for the reconciliation round (next architect step, after
the gpt-6-astra audit lands): adjudicate the LOCUS of the fix —

(a) index-builder-local (what R1-R7 currently specify): only the index store
    cuts by bytes; every other dao.stream consumer keeps its own discipline;
(b) dao.stream-layer: the bound becomes a first-class stream/transport
    contract — e.g. the composition exposes its message budget as a queryable
    property, and/or dao.stream provides standardized segmentation (a
    chunked-message-sequence protocol) so oversized logical payloads are cut
    into bounded messages ONCE, at the stream layer, with reassembly
    semantics every peer already implements;
(c) some split: transport exposes the budget + refuses overbound (already
    true); stream layer offers optional segmentation; consumers like the
    index builder still self-cut because their payload has internal structure
    (sorted pages) that only they can split semantically.

Point (c) is the likely landing zone — the index page cut is semantic
(the datom order is the index; only the builder knows legal cut points),
while the bound knowledge should come FROM the composition, not be hardcoded
(R5 already requires deriving the budget from max-message-bytes; the
generalization says every consumer must derive it the same way). But that is
the architects' call, not this seat's.

## Status of in-flight work

gpt-6-astra's audit of R1-R7 is in flight (read-only; dispatched before this
addendum — it will NOT have seen this file). Its verdict, plus this addendum,
go to the reconciliation step before any engineer re-brief. No engineer
dispatch happens until the locus question is adjudicated.

— the master-green orchestrator seat
