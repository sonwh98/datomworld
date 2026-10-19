# yin.vm engine: the VM seam

Status: contract of the existing `yin.vm.engine` namespace as of 2026-09-22,
plus three additive engine edits proposed for B4 of
`yin.vm.debruijn.stack.md`. No protocol is introduced; see section 6.

2026-10-19 correction note: In the 2026-09-22 body below (§1.1, §2.1, §3),
historical `:store-updates` / `{:rule :store-update-key}` wording has been
corrected to match current `src/cljc/yin/vm/engine.cljc`: ready entries
carry `:resource-updates` merged into private `:resources`, refusing
non-keyword keys with `{:rule :resource-update-key}`.

2026-10-19 design extension: the extension below specifies the in-instance
engine representation, wasm implementation of dao.stream, and shared K0/W0 conformance
method. Register fixtures/state live in [yin.vm.debruijn.register.md](./yin.vm.debruijn.register.md)
§10; the compiler plan is [yang.wasm.md](./yang.wasm.md).

`yin.vm.engine` is the shared scheduler for every Yin VM: the dispatch loop,
wait-set polling, ready-queue resumption, park and resume records, and the
stream-effect handlers. It is VM-agnostic because every place it needs to
know a VM's register layout it takes a function argument. This document
names that seam precisely so the third VM (B4) and any later one (R4) are
written against a stated contract rather than by copying the last VM.

## 1. What the engine owns

Functions, all already VM-shape-agnostic:

    run-loop                state active? step-fn resume-fn -> state
    check-wait-set          state -> state          (poll, wake onto ready-queue)
    resume-from-run-queue   state restore-fn -> state | nil
    park-continuation       state cont-fields -> state
    resume-continuation     state parked-id val restore-fn -> state
    handle-effect           state effect {:park-entry-fns ...} -> outcome map
    gensym, handle-make/put/cursor/next/close, handle-stream-block

Bookkeeping keys the engine reads or writes on the VM state map. A VM that
maintains these gets `run-loop`, `active-continuation?`,
`halted-with-empty-queue?`, `vm-blocked?`, and `vm-value` for free:

    :blocked?  :halted?  :wait-set  :ready-queue  :parked
    :id-counter  :store  :value  :make-stream  :modules

Known vocabulary leak: `ready-for-ingress?` also reads `:k`, `:control`, and
`:bytecode`, the named VMs' register names. It is the observer's idle gate,
not scheduler machinery, and a VM without those keys that is ever placed
under `dao.stream.observer` must supply its own idle predicate. B4 does not
consume it.

The program-cache helpers (`pinned-compiled-versions` and friends) walk
`:parked`, `:ready-queue`, and `:wait-set` looking for `:compiled-version`
inside `:next`-chained frames. On a payload without those keys they find
nothing, which is correct; no VM in this repository uses the compiled cache.

### 1.1 Names and the program store (Rule R)

`yin/def` is syntax, never a name. Two engine functions carry that rule
for every VM:

    resolve-var      env store primitives registry name -> value
    store-put        store key val -> store       (refuses a reserved key)

`resolve-var` refuses a reserved name (`:reserved-name`, role
`:variable`) before it consults env or store; every executing variable
lookup of all four VMs goes through it. `store-put`, with its guard
`check-store-key!`, is the one program store write: the `:vm/store-put`
effect dispatcher, the direct store instructions of the walker, semantic,
stack, and register VMs, and all four definition transitions write
through it, and none of those transitions resolves its operator. A
program therefore cannot redirect a definition through any binding.

`resume-from-run-queue` asserts that a ready entry's `:resource-updates`
carries only keyword keys, the ones the engine mints, and refuses any
other key with `{:rule :resource-update-key}`.

Every other store write the audit detects is state construction and
sits on an allowlist, enforced by the store-write audit
(`test/yin/vm/store_write_audit_test.clj`). The allowlist is exact for
what the detector sees, and no more. The audit reads every file under
`src` as forms, every host's reader-conditional branch included, and
detects:

- an `assoc`, `assoc-in`, `update`, `update-in`, `merge`, `merge-with`,
  `into`, `dissoc`, `select-keys`, `conj`, `swap!`, `reset!`, `vswap!`,
  or `vreset!` whose argument is `:store`, whose key path contains
  `:store`, or whose target is a store: a symbol named `store`,
  `new-store`, `store0`, ..., `(:store x)`, `(get x :store)`,
  `(get-in x [:store ...])`, or a local alias of one;
- a local alias bound by `let`, `let*`, `loop`, `loop*`, `when-let`,
  `if-let`, `when-some`, `if-some`, or `binding`, directly or through an
  earlier alias, or by a `{heap :store}` destructuring key, scoped to the
  binding form's body;
- a pipeline whose threaded value is a store: `->`, `->>`, `some->`,
  `some->>`, `cond->` and `cond->>` (forms only), and `doto`. The value
  is a store from the start when the initial value is a store (any of
  the store forms above, a scoped alias included), or from the first step
  that moves onto one (`:store`, `(:store)`, `(get :store)`,
  `(get-in [:store ...])`); every mutation-head step from then on is a
  site, conservatively, even after a step that leaves the store;
- `as->`, whose name is a store alias from a store initial value, or
  after any step that is a store;
- every `store-put` call and every map literal with a `:store` key.

It fails on any detected site, keyed by file, top-level form, and head,
outside the list, on any listed site that is gone, and on an unreadable
file. Negative fixtures pin every form above: each mutation head and
store spelling, each binding form, each pipeline shape seeded both with
an extracted store and with a let-bound alias, and each step that moves
a pipeline onto the store.

It does NOT detect, and review must cover: a store passed across a
function boundary and written under the parameter's own name; a store
carried inside another data structure (a map, an atom, a collection
taken apart by `when-first`) and written from there; and a mutation
built dynamically (`apply`, `partial`, `comp`, a head bound to another
name). Residual fixtures pin each of these as undetected, so the stated
guarantee cannot silently drift from the code. Transients, host
interop, and macros that expand to a write are outside the detector too,
with no fixture.

The list: program writes through `engine/store-put`; engine-minted
keyword keys (stream ids, cursor ids, and cursor advance in
`handle-next` and the wait-set resolver); the ready-queue merge
(asserted keyword keys); VM construction (the FFI pair, and the two de
Bruijn constructor merges, checked); `dao.await` (checked keyword
keys); the REPL history keys `*1`, `*2`, `*3`; the continuation handoff
demo (checked); a JVM demo store transfer (`src/clj/yin/demo.clj`);
display projections in two browser demos; the waitset result and a
dao.space index handle, which are not VM program writes; and the
expander's macro store. A new store write must either route through
`store-put` or join the allowlist in review.

