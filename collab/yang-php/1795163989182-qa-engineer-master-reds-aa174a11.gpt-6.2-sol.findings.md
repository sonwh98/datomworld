Completed-GMT: 2026-11-20 15:27:55 GMT
Completed-Local: 2026-11-20 10:27:55 America/New_York
Coding-Agent: codex
Session-ID: 01a1f98e-f29f-79c1-aa87-658a859b1f7d

# QA findings for the two aa174a11 master lint failures

Both lint failures reproduced and passed after the fixes. All five focused JVM namespaces and both Node namespaces passed. clj-kondo reported zero errors and warnings. The changes are uncommitted on `fix/aa174a11-master-lint-reds` at `c24a4b1de84c5c38c13e4def481ebcd4f1ccdcd0`, matching `origin/master`.

## Red phase

All three commands ran in the foreground at the focused JVM tier. Both baseline commands ran before any edits. The regression ran after editing only `code_entry_test.cljc`.

```text
$ clojure -M:test -n yin.vm.store-write-audit-test
FAIL in (every-store-mutation-is-on-the-allowlist) (store_write_audit_test.clj:411)
expected: (= allowlist (source-sites))
Ran 3 tests containing 129 assertions.
1 failures, 0 errors.
```

The actual source-sites map contained the additional entry `"src/cljc/yang/php/antlr/eval_coordinator.cljc" {["compile-and-publish*!" :map] 1}`. Exit 1. [Full store audit output](../../target/aa174a11-lint-fix/20261120T152450Z/red-store-write-audit.out)

```text
$ clojure -M:test -n dao.jing.hash-registry-contract-test
FAIL in (architectural-lint-sweeps-production-sources) (hash_registry_contract_test.cljc:479)
Found forbidden equality validation in src/cljc/yin/vm/module.cljc: (defn code-entry-handler "Read a declared export from an already installed, address-pinned module." [state effect _opts] (let [{:keys [module address export]} effect entry (when (symbol? module) (module-entry (:modules (:modules state)) module)) manifest (:manifest entry) reason (cond (nil? entry) :module-missing (or (nil? address) (not= address (:address entry)) (not= address (jing/segment-key manifest)) (not= module (:yin.module/name manifest))) :manifest-mismatch (not (contains? (:yin.module/exports manifest) export)) :export-undeclared (not (contains? (:bindings entry) export)) :binding-missing)] {:state state, :blocked? false, :value (if reason {:status :refused, :reason reason, :module module, :export export} (get (:bindings entry) export))}))
Ran 15 tests containing 6080 assertions.
1 failures, 0 errors.
```

Exit 1. [Full architectural lint output](../../target/aa174a11-lint-fix/20261120T152450Z/red-hash-registry-contract.out)

```text
$ clojure -M:test -n yin.vm.code-entry-test
FAIL in (installed-export-is-verified-with-the-address-algorithm) (code_entry_test.cljc:54)
an installed SHA-256 manifest serves its export
expected: (= :callable (lookup manifest address address))
  actual: (not (= :callable {:status :refused, :reason :manifest-mismatch, :module eval.unit, :export entry}))
Ran 2 tests containing 20 assertions.
1 failures, 0 errors.
```

Exit 1. All five new refusal cases passed at this step. [Full regression output](../../target/aa174a11-lint-fix/20261120T152450Z/red-code-entry.out)

## Inspection and fixes

