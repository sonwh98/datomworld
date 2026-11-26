# Architect Cross-Review: Test-Runtime Audit & Post-Audit Landings

Architect cross-review (independent second family: `agy` / `gemini-4-argon`, effort `high`) · 24 November 2026 · task `1795590000000-architect-agy-crossreview-test-runtime-audit` · inspected `origin/master` at `09ccfc68` (plus in-flight branches `origin/fix/quick-rebalance-r2` at `1dc17314` and `origin/fix/py-manifest-remint` at `d32c8179`) and host receipts under `~/ci/nightly-results/20261123-2121` and `/home/sto/ci/datomworld-nightly-{node,dart}/target-ci/*-shards-20261123-2121/`. Read-only; no source edits and no test executions.

---

## 1. Audit Review (`1795470000000-architect-test-runtime-audit`)

Astra's audit got three foundational things right: (a) identifying that `~/ci/nightly-jvm.sh` → `~/ci/shard-jvm.sh` was still bypassing `src/dev/quick_sharded.clj` via a static 13-subsystem list that dumped 74 namespaces into one 12,419 s `uncovered` JVM; (b) diagnosing the literal `grep -rl "\\^:slow"` bug that omitted 17 metadata-map `^{:slow true ...}` vars in `yang.call.exception-contract-test` (9) and `yang.python.antlr.exception-call-test` (8) while burning a JVM on `dao.test-slow` (0 tests); and (c) insisting on preserving cold-JVM vs. subsystem-order evidence (`841b2926` `include-test` precedent).

However, tested against the measured head-nightly numbers (`20261123-2124` on Jupiter; `20261123-2121` on Shiva at `0d378482`) and the harness code, four decomposition, modeling, and ranking calls in the audit were wrong, mutually inconsistent, or under-evidenced:

### 1.1 Priority 1 (JVM quick): The audit's 1,605–3,158 s model contradicted its own subsystem-preservation rule and missed that `yang.php` (8,008.77 s) would become the new single-shard hostage

* **What the audit claimed vs. what happened:**
  * In §"Why 14,087 assertions take longer than 93,396", the audit observed that applying `src/dev/subsystems.edn` prefix rules to the old 74-namespace `uncovered` residual (12,419 s) partitions it into **4 groups**: `yang.php` (29 namespaces), `yang.typescript` (20 namespaces), `dao.host` (6 namespaces), and `unmapped` (19 namespaces at the old pin; 21 at head).
  * In Priority 1, the audit then modeled routing to `bb test:quick-sharded --workers 12` as a **"balanced 4/6/8-way division of its work [that] models ~3,158/2,123/1,605 s quick wall (75/83/87% less)"** (`12,419 / {4,6,8} + 53`), while simultaneously requiring in the Risk/Acceptance column: **"Keep true subsystem membership, invocation order, fixtures and same-process lifetime intact."**
  * Measured head-nightly quick wall was **8,364 s** (8,012.94 s JVM execution + prerequisites), with the `yang.php` shard alone consuming **8,008.77 s (2 h 13 m 29 s)** — **64.5% of the entire old 12,419 s residual** — while the other 16 shards finished by 1,410.87 s (`yang.python` 1,410.87 s, `yin.repl` 1,052.26 s, `yang.base` 754.03 s, `yin.vm` 644.06 s).
* **Why the audit's model failed:**
  * A partition into 4 uneven registry buckets (`yang.php`, `yang.typescript`, `dao.host`, `unmapped`) is not a balanced 4/6/8-way division of runtime. The audit's two requirements — (1) hit `≤2,494 s` quick wall via `quick_sharded.clj` and (2) keep true subsystem membership and same-process lifetime intact — are **structurally incompatible** on `subsystems.edn`, because `yang.php` is a single registered subsystem (`{:name "yang.php", :prefixes ["yang.php.antlr"]}`) whose 29 namespaces take 8,008.77 s in one JVM.
  * Even the audit's one-sentence hedge (*"A longer indivisible group defeats the estimate"*) failed to identify that `yang.php` *was* that indivisible group by construction in `quick_sharded.clj/shard-plan`, and that `quick_sharded.clj` had no sub-subsystem or var-level split mechanism.
  * Worse, the audit's narrative focused on two `yang.php.antlr.e2e-test` quick tests (the 518-program permutation aggregate and the 271-entry PHPT selection). Subsequent scoped timing in `1795560000000-engineer-quick-rebalance-r2` measured those two vars at **1,701.43 s** (`permutation-corpus-quick-test`) and **747.81 s** (`phpt-selection-runs-test`) — a combined **2,449.24 s**, leaving **~5,559 s (~1 h 33 m)** of `yang.php` quick wall in the remaining 21 quick vars of `e2e-test` (notably `fixtures-on-the-walker-test`, sampled for `>461 s` in Terra's attribution, and `stage0-enabled-fixtures-on-the-walker-test`) plus the other 28 `yang.php.antlr.*` namespaces (`prelude-test`, `include-test`, `p5b2-lifecycle-test`, `eval-emitter-test`, `eval-service-test`, etc.).

### 1.2 Priority 1 (JVM quick): The audit missed two concrete defects in `quick_sharded.clj` — a `System/exit` bomb in `unmapped` (`d2-wedge-probe2`) and static namespace-count striping

