Created-GMT: 2026-09-22 18:54:50 GMT
Created-Local: 2026-09-23 01:54:50 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 341722e7-dd66-4583-996a-da14eaaeb56d (resumed: your register-VM design session)
# Task: architect-live-set-tracking — design where dead-register information lives in the format
Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-23 01:54:50 +07 | Status: active | Rationale: owner decision on a real format question (does it change canonical bytes / R), not an implementation detail to leave to the coding pass

Work in /Users/sto/workspace/datomworld (your launch directory; branch
master). Edit docs/design/yin.vm.debruijn.register.md only (sections 3,
4.2-4.4, and DECIDED/DEFERRED as needed). This is a design task; do not
touch any source file.

## The owner's decision

Discussed this session, in plain terms first: when a register continuation
is saved (at a non-tail `:call`, when a return frame is pushed), some of
the caller's registers may already be dead (their value will never be
read again) by the time the call happens. The owner's explicit preference:
"it should only track the register that is still needed and discard if
not needed" -- i.e. a saved continuation should carry only LIVE registers,
never dead ones, rather than conservatively saving everything. The owner
separately pushed back on framing this as "adding machinery": not tracking
liveness doesn't avoid work, it just leaves dead values implicitly
retained in every saved continuation forever, as unaccounted-for state --
computing liveness explicitly makes an already-true fact visible in the
format instead of leaving it as an invisible, undecided cost. Agreed with
that framing; proceeding on that basis.

## What R1 already built (read this first, in full, before designing)

- src/cljc/yin/vm/debruijn_register_code.cljc (R1, uncommitted, in
  /Users/sto/workspace/worktree-register-r0, branch register-r0 -- read
  it there): the descriptor, opcode table, `register-hash` (R), and the
  validator. `:call [op rd fn-reg arg-regs tail?]` is the current
  committed instruction shape; no `:move` instruction is ever emitted
  (the lowerer is target-register-passing: a callee's result lands
  directly in the register the caller wants, no separate move step).
- src/cljc/yin/vm/debruijn_register_compile.cljc (R1, same worktree):
  `lower-register`, the allocator (`allocate-temp!`/`free-temp!`,
  deterministic linear-scan, evaluation-order virtual-value definition,
  lowest-available-register selection, lowest-virtual-id tie breaks), and
  the lift. Read the allocator closely -- it already computes, internally,
  when a temporary register becomes free (dead) as part of doing
  allocation at all; the raw liveness information already exists inside
  the allocator's own state during lowering, it is just not currently
  emitted anywhere in the output.
- The register image shape R1 chose: `{:bodies [{:locals :registers
  :start :end} ...], :instructions [...]}` (body ranges plus positional
  instructions, per design section 3's "Register image" row).

## What to design

1. **Where does the live-set live?** Two options, decide between them (or
   propose a better one) and justify against this project's own
   discipline (canonical bytes, determinism, "derive don't persist"):
   - In-band: an additional operand on every non-tail `:call` instruction
     naming the live register set at that point (e.g. `:call [op rd
     fn-reg arg-regs tail? live-set]`). This makes it part of the
     canonical instruction bytes, so it participates in R (the hash)
     directly -- two lowerings that differ only in liveness annotation
     would be different programs by R's own definition, which is
     probably correct (an incorrect liveness computation IS a
     correctness bug, and R should catch it), but confirm this
     reasoning rather than asserting it.
   - Side table: a separate structure (keyed by pc, alongside the
     existing diagnostic side table B2/R1 already produce for binder
     names/provenance) naming the live set at each non-tail call site.
     This keeps it OUT of the hashed canonical bytes, meaning two
     lowerings with correct instructions but different liveness
     computations would still share R -- decide whether that is
     acceptable (is liveness a purely-derived, re-computable-from-the-
     image fact that never needs to be trusted/verified independently,
     like binder names, or is it load-bearing enough for correctness
     that it should be inside the hash boundary, like every other
     operand).
2. **Determinism.** Whatever representation you choose, the live-set
   computation itself must be exactly as deterministic as everything
   else in R1 (section 4.3's discipline: no host map/set iteration order
   may affect output). State the concrete algorithm: a live-set is
   computed backward from each instruction's use/def facts, in a fixed,
   host-independent order (e.g. sorted register-index order for
   serialization, computed via the same `sorted-set` discipline the
   allocator's own free-list already uses per R1's report). Do not leave
   the ordering unspecified.
3. **Encoding.** If in-band: what scalar shape does a live-set take under
   the existing scalar encoder (a `:vector` of `:long` register indices,
   sorted, is the obvious choice -- confirm or propose otherwise) so it
   round-trips through `debruijn-code/encode-scalar` like every other
   register operand, per R1's own "zero reimplemented hex/framing code"
   discipline. If side table: what shape, matching the existing
   pc-keyed/entity-keyed side table conventions this lineage already
   uses.
4. **Contract version.** Confirm this is a genuine format-shape change
   requiring R0's frozen `register-contract-version` (currently 1) to
   bump, per design section 3: "The descriptor version is incremented
   whenever opcode shape, allocation, scalar framing, or control-flow
   rules change." State the new version number and update section 3's
   descriptor table and R0's own frozen constant is OUT OF SCOPE for you
   to edit directly (R0's test file is a separate phase's frozen
   artifact) -- instead, state precisely what the version bump means for
   the FOLLOW-UP IMPLEMENTATION phase you are designing for (it will need
   to bump the constant in `debruijn_register_code.cljc`, which is R1's
   own file, already yours to consider changed).
5. **Validator.** State what the validator (`debruijn_register_code.cljc`'s
   rules) must newly check: at minimum, that a recorded live-set only
   names registers within the body's declared count (register-bounds,
   already exists for other operands, extend it), and that it is a set
   (no duplicates) in canonical sorted order (a canonicalization rule,
   so two semantically-identical live-sets can never produce different
   bytes).
6. **Scope boundary.** State plainly: this is still R1's file box (the
   lowerer and format), not R4's. Nothing about this requires a kernel to
   exist -- the live-set is computed and recorded at compile time,
   consumed only later, if R4 is ever built, to decide what to actually
   save at a real runtime continuation-push. Do not let this task's
   design imply any R4 work is needed now.

## Deliver

Edit docs/design/yin.vm.debruijn.register.md: section 3's descriptor
table (add a field if the encoding needs documenting there), section 4.4
(the `:call` instruction shape, if you choose in-band), a new subsection
under 4 for the live-set computation and its determinism rule if it does
not fit cleanly into 4.2/4.3, and DECIDED/DEFERRED as needed (this was a
DEFERRED item in earlier revisions -- resolve it, do not leave it listed
as still deferred).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then: your choice (in-band or side table) and why; the exact encoding;
the determinism rule; the contract-version implication; what changed in
the document and where; a precise, implementable spec the follow-up
coding phase can build directly from without re-deriving any of these
decisions itself.
