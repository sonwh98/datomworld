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
