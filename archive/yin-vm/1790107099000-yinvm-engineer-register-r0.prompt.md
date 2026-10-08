Created-GMT: 2026-09-22 17:58:19 GMT
Created-Local: 2026-09-23 00:58:19 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: pending (new session, caller-generated)
# Task: register-r0 — the register VM's contract and corpus
Role: yin.vm / Interpreter Engineer
Implementers:
- Model: claude-sonnet-5 | Assigned: 2026-09-23 00:58:19 +07 | Status: active | Rationale: fresh session; this phase freezes decisions later phases (R1+) implement against, so it deserves a clear head rather than a session already spent on adjacent work

Work in /Users/sto/workspace/worktree-register-r0 (your launch directory;
branch register-r0, HEAD is current master: B0-B3 merged, B2 -- the
resolver/stack-lowerer split -- merged). Do NOT stage, commit, merge or
push.

## What this phase is and is not

R0 is test-only, like B0 was for the stack VM: it freezes the contract
R1 (the actual register lowerer, not built here) implements against. It
does NOT implement `yin.vm.debruijn-register-code` or
`yin.vm.debruijn-register-compile` -- those are R1's file box. R0 does
NOT build, and must not assume, a register kernel (R4) -- the design is
explicit that R0-R2 are lowerer/format work only, R4 is gated behind R3's
benchmark, and "the format lowerer and format may ship without a kernel."

## Read first, in full

- docs/design/yin.vm.debruijn.register.md, in full. Section 1 (objective,
  invariants), section 2 and 2.1 (the resolved-tuples contract, normative
  from the stack design), section 2.2 (the address law, read this
  slowly -- it is R0's main deliverable), section 3 (the register
  descriptor table: namespace, contract version, source, scalar encoding,
  validation, lift), section 4.4 (the instruction mapping table -- this
  is the operand-mapping contract R0 freezes), section 6's "R0: contract
  and corpus" box (your exact scope and completion criteria, quoted
  below), and section 8 (DECIDED item 1: "R0-R2 are lowerer and format
  work; R4 is gated by R3").
- docs/design/yin.vm.debruijn.stack.md section 3.1 (the resolved-tuples
  contract this design defers to as normative) and the "B0: contract and
  normalizer" phase box, for the PATTERN this phase follows (a
  test-only phase that freezes a normalizer, a corpus, and completion
  criteria that later phases are checked against, with no src/ file of
  its own).
- src/cljc/yin/vm/debruijn_resolve.cljc, in full (B2's resolver, merged):
  `resolve`, `validate-resolved`, the occurrence-indexed identity model,
  the `:source`/`:params` side table. This is R0's actual input -- read
  it, not just the design doc's summary of it.
- src/cljc/yin/vm/debruijn_linearize.cljc, in full (B2's stack lowerer,
  merged): `lower-stack`, `adapt`. R0's address-law test compares this
  lowerer's output against the resolved tuples directly.
- test/yin/vm/debruijn_vm_contract_test.cljc (B0, merged): the existing
  parity corpus and `normalize` function this phase reuses. Read its
  actual corpus fixtures, do not guess their shape.
- test/yin/vm/debruijn_resolve_test.cljc and
  test/yin/vm/debruijn_linearize_test.cljc (B2, merged): the
  duplicate-parameter and free-name/shared-occurrence fixtures this
  phase's completion criteria say to reuse -- read their actual shape
  (`duplicate-param`, `free-variable`, `shared-variable-under-two-
  contexts`, `shared-lambda-under-two-contexts`, or whatever the current
  fixture names are) before writing new ones.
- src/cljc/yin/vm/debruijn_code.cljc (B1, merged): `image-hash`'s exact
  formula and canonical-bytes convention, since R's formula (design
  section 3, `R = sha256(register-descriptor-hash || canonical-register-
  vector)`) mirrors it and R0 freezes the register descriptor's
  contract-version field the same way B1's descriptor has one.

## Exact scope (section 6's box, quoted)

    New: test/yin/vm/debruijn_register_contract_test.cljc
    Existing edits: none
    Must not change: B0-B3, named datoms, stack image, resolver contract,
    projection namespace

## What to build

