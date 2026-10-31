Created-GMT: 2026-09-22 10:05:26 GMT
Created-Local: 2026-09-22 17:05:26 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: pending (new session, caller-generated)
# Task: occurrence-identity-fix — fix a confirmed silent-correctness bug in the B2 resolver
Role: yin.vm / Interpreter Engineer
Implementers:
- Model: claude-sonnet-5 | Assigned: 2026-09-22 17:05:26 +07 | Status: active | Rationale: fresh session; fixing a confirmed defect in the just-implemented resolver, found by an independent architect sign-off pass and verified directly against the running code

Work in /Users/sto/workspace/worktree-debruijn-b2 (your launch directory;
branch debruijn-b2). Do NOT stage, commit, merge or push.

## The confirmed bug, in your own code

`src/cljc/yin/vm/debruijn_resolve.cljc`'s `visit-node!` (line 85) does
`(swap! vars assoc e (debruijn/resolve-name stack (get-attr e
:yin/name)))` for a `:variable` node -- a plain map assoc keyed by source
entity `e`. `ast->datoms-with-root` (`src/cljc/yin/vm.cljc:538-563`)
allows a node to carry a pre-assigned `:eid`, in which case the same
entity is emitted once and referenced from multiple sites -- for EVERY
node type, not only lambdas. `test/yin/vm/debruijn_test.cljc:457-472`'s
existing test `shared-nodes-resolve-per-their-lexical-context` places one
`:variable` entity (`:eid -41`) as the body of both `(fn [x] ...)` and
`(fn [y] ...)`. When `resolve` visits that entity the second time (under
`[y]`), the `swap! vars assoc` OVERWRITES the first resolution: your
resolver currently produces exactly ONE resolution for a shared entity
(whichever visit ran last) and silently applies it to BOTH occurrences in
the emitted tuples, because there is only one `:yin/name` datom row for
the shared entity in the input and your `tuples` `mapcat` (lines ~168-183)
emits one resolved fact set per row. This is a silent wrong answer, not a
crash: a real program using this AST shape (which the named linearizer
and the dormant projection both already handle correctly, by different
means) would resolve incorrectly under your current code.

## The corrected design (already updated; read it, do not re-derive it)

`docs/design/yin.vm.debruijn.stack.md` section 3.1 has been rewritten by
the architect to fix this. Read it in full, along with section 3.2 (also
updated) and the "B2: resolver and stack lowerer" phase box. Summary of
what changed, so you know what to look for while reading closely:

- Resolution is now a fact about an OCCURRENCE (`[source-eid
  lexical-context]`, `lexical-context` being the complete innermost-first
  stack of enclosing parameter vectors), not about a bare source entity.
  `resolve` mints one resolved record per distinct occurrence, with a
  fresh deterministic negative id in first-visit order, and reuses that
  record when the same occurrence recurs (equal entity, equal context).
  Two references to a shared entity under UNEQUAL contexts now get two
  distinct resolved records, each independently correct.
