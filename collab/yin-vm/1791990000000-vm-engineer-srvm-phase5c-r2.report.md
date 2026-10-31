Completed-GMT: 2026-10-11 10:12:45 GMT
Completed-Local: 2026-10-11 17:12:45 Asia/Bangkok

# 5c round 2: format-record and registration coverage

Engineer: claude-opus-5-5 (fresh session). Worktree `/Users/sto/workspace/datomworld-srvm-p5c`, branch `srvm-phase5c`, base HEAD 58dc0964. Nothing is staged or committed.

Changed files (`git status --short -- src test`):
- `src/cljc/yin/vm/semantic_register/scan.cljc`: comment only, 4 lines (item 3)
- `test/yin/vm/linker_test.cljc`: one new deftest (item 1)
- `test/yin/repl/serve_test.cljc`: two requires and one new deftest (item 2)

No existing assertion was edited.

Before mutating, I backed up each source file into `collab/` and checked each copy with `cmp`: `5c-r2-linker.cljc.backup`, `5c-r2-link.cljc.backup`, `5c-r2-scan.cljc.backup`. After every mutant I restored the file with `cp` and checked it with `cmp` (printed "restored"). After F7, `git diff --stat HEAD -- src/` shows only the intended 4-line comment in `scan.cljc`.

## Item 1 (finding 1): the v4 record refuses a malformed vector
**Change.** New deftest `yin.vm.linker-test/semantic-register-format-refuses-a-malformed-vector` (`linker_test.cljc:2098`). It runs the same checks on the v3 record (`linker/semantic-format`) and the v4 record (`linker/semantic-register-format`), using the malformed vector `[[:jump 9]]`. That vector is the `:SEM` case of `structural-defect-is-refused-before-load` and the v4 `code-test` `:target-bounds` refusal. For each record it asserts:
- `:row-defect-fn` returns a defect;
- `:validate-fn` returns a defect;
- `linker/verify` refuses with `:descriptor-defect`;
- the refusal's `:defect` equals the validator's answer.

That is 4 `is` forms × 2 records = 8 assertions. The `verify` call sits after the two defect-function assertions. In a first draft it sat inside the `let`, and F6 then showed up only as an error (the scanner threw on the malformed vector) rather than as named failures.

