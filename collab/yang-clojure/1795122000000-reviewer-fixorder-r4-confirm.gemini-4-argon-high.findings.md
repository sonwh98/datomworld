Completed-GMT: 2026-11-19 18:36:10 GMT
Completed-Local: 2026-11-20 01:36:10 Asia/Ho_Chi_Minh
Role: Reviewer (compiler provenance — r4 confirmation)
Coding-Agent: agy

# fixorder r4 confirmation review

**APPROVE**

All three findings from the [r3 review](1795109958000-reviewer-fixorder-r3.gpt-6.2-sol.findings.md) (1 P2 blocker, 2 P3s) are cleanly resolved in the uncommitted worktree delta. No new findings. (B1 Jolt inventory reconciliation and targeted reruns remain tracked externally with the J2 owner, unchanged from r3.)

## Actionable findings

No actionable findings.

## Verification of r4 dispositions (1–5)

1. **Distinct tap labels `:left` / `:right` both evaluate to `0` (collision preserved).**
   In [`set-effect-order-and-collision-trace-test`](../../test/yang/clojure_collection_order_test.cljc#L1058-L1115), `left` is `(do (tap :left) (- n 1))` and `right` is `(do (tap :right) (- 1 n))` evaluated at `n = 1`. Because `tap` logs its keyword argument and returns it while `do` discards the non-final expression's value and returns the final expression's value, `left` logs `:left` and returns `0`, and `right` logs `:right` and returns `0`. Both dynamic expressions therefore collide to the set member `0` at runtime while emitting distinct side-effect events (`:left` vs. `:right`), and all four routes assert `(= #{:s1 0 2} value)`.

2. **Expected label sequence `[:s1 :left :right]` is derived from the independent element-root sort and asserted on all four routes.**
   Each source element in `[(tap :s1) left right 2]` is compiled standalone with `(yang/compile-form e false {})`, projected independently via `yin.vm/ast->semantic-bytecode` (`root-of`), and sorted by `dao.jing/canonical-compare`:
   - `2` → `:segment/blake3-096aab75e0a7a51e4a417836aa1852c8c07369ab6aa20d8eb00d699c8f4331be`
   - `(tap :s1)` → `:segment/blake3-2c9fff0d1aa99843f451527b21d4e91fbb0dcdb6a37c2ae9922e354656e01187`
   - `(do (tap :left) (- n 1))` → `:segment/blake3-e37858d6ad080a73007119a8b7b8bbb27bc05048d545e5e64ffaa699d469a933`
   - `(do (tap :right) (- 1 n))` → `:segment/blake3-f695019466049a7a925403401d76b2e8884ca46292d3d3dad28ba838ebee1afc`
   The independent sort yields `[2 (tap :s1) (do (tap :left) (- n 1)) (do (tap :right) (- 1 n))]`; stripping the untapped literal `2` derives `expected = [:s1 :left :right]`, which is asserted against `:log` across all four execution routes (`:ast-walker`, `:semantic`, `:stack`, `:register`) at [lines 1095–1099](../../test/yang/clojure_collection_order_test.cljc#L1095-L1099).

3. **Compiled-tree mutation discrimination check (`postwalk-replace` pair-exchange and duplicate) is meaningful, not theatrical.**
   - `operand-carrying-label` ([lines 1045–1055](../../test/yang/clojure_collection_order_test.cljc#L1045-L1055)) locates the exact compiled operand AST maps for `left` and `right` inside `(:operands ctor)`. Because neither operand is a subtree of the other and `postwalk-replace` does not recurse into replacement values, `{left-node right-node, right-node left-node}` cleanly transposes the two colliding operands in the compiled `(conj #{} ...)` application (`swap-ast`), and `{right-node left-node}` overwrites the second colliding operand with a duplicate of the first (`dup-ast`).
   - Both mutated ASTs are executed through `trace-ast-on`, which runs every route's full lowering and evaluation pipeline (`vm/eval` on `:ast-walker`, `vm/ast->datoms` on `:semantic`, `linearize/project` + `derive/stack-image` on `:stack`, and `linearize/project` + `derive/register-image` on `:register`).
   - On all four routes, `swap-ast` produces `:log [:s1 :right :left]` and `dup-ast` produces `:log [:s1 :left :left]` (while `:value` remains `#{0 2 :s1}` in both mutated runs). Thus, if either the emitter or any of the four VM pipelines transposed or aliased the colliding operands, the primary assertion `(= expected log)` would genuinely fail on the offending route(s). Furthermore, the in-test counter-check ([lines 1100–1115](../../test/yang/clojure_collection_order_test.cljc#L1100-L1115)) permanently guards the test oracle itself: under the r3 value-tap formulation (`[:s1 0 0]`), `(is (not= expected log))` on `swap-ast` and `dup-ast` fails on every route.

4. **Full 64-digit SHA-256 in the fixture docstring matches the fixture bytes.**
   The docstring of [`legacy-c1-1-payload-b64`](../../test/yang/clojure_collection_order_test.cljc#L1256-L1266) now records the full 64-character hex digest `9673c6143af2f20e74d7321f493784a145251c7cf8b758294cba1abfa15f49b2` (restoring the `1` in `...145251c7cf...`). Independently verified against [`test/resources/yang/clojure/constructed-collections-legacy-c1-1.cbor`](../../test/resources/yang/clojure/constructed-collections-legacy-c1-1.cbor) (`5,403` bytes, `shasum -a 256` = `9673c6143af2f20e74d7321f493784a145251c7cf8b758294cba1abfa15f49b2`).

5. **Jupiter mirror README overclaims nothing.**
   [`collab/yang-clojure/1795110000000-jupiter-g2-gate-evidence/README.md`](1795110000000-jupiter-g2-gate-evidence/README.md#L7-L19) now accurately matches the mirrored artifacts:
   - [`summary.txt`](1795110000000-jupiter-g2-gate-evidence/summary.txt): `bbtest-rc=1`, `daostream-rc=0`.
   - [`bbtest.out`](1795110000000-jupiter-g2-gate-evidence/bbtest.out#L1366-L1368): explicitly scoped to the JVM attempt of `bb test` only (`5,181` tests / `400,386` assertions / `11` failures, halting at `Error while executing task: test:clj`), explicitly stating that it establishes no Node or Dart full-quick result.
   - [`daostream.out`](1795110000000-jupiter-g2-gate-evidence/daostream.out#L139-L142): identified as the mirror's only tri-host result (JVM `589` / Node `579` / Dart `509` tests, all `pass`, exit `0`).
   - [`master-redcheck.out`](1795110000000-jupiter-g2-gate-evidence/master-redcheck.out) and [`branch-mergecheck.out`](1795110000000-jupiter-g2-gate-evidence/branch-mergecheck.out): 2 pre-existing master failures on detached `origin/master` `657f01ff` and the same 2 failures on the post-merge branch, with the 9 arm-violation failures cleared.

## Gate & SHA-256 verification

| Check | Result |
| --- | --- |
| `set-effect-order-and-collision-trace-test` (independent JVM run + live route probe) | 1 test / 33 assertions, 0 failures, 0 errors |
| Full `yang.clojure-collection-order-test` (orchestrator & r4 JVM gate) | 15 tests / 91,500 assertions, 0 failures, 0 errors |
| `clj-kondo` ([`fixorder-r4-kondo.log`](1795112272000-fixorder-r4-evidence/fixorder-r4-kondo.log)) | 0 errors, 0 warnings |
| `git diff --check` | Clean (exit 0) |
| `test/resources/yang/clojure/constructed-collections-legacy-c1-1.cbor` | `9673c6143af2f20e74d7321f493784a145251c7cf8b758294cba1abfa15f49b2` |
| `test/yang/clojure_collection_order_test.cljc` | `869492520185fd2ec486a7febf1cd133f753f34920e4be85319ae10b3a53b0ec` |
| `collab/yang-clojure/1795110000000-jupiter-g2-gate-evidence/README.md` | `93446079b1ef25a8174d96153cbf941fcf90b374e530a6c4930a3c470fae99c5` |