## 2. What a VM supplies

Exactly one VM-level function and one instruction-site builder shape.

### 2.1 The restore function

    restore : base entry val -> state

`base` is the VM state after the engine has done its part: the ready entry
popped, `:resource-updates` merged into private `:resources`, `:blocked?` and `:halted?` cleared (from
`resume-from-run-queue`, which first asserts keyword keys, section
1.1), or the parked record removed (from
`resume-continuation`). `entry` is the wait, ready, or parked record. `val`
is the value the parked instruction receives: the woken stream value, or the
resume operand.

The restore writes the VM's registers from the entry's register keys and
places `val` where the instruction after the park point expects its result.
It leaves the bookkeeping keys as `base` has them. The one exception is the
FFI two-step in section 4, which may re-park.

Every restore ever written in this repository has this signature: the
walker's `ast-walker-restore`, the semantic VM's `semantic-restore`, and
v1's `semantic-vm-restore`, `stack-vm-restore`, and `register-vm-restore`
(deleted in `d8b27a50`). The only variation is a two-arity shim
`([base entry] (restore base entry (:value entry)))` that exists because
`resume-from-run-queue` calls the function with two arguments and
`resume-continuation` with three. That is a defect of the engine's call
convention, not a VM choice, and section 7 removes it.

### 2.2 The park-entry builder

    park-entry : state effect result -> entry

Handed to `handle-effect` under `:park-entry-fns` keyed by `:stream/put` and
`:stream/next`. It returns the VM's register payload for the continuation
after the blocking instruction, merged with the transport keys the engine
reads:

    (merge registers {:reason :put   :stream-id id})
    (merge registers {:reason :next  :stream-id id :cursor-ref ref})

`handle-effect` stamps `:datom` onto a `:put` entry if the builder did not.

Builders close over instruction-site locals: the semantic VM's over
`seg pc E St K` in its hot loop, the walker's over `k env`. They are
instruction-level values, not VM-level ones, which is why no protocol on the
VM type can express them and why they stay as closures at the call site.

## 3. The entry key contract

This is the actual interface between the engine and a VM. A wait, ready, or
parked entry is the union of two disjoint key sets.

Engine-owned keys, read or written by the engine and `dao.stream.waitset`:

    :reason  :stream-id  :cursor-ref  :datom
    :value  :status  :resource-updates  :cursor
    :type  :id                           (parked records only)

`:stream` may appear on a woken entry transiently and is dropped before the
entry reaches the ready queue.

FFI keys, owned by the `yin.vm.ffi` convention: `:call-id`, `:request-sent`,
`:op`.

Every other key is the VM's register payload. The engine never reads,
writes, or removes it, and it survives park, poll, wake, and resume
verbatim. A VM's register keys must not collide with the sets above.

Payloads in use or planned:

    +-----------------------+-----------------------------------------------+
    | VM                    | Register payload                              |
    +-----------------------+-----------------------------------------------+
    | yin.vm.ast_walker     | {:k :env}                                     |
    | yin.vm.semantic       | {:segment :pc :env :stack :k}                 |
    | yin.vm.debruijn.stack | {:segment :pc :frames :stack :continuation}   |
    |   (B4, planned)       |                                               |
    | register kernel (R4,  | its own; same rules                           |
    |   gated, hypothetical)|                                               |
    +-----------------------+-----------------------------------------------+

Two value tags are also shared, because the B0 normalizer compares
continuations and parked records by type only: a reified continuation is
`{:type :reified-continuation ...registers}` and a parked record is
`{:type :parked-continuation :id ...registers}` (the engine adds the tag and
id in `park-continuation`).

## 4. The FFI two-step

An `:ffi-call` parks, appends a request, and then waits twice: first, if the
call-in stream was `full`, as a writer retrying the request; then as a reader
on the call-out cursor for the correlated response. Two entry shapes result:

- A woken `:request-sent` writer must not resume. Its registers are re-parked
  as a call-out reader with `:call-id`, and the machine stays blocked.
- A woken `:call-id` reader unwraps its value with `ffi/call-result`, drops
  its record from `:parked`, and resumes with the unwrapped value.

The two live VMs place this logic differently. The semantic VM handles both
cases inside `semantic-restore`, keyed on top-level entry keys. The walker
handles them in its continuation dispatch, keyed on frame types
`:dao.stream.apply/request-sent` and `:dao.stream.apply/eval-call` that
`ffi/call-response-wait-entry` builds into `:k`. Both are correct; they are
two placements of the same fifteen lines.

B4 uses the semantic VM's placement. The stack VM's continuation is a vector
of return frames, not typed CESK frames, so the walker's placement does not
fit it. Under the section 3 rule the re-park is register-agnostic: the
response reader is the writer entry with `:request-sent`, `:op`, and `:datom`
removed and the call-out reader keys added. Section 7 adds that helper.

The two-step is not lifted into the engine now. Doing so would require the
walker to abandon its frame-typed placement, which is a refactor of a working
VM that B4 does not need. It is the one piece of genuinely duplicated logic
at this seam, and it becomes a three-instance extraction candidate once B4
exists. The section 7 helper makes that extraction mechanical.

## 5. Glue that is pure duplication

Each VM today writes, in addition to its restore and its builders:

    +----------------------------+-------------------------------------------+
    | Per-VM glue                | Why it exists                             |
    +----------------------------+-------------------------------------------+
    | private resume-from-run-   | engine has no form with restore-fn bound  |
    |   queue wrapper (1 line)   |                                           |
    | scheduler-round: check-    | engine has no composed form; the walker   |
    |   wait-set then resume     | inlines it, the semantic VM names it      |
    | two-arity restore shim     | engine calls restore with 2 and 3 args    |
    | X-run-scheduler: run-loop  | fine; stays (it binds step-fn, which is   |
    |   with step-fn bound       | genuinely per-VM)                         |
    +----------------------------+-------------------------------------------+

The first three are eliminated by the section 7 edits. What remains per VM
is exactly what must be per VM: the restore, the builders, and a one-line
runner binding the VM's step function.

## 6. Why not a protocol

The question was whether to formalize the restore-fn and park-entry contract
as a protocol, a multimethod, or a required key on the VM state map.

The answer is no, and not because there is only one instance to generalize
from. That premise is false: two VMs use this seam in production today
(`yin.vm.ast_walker` and `yin.vm.semantic`), three did in v1 (semantic,
stack, register, all against a v1 engine of the same shape), and in none of
the five did the restore signature vary. The 3-VM case this proposal worries
about was already run, in this codebase, on the bare-function convention,
without a protocol, and the convention held. That is a demonstrated outcome,
not a prediction.

