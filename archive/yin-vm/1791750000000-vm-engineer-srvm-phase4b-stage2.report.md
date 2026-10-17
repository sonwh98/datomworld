Completed-GMT: 2026-10-10 18:55:59 GMT
Completed-Local: 2026-10-11 01:55:59 Asia/Bangkok


# Phase 4b Stage 2

Implementation and the requested targeted JVM/Node gates are complete. The full requested JVM regression group has TWO failures in one scanner-specific test because the frozen fixture prescription conflicts with that test. Dart is NOT RUN. Acceptance item 26 therefore is FAIL/incomplete; this report does not claim the requested all-PASS result. No design was amended, no commit or staging occurred, and Stage 1 assertions were retained.

## Changed files

New:
- `test/yin/vm/semantic_register/link_test.cljc` (`yin.vm.semantic-register.link-test`).
- This report, `collab/1791750000000-vm-engineer-srvm-phase4b-stage2.report.md`.

Modified slice-owned implementation/tests:
- `src/cljc/yin/vm/semantic_register.cljc`: FFI arm, retained-request and response restore, require builder, seven additional module-kernel methods, removal of both deferred-arm refusals. All nine protocol methods now exist.
- `test/yin/vm/semantic_register/effects_test.cljc`: seven new state/profile tests, all 13 current semantic-FFI template deftests respelled, and the distinct blocked diagnostic in `bounded-run`.
- `test/yin/vm/semantic_register/parity_test.cljc`: all corpus rows admitted, the five continuation programs, bounded continuation outcomes, FFI and stream run parity.
- `test/yin/vm/semantic_register/walker_trace_test.cljc`: all corpora plus five continuation programs traced, FFI production classification, arity-two trace. All Stage 1 pins retained.
- `test/yin/vm/semantic_register/vm_test.cljc`: deleted `stage2-deferred-arms`.

The approved existing-suite exception consists of exactly four files:
- `test/yin/vm/continuation_invoke_test.cljc`: additive register runner, bounded at 10,000 steps; all existing runners/assertions untouched.
- `test/yin/vm/ffi_test.cljc`: additive register VM kind and require.
- `test/yin/vm/rule_r_test.cljc`: additive register backend/require and the specifically approved parked-definition backend selection.
- `test/yin/vm/linker_require_test.cljc`: additive register backend/requires, with the specified `(constantly [])` obligation fixture and its required comment. Existing entries/assertions/other selections untouched.

No phase-3 source, phase-3 test, corpus, stack evaluator, walker, engine, FFI, linker, module, values, UCF, or design file changed. `git diff --quiet HEAD -- src/cljc/yin/vm/semantic_register test/yin/vm/semantic_register/corpus.cljc test/yin/vm/semantic_register/code_test.cljc test/yin/vm/semantic_register/linearize_test.cljc test/yin/vm/semantic_register/analysis_test.cljc test/yin/vm/semantic_register/load_paths_test.cljc` exited 0.

## Implementation evidence and templates

- The FFI arm follows `semantic.cljc:466-518` (template ranges specified by evaluator §1.10): evaluate explicit argument registers, check the call pair before parking, pin arguments, materialize, park the act payload under the call id, append one encoded request, then build a reader or retained writer with cleared control/K and blocked flags.
- Restore follows `semantic.cljc:135-181` and evaluator §2.2: retained requests re-park FIRST without decoding/delivery/removal; ordinary responses remove parked bookkeeping locally, decode/check correlation, then deliver through the existing rd/return arms. Carried response routes are preserved.
- The module protocol follows `semantic.cljc:972-1058` and evaluator §3.3. The interim format is `:yin.semantic-register/code`, contract `v4`. Named markers match a `:closure` tuple's params at position 2 and entry at position 3. Composition, store-of encoding/decoding, ownership, origin-image checks, GC, and attachment semantics mirror the template. Identity uses `jing/segment-key`, the same address used by the admitted image.
- The require builder preserves the act or tail payload including the complete delivery record. A parked child in the new link fixture makes the intermediate `:install` phase observable instead of allowing install to finish before inspection. Its parent resumes with `[nil 42 2]`; child and parent register windows stay separate.
- Profile refusal now has a concrete `:pure` callable returning `:vm/store-put`; the test asserts `:yin.k/undeclared-effect`, effect name, and empty declared effect set.

