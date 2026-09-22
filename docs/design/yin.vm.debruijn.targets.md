# yin.vm de Bruijn target hosts and emitters

Status: design only, not authorized for implementation. Exploratory
architecture for an OPTIONAL compilation and foreign-host pipeline. Its
emitters are peer lowerings of the resolved tuples, the de Bruijn encoding
of the named semantic tuples that the stack and register lowerers already
branch from; its foreign hosts execute the `:yin.debruijn.register/*`
register image (R) of `yin.vm.debruijn.register.md`. That image is not
built yet (R1 is not authorized; R4 is gated), so every kernel phase here
depends on R0 and R1 landing first; section 7 states the chain. Revised
2026-09-22 and 2026-09-23 on the owner's corrections: emitters consume
the resolved tuples, and foreign kernels target the register image, not
the stack image. Nothing here changes B0-B7, R0-R5, H, R, the engine
seam, or any existing namespace. Implementation of any phase below is a
separate, later owner decision.

This document covers Rust first, and designs the same approach for Gambit
Scheme, Chez Scheme, Clojure (the JVM host, distinct from this project's
implementation language), and Python. C, ARM, x86 and WASM appear only as
comparison points reached through the Rust toolchain.

## 0. Document choice

One document, not one per target, named `yin.vm.debruijn.targets.md` so
it sits in the `yin.vm.debruijn.*` family beside `.stack.md` and
`.register.md`. Reasons:

1. The decision is made per target FAMILY along one axis, the target
   language's tail-call guarantee (section 2). Two families, five
   languages. The shared parts (what is consumed, the state type mapping,
   the effect boundary, identity) are identical across families and would
   be repeated in five documents.
2. The name is "targets", not "native-targets": three of the five are
   hosted languages, and the design's first recommendation for Rust is a
   foreign HOST, not native codegen.
3. Rust is the first worked example (section 5) because the owner's
   discussion started there and because the Rust kernel crate is the
   artifact that both directions share.

Relation to `docs/design/yin.vm-portability.md`: that earlier proposal
also cites Ribbit, but its skeleton interprets AST DATOMS through a CESK
relation over a per-host datom log, requires bit-identical logs, and lists
"no bytecode" as a non-requirement. Its section 2 prototype namespace
(`yin.vm.space`) is no longer in `src/`, and its sections 3 and 4 are
marked unimplemented. This document does not amend it. It makes a
different choice for a different payload: the portable unit a foreign host
executes is the register image with identity R, validated by the register
descriptor's validator, a positional image of the same resolved tuples
whose stack projection is already verified byte-identical across three
hosts, and conformance is B0-normalized parity rather than log identity.
Whether the portability document is superseded or coexists is an owner
decision (section 10).

## 1. Two directions, and why the answer is per family

Direction A, native codegen: lower the resolved tuples into source in the
target language, then hand it to that language's own compiler. One emitter
per target, each a peer of `lower-stack` and `lower-register` in the
compilation topology. Output is a target-specific artifact with no
identity in the H/R model.

Direction B, Ribbit-style host: write one thin interpreter per target
language for the `:yin.debruijn.register/*` image. No new bytecode, no
new identity, no change to R. One more host of the register format,
using the same frame, closure, store and continuation contracts B3
defines for the stack format and R4 inherits (register design section
5), with a register file per activation in place of an operand stack.
CLJ, CLJS and CLJD today execute the stack image through B3; the register
format's first-party kernel is R4, gated behind R3.

The two are not alternatives at the same level. Direction B extends WHERE
an image can run. Direction A changes HOW FAST it runs where it already
can. Direction B is therefore the prerequisite for any target that does
not yet run the image, and Direction A is an optional per-target
optimization on top. The one exception is the proper-tail-call Scheme
family, where a direct-style Direction A emitter for pure programs is
cheaper than an interpreter, because the target language supplies the two
things a kernel otherwise hand-rolls: tail calls and closures.

### 1.1 The Ribbit precedent, verified and unverified

Verified from the published papers via search results (the papers and
repository could not be fetched directly in this session; page-level
claims are quoted from search summaries):

- Samuel Yvon and Marc Feeley, "A Small Scheme VM, Compiler, and REPL in
  4K", VMIL 2021: the RVM "is a stack machine with a classic code
  interpretation loop that dispatches on the next instruction"; "all data
  structures are built solely out of fixed size cells with 3 fields,
  called ribs"; the RVM "is typically only 200-400 lines of code,
  depending on the language, and some additional lines of code for a
  garbage collector when the host language does not manage memory
  automatically".
- Leonard Oest O'Leary and Marc Feeley, "A Compact and Extensible
  Portable Scheme VM", MoreVMs 2023, and arXiv 2310.13589 "A R4RS
  Compliant REPL in 7 KB": Ribbit "currently runs on 25 different hosts,
  including JavaScript, Assembly (x86), C, Python, POSIX Shell, Prolog".
- Marc Feeley is the author of Gambit and a co-author of Ribbit.
- Gambit "conforms to the R7RS Scheme language"; its C back end
  implements tail calls and call/cc with "the use of a trampoline and the
  management of the stack through an explicit array, stack pointer and
  stack-overflow checks" (Huberdeau and Feeley, SFPW 2018 abstract).

UNVERIFIED here: the exact current host list and per-host line counts in
the repository, the RVM instruction count, how `rsc` embeds the compiled
program string in a host source file, and whether the README's current
claims match the papers.

What transfers: Ribbit's portability comes from a tiny host contract, not
from a universal code generator. Its author's OTHER system, Gambit, is the
opposite strategy, native codegen with a hand-rolled trampoline in the C
back end. The same person chose Direction B for reach and Direction A for
speed. That is the split this document adopts.

What does not transfer: Ribbit's contract is small because its value
model is one cell type and its primitive set is tiny. This project's
image carries fourteen scalar classes and about twenty-five primitives
with Clojure semantics (`=` across vector and list, `conj` per collection
kind, `assoc`, `get`, nil-punning `first`/`rest`). The dispatch loop of a
foreign host will be a few hundred lines, as Ribbit's is; the primitive
and value model will be the larger part, and it is where parity effort
goes (section 3.4).

## 2. The tail-call axis

Verified (via search of the R7RS text): R7RS section 3.5, "Implementations
of Scheme are required to be properly tail-recursive", with the tail
contexts enumerated there. Chez Scheme is "a superset of R6RS" with
"proper treatment of tail calls" and a segmented stack (Hieb, Dybvig and
Bruggeman 1990). Gambit conforms to R7RS. UNVERIFIED: which R7RS-small
edition Chez 10 declares.

Verified: `clojure.core/trampoline`'s docstring: "Calls f with supplied
args, if any. If f returns a fn, calls that fn with no arguments, and
continues to repeat, until the return value is not a fn, then returns
that non-fn value. ... if you want to return a fn as a final value, you
must wrap it in some data structure and unpack it after trampoline
returns."

Verified: Guido van Rossum, Neopythonic, April 2009, "Tail Recursion
Elimination" and "Final Words on Tail Calls": TRE rejected as
"unpythonic", for stack traces, for portability of code that would come
to depend on it, and by philosophy.

