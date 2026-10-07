Created-GMT: 2026-09-22 18:12:34 GMT
Created-Local: 2026-09-23 01:12:34 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: pending (new session, caller-generated)
# Task: register-r1 — the register dimension and lowerer
Role: yin.vm / Interpreter Engineer
Implementers:
- Model: claude-sonnet-5 | Assigned: 2026-09-23 01:12:34 +07 | Status: active | Rationale: fresh session; this is the largest single phase in the register epic so far (descriptor, validator, allocator, lowerer, and lift in one phase) and benefits from full context budget

Work in /Users/sto/workspace/worktree-register-r0 (your launch directory;
branch register-r0, HEAD includes R0: `test/yin/vm/
debruijn_register_contract_test.cljc`, committed -- read it, it is your
frozen contract, not a suggestion). Do NOT stage, commit, merge or push.

## Read first, in full

- docs/design/yin.vm.debruijn.register.md, in full. You already need
  every section for this phase: 1 (invariants), 1.1 (identity: the R
  formula), 2/2.1/2.2 (topology, the resolved-tuples contract, the
  address law), 3 (the descriptor table), 4.1-4.4 (input/validation,
  register classes, determinism, instruction mapping), 5 (execution
  boundary -- read it to understand what you are NOT building: no
  kernel), 6's "R1: register dimension and lowerer" box (your exact
  scope, quoted below), 8 (DECIDED items, especially 1, 5, 6).
- test/yin/vm/debruijn_register_contract_test.cljc (R0, committed on
  this branch): the FROZEN contract. `register-contract-version` (the
  integer, currently 1) and `node-type->register-mapping` (which of the
  resolver's node types you map, to which section 4.4 instruction, and
  which are explicitly deferred to R2 -- do not implement lowering for
  anything R0 marked `:deferred-to :R2`) are not decisions for you to
  remake; read them and implement against them exactly. The two-way
  address-law test and helpers (`resolved-var-addresses`,
  `stack-image-var-addresses`) in this file are also your reference for
  how to walk resolved tuples in evaluation order -- your lowerer's own
  walk must visit nodes in the same order this test already proved
  matches `lower-stack`'s.
- src/cljc/yin/vm/debruijn_code.cljc, in full (B1, merged): this is your
  closest precedent for `debruijn_register_code.cljc`. Read its
  descriptor pattern, `scalar-class`/`encode-scalar`, `image-hash`, the
  structural validator rules (`nonempty`, `mnemonic-rule`, `arity-rule`,
  `operand-kind-rule`, `saturation-rule`, `target-bounds-rule`,
  `terminator-rule`), and `image-defect`. You reuse its scalar encoder
  and public data rules through an explicit dependency (require it), you
  do NOT reimplement scalar encoding, and you do NOT call any of its
  private helpers.
- src/cljc/yin/vm/debruijn_resolve.cljc, in full (B2, merged): `resolve`,
  `validate-resolved` (call this first, unconditionally, exactly as
  `lower-stack` does -- read that call site in debruijn_linearize.cljc
  for the pattern), the resolved-tuple shape (`:yin.resolved/depth`,
  `:yin.resolved/position`, `:yin.resolved/free`, `:yin.resolved/arity`),
  and the `:source`/`:params` side table shape you will read from when
  building your own diagnostic side table.
- src/cljc/yin/vm/debruijn_linearize.cljc, in full (B2, merged):
  `lower-stack` and `lift`. `lower-stack` is your closest precedent for
  the walk itself (operator then operands left to right, `if` test then
  arms, lambda bodies out of line in discovery order, occurrences
  expanded positionally -- a resolved record referenced twice is
  lowered twice, at two places). `lift` is your closest precedent for
  your own lift: read its trust rule closely (synthesized names by
  default, fresh against the image's free-name set and against each
  other; a supplied side table is used only when every closure entry's
  parameter count equals the instruction's arity, its names are
  capture-free by the same rule, and re-lowering the lifted result
  reproduces the original image byte for byte; otherwise synthesis is
  used). Your lift target is different (register image, not stack image)
  but the trust rule is identical -- do not weaken it.
