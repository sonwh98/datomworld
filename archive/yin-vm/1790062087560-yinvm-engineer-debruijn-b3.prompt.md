Created-GMT: 2026-09-22 07:28:07 GMT
Created-Local: 2026-09-22 14:28:07 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: pending (new session, caller-generated)
# Task: debruijn-b3 — the de Bruijn VM kernel
Role: yin.vm / Interpreter Engineer
Implementers:
- Model: claude-sonnet-5 | Assigned: 2026-09-22 14:28:07 +07 | Status: active | Rationale: fresh session (distinct from the B0/B1 engineer, whose context is full of B1's active review cycle); B3 has no file dependency on B1's still-uncommitted work, dispatched concurrently

Work in /Users/sto/workspace/worktree-debruijn-b3 (your launch directory;
branch debruijn-b3, HEAD b39f3e8c). Do NOT stage, commit, merge or push.

## Read first, in full

docs/design/yin.vm.debruijn-vm.md sections 1 (Architecture and
invariants), 4 (VM state and execution), 5 (Effects and equivalence
boundaries, for scope only -- B4 owns effects, not you), and the "B3: de
Bruijn VM kernel" phase box. Also read (for the normalizer and corpus you
reuse): test/yin/vm/debruijn_vm_contract_test.cljc (B0, already committed
on master at 08099a53) in full.

## Why this can be built now, standalone

B2 (the named-datom lowerer) does not exist yet, and B1 (the executable
dimension) is mid-review in a different worktree with its own encoding/
validation internals still settling. You do not need either: you interpret
RAW INSTRUCTION VECTORS directly -- `[:closure arity body-pc]`,
`[:load-bound depth position]`, `[:load-free name]`, `[:const value]`,
`[:call argc tail?]`, `[:return]`, `[:jump target]`,
`[:branch-false target]`, `[:halt]`, `[:store-get key]`,
`[:store-put key value]` -- whose shapes have been stable since section 2
was first written and are unchanged by B1's ongoing fix round (that round
touched `encode-scalar`/`image-hash`/the validator, not these shapes).
Build and test your VM against HAND-BUILT instruction vectors, exactly the
technique B1's own tests already use for its standalone validator tests.
Do not import or require anything from `yin.vm.debruijn-code` (it is not
even committed yet); if you need the opcode vocabulary, read it directly
from `yin.vm.code/vector-operand-table` and section 2's prose, the same
source B1 derives from.

## Scope (B3's own file box)

    New: src/cljc/yin/vm/debruijn_vm.cljc
    New: test/yin/vm/debruijn_vm_test.cljc
    Existing edits: none
    Must not change: semantic VM, engine, IVM protocols, named environment,
    merged projection namespace

In scope: frames, closures, loads (`:load-bound`, `:load-free`), calls,
returns, branches, literals (`:const`), and store operations
(`:store-get`, `:store-put`). Explicitly OUT of scope (B4's phase, not
yours): stream operations, primitives/FFI, gensym, current-continuation,
park, resume. Do not implement these; if the instruction set includes
their opcodes, your VM may simply not handle them yet (fail loudly with a
clear "not yet implemented in B3" error if one is encountered, rather than
silently no-op).

## What to build (section 4, restated precisely)

1. **Explicit VM state**, exactly this shape:
   ```
   {:segment code-image
    :pc pc
    :frames [frame ...]
    :free-env initial-name-map
    :stack operand-vector
    :continuation continuation-data
    :store store
    :status status}
   ```
2. **Frames** are positional vectors, ordered OUTERMOST to INNERMOST.
   Frame zero for a `:load-bound` reference is the INNERMOST frame, read
   from the END of the `:frames` vector (not the front) -- get this
   direction right; it is the opposite of what a naive reading of "frame
   0" might suggest, and the design calls this out explicitly as
   deliberate.
3. **Closures** capture the persistent frame stack (the `:frames` vector
   at closure-creation time) plus a body pc reference. A closure value's
   shape is yours to design (this is new, B3-owned data), but it must
   carry enough to resume execution at its body with its captured frames
   plus one new frame for the call.
4. **Calls** use the POSITIONAL equivalent of the named `bind-params`
   (`src/cljc/yin/vm/engine.cljc`, which zips params with args and
   nil-fills): `(vec (take arity (concat args (repeat nil))))`. Missing
   arguments are nil-filled; extra arguments are DROPPED (not an error).
   Do not import or call the named `bind-params` itself -- it is
   name-keyed and this is positional; write your own small equivalent, but
   the docstring should say what it corresponds to.
