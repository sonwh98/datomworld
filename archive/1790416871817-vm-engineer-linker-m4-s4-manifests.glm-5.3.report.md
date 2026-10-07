Created-GMT: 2026-09-26 10:36:14 GMT
Created-Local: 2026-09-26 17:36:14 +07
Coding-Agent: glm
Session-ID: c5f7a3b4-9f48-4bc1-bb6e-58ba0d24b079
Task: yin.vm.linker M4 slice S4, module manifests and derivation records

Status: COMPLETE (final response carries the same)

## What was built

All in `src/cljc/yin/vm/linker.cljc` (owned) and the new
`test/yin/vm/linker_manifest_test.cljc`. No other file touched;
`engine.cljc`, `module.cljc`, the kernels, and every UCF doc are
untouched (`git status`: M linker.cljc, one new test file).

1. `manifest-format` and `record-format` (section 8.1), following the
   M2 record pattern exactly (all ten slots):
   - `manifest-defect`: the schema-1 validator over the closed key set
     of section 8.1's example (the index alone optional). One schema
     version: `manifest-schema` = 1; another version (or none) is
     `{:rule :schema}`. A derivation format missing from
     `:yin.module/contracts` is `{:rule :contract-missing :format f}`.
     A declared name that is the definition operator is
     `{:rule :reserved-name :role :declaration}` (Rule R).
   - `record-defect`: exactly `yin.vm.ledger/derive-record`'s closed
     key set, one `:derive` op, the tree's Jing address as input, the
     image's identity as output (segment address for the vector format,
     contract-pinned hash string for the two de Bruijn formats), a
     keyword function, a map profile.
   - `:contract` of the two records: the manifest record's is the
     schema version `1`; the record format's is `:derive`.
   - No parts, no obligations, `segment-matches?` identity: the
     manifest and record are content like anything else.
2. `link-manifest` (new public entry point, see deviations): the
   manifest-delivered image link, host policy threading one runtime
   state through the existing stepped core (`request-link` + `step`,
   one sub-link per fetch; no change to `fetch`, `step`, `step`'s
   pipeline, or admission). Fixed order: admission (format record,
   requester contract) -> manifest fetch (steps 1-4 through
   `manifest-format`) -> the manifest's `:yin.module/contracts` entry
   for the requested format vs the format record's contract
   (`:contract-mismatch`, contract-before-content) -> the name check
   (`:module-name-mismatch` naming both, against `opts :name`, the
   name the composition resolved the manifest under) -> the
   `:yin.module/index` merge into `:indexes` of the requested format
   -> the derivation record fetch and its two address checks (input =
   `:yin.module/tree`, output = the identity fetched;
   no record for the format is `:unsupported-format`) -> the policy:
   under `:trusted` the addresses are everything and the outcome says
   `:trust :composition`; under `:verifying` (the default) the profile
   must be one this linker implements (`:unverified-derivation`
   otherwise), the tree is fetched and verified through `ast-format`
   (a refusal is `:unverified-derivation` naming the profile and what
   was missing), and the recomputed identity must equal the record's
   output (`:derivation-mismatch`) -> the image fetch (the six steps)
   -> the step 5a manifest join (`:undeclared-free`; declared
   obligations become `{:kind :primitive :profile addr}` or `{:kind
   :module :module m :manifest addr}` records) -> discharge (5b).
3. Derivation profiles: `ledger/lowering-profile` is the semantic
   format's; `stack-lowering-profile` ("b2-stack-lowering") and
   `register-lowering-profile` ("r1-register-lowering") are pinned
   here (see deviations). The `:verifying` re-lowering for the two de
   Bruijn formats reconstructs the datom lane
   (`semantic-bytecode->ast` -> `ast->datoms` -> `resolve` ->
   `lower-stack`/`lower-register`); a scratch probe (run, then
   deleted) confirmed the round trip reproduces identical H, R, and
   SEM identities for the whole corpus before I relied on it. The
   semantic format's recomputation is `yin.vm.ledger/verify-derivation`
   itself (tree + fetched vector + record + implemented profile), with
   its `:yin.k/profile-mismatch` mapped to `:unverified-derivation`
   and `:yin.k/derivation-mismatch` to `:derivation-mismatch`.
