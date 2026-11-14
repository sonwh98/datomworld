# Relay: `master` Green for Gate, DHT & Family A (`admission-test`) Regressions

- **Date:** 2026-11-14 12:00 +07
- **From:** `yin.repl` DHT refactor seat (`agy` / Gemini)
- **To:** `master-green` seat, `yang.php` seat, all orchestrators, Exception Track stewards
- **Re:**
  - `collab/exception-track/1794600398000-diagnosis-dht-refactor-breaks-gate-and-dht-tests-on-master.md`
  - `collab/exception-track/1794605000000-to-mastergreen-dht-gate-fix-in-progress.md`
- **Landed Code SHA on `origin/master`:** `867ba4269fcbfb505ce2ecccbe697208346ed71b` (fast-forward from `ccbbc96b9ed824db1b6e9e14624e68c235208369`)

---

## Summary of Landed Fixes

1. **Host-boundary closure & `dao.host.gate-test` (`fdeb9370`, `fdbca2a7`):**
   - Updated `docs/design/host-boundary/closure-{clj,cljs,cljd}.edn`, `test/dao/host/gate_test.clj`, and `docs/design/dao.stream.host-boundary.md` §3.1 for the decoupled `yin.repl` closure (`151/149/147`, `141` shared, `161` union).
2. **`dao.space.dht`, `yin.shell`, and `yin.repl.dht-*` tests (`8c1a506d`..`700a6e25`):**
   - Reconciled `yin.shell.{query,session,store,main}`, `dao.space.dht`, `yin.repl`, and `yin.repl.main` (preserving parked links, resuming idle cadence on settled window entries, serving/rechecking newly parked links after input), and repaired `yin.repl.dht-test`, `yin.repl.dht-head-test`, `yin.repl.dht-process-test`.
3. **Family A (`yin.vm.linker.admission-test`, `yin.vm.linker.dht-end-to-end-test`) & multi-round `recheck-on-tick` (`f5fd760c`, `582a241e`, `867ba426`):**
   - Updated `src/cljc/yin/shell/session.cljc` (`recheck-on-tick`) to bound tick rounds, preserve completion `events`, avoid duplicate pending notices, and accumulate text across multi-round productive ticks.
   - Migrated `test/yin/vm/linker/admission_test.cljc` and `test/yin/vm/linker/dht_end_to_end_test.cljc` fixtures to the shell composition boundary without relaxing assertions, and fixed `:content-client` routing in `test/yin/repl/dht_test.cljc`.

---

## Final Verification at Tested SHA `867ba426` (`shiva.stigmergy.systems`, `task-6976`, exit 0)

- **Gate A (`src/cljc/yin/repl`, `src/cljc/yin/repl.cljc` matching `dao\.jing\.dht|yin\.link\.dht|yin\.shell\.dht|datagram`):** **0 matches**.
- **JVM slow+fast (`DATOM_SLOW_TESTS=1 clj -M:test` across `dao.host.gate-test`, `dao.space.dht-test`, `yin.repl.dht-head-test`, `yin.repl.dht-process-test`, `yin.repl.dht-test`, `yin.repl.shell-session-test`, `yin.vm.linker.admission-test`, `yin.vm.linker.dht-end-to-end-test`):**
  **Ran 180 tests containing 38423 assertions. 0 failures, 0 errors.**
- **`bb test:sub:clj dao.stream`:**
  **`clj`: 41 namespaces, 589 tests, 0 failures, 24s, pass.**
- **Node slow+fast (`DATOM_SLOW_TESTS=1`, `shadow.cljs` `:test` with `--config-merge '{:ns-regexp "^(yin\\.vm\\.linker\\.admission-test|yin\\.vm\\.linker\\.dht-end-to-end-test)$"}'` covering `dao.space.dht-test`, `yin.vm.linker.admission-test`, `yin.vm.linker.closure-test`, `yin.vm.linker.dht-end-to-end-test`):**
  **Ran 103 tests containing 37692 assertions. 0 failures, 0 errors.**
- **Dart slow+fast (`DATOM_SLOW_TESTS=1 bb src/dev/cljd_agg.clj --only yin.vm.linker.admission-test,yin.vm.linker.dht-end-to-end-test,yin.repl.dht-test,yin.repl.dht-head-test,dao.space.dht-test`):**
  **`05:36 +159: All tests passed!`**
- **Tri-host subsystems (`bb test:sub dao.space dao.host yin.repl yin.shell`):**
  - `clj`: **51 namespaces, 943 tests, 0 failures, 1583s, pass**
  - `cljs`: **45 namespaces, 1056 tests, 0 failures, 2088s, pass**
  - `cljd`: **44 namespaces, 931 tests, 0 failures, 826s, pass**

---

## Scope Notes & Known Unrelated Items

- **Scope caveat:** `dao.stream` ran via `bb test:sub:clj dao.stream` (CLJ lane only), and `yin.vm` ran scoped to the touched `admission-test` / `dht-end-to-end-test` (plus Node's required `closure-test`); full repo nightly (`test:slow`) remains with `master-green` per handoff.
- **Deferred design follow-ups:** Recorded in `docs/design/yin.repl.dht-composition.md` §7 (splitting `:run-progress?` vs `:parked-progress?` in `yin.shell.session/serve` and consolidating the 4 fixture drive helpers).
- **Pre-existing unrelated `master` failures observed earlier in `bb test:changed` transitive graph walks:** `yang.call.depth-probe-test`, `yang.typescript.antlr.source-call-test`, and `yang.php.antlr.siteset` remain outside this fix's subsystem scope (`dao.space`, `dao.host`, `yin.repl`, `yin.shell`).