A single new test file, `test/yin/vm/debruijn_register_contract_test.cljc`,
that freezes, as data and as tests against already-merged code (B0's
normalizer, B2's resolver and stack lowerer), everything R1 will
implement against:

1. **The register descriptor's contract version**: an explicit integer,
   documented with a one-line rationale for why it starts where it does
   (e.g. "1, the first version"). This is a frozen constant future phases
   read; do not leave it implicit.
2. **The operand mapping**: for every resolved-tuple node type B2's
   resolver can produce (`:literal`, a bound `:variable`, a free
   `:variable`, `:lambda`, `:application`, `:if`), record, as data (a
   map literal is fine), which register instruction shape from section
   4.4's table it maps to. For every node type OUT of scope at this
   phase (anything R2 covers: stream/gensym/FFI/park/resume/current-
   continuation, i.e. `:dao.stream.apply/call`, `:stream/*`, `:vm/gensym`,
   `:vm/store-get`, `:vm/store-put`, `:vm/park`, `:vm/resume`,
   `:vm/current-continuation`), record that it is refused with a named
   diagnostic at R0/R1 and IS in scope for R2 -- do not silently omit it
   from the map, name it explicitly as deferred. This table is the
   contract R1's actual lowerer must match; a test should assert the
   map's keys are exactly the resolver's full node-type vocabulary (no
   node type silently unaccounted for).
3. **The two-way address law**, section 2.2's law restricted to what R0
   can actually test without a register lowerer (which does not exist
   yet): for every program in the reused B0/B2 parity corpus (plus the
   duplicate-parameter and shared-occurrence fixtures named above),
   compute `addresses(resolve x)` -- the sequence of resolved variable
   references (bound `[depth position]` or free name) read directly off
   the resolved tuples in evaluation order (operator then operands left
   to right, `if` test then arms, the main body first then each
   out-of-line lambda body in discovery order, an occurrence referenced
   twice contributing twice) -- and `addresses(lower-stack (resolve x))`
   -- the same sequence read off the stack image's `:load-bound`/
   `:load-free` operands in pc order. Assert these two sequences are
   equal for every corpus program. This is the baseline law R1 extends
   with a third sequence (the register image's) once it exists; R0's job
   is to build and pin the `addresses` function against the two paths
   that already exist, so R1 has a working, tested two-way version to
   extend rather than deriving the whole law from scratch.
4. **Pure data checks**: the register descriptor's declared field list
   (design section 3's table: namespace, contract version, source,
   register image shape, scalar encoding, lexical/free addressing,
   validation, lift) is itself checked for internal consistency where
   that's checkable without R1's code -- for instance, that every field
   this phase's own operand-mapping table implies is consistent with
   what section 3's table declares. Use your judgment on what's
   meaningfully checkable here without over-building; this is a small
   part of the phase, not its center of gravity.
5. **A recorded decision that no register kernel is assumed yet**: a
   docstring statement in the new test namespace, not a runtime
   assertion, matching design section 8's DECIDED item 1 ("R0-R2 are
   lowerer and format work; R4 is gated by R3's benchmark"). State this
   plainly so a future reader of this file does not assume R0 implies a
   kernel is coming.

## Never

Do not create `src/cljc/yin/vm/debruijn_register_code.cljc` or
`debruijn_register_compile.cljc` -- those are R1's file box, not yours.
Do not implement any register lowering, allocation, or encoding logic.
Do not touch `yin.vm.linearize`, `yin.vm.code`, `yin.vm.completion`,
`yin.vm.ast_walker`, `yin.vm.debruijn` (the dormant projection),
`yin.vm.debruijn-resolve`, `yin.vm.debruijn-linearize`,
`yin.vm.debruijn-code`, or `yin.vm.debruijn.stack` -- only READ from
them. Do not build or assume a register kernel exists or will exist. Keep
files pure ASCII, no em dashes, cljstyle-style Clojure (blank lines
between top-level forms), docstrings that say what and why.

## Environment

Default PATH gives Java 21 and the mise clojure and bb. This worktree is
already `mise trust`ed. Focused JVM run: `clojure -M:test -n
yin.vm.debruijn-register-contract-test` (confirm the actual namespace
your file defines first). If you need kondo, cljstyle, the Java 17 lane,
or the CLJD lane and they are denied, say exactly what was denied and
stop; do not retry, do not use `--dangerously-skip-permissions`.

## Final report

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: <your session id>
Then: the frozen contract version and why; the full operand-mapping table
you produced and which node types it defers to R2, naming the test that
checks it is exhaustive; how the two-way address law test works and its
exact corpus (reused B0/B2 fixtures plus any you added, named); what the
pure data checks verify; what you ran with exact counts; what you could
not run; every deviation. Facts only; promise nothing.
