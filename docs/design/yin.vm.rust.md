# yin.vm.rust: the Rust implementation of the yin.vm execution stack

The whole stack — engine, register kernel, values, codecs — plus the LLVM
sibling codegen and the native dao.stream peer; it is not itself "a VM".

Status: design only; not implemented. The owner-authorized wasm plan depends
on the shared Rust crates; this document does not independently commission
K4 LLVM or the full K5 standalone native node.

This is the implementation plan for one Rust engine: compiled into wasm
instances as the in-guest engine, or run natively as a peer on dao.stream.
It retains the register kernel, shared Value/ownership model, scheduler and
module/effect semantics. Its LLVM lowering is the sibling execution strategy
to the compiler plan in [yang.wasm.md](./yang.wasm.md). It also owns the Rust
host-side implementation of [dao.stream.wasm.md](./dao.stream.wasm.md)'s
import surface (§6.4); those adapters do not host guest engine semantics.

Authorities:
- [datom.world.md](./datom.world.md) and [datom.world.abi.md](./datom.world.abi.md): axioms and boundary doctrine.
- [dao.stream.md](./dao.stream.md) and [dao.stream.wasm.md](./dao.stream.wasm.md): abstraction and wasm implementation contract.
- [yin.vm.engine.md](./yin.vm.engine.md): shared engine semantics and K0/W0 method (§E.5).
- [yin.vm.debruijn.register.md](./yin.vm.debruijn.register.md): register contract, state, fixtures (§10.4) and wasm gates.
- [yin.vm.linker.md](./yin.vm.linker.md) §7.3: offset tables, attachment and closure transport.
- [yin.vm.code-as-tuples.md](./yin.vm.code-as-tuples.md) and [yin.vm.semantic.md](./yin.vm.semantic.md): content/projection and A identity.
- [yin.vm.debruijn.targets.md](./yin.vm.debruijn.targets.md): earlier targets, subject to the explicit amendments here.

---

## 1. Objective, scope, non-goals

Objective. One Rust workspace that:

1. executes validator-approved `:yin.debruijn.register/*` images with the
   same observable behaviour as `register.cljc` under the B0 normalizer;
2. is a peer interpreter on `dao.stream`: it observes a stream of images,
   constructs CESK state, performs effects as stream emissions, and parks
   and resumes continuations as plain data;
3. can hand a validated image to LLVM and run the result, without the
   native code becoming a new executable format, a new identity, or a
   second continuation vocabulary.

Why the register kernel is the integration point for LLVM. Four
properties of the register image, all enforced by
`register-image-defect` before execution
(`debruijn_register_code.cljc:690-698`), are exactly the preconditions of
an SSA lowering, and none of them holds for the stack image or the map
AST:

    +----------------------------+----------------------------------------+
    | Register image property    | What LLVM lowering gets from it        |
    +----------------------------+----------------------------------------+
    | each body declares its     | a fixed-size activation; every         |
    | register count; every      | register access is statically          |
    | operand is bounds-checked  | in-bounds, no runtime check to emit    |
    | (`register-bounds-rule`)   |                                        |
    | jumps stay inside their    | one body is one function; its pcs are  |
    | body (`jump-scope-rule`);  | its basic blocks; no cross-function    |
    | bodies partition the pcs   | control flow exists to model           |
    | operands are named         | instructions are already three-address |
    | registers, no `:push`      | code; no stack-effect reconstruction   |
    | every continuation         | the live set IS the spill map at that  |
    | boundary carries an exact, | boundary: which registers must be      |
    | receiver-recomputed `live` | written back to data, and that the     |
    | set (`live-exact-rule`)    | rest are dead                          |
    +----------------------------+----------------------------------------+

The last row is the decisive one. The project requires the continuation
to be data at every observable point. A native compiler's hardest
problem under that requirement is knowing what to materialize where. The
register format answers it in-band, inside R's preimage, verified by
every receiver.

Scope. The kernel (`register.cljc` in full), the pure contract
namespaces it depends on (`debruijn_register_code`,
`debruijn_register_effects`, the B1 scalar encoder), the engine seam as
far as each implementation slice needs it, the wasm host adapters, and the LLVM lowering.

Non-goals.

- No new bytecode, no new identity, no change to R, H, the register
  descriptor, the validator, or any `.cljc` namespace.
- No Rust resolver or lowerer. The Rust node receives register images;
  `resolve` and `lower-register` stay Clojure-side stages (targets
  T-D2).
- No speculative optimization, inline caching, or type feedback in this
  revision (section 7.6).
- No interop with any other language's runtime through LLVM. LLVM is a
  code generator here, not a linking model for foreign libraries. The
  only host boundary is `dao.stream` (`yin.vm.ffi.md`).
- No stack-image kernel in Rust (targets T-D13).

---

## 2. Invariant review

The task brief asks for an evaluation against seven concerns. Each is
stated as a rule of this design. Section references say where the rule is
realized.

### 2.1 Foundational invariants

    +--------------------------+-------------------------------------------+
    | Invariant                | Rule in the Rust kernel                   |
    +--------------------------+-------------------------------------------+
    | No hidden global state   | No `static mut`, no `lazy_static`/        |
    |                          | `OnceLock` holding machine or code state, |
    |                          | no thread-locals. Primitives, modules,    |
    |                          | resources, the native code cache and the  |
    |                          | runtime function table are values passed  |
    |                          | at construction or as call arguments      |
    |                          | (sections 4.5, 7.4). The opcode table and |
    |                          | descriptor hash are `const` data.         |
    | No implicit control flow | Every transfer is an instruction of the   |
    |                          | image. `step1` never recurses and never   |
    |                          | unwinds: no panic crosses the step        |
    |                          | boundary, errors are `Next::Refuse`       |
    |                          | values (5.4). Native code never calls a   |
    |                          | yin closure on the native stack (7.3).    |
    | No callbacks             | The kernel invokes no supplied function   |
    |                          | except pure primitives and `dao.stream`   |
    |                          | handle operations. The LLVM worker is     |
    |                          | reached by a request datum on a stream    |
    |                          | and answers on a stream (8.3); no         |
    |                          | compile-finished callback exists.         |
    | No shared mutable state  | A `Machine` is owned by exactly one       |
    |                          | driver. Values are reference-counted with |
    |                          | `Rc`, which is neither `Send` nor `Sync`: |
    |                          | the compiler refuses to share a machine's |
    |                          | values across threads (4.6).              |
    | No layer collapsing      | Decode/validate (image crate), execute    |
    |                          | (machine), schedule (engine), translate   |
    |                          | to IR (translator) and generate machine   |
    |                          | code (LLVM worker) are five components    |
    |                          | with data between each (3, 7.1).          |
    | No assumed graphs        | The pc-to-body index, the basic-block     |
    |                          | graph and the offset table are built by   |
    |                          | explicit passes over validated tuples at  |
    |                          | load; a dangling target is a load         |
    |                          | refusal, never a runtime lookup.          |
    +--------------------------+-------------------------------------------+

### 2.2 Ownership boundaries

Three owners, and the type system states which is which:

- Code is immutable and shared. A decoded `Image` is built once per
  admitted image, never written after load, and held by `Arc`. It is the
  only value that may be shared across threads, because it is immutable
  content named by R. It contains no `Value` and no `Rc`: its constants
  are plain owned data, materialized into `Value`s per machine (4.2).
- Machine state is exclusively owned. `step1` takes `&mut Machine`. An
  exclusive borrow is the Rust spelling of `vm -> vm'`: there is no
  alias through which the old state could be observed, so in-place
  update is not shared mutation (5.3 states the atomicity rule this
  needs).
