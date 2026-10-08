Created-GMT: 2026-09-22 08:18:08 GMT
Created-Local: 2026-09-22 15:18:08 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: pending (new session, caller-generated)
# Task: debruijn-b2 — the named-datom lowerer adapter
Role: yin.vm / Interpreter Engineer
Implementers:
- Model: claude-sonnet-5 | Assigned: 2026-09-22 15:18:08 +07 | Status: active | Rationale: fresh session; this phase is the most intricate one so far (the lift/adapt alpha-equivalence proof) and benefits from full context budget rather than one already spent on B1 or B3

Work in /Users/sto/workspace/worktree-debruijn-b2 (your launch directory;
branch debruijn-b2, HEAD includes B0, B1, and B3, all committed and
architect-signed off -- you can use B1's `yin.vm.debruijn-code` and B3's
`yin.vm.debruijn-vm` directly). Do NOT stage, commit, merge or push.

## Read first, in full

docs/design/yin.vm.debruijn-vm.md sections 1 (Architecture and
invariants), 2 (Instruction dimension and code identity, for the
descriptor/lift-morphism vocabulary), 3 (Lowering and scope, this phase's
main spec), and the "B2: named-datom lowerer adapter" phase box. This is
the most intricate phase in the design so far -- read section 3 slowly,
more than once if needed, before writing code.

## Key precedents to read before designing your own logic (do not guess
## their shape; read them)

- `yin.vm.linearize/lower` (`src/cljc/yin/vm/linearize.cljc`): takes named
  `[e a v t m]` datoms and returns NEW datom rows encoding a
  `:yin.code/*` segment (`:yin.code/op`, `:yin.code/pc`, operand
  attributes per `yin.vm.code/vector-operand-table`). This is what you run
  first, unmodified -- B2 does not repeat its traversal (occurrence
  expansion, fresh lambda labels, body layout are all inherited from it).
