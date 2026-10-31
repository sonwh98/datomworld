Completed-GMT: 2026-10-05 23:05:29 GMT
Completed-Local: 2026-10-06 06:05:29 Asia/Ho_Chi_Minh (+0700)
Coding-Agent: Codex (GPT-6)

D10 is implemented in the existing semantic handoff lower. The selected JVM, Node and Dart suites pass. Full restoration across all four kernels remains an acceptance limitation: the existing lift rejects the walker, stack and register machines before a body is produced. This report does not claim four-kernel restoration or execution of D11's future operation assignment.

Completion timestamps use the live clock tool, corroborated by the host clock. Those clocks disagree with the prompt's Created timestamps.

Changed files:

- `src/cljc/yin/vm/ucf/handoff.cljc`: deterministic grant acceptance before attachment; recursive protection checks; root custody restoration; retained IDs; child/result gates; receiver guest-state isolation; updated API documentation.
- `test/yin/vm/ucf/handoff_v1_test.cljc`: grant refusals and caller defects, exact counters including the bound, empty/nonempty replay prefixes, all three retained-write variants, protection/enrollment mismatches including children, kept link cursors, child initialization effects, receiver isolation, and D9 blocked/parked restoration fixtures with explicit grants. Existing reader success cases now supply grants.
- This requested findings file. No other tracked files changed. The supplied collab inputs were already untracked and were left unchanged.

Verified branch `ucf-d10-v1-lower`, HEAD `af5dc88e18963b8f9d289f700a7063f2323d6902`. No git writes occurred.

Restoration behavior:

After the existing decode, codec/version gate, custody inspection, recursive grammar validation and contract check, a blocked/parked v1 root requires explicit grant evidence. Missing evidence returns `:yin.k/awaiting-grant`; unavailable evidence/prefix returns an unavailable data refusal; contradictory grant binding or tenure returns `:yin.k/not-holder`. Refusals expose no `:vm`; the tests assert zero attachment calls.

The seven grant checks cover inspected checkpoint address, occurrence, lease/holder, arbitration transaction identity, portable exact epoch, live unexpired tenure, and matching dense replay prefix. Malformed grant/evidence/binding/tenure container shapes and invalid protection declarations raise caller defects rather than returning a runnable machine.

Every reachable task stream, recursively through install children, requires a declared class. Enrollment agrees in both directions. Retained writes require an ID exactly when their target is enrolled. Successful lower preserves the body's ID under the engine entry's `:op-id`; it does not assign or replace IDs. Link entries retain the carried cursor.

The root's `:yin.k/custody` contains the body occurrence/arbitration/counter, accepted lease/holder/epoch, supplied classes, input `{:next 0 :prefix prefix}`, and tenure `{:bound bound}`. Root and children are gated running; children have no custody map. Halted v1 roots require no grant and restore ended without custody. Receiver guest stores, module stores, parked records, installs and linked guest module registry entries do not supply checkpoint state; host module entries and composition resources remain available.

The v0 codec, exporter and fork behavior are unchanged. Existing v0 restoration and lift byte-equivalence tests pass on all three lanes. The v1 lift's inspection/validation self-check still requires no grant and has not been routed through runnable restoration. Lower performs no proposal, ledger acquisition, renewal, release or clock read.

Final verification (all commands exited 0):

| Check | Exact outcome |
| --- | --- |
| JVM selected four namespaces | 84 tests, 827 assertions, 0 failures, 0 errors |
| Node selected four namespaces | 84 tests, 827 assertions, 0 failures, 0 errors; compile 149 files, 9 compiled, 0 warnings |
| Dart selected four namespaces | 84 tests passed, 0 failed; assertion count is not printed by this runner |
| clj-kondo, both changed code/test files | 0 errors, 0 warnings |
| cljstyle check, both changed code/test files | Exit 0, no formatting differences |
| git diff --check | Exit 0, no whitespace defects |

Commands:

```sh
clj -M:test -n yin.vm.ucf.handoff-v1-test -n yin.vm.ucf.handoff-test -n yin.vm.ucf.lift-v1-test -n yin.vm.ucf.holder.export-test
clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^yin\\.vm\\.ucf\\.(handoff(-v1)?|lift-v1|holder\\.export)-test$"}'
bb src/dev/cljd_agg.clj --only yin.vm.ucf.handoff-v1-test,yin.vm.ucf.handoff-test,yin.vm.ucf.lift-v1-test,yin.vm.ucf.holder.export-test
clj -M:kondo --lint src/cljc/yin/vm/ucf/handoff.cljc test/yin/vm/ucf/handoff_v1_test.cljc
cljstyle check src/cljc/yin/vm/ucf/handoff.cljc test/yin/vm/ucf/handoff_v1_test.cljc
git diff --check
```