What the evidence shows is that the VM-specific surface is one function of
fixed signature plus builder closures that no VM-level dispatch can reach. A
protocol would replace one positional argument with type dispatch, add an
`extend-type` obligation to every VM, and break the engine tests' plain-map
states and fake restore functions, in exchange for nothing the argument does
not already do. A `:yin.vm.engine/restore` key holding the function on the
state map is worse: it puts a closure in VM state, which the v2 port
specifically removed ("a ready entry holds registers, ids, and values, never
a closure") so that a blocked machine survives an EDN round-trip.

The v1 to v2 history points the same way. v1 threaded `restore-fn` into
`handle-effect`, `check-wait-set`, and `run-loop`, and wrapped entries as
runtime tasks. v2 deleted all of that and left restore dispatch in exactly
two engine functions. The engine has been getting less abstract at this
seam, on purpose. `datom.world.md`: interpretation over abstraction, simple
data over rich types.

What was missing is not a mechanism but a statement: which keys the engine
owns, what the restore signature is, and the two composed forms every VM
was writing by hand. This document is the statement; section 7 is the two
forms.

## 7. Proposed engine edits (B4's file box)

All additive, all inside `src/cljc/yin/vm/engine.cljc` and
`src/cljc/yin/vm/ffi.cljc`, none changing an effect rule or an outcome:

1. `engine/scheduler-round [state restore-fn]`: `check-wait-set`, then
   `resume-from-run-queue` with `restore-fn`, returning the polled state when
   nothing woke. This is `yin.vm.semantic/scheduler-round` moved verbatim
   with its restore made a parameter.

2. One restore arity. `resume-from-run-queue` calls
   `(restore-fn base entry (:value entry))` so that both engine call sites
   pass three arguments. The existing two-arity shims in the semantic VM and
   the walker keep working and may be deleted later; the fake restore in
   `test/yin/vm/engine_test.cljc` gains a third parameter.

3. `ffi/response-wait-entry [entry call-id]`: the call-out reader entry for
   an arbitrary register payload, built by removing `:request-sent`, `:op`,
   and `:datom` and adding `:call-id`, `:reason :next`, the call-out
   `:cursor-ref`, and `:stream-id`. `ffi/call-response-wait-entry` (the
   walker's frame-typed shape) is unchanged.

Two tests: `scheduler-round` returns the polled state when nothing wakes and
the restored state when something does; `response-wait-entry` preserves an
arbitrary register payload verbatim.

B4 consumes all three. The semantic VM and the walker are not migrated in
B4: they are B4's parity oracles and must not move while B4 is measured
against them. Migrating them afterwards is a separate, behavior-preserving
commit (delete `scheduler-round`, `resume-from-run-queue`, and
`response-wait-entry` from `semantic.cljc`; delete `resume-from-run-queue`
and the inline round from `ast_walker.cljc`) and is the owner's call.

## 8. Findings outside B4's scope

- `yin.vm.ast_walker` passes `{:restore-fn ast-walker-restore}` into
  `engine/handle-effect` at six sites. The v2 engine destructures only
  `:park-entry-fns` and ignores it. This is v1 plumbing that survived the
  port; the deleted v3 design's section 3.5 (park/resume rule; now
  `yin.vm.semantic.md` §5) states the rule the walker does not
  yet follow. Harmless, and B4 must not copy it.
- `ready-for-ingress?` speaks the named register vocabulary (section 1).
- The semantic VM's private `response-wait-entry` becomes redundant with
  edit 3 above.

---

## Extension (2026-10-19, design only): the in-instance engine and wasm implementation of dao.stream

Status: design, owner-authorized planning; not implemented.

This extension retains the five-round r5 consensus (gpt-6-astra author,
gemini-4-argon reviewer, APPROVE on r5, 2026-10-18) where not superseded.
**Owner boundary clarification, 2026-10-19:** engine-in-guest replaces the
body-lane-first service architecture. §E.1/E.2 synchronization and ownership
now operate inside wasm; §E.3 points to the extracted implementation; §E.4 hosts only
stream adapters outside; §E.5 retains the shared K0/W0 oracle method.
The historical rename and boundary/taxonomy supersessions are recorded only in
[datom.world.abi.md](./datom.world.abi.md) §4. Deferred W6 engine migration
remains superseded by the in-instance architecture.

Authorities: [datom.world.md](./datom.world.md), [dao.stream.md](./dao.stream.md),
[yin.vm.rust.md](./yin.vm.rust.md), [yin.vm.linker.md](./yin.vm.linker.md).
Compiler plan: [yang.wasm.md](./yang.wasm.md). Register instance/fixtures/gates:
[yin.vm.debruijn.register.md](./yin.vm.debruijn.register.md) §10.

### E.1 The machine-state-as-data boundary and synchronization protocol

A guest machine's execution state materializes as immutable named-field data
inside the instance. The task-owned Value arena and engine state live in wasm
linear memory; compiled bodies are execution caches over that state. Separate
tasks have isolated arenas, even when they share cached compiled Modules. A
supplied coremem must preserve allocation ownership and task isolation.

The CLJS oracle remains a persistent record with ordinary map keys. The Rust
engine has equivalent immutable named-field snapshots, not Clojure records.
This is an **internal bodies↔engine synchronization discipline**, not a host
message or a production state-install import. Test/inspection entry points must
exercise old-state replay and supplied-state replacement without creating a
second system interface. The four-step discipline is:

1. **Engine-to-body entry:** import all declared execution fields, layout and engine state from the supplied authoritative snapshot. W1 does so unconditionally. A later cache may skip only after proving all relevant keys agree; a private token is not proof. An externally `assoc`-modified oracle fixture must have an equivalent replaced Rust snapshot. Copy or privately own the active shadow so old-state replay cannot mutate another snapshot.
2. **Body-to-engine boundary:** before any state-consuming engine helper, including effects, pinning, collection, scheduler, park/resume or modules, materialize all declared execution fields onto the engine Machine/state record. Do the same at step/run/restore/inspection returns. Equivalent `gc-roots` dispatch must inspect those current fields; synchronizing only the dense array is insufficient.
3. **Engine completion:** its returned state becomes authoritative, including every extension key. Apply only the opcode's specified post-helper changes, then reload locals/shadow. Never overlay a stale header. Preserve post-effect `:value`, resources, heap/GC, stores, counters, queues and custody. A plain non-tail primitive updates destination/pc without automatically replacing `:value`.
4. **Site coordinate:** expose the absolute instruction site unless the source transition explicitly advances it first (FFI parking). Capture at that site; apply the opcode's pc update once. Internal pending-operation scratch is not a completed instruction.

This is also a GC barrier: `:cell/new` may collect *inside* `handle-effect`.
Execution fields and roots must be current before entry. Arena retention cannot
substitute for the engine's exact logical heap tracing rules.

The program store is logically an immutable map, usually name-keyed but **not
restricted to names**: `:store-get`/`:store-put` accept `:data` keys,
including keywords and numbers in the corpus. Its linear-memory
representation is a root `ValueRef` to the in-instance engine's persistent map, not a
second wasm hash map; all access and updates use direct in-instance helpers. All writes go through
`engine/put-active` → `engine/store-put`. Non-nil `store-of` selects only
that module's store, with no ambient fallback. Private `:resources` and heap
are distinct from the program store.

The engine state roots retained across compiled-body and engine synchronization
include `ValueRef`s/tokens for `store`, `module-stores`, `free-env`,
`primitives`, `modules`, `wait-set`, `ready-queue`, `parked`, `resources`,
`heap`/`gc`, `owner`, `origin`/`ancestry`, `id-counter`, `:yin.k/gate`,
`:yin.k/issued`, and `:yin.k/closes`, preserving all other extension keys as
well. The actual issue-counter key is `:yin.k/issued`, distinct from
`id-counter`; do not invent `:yin.k/next-issue`. These are state/arena
retention roots, not an expansion of the engine's logical heap-GC root set.
The full existing state map is authoritative for engine extension keys.

### E.2 The in-instance value arena and two repository codecs

#### E.2.1 Task-owned handles and the internal handle↔Value facade

Compiled operands are `i32` ValueRefs: 0=nil, 1=false, 2=true; every other
integer, float, string, symbol, keyword, collection, primitive, closure,
continuation and sealed stream/cursor reference occupies a checked arena slot.
Only nil and false are false. No NaN boxing or externref is used. Numeric
semantics, metadata, equality and exact carriers come from the shared engine
crates and match the CLJS oracle, not Rust or JS default coercions.

The arena is **inside wasm linear memory**, owned by the in-instance engine.
The descriptor table holds kind, generation, task-local slot and optional
machine-record offset. The internal handle↔Value facade indexes the shared
`yin-value` Values consumed by `yin-kernel`; it is not a second Value port.
K-D2 specifies the 16-byte `#[repr(C, u8)]` enum with safe `Rc` ownership,
explicitly not NaN-boxed (`yin.vm.rust.md` §4.1 and §11 K-D2).
W0/W1 prove the target layout/build rather than assuming native layout works
unchanged on wasm32. Safe Clone/Drop owns retention and release; no copied enum
bytes or Rust pointers cross adapters. Task ownership, bounds, kind and generation
are checked before safe engine APIs. These Values remain machine-local, not
Send/Sync; immutable admitted Images retain owned literals and Arc sharing,
materializing constants per task (`yin.vm.rust.md` §4.2 and §4.6).

Trusted constructors alone mint Closure/Continuation/effect identities. A
literal map is data regardless of keys. Returned effects pass `module/effect?`
and `engine/check-callee-effect!`. Stream/cursor references are sealed plain
maps; actual operational handles stay in private resources. Primitive functions
run in-instance; external bridge handlers observe streams in the composition.
Neither becomes a serialized function pointer. Pointer equality is not guest
equality. Round trips cover metadata, exact integers, float bits, nested values
and trusted wrappers.

Internal representation may hold values that neither repository codec admits
(e.g. live primitives or lone-surrogate strings); retain or refuse them under
existing predicates. The implementation never makes them portable. Canonical Jing
encoding decides wire carriers, NaNs, integral floats and negative zero; no
private TLV framing or private normalization rule survives the owner ruling.

#### E.2.2 Two repository codecs and mandatory hashing

The same in-instance crates implement both existing repository codecs. Neither
may be replaced by the other or by a implementation-specific encoding.

| Domain | Authority / obligation | Observable products |
|---|---|---|
| Register scalar/image encoding | `debruijn_code.cljc` `scalar-class`, `encode-scalar`, `framed`; `debruijn_register_code.cljc` `encode-register-image`, `descriptor-hash`, `register-hash` | R, layout checks, continuation self-consistency. |
| Jing canonical CBOR | `dao/jing/cbor.cljc` `encode`/`decode`; `dao/jing.cljc` `canonical-bytes`, `content-hash`, `segment-key` | Stream-adapter bytes, owner, seals, profile, module/semantic addresses. |

Port executable framing exactly: class tags and eight-hex-digit **UTF-8 byte
length** prefix content; some content is hex-encoded numeric bits, while
string content is the actual string. `jing/blake3` hashes the UTF-8 bytes of
the resulting **string**, not hex-decoded bytes. R is BLAKE3 of the string
concatenation of descriptor-hash text and encoded-register-image text
(`debruijn_register_code.cljc:258–271`). Do not replace it with a CBOR hash
or hash a handle representation. Preserve descriptor material, body order, tuple
operand order, exact scalar classification, no NFC, unsupported-value rules,
numeric endian rules and canonical-key collision refusals
(`debruijn_code.cljc:198–203, 224–242, 350–378, 410–465`). The native backend
matches the admitted CLJS scalar profile where that host rejects JVM-only
scalar classes; broader Jing integer support does not silently widen
register-image admission.

Port Jing's actual canonical CBOR profile, not a generic library's defaults:
keyword/symbol/list tags, exact integer/bignum, float64 carriers,
decimal/rational forms, collection ordering, metadata stripping/validation,
equality-collapse and duplicate checks, depth limits, surrogate refusals and
decode/re-encode identity. In-machine values may retain a lone UTF-16 surrogate; it remains unhashable
by either repository codec. Keep that domain distinction and the existing qualified refusal.
`jing/content-hash` digests canonical CBOR bytes directly and returns
lowercase hex; `jing/segment-key` applies the exact
namespace/algorithm/digest spelling. The default here is BLAKE3; any
advertised alternative algorithm must reproduce the repository registry's
behavior rather than aliasing BLAKE3.

Required formulas are the actual inputs in source: owner = content-hash of
`[:yin.k/owner secret]` (nil secret → nil owner); seal = content-hash of
`[:yin.k/seal secret kind id]` with `issue-ref`'s missing-secret refusal;
primitive profile = content-hash of the exact description in
`vm/primitive-profile`, followed by the `:yin.k.pp/blake3-…` keyword
construction; module address = segment-key of the manifest. Preserve A's
existing canonical construction as well. Equal test secrets, identities and
input data must yield **bit-identical** R, owner, seal, profile and address
across CLJS and Rust. Do not normalize these fields away or let the in-instance engine trust supplied digest strings instead of recomputing them.

Assign executable scalar encoding to `yin-scalar`, image/descriptor/R and
admission to `yin-image`, Clojure values/equality to `yin-value`, primitive
profiles to `yin-prims` with engine composition, and Jing payload/content
serialization to `yin-wire` using the canonical Jing implementation. The
internal facade calls these same crates for identity bytes and hashes. These are the crate responsibilities in
`yin.vm.rust.md` §9, extended for the current
contracts; never duplicate codec ports inside each runtime wrapper. W0
freezes/verifies their oracle vectors before execution gates; W1 proves the
shared implementations run inside wasm. Reuse `test/resources/dao/jing/cbor-v1.json` and its README/errata plus
`test/dao/jing/cbor_conformance_test.cljc`,
`test/dao/jing/hash_registry_contract_test.cljc`,
`test/yin/vm/debruijn_code_test.cljc` and
`test/yin/vm/debruijn_register_code_test.cljc`; do not regenerate existing
goldens. Add cross-embedder vectors for descriptor-hash/R (including empty and
concatenated images), exact encoded bytes, owner/seal/profile inputs and
manifest addresses. W1 verifies load/primitive profiles, W2 recomputes
continuation hashes, W3 checks every minted reference seal, and W4 checks
compose/attach/layout/module addresses and received slices. These are in-instance obligations independent of the stream implementation.

#### E.2.3 Arena collection, GC root discipline, and snapshot isolation

No collection runs while a guest holds unreported local values. Initially run
arena collection only after full materialization at an exit; engine heap
collection may run inside a in-instance engine helper after the mandatory state-record barrier in
§E.1; later dirty-slot spilling can replace write-through with a separate
GC/inspection barrier. Mark the guest's materialized execution fields (for
register machines, see [`yin.vm.debruijn.register.md`](./yin.vm.debruijn.register.md)
§10.1.2), `value`, `parked`/`wait-set`/`ready-queue` entries, `store`,
`module-stores`, engine heap and pinned/outgoing values, constants, and
pending mailbox arguments. Traverse trusted wrappers via `values/payload` and
preserve the kernel's `module/gc-children` implementation and its treatment
of code. Integrate `engine/pin-refs` for arguments leaving through FFI. Keep
in-instance arena ownership and the existing logical heap GC distinct, with
explicit roots connecting them. Reclaim only unreachable implementation
storage, not stream history or persistent datoms. Avoid handle reuse
initially; later reuse needs generations checked at every internal handle lookup.

