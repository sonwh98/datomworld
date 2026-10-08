Created-GMT: 2026-09-21 21:07:53 GMT
Created-Local: 2026-09-22 04:07:53 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 50b423bc-219f-40c0-8e56-2b6d9c3d9658 (resumed — your first architectural review of this design)
# Task: debruijn-vm-design-fable-r2 — architectural check of the COMMITTED design
Role: Architect (independent architectural review)
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-22 04:07:53 +07 | Status: active | Rationale: same reviewer confirms the fixes to its own findings (resume rule); its budget refreshed at 04:00 +07 and the owner deferred this check until then; the design author is gpt-5.6-sol (different family)

Read-only, plan mode. Work in /Users/sto/workspace/datomworld (your launch
directory). You can only Read files and run `git diff` / `git status` / `git log`.
Give the complete review now as your final response; do not wait for approval and do
not promise a verdict. Be economical: read only what is needed.

## What changed since your review

Your first review (SOUND WITH CHANGES; P1-1, P2-1..P2-6, P3s) is in
collab/1790016340343-architect-debruijn-vm-design-fable.claude-fable-5-1.findings.md.
The design docs/design/yin.vm.debruijn-vm.md was revised many times since and is now
COMMITTED (b5f5e78a, then a853dafa; ~620 lines). Between your review and now:
- opus-5 did five more passes; deepseek-v4-pro and qwen3.8-max reviewed the decisions
  (collab/1790017709976-reviewer-debruijn-vm-decisions.*.findings.md);
- the owner stated the governing invariant, verbatim: "I want de Bruijn projection so
  that a yin.vm can easily share code over dao.stream linker", and delegated the open
  questions to the team under datom.world.md's invariants plus that invariant;
- decisions D1-D12 were made (section 8): D2 the lossless source DAG dropped; D8 the
  stream linker is a COMMITTED phase (B6 closed-image fetch-by-hash over dao.stream;
  B7 dependency closure later); D9 one `image-hash` function; D10 scalar classes derived
  from hashed const tags; D11 free-name boundary (an image is safely shareable only if
  closed, or free names bound identically on the receiver); D12: the merged projection is
  left DORMANT and H is the ONLY identity on the sharing path (the owner argued "if we
  have H, we don't need the projection"; the projection has no consumer in the running
  system). B2 still reuses the projection namespace's public `resolve-name`.
- the projection design doc got a one-sentence dormancy note (ca34bfc9).

## Do

1. **Confirm each of your first-review findings** against the COMMITTED design:
   P1-1 (redundant lossless DAG), P2-1 (benefit and exit criterion), P2-2 (binder names
   inside the image hash), P2-3 (dimension protocol and the "published" overclaim, the
   descriptor fields and a lift morphism), P2-4 (UCF continuation fork, frames invisible
   to completion), P2-5 (injectable loader as a relocated callback), P2-6 (what H
   denotes), and your P3s: FIXED / PARTLY FIXED / NOT FIXED with a quoted sentence.
2. **Judge the new architecture against BOTH invariant sets** and say plainly whether
   the design now delivers the owner's invariant: walk one scenario end to end (host A
   lowers and publishes; a different-runtime host B knows only H, requests it over a
   stream, verifies by recomputing image-hash, refuses unsupported scalar classes, runs
   it) and say what phase covers each step and where any gap remains.
3. **Is leaving the projection dormant, with H the only identity, architecturally
   sound?** What do we lose, and is the retained dependency on `resolve-name` handled
   honestly? Is there any statement in the design that still treats the projection or its
   fingerprint as load-bearing?
4. **Anything that would surprise the owner or block B0**, and your top three risks now.

## Deliverable

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 50b423bc-219f-40c0-8e56-2b6d9c3d9658
then a verdict: SOUND, SOUND WITH CHANGES, or NOT SOUND; the confirmation table
(ASCII); NEW findings as P1/P2/P3 (section, quoted sentence, failing scenario, smallest
fix); answers to items 2-4; what you checked and found clean. Findings only; edit no
file.
