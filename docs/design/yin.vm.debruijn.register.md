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
    | Live sets            | In-band boundary operands, hashed and        |
    |                      | recomputed by the validator (section 4.5)     |
    | Validation           | Shape, targets, body scope, register bounds,  |
    |                      | live-set shape, bounds, tail, and exactness   |
    | Lift                 | Register image to named semantics             |
    +----------------------+-----------------------------------------------+

The register descriptor has its own validator because register operands and
body register counts are new facts. It reuses B1's public data rules and
scalar encoder only through an explicit dependency; it does not call private
projection helpers. The descriptor version is incremented whenever opcode
shape, allocation, scalar framing, or control-flow rules change.

The lowerer's R formula is:

    R = sha256(register-descriptor-hash || canonical-register-vector)

Both components are canonical bytes. R is an executable-format identity
pinned to explicit SHA-256 by VM contract freeze, completely decoupled from
DaoJing's `default-hash-algorithm`. A receiver verifies R over the
received image before trusting it, as B1's wire rule requires for H; the
descriptor hash and contract version are inside R, so descriptor
agreement is that same check. It then validates, runs the receiver
closure check over `:load-free` operands exactly as D11 and D15 define it
for stack images, and only then executes. No relation to a stack H is
embedded or checked.

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
    :call         [op rd fn-reg arg-regs tail? live]
    :branch-false [op cond-reg target]
    :jump         [op target]
    :return       [op value-reg]
    :halt         [op value-reg]
    :store-get    [op rd key]
    :store-put    [op rd key value]
    :gensym       [op rd prefix]
    :stream-make  [op rd capacity]
    :stream-put   [op rd stream-reg value-reg live]
    :stream-cursor [op rd stream-reg]
    :stream-next  [op rd cursor-reg live]
    :stream-close [op rd stream-reg]
    :ffi-call     [op rd ffi-op arg-regs live]
    :current-continuation [op rd live]
    :park         [op rd live]
    :resume       [op parked-id value-reg]
    :define       [op rd name rs]

The R2 descriptor slots and operand kinds are exactly:

    :store-get
      [[:rd :reg] [:key :data]]
    :store-put
      [[:rd :reg] [:key :data] [:value :data]]
    :gensym
      [[:rd :reg] [:prefix :str]]
    :stream-make
      [[:rd :reg] [:capacity :uint]]
    :stream-put
      [[:rd :reg] [:stream-reg :reg] [:value-reg :reg] [:live :data]]
    :stream-cursor
      [[:rd :reg] [:stream-reg :reg]]
    :stream-next
      [[:rd :reg] [:cursor-reg :reg] [:live :data]]
    :stream-close
      [[:rd :reg] [:stream-reg :reg]]
    :ffi-call
      [[:rd :reg] [:ffi-op :kw] [:arg-regs :regs] [:live :data]]
    :current-continuation
      [[:rd :reg] [:live :data]]
    :park
      [[:rd :reg] [:live :data]]
    :resume
      [[:parked-id :kw] [:value-reg :reg]]
    :define
      [[:rd :reg] [:name :sym] [:rs :reg]]

Each short slot name above is published under the
`:yin.debruijn.register/*` namespace. R2 adds `:str` and `:kw` operand-kind
checks to the existing `:reg`, `:regs`, `:uint`, `:sym`, `:data`, and
`:bool` vocabulary. `:data` still passes through the exact scalar encoder;
it is not an unchecked host-value escape.

The named AST permits an omitted gensym prefix only before defaults are
applied. Resolution supplies the existing default `"id"`, so the canonical
instruction always has one string prefix and never has a second arity.

There is no `:push`: operands are named by register, so the stack path's
push-before-each-operand convention never arises. `:call` differs most from
its stack form: instead of an argc over an operand stack it names an
explicit function register, an ordered argument-register vector, a
destination register, the tail flag copied from `:yin/tail?`, and the live
set of section 4.5. `:branch-false`, `:return`, and `:halt` name registers
instead of reading an operand stack. Constants, lexical loads, free loads,
closures, jumps, and store operations retain their semantic operands while
gaining explicit destinations where needed.