External state inspection produces immutable named-field snapshots. Resuming one
snapshot cannot mutate another snapshot's frames, values, or state fields.
Persistent snapshots share immutable arena objects, never a mutable guest
execution buffer. The first implementation may copy the active state shadow
at every public IVM transition; optimize only after snapshot-alias tests pass.

### E.3 Wasm stream implementation reference

The adapter surface, import signatures, outcome encoding, canonical Jing bytes
and buffer discipline live in [dao.stream.wasm.md](./dao.stream.wasm.md) §§1–3.
Owner decomposition, 2026-10-19: the former §E.3 content moved there unchanged;
§E.4 and §E.5 retain their numbers. Boundary doctrine and supersessions live in
[datom.world.abi.md](./datom.world.abi.md).

### E.4 Composition adapters and the single in-instance engine

#### E.4.1 Execution and embedding responsibilities

Node/browser, Wasmtime and Wasmer provide the same stream implementation ([dao.stream.wasm.md](./dao.stream.wasm.md)).
The composition hosts the imported adapters, their underlying stream
implementations and bridge interpreters;
it does not implement resolve-var, primitives, stores, GC, continuations or the
scheduler for wasm. A WASI-based composition may supply stream implementations through these
adapters; the VM core imports no WASI. There is no JS dependency in native runs.
The CLJS engine is the reference oracle, not a second production wasm engine.

