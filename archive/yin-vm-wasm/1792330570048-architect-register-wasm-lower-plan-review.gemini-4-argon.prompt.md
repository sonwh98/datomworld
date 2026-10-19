Created-GMT: 2026-10-18 13:36:10 GMT
Created-Local: 2026-10-18 20:36:10 +07

# Task: Second-architect review of the register→WebAssembly lowering plan

Role: Lead System Architect

Implementers:
- Model: gemini-4-argon | Assigned: 2026-10-18 20:36 +07 | Status: active | Rationale: owner routing 2026-10-17 — architectural review routes to claude-cloud/fable or agy/argon; fable authored adjacent register-kernel design, so agy/argon gives reviewer-family independence.

You are the second architect reviewing a plan authored by a peer architect (gpt-6-astra,
thread 01a14f30-ca56-7530-bb93-3a870eca7a7b). Review it independently: do not assume its
claims are correct, and do not limit yourself to the points it raises.

Under review:
- collab/1792329904305-architect-register-wasm-lower-plan.gpt-6-astra.findings.md
  (the plan: compiled body functions + trampoline, i32 ValueRef arena, mailbox host
  services, append-only module attach, slices W0–W6)

Authoritative sources to verify the plan against (read them; the plan must match the
code, not the prompt's paraphrase):
- src/cljc/yin/vm/debruijn/register.cljc (the r2 kernel: step1, return-transition,
  register-restore, attach-image, create-vm, module kernel)
- src/cljc/yin/vm/debruijn_register_code.cljc (opcode table, contract v4, validator,
  body-liveness)
- src/cljc/yin/vm/debruijn_register_effects.cljc (offset tables, continuation payloads,
  table/image defects)
- src/cljc/yin/vm/engine.cljc (run-loop, scheduler-round, park/resume, wait-set, FFI
  two-step, pin-refs, heap gc)
- src/cljc/yin/vm/semantic/derive.cljc (register-image: A→R derivation, L/T banks)
- src/cljc/yin/vm/values.cljc, src/cljc/yin/vm/linker.cljc (§7.3 lift/lower,
  spawn-module, attach)
- docs/design/yin.vm.debruijn.register.md, docs/design/yin.vm.semantic.md,
  docs/design/yin.vm.linker.md, docs/design/yin.vm.engine.md
- docs/design/datom.world.md (the 6 non-negotiable invariants)

Evaluate, at minimum:
1. **Semantic fidelity**: does the plan's continuation payload shape (§3.1) match
   `effects/continuation-payload` exactly — every field, optionality, ordering? Does its
   restore algorithm (§3.2) match `register-restore`'s actual gate ordering and field
   behavior? Any opcode lowering (§2.3) that diverges from `step1`?
2. **Continuation correctness**: is the no-wasm-native-call trampoline design actually
   sufficient for multi-shot captures, tail-call K semantics, abortive continuation
   application, and resume into attached-but-different layouts (r6 membership vs.
   layout equivalence — the plan's own risk 2)?
3. **Invariant audit**: hidden global state (arena ownership, cached modules),
   implicit control flow (mailbox/trampoline statuses), shared mutable state (linear
   memory vs. persistent public state, snapshot aliasing — plan risk 4), layer
   collapsing (wasm backend vs. engine vs. linker seams), assumed graphs (code-layout
   generation, function tables).
4. **Blocking/effects/FFI**: does §4 reproduce the ok/full two-step, stale-wake
   handling, no-duplicate-append, and parked-vs-blocked distinction exactly?
5. **Module growth**: is append-only attach with per-image wasm modules sound against
   `attach-image`/`relocate`/`rebuild`/`reset` semantics and in-flight absolute pcs?
6. **Slices/acceptance**: are W0–W5 gates actually falsifiable against the named test
   corpora? Any silent behavior-narrowing the gates would miss?
7. **Design contradictions**: stale doc prose vs. implementation (the plan claims some);
   verify or refute those claims. Anything the plan misread in the code?

Distinguish architectural defects from implementation gaps or intentionally deferred
work (the plan's own non-goals are explicit — judge whether the non-goals are the right
cuts, not whether the work is missing).

Write your review to:
collab/1792330570048-architect-register-wasm-lower-plan-review.gemini-4-argon.findings.md

Use the repository's verdict conventions: begin the findings file's final section with
APPROVE or REQUEST CHANGES (or APPROVE WITH CHANGES), then per-finding rows of
severity | file:line | invariant/evidence | recommended correction. Also list the
requested properties that passed review. Do not edit any other file; this is a
read-only review plus the single findings file.