These are the canonical names. `:stream-open`, `:stream-take`, and
`:stream-write` are not aliases in the image. Stream and FFI instructions
name their input registers explicitly. Every value-producing instruction
names `rd`. `:store-put` preserves the named operation's exact scalar value
and writes that value to `rd`. `:resume` is the exception: it transfers
control to an existing parked continuation and never returns to its own
successor, so it has no destination register.

`[:define rd name rs]` is the definition transition (Rule R: `yin/def`
is syntax, never a name). The lowerer evaluates the value operand into
`rs` and emits `:define` instead of a call; the kernel writes register
`rs` under the literal `name` through `engine/store-put` and into `rd`,
and never resolves the definition operator. Its use set is `{rs}` and
its def set `{rd}`; it cannot suspend, so it carries no live set and is
not a boundary opcode. The lift maps it back to the definition
application. The validator's structural `reserved-rule` refuses a
`:load-free`, `:define`, `:store-get`, or `:store-put` naming `yin/def`
(`:reserved-name`), so the old `[:load-free rd yin/def]` call shape
never loads, and `resolve-var` refuses the name before env or store.

Rule R moved the register contract to `contract-version` 4, the "r2"
contract (`yin.vm/register-contract`). `load-image [vm segment contract]`
and `create-vm` (`:contract` in its options) require that stamp and
refuse `:contract-missing` or `:contract-mismatch` before validation;
`create-vm` also refuses a `:free-env`, `:store`, or `:primitives` that
binds `yin/def`. Every "r1" image is refused by stamp, with no
migration.

Effect descriptors remain ordinary data passed to `yin.vm.engine`; no
instruction contains a callback, timer, handle, or scheduler. The `live`
operands are the section 4.5 continuation boundary. They are present only
where an instruction can suspend or reify the continuation. A normal
`:call` already has one because a dynamically resolved primitive may return
an effect descriptor.

### 4.5 Live sets at continuation boundaries

A non-tail `:call`, a blocking stream operation, an FFI call, `:park`, and
`:current-continuation` can save a register body's state. Some registers
are already dead at each boundary. A saved continuation carries only live
registers, never dead ones: retaining the whole file would retain dead
values in long-running stream pipelines and actor loops as unaccounted
state. Liveness is computed at lowering time and recorded in the image,
where it is visible, hashed, and verified.

The in-band `live` operand is the set of registers of the current body
whose values are read after the operation completes before being written
again. It excludes `rd`, which completion writes. It is inside R's
preimage because the kernel discards everything else; a wrong live set
changes observable results. A side table would be outside wire
verification. The named datoms remain the authority, and every receiver
recomputes `live` from the validated image before execution.

Register numbering: `live` names physical registers by the same indices
`rd`, `fn-reg`, and `arg-regs` already use, whatever the body's numbering
of its local and temporary banks is. It never introduces a second index
space. Frames captured for `:load-bound` and closures are not registers
and are outside the live set; they are carried by the return frame as B3
carries them.

The computation is one pure function of the instruction vector and the
body ranges, `body-liveness`, exported by the register code namespace and
used by both the lowerer, to fill the operand, and the validator, to check
it. Per body:

    use/def, from the section 4.4 table:
      :const rd _              def {rd}
      :load-bound rd _ _       def {rd}
      :load-free rd _          def {rd}
      :closure rd _ _          def {rd}
      :store-get rd _          def {rd}
      :store-put rd _ _        def {rd}
      :gensym rd _             def {rd}
      :stream-make rd _        def {rd}
      :stream-put rd s v _     use {s v}           def {rd}
      :stream-cursor rd s      use {s}             def {rd}
      :stream-next rd c _      use {c}             def {rd}
      :stream-close rd s       use {s}             def {rd}
      :ffi-call rd _ args _    use args            def {rd}
      :current-continuation rd _                   def {rd}
      :park rd _                                  def {rd}
      :resume _ v               use {v}
      :move rd rs              use {rs}            def {rd}
      :call rd f args tail? _  use {f} + args      def {rd} unless tail?
      :branch-false c _        use {c}
      :jump _                  none
      :return r                use {r}
      :halt r                  use {r}

    successors within the body:
      :jump t                  {t}
      :branch-false c t        {t, pc + 1}
      :return, :halt, :resume, tail :call   {}
      every other instruction  {pc + 1}

    live-in(p)  = use(p) + (live-out(p) - def(p))
    live-out(p) = union of live-in(s) over successors s of p

    iterate over the body's pcs in descending order until no set changes

    live(boundary at p) = live-out(p) - defs(p)

