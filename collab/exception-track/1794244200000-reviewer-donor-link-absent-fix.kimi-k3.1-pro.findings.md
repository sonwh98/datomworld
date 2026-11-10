• Completed-GMT: 2026-11-09 17:22:38 GMT
  Completed-Local: 2026-11-10 00:22:38 +07

  Read-only review completed; no tests were run. I could not write the findings file because this exploration role is read-only. The ready-to-save contents for `collab/exception-track/1794244200000-reviewer-donor-link-absent-fix.kimi-k3.1-pro.findings.md` follow.

  # Adversarial review: donor-link-absent fix

  Scope: uncommitted `test/yang/call/conformance.clj` change at `/Users/sto/workspace/datomworld-xlink`, detached at `a5a3ac0e`. Static review only.

  ## 1. Mechanism correctness — accept

  The attribution is correct for Clojure-donor rows:

  - `publish-donor!` constructs wrapper applications around the already-C1-finalized Clojure unit (`test/yang/call/conformance.clj:203-218`; `src/cljc/yang/clojure.cljc:3222-3253`).
  - `module-tree`, `require-node`, and `def!` create real application nodes (`src/cljc/yang/core/module.cljc:25-34`; `src/cljc/yang/core/prelude.cljc:53-68`; `src/cljc/yang/core/uast.cljc:40-56`).
  - Any marker activates the C1 claim (`src/cljc/yin/vm/linker/publish.cljc:178-215`). Incomplete application coverage refuses publication at `publish.cljc:302-306`; successful results receive `:address` only at `publish.cljc:374-381`.
  - `publish-donor!` extracts that absent address as nil (`test/yang/call/conformance.clj:218-222`), and `link-source` installs it in the name environment (`conformance.clj:275-283`).
  - The non-DHT interpreter uses `if-let` on the lookup, so a present-but-nil mapping produces exactly `{:status :refused :reason :absent :name ...}` (`src/cljc/yin/vm/linker/interpreter.cljc:615-628`).

  `git show --stat b825498e` also confirms that this commit introduced the C1 publication validation in `publish.cljc`. No hand-waving remains in this specific causal chain.

  ## 2. Fix semantics — accept

  The annotation does not invent executable call sites. The synthetic applications already exist and already have canonical occurrence identities; the change only adds metadata required for those occurrences.

  - C1 identity is `[origin root-address path]`, not source-callee identity (`src/cljc/yang/core/uast.cljc:114-127`; `src/cljc/yang/clojure.cljc:92-113`).
  - `annotate-call-sites` walks existing grammar slots and only associates `:yang/call-site 1` on existing applications (`src/cljc/yang/core/uast.cljc:129-151`).
  - Canonical projection excludes `:yang/*` keys, so roots and executable rows remain unchanged (`src/cljc/yin/vm.cljc:1113-1123`).
  - Publication assigns one admission origin and joins those markers to existing canonical occurrences (`src/cljc/yin/vm/linker/publish.cljc:186-215,267-284`).

  This matches production finalization in `yang.core.call` (`src/cljc/yang/core/call.cljc:168-175`) and `yang.clojure.prelude` (`src/cljc/yang/clojure/prelude.cljc:108-112`). Re-annotating the already-marked inner Clojure unit is explicitly idempotent.

  ## 3. Coverage — defect, P2

  The Clojure-donor mechanism covers the 8 Clojure→Python scenarios and the 6 Python→PHP scenarios because both directions obtain the same delayed Clojure donor through `published-donors` (`test/yang/call/exception_legs.clj:206-219,239-258`).

  The claimed PHP-provenance coverage is not supported by the actual source path:

  - Every provenance frontend uses `boundary-donor`, which is explicitly `(kit/publish-donor! :python contract/boundary-source)` (`test/yang/call/exception_probes.clj:19-20,161-170,173-188`).
  - Thus PHP provenance does not require `xlang.donor.clojure`; its composition initializes only the Python donor and PHP caller runtimes (`test/yang/call/conformance.clj:225-240`; `test/yang/call/exception_probes.clj:165-188`).
  - Python lowering contains no C1 annotation, so the `cond->` leaves this donor unchanged. That is correct for Python, but means the change cannot explain or repair a Clojure-donor refusal in these fixtures.

  The supplied row accounting is also internally inconsistent: the problem statement lists seven PHP provenance identifiers while calling them six rows. Before claiming all 24 operational errors are addressed, the PHP-provenance failures need a separate causal trace or corrected grouping. The two authorized narrow-test reports do not establish this coverage.

  For non-C1 TypeScript, PHP, and Python donors, leaving `raw` untouched is semantically correct: no marker means no C1 claim (`src/cljc/yin/vm/linker/publish.cljc:114-119`).

  ## 4. Silent nil — defect, P2

  `publish-donor!` should throw immediately when publication returns `:status :refused`, preserving the complete refusal map.

  Currently every publication refusal—not just incomplete C1 coverage—is collapsed into `{:address nil :links nil}` (`test/yang/call/conformance.clj:218-222`). Execution then fails remotely as `:absent`, discarding the actual reason and diagnostic data (`src/cljc/yin/vm/linker/interpreter.cljc:617-628`). This converted a publication-contract defect into 24 misleading operational failures and will repeat for malformed trees, missing requirements, invalid closures, and other refusals.

  This is a test-fixture correctness/diagnosability defect, not a request to weaken publication validation.

  ## 5. Duplication — observation

  The local predicate is an exact copy of the publisher’s private `claims-call-sites?` (`test/yang/call/conformance.clj:190-193`; `src/cljc/yin/vm/linker/publish.cljc:178-183`). Duplication is acceptable for this minimal kit repair because no public claim-inspection API exists, and publishing every donor unconditionally would incorrectly give non-C1 frontends a C1 claim.

  A shared public predicate could prevent drift later, but should be a deliberate API decision. The publisher’s docstring additionally notes that a key inside literal data can cause a conservative false positive; the kit’s shorter docstring omits that nuance, though the behavior remains harmless because extraction sees only real syntax metadata.

  ## 6. Constraint compliance — accept

  No violation found:

  - `git status --short` shows only `M test/yang/call/conformance.clj`.
  - The diff is +12/−3 and touches no ledger or assertions.
  - HEAD remains detached at `a5a3ac0eb881766721384656a7afdd148aa1daf4`; no commit is present.
  - The author reports only the permitted single-test G0 command before and after the fix. This review did not independently execute it.

  ## 7. Regression risk — observation

  The most important currently-green coverage is `yang.cross-lang-test`, especially its Clojure-donor cases at `test/yang/cross_lang_test.clj:100-109,122-138,141-160` and subsequent Clojure source-map invocation cases. These now publish a complete C1 side table instead of being refused.

  Other affected callers:

  - `yang.python.antlr.exception-call-test`: all directed pairs use `exception-legs`; Clojure-donor direction is directly affected.
  - `yang.call.exception-contract-test`: provenance uses the unchanged Python donor.
  - `yang.python.antlr.source-call-test`: TypeScript and Python donors only, so their ASTs remain untouched.
  - `yang.call.conformance-test`: uses helper forms but does not publish donors.
  - `export-program` has no caller outside its definition/documentation in the searched tree.

  Execution-regression risk is low because canonical rows and roots are unchanged. Manifests and addresses can change because successful Clojure-donor publication now includes `:yin.module/call-sites` (`src/cljc/yin/vm/linker/publish.cljc:327-361`), but these compositions pass dynamically returned addresses rather than pinned donor addresses (`test/yang/call/conformance.clj:218-222,275-294`).

  CHANGES-REQUIRED with blocking findings 3 and 4.