- Host resources (stream handles, the FFI bridge, loaded native
  artifacts) are owned by the composition and reach the machine through
  the `resources` slot. They are never part of a continuation payload, a
  wait entry, or a `Value`.

The engine owns scheduling and wake disposition; the kernel owns
registers. The key-set split of `yin.vm.engine` is preserved: the kernel
never reads engine bookkeeping in an entry, and the engine never reads
the register payload (register design 5.2.3).

### 2.3 Explicit state and control flow

The whole machine is one struct whose fields are the fields of
`DebruijnRegisterVM` (`register.cljc:44-69` and the extra keys
`create-vm` associates, `register.cljc:273-306`). Section 4.5 gives the
inventory. Every transition is `Machine -> Machine` selected by the
instruction at `pc`. Section 5.2 gives all 23.

### 2.4 Concurrency and linearization

One machine is one task and is single-threaded. Its history is
linearized by construction: one `step1` at a time, effects ordered by
instruction order. Concurrency between tasks is concurrency between
machines, and machines interact only through streams, where order is the
stream's append order. Nothing in this design introduces a second
ordering:

- The native path changes how a run of instructions between two
  boundaries is executed, never the order of boundaries or effects
  (7.2).
- Installing a native artifact is an explicit transition taken by the
  driver between scheduler rounds, when the machine is at a boundary
  (8.3). It is not observable in any payload.
- The deterministic, allocation-triggered heap collection of
  `engine.cljc:1887-1903` is semantic (a ref to a swept cell is refused
  `:dead-or-forged-reference`), so the Rust engine reproduces its
  trigger point exactly rather than substituting its own policy (6.3).

### 2.5 Dynamic extension

Code arrives while the machine runs. The mechanism is the existing one:
`attach-image` appends an admitted image to the code space and one row
to the offset table; nothing already held moves (`register.cljc:193-227`).
The Rust kernel implements it with the same law. Native code follows the
same grain: an artifact is compiled per image row, keyed by the row's
identity, so attaching an image never invalidates native code for images
already held (7.5). Free-name resolution stays dynamic (`:load-free`
resolves at run time in the order env, store, primitives, modules), which
bounds what the translator may assume (7.6).

### 2.6 Host isolation

The Rust process is a host like the JVM or Dart. Three isolation rules:

1. Portable code never sees a Rust type. `Value` admits plain data, the
   two kernel-minted types (closure, continuation), and resolved
   primitives. No pointer, handle, or artifact address is a `Value`.
2. The kernel crate contains no `unsafe`, performs no I/O, and does not
   link LLVM. Everything that touches raw memory or the dynamic loader
   lives in separate crates the kernel does not depend on (section 9).
   A node with no LLVM toolchain is a complete node; it interprets.
3. Host errors are classified into plain-data diagnostics at the point
   they occur. A Rust panic is a kernel bug, not an outcome, and the
   conformance suite fails on any panic. But a kernel bug must not be a
   node outage: the kernel is written so that a violated internal
   invariant is a `Refuse`, and a residual panic is contained to the one
   machine it occurred in (5.5). The node never aborts on a machine's
   fault.

### 2.7 Control laws

Two laws govern translation, and two govern execution:

1. **Translation is an execution strategy.** Translating an admitted held
   register image is a kernel execution strategy, not a replacement source
   emitter; the source-emitter target (T4) remains separately gated.
2. **Artifacts are local caches.** The LLVM worker's streams are node-local;
   artifacts are disposable local caches that never cross nodes as executable
   authority — receivers derive their own (§§7.5, 8.3–8.4).
3. **A yin call is never a native call.** Native code calls only runtime
   helpers that return before the next boundary; if a yin `:call` were
   lowered to a native `call`, the continuation would live on the native
   stack, unserializable. A yin call is a data transition followed by a
   return to the driver (§7.3).
4. **A failed step leaves the machine unchanged.** The Clojure kernel gets
   this from persistence (`vm -> vm'`); the Rust kernel gets it from the
   check-then-commit rule of §5.3, tested by the conformance kit.

---

## 3. Topology

The compilation pipeline is a set of stream stages; each stage is an
interpreter that knows only its input and output values.

    syntax --yang--> map AST --project--> rows (dao.jing)
                                             |
                             resolve (Clojure)
                                             |
                                     resolved tuples
                                      /            \
                           lower-stack              lower-register
                                |                        |
                          stack image (H)         register image (R)
                                |                        |
                     stack kernels (B3)      register kernels:
                                               R4 (CLJ, CLJS, CLJD)
                                               Rust kernel (this doc)
                                                    |          \
                                              interpret     translate (R -> IR text)
                                                    ^             |
                                                    |        LLVM worker (IR -> object)
                                                    |             |
                                                    +--- native artifact (local cache)

Three statements about this picture:

1. The Rust kernel is a peer of R4 below the format, not a new lowerer.
   It consumes what R4 consumes.
2. The translator and the LLVM worker are downstream of the image on the
   same node, and upstream of nothing: no stage consumes native code as
   input. Discarding every artifact loses nothing (it is a projection in
   the sense of `code-as-tuples.md` section 1: derived, never an
   identity, recomputed at will).
3. The Rust kernel and any other observer of the image stream (an
   indexer, a verifier, a Clojure kernel) are mutually ignorant. The
   kernel never consults an index to run.

---

## 4. Memory layout

### 4.1 Value

`Value` is a 16-byte tagged enum with a defined tag position, written in
safe Rust:

    #[repr(C, u8)]
    enum Value {
        Nil, Bool(bool), Long(i64), Double(f64),      // immediates
        Primitive(u32),                               // immediate: table index
        Str(Rc<StrObj>), Keyword(Rc<Name>), Symbol(Rc<Name>),
        Vector(Rc<PVec>), List(Rc<PList>),
        Map(Rc<PMap>), Set(Rc<PSet>),
        BigInt(Rc<Big>), Ratio(Rc<Rat>),              // if declared
        Closure(Rc<Closure>),       // {owner, arity, body_pc, frames, store_of}
        Continuation(Rc<Captured>), // {owner, payload}
    }

`repr(C, u8)` fixes the tag as the first byte and the payload at offset
8. That is all the layout any code outside safe Rust may rely on, and
only for two purposes: reading the tag, and reading or writing the
payload of an IMMEDIATE variant. Every `Rc` payload is opaque outside
safe Rust. A compile-time assertion pins `size_of::<Value>() == 16`;
every `Rc` target is a sized type so each payload is one word.
Names are not interned in any process-wide table (that would be hidden
global state); keywords and symbols compare by content.

Decisions:

1. Not NaN-boxed. `:long` is a full 64-bit class and `:long` and
   `:double` are distinct classes with no integral-double folding
   (`debruijn_code.cljc:98-106`, `:142-143`). A 64-bit box cannot hold
   both. Sixteen bytes buys an exact carrier for each.
2. No code outside safe Rust reads, writes, or assumes the location of a
   reference count. `Rc`'s allocation layout is private to the standard
   library (the counts precede the value and are not at a C-visible
   offset), so an inline retain or release in emitted IR would be
   memory-unsafe and toolchain-dependent. Revision 1 of this document
   proposed exactly that and it is withdrawn. Cloning and dropping a
   value whose tag is not an immediate is always an out-of-line call
   into the kernel's own `Clone`/`Drop` (7.4). Emitted IR may test the
   tag to skip that call for immediates; it never dereferences the
   payload of any other tag. A kernel-owned `repr(C)` object header that
   would make inline counting sound is a possible later optimization and
   is deferred behind a measurement (section 11).
3. Closure and continuation are distinct tags, never maps. This is D7 of
   `values.cljc:1-24`: a map carrying `:type :closure` is data. Both
   carry the owner tag; neither answers lookup; both print opaquely as
   `{:type :closure}` / `{:type :continuation}`.