## First-run numbers and findings

The lifted counts were confirmed on their first run: B0 26, register corpus 32, definition programs 5, and continuation programs 5. `traces-align` covers 68 rows (26 + 32 + 5 + 5); arity-two is checked separately. The existing six-value-event lambda pin and all Stage 1 trace-only pins remain green.

The FFI nested program's reader window is exactly `{1 vector 2 nil}`, destination rd is 3, and its composite id is `[:caller :parked-0]`; these expectations matched on the first execution of `ffi-sent`. The retained writer/reader preserve that window and delivery record, and decoding writes exactly the saved window plus rd -> 7.

An implementer-derived expectation in the FIRST link fixture run was wrong: I guessed require rd 2, but the actual projection uses rd 5 (the enclosing vector operand has rd 3; the sequencing call inside it has rd 3 and its require operand has rd 5). The test now pins rd 5. This was my new test assumption, not a design-specified number. That run also found the simple child completed installation during one wait-set sweep, leaving no `:install` entry to inspect; the fixture was strengthened with a child stream read, then explicitly woken. The design was not changed.

The specification describes fourteen bridge rows in `semantic_ffi_test.cljc:65-340`; the current file contains 13 `deftest`s (with multiple subcases). All 13 were respelled, preserving their programs/subcases, with register loaders and portable distinct protocol arguments. This count is reported explicitly rather than inventing a fourteenth template test. The separate new tests add the register payload, retained-route, decoder, pair-order, and profile gates.

## Verification commands and exact outcomes

Commands used literal namespace flags/lists, ran in the authorized worktree, and never used shell backgrounding. No `bb test`, G2, or G3 was run; the user assigns those to the orchestrator.

Final JVM target group:

```sh
clojure -M:test -n yin.vm.semantic-register.code-test -n yin.vm.semantic-register.linearize-test -n yin.vm.semantic-register.analysis-test -n yin.vm.semantic-register.load-paths-test -n yin.vm.semantic-register.vm-test -n yin.vm.semantic-register.walker-trace-test -n yin.vm.semantic-register.parity-test -n yin.vm.semantic-register.effects-test -n yin.vm.semantic-register.link-test
```

PASS: 109 tests / 1946 assertions, 0 failures / 0 errors. All nine namespaces appeared. Earlier target runs before adding all bridge templates passed at 96 / 1878. Final effects G0 alone passed at 41 tests / 232 assertions. Link G0 passed at 4 / 41, then 5 / 52 before its walker parity row; the final target group includes all six link tests.

Phase 3 independently:

```sh
clojure -M:test -n yin.vm.semantic-register.code-test -n yin.vm.semantic-register.linearize-test -n yin.vm.semantic-register.analysis-test -n yin.vm.semantic-register.load-paths-test
```

PASS, unchanged: 33 tests / 1133 assertions, 0 failures / 0 errors.

Final existing-suite/regression group:

```sh
clojure -M:test -n yin.vm.continuation-invoke-test -n yin.vm.ffi-test -n yin.vm.rule-r-test -n yin.vm.linker-require-test -n yin.vm.semantic-test -n yin.vm.semantic-engine-test -n yin.vm.semantic-ffi-test -n yin.vm.parity-test
```

FAIL, exit 1: 122 tests / 1370 assertions, 2 failures / 0 errors. All eight namespaces ran. The ONLY failing test is `a-body-read-applied-before-its-definition-keeps-its-obligation-test`, on the new register fixture backend:

```text
expected: (some #(= 'y (:name %)) retained)
actual: no matching element in []
expected: (= :unresolved-free (:reason (refusal-of #(run-with vm/primitives))))
actual: (not (= :unresolved-free :install-error))
```

The same group had the same 122 / 1370 and two failures before final formatting/bounding. All listed continuation, composite FFI id, parked definition, require/install, module store/context, install-refusal, and pre-existing semantic regression rows pass. This is NOT an all-green regression claim.

Node compile:

```sh
clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^yin\\.vm\\.semantic-register\\.(code-test|linearize-test|analysis-test|load-paths-test|vm-test|walker-trace-test|parity-test|effects-test|link-test)$" :output-to "out/test.js" :autorun false}'
node out/test.js
```

PASS: final compile 155 files / 7 compiled / 0 warnings; execution 114 tests / 2006 assertions, 0 failures / 0 errors. All nine requested namespaces appeared, explicitly including `yin.vm.semantic-register.effects-test` and `yin.vm.semantic-register.link-test`. Dependency suite `yin.vm.parity-test` also ran. Earlier Node target pass before all bridge rows: 101 / 1938. Generated untracked `out/test.js` was removed after verification.

Checks (all ten changed source/test paths):

```sh
clj -M:kondo --lint src/cljc/yin/vm/semantic_register.cljc test/yin/vm/semantic_register/effects_test.cljc test/yin/vm/semantic_register/link_test.cljc test/yin/vm/semantic_register/vm_test.cljc test/yin/vm/semantic_register/parity_test.cljc test/yin/vm/semantic_register/walker_trace_test.cljc test/yin/vm/continuation_invoke_test.cljc test/yin/vm/ffi_test.cljc test/yin/vm/rule_r_test.cljc test/yin/vm/linker_require_test.cljc
cljstyle check src/cljc/yin/vm/semantic_register.cljc test/yin/vm/semantic_register/effects_test.cljc test/yin/vm/semantic_register/link_test.cljc test/yin/vm/semantic_register/vm_test.cljc test/yin/vm/semantic_register/parity_test.cljc test/yin/vm/semantic_register/walker_trace_test.cljc test/yin/vm/continuation_invoke_test.cljc test/yin/vm/ffi_test.cljc test/yin/vm/rule_r_test.cljc test/yin/vm/linker_require_test.cljc
```

PASS: kondo exit 0, 0 errors / 0 warnings (informational mixed-alias/referral messages only); cljstyle exit 0. Formatting was corrected in new/changed code before final checks; the four old backend entries/assertions were not reformatted. Python trailing-whitespace scan: zero offending lines across ten paths.

Dart: **NOT RUN**, attempted exactly:

```sh
bb src/dev/cljd_agg.clj --only yin.vm.semantic-register.code-test,yin.vm.semantic-register.linearize-test,yin.vm.semantic-register.analysis-test,yin.vm.semantic-register.load-paths-test,yin.vm.semantic-register.vm-test,yin.vm.semantic-register.walker-trace-test,yin.vm.semantic-register.parity-test,yin.vm.semantic-register.effects-test,yin.vm.semantic-register.link-test
```

Exit 1 before tests, while compiling. The lane stopped on the sandbox denial and was not retried or escalated. Exact errors:

```text
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 71: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.stamp.tmp.24939: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 78: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.realm: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 71: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.stamp.tmp.24962: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 78: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.realm: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 71: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.stamp.tmp.24986: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 78: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.realm: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 71: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.stamp.tmp.25009: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 78: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.realm: Operation not permitted
Execution error at cljd.compiler/mk-live-analyzer-info (compiler.cljc:214).
EOF while reading

Full report at:
/var/folders/n0/xl3_1xkn0yd5trpq843l81840000gn/T/clojure-5210328121365379831.edn
Exception in thread "Thread-0" java.lang.RuntimeException: Operation not permitted (sysctl failed)
cljd compile failed, exit 1
```

