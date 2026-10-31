Created-GMT: 2026-10-10 01:10:00 GMT
Created-Local: 2026-10-10 08:10:00 Asia/Bangkok

# Task: Architecture review of the semantic-register-vm design draft (phase 2 freeze candidate)

Role: Lead System Architect (continuing thread 01a121d7-5ba4-71b1-a70b-f6b4cf3d056a)

The identity amendments (phase 1) are merged to master. The owner
ruled, verbatim: "send the semantic-register-vm design through codex".

The design draft is `docs/design/yin.vm.semantic-register-vm.md`, one
commit on branch `semantic-register-vm` on top of master. Read it from
disk in full. It is written to your PROCEED_WITH_CHANGES verdict's six
changes and to a further owner framing ruling, verbatim:

> "The AST is the source of truth but the semantic vm is the canonical
> projection of it. the ast-walker and the semantic-vm are isopmorphic.
> we just need to rebuild the smantic-vm as register-shaped vm instead
> of a stack-shaped vm"

The orchestrator's design choices, to be judged as choices: parameters
stay in E (registers hold only the walker's in-flight values, §2.1);
body-local register ids (§2.2, §11.2); pre-order minting (§3.3 item 2);
the instruction table as r2's shapes with names and no `live` operands
(§2.3); the recoverable expression grammar as a validator obligation
(§3.1-3.2); windows as `W↾L(pc)` with L recomputed by the receiver
(§4.2, §8.1, §11.3); `deliver(rd, tail)` with tail effects routed
through K (§2.4, §4.1, §11.4); the structural `:return` after a tail
call kept (§3.3 item 4, §11.1); stamp "v3" -> "v4", b2/r2 untouched
pending byte evidence (§6, §8.4); A -> R as resolve + explicit linear
scan reusing `body-liveness` (§8.2); A -> H as parse + existing
`lower-stack` walk (§8.3).

Read alongside: yin.vm.semantic.md §2.4, §2.6, §4, §5.3;
yin.vm.debruijn.register.md §4.2-4.6; debruijn_register_compile.cljc
header; yin.vm.linearize header; ast_walker.cljc application/if/lambda
arms; UCF §7.4.1-7.4.3; v2 amendment §5.1, §6, §7; the row carrier
§5.2; your own round-1 findings file.

## Evaluate, as the phase-2 freeze review

1. Each of your six required changes: addressed, partially addressed,
   or not. Be specific about which section does it.
2. **Isomorphism.** Does §2.5's correspondence (register = walker
   in-flight value; ret frame = walker continuation frame + rd) hold
   for every walker arm, including eval-stream-put-target/val,
   request-sent, eval-call, and the effect frames? Name any walker
   state with no register-machine counterpart or vice versa.
3. **Grammar and minting (§3).** Is the grammar complete over the
   instruction table (every instruction reachable by some production)?
   Is pre-order minting fully specified, including for `define`,
   effects, and nested conditionals into the same rd? Does body-local
   numbering interact correctly with `:closure body-pc` and the
   closure-owner tree? Is definite assignment decidable by the stated
   forward walk over this grammar?
4. **Delivery (§2.4, §4.1).** Is `deliver(rd, tail)` complete and
   non-contradictory across call, return, park, resume,
   current-continuation, FFI, link waits, and halt? In particular the
   "effectful call, tail" row and §11.4: can `handle-effect`'s restore
   path deliver through K with no current activation, or is a
   trampoline required?
5. **Windows (§4.2, §8.1).** Is `L(pc)` well-defined over the
   structured CFG including the unreachable structural `:return`? Is
   the exclusion of `rd` from the saved window correct for every
   safepoint kind, including the retained-write `:put` (where the
   retained value is in pending, not the window)? Are the three window
   refusals (`:window-extra/-missing/-self`) sufficient?
6. **Identity (§3.3, §7).** Confirm A is a deterministic function of
   the AST and the pinned walk; identify any remaining input not fixed.
   Confirm the exact-identity (not alpha) stance is preserved.
7. **Derivations (§8).** Is §8.2's linear scan sufficiently specified
   to be deterministic across hosts (tie-breaks, lifetime of arm-shared
   registers, parameters)? Is §8.3's "parse then existing lower-stack
   walk" actually the same emission as today's, so that H byte identity
   is plausible? Is §8.4's decision rule honest?
8. **Safepoint table (§4.1).** Any row missing or wrong versus UCF
   §7.4.1/§7.4.3 as amended (link request/response/install; retained
   FFI; explicit park as the no-wait shape)?
9. **Blast radius and migration (§9, §10).** Corrections to the
   enumeration; are the phase gates sufficient; is anything gated in
   the wrong order?
10. **The four open questions (§11).** Your answer to each.

Distinguish architectural defects (must change before freeze) from
specification gaps (must be filled before phase 3's implementation
brief) from editorial issues.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>

Then report: severity | file:line | invariant/evidence | recommended correction.
Then your answers to items 1-10, numbered.
End with one line: VERDICT: READY_TO_FREEZE | CHANGES_REQUESTED, with
changes in the order they should be applied. (Merge publishes a Draft;
"freeze" is the phase-2 gate.)