- src/cljc/yin/vm/debruijn/stack.cljc (B3, merged): read `frame-value`
  and how `:load-bound depth position` reads the runtime frame chain,
  for context on why depth/position stay explicit operands in your
  instructions too (design section 4.2: "`:load-bound rd depth position`
  reads the existing lexical frame chain... frame 0 remains the
  innermost frame").

## Exact scope (section 6's box, quoted)

    New: src/cljc/yin/vm/debruijn_register_code.cljc
    New: src/cljc/yin/vm/debruijn_register_compile.cljc
    New: test/yin/vm/debruijn_register_compile_test.cljc
    Existing edits: none
    Must not change: B1 code, B2 lowerer, B3 VM, resolver contract,
    dao.stream protocols

## What to build

### `debruijn_register_code.cljc` -- the dimension (descriptor, validator, R)

Mirrors `debruijn_code.cljc`'s role for the stack dimension, not its
content -- this is a NEW dimension with its own facts, not a copy.

1. The descriptor: namespace `:yin.debruijn.register/*`, contract version
   read from R0's frozen `register-contract-version` (do not hardcode a
   second copy of `1` -- require R0's test namespace's constant if that
   is the cleanest path, or, if requiring a test namespace from src/ is
   wrong in this codebase's convention, define the SAME integer here as
   the canonical source and have R0's test import it instead; check how
   B1 and B0 handle this exact chicken-and-egg (does B1's own
   `lowering-contract-version` live in src/, with B0's test importing
   it, or the reverse?) and follow that precedent exactly, do not
   improvise a new pattern.
2. The instruction/opcode table: the positional register forms from
   section 4.4 (`:const [op rd value]`, `:load-bound [op rd depth
   position]`, `:load-free [op rd name]`, `:closure [op rd arity
   body-pc]`, `:move [op rd rs]`, `:call [op rd fn-reg arg-regs tail?]`,
   `:branch-false [op cond-reg target]`, `:jump [op target]`, `:return
   [op value-reg]`, `:halt [op value-reg]`). Do NOT include `:store-get`/
   `:store-put` in what you actually lower to in this phase even though
   section 4.4's table names them -- R0's frozen `node-type->register-
   mapping` deferred `:vm/store-get`/`:vm/store-put` (their SOURCE node
   types) to R2, so nothing in R1 ever emits those two instructions; if
   you declare them in the opcode table for forward-compatibility with
   R2, say so explicitly in a comment, do not let a reader assume R1
   lowers them.
3. Scalar encoding: reuse B1's `scalar-class`/`encode-scalar` through an
   explicit `debruijn-code` require. Do not reimplement or fork it.
4. The validator (`image-defect`-equivalent, name it appropriately for
   this dimension): shape, mnemonic, arity, operand-kind rules mirroring
   B1's structural rules but over YOUR opcode table; PLUS the genuinely
   new checks this format needs and the stack format does not: register-
   bounds (every `rd`/source register reference within the body's
   declared register count), body-range/target-bounds (jump and branch
   targets, closure body-pcs, all in range), and a terminator rule (every
   body ends in `:return`, the main body/segment in `:halt`, mirroring
   B1's `terminator-rule` over your own `code/terminators`-equivalent
   set).
5. `register-hash` (R): `R = sha256(register-descriptor-hash ||
   canonical-register-vector)`, mirroring B1's `image-hash` exactly in
   structure (canonical-bytes discipline, hash the received bytes before
   decoding on the receive side -- though no receive path exists yet in
   this phase, the function itself must be written so a later phase can
   use it that way).

### `debruijn_register_compile.cljc` -- the lowerer, allocator, and lift

1. `lower-register`: resolved tuples + side table -> register image +
   register-side-table. Calls `validate-resolved` first, unconditionally,
   and refuses on its diagnostic before doing anything else -- this is
   not optional, it is the R0/design-mandated seam (section 4.1, 2.1).
   Walks in the named linearizer's order (see `lower-stack` for the
   exact pattern): operator then operands left to right, `if` test then
   arms with both arms writing the expression's ONE destination register
   (section 4.3: "both arms of an `if` write the expression's one
   destination register" -- this is what removes the join-order risk a
   stack compiler would have), lambda bodies out of line in discovery
   order, occurrences expanded positionally (a resolved record
   referenced twice is lowered twice, at two places, exactly as
   `lower-stack` and `lower` do -- this is what R0's address-law test
   over 3 sequences will check once you extend it).
2. Register banks (section 4.2): each body has `L0...L(n-1)` (the body's
   declared arity's worth of parameter registers) and `T0...T(k-1)`
   (expression temporaries). `:load-bound rd depth position` reads the
   EXISTING lexical frame chain (unchanged from B3's `frame-value`
   convention, outermost-first at runtime) and writes `rd` in the
   CURRENT body's register file; it does not assign an outer frame's
   slot a register number in the current body. `:load-free rd name`
   preserves the existing free-name resolution order.
3. The allocator (sections 4.2-4.3): give every intermediate expression
   value a virtual temporary in evaluation order; deterministic
   linear-scan allocation within each body; locals are NEVER coalesced
   with temporaries (they stay in separate banks, per item 2); physical
   register count = local count + max simultaneously live temporary
   count; if you need spill slots for this phase's actual corpus (you
   likely will not, since "spilling is a later kernel concern and is not
   implicit in R1" -- but if your allocator's own logic needs a spill
   path to stay correct for pathological inputs, implement it
   deterministically: spill slots allocated after the last register,
   same evaluation order). Determinism discipline (4.3, do not skip
   any of these): virtual-value definition order is evaluation order;
   register selection is lowest-available; tie breaks are lowest
   virtual-id; any moves a target convention requires are sorted by
   destination register in a fixed cycle-breaking order; NO host map
   iteration order may affect an emitted vector anywhere in this
   pipeline (this is the determinism risk section 4.3 names explicitly
   as the real one, now that join-order is removed by construction).
