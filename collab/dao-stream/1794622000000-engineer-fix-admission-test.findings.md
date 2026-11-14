# Admission fix investigation — incomplete

Changed `test/yin/repl/dht_test.cljc`: added a `:content-client` conflict refusal and verified that the directory can be reopened after it. No admission implementation or fixture fix was completed.

The admission fixture still puts `:dht` in the engine, supplies `:dht-key`, and directly calls the engine's recheck operations. The signed-name linker now runs at the shell composition boundary. `yin.shell.session/serve` already hands nonempty pending identities back to the parked run, but its tick path currently performs one composition serve round; the fixture migration and bounded continuation/rollback behavior remain to be reconciled and verified.

Verbatim test summaries:

```
clj -M:test -n yin.vm.linker.admission-test
Ran 38 tests containing 509 assertions.
184 failures, 7 errors.

clj -M:test -n yin.repl.dht-test -v yin.repl.dht-test/a-dht-store-refuses-a-second-content-source
Ran 1 tests containing 2 assertions.
0 failures, 0 errors.
```

The Gate A `rg` command produced no matches (exit 1). Slow admission, the grouped checks, adjacent linker tests, and cross-host verification were not run. No commit was made.
