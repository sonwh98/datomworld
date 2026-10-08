Created-GMT: 2026-09-21 17:59:23 GMT
Created-Local: 2026-09-22 00:59:23 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a (resumed — your de Bruijn VM design turn)
# Task: architect follow-up — does reference-by-hash belong in the de Bruijn VM design?
Role: Architect
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-22 00:59:23 +07 | Status: active | Rationale: same architect continues the same subject (resume rule); the owner raised a scope question against your design

Work in /Users/sto/workspace/datomworld. You MAY edit exactly ONE file:
`docs/design/yin.vm.debruijn-vm.md` (the design you just authored), and only as
described below. Give the complete answer now; do not wait for approval and do
not promise one.

## The owner's point

The owner said: a VM that runs the de Bruijn projection as bytecode "puts it on
par with Unison". The orchestrator answered that this holds for ONE axis only.
Verified from Unison's own documentation: a Unison definition is identified by a
hash of its syntax tree; variable names become De Bruijn indices in hashing; and
"all dependencies ... are replaced by their hashes", so identity extends across
the dependency graph, with names kept as separate metadata (sources:
unison-lang.org/docs/the-big-idea and its meetup writeup). Not verified: how
Unison's runtime executes code; do not claim anything about it.

In yin.vm as merged, the projection keeps free references BY NAME
(`{:free name}`; a changed free name changes the fingerprint; design section 3
and the section 8 test row), so renaming a global changes every caller's
fingerprint, and the VM resolves a free name at run time through env, store,
primitives and the module registry. That is the gap between "de Bruijn
bytecode for locals" and Unison-style identity that extends across
dependencies. The owner has not asked for it; they asked you whether it belongs.

## Existing machinery you must reconcile with (read it; it may already cover part)

- Code content addressing already exists: `yin.vm.content` (src/cljc/yin/vm/
  content.cljc) materializes code rows individually, shared subtrees shared
  across trees, and a canonical instruction vector under its
  `(jing/segment-key v)` address, with `:yin.code/hash` recorded in the VM's
  alias column on `yin.vm.semantic/load-vector`. See docs/design/
  yin.vm.code-as-tuples.md (section 2.1, 4.1, 7.3.2, 7.3.4, 7.7.2-7.7.3) and
  docs/design/yin.vm.universal-continuation-format.md.
- Module registry: src/cljc/yin/vm/module.cljc (a registry is a value in VM
  state). Dependency completion: docs/design/yin.vm.dependency-completion.md
  (`:yin.k/requires`, the reachable store slice, the work-item fixed point).
- Ledger/macros: src/cljc/yin/vm/ledger.cljc, macro.cljc, docs/design/
  yin.vm.macro.md.

## Answer

1. **What exists today.** Precisely which parts of reference-by-hash the repo
   already has (code addressed by hash, module manifests, `:yin.k/requires`,
   resolution of a name to an address) and which are absent (a link step that
   rewrites free references to definition hashes, hash-based call
   instructions, cycle handling).
2. **Ruling: in scope, a later epic, or a non-goal** of the de Bruijn VM
   design. Give reasons and the smallest coherent scope if in scope. Consider:
   (a) it cannot change the existing projection or fingerprint (section 9), so a
   linked form would be a NEW dimension or layer built from the projection plus a
   name-to-hash environment; (b) which free names are definitions versus
   primitives, store keys, or module exports, and what happens to the latter;
   (c) mutual recursion and cycles (identify strongly connected components and
   hash them as a unit, as any content-addressed system must), and whether the
   existing code layer already forces an answer; (d) what a hash-call
   instruction looks like in the de Bruijn VM and how it loads by address
   (yin.vm.content, dependency completion); (e) whether resolving at LINK time or
   LOAD time is right given the module registry as a value; (f) what this does
   to the fingerprint-as-cache-key benefit and to the equivalence contract with
   the named path.
3. **Honest parity statement.** In three or four bullets: on which axes the
   de Bruijn VM would match Unison-style identity, which it would not even with a
   link step, and which are out of scope. Use only facts you can support from
   this repository and the sources above; mark the rest UNVERIFIED.
4. **Edit the design.** Add the smallest correct treatment to
   `docs/design/yin.vm.debruijn-vm.md`: if in scope, a section with phases and
   completion criteria integrated into its plan; if a later epic or non-goal,
   an explicit non-goal entry plus a short "future work" paragraph naming the
   preconditions. Do not touch any other file. List the exact sections you
   changed in your response.

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a
then the four numbered sections, the list of sections changed, and any owner
decisions this raises.
