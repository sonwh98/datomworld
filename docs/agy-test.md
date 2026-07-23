# Codebase Test Suite Audit & Strategic Recommendations

## Executive Summary

A comprehensive, cross-platform audit was performed across all primary subsystems (**`dao.*`**, **`yin.*`**, **`yang.*`**, **`agent.*`**, and **`datomworld.*`**) in `datom.world`. 

The total test suite counts and assertion volume verify strong overall health across Clojure (JVM), ClojureScript (Node.js), ClojureDart (Flutter), and Babashka (bb):

- **Clojure (JVM)**: 832 tests, 161,864 assertions — 0 failures, 0 errors.
- **ClojureScript (Node)**: 743 tests, 30,646 assertions — 0 failures, 0 errors.
- **ClojureDart (Flutter/Dart)**: 702 tests — 0 failures, 0 errors.
- **Babashka (bb)**: 829 tests, 161,816 assertions — 0 failures, 0 errors.

*Audit performed 2026-07-23; these counts are point-in-time and will drift.*

While high-level macro integration, basic stream transports, and query contracts are well-tested, a detailed inventory reveals several key areas with **zero direct unit coverage**, **thin helper coverage**, or **missing visual/partition-reconciliation testing**.

---

## Verified Subsystem Analysis & Priority Roadmap

### 1. `yin.vm.ffi` (Zero Coverage) — Priority: HIGH
> [!CAUTION]
> `yin.vm.ffi` has zero test files in `test/`.

* **Current Status**: `yin.vm.ffi.cljc` provides explicit host-side `dao.stream.apply` bridge state normalization and stepping logic for Yin VMs, with 9 functions on its public/private surface: `normalize`, `bridge-from-opts`, `attach`, `installed?`, `dispatch-call`, `synthesize-resume-entry`, `bridge-step`, `maybe-run`, `run-with-bridge`. No test file directly exercises or imports `yin.vm.ffi`.
* **Recommended Action**: Create `test/yin/vm/ffi_test.cljc` covering the full public API (`normalize`, `bridge-from-opts`, `attach`, `installed?`, `bridge-step`, `maybe-run`, `run-with-bridge`): bridge map normalization, operation dispatching, resume entry synthesis, and missing-handler error paths.

---

### 2. `yin.vm.runtime-adapter` & `yin.vm.stream-driver` (Zero Coverage) — Priority: HIGH
> [!WARNING]
> Runtime scheduling and VM stream execution drivers currently lack direct test files.

* **Current Status**: `src/cljc/yin/vm/runtime-adapter.cljc` and `src/cljc/yin/vm/stream_driver.cljc` manage thread execution adapters and stream driving loops for Yin VMs. Neither namespace has a corresponding test file in `test/`.
* **Recommended Action**: Create dedicated unit test suites verifying task state transitions, ready-queue draining, and exception propagation when driving Yin VMs through stream adapters.

---

### 3. `dao.space.transact` (Thin Coverage) — Priority: HIGH
* **Current Status**: `src/cljc/dao/space/transact.cljc` implements 10 load-bearing functions (`tempid?`, `parse-op`, `map->datoms`, `parse-tx-data`, `lookup-ident-eid`, `effective-ref-attrs-for-tx`, `expand-m-map`, `collect-tempids`, `resolve-tempids`, `apply-tempid-map`, plus the public entry point `prepare-tx`). `test/dao/space/transact_test.cljc` has a **single** `deftest` (`transact-test`) containing 3 `testing` blocks (sequential tempid resolution, cardinality-one retraction, ident-ref resolution) — a weaker test surface than 3 independent `deftest`s, since a failure anywhere in the form only reports as one failing test rather than isolating which scenario broke.
* **Recommended Action**: Split into independent `deftest`s per scenario, and expand coverage to component attribute expansions, unique value constraint collisions, same-tx schema modifications, and invalid ident lookups.

---

### 4. `dao.space.transactor` (Cross-Stream Collisions & Index Lifecycles) — Priority: HIGH
> [!IMPORTANT]
> Documented open architectural gaps and multi-publish reorder edge cases.

* **Cross-Stream Entity-ID Collisions**:
  * **Current Status**: As explicitly documented in `dao.space.transactor` (lines 17, 108), cross-stream global entity ID stamping (`[stream-ns offset]`) remains an open architectural gap.
  * **Recommended Action**: Add contract tests verifying multi-stream query behavior under overlapping entity IDs to establish baseline expectations before global entity stamping is implemented.
* **Multi-Stage Index Lifecycle & Reordering**:
  * **Current Status**: Single `append!` $\rightarrow$ `publish!` transitions are tested, but multi-stage reorder lifecycles are un-exercised. Note: the `:reorder-epoch` mechanism itself is implemented in `dao.space.index` (index.cljc:286-404), not in the transactor — `reorder-epoch` only appears in `transactor_test.cljc`, not in any `index_test.cljc`. Testing it through the transactor's public API is architecturally reasonable (that's the caller-facing surface), but the invariant being protected lives in `index.cljc`.
  * **Recommended Action**: Test complex multi-stage lifecycles: `append!` $\rightarrow$ `publish!` $\rightarrow$ `append!` (fold-back to wholesale) $\rightarrow$ `re-publish!` while multiple readers hold active cursors at different log positions to stress-test `:reorder-epoch` gapping edge cases, at both the `dao.space.index` and `dao.space.transactor` layers.

