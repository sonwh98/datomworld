Created-GMT: 2026-10-09 22:50:00 GMT
Created-Local: 2026-10-10 05:50:00 Asia/Bangkok

# Task: Architecture review -- a register-shaped canonical semantic VM with virtual registers

Role: Lead System Architect

Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-10-10 05:50 +07 | Status: active | Rationale: independent non-Claude family for an owner-level re-foundation decision; the orchestrator (Claude, acting as Architect per owner instruction) drafted the proposal below and must not be its sole reviewer. Fresh thread.

Perform a read-only architecture review of the PROPOSAL below. No
design document exists yet; your findings will shape the first draft.
Do not edit files. You are in a git worktree; use relative paths.

## Owner rulings, verbatim

Ruling 1 (code identity):
> "yes, A is the only code identity"

Ruling 2 (this proposal):
> "if register -> stack is the simpler transformation, maybe we should
> create semantic-register-vm? then have the chaining be
> semantic-register-vm -> register-vm -> stack-vm?"

> "yes, dispatch the brief with virtual registers. once we build
> semantic-register-vm we can delete semantic-vm and rename
> semantic-register-vm to sematic-vm"

Orchestrator's paraphrase, kept separate: A is the content address of
one segment of the semantic VM's canonical instruction vector
(`ucf/code-address`, one-argument `jing/segment-key`, Jing default
digest). The de Bruijn stack image hash H ("b2") and register image
hash R ("r2") become derived checksums of lowerings of A, not names
anyone requests or serves. The proposal is to change the SHAPE of the
canonical semantic vector from accumulator-plus-operand-stack to
register-shaped with VIRTUAL registers, so that both derivations from A
are easy, and then to replace the current semantic VM with the new one
(clean break; this is a dev-only repo with no deployed stores, and the
owner's standing rule is that clean breaks are the default).

## Current state (read first)

- docs/design/datom.world.md (invariants, "derive, don't persist")
- docs/design/yin.vm.semantic.md §4.1 (C = ⟨seg, pc, val, St⟩; St is the
  operand stack, filed under control), §4 transition rules, §5 the
  linearizer
- src/cljc/yin/vm/linearize.cljc (header docstring; the per-lambda
  out-of-line bodies and single :closure site per body)
- docs/design/yin.vm.debruijn.stack.md §2 (the de Bruijn stack
  dimension derives its opcode table from the semantic one; "only
  lexical addressing differs"), §2 H definition (explicit SHA-256,
  "the only executable and sharing identity" -- now superseded by
  ruling 1)
- docs/design/yin.vm.debruijn.register.md §2 (topology: both images
  branch from resolved tuples; "neither image is an emitter input"),
  §3 (register dimension), §4.1-4.6 (resolved-tuples-to-register
  lowering: target-register passing, deterministic linear-scan temp
  bank, live sets at continuation boundaries), §5.1 (continuation
  transport across VM models)
- src/cljc/yin/vm/debruijn_register_compile.cljc (header docstring:
  the lowerer and the `lift` back to the named `:yin.code/*` vector
  via a recoverable chain grammar)
- docs/design/yin.vm.debruijn.targets.md lines 239-310 (`raise`,
  T-D12, "lift then resolve is not the path")
- docs/design/yin.vm.universal-continuation-format.md §7.3.2 (the
  canonical vector), §7.4.1 (the safepoint table, written in terms of
  St), §7.4.2 (foreign-engine safepoint maps: "physical slot ->
  canonical slot")
- docs/design/yin.vm.universal-continuation-format.v2-amendment.md §1
  (four profiles), §5.1-5.3 (semantic, stack, register registers and
  frames)
- docs/design/yin.vm.code-as-tuples.md §5 (segment level; instruction
  vector is UCF §7.3.2), §7.2 (two load paths, one validator)
- docs/design/yin.vm.ucf-transport-tuples.md (merged Draft, master
  f633adf3) §5.2 (:regs / :stack / :k / :kframe rows -- would change)
- public/chp/blog/hygienic-parallel-transport-universal-continuation-format.blog
  Part Three, the paragraph on St (would be rewritten)

## The proposal

1. **Canonical form.** The semantic VM's control component becomes
   C = ⟨seg, pc, regs⟩ with E, S, K unchanged in role. Instructions are
   three-address over an unbounded VIRTUAL register file: every
   instruction that produces a value writes a fresh register, never
   reused within a segment (SSA-like, without phi: branches are
   structured as today, and a conditional's two arms write the same
   destination register, as the current register lowerer's :if
   already does). No allocation, no temp bank, no live sets in the
   canonical vector. `val` and `St` disappear.
2. **Identity.** A = address of the register-shaped vector, computed by
   the same `code-address`. Because registers are virtual and minted in
   evaluation order, A remains a deterministic lowering of the AST that
   depends only on evaluation order, as the stack vector does today
   (the property the Architect confirmed for the stack vector in the
   essay review, round 1 finding 4).
3. **Derivations, fan-out from A.**
   - de Bruijn register image R = allocate(resolve(A)): name resolution
     on the vector (the closure-owner tree is recoverable: one :closure
     site per out-of-line body, carrying params) then R1's deterministic
     linear scan over virtual registers, plus live-set filling. Contract
     r2.
   - de Bruijn stack image H = expand(resolve(A)): local register ->
     stack expansion, one instruction at a time. Contract b2.
   - Neither H nor R is served, requested or pinned; a kernel holding
     only an H- or R-addressed image with no A reachable refuses.
4. **Continuation format.** A semantic UCF frame becomes registers (a
   window of virtual registers live at the safepoint) + K; return frames
   carry segment, pc, env and the register window they need, with no
   stack-base. §7.4.2's "physical slot -> canonical slot" becomes
   literally a register map.
5. **Migration.** Build the new VM beside the old (working name
   semantic-register-vm), reach parity on the AST-walker equivalence
   corpus and the UCF Stage D/E corpus (re-encoded), then delete the old
   semantic VM and take its name. All code addresses change; no
   compatibility shim.

## Evaluate

1. **Invariants.** The six non-negotiable invariants against the
   proposal; in particular whether virtual registers introduce any
   assumed graph or hidden state the operand stack did not.
2. **Identity.** Does a virtual-register three-address vector keep A a
   deterministic function of the AST and evaluation order alone?
   Identify anything in the proposal that would make A depend on an
   allocation or scheduling choice. Is the "fresh register per value,
   arms share a destination" rule enough to make the register numbering
   canonical, or does it need a stated minting order?
3. **Semantics.** Is the CESK presentation preserved (is this still a
   CESK machine, with the register window as part of C)? Does anything
   in yin.vm.semantic.md §4 (effects, park, resume, current-continuation,
   gensym, FFI, define/Rule R, module store-of) become harder or easier
   to state over registers?
4. **Safepoints (UCF §7.4.1).** Does the safepoint table survive with
   notational edits (St -> register window) or does it need new rows?
   What does "resume value" mean: a designated register? What is the
   reconstruction obligation for a foreign engine under §7.4.2 now?
5. **The two derivations.** Confirm or contest that A -> R is "resolve +
   R1's existing allocator" and A -> H is a local expansion; name what
   in R1 (§4.1-4.6) would have to change to take a virtual-register
   vector instead of resolved tuples. Is a byte-identity acceptance law
   (the new H and R equal today's H and R on the corpus) achievable, or
   must b2/r2 be re-versioned? If re-versioned, say so plainly.
6. **Blast radius.** Enumerate the documents, namespaces, corpora and
   landed tests that change, and the ones that do not. Distinguish
   "rewrite" from "notational edit" from "untouched". The row carrier's
   §5.2 and the blog's St paragraph are known; find the rest.
7. **Sequencing.** Propose the phases of the migration and what is
   gated on what, including when the de Bruijn docs' identity statements
   (stack §2 / D9 / D12, register invariant I, targets T-D12) are
   amended for ruling 1 -- before, during or after.
8. **Risks and the honest case against.** What would make this a
   mistake? Include the cost of re-foundation on top of a UCF
   implementation that has just reached Stage E.

Distinguish architectural defects (the proposal is wrong or conflicts
with an invariant) from specification gaps (the proposal needs a
decision before a design doc can be written) from implementation cost.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>

Then report: severity | file:line | invariant/evidence | recommended correction.
Then your answers to items 1-8, numbered.
End with one line: VERDICT: PROCEED_TO_DESIGN | PROCEED_WITH_CHANGES | DO_NOT_PROCEED,
with the changes or blockers listed in the order they should be addressed.