5. **`:load-free`** resolves through the SAME order the named VM's
   `resolve-var` (`engine.cljc`) uses: `free-env -> store -> primitives ->
   module registry`. You MAY reuse `yin.vm.engine/resolve-var` directly for
   this (it is a data-only, name-keyed helper, exactly the kind of reuse
   the design authorizes) -- read its signature first
   (`[env store primitives registry name]`) and confirm it fits, or write
   your own equivalent if the signature does not fit your state shape
   cleanly; say which you chose and why. Positional locals (`:load-bound`)
   must NEVER fall through to a store key -- only `:load-free` does that.
6. **`:macro?`** is a named-datom-only concept; this positional dimension
   has no such flag on `:closure`, so there is nothing to ignore here --
   confirm the `:closure` instruction shape genuinely carries no such
   field (per section 2, it carries only arity and a body pc) and note
   this in the report rather than silently assuming it.
7. **The `yin.vm.debruijn-vm` namespace** may implement `IVM`/`IVMState`
   (`src/cljc/yin/vm.cljc`) WITHOUT ADDING NEW PROTOCOL METHODS -- read
   both protocols first. Reuse data-only engine helpers where they
   genuinely fit (name resolution per #5 above; you may also look at
   primitive descriptions, stream descriptors, store operations if a
   store-op helper already exists and is data-only -- but do NOT reach for
   gensym/continuation helpers, those are B4's). Reimplement, do not call,
   anything that serializes the NAMED register layout (private response
   wait entries, named telemetry snapshots) -- those assume the named
   VM's environment shape, not yours.
8. **Do NOT implement `vm/IVMState/environment`** at all in this phase --
   the design defers it until a frame-to-named lift exists (B4's or
   later's concern). If you implement `IVMState` for other methods
   (`control`, `store`, ...), leave `environment` unimplemented or throw a
   clear "not yet supported" error; do not return the raw positional frame
   vector from it, since the design explicitly says that would be wrong
   once the method does exist.
9. **No existing protocol or VM semantics change.** Confirm nothing in
   `yin.vm.cljc`, `yin.vm.engine.cljc`, `yin.vm.semantic.cljc`, or the
   merged projection namespace is edited.

## Completion criteria

- Pure-program parity using B0's normalizer (`yin.vm.debruijn-vm-contract-test`,
  already committed) and FRESH initial environments (a fresh VM instance
  per test, never reusing one that has run before -- this sidesteps the
  named VM's own documented environment-leak defect, which is out of
  scope here per D4). Build a small corpus of HAND-WRITTEN instruction
  vectors covering: literals, variable loads (both bound and free),
  lambda/closure creation and application (including nested closures,
  multiple arities, an under-arity call being nil-filled, and an
  over-arity call dropping extras), `if`/branch-false control flow
  (both arms), and store get/put. For each program, compare your VM's
  normalized result against the SAME program's normalized result from the
  named semantic VM (reuse the actual named AST/execution path -- read how
  `test/yin/vm/parity_test.cljc` and B0's contract test build and run
  named-VM programs, and construct the equivalent hand-lowered instruction
  vector by hand for your side; do not build a real lowerer, that is B2).
- The informational section-1 benchmark report (throughput, allocation,
  image size, load time on identical pure-program corpora) is NOT an
  acceptance condition for this phase per the design ("After B3, the
  semantic VM section 8 harness reports...") -- B3 itself does not need to
  produce this report; it is a later, informational-only obligation. Do
  not spend time on it; say so in the report if you skip it.

## Never

Do not implement B2 (a real lowerer from named datoms), B4 (effects,
streams, primitives, FFI, gensym, continuations, park/resume), or B6. Do
not touch `yin.vm.code`, `:yin.code/*`, the merged projection namespace,
or any existing VM/engine/protocol file. Do not add a new lossless record
structure. Keep files pure ASCII, no em dashes, cljstyle-style Clojure
(blank lines between top-level forms), docstrings that say what and why,
matching this design doc's own register.

## Environment

Default PATH gives Java 21 and the mise clojure and bb. Focused JVM run:
`clojure -M:test -n yin.vm.debruijn-vm-test` (confirm the actual namespace
your file defines first). If you need kondo, cljstyle, the Java 17 lane, or
the CLJD lane and they are denied, say exactly what was denied and stop; do
not retry, do not use `--dangerously-skip-permissions`.

## Final report

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: <your session id>
Then: the VM state shape and closure representation you chose; the frame
direction (confirm innermost-at-end) with a concrete example; what you
reused from `yin.vm.engine` versus wrote fresh, and why; how each
completion criterion is met, naming the test; the parity corpus (what
programs, how each was compared against the named VM); what you ran with
exact counts; what you could not run; every deviation. Facts only; promise
nothing.