No Dart pass or test count is claimed. The attempt preceded the final added bridge-template rows; no Dart version of the final patch was executed.

## Acceptance list (evaluator §5.2)

Stage 1 items cite their existing tests; they were rerun without weakening their assertions. Stage 2 items cite the new/extended rows.

| Item | Status | Requirement / evidence |
| --- | --- | --- |
| 1 | PASS | Inline stream operations: effects-test/non-parking-stream-ops; sealed references and nil close. |
| 2 | PASS | Blocked read act shape: effects-test/blocked-read-entry-shape; exact payload, EDN, nil control/K. |
| 3 | PASS | Saved window membership: effects-test/saved-window-membership; exact window includes nil key; save-all/save-none mutants fail. |
| 4 | PASS | Wake delivery: effects-test/wake-delivers-to-rd; exact window and final value; wrong-rd/extra-write mutants fail. |
| 5 | PASS | Retained stream put: effects-test/blocked-put; full transport, datom retained, window and wake result. |
| 6 | PASS | Terminal wake: effects-test/terminal-wake-raises; both immediate and resumed terminal errors. |
| 7 | PASS | Tail effect delivery: effects-test/tail-effect-returns-through-k; exact tail key set, both K cases, nil blocked control/K. |
| 8 | PASS | Tail primitive pop: effects-test/tail-primitive-pops-frame; saved non-empty caller window and final value. |
| 9 | PASS | Park/resume: effects-test/park-resume; both parked states cleared; record/EDN, delivery and missing id. |
| 10 | PASS | Resume return mode: effects-test/resume-return-mode; frame and empty-K halt. |
| 11 | PASS | Capture payload: effects-test/reified-shape; exact payload and EDN. |
| 12 | PASS | Abortive continuation values: all five effects rows, walker-compared environment/trace row, parity continuation rows and fifth continuation-invoke runner; 9, 8, [30 3], [1 :written], both arity errors. |
| 13 | PASS | Ownership refusal: effects-test/foreign-continuation-refused. |
| 14 | PASS | FFI sent: effects-test/ffi-sent and bridge-manual-bridge-step-test; composite id, payload window/rd, removed writer fields, decoded exact window, empty parked; fifth ffi-test kind. |
| 15 | PASS | FFI retained: effects-test/ffi-retained and bridge retained-request subcases; identical request, re-park without guest write, response completion; guest-delivery mutant fails 18 assertions. |
| 16 | PASS | Carried response route: effects-test/retained-response-route, default and custom routes; route-ignored mutant fails. |
| 17 | PASS | FFI error: effects-test/ffi-error-raises and bridge-error-responses-surface-as-errors-test; decode-omitted mutant fails. No cleanup-on-throw promise. |
| 18 | PASS | Counter interleaving: effects-test/store-and-gensym-test; original Stage 1 values/counter retained. |
| 19 | PASS | Require/install: fifth backend a-require-links-installs-and-resumes-with-the-module-test; link-test/require-installs-and-preserves-delivery and walker-link-parity; marker, waits/installs empty, 42. |
| 20 | PASS | Tail require: link-test/tail-require; empty K yields foo, frame case yields [nil foo 2], no own window/env/pc, final env without store-of. |
| 21 | PASS | Module stores: fifth backend a-module-closure-reads-and-writes-its-own-store-test, a-module-closure-calling-another-writes-each-store-test, input-after-a-tail-applied-module-closure-is-the-tasks-own-test; link-test/image-and-store-context. |
| 22 | PASS | Install refusals: fifth backend an-install-refuses-a-missing-export-and-a-loader-defect-test; link-test/install-refusals additionally distinguishes missing export, malformed/v3 vectors at loading and link refusal. Public stamp gates retained in vm-test. |
| 23 | PASS | Marker refusal: link-test/marker-checks; mismatched params, entry, format, detached origin, unrooted body; three marker mutants fail. |
| 24 | PASS | Window GC root: effects-test/window-is-a-gc-root; omitted-window mutant fails. |
| 25 | PASS | Full trace and parity eligibility: 26 B0, 32 register-corpus, 5 definitions, 5 continuation programs; 68 aligned traces plus pinned park/continuation/resume/tail/sentinel rows. FFI run, stream run and linked walker parity included. |
| 26 | FAIL | Verification gates: FAIL/incomplete: lint/style/whitespace and requested JVM/Node target gates pass. Regression group has the two scanner-fixture failures; Dart NOT RUN; broad bb test:sub/G2/G3 gates not run here, per orchestration scope. |

