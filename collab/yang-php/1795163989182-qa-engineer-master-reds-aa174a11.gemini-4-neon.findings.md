Completed-GMT: 2026-11-20 08:52:14 GMT
Completed-Local: 2026-11-20 15:52:14 +07
Coding-Agent: agy
Session-ID: 4a5d759c-2c2c-41a8-9c10-f293eec952d4

# QA Verification Report: `aa174a11` Master Lint Reds Remediation

- **Brief:** `collab/yang-php/1795163989182-qa-engineer-master-reds-aa174a11.prompt.md`
- **Dedicated worktree:** `/Users/sto/workspace/datomworld-aa174a11-lint-reds`
- **Branch / Base HEAD:** `fix/aa174a11-master-lint-reds` at `5c07e49626f51fb97b0f676acb561467ee60dfb0` (`origin/master`), **0 commits created**
- **Main checkout (`/Users/sto/workspace/datomworld`):** `master`, zero tracked files modified by this session

---

## 1. Red-phase reproduction (before Green fixes)

### Baseline Defect 1 — `yin.vm.store-write-audit-test`

```sh
cd /Users/sto/workspace/datomworld-aa174a11-lint-reds && clojure -M:test -n yin.vm.store-write-audit-test
```

- **Exit:** `1` (`Ran 3 tests containing 129 assertions. 1 failures, 0 errors.`)
- **Failure:** `FAIL in (every-store-mutation-is-on-the-allowlist) (store_write_audit_test.clj:411)` — `source-sites` included `"src/cljc/yang/php/antlr/eval_coordinator.cljc" {["compile-and-publish*!" :map] 1}`, absent from `allowlist`.
- **Independent inspection (`src/cljc/yang/php/antlr/eval_coordinator.cljc:56-89`):** Line 76 passes `{:store store :datoms [] :principals []}` to `stage` returned by `siteset/stage`. That `:store` value is the composition's `dao.jing` content byte-store handle (matching `src/cljc/yang/php/antlr/siteset.cljc` and `src/cljc/yin/vm/linker/interpreter.cljc`), not a VM program store write.

### Baseline Defect 2 — `dao.jing.hash-registry-contract-test`

```sh
cd /Users/sto/workspace/datomworld-aa174a11-lint-reds && clojure -M:test -n dao.jing.hash-registry-contract-test
```

- **Exit:** `1` (`Ran 15 tests containing 6080 assertions. 1 failures, 0 errors.`)
- **Failure:** `FAIL in (architectural-lint-sweeps-production-sources) (hash_registry_contract_test.cljc:479)` — `Found forbidden equality validation in src/cljc/yin/vm/module.cljc: (defn code-entry-handler ... (not= address (jing/segment-key manifest)) ...)`.

### TDD Red step on `test/yin/vm/code_entry_test.cljc` (prior to editing `src/cljc/yin/vm/module.cljc`)

Added portable `.cljc` `deftest address-directed-manifest-verification` covering `:sha256` manifest acceptance, tampered `:sha256` manifest refusal, `nil` address refusal (including against `:address nil` host entries and `(module/register-code-module registry)`), and malformed / unknown-algorithm address refusal without throwing, then ran:

```sh
cd /Users/sto/workspace/datomworld-aa174a11-lint-reds && clojure -M:test -n yin.vm.code-entry-test
```

- **Exit:** `1` (`Ran 2 tests containing 25 assertions. 1 failures, 0 errors.`)
- **Failure:** `FAIL in (address-directed-manifest-verification) (code_entry_test.cljc:49)` — `accepts an installed module under an explicit :sha256 manifest address`: `module/code-entry-handler` returned `:value {:status :refused, :reason :manifest-mismatch, :module eval.sha256, :export entry}` instead of `:sha256-callable` because 1-arg `jing/segment-key` minted `:blake3`.

---

## 2. Minimal Green fixes applied (strictly within 3-file allowlist)

