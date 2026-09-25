# yin.vm de Bruijn stack VM

Status: B0-B3 implemented and merged; B4-B7 not started

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
bytes, while verification recomputes `image-hash`. H is computed using explicit
SHA-256 and is pinned by the VM format contract version; it is completely
independent of DaoJing's `default-hash-algorithm` and does not change when
DaoJing defaults to BLAKE3.

## 3. Lowering and scope

Lowering is two stages, both pure stream-to-stream functions:

    (resolve named-datoms)          -> resolved tuples + binder side table
    (lower-stack resolved-tuples)   -> canonical stack image + pc side table

`resolve` is the de Bruijn encoding of the named semantic tuples. It is the
shared upstream of the stack projection specified here and the register
projection specified in `yin.vm.debruijn.register.md`; neither projection
is derived from the other, matching the parallel-projection topology in
`docs/agents/architecture.md`. `resolve` receives complete `:yin/*` named
datoms and reuses only the public `yin.vm.debruijn/resolve-name` helper.
That helper compares names exactly, including rightmost-wins duplicate
parameters, and does not canonicalize values. Projection-only helpers such
as `index-frame`, `build-node`, and `project-node` are not reused.
Unexpanded-macro validation remains the named front end's responsibility.
The projection is therefore kept, not removed: if it is ever retired, this
public helper must first move into the resolver's namespace.

### 3.1 Resolved tuples

The named datoms are a finite graph, not a tree. `ast->datoms-with-root`
emits a node carrying a pre-assigned `:eid` exactly once and references it
from every site that names it, for every node type. One source entity can
therefore sit under two different lexical contexts, and its resolution is
then different at each: the existing projection fixture
`shared-nodes-resolve-per-their-lexical-context` places one `:variable`
entity as the body of both `(fn [x] ...)` and `(fn [y] ...)`, bound `[0 0]`
under the first and free `x` under the second. Resolution is a fact about
an occurrence, not about a source entity, so resolved tuples are keyed by
occurrence. Writing resolution facts onto the shared source entity would
give one entity two contradictory fact sets and is not permitted.

An occurrence is the pair `[source-eid lexical-context]`, where
`lexical-context` is the complete innermost-first stack of enclosing
parameter vectors, exactly the memo key the dormant projection documents.
`resolve` mints one resolved record per distinct occurrence, with a fresh
negative id assigned deterministically in first-visit order of the walk,
and reuses that record wherever the same occurrence recurs. Two references
to one entity under equal contexts share one record, so graph sharing is
preserved; two references under unequal contexts get two records. A shared
lambda under two contexts yields two resolved lambdas, each with its own
resolved body. Resolved ids never equal source ids; provenance is a side
table, below. A cyclic input is refused, as the projection refuses it.

Each resolved record is the source node's datoms with exactly two changes,
the same two changes the B1 opcode table makes to the named instruction
table. Every other datom is carried unchanged: `:yin/type`, exact scalar
values, exact free names, `:yin/tail?`, and the node's operand references,
which now point at resolved record ids.