4. Stream, cursor and cell references are NOT host types. They are
   sealed plain-data maps (`values.cljc:23-24`) and are represented as
   ordinary `Map` values. This supersedes the `StreamRef`/`CursorRef`
   variants sketched in targets section 3.2.
5. Equality and hashing follow Clojure semantics (vector and list equal
   when sequentially equal; closures and continuations structural over
   kind, owner and payload, `values.cljc:39-62`). This remains the
   largest parity surface (targets 3.2 item 4).
6. Reference counting is sufficient for Rust memory. Frames are
   immutable once built and no instruction writes into a frame, so the
   value graph is acyclic. Mutable cells are reached through `:cell-ref`
   data naming an id in the machine's `heap` map, never through a
   pointer, so a cell that refers to itself is a map entry containing
   its own id, not a pointer cycle. The yin-level heap is collected by
   the ported engine collector (6.3), not by Rust.

Persistent maps, sets and vectors are a library type (the
`dao.stream`-boundary rule: use libraries for plumbing). The crate is an
implementation choice with one constraint: no iteration order of a map
or set may reach an encoding, a hash, or a diagnostic. Canonical order
is by encoded bytes (`:map-set-order :canonical-encoded-bytes`).

### 4.2 Code space

    struct Image {                      // immutable after load; Arc-shared
        tuples:   Vec<Tuple>,           // canonical positional tuples, pc order
        bodies:   Vec<Body>,            // {locals, registers, start, end}
        code:     Vec<Inst>,            // decoded, fixed-width, pc order
        data:     Vec<Datum>,           // :const / store key and value pool
        regs:     Vec<u32>,             // pooled arg-regs and live vectors
        body_of:  Vec<u32>,             // pc -> body index, dense
    }

    struct CodeSpace {                  // kernel state, one per machine
        image:  Arc<Image>,             // the concatenation (attach-image)
        consts: Vec<Value>,             // this machine's values for image.data
        hash:   Option<Hash>,           // R of the concatenation
        rows:   Vec<Row>,               // offset table [identity offset len]
    }

`Inst` is a fixed-width record (opcode byte plus up to three 32-bit
operands; pooled operands are `(offset, len)` into `regs` or an index
into `data`). The hot loop reads the instruction at `pc` from one
contiguous array in pc order. `tuples` is retained because R, the
payload's `:segment`, and `image-slice` are defined over the canonical
tuples, not over the decoded form; decoding is the loader's
interpretation of the tuples, as the mnemonic-to-integer mapping is in
`yin.vm.semantic.md` section 1.1.