1. **Silent truncation of the `unmapped` shard by `test/d2_wedge_probe2.clj` (19 quick namespaces / ~128 tests skipped in `20261123-2124`):**
   * The audit stated that `shard-jvm.sh` had 367 test filenames and that `quick_sharded.clj` would leave 19 namespaces (including `yang.latte-oracle-test`) in `unmapped`. It did not examine why `quick_sharded.clj` selects **369** namespaces instead of **367**.
   * `shard-jvm.sh` discovered files matching `*_test.clj` / `*_test.cljc`. By contrast, `quick_sharded.clj` uses `affected/scan-index` (`src/dev/affected.clj:265-269`), which marks a file `:test? true` if its namespace ends in `-test` **or** its text matches `#"\((?:[\w.-]+/)?deftest\s"`.
   * That regex pulls two non-`*_test` H3-1 diagnostic scratch files into the `"unmapped"` shard: `test/d2_wedge_min_repro.clj` (`d2-wedge-min-repro`) and `test/d2_wedge_probe2.clj` (`d2-wedge-probe2`), both explicitly labeled `"diagnostic probe only — contributes zero coverage"`.
   * Alphabetically, `d2-wedge-probe2` runs **second** in `unmapped` (right after `d2-wedge-min-repro` and before `dao.star.*`, `yang.call.*`, `yang.cross-lang-test`, `yang.latte-oracle-test`, and `yang.rt.effect-test`). Its first `deftest` (`test-h31-wedge-probe2`, `test/d2_wedge_probe2.clj:333-336`) calls `(-main)` → `(stage-b-real-path)`, which terminates the JVM unconditionally with **`(System/exit 0)`** on normal return (or `(System/exit 1..4)` on throw/timeout) at `test/d2_wedge_probe2.clj:306-309`.
   * In the measured head nightly `20261123-2124`, `unmapped` exited at **279.530 s** inside `d2-wedge-probe2` with **no `Ran ... tests` summary** (noted in `1795560000000-engineer-quick-rebalance-r2.codex-gpt-6.1-terra.findings.md` lines 32 and 45). All 19 subsequent namespaces in `unmapped` — **10 `dao.star.*` namespaces, 6 `yang.call.*` namespaces, `yang.cross-lang-test`, `yang.latte-oracle-test` (~888 s, 37 tests / 2,608 assertions), and `yang.rt.effect-test`** — **never executed at all**.
   * This also explains two anomalies in the head-nightly orchestrator ledger (`orchestrator-logs/1795535000-zcode-glm-5.3-flash-master-green-head-nightly-1124.md`):
     * Reported quick totals dropped from **5,248 tests / 401,419 assertions** (old shell runner) to **5,120 tests / 396,860 assertions** (128 tests and 4,559 assertions silently lost to `unmapped`'s early `System/exit`).
     * The ledger claimed **"16/17 shards GREEN"** while listing 3 red quick clusters (`dao.host` 3F, `yang.python` 6F/3E, `yin.repl` 1F/3E). With 3 red-assertion shards plus `unmapped` failing `quick_sharded.clj`'s `(some? summary)` check (`tests=nil`), only **13/17** quick shards were actually green; additionally, eliminating `d2-wedge-probe2`'s early exit restores ~1,000–1,500 s of `yang.latte-oracle-test` + `dao.star.*` work to `unmapped`.
2. **Static `take-nth` striping by namespace count in `quick_sharded.clj/run-shards!`:**
   * The audit noted in passing that `quick_sharded.clj` *"sorts by namespace count and stripes jobs across workers"*, and prescribed *"Start long whole subsystems first using elapsed history."*
   * No change was made to `quick_sharded.clj/run-shards!` (`src/dev/quick_sharded.clj:107-116`). It still sorts shards by `(comp - count val)` (namespace count) and pre-binds each worker `w` to a static chain `(take-nth workers (drop w ordered))` rather than using a shared work-stealing queue.
   * With 17 shards and 12 workers, Worker 3 is statically assigned `#3 yang.php` (29 ns, 8,008.77 s) followed serially by `#15 dao.postgraphics` (5 ns, 9 s) — holding `dao.postgraphics` hostage for 2 h 13 m while 11 workers sit idle after `t = 1,411 s`. Worse, if a heavy namespace is split into 1-var shards (`count = 1`), `sort-by (comp - count val)` places those 1-var shards at the **end** of the static stripe behind 20-namespace shards.

### 1.3 Priority 2 (JVM full finish): Cross-tier quick+slow overlap was over-ranked relative to intra-tier slow var-sharding

* Astra's Priority 2 led with **overlapping slow `yang.php.antlr.e2e-test` beside quick in a separately prepared clone** (*"Overlap can recover up to 12,472 s on the old schedule, subject to contention"*), ranking intra-namespace concurrency second.
* That ranking inverted risk and leverage:
  * Once JVM quick is rebalanced toward ~1,500–2,000 s, the theoretical ceiling of overlapping slow PHP e2e with quick shrinks from 12,472 s to **≤2,000 s**, at the cost of a second JVM clone, duplicate native/ANTLR/node-repl build prerequisites, and CPU/GC contention against 12 active quick JVMs on Jupiter.
  * By contrast, in the slow tier, 60 of 61 slow namespaces finish within ~2 h while `yang.php.antlr.e2e-test` (~29,887 s / ~8 h) runs alone for the remaining ~6 h with **11 of 12 slow worker slots idle**. Splitting slow `yang.php.antlr.e2e-test` across existing slow worker slots (the approach taken in `2862ec95`) attacks a ~20,000+ s idle-slot waste with zero cross-tier contention.
* Where Astra's Priority 2 *was* right — and where the landed implementation fell short (see §2.3) — was insisting: *"Time all 12 slow PHP vars, separating full permutations, PHPT stress and every-VM fixtures... If one sequential stress program dominates, optimize its measured evaluator path."*

### 1.4 Priority 3 & Priority 5 (Shiva Node and Dart): The audit missed why `uncovered` existed on Shiva and severely under-ranked Dart's Flutter phase

* **Why Shiva had a 22-namespace `uncovered` residual:** Both `src/dev/node_shard.clj:27` and `src/dev/dart_shard.clj:27` hardcode the legacy 13-element `all-subs` vector (`["yang.python" "yin.repl" "yin.vm" "yin.vm.ucf" "dao.stream" "dao.jing" "dao.gui" "dao.space" "demo" "dao.data" "dao.postgraphics" "yang.base" "dao.base"]`) copied from `~/ci/shard-jvm.sh`, instead of reading `src/dev/subsystems.edn` (which already defines `yang.typescript`, `yang.php`, `dao.host`, and `yin.shell`).
* **Dart's 35,756 s wall (34,873 s Flutter phase) is now the #1 critical path on Shiva once Node's residual is redistributed, and its bottleneck is a single delayed isolate (`yang.typescript.antlr.corpus-test`):**
  * Astra relegated Dart to Priority 5 (*"Low benefit until a wait or Dart execution shard is shown critical"*).
  * Because Shiva runs Node and Dart concurrently (`nightly.clj`), Shiva's host completion time is `max(Node, Dart)`. Once Node's 45,428–47,599 s residual is broken up toward Node's `yin.vm` floor (31,790–31,901 s), **Dart at 35,756 s (`20261123-2121`) exceeds Node's `yin.vm` floor and determines Shiva's wall**.
  * Extracting the exact per-test timestamps from Shiva's `/home/sto/ci/datomworld-nightly-dart/target-ci/dart-shards-20261123-2121/all.log` reveals something neither the audit nor round `1795565000000` noticed:
    * Every single Dart test across the entire 288-namespace suite **except** `cljd-agg-20261123-2121-uncovered/shard_3_test.dart` finished by **`134:37` (8,077 s = 2 h 14 m 37 s)** (`+4683 -17` out of `+4717 -17`).
    * For the next **7 hours 23 minutes (`134:37` → `577:42`, 26,585 s)**, 11 of 12 Flutter concurrency slots sat completely idle while **one single Dart isolate** (`uncovered/shard_3_test.dart`) ran **`yang.typescript.antlr.corpus-test`** (`92:27` → `576:53`, **29,066 s = 8 h 04 m**) followed by `global-write-test` (`576:53` → `577:42`, 49 s)!
    * Furthermore, `uncovered/shard_3_test.dart` did not even start until **`92:27` (5,547 s = 1 h 32 m into the Flutter run)** because `dart_shard.clj:161` passes `(conj (vec subs) "uncovered")` to `flutter test`, placing `"uncovered"` **14th out of 14 directories** on the command line behind ~77 smaller `shard_N_test.dart` files.

---

## 2. Landed-Work Review

### 2.1 `dc0c803c` — Jolt Assignments A + C (gate closure re-record + `pump.jolt:410` / `driver.jolt:117` FFI fix)

* **Assessment:** **Strong and well-evidenced.**
  * Assignment A (`dd7bc6af` + `297ee2b8`) admitted the 7 P3-prime/linker files (`yang.core.semantics`, `yang.python.antlr.{clj-runtime,contract,profiles}`, `yin.vm.linker.{closure,publish,requirements}`) into `docs/design/host-boundary/closure-{clj,cljs,cljd}.edn` (167/165/163, shared 157, union 177), verified two-run determinism and `dao.host.gate-test` (14 tests / 188 assertions, 0F/0E ×2 in fresh JVMs), and caught/corrected the initially missed prose anchor in `docs/design/dao.stream.host-boundary.md` §3.1.
  * Assignment C (`b734f6be`) fixed the genuine production Jolt FFI 6-arg `(ffi/write-array p :byte arr 0 off n)` arity bug at `src/jolt/dao/stream/ws/pump.jolt:410` (exposed by `82a8fb44`) and proactively caught the latent identical call site at `src/jolt/dao/stream/waitset/driver.jolt:117`, verified twice in fresh JVMs on `yin.repl.dht-process-test/jolt-reader-requires-a-jvm-publication-by-name` (1 test / 8 assertions, 0F/0E ×2).
* **Remaining verification obligation:** In head nightly `20261123-2124`, slow `yin.repl.dht-process-test` failed with 3F/1E across 5 tests / 142 assertions (a mixture of the Jolt reader row and Python profile-lock rows). Only the single `jolt-reader-requires-a-jvm-publication-by-name` var was re-run in `dc0c803c`; the full 5-test `yin.repl.dht-process-test` namespace must be verified end-to-end on the union tree once Assignment B lands.

### 2.2 `0d378482` — Slow-discovery metadata fix (`src/dev/slow_inventory.clj`)

* **Assessment:** **Fixes the denominator bug cleanly, but leaves two audit acceptance obligations open.**
  * Replaces `grep -rl "\\^:slow"` with `clojure.tools.namespace.find/find-namespaces-in-dir` + `(require n)` + var `:test` and `:slow` metadata inspection. Restored `yang.call.exception-contract-test` (9 slow vars, which ran for the first time in `20261123-2124` and passed GREEN) and `yang.python.antlr.exception-call-test` (8 slow vars), and eliminated the 0-test `dao.test-slow` JVM (60 → 61 slow namespaces).
* **Gaps against the audit's non-weakening bar:**
  1. **Unfiltered `(require n)` over all files in `test/`:** `find/find-namespaces-in-dir` loads every `.clj`/`.cljc` file under `test/`, including diagnostic scripts (`d2-wedge-min-repro`, `d2-wedge-probe2`), benchmarks (`bench.*`), and fixture generators. It should filter or align with valid test namespaces so scratch scripts under `test/` cannot pollute or break slow discovery.
  2. **No post-run reconciliation of discovered var counts vs. executed var counts:** The audit required comparing discovered var counts (`5,210` quick vars; `jvm-slow-manifest.edn` counts per slow namespace) against actual executed test counts (`Ran N tests`). Neither `quick_sharded.clj` nor `nightly.clj`'s slow pool checks that `sum(:tests)` equals the discovered manifest count — which is why `unmapped` exiting early via `(System/exit 0)` in `d2-wedge-probe2` (`tests=nil`, losing ~128 quick tests) did not trigger a missing-tests diagnostic in the nightly summary.

### 2.3 `2862ec95` — Opt-in slow var-sharding (`NIGHTLY_SLOW_VAR_SHARDS=1`)

* **Assessment:** **Carries three material risks under the audit's acceptance criteria (independence scope, uncalibrated round-robin var grouping, and broken queue ordering).**
  1. **Independence evidence is much narrower than the 5-namespace / 84-var enabled scope, and missed shared harness state (`lh/published` delay):**
     * In `1795500000000-engineer-slow-var-sharding.codex-gpt-6.1-terra.findings.md`, paired split-vs-intact A/B runs were executed on **only 3 slow vars in 1 namespace** (`yang.php.antlr.e2e-test/phpt-gc-{012,013,023}-stress-test`), all three of which are fast known-gap refusals returning the static payload `{:out "", :diag [], :uncaught {:class "Error", :message "Call to undefined function gc_collect_cycles()"}}`. The exploratory runs on `phpt-bug39438` and `phpt-bug40261` were killed at 78 s (`rc=143`).
     * For the other four enabled namespaces (`yang.php.antlr.include-test`, `yang.typescript.antlr.corpus-test`, `yang.python.antlr.e2e-test`, `yin.vm.semantic.safepoint-harness-test`), **zero paired split-vs-intact A/B comparisons were run** (only a single isolated 1-var run of `include-test/linked-profile-matches-bundled-test` at 330 s).
     * Independence for those four namespaces was argued solely by grepping the `*_test.clj*` files for `use-fixtures` and `defonce`. **That grep missed shared mutable/delayed state in the required harness namespaces:**
       * In `test/yang/php/antlr/linked_harness.cljc:59-69`, `(def published (delay (make-published)))` holds a process-wide in-memory content store `(:store @published)` into which `publish-units!` mutably publishes every non-`:dynscope-v1` fixture's compiled modules across all `deftest`s in `yang.php.antlr.include-test` (`test/yang/php/antlr/include_test.clj:40-48`).
       * Remember the audit's explicit warning (`line 74` and Priority 4): at `841b2926`, `yang.php.antlr.include-test` had 14 failures cold vs. 0 in subsystem order because earlier tests had pre-populated state in the shared store/environment. Splitting `include-test` across 4 fresh JVMs (2/2/2/1 vars) changes which units exist in `(:store @lh/published)` when each var runs, and also pays the expensive `lh/make-published` (compiling and publishing `yang.call`, `php` prelude, and `php.sp` safepoint hooks) 4 times instead of once.
  2. **Blind alphabetical round-robin var grouping (`take-nth 4` over `(sort vars)`) without per-var timings:**
     * `slow-schedule` (`src/dev/nightly.clj:143-150`) sorts each namespace's slow vars alphabetically and deals them round-robin `(take-nth 4 (drop i vs))` into 4 shards without measuring individual var durations (which the audit's Priority 2 explicitly required).
     * In `yang.php.antlr.include-test` (9,130 s total across 7 slow vars), almost the entire 9,130 s lives in **one var** (`p6-fixtures-linked-on-every-vm-test`, which runs all `p6/enabled` fixtures across 3 vector VMs; compare `linked-profile-matches-bundled-test` at 330 s and the small 2-unit cyclic-init tests). Dealing 7 vars into 4 shards leaves the shard holding `p6-fixtures-linked-on-every-vm-test` at ~8,000+ s, yielding near-zero wall reduction on `include-test` while consuming 3 extra JVM slots.
     * In `yang.php.antlr.e2e-test` (~29,887 s across 12 slow vars), Shard 2 gets `permutation-corpus-full-test` (5,932 generated programs), `phpt-bug44069-stress-test`, and `phpt-gc-013-stress-test` (599 s), while Shard 1 gets `fixtures-on-every-vm-test` (all fixtures × 4 VMs), `phpt-bug40809-stress-test`, and `phpt-gc-012-stress-test` (142 s). Without per-var wall times, the orchestrator log's claim (*"the next run cuts the e2e pole ~3-4×"*) is unverified and bounded below by whichever single slow var (`permutation-corpus-full-test`, `fixtures-on-every-vm-test`, `stage0-enabled-fixtures-on-every-vm-test`, or a sequential PHPT stress var) is heaviest.
     * *(Note also a minor ledger typo in `orchestrator-logs/1795535000-zcode-glm-5.3-flash-master-green-head-nightly-1124.md:17`: `yang.php.antlr.e2e-test` has **12** slow vars and 23 quick vars; **48** is `yang.python.antlr.e2e-test`'s slow var count.)*
  3. **Slow queue ordering bug in `nightly.clj` (alphabetical in `lane-jvm`, arbitrary `PersistentHashMap` order in `--list-slow`, never LPT by weight):**
     * In `src/dev/nightly.clj:106`, `slow-manifest` parses `jvm-slow-manifest.edn` (61 keys) via `edn/read-string`, which in Clojure returns an unsorted `PersistentHashMap` (because key count `> 8`).
     * `--list-slow` (`nightly.clj:479-480`) passes that unsorted `PersistentHashMap` directly to `slow-schedule`, printing commands in hash-bucket order, whereas `lane-jvm` (`nightly.clj:268`) coerces `selected` into `(sorted-map)` (alphabetical order).
     * Consequently, in actual nightly execution (`lane-jvm`, 12 workers, 76 slow jobs in alphabetical order), heavy `yin.*` and `yang.typescript.*` jobs start late: `yang.typescript.antlr.corpus-test` (8,959 s, 4 shards) sits at queue positions **47–50 of 76**, and `yin.vm.semantic.safepoint-harness-test` (5,078 s, 4 shards) sits near the very bottom at positions **67–70 of 76**! Slow jobs should be ordered LPT (Longest Processing Time first) by measured historical duration.

### 2.4 `82a8fb44` — Nightly prerequisites (`bootstrap:jolt` + `build:jolt-native`)

* **Assessment:** **Solid infrastructure fix with immediate payoff.**
  * Adding `bb bootstrap:jolt` and `bb build:jolt-native` to `lane-jvm` (`src/dev/nightly.clj:258-266`) fixed the fresh-checkout setup failures (`missing build/tools/jolt-env.sh` and `libjolt_crypto.so`) in `yin.repl.dht-process-test` and immediately unmasked the real `pump.jolt:410` FFI arity defect (`dc0c803c`).
* **One remaining prerequisite gap in `lane-jvm`:**
  * `lane-jvm` (`src/dev/nightly.clj:258-261`) runs `gen:antlr`, `build:yin-repl-node`, `bootstrap:jolt`, and `build:jolt-native`, but does **not** explicitly run `bb build:yin-repl-peer` (the Dart WebSocket peer).
  * When `subset` is nil, `bb test:quick-sharded` (`src/dev/quick_sharded.clj:199`) happens to run `build:yin-repl-peer` before JVM quick (so JVM slow later inherits it), **but** when `NIGHTLY_SUBSYSTEMS` is set (`subset` non-nil at `nightly.clj:279`), `lane-jvm` runs `bb test:sub:clj` directly and **never builds `build:yin-repl-peer`**, causing `yin.repl.main-test` to silently skip its Dart peer case (flagged in both the audit line 64 and Terra's attribution row 5). Adding `["build:yin-repl-peer" ["bb" "build:yin-repl-peer"]]` to `lane-jvm`'s prerequisite vector closes that gap for all modes.

### 2.5 `857c297d` — Shiva Node lane redistribution (`src/dev/node_shard.clj`)

* **Assessment:** **Good pragmatic mitigation of the 47,599 s serial residual, plus a right call to stop on the Dart R3 `build/test_cache` race — but carries three architectural/verification gaps:**
  1. **Treats the symptom (`"uncovered"`) rather than the cause (`all-subs` hardcoded vs. `src/dev/subsystems.edn`):**
     * As noted in §1.4, `node_shard.clj:27` still hardcodes the old 13-subsystem list instead of reading `src/dev/subsystems.edn`. By exploding `"uncovered"` into 22 single-namespace Node processes (`uncovered.<ns>`), `857c297d` actually **breaks subsystem process boundaries** for `yang.typescript` (13 CLJS test namespaces), `yang.php` (3 CLJS test namespaces: `class-model-test`, `lower-portable-test`, `prelude-test`), and `dao.host` (5 CLJS test namespaces), while keeping `yin.vm` (74 namespaces, 31,901 s) and `yang.python` (22 namespaces, 25,244 s) as monolithic single-process groups.
  2. **The weight table uses a uniform synthetic estimate (`residual-estimate-secs = 47599 / 22 = 2163`) for all 22 residual namespaces, and the required scoped verification probe timed out:**
     * Every one of the 22 residual jobs gets weight `2163` (`src/dev/node_shard.clj:102`), with a hardcoded tie-breaker only for `uncovered.yang.typescript.antlr.corpus-test`. That places all 21 other residual namespaces after `yin.vm` (31,790), `yang.python` (24,651), `yin.repl` (6,049), `yang.base` (2,839), and `corpus-test`, sorted **alphabetically** (`uncovered.dao.host.bytes-test` first, `uncovered.yang.typescript.antlr.*` last). If any other TS/PHP namespace in those 21 (e.g. `yang.typescript.antlr.linked-prelude-test`, `prelude-parity-test`, or `yang.php.antlr.prelude-test`) is multi-thousand-second heavy under `DATOM_SLOW_TESTS=1`, it starts late behind five ~2-second `dao.host.*` jobs.
     * In addition, Verification item 2 of prompt `1795565000000` required running the heaviest `yin.vm` Node namespace to completion on Shiva to validate the weight estimate; the engineer ran a 120 s timeout probe (`rc=124`, no summary) on `yin.vm.semantic.safepoint-harness-test` and explicitly recorded that a completed measurement remains outstanding.
  3. **Unattributed non-Assignment-B failure in Shiva Node's `uncovered.log` (`yang.rt.effect-test`):**
     * The head-nightly ledger Addendum (`orchestrator-logs/1795535000-zcode-glm-5.3-flash-master-green-head-nightly-1124.md:74-82`) declared: *"Node lane 45,428s: 2 red shards — `yang.python` 89F/6E and `yin.repl` 0F/17E, ALL the pin/profile-lock cluster (Assignment B)... No new red families on either host. The remaining red set on master is exactly Assignment B."*
     * **Inspection of the Shiva `20261123-2121` artifacts contradicts this claim:** in `~/ci/nightly-results/20261123-2121/summary.txt`, `node-shard` exited with **`rc=143` (SIGTERM at 45,428 s)** while `uncovered` was still running in `yang.typescript.antlr.source-call-test`, so `uncovered` never wrote a completion row to `runs.txt`.
     * However, `/home/sto/ci/datomworld-nightly-node/target-ci/node-shards-20261123-2121/uncovered.log` (lines 18–48) already recorded **6 assertion failures in `yang.rt.effect-test/clojure-core-facade-and-cold-policy` (`test/yang/rt/effect_test.cljc:253-254`)** across `:semantic`, `:stack`, and `:register` (bundled and linked), where `(inc 1.5)` on CLJS evaluates to `#dao.jing/float64 2.5` while `expected` has raw `2.5` (the identical 6 failures also appeared in `20261123-0015/uncovered.log`).
     * Now that `857c297d` schedules `uncovered.yang.rt.effect-test` as its own fast Node shard, it will fail early with **6 failures (`rc=1`)** on the confirmation nightly unless routed and fixed; it is **not** covered by Assignment B (`py-manifest-remint`).

### 2.6 The `profiles.cljc` module-lock treatment

* Without re-litigating the owner-dispatched Assignment B thread itself, two architectural points on `src/cljc/yang/python/antlr/profiles.cljc` bear recording from the landed QA (`c9d8dc3b`) and closure (`dc0c803c`) rounds:
  1. **Production closure membership + duplicate pin surface:** `dc0c803c` (`dd7bc6af`) admitted `src/cljc/yang/python/antlr/profiles.cljc` into `docs/design/host-boundary/closure-{clj,cljs,cljd}.edn` because `yin.repl.main` transitively requires it. Unlike test-only golden files under `test/`, `profiles.cljc` embeds committed content-addressed locks (`module-locks` at lines 44–109 and `revision-locks` at lines 112–116) in **production code** enforced at runtime by `verify-builtin!`. Duplicating the `py` module manifest pin in both `src/cljc/yang/python/antlr/profiles.cljc` (`module-locks`) and `test/yang/python/antlr/linked_prelude_test.cljc` (`manifest-golden`) is what allowed the initial remint branch (`bb4b1ec5`) to update the test golden while leaving the production runtime lock stale (`d7568c16…`).
  2. **Two-stage lock dependency inside `profiles.cljc` (`module-locks` → `revision-locks`):** In `profiles.cljc:148-165`, `runtime-artifacts!` with `publish? = false` (`validate-builtin`) reconstructs a module's manifest using the committed `:derivations` and `:index` maps from `module-locks` (which is why QA probe `1795510000000` observed an intermediate hybrid digest `c7d3e443…` when probing with stale `:derivations`/`:index`), and `admitted-profile` (`lines 212-221`) then validates `:yang.python/py-v1` `:descriptor-address` and `:runtime-address` in `revision-locks`, which transitively hash the `module-locks` addresses.
  3. **Countersign check on `origin/fix/py-manifest-remint` (`d32c8179`):** Note that inside `d32c8179`, the engineer's committed findings file (`collab/yang-php/1795530017-engineer-remint-amendment.gpt-6.1-terra.findings.md:23`) states that the full `yin.repl.python-antlr-test` gate was interrupted after 15 minutes (*"Full corrected REPL gate: INCOMPLETE... A green result is not claimed and this gate still needs completion"*), whereas the orchestrator handoff note on master (`09ccfc68`, `collab/yang-php/1795534050000-to-master-green-assignment-b-ready.md`) reports a subsequent unsandboxed run passed `22/201/0`. The master-green countersign on the union tree should explicitly run both `yin.repl.python-antlr-test` and `yang.python.antlr.descriptor-profile-test` (plus the two provisional slow rows `yang.call.python-exception-test` and `yang.php.antlr.e2e-test`) in a fresh clone to close that discrepancy on record.

---

## 3. What's Still Missing (Highest-Leverage Next Moves & Scoped Probes)

The landed work (`dc0c803c`, `0d378482`, `2862ec95`, `82a8fb44`, `857c297d`) leaves five high-leverage items unaddressed across Jupiter and Shiva. Here is the ranked execution plan and the exact scoped probes the orchestrator should run.

### Priority 1 (URGENT — Correctness & ~128 Missing Quick Tests): Evict `d2-wedge-probe2` and `d2-wedge-min-repro` from automatic test discovery, and split `unmapped` (`dao.star`, `yang.call`, `yang.latte-oracle-test`)

* **Why:** As shown in §1.2, `test/d2_wedge_probe2.clj` calls `(System/exit 0)` / `(System/exit 1..4)` inside `stage-b-real-path` (invoked by `deftest test-h31-wedge-probe2`), killing the `unmapped` quick shard at **279.530 s** before 19 quick test namespaces (`dao.star.*` ×10, `yang.call.*` ×6, `yang.cross-lang-test`, `yang.latte-oracle-test`, `yang.rt.effect-test`) ever run.
* **Fix:**
  1. Move `test/d2_wedge_probe2.clj` and `test/d2_wedge_min_repro.clj` out of `test/` (e.g., to `src/dev/` or replace their `deftest` wrappers with `-main` entry points), **or** tighten `affected/index-entry` (`src/dev/affected.clj:265-269`) and `slow_inventory.clj` so only `*_test.clj*` / `*-test` namespaces are selected as test suites.
  2. Add post-run var-count verification to `quick_sharded.clj` and `nightly.clj`: fail the lane if `sum(:tests)` across shards differs from the discovered metadata inventory count.
  3. Once `d2-wedge-probe2` no longer kills `unmapped`, `unmapped` will execute `yang.latte-oracle-test` (~888 s) + `dao.star.*` (10 namespaces) + `yang.call.*` (6 namespaces) serially (~1,500–2,000 s total). Add `dao.star` (`:prefixes ["dao.star"]`), `yang.call` (`:prefixes ["yang.call"]`), and `yang.latte` (`:prefixes ["yang.latte-oracle"]`) to `src/dev/subsystems.edn` so they run as separate shards automatically across JVM, Node, and Dart.

### Priority 2 (Jupiter JVM Quick Critical Path — 8,008.77 s → <2,000 s): Unblock `yang.php` quick split (`fix/quick-rebalance-r2`) with a canonicalized semantic comparator, full `yang.php` decomposition, and LPT work-stealing in `quick_sharded.clj`

* **Why `fix/quick-rebalance-r2` (`1dc17314`) stopped and how to resolve it:**
  * In `test/yang/php/antlr/e2e_test.clj:433-444`, `engine-result` catches any host `Throwable t` and returns `{:threw (str t)}`. When a negative test triggers an `ExceptionInfo` (e.g. `data primitive refused` on `float-value`), `(str t)` serializes the `ex-data` map, which embeds the live VM/session state (`:consumer`, `:session`), Java object identity hashes, random UUIDs, and unsorted Clojure hash-maps (~3.76 MB of text per throw). Raw byte equality of `(str t)` across two JVM invocations fails on non-canonical heap identities and map key print order, **not** on cross-var state.
  * **Semantic-equivalence comparator specification:** For the A/B independence check on `yang.php.antlr.e2e-test` quick vars, compare per fixture/program ID:
    * Normal completion: exact equality of `{:out (:out r), :diag (:diag r), :uncaught (:uncaught r)}`.
    * `:refused`: exact equality of `(:refused r)`.
    * `:threw`: canonicalized exception projection `{:class (str (class t)), :message (ex-message t), :semantic-data (select-keys (ex-data t) [:reason :status :defect :primitive :arg :expected :actual :construct :yang.php/diagnostic :lane :steps])}` — plus exact equality of the test assertions' own rollups (`{:total :compared :mismatch-ids}` in `run-corpus` and `{:pass-ids :unsupported-ids :fail-ids :family-counts}` in `run-phpt-selection`).
* **Why splitting only `permutation-corpus-quick-test` (1,701 s) and `phpt-selection-runs-test` (748 s) is insufficient:**
  * Those two vars sum to **2,449.24 s** out of `yang.php`'s **8,008.77 s**, leaving **~5,559 s** in the remaining 21 quick vars of `e2e-test` and the other 28 `yang.php.antlr.*` namespaces!
  * To actually bring JVM quick below ~2,000 s:
    1. Split `yang.php.antlr.e2e-test` quick (23 vars) into **4 groups**: (i) `permutation-corpus-quick-test` (1,701 s), (ii) `phpt-selection-runs-test` (748 s), (iii) `fixtures-on-the-walker-test` (>461 s), and (iv) `stage0-enabled-fixtures-on-the-walker-test` + the 19 fast unit vars.
    2. Measure per-namespace quick wall across the other 28 `yang.php.antlr.*` namespaces (especially `prelude-test`, `include-test`, `p5b2-lifecycle-test`, `eval-emitter-test`, `eval-service-test`, `class-model-test`) and split the 28 namespaces into 2–3 balanced sub-shards if their combined wall exceeds 1,500 s (keeping an intact subsystem-order check if any namespace exhibits cross-namespace state).
    3. **Fix `quick_sharded.clj/run-shards!` (`src/dev/quick_sharded.clj:107-116`):** Replace static `(sort-by (comp - count val) shards)` + `(take-nth workers ...)` with a **weighted LPT work-stealing queue** (like `nightly.clj/run-pool`). Otherwise, 1-var `e2e-test` shards have `count = 1`, get sorted to positions 18–20 (last!), and get stacked serially behind `unmapped` or `yang.typescript` on the same worker.

### Priority 3 (Shiva Dart Critical Path — 35,756 s → ~9,000 s, >70% cut with zero R3 risk): Reorder `@dirs` in `dart_shard.clj` and split `yang.typescript.antlr.corpus-test` across Dart shard files in `cljd_agg.clj`

* **Why this is the highest-leverage move on Shiva:**
  * As proven from `dart-shards-20261123-2121/all.log` in §1.4, **every Dart test in the 288-namespace suite except `yang.typescript.antlr.corpus-test` finished by `134:37` (8,077 s = 2 h 14 m)**.
  * `yang.typescript.antlr.corpus-test` ran in a single Dart isolate (`cljd-agg-20261123-2121-uncovered/shard_3_test.dart`) from `92:27` (5,547 s) to `576:53` (34,613 s) — **29,066 s (8 h 04 m) in one isolate** — and started 5,547 s late because `dart_shard.clj:161` appends `"uncovered"` last (14th) in `@dirs`.
  * Breakdown of `yang.typescript.antlr.corpus-test` inside `dart-shards-20261123-2121/all.log`:
    * `t3-bundled-matches-node-test`: `92:27` → `142:22` (~2,995 s including initial load)
    * `t3-linked-matches-node-test`: `142:22` → `157:31` (**909 s**)
    * `t1-bundled-matches-node-test`: `157:31` → `285:42` (**7,691 s = 2 h 08 m**)
    * `t2-linked-matches-node-test` (+ `t3-units-fixture` / `fixtures-agree`): `285:42` → `327:23` (**2,501 s = 42 m**)
    * `t2-bundled-matches-node-test` (+ `no-entry-is-pending`): `327:23` → `440:31` (**6,788 s = 1 h 53 m**)
    * `t1-linked-matches-node-test` (+ `t3-packets-parse`): `440:31` → `576:53` (**8,182 s = 2 h 16 m**)
* **Two-step fix that completely respects the R3 single-Flutter-driver constraint (`build/test_cache`):**
  1. **Immediate driver-only win (~5,547 s / 1.54 h saved):** In `src/dev/dart_shard.clj`, sort `@dirs` (or pass individual emitted `shard_N_test.dart` files ordered by descending historical weight) so `uncovered` / `yang.typescript` (`corpus-test`), `yin.vm`, `yang.python`, and `yin.repl` are first on the single `flutter test --concurrency ...` command line, starting `corpus-test` at `t = 0:00` instead of `t = 92:27`.
  2. **Isolate-level parallelism for `corpus-test` (~20,000 s / 5.5 h saved):** In `flutter test`, concurrency is one Dart isolate per `*_test.dart` file. Because all 6 slow `deftest`s of `yang.typescript.antlr.corpus-test` compile into one `test/cljd-out/yang/typescript/antlr/corpus_test.dart` file, `cljd_agg.clj` can only call `m0.main()` in one `shard_N_test.dart` file. Splitting `test/yang/typescript/antlr/corpus_test.cljc` into per-tier/mode test namespaces (or having `cljd_agg.clj` emit per-var Dart wrapper `_test.dart` files for `corpus-test`) allows the **single** `flutter test` process to run `t1-bundled` (7,691 s), `t1-linked` (8,182 s), `t2-bundled` (6,788 s), `t2-linked` (2,501 s), and `t3` (3,904 s) concurrently across separate Dart isolates — cutting Dart's total wall from **35,756 s (~9.9 h)** to **~9,000–10,000 s (~2.5–2.8 h)** without spawning a second Flutter driver.

### Priority 4 (Shiva Node Group Floors — `yin.vm` 31,901 s & `yang.python` 25,244 s → <10,000 s): Shard `yin.vm` and `yang.python` across Node workers and fix `yang.rt.effect-test`

* **Why:**
  * On Shiva `20261123-2121`, Dart ran all 74 `yin.vm` namespaces (with `DATOM_SLOW_TESTS=1`) across 8 `cljd-agg` shards in **5,494 s (1 h 31 m)** (`0` failures) and all 22 `yang.python` namespaces across 8 shards in **7,173 s (1 h 59 m)**.
  * On the exact same host and commit, Node (`node_shard.clj`) took **31,901 s (8 h 52 m)** for `yin.vm` and **25,244 s (7 h 01 m)** for `yang.python` — solely because `node_shard.clj` runs all 74 `yin.vm` namespaces serially in **one** single-threaded Node process and all 22 `yang.python` namespaces serially in **one** single-threaded Node process!
  * Moreover, `uncovered/shard_3_test.dart` (`yang.typescript.antlr.corpus-test`) took **29,066 s** on Dart and `>30,000 s` on Node; even as a single namespace job (`uncovered.yang.typescript.antlr.corpus-test` after `857c297d`), `corpus-test` will still run for ~25,000–30,000 s in one Node process unless its 6 corpus tiers (`t1/t2/t3 × bundled/linked`) are split.
* **Fix:**
  1. **Break the 31,901 s `yin.vm` and 25,244 s `yang.python` single-process Node floors:** In `src/dev/node_shard.clj`, partition `yin.vm` (74 namespaces) and `yang.python` (22 namespaces) into 4–6 weighted sub-jobs on the existing single-compile `target/node-tests.js --test=...` work-stealing queue (specifically isolating `yin.vm.semantic.derive-test`, `yin.vm.semantic.safepoint-harness-test`, `yin.vm.semantic.scan-test`, `yin.vm.linker.dht-end-to-end-test`, and `yin.vm.linker-test` in `yin.vm`; and `int-ops-test`, `c3-gate-test`, `descriptor-profile-test`, `import-test`, `dht-import-test`, `safepoint-test`, and `linked-safepoint-test` in `yang.python`, matching the heavy namespaces identified in Dart's `all.log` table).
  2. **Align `node_shard.clj` and `dart_shard.clj` with `src/dev/subsystems.edn`** instead of the hardcoded 13-element `all-subs` vector, and replace the uniform `2163` (`47599 / 22`) synthetic residual weights with measured per-job durations.
  3. **Route and fix the 6 non-Assignment-B Node failures in `yang.rt.effect-test/clojure-core-facade-and-cold-policy` (`test/yang/rt/effect_test.cljc:253-254`):** On CLJS (`node-shards-20261123-2121/uncovered.log:20-48`), `(inc 1.5)` returns `#dao.jing/float64 2.5` while `expected` has raw `2.5` across `:semantic`, `:stack`, and `:register` (bundled and linked).

### Priority 5 (Jupiter JVM Slow — ~29,887 s `yang.php.antlr.e2e-test` & 9,130 s `include-test`): Per-var timing, weight-balanced var groups, and LPT slow-queue ordering

* **Fix:**
  1. In `src/dev/nightly.clj`, order the 76 `slow-schedule` jobs by **descending measured historical duration (LPT)** instead of alphabetical order (`lane-jvm`) or `PersistentHashMap` iteration order (`--list-slow`), so `yang.typescript.antlr.corpus-test` (8,959 s) and `yin.vm.semantic.safepoint-harness-test` (5,078 s) start in wave 1 instead of slots 47–50 and 67–70.
  2. Replace alphabetical round-robin `(take-nth 4 (drop i vs))` in `nightly.clj/slow-schedule` with weight-balanced var groups once the 12 slow vars of `yang.php.antlr.e2e-test` are timed (separating `permutation-corpus-full-test` [5,932 programs], `fixtures-on-every-vm-test`, and `stage0-enabled-fixtures-on-every-vm-test` into distinct shards).
  3. For `yang.php.antlr.include-test`, run a paired split-vs-intact A/B check (because of the shared `lh/published` content-store delay at `test/yang/php/antlr/linked_harness.cljc:59`); if `p6-fixtures-linked-on-every-vm-test` accounts for >85% of `include-test`'s 9,130 s, remove `include-test` from `default-var-shards` so it runs intact in one JVM without paying 4× `lh/make-published` overhead.

---

## 4. Scoped Probes for the Orchestrator to Run

Because this cross-review is strictly read-only, the orchestrator should run the following scoped probes in disposable clones:

1. **Verify the `d2-wedge-probe2` `System/exit` truncation of `unmapped` and restore the 19 skipped quick namespaces (Jupiter):**
   ```sh
   # Confirm d2-wedge-min-repro and d2-wedge-probe2 are in unmapped:
   bb test:quick-sharded --list --workers 12
   # Run the 19 real unmapped test namespaces WITHOUT d2-wedge-min-repro and d2-wedge-probe2:
   /usr/bin/time -v clojure -M:test -e :slow \
     -n dao.star.driver-test -n dao.star.facts-test -n dao.star.loopback-test \
     -n dao.star.peer-close-test -n dao.star.r2-test -n dao.star.r3-test \
     -n dao.star.r4-test -n dao.star.r5-test -n dao.star.router-test \
     -n dao.star.signals-test -n yang.call.conformance-test \
     -n yang.call.depth-probe-test -n yang.call.exception-contract-test \
     -n yang.call.exception-source-test -n yang.call.php-exception-test \
     -n yang.call.python-exception-test -n yang.cross-lang-test \
     -n yang.latte-oracle-test -n yang.rt.effect-test
   ```
2. **Time the remaining quick components of `yang.php` (8,008.77 s total minus 2,449.24 s already measured for `permutation-corpus-quick-test` + `phpt-selection-runs-test` = ~5,559 s unaccounted) (Jupiter):**
   ```sh
   # Isolate the other two heavy walker loops in yang.php.antlr.e2e-test quick:
   /usr/bin/time -v clojure -M:test -e :slow -n yang.php.antlr.e2e-test \
     -v yang.php.antlr.e2e-test/fixtures-on-the-walker-test
   /usr/bin/time -v clojure -M:test -e :slow -n yang.php.antlr.e2e-test \
     -v yang.php.antlr.e2e-test/stage0-enabled-fixtures-on-the-walker-test
   # Time the other 28 yang.php namespaces without e2e-test:
   /usr/bin/time -v clojure -M:test -e :slow \
     $(bb test:sub:list yang.php | awk '/^clj:/{f=1;next} /^[A-Za-z]/{f=0} f{gsub(/ /,""); if ($0 != "yang.php.antlr.e2e-test") printf "-n %s ", $0}')
   ```
3. **Verify `yang.php.antlr.include-test` slow split-vs-intact equivalence (checking for `lh/published` store sensitivity) and time `p6-fixtures-linked-on-every-vm-test` (Jupiter):**
   ```sh
   /usr/bin/time -v clojure -M:test -i :slow -n yang.php.antlr.include-test \
     -v yang.php.antlr.include-test/p6-fixtures-linked-on-every-vm-test
   ```
4. **Reproduce the unattributed non-Assignment-B Node failure in `yang.rt.effect-test` and time the heavy `yin.vm` Node namespaces (Shiva):**
   ```sh
   # Fast reproduction of the 6 float64 vs number failures in uncovered.log:
   node target/node-tests.js --test=yang.rt.effect-test
   # Completed wall times for the top 3 yin.vm slow namespaces on Node (identified via Dart all.log):
   DATOM_SLOW_TESTS=1 /usr/bin/time -v node target/node-tests.js --test=yin.vm.semantic.derive-test
   DATOM_SLOW_TESTS=1 /usr/bin/time -v node target/node-tests.js --test=yin.vm.semantic.safepoint-harness-test
   DATOM_SLOW_TESTS=1 /usr/bin/time -v node target/node-tests.js --test=yin.vm.semantic.scan-test
   ```
5. **Countersign verification on the union tree (`dc0c803c` + `origin/fix/py-manifest-remint` `d32c8179`) for the incomplete/provisional rows (Jupiter):**
   ```sh
   clojure -M:test -n yin.repl.python-antlr-test
   clojure -M:test -n yang.python.antlr.descriptor-profile-test
   clojure -M:test -i :slow -n yin.repl.dht-process-test
   clojure -M:test -i :slow -n yang.call.python-exception-test
   ```
