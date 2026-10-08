Created-GMT: 2026-09-22 12:34:00 GMT
Created-Local: 2026-09-22 19:34:00 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: pending (new session, caller-generated)
# Task: architect-rust-compilation-pipeline — design an optional compilation pipeline, Rust first, multi-target by design
Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-22 19:34:00 +07 | Status: active | Rationale: new substantial design, owner-directed to use fable as primary architect for design work this session

Work in /Users/sto/workspace/datomworld (your launch directory; branch
master). This is a DESIGN task: produce a design document, do not
implement code. Do not edit any file except the new document you create.

## Objective

Design an OPTIONAL compilation pipeline for `yin.vm`, targeting Rust
first, but architected from the start so the same approach reaches other
target languages cheaply rather than requiring a full bespoke codegen
backend per language. The owner named these additional target languages
to design for, alongside Rust: Gambit Scheme, Chez Scheme, Clojure, and
Python (in addition to the native/hardware targets -- C, ARM, x86 -- and
WASM already discussed this session as comparison points, not necessarily
in scope for this document). This is new, exploratory design work -- not
yet authorized for implementation. Produce the design; implementation
authorization is a separate, later owner decision.

## A critical axis across these targets: does the target language itself

## guarantee proper tail calls?

This changes the design materially per target family, and your document
must organize around it rather than treat every target as needing the
same trampoline machinery:

- **Gambit Scheme and Chez Scheme** both implement R7RS-class Scheme,
  which mandates proper tail calls as a language guarantee. Verify this
  yourself rather than trusting this summary, but if true, compiling
  yin.vm's resolved tuples or bytecode directly to idiomatic Scheme
  source, with ordinary Scheme function calls in tail position, gets
  correct unbounded tail recursion for free from the target compiler --
  no hand-rolled trampoline kernel needed for THIS specific problem
  (effects/continuations/park-resume are a separate concern, addressed
  below, independent of the TCO question). Note also: Gambit's own author
  (Marc Feeley) also created Ribbit -- these are not unrelated projects;
  factor that relationship into how much of the Ribbit precedent
  transfers directly to a Gambit-specific design.
- **Clojure** (the JVM host, distinct from this project's own use of
  Clojure as its implementation language) has no automatic TCO (the JVM
  itself has none), but Clojure's core library ships a built-in
  `clojure.core/trampoline` function specifically for this exact problem
  -- verify its actual semantics yourself, but if it matches the general
  trampoline pattern (a tail call returns a thunk/value instead of
  recursing, `trampoline` drives the loop), emitting idiomatic Clojure
  source that uses `trampoline` for tail calls may need little to no
  bespoke kernel code, unlike Rust.