## Mutation evidence

Every mutation was applied to `semantic_register.cljc` alone, one at a time. Each run used exactly:

```sh
clojure -M:test -n yin.vm.semantic-register.effects-test -n yin.vm.semantic-register.link-test -n yin.vm.semantic-register.vm-test
```

A byte backup of the implemented Stage 2 evaluator was restored in `finally` after EVERY run and byte equality asserted. This is restoration to the implementation baseline, not to HEAD (the intended Stage 2 source diff remains). No mutation remains. Final evaluator SHA-256: `7fe2bb0c0984d44c4f1efc6bf3d1c365a023b07dfd59efc5bf91f04e35ba28a2`. The first mutation group restored hash `34d9242abb4f44f4b805747ef3b6b068ff7281a224162adbc99758b4c08677a3`; a subsequent comment correction removed the obsolete integer-opcode `(4)` wording from the ported closure-check comment before the final two mutations. No executable source changed after that group.

22 of 23 mutations fail. The one survivor swaps only decoder/table-removal local bindings and is observationally equivalent under the specified non-recoverable throw contract; see findings below. Counts vary because the walker-link test and subsequently the complete bridge-template rows were added during this verification turn; every table entry records the exact tests actually run for it. Added rows did not change the original mutation-gating assertions.

| Mutation | Result / exact counts | Named failing tests | Seconds |
| --- | --- | --- | --- |
| wrong-rd | KILLED; exit 1; Ran 50 tests containing 361 assertions.; 16 failures, 1 errors. | blocked-put, continuation-restores-captured-environment, escape-discards-pending-frames-test, ffi-response-validation, ffi-retained, ffi-sent, park-resume, re-entry-after-return-test, restore-delivery, store-not-rolled-back-test, stream-outcomes-and-edn-wake, tail-position-invocation-test, wake-delivers-to-rd | 6.71 |
| save-too-much | KILLED; exit 1; Ran 50 tests containing 368 assertions.; 8 failures, 0 errors. | blocked-put, blocked-read-entry-shape, ffi-retained, ffi-sent, saved-window-membership | 6.49 |
| save-too-little | KILLED; exit 1; Ran 50 tests containing 339 assertions.; 10 failures, 6 errors. | continuation-restores-captured-environment, escape-discards-pending-frames-test, ffi-retained, ffi-sent, park-resume, require-installs-and-preserves-delivery, saved-window-membership, store-not-rolled-back-test, tail-position-invocation-test, wake-delivers-to-rd | 5.87 |
| tail-restores-window | KILLED; exit 1; Ran 50 tests containing 368 assertions.; 11 failures, 0 errors. | tail-effect-returns-through-k, tail-require | 5.79 |
| deliver-twice | KILLED; exit 1; Ran 50 tests containing 368 assertions.; 5 failures, 0 errors. | blocked-put, ffi-response-validation, ffi-sent, restore-delivery, wake-delivers-to-rd | 5.81 |
| invoke-keeps-K | KILLED; exit 1; Ran 50 tests containing 368 assertions.; 4 failures, 0 errors. | continuation-restores-captured-environment, re-entry-after-return-test | 5.79 |
| window-not-root | KILLED; exit 1; Ran 50 tests containing 368 assertions.; 1 failures, 0 errors. | window-is-a-gc-root | 5.80 |
| retained-delivers | KILLED; exit 1; Ran 50 tests containing 342 assertions.; 0 failures, 2 errors. | ffi-retained, retained-response-route | 5.96 |
| link-loses-delivery | KILLED; exit 1; Ran 50 tests containing 347 assertions.; 0 failures, 2 errors. | require-installs-and-preserves-delivery, tail-require | 5.97 |
| call-id-not-removed | KILLED; exit 1; Ran 50 tests containing 368 assertions.; 3 failures, 0 errors. | ffi-response-validation, ffi-retained, ffi-sent | 6.22 |
| decode-before-retained | KILLED; exit 1; Ran 50 tests containing 342 assertions.; 0 failures, 2 errors. | ffi-retained, retained-response-route | 6.18 |
| decode-omitted | KILLED; exit 1; Ran 50 tests containing 368 assertions.; 4 failures, 3 errors. | ffi-error-raises, ffi-response-validation, ffi-retained, ffi-sent | 6.29 |
| carried-route-ignored | KILLED; exit 1; Ran 51 tests containing 381 assertions.; 2 failures, 0 errors. | retained-response-route | 6.40 |
| sent-reader-loses-window | KILLED; exit 1; Ran 51 tests containing 381 assertions.; 3 failures, 1 errors. | ffi-sent | 6.33 |
| install-loader-validation-bypassed | KILLED; exit 1; Ran 51 tests containing 353 assertions.; 6 failures, 3 errors. | install-refusals, require-installs-and-preserves-delivery, tail-require, walker-link-parity | 6.11 |
| marker-params-not-checked | KILLED; exit 1; Ran 51 tests containing 381 assertions.; 1 failures, 0 errors. | marker-checks | 6.07 |
| marker-entry-not-checked | KILLED; exit 1; Ran 51 tests containing 381 assertions.; 1 failures, 0 errors. | marker-checks | 6.42 |
| marker-format-not-checked | KILLED; exit 1; Ran 51 tests containing 381 assertions.; 1 failures, 0 errors. | marker-checks | 6.59 |
| M-P | KILLED; exit 1; Ran 51 tests containing 381 assertions.; 1 failures, 0 errors. | declared-profile-refusal | 6.42 |
| decode-remove-order-swapped | SURVIVES (equivalent); exit 0; Ran 51 tests containing 381 assertions.; 0 failures, 0 errors. |  | 5.89 |
| retained-writes-guest | KILLED; exit 1; Ran 51 tests containing 381 assertions.; 18 failures, 0 errors. | ffi-retained, retained-response-route | 6.52 |
| store-context-omitted | KILLED; exit 1; Ran 64 tests containing 449 assertions.; 2 failures, 0 errors. | image-and-store-context | 6.16 |
| image-identity-not-verified | KILLED; exit 1; Ran 64 tests containing 449 assertions.; 1 failures, 0 errors. | image-and-store-context | 6.37 |

