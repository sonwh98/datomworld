Completed-GMT: 2026-10-10 18:59:07 GMT
Completed-Local: 2026-10-11 01:59:07 Asia/Bangkok


# Phase 4b Stage 2 round 2

PASS for all requested lanes/checks runnable here. JVM: 231 tests / 3313 assertions. Node: 114 tests / 2006 assertions. Both have zero failures/errors. Dart NOT RUN by me, as explicitly delegated to the orchestrator in this round. Acceptance item 26 is now PASS within that requested verification scope; no Dart or broader G2/G3 pass is claimed.

## Round-2 change and exclusion site

Modified only `test/yin/vm/linker_require_test.cljc` in this round, at `a-body-read-applied-before-its-definition-keeps-its-obligation-test` (line 612). Exactly ONE exclusion site:

```clojure
;; excluded until phase 5c supplies the register-vector scanners; install mechanics are covered by link-test
(doseq [backend (remove #{:semantic-register} (keys backends))]
  ...)
```

This replaces that test's `(keys backends)` iteration with the owner-authorized exclusion. Every existing assertion and all other backend entries/selections remain untouched. The new backend still runs in every other backend-iterating linker test, including install, context/store, refusal, correlation, and retained-request mechanics.

Searched the entire file for `retained`, `obligations`, and `scanner`, and inspected the relevant tests. Only this test invokes a backend's scanner and asserts that the resulting retained obligations contain `y`. `step-5b-runs-against-the-live-state-test` supplies its own explicit obligation records, so it retains the new backend. No additional exclusions were necessary.

No evaluator/source, other test, design, or phase-3 file changed in round 2. All Stage 2 implementation changes remain as reported previously. New artifact: this report. No git staging or commits.

## Verification

Full requested JVM regression group PLUS all nine semantic-register namespaces, foreground with literal flags:

```sh
clojure -M:test -n yin.vm.continuation-invoke-test -n yin.vm.ffi-test -n yin.vm.rule-r-test -n yin.vm.linker-require-test -n yin.vm.semantic-test -n yin.vm.semantic-engine-test -n yin.vm.semantic-ffi-test -n yin.vm.parity-test -n yin.vm.semantic-register.code-test -n yin.vm.semantic-register.linearize-test -n yin.vm.semantic-register.analysis-test -n yin.vm.semantic-register.load-paths-test -n yin.vm.semantic-register.vm-test -n yin.vm.semantic-register.walker-trace-test -n yin.vm.semantic-register.parity-test -n yin.vm.semantic-register.effects-test -n yin.vm.semantic-register.link-test
```

PASS, exit 0: 231 tests / 3313 assertions, 0 failures / 0 errors. All 17 requested namespace `Testing` lines appeared. The count is the prior 122 / 1370 regression group minus the three assertions for the excluded fixture backend, plus the 109 / 1946 semantic-register group. No assertion expression was removed or weakened; the four real-scanner backends still execute that test unchanged.

Node, same scope as the Stage 2 report:

```sh
clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^yin\\.vm\\.semantic-register\\.(code-test|linearize-test|analysis-test|load-paths-test|vm-test|walker-trace-test|parity-test|effects-test|link-test)$" :output-to "out/test.js" :autorun false}'
node out/test.js
```

PASS: compile exit 0, 155 files / 8 compiled / 0 warnings. Execution exit 0, 114 tests / 2006 assertions, 0 failures / 0 errors. All nine requested namespace `Testing` lines appeared, including effects-test and link-test; dependency `yin.vm.parity-test` also ran. The generated untracked output was removed after the run.

Checks covered every changed source/test file of the full Stage 2 patch (ten paths), not just the one round-2 edit:

```sh
clj -M:kondo --lint src/cljc/yin/vm/semantic_register.cljc test/yin/vm/semantic_register/effects_test.cljc test/yin/vm/semantic_register/link_test.cljc test/yin/vm/semantic_register/vm_test.cljc test/yin/vm/semantic_register/parity_test.cljc test/yin/vm/semantic_register/walker_trace_test.cljc test/yin/vm/continuation_invoke_test.cljc test/yin/vm/ffi_test.cljc test/yin/vm/rule_r_test.cljc test/yin/vm/linker_require_test.cljc
cljstyle check src/cljc/yin/vm/semantic_register.cljc test/yin/vm/semantic_register/effects_test.cljc test/yin/vm/semantic_register/link_test.cljc test/yin/vm/semantic_register/vm_test.cljc test/yin/vm/semantic_register/parity_test.cljc test/yin/vm/semantic_register/walker_trace_test.cljc test/yin/vm/continuation_invoke_test.cljc test/yin/vm/ffi_test.cljc test/yin/vm/rule_r_test.cljc test/yin/vm/linker_require_test.cljc
```

PASS: kondo exit 0, 0 errors / 0 warnings (informational alias/referral messages remain); cljstyle exit 0. Python trailing-whitespace scan: zero offending lines across all ten paths. `git diff --check` exits 0.

Dart: **NOT RUN by me in round 2**, per the explicit instruction “Dart: leave it, the orchestrator runs it.” No Dart command was attempted in this round and no count/pass is claimed. The earlier Stage 2 sandbox failure remains documented in that report; it was not re-run. Full `bb test`, G2 and G3 were not run.

## Evaluator preservation

At round-2 entry I copied `src/cljc/yin/vm/semantic_register.cljc` to a temporary byte baseline; direct comparison after the checks passes. SHA-256 at both entry and exit is:

```text
7fe2bb0c0984d44c4f1efc6bf3d1c365a023b07dfd59efc5bf91f04e35ba28a2
```

It also matches the final Stage 2 report's SHA-256. Thus the evaluator's existing diff relative to HEAD is unchanged from Stage 2; the intentional Stage 2 source diff is still present, and I do NOT claim a clean source diff against HEAD. No source mutation was performed in this round.

## Equivalent mutant

The previously tested decoder/table-removal local-binding swap SURVIVED: the Stage 2 mutation run had 51 tests / 381 assertions, 0 failures / 0 errors. This remains plainly recorded as an equivalent mutant, not a killed one. On success both pure local orders produce the same immutable machine and decoded value. On a decoding throw no updated machine is returned, and the caller still holds its original parked table. `ffi/call-result` receives only the response and id, not the machine, so it cannot observe the local removal timing. The implementation keeps the specified remove-then-decode order. Omitted removal and decoding before the retained-request branch were separately killed in Stage 2. No mutants were re-run or newly claimed killed in round 2.

## Acceptance list (evaluator §5.2)

Items 1-25 retain their Stage 2 evidence; all referenced target and legacy tests reran green in the JVM group above. Item 26 is updated under this round's explicit runnable-lanes/Dart-delegation ruling.

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
| 26 | PASS | Requested verification: combined JVM 231 tests / 3313 assertions and Node 114 / 2006, both 0 failures/errors; kondo, cljstyle, whitespace and diff checks pass. Exactly one authorized scanner-fixture exclusion resolves the two prior failures. Dart NOT RUN by me, delegated to orchestrator; no G2/G3 or Dart pass claimed. |

## Remaining work

No requested round-2 implementation or runnable verification remains. The orchestrator owns final Dart and broader landing gates. Phase 5c must supply the real register-vector scanners and retire this one explicitly commented exclusion together with the empty-obligations fixture. No new design ambiguity or source defect was found.
