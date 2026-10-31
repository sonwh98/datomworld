I’ll verify the revised two-file diff against the surrounding outcome, cursor, lifecycle, and surface rules, with particular attention to whether the new lead invariant is implementable and complete for both writable and read-only transports.

I’m using the repository’s `code-review-pr` checklist again because this is the follow-up contract-diff review; I’ll apply it narrowly to the accepted corrections and any new contradictions.

Completed-GMT: 2026-09-08 14:22:34 GMT  
Completed-Local: 2026-09-08 21:22:34 +0700 (Asia/Bangkok)  
Coding-Agent: codex  
Session-ID: 01a080de-1a15-7d23-9a35-4106b127e4f0

# Architect confirmation

## Verdict

**Not quite ready to commit.** The origin-cursor and cursor-level wording corrections are closed. The `full`/`gap` correction is substantially right, but its lead obligation remains incomplete for reader-only histories and conflicts with the contract’s rule that a handle’s writer surface may be on a different sequence.

## Remaining finding

### P1 — The lead completeness obligation assumes an append-created, same-sequence history

File: [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:711)

**Blocks commit: yes.**

The new lead sentence is:

> a value whose append answered `ok` remains observable for as long as that logical stream exists.

Two cases escape it:

1. A finite immutable/read-only history has no `append!`. The sentence is therefore vacuously satisfied even if that transport exposes only a suffix. Yet the following paragraph correctly declares such a transport valid.

2. DaoStream permits a handle’s writer surface to represent an ordered outbound path rather than the sequence exposed by its reader surface. An `ok` append on such a writer does not imply that the value becomes observable through the complete-history reader. The new sentence currently contradicts that distinction in the Writing and Concurrency sections.

**Concrete change:** define completeness on the reader’s logical sequence first, then conditionally connect a writer that appends to that same sequence. For example:

> A complete-history reader begins at its logical sequence’s origin and never evicts an element of that sequence. Every element present at creation, and every value an append places on that same sequence with `ok`, remains part of it for as long as the logical stream exists. If the medium cannot serve retained history, it reports `transport-error`; it never silently substitutes a suffix.

Then retain the current derivations:

- `:oldest` stays at origin.
- `gap` is impossible.
- A writer onto that sequence with declared finite capacity returns `full` without append or eviction.
- A logically unbounded writer may exclude `full`.
- Physical exhaustion is `transport-error`.
- A read-only complete history owes no writer outcome.

That completes the checklist for both the forthcoming writable log and immutable reader-only transports.

## Corrections confirmed closed

- **Origin cursor:** correctly limited to detection on transports that can evict. The added statement that complete-retention transports need no kept origin cursor is accurate.
- **Cursor-level promises:** all five identified sites now correctly make gap visibility conditional on a cursor spanning eviction.
- **WebSocket design:** its cursor-before-delivery assembly remains consistent; the revised wording no longer overpromises detection to fresh readers.
- **Outcome exclusions:** the ring buffer’s exclusion of `full` is now described precisely.
- **No predicate:** retention remains declared composition provenance, never handle introspection.
- **No `seek` or `closed?` conflict:** cursor provenance and operation-authoritative lifecycle remain intact.

After the lead completeness obligation is generalized from “successful append” to “the complete reader sequence,” this is ready to commit.