- `yin.vm.ast-walker/vm-load-rows` (`src/cljc/yin/vm/ast_walker.cljc:724`):
  the existing named-VM precedent for turning `lower`'s output ROWS into a
  canonical POSITIONAL VECTOR (pc-indexed tuples). Read this to understand
  how rows become a vector in the named path; your adapter does the
  equivalent conversion but emits `:yin.debruijn.code/*` operand shapes
  (via B1's descriptor) instead of `:yin.code/*` shapes.
- `yin.vm.completion`'s private `closure-ranges`, `layout-conforms?`, and
  public `segment-scope` (`src/cljc/yin/vm/completion.cljc:132-168`): the
  EXACT algorithm shape section 3 asks you to reproduce (not call -- these
  operate on `:yin.code/*` vectors with named `:closure` params, yours
  operate on `:yin.debruijn.code/*` vectors with `:closure` arity). Read
  them closely: `closure-ranges` finds each closure's `[body, first
  :return at-or-after body]` span; `layout-conforms?` checks every body is
  closed, none covers pc 0, and bodies are pairwise disjoint (never
  interleaved); `segment-scope` derives the `[pc, enclosing-closure-pc]`
  relation from those spans. This is the "scope reconstruction is a pure
  function of the image" section 3 requires.
- `yin.vm.debruijn/resolve-name` (`src/cljc/yin/vm/debruijn.cljc:606`):
  the ONLY public helper from the merged (dormant) projection namespace
  you may reuse. Read its docstring and body in full -- it already
  implements exactly the name-stack resolution section 3 wants
  (inner-to-outer frame search, rightmost-wins duplicate parameters,
  `{:bound [depth position]}` or `{:free name}`), but takes a `stack`
  argument whose frame order and construction you must build yourself
  from your reconstructed scope (innermost-first per section 3 -- the
  OPPOSITE of the B3 runtime frame vector's outermost-to-innermost order;
  section 3 says this conversion must be explicit, so document it plainly
  where you build the stack).
- B1's `yin.vm.debruijn-code` (this worktree, committed): `descriptor`,
  `scalar-class`/`encode-scalar`, `image-hash`, `image-defect` (the
  validator). Every image you emit must pass `image-defect`; every
  hand-built out-of-range image you construct as a NEGATIVE test must be
  rejected by both your own validation and B1's.

## Scope (B2's own file box)

    New: src/cljc/yin/vm/debruijn_linearize.cljc
    New: test/yin/vm/debruijn_linearize_test.cljc
    Existing edits: none
    Must not change: yin.vm.linearize and named lowering semantics

## What to build (section 3, restated precisely)

1. **Run `yin.vm.linearize/lower`** on the named datoms, unmodified, to
   get the named `:yin.code/*` rows. Turn those into the named canonical
   vector the same way `vm-load-rows` does (read it; you may call it, or
   reimplement its rows-to-vector step if that is cleaner for your
   purposes -- say which and why).
2. **Rewrite each `:var` operand** to `:load-bound [depth position]` or
   `:load-free [name]` (B1's shapes), and **replace each `:closure`
   parameter vector with its arity** (B1's shape: `[arity body-pc]`).
   Every other instruction's operand shape is copied unchanged (same
   values, renamespaced per B1's descriptor if needed).
3. **Scope reconstruction is a PURE FUNCTION OF THE IMAGE**, never of
   `:yin.code/source` (the named AST reference each row carries for
   diagnostics) -- do not use `:yin.code/source` to figure out scope; use
   only the structural body-range/enclosing-body-chain relation you
   derive the way `closure-ranges`/`layout-conforms?`/`segment-scope`
   derive it. A failed `layout-conforms?`-equivalent check on the NAMED
   vector (before rewriting) is a validation defect -- report it clearly,
   do not silently produce a wrong image.
4. **Diagnostic side table**: binder names and provenance (original
   parameter symbols, source references) go in a side table indexed by
   instruction slot, exactly as section 2 describes -- this is what B1's
   descriptor's lift morphism consumes. Design its shape now; document it.
5. **The lift**: a function from (de Bruijn image, side table) back to a
   `:yin.code/*`-shaped canonical vector -- `:load-bound`/`:load-free`
   rewritten back to `:var` using the side table's names (or synthesized
   fresh names, fresh against the image's free-name set, when no side
   table entry exists or none is supplied), `:closure`'s arity rewritten
   back to a parameter vector.
6. **The lift correctness law** (this phase's hardest completion
   criterion): `lift(adapt(lower x), side-table) = canonical-vector(lower
   x)` when the ORIGINAL side table (the one your adapter itself produced)
   is supplied -- exact equality. With SYNTHESIZED names (no side table,
   or a different one), the lifted result must be ALPHA-EQUIVALENT to
   `canonical-vector(lower x)`, not necessarily equal -- including the
   duplicate-parameter case `(fn [x x] x)` (where `resolve-name`'s
   rightmost-wins rule matters). A supplied side table is accepted (used
   to produce named output) only when adapting ITS lift returns the
   original de Bruijn image back -- i.e. the side table must itself be
   validated by a round trip before being trusted, not merely stored.

## Completion criteria (do not weaken any of these)

- Deterministic output: running your adapter twice on the same named
  datoms produces byte-identical de Bruijn images (same `image-hash`).
- Every node and opcode the named linearizer can emit is handled -- do not
  silently skip an operation; if something is out of scope, fail loudly
  with a clear diagnostic, the same posture B1 and B3 took for their own
  out-of-scope cases.
- Lexical validation: your own `layout-conforms?`-equivalent check, run
  BEFORE rewriting, catches a malformed named vector.
- A structural opcode-by-opcode comparison with `lower`'s own output
  (post `vm-load-rows`) differing ONLY at `:var`/`:closure` operands --
  every other pc's mnemonic and other operands match exactly. Write a test
  that actually asserts this structurally (diff the two vectors
  position-by-position, assert equality except at var/closure operand
  slots), not just that both "work."
- Image encode/validate/load round trips: `image-hash`, `image-defect`
  (B1), and B3's `create-vm`/`run` all accept every image you emit.
- The lift law from item 6 above, tested for: a simple program (no
  duplicates), a duplicate-parameter program `(fn [x x] x)`, a
  free-variable program, and a nested-closure program -- both with the
  adapter's own side table (exact equality) and with no side table
  (alpha-equivalence, synthesized fresh names).
- Every image B2 emits is accepted by B1's validator (`image-defect`
  returns nil); every hand-built out-of-range image (construct at least
  one, e.g. a `:load-bound` pointing past its enclosing arity) is rejected
  by BOTH your own validation and B1's.

## Never

Do not implement B3 further, B4, B5, or B6. Do not touch `yin.vm.linearize`,
`yin.vm.code`, `yin.vm.completion`, `yin.vm.ast_walker`, or the merged
projection namespace (`yin.vm.debruijn`, `yin.vm.pipeline`) -- only READ
from them. Do not reach any PRIVATE var in the projection namespace beyond
the one public `resolve-name` (no `index-frame`, `build-node`,
`project-node`). Do not add a new lossless record structure (D2). Keep
files pure ASCII, no em dashes, cljstyle-style Clojure (blank lines
between top-level forms), docstrings that say what and why, matching this
design doc's own register.

## Environment

Default PATH gives Java 21 and the mise clojure and bb. This worktree is
already `mise trust`ed. Focused JVM run: `clojure -M:test -n
yin.vm.debruijn-linearize-test` (confirm the actual namespace your file
defines first). If you need kondo, cljstyle, the Java 17 lane, or the
CLJD lane and they are denied, say exactly what was denied and stop; do
not retry, do not use `--dangerously-skip-permissions`.

## Final report

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: <your session id>
Then: how rows become your canonical positional vector (reused
`vm-load-rows` or your own equivalent, and why); your scope-reconstruction
algorithm and how it mirrors `closure-ranges`/`layout-conforms?`/
`segment-scope`; the side-table design; the lift function and how the
correctness law is proven for each of the four required programs; how
each completion criterion is met, naming the test; what you ran with
exact counts; what you could not run; every deviation. Facts only; promise
nothing.
