Created-GMT: 2026-09-22 09:01:39 GMT
Created-Local: 2026-09-22 16:01:39 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: pending (new session, caller-generated)
# Task: architect-register-vm-redesign — correct a foundational premise error in the register-VM design
Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-22 16:01:39 +07 | Status: active | Rationale: owner-directed correction of a design mistake in a document gpt-5.6-sol authored; the owner asked for fable to be used more as primary architect, playing to its own design strength, not just as a secondary checker

Work in /Users/sto/workspace/datomworld (your launch directory; branch
master). This is a DESIGN task: you may edit
docs/design/yin.vm.debruijn.register.md directly. Do not edit any other
file.

## The correction, verbatim from the owner

The owner read the current docs/design/yin.vm.debruijn.register.md
(section 1, "The objective is a deterministic register image derived from
an already lowered `:yin.debruijn.code/*` stack image...") and said:

"that's wrong. the register vm image should not be derived from the stack
image. the register vm and the stack vm are at the same level both
derived from the debruijn encoding of the semantic tuples"

This invalidates the document's current core premise: section 2's topology
diagram chains the register projection AFTER the stack projection
(register derived FROM the stack image); section 4.1 states the compiler
"accepts only the validated B1 stack vector and its H... does not accept
named datoms"; section 7 explicitly forbids the compiler from lowering
named datoms directly, calling that "a second lowerer." All of this must
be reconsidered under the corrected premise: stack and register are PEER
projections at the same level, both derived from the de Bruijn encoding of
the semantic tuples, not one compiled from the other.

## Read first, in full

- docs/design/datom.world.md (foundational invariants, malleability)
- docs/agents/architecture.md, the "COMPILATION AS STREAM PROCESSING"
  section (lines 130-183): this project's own established pattern is that
  one upstream stream feeds MULTIPLE PARALLEL peer projections (semantic,
  stack, register), none derived from another. The owner's correction is
  asking you to apply this exact pattern one level down, at the de Bruijn
  layer, instead of chaining register after stack.
- docs/design/yin.vm.debruijn.stack.md, in full -- this is the existing,
  already-merged design for B0 (contract/normalizer), B1 (the
  `:yin.debruijn.code/*` stack dimension and validator, merged), B2 (the
  named-datom lowerer/adapter that produces de-Bruijn-addressed images,
  implemented, NOT yet committed -- independent review in progress), B3
  (the stack VM kernel, merged). Read section 3 (lowering and scope) and
  the B2 phase box closely: this is where "the de Bruijn encoding of the
  semantic tuples" the owner refers to is actually produced today.
- src/cljc/yin/vm/debruijn_linearize.cljc (B2's actual implementation, in
  this worktree: /Users/sto/workspace/worktree-debruijn-b2/src/cljc/yin/vm/debruijn_linearize.cljc
  -- read it there). This is the critical file for your redesign decision:
  B2 currently does TWO things in one pass -- (a) de Bruijn name
  resolution (rewriting named `:var` to `:load-bound`/`:load-free`,
  `:closure` params to arity) and (b) assembling the result directly into
  B1's STACK-shaped instruction encoding (which already bakes in a
  `:push`-before-each-call-operand, argc-based `:call` convention, because
  B1's opcode table is copied unchanged from `yin.vm.code/vector-operand-
  table`, itself inherently stack-oriented). There is currently no
  existing artifact in the codebase that is "de-Bruijn-addressed but
  execution-model-agnostic" -- B2's output already commits to the stack
  shape. Your redesign must resolve this concretely: either (i) identify
  that B2's de Bruijn *addressing* step (before final stack-shape assembly)
  is already effectively the right peer-input for a register lowering too,
  and both the stack and register lowerings should each independently
  consume that shared de-Bruijn-resolved form and assemble their own
  instruction shape from it, or (ii) some other concrete resolution you
  can justify. State plainly whether this requires restructuring B2 itself
  (it is implemented but NOT committed -- there is room to change it if
  your design genuinely requires it) or whether the register path can add
  its own independent de-Bruijn-resolution pass without touching B2's
  existing file box, and why.
- docs/design/yin.vm.debruijn.register.md (the document you are revising).
- src/cljc/yin/vm/debruijn_code.cljc (B1's actual opcode table/descriptor).
- src/cljc/yin/vm/debruijn/stack.cljc (B3's actual VM kernel).

## What to produce

A revised docs/design/yin.vm.debruijn.register.md with the corrected
premise applied throughout -- not just section 1's opening sentence.
Specifically address:

1. The compilation topology (current section 2): redraw it so the register
   projection is a PEER of the stack projection, both branching from the
   de Bruijn encoding of the semantic tuples, matching architecture.md's
   own established parallel-projection pattern. State exactly what shared
   artifact both projections consume (per your resolution above) and
   whether it needs a name/identity of its own (does IT get an H, or is it
   purely a same-process intermediate with no independent identity/
   sharing role -- justify either answer against invariant I and D9).
2. Section 4 (stack-to-register lowering) needs to become "semantic-
   tuples-to-register lowering" (or equivalent) -- consuming whatever the
   shared upstream artifact turns out to be, not the stack image.
3. Section 7's non-goal "The compiler is not allowed to lower named datoms
   directly... It must consume the validated stack image" is now WRONG per
   the owner and must be corrected or removed.
4. Re-examine whether the identity model (section 1.1, register hash R
   including "the source stack H") still makes sense once the register
   path is no longer literally derived from the stack image -- if R no
   longer has a stack H to include, what replaces it, and does this change
   anything about how a receiver verifies a fetched register image without
   ever having seen or needed the stack image?
5. Re-examine the R0-R5 phase boxes' file lists and "Existing edits: none"
   / "Must not change" lists given this correction -- do any phases now
   need to touch B2 (or a new sibling of B2), and does that change the
   "Must not change: B1 code, B2 lowerer, B3 VM" line in R1?
6. Everything else (register allocation strategy in 4.2-4.3, the
   instruction mapping in 4.4, the determinism argument, the DECIDED/
   DEFERRED lists, the benchmark-gate phasing R0-R2-R3-R4-R5) should be
   preserved as-is wherever the corrected premise does not actually change
   it -- do not rewrite what does not need to change, and say plainly in
   your final report which sections you left untouched and why they are
   still correct under the new premise.

Keep the project's design-doc conventions: numbered sections, a phase
table, DECIDED/DEFERRED, ASCII only, no em dashes, 80 columns, clear
Status line.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then: your resolution of what "the de Bruijn encoding of the semantic
tuples" concretely is in this codebase, whether it requires touching B2,
a summary of every section you changed and why, every section you left
alone and why it is still sound, and anything that needs the owner's
decision.
