Created-GMT: 2026-09-21 17:55:57 GMT
Created-Local: 2026-09-22 00:55:57 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a (resumed — your de Bruijn thread)
# Task: author the design for a VM that executes linearized de Bruijn code
Role: Architect
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-22 00:55:57 +07 | Status: active | Rationale: you authored the projection design and signed off D0-D6; the owner now wants the next stage designed by the same architect

Work in /Users/sto/workspace/datomworld (master; the de Bruijn projection, D0-D6,
is merged). You MAY write exactly ONE new file:
`docs/design/yin.vm.debruijn-vm.md`. Edit no other file. Produce the complete
design now; do not wait for approval and do not promise one.

## The owner's goal (their words, restated)

"The de Bruijn projection is not finished. I want a VM that runs the linearized
de Bruijn encoding too. Right now we have a VM that runs a linearized version of
the Universal AST map." I.e. the semantic VM today executes `:yin.code/*` linear
datoms lowered from the NAMED AST; the owner wants a VM that also executes a
linearized DE BRUIJN form, alongside it (coexisting, not replacing it).

## Verified facts to design against (read the sources; correct me where wrong)

- The named path: `yin.vm/ast->datoms` (AST map to `:yin/*` datoms) then
  `yin.vm.linearize/lower` (datoms to `:yin.code/*` linear executable datoms,
  reading the datom index directly, lambda bodies out of line, labels resolved,
  each instruction carrying `:yin.code/source` naming its AST entity), then
  `yin.vm.semantic` loads and executes the image (CESK on linear datoms;
  docs/design/yin.vm.semantic.md sections 2-5). `yin.vm.ast-walker` evaluates
  the named AST map directly. `yin.vm.engine` supplies `bind-params` (zip params
  with args, nil-fill missing, drop extras) and `resolve-var` (env, then store,
  then primitives, then module registry).
- The environment is a NAME-keyed map: a call merges `(bind-params params args)`
  into the closure's env. The `:var` instruction carries `:yin.code/name`
  (`:load-var`). The `:closure` instruction carries `:yin.code/params :syms`
  and `:yin.code/body :pc` (src/cljc/yin/vm/code.cljc around lines 21-40,
  200-210).
- The projection (src/cljc/yin/vm/debruijn.cljc, docs/design/
  yin.vm.debruijn-projection.md, merged) yields a hash-consed Merkle DAG of
  records: `:yin.debruijn/type`, `:arity`, `{:bound [frame-depth position]}` or
  `{:free name}`, scalar slots, ordered child hashes, and a root fingerprint.
  It DROPS binder names, `:yin/tail?`, macro-name, source eids and tempids, and
  shares subtrees. Free names are preserved exactly. `:yin/tail?` is derived, so
  recomputable. There is no source-to-projected lineage index (optional, not
  built). The reader does not scope-check `{:bound [d p]}`.
- Related designs to read and reconcile with: yin.vm.semantic.md,
  yin.vm.code-as-tuples.md (+ implementation plan), yin.vm.universal-
  continuation-format.md, yin.vm.jit.md, yin.vm-portability.md,
  yin.vm.macro.md, yin.vm.streams-all-the-way-down.md, dao.stream.md and
  datom.world.md (axioms, invariants). Existing parity infrastructure:
  test/yin/vm/parity_test.cljc, semantic_test.cljc, semantic_engine_test.cljc,
  linearize_test.cljc, vm_test.cljc.
- The projection design section 9 puts the VM, linearizer and `:yin.code/*`
  OUTSIDE its authority. This new design must therefore state explicitly what it
  adds and what it forbids itself from touching.

Owner preferences: minimal diffs, reuse libraries and existing namespaces where
that is honest, coexistence with the named path (no regression to it), every
phase reviewed by a different model family than its author, cross-host (JVM,
ClojureScript, ClojureDart) parity as a first-class requirement.

## What the design document must decide

