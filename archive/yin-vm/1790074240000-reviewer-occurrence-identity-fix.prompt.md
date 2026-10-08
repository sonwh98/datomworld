Created-GMT: 2026-09-22 10:24:00 GMT
Created-Local: 2026-09-22 17:24:00 +07 (Indochina Time)
Coding-Agent: deepseek
Session-ID: pending (new session, caller-generated)
# Task: reviewer-occurrence-identity-fix — independent review of the occurrence-identity fix
Role: Independent Reviewer
Implementers:
- Model: deepseek-v4-pro | Assigned: 2026-09-22 17:24:00 +07 | Status: active | Rationale: different model family from the implementer (claude-sonnet-5); B2's resolver/lowerer split already passed two independent reviews (qwen3.8-max, glm-5.3), this reviews only the newly added occurrence-identity fix

Work in /Users/sto/workspace/worktree-debruijn-b2 (your launch directory;
branch debruijn-b2). This is a READ-ONLY, STATIC REVIEW: read the files
named below in full and reason about them directly. Do not edit any file,
do not run `git add`/`commit`/`push`, and do not attempt to run the test
suite or a build. The orchestrator has already run and verified: full JVM
suite 1796 tests / 175421 assertions, 0 failures; kondo 0 errors/0
warnings. Find defects by reading the code.

## Context

An independent architect sign-off (gpt-5.6-sol) found the B2 resolver
design (and, exactly matching it, the implementation) could not soundly
represent an AST shape where one source entity is referenced under two
different lexical contexts (e.g. a shared `:variable` node placed as the
body of two lambdas with different parameter names) -- resolution facts
were written onto the shared source entity by plain `assoc`, so the
second visit silently overwrote the first (last-write-wins), giving one
WRONG resolution applied to both occurrences. This is confirmed real
against an existing test fixture proving the AST shape is deliberately
supported (`test/yin/vm/debruijn_test.cljc:457-472`,
`shared-nodes-resolve-per-their-lexical-context`, run against the dormant
merged projection, which already handles it correctly by a different
mechanism).

The fix: resolution is now keyed by OCCURRENCE (`[source-eid
lexical-context]`), not by bare source entity. `resolve` mints one
resolved record per distinct occurrence with a fresh id that never equals
the source id, reusing the record when the same occurrence recurs
(equal entity, equal context) and minting a new one when the same entity
recurs under a different context. Cycle detection was also added
(previously absent). Full spec: `docs/design/yin.vm.debruijn.stack.md`
section 3.1 (read it in full first).

## Read first, in full

- docs/design/yin.vm.debruijn.stack.md section 3 (3.1 and 3.2), the exact
  spec this implementation must satisfy.
- src/cljc/yin/vm/debruijn_resolve.cljc, in full -- this file changed
  substantially: the occurrence memo, `visit!`/`emit-node!`, cycle
  detection, the `:source`/`:params` side-table maps, `unresolve`'s
  merge-by-source step, and the exported `validate-resolved`.
- test/yin/vm/debruijn_resolve_test.cljc, in full -- especially the
  `shared-variable-under-two-contexts` and `shared-lambda-under-two-
  contexts` fixtures and their tests, and `cyclic-input-is-refused`.
- src/cljc/yin/vm/debruijn_linearize.cljc -- specifically wherever it now
  reads source ids through the `:source` provenance map (it must not
  assume resolved id equals source id anywhere) and its `validate-
  resolved` call at entry.

## What to check specifically

1. **The occurrence memo is genuinely correct, not accidentally
   entity-keyed again somewhere.** Trace every place a resolved id is
   minted or looked up; confirm the memo key is truly `[source-eid
   lexical-context]` (or an equivalent that distinguishes unequal
   contexts) everywhere, not just in the primary `:variable` path. Check
   `:lambda` occurrences too (the design requires: "a shared lambda under
   two contexts yields two resolved lambdas, each with its own resolved
   body").
2. **Cycle detection is sound and matches its stated model.** Confirm the
   `active`/in-progress-path check actually catches a cycle before
   infinite recursion, not after, and that the diagnostic is genuinely
   useful (names the entity and the cycle, not a stack overflow).
3. **`unresolve`'s merge-by-source step is actually correct**, not just
   "doesn't crash." For a shared entity with two resolved records, the
   design says both records carry EQUAL facts when translated back
   through `:source` for everything except the resolution-specific
   attributes -- confirm the implementation's `seen`-set-based
   idempotent emission is genuinely comparing/trusting that invariant
   correctly rather than silently picking an arbitrary one of the two
   records' non-resolution facts (which could differ if the resolver has
   a latent bug elsewhere).
4. **`validate-resolved`'s checks are complete enough to actually catch a
   malformed hand-built resolved-tuple set** -- try to construct, in your
   own reasoning (not by running code), a plausible malformed input this
   validator would NOT catch: a dangling operand reference, an
   out-of-range bound reference, two resolution fact sets on one record,
   a record with neither depth/position nor free. Check the actual
   validator code against each.
5. **The host-value-rejection scope fix** (`:yin/name`, `:yin/params`,
   `:yin/op`, `:yin/buffer`, `:yin/prefix`, `:yin/parked-id` now checked)
   -- confirm it actually matches what `yin.vm.linearize/lower`'s
   `emit!` would refuse, per the design's parity claim. Any data operand
   still uncovered?
6. **`:yin/macro?` pass-through** was added to both `resolve` and
   `unresolve` (the implementer's own flagged deviation, saying the
   original buggy implementation carried it for free as an unintercepted
   datom and the from-scratch tree reconstruction would otherwise have
   silently dropped it) -- confirm this is actually necessary and
   correctly threaded through both directions, even though no corpus
   fixture currently exercises `:macro? true`.
7. Anything else a fresh pair of eyes would flag as a genuine correctness
   risk in this specific fix.

## Verdict

READY / READY WITH CHANGES / NOT READY, findings as P1 (must fix before
commit) / P2 (should fix) / P3 (nice to have). Be specific: file, line,
concrete failure scenario for every P1/P2.

## Final report

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: deepseek
Session-ID: <your session id>
Then the verdict and findings. Facts only.
