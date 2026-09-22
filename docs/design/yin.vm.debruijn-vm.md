# yin.vm de Bruijn VM

Status: design; not implemented

This document specifies a second executable path for `yin.vm`. The existing
path lowers named Universal AST datoms to `:yin.code/*` and executes that
image. This design defines a sibling VM for a derived linear image. No second
lossless source view is built. The merged projection is retained as a dormant,
non-load-bearing artifact and is not consumed by this VM or its linker.

## 1. Architecture and invariants

Design invariant I: a yin.vm can share executable code over a `dao.stream`
linker. The linker may be local or remote, but its boundary is always the
stream.

Three artifacts are associated with one named AST. The named datoms are the
bijective source representation. The executable de Bruijn image is the
execution artifact and the sole sharing identity, H. The merged projection is
an alpha-canonical, lossy historical artifact. It is dormant, has no consumer
in this design, and is not itself executable.

Architecture A means lowering from projected records. Architecture B, the
recommended architecture, lowers from named datoms and is not the same as A.
Lowering projected records cannot promise named execution semantics because
projection deliberately canonicalizes scalar spelling, preserves free names
only in canonical form, removes binder names and provenance, and drops
front-end `:yin/tail?`. Restricting the input to already-canonical programs,
weakening continuation guarantees, and treating the records as a cache key
would make that path unsound; making them non-identities would make it a poor
executable artifact.

Named datoms remain the lossless truth. Binder names and provenance are kept
in the diagnostic side table used by the executable image, while lexical
positions are derived by `resolve-name`; no second lossless fact structure is
persisted. This follows the datom.world rule "derive, don't persist".

The executable VM promises equivalence with the named semantic VM only after
normalization. Results, effects, streams, cursors, stores, terminal and
blocked outcomes, and errors must agree. Error comparison uses the message;
ex-data is compared after recursively applying the value normalizer to every
ex-data value. Closures compare as `{:type :closure :arity n}` only;
continuation and parked values compare by type only; stream and cursor
references compare by identity; host handles and telemetry are excluded.
Behavioural application tests cover closure contents. This normalizer is
defined and tested in B0, rather than assumed to be an existing repository
utility.

The free environment is the VM's initial environment value, fixed for one VM
instance. It is not the mutable callee `:env` register left by an earlier
named `run`. The named VM's environment leak is a separate defect and is not
silently included in this contract. B5 uses fresh VM instances for ordinary
parity. Park/resume fixtures that cross program boundaries are restricted to
free names supplied by the initial environment or store until the named-VM
leak is fixed; otherwise B5 records a named-path refusal.

Until B7 dependency closure exists, invariant I safely shares an image only
when it is closed for the receiving environment, or when that environment
binds every free name identically. Free names are `:load-free` operands, not
bindings. Closed means that the receiver-side closure check passes: every
free name resolves through primitives or modules, and none is shadowed by the
receiver's free environment or store. An image with no `:load-free` operands
passes trivially. Otherwise B6 refuses it with `:unresolved-free` or
`:shadowed-free`, rather than executing it.

H is alpha-invariant for binder renames because binder names are outside the
hash. Equal H therefore implies agreement on exact scalar spelling and free
name operands as well as the same executable image. Alpha-equivalent source
programs share H only when their front-end tail flags agree; this is sound but
intentionally incomplete.

The following are not promised to be identical: binder spelling in the
dormant projection, instruction program counters, temporary ids, allocation
identity, stack-trace text, source-map formatting, or interchangeability of
continuations between the two VMs. Named datoms remain available for complete
decoding and debugging; the executable image is not an inverse encoding.

The VM obeys the datom.world invariants: state is explicit data, execution is
an interpreter above streams, callbacks are never retained, effects are
stream-visible, and the existing AST, merged dormant projection, lease, waitset,
and named VM
contracts are not changed.