The shared image holds no `Value`. `Datum` (and every operand inside a
`Tuple`) is plain owned data with no `Rc` anywhere in it: scalars,
strings, and boxed collections of `Datum`. `Image` is therefore `Send +
Sync` by the compiler's own derivation, with no `unsafe impl`. A
`Value` constant has a non-atomic count, so one shared across threads
would race on every `const`; revision 1 put `Vec<Value>` in the image
and that is corrected here. Each machine materializes its own `consts`
from `image.data` once, at `load-image` and at `attach-image` (appending
only the new image's entries, so existing indices are stable). A
`const` instruction then clones a machine-local `Value`. The pool is
derived state: it is rebuilt from the image on restore and never
serialized.

Index width. Register indices, register counts, pcs, depths and
positions are `u32` throughout. The Clojure validator bounds a register
index only by its body's declared count and puts no ceiling on that
count (`nonneg-int?`, `debruijn_register_code.cljc:432-446`,
`:489-494`); revision 1's `u16` would have refused or mis-decoded a
valid image. An image with any integer operand above `u32::MAX`, or a
declared register count above the host's configured activation limit,
is refused at load, before hashing is trusted and before execution,
with a qualified `:host-limit` diagnostic naming the operand. That is a
declared host limit of the same kind as an undeclared scalar class
(targets T-D6) and the CLJS safe-integer bound in the same validator:
the host says what it cannot represent and refuses, it never truncates.
The limits are part of the host's declaration and appear in the kit.

`body_of` replaces the linear `body-of-pc` scan (`register.cljc:81-86`),
which the Clojure kernel runs on every closure call and every return
(`:327`, `:592`). The index is derived state, rebuilt by `attach-image`
by appending; it is not observable.

`attach-image` builds a new `Image` value with the new tuples relocated
and appended, and pushes one row. Because `Vec` growth may reallocate,
attach produces a new `Arc<Image>`; pcs are indices, not addresses, so
no existing pc, frame, or return frame changes meaning
(`register.cljc:193-203`).

### 4.3 Activation: the register file and frames

    type File   = Box<[Value]>;         // length = body.registers
    type Frame  = Rc<[Value]>;          // one lambda's positional locals
    type Frames = Option<Rc<FrameCell>>;// persistent cons, innermost first

- The file is allocated at call time at the callee body's declared size.
  Registers `0..locals` (the L bank) are bound from the arguments with
  nil-fill and extras dropped; the rest (the T bank) start `Nil`
  (`register.cljc:595-599`).
- The frame is a second, immutable copy of the bound locals, pushed on
  the callee closure's captured frames (`:596`). `:load-bound rd depth
  position` walks `depth` cells and copies into `rd`. The L bank is
  never aliased to frame 0 (register code docstring,
  `debruijn_register_code.cljc:20-27`).
- The Clojure kernel stores frames outermost-first and reads from the
  end (`register.cljc:89-105`); the Rust kernel stores a cons list
  innermost-first so closures share tails by `Rc`. The direction is not
  observable; the serialized form is always the outermost-first vector
  of vectors the payload contract names. The conversion is explicit, in
  the wire module only.

### 4.4 Continuation

    struct ReturnFrame {                // one per non-tail closure call
        site_pc:   u32,
        return_pc: u32,                 // absolute; survives every attach
        frames:    Frames,              // caller's lexical frames
        regs:      Box<[(u32, Value)]>, // live registers only, ascending
        dest:      u32,                 // caller's rd
        store_of:  Option<Value>,       // caller's module store address
    }
    type K = Vec<ReturnFrame>;          // innermost last

This is the sparse frame the code pushes (`register.cljc:602-613`), not
the whole-file save sketched in targets 3.2. Saving only `live` is
required, not optional (register design 5.2.2: a parked actor loop must
not retain dead messages). A return frame carries no code space (r7,
`register.cljc:315-321`): `return_pc` is absolute in the machine's code
space.

`:live` and the wire form. The Clojure frame carries `:live`
(`register.cljc:611`) and `return-frame-defect` checks it against the
call instruction (`debruijn_register_effects.cljc:190-194`). The Rust
frame's SERIALIZED form carries `:live` too, with the same key and the
same bytes: parity is defined over the canonical encoding, and K2
asserts it frame by frame. The in-memory struct does not hold a second
copy, because for an admitted frame `:live` is a function of two things
the frame already has: it equals the `live` operand of the non-tail
`call` at `site_pc` (the rule the validator enforces) and equals the
index column of `regs` (`:continuation-registers`). The wire module
writes `:live` from `regs`' indices; on decode, `continuation-defect`
runs over the received data, `:live` included, before any struct is
built, so a frame whose `:live` disagrees is refused with
`:continuation-live` exactly as on Clojure. Storing it again in memory
would be a persisted copy of a derived fact with a consistency
obligation and no reader.

Return rebuilds the caller's file at the body's declared size, fills the
saved pairs, writes the value to `dest` (`register.cljc:322-344`).

### 4.5 The machine

Every field of the Clojure state has exactly one home. Fields are
grouped by owner; the grouping is the ownership boundary of 2.2.

    +---------------+--------------------------------+---------------------+
    | Group         | Fields                         | Owner; serialized?  |
    +---------------+--------------------------------+---------------------+
    | registers     | pc, frames, file, k, store_of  | kernel; yes, as the |
    | (the payload) |                                | sparse payload      |
    | code space    | image, hash, rows              | kernel; by identity |
    |               |                                | and by value in the |
    |               |                                | payload `:segment`  |
    | store         | store, heap, gc                | engine              |
    | scheduler     | blocked, halted, value,        | engine              |
    |               | wait_set, ready_queue, parked, |                     |
    |               | id_counter                     |                     |
    | composition   | free_env, primitives, modules, | composition; never  |
    |               | callable_effects, origin,      | in a payload        |
    |               | ancestry, owner,               |                     |
    |               | capability_secret,             |                     |
    |               | secret_source, ffi_caller_id   |                     |
    | host          | resources (link pair, call     | composition; never  |
    | resources     | pair, attach_stream),          | serialized, never a |
    |               | make_stream, bridge, native    | `Value`             |
    +---------------+--------------------------------+---------------------+

`native` is the one field with no Clojure counterpart: the table of
loaded artifacts, keyed by offset-table row identity (7.5). It is a host
resource in every respect: supplied by the composition, absent from
every payload, and removable at any boundary without changing any
outcome.

### 4.6 Threads

`Machine` is not `Send`, because its values are `Rc`. This is the
intended statement of "no shared mutable state": a task moves between
threads or processes only as a serialized continuation over a stream,
which is the project's migration mechanism anyway. `Arc<Image>` is
`Send + Sync` because an image contains no `Rc` (4.2); the compiler
derives that, and the workspace contains no `unsafe impl Send` or
`Sync`. A node runs many machines; each driver owns its machines;
drivers share only images and streams. A static assertion in the image
crate (`fn assert_send_sync<T: Send + Sync>()` applied to `Image`) makes
a regression a compile error.

---

## 5. The CESK execution loop

### 5.1 Configuration

    C  control       code space, pc, file (the register file is control:
                     it is what the machine is doing, saved in frames)
    E  environment   frames (positional), free_env (named)
    S  store         store, heap, the module stores reached by store_of
    K  continuation  Vec<ReturnFrame>, sparse, data

### 5.2 Transitions

`I = code[pc]`. `R[x]` is the file. "next" means `pc + 1`. All 23
mnemonics of `opcode-table` (`debruijn_register_code.cljc:68-149`):

    +-----------------------+----------------------------------------------+
    | Instruction           | Transition                                   |
    +-----------------------+----------------------------------------------+
    | const rd v            | R[rd] = v; next                              |
    | load-bound rd d p     | R[rd] = frames[d][p]; next                   |
    | load-free rd name     | R[rd] = resolve(free_env, active store,      |
    |                       | primitives, modules, name); next             |
    | closure rd arity body | R[rd] = Closure{owner, arity, body, frames,  |
    |                       | store_of}; next                              |
    | move rd rs            | R[rd] = R[rs]; next                          |
    | call rd f args tail   | by operator kind, below                      |
    | live                  |                                              |
    | branch-false c target | pc = truthy(R[c]) ? next : target            |
    | jump target           | pc = target                                  |
    | return r              | return transition with R[r]                  |
    | halt r                | halted; value = R[r]; store_of = none        |
    | store-get rd key      | R[rd] = active_store[key]; next              |
    | store-put rd key v    | active_store[key] = v; R[rd] = v; next       |
    | define rd name rs     | active_store[name] = R[rs]; R[rd] = R[rs];   |
    |                       | next (operator never resolved, Rule R)       |
    | gensym rd prefix      | R[rd] = fresh id; id_counter advances; next  |
    | stream-make rd cap    | effect :stream/make                          |
    | stream-put rd s v     | effect :stream/put; parks on `full`          |
    | live                  |                                              |
    | stream-cursor rd s    | effect :stream/cursor                        |
    | stream-next rd c live | effect :stream/next; parks on `blocked`      |
    | stream-close rd s     | effect :stream/close                         |
    | current-continuation  | R[rd] = Continuation{owner, payload(pc)};    |
    | rd live               | next                                         |
    | park rd live          | engine park with payload(pc); halts with the |
    |                       | parked record as value                       |
    | resume id r           | engine resume of parked `id` with R[r]       |
    | ffi-call rd op args   | park-and-call over the call pair (6.2)       |
    | live                  |                                              |
    +-----------------------+----------------------------------------------+

`call`, by `operator-kind` of `R[f]` (`register.cljc:580-645`):

- closure, not tail: push `ReturnFrame{pc, pc+1, frames, live pairs, rd,
  store_of}`; file = callee file; frames = closure frames + locals;
  `store_of` = closure's; pc = body pc.
- closure, tail: the same without the push. `live` is `[]` by
  `live-tail-rule`. K and native stack both stay constant.
- primitive returning a value: `R[rd] = v; next`, or the return
  transition when tail.
- primitive returning an effect: `engine::handle_effect` with the call's
  own `rd` and `live` as its suspension contract; resume mode
  `:write-result`, or `:return-result` when tail.
- continuation: abortive. Identity check only (`check-format!`), then
  `write-back`: the call site's file, frames and K are dropped and the
  captured ones installed with the argument in the capture's `dest`.

The return transition (`register.cljc:315-344`): pop a frame if K is
non-empty, rebuild the caller's file, write `dest`, restore pc, frames,
`store_of`; otherwise halt with the value.

### 5.3 The step function and its atomicity rule

    enum Next {
        Continue,                 // took one transition; machine running
        Halt,                     // :halt, or return with K empty
        Blocked,                  // parked into the wait set
        Refuse(Diagnostic),       // qualified rule + plain-data fields
    }

    fn step1(m: &mut Machine) -> Next;      // exactly one instruction
    fn run(m: &mut Machine)  -> Outcome;    // the scheduler loop of 6.1

Rules:

1. `step1` does not call itself, `run`, or any body. Native stack depth
   is constant per step.
2. Check-then-commit. Every fallible read of a transition (operand
   fetch, `load-bound` range, operator kind, resolution, effect
   admission, payload construction) completes before the first write to
   `m`. A step that returns `Refuse` leaves `m` equal to its value
   before the step. This is the property the Clojure kernel gets from
   persistence (contradiction C4), and it is what makes a refused
   machine a valid value to inspect, serialize, or retry. The one
   transition that performs an external append before its last internal
   write is `ffi-call`; its order is exactly the Clojure order (require
   the pair, build the parked state, append the request, then branch on
   the outcome, `register.cljc:495-529`) and an append refusal discards
   the built state.
3. Dispatch is a `match` on the opcode byte. Operands were validated at
   load, but the kernel does not stake the node on the validator being
   bug-free: every access the validator is supposed to have proved (the
   instruction at `pc`, a register, a pool slice, a body, a frame slot)
   goes through a fallible accessor (`slice::get`), and a miss is
   `Refuse` with rule `:kernel-invariant` naming the access, under the
   check-then-commit rule like any other refusal. Panicking index
   syntax (`x[i]`), `unwrap`, `expect`, and overflow-panicking
   arithmetic are denied by lint in the kernel, image and engine crates.
   The cost is one well-predicted branch per access; it is paid.
4. `step` (the `IVM` single-step) always interprets. Native code is
   entered only from `run` (7.2).

### 5.4 Diagnostics

A `Diagnostic` is plain data: the rule keyword and the same fields the
Clojure `ex-data` carries (`:rule :load-bound-depth, :depth, :frame-count`
and so on). The B0 normalizer compares errors by message and normalized
data, so the message strings are part of the port and are fixtures in
the kit. No Rust error type, backtrace, or pointer value appears in one.

### 5.5 Fault containment

Three layers, so that a defect in the validator, the kernel, or a
primitive costs at most one machine:

1. Refuse, not panic. Section 5.3 rule 3: a violated internal invariant
   is a `:kernel-invariant` refusal. The machine is unchanged and
   remains a valid, inspectable value. `:kernel-invariant` has no
   Clojure counterpart; it never appears in a conforming run, and any
   occurrence is reported as a kernel defect.
2. Contain the residue. Panics the lints cannot exclude (a library
   collection, a primitive, allocation-size arithmetic) are caught at
   the driver's call to `run`, per machine. Builds use unwinding, not
   `panic = "abort"`. A machine that panicked may be mid-transition, so
   it is poisoned: dropped, never stepped, never serialized, and the
   driver appends a `:kernel-fault` outcome for that task as plain data.
   Other machines and the node continue.
3. Never unwind through native code. Every out-of-line helper in the ABI
   crate catches a panic at the `extern "C"` boundary and returns a
   `FAULT` code; the native function returns it to the driver, which
   poisons the machine as in 2.

Resource exhaustion is bounded the same way: activation size is checked
against the host limit at load (4.2), and K depth and heap growth
against per-machine limits the composition supplies, refusing with
`:host-limit`.

---

## 6. Effects and the engine seam

The kernel/engine split is kept exactly. The kernel builds payloads with
the R2 constructors and hands effects to the engine; the engine decides
value-or-park and owns the wait set. The following slices retain sparse registers and one engine implementation;
the former Level-1 architecture is superseded by the 2026-10-19 ruling.

### 6.1 Pure execution slice

Instruction set: `const load-bound load-free closure move call
branch-false jump return halt store-get store-put define`. An image
containing any other opcode is refused by a static scan before
execution. No stream, no park. This is targets T1 with `:define` added
(contract `"r2"`).

### 6.2 Effect payload and restore conformance

The kernel builds the pure R2 `effect-descriptor` and `continuation-payload`
shapes (`debruijn_register_effects.cljc:28-65`, `:83-119`) and passes effects
to the same engine inside the wasm instance or native process. It is not a
remote step function for a separate production Clojure engine. The CLJS/R4
implementation remains the conformance oracle.

K2 preserves bidirectional Rust↔R4 payload/restore equality and identical
qualified refusals, including every return frame's `:live`. This is conformance
and admissible data interchange, not an offload service. The in-instance
scheduler owns wait sets and park/resume; only stream operations reach adapters.

### 6.3 Shared engine and standalone completion

The native node and wasm instance own the same engine. Ported as a separate crate, in this
order, each a parity target against `engine.cljc`:

1. `handle-effect` and the stream handlers over a `dao.stream` handle
   trait whose operations return the exhaustive outcome map.
2. `check-wait-set`, `scheduler-round`, `resume-from-run-queue`, the
   terminal-outcome check before restore, `run-loop`
   (`engine.cljc:1658-1672`).
3. `park-continuation`, `resume-continuation`, `gensym`, ref sealing
   (`issue-ref`, `authentic-ref?`).
4. The FFI two-step over the `dao.stream.apply` pair. The request and
   response envelopes are `dao.stream.apply` data; nothing in this crate
   names a transport.
5. The heap and its collector, with the same allocation-count trigger
   and the same never-reused ids, because collection timing is
   observable (2.4).
6. The module kernel (`IModuleKernel`, `register.cljc:806-875`):
   `lift-closure`, `lower-closure`, `spawn-module`, `gc-roots`,
   `gc-children`, and the link-install path.

Restore is `register-restore` (`register.cljc:402-430`) with its four
gates in order: format and row identity (`:image`, never `:hash`),
`continuation-defect`, plain-data resume value, then the FFI two-step or
`write-back`.

The wait set, ready queue and parked map hold plain data only. A Rust
node's blocked machine must survive a canonical-encoding round trip and
restore on a JVM node running R4. No `Waker`, future, channel, or
closure is stored in an entry. The kernel and engine crates are
synchronous; they do not depend on an async runtime. Cadence (when to
call `run` again) belongs to the host driver, and liveness to
`dao.lease`, as on every other host.

---

### 6.4 Rust host implementation of the wasm stream surface

Implement [dao.stream.wasm.md](./dao.stream.wasm.md) §§1–3 in the Rust
Wasmtime/Wasmer composition adapters: checked memory regions and task-local
operational handles, canonical Jing encode/decode, exact stream outcome maps,
and pre-operation response reservation. The signatures and buffer contract
are specified only there, not duplicated here. Reuse `yin-wire` and the shared
repository codecs; runtime wrappers contain no parallel kernel or engine port.

The host adapters implement stream operations over the composition's supplied
implementations. They do not implement guest resolve-var, primitives, stores,
GC, continuation restoration or scheduler work: the wasm engine owns those.
CLJS hosts implement the same language-neutral surface. Co-resident streams
may use an in-instance ring-buffer implementation with no host crossing.
W0–W5 acceptance is owned by engine §E.5 and register §10.5; this host-side
work does not create a new K phase or change the LLVM commissioning gate.

## 7. LLVM bridging strategy

### 7.1 Components

    translate : (Image, Row, AbiVersion) -> IR text      pure, in Rust
    LLVM worker : IR text -> native object                separate process
    loader : native object -> entry table                 host crate

The translator is a printer. It walks one image row's validated tuples
and writes textual LLVM IR. It links no LLVM library, so it runs in any
build of the node and its output is a deterministic string: equal
`(row identity, translator version, ABI version)` gives equal bytes.
That determinism is what makes the IR a derived value in the project's
sense, and is tested like every other lowering (byte-identical repeated
output, golden fixtures).

LLVM is the interpreter of that text. It runs as a worker (8.3), out of
process, so that the kernel crate has no LLVM dependency and a toolchain
failure is an outcome on a stream, not a crash in the node.

### 7.2 The equivalence law

    For every validated image, every reachable machine state m at a
    boundary, and every later boundary: running natively from m and
    interpreting from m reach equal machine states, with equal effect
    emissions in equal order, and equal diagnostics on refusal.

"Boundary" means: body entry, the pc after any non-tail `call`, the pc
after any of the six boundary opcodes
(`boundary-opcodes`, `debruijn_register_code.cljc:156-159`), halt, and
refusal. Between boundaries the native code's intermediate state is not
a machine state and is never observed; this is "immutability observable
only at park points" (`yin.vm.semantic.md` 6.4) applied to register
contents.

Consequences that are rules, not hopes:

1. Native code has no continuation vocabulary. Resume points are
   register pcs. A continuation captured under native code is the same
   sparse payload, restorable by the interpreter on a host with no
   artifact, on any architecture (targets T-D10, now for translated
   images rather than emitted source).
2. The artifact can be dropped at any boundary. The machine continues
   interpreting at the same pc.
3. The conformance kit runs every fixture twice on a node with a
   toolchain: interpreted, and native. Results, payloads and diagnostics
   must be equal.

### 7.3 Function shape and calls

One LLVM function per body:

    define i32 @body_<k>(ptr %ctx, ptr %rt, i32 %entry)

- `%ctx` points at a `repr(C)` view of the running activation: the file
  pointer, the machine pointer (opaque to IR), and a slot for the next
  pc.
- `%rt` points at the runtime function table (7.4).
- `%entry` is a body-relative pc. The function begins with a `switch`
  over the body's legal entry pcs: its start, and every in-body
  boundary successor. Any other value returns `REFUSE`.
- The return value is the `Next` code; the next pc is written to `ctx`.

Each pc that is a jump target, a boundary successor, or the body start
becomes a basic block. `jump` and `branch-false` become `br`. Truthiness
is a tag test: `Nil`, or `Bool` with bits 0, is false.

How a yin `call` is lowered is the center of the design:

    call rd f args tail live   ==>
        store the next pc; %n = call i32 %rt.call(%ctx, i32 <site-pc>)
        ret i32 %n

`rt.call` performs the interpreter's own call transition, out of line,
on the machine: operator kind dispatch, the `ReturnFrame` push with the
live pairs read from the file, the callee file allocation, or the
primitive application. Its result is decided by the same case analysis
as `register.cljc:580-645`, and the translator emits a different
continuation for tail and non-tail sites:

    +------------------------------+------------+------------------------+
    | Operator and outcome         | Non-tail   | Tail                   |
    +------------------------------+------------+------------------------+
    | closure                      | ENTER      | ENTER                  |
    | primitive, plain value       | CONTINUE_  | return transition,     |
    |                              | INLINE     | then ENTER or HALT     |
    | primitive, effect, immediate | CONTINUE_  | return transition,     |
    | value                        | INLINE     | then ENTER or HALT     |
    | primitive, effect, parks     | BLOCKED    | BLOCKED                |
    | continuation (abortive)      | ENTER      | ENTER                  |
    | any refusal                  | REFUSE     | REFUSE                 |
    +------------------------------+------------+------------------------+

`CONTINUE_INLINE` exists only at a non-tail site, where the value has
been written to `rd` and control stays in the same body at the next pc.
A tail call has no successor in its body (`successors-of`,
`debruijn_register_code.cljc:309`), so the translator emits an
unconditional `ret` after a tail `rt.call` and no fall-through block. A
tail primitive that produces a value performs the kernel's own
`return-transition` (`register.cljc:627-628`, and `:487-488` for the
effect case): it pops the caller's `ReturnFrame`, rebuilds the caller's
file, writes the caller's `dest`, and sets pc to `return_pc`, which is
in another body; or it halts when K is empty. Either way the native
function returns to the driver. Revision 1 said a primitive value was
always `CONTINUE_INLINE`; for a tail site that would have continued in
a body whose activation no longer exists. `rt.call` never returns
`CONTINUE_INLINE` for a tail site, and the native entry stub treats that
code after a tail call as `FAULT`.

For `ENTER` the driver, the same `run` loop, reads the machine's pc and
enters either that body's native function or the interpreter.

So LLVM `call` appears for runtime helpers only. A yin closure call is
never an LLVM call, native stack depth is constant, and the continuation
is `Vec<ReturnFrame>` under native code exactly as under the interpreter
(contradiction C3). The driver loop is a trampoline. Replacing the
trampoline with guaranteed tail calls between body functions (LLVM
`musttail` under a tail-call calling convention) is a later
optimization that changes no state shape; it is deferred until a
measurement asks for it, and it must be verified against the LLVM
version in use rather than assumed.

Registers in native code. Revision 1 of the translator keeps registers
in the file: every read is a load from `ctx.file[i]`, and every write
goes through the slot-write rule of 7.4 (an immediate over an immediate
is a plain 16-byte store; anything else is an out-of-line call that
drops the old value and clones the new). This is trivially correct at every
boundary, and LLVM's own store-to-load forwarding removes the redundant
traffic inside a block. A later revision may hold registers in SSA
values and write back, before each helper call and each return, exactly
the registers the instruction reads plus its `live` set. The live set is
what makes that sound: it is exact, in-band, and recomputed by the
validator, so a register not in it is provably dead at that boundary and
need not exist in memory.

### 7.4 Function calls and LTO

Native code reaches the runtime two ways. The split is decided by one
question: does the helper's correctness depend on Rust's unspecified
type layout?

    +------------------+---------------------------+----------------------+
    | Tier             | Contents                  | How native code      |
    |                  |                           | reaches it           |
    +------------------+---------------------------+----------------------+
    | inline tier      | the tag byte and the      | LLVM bitcode of a    |
    |                  | payload of IMMEDIATE      | small `no_std`       |
    |                  | variants only: tag tests, | crate, linked with   |
    |                  | truthiness, `is_immediate`| the body IR under    |
    |                  | , immediate `const`,      | LTO and inlined      |
    |                  | `move` and slot write     |                      |
    |                  | when both old and new     |                      |
    |                  | values are immediates     |                      |
    | out-of-line tier | everything else: cloning  | indirect calls       |
    |                  | or dropping any counted   | through the `%rt`    |
    |                  | value (`rt.slot_write`,   | table of `extern     |
    |                  | `rt.slot_clear`), `call`, | "C"` function        |
    |                  | return, `load-free`,      | pointers, passed as  |
    |                  | `load-bound`, `closure`,  | an argument          |
    |                  | non-immediate `const`,    |                      |
    |                  | store ops, `define`,      |                      |
    |                  | every effect and boundary |                      |
    |                  | op                        |                      |
    +------------------+---------------------------+----------------------+

The slot-write rule. To write value `v` into register slot `s`: if the
tag of `v` and the tag currently in `s` are both immediates, emit a
16-byte store. Otherwise call `rt.slot_write(ctx, index, src)`, which
runs the kernel's safe `Clone` and `Drop`. Native code never copies a
counted `Value` bitwise, never frees one, and never reads its payload.
Reference counting therefore has exactly one implementation, the
standard library's, reached only from safe Rust. This costs a call per
counted move that revision 1 hoped to inline; that cost is accepted, and
it is one of the things K3 measures.

Why LTO for the inline tier. The inline helpers are written once, in
Rust, in a crate that sees `Value` only as a tag byte and an immediate
payload at the offsets `repr(C, u8)` defines, and compiled to LLVM
bitcode. The translator emits calls to them by name; link-time
optimization of body IR plus that bitcode inlines them, so a `move` or a
`branch-false` becomes a few instructions with no call. The alternative,
having the translator print those instruction sequences itself, would
put a second definition of `Value`'s layout in a string printer. One
definition, inlined by LTO, is the "derive, don't persist" choice. This
requires the bitcode and the worker's LLVM to be version-compatible; the
ABI version in the cache key (7.5) pins the pair, and a mismatch is an
`unsupported` outcome.

Why a function table for the rest. The out-of-line helpers live in the
host process's one copy of the kernel. An artifact must not contain a
second copy of kernel code operating on the same memory: two separately
compiled copies of a Rust crate are not guaranteed the same layout for
non-`repr(C)` types. Passing the table as an argument also means the
artifact has no undefined symbols to resolve against the process and no
global to initialize, so loading it binds nothing implicitly (no hidden
global state, 2.1).

Whole-node AOT. A deployment that knows its images at build time (an
embedded node, a benchmark) may instead link kernel, runtime and
translated bodies in one LTO link. With a single compilation of the
kernel there is one layout, and the out-of-line tier becomes inlinable
too. This mode produces a node binary, which is a host capability with
no identity (targets 3.5), and it changes nothing in 7.2: the same
boundaries, the same payloads. It is the upper bound the benchmark gate
should measure.

In both modes the helpers are the interpreter's own transition
functions. `rt.call` calls the function `step1`'s `call` arm calls.
There is one implementation of each transition, reached from two
dispatchers. This is what keeps the equivalence law cheap to maintain:
a semantic change is made once.

### 7.5 Artifact identity and the code cache

    key = (row identity, target triple, translator version, ABI version)

- Row identity is the offset-table row's identity: R of that image
  alone (`attach-image`, `register.cljc:210-227`). Bodies are translated
  with body-relative pcs and the row's offset is supplied at entry, so
  an artifact is independent of where its image sits in a code space and
  of what was attached before or after it.
- The artifact is a local cache, rebuilt and never fetched (targets
  T-D5, T-D11). It has no address in `dao.jing`, appears in no ref, and
  is named by no continuation.
- The cache is an explicit value owned by the composition and handed to
  the machine as the `native` resource. Lookup is by row identity at
  body entry. A miss interprets.

### 7.6 What the translator may not assume

Stated because each is a tempting optimization that breaks parity:

1. It may not resolve `load-free` at translation time. Resolution order
   is env, store, primitives, modules, and the store is mutable: a
   `define` can shadow a primitive at run time. `(+ a b)` is therefore
   `load-free`, then `rt.call`, not an `add`.
2. It may not unbox arithmetic. Numeric semantics (overflow, the float64
   kind, big integers) belong to the primitive table.
3. It may not reorder or merge effects, or move a boundary.
4. It may not elide a refusal: a failing helper returns a status and the
   function returns `REFUSE` with the site pc.

These limits bound the speedup. Revision 1 removes dispatch and operand
decoding and lets LLVM optimize register traffic; it does not make
`(+ x 1)` a machine add. Guarded specialization (assume a free name
still resolves to a known primitive, deoptimize to the interpreter at
the same pc otherwise) fits the design, since deoptimization is "drop to
the interpreter at a boundary", but it is out of scope until a
measurement justifies it. `yin.vm.jit.md`'s trace surface is the place
such evidence would come from.

---

## 8. The Rust node as an interpreter on dao.stream

### 8.1 Program ingress

Identical in shape to every other evaluator (`yin.vm.semantic.md` 3.1).
An observer owns the handle, the cursor, and gap accounting. The machine
holds no program stream. The loader the composition supplies is:

    1. decode the batch value (dao.jing CBOR, by library)
    2. check the contract stamp against "r2": :contract-missing,
       :contract-mismatch, before anything else
    3. validate: the fourteen rules of register-image-defect, in order,
       first defect wins
    4. compute R over the canonical encoding and compare with the
       identity the batch or the link response claims
    5. load-image, or attach-image when extending a running code space

The validator and `body-liveness` are reimplemented in Rust from their
stated rules. `live-exact-rule` means the Rust node recomputes every
live set of every image it admits; a received live set is never trusted.
That is also the safety argument for 7.3: the spill map native code
relies on was computed by this node.

R requires the B1 scalar encoder byte for byte (class tag, 8-hex length,
content; `debruijn_code.cljc:207-212`, `:419-457`) and the register
descriptor hash. The descriptor hash is a constant of the contract
version; the Rust crate pins it and a kit fixture asserts it. A scalar
class the Rust host does not declare is refused before hashing with
`:unsupported-value` (targets T-D6).

### 8.2 Peer, not server

A Rust node is one more observer and one more appender. It is not a
compile server or an execution server, and no other node is its client
(the P2P invariant of `dao.stream`). Concretely:

- Any stream the node opens is an ordinary `dao.stream` that may be
  exposed over ws or UDP like any other.
- Work reaches the node as values on streams it observes and leaves as
  values it appends. Correlation is by identity in the data.
- A node without a register kernel that receives a register continuation
  refuses it with `:continuation-format`; a Rust node that receives a
  stack continuation does the same. Same-model transport only (register
  design 5.1) until the universal continuation format exists.

### 8.3 The LLVM worker

The worker is a stream interpreter in the node's own composition:

    request stream   [key, IR text]                 appended by the driver
    outcome stream   [key, :ok artifact-location]   appended by the worker
                     [key, :unsupported reason]
                     [key, :failed diagnostic]

- The driver appends a request when policy says an image row is worth
  translating. Policy is the composition's (always, never, after n
  entries); it is not in the kernel.
- The driver reads the outcome stream between scheduler rounds. On `:ok`
  it loads the artifact and adds it to the `native` table. That is an
  explicit transition at a boundary. Until then, and forever on any
  other outcome, the image is interpreted.
- `:unsupported` is a correct outcome (no toolchain, wrong LLVM version,
  unsupported target), not a gap. A node with no worker simply never
  sees an outcome.
- The kernel never waits on the worker. There is no blocking compile.

The request/outcome pair is the shape `dao.stream.apply` already
defines; reusing its envelope rather than inventing one is recommended,
and is an owner decision (section 11) because it gives that vocabulary a
second, non-VM user.

The same worker serves a Clojure node unchanged: a JVM composition could
observe the same request vocabulary. Nothing in the worker knows who
asked.

### 8.4 Trust

Both streams of 8.3 are node-local, and this is a rule rather than a
default. A native artifact cannot be validated by any receiver short of
rebuilding it, so a node executes only artifacts produced by its own
worker from IR its own translator printed from an image its own
validator admitted. Sharing artifacts or IR between nodes would be a new
trust policy of the same kind as the linker's `:trusted` derivation
(`yin.vm.linker.md` 8.1) and would need the same explicit, reported
choice. It is not designed here.

### 8.5 Effects out, events in

In the shared engine every effect is an append or a read on a stream handle with
an exhaustive outcome map; the FFI is the `dao.stream.apply` pair. Host
events (a socket becomes readable, a worker finishes) are deposited as
plain data by adapters and noticed by the next `check-wait-set` poll.
The kernel and engine crates take no callback and return no future.

---

## 9. Crate layout

    src/host/rust/yin-debruijn/           (workspace; path per targets T1)
      yin-value      Value, Clojure equality and hashing, collections
      yin-scalar     B1 scalar encoder/decoder, class declaration
      yin-image      tuples, decode, validator, body-liveness, R,
                     attach, offset table
      yin-kernel     Machine, step1, return/restore, payload builders
                     (the port of register.cljc + register_effects)
      yin-prims      the primitive table with Clojure semantics
      yin-engine     scheduler, wait set, effects, FFI, heap
      yin-wire       payload and value serialization (dao.jing CBOR)
      yin-translate  Image row -> LLVM IR text; no LLVM dependency
      yin-rt-inline  no_std inline tier over tag + immediates, to bitcode
      yin-rt-abi     extern "C" table over yin-kernel's safe API; turns
                     raw ctx pointers back into references and catches
                     panics at the boundary; never inspects an Rc
      yin-native     artifact loading, the native code cache
      yin-node       composition: observers, drivers, worker streams

`#![forbid(unsafe_code)]` holds for every crate except `yin-rt-inline`,
`yin-rt-abi` and `yin-native`. Those three are the entire unsafe
surface, and a node built without them (no native path) is fully
functional. This narrows targets T1's "no `unsafe`" from the whole host
to everything except the optional native path.

