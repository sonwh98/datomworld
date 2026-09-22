# yin.vm de Bruijn target hosts and emitters

Status: design only, not authorized for implementation. Exploratory
architecture for an OPTIONAL compilation and foreign-host pipeline over the
committed `:yin.debruijn.code/*` stack image. Nothing here changes B0-B7,
R0-R5, H, R, the engine seam, or any existing namespace. Implementation of
any phase below is a separate, later owner decision.

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
executes is the stack image with identity H, validated by B1, whose bytes
are already verified identical across three hosts, and conformance is
B0-normalized parity rather than log identity. Whether the portability
document is superseded or coexists is an owner decision (section 10).

## 1. Two directions, and why the answer is per family

Direction A, native codegen: lower an executable image into source in the
target language, then hand it to that language's own compiler. One emitter
per target. Output is a target-specific artifact with no identity in the
H/R model.

Direction B, Ribbit-style host: write one thin interpreter per target
language for the `:yin.debruijn.code/*` image. No new bytecode, no new
identity, no change to H. One more host of the same step/state contract
B3 defines, exactly as CLJ, CLJS and CLJD are hosts of it today.

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

Direction B consumes the canonical wire bytes of a `:yin.debruijn.code/*`
image as-is. Nothing else arises: the host hashes received bytes, checks
H against the descriptor (D14), runs the receiver closure check (D11,
D15), validates the image by B1's rules reimplemented in the host, and
loads. No resolver, no lowerer, no named datoms exist on a foreign host.

Direction A consumes an EXECUTABLE IMAGE WITH AN IDENTITY: the stack image
(H) now, the register image (R) if R1 is ever built. It never consumes
resolved tuples or named datoms. Justification against the peer-
projection precedent:

1. The stack/register split kept two IDENTITY-BEARING formats from being
   derived from each other, so that H and R are independent preimages
   (D9, D12, register design invariant 3). A Direction A artifact has no
   identity and no sharing role (section 3.5). "Derived from H" is not
   the relationship that precedent forbids; it is the relationship a
   cache wants: the artifact is a pure function of (H, target, emitter
   version) and is keyed by exactly that.
2. Invariant I shares executable code over the stream linker. What is
   fetched by H must be sufficient to run. If an emitter consumed
   resolved tuples, a host that fetched an image by H could not compile
   it; it would need the named datoms and the resolver, which B6 does not
   ship. Consuming the image means "fetch by H, then compile locally" is
   the whole story on every host, including foreign ones.
3. Resolved tuples have no identity and are not executable (stack design
   section 3.1). Keying a compiled artifact on them would give them the
   cache-key role D12 and "derive, do not persist" deny them.

