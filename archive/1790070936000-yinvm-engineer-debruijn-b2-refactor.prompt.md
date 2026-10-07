Created-GMT: 2026-09-22 09:15:36 GMT
Created-Local: 2026-09-22 16:15:36 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: pending (new session, caller-generated)
# Task: debruijn-b2-refactor — split B2 into a resolver and a stack lowerer
Role: yin.vm / Interpreter Engineer
Implementers:
- Model: claude-sonnet-5 | Assigned: 2026-09-22 16:15:36 +07 | Status: active | Rationale: fresh session; the architecture changed under B2 before it was ever committed, so this replaces the existing implementation rather than patching it

Work in /Users/sto/workspace/worktree-debruijn-b2 (your launch directory;
branch debruijn-b2, HEAD includes B0, B1, and B3, all committed and
architect-signed off). Do NOT stage, commit, merge or push.

## Why this exists

A prior session implemented B2 as one fused pass: named datoms to the named
canonical vector to de Bruijn addressing to a stack-shaped image, all in
`src/cljc/yin/vm/debruijn_linearize.cljc`. Before that work was committed,
the owner corrected the project's register-VM design: the stack image and a
planned register image must be PEER projections, both derived from a de
Bruijn encoding of the semantic tuples, neither derived from the other. The
architect (claude-fable-5-1) then redesigned both
`docs/design/yin.vm.debruijn.register.md` and, for this phase specifically,
`docs/design/yin.vm.debruijn.stack.md` sections 3 (now 3.1/3.2) and the "B2:
resolver and stack lowerer" phase box, which now specifies TWO namespaces
instead of one fused one. Independent review (qwen3.8-max) of the old fused
B2 found no correctness defect in its logic, so the resolution algorithm
itself (via `resolve-name`) does not need to change -- only where the
boundary sits.

## Read first, in full

- docs/design/yin.vm.debruijn.stack.md section 1 (architecture/invariants),
  section 3 in full (now split into 3.1 "resolver" and 3.2 "stack
  lowerer"), and the "B2: resolver and stack lowerer" phase box. Read
  section 3 slowly, more than once -- it is the exact spec for this task.
- src/cljc/yin/vm/debruijn_linearize.cljc as it exists right now in this
  worktree: this is the FUSED implementation you are splitting. Its
  overall approach (running `yin.vm.linearize/lower`, using
  `yin.vm.debruijn/resolve-name` with an innermost-first frame stack,
  the lift/adapt round-trip design, the side-table-trusted-only-on-
  round-trip rule) is correct per independent review and per section 3's
  own description of what carries over -- read it to understand what
  already works before you restructure it.
- test/yin/vm/debruijn_linearize_test.cljc as it exists right now: same
  reason, and because its test corpus and lift-law coverage should mostly
  carry over structurally even though the file box changes.
- `yin.vm.debruijn/resolve-name` (`src/cljc/yin/vm/debruijn.cljc:606`): the
  one public helper you may reuse from the projection namespace, same as
  before.
- `yin.vm.linearize/lower` (`src/cljc/yin/vm/linearize.cljc`): still run
  unmodified as the source of the named canonical vector your structural
  comparison test needs, per section 3.2.
- B1's `yin.vm.debruijn-code` (`src/cljc/yin/vm/debruijn_code.cljc`,
  committed): descriptor, `image-hash`, `image-defect`. Unchanged target.
- B3's `yin.vm.debruijn.stack` (`src/cljc/yin/vm/debruijn/stack.cljc`,
  committed): unchanged target for `adapt`'s output.

## What to build (section 3, restated precisely)

Split the fused implementation into two namespaces, per the phase box:

1. **`src/cljc/yin/vm/debruijn_resolve.cljc`** (new namespace
   `yin.vm.debruijn-resolve`): `resolve`, taking named datoms (or the
   named AST) and producing resolved tuples per section 3.1 -- named
   datoms carried unchanged (entity ids preserved as provenance),
   `:variable` loses `:yin/name` and gains `:yin.resolved/depth` plus
   `:yin.resolved/position` (bound) or `:yin.resolved/free` (free),
   `:lambda` loses `:yin/params` and gains `:yin.resolved/arity`, plus a
   binder side table (lambda entity to parameter vector). Also: `resolve`'s
   scope validator (tree-level, runs before rewriting -- this replaces the
   fused implementation's vector-level `layout-conforms?`-equivalent
   check, now done directly on the tree instead of after linearization),
   and `unresolve` (resolved tuples plus side table back to named datoms)
   as the test oracle section 3.1 requires. Read section 3.1's exact
   completion criteria for this namespace's tests
   (test/yin/vm/debruijn_resolve_test.cljc, new).

