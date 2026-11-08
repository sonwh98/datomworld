Created-GMT: 2026-11-07 12:28:xx GMT
Created-Local: 2026-11-07 19:28:xx +07
Coding-Agent: interactive (ZCode / glm-5.3-flash, master-green orchestrator seat)
Session-ID: not-applicable (interactive seat)

# OWNER ADDENDUM 2: dao.stream is framed as a stream of bytes, but is actually a stream of size-bounded chunks

## The owner's words (2026-11-07, verbatim, during the byte-cut design discussion)

> "dao.stream is framed as a stream of bytes but now it seems like its a
> stream of chunks of bytes that can vary in size"

## What this observes

The dao.stream abstraction (docs/design/dao.stream*.md) frames the payload as
a byte stream. The transport reality says otherwise: every message is
(a) CBOR-framed, (b) chunked into ::max-datagram (1200-byte) datagrams at
send, (c) reassembled under ::max-message-bytes (65536) at receive, and
(d) refused fail-closed if it exceeds (c). The chunk — the unit that crosses
the wire intact and is acknowledged as a unit — is a first-class observable
of the system, not an implementation detail. The docs lag the code: the
implementation already speaks chunks (chunks/split, partial-message
reassembly, per-message acks); the stated contract still says bytes.

## Design consequence (feeds the same reconciliation round as addendum 1)

The honest dao.stream contract is: **a stream of chunks (messages), each
carrying a byte payload bounded by the composition's message budget, with
acknowledgment per chunk** — of which "an unbounded byte stream" is a derived
convenience view built by the producer's framing. This sharpening matters for
the locus adjudication (addendum 1, point 8): if the stream's real unit is
the bounded chunk, then the chunk boundary is where transport semantics
(ack, refusal, reassembly) attach, and a stream-layer segmentation contract
(b: budget-as-property + standardized chunk sequences) is more natural than
it appeared under the byte-stream framing. It also reframes the index
builder's page cut: a page IS a chunk, chosen by the builder to fit the
chunk contract — not a byte stream that happens to get chopped at the wire.

## Handling

Both addenda (this and 1794057236000) go to the reconciliation round after
gpt-6-astra's audit lands; the architect rules on locus AND on the contract
restatement (byte stream → chunk stream) as part of the final engineer brief.

— the master-green orchestrator seat