Cost accepted: an emitter working from a flat stack image must rediscover
body extents. It does not need the fused design's removed `closure-body-
ranges`. Each `[:closure arity body-pc]` names a body entry; a body is
the set of pcs reachable from its entry through `:jump` and
`:branch-false` targets and fall-through, terminated by `:return`. B1's
validator already performs an equivalent walk to check bound operands, so
the emitter's front end (section 3.6) is a second consumer of that
reachability, not a new analysis. The register image, when it exists, is
the better AOT source (no `:push`, named operands) and the emitter front
end must be shaped so that switching its input from H to R changes the
decoder, not the printers.

### 3.2 State as Rust types

The B3 record `{:segment :pc :frames :free-env :stack :continuation
:store :status :primitives :modules}` maps as follows. Names are
illustrative; the shapes are the decisions.

    enum Value {
        Nil, Bool(bool), Long(i64), Double(f64),
        Str(Rc<str>), Keyword(Name), Symbol(Name),
        Vector(Rc<[Value]>), List(Rc<[Value]>),
        Map(Rc<PMap>), Set(Rc<PSet>),
        // ratio, bigint, char: only if the host declares the class
        Closure(Rc<Closure>),        // {arity, body_pc, frames}
        Primitive(PrimId),           // resolved :load-free primitive
        Reified(Rc<Registers>),      // {:type :reified-continuation}
        Parked(ParkedId),            // {:type :parked-continuation}
        StreamRef(Id), CursorRef(Id) // opaque, compared by identity
    }

    type Frame  = Rc<[Value]>;       // one lambda's positional locals
    type Frames = Rc<FrameList>;     // persistent cons, innermost first

    struct ReturnFrame { return_pc: u32, frames: Frames, stack_base: u32 }

    struct Registers {               // the B4 register payload, exactly
        segment: ImageId, pc: u32, frames: Frames,
        stack: Vec<Value>, continuation: Vec<ReturnFrame>,
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
   itself or any emitted body recursively. A `:call` with `tail?` true
   does not push a `ReturnFrame`; one with `tail?` false pushes exactly
   one. This is B3's `step-call` verbatim, and it is the whole reason the
   Rust host is a state machine rather than a recursive evaluator. Native
   stack depth is constant per step. This is the hard constraint of
   section 2, satisfied by construction.
2. `Frames` is a persistent cons list innermost-first, so `:load-bound
   depth position` is a walk of `depth` cells. B3 stores a vector
   outermost-first and reads from the end; the stack design says that
   conversion is explicit and not observable. Closures share frame tails
   by `Rc`, which is the persistent sharing the Clojure vector gives for
   free.
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

Level 0, pure. The host executes images whose instruction set is B3's:
`:const :load-bound :load-free :closure :push :call :return :jump
:branch-false :halt :store-get :store-put`, plus `:call` on a resolved
pure primitive. Any other opcode refuses with `:not-yet-implemented`
before execution (a static scan of the image, not a runtime trap, so a
refused image never starts). No stream, no park, no host boundary
crossing. This matches how the register design gated R1 (pure lowering)
before R2 (effects) and R4 (kernel).

Level 1, offload. The foreign kernel is a REMOTE STEP FUNCTION for the
existing Clojure engine. It runs until `:halt` or until a blocking or
continuation instruction, then returns its whole `Registers` plus the
effect request as DATA to the embedding Clojure process. The Clojure
engine schedules exactly as it does for the stack VM after B4: the
returned `Registers` is the B4 register payload `{:segment :pc :frames
:stack :continuation}` and the engine's restore function re-enters the
foreign kernel with that payload and the woken value. No foreign-side
wait set, ready queue or `dao.stream` client exists. The park boundary
is the host boundary, and it is crossed by data only, which is the
project's own rule for park in any case. This level requires a
serialization of `Registers` and of `Value` including closures
(`arity`, `body_pc`, `frames`), for which the B1 scalar encoding
extended with closure and continuation tags is the natural wire form.
Level 1 depends on B4 (the payload shape and the restore signature are
B4's) and on a transport for the data, which is composition plumbing
below the `dao.stream` boundary: a subprocess pipe, an FFI call, or a
`dao.stream` itself. Level 1 is where park/resume across a host boundary
first exists, and it exists as data transfer, not as a foreign scheduler.

Level 2, standalone host. The foreign host owns a scheduler and a
`dao.stream` client and runs effectful programs with no Clojure process.
This requires the engine contract (`run-loop`, wait set, ready queue,
park and resume records, the FFI two-step) reimplemented in the target
language, a `dao.stream` wire client (DaoJing CBOR framing once that work
lands, decoded with a CBOR library, never hand-rolled), and B6's fetch-
by-H. It is the level the portability document's effect boundary
describes. It is NOT designed here beyond naming its dependencies (B4,
B6, `dao.jing.cbor`) and is deferred (section 9).

Recommendation: every target stops at Level 0 until the owner asks for
more; Rust is the only target for which Level 1 is planned in this
document (T5); Level 2 is deferred for all targets.

### 3.4 Conformance: what a foreign host must reproduce

The oracle is the B0 normalizer over the pure parity corpus, run on the
JVM lane, exported as data. A foreign host is conformant at Level 0 when,
for every corpus image (canonical wire bytes plus expected normalized
result or error), it reproduces the expected value under the normalizer,
refuses every image the JVM refuses with the same rule keyword, computes
the same H over the same bytes, and refuses images using scalar classes
outside its declared set with `:unsupported-value` before execution.

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
model" and therefore "can use the bytecode directly"; the image travels
with H, and the host executes it or refuses it.

Direction A artifacts are NOT an executable format under invariant I.

1. A native artifact (Rust, C, WASM binary) is host- and architecture-
   specific. H is defined host-independent and verified identical across
   three hosts; no property of a native binary can be verified by a
   receiver against H except by rebuilding it. A compiled artifact is
   therefore local-only, rebuilt-not-fetched, on every host that wants
   it. It is a cache keyed by `(H, target-triple, emitter-version)`, and
   a cache is an explicit value keyed by H exactly as the stack design
   permits (section 2 there). It never crosses `dao.stream` as code.
2. Hosted-language emitted SOURCE (Clojure, Python, Scheme text) is
   host-independent bytes and could be canonicalized and hashed. It is
   still not given an identity. Reasons: D9 and D12 make H the only
   identity on the sharing path; a receiver cannot validate emitted
   source with B1's validator or run the closure check over it, so
   accepting it would mean trusting an emitter instead of verifying an
   image; and the same source is a deterministic function of H and the
   emitter version, so any receiver holding H can regenerate it. Emitted
   source is a local cache with the same key as a native artifact. A
   composition MAY publish such caches as opaque blobs, but a receiver
   MUST NOT execute one it did not derive itself. Stated as a decision
   in section 8 (T-D5).
3. Continuations produced by a Direction A artifact, when they exist
   (Level 1 and above), are the B4 register payload with pcs into the
   ORIGINAL image, never into emitted code. This is what keeps an
   offloaded continuation restorable by the stack VM on a host without
   the artifact: the artifact is an accelerator for a machine whose
   state vocabulary is the image's.

### 3.6 The shared emitter front end (Direction A)

One `.cljc` namespace decodes an image into a target-neutral body form:
for each body entry (the main body at pc 0 and every `:closure` target),
the reachable pcs partitioned into basic blocks at jump targets, with the
operand-stack effect of each block computed statically (B1's operand
table gives every instruction's stack delta, so a body's temporaries can
be named rather than pushed and popped). Per-target work is then:

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

## 4. Recommendation per target family

    +----------------+-----------+-----------------------------------------+
    | Family         | Order     | Reasoning                               |
    +----------------+-----------+-----------------------------------------+
    | Rust           | B, then A | B gives a native host binary now and    |
    |                | (gated)   | builds the kernel crate A needs; A is   |
    |                |           | gated by a benchmark, as R4 is by R3.   |
    |                |           | Level 1 offload planned for Rust only.  |
    | Python         | B only    | Interpreter in Python is the Ribbit     |
    |                |           | case exactly; A on CPython buys little  |
    |                |           | over B and costs a printer plus prelude |
    | Gambit, Chez   | A (pure   | Direct style is nearly free and gives   |
    |                | subset)   | tail calls and closures from the host;  |
    |                | first;    | B only if effectful programs on Scheme  |
    |                | B later   | are wanted, and then B's driver is a    |
    |                | if wanted | tail call, the simplest B of all        |
    | Clojure (JVM)  | B done;   | The B3 kernel IS the Clojure host. A    |
    |                | A gated,  | (compile image to nested fns or to      |
    |                | deferred  | source with a typed driver) is a pure   |
    |                |           | speed play against a JIT-compiled       |
    |                |           | interpreter; needs the same benchmark   |
    |                |           | gate as R3 before it is commissioned    |
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
hashing), `scalar` (decoder for B1's canonical scalar encoding, the
fourteen tags, refusal of undeclared classes), `image` (wire framing,
`image_hash` over the received bytes, descriptor check, B1's structural
and scope validator reimplemented from its stated rules), `prims` (the
pure primitive table with Clojure semantics), `machine` (`step1`, `run`,
`Next`), and a `conformance` test that loads the T0 kit and asserts every
fixture. The crate has no I/O beyond reading fixture files and no
dependency on the Clojure process. Entry: `run(bytes, free_env) ->
Outcome`, where `Outcome` is `Halted(Value) | Refused(Diagnostic)`.

Correctness argument for tail calls: `step1` for `:call` with `tail?`
true replaces `regs.frames` and `regs.pc` and does not push; the driver
`loop` re-enters `step1`. Native depth is one frame regardless of program
depth. The T0 million-iteration fixtures prove it.

### 5.2 T4, the Rust emitter (Direction A, gated)

Input: the section 3.6 body form of one image. Output: a Rust source
file that depends on the T1 crate's `value`, `scalar`, `prims` and the
`ReturnFrame`/`Registers` types, and defines one `fn body_N(m: &mut
Machine) -> Next` per body, plus a `dispatch(body_id)` table used by the
driver. Inside a body, blocks become labelled loop arms and the operand
stack is replaced by local `Value` bindings where the static stack effect
allows it, with `:push` disappearing entirely. `:call` with `tail?` false
pushes a `ReturnFrame` whose `return_pc` is the ORIGINAL image pc of the
continuation block; `dispatch` maps image pcs of block entries to
generated functions, so a restored continuation resumes into emitted code
by pc, and, on a host without the artifact, into the T1 interpreter by
the same pc. Bound loads walk `Frames` as in T1; the emitter does not
change frame layout, so closures created by emitted code and by the
interpreter are the same data. The driver is the T1 `run` loop with
`step1` replaced by `dispatch`.

Gate: T4 is commissioned only if the T3 benchmark (T1 interpreter versus
JVM B3 versus a hand-written Rust translation of two corpus programs)
shows a material gap that an emitter would close. The number is the
owner's, as R3's is.

### 5.3 What Direction B and Direction A share on Rust

Everything except `machine::step1` and the printer: the value model,
scalar decoder, validator, primitives, `Registers`, `ReturnFrame`, the
driver loop and the conformance harness. This is the concrete reason B
precedes A on Rust: T1 is roughly the whole of T4's runtime.

### 5.4 T5, offload (Level 1)

Add to the crate a `park` path: on any B4 instruction (`:stream-*`,
`:park`, `:resume`, `:current-continuation`, `:ffi-call`, `:gensym`),
`run` returns `Outcome::Parked { regs: Registers, request: Effect }`
serialized with the extended scalar encoding, and a `resume(regs, val)`
entry that rebuilds `Machine` and continues. On the Clojure side, a
restore function of B4's signature `base entry val -> state` that
forwards the entry's register payload and `val` to the crate and reads
back the outcome; the engine, wait set, lease and streams are untouched.
The Clojure-side file is a new namespace, not an edit to `engine.cljc`
or `stack.cljc`. Depends on B4 merged.

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
`:closure` becomes a `lambda` of `arity` parameters whose free variables
are Scheme lexicals; there is no `Frames` structure because Scheme's own
closures capture. A `:call` becomes an application, in tail position when
`tail?` is true. Missing arguments nil-fill and extra arguments drop, so
each lambda takes a rest argument and binds positionally, or the emitter
emits an arity-adapting wrapper; the exact form is the printer's, and
either must pass the nil-fill and extras-dropped fixtures. `:if` is
`if`, `:store-get`/`:store-put` are operations on a prelude hash table,
and the eleven-class common domain maps onto Scheme values with a small
prelude for keywords and symbols so that the `=` primitive keeps Clojure
semantics.

The pure-subset restriction (section 2) is enforced by the same static
opcode scan Level 0 hosts use: an image containing any B4 opcode is
refused by the emitter with `:not-yet-implemented`, never emitted as
direct-style code that would hide its continuation in the Scheme stack.

Why this is worth doing before any Scheme Direction B host: it is the
smallest possible demonstration that an image is a program, not just
bytes for one interpreter, and Scheme is the one family where the
demonstration costs a printer and a prelude only. If effectful programs
on Scheme are ever wanted, a Direction B host in Scheme reuses the
prelude and adds the section 3.2 state machine, whose driver is a tail
call rather than a loop.

### 6.3 Clojure Direction A (deferred)

The JVM already runs the image through B3. A Clojure emitter would either
print source or, more usefully, compile the image at load time into a
vector of Clojure fns (one per block) closed over the image, invoked by a
typed driver: a block returns `Value` or a `Step` record naming the next
block, and the driver loops on `Step`. `clojure.core/trampoline` is not
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
    | T0  | host conformance kit       | B1, B2, B3 merged (done)           |
    | T1  | Rust host, Level 0         | T0                                 |
    | T2  | Python host, Level 0       | T0                                 |
    | T3  | Scheme direct-style        | T0; Gambit and Chez toolchains     |
    |     | emitter, pure subset       |                                    |
    | T4  | Rust emitter (gated)       | T1; benchmark gate passed          |
    | T5  | Rust offload, Level 1      | T1, B4 merged                      |
    | T6  | any standalone Level 2     | B4, B6, dao.jing.cbor; deferred    |
    | T7  | Clojure Direction A        | benchmark gate; deferred           |
    +-----+----------------------------+------------------------------------+

Order: T0, then T1 and T2 may run in parallel; T3 is independent of T1
and T2 and may run at any time after T0; T4 and T5 follow T1 and are
independent of each other; T6 and T7 are deferred and not scheduled.

### T0: host conformance kit

    New: test/yin/vm/debruijn/host_conformance_test.cljc
    New: test/resources/yin/debruijn/conformance/  (generated fixtures)
    Existing edits: none
    Must not change: B1 bytes, H, the B0 normalizer, the parity corpus

Export, from the JVM lane, every pure parity-corpus program as canonical
image bytes, H, and the B0-normalized expected result or error, plus the
refusal fixtures (malformed rows, out-of-range operands, undeclared
classes) with their rule keywords, plus the tail-depth fixtures of
section 3.4 and a Level 0 opcode scan fixture (an image containing a B4
opcode, expected `:not-yet-implemented`). Fixture format is data (EDN
and a JSON mirror so hosts without an EDN reader can load it). The test
asserts that CLJS and CLJD lanes reproduce the JVM kit exactly for the
common domain, which makes the kit host-neutral before any foreign host
consumes it. Completion: kit generated deterministically on all three
lanes; a change to any golden byte fails the test.

### T1: Rust host, Level 0

    New: src/host/rust/yin-debruijn/  (Cargo crate; modules per 5.1)
    Existing edits: none
    Must not change: any .cljc, the kit, H

Completion: every T0 fixture passes under `cargo test`; H recomputed
over fixture bytes equals the fixture H; undeclared classes refused
before execution; million-iteration tail fixtures pass with a
release-mode stack limit set deliberately small; no `unsafe`; no
recursion in `step1` or `run` (a test walks the crate for recursive
calls, or a debug assertion checks native depth per step). Verification:
`cargo test`; JVM/CLJS/CLJD lanes unchanged because no `.cljc` changed.

### T2: Python host, Level 0

    New: src/host/python/yin_debruijn/
    Existing edits: none
    Must not change: any .cljc, the kit, H

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

Completion: the front end's body form is deterministic on all three
lanes and its static stack effects agree with actual B3 execution on the
corpus (a `.cljc` test); emitted Scheme text is byte-identical across
lanes; images with any B4 opcode are refused; on both Gambit and Chez,
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
Completion: emitted programs pass the kit; continuation `return_pc`
values are original image pcs; a program run half by the interpreter
and half by emitted code (park under the interpreter at Level 1, resume
into emitted code, or the reverse) produces the same normalized result,
which is the test that emitted code and interpreter share one state
vocabulary. Verification as T1 plus the `.cljc` lanes.

### T5: Rust offload, Level 1

    New: src/host/rust/yin-debruijn/src/park.rs, wire.rs
    New: src/cljc/yin/vm/debruijn/offload.cljc  (B4-signature restore,
         transport adapter)
    New: test/yin/vm/debruijn/offload_test.cljc
    Existing edits: none
    Must not change: engine.cljc, ffi.cljc, stack.cljc, the engine-owned
    key sets, dao.stream, lease, waitset

Completion: for every B4 effect fixture, the offloaded run's normalized
outcome equals the stack VM's; a parked `Registers` serialized by Rust is
restorable by the stack VM (`stack-restore`) and vice versa; no closure
or handle crosses the boundary, only data; the transport is replaceable
(the test runs it over an in-process pipe and over a `dao.stream`).
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
4. Does not require B4-B7 first. T0-T4 depend only on B1-B3. T5 depends
   on B4 for the register payload and restore signature. Any Level 2
   host depends on B6 for fetch-by-H and on `dao.jing.cbor` for the
   stream wire.
5. Native and hosted artifacts are local caches, never executable code
   on the sharing path (section 3.5).
6. This document does not adopt `yin.vm-portability.md`'s Yang text
   preprocessor, base-92 stream embedding, or cross-language macros, and
   does not retire them; see section 10.

DECIDED:

1. T-D1 directions: phased, per family, per section 4. Direction B
   first for Rust and Python; Direction A pure-subset first for Scheme;
   Direction A for Clojure and Rust gated by benchmark.
2. T-D2 source: Direction B consumes canonical image bytes as-is.
   Direction A consumes an identity-bearing executable image (H now, R
   if built), never resolved tuples or named datoms (section 3.1).
3. T-D3 continuation: in every direction and family the continuation is
   explicit data (`Vec<ReturnFrame>` or its target equivalent) and the
   native stack depth is constant per step; direct-style emission is
   permitted only for images with no B4 opcode, enforced by a static
   scan that refuses rather than emits.
4. T-D4 effect levels: Level 0 pure for every target first; Level 1
   offload keeps the Clojure engine as the only scheduler and crosses
   the host boundary with the B4 register payload as data; Level 2 is
   deferred for all targets.
5. T-D5 identity: host binaries and compiled or emitted artifacts have
   no identity and are not sharing participants; they are local caches
   keyed by `(H, target, emitter-version)`, rebuilt not fetched; a
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
10. T-D10 continuation pcs: emitted code's return frames name original
    image pcs, so interpreter and emitted code share one state
    vocabulary and a continuation restores on a host without the
    artifact.

DEFERRED:

- Owner authorization to start T0, and the order of T1 versus T2.
- The T4 and T7 benchmark thresholds.
- Level 2 standalone hosts: scheduler port, `dao.stream` client, fetch
  by H on a foreign host.
- The serialization tags for closures and continuations in the T5 wire
  form (an extension of B1's scalar encoding or a separate small
  dimension; not decided here).
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
2. Validator drift: B1's validator is reimplemented in each host from
   its stated rules; a rule change in `.cljc` must fail the kit's
   refusal fixtures on every host, which requires the kit to be
   regenerated and rerun as part of any B1 change.
3. Body-form divergence: the section 3.6 front end's static stack
   effect must agree with actual execution; T3's `.cljc` test checks
   this against B3 on the corpus, but an emitter bug that only appears
   on non-corpus control flow is not caught.
4. Offload transport becoming a hidden scheduler: T5 must keep the
   Clojure engine as the only place that decides what runs next; the
   Rust side returns and waits.
5. Scope creep toward Level 2 before B4 and B6 exist.

## 10. Owner decisions requested

1. Whether to authorize T0 at all, and if so whether T1 (Rust) or T2
   (Python) is the first foreign host. Recommendation: T0 then T1; T2
   follows as the host-neutrality proof.
2. Whether `yin.vm-portability.md` is superseded by this document for the
   executable payload question (image versus datom CESK), or coexists as
   the design for a different, datom-level skeleton. Recommendation:
   mark it historical for sections 1-3 and keep section 2.6's
   conformance philosophy as the ancestor of T0.
3. The benchmark thresholds for T4 and T7, when those phases are
   proposed.
4. Whether a Scheme Direction B host is ever wanted; nothing here
   schedules one.
