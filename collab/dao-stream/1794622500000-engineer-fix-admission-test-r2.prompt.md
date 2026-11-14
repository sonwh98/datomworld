Created-GMT: 2026-11-14 02:10:00 GMT
Created-Local: 2026-11-14 09:10:00 +07

# Task: Complete Family A Fix (`yin.vm.linker.admission-test` -> 0 failures, 0 errors)

Role: Senior Clojure/ClojureScript/ClojureDart Stream & Shell Engineer

DO NOT stop with an "incomplete" report. Diagnose all remaining failures and iterate until `clj -M:test -n yin.vm.linker.admission-test` (both default and `DATOM_SLOW_TESTS=1`) passes with 0 failures and 0 errors, then commit your changes on `fix/master-gate-and-dht-tests`.

Current Workspace State (`~/work/fix-master-dht` on `jupiter`):
- Branch: `fix/master-gate-and-dht-tests` (base `9bc6ebc3`).
- `test/yin/repl/dht_test.cljc` already has a clean unstaged addition testing `:content-client` refusal — keep that change and commit it too once everything is green.
- `clj -M:test -n yin.vm.linker.admission-test` currently fails with 184 failures, 7 errors across 38 tests (509 assertions).

What to Inspect First:
1. `collab/exception-track/1794612600000-engineer-admission-inheritance-fix.codex-gpt-6.1-terra.findings.md` (notes the prior discriminator: `4d0d3e70` passed 38/509/0; `c0216b9f` decoupled `yin.repl` from DHT and moved signed-name linking to `yin.shell.session` + `yin.shell.linker`).
2. `test/yin/vm/linker/admission_test.cljc` (around lines 600-1508 where the pre-refactor shell fixture directly calls `main/boot {:dht-key ...}`, `repl/eval-input`, `repl/recheck-pending`, `repl/recheck-on-tick`, and reads `:dht`).
3. `src/cljc/yin/shell/session.cljc`, `src/cljc/yin/shell/linker.cljc`, `src/cljc/yin/shell/main.cljc`, `src/cljc/yin/shell/completion.cljc`, `src/cljc/yin/repl.cljc`, and `src/cljc/yin/vm/linker/admission.cljc`.
4. Also compare how `test/yin/repl/dht_test.cljc` and `test/yin/repl/dht_head_test.cljc` were migrated on this branch (`git show 7c3534dd 9420936b 1dce75af` for reference).

Requirements:
1. **Migrate `test/yin/vm/linker/admission_test.cljc`** off engine-owned `:dht`/`:dht-key`/`repl/eval-input`/`repl/recheck-*` onto the real composed shell boundary (`yin.shell.main`, `yin.shell.session`, `:publisher-key`, etc.), **WITHOUT** deleting, skipping, or weakening ANY assertion (preserve inheritance, tick-rollback behavior, trusted-import behavior, idle-tick behavior, answer-alone progress, and all VM coverage).
2. **Fix any real composition/handoff contract bugs** in `src/cljc/yin/shell/session.cljc`, `src/cljc/yin/shell/linker.cljc`, `src/cljc/yin/shell/completion.cljc`, `src/cljc/yin/repl.cljc`, or `src/cljc/yin/vm/linker/admission.cljc` exposed once the test fixture drives the shell boundary (e.g., pending admission/link identity visibility in `[:pending-run :links]`, bounded same-tick continuation, successor state preservation across tick rollback, `budget` handling, etc.).
3. **Preserve Gate A & Portability**:
   - Zero references to `dao.jing.dht`, `yin.link.dht`, `yin.shell.dht`, or `datagram` inside `src/cljc/yin/repl` or `src/cljc/yin/repl.cljc`.
   - All `.cljc` edits must remain valid across `:clj`, `:cljs`, and `:cljd`.
   - Do not use UDP sockets on `jupiter` (the sandbox blocks socket syscalls); `yin.vm.linker.admission-test` uses in-memory `mesh/network` / solo node.

Verification Commands (run all after your final edit and verify every one passes with 0 failures, 0 errors):
1. `clj -M:test -n yin.vm.linker.admission-test`
2. `DATOM_SLOW_TESTS=1 clj -M:test -n yin.vm.linker.admission-test`
3. `clj -M:test -n dao.host.gate-test -n yin.repl.dht-test -n yin.repl.dht-head-test -n yin.repl.shell-session-test`
4. `clj -M:test -n yin.vm.linker-require-test -n yin.vm.linker.dht-test -n yin.vm.linker.dht-end-to-end-test -n yin.vm.linker.publish-test -n yin.vm.linker.head-test`
5. `rg -n 'dao\.jing\.dht|yin\.link\.dht|yin\.shell\.dht|datagram' src/cljc/yin/repl src/cljc/yin/repl.cljc` (expect 0 matches, exit 1)

Once ALL 5 checks pass cleanly:
- Commit the source and test fixes cleanly on `fix/master-gate-and-dht-tests` (including the `test/yin/repl/dht_test.cljc` change).
- Write `collab/dao-stream/1794622500000-engineer-fix-admission-test-r2.findings.md` summarizing the exact root causes, modified files, commit SHA, and verbatim test outputs.

Begin your final summary with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
