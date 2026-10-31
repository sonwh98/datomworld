I’ll check the two changes against the full row table and trace a nested cell occurrence through the path grammar. This remains read-only.

Completed-GMT: 2026-10-09 17:48:06 GMT
Completed-Local: 2026-10-10 00:48:06 Asia/Bangkok

Reviewed `ac3227f5a543559ba1fe7ec24c612277705bd682` and its preceding-commit diff. Re-read §6 against the row table. No files edited.

**LOW — editorial, non-blocking | docs/design/yin.vm.ucf-transport-tuples.md:492 | The census contains stream/position information that reference positions alone do not contain.** Excluding its declarations from refinement is nevertheless sound because initial cell labels include those bodies, and final emission commits to the complete census. | Replace “no information the reference positions do not” with “no additional information beyond the reached cell identities and their bodies.”

Properties that passed:

- Bare pending cell slots and value references now use class labels consistently. Incoming cell names cannot affect refinement through these positions.
- Census omission from `CE(root)` is sound. Declaration edges contribute no positions; bodies inform initial labels, and the re-emitted census remains authenticated by the root.
- Path categories now compose deterministically. For example, descending through a kframe environment, closure binding, and map value appends fixed-slot and keyed-value steps; wrappers add no occurrence step, and unordered entries introduce no enumeration index.
- Embedded labels and individualization labels have fixed CBOR byte-string representations. Search depth and the zero-cell case are specified.
- Bottom-up child canonicalization respects task-local cells, shared immutable child content, and separate native restoration.
- Exhaustive search and least root-body-byte selection establish a reproducible canonical representation. Equal emitted commitments need no source-labeling tie-break.
- Re-emission/root-id validation is consistent with the fresh-receiver round-trip law and rejects noncanonical representations without comparing arbitrary bijections.
- No remaining architecture or specification blocker was found for step 12, provided the complete validation sequence and required work bounds are implemented.

This approves publication as a **Draft**, not implementation acceptance or a tested safe-lowering implementation. The other-profile tables, concrete work limits, and conformance evidence remain implementation-phase obligations.

VERDICT: READY_TO_MERGE
