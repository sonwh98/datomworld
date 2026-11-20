# Jolt Gambit FFI stage 2 — qualified callable modes (DESIGNED, NOT IMPLEMENTED)

Status: design note only. Compiled 2026-11-19 from the standing rulings; nothing
here is implemented on the fork. Implementation authority: owner instruction.
Governing artifacts: the astra FFI plan (collab/jolt/1794976827310-architect-
gambit-ffi-plan.gpt-6-astra.findings.md §2 Callbacks, §3 gate table), the codex
review (1795030000000-...findings.md F6), the codex confirmation
(1795040000000-...findings.md C2/C4/C5), and the P1a-r2 evidence
(fork gambit-ffi-p1, commits dc6176dc/6507ec1e).

## What stage 1 left refused, and why it is correct to refuse it today

`sa-foreign-callable` / `sa-foreign-callable-collect-safe` (Scheme closures
passed into C and invoked as C function pointers) raise unsupported on the
Gambit target. The refusal is held by gambitcheck rows and exists because:

- **F6 (codex, P2):** stock Gambit 4.9.8's dynamic closure trampoline generator
  (`___make_c_closure`) ships machine-code emitters only for 32-bit x86, some
  PowerPC, and SPARC (lib/os_dyn.c:639-670); without `C_CLOSURE_CODE_SIZE` it
  returns null (c_intf.c:758-760). There is **no arm64 or x86_64 generator**,
  so arbitrary runtime closures cannot be converted. Static `c-define`
  representatives do NOT qualify dynamic closures (the static lookup path is
  c_intf.c:2914-2923).
- **P1a evidence:** uncaught callback exceptions kill the process (exit 70);
  second-Gambit-thread callbacks run only serialized after the foreign call
  returns; foreign-pthread callbacks survive 3/3 in one shape but are
  undocumented, and their sibling shape crashes (W3 phase3).

## What stage 2 would add (the design, per astra §2)

Qualified modes, in increasing ambition:

1. **Same-thread callbacks** (owner thread; C calls back into Scheme on the
   thread that entered C is NOT this — this is plain same-thread
   Scheme->C->Scheme round trips): trampoline + rooting + exception
   containment. Manual nesting warning applies (txi:19983-19992): no switching
   between nested Scheme->C->Scheme chains.
2. **Owner-parked re-entry** (re-entry during a released blocking call): the
   r2 evidence (serialized foreign entry survives with correct values 3/3,
   one driving 3 real GC rounds from the foreign pthread) makes this shape
   empirically plausible, but the codex confirmation C2 explicitly says
   serialized survival is NOT qualification; this mode needs its own
   lifecycle evidence before it is even a candidate.

Explicitly refused forever (unless the runtime changes): concurrent re-entry
with a live VM (crashes 3/3), generic unrelated-pthread entry (no documented
attach protocol; the MT experiment's best-effort bridge deadlocks on any
collection), any foreign-thread "mode" advertised as general.

## Implementation sketch (from astra §2; the design already fixed the shape)

- **Stable C trampoline per registered ABI shape**: generated from the SAME
  manifest as the call shims (host/gambit/ffi/generated-shims.scm pattern;
  variadic boundary is part of shape identity — codex's stage-1 fix). The
  trampoline looks up the rooted closure in an invocation table and calls it.
  This replaces Gambit's missing machine-code generator entirely; no
  ___make_c_closure dependency.
- **Rooting + lifetime**: closure handles rooted at registration; explicit
  release on teardown; registration table maps the C function pointer to the
  handle. GC-during-callback must be a tested row, not an assumption
  (P1a W4 + the real sa-gc-collect from stage 1 make this testable).
- **Exception containment**: every trampoline catches inside (P1a W4-M5:
  uncaught exits kill the process; the server.scm pattern is the reference).
- **Ownership tag**: each registration records its allowed entry mode
  (same-thread / owner-parked) and the trampoline refuses other modes with
  message-carrying errors.

## Gates (astra §3's gambitffi-callables, scoped to the qualified modes)

| Row | Evidence |
|---|---|
| same-thread round trip | value parity, nesting depth 2-3, callback that allocates |
| GC during callback | real collection completes while the callback runs; rooted handle survives |
| exception containment | callback throws; C caller sees the documented error mapping; process survives |
| lifetime | release then call -> refused message; re-register -> new identity |
| mode violations | wrong-thread entry refused with the exact message |
| negative controls | deliberately-wrong rooting; uncaught exception variant |

## Acceptance bar (unchanged from stage 1)

Target-identified public jolt execution (not standalone fixtures), macOS arm64
first then the Linux leg, state-derived assertive verdicts, watchdogged, seed
regeneration reproducible, review by a family independent of the author
(codex implements -> agy reviews -> same-thread confirmations), within the
C4 must-not-assert list (no generic foreign-pthread claims, no blocking
qualification, no Linux claim until the Linux leg passes).

## Prerequisites and open items

- Linux leg for stage 1's tier must land first (platform qualification is
  cumulative; stage-2 callables sit on the same bridge).
- Lane B (target-aware launchers) must land so callables-gate evidence names
  its target.
- The invocation-table protocol needs an Architect glance before
  implementation ONLY if it departs from astra §2 as written above; otherwise
  this note is the spec and the astra plan remains the governing ruling.

## Estimate

Moderate: trampoline table + rooting protocol + the gate family, on top of
stage-1's proven bridge pattern. Roughly the size of stage 1's adapter rows
plus one new gate family; the risk concentrates in rooting correctness
(GC-adjacent) and lifetime discipline, both of which have decisive negative
controls designed above.
