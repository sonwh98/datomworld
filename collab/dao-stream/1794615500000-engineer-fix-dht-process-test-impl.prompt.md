Created-GMT: 2026-11-14 00:44:00 GMT
Created-Local: 2026-11-14 07:44:00 +07
Coding-Agent: codex
Model: gpt-6.1-terra
Role: DaoStream & Distributed Protocol Engineer (and Yin.REPL Shell Composition)
Worktree: ~/work/fix-master-dht (on jupiter.stigmergy.systems)
Branch: fix/master-gate-and-dht-tests

# Task: Implement Fixes for `yin.repl.dht-process-test` & Live CLI (`yin.shell.main` / `yin.repl.main`)

Note: Prior investigation sessions `01a1d77e-5caa-70f0-b120-a1924a0a73c1` identified concrete leads (`1794615000000-engineer-fix-dht-process-test.findings.md`), and their resume failed to get write permissions. You are a **fresh writable session (`-s workspace-write`)**: diagnose, edit files, test non-socket suites, and build Node REPL cleanly.

## Three Concrete Failures Observed on `shiva` (`clj -M:test -n yin.repl.dht-process-test`)

### 1. `a-module-published-by-name-is-required-by-name-across-processes` (`test/yin/repl/dht_process_test.clj:917`)
- Transcript printed `dht: published <m1> ..., acknowledged: sent to 2 peers`, then 7 `PARTIAL`/`NOT acknowledged` lines, then all 7 `dht: republished <addr> (... blobs), acknowledged: sent to 2 peers`, yet `await-head-published` returned `nil`.
- **Root cause confirmed in prior trace:**
  - `src/cljc/dao/space/dht.cljc:988` legitimately emits `:republished` when a previously reported publication finishes later, and `src/cljc/yin/shell/dht.cljc:824` formats it as `"dht: republished <manifest> (<blobs> blobs), ..."`.
  - `test/yin/repl/dht_process_test.clj:576` defines `published-line` matching only `#"dht: published :(segment/\S+) .*, acknowledged"`, missing `republished`.
- **Fix:** Update `published-line` (and verify any other callers in `dht_process_test.clj`) to match both `published` and `republished` while still verifying the acknowledged address equals `head-of-dir`.

### 2. `a-reader-follows-the-publisher-s-head-across-processes` (`test/yin/repl/dht_process_test.clj:1048`)
- Both `reader-b-jvm` (`yin.shell.main`) and `reader-b-node` (`target/yin-repl.js`) printed:
  `";; require still pending; lines typed meanwhile run when it completes, (abandon) gives up"`
  instead of `";; require pending: my.lib"`, so `(await-line p #";; require pending: my\.lib" 60000)` failed on BOTH JVM and Node!
- **Root cause lead from prior trace:**
  - Compare `test/yin/repl/dht_head_test.cljc` (fixed at commit `9420936b`) against `src/cljc/yin/repl/main.cljc` (`step-all` around line 823) and `src/cljc/yin/shell/main.cljc` / `src/cljc/yin/shell/session.cljc`.
  - Currently `yin.repl.main/step-all` calls its composition recheck (`recheck-fn`) BEFORE `driver/repl-step`, which evaluates input lines and parks `(require ...)`. Newly parked link/service/publication requests are therefore NOT served (`shell.session/serve`) and rechecked (`shell.session/recheck-pending` / `shell.session/recheck-on-tick` or generic composition hook) right after evaluation! Notice `dht_head_test.cljc` worked around this in `eval-at`, whereas the real CLI (`yin.shell.main` on JVM and Node via `yin.repl.main`) still lacked post-eval serving/rechecking (or trace what happens tick-by-tick when `reader-b` evaluates `(require ...)` after `(lang :yang/clojure)` / `(engine :ast-walker)` / `(yin.head/follow ...)`!).
- **Fix:** Inspect `src/cljc/yin/repl/main.cljc`, `src/cljc/yin/shell/main.cljc`, `src/cljc/yin/shell/session.cljc`, and `src/cljc/yin/repl.cljc`. Fix the live CLI loop (`step-all` / composition callback) cleanly via generic composition hooks (preserving Gate A = 0 in `src/cljc/yin/repl/` and `src/cljc/yin/repl.cljc`, and keeping `dao.host.gate-test` green!). Add a focused non-socket unit test (e.g., in `yin.repl.shell-session-test` or `yin.repl.main-test`) verifying the exact `";; require pending: ..."` live-loop behavior!

### 3. `prompt-returns-after-filtered-comprehension-assignment-test` (`test/yin/repl/dht_process_test.clj:1207`)
- Hung past `timeout 120` on `shiva` (`1794615000000-engineer-fix-dht-process-test.findings.md` tested `:clj-yin-repl` WITHOUT `--no-state` and didn't inspect `run-submissions` / `spawn!`).
- **Action:** Read `run-submissions` and `prompt-returns-after-filtered-comprehension-assignment-test` in `test/yin/repl/dht_process_test.clj` (~lines 1140-1237). Notice `spawn!` runs `clojure.main -m yin.shell.main --no-state --name prompt-hang-bug --dir target/prompt-hang-bug` (NO sockets needed!).
- Run a direct piped repro with `--no-state --name prompt-hang-bug --dir target/repro-hang` AND/OR run `-v yin.repl.dht-process-test/prompt-returns-after-filtered-comprehension-assignment-test` directly in `~/work/fix-master-dht` (since this `deftest` does NOT open sockets! Just check if `--no-state` + EOF/stdin/shutdown causes a hang or if it was just >120s for two JVM cold starts, and fix any real hang in `yin.shell.main` / `yin.repl.main` or shutdown!).

## Sandbox Note
- Do NOT run socketed `yin.repl.dht-process-test` vars (Vars 1, 2, 3) on `jupiter` inside `-s workspace-write` (sockets hit `EPERM` under bwrap). Var 4 (`prompt-returns-after-filtered-comprehension-assignment-test`) uses stdin/stdout pipes only — test that one if socketless!

## Acceptance Criteria
1. Reproduce/verify `prompt-returns-after-filtered-comprehension-assignment-test` (or socketless `yin.shell.main --no-state --name ... --dir ...` equivalent) and fix any hang.
2. Fix live CLI (`yin.shell.main` / `yin.repl.main` / `yin.shell.session`) so newly parked links serve/recheck and print `";; require pending: ..."` cleanly + add a focused non-socket regression test.
3. Fix `published-line` in `test/yin/repl/dht_process_test.clj` for `published` / `republished`.
4. All non-socket suites pass (`0 failures, 0 errors`): `yin.repl.main-test`, `yin.repl.shell-session-test`, `yin.repl.dht-test`, `yin.repl.dht-head-test`, `dao.host.gate-test`.
5. `bb build:yin-repl-node` succeeds (`0 warnings`).
6. Gate A check (`rg 'dao\.jing\.dht|yin\.link\.dht|yin\.shell\.dht|datagram' src/cljc/yin/repl src/cljc/yin/repl.cljc`) returns **0 matches**.
7. `clj -M:kondo --lint` on all modified files returns **0 errors, 0 warnings**.
8. Write findings + diff + verbatim outputs to `collab/dao-stream/1794615500000-engineer-fix-dht-process-test-impl.findings.md`.
9. Remove any stray scratch files (`.attach_pid*` etc.) and do NOT commit or push.

Begin your final response with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
