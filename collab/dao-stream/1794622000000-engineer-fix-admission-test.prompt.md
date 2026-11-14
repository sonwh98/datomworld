Created-GMT: 2026-11-14 02:06:00 GMT
Created-Local: 2026-11-14 09:06:00 +07

# Task: Fix Family A (`yin.vm.linker.admission-test`) & add `:content-client` refusal test

Role: Senior Clojure/ClojureScript/ClojureDart Stream & Shell Engineer

Context & Prior Diagnosis:
- Base branch: `fix/master-gate-and-dht-tests` (synced to latest HEAD).
- `yin.vm.linker.admission-test` passed at `4d0d3e70` (38 tests, 509 assertions, 0 failures, 0 errors), broke in step 2 (`c0216b9f`) of the `yin.repl` DHT decoupling refactor, and currently fails on `1dce75af` with 184 failures, 7 errors (38 tests, 509 assertions).
- Read `collab/exception-track/1794612600000-engineer-admission-inheritance-fix.codex-gpt-6.1-terra.findings.md` (if present) and inspect:
  1. `src/cljc/yin/shell/session.cljc`
  2. `src/cljc/yin/shell/linker.cljc`
  3. `src/cljc/yin/shell/main.cljc`
  4. `src/cljc/yin/repl.cljc`
  5. `src/cljc/yin/vm/linker/admission.cljc`
  6. `test/yin/vm/linker/admission_test.cljc`

Known Root Causes to Diagnose & Repair Completely:
1. Composition handoff & parked link identity visibility:
   - The decoupled `yin.repl` engine defers signed-name link resolution to `yin.shell.session` + `yin.shell.linker`.
   - Inspect how `yin.shell.linker/serve-links` and `yin.shell.session/serve` / `eval-input` / `recheck-pending` / `recheck-on-tick` handle pending identities (`:pending`, parked `:links` on `[:pending-run :links]`), admission visibility (`pending-admission`), completion matching, bounded same-tick continuation, and full successor preservation across tick rollback (check what commit `7c3534dd`, `9420936b`, and `1dce75af` already changed vs what is still missing or broken in `yin.shell.session`, `yin.shell.linker`, or `yin.repl`).
2. Shell fixture migration in `test/yin/vm/linker/admission_test.cljc`:
   - The old shell fixture in `admission_test.cljc` drives `yin.repl/eval-input`, `recheck-pending`, and `recheck-on-tick` directly with `:dht-key` and expects an engine-owned node.
   - Migrate it cleanly to drive the real composed shell boundary (`yin.shell.session`, `yin.shell.main`, `:publisher-key`) WITHOUT relaxing, deleting, or weakening ANY assertions (including inheritance, tick-rollback x42, trusted-import x24, idle-tick, answer-alone progress, and multi-VM coverage).
3. Cross-host portability & Gate A boundary:
   - Keep Gate A strictly clean: zero references to `dao.jing.dht`, `yin.link.dht`, `yin.shell.dht`, or `datagram` in `src/cljc/yin/repl` or `src/cljc/yin/repl.cljc`.
   - Ensure all `.cljc` edits are portable across `:clj`, `:cljs`, and `:cljd`.
4. Small test gap from review (`P3`):
   - In `test/yin/repl/dht_test.cljc` (`a-dht-store-refuses-a-second-content-source`), also assert passing `:content-client` refuses and leaves the directory unlocked afterward (covering both arms of `src/cljc/yin/shell/store.cljc`).

Verification on `jupiter` (run locally in your worktree before committing):
1. `clj -M:test -n yin.vm.linker.admission-test` (all tests, default) -> 0 failures, 0 errors
2. `DATOM_SLOW_TESTS=1 clj -M:test -n yin.vm.linker.admission-test` -> 0 failures, 0 errors
3. `clj -M:test -n dao.host.gate-test -n yin.repl.dht-test -n yin.repl.dht-head-test -n yin.repl.shell-session-test` -> 0 failures, 0 errors
4. `rg -n 'dao\.jing\.dht|yin\.link\.dht|yin\.shell\.dht|datagram' src/cljc/yin/repl src/cljc/yin/repl.cljc` -> 0 matches
5. Check adjacent linker tests (`yin.vm.linker-require-test`, `yin.vm.linker.dht-test`, `yin.vm.linker.dht-end-to-end-test`, `yin.vm.linker.publish-test`, `yin.vm.linker.head-test`) -> 0 failures, 0 errors.

Deliverables:
- Commit your clean code and test fixes on `fix/master-gate-and-dht-tests`.
- Write concise findings with exact changed files, root cause, and verbatim test outputs to `collab/dao-stream/1794622000000-engineer-fix-admission-test.findings.md` (do NOT edit unrelated files).

Begin your final summary with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
