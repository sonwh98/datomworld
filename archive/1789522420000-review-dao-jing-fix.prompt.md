Created-GMT: 2026-09-15 21:13:40 GMT
Created-Local: 2026-09-16 04:13:40 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: deepseek
Session-ID: a1827996-e09c-45fc-a454-74de7c9c4826
# Task: Adversarial review of dao.jing canonical encoder P0 fix
Role: Adversarial Review
Implementers:
- Model: deepseek-v4-pro | Assigned: 2026-09-16 04:13:40 +07 | Status: active | Rationale: advanced logic/adversarial-review strength per team.md, cross-family from the GLM implementer, no spending cap tonight

## Context

`src/cljc/dao/jing.cljc` implements DaoJing's content-addressing encoder.
An earlier attempt at fixing three known collision defects (metadata
never printed, set tag inside the value domain, records silently
colliding with equal maps) was reviewed and found only partially closed
by an independent reviewer
(`collab/1789502626000-claude-review.findings.md`, "Fix 1" section — read
this first, it's the specification of what was broken and why).

GLM (glm-5.3) then implemented a full fix. This is currently an
uncommitted working-tree diff to `src/cljc/dao/jing.cljc` (+122/−20) and
`test/dao/jing_test.cljc` (+111 new tests). The orchestrator has already:
- read the full diff
- independently run `clojure -M:test -n dao.jing-test` (38 tests, 226
  assertions, 0 failures)
- independently run the two real consumers of `materialize!`:
  `dao.space.index-test` (55 tests, 512 assertions, 0 failures) and
  `dao.data.btree-durability-test` (21 tests, 768 assertions, 0 failures)

GLM's own findings are at
`collab/1789513569000-storage-jing-canonical-fix.glm-5.3.findings.md` —
read it, but verify its claims yourself rather than trusting them; that's
your job here.

## Task

Adversarially review the diff (`git diff -- src/cljc/dao/jing.cljc
test/dao/jing_test.cljc`). You are looking for defects the implementer
and the orchestrator's spot-check missed. Specifically probe:

1. **Metadata handling correctness.** `order-normalize`'s `attach-meta`
   strips `:line :column :end-line :end-column` before considering
   metadata address-significant, and drops empty metadata after that
   strip. Is there any metadata-bearing value shape where this either
   over-strips (silently dropping metadata that should be
   address-significant) or under-strips (leaving host-specific reader
   noise that would make two semantically-identical values from different
   hosts/readers hash differently)? Consider nested metadata (metadata
   whose own value carries metadata), metadata on map/set elements deep
   inside a structure, and interaction between `attach-meta` and the
   `record?` check ordering (does a record check happen before or after
   metadata handling — could a record's metadata reach `attach-meta`
   before the type check rejects it?).
2. **`canonical-print` completeness and cross-host fidelity.** It
   hand-renders maps/sets/vectors/sequences and delegates scalars to
   `pr-str`. Are there scalar types whose `pr-str` output differs across
   `:clj`/`:cljs`/`:cljd` in a way that would break the "equal values
   produce the same bytes on every platform" invariant (`dao.jing.md`,
   Canonical encoding)? Consider: ratios, BigDecimals/BigIntegers, chars,
   keywords with unusual namespaces, floating-point edge values (NaN,
   Infinity, -0.0), and byte arrays (explicitly a supported type per
   `dao.jing.md`).
3. **The set-tag fix's completeness.** Sets now normalize to
   `sorted-set-by` and print via `#{}`. Is there any way to construct a
   non-set value that `canonical-print` would still render with `#{}`
   braces, or any way a set's canonical form could coincide with a
   map's or vector's canonical print output? Check the brace/bracket
   choice is exhaustive and non-overlapping across all five branches
   (map/set/vector/sequential/scalar).
4. **The records rejection.** Is `record? v` checked early enough to
   catch a record nested inside any collection at any depth, before that
   collection's own branch would otherwise try to treat the record as a
   map (records satisfy `map?` in Clojure)? Verify the `cond` ordering in
   the actual code, don't assume.
5. **`materialize!`'s new read-back check.** `(= (content-hash stored)
   (segment-hash address))` — walk through what happens if `stored` is
   itself not encodable (e.g., a backend returns something pathological).
   Does this throw a confusing error, or does it correctly surface as an
   integrity failure? Also check: does this change alter behavior for the
   common case (canonically-equal-but-not-`=` values like a list vs. a
   seq) in a way that could now wrongly accept or wrongly reject a
   legitimate idempotent re-materialize?
6. **Test coverage gaps.** Are there adversarial pairs the new test
   suite doesn't cover that you can construct and that would break under
   this implementation? Try to actually break it — construct a specific
   counterexample if you can, don't just speculate abstractly.

## Boundaries

Read-only. Do not edit anything.

## Deliverable

Report each finding as: severity (blocking / non-blocking) | file:line |
concrete failure scenario (not abstract) | recommended fix. End with an
explicit verdict: does this diff close the three P0 defects
(`collab/1789502626000-claude-review.findings.md`'s Fix 1) without
introducing new ones, safe to proceed to Architect sign-off — yes or no.
Produce the complete deliverable now, without waiting for further input.

Write your findings to
`collab/1789522420000-review-dao-jing-fix.deepseek-v4-pro.findings.md`
with the same header block as this prompt.