The in-instance engine owns run-loop/scheduler-round, step budgets, ready/wait
queues and terminal checks. A step performs exactly one guest instruction; run
keeps the original ready-queue/terminal ordering and returns on the same blocked,
parked or halted states. Blocking is a normal result. Inspection/test snapshots
are immutable data, not an external engine service contract. Runtime start/drive
exports may only enter the in-instance driver; they are lifecycle controls, not
application callbacks. No wasm stack survives a wait.

#### E.4.2 Single-engine-port rule and Rust reconciliation

The production engine **is** the Rust design's `yin-value`, `yin-scalar`,
`yin-image`, `yin-kernel`, `yin-prims`, `yin-engine`, `yin-wire` workspace
(`yin.vm.rust.md` §9), compiled to
`wasm32-unknown-unknown` and linked with compiled register bodies. The internal
handle facade adapts values, never reimplements semantics. Native wrappers
implement only stream adapters and runtime mechanics. No parallel engine port
is permitted to bypass a crate's missing wasm support.

“One transition implementation, two dispatchers” applies to the Rust interpreter,
compiled wasm bodies and future LLVM helpers (`yin.vm.rust.md` §7.4 and §12 risk 2).
Generated control flow uses shared transition helpers for call/return/restore/
effects; no wrapper duplicates them. CLJS necessarily has a language-port
implementation: it remains the shared-kit oracle, not literally the same binary.
Check-then-commit, refused-step equality and per-machine fault containment remain
mandatory (`yin.vm.rust.md` §§5.3–5.5). Panics are kernel defects,
not success outcomes. W0/W1 must prove trap containment with this wasm profile;
never rely on wasm exceptions or unwinding. Safe checked slices/Value APIs and
the Rust design's reviewed boundary crates retain the unsafe-code boundary
(Rust engine §9).

Rust revision 2's design-only status is unchanged. This wasm plan supersedes its
DEFERRED wasm artifact item (Rust engine §11 Deferred), not the K3 review gate for K4 or
unrelated standalone-node/transports work. The required K-phase library subset
is now a **wasm build dependency**, including shared engine work by W3–W4.
It does not authorize a separate full K5 node. A remains sole code identity;
R is executable checksum despite older Rust topology wording (Rust engine §§3, 7.5). u32 Rust indices retain checked host limits (Rust engine §4.2 and §11 K-D2b)
against 64-bit stored pc coordinates, never truncation. Rust public step still
interprets (Rust engine §5.3); generated one-step mode is a separate dispatcher.
Shared-reference traversal stabilization below remains an explicitly scoped
wasm prerequisite outside the Rust phases' CLJC-edit exclusion (Rust engine §§1, 10).

#### E.4.3 Deterministic engine traversal prerequisite

Source verification found real unsorted map/set walks that can mint ids or
choose diagnostics. Rust must not reproduce them with its default map order,
and canonicalizing only final stream bytes is too late. Introduce an
explicit **shared deterministic traversal policy in the CLJC helpers and the
in-instance engine**, then use that revised reference for wasm parity. This is a
planned semantic stabilization of previously host-order-dependent behavior,
not an already satisfied property of current source and not a wasm-only
patch. The traversal stabilization is planned work, not an implemented
property.

