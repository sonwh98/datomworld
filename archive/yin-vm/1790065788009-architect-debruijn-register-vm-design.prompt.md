Created-GMT: 2026-09-22 08:29:48 GMT
Created-Local: 2026-09-22 15:29:48 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a (resumed: your de Bruijn VM design thread)
# Task: debruijn-register-vm-design — design a register-based VM and a compiler from the existing de Bruijn stack bytecode to it
Role: Architect
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-22 15:29:48 +07 | Status: active | Rationale: owner-requested exploratory design, building directly on this design's invariants and now-implemented B0-B3

Work in /Users/sto/workspace/datomworld (your launch directory; branch
master, HEAD b5bfe94b). This is a DESIGN task: produce a design document,
do not implement code. BE THOROUGH -- this is a substantial new design, not
a small addendum.

## Read first, in full

- docs/design/yin.vm.debruijn-vm.md (your own design; B0, B1, and B3 are
  now committed and architect-signed off by you; B2, the named-datom
  lowerer adapter, is in progress in a separate worktree, not yet done).
- docs/agents/architecture.md, for the "COMPILATION AS STREAM PROCESSING"
  section only (lines 130-183) -- read it for the pipeline PATTERN it
  describes (see item 2 below). Its "Status" note at lines 89-97, which
  says a stack-based and a register-based bytecode VM once existed and
  were deleted as experimental AST-projection proofs, is OWNER-FLAGGED
  STALE for this purpose: those VMs were removed as part of the separate
  dao.stream v2 redesign (a structural migration), not because a
  register-based execution model was judged architecturally wrong on its
  own merits. Do not treat that note as an argument against this design;
  it is historical color, not a live objection. The compilation-pipeline
  PATTERN (streams, pure stage functions, non-destructive projections) is
  a general, still-current project principle independent of those
  specific deleted VMs -- apply that pattern, not the deleted artifacts.

## What to design

1. **A register-based VM** as a NEW sibling execution path, analogous in
   spirit to how your existing design is a sibling of the named semantic
   VM: same underlying program semantics, different execution model and
   identity. Decide and justify: is this a new `:yin.debruijn.register/*`
   (or similarly named) dimension, with its own descriptor, its own image
   hash, its own validator -- following the same pattern B1 established
   for the stack dimension? Or does it reuse the stack dimension's H and
   only add an alternate encoding/execution path under the same identity?
   Give your reasoning against this design's own invariant I ("a yin.vm
   can share executable code over a dao.stream linker") and D9 (H is
   computed by one function per dimension) -- a second execution model
   for the SAME program should not create a second, competing sharing
   identity unless you have a clear reason it must.
2. **The compiler**: a stage from the EXISTING de Bruijn STACK bytecode
   (`:yin.debruijn.code/*`, B1's committed dimension -- read
   src/cljc/yin/vm/debruijn_code.cljc for its actual opcode table and
   shapes) to register bytecode. Not from named datoms directly -- the
   input is the already-lowered, already-identity-bearing stack image.

   The owner asked that this follow the SAME COMPILATION PIPELINE PATTERN
   architecture.md's "COMPILATION AS STREAM PROCESSING" section already
   establishes for this project (lines 130-183, read them again now):
   compilation is a stream processor, each stage a PURE FUNCTION from
   stream to stream; no stage mutates or destroys its input; intermediate
   representations coexist (the topology diagram shows the AST datom
   stream feeding three independent projections -- semantic, stack,
   register -- that all persist side by side). Apply that same shape here:
   this new stage is one more projection in that topology, consuming the
   stack bytecode stream and emitting a register bytecode stream, with the
   stack bytecode left untouched and still independently addressable
   (B1's `image-hash` over it does not change). State explicitly in your
   design how this stage fits the existing topology diagram (does it
   extend the diagram as a fourth projection, chained after the second
   one rather than parallel to it since its input is stack bytecode, not
   raw AST datoms -- draw or describe the updated topology) and confirm it
   is a pure, non-destructive stream transform, not an in-place rewrite.

   Design:
   - The register allocation strategy: how many registers a body needs,
     which register holds which intermediate value, how this composes
     with the existing `:load-bound`/`:load-free` lexical addressing (do
     bound locals get their own registers, separate from expression-
     temporary registers, or share the same numbering scheme?).
   - Determinism: this project hashes canonical bytes for identity
     (H = sha256 over exact bytes, D9/D14). If the register VM gets its
     own identity, the register-allocation algorithm's output must be
     exactly reproducible across runs and hosts, the same way B1's
     lowering-contract version guards against silent identity forks.
     Name the specific determinism risk register allocation introduces
     that a stack machine's direct tree-walk code generation does not
     have, and how your design closes it.
   - Which instructions change shape (arithmetic, `:call`, `:branch-false`
     equivalents needing explicit register operands) versus which, if
     any, can stay as-is.
3. **Scope boundary**: does this design REQUIRE or preclude a real
   register-based `yin.vm.debruijn-register-vm.cljc` execution kernel
   (mirroring B3's role for the stack dimension), or can the register
   BYTECODE exist as a pure compilation target with no VM built yet (an
   intermediate deliverable, similar to how B1 defined the stack
   dimension standalone before B3's kernel existed)? State your
   recommended phase order if you recommend building this at all.
4. **Non-goals and what this must not change**: the existing stack VM
   design (B0-B7) is unaffected -- this is an ADDITIONAL path, not a
   replacement; nothing here should require editing
   yin.vm.debruijn-vm.md's own committed phases. State this explicitly, as
   your own design already does for the merged projection (D12) and named
   VM (never silently retired without its own design, D7).

## Deliver

A new design document at docs/design/yin.vm.debruijn-register-vm.md,
following this project's existing design-doc conventions (see
yin.vm.debruijn-vm.md's own structure: numbered sections, a phase table if
you propose implementation phases, a DECIDED/DEFERRED section, ASCII only,
no em dashes, 80 columns, a clear Status line). Do not edit
yin.vm.debruijn-vm.md itself. If you find a genuine architectural reason
against building this (on its own merits, not the stale deletion note),
say so plainly instead of writing a full phased design -- either answer is
a valid, complete deliverable for this turn.

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a
then: your recommendation; if building, a summary of the document you
wrote and where, and how the compiler stage fits the compilation-pipeline
pattern; anything that needs the owner's decision.
