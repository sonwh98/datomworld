Created-GMT: 2026-10-10 02:05:00 GMT
Created-Local: 2026-10-10 09:05:00 Asia/Bangkok

# Task: Round 2 -- semantic-register-vm design, all thirteen freeze findings applied

Role: Lead System Architect (continuing thread 01a121d7-5ba4-71b1-a70b-f6b4cf3d056a)

All thirteen round-1 findings and the ten answers were taken. New HEAD
is one commit on top of `60b1baaa` on branch `semantic-register-vm`.
Run `git diff HEAD~1 HEAD` and re-read §§1, 2.4, 2.5, 3, 4, 6, 8, 9,
10, 11 from disk.

Per finding:

1. Saved window: `saved(p, rd) = W↾(L(p) − {rd})` defined once in §2.4
   and used for call frames, waits, park, capture, reconstruction and
   R live operands; §8.1 states standard liveness over an explicit CFG
   (tail `:call`, `:resume`, `:return`, `:halt` successor-less; layout
   syntax as predecessor-less nodes) and that `L(p)` normally contains
   `rd`.
2. Grammar exhaustive over §2.3: zero-child atoms for gensym,
   store-get, store-put, stream-make, current-continuation, park;
   effect productions per stream/FFI opcode with child order; `resume`
   as the one destination-less form.
3. `expr(rd)` defined by a recursive result relation; `if(rd)` has
   result destination rd by rule with both arms `expr(rd)`; exclusive
   definitions validated by structured paths (leaf arms of one
   `expr(rd)` subtree), not pairwise (§3.2).
4. Tail calls: uniform completion through K including pure-primitive
   immediate values (§2.4 call arms); textually following instructions
   are layout syntax, validated structurally; liveness over the real
   successor relation (§3.3 item 4, §3.4 items 8/10).
5. Closed state shapes `act {seg pc E window K}` and `tail {seg site K}`
   with closed `deliver` records `{:deliver :rd :rd r}` /
   `{:deliver :return}`; UCF frame shapes for both (§4.2); every linker
   phase carries either mode (§4.1).
6. Continuation invocation as a `:call` arm: one argument, ownership
   validated, abortive, deliver once per κ's record (§2.4); plus
   not-callable error.
7. `:regs` row keeps `k-cref`; `:tail-state` sibling row; `deliver`
   as structural data (§4.2).
8. §3.4 item 6: complete body partition, exactly one owner per non-main
   body, rooted acyclic ownership, no cross-body targets.
9. §8.2: pinned interval allocation over pc order (intervals covering
   all definitions and live points; shared destination = one interval
   across the whole `if(rd)` subtree; expiry then assignment order;
   source/destination overlap rule recorded by the r2 descriptor;
   parameters reserved; tie-breaks pc then id; count rule).
10. §8.2 step 3 names exactly the r2 boundary opcodes and computes
    `live` as the physical image of `saved(pc+1, rd)`, agreeing with
    `body-liveness`'s destination exclusion and tail handling.
11. §6 and §8.4: retain b2/r2 only if bytes AND normative contracts are
    unchanged; compare actual vectors and encoded bytes, checksums as
    summary.
12. §1 item 2 and §2.5: correspondence allowing administrative steps,
    per walker frame and effect phase (table), scoped to the shared
    supported vocabulary (store-update excluded).
13. §10 phase 1 marked complete (2daa17fb).

Answers adopted: §3.3 item 2 (arm destination mints nothing; resume
mints nothing; zero-child atoms; occurrence expansion; saturation;
FIFO body queue including bodies discovered inside queued bodies);
§3.4 item 13 canonical-numbering round trip; §4.1 linker phases split
with delivery only at install completion, explicit park and reified
continuation set apart, quiescence restated; §4.2 full frame validator
list; §8.3 adapter to resolved-tuple input with pc recomputation; §9
blast-radius additions and "executable admission changed" note; §10
liveness moved to phase 3, phase 6 gated on every reconstructing
engine; §11 rewritten as resolved.

Check, read-only, as the freeze gate:
A. Is `saved(p, rd)` now used consistently everywhere a window is saved
   or reconstructed, with no residual `L(p)` claim?
B. Is the grammar complete and is the recursive result relation
   well-founded (every `expr(rd)` has exactly one result destination;
   nested `if(rd)` composes; `resume` handled)?
C. Are the `act`/`tail` states and `deliver` records sufficient for
   validation and resume across every §4.1 row, including the three
   linker phases and FFI retained→sent?
D. Is the §8.2 interval algorithm deterministic and complete as
   written? Anything unpinned?
E. Any remaining contradiction between §2.4 (tail completion through K
   for pure primitives too) and §4.1 / §3.3 / §8.1?
F. Is the document now ready to freeze (phase 2 gate), with remaining
   items correctly deferred to the phase-3 brief (§11 last paragraph)?

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>

Then: severity | file:line | evidence | correction, for anything
remaining; the properties that passed; and end with one line:
VERDICT: READY_TO_FREEZE | CHANGES_REQUESTED.
