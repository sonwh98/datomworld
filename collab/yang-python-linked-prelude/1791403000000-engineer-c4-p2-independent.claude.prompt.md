Created-GMT: 2026-10-08 07:43:00 GMT
Created-Local: 2026-10-08 14:43:00 ICT

You are the Implementation Engineer for Track A Phase C4 Slice P2: "Module emitter, py manifest, linked profile".
Model: Claude Opus 5.5.
Repository root: /Users/sto/workspace/datomworld-p1
Branch: yang-python-c4-p2 (based on master @ 66756d20)

Read the Lead System Architect's remediation ruling:
`collab/1791405500000-architect-c4-p2-remediation.claude-fable-5-1.findings.md`
And the original spec:
`collab/1791403000000-architect-c4-p2-spec.claude-fable-5-1.findings.md`

Per Architect ruling R9 and §6:
Slice L-f is currently being executed in an isolated sibling worktree by a dedicated linker engineer.
While L-f proceeds, you are authorized and instructed to implement the frontend and prelude portions of P2 that are completely independent of F1/F2:
- Step 1: Red test phase for prelude units:
  * A8, A9, A10 in `test/yang/python/antlr/linked_prelude_test.cljc`
  * Add unit tests for `module-uast`, `module-exports`, and `module-spec`
- Step 2: Minimal L-a in `src/cljc/yin/vm/linker.cljc` (step 5b discharge for qualified host-module obligations) and `src/cljc/yin/vm/engine.cljc` (primitives discharge-defect arm per spec §2.3).
- Step 3: Single-source updates in `src/cljc/yang/python/antlr/prelude.cljc`:
  * Rename `py/conj` -> `py/vconj`, `py/not` -> `py/lnot` (D4).
  * Add `py/object-class` and `py/run-main` (D7, D8).
  * Add module-level placeholders for all runtime keys with literal `:py/uninit` (D6).
  * Build module emitter: `module-uast` (wide layout D5, stripping D3), `module-exports`, `module-spec` (D10).
- Step 4: Lowering updates in `src/cljc/yang/python/antlr/lower.cljc`:
  * Baseless class uses `(py/object-class)` (D8).
  * Add stage option `:prelude` (`:bundled` or `:linked`, defaulting to `:bundled`) in `lower-packet` and `open-stage` (D9).
  * Emit linked entry wrapper `(do (require 'py) (py/init!) (py/run-main (fn [%globals %globals-fn] body)))`.
- Step 6: Golden re-pins per §4.9 (`float_address_test.cljc`, `lower_test.clj`, token renames).
- Step 7: Lint & fast verification:
  * `clj -M:kondo --lint src/cljc/yang/python/antlr test/yang/python/antlr`
  * Verify `clojure -M:test -n yang.python.antlr.prelude-parity-test` and lower test suites.
  * Ensure `bb test:clj` stays green on existing suites.

Do NOT run steps 5, 8, or the full linked legs yet (those will run after L-f lands on master and this branch is rebased on master).

Write your interim progress and test results to:
`collab/1791403000000-engineer-c4-p2.claude-opus-5-5.findings.md`