Consequences per family:

    +---------------+------------------+-----------------------------------+
    | Family        | Tail calls       | What a Direction A emitter needs   |
    +---------------+------------------+-----------------------------------+
    | Gambit, Chez  | guaranteed by    | direct-style calls; no driver     |
    |               | the language     | loop; closures are host closures  |
    | Clojure (JVM) | none; core       | `trampoline` is NOT usable as-is: |
    |               | `trampoline`     | yin closures are values, and a    |
    |               |                  | fn returned in tail position is   |
    |               |                  | indistinguishable from a thunk    |
    |               |                  | (the docstring's own caveat). A   |
    |               |                  | ten-line typed driver is needed;  |
    |               |                  | that is a kernel, only a tiny one |
    | Python        | none, rejected   | hand-rolled driver loop           |
    | Rust, C, WASM | none guaranteed  | hand-rolled driver loop           |
    +---------------+------------------+-----------------------------------+

The axis decides whether a Direction A emitter needs a driver loop. It
does NOT decide the continuation question, which is independent and
stricter, and it bites every family equally:

Hard constraint. The project's invariant "everything is a continuation"
requires the continuation to be explicit, serializable data, never the
host call stack. A direct-style Scheme program keeps its continuation in
the Scheme stack; so does direct-style Clojure and so does any Rust
program that recurses. Scheme's `call/cc` reifies that stack as a host
procedure, which is a host value, not data, and does not cross a stream.

Therefore, for EVERY family, Direction A splits into two subsets:

- Pure programs (no park, no resume, no current-continuation, no stream
  effect): the continuation is never observed, so the host stack may hold
  it. Direct style is sound here, and for Scheme it is free.
- Effectful programs: the continuation must be data. The emitter must
  emit a defunctionalized machine (the same `Vec<ReturnFrame>` a Direction
  B kernel keeps), at which point the emitted code IS a compiled kernel and
  the tail-call axis only decides whether its driver is a `loop` or a
  tail call. Scheme's guarantee then saves the loop, not the machinery.

This is why the trampoline kernel is the right shape for Rust and Python
in both subsets, and why the Scheme "free" case is real but bounded to
pure programs.

## 3. Common design, all directions

### 3.1 Source representation

The two directions consume different things, because they sit at
different places in the compilation topology of `docs/agents/
architecture.md` and the register design's section 2:

    named datoms --resolve--> resolved tuples
                                 |\
                                 | \-> lower-stack    -> stack image (H)
                                 |  \-> lower-register-> register image (R)
                                 |   \-> emit-<target> -> target source
                                 |                        (no identity)
    stack image (H) ---------------> stack kernels: CLJ, CLJS, CLJD (B3)
    register image (R) ------------> register kernels: R4 (gated), and
                                     every Direction B foreign host

Direction A emitters are LOWERINGS. They consume the resolved tuples and
their side table, call `validate-resolved` first exactly as `lower-stack`
and `lower-register` do, and refuse on its diagnostic. They never consume
the stack image, the register image, or named datoms. This is the owner's
correction of the first revision, which had emitters decode the stack
image; that choice was wrong, for a reason the first revision's own
section 3.6 exposed: decoding a stack image into blocks with static stack
effects was reconstructing the expression tree that the resolved tuples
already are. An emitter that starts from the tree performs the same walk
`lower-stack` performs (operator, operands left to right, `if` arms,
lambda bodies out of line in discovery order, occurrences expanded
positionally) and prints target syntax instead of stack instructions.
There is no body-extent recovery, no basic-block analysis, and no
dependence on the `:push`/argc shape of one particular lowering.

Direction B hosts are KERNELS. They consume the canonical wire bytes of a
`:yin.debruijn.register/*` image as-is: hash the received bytes, check R
against the register descriptor (the register design's wire rule, which
mirrors D14), run the receiver closure check over `:load-free` operands
(D11, D15 as the register design applies them), validate by the register
validator's rules (shape, targets, body scope, register bounds)
reimplemented in the host, load. No resolver, no lowerer, no named
datoms, no resolved tuples exist on a foreign host. A foreign host is a
host of the register format, beside R4, not a third lowerer. The
peer-projection topology places kernels below formats, not beside
lowerers, and the register design's own rule that a register kernel
executes R, not resolved tuples, is the precedent. Interpreting resolved
tuples directly would make them an executable format, which the stack
design's section 3.1 and the register design's decision 3 both deny, and
would then require them to carry a sharing identity, which D12 denies.
Why the register image rather than the stack image is section 3.7.

Two questions the earlier revisions raised are answered here rather than
dropped:

1. Identity. Resolved tuples still have no hash, no identity and no
   sharing role. No consumer in this document needs one, because nothing
   is keyed on the tuples: a Direction A artifact is keyed by `(R,
   target, emitter-version)` (section 3.5), where R is the register
   image of the SAME resolved tuples, computed by running
   `lower-register` beside the emitter. That pairing is deterministic
   (equal resolved tuples give equal R, register design section 1.1) and
   is a composition fact carried beside the artifact, exactly as an R is
   paired with an H. An identity over the tuples themselves would, once
   `raise` below exists, be equivalent as an equivalence relation to H
   and to R and therefore the "third identity that no request, response,
   verification, or cache needs" the register design rejects. D9, D12
   and "derive, do not persist" continue to forbid it.

2. Where the register image comes from. At Level 0 and Level 1 (section
   3.3) a foreign host fetches nothing itself: it receives register bytes
   from the Clojure process that embeds it or from files that process
   wrote. That process holds R in one of two ways:

   - The common case, compiling where the program is written: it holds
     the named datoms, runs `resolve` once, and feeds the same tuples to
     `lower-register` (for R), to `lower-stack` (for H, when the program
     is also to be published) and to any emitter. No fetch is involved.
     T0 through T5 cover only this case.
   - It holds only a stack image fetched by H (B6 is the committed
     linker and serves H; R5, the register linker, is gated): it runs
     `raise`, the inverse of `lower-stack` on images `lower-stack`
     produced, stack image plus pc side table to resolved tuples plus
     resolver side table, then `lower-register`. `raise` is a new,
     bounded component with two laws as its completion criteria:
     `lower-stack(raise(img)) = img` byte for byte for every validator-
     accepted image the corpus produces, and `raise(lower-stack(t)) = t`
     as a record set up to occurrence sharing (both lowerers expand
     occurrences positionally, so sharing is not recoverable and is not
     needed: neither lowerer's bytes depend on it). A valid image that
     does not fit the linearizer's pattern (a hand-built one) is refused
     with `:not-raisable`; such an image has no register form and runs
     only on a stack kernel. `raise` is at the de Bruijn level, where no
     names have to be synthesized. Its output feeding `lower-register` is
     DECIDED here (T-D12): it is the only way a host that holds H obtains
     R before R5 exists, and the R it produces equals the R the producer
     would have computed, by the second law. It is phase T8 and is on
     the path only for hosts that must run images they did not produce.

   At Level 2, a standalone foreign host fetches over `dao.stream`
   itself, and then it needs R5 (fetch by R), because `raise` and
   `lower-register` are Clojure-side stages that no foreign host
   reimplements. Level 2 is deferred for every target, so R5 is a
   dependency of nothing scheduled here.

   Lift then resolve is not the path: B2's `lift` produces a
   `:yin.code/*` canonical instruction vector for the semantic VM, not
   named datoms (stack design section 3.2, `lift(adapt x) =
   canonical-vector(lower x)`), and the stack design states that "the
   executable image is not an inverse encoding". `raise` is that
   decompiler placed where it is smallest.

Consequences: H remains the identity the committed linker serves and R
the identity a foreign host verifies; neither is derived from the other,
both are lowered from the same tuples; and every emitter is a peer of the
two existing lowerers. Neither image is an emitter input.

### 3.2 State as Rust types

The register kernel state of the register design's section 5, `{:image
:pc :registers :frames :free-env :continuation :store :status
:primitives :modules}`, maps as follows. Names are illustrative; the
shapes are the decisions.

    enum Value {
        Nil, Bool(bool), Long(i64), Double(f64),
        Str(Rc<str>), Keyword(Name), Symbol(Name),
        Vector(Rc<[Value]>), List(Rc<[Value]>),
        Map(Rc<PMap>), Set(Rc<PSet>),
        // ratio, bigint, char: only if the host declares the class
        Closure(Rc<Closure>),        // {arity, body, frames}
        Primitive(PrimId),           // resolved :load-free primitive
        Reified(Rc<Registers>),      // {:type :reified-continuation}
        Parked(ParkedId),            // {:type :parked-continuation}
        StreamRef(Id), CursorRef(Id) // opaque, compared by identity
    }

    type Frame  = Rc<[Value]>;       // one lambda's positional locals
    type Frames = Rc<FrameList>;     // persistent cons, innermost first
    type File   = Box<[Value]>;      // one activation's L and T banks,
                                     // sized from the body's declared
                                     // register count

    struct ReturnFrame {
        return_pc: u32, frames: Frames,
        file: File,                  // caller's registers, saved
        dest: RegIdx,                // caller's rd for the result
    }

    struct Registers {               // this kernel's engine payload
        image: ImageId, pc: u32, frames: Frames,
        file: File, continuation: Vec<ReturnFrame>,
    }

    struct Machine {
        regs: Registers,
        image: Rc<Image>,            // validated, immutable
        free_env: Rc<PMap>, store: Rc<PMap>,
        primitives: Rc<PrimTable>, modules: Rc<Modules>,
        status: Status,              // Running | Halted | Blocked | Parked
    }

    enum Next { Step, Halt, Park(ParkRequest), Refuse(Diagnostic) }

    fn step1(m: &mut Machine) -> Next     // one instruction, no recursion
    fn run(m: &mut Machine) -> Outcome    // loop { match step1(m) { .. } }

Decisions embedded here:

1. The continuation is `Vec<ReturnFrame>`, data, and `step1` never calls
   itself or any emitted body recursively. A `:call rd f args tail?`
   with `tail?` true replaces `frames` and `file` and does not push a
   `ReturnFrame`; with `tail?` false it pushes exactly one, carrying the
   caller's file and `rd`. This is B3's `step-call` with a register
   file where B3 has `stack_base`, and it is the whole reason the Rust
   host is a state machine rather than a recursive evaluator. Native
   stack depth is constant per step. This is the hard constraint of
   section 2, satisfied by construction. At every non-tail call site the
   kernel clears the caller's temporaries that the image marks dead, if
   the register descriptor carries a live set, and otherwise saves the
   whole file; either way the saved file is data and its size is the
   body's declared count.
2. `Frames` is a persistent cons list innermost-first, so `:load-bound
   rd depth position` is a walk of `depth` cells writing `rd`. B3 stores
   a vector outermost-first and reads from the end; the register design
   keeps B3's frame direction and says the conversion is explicit and
   not observable. The L bank of the current activation holds the same
   values as frame 0; the register design keeps `:load-bound` for depth
   0 rather than aliasing, and so does this kernel. Closures share frame
   tails by `Rc`, which is the persistent sharing the Clojure vector
   gives for free.
3. Persistent maps for `store` and `free_env`, so that a park snapshot is
   a cheap clone and no mutation is visible across a park. A `PMap` here
   is a library type (per the project rule to use libraries below the
   `dao.stream` boundary rather than hand-rolling); the choice of crate
   is an implementation detail.
4. Scalar classes: the Rust host declares its supported subset of the
   fourteen B1 classes and refuses an image using an undeclared class
   before execution, exactly as CLJS and CLJD do for ratio and char
   (D10). The initial subset is the eleven-class common domain: nil,
   bool, long, double, string, keyword, symbol, vector, list, map, set.
   Ratio, bigint and char are additive later, via a bigint crate, with a
   bump of the host's declared class set. Equality and hashing of
   `Value` follow Clojure semantics (vector and list equal when
   sequential-equal, doubles by value, maps by entry set) because the
   primitive `=` and map keys depend on it; this is the largest single
   parity obligation on any foreign host and is tested by the T0 corpus.
5. Errors are values: `Next::Refuse(Diagnostic)` with the qualified rule
   keyword, never a Rust panic across the step boundary. The B0
   normalizer compares errors by message and normalized ex-data, so the
   Rust host must produce the same messages for the shared corpus.
6. Primitives are a table indexed by symbol, resolved at `:load-free`
   time in the order `free_env`, `store`, `primitives`, `modules`,
   matching `engine/resolve-var`. Module resolution is empty on a foreign
   host in every phase below; a namespaced free name that is not a
   primitive refuses with the same message.

The same mapping, with types relaxed, is the Python host's class layout
and the defunctionalized emitters' runtime prelude. It is written once
per target language, as the target's "kernel crate" or prelude, and it
is shared by Direction B and Direction A on that target (section 5.3).

### 3.3 Effects, streams, scheduling

`yin.vm.engine` is Clojure-specific machinery talking to `dao.stream`,
`dao.stream.waitset` and the lease layer. A foreign host does NOT
reimplement it in the early phases. Three levels, in order:

Level 0, pure. The host executes images whose instruction set is R1's
(register design section 4.4): `:const :load-bound :load-free :closure
:move :call :branch-false :jump :return :halt :store-get :store-put`,
plus `:call` on a resolved pure primitive. Any R2 opcode refuses with
`:not-yet-implemented` before execution (a static scan of the image, not
a runtime trap, so a refused image never starts). No stream, no park, no
host boundary crossing. This matches how the register design gated R1
(pure lowering) before R2 (effects) and R4 (kernel).

Level 1, offload. The foreign kernel is a REMOTE STEP FUNCTION for the
existing Clojure engine. It runs until `:halt` or until a blocking or
continuation instruction, then returns its whole `Registers` plus the
effect request as DATA to the embedding Clojure process. The Clojure
engine schedules exactly as it does for any VM under the engine seam:
the returned `Registers` is this kernel's register payload `{:image :pc
:frames :file :continuation}`, which the engine never reads (engine
design section 3: every key it does not own is the VM's payload,
preserved verbatim), and the restore function of the engine's signature
re-enters the foreign kernel with that payload and the woken value. No
foreign-side wait set, ready queue or `dao.stream` client exists. The
park boundary is the host boundary, and it is crossed by data only,
which is the project's own rule for park in any case. This level
requires a serialization of `Registers` and of `Value` including
closures (`arity`, `body`, `frames`), for which the B1 scalar encoding
extended with closure and continuation tags is the natural wire form.
Level 1 depends on B4's three additive engine edits (the three-argument
restore and `scheduler-round` are what the Clojure-side adapter binds
to), on R2 (the register shapes of the effect instructions), and on a
transport for the data, which is composition plumbing below the
`dao.stream` boundary: a subprocess pipe, an FFI call, or a `dao.stream`
itself. It does not depend on R4: the payload is opaque to the engine
and is restored only by the kernel that produced it. What R4 would add
is a Clojure-side kernel able to restore the same payload without the
foreign host, and that fallback is not promised here. Level 1 is where
park/resume across a host boundary first exists, and it exists as data
transfer, not as a foreign scheduler.

Level 2, standalone host. The foreign host owns a scheduler and a
`dao.stream` client and runs effectful programs with no Clojure process.
This requires the engine contract (`run-loop`, wait set, ready queue,
park and resume records, the FFI two-step) reimplemented in the target
language, a `dao.stream` wire client (DaoJing CBOR framing once that work
lands, decoded with a CBOR library, never hand-rolled), and R5's fetch-
by-R, since a foreign host holds no `raise` or `lower-register`. It is
the level the portability document's effect boundary describes. It is
NOT designed here beyond naming its dependencies (B4, R5,
`dao.jing.cbor`) and is deferred (section 9).

Recommendation: every target stops at Level 0 until the owner asks for
more; Rust is the only target for which Level 1 is planned in this
document (T5); Level 2 is deferred for all targets.

### 3.4 Conformance: what a foreign host must reproduce

The oracle is the B0 normalizer over the pure parity corpus, run on the
JVM lane, exported as data. The expected results are program semantics,
computed by the named VM and the stack VM, which exist; they do not
depend on any register kernel existing. A foreign host is conformant at
Level 0 when, for every corpus program (canonical register wire bytes
plus expected normalized result or error), it reproduces the expected
value under the normalizer, refuses every image the register validator
refuses with the same rule keyword, computes the same R over the same
bytes, and refuses images using scalar classes outside its declared set
with `:unsupported-value` before execution.

What the kit cannot check without a first-party register kernel is
format-level behaviour the normalizer erases: continuation shape, the
saved-file contents at a park, spill and move behaviour. B0 compares
continuations by type only, so those are invisible to the kit by
design. Until R4 exists, the Clojure-side cross-check for them is the
reference register interpreter the register design's R3 already
envisages for its benchmark harness; section 7 makes that interpreter
T0's second export. The alternative, accepting a foreign host as the
first register executor with kit-only checking, is an owner decision
(section 10) and is not assumed.

Host-divergent numerics (long overflow, integer/double folding on CLJS,
Dart's own classes) already split the three existing lanes into a common
corpus plus per-host fixtures; foreign hosts inherit that rule. The
exported corpus is the "host conformance kit" (T0) and is shared by
every target in this document, which is what makes a new host a bounded
job: implement the value model, the primitives, the validator, the step
function, and pass the kit.

A required fixture in the kit: a tail-recursive loop of at least one
million iterations and a mutual tail recursion of the same depth,
completing with constant native stack. On a Direction B host this tests
the driver; on a Scheme Direction A emitter it tests that the emitter
placed calls in tail context and did not wrap them.

### 3.5 Identity and sharing

Direction B host binaries are host capabilities, like the JVM or Dart
runtime. They have no identity in the H/R/linker model, are not fetched
over `dao.stream`, and are not sharing participants. Only images cross
the stream. A foreign host is, in the vocabulary of
`docs/agents/architecture.md`, a destination that "runs the same VM
model" and therefore "can use the bytecode directly"; the register image
travels with R, and the host executes it or refuses it.

Direction A artifacts are NOT an executable format under invariant I.

1. A native artifact (Rust, C, WASM binary) is host- and architecture-
   specific. H and R are defined host-independent; no property of a
   native binary can be verified by a receiver against either except by
   rebuilding it. A compiled artifact is therefore local-only, rebuilt-
   not-fetched, on every host that wants it. It is a cache keyed by `(R,
   target-triple, emitter-version)`, and a cache is an explicit value
   keyed by an image identity exactly as the stack design permits
   (section 2 there). It never crosses `dao.stream` as code.
2. Hosted-language emitted SOURCE (Clojure, Python, Scheme text) is
   host-independent bytes and could be canonicalized and hashed. It is
   still not given an identity. Reasons: D9 and D12 and their register
   mirror make H and R the only identities on the sharing path; a
   receiver cannot validate emitted source with either validator or run
   the closure check over it, so accepting it would mean trusting an
   emitter instead of verifying an image; and the same source is a
   deterministic function of the resolved tuples and the emitter version,
   so any receiver holding R can regenerate it through `raise` and
   `lower-register`'s inverse walk, or holding H through `raise` alone.
   Emitted source is a local cache with the same key as a native
   artifact. A composition MAY publish such caches as opaque blobs, but
   a receiver MUST NOT execute one it did not derive itself. Stated as a
   decision in section 8 (T-D5).
3. Continuations produced by a Direction A artifact, when they exist
   (Level 1 and above), are the section 3.2 register payload, and their
   return points are labelled with the pcs `lower-register` assigns to
   the same resolved tuples, never with positions in emitted code. The
   emitter obtains those labels by running `lower-register` beside
   itself on the same tuples, a deterministic pairing (section 3.1), and
   its activation record is the register file of section 3.2, so the
   payload is the kernel's payload without conversion (section 3.7 item
   5). This keeps an offloaded continuation restorable by the register
   kernel on a host without the artifact: the artifact is an accelerator
   for a machine whose state vocabulary is the register image's. Pure-
   subset emitters (T3, T4) expose no continuation and need no labels.

### 3.6 The shared emitter front end (Direction A)

One `.cljc` namespace walks the resolved tuples in the linearizer's order
and produces a target-neutral body form: one body per resolved lambda
plus the main body, each a tree of `:literal`, bound reference `[depth
position]`, free reference, `:closure` (arity, body id), `:if`, and
`:application` with its copied `:yin/tail?`, with every intermediate
value named in evaluation order. For trampoline targets the front end
additionally marks each non-tail application as a return point, so a
printer can emit a per-body state machine whose driver re-enters at that
point; for direct-style targets the marks are ignored. This is the tree
the resolver already produced with names attached to intermediates; no
analysis of any flat image occurs. Per-target work is then:

    +--------------------+----------------------------------------------+
    | Per target         | Contents                                     |
    +--------------------+----------------------------------------------+
    | printer            | body form to source text; a few hundred     |
    |                    | lines; no analysis                          |
    | runtime prelude    | value model, primitives with Clojure        |
    |                    | semantics, driver loop where the axis needs |
    |                    | one; shared with that target's Direction B  |
    |                    | host when one exists                        |
    | build recipe       | how to invoke the target compiler; not      |
    |                    | part of the repository's own lanes          |
    +--------------------+----------------------------------------------+

This is the "cheap additional target" claim for Direction A, and it is
weaker than Direction B's: a printer is cheap, but the prelude is the
same size as a Direction B host's value model and primitives, because it
IS that. Hence the phasing: build the host first, then the printer.

### 3.7 The kernel format: the register image

Every Direction B foreign kernel interprets the register image. This is
the owner's decision of 2026-09-23, replacing two earlier revisions that
chose the stack image for today and the register image for a later
state. The reasons are properties of the two formats themselves, and
none depends on what happens to be built first.

1. Resource bounds are declared, not inferred. A register body declares
   its register count, and the register validator checks every
   destination and source against it before execution. The stack format
   declares no operand-stack height anywhere, and its validator's rules
   (shape, mnemonic, arity, operand kind, saturation, target bounds,
   terminator, scope) do not verify stack discipline; a validator-
   accepted stack image can still underflow at runtime, and every host
   must carry that as a runtime check. For a foreign host, "assume
   hostile or malformed inputs" and "VM boundaries are security
   boundaries" (`docs/agents/architecture.md`) are cheaper to honour
   when the format itself bounds every access statically and an
   activation is a fixed-size array whose size is read from the image.
   This is "state is explicit data" applied to the machine's own
   resources.
2. Fewer dispatches per program point. The register format has no
   `:push` and names its operands, so each instruction does the work of
   a stack instruction plus the pushes and pops around it. In an
   interpreter, dispatch is the cost, and it dominates most in the slow
   host languages Direction B exists for. The direction of this
   advantage is intrinsic; only its magnitude varies by host. R3 will
   measure that magnitude on the reference hosts; this document does not
   wait for the number, because the direction is what decides a format.
3. The interpreter is the runtime path, so its speed is not Direction
   A's job. Direction A is ahead-of-time, and the project's own scenario
   is code and continuations that arrive over a stream and must run on
   arrival, with no target compiler in the loop. That code runs in the
   kernel. A host that has both a kernel and an emitter still executes
   arriving images in the kernel.
4. A cleaner instruction set to port. The stack format inherits its
   layout from the named linearizer (the structural-comparison law in
   the stack design's section 3.2), which is why B3's `:push` is a no-op
   with a documented obligation on every value-producing opcode. The
   register format was designed from the resolved tuples with no layout
   obligation and has no such convention. A porter of the register
   format has fewer things to know that are not in the instruction
   table.
5. The activation model matches emitted code. An emitter lowering the
   resolved tuples names its intermediates in evaluation order (section
   3.6), which is a temporaries bank, not an operand stack. A register
   kernel's activation record and return frame (register file plus
   destination plus return point) are therefore the same shapes emitted
   code uses, so the offload extension of section 5.2 that lets a
   continuation parked under emitted code restore under the kernel is a
   label mapping, not a reconstruction of an operand stack from named
   locals. This is the surviving form of the "shared runtime" argument:
   not shared decoding, which emitters do not do, but a shared
   activation model.

What the stack format keeps in its favour, and why it does not decide
it: its continuation is smaller and uniform (one shared operand stack,
a `stack_base` per frame, no destination register, and no dead values,
since everything on an operand stack is live), whereas a register
continuation saves a register file per frame and can carry dead
temporaries unless liveness is also carried. That is a constant factor
per activation on a payload that is data either way, and it is the one
place a register kernel must be careful (section 3.2 item 1). The
Ribbit precedent chose a stack machine for a 4 KB footprint goal that
this project does not have. The stack image's other advantage, that it
is already built and served by the committed linker, is a fact about
sequencing, not about the format, and section 3.7.1 states what that
sequencing costs rather than letting it choose the format.

#### 3.7.1 What committing to the register image costs in sequencing

Stated plainly, because the register image does not exist yet:

1. The first foreign kernel phase (T1) depends on R0 and R1, the
   register contract, dimension, lowerer, validator and golden R bytes
   on three lanes. R1 is not authorized to start. Before this revision
   T1 depended only on B1-B3, which are merged. Authorizing T0 or T1 now
   means authorizing R0 and R1 first; there is no register kernel work
   that does not.
2. T1 does not depend on R4. The kit's expected results are program
   semantics computed by the VMs that exist (section 3.4), and the
   Level 1 payload is opaque to the engine (section 3.3). What is lost
   without R4 is a first-party cross-check of format-level behaviour
   the normalizer erases. This document closes that gap with the
   reference register interpreter R3 already plans, exported by T0 as
   its second deliverable and used as the cross-check for every foreign
   kernel; the register design's gate on R4 as a PRODUCTION kernel is
   untouched, since that interpreter is a test oracle, not a fourth
   evaluator for consumers. If the owner declines the interpreter, the
   foreign host is the first register executor with kit-only checking,
   and the document says so in section 10 rather than hiding it.
3. T5 depends on B4's engine edits and on R2, not on R4.
4. Fetch is not on the path of any scheduled phase (section 3.1 item
   2): the embedding Clojure process supplies R. A host that holds only
   H needs T8. Level 2 needs R5 and is deferred.
5. Frames and closures are the same data in both formats (register
   design section 4.2 keeps B3's frame direction and capture), so if a
   stack kernel were ever wanted on a foreign host it would be a change
   of activation model, not of value model, primitives or driver. None
   is planned.

## 4. Recommendation per target family

    +----------------+-----------+-----------------------------------------+
    | Family         | Order     | Reasoning                               |
    +----------------+-----------+-----------------------------------------+
    | Rust           | B, then A | B gives a native host binary once R1   |
    |                | (gated)   | lands and builds the kernel crate A     |
    |                |           | needs; A is gated by a benchmark, as    |
    |                |           | R4 is by R3. Level 1 offload planned    |
    |                |           | for Rust only.                          |
    | Python         | B only    | Interpreter in Python is the Ribbit     |
    |                |           | case exactly; A on CPython buys little  |
    |                |           | over B and costs a printer plus prelude |
    | Gambit, Chez   | A (pure   | Direct style is nearly free and gives   |
    |                | subset)   | tail calls and closures from the host;  |
    |                | first;    | B only if effectful programs on Scheme  |
    |                | B later   | are wanted, and then B's driver is a    |
    |                | if wanted | tail call, the simplest B of all        |
    | Clojure (JVM)  | B is R4,  | The first-party register kernel is R4,  |
    |                | gated;    | owned by the register design and gated  |
    |                | A gated,  | by R3; B3 runs the stack image today.   |
    |                | deferred  | A (tuples to fns with a typed driver)   |
    |                |           | is a pure speed play and needs the same |
    |                |           | benchmark gate before it is commissioned|
    | C, WASM, ARM,  | via Rust  | rustc targets them; a C printer is a    |
    | x86            |           | peer of the Rust printer and is not     |
    |                |           | needed while Rust covers them           |
    +----------------+-----------+-----------------------------------------+

Global shape: a phased plan, not an either/or. Direction B first wherever
a host does not yet exist (Rust, Python), Direction A first where the
target makes it nearly free and the pure subset is the useful one (Scheme),
Direction A gated behind measurement where a host already exists (Clojure,
and Rust after T1).

## 5. Rust: the first worked example

### 5.1 T1, the Rust host (Direction B, Level 0)

One crate. Modules: `value` (section 3.2 types, Clojure equality and
hashing), `scalar` (decoder for the B1 scalar encoding the register
descriptor reuses, the fourteen tags, refusal of undeclared classes),
`image` (register wire framing, `register_hash` over the received bytes,
descriptor check, the register validator reimplemented from its stated
rules: shape, targets, body scope, register bounds, so that every `rd`
and source index is proven in-bounds before execution and an activation
is allocated at its declared size), `prims` (the pure primitive table
with Clojure semantics), `machine` (`step1`, `run`, `Next`, over the
Level 0 instruction set of section 3.3), and a `conformance` test that
loads the T0 kit and asserts every fixture. The crate has no I/O beyond
reading fixture files and no dependency on the Clojure process. Entry:
`run(bytes, free_env) -> Outcome`, where `Outcome` is `Halted(Value) |
Refused(Diagnostic)`.

Correctness argument for tail calls: `step1` for `:call` with `tail?`
true allocates the callee's file at its declared size, binds the
arguments positionally with nil-fill and extras dropped, replaces
`regs.frames`, `regs.file` and `regs.pc`, and does not push; the driver
`loop` re-enters `step1`. Native depth is one frame regardless of program
depth, and heap use is one file per LIVE activation, not per call. The
T0 million-iteration fixtures prove both.

### 5.2 T4, the Rust emitter (Direction A, gated)

Input: the section 3.6 body form of one program's resolved tuples.
Output: a Rust source file that depends on the T1 crate's `value`,
`scalar`, `prims` and the `ReturnFrame`/`Registers` types, and defines
one `fn body_N(m: &mut Machine, entry: Label) -> Next` per body, plus a
`dispatch(label)` table used by the driver. Inside a body, intermediates
are local `Value` bindings in evaluation order; there is no operand stack
and no `:push`. `:call` with `tail?` true replaces `frames` and returns
`Next::Enter(body, entry)` to the driver; with `tail?` false it pushes a
`ReturnFrame` naming the return point after the call and enters the
callee the same way. Bound loads walk `Frames` as in T1; the emitter does
not change frame layout, so closures created by emitted code and by the
interpreter are the same data. The driver is the T1 `run` loop with
`step1` replaced by `dispatch`. Labels are emitter-local in T4; when T5
offload is extended to emitted code, labels are the register pcs of
section 3.5 item 3, obtained from `lower-register` over the same tuples,
and the emitted body's local bindings are laid out as the register file
that image declares, so that the payload is the T1 kernel's payload and
a continuation parked under emitted code restores under the T1
interpreter without conversion.

Gate: T4 is commissioned only if a benchmark (T1 interpreter versus JVM
B3 versus a hand-written Rust translation of two corpus programs) shows a
material gap that an emitter would close. The number is the owner's, as
R3's is.

### 5.3 What Direction B and Direction A share on Rust

Everything except `machine::step1`, the image decoder, and the printer:
the value model, scalar rules, primitives, `Registers`, `ReturnFrame`,
the driver loop and the conformance harness. This is the concrete reason
B precedes A on Rust: T1 is roughly the whole of T4's runtime. The two
have different INPUTS (T1 the register image, T4 the resolved tuples)
and the same RUNTIME, down to the activation record (section 3.7 item
5), which is what section 3.1's topology predicts: kernels and lowerings
differ in what they read, not in what a value, a frame or a file is.

### 5.4 T5, offload (Level 1)

Add to the crate a `park` path: on any R2 instruction (the register
shapes of `:stream-*`, `:park`, `:resume`, `:current-continuation`,
`:ffi-call`, `:gensym`), `run` returns `Outcome::Parked { regs:
Registers, request: Effect }` serialized with the extended scalar
encoding, and a `resume(regs, val)` entry that rebuilds `Machine`,
writes `val` into the parked instruction's `rd`, and continues. On the
Clojure side, a restore function of the engine's signature `base entry
val -> state` that forwards the entry's register payload and `val` to
the crate and reads back the outcome; the engine, wait set, lease and
streams are untouched. The Clojure-side file is a new namespace, not an
edit to `engine.cljc`, `stack.cljc` or any register namespace. Depends
on B4's engine edits and on R2.

## 6. Python, Scheme, Clojure

### 6.1 Python host (T2)

A single module or small package mirroring the T1 crate's module split,
with `Value` as a small class hierarchy or tagged tuples, `Frames` as a
linked list, and the driver as a `while` loop. Python's arbitrary ints
and `fractions.Fraction` make bigint and ratio cheap to declare; char is
not declared (Python has no char class, matching CLJS and CLJD). Clojure
equality semantics for `=` and for map keys must be implemented, not
inherited from Python's, which conflates `1 == 1.0 == True`. This is the
second foreign host and its purpose beyond reach is to prove the T0 kit
is host-neutral: two independent implementations passing the same
exported fixtures.

### 6.2 Scheme direct-style emitter (T3)

A printer from the section 3.6 body form to R7RS-small source using only
the intersection Gambit and Chez both accept, with a per-implementation
prelude of a few lines for anything outside it (UNVERIFIED which forms
differ; expected: record definitions, hash tables, bytevector I/O). A
resolved lambda becomes a `lambda` of `arity` parameters; a bound
reference `[depth position]` prints as the `position`-th parameter of the
`depth`-th enclosing `lambda`, from a name stack the printer maintains
exactly as the resolver did, with synthesized names fresh against the
program's free names (the lift's own rule); there is no `Frames`
structure because Scheme's own closures capture. A free reference prints
as a prelude global. A `:call` becomes an application, in tail position
when `tail?` is true. Missing arguments nil-fill and extra arguments drop, so
each lambda takes a rest argument and binds positionally, or the emitter
emits an arity-adapting wrapper; the exact form is the printer's, and
either must pass the nil-fill and extras-dropped fixtures. `:if` is
`if`, `:store-get`/`:store-put` are operations on a prelude hash table,
and the eleven-class common domain maps onto Scheme values with a small
prelude for keywords and symbols so that the `=` primitive keeps Clojure
semantics.

The pure-subset restriction (section 2) is enforced by a static scan of
the resolved tuples, the tree-level twin of the opcode scan Level 0 hosts
run on images: a program containing any stream, park, resume,
current-continuation, gensym or FFI node is refused by the emitter with
`:not-yet-implemented`, never emitted as direct-style code that would
hide its continuation in the Scheme stack.

Why this is worth doing before any Scheme Direction B host: it is the
smallest possible demonstration that an image is a program, not just
bytes for one interpreter, and Scheme is the one family where the
demonstration costs a printer and a prelude only. If effectful programs
on Scheme are ever wanted, a Direction B host in Scheme reuses the
prelude and adds the section 3.2 state machine, whose driver is a tail
call rather than a loop.

### 6.3 Clojure Direction A (deferred)

The JVM already runs the stack image through B3, and R4 is the gated
first-party register kernel. A Clojure emitter would be a peer lowering
like the others: from the resolved tuples to a vector of
Clojure fns (one per body, entered at a return point) closed over the
runtime, invoked by a typed driver: a body returns `Value` or a `Step`
record naming the next body and entry, and the driver loops on `Step`.
(Closure-compiling the stack IMAGE at load time is a different thing, an
interpreter technique inside the existing host, and is not a lowering;
it is not proposed here.) `clojure.core/trampoline` is not
used, because yin closures are values and the docstring's own caveat
applies: a fn returned in tail position would be mistaken for a thunk.
This is a speed optimization of an existing host and is commissioned
only through a benchmark gate, as R4 is. It is listed for completeness
and is deferred.

## 7. Phases

Each phase has a file box, a must-not-change list, completion criteria
and verification. Foreign-language toolchains (cargo, python3, gambit,
chez) are not part of the repository's JVM/CLJS/CLJD lanes; each phase
states what runs where. No phase edits an existing `.cljc` namespace.

    +-----+----------------------------+------------------------------------+
    | Ph  | Deliverable                | Depends on                         |
    +-----+----------------------------+------------------------------------+
    | T0  | host conformance kit and   | B0-B3 merged (done); R0 and R1     |
    |     | reference register interp. | (NOT authorized, NOT started)      |
    | T1  | Rust host, Level 0         | T0                                 |
    | T2  | Python host, Level 0       | T0                                 |
    | T3  | Scheme direct-style        | B2 (done); Gambit and Chez         |
    |     | emitter, pure subset       | toolchains; no register dependency |
    | T4  | Rust emitter (gated)       | T1; benchmark gate passed          |
    | T5  | Rust offload, Level 1      | T1, B4 engine edits, R2            |
    | T6  | any standalone Level 2     | B4, R5, dao.jing.cbor; deferred    |
    | T7  | Clojure Direction A        | benchmark gate; deferred           |
    | T8  | raise: stack image to      | B2; needed only by a host holding  |
    |     | resolved tuples, then R    | H and not the tuples; deferred     |
    +-----+----------------------------+------------------------------------+

The real chain for a register-targeting foreign host, in order: R0
(register contract and corpus), R1 (register dimension, lowerer,
validator, golden R on three lanes), T0, T1. R0 and R1 belong to the
register design and are not authorized; nothing in this document starts
until they are. R3 and R4 are NOT on the chain: R4 remains gated by R3
exactly as the register design states, and the reference interpreter T0
exports is a test oracle inside R3's own remit ("a reference register
interpreter or instrumentation harness"), not R4. T3 is the one phase
with no register dependency, because emitters consume the resolved
tuples. Order: R0, R1, then T0; then T1 and T2 may run in parallel; T3
may run at any time after B2, which is merged; T4 and T5 follow T1 and
are independent of each other; T6, T7 and T8 are deferred and not
scheduled.

### T0: host conformance kit and reference register interpreter

    New: test/yin/vm/debruijn/host_conformance_test.cljc
    New: test/resources/yin/debruijn/conformance/  (generated fixtures)
    New: test/yin/vm/debruijn/register_reference.cljc  (test-only
         interpreter of the Level 0 register instruction set; lives
         under test/, is not a consumer-facing evaluator, and is the
         harness R3 names)
    Existing edits: none
    Depends on: R0, R1 merged
    Must not change: R1 bytes, R, H, the B0 normalizer, the parity
    corpus, the R4 gate

Export, from the JVM lane, every pure parity-corpus program as canonical
register image bytes, R, and the B0-normalized expected result or error
(computed by the named VM and cross-checked against the stack VM, which
exist), plus the refusal fixtures (malformed rows, out-of-range operands,
register-bound violations, undeclared classes) with their rule keywords,
plus the tail-depth fixtures of section 3.4 and a Level 0 opcode scan
fixture (an image containing an R2 opcode, expected
`:not-yet-implemented`). Each fixture also records the corpus id of its
named root, so an emitter test in the Clojure process can pair `resolve`
of that root with the same expected normalized result; the kit exports
no resolved tuples, which have no wire form and no identity. Fixture
format is data (EDN and a JSON mirror so hosts without an EDN reader can
load it). The test asserts that CLJS and CLJD lanes reproduce the JVM kit
exactly for the common domain, which makes the kit host-neutral before
any foreign host consumes it.

The reference interpreter executes every kit image on all three lanes
and must reproduce every expected result; it also emits, per fixture,
the format-level trace the normalizer erases (register file at each
non-tail call, saved files in the continuation, spill and move effects)
as a second, separately versioned export, which foreign kernels compare
against under T1 and T2. Completion: kit and traces generated
deterministically on all three lanes; the interpreter has B0 parity
over the corpus; a change to any golden byte fails the test.

### T1: Rust host, Level 0

    New: src/host/rust/yin-debruijn/  (Cargo crate; modules per 5.1)
    Existing edits: none
    Depends on: T0
    Must not change: any .cljc, the kit, R

Completion: every T0 fixture passes under `cargo test`; R recomputed
over fixture bytes equals the fixture R; register-bound violations and
undeclared classes refused before execution; the format-level trace
matches T0's for every fixture; million-iteration tail fixtures pass
with a release-mode stack limit set deliberately small and with heap
bounded to one file per live activation; no `unsafe`; no recursion in
`step1` or `run` (a test walks the crate for recursive calls, or a debug
assertion checks native depth per step). Verification: `cargo test`;
JVM/CLJS/CLJD lanes unchanged because no `.cljc` changed.

### T2: Python host, Level 0

    New: src/host/python/yin_debruijn/
    Existing edits: none
    Depends on: T0
    Must not change: any .cljc, the kit, R

Completion: same criteria as T1 under `python3 -m unittest`, with
bigint and ratio declared and char not declared. Verification as T1.

### T3: Scheme direct-style emitter, pure subset

    New: src/cljc/yin/vm/debruijn/emit.cljc        (section 3.6 front end)
    New: src/cljc/yin/vm/debruijn/emit_scheme.cljc (printer)
    New: src/host/scheme/prelude.scm, prelude-gambit.scm, prelude-chez.scm
    New: test/yin/vm/debruijn/emit_test.cljc
    New: test/yin/vm/debruijn/emit_scheme_test.cljc
    Existing edits: none
    Must not change: B1-B3, the kit, the stack VM

Completion: the front end calls `validate-resolved` first and refuses on
its diagnostic; its body form is deterministic on all three lanes; the
sequence of bound and free references it visits equals the register
design's section 2.2 address law over the corpus (the same cross-check
`lower-stack` and `lower-register` satisfy, which is what makes the
emitter a third peer rather than a fork); emitted Scheme text is byte-
identical across lanes; programs with any effect node are refused; on
both Gambit and Chez,
every T0 pure fixture evaluates to the normalized expected value and the
tail-depth fixtures run in constant stack with no trampoline in the
emitted text. The Scheme runs are a shell harness outside the `.cljc`
lanes. Verification: JVM/CLJS/CLJD lanes for the `.cljc` files, kondo,
cljstyle; Gambit and Chez for the emitted programs.

### T4: Rust emitter (gated)

    New: src/cljc/yin/vm/debruijn/emit_rust.cljc
    New: test/yin/vm/debruijn/emit_rust_test.cljc
    New: src/host/rust/yin-debruijn/src/emitted/  (build recipe, tests)
    Existing edits: none
    Must not change: T1's value, scalar, image, prims modules; the kit

Gate: a benchmark report (T1 versus JVM B3 versus hand-written Rust on
two corpus programs) reviewed by the owner; the threshold is the owner's.
Completion: the emitter consumes resolver output and satisfies the
address law as T3 does; emitted programs pass the kit's pure fixtures
with the same normalized results the T1 interpreter produces for the
paired R; closures built by emitted code and by the T1 interpreter are
byte-identical under the T5 wire form. The mixed interpreter/emitted
continuation test belongs to the T5 extension for emitted code (section
5.2), not to T4, because pure programs expose no continuation.
Verification as T1 plus the `.cljc` lanes.

### T8: raise (deferred)

    New: src/cljc/yin/vm/debruijn/raise.cljc
    New: test/yin/vm/debruijn/raise_test.cljc
    Existing edits: none
    Must not change: lower-stack, lower-register, H, R, the resolver

Completion: the two laws of section 3.1 over the parity corpus and the
shared-occurrence fixtures, `:not-raisable` on a hand-built valid image
outside the linearizer's pattern, determinism on all three lanes, and
the derived-R law: `lower-register(raise(lower-stack(t))) =
lower-register(t)` byte for byte over the corpus, which is what lets a
host holding only H obtain the same R the producer would have computed
(T-D12).

### T5: Rust offload, Level 1

    New: src/host/rust/yin-debruijn/src/park.rs, wire.rs
    New: src/cljc/yin/vm/debruijn/offload.cljc  (B4-signature restore,
         transport adapter)
    New: test/yin/vm/debruijn/offload_test.cljc
    Existing edits: none
    Depends on: T1, B4's three engine edits, R2
    Must not change: engine.cljc, ffi.cljc, stack.cljc, any register
    namespace, the engine-owned key sets, dao.stream, lease, waitset

Completion: for every B4 effect fixture lowered by R2, the offloaded
run's normalized outcome equals the stack VM's under B4; a parked
`Registers` serialized by Rust survives an EDN round trip through the
engine's ready queue and parked map verbatim and restores in Rust; the
T0 reference interpreter, extended in this phase to the R2 instruction
set under test/, restores the same payload to the same normalized
outcome (the cross-host restore check that R4 would otherwise give); no
closure or handle crosses the boundary, only data; the transport is
replaceable (the test runs it over an in-process pipe and over a
`dao.stream`).
Verification: JVM lane for the `.cljc` (CLJS and CLJD lanes compile it
but the Rust transport is JVM-only and skips elsewhere, recorded as a
host skip), `cargo test`.

### T6, T7: deferred

Level 2 standalone hosts and Clojure Direction A are not scheduled and
have no file boxes here. Their dependencies are stated in sections 3.3
and 6.3.

## 8. Non-goals and what this does not change

1. The stack image (H) and, if built, the register image (R) remain the
   portable, canonical, content-addressed executable formats. Nothing in
   this document is a new source of truth, a new bytecode, or a new
   identity.
2. No change to B0-B7, R0-R5, `yin.vm.engine`, `yin.vm.ffi`, the stack
   VM, the named VM, `dao.stream`, lease, waitset, or `dao.jing`.
3. Not a JIT, not equality saturation, not lambda lifting or ANF (those
   remain possible upstream AST stages per the stack design's 7.1), not
   a replacement for the three existing hosts.
4. Does not require B4-B7 or R3-R5 first, but DOES require R0 and R1.
   T0, T1, T2 and T4 depend on B0-B3 (merged) and on R0 and R1 (not
   authorized). T3 depends on B2 only. T5 depends on B4's engine edits
   and on R2. Any Level 2 host depends on R5 for fetch-by-R and on
   `dao.jing.cbor` for the stream wire. R4, the first-party register
   kernel, is on no phase's path; its R3 gate is untouched.
5. Native and hosted artifacts are local caches, never executable code
   on the sharing path (section 3.5).
6. This document does not adopt `yin.vm-portability.md`'s Yang text
   preprocessor, base-92 stream embedding, or cross-language macros, and
   does not retire them; see section 10.

DECIDED:

1. T-D1 directions: phased, per family, per section 4. Direction B
   first for Rust and Python; Direction A pure-subset first for Scheme;
   Direction A for Clojure and Rust gated by benchmark.
2. T-D2 source: Direction B hosts are kernels of the register format
   and consume canonical register image bytes as-is; resolved tuples
   remain non-executable and identity-free. Direction A emitters are
   peer lowerings and consume the resolved tuples plus side table,
   calling `validate-resolved` first, never the stack image, the
   register image or named datoms. The Clojure process that embeds a
   foreign host supplies R by `lower-register` over tuples it holds, or
   by `raise` then `lower-register` when it holds only H (section 3.1).
3. T-D3 continuation: in every direction and family the continuation is
   explicit data (`Vec<ReturnFrame>` or its target equivalent) and the
   native stack depth is constant per step; direct-style emission is
   permitted only for images with no B4 opcode, enforced by a static
   scan that refuses rather than emits.
4. T-D4 effect levels: Level 0 pure for every target first; Level 1
   offload keeps the Clojure engine as the only scheduler and crosses
   the host boundary with the kernel's register payload as data, opaque
   to the engine; Level 2 is deferred for all targets.
5. T-D5 identity: host binaries and compiled or emitted artifacts have
   no identity and are not sharing participants; they are local caches
   keyed by `(R, target, emitter-version)`, rebuilt not fetched; a
   receiver never executes an artifact it did not derive.
6. T-D6 scalar classes: each foreign host declares its class subset and
   refuses undeclared classes before execution (D10 applied); Rust
   starts at the eleven-class common domain, Python declares bigint and
   ratio, no foreign host declares char.
7. T-D7 conformance: the T0 kit, exported from the JVM lane and
   confirmed on CLJS and CLJD, is the sole conformance oracle for every
   foreign host and emitter; parity is B0-normalized, not log identity.
8. T-D8 primitives: foreign hosts implement the pure primitive table
   with Clojure equality and collection semantics; module resolution is
   empty on foreign hosts in all phases here.
9. T-D9 `trampoline`: not used by any Clojure emitter, because yin
   closures are values and the docstring's caveat applies; a typed
   driver is used instead.
10. T-D10 continuation labels: when emitted code exposes continuations
    (T5 extended to emitted code), its return frames are labelled with
    the pcs `lower-register` assigns to the same resolved tuples and its
    locals are laid out as that image's register file, so interpreter
    and emitted code share one state vocabulary and a continuation
    restores on a host without the artifact. Pure-subset emitters need
    no labels.
11. T-D11 artifact pairing: a Direction A artifact is keyed by `(R,
    target, emitter-version)` where R is `lower-register` of the same
    resolved tuples; the pairing is composition data, as R's pairing
    with H is, and gives the tuples no identity.
12. T-D12 raise: a Clojure process holding only a stack image fetched by
    H obtains R through `raise`, the inverse of `lower-stack` on images
    it produced, then `lower-register`; the two laws of section 3.1 and
    the derived-R law of T8 are its completion criteria, `:not-raisable`
    refuses images outside the pattern, and `lift` then `resolve` is not
    the path, because `lift` yields linear named code, not named datoms.
13. T-D13 kernel format: every foreign kernel interprets the register
    image, for the five reasons of section 3.7; the stack image is not a
    foreign-kernel target and no stack fallback is planned. The
    sequencing cost is stated in 3.7.1 and section 7, not traded away.
14. T-D14 reference: until R4 exists, the test-only reference register
    interpreter T0 exports is the Clojure-side cross-check for every
    foreign kernel's format-level behaviour; it is a test oracle under
    test/, not a fourth consumer-facing evaluator, and it leaves the R4
    gate untouched.

DEFERRED:

- Owner authorization of R0 and R1 (the register design's), then T0,
  and the order of T1 versus T2.
- The T4 and T7 benchmark thresholds.
- Level 2 standalone hosts: scheduler port, `dao.stream` client, fetch
  by R on a foreign host (R5).
- The serialization tags for closures and continuations in the T5 wire
  form (an extension of B1's scalar encoding or a separate small
  dimension; not decided here).
- Whether the register descriptor carries a per-call-site live set so
  a kernel can clear dead temporaries before saving a file (section 3.2
  item 1); an R1 decision, raised here, not made here.
- Ratio, bigint and char on the Rust host.
- A C printer, and WASM beyond what `rustc --target wasm32` gives.
- The relation between this document and `yin.vm-portability.md`
  (section 10).

## 9. Risks

1. Primitive semantics drift: Clojure equality and collection semantics
   reimplemented per host are the largest parity surface; the kit
   catches divergence only where the corpus exercises it. Mitigation:
   the kit includes the every-tag corpora from `content_test` and
   `completion_test`, as B0 does.
2. Validator drift: the register validator is reimplemented in each host
   from its stated rules; a rule change in `.cljc` must fail the kit's
   refusal fixtures on every host, which requires the kit to be
   regenerated and rerun as part of any R1 change.
2a. First register executor: until R4 exists, the only executors of the
   register format are the T0 test-only interpreter and the foreign
   kernels. A format defect (allocation, spill, move order) found in
   Rust must be fixed in R1's lowerer and re-frozen into golden R bytes,
   invalidating every host's fixtures at once. The kit and trace
   versioning in T0 make that a visible, single-commit event rather than
   a silent divergence, but the exposure is real and is the price of
   targeting a format before its first-party kernel.
3. Walk divergence: the section 3.6 front end must visit the resolved
   tuples in the linearizer's order or its tail flags, evaluation order
   and address sequence silently differ from the stack image's; the
   address law in T3 and T4 catches address-order drift over the corpus,
   and the kit's normalized results catch evaluation-order drift where a
   corpus program observes it, but nothing catches either on non-corpus
   shapes.
4. Raise pattern drift: `raise` (T8) decodes `lower-stack`'s emission
   pattern; a layout change in `lower-stack` must fail T8's laws, which
   means T8's tests must run on every B2 change once T8 exists.
5. Offload transport becoming a hidden scheduler: T5 must keep the
   Clojure engine as the only place that decides what runs next; the
   Rust side returns and waits.
6. Scope creep toward Level 2 before B4 and B6 exist.

## 10. Owner decisions requested

1. Whether to authorize R0 and R1 of the register design, which this
   document now requires before T0, and if so whether T1 (Rust) or T2
   (Python) is the first foreign host. Recommendation: R0, R1, T0, then
   T1; T2 follows as the host-neutrality proof.
1a. Whether the T0 reference register interpreter (test-only, under
   test/, R3's own harness) is the cross-check for foreign kernels, or
   whether the owner accepts a foreign host as the first register
   executor with kit-only checking. Recommendation: the interpreter; it
   is a few hundred lines, it is R3's harness anyway, and it keeps the
   `.cljc` reference as the oracle without touching the R4 gate.
2. Whether `yin.vm-portability.md` is superseded by this document for the
   executable payload question (image versus datom CESK), or coexists as
   the design for a different, datom-level skeleton. Recommendation:
   mark it historical for sections 1-3 and keep section 2.6's
   conformance philosophy as the ancestor of T0.
3. The benchmark thresholds for T4 and T7, when those phases are
   proposed.
4. Whether a Scheme Direction B host is ever wanted; nothing here
   schedules one.
5. Whether the hold-only-H case matters enough to schedule T8 (`raise`)
   at all. Its output feeding `lower-register` is decided (T-D12).
   Recommendation: leave T8 unscheduled until a Clojure process that
   holds only H actually needs to hand R to a foreign host.
