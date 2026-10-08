Created-GMT: 2026-09-20 17:31:00 GMT
Created-Local: 2026-09-21 00:31:00 +07 (Indochina Time)
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a (resumed — your lease/waitset consensus thread)
# Task: design the de Bruijn projection — canonical code form for the Universal AST

Role: Lead System Architect

The contract owner has commissioned a new compilation-layer unit, and this
task is its DESIGN: author the design + implementation plan for the **de
Bruijn projection** — a deterministic stream transformation that rewrites
the named Universal AST into its alpha-canonical form, so that
alpha-equivalent programs become structurally identical artifacts
(identical tuples, identical canonical encoding, identical content hash).

The owner's stated intent, verbatim constraints:

- The AST, the AST walker, and the semantic VM **do not change**.
- What changes is the **compilation pipeline**, which is built on the
  composition of dao.stream — so the projection is a new stream-to-stream
  interpreter, not a VM refactor.
- Pipeline: Yang → Universal AST (named form) → **de Bruijn projection** →
  projected form.
- The projected form serves two purposes: **(a) alpha-equivalence as
  identity** — two alpha-equivalent programs project to identical forms,
  so equality is a hash comparison of projections (the thing Clojure's
  identity-based fn equality cannot do); **(b) compression** — identical
  projections dedupe, and the projected form is smaller than the named
  form.
- The named form remains the **stored, bijective, queryable** form
  (names preserved, renderable back to any syntax). Both forms coexist;
  neither replaces the other.
- Layering: this is compilation-layer work. It must not interact with the
  lease layer (`dao.lease` — its unknown-silence conjunction ruling and
  cap-basis clarification are settled and untouched) or the waitset
  (committed at 45bfcc16/ee585c92, merged).

Read first:
- `docs/design/datom.md` — the moduli space (d1 content-addressing floor,
  d5 the datom, open dimensions)
- `docs/design/dao.lease.implementation-plan.md` §0.1 and D1 — the
  plain-data discipline and the segment-storage path your design composes
  with
- `src/cljc/yin/vm.cljc` — the AST schema (`:yin/*` attributes), the
  emitter (`emit!`, `ast->datoms-with-root`, `gen-id`)
- `src/cljc/yin/vm/linearize.cljc` and `test/yin/vm/linearize_test.cljc`
  — the existing stream-to-stream lowering idiom your design joins
- `public/chp/blog/universal-ast-vs-assembly.blog` — the owner's essay
  whose example the projection must preserve

The design must specify, at minimum:

1. **The projection algorithm**: the scope stack (binder names → depths,
   pushed on lambda entry, innermost = 0, popped on exit); occurrence
   resolution (in-scope → depth index, nearest binder wins on shadowing;
   out-of-scope → free, name preserved); the treatment of every node type
   the emitter produces (`:literal`, `:variable`, `:lambda`,
   `:application`, `:if`, `:dao.stream.apply/call`, the `:vm/*` and
   `:stream/*` primitives); determinism (same input datom set → same
   projected output, byte-for-byte).
2. **The projected form**: exact tuple/attribute shapes — the binder
   becomes arity, bound occurrences become depth indices, free names
   survive, refs preserved — such that the projection is a pure function
   of the alpha-equivalence class.
3. **The fingerprint**: the canonical encoding of the projected form and
   its hash — the alpha-invariant identity of the program. Specify the
   encoding rules that make it deterministic.
4. **The pipeline position**: where the projection sits (post-Yang,
   pre-storage), which consumers take the projected form vs the named
   form, and what the layering forbids.
5. **The implementation plan**: phases, the files touched
   (`src/cljc/yang/…` or `src/cljc/yin/vm/…` — you decide and justify),
   the test list (alpha-equivalent pairs → identical projections and
   identical fingerprints; non-equivalent → different; shadowing; free
   names; determinism), and the end condition.
6. **What must not change**: the AST schema, the walker, the semantic VM,
   `dao.lease`, the waitset, the stored bijective named form. Note any
   pressure your design puts on them, if any.
7. **Open questions** for the contract owner, if any — named, not
   silently decided.

Author the design as `docs/design/yin.vm.debruijn-projection.md` in this
repository, following the house design-document conventions (see
`docs/design/dao.lease.md` for the rule-shaped voice and
`docs/design/dao.lease.implementation-plan.md` for the plan structure).
That file is the ONLY file you may create or edit.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07 (Indochina Time)>

Then report: the design's structure, the key decisions, the open
questions, and anything unresolved.
