Completed-GMT: 2026-10-10 18:33:54 GMT
Completed-Local: 2026-10-11 01:33:54 Asia/Bangkok


# Stage 1 round 2 result

Completed the five requested test changes. Modified only `test/yin/vm/semantic_register/effects_test.cljc`; this report is new. No design, phase-3 corpus, or permanent source changes. No staging or commits. M-P and `not-in-slice!` remain deferred to Stage 2 as instructed.

## Changes and evidence

1. PASS — `continuation-restores-captured-environment` spells the requested shadowing program using the corpus AST constructors. Both walker and register machines yield `(7 1)`, their normalized results agree, and both whole traces match the pinned 22-event trace below. M-G changes only continuation invocation's restored environment to the invoker's `E`. The named test fails with `(7 9)` on the register machine, normalized `[7 9]`, and a differing trace; the walker still yields `(7 1)`.
2. PASS — `park-resume` now asserts `(nil? (:control parked))` and `(nil? (:k parked))` for both parked states (the nested vector program and main-level park). M-I changes the park arm's final clearing to `(assoc :k nil)`. Both new control assertions fail: actual controls are `{:segment -1 :pc 2}` and `{:segment -1 :pc 0}`.
3. PASS — `tail-effect-returns-through-k` now asserts `(nil? (:control blocked))` and `(nil? (:k blocked))` for both the one-frame and empty-K cases. M-O changes `run-effect`'s blocked stop to `(assoc state :control nil)`. The non-main case fails the new nil-K assertion: actual K contains its return frame with window `{1 vector 2 nil}` and destination 3. The empty-K case remains nil, as expected.
4. PASS — all five existing continuation value programs use `bounded-run` through the renamed helper, with a maximum of 10,000 calls to `vm/step`. Exhaustion throws `"Test step bound exhausted"` with `{:steps 10000}` and fails the expected result assertion. The new walker-compared program also uses this bound for both machines. The trace helper retains its existing independent 100,000-step bound. M-K changes the current-continuation payload's PC to the capture PC. The effects run exits unsuccessfully after 3.93 seconds, rather than hanging: escape, tail-position, and store-not-rolled-back tests fail on the exhausted bound; the new environment test reports a bounded exhaustion error. Re-entry and reified-shape also fail on wrong results/PC. Arity checks remain bounded and preserve their error expectations.
5. PASS — renamed the register-only `every-vm=` helper to `register-value=` and updated all five call sites. M-G's new row explicitly compares the walker and register machine; the existing five retain their original programs and expected outcomes.

## Derived trace

The capture initially supplies a continuation to `r`; equality with 7 is false. The shadowing lambda binds x to 9 and invokes r with 7. Delivery resumes the capture point, supplying 7 to r; equality is now true. After returning 7, the restored outer environment supplies x = 1. List construction yields `(7 1)`. Both machines confirmed this whole normalized trace on the first run:

```clojure
[[:value {:type :closure :params [x]}] [:value 1]
 [:value :host-fn] [:value {:type :closure :params [r]}]
 [:value :continuation] [:value :host-fn] [:value :continuation]
 [:value 7] [:value false] [:value {:type :closure :params [x]}]
 [:value 9] [:value :continuation] [:value 7] [:value 7]
 [:value :host-fn] [:value 7] [:value 7] [:value true]
 [:value 7] [:value 1] [:value [7 1]] [:halt [7 1]]]
```

## Mutation runs (JVM, one at a time)

Each command was `clojure -M:test -n yin.vm.semantic-register.effects-test`. The original evaluator bytes were saved before mutations and restored in a `finally` block after each run, with byte equality asserted. The process timeout safeguard was 120 seconds; no run reached it. All four mutants were killed.