4. `discharge` extended for `:kind`-bearing obligations (5b's rule): a
   primitive obligation only by an equal `:yin.k/profile` address, a
   module obligation only by an equal manifest address
   (`module/resolve-module`), name-shadowing first; kind-less
   obligations take the old path unchanged.
5. The fallback pair keeps its B6 names, shapes, and arities and adds
   manifest 3-arities (section 5.5): `trusted-fallback` and
   `verifying-fallback` over a manifest address run `link-manifest`
   with their policy pinned (`:trust :composition` / `:trust
   :verified`), tag `:fallback {:root tree :manifest addr :from
   :yin.debruijn.register}`, and rename a `:derivation-mismatch`
   raised during the fallback to `:pairing-mismatch`. The B6
   pairing-datom arities (4 and 5) are byte-identical.
6. `refusal-reasons` extended with `:undeclared-free`,
   `:module-name-mismatch`, `:derivation-mismatch`,
   `:unverified-derivation` (the section 4.3 table).

## Verification

- Baseline before any edit (this worktree at master 9428c3d2):
  JVM 2,138 tests / 181,890 assertions, 0 failures, 0 errors.
  Note: the task said 181,898; this tree measures 181,890 (8 fewer).
- After (full suite, run twice, `clojure -M:test` and the baseline's
  own `clojure -M:test:jvm` form, identical results):
  JVM 2,152 tests / 181,991 assertions, 0 failures, 0 errors.
  Delta: +14 tests (all mine), +101 assertions. My file alone is 77
  (14 tests, run in isolation, green). The remaining 24 sit in the old
  suite: no old test was edited, the old linker suite alone still runs
  green (58 tests / 414 assertions), and the old suite's assertion
  count demonstrably moves between sessions (the orchestrator's own
  baseline claim differs from this tree's measurement by 8); the tests
  with random inputs (debruijn_test, ucf_test) are untouched by this
  slice. Zero failures, zero errors, reproducibly.
- TDD: the new test file was written first and run red
  (`No such var: linker/stack-lowering-profile` and friends) before
  any implementation, then driven green.
- kondo: 0 errors, 0 warnings on both changed files.
- cljstyle: both files pass after `cljstyle fix` (indentation only).
- Every line added or edited is pure ASCII, <= 80 columns
  (checked mechanically).

## Files changed

- `src/cljc/yin/vm/linker.cljc`: +650/-29 (the four removed blocks are
  the ns docstring fragment, the old `refusal-reasons` set, the old
  `discharge` cond, and the two B6 fallback defns, each replaced by an
  extension that preserves the old behavior verbatim).
- `test/yin/vm/linker_manifest_test.cljc`: new, 14 deftests,
  77 assertions.

## Tests added (all in linker_manifest_test.cljc)

- manifests-and-records-verify-as-content
- another-schema-version-is-a-schema-defect (criterion 22)
- a-derivation-without-a-contract-entry-is-a-defect (criterion 22)
- a-manifest-declaring-the-definition-operator-is-reserved-name (R)
- a-manifest-declaring-another-name-is-module-name-mismatch
- records-leading-from-another-tree-are-derivation-mismatch
  (the three swapped-image tests, SEM/H/R, under BOTH policies)
- a-record-claiming-another-tree-s-image-is-derivation-mismatch
  (the fourth swapped-image test; :verifying refuses, :trusted
  installs with :trust :composition)
- an-unimplemented-profile-is-unverified-derivation-when-verifying
  (criterion 18)
- one-four-way-manifest-links-through-all-four-kernels (criterion 22;
  both policies, each image run on its own kernel, B0-equal results)
- the-manifest-index-resolves-the-images-it-names (index merge)
- a-free-name-the-manifest-never-declares-is-undeclared-free
- declared-obligations-carry-their-declaration
- declared-obligations-discharge-by-profile-and-manifest
- the-fallbacks-read-derivations-from-the-manifest

## Done / left

Done: everything in the task's S4 list (records, schema validation,
contract-before-content, name check, both policies, index merge,
fallbacks, the S4 test group). Left for M4's other slices, untouched
here: require lowering and the install child (S3), engine/module
integration (`link-module`), by-name resolution wiring over the
authority policy in the stepped core (a by-name request is still
`:absent` in `root-address`; a composition resolves the name itself
and calls `link-manifest` by address, which is how the S4 tests run),
the UCF amendments (S5), and M5.

## Deviations

1. `link-manifest` is a new public export not in section 10's list.
   The listed `manifest-format`/`record-format` are exported as
   specified; the manifest flow itself needs a caller-facing entry
   point, S3's engine-side `link-module` being out of my scope.
2. Fourth swapped-image test interpretation: the manifest's tree is
   T(worked), the record claims input T and output = another tree's
   image, so only `:verifying`'s recomputation catches it ("the
   :yin.ast/code tree is not the manifest's [derivations'] tree"). If
   the orchestrator meant a different construction, the check the
   test exercises (`relowered` vs `:yin.ledger/output`) is the one
   section 8.1 defines either way.
3. The stack and register profile maps ("b2-stack-lowering",
   "r1-register-lowering") are pinned in linker.cljc: the doc pins
   only the semantic profile (in ledger.cljc) and says the other two
   are "each pinned by its own :yin.ledger/profile map" without a
   value; the names follow the doc's words ("the B2 stack lowering",
   "the R1 register lowering").
4. The two fallbacks gained 3-arity manifest variants alongside the
   unchanged B6 arities (arity overloading), since the B6 tests must
   keep passing unchanged.
5. `relowered` (H/R re-lowering) runs over the datom lane with the
   row-lane tree reconstructed first; probe-verified to reproduce the
   datom lane's identities exactly.

## Unrun checks

- Node and Dart lanes (the orchestrator runs both). The new code
  avoids the known CLJD traps: no `qualified-symbol?`,
  `#?(:cljd ... :clj ...)` order respected in every reader
  conditional added, no private-var cross-namespace references,
  portable predicates only.
