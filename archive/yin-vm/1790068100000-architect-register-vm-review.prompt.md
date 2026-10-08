Created-GMT: 2026-09-22 08:56:58 GMT
Created-Local: 2026-09-22 15:56:58 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: pending (new session, caller-generated)
# Task: architect-register-vm-review — independent architectural review of the register-VM design
Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-22 15:56:58 +07 | Status: active | Rationale: independent second architectural opinion on a design authored entirely by gpt-5.6-sol, playing to fable's own software-design/architecture strength rather than treating it as a lesser follow-up check

Work in /Users/sto/workspace/datomworld (your launch directory; branch
master). This is a READ-ONLY ARCHITECTURE REVIEW. Do not edit any file.

## Read first, in full

- docs/design/datom.world.md (foundational invariants, malleability)
- docs/design/yin.vm.debruijn.stack.md (the existing, already-merged stack
  VM design this proposal is a sibling to -- B0, B1, B3 are implemented and
  merged; B2 is implemented, awaiting independent review, not yet
  committed)
- docs/design/yin.vm.debruijn.register.md (the design under review --
  written entirely by gpt-5.6-sol, no independent architectural check yet)
- src/cljc/yin/vm/debruijn_code.cljc (B1's actual opcode table/descriptor
  pattern the register design's compiler must fit alongside)
- src/cljc/yin/vm/debruijn/stack.cljc (B3's actual VM kernel, the sibling
  execution model)

## Context you need

This design proposes an OPTIONAL register execution path derived from the
already-committed de Bruijn stack image (`:yin.debruijn.code/*`, B1). It
recommends: ship a pure stack-to-register compiler and validator first (R0,
R1), no register VM kernel yet; gate a real kernel (R4) behind a benchmark
(R3) showing material benefit over the stack VM; the register format gets
its own identity (`:yin.debruijn.register/*`, hash R, distinct from stack
H). The owner separately confirmed the project's own architecture.md
"deleted stack/register VM" status note (if you encounter it) is stale
color from an unrelated dao.stream v2 migration, not a live objection to
register VMs on their own merits -- do not raise it as an objection here
either.

## What to evaluate

Apply the architect role's full standard checklist (foundational
invariants, ownership boundaries, explicit state and control flow,
concurrency and linearization, dynamic extension, host isolation, CLJ/
CLJS/CLJD portability, migration risk, completion criteria, design
contradictions) specifically to THIS document. In particular:

1. Is the R1-standalone-compiler-before-any-kernel phasing actually sound,
   or does it risk building a format nobody ever benchmarks against
   (dead weight, same failure mode architecture.md's own history warns
   against for prior stack/register VM experiments)?
2. Is the determinism argument for register allocation (R hashes exact
   register bytes; allocation must be exactly reproducible across runs and
   hosts) actually closed, or does it hand-wave a real risk the way a
   naive allocator (e.g., unstable iteration order, host-dependent numeric
   width choices) could silently fork identity?
3. Does the register format's proposed identity split (stack H vs
   register R, both derived from the same source image) actually respect
   invariant I ("a yin.vm can share executable code over a dao.stream
   linker") and D9 (one image-hash function per dimension), or does it
   quietly create two competing sharing identities for one program the
   way the design's own R1 section worries about?
4. Any invariant or malleability violation in datom.world.md this design
   doesn't address.
5. Whether the recommended phase order (R0, R1, R2, R3 gate, R4 optional,
   R5 linker) is the right order, given B2 is not yet committed and B4-B7
   of the stack VM (effects, continuations, differential integration,
   stream linker) haven't started either -- does this design create any
   ordering hazard against the stack VM's own remaining phases?

Distinguish architectural defects from implementation gaps or
intentionally deferred work (the design explicitly defers the kernel
itself; that is not itself a defect to flag unless the deferral mechanism
is unsound).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then report: severity | file:line | invariant/evidence | recommended
correction. Also confirm the requested properties that passed review. End
with a plain verdict: sound as written / sound with changes (list them) /
not sound (say why, on the design's own merits).