Mutations and assertion meanings:
- `wrong-rd` writes 999; `deliver-twice` writes the destination AND 999. Exact window assertions fail, not merely final values.
- `save-too-much` retains all W; `save-too-little` retains {}. Literal membership and nil-key gates fail.
- `tail-restores-window` uses act builders for tail effects. The tail entry key set/delivery assertions fail, including tail require.
- `invoke-keeps-K` retains the invoker K. `re-entry-after-return-test` and the environment row fail.
- `window-not-root` removes W from GC roots. The sole-reference cell no longer survives.
- `retained-writes-guest` replaces step 1's blocked re-park with actual rd delivery of the encoded request. `ffi-retained` fails blocked/nil-control/unchanged-window/reader-payload assertions; the guest slot contains the request. This directly tests delivery at the retained-request phase.
- `retained-delivers` bypasses the retained branch, and `decode-before-retained` decodes before the branch. Both hit malformed-envelope errors on request data, demonstrating the required phase ordering.
- `link-loses-delivery` drops :deliver in the require builder. The install path cannot restore and fails the named link tests.
- `call-id-not-removed` omits parked removal; successful response tests find the stale parked entry.
- `decode-omitted` bypasses call-result; decoder/refusal/final-value tests fail. `carried-route-ignored` fails the exact custom route. `sent-reader-loses-window` fails the payload and exact window assertions.
- `install-loader-validation-bypassed` replaces the spawn image with a valid nil program, bypassing the supplied defective image. The loading refusal assertions fail (malformed/v3 images no longer produce the intended refusal), along with exports/install completion tests. Export-missing and link-refusal admission are engine-owned and remain untouched; no unauthorized engine mutation was used.
- Three marker mutants omit params equality, entry equality, or format equality. `marker-checks` fails each refusal assertion.
- M-P drops check-callee-effect!. `declared-profile-refusal` fails its qualified refusal-data assertion; the effect executes instead.
- `store-context-omitted` omits store-of from the lifted marker, failing marker context and lower/lift equality. `image-identity-not-verified` answers true for every image, failing the mismatched-image assertion.