Write it in the style and rigor of docs/design/yin.vm.debruijn-projection.md
(numbered sections, invariants, an implementation plan with phases and
completion criteria, and a test matrix; ASCII box tables only, no markdown pipe
tables, no em dashes, 80 columns, headings as in that file). Status line:
"design; not implemented". It must settle, or clearly list as an OWNER decision:

1. **Objective and invariants.** What "runs the linearized de Bruijn encoding"
   means, and the semantic-equivalence contract with the named path: for the same
   program, the same results, errors, effects, stream and store behaviour, tail
   calls, `call/cc`-style continuations, park/resume, gensym, macros (`:macro?`
   lambdas), free-variable resolution order (env, store, primitives, registry).
   State exactly where equivalence is NOT promised (for example anything that
   depends on binder names, stack traces, or `:yin.code/source` provenance) and
   why that is acceptable.
2. **Where the de Bruijn code lives.** A new instruction dimension (its own
   published descriptor, like `:yin.debruijn/*`), or the existing `:yin.code/*`
   set with new ops (a local-load by `[depth pos]`, closures carrying arity
   instead of `:syms`)? Weigh: content addressing of code by fingerprint,
   coexistence, validator reuse (yin.vm.code), and the ban on changing existing
   `:yin.code/*` semantics. Recommend one.
3. **Lowering.** From projected records to a linear instruction vector: input is
   the record set plus root fingerprint. Evaluation order, lambda bodies out of
   line, labels/refs, recomputing tail position, and above all how to lower a
   hash-consed DAG whose subterms are shared (duplicate the shared subterm at
   each use, or emit shared code once and call it, and what each does to
   `[depth pos]` correctness under different lexical contexts, since the
   projection memoises by `[eid lexical-context]`). Say what replaces
   `:yin.code/source` provenance.
4. **The VM.** The frame-indexed environment: representation of a frame and the
   frame stack, closure capture, call and return, `bind-params` semantics under
   arity (nil-fill, extras dropped), how free variables still resolve, how
   continuations reify and resume (universal-continuation-format), park/resume,
   stream ops, primitives and FFI. Decide whether this is a new VM namespace
   sharing `yin.vm.engine`, a mode of `yin.vm.semantic`, or a sibling, with the
   smallest change to existing code and no protocol change to existing VMs.
5. **Scope validation.** The projected reader does not scope-check
   `{:bound [d p]}`. A VM that indexes frames will fault or read the wrong frame
   on an ill-scoped record. Decide where validation belongs (reader, lowering,
   loader) and specify it.
6. **Phases (B0...Bn).** File box per phase (new files; the exact minimal edits
   to existing files, named), what must not change, completion criteria, and
   verification lanes (JVM, Node, Dart, kondo, cljstyle). Include differential
   testing as the acceptance backbone: every program in the existing
   semantic/parity suites must produce identical outcomes on both VMs, and
   fingerprint-equal programs must produce identical results. Make the phases
   independently reviewable and shippable.
7. **Test matrix and non-goals.** Explicitly out of scope: an equality-
   saturation or optimizer, a de Bruijn JIT beyond what yin.vm.jit.md already
   allows, global distribution, changing the projection or the fingerprint.
8. **Risks, and the owner-decision list** (anything that needs the owner rather
   than an architect ruling), plus a **routing recommendation**: implementation
   on Claude models (the Claude pool refreshes 2026-09-22 04:00 +07), review by
   a different family, gpt for sign-off; glm-5.3 is about 68 percent used until
   2026-09-27 and agy is nearly exhausted until about 2026-09-23 10:00 +07, so
   do not route reviews to Gemini.

Also report separately, as a short list at the end of your final response (not in
the document): anything in the merged projection or its design that this work
shows was underspecified or wrong, so the owner can decide whether to amend it.

## Deliverable

1. Create `docs/design/yin.vm.debruijn-vm.md`.
2. Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a
then: the path of the file you created; a one-paragraph summary of the design;
the OWNER DECISIONS list; the underspecified-projection list; and any facts in
this brief that you found to be wrong. Do not edit any other file.