- Resolved ids never equal source ids. Provenance moves to a side table
  with two maps: `:source` (`{resolved-id source-eid}`, every record) and
  `:params` (`{resolved-lambda-id [param-symbol ...]}`, every resolved
  lambda's exact parameter vector). No per-occurrence name is stored; a
  bound occurrence's original spelling is recoverable from the owning
  resolved lambda's parameter at the resolved position.
- Binder names appear nowhere in the resolved tuples, including in ids.
- A cyclic input (via a pre-assigned `:eid` forming a cycle) is refused,
  matching the dormant projection's own refusal.
- The resolver exports one pure `validate-resolved [tuples side-table]`:
  shape, one resolution fact set per record, every bound reference inside
  its enclosing arity chain, every operand reference resolving to a
  record, `:source` total over the records. `lower-stack` must call it
  unconditionally at entry and refuse on its diagnostic, before doing
  anything else.
- `unresolve` now maps each record back through `:source`, restores
  `:yin/name`/`:yin/params`, and MERGES the records of one source entity
  (which carry equal facts by construction, since they came from the same
  original datom row set) back into one entity in the output. The inverse
  law is `unresolve(resolve x, side-table) = x` as a datom set, including
  the shared-entity fixtures -- this must now be tested with
  `shared-nodes-resolve-per-their-lexical-context`'s own AST shape (a
  shared `:variable` under two lambdas with different params) and at
  least one shared-LAMBDA-under-two-contexts case too (section 3.1: "a
  shared lambda under two contexts yields two resolved lambdas, each with
  its own resolved body").
- `lower-stack` reads source ids through the provenance `:source` map
  (never the resolved id) when building its own pc side table's `:source`
  field, so it still matches `lower`'s `:yin.code/source` at the same pc.
  It still expands occurrences positionally exactly as before (a resolved
  record referenced twice from the SAME context is emitted twice, at two
  pcs -- the occurrence memo affects resolver-side sharing, never emitted
  bytes).

## What to change (a bounded fix, not a rewrite)

Per the architect's own assessment: "the resolver needs the occurrence
memo, fresh deterministic ids with a provenance map, cycle refusal, the
exported validator, and the inverse's merge step. The stack lowerer
changes only where it reads source ids, now through the provenance map,
and gains the validator call at entry. The flattening walk, lift,
structural comparison, and every emitted byte are unchanged." Scope your
changes accordingly -- do not touch `lower-stack`'s actual flattening
walk, `lift`, or `adapt`'s composition beyond what section 3.1/3.2 above
requires.

1. In `debruijn_resolve.cljc`: replace the entity-keyed `vars`/`arities`
   atoms with an occurrence memo keyed by `[source-eid lexical-context]`
   (decide the concrete representation -- the stack itself, or something
   derived from it that compares correctly; document your choice).
   `visit-node!` must look up or mint a resolved record id per occurrence
   rather than per bare entity. Add cycle detection (a source entity
   already on the current path being revisited without having completed
   -- refuse with a qualified diagnostic, matching how the dormant
   projection refuses cycles; find and read that refusal if you need the
   exact shape to match). Build the `:source`/`:params` side-table maps
   instead of the current single `binders` map. Export `validate-resolved`
   per section 3.1's exact checks. Fix `unresolve` to merge one source
   entity's records back into one entity.
2. In `debruijn_linearize.cljc`: `lower-stack` calls `validate-resolved`
   first and refuses on its diagnostic (test this with a hand-built
   invalid resolved-tuple set, per the design's existing pattern for
   testing out-of-range fixtures). Update wherever it currently reads
   source ids for the pc side table to go through the provenance
   `:source` map instead of assuming resolved id equals source id.
3. Tests (`debruijn_resolve_test.cljc`, `debruijn_linearize_test.cljc`):
   add the shared-entity fixtures from
   `shared-nodes-resolve-per-their-lexical-context` (a shared `:variable`
   under two different-parameter lambdas) and a shared-lambda-under-two-
   contexts fixture, proving: (a) `resolve` produces two distinct correct
   resolutions, not one overwritten one; (b) `unresolve(resolve x,
   side-table) = x`, including these fixtures; (c) `validate-resolved`
   accepts what `resolve` produces and refuses a hand-built malformed
   set; (d) a cyclic input is refused; (e) `lower-stack`'s emitted bytes
   for these fixtures are correct (each occurrence resolves and lowers
   independently).
4. Also fix, from the independent review of your prior implementation
   (glm-5.3, P2 finding): `visit-node!`'s `reject-host-value!` calls are
   narrower than section 3.1 requires and narrower than your own
   docstring's parity claim with `yin.vm.linearize/lower`. Currently
   checked: `:yin/value` (literal), `:yin/key`, store-put's `:yin/value`.
   NOT checked: `:yin/name`, `:yin/params` content, `:yin/prefix`,
   `:yin/op`, `:yin/parked-id`, `:yin/buffer`. Either extend the checks to
   cover every data operand `lower`'s `emit!` would refuse, or correct the
   docstring to state the actual, narrower scope and name B1 as the
   eventual refuser for the uncovered cases -- your choice, but be
   explicit and consistent between code and docstring.

## Completion criteria (unchanged from the original B2 box, plus the above)

Everything the original completion criteria required (determinism, every
node type, exact free names and scalars, the duplicate-parameter fixture
resolving to `[0 1]`, out-of-range hand-built resolved tuples refused,
the structural comparison with `named-canonical-vector(lower x)`, the
lift law for the four required programs, B1's golden image bytes and H
unchanged) still applies and must still pass. This task adds: the
occurrence-identity fix itself, cycle refusal, the shared-entity/shared-
lambda fixtures proving the fix, and the host-value-rejection scope fix.

## Never

Same as the original B2 box: do not implement B3 further, B4, B5, or B6.
Do not touch `yin.vm.linearize`, `yin.vm.code`, `yin.vm.completion`,
`yin.vm.ast_walker`, `yin.vm.debruijn` (beyond calling `resolve-name`),
`yin.vm.debruijn-code`, or `yin.vm.debruijn.stack` -- only READ from them.
Keep files pure ASCII, no em dashes, cljstyle-style Clojure.

## Environment

Same as before: default PATH gives Java 21 and the mise clojure and bb.
This worktree is already `mise trust`ed. Focused JVM run:
`clojure -M:test -n yin.vm.debruijn-resolve-test -n yin.vm.debruijn-linearize-test`.

## Final report

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: <your session id>
Then: your occurrence-memo representation and why; how cycle detection
works and its test; how the inverse's merge-by-source step works and its
test; how validate-resolved's checks map to section 3.1's list; how the
host-value-rejection scope was fixed (extended checks, or corrected
docstring -- name which); what you ran with exact counts; what you could
not run; every deviation. Facts only.
