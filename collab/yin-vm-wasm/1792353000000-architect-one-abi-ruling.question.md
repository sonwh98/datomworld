# Follow-up design question (round 2; read-only — reply with your analysis, edit nothing)

You answered this question in round 1: "why can't the guest-host interface also be
dao.stream — why a different ABI at all?" Your answer is preserved in this
conversation (and in collab/1792350400000-architect-guest-host-abi-dao-stream.*).

Since then, the owner pursued the argument in a direct exchange with the
orchestrator. The exchange, compressed:

1. Owner: "But FFI can be implemented on dao.stream + host interpreter." — i.e.
   the FFI two-step already proves the async pattern works for guest operations:
   append a request, park as plain data, the composition services it, the machine
   resumes. Why not for everything?
2. Reply: FFI works because its *semantics include a yield* — world-fact answers,
   arriving late, program-event frequency. Services like `:load-free`, primitive
   apply, GC root materialization are not yield points: their completion is the
   atomic instruction transition, at instruction frequency.
3. Owner: "But these boundaries are artificial. We decide whether it's part of
   the outside or not." — conceding the point: the world/machine line is a design
   choice (`(+ 1 2)` is an opcode in every real ISA; contract v4 promoted
   `:define` from host service to opcode). What actually governs the placement is
   engineering economics: crossing frequency, freeze-vs-extend, canonicalization
   cost, observability, trust.
4. Owner's final position, now RECORDED AS AN OWNER RULING in
   docs/design/datom.world.md (Streams → Host Boundaries → "Owner ruling,
   2026-10-19: the boundary is drawn, not found; one ABI is the principle").
   Read it there. Compressed: one ABI (dao.stream) is the principle; the floor is
   immovable (world-facts cannot be computed by shipped code); the sole valid
   justification for a second, process-local ABI is **performance** — measured
   per-instruction crossing cost; it is therefore an engineering-cost measure,
   provisional by principle, to be shrunk as measurements allow and dissolved
   where a lane sits at the floor (the engine-in-guest wasm node, whose only
   interface is dao.stream); every addition to a process-local ABI is a cost to
   be justified, every removal is progress. The same ruling is noted in
   yin.vm.engine.md §E.3 and yang.wasm.md §4.

Round-1 positions for recall: astra — justified (split machine, richer internal
values); challenged the "wasm can't express streams" premise and refused to defend
generic `exchange` as principle. deepseek — justified (synchronous state-local
one-privileged-reader seam vs async canonical no-privileged-reader medium; "no
design in which guest-host is dao.stream"). fable — justified (shared value
custody; the wasm-limitation justification is the accident; proposed the
engine-in-guest "node lowering" lane where the owner's instinct is correct).
argon — dissented structurally: the boundary is cut inside the VM's inner loop;
recommended compiling the engine crates INTO the wasm instance (Option 1) so the
guest-host ABI literally becomes dao.stream.

Questions for round 2 — answer independently, do not hedge toward the other
architects, and critique freely:

1. Does the owner's ruling change your round-1 position? Say what survives and
   what you would now revise.
2. Critique the ruling itself. Is "performance is the sole valid justification
   for a second ABI" correct? Are there other valid reasons the ruling misses —
   e.g. capability/security granularity, determinism and cross-embedder parity,
   freeze-vs-extend of the ISA, failure isolation, independent versioning? For
   each you name, say whether it is genuinely a *second-ABI* justification or
   reducible to performance/cost.
3. Is the floor argument sound — that operations whose answers are world-facts
   cannot be pulled inside the machine, so `dao.stream`-only is the immovable
   minimum? Or can even the floor move (e.g. by changing what the system treats
   as a fact — determinism, recorded worlds, replicated agreement)?
4. Consequences for the plan: with the ruling in force, does the current
   body-lane-first plan (TLV mailbox in W0–W5, engine-in-guest deferred to a W6
   experiment) still make sense — or does the ruling commit the project to the
   node lane as the destination, changing what W0 should build? State what you
   would change in W0–W6, if anything.
