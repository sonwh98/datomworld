Created-GMT: 2026-10-10 17:50:00 GMT
Created-Local: 2026-10-11 00:50:00 Asia/Bangkok

# Task: review the REWRITTEN phase-5 derivations design (route a), continuing your thread

Read-only. Worktree `/Users/sto/workspace/datomworld-srvm-p5-design`, branch srvm-phase5-design, HEAD = commit just made (route-(a) rewrite of ab952ace). Read Fable's report `collab/1791660000000-architect-srvm-phase5-route-a.report.md` first; the brief it answered is `collab/1791660000000-architect-srvm-phase5-route-a.prompt.md`.

Owner rulings behind the rewrite: R and H are lowered FROM A (A is the one linearization; same A gives same R and H); NO legacy, so no version bump or re-version procedure, pinned checksums regenerated once; no adapter over the old lowerers; the old compilers are an execution oracle only; new invariant: the AST is never lost (unitarity), so no design assumes a tree-less receiver. Route (b) is dropped; do not review it.

Evaluate, with independent derivation:
1. The A -> R allocator (§2): is the frozen §8.2 interval scan complete and deterministic (ties, expiry, widening over layout syntax, structural slot, branches, tail calls, `:resume`)? Re-derive at least five of the eight goldens in §6.3 yourself (incl. `(f (if c 1 2) y)`, the tail-call closure body, the `:define` rows, `(f (resume :p x) y)`). Report any wrong number.
2. The A -> H emitter (§3): any choice left open? Do the H goldens equal what today's `lower-stack` emits (verify at least three)?
3. The facts-A-omits finding (§4): is the argument that no omitted fact can affect R or H sound? Check `:tail?`, `:macro?`, shared eids, provenance against the code cited.
4. Verification (§6): is an execution oracle plus validators plus goldens plus determinism sufficient without a byte oracle? Corpus membership, normalization of closure results, the C4 read-only harness, the twenty non-value rows compared on throws.
5. Regeneration (§8): is every moved constant listed and mechanically checkable; what keeps the old lowerers alive until phase 8; any claim that "descriptors are unchanged" that is not true?
6. Unitarity amendments (§9.2): are the proposed amendments to linker.md and UCF correct and minimal; note the owner's own position that only some of the four items are real conflicts (linker.md 225-232 consistent; `:trusted` only requires the tree be retrievable, not fetched; host modules need an invariant carve-out; UCF needs a pointer from a segment/A to its tree). Is Fable's treatment consistent with that?
7. Findings F8-F14 against the frozen design: ruling per finding (ACCEPT / ACCEPT WITH CHANGES / REJECT, which document).
8. Slices 5a/5b/5c, numbered mechanically checkable acceptance lists, independence from 4b, grounding: spot-check at least 15 file:line references.
9. Portability and the six invariants plus the new unitarity invariant.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
Then findings (severity | file:line | evidence | correction), answers 1-9, and end with exactly one line:
VERDICT: READY_TO_MERGE | CHANGES_REQUESTED
