# yin.vm engine: the VM seam

Status: contract of the existing `yin.vm.engine` namespace as of 2026-09-22,
plus three additive engine edits proposed for B4 of
`yin.vm.debruijn.stack.md`. No protocol is introduced; see section 6.

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

`resume-from-run-queue` asserts that a ready entry's `:store-updates`
carries only keyword keys, the ones the engine mints, and refuses any
other key with `{:rule :store-update-key}`.

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
popped, `:store-updates` merged, `:blocked?` and `:halted?` cleared (from
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
    :value  :status  :store-updates  :cursor
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
  port; `yin.vm.semantic.md` section 3.5 states the rule the walker does not
  yet follow. Harmless, and B4 must not copy it.
- `ready-for-ingress?` speaks the named register vocabulary (section 1).
- The semantic VM's private `response-wait-entry` becomes redundant with
  edit 3 above.
