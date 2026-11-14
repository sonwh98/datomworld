Created-GMT: 2026-11-13 18:40:00 GMT
Created-Local: 2026-11-13 01:40:00 +07 (+0700) — see correction
Coding-Agent: mob (two independent architects: gpt-6-astra, deepseek-v4-pro)
Session-ID: ece6e46b-9273-49e1-b940-d6c68224a710 (astra; capture thread.started)
# Task: architect MOB — the depth-probe driver: build, build-scoped, descope, or re-route?
Role: Architect (independent mob — two architects ruling separately; do not read the other's verdict)
Implementers:
- Model: gpt-6-astra (codex, POOL 1) | Assigned
- Model: deepseek-v4-pro (deepseek CLI) | Assigned

## The decision (owner escalation, 1794592000000-owner-escalation-depth-probe.md — read it fully)

The uniform depth probe (the end-gate's "measured, not asserted" pillar) has TWO honest BLOCKED
rounds:
1. Round 1 (linked): the mod's Q4 required the probe on LINKED backends; linked composition
   requires scheduler/FFI-bridge/link-serving handling DURING stepping — a substantial
   instrumentation design task.
2. Round 2 (unlinked, per sol's (C) ruling): the existing depth helper TREATS BLOCKING AS
   COMPLETION — it cannot distinguish a VM parked on a scheduler/FFI effect from a finished
   program. The probe needs a NEW measurement driver (completion-vs-blocking semantics from the
   engine state machine) plus the 16-fixture matrix (4 frontends × 4 variants × 3 lanes).

## A refinement the owner's questioning surfaced (factor it in)

The probe's fixture set is DELIBERATELY PURE — loops, try/finally, caught failures, no FFI
effects. Pure guest code on the UNLINKED lanes (semantic/stack/register) runs synchronously to
completion: no effects fire, no parking happens. So a driver scoped to PURE FIXTURES on
UNLINKED LANES may not need the completion-vs-blocking machinery at all — step until the
continuation is empty, track max-K. The full driver (blocking-aware) is required for the
LINKED-WARM follow-up and for general programs with effects — sol's linked-warm spec already
covers that follow-up separately.

## The options (rule on exactly these)

**(a) BUILD the full driver** — completion-vs-blocking semantics + 16-fixture matrix + three
lanes, per sol's (C) spec. One focused round for a strong implementer.
**(a′) BUILD scoped** — pure-fixture driver on unlinked lanes now (simpler: step-to-completion,
track max-K, no blocking machinery); the blocking-aware driver folds into the linked-warm
follow-up where it is actually required. The constant-case contract is asserted on unlinked
lanes; the linear-case characterization is recorded on unlinked lanes; the linked-warm
obligation remains a named follow-up (sol's spec already defines it).
**(b) DESCOPE** — close the track with the depth contract as a documented obligation (sol's
spec is complete); the probe becomes a named follow-up round.
**(c) RE-ROUTE** — assign to a different lane; independence re-negotiated.

## Constraints

- The prime directive: "measured, never asserted" — the depth contract is the last pillar.
- The mod's Q4 intent: uniform measured contract across all four frontends; constant case
  ASSERTED; linear case MEASURED-never-asserted.
- No VM files; no kit contract-file changes; the frontends' guest code compiles through
  production lowerings.
- The pair-frontier rounds (ts/php seats) are in flight on their own surfaces — this decision
  does not gate them.
- The track CAN close with (b) — the encoding/contract/provenance pillars are all measured and
  merged; the depth pillar would be a documented exception with sol's spec as the follow-up.

## Deliverable

Rule on exactly one option: (a), (a′), (b), or (c) — with evidence (read both BLOCKED rounds'
logs, sol's (C) spec, the escalation packet) and the implementer instruction (which lane, what
scope, what the round delivers). End with one line: **SCOPE RULED: <option> + <one-line
contract language>**. Read-only. Begin with Completed-GMT/Completed-Local.
