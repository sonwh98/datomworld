I’ll check the three corrections in the current diff and the fix-round report, then give the final sign-off with the D10b scope amendment. I won’t edit files or run suites.


Completed-GMT: 2026-10-06 00:36:31 GMT
Completed-Local: 2026-10-06 07:36:31 +0700

CHANGES

1. **Make the float-epoch regression portable.** In `handoff_v1_test.cljc`, replace the new `1.0` epoch with `(jing.cbor/float64 1)`. On JavaScript, bare `1.0` is integer-kind content under `host-integer?`; the current row supplies a valid epoch and incorrectly expects refusal. The carrier pins the intended float-kind rejection on every host.

2. **Scope issue reconstruction to version 1.** The new reduction stamps every restored put, and machine construction unconditionally installs `:yin.k/issued`, including version-0 forks. Guard both changes with `version-one?`; preserve the existing version-0 entries and receiver behavior.

3. **Add the requested issue-order and contamination regressions.** The current test diff contains no assertions for `:yin.k/issue`, `:yin.k/issued`, or `:yin.k/closes`. Pin restored puts in wait order, the rebuilt next counter, removal of receiver-local close/issue state, and restored-put → new close → new put ordering, including an install child.

The private child-restoration helper closes the public-option bypass, and the new equal-length prefix cases address the missing density coverage. D10b’s scope amendment stands: D10 covers semantic-kernel restoration; D10b owns successful four-kernel lift/lower generalization as a prerequisite to D16, without blocking D11/D12. The fix-round report establishes JVM results only; it does not resolve the JavaScript test defect above. No files were edited and no suites were run.