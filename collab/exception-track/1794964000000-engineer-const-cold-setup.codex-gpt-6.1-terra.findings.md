# Const cold setup: residual lowering refusal

Task-ID: `1794964000000-engineer-const-cold-setup`. Jupiter, worktree `/tmp/datom-const-cold`, branch `fix/const-cold-setup`; tested source HEAD `f32a4392935f8ad455f8fd7d94e9679815619c3b` (full hash obtainable as this findings commit's parent). Origin was fetched from GitHub and `f32a4392` verified ancestral to `origin/master` (`f9d88d57` at fetch). The dispatch brief was read from `origin/master`; it is absent at the tested base.

**Disposition: findings only.** A fresh, per-row published runtime/store did not clear the first affected row. The existing `run-fixture` publishes non-entry units, lowers the entry with linked profile, constructs the signed source and name environment, and constructs a fresh link pair and registry per backend. The experiment temporarily supplied `(delay (lh/make-published))` to `lh/published` per const row, so publication and subsequent fixture composition used a fresh runtime/store. No expectations, skips, dyn rows, or prelude were changed. The experimental test edit was reverted on the residual rule.

The refusal arises before linked execution: `constant-node` in `src/cljc/yang/php/antlr/lower.cljc` explicitly calls `unsupported!` for a bare global constant that is not a builtin or imported constant. Its comment states that ordinary global declarations are not statically resolved there. This explains the observed refusal independently of runtime/store composition; it does **not** establish the cause of the reported subsystem-order green or prove any state-dependent lowering behavior. A PHP lowering unit is needed to decide how to admit these reads while preserving the diagnostic contract.

Per-row fresh-JVM lowering probe, each using `lower-program` with `{:prelude :linked :path "main.php"}`:

- `:const-define-redefinition`: `Unsupported PHP construct: :constant` `{:yang.php/diagnostic :yang.php/unsupported, :rule "constant", :construct :constant, :span [53 54]}`. Also reproduced through the fixture with a newly published runtime/store.
- `:const-declare-and-read`: `Unsupported PHP construct: :constant` `{:yang.php/diagnostic :yang.php/unsupported, :rule "constant", :construct :constant, :span [41 42]}`.
- `:const-redeclare-warns`: `Unsupported PHP construct: :constant` `{:yang.php/diagnostic :yang.php/unsupported, :rule "constant", :construct :constant, :span [37 38]}`.
- `:const-undefined-error`: `Unsupported PHP construct: :constant` `{:yang.php/diagnostic :yang.php/unsupported, :rule "constant", :construct :constant, :span [17 21]}`.

Evidence and verification:

- `bb gen:antlr`: rc=0, prerequisite generation completed.
- Focused `clojure -M:test -n yang.php.antlr.include-test -v yang.php.antlr.include-test/p6-fixtures-linked-on-every-vm-test` with the experimental per-row store: interrupted after the first independent residual was established; rc=143, no Ran line. It is not a pass. Log `/tmp/const-focused.log`.
- Per-row fixture probe with fresh published runtime: first row returned the refusal above; interrupted before remaining rows to obey the residual stop rule; rc=143, no Ran line. Log `/tmp/const-probe.log`.
- Direct four-row lowering probe: rc=0 (the probe caught and printed all four exceptions); no test-runner Ran line. Log `/tmp/const-lower.log`. rc=0 here means the probe completed, not that the fixtures passed.
- The requested two complete isolated namespace runs, adjacent PHP namespace, `dao.stream` lane, and mutual-include/dyn verification were not run: the residual rule requires stopping rather than presenting a setup fix. No green gate is claimed.

Only this findings file is delivered; no source or test changes remain. The branch must not be treated as a fix or merged as one.