- **Python** has neither native TCO nor a standard-library trampoline
  equivalent (well documented that Python's own creator rejected TCO);
  compiling to Python needs the same hand-rolled kernel/trampoline
  approach this session already designed conceptually for Rust.
- **Rust, C, and other systems targets** (already discussed this session,
  included here for continuity, not necessarily to be re-litigated in
  depth) need the same hand-rolled trampoline kernel as Python, for the
  same reason: no target-language TCO guarantee.

Use this axis to decide, per target family, whether Direction A (native
codegen, described below) needs a hand-rolled kernel at all, or whether
it can be a much simpler direct-style source emitter for the Scheme
targets specifically (and possibly Clojure, if `trampoline` covers it
cleanly).

## Read first, in full

- docs/design/yin.vm.debruijn.stack.md (the committed stack VM: B0-B3
  merged, B4-B7 not started; sections 1-3 especially, for the resolved-
  tuples/stack-image shapes and the project's host-portability model --
  H is already defined to be host-independent, verified identical across
  JVM/CLJS/CLJD).
- docs/design/yin.vm.debruijn.register.md (the peer register-VM design,
  compiler-only so far, kernel gated behind a benchmark).
- docs/design/yin.vm.engine.md (the shared scheduler/continuation/effect
  contract: `run-loop`, wait-set/ready-queue, park/resume, the
  restore-fn/park-entry-fns convention every VM instance supplies).
- docs/agents/architecture.md, especially the AGENTS section
  (continuations as first-class reified execution contexts) and
  "COMPILATION AS STREAM PROCESSING".
- src/cljc/yin/vm/debruijn/stack.cljc (B3's actual step/state shape --
  `step1`/`run`, explicit VM record) -- read this closely, it is the
  concrete precedent for the "small kernel state machine" approach below.

## Two design directions to evaluate -- do not pick one without

## reasoning through both

The owner named the concrete idea already discussed this session: a small
Rust "kernel," meaning a compact state machine, not a naive recursive
compiler, so that tail calls never grow the native call stack (the
trampoline pattern: a call in tail position returns a "what to do next"
value instead of recursing; an outer `loop` drives execution; a real,
non-tail call is the only place a genuine continuation frame is pushed).
This maps directly onto B3's own `step1`/`run` shape -- a compiled Rust
kernel would be the SAME explicit step/state design, ahead-of-time
compiled instead of bytecode-dispatched at runtime.

The owner then asked specifically: use **Ribbit** (the Ribbit Scheme
system / RVM, by Marc Feeley et al.) as inspiration for targeting
multiple languages, not just Rust. Verify Ribbit's actual design
yourself before relying on it (do not take this summary as fact,
independently confirm what you can, and say plainly what you could not
verify): Ribbit's approach is understood to be a very compact, portable
bytecode format plus DELIBERATELY MINIMAL per-host interpreters (each a
few hundred lines) written directly in many target languages (JS, Python,
C, and others), rather than compiling source-to-source into each target
language. Portability comes from the bytecode being small and the
per-host interpreter being cheap to port, not from one universal codegen
backend targeting every language's native compiler.

Weigh two directions against each other, informed by that precedent:

**Direction A -- native codegen (compile to target-language source, then
that target's own compiler):** Lower resolved tuples or the register
image into actual source in the target language (state as data
structures idiomatic to that language, tail calls handled per the TCO
axis above -- a trampoline kernel for Rust/Python/C, plain direct-style
calls for Gambit/Chez, `trampoline` for Clojure if it fits), then hand it
to that language's own compiler for a genuinely optimized artifact. This
is inherently a NEW per-language emitter for each target -- a Rust
emitter, a Python emitter, a Gambit emitter, etc. are separate
design/implementation efforts, though they may share a common front-end
(the same resolved-tuples or bytecode source, the same allocation/layout
decisions where applicable) even as their back-ends differ. Produces a
target-specific compiled artifact whose identity/sharing model differs
from H/R (compiled output is not portable data the way canonical bytecode
is, though for hosted targets like Clojure/Python, the emitted SOURCE
text could in principle still be canonicalized and hashed -- consider
this explicitly for the hosted-language targets versus the truly native
ones).

**Direction B -- Ribbit-style: each target language as a new yin.vm HOST,
interpreting the existing bytecode:** Instead of compiling per-program
into each target language's source, write ONE thin interpreter per target
language for the ALREADY-DESIGNED, ALREADY PORTABLE
`:yin.debruijn.code/*` stack image (or the register image, once built) --
the same instruction set CLJ/CLJS/CLJD already execute, just one more host
implementation of the same step/state contract B3 defines, per target
language. This reuses B1's descriptor and B3's design entirely; no new
bytecode format, no new identity model, no change to H. Reaching a NEW
target language then costs one more small interpreter (matching Ribbit's
own economics: each of its many hosts is reportedly a few hundred lines),
not a new codegen backend. The tradeoff: an interpreter, even a fast
compiled one, does not reach the same peak performance a real AOT
compile-to-native-code pipeline (Direction A) can, and for the
proper-TCO targets (Gambit, Chez) Direction B forfeits the "free" tail
calls those languages would give a native-style emitter, since the
interpreter's own dispatch loop is what's executing, not target-native
tail-recursive functions.

Decide, and this may reasonably differ PER TARGET FAMILY rather than being
one global answer: is this an either/or choice per language, or a phased
plan (Direction B first for most/all targets, cheap, extends host coverage
immediately and validates the design against real target-language code;
Direction A later, optional, per target, for languages/use-cases that
need genuine native performance or genuinely idiomatic output beyond what
a compiled interpreter loop gives -- and where Direction A may be nearly
free for the proper-TCO Scheme targets specifically, making it worth
prioritizing differently there than for Rust/Python/C)? Give your own
reasoned recommendation per target family, do not just present both
neutrally as one global choice.

## What the design must address, whichever direction(s) you recommend

1. **Source representation.** For Direction A: does the Rust emitter
   consume resolved tuples (peer projection, matching the stack/register
   precedent) or the already-lowered stack/register bytecode (reuse
   existing lowering, accept the "derived from" relationship the
   stack/register split specifically avoided for each other)? Justify
   against invariant I, D9, D12, and the peer-projection precedent this
   session's stack/register work established. For Direction B, this
   question does not arise (it consumes the existing bytecode as-is) --
   say so explicitly if you recommend B.
2. **State representation as Rust types.** Map the existing VM state
   shape (`{:frames :free-env :stack :continuation :store :status
   :primitives :modules}`, per B3 and the engine design) onto concrete
   Rust enums/structs. Continuations must be explicit, serializable data
   (the project's own "everything is a continuation" invariant), not
   Rust's native call stack -- state this plainly as a hard constraint
   the trampoline design exists to satisfy.
3. **Effects, streams, scheduling.** `yin.vm.engine`'s contract
   (`run-loop`, wait-set/ready-queue, park/resume, restore-fn/park-entry-
   fns) is Clojure-specific machinery talking to `dao.stream`. A Rust
   host needs an equivalent -- does it need a Rust-side `dao.stream`
   client from day one, or can an initial phase scope to PURE programs
   only (no stream effects, no park/resume across a host boundary),
   matching how the register design gated its own kernel (R4) behind a
   benchmark and its effects phase (R2) separately from its pure lowering
   phase (R1)? Recommend a phase order with the same discipline this
   project's other phased designs use (bounded file boxes, must-not-
   change lists, completion criteria per phase).
4. **Identity and sharing.** Does a compiled Rust artifact (Direction A)
   or a Rust host binary (Direction B, the interpreter itself, not
   per-program output) get any identity in this project's H/R/linker
   model, or is it explicitly NOT a sharing-identity participant (a local
   host capability, not fetched over `dao.stream` the way canonical
   bytecode is)? For Direction A specifically: a native compiled artifact
   is host/arch-specific in a way H (host-independent, verified across
   three existing hosts) is not -- state clearly what this means for
   invariant I ("share executable code over a dao.stream linker") and
   whether a compiled-Rust artifact can be an executable format at all
   under that invariant, or whether it is necessarily local-only,
   rebuilt-not-fetched on every host that wants it.
5. **Non-goals.** State explicitly what this does NOT replace or require:
   the stack/register bytecode remains the portable, canonical, content-
   addressed format regardless of what native pipeline exists; this is an
   optional local execution/performance path, not a new source of truth.
   Does not require implementing B4-B7 first, though note which of those
   phases this design would actually depend on if effects/continuations
   are in scope for any recommended phase.
6. **Phasing.** A phase table matching this project's convention (bounded
   file box, must-not-change list, completion criteria, JVM/CLJS/CLJD/Rust
   verification requirements as applicable per phase).

## Deliver

A new design document. Since this now covers multiple target languages
(Rust, Gambit Scheme, Chez Scheme, Clojure, Python), pick the right name
and structure yourself and justify it -- options include one document
covering the general multi-target pattern with per-target sections (e.g.
docs/design/yin.vm.debruijn.native-targets.md or a name you judge fits
better), or a short general document plus this session's Rust discussion
folded in as its first worked example. State your choice and why.
Whichever structure, follow this project's design-doc conventions
(numbered sections, a phase table, a DECIDED/DEFERRED section, ASCII
only, no em dashes, 80 columns, a clear Status line stating this is
design-only, not authorized for implementation).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then: your recommendation (Direction A, B, or phased combination, and
why); what you verified about Ribbit versus what you could not verify;
a summary of the document and where you wrote it; anything needing the
owner's decision.
