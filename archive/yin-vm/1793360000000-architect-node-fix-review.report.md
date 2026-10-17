Completed-GMT: 2026-10-16 08:45:00 GMT
Completed-Local: 2026-10-16 15:45:00 +07
Coding-Agent: claude (claude-fable-5-1)
Session-ID: pending
(Host clock read 2026-10-15 18:00 GMT at completion; the brief's clock is used above.)

# Architect review: uncommitted Node slow-C4 fixture fix

Brief: ./collab/1793350000000-architect-node-fix-review.prompt.md
Ruling under review: ./collab/1793330000000-architect-node-slow-c4.report.md
Engineer report: ./collab/1792087076294-engineer-node-slow-c4.report.md (codex, READY)
Worktree: datomworld-srvm-p8nodefix, branch srvm-node-slow-c4-floats @ 47249651, uncommitted diff over two test files (24 insertions, 7 deletions). Read-only except this report.

## Checks

1. Diff matches F1 exactly. `test/yang/python/antlr/prelude_parity_test.cljc:231-233` are the three `py/mod` rows with `{:py/float ...}` operands and `{:py/float ...}` expectations, byte-for-byte the ruling's text. The reducer in `prelude-semantics-on-every-vm` (lines 240-243) lost the `py/float-mod` branch; every row is now `(ops/exact-literals (with-float64 f))`. The expectation line and the rest of the function are unchanged.

2. Diff matches F2 exactly. `test/yin/vm/semantic/load_paths_test.cljc` gains the `[dao.jing.cbor :as cbor]` require (alphabetically placed before `yin.vm`) and the deftest `float64-literal-beyond-2-53-loads-on-every-host` with the ruling's body verbatim. No `^:slow`, no guard, as intended for the Node fast lane.

3. Pinned address. Re-ran `clojure -M:test -n yin.vm.semantic.load-paths-test` here: 3 tests, 130 assertions, 0 failures, 0 errors. The JVM mints `:segment/blake3-d034b2a4...ed46b6` from `corpus/tail` of `corpus/lit` of the float64 carrier, so no re-pin was needed and codex's report says the same. Node and Dart are the orchestrator's lanes; a mismatch there is a determinism finding, not grounds to loosen.

4. Reader conditional. `#?(:cljs (testing ...) :default nil)` sits as the second body form of the deftest, sibling to the first `testing`, not nested inside it. On the JVM and on ClojureDart it reads as `nil`, which `deftest` tolerates. The ClojureDart host-eval pass carries `:clj`, not `:cljs`, so it cannot select the JS branch; `:default nil` is the branch it takes. Same shape as the precedent at `test/yin/vm/debruijn_code_test.cljc:251-253`. The `js/Error` and `thrown-with-msg?` references only exist inside the `:cljs` branch. The regex `#"unsafe integral JavaScript number"` matches the `number-wire` refusal text at `src/cljc/dao/jing/cbor.cljc:1020`.

5. No weakening of the fixture. The three rows assert the same arithmetic facts as before: 1.0E20 mod 3.0 is 1.0, -1.0E20 mod 3.0 is 2.0 (sign corrected toward the divisor), 1.0E300 mod 7.0 is 1.0. The path still reaches `py/float-mod`: `py/mod` (prelude.cljc:1353-1359) calls `py/division-operands`, which for a non-int pair calls `py/as-float` on each operand; `py/as-float` (prelude.cljc:1102-1107) returns `(py/num a)` for a float, the raw host double out of the carrier; `py/mod` then calls `(py/float-mod x y)` on those doubles and re-wraps with `py/float`. So `py/float-mod`'s no-quotient property (prelude.cljc:1265-1266) is exercised on exactly the same large operands as before; only the carrier wrapping at the fixture boundary changed. The carrier-never-meets-host-arithmetic rule (prelude.cljc:1791) is honoured because the unwrap happens in `py/as-float`, not in the fixture. The rows now have the same shape as the existing `py/mod {:py/float 5.9} {:py/float 1.1}` rows (lines 189-190), which already pass on Node.

6. Slow fixture on the JVM. I could not re-run `DATOM_SLOW_TESTS=1 clojure -M:test -i :slow -n yang.python.antlr.prelude-parity-test` in this session (the environment-variable prefix needs an approval the session cannot grant). Codex's stdout log records that exact command with "Ran 9 tests containing 116 assertions. 0 failures, 0 errors." and its report states all four VM assertions (`:ast-walker`, `:semantic`, `:stack`, `:register`) passed. Codex also ran kondo on both files (0 findings) and `git diff --check` (clean).

7. No other changes. `git status` shows only the two tracked test files modified plus untracked collab files. No `src/` edit, no docs, no other `test/yang` file. The optional `exact-literals` docstring trim in int_ops_test.cljc was not taken; its sentence "callers skip the float-only rows" is now stale but harmless (noted in the ruling as optional).

## Findings

P1: none.

P2: none.

P3:
- `test/yang/python/antlr/int_ops_test.cljc:107-111` docstring sentence about callers skipping float-only rows is stale after F1. Optional follow-up, C4 file, not blocking.
- The ruling's known-gaps candidate stands: `linearize/project` accepts a bare unsafe integral JS Number that only cbor refuses later at load, so the error names no node. Ledger row for a later slice, not this fix.

## Remaining verification (orchestrator)

Per the ruling's step 5, the Node slow lane (`bb test:sub:cljs yang.python --slow`) is the verification of record for the original failure, and the quick `yin.vm` lanes on Node and Dart cover the new pin and its pinned address. Those have not been run by codex or by this review. Sign-off below is on the diff's correctness against the ruling; commit after those lanes are green.

SIGN-OFF
