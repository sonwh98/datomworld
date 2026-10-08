Created-GMT: 2026-09-22 09:42:11 GMT
Created-Local: 2026-09-22 16:42:11 +07 (Indochina Time)
Coding-Agent: glm
Session-ID: pending (new session, caller-generated)
# Task: reviewer-debruijn-b2-refactor — independent review of the B2 resolver/lowerer split
Role: Independent Reviewer
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-22 16:42:11 +07 | Status: active | Rationale: different model family from the implementer (claude-sonnet-5); B2's original fused implementation already passed independent review from qwen3.8-max, this reviews only the restructuring

Work in /Users/sto/workspace/worktree-debruijn-b2 (your launch directory;
branch debruijn-b2). This is a READ-ONLY, STATIC REVIEW: read the files
named below in full and reason about them directly. Do not edit any file,
do not run `git add`/`commit`/`push`, and do not attempt to run the test
suite or a build. The orchestrator has already run and verified these
independently (57 tests, 555 assertions across the full de Bruijn
namespace set, 0 failures; kondo 0 errors/0 warnings) -- find defects by
reading the code the way a human reviewer would.

## Context

A prior fused implementation of B2 (one namespace doing named-datom
resolution and stack-image assembly in one pass) was independently
reviewed by qwen3.8-max and found READY WITH CHANGES with no correctness
defect in its core logic (P1/P2 findings were about an unrelated rename
and stale docstrings, already fixed separately). Before that fused
implementation was committed, the project's register-VM design was
corrected: the stack VM and a future register VM must be PEER projections
derived from a shared "de Bruijn encoding of the semantic tuples," neither
derived from the other. This forced splitting B2 into two namespaces so a
future register lowerer can consume the same shared artifact the stack
lowerer does, instead of chaining after the stack image.

The split was specified by the architect (claude-fable-5-1) in
docs/design/yin.vm.debruijn.stack.md section 3 (now 3.1 resolver, 3.2
stack lowerer) and the "B2: resolver and stack lowerer" phase box, then
implemented by a fresh engineer session. Your job is to review whether the
IMPLEMENTATION correctly realizes that split and preserves everything the
fused version already got right -- not to re-litigate the resolution
algorithm itself (resolve-name usage, alpha-equivalence, the lift law),
which the prior review already found sound, except where the split itself
could have introduced a NEW defect at the seam between the two
namespaces.

## Read first, in full

- docs/design/yin.vm.debruijn.stack.md section 3 (both 3.1 and 3.2) and
  the "B2: resolver and stack lowerer" phase box -- this is the exact spec
  the implementation must satisfy.
- src/cljc/yin/vm/debruijn_resolve.cljc (new: the resolver namespace,
  `yin.vm.debruijn-resolve`) -- resolve, its scope validator, and
  `unresolve` (the test oracle).
- test/yin/vm/debruijn_resolve_test.cljc (new).
- src/cljc/yin/vm/debruijn_linearize.cljc (reduced: now only `lower-stack`,
  `adapt`, `lift`, and public `named-canonical-vector`) -- confirm the
  vector-level helpers the design says must be REMOVED, not moved
  (`closure-body-ranges`, `layout-conforms?`, `body-owner`, `chain-of`,
  vector-level `resolve-var`, `rewrite`, the duplicate vector-level
  `image-scope-defect`) are actually gone, not just renamed or left dead.
- test/yin/vm/debruijn_linearize_test.cljc (reduced/updated).

## What to check specifically (the seam, not the algorithm)

1. **Scope validation moved correctly.** The design says tree-level scope
   validation in the resolver REPLACES the old vector-level
   `layout-conforms?`-equivalent check as the primary defense (B1's
   `image-defect` remains the vector-level backstop). Confirm the
   resolver actually refuses a malformed named tree (an out-of-range
   `resolve-name` result, or whatever the resolver's own defect condition
   is) BEFORE `lower-stack` ever runs, and that this refusal is tested
   with a hand-built malformed fixture -- not just asserted in prose.
2. **`lower-stack` really doesn't call `yin.vm.linearize/lower` on
   resolved tuples** (the design is explicit that `lower` cannot consume
   resolved tuples -- `lower-stack` must reproduce the flattening walk,
   occurrence expansion, and body layout itself). Confirm this by reading
   the actual code path, not the docstring.
3. **The structural comparison test still holds**: `lower-stack`'s output,
   compared position-by-position against `named-canonical-vector(lower
   x)`, must differ ONLY at variable/closure operands, matching the
   design's required test. Confirm this test still exists post-split and
   actually asserts equality at every other position (not just "both
   run").
4. **The lift law still holds under the new composition**:
   `lift(adapt x, side-table) = canonical-vector(lower x)` where `adapt`
   is now `resolve` composed with `lower-stack`. Confirm the test covers
   the same four required programs as before (simple, duplicate-parameter
   `(fn [x x] x)`, free-variable, nested-closure), both with the
   resolver's own side table (exact equality) and with synthesized names
   (alpha-equivalence).
5. **B1's golden image bytes and H are unchanged.** The design is explicit
   this refactor changes derivation, not bytes -- if any golden hash in
   test/yin/vm/debruijn_code_test.cljc or elsewhere changed, that is a bug.
   Confirm no golden fixture file was touched by this refactor (git diff
   should show debruijn_code_test.cljc, if touched at all, unrelated to
   golden bytes).
6. **The resolver's own completion criteria** (section 3.1): deterministic
   output, every node type resolved or refused with a named diagnostic,
   the duplicate-parameter fixture `(fn [x x] x)` resolving to `[0 1]`,
   out-of-range hand-built resolved tuples refused by the resolver's own
   validator, and `unresolve(resolve x, side-table) = x` over the corpus
   (the inverse law) -- confirm each is actually tested, naming the test.
7. Anything a fresh pair of eyes would flag as a genuine correctness risk
   introduced specifically by moving code between namespaces (a helper
   that silently changed behavior in the move, a validation check that
   got dropped rather than relocated, an off-by-one in body/pc indexing
   that a copy-paste of the flattening walk could introduce).

## Verdict

READY / READY WITH CHANGES / NOT READY, findings as P1 (must fix before
commit) / P2 (should fix) / P3 (nice to have). Be specific: file, line,
concrete failure scenario for every P1/P2.

## Final report

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: <your session id>
Then the verdict and findings. Facts only.
