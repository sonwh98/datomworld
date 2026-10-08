Created-GMT: 2026-09-21 19:47:17 GMT
Created-Local: 2026-09-22 02:47:17 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a (resumed — your r6 fold-in turn)
# Task: make the image hash H the only identity on the sharing path; leave the projection dormant
Role: Architect
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-22 02:47:17 +07 | Status: active | Rationale: you authored the design; the owner ruled on the projection's role. One small, focused turn (GPT weekly budget about 20 percent, resets 2026-09-23 20:44 +07).

Work in /Users/sto/workspace/datomworld. You MAY edit exactly ONE file:
`docs/design/yin.vm.debruijn-vm.md` (already committed as b5f5e78a; this is a
follow-up edit). BE ECONOMICAL: read only that file. Give the complete answer now;
do not wait for approval and do not promise one.

## The owner's ruling (2026-09-22)

The owner argued: "if we have H, we don't need the projection." Verified by the
orchestrator: the projection has NO consumer in the running system
(`yin.vm.pipeline/persist-compiled!` has no caller; nothing indexes projected
records; the design's linker already uses H for everything). The owner decided:
**leave the projection code DORMANT (do not delete it, do not add new dependents)
and update the design.** The projection stays merged, tested and pinned (D0-D6),
but it is no longer part of the architecture the VM design relies on for sharing.

## Edit the design so that

1. **H is the ONLY identity on the sharing path.** The linker (section 7.2, B6, B7),
   the request and response values, the image, the receiver's verification, and any
   later cache use H and nothing else. Remove every place where the projection
   fingerprint is used as a lookup index, recorded as image metadata, or named as a
   way "to find alpha-equivalent candidates" (approximately lines 183-187, 197,
   385-390, 487, 559 and the compliance-table and section 8 mentions). It may be
   mentioned only as history or as the dormant artifact.
2. **The artifact model** (section 1) becomes: NAMED DATOMS (source of truth), the
   EXECUTABLE IMAGE (execution and sharing identity H), and the PROJECTION described
   as merged and DORMANT, not load-bearing, not consumed by this design. Say why H
   suffices: H is alpha-invariant for binders (binder names outside the hash), so
   same H implies alpha-equivalent programs that also agree on exact scalar
   spelling and free names; alpha-equivalent programs share H only when front-end
   tail flags agree (state that honestly as sound-but-incomplete). Keep the
   statements that the projection is lossy and cannot execute (1.0 becomes 1, NFC,
   no tail flags, no parameter names); that history explains why architecture B
   exists.
3. **The one real dependency**: B2 reuses the public `resolve-name` from the merged
   `yin.vm.debruijn` namespace. State that "dormant" therefore means "kept, not
   removed", and that if the projection is ever retired `resolve-name` must first move
   into this design's own namespace; make that a deferred note (section 8), not a
   phase.
4. **B5**: rewrite the acceptance criterion that says "alpha-equivalent programs share
   projection identity while their executable images may differ" so it no longer
   references projection identity: programs that differ only in binder names produce
   the same H; programs differing in exact scalar spelling, free names or front-end
   tail flags produce different H (keep the cross-host and stream-transfer criteria).
   Remove "projection metadata" from B5's pipeline description.
5. **Must-not-change lists** (B2, B3-B6 boxes and section 9): keep "do not modify the
   merged projection namespace" (dormant means untouched), but drop language that
   implies the projection is an active participant; keep section 9's protection of
   existing code.
6. **Optional future view, one short paragraph** in the deferred or non-goal area:
   if a coarser semantic-deduplication identity is ever wanted, it is derived from
   the image as a hash over a normalized view (canonical scalars, tail flags
   dropped); it would NOT equal the pinned projection fingerprint (different
   structure), so it would be a new derived value and a separate decision. Do not
   build or specify it.
7. **Section 8**: add DECIDED item D12: "H is the only identity on the sharing path;
   the projection is left dormant (merged, untouched, no new dependents)" with the
   owner's one-line rationale and the invariant it serves; add the `resolve-name`
   retirement note to DEFERRED.
8. Keep the document's conventions (numbered sections, ASCII box tables kept ALIGNED
   (all rows the same length), no em dashes, 80 columns, status "design; not
   implemented", no operational routing text). Keep the compliance table honest
   (update any row that mentioned the projection).

## Also report (do NOT edit)

A one-sentence note that could be added to the status paragraph of
docs/design/yin.vm.debruijn-projection.md saying the projection is dormant and not
consumed by the de Bruijn VM (80 columns), for the owner to approve separately.

## Deliverable

Your sign-off as author (READY or NOT READY to commit), the list of sections
changed, any place you kept a projection reference and why, and the suggested
projection-doc sentence.

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a
then: the sign-off line, sections changed, retained projection references with
reasons, and the suggested projection-doc sentence.
