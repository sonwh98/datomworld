Created-GMT: 2026-09-22 10:00:06 GMT
Created-Local: 2026-09-22 17:00:06 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 341722e7-dd66-4583-996a-da14eaaeb56d (resumed: your register-VM design session)
# Task: architect-occurrence-identity-fix — fix a confirmed blocking defect in the resolved-tuples design
Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-22 17:00:06 +07 | Status: active | Rationale: fixing a confirmed defect in your own resolved-tuples design (section 2.1/3.1), found by an independent architect sign-off pass (gpt-5.6-sol) and verified directly by the orchestrator against real code and a real test

Work in /Users/sto/workspace/datomworld (your launch directory; branch
master). You may edit docs/design/yin.vm.debruijn.stack.md (section 3
only) and docs/design/yin.vm.debruijn.register.md (section 2.1/2.2 only,
plus any section that directly depends on the resolved-tuples identity
model). Do not edit any source file.

## The confirmed defect

Your "resolved tuples" design (stack design section 2.1, register design
section 2.1) proposes representing name resolution as facts attached
directly to the SOURCE entity: `:variable` loses `:yin/name` and gains
`:yin.resolved/depth`+`:yin.resolved/position` (bound) or
`:yin.resolved/free` (free), keyed by the same entity id the named AST
datoms already use.

This is unsound because entity sharing across different lexical contexts
is an existing, deliberately supported, already-tested feature of this
project's AST model -- not a hypothetical edge case. Read, in full:

- `src/cljc/yin/vm.cljc` lines 538-563 (`ast->datoms-with-root`): a node
  may carry a pre-assigned `:eid`; `convert` accepts `pre-eid` for EVERY
  node type (not only lambdas -- the duplicate check happens before the
  type dispatch), and when the same `:eid` is reused across the AST, the
  entity is emitted exactly once and referenced from multiple places. This
  is the mechanism for sharing a subtree (a lambda definition referenced
  at multiple sites, or, as the confirmed counterexample below shows, a
  bare `:variable` node referenced from two different binder scopes).
- `test/yin/vm/debruijn_test.cljc` lines 457-472, in full -- the existing
  test `shared-nodes-resolve-per-their-lexical-context`, "one shared
  source node under unequal lexical contexts": one entity (`:eid -41`) is
  placed as the body of BOTH `(lam '[x] body)` and `(lam '[y] body)`. The
  dormant merged projection (the one you are NOT touching, D12) correctly
  resolves this one entity two different ways depending on which lambda's
  body position is being projected: `{:yin.debruijn/bound [0 0]}` under
  `[x]`, `{:yin.debruijn/free 'x}` under `[y]`. It does this by memoizing
  per `[source-eid, lexical-context]` and emitting FRESH PROJECTED nodes
  per occurrence -- never by writing resolution facts back onto the
  shared source entity itself. Read
  `docs/design/yin.vm.debruijn-projection.md` lines 91-95 for how the
  existing design already documents this memoization requirement.
- The orchestrator additionally confirmed the CURRENT B2 refactor
  implementation (`/Users/sto/workspace/worktree-debruijn-b2/src/cljc/yin/vm/debruijn_resolve.cljc`,
  read it there) has NO eid-deduplication logic at all (`grep -n
  "seen-eid\|pre-eid\|:eid"` returns nothing) -- it is a live, reachable
  defect in real code, not just a design-paper gap: a shared entity under
  two lexical contexts would be visited twice and would get two
  conflicting `:yin.resolved/*` fact sets written for the same entity id.

Your resolved-tuples design therefore cannot represent this AST shape at
all today, silently produces an incoherent/last-write-wins result (you
must determine which, by reading the actual `visit-node!` implementation
in that file), where the named linearizer (`yin.vm.linearize/lower`,
already merged, unaffected by this defect) and the dormant projection both
already handle it correctly, by different means (the named linearizer
processes reference occurrences positionally into a flat instruction
stream at distinct program counters, never writing resolution facts back
onto a shared source entity; the projection memoizes per
`[source-eid, lexical-context]`).

## What to fix

Correct the resolved-tuples representation so it can soundly represent a
shared source entity referenced under different lexical contexts. You
must choose and justify one approach (or a better one you identify):

1. **Occurrence-indexed identity**, mirroring the dormant projection's
   `[source-eid, lexical-context]` memoization: resolved tuples are keyed
   by occurrence, not by bare source entity; a shared source entity
   produces multiple resolved-tuple records, one per distinct lexical
   context it appears under, each carrying its own resolution facts. State
   precisely what identifies an "occurrence" (the lexical-context key
   itself? a synthesized occurrence id? the pc/position it will eventually
   land at in EITHER lowerer's output, decided before either lowering
   runs?) and how `unresolve`/the inverse law and the side table still
   work under this scheme.
2. **Mandatory occurrence-expansion before resolution**: require that
   shared subtrees be expanded into distinct entities (turning the AST
   DAG into a tree) as a precursor pass, before `resolve` ever runs, so
   `resolve` can keep its current entity-attribute representation
   unchanged because by the time it runs, no entity is ever visited twice
   under different contexts. State where this expansion pass lives (is it
   part of `resolve` itself, a separate stage, or does it turn out that
   `yin.vm.linearize/lower`'s own existing occurrence-expansion already
   does exactly this and resolved tuples should be defined as running
   AFTER that expansion rather than before it -- read
   `yin.vm.linearize/lower`'s occurrence-expansion behavior yourself to
   settle this, do not assume).
3. Any other sound resolution you can justify against invariant I, D2,
   D9, D12, and derive-don't-persist.

Whichever you choose, state exactly how it changes:
- `docs/design/yin.vm.debruijn.stack.md` section 3.1's resolved-tuple
  contract and the resolver's file box / completion criteria.
- `docs/design/yin.vm.debruijn.register.md` section 2.1's definition of
  the shared artifact, and anything in sections 2.2, 3, or 4 that assumed
  the old entity-keyed representation.
- Whether the B2 refactor implementation
  (`debruijn_resolve.cljc`/`debruijn_linearize.cljc`, in the worktree
  above, already independently reviewed twice and otherwise found sound)
  needs to change at all, needs a small additive fix, or needs
  re-implementing against the corrected contract -- be precise, this
  determines how much rework follows.

Also address, since you have the design open: gpt-5.6-sol's sign-off pass
also raised three should-fix findings you have not yet addressed (not
blocking, but note them for completeness if trivial to fold in now,
otherwise leave for a later pass and say so explicitly):
- `lower-stack`/a future `lower-register` should both unconditionally call
  one shared `validate-resolved` from the resolver, not each own their own
  partial check.
- The register design's "share H iff share R" claim (section 1.1) is an
  overclaim -- state it as one-directional determinism only, not a proven
  biconditional, unless you can actually prove the converse.
- The lift's side-table trust model should default to synthesized,
  capture-free names for execution; a supplied side table should be
  accepted only after arity validation AND a round-trip check (this may
  already be true of the implementation -- confirm against the design's
  own stated completion criteria and tighten the design's wording if the
  design text is looser than what's actually required).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then: which approach you chose and why; exactly what changed in each
document; whether/how much the B2 implementation needs to change; how the
three should-fix findings were handled; anything needing the owner's
decision.
