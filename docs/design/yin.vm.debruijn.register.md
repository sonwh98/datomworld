# yin.vm de Bruijn register VM

Status: design, revised; not implemented

This document specifies a register execution path that is a peer of the
committed de Bruijn stack path. Both are projections of the same de Bruijn
encoding of the named semantic tuples; neither is derived from the other.
It does not replace the named datoms, the stack image, the stack VM, or the
dormant projection. The first deliverable is a pure resolved-tuples-to-
register lowerer and validator. The register VM kernel is a committed later
phase, authorized by the owner without a benchmark condition (section 8,
DECIDED 1).

## 1. Objective and invariants

The objective is a deterministic register image derived from the de Bruijn
encoding of the named `:yin/*` semantic tuples, at the same level as the
`:yin.debruijn.code/*` stack image. The register lowerer consumes the
resolved tuples and emits a register image; it never reads, rewrites, or
depends on the stack image. The stack image remains independently
addressable by its existing H, and the register image is independently
addressable by its own R.

This follows the project's established compilation topology
(`docs/agents/architecture.md`, "COMPILATION AS STREAM PROCESSING"): one
upstream stream feeds several parallel projections, and no projection is
compiled from another. That pattern is applied here one level down, at the
de Bruijn layer.

This path is worth building on its own merits, and the merit is not
performance. The governing reason, in the owner's words, is that it
demonstrates the philosophy that one universal AST can be interpreted by
different VMs: one truth, many interpretations. The named datoms are this
project's single source of truth. The named VM, the stack VM, and the
register VM are not competing implementations with one canonical and the
others alternates; they are peer witnesses to the same truth, each free to
interpret it by its own execution model, bound only by agreement on
observable behavior under the B0 normalizer and never on internal
mechanism. This is not new here. It is axiom 2 of `datom.world.md`,
"Interpretation Creates Semantics ... one truth, many perspectives", made
concrete for evaluators the way its Streams section already makes it
concrete for a `yin.vm` evaluator and `dao.space` reading one stream with
no privileged reader ("one stream, many interpretations, none of them the
stream's own"), and the way `docs/agents/architecture.md`, "COMPILATION AS
STREAM PROCESSING", already states it for backends ("Same datoms, multiple
interpreters ... same wave function, different measurements"). The same
file's "AGENTS" section, under Continuation Migration, already relies on
it: the AST datoms are the canonical payload, bytecode is a projection for
one execution model, and a destination projects the datoms into whatever
model it prefers. A third evaluator over the same datoms is that
established idea with one more witness.

The specific mechanism for it is the one this epic has been building
toward: a configurable compilation pipeline with `dao.stream` as every
stage boundary, in which several peer executable formats (named, stack,
register) share one upstream de Bruijn encoding and each backend is an
independent, swappable interpreter rather than a hardcoded single
evaluator. The register path also targets an alpha-invariant, content-
addressed encoding rather than reintroducing a VM directly beside the AST
walker. A lowerer alone proves the register format is well defined. Only a
running kernel proves that a second execution backend can be plugged into
the same pipeline and run real programs through it, which is what makes
the philosophy demonstrated rather than asserted. The owner has therefore
authorized the kernel unconditionally; the lowerer and format still ship
first, as their own milestones, and the R3 benchmark reports numbers
without deciding anything.

The following invariants apply:

1. A register image is derived data, derived from the resolved tuples. The
   stack image is never consumed, rewritten, or invalidated by the register
   path, and neither image is derived from the other.
2. `dao.stream` is the stage boundary. Every lowering is a pure stream-to-
   stream function; no stage mutates an input stream or retains a callback.
3. H and R are peer format identities, not competing semantic identities.
   H identifies the exact stack bytes; R identifies the exact register bytes
   that a register-capable host can run. Neither preimage mentions the other.
4. Equal R implies equal register bytes and descriptor contract. It does not
   imply that a stack VM can execute those bytes.
5. Register execution must agree with the named semantic VM, and therefore
   with the stack VM, under the B0 normalizer: results, errors, store
   effects, stream outcomes, tail calls, and lexical resolution.
6. All register allocation choices are data-derived and deterministic across
   runs and hosts.

The existing stack design's D1-D16 decisions remain authoritative. The
register path adds no projection fingerprint and no global registry. B2
is decided as the resolver-and-stack-lowerer split described in section
2.2 below; everything else in `yin.vm.debruijn.stack.md` is unchanged.

### 1.1 Identity and sharing

The register descriptor is `:yin.debruijn.register/*`. Its `register-hash`
function, R, hashes the register descriptor hash and the canonical
positional register vector, exactly as B1's `image-hash` hashes the stack
descriptor hash and the canonical stack vector. R is the only identity used
to fetch, verify, cache, or execute register bytes. A linker request must
name both the format and R, for example
`{:format :yin.debruijn.register :hash R}`.

R is alpha-invariant for binder renames for the same reason H is: the
register vector carries `:load-bound` depth and position operands and
`:closure` arities, and binder names live in a diagnostic side table outside
the hash. Equal R therefore implies agreement on exact scalar spelling, free
name operands, and front-end tail flags, as equal H does. Each format is
deterministically derived from the resolved tuples, so equal resolved
tuples give equal H and equal R, and a binder rename changes neither. No
biconditional between H and R is claimed: two deterministic projections may
preserve different distinctions, and the corpus tests are evidence for the
one-directional law only.

This is not a second semantic answer to alpha-equivalence. A register
lowerer cannot repair differences in exact scalar spelling, free names, or
front-end tail flags; those differences already make the resolved tuples,
and so both H and R, differ.

The pairing of an R with the H of the same named root is a composition
fact, carried beside a request or recorded as a datom by whoever publishes
both, never inside either preimage. A receiver verifies a fetched register
image with R alone and never needs to have seen the stack image.

## 2. Compilation topology and stream contract

The register projection is a peer of the stack projection. Both branch from
the resolved tuples, the de Bruijn encoding of the named AST datoms:

    Source -> Yang -> named AST datoms (:yin/*)
                         |\
                         | \-> named semantic image (:yin.code/*)
                         |
                         +----> resolved tuples (de Bruijn AST, no H)
                                   |\
                                   | \-> stack image (H)      B1 shape, B2
                                   |
                                   +---> register image (R)   R1

The register edge is not chained after the stack image. The named stream,
the resolved tuples, the stack image, and the register image coexist and
remain independently queryable or persistable, per the pipeline invariant
that no stage destroys its input.

The stages have the pure shapes:

    (resolve named-datom-stream)      -> resolved-tuple-stream
    (lower-stack resolved-stream)     -> stack-image-stream     (H)
    (lower-register resolved-stream)  -> register-image-stream  (R)

Each register output item carries a register descriptor reference, the
canonical register vector, R, and a diagnostic side table. The implementation
may build indexes while evaluating one bounded item, but those indexes are
local function data and are not written back into any input.

The register stage refuses malformed resolved tuples, unsupported node
types, or out-of-range bound operands with qualified data diagnostics. It
does not silently fall back inside the lowerer. A composition may route the
same named root to the stack path after receiving that outcome.

### 2.1 The shared artifact: resolved tuples

The resolved tuples are defined by the stack design, section 3.1, and that
definition is normative here. In summary: the named `:yin/*` datoms form a
finite graph in which one source entity may be referenced under several
lexical contexts, and resolution is a fact about an occurrence, the pair
`[source-eid lexical-context]`, not about the entity. The resolver mints one
resolved record per distinct occurrence, with a fresh deterministic id, and
shares it across references under equal contexts. Each record is the source
node's datoms with exactly two changes, the same two changes B1's opcode
table makes to the named instruction table:

1. A `:variable` record is resolved by the public
   `yin.vm.debruijn/resolve-name` against the innermost-first stack of
   enclosing parameter vectors, to either a bound reference `[depth
   position]` or a free reference carrying the exact symbol.
2. A `:lambda` record carries its arity instead of its parameter vector.

Everything else is carried exactly: scalar values with their exact spelling,
free names as supplied, `:yin/tail?` as the front end wrote it, node
structure, and evaluation order. Binder names and provenance go to the
resolver's side table, keyed by resolved record: `:source` maps every
record to its source eid and `:params` maps every resolved lambda to its
exact parameter vector. Nothing is canonicalized: this is not the dormant
merged projection, which canonicalizes scalars, NFC-folds names, and drops
tail flags, and which the stack design's architecture B rejected as a
lowering input for those reasons. It does share that projection's
occurrence key, because the two face the same graph.

The resolver is the graph-level home of name resolution. It validates each
bound reference against the enclosing arity chain, the same nonnegative-
depth-and-position rule B2 checks on the vector, refuses a program whose
resolution fails before either lowerer runs, and exports the one
`validate-resolved` both lowerers call unconditionally on their input. It
reuses only `resolve-name`; projection-only helpers are not reused, and the
resolver inherits the stack design's retirement condition for that helper.

The resolved tuples have a shape contract and a namespace so that two
lowerers and their tests can agree on what they consume. They have no hash,
no identity, and no sharing role:

- Invariant I shares executable code. The resolved tuples are not executable
  by any VM in this project; the executable artifacts are the stack image
  and the register image, identified by H and R.
- D9 and D12 make H the only identity on the stack sharing path, computed by
  one function over the descriptor hash and the canonical vector. R mirrors
  that rule for the register path. A hash of the resolved tuples would be a
  third identity that no request, response, verification, or cache needs.
- "Derive, do not persist" (`datom.world.md`): the resolved tuples are a
  pure function of the named datoms and `resolve-name`. Persisting them or
  giving them an identity would duplicate authority over facts the named
  datoms already hold. They are a stage value: computed, consumed, and
  discardable. A composition that chooses to retain the stream may do so,
  as it may retain any intermediate, but nothing downstream keys on it.
- D2 and section 7.3 of the stack design forbid a second lossless record
  DAG. The resolved tuples are not one: they are never stored as a source
  view, never served, and never a fetch key. With the side table they are
  invertible to the named datoms, but that property is used only by tests.

### 2.2 Relation to B2 as implemented

The owner decided, and B2 now implements, the resolver-and-stack-lowerer
split rather than the earlier fused pass: `yin.vm.debruijn-resolve/resolve`
produces resolved tuples per section 2.1 (occurrence-indexed identity,
cycle refusal, the exported `validate-resolved`), and
`yin.vm.debruijn-linearize/lower-stack` consumes them, calling
`validate-resolved` unconditionally at entry. `adapt`, the public entry
from named datoms to a stack image, is the composition `lower-stack` after
`resolve`. The stack image is therefore literally a peer projection of the
resolved tuples, by construction, not merely an equivalent one proven by a
separate law -- the vector-level scope reconstruction the earlier fused
pass needed (`closure-body-ranges`, `layout-conforms?`, `body-owner`,
`chain-of`) is removed, not moved, because scope is resolved on the graph
before either lowerer runs.

`lower-stack` still cannot call `yin.vm.linearize/lower` directly (it reads
`:yin/name` and `:yin/params`, which resolved tuples no longer carry), so it
reproduces the named linearizer's flattening walk itself: the same
recursion order, the same `:push`/`:call`/label emission, occurrences
expanded positionally exactly as `lower` expands them (a resolved record
referenced twice contributes two emitted instructions, at two pcs). Layout
equality with `lower` no longer holds by construction and is a required
structural-comparison test instead. Every golden H is unchanged: the
emitted bytes are identical to the fused pass's, confirmed against B1's
golden fixtures.

The address law remains useful as an additional cross-check for the
register lowerer once it exists, not as B2's own coupling mechanism:

    addresses(B2 stack image) = addresses(register image)
                              = addresses(resolved tuples)

where `addresses` is the sequence of resolved variable references, bound
`[depth position]` or free name, in evaluation order with occurrences
expanded positionally: the main sequence first, then each out-of-line body
in discovery order. This is an R0 fixture over the whole B0 parity corpus
plus the shared-occurrence fixtures of the stack design's B2 box.

## 3. Register dimension

The descriptor declares:

    +----------------------+-----------------------------------------------+
    | Field                | Rule                                          |
    +----------------------+-----------------------------------------------+
    | Namespace            | :yin.debruijn.register/*                      |
    | Contract version     | Explicit integer, included in descriptor H    |
    | Source               | Resolved tuples (section 2.1)                 |
    | Register image       | Body ranges plus positional instructions      |
    | Scalar encoding      | Reuse B1 scalar bytes, never projection NFC   |
    | Lexical addressing   | :load-bound depth/position remains explicit   |
    | Free addressing      | :load-free name remains exact                 |
    | Validation           | Shape, targets, body scope, register bounds   |
    | Lift                 | Register image to named semantics             |
    +----------------------+-----------------------------------------------+

The register descriptor has its own validator because register operands and
body register counts are new facts. It reuses B1's public data rules and
scalar encoder only through an explicit dependency; it does not call private
projection helpers. The descriptor version is incremented whenever opcode
shape, allocation, scalar framing, or control-flow rules change.

The lowerer's R formula is:

    R = sha256(register-descriptor-hash || canonical-register-vector)

Both components are canonical bytes. Received register bytes are hashed
before decoding, as in B1's wire rule. A receiver verifies R, checks the
descriptor, runs the receiver closure check over `:load-free` operands
exactly as D11 and D15 define it for stack images, validates, and only then
executes. No relation to a stack H is embedded or checked.

The lift goes from the register image to the named `:yin.code/*` image, so
that B6's rule "a fetched image may be executed by the semantic VM after
lifting" holds for register images too. It is a function of the register
image plus its side table, under the stack design's trust rule (section
3.2 there): execution lifts use synthesized names, fresh against the
image's free-name set and each other, by default. A supplied side table is
used only when every closure entry's parameter count equals the
instruction's arity, its names are capture-free by the same rule, and
lowering the lifted result reproduces the original register image byte for
byte; otherwise it is discarded. R1 tests original-name equality and
synthesized-name alpha-equivalence, including duplicate parameters.

## 4. Resolved-tuples-to-register lowering

### 4.1 Input and validation

The lowerer accepts only resolved tuples from the section 2.1 resolver and
their side table. It calls the resolver's `validate-resolved` first and
refuses on its diagnostic, identically to `lower-stack`, before emitting
anything. It does not accept named datoms directly, does not rerun name
resolution, and does not accept a stack image. B2 remains the only
producer of `:yin.debruijn.code/*` stack images; this lowerer is the only
producer of `:yin.debruijn.register/*` register images.

Because the input is an expression graph rather than a flat instruction
stream, there is no control-flow graph to recover and no abstract operand
stack to simulate: every intermediate value is an expression whose extent
is known from its record. The walk is the named linearizer's order,
operator then operands left to right, lambda bodies out of line in
discovery order, `if` evaluating one arm, with occurrences expanded
positionally: a resolved record referenced twice is lowered twice, at two
places, exactly as `lower-stack` and `lower` do. Every body has one
enclosing lexical chain by construction, and every bound reference is
already validated against it. The lowerer's own checks are register bounds,
body ranges, and target bounds, applied to its output.

### 4.2 Register classes

Each body has two disjoint register banks:

    L0 ... L(n-1)     current body's parameters
    T0 ... T(k-1)     expression temporaries

`n` is the body's declared arity. `:load-bound rd depth position` reads the
existing lexical frame chain and writes `rd`; it does not assign an outer
frame's slot a register number in the current body. Thus frame 0 remains the
innermost frame and the runtime frame vector remains outermost-first, exactly
as in B3. `:load-free rd name` preserves the existing resolution order.

The allocator gives every intermediate expression value a virtual temporary
in evaluation order, then performs deterministic linear-scan allocation
within each body. Locals are never coalesced with temporaries. The physical
register count is the number of local registers plus the maximum
simultaneously live temporary registers. If a target requires a bounded
register file, deterministic spill slots are allocated after the last
register using the same order; spilling is a later kernel concern and is not
implicit in R1.

### 4.3 Determinism

Lowering from expression records removes the join-order risk a stack-image
compiler would have: there are no predecessor traversals to order, and both
arms of an `if` write the expression's one destination register. The remaining
risks are hash-map iteration, liveness tie breaks, and spill choices.

The lowerer closes these risks by using evaluation order for virtual-value
definition, lowest available register, and lowest virtual-id tie breaks.
Any moves that a target convention requires are sorted by destination
register and emitted in a fixed cycle-breaking order. No host map iteration
order may affect an emitted vector. R1 pins golden register vectors and R
values for a corpus on JVM, CLJS, and CLJD.

### 4.4 Instruction mapping

The register instruction set is a positional form of the B1 table:

    :const        [op rd value]
    :load-bound   [op rd depth position]
    :load-free    [op rd name]
    :closure      [op rd arity body-pc]
    :move         [op rd rs]
    :call         [op rd fn-reg arg-regs tail?]
    :branch-false [op cond-reg target]
    :jump         [op target]
    :return       [op value-reg]
    :halt         [op value-reg]
    :store-get    [op rd key]
    :store-put    [op key value-reg]

There is no `:push`: operands are named by register, so the stack path's
push-before-each-operand convention never arises. `:call` differs most from
its stack form: instead of an argc over an operand stack it names an
explicit function register, an ordered argument-register vector, a
destination register, and the tail flag copied from `:yin/tail?`.
`:branch-false`, `:return`, and `:halt` name registers instead of reading an
operand stack. Constants, lexical loads, free loads, closures, jumps, and
store operations retain their semantic operands while gaining explicit
destinations where needed.

Stream, gensym, FFI, park, and resume instructions use the same explicit
destination and source-register convention in R2. Their effect descriptors
remain stream values; no callback or hidden scheduler is introduced.

## 5. Execution boundary

R1 may produce and validate register bytecode without a register kernel. This
is the first milestone and is analogous to B1's standalone dimension; the
kernel follows it as a committed phase, not a possibility.

R4 adds `yin.vm.debruijn.register` as a sibling kernel. It owns explicit state
`{:image :pc :registers :frames :free-env :continuation :store :status
:primitives :modules}` and uses the same frame, free-name, store, stream, and
continuation contracts as B3, including the engine seam B4 implements
(`yin.vm.engine.md`: one restore function, per-site park-entry builders, the
engine-owned key sets). It adds no `IVM` or `IVMState` methods.

The register kernel must execute only validator-approved images. It must
preserve B3's frame direction, closure capture, nil-fill and extra-argument
rules, and B0 normalization. Register continuations are not interchangeable
with stack continuations unless an explicit lift is provided.

## 6. Implementation phases

Each phase has a bounded file box, must-not-change list, completion criteria,
and JVM, CLJS, CLJD, kondo, and cljstyle verification.

### R0: contract and corpus

    New: test/yin/vm/debruijn_register_contract_test.cljc
    Existing edits: none
    Must not change: B0-B3, named datoms, stack image, resolver contract,
    projection namespace

The resolver (`yin.vm.debruijn-resolve`: resolved tuples, side table,
scope validation, the inverse used by tests) is now B2's own file box, per
the stack design's B2 phase box (`yin.vm.debruijn.stack.md` section 3.1) --
not built here. R0 depends on it and freezes the register descriptor
version, operand mapping, normalizer fixtures, and the named-root corpus
against it. Completion requires every node type from the resolver either
mapped or refused with a named diagnostic, the duplicate-parameter and
free-name fixtures from B2, the section 2.2 address law between B2's stack
images and the resolved tuples over the B0 parity corpus, pure data
checks, and a decision that no register kernel is assumed yet.

### R1: register dimension and lowerer

    New: src/cljc/yin/vm/debruijn_register_code.cljc
    New: src/cljc/yin/vm/debruijn_register_compile.cljc
    New: test/yin/vm/debruijn_register_compile_test.cljc
    Existing edits: none
    Must not change: B1 code, B2 lowerer, B3 VM, resolver contract,
    dao.stream protocols

Implement the descriptor, validation, deterministic lowering from resolved
records, allocation, encoding, R, and the lift to `:yin.code/*`. Completion
requires every resolved node type mapped or refused with a named diagnostic,
byte-identical repeated lowerings, golden register vectors, cross-host R
agreement, the section 2.2 address law extended to the register image, and
the lift law `lift(lower-register (resolve x), side-table)` alpha-equivalent
to `canonical-vector(lower x)` under the arity and layout caveats of the
stack design's B2 box.

### R2: effects and stream forms

    Existing source: src/cljc/yin/vm/debruijn_register_compile.cljc
    New: test/yin/vm/debruijn_register_effects_test.cljc
    Existing edits: none
    Must not change: dao.stream, lease, waitset, B0-B3 semantics

Lower stream, gensym, FFI, park, and resume shapes with explicit registers.
Completion compares effect descriptors and blocked outcomes with the stack
path without executing a register VM.

### R3: benchmark report

    New: test/yin/vm/debruijn_register_benchmark_test.cljc
    Existing edits: none
    Must not change: stack VM and its H

Informational only, and sequenced after R4: it runs identical pure-program
corpora through the stack VM and the real R4 kernel, never a stand-in
interpreter, which the old gate needed only because the kernel was not yet
authorized. Report throughput, allocation, image size, load time, and
lowering cost. This is the same role the stack design gives its own B3
report: numbers the owner reads, not a condition any phase satisfies. It
gates nothing, and no threshold is defined for it.

### R4: register kernel

    New: src/cljc/yin/vm/debruijn/register.cljc
    New: test/yin/vm/debruijn/register_test.cljc
    Existing edits: none
    Must not change: B0-B3, named VM, existing IVM methods

Authorized unconditionally (section 8, DECIDED 1). Its prerequisite is R1:
a validated register image and its R. R4 follows B3's own precedent and
ships in two tiers. The pure-program tier depends on R1 only: frames,
closures, loads, calls, returns, branches, constants, and store operations,
with every opcode outside that set refused loudly with a
`:not-yet-implemented` diagnostic, exactly as B3 does before B4. Its
completion requires B0-normalized parity against the named VM and the stack
VM over the pure-program corpus, all register validator fixtures, lexical
and closure tests, and store and control flow tests. The effects tier
depends on R2, for the register shapes of stream, gensym, FFI, park, and
resume, and on B4's engine seam, which supplies the restore and park-entry
conventions the kernel must share. Its completion requires parity for
values, errors, effects, stores, stream outcomes, and blocked states, and
deterministic stream/effect behavior. The pure-program tier may merge
before R2 lands; R4 is complete only when both tiers are.

The phase order is therefore R0, R1, R2, R4, R3, R5, with R4's pure-program
tier free to precede R2.

### R5: linker integration

    New: src/cljc/yin/vm/debruijn_register_linker.cljc
    New: test/yin/vm/debruijn_register_linker_test.cljc
    Existing edits: none
    Must not change: stack linker semantics or stack H

Fetch and verify R over `dao.stream` with the same request, wire-hash,
closure-check, and refusal shape as B6, keyed by R. A host may refuse R and
instead request, by H, a stack image the composition has published for the
same named root; that pairing is composition data, not linker machinery. No
global loader or callback is introduced.

## 7. Non-goals and protected surfaces

This design does not alter any B0-B7 phase's semantics, the stack VM, named
datoms, the dormant projection, or the AST-walker. It does not change B1's
H, its scalar bytes, its validator, or its wire protocol. It does not add
equality saturation, a JIT, lambda lifting, ANF, global distribution, or a
new continuation interchange format. Its one proposed edit to the stack
design, B2's adoption of the resolver, is recorded in section 2.2 and is the
owner's decision.

The register lowerer consumes the resolved tuples, never the stack image and
never named datoms directly. It is not a second stack lowerer: it emits only
`:yin.debruijn.register/*` and B2 remains the sole producer of
`:yin.debruijn.code/*`. Its input remains available after the transform.
Register code is not portable to a stack-only host; such a host obtains a
stack image for the same program by H or by lowering the named datoms.

The resolver is not a second lossless source view. It has no identity, is
not persisted, and is not a fetch key; its inverse exists for tests only.

## 8. Risks and decisions

Risks include register-allocation drift, spill policy becoming observable,
effect ordering, register continuation shape, drift between B2's fused
resolution and the resolver if B2 is not refactored, and the permanent
second-evaluator maintenance surface accepted in DECIDED 1.

DECIDED:

1. R0-R2 are lowerer and format work. R4, the register kernel, is
   authorized to proceed as soon as its prerequisites exist (R1 for the
   pure-program tier, R2 and B4's engine seam for the effects tier) and is
   not contingent on R3's report or any benchmark. This is a deliberate
   architectural commitment to a second evaluator, made by the owner on
   2026-09-23 after the tradeoff was explained: every future opcode, effect,
   or contract change is implemented and tested twice, in the stack kernel
   and the register kernel, across JVM, CLJS, and CLJD, permanently. The
   owner accepted that cost because the kernel is the end-to-end proof that
   the `dao.stream` compilation pipeline is genuinely configurable with
   swappable peer backends (section 1). The commitment is not informational
   and is not revisited by R3's numbers.
2. The stack image and the register image are peer projections of the
   resolved tuples. H and R are peer executable-format identities; neither
   preimage includes the other, and neither is a replacement semantic
   identity.
3. The resolved tuples are a derived stage value with a shape contract and a
   namespace but no hash, no identity, and no sharing role, per invariant I,
   D9, D12, and "derive, do not persist".
4. Lowering is a pure, non-destructive stream transform. Named datoms,
   resolved tuples, stack images, and register images coexist.
5. Lexical depth and position remain explicit. Locals and temporaries use
   disjoint register banks.
6. Allocation and any move order are canonicalized by evaluation and
   virtual-id order, with a versioned descriptor and golden fixtures.
7. The register kernel is a sibling and cannot silently replace the stack
   VM or alter its protocols.
8. B2 is the resolver-and-stack-lowerer split (section 2.2); the address
   law over the parity corpus remains a cross-check, not the coupling.

DEFERRED:

- Owner approval to start R1 and the exact register descriptor publication.
- Spill representation and register-file limits, if a target requires them.
- Register continuation lifting and cross-model park/resume transport.
- Whether R5 is ever commissioned; R4 is decided (DECIDED 1).

## 9. End condition

The lowerer-only end condition is a validated, deterministic register image
whose R is reproducible on all three hosts, whose address law with the stack
image holds over the parity corpus, and whose named-VM normalized fixtures
agree. The full register-VM end condition adds a validator-approved kernel
with both R4 tiers complete, the R3 report published for the owner to read,
and, if R5 is commissioned, cross-stream R fetch and verification. The
project does not stop at R2: R3's numbers inform, they do not decide.