---

## 10. Phases

Each phase adds files only under `src/host/rust/` and `test/`; none
edits an existing `.cljc` namespace.

| Phase | Deliverable | Depends on |
|---|---|---|
| K0 | Shared conformance kit exported from R4 (targets T0) | R1, R2, R4: landed |
| K1 | Pure Rust kernel including attach-image (targets T1) | K0 |
| K2 | Effects/restore conformance in the shared engine; supersedes T5 offload framing | K1 |
| K3 | Benchmark K1 versus R4/JVM and whole-node AOT of two hand-translated corpus images | K1 |
| K4 | Translator, inline runtime, worker and native path | K2; K3 reviewed by owner |
| K5 | Standalone native node (targets T6) | K2; Rust dao.stream client/implementations |

K0 is the **shared K0/W0 deliverable**, exported once from R4/CLJS, not a
second interpreter or kit. Its method, differential lanes and deterministic
trace gates have one home in [yin.vm.engine.md](./yin.vm.engine.md) §E.5;
its complete register fixture schema lives in
[yin.vm.debruijn.register.md](./yin.vm.debruijn.register.md) §10.4. Export that
kit first; preserve all fixture groups and existing goldens. The test-only
reference interpreter proposed by targets T-D14 is superseded by the R4 oracle.
The wasm schedule depends on the needed shared engine subset by W3–W4, even
though full standalone-native completion remains K5.

