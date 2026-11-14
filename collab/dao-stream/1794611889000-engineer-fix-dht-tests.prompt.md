Created-GMT: 2026-11-13 23:18:00 GMT
Created-Local: 2026-11-14 06:18:00 +07
Coding-Agent: codex
Model: gpt-6.1-terra
Role: DaoStream & Distributed Protocol Engineer (and Yin.REPL Shell Composition)
Worktree: ~/work/fix-master-dht (on shiva.stigmergy.systems)
Branch: fix/master-gate-and-dht-tests

# Task: Diagnose and Fix `yin.repl.dht-test` Regressions After `yin.repl` DHT Decoupling Refactor

## Background
`yin-repl-dht-composition` merged into `master` (`6445f37a`), moving concrete DHT/datagram/publishing wiring out of `yin.repl.*` into `yin.shell.*`. That left two suites red on `master`:
1. `dao.host.gate-test` — ALREADY FIXED on `fix/master-gate-and-dht-tests` (`e181a633`: updated `docs/design/host-boundary/closure-{clj,cljs,cljd}.edn` and `test/dao/host/gate_test.clj`; verified 14 tests, 178 assertions, 0 failures, 0 errors).
2. `yin.repl.dht-test` — STILL RED. On `master` (`746b2c34`), `clj -M:test -n yin.repl.dht-test` (without `DATOM_SLOW_TESTS=1`) produced **46 tests, 414 assertions, 116 failures, 19 errors**.

Note: `collab/exception-track/1794600398000-diagnosis-dht-refactor-breaks-gate-and-dht-tests-on-master.md` diagnosed an earlier `69b49636` failure involving deleted `yin.repl.dht` vs `yin.shell.dht` and `store.cljc` / `fs.cljc`. Some tests or production wiring in `yin.shell.*`, `yin.repl.main`, `yin.repl.store`, or `test/yin/repl/dht_test.cljc` remain broken or miswired after the Step 4 refactor.

## Governing Specifications & Constraints (Read First)
- `docs/design/yin.repl.dht-composition.md`
- `docs/design/datom.world.md`
- `docs/agents/build-n-test.md`
- `collab/exception-track/1794600398000-diagnosis-dht-refactor-breaks-gate-and-dht-tests-on-master.md`
- Relevant source and test files under `src/cljc/yin/shell/`, `src/cljc/yin/repl/`, `src/cljc/dao/jing/`, and `test/yin/repl/dht_test.cljc`.

## Observed Baseline Failures (`clj -M:test -n yin.repl.dht-test`)
19 errors across:
- `store.cljc:103` (`mem-stays-the-default-and-dht-is-an-explicit-choice`, `peers-publication-and-binding-are-separate-explicit-flags`, `the-banner-and-help-say-a-require-may-fetch-from-peers`, `the-banner-states-what-will-be-shared-before-anything-is`)
- `fs.cljc:71` (`a-dht-store-refuses-a-second-content-source`, `hydration-is-a-directory-s-first-head-write-at-its-own-sequence`)
- `jing.cljc:493` (`a-module-published-in-a-partial-round-is-absent-until-repaired`, `a-two-principal-dependency-evaluates-or-raises-dependency-binding`, `publish-at-the-prompt-derives-one-tree-and-declares-what-it-reads`)
- `Numbers.java:1095` (`a-require-of-a-failed-candidate-s-address-leaves-it-to-the-follower`, `retry-at-the-prompt-brings-the-same-repair-forward`)

116 assertion failures across pending-require / park-and-resume ticks (`";; require still pending..."`), refusal/unaskable handling, manifest hydration & index queries, partial publication & repair, plain load operations (`load-module`, `module-status`), CLI flag parsing / startup banners, and `with-peers-that-never-accept-twenty-rounds-report-and-the-ticker-idles` (`dht_test.cljc:2383`, when run without `DATOM_SLOW_TESTS=1`).

## Non-Negotiable Architectural & Quality Rules
1. **Preserve DHT decoupling in `yin.repl` (Gate A):**
   - `rg 'dao\.jing\.dht|yin\.link\.dht|yin\.shell\.dht|datagram' src/cljc/yin/repl src/cljc/yin/repl.cljc` MUST return **0 matches**!
   - Do NOT leak concrete DHT, datagram, or `yin.shell.dht` symbols back into `yin.repl` / `yin.repl.main` / `yin.repl.store` (unless strictly via composition injection / generic service/session/store abstractions per `docs/design/yin.repl.dht-composition.md`).
2. **Keep `dao.host.gate-test` green:**
   - Do not add dependencies to `yin.repl.main`'s host-boundary closure (`docs/design/host-boundary/closure-*.edn`). If any dependency of `yin.shell.*` vs `yin.repl.main` changes, verify `clj -M:test -n dao.host.gate-test` stays 0 failures / 0 errors.
3. **Do NOT weaken tests:**
   - Preserve the behavioral intent of every test in `test/yin/repl/dht_test.cljc`. Only update test helpers/imports/assertions where symbols or startup contracts genuinely moved to `yin.shell.*` per `docs/design/yin.repl.dht-composition.md`, and fix real bugs in `yin.shell.*` / `yin.repl.*` where the implementation regressed.
4. **Execution discipline (CRITICAL):**
   - You are running directly on `shiva.stigmergy.systems` in `~/work/fix-master-dht`.
   - Run ALL test and shell commands **in the foreground** with explicit timeouts (e.g., `timeout 180 clj -M:test -n yin.repl.dht-test`). Never use `&`, `nohup`, or backgrounded test lanes.
   - Leave clean changes in the working tree (do NOT commit or push; the orchestrator will review and commit after independent verification).

## Acceptance Criteria
1. `clj -M:test -n yin.repl.dht-test` passes with **0 failures, 0 errors** (both with default env AND with `DATOM_SLOW_TESTS=1`).
2. `clj -M:test -n dao.host.gate-test` passes with **0 failures, 0 errors**.
3. Related suites pass with **0 failures, 0 errors** (check all test namespaces under `test/yin/repl/` and `test/yin/shell/` if any).
4. Gate A check (`rg 'dao\.jing\.dht|yin\.link\.dht|yin\.shell\.dht|datagram' src/cljc/yin/repl src/cljc/yin/repl.cljc`) returns **0 matches**.
5. Lint / delimiter checks pass on all modified files (e.g., `bb lint` or `clj-kondo --lint ...`).
6. Write a concise findings and change summary to `collab/dao-stream/1794611889000-engineer-fix-dht-tests.findings.md`.

## Output Format
Begin your final response with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Include:
- Root causes diagnosed for each failure family
- Files modified (with brief rationale for each)
- Exact verbatim command outputs for `yin.repl.dht-test` (default and `DATOM_SLOW_TESTS=1`), `dao.host.gate-test`, related `yin.repl.*`/`yin.shell.*` tests, Gate A grep, and lint
- Path to `collab/dao-stream/1794611889000-engineer-fix-dht-tests.findings.md`
