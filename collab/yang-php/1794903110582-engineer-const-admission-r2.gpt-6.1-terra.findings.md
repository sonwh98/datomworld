# Constant admission r2 — quick PHPT manifest re-pin

Branch `fix/const-admission`, base `f32a4392935f8ad455f8fd7d94e9679815619c3b`; r1 work remains uncommitted. No commits.

Compared the actual mismatch ID list in `target/const-r2/r1-yang-php-sub-clj.log` against the 95 recorded known-gap IDs. Actual has 104, removed set is empty, and the nine added IDs are:

- `Zend/tests/bug28072.phpt`
- `Zend/tests/bug53305.phpt`
- `Zend/tests/bug69755.phpt`
- `Zend/tests/bug69788.phpt`
- `Zend/tests/bug70398.phpt`
- `Zend/tests/bug72944.phpt`
- `Zend/tests/bug77738.phpt`
- `Zend/tests/bug78396.phpt`
- `Zend/tests/named_params/defaults.phpt`

The quick selection remains 271 entries: 119 pass; unsupported 57 → 48; run-fail 95 → 104. The manifest groups the nine under a new `:constant-admission-migration` known-gap feature with an explicit work-item noting that these cases were previously refused for bare global reads and now execute but mismatch for other gaps. Their precise remaining mismatch causes are not asserted by the ID-only gate output. This new category is counted in `:other`, 62 → 71; existing family counts stay unchanged. Selection titles are added to `:descriptions`. All existing family-count, exact-ID, description-ID and refusal-count guards remain unchanged. Structural EDN check: 104 distinct IDs, 104 description keys, no differences.

The r1 quick subsystem run recorded 184 tests, 5046 assertions, 3 failures, 0 errors, all three PHPT manifest guards (`target/const-r2/r1-yang-php-sub-clj.log`); the additional `DIFF` lines from probe namespaces are informational and did not count as test failures. R1's linked slow acceptance was 1 test, 90 assertions, 0 failures, 0 errors (`target/const-r1/linked-slow.log`). The `dao.stream` foundation gate failed under socket-restricted execution, as recorded in the r1 findings.

Focused r2 `clojure -M:test -v yang.php.antlr.e2e-test/phpt-selection-runs-test`: exit 0, 1 test, 6 assertions, 0 failures, 0 errors; quick selection 271 entries, 119 pass, 48 unsupported, 104 known mismatches (`target/const-r2/phpt-selection.log`). Full JVM PHP subsystem gate started afterward.

Full r2 `bb test:sub:clj yang.php`: exit 0, 29 namespaces, 184 tests, 5046 assertions, 0 failures, 0 errors, 7537 seconds (`target/const-r2/yang-php-console.log`, `target/const-r2/yang-php-sub-clj.log`). The default quick tier excludes `^:slow`; r1's separate 90-assertion linked test provides that coverage. As in r1, probe namespaces print informational `DIFF` diagnostics for pre-existing cross-unit and autoload cases; they are not test assertion failures and this gate does not certify those probe observations.