2. **`src/cljc/yin/vm/debruijn_linearize.cljc`** (kept, reduced): now owns
   only `lower-stack` (resolved tuples to B1-shaped stack image -- it must
   reproduce `yin.vm.linearize/lower`'s own flattening walk, occurrence
   expansion, and body layout itself, since `lower` cannot consume
   resolved tuples; section 3.2 states the exact emission rules), `adapt`
   (the composition: `resolve` then `lower-stack`), `lift`, and the public
   `named-canonical-vector` helper the structural comparison test uses.
   REMOVE, do not move: `closure-body-ranges`, `layout-conforms?`,
   `body-owner`, `chain-of`, the vector-level `resolve-var`, `rewrite`,
   and the duplicate vector-level `image-scope-defect` -- section 3.2 says
   plainly these existed only because resolution was previously placed
   after linearization; scope is now known on the tree by `resolve`, and
   B1's `image-defect` remains the vector-level check.

## Completion criteria (do not weaken any of these; section 3's own list)

Resolver (`yin.vm.debruijn-resolve`): deterministic output on this host,
every node type resolved or refused with a named diagnostic, exact free
names and scalars preserved, the duplicate-parameter fixture `(fn [x x] x)`
resolving to `[0 1]`, out-of-range hand-built resolved tuples refused by
your scope validator, and `unresolve(resolve x, side-table) = x` over the
corpus (the inverse law).

Stack lowerer (`yin.vm.debruijn-linearize`): deterministic output; every
node and opcode; the structural opcode-by-opcode comparison with
`named-canonical-vector(lower x)` differing only at variable and closure
operands (section 3.2's required test, not just "both work" -- this
carries over from the old test file, keep it); image encode/validate/load
round trips against B1's `image-defect` and B3's `create-vm`/`run`;
`lift(adapt x, side-table) = canonical-vector(lower x)` exactly with the
original side table, alpha-equivalent with synthesized names (including
`(fn [x x] x)`, synthesized names fresh against the image's free-name
set); a supplied side table accepted only when `adapt` of its lift returns
the original image; every image you emit accepted by B1's validator, every
hand-built out-of-range image rejected by B1's validator; B1's golden
image bytes and H values reproduced unchanged by `adapt` (the refactor
changes derivation, not bytes -- if any golden H changes, that is a bug in
your refactor, not an expected outcome).

## Also fix, from the prior implementation's independent review (qwen3.8-max)

These are real findings against the fused implementation; fold the fixes
into the new split rather than patching the file about to be removed:

- The `corpus` docstring in the test file claims every one of
  `yin.vm.code/mnemonics`' 21 mnemonics is reached, but nothing actually
  computes and asserts that coverage. Add a real test: compute the union
  of emitted mnemonics across the adapted corpus and assert it equals
  `code/mnemonics`.
- The existing "a foreign side table that fails to round-trip falls back
  to synthesis" test is vacuous: its fixture (a 2-name vector on an
  arity-1 closure) is rejected by `lift-params`' arity check before the
  round-trip check ever runs, so the fallback branch it claims to cover
  is never executed. Replace or add a fixture that genuinely fails
  round-trip at the SAME arity (e.g. a same-arity capturing name that
  would re-resolve a free reference to bound) so the fallback branch is
  actually exercised.
- Document the precondition on `lift`/`lift-once` (raises on a
  scope-invalid image; B1's validator is the sole admitter, so this is
  fine, but say so in the docstring) and on your scope-defect check
  (undefined behavior if the tree is not already well-formed -- only
  shape-validity is disclaimed today).

## Never

Do not implement B3 further, B4, B5, or B6. Do not touch `yin.vm.linearize`,
`yin.vm.code`, `yin.vm.completion`, `yin.vm.ast_walker`, `yin.vm.debruijn`
(beyond calling the one public `resolve-name`), `yin.vm.debruijn-code`, or
`yin.vm.debruijn.stack` -- only READ from them. Do not reach any PRIVATE
var in the projection namespace beyond `resolve-name`. Do not add a new
lossless record structure (D2). Keep files pure ASCII, no em dashes,
cljstyle-style Clojure (blank lines between top-level forms), docstrings
that say what and why.

## Environment

Default PATH gives Java 21 and the mise clojure and bb. This worktree is
already `mise trust`ed. Focused JVM run:
`clojure -M:test -n yin.vm.debruijn-resolve-test -n yin.vm.debruijn-linearize-test`
(confirm the actual namespace names your files define first). If you need
kondo, cljstyle, the Java 17 lane, or the CLJD lane and they are denied,
say exactly what was denied and stop; do not retry, do not use
`--dangerously-skip-permissions`.

## Final report

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: <your session id>
Then: how the fused implementation was split (what moved to
`debruijn_resolve.cljc` verbatim vs. rewritten); the resolver's scope
validator and how it mirrors section 3.1; `lower-stack`'s own flattening
walk and how it stays layout-identical to `lower`; how each completion
criterion is met, naming the test; confirmation B1's golden H values are
unchanged; how the three qwen findings above were fixed, naming the test
for each; what you ran with exact counts; what you could not run; every
deviation. Facts only; promise nothing.