## Ambiguities / conflicts / unresolved work

1. **Frozen empty-obligation fixture versus existing scanner test.** Evaluator §5.2 explicitly prescribes `(constantly [])` for the new backend until phase 5, and the approved exception permits additive backend registration with existing assertions unchanged. But `linker_require_test.cljc`'s scanner-specific `a-body-read-applied-before-its-definition-keeps-its-obligation-test` iterates every backend and requires a retained y obligation and a scanner-derived unresolved-free refusal. With [] the first fails and the second reaches installation, where y is unresolved and the child refuses as install-error. I kept the specified fixture and untouched assertions (smallest authorized reading). An async clarification requested authorization to exclude ONLY this new install-mechanics fixture from that scanner-specific test; no response/authorization arrived before this report. No such selection edit was made. This is the two-failure regression result, not a source defect masked as a pass.
2. **Removal/decode local ordering is unobservable.** The swapped-local-bindings mutant survives. Evaluator §2.2 explicitly explains that decode throws return no modified machine, leaving the caller's original parked table intact; ffi/call-result has no machine parameter. On success both orders produce the same immutable base and value. The code retains remove THEN decode by static reading, but the suite cannot honestly claim an observable gate for that relative order. Omitted removal and decoding before retained-request detection ARE separately killed.
3. **Template count wording.** The current semantic-FFI template has 13 deftests, not the design's fourteen wording. All are ported; additional seven register/profile tests cover the Stage 2 acceptance gates. No design edit.
4. **Verification incomplete.** Dart cache writes are sandbox-blocked. The orchestrator must run the final Dart patch and its broader G2/G3 gates. No tri-host all-green claim is made.

## §5.3 carryovers

1. Restore steps 1-2 are now implemented and gated, with the equivalent ordering distinction explicitly recorded above.
2. Pre-state continuation/resume destinations and their pinned traces remain; park-then-halt still pins the single park event. FFI trace classification now exists; blocked instructions emit no event.
3. Engine active predicate and scheduler-round composition remain unchanged from reviewed Stage 1.
4. Pure-arm materializations remain removed, matching §1.8. The harmless double materialization in the host-fn effect path is retained (optional cleanup was not required).
5. No gate was added for the equivalent saved-window subtraction at a call frame/capture.
6. Main-level tail primitive trace remains.
7. Shared return-to and its non-empty saved-window tail-host gate remain.
8. The walker-compared `(2 1)` caller-environment row remains, as does round-2's `(7 1)` abortive environment row.
9. The `[5 7 8 9 6]` arm order is a static golden read, not claimed as observed runtime order. Nil source/register distinction and per-instruction image lookup notes remain; no unrequested optimization was made. Stage 1 already builds a call frame only when not tail.

No Stage 1 work was redone or weakened. Remaining work: owner ruling on the narrowly identified scanner fixture selection, final Dart and orchestrator G2/G3 verification. The implementation is reviewable but this task is not reported fully green.