The following table records compliance with design invariant I and the
datom.world principles.

    +--------------------------+--------------------------------------------+
    | Requirement              | Mechanism                                  |
    +--------------------------+--------------------------------------------+
    | I: share executable code | B1-B2 define H and the lift; B6 fetches    |
    | over dao.stream          | and verifies images over streams without   |
    |                          | requiring B3 or B4 (§2, §6, §7.2).         |
    | Everything is a stream   | Effects, transfer, linking and failure     |
    |                          | are stream outcomes (§4, §7.2).            |
    | Everything is a          | Parked machine state is explicit data and  |
    | continuation             | resumes through stream-carried events      |
    |                          | within a VM; cross-host UCF transport is   |
    |                          | deferred (§4, §7.2).                       |
    | No hidden global state   | VM state, free-env and registries are      |
    |                          | explicit values (§4).                      |
    | No implicit control flow | Steps classify explicit data; missing      |
    |                          | code parks and emits a request (§7.2).     |
    | No callbacks             | No loader or callback is invoked; events   |
    |                          | cross stream boundaries (§7.2).            |
    | No shared mutable state  | Frames, stores and continuations are       |
    |                          | persistent transition data (§4).           |
    | No layer collapse        | Named source, lowering, VM and linker      |
    |                          | remain separate interpreters (§1, §7.2).   |
    | No assumed graphs        | Image refs and body ranges are validated   |
    |                          | before execution (§3).                     |
    | Derive, do not persist   | Named datoms stay authoritative; lexical   |
    |                          | positions are derived (§1).                |
    | Interpretation semantics | Named and de Bruijn VMs are compared by    |
    |                          | the explicit normalizer (§1, B0).          |
    | Host boundaries          | Cross-host common domain is tested;        |
    |                          | unsupported classes refuse (§2, B1).       |
    | Strained: frame env      | Frames require a lift for completion and   |
    |                          | exported continuations (§4, B4).           |
    | Strained: image state    | Loaded images are explicit values; the     |
    |                          | linker must supply missing-code events.    |
    | Strained: diagnostics    | Binder names and provenance are side data  |
    |                          | and never executable identity (§2, §3).    |
    +--------------------------+--------------------------------------------+

### Benefit and exit criterion

The primary benefit is network-shareable, alpha-invariant, content-addressed
executable code over `dao.stream`. Existing named code is already content
addressed by `yin.vm.content`; this adds binder-alpha-invariant image identity
and portable bytes for the common scalar domain. Sharing does not require the
frame VM: its additional benefit is positional lookup performance, which is
measured but not assumed. Free names still require B7 dependency closure.
Physical Jing addresses remain print-based until the DaoJing CBOR work lands;
H uses its own canonical encoding and is independent of that address.

After B3, the semantic VM section 8 harness reports throughput, allocation,
image size, and load time on identical pure-program corpora. The report is
informational, not an acceptance condition. Retiring the semantic VM would
require its own design because existing consumers depend on it; the owner may
instead make the de Bruijn VM the default while retaining the semantic VM.

## 2. Instruction dimension and code identity

The executable image uses a new `:yin.debruijn.code/*` dimension. The
implementation defines and exports its descriptor; persistent publication and
discovery are outside B0 through B7. Existing `:yin.code/*` semantics are
unchanged. The new descriptor derives its opcode table as data from the
existing `yin.vm.code/vector-operand-table`; only lexical addressing differs:
`:var` becomes `:load-bound` or `:load-free`, and `:closure` carries
arity and body references rather than parameter symbols. The stack operations,
`:const`, `:push`, `:halt`, `:branch-false`, stream operations, store
operations, park, resume, gensym, and FFI retain their existing opcode shapes.
Its descriptor declares a lowering-contract version, arity, ordered slots,
canonical encoding, and one lift
morphism from `:yin.debruijn.code/*` to `:yin.code/*`; the lift uses diagnostic
binder names or synthesized names. Exact-spelling slots are typed raw `Bytes`,
so no-NFC behavior is a slot rule, not an exception to the dimension contract.