The fixpoint is unique because the transfer functions are monotone over a
finite lattice, so the result does not depend on iteration order; the
descending-pc order is fixed only so that every host does the same work.
Every set is a sorted set of register indices during computation and is
serialized as a vector of those indices in strictly ascending order. No
host map or set iteration order can reach the output: sorted-set
iteration is integer order on JVM, CLJS, and CLJD alike, and union and
difference are order-independent. A tail `:call` has no successors and
saves nothing; its `live` operand is the empty vector, always. The
lowerer's allocator is unchanged by this: it does not feed `live` from
its own free-list state, which is a forward approximation; `live` comes
from the backward pass over the emitted body, so there is exactly one
definition of liveness in the format.

Encoding: `live` is a `:data` operand, a vector of longs, and goes through
the existing scalar encoder as a `:vector` of `:long` with no new framing.
The ascending, duplicate-free rule is what makes the encoding canonical:
two equal live sets can never produce different bytes.

The validator applies four rules to every opcode with a `live` slot, after
the existing structural and register-bounds rules and using the defect
shape `{:rule r :pc p}`:

1. `:live-shape`: `live` is a vector of nonnegative integers in strictly
   ascending order (so it is a set and it is canonical).
2. `:live-bounds`: every index is below the body's declared register
   count, the register-bounds rule extended to this operand.
3. `:live-tail`: when a `:call` is tail, `live` is `[]`.
4. `:live-exact`: `live` equals `body-liveness`'s own answer for that pc,
   reported with `:expected` and `:actual`. A received image's live sets
   are not trusted; they are recomputed on the receiving host before
   execution, the same discipline as B1's scope check.

R1 introduced `live` on `:call` and contract version 2. R2 extends the same
analysis to suspension and capture opcodes and changes the descriptor to
contract version 3. Every R changes because the descriptor hash is in its
preimage, including pure images. Version 2 has no compatibility path.

R1 implemented version 2, the `:call` live slot, `body-liveness`, the four
rules, and the required golden fixtures. R2 changes that same descriptor
and analysis in one phase; it does not introduce a second liveness pass.

### 4.6 R2 lowering rules

R2 completes every node type R1 deferred. It extends the existing
target-register-passing walk; it does not introduce an effect AST or a
second allocator.

    resolved node                 lowering
    :vm/store-get                 [:store-get rd key]
    :vm/store-put                 [:store-put rd key value]
    :vm/gensym                    [:gensym rd prefix]
    :stream/make                  [:stream-make rd buffer]
    :stream/put                   lower target, then value;
                                  [:stream-put rd rt rv live]
    :stream/cursor                lower source;
                                  [:stream-cursor rd rs]
    :stream/next                  lower source;
                                  [:stream-next rd rc live]
    :stream/close                 lower source;
                                  [:stream-close rd rs]
    :dao.stream.apply/call        lower operands left to right;
                                  [:ffi-call rd op arg-regs live]
    :vm/current-continuation      [:current-continuation rd live]
    :vm/park                      [:park rd live]
    :vm/resume                    lower value;
                                  [:resume parked-id rv]

`key`, `value`, `prefix`, `buffer`, `op`, and `parked-id` are exact scalar
facts from the resolved record. They are never looked up in the register
file. Child expressions are assigned temporary registers in fixed child
order. After the parent instruction is emitted, their temporaries are
freed in that same order. `rd` is the target supplied by the parent.

`:resume` ignores that supplied target because successful resume abandons
the current control path. Code emitted after it may be structurally present
but is unreachable under the successor rule. The validator treats
`:resume` as a terminator. Missing parked ids and format mismatches are
runtime outcomes, not lowering-time guesses.