1. `test/yin/vm/store_write_audit_test.clj`: Added `"src/cljc/yang/php/antlr/eval_coordinator.cljc" {["compile-and-publish*!" :map] 1}` to `allowlist` immediately after `"src/cljc/yang/php/antlr/siteset.cljc"` with rationale comment.
2. `src/cljc/yin/vm/module.cljc`: Updated `code-entry-handler` line 709 only from `(not= address (jing/segment-key manifest))` to `(not (jing/segment-matches? address manifest))`, preserving `(nil? address)`, `(not= address (:address entry))`, `(not= module (:yin.module/name manifest))`, and leaving `link-module` (line 95) and all other functions untouched.
3. `test/yin/vm/code_entry_test.cljc`: Added `address-directed-manifest-verification` (existing `installed-export-is-address-pinned` preserved intact).

---

## 3. Green-phase verification

All commands executed in foreground in `/Users/sto/workspace/datomworld-aa174a11-lint-reds` with `PATH="$HOME/.local/share/mise/shims:$PATH"`.

### Build prerequisite (JVM PHP ANTLR classes)
- `bb gen:antlr -- --grammar php --target java` -> **exit 0** (`generated 3 java files in build/antlr/php/java/yang/php/antlr/gen`).

### Focused JVM lane (fast/default tier — explicit `-n <ns>`, `^:slow` not applicable to these namespaces)
1. `clojure -M:test -n yin.vm.store-write-audit-test` -> **exit 0** (`Ran 3 tests containing 129 assertions. 0 failures, 0 errors.`)
2. `clojure -M:test -n dao.jing.hash-registry-contract-test` -> **exit 0** (`Ran 15 tests containing 6080 assertions. 0 failures, 0 errors.`)
3. `clojure -M:test -n yin.vm.code-entry-test` -> **exit 0** (`Ran 2 tests containing 25 assertions. 0 failures, 0 errors.`)
4. `clojure -M:test -n yin.vm.module-test` -> **exit 0** (`Ran 10 tests containing 63 assertions. 0 failures, 0 errors.`)
5. `clojure -M:test -n yang.php.antlr.eval-coordinator-test` -> **exit 0** (`Ran 3 tests containing 22 assertions. 0 failures, 0 errors.`)

### Cross-host `.cljc` focused parity — Node (`shadow-cljs`, fast/default tier, 2 namespaces only)
- `bb deps:npm && clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^(yin\\.vm\\.code-entry-test|dao\\.jing\\.hash-registry-contract-test)$"}'`
  -> **exit 0** (`Testing dao.jing.hash-registry-contract-test`, `Testing yin.vm.code-entry-test`; `Ran 16 tests containing 108 assertions. 0 failures, 0 errors.`; `[:test] Build completed. (91 files, 90 compiled, 0 warnings, 7.53s)`).

### Cross-host `.cljc` focused parity — Dart (`cljd`, fast/default tier, 2 namespaces only)
- `flutter pub get && rm -rf .clojuredart test/cljd-out lib/cljd-out build/cljd-agg && bb src/dev/cljd_agg.clj --only yin.vm.code-entry-test,dao.jing.hash-registry-contract-test`
  -> **exit 0** (`Compilation succeeded`, `Running 2 test files in 2 shards`, `00:00 +16: All tests passed!`).

---

## 4. `clj-kondo` lint and worktree hygiene

- **Lint:** `clj -M:kondo --lint test/yin/vm/store_write_audit_test.clj src/cljc/yin/vm/module.cljc test/yin/vm/code_entry_test.cljc`
  -> **exit 0** (`linting took 611ms, errors: 0, warnings: 0`).
- **`git rev-parse HEAD` (worktree):** `5c07e49626f51fb97b0f676acb561467ee60dfb0` (**0 commits**).
- **`git status --short` (worktree):**
  - ` M src/cljc/yin/vm/module.cljc`
  - ` M test/yin/vm/code_entry_test.cljc`
  - ` M test/yin/vm/store_write_audit_test.clj`