The new code namespace copies its small framing and length-prefix helpers and
defines its own scalar tag table; it does not reach private vars in the merged
dormant projection namespace.
It uses `jing/sha256` and a separate executable scalar encoding: distinct tags
represent nil, booleans, long, double, ratio, bigint, char, string, keyword,
symbol, vectors, lists, maps, sets, and other explicitly supported values.
Map and set entries are ordered by encoded bytes; vectors and lists are
distinct classes. Numeric bits and string/name UTF-8 bytes are used exactly as
supplied; no NFC or integral-double folding occurs in hashed bytes.
Unpaired UTF-16 surrogates are refused with `:unsupported-value`, as they are
by the projection's UTF-16 guard. Values outside the declared executable
domain are refused with a qualified `unsupported-value` diagnostic, not
silently canonicalized. This is deliberately distinct
from `yin.vm.debruijn/encode-value`.

Binder names and provenance are carried in a diagnostic side table indexed by
instruction slot and are outside the image hash. Exact scalar bytes are in
the `:const` operands and are included in the image hash; free names remain
hashed `:load-free` operands.

Host classification is explicit. On CLJS, a JavaScript number is encoded as a
long when it is a safe integer and as a double otherwise. Integral-valued
doubles are outside the CLJS common scalar domain, because JavaScript cannot
distinguish them from longs; a CLJS receiver refuses such an image with
`unsupported-value`. CLJS cannot distinguish source spellings that already
read as the same number. Characters are a distinct class only where the
host has one and distinguishes it from a one-codepoint string: the JVM.
ClojureDart's `char?` does not reliably tell a genuine char from a
one-codepoint string (verified during B1), so this dimension treats
ClojureDart the same as CLJS here: no distinct :char class, chars encode
as strings. Ratios and host bigints are JVM classes; neither ClojureDart's
core nor this dimension defines a project-local equivalent, so B1 refuses
them with `:unsupported-value` on CLJS and ClojureDart alike (the ratio
fixture's designed either/or, resolved: encoded on the JVM, refused
elsewhere). Cross-host byte identity is required only for values present
on all three hosts. B1 tests for `1` versus `1.0` are JVM/Dart tests;
ratio and char tests are JVM-only; shared cross-host fixtures cover the
common scalar domain.

The scalar classes an image uses are derived from tags in its hashed `:const`
operands. A producer can scan those tags to identify common-domain-safe images.
A receiving host that lacks a class refuses the image with a qualified
`unsupported-value` outcome before execution; this is a host-boundary outcome,
not a stream gap.

The code-image identity H is computed by one B1 function, `image-hash`, over
the descriptor hash, including its lowering-contract version, and the
canonical positional instruction vector: pc-indexed tuples, refs resolved to
pcs, no header, and exact scalar bytes. Provenance is
a diagnostic side table indexed by pc and is outside the hash. H is the only
executable and sharing identity. No projection fingerprint or second identity
is attached to the image, request, response, verification, or cache. Any later
cache is keyed by H.
No cache is specified by B0 through B5; any later cache is an explicit value
keyed by H. A receiver hashes the received canonical wire bytes before
decoding; those bytes are H's preimage. `jing/segment-key` is only the storage
address for the same bytes and is not H; `yin.vm.content` stores and fetches
bytes, while verification recomputes `image-hash`.

## 3. Lowering and scope

The lowerer receives complete `:yin/*` named datoms. It reuses only the public
`yin.vm.debruijn/resolve-name` helper. That helper compares names exactly,
including rightmost-wins duplicate parameters, and does not canonicalize
values. Projection-only helpers such as `index-frame`, `build-node`, and
`project-node` are not reused. Unexpanded-macro validation remains the named
front end's responsibility.
The projection is therefore kept, not removed: if it is ever retired, this
public helper must first move into this design's namespace.

The walk is deterministic and left to right. Lambda bodies are out of line,
applications evaluate operator then operands, and `if` evaluates one arm.
The lowerer copies the front end's `:yin/tail?` exactly as the named linearizer
does. It does not infer tail position from syntax. Only `:application` has a
tail operand in the existing instruction table; `:dao.stream.apply/call`
lowers to `:ffi-call` and has no tail operand.