K1 completion: every pure fixture passes; R equal; refusals equal by
rule; no recursion in `step1` or `run`; constant native stack and one
file per live activation on the tail fixtures; atomicity fixtures pass;
no panic; a fault-injection test (a deliberately corrupted decoded
image, bypassing the validator) yields `:kernel-invariant` refusals and
a poisoned machine at worst, never a process exit, while a second
machine on the same node runs to completion.

K2 completion: for every effect fixture, the Rust kernel's payload and
effect descriptor equal R4's in canonical encoding, every return frame's
`:live` included; a payload parked by
Rust restores under R4 and one parked by R4 restores under Rust, to the
same normalized outcome; `continuation-defect` refusals equal by rule.

K3 is a gate in the sense targets gave T4: numbers the owner reads
before K4 is commissioned. Section 7.6 predicts a bounded gain; K3 is
where that prediction is checked before any LLVM code is written.

K4 completion: translator output byte-identical across runs and
platforms; the equivalence law of 7.2 over the whole kit, native versus
interpreted, including payload equality at every boundary and diagnostic
equality on refusal; artifact dropped mid-run at a boundary continues
interpreted to the same result; a node with no toolchain passes the
whole kit interpreted and records `:unsupported`.

K5 completion: the engine parity criteria of 6.3; a blocked Rust machine
round-trips through its wire form and resumes on a JVM node, and the
reverse.

