Completed-GMT: 2026-10-04 07:21:22 GMT
Completed-Local: 2026-10-04 14:21:22 Asia/Ho_Chi_Minh

APPROVE-WITH-NITS

1. **LOW — test/yin/vm/ucf/handoff_test.cljc:1373.** The refusal tests assert status and attachment count, but do not pin `:yin.k/kind :incomplete-install` or the pending’s path; the renamed case also omits the name assertion. **Fix:** assert these fields for both cases and add a nested-child missing-install case. The implementation’s recursive validation is correct; this is coverage hardening.

2. **PASS — src/cljc/yin/vm/ucf/handoff.cljc:1403.** `entries` restores exactly one wait per carried frame, in vector order. The active park remains a separate record and introduces no wait. Thus this change neither loses nor duplicates carried waits, matching UCF 7.2.1 and 7.4.3. **Fix:** none.

3. **PASS — src/cljc/yin/vm/ucf/handoff.cljc:1010.** The membership check uses the pending’s name against its own body’s installs. Every export path uses the same names: `lift-pending!` checks the source installs map, `export-installs` preserves its keys, and nested children recurse through `export-task`. Validation recurses before restoration or attachment. The status, kind and path follow neighbouring refusal conventions. **Fix:** none.

4. **PASS — docs/design/yin.vm.universal-continuation-format.md:939.** **No**, an install entry with no waiter should not be refused. UCF 7.4.3 explicitly says: “Every live child travels, waited on or not.” The completeness requirement runs from pending to entry, not conversely. **Fix:** none.

5. **PASS — test/yin/vm/ucf/handoff_test.cljc:1339, :1374; docs/design/yin.vm.ucf-revisions.md:370.** Static mutation review confirms the old parked branch fails the restored-wait assertion; removing the new validation admits the stripped body and attempts attachment. These detect the intended defects. The doc accurately describes the fixes and unchanged wire. The historical “red-then-green” claim relies on the engineer’s execution evidence.

Read-only review completed; no edits, suites or executable mutations performed.
