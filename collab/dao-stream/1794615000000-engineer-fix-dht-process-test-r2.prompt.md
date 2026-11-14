Created-GMT: 2026-11-14 00:42:00 GMT
Created-Local: 2026-11-14 07:42:00 +07
Coding-Agent: codex
Model: gpt-6.1-terra
Role: DaoStream & Distributed Protocol Engineer (and Yin.REPL Shell Composition)
Worktree: ~/work/fix-master-dht (on jupiter.stigmergy.systems)
Branch: fix/master-gate-and-dht-tests
Resume-Of: 01a1d77e-5caa-70f0-b120-a1924a0a73c1

# Task (r2): Implement Fixes for `yin.repl.dht-process-test` & `yin.shell.main` / `yin.repl.main`

Your prior turn ended after initial investigation with zero edits. This round requires **concrete diagnosed fixes** (do not stop with an empty diff unless you prove nothing is wrong and show `clojure -M -m yin.shell.main` passing).

## Key Clues for Your Three Leads
1. **Why your piped test didn't hang:**
   - Look at `jvm-command` / `spawn!` in `test/yin/repl/dht_process_test.clj` (around line 198): the test spawns **`yin.shell.main`** (`clojure -M -m yin.shell.main ...`), **NOT** `:clj-yin-repl` (`yin.repl.main`)!
   - Reproduce `prompt-returns-after-filtered-comprehension-assignment-test` WITHOUT sockets by piping the exact lines into `clojure -M -m yin.shell.main --name prompt-regression --dir /tmp/yin-shell-prompt-regression` (and check both `--dir` and no `--dir` or whatever `spawn!` passes in `dht_process_test.clj`). Trace `yin.shell.main` vs `yin.repl.main` EOF/poller/`step-all`/`run-submissions` shutdown handling and fix the hang!
2. **Why `reader-b-jvm` and `reader-b-node` printed `";; require still pending"` instead of `";; require pending: my.lib"` (`dht_process_test.clj:1048`):**
   - You already observed `yin.repl.main/step-all` runs composition recheck BEFORE `driver/repl-step`, so newly parked link/service requests are NOT served/rechecked right after evaluation!
   - Verify whether `yin.repl.main` + `yin.shell.main` + Node REPL (`yin.shell.main` CLJS entry) all use `step-all` (or `yin.shell.main`'s own loop), fix the post-evaluation composition `serve` + `recheck-pending` loop in production code cleanly (without leaking DHT into `yin.repl` — keep using `composition` / `session` generic hooks!), and write a non-socket unit test verifying `(require ...)` at a mocked/mesh first-contact shell prints `";; require pending: ..."`.
3. **Why `dht_process_test.clj:917` (`a-module-published-by-name-is-required-by-name-across-processes`) failed with `manifest = nil`:**
   - Check `await-head-published` in `test/yin/repl/dht_process_test.clj` vs `src/cljc/dao/space/dht.cljc` / `src/cljc/yin/shell/dht.cljc`: when initial publication reports `PARTIAL` / `NOT acknowledged` and a later retry completes acknowledgement, does the transport emit `dht: republished <addr> (... blobs), acknowledged: sent to N peers`? If yes, fix `await-head-published` in `dht_process_test.clj` cleanly so it recognizes both `dht: published ` and `dht: republished ` (while still requiring the acknowledged address to match HEAD!).

## Non-Negotiable Constraints
1. **Gate A = 0:** `rg 'dao\.jing\.dht|yin\.link\.dht|yin\.shell\.dht|datagram' src/cljc/yin/repl src/cljc/yin/repl.cljc` must return 0 matches.
2. **Do not weaken assertions:** keep behavioral contracts intact.
3. **Keep sandbox active (`workspace-write`)**, do NOT run socketed `dht-process-test` inside the sandbox (orchestrator will run it on `shiva`), and run all non-socket tests + Node build + Gate A + lint in foreground with `timeout`.
4. **Do NOT commit or push.**
5. Write complete findings + verbatim test/Gate-A/lint/Node-build results to `collab/dao-stream/1794615000000-engineer-fix-dht-process-test-r2.findings.md`.

## Acceptance Criteria to Verify Locally Before Finishing
- `timeout 60 bash -c '... | clojure -M -m yin.shell.main ...'` for the filtered comprehension passes and exits cleanly (0 hang).
- Focused non-socket tests: `yin.repl.main-test`, `yin.repl.shell-session-test`, `yin.repl.dht-test`, `yin.repl.dht-head-test`, `dao.host.gate-test` ALL pass (`0 failures, 0 errors`).
- `bb build:yin-repl-node` succeeds (`0 warnings`).
- `clj -M:kondo --lint` on all modified files passes (`errors: 0, warnings: 0`).
- Gate A grep returns exit 1 (0 matches).

Begin your final response with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