---

## 11. Decisions, amendments, and owner questions

DECIDED in this document (within the brief's scope):

1. K-D1. The Rust kernel is a port of `register.cljc` at contract
   `"r2"`, a peer of R4, consuming validated register images only.
2. K-D2. `Value` is a 16-byte `repr(C, u8)` enum in safe Rust; closures
   and continuations are host types with owner tags; stream, cursor and
   cell refs are plain data. Only the tag and immediate payloads are
   layout visible outside safe Rust; no native code touches a reference
   count.
   K-D2a. The shared `Image` holds plain data only and is `Send + Sync`
   by derivation; each machine owns its constant pool.
   K-D2b. Indices are `u32`; anything larger, or beyond the host's
   declared limits, is refused at load with `:host-limit`.
3. K-D3. Return frames and payloads are sparse (live registers only),
   carry no code space, and restore by row identity.
4. K-D4. `step1` is check-then-commit; a refused step leaves the machine
   unchanged. A violated internal invariant is a `:kernel-invariant`
   refusal, a residual panic poisons one machine, and the node never
   aborts (5.5).
5. K-D5. LLVM lowering is a specialization of the kernel over one
   validated image row, with register pcs as its only resume labels and
   the interpreter's transition functions as its runtime. A yin call is
   never an LLVM call.
6. K-D6. Inline tier by LTO over the tag and immediate payloads only;
   out-of-line tier, including every clone and drop of a counted value,
   by an explicit function table argument. No artifact contains kernel code or
   resolves a symbol against the process.
7. K-D7. Artifacts are node-local caches keyed by `(row identity,
   target, translator version, ABI version)`; the worker is reached only
   by streams; the kernel never waits on it.
8. K-D8. The kernel, image, value and engine crates forbid `unsafe`,
   link no LLVM, and use no async runtime.

AMENDMENTS proposed to `yin.vm.debruijn.targets.md`:

- A1. Its status header (`targets.md:3-15`) and section 3.7.1 describe
  R1 and R4 as unbuilt and unauthorized. Both are built. T0's
  dependency is met and T-D14's reference interpreter is superseded by
  R4 as the oracle.
- A2. Section 3.2's Rust types are superseded by section 4 here (sparse
  frames, owner tags, `store_of`, offset table, plain-data refs).