Final logs: `/private/tmp/d10-accepted-jvm.log`, `/private/tmp/d10-accepted-node.log`, `/private/tmp/d10-accepted-dart.log`, `/private/tmp/d10-accepted-kondo.log`. Dart emitted 14 dynamic-member compiler warnings in dependency code; its tests passed. Kondo and cljstyle were not sandbox-blocked.

Red and iteration evidence:

| Run | Tests / assertions | Failures / errors |
| --- | --- | --- |
| Initial behavior tests before implementation, tool transcript | 13 / 269 | 77 / 0 |
| `/private/tmp/d10-green.log` | 63 / 684 | 13 / 0 |
| `/private/tmp/d10-next2.log` | 16 / 285 | 3 / 0 |
| `/private/tmp/d10-final-jvm.log` | 66 / 700 | 1 / 0 |
| `/private/tmp/d10-matrix.log` | 17 / 301 | 12 / 0 |
| `/private/tmp/d10-residual-red.log` | 17 / 306 | 15 / 0 |
| `/private/tmp/d10-matrix2.log` | 17 / 310 | 18 / 0 |
| `/private/tmp/d10-green-jvm.log` | 82 / 795 | 0 / 0 |
| `/private/tmp/d10-final2-jvm.log` | 83 / 809 | 0 / 1 |
| `/private/tmp/d10-final3-jvm.log` and focused Node run | 83 / 822 | 0 / 0 |
| Caller-shape tests before their fix, `/private/tmp/d10-shape-red2.log` | 19 / 332 | 3 / 0 |
| Final JVM and Node runs | 84 / 827 each | 0 / 0 each |

The initial red batch demonstrated missing grant enforcement, custody/counter/gates, ID restoration and isolation. Subsequent tests exposed the receiver guest registry leak and unavailable-prefix handling before their fixes. Some intermediate failures were harness mistakes: a synthetic put at a next-only safepoint, an attempted nonexistent `engine/apply-ffi-result`, a nested v0 child, and treating a ring-buffer drain as destructive. These were corrected. The added synthetic `parked-module-store-cursors` restoration case raised a closure-marker mismatch; that fixture fabricates a closure at a non-lambda entry, so it was removed from the added restoration batch, without changing existing D9 assertions. The original D9 halted module-closure restoration tests remain and pass.

The initial behavior batch and caller-shape batch ran red before their source edits. Extended FFI/link, kernel-boundary and D9 coverage was added after the shared implementation; this is not a claim that every later coverage case independently went red before implementation.

Infrastructure attempts:

- `/private/tmp/d10-next.log`: test compilation failed on the nonexistent apply function; no test counts were produced.
- Initial Node compilation completed (480 files, 479 compiled, 0 warnings), but runtime could not load `@noble/hashes/blake3`; no tests ran. `npm ci` installed 69 packages without changing tracked package files.
- A Node retry used an incorrectly nested filter and started the broad suite. I stopped only my Node child, then used the correct build-level filter. The interrupted broad run is not a verification result.
- Initial Dart compilation failed on Flutter SDK cache writes and `sysctl` under the sandbox; no tests ran. Escalated reruns succeeded, first with 82 tests, then 83, and finally 84. The approvals did not require git writes.
- A `clj -M:test -v ...` attempt failed during unrelated namespace discovery on missing `yang.python.antlr.gen.Python3Lexer`; no tests ran. Namespace-filtered runs succeeded. No unrelated parser code was changed.
- Formatting checks initially reported two incorrectly formatted files, then one after additions; apply_patch corrections produced a clean final check.

Remaining limitations and incomplete acceptance:

1. Walker, stack and register sources hit the existing `:yin.k/non-portable`, `:unaddressed-segment` lift refusal. The new kernel-boundary test pins this result and proves semantic restoration; it is not four-kernel restoration coverage. Generalizing the pre-existing lift/register grammar and lower remains outstanding.
2. The exact restored counter is asserted at ordinary values and at 2^52-1. The next actual assigned ID is not exercised because D11 owns assignment; lower itself assigns none.
3. D13 must authenticate evidence, prove the checkpoint is an admitted occurrence variant, recheck tenure before scheduling/IO, and handle release after post-grant failure. Equality of supplied address fields does not prove admission. These duties remain D13's.
4. The current D9 `restored!` harness restores halted fixtures, which need no grant. Its assertions were preserved. New blocked/parked D9-source restoration coverage lives in the allowed handoff test file and supplies explicit grants; no extra test file was edited.
5. No full-repository suite, real cross-process handoff matrix, D11 write execution or D12 replay delivery was run. No claim is made for those acceptance gates.
