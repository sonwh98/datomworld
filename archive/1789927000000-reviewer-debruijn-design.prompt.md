Created-GMT: 2026-09-20 17:27:00 GMT
Created-Local: 2026-09-21 00:27:00 +07 (Indochina Time)
Session-ID: 8fafe5c6-77cd-4436-8231-ff0f220ce947 (resumed — your lease/waitset review session)
# Task: adversarial design review — the de Bruijn projection (canonical code form)

A new compilation-layer design has been authored per the owner's commission:
a deterministic stream transformation that rewrites the named Universal AST
into its alpha-canonical form, so alpha-equivalent programs become
structurally identical artifacts with identical content hashes. The design
doc under review is `docs/design/yin.vm.debruijn-projection.md` (authored
by gpt-5.6-sol from the lease/waitset consensus thread).

Read first:
- `docs/design/yin.vm.debruijn-projection.md` — the design under review
- `docs/design/dao.lease.md` — the contract your previous reviews guarded
  (the holder/lease semantics; the projection must not disturb them)
- `docs/design/datom.md` — the moduli space (d1 content-addressing floor,
  d5 the datom, open dimensions)
- `src/cljc/yin/vm.cljc` — the emitter (`emit!`, `ast->datoms-with-root`,
  the `:yin/*` schema)
- `src/cljc/yin/vm/linearize.cljc` — the existing lowering idiom
- `public/chp/blog/universal-ast-vs-assembly.blog` — the owner's essay whose
  example the design must preserve

The owner's constraints (verbatim, binding on the design):
1. The AST, the AST walker, and the semantic VM do not change.
2. The projection is a new stream-to-stream interpreter in the compilation
   pipeline (post-Yang, pre-storage), built on the composition of
   dao.stream — not a VM refactor.
3. The named AST remains the stored, bijective, queryable form. Both forms
   coexist; neither replaces the other.
4. The projected form serves (a) alpha-invariant identity — two
   alpha-equivalent programs project identically, so equality is a hash
   comparison — and (b) compression.
5. Layering: no interaction with `dao.lease` or the waitset.

Review for:
1. **Alpha-invariance soundness**: is the projection provably
   alpha-invariant (shadowing, capture, binder nesting, duplicate names,
   free/bound distinctions)? Name any input where two alpha-equivalent
   programs project differently, or two non-equivalent programs project
   identically.
2. **Determinism**: is the canonical encoding byte-stable for the same
   input (traversal order, id assignment, map/set iteration hazards)?
3. **Fingerprint soundness**: is the hash a function of the
   alpha-equivalence class alone (no accidental dependence on local ids,
   datom order, or emission history)?
4. **Completeness**: every node type the emitter produces must have a
   projection rule. Name any emitted `:yin/*` node type the design omits.
5. **The six open questions** the design defers — parameter depth
   convention, shared-node duplication, unordered literals, projected
   storage vocabulary, fingerprint versioning, source-to-projected
   provenance retention — rule on each with reasons.
6. **Boundary**: confirm no interaction with `dao.lease`/the waitset and
   no change to the AST schema, walker, semantic VM, or `dao.stream`.

Do not edit. Do not rerun suites (docs-only design; nothing is
implemented yet).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07 (Indochina Time)>

Report findings as P0-P3 | design section | evidence | recommended
correction. Rule on each of the six open questions with reasons. State
explicitly whether the design is ready for implementation dispatch.