The `live` operands are filled in one post-pass after every body and pc is
final, using the one exported `body-liveness` definition. The pass covers
`:call`, `:stream-put`, `:stream-next`, `:ffi-call`,
`:current-continuation`, and `:park`. No allocator free-list state enters
the result. Effect lowering is therefore alpha-invariant and byte-stable
for the same reasons as R1: fixed traversal, lowest-register allocation,
sorted live vectors, exact scalar bytes, and no host iteration order.

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
rules, and B0 normalization. Register continuations and stack
continuations are not interchangeable; section 5.1 states the rule.

### 5.1 Continuation transport across VM models

A parked continuation resumes only under the VM model, and the image
identity, that parked it. This is a decided rule for B4 and R4, not a
gap. Every parked record and reified continuation of either VM carries
its model and image identity in its register payload, `{:format
:yin.debruijn.code :hash H}` or `{:format :yin.debruijn.register :hash R}`
beside the fields the engine seam already lists, and a restore whose VM or
image does not match refuses with a qualified `:continuation-format`
outcome instead of interpreting a foreign payload. The engine never reads
those keys; they are register payload under the section 3 rule of
`yin.vm.engine.md`.

Direct cross-model resume is not possible, and a pairwise lift is the
wrong shape. A register park site and a stack park site for the same
program point have no pc correspondence, a register file is not an
operand stack, and each is a positional artifact of one lowering.
Translating one bytecode continuation into another is decompilation, and
with a third model it becomes six lifts. This project already rules on
the shape: `docs/agents/architecture.md`, "AGENTS", Continuation
Migration says the AST datoms are the canonical payload, bytecode is a
projection for one execution model, and a destination projects into
whatever model it prefers. The cross-model form of a continuation is
therefore a continuation over the source, not over either image: pending
frames named by resolved-tuple occurrence, plus the values those frames
hold. Both images carry provenance to source occurrences through their pc
side tables, so each VM can supply a lift-out from its own continuation
to that form and a lift-in from it, and no VM needs to know another
exists. That form is the universal continuation format the stack design
names as proposed and deferred. It is a shared concern of every VM, not
either de Bruijn VM's, and belongs in its own design document; this
document does not specify it.

Two consequences hold now. First, the section 4.5 live sets lose nothing
observable at that boundary: a register continuation carries only live
registers, and a dead register is by definition never read again, so a
lift-out has every value any pending frame can observe. What does not
survive a round trip is pc identity, temporary ids, and dead values, all
of which the stack design's section 1 already declines to promise.
Second, until that format exists, cross-host transport of a parked
continuation is same-model only: a register continuation travels to a
host with a register kernel, a stack continuation to a host with the
stack VM, and each such transfer also needs the image by R or H through
the B6 linker. This rule is deliberate; the alternative was a pair
of lossy lifts between two positional formats that the third VM would
have made obsolete.

### 5.2 R2 effect and suspension contract

R2 defines the data contract R4 will interpret. It does not execute an
instruction. Pure constructors and validators live in
`yin.vm.debruijn-register-effects`; R4 must reuse them rather than invent
another payload shape.

Its public, pure surface is:

    effect-descriptor  instruction registers -> effect | nil
    continuation-payload runtime instruction -> payload
    continuation-defect payload -> defect | nil
    wait-entry-defect entry -> defect | nil

`runtime` is an explicit map containing the validated image, R, pc,
lexical frames, register vector, and return frames. These functions read no
host clock, stream, namespace registry, or mutable global.

#### 5.2.1 Effect dispatch

The register kernel will interpret the R2 instructions as follows:

    instruction       engine action or value action
    :store-get         read state store; write rd
    :store-put         write exact scalar to store and rd
    :gensym            engine/gensym; write rd
    :stream-make       {:effect :stream/make, :capacity capacity}
    :stream-put        {:effect :stream/put,
                        :stream regs[stream-reg],
                        :val regs[value-reg]}
    :stream-cursor     {:effect :stream/cursor,
                        :stream regs[stream-reg]}
    :stream-next       {:effect :stream/next,
                        :cursor regs[cursor-reg]}
    :stream-close      {:effect :stream/close,
                        :stream regs[stream-reg]}
    :ffi-call          park-and-call with ffi-op and arg-regs
    :current-continuation  produce a reified continuation value
    :park              engine/park-continuation
    :resume            engine/resume-continuation

