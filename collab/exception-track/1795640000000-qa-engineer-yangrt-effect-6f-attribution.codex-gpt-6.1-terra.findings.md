# yang.rt.effect-test Node float attribution

- Host: shiva; branch `fix/yangrt-effect-cljs-float`.
- Baseline `origin/master`: `4e12b3c3fe97818a49b28b687f921b6478f94231`.
- Verified test-change commit: `84b29cd238a6242c220115cf014e4a4afef50dee` (the subsequent findings-only commit does not alter test code).
- Classification: **(a) stale test expectation**, not a bundled/linked asymmetry or a production defect.

## Contract and cause

`docs/design/yang.preludes.md:174-175` requires boxed `data/float64` inputs and outputs for `yin.vm.float`, and scopes the boxed path to Clojure-family profiles. `docs/design/dao.jing.cbor.md:387-392` specifies the portable float64 carrier and JavaScript's carrier on decoding. `dao.jing.cbor/float64` returns a JVM Double and a JavaScript Float64 carrier (`src/cljc/dao/jing/cbor.cljc:242-247`, `src/cljc/dao/host/number.cljc:200-208`).

`yang.clojure/compile-literal` retains the host literal (`src/cljc/yang/clojure.cljc:766-769`); a non-integral JS number is recognized as float64 content (`src/cljc/dao/jing/cbor.cljc:212-221`). `inc` routes float input through `float/add` (`src/cljc/yang/clojure/core_impl.cljc:256-259`), which boxes its result (`src/cljc/yin/vm/float.cljc:110-111`). The CLJS Float64 equality implementation requires another Float64 (`src/cljc/dao/host/number.cljc:97-101`), so raw host literal `2.5` is not equal to the returned carrier. On the JVM, the constructor and result are ordinary Doubles, so the original test passed. Both bundled and linked outputs showed the same six failures across all three backends. The fix uses `(cbor/float64 2.5)` as the exact expected content, retaining all cold-policy assertions and strict equality.

## Scoped verification

Build prerequisites: `bb check:node-discovery` rc=0; `bb build:yin-repl-node` completed successfully (the surrounding shell was interrupted with rc=130 to prevent its following full-suite autorun); `clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:autorun false}'` rc=0 before and after the fix. The override prevents full-suite autorun while producing the lane's Node test bundle.

- Baseline Node `node target/node-tests.js --test=yang.rt.effect-test`: rc=1; Ran 9 tests containing 78 assertions; 6 failures, 0 errors.
- Baseline JVM `clojure -M:test -n yang.rt.effect-test`: rc=0; Ran 9 tests containing 78 assertions; 0 failures, 0 errors.
- Fixed Node same command, run 1: rc=0; Ran 9 tests containing 78 assertions; 0 failures, 0 errors.
- Fixed Node same command, run 2: rc=0; Ran 9 tests containing 78 assertions; 0 failures, 0 errors.
- Fixed JVM same command: rc=0; Ran 9 tests containing 78 assertions; 0 failures, 0 errors.
- `clj -M:kondo --lint test/yang/rt/effect_test.cljc`: rc=0; 0 errors, 0 warnings.
- `git diff --check`: rc=0.

No master merge performed. The prompt file was already untracked and was not included in the branch.
