Created-GMT: 2026-09-22 18:48:43 GMT
Created-Local: 2026-09-23 01:48:43 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 341722e7-dd66-4583-996a-da14eaaeb56d (resumed: your register-VM design session)
# Task: architect-r4-unconditional — remove R4's benchmark gate
Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-23 01:48:43 +07 | Status: active | Rationale: owner decision reversing DECIDED item 1's gating

Work in /Users/sto/workspace/datomworld (your launch directory; branch
master). You may edit docs/design/yin.vm.debruijn.register.md only.

## The owner's decision, verbatim

After being told what R3's benchmark gate does and why R4 (the real
register kernel) is deliberately built only after a reference interpreter
proves a material benefit, the owner said: "its worth building regardles
of what the benchmark says." R4 is authorized unconditionally -- not
gated behind R3's outcome. Asked why, the owner said: "because it
demonstrate the configurable compilation pipeline using dao.stream."

This is the real justification to record, not "the owner does not care
about performance." Asked a second time why, unprompted, the owner
sharpened it further: "it demonstrate the philosophy that one universal
AST can be interpreted by different VM: one truth, many interpretations."

Record BOTH statements as the rationale, the second refining the first,
not replacing it:

1. The dao.stream framing: `dao.stream` is the stage boundary for a
   genuinely configurable compilation pipeline with multiple peer
   executable formats (named, stack, register) sharing one upstream de
   Bruijn encoding, each an independent, swappable backend rather than a
   hardcoded single evaluator.
2. The deeper claim underneath it, in the owner's own words: one
   universal AST (the named datoms, this project's single source of
   truth) admits many independent, equally valid interpretations/
   executions -- the named VM, the stack VM, and (once R4 exists) the
   register VM are not competing implementations where one is canonical
   and the others are alternates; they are peer witnesses to the same
   underlying truth, each free to interpret it by its own execution
   model, bound only by agreement on OBSERVABLE BEHAVIOR (B0-normalized
   parity), never on internal mechanism. This is the stronger,
   more general claim the dao.stream framing is a specific instance of --
   state it as the governing philosophy, with the dao.stream/
   configurable-pipeline point as its concrete mechanism in this project.

A working register KERNEL, not just a lowerer that produces inert bytes,
is what actually demonstrates this: a lowerer alone proves the format is
well-defined; a kernel that runs real programs and agrees with the named
VM under B0's normalizer is what proves "one truth, many interpretations"
is real here, not just claimed. Fold this into section 1's existing
"worth considering on its own merits" framing (it already gestures at
this with "targets an alpha-invariant, content-addressed encoding rather
than reintroducing a VM directly beside the AST walker" -- sharpen that
into the explicit one-truth-many-interpretations claim, citing both of
the owner's statements above) and into whatever replaces DECIDED item 1,
so the record shows WHY R4 is authorized, not just THAT it is. If this
philosophy is not already named somewhere in this project's foundational
docs (`docs/design/datom.world.md`, `docs/agents/architecture.md`), say
so; if it already is (under a different name or framing), cite it rather
than treating this as a brand new idea invented in this document.

## What to change

1. Section 8 DECIDED item 1 currently reads "R0-R2 are lowerer and format
   work; R4 is gated by R3. This keeps a second evaluator from becoming
   an unmeasured architectural commitment." Rewrite it to state R4 is
   authorized to proceed once its actual prerequisites exist, not
   contingent on R3's report. State plainly that this IS now a deliberate
   architectural commitment to a second evaluator, made by the owner with
   the tradeoff (permanently doubling the maintenance and test surface
   across JVM/CLJS/CLJD for every future opcode/effect change) already
   explained and accepted -- do not soften this into sounding like the
   commitment is still informational.
2. Section 6's "R3: benchmark gate" box: R3 no longer gates anything.
   Decide and state plainly what R3's role becomes -- purely
   informational (still worth running, still worth reporting, but no
   longer a precondition for R4), or genuinely unnecessary now and worth
   dropping/deferring as its own phase. Your call, but do not leave the
   box reading as though it still blocks R4 if it does not.
3. Section 6's "R4: optional register kernel" box: rename away from
   "optional" if that is no longer accurate, and state its real
   dependency chain. Read R4's own completion criteria again ("B0-
   normalized parity against the named VM and the stack VM... deterministic
   stream/effect behavior") -- this implies R4 needs R2 (effects/stream
   lowering) to be meaningful, not just R1. State whether R4 depends on
   R2, or can meaningfully ship a pure-programs-only kernel before R2
   lands and gain effects support once R2 exists -- your call, but be
   explicit, this is a real sequencing question the "gated by benchmark"
   framing let the design avoid answering precisely.
4. Section 1's framing ("A kernel is allowed only after the benchmark
   gate in R3 reports a material benefit over the committed stack VM")
   and anywhere else in the document that states or implies the R3 gate
   -- find every instance (grep-check yourself, do not rely on memory)
   and make them consistent with the new decision.
5. Section 8 DEFERRED currently lists "Whether R4 and R5 are ever
   commissioned after R3." R4 is no longer part of that deferred item --
   remove it or split it so only R5 (still a separate, unaddressed
   decision) remains listed as deferred.
6. Do NOT change anything about R5 (linker integration) -- the owner's
   decision was specifically about the register KERNEL, not the linker;
   leave R5's own gating/status exactly as it is unless something in
   items 1-5 genuinely requires touching its text for internal
   consistency (say so if it does).
7. Section 9 (End condition) currently frames "the full register-VM end
   condition adds a measured R3 benefit, a validator-approved kernel..."
   -- fix the "measured R3 benefit" framing to match the new decision
   (R3 may still run and report, but is not a condition R4 needs to
   satisfy).

Do not touch R0's or R1's own file boxes, completion criteria, or
anything about the format/lowerer work itself -- this decision is
specifically about the KERNEL phase's authorization, not about what R0/R1
already built or how they work.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then: every section you changed and exactly what changed; what R3's role
is now; what you decided about R4's real dependency chain (R1 alone, or
R1+R2); anything still needing the owner's decision.