---

### 5. `yin.vm.wasm` Boundary & OOM Fuzzing — Priority: MEDIUM
* **Current Status**: `test/yin/vm/wasm_test.cljc` tests valid WASM execution, but memory bounds and call stack limits are un-fuzzed.
* **Recommended Action**: Add boundary tests for out-of-bounds memory access, indirect table calls, and stack depth limits to ensure the interpreter fails fast without crashing parent processes.

---

### 6. Postgraphics Visual Snapshot Testing — Priority: MEDIUM
* **Current Status**: Numerical matrix outputs and vertex attributes are verified in `test/dao/postgraphics/`, but pixel framebuffer outputs are not visually validated.
* **Recommended Action**: Introduce pixel-level framebuffer/hash snapshot testing (comparing rendered outputs to reference PNG/hash snapshots) to detect rendering regressions in shaders, winding orders, clipping, and texture sampling.

---

### 7. Targeted Network & DHT Scenarios — Priority: MEDIUM
> [!NOTE]
> Basic DHT eviction (`kad_test.cljc`) and WebSocket stream reconnection (`ws_test.cljc`) are already implemented.

* **DHT Partition Reconciliation**:
  * **Current Status**: Unreachable host timeouts (`node_test.cljc`) and peer eviction (`kad_test.cljc`) are tested. Node crash/join routing table refreshes and cross-partition reconciliation remain open.
  * **Recommended Action**: Add partition-and-rejoin simulation tests to verify eventual consistency of stored datoms and segments when network connectivity is restored between sub-clusters.
* **WebSocket Keepalive & Ping/Pong Frames**:
  * **Current Status**: Reconnection after socket closure is verified in `ws_test.cljc`.
  * **Recommended Action**: Test ping/pong keepalive frames and simulated network timeouts during active stream writes.

---

### 8. CLJD Postgraphics Host Gap — Priority: LOW
* **Current Status (corrected)**: `test/dao/postgraphics/flutter_cljd_test.cljd` exists (155 lines, 7 `deftest`s) and does cover the Flutter orchestrator, so the gap is not zero coverage as previously stated. The narrower gap is that ClojureScript gets per-*file* host tests (`web/canvas_test.cljs`, `web/gpu_test.cljs`), while the CLJD side has only the one orchestrator-level test file — `flutter/canvas.cljd`, `flutter/gpu.cljd`, and `flutter/texture.cljd` don't each get their own dedicated test file the way the CLJS backends do.
* **Recommended Action**: Add per-file Dart host tests (`canvas_test.cljd`, `gpu_test.cljd`, `texture_test.cljd`) mirroring the CLJS structure, rather than relying solely on the orchestrator-level test.

---

### 9. `agent.tzu` Tool Call Parsing (Deprioritized) — Priority: LOW
* **Current Status (corrected)**: Not dead code. No other `.cljc` namespace `:require`s `agent.tzu`, but it has its own `-main` (tzu.cljc:364) and is invoked directly as a CLI entry point (`clj -M -m agent.tzu`), referenced from `src/cljc/agent/llm-configuration.md` and `src/cljc/agent/env.example.sh`. "No importers" only means it's not used as a library dependency — it's a standalone tool.
* **Recommended Action**: If the CLI tool is still in active use, fuzz-test its tool-call/JSON-parsing path per the original recommendation rather than dropping it. If it turns out nobody runs the CLI anymore, the correct action is to remove the `-main` entry point and its test outright, not to leave it untested and "deprioritized." Confirm actual usage before deciding which.

---

## Verified Audit Summary Table

| Rank | Component / Namespace | Coverage Status | Recommended Action | Priority |
| :--- | :--- | :--- | :--- | :--- |
| **1** | `yin.vm.ffi` | **Zero Coverage** | Add `ffi_test.cljc` covering the full public API (`normalize`, `bridge-from-opts`, `attach`, `installed?`, `bridge-step`, `maybe-run`, `run-with-bridge`). | **HIGH** |
| **2** | `yin.vm.runtime-adapter` & `stream-driver` | **Zero Coverage** | Add unit tests for VM execution drivers and stream adapters. | **HIGH** |
| **3** | `dao.space.transact` | Thin Coverage | Split the single 3-`testing`-block `deftest` into independent `deftest`s; expand beyond happy paths (refs, uniqueness, schema). | **HIGH** |
| **4** | `dao.space.transactor` | Documented Gap | Add cross-stream collision contract tests & multi-publish reorder tests (spanning `dao.space.index` and `dao.space.transactor`). | **HIGH** |
| **5** | `yin.vm.wasm` | Boundary Gaps | Fuzz interpreter with OOM and stack depth limits to ensure host safety. | **MEDIUM** |
| **6** | `dao.postgraphics` | Visual Gaps | Implement image/framebuffer pixel snapshot tests for 2D/3D render paths. | **MEDIUM** |
| **7** | `dao.jing.dht` & `dao.stream.ws` | Targeted Scenarios | Add DHT partition reconciliation tests & WS ping/pong keepalive tests. | **MEDIUM** |
| **8** | `cljd/dao/postgraphics/flutter/*` | Per-File Gap (not zero) | Add per-file Dart tests for canvas/gpu/texture, matching CLJS structure. | **LOW** |
| **9** | `agent.tzu` | Live CLI, Untested | Confirm actual CLI usage; fuzz-test if active, remove `-main` + test if not. | **LOW** |