- `compile-and-publish*!` passes the composition's `store` to publication and to the `siteset/stage` context. Publication uses it with `jing/materialize!`. Staging uses it as the base of a buffered byte-store overlay, matching the PHP staging precedent in `f94649e3` and the content-store context in `yin.vm.linker.interpreter/dht-context`. The audit now allows one `:map` site in `compile-and-publish*!`. [Coordinator staging call](../../src/cljc/yang/php/antlr/eval_coordinator.cljc#L76) · [Allowlist](../../test/yin/vm/store_write_audit_test.clj#L302)
- `code-entry-handler` now verifies the manifest with `jing/segment-matches?` and retains the nil-address, installed-address and module-name guards. The regression covers a SHA-256 lookup, tampering, a nil request against a SHA-256 entry, nil stored and requested addresses, malformed input and an unregistered algorithm. The last two fixtures submit the same address stored in the registry, so they reach address validation. `link-module` still mints at line 95. [Handler](../../src/cljc/yin/vm/module.cljc#L709) · [Regression](../../test/yin/vm/code_entry_test.cljc#L39)
- `parse-segment-address` returns nil for malformed addresses and unregistered algorithms. For parsed addresses, the predicate wraps canonical encoding and digest calculation in a host-specific catch (`Throwable` on CLJ, `:default` on CLJS, `Object` on CLJD). The existing contract cases exercised malformed inputs, unknown algorithms and encoder refusal on JVM and Node. CLJD was checked by source inspection. [Verification predicate](../../src/cljc/dao/jing.cljc#L303)

## Green phase

Every test command below exited 0. The focused namespace checks ran in the foreground.

| Tier | Command | Tests | Assertions | Failures | Errors |
| --- | --- | ---: | ---: | ---: | ---: |
| G0 focused JVM | `clojure -M:test -n yin.vm.store-write-audit-test` | 3 | 129 | 0 | 0 |
| G0 focused JVM | `clojure -M:test -n dao.jing.hash-registry-contract-test` | 15 | 6080 | 0 | 0 |
| G0 focused JVM | `clojure -M:test -n yin.vm.code-entry-test` | 2 | 20 | 0 | 0 |
| G0 focused JVM | `clojure -M:test -n yin.vm.module-test` | 10 | 63 | 0 | 0 |
| G0 focused JVM | `clojure -M:test -n yang.php.antlr.eval-coordinator-test` | 3 | 22 | 0 | 0 |
| Focused Node parity | `node target/node-tests.js --test=yin.vm.code-entry-test` | 2 | 20 | 0 | 0 |
| Focused Node parity | `node target/node-tests.js --test=dao.jing.hash-registry-contract-test` | 14 | 83 | 0 | 0 |

The architectural source sweep is JVM-only. The focused Node build exited 0 with zero compiler warnings.

```bash
clojure -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^(yin[.]vm[.]code-entry-test|dao[.]jing[.]hash-registry-contract-test)$" :autorun false}'
```

## Lint and scope

The requested `clj -M:kondo` launcher exited 1 before linting because rlwrap could not create its history file on the read-only filesystem. With a workspace-local history directory, rlwrap could not open `/dev/tty`. The non-interactive launcher ran the same alias on the same paths and exited 0.

```text
$ clojure -M:kondo --lint test/yin/vm/store_write_audit_test.clj src/cljc/yin/vm/module.cljc test/yin/vm/code_entry_test.cljc
Loading initial Timbre config from: :default
linting took 488ms, errors: 0, warnings: 0
```

On Jupiter, `mise install` confirmed all 12 tools were installed, then failed to rebuild shims on the read-only filesystem. `eval "$(mise env -s bash)"` activated the installed tools used for verification. [Run logs](../../target/aa174a11-lint-fix/20261120T152450Z/)

`git diff --check` passed. There are no staged changes, HEAD still equals the starting commit, and `git rev-list --count origin/master..HEAD` returned `0`. The three files in `git diff --stat` exhaust the tracked changes. This findings file is the requested untracked deliverable. The other untracked files were present on arrival.

```text
 src/cljc/yin/vm/module.cljc            |  2 +-
 test/yin/vm/code_entry_test.cljc       | 30 +++++++++++++++++++++++++++++-
 test/yin/vm/store_write_audit_test.clj |  3 +++
 3 files changed, 33 insertions(+), 2 deletions(-)
```

## Actionable findings

No actionable findings

## Unrun checks

- **Focused Dart parity** — `bb src/dev/cljd_agg.clj --only yin.vm.code-entry-test,dao.jing.hash-registry-contract-test`. Even `dart --version` failed because Flutter's startup script could not update its installed cache on the read-only filesystem.
- **Fast three-lane merge precondition** — `bb test:sub dao.stream`. Sandbox preflight returned `Operation not permitted` on TCP and UDP loopback binds. This gate exercises real sockets and also requires Flutter. It produced no per-lane logs.
- **Independent Step-7 cross-family review** remains with the orchestrator.
