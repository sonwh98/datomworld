Created-GMT: 2026-11-14 00:18:00 GMT
Created-Local: 2026-11-14 07:18:00 +07
Coding-Agent: codex
Model: gpt-6.1-terra
Role: DaoStream & Distributed Protocol Engineer (and Yin.REPL Shell Composition)
Worktree: ~/work/fix-master-dht (on jupiter.stigmergy.systems)
Branch: fix/master-gate-and-dht-tests

# Task: Diagnose and Fix `yin.repl.dht-head-test` After `yin.repl` DHT Decoupling Refactor

## Background
On branch `fix/master-gate-and-dht-tests` (HEAD `d787b76a`):
- `dao.host.gate-test`: green (14 tests, 178 assertions, 0 failures, 0 errors).
- `yin.repl.dht-test`: green (46 tests, 473 assertions, 0 failures, 0 errors, both default and `DATOM_SLOW_TESTS=1`).
- 18 other `yin.repl.*` / `yin.repl-test` namespaces (fast sweep): green (302 tests, 2592 assertions across the sweep except `yin.repl.dht-head-test`).

`yin.repl.dht-head-test` is STILL RED (`timeout 300 clj -M:test -n yin.repl.dht-head-test`): **11 failures, 1 error** (note: `yin.repl.dht-head-test` uses in-process loopback net + mesh and needs NO external sockets).

## Observed Failures (`clj -M:test -n yin.repl.dht-head-test`)
1. `a-reader-follows-requires-moves-and-restarts-from-heads-edn` (`dht_head_test.cljc:291, 293, 314`, plus `UnsupportedOperationException: nth not supported on this type: PersistentTreeMap` at `dht_head_test.cljc:326`):
   - `get-in w [:b :repl :pending-run :links]` was empty instead of `[principal]`
   - `(lines-with w :b ";; require pending: alib")` was empty
   - `(value w :b)` returned `nil` instead of manifest map for `(yin.head/heads)`
2. `a-refused-deposit-is-reported-once-and-fails-nothing` (`dht_head_test.cljc:976, 981, 982, 993`):
   - Printed `";; require still pending; lines typed meanwhile run when it completes, (abandon) gives up\n"` twice and had `was not deposited` count != 1.
3. `the-host-module-answers-with-no-follower` (`dht_head_test.cljc:666, 668`)
4. `a-simulated-crash-restarts-at-the-head-heads-edn-holds` (`dht_head_test.cljc:512, 518`):
   - `(written)` / `installed-heads` stayed at `seq 15` (`writing heads.edn again in 800 ticks`) instead of advancing to `newest`.

(Notice the strong parallel to how `test/yin/repl/dht_test.cljc` and `src/cljc/yin/shell/session.cljc` / `query.cljc` were fixed in `7c3534dd` — compare `git show 7c3534dd` for context!)

## Governing Specifications & Constraints (Read First)
- `docs/design/yin.repl.dht-composition.md`
- `docs/design/yin.vm.linker.dht.head.md` (if present) and `docs/agents/build-n-test.md`
- `git show 7c3534dd` (to see the session/harness/shell composition conventions already landed)
- `test/yin/repl/dht_head_test.cljc`, `src/cljc/yin/shell/session.cljc`, `src/cljc/yin/shell/dht.cljc`, `src/cljc/yin/shell/query.cljc`.

## Non-Negotiable Architectural & Quality Rules
1. **Preserve DHT decoupling in `yin.repl` (Gate A):**
   - `rg 'dao\.jing\.dht|yin\.link\.dht|yin\.shell\.dht|datagram' src/cljc/yin/repl src/cljc/yin/repl.cljc` MUST return **0 matches**!
2. **Keep `dao.host.gate-test` and `yin.repl.dht-test` green.**
3. **Do NOT weaken tests:**
   - Keep every assertion's behavioral intent intact; only update test helpers/state-paths/composition hooks where responsibilities genuinely moved to `yin.shell.*`, and fix real production bugs in `yin.shell.*` if any remain.
4. **Execution discipline (CRITICAL):**
   - You are running locally in `~/work/fix-master-dht` on `jupiter.stigmergy.systems`.
   - Run ALL test and shell commands **in the foreground** with explicit timeouts (`timeout 300 clj -M:test ...`). Never use `&` or `nohup`.
   - Do NOT commit or push; leave clean working tree edits for orchestrator verification.

## Acceptance Criteria
1. `timeout 300 clj -M:test -n yin.repl.dht-head-test` passes with **0 failures, 0 errors**.
2. `timeout 300 env DATOM_SLOW_TESTS=1 clj -M:test -n yin.repl.dht-head-test` passes with **0 failures, 0 errors**.
3. `timeout 300 clj -M:test -n yin.repl.dht-test` passes with **0 failures, 0 errors**.
4. `timeout 180 clj -M:test -n dao.host.gate-test` passes with **0 failures, 0 errors**.
5. Gate A check (`rg 'dao\.jing\.dht|yin\.link\.dht|yin\.shell\.dht|datagram' src/cljc/yin/repl src/cljc/yin/repl.cljc`) returns **0 matches**.
6. Lint (`clj -M:kondo --lint ...` on all modified files) passes with 0 errors and 0 warnings.
7. Write findings + diff + verification summary to `collab/dao-stream/1794614000000-engineer-fix-dht-head-test.findings.md`.

## Output Format
Begin your final response with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Include root causes, files modified, verbatim test/Gate-A/lint outputs, and path to `collab/dao-stream/1794614000000-engineer-fix-dht-head-test.findings.md`.
