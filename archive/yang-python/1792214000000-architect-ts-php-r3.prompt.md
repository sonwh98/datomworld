Created-GMT: 2026-10-16 20:45:00 GMT
Created-Local: 2026-10-17 03:45:00 +07 (+0700)
Coding-Agent: claude-cloud
Session-ID: 93cda4c1-ec00-4fd9-8fe6-1d63c63eac89 (resumed; round 3)

# Task: Revise both designs — round 3 (the codex fresh-thread review: 3 P1s + 1 P2, one theme)

Role: Lead System Architect

The codex round-2 review (fresh thread): /Users/sto/workspace/datomworld/collab/1792212000000-reviewer-ts-php-designs-r2-fresh.gpt-6.1-sol.findings.md
— read whole. The residue:
1. P1: PHP's revised copy rule still fails the unset($r) fixture — the holder count increments for every
   copied reference slot, so copying $a makes the subsequent write share (prints 2) instead of demote
   (prints 1). unset must decrement the holder count; COW separates only when the count reaches the
   unshared state. Model it correctly on the cell substrate.
2. P1: ES evaluation commits successful cycle members too early — the instantiation design commits cycle
   members before the cycle completes; rule the two-phase (declare-then-execute) instantiation over the
   store model so cyclic calls into hoisted exports work.
3. P1: declaration-claim coordinates remain contradictory for SCC publication — the claims entity
   ([canonical-root path]) does not compose with SCC bundles (bundled members share one unit; the claim
   coordinates must resolve through the bundle to the member).
4. P2: T10 requires more than the designated checker implements — rescope T10 (or extend the checker
   slice) honestly.

INSTRUCTION: these three are one theme — identity/lifecycle against the cell+linker substrate. Solve them
as ONE coherent mechanism revision (the reference/holder lifecycle, the ES instantiation phases, and the
claim coordinates should compose, not patch each other), then re-weave into both designs. Keep everything
codex confirmed closed (the string representation, the generated-name scheme, const enum). Address the P2
explicitly. Note what you expect round 3's reviewer to probe.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700>
