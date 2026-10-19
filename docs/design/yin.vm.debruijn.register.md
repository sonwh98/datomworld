# yin.vm de Bruijn register VM

Status: design, revised; not implemented

> **Amendment 2026-10-10: A is the only code identity.** Owner ruling,
> verbatim: *"yes, A is the only code identity"* (A = `ucf/code-address`
> of the semantic VM's canonical vector, UCF §7.3.2). **This amendment
> takes precedence over every statement in this document**, however
> often repeated, that makes R (or H) a *name*: a request key, fetch
> key, index key, publication output, pin, dependency identity,
> captured-code identity, cache authority, or executable admission
> identity. R remains a *value*: the integrity checksum of the register
> image, used to verify that held or received native bytes are the
> lowering of A under this format's contract. Named instances, so none
> survives by omission: the status sentence and §1 "neither is derived
> from the other" (the register image is derived from A; R and H remain
> independent sibling lowerings of A, neither derived from the other);
> §1 "independently addressable by its own R" and the independently
> addressable H/R of line 51; §1.1 "R is the only identity used to
> fetch, verify, cache, or execute register bytes", "a linker request
> must name both the format and R", and "a receiver verifies a fetched
> register image with R alone" (a receiver verifies A and the lowering
> relationship before execution, per the admission procedure in the
> stack design's amendment); invariant 3 (line 106) and DECIDED 2 (line
> 1123) on peer executable-format identities (the checksum-equality laws
> are retained); §2's topology diagram and §2.1's D9/D12 bullets (the
> input to `lower-register` becomes A, with name resolution a shared
> function both lowerers call whose output is a stage value with no
> identity); §3 (line 319) R as executable-format identity with
> verification-then-execution without A; §5.1's captured image identity
> `{:format … :hash H/R}` and its fetch-by-R/H (A identifies captured
> code; native same-model restore constraints stand until the new
> continuation design replaces them); R5 and §9's R index, same-root
> pairing datoms, and the cross-stream fetch-by-R end condition
> (replaced by A; the `(R, H)` pairing becomes both being functions of
> A). Invariant I is unchanged in substance; the linker contract
> (`yin.vm.linker.md`) carries a coordinated amendment of the same date. The principle *"one truth, many interpretations"* quoted
> in §1 **is not violated** (owner, 2026-10-10): the Universal AST is the
> one truth and the three VMs remain its interpretations. Only §1's
> narrower topology sentence, "no projection is compiled from another"
> as applied at the de Bruijn layer, is superseded, since the register
> image is now derived from A. R1's lowering walk changes input and is not a plug-in allocator over a
> vector (Architect, `collab/1791510000000-architect-semantic-register-vm.gpt-6.1-sol.findings.md`
> finding 6).
>
> A second ruling of the same date makes the canonical semantic vector
> register-shaped with virtual registers (*"rebuilding the semantic-vm as
> register shaped ... is the right way to go"*); its design
> (`yin.vm.semantic-register-vm.md`, forthcoming; landed and renamed
> `yin.vm.semantic.md` at the 2026-10-15 cutover) will restate §4's
> input and lowering contract. Not applied here.
>
> **Amendment 2026-10-15 (phase 8, 8g).** This kernel survived the
> cutover: `debruijn/register.cljc` is a **backend** of the canonical
> semantic kernel, executing the lowering `A → R` over the documented
> fragment (continuation design §4.6), checked by the cross-engine laws
> on that fragment. Refusal of the unsupported remainder before attach
> remains the requirement; §4.6 lists the four implemented checks and the
> harness-only exclusions, not a general fragment gate.
> The old lowerers that produced its input are gone; `derive` lowers
> from A. The fragment is not widened.

This document specifies a register execution path that is a peer of the
committed de Bruijn stack path. Both are projections of the same de Bruijn
encoding of the named semantic tuples; neither is derived from the other
*(superseded 2026-10-10; see the amendment above)*.
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
`{:format :yin.debruijn.register :hash R}` *(superseded 2026-10-10: a
request names A and the format; R verifies the held image against
`register-lower(A)`; see the amendment at the top)*.

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

> **Amendment 2026-10-15 (phase 8, 8g).** The derivation prose of this
> section and §2.1 is historical: the stages it names —
> `(resolve …)`, `(lower-stack …)`, `(lower-register …)` over named
> datoms — are the deleted old lowerers (`yin.vm.debruijn-resolve`,
> `debruijn-linearize`, `debruijn-register-compile`, removed at 8e). The
> living lowering is `yin.vm.semantic.derive`: A (the semantic vector)
> is resolved per `:var` by the surviving `yin.vm.debruijn/resolve-name`
> and lowered to R by one walk (the register design of
> `yin.vm.semantic.md` §8.2, derivations design §2). The resolved tuples
> remain what §2.1 says — a stage value with no identity — inside
> `derive`. The topology below is the coexistence-era record.

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

    R = blake3(register-descriptor-hash || canonical-register-vector)

Both components are canonical bytes. R is an executable-format identity
pinned to explicit BLAKE3 (SHA-256 until the phase-8 cutover, 2026-10-15;
the descriptor row and every R moved then, once, without a version bump)
by VM contract freeze, completely decoupled from
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
    Specification: docs/design/yin.vm.linker.md
    Must not change: stack H, dao.stream, dao.jing, dao.jing.dht

Per the owner's directive ("B6 should be used by both the stack and register
vm for linking code over dao.stream"), the register format linking capabilities
are fulfilled directly within the unified B6 linker phase. Phase B6 delivers
`src/cljc/yin/vm/linker.cljc`, providing the parameterized `fetch`
function, the register format record (`:yin.debruijn.register`), the R index,
and same-root pairing datoms (`[root :yin.debruijn.register/hash R]`) with
both trusted and verifying fallback paths. Full specification, file box,
and completion criteria are defined in `docs/design/yin.vm.linker.md`.

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


---

## 10. The wasm lane: register bytecode lowered to wasm (2026-10-19, design only)

Status: design, owner-authorized planning; not implemented. Sections 1–9 above
(as amended on 2026-10-10 and 2026-10-15) remain authoritative for the register
image contract and the landed R4 register kernel.

> **Amendment 2026-10-19 (design only): the register bytecode's wasm
> execution lane, and K0/W0 register conformance fixtures.** The register VM
> is not itself a wasm instance; register bytecode is lowered to wasm
> bytecode (yang.wasm), and this section holds the register dimension's
> facts for that lane.
>
> Consensus provenance (2026-10-18): gpt-6-astra, author; gemini-4-argon,
> reviewer; five review rounds, APPROVE on r5. Author thread:
> `01a14f30-ca56-7530-bb93-3a870eca7a7b`; reviewer thread:
> `f365ef1d-caa2-4379-8e96-51e64ca36af8`. It does not report an implementation
> or runtime acceptance result.
>
> Owner boundary clarification and decomposition, 2026-10-19: the Rust engine
> executes inside wasm; eight-field synchronization is internal. Doctrine and
> historical supersessions live in [datom.world.abi.md](./datom.world.abi.md);
> the adapter implementation lives in [dao.stream.wasm.md](./dao.stream.wasm.md).
> Fixtures and register transition semantics survive unchanged.
> Wasmer's full-suite gate moves from W4 to W5 under the revised schedule.
>
> Earlier decomposition/fold provenance remains: engine method lives in
> engine §E; the stream implementation lives in dao.stream.wasm.md; this section holds register state, fixtures and gates. The dated
> correction of historical `:store-updates` remains: ready entries merge
> `:resource-updates` into private `:resources`, refusing non-keyword keys with
> `{:rule :resource-update-key}`.

Authorities and companion specifications for §10:
- [datom.world.md](./datom.world.md): foundational axioms and invariants.
- [yin.vm.semantic.md](./yin.vm.semantic.md): A identity, semantic execution, and A→R derivation.
- [yin.vm.linker.md](./yin.vm.linker.md): admission, offset tables, and module transport.
- [yin.vm.engine.md](./yin.vm.engine.md): scheduler, effects, custody, and (`§E.1–§E.5`) the in-instance engine and reference to the wasm stream implementation, and K0/W0 differential conformance method.
- [yin.vm.rust.md](./yin.vm.rust.md): shared Rust crates, `Value` ownership, transition reuse, and LLVM sibling relationship.
- [yang.wasm.md](./yang.wasm.md): lowering register images to WebAssembly modules, the 10-region linear-memory layout, and all 23 opcode lowerings linked to the in-instance engine.

### 10.1 Register machine state and the register instance of the synchronization barrier

This is the register instance (contract version 4 / "r2", 23 opcodes) of the
in-instance engine in [yin.vm.engine.md](./yin.vm.engine.md) §E.1–§E.5.
[yang.wasm.md](./yang.wasm.md) compiles admitted register images and links the
same Rust engine into wasm under Node/Wasmtime/Wasmer. The CLJS kernel is the
oracle. Arena/Value facade and both repository codecs live inside the instance;
only dao.stream adapters ([dao.stream.wasm.md](./dao.stream.wasm.md)) cross into the composition. The single-port rule
and Rust reconciliations remain §E.4.

#### 10.1.1 The eight materialized register fields and the synchronization barrier instance

Each register-wasm task owns one imported, non-shared wasm32 memory arena with
the ten-region layout (`Task header`, `Body directory`, `Active register shadow`,
`Lexical frames`, `Return stack`, `Sparse snapshots`, `Continuation record`,
`Value descriptors`, `Engine state roots`, `Exit mailbox`), 64-bit wire `pc`
fields, and program-store `ValueRef` rules specified in
[`yang.wasm.md`](./yang.wasm.md) §2.2.

As the **register instance** of the engine extension's machine-state-as-data
boundary pattern ([`yin.vm.engine.md`](./yin.vm.engine.md) §E.1), the register
machine's declared execution state consists of **eight materialized fields** on
the authoritative in-instance state record (the oracle uses a VM map):

    :pc  :registers  :frames  :continuation  :store-of  :value  :halted?  :blocked?

Linear memory is a private execution cache over these eight fields plus the
current register code layout (`:segment`, `:hash`, `:images`) and engine state.
The register instance of the four-step synchronization protocol
([`yin.vm.engine.md`](./yin.vm.engine.md) §E.1) is:

1. **Engine-to-body entry:** `step`, `run`, and `register-restore` accept the supplied named-field snapshot as authoritative. Import `:pc`, `:registers`, `:frames`, `:continuation`, `:store-of`, `:value`, `:halted?`, and `:blocked?`, plus the current code layout and engine state. W1 imports unconditionally. A later cache may skip only after proving all relevant keys agree with its last materialized snapshot; a retained private token alone is not proof. In particular `(assoc parked :pc 2 :halted? false)` must execute the requested state. Import a copied or privately owned active register shadow so running an old or externally modified map never mutates another returned map.
2. **Body-to-engine:** before every state-consuming `engine/*` call, including `handle-effect`, pinning, collection, scheduler, park/resume and module work, materialize all eight register execution fields (`:pc`, `:registers`, `:frames`, `:continuation`, `:store-of`, `:value`, `:halted?`, `:blocked?`) onto the VM record. Materialize them also at every public `step`, `run`, restore or inspection return. Preserve equivalent `module/gc-roots` dispatch over the current record. Dense shadow synchronization without state-record synchronization is insufficient.
3. **Engine completion:** the returned engine state is the new authority, including its eight execution fields and every extension key. Apply only the current opcode's specified post-helper changes ([`yang.wasm.md`](./yang.wasm.md) §3.3–§3.4) to that returned state, then reimport it into memory. Never overlay a pre-helper header onto it. In particular `handle-effect` sets `:value` even for nonblocking effects; preserve that value, resource/heap/GC updates, stores, counters, queues and custody fields. A plain non-tail primitive call, by contrast, only updates `:pc`/`rd` in the kernel and does not automatically set `:value`.
4. **Seam `pc`:** before an engine helper, the map contains the instruction's absolute site `pc`, unless the source transition explicitly advances it first (such as parking in the FFI path). Capture the payload at the site; apply the opcode's `pc` update exactly once after helper completion. Mailbox state does not count as a completed guest instruction.

Because `:cell/new` may collect *inside* `handle-effect` before the caller
receives its result ([`yin.vm.engine.md`](./yin.vm.engine.md) §E.1), state-record
`:registers`, `:frames`, `:continuation`, and `:value` must already be current
before `handle-effect` is entered.

#### 10.1.2 Dense register shadow, sparse saved frames, and register GC roots

Start with a dense active register shadow (`Active register shadow` in
[`yang.wasm.md`](./yang.wasm.md) §2.2) synchronized on every register write and
before every return to the engine driver. The wasm local is the execution operand; the
shadow makes complete current state and GC roots available. **Sparse saved
frames and payloads are nevertheless mandatory:** never retain the full dense
shadow through a guest call or park. On a successful call/restore, replace the
active shadow; clear abandoned mailbox/argument roots when no longer needed. A
parked VM's observable active `:registers` vector may still contain dead slots,
as the reference register VM does; do not confuse those current-state roots with
the sparse continuation payload.

Under the arena and engine heap collection rules of
[`yin.vm.engine.md`](./yin.vm.engine.md) §E.2.3, mark active `:registers`,
`:frames`, `:value`, `:continuation`, `parked`/`wait-set`/`ready-queue` entries,
`store`, `module-stores`, engine heap and pinned/outgoing values, constants,
and pending mailbox arguments. Traverse trusted wrappers via `values/payload`
and preserve the register kernel's `module/gc-children` implementation and its
treatment of code. Persistent snapshots share immutable arena objects, never a
mutable register buffer; the first implementation may copy the active dense
`:registers` vector at every public `IVM` transition and optimize only after
snapshot-alias tests pass.

### 10.2 Sparse continuation materialization, `register-restore`, and layout transport

#### 10.2.1 Materialization and capture-time metadata

At any of the six in-band live-set opcodes (`:call`, `:stream-put`,
`:stream-next`, `:current-continuation`, `:park`, `:ffi-call`) at site `p`, the
exported boundary payload and return-frame shape are those built by
`effects/continuation-payload` as specified in [`yang.wasm.md`](./yang.wasm.md)
§3.5 (`:segment`, `:site-pc p`, `:pc (inc p)`, `:frames`, sparse `:regs` and
`:live`, `:continuation`, `:dest`, `:resume-mode`, `:format
:yin.debruijn.register`, `:hash`, `:image`, and optional `:images` /
`:store-of`). Return frames contain `{:site-pc :return-pc :frames :regs :live
:dest}` plus optional `:store-of`, with no code space.

Materialization recursively resolves `ValueRef`s and never puts numeric handle
IDs or arena references into payload values. Import performs the
inverse into immutable records. The capture-time `:segment`/`:hash`/`:images`
are validation data and must survive later `attach-image` unchanged. They can
share immutable objects in memory, but a plain-data export must contain the
actual required image data. A wasm function index, memory pointer, instance id,
or private linkage version is not a guest format identity.

#### 10.2.2 `register-restore` algorithm and failure ordering

Implement one in-instance restore with the same `(base entry val) -> state` contract
([`yin.vm.engine.md`](./yin.vm.engine.md) §2.1) and use it from both engine
resume paths:

1. Check `:format` equals `:yin.debruijn.register` and entry `:image` is in base `:images`; preserve `:rule :continuation-format`, `:reason :foreign-format`, and diagnostic context. Do **not** compare entry `:hash` to current base `:hash`.
2. Run the existing `effects/continuation-defect` against the entry, preserving deterministic first-defect results, including capture-time hash recomputation (`yin-image` R in [`yin.vm.engine.md`](./yin.vm.engine.md) §E.2.2) and per-component image/table validation.
3. Check the actual `vm/machine-data?` predicate on `val`; reject unsupported host functions/errors with `:rule :resume-value`. Do not turn an error-shaped ordinary map into an exception.
4. If `request-sent`, enqueue `ffi/response-wait-entry` using `call-id` and remain blocked with `:yin/blocked`, `halted` false ([`yin.vm.engine.md`](./yin.vm.engine.md) §4 and §E.4.4). Do not deliver writer acknowledgement into `rd`.
5. Otherwise remove `call-id` from `parked` when present and apply `ffi/call-result` to its response. For `:write-result` allocate the receiving body's register count, nil-fill, copy sparse pairs, then write `dest`; restore `pc`, `frames`, `K`, `store-of`, `value` and `halted` false. For `:return-result` first `assoc` `:pc (:pc entry)`, `:frames (:frames entry)`, `:continuation (:continuation entry)`, `:store-of (:store-of entry)`, and `:halted? false` onto `base`, leaving `:registers` equal to `(:registers base)`; then run the ordinary `return-transition` ([`yang.wasm.md`](./yang.wasm.md) §3.3). With empty captured `K` this halts at the entry's next `pc`, retains `base` registers and captured frames, and clears `store-of`. With nonempty `K`, the popped caller frame replaces `pc`/`registers`/`frames`/`store-of` as usual. Preserve `base`'s `store`, `resources`, queues, counters, `modules`, and code space.

Do not clear `blocked` universally in the low-level restore: the engine clears
it in ready-queue resumption; direct paths preserve the source's exact field
behavior. Public APIs should route through engine scheduling instead of
manufacturing a partially cleared state. Failed restoration must not commit a
mutated arena/state snapshot: stage validation and allocations before publishing
the successful transition.

The distinct internal `:call` path for a trusted reified continuation
(`engine/operator-kind` ownership first → `engine/continuation-argument` arity
second → format/image membership third → `write-back`, skipping external
`continuation-defect`/`machine-data?` gates) is specified in
[`yang.wasm.md`](./yang.wasm.md) §3.3 and §3.5.

#### 10.2.3 Cross-runtime continuation transport and layout compatibility

Same-layout native payloads with admissible values round-trip between reference
and wasm execution through conformance/inspection facilities. Inside one task,
trusted wrappers remain arena Values; there is no serialized handle service.
This grants no external portability. The actual module/linker path retains
`:yin.k/non-portable` / `:non-canonicalizable` continuation refusal in
`engine/encoder`. Only admitted canonical stream values use
[dao.stream.wasm.md](./dao.stream.wasm.md) §§1–3.

If a future authorized transport carries register continuation coordinates
image-relatively, it must translate **site-pc, pc, every return frame's
site/return pc, and recursively all closure body pcs**, attach admitted origins,
reconstruct self-consistent segment/hash/live metadata, and apply normal native
`register-restore`. Raw payloads with absolute pcs must not be fed to a
differently ordered receiving layout merely because one R is present. W0 must
gate the native transport API to a verified matching layout/prefix or return
qualified `:continuation-format`; this transport restriction does not change
same-task `register-restore` semantics. General rebasing and canonical semantic
UCF migration remain outside this backend's completion claim.

#### 10.2.4 Register wait-entry validation scope

Guest-agnostic stream, park, custody-gate, and FFI two-step rules are specified
in [`yin.vm.engine.md`](./yin.vm.engine.md) §E.4.4. For register wait entries
specifically, `effects/wait-entry-defect` is an acceptance oracle **only for its
four newly built stream/FFI forms**: `put`/`next` writers/readers and
`request-sent`/`call-id` writer/reader entries. Do not apply it universally to
the wait set: `link-request`, `link-response`, `install`, and gated `observe`
entries are outside its shape vocabulary. For these, check
`effects/continuation-defect` on the register payload plus the owning
module/engine reason-specific fields and transitions. Do not apply a newly
parked-entry oracle to stale-key-bearing ready entries either.

### 10.3 Register module attachment, `spawn-module`, and artifact cache

Per-image-row compilation, PC relocation, the four-step staged `attach-image`
transaction, combined-segment boundary payload reads, empty-base
`[[empty-R 0 0]]` offset-table rules, and the 65,536-slot preallocated function
table are specified in [yang.wasm.md](./yang.wasm.md) §5. The composition's
runtime lifecycle installs derived body functions only at materialized boundaries;
the **in-instance engine** owns admission/attachment and publishes its layout.
Missing artifacts use its own register interpreter. This is runtime linkage,
not an imported engine service. `reset` preserves offset rows; `load-image`
replaces code space and resets execution, so old membership checks can fail.

Under the `spawn-module` isolation rules of
[`yin.vm.engine.md`](./yin.vm.engine.md) §E.4.4, register `spawn-module` creates
a fresh task/memory/arena/owner/counter/resources context with the composition
options copied by the register kernel; it does not borrow the parent's mutable
active register shadow or per-module store. Reuse the module scheduler
child-task lifecycle, encoder/decoder, and `module/IModuleKernel` seams.
Register closure lifting uses image-relative body references and `store-of`;
lowering validates binding/format, attached origin, and arity against actual
closure instructions before minting the receiver's trusted Closure value.
Cache compiled wasm modules by `A`, `r2` derivation/checksum `R`,
internal layout/emitter version, and feature profile; `R` alone is
not executable admission authority. A missing or dropped artifact falls back to
the in-instance register interpreter at any materialized boundary.

### 10.4 Register conformance fixture schema (K0/W0)

**W0 and Rust K0 are the same conformance-kit deliverable**, exported from R4,
not independent harness authorities
(`yin.vm.rust.md` §10 K0). The guest-agnostic differential
conformance method (oracle export discipline, the CLJS/Rust-interpreter/wasm/LLVM
lanes, trace-not-just-values comparison, B0-normalized vs. raw boundary
comparison, and determinism/snapshot gates) is specified in
[`yin.vm.engine.md`](./yin.vm.engine.md) §E.5.1–§E.5.3.

For the register format and kernel, the K0/W0 kit exports, per corpus program:
- the register image as canonical data,
- `R` (`register-hash`),
- the B0-normalized result or error, and
- the format-level trace R4 produces (sparse continuation payload at every boundary, `K` depth, saved register pairs).

Plus the required register fixture groups:
- refusal fixtures for all fourteen validator rules with rule keywords,
- the descriptor hash (`descriptor-hash`),
- executable register scalar-encoding vectors (`yin-scalar` / `debruijn_code.cljc`),
- diagnostic message fixtures,
- refused-step atomicity fixtures (a refused step leaves the state equal),
- tail-site fixtures for every row of the Rust design's §7.3 call-outcome table (including a tail primitive value and a tail primitive effect returning into a caller in another body, and halting with `K` empty; `yin.vm.rust.md` §7.3),
- a wide-body fixture whose register count exceeds `65,535`,
- `:host-limit` refusal fixtures, and
- the million-iteration tail fixtures.

Required repository corpora for register-wasm and Rust K0–K5 (each processed by the
generic projection/admission/execution/comparison procedure of
[`yin.vm.engine.md`](./yin.vm.engine.md) §E.5.1; only the register-specific
fixtures, bindings, and compared fields are listed here):
- `test/yin/vm/semantic/corpus.cljc` **programs and literal goldens**: these goldens specify syntax/address, not a runnable environment or expected final value for every entry. Supply deterministic `f`/`g`/`h`/`k`/`x`/`y`/`c`/`loop` bindings, stream fixtures, and genuine parked targets for resume cases; otherwise compare the same qualified refusal.
- All fixtures and assertions of `test/yin/vm/debruijn/register_test.cljc` (plus `test/yin/vm/debruijn_code_test.cljc` and `test/yin/vm/debruijn_register_code_test.cljc`), including hand images, B0/B2 parity, stream scripts, continuation corruption, and `IVM` behavior. The register raw observable fields compared beyond the method's B0-normalized values: register payload shapes, `store`/`module-stores`, `pc`, frame order, `K`, counter, flags, `wait-set`/`ready-queue`/`parked` entries, and emitted request traces.

### 10.5 Register-specific W0–W6 implementation slices and acceptance gates

The node-lane schedule replaces private-service-first staging by owner ruling,
2026-10-19. Toolchain pins, oracle-first K0/W0, two-codec determinism, wasm32
compilability and dao.stream implementation probes live in
[yin.vm.engine.md](./yin.vm.engine.md) §E.5.2–§E.5.3. Compiler work lives in
[yang.wasm.md](./yang.wasm.md) §6.1. Each gate below retains the existing
register acceptance assertions, now against the in-instance engine.

#### 10.5.1 W0 — Oracle first, admission, wasm32 feasibility and internal state

First export/extend K0 from the CLJS oracle; probe the shared crates on
`wasm32-unknown-unknown` with the final-module feature allowlist and adapter
reservation/handle/CBOR tests. No service-library substitute is allowed.

Acceptance: all semantic goldens retain their literal vector/address; all
derived register images retain `R` and exact live operands. Existing register
tests pass unchanged on the reference lane. Invalid contract, Rule R, unknown
opcode, cross-body target, bad live set and empty-image cases have identical
admission outcomes. Validate a minimal wasm instance and explicit resource-limit
diagnostics. Establish matching-layout register payload transport and qualified
`:continuation-format` refusal for incompatible layouts (§10.2.3). Freeze the
eight-field register map/memory authority protocol (§10.1.1) and its
internal snapshot equivalents, including external `assoc`/snapshot replacement,
old-state replay, and preservation of unknown engine extension keys; do not
defer that contract to performance tuning. Add cross-embedder vectors for
`descriptor-hash`/`R` (including empty and concatenated images), exact encoded
bytes, owner/seal/profile inputs, and manifest addresses.

#### 10.5.2 W1 — Pure register execution and body dispatch

Required runtime gate: Node + Wasmtime, identical image binaries and full
step/stream traces. The required shared Rust crates must actually compile, link
and run inside wasm; require their interpreter dispatcher to pass the same pure
fixtures. Failure of wasm32/profile feasibility blocks W1. Also test
refused-step atomicity and per-machine fault isolation from K0/K1, including
the wide-register (`>65,535`) and million-tail cases.

Implement arena, dense register shadow, body dispatcher,
constants/moves/loads/branches, closures, explicit calls/returns/halt, primitive
results, `store`/`define`/`gensym`, and one-step materialization.
Effects/continuation execution remains explicitly unavailable until its slice,
rather than silently emulated as a completed wasm feature.

Acceptance: register
literal/load-bound/free/store/nested-closure/multi-arg/compose/branch/B0/B2/gensym
tests; pure semantic corpus entries including `nested-if-same-rd`,
`body-queue-order`, `define-then-call`, and unreachable structural slots. Add
nil-fill/extra-drop, zero truthiness, frame shadowing, backward/irreducible
valid CFGs, source/destination aliasing, bounded yields, deep non-tail
recursion and long tail recursion. Assert guest `K` behavior and bounded native
call depth. Preserve snapshot immutability between steps. Assert bit-identical
load `R`, owner tags and primitive-profile addresses, plus direct map
`pc`/register/frame reads, externally `assoc`-modified entry state, and
`:halt`/final-`:return` preservation of the executing `pc`, `:registers`, and
`:frames` (including nonempty `K` on `:halt`).

#### 10.5.3 W2 — Continuations and `register-restore`

Required runtime gate: Node + Wasmtime full continuation/restore traces, plus
native↔CLJS admissible payload round-trips and identical qualified refusals.
Implement native payload/owner/type validation before this gate.

Implement sparse payload materialization (§10.2.1), reification, internal
continuation application, explicit `park`/`resume`, and external
`register-restore` (§10.2.2). Use existing validators and engine gates.

Acceptance: `current-continuation`, `park-and-resume`, `bytecode-resume`, every
tampered/refusal test in `register_test`; semantic corpus `park`/`resume`
productions with valid fixture targets or matching errors. Add nested non-tail
capture then abortive invocation, multi-shot capture, tail `:return-result`,
live Closure versus live primitive distinctions under `machine-data?`,
host-function/error resume refusal, error-shaped data success, dead-slot `nil`
restoration, snapshot alias isolation, and both native
interpreted→wasm/wasm→interpreted payload round-trips. Assert expected linker
`:non-canonicalizable` refusals rather than claiming transport support from
same-process tests. Assert native `continuation-defect` recomputes the same
repository `R` on original and tampered images, without trusting the supplied
hash or hashing adapter bytes. Add `:return-result` fixtures with empty and
nonempty captured `K`, deliberately different `base` `pc`/registers, and exact
post-restore `pc`/register/frame assertions. Add an unowned wrong-arity
continuation case proving `:foreign-value` precedes `:continuation-arity`, and
an owned wrong-arity/foreign-format case proving arity precedes format.

#### 10.5.4 W3 — Streams, shared scheduler, and FFI

Required runtime gate: Node + Wasmtime full scheduler/event/stream-adapter traces using
independent scripted transports; in-instance scheduler, custody, collection and
two-step FFI are required now.

Implement typed effect completion inside the engine, register site wait builders,
scheduler seam, call-effect handling including tail effects
([`yang.wasm.md`](./yang.wasm.md) §3.4), bridge wrapper, FFI request/response
logic and pinning.

Acceptance: every immediate/blocking stream and bridge success/error test in
`register_test`; semantic corpus streams/ffi/park with scheduled events. Add
full-writer two-step and stale-key checks, pending-ready-after-halt,
lost/closed stream outcomes, missing call-pair before id allocation,
call-result errors, repeated polling without duplicate append, effect
permission checks and resource-update validation. Compare value/store/parking
and external emission traces after each wake, not only final values. Exercise
the [`yang.wasm.md`](./yang.wasm.md) §3.4 (r5 §2.4) host-call matrix:
immediate/blocked × tail/non-tail, including `put`/`next`, module
`require`/`try-require` builder fallback (full module installation in W4), and
running `poll`'s `observe` fallback. Assert blocked `pc` advances without `rd`
write or tail return, and post-effect state mutations survive each completion.
Compare immediate empty-`K` tail completion at site `pc` with restored blocked
completion at entry `pc`.

Add a low-threshold `:cell/new` host-effect test whose only live cell roots
occur in newly materialized registers/frames/K, forcing collection inside
`handle-effect`; it must retain those cells. Check `:value` after each
nonblocking effect and verify a plain non-tail primitive leaves the previous
value unchanged. Test all supported gate modes
(`ungated`/`running`/`exporting`/`ended`), no handle calls for deferred
FFI/cursor/put/poll/close/link work, separate issue/id counters, queued running
closes versus exporting/ended refusals, suppressed wait polling, and driver
apply synchronization. Scope `wait-entry-defect` to its four shapes (§10.2.4);
use payload plus owning-shape checks for `observe`/`link`/`install`. These are
W3 correctness gates, not deferred GC optimizations. Assert exact seals on
stream/cursor/cell refs with the same secrets and ids, and deterministic
`resource-updates` diagnostic order under [`yin.vm.engine.md`](./yin.vm.engine.md)
§E.4.3 (r5 §4.1.2).

W3 repeats W0 adapter probes against real effects: full-before-append, response
reservation before execution, all stream terminal/error outcomes, bounds/CBOR/
handle failures with no accidental mutation, and no automatic retry after an
unknown-acceptance transport error. Full writer wake converts once to the FFI
reader: no second append/id/GC/resource update. Compare resulting records and
canonical stream bytes on Node and Wasmtime; Wasmer repeats by W5. There is
no response-refetch protocol. Internal gensym/park/GC never cross adapters.

#### 10.5.5 W4 — Attach, modules, and lifetime correctness

Required runtime gate: Node + Wasmtime full module/state/stream traces; implement
in-instance admission, module stores and resource/closure transport. Prepare
the Wasmer adapter implementation; its complete suite is required by W5.

Implement independent image instances/function-table append, `IModuleKernel`
adapter, closure/resource transport, `store-of` restoration, `reset`/`load-image`
distinctions and arena/heap root integration (§10.3).

Acceptance: rerun both corpora completely; add A-calls-B-while-C-attaches
followed by ordinary return, park-before-attach then resume, stale hash but
present image success, absent-image refusal, duplicate/empty attach no-op,
empty-base row, layout-image rehashing, independent `spawn-module`
counters/stores and module `require` in a tail call. Cross-layout closure lower
validates arity/format and private store behavior. GC tests retain captured
live values and FFI-pinned refs, release unreachable scratch records, and never
free values held by old immutable state snapshots. No return or resume shrinks
the code space. Reuse one compiled component in two different attachment orders
and assert absolute exit/capture/return/closure pcs and combined-segment
payload checks. Assert empty-base attachment retains both offset rows, `halted`
true and `registers []`, then `reset` and capture with the two-row table.
Complete gated install-child scheduling and link-response/install oracle tests.
Node and Wasmtime must pass the identical binaries now; Wasmer must pass the
entire W1–W4 suite as part of W5 (dated owner schedule supersession above).

Require in-wasm↔CLJS module-slice parity in both directions: lift with
`engine/lift-slice`, transfer real canonical content, then receive with
`engine/receive-module` semantics into matched receiver
layouts/secrets/resources. Compare exact slice-entry vectors, cells/aliasing,
store snapshots, component image hashes/addresses, minted stream/cursor ids and
seals, owner/profile fields, counters, diagnostics and subsequent module
execution. Vary insertion history and map size, nested sets/maps, multiple
module stores and repeated references; verify the shared traversal
prerequisite, not just final return values. Recompute repository hashes on
receiving in-instance engines. Also test distinct receiver secrets: compare each
receiver against its own CLJS oracle, since valid resealing intentionally
differs across different secrets.

#### 10.5.6 W5 — Lane completion and release gate

Register wasm as an embedder-agnostic backend with Node, Wasmtime and
Wasmer stream adapters, with no changes to the `r2` opcode table. Freeze
the dao.stream implementation/profile and internal linkage versioning and deterministic binary generation. Run
all named register tests and every semantic golden through the harness; report
unsupported canonical-semantic fragment cases as named refusals with reasons.
The wasm backend must not silently narrow behavior relative to an admitted
register image except documented resource limits or preexisting transport
refusal boundaries.

Acceptance: required corpora pass in full, every opcode exercised, public
`IVM`/`IVMState` behavior matches (including eval/environment refusals),
differential traces agree, no host handle leaks into wire payloads, and all
binary artifacts validate. Run focused JVM reference tests with the existing
`clojure -M:test -n yin.vm.debruijn.register-test` invocation, the existing
Node tests through the documented
`clj -M:cljs -m shadow.cljs.devtools.cli compile test` lane, and the new wasm
harness in that lane. Add dedicated native Wasmtime and Wasmer harness tasks
and a shared cross-runtime trace runner; run all W1–W5 cases on Node and both
native runtimes against the reference. No native lane may be reduced to
final-value checks. Verify byte-identical artifacts, dao.stream external
import allowlist, malformed CBOR/bounds/overflow/reservation/full handling,
no duplicate effects and no JS/WASI dependencies in native execution. Register
these build/tasks during implementation rather than pretending they exist
today. Follow repository quick/slow phase gates and existing Dart regression
lane for shared CLJC edits. These acceptance tests are planned, not reported
execution results.

#### 10.5.7 W6 — Measured optimization and register non-goals

Keep Node + Wasmtime + Wasmer full-trace parity mandatory for every
optimization. Measure compilation latency, binary size, dispatch/indirection,
native stack depth, arena/heap retention, call/suspension, stream bytes/copy/codec
cost and end-to-end time against the same in-instance register interpreter.
Only then coalesce/structure blocks, reduce redundant spills or use pinned
Binaryen passes ([yang.wasm.md](./yang.wasm.md) §6.1). Re-run all parity and
profile gates. Engine placement is settled; W6 is **codegen optimization only**,
not a service-migration or alternative architecture experiment. K3 may measure
wasm on the shared harness; this does not commission K4. Making wasm work is
not evidence it is faster.

Register-specific non-goals (in addition to the guest-agnostic non-goals in
[`yin.vm.engine.md`](./yin.vm.engine.md) §E.5.4): no new register
opcode/contract version, no alternate A→R register allocator, no native stack
continuations or async-stack capture, and no full semantic VM feature parity
beyond the established native register fragment.

#### 10.5.8 Rust K1–K5 and LLVM acceptance retained

The same K0/W0 conformance kit retains the Rust phase gates
(`yin.vm.rust.md` §10 K1–K5):
- **K1:** pure fixtures and R/refusal parity; constant stack and one file per live activation on tail fixtures; refused-step atomicity; no panic. Fault-injected decoded images refuse with `:kernel-invariant` or poison at most the one machine, while another task completes.
- **K2:** payload/effect descriptor canonical bytes equal R4, including every return frame's `:live`; bidirectional Rust↔R4 restore and equal `continuation-defect` refusal rules.
- **K3:** benchmark evidence for owner review before K4 is commissioned.
- **K4:** deterministic translator bytes across runs/platforms, full-kit interpreted/native boundary and refusal equality, artifact-drop fallback, and interpretation with no toolchain plus `:unsupported`.
- **K5:** shared engine parity and blocked-machine wire round-trips with JVM R4 in both directions.

Wasm completion does not certify the full K5 standalone node. Rust interpreter
coverage advances alongside the W slices and cannot be omitted because native
wasm passes. Native runtime execution is not CLI validation, replay, a JS
subprocess or a success-only primitive stub.

### 10.6 Register-specific risks, reference conventions, and open items

Guest-agnostic host, arena, single-port, and embedder risks are specified in
[`yin.vm.engine.md`](./yin.vm.engine.md) §E.5.4; lowering-specific risks (body
dispatch size, wasm module code growth, canonical admission) are in
[`yang.wasm.md`](./yang.wasm.md) §6.2. Register-host-specific risks retained
from r5 §7:

1. **Native continuation transport has a limited scope.** The sources support same-model register payloads and qualified transport refusal, while the module encoder currently refuses continuations. Keep this distinction visible in API docs/tests. A full portable continuation design would require separate authorization and compatibility work, not an ad hoc wasm-only decoder.
2. **Absolute `pc` layout compatibility.** The register kernel's format check tests image membership, not complete receiving-layout equivalence. That suffices for grow-only same-task use; it is not proof that arbitrary imported absolute pcs mean the same thing. The transport adapter must enforce matching layout/prefix or use a separately specified rebaser (§10.2.3). Preserve legacy restore diagnostics without presenting the legacy check as stronger than it is.

The wasm register implementation is complete when the same emitted baseline
binary for an admitted `r2` image has the same values, store transitions,
continuation records, refusals, parking/wake behavior and module growth under
Node, Wasmtime and Wasmer as under the existing register kernel, with
full-trace parity and no suspended computation held on the wasm stack.

**Reference conventions and open items:** follow
[yin.vm.engine.md](./yin.vm.engine.md) §E.5.5 and
[yang.wasm.md](./yang.wasm.md), Reference conventions / Open items. The former
service-call split and response-refetch questions are superseded by the owner
ruling. Remaining W0 feasibility/adapter/ordering gates do not change the
register fixture schema or restore semantics.