- **`git diff --stat` (worktree):**
  - `src/cljc/yin/vm/module.cljc | 2 +-`
  - `test/yin/vm/code_entry_test.cljc | 67 +++++++++++++++++++++++++++++++++-`
  - `test/yin/vm/store_write_audit_test.clj | 5 +++`
  - `3 files changed, 72 insertions(+), 2 deletions(-)`
- `.flutter-plugins-dependencies` remained unmodified.

---

## 5. Actionable findings

For the 3-file `aa174a11` fix scope: **No actionable findings**.

### Out-of-scope pre-existing `master@5c07e496` reds observed during master health triage (untouched per strict 3-file allowlist)

- `P1` | `src/cljc/yang/core/lower.cljc:104` | `clojure -M:test -n dao.host.gate-test -n yin.vm.store-write-audit-test` on `master@5c07e496` failed 9 assertions in `dao.host.gate-test` (`gate-passes-on-the-current-tree`, `list-1-equals-the-observed-arms`, `an-injected-*`, `effect-calls-escape-*`) flagging non-exempt `#?(:cljd ... :clj ... :cljs ...)` in `yang.core.lower/code-point->str` (`:classes #{:none}` in `docs/design/host-boundary/closure-{clj,cljs,cljd}.edn`). | Implement `code-point->str` portably over `dao.host.text/units->string` (handling BMP vs astral surrogate pairs) or register the file/arm per host-boundary §8.1.
- `P1` | `src/cljc/yang/php/antlr/cst.cljc:43` | Same `dao.host.gate-test` run flagged `#?(:clj ... :cljs ...)` in `make-eval-worker` because `src/cljc/yang/php/antlr/cst.cljc` (already `:deferred-stage` in `docs/design/host-boundary/closure-{clj,cljs}.edn`) is missing from `list-1` in `test/dao/host/gate_test.clj`. | Add `"src/cljc/yang/php/antlr/cst.cljc" (entry :deferred-scope :stays)` to `list-1` in `test/dao/host/gate_test.clj` (per host-boundary `05130e07` intent / §8.1).
- `P1` | `src/cljc/yang/core/prelude_admission.cljc` | Same `dao.host.gate-test` run failed `independent-traversal-agrees-with-the-committed-closure` (`gate_test.clj:996`) on `:clj`, `:cljs`, and `:cljd` (`walked, not committed` `#{"src/cljc/yang/core/prelude_admission.cljc"}`). | Regenerate `docs/design/host-boundary/closure-{clj,cljs,cljd}.edn` via `host-boundary.closure` and update `closure-totals-are-the-design-totals` / `gate-passes-on-the-current-tree` totals per host-boundary process.
- `P2` | `src/dev/affected_test.clj:376` | `bb -cp src/dev -m affected-test` failed 21/540 assertions (`subsystems-orphan-and-coverage-check`: 20 test namespaces missing from `src/dev/subsystems.edn`, including `dao.star.*`, `yang.call.*`, `yang.latte-oracle-test`, `yang.cross-lang-test`, `d2-wedge-*`, plus `ucf-tests` count 40 vs 28). | Map the unmapped test namespaces in `src/dev/subsystems.edn` and update the `ucf-tests` count assertion in `src/dev/affected_test.clj:399` after auditing `yin.vm.ucf` ownership.

---

## 6. Unrun checks

- **`bb test:sub dao.stream`** (fast-tier merge precondition): **not run locally** (suite-scale runs are forbidden on the laptop per the 2026-11-04 owner ruling in `docs/agents/build-n-test.md`; must run on `jupiter.stigmergy.systems` or `shiva.stigmergy.systems` before merge).
- **Full remote tri-host suite (`bb test` / `bb test:quick-sharded` / `bb test:slow` / `src/dev/nightly.clj`)**: **not run** (last recorded remote nightly run on `jupiter`/`shiva` was `20261114-1221` / `20261114-1225` at `f8fc4546`, 261 commits behind `5c07e496`; nightly cron entries remain removed per 2026-11-06 owner instruction).
- **Independent Step-7 cross-family review:** pending orchestrator dispatch over the uncommitted diff in `/Users/sto/workspace/datomworld-aa174a11-lint-reds`.