For currently unordered traversal sites, use canonical **Jing CBOR bytes** for
canonical keys/elements, never arena numbering or encoded resource-lifting output.
For internal values outside Jing's domain, retain non-minting structural
comparison within the value implementation; W0 must freeze its type/field order
and cross-language vectors before enabling those fixtures. It is an in-memory
comparison, not a third serialized codec or a claim those values are portable.
This replaces the dissolved private-byte comparator by the owner ruling.
Prepare ordering before id-minting traversal. Retain vector/list order, ordered
exports, wait/ready FIFO and first-occurrence deduplication. Preserve existing
explicit `sort-by str` cursor/module-store order and `sort-by key` image order,
with cross-host fixtures; do not replace already-specified ordering.

| Site (existing symbols in `engine.cljc` unless qualified) | Required shared traversal change / preserved order |
|---|---|
| `encoder`, `encode-store` | Sort raw literal-map/store keys before visiting key then value; sort sets before recursively encoding. Never sort the already encoded output: `cell-for!` has already minted `:yin.k/c-N` by then. Keep vector/list and explicit export sequence order. |
| `markers` | Maintain pre-order tree walk; for maps visit values in sorted-key order (preserve the source's values-only walk), for sets sort elements, for vectors/lists preserve sequence order. No incidental host map order may determine first stream discovery. |
| `lower-resources` | Preserve sorted used-cursor ids and the direct-stream-marker pass followed by cursor-derived streams; consume canonically traversed marker sequences, deduplicate by stream identity at first encounter, then mint gensym ids. Do not reorder queues or change aliasing of repeated cursor cells. |
| `receive-module` | Keep its explicit sorted image attach order. Enumerate slice values and fresh-store values in sorted-key order before `lower-resources`; use the same order for bindings/store decoding and deterministic error selection. Preserve slice-before-fresh-store phase order and first-link-wins stores. |
| `module/module-entries`, `module/list-modules` (`module.cljc`) | Walk registry child maps in sorted-key order and return top-level module names in that order. Missing-module `:available` vectors and registry consumers no longer inherit host map iteration. |
| `resume-from-run-queue` | Keep FIFO entry selection and terminal-check ordering; sort resource-update keys before selecting the first non-keyword diagnostic. Preserve updates and merge semantics. |
| `lift-slice`, `decode-store` and adjacent consumers | Preserve ordered export input; if a collection is a set, sort before visitation. Audit downstream unordered traversal for id minting, first-error choice or observable vectors, and route those paths through this policy. |

The helpers named above exist; the ordering policy/helper itself is proposed
implementation work. It must land with shared CLJC/native regression tests
before the corresponding W3/W4 acceptance gate. This deliberately can change
old host-specific `c-N` assignments, stream ids/seals, slice-entry vectors or
diagnostic order; record those deltas as the named prerequisite, never
normalize them out. A fixed CLJS-only historical map order is not a
cross-runtime contract. Existing canonical R/Jing hash algorithms and frozen
byte corpora remain unchanged.

W0 inventories unordered observation sites and freezes order-key fixtures. W3
implements resource-update diagnostic order and validates counters/seals; W4
completes module lift/lower ordering and tests equal maps built with different
insertion histories, small and large maps spanning CLJS map representations,
nested maps/sets, repeated stream/cursor refs, multi-store modules and
multiple invalid update keys. Compare exact minted ids, seals,
`:yin.k/entries`, `:cells`, `:available`, counters and resource maps. A parity
failure here blocks the slice; it cannot be hidden as implementation-private
arena variation. The old Rust K-phase restriction on CLJC edits remains
distinct from this explicit wasm-driven shared-reference prerequisite: version
the kit after that stabilization and retain the original goldens/history, as
specified in §E.4.2 and [`yang.wasm.md`](./yang.wasm.md) §1.1's Rust-authority
reconciliation; do not silently choose Rust map order or normalize away
changed guest-visible ids.

#### E.4.4 Stream, park, custody-gate, FFI two-step, and `spawn-module` in-instance rules

**Stream and park transitions:** Stream `make`/`cursor`/`close` finish
without suspension under the current contract. `put`-full and `next`-blocked
create per-site wait entries using the guest's site builders. Advance the
guest site coordinate once, set `blocked` true / `halted` false / `value`
`:yin/blocked`, and return to the scheduler. Explicit `park` instead produces
the parked record as `value` and sets `halted` true; it is not automatically
blocked and has no `wait-set` entry. The scheduler may still have other ready
work. Resume from a ready entry uses `engine/resume-from-run-queue`: pop FIFO
entry, validate `resource-updates` keys, merge updates into `:resources`,
clear flags, and handle terminal wake diagnostics before in-instance restore. The
same errors must occur whether a stream failed immediately or after blocking.
Exported entries contain no callbacks, raw stream handles, timers, or internal
pending-operation scratch.

**Custody gates:** Retain `:yin.k/gate`, `:yin.k/issued`, `:yin.k/closes`,
entry issue numbers, and every extension key across synchronization and
public map edits. Use `vm/gate-mode` and existing engine/FFI/module handlers;
do not replace them with one generic “gate means error” switch:
- Any gate suppresses `ffi/put-request` append and returns `full`, so FFI retains its request as a `request-sent` writer. Link misses likewise park as `link-request` without minting a response cursor or appending; already-resolved module and install-join branches still follow the handler's ordering.
- Any gate suppresses autonomous link retry/response scans, FFI response polling, and `put`/`next` retries in `check-wait-set`. Gated install children are stamped/stepped only in `running` mode; ungated scheduling remains unchanged.
- `running` stream cursor creation creates the sealed reference and unminted resource cell without a stream cursor call. `exporting`/`ended` immediate observation paths refuse through the existing gate checks.
- `running` `put` uses the engine's deferred observation path and stamps a blocked writer with `:yin.k/issue`, advancing `:yin.k/issued` independently of `id-counter`. `running` blocked poll becomes `:reason :observe, :op :poll`, using the call-site `next` builder fallback; its application remains the driver's operation.
- **Current source nuance:** `running` stream close queues a record in `:yin.k/closes`, increments the issue counter, returns `nil` and does not call the stream handle. `exporting`/`ended` close refuses.

**FFI two-step, without duplicate sends:**
1. `ffi/require-call-pair!` runs before allocating an id or parking. Read args in vector order, pin references, build the boundary payload, derive `ffi/call-id` from task identity and `park-id`, and park under that correlated id. Increment the shared `id-counter` once through park.
2. Build `apply2/request` and invoke `ffi/put-request`. On `ok`, enqueue `response-wait-entry`. On `full`, enqueue payload plus `request-sent` true, `call-id`, `op`, `reason` `:put`, call-in stream id and the exact request datom. Other outcomes preserve the existing qualified error. Under any custody gate `put-request` returns `full` without appending; retain the request for the driver rather than retrying it autonomously.
3. The ungated engine retries a full writer. Its wake means the request append completed: restore converts it to a response reader, strips stale wake/writer fields through `ffi/response-wait-entry`, and stays blocked. It must neither append the request again nor advance the guest beyond the pending result.
4. On correlated response, terminal response-loss handling runs first, then `call-result` unwraps success/error, parked `call-id` is removed, and the guest destination or tail return receives the result exactly once. Use constants from `yin.vm` for reserved resources. Keep the bridge in the existing host interpreter; do not implement wasm imports named after arbitrary FFI operations.

**`spawn-module` isolation and node-local artifact-cache fallback:**
`spawn-module` creates a fresh task/memory/arena/owner/counter/resources
context, with the composition options copied by the kernel; it never borrows
the parent's mutable execution shadow or per-module store. Under the shared
artifact-cache policy (`yin.vm.rust.md` §7.5, §8.4 and §11 K-D7; [`yang.wasm.md`](./yang.wasm.md) §5;
[`yin.vm.debruijn.register.md`](./yin.vm.debruijn.register.md) §10.3), compiled
wasm binaries are disposable **node-local caches**, never `dao.jing` content
identities, refs, continuation fields or portable module payloads. A missing
or dropped artifact falls back to the in-instance register interpreter at any
materialized boundary, with no pending mutation or active guest stack.

### E.5 Conformance method, guest-agnostic acceptance gates, toolchain pins, non-goals, risks, and open items

*(Owner amendment note, 2026-10-19: By explicit owner ruling, the
conformance **method** from the former standalone kit specification is folded into this section as the acceptance specification of
the in-instance engine; the register-specific fixture schema and register
W0–W6 / Rust K1–K5 lane gates live in
[`yin.vm.debruijn.register.md`](./yin.vm.debruijn.register.md) `§10.4–§10.5`.)*

#### E.5.1 Oracle export, differential execution lanes, and trace comparison method

The K0/W0 conformance kit is the single **shared deliverable** of
[`yin.vm.rust.md`](./yin.vm.rust.md), this specification
(`§E.1–§E.5`), [`yang.wasm.md`](./yang.wasm.md), and
[`yin.vm.debruijn.register.md`](./yin.vm.debruijn.register.md) (`§10`). It is
exported from R4 and extended for wasm lowering, wasm implementation of dao.stream, and runtime conformance, not a second reference interpreter or a
second kit (`yin.vm.rust.md` §10 K0, equivalence law at §7.2). The execution specifications retain their respective
authorization boundaries; the planned LLVM lane remains behind K3 owner review
before K4. W0–W6 are the wasm slices; K0–K5 are the Rust design's phases. No
new fixture semantics are introduced. For the register machine's exported
fixture schema (canonical images, R, B0-normalized results/errors, format-level
boundary traces/K depth/saved pairs, 14 validator refusal rules, descriptor/scalar
vectors, diagnostic messages, refused-step atomicity, 7.3 tail-site table,
`>65,535`-register, host-limit, and million-iteration tail fixtures), see
[`yin.vm.debruijn.register.md`](./yin.vm.debruijn.register.md) `§10.4`. If K0
exports are not yet available, create that shared deliverable once; do not
fabricate a wasm-only reference interpreter. Record the actual reference
revision and fixture version, including the explicit shared traversal
stabilization delta (§E.4.3).

One manifest drives the **CLJS oracle and the in-wasm Rust engine under Node,
Wasmtime and Wasmer**, plus the shared native Rust interpreter and planned LLVM
lanes as their phases become available. Wasm runs the same bytes under Node and native
runtimes as gated below; all use the same scripted inputs, scalar/host-limit
declarations and expected refusals. Adapter-specific probes extend K0 as a
adapter test group without replacing format-level fixtures. The in-instance Rust interpreter and compiled dispatcher both pass each W
slice; native Rust phase gates remain independently required for their claims. B0 results and complete boundary traces remain shared; wasm
single-step traces add finer evidence, not a second oracle.

Build an explicit differential adapter rather than replacing the existing
tests' private `register-run` vars by global mutation. Parameterize/extract
shared fixtures when implementation is authorized. Each case receives
independent scripted streams with identical inputs, capability secret,
primitive registry, origin and counter setup. From W1 onward, compile each
image once and run those identical wasm bytes under **Node and Wasmtime**,
each against interpreted register after every guest instruction and scheduler
event; Wasmer must also pass the complete suite by W5. Compare canonical stream-adapter
traffic as well as full semantic traces; also compare semantic execution where
the existing A→R fragment permits it.

The generic per-fixture procedure, applied to every lane against the oracle
exports: project the guest's admitted source to its canonical form and assert the
literal goldens; derive and validate the lowerings; compile and instantiate per
lane; execute with the deterministic declared bindings (the guest specification
names them) and the genuine parked targets its resume cases require; execute every
conditional arm where applicable; and compare the same qualified refusal where a
case is unsupported. Never silently skip resume, terminal, or closure cases, and
never relabel unsupported cases as successes. B0 normalization alone is
insufficient for backend acceptance: the guest specification's raw observable
fields are compared in addition, as enumerated by the guest fixture specification
(for the register machine, [`yin.vm.debruijn.register.md`](./yin.vm.debruijn.register.md)
§10.4). Compare resources by
authenticated logical identity/cursor progress and stream emissions, not by
host object address. With identical secrets and inputs,
hash/owner/seal/profile/address strings, guest-visible ids and slice ordering
must be bit-identical. Compare canonical expanded observable-state encodings,
not pointer layouts; private arena numbers may be renamed only where they
cannot affect key ordering, id minting or guest state. Across independent
tasks use the same explicit owner inputs; never erase owner checks. Test
primitive implementations exist in the in-instance crates and CLJS oracle;
scripted transports exist in every composition with the same declared profiles; a JS function fixture must
get an equivalent native fixture, not disappear from the native run. Normalize
only private memory addresses, arena numbering and runtime object identities;
preserve all guest values, errors, `pc`s, frames, snapshots, queues, counters,
store/heap/custody changes, resource progress and emitted request order.
Compare failures by qualified rule/reason/outcome and relevant message
assertions.

The planned LLVM lane uses this same kit under Rust §7.2: interpreted and
native runs compare boundary states, ordered emissions and refusal
diagnostics. Artifact-drop fallback continues interpreted from the same
boundary. Wasm adds wasm→Rust-interpreter and wasm→CLJS-interpreter fallback
fixtures. No backend forks the validator, live-set rules or payload
vocabulary. K3 may measure wasm on the same harness; a wasm result does not
commission K4.

#### E.5.2 Codec, identity and traversal determinism gates

Both repository codecs (§E.2.2) must match frozen bytes/hashes across CLJS and
in-wasm Rust: R (including empty/combined images), owner, seal, primitive-profile
and manifest addresses are bit-identical for equal inputs/secrets. Reuse existing
Jing/scalar/image corpora without regenerating goldens. W1 verifies load/profiles;
W2 recomputes continuation hashes; W3 checks minted seals; W4 checks admitted
modules/layouts/slices. Jing alone governs adapter payload canonicalization, numeric
carriers, NaNs, negative zero, collision/refusal and decode/re-encode identity.
Internal noncanonical values keep their in-machine fidelity and existing wire
refusals; no private transport codec is required to compare them structurally.

Traversal gates (§E.4.3) compare exact `:yin.k/c-N`, stream/cursor ids/seals,
`:yin.k/entries`, `:cells`, `:available`, counters/resource maps and first-error
order. Vary insertion history, small/large CLJS map representations, nested maps/
sets, repeated references, module stores and multiple invalid resource keys.
Private handle renaming never excuses a guest-visible difference.

#### E.5.3 W0 oracle, toolchain, wasm32 and adapter gates

**Oracle first:** consume/extend the R4-exported K0 kit before trusting any new
engine or compiler. Preserve fixture schema and original goldens. Pin the
reference revision plus scoped traversal delta. W0 probes wasm32 compilability;
W1 cannot pass until the actual required Rust crates link and execute inside
wasm. An unavailable crate dependency blocks the lane; no host service fallback.

These are inherited reproducibility targets, not claims of installed/tested tools:

| Tool | Pin / scope | Evidence |
|---|---|---|
| Node | `25.6.1` | Existing `mise.toml` node pin; keep the repository selection. |
| Clojure CLI / shadow-cljs | `1.12.4.1602` / `3.3.5` | Existing `mise.toml` and `deps.edn` selections. JVM emitter uses the repository Java 21 lane; record resolved vendor/build in the W0 manifest. |
| WABT | `1.0.37` | [Release](https://github.com/WebAssembly/wabt/releases/tag/1.0.37); assemble and validate with an explicit baseline feature allowlist. |
| Wasmtime native crate/runtime | `=38.0.0` | [Versioned embedding API](https://docs.rs/wasmtime/38.0.0/wasmtime/); required native runtime from W1. |
| Wasmer native runtime | `6.0.1` | [Release](https://github.com/wasmerio/wasmer/releases/tag/v6.0.1); second wrapper and completion gate by W5. |
| Rust / Cargo | `1.90.0` toolchain | [Release](https://blog.rust-lang.org/2025/09/18/Rust-1.90.0/); in-instance engine target `wasm32-unknown-unknown`, plus runtime wrappers. |

W0 builds/probes the pins together, records checksums, target triples, Java 21
vendor/build and transitive lockfiles. Any changed pin requires an explicit
record and rerun. Binaryen is absent initially. Record browser versions on
qualification. The Rust/LLVM linker used for the engine must be pinned as part
of the Rust build; deterministic WAT emission/WABT remains the body toolchain.

Probe core wasm 1.0 plus mutable globals and original function tables: imported
memory, checked bounds/byte order, i32 calls, i64 memory fields, active segments,
call_indirect, memory growth/view refresh and shared task memory across image
components. Audit **engine dependencies and final linked artifacts**, not only
emitted bodies, against the allowlist. No WASI, reference types, bulk memory,
threads, exceptions, tail-call extension or WasmGC may slip in via Rust defaults.
Every extension is a separately probed profile/cache key. Test embedding
harnesses, not a bare CLI unable to supply imports.

W0 adapter probes run on Node and Wasmtime (Wasmer smoke probe where available;
full suites mandatory by W5): canonical request/outcome round trips, all declared
stream outcomes, handle ownership, malformed CBOR, bounds/overlap/overflow,
insufficient **pre-operation** reservation, full-before-append and no mutation
on rejected adapter calls. W3 repeats against real FFI/writer/reader paths and
proves once-only append after full. Test unknown-acceptance transport errors
without blind retry. No post-commit resize/refetch protocol exists.

Snapshot gates cover all declared fields/extension keys, supplied-state
replacement, old-state replay and immutable snapshots. GC gates force collection
inside a helper. Codec probes include both repository encodings, metadata,
float/integer carriers, canonical collisions, surrogate refusals, deep keys and
permuted handle allocation; frozen private-TLV tests are removed by supersession.

W/K map: W0=K0 oracle/probes; W1=required K1 engine/value/image builds in wasm;
W2=K2 restore plus park subset; W3–W4=required shared engine library coverage;
W5=wasm completion on Node/Wasmtime/Wasmer, not full K5; W6=codegen measurements
on the shared K3 harness, without commissioning K4
(`yin.vm.rust.md` §10). Full register slice
acceptance lives in [yin.vm.debruijn.register.md](./yin.vm.debruijn.register.md)
§10.5, compiler work in [yang.wasm.md](./yang.wasm.md) §6.1.

#### E.5.4 Non-goals and risks

No second engine/value/codec port, new register contract, universal continuation
transport, arbitrary FFI imports, WASI core, threads/shared memory, WasmGC,
NaN-boxing, cross-node artifact service or unrelated K4/full-K5 authorization.
Immediate JVM/Dart application integration and malicious network wasm are not
completion claims. W6 starts after correctness; engine-in-guest is not deferred.

Risks: wasm32/dependency feature compliance is an early hard gate; shared
shared engine crates are substantial required work. Preserve persistent snapshots over
mutable memory and logical heap roots separately from arena retention. Traps or
panics must not poison another task; ambiguous external effects cannot be rolled
back/retried as if absent. Cross-runtime buffer/handle lifetimes and dynamic
module installation need physical-runtime probes. Predicate/codec drift is caught
by the oracle kit. Measure in-instance code size, dispatch and collection costs;
none justifies restoring a second semantic service protocol.

#### E.5.5 Reference conventions and open items

CLJC helper names denote oracle semantics and shared Rust equivalents, not a
requirement to load Clojure inside wasm. Rust-design references use sections of [yin.vm.rust.md](./yin.vm.rust.md).
Adapter names in [dao.stream.wasm.md](./dao.stream.wasm.md) §1 are proposed imports.

The owner ruling resolves the former compiled-call/service split: generated
control invokes the one in-instance transition implementation. It also removes
the former response-refetch sequencing question with that protocol. Remaining
W0 gates are wasm32 build/profile feasibility, bounded response reservations,
and a frozen internal structural ordering for noncanonical values (§E.4.3).
Do not invent a third codec to answer the last question.