4. Instruction mapping (4.4, and R0's frozen table): implement exactly
   the 6 in-scope mappings R0 froze (`:literal`, bound `:variable`, free
   `:variable`, `:lambda`, `:application`, `:if`) and nothing else.
   `:application` -> `:call [op rd fn-reg arg-regs tail?]`: the `tail?`
   flag copies `:yin/tail?` exactly as resolved, never inferred from
   syntax (same rule `lower-stack` follows). Every other node type
   R0 marked `:deferred-to :R2` must be refused with the SAME named
   diagnostic R0's contract table declares for it -- read R0's
   `:diagnostic` values and use them verbatim, do not invent new ones.
5. The lift: register image (+ side table) -> the named `:yin.code/*`
   canonical vector (the SAME target `lift` in debruijn_linearize.cljc
   produces for the stack image -- both lifts land on the same named
   shape, which is what lets B6's rule "a fetched image may be executed
   by the semantic VM after lifting" hold for either format). Follow
   `lift`'s exact trust rule (see Read-first above) -- do not write a
   looser or stricter version. The completion law is `lift(lower-
   register(resolve x), side-table)` alpha-equivalent to `canonical-
   vector(lower x)`, under the same arity/layout caveats B2's own lift
   law has (duplicate parameters, synthesized-name freshness).

## Completion criteria (do not weaken any of these; section 6's own list)

- Every resolved node type mapped or refused with a named diagnostic
  (R0's table is the source of truth for which).
- Byte-identical repeated lowerings of the same input (determinism, all
  three hosts).
- Golden register vectors and R values pinned for a corpus, on JVM, CLJS,
  and CLJD (cross-host R agreement).
- The section 2.2 address law extended to the register image: EXTEND
  R0's two-way `resolved-var-addresses`/`stack-image-var-addresses` test
  into a three-way test also computing `register-image-var-addresses`
  (read off your register image's `:load-bound`/`:load-free` operands in
  the same evaluation-order-with-positional-occurrence-expansion R0's
  helpers already use) and asserting all three sequences equal, over the
  SAME corpus R0 already uses (the B0 parity corpus plus B2's duplicate-
  parameter and shared-occurrence fixtures). Do this as an addition to
  R0's existing test file if that is the cleanest place, or your own new
  test file with R0's file required -- your call, but do not duplicate
  R0's fixture-building code, reuse it.
- The lift law from item 5 above, tested for at least: a simple program
  (no duplicates), a duplicate-parameter program `(fn [x x] x)`, a
  free-variable program, and a nested/shared-occurrence program -- both
  with the lowerer's own side table (exact equality where B2's own lift
  law achieves exact equality) and with no side table (alpha-equivalence,
  synthesized names).
- Your validator accepts every image `lower-register` emits and rejects
  every hand-built out-of-range image you construct (at minimum: an
  out-of-range register reference, an out-of-range jump/branch target,
  and a malformed terminator).

## Never

Do not implement a register kernel (that is R4, explicitly gated behind
R3's benchmark -- do not build it, do not scaffold for it beyond what
section 5's state shape already documents as a future fact). Do not
implement R2 (stream/gensym/FFI/park/resume lowering) -- refuse those
node types with R0's named diagnostics instead. Do not touch
`yin.vm.linearize`, `yin.vm.code`, `yin.vm.completion`, `yin.vm.
ast_walker`, `yin.vm.debruijn` (the dormant projection), `yin.vm.
debruijn-resolve`, `yin.vm.debruijn-linearize`, `yin.vm.debruijn-code`,
or `yin.vm.debruijn.stack` -- only READ from them. Do not modify
`test/yin/vm/debruijn_register_contract_test.cljc`'s frozen contract
(the version integer, the operand-mapping table) -- if you find it is
actually wrong or incomplete once you try to implement against it, STOP
and report the specific problem in your final report rather than
silently changing R0's file. Keep files pure ASCII, no em dashes,
cljstyle-style Clojure (blank lines between top-level forms), docstrings
that say what and why.

## Environment

Default PATH gives Java 21 and the mise clojure and bb. This worktree is
already `mise trust`ed. Focused JVM run: `clojure -M:test -n
yin.vm.debruijn-register-contract-test -n
yin.vm.debruijn-register-compile-test` (confirm your actual namespace
name first). If you need kondo, cljstyle, the Java 17 lane, or the CLJD
lane and they are denied, say exactly what was denied and stop; do not
retry, do not use `--dangerously-skip-permissions`.

## Final report

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: <your session id>
Then: how the descriptor/contract-version chicken-and-egg with R0 was
resolved; the validator's exact rules and how each maps to a test; the
allocator's determinism discipline and where each of the 4.3 rules is
enforced in code, naming the test that pins it; how the three-way address
law extension works and its exact corpus; the lift law's proof for each
of the 4 required programs; what you ran with exact counts; what you
could not run; every deviation, INCLUDING if you found R0's frozen
contract actually wrong or incomplete (do not silently work around it).
Facts only; promise nothing.
