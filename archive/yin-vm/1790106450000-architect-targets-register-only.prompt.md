Created-GMT: 2026-09-22 17:47:30 GMT
Created-Local: 2026-09-23 00:47:30 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 7e07b6da-0624-4b87-8533-378b43c3d302 (resumed: your targets design session)
# Task: architect-targets-register-only — commit to the register image, not a staged transition
Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-23 00:47:30 +07 | Status: active | Rationale: owner rejects the staged today/tomorrow framing your last turn wrote and wants a direct decision

Work in /Users/sto/workspace/datomworld (your launch directory; branch
master). docs/design/yin.vm.debruijn.targets.md is committed. You may
edit it further; do not edit anything else.

## The owner's instruction, verbatim

"rewrite @docs/design/yin.vm.debruijn.targets.md saying to use the
register and vm, not today the stack vm and tomorrow the register-vm."

## What this means

Your last turn added section 3.7 (3.7.1 "today: stack", 3.7.2 "complete
world: register", 3.7.3 "the transition rule") and T-D13, deliberately
keeping the two answers separate because they rested on different
ground. The owner is now rejecting that staging. Rewrite the document so
a Direction B foreign-host kernel targets the REGISTER image, stated as
the document's actual plan, not as a future state gated behind a
transition rule.

Work through what this actually requires, do not just change the words:

1. The register image does not exist yet (R1 is not authorized to start;
   R4, the reference kernel, is gated behind R3's benchmark and the
   register design states it "is not a promise that a second evaluator
   will exist"). If the document now targets R unconditionally, say
   plainly what that means for sequencing: does a foreign-host kernel
   phase (T1) now DEPEND ON R1 and R4 landing first, where it previously
   depended only on B1-B3 (already merged)? State the real dependency
   chain, do not hide it.
2. Fetchability: section 3.7.1's strongest reason for stack was that H is
   the only identity the committed linker (B6) actually serves; R5 (the
   register linker) is gated and may not exist. If the document commits
   to register, does a foreign host now wait on R5 too, or does it fetch
   the STACK image by H (since that remains the only thing B6 serves) and
   then derive a register image locally -- through `lower-register` if
   the host holds the resolved tuples (the common case, compiling where
   the program is written), or through `raise` (T8) plus `lower-register`
   if it only holds H? Decide and state this precisely; do not leave the
   fetch path implicit.
3. If a register kernel is the plan but R4 does not exist yet as a
   reference to check a foreign implementation against, say plainly
   whether that changes the phase order (does the FIRST register kernel
   anywhere then need to be built and proven on a Clojure host, i.e. does
   this document now effectively require R4 before or alongside T1,
   reversing today's B3-only dependency), or whether the owner accepts a
   foreign host being the reference implementation for register execution
   with no Clojure-side cross-check until R4 lands later. State which,
   do not paper over it.
4. Rewrite the phase table (section 7), the T1 Rust host box (section
   5.1), T2's Python box (section 6.1), and the DECIDED section (T-D13
   at minimum) to reflect register as the committed target, with real
   dependencies, not aspirational ones. Section 3.7 either goes away
   (if the today/tomorrow distinction no longer applies) or is rewritten
   to state the single committed answer plainly, your call on the
   cleanest structure, but the document must not still read as a staged
   plan afterward.
5. Keep section 3.7.2's five intrinsic-merit reasons (resource bounds
   declared, fewer dispatches, kernel is the runtime path, cleaner
   instruction set, activation model matches emitted code) as the
   justification for WHY register, since those still hold; do not
   re-derive them, cite them and move them into wherever the single
   answer now lives.
6. If, after working through 1-3, you conclude committing to register
   now is not actually buildable (for instance: there is no register
   kernel anywhere to validate a foreign implementation against, and no
   linker to fetch R over), say so as plainly as you would say the
   opposite, and propose the smallest concrete thing that IS buildable
   today under this instruction (for example: design and phase the
   register-targeting pipeline now, but gate its first foreign kernel
   phase on R4 existing, exactly as T4's Rust emitter is already gated on
   a benchmark) rather than silently reintroducing a stack fallback the
   owner just rejected.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then: what you changed and why; the real dependency chain a register-
targeting foreign host now has; anything still needing the owner's
decision.