The image inherits occurrence expansion, fresh lambda labels, and body layout
from `yin.vm.linearize/lower`; B2 does not repeat that traversal. Any adapter
memo is keyed by the emitted body and pc context. If a future source-level
memo is introduced, its key must include node identity, the full vector of
parameter-name vectors, and tail context. Frame arities remain separate scope
validation data and cannot determine name resolution.

The executable image may carry a debug node hash and named source reference.
Those fields are always diagnostic metadata outside code identity. They replace
no named source map.

Scope validation is mandatory in both places where an image can enter:

1. B2 validates nonnegative depth and position against the frame-arity vector.
2. B1's image validator repeats the check by walking each body's declared
   arity and enclosing-body chain. A shape-valid hand-built image with
   `[:load-bound [5 0]]` is rejected before execution.

No projected reader participates in this path; only the B1 image validator
admits executable images.

## 4. VM state and execution

The sibling VM uses explicit state:

    {:segment code-image
     :pc pc
     :frames [frame ...]
     :free-env initial-name-map
     :stack operand-vector
     :continuation continuation-data
     :store store
     :status status}

Frames are positional vectors ordered outermost to innermost; frame zero for a
bound reference is the innermost frame, read from the end of the vector. A
closure captures the persistent frame stack and its body reference. Calls use
the positional equivalent of `bind-params`: `(vec (take arity (concat args
(repeat nil))))`. Missing arguments are nil-filled and extras are dropped.
The existing named `bind-params` remains name-keyed and unchanged.

`:load-free` uses the existing `resolve-var` order with `free-env`, store,
primitives, and module registry. Positional locals never fall through to a
store key. `:macro?` is retained only in named datoms; runtime closure
application ignores it exactly as the named runtime does after macro expansion.

The VM is a new `yin.vm.debruijn-vm` namespace. It may implement existing VM
protocols without adding methods, and it may reuse data-only engine helpers
for name resolution, primitive descriptions, stream descriptors, store
operations, gensym, and continuation records. It reimplements any helper
that serializes the named register layout, including private response wait
entries and named telemetry snapshots. No existing protocol or VM semantics
change.

It does not implement `vm/IVMState/environment` until a frame-to-named lift is
defined. If that method is later implemented, it returns the lifted named
environment, not the positional frame vector.

Park and resume carry the frame stack, free environment, operand stack, code
identity, continuation chain, store view, parked records, gensym counter,
primitives, modules, code aliases, and the semantic VM's other non-environment
registers as explicit data. B4 supplies a frame-aware completion adapter or a
lift through the descriptor morphism before exporting a continuation. UCF
remains proposed and deferred, so heterogeneous continuation transport is not
yet promised.

## 5. Effects and equivalence boundaries

The executable path covers the existing literal, variable, lambda,
application, `if`, stream, store, gensym, continuation, park, resume, and FFI
operations. Macro expansion remains upstream; unexpanded macro diagnostics
are not runtime execution.

Equivalence excludes the named VM's stale-register leak. A named VM instance
used for B5 must begin with a fixed initial environment, or the test records a
named-path defect and does not claim parity. It also excludes representation
details that the normalizer intentionally erases. It does not exclude exact
literal values, spelling-sensitive store keys, front-end tail choices, stream
outcomes, or error classifications.

## 6. Implementation phases

Each phase B0 through B7 has a file box, a must-not-change list, completion
criteria, and JVM, Node/CLJS, ClojureDart, kondo, and cljstyle verification.
The shortest sharing path is B0, B1, B2, B6, then B3 through B5, then B7.
B6 depends on B1, B2, and the lift, not on B3 or B4; it may execute a fetched
image through the existing semantic VM after lifting with synthesized names.

### B0: contract and normalizer

    New: test/yin/vm/debruijn_vm_contract_test.cljc
    Existing edits: none
    Must not change: AST, emitter, merged projection namespace, named VM,
    code dimension

Freeze the result/error normalizer and actual parity corpus:
`parity-test` plus the every-tag corpora in `content_test` and
`completion_test`. Completion requires named-VM self-parity and idempotence of
the normalizer, plus normalized closure, continuation, error, stream, cursor,
and store comparisons, duplicate-parameter, and all-node fixtures.