- A3. T-D2 gains a clause: a kernel-side translation of an admitted
  register image is an execution strategy of that kernel, not an
  emitter, and is permitted (control law 1, §2.7).
- A4. T1's "no `unsafe`" is scoped to the non-native crates (section 9).

OWNER QUESTIONS / RESOLUTIONS:

1. Translation of held admitted images is owner-authorized (§2.7 law 1); T4 is not commissioned.
2. Node-local worker/cache scope is settled (§2.7); a shared compile farm remains deferred.
3. Should the worker's request/outcome vocabulary be `dao.stream.apply`
   envelopes (8.3)?
4. The K3 threshold for commissioning K4.
5. K1 scalar classes: the eleven-class common domain first (targets
   T-D6), with bigint, ratio and the float64 kind following?

DEFERRED: a kernel-owned `repr(C)` counted object header that would
permit inline retain and release (it moves the counting implementation
into an unsafe crate, so it needs a K3 number showing the out-of-line
calls matter); guaranteed tail calls between native bodies; SSA registers
with live-set write-back; guarded specialization and deoptimization;
in-process JIT; cross-node artifact sharing; the Rust `dao.stream`
client and transports beyond the wasm adapter work (§6.4). The deferred wasm
artifact target is superseded by [yang.wasm.md](./yang.wasm.md), with engine
method in [yin.vm.engine.md](./yin.vm.engine.md) §E.5 and register fixtures/
gates in [yin.vm.debruijn.register.md](./yin.vm.debruijn.register.md) §10.

---

## 12. Risks

1. Primitive and equality drift. Unchanged from targets section 9 and
   still the largest surface. The kit is the only defence.
2. Two dispatchers. The interpreter and native code must agree forever.
   Mitigation is structural (one set of transition functions, 7.4) and
   tested (every fixture both ways, 7.2). A transition implemented twice
   is a defect.
3. Layout coupling. The inline tier fixes `Value`'s tag position and
   immediate payload offset as ABI, and nothing else: no reference
   count offset and no `Rc` representation. A change is an ABI version
   bump that
   invalidates every artifact; because artifacts are caches, that costs
   recompilation only.
4. Toolchain coupling. LTO needs the inline bitcode and the worker's
   LLVM to match. Pinned by the ABI version; mismatch degrades to
   interpretation.
5. Modest speedup. Dynamic resolution and boxed arithmetic bound what
   revision 1 can gain (7.6). K3 exists so this is measured before K4
   is built.
6. Engine port size. `engine.cljc` is over two thousand lines including
   linking, sealing and collection. The wasm lane requires this shared engine
   subset before its effects/module gates; an offload substitute is not permitted.
7. Unsafe surface. Loading native code is the one place a defect can
   corrupt a machine rather than refuse. It is confined to three crates,
   optional, and exercised only on images this node validated.
8. Payload size. The payload carries its code space by value
   (`:segment`). That is the existing contract, not introduced here; a
   Rust node shares the `Arc<Image>` in memory and pays the cost only on
   the wire.

Related specs: [datom.world.abi.md](./datom.world.abi.md) (doctrine),
[dao.stream.wasm.md](./dao.stream.wasm.md) (language-neutral adapter contract),
[yin.vm.engine.md](./yin.vm.engine.md) §E.5 (method),
[yin.vm.debruijn.register.md](./yin.vm.debruijn.register.md) §10 (instance/fixtures/gates),
[yang.wasm.md](./yang.wasm.md) (sibling wasm compiler).

2026-10-19 owner decomposition: rescoped to the Rust implementation plan;
shared technical core retained, offload framing superseded, K0 fixture text
replaced by references to its single homes, and old fixed line citations
replaced by section citations in companion specs.