| Mutant | Named failure and assertion | Exact outcome | Elapsed command time |
| --- | --- | --- | --- |
| M-G | `continuation-restores-captured-environment`: `(= '(7 1) (vm/value r))`, actual `(7 9)`; normalized parity and whole trace also fail | exit 1; 21 tests / 103 assertions; 3 failures / 0 errors | 3.85 s |
| M-I | `park-resume`: `(nil? (:control parked))` in both parked states | exit 1; 21 tests / 103 assertions; 2 failures / 0 errors | 3.80 s |
| M-O | `tail-effect-returns-through-k`: `(nil? (:k blocked))` in the non-main case | exit 1; 21 tests / 103 assertions; 1 failure / 0 errors | 3.84 s |
| M-K | `tail-position-invocation-test`, `store-not-rolled-back-test`, `escape-discards-pending-frames-test`: `(= expected [:thrown (:thrown result)])`, actual thrown message `"Test step bound exhausted"`; additional new-row exhaustion error and re-entry/reified-shape failures | exit 1; 21 tests / 96 assertions; 5 failures / 1 error | 3.93 s |

The evaluator SHA-256 after restoration is `55667c45017ce1fe732d84c9f2d231af4589dba8f6d436c80a08af972d2cc075`. Direct byte comparison with `git show HEAD:src/cljc/yin/vm/semantic_register.cljc` passes. `git diff --quiet HEAD -- src/` exits 0, and `git diff -- src/` is empty.

## Verification

All commands ran in the authorized worktree, in the foreground. No namespace list was put in a shell variable.

- Initial G0: `clojure -M:test -n yin.vm.semantic-register.effects-test` — PASS, 21 tests / 103 assertions, 0 failures / 0 errors; first-run trace expectation matched.
- Final JVM: `clojure -M:test -n yin.vm.semantic-register.effects-test -n yin.vm.semantic-register.vm-test -n yin.vm.semantic-register.walker-trace-test -n yin.vm.semantic-register.parity-test` — PASS, 47 tests / 583 assertions, 0 failures / 0 errors. All four `Testing <ns>` lines appeared.
- Node compile: `clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^yin\\.vm\\.semantic-register\\.(effects-test|vm-test|walker-trace-test|parity-test)$" :output-to "out/test.js" :autorun false}'` — PASS, 150 files / 3 compiled / 0 warnings.
- Node execution: `node out/test.js` — PASS, 60 tests / 732 assertions, 0 failures / 0 errors. All four requested namespaces appeared; dependency namespaces `yin.vm.parity-test` and `yin.vm.semantic-register.code-test` also appeared and ran.
- `clj -M:kondo --lint test/yin/vm/semantic_register/effects_test.cljc` — PASS, exit 0, 0 errors / 0 warnings.
- `cljstyle fix test/yin/vm/semantic_register/effects_test.cljc` reformatted the renamed call sites; subsequent `cljstyle check test/yin/vm/semantic_register/effects_test.cljc` — PASS, exit 0.
- Python trailing-whitespace scan of the changed test — PASS, zero offending lines.

Dart tests: **NOT RUN**. Attempted exactly:

```sh
bb src/dev/cljd_agg.clj --only yin.vm.semantic-register.effects-test,yin.vm.semantic-register.vm-test,yin.vm.semantic-register.walker-trace-test,yin.vm.semantic-register.parity-test
```

The command exited 1 during compilation. Flutter cache writes outside the workspace were denied. Exact error lines:

```text
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 71: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.stamp.tmp.18058: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 78: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.realm: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 71: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.stamp.tmp.18081: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 78: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.realm: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 71: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.stamp.tmp.18104: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 78: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.realm: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 71: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.stamp.tmp.18127: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 78: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.realm: Operation not permitted
Execution error at cljd.compiler/mk-live-analyzer-info (compiler.cljc:214).
EOF while reading

Full report at:
/var/folders/n0/xl3_1xkn0yd5trpq843l81840000gn/T/clojure-9996149296018882129.edn
Exception in thread "Thread-0" java.lang.RuntimeException: Operation not permitted (sysctl failed)
cljd compile failed, exit 1
```

No Dart test counts or pass are claimed. The lane was stopped; no escalation attempted.

## Remaining work / findings

No new source defect or design ambiguity found in this round. The four requested mutants all fail. Dart verification requires the orchestrator's unrestricted lane. Stage 2 items, including M-P's undeclared-effect profile row and the deferred helper naming, were not started. No other requested round-2 work remains.
