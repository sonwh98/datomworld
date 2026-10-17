Completed-GMT: 2026-10-16 14:40:36 GMT
Completed-Local: 2026-10-16 21:40:36 +07
Coding-Agent: claude
Session-ID: dc3e4b88-02d8-428d-b938-541541f0fbe1
# Slice 6d-a cljd compile fix

Role: VM Runtime. Branch `srvm-6d-a`. The fix is commit **`d5987015`**, "test(yin.vm.semantic): cljd compile gates for 6d-a harness defns", appended on top of `2a4fe508`. It was pushed to `origin srvm-6d-a` as a fast-forward; no amend and no force-push.

Only `test/yin/vm/semantic/safepoint_harness_test.cljc` changed. No `src/` file and no `:cljs` gate. `collab/` and `review/` files were left untracked.

## Verified locally: yes

A Dart SDK is available locally (`mise exec -- dart --version`: 3.13.5). I iterated on the lane's exact command:

```
mise exec -- bb src/dev/cljd_agg.clj --only yin.vm.ucf.rows-test,yin.vm.ucf.rows-custody-test,yin.vm.ucf.rows-checkpoint-test,yin.vm.ucf.ledger-fixture-test,yin.vm.semantic.frame-test,yin.vm.semantic.layout-test,yin.vm.semantic.safepoint-harness-test,yin.vm.semantic.vm-test,yin.vm.engine-test,yin.vm-test
```

| Iteration | Result | Log |
|---|---|---|
| 1 | Reproduced shiva's failure: compiling `yin.vm.semantic.safepoint-harness-test` hits `Cannot invoke "clojure.lang.IFn.invoke(Object, Object)" because "aliases" is null` while compiling `(defn check-returned-boundaries …)`. cljd compile failed, exit 1. | `target/6da/r5-cljd-1.log` |
| 2 | After gating the defn, cljd compiled. The **Dart** compiler then failed: `safepoint-harness-test_test.dart:33851: Error: This expression has type 'void' and can't be used. $UNDERSCORE_$2=t_test.expect(...)`. The generated metadata traced it to source line 2834. | `target/6da/r5-cljd-2.log` |
| 3 | **Exit 0.** "Running 10 test files in 8 shards" … "+267: All tests passed!". 267 equals the `deftest` count of the ten namespaces (17 + 6 + 50 + 14 + 54 + 15 + 30 + 16 + 30 + 35), so every namespace ran. | `target/6da/r5-cljd-3.log` |

This is the quick lane only; `slow/guard` bodies print SKIP on cljd. The final commit was reformatted (whitespace only) after iteration 3. I did not re-run the Dart lane on that reformatted text.

## Per-defn gating inventory

| Form | Failure | Gating |
|---|---|---|
| `safepoint_harness_test.cljc` `check-returned-boundaries` | cljd compiler NPE (`aliases` is null) in the IFn reify expansion of a multi-arity defn with a destructuring `fn` | `#?(:cljd (defn check-returned-boundaries [& _] nil) :default (defn check-returned-boundaries …real body…))`, with the comment "cljd compiler NPE on multi-arity + destructuring; stub — JVM/cljs use the real body; no cljd caller (gated)". Its three callers were already `#?(:cljd nil …)`. The two now-redundant inner `#?(:cljd (throw …) :default (with-redefs …))` were folded back to the plain `with-redefs`. The JVM/cljs body is token-identical to the reviewed one; only indentation changed. |
| `safepoint_harness_test.cljc` `ready-portability-root-oracle`, binding `_ (when (= root :state) (is (seq wkeys)))` | cljd turned the value-position `is` into `$_ = expect(...)` on a void, which Dart rejects | Became `_ (when (= root :state) (is (seq wkeys)) nil)`, with a one-line comment. The same assertion runs on every host; nothing is gated. |

No other defn in the ten namespaces needed a gate: the lane compiles and passes after these two.

## JVM quick tier

`clojure -M:test -e :slow` on the final tree: **4,079 tests, 269,020 assertions, 0 failures, 0 errors** (`target/6da/r5-quick.log`). This matches the required baseline exactly. kondo reports 0 errors and 0 warnings on the touched file, and cljstyle was applied.

## STOPPED on

Nothing.

READY