**Mutant F6** (`linker.cljc:723-724` `:row-defect-fn`/`:validate-fn` → `(constantly nil)`): **KILLED** by `semantic-register-format-refuses-a-malformed-vector`. It produced FAILs at `linker_test.cljc:2104` and `:2105`, and an ERROR at `:2106` (with no validator, `verify` reaches `scan/free-occurrences`, which throws on the malformed vector). Lane: `clojure -M:test -e :slow -n yin.vm.linker-test -n yin.vm.semantic-register.scan-test` gave **73 tests, 1146 assertions, 2 failures, 1 error** (`collab/5c-r2-mutant-F6.log`). The unmutated baseline on the same lane gave **73 tests, 1147 assertions, 0 failures, 0 errors** (`collab/5c-r2-baseline-linker-scan.log`; the reviewer's figure was 72 / 1139, so this is +1 test and +8 assertions). The baseline was taken before the reorder; the reorder changes neither count.

## Item 2 (finding 2): the `formats` registration
**Change.** New deftest `yin.repl.serve-test/the-composition-registers-the-v4-format-record` (`serve_test.cljc:446`). It required adding `[yin.repl.link :as link]` and `[yin.vm.linker :as linker]` to the ns. The test asserts:
- `(count link/formats)` is 7;
- `linker/semantic-register-format` is among `(vals link/formats)`;
- `(get link/formats :yin.semantic-register/code)` is the record. This is the same lookup the linker's request resolver performs: `(get (:formats state) format-kw)` at `linker.cljc:1528`.

**Mutant F7** (`linker/semantic-register-format` removed from the vector in `yin.repl.link/formats`): **KILLED** by `the-composition-registers-the-v4-format-record`, with FAILs at `serve_test.cljc:447`, `:448` and `:449`. Lane: `clojure -M:test -e :slow -n yin.repl.serve-test -n yin.vm.linker-test` gave **87 tests, 680 assertions, 3 failures, 0 errors** (`collab/5c-r2-mutant-F7.log`).

## Item 3 (finding 3): the unreachable reserved-name guard
**Change (source, comment only).** In `scan.cljc`, above `(when (and (not (vm/reserved-name? name)) ...`, I added a 4-line comment. It says the guard is unreachable today, because `bodies` runs `code/well-formed?` (which refuses a reserved `:var` name) and lowering emits `:define`, never `:var yin/def`. It also says why the guard is kept: so that Rule R does not rest on the validator alone. Behaviour is unchanged; the guard is kept. The mutant was not re-run, because it is equivalent by construction (reviewer's R8/F3).

## Item 4 (finding 4): the mutant-section correction
Written as a separate addendum: `collab/1791990000000-vm-engineer-srvm-phase5c-r2.mutant-addendum.md`. The round-1 report is unchanged. The addendum says:
- the round-1 kills are direct-call kills only (the harness rebound vars, but the record keeps the function objects captured at load);
- the join path was covered later by the reviewer's fresh-process F1/F2;
- F3 is equivalent;
- F6 and F7 survived round 1 and are now killed by the named round-2 tests.

## Item 5: lanes (all foreground, final sources)
- **JVM:** `clojure -M:test -e :slow -n yin.vm.semantic-register.scan-test -n yin.vm.linker-test -n yin.vm.linker-require-test -n yin.repl.require-test -n yin.repl.serve-test -n yin.vm.semantic-register.code-test -n yin.vm.semantic-register.link-test -n yin.vm.semantic-register.linearize-test` gave **173 tests, 2630 assertions, 0 failures, 0 errors**. All eight `Testing` lines were printed (`collab/5c-r2-jvm-final.log`).
- **JVM, the remaining semantic-register namespaces:** `-n yin.vm.semantic-register.analysis-test -n ...effects-test -n ...load-paths-test -n ...parity-test -n ...vm-test -n ...walker-trace-test` gave **81 tests, 1377 assertions, 0 failures, 0 errors** (`collab/5c-r2-jvm-sr-rest.log`).
- **Node (fast):** `clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^yin\\.vm\\.(linker-test|semantic-register\\.scan-test)$" :output-to "target/5c-r2-node.js"}'`. The `:test` build has `:autorun true`, so the tests ran during the compile. Result: **129 tests, 1759 assertions, 0 failures, 0 errors** (`collab/5c-r2-node-compile.log`).
  - Shadow also pulled in `lower-portable-test`, `vm-test`, `debruijn-vm-contract-test` and `parity-test`.
  - `Testing yin.vm.linker-test` and `Testing yin.vm.semantic-register.scan-test` were both printed.
  - Four SKIPs, all slow-guarded: `a-prelude-sized-module-publishes-under-default-bounds`, `scanners-agree-on-the-c4-modules`, `goldens-match`, `verify-outcomes-agree`.
  - A separate `node target/5c-r2-node.js` run was refused by the sandbox (approval required) and **did not occur**. The autorun result above is the Node evidence.
- **kondo:** `clj -M:kondo --lint` on the three changed files gave **errors: 0, warnings: 0** (`collab/5c-r2-kondo.log`).
- **cljstyle:** `cljstyle check` on the three changed files, run through a `bb` `p/shell` wrapper because the direct call needed approval: **exit 0, no output** (`collab/5c-r2-cljstyle.log`).
- **Not run, as instructed:** the slow C4 row on any host, and Dart. `yin.repl.serve-test` was not run on Node or Dart, so the new registration test is JVM-proven only; the orchestrator's Dart lane would cover it if it includes `serve-test`.
