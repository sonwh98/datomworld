Coding-Agent: agy
Model: gemini-3.1-pro-high

# Review: U5 — port tests off the v1 VM, pin parity values

Review strictly localized to the uncommitted changes in `test/README.md`,
`test/yang/{clojure,python,php}_test.clj`, `test/yin/module_test.cljc`,
`test/yin/vm/parity_test.cljc`, `test/yin/vm_test.cljc`. Explicitly
ignored the concurrent U2 work in `src/cljc/yin/repl/core.cljc` and
Flutter-related files.

### 1. D4 Pinning Mechanism — None (Correct)
The pinning mechanism is correctly implemented. `expected` values are
genuinely pinned directly into the corpus structure in `parity_test.cljc`
(`[name ast expected]`). Spot-checked multiple cases: `["addition" (binop
'+ 10 20) 30]`, `["higher order" ... 15]`, `["stream make" ... {:type
:stream-ref, :id :stream-0}]`. The pinned values correspond correctly to
the evaluation of the given ASTs. The `run-v1` live-evaluation helper was
successfully removed, completely detaching the test from the v1 VM at
runtime.

### 2. Stated Deviation for `clojure_test.clj` — None (Correct)
Sound. The test was failing because v2 does not register the stream
module globally at load time. The fix merges `:modules
(module/register-stream-module (module/default-registry))` into the test
`compile-and-run` environment. Matches the official composition wiring
pattern found in `yin.repl.core:277`.

### 3. Assertion-Count Discipline — None (Correct)
Meticulously disciplined. The 4 deftests in `parity_test.cljc` retained
their exact assertion counts. A new deftest
`codec-round-trips-the-v1-corpus-node-types` was cleanly added to
`v2_test.cljc` with 8 clear assertions that fully absorb the
`ast_conversion_test.cljc` codec cases. No assertions silently drifted.

### 4. README Template Update — None (Correct)
`test/README.md` was appropriately updated from `yin.vm` to `yin.vm`
in both the namespace import boilerplate and the file linkage.

### 5. V1 Untouched — None (Correct)
`git diff` confirms only test files were touched. `yin.vm`,
`yin.vm.ast-walker`, `dao.await` are genuinely untouched. Deletion of
these namespaces is safely deferred to U6.

## Verdict

**Ready for Architect sign-off.** The work meets all stated criteria
without any silent breakages, unwarranted modifications, or test drifts.
