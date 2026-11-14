Created-GMT: 2026-11-13 23:39:00 GMT
Created-Local: 2026-11-14 06:39:00 +07
Coding-Agent: codex
Model: gpt-6.1-terra
Role: DaoStream & Distributed Protocol Engineer (and Yin.REPL Shell Composition)
Worktree: ~/work/fix-master-dht (on jupiter.stigmergy.systems)
Branch: fix/master-gate-and-dht-tests

# Task: Fix Final Failing Test in `yin.repl.dht-test` & Complete Verification

## Background
Commit `e181a633` fixed `dao.host.gate-test`, and commit `7c3534dd` (on branch `fix/master-gate-and-dht-tests`) resolved 45/46 tests in `yin.repl.dht-test` (all 43 non-slow tests now pass: 43 tests, 419 assertions, 0 failures, 0 errors).

One `^:slow` test remains failing when running the full `yin.repl.dht-test` namespace (`clj -M:test -n yin.repl.dht-test`):
```
FAIL in (with-peers-that-never-accept-twenty-rounds-report-and-the-ticker-idles) (dht_test.cljc:2426)
between repair cycles the ticker returns to its idle cadence
and the idle curve resumes after a cycle
expected: (some false? (drop-while false? (:moved idle)))
  actual: (not (some false? (true true true ... 400 trues ...)))
```
Note from the prior engineer's trace: after 20 unacknowledged publications and 400 idle ticks, `(:reading node)` / `(:pubs node)` looked settled (`reading 204750, fresh [], repair [], inflight {}, pubs due 249250..324750`), yet every tick returned `moved` = `true` (look at how `dht_test.cljc`'s `step`, `busy?`, `driver/pending-write?`, or shell composition tick state tracks activity / `moved`).

## Governing Specifications & Constraints (Read First)
- `docs/design/yin.repl.dht-composition.md`
- `docs/design/datom.world.md`
- `docs/agents/build-n-test.md`
- Inspect `test/yin/repl/dht_test.cljc` (around line 1990-2450), `src/cljc/yin/shell/session.cljc`, `src/cljc/yin/shell/dht.cljc`, and `src/cljc/yin/repl/main.cljc`.

## Non-Negotiable Architectural & Quality Rules
1. **Preserve DHT decoupling in `yin.repl` (Gate A):**
   - `rg 'dao\.jing\.dht|yin\.link\.dht|yin\.shell\.dht|datagram' src/cljc/yin/repl src/cljc/yin/repl.cljc` MUST return **0 matches**!
2. **Keep `dao.host.gate-test` green:**
   - Do not add dependencies to `yin.repl.main`'s host-boundary closure.
3. **Do NOT weaken tests:**
   - Preserve all behavioral assertions of `with-peers-that-never-accept-twenty-rounds-report-and-the-ticker-idles` (ticker returns to idle cadence between repair cycles; `moved` is false between rounds). Diagnose whether the bug is in the composed shell `step-shell` / `session` / `driver` state or how `dht_test.cljc` computes `moved` under the refactored shell.
4. **Execution discipline (CRITICAL):**
   - You are running locally on `jupiter.stigmergy.systems` in `~/work/fix-master-dht`.
   - Run ALL test and shell commands **in the foreground** with explicit timeouts (e.g., `timeout 180 clj -M:test ...`). Never use `&`, `nohup`, or backgrounded test lanes.
   - Do NOT commit or push; leave clean working tree edits for orchestrator verification.

## Acceptance Criteria
1. Reproduce and fix `with-peers-that-never-accept-twenty-rounds-report-and-the-ticker-idles` (`timeout 180 clj -M:test -n yin.repl.dht-test -v yin.repl.dht-test/with-peers-that-never-accept-twenty-rounds-report-and-the-ticker-idles`).
2. Full `yin.repl.dht-test` passes with **0 failures, 0 errors** under BOTH:
   - `timeout 300 clj -M:test -n yin.repl.dht-test` (all 46 tests)
   - `timeout 300 DATOM_SLOW_TESTS=1 clj -M:test -n yin.repl.dht-test` (all 46 tests with slow guards active)
3. `timeout 180 clj -M:test -n dao.host.gate-test` passes with **0 failures, 0 errors**.
4. Related suites pass with **0 failures, 0 errors** (run all test namespaces under `test/yin/repl/` and `test/yin/shell/`, excluding `dht-process-test` only if cross-host builds aren't present — actually check `bb build:yin-repl-node` / `yin.repl.*` tests!).
5. Gate A check (`rg 'dao\.jing\.dht|yin\.link\.dht|yin\.shell\.dht|datagram' src/cljc/yin/repl src/cljc/yin/repl.cljc`) returns **0 matches**.
6. Lint (`bb lint` or `clj-kondo --lint` on modified files) passes cleanly.
7. Write findings + diff + verification summary to `collab/dao-stream/1794613000000-engineer-fix-dht-idle-test.findings.md`.

## Output Format
Begin your final response with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report root cause, changed files, verbatim verification outputs, and path to `collab/dao-stream/1794613000000-engineer-fix-dht-idle-test.findings.md`.