1. A `:variable` record has no `:yin/name` datom and has either
   `:yin.resolved/depth` and `:yin.resolved/position` (a bound reference,
   from `resolve-name`'s `{:bound [depth position]}`) or `:yin.resolved/free`
   carrying the exact symbol (from `{:free name}`).
2. A `:lambda` record has no `:yin/params` datom and has
   `:yin.resolved/arity`, the parameter count.

The attribute namespace is `:yin.resolved/*`, not `:yin.debruijn/*`, which
the dormant projection owns. The side table has two maps: `:source`,
`{resolved-id source-eid}` for every record, and `:params`,
`{resolved-lambda-id [param-symbol ...]}`, the exact parameter vector of
every resolved lambda. No name is recorded per variable occurrence; a bound
occurrence's original spelling is the owning resolved lambda's parameter at
the resolved position, which `resolve-name`'s contract guarantees is exact.
Binder names appear nowhere in the resolved tuples, including in ids.

The walk is in the named linearizer's order: operator then operands left to
right, `if` test then arms, lambda bodies with the lambda's parameter vector
pushed innermost-first on the resolution stack for the body's extent. Depth
0 is the innermost enclosing lambda. The resolution stack and the occurrence
memo are the resolver's only state; the runtime frame vector's outermost-
first order is B3's, and the conversion between them is explicit there.

`resolve` refuses with a qualified diagnostic, before any lowerer runs, a
program whose bound reference falls outside its enclosing arity chain, an
unsupported node type, a host value in a data operand, or a cycle. The
resolver also exports one pure `validate-resolved` over an already resolved
tuple set and side table: shape, one resolution fact set per record, every
bound reference inside its enclosing arity chain, every operand reference
a record, and `:source` total over the records. Every lowerer calls it
unconditionally on its input, so a hand-built resolved set with
`:yin.resolved/depth 5` and no enclosing lambda is refused identically by
`lower-stack` and by the register lowerer, before either emits anything.

The inverse `unresolve` maps each record back through `:source`, restores
`:yin/name` from the owning lambda's parameters and `:yin/params` from
`:params`, and merges the records of one source entity, which carry equal
facts by construction. `unresolve(resolve x, side-table)` equals `x` as a
datom set, including the shared-entity fixtures.

The resolved tuples are a derived stage value. They have this shape
contract and namespace so that two lowerers and their tests agree on what
they consume, but no hash, no identity, and no sharing role: H and R are
the only executable identities (D9, D12), the tuples are not executable,
and "derive, do not persist" forbids a persisted copy of facts the named
datoms already hold. They are not the second lossless view D2 rejects:
never stored as a source view, never served, never a fetch key. With the
binder side table they invert to the named datoms; that inverse is the
resolver's test oracle only.

### 3.2 Stack lowering

`lower-stack` consumes resolved tuples and their side table, calls
`validate-resolved` first and refuses on its diagnostic, then emits the
canonical positional `:yin.debruijn.code/*` image (section 2) plus the
pc-keyed diagnostic side table the lift needs. It cannot call
`yin.vm.linearize/lower`, which reads `:yin/name` and `:yin/params`. It
therefore reproduces the named linearizer's flattening itself: the same
recursion in evaluation order, the same `:push` after operator and after
each operand, `:call` with argc and the copied `:yin/tail?`, `if` as
`branch-false`, `jump`, and two labels, `:halt` after the root, lambda
bodies out of line in discovery order each ending in `:return`, and labels
resolved to pcs in a second pass. Like `lower`, it expands occurrences
positionally: a resolved record referenced twice is emitted twice, at two
pcs; the resolver's occurrence memo never changes emitted bytes. A
`:variable` emits `[:load-bound depth position]` or `[:load-free name]`
straight from its resolved attributes; a `:lambda` emits `[:closure arity
body-pc]`. Every other node emits the carried mnemonic unchanged.

Layout equality with the named linearizer no longer holds by construction.
It is a required structural-comparison test: for every corpus program the
canonical vector of `(lower x)` and `(lower-stack (resolve x))` have the
same length and the same mnemonic at every pc, and differ only at `:var`
versus `:load-bound`/`:load-free` operands and at `:closure`'s parameter
versus arity operand. A layout change in `yin.vm.linearize` must fail this
test rather than silently fork H.

`lower-stack` copies `:yin/tail?` exactly as it was resolved. It does not
infer tail position from syntax. Only `:application` has a tail operand in
the existing instruction table; `:dao.stream.apply/call` lowers to
`:ffi-call` and has no tail operand.

The pc side table is `{pc {:kind :closure, :params [sym ...], :source e}}`
for every `:closure` pc, with `:params` read from the resolver side table's
`:params` by resolved lambda id, and `{pc {:kind :var, :source e}}` for
every load pc. `:source` is the source eid, read through the resolver side
table's `:source` map, never the resolved id, so it matches `lower`'s
`:yin.code/source` at the same pc. `adapt` is the composition `lower-stack`
after `resolve` and is the one public entry that takes named datoms to an
image.

The lift executes with synthesized names by default: fresh against the
image's free-name set and against each other, so no lifted binder can
capture a free name. A supplied pc side table is used only when every
`:closure` entry's parameter count equals the instruction's arity, its
names are capture-free by the same rule, and `adapt` of the lifted result
reproduces the original image byte for byte; otherwise it is discarded and
synthesis is used. This is the rule the B2 box's completion criteria
already require; it is stated here as the contract.

This removes the fused design's vector-level scope reconstruction:
`closure-body-ranges`, `layout-conforms?`, `body-owner`, `chain-of`, and
the vector-level `resolve-var` and `rewrite`. That machinery existed only
because resolution was placed after linearization and had to recover body
scopes from an already flattened vector. Scope is now known during the tree
walk, and B1's validator remains the independent vector-level check.

The executable image may carry a debug node hash and named source reference.
Those fields are always diagnostic metadata outside code identity. They replace
no named source map.

Scope validation is mandatory in both places where an image can enter:

1. `resolve` validates nonnegative depth and position against the enclosing
   arity chain at resolution time, on the tree.
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

The VM is a new `yin.vm.debruijn.stack` namespace. It may implement existing VM
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

### 4.1 Engine seam (B4)

`yin.vm.engine` is the shared scheduler for every Yin VM, and the contract
between it and a VM is stated once in `yin.vm.engine.md`: the engine owns a
closed set of bookkeeping keys on the state map and a closed set of keys on
wait, ready, and parked entries; a VM supplies one restore function of the
signature `base entry val -> state` and, at each blocking instruction, a
park-entry builder returning its register payload merged with the transport
keys. Everything on an entry that the engine does not own is the VM's
payload, preserved verbatim. No protocol or multimethod formalizes this;
`yin.vm.engine.md` section 6 records why, from the two live instances
(`yin.vm.ast_walker`, `yin.vm.semantic`) and the three v1 instances that ran
on the same bare-function convention.

B4 implements that contract as follows. Each item is a decision, not a
suggestion; the sentence "it reimplements any helper that serializes the
named register layout" above means the restore and the builders, and nothing
else.

1. Bookkeeping. `:status` is replaced by the engine's `:halted?` and
   `:blocked?`, and the record gains `:wait-set`, `:ready-queue`, `:parked`,
   `:id-counter`, `:value`, and `:make-stream`. `halted?` becomes
   `engine/halted-with-empty-queue?`, `blocked?` becomes
   `engine/vm-blocked?`, and `run` becomes `engine/run-loop` with
   `engine/active-continuation?`, the step function, and
   `engine/scheduler-round` bound to this VM's restore. `create-vm` starts
   halted with an empty program exactly as the semantic VM does.
2. Value. `value` returns `(:value vm)`, as `engine/vm-value` reads it.
   `:halt` and a `:return` on an empty continuation write the stack top into
   `:value`; a park writes the parked record and a block writes
   `:yin/blocked` (both done by the engine). B3's `(peek stack)` is
   equivalent for pure programs and stays as the halting write.
3. Register payload. `{:segment :pc :frames :stack :continuation :format
   :hash}`. `:segment` names the image as the record holds it, in the same
   role as the semantic VM's segment id; while one image is loaded it is
   that image. `:format` is `:yin.debruijn.code` and `:hash` is the loaded
   image's H: every parked record and reified continuation carries the
   model and image identity that produced it, so a continuation is never
   interpreted by a VM or against an image other than its own. These keys
   do not collide with the engine-owned set and the engine never reads
   them. Free environment, store, primitives, and modules are not
   registers and stay on the state map.
4. Restore. One function, `stack-restore [base entry val]`, which first
   refuses with a qualified `:continuation-format` outcome unless the
   entry's `:format` is `:yin.debruijn.code` and its `:hash` equals the
   loaded image's H, then writes the registers from the entry and conjes
   `val` onto the restored `:stack` (the B3 obligation that every
   value-producing opcode lands on the stack, since there is no
   accumulator). The refusal is the same-model, same-image rule the
   register design states in its section 5.1; cross-model transport is
   not a lift here and is deliberately unsupported. The FFI two-step
   (engine design section 4) lives here, on the entry keys `:request-sent`
   and `:call-id`, using `ffi/call-result` and `ffi/response-wait-entry`.
   The walker's frame-typed placement is not used: this machine's
   continuation is a vector of return frames.
5. Builders. Per blocking instruction, a `:stream/put` and `:stream/next`
   builder closing over the post-instruction registers (`pc` advanced,
   operands popped), returning the payload merged with `:reason` and the
   `:stream-id`/`:cursor-ref` from the handler's result. Nothing else is
   attached: no handle, no closure, no `:restore-fn` option to
   `handle-effect`.
6. Continuation tags. `:current-continuation` pushes
   `{:type :reified-continuation ...payload}`; `:park` goes through
   `engine/park-continuation` with the payload; `:resume` goes through
   `engine/resume-continuation` with `stack-restore`. The B0 normalizer
   compares these by type, so the tags match the named VM's.
7. Idle predicate. `engine/ready-for-ingress?` reads named register keys and
   is not consumed by this VM; if the stack VM is ever placed under
   `dao.stream.observer`, it supplies its own.

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

### B2: resolver and stack lowerer

    New: src/cljc/yin/vm/debruijn_resolve.cljc
    New: test/yin/vm/debruijn_resolve_test.cljc
    New: src/cljc/yin/vm/debruijn_linearize.cljc
    New: test/yin/vm/debruijn_linearize_test.cljc
    Existing edits: none
    Must not change: yin.vm.linearize and named lowering semantics

B2 is two namespaces (section 3). `yin.vm.debruijn-resolve` owns `resolve`:
named datoms to resolved tuples plus binder side table, its scope validator,
and the inverse `unresolve` used as a test oracle. It is shared with the
register design and depends on nothing in this design below section 3.1.
`yin.vm.debruijn-linearize` owns `lower-stack`, `adapt`, `lift`, and the
public `named-canonical-vector` helper the structural comparison uses. The
fused implementation's `closure-body-ranges`, `layout-conforms?`,
`body-owner`, `chain-of`, vector-level `resolve-var`, `rewrite`, and the
duplicate vector-level `image-scope-defect` are removed, not moved: scope
is known on the tree, and B1's validator is the vector-level check.

Completion requires, for the resolver: deterministic output on every host,
every node type resolved or refused with a named diagnostic, exact free
names and scalars, the duplicate-parameter fixture `(fn [x x] x)` resolving
to `[0 1]`, out-of-range hand-built resolved tuples refused by
`validate-resolved`, a cyclic input refused, the shared-occurrence fixtures
(one variable entity under `[x]` and `[y]` resolving bound `[0 0]` and free
`x`; the same entity under `[x]` and `[x] [y]` resolving `[0 0]` and
`[1 0]`; one entity twice under equal contexts yielding one record
referenced twice; one lambda entity under two contexts yielding two
resolved lambdas), and `unresolve(resolve x, side-table) = x` as a datom
set over the corpus and those fixtures.

For the stack lowerer: deterministic output, every node and opcode, the
section 3.2 structural opcode-by-opcode comparison with `lower` differing
only at variable and closure operands, image encode/validate/load round
trips, and `lift(adapt x, side-table) = canonical-vector(lower x)`. The lift
is a function of the image plus its pc side table. With the original binder
names in the side table, equality holds; with synthesized names, the lifted
result is alpha-equivalent to `lower x`, including `(fn [x x] x)`.
Synthesized names must be fresh against the image's free-name set. A
supplied side table is accepted only when `adapt` of its lift returns the
original image. Every image B2 emits must be accepted by B1's validator, and
every hand-built out-of-range image must be rejected by B1's validator. The
golden image bytes and H values frozen in B1 must be reproduced unchanged
by `adapt`; the refactor changes derivation, not bytes.

### B3: de Bruijn VM kernel

    New: src/cljc/yin/vm/debruijn/stack.cljc
    New: test/yin/vm/debruijn/stack_test.cljc
    Existing edits: none
    Must not change: semantic VM, engine, IVM protocols, named environment,
    merged projection namespace

Implement frames, closures, loads, calls, returns, branches, literals, and
store operations. Completion requires pure-program parity using B0's
normalizer and fresh initial environments, plus the informational section 1
benchmark report, not an acceptance condition.

### B4: effects and continuations

    Existing source: src/cljc/yin/vm/debruijn/stack.cljc
    New: test/yin/vm/debruijn/stack_effects_test.cljc
    Existing edits: src/cljc/yin/vm/engine.cljc (additive only:
      scheduler-round; resume-from-run-queue calls restore-fn with three
      arguments), src/cljc/yin/vm/ffi.cljc (additive only:
      response-wait-entry), test/yin/vm/engine_test.cljc (fake restore
      arity; tests for the two additions)
    Must not change: dao.stream protocols, lease, waitset, named effect rules,
    merged projection namespace, yin.vm.semantic, yin.vm.ast_walker (B4's
    parity oracles; their migration to the engine additions is a separate
    commit), any existing engine outcome or entry key

Implement stream operations, primitives, FFI, gensym, current-continuation,
park, and resume against the engine seam in section 4.1 and
`yin.vm.engine.md`. The engine edits are exactly the three listed in that
document's section 7; B4 adds no other engine function and hand-writes no
scheduler round, run-queue wrapper, or two-arity restore shim. Completion
requires parity for values, errors, effects, stores, stream outcomes, and
blocked states. Cross-program park/resume tests must use only
initial-environment or store-resolved free names until the named-VM
environment leak is fixed. Before a continuation is exported, B4 must either
lift frames through the descriptor morphism or run the frame-aware
completion adapter so `:yin.k/requires` is not under-approximated.

Recommended sequencing, across this design and the register design, now
that the register kernel is committed unconditionally: B4 is the pole,
because it gates the register kernel's effects tier and the cross-model
continuation format, and its parity oracles already exist. Dispatch B4
first. In parallel, on the register track: the live-set contract change,
then R2, then the register kernel's pure-program tier. In parallel, on the
linker track: B6 and R5 as one unit, which depend on neither B4 nor the
kernel. Then the register kernel's effects tier, after B4 and R2. The
continuation-format design, and its implementation, come last.

### B5: differential integration

    New: test/yin/vm/debruijn/stack_parity_test.cljc
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

### B6: committed closed-image linker over dao.jing

    New: src/cljc/yin/vm/linker.cljc
    New: test/yin/vm/linker_test.cljc
    Existing edits: none
    Depends on: dao.jing (segment-key, materialize!, get), dao.jing.dht
    (create-content-dht, IDhtNet), dao.jing.remote (the DaoStream
    transport and its server handlers), B1 (image-hash, image-defect,
    descriptor), B2 (lift)
    Must not change: merged projection namespace, image-hash, dao.stream,
    dao.jing, dao.jing.dht, dao.jing.remote, named VM semantics

B6 is specified in `docs/design/yin.vm.debruijn.linker.md`, the
standalone linker design for Phase B6, used by both the stack and register
VM for linking code over `dao.stream`. That document specifies the format-
parameterized fetch function over `dao.stream` / `dao.jing`, the address and
identity distinction, the six-step fetch protocol, the qualified refusal
vocabulary, the H and R indexes, the transitional content-hash risk, and the
completion list. Section 7.2 below remains the topology summary and D8, D9,
D11, D13, D14, D15, and D16 remain the governing decisions.

### B7: dependency closure linker

    New: src/cljc/yin/vm/linker.cljc
    New: test/yin/vm/linker_dependency_test.cljc
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

`dao.jing` stores and fetches the image as a value at its own content
address, over `dao.stream` through `dao.jing.remote`. B verifies the
received value twice, against its Jing address with `segment-key` and
against H with `image-hash`, and never treats `jing/segment-key` as H. The
integrity rule is the same as hashing wire bytes before trusting them; the
mechanics are Jing's, which hash the decoded value, and the B6 box states
them. `load-vector` and `:code-aliases` remain named-VM machinery. B6 does
not require persistent publication or discovery: the composition supplies
the Jing handle and the H index.

A linked dimension may contain `:call-hash H` once B7 emits it. If H is
unavailable, the linked interpreter parks its explicit continuation and emits a
REQUEST value carrying H. The composition resolves H to a Jing address
through its index and fetches the value, or reports a qualified absence
or unsupported outcome. B verifies the value against its address and
against H, rejects mismatches, validates, runs the receiver closure check,
loads only verified images, and resumes. The request token, parked
continuation, and response are data; no callback is retained. Gaps,
timeouts, and permanent absence remain explicit stream events.

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
14. D14 wire verification: receivers verify received content before
    trusting it, and the descriptor hash includes the lowering-contract
    version. Under the B6 Jing path this is two checks on the received
    value, `segment-key` against its address and `image-hash` against H,
    before validation or load. This serves content integrity and
    host-boundary safety.
15. D15 receiver closure: free names are derived by scanning operands and are
    accepted only when primitive/module resolution is unshadowed. This serves
    explicit state and prevents same-H semantic drift.
16. D16 index ownership: the composition holds the H index as an explicit
    value or as H-to-address datoms it queries; the content itself is
    served by `dao.jing`'s existing DHT and remote server side, and B6 has
    no responder of its own. This serves the no-global-state and
    no-callback invariants.

DEFERRED:

- B6 request/response schema and contract stamps.
- B7 dependency-closure and SCC manifest mechanics.
- The exact retry, timeout, and permanent-absence event vocabulary.
- A named-VM environment-leak fix design, which owns D4's release condition.
- If the merged projection is ever retired, move the public `resolve-name`
  helper into `yin.vm.debruijn-resolve` before removal. Dormant means kept,
  not removed.

No owner decision blocks B0. B0 starts with D1 and the frozen parity corpus.

## 9. What this design must not change

The AST schema, AST walker, named emitter, named linearizer, semantic VM,
existing `:yin.code/*` dimension, `dao.stream` protocols, lease layer, waitset,
named storage, and the merged projection namespace and pinned fingerprint
remain unchanged. The projection is dormant and has no new dependents. The
executable encoding and VM are additional artifacts.