`:stream-make`, `:stream-cursor`, and `:stream-close` are nonblocking under
the engine contract. `:stream-put` parks only on `:dao.stream/full`, and
`:stream-next` parks only on `:dao.stream/blocked`. Every other declared
stream outcome is returned or raised exactly as the shared engine defines;
R2 adds no interpretation.

A normal `:call` whose resolved host primitive returns a
`module/effect?` value sends that value through `engine/handle-effect`.
The call's existing `rd` and `live` operands are its suspension contract.
A plain primitive result is written directly to `rd`. For a tail primitive
effect, the payload uses `:resume-mode :return-result`; it preserves no
current-body registers and delivers the resumed value through the normal
return transition. A non-tail primitive effect uses
`:resume-mode :write-result` and its call destination.

`:ffi-call` first requires the call pair, before parking or minting an id.
Its arguments are read from `arg-regs` in vector order. It creates the same
request envelope as the stack VM. An immediate append waits on call-out; a
full append parks the identical request as a writer. Response correlation
and error envelopes are interpreted only by `ffi/call-result`. There is no
error register and no raw exception in serialized continuation data.

#### 5.2.2 Sparse continuation representation

The runtime register file is a vector indexed by the physical register
numbers in the image. A saved continuation contains only live values. Its
canonical payload is:

    {:segment <validated register image>
     :site-pc <suspending instruction pc>
     :pc <next pc>
     :frames <outermost-first lexical frames>
     :regs [[register-index value] ...]
     :live [register-index ...]
     :continuation [<return frame> ...]
     :dest <register-index or nil>
     :resume-mode :write-result | :return-result
     :format :yin.debruijn.register
     :hash <R>}

`:live` is copied from the instruction's verified operand. `:regs` is a
vector in the same strictly ascending index order and has exactly the same
indices. A saved value is read from the running register file at snapshot
time. Maps are not used because their iteration order is not an encoding
contract. `:dest` is `rd` for `:write-result` and nil for
`:return-result`. The destination is not in `live`; restoration writes it.

Each non-tail register call pushes a return frame of the same sparse form:

    {:segment <validated register image>
     :hash <R>
     :site-pc <call pc>
     :return-pc <pc after call>
     :frames <caller lexical frames>
     :regs [[register-index value] ...]
     :live [register-index ...]
     :dest <call rd>}

The outer payload's `:continuation` is a vector of these frames. An image
and hash on each frame permit later cross-image calls without guessing
which register layout owns a frame. R4 may share persistent image values in
memory; their presence in the data shape does not require byte copying.

`:current-continuation` constructs
`{:type :reified-continuation ...payload}` for the point after itself,
then writes that value to its own `rd`. If resumed later, the supplied
resume value is written to the same `rd`. `:park` supplies the same payload
to `engine/park-continuation`; the engine adds
`:type :parked-continuation` and `:id`, writes that record to the VM value,
and halts. Successful resume writes the supplied value to `rd`.

Discarding dead registers is required, not an optimization option. Stream
pipelines and actor loops can park indefinitely; retaining dead values at
every boundary would retain obsolete messages, closures, and collections.
The verified live set proves those values cannot be observed again. Lexical
frames are not registers and are retained until a separate frame-liveness
design exists.

The pure payload validator checks `:format`, recomputes R from `:segment`,
checks image validity, site pc and continuation pc ownership, destination
bounds, ascending `live`, exact `regs`/`live` index agreement, return-frame
shape, and that the live vector equals the image operand at `:site-pc`. It
refuses with qualified rules including
`:continuation-format`, `:continuation-hash`, `:continuation-pc`,
`:continuation-live`, `:continuation-registers`, and
`:continuation-destination`.

#### 5.2.3 Engine restoration and wait entries

The future register restore has the shared signature:

    register-restore : base entry val -> state

`base` is authoritative for engine bookkeeping. The engine has already
popped the ready entry, merged `:store-updates`, and cleared the blocked and
halted flags. Restore never merges the entry wholesale. It reads only the
validated register payload and the documented FFI keys.

For `:resume-mode :write-result`, restore creates a register vector of the
declared body size, fills the saved pairs, writes `val` to `dest`, restores
the image, pc, frames, and return frames, and continues. Dead slots are nil
and may not be read because the image was liveness-validated. For
`:return-result`, restore applies the normal return transition to `val`.

