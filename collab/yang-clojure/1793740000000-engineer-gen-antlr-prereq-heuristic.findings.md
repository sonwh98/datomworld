# gen:antlr prerequisite heuristic findings

Base: `e85c582f7f11dec92f37a2ea4d04d29c8ffcb48f`; branch: `fix/gen-antlr-prereq-heuristic`.

The prerequisite scan followed the forward dependency closure of all selected lanes for the two REPL builds, but only the JVM closure for ANTLR. Python generates JavaScript and Dart as well as Java; `yang.clojure.stream-eval-test` reaches its parser through `yin.repl.frontends`, so a CLJS-only selection needs the same generator. The Java-specific restriction is unnecessary: the generator produces all targets together and its cache verifies inputs and outputs. The REPL triggers are unchanged.

Exact source diff:

```diff
-   names the artifact the task builds. ANTLR classes are JVM only."
+   names the artifact the task builds. ANTLR outputs serve multiple lanes."
@@
-        clj-texts (reached-text index (set (:clj selected)))
@@
-      (any? clj-texts #"Python3Parser|Python3Lexer|build/antlr")
+      (any? texts #"Python3Parser|Python3Lexer|build/antlr")
```

The existing `src/dev/affected_test.clj` prerequisite test now expects `:gen:antlr` for CLJ, CLJS and CLJD, and retains checks for the REPL tasks and an unrelated mixed selection. Its former CLJ expectation was the stale `:gen:python-antlr`.

Verification (repo toolchain on PATH):

- Fresh worktree, initially without `build/antlr`: `bb test:sub:cljs yang.base` selected the prerequisite and generated four Python JavaScript files. The initial sandboxed attempt could not resolve the upstream host; a network-enabled retry produced:
  ```text
  Skipping build:yin-repl-peer (no selected test reaches its output)
  Skipping build:yin-repl-node (no selected test reaches its output)
  Prerequisite gen:antlr (a selected test or its requires name its output)
  antlr cache miss; running clojure -M:antlr-gen
  generated 4 javascript files in build/antlr/python3/js/yang/antlr/gen/python3
  [:test] Build completed. (223 files, 222 compiled, 0 warnings, 9.89s)
  ```
  That first compile succeeded but the test process lacked Node packages (`Cannot find module '@noble/hashes/blake3.js'`). After `bb deps:npm`, rerunning `bb test:sub:cljs yang.base` returned:
  ```text
  Prerequisite gen:antlr (a selected test or its requires name its output)
  antlr cache hit (inputs and outputs verified)
  cljs: done, exit 0, 19 s
  lane  namespaces  tests failures seconds  status
  cljs           6     34        0      19  pass
  ```
- `bb test:sub:clj yang.base`:
  ```text
  Prerequisite gen:antlr (a selected test or its requires name its output)
  antlr cache hit (inputs and outputs verified)
  clj: done, exit 0, 76 s
  lane  namespaces  tests failures seconds  status
  clj            7    122        0      76  pass
  ```
- Focused test: `bb -cp src/dev -e "(require '[affected-test] '[clojure.test :as t]) (t/test-vars [#'affected-test/prerequisites-follow-the-forward-closure]) (println :focused-complete)"` printed `:focused-complete` with no failures. Before the change the CLJS and CLJD assertions both failed with actual `#{}`.
- `clj-kondo --lint src/dev/affected.clj`: `linting took 90ms, errors: 0, warnings: 0`.
- Full `bb -cp src/dev -m affected-test` has pre-existing unrelated registry coverage failures: 12 unmapped test namespaces and an expected UCF count of 40 versus 28 (22 tests, 491 assertions, 15 failures before the fix: 13 registry failures plus the two new lane assertions). The focused test passes after the fix.
- `bb test:clj` is defined in `bb.edn` with direct `:depends [build:yin-repl-node gen:antlr]`, so it does not consult this heuristic and always requests ANTLR; the requested claim that it triggers only on JVM reach does not describe the current task. It was not run, consistent with the branch testing rule against full-suite runs. The focused test exercises JVM-only selections with and without ANTLR reach.
- `bb test:sub:clj dao.stream`:
  ```text
  Skipping gen:antlr (no selected test reaches its output)
  clj: done, exit 0, 11 s
  lane  namespaces  tests failures seconds  status
  clj           38    538        0      11  pass
  ```
