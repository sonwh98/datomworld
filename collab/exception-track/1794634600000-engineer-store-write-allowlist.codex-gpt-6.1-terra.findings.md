# Store-write audit findings

Base under test: `3d2710994b59d64486e0fdd4f9de185a00972943` (origin/master). The requested branch starts at this revision. The reported entries already exist on current master; no source change is warranted.

- (b) `continuation_stream.cljs`, `vm->cesk` map: a read-only display projection copying `(:store vm-state)`, not a store mutation. The audit already allowlists it with that reason.
- (b) `rows.cljc`, `row-table` map: `:store` names a wire row kind in a schema, not a VM store. The audit already allowlists it with that reason.
- (a) `rows.cljc`, `restore-task`: the carried keys are already checked individually through `engine/store-put`; the resulting checked slice is installed on the fresh receiver via `assoc`. Both detected sites and their reason are already allowlisted.

Source diff against base: empty. The audit reads forms and conservatively records every map literal containing `:store`, along with store-targeting mutations and all `store-put` calls; it is not a regex. Its documented residuals include writes through function parameters and dynamically built mutations.

Verification on base:
- `clojure -M:test -n yin.vm.store-write-audit-test`: rc 1; Ran 3 tests containing 129 assertions; 1 failure, 0 errors. Both assigned files match their allowlist entries. The sole mismatch is the additional `src/cljc/yang/php/antlr/siteset.cljc` `["stage" "assoc"]` site, outside assigned ownership. The audit test was not edited.
- `clojure -M:test -n yin.vm.ucf.rows-test`: rc 0; Ran 50 tests containing 818 assertions; 0 failures, 0 errors. This is the unchanged master source.
- `bb test:sub:clj dao.stream`: rc 0; 41 namespaces, 589 tests, 0 failures. Babashka 1.13.225 was added to PATH for this run.

## 2026-11-14 follow-up

Base: `24841550c5313ef25a03793efae8a58731b8085b` (fresh origin/master worktree). The previous findings were recovered from `fix/store-write-allowlist` because they had not landed on master.

Before the change, `clojure -M:test -n yin.vm.store-write-audit-test` returned rc 1: Ran 3 tests containing 129 assertions; 1 failure, 0 errors. The sole mismatch was `src/cljc/yang/php/antlr/siteset.cljc` `["stage" "assoc"]`.

Disposition (b): `stage` associates a buffered content byte-store handle into the staging context. `overlay` wraps `:get-bytes-fn` and `:put-bytes-fn`, buffering byte writes until `flush!`; it is not a VM program store, nor a wire-schema literal. The audit detects the `:store` argument to `assoc`. Added the exact site and this reason to the audit allowlist, matching the existing Python staging entry. No PHP source changed.

Verification after the allowlist edit:
- `clojure -M:test -n yin.vm.store-write-audit-test`: rc 0; Ran 3 tests containing 129 assertions; 0 failures, 0 errors.
- `clojure -M:test -n yang.php.antlr.siteset-test`: rc 0; Ran 7 tests containing 30 assertions; 0 failures, 0 errors.
- `bb test:sub:clj dao.stream`: rc 0; 41 namespaces, 589 tests, 0 failures (Babashka 1.13.225 on PATH).