Terminal stream outcomes are handled by `engine/resume-from-run-queue`
before restore and fail as their immediate forms do. An FFI response is
unwrapped by `ffi/call-result`; its qualified error is raised at that point.
The correlated call id is removed from `base :parked` only on the response
reader path, never when a full request writer is re-parked.
An arbitrary value passed to `:resume`, including an error-shaped value, is
ordinary program data unless it is an explicit outcome envelope governed
by an existing contract. A host exception or other non-plain value is not a
resume value: restore refuses it with `:resume-value` before changing the
register file. Raw host exceptions never enter a serialized continuation.

The wait-set forms are:

    stream writer  payload + {:reason :put, :stream-id id,
                              :datom value}
    stream reader  payload + {:reason :next, :stream-id id,
                              :cursor-ref ref}
    FFI writer     payload + {:request-sent true, :call-id id,
                              :op ffi-op, :reason :put,
                              :stream-id :yin.vm/call-in,
                              :datom request}
    FFI reader     payload + {:call-id id, :reason :next,
                              :stream-id :yin.vm/call-out,
                              :cursor-ref <call-out cursor ref>}

The actual reserved stream ids are the constants in `yin.vm`, not copied
literals. `:yin/blocked` is the VM state's blocked value. `stream-blocked`
and `ffi-wait` name the two categories above; they are not new `:type`
tags. Explicit `:park` records alone use `:type :parked-continuation`.

A newly parked entry contains no prior wake disposition. In particular,
conversion of a woken FFI writer into a response reader removes
`:value`, `:status`, `:cursor`, `:store-updates`, `:stream`, `:datom`,
`:type`, `:id`, `:request-sent`, and `:op` before adding the response
reader keys. `ffi/response-wait-entry` must satisfy this rule before R4
uses it. This closes the stale-wake defect recorded against the earlier
engine design; a store transition from the writer wake must never be
applied again when the response wakes.

R4 uses `engine/scheduler-round` and the three-argument restore directly.
It supplies instruction-site park-entry builders containing only the pure
payload plus engine transport keys. No restore closure, stream handle,
timer, or callback is stored. Host cadence remains outside the VM.

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
    Existing source: src/cljc/yin/vm/debruijn_register_code.cljc
    New: src/cljc/yin/vm/debruijn_register_effects.cljc
    New: test/yin/vm/debruijn_register_effects_test.cljc
    Existing edits: test/yin/vm/debruijn_register_compile_test.cljc
    Must not change: dao.stream, lease, waitset, engine effect rules,
      B0-B4 semantics, stack H, named or stack instruction dimensions

Implement sections 4.4 through 4.6 and 5.2. Extend the descriptor and
validator with the R2 opcodes, extend `body-liveness` to every continuation
boundary, lower every node R1 deferred, and add the pure effect, snapshot,
and wait-entry constructors and validators. Contract version 3 retires
version 2. Re-pin the descriptor hash, every golden register vector, and
every R fixture because the descriptor hash is part of every R.

R2 has no execution kernel dependency. Its completion criteria are:

1. Every R2 node lowers to the exact tuple in section 4.6. Child evaluation
   order, register allocation, side-table provenance, and effect argument
   order match the named and stack paths.
2. The descriptor declares every operand and kind. The validator refuses
   wrong arity, wrong scalar kind, negative or out-of-body registers,
   malformed argument vectors, invalid capacities, invalid FFI operations,
   cross-body targets, and a nonterminal `:resume`, with named diagnostics.
3. `body-liveness` has complete use/def and successor coverage for every
   mnemonic. All live operands are ascending, bounded, exact, and hashed.
   Hand-built omissions, additions, duplicates, and dead-register captures
   are refused.
4. The pure effect constructor produces the same normalized descriptors as
   the stack B4 path for stream make, put, cursor, next, close, and dynamic
   primitive effects. Store and gensym value/state transitions match too.
5. Sparse snapshots contain exactly the verified live register pairs,
   destination, image identity, lexical frames, and return frames. Tests
   cover current-continuation, explicit park/resume, non-tail and tail
   primitive effects, immediate stream success, blocked put and next, and
   both FFI wait stages.
