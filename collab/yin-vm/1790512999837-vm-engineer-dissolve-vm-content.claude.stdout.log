Task complete. Summary before the formal report:

- `yin.vm.content` is dissolved: `materialize-tree!` moved into `yin.vm.cljc` beside `validate-rows` (its grammar); `materialize-vector!` moved into `yin.vm/code.cljc` beside `well-formed-vector?` — a genuine split is required here, not a stylistic choice, because `yin.vm.code` already requires `yin.vm` (for `reserved-name?`/`plain-data?`), so putting `materialize-vector!`'s grammar dependency in `yin.vm` would create a circular require.
- All consumers updated (`completion.cljc`, `linker.cljc`, `dao/jing/content/driver.clj` doc mentions; real requires/call sites in `require_test.cljc`, `linker.cljc`/`linker_test.cljc`/`linker_step_test.cljc`/`linker_manifest_test.cljc`).
- `content_test.cljc` renamed to `test/yin/vm/mint_test.cljc` (ns `yin.vm.mint-test`) since its round-trip tests exercise both new homes together; doc-comment cross-references in `debruijn_vm_contract_test.cljc` and `stack_parity_test.cljc` updated to match.
- grep-verified: zero `yin.vm.content` references remain anywhere in `src/`/`test/`.

Completed-GMT: 2026-09-27 12:53:10 GMT
Completed-Local: 2026-09-27 19:53:10 +07

Verification:
- kondo: 0 errors (4 pre-existing unrelated warnings in `yin/vm.cljc`).
- JVM full suite (`clj -M:test`): **2279 tests, 183284 assertions, 0 failures, 0 errors** (baseline was ~2276/183262; the +3/+22 delta is the concurrent udp session's own additions, not this change — my move is a 1:1 test swap).
- Focused run on every touched namespace (`yin.vm-test`, `yin.vm.code-test`, `yin.vm.mint-test`, `yin.vm.linker-test`, `-step-test`, `-manifest-test`, `yin.repl.require-test`, `debruijn-vm-contract-test`, `debruijn.stack-parity-test`): **186 tests, 2279 assertions, 0 failures, 0 errors**.
- No `udp*` failures appeared in the full run, so nothing to attribute to the concurrent session.
- Node (`clj -M:cljs` / `npx shadow-cljs compile test`) and Dart (`flutter`/`dart`) lanes: could not run — `shadow-cljs` fails to load in this environment with a macroexpansion error, and `flutter`/`dart` are not installed on PATH. I confirmed this is pre-existing and unrelated to this change by reproducing the identical shadow-cljs failure in the untouched `target/head-baseline` worktree.
- cljstyle: no `cljstyle` binary on PATH; running it via `clj -M -m cljstyle.main` (added ad hoc, not a project alias) errors on config resolution before even reaching a file, in this repo root and in the baseline worktree alike — also a pre-existing environment gap, not something introduced here. Manually kept edits ASCII, ≤80 cols, and matching the surrounding blank-line/indentation conventions.

Status: BLOCKED — Node (shadow-cljs) and Dart (flutter/dart) verification lanes are unavailable in this environment (reproduced identically on the untouched baseline worktree, so pre-existing, not caused by this change); JVM full suite and all focused namespace runs are green, kondo is clean, and no `yin.vm.content` reference remains anywhere in src/test.
