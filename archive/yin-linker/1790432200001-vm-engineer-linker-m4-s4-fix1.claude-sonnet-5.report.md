# Report: M4 S4 gate fixes (fix1)

Worktree datomworld-m4-s4 (branch m4-s4), uncommitted. Files touched:
src/cljc/yin/vm/linker.cljc, test/yin/vm/linker_manifest_test.cljc,
docs/design/yin.vm.linker.md (~line 1642 wording only).

1. P1-1: `manifest-defect` returns `{:rule :manifest-shape}` for a non-map
   (was nil = verified). Test `a-non-map-manifest-is-a-shape-defect`
   (vector and string payloads at a valid address -> :descriptor-defect).
2. P1-2: `declared-discharge` default arm refuses `:unresolved-free` with
   `:name` and the unknown `:kind`; assertion added in
   `declared-obligations-discharge-by-profile-and-manifest`.
3. P2-1: profiles renamed "stack-lowering" / "register-lowering"; no other
   uses existed in src/test/docs (docstring "B2 stack lowering" and spec
   wording amended too).
4. P2-2: new tests: criterion 24 stamp ("b1" -> :contract-mismatch),
   `record-defect` wrong op / extra key, and the `:missing :relowering`
   branch. The relowering test is `#?(:cljd nil :clj ...)` and uses
   `with-redefs-fn` on `linearize/adapt` / `rc/adapt`: every tree valid
   under the semantic grammar lowers, so no natural input reaches the
   branch. The linearize require is `#?@(:clj ...)` for kondo.
5. P2-3: manifest-step :contract-mismatch evidence is now
   `{:expected <requested contract>, :actual <manifest entry>}`, matching
   step 0's requester-as-expected convention. `with-manifest-index`
   docstring now says the merge is link-local.

Verification (JVM, touched namespaces): yin.vm.linker-manifest-test 18
tests / 88 assertions, 0 failures; yin.vm.linker-test 58 / 414, 0
failures; kondo clean on both files; cljstyle check clean.
Note: `clojure -M:test -e ...` ignores -e and runs the full JVM suite
(2155 tests, 0 failures, on the pre-relowering-test tree).
