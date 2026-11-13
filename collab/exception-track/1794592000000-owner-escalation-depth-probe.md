Created-GMT: 2026-11-12 18:35:00 GMT
Created-Local: 2026-11-13 01:35:00 +07 (+0700)
Owner decision requested: the depth probe — build the driver, descope, or re-route.

# OWNER ESCALATION — the uniform depth probe: two honest BLOCKED rounds, three options

## The two blockers (both honest scope discoveries, not execution failures)

1. **Round 1 (linked)**: the mod's Q4 required the probe on LINKED backends; the existing depth
   utility measures UNLINKED runs — linked composition requires scheduler/FFI/link-serving
   handling during stepping (sol confirmed the complexity; the linked-warm follow-up is
   specified for a later round).
2. **Round 2 (unlinked, per sol's (C) ruling)**: the existing depth helper **treats blocking as
   completion** — it cannot distinguish a VM paused on a scheduler/FFI effect from a program
   that finished. The unlinked probe needs a NEW measurement driver (completion-vs-blocking
   semantics from the engine state machine) plus the 16-fixture matrix (4 frontends × 4
   variants × 3 lanes).

Neither round failed on execution — both were honest scope discoveries, each producing a more
precise specification. But the two-round discipline says escalate, and the picture is now
complete enough to decide.

## The three options

**(a) BUILD the driver** (recommended): one focused round for deepseek-v4-pro — the engine
state machine (blocking semantics, effect dispatch, heap-write) is its code from this arc. The
driver: run guest code to ACTUAL completion on unlinked semantic/stack/register lanes
(distinguishing blocked-state from returned-state), measure max-K during the post-init
interval, across the 16-fixture matrix. Substantial but bounded — the fixtures and assertions
are already specified by sol's ruling; only the driver is new. Cost: one strong-lane round.
Risk: deepseek credits (owner says low) — if it dies, recover-by-state and finish on codex.

**(b) DESCOPE**: close the track with the depth contract as a **documented obligation** —
sol's consult (`1794590000000-...findings.md`) is the complete spec (constant-case assertions,
linear-case measurements, the linked-warm follow-up); the probe becomes a named follow-up
round with its evidence file defined. The track's other three pillars (encoding, contract,
provenance) are all measured and merged. Cost: the depth pillar stays unmeasured — the
"measured, not asserted" principle takes a documented exception.

**(c) RE-ROUTE**: assign the driver build to a different lane (sol — it designed the ruling;
codex-sol can implement; reviewer = deepseek/qwen). Cost: sol's review-lane reservation breaks
for one round.

## Recommendation

**(a)** — the depth pillar is the end-gate's "measured, not asserted" principle made real, the
driver is bounded engine work for the lane that knows the state machine best, and the
escalation thresholds (the reason the probe exists) stay intact. If deepseek dies mid-round,
recover-by-state → codex completes.

## Evidence

- Round 1: `1794589000000-engineer-depth-probe.gpt-6.1-terra.stdout.log` (linked complexity)
- Round 2: `1794591000000-engineer-depth-probe-unlinked.gpt-6.1-terra.stdout.log` (blocking-as-
  completion; new driver needed)
- sol's spec: `1794590000000-consult-depth-probe-scope.gpt-6.1-sol.stdout.log` (SCOPE (C), the
  complete fixture/assertion/evidence specification)