### B1: executable dimension and validator

    New: src/cljc/yin/vm/debruijn_code.cljc
    New: test/yin/vm/debruijn_code_test.cljc
    Existing edits: none
    Must not change: yin.vm.code, :yin.code/*, merged projection namespace

Define and export the descriptor, derived opcode table, exact executable
scalar encoder, code-image hash, and validator. Completion includes malformed
rows,
out-of-range bound operands, exact spelling preservation, distinct hashes
for composed and decomposed e-acute, a ratio fixture that is either
encoded or refused with `:unsupported-value` (JVM encodes, CLJS and
ClojureDart both refuse), distinct hashes for `1`/`1.0` on JVM and Dart,
distinct hashes for ratios and chars on the JVM, and identical bytes
across hosts for the common scalar domain. Golden image bytes and H
values for a frozen corpus are checked on all three host lanes. A
lowering layout change must fail those fixtures rather than silently
forking identity.

### B2: named-datom lowerer adapter

    New: src/cljc/yin/vm/debruijn_linearize.cljc
    New: test/yin/vm/debruijn_linearize_test.cljc
    Existing edits: none
    Must not change: yin.vm.linearize and named lowering semantics

Run the existing `yin.vm.linearize/lower` on the named datoms, then rewrite
each `:var` operand and replace each `:closure` parameter vector with its
arity. Scope reconstruction is a pure function of the image, never of
`:yin.code/source`: each body range is `[entry, first :return]`, its parent is
the body containing the `:closure`, and the name stack for `resolve-name` is
built innermost-first. This is opposite to the runtime frame vector's
outermost-to-innermost order and the conversion is explicit. `segment-scope`
is the public precedent; the new namespace reproduces the rules of private
`closure-ranges` and `layout-conforms?` rather than changing their visibility.
A failed
`layout-conforms?` check is a validation defect. Completion requires
deterministic output, every node and opcode, lexical validation, a structural
opcode-by-opcode comparison with `lower` differing only at variable and
closure operands, image encode/validate/load round trips, and
`lift(adapt(lower x), side-table) = canonical-vector(lower x)`. The lift is a
function of the image plus its diagnostic side table. With the original binder
names in the side table, equality holds; with synthesized names, the lifted
result is alpha-equivalent to `lower x`, including `(fn [x x] x)`. Synthesized
names must be fresh against the image's free-name set. A supplied side table is
accepted only when adapting its lift returns the original image.
Every image B2 emits must be accepted by B1's validator, and every hand-built
out-of-range image must be rejected by both validators.

### B3: de Bruijn VM kernel

    New: src/cljc/yin/vm/debruijn_vm.cljc
    New: test/yin/vm/debruijn_vm_test.cljc
    Existing edits: none
    Must not change: semantic VM, engine, IVM protocols, named environment,
    merged projection namespace

Implement frames, closures, loads, calls, returns, branches, literals, and
store operations. Completion requires pure-program parity using B0's
normalizer and fresh initial environments, plus the informational section 1
benchmark report, not an acceptance condition.

### B4: effects and continuations

    Existing source: src/cljc/yin/vm/debruijn_vm.cljc
    New: test/yin/vm/debruijn_vm_effects_test.cljc
    Existing edits: none
    Must not change: dao.stream protocols, lease, waitset, named effect rules,
    merged projection namespace

Implement stream operations, primitives, FFI, gensym, current-continuation,
park, and resume. Completion requires parity for values, errors, effects,
stores, stream outcomes, and blocked states. Cross-program park/resume tests
must use only initial-environment or store-resolved free names until the
named-VM environment leak is fixed. Before a continuation is exported, B4
must either lift frames through the descriptor morphism or run the frame-aware
completion adapter so `:yin.k/requires` is not under-approximated.

### B5: differential integration

    New: test/yin/vm/debruijn_vm_parity_test.cljc
    Existing edits: none
    Must not change: named storage and the existing linearizer pipeline

Compare `ast->datoms` -> B2 -> de Bruijn VM against
`ast->datoms` -> `linearize/lower` -> semantic VM. Use the actual parity and
content/completion corpora, not helper-only tests as execution fixtures.
Completion requires programs differing only in binder names to produce the
same H on every host lane for the common scalar domain. Programs differing in
exact scalar spelling, front-end tail flags, or free names must produce
different H values. An image sent over a `dao.stream` from one host lane must
load, validate, and execute on another with the same normalized result as
local execution. B5 uses committed golden bytes for cross-runtime identity;
the real cross-process stream transfer is a B6 acceptance test. All three host
lanes must agree under the normalizer.

### B6: committed closed-image stream linker

    New: src/cljc/yin/vm/debruijn_linker.cljc
    New: src/cljc/yin/vm/debruijn_linker_responder.cljc
    New: test/yin/vm/debruijn_linker_test.cljc
    New: test/yin/vm/debruijn_linker_responder_test.cljc
    Existing edits: none
    Must not change: merged projection namespace, image-hash, dao.stream,
    named VM semantics

Implement fetch-by-H for closed images. B6 tests use an explicit fetch; the
`:call-hash` instruction is emitted only by the later dependency linker. A
host emits a REQUEST value carrying H, and a responder process holds an
explicit value mapping H to image bytes or queries an H-to-address datom chosen
by its composition. The response carries canonical wire bytes or a qualified
absence/unsupported outcome. The receiver hashes those bytes before decoding,
checks H and the descriptor, runs the closure check, validates, and loads only
verified bytes. Every free name must resolve through primitives or modules and
must not be shadowed by free-env or store; failures are `:unresolved-free` or
`:shadowed-free`. Completion requires JVM to Dart stream transfer, where the
receiver initially knows only H, mismatch and unsupported refusal, absence
events, and equal normalized results using the existing semantic VM via lift.

### B7: dependency closure linker

    New: src/cljc/yin/vm/debruijn_linker.cljc
    New: test/yin/vm/debruijn_linker_dependency_test.cljc
    Existing edits: none
    Must not change: merged projection namespace or B6 closed-image semantics

Add value/stream name environments, hash-of-unit dependency closure, and
strongly connected component manifests. Completion requires cycle-safe
ordering, explicit authority and provenance, and stream-defined retry,
timeout, and permanent-absence outcomes.

## 7. Test matrix and non-goals

The matrix includes exact scalar spelling, names, tail flags,
provenance, scope, duplicate parameters, nil fill, extras dropped, control
flow, streams, effects, continuations, macros, malformed images, and all
existing parity/content/completion execution fixtures. It also includes
alpha-equivalent binder renames with equal image hashes, exact-spelling and
tail-flag differences with distinct hashes, free-name changes, stream transfer,
and cross-host code-image bytes.

Non-goals are equality saturation, a new JIT, global distribution beyond B6
and B7, and any change to the merged dormant projection or its fingerprint.

### 7.1 Prior art: Unison's runtime

Unison's published runtime documentation describes let-rec minimization,
lambda lifting, ANF, an IR with De Bruijn indices and stack positions, and
decompilation. The supplied source listing is reported to contain runtime
components including ANF, MCode, Machine, Serialize, Decompile, and
Canonicalizer. These facts support architecture B as an analogy:
the executable form is derived from a full-information term and uses explicit
stack positions, rather than being lowered from a lossy identity projection.
They do not establish that Unison executes its exact stored identity form.

The named linearizer already supplies a deterministic stack-oriented image.
Lambda lifting, ANF, and let-rec minimization are therefore not adopted by
this design: lambda lifting would change closure capture into extra ordinary
parameters, and the resulting calling convention is a separate optimization
contract. They remain possible upstream AST-to-AST stream stages as described
below. Unison's decompilation
to displayable terms is not assumed here to preserve exact source datoms, tail
flags, tempids, or provenance. This design infers no stronger decompilation
property from those sources.

In this project, lambda lifting or ANF may be separate AST-to-AST stream
stages upstream of both lowerers. Such a stage reads and writes named
`:yin/*` datoms, is optional, and changes arity. Synthesized parameters need
side-table entries. It must write `:yin/tail?` on every node it creates,
because both lowerers copy that flag and infer nothing. Lifted and unlifted
programs must be compared under an explicit arity caveat, and the B0
normalizer applies to whichever named AST the lowerer receives. This VM
neither depends on nor forbids those stages.

The verified identity documentation is:

    https://www.unison-lang.org/docs/the-big-idea/
    https://unison-lang.org/whats-new/writeup-of-our-first-unison-meetup

The verified runtime documentation and source directory are:

    https://raw.githubusercontent.com/unisonweb/unison/trunk/
      unison-runtime/src/Unison/Runtime/docs.markdown
    https://github.com/unisonweb/unison/tree/trunk/
      unison-runtime/src/Unison/Runtime

UNVERIFIED here: evaluation as a documented pipeline stage, the detailed
component listing, whether the runtime input is the stored identity artifact,
reference resolution, MCode and Machine semantics, and whether
`docs.markdown` is current.

### 7.2 Committed B6 and later B7 linker topology

Reference-by-hash is a stream process, not hidden VM machinery. B6 is the
committed closed-image fetch path. It consumes executable images and a
value-held name environment, uses only H as identity and request key, emits
verified image requests, consumes image bytes, and emits a linked image or an
explicit refusal. This follows the axiom that
all IO and data flow through append-only streams. No global mutable registry
is introduced, and no direct function-to-function call crosses the boundary.
The boundary is `dao.stream`; local and remote resolution are the same
transport-neutral mechanism, and their physical placement is not a linker
decision.

`yin.vm.content` stores and fetches bytes. B hashes received canonical wire
bytes before decoding, then verifies H with `image-hash`, not by treating
`jing/segment-key` as H. `load-vector`
and `:code-aliases` remain named-VM machinery. B6 does not require persistent
publication or discovery: an observer or peer wired by the composition supplies
the response stream.

A linked dimension may contain `:call-hash H` once B7 emits it. If H is
unavailable, the linked interpreter parks its explicit continuation and emits a
REQUEST value carrying H. A response stream carries canonical image bytes or
a qualified absence/unsupported outcome. B hashes the wire bytes before
decoding, rejects mismatches, runs the receiver closure check, loads only
verified bytes, and resumes. The request token, parked continuation, and
response are data; no callback is retained. Gaps, timeouts, and permanent
absence remain explicit stream events.

B7 supplies dependency closure for free names. Mutually recursive definitions
form one strongly connected component, whose members are ordered by canonical
image hash before the component hash is computed. A name environment is a
value or stream with a declared ledger, trust, and provenance. Streams provide
failure events but do not choose retry, timeout, or permanent-absence policy.

This does not require a semantic change to B0 through B5. B3 already makes
loading and continuation state explicit, and B4 carries park and resume as
data transitions. The VM accepts loaded images only as values in its state and
never invokes a loader; absence is a park plus a request emission.

Completion for B6 requires closed-image fetch by H, verified cross-runtime
execution, malformed or missing-address diagnostics, and explicit absence and
unsupported outcomes. B7 additionally requires cycle-safe manifests and
dependency closure. Neither modifies the merged dormant projection or
establishes any property of the Unison runtime, which is UNVERIFIED here.

### 7.3 Lossless source view non-goal

The VM does not build a second lossless record DAG. Named datoms remain the
lossless source, and lexical positions, binder diagnostics, and provenance are
derived into the executable image as needed. A separate inverse view would
require its own design and is outside B0 through B7.

If a future coarser semantic-deduplication identity is wanted, it must be
derived from the executable image by hashing a normalized view with canonical
scalars and tail flags dropped. It must not reuse or equal the dormant pinned
projection fingerprint; it would be a new derived value and a separate
decision.

## 8. Risks and owner decisions

Risks include divergence from front-end tail flags, exact scalar spelling,
named-VM environment leakage, malformed bound operands, source-map loss, and
UCF's deferred transport contract.

DECIDED:

1. D1 normalizer: adopt recursive ex-data normalization, closure type and
   arity comparison, continuation and parked type comparison, stream/cursor
   ids, host-handle-free stores, and telemetry exclusion. This serves
   interpretation parity without inventing hidden state.
2. D2 lossless DAG: drop it from B0 through B7 and make it a non-goal. Named
   datoms remain authoritative; lexical positions and diagnostics are derived.
   This serves "derive, don't persist" and removes redundant authority.
3. D3 descriptor: adopt the arity, slots, raw-Bytes encoding, scalar domain,
   and lift to `:yin.code/*`. Unsupported host classes refuse before running.
4. D4 environment leak: `yin.vm.semantic` writes the callee's merged env into
   `:env` on return and park (`semantic.cljc:285-293, 317`); `vm-load-program`
   does not reset it (`semantic.cljc:679-690`), while `vm-eval` restores the
   initial env (`semantic.cljc:779`). Treat this as a separate named-VM defect
   outside these phases; retain the fixture restriction until fixed. The fix
   requires its own design.
5. D5 hash identity: `:call-hash H` names the canonical executable image hash
   of a definition unit; a recursive component is one hashed unit. H is not a
   projection identity.
6. D6 linker principles: the name environment is a value or stream, trust and
   provenance are composition policy, SCCs hash as units with canonical member
   ordering, and retry, timeout, and absence are stream events. The boundary
   is always `dao.stream`, whether local or remote.
7. D7 end state: both VMs coexist. B3 reports benchmark numbers only; the
   semantic VM is never retired without its own design.
8. D8 committed B6: closed images are fetched by H over `dao.stream`, verified
   with `image-hash`, and loaded only after verification. This directly serves
   invariant I.
9. D9 H function: B1's single `image-hash` function hashes the descriptor
   hash, including the lowering-contract version, and the canonical positional
   vector. Jing addresses are storage locations, not H.
10. D10 scalar classes: classes are derived from hashed constant tags; a host
    lacking one refuses before execution. No redundant class manifest exists.
11. D11 free names: closed means the receiver closure check passes against
    its primitive/module tables with no free-env or store shadowing. B6 refuses
    `:unresolved-free` and `:shadowed-free`; an image with no free operands
    passes trivially.
12. D12 sharing identity: H is the only identity on the sharing path. The
    merged projection remains dormant, untouched, and has no new dependents.
    This serves invariant I and "derive, do not persist" because H is already
    alpha-invariant for binder names.
13. D13 dependency order: B6 depends on B1, B2, and the lift, not B3 or B4;
    the existing semantic VM can execute a fetched image after lifting. This
    serves invariant I by making sharing available before the new VM kernel.
14. D14 wire verification: receivers hash canonical received bytes before
    decoding, and the descriptor hash includes the lowering-contract version.
    This serves content integrity and host-boundary safety.
15. D15 receiver closure: free names are derived by scanning operands and are
    accepted only when primitive/module resolution is unshadowed. This serves
    explicit state and prevents same-H semantic drift.
16. D16 responder ownership: B6 responders hold an explicit H-to-bytes value
    or query an H-to-address datom selected by composition. This serves the
    no-global-state and no-callback invariants.

DEFERRED:

- B6 request/response schema and contract stamps.
- B7 dependency-closure and SCC manifest mechanics.
- The exact retry, timeout, and permanent-absence event vocabulary.
- A named-VM environment-leak fix design, which owns D4's release condition.
- If the merged projection is ever retired, move the public `resolve-name`
  helper into this design's namespace before removal. Dormant means kept, not
  removed.

No owner decision blocks B0. B0 starts with D1 and the frozen parity corpus.

## 9. What this design must not change

The AST schema, AST walker, named emitter, named linearizer, semantic VM,
existing `:yin.code/*` dimension, `dao.stream` protocols, lease layer, waitset,
named storage, and the merged projection namespace and pinned fingerprint
remain unchanged. The projection is dormant and has no new dependents. The
executable encoding and VM are additional artifacts.