6. Wait-entry validation refuses missing resource ids, a reason/shape
   mismatch, an out-of-bounds destination, a foreign format or R, an
   ill-formed live set, every stale wake key named in section 5.2.3, and a
   raw host exception or other non-plain resume value.
7. FFI tests require the call pair before parking, preserve request bytes
   across a full writer retry, correlate the response id, and classify a
   response error without inventing an error register.
8. Repeated lowering is byte-identical. Effect-bearing golden images and R
   values agree on JVM, CLJS, and CLJD for the common scalar domain. Host-
   unsupported scalar classes refuse before hashing under the existing
   qualified rule.
9. The section 5.2 constructors and validators are pure data functions.
   EDN round trips preserve snapshots and wait entries. No fixture stores a
   function, stream handle, callback, timer, or namespace-global state.
10. R2 tests do not instantiate a register VM. R4 must later prove that its
    interpreter consumes these constructors and produces the same values,
    errors, stores, blocked states, and stream outcomes as stack B4 under
    the B0 normalizer.

Verification runs the focused R0-R2 tests, full JVM, full Node/CLJS, full
ClojureDart, kondo, cljstyle, and an 80-column design check.

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

The phase order runs on two parallel tracks after R1: on the register
track, R2's descriptor and liveness extension and R4's pure-program tier,
then R4's effects tier once B4 and R2 exist, then R3 against the real
kernel; on the linker track, Phase B6 provides both stack and register
linking over dao.stream, depending on R1 and B1/B2 only and free to land
before R2 or R4.

### R5: linker integration over dao.stream

    Fulfilled by: Phase B6 (src/cljc/yin/vm/linker.cljc,
    test/yin/vm/linker_test.cljc)
    Specification: docs/design/yin.vm.debruijn.linker.md
    Must not change: stack H, dao.stream, dao.jing, dao.jing.dht

Per the owner's directive ("B6 should be used by both the stack and register
vm for linking code over dao.stream"), the register format linking capabilities
are fulfilled directly within the unified B6 linker phase. Phase B6 delivers
`src/cljc/yin/vm/linker.cljc`, providing the parameterized `fetch`
function, the register format record (`:yin.debruijn.register`), the R index,
and same-root pairing datoms (`[root :yin.debruijn.register/hash R]`) with
both trusted and verifying fallback paths. Full specification, file box,
and completion criteria are defined in `docs/design/yin.vm.debruijn.linker.md`.

## 7. Non-goals and protected surfaces

This design does not alter any B0-B7 phase's semantics, the stack VM, named
datoms, the dormant projection, or the AST-walker. It does not change B1's
H, its scalar bytes, its validator, or its wire protocol. It does not add
equality saturation, a JIT, lambda lifting, ANF, global distribution, or a
new continuation interchange format. B2's adoption of the resolver is the
decided shared upstream recorded in section 2.2.

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
effect ordering, register continuation shape, drift between the stack and
register lowerers, and the permanent second-evaluator maintenance surface
accepted in DECIDED 1.

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
9. Live sets are in-band on every continuation boundary, as strictly
   ascending vectors computed by one exported backward-dataflow function,
   inside R's preimage, and recomputed by every receiver. R2 is register
   contract version 3. A saved continuation carries only live registers.
10. R2 defines effect opcodes, sparse continuation payloads, wait-entry
    shapes, and validation as pure data before R4 interprets them. The
    shared engine remains the sole owner of scheduling and wake disposition.

DEFERRED:

- Spill representation and register-file limits, if a target requires them.
- Register continuation lifting and cross-model park/resume transport.
- The B7 name-environment ledger and provenance that the B6 same-root
  pairing's trust rests on; R4 and B6 are both decided phases.

## 9. End condition

The lowerer-only end condition is a validated, deterministic register image
whose R is reproducible on all three hosts, whose address law with the stack
image holds over the parity corpus, and whose named-VM normalized fixtures
agree. The full register-VM end condition adds a validator-approved kernel
with both R4 tiers complete, the R3 report published for the owner to read,
and cross-stream R fetch and verification via the B6 linker. The
project does not stop at R2: R3's numbers inform, they do not decide.
