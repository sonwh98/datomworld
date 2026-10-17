Created-GMT: 2026-10-10 16:15:00 GMT
Created-Local: 2026-10-10 23:15:00 Asia/Bangkok

# Task: Architecture review of the phase-5 derivations design (authored by claude-fable-5-1)

Role: Lead System Architect (continuing thread 01a121d7-5ba4-71b1-a70b-f6b4cf3d056a)

Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-10-10 23:15 +07 | Status: active | Rationale: the design was authored by a Claude Architect delegate (fable-5-1) and committed by a Claude orchestrator; sign-off must come from a non-Claude family. Owner instruction: "delegate to fable-5.1 ... have codex review" (the same pattern as the phase-4 design).

Read-only review of `docs/design/yin.vm.semantic-register-vm.derivations.md`
(1444 lines) at the HEAD of branch `srvm-phase5-design` in
`/Users/sto/workspace/datomworld-srvm-p5-design` (one commit on master
0ff25b58). Read the author's report first:
`collab/1791570000000-architect-srvm-phase5-design.report.md`. The frozen
design `docs/design/yin.vm.semantic-register-vm.md` stays frozen; the
author proposes FIVE amendments to it (F8-F12), which are yours to rule
on. Format and rigor model: the phase-4 evaluator design you reviewed over
four rounds, `docs/design/yin.vm.semantic-register-vm.evaluator.md`.

## The central claim to test (F8)

Frozen §8.2 specified A -> R as a NEW pinned interval allocator over the
virtual-register vector, and §8.4 said R is "not expected" to keep today's
bytes. The author recommends instead the ADAPTER route: parse A to its
expression trees, emit resolved tuples in exactly the shape
`yin.vm.debruijn-resolve/resolve` emits, and call the EXISTING
`lower-register` (and `lower-stack` for H) unchanged, so R and H keep
today's bytes by construction, "b2" and "r2" are retained, and no new
allocator is written. Its evidence: R1's allocator assigns an expression's
destination at entry (pre-order, before its children) with a free list,
whereas the frozen scan assigns an interval at its first syntactic
definition (for a call, the `:call` itself, after its operands). On
`(+ 1 2)`: R1 gives `[:load-free 1 +] [:const 2 1] [:const 3 2]
[:call 0 1 [2 3] false []]`; the frozen scan gives `[:load-free 0 +]
[:const 1 1] [:const 2 2] [:call 3 0 [1 2] false []]`.

## Evaluate

1. **F8, independently.** Derive both allocations for `(+ 1 2)` and for
   the worked example `(f (g x) y)` yourself from `debruijn_register_compile.cljc`
   (R1) and from the frozen §8.2's own tie rules; do the numbers match the
   author's? Is the adapter route actually byte-identical by construction
   (§1.6's argument): both producers of the lowerer's input equivalent,
   including saturation (`ast->datoms` materialising the gensym prefix and
   the stream buffer default)? Where could it still differ (occurrence
   expansion, `:define` synthesised records, body order, tail flags, the
   record-id scheme)? Is turning the claim into a per-program gate (§4) the
   right response to "an argument, not a run"?
2. **The consequence for the frozen design.** If route (b), is "A is the
   canonical projection and the register vector's virtual ids are a
   derivation SOURCE" still true, or do the virtual ids become merely a
   spelling the allocators never read? Is that acceptable under the owner
   rulings (A is the only code identity; register-shaped canonical VM with
   virtual registers; one truth many interpretations)? Say plainly what the
   rebuild's register shape buys for derivations under (b), if anything,
   beyond the evaluator, UCF windows and foreign-engine reconstruction.
   (This is an owner-level observation, not a defect; raise it as such.)
3. **F9-F12.** For each: evidence correct, proposed text right, frozen
   section amended. F9: the adapter neither orders bodies nor recomputes
   pcs (both emitters own a FIFO queue and a label pass). F10: the
   register and stack lifts hash `:dim/lift-to [:yin.code/*]`, so changing
   their output contract would force "b3"/"r3" with no executable byte
   changed; phase 5 leaves the lifts alone. F11: "discharged bodies" is not
   a scanner fact; the gate compares free-name sets, occurrences and
   definitions by name and flag in order, application-site counts, the
   requirements map and the step-5a join's retained obligations, on three
   corpora (B0, register corpus, the C4 linked-prelude corpus by name). F12:
   the structural-slot machinery goes with the interval formulation.
4. **Resolve-on-vector (§1).** Is the record-for-record table into
   `validate-resolved`'s expectation complete, including the two synthesized
   records a `:define` needs, duplicate parameters, shadowing, free names?
   Does it equal `resolve-name`'s behavior, with cited pinning tests?
5. **The scanners (§6).** The one genuinely new logic. Are the facts compared
   right and sufficient for the C4 track's module layout to be built against
   them? Is the old-scanner golden fixture (pinned before cutover deletes
   the old scanners) sound? The C4/yang.python track belongs to another
   orchestrator: confirm the design only specifies a READ-ONLY agreement
   gate over its corpus and designs no change to it.
6. **The byte-identity and contract decision procedure (§4).** Mechanical,
   complete, and consistent with frozen §6 (bytes AND normative contract)?
7. **Slices and gates (§7).** 5a / 5b / 5c: shippable alone, numbered
   acceptance lists mechanically checkable by a reviewer who has not read
   the design; the claim that 5a, 5b and the scanner half of 5c run
   concurrently with 4b touching no 4b file.
8. **The three owner questions** (route (b) retaining "r2"/"b2"; the phase-8
   lift label; the phase-8 `:yin.lower/profile` string) and the decisions
   the author assigns to you (record-id scheme, corpus membership, "gate
   not a choice", where the "v4" footprint table lives, the byte-neutral 5c
   switch of `relowered`/`publish-closure!`, the fate of the named-datom
   resolver after cutover): rule on each one that is yours, and say which
   are the owner's.
9. **Grounding.** Spot-check at least 15 file:line references across all
   sections against master 0ff25b58. Report mismatches.
10. **Invariants** (the six) and portability notes (§10 of the design).

Distinguish defects (must fix before acceptance), gaps (before the slice's
implementation brief) and notes. Do not edit files.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>

Then findings (severity | file:line | evidence | correction); your answers
to 1-10 with an explicit RULING per finding F8-F12 (ACCEPT / ACCEPT WITH
CHANGES / REJECT and which document it amends); and end with exactly one
line: VERDICT: READY_TO_MERGE | CHANGES_REQUESTED, with changes in order.
